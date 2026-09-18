package com.trainereval.rag.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.trainereval.rag.mapper.RagChunkMapper;
import com.trainereval.rag.mimirq.MimirQIngestClient;
import com.trainereval.training.entity.TraChapter;
import com.trainereval.training.entity.TraMaterial;
import com.trainereval.training.mapper.TraChapterMapper;
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
}
