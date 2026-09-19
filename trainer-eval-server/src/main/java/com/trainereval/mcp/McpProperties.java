package com.trainereval.mcp;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP（Model Context Protocol）接入配置。
 *
 * <h3>为什么要接 MCP</h3>
 * Agent 现在只有 4 个写死在 Java 里的本地工具（成绩、错题、选课、资料检索）。
 * 每加一个能力（查工单、查制度库、跑计算、调内部 API）都要改代码、发版。
 * MCP 把"工具"变成<b>配置</b>：运维在 {@code application.yml} 里声明一个 MCP server，
 * 它的工具就自动出现在 Agent 的工具列表里，模型自己去挑。
 *
 * <h3>安全边界（这是接 MCP 最需要想清楚的地方）</h3>
 * <ol>
 *   <li><b>stdio 传输等于在服务器上执行任意命令</b>。所以 server 列表<b>只能来自配置文件</b>，
 *       <b>绝不开放 API 动态添加</b>——那等于给了一个远程命令执行入口。
 *       控制器只提供"看状态 / 看工具 / 重连 / 试调"四个只读或受限动作。</li>
 *   <li><b>MCP 工具天然是"组织级"的，没有用户身份</b>。本地工具靠 {@code userId} 做行级过滤，
 *       MCP 工具做不到。所以用 {@code allowed-roles} 做角色级闸门：
 *       默认不给员工，只有明确知道该 server 不返回他人数据时才放开。这是"默认拒绝"。</li>
 *   <li><b>工具返回值一律视为不可信输入</b>，与课程资料同等对待：包在 {@code <data>} 里，
 *       系统提示词已声明其中的指令不执行。</li>
 * </ol>
 *
 * <h3>为什么默认关闭</h3>
 * 没配任何 server 时，{@code enabled=false}，工具列表为空，Agent 行为与没有这个功能完全一致——
 * 不会因为引入 MCP 而改变现网行为。
 */
@Data
@Component
@ConfigurationProperties(prefix = "mcp")
public class McpProperties {

    /** 总开关。关闭时完全不连接任何 server。 */
    private boolean enabled = false;

    /** 单次请求（tools/list、tools/call）的默认超时。 */
    private Duration defaultTimeout = Duration.ofSeconds(20);

    /** 握手（initialize）超时。进程冷启动可能慢，给它单独一档。 */
    private Duration initTimeout = Duration.ofSeconds(15);

    /** 所有 server 合计最多注册多少个工具，防止某个 server 一口气暴露几百个工具把提示词撑爆。 */
    private int maxTools = 64;

    /** 工具结果进链路追踪时是否记录完整内容。默认 false：外部工具结果体积不可控。 */
    private boolean captureToolResult = false;

    /** 按名字声明的 server 列表。 */
    private Map<String, Server> servers = new LinkedHashMap<>();

    @Data
    public static class Server {

        private boolean enabled = true;

        /** {@code stdio}（启动本地子进程）或 {@code http}（Streamable HTTP）。 */
        private String transport = "stdio";

        /** stdio：可执行文件路径或命令名。 */
        private String command;

        private List<String> args = new ArrayList<>();

        /** stdio：注入子进程的环境变量（例如 API Key，用 ${ENV} 占位符从宿主环境取）。 */
        private Map<String, String> env = new LinkedHashMap<>();

        /** http：MCP Streamable HTTP 端点。 */
        private String url;

        /** http：附加请求头（例如 Authorization）。 */
        private Map<String, String> headers = new LinkedHashMap<>();

        /** 覆盖全局超时。 */
        private Duration timeout;

        /**
         * 工具名前缀。MCP 工具是外部命名，很容易和本地工具重名（比如都叫 search）。
         * 默认自动加上 {@code mcp_<server>_}；设成 "-" 可显式关闭加前缀。
         */
        private String toolPrefix;

        /**
         * 允许看到该 server 工具的<b>角色</b>（不带 ROLE_ 前缀，如 MANAGER / TRAINER）。
         * <b>留空表示只对管理员开放</b>（最保守的默认），而不是"对所有角色开放" ——
         * 因为 MCP 工具没有用户身份，误开放等于跨用户数据泄露。
         */
        private List<String> allowedRoles = new ArrayList<>();

        /** 给运维看的说明，会出现在管理页上。 */
        private String description = "";
    }
}
