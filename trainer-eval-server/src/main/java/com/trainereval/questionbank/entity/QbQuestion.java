package com.trainereval.questionbank.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.trainereval.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.math.BigDecimal;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("qb_question")
public class QbQuestion extends BaseEntity {
    private Long courseId;
    private String questionType;
    private String content;
    private String options;
    private String referenceAnswer;
    private String scoringCriteria;
    private Integer defaultScore;
    private Integer difficulty;
    private String analysis;
    private String tags;
    private Long creatorId;
    private Integer status;

    // Convenience getters for agent-generated code compatibility
    public String getQuestionContent() { return content; }
    public void setQuestionContent(String v) { this.content = v; }
    public String getQuestionOptions() { return options; }
    public void setQuestionOptions(String v) { this.options = v; }
    public BigDecimal getQuestionScore() { return defaultScore != null ? BigDecimal.valueOf(defaultScore) : BigDecimal.ZERO; }
    public void setQuestionScore(Integer v) { this.defaultScore = v; }
}
