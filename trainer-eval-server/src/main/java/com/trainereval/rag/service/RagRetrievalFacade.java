package com.trainereval.rag.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trainereval.rag.dto.RetrievalHit;
import com.trainereval.rag.entity.RagRetrievalTrace;
import com.trainereval.rag.mapper.RagRetrievalTraceMapper;
import com.trainereval.rag.mimirq.MimirQClient;
import com.trainereval.rag.mimirq.MimirQProperties;
import com.trainereval.training.entity.TraChapter;
import com.trainereval.training.entity.TraCourse;
import com.trainereval.training.entity.TraMaterial;
import com.trainereval.training.mapper.TraChapterMapper;
import com.trainereval.training.mapper.TraCourseMapper;
import com.trainereval.training.mapper.TraMaterialMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 检索门面：把"用哪个检索引擎"这一个决策收敛到一处，对上层（Agent 工具）暴露稳定接口。
 * <p>
 * 三级链路（对应方案文档第九节"风险与降级"）：
 * <pre>
 *   MimirQ（混合召回+重排）  →  本地混合检索（ngram 全文 + 子串 + RRF）  →  子串兜底
 * </pre>
 * 两个刻意的设计：
 * <ol>
 *   <li><b>熔断</b>：MimirQ 连续失败 N 次后打开断路器，冷却期内直接走本地，
 *       避免每次提问都白等一个读超时。冷却结束自动半开重试。</li>
 *   <li><b>权限裁剪对两条路径同时生效</b>：否则"服务挂了就降级"等于 fail-open，
 *       绕过的恰恰是权限本身。</li>
 * </ol>
 * 轨迹由本类统一写入，并记录<b>实际服务方</b>（backend），
 * 这样复盘时能回答"这次是知识库没召回，还是降级了没查"。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagRetrievalFacade {

    public static final String BACKEND_MIMIRQ = "MIMIRQ";
    public static final String BACKEND_LOCAL = "LOCAL_HYBRID";

    /** 同一份资料最多贡献几条，与本地路径的多样性策略保持一致。 */
    private static final int MAX_PER_MATERIAL = 2;

    private final MimirQProperties mimirqProperties;
    private final MimirQClient mimirqClient;
    private final RetrievalService localRetrieval;
    private final KnowledgeAccessService accessService;
    private final TraMaterialMapper materialMapper;
    private final TraChapterMapper chapterMapper;
    private final TraCourseMapper courseMapper;
    private final RagRetrievalTraceMapper traceMapper;
    private final ObjectMapper objectMapper;

    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private volatile long openUntilMillis = 0L;
    private volatile String lastFailure = null;

    /** 一次检索的结果：命中 + 实际服务方 + 是否发生降级。 */
    public record RagResult(List<RetrievalHit> hits, String backend, boolean degraded, String note) {

        public boolean isEmpty() {
            return hits.isEmpty();
        }
    }

    public RagResult search(Long userId, String sessionId, String query, Long requestedCourseId, int topK) {
        long start = System.currentTimeMillis();

        KnowledgeAccessService.AccessScope scope = accessService.resolve(userId, requestedCourseId);
        if (scope.isEmpty()) {
            // 学生未报名相关课程：这是权限裁剪的结果，不是检索失败，要如实区分
            return new RagResult(List.of(), BACKEND_LOCAL, false,
                    "当前账号未报名相关课程，看不到该范围的资料");
        }

        // ---------- 一级：MimirQ ----------
        if (mimirqProperties.isEnabled() && breakerAllowsRequest()) {
            try {
                List<RetrievalHit> hits = searchMimirQ(scope, requestedCourseId, query, topK);
                onSuccess();
                trace(userId, sessionId, query, BACKEND_MIMIRQ, hits, start);
                // 命中为空不是故障：知识库确实没有相关内容，要如实告知而不是继续降级
                return new RagResult(hits, BACKEND_MIMIRQ, false,
                        hits.isEmpty() ? "知识库中未找到相关内容" : null);
            } catch (Exception e) {
                onFailure(e);
            }
        }

        // ---------- 二级/三级：本地混合（内部含子串兜底），同样受权限裁剪约束 ----------
        List<RetrievalHit> local = searchLocal(scope, requestedCourseId, userId, sessionId, query, topK);
        boolean degraded = mimirqProperties.isEnabled();
        String note = null;
        if (degraded) {
            note = lastFailure == null
                    ? "知识库服务不可用，已降级为本地检索"
                    : "知识库服务不可用（" + lastFailure + "），已降级为本地检索";
        }
        trace(userId, sessionId, query, BACKEND_LOCAL, local, start);
        return new RagResult(local, BACKEND_LOCAL, degraded, note);
    }

    // ------------------------------------------------------------------ MimirQ 路径

    private List<RetrievalHit> searchMimirQ(KnowledgeAccessService.AccessScope scope,
                                            Long requestedCourseId, String query, int topK) {
        Set<Long> courses = resolveTargetCourses(scope, requestedCourseId);
        if (courses.isEmpty()) {
            return List.of();
        }
        Map<String, TraMaterial> byTitle = loadMaterialsByTitle(courses);

        List<RetrievalHit> merged = new ArrayList<>();
        int attempted = 0;
        int failed = 0;
        RuntimeException lastError = null;

        for (Long courseId : courses) {
            attempted++;
            List<MimirQClient.MimirQRecord> records;
            try {
                // Dify 协议一次只能查一个 knowledge_id，因此多课程要发多次请求
                records = mimirqClient.retrieveOne(mimirqProperties.knowledgeIdFor(courseId), query);
            } catch (RuntimeException e) {
                // 部分可用：某门课还没建知识库（MimirQ 返回 2001 mapping not found）或临时出错，
                // 不应让整次检索失败。跳过它，继续查其它课程。
                failed++;
                lastError = e;
                log.debug("课程 {} 的知识库检索失败，跳过: {}", courseId, e.getMessage());
                continue;
            }
            int index = 0;
            for (MimirQClient.MimirQRecord record : records) {
                merged.add(toHit(record, byTitle, courseId, index++));
            }
        }

        // 只有"所有课程都失败"才视为知识库不可用（交给上层降级并触发熔断）；
        // 部分失败则返回已取到的结果，避免一门课拖垮整次检索。
        if (attempted > 0 && failed == attempted && lastError != null) {
            throw lastError;
        }
        if (failed > 0) {
            log.info("MimirQ 部分课程检索失败（{}/{}），已返回其余结果", failed, attempted);
        }

        merged.sort(Comparator.comparingDouble(RetrievalHit::score).reversed());
        return diversify(merged, topK);
    }

    /**
     * MimirQ 命中 → 统一 RetrievalHit。
     * <p>
     * 资料映射优先用 <b>稳定 ID</b>（上传时写入 chunk metadata 的 {@code material_id}），
     * 标题匹配只作兜底。原因：MimirQ 的 record.title 是"文档名"，取决于导入时的 filename，
     * 与 tra_material.title 不一定一致（实测上传为 {@code m4.txt} 时就对不上，
     * 导致引用退化成不可跳转的 MQ- 格式）。用 ID 映射既稳又能给出可点击的 M{id}#{idx}。
     */
    private RetrievalHit toHit(MimirQClient.MimirQRecord record, Map<String, TraMaterial> byTitle,
                               Long courseId, int index) {
        String title = record.title() == null ? "" : record.title();

        TraMaterial material = null;
        Long materialId = extractMaterialId(record.metadata());
        if (materialId != null) {
            material = materialMapper.selectById(materialId);
        }
        if (material == null && !title.isBlank()) {
            // 兜底：按标题匹配（适用于以资料标题命名的知识库文档）
            material = byTitle.get(title.trim());
            if (material == null) {
                // 知识库文档名通常带扩展名（如 "数据保护三大原则详解.txt"），
                // 去掉扩展名后再匹配一次，否则永远对不上 byTitle（键是资料标题、无扩展名）
                String noExt = title.trim().replaceAll("\\.[A-Za-z0-9]{1,6}$", "").trim();
                if (!noExt.isEmpty()) {
                    material = byTitle.get(noExt);
                }
            }
        }
        if (material != null) {
            materialId = material.getId();
        } else {
            materialId = null;
        }

        Long chapterId = material == null ? null : material.getChapterId();
        Long resolvedCourseId = courseId;
        String chapterTitle = "";
        String courseName = "";
        String displayTitle = material != null && material.getTitle() != null ? material.getTitle() : title;
        if (material != null && material.getChapterId() != null) {
            TraChapter chapter = chapterMapper.selectById(material.getChapterId());
            if (chapter != null) {
                chapterTitle = chapter.getTitle() == null ? "" : chapter.getTitle();
                if (chapter.getCourseId() != null) {
                    resolvedCourseId = chapter.getCourseId();
                    TraCourse course = courseMapper.selectById(chapter.getCourseId());
                    courseName = course == null ? "" : course.getCourseName();
                }
            }
        }
        // 引用编号：资料对得上时沿用 M{id}#{idx} 规则（前端可跳转）；
        // 对不上（例如 MimirQ 里是独立导入、本地无对应资料的文档）退化为 MQ- 前缀，仅作展示
        String citation = materialId != null
                ? RetrievalHit.buildCitation(materialId, index)
                : "MQ-" + resolvedCourseId + "-" + index;

        String content = record.content() == null ? "" : record.content();
        String snippet = content.length() <= 300 ? content : content.substring(0, 300) + "…";

        return new RetrievalHit(citation, null, materialId, resolvedCourseId, chapterId, index,
                displayTitle.isBlank() ? "知识库片段" : displayTitle, courseName, chapterTitle, snippet,
                record.score(), "MIMIRQ");
    }

    /** 从 MimirQ 返回的 metadata 里取 material_id（可能是数字或字符串）。 */
    private Long extractMaterialId(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return null;
        }
        Object raw = metadata.get("material_id");
        if (raw == null) {
            return null;
        }
        if (raw instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(raw).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Set<Long> resolveTargetCourses(KnowledgeAccessService.AccessScope scope, Long requestedCourseId) {
        if (requestedCourseId != null) {
            return Set.of(requestedCourseId);
        }
        if (scope.unrestricted()) {
            // 系统/员工上下文且未指定课程：用资料表反推"真正有正文"的课程。
            // 必须过滤空正文 —— 否则会对只有占位资料（如"复习"、"video"这类空内容）的课程
            // 也发请求，而 MimirQ 侧没有对应 knowledge 映射，会返回 2001 mapping not found。
            List<TraMaterial> materials = materialMapper.selectList(
                    new LambdaQueryWrapper<TraMaterial>()
                            .select(TraMaterial::getChapterId)
                            .isNotNull(TraMaterial::getContent)
                            .ne(TraMaterial::getContent, ""));
            Set<Long> courseIds = new LinkedHashSet<>();
            for (TraMaterial material : materials) {
                if (material.getChapterId() == null) {
                    continue;
                }
                TraChapter chapter = chapterMapper.selectById(material.getChapterId());
                if (chapter != null && chapter.getCourseId() != null) {
                    courseIds.add(chapter.getCourseId());
                }
            }
            return cap(courseIds);
        }
        return cap(new LinkedHashSet<>(scope.courseIds()));
    }

    private Set<Long> cap(Set<Long> courseIds) {
        int max = Math.max(1, mimirqProperties.getMaxCoursesPerQuery());
        if (courseIds.size() <= max) {
            return courseIds;
        }
        Set<Long> capped = new LinkedHashSet<>();
        for (Long id : courseIds) {
            if (capped.size() >= max) {
                break;
            }
            capped.add(id);
        }
        log.debug("课程数 {} 超过上限 {}，本次只查前 {} 个", courseIds.size(), max, max);
        return capped;
    }

    /** 标题 → 资料，用于把 MimirQ 命中映射回本地可跳转的引用。 */
    private Map<String, TraMaterial> loadMaterialsByTitle(Set<Long> courseIds) {
        Map<String, TraMaterial> byTitle = new HashMap<>();
        for (Long courseId : courseIds) {
            List<TraChapter> chapters = chapterMapper.selectList(
                    new LambdaQueryWrapper<TraChapter>().eq(TraChapter::getCourseId, courseId));
            if (chapters.isEmpty()) {
                continue;
            }
            Set<Long> chapterIds = new HashSet<>();
            for (TraChapter chapter : chapters) {
                chapterIds.add(chapter.getId());
            }
            List<TraMaterial> materials = materialMapper.selectList(
                    new LambdaQueryWrapper<TraMaterial>().in(TraMaterial::getChapterId, chapterIds));
            for (TraMaterial material : materials) {
                if (material.getTitle() != null && !material.getTitle().isBlank()) {
                    byTitle.putIfAbsent(material.getTitle().trim(), material);
                }
            }
        }
        return byTitle;
    }

    // ------------------------------------------------------------------ 本地路径

    private List<RetrievalHit> searchLocal(KnowledgeAccessService.AccessScope scope, Long requestedCourseId,
                                           Long userId, String sessionId, String query, int topK) {
        // 多取一些候选再按权限过滤：裁剪发生在检索之后，取宽一点避免过滤后条数不足
        int candidateTopK = scope.unrestricted() ? topK : Math.max(topK * 3, topK + 5);
        List<RetrievalHit> candidates =
                localRetrieval.retrieve(userId, sessionId, query, requestedCourseId, candidateTopK, false);

        List<RetrievalHit> allowed = new ArrayList<>();
        for (RetrievalHit hit : candidates) {
            if (scope.allows(hit.courseId())) {
                allowed.add(hit);
            }
        }
        if (allowed.size() < candidates.size()) {
            log.debug("权限裁剪过滤掉 {} 条不可见命中", candidates.size() - allowed.size());
        }
        return allowed.size() > topK ? allowed.subList(0, topK) : allowed;
    }

    private List<RetrievalHit> diversify(List<RetrievalHit> hits, int topK) {
        List<RetrievalHit> picked = new ArrayList<>();
        Map<String, Integer> perMaterial = new HashMap<>();
        for (RetrievalHit hit : hits) {
            String key = hit.materialId() != null ? String.valueOf(hit.materialId()) : hit.citation();
            int used = perMaterial.getOrDefault(key, 0);
            if (used >= MAX_PER_MATERIAL) {
                continue;
            }
            perMaterial.put(key, used + 1);
            picked.add(hit);
            if (picked.size() >= topK) {
                break;
            }
        }
        return picked;
    }

    // ------------------------------------------------------------------ 熔断

    private boolean breakerAllowsRequest() {
        long openUntil = this.openUntilMillis;
        if (openUntil == 0L) {
            return true;
        }
        if (System.currentTimeMillis() >= openUntil) {
            // 冷却结束，半开：放一次请求试试
            log.info("MimirQ 熔断冷却结束，允许半开重试");
            this.openUntilMillis = 0L;
            return true;
        }
        return false;
    }

    private void onSuccess() {
        if (consecutiveFailures.get() > 0) {
            log.info("MimirQ 恢复正常");
        }
        consecutiveFailures.set(0);
        openUntilMillis = 0L;
        lastFailure = null;
    }

    private void onFailure(Exception e) {
        lastFailure = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        if (lastFailure.length() > 120) {
            lastFailure = lastFailure.substring(0, 120) + "…";
        }
        int failures = consecutiveFailures.incrementAndGet();
        log.warn("MimirQ 检索失败（连续 {} 次）: {}", failures, lastFailure);
        if (failures >= Math.max(1, mimirqProperties.getFailureThreshold())) {
            long cooldown = mimirqProperties.getCooldown().toMillis();
            openUntilMillis = System.currentTimeMillis() + cooldown;
            consecutiveFailures.set(0);
            log.warn("MimirQ 连续失败达阈值，熔断 {} 秒后重试", cooldown / 1000);
        }
    }

    /** 供管理端查看熔断状态。 */
    public Map<String, Object> health() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("enabled", mimirqProperties.isEnabled());
        status.put("baseUrl", mimirqProperties.getBaseUrl());
        status.put("circuitOpen", openUntilMillis > System.currentTimeMillis());
        status.put("circuitOpenRemainMs", Math.max(0L, openUntilMillis - System.currentTimeMillis()));
        status.put("lastFailure", lastFailure);
        return status;
    }

    // ------------------------------------------------------------------ 轨迹

    private void trace(Long userId, String sessionId, String query, String backend,
                       List<RetrievalHit> hits, long startMillis) {
        try {
            RagRetrievalTrace trace = new RagRetrievalTrace();
            trace.setUserId(userId);
            trace.setSessionId(sessionId);
            trace.setQueryText(query == null ? "" : query.substring(0, Math.min(500, query.length())));
            // strategy 字段记录实际服务方：复盘时能区分"知识库没召回"与"降级了"
            trace.setStrategy(backend);
            trace.setCandidateCount(hits.size());
            trace.setHitCount(hits.size());
            trace.setLatencyMs((int) (System.currentTimeMillis() - startMillis));
            List<Map<String, Object>> top = new ArrayList<>();
            for (RetrievalHit hit : hits) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("citation", hit.citation());
                item.put("materialId", hit.materialId());
                item.put("courseId", hit.courseId());
                item.put("score", Math.round(hit.score() * 10000) / 10000.0);
                item.put("channels", hit.channels());
                top.add(item);
            }
            trace.setTopHits(objectMapper.writeValueAsString(top));
            traceMapper.insert(trace);
        } catch (Exception e) {
            log.warn("检索轨迹写入失败（不影响检索）: {}", e.getMessage());
        }
    }
}
