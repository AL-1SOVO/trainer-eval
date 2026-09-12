-- ==================== Seed Data ====================

USE trainer_eval;

-- Roles
INSERT INTO sys_role (role_code, role_name, description, sort_order) VALUES
('ROLE_EMPLOYEE', '员工', '新员工/普通员工，可学习课程和参加考试', 1),
('ROLE_TRAINER', '内训师', '内部培训师，可创建课程、题库和试卷', 2),
('ROLE_MANAGER', '管理员', '系统管理员，拥有全部权限', 3);

-- Department
INSERT INTO sys_dept (id, parent_id, dept_name, dept_code, sort_order) VALUES
(1, 0, '总公司', 'HQ', 0),
(2, 1, '技术部', 'TECH', 1),
(3, 1, '产品部', 'PROD', 2),
(4, 1, '市场部', 'MKT', 3),
(5, 1, '人力资源部', 'HR', 4);

-- Admin user (password: admin123, BCrypt encoded)
INSERT INTO sys_user (username, password, real_name, email, phone, dept_id, status) VALUES
('admin', '$2a$10$faM9BuOwAkPQfJJWqSuJyu9cNd.S.48stfUqhBbKOtwhvJVKtW/4C', '系统管理员', 'admin@company.com', '13800000000', 1, 1);

-- Admin gets MANAGER role
INSERT INTO sys_user_role (user_id, role_id) VALUES (1, 3);

-- Menus (3-level: directory -> menu -> button)
INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, path, component, permission, icon, sort_order) VALUES
-- Employee Portal
(1, 0, '员工门户', 'M', '/employee', NULL, NULL, 'Monitor', 1),
(2, 1, '学习中心', 'C', 'courses', 'employee/course/CourseList', NULL, 'Reading', 1),
(3, 1, '我的课程', 'C', 'my-courses', 'employee/mycourse/MyCourseList', NULL, 'Collection', 2),
(4, 1, '我的考试', 'C', 'my-exams', 'employee/exam/ExamList', NULL, 'EditPen', 3),
(5, 1, '错题分析', 'C', 'wrong-analysis', 'employee/analysis/WrongAnalysis', NULL, 'Warning', 4),
(6, 1, 'AI学习助手', 'C', 'ai-tutor', 'employee/agent/AiTutor', NULL, 'ChatDotRound', 5),

-- Trainer Portal
(10, 0, '内训师门户', 'M', '/trainer', NULL, NULL, 'UserFilled', 2),
(11, 10, '课程管理', 'C', 'course-manage', 'trainer/course/CourseManage', NULL, 'Document', 1),
(12, 10, '题库管理', 'C', 'question-bank', 'trainer/question/QuestionList', NULL, 'Tickets', 2),
(13, 10, '试卷管理', 'C', 'paper-manage', 'trainer/paper/PaperList', NULL, 'Files', 3),
(14, 10, '阅卷中心', 'C', 'grading', 'trainer/grading/GradingQueue', NULL, 'Checked', 4),
(15, 10, 'AI评分配置', 'C', 'ai-config', 'trainer/aiconfig/AiRuleConfig', NULL, 'Setting', 5),

-- Manager Portal
(20, 0, '管理后台', 'M', '/manager', NULL, NULL, 'Setting', 3),
(21, 20, '用户管理', 'C', 'users', 'manager/user/UserList', 'sys:user:list', 'User', 1),
(22, 21, '新增用户', 'F', NULL, NULL, 'sys:user:add', NULL, 1),
(23, 21, '编辑用户', 'F', NULL, NULL, 'sys:user:edit', NULL, 2),
(24, 21, '删除用户', 'F', NULL, NULL, 'sys:user:delete', NULL, 3),
(25, 20, '角色管理', 'C', 'roles', 'manager/role/RoleManage', 'sys:role:list', 'Avatar', 2),
(26, 20, '部门管理', 'C', 'depts', 'manager/dept/DeptManage', 'sys:dept:list', 'OfficeBuilding', 3),
(27, 20, '菜单管理', 'C', 'menus', 'manager/menu/MenuManage', 'sys:menu:list', 'Menu', 4),
(28, 20, '培训监管', 'C', 'all-courses', 'manager/training/AllCourses', NULL, 'DataAnalysis', 5),
(29, 20, '考试统计', 'C', 'exam-stats', 'manager/exam/ExamStats', NULL, 'TrendCharts', 6),
(30, 20, '报表中心', 'C', 'reports', 'manager/report/ReportGenerate', NULL, 'Printer', 7),
(31, 20, 'AI评分日志', 'C', 'ai-logs', 'manager/log/AiGradingLog', NULL, 'DocumentChecked', 8);

-- Manager role gets ALL menus
INSERT INTO sys_role_menu (role_id, menu_id)
SELECT 3, id FROM sys_menu;
