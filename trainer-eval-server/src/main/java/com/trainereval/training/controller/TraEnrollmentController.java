package com.trainereval.training.controller;

import com.trainereval.common.result.R;
import com.trainereval.common.util.SecurityUtils;
import com.trainereval.training.entity.TraEnrollment;
import com.trainereval.training.service.TraEnrollmentService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/training/enrollments")
public class TraEnrollmentController {

    @Autowired private TraEnrollmentService enrollmentService;

    @GetMapping("/my")
    public R<List<TraEnrollment>> my() {
        return R.ok(enrollmentService.getMyEnrollments(SecurityUtils.getCurrentUserId()));
    }

    @PostMapping
    public R<?> enroll(@RequestBody Map<String, Long> body) {
        enrollmentService.enroll(SecurityUtils.getCurrentUserId(), body.get("courseId"));
        return R.ok("报名成功");
    }

    @DeleteMapping("/{id}")
    public R<?> drop(@PathVariable Long id) {
        // 修复：原实现直接把路径 id 当主键物理删除，无角色、无归属校验，
        // 任意登录用户（含 EMPLOYEE）都能退掉别人的课，并级联清除对方学习进度。
        TraEnrollment enrollment = enrollmentService.getById(id);
        if (enrollment == null) {
            return R.error(404, "报名记录不存在");
        }
        Long me = SecurityUtils.getCurrentUserId();
        if (me == null || !me.equals(enrollment.getUserId())) {
            return R.error(403, "无权操作他人的报名记录");
        }
        enrollmentService.removeById(id);
        return R.ok();
    }

    @PutMapping("/{enrollmentId}/progress")
    public R<?> updateProgress(@PathVariable Long enrollmentId, @RequestBody Map<String, Long> body) {
        enrollmentService.updateProgress(SecurityUtils.getCurrentUserId(), body.get("materialId"));
        return R.ok();
    }

    @GetMapping("/course/{courseId}/stats")
    @PreAuthorize("hasRole('TRAINER')")
    public R<Map<String, Object>> courseStats(@PathVariable Long courseId) {
        return R.ok(enrollmentService.getCourseStats(courseId));
    }
}
