package com.trainereval.report.service;

import java.util.List;
import java.util.Map;

public interface ExcelReportBuilder {

    byte[] buildUserTrainingReport(Map<String, Object> params);

    byte[] buildUserExamReport(Map<String, Object> params);

    byte[] buildDeptTrainingReport(Map<String, Object> params);

    byte[] buildCourseStatsReport(Map<String, Object> params);
}
