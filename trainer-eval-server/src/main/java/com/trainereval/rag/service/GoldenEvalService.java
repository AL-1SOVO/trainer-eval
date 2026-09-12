package com.trainereval.rag.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trainereval.rag.dto.RetrievalHit;
import com.trainereval.rag.entity.RagEvalRun;
import com.trainereval.rag.entity.RagGoldenItem;
import com.trainereval.rag.mapper.RagEvalRunMapper;
import com.trainereval.rag.mapper.RagGoldenItemMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Golden 评测 —— 对应 MimirQ 的"质量可回归"。
 * <p>
 * 为什么这件事必须做：改切块大小、改 RRF 参数、换检索通道之后，
 * "效果变好还是变差"如果没有固定题集就完全无法回答，只能靠感觉。
 * 这里用最经典的三项检索指标，并把每次结果落库，便于版本间横向对比（质量门禁）：
 * <ul>
 *   <li><b>HitRate@k</b>：期望资料至少召回一条的题目占比 —— 反映"能不能找到"</li>
 *   <li><b>Recall@k</b>：期望资料被召回的比例 —— 反映"找得全不全"</li>
 *   <li><b>MRR</b>：第一个正确结果排名的倒数均值 —— 反映"排得对不对"</li>
 * </ul>
 * 注意：这里评的是<b>检索</b>，不是最终生成的答案。检索指标可离线、可重复、不受模型随机性影响，
 * 是排查"答错"时应当先固定住的那一半。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GoldenEvalService {

    private final RagGoldenItemMapper goldenMapper;
    private final RagEvalRunMapper evalRunMapper;
    private final RetrievalService retrievalService;
    private final ObjectMapper objectMapper;

    public Map<String, Object> run(int topK) {
        long start = System.currentTimeMillis();

        List<RagGoldenItem> items = goldenMapper.selectList(
                new LambdaQueryWrapper<RagGoldenItem>().eq(RagGoldenItem::getEnabled, 1));
        if (items.isEmpty()) {
            return Map.of("error", "Golden 题集为空，请先通过 /api/v1/rag/golden 录入题目");
        }

        int hitCount = 0;
        double recallSum = 0;
        double rrSum = 0;
        List<Map<String, Object>> misses = new ArrayList<>();
        List<Map<String, Object>> details = new ArrayList<>();

        for (RagGoldenItem item : items) {
            List<Long> expected = parseIds(item.getExpectedMaterialIds());
            List<RetrievalHit> hits = retrievalService.retrieve(null, "golden-eval", item.getQuestion(), null, topK);

            Set<Long> got = new HashSet<>();
            for (RetrievalHit hit : hits) {
                got.add(hit.materialId());
            }

            int matched = 0;
            for (Long id : expected) {
                if (got.contains(id)) {
                    matched++;
                }
            }
            boolean anyHit = matched > 0;
            if (anyHit) {
                hitCount++;
            }
            if (!expected.isEmpty()) {
                recallSum += (double) matched / expected.size();
            }

            // 第一个命中期望资料的排名
            int firstRank = 0;
            for (int i = 0; i < hits.size(); i++) {
                if (expected.contains(hits.get(i).materialId())) {
                    firstRank = i + 1;
                    break;
                }
            }
            rrSum += firstRank > 0 ? 1.0 / firstRank : 0;

            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("question", item.getQuestion());
            detail.put("expected", expected);
            detail.put("returned", new ArrayList<>(got));
            detail.put("firstHitRank", firstRank);
            details.add(detail);

            if (!anyHit) {
                Map<String, Object> miss = new LinkedHashMap<>();
                miss.put("question", item.getQuestion());
                miss.put("expectedMaterialIds", expected);
                miss.put("note", item.getNote());
                misses.add(miss);
            }
        }

        int n = items.size();
        BigDecimal hitRate = ratio(hitCount, n);
        BigDecimal recall = BigDecimal.valueOf(recallSum / n).setScale(4, RoundingMode.HALF_UP);
        BigDecimal mrr = BigDecimal.valueOf(rrSum / n).setScale(4, RoundingMode.HALF_UP);
        long latency = System.currentTimeMillis() - start;

        try {
            RagEvalRun run = new RagEvalRun();
            run.setTopK(topK);
            run.setItemCount(n);
            run.setHitRate(hitRate);
            run.setRecall(recall);
            run.setMrr(mrr);
            run.setMisses(objectMapper.writeValueAsString(misses));
            run.setLatencyMs((int) latency);
            evalRunMapper.insert(run);
        } catch (Exception e) {
            log.warn("评测结果落库失败（不影响返回）: {}", e.getMessage());
        }

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("topK", topK);
        report.put("itemCount", n);
        report.put("hitRate", hitRate);
        report.put("recall", recall);
        report.put("mrr", mrr);
        report.put("missCount", misses.size());
        report.put("misses", misses);
        report.put("details", details);
        report.put("latencyMs", latency);
        return report;
    }

    public List<RagEvalRun> history(int limit) {
        return evalRunMapper.selectList(new LambdaQueryWrapper<RagEvalRun>()
                .orderByDesc(RagEvalRun::getCreateTime)
                .last("LIMIT " + Math.max(1, Math.min(limit, 100))));
    }

    private BigDecimal ratio(int part, int total) {
        if (total == 0) {
            return BigDecimal.ZERO;
        }
        return BigDecimal.valueOf((double) part / total).setScale(4, RoundingMode.HALF_UP);
    }

    private List<Long> parseIds(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<Long>>() {});
        } catch (Exception e) {
            log.warn("Golden 期望资料 ID 解析失败: {}", json);
            return List.of();
        }
    }
}
