import request from './request'

export function getPapers(params) { return request.get('/papers', { params }) }
export function getPaper(id) { return request.get(`/papers/${id}`) }
export function createPaper(data) { return request.post('/papers', data) }
export function updatePaper(id, data) { return request.put(`/papers/${id}`, data) }
export function deletePaper(id) { return request.delete(`/papers/${id}`) }
export function updatePaperStatus(id, status) { return request.put(`/papers/${id}/status`, null, { params: { status } }) }
export function getPaperQuestions(id) { return request.get(`/papers/${id}/questions`) }
export function getPaperStats(courseId) { return request.get('/papers/stats', { params: { courseId } }) }
