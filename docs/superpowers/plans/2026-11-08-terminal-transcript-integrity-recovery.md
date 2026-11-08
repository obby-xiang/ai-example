# Terminal Transcript Integrity Recovery Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修复隐藏 reasoning 导致最终助手历史缺失的问题，并将 run 权威完成与 transcript 降级及真实 Redis 故障准确区分。

**Architecture:** 存储 reasoning 与可见 reasoning 分离，仅展示开启时校验 reasoning 帧。保留现有有界重试、runEpoch fencing 和 purge guard；以最小结果分类区分时间线构造失败、写入拒绝与 Redis 临时异常，成功 run 通过 typed transcript error + turn_end=done 明确降级，不再统一 AI_REDIS_UNAVAILABLE/unconfirmed。

**Tech Stack:** Java 21、Spring、现有 SseChatEmitter/ConversationTranscriptStore/RunStore。

**Spec:** agent-1099 根因报告；`docs/delivery/development-standards.md`；真实证据 `<仓外证据根>/ai-reasoning-continuity-rerun-20261108-182814/`。

## Global Constraints

- 普通测试代码不得修改，施工阶段不执行普通测试；生产 Maven compile/package 必须通过。
- `display-enabled=false` 时存储 reasoning 不进入展示帧对账；`true` 时仍严格校验，不能跳过完整时间线。
- run 快照 DONE 与 transcript 完整度分别报告；typed error 必须含 `transcriptDegraded=true`、`historyComplete=false`、`runStatus=DONE`，`turn_end=done`，不得误称 unconfirmed。
- `AI_TRANSCRIPT_INCOMPLETE` 只用于时间线构造失败，`AI_TRANSCRIPT_WRITE_FAILED` 用于非重试写入拒绝或非 Redis 异常，`AI_REDIS_UNAVAILABLE` 仅用于真实可重试 Redis 故障耗尽。
- 不新增数据库表、Redis 脚本或跨槽事务，不绕过 fencing/purge guard，不隐藏失败正文或原始异常。
- 保留普通取消、失败、UNCONFIRMED 权威快照未知路径，不把本修复扩展为全终态重写。

## Review Focus

- 隐藏 reasoning 在第一、第二和最终 assistant 时，最终 `/history` 必须包含最后 assistant；由 DeepSeek 三轮验证。
- 展示开启且归档 reasoning 缺段仍须 `AI_TRANSCRIPT_INCOMPLETE`，不得被新规则放行。
- append 拒绝、真实 Redis 超时和时间线失败不得共用错误码；由独立审查及后续故障注入验证。
- typed error 与 run DONE 不得让 UI 把历史缺失误报为完整；additive extra 与 turn_end 必须一致。
- 重试、清理、epoch fencing 与多实例接管不得被降级路径绕过。

---

### Task 1: 可见推理对账与终态错误分类

**Files:**
- Modify: `backend/src/main/java/com/example/configmgr/ai/run/ResilientChatService.java`
- Modify: `backend/src/main/java/com/example/configmgr/ai/gate/SpToolCallingManager.java`
- Modify: `backend/src/main/java/com/example/configmgr/common/ErrorCodes.java`
- Modify: `backend/src/main/resources/messages_zh.properties`
- Modify: `backend/src/main/resources/messages_en.properties`

**Interfaces:**
- Consumes: `AiProperties.reasoning.displayEnabled`、`NonRetryableTranscriptException`、`isRecordTerminalTranscript`、`emitFailureTerminal` 和 `emitTurnEnd`。
- Produces: 可见 reasoning 对账、三类 transcript 结果及 DONE+degraded 的明确终态证据。

- [ ] **Step 1: 分离存储与可见 reasoning**
  - ResilientChatService.completeTimeline/populateAssistantSegments 与 SpToolCallingManager.assistantTranscript 仅在实际展示时传 entry.reasoning() 对账；关闭时传空串，存储 entry 不变。
- [ ] **Step 2: 返回真实失败类别**
  - isRecordTerminalTranscript 以最小结果形式返回成功或错误码；时间线 null 使用 AI_TRANSCRIPT_INCOMPLETE，写入拒绝/非 Redis 异常使用 AI_TRANSCRIPT_WRITE_FAILED，真实 Redis 重试耗尽使用 AI_REDIS_UNAVAILABLE。
  - NonRetryableTranscriptException 保留失败码；普通 isRecordTranscript 调用兼容，现有重试与日志异常对象不变。
- [ ] **Step 3: 正确报告 DONE+degraded**
  - persistSuccessSnapshot 不再将 transcript false 映射为统一 unconfirmed；发 typed transcript error（含规定 additive 字段）并 emitTurnEnd(done, 实际错误码)。
  - 不伪发正常 historyComplete，不把生成 DONE 改成 FAILED，不让清理/恢复绕过现有所有权。
- [ ] **Step 4: 用户安全文案与编译审查**
  - 注册两个错误码及中英文安全消息，说明对话已完成但部分历史未保存，不暴露 Redis/服务器细节。
  - IDEA Reformat Java，Maven生产编译；独立关联审查后提交推送，DeepSeek重跑原三轮并验证最终assistant history。
