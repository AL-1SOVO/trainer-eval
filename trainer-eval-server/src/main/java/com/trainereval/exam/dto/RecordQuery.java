package com.trainereval.exam.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class RecordQuery {

    private Long deptId;
    private Long courseId;
    private Long paperId;
    private String status;
    private String startDate;
    private String endDate;
    private Integer page = 1;
    private Integer size = 10;
}
