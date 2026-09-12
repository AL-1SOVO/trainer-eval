package com.trainereval.exam.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.trainereval.common.exception.BusinessException;
import com.trainereval.common.exception.ErrorCode;
import com.trainereval.exam.service.AsyncGradingTrigger;
import com.trainereval.common.result.PageResult;
import com.trainereval.exam.dto.AnswerDTO;
import com.trainereval.exam.dto.RecordQuery;
import com.trainereval.exam.entity.ExExamAnswer;
import com.trainereval.exam.entity.ExExamRecord;
import com.trainereval.exam.entity.ExWrongAnswerAnalysis;
import com.trainereval.exam.mapper.ExExamAnswerMapper;
import com.trainereval.exam.mapper.ExExamRecordMapper;
import com.trainereval.exam.mapper.ExWrongAnswerAnalysisMapper;
import com.trainereval.questionbank.entity.QbExamPaper;
import com.trainereval.questionbank.entity.QbQuestion;
import com.trainereval.questionbank.entity.QbPaperQuestion;
import com.trainereval.questionbank.mapper.QbExamPaperMapper;
import com.trainereval.questionbank.mapper.QbQuestionMapper;
import com.trainereval.questionbank.mapper.QbPaperQuestionMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class ExExamServiceImpl extends ServiceImpl<ExExamRecordMapper, ExExamRecord> implements com.trainereval.exam.service.ExExamService {

    @Autowired private ExExamAnswerMapper examAnswerMapper;
    @Autowired private ExExamRecordMapper examRecordMapper;
    @Autowired private ExWrongAnswerAnalysisMapper wrongAnswerAnalysisMapper;
    @Autowired private QbExamPaperMapper paperMapper;
    @Autowired private QbQuestionMapper questionMapper;
    @Autowired private QbPaperQuestionMapper paperQuestionMapper;
    @Autowired(required = false) private com.trainereval.ai.service.AiErrorAnalysisService aiErrorAnalysisService;
    @Autowired private AsyncGradingTrigger asyncGradingTrigger;

    private static final Set<String> OBJECTIVE_TYPES = Set.of("SINGLE_CHOICE", "MULTI_CHOICE", "TRUE_FALSE");

    @Override
    public List<Map<String, Object>> getAvailablePapers(Long userId) {
        LambdaQueryWrapper<QbExamPaper> pw = new LambdaQueryWrapper<>();
        pw.eq(QbExamPaper::getStatus, 1).orderByDesc(QbExamPaper::getCreateTime);
        List<QbExamPaper> papers = paperMapper.selectList(pw);

        List<Map<String, Object>> result = new ArrayList<>();
        for (QbExamPaper paper : papers) {
            Map<String, Object> map = new HashMap<>();
            map.put("paperId", paper.getId());
            map.put("paperName", paper.getPaperName());
            map.put("courseId", paper.getCourseId());
            map.put("totalScore", paper.getTotalScore());
            map.put("passScore", paper.getPassScore());
            map.put("duration", paper.getDuration());

            LambdaQueryWrapper<QbPaperQuestion> qw = new LambdaQueryWrapper<>();
            qw.eq(QbPaperQuestion::getPaperId, paper.getId());
            long qCount = paperQuestionMapper.selectCount(qw);
            map.put("questionCount", qCount);

            LambdaQueryWrapper<ExExamRecord> rw = new LambdaQueryWrapper<>();
            rw.eq(ExExamRecord::getUserId, userId).eq(ExExamRecord::getPaperId, paper.getId());
            long attemptCount = examRecordMapper.selectCount(rw);
            map.put("attemptCount", attemptCount);
            map.put("maxAttempts", paper.getMaxAttempts());
            map.put("canAttempt", attemptCount < (paper.getMaxAttempts() != null ? paper.getMaxAttempts() : Integer.MAX_VALUE));
            result.add(map);
        }
        return result;
    }

    @Override
    @Transactional
    public Map<String, Object> startExam(Long userId, Long paperId) {
        QbExamPaper paper = paperMapper.selectById(paperId);
        if (paper == null || paper.getStatus() != 1) throw new BusinessException(ErrorCode.EXAM_NOT_FOUND);

        LambdaQueryWrapper<ExExamRecord> progressWrapper = new LambdaQueryWrapper<>();
        progressWrapper.eq(ExExamRecord::getUserId, userId)
                .eq(ExExamRecord::getPaperId, paperId)
                .eq(ExExamRecord::getStatus, "IN_PROGRESS")
                .orderByDesc(ExExamRecord::getStartTime)
                .last("LIMIT 1");
        ExExamRecord existingRecord = examRecordMapper.selectOne(progressWrapper);
        if (existingRecord != null) {
            return getSession(existingRecord.getId());
        }

        LambdaQueryWrapper<ExExamRecord> rw = new LambdaQueryWrapper<>();
        rw.eq(ExExamRecord::getUserId, userId).eq(ExExamRecord::getPaperId, paperId);
        long attempts = examRecordMapper.selectCount(rw);
        if (attempts >= paper.getMaxAttempts()) throw new BusinessException(ErrorCode.EXAM_MAX_ATTEMPTS);

        ExExamRecord record = new ExExamRecord();
        record.setUserId(userId);
        record.setPaperId(paperId);
        record.setCourseId(paper.getCourseId());
        record.setAttemptNumber((int) attempts + 1);
        record.setStatus("IN_PROGRESS");
        record.setStartTime(LocalDateTime.now());
        record.setObjectiveScore(0);
        record.setSubjectiveScore(0);
        baseMapper.insert(record);

        LambdaQueryWrapper<QbPaperQuestion> pqw = new LambdaQueryWrapper<>();
        pqw.eq(QbPaperQuestion::getPaperId, paperId).orderByAsc(QbPaperQuestion::getSortOrder);
        List<QbPaperQuestion> pqList = paperQuestionMapper.selectList(pqw);

        List<QbPaperQuestion> orderedQuestions = new ArrayList<>(pqList);
        if (paper.getShuffleQuestions() != null && paper.getShuffleQuestions() == 1) {
            Collections.shuffle(orderedQuestions);
        }

        List<Map<String, Object>> questionList = new ArrayList<>();
        int sort = 0;
        for (QbPaperQuestion pq : orderedQuestions) {
            QbQuestion q = questionMapper.selectById(pq.getQuestionId());
            if (q == null) continue;

            ExExamAnswer answer = new ExExamAnswer();
            answer.setRecordId(record.getId());
            answer.setQuestionId(q.getId());
            answer.setQuestionType(q.getQuestionType());
            answer.setQuestionContent(q.getContent());
            answer.setQuestionOptions(q.getOptions());
            answer.setQuestionScore(BigDecimal.valueOf(pq.getScore() != null ? pq.getScore() : 0));
            answer.setAiGraded(0);
            examAnswerMapper.insert(answer);

            Map<String, Object> qm = new HashMap<>();
            qm.put("questionId", q.getId());
            qm.put("questionType", q.getQuestionType());
            qm.put("questionContent", q.getContent());
            qm.put("options", q.getOptions());
            qm.put("questionScore", pq.getScore());
            qm.put("sortOrder", sort++);
            questionList.add(qm);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("recordId", record.getId());
        result.put("paperName", paper.getPaperName());
        result.put("duration", paper.getDuration());
        result.put("totalScore", paper.getTotalScore());
        result.put("questions", questionList);
        return result;
    }

    @Override
    public Map<String, Object> getSession(Long recordId) {
        ExExamRecord record = baseMapper.selectById(recordId);
        if (record == null) throw new BusinessException(ErrorCode.NOT_FOUND);
        QbExamPaper paper = paperMapper.selectById(record.getPaperId());

        List<ExExamAnswer> answers = examAnswerMapper.selectByRecordId(recordId);
        List<Map<String, Object>> qList = new ArrayList<>();
        for (ExExamAnswer a : answers) {
            Map<String, Object> m = new HashMap<>();
            m.put("questionId", a.getQuestionId());
            m.put("questionType", a.getQuestionType());
            m.put("questionContent", a.getQuestionContent());
            m.put("options", a.getQuestionOptions());
            m.put("questionScore", a.getQuestionScore());
            qList.add(m);
        }

        long elapsedSec = java.time.Duration.between(record.getStartTime(), LocalDateTime.now()).getSeconds();
        long remainingSec = (paper != null ? paper.getDuration() * 60L : 3600L) - elapsedSec;

        Map<String, Object> result = new HashMap<>();
        result.put("recordId", record.getId());
        result.put("paperName", paper != null ? paper.getPaperName() : "");
        result.put("duration", paper != null ? paper.getDuration() : 60);
        result.put("remainingSec", Math.max(0, remainingSec));
        result.put("questions", qList);
        return result;
    }

    @Override
    @Transactional
    public void saveAnswer(Long recordId, Long questionId, String userAnswer) {
        ExExamRecord record = baseMapper.selectById(recordId);
        if (record == null || !"IN_PROGRESS".equals(record.getStatus())) return;

        LambdaUpdateWrapper<ExExamAnswer> uw = new LambdaUpdateWrapper<>();
        uw.eq(ExExamAnswer::getRecordId, recordId).eq(ExExamAnswer::getQuestionId, questionId)
          .set(ExExamAnswer::getUserAnswer, userAnswer);
        examAnswerMapper.update(null, uw);
    }

    @Override
    @Transactional
    public ExExamRecord submitExam(Long recordId, List<AnswerDTO> answers) {
        ExExamRecord record = baseMapper.selectById(recordId);
        if (record == null) throw new BusinessException(ErrorCode.EXAM_NOT_FOUND);
        if (!"IN_PROGRESS".equals(record.getStatus())) throw new BusinessException(ErrorCode.EXAM_ALREADY_SUBMITTED);

        QbExamPaper paper = paperMapper.selectById(record.getPaperId());
        long elapsed = java.time.Duration.between(record.getStartTime(), LocalDateTime.now()).toMinutes();
        if (paper != null && elapsed > paper.getDuration()) throw new BusinessException(ErrorCode.EXAM_TIME_EXCEEDED);

        // Save answers
        if (answers != null) {
            for (AnswerDTO dto : answers) {
                LambdaUpdateWrapper<ExExamAnswer> uw = new LambdaUpdateWrapper<>();
                uw.eq(ExExamAnswer::getRecordId, recordId).eq(ExExamAnswer::getQuestionId, dto.getQuestionId())
                  .set(ExExamAnswer::getUserAnswer, dto.getUserAnswer());
                examAnswerMapper.update(null, uw);
            }
        }

        // Auto-grade objective questions
        List<ExExamAnswer> answerList = examAnswerMapper.selectByRecordId(recordId);
        int objectiveScore = 0;
        for (ExExamAnswer ans : answerList) {
            if (OBJECTIVE_TYPES.contains(ans.getQuestionType())) {
                QbQuestion q = questionMapper.selectById(ans.getQuestionId());
                if (q != null) {
                    boolean correct = isAnswerCorrect(ans.getUserAnswer(), q.getReferenceAnswer(), ans.getQuestionType());
                    int isCorrect = correct ? 1 : 0;
                    BigDecimal awardedScore = correct ? ans.getQuestionScore() : BigDecimal.ZERO;

                    LambdaUpdateWrapper<ExExamAnswer> uw = new LambdaUpdateWrapper<>();
                    uw.eq(ExExamAnswer::getId, ans.getId())
                      .set(ExExamAnswer::getIsCorrect, isCorrect)
                      .set(ExExamAnswer::getScore, awardedScore);
                    examAnswerMapper.update(null, uw);

                    if (correct) objectiveScore += awardedScore.intValue();
                }
            }
        }

        record.setObjectiveScore(objectiveScore);
        record.setTotalScore(objectiveScore);
        record.setSubmitTime(LocalDateTime.now());
        record.setStatus("SUBMITTED");
        baseMapper.updateById(record);

        // Start AI grading after commit so async readers see the latest answers and status.
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    asyncGradingTrigger.executeAsync(recordId);
                }
            });
        } else {
            asyncGradingTrigger.executeAsync(recordId);
        }

        // Re-fetch with updated data
        return baseMapper.selectById(recordId);
    }

    private boolean isAnswerCorrect(String userAnswer, String referenceAnswer, String questionType) {
        if (userAnswer == null || referenceAnswer == null) return false;
        if ("MULTI_CHOICE".equals(questionType)) {
            Set<String> uSet = new HashSet<>(Arrays.asList(userAnswer.replaceAll("[,\\s]+", "").split("")));
            Set<String> rSet = new HashSet<>(Arrays.asList(referenceAnswer.replaceAll("[,\\s]+", "").split("")));
            return uSet.equals(rSet);
        }
        return userAnswer.trim().equalsIgnoreCase(referenceAnswer.trim());
    }

    @Override
    public Map<String, Object> getResult(Long recordId) {
        ExExamRecord record = baseMapper.selectById(recordId);
        if (record == null) throw new BusinessException(ErrorCode.NOT_FOUND);
        QbExamPaper paper = paperMapper.selectById(record.getPaperId());
        List<ExExamAnswer> answers = examAnswerMapper.selectByRecordId(recordId);

        Map<String, Object> result = new HashMap<>();
        result.put("recordId", record.getId());
        result.put("paperName", paper != null ? paper.getPaperName() : "");
        result.put("status", record.getStatus());
        result.put("totalMaxScore", paper != null ? paper.getTotalScore() : 0);
        result.put("totalScore", record.getTotalScore() != null
                ? record.getTotalScore()
                : (record.getObjectiveScore() != null ? record.getObjectiveScore() : 0)
                + (record.getSubjectiveScore() != null ? record.getSubjectiveScore() : 0));
        result.put("objectiveScore", record.getObjectiveScore());
        result.put("subjectiveScore", record.getSubjectiveScore());
        result.put("passScore", paper != null ? paper.getPassScore() : 0);
        result.put("isPass", record.getTotalScore() != null && paper != null && record.getTotalScore() >= paper.getPassScore());
        result.put("aiPending", hasPendingAiGrading(record, answers));

        long durationSec = 0;
        if (record.getSubmitTime() != null && record.getStartTime() != null) {
            durationSec = java.time.Duration.between(record.getStartTime(), record.getSubmitTime()).toSeconds();
        }
        result.put("duration", String.format("%d分%d秒", durationSec / 60, durationSec % 60));

        List<Map<String, Object>> answerList = new ArrayList<>();
        for (ExExamAnswer ans : answers) {
            QbQuestion q = questionMapper.selectById(ans.getQuestionId());
            Map<String, Object> am = new HashMap<>();
            am.put("questionId", ans.getQuestionId());
            am.put("questionType", ans.getQuestionType());
            am.put("questionContent", ans.getQuestionContent());
            am.put("questionScore", ans.getQuestionScore());
            am.put("userAnswer", ans.getUserAnswer());
            am.put("referenceAnswer", q != null ? q.getReferenceAnswer() : "");
            am.put("score", ans.getScore());
            am.put("isCorrect", ans.getIsCorrect());
            am.put("aiGraded", ans.getAiGraded());
            am.put("aiFeedback", ans.getAiFeedback());
            // Generate error analysis for wrong answers
            String errorAnalysis = ans.getErrorAnalysis();
            if (errorAnalysis == null && ans.getIsCorrect() != null && ans.getIsCorrect() == 0 && q != null) {
                errorAnalysis = buildErrorAnalysis(ans, q);
            }
            am.put("errorAnalysis", errorAnalysis);
            answerList.add(am);
        }
        result.put("answers", answerList);
        return result;
    }

    @Override
    public PageResult<ExExamRecord> getMyRecords(Long userId, int page, int size) {
        LambdaQueryWrapper<ExExamRecord> w = new LambdaQueryWrapper<>();
        w.eq(ExExamRecord::getUserId, userId).orderByDesc(ExExamRecord::getCreateTime);
        Page<ExExamRecord> p = baseMapper.selectPage(new Page<>(page, size), w);
        enrichRecords(p.getRecords());
        return PageResult.of(p.getRecords(), p.getTotal(), p.getCurrent(), p.getSize());
    }

    @Override
    public PageResult<ExExamRecord> getAllRecords(RecordQuery query) {
        LambdaQueryWrapper<ExExamRecord> w = new LambdaQueryWrapper<>();
        if (query.getPaperId() != null) w.eq(ExExamRecord::getPaperId, query.getPaperId());
        if (query.getCourseId() != null) w.eq(ExExamRecord::getCourseId, query.getCourseId());
        if (query.getStatus() != null) w.eq(ExExamRecord::getStatus, query.getStatus());
        w.orderByDesc(ExExamRecord::getCreateTime);
        Page<ExExamRecord> p = baseMapper.selectPage(new Page<>(query.getPage(), query.getSize()), w);
        enrichRecords(p.getRecords());
        return PageResult.of(p.getRecords(), p.getTotal(), p.getCurrent(), p.getSize());
    }

    private boolean hasPendingAiGrading(ExExamRecord record, List<ExExamAnswer> answers) {
        boolean hasSubjective = false;
        for (ExExamAnswer ans : answers) {
            if (!OBJECTIVE_TYPES.contains(ans.getQuestionType())) {
                hasSubjective = true;
                if (ans.getAiGraded() == null || ans.getAiGraded() != 1) {
                    return true;
                }
            }
        }
        return hasSubjective && !"GRADED".equals(record.getStatus());
    }

    private String buildErrorAnalysis(ExExamAnswer ans, QbQuestion q) {
        StringBuilder sb = new StringBuilder();
        sb.append("【错题分析】\n");
        sb.append("题目类型: ").append(getQuestionTypeName(ans.getQuestionType())).append("\n\n");
        sb.append("你的答案: ").append(ans.getUserAnswer() != null ? ans.getUserAnswer() : "未作答").append("\n");
        sb.append("正确答案: ").append(q.getReferenceAnswer() != null ? q.getReferenceAnswer() : "").append("\n\n");
        if (q.getAnalysis() != null && !q.getAnalysis().isEmpty()) {
            sb.append("解析: ").append(q.getAnalysis()).append("\n\n");
        }
        // 根据题型给出建议
        switch (ans.getQuestionType()) {
            case "SINGLE_CHOICE":
                sb.append("建议: 单选题考察对知识点的准确理解，请回顾相关章节内容，注意区分易混淆的选项。");
                break;
            case "MULTI_CHOICE":
                sb.append("建议: 多选题需全面掌握知识点，注意不要遗漏正确选项，也不要多选错误选项。");
                break;
            case "TRUE_FALSE":
                sb.append("建议: 判断题考察对基本概念的理解，请注意细节描述是否准确。");
                break;
            case "SHORT_ANSWER":
            case "ESSAY":
                String feedback = ans.getAiFeedback();
                if (feedback != null && !feedback.isEmpty()) {
                    sb.append("AI评语: ").append(feedback).append("\n");
                }
                sb.append("建议: 请对照参考答案和评分标准，理解得分点和自己的不足之处。");
                break;
        }
        return sb.toString();
    }

    private String getQuestionTypeName(String type) {
        return switch (type) {
            case "SINGLE_CHOICE" -> "单选题";
            case "MULTI_CHOICE" -> "多选题";
            case "TRUE_FALSE" -> "判断题";
            case "SHORT_ANSWER" -> "简答题";
            case "ESSAY" -> "论述题";
            default -> type;
        };
    }

    private void enrichRecords(List<ExExamRecord> records) {
        for (ExExamRecord r : records) {
            QbExamPaper paper = paperMapper.selectById(r.getPaperId());
            if (paper != null) r.setPaperName(paper.getPaperName());
        }
    }

    @Override
    public Map<String, Object> getOverviewStats() {
        long total = baseMapper.selectCount(null);
        Map<String, Object> stats = new HashMap<>();
        stats.put("totalExams", total);
        stats.put("totalPassed", 0L);
        stats.put("passRate", "0%");
        stats.put("avgScore", BigDecimal.ZERO);
        return stats;
    }

    @Override
    public List<Map<String, Object>> getStatsByDept() { return List.of(); }

    @Override
    public List<Map<String, Object>> getStatsByCourse() { return List.of(); }

    @Override
    public List<Map<String, Object>> getScoreDistribution() { return List.of(); }
}
