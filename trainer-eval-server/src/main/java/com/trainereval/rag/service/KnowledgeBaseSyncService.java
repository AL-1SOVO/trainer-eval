package com.trainereval.rag.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.trainereval.rag.mapper.RagChunkMapper;
import com.trainereval.rag.mimirq.MimirQDocumentNaming;
import com.trainereval.rag.mimirq.MimirQIngestClient;
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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 知识库运维：全量重推 + 状态巡检。
 *
 * <h3>为什么必须有"全量重推"</h3>
 * MimirQ 有一条硬要求：<b>换 embedding 模型后，已有向量全部失效，必须重建索引</b>。
 * 而"重建"在 MimirQ 侧没有单独的按钮 —— 它的做法是重新灌入文档（删除后重建会触发重新向量化）。
 * 资料会随日常上传/修改自动同步，但**换模型这一天，存量资料不会自己重来一遍**。
 * 所以需要一个能对全部资料重跑一遍的入口。
 * <p>
 * 它同时也解决另一类问题：某段时间 MimirQ 不可用（或没配写入账号），
 * 期间上传的资料只进了本地索引，知识库里是缺的。重推一次即可补齐。
 *
 * <h3>为什么不复用 /rag/reindex</h3>
 * {@code /rag/reindex} 只重建<b>本地切块</b>（{@code rag_chunk}），不碰 MimirQ。
 * 两者是"事实源"与"派生索引"的关系，重建的触发条件和使用代价都不同，混在一起会让人误判。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeBaseSyncService {

    private final TraMaterialMapper materialMapper;
    private final TraChapterMapper chapterMapper;
    private final TraCourseMapper courseMapper;
    private final RagChunkMapper chunkMapper;
    private final RagIndexSyncService indexSyncService;
    private final MimirQIngestClient ingestClient;

    /**
     * 把全部有正文的资料重新推送到 MimirQ。
     * <p>
     * 注意代价：MimirQ 侧会把每份文档<b>先删后建</b>，因此会重新走一遍解析/切块/向量化。
     * 资料多的时候耗时明显，且期间该课程的知识库是"暂时变少"的 —— 这是"先删后建"的固有代价，
     * 换来的是不产生重复文档。调用方应当知道这一点（接口文档与返回里都写明了）。
     */
    public Map<String, Object> resyncAll() {
        Map<String, Object> result = new LinkedHashMap<>();
        if (!ingestClient.isConfigured()) {
            result.put("configured", false);
            result.put("message", "未配置 MimirQ 写入账号（mimirq.username / mimirq.password），无法重建知识库。"
                    + "资料仍可通过本地索引检索。");
            return result;
        }
        result.put("configured", true);

        List<TraMaterial> materials = materialMapper.selectList(
                new LambdaQueryWrapper<TraMaterial>()
                        .isNotNull(TraMaterial::getContent)
                        .ne(TraMaterial::getContent, "")
                        .orderByAsc(TraMaterial::getId));

        int pushed = 0;
        int skipped = 0;
        int failed = 0;
        List<Map<String, Object>> failures = new ArrayList<>();
        Set<Long> touchedCourses = new LinkedHashSet<>();

        for (TraMaterial material : materials) {
            Long courseId = resolveCourseId(material.getChapterId());
            if (courseId == null) {
                // 章节缺失/无课程归属的资料无法定位知识库；不猜、不推到错误的库里
                skipped++;
                continue;
            }
            int chunks = chunkMapper.listContentsByMaterial(material.getId()).size();
            if (chunks == 0) {
                // 有正文但没切块：先补切块再推，否则推过去是空文档
                try {
                    indexSyncService.rebuildLocalChunks(material);
                    chunks = chunkMapper.listContentsByMaterial(material.getId()).size();
                } catch (Exception e) {
                    log.warn("重建切块失败，跳过该资料：materialId={}, err={}", material.getId(), e.toString());
                }
            }
            if (chunks == 0) {
                skipped++;
                continue;
            }
            try {
                indexSyncService.pushToMimirQ(material.getId(), courseId, material.getTitle());
                pushed++;
                touchedCourses.add(courseId);
            } catch (Exception e) {
                failed++;
                Map<String, Object> failure = new LinkedHashMap<>();
                failure.put("materialId", material.getId());
                failure.put("courseId", courseId);
                failure.put("title", material.getTitle());
                failure.put("error", e.getMessage());
                failures.add(failure);
                log.warn("知识库重推失败：materialId={}, err={}", material.getId(), e.toString());
            }
        }

        result.put("totalMaterials", materials.size());
        result.put("pushed", pushed);
        result.put("skipped", skipped);
        result.put("failed", failed);
        result.put("courses", new ArrayList<>(touchedCourses));
        if (!failures.isEmpty()) {
            // 只回前若干条，避免响应体被大量失败明细撑爆；完整原因已在日志里
            result.put("failures", failures.subList(0, Math.min(failures.size(), 20)));
            result.put("failuresTruncated", failures.size() > 20);
        }
        log.info("知识库全量重推完成：pushed={}, skipped={}, failed={}, courses={}",
                pushed, skipped, failed, touchedCourses);
        return result;
    }

    /**
     * 巡检：MimirQ 侧实际有哪些知识库、各有多少文档与切块。
     * 与本地期望值对比就能发现"哪些课程漏灌了"。
     */
    public Map<String, Object> status() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("configured", ingestClient.isConfigured());
        result.put("mimirqDatasets", ingestClient.isConfigured() ? ingestClient.listDatasets() : List.of());

        // 本地期望：有正文的资料分布
        List<TraMaterial> materials = materialMapper.selectList(
                new LambdaQueryWrapper<TraMaterial>()
                        .isNotNull(TraMaterial::getContent)
                        .ne(TraMaterial::getContent, "")
                        .select(TraMaterial::getId, TraMaterial::getChapterId));
        Map<Long, Integer> expected = new LinkedHashMap<>();
        int noCourse = 0;
        for (TraMaterial m : materials) {
            Long courseId = resolveCourseId(m.getChapterId());
            if (courseId == null) {
                noCourse++;
                continue;
            }
            expected.merge(courseId, 1, Integer::sum);
        }
        List<Map<String, Object>> expectedList = new ArrayList<>();
        expected.forEach((courseId, count) -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("knowledgeId", "course-" + courseId);
            item.put("localMaterialsWithContent", count);
            expectedList.add(item);
        });
        result.put("expectedLocal", expectedList);
        result.put("materialsWithoutCourse", noCourse);
        result.put("hint", "mimirqDatasets 里缺少某个 course-N，说明该课程资料未成功灌入知识库；"
                + "可调用 POST /api/v1/rag/knowledge-base/resync 补齐。换 embedding 模型后也必须重推。");
        return result;
    }

    private Long resolveCourseId(Long chapterId) {
        if (chapterId == null) {
            return null;
        }
        TraChapter chapter = chapterMapper.selectById(chapterId);
        return chapter == null ? null : chapter.getCourseId();
    }

    // ------------------------------------------------------------------ 单条重推

    /**
     * 只重推一条资料。
     * <p>
     * 为什么需要它：{@link #resyncAll()} 会把每份文档"先删后建"，
     * 20 份资料的课程就是 20 次删+20 次重新向量化，期间该课程的知识库是"暂时变少"的。
     * 而真实场景里最常见的操作是"我刚才改的那一份资料没生效"——
     * 为了它付出全量代价既慢又有风险（中途失败会留下半灌状态）。
     */
    public Map<String, Object> resyncMaterial(Long materialId) {
        Map<String, Object> result = new LinkedHashMap<>();
        TraMaterial material = materialMapper.selectById(materialId);
        if (material == null) {
            result.put("ok", false);
            result.put("message", "资料不存在");
            return result;
        }
        if (!ingestClient.isConfigured()) {
            result.put("ok", false);
            result.put("message", "未配置 MimirQ 写入账号（mimirq.username / mimirq.password）");
            return result;
        }
        Long courseId = resolveCourseId(material.getChapterId());
        if (courseId == null) {
            result.put("ok", false);
            result.put("message", "该资料的章节未归属任何课程，无法定位知识库（不猜、不推到错误的库里）");
            return result;
        }

        int chunks = chunkMapper.listContentsByMaterial(materialId).size();
        if (chunks == 0) {
            // 有正文却没切块：先补切块，否则推过去是空文档，反而把原来的好文档删掉
            try {
                indexSyncService.rebuildLocalChunks(material);
                chunks = chunkMapper.listContentsByMaterial(materialId).size();
            } catch (Exception e) {
                result.put("ok", false);
                result.put("message", "重建本地切块失败：" + e.getMessage());
                return result;
            }
        }
        if (chunks == 0) {
            result.put("ok", false);
            result.put("message", "该资料没有可推送的正文（可能是 DOCUMENT/VIDEO 类占位资料）");
            return result;
        }

        try {
            indexSyncService.pushToMimirQ(materialId, courseId, material.getTitle());
            result.put("ok", true);
            result.put("materialId", materialId);
            result.put("title", material.getTitle());
            result.put("courseId", courseId);
            result.put("knowledgeId", "course-" + courseId);
            result.put("chunks", chunks);
            log.info("单条资料重推完成：materialId={}, courseId={}, chunks={}", materialId, courseId, chunks);
        } catch (Exception e) {
            result.put("ok", false);
            result.put("message", "推送失败：" + e.getMessage());
            log.warn("单条资料重推失败：materialId={}, err={}", materialId, e.toString());
        }
        return result;
    }

    // ------------------------------------------------------------------ 漂移巡检

    /**
     * 逐课程对比"本地应有"与"知识库实有"，列出缺失与多余。
     *
     * <h3>为什么这件事值得单独做一个接口</h3>
     * {@code /knowledge-base/status} 只给出两侧的总数，能看出"哪个课程数量不对"，
     * 但看不出"到底缺了哪一份、多了哪一份"。而实际情况经常是
     * 资料被改名/改归属后，<b>旧文档留在知识库里成了幽灵</b>：数量对得上，检索却会命中已经不存在的资料。
     * 所以这里按 {@code m{materialId}__} 前缀做<b>逐条核对</b>，把"数量相等但内容不同"这种
     * 靠总数永远发现不了的情况也暴露出来。
     */
    public Map<String, Object> drift() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("configured", ingestClient.isConfigured());

        // 本地期望：courseId → 有正文的资料（id + title）
        List<TraMaterial> materials = materialMapper.selectList(
                new LambdaQueryWrapper<TraMaterial>()
                        .isNotNull(TraMaterial::getContent)
                        .ne(TraMaterial::getContent, "")
                        .select(TraMaterial::getId, TraMaterial::getChapterId, TraMaterial::getTitle));
        Map<Long, List<TraMaterial>> expected = new LinkedHashMap<>();
        int noCourse = 0;
        for (TraMaterial material : materials) {
            Long courseId = resolveCourseId(material.getChapterId());
            if (courseId == null) {
                noCourse++;
                continue;
            }
            expected.computeIfAbsent(courseId, k -> new ArrayList<>()).add(material);
        }
        result.put("materialsWithoutCourse", noCourse);
        result.put("coursesWithMaterials", expected.size());

        if (!ingestClient.isConfigured()) {
            result.put("message", "未配置 MimirQ 写入账号，无法读取知识库侧事实；"
                    + "本地侧期望已列出，配置后即可对比。");
            result.put("expected", summarizeExpected(expected));
            return result;
        }

        List<Map<String, Object>> courses = new ArrayList<>();
        int totalMissing = 0;
        int totalOrphan = 0;
        Set<Long> described = new LinkedHashSet<>();
        List<Map<String, Object>> unreadable = new ArrayList<>();

        for (Map.Entry<Long, List<TraMaterial>> entry : expected.entrySet()) {
            Long courseId = entry.getKey();
            List<TraMaterial> localMaterials = entry.getValue();
            described.add(courseId);

            List<Map<String, Object>> remote;
            try {
                remote = ingestClient.listDocuments(courseId);
            } catch (Exception e) {
                // 读不通的课程不做判断，单独列出原因。
                // 若这里直接把缺失全报出来，会得到一份"全都是漏灌"的假报告 ——
                // 那比不报更糟，因为它会引导人去重推本来好好的资料。
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("courseId", courseId);
                row.put("knowledgeId", "course-" + courseId);
                row.put("localMaterials", localMaterials.size());
                row.put("error", e.getMessage());
                row.put("note", "读取知识库文档列表失败，本课程未参与漂移判断");
                unreadable.add(row);
                continue;
            }
            Set<Long> remoteMaterialIds = new LinkedHashSet<>();
            int untagged = 0;
            for (Map<String, Object> document : remote) {
                Object parsed = document.get("materialId");
                if (parsed instanceof Long id) {
                    remoteMaterialIds.add(id);
                } else {
                    // 早期手工灌库的文档没有 m{id}__ 前缀，无法归属到具体资料。
                    // 不把它们算成"多余"（那会是假警报），单独计数提醒人工确认。
                    untagged++;
                }
            }

            List<Map<String, Object>> missing = new ArrayList<>();
            for (TraMaterial material : localMaterials) {
                if (!remoteMaterialIds.contains(material.getId())) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("materialId", material.getId());
                    row.put("title", material.getTitle());
                    missing.add(row);
                }
            }
            Set<Long> localIds = new LinkedHashSet<>();
            for (TraMaterial material : localMaterials) {
                localIds.add(material.getId());
            }
            List<Map<String, Object>> orphan = new ArrayList<>();
            for (Map<String, Object> document : remote) {
                Object parsed = document.get("materialId");
                if (parsed instanceof Long id && !localIds.contains(id)) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("materialId", id);
                    row.put("filename", document.get("filename"));
                    row.put("documentId", document.get("documentId"));
                    orphan.add(row);
                }
            }

            if (missing.isEmpty() && orphan.isEmpty() && untagged == 0) {
                // 一致的课程不进报告：报告里全是噪音就没人看了
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("courseId", courseId);
            row.put("knowledgeId", "course-" + courseId);
            row.put("localMaterials", localMaterials.size());
            row.put("remoteDocuments", remote.size());
            row.put("missingCount", missing.size());
            row.put("orphanCount", orphan.size());
            row.put("untaggedCount", untagged);
            row.put("missing", missing);
            row.put("orphan", orphan);
            row.put("advice", buildAdvice(missing, orphan));
            courses.add(row);
            totalMissing += missing.size();
            totalOrphan += orphan.size();
        }

        // 知识库里有、本地一门课都没有的库（课程被删/资料被清空后留下的空壳或幽灵）
        Map<String, Long> datasetOwner = new LinkedHashMap<>();
        for (Map<String, Object> dataset : ingestClient.listDatasets()) {
            String name = String.valueOf(dataset.get("name"));
            if (name.startsWith("course-")) {
                try {
                    datasetOwner.put(name, Long.parseLong(name.substring("course-".length())));
                } catch (NumberFormatException ignored) {
                    // 非本系统命名规范的库，跳过
                }
            }
        }
        List<Map<String, Object>> orphanCourses = new ArrayList<>();
        for (Map.Entry<String, Long> entry : datasetOwner.entrySet()) {
            if (!described.contains(entry.getValue())) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("courseId", entry.getValue());
                row.put("knowledgeId", entry.getKey());
                row.put("note", "本地已无有正文的资料，但知识库里仍有该课程的知识库");
                orphanCourses.add(row);
            }
        }

        result.put("inconsistentCourses", courses);
        result.put("unreadableCourses", unreadable);
        result.put("totalMissing", totalMissing);
        result.put("totalOrphanDocuments", totalOrphan);
        result.put("kbsWithoutLocalMaterials", orphanCourses);
        result.put("comparison", "expected(本地)=" + expected.size() + " 门课；"
                + "actual(知识库)=" + datasetOwner.size() + " 个库");
        result.put("hint", totalMissing == 0 && totalOrphan == 0
                ? "未发现漂移：本地有正文的资料都能在知识库中找到对应文档。"
                : "存在漂移。可对单条资料调用 POST /api/v1/rag/knowledge-base/resync/{materialId} 补齐；"
                  + "多余的文档删除后不会被重建，需人工确认是否为改名/改归属留下的残留。");
        return result;
    }

    private String buildAdvice(List<Map<String, Object>> missing, List<Map<String, Object>> orphan) {
        List<String> parts = new ArrayList<>();
        if (!missing.isEmpty()) {
            parts.add("有 " + missing.size() + " 份资料未灌入 → 对这些 materialId 调用 resync/{materialId} 即可补齐");
        }
        if (!orphan.isEmpty()) {
            parts.add("有 " + orphan.size() + " 份文档在本地已找不到对应资料（改名/改归属/删除留下的残留）"
                    + " → 会污染检索结果，建议在 MimirQ 侧删除或重新全量重推");
        }
        return String.join("；", parts);
    }

    private List<Map<String, Object>> summarizeExpected(Map<Long, List<TraMaterial>> expected) {
        List<Map<String, Object>> rows = new ArrayList<>();
        expected.forEach((courseId, list) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("knowledgeId", "course-" + courseId);
            row.put("localMaterialsWithContent", list.size());
            rows.add(row);
        });
        return rows;
    }

    /** 某课程在知识库里的实际文档列表（只读，不会顺手建库）。 */
    public Map<String, Object> documents(Long courseId) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("configured", ingestClient.isConfigured());
        result.put("courseId", courseId);
        result.put("knowledgeId", "course-" + courseId);
        if (!ingestClient.isConfigured()) {
            result.put("documents", List.of());
            result.put("message", "未配置 MimirQ 写入账号，无法读取知识库侧文档");
            return result;
        }
        String datasetId = ingestClient.findDatasetId(courseId);
        result.put("datasetExists", datasetId != null);
        result.put("datasetId", datasetId);
        result.put("documents", ingestClient.listDocuments(courseId));
        return result;
    }

    /**
     * 资料级的"同步状态"清单 —— 知识库管理页的主表格数据源。
     *
     * <h3>为什么不直接返回 tra_material 列表让前端自己拼</h3>
     * "是否已灌入知识库"这个字段只有服务端知道：它要求把
     * {@code tra_material} 与 MimirQ 的文档列表做 join。放在前端做意味着
     * 前端要发 N 次请求再自己匹配，既慢又把映射规则（{@code m{id}__} 前缀）
     * 复制到了第二个地方 —— 那份规则一旦变化就会静默错位。
     *
     * <h3>只读、且不建库</h3>
     * 未配置写入账号或知识库里没有该课程的库时，{@code inKnowledgeBase=false} 并且不触发任何写操作，
     * 这样"看到的就是现状"，不会因为打开页面而改变服务端状态。
     *
     * @param courseId 可选，限定课程
     * @param keyword  可选，按标题模糊过滤
     * @param limit    上限，默认 200（培训系统资料量级很小，够用；避免无界查询）
     */
    public Map<String, Object> materials(Long courseId, String keyword, int limit) {
        Map<String, Object> result = new LinkedHashMap<>();
        int size = Math.min(Math.max(limit, 1), 500);
        result.put("configured", ingestClient.isConfigured());

        LambdaQueryWrapper<TraMaterial> wrapper = new LambdaQueryWrapper<TraMaterial>()
                .isNotNull(TraMaterial::getContent)
                .ne(TraMaterial::getContent, "")
                .orderByAsc(TraMaterial::getId)
                .last("LIMIT " + size);
        if (keyword != null && !keyword.isBlank()) {
            wrapper.like(TraMaterial::getTitle, keyword.trim());
        }
        List<TraMaterial> materials = materialMapper.selectList(wrapper);

        // 每个课程只查一次远端文档：否则 200 份资料就是 200 次 HTTP（N+1 会把页面拖死）
        Map<Long, Set<Long>> remoteByCourse = new LinkedHashMap<>();
        Map<Long, String> courseNames = new LinkedHashMap<>();
        // 读远端失败的课程：如实回给页面，而不是让整个接口 500
        Map<Long, String> remoteErrors = new LinkedHashMap<>();

        List<Map<String, Object>> items = new ArrayList<>();
        for (TraMaterial material : materials) {
            Long materialCourseId = resolveCourseId(material.getChapterId());
            if (courseId != null && !courseId.equals(materialCourseId)) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("materialId", material.getId());
            row.put("title", material.getTitle());
            row.put("materialType", material.getMaterialType());
            row.put("courseId", materialCourseId);
            row.put("knowledgeId", materialCourseId == null ? null : "course-" + materialCourseId);
            row.put("contentChars", material.getContent() == null ? 0 : material.getContent().length());

            int chunks = chunkMapper.listContentsByMaterial(material.getId()).size();
            row.put("localChunks", chunks);

            boolean inKb = false;
            if (materialCourseId != null && ingestClient.isConfigured()) {
                Set<Long> remoteIds = remoteByCourse.computeIfAbsent(materialCourseId, id -> {
                    Set<Long> ids = new LinkedHashSet<>();
                    try {
                        for (Map<String, Object> document : ingestClient.listDocuments(id)) {
                            if (document.get("materialId") instanceof Long parsed) {
                                ids.add(parsed);
                            }
                        }
                    } catch (Exception e) {
                        // 单个课程读失败不能让整页 500：页面还要能展示"哪个课程读不到"，
                        // 否则运维看到的是一个没有任何线索的报错
                        remoteErrors.put(id, e.getMessage());
                        log.warn("读取知识库文档失败：courseId={}, err={}", id, e.toString());
                    }
                    return ids;
                });
                inKb = remoteIds.contains(material.getId());
                courseNames.computeIfAbsent(materialCourseId, id -> {
                    TraCourse course = courseMapper.selectById(id);
                    return course == null ? null : course.getCourseName();
                });
                row.put("courseName", courseNames.get(materialCourseId));
            }
            row.put("inKnowledgeBase", inKb);
            row.put("name", MimirQDocumentNaming.fileName(material.getId(), material.getTitle()));
            row.put("needsAttention", chunks == 0 || (ingestClient.isConfigured() && !inKb));
            items.add(row);
        }

        result.put("total", items.size());
        result.put("limit", size);
        result.put("items", items);
        if (!remoteErrors.isEmpty()) {
            // 明确区分"资料没进知识库"和"读不到知识库"：两者都表现为 inKnowledgeBase=false，
            // 但处理方式完全不同（前者要重推，后者要修连接）
            result.put("remoteReadErrors", remoteErrors);
            result.put("remoteReadHint", "有课程的知识库文档列表读取失败，这些课程的 inKnowledgeBase 不可信，"
                    + "请先修复知识库连接再排查同步状态。");
        }
        result.put("hint", "needsAttention=true 表示该资料要么没切块、要么还没进知识库 —— 两者都会让员工搜不到它。"
                + "可用 POST /api/v1/rag/knowledge-base/resync/{materialId} 单条补齐。");
        return result;
    }
}
