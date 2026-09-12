package com.trainereval.questionbank.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.trainereval.common.result.PageResult;
import com.trainereval.questionbank.entity.QbQuestion;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

public interface QbQuestionService extends IService<QbQuestion> {
    PageResult<QbQuestion> pageByCourse(Long courseId, String questionType, Integer difficulty, int page, int size);
    List<QbQuestion> listByCourse(Long courseId);
    int importFromExcel(MultipartFile file, Long courseId, Long creatorId) throws Exception;
    byte[] exportTemplate() throws Exception;
    Map<String, Object> getTypeStats(Long courseId);
    void deleteSafely(Long id);
}
