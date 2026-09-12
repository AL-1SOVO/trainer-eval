import request from './request'
export function getDeptTree(config = {}) { return request.get('/sys/depts/tree', { ...config }) }
export function createDept(data) { return request.post('/sys/depts', data) }
export function updateDept(id, data) { return request.put(`/sys/depts/${id}`, data) }
export function deleteDept(id) { return request.delete(`/sys/depts/${id}`) }
