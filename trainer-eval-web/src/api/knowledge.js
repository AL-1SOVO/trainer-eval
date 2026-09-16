import request from '@/api/request'

/**
 * 培训文档知识检索（员工 / 内训师 / 管理员都能用）。
 *
 * 后端会按登录用户做权限裁剪：员工只能搜到自己有权看到的课程文档，
 * 所以前端不需要（也不应该）自己拼课程范围。
 *
 * 返回：
 *   { query, total, items: [{ citation, materialId, courseId, chapterId, title,
 *                            courseName, chapterTitle, excerpt, relevance }],
 *     message?, degraded }
 */
export function searchKnowledge({ query, courseId, topK }) {
  return request.post('/knowledge/search', { query, courseId, topK })
}

/** 当前用户可检索的课程（用于筛选下拉，与检索共用同一套权限裁剪）。 */
export function listSearchableCourses() {
  return request.get('/knowledge/courses')
}
