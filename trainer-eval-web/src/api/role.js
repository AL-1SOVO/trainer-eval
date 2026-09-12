import request from './request'
export function getRoles() { return request.get('/sys/roles') }
export function createRole(data) { return request.post('/sys/roles', data) }
export function updateRole(id, data) { return request.put(`/sys/roles/${id}`, data) }
export function deleteRole(id) { return request.delete(`/sys/roles/${id}`) }
export function assignMenus(id, menuIds) { return request.put(`/sys/roles/${id}/menus`, { menuIds }) }
