package com.trainereval.mcp;

import java.util.List;
import java.util.Map;

/**
 * 一个 MCP server 的连接。实现只有两种传输：stdio 与 Streamable HTTP，
 * 对应 MCP 规范里实际被广泛使用的两条路径。
 */
public interface McpClient extends AutoCloseable {

    String serverName();

    /** 建立连接并完成 {@code initialize} 握手。失败要抛 {@link McpException}。 */
    void initialize();

    /** 拉取工具列表。每次调用都重新拉：MCP server 可以热更新自己的工具。 */
    List<McpToolSpec> listTools();

    /** 调用一个工具，返回已经拼成纯文本的结果（多段 content 用换行连接）。 */
    String callTool(String toolName, Map<String, Object> arguments);

    /** 最近一次操作是否成功。用于管理页展示"连接正常/异常"。 */
    boolean healthy();

    /** 最近一次失败原因，正常时为 null。 */
    String lastError();

    /** 已完成的握手次数，便于判断是否发生过重连。 */
    int handshakeCount();

    /** 连接状态快照，供管理页展示（含传输方式、进程号/端点、stderr 尾部等）。 */
    Map<String, Object> status();

    @Override
    void close();
}
