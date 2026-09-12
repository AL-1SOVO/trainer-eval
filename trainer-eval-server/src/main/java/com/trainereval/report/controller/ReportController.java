package com.trainereval.report.controller;

import com.trainereval.common.result.PageResult;
import com.trainereval.common.result.R;
import com.trainereval.common.util.SecurityUtils;
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

@RestController
@RequestMapping("/api/v1/reports")
@RequiredArgsConstructor
public class ReportController {

    private final ReportService reportService;

    @PostMapping("/generate")
    @PreAuthorize("hasAnyRole('EMPLOYEE', 'TRAINER', 'MANAGER')")
    public R<ReportResponse> generateReport(@Valid @RequestBody ReportRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        Long logId = reportService.generateReport(request, userId);

        ReportResponse response = ReportResponse.builder()
                .logId(logId)
                .status(RptExportLog.STATUS_PENDING)
                .build();

        return R.ok(response);
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
