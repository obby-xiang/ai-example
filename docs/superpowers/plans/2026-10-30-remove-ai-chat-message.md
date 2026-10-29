# Remove ai_chat_message Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 移除 `ai_chat_message` 的全部应用交互并仅删除该表。

**Architecture:** 完整移除数据库历史写入、读取、实体和销毁关联；Redis 记忆、运行快照、SSE 与反馈保持原路径。以一个新的 Flyway 迁移删除目标表，不改历史迁移和其他表。

**Tech Stack:** Java 21、Spring Boot/JPA、Flyway、JUnit。

**Spec:** `docs/superpowers/specs/2026-10-30-remove-ai-chat-message-design.md`

## Global Constraints

- 主仓库 `<REPO_ROOT>`（本工作树）施工。
- `ai_feedback` 与所有 AI 配置表、业务表不动。
- 只能精确单点测试，禁止整类、整文件、批量或循环测试。
- 不改变 Redis 记忆、RunSnapshot、SSE 和现有 `/api/ai/history/{sessionId}` 窗口接口。

## Review Focus

- 删除表后 JPA 不再有实体校验依赖，后端可启动。
- 正常聊天和工具续跑不再调用数据库历史写入器。
- 会话销毁仍处理 `ai_feedback`，但不访问 `ai_chat_message`。
- 新迁移只删除 `ai_chat_message`，不删除或改写其他表。
- 当前 Redis 历史窗口和反馈接口保持原契约。

### Task 1: 移除数据库完整历史链路

**Files:**
- Delete: `backend/src/main/java/com/example/configmgr/ai/history/AiChatMessage.java`
- Delete: `backend/src/main/java/com/example/configmgr/ai/history/AiChatMessageRepository.java`
- Delete: `backend/src/main/java/com/example/configmgr/ai/history/AiChatHistoryService.java`
- Delete: `backend/src/main/java/com/example/configmgr/ai/history/AiChatHistoryRecorder.java`
- Modify: `AiController`、`ResilientChatService`、`ResumeService`、`SpToolCallingManager`、`SessionPurgeDatabase` 及受影响测试。

**Interfaces:**
- Consumes: 现有 Redis ChatMemory、RunSnapshot、FrameStore。
- Produces: 无数据库历史写入/读取；`GET /api/ai/history/{sessionId}` 保持不变；数据库完整历史端点移除。

- [ ] 将相关单点测试改为断言无数据库历史依赖，并先确认旧代码不符合目标。
- [ ] 删除四个数据库历史类及全部字段注入、导入和调用。
- [ ] 删除 `GET /api/ai/history/{sessionId}/messages`；会话销毁仅去掉 chat_message 面，保留反馈面。
- [ ] 精确运行受影响的最小单点用例并确保通过。

### Task 2: 删除目标表并收尾

**Files:**
- Create: `backend/src/main/resources/db/migration/V*__drop_ai_chat_message.sql`
- Modify: 受影响迁移测试、设计/计划文档。

**Interfaces:**
- Consumes: Task 1 已清除所有应用依赖。
- Produces: 数据库中不存在 `ai_chat_message`，其他表不变。

- [ ] 写一个单点迁移测试，断言目标表被删除且反馈/配置表仍存在。
- [ ] 取下一个迁移版本号，仅执行 `DROP TABLE IF EXISTS ai_chat_message`。
- [ ] 运行该精确迁移用例，并复查全仓生产代码无 `ai_chat_message` 引用。
- [ ] 主控代码验收后提交并推送。
