package com.trainereval.rag.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.trainereval.common.util.SecurityUtils;
import com.trainereval.security.LoginUser;
import com.trainereval.training.entity.TraEnrollment;
import com.trainereval.training.mapper.TraEnrollmentMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 权限裁剪（Security Trimming）—— 对应方案文档阶段四。
 * <p>
 * 企业级 RAG 与 demo 的分水岭：<b>在工具边界按业务关系裁剪可检索范围</b>，
 * 而不是把"你不要越权"写进提示词指望模型遵守。
 * <p>
 * 关键设计：裁剪对 <b>MimirQ 与本地检索两条路径同时生效</b>。
 * 如果只在 MimirQ 路径裁剪，那么"知识库服务不可用 → 降级到本地"就变成了
 * <b>fail-open</b>：只要把 MimirQ 打挂，绕过的正是权限本身。这是降级设计里最容易踩的坑。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeAccessService {

    private final TraEnrollmentMapper enrollmentMapper;

    /** 裁剪总开关。关闭后所有登录用户可检索全部课程资料（仅建议本地调试用）。 */
    @Value("${rag.security-trimming.enabled:true}")
    private boolean trimmingEnabled;

    /**
     * 可访问的课程范围。
     *
     * @param unrestricted true 表示不受限（系统调用或培训师/管理员）
     */
    public record AccessScope(boolean unrestricted, Set<Long> courseIds) {

        public boolean allows(Long courseId) {
            if (unrestricted) {
                return true;
            }
            return courseId != null && courseIds.contains(courseId);
        }

        public boolean isEmpty() {
            return !unrestricted && courseIds.isEmpty();
        }
    }

    /**
     * 解析当前请求的可访问范围。
     *
     * @param userId            显式传入的调用主体；传 null 表示系统上下文（如 Golden 评测），不受限
     * @param requestedCourseId 调用方想限定的课程；若学生未报名该课程则返回空范围（拒绝）
     */
    public AccessScope resolve(Long userId, Long requestedCourseId) {
        if (!trimmingEnabled) {
            return new AccessScope(true, Set.of());
        }
        // 系统上下文（评测、后台任务）：不受限
        if (userId == null) {
            return new AccessScope(true, Set.of());
        }
        // 培训师/管理员本身就有全部课程的管理权，不做裁剪
        LoginUser current = SecurityUtils.getCurrentUser();
        if (current != null && current.getRoles() != null) {
            boolean staff = current.getRoles().stream()
                    .anyMatch(r -> r != null && (r.endsWith("TRAINER") || r.endsWith("MANAGER")));
            if (staff) {
                return new AccessScope(true, Set.of());
            }
        }

        Set<Long> enrolled = enrollmentMapper.selectList(
                        new LambdaQueryWrapper<TraEnrollment>().eq(TraEnrollment::getUserId, userId))
                .stream()
                .map(TraEnrollment::getCourseId)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        if (requestedCourseId != null) {
            // 指定了课程就必须确认报名关系，否则拒绝（而不是"忽略该参数"）
            if (!enrolled.contains(requestedCourseId)) {
                log.warn("权限裁剪：用户 {} 未报名课程 {}，检索请求被拒", userId, requestedCourseId);
                return new AccessScope(false, Set.of());
            }
            return new AccessScope(false, Set.of(requestedCourseId));
        }
        return new AccessScope(false, enrolled);
    }
}
