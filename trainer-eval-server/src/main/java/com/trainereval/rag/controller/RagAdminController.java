package com.trainereval.rag.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.trainereval.common.result.R;
import com.trainereval.common.util.SecurityUtils;
import com.trainereval.rag.dto.RetrievalHit;
import com.trainereval.rag.entity.RagChunk;
import com.trainereval.rag.entity.RagEvalRun;
import com.trainereval.rag.entity.RagGoldenItem;
import com.trainereval.rag.entity.RagRetrievalTrace;
import com.trainereval.rag.mapper.RagChunkMapper;
import com.trainereval.rag.mapper.RagGoldenItemMapper;
import com.trainereval.rag.mapper.RagRetrievalTraceMapper;
import com.trainereval.rag.service.ChunkingService;
import com.trainereval.rag.service.GoldenEvalService;
import com.trainereval.rag.service.RagRetrievalFacade;
import com.trainereval.rag.service.RetrievalService;
import com.trainereval.training.entity.TraMaterial;
import com.trainereval.training.mapper.TraMaterialMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RAG 管理与调试接口 —— 对应 MimirQ 的"可检索 / 可替换 / 可回归"三件事：
 * <ul>
 *   <li>{@code /chunks}、{@code /search}：可检索 —— 看得见切成了什么、召回的是哪几条、走的哪个通道</li>
 *   <li>{@code /reindex}：可替换 —— 改完切块/索引策略后能一键重建并立刻对比</li>
 *   <li>{@code /eval}、{@code /eval-runs}、{@code /golden}：可回归 —— 固定题集 + 历史指标</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/rag")
@RequiredArgsConstructor
public class RagAdminController {

    private final ChunkingService chunkingService;
    private final RetrievalService retrievalService;
    private final GoldenEvalService goldenEvalService;
    private final RagRetrievalFacade retrievalFacade;
    private final RagChunkMapper chunkMapper;
    private final RagGoldenItemMapper goldenMapper;
    private final RagRetrievalTraceMapper traceMapper;
    private final TraMaterialMapper materialMapper;

    /**
     * 检索链路健康状态：是否启用 MimirQ、熔断是否打开、最近一次失败原因。
     * 排查"为什么降级了"时先看这里。
     */
    @GetMapping("/health")
    @PreAuthorize("hasAnyRole('TRAINER','MANAGER')")
    public R<Map<String, Object>> health() {
        return R.ok(retrievalFacade.health());
    }

    /** 从 classpath 的 golden-rag.jsonl 覆盖式导入 Golden 题集。 */
    @PostMapping("/golden/import")
    @PreAuthorize("hasAnyRole('TRAINER','MANAGER')")
    public R<Map<String, Object>> importGolden() {
        int count = goldenEvalService.importFromClasspath();
        return R.ok("Golden 题集导入完成", Map.of("imported", count));
    }

    /** 全量重建切块。 */
    @PostMapping("/reindex")
    @PreAuthorize("hasAnyRole('TRAINER','MANAGER')")
    public R<Map<String, Object>> reindex() {
        int materials = chunkingService.rebuildAll();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("materialsIndexed", materials);
        result.put("chunkStats", chunkingService.chunkStats());
        return R.ok("重建完成", result);
    }

    /** 预览某个资料的切块效果（不落库），用于调切块参数。 */
    @GetMapping("/chunks/preview")
    @PreAuthorize("hasAnyRole('TRAINER','MANAGER')")
    public R<Map<String, Object>> previewChunks(@RequestParam Long materialId) {
        TraMaterial material = materialMapper.selectById(materialId);
        if (material == null) {
            return R.error("资料不存在");
        }
        List<String> pieces = chunkingService.previewChunks(material.getContent());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("title", material.getTitle());
        result.put("chunkCount", pieces.size());
        result.put("chunks", pieces);
        return R.ok(result);
    }

    /** 查看某资料已入库的切块。 */
    @GetMapping("/chunks")
    @PreAuthorize("hasAnyRole('TRAINER','MANAGER')")
    public R<List<RagChunk>> chunks(@RequestParam Long materialId) {
        return R.ok(chunkMapper.selectList(new LambdaQueryWrapper<RagChunk>()
                .eq(RagChunk::getMaterialId, materialId)
                .orderByAsc(RagChunk::getChunkIndex)));
    }

    /**
     * 检索调试：返回实际服务方（backend）、是否降级、降级原因与命中明细。
     * 走的是 Agent 同一条链路，因此这里的输出可以当作"线上此刻会返回什么"。
     */
    @PostMapping("/search")
    public R<Map<String, Object>> search(@RequestBody Map<String, Object> body) {
        String query = body.get("query") == null ? "" : String.valueOf(body.get("query"));
        Long courseId = body.get("courseId") instanceof Number n ? n.longValue() : null;
        int topK = body.get("topK") instanceof Number n ? Math.min(Math.max(n.intValue(), 1), 20) : 5;
        // 走门面而不是 RetrievalService：这样调试接口反映的是 Agent 真实走的那条链路
        // （含 MimirQ、降级、权限裁剪），否则"调试通过"不代表线上没问题。
        RagRetrievalFacade.RagResult result = retrievalFacade.search(
                SecurityUtils.getCurrentUserId(), "rag-debug", query, courseId, topK);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("backend", result.backend());
        out.put("degraded", result.degraded());
        out.put("note", result.note());
        out.put("hits", result.hits());
        return R.ok(out);
    }

    /** 运行 Golden 评测。 */
    @PostMapping("/eval")
    @PreAuthorize("hasRole('MANAGER')")
    public R<Map<String, Object>> evaluate(@RequestParam(defaultValue = "5") int topK) {
        return R.ok(goldenEvalService.run(Math.min(Math.max(topK, 1), 20)));
    }

    /** 历史评测结果（版本对比用）。 */
    @GetMapping("/eval-runs")
    @PreAuthorize("hasRole('MANAGER')")
    public R<List<RagEvalRun>> evalRuns(@RequestParam(defaultValue = "20") int limit) {
        return R.ok(goldenEvalService.history(limit));
    }

    /** Golden 题集列表。 */
    @GetMapping("/golden")
    @PreAuthorize("hasAnyRole('TRAINER','MANAGER')")
    public R<List<RagGoldenItem>> goldenItems() {
        return R.ok(goldenMapper.selectList(new LambdaQueryWrapper<RagGoldenItem>()
                .orderByDesc(RagGoldenItem::getCreateTime)));
    }

    /** 新增 Golden 题目。 */
    @PostMapping("/golden")
    @PreAuthorize("hasAnyRole('TRAINER','MANAGER')")
    public R<RagGoldenItem> addGolden(@RequestBody RagGoldenItem item) {
        if (item.getQuestion() == null || item.getQuestion().isBlank()) {
            return R.error("问题不能为空");
        }
        if (item.getEnabled() == null) {
            item.setEnabled(1);
        }
        item.setId(null);
        goldenMapper.insert(item);
        return R.ok(item);
    }

    /** 最近检索轨迹：复盘"为什么没召回"。 */
    @GetMapping("/traces")
    @PreAuthorize("hasRole('MANAGER')")
    public R<List<RagRetrievalTrace>> traces(@RequestParam(defaultValue = "20") int limit) {
        return R.ok(traceMapper.selectList(new LambdaQueryWrapper<RagRetrievalTrace>()
                .orderByDesc(RagRetrievalTrace::getCreateTime)
                .last("LIMIT " + Math.min(Math.max(limit, 1), 100))));
    }
}
