package com.trainereval.agent.tools;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trainereval.exam.entity.ExExamRecord;
import com.trainereval.exam.entity.ExWrongAnswerAnalysis;
import com.trainereval.exam.mapper.ExExamRecordMapper;
import com.trainereval.exam.mapper.ExWrongAnswerAnalysisMapper;
import com.trainereval.questionbank.entity.QbQuestion;
import com.trainereval.questionbank.mapper.QbQuestionMapper;
import com.trainereval.training.entity.TraChapter;
import com.trainereval.training.entity.TraCourse;
import com.trainereval.training.entity.TraMaterial;
import com.trainereval.training.mapper.TraChapterMapper;
import com.trainereval.training.mapper.TraCourseMapper;
import com.trainereval.training.mapper.TraMaterialMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 学习辅导 Agent 的业务工具集。每个实例绑定一个请求的 userId，
 * 所有查询强制按该用户过滤，保证 Agent 无法越权读取他人数据。
 */
@Slf4j
public class TutorTools {

    private final Long userId;
    private final ObjectMapper objectMapper;
    private final ExExamRecordMapper examRecordMapper;
    private final ExWrongAnswerAnalysisMapper wrongAnswerMapper;
    private final QbQuestionMapper questionMapper;
    private final TraMaterialMapper materialMapper;
    private final TraChapterMapper chapterMapper;
    private final TraCourseMapper courseMapper;

    public TutorTools(Long userId, ObjectMapper objectMapper,
                      ExExamRecordMapper examRecordMapper,
                      ExWrongAnswerAnalysisMapper wrongAnswerMapper,
                      QbQuestionMapper questionMapper,
                      TraMaterialMapper materialMapper,
                      TraChapterMapper chapterMapper,
                      TraCourseMapper courseMapper) {
        this.userId = userId;
        this.objectMapper = objectMapper;
        this.examRecordMapper = examRecordMapper;
        this.wrongAnswerMapper = wrongAnswerMapper;
        this.questionMapper = questionMapper;
        this.materialMapper = materialMapper;
        this.chapterMapper = chapterMapper;
        this.courseMapper = courseMapper;
    }

    @Tool(description = "查询当前用户最近10次考试成绩记录，包含试卷名、客观分、主观分、总分和状态")
    public String myExamRecords() {
        List<ExExamRecord> records = examRecordMapper.selectList(
                new LambdaQueryWrapper<ExExamRecord>()
                        .eq(ExExamRecord::getUserId, userId)
                        .orderByDesc(ExExamRecord::getCreateTime)
                        .last("LIMIT 10"));
        List<Map<String, Object>> items = new ArrayList<>();
        for (ExExamRecord r : records) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("recordId", r.getId());
            item.put("courseId", r.getCourseId());
            item.put("objectiveScore", r.getObjectiveScore());
            item.put("subjectiveScore", r.getSubjectiveScore());
            item.put("totalScore", r.getTotalScore());
            item.put("status", r.getStatus());
            item.put("submitTime", r.getSubmitTime() == null ? null : r.getSubmitTime().toString());
            items.add(item);
        }
        return toJson(Map.of("records", items));
    }

    @Tool(description = "查询当前用户的错题分析记录（最近10条），包含题目内容、错误类型归类、错误原因分析和学习建议")
    public String myWrongAnswers() {
        List<ExWrongAnswerAnalysis> analyses = wrongAnswerMapper.selectList(
                new LambdaQueryWrapper<ExWrongAnswerAnalysis>()
                        .eq(ExWrongAnswerAnalysis::getUserId, userId)
                        .orderByDesc(ExWrongAnswerAnalysis::getCreateTime)
                        .last("LIMIT 10"));
        List<Map<String, Object>> items = new ArrayList<>();
        for (ExWrongAnswerAnalysis a : analyses) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("questionId", a.getQuestionId());
            item.put("errorCategory", a.getErrorCategory());
            item.put("errorAnalysis", a.getErrorAnalysis());
            item.put("recommendation", a.getRecommendation());
            QbQuestion q = a.getQuestionId() == null ? null : questionMapper.selectById(a.getQuestionId());
            if (q != null) {
                item.put("questionContent", q.getContent());
                item.put("questionType", q.getQuestionType());
            }
            items.add(item);
        }
        return toJson(Map.of("wrongAnswers", items));
    }

    @Tool(description = "按关键词搜索课程培训资料（标题和正文模糊匹配），返回资料标题、类型、所属章节与内容摘要")
    public String searchCourseMaterials(@ToolParam(description = "搜索关键词，如：数据加密、钓鱼邮件")
                                        String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return toJson(Map.of("materials", List.of()));
        }
        List<TraMaterial> materials = materialMapper.selectList(
                new LambdaQueryWrapper<TraMaterial>()
                        .and(w -> w.like(TraMaterial::getTitle, keyword)
                                .or().like(TraMaterial::getContent, keyword))
                        .last("LIMIT 10"));
        List<Map<String, Object>> items = new ArrayList<>();
        for (TraMaterial m : materials) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("materialId", m.getId());
            item.put("title", m.getTitle());
            item.put("materialType", m.getMaterialType());
            item.put("chapterId", m.getChapterId());
            item.put("excerpt", m.getContent() == null ? ""
                    : m.getContent().substring(0, Math.min(300, m.getContent().length())));
            item.put("fullContentAvailable", m.getContent() != null && m.getContent().length() > 300);
            items.add(item);
        }
        return toJson(Map.of("materials", items));
    }

    @Tool(description = "获取指定资料的完整正文内容（用于深入阅读某个培训资料），需要传入 searchCourseMaterials 返回的 materialId")
    public String getMaterialDetail(@ToolParam(description = "资料ID") Long materialId) {
        TraMaterial m = materialMapper.selectById(materialId);
        if (m == null) {
            return toJson(Map.of("error", "资料不存在"));
        }
        TraChapter chapter = m.getChapterId() == null ? null : chapterMapper.selectById(m.getChapterId());
        String courseName = null;
        if (chapter != null && chapter.getCourseId() != null) {
            TraCourse course = courseMapper.selectById(chapter.getCourseId());
            courseName = course == null ? null : course.getCourseName();
        }
        String content = m.getContent() == null ? "" :
                m.getContent().substring(0, Math.min(4000, m.getContent().length()));
        return toJson(Map.of(
                "title", m.getTitle() == null ? "" : m.getTitle(),
                "courseName", courseName == null ? "" : courseName,
                "chapterTitle", chapter == null || chapter.getTitle() == null ? "" : chapter.getTitle(),
                "content", content
        ));
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            log.error("Tool result serialize failed: {}", e.getMessage());
            return "{\"error\":\"serialize failed\"}";
        }
    }
}
