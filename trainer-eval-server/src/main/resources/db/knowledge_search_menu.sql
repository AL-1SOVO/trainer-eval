-- =============================================================================
-- 「知识搜索」菜单（员工 / 内训师 / 管理员）
-- =============================================================================
-- 背景：RAG 检索原先只挂在 /api/v1/rag/*，是管理与调试面（限 TRAINER/MANAGER），
--       员工根本看不到入口。员工要"快速查找企业培训文档知识"就必须有自己的页面。
--
-- 为什么放在**顶层**（parent_id = 0）而不是塞进「员工门户」分组：
--   最初是挂在 员工门户 下的，但那个分组默认是收起的。对"只授权了一两个菜单"的账号，
--   侧边栏里就只剩"员工门户"四个字，使用者会直接判定"没有这个功能"。
--   实际发生过两次（员工、以及以管理员身份浏览的人），所以改成顶层项：
--   不依赖任何分组展开动作，任何角色登录都能直接看到。
--
-- 顶层项的 path 必须是**完整路径**（/employee/knowledge-search）：
--   Sidebar 对没有子菜单的顶层项直接用 menu.path 作为 el-menu 的 index，
--   不像分组那样会用 父路径+子路径 去拼。
--
-- 该脚本可重复执行（幂等）：
--   菜单用固定 id=7 + ON DUPLICATE KEY UPDATE；授权用 INSERT IGNORE。
--
-- 注意：改菜单不需要重启后端，但前端要刷新页面重新拉取菜单树
--       （菜单树缓存在 Pinia，登录后由 GET /sys/menus/user 加载）。
-- =============================================================================

INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, path, component, permission, icon, sort_order, visible, status)
VALUES (7, 0, '知识搜索', 'C', '/employee/knowledge-search', 'employee/knowledge/KnowledgeSearch', NULL, 'Search', 0, 1, 1)
ON DUPLICATE KEY UPDATE
    parent_id  = VALUES(parent_id),
    menu_name  = VALUES(menu_name),
    menu_type  = VALUES(menu_type),
    path       = VALUES(path),
    component  = VALUES(component),
    icon       = VALUES(icon),
    sort_order = VALUES(sort_order),
    visible    = VALUES(visible),
    status     = VALUES(status);

-- 授予「员工」「内训师」：两者都需要检索能力
--   · 员工：学习时查资料
--   · 内训师：上传文档后自查"员工能不能搜到"，否则只能靠管理员代查
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT r.id, 7 FROM sys_role r WHERE r.role_name IN ('员工', '内训师');

-- 管理员默认能看全部菜单，这里显式补一条，避免"按角色逐个授权"的实现漏掉它
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT r.id, 7 FROM sys_role r WHERE r.role_name = '管理员';

-- 校验：应该看到 3 行（员工 / 内训师 / 管理员）
-- SELECT r.role_name, m.id, m.menu_name
--   FROM sys_role_menu rm
--   JOIN sys_role r ON r.id = rm.role_id
--   JOIN sys_menu m ON m.id = rm.menu_id
--  WHERE m.id = 7;
