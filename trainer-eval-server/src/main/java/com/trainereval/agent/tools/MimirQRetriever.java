package com.trainereval.agent.tools;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trainereval.training.entity.TraEnrollment;
import com.trainereval.training.mapper.TraEnrollmentMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MimirQ 知识库检索客户端。
 * 走 MimirQ 的 Dify External Knowledge 兼容协议：
 *   POST {base-url}/api/v1/integrations/dify/retrieval
 * 权限模型：knowledge_id 按课程路由（course-{courseId}），调用前必须先校验用户报名关系，
 * 未报名课程的 knowledge_id 不允许出现在请求里（security trimming 的第一道防线）。
 */
@Slf4j
public class MimirQRetriever {

    private final RestClient restClient;
    private final TraEnrollmentMapper enrollmentMapper;
    private final ObjectMapper objectMapper;
    private final Duration timeout;

    public MimirQRetriever(String baseUrl, String apiKey, Duration timeout,
                           TraEnrollmentMapper enrollmentMapper, ObjectMapper objectMapper) {
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
        this.enrollmentMapper = enrollmentMapper;
        this.objectMapper = objectMapper;
        this.timeout = timeout;
    }

    /**
     * 在用户已报名课程的 dataset 范围内做语义检索。
     * 返回摘要 JSON：{"records":[{"title","excerpt","score","courseId"}], "degraded":bool}
     */
    public List<Map<String, Object>> search(Long userId, String query, int topK, double scoreThreshold) {
        List<Long> courseIds = enrollmentMapper.selectList(
                        new LambdaQueryWrapper<TraEnrollment>().eq(TraEnrollment::getUserId, userId))
                .stream()
                .map(TraEnrollment::getCourseId)
                .distinct()
                .toList();

        List<Map<String, Object>> merged = new ArrayList<>();
        boolean degraded = false;
        for (Long courseId : courseIds) {
            try {
                Map<String, Object> body = Map.of(
                        "knowledge_id", "course-" + courseId,
                        "query", query,
                        "retrieval_setting", Map.of("top_k", topK, "score_threshold", scoreThreshold));
                String raw = restClient.post()
                        .uri("/api/v1/integrations/dify/retrieval")
                        .body(body)
                        .retrieve()
                        .body(String.class);
                merged.addAll(parseRecords(raw, courseId));
            } catch (Exception e) {
                log.warn("MimirQ retrieval failed for course {}: {}", courseId, e.getMessage());
                degraded = true;
            }
        }
        merged.sort((a, b) -> Double.compare(
                toDouble(b.get("score")), toDouble(a.get("score"))));
        if (merged.size() > topK) {
            merged = merged.subList(0, topK);
        }
        if (degraded) {
            for (Map<String, Object> item : merged) {
                item.put("degraded", true);
            }
        }
        return merged;
    }

    private List<Map<String, Object>> parseRecords(String raw, Long courseId) {
        List<Map<String, Object>> result = new ArrayList<>();
        try {
            DifyRetrievalResponse resp = objectMapper.readValue(raw, DifyRetrievalResponse.class);
            if (resp.records() == null) return result;
            for (DifyRetrievalResponse.Record r : resp.records()) {
                Map<String, Object> item = new LinkedHashMap<>();
                Map<String, Object> meta = r.metadata() == null ? Map.of() : r.metadata();
                item.put("title", String.valueOf(meta.getOrDefault("title", meta.getOrDefault("doc_name", ""))));
                item.put("excerpt", r.content() == null ? "" :
                        r.content().substring(0, Math.min(300, r.content().length())));
                item.put("score", r.score() == null ? 0.0 : r.score());
                item.put("courseId", courseId);
                result.add(item);
            }
        } catch (Exception e) {
            log.warn("Failed to parse MimirQ response: {}", e.getMessage());
        }
        return result;
    }

    private double toDouble(Object o) {
        return o instanceof Number n ? n.doubleValue() : 0.0;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record DifyRetrievalResponse(List<Record> records) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        record Record(String content, Double score, Map<String, Object> metadata) {
        }
    }
}
