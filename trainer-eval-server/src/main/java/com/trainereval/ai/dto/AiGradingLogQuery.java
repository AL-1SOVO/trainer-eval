package com.trainereval.ai.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AiGradingLogQuery {

    private Long courseId;
    private String model;
    private String status;
    private String startDate;
    private String endDate;
    private Integer page = 1;
    private Integer size = 10;
}
