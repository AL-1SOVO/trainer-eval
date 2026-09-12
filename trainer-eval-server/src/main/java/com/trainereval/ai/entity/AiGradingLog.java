package com.trainereval.ai.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.trainereval.common.base.BaseEntity;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("ai_grading_log")
public class AiGradingLog extends BaseEntity {

    private Long recordId;
    private Long answerId;
    private Long ruleId;
    private String model;
    private String promptSystem;
    private String promptUser;
    private String responseRaw;
    private Integer score;
    private String reasoning;
    private Integer tokensPrompt;
    private Integer tokensCompletion;
    private Integer tokensTotal;
    private Long latencyMs;
    private BigDecimal cost;
    private String status;
    private String errorMsg;

    public static final String STATUS_SUCCESS = "SUCCESS";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_PARSE_ERROR = "PARSE_ERROR";
}
