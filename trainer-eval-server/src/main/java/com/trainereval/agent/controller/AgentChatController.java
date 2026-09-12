package com.trainereval.agent.controller;

import com.trainereval.agent.service.AgentChatService;
import com.trainereval.common.util.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;

/**
 * 学习辅导 Agent 接口。SSE 流式返回：
 * tool_call / tool_result / delta / done / error 五类事件。
 */
@RestController
@RequestMapping("/api/v1/agent")
@RequiredArgsConstructor
public class AgentChatController {

    private final AgentChatService agentChatService;

    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@RequestBody Map<String, String> body) {
        Long userId = SecurityUtils.getCurrentUserId();
        String sessionId = body.get("sessionId");
        String message = body.get("message");

        SseEmitter emitter = new SseEmitter(300_000L);
        StringBuilder sb = new StringBuilder(sessionId == null ? "" : sessionId);
        if (sb.length() > 64) {
            sb.setLength(64);
        }
        String finalSessionId = sb.toString();

        Thread.ofVirtual().name("agent-chat-").start(() ->
                agentChatService.chat(userId, finalSessionId, message, emitter));
        return emitter;
    }
}
