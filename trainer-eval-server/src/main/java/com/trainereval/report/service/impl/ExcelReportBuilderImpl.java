package com.trainereval.report.service.impl;

import com.trainereval.report.dto.ReportDataset;
import com.trainereval.report.service.ExcelReportBuilder;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.util.List;

/**
 * Excel 报表渲染器（真实写入数据）。
 *
 * <h3>原来是什么样</h3>
 * 原实现只写了两个单元格（标题 + 生成时间），入参 {@code params} 一次都没用过 ——
 * 导出的 Excel 是个空壳。用户看到该有数据的地方一片空白，只会认为系统坏了。
 *
 * <h3>几个刻意的处理</h3>
 * <ul>
 *   <li>用 {@link SXSSFWorkbook}（流式）而不是 XSSFWorkbook：报表行数会随报名/考试记录增长，
 *       流式写入把内存占用压到滚动窗口大小，不会因为一次导出把堆撑爆。</li>
 *   <li>标题/副标题/表头/汇总分区排版，而不是"一坨数据"：报表是要给人看和打印的。</li>
 *   <li>数字写成数值型、文本写成字符串：否则 Excel 里无法排序求和，但像 "85.5%" 这种
 *       保持文本更好读 —— 可读性优先于可计算性。</li>
 *   <li>结尾 {@code dispose()} 清临时文件：SXSSF 会在磁盘留临时文件，不清理会累积。</li>
 * </ul>
 */
@Slf4j
@Component
public class ExcelReportBuilderImpl implements ExcelReportBuilder {

    @Override
    public byte[] build(ReportDataset dataset) {
        try (SXSSFWorkbook wb = new SXSSFWorkbook(200); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            wb.setCompressTempFiles(true);
            Sheet sheet = wb.createSheet(safeSheetName(dataset.title()));

            CellStyle titleStyle = titleStyle(wb);
            CellStyle subtitleStyle = subtitleStyle(wb);
            CellStyle headerStyle = headerStyle(wb);
            CellStyle bodyStyle = bodyStyle(wb);
            CellStyle noteStyle = noteStyle(wb);

            int colCount = Math.max(dataset.headers().size(), 1);
            int rowIdx = 0;

            Row titleRow = sheet.createRow(rowIdx++);
            titleRow.setHeightInPoints(24);
            Cell titleCell = titleRow.createCell(0);
            titleCell.setCellValue(dataset.title());
            titleCell.setCellStyle(titleStyle);
            sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, colCount - 1));

            Row subRow = sheet.createRow(rowIdx++);
            Cell subCell = subRow.createCell(0);
            subCell.setCellValue(dataset.subtitle() == null ? "" : dataset.subtitle());
            subCell.setCellStyle(subtitleStyle);
            sheet.addMergedRegion(new CellRangeAddress(1, 1, 0, colCount - 1));

            rowIdx++; // 空行

            Row headerRow = sheet.createRow(rowIdx++);
            headerRow.setHeightInPoints(20);
            for (int c = 0; c < colCount; c++) {
                Cell cell = headerRow.createCell(c);
                cell.setCellValue(c < dataset.headers().size() ? dataset.headers().get(c) : "");
                cell.setCellStyle(headerStyle);
            }
            int headerRowIdx = rowIdx - 1;

            List<List<Object>> rows = dataset.rows();
            if (rows == null || rows.isEmpty()) {
                Row empty = sheet.createRow(rowIdx++);
                Cell cell = empty.createCell(0);
                cell.setCellValue("（没有符合条件的数据）");
                cell.setCellStyle(bodyStyle);
                sheet.addMergedRegion(new CellRangeAddress(rowIdx - 1, rowIdx - 1, 0, colCount - 1));
            } else {
                for (List<Object> dataRow : rows) {
                    Row row = sheet.createRow(rowIdx++);
                    for (int c = 0; c < colCount; c++) {
                        Cell cell = row.createCell(c);
                        Object value = c < dataRow.size() ? dataRow.get(c) : null;
                        writeCell(cell, value);
                        cell.setCellStyle(bodyStyle);
                    }
                }
            }

            List<String> summary = dataset.summary();
            if (summary != null && !summary.isEmpty()) {
                rowIdx++;
                for (String line : summary) {
                    Row row = sheet.createRow(rowIdx++);
                    Cell cell = row.createCell(0);
                    cell.setCellValue(line);
                    cell.setCellStyle(bodyStyle);
                    if (colCount > 1) {
                        sheet.addMergedRegion(new CellRangeAddress(rowIdx - 1, rowIdx - 1, 0, colCount - 1));
                    }
                }
            }

            if (dataset.note() != null && !dataset.note().isBlank()) {
                rowIdx++;
                Row row = sheet.createRow(rowIdx);
                Cell cell = row.createCell(0);
                cell.setCellValue(dataset.note());
                cell.setCellStyle(noteStyle);
                if (colCount > 1) {
                    sheet.addMergedRegion(new CellRangeAddress(rowIdx, rowIdx, 0, colCount - 1));
                }
            }

            for (int c = 0; c < colCount; c++) {
                String header = c < dataset.headers().size() ? dataset.headers().get(c) : "";
                // 中文按 2 字符宽估算；过窄会显示成 ###，过宽则浪费版面
                int visual = header.length() + header.replaceAll("[^\\x00-\\xff]", "").length();
                sheet.setColumnWidth(c, Math.min(60, Math.max(14, visual + 6)) * 256);
            }
            sheet.createFreezePane(0, headerRowIdx + 1);

            wb.write(out);
            wb.dispose();
            byte[] bytes = out.toByteArray();
            log.info("Excel 报表已生成：{}，{} 行，{} 字节", dataset.title(), dataset.rowCount(), bytes.length);
            return bytes;
        } catch (Exception e) {
            // 抛出而不是 return new byte[0]：原实现在异常时返回 0 字节，
            // 结果是生成一个 0 字节的"已完成"报表，用户下载到空文件还不知道错在哪。
            throw new IllegalStateException("Excel 报表生成失败：" + e.getMessage(), e);
        }
    }

    private void writeCell(Cell cell, Object value) {
        if (value == null) {
            cell.setCellValue("");
            return;
        }
        if (value instanceof Number number) {
            cell.setCellValue(number.doubleValue());
            return;
        }
        cell.setCellValue(String.valueOf(value));
    }

    private CellStyle titleStyle(SXSSFWorkbook wb) {
        CellStyle style = wb.createCellStyle();
        Font font = wb.createFont();
        font.setBold(true);
        font.setFontHeightInPoints((short) 16);
        style.setFont(font);
        style.setAlignment(HorizontalAlignment.CENTER);
        style.setVerticalAlignment(VerticalAlignment.CENTER);
        return style;
    }

    private CellStyle subtitleStyle(SXSSFWorkbook wb) {
        CellStyle style = wb.createCellStyle();
        Font font = wb.createFont();
        font.setFontHeightInPoints((short) 9);
        font.setColor(IndexedColors.GREY_50_PERCENT.getIndex());
        style.setFont(font);
        style.setAlignment(HorizontalAlignment.LEFT);
        return style;
    }

    private CellStyle headerStyle(SXSSFWorkbook wb) {
        CellStyle style = wb.createCellStyle();
        Font font = wb.createFont();
        font.setBold(true);
        style.setFont(font);
        style.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setAlignment(HorizontalAlignment.CENTER);
        style.setVerticalAlignment(VerticalAlignment.CENTER);
        border(style);
        return style;
    }

    private CellStyle bodyStyle(SXSSFWorkbook wb) {
        CellStyle style = wb.createCellStyle();
        style.setVerticalAlignment(VerticalAlignment.CENTER);
        style.setWrapText(false);
        border(style);
        return style;
    }

    private CellStyle noteStyle(SXSSFWorkbook wb) {
        CellStyle style = wb.createCellStyle();
        Font font = wb.createFont();
        font.setFontHeightInPoints((short) 9);
        font.setColor(IndexedColors.GREY_50_PERCENT.getIndex());
        style.setFont(font);
        return style;
    }

    private void border(CellStyle style) {
        style.setBorderTop(BorderStyle.THIN);
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
    }

    /** Excel 工作表名不允许 : \ / ? * [ ]，且上限 31 字符。 */
    private String safeSheetName(String name) {
        if (name == null || name.isBlank()) {
            return "报表";
        }
        String cleaned = name.replaceAll("[\\\\/:*?\\[\\]]", " ").trim();
        return cleaned.length() > 31 ? cleaned.substring(0, 31) : cleaned;
    }
}
