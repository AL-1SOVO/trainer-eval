package com.trainereval.exam.service;

import com.trainereval.common.result.PageResult;
import com.trainereval.exam.dto.AnswerDTO;
import com.trainereval.exam.dto.RecordQuery;
import com.trainereval.exam.entity.ExExamRecord;
import java.util.List;
import java.util.Map;

public interface ExExamService {
    List<Map<String, Object>> getAvailablePapers(Long userId);
    Map<String, Object> startExam(Long userId, Long paperId);
    Map<String, Object> getSession(Long recordId, Long userId);
    void saveAnswer(Long recordId, Long questionId, String answer, Long userId);
    ExExamRecord submitExam(Long recordId, List<AnswerDTO> answers, Long userId);
    Map<String, Object> getResult(Long recordId, Long userId);
    PageResult<ExExamRecord> getMyRecords(Long userId, int page, int size);
    PageResult<ExExamRecord> getAllRecords(RecordQuery query);
    Map<String, Object> getOverviewStats();
    List<Map<String, Object>> getStatsByDept();
    List<Map<String, Object>> getStatsByCourse();
    List<Map<String, Object>> getScoreDistribution();
}
