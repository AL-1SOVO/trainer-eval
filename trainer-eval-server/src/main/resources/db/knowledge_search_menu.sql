-- =============================================================================
-- 「知识搜索」菜单（员工 / 内训师可用）
-- =============================================================================
-- 背景：RAG 检索原先只挂在 /api/v1/rag/*，是管理与调试面（限 TRAINER/MANAGER），
--       员工根本看不到入口。员工要"快速查找企业培训文档知识"就必须有自己的页面。
--
-- 该脚本可重复执行（幂等）：
--   菜单用固定 id=7 + ON DUPLICATE KEY UPDATE，重复执行只会覆盖同样的值；
--   授权用 INSERT IGNORE，靠 (role_id, menu_id) 上的唯一索引去重。
--
-- 注意：新增菜单不需要重启后端，但前端需要刷新页面重新拉取菜单树
--       （菜单树缓存在 Pinia，登录后由 GET /sys/menus/user 加载）。
-- =============================================================================

INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, path, component, permission, icon, sort_order, visible, status)
VALUES (7, 1, '知识搜索', 'C', 'knowledge-search', 'employee/knowledge/KnowledgeSearch', NULL, 'Search', 6, 1, 1)
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
