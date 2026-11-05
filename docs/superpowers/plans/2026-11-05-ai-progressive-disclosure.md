# AI 渐进式披露与知识库工具选择 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 缩短并去重常驻提示，按能力披露领域规则和工具，恢复条件阶段的 KB 工具，并为截断结果与检索评估建立可复用机制。

**Architecture:** 采用“稳定安全核心 + 按披露能力渲染的规则包”，工具参数约束以 Schema 为唯一来源。模型默认 `auto`，不增加独立分类模型或中文关键词硬门；条件阶段同时保留业务动作与 KB 只读工具。大结果复用既有台账，以 `toolCallId + offset` 回读。

**Tech Stack:** Java 21、Spring AI、Spring Data Redis、Vue 3、Node.js 独立验证脚本。

**Spec:** `docs/research/调研-AI渐进式披露与知识库工具选择-2026-11-05.md`

## Global Constraints

- 只修改非测试生产代码；仓库测试目录和既有测试代码不得修改。
- 不运行项目测试；完成后仅执行后端生产 `compile` 和前端生产 `node scripts/build.mjs`。
- 独立评估脚本位于 `scripts/verification`，不是项目测试，不读取或提交密钥。
- 不做旧数据迁移或旧协议兼容，不使用中文关键词硬门、配置编码特判或全局 `toolChoice=required`。
- 保留确认卡、DANGER、防注入、真实错误和模型侧原始 KB 安全包装。
- 每批由 K3.1 Pro 实现和静态复核；通过后才提交推送，静态复核不等于实测。

## Review Focus

- `DIRECT` 问题不得被强制检索；`KNOWLEDGE` 问题在条件阶段仍必须能看到 KB 工具。
- `MIXED` 问题必须同时保留当前动作工具和 KB 只读工具，不得丢失任务步骤上下文。
- 大量配置、条件和历史候选不得突破任务槽位总预算。
- 截断后的工具结果必须能按 `toolCallId + offset` 有界回读，且不能跨 run 或跨身份读取。
- 普通 JSON、真实错误、无命中和 DeepSeek 不支持指定工具的模式不得被伪造为成功或强制调用。

---

### Task 1: 稳定核心与按需规则包

**Files:**
- Modify: `backend/src/main/java/com/example/configmgr/ai/run/ResilientChatService.java`
- Modify: `backend/src/main/java/com/example/configmgr/ai/context/ContextBuilder.java`
- Modify: `backend/src/main/java/com/example/configmgr/ai/kb/KbPromptSection.java`

**Interfaces:**
- Consumes: `ToolRegistry.forContext` 已披露的工具集合与当前任务/页面/步骤。
- Produces: 稳定核心 System 与按能力渲染的短规则包；有统一字符上限和稳定优先级的任务槽位段。

- [ ] 将 System 拆为稳定安全核心和条件规则包；创建、表格、UI、表单规则只在对应能力披露时出现。
- [ ] 合并第 8/24/30、26/28/30 等重复规则；参数细节交还工具 Schema，确认、防注入和真实结局约束保留。
- [ ] KB 默认不渲染全目录，保留精简使用规则和不可用状态；目录通过 KB 工具按需获得。
- [ ] 为任务槽位设置独立总字符预算，优先当前任务、当前缺口和有效条件，稳定排序，不逐字截断结构化字段。
- [ ] 运行后端生产 `compile`，由另一名 K3.1 Pro 只读复核披露条件和重复文本是否真正减少。

### Task 2: 工具披露、Schema 与结果回读

**Files:**
- Modify: `backend/src/main/java/com/example/configmgr/ai/tool/ToolRegistry.java`
- Modify: `backend/src/main/java/com/example/configmgr/ai/tools/AiTools.java`
- Modify: `backend/src/main/resources/ai/tools.yaml`
- Modify: `backend/src/main/java/com/example/configmgr/ai/tool/ToolResultEnvelope.java`
- Modify: `backend/src/main/java/com/example/configmgr/ai/tool/ToolResultLimiter.java`
- Modify: 必要的现有结果读取与台账类（实现者先确认最小范围）

**Interfaces:**
- Consumes: Task 1 的同一披露工具集合；现有 `RunStore` 工具台账和身份上下文。
- Produces: `read_tool_result(toolCallId, offset, maxChars)` 有界分页读取；信封中的 `nextOffset`/游标事实；条件阶段保留 `kb_search/kb_read_doc/kb_lookup_term`。

- [ ] 在条件结构化交棒中保留三个 KB 只读工具及当前动作工具，不新增独立意图分类模型。
- [ ] 精简 `generative_form` 的宿主模板和最小 Schema，高级联动按需披露；统一附件 `attachment` 白名单，不削弱校验。
- [ ] 省略信封 null 字段和重复截断文案，保留结局、安全定界及真实错误。
- [ ] 为被截断的非 KB 结果提供 `read_tool_result(toolCallId, offset, maxChars)`；按当前 run 和身份校验，返回下一游标，不新增全量 SCAN 或无界存储。
- [ ] 运行后端生产 `compile`，由另一名 K3.1 Pro 只读复核工具面、分页边界和跨 run/身份拒绝。

### Task 3: 独立评估体系与复测用例

**Files:**
- Create: `scripts/verification/ai-progressive-disclosure-eval.mjs`
- Create: `scripts/verification/fixtures/ai-rag-eval.json`
- Create: `docs/verification/AI对话评估体系-2026-11-05.md`
- Modify: `docs/verification/AI对话独立复测清单-2026-11-05.md`

**Interfaces:**
- Consumes: 运行中服务的公开 HTTP/SSE 接口；环境变量仅传地址、身份和轮次，不读取密钥文件。
- Produces: 可重复的检索/披露评估报告、逐案轨迹和人工评分模板。

- [ ] 建立 `DIRECT/KNOWLEDGE/ACTION/MIXED` 小样本，覆盖应检索、无需检索、纯动作、混合、无命中、近似标题、长上下文和条件阶段。
- [ ] 脚本记录 system/工具披露证据（接口能证明的范围）、`tool_start/tool_result`、答案、延迟、token、调用轨迹；不能证明的披露内容标记 `unknown`，不得伪造。
- [ ] 计算检索召回、误触发、工具选择准确率、有效命中、无命中、平均调用数；答案正确性和忠实度使用明确人工评分栏及引用核对，不用字符串匹配冒充语义评分。
- [ ] 在独立复测清单增加条件阶段 KB、普通问答不强制检索、混合意图、无命中一次改写、长 Schema 不回归、任务槽位预算和结果回读用例；复杂场景至少三轮。
- [ ] 仅做脚本语法检查和文档静态复核，不执行真实模型评估；实际评估由后续 DeepSeek/指定复测流程运行并保存证据。
