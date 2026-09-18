package com.trainereval.training.controller;

import com.trainereval.common.result.R;
import com.trainereval.common.util.SecurityUtils;
import com.trainereval.training.dto.TraChapterForm;
import com.trainereval.training.dto.TraMaterialForm;
import com.trainereval.training.entity.TraChapter;
import com.trainereval.training.entity.TraCourse;
import com.trainereval.training.entity.TraMaterial;
import com.trainereval.training.service.MaterialIngestService;
import com.trainereval.training.service.TraCourseContentService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/training")
public class TraCourseContentController {

    @Autowired
    private TraCourseContentService courseContentService;

    @Autowired
    private MaterialIngestService materialIngestService;

    @GetMapping("/courses/{courseId}/learn")
    public R<TraCourse> getLearningDetail(@PathVariable Long courseId) {
        return R.ok(courseContentService.getLearningDetail(SecurityUtils.getCurrentUserId(), courseId));
    }

    @PostMapping("/courses/{courseId}/chapters")
    @PreAuthorize("hasRole('TRAINER')")
    public R<TraChapter> createChapter(@PathVariable Long courseId, @Valid @RequestBody TraChapterForm form) {
        return R.ok(courseContentService.createChapter(courseId, form));
    }

    @PutMapping("/chapters/{chapterId}")
    @PreAuthorize("hasRole('TRAINER')")
    public R<TraChapter> updateChapter(@PathVariable Long chapterId, @Valid @RequestBody TraChapterForm form) {
        return R.ok(courseContentService.updateChapter(chapterId, form));
    }

    @DeleteMapping("/chapters/{chapterId}")
    @PreAuthorize("hasRole('TRAINER')")
    public R<?> deleteChapter(@PathVariable Long chapterId) {
        courseContentService.deleteChapter(chapterId);
        return R.ok();
    }

    @PostMapping("/chapters/{chapterId}/materials")
    @PreAuthorize("hasRole('TRAINER')")
    public R<TraMaterial> createMaterial(@PathVariable Long chapterId, @Valid @RequestBody TraMaterialForm form) {
        return R.ok(courseContentService.createMaterial(chapterId, form));
    }

    @PutMapping("/materials/{materialId}")
    @PreAuthorize("hasRole('TRAINER')")
    public R<TraMaterial> updateMaterial(@PathVariable Long materialId, @Valid @RequestBody TraMaterialForm form) {
        return R.ok(courseContentService.updateMaterial(materialId, form));
    }

    @DeleteMapping("/materials/{materialId}")
    @PreAuthorize("hasRole('TRAINER')")
    public R<?> deleteMaterial(@PathVariable Long materialId) {
        courseContentService.deleteMaterial(materialId);
        return R.ok();
    }

    @PostMapping("/materials/upload")
    @PreAuthorize("hasRole('TRAINER')")
    public R<Map<String, Object>> uploadMaterial(@RequestParam("file") MultipartFile file) {
        return R.ok(courseContentService.uploadMaterial(file));
    }

    /**
     * 上传文件并直接建成<b>可被检索</b>的资料：抽取正文 → 建资料 → 切块 → 灌知识库。
     * <p>
     * 与 {@code /materials/upload} 的区别：那个只把文件存下来返回 URL，
     * 之后还要人工建资料并粘贴正文；漏了那一步资料就没有正文，永远搜不到。
     */
    @PostMapping(value = "/chapters/{chapterId}/materials/ingest", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('TRAINER')")
    public R<Map<String, Object>> uploadAndIndex(@PathVariable Long chapterId,
                                                 @RequestParam("file") MultipartFile file,
                                                 @RequestParam(value = "title", required = false) String title,
                                                 @RequestParam(value = "materialType", required = false) String materialType) {
        return R.ok(materialIngestService.uploadAndIndex(chapterId, file, title, materialType));
    }

    /** 前端用于提示"支持哪些格式、多大"，避免让用户先传一次才发现不支持。 */
    @GetMapping("/materials/ingest/capabilities")
    @PreAuthorize("hasRole('TRAINER')")
    public R<Map<String, Object>> ingestCapabilities() {
        return R.ok(materialIngestService.uploadCapabilities());
    }

    @PostMapping("/courses/cover/upload")
    @PreAuthorize("hasRole('TRAINER')")
    public R<Map<String, Object>> uploadCourseCover(@RequestParam("file") MultipartFile file) {
        return R.ok(courseContentService.uploadCourseCover(file));
    }

    @PostMapping("/materials/{materialId}/access")
    public R<?> recordAccess(@PathVariable Long materialId) {
        courseContentService.recordMaterialAccess(SecurityUtils.getCurrentUserId(), materialId);
        return R.ok();
    }

    @PostMapping("/materials/{materialId}/complete")
    public R<?> completeMaterial(@PathVariable Long materialId) {
        courseContentService.completeMaterial(SecurityUtils.getCurrentUserId(), materialId);
        return R.ok();
    }

    @GetMapping("/materials/{materialId}/preview")
    public R<Map<String, Object>> previewMaterial(@PathVariable Long materialId) {
        return R.ok(courseContentService.previewMaterial(SecurityUtils.getCurrentUserId(), materialId));
    }
}
