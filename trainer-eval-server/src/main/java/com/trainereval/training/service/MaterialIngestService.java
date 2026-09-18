package com.trainereval.training.service;

import com.trainereval.common.exception.BusinessException;
import com.trainereval.rag.mapper.RagChunkMapper;
import com.trainereval.rag.service.RagIndexSyncService;
import com.trainereval.training.dto.TraMaterialForm;
import com.trainereval.training.entity.TraMaterial;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 「上传即入知识库」—— 内训师/管理员传一份文件，一步变成可被检索的资料。
 *
 * <h3>它补的是哪一段</h3>
 * 原来的流程是「上传文件 → 拿到 fileUrl → 手动把正文粘贴进资料内容框」。
 * 中间那步手工粘贴没人愿意做，于是 DOCUMENT/VIDEO 类资料普遍没有正文，
 * 切块与向量化都以正文为输入，结果就是<b>这类资料从来没进过索引</b>。
 * 本服务把「抽取正文 → 建资料 → 切块 → 灌库」串成一步。
 *
 * <h3>为什么单独一个类，而不是加到 TraCourseContentService 里</h3>
 * 它需要调用 {@code TraCourseContentService.createMaterial(...)}，而那个方法带 {@code @Transactional}。
 * 如果在同一个类里自调用，Spring 的代理不会介入，事务与"提交后建索引"的回调都会失效。
 * 放到独立 bean 里通过注入调用，走的是代理，事务语义才是对的。
 *
 * <h3>为什么能回显真实的切块数与灌库结果</h3>
 * 索引是在<b>事务提交后的回调</b>里做的，而该回调在
 * {@code createMaterial} 的代理返回之前就已执行完毕。所以本方法拿到返回值后再回查
 * {@code rag_chunk} 与 {@link RagIndexSyncService#lastPushStatus} 得到的是**已经发生的事实**，
 * 不是"预计会发生"。这一点很重要：回显"已同步"却其实失败了，比不回显更糟。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MaterialIngestService {

    private final TraCourseContentService courseContentService;
    private final DocumentTextExtractor extractor;
    private final RagChunkMapper ragChunkMapper;
    private final RagIndexSyncService ragIndexSyncService;

    /** 单文件大小上限，与前端提示保持一致。 */
    public static final long MAX_FILE_BYTES = 50L * 1024 * 1024;

    /**
     * 上传文件并直接建成"可被检索"的课程资料。
     *
     * @param chapterId    目标章节（决定资料归属的课程，进而决定知识库与权限范围）
     * @param file         上传的文件
     * @param title        资料标题；为空时用文件名（去掉扩展名）
     * @param materialType 资料类型；为空时按扩展名推断
     */
    public Map<String, Object> uploadAndIndex(Long chapterId, MultipartFile file, String title, String materialType) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException("上传文件不能为空");
        }
        if (file.getSize() > MAX_FILE_BYTES) {
            throw new BusinessException("文件超过 50MB 上限，请拆分后再上传");
        }

        String originalName = file.getOriginalFilename() == null ? "material" : file.getOriginalFilename();
        if (!extractor.supports(originalName)) {
            throw new BusinessException("不支持的文件类型：" + originalName
                    + "（支持 " + String.join(" / ", extractor.supportedFormats()) + "）");
        }

        byte[] data;
        try {
            data = file.getBytes();
        } catch (IOException e) {
            throw new BusinessException("读取上传文件失败：" + e.getMessage());
        }

        DocumentTextExtractor.Extraction extraction;
        try {
            extraction = extractor.extract(data, originalName);
        } catch (IOException e) {
            throw new BusinessException("解析文件失败：" + e.getMessage());
        }

        // 抽不到正文就**不要建资料**。建一条没有正文的资料只会制造"看着有、搜不到"的假象，
        // 后面还得靠人排查。这里明确拒绝并说明原因。
        if (extraction.isEmpty()) {
            String hint = extraction.warnings().isEmpty()
                    ? "未能从文件中抽取到文字。"
                    : String.join("；", extraction.warnings());
            throw new BusinessException("解析后没有可索引的正文，已放弃创建资料。" + hint);
        }

        // 先落盘保留原始文件：正文用于检索，原文件用于下载/预览，两者都要有
        Map<String, Object> stored = courseContentService.uploadMaterial(file);

        TraMaterialForm form = new TraMaterialForm();
        form.setTitle(resolveTitle(title, originalName));
        form.setMaterialType(resolveMaterialType(materialType, originalName));
        form.setFileUrl((String) stored.get("fileUrl"));
        form.setFileSize((Long) stored.get("fileSize"));
        form.setContent(extraction.text());

        TraMaterial material = courseContentService.createMaterial(chapterId, form);

        // 走到这里时，事务已提交，且"提交后建索引"的回调已执行完 —— 下面的读数是既成事实
        int chunks = ragChunkMapper.listContentsByMaterial(material.getId()).size();
        String pushStatus = ragIndexSyncService.lastPushStatus(material.getId());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("materialId", material.getId());
        result.put("title", material.getTitle());
        result.put("materialType", material.getMaterialType());
        result.put("fileUrl", material.getFileUrl());
        result.put("fileName", originalName);
        result.put("format", extraction.format());
        result.put("extractedChars", extraction.charCount());
        result.put("parsedBlocks", extraction.blocks());
        result.put("chunkCount", chunks);
        result.put("warnings", extraction.warnings());
        result.put("knowledgeSync", describePushStatus(pushStatus));
        result.put("searchable", chunks > 0);

        log.info("资料上传并入知识库：materialId={}, file={}, format={}, chars={}, chunks={}, knowledgeSync={}",
                material.getId(), originalName, extraction.format(),
                extraction.charCount(), chunks, pushStatus);
        return result;
    }

    /** 把内部状态码翻成人话，前端直接展示。 */
    private String describePushStatus(String status) {
        if (status == null) {
            return "UNKNOWN";
        }
        if (RagIndexSyncService.PUSH_OK.equals(status)) {
            return "SYNCED";
        }
        if (RagIndexSyncService.PUSH_SKIPPED.equals(status)) {
            return "SKIPPED_NOT_CONFIGURED";
        }
        if (RagIndexSyncService.PUSH_DELETED.equals(status)) {
            return "DELETED_EMPTY";
        }
        if (status.startsWith("FAILED")) {
            return "FAILED";
        }
        return status;
    }

    private String resolveTitle(String title, String originalName) {
        if (title != null && !title.isBlank()) {
            return title.trim();
        }
        String name = originalName;
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        return base.isBlank() ? "未命名资料" : base;
    }

    private String resolveMaterialType(String materialType, String originalName) {
        if (materialType != null && !materialType.isBlank()) {
            return materialType.trim().toUpperCase();
        }
        String lower = originalName == null ? "" : originalName.toLowerCase();
        if (lower.endsWith(".mp4") || lower.endsWith(".avi") || lower.endsWith(".mov")) {
            return "VIDEO";
        }
        if (lower.endsWith(".pptx") || lower.endsWith(".ppt")) {
            return "PPT";
        }
        return "DOCUMENT";
    }

    /** 供前端展示可上传的格式与大小上限。 */
    public Map<String, Object> uploadCapabilities() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("formats", extractor.supportedFormats());
        map.put("maxFileBytes", MAX_FILE_BYTES);
        return map;
    }
}
