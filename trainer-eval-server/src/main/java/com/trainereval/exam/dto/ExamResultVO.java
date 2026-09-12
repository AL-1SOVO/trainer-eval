package com.trainereval.exam.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ExamResultVO {

    private Long recordId;
    private String paperName;
    private BigDecimal totalScore;
    private BigDecimal objectiveScore;
    private BigDecimal subjectiveScore;
    private BigDecimal passScore;
    private Boolean isPass;
    private String status;
    private Long duration;
    private List<AnswerDetailVO> answers;
}
