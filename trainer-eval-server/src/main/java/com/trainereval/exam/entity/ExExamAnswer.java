package com.trainereval.exam.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("ex_exam_answer")
public class ExExamAnswer {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long recordId;
    private Long questionId;
    private String questionType;
    private String questionContent;
    private String questionOptions;
    private BigDecimal questionScore;
    private String userAnswer;
    private Integer isCorrect;
    private BigDecimal score;
    private Integer aiGraded;
    private BigDecimal aiScore;
    private String aiFeedback;
    private BigDecimal manualScore;
    private Long graderId;
    private LocalDateTime gradeTime;

    @TableField(exist = false)
    private String errorAnalysis;
    @TableField(exist = false)
    private String referenceAnswer;
}
