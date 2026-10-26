> **历史分析 / 过程证据 — 非现行基线，非验收结论**
>
> - 归档：2026-10-26 · `docs/evidence/history-recovery-2026-10-26/wire-tail/`
> - 来源：Kimi Code 会话 `<KIMI_SESSION>` / 子 agent `agent-489` 的 wire 末条 assistant 文本（消息序号 m=22；只读流式解析，非原始会话转写）
> - 历史时点：2026-10-11 22:30 → 2026-10-11 22:32（wire 事件时间窗，UTC+8）；当时模型：`DeepSeek/deepseek-flash`
> - 原基线：`3edaeb9`（正文自动摘取的首次基线提及），未经本批复验
> - 原始体量：33314 B / 24750 字符 / 512 行；行指纹覆盖率（本批复算）1.1%（最高单文件 1%：`docs/research/调研-无向量知识问答业界惯例-2026-10-21.md`）
> - 类别：**施工复核**
> - 与既有件关系：072-entry；报告口径覆盖率 0.11
> - 脱敏：路径 1 / 会话标识 0 / 身份 0 / 密钥 0 / 命名纪律 0
> - 正文为该条消息的完整文本，未删段、未改写结论；仅脱敏、占位符归一与行尾空白规整。当时结论不代表当前代码状态，不得作为当前缺陷或有效裁决引用。
核验完成。以下每条均为 `git` HEAD=3edaeb9 工作树实读（工作树干净，`git status --porcelain` 空输出）。

---

# 1. `FrontendToolExecution` 字段面 —— 【确认】

`frontend/src/types/tools.ts:114-120`（原文，含闭合括号）：

```ts
114: /** 前端工具的执行结果（回灌给后端的文本 + 本地痕迹）。 */
115: export interface FrontendToolExecution {
116:   ok: boolean
117:   availability: ToolAvailability
118:   /** 回灌给模型的说明文本（POST /api/ai/frontend-tool-result 的 result） */
119:   result: string
120: }
```

- 恰为 `{ok; availability; result}` 三字段，行号与草案 :115-120 一致。
- **没有** `outcome` 字段（全仓 `grep -rn "outcome" frontend/src/types/*.ts` 无命中）。
- `ToolAvailability` 定义在 `frontend/src/types/tools.ts:55`：`'available' | 'degraded' | 'unavailable'`。

---

# 2. `frontend/src/utils/frontend-tools.ts` 逐项

### 2.1 `timeoutOutcome` —— 【确认】（草案 :411-427，实为 411-426）

```ts
411: function timeoutOutcome(call: FrontendToolCall, budgetMs: number, elapsedMs: number): FrontendToolExecution {
...
418:     ? `${label}（${call.name}）前端超时：等待 ${waitedSeconds} 秒仍未收到浏览器的完成回执（执行器预算 ${budgetSeconds} 秒）。`
421:       + '本次调用结局未知 —— 既未确认成功，也未确认失败。'
...
422:     : `${label}（${call.name}）前端超时：等待 ${waitedSeconds} 秒仍未返回执行结果（执行器预算 ${budgetSeconds} 秒）。`
423:       + '本次调用结局未知 —— 既未确认成功，也未确认失败。'
424:       + '请提示用户核对界面上的实际状态（相关卡片、列表或表格，以及数据是否已变化），确认后再决定是否重试。'
425:   return { ok: false, availability: 'degraded', result }
426: }
```

- 返回对象 `{ok:false, availability:'degraded', result}` ✓（425 行）。
- 文案原文 ✓，下载类与一般类两条支**都**含 `本次调用结局未知 —— 既未确认成功，也未确认失败。`（421 / 423）。

### 2.2 `runPageHandler` 成功支 —— 【确认】

```ts
875: async function runPageHandler(
876:   name: string,
877:   handler: (payload: Record<string, unknown>) => unknown | Promise<unknown>,
878:   args: Record<string, unknown>,
879:   displayName?: string
880: ): Promise<FrontendToolExecution> {
881:   const label = displayName ?? name
882:   try {
883:     const outcome = await handler(args)
884:     return {
885:       ok: true,
886:       availability: 'available',
887:       result: typeof outcome === 'string' ? outcome : `${label}已执行`
888:     }
889:   } catch (error) {
890:     return {
891:       ok: false,
892:       availability: 'degraded',
893:       result: `${label}执行失败：${error instanceof Error ? error.message : String(error)}。请提示用户手动重试。`
894:     }
895:   }
896: }
```

- `ok:true` 在 **885** 行，草案 :885 准确；函数区间实为 **875-896**（草案 :875-895 差 1 行）。
- handler 返回**字符串**：原样成为 `result`，无条件包 `ok:true`；返回**非字符串**（对象 / `undefined`）：`${label}已执行`，同样 `ok:true`。**关键：handler 返回"失败形字符串"时也一律 `ok:true`**，只有 handler **抛错**才落 `ok:false`+degraded。

### 2.3 `runRegisteredTool` / 执行器签名 —— 【确认】（catch 行号微调）

```ts
 69: export type FrontendToolExecutor = (call: FrontendToolCall) => Promise<string> | string
...
808: async function runRegisteredTool(
...
840:   try {
841:     const result = await executor({ ...call, args: validated.coerced })
842:     return { ok: true, availability: 'available', result }
843:   } catch (error) {
844:     return {
845:       ok: false,
846:       availability: 'degraded',
847:       result: `${descriptor.displayName}执行失败：${error instanceof Error ? error.message : String(error)}。请提示用户手动重试。`
848:     }
849:   }
850: }
```

- `:842` 逐字一致 ✓；执行器签名 `:69` 逐字一致 ✓。
- **微调**：`catch` 关键字在 **843** 行，草案写 :844-849 起点偏 1（body 844-848，闭合 849）。
- 另一处要点：无 executor 时（831-838）返回 `ok:false` + **degraded**（不是 unavailable）。

### 2.4 `defaultExecutor` —— 【确认】

```ts
981: /** 契约层自带的默认执行器（页面未覆盖时使用）。 */
982: function defaultExecutor(name: FrontendToolName): FrontendToolExecutor | null {
983:   if (name === 'open_export_file_editor') {
984:     return async (call) => {
985:       const handler = getPageHandler(PAGE_HANDLER_OPEN_EXPORT_EDITOR)
986:       if (!handler) {
987:         throw new Error('导出向导未注册在线编辑器能力')
988:       }
989:       const outcome = await handler({ taskId: call.args.taskId, defCode: call.args.defCode })
990:       return typeof outcome === 'string' ? outcome : `已请求打开 ${String(call.args.defCode)} 的在线编辑器`
991:     }
992:   }
993:   return null
994: }
```

- `:989` 调 handler、`:990` `typeof outcome === 'string' ? outcome : ...` ✓；字符串原样返回 → 上行 `runRegisteredTool:842` 包成 `ok:true` ✓。

### 2.5 「工作区动作表」四段 —— 【需修正（命名）】+ 四段行号【确认】

**命名需修正**：草案把 `:739-782` 称为"工作区动作表"。真实分裂为两处：

- 动作表本体 `WORKSPACE_ACTIONS` 在 **frontend-tools.ts:603-662**（`navigate_to` 604-621 / `select_definitions` 622-637 / `set_condition` 638-649 / `confirm_step` 650-661）。
- 草案 :739-782 实为 **`runWorkspaceAction`（696-782）的后半段**：契约层结局 → 三级可用性的映射。下面四段均在此函数内。

| 草案行号 | 实读行号 | 触发条件 | ok | availability | 文案原文 |
|---|---|---|---|---|---|
| :750-756 | **750-756** | `outcome.handled` 且 `navigatesTo≠null` 且 `wait.pageReady && wait.taskBound` | `true` | `'available'` | `` `${outcome.message}（页面已就绪${bound}，可继续执行本页动作）` `` |
| :758-766 | **758-766** | `handled` 且 `navigatesTo≠null` 且 `pageReady===true` 但 `taskBound===false`（即 3s 内任务未绑定） | `false` | `'degraded'` | `` `${outcome.message}，但任务 #${expectedTaskId} 的绑定没有完成（向导页已就绪，未绑定该任务）。请核对任务 #${expectedTaskId} 是否存在，或提示用户手动在任务中心打开该任务后重试。` `` |
| :767-772 | **767-772** | `handled` 且 `navigatesTo≠null` 且 **`pageReady===false`（`waitForPageReady` 3s 超时）** | **`true`** | `'available'` | `` `${outcome.message}（页面${expectedTaskId === null ? '' : '与任务绑定'}仍在加载中；若要继续在该页动作，请稍后重试或提示用户手动操作）` `` |
| :774 | **774** | `handled===true` 且 `navigatesTo` 为 `null`（非导航类动作） | `true` | `'available'` | `outcome.message` |

```ts
750:      if (wait.pageReady && wait.taskBound) {
751:        const bound = expectedTaskId === null ? '' : `，任务 #${expectedTaskId} 已绑定`
752:        return {
753:          ok: true,
754:          availability: 'available',
755:          result: `${outcome.message}（页面已就绪${bound}，可继续执行本页动作）`
756:        }
757:      }
758:      if (wait.pageReady) {
759:        // 页面已就绪却仍没绑定该任务 ⇒ 不是"还在加载"，再等也不会变：明确降级，不谎报成功
760:        return {
761:          ok: false,
762:          availability: 'degraded',
763:          result: `${outcome.message}，但任务 #${expectedTaskId} 的绑定没有完成（向导页已就绪，未绑定该任务）。`
764:            + `请核对任务 #${expectedTaskId} 是否存在，或提示用户手动在任务中心打开该任务后重试。`
765:        }
766:      }
767:      return {
768:        ok: true,
769:        availability: 'available',
770:        result: `${outcome.message}（页面${expectedTaskId === null ? '' : '与任务绑定'}仍在加载中；`
771:          + '若要继续在该页动作，请稍后重试或提示用户手动操作）'
772:      }
773:    }
774:    return { ok: true, availability: 'available', result: outcome.message }
```

**最值得注意的事实（与 D1 直接相关）**：:767-772 这一支**导航超时（页面根本没就绪）却回 `ok:true` + `available`**，且 line 774 对"handled 但无导航目标"也无条件 `ok:true`。此外 :777-781（草案未列）是契约层拒绝支：`ok:false` + `degraded`。

```ts
776:  // 契约层拒绝（缺任务/缺 handler）→ degraded：给出可行动说明，不静默
777:  return {
778:    ok: false,
779:    availability: 'degraded',
780:    result: `${action.displayName}未完成（${outcome.message}）。请提示用户在界面上手动完成该动作。`
781:  }
```

### 2.6 `PAGE_READY_TIMEOUT_MS` / `waitForPageReady` —— 【确认值，行号微调】

```ts
516: /** 页面就绪等待上限（毫秒）：超过即如实告知"仍在加载"，不无限等。 */
517: export const PAGE_READY_TIMEOUT_MS = 3_000
...
529: export async function waitForPageReady(page: WorkspacePageId, timeoutMs = PAGE_READY_TIMEOUT_MS): Promise<boolean> {
530:   const deadline = Date.now() + timeoutMs
531:   const probes = PAGE_READY_PROBES[page]
532:   for (;;) {
533:     const workspace = useWorkspaceStore()
534:     const ready = workspace.pageId === page
535:       && (probes.length === 0 || probes.some((name) => getPageHandler(name) !== null))
536:     if (ready) {
537:       return true
538:     }
539:     if (Date.now() >= deadline) {
540:       return false
541:     }
542:     await delay(50)
543:   }
544: }
```

- `= 3_000` at **517** ✓（草案 :517 准）。
- 函数实为 **529-544**（草案 ":529-546" 多算 2 行；545 空行、546 是下一段注释）。
- 配套 `waitForTaskBound` 在 566-586，同样默认 3_000、轮询 50ms；`PAGE_READY_PROBES` 在 508-514（`export: ['export.nextStep']`、`import: ['import.nextStep']`，其余三页为空数组）。

### 2.7 `executeFrontendTool` —— 【需修正：只有两支，不是三分支】

```ts
315: export async function executeFrontendTool(call: FrontendToolCall): Promise<FrontendToolExecution> {
316:   const budgetMs = normalizeBudgetMs(call.budgetMs)
317:   const startedAt = Date.now()
318:   const outcome = await runWithBudget(dispatchFrontendTool(call), budgetMs, startedAt)
319:   return outcome.kind === 'timeout'
320:     ? timeoutOutcome(call, budgetMs, outcome.elapsedMs)
321:     : outcome.execution
322: }
```

- 实读是**二元三元**：`timeout → timeoutOutcome` / 否则 `outcome.execution`（草案"三分支"表述不成立；函数区间 315-322，草案 :315-321 差 1 行）。
- **新 `outcome` 维度是否被丢弃**：
  - settled 支（321）**按引用透传**执行结局对象，新字段不会被本函数丢掉。
  - timeout 支（320）是 `timeoutOutcome` **新建**的对象（411-425），新字段必然**缺失** —— 若新维度需覆盖超时结局，必须同时改 `timeoutOutcome`。【此处为基于代码结构的直接推论，非实测】
  - 下游消费侧（`stores/ai.ts`）只读 `ok` / `availability` / `result`，新字段**不会被消费**（见第 3 点）。

### 2.8 `runWithBudget` —— 【确认，不需改】

```ts
351: async function runWithBudget(
352:   task: Promise<FrontendToolExecution>,
353:   budgetMs: number,
354:   startedAt: number
355: ): Promise<BudgetOutcome> {
...
367:     return await Promise.race([
368:       task.then((execution): BudgetOutcome => ({ kind: 'settled', execution })),
369:       expired
370:     ])
371:   } finally {
372:     if (timer !== null) {
373:       clearTimeout(timer)
374:     }
375:   }
376: }
```

- 只做 `{kind:'settled', execution}` / `{kind:'timeout', elapsedMs}` 的包装（判别类型定义在 301-303），**完全不读** `ok`/`availability`；新维度因对象引用透传（368）而自动随行。区间 351-376 ✓。

### 2.9 草案 D1 理由② 引的 `frontend-tools.ts:864` —— 【确认，但语义需补】

```ts
862:   try {
863:     const outcome = await dispatchUiEvent(event)
864:     return { ok: outcome.handled, availability: 'available', result: outcome.message }
865:   } catch (error) {
866:     return {
867:       ok: false,
868:       availability: 'degraded',
869:       result: `工作区动作 ${name} 执行失败：${error instanceof Error ? error.message : String(error)}`
870:     }
871:   }
```

- `:864` 逐字一致 ✓，位于 `runUiEventAction`（853-872），即**路由 3**。
- 此处 `outcome` 是 **863 行的局部变量**（`dispatchUiEvent` 的返回值），**不是**新字段，类型 `UiEventResult`（`frontend/src/types/workspace.ts:71-75`，仅 `{handled:boolean; message:string}`）。
- `ok: outcome.handled` 语义 = "契约层是否已受理并落地该动作"。**副作用**：`availability` 硬编码 `'available'`，故 `ok:false`（如只读态拒绝）时也不会触发 `stores/ai.ts:1815` 的降级 notice —— 卡面直接落 `failed`。

---

# 3. 消费点清点（S0）—— 实读

### `grep -rn "\.ok\b" frontend/src`（全部命中）

| file:line | 上下文 |
|---|---|
| `frontend/src/api/sse.ts:358` | `if (!response.ok) {` —— fetch `Response.ok`，与工具结局无关 |
| `frontend/src/components/AiPanel/AiPanel.vue:300` `/`:302` | `copyState.ok ? '已复制' : '复制失败…'` —— 复制按钮状态 |
| `frontend/src/stores/ai.ts:1278` | `if (outcome.ok \|\| outcome.duplicate)` —— `submitFrontendToolResult` 返回值（回灌 POST 结果） |
| `frontend/src/stores/ai.ts:1311` | `if (!outcome.ok && !outcome.duplicate)` —— 同上，取消回灌路径 |
| **`frontend/src/stores/ai.ts:1804`** | **`const status: ToolRunStatus = execution.ok ? 'succeeded' : 'failed'`** —— **全仓唯一读 `FrontendToolExecution.ok` 的点** |
| `frontend/src/stores/workspace.ts:485` | `if (!response.ok)` —— ui_event `download` 的 fetch |
| `frontend/src/utils/frontend-tools.ts:813` | `if (!validated.ok)` —— `validateArgs` 局部判别联合 |
| `frontend/src/utils/parts.ts:285` | `frame.ok ? 'succeeded' : 'failed'` —— `tool_result` 帧驱动工具段 |
| `frontend/src/utils/parts.ts:364` | `frame.ok ? 'succeeded' : 'failed'` —— `frontend_tool_result` 帧 |
| `frontend/src/views/ExportWizardView.vue:1114` `/`:1214` | `gate.ok` —— `ToolTaskGate` 局部 |
| `frontend/src/views/ExportWizardView.vue:1235` | `loaded.ok` —— `ensureGridLoaded` 局部 |
| `frontend/src/views/ExportWizardView.vue:1258` | `preflight.ok` —— `preflightDownload` 局部 |

测试侧另有：`frontend/tests/tools-and-deadline.spec.ts:61` `expect(execution.ok).toBe(false)`、`:62` `expect(execution.availability).toBe('degraded')`、`:63-64`（读 `execution.result` 含「前端超时」/「结局未知」）。`frontend/tests/parts-residue-cleanup.spec.ts:419` 的 `'probe.ok-tool'` 只是 handler 名，不读 `.ok`。

### `grep -rn "execution\.ok\|execution\.availability\|execution\.outcome" frontend/src`

```
frontend/src/stores/ai.ts:1804:      const status: ToolRunStatus = execution.ok ? 'succeeded' : 'failed'
frontend/src/stores/ai.ts:1815:      if (execution.availability === 'degraded' || execution.availability === 'unavailable') {
```

- **`execution.outcome` 全仓零命中。当前没有任何代码在读 `execution.outcome`。**
- `availability` 的消费也仅 `ai.ts:1815` 一处（`frontend/src/types/tools.ts:117` 是声明本身）。
- 相关的执行结局消费完整链路（`stores/ai.ts:1798-1818`）：

```ts
1798:      const execution = await executeFrontendTool({ ... })
1804:      const status: ToolRunStatus = execution.ok ? 'succeeded' : 'failed'
1806:      this.upsertToolPart(message, { toolCallId, name, kind2: 'FRONTEND', status,
1811:        result: execution.result, ... })
1815:      if (execution.availability === 'degraded' || execution.availability === 'unavailable') {
1816:        this.notice = execution.result
1817:      }
1818:      await this.submitFrontendToolResult(frame.toolCallId, execution.result, 'frontend-executor')
```

即：卡面 status 只由 `ok` 二元派生；回灌 POST 只送 `result` 文本（`ai.ts:1138-1144` 签名 `result: string`）。

### 【重要旁证，D1 相关】渲染侧已有 `'unknown'` 槽位但**无写入方**

- `frontend/src/types/ai.ts:165`：`export type ToolRunStatus = 'pending' | 'running' | 'succeeded' | 'failed' | 'rejected' | 'expired' | 'blocked' | 'unknown' | 'cancelled'`。
- `frontend/src/components/AiPanel/AiPanel.vue:554-557`：`case 'unknown': return 'warning'`；`:575-576`：`unknown: '结局未知（未确认成功/失败）'`。
- `frontend/src/utils/parts.ts:120-122`：`pendingStatusOf` 的 `default` 把 `unknown` 归回 `'pending'`。
- 全仓 `grep -rn "'unknown'" frontend/src`（除类型定义与这两处渲染分支）**无任何赋值点**：`stores/ai.ts:1804` 只产出 `succeeded`/`failed`，帧映射 `parts.ts:210-228` / `ai.ts:333-357` 也无 `unknown` 分支。
- 【结论】"结局未知"的**展示口径已存在**，但**从 `FrontendToolExecution` → 卡面 status 的唯一映射只认 `ok`**，因此当前前端侧超时（`ok:false`）在卡面上表现为 **`failed`「执行失败」**，而不是「结局未知」。这是草案 D1 的事实基础，成立。

---

# 4. `registerPageHandler(` 注册点 —— 【确认：恰好 11 个，全在两向导】

`grep -rn "registerPageHandler(" frontend/src` 全部命中（除 `stores/workspace.ts:268` 的定义本体与 `:7` 注释）：

| file:line | handler 名 |
|---|---|
| `frontend/src/views/ExportWizardView.vue:1136` | `'export.nextStep'` |
| `frontend/src/views/ExportWizardView.vue:1150` | `'export.selectDefs'` |
| `frontend/src/views/ExportWizardView.vue:1167` | `'export.setConditions'` |
| `frontend/src/views/ExportWizardView.vue:1198` | `'export.start'` |
| `frontend/src/views/ExportWizardView.vue:1207` | `PAGE_HANDLER_OPEN_EXPORT_EDITOR`（= `'export.openEditor'`，定义在 `frontend-tools.ts:72`） |
| `frontend/src/views/ImportWizardView.vue:1006` | `'import.nextStep'` |
| `frontend/src/views/ImportWizardView.vue:1027` | `'import.selectDefs'` |
| `frontend/src/views/ImportWizardView.vue:1041` | `'import.upload'` |
| `frontend/src/views/ImportWizardView.vue:1050` | `'import.startPrecheck'` |
| `frontend/src/views/ImportWizardView.vue:1059` | `'import.startImport'` |
| `frontend/src/views/ImportWizardView.vue:1068` | `'import.startPublish'` |

导出 5 + 导入 6 = **11** ✓，且全部在这两个文件 ✓。注销侧对称：`ExportWizardView.vue:1003-1009` `HANDLER_NAMES`（5 项）`:1289` 遍历 `unregisterPageHandler`；`ImportWizardView.vue:995-1002`（6 项）。

【附带事实】`'export.openEditor'` **不在** `PageHandlerName` 联合里（`frontend/src/types/workspace.ts:14-25` 列了 10 个 + `data.refresh`），它靠 `registerPageHandler(name: PageHandlerName | string, …)`（`workspace.ts:268`）的 `| string` 重载通过类型检查。

---

# 5. 失败形字符串清单逐行判定

## ExportWizardView.vue（实读 1134-1241）

| 行号 | 分支条件 | 形态判定 | 字符串首 30 字（原文） |
|---|---|---|---|
| :1139 | `isReadOnly.value`（1138） | **拒绝形** | `任务已结束，仅供查看，不能推进步骤` |
| :1144 | `index < 0 \|\| index >= STEP_KEYS.length`（1143） | **失败形** | `` 步骤 ${payload.step ?? index} 不在导出向导范围内（可选：`` |
| :1154 | `isReadOnly.value`（1153） | **拒绝形** | `任务已结束，仅供查看，未变更所选配置项` |
| :1170 | `isReadOnly.value`（1169） | **拒绝形** | `任务已结束，仅供查看，未设置查询条件` |
| :1174 | `!defCode`（1173） | **失败形** | `缺少 defCode，未设置条件` |
| **:1181** | `taskId.value === null`（1180） | **降级形（成功前缀 + 未落库附注）**，**不是三元** | `` 已设置 ${defCode} 的查询条件：${summary}（当前未绑定任务…`` |
| :1193 | `catch (error)`（1191，`tasksApi.setCondition` 抛错） | **混合形：成功前缀 + 明确失败** | `` 已设置 ${defCode} 的查询条件（${summary}），但落库失败：`` |
| :1201 | `isReadOnly.value`（1200） | **拒绝形** | `任务已结束，仅供查看，未启动导出作业` |
| **:1204** | 三元，条件 `exportJob.value`（1203 `await startExport()` 之后） | **双分支：成功形 / 失败形** ⚠ | 真：`` 导出作业 #${exportJob.value.id} 已启动 ``；假：`导出未启动（请检查配置项选择）` |
| :1210 | `!defCode`（1209） | **失败形** | `缺少 defCode，无法打开在线编辑器` |
| :1236 | `!loaded.ok`（1235，`ensureGridLoaded` 返回失败） | **失败形**（但作为**返回字符串**给出 ⇒ 上游仍 `ok:true`，见第 6 点） | `` ${defCode} 的在线编辑器表格未就绪（任务 #${taskId.value}）：`` |

原文（1179-1204 与 1234-1241）：

```ts
1179:    const summary = conditionSummary(condition)
1180:    if (taskId.value === null) {
1181:      return `已设置 ${defCode} 的查询条件：${summary}（当前未绑定任务，条件只存在于本页表单，推进步骤时会尝试落库）`
1182:    }
...
1191:    } catch (error) {
1192:      const reason = error instanceof Error ? error.message : String(error)
1193:      return `已设置 ${defCode} 的查询条件（${summary}），但落库失败：${reason}。`
1194:        + '请提示用户先在「选择配置」步勾选该配置项，或手动在界面上重设条件。'
...
1203:    await startExport()
1204:    return exportJob.value ? `导出作业 #${exportJob.value.id} 已启动` : '导出未启动（请检查配置项选择）'
...
1234:    const loaded = await ensureGridLoaded(defCode)
1235:    if (!loaded.ok) {
1236:      return `${defCode} 的在线编辑器表格未就绪（任务 #${taskId.value}）：${loaded.error}。`
1237:        + '导出结果步骤与该配置标签已切到前台，但表格位置是加载错误提示，没有可编辑的数据；'
1238:        + '请提示用户核对网络后重试，在此之前不要假设编辑器可用。'
1239:    }
1240:    return `已打开任务 #${taskId.value} 的 ${defCode} 在线编辑器（导出结果表格）`
```

## ImportWizardView.vue（实读 1004-1078）

| 行号 | 分支条件 | 形态判定 | 字符串首 30 字 |
|---|---|---|---|
| :1009 | `isReadOnly.value`（1008） | **拒绝形** | `任务已结束，仅供查看，不能推进步骤` |
| :1014 | `index` 越界（1013） | **失败形** | `` 步骤 ${payload.step ?? index} 不在导入向导范围内（可选：`` |
| :1020 | `!advanced`（1019，`goStep` 未推进） | **失败形（未推进）** | `` 步骤未推进：当前仍在「${stepLabel(...)}」。`` |
| :1030 | `isReadOnly.value`（1029） | **拒绝形** | `任务已结束，仅供查看，未变更所选配置项` |
| :1045 | `isReadOnly.value`（1044） | **拒绝形** | `任务已结束，仅供查看；上传入口在只读页面不可用，本次未执行上传。` |
| :1053 | `isReadOnly.value`（1052） | **拒绝形** | `任务已结束，仅供查看，未启动预检查作业` |
| **:1056** | 三元，条件 `checkJob.value`（1055 `await startJob('check')` 之后） | **双分支：成功形 / 失败形** ⚠ | 真：`` 预检查作业 #${checkJob.value.id} 已启动 ``；假：`预检查未启动` |
| :1062 | `isReadOnly.value`（1061） | **拒绝形** | `任务已结束，仅供查看，未启动导入作业` |
| **:1065** | 三元，条件 `importJob.value`（1064 `await startJob('import')` 之后） | **双分支：成功形 / 失败形** ⚠ | 真：`` 导入作业 #${importJob.value.id} 已启动 ``；假：`导入未启动` |
| :1071 | `isReadOnly.value`（1070） | **拒绝形** | `任务已结束，仅供查看，未启动发布作业` |

```ts
1055:    await startJob('check')
1056:    return checkJob.value ? `预检查作业 #${checkJob.value.id} 已启动` : '预检查未启动'
...
1064:    await startJob('import')
1065:    return importJob.value ? `导入作业 #${importJob.value.id} 已启动` : '导入未启动'
```

## 对草案点名的 3 处「带成功支的三元/降级形」的裁定

- **:1056 / :1065 —— 【确认】** 确为三元，**同一条 `return` 上既可能返回成功形也返回失败形**；判定条件就是 `startJob(...)` 之后 store 里的作业对象是否为真值（`checkJob.value` / `importJob.value`）。这两处是"回灌文本自相矛盾"的真实来源：失败支字符串走上 `runPageHandler:885` 后仍回 `ok:true`。
- **:1181 —— 【需修正】** 它**不是三元**，只在 `taskId.value === null` 时执行、**只返回一种字符串**（成功前缀 + "未落库、仅在本页表单"的降级附注）。真正的"成功/失败二择"在它**下方**（:1190 成功落库 / :1193 落库失败）。
- 草案漏列、但形态同族的还有 **ExportWizardView.vue:1204**（已列在清单中，三元）和 **ImportWizardView.vue:1074-1076**（发布作业三元，草案清单**未列**）：`return publishJob.value ? \`发布作业 #${publishJob.value.id} 已启动；…\` : '发布未启动'`。
- 另有若干**纯成功形**字符串（草案清单未列，属正常）：`ExportWizardView.vue:1147`（`已推进到导出向导步骤「…」`）、`:1164`（`已选择配置项：…`）、`:1190`（`已设置 … 并落库：…`）、`:1240`（`已打开任务 #… 在线编辑器…`）；`ImportWizardView.vue:1024`（`已推进到导入向导步骤「…」`）、`:1038`（`已选择配置项：…`）、`:1047`（`上传文件需要浏览器文件句柄：请提示用户…`）。

---

# 6. `open_export_file_editor` 无显式 executor → defaultExecutor → 卡面「已成功」链路 —— 【确认成立】

- `grep -rn "registerFrontendToolExecutor" frontend/src` 结果：定义在 `frontend-tools.ts:77`，**唯一注册点是 `ExportWizardView.vue:1256`，且只注册 `'download_export_file'`**（注销 `:1290`）。
- 故 `open_export_file_editor` 无显式 executor，`runRegisteredTool:831` `executors.get('open_export_file_editor') ?? defaultExecutor('open_export_file_editor')` 命中 **`defaultExecutor`（982-994）**。
- 链路（逐跳实读）：

| 跳 | 位置 | 实际行为 |
|---|---|---|
| 1 | `frontend-tools.ts:983-990` | `getPageHandler('export.openEditor')` → `await handler(...)` → 返回字符串则原样返回 |
| 2 | `ExportWizardView.vue:1207` 注册的 handler | `:1235 if (!loaded.ok)` → **`:1236` 返回失败形字符串**（`…在线编辑器表格未就绪…`） |
| 3 | `frontend-tools.ts:841-842` | `const result = await executor(...)` → `return { ok: true, availability: 'available', result }` |
| 4 | `stores/ai.ts:1804` | `status = execution.ok ? 'succeeded' : 'failed'` → **`'succeeded'`** |
| 5 | `AiPanel.vue:567` | `succeeded: '已成功'` |

- ExportWizardView.vue:1236 handler 文案（全文）：

```ts
1236:      return `${defCode} 的在线编辑器表格未就绪（任务 #${taskId.value}）：${loaded.error}。`
1237:        + '导出结果步骤与该配置标签已切到前台，但表格位置是加载错误提示，没有可编辑的数据；'
1238:        + '请提示用户核对网络后重试，在此之前不要假设编辑器可用。'
```

- **结论**：`open_export_file_editor` 经**工具名路径**进来、页内引擎加载失败时，回灌文本如实说"未就绪"，但 `runPageHandler` 已于 :885 把它包成 `ok:true` → 卡面仍显示「已成功」（`AiPanel.vue:567`）。**"工具名路径进来时卡面仍显示已成功"这条链路成立** ✓。
  （对照：同页 `download_export_file` 走的是**显式 executor**（`:1256-1277`），路径不同；`open_export_file_editor` 的 `ensureGridLoaded` 失败若想落 `failed`，唯一出口是 handler **抛错**（如 :1215 / :1218 那样），而 :1236 选择的是 `return`。）

---

# 7. D4(b) 依赖：`dispatchUiEvent` 三支 —— 【需修正（函数名/区间）】+ 语义【部分确认】

**区间需修正**：`dispatchUiEvent` 本体在 **`frontend/src/stores/workspace.ts:368-374`**（草案写 :392-427）：

```ts
368: export async function dispatchUiEvent(event: UiEvent): Promise<UiEventResult> {
369:   const result = await runUiEvent(event)
370:   const workspace = useWorkspaceStore()
371:   workspace.touch(result.message, 'AI')
372:   emitWorkspaceEvent('ui_event_result', { event: event.type, ...result })
373:   return result
374: }
```

草案 :392-427 实际对应 **`runUiEvent`（376-526）内的三支**，逐支实读：

```ts
392:      case 'goto_step': {
393:        const taskId = workspace.taskId
394:        if (taskId === null) {
395:          return fail('当前没有进行中的任务，无法跳转步骤')
396:        }
397:        await tasksApi.goToStep(taskId, event.step)
398:        workspace.setStep(event.step)
399:        await taskStore.refreshCurrent()
400:        return ok(`已跳转到步骤 ${event.step}`)
401:      }
402:      case 'select_defs': {
403:        const taskId = workspace.taskId
404:        if (taskId === null) {
405:          return fail('当前没有进行中的任务，无法选择配置项')
406:        }
407:        // 收口修 F6（红队 minor-4）：只读态守卫，与页面 handler 同口径
408:        if (await isCurrentTaskReadOnly(workspace)) {
409:          return fail('任务已结束，仅供查看，未变更所选配置项')
410:        }
411:        await tasksApi.selectDefs(taskId, event.defCodes)
412:        workspace.setSelectedDefs(event.defCodes)
413:        await taskStore.refreshCurrent()
414:        return ok(`已选择配置项：${event.defCodes.join('、') || '（空）'}`)
415:      }
416:      case 'set_conditions': {
417:        const taskId = workspace.taskId
418:        if (taskId === null) {
419:          return fail('当前没有进行中的任务，无法设置查询条件')
420:        }
421:        // 收口修 F6（红队 minor-4）：只读态守卫，与页面 handler 同口径
422:        if (await isCurrentTaskReadOnly(workspace)) {
423:          return fail('任务已结束，仅供查看，未设置查询条件')
424:        }
425:        await tasksApi.setCondition(taskId, event.defCode, event.conditions)
426:        workspace.setQueryConditions(event.defCode, event.conditions)
427:        return ok(`已设置 ${event.defCode} 的查询条件`)
428:      }
```

| 判据 | 实读结论 |
|---|---|
| 三支均为 `await` 的服务端写入 | **确认**：`:397 await tasksApi.goToStep`、`:411 await tasksApi.selectDefs`、`:425 await tasksApi.setCondition`（`select_defs`/`set_conditions` 另各带一次 `await taskStore.refreshCurrent()`；`set_conditions` 无 refresh）。`ok()`/`fail()` 定义在 329-335（`{handled:true,message}` / `{handled:false,message}`）。 |
| `handled:true` ⇒ 副作用已落地 | **确认（限定口径）**：`ok(...)` 只在上述 `await` **正常返回之后**才被调用（异常由 523-525 的 `catch` 统一翻成 `fail(...)`），故 `handled:true` 对这三支意味着"服务端写入的 HTTP 调用已成功返回 + 本地 store 已同步"。注意 `handled:true` 在**纯 UI 支**（`open_page` 384、`refresh_defs` 504、`refresh_data` 510、`refresh_tasks` 516）并不带服务端写语义 —— 其中 `refresh_defs`/`refresh_data` 的 `ok(...)` 文案自认是"已记录刷新意图…数据不会因此变化"（空回执）。 |
| 只读态是否走 `fail`→`ok:false` | **部分/需修正**：`select_defs`（`:408-410`）与 `set_conditions`（`:422-424`）有 `isCurrentTaskReadOnly` 守卫 → `fail`；**`goto_step`（392-401）没有只读守卫**，只判 `taskId === null` ⇒ 只读任务上经契约层 `goto_step` 会照常 `await tasksApi.goToStep`。页面 handler 侧（`ExportWizardView.vue:1137-1139` / `ImportWizardView.vue:1007-1009`）**有**只读守卫，两条路口径不一致。 |
| 附加事实（D1 相关） | `dispatchUiEvent:371` 对 **`fail` 也执行** `workspace.touch(result.message, 'AI')` —— 失败的 ui_event 同样会进 `recentActions` 并广播 `ui_event_result`。另外 `UiEventResult`（`types/workspace.ts:71-75`）**只有 `handled:boolean` + `message:string` 两个字段**，契约层无法表达"已受理但未落地/结局未知"这第三态 —— 这正是 `runWorkspaceAction:767-772` 只能把它硬编码成 `ok:true, available` 的结构性原因。 |

---

## 附：本子智能体实际运行模型名

**无法从运行环境自证。** 实读环境变量（`env | grep -i "kimi|model|anthropic|openai"`）仅有 `KIMI_CODE_REGION_MARKER=off` 与 `OLDPWD=<DRIVE>/Program Files/Kimi Code`，**无任何模型标识变量**；我的系统提示词自称 "Kimi Code"（Kimi Code 桌面端 agent），但提示词不构成对环境事实的自证，故我不给出具体模型版本号。若该行必须填实，请由调用方从运行侧（客户端配置/服务端路由）确认。
