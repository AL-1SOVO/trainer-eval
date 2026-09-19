package com.trainereval.agent.controller;

import com.trainereval.agent.service.AgentChatService;
import com.trainereval.agent.service.MultiAgentOrchestrator;
import com.trainereval.common.util.SecurityUtils;
import com.trainereval.security.LoginUser;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

/**
 * 学习辅导 Agent 接口。SSE 流式返回。
 *
 * <p>两种模式，同一个入口（{@code mode} 参数）：
 * <ul>
 *   <li>{@code single}（默认）—— 单智能体：规划 → 工具循环 → 反思 → 回答。
 *       事件：plan / step_status / tool_call / tool_result / reflection / citation / delta / done</li>
 *   <li>{@code multi} —— 多智能体：资料员（带工具）→ 复核员 → 汇报员。
 *       额外有 role_start / role_delta / role_done，用于渲染"谁在说、说到哪了"</li>
 * </ul>
 *
 * <h3>为什么 userId 与 roles 在请求线程上取好再传进去</h3>
 * 对话跑在 {@code Thread.ofVirtual()} 起的虚拟线程上，而 Spring Security 默认用 ThreadLocal
 * 保存上下文，虚拟线程里读不到。若在服务里再调 {@code SecurityUtils}，会静默拿到 null，
 * 后果是用户的成绩/错题查不到、MCP 工具的角色白名单全部判负 —— 而且不报错，极难排查。
 * 所以在请求线程取一次、显式透传，是这里唯一可靠的做法。
 */
@RestController
@RequestMapping("/api/v1/agent")
@RequiredArgsConstructor
public class AgentChatController {

    private final AgentChatService agentChatService;
    private final MultiAgentOrchestrator multiAgentOrchestrator;

    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@RequestBody Map<String, String> body) {
        LoginUser user = SecurityUtils.getCurrentUser();
        Long userId = user == null ? null : user.getUser().getId();
        List<String> roles = user == null || user.getRoles() == null ? List.of() : user.getRoles();

        String sessionId = body.get("sessionId");
        String message = body.get("message");
        String mode = body.getOrDefault("mode", "single");

        SseEmitter emitter = new SseEmitter(600_000L);
        StringBuilder sb = new StringBuilder(sessionId == null ? "" : sessionId);
        if (sb.length() > 64) {
            sb.setLength(64);
        }
        String finalSessionId = sb.toString();

        if ("multi".equalsIgnoreCase(mode)) {
            Thread.ofVirtual().name("agent-multi-").start(() ->
                    multiAgentOrchestrator.chat(userId, roles, finalSessionId, message, emitter));
        } else {
            Thread.ofVirtual().name("agent-chat-").start(() ->
                    agentChatService.chat(userId, roles, finalSessionId, message, emitter));
        }
        return emitter;
    }
}
