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
    public void executeAsync(Long recordId) {
        // 修复：本方法原先同时带 @Async 与 @Transactional，导致 N 道题的 N 次串行
        // LLM 调用全程持有同一个数据库连接（Hikari 默认池 10、本线程池 max 10），
        // 连接池被占满后整个应用（含与 AI 无关的接口）都会阻塞。
        // 现在不开启事务，每条写操作各自提交。
        regrade(recordId);
    }

    /**
     * 同步执行 AI 批改（也供阅卷端"重新 AI 批改"调用）。
     * 关键约束：AI 未成功时绝不把 0 分写成成绩。
     */
    public void regrade(Long recordId) {
        List<ExExamAnswer> answers = examAnswerMapper.selectByRecordId(recordId);
        int subjectiveScore = 0;
        int failedCount = 0;

        for (ExExamAnswer ans : answers) {
            if (OBJECTIVE_TYPES.contains(ans.getQuestionType())) {
                continue;
            }
            QbQuestion q = questionMapper.selectById(ans.getQuestionId());
            if (q == null) {
                continue;
            }

            AiGradingService.GradeResult gr;
            try {
                gr = aiGradingService.grade(ans, q);
            } catch (Exception e) {
                log.error("AI 批改异常 record={} answer={}", recordId, ans.getId(), e);
                failedCount++;
                continue;
            }

            // 修复：AI 失败时原实现会把 0 分写入 score/ai_score 并置 ai_graded=1，
            // 界面因此显示绿色"AI 已评分"，学生被判不及格而老师无从察觉。
            // 现在失败既不写分数也不置 ai_graded，让该题保持"待人工"状态。
            if (gr.isFailed()) {
                failedCount++;
                log.warn("AI 批改未成功，保持待人工状态 record={} answer={}", recordId, ans.getId());
                continue;
            }

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

        ExExamRecord record = examRecordMapper.selectById(recordId);
        if (record == null) {
            return;
        }
        int objective = record.getObjectiveScore() != null ? record.getObjectiveScore() : 0;
        record.setSubjectiveScore(subjectiveScore);
        record.setTotalScore(objective + subjectiveScore);
        record.setGradeTime(LocalDateTime.now());
        if (failedCount > 0) {
            // 仍有题未批改成功 → 保持 SUBMITTED，让阅卷队列显示"AI 处理中"，
            // 而不是把不完整的分数当成最终成绩（原实现会直接置 GRADED）。
            record.setStatus("SUBMITTED");
            log.warn("record={} 有 {} 道主观题 AI 批改未成功，保持待人工状态", recordId, failedCount);
        } else {
            record.setStatus("GRADED");
        }
        examRecordMapper.updateById(record);
    }
}
