package com.trainereval.training.controller;

import com.trainereval.common.result.PageResult;
import com.trainereval.common.result.R;
import com.trainereval.common.util.SecurityUtils;
import com.trainereval.training.entity.TraCourse;
import com.trainereval.training.service.TraCourseService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/training/courses")
public class TraCourseController {

    @Autowired private TraCourseService courseService;

    @GetMapping
    public R<PageResult<TraCourse>> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Long deptId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        return R.ok(courseService.pagePublished(keyword, deptId, page, size));
    }

    @GetMapping("/my/managed")
    @PreAuthorize("hasRole('TRAINER')")
    public R<PageResult<TraCourse>> myManaged(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        return R.ok(courseService.pageManaged(page, size));
    }

    @GetMapping("/all")
    @PreAuthorize("hasRole('MANAGER')")
    public R<PageResult<TraCourse>> all(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        return R.ok(courseService.pageAll(keyword, status, page, size));
    }

    @GetMapping("/{id}")
    public R<TraCourse> getById(@PathVariable Long id) {
        return R.ok(courseService.getDetail(id));
    }

    @PostMapping
    @PreAuthorize("hasRole('TRAINER')")
    public R<TraCourse> create(@RequestBody TraCourse course) {
        course.setTrainerId(SecurityUtils.getCurrentUserId());
        course.setStatus(0);
        courseService.save(course);
        return R.ok(course);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('TRAINER')")
    public R<?> update(@PathVariable Long id, @RequestBody TraCourse course) {
        course.setId(id);
        courseService.updateById(course);
        return R.ok();
    }

    @PutMapping("/{id}/status")
    @PreAuthorize("hasRole('TRAINER')")
    public R<?> updateStatus(@PathVariable Long id, @RequestBody Map<String, Integer> body) {
        if (body.get("status") == 1) courseService.publish(id);
        else if (body.get("status") == 2) courseService.archive(id);
        return R.ok();
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('TRAINER')")
    public R<?> delete(@PathVariable Long id) {
        courseService.removeById(id);
        return R.ok();
    }
}
