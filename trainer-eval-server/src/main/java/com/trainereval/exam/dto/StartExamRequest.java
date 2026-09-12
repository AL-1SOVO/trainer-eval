package com.trainereval.exam.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.validation.constraints.NotNull;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class StartExamRequest {

    @NotNull(message = "试卷ID不能为空")
    private Long paperId;
}
