package com.trainereval.exam.controller;

import com.trainereval.common.exception.BusinessException;
import com.trainereval.common.exception.ErrorCode;
import com.trainereval.common.result.PageResult;
import com.trainereval.common.result.R;
import com.trainereval.common.util.SecurityUtils;
import com.trainereval.exam.entity.ExExamAnswer;
import com.trainereval.exam.entity.ExExamRecord;
import com.trainereval.exam.entity.ExWrongAnswerAnalysis;
import com.trainereval.exam.mapper.ExExamAnswerMapper;
import com.trainereval.exam.mapper.ExExamRecordMapper;
import com.trainereval.exam.service.ExWrongAnswerAnalysisService;
import com.trainereval.security.LoginUser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/exam/analysis")
public class AnalysisController {

    @Autowired
    private ExWrongAnswerAnalysisService analysisService;
    @Autowired
    private ExExamAnswerMapper answerMapper;
    @Autowired
    private ExExamRecordMapper recordMapper;

    @PostMapping("/{answerId}")
    public R<ExWrongAnswerAnalysis> triggerAnalysis(@PathVariable Long answerId) {
        requireOwnAnswer(answerId);
        return R.ok("AI分析完成", analysisService.triggerAiAnalysis(answerId));
    }

    @GetMapping("/answer/{answerId}")
    public R<ExWrongAnswerAnalysis> getByAnswerId(@PathVariable Long answerId) {
        requireOwnAnswer(answerId);
        ExWrongAnswerAnalysis analysis = analysisService.getByAnswerId(answerId);
        if (analysis == null) {
            return R.error(404, "未找到分析记录");
        }
        return R.ok(analysis);
    }

    /**
     * 修复：/analysis/{answerId} 原先既不校验归属也无角色限制 —— 任意登录用户可读
     * 他人错题分析，并可循环调用触发 LLM 调用（消耗 API 额度）。
     * 现在要求答案是本人的，或调用方为阅卷方/管理员。
     */
    private void requireOwnAnswer(Long answerId) {
        ExExamAnswer answer = answerMapper.selectById(answerId);
        if (answer == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        LoginUser current = SecurityUtils.getCurrentUser();
        if (current == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        boolean graderOrAdmin = current.getRoles() != null && current.getRoles().stream()
                .anyMatch(r -> r != null && (r.endsWith("TRAINER") || r.endsWith("MANAGER")));
        if (graderOrAdmin) {
            return;
        }
        ExExamRecord record = recordMapper.selectById(answer.getRecordId());
        Long me = SecurityUtils.getCurrentUserId();
        if (record == null || me == null || !me.equals(record.getUserId())) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
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
