package com.trainereval.rag.mimirq;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * MimirQ 检索服务的接入配置。
 * <p>
 * 对应《企业级RAG实施方案.md》阶段三 3.4 的配置项，另加两项工程必需项：
 * <ul>
 *   <li>{@code connectTimeout}：连接超时单独配置。MimirQ 未启动时本机是"连接被拒"，
 *       会立刻返回，不必等满 5s —— 这让"服务不在"的代价接近 0。</li>
 *   <li>{@code maxCoursesPerQuery}：Dify External Knowledge 协议一次只接受一个
 *       knowledge_id，因此多课程需要发多次请求。用它限制扇出上限。</li>
 * </ul>
 */
@Data
@Component
@ConfigurationProperties(prefix = "mimirq")
public class MimirQProperties {

    /** 总开关。关闭时直接走本地检索，完全不发外部请求。 */
    private boolean enabled = false;

    private String baseUrl = "http://localhost:8000";

    /** Dify 协议用 Bearer 鉴权，对应 MimirQ 的 DIFY_EXTERNAL_KNOWLEDGE_API_KEYS。 */
    private String apiKey = "";

    /** 检索端点路径（单独可配，便于 MimirQ 版本升级时调整）。 */
    private String retrievalPath = "/api/v1/integrations/dify/retrieval";

    /** 读超时，对应方案文档的 timeout: 5s。 */
    private Duration timeout = Duration.ofSeconds(5);

    /** 连接超时。 */
    private Duration connectTimeout = Duration.ofMillis(1500);

    private int topK = 6;

    private double scoreThreshold = 0.4;

    /**
     * 相关度下限：低于它的命中直接丢弃（默认 0.01，等价于"只丢 score=0"）。
     * <p>
     * 为什么在 Java 侧再设一道，而不是只靠 MimirQ 的 {@code scoreThreshold}：
     * 实测（2026-09-18）发现 MimirQ 的 {@code score_threshold} <b>只作用于向量通道</b>，
     * 关键词通道的命中不受它约束，而且这些命中会以 <b>score = 0.0</b> 返回。
     * 把 score_threshold 调到 0.7，MimirQ 依然返回 score=0.0 的记录 ——
     * 于是一个语料里根本没有的问题（如"Kubernetes 容器编排"）也会拿到两条"资料"，
     * 助手再据此编造引用。
     * <p>
     * 代价说明：0 分的命中是纯词面匹配，而<b>本地混合检索已经在做同一件事</b>
     * （ngram 全文 + 子串），且门面会把本地结果作为补充。所以丢弃它们不损失召回。
     */
    private double minScore = 0.01;

    /**
     * MimirQ 的 latency_profile 扩展。取 "fast" 会关闭昂贵的回退/重排分支，
     * 适合 Agent 在线对话；设为空则走质量优先路径。
     */
    private String latencyProfile = "fast";

    /** 单次检索最多查询几个课程知识库（避免 N 次串行请求拖长延迟）。 */
    private int maxCoursesPerQuery = 5;

    /** 连续失败多少次后熔断。 */
    private int failureThreshold = 3;

    /** 熔断后的冷却时间，冷却结束会再试一次。 */
    private Duration cooldown = Duration.ofSeconds(60);

    /** knowledge_id 的命名模板，{courseId} 会被替换。 */
    private String knowledgeIdPattern = "course-{courseId}";

    // ------------------------------------------------------------------ 写入（自动灌库）

    /**
     * 自动灌库总开关。
     * <p>
     * 与 {@code enabled}（检索）分开，因为两者的依赖不同：检索只要一个 API Key，
     * 写入还需要<b>账号密码</b>去换 JWT。默认关，是为了"没配账号密码时不要每传一份资料就报一次错"。
     */
    private boolean ingestEnabled = false;

    /** 写权限账号。MimirQ 的写入接口要登录换 JWT，Dify 的 API Key 在这里不通用。 */
    private String username = "";

    private String password = "";

    private String loginPath = "/api/v1/auth/login";

    private String datasetsPath = "/api/v1/datasets";

    private String documentsPath = "/api/v1/documents";

    public String knowledgeIdFor(Long courseId) {
        return knowledgeIdPattern.replace("{courseId}", String.valueOf(courseId));
    }
}
