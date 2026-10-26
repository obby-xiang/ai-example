> **历史分析 / 过程证据 — 非现行基线，非验收结论**
>
> - 归档：2026-10-26 · `docs/evidence/history-recovery-2026-10-26/wire-only/`
> - 来源：Kimi Code 会话 `<KIMI_SESSION>` / 子 agent `agent-298` 的 wire 末条 assistant 文本（只读流式解析；非原始会话转写）
> - 原始体量：22172 B / 14884 字符 / 253 行；行指纹覆盖率（仓内 + 落盘语料）11.5%
> - 类别：**正式独立报告**
> - 脱敏：路径 1 / 会话标识 0 / 身份 0 / 密钥 0
> - 正文为该条消息的完整文本，未删段、未改写结论；仅脱敏与行尾空白规整。当时结论不代表当前代码状态。
# M1b 走读报告（前端批次B + 批次C）

**仓库状态（只读核对）**：`<REPO_ROOT>`，branch `main`，HEAD `21b05f0b5af9323b30f0cb7651ead272fd15b58a`。工作区除本任务 6 个前端文件外，另有 6 个 backend 文件 `M` 与 4 处 backend 未跟踪新文件（`common/sse/`、`SseChatEmitterWriteBudgetTest.java`、`common/sse/` 测试、`TaskSseServiceTest.java`）——**不属**本走读声明的"前端两批"，此处仅登记。

**总量核对**：`git diff --numstat` 6 文件合计 **182+/74−**（与 `--stat` 一致）。其中批次B 三文件 = 24+19 / 8+8 / 12+8 = **44+/35−**，与声称 "44+/35−" **完全吻合**；余下 138+/39− 为批次C（Export 36+6 / frontend-tools 99+31 / ai.ts 3+2）。声称的"6 个修改文件"数量吻合。

---

## 1. frontend/src/views/TaskCenterView.vue（24+/19−，3 hunks）

**变更摘要**
- hunk1（模板，创建任务弹窗）`TaskCenterView.vue#el-dialog[title="创建任务"]:121-138`：`label-width="80px"`→`auto`（:122）；`el-radio-group` 加 `class="flex flex-col items-start gap-1"`（:124）；两个 `el-radio` 加 `class="!h-auto !whitespace-normal !items-start !mr-0"`（:125 / :129）；`<el-form-item label="任务名称">` 去掉 `required`（:135）；placeholder 改为 `例如：Q4 税率批量导入（留空则自动命名）`（:136）。
- hunk2（模板，作业表）`:170-188`：8 列参与改动 —— `作业 width=80→min-width=56`、`类型 100→60`、`状态 100→80`、`行级进度 min-width 140→108`、`错误 70→48`、`警告 70→48`、`开始 160→138`、`结束 160→138`。同文件"文件"表（:194-197）与"问题"表（:204-214）未动。
- hunk3（脚本）`:432-436` 新增 `defaultTaskTitle(type)`；`:438-441` `createTask()` 删除空值告警分支，改为 `const title = createForm.title.trim() || defaultTaskTitle(createForm.type)`；`:449` `createForm.title = ''` 保持。

**归属判定**：全部 3 个 hunk 归**批次B**（创建弹窗重叠 / label-width / 作业表列宽 / 任务名称非必填方案B），与声称一一对应。

**范围外 hunk**：无独立范围外 hunk。无 `console.*` / `debugger` / 注释掉的代码 / TODO（已用 `git diff -U0 | grep` 逐文件扫描确认，6 文件全部无命中）。

**异常标记（行号 + 原文）**
1. 名称恒非空——**成立**。提交路径唯一：`TaskCenterView.vue#createTask:438-443`
   ```ts
   const title = createForm.title.trim() || defaultTaskTitle(createForm.type)
   ...
   const task = await taskStore.createTask(createForm.type, title)
   ```
   `createForm` 声明为 `reactive<{ type: TaskType; title: string }>({ type: 'EXPORT', title: '' })`（`TaskCenterView.vue:255`），`title` 类型为 `string`，`.trim()` 不会因 undefined 抛错；空串/纯空白走 `||` 分支。`defaultTaskTitle`（:434-436）返回模板字符串 `${TASK_TYPE_LABELS[type]}任务 ${new Date().toLocaleString('zh-CN')}`，即便 `TASK_TYPE_LABELS[type]` 为 undefined 也仍是非空字符串。
2. 全仓 `createTask` 调用点共 3 处（`grep` 全仓）：`TaskCenterView.vue:443`（非空字符串）、`ExportWizardView.vue:1021` 与 `ImportWizardView.vue:1000`（写死 `` `导出任务 ...` `` / `` `导入任务 ...` ``）。store 侧 `stores/task.ts:242` 签名 `title?: string`，`api/tasks.ts:25-26` 逻辑为 `title === undefined ? { type } : { type, title }` —— 即**不存在**传 `null` 的代码路径（缺省时字段直接不出现）。
3. 注释口径核对：`defaultTaskTitle` 注释自称"口径与两处向导的自动建任务一致"——已核对，向导为 `` `导出任务 ${new Date().toLocaleString('zh-CN')}` `` / `` `导入任务 ...` ``（ExportWizardView.vue:1021、ImportWizardView.vue:1000），与 `TASK_TYPE_LABELS`（`utils/format.ts:61-64`：EXPORT→'导出'、IMPORT→'导入'）拼出的结果一致。
4. `!` 前缀类（`!h-auto` 等）在 Tailwind `^3.4.17` 下语法正确（v3 的 important 修饰符是前缀）；`tailwind.config.js` 的 `content: ['./index.html','./src/**/*.{vue,ts}']` 覆盖本文件，类名不会因扫描缺失被裁掉。同文件其余 `!` 均为 `v-if="!..."` 等非类名字符串。

**与声称范围不符之处**
- 声称"作业表 8 列 width→min-width"：实际只有 **7** 列是 `width=`→`min-width=`；第 8 列 `行级进度` 原本就是 `min-width="140"`，本次只是**改值** 140→108（`TaskCenterView.vue:179`）。
- 7 列的 min-width 取值普遍**小于原 width**（80→56、100→60、100→80、70→48、70→48、160→138、160→138），不是同值换算。
- 声称未提及的额外改动：radio 上除 `!h-auto !whitespace-normal` 外还加了 `!items-start !mr-0`；`el-radio-group` 上的 `items-start gap-1`。

---

## 2. frontend/src/views/DefinitionsView.vue（8+/8−，2 hunks）

**变更摘要**
- hunk1（模板，详情抽屉字段表）`:92-98`：7 列参与 —— `排序 width=70→min-width=48`、`字段编码 150→120`、`字段名称（表头）min-width 140→134`、`类型 width 110→min-width 120`、`必填 70→48`、`主键 70→58`、`选项 / 引用 min-width 200→184`。
- hunk2（模板，编辑抽屉表单）`:132`：`label-width="110px"`→`label-width="auto"`。

**归属判定**：2 个 hunk 均归**批次B**（`label-width="auto"` + 字段表 min-width），与声称一致。

**范围外 hunk**：无。

**异常标记（行号 + 原文）**：无代码异常。已确认全仓无 `v-html` / `innerHTML` / `insertAdjacentHTML` / `dangerouslySetInnerHTML`（仅 3 处注释文本提到"永不 v-html"：`components/AiPanel/FormRenderer.vue:12`、`:111`、`types/form-schema.ts:9`）。

**与声称范围不符之处**
- 两列是 `min-width`→`min-width` 的**取值调整**而非 `width→min-width`：`字段名称（表头）` 140→**134**（:94）、`选项 / 引用` 200→**184**（:98）。
- `类型` 列 110→**120** 是 min-width **变大**（其余多为变小）。
- 声称未提及本文件的列表页表格（:36-62）——已核对，该表本次**未改动**。
- 本文件仅编辑抽屉的 form 改为 `label-width="auto"`（:132），仓内该文件再无其他 `label-width`（已 `grep`）。

---

## 3. frontend/src/views/ImportWizardView.vue（12+/8−，3 hunks）

**变更摘要**
- hunk1（模板，暂存区 collapse）`:143-146`：删去 `:title="\`${defName(...)}（${group.defCode}）— ${group.rowCount} 行\`"` 属性，改为 `<template #title>` + `<span class="block whitespace-normal leading-snug">` 包裹同一段文本插值；附带一条 HTML 注释：
  ```html
  <!-- 行高必须落在这个块级元素上：inline 子元素的小 line-height 会被 el-collapse-item__title 的 48px 行高「撑」回去 -->
  ```
- hunk2（脚本 import）`:214`：`import { computed, h, nextTick, ... } from 'vue'` —— 新增 `h`。
- hunk3（脚本 `handleUpload`）`:538-543`：
  ```ts
  // 多条目须换行展示：ElMessageBox 的 message 支持 VNode，用 Tailwind whitespace-pre-line 还原 \n
  await ElMessageBox.alert(
    h('span', { class: 'whitespace-pre-line' }, result.unmatchedFiles.join('\n')),
    '以下文件未匹配到配置项',
    { type: 'warning' }
  )
  ```

**归属判定**：3 个 hunk 均归**批次B**（collapse #title 块级化、ElMessageBox.alert 改 `h()` vnode 渲染），与声称一致。

**范围外 hunk**：无。

**异常标记（行号 + 原文）**
1. **未发现 raw HTML 注入**。`h('span', { class: ... }, text)` 的第三个参数传入的是**字符串 children**，Vue 按文本节点处理；全文件无 `v-html` / `innerHTML`，数据源 `result.unmatchedFiles.join('\n')` 是纯字符串。Element Plus `^2.9.3` 类型定义确认 VNode 合法：`node_modules/element-plus/es/components/message-box/src/message-box.type.d.ts:106` → `message?: string | VNode | ((params: MessageBoxActionHandlers) => VNode);`
2. 附注（事实登记，不下定级）：换行还原依赖 Tailwind 的 `whitespace-pre-line` 类实际落到 `.el-message-box__message` 内的 `<span>` 上；类名以字面量出现在 `.vue` 文件中，`tailwind.config.js` 的 `content` 覆盖 `./src/**/*.{vue,ts}`，扫描可见。
3. 原实现 `result.unmatchedFiles.join('\n')` 作为 string 传入（未改的其他 MessageBox 调用无同类多行场景：全仓仅 `ImportWizardView.vue:539` 一处 `ElMessageBox.alert`）。

**与声称范围不符之处**：无实质不符；仅补充"删除了 `:title` 属性"这一声称未明写的动作。

---

## 4. frontend/src/views/ExportWizardView.vue（36+/6−，2 hunks）

**变更摘要**
- hunk1（脚本）`ExportWizardView.vue#ensureGridLoaded:653-682`：返回值由 `Promise<void>` 改为 `Promise<{ ok: boolean; error: string }>`；四个早退分支各自产出结局：
  - `:654-656` 空 code → `{ ok: false, error: '缺少配置编码' }`
  - `:657-659` `loadedGrids.has(code)` → `{ ok: true, error: '' }`
  - `:660-662` `taskId.value === null` → `{ ok: false, error: '当前未绑定任务，无法读取结果文件' }`
  - `:663-666` `!grid` → `{ ok: false, error: \`${code} 的结果表格组件未挂载（请重开该标签页重试）\` }`
  - 成功路径 `:671-677` 返回 `{ ok: true, error: '' }`；`catch` `:678-683` 中新增 `const reason = error instanceof Error ? error.message : \`${code} 结果文件加载失败\``，`ElMessage.error(reason)` 与 `loadedGrids.delete(code)`、`return { ok: false, error: reason }`。
  - 新增 JSDoc（:640-652），自称 "S2-1"，并登记"与预算的关系（S2-2 后同值）"：SpreadGrid `READY_TIMEOUT_MS` 60s 与折算后预算 60s 同值。
- hunk2（脚本，`PAGE_HANDLER_OPEN_EXPORT_EDITOR` handler）`:967-978`：`await ensureGridLoaded(defCode)` 改为 `const loaded = await ensureGridLoaded(defCode)`，失败分支**返回**（非 throw）：
  ```ts
  return `${defCode} 的在线编辑器表格未就绪（任务 #${taskId.value}）：${loaded.error}。`
    + '导出结果步骤与该配置标签已切到前台，但表格位置是加载错误提示，没有可编辑的数据；'
    + '请提示用户核对网络后重试，在此之前不要假设编辑器可用。'
  ```

**归属判定**：2 个 hunk 均归**批次C**（S2 修单 + S2-a），与声称一致。

**异常标记（行号 + 原文）—— `{ok,error}` 的全部消费点**
`grep` 全仓，`ensureGridLoaded` 定义于本文件、无跨文件导入；调用点共 **4** 处：

| 行号 | 所在函数 | 是否消费返回值 |
|---|---|---|
| `ExportWizardView.vue:367` | `loadExistingResults()` | 否（`await ensureGridLoaded(activeTab.value)`，丢弃） |
| `ExportWizardView.vue:622` | `refreshResults()` | 否（`await ensureGridLoaded(first)`，丢弃） |
| `ExportWizardView.vue:684` | `onTabChange(name)` | 否（`void ensureGridLoaded(String(name))`，显式丢弃） |
| `ExportWizardView.vue:971` | `registerPageHandler(PAGE_HANDLER_OPEN_EXPORT_EDITOR, ...)` | **是**（:972-976 `if (!loaded.ok)` 分支回灌失败文案） |

- 失败文案**不含**"已打开"字样：:972-975 的三行字符串逐一核对，出现"未就绪 / 已切到前台 / 没有可编辑的数据 / 不要假设编辑器可用"，无"已打开"子串；成功文案 :977 为 `` `已打开任务 #${taskId.value} 的 ${defCode} 在线编辑器（导出结果表格）` ``。
- "失败含引擎原文"的原文来源有 4 类，其中仅 catch 分支是引擎/网络原文：`error.message`（:680，来自 `grid.loadFromXlsx` → `SpreadGrid#waitForReady` 或 `tasksApi.downloadFile` 的拒绝原因）；其余 3 类为本地常量文案（缺少编码 / 未绑定任务 / 组件未挂载）。
- 交叉核对注释中的数值引用：`SpreadGrid.vue:99 const READY_TIMEOUT_MS = 60_000`（与注释"同为 60s"一致）；`api/http.ts:85 timeout: 60000`（与 frontend-tools.ts 引用的"`http.ts:85`"锚点一致，行号正确）。
- 该 handler 的其他早退分支仍为 `throw new Error(...)`（:951、:955、:960），即失败结局同时存在"返回字符串"与"抛错"两种形态——仅登记事实。

**与声称范围不符之处**：无。

---

## 5. frontend/src/utils/frontend-tools.ts（99+/31−，3 hunks）

**变更摘要**
- hunk1（文件头注释 :39）：预算表述由 `默认 90s < 后端挂起上限 120s` 改为 `默认 60s = 后端挂起上限 120s − 回灌 POST 上限 60s`。
- hunk2（常量与折算函数 :156-247）：
  - `FRONTEND_TOOL_TIMEOUT_BUDGET_MS = 90_000` → **`60_000`**（:191）
  - `FRONTEND_TOOL_RESULT_HEADROOM_MS = 30_000` → **`60_000`**（:200）
  - `deriveFrontendToolBudgetMs` 折算式去掉旧的上界常量钳制 `Math.min(FRONTEND_TOOL_TIMEOUT_BUDGET_MS, usableMs)`，改为仅 `Math.max(MIN, usableMs)` 再 `Math.min(..., serverBudgetMs)`；新增 `!Number.isFinite(serverBudgetMs)` 溢出防线（:240）。
  - 新增 `const TIMER_MAX_DELAY_MS = 2_147_483_647`（:278）+ `normalizeBudgetMs` 收口（:289-294）。
- hunk3（`runWithBudget` 域内注释 :317-372）：`executeFrontendTool` :315 `const budgetMs = normalizeBudgetMs(call.budgetMs)` 未改代码；仅在 `setTimeout` 前（:356-357）加注释、JSDoc 增补"计时器 delay 的上界"段（:344-348），并把 `race` 注释的余量表述从"若阻塞在 120s 内"改为"若'阻塞结束 + 回灌 POST'仍 ≤ 服务端 120s 上限"。

**归属判定**：3 个 hunk 均归**批次C**（S2-2）。声称的 4 项（预算公式、常量 60_000、TIMER_MAX_DELAY_MS 钳制同源收口、1e307 溢出防线）全部**存在**且位置对应。

**范围外 hunk**：无。行为层面的可选提示（事实，不下定级）：`FRONTEND_TOOL_TIMEOUT_BUDGET_MS` 由"上界常量"降级为"仅在服务端上限缺失/非法/溢出时的回落值"，因此服务端给 >120s 时预算**不再被封顶**（150s→90s、300s→240s），这是公式改动的直接后果，注释 :222 已明写。

**异常标记（行号 + 原文）**
- TIMER_MAX_DELAY_MS 的病态区（>24.86 天）退化说明——**存在**，`frontend-tools.ts:274-276`：
  > `**诚实的退化登记**：预算 > 24.86 天属**病态配置**（后端默认 120s，与该值差 5 个数量级）：该场景的超时判定实际退化为**本次会话内不超时**，由服务端上限判定兜底 —— 属**可接受退化**，远优于"秒级假超时"。`
- 同源收口表述存在（:269-272）：计时器是唯一判定源、`normalizeBudgetMs` 一次收口、超时文案报同值。
- 1e307 防线文案与代码一致（:238-240 注释 + 代码 `!Number.isFinite(serverBudgetMs)`）。
- 无 `console.*` / `debugger` / 注释掉的代码。

**与声称范围不符之处**：无（声称"预算公式 min(max(server−60000,5000),server)"与 :244-246 实现逐字一致）。

### J6 裁决证据：预算公式与 normalizeBudgetMs 完整原文（逐字抄录）

`frontend/src/utils/frontend-tools.ts:191-247`：
```ts
export const FRONTEND_TOOL_TIMEOUT_BUDGET_MS = 60_000

/**
 * 回灌 POST 的最坏耗时（毫秒）= 预算必须让出的余量。
 *
 * 取值 = `api/http.ts` 的 axios 单请求上限（60s，`http.ts:85`）—— 回灌 POST 自己最多能花
 * 这么久，不是"经验留白"。折算式见 {@link deriveFrontendToolBudgetMs}：
 * `预算 = min(max(服务端上限 − 本余量, 5s 下限), 服务端上限)`。
 */
export const FRONTEND_TOOL_RESULT_HEADROOM_MS = 60_000

/**
 * 折算预算的下限（毫秒）：服务端上限被配得过小时，仍给执行器一点起码的完成时间。
 *
 * 与 {@link deriveFrontendToolBudgetMs} 的上限钳制冲突时以**上限为准**：服务端上限本身
 * ≤ 本值时（病态配置），预算取服务端上限而非本值 —— 宁可无余量，也不倒挂。
 */
const MIN_FRONTEND_TOOL_TIMEOUT_BUDGET_MS = 5_000

/**
 * 由服务端帧的挂起上限折算出执行器预算（S2-2 严格化后的唯一折算式）：
 * `min(max(服务端上限 − {@link FRONTEND_TOOL_RESULT_HEADROOM_MS}, MIN), 服务端上限)`；
 * 上限缺失 / 非法 / 溢出时回落 {@link FRONTEND_TOOL_TIMEOUT_BUDGET_MS}。
 *
 * 默认 120s 上限 → **60s**；90s → 30s；65s → 5s（≈上限，余量被压到 0）。
 *
 * 为什么以服务端值为准而不是写死 120s：帧里的 `timeoutSeconds` 就是后端真实等待上限
 * （`app.ai.hitl.timeout`，可在配置里改）。以后端值为基算出预算，后端调整上限时前端无需
 * 同步改常量；余量取回灌 POST 自身的 axios 上限（60s），于是
 * `预算 + 60000 ≤ 服务端上限` 对 `服务端上限 ≥ 65s` 的一切取值成立 —— 这才是注释里可自称
 * "保证"的数学口径（旧式 `服务端上限 − 30s` 只在 POST <30s 时成立，见 S2-2）。
 * >120s 的上限不再被常量封顶（150s → 90s、300s → 240s），保证式随服务端值平移。
 *
 * 两道钳制（既有逻辑，取值随 S2-2 平移）：
 * - **下限** `MIN`（5s）防"预算过短"：`服务端上限 − 60s ≤ 5s` 的配置（≤65s）由它兜底
 *   （上限 ≤60s 时 `usableMs` 已 ≤0，同落此下限、再被上界收口）；
 * - **上界** `timeoutSeconds × 1000` 防"预算倒挂"：上限 ≤5s 时下限会反超上限
 *   （`timeoutSeconds=3` → 5000ms > 3000ms），故以服务端上限收口；两者冲突时**上限优先**。
 * - 病态区（上限 ≤65s，即下限生效区）于是落在"预算 == 服务端上限、回灌余量为 0"（≤5s）或
 *   "预算 == 5s 下限、余量不足 60s"：此处上述数学保证必然失效，只能退化为"到点即回灌"。
 *   上限 ≤65s 属**病态配置**（实践中服务端默认 120s），该分支只是"不倒挂"的兜底：到点即回灌，
 *   仍比"什么都不发、坐等后端判超时"多一次如实回执。
 */
export function deriveFrontendToolBudgetMs(timeoutSeconds?: number | null): number {
  const serverBudgetMs = typeof timeoutSeconds === 'number' && Number.isFinite(timeoutSeconds) && timeoutSeconds > 0
    ? timeoutSeconds * 1000
    : null
  // 溢出防线：`timeoutSeconds` 有限但 ×1000 溢出为 Infinity（如 1e307）时同样判非法 ——
  // 否则预算 = Infinity，`setTimeout(Infinity)` 被引擎钳到 ~1ms，等于**瞬间判超时（假超时）**。
  if (serverBudgetMs === null || !Number.isFinite(serverBudgetMs)) {
    return FRONTEND_TOOL_TIMEOUT_BUDGET_MS
  }
  // 折算 = 服务端上限 − 回灌 POST 最坏耗时（60s）：先夹 5s 下限，再以服务端上限收口防倒挂。
  const usableMs = serverBudgetMs - FRONTEND_TOOL_RESULT_HEADROOM_MS
  const clampedMs = Math.max(MIN_FRONTEND_TOOL_TIMEOUT_BUDGET_MS, usableMs)
  return Math.min(clampedMs, serverBudgetMs)
}
```

`frontend/src/utils/frontend-tools.ts:289-294`：
```ts
function normalizeBudgetMs(value?: number): number {
  const requestedMs = typeof value === 'number' && Number.isFinite(value) && value > 0
    ? value
    : FRONTEND_TOOL_TIMEOUT_BUDGET_MS
  return Math.min(requestedMs, TIMER_MAX_DELAY_MS)
}
```
（`TIMER_MAX_DELAY_MS` 定义于 :278：`const TIMER_MAX_DELAY_MS = 2_147_483_647`；喂入 `setTimeout` 处为 :357 `timer = setTimeout(() => resolve({ kind: 'timeout', elapsedMs: Date.now() - startedAt }), budgetMs)`，`budgetMs` 来自 :315 `normalizeBudgetMs(call.budgetMs)`。）

---

## 6. frontend/src/stores/ai.ts（3+/2−，1 hunk）

**变更摘要**：仅一处 JSDoc 段落重写，位于 `ai.ts:1480-1489`（`useAiStore` 内 `runFrontendTool` 注释块）：
- 删除：`预算由帧里的**服务端**挂起上限折算（\`timeoutSeconds\`，默认 120s → 90s），`
- 新增：`预算由帧里的**服务端**挂起上限折算（\`timeoutSeconds\`，默认 120s → 60s = 上限 − 回灌 POST 的 axios 上限 60s），理由与取值见 \`FRONTEND_TOOL_TIMEOUT_BUDGET_MS\` / \`deriveFrontendToolBudgetMs\`：...`

**归属判定**：批次C（S2-2 注释同步）。

**范围外 hunk**：无。

**异常标记（行号 + 原文）—— 是否"仅注释同步"**：**成立**。`git diff -U0` 逐行核对，本文件全部改动行均落在 `/** ... */` 块内（前导 ` * ` 注释行），numstat 3+/2− 与之一致；该文件唯一代码锚点 `ai.ts:1498 budgetMs: deriveFrontendToolBudgetMs(frame.timeoutSeconds)` **未改动**（`git diff` 无该行）。仓库内无其他 ai.ts 改动 hunk。

**与声称范围不符之处**：无。

---

## 汇总（事实层）

- **归属**：6 文件 13 个 hunk 全部落在声称范围内；**未发现**任何范围外 hunk、调试代码、`console.*`、注释掉的可执行代码。仅 frontend-tools.ts 与两个 view 的新增代码注释属实现伴随文档。
- **声称内容缺失**：无。四组声称（批次B 三文件细项、批次C `{ok,error}` 回灌、预算公式/常量/钳制/溢出防线、ai.ts 仅注释）逐条在本仓库**找到**。
- **与声称措辞不符（措辞级，非范围级）**：
  1. TaskCenterView"作业表 8 列 width→min-width"——实际 7 列是 `width→min-width`，第 8 列 `行级进度` 为 `min-width` 改值（140→108）；7 列 min-width 值均小于原 width。
  2. TaskCenterView radio 类实际为 `!h-auto !whitespace-normal !items-start !mr-0` + group `items-start gap-1`，多于声称的两项。
  3. DefinitionsView 两列 `min-width` 改值为 140→134、200→184（声称仅"min-width"），且 `类型` 列 110→120 是变大。
  4. ImportWizardView 另含"删除 `:title` 属性改用 `#title` 插槽"这一未明写的等价动作。
- **行号锚点汇总**：`TaskCenterView.vue:122,124-131,135-136,170-188,255,434-436,438-449`；`DefinitionsView.vue:92-98,132`；`ImportWizardView.vue:143-146,214,538-543`；`ExportWizardView.vue:367,622,653-683,684,967-978`；`frontend-tools.ts:39,191,200,208,234-247,278,289-294,315,344-348,356-357`；`stores/ai.ts:1480-1489`；旁证：`api/http.ts:85`、`components/SpreadGrid.vue:99`、`utils/format.ts:61-64`、`stores/task.ts:242`、`api/tasks.ts:25-26`、`tailwind.config.js:3`、`node_modules/element-plus/.../message-box.type.d.ts:106`。
