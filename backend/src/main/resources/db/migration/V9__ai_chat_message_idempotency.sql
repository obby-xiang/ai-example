-- 完整历史第二阶段：写入幂等键。
--
-- 为什么需要它：
--   * 同一轮对话里 user / assistant(tool_calls) / tool / assistant(final) 是多条消息，
--     `(session_id, run_id, message_id)` 才能唯一标识"一条消息"；
--   * 取消/重复 converge/进程重启后的 resume 会重新经过同一落定点，必须"首次写入胜、后续命中返回"，
--     不能靠 seq 判断（seq 只保证会话内顺序，重复写入会拿到新 seq）。
--
-- 注意：第一阶段底座未接入聊天链路，正常库中不应已有本表数据；若手工写入过重复三元组，
-- 本索引会显式失败（不静默删历史）。重复行需人工裁决后再加索引。

CREATE UNIQUE INDEX IF NOT EXISTS uq_ai_chat_message_session_run_message
    ON ai_chat_message (session_id, run_id, message_id);
