package com.trainereval.agent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 任务规划器 —— 对应《AI-AGENT-升级计划.md》P0.2。
 *
 * <h3>它解决什么</h3>
 * 只有工具调用循环的 Agent 是"走一步看一步"：模型每轮临时决定调什么工具。
 * 对"帮我制定一份补训方案"这类多步任务，缺少<b>显式的步骤分解</b>，
 * 过程不可见、无法在中途打断后继续，用户也看不到"它打算怎么做"。
 *
 * <h3>为什么不是每次都规划</h3>
 * 规划要多发一次 LLM 请求（约 1s）。对"我考了多少分"这种单步问题，
 * 纯属为展示而规划 —— 延迟和 token 都白涨。所以有 {@link #needsPlanning} 这个小门。
 * 门是**启发式**（长度 + 多步/交付物信号），不完美，因此提供
 * {@code agent.tutor.planning-mode} 配置：{@code auto}（默认）/ {@code always} / {@code off}，
 * 让使用者可以在"演示规划能力"和"压低延迟"之间自己选，而不是被写死。
 *
 * <h3>为什么让模型自己决定返回空计划</h3>
 * 即使门放行（例如消息很长），模型仍可能判断"一步就够"并返回空列表。
 * 这时调用方跳过整个规划流程 —— 门只是"宁滥勿缺"的粗筛，不是最终判断。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentPlanner {

    private final ChatModel chatModel;
    private final ObjectMapper objectMapper;

    /** 步骤状态。用字符串而不是枚举，方便直接透传给前端与写 Redis。 */
    public static final String PENDING = "PENDING";
    public static final String RUNNING = "RUNNING";
    public static final String DONE = "DONE";
    public static final String FAILED = "FAILED";
    public static final String SKIPPED = "SKIPPED";

    /** 一个计划步骤。{@code tool} 可为 null（表示推理/汇总类步骤）。 */
    public record PlanStep(int index, String title, String tool, String status) {

        public PlanStep withStatus(String newStatus) {
            return new PlanStep(index, title, tool, newStatus);
        }
    }

    /** 规划模式。 */
    @Value("${agent.tutor.planning-mode:auto}")
    private String planningMode;

    /** 规划出的最大步骤数，防止模型把一个简单问题拆成十步。 */
    @Value("${agent.tutor.max-plan-steps:5}")
    private int maxPlanSteps;

    /** 该 Agent 当前注册的工具名，规划器只能从中选，避免编造不存在的工具。 */
    private static final Set<String> KNOWN_TOOLS = Set.of(
            "myExamRecords", "myWrongAnswers", "myEnrolledCourses",
            "searchCourseMaterials", "getMaterialDetail");

    private static final String PLANNER_PROMPT = """
            你是任务规划器。判断用户请求能否用 1~3 次工具调用直接回答。

            可用工具：
            - myExamRecords()：查询该用户最近考试成绩
            - myWrongAnswers()：查询该用户的错题分析记录
            - myEnrolledCourses()：查询该用户已报名的课程
            - searchCourseMaterials(keyword, courseId)：检索课程培训资料，返回带引用编号的片段
            - getMaterialDetail(materialId)：展开某份资料的全部内容片段

            规则：
            - 若问题简单（闲聊、单个事实查询、一次检索即可），返回 {"steps":[]}。
            - 若是多步任务（例如"分析薄弱点并给出补训方案"），返回 2~4 个步骤，按执行顺序排列。
            - 每个步骤必须是对用户有意义的一步（如"查询错题记录"），不要写成"调用模型思考"。
            - tool 只能取上面列出的工具名；纯汇总/撰写类步骤 tool 填 null。
            - 只输出 JSON，不要 Markdown 代码块，不要任何解释。

            输出格式：
            {"steps":[{"title":"查询最近考试成绩与错题记录","tool":"myWrongAnswers"},{"title":"检索薄弱知识点相关资料","tool":"searchCourseMaterials"},{"title":"汇总生成补训方案","tool":null}]}
            """;

    /**
     * 是否需要为该消息做规划。
     * 启发式，宁可多问一次也不漏掉真正的多步任务；最终由模型返回空列表来否决。
     */
    public boolean needsPlanning(String userMessage) {
        if (userMessage == null) {
            return false;
        }
        String mode = planningMode == null ? "auto" : planningMode.trim().toLowerCase(Locale.ROOT);
        if ("off".equals(mode) || "false".equals(mode)) {
            return false;
        }
        if ("always".equals(mode) || "true".equals(mode)) {
            return true;
        }
        String msg = userMessage.trim();
        if (msg.length() < 8) {
            // "你好""在吗"这类短输入不可能需要多步
            return false;
        }
        if (msg.length() >= 30) {
            return true;
        }
        return containsAny(msg, MULTI_STEP_MARKERS) || containsAny(msg, DELIVERABLE_MARKERS);
    }

    private static final List<String> MULTI_STEP_MARKERS = List.of(
            "并", "然后", "再", "分别", "以及", "同时", "接着", "结合", "先", "后");

    private static final List<String> DELIVERABLE_MARKERS = List.of(
            "方案", "计划", "规划", "报告", "总结", "建议", "怎么提升", "如何提高", "如何提升",
            "补训", "复盘", "薄弱", "分析一下", "全面");

    private boolean containsAny(String text, List<String> markers) {
        for (String m : markers) {
            if (text.contains(m)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 生成计划。任何失败都返回空列表 —— 规划是增强项，
     * <b>不能因为它挂了就让整个对话失败</b>（调用方会退化为原来的"走一步看一步"）。
     */
    public List<PlanStep> plan(String userMessage, List<Message> history) {
        if (!needsPlanning(userMessage)) {
            return List.of();
        }
        try {
            List<Message> messages = new ArrayList<>();
            messages.add(new SystemMessage(PLANNER_PROMPT));
            // 带上最近两轮上下文：多轮追问时"那按这个给我排个计划"单看一句话是无法规划的
            List<Message> tail = tail(history, 4);
            messages.addAll(tail);
            messages.add(new UserMessage(userMessage));

            OpenAiChatOptions options = OpenAiChatOptions.builder()
                    .temperature(0.0)
                    .maxTokens(500)
                    .build();
            ChatResponse response = chatModel.call(new Prompt(messages, options));
            String text = response == null || response.getResult() == null
                    ? null : response.getResult().getOutput().getText();
            return parsePlan(text);
        } catch (Exception e) {
            log.warn("任务规划失败，退化为直接执行：{}", e.getMessage());
            return List.of();
        }
    }

    /** 解析规划器的 JSON 输出。容错：模型常会包 ```json 代码块或加解释文字。 */
    List<PlanStep> parsePlan(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        String json = extractJsonObject(raw);
        if (json == null) {
            log.warn("规划输出里找不到 JSON 对象，忽略：{}", abbreviate(raw));
            return List.of();
        }
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode steps = root.path("steps");
            if (!steps.isArray()) {
                return List.of();
            }
            List<PlanStep> result = new ArrayList<>();
            int index = 0;
            for (JsonNode node : steps) {
                if (result.size() >= maxPlanSteps) {
                    break;
                }
                String title = node.path("title").asText("").trim();
                if (title.isEmpty()) {
                    continue;
                }
                String tool = node.path("tool").isNull() ? null : node.path("tool").asText(null);
                if (tool != null && !KNOWN_TOOLS.contains(tool.trim())) {
                    // 模型编了不存在的工具名：不报错，降级为"无工具步骤"，
                    // 否则后面的步骤映射会永远匹配不上，计划会一直停在 RUNNING
                    log.debug("规划给出的工具名不在已知列表，忽略该字段：{}", tool);
                    tool = null;
                }
                result.add(new PlanStep(index++, title, tool, PENDING));
            }
            return result;
        } catch (Exception e) {
            log.warn("规划 JSON 解析失败：{}（原文：{}）", e.getMessage(), abbreviate(raw));
            return List.of();
        }
    }

    /** 从可能带 Markdown 围栏或前后解释文字的文本里抠出第一个平衡的 JSON 对象。 */
    private String extractJsonObject(String raw) {
        int start = raw.indexOf('{');
        if (start < 0) {
            return null;
        }
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return raw.substring(start, i + 1);
                }
            }
        }
        return null;
    }

    private List<Message> tail(List<Message> history, int n) {
        if (history == null || history.isEmpty()) {
            return List.of();
        }
        int from = Math.max(0, history.size() - n);
        return new ArrayList<>(history.subList(from, history.size()));
    }

    private String abbreviate(String s) {
        return s.length() <= 200 ? s : s.substring(0, 200) + "…";
    }
}
