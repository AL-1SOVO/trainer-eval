package com.trainereval.exam.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.trainereval.exam.entity.ExExamAnswer;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ExExamAnswerMapper extends BaseMapper<ExExamAnswer> {

    @Select("SELECT * FROM ex_exam_answer WHERE record_id = #{recordId}")
    List<ExExamAnswer> selectByRecordId(Long recordId);
}
