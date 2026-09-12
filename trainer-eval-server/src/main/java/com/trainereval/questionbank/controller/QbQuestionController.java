package com.trainereval.questionbank.controller;

import com.trainereval.common.exception.BusinessException;
import com.trainereval.common.result.PageResult;
import com.trainereval.common.result.R;
import com.trainereval.common.util.SecurityUtils;
import com.trainereval.questionbank.entity.QbQuestion;
import com.trainereval.questionbank.service.QbQuestionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/questions")
public class QbQuestionController {

    @Autowired
    private QbQuestionService questionService;

    @GetMapping
    public R<PageResult<QbQuestion>> list(
            @RequestParam Long courseId,
            @RequestParam(required = false) String questionType,
            @RequestParam(required = false) Integer difficulty,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return R.ok(questionService.pageByCourse(courseId, questionType, difficulty, page, size));
    }

    @GetMapping("/all")
    public R<List<QbQuestion>> all(@RequestParam Long courseId) {
        return R.ok(questionService.listByCourse(courseId));
    }

    @GetMapping("/{id}")
    public R<QbQuestion> getById(@PathVariable Long id) {
        QbQuestion q = questionService.getById(id);
        return q != null ? R.ok(q) : R.error(404, "题目不存在");
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('TRAINER','MANAGER')")
    public R<?> create(@RequestBody QbQuestion question) {
        question.setCreatorId(SecurityUtils.getCurrentUserId());
        if (question.getStatus() == null) question.setStatus(1);
        questionService.save(question);
        return R.ok("创建成功", question);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('TRAINER','MANAGER')")
    public R<?> update(@PathVariable Long id, @RequestBody QbQuestion question) {
        question.setId(id);
        questionService.updateById(question);
        return R.ok("更新成功");
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('TRAINER','MANAGER')")
    public R<?> delete(@PathVariable Long id) {
        try {
            questionService.deleteSafely(id);
            return R.ok("删除成功");
        } catch (BusinessException e) {
            return R.error(e.getCode(), e.getMessage());
        }
    }

    @PostMapping("/import")
    @PreAuthorize("hasAnyRole('TRAINER','MANAGER')")
    public R<Map<String, Object>> importExcel(
            @RequestParam MultipartFile file,
            @RequestParam Long courseId) {
        try {
            Long creatorId = SecurityUtils.getCurrentUserId();
            int count = questionService.importFromExcel(file, courseId, creatorId);
            Map<String, Object> result = Map.of("imported", count, "courseId", courseId);
            return R.ok("成功导入 " + count + " 道题目", result);
        } catch (Exception e) {
            return R.error("导入失败: " + e.getMessage());
        }
    }

    @GetMapping("/template")
    public ResponseEntity<byte[]> downloadTemplate() {
        try {
            byte[] data = questionService.exportTemplate();
            String filename = URLEncoder.encode("试题导入模板.xlsx", StandardCharsets.UTF_8);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + filename)
                    .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                    .body(data);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/stats")
    public R<Map<String, Object>> stats(@RequestParam Long courseId) {
        return R.ok(questionService.getTypeStats(courseId));
    }
}
