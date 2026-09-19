package com.trainereval.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trainereval.common.util.SecurityUtils;
import com.trainereval.observability.Observability;
import com.trainereval.security.LoginUser;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MCP 工具的注册中心：负责连接生命周期、工具发现、以及"谁能看到哪些工具"。
 *
 * <h3>为什么是懒连接（而不是启动就连）</h3>
 * 启动时连接有三个坏处：① 任何一个 MCP server 配错都会拖慢甚至搞挂应用启动；
 * ② 绝大多数请求根本用不到外部工具，白起一堆子进程；③ 冷启动可能几十秒。
 * 所以这里改成<b>第一次真正要用时才连</b>，而且连不上只记在状态里，
 * 绝不让 Agent 请求失败——降级为"没有这个工具"。
 *
 * <h3>权限模型：角色闸门</h3>
 * 本地工具靠 {@code userId} 做行级过滤，MCP 工具没有用户身份、做不到。
 * 因此这里只能做<b>角色级</b>闸门，且默认最保守：
 * <ul>
 *   <li>{@code allowed-roles} 留空 → <b>只有管理员</b>能看到（而不是"所有人"）。</li>
 *   <li>显式写着 {@code [EMPLOYEE, TRAINER]} → 这些角色也能看到。</li>
 * </ul>
 * 这个默认值是刻意的：把"忘记配置"的后果从"跨用户数据泄露"变成"功能没生效"。
 * 后者是可见的、可修的；前者往往是不可见的。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class McpToolRegistry {

    private final McpProperties properties;
    private final ObjectMapper objectMapper;
    private final Observability observability;

    /** serverName → 客户端。懒初始化，之后复用。 */
    private final Map<String, McpClient> clients = new ConcurrentHashMap<>();
    /** serverName → 已发现的工具。每次 {@link #ensureConnected} 成功时刷新。 */
    private final Map<String, List<McpToolSpec>> toolsByServer = new ConcurrentHashMap<>();
    /** 连接失败的 server 及原因，供管理页展示。 */
    private final Map<String, String> connectionErrors = new ConcurrentHashMap<>();

    // ------------------------------------------------------------------ 对外：给 Agent 用

    /**
     * 当前登录用户可用的 MCP 工具。
     * <p>
     * 每次对话都会调用，所以这里必须便宜：已连接的直接用缓存，
     * 只对"还没连上且配置为启用"的 server 尝试连接（失败后不会每轮都重试，见 {@link #ensureConnected}）。
     * <p>
     * <b>为什么不在这里直接读 SecurityContext</b>：Agent 对话跑在 {@code Thread.ofVirtual()}
     * 起的虚拟线程上，而 Spring Security 默认用 ThreadLocal 保存上下文，
     * 虚拟线程里读不到 —— 那样 {@code allowed-roles} 会全部判定为"不是管理员"，
     * 表现为"配了 MCP 但模型永远看不到工具"，且完全不报错，极难排查。
     * 所以角色由调用方在<b>请求线程</b>上取好再显式传进来。
     */
    public List<ToolCallback> callbacks(List<String> roles) {
        if (!properties.isEnabled()) {
            return List.of();
        }
        List<ToolCallback> callbacks = new ArrayList<>();
        int budget = Math.max(properties.getMaxTools(), 1);

        for (Map.Entry<String, McpProperties.Server> entry : properties.getServers().entrySet()) {
            String serverName = entry.getKey();
            McpProperties.Server config = entry.getValue();
            if (!config.isEnabled()) {
                continue;
            }
            if (!roleAllowed(config, roles)) {
                continue;
            }
            McpClient client = ensureConnected(serverName, config);
            if (client == null) {
                continue;
            }
            for (McpToolSpec spec : toolsByServer.getOrDefault(serverName, List.of())) {
                if (callbacks.size() >= budget) {
                    log.warn("MCP 工具数已达到上限 {}，其余工具不会暴露给模型", budget);
                    return callbacks;
                }
                callbacks.add(new McpToolCallback(client, spec, objectMapper, observability));
            }
        }
        return callbacks;
    }

    /** 便捷重载：从当前安全上下文取角色。仅适用于请求线程（不要在虚拟线程里调用）。 */
    public List<ToolCallback> callbacks() {
        LoginUser user = SecurityUtils.getCurrentUser();
        return callbacks(user == null ? List.of() : user.getRoles());
    }

    private boolean roleAllowed(McpProperties.Server config, List<String> roleList) {
        List<String> allowed = config.getAllowedRoles();
        List<String> roles = roleList == null ? List.of() : roleList;
        boolean isManager = roles.stream().anyMatch(r -> r != null && r.endsWith("MANAGER"));
        if (allowed == null || allowed.isEmpty()) {
            // 未配置 = 只给管理员。见类注释里的默认值理由。
            return isManager;
        }
        for (String role : allowed) {
            if (role == null || role.isBlank()) {
                continue;
            }
            String normalized = role.toUpperCase().startsWith("ROLE_") ? role.toUpperCase()
                    : "ROLE_" + role.toUpperCase();
            if (roles.contains(normalized)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 保证某 server 已连接且工具列表已就绪。
     * <p>
     * 注意"失败不缓存"的处理：连接失败会被记录，但下一次调用<b>仍会重试</b>——
     * 因为 MCP server 往往是本机子进程，运维修好后应当自动恢复，而不是要求重启应用。
     * 重试的代价有界：一次 initialize 请求 + 一次 tools/list。
     */
    private McpClient ensureConnected(String serverName, McpProperties.Server config) {
        McpClient existing = clients.get(serverName);
        if (existing != null && existing.healthy() && toolsByServer.containsKey(serverName)) {
            return existing;
        }
        synchronized (this) {
            existing = clients.get(serverName);
            if (existing != null && existing.healthy() && toolsByServer.containsKey(serverName)) {
                return existing;
            }
            if (existing != null) {
                // 不健康的旧连接先关掉，否则 stdio 会不断堆积僵尸子进程
                closeQuietly(existing);
                clients.remove(serverName);
            }
            return connect(serverName, config);
        }
    }

    private McpClient connect(String serverName, McpProperties.Server config) {
        McpClient client = createClient(serverName, config);
        try {
            client.initialize();
            List<McpToolSpec> tools = client.listTools();
            clients.put(serverName, client);
            toolsByServer.put(serverName, tools);
            connectionErrors.remove(serverName);
            log.info("MCP server 工具已就绪：name={}, tools={}", serverName, tools.size());
            return client;
        } catch (Exception e) {
            closeQuietly(client);
            String message = e instanceof McpException me ? me.getMessage()
                    : e.getClass().getSimpleName() + ": " + e.getMessage();
            connectionErrors.put(serverName, message);
            log.warn("MCP server 连接失败（该 server 的工具本轮不可用）：name={}, err={}", serverName, message);
            return null;
        }
    }

    private McpClient createClient(String serverName, McpProperties.Server config) {
        Duration timeout = properties.getDefaultTimeout();
        if ("http".equalsIgnoreCase(config.getTransport())) {
            return new HttpMcpClient(serverName, config, objectMapper, timeout);
        }
        return new StdioMcpClient(serverName, config, objectMapper, timeout);
    }

    private void closeQuietly(McpClient client) {
        try {
            client.close();
        } catch (Exception e) {
            log.debug("关闭 MCP 客户端失败（忽略）：{}", e.toString());
        }
    }

    // ------------------------------------------------------------------ 对外：给管理页用

    /** 全部 server 的状态（不触发连接，避免打开页面就起一堆子进程）。 */
    public List<Map<String, Object>> status() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map.Entry<String, McpProperties.Server> entry : properties.getServers().entrySet()) {
            String serverName = entry.getKey();
            McpClient client = clients.get(serverName);
            Map<String, Object> row = client != null
                    ? new LinkedHashMap<>(client.status())
                    : describeUnconnected(serverName, entry.getValue());
            row.put("toolCount", toolsByServer.getOrDefault(serverName, List.of()).size());
            if (connectionErrors.containsKey(serverName)) {
                row.put("healthy", false);
                row.put("lastError", connectionErrors.get(serverName));
            }
            rows.add(row);
        }
        return rows;
    }

    /** 已发现的工具清单（含未连接 server 的说明），供管理页展示"模型实际能看到什么"。 */
    public List<Map<String, Object>> tools() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map.Entry<String, List<McpToolSpec>> entry : toolsByServer.entrySet()) {
            for (McpToolSpec spec : entry.getValue()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("server", spec.serverName());
                row.put("name", spec.name());
                row.put("exposedName", spec.exposedName());
                row.put("description", spec.description());
                row.put("inputSchema", spec.inputSchema());
                rows.add(row);
            }
        }
        return rows;
    }

    /** 强制重连全部 server：改完配置（或修好了外部服务）后用。 */
    public Map<String, Object> reload() {
        Map<String, Object> result = new LinkedHashMap<>();
        if (!properties.isEnabled()) {
            result.put("enabled", false);
            result.put("message", "mcp.enabled=false，未启用 MCP");
            return result;
        }
        List<Map<String, Object>> outcomes = new ArrayList<>();
        for (Map.Entry<String, McpProperties.Server> entry : properties.getServers().entrySet()) {
            String serverName = entry.getKey();
            if (!entry.getValue().isEnabled()) {
                continue;
            }
            synchronized (this) {
                McpClient existing = clients.remove(serverName);
                if (existing != null) {
                    closeQuietly(existing);
                }
                toolsByServer.remove(serverName);
                McpClient fresh = connect(serverName, entry.getValue());
                Map<String, Object> outcome = new LinkedHashMap<>();
                outcome.put("server", serverName);
                outcome.put("connected", fresh != null);
                outcome.put("toolCount", toolsByServer.getOrDefault(serverName, List.of()).size());
                if (fresh == null) {
                    outcome.put("error", connectionErrors.get(serverName));
                }
                outcomes.add(outcome);
            }
        }
        result.put("enabled", true);
        result.put("servers", outcomes);
        return result;
    }

    /**
     * 试调一个工具。运维配完 MCP 最想知道的就是"到底通不通、返回长什么样"，
     * 而不是等某次 Agent 对话出问题再猜。
     */
    public Map<String, Object> testCall(String serverName, String toolName, Map<String, Object> arguments) {
        Map<String, Object> result = new LinkedHashMap<>();
        McpProperties.Server config = properties.getServers().get(serverName);
        if (config == null) {
            result.put("ok", false);
            result.put("message", "未配置名为 " + serverName + " 的 MCP server");
            return result;
        }
        McpClient client = ensureConnected(serverName, config);
        if (client == null) {
            result.put("ok", false);
            result.put("message", connectionErrors.getOrDefault(serverName, "连接失败"));
            return result;
        }
        try {
            String output = client.callTool(toolName, arguments == null ? Map.of() : arguments);
            result.put("ok", true);
            result.put("server", serverName);
            result.put("tool", toolName);
            // 截断只影响展示，不影响调用
            result.put("result", output != null && output.length() > 4000
                    ? output.substring(0, 4000) + "…（已截断展示）" : output);
            return result;
        } catch (Exception e) {
            result.put("ok", false);
            result.put("message", e.getMessage());
            return result;
        }
    }

    public Map<String, Object> overview() {
        Map<String, Object> overview = new LinkedHashMap<>();
        overview.put("enabled", properties.isEnabled());
        overview.put("configuredServers", properties.getServers().size());
        overview.put("connectedServers", clients.size());
        overview.put("discoveredTools", tools().size());
        overview.put("maxTools", properties.getMaxTools());
        overview.put("hint", properties.isEnabled()
                ? "MCP 工具在对话中按需连接；allowed-roles 为空表示仅管理员可见（默认拒绝，避免跨用户数据暴露）。"
                : "mcp.enabled=false。MCP server 列表只能来自配置文件（stdio 等于执行本机命令，不开放 API 动态添加）。");
        return overview;
    }

    private Map<String, Object> describeUnconnected(String serverName, McpProperties.Server config) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("name", serverName);
        row.put("enabled", config.isEnabled());
        row.put("transport", config.getTransport());
        row.put("description", config.getDescription());
        row.put("healthy", null);
        row.put("connected", false);
        row.put("handshakeCount", 0);
        row.put("allowedRoles", config.getAllowedRoles());
        row.put("note", "尚未连接（MCP 是懒连接：第一次有请求用到它时才连）");
        if ("stdio".equalsIgnoreCase(config.getTransport())) {
            row.put("command", config.getCommand());
            row.put("args", config.getArgs());
        } else {
            row.put("url", config.getUrl());
        }
        return row;
    }

    @PreDestroy
    public void shutdown() {
        for (McpClient client : clients.values()) {
            closeQuietly(client);
        }
        clients.clear();
        toolsByServer.clear();
    }

    /** 供控制器判断是否可用；也便于测试。 */
    public boolean enabled() {
        return properties.isEnabled();
    }

    /** 只读视图，供调试接口。 */
    public List<String> serverNames() {
        return Collections.unmodifiableList(new ArrayList<>(properties.getServers().keySet()));
    }
}
