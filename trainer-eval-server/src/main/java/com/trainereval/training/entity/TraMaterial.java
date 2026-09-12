package com.trainereval.training.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("tra_material")
public class TraMaterial {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long chapterId;
    private String title;
    private String materialType;
    private String fileUrl;
    private Long fileSize;
    private String content;
    private Integer duration;
    private Integer sortOrder;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(exist = false)
    private Integer completed;

    @TableField(exist = false)
    private LocalDateTime firstAccessTime;

    @TableField(exist = false)
    private LocalDateTime completeTime;
}
