package com.trainereval.training.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.trainereval.common.exception.BusinessException;
import com.trainereval.common.exception.ErrorCode;
import com.trainereval.common.result.PageResult;
import com.trainereval.common.util.SecurityUtils;
import com.trainereval.training.entity.*;
import com.trainereval.training.mapper.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.*;

@Service
public class TraEnrollmentService extends ServiceImpl<TraEnrollmentMapper, TraEnrollment> {

    @Autowired private TraCourseMapper courseMapper;
    @Autowired private TraChapterMapper chapterMapper;
    @Autowired private TraMaterialMapper materialMapper;
    @Autowired private TraLearningProgressMapper progressMapper;
    @Autowired private TraCourseContentService courseContentService;

    @Transactional
    public void enroll(Long userId, Long courseId) {
        TraCourse course = courseMapper.selectById(courseId);
        if (course == null || course.getStatus() != 1) throw new BusinessException(ErrorCode.COURSE_NOT_FOUND);
        LambdaQueryWrapper<TraEnrollment> w = new LambdaQueryWrapper<>();
        w.eq(TraEnrollment::getUserId, userId).eq(TraEnrollment::getCourseId, courseId);
        if (baseMapper.selectCount(w) > 0) throw new BusinessException(ErrorCode.ALREADY_ENROLLED);

        TraEnrollment e = new TraEnrollment();
        e.setUserId(userId); e.setCourseId(courseId);
        e.setProgress(BigDecimal.ZERO); e.setStatus(0);
        e.setEnrollTime(LocalDateTime.now());
        baseMapper.insert(e);
        course.setEnrollCount(course.getEnrollCount() + 1);
        courseMapper.updateById(course);
    }

    public List<TraEnrollment> getMyEnrollments(Long userId) {
        LambdaQueryWrapper<TraEnrollment> w = new LambdaQueryWrapper<>();
        w.eq(TraEnrollment::getUserId, userId).orderByDesc(TraEnrollment::getEnrollTime);
        List<TraEnrollment> list = baseMapper.selectList(w);
        for (TraEnrollment e : list) {
            TraCourse course = courseMapper.selectById(e.getCourseId());
            if (course != null) e.setCourseName(course.getCourseName());
        }
        return list;
    }

    @Transactional
    public void updateProgress(Long userId, Long materialId) {
        courseContentService.completeMaterial(userId, materialId);
    }

    public Map<String, Object> getCourseStats(Long courseId) {
        LambdaQueryWrapper<TraEnrollment> w = new LambdaQueryWrapper<>();
        w.eq(TraEnrollment::getCourseId, courseId);
        List<TraEnrollment> list = baseMapper.selectList(w);
        Map<String, Object> stats = new HashMap<>();
        stats.put("total", list.size());
        stats.put("completed", list.stream().filter(e -> e.getStatus() == 1).count());
        double avgProgress = list.stream().mapToDouble(e -> e.getProgress().doubleValue()).average().orElse(0);
        stats.put("avgProgress", Math.round(avgProgress * 100.0) / 100.0);
        return stats;
    }
}
