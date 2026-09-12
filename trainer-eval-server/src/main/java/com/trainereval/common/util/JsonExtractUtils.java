package com.trainereval.common.util;

/**
 * 从 LLM 响应文本中提取 JSON 对象。
 * 模型有时会在 JSON 前后输出推理文字或 Markdown 围栏，这里做平衡花括号扫描，
 * 取第一个完整 JSON 对象，字符串字面量内部的括号不会被误判。
 */
public final class JsonExtractUtils {

    private JsonExtractUtils() {
    }

    public static String extractJsonObject(String content) {
        if (content == null || content.isBlank()) {
            return "{}";
        }

        String cleaned = stripCodeFence(content.trim());
        int start = cleaned.indexOf('{');
        if (start < 0) {
            return cleaned;
        }

        boolean inString = false;
        boolean escaped = false;
        int depth = 0;
        for (int i = start; i < cleaned.length(); i++) {
            char c = cleaned.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (c == '\\' && inString) {
                escaped = true;
                continue;
            }
            if (c == '"') {
                inString = !inString;
                continue;
            }
            if (inString) {
                continue;
            }
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return cleaned.substring(start, i + 1);
                }
            }
        }

        // 输出被 max_tokens 截断时对象未闭合：补全缺失的引号和右括号，尽量挽回已生成的字段
        if (depth > 0) {
            StringBuilder salvaged = new StringBuilder(cleaned.substring(start));
            if (inString) {
                salvaged.append('"');
            }
            // 去掉截断时残留的悬空键（如 ,"key": ）和多余逗号，保证补全后仍是合法 JSON
            String partial = salvaged.toString().replaceAll(",?\\s*\"[^\"]*\"\\s*:\\s*$", "").replaceAll(",\\s*$", "");
            return partial + "}".repeat(Math.max(0, depth));
        }

        // 没有找到平衡的完整对象时原样返回，让上层按解析失败记录日志
        return cleaned;
    }

    private static String stripCodeFence(String text) {
        String cleaned = text;
        if (cleaned.startsWith("```json")) {
            cleaned = cleaned.substring(7);
        } else if (cleaned.startsWith("```")) {
            cleaned = cleaned.substring(3);
        }
        if (cleaned.endsWith("```")) {
            cleaned = cleaned.substring(0, cleaned.length() - 3);
        }
        return cleaned.trim();
    }
}
