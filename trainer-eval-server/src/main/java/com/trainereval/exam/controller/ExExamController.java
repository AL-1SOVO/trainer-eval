package com.trainereval.exam.controller;

import com.trainereval.common.result.PageResult;
import com.trainereval.common.result.R;
import com.trainereval.common.util.SecurityUtils;
import com.trainereval.exam.dto.*;
import com.trainereval.exam.entity.ExExamRecord;
import com.trainereval.exam.service.ExExamService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/v1/exam")
public class ExExamController {
    @Autowired private ExExamService examService;

    @GetMapping("/papers/available")
    public R<List<Map<String, Object>>> papers() { return R.ok(examService.getAvailablePapers(SecurityUtils.getCurrentUserId())); }

    @PostMapping("/start")
    public R<Map<String, Object>> start(@Valid @RequestBody StartExamRequest req) { return R.ok(examService.startExam(SecurityUtils.getCurrentUserId(), req.getPaperId())); }

    @GetMapping("/session/{recordId}")
    public R<Map<String, Object>> session(@PathVariable Long recordId) { return R.ok(examService.getSession(recordId)); }

    @PostMapping("/session/{recordId}/save")
    public R<?> save(@PathVariable Long recordId, @RequestBody Map<String, Object> body) { examService.saveAnswer(recordId, Long.valueOf(body.get("questionId").toString()), String.valueOf(body.get("answer"))); return R.ok(); }

    @PostMapping("/submit")
    public R<ExExamRecord> submit(@Valid @RequestBody SubmitExamRequest req) { return R.ok(examService.submitExam(req.getRecordId(), req.getAnswers())); }

    @GetMapping("/records/my")
    public R<PageResult<ExExamRecord>> myRecords(@RequestParam(defaultValue="1") int page, @RequestParam(defaultValue="10") int size) { return R.ok(examService.getMyRecords(SecurityUtils.getCurrentUserId(), page, size)); }

    @GetMapping("/records/{id}/result")
    public R<Map<String, Object>> result(@PathVariable Long id) { return R.ok(examService.getResult(id)); }

    @GetMapping("/stats/overview") @PreAuthorize("hasRole('MANAGER')")
    public R<Map<String, Object>> overview() { return R.ok(examService.getOverviewStats()); }
}
