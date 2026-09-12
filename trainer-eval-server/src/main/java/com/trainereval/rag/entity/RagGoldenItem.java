package com.trainereval.rag.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Golden 题集。对应 MimirQ 的"质量可回归"：
 * 用固定题集 + 期望命中的资料，把"检索效果"变成可版本对比的数字，而不是靠感觉。
 */
@Data
@TableName("rag_golden_item")
public class RagGoldenItem {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String question;
    /** JSON 数组，期望召回的资料 ID。 */
    private String expectedMaterialIds;
    private String note;
    private Integer enabled;
    private LocalDateTime createTime;
}
