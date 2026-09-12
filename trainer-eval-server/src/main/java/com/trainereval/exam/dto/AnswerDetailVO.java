package com.trainereval.exam.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AnswerDetailVO {

    private Long questionId;
    private String questionType;
    private String questionContent;
    private String questionOptions;
    private BigDecimal questionScore;
    private String userAnswer;
    private String referenceAnswer;
    private Boolean isCorrect;
    private BigDecimal score;
    private String aiFeedback;
    private String errorAnalysis;
}
