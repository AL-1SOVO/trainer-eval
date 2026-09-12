package com.trainereval.rag.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 知识库切块。对应 MimirQ 的"业务切分"层。
 * <p>
 * 为什么不直接检索 tra_material.content 原文？因为 chunk 是"可检索 / 可引用 / 可评测"的最小单元：
 * 只有切块后才能给出稳定的引用编号、才能算 Recall@k、才能定位"答错是因为没召回还是召回错了"。
 */
@Data
@TableName("rag_chunk")
public class RagChunk {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long materialId;
    private Long courseId;
    private Long chapterId;
    private Integer chunkIndex;
    private String title;
    private String content;
    private Integer charLen;

    /** 仅用于承接全文检索打分，不是表字段。 */
    @TableField(exist = false)
    private Double ftScore;
}
