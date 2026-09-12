package com.trainereval.ai.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trainereval.ai.client.OpenAIClient;
import com.trainereval.ai.service.AiErrorAnalysisService;
import com.trainereval.ai.service.AiPromptBuilder;
import com.trainereval.common.util.JsonExtractUtils;
import com.trainereval.exam.entity.ExExamAnswer;
import com.trainereval.exam.entity.ExExamRecord;
import com.trainereval.exam.entity.ExWrongAnswerAnalysis;
import com.trainereval.exam.mapper.ExExamAnswerMapper;
import com.trainereval.exam.mapper.ExExamRecordMapper;
import com.trainereval.exam.mapper.ExWrongAnswerAnalysisMapper;
import com.trainereval.questionbank.entity.QbQuestion;
import com.trainereval.questionbank.mapper.QbQuestionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiErrorAnalysisServiceImpl implements AiErrorAnalysisService {

    private final ExExamAnswerMapper answerMapper;
    private final ExExamRecordMapper recordMapper;
    private final QbQuestionMapper questionMapper;
    private final ExWrongAnswerAnalysisMapper wrongAnswerAnalysisMapper;
    private final OpenAIClient openAIClient;
    private final AiPromptBuilder promptBuilder;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional
    public ExWrongAnswerAnalysis analyzeWrongAnswer(Long answerId) {
        ExWrongAnswerAnalysis defaultAnalysis = new ExWrongAnswerAnalysis();
        defaultAnalysis.setAnswerId(answerId);
        defaultAnalysis.setErrorCategory("OTHER");
        defaultAnalysis.setErrorAnalysis("未能生成错误分析");
        defaultAnalysis.setRecommendation("请咨询教师获取帮助");

        ExExamAnswer answer = answerMapper.selectById(answerId);
        if (answer == null) {
            log.warn("Answer not found for analysis: {}", answerId);
            return defaultAnalysis;
        }

        QbQuestion question = questionMapper.selectById(answer.getQuestionId());
        if (question == null) {
            log.warn("Question not found for answer analysis: {}", answer.getQuestionId());
            return defaultAnalysis;
        }

        ExExamRecord record = recordMapper.selectById(answer.getRecordId());
        if (record == null) {
            log.warn("Record not found for answer analysis: {}", answer.getRecordId());
            defaultAnalysis.setUserId(-1L);
        } else {
            defaultAnalysis.setUserId(record.getUserId());
        }
        defaultAnalysis.setQuestionId(question.getId());

        String prompt;
        try {
            prompt = promptBuilder.buildErrorAnalysisPrompt(answer, question);
        } catch (Exception e) {
            log.error("Failed to build error analysis prompt: {}", e.getMessage());
            return defaultAnalysis;
        }

        String systemPrompt = "You are an education diagnostician. Analyze student errors and return JSON.";
        try {
            Map<String, Object> aiResponse = openAIClient.chatCompletion(systemPrompt, prompt);
            String content = (String) aiResponse.getOrDefault("content", "");
            if (content.isEmpty()) {
                saveAnalysis(defaultAnalysis);
                return defaultAnalysis;
            }

            String cleaned = cleanJsonResponse(content);
            Map<String, Object> parsed = objectMapper.readValue(cleaned, new TypeReference<Map<String, Object>>() {});

            ExWrongAnswerAnalysis analysis = new ExWrongAnswerAnalysis();
            analysis.setUserId(defaultAnalysis.getUserId());
            analysis.setAnswerId(answerId);
            analysis.setQuestionId(question.getId());
            analysis.setErrorCategory(parsed.getOrDefault("errorCategory", "OTHER").toString());
            analysis.setErrorAnalysis(parsed.getOrDefault("errorAnalysis", "").toString());
            analysis.setRecommendation(parsed.getOrDefault("recommendation", "").toString());

            Object suggestedTopicsObj = parsed.get("suggestedTopics");
            if (suggestedTopicsObj != null) {
                // 修复：related_material_ids 是 MySQL JSON 列，原实现写的是
                // "1, 2, 3" 这种逗号串 → MySQL 直接报 ERROR 3141 Invalid JSON text，
                // 异常被下面的 catch 吞掉并回退成"未能生成错误分析"的默认文案。
                // 后果是"AI 分析越成功（真的返回了 suggestedTopics），越会被丢弃"。
                // 现在统一序列化为合法 JSON 数组。
                List<String> topics;
                if (suggestedTopicsObj instanceof List<?> list) {
                    topics = list.stream()
                            .filter(java.util.Objects::nonNull)
                            .map(Object::toString)
                            .map(String::trim)
                            .filter(s -> !s.isEmpty())
                            .collect(Collectors.toList());
                } else {
                    topics = Arrays.stream(suggestedTopicsObj.toString().replaceAll("[\\[\\]\"]", "").split(","))
                            .map(String::trim)
                            .filter(s -> !s.isEmpty())
                            .collect(Collectors.toList());
                }
                analysis.setRelatedMaterialIds(objectMapper.writeValueAsString(topics));
            }

            saveAnalysis(analysis);
            return analysis;

        } catch (Exception e) {
            log.error("Error analysis failed for answer {}: {}", answerId, e.getMessage());
            saveAnalysis(defaultAnalysis);
            return defaultAnalysis;
        }
    }

    private void saveAnalysis(ExWrongAnswerAnalysis analysis) {
        LambdaQueryWrapper<ExWrongAnswerAnalysis> wrapper = new LambdaQueryWrapper<ExWrongAnswerAnalysis>()
                .eq(ExWrongAnswerAnalysis::getAnswerId, analysis.getAnswerId());
        ExWrongAnswerAnalysis existing = wrongAnswerAnalysisMapper.selectOne(wrapper);
        if (existing != null) {
            analysis.setId(existing.getId());
            wrongAnswerAnalysisMapper.updateById(analysis);
        } else {
            wrongAnswerAnalysisMapper.insert(analysis);
        }
    }

    private String cleanJsonResponse(String content) {
        return JsonExtractUtils.extractJsonObject(content);
    }
}
