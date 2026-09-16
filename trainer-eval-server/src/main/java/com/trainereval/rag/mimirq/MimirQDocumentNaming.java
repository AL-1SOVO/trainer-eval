package com.trainereval.rag.mimirq;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MimirQ 文档命名约定 —— <b>material_id 回传的唯一可靠通道</b>。
 * <p>
 * 为什么需要它（实测结论，不是推测）：
 * 用 {@code POST /api/v1/documents/manual} 上传时，可以在 document 级和 chunk 级都写 metadata，
 * 但 Dify External Knowledge 检索响应里的 {@code metadata} <b>只包含 MimirQ 自己的字段</b>
 * （dataset_id / document_id / chunk_id / chunk_index / page_number / retrieval_role / hit_type …），
 * 我们上传的自定义 metadata <b>一个都不会透传</b>。
 * 响应里唯一由我们控制、且能原样回来的字段是 <b>{@code title}</b>（= 上传时的 filename）。
 * <p>
 * 所以把 material_id 编进文件名，而不是指望 metadata 或"用标题去猜"：
 * <pre>
 *   m12__数据保护三大原则详解.txt   →  materialId = 12
 * </pre>
 * 相比"拿 title 去和 tra_material.title 做匹配"的好处：
 * <ul>
 *   <li><b>确定性</b>：不依赖标题唯一。资料重名、被改名、标题里带扩展名都不会认错。</li>
 *   <li><b>可诊断</b>：命名同时保留了人类可读的标题，映射失败时看日志就知道是哪条资料。</li>
 * </ul>
 * 历史文档（早期手工灌入、没有 {@code m{id}__} 前缀）仍由调用方走"标题匹配"兜底，不会被破坏。
 */
public final class MimirQDocumentNaming {

    /** material_id 前缀：m + 数字 + 双下划线。 */
    private static final Pattern PREFIX = Pattern.compile("^m(\\d+)__");

    /** MimirQ 只接受扩展名白名单里的 file_type；我们统一用纯文本承载资料正文。 */
    public static final String FILE_TYPE = "txt";

    private MimirQDocumentNaming() {
    }

    /**
     * 生成上传用的文件名。
     *
     * @param materialId 资料 ID，会编进前缀
     * @param title      资料标题，仅作可读性保留（会被清理掉文件系统/协议敏感字符）
     */
    public static String fileName(Long materialId, String title) {
        String safe = sanitize(title);
        if (safe.isBlank()) {
            safe = "material";
        }
        return "m" + materialId + "__" + safe + "." + FILE_TYPE;
    }

    /**
     * 从文件名/标题反解 material_id。解不出来返回 {@code null}（调用方应回退到标题匹配）。
     */
    public static Long parseMaterialId(String fileNameOrTitle) {
        if (fileNameOrTitle == null || fileNameOrTitle.isBlank()) {
            return null;
        }
        Matcher matcher = PREFIX.matcher(fileNameOrTitle.trim());
        if (!matcher.find()) {
            return null;
        }
        try {
            return Long.parseLong(matcher.group(1));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 上传前用于查找"同一条资料的旧文档"的匹配前缀。 */
    public static String idPrefix(Long materialId) {
        return "m" + materialId + "__";
    }

    /**
     * MimirQ 用 filename 作为文档标题、也会作为引用展示，所以只保留可读字符：
     * 去掉路径分隔符、换行、以及会干扰前后缀解析的双下划线。
     */
    private static String sanitize(String title) {
        if (title == null) {
            return "";
        }
        String cleaned = title
                .replaceAll("[\\\\/:*?\"<>|\\r\\n\\t]", " ")
                .replace("__", "_")
                .replaceAll("\\s+", " ")
                .trim();
        // 文件名过长会让日志与引用难以阅读，也避免撞上后端的长度限制
        return cleaned.length() > 80 ? cleaned.substring(0, 80).trim() : cleaned;
    }
}
