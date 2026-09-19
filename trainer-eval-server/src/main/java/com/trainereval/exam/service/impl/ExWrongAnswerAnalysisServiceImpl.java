package com.trainereval.exam.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.trainereval.ai.service.AiErrorAnalysisService;
import com.trainereval.common.result.PageResult;
import com.trainereval.exam.entity.ExExamAnswer;
import com.trainereval.exam.entity.ExExamRecord;
import com.trainereval.exam.entity.ExWrongAnswerAnalysis;
import com.trainereval.exam.mapper.ExExamAnswerMapper;
import com.trainereval.exam.mapper.ExExamRecordMapper;
import com.trainereval.exam.mapper.ExWrongAnswerAnalysisMapper;
import com.trainereval.exam.service.ExWrongAnswerAnalysisService;
import com.trainereval.questionbank.entity.QbQuestion;
import com.trainereval.questionbank.mapper.QbQuestionMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trainereval.training.entity.TraChapter;
import com.trainereval.training.entity.TraCourse;
import com.trainereval.training.entity.TraMaterial;
import com.trainereval.training.mapper.TraChapterMapper;
import com.trainereval.training.mapper.TraCourseMapper;
import com.trainereval.training.mapper.TraMaterialMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ExWrongAnswerAnalysisServiceImpl implements ExWrongAnswerAnalysisService {

    private final ExWrongAnswerAnalysisMapper analysisMapper;
    private final ExExamAnswerMapper answerMapper;
    private final ExExamRecordMapper recordMapper;
    private final QbQuestionMapper questionMapper;
    private final TraCourseMapper courseMapper;
    private final TraMaterialMapper materialMapper;
    private final TraChapterMapper chapterMapper;
    private final ObjectMapper objectMapper;
    private final AiErrorAnalysisService aiErrorAnalysisService;

    @Override
    @Transactional
    public ExWrongAnswerAnalysis triggerAiAnalysis(Long answerId) {
        return aiErrorAnalysisService.analyzeWrongAnswer(answerId);
    }

    @Override
    public ExWrongAnswerAnalysis getByAnswerId(Long answerId) {
        LambdaQueryWrapper<ExWrongAnswerAnalysis> wrapper = new LambdaQueryWrapper<ExWrongAnswerAnalysis>()
                .eq(ExWrongAnswerAnalysis::getAnswerId, answerId);
        return analysisMapper.selectOne(wrapper);
    }

    @Override
    public PageResult<Map<String, Object>> getUserAnalyses(Long userId, int page, int size, Long courseId, String questionType) {
        LambdaQueryWrapper<ExWrongAnswerAnalysis> wrapper = new LambdaQueryWrapper<ExWrongAnswerAnalysis>()
                .eq(ExWrongAnswerAnalysis::getUserId, userId)
                .orderByDesc(ExWrongAnswerAnalysis::getCreateTime);

        IPage<ExWrongAnswerAnalysis> pageResult = analysisMapper.selectPage(new Page<>(page, size), wrapper);

        List<Map<String, Object>> enriched = pageResult.getRecords().stream().map(analysis -> {
            Map<String, Object> item = new HashMap<>();
            item.put("analysis", analysis);
            // 把 related_material_ids 解析成可展示的资料信息。
            // 前端原来直接把它当文本渲染，用户看到的是 "[1]" 这种原始 JSON —— 既看不懂也点不了。
            item.put("relatedMaterials", resolveRelatedMaterials(analysis.getRelatedMaterialIds()));

            ExExamAnswer answer = answerMapper.selectById(analysis.getAnswerId());
            if (answer != null) {
                item.put("userAnswer", answer.getUserAnswer());
                item.put("questionType", answer.getQuestionType());
                item.put("questionContent", answer.getQuestionContent());
                item.put("questionOptions", answer.getQuestionOptions());

                QbQuestion question = questionMapper.selectById(answer.getQuestionId());
                if (question != null) {
                    item.put("referenceAnswer", question.getReferenceAnswer());
                    item.put("questionAnalysis", question.getAnalysis());
                    item.put("difficulty", question.getDifficulty());
                    item.put("tags", question.getTags());

                    TraCourse course = courseMapper.selectById(question.getCourseId());
                    if (course != null) {
                        item.put("courseName", course.getCourseName());
                        item.put("courseId", course.getId());
                    }
                }

                ExExamRecord record = recordMapper.selectById(answer.getRecordId());
                if (record != null) {
                    item.put("totalScore", record.getTotalScore());
                    item.put("passScore", null); // would need to look up paper
                }
            }

            return item;
        }).collect(Collectors.toList());

        // Filter by course/type if needed
        List<Map<String, Object>> filtered = enriched;
        if (courseId != null) {
            filtered = filtered.stream().filter(m -> courseId.equals(m.get("courseId"))).collect(Collectors.toList());
        }
        if (questionType != null && !questionType.isEmpty()) {
            filtered = filtered.stream().filter(m -> questionType.equals(m.get("questionType"))).collect(Collectors.toList());
        }

        PageResult<Map<String, Object>> result = new PageResult<>();
        result.setPage(page);
        result.setSize(size);
        result.setTotal(pageResult.getTotal());
        result.setRecords(filtered);
        return result;
    }

    /**
     * 把 {@code related_material_ids}（JSON 数组）解析成前端能直接渲染的资料条目。
     * <p>
     * 容错要点：这个字段历史上被写入过**主题词字符串**（如 {@code ["数据加密"]}），
     * 那是类型错位留下的脏数据。解析时只接受能转成数字的元素，其余静默跳过 ——
     * 不能让一条历史脏数据把整个错题分析列表打挂。
     */
    private List<Map<String, Object>> resolveRelatedMaterials(String relatedMaterialIds) {
        if (relatedMaterialIds == null || relatedMaterialIds.isBlank()) {
            return List.of();
        }
        List<Long> ids = new ArrayList<>();
        try {
            List<?> raw = objectMapper.readValue(relatedMaterialIds, List.class);
            for (Object o : raw) {
                if (o == null) {
                    continue;
                }
                if (o instanceof Number n) {
                    ids.add(n.longValue());
                    continue;
                }
                try {
                    ids.add(Long.parseLong(String.valueOf(o).trim()));
                } catch (NumberFormatException ignored) {
                    // 主题词等非数字内容：跳过
                }
            }
        } catch (Exception e) {
            log.debug("related_material_ids 不是合法 JSON 数组，忽略：{}", relatedMaterialIds);
            return List.of();
        }
        if (ids.isEmpty()) {
            return List.of();
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (TraMaterial material : materialMapper.selectBatchIds(ids)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("materialId", material.getId());
            row.put("title", material.getTitle());
            row.put("materialType", material.getMaterialType());
            TraChapter chapter = material.getChapterId() == null
                    ? null : chapterMapper.selectById(material.getChapterId());
            if (chapter != null) {
                row.put("chapterTitle", chapter.getTitle());
                // 前端要跳到课程学习页，所以必须带上 courseId（资料实体本身没有这个字段）
                row.put("courseId", chapter.getCourseId());
                TraCourse course = chapter.getCourseId() == null
                        ? null : courseMapper.selectById(chapter.getCourseId());
                row.put("courseName", course == null ? null : course.getCourseName());
            }
            result.add(row);
        }
        return result;
    }

    @Override
    public Map<String, Object> getUserStats(Long userId) {
        Map<String, Object> stats = new HashMap<>();

        List<Map<String, Object>> categoryStats = analysisMapper.countByCategory(userId);
        stats.put("errorCategoryDistribution", categoryStats);

        List<Map<String, Object>> typeStats = analysisMapper.countByQuestionType(userId);
        stats.put("questionTypeDistribution", typeStats);

        List<Map<String, Object>> courseStats = analysisMapper.countByCourse(userId);
        stats.put("courseDistribution", courseStats);

        Long totalWrong = analysisMapper.countWrongByUserId(userId);
        stats.put("totalWrong", totalWrong);

        List<Map<String, Object>> topWrong = analysisMapper.topWrongQuestions(userId, 5);
        stats.put("topWrongQuestions", topWrong);

        return stats;
    }
}
