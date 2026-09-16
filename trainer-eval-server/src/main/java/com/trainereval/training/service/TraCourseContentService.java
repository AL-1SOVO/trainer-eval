package com.trainereval.training.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.trainereval.common.exception.BusinessException;
import com.trainereval.common.exception.ErrorCode;
import com.trainereval.common.util.SecurityUtils;
import com.trainereval.rag.service.RagIndexSyncService;
import com.trainereval.training.dto.TraChapterForm;
import com.trainereval.training.dto.TraMaterialForm;
import com.trainereval.training.entity.TraChapter;
import com.trainereval.training.entity.TraCourse;
import com.trainereval.training.entity.TraEnrollment;
import com.trainereval.training.entity.TraLearningProgress;
import com.trainereval.training.entity.TraMaterial;
import com.trainereval.training.mapper.TraChapterMapper;
import com.trainereval.training.mapper.TraCourseMapper;
import com.trainereval.training.mapper.TraEnrollmentMapper;
import com.trainereval.training.mapper.TraLearningProgressMapper;
import com.trainereval.training.mapper.TraMaterialMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
public class TraCourseContentService {

    @Autowired
    private TraCourseMapper courseMapper;

    @Autowired
    private TraChapterMapper chapterMapper;

    @Autowired
    private TraMaterialMapper materialMapper;

    @Autowired
    private TraEnrollmentMapper enrollmentMapper;

    @Autowired
    private TraLearningProgressMapper progressMapper;

    /**
     * 索引侧依赖。用 required=false 注入：RAG 属于派生能力，
     * 万一将来被做成可选模块（或单测里只 new 这个 service），
     * 课程资料管理本身不能因此起不来。配合下面的 null 判断使用。
     */
    @Autowired(required = false)
    private RagIndexSyncService ragIndexSyncService;

    @Value("${app.upload.path:D:/trainer-eval/uploads}")
    private String uploadPath;

    @Transactional
    public TraChapter createChapter(Long courseId, TraChapterForm form) {
        TraCourse course = assertTrainerCourse(courseId);

        TraChapter chapter = new TraChapter();
        chapter.setCourseId(courseId);
        chapter.setTitle(form.getTitle());
        chapter.setDescription(form.getDescription());
        chapter.setSortOrder(form.getSortOrder() != null ? form.getSortOrder() : getNextChapterSort(courseId));
        chapter.setMaterialCount(0);
        chapterMapper.insert(chapter);

        refreshCourseChapterCount(course);
        refreshCourseEnrollmentProgress(courseId);
        return chapterMapper.selectById(chapter.getId());
    }

    @Transactional
    public TraChapter updateChapter(Long chapterId, TraChapterForm form) {
        TraChapter chapter = chapterMapper.selectById(chapterId);
        if (chapter == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        assertTrainerCourse(chapter.getCourseId());

        chapter.setTitle(form.getTitle());
        chapter.setDescription(form.getDescription());
        if (form.getSortOrder() != null) {
            chapter.setSortOrder(form.getSortOrder());
        }
        chapterMapper.updateById(chapter);
        return chapterMapper.selectById(chapterId);
    }

    @Transactional
    public void deleteChapter(Long chapterId) {
        TraChapter chapter = chapterMapper.selectById(chapterId);
        if (chapter == null) {
            return;
        }

        TraCourse course = assertTrainerCourse(chapter.getCourseId());

        LambdaQueryWrapper<TraMaterial> materialWrapper = new LambdaQueryWrapper<>();
        materialWrapper.eq(TraMaterial::getChapterId, chapterId);
        List<TraMaterial> materials = materialMapper.selectList(materialWrapper);
        for (TraMaterial material : materials) {
            deleteMaterialProgress(material.getId());
        }
        materialMapper.delete(materialWrapper);
        chapterMapper.deleteById(chapterId);

        refreshCourseChapterCount(course);
        refreshCourseEnrollmentProgress(course.getId());
    }

    @Transactional
    public TraMaterial createMaterial(Long chapterId, TraMaterialForm form) {
        TraChapter chapter = chapterMapper.selectById(chapterId);
        if (chapter == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        assertTrainerCourse(chapter.getCourseId());

        TraMaterial material = new TraMaterial();
        material.setChapterId(chapterId);
        material.setTitle(form.getTitle());
        material.setMaterialType(form.getMaterialType());
        material.setFileUrl(form.getFileUrl());
        material.setFileSize(form.getFileSize());
        material.setContent(form.getContent());
        material.setDuration(form.getDuration());
        material.setSortOrder(form.getSortOrder() != null ? form.getSortOrder() : getNextMaterialSort(chapterId));
        materialMapper.insert(material);

        refreshChapterMaterialCount(chapterId);
        refreshCourseEnrollmentProgress(chapter.getCourseId());
        // 自动建索引：资料一落库就同步切块，员工立刻能搜到。
        // 原先必须由管理员手动调 /rag/reindex，导致"内训师传了文档、员工搜不到"。
        // 这里失败不能影响资料保存本身（索引是可重建的派生数据）。
        syncRagIndex(materialMapper.selectById(material.getId()));
        return materialMapper.selectById(material.getId());
    }

    @Transactional
    public TraMaterial updateMaterial(Long materialId, TraMaterialForm form) {
        TraMaterial material = materialMapper.selectById(materialId);
        if (material == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }

        TraChapter chapter = chapterMapper.selectById(material.getChapterId());
        if (chapter == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        assertTrainerCourse(chapter.getCourseId());

        material.setTitle(form.getTitle());
        material.setMaterialType(form.getMaterialType());
        material.setFileUrl(form.getFileUrl());
        material.setFileSize(form.getFileSize());
        material.setContent(form.getContent());
        material.setDuration(form.getDuration());
        if (form.getSortOrder() != null) {
            material.setSortOrder(form.getSortOrder());
        }
        materialMapper.updateById(material);
        refreshCourseEnrollmentProgress(chapter.getCourseId());
        // 内容变了就重建该资料的切块，保证检索与正文一致
        syncRagIndex(materialMapper.selectById(materialId));
        return materialMapper.selectById(materialId);
    }

    @Transactional
    public void deleteMaterial(Long materialId) {
        TraMaterial material = materialMapper.selectById(materialId);
        if (material == null) {
            return;
        }

        TraChapter chapter = chapterMapper.selectById(material.getChapterId());
        if (chapter == null) {
            return;
        }
        assertTrainerCourse(chapter.getCourseId());

        materialMapper.deleteById(materialId);
        deleteMaterialProgress(materialId);
        // 资料删了，索引也要清掉，否则会搜到已删除的内容（本地 + MimirQ 两套）
        removeRagIndex(materialId, chapter.getCourseId());
        refreshChapterMaterialCount(chapter.getId());
        refreshCourseEnrollmentProgress(chapter.getCourseId());
    }

    public Map<String, Object> uploadMaterial(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException("上传文件不能为空");
        }

        String originalName = file.getOriginalFilename() != null ? file.getOriginalFilename() : "material";
        String extension = "";
        int dotIndex = originalName.lastIndexOf('.');
        if (dotIndex >= 0) {
            extension = originalName.substring(dotIndex);
        }

        String dateFolder = LocalDate.now().toString().replace("-", "");
        Path targetDir = Paths.get(uploadPath, "training", dateFolder);
        try {
            Files.createDirectories(targetDir);
            String fileName = UUID.randomUUID().toString().replace("-", "") + extension;
            Path targetPath = targetDir.resolve(fileName);
            Files.copy(file.getInputStream(), targetPath, StandardCopyOption.REPLACE_EXISTING);

            Map<String, Object> result = new HashMap<>();
            result.put("fileUrl", "/uploads/training/" + dateFolder + "/" + fileName);
            result.put("fileSize", file.getSize());
            result.put("originalName", originalName);
            return result;
        } catch (IOException e) {
            throw new BusinessException("文件上传失败: " + e.getMessage());
        }
    }

    public Map<String, Object> uploadCourseCover(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException("上传文件不能为空");
        }

        String originalName = file.getOriginalFilename() != null ? file.getOriginalFilename() : "cover";
        String extension = "";
        int dotIndex = originalName.lastIndexOf('.');
        if (dotIndex >= 0) {
            extension = originalName.substring(dotIndex);
        }

        String dateFolder = LocalDate.now().toString().replace("-", "");
        Path targetDir = Paths.get(uploadPath, "course-cover", dateFolder);
        try {
            Files.createDirectories(targetDir);
            String fileName = UUID.randomUUID().toString().replace("-", "") + extension;
            Path targetPath = targetDir.resolve(fileName);
            Files.copy(file.getInputStream(), targetPath, StandardCopyOption.REPLACE_EXISTING);

            Map<String, Object> result = new HashMap<>();
            result.put("fileUrl", "/uploads/course-cover/" + dateFolder + "/" + fileName);
            result.put("fileSize", file.getSize());
            result.put("originalName", originalName);
            return result;
        } catch (IOException e) {
            throw new BusinessException("文件上传失败: " + e.getMessage());
        }
    }

    public TraCourse getLearningDetail(Long userId, Long courseId) {
        TraCourse course = courseMapper.selectById(courseId);
        if (course == null || course.getStatus() != 1) {
            throw new BusinessException(ErrorCode.COURSE_NOT_FOUND);
        }

        TraEnrollment enrollment = getEnrollment(userId, courseId);
        refreshEnrollmentProgress(enrollment);
        enrollment = enrollmentMapper.selectById(enrollment.getId());

        course.setEnrollmentId(enrollment.getId());
        course.setProgress(enrollment.getProgress());
        course.setEnrollmentStatus(enrollment.getStatus());
        return enrichCourseDetail(course, userId, true);
    }

    @Transactional
    public void recordMaterialAccess(Long userId, Long materialId) {
        TraMaterial material = materialMapper.selectById(materialId);
        if (material == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }

        TraChapter chapter = chapterMapper.selectById(material.getChapterId());
        if (chapter == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }

        TraEnrollment enrollment = getEnrollment(userId, chapter.getCourseId());
        TraLearningProgress progress = getProgress(userId, materialId);
        if (progress == null) {
            progress = new TraLearningProgress();
            progress.setUserId(userId);
            progress.setMaterialId(materialId);
            progress.setEnrollmentId(enrollment.getId());
            progress.setIsCompleted(0);
            progress.setFirstAccessTime(LocalDateTime.now());
            progressMapper.insert(progress);
        } else if (progress.getFirstAccessTime() == null) {
            progress.setFirstAccessTime(LocalDateTime.now());
            progressMapper.updateById(progress);
        }
    }

    @Transactional
    public void completeMaterial(Long userId, Long materialId) {
        TraMaterial material = materialMapper.selectById(materialId);
        if (material == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }

        TraChapter chapter = chapterMapper.selectById(material.getChapterId());
        if (chapter == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }

        TraEnrollment enrollment = getEnrollment(userId, chapter.getCourseId());
        TraLearningProgress progress = getProgress(userId, materialId);
        if (progress == null) {
            progress = new TraLearningProgress();
            progress.setUserId(userId);
            progress.setMaterialId(materialId);
            progress.setEnrollmentId(enrollment.getId());
            progress.setFirstAccessTime(LocalDateTime.now());
            progress.setIsCompleted(1);
            progress.setCompleteTime(LocalDateTime.now());
            progressMapper.insert(progress);
        } else {
            if (progress.getFirstAccessTime() == null) {
                progress.setFirstAccessTime(LocalDateTime.now());
            }
            progress.setEnrollmentId(enrollment.getId());
            progress.setIsCompleted(1);
            progress.setCompleteTime(LocalDateTime.now());
            progressMapper.updateById(progress);
        }

        refreshEnrollmentProgress(enrollment);
    }

    public Map<String, Object> previewMaterial(Long userId, Long materialId) {
        TraMaterial material = materialMapper.selectById(materialId);
        if (material == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }

        TraChapter chapter = chapterMapper.selectById(material.getChapterId());
        if (chapter == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }

        getEnrollment(userId, chapter.getCourseId());

        Map<String, Object> preview = new HashMap<>();
        preview.put("mode", "none");
        preview.put("pages", List.of());
        preview.put("html", "");

        if (!needsPreview(material)) {
            return preview;
        }

        Path filePath = resolveUploadFile(material.getFileUrl());
        if (filePath == null || !Files.exists(filePath)) {
            throw new BusinessException("资料文件不存在，无法预览");
        }

        String extension = getExtension(filePath.getFileName().toString());
        try {
            if ("docx".equals(extension)) {
                preview.put("mode", "docx");
                preview.put("html", previewDocxHtml(filePath));
                return preview;
            }
            if ("pptx".equals(extension)) {
                preview.put("mode", "slides");
                preview.put("pages", previewPptxImages(filePath));
                return preview;
            }
            if ("txt".equals(extension) || "md".equals(extension)) {
                preview.put("mode", "text");
                preview.put("html", escapeHtml(Files.readString(filePath, StandardCharsets.UTF_8)).replace("\n", "<br/>"));
                return preview;
            }
            return preview;
        } catch (IOException e) {
            throw new BusinessException("资料预览失败: " + e.getMessage());
        }
    }

    public TraCourse enrichCourseDetail(TraCourse course, Long userId, boolean includeProgress) {
        LambdaQueryWrapper<TraChapter> chapterWrapper = new LambdaQueryWrapper<>();
        chapterWrapper.eq(TraChapter::getCourseId, course.getId()).orderByAsc(TraChapter::getSortOrder);
        List<TraChapter> chapters = chapterMapper.selectList(chapterWrapper);

        for (TraChapter chapter : chapters) {
            LambdaQueryWrapper<TraMaterial> materialWrapper = new LambdaQueryWrapper<>();
            materialWrapper.eq(TraMaterial::getChapterId, chapter.getId()).orderByAsc(TraMaterial::getSortOrder);
            List<TraMaterial> materials = materialMapper.selectList(materialWrapper);

            if (includeProgress && userId != null) {
                for (TraMaterial material : materials) {
                    TraLearningProgress progress = getProgress(userId, material.getId());
                    if (progress != null) {
                        material.setCompleted(progress.getIsCompleted());
                        material.setFirstAccessTime(progress.getFirstAccessTime());
                        material.setCompleteTime(progress.getCompleteTime());
                    } else {
                        material.setCompleted(0);
                    }
                }
            }

            chapter.setMaterialCount(materials.size());
            chapter.setMaterials(materials);
        }

        course.setChapters(chapters);
        course.setTotalChapters(chapters.size());
        return course;
    }

    private TraCourse assertTrainerCourse(Long courseId) {
        TraCourse course = courseMapper.selectById(courseId);
        if (course == null) {
            throw new BusinessException(ErrorCode.COURSE_NOT_FOUND);
        }

        Long currentUserId = SecurityUtils.getCurrentUserId();
        if (currentUserId != null && !currentUserId.equals(course.getTrainerId())) {
            throw new BusinessException(ErrorCode.FORBIDDEN.getCode(), "无权限操作该课程");
        }
        return course;
    }

    private TraEnrollment getEnrollment(Long userId, Long courseId) {
        LambdaQueryWrapper<TraEnrollment> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(TraEnrollment::getUserId, userId)
                .eq(TraEnrollment::getCourseId, courseId)
                .last("LIMIT 1");
        TraEnrollment enrollment = enrollmentMapper.selectOne(wrapper);
        if (enrollment == null) {
            throw new BusinessException("请先报名课程");
        }
        return enrollment;
    }

    private TraLearningProgress getProgress(Long userId, Long materialId) {
        LambdaQueryWrapper<TraLearningProgress> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(TraLearningProgress::getUserId, userId)
                .eq(TraLearningProgress::getMaterialId, materialId)
                .last("LIMIT 1");
        return progressMapper.selectOne(wrapper);
    }

    private int getNextChapterSort(Long courseId) {
        LambdaQueryWrapper<TraChapter> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(TraChapter::getCourseId, courseId)
                .orderByDesc(TraChapter::getSortOrder)
                .last("LIMIT 1");
        TraChapter chapter = chapterMapper.selectOne(wrapper);
        return chapter != null && chapter.getSortOrder() != null ? chapter.getSortOrder() + 1 : 1;
    }

    private int getNextMaterialSort(Long chapterId) {
        LambdaQueryWrapper<TraMaterial> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(TraMaterial::getChapterId, chapterId)
                .orderByDesc(TraMaterial::getSortOrder)
                .last("LIMIT 1");
        TraMaterial material = materialMapper.selectOne(wrapper);
        return material != null && material.getSortOrder() != null ? material.getSortOrder() + 1 : 1;
    }

    private void refreshCourseChapterCount(TraCourse course) {
        LambdaQueryWrapper<TraChapter> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(TraChapter::getCourseId, course.getId());
        course.setTotalChapters(chapterMapper.selectCount(wrapper).intValue());
        courseMapper.updateById(course);
    }

    private void refreshChapterMaterialCount(Long chapterId) {
        TraChapter chapter = chapterMapper.selectById(chapterId);
        if (chapter == null) {
            return;
        }

        LambdaQueryWrapper<TraMaterial> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(TraMaterial::getChapterId, chapterId);
        chapter.setMaterialCount(materialMapper.selectCount(wrapper).intValue());
        chapterMapper.updateById(chapter);
    }

    private void refreshEnrollmentProgress(TraEnrollment enrollment) {
        LambdaQueryWrapper<TraChapter> chapterWrapper = new LambdaQueryWrapper<>();
        chapterWrapper.eq(TraChapter::getCourseId, enrollment.getCourseId());
        List<Long> chapterIds = chapterMapper.selectList(chapterWrapper).stream().map(TraChapter::getId).toList();

        if (chapterIds.isEmpty()) {
            enrollment.setProgress(BigDecimal.ZERO);
            enrollment.setStatus(0);
            enrollment.setCompleteTime(null);
            enrollmentMapper.updateById(enrollment);
            return;
        }

        LambdaQueryWrapper<TraMaterial> materialWrapper = new LambdaQueryWrapper<>();
        materialWrapper.in(TraMaterial::getChapterId, chapterIds);
        long totalMaterials = materialMapper.selectCount(materialWrapper);

        if (totalMaterials == 0) {
            enrollment.setProgress(BigDecimal.ZERO);
            enrollment.setStatus(0);
            enrollment.setCompleteTime(null);
            enrollmentMapper.updateById(enrollment);
            return;
        }

        LambdaQueryWrapper<TraLearningProgress> progressWrapper = new LambdaQueryWrapper<>();
        progressWrapper.eq(TraLearningProgress::getEnrollmentId, enrollment.getId())
                .eq(TraLearningProgress::getUserId, enrollment.getUserId())
                .eq(TraLearningProgress::getIsCompleted, 1);
        long completedCount = progressMapper.selectCount(progressWrapper);

        BigDecimal progress = BigDecimal.valueOf(completedCount)
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(totalMaterials), 2, RoundingMode.HALF_UP);
        enrollment.setProgress(progress);
        if (completedCount >= totalMaterials) {
            enrollment.setStatus(1);
            if (enrollment.getCompleteTime() == null) {
                enrollment.setCompleteTime(LocalDateTime.now());
            }
        } else {
            enrollment.setStatus(0);
            enrollment.setCompleteTime(null);
        }
        enrollmentMapper.updateById(enrollment);
    }

    private void refreshCourseEnrollmentProgress(Long courseId) {
        LambdaQueryWrapper<TraEnrollment> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(TraEnrollment::getCourseId, courseId);
        List<TraEnrollment> enrollments = enrollmentMapper.selectList(wrapper);
        for (TraEnrollment enrollment : enrollments) {
            refreshEnrollmentProgress(enrollment);
        }
    }

    private void deleteMaterialProgress(Long materialId) {
        LambdaQueryWrapper<TraLearningProgress> progressWrapper = new LambdaQueryWrapper<>();
        progressWrapper.eq(TraLearningProgress::getMaterialId, materialId);
        progressMapper.delete(progressWrapper);
    }

    private boolean needsPreview(TraMaterial material) {
        if (material.getFileUrl() == null || material.getFileUrl().isBlank()) {
            return false;
        }
        String extension = getExtension(material.getFileUrl());
        return List.of("docx", "pptx", "txt", "md").contains(extension);
    }

    private Path resolveUploadFile(String fileUrl) {
        if (fileUrl == null || fileUrl.isBlank() || !fileUrl.startsWith("/uploads/")) {
            return null;
        }
        String relativePath = fileUrl.substring("/uploads/".length()).replace("/", java.io.File.separator);
        return Paths.get(uploadPath, relativePath);
    }

    private String getExtension(String fileName) {
        int index = fileName.lastIndexOf('.');
        if (index < 0 || index == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(index + 1).toLowerCase();
    }

    private String previewDocxHtml(Path filePath) throws IOException {
        StringBuilder html = new StringBuilder();
        html.append("<div class=\"docx-preview-content\">");
        try (InputStream inputStream = Files.newInputStream(filePath);
             XWPFDocument document = new XWPFDocument(inputStream)) {
            for (XWPFParagraph paragraph : document.getParagraphs()) {
                String text = paragraph.getText();
                if (text != null && !text.isBlank()) {
                    html.append("<p>").append(escapeHtml(text.trim())).append("</p>");
                }
            }
            for (XWPFTable table : document.getTables()) {
                html.append("<table>");
                for (XWPFTableRow row : table.getRows()) {
                    html.append("<tr>");
                    for (XWPFTableCell cell : row.getTableCells()) {
                        String text = cell.getText();
                        html.append("<td>").append(escapeHtml(text == null ? "" : text.trim())).append("</td>");
                    }
                    html.append("</tr>");
                }
                html.append("</table>");
            }
        }
        html.append("</div>");
        return html.toString();
    }

    private List<String> previewPptxImages(Path filePath) throws IOException {
        List<String> slides = new ArrayList<>();
        try (InputStream inputStream = Files.newInputStream(filePath);
             XMLSlideShow slideShow = new XMLSlideShow(inputStream)) {
            Dimension pageSize = slideShow.getPageSize();
            for (XSLFSlide slide : slideShow.getSlides()) {
                BufferedImage image = new BufferedImage(pageSize.width, pageSize.height, BufferedImage.TYPE_INT_RGB);
                Graphics2D graphics = image.createGraphics();
                graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                graphics.setPaint(Color.WHITE);
                graphics.fill(new Rectangle2D.Float(0, 0, pageSize.width, pageSize.height));
                slide.draw(graphics);
                graphics.dispose();
                slides.add("data:image/png;base64," + toBase64(image));
            }
        }
        return slides;
    }

    private String toBase64(BufferedImage image) throws IOException {
        java.io.ByteArrayOutputStream outputStream = new java.io.ByteArrayOutputStream();
        ImageIO.write(image, "png", outputStream);
        return Base64.getEncoder().encodeToString(outputStream.toByteArray());
    }

    private String escapeHtml(String input) {
        if (input == null) {
            return "";
        }
        return input
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    // ------------------------------------------------------------------ RAG 索引联动

    /**
     * 资料落库后同步"两套索引"：本地切块 + MimirQ 知识库。
     * <p>
     * 为什么放在"事务提交之后"执行，而不是在这里直接调同步服务：
     * <ol>
     *   <li>在业务事务内直接调用时，被调用的 {@code @Transactional} 方法一旦抛异常，
     *       Spring 会把<b>外层</b>事务标记成 rollback-only —— 这里就算 catch 住，
     *       外层 commit 时仍会抛 {@code UnexpectedRollbackException}，
     *       "索引失败"最终把"资料保存"一起拖垮。（单纯 try/catch 是挡不住的。）</li>
     *   <li>索引是派生数据：允许失败、允许延迟，事后可用 {@code POST /api/v1/rag/reindex} 重建。
     *       业务数据（资料本身）不能因为索引失败而回滚。</li>
     * </ol>
     * 同步服务内部再用 {@code REQUIRES_NEW} 保证写入真的落库（原因见该类注释）。
     */
    private void syncRagIndex(TraMaterial material) {
        if (material == null || material.getId() == null) {
            return;
        }
        Long courseId = resolveCourseId(material.getChapterId());
        Long materialId = material.getId();
        String title = material.getTitle();

        afterCommit(() -> {
            if (ragIndexSyncService == null) {
                return;
            }
            // 第一步：本地切块。它是"事实"，失败就没必要推外部（推了也是旧的）
            try {
                int chunks = ragIndexSyncService.rebuildLocalChunks(material);
                log.info("RAG 本地索引已同步：materialId={}, title={}, chunks={}", materialId, title, chunks);
            } catch (Exception e) {
                log.warn("RAG 本地索引同步失败（不影响资料保存），materialId={}，"
                        + "可调用 /api/v1/rag/reindex 重建：{}", materialId, e.toString());
                return;
            }
            // 第二步：推给 MimirQ。失败只降级为"这条资料暂时只能靠本地检索到"
            try {
                ragIndexSyncService.pushToMimirQ(materialId, courseId, title);
            } catch (Exception e) {
                log.warn("推送到 MimirQ 失败（该资料仍可通过本地检索搜到），materialId={}：{}",
                        materialId, e.toString());
            }
        });
    }

    /** 资料删除后清掉两套索引，否则员工会搜到已删除的内容。 */
    private void removeRagIndex(Long materialId, Long courseId) {
        if (materialId == null) {
            return;
        }
        afterCommit(() -> {
            if (ragIndexSyncService == null) {
                return;
            }
            try {
                int removed = ragIndexSyncService.removeLocalChunks(materialId);
                if (removed > 0) {
                    log.info("RAG 本地索引已清理：materialId={}, chunks={}", materialId, removed);
                }
            } catch (Exception e) {
                log.warn("RAG 本地索引清理失败，materialId={}：{}", materialId, e.toString());
            }
            try {
                ragIndexSyncService.removeFromMimirQ(materialId, courseId);
            } catch (Exception e) {
                log.warn("MimirQ 索引清理失败，materialId={}：{}", materialId, e.toString());
            }
        });
    }

    /** 由章节反查课程，用于定位 MimirQ 侧的知识库（course-{courseId}）。 */
    private Long resolveCourseId(Long chapterId) {
        if (chapterId == null) {
            return null;
        }
        TraChapter chapter = chapterMapper.selectById(chapterId);
        return chapter == null ? null : chapter.getCourseId();
    }

    /**
     * 注册"业务事务提交后执行"。没有活跃事务时（例如单测直接调用）立即执行，
     * 避免出现"注册了却永远不触发"的静默失效。
     * <p>
     * 用 {@code afterCompletion + STATUS_COMMITTED} 而不是 {@code afterCommit}：
     * 前者语义就是"事务已彻底结束"，此时在回调里开新事务语义最清晰；
     * 且回滚时不会误执行（afterCommit 本来也不会，但这里多一层显式判断更不容易被后人改错）。
     */
    private void afterCommit(Runnable task) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            task.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == TransactionSynchronization.STATUS_COMMITTED) {
                    task.run();
                }
            }
        });
    }
}
