package com.trainereval.ai.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.trainereval.common.base.BaseEntity;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("ai_grading_rule")
public class AiGradingRule extends BaseEntity {

    private Long courseId;
    private String questionType;
    private String ruleName;
    private String promptTemplate;
    private String model;
    private Double temperature;
    private Integer maxTokens;
    private String status;
}
