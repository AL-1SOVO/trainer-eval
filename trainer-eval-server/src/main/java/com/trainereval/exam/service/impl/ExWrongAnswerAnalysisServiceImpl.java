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
import com.trainereval.training.entity.TraCourse;
import com.trainereval.training.mapper.TraCourseMapper;
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
