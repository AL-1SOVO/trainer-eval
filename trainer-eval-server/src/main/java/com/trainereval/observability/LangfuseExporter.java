package com.trainereval.observability;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 把 {@link LlmTrace} 导出到 Langfuse。
 *
 * <h3>为什么是"自己的 30 行客户端"而不是官方 SDK</h3>
 * Langfuse 的官方 Java 路径是 OpenTelemetry Java SDK（或 OpenLLMetry）。引入它意味着
 * 多出一整套 OTel SDK + exporter 依赖，还要在全局注册 {@code OpenTelemetry}/{@code Tracer}，
 * 与本项目"用 Spring AI 但不引入 OTel 全家桶"的现状冲突。而我们要发的就是
 * <b>一个 HTTP POST + 一段结构化 JSON</b>，用 OTLP/HTTP-JSON 协议自己写反而更可控：
 * 字段可读、可断言、出问题能直接 curl 对比。
 *
 * <h3>端点与协议选择（依据官方契约，不是猜的）</h3>
 * <ul>
 *   <li>默认 {@code POST {host}/api/public/otel/v1/traces}（OTLP/HTTP + JSON）。
 *       官方文档明确：老的 {@code /api/public/ingestion} 已 deprecated，
 *       Langfuse Cloud 自 2026-11-16 起只接受 {@code score-create}，替代路径就是 OTLP。</li>
 *   <li>旧自托管（&lt; v3.22.0）没有 OTLP 端点，用 {@code langfuse.mode=legacy} 切回批次接口。</li>
 *   <li>OTLP 需要 {@code x-langfuse-ingestion-version: 4}，否则新数据在 v4 数据模型上
 *       可能延迟约 10 分钟才可见（官方文档原话）。</li>
 * </ul>
 *
 * <h3>三条工程约束</h3>
 * <ol>
 *   <li><b>绝不阻塞业务线程</b>：{@link #export} 只入队，网络请求在后台线程做。</li>
 *   <li><b>绝不抛异常给业务</b>：任何失败只记日志 + 计数，本地表已经保留了全部数据。</li>
 *   <li><b>队列有界</b>：满了就丢弃并计数。可观测性不能反过来把应用拖死——这是最容易犯的错。</li>
 * </ol>
 */
@Slf4j
@Component
public class LangfuseExporter {

    private static final DateTimeFormatter ISO_MILLIS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    private final LangfuseProperties properties;
    private final ObjectMapper objectMapper;

    private final BlockingQueue<LlmTrace> queue;
    private final AtomicLong exportedTraces = new AtomicLong();
    private final AtomicLong exportedSpans = new AtomicLong();
    private final AtomicLong droppedTraces = new AtomicLong();
    private final AtomicLong failedBatches = new AtomicLong();
    private final AtomicLong sampledOut = new AtomicLong();

    private volatile String lastError;
    private volatile long lastErrorAtMillis;

    private final Thread worker;
    private volatile boolean running = true;
    private volatile RestClient client;

    public LangfuseExporter(LangfuseProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.queue = new ArrayBlockingQueue<>(Math.max(properties.getQueueCapacity(), 16));
        this.worker = new Thread(this::loop, "langfuse-exporter");
        this.worker.setDaemon(true);
        this.worker.start();
    }

    // ------------------------------------------------------------------ 对外

    /** 入队。未配置 / 未命中采样 / 队列满，都在这里安静地短路掉。 */
    public void export(LlmTrace trace) {
        if (trace == null || !properties.isConfigured()) {
            return;
        }
        if (!sampled()) {
            sampledOut.incrementAndGet();
            return;
        }
        if (!queue.offer(trace)) {
            droppedTraces.incrementAndGet();
            lastError = "导出队列已满（capacity=" + queue.size() + "），本批链路被丢弃";
            lastErrorAtMillis = System.currentTimeMillis();
        }
    }

    private boolean sampled() {
        double rate = properties.getSampleRate();
        if (rate >= 1.0) {
            return true;
        }
        return rate > 0 && Math.random() < rate;
    }

    /** 立刻同步导出一次，只给"测试连接"用：这样接口能如实回报错误原因，而不是"已入队"。 */
    public Map<String, Object> exportNow(LlmTrace trace) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (!properties.isConfigured()) {
            result.put("ok", false);
            result.put("message", "未配置 langfuse（enabled/public-key/secret-key 任一为空）");
            return result;
        }
        try {
            post(buildBatch(List.of(trace)));
            result.put("ok", true);
            result.put("endpoint", endpoint());
            result.put("mode", properties.isOtlp() ? "otlp" : "legacy");
            result.put("traceId", trace.traceId());
            exportedTraces.incrementAndGet();
            exportedSpans.addAndGet(trace.observations().size());
            return result;
        } catch (Exception e) {
            result.put("ok", false);
            result.put("endpoint", endpoint());
            result.put("message", e.getClass().getSimpleName() + ": " + e.getMessage());
            return result;
        }
    }

    public Map<String, Object> stats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("configured", properties.isConfigured());
        stats.put("mode", properties.isOtlp() ? "otlp" : "legacy");
        stats.put("endpoint", endpoint());
        stats.put("environment", properties.getEnvironment());
        stats.put("sampleRate", properties.getSampleRate());
        stats.put("queueDepth", queue.size());
        stats.put("queueCapacity", properties.getQueueCapacity());
        stats.put("exportedTraces", exportedTraces.get());
        stats.put("exportedSpans", exportedSpans.get());
        stats.put("droppedTraces", droppedTraces.get());
        stats.put("failedBatches", failedBatches.get());
        stats.put("sampledOut", sampledOut.get());
        stats.put("lastError", lastError);
        stats.put("lastErrorAt", lastErrorAtMillis == 0 ? null : ISO_MILLIS.format(
                Instant.ofEpochMilli(lastErrorAtMillis)));
        return stats;
    }

    @PreDestroy
    public void shutdown() {
        running = false;
        worker.interrupt();
    }

    // ------------------------------------------------------------------ 后台线程

    private void loop() {
        List<LlmTrace> batch = new ArrayList<>(Math.max(properties.getBatchSize(), 4));
        while (running) {
            try {
                LlmTrace first = queue.poll(1, TimeUnit.SECONDS);
                if (first == null) {
                    continue;
                }
                batch.clear();
                batch.add(first);
                queue.drainTo(batch, Math.max(properties.getBatchSize() - 1, 0));
                try {
                    post(buildBatch(batch));
                    // 按 trace 计数而不是按批次：一批可能装多条链路，
                    // 按批次计会让"已导出链路数"偏小，排查"是不是漏发了"时反而误导人
                    exportedTraces.addAndGet(batch.size());
                    for (LlmTrace trace : batch) {
                        exportedSpans.addAndGet(trace.observations().size());
                    }
                } catch (Exception e) {
                    failedBatches.incrementAndGet();
                    lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
                    lastErrorAtMillis = System.currentTimeMillis();
                    log.warn("Langfuse 导出失败（本地表已保留数据）：{}", lastError);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                // 兜底：后台线程绝不能因为一次意外异常就死掉，否则可观测性会静默失效
                log.warn("Langfuse 导出线程异常：{}", e.toString());
            }
        }
    }

    private void post(Object payload) {
        String url = endpoint();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(HttpHeaders.AUTHORIZATION, basicAuth());
        if (properties.isOtlp()) {
            // 不加这个头，直连 OTLP 的数据在 v4 数据模型上可能延迟约 10 分钟才可见
            headers.set("x-langfuse-ingestion-version", "4");
        }
        client().post()
                .uri(url)
                .headers(h -> h.putAll(headers))
                .body(payload)
                .retrieve()
                .toBodilessEntity();
    }

    private String endpoint() {
        String host = properties.getHost() == null ? "" : properties.getHost().trim();
        while (host.endsWith("/")) {
            host = host.substring(0, host.length() - 1);
        }
        return properties.isOtlp() ? host + "/api/public/otel/v1/traces" : host + "/api/public/ingestion";
    }

    private String basicAuth() {
        String raw = properties.getPublicKey() + ":" + properties.getSecretKey();
        return "Basic " + Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private RestClient client() {
        RestClient existing = this.client;
        if (existing != null) {
            return existing;
        }
        synchronized (this) {
            if (this.client == null) {
                SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
                int timeout = (int) properties.getTimeout().toMillis();
                factory.setConnectTimeout(Math.min(timeout, 3000));
                factory.setReadTimeout(timeout);
                this.client = RestClient.builder().requestFactory(factory).build();
            }
            return this.client;
        }
    }

    // ------------------------------------------------------------------ 序列化

    private Map<String, Object> buildBatch(List<LlmTrace> traces) {
        return properties.isOtlp() ? buildOtlp(traces) : buildLegacy(traces);
    }

    /**
     * OTLP/HTTP JSON。traceId/spanId 用十六进制字符串，时间戳用纳秒字符串（proto3 JSON 对 int64 的要求）。
     */
    private Map<String, Object> buildOtlp(List<LlmTrace> traces) {
        List<Map<String, Object>> spans = new ArrayList<>();
        for (LlmTrace trace : traces) {
            for (Observation observation : trace.observations()) {
                spans.add(toOtlpSpan(trace, observation));
            }
        }

        Map<String, Object> resource = new LinkedHashMap<>();
        resource.put("attributes", List.of(
                stringAttr("service.name", properties.getServiceName()),
                stringAttr("deployment.environment", properties.getEnvironment())));

        Map<String, Object> scope = new LinkedHashMap<>();
        scope.put("name", "trainer-eval-observability");
        scope.put("version", "1.0.0");

        Map<String, Object> scopeSpans = new LinkedHashMap<>();
        scopeSpans.put("scope", scope);
        scopeSpans.put("spans", spans);

        Map<String, Object> resourceSpans = new LinkedHashMap<>();
        resourceSpans.put("resource", resource);
        resourceSpans.put("scopeSpans", List.of(scopeSpans));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("resourceSpans", List.of(resourceSpans));
        return body;
    }

    private Map<String, Object> toOtlpSpan(LlmTrace trace, Observation observation) {
        Map<String, Object> span = new LinkedHashMap<>();
        span.put("traceId", trace.traceId());
        span.put("spanId", observation.id());
        if (observation.parentId() != null) {
            span.put("parentSpanId", observation.parentId());
        }
        span.put("name", observation.name() == null ? "unnamed" : observation.name());
        // SPAN_KIND_INTERNAL = 1：这些都是进程内步骤，没有跨进程语义
        span.put("kind", 1);
        span.put("startTimeUnixNano", String.valueOf(observation.startEpochNanos()));
        span.put("endTimeUnixNano", String.valueOf(observation.endEpochNanos()));

        List<Map<String, Object>> attributes = new ArrayList<>();
        // ---- trace 级属性：官方文档要求"写到每一个 span 上"，否则按用户/会话过滤聚合不可靠
        attributes.add(stringAttr("langfuse.trace.name", trace.name()));
        if (trace.userId() != null) {
            attributes.add(stringAttr("langfuse.user.id", String.valueOf(trace.userId())));
        }
        if (trace.sessionId() != null && !trace.sessionId().isBlank()) {
            attributes.add(stringAttr("langfuse.session.id", trace.sessionId()));
        }
        if (properties.getEnvironment() != null && !properties.getEnvironment().isBlank()) {
            attributes.add(stringAttr("langfuse.environment", properties.getEnvironment()));
        }
        if (properties.getRelease() != null && !properties.getRelease().isBlank()) {
            attributes.add(stringAttr("langfuse.release", properties.getRelease()));
        }
        if (!trace.tags().isEmpty()) {
            attributes.add(arrayAttr("langfuse.trace.tags", trace.tags()));
        }
        for (Map.Entry<String, Object> entry : trace.metadata().entrySet()) {
            attributes.add(stringAttr("langfuse.trace.metadata." + entry.getKey(),
                    stringify(entry.getValue())));
        }
        // trace 的 input/output：v4 已标记 deprecated（改为看根 observation），
        // 但旧版看板与 trace 级 evaluator 仍读它，所以只在根节点上写一次，兼顾两代
        if (observation.parentId() == null) {
            if (properties.isCaptureContent()) {
                attributes.add(jsonAttr("langfuse.trace.input", trace.input()));
                attributes.add(jsonAttr("langfuse.trace.output", trace.output()));
            }
        }

        // ---- observation 级属性
        attributes.add(stringAttr("langfuse.observation.type", observation.type()));
        attributes.add(stringAttr("langfuse.observation.level", observation.level()));
        if (observation.statusMessage() != null) {
            attributes.add(stringAttr("langfuse.observation.status_message", observation.statusMessage()));
        }
        if (observation.model() != null) {
            attributes.add(stringAttr("langfuse.observation.model.name", observation.model()));
        }
        if (properties.isCaptureContent()) {
            attributes.add(jsonAttr("langfuse.observation.input", observation.input()));
            attributes.add(jsonAttr("langfuse.observation.output", observation.output()));
        }
        Map<String, Object> usage = new LinkedHashMap<>();
        if (observation.promptTokens() != null) {
            usage.put("input", observation.promptTokens());
        }
        if (observation.completionTokens() != null) {
            usage.put("output", observation.completionTokens());
        }
        if (!usage.isEmpty()) {
            // 官方映射：langfuse.observation.usage_details 接受 JSON 字符串
            attributes.add(stringAttr("langfuse.observation.usage_details", toJson(usage)));
        }
        for (Map.Entry<String, Object> entry : observation.metadata().entrySet()) {
            attributes.add(stringAttr("langfuse.observation.metadata." + entry.getKey(),
                    stringify(entry.getValue())));
        }

        span.put("attributes", attributes);
        span.put("status", Map.of(
                "code", AiTrace.LEVEL_ERROR.equals(observation.level()) ? 2 : 1,
                "message", observation.statusMessage() == null ? "" : observation.statusMessage()));
        return span;
    }

    /**
     * 旧的批次接口。保留它是为了兼容没有 OTLP 端点的自托管版本（&lt; v3.22.0）。
     * 注意：Langfuse Cloud 自 2026-11-16 起会拒绝除 score-create 以外的事件，所以只适合旧自托管。
     */
    private Map<String, Object> buildLegacy(List<LlmTrace> traces) {
        List<Map<String, Object>> batch = new ArrayList<>();
        for (LlmTrace trace : traces) {
            Map<String, Object> traceBody = new LinkedHashMap<>();
            traceBody.put("id", trace.traceId());
            traceBody.put("timestamp", ISO_MILLIS.format(Instant.now()));
            traceBody.put("name", trace.name());
            if (trace.userId() != null) {
                traceBody.put("userId", String.valueOf(trace.userId()));
            }
            if (trace.sessionId() != null && !trace.sessionId().isBlank()) {
                traceBody.put("sessionId", trace.sessionId());
            }
            traceBody.put("environment", properties.getEnvironment());
            if (properties.getRelease() != null && !properties.getRelease().isBlank()) {
                traceBody.put("release", properties.getRelease());
            }
            if (!trace.tags().isEmpty()) {
                traceBody.put("tags", trace.tags());
            }
            if (!trace.metadata().isEmpty()) {
                traceBody.put("metadata", trace.metadata());
            }
            if (properties.isCaptureContent()) {
                traceBody.put("input", trace.input());
                traceBody.put("output", trace.output());
            }
            batch.add(envelope("trace-create", traceBody));

            for (Observation observation : trace.observations()) {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("id", observation.id());
                body.put("traceId", trace.traceId());
                body.put("name", observation.name());
                body.put("startTime", nanosToIso(observation.startEpochNanos()));
                body.put("endTime", nanosToIso(observation.endEpochNanos()));
                body.put("environment", properties.getEnvironment());
                body.put("level", observation.level());
                if (observation.statusMessage() != null) {
                    body.put("statusMessage", observation.statusMessage());
                }
                if (observation.parentId() != null) {
                    body.put("parentObservationId", observation.parentId());
                }
                // 批次接口是强类型的：generation 走 generation-create，其余走 span-create
                String type = Observation.TYPE_GENERATION.equals(observation.type())
                        ? "generation-create" : "span-create";
                if (Observation.TYPE_GENERATION.equals(observation.type())) {
                    body.put("model", observation.model());
                    Map<String, Object> usage = new LinkedHashMap<>();
                    if (observation.promptTokens() != null) {
                        usage.put("input", observation.promptTokens());
                    }
                    if (observation.completionTokens() != null) {
                        usage.put("output", observation.completionTokens());
                    }
                    if (!usage.isEmpty()) {
                        body.put("usage", usage);
                    }
                }
                if (properties.isCaptureContent()) {
                    body.put("input", observation.input());
                    body.put("output", observation.output());
                }
                if (!observation.metadata().isEmpty()) {
                    body.put("metadata", observation.metadata());
                }
                batch.add(envelope(type, body));
            }
        }
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("batch", batch);
        return request;
    }

    private Map<String, Object> envelope(String type, Map<String, Object> body) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("id", LlmTrace.randomHex(32));
        event.put("type", type);
        event.put("timestamp", ISO_MILLIS.format(Instant.now()));
        event.put("body", body);
        return event;
    }

    // ------------------------------------------------------------------ 属性小工具

    private static Map<String, Object> stringAttr(String key, String value) {
        return Map.of("key", key, "value", Map.of("stringValue", value == null ? "" : value));
    }

    private static Map<String, Object> arrayAttr(String key, List<String> values) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (String value : values) {
            items.add(Map.of("stringValue", value == null ? "" : value));
        }
        return Map.of("key", key, "value", Map.of("arrayValue", Map.of("values", items)));
    }

    /**
     * Langfuse 的 input/output 期望是"(JSON) string"。这里对字符串做特殊处理：
     * 纯文本直接原样发（否则会被包成 {@code "文本"}，看板上多一对引号），
     * 结构化的才序列化成 JSON。
     */
    private Map<String, Object> jsonAttr(String key, Object value) {
        String text;
        if (value == null) {
            return stringAttr(key, "");
        } else if (value instanceof String s) {
            text = truncate(s);
        } else {
            text = truncate(toJson(value));
        }
        return stringAttr(key, text);
    }

    private String truncate(String text) {
        int max = properties.getMaxTextLength();
        if (max <= 0 || text.length() <= max) {
            return text;
        }
        return text.substring(0, max) + "\n…（已截断，完整内容见本地 ai_trace 表）";
    }

    private String stringify(Object value) {
        if (value == null) {
            return "";
        }
        return value instanceof String s ? s : toJson(value);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }

    private static String nanosToIso(long epochNanos) {
        return ISO_MILLIS.format(Instant.ofEpochMilli(epochNanos / 1_000_000L));
    }
}
