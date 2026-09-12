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
    }
}
