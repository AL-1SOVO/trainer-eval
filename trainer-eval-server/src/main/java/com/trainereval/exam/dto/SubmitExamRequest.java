package com.trainereval.exam.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SubmitExamRequest {

    @NotNull(message = "考试记录ID不能为空")
    private Long recordId;

    @Valid
    private List<AnswerDTO> answers;
}
