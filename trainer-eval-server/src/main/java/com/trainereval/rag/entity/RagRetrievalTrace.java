package com.trainereval.rag.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 检索轨迹。对应 MimirQ 的"错误可定位"：一次召回到底查了什么、各通道是否命中、
 * 融合后留下哪几条。没有这张表，Agent 答错时无法区分"知识库里没有"与"检索没找对"。
 */
@Data
@TableName("rag_retrieval_trace")
public class RagRetrievalTrace {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String sessionId;
    private String queryText;
    private String strategy;
    private Integer candidateCount;
    private Integer hitCount;
    private Integer latencyMs;
    private String topHits;
    private LocalDateTime createTime;

    public static final String STRATEGY_HYBRID = "HYBRID_RRF";
}
