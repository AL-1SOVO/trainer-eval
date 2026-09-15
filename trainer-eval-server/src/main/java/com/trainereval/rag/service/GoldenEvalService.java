package com.trainereval.rag.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trainereval.rag.dto.RetrievalHit;
import com.trainereval.rag.entity.RagEvalRun;
import com.trainereval.rag.entity.RagGoldenItem;
import com.trainereval.rag.mapper.RagChunkMapper;
import com.trainereval.rag.mapper.RagEvalRunMapper;
import com.trainereval.rag.mapper.RagGoldenItemMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Golden 评测 —— 对应 MimirQ 的"质量可回归"与方案文档阶段五。
 * <p>
 * 除了给出检索指标，本类刻意做了<b>基线对比</b>：同一条题集上同时评测
 * "现行检索链路"与"改造前的 SQL LIKE"，把优化幅度变成数字。
 * 没有基线的指标是没有说服力的 —— 无法回答"这算好还是不好"。
 * <p>
 * 三项指标：HitRate@k（能不能找到）、Recall@k（找得全不全）、MRR（排得对不对）。
 * 评的是<b>检索</b>而非最终生成：检索指标可离线、可重复、不受模型随机性影响，
 * 排查"答错"时应先把这一半固定住。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GoldenEvalService {

    public static final String STRATEGY_CHAIN = "CHAIN";
    public static final String STRATEGY_LIKE = "LIKE_BASELINE";

    private static final String GOLDEN_RESOURCE = "rag/golden-rag.jsonl";

    private final RagGoldenItemMapper goldenMapper;
    private final RagEvalRunMapper evalRunMapper;
    private final RagChunkMapper chunkMapper;
    private final RetrievalService retrievalService;
    private final RagRetrievalFacade retrievalFacade;
    private final ObjectMapper objectMapper;

    /** 单策略的评测结果。 */
    public record Metrics(int itemCount, int hitCount, BigDecimal hitRate, BigDecimal recall,
                          BigDecimal mrr, List<Map<String, Object>> misses) {
    }

    public Map<String, Object> run(int topK) {
        long start = System.currentTimeMillis();
        List<RagGoldenItem> items = goldenMapper.selectList(
                new LambdaQueryWrapper<RagGoldenItem>().eq(RagGoldenItem::getEnabled, 1));
        if (items.isEmpty()) {
            return Map.of("error", "Golden 题集为空，请先 POST /api/v1/rag/golden/import 导入");
        }

        Metrics chain = evaluate(items, topK, false);
        Metrics like = evaluate(items, topK, true);

        persist(STRATEGY_CHAIN, topK, chain);
        persist(STRATEGY_LIKE, topK, like);

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("topK", topK);
        report.put("itemCount", items.size());
        report.put("chain", toMap(chain));
        report.put("likeBaseline", toMap(like));
        // 优化幅度：可直接写进实施文档/答辩材料
        report.put("improvement", Map.of(
                "hitRateDelta", chain.hitRate().subtract(like.hitRate()),
                "recallDelta", chain.recall().subtract(like.recall()),
                "mrrDelta", chain.mrr().subtract(like.mrr())));
        report.put("latencyMs", System.currentTimeMillis() - start);
        return report;
    }

    /**
     * 从 classpath 的 golden-rag.jsonl 导入题集（覆盖式：文件是唯一事实来源，
     * 避免库里残留过期的期望值）。
     * 每行格式：{"question":"...","expectedMaterialIds":[3],"note":"..."}
     */
    public int importFromClasspath() {
        ClassPathResource resource = new ClassPathResource(GOLDEN_RESOURCE);
        if (!resource.exists()) {
            throw new IllegalStateException("找不到 Golden 题集文件: " + GOLDEN_RESOURCE);
        }
        List<RagGoldenItem> parsed = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            int lineNo = 0;
            while ((line = reader.readLine()) != null) {
                lineNo++;
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                JsonNode node = objectMapper.readTree(trimmed);
                String question = node.path("question").asText("").trim();
                if (question.isEmpty()) {
                    log.warn("golden-rag.jsonl 第 {} 行缺少 question，已跳过", lineNo);
                    continue;
                }
                RagGoldenItem item = new RagGoldenItem();
                item.setQuestion(question);
                item.setExpectedMaterialIds(node.path("expectedMaterialIds").toString());
                item.setNote(node.path("note").asText(null));
                item.setEnabled(1);
                parsed.add(item);
            }
        } catch (Exception e) {
            throw new IllegalStateException("解析 " + GOLDEN_RESOURCE + " 失败: " + e.getMessage(), e);
        }
        if (parsed.isEmpty()) {
            return 0;
        }
        goldenMapper.delete(null);
        for (RagGoldenItem item : parsed) {
            goldenMapper.insert(item);
        }
        log.info("Golden 题集已从 {} 导入 {} 条", GOLDEN_RESOURCE, parsed.size());
        return parsed.size();
    }

    public List<RagEvalRun> history(int limit) {
        return evalRunMapper.selectList(new LambdaQueryWrapper<RagEvalRun>()
                .orderByDesc(RagEvalRun::getCreateTime)
                .last("LIMIT " + Math.max(1, Math.min(limit, 100))));
    }

    // ------------------------------------------------------------------ 评测实现

    private Metrics evaluate(List<RagGoldenItem> items, int topK, boolean likeOnly) {
        int hitCount = 0;
        double recallSum = 0;
        double rrSum = 0;
        List<Map<String, Object>> misses = new ArrayList<>();

        for (RagGoldenItem item : items) {
            List<Long> expected = parseIds(item.getExpectedMaterialIds());
            List<Long> returned = likeOnly
                    ? likeBaselineMaterials(item.getQuestion(), topK)
                    : chainMaterials(item.getQuestion(), topK);

            Set<Long> got = new HashSet<>(returned);
            int matched = 0;
            for (Long id : expected) {
                if (got.contains(id)) {
                    matched++;
                }
            }
            if (matched > 0) {
                hitCount++;
            }
            if (!expected.isEmpty()) {
                recallSum += (double) matched / expected.size();
            }
            int firstRank = 0;
            for (int i = 0; i < returned.size(); i++) {
                if (expected.contains(returned.get(i))) {
                    firstRank = i + 1;
                    break;
                }
            }
            rrSum += firstRank > 0 ? 1.0 / firstRank : 0;

            if (matched == 0) {
                Map<String, Object> miss = new LinkedHashMap<>();
                miss.put("question", item.getQuestion());
                miss.put("expected", expected);
                miss.put("returned", returned);
                miss.put("note", item.getNote());
                misses.add(miss);
            }
        }

        int n = items.size();
        return new Metrics(n, hitCount, ratio(hitCount, n),
                BigDecimal.valueOf(recallSum / n).setScale(4, RoundingMode.HALF_UP),
                BigDecimal.valueOf(rrSum / n).setScale(4, RoundingMode.HALF_UP), misses);
    }

    /**
     * 现行链路。评测必须走 {@link RagRetrievalFacade} —— 也就是 Agent 真实走的那条链路
     * （MimirQ → 本地混合 → 兜底，含降级）。
     * 早先版本这里直接调用本地检索，导致 "CHAIN" 这个标签名不副实：
     * 指标好看，但测的不是生产路径。
     */
    private List<Long> chainMaterials(String question, int topK) {
        RagRetrievalFacade.RagResult result =
                retrievalFacade.search(null, "golden-eval", question, null, topK);
        List<Long> ids = new ArrayList<>();
        for (RetrievalHit hit : result.hits()) {
            ids.add(hit.materialId());
        }
        return ids;
    }

    /**
     * SQL LIKE 基线：模拟改造前的行为 —— 对清洗后的关键词做子串匹配，
     * 按"块长度升序"取前 k 条资料。用同一套查询清洗，保证与现行链路输入一致，对比才公平。
     */
    private List<Long> likeBaselineMaterials(String question, int topK) {
        String normalized = retrievalService.normalizeQuery(question);
        if (normalized.isBlank()) {
            return List.of();
        }
        Set<String> keywords = new LinkedHashSet<>();
        keywords.add(normalized);
        for (String token : normalized.split("\\s+")) {
            if (token.length() >= 2) {
                keywords.add(token);
            }
        }
        Set<Long> ordered = new LinkedHashSet<>();
        for (String keyword : keywords) {
            for (var chunk : chunkMapper.searchLike(keyword, Math.max(topK * 3, 10))) {
                if (chunk.getMaterialId() != null) {
                    ordered.add(chunk.getMaterialId());
                }
                if (ordered.size() >= topK) {
                    return new ArrayList<>(ordered);
                }
            }
        }
        return new ArrayList<>(ordered);
    }

    private void persist(String strategy, int topK, Metrics metrics) {
        try {
            RagEvalRun run = new RagEvalRun();
            run.setStrategy(strategy);
            run.setTopK(topK);
            run.setItemCount(metrics.itemCount());
            run.setHitRate(metrics.hitRate());
            run.setRecall(metrics.recall());
            run.setMrr(metrics.mrr());
            run.setMisses(objectMapper.writeValueAsString(metrics.misses()));
            run.setLatencyMs(0);
            evalRunMapper.insert(run);
        } catch (Exception e) {
            log.warn("评测结果落库失败（不影响返回）: {}", e.getMessage());
        }
    }

    private Map<String, Object> toMap(Metrics metrics) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("itemCount", metrics.itemCount());
        map.put("hitCount", metrics.hitCount());
        map.put("hitRate", metrics.hitRate());
        map.put("recall", metrics.recall());
        map.put("mrr", metrics.mrr());
        map.put("missCount", metrics.misses().size());
        map.put("misses", metrics.misses());
        return map;
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
