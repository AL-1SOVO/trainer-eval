package com.trainereval.mcp;

/**
 * 一个 MCP 工具的描述。对应 MCP 协议 {@code tools/list} 返回里的一个元素。
 *
 * @param serverName   来自哪个 MCP server（管理页要展示来源）
 * @param name         服务端声明的原始工具名，调用时要用它
 * @param exposedName  暴露给模型的工具名（可能带前缀），调用时要反查回 name
 * @param description  给模型看的用途说明
 * @param inputSchema  JSON Schema 字符串，直接透传给模型做参数约束
 */
public record McpToolSpec(
        String serverName,
        String name,
        String exposedName,
        String description,
        String inputSchema) {
}
