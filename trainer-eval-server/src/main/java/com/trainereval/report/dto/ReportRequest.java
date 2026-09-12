package com.trainereval.report.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ReportRequest {

    @NotBlank(message = "报告类型不能为空")
    private String reportType;

    @NotBlank(message = "报告格式不能为空")
    private String reportFormat;

    private Map<String, Object> params;

    public static final String TYPE_USER_TRAINING = "USER_TRAINING";
    public static final String TYPE_USER_EXAM = "USER_EXAM";
    public static final String TYPE_DEPT_TRAINING = "DEPT_TRAINING";
    public static final String TYPE_COURSE_STATS = "COURSE_STATS";

    public static final String FORMAT_XLSX = "XLSX";
    public static final String FORMAT_PDF = "PDF";
}
