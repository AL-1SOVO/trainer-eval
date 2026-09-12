package com.trainereval.training.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("tra_learning_progress")
public class TraLearningProgress {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private Long materialId;
    private Long enrollmentId;
    private Integer isCompleted;
    private LocalDateTime firstAccessTime;
    private LocalDateTime completeTime;
}
