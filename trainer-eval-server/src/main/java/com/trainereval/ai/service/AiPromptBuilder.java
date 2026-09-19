package com.trainereval.ai.service;

import com.trainereval.ai.entity.AiGradingRule;
import com.trainereval.exam.entity.ExExamAnswer;
import com.trainereval.questionbank.entity.QbQuestion;

public interface AiPromptBuilder {

    String buildGradingPrompt(ExExamAnswer answer, QbQuestion question, AiGradingRule rule);

    String buildErrorAnalysisPrompt(ExExamAnswer answer, QbQuestion question);

    /**
     * 带检索上下文的错因分析提示词。
     * <p>
     * 原来的 2 参版本只把题目与答案交给模型，模型给出的"学习建议"**无从依据真实资料** ——
     * 只能凭常识编一个看起来合理的建议。这个重载把课程知识库检索到的片段注入模板，
     * 让建议指向本课程真实存在的资料（且不存在的资料不许编）。
     *
     * @param retrievalContext 已格式化的检索片段；为空表示未检索到
     */
    String buildErrorAnalysisPrompt(ExExamAnswer answer, QbQuestion question, String retrievalContext);
}
