package com.trainereval.report.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trainereval.common.result.PageResult;
import com.trainereval.common.util.SecurityUtils;
import com.trainereval.report.dto.ReportDataset;
import com.trainereval.report.dto.ReportRequest;
import com.trainereval.report.entity.RptExportLog;
import com.trainereval.report.mapper.RptExportLogMapper;
import com.trainereval.report.service.ExcelReportBuilder;
import com.trainereval.report.service.PdfReportBuilder;
import com.trainereval.report.service.ReportDatasetService;
import com.trainereval.report.service.ReportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
    private final ReportDatasetService datasetService;
    private final ObjectMapper objectMapper;

    private static final String REPORT_DIR = System.getProperty("user.home") + "/trainer-eval/reports";

    @Override
    @Transactional
    public RptExportLog generateReport(ReportRequest request, Long userId, java.util.Set<String> roles) {
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

        // 调用者上下文必须在**请求线程**里取好再传下去：doGenerate 一旦真异步执行，
        // SecurityUtils 的 ThreadLocal 就是空的，再想拿 userId/角色就晚了。
        doGenerate(logEntry.getId(), userId, roles);

        return exportLogMapper.selectById(logEntry.getId());
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

    /**
     * 修复：getExportLog / downloadFile / deleteReport 原先只按主键 selectById，
     * 从不把 logEntry.userId 与当前登录者比对 —— 任意登录用户（含 ROLE_EMPLOYEE）
     * 都能按 id 遍历下载或删除他人的报表文件。对照 getHistory 里的 eq(userId)
     * 可见这属于漏写。现在统一在此强制属主校验。
     */
    private RptExportLog requireOwnLog(Long logId) {
        RptExportLog logEntry = exportLogMapper.selectById(logId);
        if (logEntry == null) {
            throw new RuntimeException("导出记录不存在");
        }
        Long me = SecurityUtils.getCurrentUserId();
        if (me == null || !me.equals(logEntry.getUserId())) {
            throw new RuntimeException("无权访问他人的报表");
        }
        return logEntry;
    }

    @Override
    public RptExportLog getExportLog(Long logId) {
        return requireOwnLog(logId);
    }

    @Override
    public byte[] downloadFile(Long logId) {
        RptExportLog logEntry = requireOwnLog(logId);
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
        RptExportLog logEntry = requireOwnLog(logId);
        if (logEntry.getFilePath() != null) {
            try {
                Files.deleteIfExists(Paths.get(logEntry.getFilePath()));
            } catch (IOException e) {
                log.warn("Failed to delete report file: {}", e.getMessage());
            }
        }
        exportLogMapper.deleteById(logId);
    }

    /**
     * 生成报表文件并回写状态。
     *
     * <h3>关于 @Async（一个"看起来异步、其实同步"的坑）</h3>
     * 原来这里标着 {@code @Async}，但 {@link #generateReport} 是用 {@code this.doGenerate(...)} 调它的 ——
     * <b>自调用不经过 Spring 代理，@Async 完全不生效</b>，实际一直是同步执行。
     * 更麻烦的是：如果哪天有人为了"修好异步"改成注入自身代理，就会踩到第二个坑 ——
     * generateReport 带 {@code @Transactional}，异步线程可能在插入的日志行**尚未提交**时就去读它，
     * 读到 null 然后静默 return。
     * <p>
     * 所以这里不再假装异步：显式同步执行，并把调用者上下文（userId + roles）作为参数传进来。
     * 报表数据量在本项目的规模下是毫秒级，同步换来的是"状态一定准确、权限一定生效"。
     * 将来数据量真的上来，正确的做法是投递到队列（项目已有 Redis），而不是给一个自调用加注解。
     */
    public void doGenerate(Long logId, Long requesterId, java.util.Set<String> roles) {
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

            // 先装配数据，再交给渲染器。数据为空/越权都在这一步暴露，避免"生成了一个空文件还说成功"。
            ReportDataset dataset = datasetService.load(logEntry.getReportType(), params,
                    new ReportDatasetService.Requester(requesterId, roles));

            ensureReportDir();
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
            String typeName = getTypeName(logEntry.getReportType());
            String ext = ReportRequest.FORMAT_PDF.equals(logEntry.getReportFormat()) ? ".pdf" : ".xlsx";
            String fileName = typeName + "_" + timestamp + ext;
            String filePath = REPORT_DIR + "/" + fileName;

            byte[] fileData = ReportRequest.FORMAT_PDF.equals(logEntry.getReportFormat())
                    ? pdfReportBuilder.build(dataset)
                    : excelReportBuilder.build(dataset);

            if (fileData == null || fileData.length == 0) {
                // 渲染器返回空内容视为失败：写入 0 字节文件却标记"已完成"，
                // 用户下载到空文件、日志里却一切正常，是最难排查的一类问题。
                throw new IllegalStateException("渲染器返回了空文件");
            }

            try (FileOutputStream fos = new FileOutputStream(filePath)) {
                fos.write(fileData);
            }

            logEntry.setFileName(fileName);
            logEntry.setFilePath(filePath);
            logEntry.setFileSize((long) fileData.length);
            logEntry.setStatus(RptExportLog.STATUS_COMPLETED);
            logEntry.setErrorMsg(null);
            logEntry.setCompletedTime(LocalDateTime.now());
            exportLogMapper.updateById(logEntry);

            log.info("Report generated successfully: {} ({} bytes, {} rows)",
                    fileName, fileData.length, dataset.rowCount());

        } catch (Exception e) {
            log.error("Report generation failed for log {}: {}", logId, e.getMessage(), e);
            logEntry.setStatus(RptExportLog.STATUS_FAILED);
            logEntry.setErrorMsg(rootMessage(e));
            logEntry.setCompletedTime(LocalDateTime.now());
            exportLogMapper.updateById(logEntry);
        }
    }

    /** 取最内层异常的消息：越权/无字体这类原因都包在 IllegalStateException 里面。 */
    private String rootMessage(Throwable e) {
        Throwable cursor = e;
        while (cursor.getCause() != null && cursor.getCause() != cursor) {
            cursor = cursor.getCause();
        }
        String msg = cursor.getMessage();
        return msg == null || msg.isBlank() ? e.getClass().getSimpleName() : msg;
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
