-- ==================== Test Data ====================
USE trainer_eval;

-- Give admin all three roles so they can access everything
INSERT IGNORE INTO sys_user_role (user_id, role_id) VALUES (1, 1), (1, 2);

-- Add a test employee user (password: 123456)
INSERT INTO sys_user (username, password, real_name, email, phone, dept_id, status) VALUES
('employee1', '$2a$10$faM9BuOwAkPQfJJWqSuJyu9cNd.S.48stfUqhBbKOtwhvJVKtW/4C', '测试员工', 'emp1@company.com', '13800000001', 2, 1);
INSERT INTO sys_user_role (user_id, role_id) VALUES (2, 1);

-- Course 1: 企业安全培训
INSERT INTO tra_course (id, course_name, description, trainer_id, total_chapters, status) VALUES
(1, '企业信息安全培训', '涵盖企业信息安全基础知识、常见威胁防护和数据保护规范', 1, 3, 1);

INSERT INTO tra_chapter (id, course_id, title, description, sort_order) VALUES
(1, 1, '信息安全基础', '了解信息安全的基本概念和重要性', 0),
(2, 1, '常见网络威胁', '识别钓鱼邮件、恶意软件等常见攻击手段', 1),
(3, 1, '数据保护规范', '学习个人隐私和企业数据保护的相关法规', 2);

-- Enroll admin and employee in the course
INSERT INTO tra_enrollment (user_id, course_id, progress, status, enroll_time) VALUES
(1, 1, 100.00, 1, NOW()),
(2, 1, 100.00, 1, NOW());

-- Questions for the course (various types)
INSERT INTO qb_question (id, course_id, question_type, content, options, reference_answer, scoring_criteria, default_score, difficulty, analysis, tags, creator_id, status) VALUES
-- Single choice
(1, 1, 'SINGLE_CHOICE', '以下哪个是保护企业数据最有效的方法？',
 '["A. 定期备份数据","B. 使用简单密码","C. 不安装防病毒软件","D. 共享所有文件"]',
 'A', '选择A得满分', 5, 1, '定期备份是数据保护的基础措施，可以在数据丢失或损坏时快速恢复。', '数据保护,基础', 1, 1),
-- Multiple choice
(2, 1, 'MULTI_CHOICE', '以下哪些属于常见的网络安全威胁？（多选）',
 '["A. 钓鱼邮件","B. 勒索软件","C. 定期更新系统","D. 社会工程攻击"]',
 'A,B,D', '选对ABD得满分，多选错选不得分', 8, 2, '钓鱼邮件、勒索软件和社会工程攻击都是常见的网络安全威胁。定期更新系统是防护措施而非威胁。', '安全威胁,基础', 1, 1),
-- True/False
(3, 1, 'TRUE_FALSE', '使用公共WiFi时，通过VPN连接可以增强数据传输的安全性。',
 '["正确","错误"]',
 '正确', '判断正确得满分', 3, 1, 'VPN可以加密数据传输，在公共WiFi环境下使用能有效防止数据被窃取。', '网络安全,基础', 1, 1),
-- Short answer
(4, 1, 'SHORT_ANSWER', '请简述企业在处理客户数据时应遵循的三大原则。',
 NULL,
 '合法性原则：数据处理必须有合法依据；\n必要性原则：只收集必要的数据；\n安全性原则：采取适当的安全措施保护数据。',
 '答出三个原则各得2分，展开说明酌情加分', 10, 3, '这三大原则是数据保护法规的核心要求，企业在处理数据时必须严格遵守。', '数据保护,法规', 1, 1),
-- Essay
(5, 1, 'ESSAY', '某公司近期发生了一起数据泄露事件，请从技术和管理两个角度分析可能的原因，并提出至少三条改进建议。',
 NULL,
 '技术角度：未及时打补丁、访问控制不严格、加密措施不足、网络监控缺失；\n管理角度：员工安全意识培训不足、安全制度执行不到位、应急预案缺乏；\n改进建议：1.建立定期安全审计机制 2.加强员工安全培训 3.部署入侵检测系统 4.实施数据分级保护 5.制定应急响应预案',
 '技术分析(1-5分)、管理分析(1-5分)、改进建议每条2分', 15, 3, '这是一道综合分析题，考察学员对信息安全事件的全面分析能力。', '安全事件,综合分析', 1, 1);

-- Exam Paper
INSERT INTO qb_exam_paper (id, course_id, paper_name, description, total_score, pass_score, duration, shuffle_questions, shuffle_options, allow_retake, max_attempts, status, creator_id) VALUES
(1, 1, '企业信息安全培训考核', '针对企业信息安全培训课程的结业考试，涵盖基础知识和实际应用', 41, 24, 60, 0, 1, 1, 3, 1, 1);

-- Paper-Question mapping
INSERT INTO qb_paper_question (paper_id, question_id, score, sort_order) VALUES
(1, 1, 5, 0),
(1, 2, 8, 1),
(1, 3, 3, 2),
(1, 4, 10, 3),
(1, 5, 15, 4);

-- Exam record for admin (completed, some wrong answers)
INSERT INTO ex_exam_record (id, user_id, paper_id, course_id, attempt_number, total_score, objective_score, subjective_score, status, start_time, submit_time) VALUES
(1, 1, 1, 1, 1, 18, 8, 10, 'GRADED', '2026-05-19 09:00:00', '2026-05-19 10:00:00');

-- Exam answers for admin (questions 1-3 wrong, 4-5 partially correct)
INSERT INTO ex_exam_answer (record_id, question_id, question_type, question_content, question_options, question_score, user_answer, is_correct, score, ai_graded, ai_feedback) VALUES
-- Q1: Single choice - WRONG (user picked B)
(1, 1, 'SINGLE_CHOICE', '以下哪个是保护企业数据最有效的方法？', '["A. 定期备份数据","B. 使用简单密码","C. 不安装防病毒软件","D. 共享所有文件"]', 5, 'B', 0, 0, 0, NULL),
-- Q2: Multiple choice - WRONG (user picked A,B only, missing D)
(1, 2, 'MULTI_CHOICE', '以下哪些属于常见的网络安全威胁？（多选）', '["A. 钓鱼邮件","B. 勒索软件","C. 定期更新系统","D. 社会工程攻击"]', 8, 'A,B', 0, 0, 0, NULL),
-- Q3: True/False - CORRECT
(1, 3, 'TRUE_FALSE', '使用公共WiFi时，通过VPN连接可以增强数据传输的安全性。', '["正确","错误"]', 3, '正确', 1, 3, 0, NULL),
-- Q4: Short answer - PARTIALLY CORRECT (user gave generic answer)
(1, 4, 'SHORT_ANSWER', '请简述企业在处理客户数据时应遵循的三大原则。', NULL, 10, '要保护客户数据安全，不能泄露', 0, 5, 1, '回答较为笼统，未具体说明三个原则。但提到了数据安全保护的核心概念。建议学习数据保护的三大原则具体内容。'),
-- Q5: Essay - PARTIALLY CORRECT
(1, 5, 'ESSAY', '某公司近期发生了一起数据泄露事件，请从技术和管理两个角度分析可能的原因，并提出至少三条改进建议。', NULL, 15, '技术方面可能是黑客攻击了服务器，管理方面是员工没有安全意识。建议加强防火墙和员工培训。', 0, 10, 1, '回答涉及了技术和管理两个角度，但分析不够深入。技术角度未涉及具体漏洞类型，管理角度较笼统。改进建议只有两条，较简单。建议深入理解信息安全管理体系。');

-- Wrong answer analysis records (pre-populated so the page has data to show)
INSERT INTO ex_wrong_answer_analysis (user_id, answer_id, question_id, error_category, error_analysis, recommendation, related_material_ids, create_time) VALUES
(1, 1, 1, 'MISUNDERSTANDING', '学员将"使用简单密码"误认为有效的安全措施，说明对基本安全概念存在理解偏差。保护数据最有效的方法是定期备份，因为简单密码容易被破解，不安装杀毒软件会增加风险，共享文件本身就是安全隐患。', '建议重新学习"信息安全基础"章节中关于数据保护措施的内容，重点理解备份策略和密码安全的基本原理。', '[1]', NOW()),
(1, 2, 2, 'KNOWLEDGE_GAP', '学员漏选了"社会工程攻击"，只选择了钓鱼邮件和勒索软件。这表明学员对社会工程攻击的概念不够了解，不知道它是通过心理操纵获取信息的一种常见攻击方式。', '建议学习"常见网络威胁"章节，重点关注社会工程攻击的定义、常见形式（如假冒身份、钓鱼电话等）以及防范方法。', '[2]', NOW()),
(1, 4, 4, 'KNOWLEDGE_GAP', '学员的回答过于笼统，仅提及"保护客户数据安全"的抽象概念，未涉及数据保护法规要求的具体原则。对数据保护三大原则（合法性、必要性、安全性）缺乏了解。', '建议学习"数据保护规范"章节，重点掌握《数据安全法》等法规中关于数据处理的基本原则，并能结合具体场景应用。', '[3]', NOW()),
(1, 5, 5, 'MISUNDERSTANDING', '学员的分析停留在表面层面，技术分析较为单一（仅提到黑客攻击和防火墙），管理分析不够具体（仅提到员工培训），改进建议数量不足且缺乏系统性。', '建议系统学习信息安全管理体系（ISMS）框架，从预防、检测、响应三个维度思考安全问题，并结合企业实际情况提出可落地的改进措施。', '[1,2,3]', NOW());

-- Mark analyses as having been analyzed
UPDATE ex_exam_answer SET ai_graded = 1 WHERE record_id = 1 AND question_id IN (4, 5);
