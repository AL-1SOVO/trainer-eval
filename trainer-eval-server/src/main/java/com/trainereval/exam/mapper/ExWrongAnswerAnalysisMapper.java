package com.trainereval.exam.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.trainereval.exam.entity.ExWrongAnswerAnalysis;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

@Mapper
public interface ExWrongAnswerAnalysisMapper extends BaseMapper<ExWrongAnswerAnalysis> {

    @Select("SELECT error_category, COUNT(*) as count FROM ex_wrong_answer_analysis WHERE user_id = #{userId} GROUP BY error_category")
    List<Map<String, Object>> countByCategory(Long userId);

    @Select("SELECT a.question_type, COUNT(*) as count FROM ex_wrong_answer_analysis wa JOIN ex_exam_answer a ON wa.answer_id = a.id WHERE wa.user_id = #{userId} GROUP BY a.question_type")
    List<Map<String, Object>> countByQuestionType(Long userId);

    @Select("SELECT c.id AS courseId, c.course_name AS courseName, COUNT(*) as count FROM ex_wrong_answer_analysis wa JOIN qb_question q ON wa.question_id = q.id JOIN tra_course c ON q.course_id = c.id WHERE wa.user_id = #{userId} GROUP BY c.id, c.course_name")
    List<Map<String, Object>> countByCourse(Long userId);

    @Select("SELECT COUNT(DISTINCT wa.id) as wrongCount FROM ex_wrong_answer_analysis wa WHERE wa.user_id = #{userId}")
    Long countWrongByUserId(Long userId);

    @Select("SELECT DISTINCT wa.question_id, COUNT(wa.id) as wrongCount FROM ex_wrong_answer_analysis wa WHERE wa.user_id = #{userId} GROUP BY wa.question_id ORDER BY wrongCount DESC LIMIT #{limit}")
    List<Map<String, Object>> topWrongQuestions(Long userId, int limit);
}
