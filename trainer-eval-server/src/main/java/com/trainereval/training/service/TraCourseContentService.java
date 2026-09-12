package com.trainereval.training.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.trainereval.common.exception.BusinessException;
import com.trainereval.common.exception.ErrorCode;
import com.trainereval.common.util.SecurityUtils;
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
}
