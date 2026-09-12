package com.trainereval.exam.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.trainereval.common.result.R;
import com.trainereval.common.util.SecurityUtils;
import com.trainereval.exam.entity.ExExamAnswer;
import com.trainereval.exam.entity.ExExamRecord;
import com.trainereval.exam.mapper.ExExamAnswerMapper;
import com.trainereval.exam.mapper.ExExamRecordMapper;
import com.trainereval.questionbank.entity.QbExamPaper;
import com.trainereval.questionbank.entity.QbQuestion;
import com.trainereval.questionbank.mapper.QbExamPaperMapper;
import com.trainereval.questionbank.mapper.QbQuestionMapper;
import com.trainereval.system.entity.SysUser;
import com.trainereval.system.mapper.SysUserMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/v1/exam/grading")
public class ExGradingController {

    private static final Set<String> SUBJECTIVE_TYPES = Set.of("SHORT_ANSWER", "ESSAY");
    private static final Set<String> OBJECTIVE_TYPES = Set.of("SINGLE_CHOICE", "MULTI_CHOICE", "TRUE_FALSE");

    @Autowired
    private ExExamRecordMapper recordMapper;

    @Autowired
    private ExExamAnswerMapper answerMapper;

    @Autowired
    private QbExamPaperMapper paperMapper;

    @Autowired
    private QbQuestionMapper questionMapper;

    @Autowired
    private SysUserMapper userMapper;

    @GetMapping("/queue")
    @PreAuthorize("hasAnyRole('TRAINER','MANAGER')")
    public R<List<Map<String, Object>>> queue(@RequestParam(required = false) String status) {
        LambdaQueryWrapper<ExExamRecord> wrapper = new LambdaQueryWrapper<>();
        if (status != null && !status.isBlank()) {
            wrapper.eq(ExExamRecord::getStatus, status);
        } else {
            wrapper.in(ExExamRecord::getStatus, List.of("SUBMITTED", "GRADED", "REVIEWED"));
        }
        wrapper.orderByDesc(ExExamRecord::getSubmitTime).orderByDesc(ExExamRecord::getCreateTime);

        List<ExExamRecord> records = recordMapper.selectList(wrapper);
        List<Map<String, Object>> result = new ArrayList<>();
        for (ExExamRecord record : records) {
            List<ExExamAnswer> answers = answerMapper.selectByRecordId(record.getId());
            long subjectiveCount = answers.stream().filter(this::isSubjective).count();
            if (subjectiveCount == 0) {
                continue;
            }

            long aiDoneCount = answers.stream()
                    .filter(this::isSubjective)
                    .filter(answer -> answer.getAiGraded() != null && answer.getAiGraded() == 1)
                    .count();
            long manualDoneCount = answers.stream()
                    .filter(this::isSubjective)
                    .filter(answer -> answer.getManualScore() != null)
                    .count();

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("recordId", record.getId());
            item.put("status", record.getStatus());
            item.put("submitTime", record.getSubmitTime());
            item.put("reviewTime", record.getReviewTime());
            item.put("totalScore", record.getTotalScore());
            item.put("objectiveScore", record.getObjectiveScore());
            item.put("subjectiveScore", record.getSubjectiveScore());
            item.put("subjectiveCount", subjectiveCount);
            item.put("aiDoneCount", aiDoneCount);
            item.put("manualDoneCount", manualDoneCount);
            item.put("aiPending", aiDoneCount < subjectiveCount);

            QbExamPaper paper = paperMapper.selectById(record.getPaperId());
            if (paper != null) {
                item.put("paperName", paper.getPaperName());
                item.put("paperTotalScore", paper.getTotalScore());
                item.put("passScore", paper.getPassScore());
            }

            SysUser user = userMapper.selectById(record.getUserId());
            if (user != null) {
                item.put("userId", user.getId());
                item.put("userName", user.getRealName());
                item.put("username", user.getUsername());
            }

            result.add(item);
        }
        return R.ok(result);
    }

    @GetMapping("/{recordId}")
    @PreAuthorize("hasAnyRole('TRAINER','MANAGER')")
    public R<Map<String, Object>> detail(@PathVariable Long recordId) {
        ExExamRecord record = recordMapper.selectById(recordId);
        if (record == null) {
            return R.error("阅卷记录不存在");
        }

        List<ExExamAnswer> answers = answerMapper.selectByRecordId(recordId);
        List<Map<String, Object>> answerItems = new ArrayList<>();
        BigDecimal subjectiveTotal = BigDecimal.ZERO;
        BigDecimal subjectiveAwarded = BigDecimal.ZERO;
        int answeredSubjectiveCount = 0;

        for (ExExamAnswer answer : answers) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", answer.getId());
            item.put("questionId", answer.getQuestionId());
            item.put("questionType", answer.getQuestionType());
            item.put("questionContent", answer.getQuestionContent());
            item.put("questionOptions", answer.getQuestionOptions());
            item.put("questionScore", answer.getQuestionScore());
            item.put("userAnswer", answer.getUserAnswer());
            item.put("isCorrect", answer.getIsCorrect());
            item.put("score", answer.getScore());
            item.put("aiGraded", answer.getAiGraded());
            item.put("aiScore", answer.getAiScore());
            item.put("aiFeedback", answer.getAiFeedback());
            item.put("manualScore", answer.getManualScore());
            item.put("gradeTime", answer.getGradeTime());

            QbQuestion question = questionMapper.selectById(answer.getQuestionId());
            if (question != null) {
                item.put("referenceAnswer", question.getReferenceAnswer());
                item.put("analysis", question.getAnalysis());
                item.put("scoringCriteria", question.getScoringCriteria());
            }

            if (isSubjective(answer)) {
                subjectiveTotal = subjectiveTotal.add(nullSafe(answer.getQuestionScore()));
                subjectiveAwarded = subjectiveAwarded.add(resolveFinalScore(answer));
                if (answer.getUserAnswer() != null && !answer.getUserAnswer().isBlank()) {
                    answeredSubjectiveCount++;
                }
            }

            answerItems.add(item);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("record", record);
        result.put("answers", answerItems);
        result.put("subjectiveTotalScore", subjectiveTotal);
        result.put("subjectiveAwardedScore", subjectiveAwarded);
        result.put("answeredSubjectiveCount", answeredSubjectiveCount);

        QbExamPaper paper = paperMapper.selectById(record.getPaperId());
        if (paper != null) {
            result.put("paper", paper);
        }

        SysUser user = userMapper.selectById(record.getUserId());
        if (user != null) {
            Map<String, Object> userInfo = new LinkedHashMap<>();
            userInfo.put("id", user.getId());
            userInfo.put("realName", user.getRealName());
            userInfo.put("username", user.getUsername());
            result.put("user", userInfo);
        }

        return R.ok(result);
    }

    @PutMapping("/answers/{answerId}")
    @PreAuthorize("hasAnyRole('TRAINER','MANAGER')")
    public R<Map<String, Object>> adjust(@PathVariable Long answerId, @RequestBody Map<String, Object> body) {
        ExExamAnswer answer = answerMapper.selectById(answerId);
        if (answer == null) {
            return R.error("题目答案不存在");
        }

        Number manualScoreNumber = body.get("manualScore") instanceof Number ? (Number) body.get("manualScore") : null;
        String feedback = body.get("aiFeedback") != null ? String.valueOf(body.get("aiFeedback")) : null;

        if (manualScoreNumber != null) {
            BigDecimal manualScore = BigDecimal.valueOf(manualScoreNumber.doubleValue());
            BigDecimal maxScore = nullSafe(answer.getQuestionScore());
            if (manualScore.compareTo(BigDecimal.ZERO) < 0) {
                manualScore = BigDecimal.ZERO;
            }
            if (manualScore.compareTo(maxScore) > 0) {
                manualScore = maxScore;
            }
            answer.setManualScore(manualScore);
            answer.setScore(manualScore);
        }

        if (feedback != null) {
            answer.setAiFeedback(feedback);
        }

        answer.setGraderId(SecurityUtils.getCurrentUserId());
        answer.setGradeTime(LocalDateTime.now());
        answerMapper.updateById(answer);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("answerId", answer.getId());
        result.put("manualScore", answer.getManualScore());
        result.put("score", answer.getScore());
        result.put("aiFeedback", answer.getAiFeedback());
        return R.ok(result);
    }

    @PutMapping("/{recordId}/finalize")
    @PreAuthorize("hasAnyRole('TRAINER','MANAGER')")
    public R<Map<String, Object>> finalizeReview(@PathVariable Long recordId) {
        ExExamRecord record = recordMapper.selectById(recordId);
        if (record == null) {
            return R.error("阅卷记录不存在");
        }

        List<ExExamAnswer> answers = answerMapper.selectByRecordId(recordId);
        BigDecimal subjectiveScore = BigDecimal.ZERO;
        for (ExExamAnswer answer : answers) {
            if (isSubjective(answer)) {
                BigDecimal finalScore = resolveFinalScore(answer);
                subjectiveScore = subjectiveScore.add(finalScore);
                if (answer.getScore() == null || answer.getScore().compareTo(finalScore) != 0) {
                    answer.setScore(finalScore);
                    answer.setGradeTime(LocalDateTime.now());
                    answer.setGraderId(SecurityUtils.getCurrentUserId());
                    answerMapper.updateById(answer);
                }
            }
        }

        int objectiveScore = record.getObjectiveScore() != null ? record.getObjectiveScore() : 0;
        int subjectiveScoreInt = subjectiveScore.intValue();
        record.setSubjectiveScore(subjectiveScoreInt);
        record.setTotalScore(objectiveScore + subjectiveScoreInt);
        record.setStatus("REVIEWED");
        record.setReviewBy(SecurityUtils.getCurrentUserId());
        record.setReviewTime(LocalDateTime.now());
        record.setGradeTime(LocalDateTime.now());
        recordMapper.updateById(record);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("recordId", record.getId());
        result.put("status", record.getStatus());
        result.put("totalScore", record.getTotalScore());
        result.put("objectiveScore", record.getObjectiveScore());
        result.put("subjectiveScore", record.getSubjectiveScore());
        return R.ok(result);
    }

    private boolean isSubjective(ExExamAnswer answer) {
        return SUBJECTIVE_TYPES.contains(answer.getQuestionType());
    }

    private BigDecimal resolveFinalScore(ExExamAnswer answer) {
        if (answer.getManualScore() != null) {
            return answer.getManualScore();
        }
        if (answer.getScore() != null) {
            return answer.getScore();
        }
        if (answer.getAiScore() != null) {
            return answer.getAiScore();
        }
        return BigDecimal.ZERO;
    }

    private BigDecimal nullSafe(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }
}
