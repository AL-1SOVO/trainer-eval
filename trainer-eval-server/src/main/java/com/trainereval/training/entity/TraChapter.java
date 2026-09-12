package com.trainereval.training.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.util.List;
import java.time.LocalDateTime;

@Data
@TableName("tra_chapter")
public class TraChapter {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long courseId;
    private String title;
    private String description;
    private Integer sortOrder;
    private Integer materialCount;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(exist = false)
    private List<TraMaterial> materials;
}
