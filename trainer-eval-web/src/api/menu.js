import request from './request'
export function getMenuTree() { return request.get('/sys/menus/tree') }
export function getRoleMenuTree(roleId) { return request.get('/sys/menus/role-tree', { params: { roleId } }) }
export function getUserMenus() { return request.get('/sys/menus/user') }
export function createMenu(data) { return request.post('/sys/menus', data) }
export function updateMenu(id, data) { return request.put(`/sys/menus/${id}`, data) }
export function deleteMenu(id) { return request.delete(`/sys/menus/${id}`) }
