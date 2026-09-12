package com.trainereval.ai.service;

import com.trainereval.exam.entity.ExExamAnswer;
import com.trainereval.questionbank.entity.QbQuestion;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

public interface AiGradingService {

    GradeResult grade(ExExamAnswer answer, QbQuestion question);

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    class GradeResult {
        private int score;
        private String reasoning;
        private String feedback;
        private Map<String, Integer> dimensionScores;
        /**
         * 本次批改是否失败（调用异常 / 空响应 / JSON 解析失败）。
         * 新增该字段的原因：原实现失败时只返回 score=0，调用方无法区分
         * "模型真的给了 0 分" 与 "AI 调用挂了"，于是把失败当成合法成绩写库，
         * 导致界面显示"AI 已评分"而学生被判 0 分。
         */
        private boolean failed;
    }
}
