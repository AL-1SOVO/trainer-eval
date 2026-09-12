package com.trainereval.rag.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trainereval.rag.dto.RetrievalHit;
import com.trainereval.rag.entity.RagChunk;
import com.trainereval.rag.entity.RagRetrievalTrace;
import com.trainereval.rag.mapper.RagChunkMapper;
import com.trainereval.rag.mapper.RagRetrievalTraceMapper;
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

/**
 * 混合检索服务 —— 对应 MimirQ 的"混合召回 + 重排 + 引用"。
 * <p>
 * 为什么是"混合"而不是单通道：
 * <ul>
 *   <li><b>全文通道（ngram）</b>提供相关度排序，能处理词序不同、近义搭配；但对短于 2 字的查询无效。</li>
 *   <li><b>子串通道（LIKE）</b>保证精确短语一定命中；但没有相关度，只能返回原始顺序。</li>
 *   <li>两者互补，用 <b>RRF（Reciprocal Rank Fusion）</b> 融合。选 RRF 而不是加权求和的原因：
 *       两通道分数量纲完全不同（MySQL 全文相关度 ~0-2，LIKE 无分数），
 *       归一化加权需要调参且不稳定，而 RRF 只用"排名"，天然免疫分数量纲问题。</li>
 * </ul>
 * 重排采用"规则重排"而非模型重排：见 {@link #rerank} 的说明。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RetrievalService {

    private final RagChunkMapper chunkMapper;
    private final RagRetrievalTraceMapper traceMapper;
    private final TraMaterialMapper materialMapper;
    private final TraChapterMapper chapterMapper;
    private final TraCourseMapper courseMapper;
    private final ObjectMapper objectMapper;

    /** RRF 常数，取业界常用值 60。 */
    private static final int RRF_K = 60;
    private static final int CHANNEL_LIMIT = 30;
    /** 同一资料最多贡献几条，避免单一资料霸榜（简化版多样性重排）。 */
    private static final int MAX_PER_MATERIAL = 2;
    private static final int SNIPPET_LEN = 300;

    /** 疑问词/停用词，用于清洗查询：不清洗会让"什么是…""我应该怎么…"污染 ngram 检索。 */
    private static final Set<String> STOPWORDS = Set.of(
            "什么", "怎么", "如何", "为什么", "哪些", "哪个", "是否", "可以", "应该", "需要",
            "请问", "帮我", "我想", "我要", "一下", "的话", "以及", "或者", "还有", "这个", "那个",
            "我们", "他们", "自己", "关于", "对于", "根据", "知道", "了解", "介绍", "解释", "说明");

    /**
     * 检索入口。
     *
     * @param courseId 可选，限定在某课程内检索（用于"我学的那门课"这类上下文）
     */
    public List<RetrievalHit> retrieve(Long userId, String sessionId, String query, Long courseId, int topK) {
        long start = System.currentTimeMillis();
        List<RetrievalHit> hits = List.of();
        int candidateCount = 0;

        try {
            String normalized = normalizeQuery(query);
            if (normalized.isBlank()) {
                return List.of();
            }

            Map<Long, Integer> ftRanks = new LinkedHashMap<>();
            Map<Long, Integer> likeRanks = new LinkedHashMap<>();
            Map<Long, RagChunk> chunkById = new HashMap<>();

            // 通道 A：ngram 全文（要求 >= 2 字符，ngram_token_size 默认 2）
            if (normalized.length() >= 2) {
                List<RagChunk> ft = chunkMapper.searchFulltext(normalized, CHANNEL_LIMIT);
                int rank = 0;
                for (RagChunk c : ft) {
                    ftRanks.put(c.getId(), rank++);
                    chunkById.put(c.getId(), c);
                }
            }

            // 通道 B：子串（整串 + 空格分词），保证精确短语与短查询也能命中
            Set<String> likeKeywords = new LinkedHashSet<>();
            likeKeywords.add(normalized);
            for (String token : normalized.split("\\s+")) {
                if (token.length() >= 2) {
                    likeKeywords.add(token);
                }
            }
            for (String keyword : likeKeywords) {
                List<RagChunk> like = chunkMapper.searchLike(keyword, CHANNEL_LIMIT);
                int rank = 0;
                for (RagChunk c : like) {
                    likeRanks.putIfAbsent(c.getId(), rank++);
                    chunkById.putIfAbsent(c.getId(), c);
                }
            }

            candidateCount = chunkById.size();
            if (chunkById.isEmpty()) {
                return List.of();
            }

            // RRF 融合
            Map<Long, Double> fused = new HashMap<>();
            for (Long id : chunkById.keySet()) {
                double score = 0;
                Integer ft = ftRanks.get(id);
                if (ft != null) {
                    score += 1.0 / (RRF_K + ft + 1);
                }
                Integer lk = likeRanks.get(id);
                if (lk != null) {
                    score += 1.0 / (RRF_K + lk + 1);
                }
                fused.put(id, score);
            }

            // 补齐元数据（标题/课程/章节）用于引用展示
            Map<Long, TraMaterial> materialCache = loadMaterials(chunkById.values());
            Map<Long, TraChapter> chapterCache = loadChapters(materialCache.values());
            Map<Long, TraCourse> courseCache = loadCourses(chapterCache.values());

            List<RetrievalHit> candidates = new ArrayList<>();
            for (RagChunk chunk : chunkById.values()) {
                TraMaterial material = materialCache.get(chunk.getMaterialId());
                if (material == null) {
                    continue;
                }
                TraChapter chapter = material.getChapterId() == null
                        ? null : chapterCache.get(material.getChapterId());
                Long cid = chapter == null ? null : chapter.getCourseId();
                if (courseId != null && !courseId.equals(cid)) {
                    continue;
                }
                TraCourse course = cid == null ? null : courseCache.get(cid);

                String channels = (ftRanks.containsKey(chunk.getId()) ? "FT" : "")
                        + (likeRanks.containsKey(chunk.getId()) ? (ftRanks.containsKey(chunk.getId()) ? "+LIKE" : "LIKE") : "");

                candidates.add(new RetrievalHit(
                        RetrievalHit.buildCitation(chunk.getMaterialId(), chunk.getChunkIndex()),
                        chunk.getId(),
                        chunk.getMaterialId(),
                        cid,
                        material.getChapterId(),
                        chunk.getChunkIndex() == null ? 0 : chunk.getChunkIndex(),
                        material.getTitle(),
                        course == null ? "" : course.getCourseName(),
                        chapter == null ? "" : chapter.getTitle(),
                        snippet(chunk.getContent()),
                        fused.getOrDefault(chunk.getId(), 0.0),
                        channels));
            }

            hits = rerank(candidates, normalized, topK);
            return hits;
        } finally {
            // 无论成功与否都留轨迹：检索不到结果恰恰是最需要被看到的失败
            persistTrace(userId, sessionId, query, candidateCount, hits, System.currentTimeMillis() - start);
        }
    }

    /**
     * 规则重排。为什么不用模型重排（如 bge-reranker / Cohere Rerank）：
     * 当前语料仅 2 条有效资料、总量约 250 字符，全部候选都能塞进上下文，
     * 上重排模型只会增加一次网络往返与成本，收益为负。规则重排在"资料级"信号
     * （标题命中、查询覆盖度）上与轻量模型效果接近，且完全可解释、零延迟。
     * 语料增长到数百条后再切换到交叉编码器重排（{@code agent.rag.rerank} 可扩展点）。
     */
    private List<RetrievalHit> rerank(List<RetrievalHit> candidates, String normalizedQuery, int topK) {
        Set<String> bigrams = bigrams(normalizedQuery);

        List<RetrievalHit> scored = new ArrayList<>();
        for (RetrievalHit hit : candidates) {
            double base = hit.score();
            double boost = 1.0;

            String title = hit.title() == null ? "" : hit.title();
            String content = hit.snippet() == null ? "" : hit.snippet();

            // 标题命中查询 → 强信号（资料标题往往就是知识点名）
            if (!normalizedQuery.isBlank() && title.contains(normalizedQuery)) {
                boost += 0.6;
            }

            // 查询二元组覆盖率 → 衡量"这块内容到底讲了多少用户问的东西"
            if (!bigrams.isEmpty()) {
                int covered = 0;
                String haystack = title + content;
                for (String bg : bigrams) {
                    if (haystack.contains(bg)) {
                        covered++;
                    }
                }
                boost += 0.4 * covered / bigrams.size();
            }

            // 过短块信息量不足，轻微降权
            if (content.length() < 50) {
                boost -= 0.2;
            }

            scored.add(new RetrievalHit(hit.citation(), hit.chunkId(), hit.materialId(), hit.courseId(),
                    hit.chapterId(), hit.chunkIndex(), hit.title(), hit.courseName(), hit.chapterTitle(),
                    hit.snippet(), base * boost, hit.channels()));
        }

        scored.sort(Comparator.comparingDouble(RetrievalHit::score).reversed());

        // 多样性：同一资料最多 MAX_PER_MATERIAL 条
        List<RetrievalHit> picked = new ArrayList<>();
        Map<Long, Integer> perMaterial = new HashMap<>();
        for (RetrievalHit hit : scored) {
            int used = perMaterial.getOrDefault(hit.materialId(), 0);
            if (used >= MAX_PER_MATERIAL) {
                continue;
            }
            perMaterial.put(hit.materialId(), used + 1);
            picked.add(hit);
            if (picked.size() >= topK) {
                break;
            }
        }
        return picked;
    }

    /** 清洗查询：去标点、去疑问词与停用词。 */
    String normalizeQuery(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.replaceAll("[\\p{Punct}\\p{IsPunctuation}]", " ").trim();
        for (String stop : STOPWORDS) {
            s = s.replace(stop, " ");
        }
        return s.replaceAll("\\s+", " ").trim();
    }

    private Set<String> bigrams(String text) {
        Set<String> set = new HashSet<>();
        for (int i = 0; i + 2 <= text.length(); i++) {
            set.add(text.substring(i, i + 2));
        }
        return set;
    }

    private String snippet(String content) {
        if (content == null) {
            return "";
        }
        return content.length() <= SNIPPET_LEN ? content : content.substring(0, SNIPPET_LEN) + "…";
    }

    private Map<Long, TraMaterial> loadMaterials(java.util.Collection<RagChunk> chunks) {
        Set<Long> ids = new HashSet<>();
        for (RagChunk c : chunks) {
            if (c.getMaterialId() != null) {
                ids.add(c.getMaterialId());
            }
        }
        Map<Long, TraMaterial> map = new HashMap<>();
        if (!ids.isEmpty()) {
            for (TraMaterial m : materialMapper.selectBatchIds(ids)) {
                map.put(m.getId(), m);
            }
        }
        return map;
    }

    private Map<Long, TraChapter> loadChapters(java.util.Collection<TraMaterial> materials) {
        Set<Long> ids = new HashSet<>();
        for (TraMaterial m : materials) {
            if (m.getChapterId() != null) {
                ids.add(m.getChapterId());
            }
        }
        Map<Long, TraChapter> map = new HashMap<>();
        if (!ids.isEmpty()) {
            for (TraChapter c : chapterMapper.selectBatchIds(ids)) {
                map.put(c.getId(), c);
            }
        }
        return map;
    }

    private Map<Long, TraCourse> loadCourses(java.util.Collection<TraChapter> chapters) {
        Set<Long> ids = new HashSet<>();
        for (TraChapter c : chapters) {
            if (c.getCourseId() != null) {
                ids.add(c.getCourseId());
            }
        }
        Map<Long, TraCourse> map = new HashMap<>();
        if (!ids.isEmpty()) {
            for (TraCourse c : courseMapper.selectBatchIds(ids)) {
                map.put(c.getId(), c);
            }
        }
        return map;
    }

    /** 落检索轨迹。"为什么召回的是这几条"必须可事后复盘。 */
    private void persistTrace(Long userId, String sessionId, String query,
                              int candidateCount, List<RetrievalHit> hits, long latencyMs) {
        try {
            RagRetrievalTrace trace = new RagRetrievalTrace();
            trace.setUserId(userId);
            trace.setSessionId(sessionId);
            trace.setQueryText(query == null ? "" : query.substring(0, Math.min(500, query.length())));
            trace.setStrategy(RagRetrievalTrace.STRATEGY_HYBRID);
            trace.setCandidateCount(candidateCount);
            trace.setHitCount(hits.size());
            trace.setLatencyMs((int) latencyMs);
            // 必须是合法 JSON（该列是 JSON 类型）—— 直接序列化 List<Map> 得到 JSON 数组
            List<Map<String, Object>> top = new ArrayList<>();
            for (RetrievalHit hit : hits) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("citation", hit.citation());
                item.put("materialId", hit.materialId());
                item.put("chunkIndex", hit.chunkIndex());
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

    /** 供检索调试接口使用：返回轨迹而不入库。 */
    public List<RetrievalHit> debug(String query, Long courseId, int topK) {
        return retrieve(null, null, query, courseId, topK);
    }
}
