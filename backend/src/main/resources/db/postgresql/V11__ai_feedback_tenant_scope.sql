-- V11：ai_feedback 的租户/用户隔离（2026-10-30 · 租户任务 2 收尾组）。
--
-- 背景：反馈是<b>会话数据</b>（设置页「AI 反馈」按身份读、会话销毁按会话删）。V3 只有
--   session_id + created_by 预留位 ⇒ 两个身份用同一个 sessionId / messageId 时会互相覆盖，
--   销毁一方会把另一方的行删掉。本迁移只动 ai_feedback 一张表。
--
-- 口径：
--   ① 新增 tenant_id / user_id（BIGINT NOT NULL DEFAULT 0）：存量行一律落到 (0,0) —— 那是
--      "无归属 legacy"，不属于任何真实身份；新身份的读/写/删都不会命中它（legacy 命名空间）；
--   ② 唯一键从 message_id 改为 (tenant_id, user_id, message_id)：同一 messageId 在不同身份下
--      各自一行（幂等 upsert 的粒度变成"身份内"）；legacy (0,0) 行的唯一性不变；
--   ③ 补一条按身份读列表的索引（设置页按 updated_at 倒序取前 100）；
--   ④ 无方言差异（仅 ALTER TABLE / CREATE INDEX，无 IDENTITY/CLOB/序列）⇒
--      db/migration（H2）与 db/postgresql（PG）两份逐字节相同。
--
-- 不动：业务表，以及 ai_provider / ai_model / ai_setting / ai_config_audit（AI 模型配置面）。

ALTER TABLE ai_feedback ADD COLUMN tenant_id BIGINT NOT NULL DEFAULT 0;
ALTER TABLE ai_feedback ADD COLUMN user_id BIGINT NOT NULL DEFAULT 0;

ALTER TABLE ai_feedback DROP CONSTRAINT uq_ai_feedback_message;
ALTER TABLE ai_feedback ADD CONSTRAINT uq_ai_feedback_message UNIQUE (tenant_id, user_id, message_id);

CREATE INDEX idx_ai_feedback_scope ON ai_feedback (tenant_id, user_id, updated_at);
