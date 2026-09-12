package com.trainereval.training.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.trainereval.common.result.PageResult;
import com.trainereval.common.util.SecurityUtils;
import com.trainereval.training.entity.*;
import com.trainereval.training.mapper.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class TraCourseService extends ServiceImpl<TraCourseMapper, TraCourse> {

    @Autowired private TraChapterMapper chapterMapper;
    @Autowired private TraMaterialMapper materialMapper;
    @Autowired private TraEnrollmentMapper enrollmentMapper;
    @Autowired private TraCourseContentService courseContentService;

    public PageResult<TraCourse> pagePublished(String keyword, Long deptId, int page, int size) {
        LambdaQueryWrapper<TraCourse> w = new LambdaQueryWrapper<>();
        w.eq(TraCourse::getStatus, 1);
        if (deptId != null) w.and(x -> x.eq(TraCourse::getDeptId, deptId).or().isNull(TraCourse::getDeptId));
        if (StringUtils.hasText(keyword)) w.like(TraCourse::getCourseName, keyword);
        w.orderByDesc(TraCourse::getCreateTime);
        Page<TraCourse> p = baseMapper.selectPage(new Page<>(page, size), w);
        return PageResult.of(p.getRecords(), p.getTotal(), p.getCurrent(), p.getSize());
    }

    public PageResult<TraCourse> pageManaged(int page, int size) {
        Long trainerId = SecurityUtils.getCurrentUserId();
        LambdaQueryWrapper<TraCourse> w = new LambdaQueryWrapper<>();
        w.eq(TraCourse::getTrainerId, trainerId).orderByDesc(TraCourse::getCreateTime);
        Page<TraCourse> p = baseMapper.selectPage(new Page<>(page, size), w);
        return PageResult.of(p.getRecords(), p.getTotal(), p.getCurrent(), p.getSize());
    }

    public PageResult<TraCourse> pageAll(String keyword, Integer status, int page, int size) {
        LambdaQueryWrapper<TraCourse> w = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(keyword)) w.like(TraCourse::getCourseName, keyword);
        if (status != null) w.eq(TraCourse::getStatus, status);
        w.orderByDesc(TraCourse::getCreateTime);
        Page<TraCourse> p = baseMapper.selectPage(new Page<>(page, size), w);
        return PageResult.of(p.getRecords(), p.getTotal(), p.getCurrent(), p.getSize());
    }

    public TraCourse getDetail(Long id) {
        TraCourse course = baseMapper.selectById(id);
        if (course != null) {
            course = courseContentService.enrichCourseDetail(course, null, false);
        }
        return course;
    }

    @Transactional
    public void publish(Long id) {
        TraCourse course = baseMapper.selectById(id);
        if (course != null) { course.setStatus(1); baseMapper.updateById(course); }
    }

    @Transactional
    public void archive(Long id) {
        TraCourse course = baseMapper.selectById(id);
        if (course != null) { course.setStatus(2); baseMapper.updateById(course); }
    }
}
