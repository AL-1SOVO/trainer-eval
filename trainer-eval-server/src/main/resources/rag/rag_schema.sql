-- ============================================================
-- RAG 层（参考 MimirQ 的"可检索 / 可替换 / 可回归"设计）
-- 幂等：可重复执行
-- ============================================================
SET NAMES utf8mb4;
USE trainer_eval;

-- ---------- 1. 切块表（可检索的基础：每一步都能看到切成了什么）----------
DROP TABLE IF EXISTS rag_chunk;
CREATE TABLE rag_chunk (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    material_id     BIGINT       NOT NULL,
    course_id       BIGINT,
    chapter_id      BIGINT,
    chunk_index     INT          NOT NULL,
    title           VARCHAR(255),
    content         TEXT         NOT NULL,
    char_len        INT          NOT NULL DEFAULT 0,
    -- 中文分词：必须用 ngram 解析器，默认解析器按空格分词，中文整段会被当成一个词
    FULLTEXT KEY ft_rag_chunk (title, content) WITH PARSER ngram,
    KEY idx_chunk_material (material_id),
    KEY idx_chunk_course (course_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------- 2. 检索轨迹（"错误可定位"：为什么召回的是这几条）----------
DROP TABLE IF EXISTS rag_retrieval_trace;
CREATE TABLE rag_retrieval_trace (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id         BIGINT,
    session_id      VARCHAR(64),
    query_text      VARCHAR(512) NOT NULL,
    strategy        VARCHAR(32)  NOT NULL,
    candidate_count INT          NOT NULL DEFAULT 0,
    hit_count       INT          NOT NULL DEFAULT 0,
    latency_ms      INT          NOT NULL DEFAULT 0,
    top_hits        JSON,
    create_time     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    KEY idx_trace_user_time (user_id, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------- 3. Golden 题集（"质量可回归"）----------
DROP TABLE IF EXISTS rag_golden_item;
CREATE TABLE rag_golden_item (
    id                    BIGINT AUTO_INCREMENT PRIMARY KEY,
    question              VARCHAR(512) NOT NULL,
    expected_material_ids JSON,
    note                  VARCHAR(255),
    enabled               TINYINT      NOT NULL DEFAULT 1,
    create_time           DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------- 4. 评测历史（版本质量门禁）----------
DROP TABLE IF EXISTS rag_eval_run;
CREATE TABLE rag_eval_run (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    top_k        INT          NOT NULL,
    item_count   INT          NOT NULL,
    hit_rate     DECIMAL(5,4) NOT NULL DEFAULT 0,
    recall       DECIMAL(5,4) NOT NULL DEFAULT 0,
    mrr          DECIMAL(5,4) NOT NULL DEFAULT 0,
    misses       JSON,
    latency_ms   INT          NOT NULL DEFAULT 0,
    create_time  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------- 5. Agent 消息表（若不存在则创建）----------
CREATE TABLE IF NOT EXISTS agent_message (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id  VARCHAR(64),
    user_id     BIGINT,
    role        VARCHAR(16)  NOT NULL,
    content     TEXT,
    tool_name   VARCHAR(64),
    tool_args   TEXT,
    tool_result MEDIUMTEXT,
    create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    KEY idx_agent_session (session_id, create_time),
    KEY idx_agent_user (user_id, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
