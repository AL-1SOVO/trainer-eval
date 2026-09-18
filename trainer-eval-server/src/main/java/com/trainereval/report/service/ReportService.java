package com.trainereval.report.service;

import com.trainereval.common.result.PageResult;
import com.trainereval.report.dto.ReportRequest;
import com.trainereval.report.entity.RptExportLog;

import java.util.Set;

public interface ReportService {

    /**
     * 生成报表，返回**最终状态**的导出记录。
     * <p>
     * 原来返回 logId 而控制器把状态写死成 PENDING —— 但生成其实是同步完成的
     * （原因见实现类里对 {@code @Async} 自调用的说明）。结果是接口永远回报"待生成"，
     * 前端若不重新拉一次历史就永远看不到"已完成"。返回记录本身，让调用方拿到真实状态与失败原因。
     *
     * @param roles 调用者角色，用于限定报表的数据范围（部门/课程级报表仅管理员可生成）
     */
    RptExportLog generateReport(ReportRequest request, Long userId, Set<String> roles);

    PageResult<RptExportLog> getHistory(Long userId, int page, int size);

    RptExportLog getExportLog(Long logId);

    byte[] downloadFile(Long logId);

    void deleteReport(Long logId);
}
