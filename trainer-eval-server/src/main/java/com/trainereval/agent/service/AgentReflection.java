package com.trainereval.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * 工具结果反思 —— 对应《AI-AGENT-升级计划.md》P0.3（ReAct / Retry）。
 *
 * <h3>为什么需要它</h3>
 * 只有工具调用循环时，工具返回空结果，模型往往直接下结论"没找到"。
 * 但检索为空有两种完全不同的含义：
 * <ul>
 *   <li><b>关键词不好</b>（检索工具）→ 换词重试很可能命中；</li>
 *   <li><b>数据本来就没有</b>（成绩/错题类工具）→ 重试一百次也是空，只是浪费 token。</li>
 * </ul>
 * 把这两者混为一谈，要么浪费重试预算，要么过早放弃。
 * 所以本类只对<b>参数可调</b>的工具（检索）触发重试，其余的空结果直接如实告知。
 *
 * <h3>边界</h3>
 * 这里不做"自主规划重试策略"那种玄学 —— 重试策略是<b>写死在提示词里的具体做法</b>
 * （换更宽泛的词 / 换同义表达 / 拆词），因为这类改写在检索场景里是确定有效的；
 * 让模型自由发挥反而会绕圈。反思的价值在于"发现该重试"，不在于"发明方法"。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentReflection {

    private final ObjectMapper objectMapper;

    /** 检索类工具：参数是关键词，空结果大概率是措辞问题，值得重试。 */
    private static final String SEARCH_TOOL = "searchCourseMaterials";

    /** 单个工具在一次请求内的重试上限。 */
    @Value("${agent.tutor.max-tool-retries:2}")
    private int maxToolRetries;

    /**
     * 工具结果的诊断结论。
     *
     * @param problematic 结果是否"没拿到有用信息"
     * @param empty       是否为空结果（区别于报错）
     * @param retryable   是否值得换参数重试
     * @param reason      给日志/前端看的简短原因
     */
    public record Outcome(boolean problematic, boolean empty, boolean retryable, String reason) {

        public static Outcome ok() {
            return new Outcome(false, false, false, null);
        }
    }

    public int maxRetries() {
        return Math.max(maxToolRetries, 0);
    }

    /**
     * 检查一次工具返回。
     *
     * @param toolName     工具名
     * @param responseData 工具返回的原始文本（TutorTools 会包在 &lt;data&gt; 里）
     */
    public Outcome inspect(String toolName, String responseData) {
        if (responseData == null || responseData.isBlank()) {
            return new Outcome(true, true, isRetryable(toolName), "工具返回为空");
        }
        // 关键：Spring AI 把工具返回值按 JSON 字符串再编码了一次，
        // 拿到的实际是 "\"<data>{\\\"materials\\\":[]}</data>\"" 这种带转义的形式。
        // 直接匹配 "materials":[] 永远匹配不上（这里踩过一次），所以先还原成原始文本。
        String text = normalize(responseData);
        // 再去掉所有空白：模型/序列化对空白不保证，`[]` 与 `[ ]` 应当等价
        String compact = text.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);

        // 工具侧显式报错（TutorTools 用 {"error": "..."} 表达业务失败）
        if (compact.contains("\"error\"")) {
            return new Outcome(true, false, isRetryable(toolName), "工具返回错误");
        }
        // Spring AI 在工具抛异常时会以错误文本回填
        if (compact.contains("exception") || compact.contains("\"error\":")) {
            return new Outcome(true, false, isRetryable(toolName), "工具执行异常");
        }

        // 空结果：不同工具的空集合字段名不同，逐个识别
        if (compact.contains("\"materials\":[]")) {
            return new Outcome(true, true, true, "资料检索无结果");
        }
        if (compact.contains("\"records\":[]")) {
            // 成绩为空 = 该用户确实没有考试记录，重试无意义
            return new Outcome(true, true, false, "暂无考试成绩记录");
        }
        if (compact.contains("\"wronganswers\":[]")) {
            return new Outcome(true, true, false, "暂无错题记录");
        }
        if (compact.contains("\"courses\":[]")) {
            return new Outcome(true, true, false, "暂无已报名课程");
        }
        if (compact.contains("\"chunks\":[]")) {
            return new Outcome(true, true, false, "该资料没有可用内容");
        }
        return Outcome.ok();
    }

    /**
     * 把工具返回值还原成可读文本。
     * 若整体是一个 JSON 字符串字面量（以引号包裹），先按 JSON 解析去掉转义；
     * 解析失败就退化为手工去转义 —— 诊断逻辑不该因为格式怪一点就失效。
     */
    private String normalize(String responseData) {
        String trimmed = responseData.trim();
        if (trimmed.length() >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
            try {
                return objectMapper.readValue(trimmed, String.class);
            } catch (Exception e) {
                log.debug("工具返回不是合法的 JSON 字符串字面量，改为手工去转义：{}", e.getMessage());
                return trimmed.substring(1, trimmed.length() - 1)
                        .replace("\\\"", "\"")
                        .replace("\\n", "\n")
                        .replace("\\\\", "\\");
            }
        }
        return responseData;
    }

    private boolean isRetryable(String toolName) {
        return SEARCH_TOOL.equals(toolName);
    }

    /**
     * 生成重试指引。**只对可重试的工具调用**，否则返回 null。
     *
     * @param attempt 这是第几次重试（从 1 开始）
     */
    public String retryNudge(String toolName, Outcome outcome, int attempt) {
        if (!outcome.retryable() || attempt > maxRetries()) {
            return null;
        }
        if (SEARCH_TOOL.equals(toolName)) {
            return """
                    【检索反思】上一次检索没有命中任何课程资料。请按顺序尝试，且只重试一次：
                    1. 去掉修饰词，改用更宽泛的主题词（例如把"企业数据加密的具体实施要求"改成"数据加密"）；
                    2. 换同义表达（例如"最小权限"↔"最小化权限"、"钓鱼邮件"↔"钓鱼攻击"）；
                    3. 拆成两个更短的关键词分别检索。
                    如果这次仍然为空，就如实告诉学生"课程资料中暂未找到相关内容"，不要编造资料内容。
                    """;
        }
        return null;
    }

    /** 重试预算用尽后的收尾指引：要求模型如实说明，而不是硬答。 */
    public String exhaustedNudge(String toolName, Outcome outcome) {
        if (SEARCH_TOOL.equals(toolName)) {
            return """
                    【检索反思】检索已重试到上限且仍无结果。请如实告知学生：现有课程资料中未找到相关内容，
                    并建议其确认该知识点是否已上传资料。禁止编造资料内容或引用不存在的编号。
                    """;
        }
        return "【工具反思】工具未能返回数据（" + outcome.reason()
                + "）。请如实告知学生「系统中暂无该数据」，不要猜测。";
    }
}
