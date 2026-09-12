package com.trainereval.training.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.trainereval.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.util.List;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("tra_course")
public class TraCourse extends BaseEntity {
    private String courseName;
    private String description;
    private String coverImage;
    private Long deptId;
    private Long trainerId;
    private Integer totalChapters;
    private Integer enrollCount;
    private Integer status;

    @TableField(exist = false)
    private String trainerName;
    @TableField(exist = false)
    private String deptName;
    @TableField(exist = false)
    private List<TraChapter> chapters;
    @TableField(exist = false)
    private Long enrollmentId;
    @TableField(exist = false)
    private BigDecimal progress;
    @TableField(exist = false)
    private Integer enrollmentStatus;
}
