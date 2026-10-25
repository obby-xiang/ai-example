-- 完整对话历史独立存储（第一阶段底座）。
--
-- 边界（2026-10-25 裁决）：
--   1) ChatMemory/Redis 只给模型提供窗口（当前 40 条），用户可见的完整历史落本表，不随窗口裁剪；
--   2) 本表不复用 app.ai.session-ttl 的运行态 TTL，不做 24h 过期；
--   3) 本迁移只建表，不做旧数据回填（旧 Redis 记忆窗口不是完整历史，无法可靠还原）。
--
-- 结构口径：
--   * seq = 每会话单调递增序号（从 1 开始），(session_id, seq) 唯一；
--     追加由 AiChatHistoryService 以「MAX(seq)+1 + 唯一约束冲突重试」保证不重复、不跳号；
--   * run_id 非空、但 (session_id, run_id) 只建普通索引 —— 一轮里会有 user / assistant(tool_calls) /
--     tool / assistant(final) 多条消息，不能做唯一约束；
--   * message_id 是消息身份（与 SSE 帧/前端消息 id 同源）；同一轮内 (session_id, run_id, message_id)
--     唯一（V9 唯一索引）：同一 assistant 消息可能按工具轮次拆成多条行，但同一轮同一 id 只允许一行；
--     索引仅用于按消息 id 定位；
--   * tool_calls_json / tool_responses_json / metadata_json 按 MessageJsonCodec 的既有字段名保存原文，
--     不做二次解析或裁剪；metadata_json 可含 reasoningContent 及供应商未知元数据（不求全部展示）；
--   * 本表不含任何密钥/凭据列；
--   * 删除会话 = 物理 DELETE FROM ai_chat_message WHERE session_id = ?（由 service 提供）。

-- 可重入：V6 的既有迁移测试会删掉版本历史行再重跑（模拟“约束已建、版本未记”）。
-- 本表是 net-new，IF NOT EXISTS 让同一脚本在表/索引已存在时安全重放；不删除、不改写任何旧表。
CREATE TABLE IF NOT EXISTS ai_chat_message (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    session_id          VARCHAR(128) NOT NULL,
    run_id              VARCHAR(64)  NOT NULL,
    seq                 BIGINT       NOT NULL,
    message_id          VARCHAR(128) NOT NULL,
    role                VARCHAR(16)  NOT NULL, -- MessageType：SYSTEM / USER / ASSISTANT / TOOL
    text                CLOB,
    tool_calls_json     CLOB,
    tool_responses_json CLOB,
    metadata_json       CLOB,
    stopped             BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_ai_chat_message_session_seq UNIQUE (session_id, seq),
    CONSTRAINT ck_ai_chat_message_seq_positive CHECK (seq > 0)
);

-- 轮次级查询（同一 run 的多条消息）与按消息 id 定位。
CREATE INDEX IF NOT EXISTS ix_ai_chat_message_session_run ON ai_chat_message (session_id, run_id);
CREATE INDEX IF NOT EXISTS ix_ai_chat_message_session_message ON ai_chat_message (session_id, message_id);
