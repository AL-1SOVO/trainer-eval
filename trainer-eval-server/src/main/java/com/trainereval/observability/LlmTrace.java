package com.trainereval.observability;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一条链路的句柄。业务代码的典型用法：
 *
 * <pre>{@code
 * try (LlmTrace trace = observability.startTrace("agent.chat", userId, sessionId, question)) {
 *     try (Observation gen = trace.generation("plan", model, prompt)) {
 *         ChatResponse r = chatModel.call(prompt);
 *         gen.end(answer).usage(p, c);
 *     }
 *     trace.output(answer);
 * }
 * }</pre>
 *
 * <h3>设计要点</h3>
 * <ul>
 *   <li><b>必须 close</b>：只有 close 时才会落库 + 导出。写成 try-with-resources 就不会漏。
 *       即便业务抛异常，也会记为 ERROR 并保留已发生的耗时——异常链路恰恰是最需要被看到的。</li>
 *   <li><b>根节点即 trace</b>：OTLP 里"trace 由根 span 创建"，所以这里也把根 observation 显式建出来，
 *       子节点默认挂在根下。这样导出到 Langfuse 后是标准的树形，而不是一串游离的 span。</li>
 *   <li><b>trace 级属性会写到每一个 span</b>：这不是冗余。Langfuse 官方文档明确说明
 *       {@code langfuse.user.id / session.id / trace.name / release / tags / metadata}
 *       只有在<b>每个 span 上都有</b>时，按用户/会话过滤与聚合才是可靠的。</li>
 * </ul>
 */
public final class LlmTrace implements AutoCloseable {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private final String traceId;
    private final String name;
    private final Long userId;
    private final String sessionId;
    /** 耗时用单调时钟，时间戳用墙上时钟——原因见 {@link Observation} 的注释。 */
    private final long startNanos;
    private final long startEpochMillis;

    private final Observation root;
    private final List<Observation> observations = Collections.synchronizedList(new ArrayList<>());
    private final List<String> tags = Collections.synchronizedList(new ArrayList<>());
    private final Map<String, Object> metadata = Collections.synchronizedMap(new LinkedHashMap<>());

    private Object input;
    private Object output;
    private String level = AiTrace.LEVEL_DEFAULT;
    private String statusMessage;
    private long endNanos;
    private boolean closed;

    private final Observability owner;

    LlmTrace(Observability owner, String name, Long userId, String sessionId, Object input) {
        this.owner = owner;
        this.traceId = randomHex(32);
        this.name = name;
        this.userId = userId;
        this.sessionId = sessionId == null ? null : String.valueOf(sessionId);
        this.input = input;
        this.startNanos = System.nanoTime();
        this.startEpochMillis = System.currentTimeMillis();
        // 根 observation 用 chain 类型：它代表"一次完整业务流程"，Langfuse 里 chain 正是这个语义
        this.root = new Observation(randomHex(16), traceId, null, Observation.TYPE_CHAIN, name, input);
        this.observations.add(root);
    }

    public String traceId() {
        return traceId;
    }

    public String name() {
        return name;
    }

    public Long userId() {
        return userId;
    }

    public String sessionId() {
        return sessionId;
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
        return closed ? endNanos : System.nanoTime();
    }

    /** OTLP 的 startTimeUnixNano：纪元纳秒（{@code System.nanoTime()} 的原点是任意的，不能当时间戳用）。 */
    public long startEpochNanos() {
        return startEpochMillis * 1_000_000L;
    }

    public long endEpochNanos() {
        if (!closed) {
            return System.currentTimeMillis() * 1_000_000L;
        }
        return startEpochMillis * 1_000_000L + (endNanos - startNanos);
    }

    public long latencyMillis() {
        return (endNanos() - startNanos) / 1_000_000L;
    }

    public Observation root() {
        return root;
    }

    public List<Observation> observations() {
        synchronized (observations) {
            return new ArrayList<>(observations);
        }
    }

    public List<String> tags() {
        synchronized (tags) {
            return new ArrayList<>(tags);
        }
    }

    public Map<String, Object> metadata() {
        return new LinkedHashMap<>(metadata);
    }

    public Object input() {
        return input;
    }

    public Object output() {
        return output;
    }

    // ------------------------------------------------------------------ 便捷入口

    public LlmTrace input(Object input) {
        this.input = input;
        return this;
    }

    public LlmTrace output(Object output) {
        this.output = output;
        // 根节点的输出与 trace 输出保持一致：Langfuse v4 已把 trace 的 input/output 标记为
        // deprecated，改为读根 observation 的 input/output，两边都写才不会在新版看板上丢信息
        this.root.end(output);
        return this;
    }

    public LlmTrace tag(String tag) {
        if (tag != null && !tag.isBlank()) {
            tags.add(tag);
        }
        return this;
    }

    public LlmTrace metadata(String key, Object value) {
        if (key != null && value != null) {
            metadata.put(key, value);
        }
        return this;
    }

    public LlmTrace error(String message) {
        this.level = AiTrace.LEVEL_ERROR;
        this.statusMessage = message;
        this.root.error(message);
        return this;
    }

    public LlmTrace warn(String message) {
        if (!AiTrace.LEVEL_ERROR.equals(this.level)) {
            this.level = AiTrace.LEVEL_WARNING;
        }
        this.statusMessage = message;
        return this;
    }

    // ------------------------------------------------------------------ 子节点

    public Observation span(String name, Object input) {
        return child(root, Observation.TYPE_SPAN, name, input);
    }

    /** 检索一跳：类型用 retriever，Langfuse 会按"检索"渲染（能看 query 与命中）。 */
    public Observation retriever(String name, Object input) {
        return child(root, Observation.TYPE_RETRIEVER, name, input);
    }

    /** 工具调用：类型用 tool。MCP 工具与本地 @Tool 都走这里，便于对比各自耗时。 */
    public Observation tool(String name, Object input) {
        return child(root, Observation.TYPE_TOOL, name, input);
    }

    /** 一次模型调用：类型用 generation，才能记录 model 与 token 用量。 */
    public Observation generation(String name, String model, Object input) {
        return child(root, Observation.TYPE_GENERATION, name, input).model(model);
    }

    /** 多智能体里的一个角色（规划者/复核者/汇总者），类型用 agent。 */
    public Observation agent(String name, Object input) {
        return child(root, Observation.TYPE_AGENT, name, input);
    }

    public Observation child(Observation parent, String type, String name, Object input) {
        String parentId = parent == null ? root.id() : parent.id();
        Observation observation = new Observation(randomHex(16), traceId, parentId, type, name, input);
        observations.add(observation);
        return observation;
    }

    // ------------------------------------------------------------------ 结束

    /** 未显式 output 时，也要把根节点收尾，否则它永远没有 endTime。 */
    void finishPending() {
        if (!root.ended()) {
            root.end(output);
        }
        for (Observation observation : observations()) {
            if (!observation.ended()) {
                observation.end();
            }
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        this.endNanos = System.nanoTime();
        this.closed = true;
        finishPending();
        if (owner != null) {
            owner.record(this);
        }
    }

    // ------------------------------------------------------------------ 工具

    static String randomHex(int length) {
        byte[] bytes = new byte[length / 2];
        RANDOM.nextBytes(bytes);
        char[] out = new char[length];
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xFF;
            out[i * 2] = HEX[v >>> 4];
            out[i * 2 + 1] = HEX[v & 0x0F];
        }
        return new String(out);
    }

    /** 摘要：给本地表的 input_brief/output_brief 用，避免超长文本撑爆行。 */
    public String inputBrief(int max) {
        return brief(input, max);
    }

    public String outputBrief(int max) {
        return brief(output, max);
    }

    static String brief(Object value, int max) {
        if (value == null) {
            return null;
        }
        String text = value instanceof String s ? s : String.valueOf(value);
        text = text.replaceAll("\\s+", " ").trim();
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }
}
