package com.trainereval.training.service;

import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfReader;
import com.itextpdf.kernel.pdf.canvas.parser.PdfTextExtractor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTable;
import org.apache.poi.xslf.usermodel.XSLFTableCell;
import org.apache.poi.xslf.usermodel.XSLFTableRow;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 培训资料正文抽取器 —— 补上 RAG 链路里一直缺的<b>解析层</b>。
 *
 * <h3>为什么必须有这一层</h3>
 * 在它之前，内训师上传 PDF/Word/PPT 只会拿到一个 fileUrl，资料本身没有正文；
 * 而切块、全文索引、向量化<b>全部以正文为输入</b>。没有正文的资料，
 * 无论后面接多好的 embedding 和重排都救不回来 —— 检索质量的上限在这里被物理锁死。
 * 实测：{@code tra_material} 里 DOCUMENT/VIDEO 类资料的 {@code content} 为空，
 * 因此从来没有产生过 {@code rag_chunk}，也就永远搜不到（这是"解析层缺失"的具体表现）。
 *
 * <h3>能力边界（明确写出来，避免被误当成"支持所有格式"）</h3>
 * <ul>
 *   <li><b>支持</b>：txt/md/csv/json/log/xml/html（纯文本，自动探测 UTF-8 / GBK）、
 *       docx（段落 + 表格）、pptx（逐页文本 + 表格）、xlsx（逐 sheet 逐行）、pdf（文本层）。</li>
 *   <li><b>不支持</b>：扫描件/图片型 PDF（没有文本层，需要 OCR）。
 *       这类文件抽出来是空的 —— 本类会返回明确警告，<b>由调用方拒绝建资料</b>，
 *       而不是静默落一条"没有正文、搜不到"的资料。MimirQ 侧有 OCR 解析后端可以覆盖这种场景。</li>
 *   <li><b>不支持</b>：doc（旧格式，需要 poi-scratchpad 的 HWPF，暂未接）、ppt（旧格式）。</li>
 * </ul>
 */
@Slf4j
@Component
public class DocumentTextExtractor {

    /** 抽取结果。{@code blocks} 是"段落/slide/sheet 行"的数量，用于给用户一个直观的解析规模。 */
    public record Extraction(String text, String format, int blocks, List<String> warnings) {

        public boolean isEmpty() {
            return text == null || text.isBlank();
        }

        public int charCount() {
            return text == null ? 0 : text.length();
        }
    }

    /** 抽取文本的上限。防止一份超长文档撑爆数据库行与后续切块耗时。 */
    private static final int MAX_CHARS = 200_000;

    private static final Set<String> TEXT_FORMATS = Set.of(
            "txt", "md", "markdown", "csv", "json", "log", "xml", "yaml", "yml");

    private static final Set<String> HTML_FORMATS = Set.of("html", "htm");

    private static final Set<String> SUPPORTED = new LinkedHashSet<>();

    static {
        SUPPORTED.addAll(TEXT_FORMATS);
        SUPPORTED.addAll(HTML_FORMATS);
        SUPPORTED.addAll(List.of("docx", "pptx", "xlsx", "pdf"));
    }

    /** GBK 作为中文纯文本的兜底编码：老资料里并不少见。 */
    private static final Charset GBK = Charset.forName("GBK");

    private static final Pattern HTML_TAG = Pattern.compile("(?s)<script.*?</script>|<style.*?</style>|<[^>]+>");
    private static final Pattern HTML_ENTITY = Pattern.compile("&nbsp;|&amp;|&lt;|&gt;|&quot;|&#39;");
    private static final Pattern BLANK_LINES = Pattern.compile("\\n{3,}");

    /** 支持的扩展名（小写、不带点），供前端提示与接口校验复用。 */
    public Set<String> supportedFormats() {
        return Set.copyOf(SUPPORTED);
    }

    public boolean supports(String fileName) {
        return SUPPORTED.contains(extensionOf(fileName));
    }

    /**
     * 抽取正文。
     *
     * @param data         文件字节（调用方已读入一次，避免在解析器里重复 IO）
     * @param originalName 原始文件名（用于判断格式）
     */
    public Extraction extract(byte[] data, String originalName) throws IOException {
        if (data == null || data.length == 0) {
            return new Extraction("", extensionOf(originalName), 0,
                    List.of("文件内容为空"));
        }
        String ext = extensionOf(originalName);
        List<String> warnings = new ArrayList<>();
        String text;
        int blocks;

        switch (ext) {
            case "docx" -> {
                DocxResult r = extractDocx(data, warnings);
                text = r.text();
                blocks = r.blocks();
            }
            case "pptx" -> {
                PptxResult r = extractPptx(data, warnings);
                text = r.text();
                blocks = r.blocks();
            }
            case "xlsx" -> {
                XlsxResult r = extractXlsx(data, warnings);
                text = r.text();
                blocks = r.blocks();
            }
            case "pdf" -> {
                PdfResult r = extractPdf(data, warnings);
                text = r.text();
                blocks = r.blocks();
            }
            default -> {
                if (HTML_FORMATS.contains(ext)) {
                    text = stripHtml(readTextSmart(data, warnings));
                } else if (TEXT_FORMATS.contains(ext)) {
                    text = readTextSmart(data, warnings);
                } else {
                    throw new IOException("不支持的文件类型：." + ext
                            + "（当前支持：" + String.join("/", SUPPORTED) + "）");
                }
                blocks = countNonBlankLines(text);
            }
        }

        String normalized = normalize(text);
        if (normalized.length() > MAX_CHARS) {
            normalized = normalized.substring(0, MAX_CHARS);
            warnings.add("正文超过 " + MAX_CHARS + " 字，已截断（后续内容不会进入索引）");
        }
        if (normalized.isBlank()) {
            warnings.add("未能从文件中抽取到任何文字。若是扫描件/图片型 PDF，需要 OCR 才能解析。");
        }
        return new Extraction(normalized, ext, blocks, warnings);
    }

    // ------------------------------------------------------------------ 各格式实现

    private record DocxResult(String text, int blocks) {
    }

    private DocxResult extractDocx(byte[] data, List<String> warnings) throws IOException {
        StringBuilder sb = new StringBuilder();
        int blocks = 0;
        try (InputStream in = new ByteArrayInputStream(data); XWPFDocument doc = new XWPFDocument(in)) {
            for (XWPFParagraph p : doc.getParagraphs()) {
                String t = p.getText();
                if (t != null && !t.isBlank()) {
                    sb.append(t.trim()).append('\n');
                    blocks++;
                }
            }
            // 表格常承载评分标准/参数清单，漏掉会明显损失信息
            for (XWPFTable table : doc.getTables()) {
                for (XWPFTableRow row : table.getRows()) {
                    List<String> cells = new ArrayList<>();
                    for (XWPFTableCell cell : row.getTableCells()) {
                        String t = cell.getText();
                        if (t != null && !t.isBlank()) {
                            cells.add(t.trim().replaceAll("\\s+", " "));
                        }
                    }
                    if (!cells.isEmpty()) {
                        sb.append(String.join(" | ", cells)).append('\n');
                        blocks++;
                    }
                }
            }
            int images = doc.getAllPictures().size();
            if (images > 0) {
                warnings.add("文档含 " + images + " 张图片，图片内的文字不会被解析（需要 OCR）");
            }
        } catch (Exception e) {
            throw new IOException("docx 解析失败：" + e.getMessage(), e);
        }
        return new DocxResult(sb.toString(), blocks);
    }

    private record PptxResult(String text, int blocks) {
    }

    private PptxResult extractPptx(byte[] data, List<String> warnings) throws IOException {
        StringBuilder sb = new StringBuilder();
        int blocks = 0;
        try (InputStream in = new ByteArrayInputStream(data); XMLSlideShow slideShow = new XMLSlideShow(in)) {
            int page = 0;
            for (XSLFSlide slide : slideShow.getSlides()) {
                page++;
                sb.append("第 ").append(page).append(" 页\n");
                for (XSLFShape shape : slide.getShapes()) {
                    if (shape instanceof XSLFTextShape textShape) {
                        String t = textShape.getText();
                        if (t != null && !t.isBlank()) {
                            sb.append(t.trim()).append('\n');
                            blocks++;
                        }
                    } else if (shape instanceof XSLFTable table) {
                        // 幻灯片里的表格：单元格本身就是文本形状
                        for (XSLFTableRow row : table.getRows()) {
                            List<String> cells = new ArrayList<>();
                            for (XSLFTableCell cell : row.getCells()) {
                                String t = cell.getText();
                                if (t != null && !t.isBlank()) {
                                    cells.add(t.trim().replaceAll("\\s+", " "));
                                }
                            }
                            if (!cells.isEmpty()) {
                                sb.append(String.join(" | ", cells)).append('\n');
                                blocks++;
                            }
                        }
                    }
                }
            }
            int images = slideShow.getPictureData().size();
            if (images > 0) {
                warnings.add("演示文稿含 " + images + " 张图片，图片内的文字不会被解析（需要 OCR）");
            }
        } catch (Exception e) {
            throw new IOException("pptx 解析失败：" + e.getMessage(), e);
        }
        return new PptxResult(sb.toString(), blocks);
    }

    private record XlsxResult(String text, int blocks) {
    }

    private XlsxResult extractXlsx(byte[] data, List<String> warnings) throws IOException {
        StringBuilder sb = new StringBuilder();
        int blocks = 0;
        DataFormatter formatter = new DataFormatter();
        try (InputStream in = new ByteArrayInputStream(data); Workbook workbook = new org.apache.poi.xssf.usermodel.XSSFWorkbook(in)) {
            for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
                Sheet sheet = workbook.getSheetAt(i);
                sb.append("【").append(sheet.getSheetName()).append("】\n");
                for (Row row : sheet) {
                    List<String> cells = new ArrayList<>();
                    for (Cell cell : row) {
                        String v = formatter.formatCellValue(cell);
                        if (v != null && !v.isBlank()) {
                            cells.add(v.trim());
                        }
                    }
                    if (!cells.isEmpty()) {
                        sb.append(String.join(" | ", cells)).append('\n');
                        blocks++;
                    }
                }
            }
            if (workbook.getNumberOfSheets() > 20) {
                warnings.add("工作簿含 " + workbook.getNumberOfSheets() + " 个 sheet，正文较长时可能被截断");
            }
        } catch (Exception e) {
            throw new IOException("xlsx 解析失败：" + e.getMessage(), e);
        }
        return new XlsxResult(sb.toString(), blocks);
    }

    private record PdfResult(String text, int blocks) {
    }

    private PdfResult extractPdf(byte[] data, List<String> warnings) throws IOException {
        StringBuilder sb = new StringBuilder();
        int pages = 0;
        int emptyPages = 0;
        try (PdfDocument pdf = new PdfDocument(new PdfReader(new ByteArrayInputStream(data)))) {
            int total = pdf.getNumberOfPages();
            for (int i = 1; i <= total; i++) {
                String t;
                try {
                    t = PdfTextExtractor.getTextFromPage(pdf.getPage(i));
                } catch (Exception e) {
                    // 单页解析失败不该让整份文档失败
                    log.warn("PDF 第 {} 页文本抽取失败：{}", i, e.getMessage());
                    t = "";
                }
                if (t == null || t.isBlank()) {
                    emptyPages++;
                    continue;
                }
                sb.append(t.trim()).append('\n');
                pages++;
            }
            if (emptyPages > 0) {
                warnings.add(emptyPages + "/" + total + " 页没有文本层（可能是扫描件或纯图片页），这部分内容无法被检索");
            }
        } catch (Exception e) {
            throw new IOException("pdf 解析失败：" + e.getMessage(), e);
        }
        return new PdfResult(sb.toString(), pages);
    }

    // ------------------------------------------------------------------ 文本读取与清洗

    /**
     * 读取纯文本并探测编码。
     * 先按 UTF-8 严格解码；失败说明不是合法 UTF-8（中文老资料常见 GBK），退回 GBK。
     * 关键在于用 <b>严格</b> 模式：{@code new String(bytes, UTF_8)} 会把非法字节替换成 U+FFFD，
     * 不报错，于是 GBK 文件会被"成功"读成一堆乱码 —— 那比读失败更糟，因为它会静默污染索引。
     */
    private String readTextSmart(byte[] bytes, List<String> warnings) throws IOException {
        if (bytes.length == 0) {
            return "";
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException e) {
            warnings.add("文件不是 UTF-8 编码，已按 GBK 读取（若出现乱码请转成 UTF-8 后重新上传）");
            return new String(bytes, GBK);
        }
    }

    private String stripHtml(String html) {
        if (html == null) {
            return "";
        }
        String text = HTML_TAG.matcher(html).replaceAll(" ");
        text = HTML_ENTITY.matcher(text).replaceAll(" ");
        return text;
    }

    /** 归一化：统一换行、压掉多余空行与行内空白。切块器按段落切，这里保持段落边界。 */
    private String normalize(String text) {
        if (text == null) {
            return "";
        }
        String t = text.replace("\r\n", "\n").replace('\r', '\n');
        t = t.replaceAll("[ \\t\\u00A0]+", " ");
        t = BLANK_LINES.matcher(t).replaceAll("\n\n");
        return t.trim();
    }

    private int countNonBlankLines(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        int n = 0;
        for (String line : text.split("\n")) {
            if (!line.isBlank()) {
                n++;
            }
        }
        return n;
    }

    private String extensionOf(String fileName) {
        if (fileName == null) {
            return "";
        }
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
