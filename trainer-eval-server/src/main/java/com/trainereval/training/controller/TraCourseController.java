package com.trainereval.training.controller;

import com.trainereval.common.exception.BusinessException;
import com.trainereval.common.exception.ErrorCode;
import com.trainereval.common.result.PageResult;
import com.trainereval.common.result.R;
import com.trainereval.common.util.SecurityUtils;
import com.trainereval.security.LoginUser;
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
        requireManageable(id);
        course.setId(id);
        // 禁止通过该接口改归属或改状态（状态只能走 /status，且归属不可转移）
        course.setTrainerId(null);
        course.setStatus(null);
        course.setEnrollCount(null);
        courseService.updateById(course);
        return R.ok();
    }

    @PutMapping("/{id}/status")
    @PreAuthorize("hasRole('TRAINER')")
    public R<?> updateStatus(@PathVariable Long id, @RequestBody Map<String, Integer> body) {
        requireManageable(id);
        Integer status = body.get("status");
        // 修复：原先 body.get("status")==1 对 null 拆箱会 NPE，且非法值静默返回成功
        if (status == null) {
            return R.error(400, "缺少 status 参数");
        }
        if (status == 1) {
            courseService.publish(id);
        } else if (status == 2) {
            courseService.archive(id);
        } else {
            return R.error(400, "不支持的 status 值: " + status);
        }
        return R.ok();
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('TRAINER')")
    public R<?> delete(@PathVariable Long id) {
        // 修复：原实现无归属校验，而 tra_chapter/tra_material/tra_enrollment/
        // tra_learning_progress 全是 ON DELETE CASCADE → 一条 DELETE 会永久清空
        // 该课程全部学员的报名与学习进度。
        requireManageable(id);
        courseService.removeById(id);
        return R.ok();
    }

    /**
     * 修复：本控制器的 update/updateStatus/delete 原先只有 @PreAuthorize 角色校验，
     * 没有归属校验，任意 TRAINER 可改/删/发布他人课程（含把 trainerId 改成自己夺取课程）。
     * 现在要求课程归属本人，或调用方为 MANAGER。
     */
    private void requireManageable(Long courseId) {
        TraCourse course = courseService.getById(courseId);
        if (course == null) {
            throw new BusinessException(ErrorCode.COURSE_NOT_FOUND);
        }
        LoginUser current = SecurityUtils.getCurrentUser();
        if (current == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        boolean manager = current.getRoles() != null && current.getRoles().stream()
                .anyMatch(r -> r != null && r.endsWith("MANAGER"));
        if (manager) {
            return;
        }
        Long me = SecurityUtils.getCurrentUserId();
        if (me == null || !me.equals(course.getTrainerId())) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
    }
}
