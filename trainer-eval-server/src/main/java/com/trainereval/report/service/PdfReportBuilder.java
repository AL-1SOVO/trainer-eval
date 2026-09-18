package com.trainereval.report.service;

import com.trainereval.report.dto.ReportDataset;

/**
 * PDF 报表渲染器。与 {@link ExcelReportBuilder} 一样收敛为单个 {@code build(dataset)}。
 */
public interface PdfReportBuilder {

    byte[] build(ReportDataset dataset);
}
