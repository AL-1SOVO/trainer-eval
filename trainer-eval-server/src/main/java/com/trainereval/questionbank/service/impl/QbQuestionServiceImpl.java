package com.trainereval.questionbank.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.trainereval.common.exception.BusinessException;
import com.trainereval.common.exception.ErrorCode;
import com.trainereval.common.result.PageResult;
import com.trainereval.exam.mapper.ExExamAnswerMapper;
import com.trainereval.exam.mapper.ExWrongAnswerAnalysisMapper;
import com.trainereval.questionbank.entity.QbPaperQuestion;
import com.trainereval.questionbank.entity.QbQuestion;
import com.trainereval.questionbank.mapper.QbPaperQuestionMapper;
import com.trainereval.questionbank.mapper.QbQuestionMapper;
import com.trainereval.questionbank.service.QbQuestionService;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class QbQuestionServiceImpl extends ServiceImpl<QbQuestionMapper, QbQuestion> implements QbQuestionService {

    @Autowired
    private QbPaperQuestionMapper paperQuestionMapper;
    @Autowired
    private ExExamAnswerMapper examAnswerMapper;
    @Autowired
    private ExWrongAnswerAnalysisMapper wrongAnswerAnalysisMapper;

    private static final Map<String, String> TYPE_NAME_TO_CODE = new LinkedHashMap<>();
    static {
        TYPE_NAME_TO_CODE.put("单选题", "SINGLE_CHOICE");
        TYPE_NAME_TO_CODE.put("多选题", "MULTI_CHOICE");
        TYPE_NAME_TO_CODE.put("判断题", "TRUE_FALSE");
        TYPE_NAME_TO_CODE.put("简答题", "SHORT_ANSWER");
        TYPE_NAME_TO_CODE.put("论述题", "ESSAY");
    }

    private static final Map<String, String> TYPE_CODE_TO_NAME;
    static {
        TYPE_CODE_TO_NAME = TYPE_NAME_TO_CODE.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getValue, Map.Entry::getKey));
    }

    @Override
    public PageResult<QbQuestion> pageByCourse(Long courseId, String questionType, Integer difficulty, int page, int size) {
        LambdaQueryWrapper<QbQuestion> w = new LambdaQueryWrapper<>();
        w.eq(QbQuestion::getCourseId, courseId);
        if (questionType != null && !questionType.isEmpty()) w.eq(QbQuestion::getQuestionType, questionType);
        if (difficulty != null) w.eq(QbQuestion::getDifficulty, difficulty);
        w.orderByDesc(QbQuestion::getCreateTime);
        Page<QbQuestion> p = baseMapper.selectPage(new Page<>(page, size), w);
        return PageResult.of(p.getRecords(), p.getTotal(), p.getCurrent(), p.getSize());
    }

    @Override
    public List<QbQuestion> listByCourse(Long courseId) {
        return baseMapper.selectList(new LambdaQueryWrapper<QbQuestion>()
                .eq(QbQuestion::getCourseId, courseId)
                .eq(QbQuestion::getStatus, 1)
                .orderByDesc(QbQuestion::getCreateTime));
    }

    @Override
    @Transactional
    public int importFromExcel(MultipartFile file, Long courseId, Long creatorId) throws Exception {
        InputStream is = file.getInputStream();
        Workbook workbook = WorkbookFactory.create(is);
        Sheet sheet = workbook.getSheetAt(0);

        int count = 0;
        for (int i = 1; i <= sheet.getLastRowNum(); i++) {
            Row row = sheet.getRow(i);
            if (row == null) continue;
            if (isEmptyRow(row)) continue;

            try {
                QbQuestion q = new QbQuestion();
                q.setCourseId(courseId);
                q.setCreatorId(creatorId);
                q.setStatus(1);

                String content = getCellString(row, 0);
                if (content.isEmpty()) continue;
                q.setContent(content);

                String typeName = getCellString(row, 1);
                String typeCode = detectType(typeName);
                if (typeCode == null) continue;
                q.setQuestionType(typeCode);

                if ("SINGLE_CHOICE".equals(typeCode) || "MULTI_CHOICE".equals(typeCode)) {
                    List<String> opts = new ArrayList<>();
                    for (int c = 2; c <= 5; c++) {
                        String opt = getCellString(row, c);
                        if (!opt.isEmpty()) opts.add(opt);
                    }
                    if (!opts.isEmpty()) {
                        q.setOptions(toJson(opts));
                    }
                } else if ("TRUE_FALSE".equals(typeCode)) {
                    q.setOptions(toJson(List.of("正确", "错误")));
                }

                String answer = getCellString(row, 6);
                q.setReferenceAnswer(answer);

                String scoreStr = getCellString(row, 7);
                q.setDefaultScore(parseInt(scoreStr, 5));

                String diffStr = getCellString(row, 8);
                q.setDifficulty(Math.max(1, Math.min(5, parseInt(diffStr, 1))));

                String analysis = getCellString(row, 9);
                if (!analysis.isEmpty()) q.setAnalysis(analysis);

                String tags = getCellString(row, 10);
                if (!tags.isEmpty()) q.setTags(tags);

                String criteria = getCellString(row, 11);
                if (!criteria.isEmpty()) q.setScoringCriteria(criteria);

                baseMapper.insert(q);
                count++;
            } catch (Exception e) {
                log.warn("Failed to import row {}: {}", i + 1, e.getMessage());
            }
        }
        workbook.close();
        is.close();
        return count;
    }

    @Override
    public byte[] exportTemplate() throws Exception {
        Workbook wb = new XSSFWorkbook();
        Sheet sheet = wb.createSheet("试题导入模板");

        // Header style
        CellStyle headerStyle = wb.createCellStyle();
        Font headerFont = wb.createFont();
        headerFont.setBold(true);
        headerFont.setFontHeightInPoints((short) 11);
        headerStyle.setFont(headerFont);
        headerStyle.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        headerStyle.setBorderBottom(BorderStyle.THIN);
        headerStyle.setAlignment(HorizontalAlignment.CENTER);

        String[] headers = {"题目内容*", "题型*", "选项A", "选项B", "选项C", "选项D", "正确答案*", "分值", "难度(1-5)", "解析", "标签", "评分标准"};
        Row headerRow = sheet.createRow(0);
        for (int i = 0; i < headers.length; i++) {
            Cell cell = headerRow.createCell(i);
            cell.setCellValue(headers[i]);
            cell.setCellStyle(headerStyle);
            sheet.setColumnWidth(i, 4500);
        }
        sheet.setColumnWidth(0, 8000); // content wider
        sheet.setColumnWidth(9, 6000); // analysis wider

        // Example rows
        Object[][] examples = {
            {"以下哪个是常见的网络攻击类型？", "单选题", "SQL注入", "定期备份", "安装杀毒软件", "使用VPN", "A", "5", "2", "SQL注入是最常见的Web应用攻击方式之一", "网络安全,基础", "选择正确得满分"},
            {"以下哪些属于数据保护措施？（多选）", "多选题", "数据加密", "定期备份", "共享密码", "访问控制", "A,B,D", "8", "3", "数据加密、定期备份和访问控制是基本的数据保护措施", "数据保护,进阶", ""},
            {"HTTPS协议比HTTP协议更安全", "判断题", "", "", "", "", "正确", "3", "1", "HTTPS使用SSL/TLS加密传输数据", "网络安全,基础", ""},
            {"请简述企业数据保护的三原则", "简答题", "", "", "", "", "合法性、必要性、安全性，各2分", "10", "3", "考察对数据保护基本原则的理解", "数据保护,法规", "答出三个原则各得2分，展开说明酌情加分"},
        };

        for (int r = 1; r <= examples.length; r++) {
            Row row = sheet.createRow(r);
            for (int c = 0; c < examples[r - 1].length; c++) {
                row.createCell(c).setCellValue(String.valueOf(examples[r - 1][c]));
            }
        }

        // Add instruction sheet
        Sheet instrSheet = wb.createSheet("填写说明");
        Row ir = instrSheet.createRow(0);
        ir.createCell(0).setCellValue("题型支持：单选题、多选题、判断题、简答题、论述题");
        ir = instrSheet.createRow(1);
        ir.createCell(0).setCellValue("带有*号的列为必填项");
        ir = instrSheet.createRow(2);
        ir.createCell(0).setCellValue("判断题的正确答案填写'正确'或'错误'");
        ir = instrSheet.createRow(3);
        ir.createCell(0).setCellValue("单选题正确答案填写选项字母如 A，多选题填写 A,B,D");
        ir = instrSheet.createRow(4);
        ir.createCell(0).setCellValue("难度范围1-5，1最简单5最难");
        instrSheet.setColumnWidth(0, 15000);

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        wb.write(bos);
        wb.close();
        return bos.toByteArray();
    }

    @Override
    public Map<String, Object> getTypeStats(Long courseId) {
        Map<String, Object> stats = new HashMap<>();
        LambdaQueryWrapper<QbQuestion> w = new LambdaQueryWrapper<QbQuestion>()
                .eq(QbQuestion::getCourseId, courseId).eq(QbQuestion::getStatus, 1);
        List<QbQuestion> all = baseMapper.selectList(w);
        stats.put("total", all.size());
        Map<String, Long> typeCount = all.stream()
                .collect(Collectors.groupingBy(QbQuestion::getQuestionType, Collectors.counting()));
        Map<String, String> namedCount = new LinkedHashMap<>();
        for (Map.Entry<String, Long> e : typeCount.entrySet()) {
            namedCount.put(TYPE_CODE_TO_NAME.getOrDefault(e.getKey(), e.getKey()), String.valueOf(e.getValue()));
        }
        stats.put("byType", namedCount);
        return stats;
    }

    @Override
    @Transactional
    public void deleteSafely(Long id) {
        QbQuestion q = baseMapper.selectById(id);
        if (q == null) return;

        long paperRefs = paperQuestionMapper.selectCount(
                new LambdaQueryWrapper<QbPaperQuestion>().eq(QbPaperQuestion::getQuestionId, id));
        if (paperRefs > 0) {
            throw new BusinessException(ErrorCode.QUESTION_NOT_FOUND.getCode(),
                    "该题目已被试卷引用，请先从试卷中移除此题后再删除");
        }

        long answerRefs = examAnswerMapper.selectCount(
                new LambdaQueryWrapper<com.trainereval.exam.entity.ExExamAnswer>().eq(com.trainereval.exam.entity.ExExamAnswer::getQuestionId, id));
        if (answerRefs > 0) {
            throw new BusinessException(ErrorCode.QUESTION_NOT_FOUND.getCode(),
                    "该题目已有考试记录，无法删除。可将其禁用（设置状态为禁用）");
        }

        long analysisRefs = wrongAnswerAnalysisMapper.selectCount(
                new LambdaQueryWrapper<com.trainereval.exam.entity.ExWrongAnswerAnalysis>().eq(com.trainereval.exam.entity.ExWrongAnswerAnalysis::getQuestionId, id));
        if (analysisRefs > 0) {
            throw new BusinessException(ErrorCode.QUESTION_NOT_FOUND.getCode(),
                    "该题目已有关联的错题分析记录，无法删除。可将其禁用");
        }

        baseMapper.deleteById(id);
    }

    // ---- helpers ----

    private String getCellString(Row row, int col) {
        Cell cell = row.getCell(col);
        if (cell == null) return "";
        switch (cell.getCellType()) {
            case STRING: return cell.getStringCellValue().trim();
            case NUMERIC:
                double v = cell.getNumericCellValue();
                if (v == Math.floor(v) && !Double.isInfinite(v)) return String.valueOf((long) v);
                return String.valueOf(v);
            case BOOLEAN: return String.valueOf(cell.getBooleanCellValue());
            default: return "";
        }
    }

    private boolean isEmptyRow(Row row) {
        for (int c = 0; c < 6; c++) {
            if (!getCellString(row, c).isEmpty()) return false;
        }
        return true;
    }

    private String detectType(String name) {
        if (name == null) return null;
        String trimmed = name.trim();
        if (trimmed.contains("单选")) return "SINGLE_CHOICE";
        if (trimmed.contains("多选")) return "MULTI_CHOICE";
        if (trimmed.contains("判断")) return "TRUE_FALSE";
        if (trimmed.contains("简答")) return "SHORT_ANSWER";
        if (trimmed.contains("论述") || trimmed.contains("问答") || trimmed.contains("essay")) return "ESSAY";
        return TYPE_NAME_TO_CODE.getOrDefault(trimmed, null);
    }

    private String toJson(List<String> items) {
        return "[" + items.stream().map(s -> "\"" + s.replace("\"", "\\\"") + "\"").collect(Collectors.joining(",")) + "]";
    }

    private int parseInt(String s, int def) {
        try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return def; }
    }
}
