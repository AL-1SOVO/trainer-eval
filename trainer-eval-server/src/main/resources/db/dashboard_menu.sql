-- =============================================================================
-- 仪表盘菜单 + 门户授权补齐
-- =============================================================================
-- 本脚本修两类问题：
--
-- 【问题一】三个仪表盘页面没有菜单入口（孤儿页面）
--   /employee/dashboard、/trainer/dashboard、/manager/dashboard 都有页面组件和路由，
--   但 sys_menu 里没有对应行 —— 只能手敲 URL 才能打开，等于死代码。
--
-- 【问题二】门户授权不完整，纯角色账号看不到自己的门户（更严重）
--   修复前：ROLE_EMPLOYEE 只授权了 6、7；ROLE_TRAINER 也只授权了 6、7。
--   后果：
--     · 员工在侧边栏看不到「学习中心/我的课程/我的考试/错题分析」，
--       而登录后默认落地页恰恰是 /employee/courses —— 落在一个菜单里找不到的页面上。
--     · 内训师门户的 5 个菜单（课程管理/题库/试卷/阅卷/AI评分配置）**一个都没授权给内训师**，
--       纯内训师账号根本进不去自己的门户。
--   之所以一直没被发现：admin 账号同时挂了 员工/内训师/管理员 三个角色，
--   而管理员拥有全部菜单，把这个问题完全掩盖了。
--
-- 幂等：菜单用固定 id + ON DUPLICATE KEY UPDATE；授权用 INSERT IGNORE。
-- =============================================================================

-- ---------------------------------------------------------------- 1. 三个仪表盘

INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, path, component, permission, icon, sort_order, visible, status)
VALUES (8,  1,  '学习仪表盘', 'C', 'dashboard', 'employee/dashboard/EmployeeDashboard', NULL, 'Odometer', 0, 1, 1),
       (16, 10, '培训概览',   'C', 'dashboard', 'trainer/dashboard/TrainerDashboard',   NULL, 'DataLine', 0, 1, 1),
       (32, 20, '管理仪表盘', 'C', 'dashboard', 'manager/dashboard/ManagerDashboard',   NULL, 'Odometer', 0, 1, 1)
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

-- ---------------------------------------------------------------- 2. 补齐门户授权
-- 员工：员工门户下的全部页面（含仪表盘与知识搜索）
-- 说明：菜单 1 是父目录，必须一起授权，否则 buildTree 找不到父节点；
--       （后端已做祖先后置补全，但显式授权更直观、也不依赖那层兜底。）
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT r.id, m.id
  FROM sys_role r
  JOIN sys_menu m ON m.id IN (1, 2, 3, 4, 5, 6, 7, 8)
 WHERE r.role_code = 'ROLE_EMPLOYEE';

-- 内训师：既有员工门户（内训师本身也是学习者），也有内训师门户
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT r.id, m.id
  FROM sys_role r
  JOIN sys_menu m ON m.id IN (1, 2, 3, 4, 5, 6, 7, 8, 10, 11, 12, 13, 14, 15, 16)
 WHERE r.role_code = 'ROLE_TRAINER';

-- 管理员：全部菜单（含新增的 8/16/32）
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT r.id, m.id
  FROM sys_role r
  JOIN sys_menu m ON 1 = 1
 WHERE r.role_code = 'ROLE_MANAGER';

-- 校验：
--   SELECT r.role_code, COUNT(*) FROM sys_role_menu rm
--     JOIN sys_role r ON r.id = rm.role_id GROUP BY r.role_code;
--   期望：员工 8 条、内训师 15 条、管理员 = sys_menu 总行数
