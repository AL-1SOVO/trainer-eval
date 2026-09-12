package com.trainereval.ai.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.trainereval.ai.dto.GradingRuleForm;
import com.trainereval.ai.entity.AiGradingLog;
import com.trainereval.ai.entity.AiGradingRule;
import com.trainereval.ai.mapper.AiGradingLogMapper;
import com.trainereval.ai.mapper.AiGradingRuleMapper;
import com.trainereval.common.result.PageResult;
import com.trainereval.common.result.R;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/ai")
public class AiGradingController {

    private static final String ACTIVE_STATUS = "1";
    private static final String DEFAULT_MODEL = "deepseek-v4-flash";

    @Autowired
    private AiGradingRuleMapper ruleMapper;

    @Autowired
    private AiGradingLogMapper logMapper;

    @GetMapping("/rules")
    @PreAuthorize("hasAnyRole('TRAINER','MANAGER')")
    public R<List<AiGradingRule>> listRules() {
        LambdaQueryWrapper<AiGradingRule> wrapper = new LambdaQueryWrapper<>();
        wrapper.in(AiGradingRule::getStatus, List.of(ACTIVE_STATUS, "1"))
                .orderByDesc(AiGradingRule::getCreateTime);
        return R.ok(ruleMapper.selectList(wrapper));
    }

    @GetMapping("/rules/{id}")
    @PreAuthorize("hasAnyRole('TRAINER','MANAGER')")
    public R<Map<String, Object>> getRule(@PathVariable Long id) {
        AiGradingRule rule = ruleMapper.selectById(id);
        if (rule == null) {
            return R.error("评分规则不存在");
        }
        return R.ok(enrichRule(rule));
    }

    @PostMapping("/rules")
    @PreAuthorize("hasAnyRole('TRAINER','MANAGER')")
    public R<Map<String, Object>> createRule(@Valid @RequestBody GradingRuleForm form) {
        AiGradingRule rule = new AiGradingRule();
        fillRule(rule, form, true);
        rule.setStatus(ACTIVE_STATUS);
        ruleMapper.insert(rule);
        return R.ok(enrichRule(ruleMapper.selectById(rule.getId())));
    }

    @PutMapping("/rules/{id}")
    @PreAuthorize("hasAnyRole('TRAINER','MANAGER')")
    public R<Map<String, Object>> updateRule(@PathVariable Long id, @RequestBody GradingRuleForm form) {
        AiGradingRule rule = ruleMapper.selectById(id);
        if (rule == null) {
            return R.error("评分规则不存在");
        }

        fillRule(rule, form, false);
        if (rule.getStatus() == null || rule.getStatus().isBlank()) {
            rule.setStatus(ACTIVE_STATUS);
        }
        ruleMapper.updateById(rule);
        return R.ok(enrichRule(ruleMapper.selectById(id)));
    }

    @DeleteMapping("/rules/{id}")
    @PreAuthorize("hasAnyRole('TRAINER','MANAGER')")
    public R<?> deleteRule(@PathVariable Long id) {
        ruleMapper.deleteById(id);
        return R.ok();
    }

    @GetMapping("/logs")
    @PreAuthorize("hasRole('MANAGER')")
    public R<PageResult<AiGradingLog>> listLogs(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String status) {
        LambdaQueryWrapper<AiGradingLog> wrapper = new LambdaQueryWrapper<>();
        if (status != null && !status.isBlank()) {
            wrapper.eq(AiGradingLog::getStatus, status);
        }
        wrapper.orderByDesc(AiGradingLog::getCreateTime);
        Page<AiGradingLog> result = logMapper.selectPage(new Page<>(page, size), wrapper);
        return R.ok(PageResult.of(result.getRecords(), result.getTotal(), result.getCurrent(), result.getSize()));
    }

    @GetMapping("/logs/{id}")
    @PreAuthorize("hasRole('MANAGER')")
    public R<AiGradingLog> getLog(@PathVariable Long id) {
        return R.ok(logMapper.selectById(id));
    }

    private void fillRule(AiGradingRule rule, GradingRuleForm form, boolean creating) {
        if (creating || form.getCourseId() != null) {
            rule.setCourseId(form.getCourseId());
        }
        if (creating || form.getQuestionType() != null) {
            rule.setQuestionType(form.getQuestionType());
        }
        if (creating || form.getRuleName() != null) {
            rule.setRuleName(form.getRuleName());
        }

        String leniencyLevel = normalizeLeniency(form.getLeniencyLevel());
        String promptTemplate = form.getPromptTemplate();
        Double temperature = form.getTemperature();
        Integer maxTokens = form.getMaxTokens();

        if (creating || form.getModel() != null) {
            rule.setModel(form.getModel() != null ? form.getModel() : DEFAULT_MODEL);
        }

        if (leniencyLevel != null) {
            rule.setPromptTemplate(buildPromptTemplate(rule.getQuestionType(), leniencyLevel, promptTemplate));
            rule.setTemperature(resolveTemperature(leniencyLevel, temperature));
            if (creating || maxTokens != null) {
                rule.setMaxTokens(maxTokens != null ? maxTokens : 2000);
            }
            return;
        }

        if (creating || promptTemplate != null) {
            rule.setPromptTemplate(promptTemplate != null ? promptTemplate : defaultPromptTemplate(rule.getQuestionType()));
        }
        if (creating || temperature != null) {
            rule.setTemperature(temperature != null ? temperature : 0.3);
        }
        if (creating || maxTokens != null) {
            rule.setMaxTokens(maxTokens != null ? maxTokens : 2000);
        }
    }

    private Map<String, Object> enrichRule(AiGradingRule rule) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", rule.getId());
        result.put("courseId", rule.getCourseId());
        result.put("questionType", rule.getQuestionType());
        result.put("ruleName", rule.getRuleName());
        result.put("promptTemplate", rule.getPromptTemplate());
        result.put("model", rule.getModel());
        result.put("temperature", rule.getTemperature());
        result.put("maxTokens", rule.getMaxTokens());
        result.put("status", rule.getStatus());
        result.put("createTime", rule.getCreateTime());
        result.put("updateTime", rule.getUpdateTime());
        result.put("leniencyLevel", detectLeniency(rule));
        return result;
    }

    private String defaultPromptTemplate(String questionType) {
        return buildPromptTemplate(questionType, "STANDARD", null);
    }

    private String buildPromptTemplate(String questionType, String leniencyLevel, String customPrompt) {
        if (customPrompt != null && !customPrompt.isBlank()) {
            return customPrompt;
        }

        String typeLabel = "ESSAY".equals(questionType) ? "论述题" : "SHORT_ANSWER".equals(questionType) ? "简答题" : "主观题";
        String styleText = switch (leniencyLevel) {
            case "STRICT" -> "从严评分，只有准确覆盖关键得分点、逻辑清晰、表达完整时才给高分。对模糊表述、遗漏关键点、偏题内容要明显扣分。";
            case "LENIENT" -> "适度从宽评分，只要核心观点基本正确、能体现理解，即可给出较高分数。允许表述不够完美，但要保持基本准确。";
            default -> "按标准评分，兼顾准确性、完整性和表达条理，既不过严，也不过松。";
        };

        return "请作为企业培训考试的" + typeLabel + "阅卷老师进行评分。" + styleText + " 请严格依据参考答案和评分标准判断，不得编造题外信息。";
    }

    private Double resolveTemperature(String leniencyLevel, Double customTemperature) {
        if (customTemperature != null) {
            return customTemperature;
        }
        return switch (leniencyLevel) {
            case "STRICT" -> 0.2;
            case "LENIENT" -> 0.45;
            default -> 0.3;
        };
    }

    private String normalizeLeniency(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toUpperCase();
        return switch (normalized) {
            case "STRICT", "STANDARD", "LENIENT" -> normalized;
            case "严", "严格" -> "STRICT";
            case "标准", "普通", "默认" -> "STANDARD";
            case "宽松", "从宽" -> "LENIENT";
            default -> null;
        };
    }

    private String detectLeniency(AiGradingRule rule) {
        if (rule.getPromptTemplate() != null) {
            if (rule.getPromptTemplate().contains("从严评分")) {
                return "STRICT";
            }
            if (rule.getPromptTemplate().contains("适度从宽评分")) {
                return "LENIENT";
            }
        }
        if (rule.getTemperature() != null) {
            if (rule.getTemperature() <= 0.22) {
                return "STRICT";
            }
            if (rule.getTemperature() >= 0.42) {
                return "LENIENT";
            }
        }
        return "STANDARD";
    }
}
