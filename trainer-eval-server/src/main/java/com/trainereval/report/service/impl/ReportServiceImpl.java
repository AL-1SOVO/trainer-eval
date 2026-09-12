package com.trainereval.report.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trainereval.common.result.PageResult;
import com.trainereval.report.dto.ReportRequest;
import com.trainereval.report.entity.RptExportLog;
import com.trainereval.report.mapper.RptExportLogMapper;
import com.trainereval.report.service.ExcelReportBuilder;
import com.trainereval.report.service.PdfReportBuilder;
import com.trainereval.report.service.ReportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReportServiceImpl implements ReportService {

    private final RptExportLogMapper exportLogMapper;
    private final ExcelReportBuilder excelReportBuilder;
    private final PdfReportBuilder pdfReportBuilder;
    private final ObjectMapper objectMapper;

    private static final String REPORT_DIR = System.getProperty("user.home") + "/trainer-eval/reports";

    @Override
    @Transactional
    public Long generateReport(ReportRequest request, Long userId) {
        RptExportLog logEntry = new RptExportLog();
        logEntry.setUserId(userId);
        logEntry.setReportType(request.getReportType());
        logEntry.setReportFormat(request.getReportFormat());
        logEntry.setStatus(RptExportLog.STATUS_PENDING);
        try {
            logEntry.setParams(objectMapper.writeValueAsString(request.getParams()));
        } catch (Exception e) {
            logEntry.setParams("{}");
        }

        exportLogMapper.insert(logEntry);

        doGenerate(logEntry.getId());

        return logEntry.getId();
    }

    @Override
    public PageResult<RptExportLog> getHistory(Long userId, int page, int size) {
        Page<RptExportLog> pageParam = new Page<>(page, size);
        LambdaQueryWrapper<RptExportLog> wrapper = new LambdaQueryWrapper<RptExportLog>()
                .eq(RptExportLog::getUserId, userId)
                .orderByDesc(RptExportLog::getCreateTime);

        Page<RptExportLog> result = exportLogMapper.selectPage(pageParam, wrapper);
        result.getRecords().forEach(this::attachFileUrl);
        return PageResult.of(result.getRecords(), result.getTotal(), result.getCurrent(), result.getSize());
    }

    @Override
    public RptExportLog getExportLog(Long logId) {
        RptExportLog logEntry = exportLogMapper.selectById(logId);
        if (logEntry == null) {
            throw new RuntimeException("导出记录不存在");
        }
        return logEntry;
    }

    @Override
    public byte[] downloadFile(Long logId) {
        RptExportLog logEntry = exportLogMapper.selectById(logId);
        if (logEntry == null) {
            throw new RuntimeException("导出记录不存在");
        }
        if (!RptExportLog.STATUS_COMPLETED.equals(logEntry.getStatus())) {
            throw new RuntimeException("报告尚未生成完成");
        }
        try {
            return Files.readAllBytes(Paths.get(logEntry.getFilePath()));
        } catch (IOException e) {
            log.error("Failed to read report file: {}", e.getMessage(), e);
            throw new RuntimeException("读取报告文件失败");
        }
    }

    @Override
    @Transactional
    public void deleteReport(Long logId) {
        RptExportLog logEntry = exportLogMapper.selectById(logId);
        if (logEntry == null) {
            throw new RuntimeException("导出记录不存在");
        }
        if (logEntry.getFilePath() != null) {
            try {
                Files.deleteIfExists(Paths.get(logEntry.getFilePath()));
            } catch (IOException e) {
                log.warn("Failed to delete report file: {}", e.getMessage());
            }
        }
        exportLogMapper.deleteById(logId);
    }

    @Async
    public void doGenerate(Long logId) {
        RptExportLog logEntry = exportLogMapper.selectById(logId);
        if (logEntry == null) {
            log.error("Export log not found: {}", logId);
            return;
        }

        try {
            logEntry.setStatus(RptExportLog.STATUS_GENERATING);
            exportLogMapper.updateById(logEntry);

            java.util.Map<String, Object> params = new java.util.HashMap<>();
            if (logEntry.getParams() != null && !logEntry.getParams().isEmpty()) {
                try {
                    @SuppressWarnings("unchecked")
                    java.util.Map<String, Object> parsed = objectMapper.readValue(logEntry.getParams(), java.util.Map.class);
                    params = parsed;
                } catch (Exception e) {
                    log.warn("Failed to parse report params: {}", e.getMessage());
                }
            }

            ensureReportDir();
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
            String typeName = getTypeName(logEntry.getReportType());
            String ext = ReportRequest.FORMAT_PDF.equals(logEntry.getReportFormat()) ? ".pdf" : ".xlsx";
            String fileName = typeName + "_" + timestamp + ext;
            String filePath = REPORT_DIR + "/" + fileName;

            byte[] fileData;
            if (ReportRequest.FORMAT_PDF.equals(logEntry.getReportFormat())) {
                fileData = generatePdfReport(logEntry.getReportType(), params);
            } else {
                fileData = generateExcelReport(logEntry.getReportType(), params);
            }

            try (FileOutputStream fos = new FileOutputStream(filePath)) {
                fos.write(fileData);
            }

            logEntry.setFileName(fileName);
            logEntry.setFilePath(filePath);
            logEntry.setFileSize((long) fileData.length);
            logEntry.setStatus(RptExportLog.STATUS_COMPLETED);
            logEntry.setCompletedTime(LocalDateTime.now());
            exportLogMapper.updateById(logEntry);

            log.info("Report generated successfully: {} ({} bytes)", fileName, fileData.length);

        } catch (Exception e) {
            log.error("Report generation failed for log {}: {}", logId, e.getMessage(), e);
            logEntry.setStatus(RptExportLog.STATUS_FAILED);
            logEntry.setErrorMsg(e.getMessage());
            logEntry.setCompletedTime(LocalDateTime.now());
            exportLogMapper.updateById(logEntry);
        }
    }

    private byte[] generateExcelReport(String reportType, java.util.Map<String, Object> params) {
        return switch (reportType) {
            case ReportRequest.TYPE_USER_TRAINING -> excelReportBuilder.buildUserTrainingReport(params);
            case ReportRequest.TYPE_USER_EXAM -> excelReportBuilder.buildUserExamReport(params);
            case ReportRequest.TYPE_DEPT_TRAINING -> excelReportBuilder.buildDeptTrainingReport(params);
            case ReportRequest.TYPE_COURSE_STATS -> excelReportBuilder.buildCourseStatsReport(params);
            default -> throw new IllegalArgumentException("不支持的报表类型: " + reportType);
        };
    }

    private byte[] generatePdfReport(String reportType, java.util.Map<String, Object> params) {
        return switch (reportType) {
            case ReportRequest.TYPE_USER_TRAINING -> pdfReportBuilder.buildUserTrainingReport(params);
            case ReportRequest.TYPE_USER_EXAM -> pdfReportBuilder.buildUserExamReport(params);
            case ReportRequest.TYPE_DEPT_TRAINING -> pdfReportBuilder.buildDeptTrainingReport(params);
            case ReportRequest.TYPE_COURSE_STATS -> pdfReportBuilder.buildCourseStatsReport(params);
            default -> throw new IllegalArgumentException("不支持的报表类型: " + reportType);
        };
    }

    private String getTypeName(String reportType) {
        return switch (reportType) {
            case ReportRequest.TYPE_USER_TRAINING -> "用户培训报告";
            case ReportRequest.TYPE_USER_EXAM -> "用户考试报告";
            case ReportRequest.TYPE_DEPT_TRAINING -> "部门培训报告";
            case ReportRequest.TYPE_COURSE_STATS -> "课程统计报告";
            default -> "报告";
        };
    }

    private void ensureReportDir() {
        File dir = new File(REPORT_DIR);
        if (!dir.exists()) {
            dir.mkdirs();
        }
    }

    private void attachFileUrl(RptExportLog logEntry) {
        if (logEntry != null && RptExportLog.STATUS_COMPLETED.equals(logEntry.getStatus())) {
            logEntry.setFileUrl("/api/v1/reports/" + logEntry.getId() + "/download");
        }
    }
}
