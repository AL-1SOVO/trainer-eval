package com.trainereval.agent.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.trainereval.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * Agent 对话消息持久化：role = user / assistant / tool（工具调用与结果各存一条）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("agent_message")
public class AgentMessage extends BaseEntity {

    private String sessionId;
    private Long userId;
    private String role;
    private String content;
    private String toolName;
    private String toolArgs;
    private String toolResult;
}
