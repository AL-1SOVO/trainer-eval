package com.trainereval.training.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class TraMaterialForm {
    @NotBlank(message = "资料标题不能为空")
    private String title;
    @NotBlank(message = "资料类型不能为空")
    private String materialType;
    private String fileUrl;
    private Long fileSize;
    private String content;
    private Integer duration;
    private Integer sortOrder;
}
