-- =============================================================================
-- 知识库管理 / 链路追踪 / MCP 工具接入 —— 菜单与授权
-- =============================================================================
-- 本脚本补上三个新页面在 sys_menu 里的入口，并把授权补全。
--
-- 【为什么要单独写授权】
-- sys_role_menu 是数据，不是规则：之前那条 "给 ROLE_MANAGER 授全部菜单" 的
-- INSERT ... SELECT ... ON 1=1 只在执行当时生效，**新增的菜单不会被自动继承**。
-- 所以每加一个菜单都必须显式补授权，否则表现是"页面能通过 URL 打开、
-- 但侧边栏里找不到"——这类问题在管理员账号上尤其容易被忽略，
-- 因为管理员账号同时挂了员工/内训师角色，从别的门户那侧仍能点进去。
--
-- 【菜单 id 分配】
--   17  → 内训师门户：知识库管理（内训师上传的文档，出问题要能自查）
--   33  → 管理后台：知识库管理
--   34  → 管理后台：链路追踪（仅管理员，链路含用户提问原文）
--   35  → 管理后台：MCP 工具接入（仅管理员，涉及外部系统与角色白名单）
--
-- 幂等：菜单用固定 id + ON DUPLICATE KEY UPDATE；授权用 INSERT IGNORE。
-- 执行：mysql -uroot -p123456 trainer_eval < db/knowledge_observability_menu.sql
-- =============================================================================

-- ------------------------------------------------------------ 1. 菜单
INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, path, component, permission, icon, sort_order, visible, status)
VALUES (17, 10, '知识库管理', 'C', 'knowledge', 'manager/knowledge/KnowledgeBase', NULL, 'Collection', 6, 1, 1),
       (33, 20, '知识库管理', 'C', 'knowledge', 'manager/knowledge/KnowledgeBase', NULL, 'Collection',  9, 1, 1),
       (34, 20, '链路追踪',   'C', 'traces',    'manager/observability/TraceList', NULL, 'DataAnalysis', 10, 1, 1),
       (35, 20, 'MCP 工具接入', 'C', 'mcp',     'manager/mcp/McpManage',           NULL, 'Connection',   11, 1, 1)
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

-- ------------------------------------------------------------ 2. 授权

-- 内训师：培训师门户全部菜单（含新增的 17）
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT r.id, m.id
  FROM sys_role r
  JOIN sys_menu m ON m.id IN (1, 2, 3, 4, 5, 8, 10, 11, 12, 13, 14, 15, 16, 17)
 WHERE r.role_code = 'ROLE_TRAINER';

-- 员工：员工门户（不含任何运维页面）
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT r.id, m.id
  FROM sys_role r
  JOIN sys_menu m ON m.id IN (1, 2, 3, 4, 5, 6, 7, 8)
 WHERE r.role_code = 'ROLE_EMPLOYEE';

-- 管理员：全部菜单（每条都要显式授，见文件头说明）
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT r.id, m.id
  FROM sys_role r
  JOIN sys_menu m ON 1 = 1
 WHERE r.role_code = 'ROLE_MANAGER';

-- 校验：
--   SELECT r.role_code, COUNT(*) FROM sys_role_menu rm
--     JOIN sys_role r ON r.id = rm.role_id GROUP BY r.role_code;
--   期望（本脚本执行后实测）：员工 8、内训师 16、管理员 32
--   SELECT COUNT(*) FROM sys_menu;   -- 实测 32
