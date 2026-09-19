package com.trainereval.observability.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trainereval.observability.AiTrace;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 单条链路的完整明细。
 *
 * <p>{@code steps} 是把库里 {@code detail} 那串 JSON 反序列化回来的结果，
 * 前端可以直接渲染成时间线。若 JSON 解析失败（例如手改过库），
 * 这里降级成空列表并把原始串放进 {@code rawDetail} —— 宁可展示不全，也不要整个接口报错。
 */
public record TraceDetail(
        Long id,
        String traceId,
        String name,
        Long userId,
        String sessionId,
        Integer latencyMs,
        Integer promptTokens,
        Integer completionTokens,
        String level,
        String statusMessage,
        String inputBrief,
        String outputBrief,
        Integer exported,
        LocalDateTime createTime,
        List<Map<String, Object>> steps,
        String rawDetail) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @SuppressWarnings("unchecked")
    public static TraceDetail from(AiTrace row) {
        List<Map<String, Object>> steps = List.of();
        String raw = row.getDetail();
        if (raw != null && !raw.isBlank()) {
            try {
                steps = MAPPER.readValue(raw, List.class);
                raw = null;
            } catch (Exception ignored) {
                // 保留 rawDetail 供排查，steps 留空
            }
        }
        return new TraceDetail(
                row.getId(),
                row.getTraceId(),
                row.getName(),
                row.getUserId(),
                row.getSessionId(),
                row.getLatencyMs(),
                row.getPromptTokens(),
                row.getCompletionTokens(),
                row.getLevel(),
                row.getStatusMessage(),
                row.getInputBrief(),
                row.getOutputBrief(),
                row.getExported(),
                row.getCreateTime(),
                steps,
                raw);
    }
}
