package com.trainereval.exam.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.trainereval.ai.service.AiGradingService;
import com.trainereval.exam.entity.ExExamAnswer;
import com.trainereval.exam.entity.ExExamRecord;
import com.trainereval.exam.mapper.ExExamAnswerMapper;
import com.trainereval.exam.mapper.ExExamRecordMapper;
import com.trainereval.questionbank.entity.QbQuestion;
import com.trainereval.questionbank.mapper.QbQuestionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class AsyncGradingTrigger {

    private final ExExamAnswerMapper examAnswerMapper;
    private final ExExamRecordMapper examRecordMapper;
    private final QbQuestionMapper questionMapper;
    private final AiGradingService aiGradingService;

    private static final Set<String> OBJECTIVE_TYPES = Set.of("SINGLE_CHOICE", "MULTI_CHOICE", "TRUE_FALSE");

    @Async
    @Transactional
    public void executeAsync(Long recordId) {
        try {
            List<ExExamAnswer> answers = examAnswerMapper.selectByRecordId(recordId);
            int subjectiveScore = 0;
            for (ExExamAnswer ans : answers) {
                if (!OBJECTIVE_TYPES.contains(ans.getQuestionType())) {
                    QbQuestion q = questionMapper.selectById(ans.getQuestionId());
                    if (q != null) {
                        AiGradingService.GradeResult gr = aiGradingService.grade(ans, q);
                        BigDecimal awarded = BigDecimal.valueOf(gr.getScore());
                        LambdaUpdateWrapper<ExExamAnswer> uw = new LambdaUpdateWrapper<>();
                        uw.eq(ExExamAnswer::getId, ans.getId())
                          .set(ExExamAnswer::getAiGraded, 1)
                          .set(ExExamAnswer::getAiScore, awarded)
                          .set(ExExamAnswer::getAiFeedback, gr.getFeedback())
                          .set(ExExamAnswer::getScore, awarded);
                        examAnswerMapper.update(null, uw);
                        subjectiveScore += awarded.intValue();
                    }
                }
            }

            ExExamRecord record = examRecordMapper.selectById(recordId);
            if (record != null) {
                record.setSubjectiveScore(subjectiveScore);
                record.setTotalScore((record.getObjectiveScore() != null ? record.getObjectiveScore() : 0) + subjectiveScore);
                record.setStatus("GRADED");
                record.setGradeTime(LocalDateTime.now());
                examRecordMapper.updateById(record);
            }
        } catch (Exception e) {
            log.error("AI grading failed for record: {}", recordId, e);
            ExExamRecord record = examRecordMapper.selectById(recordId);
            if (record != null) {
                record.setStatus("SUBMITTED");
                examRecordMapper.updateById(record);
            }
        }
    }
}
