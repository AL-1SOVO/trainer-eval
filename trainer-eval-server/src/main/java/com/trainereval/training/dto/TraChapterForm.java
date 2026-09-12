package com.trainereval.training.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class TraChapterForm {
    @NotBlank(message = "章节标题不能为空")
    private String title;
    private String description;
    private Integer sortOrder;
}
