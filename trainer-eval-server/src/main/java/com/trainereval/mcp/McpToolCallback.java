package com.trainereval.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trainereval.observability.Observation;
import com.trainereval.observability.Observability;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 把一个 MCP 工具适配成 Spring AI 的 {@link ToolCallback}，从而能直接进
 * {@code OpenAiChatOptions.toolCallbacks}，和本地 {@code @Tool} 方法混在一起交给模型。
 *
 * <h3>为什么要包一层，而不是把 MCP 直接接进模型</h3>
 * <ul>
 *   <li><b>统一出口</b>：本地工具与 MCP 工具走同一个 {@code ToolCallingManager}，
 *       Agent 的规划/反思/引用逻辑不用为 MCP 写第二套。</li>
 *   <li><b>统一护栏</b>：结果统一包 {@code <data>} 标签（系统提示词声明其中指令不执行）、
 *       统一记链路 span、统一做长度截断。</li>
 *   <li><b>统一错误语义</b>：外部工具挂掉时返回一句模型能读懂的说明，
 *       而不是让异常冒到 SSE 层把整轮对话打断。</li>
 * </ul>
 *
 * <h3>关于 {@code <data>} 标签</h3>
 * MCP 工具的返回值来自外部系统，其内容完全不受本系统控制，
 * 是比课程资料更典型的"不可信输入"。所以这里同样加边界标签 —— 这与 {@code TutorTools.toJson} 是同一套约定。
 */
@Slf4j
public class McpToolCallback implements ToolCallback {

    /** 单次工具结果的长度上限：外部工具可能返回整篇网页，直接塞进上下文会爆 token。 */
    private static final int MAX_RESULT_CHARS = 6000;

    private final McpClient client;
    private final McpToolSpec spec;
    private final ObjectMapper objectMapper;
    private final Observability observability;
    private final ToolDefinition definition;

    public McpToolCallback(McpClient client, McpToolSpec spec, ObjectMapper objectMapper,
                           Observability observability) {
        this.client = client;
        this.spec = spec;
        this.objectMapper = objectMapper;
        this.observability = observability;
        this.definition = ToolDefinition.builder()
                .name(spec.exposedName())
                .description(describe(spec))
                .inputSchema(spec.inputSchema())
                .build();
    }

    /**
     * 描述里显式写明"来源外部系统"，让模型知道这条结果的可信度与本地数据不同。
     * 这对抑制"把外部返回当权威事实"很有用。
     */
    private String describe(McpToolSpec spec) {
        String base = spec.description() == null || spec.description().isBlank()
                ? "外部 MCP 工具 " + spec.name() : spec.description();
        return base + "（来源：外部 MCP 服务 " + spec.serverName() + "，非本系统数据，请以工具返回为准并说明来源）";
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return definition;
    }

    @Override
    public String call(String toolInput) {
        Map<String, Object> arguments = parseArguments(toolInput);
        try (Observation span = observability.toolSpan(spec.exposedName(), arguments)) {
            span.metadata("mcpServer", spec.serverName())
                    .metadata("mcpTool", spec.name());
            try {
                String result = client.callTool(spec.name(), arguments);
                String text = truncate(result);
                span.metadata("resultChars", result == null ? 0 : result.length()).end(
                        Map.of("chars", text.length()));
                return wrap(text);
            } catch (McpException e) {
                // 外部工具报错不该打断整轮对话：把它作为"这一跳失败"回报给模型，
                // 模型可以据此换工具或如实告知用户；同时链路里记为 ERROR 便于运维发现。
                span.error(e.getMessage());
                log.warn("MCP 工具调用失败：server={}, tool={}, err={}",
                        spec.serverName(), spec.name(), e.getMessage());
                return wrap("调用失败：" + e.getMessage() + "。请换一种方式，或如实说明该信息暂时取不到。");
            }
        }
    }

    private Map<String, Object> parseArguments(String toolInput) {
        if (toolInput == null || toolInput.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(toolInput, new com.fasterxml.jackson.core.type.TypeReference<>() {
            });
        } catch (Exception e) {
            // 模型偶尔会把参数拼成非 JSON。与其抛异常中断，不如记一条并当空参数调用，
            // 让工具自己报"参数缺失"——那条错误对模型更有指导性。
            log.warn("MCP 工具参数不是合法 JSON：tool={}, raw={}", spec.exposedName(), toolInput);
            Map<String, Object> fallback = new LinkedHashMap<>();
            fallback.put("_raw", toolInput);
            return fallback;
        }
    }

    private String truncate(String result) {
        if (result == null) {
            return "";
        }
        if (result.length() <= MAX_RESULT_CHARS) {
            return result;
        }
        return result.substring(0, MAX_RESULT_CHARS)
                + "\n…（结果过长已截断，共 " + result.length() + " 字符）";
    }

    private String wrap(String text) {
        return "<data>{\"source\":\"mcp:" + spec.serverName() + "\",\"tool\":\"" + spec.name()
                + "\",\"result\":" + toJsonString(text) + "}</data>";
    }

    private String toJsonString(String text) {
        try {
            return objectMapper.writeValueAsString(text);
        } catch (Exception e) {
            return "\"\"";
        }
    }

    public McpToolSpec spec() {
        return spec;
    }
}
