# 下载执行前确认与 UI 撤销清理 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 以通用两阶段协议为 DANGER+FRONTEND 工具提供独立 HITL，并清退普通 UI 操作撤销入口。

**Architecture:** 独立 CONFIRM 审批记录 A 与原 FRONTEND 执行记录 E 双向链接、分别持久化、分别出卡；复用 ConfirmGate、RunStore、现有帧和 ToolPart 分派，不在普通工具段写入 decision。

**Tech Stack:** Java 21、Spring、Redis、Vue 3、TypeScript。

**Spec:** `docs/superpowers/specs/2026-11-10-download-pre-execution-approval-design.md`

## Global Constraints

- 触发条件仅为 `risk=DANGER && channel=FRONTEND`，禁止工具名、参数、任务或场景特判。
- A 不进入模型工具历史；E 保留原 toolCallId，模型只看到一次调用和一次结果。
- 普通工具卡只读；禁止确认 Map、伪造 APPROVED+FRONTEND、普通工具段 decision 和绕过 ConfirmGate 的假批准。
- 不修改普通测试代码；仅允许生产代码、压测代码、独立验收资产和文档。
- `undo_last_sheet_edit` 及其预览确认保持独立，不与普通 UI 撤销一起删除。
- 纳管生产提交须通过独立审查、IDEA Reformat、真实生产构建和规范 ledger。

## Review Focus

- 批准前任何阶段均零切页、预检、文件生成、Blob/锚点下载和业务写入。
- 批准/拒绝/停止/刷新/跨实例/重复回包时，A/E 各自保持真实状态且不重复执行。
- 链接缺失、类型错误、目标不存在或反向链接不一致时失败关闭，不执行后端哨兵桩或前端工具。
- 审批等待不计入机器回执超时；机器超时只从 frontend_tool_request 开始。
- 普通 UI 工具不出现撤销入口，表格写预览和 undo_last_sheet_edit 不变。

---

### Task 1: 通用审批记录与链接模型

**Files:**
- Modify: `backend/src/main/java/com/example/configmgr/ai/run/PendingToolCall.java`
- Modify: `backend/src/main/java/com/example/configmgr/ai/tool/ToolRegistry.java`
- Modify: `backend/src/main/java/com/example/configmgr/ai/tools/AiTools.java`
- Modify: `backend/src/main/resources/ai/tools.yaml`
- Modify: `frontend/src/types/tools.ts`
- Create: `backend/src/main/java/com/example/configmgr/ai/gate/PreExecutionApprovalService.java`

**Interfaces:**
- `preExecutionApprovalId?: string`
- `executionToolCallId?: string`
- A=`CONFIRM`，E=`FRONTEND`；A/E 链接可序列化恢复且不一致时失败关闭。

- [ ] **Step 1:** 以现有 pending/asMap 增加 additive 链接字段，保持旧快照兼容；增加通用协作者负责 A/E 创建、链接校验、拒绝结算和完成收敛。
- [ ] **Step 2:** 以合法两阶段替代旧 DANGER+FRONTEND 启动拒绝，保留 BACKEND 风险防线；下载声明改 DANGER 并同步 YAML 和前端风险镜像。
- [ ] **Step 3:** 后端 Maven `-Dmaven.test.skip=true compile`，前端 `node scripts/build.mjs`，独立静态审查链接模型与启动约束。

### Task 2: 审批、执行、恢复与权威历史

**Files:**
- Modify: `backend/src/main/java/com/example/configmgr/ai/gate/SpToolCallingManager.java`
- Modify: `backend/src/main/java/com/example/configmgr/ai/gate/ConfirmGate.java`（仅在必须扩展既有接缝时）
- Modify: `backend/src/main/java/com/example/configmgr/ai/run/RunStore.java`
- Modify: `backend/src/main/java/com/example/configmgr/ai/run/ResumeService.java`
- Modify: `backend/src/main/java/com/example/configmgr/ai/web/AiController.java`

**Interfaces:**
- 待审批：A.pending+req=true，E.pending+req=false，无 frontend_tool_request。
- 批准：A.approved 后仅补发一次 E.frontend_tool_request；E.req=true 后不重复驱动。
- 拒绝：A/E rejected，零执行；停止：A/E cancelled；完成：A 收敛但不执行后端工具。
- `inFlightToolCalls` 只含 E；展示历史可含 A+E；模型历史只含 E 的一次结果。

- [ ] **Step 1:** 在准备、分发、续跑和 runs/current 中统一接入 A/E 状态判据，复用 ConfirmGate 决策 guard、req、park/resume、取消和现有接口。
- [ ] **Step 2:** 拒绝、停止、重复决策、跨实例恢复及完成均按 A/E 独立终态收敛；审批等待不计机器超时，E 发出后才启用原机器回执超时。
- [ ] **Step 3:** 下载与打开编辑器只读 viewStep，不隐写配置、条件或步骤；执行器保持批准后即发起且不谎称已保存。
- [ ] **Step 4:** 后端编译与独立关联审查，重点核对链接损坏、重复恢复、跨实例和模型协议。

### Task 3: 清退普通 UI 操作撤销

**Files:**
- Modify: `frontend/src/components/AiPanel/AiPanel.vue`
- Modify: `frontend/src/stores/ai.ts`
- Modify: `frontend/src/utils/ai-dom-tools.ts`
- Modify: `frontend/src/types/tools.ts`
- Modify: `frontend/src/locales/zh-CN.json`
- Modify: `frontend/src/locales/en-US.json`

**Interfaces:**
- 删除面板 ui-undo-button、撤销提示、undoLastUiAction 及普通 UI 撤销栈写读；不再提示原值可撤销。
- 不提供 ui_undo 工具，不改变 ui_click/fill/select 的实际业务动作和停止语义。
- 不删除 `undo_last_sheet_edit` 或表格写预览。

- [ ] **Step 1:** 移除普通 UI 撤销按钮、提示、store 状态和 DOM 撤销栈，清理失效引用和中英文文案。
- [ ] **Step 2:** 前端构建并独立审查普通 UI 工具、表格撤销和恢复路径，确认无遗留入口或误删业务回滚。

### Task 4: 规格与独立验收

**Files:**
- Modify: `docs/requirements.md`
- Modify: `docs/03-技术方案文档-v2.1.md`
- Modify: `docs/verification/AI对话独立复测清单-2026-11-05.md`
- Modify: `docs/verification/AI对话恢复与导出内容验收-2026-11-08.md`
- Modify: `scripts/verification/ai-tool-card-recovery-browser.mjs`

**Interfaces:**
- 独立 HITL 与只读工具卡分别验证；无工具名分支、无下载专用恢复逻辑。
- 覆盖批准、拒绝、停止、刷新、重复回包、链接损坏、单文件、ZIP 和中英文。

- [ ] **Step 1:** 更新规格与独立脚本，验证批准前零副作用、批准后一次执行、拒绝不复活、A/E 恢复和模型只收到 E 的一次结果。
- [ ] **Step 2:** 每场景每 locale 独立进程执行，禁止批量循环；产品问题交主会话，不修改普通测试或降低断言。
- [ ] **Step 3:** 独立审查后按生产/文档/test-only 边界分别提交推送；动态结果未执行项明确 NOT_RUN。
