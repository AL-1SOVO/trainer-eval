package com.trainereval.report.service.impl;
import com.trainereval.report.service.PdfReportBuilder;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Map;

@Component
public class PdfReportBuilderImpl implements PdfReportBuilder {
    @Override public byte[] buildUserTrainingReport(Map<String, Object> p) { return build("员工培训报表", p); }
    @Override public byte[] buildUserExamReport(Map<String, Object> p) { return build("考试成绩报表", p); }
    @Override public byte[] buildDeptTrainingReport(Map<String, Object> p) { return build("部门培训报表", p); }
    @Override public byte[] buildCourseStatsReport(Map<String, Object> p) { return build("课程统计报表", p); }
    private byte[] build(String title, Map<String, Object> p) { return ("PDF: " + title + " | Generated: " + LocalDateTime.now()).getBytes(StandardCharsets.UTF_8); }
}
