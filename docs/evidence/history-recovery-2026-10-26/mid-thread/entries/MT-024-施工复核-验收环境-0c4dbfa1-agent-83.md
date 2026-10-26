<!-- 历史中间稿证据条目（自动生成头部，非正文）。源 = Kimi Code assistant wire 文本；不含 think / 工具输出 / 原始用户消息。 -->

# MT-024 · 施工复核 · 验收环境（0c4dbfa1 / agent-83）

> **项目归属**：**项目硬锚点**（正文命中 ai-example 路径 / 项目类名 / 项目文档名 / 项目 UI 概念）。
> **归属依据**：正文命中：`ai-example-<OLD_REPO_DIR>`、`frontend/src/`、`AiPanel.vue`、`FormRenderer.vue`。
> **稿态**：历史中间稿 —— 该 wire 第 `m22` 条 assistant 消息（m 按 wire 内 assistant 消息计，含工具调用轮）；本条是该 wire 第 `mt6` 条有正文的 assistant 消息（共 9 条有正文），末条有正文消息为 `m40`。**非现行基线、非验收结论、非当前缺陷清单**。
> **来源**：会话 `0c4dbfa1` / agent `agent-83`（`main` = 主会话）；原字符 8,443（链内 1 段、合计 8,443）。
> **链号 / 类别**：`MT-024` / **施工复核**；判定：施工复核记录（当时那次核验看到什么，不证明当前仍成立）；**不是用户批准**。
> **轮次**：位于该 wire 第 2 个用户轮之后；相邻用户轮未见决断类字样（仅记布尔，未复制用户原文）。
> **去重**：行指纹对「仓内 + 落盘语料」覆盖率 `19%`（<60% 视为仓内无等价件）；同 wire 后文覆盖率 `16%`。
> **与末条归档（138 件）的关系**：末条 `m40`（8,545 字符）未入末条归档。
> **脱敏**：路径 4 / 会话标识 0 / 身份 0 / 密钥 0 / 旧仓名与同源仓名 2 处。

---

All checks done. Here is the read-only mechanical acceptance report.

## 验收环境
- 仓库：`<REPO>`，分支 `<OLD_REPO_DIR>`，HEAD `b41784a6e8eb1c809b442dad3e4d2052ecc2f385`（验收前后一致）
- 未做任何 repo 内文件修改、未做任何 git 写操作；验收后 `git status --porcelain` 仍为同样 6 条
- 唯一副作用：在 repo 外创建了证据文件 `<LOCAL_WORK>\gfb-typecheck.log`、`<LOCAL_WORK>\gfb-build.log`、`<LOCAL_WORK>\gfb-scratch\`（含 `fs.mts` 副本与 `t.mts` 断言脚本）

---

## 1. 范围核对 — PASS
```
$ git status --porcelain
 M frontend/src/components/AiPanel/AiPanel.vue
 M frontend/src/stores/ai.ts
 M frontend/src/types/ai.ts
 M frontend/src/types/api.ts
?? frontend/src/components/AiPanel/FormRenderer.vue
?? frontend/src/types/form-schema.ts
$ git status --porcelain | wc -l   →  6
$ git diff HEAD --stat
 4 files changed, 255 insertions(+), 10 deletions(-)   （无 staged 改动：git diff --cached 为空）
```
禁止路径核对：`git status --porcelain | awk '{print $2}' | grep -E '(^backend/|\.github/|scripts/githooks|package\.json|yarn\.lock|package-lock\.json|pnpm-lock)'` → **无匹配**。即后端、`.github/`、`scripts/githooks`、`frontend/package.json`、`yarn.lock` 均零改动 ⇒ 无新增依赖。

## 2. 构建门 — PASS
```
$ cd frontend && yarn typecheck
$ vue-tsc --noEmit
Done in 3.68s.        EXIT=0 ELAPSED=4s      （无告警输出）

$ cd frontend && yarn build
$ vue-tsc --noEmit && node scripts/build.mjs
vite v6.4.4 building for production...
✓ 1746 modules transformed.
✓ built in 33.75s
Done in 38.23s.       EXIT=0 ELAPSED=39s
```
（`dist/` 属 gitignore，验收后 worktree 未新增可追踪文件。）

## 3. 视觉纪律 — PASS（1 处轻微偏差见备注 A）
- 真实 `v-html` 用法：`grep -rn "v-html" frontend/src` → 全仓仅 **3** 处，**全部在注释文本内**（见第 6 项）。AiPanel.vue 的 diff 无任何 `v-html` 新增。
- 新增 style 块：`git diff -U0 -- AiPanel.vue | grep '^+' | grep -E "style|v-html"` → **NONE**。AiPanel.vue 唯一 `<style scoped>` 为**既有**块，与 HEAD 逐字节相同：
  `diff <(git show HEAD:...AiPanel.vue | sed -n '520,538p') <(sed -n '570,588p' worktree)` → `STYLE BLOCK IDENTICAL`。
- FormRenderer.vue：`grep -n "<style"` 仅命中第 111 行（注释里那句话），**无真实 style 块**；模板全部为 `el-input / el-input-number / el-switch / el-date-picker / el-select / el-option / el-alert / el-button / el-tag` + Tailwind 工具类（`flex items-center gap-1.5 flex-wrap text-[13px] mt-2 w-full` 等），与本仓视觉基线 `frontend/src/components/wizard/ConditionForm.vue`（全 el-* + Tailwind）同风格。

## 4. 契约关键点抽查 — 全部 PASS
| 项 | 结论 | 证据（`frontend/src/stores/ai.ts` 等） |
|---|---|---|
| a. generative_form 特判，不落通用执行器 | PASS | `ai.ts:915` `if (frame.name === 'generative_form') { this.openGenerativeForm(message, frame); break }`，`break` 在 `void this.runFrontendTool(...)`（行 918 之后）之前；且 `generative_form` 未登记在 `FRONTEND_TOOLS` / `WORKSPACE_ACTIONS`（grep 该文件仅出现在注释），未特判时 `executeFrontendTool` 会落到 `utils/frontend-tools.ts:164-168` 的说明文本分支（`未知前端工具 …`），证实注释里"说明文本不是值 JSON"的判断成立 |
| b. 取消路径 + schema 解析失败路径均回灌 `cancelled:true` | PASS | 解析失败：`ai.ts:647` `void this.submitFrontendToolResult(frame.toolCallId, '', 'frontend-executor', true)`（`!spec \|\| !runId` 分支，并把 pendingCall 置 `failed`）；取消：`ai.ts:702-709` `cancelGenerativeForm` → `submitFrontendToolResult(..., 'http-post', true)` |
| c. 支持 cancel，且区分 400 FORM_RESULT_REJECTED / 409 DUPLICATE_TOOL_CALL_ID | PASS | `ai.ts:587-592` 新增第 4 参 `cancelled = false`，返回 `{ok, duplicate?, rejected?, message?}`；`ai.ts:606-608` 409 → `DUPLICATE_TOOL_CALL_ID` → `{ok:false,duplicate:true}`；`ai.ts:610-618` 400 → `FORM_RESULT_REJECTED` → 读取 `error.bodyAs<{reasons?: unknown}>()?.reasons` 拼成 `rejected` 文案；`ai.ts:686-694` 收到 `rejected` 时 `status='filling'` + `error='提交被后端拒绝（挂起仍在等待）：…'`（同 toolCallId 可改值重发）。后端侧核对：`AiController.java:233` 端点 → 409 走 `duplicateToolCall`（body `code=DUPLICATE_TOOL_CALL_ID`）、400 走 `rejectedFrontendResult`（body 含 `reasons` 字段）⇒ 前端取 `reasons` 字段名一致 |
| d. 本地超时到点自动 `cancelled:true` 回灌、409 忽略 | PASS | `ai.ts:650-671`：`deadline = frame.expiresAt`（epoch 毫秒，见 `types/sse.ts:182-183`）否则 `Date.now() + timeoutSeconds*1000`，`formExpiryTimer = setTimeout(() => { ... void this.cancelGenerativeForm(true) }, max(0, deadline-now))`；`ai.ts:709-712` 409（`duplicate`）不置 error、按幂等忽略，仅真失败才写 `form.error` |
| e. 类型面 | PASS | `types/ai.ts:69-76` `result?: string`（改可选）+ `cancelled?: boolean`（带 GFa 裁决 #2 注释）；`types/api.ts:71-77` `FORM_RESULT_REJECTED` / `FORM_SCHEMA_REJECTED` / `FRONTEND_CANCELLED`；后端 `FrontendToolResultRequest.java:14-15` 确有 `Boolean cancelled`，`GenerativeFormRules.java:58/61` 确有 `FORM_SCHEMA_REJECTED`/`FORM_RESULT_REJECTED`，`PendingToolCall.java:40` 有 `FRONTEND_CANCELLED` ⇒ 前后端契约对齐 |
| f. AiPanel 登记与渲染 | PASS | `AiPanel.vue:323` `generative_form: '生成式表单'`；`AiPanel.vue:202-216` 新增挂起卡分支 `v-else-if="m.pendingCall.kind === 'FRONTEND' && m.pendingCall.name === 'generative_form'"` 内渲染 `<FormRenderer :schema="liveForm.form" :submitting=… :error=… :remaining-seconds="formRemainingSeconds" @submit @cancel />`，兜底 `v-else` 显示"表单已取消/已提交/状态已丢失"标签；`liveForm`（行 384-387）仅 `filling/submitting` 才渲染 |

## 5. form-schema.ts 防御性校验 — PASS（静态 + 动态双证）
读代码：`parseFormSchema`（`types/form-schema.ts:102-130`）对非对象、`scenario` 非字符串/空、`title` 非字符串、`fields` 非数组/空、字段 `key/label` 缺失、`type` 不在六型白名单、enum/multi_select 的 options 非对象数组/空/含空值/重复、字段 key 重复 ⇒ 一律 `return null`；`checkFormValues`（行 181-232）覆盖：未知字段（行 184-188）、必填缺失（行 191-195）、text/number/boolean 形状、date 正则+`Date.parse`、enum 越界、multi_select 越界/重复。

动态复核（把该文件原样复制到 repo 外 `<LOCAL_WORK>\gfb-scratch\fs.mts`，用 `node --experimental-strip-types` 跑断言，未改 repo）：
```
parse[null] -> null          parse[string] -> null
parse[missing scenario] -> null   parse[empty fields] -> null
parse[bad type] -> null      parse[dup key] -> null
parse[enum no options] -> null    parse[enum bare-string options] -> null
parse[valid] -> {"scenario":"FILTER","fields":[…]}
check missing required : ["「A」为必填项"]
check unknown field    : ["存在 schema 之外的字段：ghost"]
check enum out-of-range: ["「A」的取值不在选项内"]
check date bad format  : ["「D」应为 yyyy-MM-dd 格式的合法日期"]
check date bad day     : ["「D」应为 yyyy-MM-dd 格式的合法日期"]   （2024-13-45）
check ok               : []
```

## 6. "3 处 v-html/style 命中均为注释文本" 独立复核 — PASS（需限定口径，见说明）
```
$ grep -rn "v-html" frontend/src → 3 处：
 components/AiPanel/FormRenderer.vue:12  <!-- 字段区：… 文案一律文本插值，永不 v-html -->
 components/AiPanel/FormRenderer.vue:111  *   内联 Tailwind 工具类；无 <style> 块、无 v-html …
 types/form-schema.ts:9                   * 安全边界：… 永不 v-html …
```
- 全仓 **无任何真实 v-html 使用**（3 处全在注释里）⇒ 说法属实。
- `<style` 全仓 3 处：`FormRenderer.vue:111`（注释）、`AiPanel.vue:570`（**真实但既有**的 `<style scoped>`，与 HEAD 字节相同、本棒未新增）、`utils/excel.ts:610`（既有 JS 里的 `<styleSheet>` XML 字符串）。故"3 处"这一数字**只与 v-html 的 grep 完全吻合**；若把 `<style` 一并算入，命中不止 3 处，但结论不变：**FormRenderer.vue 零真实 style 块、本棒零新增 style 块**。

## 7. 已知登记项：过时注释仍残留 — PASS（确认残留）
`frontend/src/utils/frontend-tools.ts:14`：
```
 * - `generative_form` 后端已披露，前端执行接入待后续棒。
```
确认行号正是 14（`grep -n` 命中 `utils/frontend-tools.ts:14`），该文件**未被本棒修改**（不在 6 项之内），故注释确实过期待修。**连带一处同类过期**：`frontend/src/types/tools.ts:34` 写"DC-15 起 `generative_form` 亦然（后端已披露，前端执行接入见 `utils/frontend-tools.ts` 头部口径）"——指向同一段过期口径，建议一并修（同样未被本棒触碰）。

---

## FAIL / 可疑处
无 FAIL。以下 3 条为**低风险备注，不构成验收不通过**：

- **A（轻微偏差）** `FormRenderer.vue:29` 的 `el-input-number` 用了内联 style 属性 `style="width: 100%"`——全仓 `.vue` 里唯一的字面内联 style（`grep -rn 'style="' --include=*.vue` 仅此 1 处，另一处是 `SpreadGrid.vue:6` 的动态 `:style="{ height }"`）。它不是 `<style>` 块，故未违反第 3 项字面要求；动机应是 Element Plus 对 `el-input-number` 的默认宽度覆盖。若要严格贴"全 Tailwind 类"纪律，可改用 `class="w-full"` 或 CSS 变量，但 EP 的 `.el-input-number{width:150px}` 可能压过工具类，属可接受的务实处理。
- **B（潜在、未触发）** `FormRenderer.vue:138` `const model = ref(buildInitialValues(props.schema))` 只在组件初始化时按 schema 建值，未 `watch(props.schema)`。当前调用点由 `v-if="liveForm.toolCallId === m.pendingCall.toolCallId"` 且消息列表按 id 键控，正常路径下每个表单都是新实例；仅当同一 DOM 位置复用到同一组件实例且 schema 变化时，旧字段值会残留并被 `checkFormValues` 判为"schema 之外的字段"从而被后端 400 拒（可改值重发，不致命）。属边界场景，未见触发路径。
- **C（后端码覆盖不全）** 400 的 `code` 后端可能返回 `FORM_RESULT_REJECTED` 之外的值（`FRONTEND_TOOL_RESULT_REJECTED`，见 `AiController.java:544` verdict==null 分支）。前端只特判 `FORM_RESULT_REJECTED`，其余 400 走通用分支（置 notice + 表单回 `filling` + 提示"提交失败，请重试或点「取消」放弃"，`ai.ts:695-699`）——行为仍可恢复，只是提示语不如拒绝原因精确。后台对非表单的前端工具同样适用，不影响表单主路径。

## 一行总结论
**6 项全部 PASS、0 FAIL：范围恰好 6 项且未碰后端/CI/依赖文件，typecheck（exit 0，3.7s）与 build（exit 0，38.2s）皆绿，无新增 `<style>` 块且全仓零真实 `v-html`（3 处命中确为注释），契约 6 个关键点（generative_form 特判、双路径 `cancelled:true`、409/400 区分与同 id 重发、本地超时回灌、类型/错误码、AiPanel 登记与渲染）与后端 `FrontendToolResultRequest.cancelled` / `FORM_RESULT_REJECTED` 实测对齐，`parseFormSchema`/`checkFormValues` 经动态断言验证；仅剩 `utils/frontend-tools.ts:14`（连带 `types/tools.ts:34`）过时注释待修，另 3 条低风险备注（内联 width、无 schema watcher、非表单 400 码提示不够精确）不影响验收。**
