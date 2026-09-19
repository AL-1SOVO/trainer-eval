package com.trainereval.observability.dto;

import com.trainereval.observability.AiTrace;

import java.time.LocalDateTime;

/**
 * 链路列表项。
 * <p>
 * 刻意与实体分开：{@code detail} 字段可能有几十 KB，列表接口带上它会让
 * "最近 20 条"这个查询变成一次几十万字符的传输。列表只给判断"哪条值得点开"所需的最小信息。
 */
public record TraceSummary(
        Long id,
        String traceId,
        String name,
        Long userId,
        String sessionId,
        Integer latencyMs,
        Integer promptTokens,
        Integer completionTokens,
        Integer observationCount,
        String level,
        String statusMessage,
        String inputBrief,
        String outputBrief,
        LocalDateTime createTime) {

    public static TraceSummary from(AiTrace row) {
        return new TraceSummary(
                row.getId(),
                row.getTraceId(),
                row.getName(),
                row.getUserId(),
                row.getSessionId(),
                row.getLatencyMs(),
                row.getPromptTokens(),
                row.getCompletionTokens(),
                row.getObservationCount(),
                row.getLevel(),
                row.getStatusMessage(),
                row.getInputBrief(),
                row.getOutputBrief(),
                row.getCreateTime());
    }
}
