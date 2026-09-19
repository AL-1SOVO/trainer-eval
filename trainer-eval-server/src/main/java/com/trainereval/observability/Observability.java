package com.trainereval.observability;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trainereval.observability.dto.TraceDetail;
import com.trainereval.observability.dto.TraceSummary;
import com.trainereval.observability.mapper.AiTraceMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 链路追踪的门面：业务代码只跟它打交道。
 *
 * <h3>它解决的具体问题</h3>
 * 一次"AI 学习助手"对话内部有：输入护栏 → 规划（1 次模型调用）→ N 轮工具调用
 * （每轮 1 次模型调用 + 若干工具，其中检索还会走 MimirQ）→ 反思 → 生成回答。
 * 只靠日志，无法回答"这次 8 秒里检索占了几秒""第 2 轮为什么重试""token 花在哪"。
 *
 * <h3>为什么记录是"双写"</h3>
 * 本地表 {@code ai_trace} 保证数据不丢、可被管理端直接查询；
 * Langfuse 是可选的可视化出口。两者互为兜底，且<b>都不允许影响业务</b>：
 * 写表失败只 warning，导出失败只在后台计数。
 *
 * <h3>关于 {@link #attach}/{@link #detach}</h3>
 * 检索服务（{@code RagRetrievalFacade}）与工具执行处在调用栈深处，
 * 为了给它们挂上 span 而不把 {@code LlmTrace} 参数一路透传，这里用了一个线程内的
 * "当前链路"。它只在同一次请求的同一条线程上生效，且必须在 finally 里 detach ——
 * 不 detach 会让下一个复用到该线程的请求把 span 记到别人的链路里。
 * （Spring MVC 用的是虚拟线程，一次请求一条线程，这个假设是成立的。）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class Observability {

    /** 链路名常量：集中定义，避免各处写字符串导致看板上同一条链路被拆成好几个名字。 */
    public static final String TRACE_AGENT_CHAT = "agent.chat";
    public static final String TRACE_AGENT_MULTI = "agent.multi";
    public static final String TRACE_RAG_RETRIEVE = "rag.retrieve";
    public static final String TRACE_AI_GRADE = "ai.grade";
    public static final String TRACE_ERROR_ANALYSIS = "error.analysis";

    private final LangfuseProperties properties;
    private final LangfuseExporter exporter;
    private final AiTraceMapper traceMapper;
    private final ObjectMapper objectMapper;

    private final ThreadLocal<LlmTrace> current = new ThreadLocal<>();

    // ------------------------------------------------------------------ 生命周期

    /**
     * 开一条链路。返回值必须用 try-with-resources 关闭，否则不会落库。
     * <p>
     * 注意：即使 {@code langfuse.enabled=false} 也照常返回可用的 trace —— 本地表始终记录。
     */
    public LlmTrace startTrace(String name, Long userId, String sessionId, Object input) {
        return new LlmTrace(this, name, userId, sessionId, input);
    }

    /** 把 trace 挂到当前线程，供深层组件取用；调用方必须成对调用 {@link #detach}。 */
    public void attach(LlmTrace trace) {
        if (trace != null) {
            current.set(trace);
        }
    }

    public void detach() {
        current.remove();
    }

    /** 当前线程正在记录的链路；没有则返回 null（深层组件据此决定要不要加 span）。 */
    public LlmTrace currentTrace() {
        return current.get();
    }

    /**
     * 便捷方法：在当前链路上加一个检索 span；没有活跃链路时返回一个"游离"的 span，
     * 对调用方是安全的空操作（不会 NPE，也不会污染别人的链路）。
     */
    public Observation retrieverSpan(String name, Object input) {
        LlmTrace trace = current.get();
        if (trace == null) {
            return new Observation(LlmTrace.randomHex(16), LlmTrace.randomHex(32), null,
                    Observation.TYPE_RETRIEVER, name, input);
        }
        return trace.retriever(name, input);
    }

    /** 同上，工具 span。 */
    public Observation toolSpan(String name, Object input) {
        LlmTrace trace = current.get();
        if (trace == null) {
            return new Observation(LlmTrace.randomHex(16), LlmTrace.randomHex(32), null,
                    Observation.TYPE_TOOL, name, input);
        }
        return trace.tool(name, input);
    }

    // ------------------------------------------------------------------ 落库 + 导出

    /** 由 {@link LlmTrace#close()} 回调。整条链路只在这里做一次 IO。 */
    void record(LlmTrace trace) {
        try {
            persist(trace);
        } catch (Exception e) {
            log.warn("链路落库失败（不影响业务）：traceId={}, err={}", trace.traceId(), e.toString());
        }
        try {
            exporter.export(trace);
        } catch (Exception e) {
            log.warn("链路导出投递失败（不影响业务）：traceId={}, err={}", trace.traceId(), e.toString());
        }
    }

    private void persist(LlmTrace trace) {
        AiTrace entity = new AiTrace();
        entity.setTraceId(trace.traceId());
        entity.setName(trace.name());
        entity.setUserId(trace.userId());
        entity.setSessionId(trace.sessionId());
        entity.setLatencyMs((int) Math.min(trace.latencyMillis(), Integer.MAX_VALUE));
        entity.setObservationCount(trace.observations().size());
        entity.setLevel(trace.level());
        entity.setStatusMessage(truncate(trace.statusMessage(), 500));
        entity.setInputBrief(trace.inputBrief(1000));
        entity.setOutputBrief(trace.outputBrief(1000));
        entity.setDetail(toJson(trace.observations()));

        int prompt = 0;
        int completion = 0;
        for (Observation observation : trace.observations()) {
            if (observation.promptTokens() != null) {
                prompt += observation.promptTokens();
            }
            if (observation.completionTokens() != null) {
                completion += observation.completionTokens();
            }
        }
        entity.setPromptTokens(prompt);
        entity.setCompletionTokens(completion);
        entity.setExported(properties.isConfigured() ? 1 : 0);
        entity.setCreateTime(LocalDateTime.now());
        traceMapper.insert(entity);
    }

    /**
     * 观测明细的 JSON 形态：只保留可展示字段，把 nanoTime 之类的内部计时丢掉 ——
     * 它们对排查没有意义，反而会让 JSON 变得不可读。
     * 时间统一转成"相对链路起点的毫秒偏移 + 绝对耗时"，这是看链路时真正想看的东西。
     */
    private String toJson(List<Observation> observations) {
        long base = Long.MAX_VALUE;
        for (Observation observation : observations) {
            base = Math.min(base, observation.startEpochNanos());
        }
        if (base == Long.MAX_VALUE) {
            base = System.currentTimeMillis() * 1_000_000L;
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Observation observation : observations) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", observation.id());
            row.put("parentId", observation.parentId());
            row.put("type", observation.type());
            row.put("name", observation.name());
            row.put("offsetMs", (observation.startEpochNanos() - base) / 1_000_000L);
            row.put("latencyMs", observation.latencyMillis());
            if (observation.model() != null) {
                row.put("model", observation.model());
            }
            if (observation.promptTokens() != null || observation.completionTokens() != null) {
                row.put("promptTokens", observation.promptTokens());
                row.put("completionTokens", observation.completionTokens());
            }
            if (observation.level() != null && !AiTrace.LEVEL_DEFAULT.equals(observation.level())) {
                row.put("level", observation.level());
            }
            if (observation.statusMessage() != null) {
                row.put("statusMessage", truncate(observation.statusMessage(), 300));
            }
            if (properties.isCaptureContent()) {
                if (observation.input() != null) {
                    row.put("input", LlmTrace.brief(observation.input(), 500));
                }
                if (observation.output() != null) {
                    row.put("output", LlmTrace.brief(observation.output(), 500));
                }
            }
            if (!observation.metadata().isEmpty()) {
                row.put("metadata", observation.metadata());
            }
            rows.add(row);
        }
        try {
            return objectMapper.writeValueAsString(rows);
        } catch (Exception e) {
            return "[]";
        }
    }

    // ------------------------------------------------------------------ 查询

    public Map<String, Object> status() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("localStore", localStoreStatus());
        status.put("langfuse", exporter.stats());
        status.put("captureContent", properties.isCaptureContent());
        status.put("hint", properties.isConfigured()
                ? "已配置 Langfuse，链路会异步导出；本地表同时保留，导出失败不影响业务。"
                : "未配置 Langfuse，链路只写本地 ai_trace 表。配置 langfuse.enabled/public-key/secret-key 后可导出。");
        return status;
    }

    private Map<String, Object> localStoreStatus() {
        Map<String, Object> local = new LinkedHashMap<>();
        local.put("table", "ai_trace");
        try {
            local.put("available", true);
            local.put("total", traceMapper.selectCount(null));
        } catch (Exception e) {
            // 表不存在时给出可执行的提示，而不是把 500 抛到页面上
            local.put("available", false);
            local.put("message", "ai_trace 表不可用，请执行 db/ai_trace.sql：" + e.getMessage());
        }
        return local;
    }

    /** 最近链路列表（不含 detail，避免列表响应过大）。 */
    public List<TraceSummary> recent(String name, int limit) {
        int size = Math.min(Math.max(limit, 1), 200);
        LambdaQueryWrapper<AiTrace> wrapper = new LambdaQueryWrapper<AiTrace>()
                .orderByDesc(AiTrace::getId)
                .last("LIMIT " + size);
        if (name != null && !name.isBlank()) {
            wrapper.eq(AiTrace::getName, name);
        }
        List<AiTrace> rows = traceMapper.selectList(wrapper);
        List<TraceSummary> result = new ArrayList<>();
        for (AiTrace row : rows) {
            result.add(TraceSummary.from(row));
        }
        return result;
    }

    /** 单条链路的完整明细（含每一步）。 */
    public TraceDetail detail(Long id) {
        AiTrace row = traceMapper.selectById(id);
        if (row == null) {
            return null;
        }
        return TraceDetail.from(row);
    }

    /** 按 traceId 查，便于拿 Langfuse 里的 id 反查本地记录。 */
    public TraceDetail detailByTraceId(String traceId) {
        AiTrace row = traceMapper.selectOne(new LambdaQueryWrapper<AiTrace>()
                .eq(AiTrace::getTraceId, traceId).last("LIMIT 1"));
        return row == null ? null : TraceDetail.from(row);
    }

    /**
     * 自检：造一条合成链路并<b>同步</b>导出一次。
     * <p>
     * 异步导出对使用者是黑盒（"我配了，但没数据"），所以必须有一个"现在立刻发一次、
     * 把真实错误原样返回"的按钮。返回体里带 endpoint，配错地址时一眼能看出来。
     */
    public Map<String, Object> testExport() {
        Map<String, Object> result = new LinkedHashMap<>();
        try (LlmTrace trace = startTrace("observability.selftest", null, null,
                Map.of("purpose", "验证 Langfuse 连通性"))) {
            trace.tag("selftest");
            try (Observation generation = trace.generation("selftest.generation",
                    "selftest-model", Map.of("prompt", "ping"))) {
                generation.end("pong").usage(1, 1);
            }
            trace.metadata("source", "POST /api/v1/observability/test");
            trace.output("自检完成");
            result.putAll(exporter.exportNow(trace));
            result.put("traceId", trace.traceId());
        } catch (Exception e) {
            result.put("ok", false);
            result.put("message", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        return result;
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return null;
        }
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }
}
