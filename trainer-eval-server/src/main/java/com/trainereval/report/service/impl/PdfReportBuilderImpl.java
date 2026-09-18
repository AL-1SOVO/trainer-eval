package com.trainereval.report.service.impl;

import com.itextpdf.io.font.PdfEncodings;
import com.itextpdf.kernel.colors.ColorConstants;
import com.itextpdf.kernel.font.PdfFont;
import com.itextpdf.kernel.font.PdfFontFactory;
import com.itextpdf.kernel.geom.PageSize;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.layout.Document;
import com.itextpdf.layout.element.Cell;
import com.itextpdf.layout.element.Paragraph;
import com.itextpdf.layout.element.Table;
import com.itextpdf.layout.properties.TextAlignment;
import com.itextpdf.layout.properties.UnitValue;
import com.trainereval.report.dto.ReportDataset;
import com.trainereval.report.service.PdfReportBuilder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.List;

/**
 * PDF 报表渲染器（iText 8，生成**真正的 PDF**）。
 *
 * <h3>原来是什么样</h3>
 * 原实现返回的是 {@code ("PDF: " + title + " | Generated: ...").getBytes()} ——
 * 一串纯文本字节，而 {@code ReportController} 对 .pdf 设的是 {@code application/pdf}。
 * 用户下载到的是一个"声称是 PDF、实际打不开"的文件，比功能缺失更糟。
 *
 * <h3>中文字体是这里最容易踩的坑</h3>
 * PDF 的 14 种标准字体（Helvetica 等）**不含中文字形**。不指定字体时，
 * 中文会渲染成空白或直接抛异常，而不是"显示成方块"那么显眼。
 * 所以这里按顺序取字体：
 * <ol>
 *   <li>iText 自带的 CJK 字体 {@code STSong-Light}（随 font-asian 模块提供，
 *       不依赖系统装字体，跨平台最稳）；</li>
 *   <li>系统上的中文字体文件（Windows 的 simhei/msyh、Linux 的 Noto CJK）；</li>
 *   <li>都拿不到就**明确报错**，而不是生成一份中文全空白的 PDF ——
 *       那种"看起来成功"的输出最难排查。</li>
 * </ol>
 */
@Slf4j
@Component
public class PdfReportBuilderImpl implements PdfReportBuilder {

    private static final String CJK_CANDIDATE = "中文测试";

    /** 系统字体候选：按优先级排列，覆盖 Windows 与常见 Linux 发行版。 */
    private static final String[] SYSTEM_FONT_CANDIDATES = {
            "C:/Windows/Fonts/simhei.ttf",
            "C:/Windows/Fonts/msyh.ttc",
            "C:/Windows/Fonts/simsun.ttc",
            "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc",
            "/usr/share/fonts/truetype/noto/NotoSansCJK-Regular.ttc",
            "/usr/share/fonts/truetype/wqy/wqy-zenhei.ttc",
            "/System/Library/Fonts/PingFang.ttc"
    };

    private volatile FontSource cachedSource;

    /** 字体来源：探明一次后可复用，但**不能缓存 PdfFont 对象本身**（见 {@link #createFont}）。 */
    private record FontSource(boolean builtin, String value) {
    }

    @Override
    public byte[] build(ReportDataset dataset) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PdfDocument pdf = new PdfDocument(new PdfWriter(out));
            // 列多时横向排版，否则中文列会被压成竖条
            PageSize size = dataset.headers().size() > 6 ? PageSize.A4.rotate() : PageSize.A4;
            Document doc = new Document(pdf, size);
            doc.setMargins(36, 36, 36, 36);

            // 字体必须**每份文档新建一个**：iText 的 PdfFont 会持有绑定到具体 PdfDocument 的
            // 间接对象，跨文档复用会抛 "Pdf indirect object belongs to other PDF document"。
            // 实测：第一份 PDF 成功、后续 3 份全失败 —— 就是缓存了 PdfFont 导致的。
            // 注意 cached=true 缓存的是"字体程序"（解析结果），不是 PdfFont，所以性能不受影响。
            PdfFont font = createFont(resolveSource());

            doc.add(new Paragraph(dataset.title())
                    .setFont(font).setFontSize(18).setBold()
                    .setTextAlignment(TextAlignment.CENTER)
                    .setMarginBottom(4));

            if (dataset.subtitle() != null && !dataset.subtitle().isBlank()) {
                doc.add(new Paragraph(dataset.subtitle())
                        .setFont(font).setFontSize(9)
                        .setFontColor(ColorConstants.GRAY)
                        .setTextAlignment(TextAlignment.CENTER)
                        .setMarginBottom(12));
            }

            doc.add(buildTable(dataset, font));

            List<String> summary = dataset.summary();
            if (summary != null && !summary.isEmpty()) {
                doc.add(new Paragraph("汇总").setFont(font).setFontSize(11).setBold().setMarginTop(14));
                for (String line : summary) {
                    doc.add(new Paragraph("· " + line).setFont(font).setFontSize(10).setMarginBottom(2));
                }
            }

            if (dataset.note() != null && !dataset.note().isBlank()) {
                doc.add(new Paragraph(dataset.note())
                        .setFont(font).setFontSize(8)
                        .setFontColor(ColorConstants.GRAY)
                        .setMarginTop(16));
            }

            doc.close();
            byte[] bytes = out.toByteArray();
            log.info("PDF 报表已生成：{}，{} 行，{} 字节", dataset.title(), dataset.rowCount(), bytes.length);
            return bytes;
        } catch (Exception e) {
            throw new IllegalStateException("PDF 报表生成失败：" + e.getMessage(), e);
        }
    }

    private Table buildTable(ReportDataset dataset, PdfFont font) {
        int colCount = Math.max(dataset.headers().size(), 1);
        Table table = new Table(UnitValue.createPercentArray(colCount)).useAllAvailableWidth();

        for (String header : dataset.headers()) {
            table.addHeaderCell(new Cell()
                    .add(new Paragraph(header).setFont(font).setFontSize(9).setBold())
                    .setTextAlignment(TextAlignment.CENTER)
                    .setPadding(5));
        }

        List<List<Object>> rows = dataset.rows();
        if (rows == null || rows.isEmpty()) {
            table.addCell(new Cell(1, colCount)
                    .add(new Paragraph("（没有符合条件的数据）").setFont(font).setFontSize(10))
                    .setTextAlignment(TextAlignment.CENTER)
                    .setPadding(8));
            return table;
        }

        for (List<Object> row : rows) {
            for (int c = 0; c < colCount; c++) {
                Object value = c < row.size() ? row.get(c) : null;
                table.addCell(new Cell()
                        .add(new Paragraph(value == null ? "" : String.valueOf(value))
                                .setFont(font).setFontSize(9))
                        .setPadding(4));
            }
        }
        return table;
    }

    /**
     * 探明一个能渲染中文的字体来源（只探一次，结果缓存）。
     *
     * <h3>为什么缓存的是"来源"而不是 PdfFont</h3>
     * iText 的 {@code PdfFont} 持有绑定到具体 {@code PdfDocument} 的间接对象，
     * 跨文档复用会抛 {@code Pdf indirect object belongs to other PDF document}。
     * 实测表现为：第一份 PDF 正常，后面每一份都失败 —— 一个很容易被"第一次测试通过"掩盖的坑。
     * 所以这里只缓存"用哪个字体"，每份文档各自 {@link #createFont} 一次。
     * {@code cached=true} 缓存的是<b>字体程序</b>（解析后的结果），因此不存在重复解析的开销。
     */
    private FontSource resolveSource() {
        FontSource existing = this.cachedSource;
        if (existing != null) {
            return existing;
        }
        synchronized (this) {
            if (this.cachedSource != null) {
                return this.cachedSource;
            }
            try {
                PdfFont probe = PdfFontFactory.createFont("STSong-Light", "UniGB-UCS2-H",
                        PdfFontFactory.EmbeddingStrategy.PREFER_NOT_EMBEDDED, true);
                probe.getWidth(CJK_CANDIDATE, 10);
                FontSource source = new FontSource(true, "STSong-Light");
                this.cachedSource = source;
                log.info("PDF 中文报表使用 iText 内置字体 STSong-Light");
                return source;
            } catch (Exception e) {
                log.debug("iText 内置 CJK 字体不可用，回退系统字体：{}", e.getMessage());
            }
            for (String path : SYSTEM_FONT_CANDIDATES) {
                if (!new File(path).exists()) {
                    continue;
                }
                try {
                    PdfFont probe = createFromFile(path);
                    probe.getWidth(CJK_CANDIDATE, 10);
                    FontSource source = new FontSource(false, path);
                    this.cachedSource = source;
                    log.info("PDF 中文报表使用系统字体 {}", path);
                    return source;
                } catch (Exception e) {
                    log.debug("字体 {} 加载失败：{}", path, e.getMessage());
                }
            }
            throw new IllegalStateException(
                    "找不到可用的中文字体，无法生成 PDF 报表（请安装中文字体，或改用 Excel 格式导出）");
        }
    }

    /** 每份文档调用一次，创建一个属于该文档的 PdfFont。 */
    private PdfFont createFont(FontSource source) throws java.io.IOException {
        return source.builtin()
                ? PdfFontFactory.createFont(source.value(), "UniGB-UCS2-H",
                        PdfFontFactory.EmbeddingStrategy.PREFER_NOT_EMBEDDED, true)
                : createFromFile(source.value());
    }

    /** .ttc 是字体集合，必须用 createTtcFont 指定索引，直接用 createFont 会失败。 */
    private PdfFont createFromFile(String path) throws java.io.IOException {
        if (path.toLowerCase().endsWith(".ttc")) {
            return PdfFontFactory.createTtcFont(path, 0, PdfEncodings.IDENTITY_H,
                    PdfFontFactory.EmbeddingStrategy.PREFER_EMBEDDED, true);
        }
        return PdfFontFactory.createFont(path, PdfEncodings.IDENTITY_H,
                PdfFontFactory.EmbeddingStrategy.PREFER_EMBEDDED, true);
    }
}
