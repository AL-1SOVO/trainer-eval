package com.trainereval.questionbank.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.trainereval.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.util.List;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("qb_exam_paper")
public class QbExamPaper extends BaseEntity {
    private Long courseId;
    private String paperName;
    private String description;
    private Integer totalScore;
    private Integer passScore;
    private Integer duration;
    private Integer shuffleQuestions;
    private Integer shuffleOptions;
    private Integer allowRetake;
    private Integer maxAttempts;
    private Integer status;
    private Long creatorId;

    @TableField(exist = false)
    private String courseName;
    @TableField(exist = false)
    private List<QbQuestion> questions;
    @TableField(exist = false)
    private List<Integer> questionScores;
}
