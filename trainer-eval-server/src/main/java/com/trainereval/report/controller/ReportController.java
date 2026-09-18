package com.trainereval.report.controller;

import com.trainereval.common.result.PageResult;
import com.trainereval.common.result.R;
import com.trainereval.common.util.SecurityUtils;
import com.trainereval.security.LoginUser;
import com.trainereval.report.dto.ReportRequest;
import com.trainereval.report.dto.ReportResponse;
import com.trainereval.report.entity.RptExportLog;
import com.trainereval.report.service.ReportService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

@RestController
@RequestMapping("/api/v1/reports")
@RequiredArgsConstructor
public class ReportController {

    private final ReportService reportService;

    @PostMapping("/generate")
    @PreAuthorize("hasAnyRole('EMPLOYEE', 'TRAINER', 'MANAGER')")
    public R<ReportResponse> generateReport(@Valid @RequestBody ReportRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        // 角色必须在请求线程里取：报表的数据范围取决于角色（部门/课程级报表仅管理员可生成）。
        // 依赖服务层再去读 ThreadLocal 是很脆的写法 —— 一旦改成异步执行就会静默退化成"没有角色"。
        Set<String> roles = currentRoles();
        RptExportLog logEntry = reportService.generateReport(request, userId, roles);

        // 状态取自数据库而不是写死 PENDING：生成是同步完成的（见 ReportServiceImpl 的说明），
        // 写死 PENDING 会让前端必须再拉一次历史才能看到"已完成"，失败原因也传不出去。
        if (RptExportLog.STATUS_FAILED.equals(logEntry.getStatus())) {
            return R.error(logEntry.getErrorMsg() == null ? "报表生成失败" : logEntry.getErrorMsg());
        }
        return R.ok(ReportResponse.builder()
                .logId(logEntry.getId())
                .status(logEntry.getStatus())
                .fileUrl(logEntry.getFileUrl())
                .build());
    }

    private Set<String> currentRoles() {
        try {
            LoginUser user = SecurityUtils.getCurrentUser();
            return user == null || user.getRoles() == null ? Set.of() : new HashSet<>(user.getRoles());
        } catch (Exception e) {
            return Set.of();
        }
    }

    @GetMapping("/history")
    @PreAuthorize("hasAnyRole('EMPLOYEE', 'TRAINER', 'MANAGER')")
    public R<PageResult<RptExportLog>> getHistory(@RequestParam(defaultValue = "1") int page,
                                                   @RequestParam(defaultValue = "10") int size) {
        Long userId = SecurityUtils.getCurrentUserId();
        PageResult<RptExportLog> result = reportService.getHistory(userId, page, size);
        return R.ok(result);
    }

    @GetMapping("/{id}/download")
    @PreAuthorize("hasAnyRole('EMPLOYEE', 'TRAINER', 'MANAGER')")
    public ResponseEntity<byte[]> downloadReport(@PathVariable Long id) {
        RptExportLog logEntry = reportService.getExportLog(id);
        byte[] fileData = reportService.downloadFile(id);

        String fileName = logEntry.getFileName() != null ? logEntry.getFileName() : "report";
        String encodedFileName = URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");

        MediaType mediaType;
        if (fileName.endsWith(".pdf")) {
            mediaType = MediaType.APPLICATION_PDF;
        } else {
            mediaType = MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        }

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encodedFileName)
                .contentType(mediaType)
                .body(fileData);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('EMPLOYEE', 'TRAINER', 'MANAGER')")
    public R<Void> deleteReport(@PathVariable Long id) {
        reportService.deleteReport(id);
        return R.ok();
    }
}
