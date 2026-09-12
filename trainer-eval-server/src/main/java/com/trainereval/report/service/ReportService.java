package com.trainereval.report.service;

import com.trainereval.common.result.PageResult;
import com.trainereval.report.dto.ReportRequest;
import com.trainereval.report.entity.RptExportLog;

public interface ReportService {

    Long generateReport(ReportRequest request, Long userId);

    PageResult<RptExportLog> getHistory(Long userId, int page, int size);

    RptExportLog getExportLog(Long logId);

    byte[] downloadFile(Long logId);

    void deleteReport(Long logId);
}
