package com.trainereval.report.service.impl;
import com.trainereval.report.service.ExcelReportBuilder;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.apache.poi.ss.usermodel.*;
import org.springframework.stereotype.Component;
import java.io.ByteArrayOutputStream;
import java.time.LocalDateTime;
import java.util.Map;

@Component
public class ExcelReportBuilderImpl implements ExcelReportBuilder {
    @Override public byte[] buildUserTrainingReport(Map<String, Object> p) { return build("员工培训报表"); }
    @Override public byte[] buildUserExamReport(Map<String, Object> p) { return build("考试成绩报表"); }
    @Override public byte[] buildDeptTrainingReport(Map<String, Object> p) { return build("部门培训报表"); }
    @Override public byte[] buildCourseStatsReport(Map<String, Object> p) { return build("课程统计报表"); }
    private byte[] build(String title) {
        try (SXSSFWorkbook wb = new SXSSFWorkbook(100)) {
            Sheet sh = wb.createSheet(title);
            Row h = sh.createRow(0);
            h.createCell(0).setCellValue(title);
            h.createCell(1).setCellValue(LocalDateTime.now().toString());
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out); return out.toByteArray();
        } catch (Exception e) { return new byte[0]; }
    }
}
