import request from './request'

export function getQuestions(params) { return request.get('/questions', { params }) }
export function getAllQuestions(courseId) { return request.get('/questions/all', { params: { courseId } }) }
export function getQuestion(id) { return request.get(`/questions/${id}`) }
export function createQuestion(data) { return request.post('/questions', data) }
export function updateQuestion(id, data) { return request.put(`/questions/${id}`, data) }
export function deleteQuestion(id) { return request.delete(`/questions/${id}`) }
export function importQuestions(formData) { return request.post('/questions/import', formData) }
export function downloadTemplate() { return request.get('/questions/template', { responseType: 'blob' }) }
export function getQuestionStats(courseId) { return request.get('/questions/stats', { params: { courseId } }) }
