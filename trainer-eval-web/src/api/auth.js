import request from './request'

export function login(data) {
  return request.post('/auth/login', data)
}

export function register(data) {
  return request.post('/auth/register', data)
}

export function refreshToken(data) {
  return request.post('/auth/refresh', data)
}

export function logout() {
  return request.post('/auth/logout')
}

export function getUserInfo() {
  return request.get('/auth/userinfo')
}

export function changePassword(data) {
  return request.put('/auth/password', data)
}

/**
 * 更新个人信息。只提交可自助修改的字段（邮箱/手机号/性别）——
 * 用户名、姓名、部门、角色由管理员在「用户管理」维护，后端也会忽略这些字段。
 */
export function updateProfile(data) {
  return request.put('/auth/profile', data)
}
