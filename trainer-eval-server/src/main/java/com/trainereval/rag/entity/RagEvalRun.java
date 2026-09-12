package com.trainereval.rag.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 一次 Golden 评测的结果快照，用于版本间横向对比（质量门禁）。 */
@Data
@TableName("rag_eval_run")
public class RagEvalRun {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Integer topK;
    private Integer itemCount;
    /** 至少命中 1 条期望资料的题目比例。 */
    private BigDecimal hitRate;
    /** 期望资料被召回的比例（宏观召回率）。 */
    private BigDecimal recall;
    /** 平均倒数排名（第一个正确结果排在第几位）。 */
    private BigDecimal mrr;
    private String misses;
    private Integer latencyMs;
    private LocalDateTime createTime;
}
