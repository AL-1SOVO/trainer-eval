package com.trainereval.exam.entity;

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
@TableName("ex_wrong_answer_analysis")
public class ExWrongAnswerAnalysis extends BaseEntity {

    private Long userId;
    private Long answerId;
    private Long questionId;
    private String errorCategory;
    private String errorAnalysis;
    private String recommendation;
    private String relatedMaterialIds;
}
