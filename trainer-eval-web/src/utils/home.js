/**
 * 按角色决定登录后的落地页。
 *
 * 为什么需要它：登录后原来固定跳 `/employee/courses`。
 * 对管理员/内训师来说，落地在"员工的学习中心"很别扭 —— 那既不是他的主页，
 * 侧边栏里也不会有对应的高亮项；而现在每个门户都有自己的仪表盘，那才是合适的入口。
 *
 * 优先级：管理员 > 内训师 > 员工。一个账号可能同时持有多个角色
 * （例如 admin 同时是 员工/内训师/管理员），此时按最高权限的门户落地。
 */
const HOME_BY_ROLE = [
  ['ROLE_MANAGER', '/manager/dashboard'],
  ['ROLE_TRAINER', '/trainer/dashboard']
]

export const DEFAULT_HOME = '/employee/dashboard'

export function homePath(roles) {
  const list = Array.isArray(roles) ? roles : []
  for (const [role, path] of HOME_BY_ROLE) {
    if (list.includes(role)) return path
  }
  return DEFAULT_HOME
}
