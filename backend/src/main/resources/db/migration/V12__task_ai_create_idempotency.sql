-- V12：AI 创建任务的数据库幂等兜底（L-1，2026-11-03 · Redis 锁缓存与内存驻留审查）。
--
-- 背景：createIdempotent 原先只靠 settings_json LIKE 查重 + 方法体内的 synchronized：
--   ① 锁在 @Transactional 方法体内 ⇒ 解锁先于提交，第二个调用者在 READ COMMITTED 下读不到
--      未提交插入，可重复建任务；② settings_json 是 CLOB/TEXT，建不了唯一索引，跨实例完全没有兜底。
--
-- 口径：
--   ① 新增 ai_create_run / ai_create_key 两列：settings_json 里 aiCreateRun / aiCreateKey 的
--      **可索引镜像**。幂等标记仍按原规格写在 settings_json，业务语义不变；这两列只服务唯一约束；
--   ② 各自唯一：同一 runId 至多一个任务、无 runId 时同一幂等键至多一个任务。
--      普通（非 AI）创建两列留 NULL，唯一约束允许多个 NULL（H2 / PG 一致），不互相阻塞；
--   ③ 项目未上线，不回填存量（存量行两列 NULL，不再参与幂等判定）。
--
-- 无方言差异（仅 ALTER TABLE）⇒ db/migration（H2）与 db/postgresql（PG）两份逐字节相同。

ALTER TABLE tasks ADD COLUMN ai_create_run VARCHAR(255);
ALTER TABLE tasks ADD COLUMN ai_create_key VARCHAR(64);

ALTER TABLE tasks ADD CONSTRAINT uq_tasks_ai_create_run UNIQUE (ai_create_run);
ALTER TABLE tasks ADD CONSTRAINT uq_tasks_ai_create_key UNIQUE (ai_create_key);
