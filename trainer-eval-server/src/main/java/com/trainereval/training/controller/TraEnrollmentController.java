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
