package com.trainereval.report.service;

import com.trainereval.report.dto.ReportDataset;

/**
 * Excel 报表渲染器。
 * <p>
 * 接口从"4 个报表类型各一个方法"收敛成一个 {@code build(dataset)}：
 * 原来 4 个方法体内是同一段代码，只有标题不同 —— 那是把"报表类型"这个维度
 * 混进了渲染层。类型差异属于<b>数据</b>，已经在 {@link ReportDataset} 里表达完毕。
 */
public interface ExcelReportBuilder {

    byte[] build(ReportDataset dataset);
}
