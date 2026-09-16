package com.trainereval.rag.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.trainereval.common.result.R;
import com.trainereval.common.util.SecurityUtils;
import com.trainereval.rag.dto.RetrievalHit;
import com.trainereval.rag.service.KnowledgeAccessService;
import com.trainereval.rag.service.RagRetrievalFacade;
import com.trainereval.training.entity.TraCourse;
import com.trainereval.training.mapper.TraCourseMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识检索 —— <b>面向所有登录用户</b>（含员工）的培训文档检索入口。
 * <p>
 * 与 {@code /api/v1/rag/*} 的区别：
 * <ul>
 *   <li>{@code /rag/*} 是<b>管理与调试</b>面（重建索引、切块预览、评测、轨迹、熔断状态），
 *       限 TRAINER/MANAGER，返回内部字段（backend / degraded / channels / score 原值）。</li>
 *   <li>本接口是<b>产品面</b>：员工用来快速查找企业培训文档知识，
 *       只返回用户需要的信息（标题、课程、章节、片段、出处），
 *       并在服务降级时给出一句人话提示，而不是暴露技术细节。</li>
 * </ul>
 * 可检索范围由 {@link KnowledgeAccessService} 按登录用户裁剪 —— 员工只能搜到自己有权看到的课程，
 * 越权不可能通过构造查询绕过（裁剪发生在数据访问处，不依赖提示词约束）。
 */
@RestController
@RequestMapping("/api/v1/knowledge")
@RequiredArgsConstructor
public class KnowledgeSearchController {

    private static final int DEFAULT_TOP_K = 8;
    private static final int MAX_TOP_K = 20;

    private final RagRetrievalFacade retrievalFacade;
    private final KnowledgeAccessService accessService;
    private final TraCourseMapper courseMapper;

    /**
     * 检索培训文档知识。
     * 请求体：{@code {"query":"数据加密","courseId":1,"topK":8}}（courseId / topK 可省略）
     */
    @PostMapping("/search")
    public R<Map<String, Object>> search(@RequestBody Map<String, Object> body) {
        String query = body.get("query") == null ? "" : String.valueOf(body.get("query")).trim();
        Long courseId = body.get("courseId") instanceof Number n ? n.longValue() : null;
        int topK = body.get("topK") instanceof Number n
                ? Math.min(Math.max(n.intValue(), 1), MAX_TOP_K)
                : DEFAULT_TOP_K;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("query", query);
        if (query.isEmpty()) {
            result.put("total", 0);
            result.put("items", List.of());
            result.put("message", "请输入要查找的内容");
            return R.ok(result);
        }

        RagRetrievalFacade.RagResult ragResult = retrievalFacade.search(
                SecurityUtils.getCurrentUserId(), "knowledge-search", query, courseId, topK);

        List<Map<String, Object>> items = new ArrayList<>();
        for (RetrievalHit hit : ragResult.hits()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("citation", hit.citation());
            item.put("materialId", hit.materialId());
            item.put("courseId", hit.courseId());
            item.put("chapterId", hit.chapterId());
            item.put("title", hit.title());
            item.put("courseName", hit.courseName());
            item.put("chapterTitle", hit.chapterTitle());
            item.put("excerpt", hit.snippet());
            item.put("relevance", Math.round(hit.score() * 10000) / 10000.0);
            items.add(item);
        }

        result.put("total", items.size());
        result.put("items", items);
        if (items.isEmpty()) {
            result.put("message", ragResult.note() != null
                    ? ragResult.note()
                    : "没有找到相关资料。可能是该知识点还没有上传培训文档，或换个说法再试试。");
        } else if (ragResult.degraded()) {
            // 降级时如实告知，但不暴露"哪个服务挂了"这类实现细节
            result.put("message", "当前为降级检索，结果可能不完整。");
        }
        // 供前端做提示（不暴露 backend 名称）
        result.put("degraded", ragResult.degraded());
        return R.ok(result);
    }

    /**
     * 当前用户可检索的课程列表，用于前端做课程筛选下拉。
     * 与检索使用同一套裁剪逻辑，保证"能筛到的课 = 能搜到的课"。
     */
    @GetMapping("/courses")
    public R<List<Map<String, Object>>> courses() {
        Long userId = SecurityUtils.getCurrentUserId();
        KnowledgeAccessService.AccessScope scope = accessService.resolve(userId, null);

        LambdaQueryWrapper<TraCourse> wrapper = new LambdaQueryWrapper<>();
        if (scope.unrestricted()) {
            wrapper.eq(TraCourse::getStatus, 1);
        } else if (scope.courseIds().isEmpty()) {
            return R.ok(List.of());
        } else {
            wrapper.in(TraCourse::getId, scope.courseIds());
        }
        wrapper.orderByDesc(TraCourse::getCreateTime);

        List<Map<String, Object>> result = new ArrayList<>();
        for (TraCourse course : courseMapper.selectList(wrapper)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("courseId", course.getId());
            item.put("courseName", course.getCourseName());
            result.add(item);
        }
        return R.ok(result);
    }
}
