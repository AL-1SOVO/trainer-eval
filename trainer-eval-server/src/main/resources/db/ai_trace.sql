-- =============================================================================
-- AI 链路追踪本地表
-- =============================================================================
-- 为什么需要这张表（而不是只把链路发到 Langfuse）：
--   1. Langfuse 是可选基础设施（自托管要 Postgres + ClickHouse + Redis + MinIO），
--      本表让"链路可见"零依赖成立；
--   2. 导出随时可能失败（网络/鉴权/配额），本表保证数据一定在；
--   3. 管理端页面直接查本表，不需要把 Langfuse 密钥下发到前端。
--
-- 建表原则：只把【可检索的维度】建列，易变的结构塞进 detail 的 JSON。
--   · 稳定需求：按用户查、按链路名查、按耗时排序、找失败的 → 建列 + 索引
--   · 易变内容：每一步的明细（未来还会加字段）→ JSON，不改表
--
-- 幂等：CREATE TABLE IF NOT EXISTS。
-- 执行：mysql -uroot -p123456 trainer_eval < db/ai_trace.sql
-- =============================================================================

CREATE TABLE IF NOT EXISTS `ai_trace` (
    `id`                 BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `trace_id`           CHAR(32)     NOT NULL                COMMENT '32 位十六进制 traceId，与 Langfuse 侧一致',
    `name`               VARCHAR(64)  NOT NULL                COMMENT '链路名：agent.chat / rag.retrieve / ai.grade ...',
    `user_id`            BIGINT       NULL                    COMMENT '发起用户',
    `session_id`         VARCHAR(64)  NULL                    COMMENT '会话标识（Agent 对话用）',
    `latency_ms`         INT          NULL                    COMMENT '整条链路耗时(ms)',
    `prompt_tokens`      INT          NULL                    COMMENT '输入 token 合计',
    `completion_tokens`  INT          NULL                    COMMENT '输出 token 合计',
    `observation_count`  INT          NULL                    COMMENT '观测节点数（模型调用+检索+工具）',
    `level`              VARCHAR(16)  NULL                    COMMENT 'DEFAULT / WARNING / ERROR',
    `status_message`     VARCHAR(512) NULL                    COMMENT '异常说明',
    `input_brief`        VARCHAR(1024) NULL                   COMMENT '输入摘要（截断）',
    `output_brief`       VARCHAR(1024) NULL                   COMMENT '输出摘要（截断）',
    `detail`             MEDIUMTEXT   NULL                    COMMENT '全部观测明细 JSON',
    `exported`           TINYINT      NOT NULL DEFAULT 0      COMMENT '是否已投递 Langfuse：0 未配置/失败，1 已投递',
    `export_error`       VARCHAR(512) NULL                    COMMENT '导出失败原因',
    `create_time`        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_ai_trace_trace_id` (`trace_id`),
    KEY `idx_ai_trace_name_time` (`name`, `create_time`),
    KEY `idx_ai_trace_user_time` (`user_id`, `create_time`),
    KEY `idx_ai_trace_level` (`level`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'AI 链路追踪（本地主存储）';

-- 校验：
--   SELECT name, COUNT(*), AVG(latency_ms) FROM ai_trace GROUP BY name;
