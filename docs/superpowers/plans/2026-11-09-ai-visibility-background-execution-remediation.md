# AI 可见性与后台执行治理 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 解除标签页可见性对 AI 传输与后台业务执行的错误阻断，同时补齐真实元素可操作性、到期收敛和重放幂等。

**Architecture:** 分离 SSE/恢复传输与 DOM 工具执行。业务工具按上下文执行，DOM 写操作按路由、挂载、代次、CSS 和视窗复核；前后端以 expiresAt 统一期限，后端共享扫描复用现有到期收敛。

**Tech Stack:** Vue 3/TypeScript、Spring/Java 21、现有 Redis/guard/fence、Node.js 独立 CDP/API 验证。

**Spec:** `docs/superpowers/specs/2026-11-09-ai-visibility-background-execution-design.md`

## Global Constraints

- 不使用 document.hidden/hasFocus 作为 AI 执行许可；视窗外元素允许时先滚动再复核。
- 不新增依赖、线程池、每 run 线程、无界扫描、普通测试修改或动态自验。
- 不代替用户处理 HITL/表单/文件选择，不把重复结果冲突吞成成功。
- stores/ai.ts 必须等当前恢复提示批次完成后修改；其他文件可并行。
- Java 用 IDEA 默认 Reformat，生产编译/前端构建必须通过，独立审查后提交推送。

## Review Focus

- 标签页隐藏时 SSE/history/回执仍推进，不增加用户提示。
- 路由切换、旧 UI 代次和不可滚动元素被真实拒绝；可滚动元素先滚动再执行。
- expiresAt 与后端到期一致，重复回执幂等但冲突可见。
- 一次性任务失败、实例退出和无客户端时到期仍收敛且无重复副作用。
- 构建、编译、语法检查与动态证据严格区分，未执行场景保持 NOT_RUN。

---

### Task 1: 解除 SSE 可见性阻断并统一 DOM/预算语义

**Files:**
- Modify: `frontend/src/api/sse.ts`
- Modify: `frontend/src/utils/ai-dom-tools.ts`
- Modify: `frontend/src/utils/frontend-tools.ts`
- Modify after the recovery-notice batch: `frontend/src/stores/ai.ts`

**Interfaces:**
- Consumes: 现有 SSE、history/恢复、工具执行、expiresAt 和 UI 区域/元素围栏。
- Produces: 隐藏页持续传输；真实元素可操作性；绝对期限与幂等回执。

- [ ] **Step 1:** SSE 建连、重连、probeReattach 不再受标签页可见性控制。
- [ ] **Step 2:** 统一元素可操作性，路由/挂载/代次/CSS/视窗逐级判断，必要时滚动后复核，无法操作快速失败。
- [ ] **Step 3:** 前端预算按 expiresAt 剩余时间，区分不可操作与真实超时；接续 stores/ai.ts 的 history/恢复调用和重复回执幂等。

### Task 2: 补齐后端到期收敛兜底

**Files:**
- Modify: `backend/src/main/java/com/example/configmgr/ai/gate/ConfirmGate.java`
- Create or modify only appropriate shared runner under `backend/src/main/java/com/example/configmgr/ai/run/`

**Interfaces:**
- Consumes: `convergeExpiredParkedFrontend`、现有 guard/fence/active-run 索引和受管调度器。
- Produces: 一次性失败有界重试、跨实例共享周期收敛、启动恢复。

- [ ] **Step 1:** 一次性到期失败进入有界重试，异常保持真实可观察。
- [ ] **Step 2:** 共享调度复用收敛方法，候选有界、同槽/身份正确、无租户列表和无界扫描。
- [ ] **Step 3:** IDEA Reformat 与生产编译，关联审查多实例和重复写者。

### Task 3: 独立验收与复测

**Files:**
- Modify: `docs/verification/AI对话独立复测清单-2026-11-05.md`
- Create: `docs/verification/AI可见性与后台执行独立验证.md`
- Create: `scripts/verification/ai-visibility-action-browser.mjs`
- Create only if needed: `scripts/verification/ai-visibility-expiry-api.mjs`

**Interfaces:**
- Consumes: Task 1/2 的传输、元素、到期和幂等行为。
- Produces: C-147～C-152 单场景验收证据。

- [ ] **Step 1:** C-147 后台四类工具；C-148 视窗滚动/不可操作；C-149 无客户端到期；C-150 实例切换；C-151 重放幂等；C-152 后台完整导出与恢复。
- [ ] **Step 2:** 每场景单独执行并核对 DOM/history/run/回执次数/业务副作用/清理，禁止静态文本匹配冒充动态。
- [ ] **Step 3:** 独立审查、构建/编译、门禁提交推送，DeepSeek 不使用 CU 逐场景复测，未执行项 NOT_RUN。
