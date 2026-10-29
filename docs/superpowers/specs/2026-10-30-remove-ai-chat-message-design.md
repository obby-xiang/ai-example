# 移除 ai_chat_message 设计

## 目标与边界

按用户指定方案，移除 `ai_chat_message` 的全部应用交互并删除该表，消除 AI 对话正文、工具调用/回执及推理元数据在业务数据库中的长期留存。

只改对话完整历史这条链路。`ai_feedback`、`ai_provider`、`ai_model`、`ai_setting`、`ai_config_audit` 以及全部业务表保持现状；赞踩反馈功能和 Redis 会话/运行态不改为其他存储。

## 方案选择

采用“完整移除链路 + Flyway 新迁移删表”。只删表会留下 JPA 启动校验、完整历史接口和会话销毁故障；把表留空则不符合“把这个表删了”。不采用迁移对话数据到其他数据库表，因为用户明确要求其他表不动。

## 实施范围

- 删除 `AiChatMessage`、其 Repository、`AiChatHistoryService`、`AiChatHistoryRecorder`。
- 移除首轮、工具轮、停止轮、续跑收敛中的数据库历史写入；Redis 记忆、RunSnapshot、SSE 归档及续跑流程保持原样。
- 移除 `GET /api/ai/history/{sessionId}/messages` 及其依赖；保留现有 Redis 窗口接口 `GET /api/ai/history/{sessionId}`。
- 会话销毁不再枚举或删除 `ai_chat_message`，仍按现状处理 `ai_feedback` 和其余存储面。
- 新增一个 Flyway 迁移，仅 `DROP TABLE IF EXISTS ai_chat_message`；不修改既有迁移，不动其他表。
- 同步受影响测试与文档注释，不改无关功能。

## 影响

正常聊天、工具调用、HITL、Redis 挂起续跑和当前前端 40 条窗口恢复不依赖该表，保持原行为。数据库完整历史分页能力取消；已有表内数据随删表永久删除。反馈表及其功能保留。

## 验证

仅执行精确单点测试：覆盖正常 Redis 历史/续跑不依赖该表、会话销毁仍清反馈且跳过已删除历史面、迁移只删除目标表，以及受影响控制器/运行链路的针对性用例。主控只做代码验收，不批量或循环跑测试。
