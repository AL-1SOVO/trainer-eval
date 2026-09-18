import request from './request'
export function getPublishedCourses(params) { return request.get('/training/courses', { params }) }
export function getManagedCourses(params) { return request.get('/training/courses/my/managed', { params }) }
export function getAllCourses(params) { return request.get('/training/courses/all', { params }) }
export function getCourseById(id) { return request.get(`/training/courses/${id}`) }
export function createCourse(data) { return request.post('/training/courses', data) }
export function updateCourse(id, data) { return request.put(`/training/courses/${id}`, data) }
export function updateCourseStatus(id, status) { return request.put(`/training/courses/${id}/status`, { status }) }
export function deleteCourse(id) { return request.delete(`/training/courses/${id}`) }
export function enrollCourse(courseId) { return request.post('/training/enrollments', { courseId }) }
export function getMyEnrollments() { return request.get('/training/enrollments/my') }
export function getLearningCourseDetail(courseId) { return request.get(`/training/courses/${courseId}/learn`) }
export function createChapter(courseId, data) { return request.post(`/training/courses/${courseId}/chapters`, data) }
export function updateChapter(chapterId, data) { return request.put(`/training/chapters/${chapterId}`, data) }
export function deleteChapter(chapterId) { return request.delete(`/training/chapters/${chapterId}`) }
export function createMaterial(chapterId, data) { return request.post(`/training/chapters/${chapterId}/materials`, data) }
export function updateMaterial(materialId, data) { return request.put(`/training/materials/${materialId}`, data) }
export function deleteMaterial(materialId) { return request.delete(`/training/materials/${materialId}`) }
export function uploadMaterial(formData) {
  return request.post('/training/materials/upload', formData, {
    headers: { 'Content-Type': 'multipart/form-data' }
  })
}
/**
 * 上传文件并直接建成"可被检索"的资料：后端抽取正文 → 建资料 → 切块 → 灌知识库。
 * 与 uploadMaterial 的区别：那个只存盘返回 URL，之后还要人工粘贴正文，漏了那步就永远搜不到。
 */
export function ingestMaterial(chapterId, formData) {
  return request.post(`/training/chapters/${chapterId}/materials/ingest`, formData, {
    headers: { 'Content-Type': 'multipart/form-data' }
  })
}
/** 可上传的格式与大小上限（用于前端提示，避免先传一次才知道不支持） */
export function getIngestCapabilities() {
  return request.get('/training/materials/ingest/capabilities')
}
export function uploadCourseCover(formData) {
  return request.post('/training/courses/cover/upload', formData, {
    headers: { 'Content-Type': 'multipart/form-data' }
  })
}
export function recordMaterialAccess(materialId) { return request.post(`/training/materials/${materialId}/access`) }
export function completeMaterial(materialId) { return request.post(`/training/materials/${materialId}/complete`) }
export function previewMaterial(materialId) { return request.get(`/training/materials/${materialId}/preview`) }
