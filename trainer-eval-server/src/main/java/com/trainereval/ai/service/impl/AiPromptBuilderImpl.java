package com.trainereval.ai.service.impl;

import com.trainereval.ai.entity.AiGradingRule;
import com.trainereval.ai.service.AiPromptBuilder;
import com.trainereval.exam.entity.ExExamAnswer;
import com.trainereval.questionbank.entity.QbQuestion;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 从 classpath:ai-prompts/ 加载提示词模板并填充占位符（{{var}}）。
 * 模板文件：short-answer-grading.txt、essay-grading.txt、error-analysis.txt
 */
@Component
public class AiPromptBuilderImpl implements AiPromptBuilder {

    private static final String SYSTEM_PROMPT = "You are an expert education evaluator.";

    private final Map<String, String> templateCache = new ConcurrentHashMap<>();

    @Override
    public String buildGradingPrompt(ExExamAnswer answer, QbQuestion question, AiGradingRule rule) {
        String typeKey = "SHORT_ANSWER".equals(question.getQuestionType()) ? "short-answer" : "essay";
        String template = loadTemplate(typeKey + "-grading");
        String scoringCriteria = notBlank(question.getScoringCriteria())
                ? question.getScoringCriteria()
                : "根据答案准确性、完整性、条理性进行综合评分。";

        return fill(template, Map.ofEntries(
                Map.entry("gradingRules", safe(rule != null ? rule.getPromptTemplate() : null)),
                Map.entry("questionContent", safe(question.getContent())),
                Map.entry("referenceAnswer", safe(question.getReferenceAnswer())),
                Map.entry("scoringCriteria", safe(scoringCriteria)),
                Map.entry("studentAnswer", safe(answer.getUserAnswer())),
                Map.entry("maxScore", String.valueOf(question.getQuestionScore().intValue()))
        ));
    }

    @Override
    public String buildErrorAnalysisPrompt(ExExamAnswer answer, QbQuestion question) {
        // 不带检索上下文时，明确告诉模型"没有资料可引用" ——
        // 否则模板里的检索段落会变成空字符串，模型可能自行编造资料标题。
        return buildErrorAnalysisPrompt(answer, question, "（本次未检索到相关课程资料）");
    }

    @Override
    public String buildErrorAnalysisPrompt(ExExamAnswer answer, QbQuestion question, String retrievalContext) {
        String template = loadTemplate("error-analysis");
        String context = notBlank(retrievalContext) ? retrievalContext : "（本次未检索到相关课程资料）";
        return fill(template, Map.of(
                "questionContent", safe(question.getContent()),
                "studentAnswer", safe(answer.getUserAnswer()),
                "correctAnswer", safe(question.getReferenceAnswer()),
                "questionAnalysis", safe(question.getAnalysis()),
                "retrievalContext", context
        ));
    }

    public String getSystemPrompt() {
        return SYSTEM_PROMPT;
    }

    private String loadTemplate(String name) {
        return templateCache.computeIfAbsent(name, key -> {
            try (InputStream in = new ClassPathResource("ai-prompts/" + key + ".txt").getInputStream()) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new IllegalStateException("未找到提示词模板: ai-prompts/" + key + ".txt", e);
            }
        });
    }

    private String fill(String template, Map<String, String> vars) {
        String result = template;
        for (Map.Entry<String, String> entry : vars.entrySet()) {
            result = result.replace("{{" + entry.getKey() + "}}", entry.getValue());
        }
        return result;
    }

    private String safe(String value) {
        return value == null || value.isBlank() ? "无" : value.trim();
    }

    private boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
