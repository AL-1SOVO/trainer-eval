package com.trainereval.report.dto;

import java.util.List;

/**
 * 报表数据集：**渲染无关**的中间表示。
 *
 * <h3>为什么要单独抽出来</h3>
 * 原来的 `ExcelReportBuilderImpl` / `PdfReportBuilderImpl` 各自实现 4 个方法（共 8 个），
 * 但都只是把标题写进去 —— 因为它们**没有数据可写**：
 * `params` 传进来的其实是前端那个"附加说明"文本框的内容，不是报表数据。
 * <p>
 * 如果让两个 builder 各自去查库，同一个报表会有两套口径、两处可能写错。
 * 所以查询与聚合只做一次（{@code ReportDatasetService}），两个 builder 只负责"把表格画出来"。
 * 这也让"给报表加一种类型"从改 4 处变成改 1 处。
 */
public record ReportDataset(
        /** 报表主标题，如"员工培训报表" */
        String title,
        /** 副标题：生成时间、数据范围、筛选条件等，用于让读者知道这份报表的口径 */
        String subtitle,
        /** 表头 */
        List<String> headers,
        /** 数据行；每行长度与 headers 一致 */
        List<List<Object>> rows,
        /** 汇总信息（在表格下方以"键：值"展示），可为空 */
        List<String> summary,
        /** 页脚说明：口径、数据来源、免责说明 */
        String note) {

    public int rowCount() {
        return rows == null ? 0 : rows.size();
    }
}
