package com.trainereval.exam.controller;

import com.trainereval.common.result.PageResult;
import com.trainereval.common.result.R;
import com.trainereval.common.util.SecurityUtils;
import com.trainereval.exam.entity.ExWrongAnswerAnalysis;
import com.trainereval.exam.service.ExWrongAnswerAnalysisService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/exam/analysis")
public class AnalysisController {

    @Autowired
    private ExWrongAnswerAnalysisService analysisService;

    @PostMapping("/{answerId}")
    public R<ExWrongAnswerAnalysis> triggerAnalysis(@PathVariable Long answerId) {
        return R.ok("AI分析完成", analysisService.triggerAiAnalysis(answerId));
    }

    @GetMapping("/answer/{answerId}")
    public R<ExWrongAnswerAnalysis> getByAnswerId(@PathVariable Long answerId) {
        ExWrongAnswerAnalysis analysis = analysisService.getByAnswerId(answerId);
        if (analysis == null) {
            return R.error(404, "未找到分析记录");
        }
        return R.ok(analysis);
    }

    @GetMapping("/user")
    public R<PageResult<Map<String, Object>>> userAnalyses(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) Long courseId,
            @RequestParam(required = false) String questionType) {
        Long userId = SecurityUtils.getCurrentUserId();
        return R.ok(analysisService.getUserAnalyses(userId, page, size, courseId, questionType));
    }

    @GetMapping("/stats")
    public R<Map<String, Object>> userStats() {
        Long userId = SecurityUtils.getCurrentUserId();
        return R.ok(analysisService.getUserStats(userId));
    }
}
