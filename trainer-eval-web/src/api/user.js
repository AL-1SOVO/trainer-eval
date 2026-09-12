import request from './request'
export function getUsers(params) { return request.get('/sys/users', { params }) }
export function getUserById(id) { return request.get(`/sys/users/${id}`) }
export function createUser(data) { return request.post('/sys/users', data) }
export function updateUser(id, data) { return request.put(`/sys/users/${id}`, data) }
export function deleteUser(id) { return request.delete(`/sys/users/${id}`) }
export function assignRoles(id, roleIds) { return request.put(`/sys/users/${id}/roles`, { roleIds }) }
export function resetPassword(id, password) { return request.post(`/sys/users/${id}/reset-password`, { password }) }
