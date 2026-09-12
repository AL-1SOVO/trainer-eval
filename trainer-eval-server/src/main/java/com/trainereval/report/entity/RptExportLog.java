package com.trainereval.report.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.trainereval.common.base.BaseEntity;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("rpt_export_log")
public class RptExportLog extends BaseEntity {

    private Long userId;
    private String reportType;
    private String reportFormat;
    private String fileName;
    @TableField("file_url")
    private String filePath;
    private Long fileSize;
    private String params;
    private String status;
    @TableField("complete_time")
    private LocalDateTime completedTime;
    private String errorMsg;

    @TableField(exist = false)
    private String fileUrl;

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_GENERATING = "GENERATING";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_FAILED = "FAILED";
}
