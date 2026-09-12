package com.trainereval.training.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("tra_enrollment")
public class TraEnrollment {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private Long courseId;
    private BigDecimal progress;
    private Integer status;
    private LocalDateTime enrollTime;
    private LocalDateTime completeTime;

    @TableField(exist = false)
    private String courseName;
    @TableField(exist = false)
    private String userName;
    @TableField(exist = false)
    private String deptName;
}
