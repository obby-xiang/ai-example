# Deterministic Export Conditions Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 已知结构化条件由确定性后端工作流直接落库并推进，模型不再决定条件收集路径，自定义配置项同样生效。

**Architecture:** 以无损 `ConditionCommand` 表示每个配置项的条件事实。导出工作流只按 `SET / EXPLICIT_UNSET / UNDECIDED` 执行现有领域落库、一次收集和步骤推进；模型仅负责自然语言语义理解。FRONTEND 工具回灌与真实任务状态作为事实源，不用 `ai:ledger` 判断前端工具是否执行。

**Tech Stack:** Java 21、Spring、现有 TaskService/配置元数据、Vitest/JUnit（仅精确单用例）。

**Spec:** 用户在当前对话中已确认的“确定性导出工作流”方案；异步导出、既有确认门、通用配置元数据和 D6 契约为边界。

## Global Constraints

- 禁止 CURRENCY/CNY 等配置项特判；新配置定义必须走同一代码路径。
- 不让模型填写应用已知的条件参数，不让模型决定是否弹已知条件表单。
- 未决定只收集一次；非空必须真实落库；明确留空才全量；取消不自动重问。
- 已进入 EXPORT 的 null/空白视为留空；QUERY_COND 的 null 仍未决定；坏结构不得放行。
- 不自动执行或下载导出作业；`start_export` 仍只走既有确认卡。
- 每行为仅跑最必要的精确单用例 RED→GREEN；禁止整类/整文件/批量/循环测试和 `clean`。
- 子 agent 不提交不推送，主控代码验收后明确文件提交推送。

## Review Focus

- 自定义配置项的已知 EQ 条件：无需模型即可落库并推进（Task 1 单用例）。
- 多配置、非 EQ/scopeKeys、明确不设和坏结构：不误判全量（Task 1 单用例）。
- EXPORT 与 QUERY_COND 的空白语义一致，不再互相矛盾（Task 1 单用例）。
- FRONTEND 工具没有 `ai:ledger` 时，仍能从真实回灌识别已处理条件（Task 1 单用例）。
- 条件未知时仍只有一张合法表单，非法系统拒绝不上屏（由独立静默校验批次验收）。

---

### Task 1: 确定性条件工作流

**Files:**
- Create: `backend/src/main/java/com/example/configmgr/ai/slot/ConditionCommand.java`
- Create: `backend/src/main/java/com/example/configmgr/ai/form/ExportConditionWorkflow.java`
- Modify: `ConditionAssessment.java`、`TaskSlotResolver.java`、`TaskSlots.java`、`ConditionsForm.java`、`ContextBuilder.java`、`ToolPreflightService.java`、`ResilientChatService.java`（仅删除重复模型路径/提示）
- Test: 对应少量精确测试与 `ExportConditionWorkflowTest.java`

**Interfaces:**
- Consumes: 现有 `TaskService.setCondition`、任务真实 `conditionJson`、工作区/表单结构化回灌、配置元数据。
- Produces: 按配置项无损条件命令；`SET` 直接落库、`EXPLICIT_UNSET` 直接推进、`UNDECIDED` 一次收集；不向模型暴露新的自动导出工具。

- [ ] **Step 1: 写最必要失败用例**
  - 自定义 `ORDER` + `code=CNY`：已有结构化条件但未落库时，工作流直接调用现有 `set_condition`，不弹表、不读 `ai:ledger`。
  - `UNDECIDED` 只收集一次；`EXPLICIT_UNSET` 直接推进；坏结构拒绝。
  - FRONTEND `set_condition` 已回灌但无 `ai:ledger` 时，仍识别为已处理。
  - EXPORT+null 在预检与 `ContextBuilder` 中均按明确留空，不再一处全量一处未决定。

- [ ] **Step 2: 每个用例单独运行并确认因旧路径而失败**

- [ ] **Step 3: 最小实现结构化命令与工作流，删除旧的模型纠正/台账依赖分支**
  - 复用现有领域校验和落库，不复制 CNY 或配置项业务逻辑。
  - 已知条件不经过模型；模型只处理非结构化语义和真正缺口。
  - 不改变 SSE、Redis、身份、异步作业与下载逻辑。

- [ ] **Step 4: 同一批必要单用例逐条运行至通过，不扩散测试**

- [ ] **Step 5: 主控代码审查、同步 D6、明确文件提交推送**

### Task 2: 真实模型与通用性验收

**Files:**
- Test: 必要时的单点真机证据与 D6 更新

- [ ] **Step 1: 重启真实服务，在本机 Chrome 连续两遍执行“导出cny货币字典”**
  - 两次均不弹条件表，真实 `code=CNY` 落库，出现启动确认，批准后结束本轮且不自动下载。
- [ ] **Step 2: 用一个自定义配置项执行一次同链路**
  - 同一工作流生效，无配置编码特判。
- [ ] **Step 3: 条件未知场景只出现一张合法表单；非法系统拒绝零用户卡**
- [ ] **Step 4: 验收后再补必要覆盖，不预先跑大批量测试**
