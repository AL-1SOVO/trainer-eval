package com.trainereval.questionbank.dto;

import lombok.Data;

@Data
public class PaperQuery {
    private Long courseId;
    private String keyword;
    private Integer status;
    private int page = 1;
    private int size = 20;
}
