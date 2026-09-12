package com.trainereval.exam.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;
import java.util.List;

@Data
@TableName("ex_exam_record")
public class ExExamRecord {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private Long paperId;
    private Long courseId;
    private Integer attemptNumber;
    private Integer totalScore;
    private Integer objectiveScore;
    private Integer subjectiveScore;
    private String status;
    private LocalDateTime startTime;
    private LocalDateTime submitTime;
    private LocalDateTime gradeTime;
    private Long reviewBy;
    private LocalDateTime reviewTime;
    private LocalDateTime createTime;

    @TableField(exist = false)
    private String paperName;
    @TableField(exist = false)
    private String userName;
    @TableField(exist = false)
    private String deptName;
    @TableField(exist = false)
    private List<ExExamAnswer> answers;
}
