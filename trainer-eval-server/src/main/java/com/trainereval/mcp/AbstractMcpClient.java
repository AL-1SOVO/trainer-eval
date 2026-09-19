package com.trainereval.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * MCP 客户端的公共部分：JSON-RPC 2.0 的组装/解析 + {@code initialize} 握手 + 三个方法的实现。
 * 传输细节（子进程管道 / HTTP）交给子类，只实现 {@link #exchange}。
 *
 * <h3>协议事实（按 MCP 规范，非推测）</h3>
 * <pre>
 *   握手：  initialize            →  {protocolVersion, capabilities, clientInfo}
 *           notifications/initialized（通知，无 id，无响应）
 *   工具：  tools/list            →  {tools:[{name, description, inputSchema}]}
 *           tools/call            →  {content:[{type:"text", text:"..."}], isError:bool}
 * </pre>
 * 时间戳/协议版本用 {@code 2024-11-05}：这是被最广泛实现的一版；
 * 服务端若返回它自己支持的版本，我们接受它（版本协商由服务端拍板）。
 */
@Slf4j
public abstract class AbstractMcpClient implements McpClient {

    /** 客户端声明支持的协议版本。 */
    protected static final String PROTOCOL_VERSION = "2024-11-05";

    protected static final String METHOD_INITIALIZE = "initialize";
    protected static final String METHOD_INITIALIZED = "notifications/initialized";
    protected static final String METHOD_TOOLS_LIST = "tools/list";
    protected static final String METHOD_TOOLS_CALL = "tools/call";

    protected final String serverName;
    protected final McpProperties.Server config;
    protected final ObjectMapper objectMapper;
    protected final Duration timeout;

    private final AtomicInteger requestIds = new AtomicInteger(0);
    private final AtomicInteger handshakeCount = new AtomicInteger(0);

    private volatile String lastError;
    private volatile boolean healthy;
    /** 服务端协商出来的协议版本，仅用于展示。 */
    private volatile String negotiatedVersion;

    protected AbstractMcpClient(String serverName, McpProperties.Server config, ObjectMapper objectMapper,
                                Duration defaultTimeout) {
        this.serverName = serverName;
        this.config = config;
        this.objectMapper = objectMapper;
        this.timeout = config.getTimeout() != null ? config.getTimeout() : defaultTimeout;
    }

    // ------------------------------------------------------------------ 传输抽象

    /** 建立底层连接。 */
    protected abstract void open() throws Exception;

    /**
     * 发一条 JSON-RPC 请求并拿到<b>同 id</b> 的响应。
     * 实现必须自己处理"响应里混入通知/日志"的情况。
     */
    protected abstract JsonNode exchange(ObjectNode request, Duration timeout) throws Exception;

    /** 关闭底层连接。 */
    protected abstract void shutdown();

    // ------------------------------------------------------------------ 生命周期

    @Override
    public String serverName() {
        return serverName;
    }

    @Override
    public boolean healthy() {
        return healthy;
    }

    @Override
    public String lastError() {
        return lastError;
    }

    @Override
    public int handshakeCount() {
        return handshakeCount.get();
    }

    protected String negotiatedVersion() {
        return negotiatedVersion;
    }

    @Override
    public synchronized void initialize() {
        try {
            open();
            ObjectNode params = objectMapper.createObjectNode();
            params.put("protocolVersion", PROTOCOL_VERSION);
            params.set("capabilities", objectMapper.createObjectNode());
            ObjectNode clientInfo = params.putObject("clientInfo");
            clientInfo.put("name", "trainer-eval");
            clientInfo.put("version", "1.0.0");

            JsonNode result = request(METHOD_INITIALIZE, params, null);
            negotiatedVersion = result == null ? null : result.path("protocolVersion").asText(null);

            // 通知：没有 id，服务端不会回。发失败也不算致命（有些实现允许跳过）
            notify(METHOD_INITIALIZED, objectMapper.createObjectNode());

            handshakeCount.incrementAndGet();
            healthy = true;
            lastError = null;
            log.info("MCP server 已连接：name={}, transport={}, protocolVersion={}",
                    serverName, config.getTransport(), negotiatedVersion);
        } catch (Exception e) {
            healthy = false;
            lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
            throw e instanceof McpException me ? me
                    : new McpException(serverName, "初始化失败：" + lastError, e);
        }
    }

    @Override
    public List<McpToolSpec> listTools() {
        JsonNode result = request(METHOD_TOOLS_LIST, objectMapper.createObjectNode(), null);
        List<McpToolSpec> tools = new ArrayList<>();
        JsonNode items = result == null ? null : result.path("tools");
        if (items == null || !items.isArray()) {
            return tools;
        }
        for (JsonNode item : items) {
            String name = item.path("name").asText("");
            if (name.isBlank()) {
                continue;
            }
            JsonNode schema = item.path("inputSchema");
            // inputSchema 缺失时给一个"任意对象"的合法 schema：模型仍能调，只是没有参数约束。
            // 给 null 会让部分模型的 function-calling 直接报错。
            String inputSchema = schema.isMissingNode() || schema.isNull()
                    ? "{\"type\":\"object\",\"properties\":{}}"
                    : schema.toString();
            tools.add(new McpToolSpec(
                    serverName,
                    name,
                    exposedName(name),
                    item.path("description").asText(""),
                    inputSchema));
        }
        return tools;
    }

    @Override
    public String callTool(String toolName, Map<String, Object> arguments) {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("name", toolName);
        params.set("arguments", objectMapper.valueToTree(arguments == null ? Map.of() : arguments));

        JsonNode result = request(METHOD_TOOLS_CALL, params, null);
        if (result == null) {
            return "";
        }
        // MCP 的约定：工具执行失败不是协议错误，而是 isError=true + content 里给原因。
        // 必须把它当"失败"回报给模型，否则模型会把错误信息当正常结果继续编。
        boolean isError = result.path("isError").asBoolean(false);
        String text = flattenContent(result.path("content"));
        if (isError) {
            throw new McpException(serverName, "工具 " + toolName + " 返回错误：" + abbreviate(text));
        }
        return text;
    }

    /** 把 content 数组拼成纯文本：text 段直接用，其它类型（图片等）降级成类型标注。 */
    private String flattenContent(JsonNode content) {
        if (content == null || !content.isArray()) {
            return content == null ? "" : content.toString();
        }
        StringBuilder sb = new StringBuilder();
        for (JsonNode part : content) {
            String type = part.path("type").asText("");
            if ("text".equals(type)) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(part.path("text").asText(""));
            } else if (!type.isBlank()) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                // 非文本内容不丢，至少让模型知道"这里还有一段 X 类型的内容"
                sb.append("[非文本内容 type=").append(type).append("]");
            }
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ JSON-RPC

    protected JsonNode request(String method, ObjectNode params, Duration overrideTimeout) {
        int id = requestIds.incrementAndGet();
        ObjectNode request = objectMapper.createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("id", id);
        request.put("method", method);
        if (params != null) {
            request.set("params", params);
        }
        try {
            JsonNode response = exchange(request, overrideTimeout == null ? timeout : overrideTimeout);
            if (response == null) {
                throw new McpException(serverName, method + " 没有返回响应（超时或连接已断开）");
            }
            JsonNode error = response.path("error");
            if (!error.isMissingNode() && !error.isNull()) {
                throw new McpException(serverName, method + " 返回错误 "
                        + error.path("code").asInt() + "：" + error.path("message").asText(""));
            }
            healthy = true;
            lastError = null;
            return response.path("result");
        } catch (McpException e) {
            healthy = false;
            lastError = e.getMessage();
            throw e;
        } catch (Exception e) {
            healthy = false;
            lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
            throw new McpException(serverName, method + " 失败：" + lastError, e);
        }
    }

    /** 通知：无 id、不等响应。失败只记日志——很多 MCP 实现不回复通知。 */
    protected void notify(String method, ObjectNode params) {
        try {
            ObjectNode notification = objectMapper.createObjectNode();
            notification.put("jsonrpc", "2.0");
            notification.put("method", method);
            if (params != null) {
                notification.set("params", params);
            }
            send(notification);
        } catch (Exception e) {
            log.debug("MCP 通知发送失败（可忽略）：server={}, method={}, err={}", serverName, method, e.toString());
        }
    }

    /** 仅用于通知：子类可覆写为"写了就走"。 */
    protected void send(ObjectNode notification) throws Exception {
        throw new UnsupportedOperationException("该传输不支持单向通知");
    }

    // ------------------------------------------------------------------ 工具名

    /**
     * 计算暴露给模型的工具名。
     * <p>
     * 默认加 {@code mcp_<server>_} 前缀：MCP server 的工具名是外部约定的，
     * 很容易和本地工具（searchCourseMaterials 之类）撞名，撞了以后模型调谁完全不可控。
     * 运维如果确信不冲突，可以显式设 {@code tool-prefix: "-"} 关掉。
     */
    protected String exposedName(String rawName) {
        String prefix = config.getToolPrefix();
        if ("-".equals(prefix)) {
            return rawName;
        }
        if (prefix == null || prefix.isBlank()) {
            prefix = "mcp_" + serverName + "_";
        }
        return prefix + rawName;
    }

    // ------------------------------------------------------------------ 状态

    public Map<String, Object> status() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("name", serverName);
        status.put("enabled", config.isEnabled());
        status.put("transport", config.getTransport());
        status.put("description", config.getDescription());
        status.put("healthy", healthy);
        status.put("handshakeCount", handshakeCount.get());
        status.put("protocolVersion", negotiatedVersion);
        status.put("allowedRoles", config.getAllowedRoles());
        status.put("lastError", lastError);
        if ("stdio".equalsIgnoreCase(config.getTransport())) {
            status.put("command", config.getCommand());
            status.put("args", config.getArgs());
        } else {
            status.put("url", config.getUrl());
        }
        return status;
    }

    protected static String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= 400 ? text : text.substring(0, 400) + "…";
    }

    /** 供子类构造 {@code params.tools} 之类的数组。 */
    protected ArrayNode arrayNode() {
        return objectMapper.createArrayNode();
    }
}
