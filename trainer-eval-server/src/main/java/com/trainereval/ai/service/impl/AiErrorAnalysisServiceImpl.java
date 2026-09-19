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
import com.trainereval.rag.dto.RetrievalHit;
import com.trainereval.rag.service.RagRetrievalFacade;
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
    private final RagRetrievalFacade retrievalFacade;

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

        List<RetrievalHit> retrievedHits = List.of();
        String prompt;
        try {
            // ---------- 先检索，再让模型基于真实资料给建议 ----------
            // 原来这里直接把题目丢给模型，模型给出的"学习建议"无从依据本课程资料，
            // 只能凭常识编 —— 学生照着建议去课程里找，往往找不到对应内容。
            // 现在先把课程知识库里的相关片段检索出来注入提示词，并把命中的真实资料 ID 落库，
            // 前端据此渲染可点击的资料链接。
            List<RetrievalHit> hits = retrieveContext(question, record);
            prompt = promptBuilder.buildErrorAnalysisPrompt(answer, question, buildRetrievalContext(hits));
            retrievedHits = hits;
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

            // related_material_ids 存**真实的资料 ID**（数字数组）。
            // 原实现把模型返回的 suggestedTopics（主题词，如"数据加密"）塞进这个字段 ——
            // 字段名与语义都是"资料 ID"，存文本属于类型错位，前端拿到后无法跳转，
            // 只能把原始 JSON 当文本显示给用户看。主题词本身没有丢：它已经体现在
            // recommendation 的文字里，落库的是可点击的资料引用。
            if (!retrievedHits.isEmpty()) {
                List<Long> materialIds = retrievedHits.stream()
                        .map(RetrievalHit::materialId)
                        .filter(java.util.Objects::nonNull)
                        .distinct()
                        .collect(Collectors.toList());
                if (!materialIds.isEmpty()) {
                    analysis.setRelatedMaterialIds(objectMapper.writeValueAsString(materialIds));
                }
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

    /**
     * 检索与本题相关的课程资料。
     * <p>
     * 用**学生本人的 userId** 调检索门面，因此权限裁剪照常生效 ——
     * 分析结果里不会出现该学生看不到的课程资料，与「知识搜索」「AI 学习助手」同一套口径。
     * 检索失败不影响分析本身：拿不到资料就退化为不带引用的建议，而不是让整个分析失败。
     */
    private List<RetrievalHit> retrieveContext(QbQuestion question, ExExamRecord record) {
        try {
            StringBuilder query = new StringBuilder();
            if (question.getContent() != null) {
                query.append(question.getContent()).append(' ');
            }
            if (question.getTags() != null && !question.getTags().isBlank()) {
                query.append(question.getTags());
            }
            if (query.isEmpty()) {
                return List.of();
            }
            Long userId = record == null ? null : record.getUserId();
            RagRetrievalFacade.RagResult result = retrievalFacade.search(
                    userId, "error-analysis", query.toString().trim(), question.getCourseId(), 4);
            return result.hits();
        } catch (Exception e) {
            log.warn("错因分析的资料检索失败，将退化为无引用建议：{}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 把检索命中格式化成提示词里的一段。
     *
     * <h3>为什么把相关度与匹配通道也写进去</h3>
     * 实测（answer 23，题目问 VPN/公共 WiFi，语料里没有该主题）发现：
     * 检索仍会返回几条**弱相关**的资料，模型于是"尽力建立联系"，
     * 把 VPN 硬扯到《数据保护三大原则详解》的"安全性原则"上。
     * 引用的文字确实来自原文（没有编造），但对这道题几乎没有帮助 ——
     * 这属于"看起来合理"的建议里最难被察觉的一类问题。
     * <p>
     * 检索层现在无法用统一阈值挡掉它：本地混合检索的 RRF 分数（约 0.01~0.07）
     * 与 MimirQ 的语义分数（约 0.6~0.8）尺度完全不同，定一个阈值必然误伤一边
     * （该边界已记录在《RAG与Agent优化方案.md》）。所以把**可判断的信息交给模型**，
     * 由它结合规则决定"该引用还是该说明暂无对应资料"，而不是拍一个数字。
     */
    private String buildRetrievalContext(List<RetrievalHit> hits) {
        if (hits == null || hits.isEmpty()) {
            return "（本次未检索到相关课程资料：不要编造资料标题或章节，直接说明该知识点暂无上传资料）";
        }
        StringBuilder sb = new StringBuilder();
        int index = 0;
        for (RetrievalHit hit : hits) {
            index++;
            sb.append(index).append(". 《").append(hit.title() == null ? "未命名资料" : hit.title()).append('》');
            if (hit.courseName() != null && !hit.courseName().isBlank()) {
                sb.append("（课程：").append(hit.courseName()).append('）');
            }
            if (hit.chapterTitle() != null && !hit.chapterTitle().isBlank()) {
                sb.append("（章节：").append(hit.chapterTitle()).append('）');
            }
            sb.append("［相关度 ").append(String.format("%.2f", hit.score()));
            sb.append("，命中方式：").append(describeChannels(hit.channels())).append("］");
            sb.append("\n   片段：").append(hit.snippet() == null ? "" : hit.snippet()).append('\n');
        }
        return sb.toString();
    }

    /**
     * 把内部通道标记翻成人话。
     * "仅词面匹配"是个重要信号：说明这条是靠字面重叠捞到的，未必真的在讲学生错的那个知识点。
     */
    private String describeChannels(String channels) {
        if (channels == null || channels.isBlank()) {
            return "知识库语义检索";
        }
        boolean ft = channels.contains("FT");
        boolean like = channels.contains("LIKE");
        if (ft && like) {
            return "语义检索 + 词面匹配";
        }
        if (ft) {
            return "仅词面匹配（全文）";
        }
        if (like) {
            return "仅词面匹配（子串）";
        }
        return "知识库语义检索";
    }

    private String cleanJsonResponse(String content) {
        return JsonExtractUtils.extractJsonObject(content);
    }
}
