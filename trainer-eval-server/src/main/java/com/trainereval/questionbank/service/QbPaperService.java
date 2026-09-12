package com.trainereval.questionbank.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.trainereval.common.result.PageResult;
import com.trainereval.questionbank.dto.PaperForm;
import com.trainereval.questionbank.dto.PaperQuery;
import com.trainereval.questionbank.entity.QbExamPaper;
import com.trainereval.questionbank.entity.QbQuestion;

import java.util.List;
import java.util.Map;

public interface QbPaperService extends IService<QbExamPaper> {
    PageResult<QbExamPaper> pageByCourse(PaperQuery query);
    QbExamPaper getDetail(Long id);
    QbExamPaper create(PaperForm form, Long creatorId);
    QbExamPaper update(Long id, PaperForm form);
    void deleteSafely(Long id);
    void updateStatus(Long id, Integer status);
    List<QbQuestion> getPaperQuestions(Long id);
    Map<String, Object> getStats(Long courseId);
}
