package com.trainereval.questionbank.controller;

import com.trainereval.common.exception.BusinessException;
import com.trainereval.common.result.PageResult;
import com.trainereval.common.result.R;
import com.trainereval.common.util.SecurityUtils;
import com.trainereval.questionbank.dto.PaperForm;
import com.trainereval.questionbank.dto.PaperQuery;
import com.trainereval.questionbank.entity.QbExamPaper;
import com.trainereval.questionbank.entity.QbQuestion;
import com.trainereval.questionbank.service.QbPaperService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/papers")
public class QbPaperController {

    @Autowired
    private QbPaperService paperService;

    @GetMapping
    public R<PageResult<QbExamPaper>> list(
            @RequestParam(required = false) Long courseId,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        PaperQuery query = new PaperQuery();
        query.setCourseId(courseId);
        query.setKeyword(keyword);
        query.setStatus(status);
        query.setPage(page);
        query.setSize(size);
        return R.ok(paperService.pageByCourse(query));
    }

    @GetMapping("/{id}")
    public R<QbExamPaper> getById(@PathVariable Long id) {
        QbExamPaper paper = paperService.getDetail(id);
        return paper != null ? R.ok(paper) : R.error(404, "试卷不存在");
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('TRAINER','MANAGER')")
    public R<QbExamPaper> create(@RequestBody PaperForm form) {
        Long creatorId = SecurityUtils.getCurrentUserId();
        QbExamPaper paper = paperService.create(form, creatorId);
        return R.ok("创建成功", paper);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('TRAINER','MANAGER')")
    public R<?> update(@PathVariable Long id, @RequestBody PaperForm form) {
        paperService.update(id, form);
        return R.ok("更新成功");
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('TRAINER','MANAGER')")
    public R<?> delete(@PathVariable Long id) {
        try {
            paperService.deleteSafely(id);
            return R.ok("删除成功");
        } catch (BusinessException e) {
            return R.error(e.getCode(), e.getMessage());
        }
    }

    @PutMapping("/{id}/status")
    @PreAuthorize("hasAnyRole('TRAINER','MANAGER')")
    public R<?> updateStatus(@PathVariable Long id, @RequestParam Integer status) {
        paperService.updateStatus(id, status);
        return R.ok(status == 1 ? "已启用" : "已禁用");
    }

    @GetMapping("/{id}/questions")
    public R<List<QbQuestion>> getQuestions(@PathVariable Long id) {
        return R.ok(paperService.getPaperQuestions(id));
    }

    @GetMapping("/stats")
    public R<Map<String, Object>> stats(@RequestParam(required = false) Long courseId) {
        return R.ok(paperService.getStats(courseId));
    }
}
