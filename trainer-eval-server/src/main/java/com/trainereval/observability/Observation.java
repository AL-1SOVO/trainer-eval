package com.trainereval.observability;

import java.util.Objects;

/**
 * 链路里的一个观测节点（对应 Langfuse 的 observation / OpenTelemetry 的 span）。
 *
 * <p>类型取值严格对齐 Langfuse 的 {@code langfuse.observation.type}：
 * {@code span / generation / event / embedding / agent / tool / chain / retriever / guardrail / evaluator}。
 * 之所以不自定义类型名，是因为 Langfuse 对无法识别的类型会静默降级成 {@code span} ——
 * 那样看板上"检索"和"工具"就分不出来了，追踪的价值直接减半。
 *
 * <p>这个类刻意保持"哑"：只攒数据，不做任何 IO。导出由 {@link LangfuseExporter} 在后台线程做，
 * 落库由 {@link Observability} 做。这样即使可观测性组件整体挂掉，
 * 业务代码里 {@code try (var span = trace.retriever(...)) { ... }} 也不会抛异常。
 */
public final class Observation implements AutoCloseable {

    public static final String TYPE_SPAN = "span";
    public static final String TYPE_CHAIN = "chain";
    public static final String TYPE_GENERATION = "generation";
    public static final String TYPE_RETRIEVER = "retriever";
    public static final String TYPE_TOOL = "tool";
    public static final String TYPE_AGENT = "agent";
    public static final String TYPE_EVALUATOR = "evaluator";
    public static final String TYPE_GUARDRAIL = "guardrail";

    private final String id;
    private final String traceId;
    private final String type;
    private final String name;

    /**
     * 计时用 {@link System#nanoTime()}（单调时钟，不受系统时间调整影响，适合算耗时）；
     * 但 OTLP 要的是 <b>Unix 纪元纳秒</b>，而 {@code nanoTime()} 的原点是任意的，
     * 直接拿它当时间戳会导出 1970 年附近的时间。所以两个都记：
     * 耗时用 nano 差，时间戳用墙上时钟。
     */
    private final long startNanos;
    private final long startEpochMillis;

    private String parentId;
    private String model;
    private Object input;
    private Object output;
    private Integer promptTokens;
    private Integer completionTokens;
    private String level = AiTrace.LEVEL_DEFAULT;
    private String statusMessage;
    private long endNanos;
    private boolean ended;

    private final java.util.Map<String, Object> metadata = new java.util.LinkedHashMap<>();

    Observation(String id, String traceId, String parentId, String type, String name, Object input) {
        this.id = id;
        this.traceId = traceId;
        this.parentId = parentId;
        this.type = type;
        this.name = name;
        this.input = input;
        this.startNanos = System.nanoTime();
        this.startEpochMillis = System.currentTimeMillis();
    }

    public String id() {
        return id;
    }

    public String traceId() {
        return traceId;
    }

    public String parentId() {
        return parentId;
    }

    public String type() {
        return type;
    }

    public String name() {
        return name;
    }

    public String model() {
        return model;
    }

    public Object input() {
        return input;
    }

    public Object output() {
        return output;
    }

    public Integer promptTokens() {
        return promptTokens;
    }

    public Integer completionTokens() {
        return completionTokens;
    }

    public String level() {
        return level;
    }

    public String statusMessage() {
        return statusMessage;
    }

    public long startNanos() {
        return startNanos;
    }

    public long endNanos() {
        return ended ? endNanos : startNanos;
    }

    /** OTLP 的 startTimeUnixNano：纪元纳秒。 */
    public long startEpochNanos() {
        return startEpochMillis * 1_000_000L;
    }

    /** OTLP 的 endTimeUnixNano：用"起始墙上时钟 + 单调时钟测得的耗时"合成，两边自洽。 */
    public long endEpochNanos() {
        if (!ended) {
            return System.currentTimeMillis() * 1_000_000L;
        }
        return startEpochMillis * 1_000_000L + (endNanos - startNanos);
    }

    public long latencyMillis() {
        return (endNanos() - startNanos) / 1_000_000L;
    }

    public boolean ended() {
        return ended;
    }

    public java.util.Map<String, Object> metadata() {
        return metadata;
    }

    void reparent(String parentId) {
        this.parentId = parentId;
    }

    public Observation model(String model) {
        this.model = model;
        return this;
    }

    public Observation usage(Integer prompt, Integer completion) {
        this.promptTokens = prompt;
        this.completionTokens = completion;
        return this;
    }

    public Observation metadata(String key, Object value) {
        if (key != null && value != null) {
            metadata.put(key, value);
        }
        return this;
    }

    public Observation error(String message) {
        this.level = AiTrace.LEVEL_ERROR;
        this.statusMessage = message;
        return end();
    }

    public Observation warn(String message) {
        this.level = AiTrace.LEVEL_WARNING;
        this.statusMessage = message;
        return end();
    }

    public Observation end(Object output) {
        this.output = output;
        return end();
    }

    /** 幂等：重复 end 不会覆盖首次结束时间（否则嵌套 try/finally 会把耗时算错）。 */
    public Observation end() {
        if (!ended) {
            this.endNanos = System.nanoTime();
            this.ended = true;
        }
        return this;
    }

    /**
     * 让 {@code try (Observation o = trace.retriever(...)) { ... }} 能成立。
     * 有这个兜底，"忘记 end" 导致的 span 永远没有结束时间就不会再发生；
     * 正常路径上先用 {@code end(output)} 记好输出，close 只是幂等的收尾。
     */
    @Override
    public void close() {
        end();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Observation other && Objects.equals(id, other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}
