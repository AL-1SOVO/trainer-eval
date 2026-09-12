package com.trainereval.exam.service;

import com.trainereval.common.result.PageResult;
import com.trainereval.exam.entity.ExWrongAnswerAnalysis;
import java.util.Map;

public interface ExWrongAnswerAnalysisService {
    ExWrongAnswerAnalysis triggerAiAnalysis(Long answerId);
    ExWrongAnswerAnalysis getByAnswerId(Long answerId);
    PageResult<Map<String, Object>> getUserAnalyses(Long userId, int page, int size, Long courseId, String questionType);
    Map<String, Object> getUserStats(Long userId);
}
