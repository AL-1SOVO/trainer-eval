package com.trainereval.observability;

import com.trainereval.common.result.R;
import com.trainereval.observability.dto.TraceDetail;
import com.trainereval.observability.dto.TraceSummary;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 链路追踪查询与自检接口。
 *
 * <p>权限：链路里含用户提问原文，属于运营数据，只对 MANAGER 开放。
 * 内训师不开放——内训师看的是"教学效果"，不需要看全站用户的对话输入。
 */
@RestController
@RequestMapping("/api/v1/observability")
@RequiredArgsConstructor
public class ObservabilityController {

    private final Observability observability;

    /** 链路状态：本地表是否可用、Langfuse 是否配置、导出计数与最近一次错误。 */
    @GetMapping("/status")
    @PreAuthorize("hasRole('MANAGER')")
    public R<Map<String, Object>> status() {
        return R.ok(observability.status());
    }

    /** 最近链路列表。可按 name（如 agent.chat）过滤。 */
    @GetMapping("/traces")
    @PreAuthorize("hasRole('MANAGER')")
    public R<List<TraceSummary>> traces(@RequestParam(required = false) String name,
                                        @RequestParam(defaultValue = "30") int limit) {
        return R.ok(observability.recent(name, limit));
    }

    /** 单条链路明细，含每一步的类型/耗时/token/输入输出。 */
    @GetMapping("/traces/{id}")
    @PreAuthorize("hasRole('MANAGER')")
    public R<TraceDetail> detail(@PathVariable Long id) {
        TraceDetail detail = observability.detail(id);
        return detail == null ? R.error("链路不存在") : R.ok(detail);
    }

    /** 按 traceId 反查，用于把 Langfuse 看板上的 trace 对回本地记录。 */
    @GetMapping("/traces/by-trace-id/{traceId}")
    @PreAuthorize("hasRole('MANAGER')")
    public R<TraceDetail> detailByTraceId(@PathVariable String traceId) {
        TraceDetail detail = observability.detailByTraceId(traceId);
        return detail == null ? R.error("链路不存在") : R.ok(detail);
    }

    /**
     * Langfuse 连通性自检：同步发一条合成链路，把真实错误返回。
     * 异步导出是黑盒，这个接口是排查"配了但没数据"的唯一直接手段。
     */
    @PostMapping("/test")
    @PreAuthorize("hasRole('MANAGER')")
    public R<Map<String, Object>> test() {
        Map<String, Object> result = observability.testExport();
        return Boolean.TRUE.equals(result.get("ok"))
                ? R.ok("Langfuse 连通正常", result)
                : R.error(String.valueOf(result.getOrDefault("message", "导出失败")));
    }
}
