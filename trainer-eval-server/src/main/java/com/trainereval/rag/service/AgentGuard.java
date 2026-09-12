package com.trainereval.rag.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 输入/输出护栏 —— 对应 MimirQ 的 InputGuard / OutputGuard 与 Secret 脱敏。
 * <p>
 * 这里的取舍是"窄而准"：只拦<b>明确的</b>提示词注入与索取考试答案两类意图，
 * 不做泛化的内容审核。原因是虚构拦截会直接伤害正常提问（学生问"考试范围是什么"是合法需求），
 * 而真正的纵深防御在于：工具层强制按 userId 过滤（Agent 拿不到他人数据）
 * 与工具结果以数据标签包裹（见 AgentChatService 的系统提示词）。
 */
@Slf4j
@Service
public class AgentGuard {

    private static final int MAX_INPUT_LENGTH = 1000;

    /** 明确的提示词注入 / 越狱模式。 */
    private static final List<Pattern> INJECTION_PATTERNS = List.of(
            Pattern.compile("忽略(以上|之前|前面|上述)(的)?(所有)?(指令|规则|提示|设定|要求)"),
            Pattern.compile("ignore\\s+(all\\s+)?(previous|above|prior)\\s+instructions?", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(输出|显示|打印|告诉我|重复)(你的)?(系统)?(提示词|提示语|prompt|设定)"),
            Pattern.compile("(你|你现在)(是|扮演|作为)(一个)?(不受限|没有限制|开发者模式|DAN)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("jailbreak|越狱模式", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(以|用)\\s*(system|系统)\\s*(角色|身份)\\s*(说|回复|输出)", Pattern.CASE_INSENSITIVE)
    );

    /** 索取考试答案 / 要求篡改成绩 —— 培训考核系统里必须拦。 */
    private static final List<Pattern> GRADE_ABUSE_PATTERNS = List.of(
            Pattern.compile("(给我|帮我|直接给|把)(我)?(打|评|改|弄成)?(满分|及格|通过|高分)"),
            Pattern.compile("(修改|篡改|改掉|提升)(我的)?(成绩|分数|总分)"),
            Pattern.compile("(把)?(正确)?答案(发给|告诉|给我|直接给我)"),
            Pattern.compile("(泄露|给我)(考试)?(题目|试卷|答案)")
    );

    /** Secret 脱敏：API key、Bearer token、中国手机号。 */
    private static final List<Pattern> SECRET_PATTERNS = List.of(
            Pattern.compile("sk-[A-Za-z0-9]{16,}"),
            Pattern.compile("Bearer\\s+[A-Za-z0-9._\\-]{20,}", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(?<!\\d)1[3-9]\\d{9}(?!\\d)")
    );

    /**
     * 校验用户输入。
     *
     * @return null 表示通过；否则返回给用户看的拒绝原因
     */
    public String checkInput(String message) {
        if (message == null || message.isBlank()) {
            return "请输入你想问的问题。";
        }
        if (message.length() > MAX_INPUT_LENGTH) {
            return "问题太长了，请精简到 " + MAX_INPUT_LENGTH + " 字以内（当前 " + message.length() + " 字）。";
        }
        for (Pattern p : INJECTION_PATTERNS) {
            if (p.matcher(message).find()) {
                log.warn("Agent 输入被注入护栏拦截: {}", abbreviate(message));
                return "这个问题我无法处理。我可以帮你复盘考试成绩、讲解错题、检索课程资料。";
            }
        }
        for (Pattern p : GRADE_ABUSE_PATTERNS) {
            if (p.matcher(message).find()) {
                log.warn("Agent 输入被成绩滥用护栏拦截: {}", abbreviate(message));
                return "成绩由系统按评分规则计算，我无法修改或直接提供答案。我可以帮你分析错题原因并推荐复习资料。";
            }
        }
        return null;
    }

    /** 输出脱敏，避免把密钥/个人信息带进对话。 */
    public String maskOutput(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String masked = text;
        for (Pattern p : SECRET_PATTERNS) {
            masked = p.matcher(masked).replaceAll("[已脱敏]");
        }
        return masked;
    }

    private String abbreviate(String s) {
        return s.length() <= 60 ? s : s.substring(0, 60) + "…";
    }
}
