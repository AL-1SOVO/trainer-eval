-- ============================================================
-- Enterprise Training & Assessment System - Database Schema
-- ============================================================

CREATE DATABASE IF NOT EXISTS trainer_eval CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE trainer_eval;

-- ==================== System Module ====================

DROP TABLE IF EXISTS sys_role_menu;
DROP TABLE IF EXISTS sys_user_role;
DROP TABLE IF EXISTS sys_menu;
DROP TABLE IF EXISTS sys_user;
DROP TABLE IF EXISTS sys_role;
DROP TABLE IF EXISTS sys_dept;

CREATE TABLE sys_dept (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    parent_id BIGINT NOT NULL DEFAULT 0,
    dept_name VARCHAR(64) NOT NULL,
    dept_code VARCHAR(32) NOT NULL UNIQUE,
    leader VARCHAR(32),
    phone VARCHAR(20),
    sort_order INT NOT NULL DEFAULT 0,
    status TINYINT NOT NULL DEFAULT 1,
    create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE sys_user (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(32) NOT NULL UNIQUE,
    password VARCHAR(128) NOT NULL,
    real_name VARCHAR(32) NOT NULL,
    email VARCHAR(64),
    phone VARCHAR(20),
    avatar VARCHAR(256),
    gender TINYINT DEFAULT 0,
    dept_id BIGINT,
    status TINYINT NOT NULL DEFAULT 1,
    last_login_time DATETIME(3),
    create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    FOREIGN KEY (dept_id) REFERENCES sys_dept(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE sys_role (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    role_code VARCHAR(32) NOT NULL UNIQUE,
    role_name VARCHAR(32) NOT NULL,
    description VARCHAR(256),
    sort_order INT NOT NULL DEFAULT 0,
    status TINYINT NOT NULL DEFAULT 1,
    create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE sys_user_role (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    role_id BIGINT NOT NULL,
    UNIQUE KEY uk_user_role (user_id, role_id),
    FOREIGN KEY (user_id) REFERENCES sys_user(id) ON DELETE CASCADE,
    FOREIGN KEY (role_id) REFERENCES sys_role(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE sys_menu (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    parent_id BIGINT NOT NULL DEFAULT 0,
    menu_name VARCHAR(32) NOT NULL,
    menu_type CHAR(1) NOT NULL DEFAULT 'M',
    path VARCHAR(128),
    component VARCHAR(128),
    permission VARCHAR(64),
    icon VARCHAR(32),
    sort_order INT NOT NULL DEFAULT 0,
    visible TINYINT NOT NULL DEFAULT 1,
    status TINYINT NOT NULL DEFAULT 1,
    create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE sys_role_menu (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    role_id BIGINT NOT NULL,
    menu_id BIGINT NOT NULL,
    UNIQUE KEY uk_role_menu (role_id, menu_id),
    FOREIGN KEY (role_id) REFERENCES sys_role(id) ON DELETE CASCADE,
    FOREIGN KEY (menu_id) REFERENCES sys_menu(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ==================== Training Module ====================

DROP TABLE IF EXISTS tra_learning_progress;
DROP TABLE IF EXISTS tra_enrollment;
DROP TABLE IF EXISTS tra_material;
DROP TABLE IF EXISTS tra_chapter;
DROP TABLE IF EXISTS tra_course;

CREATE TABLE tra_course (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    course_name VARCHAR(128) NOT NULL,
    description TEXT,
    cover_image VARCHAR(256),
    dept_id BIGINT,
    trainer_id BIGINT NOT NULL,
    total_chapters INT NOT NULL DEFAULT 0,
    enroll_count INT NOT NULL DEFAULT 0,
    status TINYINT NOT NULL DEFAULT 0,
    create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    FOREIGN KEY (trainer_id) REFERENCES sys_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE tra_chapter (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    course_id BIGINT NOT NULL,
    title VARCHAR(128) NOT NULL,
    description VARCHAR(512),
    sort_order INT NOT NULL DEFAULT 0,
    material_count INT NOT NULL DEFAULT 0,
    create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    FOREIGN KEY (course_id) REFERENCES tra_course(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE tra_material (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    chapter_id BIGINT NOT NULL,
    title VARCHAR(128) NOT NULL,
    material_type VARCHAR(16) NOT NULL,
    file_url VARCHAR(512),
    file_size BIGINT,
    content LONGTEXT,
    duration INT,
    sort_order INT NOT NULL DEFAULT 0,
    create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    FOREIGN KEY (chapter_id) REFERENCES tra_chapter(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE tra_enrollment (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    course_id BIGINT NOT NULL,
    progress DECIMAL(5,2) NOT NULL DEFAULT 0.00,
    status TINYINT NOT NULL DEFAULT 0,
    enroll_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    complete_time DATETIME(3),
    UNIQUE KEY uk_user_course (user_id, course_id),
    FOREIGN KEY (user_id) REFERENCES sys_user(id) ON DELETE CASCADE,
    FOREIGN KEY (course_id) REFERENCES tra_course(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE tra_learning_progress (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    material_id BIGINT NOT NULL,
    enrollment_id BIGINT NOT NULL,
    is_completed TINYINT NOT NULL DEFAULT 0,
    first_access_time DATETIME(3),
    complete_time DATETIME(3),
    UNIQUE KEY uk_user_material (user_id, material_id),
    FOREIGN KEY (user_id) REFERENCES sys_user(id) ON DELETE CASCADE,
    FOREIGN KEY (material_id) REFERENCES tra_material(id) ON DELETE CASCADE,
    FOREIGN KEY (enrollment_id) REFERENCES tra_enrollment(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ==================== Question Bank Module ====================

DROP TABLE IF EXISTS qb_paper_question;
DROP TABLE IF EXISTS qb_exam_paper;
DROP TABLE IF EXISTS qb_question;

CREATE TABLE qb_question (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    course_id BIGINT NOT NULL,
    question_type VARCHAR(16) NOT NULL,
    content TEXT NOT NULL,
    options JSON,
    reference_answer TEXT,
    scoring_criteria TEXT,
    default_score INT NOT NULL DEFAULT 5,
    difficulty TINYINT NOT NULL DEFAULT 1,
    analysis TEXT,
    tags VARCHAR(256),
    creator_id BIGINT NOT NULL,
    status TINYINT NOT NULL DEFAULT 1,
    create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    FOREIGN KEY (course_id) REFERENCES tra_course(id),
    FOREIGN KEY (creator_id) REFERENCES sys_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE qb_exam_paper (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    course_id BIGINT NOT NULL,
    paper_name VARCHAR(128) NOT NULL,
    description VARCHAR(512),
    total_score INT NOT NULL DEFAULT 0,
    pass_score INT NOT NULL DEFAULT 0,
    duration INT NOT NULL DEFAULT 60,
    shuffle_questions TINYINT NOT NULL DEFAULT 0,
    shuffle_options TINYINT NOT NULL DEFAULT 0,
    allow_retake TINYINT NOT NULL DEFAULT 1,
    max_attempts INT NOT NULL DEFAULT 3,
    status TINYINT NOT NULL DEFAULT 0,
    creator_id BIGINT NOT NULL,
    create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    FOREIGN KEY (course_id) REFERENCES tra_course(id),
    FOREIGN KEY (creator_id) REFERENCES sys_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE qb_paper_question (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    paper_id BIGINT NOT NULL,
    question_id BIGINT NOT NULL,
    score INT NOT NULL DEFAULT 5,
    sort_order INT NOT NULL DEFAULT 0,
    UNIQUE KEY uk_paper_question (paper_id, question_id),
    FOREIGN KEY (paper_id) REFERENCES qb_exam_paper(id) ON DELETE CASCADE,
    FOREIGN KEY (question_id) REFERENCES qb_question(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ==================== Exam Module ====================

DROP TABLE IF EXISTS ex_wrong_answer_analysis;
DROP TABLE IF EXISTS ex_exam_answer;
DROP TABLE IF EXISTS ex_exam_record;

CREATE TABLE ex_exam_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    paper_id BIGINT NOT NULL,
    course_id BIGINT NOT NULL,
    attempt_number INT NOT NULL DEFAULT 1,
    total_score INT,
    objective_score INT DEFAULT 0,
    subjective_score INT DEFAULT 0,
    status VARCHAR(16) NOT NULL DEFAULT 'IN_PROGRESS',
    start_time DATETIME(3) NOT NULL,
    submit_time DATETIME(3),
    grade_time DATETIME(3),
    review_by BIGINT,
    review_time DATETIME(3),
    create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    FOREIGN KEY (user_id) REFERENCES sys_user(id),
    FOREIGN KEY (paper_id) REFERENCES qb_exam_paper(id),
    FOREIGN KEY (course_id) REFERENCES tra_course(id),
    FOREIGN KEY (review_by) REFERENCES sys_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE ex_exam_answer (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    record_id BIGINT NOT NULL,
    question_id BIGINT NOT NULL,
    question_type VARCHAR(16) NOT NULL,
    question_content TEXT NOT NULL,
    question_options JSON,
    question_score INT NOT NULL,
    user_answer TEXT,
    is_correct TINYINT,
    score INT,
    ai_graded TINYINT NOT NULL DEFAULT 0,
    ai_score INT,
    ai_feedback TEXT,
    manual_score INT,
    grader_id BIGINT,
    grade_time DATETIME(3),
    UNIQUE KEY uk_record_question (record_id, question_id),
    FOREIGN KEY (record_id) REFERENCES ex_exam_record(id) ON DELETE CASCADE,
    FOREIGN KEY (question_id) REFERENCES qb_question(id),
    FOREIGN KEY (grader_id) REFERENCES sys_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE ex_wrong_answer_analysis (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    answer_id BIGINT NOT NULL,
    question_id BIGINT NOT NULL,
    error_category VARCHAR(32),
    error_analysis TEXT,
    recommendation TEXT,
    related_material_ids JSON,
    create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    FOREIGN KEY (user_id) REFERENCES sys_user(id),
    FOREIGN KEY (answer_id) REFERENCES ex_exam_answer(id) ON DELETE CASCADE,
    FOREIGN KEY (question_id) REFERENCES qb_question(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ==================== AI Grading Module ====================

DROP TABLE IF EXISTS ai_grading_log;
DROP TABLE IF EXISTS ai_grading_rule;

CREATE TABLE ai_grading_rule (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    course_id BIGINT NOT NULL,
    question_type VARCHAR(16) NOT NULL,
    rule_name VARCHAR(64) NOT NULL,
    prompt_template TEXT NOT NULL,
    model VARCHAR(32) NOT NULL DEFAULT 'gpt-4o',
    temperature DECIMAL(3,2) NOT NULL DEFAULT 0.30,
    max_tokens INT NOT NULL DEFAULT 2000,
    status TINYINT NOT NULL DEFAULT 1,
    create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    FOREIGN KEY (course_id) REFERENCES tra_course(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE ai_grading_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    record_id BIGINT NOT NULL,
    answer_id BIGINT NOT NULL,
    rule_id BIGINT,
    model VARCHAR(32) NOT NULL,
    prompt_system TEXT NOT NULL,
    prompt_user TEXT NOT NULL,
    response_raw TEXT NOT NULL,
    score INT,
    reasoning TEXT,
    tokens_prompt INT NOT NULL DEFAULT 0,
    tokens_completion INT NOT NULL DEFAULT 0,
    tokens_total INT NOT NULL DEFAULT 0,
    latency_ms INT NOT NULL DEFAULT 0,
    cost DECIMAL(10,6) NOT NULL DEFAULT 0.000000,
    status VARCHAR(16) NOT NULL DEFAULT 'SUCCESS',
    error_msg TEXT,
    create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    FOREIGN KEY (record_id) REFERENCES ex_exam_record(id),
    FOREIGN KEY (answer_id) REFERENCES ex_exam_answer(id),
    FOREIGN KEY (rule_id) REFERENCES ai_grading_rule(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ==================== Report Module ====================

DROP TABLE IF EXISTS rpt_export_log;

CREATE TABLE rpt_export_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    report_type VARCHAR(32) NOT NULL,
    report_format VARCHAR(8) NOT NULL,
    params JSON,
    file_name VARCHAR(256),
    file_url VARCHAR(512),
    file_size BIGINT,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    error_msg TEXT,
    create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    complete_time DATETIME(3),
    FOREIGN KEY (user_id) REFERENCES sys_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ==================== Views ====================

CREATE OR REPLACE VIEW v_user_course_stats AS
SELECT e.user_id, e.course_id, c.course_name, d.dept_name,
       e.progress, e.status, e.enroll_time, e.complete_time
FROM tra_enrollment e
JOIN tra_course c ON e.course_id = c.id
LEFT JOIN sys_user u ON e.user_id = u.id
LEFT JOIN sys_dept d ON u.dept_id = d.id;

CREATE OR REPLACE VIEW v_exam_result_stats AS
SELECT r.user_id, r.course_id, r.paper_id, p.paper_name,
       r.total_score, r.objective_score, r.subjective_score,
       r.status, p.pass_score,
       CASE WHEN r.total_score >= p.pass_score THEN 1 ELSE 0 END AS is_pass
FROM ex_exam_record r
JOIN qb_exam_paper p ON r.paper_id = p.id;

-- ==================== Agent ====================

CREATE TABLE IF NOT EXISTS agent_message (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id VARCHAR(64) NOT NULL,
    user_id BIGINT NOT NULL,
    role VARCHAR(16) NOT NULL COMMENT 'user/assistant/tool',
    content MEDIUMTEXT NULL,
    tool_name VARCHAR(64) NULL,
    tool_args TEXT NULL,
    tool_result MEDIUMTEXT NULL,
    create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    KEY idx_session (session_id),
    KEY idx_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
