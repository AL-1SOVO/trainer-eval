package com.trainereval.questionbank.dto;

import lombok.Data;

@Data
public class PaperQuestionItem {
    private Long questionId;
    private Integer score;
    private Integer sortOrder;
}
