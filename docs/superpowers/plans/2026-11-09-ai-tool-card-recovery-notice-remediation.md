# AI 工具卡稳定展示与恢复提示收口 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 保留全部业务工具卡，并将恢复呈现统一为自动过程静默、最终失败单提示。

**Architecture:** 在现有 `AiPanel.vue` 过滤与恢复提示层做最小修改，保留 store 的真实恢复状态和后台恢复流程。中英文文案同步，独立验收文档和脚本只增加确定性断言。

**Tech Stack:** Vue 3、TypeScript、Tailwind CSS、现有 i18n、Node.js 独立验证脚本。

**Spec:** `docs/superpowers/specs/2026-11-09-ai-tool-card-recovery-notice-design.md`

## Global Constraints

- 普通 `skipped` 过滤保持现状，不恢复卡片或 `skippedHuman`。
- 不修改普通测试代码，不执行测试验证；生产构建和脚本语法检查必须实际通过。
- 不新增依赖、API/SSE 字段、用户提示或状态机，不掩盖永久失败和未知结局。
- 每个独立场景单独执行，DeepSeek 不使用 CU；动态结果未产生前均为 NOT_RUN。
- 提交前逐 hunk 独立关联审查并追加开发规范门禁记录，不绕过 pre-commit。

## Review Focus

- 四类工具 running→succeeded 后仍在 DOM、history 和复制内容中，普通 `skipped` 仍按原规则过滤。
- 自动恢复成功时无额外提示；最终失败最多一条提示和一个“重试”入口。
- 中英文无已删除提示的死引用，普通工具、HITL、表单状态样式不被顺带改变。
- 镜像/history 可用、暂不可用和永久失败不被混写成成功或重复提示。
- 生产构建与独立脚本语法检查真实执行，不把静态审查写成动态 PASS。

---

### Task 1: 恢复工具卡稳定展示并统一提示边界

**Files:**
- Modify: `frontend/src/components/AiPanel/AiPanel.vue`
- Modify: `frontend/src/locales/zh-CN.json`
- Modify: `frontend/src/locales/en-US.json`
- Modify only if dead references require it: `frontend/src/stores/ai.ts`

**Interfaces:**
- Consumes: 现有 `message.parts`、`blocksOf`、`recoveryGap`、恢复错误及自动恢复动作。
- Produces: 四类普通工具全生命周期可见；恢复过程静默，最终失败单提示单动作；普通 `skipped` 行为不变。

- [ ] **Step 1: 删除四类工具成功过滤**
  删除 `AUTOMATIC_WORKSPACE_PROCESS_TOOLS` 及对应 `shouldShowToolPart` 分支，保留 `skipped` 和真实幻影过滤；渲染与复制共用剩余规则。
- [ ] **Step 2: 清理恢复提示多重呈现**
  删除 recoveryGap 独立顶部条、消息级提示、恢复错误附加缺口文案及死引用；后台恢复状态保留。
- [ ] **Step 3: 统一最终失败提示**
  自动恢复期间不增加提示；最终需要人工处理时最多一条简洁错误提示和一个“重试”入口。同步中英文并清理无用 key，不增加替代话术。
- [ ] **Step 4: 构建与静态复核**
  执行前端生产构建；逐 hunk 检查工具卡、HITL、表单和恢复行为，动态验证记 NOT_RUN。

### Task 2: 刷新独立验收文档与脚本

**Files:**
- Modify: `docs/verification/AI对话独立复测清单-2026-11-05.md`
- Modify: `docs/verification/AI对话恢复与导出内容验收-2026-11-08.md`
- Create or modify only independent verification assets under `scripts/verification/`

**Interfaces:**
- Consumes: Task 1 的可见性和提示口径。
- Produces: 可由 DeepSeek 单场景执行的卡片稳定性、复制、刷新和恢复提示断言。

- [ ] **Step 1: 固定卡片稳定性场景**
  分别覆盖四类工具 running→succeeded→刷新→复制，断言卡片不消失；普通 `skipped` 仍过滤。
- [ ] **Step 2: 固定恢复呈现场景**
  自动恢复成功断言无新增提示；最终失败断言最多一条提示和一个重试按钮；覆盖中英文和重复刷新。
- [ ] **Step 3: 语法检查与证据边界**
  对独立 Node 脚本执行 `node --check`，文档明确动态 NOT_RUN、业务副作用和内容对账要求。

### Task 3: 独立审查、提交与 DeepSeek 复测

**Files:**
- Modify: `docs/delivery/development-standards-review.json`
- Modify only evidence records: `docs/verification/AI对话恢复与导出内容验收-2026-11-08.md`

**Interfaces:**
- Consumes: Task 1/2 最终 diff 和构建结果。
- Produces: 审核通过的提交、推送及真实复测证据。

- [ ] **Step 1: 独立关联审查**
  非施工者逐 hunk 检查所有生产、脚本和文档变化，返修至无 Critical/Important。
- [ ] **Step 2: 门禁与提交推送**
  追加开发规范审查记录，运行 `node scripts/ci/check-d6-sync.mjs --standards`，不绕过 pre-commit，提交并推送。
- [ ] **Step 3: DeepSeek 单场景复测**
  使用最新提交和内置浏览器，逐场景核对 DOM、history、复制内容、提示数量、业务副作用和清理；失败项返修后按原场景复测，未执行项保留 NOT_RUN。
