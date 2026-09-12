package com.trainereval.ai.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class GradingRuleForm {

    @NotNull(message = "课程ID不能为空")
    private Long courseId;

    @NotBlank(message = "题目类型不能为空")
    private String questionType;

    @NotBlank(message = "规则名称不能为空")
    private String ruleName;

    private String promptTemplate;

    private String model;

    private Double temperature;

    private Integer maxTokens;

    private String leniencyLevel;
}
