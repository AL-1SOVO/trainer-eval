import request from './request'

export function generateReport(data) {
  return request.post('/reports/generate', data)
}

export function getReportHistory(params) {
  return request.get('/reports/history', { params })
}

export function deleteReport(id) {
  return request.delete(`/reports/${id}`)
}
