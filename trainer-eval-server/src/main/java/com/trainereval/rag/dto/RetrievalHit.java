package com.trainereval.rag.dto;

/**
 * 一条带引用的检索结果。对应 MimirQ 的"引用证据"：
 * Agent 回答里出现的每个结论都应能回溯到具体的资料/切块，而不是只有一段无出处的文字。
 *
 * @param citation    稳定引用编号，形如 M12#0（资料 12 的第 0 块）
 * @param channels    命中的通道，如 FT / LIKE / FT+LIKE —— 便于解释"为什么这条被召回"
 */
public record RetrievalHit(
        String citation,
        Long chunkId,
        Long materialId,
        Long courseId,
        Long chapterId,
        int chunkIndex,
        String title,
        String courseName,
        String chapterTitle,
        String snippet,
        double score,
        String channels
) {
    public static String buildCitation(Long materialId, int chunkIndex) {
        return "M" + materialId + "#" + chunkIndex;
    }
}
