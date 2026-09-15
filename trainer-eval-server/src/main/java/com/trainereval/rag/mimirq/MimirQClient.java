package com.trainereval.rag.mimirq;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MimirQ 检索客户端（Dify External Knowledge 兼容协议）。
 * <p>
 * 契约来自 MimirQ 源码 {@code app/api/v1/dify_support/external_api.py}：
 * <pre>
 * 请求 DifyExternalKnowledgeRequest(knowledge_id, query, retrieval_setting{top_k, score_threshold, latency_profile})
 * 响应 DifyExternalKnowledgeResponse(records=[DifyExternalKnowledgeRecord(content, score, title, metadata)])
 * </pre>
 * 注意两点与方案文档的差异（读源码后修正）：
 * <ol>
 *   <li>record 的 {@code title} 是<b>顶层字段</b>，不在 metadata 里；metadata 才是自定义元数据的容器。
 *       因此解析时两者都兼容，但优先取顶层 title。</li>
 *   <li>{@code retrieval_setting.latency_profile} 是 MimirQ 的扩展字段，取 "fast" 会关闭昂贵的
 *       回退/重排分支。Agent 工具调用是在线对话路径，默认用它。</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MimirQClient {

    private final MimirQProperties properties;
    private final ObjectMapper objectMapper;

    /** 一次检索命中的一条证据。 */
    public record MimirQRecord(String content, double score, String title, Map<String, Object> metadata) {
    }

    /** 服务不可用（网络/超时/非 2xx/响应不可解析）。调用方据此降级。 */
    public static class MimirQUnavailableException extends RuntimeException {
        public MimirQUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }

        public MimirQUnavailableException(String message) {
            super(message);
        }
    }

    private volatile RestClient client;

    private RestClient client() {
        RestClient existing = this.client;
        if (existing != null) {
            return existing;
        }
        synchronized (this) {
            if (this.client == null) {
                SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
                factory.setConnectTimeout(properties.getConnectTimeout());
                factory.setReadTimeout(properties.getTimeout());
                this.client = RestClient.builder()
                        .baseUrl(properties.getBaseUrl())
                        .requestFactory(factory)
                        .build();
            }
            return this.client;
        }
    }

    /** 供熔断冷却后重建连接（配置变更/服务重启场景）。 */
    public void resetClient() {
        this.client = null;
    }

    /**
     * 对单个知识库执行检索。
     *
     * @throws MimirQUnavailableException 任何导致"无法取得结果"的故障
     */
    public List<MimirQRecord> retrieveOne(String knowledgeId, String query) {
        Map<String, Object> setting = new LinkedHashMap<>();
        setting.put("top_k", properties.getTopK());
        setting.put("score_threshold", properties.getScoreThreshold());
        if (properties.getLatencyProfile() != null && !properties.getLatencyProfile().isBlank()) {
            setting.put("latency_profile", properties.getLatencyProfile());
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("knowledge_id", knowledgeId);
        payload.put("query", query);
        payload.put("retrieval_setting", setting);

        String raw;
        try {
            raw = client().post()
                    .uri(properties.getRetrievalPath())
                    .contentType(MediaType.APPLICATION_JSON)
                    .headers(headers -> {
                        if (properties.getApiKey() != null && !properties.getApiKey().isBlank()) {
                            headers.setBearerAuth(properties.getApiKey());
                        }
                    })
                    .body(payload)
                    .retrieve()
                    .body(String.class);
        } catch (Exception e) {
            // 连接被拒 / 读超时 / 4xx / 5xx 都归为"不可用"，由上层降级
            throw new MimirQUnavailableException(
                    "调用 MimirQ 检索失败: " + e.getClass().getSimpleName() + " " + e.getMessage(), e);
        }

        if (raw == null || raw.isBlank()) {
            throw new MimirQUnavailableException("MimirQ 返回空响应体");
        }

        try {
            JsonNode root = objectMapper.readTree(raw);
            JsonNode records = root.path("records");
            if (!records.isArray()) {
                throw new MimirQUnavailableException("MimirQ 响应缺少 records 数组: " + abbreviate(raw));
            }
            List<MimirQRecord> result = new ArrayList<>();
            for (JsonNode node : records) {
                String content = node.path("content").asText("");
                if (content.isBlank()) {
                    continue;
                }
                double score = node.path("score").asDouble(0.0);
                String title = node.path("title").asText(null);
                Map<String, Object> metadata = new LinkedHashMap<>();
                JsonNode meta = node.path("metadata");
                if (meta.isObject()) {
                    meta.fields().forEachRemaining(entry -> metadata.put(entry.getKey(), asPlain(entry.getValue())));
                }
                if (title == null || title.isBlank()) {
                    title = metadata.get("title") == null ? null : String.valueOf(metadata.get("title"));
                }
                result.add(new MimirQRecord(content, score, title, metadata));
            }
            return result;
        } catch (MimirQUnavailableException e) {
            throw e;
        } catch (Exception e) {
            throw new MimirQUnavailableException("解析 MimirQ 响应失败: " + e.getMessage(), e);
        }
    }

    private Object asPlain(JsonNode node) {
        if (node.isTextual()) {
            return node.asText();
        }
        if (node.isNumber()) {
            return node.numberValue();
        }
        if (node.isBoolean()) {
            return node.asBoolean();
        }
        return node.toString();
    }

    private String abbreviate(String s) {
        return s.length() <= 200 ? s : s.substring(0, 200) + "…";
    }
}
