-- 移除 ai_chat_message：应用侧读写链路已整体退场，本迁移只删目标表。
-- 边界：ai_feedback 与 AI 配置表（ai_provider / ai_model / ai_setting / ai_config_audit）、
-- 业务表一概不动；V8/V9 保持原样。IF EXISTS 让未跑过建表的库也能安全通过。
DROP TABLE IF EXISTS ai_chat_message;
