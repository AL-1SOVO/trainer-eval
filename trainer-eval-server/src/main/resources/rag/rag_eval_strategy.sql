-- ============================================================
-- 评测表增加 strategy 列：让"LIKE 基线"与"RAG 链路"的指标可分别留档对比
-- （对应《企业级RAG实施方案.md》阶段五：现状基线 vs RAG）
-- 幂等：列已存在则忽略报错
-- ============================================================
SET NAMES utf8mb4;
USE trainer_eval;

ALTER TABLE rag_eval_run
    ADD COLUMN strategy VARCHAR(32) NOT NULL DEFAULT 'CHAIN' AFTER id;

ALTER TABLE rag_eval_run
    ADD KEY idx_eval_strategy_time (strategy, create_time);
