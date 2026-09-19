package com.trainereval.observability;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * LLM/RAG/Agent 链路追踪的接入配置。
 *
 * <h3>为什么要有这一层</h3>
 * 之前排查质量问题只能翻 {@code ai_grading_log} 和 {@code rag_retrieval_trace} 两张表：
 * 能看到"这次评了几分""这次召回了哪几条"，但看不到<b>一次请求内部的完整链路</b>——
 * Agent 规划用了多久、第几轮工具调用花了多少 token、检索这一跳占了多少时间、
 * 哪一次模型调用是失败的。没有这条链路，优化就只能靠猜。
 *
 * <h3>为什么同时提供"本地存储"和"导出到 Langfuse"</h3>
 * Langfuse 是可选基础设施（自托管要 Postgres + ClickHouse + Redis + MinIO，很重）。
 * 所以设计成：
 * <ul>
 *   <li><b>本地表 {@code ai_trace} 永远是主路径</b>——不依赖任何外部服务，
 *       管理端"链路追踪"页直接可查，导出失败也不影响它。</li>
 *   <li><b>Langfuse 是可选的第二出口</b>——配了才发，且导出是异步的、失败的，
 *       永远不会让一次对话因为可观测性组件挂掉而失败。</li>
 * </ul>
 *
 * <h3>为什么默认用 OTLP 而不是 /api/public/ingestion</h3>
 * 查 Langfuse 官方 API 契约（{@code fern/apis/server/definition/ingestion.yml}）后确认：
 * 老的 {@code POST /api/public/ingestion} 批次接口已被标记 deprecated，
 * Langfuse Cloud 将于 <b>2026-11-16</b> 起只接受 {@code score-create}，
 * 官方给出的替代路径就是 {@code POST /api/public/otel/v1/traces}。
 * 因此默认走 OTLP；自托管的旧版本仍可用 {@code langfuse.mode=legacy} 切回批次接口。
 */
@Data
@Component
@ConfigurationProperties(prefix = "langfuse")
public class LangfuseProperties {

    /** 是否把链路导出到 Langfuse。关闭时本地表照常记录。 */
    private boolean enabled = false;

    /** Langfuse 地址，如 http://localhost:3000 或 https://cloud.langfuse.com。 */
    private String host = "http://localhost:3000";

    private String publicKey = "";

    private String secretKey = "";

    /**
     * 导出协议：
     * <ul>
     *   <li>{@code otlp}（默认）—— {@code POST /api/public/otel/v1/traces}，官方推荐路径</li>
     *   <li>{@code legacy} —— {@code POST /api/public/ingestion}，兼容旧自托管版本</li>
     * </ul>
     */
    private String mode = "otlp";

    /** Langfuse 的 environment 维度，便于区分 dev/prod 的观测数据。 */
    private String environment = "default";

    /** Langfuse 的 release 维度，建议填应用版本或 git 短哈希。 */
    private String release = "";

    /** OTLP 的 service.name。 */
    private String serviceName = "trainer-eval";

    /** 采样率（0~1）。Agent 流量不高，默认全采。 */
    private double sampleRate = 1.0;

    /** 单次导出的超时。导出在后台线程，超时只影响这一批。 */
    private Duration timeout = Duration.ofSeconds(5);

    /** 后台队列容量。满了就丢弃并计数——可观测性不能反过来拖垮业务。 */
    private int queueCapacity = 2000;

    /** 单批最多几条 trace（OTLP 的 resourceSpans 数量上限远高于此，这里防的是请求体过大）。 */
    private int batchSize = 20;

    /** 单条文本字段截断长度。提示词/回答可能很长，导出前截断避免请求体膨胀。 */
    private int maxTextLength = 4000;

    /** 是否在 trace 里记录完整提示词与回答。关掉只留长度与摘要（合规敏感场景用）。 */
    private boolean captureContent = true;

    public boolean isConfigured() {
        return enabled
                && host != null && !host.isBlank()
                && publicKey != null && !publicKey.isBlank()
                && secretKey != null && !secretKey.isBlank();
    }

    public boolean isOtlp() {
        return !"legacy".equalsIgnoreCase(mode);
    }
}
