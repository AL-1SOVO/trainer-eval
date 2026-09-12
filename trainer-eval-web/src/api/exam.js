import request from './request'
export function getAvailablePapers() { return request.get('/exam/papers/available') }
export function startExam(paperId) { return request.post('/exam/start', { paperId }) }
export function getSession(recordId) { return request.get(`/exam/session/${recordId}`) }
export function saveAnswer(recordId, data) { return request.post(`/exam/session/${recordId}/save`, data) }
export function submitExam(data) { return request.post('/exam/submit', data) }
export function getMyRecords(params) { return request.get('/exam/records/my', { params }) }
export function getResult(recordId) { return request.get(`/exam/records/${recordId}/result`) }
export function getErrorAnalysis(answerId) { return request.get(`/exam/answers/${answerId}/analysis`) }

// Wrong answer analysis
export function triggerAnalysis(answerId) { return request.post(`/exam/analysis/${answerId}`) }
export function getAnalysisByAnswer(answerId) { return request.get(`/exam/analysis/answer/${answerId}`) }
export function getUserAnalyses(params) { return request.get('/exam/analysis/user', { params }) }
export function getAnalysisStats() { return request.get('/exam/analysis/stats') }

export function getGradingQueue(params) { return request.get('/exam/grading/queue', { params }) }
export function getGradingDetail(recordId) { return request.get(`/exam/grading/${recordId}`) }
export function updateManualScore(answerId, data) { return request.put(`/exam/grading/answers/${answerId}`, data) }
export function finalizeGrading(recordId) { return request.put(`/exam/grading/${recordId}/finalize`) }

export function getAiRules() { return request.get('/ai/rules') }
export function getAiRule(id) { return request.get(`/ai/rules/${id}`) }
export function createAiRule(data) { return request.post('/ai/rules', data) }
export function updateAiRule(id, data) { return request.put(`/ai/rules/${id}`, data) }
export function deleteAiRule(id) { return request.delete(`/ai/rules/${id}`) }
