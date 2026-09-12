package com.trainereval.ai.service;

import com.trainereval.exam.entity.ExWrongAnswerAnalysis;

public interface AiErrorAnalysisService {

    ExWrongAnswerAnalysis analyzeWrongAnswer(Long answerId);
}
