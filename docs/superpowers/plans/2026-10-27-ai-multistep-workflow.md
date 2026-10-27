# AI 多步工作流 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 创建或续作任务时，批准后可在同一运行中连续完成导航、选择配置、推进和设置条件，并在进入 EXPORT 后获得 `start_export`，全程保留现有 HITL。

**Architecture:** 采用“受限模型编排 + 确定性步骤约束 + 任务状态驱动页面”。页面级工具按固定顺序串行；每次工具批后按成功结果推进 `RunSnapshot.context`，下一跳通过 `resolveToolDefinitions` 重新计算披露面；同一批的 scope 判据继续冻结，DANGER 工具仍走原确认门。

**Tech Stack:** Java 21 / Spring AI 1.1.7 / Vue 3 + TypeScript / Redis / Maven 精确单点测试。

**Spec:** `docs/research/设计-AI可视可操作对齐业界主流-2026-10-22.md`；`docs/research/修复-创建导航与步骤推进-2026-10-27.md`。

## Global Constraints

- 不新增工作流框架、帧类型、前端回灌字段或用户确认步骤。
- `create_task`、`start_export`、`start_precheck`、`start_import`、`start_publish` 的 HITL 不变；动态披露不等于授权。
- `confirm_step` 默认不传 `step`，每次只前进一步；查询条件保持可选。
- 同一批工具调用的 scope 判据冻结；跨批才刷新上下文与披露面。
- 刷新失败只保留旧上下文并 WARN，不让工具轮失败或变空。
- 子 agent 只用 DeepSeek/deepseek-flash；只运行精确单点方法，禁止整类、批量或循环测试。
- 主控只做代码验收，不执行测试；完成后同步文档并提交推送。

## Review Focus

- 导航后同批调用 `select_definitions` 仍应被冻结 scope 拒绝；下一批才允许。
- 进入 `QUERY_COND` 前不得执行 `set_condition`；进入 `EXPORT` 前不得披露或执行 `start_export`。
- 无任务导航必须清除旧 `taskType/step/taskStatus`，换页必须移除旧 `uiRegionCount`。
- 刷新失败不得抹掉旧上下文或让整轮失败。
- `create_task` 与作业启动仍必须经过各自确认卡，不得因多步连续执行而跳过。

---

### Task 1: 页面级多步提示词与工具描述

**Files:**
- Modify: `backend/src/main/java/com/example/configmgr/ai/run/ResilientChatService.java`
- Modify: `backend/src/main/java/com/example/configmgr/ai/tools/AiTools.java`
- Modify: `backend/src/main/resources/ai/tools.yaml`
- Test: `backend/src/test/java/com/example/configmgr/ai/run/SystemPromptHitlConfirmTest.java`
- Test: `backend/src/test/java/com/example/configmgr/ai/run/LanguagePolicyTest.java`
- Test: `backend/src/test/java/com/example/configmgr/ai/tool/ToolDefinitionOverridesTest.java`

**Interfaces:**
- Produces: 创建后允许按 `navigate_to → select_definitions → confirm_step → set_condition` 连续分批执行；进入新步骤后使用最新披露工具；HITL 不变。

- [ ] **Step 1: 更新三个精确测试，先证明旧口径会失败**

覆盖：导航后不再无条件结束；页面级工具可连续分批；每步仅前进一次；条件只在用户明确提出时设置；`tools.yaml` 新版本与描述一致。

- [ ] **Step 2: 分别运行三个精确测试方法确认 RED**

只允许 `-Dtest=类#方法`，禁止整类或通配。

- [ ] **Step 3: 修改提示词和工具描述**

删除“导航成功即结束本轮”和“推进只对下一轮生效”；保留创建与导航不并批、导航前不选择、逐步推进、失败即停和全部 HITL。`tools.yaml` 版本升至 `2026-10-27.2`。

- [ ] **Step 4: 分别运行同一精确测试方法确认 GREEN**

### Task 2: 运行上下文推进与下一跳动态披露

**Files:**
- Create: `backend/src/main/java/com/example/configmgr/ai/gate/RunContextRefresher.java`
- Modify: `backend/src/main/java/com/example/configmgr/ai/gate/SpToolCallingManager.java`
- Test: `backend/src/test/java/com/example/configmgr/ai/gate/SpToolCallingManagerDynamicDisclosureTest.java`

**Interfaces:**
- Consumes: 已结算且 `outcome=OK` 的 `navigate_to` / `create_task`；`TaskService` 的权威任务类型、步骤、状态；`ToolRegistry.forContext`。
- Produces: `RunContextRefresher.next(...)`；批后更新的 `RunSnapshot.context`；`resolveToolDefinitions` 按下一跳上下文重算工具。

- [ ] **Step 1: 写动态披露精确失败测试**

覆盖：导航成功后快照更新且下一批含选择/条件/步骤工具；进入 EXPORT 前仍无 `start_export`；无任务导航清旧任务字段；刷新失败回落；空 callbacks/无快照保持委托。

- [ ] **Step 2: 运行精确测试方法确认 RED**

- [ ] **Step 3: 实现最小刷新与披露**

逐条成功结算后刷新快照；`resolveToolDefinitions` 每跳按快照重算；同批执行判据不变；刷新失败只 WARN。执行侧需要时用当前 callbacks 副本，不修改官方循环返回结构。

- [ ] **Step 4: 运行同一精确测试方法确认 GREEN**

### Task 3: 主控验收、文档与提交

**Files:**
- Create: `docs/research/修复-AI多步工作流与动态披露-2026-10-27.md`
- Modify: `docs/research/README.md`
- Modify: `docs/任务看板.md`
- Modify: `docs/决策看板.md`

- [ ] **Step 1: 主控只读 diff 验收**

重点检查 HITL、同批冻结、失败降级、上下文清理、单键/单实例边界和测试真实性。

- [ ] **Step 2: 同步研究件、索引与看板**

只记录精确单点回执，不声称全量、真实模型、真实浏览器或真实 Redis 已验。

- [ ] **Step 3: 运行一次 `node scripts/ci/check-docs.mjs`，提交并推送**
