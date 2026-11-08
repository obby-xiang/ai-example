# OpenAI Assistant Continuity Recovery Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 OpenAI 标准工具续轮之上恢复完整 assistant 上下文，避免流式推理分片在工具执行点变成空串并导致挂起恢复失败。

**Architecture:** 复用既有 TurnTextCapture/toolContext，在消费分片时同步累计当前工具跳的推理元数据，在执行点一次构造完整 assistant。标准 `role/content/tool_calls/tool_call_id` 始终保持；`reasoning_content` 仅作为同源、真实非空的扩展元数据回放，不增加供应商分支或新配置。

**Tech Stack:** Java 21、Spring AI 1.1.7、Reactor、现有 RunStore/ResumeService。

**Spec:** 根因调查报告 agent-1099；OpenAI 官方 Chat Completions 与 Function Calling 文档；`docs/delivery/development-standards.md` 6.12。

## Global Constraints

- 禁止 DeepSeek/provider/model/Base URL/专有错误文本分支；不得把 `reasoning_content` 当作 OpenAI 标准字段。
- 普通测试代码不得修改，施工阶段不执行普通测试；生产 Maven compile/package 必须通过。
- 标准 assistant/tool 消息字段、顺序和配对始终保留；扩展字段只有同源且实际非空时才进入请求，空串不得注入。
- 不修改 Redis、前端 pending 或错误重试来掩盖问题；真实上游失败继续可观测。
- 保持 attempt reset、工具跳 commit、挂起恢复和跨线程上下文一致，防止重复拼接和串轮。
- 不打印推理正文、密钥或原始上游响应体；日志使用英文且 DEBUG 先判开关。

## Review Focus

- `reasoning A → reasoning B → content → tool_call（空推理）` 必须在执行点得到 A+B；由实现报告的静态调用链与后续真实 C-117/C-129 覆盖。
- 普通工具立即续轮、generative_form 挂起恢复、宿主表单挂起不得共享残缺 assistant；由后续真实场景覆盖。
- attempt reset/工具跳 commit 不得重复推理；由独立审查检查。
- 无推理或跨来源不得生成扩展字段；由请求构造审查和后续上游代理证据覆盖。
- `persist-content=false` 不得破坏当前工具跳协议连续性；由独立审查检查。

---

### Task 1: 完整 assistant 捕获与通用回放

**Files:**
- Modify: `backend/src/main/java/com/example/configmgr/ai/gate/SpToolCallingManager.java`
- Modify: `backend/src/main/java/com/example/configmgr/ai/run/ResilientChatService.java`
- Modify: `backend/src/main/java/org/springframework/ai/openai/ReasoningAwareOpenAiChatModel.java`

**Interfaces:**
- Consumes: 现有 `TurnTextCapture`、`Accumulator.reasoning`、`ReasoningOrigin.replayAllowed` 和 assistant metadata。
- Produces: 当前工具跳完整正文与推理段；普通续轮和挂起恢复共享完整 assistant。

- [ ] **Step 1: 扩展分段捕获器**
  - TurnTextCapture 同步保存正文与推理段；reset/commit 同时清理，保留既有单参数 reset 兼容入口。
  - Accumulator.reasoning 将真实分片追加到同一捕获器；纠正前缀只在既有 seed 条件下种入一次。
- [ ] **Step 2: 执行点构造完整 assistant**
  - 同时合并正文与非空推理 metadata，保留 toolCalls/media/其他 metadata；正文为空但推理非空时也必须重建。
  - 持久化后 commit 两段，避免下一工具跳重复拼接。
- [ ] **Step 3: 通用请求回放**
  - 仅同源且 reasoningContent 非空白时回放；空白或未知来源不生成字段。
  - 清除补丁注释中的 DeepSeek 特制表述，保持标准消息和 extraBody 行为。
- [ ] **Step 4: 编译、审查与提交**
  - IDEA Reformat 仅 Java；运行 `mvn -B -Dmaven.test.skip=true compile -s maven-settings.xml`。
  - 独立关联审查普通续轮、挂起恢复、reset/commit、标准协议与日志安全，门禁通过后提交推送。
- [ ] **Step 5: 真实验收**
  - 重启当前后端，DeepSeek 重跑原 reasoning/多表单失败及 C-117/C-129 相关路径；保存上行扩展字段存在性（不保存正文）、SSE/history、pending、Redis和日志。
