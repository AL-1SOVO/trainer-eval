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
public class QuestionSnapshotDTO {

    private Long questionId;
    private String questionType;
    private String questionContent;
    private String options;
    private BigDecimal questionScore;
    private Integer sortOrder;
}
