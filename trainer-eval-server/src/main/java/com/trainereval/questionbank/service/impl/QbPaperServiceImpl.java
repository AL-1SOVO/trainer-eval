package com.trainereval.questionbank.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.trainereval.common.exception.BusinessException;
import com.trainereval.common.exception.ErrorCode;
import com.trainereval.common.result.PageResult;
import com.trainereval.exam.entity.ExExamRecord;
import com.trainereval.exam.mapper.ExExamRecordMapper;
import com.trainereval.questionbank.dto.PaperForm;
import com.trainereval.questionbank.dto.PaperQuery;
import com.trainereval.questionbank.dto.PaperQuestionItem;
import com.trainereval.questionbank.entity.QbExamPaper;
import com.trainereval.questionbank.entity.QbPaperQuestion;
import com.trainereval.questionbank.entity.QbQuestion;
import com.trainereval.questionbank.mapper.QbExamPaperMapper;
import com.trainereval.questionbank.mapper.QbPaperQuestionMapper;
import com.trainereval.questionbank.mapper.QbQuestionMapper;
import com.trainereval.questionbank.service.QbPaperService;
import com.trainereval.training.entity.TraCourse;
import com.trainereval.training.mapper.TraCourseMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class QbPaperServiceImpl extends ServiceImpl<QbExamPaperMapper, QbExamPaper> implements QbPaperService {

    @Autowired
    private QbPaperQuestionMapper paperQuestionMapper;
    @Autowired
    private QbQuestionMapper questionMapper;
    @Autowired
    private ExExamRecordMapper examRecordMapper;
    @Autowired
    private TraCourseMapper courseMapper;

    @Override
    public PageResult<QbExamPaper> pageByCourse(PaperQuery query) {
        LambdaQueryWrapper<QbExamPaper> w = new LambdaQueryWrapper<>();
        if (query.getCourseId() != null) {
            w.eq(QbExamPaper::getCourseId, query.getCourseId());
        }
        if (query.getKeyword() != null && !query.getKeyword().isEmpty()) {
            w.like(QbExamPaper::getPaperName, query.getKeyword());
        }
        if (query.getStatus() != null) {
            w.eq(QbExamPaper::getStatus, query.getStatus());
        }
        w.orderByDesc(QbExamPaper::getCreateTime);
        Page<QbExamPaper> p = baseMapper.selectPage(new Page<>(query.getPage(), query.getSize()), w);

        // enrich course name and question count
        for (QbExamPaper paper : p.getRecords()) {
            if (paper.getCourseId() != null) {
                TraCourse course = courseMapper.selectById(paper.getCourseId());
                if (course != null) paper.setCourseName(course.getCourseName());
            }
            Long qCount = paperQuestionMapper.selectCount(
                    new LambdaQueryWrapper<QbPaperQuestion>().eq(QbPaperQuestion::getPaperId, paper.getId()));
            paper.setQuestionScores(Collections.singletonList(qCount.intValue()));
        }

        return PageResult.of(p.getRecords(), p.getTotal(), p.getCurrent(), p.getSize());
    }

    @Override
    public QbExamPaper getDetail(Long id) {
        QbExamPaper paper = baseMapper.selectById(id);
        if (paper == null) return null;

        if (paper.getCourseId() != null) {
            TraCourse course = courseMapper.selectById(paper.getCourseId());
            if (course != null) paper.setCourseName(course.getCourseName());
        }

        // Load questions with scores
        LambdaQueryWrapper<QbPaperQuestion> pqw = new LambdaQueryWrapper<>();
        pqw.eq(QbPaperQuestion::getPaperId, id).orderByAsc(QbPaperQuestion::getSortOrder);
        List<QbPaperQuestion> pqList = paperQuestionMapper.selectList(pqw);

        List<QbQuestion> questions = new ArrayList<>();
        List<Integer> scores = new ArrayList<>();
        for (QbPaperQuestion pq : pqList) {
            QbQuestion q = questionMapper.selectById(pq.getQuestionId());
            if (q != null) {
                questions.add(q);
                scores.add(pq.getScore());
            }
        }
        paper.setQuestions(questions);
        paper.setQuestionScores(scores);
        return paper;
    }

    @Override
    @Transactional
    public QbExamPaper create(PaperForm form, Long creatorId) {
        QbExamPaper paper = new QbExamPaper();
        paper.setCourseId(form.getCourseId());
        paper.setPaperName(form.getPaperName());
        paper.setDescription(form.getDescription());
        paper.setTotalScore(form.getTotalScore());
        paper.setPassScore(form.getPassScore());
        paper.setDuration(form.getDuration());
        paper.setShuffleQuestions(form.getShuffleQuestions() != null ? form.getShuffleQuestions() : 0);
        paper.setShuffleOptions(form.getShuffleOptions() != null ? form.getShuffleOptions() : 0);
        paper.setAllowRetake(form.getAllowRetake() != null ? form.getAllowRetake() : 1);
        paper.setMaxAttempts(form.getMaxAttempts() != null ? form.getMaxAttempts() : 3);
        paper.setStatus(form.getStatus() != null ? form.getStatus() : 1);
        paper.setCreatorId(creatorId);
        baseMapper.insert(paper);

        // Save questions
        saveQuestions(paper.getId(), form.getQuestions());
        return paper;
    }

    @Override
    @Transactional
    public QbExamPaper update(Long id, PaperForm form) {
        QbExamPaper paper = baseMapper.selectById(id);
        if (paper == null) throw new BusinessException(ErrorCode.EXAM_NOT_FOUND);

        paper.setCourseId(form.getCourseId());
        paper.setPaperName(form.getPaperName());
        paper.setDescription(form.getDescription());
        paper.setTotalScore(form.getTotalScore());
        paper.setPassScore(form.getPassScore());
        paper.setDuration(form.getDuration());
        paper.setShuffleQuestions(form.getShuffleQuestions());
        paper.setShuffleOptions(form.getShuffleOptions());
        paper.setAllowRetake(form.getAllowRetake());
        paper.setMaxAttempts(form.getMaxAttempts());
        if (form.getStatus() != null) paper.setStatus(form.getStatus());
        baseMapper.updateById(paper);

        // Replace questions
        paperQuestionMapper.delete(
                new LambdaQueryWrapper<QbPaperQuestion>().eq(QbPaperQuestion::getPaperId, id));
        saveQuestions(id, form.getQuestions());
        return paper;
    }

    @Override
    @Transactional
    public void deleteSafely(Long id) {
        QbExamPaper paper = baseMapper.selectById(id);
        if (paper == null) return;

        Long examCount = examRecordMapper.selectCount(
                new LambdaQueryWrapper<ExExamRecord>().eq(ExExamRecord::getPaperId, id));
        if (examCount > 0) {
            throw new BusinessException(ErrorCode.EXAM_NOT_FOUND.getCode(),
                    "该试卷已有 " + examCount + " 条考试记录，无法删除。可将其禁用");
        }

        paperQuestionMapper.delete(
                new LambdaQueryWrapper<QbPaperQuestion>().eq(QbPaperQuestion::getPaperId, id));
        baseMapper.deleteById(id);
    }

    @Override
    public void updateStatus(Long id, Integer status) {
        QbExamPaper paper = baseMapper.selectById(id);
        if (paper == null) throw new BusinessException(ErrorCode.EXAM_NOT_FOUND);
        paper.setStatus(status);
        baseMapper.updateById(paper);
    }

    @Override
    public List<QbQuestion> getPaperQuestions(Long id) {
        LambdaQueryWrapper<QbPaperQuestion> pqw = new LambdaQueryWrapper<>();
        pqw.eq(QbPaperQuestion::getPaperId, id).orderByAsc(QbPaperQuestion::getSortOrder);
        List<QbPaperQuestion> pqList = paperQuestionMapper.selectList(pqw);

        List<QbQuestion> questions = new ArrayList<>();
        for (QbPaperQuestion pq : pqList) {
            QbQuestion q = questionMapper.selectById(pq.getQuestionId());
            if (q != null) {
                q.setDefaultScore(pq.getScore());
                questions.add(q);
            }
        }
        return questions;
    }

    @Override
    public Map<String, Object> getStats(Long courseId) {
        LambdaQueryWrapper<QbExamPaper> w = new LambdaQueryWrapper<>();
        if (courseId != null) w.eq(QbExamPaper::getCourseId, courseId);
        List<QbExamPaper> papers = baseMapper.selectList(w);

        Map<String, Object> stats = new HashMap<>();
        stats.put("total", papers.size());

        long enabled = papers.stream().filter(p -> p.getStatus() != null && p.getStatus() == 1).count();
        stats.put("enabled", enabled);
        stats.put("disabled", papers.size() - enabled);

        // Per-course stats
        Map<Long, Long> courseCounts = papers.stream()
                .collect(Collectors.groupingBy(QbExamPaper::getCourseId, Collectors.counting()));
        stats.put("byCourse", courseCounts);

        return stats;
    }

    private void saveQuestions(Long paperId, List<PaperQuestionItem> items) {
        if (items == null || items.isEmpty()) return;
        int order = 0;
        for (PaperQuestionItem item : items) {
            QbPaperQuestion pq = new QbPaperQuestion();
            pq.setPaperId(paperId);
            pq.setQuestionId(item.getQuestionId());
            pq.setScore(item.getScore() != null ? item.getScore() : 5);
            pq.setSortOrder(item.getSortOrder() != null ? item.getSortOrder() : order++);
            paperQuestionMapper.insert(pq);
        }
    }
}
