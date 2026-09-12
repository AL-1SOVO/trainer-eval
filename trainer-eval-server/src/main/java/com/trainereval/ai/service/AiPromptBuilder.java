package com.trainereval.ai.service;

import com.trainereval.ai.entity.AiGradingRule;
import com.trainereval.exam.entity.ExExamAnswer;
import com.trainereval.questionbank.entity.QbQuestion;

public interface AiPromptBuilder {

    String buildGradingPrompt(ExExamAnswer answer, QbQuestion question, AiGradingRule rule);

    String buildErrorAnalysisPrompt(ExExamAnswer answer, QbQuestion question);
}
