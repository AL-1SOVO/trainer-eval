package com.trainereval.ai.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trainereval.ai.client.OpenAIClient;
import com.trainereval.ai.entity.AiGradingLog;
import com.trainereval.ai.entity.AiGradingRule;
import com.trainereval.ai.mapper.AiGradingLogMapper;
import com.trainereval.ai.mapper.AiGradingRuleMapper;
import com.trainereval.ai.service.AiPromptBuilder;
import com.trainereval.ai.service.AiGradingService;
import com.trainereval.common.util.JsonExtractUtils;
import com.trainereval.exam.entity.ExExamAnswer;
import com.trainereval.exam.entity.ExExamRecord;
import com.trainereval.exam.mapper.ExExamRecordMapper;
import com.trainereval.questionbank.entity.QbQuestion;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiGradingServiceImpl implements AiGradingService {

    private final AiGradingRuleMapper ruleMapper;
    private final AiGradingLogMapper logMapper;
    private final ExExamRecordMapper examRecordMapper;
    private final OpenAIClient openAIClient;
    private final AiPromptBuilder promptBuilder;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional
    public GradeResult grade(ExExamAnswer answer, QbQuestion question) {
        GradeResult defaultResult = new GradeResult();
        defaultResult.setScore(0);
        defaultResult.setReasoning("AI grading failed");
        defaultResult.setFeedback("系统评分异常，请联系管理员");

        ExExamRecord record = examRecordMapper.selectById(answer.getRecordId());
        if (record == null) {
            return defaultResult;
        }

        AiGradingRule rule = ruleMapper.selectOne(
                new LambdaQueryWrapper<AiGradingRule>()
                        .eq(AiGradingRule::getCourseId, record.getCourseId())
                        .eq(AiGradingRule::getQuestionType, question.getQuestionType())
                        .in(AiGradingRule::getStatus, List.of("ACTIVE", "1"))
                        .last("LIMIT 1")
        );

        if (rule == null) {
            String typeKey = "SHORT_ANSWER".equals(question.getQuestionType()) ? "short-answer" : "essay";
            rule = new AiGradingRule();
            rule.setCourseId(record.getCourseId());
            rule.setQuestionType(question.getQuestionType());
            rule.setModel("deepseek-v4-flash");
            rule.setTemperature(0.3);
            rule.setMaxTokens(4096);
            rule.setPromptTemplate(null);
        }

        String systemPrompt;
        String userPrompt;
        try {
            String combined = promptBuilder.buildGradingPrompt(answer, question, rule);
            String[] parts = splitSystemAndUser(combined);
            systemPrompt = parts[0];
            userPrompt = parts[1];
        } catch (Exception e) {
            log.error("Failed to build grading prompt: {}", e.getMessage(), e);
            return defaultResult;
        }

        long startTime = System.currentTimeMillis();

        Map<String, Object> aiResponse;
        try {
            aiResponse = openAIClient.chatCompletion(systemPrompt, userPrompt,
                    rule.getModel(), rule.getTemperature(), rule.getMaxTokens());
        } catch (Exception e) {
            log.error("OpenAI call failed: {}", e.getMessage(), e);
            saveLog(answer, record, rule, systemPrompt, userPrompt, null, startTime, null, AiGradingLog.STATUS_FAILED, e.getMessage());
            return defaultResult;
        }

        long latencyMs = System.currentTimeMillis() - startTime;

        String responseContent = (String) aiResponse.getOrDefault("content", "");
        if (responseContent.isEmpty()) {
            saveLog(answer, record, rule, systemPrompt, userPrompt, responseContent, startTime, null, AiGradingLog.STATUS_FAILED, "Empty AI response");
            return defaultResult;
        }

        try {
            String cleanedJson = cleanJsonResponse(responseContent);
            Map<String, Object> parsed = objectMapper.readValue(cleanedJson, new TypeReference<Map<String, Object>>() {});

            GradeResult gradeResult = new GradeResult();

            Object scoreObj = parsed.get("score");
            int score = scoreObj instanceof Number ? ((Number) scoreObj).intValue() : 0;
            score = Math.max(0, Math.min(score, question.getQuestionScore().intValue()));
            gradeResult.setScore(score);

            gradeResult.setReasoning(parsed.getOrDefault("reasoning", "").toString());
            gradeResult.setFeedback(parsed.getOrDefault("feedback", "").toString());

            @SuppressWarnings("unchecked")
            Map<String, Object> dimScores = (Map<String, Object>) parsed.get("dimensionScores");
            if (dimScores != null) {
                Map<String, Integer> intScores = new HashMap<>();
                for (Map.Entry<String, Object> entry : dimScores.entrySet()) {
                    intScores.put(entry.getKey(), entry.getValue() instanceof Number ?
                            ((Number) entry.getValue()).intValue() : 0);
                }
                gradeResult.setDimensionScores(intScores);
            }

            saveLog(answer, record, rule, systemPrompt, userPrompt, responseContent, startTime, aiResponse, AiGradingLog.STATUS_SUCCESS, null);

            return gradeResult;

        } catch (Exception e) {
            log.error("Failed to parse AI grading response: {}", e.getMessage(), e);
            saveLog(answer, record, rule, systemPrompt, userPrompt, responseContent, startTime, null, AiGradingLog.STATUS_PARSE_ERROR, e.getMessage());
            return defaultResult;
        }
    }

    private String cleanJsonResponse(String content) {
        return JsonExtractUtils.extractJsonObject(content);
    }

    private void saveLog(ExExamAnswer answer, ExExamRecord record, AiGradingRule rule,
                          String systemPrompt, String userPrompt, String responseRaw,
                          long startTime, Map<String, Object> aiResponse, String status, String errorMsg) {
        try {
            AiGradingLog logEntry = new AiGradingLog();
            logEntry.setRecordId(record.getId());
            logEntry.setAnswerId(answer.getId());
            logEntry.setRuleId(rule.getId() != null && rule.getId() > 0 ? rule.getId() : null);
            logEntry.setModel(rule.getModel());
            logEntry.setPromptSystem(systemPrompt);
            logEntry.setPromptUser(userPrompt);
            logEntry.setResponseRaw(responseRaw);
            logEntry.setLatencyMs(System.currentTimeMillis() - startTime);
            logEntry.setStatus(status);
            logEntry.setErrorMsg(errorMsg);

            if (responseRaw != null) {
                try {
                    Map<String, Object> parsed = objectMapper.readValue(cleanJsonResponse(responseRaw), new TypeReference<Map<String, Object>>() {});
                    Object scoreObj = parsed.get("score");
                    if (scoreObj instanceof Number) {
                        logEntry.setScore(((Number) scoreObj).intValue());
                    }
                    logEntry.setReasoning(parsed.getOrDefault("reasoning", "").toString());
                } catch (Exception ignored) {
                }
            }

            Integer promptTokens = 0;
            Integer completionTokens = 0;
            Integer totalTokens = 0;

            if (aiResponse != null) {
                Object pt = aiResponse.get("promptTokens");
                Object ct = aiResponse.get("completionTokens");
                Object tt = aiResponse.get("totalTokens");

                promptTokens = pt instanceof Number ? ((Number) pt).intValue() : 0;
                completionTokens = ct instanceof Number ? ((Number) ct).intValue() : 0;
                totalTokens = tt instanceof Number ? ((Number) tt).intValue() : promptTokens + completionTokens;
            }

            logEntry.setTokensPrompt(promptTokens);
            logEntry.setTokensCompletion(completionTokens);
            logEntry.setTokensTotal(totalTokens);

            BigDecimal costPerPromptToken = new BigDecimal("0.000005");
            BigDecimal costPerCompletionToken = new BigDecimal("0.000015");
            BigDecimal cost = BigDecimal.valueOf(promptTokens).multiply(costPerPromptToken)
                    .add(BigDecimal.valueOf(completionTokens).multiply(costPerCompletionToken))
                    .setScale(6, RoundingMode.HALF_UP);
            logEntry.setCost(cost);

            logMapper.insert(logEntry);
        } catch (Exception e) {
            log.error("Failed to save grading log: {}", e.getMessage());
        }
    }

    private String[] splitSystemAndUser(String combined) {
        String systemPrompt = "You are an expert education evaluator.";
        String userPrompt = combined;

        if (combined.contains("##SYSTEM##") && combined.contains("##USER##")) {
            int sysStart = combined.indexOf("##SYSTEM##") + 10;
            int sysEnd = combined.indexOf("##USER##");
            if (sysEnd > sysStart) {
                systemPrompt = combined.substring(sysStart, sysEnd).trim();
                userPrompt = combined.substring(sysEnd + 8).trim();
            }
        }

        return new String[]{systemPrompt, userPrompt};
    }
}
