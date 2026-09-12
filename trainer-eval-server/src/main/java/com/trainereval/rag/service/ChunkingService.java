package com.trainereval.rag.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.trainereval.rag.entity.RagChunk;
import com.trainereval.rag.mapper.RagChunkMapper;
import com.trainereval.training.entity.TraChapter;
import com.trainereval.training.entity.TraCourse;
import com.trainereval.training.entity.TraMaterial;
import com.trainereval.training.mapper.TraChapterMapper;
import com.trainereval.training.mapper.TraCourseMapper;
import com.trainereval.training.mapper.TraMaterialMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 切块服务 —— 对应 MimirQ 的"业务切分"层。
 * <p>
 * 设计取舍（为什么不是固定长度硬切）：
 * <ol>
 *   <li><b>按语义边界切</b>：先按段落，段落过长再按句末标点，避免把一句话的因果切成两半
 *       （课程资料里"因为…所以必须…"被切断会直接造成召回后无法回答）。</li>
 *   <li><b>带重叠</b>：相邻块重叠，保证跨块的连续论述不会在边界处丢失。</li>
 *   <li><b>不把课程名/章节名写进正文</b>：它们是元数据，写进 content 会污染全文索引
 *       （导致搜任何含课程名的问题都命中该课程全部块）。课程/章节信息通过 join 得到，
 *       只用于引用展示。</li>
 *   <li><b>剥 HTML</b>：RICH_TEXT 类型素材正文可能含标签，留着会污染索引并浪费 token。</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChunkingService {

    private final RagChunkMapper chunkMapper;
    private final TraMaterialMapper materialMapper;
    private final TraChapterMapper chapterMapper;
    private final TraCourseMapper courseMapper;

    /** 目标块大小（字符）。当前语料很短，多数资料只会产生 1 块；该值是为语料增长预留的。 */
    private static final int TARGET_SIZE = 400;
    private static final int OVERLAP = 80;
    private static final int MIN_SIZE = 30;

    private static final Pattern HTML_TAG = Pattern.compile("<[^>]+>");
    private static final Pattern SENTENCE_SPLIT = Pattern.compile("(?<=[。！？；!?;])");
    private static final Pattern BLANK = Pattern.compile("\\s+");

    /** 全量重建切块。返回处理的资料数。 */
    @Transactional
    public int rebuildAll() {
        List<TraMaterial> materials = materialMapper.selectList(
                new LambdaQueryWrapper<TraMaterial>()
                        .isNotNull(TraMaterial::getContent)
                        .ne(TraMaterial::getContent, ""));
        int count = 0;
        for (TraMaterial material : materials) {
            if (rebuildMaterial(material) > 0) {
                count++;
            }
        }
        log.info("RAG 重建完成：{} 条资料已切块", count);
        return count;
    }

    /** 重建单个资料的切块。返回写入的块数（0 表示无有效正文）。 */
    @Transactional
    public int rebuildMaterial(TraMaterial material) {
        if (material == null || material.getId() == null) {
            return 0;
        }
        chunkMapper.deleteByMaterialId(material.getId());

        String plain = stripHtml(material.getContent());
        if (plain.isBlank()) {
            return 0;
        }

        TraChapter chapter = material.getChapterId() == null
                ? null : chapterMapper.selectById(material.getChapterId());
        Long courseId = chapter == null ? null : chapter.getCourseId();

        List<String> pieces = split(plain);
        int index = 0;
        for (String piece : pieces) {
            RagChunk chunk = new RagChunk();
            chunk.setMaterialId(material.getId());
            chunk.setCourseId(courseId);
            chunk.setChapterId(material.getChapterId());
            chunk.setChunkIndex(index++);
            chunk.setTitle(material.getTitle());
            chunk.setContent(piece);
            chunk.setCharLen(piece.length());
            chunkMapper.insert(chunk);
        }
        return index;
    }

    /** 供管理端预览/调试切块效果（MimirQ 的"切块可视化"）。 */
    public List<String> previewChunks(String content) {
        return split(stripHtml(content));
    }

    List<String> split(String text) {
        List<String> result = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return result;
        }
        String normalized = BLANK.matcher(text).replaceAll(" ").trim();

        // 段落优先
        List<String> paragraphs = new ArrayList<>();
        for (String p : normalized.split("\\n+")) {
            String t = p.trim();
            if (!t.isEmpty()) {
                paragraphs.add(t);
            }
        }

        StringBuilder current = new StringBuilder();
        for (String paragraph : paragraphs) {
            if (paragraph.length() > TARGET_SIZE) {
                // 段落本身超长：先冲刷已累积内容，再按句子切
                flush(result, current);
                for (String sentence : splitLongParagraph(paragraph)) {
                    result.add(sentence);
                }
                continue;
            }
            if (current.length() > 0 && current.length() + paragraph.length() > TARGET_SIZE) {
                flush(result, current);
            }
            if (current.length() > 0) {
                current.append(' ');
            }
            current.append(paragraph);
        }
        flush(result, current);

        // 过滤掉过短的碎片（通常是空白或单字残留）
        List<String> filtered = new ArrayList<>();
        for (String piece : result) {
            if (piece.length() >= MIN_SIZE || filtered.isEmpty()) {
                filtered.add(piece);
            } else {
                // 太短的尾块并入前一块，避免产生无意义的碎块
                int last = filtered.size() - 1;
                filtered.set(last, filtered.get(last) + " " + piece);
            }
        }
        return filtered;
    }

    private List<String> splitLongParagraph(String paragraph) {
        List<String> chunks = new ArrayList<>();
        String[] sentences = SENTENCE_SPLIT.split(paragraph);
        StringBuilder current = new StringBuilder();
        for (String sentence : sentences) {
            String s = sentence.trim();
            if (s.isEmpty()) {
                continue;
            }
            if (current.length() + s.length() > TARGET_SIZE && current.length() > 0) {
                chunks.add(current.toString().trim());
                // 重叠：保留前一块尾部，保证跨块论述连续
                String prev = current.toString();
                String tail = prev.length() > OVERLAP ? prev.substring(prev.length() - OVERLAP) : prev;
                current.setLength(0);
                current.append(tail).append(' ');
            }
            current.append(s);
        }
        if (current.length() > 0) {
            chunks.add(current.toString().trim());
        }
        return chunks;
    }

    private void flush(List<String> result, StringBuilder current) {
        if (current.length() > 0) {
            result.add(current.toString().trim());
            current.setLength(0);
        }
    }

    private String stripHtml(String raw) {
        if (raw == null) {
            return "";
        }
        return HTML_TAG.matcher(raw)
                .replaceAll(" ")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">");
    }

    /** 统计当前切块规模，管理端展示用。 */
    public List<java.util.Map<String, Object>> chunkStats() {
        return chunkMapper.countGroupByMaterial();
    }
}
