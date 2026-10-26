> **历史分析 / 过程证据 — 非现行基线，非验收结论**
>
> - 归档：2026-10-26 · `docs/evidence/history-recovery-2026-10-26/wire-tail/`
> - 来源：Kimi Code 会话 `<KIMI_SESSION>` / 子 agent `agent-486` 的 wire 末条 assistant 文本（消息序号 m=9；只读流式解析，非原始会话转写）
> - 历史时点：2026-10-11 22:27 → 2026-10-11 22:29（wire 事件时间窗，UTC+8）；当时模型：`DeepSeek/deepseek-flash`
> - 原基线：`3edaeb9`（正文自动摘取的首次基线提及），未经本批复验
> - 原始体量：17319 B / 12273 字符 / 270 行；行指纹覆盖率（本批复算）1.7%（最高单文件 1%：`docs/evidence/history-recovery-2026-10-26/wire-only/entries/072-落卡状态-+-帧序竞态-只读核验报告-7f217e6f-agent-490.md`）
> - 类别：**过程核验**
> - 与既有件关系：072-entry；报告口径覆盖率 0.01
> - 脱敏：路径 0 / 会话标识 0 / 身份 0 / 密钥 0 / 命名纪律 0
> - 正文为该条消息的完整文本，未删段、未改写结论；仅脱敏、占位符归一与行尾空白规整。当时结论不代表当前代码状态，不得作为当前缺陷或有效裁决引用。
核查完成。以下按 1-16 逐条给出结论，所有代码原文均为实读所得（文件：`frontend/src/...`，HEAD=3edaeb9）。

---

### 1. `types/tools.ts:115-120` — FrontendToolExecution 无 outcome 字段
**结论：属实【实测】**

```ts
115  export interface FrontendToolExecution {
116    ok: boolean
117    availability: ToolAvailability
118    /** 回灌给模型的说明文本（POST /api/ai/frontend-tool-result 的 result） */
119    result: string
120  }
```
全仓 `FrontendToolExecution` 定义点仅此一处（`utils/frontend-tools.ts:48/302/315/352/411/439/699/811/853/880` 全是引用），接口内**无任何 `outcome` 字段**。

### 2. `types/tools.ts:84-95` — open_export_file_editor 无 executor 字段
**结论：属实【实测】**（实际区间 `83-94`，`name` 在 `:84`）

```ts
84      name: 'open_export_file_editor',
85      displayName: '打开导出文件在线编辑器',
86      description: '请求前端打开指定配置的导出文件在线编辑器（SpreadJS）',
87      channel: 'FRONTEND',
88      riskLevel: 'READ',
89      scopePatterns: ['task:EXPORT/EXPORT'],
90      args: [
91        { name: 'taskId', type: 'number', required: true, description: '任务ID' },
92        { name: 'defCode', type: 'string', required: true, description: '配置定义编码' }
93      ]
```
`FrontendToolDescriptor`（`:67-76`）字段集为 name/displayName/description/channel/riskLevel/scopePatterns/args，**无 executor** ⇒ 走 `defaultExecutor`（见第 8 条）。

### 3. `types/tools.ts:118` result 注释是否写明"同时给模型和人看"
**结论：不属实【实测】**

`:118` 原文仅 `/** 回灌给模型的说明文本（POST /api/ai/frontend-tool-result 的 result） */`，接口说明 `:114` 为 `/** 前端工具的执行结果（回灌给后端的文本 + 本地痕迹）。 */` —— 两处都**只提"给模型"**，无"人看/展示"字样。全文件唯一"同时给模型和人"口径在 `:184`，且属另一接口：

```ts
184    /** 中文动作摘要（展示文案，同时也是给模型看的一句话） */
185    summary: string
```
（即 `WorkspaceActionRecord.summary`，不是 `FrontendToolExecution.result`；且措辞也是"给模型看"，非"给人看"。）

### 4. `utils/frontend-tools.ts:69` 执行器签名
**结论：属实【实测】**（逐字一致）

```ts
68  /** 执行器签名：返回回灌给模型的结果文本。 */
69  export type FrontendToolExecutor = (call: FrontendToolCall) => Promise<string> | string
```

### 5. `utils/frontend-tools.ts:411-427` timeoutOutcome
**结论：属实【实测】**

```ts
420        + '请让用户先到下载目录确认文件是否已落盘，再决定是否需要重试；不要直接重复触发下载。'
421        + '本次调用结局未知 —— 既未确认成功，也未确认失败。'
422      : `${label}（${call.name}）前端超时：等待 ${waitedSeconds} 秒仍未返回执行结果（执行器预算 ${budgetSeconds} 秒）。`
423        + '本次调用结局未知 —— 既未确认成功，也未确认失败。'
424        + '请提示用户核对界面上的实际状态（相关卡片、列表或表格，以及数据是否已变化），确认后再决定是否重试。'
425    return { ok: false, availability: 'degraded', result }
```
`:425` 逐字命中；「本次调用结局未知」在 `:421`（下载类支）与 `:423`（其余工具支）各一次。

### 6. `utils/frontend-tools.ts:875-895` runPageHandler
**结论：部分属实（成功支区间起点差 1 行，其余逐字命中）【实测】**

```ts
882    try {
883      const outcome = await handler(args)
884      return {
885        ok: true,
886        availability: 'available',
887        result: typeof outcome === 'string' ? outcome : `${label}已执行`
888      }
889    } catch (error) {
890      return {
891        ok: false,
892        availability: 'degraded',
893        result: `${label}执行失败：${error instanceof Error ? error.message : String(error)}。请提示用户手动重试。`
```
`:885 ok: true` ✓、`:887` 原样三元 ✓；成功支实为 `884-888`（`:883` 是 `const outcome`），草案写 `:883-887` 属界标偏移。catch 落在 `:889-894`，返回 `ok:false + degraded`（**不是** throw）。

### 7. `utils/frontend-tools.ts:808-850` runRegisteredTool
**结论：属实【实测】**

```ts
840    try {
841      const result = await executor({ ...call, args: validated.coerced })
842      return { ok: true, availability: 'available', result }
843    } catch (error) {
844      return {
845        ok: false,
846        availability: 'degraded',
847        result: `${descriptor.displayName}执行失败：${error instanceof Error ? error.message : String(error)}。请提示用户手动重试。`
848      }
```
`:842` 逐字命中；`:844-849` 返回 `ok:false + degraded` ✓。

### 8. `utils/frontend-tools.ts:982-994` defaultExecutor
**结论：属实【实测】**

```ts
982  function defaultExecutor(name: FrontendToolName): FrontendToolExecutor | null {
983    if (name === 'open_export_file_editor') {
...
989        const outcome = await handler({ taskId: call.args.taskId, defCode: call.args.defCode })
990        return typeof outcome === 'string' ? outcome : `已请求打开 ${String(call.args.defCode)} 的在线编辑器`
991      }
992    }
993    return null
```
`:989`/`:990` 逐字命中。它**只判类型不判语义**：handler 返回任何字符串（含"缺少 defCode，无法打开在线编辑器"这类失败形）都原样 `return`，再被 `:842` 无条件包成 `ok:true/available`。仅覆盖 `open_export_file_editor`，其余名字返回 `null`（⇒ `:832-838` degraded 支）。

### 9. `utils/frontend-tools.ts:739-782` 工作区动作表
**结论：属实（三处 ok:true 行号全对）【实测】**，逐支如下：

- 常量/函数：`:517 export const PAGE_READY_TIMEOUT_MS = 3_000`（**精确命中**）；`waitForPageReady` 定义 `:529`、闭合 `:544`（草案写 `:529-546`，`:545-546` 已是下一段 `TaskBindWaitResult` 的文档注释，属近似）。
- `:750 if (wait.pageReady && wait.taskBound)` → 页面已就绪**且**任务已绑定：
```ts
751        const bound = expectedTaskId === null ? '' : `，任务 #${expectedTaskId} 已绑定`
752        return {
753          ok: true,
754          availability: 'available',
755          result: `${outcome.message}（页面已就绪${bound}，可继续执行本页动作）`
```
- `:758 if (wait.pageReady)` → 已就绪未绑定：
```ts
760        return {
761          ok: false,
762          availability: 'degraded',
763          result: `${outcome.message}，但任务 #${expectedTaskId} 的绑定没有完成（向导页已就绪，未绑定该任务）。`
764            + `请核对任务 #${expectedTaskId} 是否存在，或提示用户手动在任务中心打开该任务后重试。`
```
- `:767-772` → 只有 `pageReady === false` 才走到（`waitForTaskBound` 在 `:581-582` 超时才回 `pageReady:false`），返回**ok:true**：
```ts
767      return {
768        ok: true,
769        availability: 'available',
770        result: `${outcome.message}（页面${expectedTaskId === null ? '' : '与任务绑定'}仍在加载中；`
771          + '若要继续在该页动作，请稍后重试或提示用户手动操作）'
```
- `:774` → 无导航目标（`:743 target !== null` 为假）时回灌契约层文案：
```ts
774    return { ok: true, availability: 'available', result: outcome.message }
```
- 另附契约层拒绝支 `:777-781`（`ok:false + degraded`）。

### 10. `utils/frontend-tools.ts:315-321` executeFrontendTool / runWithBudget
**结论：属实（"三分支"措辞需澄清）【实测】**

```ts
315  export async function executeFrontendTool(call: FrontendToolCall): Promise<FrontendToolExecution> {
316    const budgetMs = normalizeBudgetMs(call.budgetMs)
317    const startedAt = Date.now()
318    const outcome = await runWithBudget(dispatchFrontendTool(call), budgetMs, startedAt)
319    return outcome.kind === 'timeout'
320      ? timeoutOutcome(call, budgetMs, outcome.elapsedMs)
321      : outcome.execution
```
它是**唯一对外入口**并返回 `FrontendToolExecution`（唯一调用方：`stores/ai.ts:1798`）。实为**二元**三元表达式；"三"来自 `BudgetOutcome` 判别联合（`:301-303`）。`runWithBudget` 起始行确为 `:351`（`async function runWithBudget(`），其结算对象除 `execution`（内含 ok/availability/result）外还有判别字段 `kind: 'settled' | 'timeout'`，timeout 支另有 `elapsedMs`：

```ts
301  type BudgetOutcome =
302    | { kind: 'settled'; execution: FrontendToolExecution }
303    | { kind: 'timeout'; elapsedMs: number }
```

### 11. `utils/frontend-tools.ts:864` 的 `ok: outcome.handled` 读点 + 全文件 `.ok`/`ok:` 清单
**结论：属实【实测】**

```ts
862    try {
863      const outcome = await dispatchUiEvent(event)
864      return { ok: outcome.handled, availability: 'available', result: outcome.message }
```
- `.ok` **读点（本文件内）仅 2 处**：`:813 if (!validated.ok)`（参数校验）、`:864` 的 `outcome.handled` 直通（D1 理由③ 引用点，唯一一处"契约层 handled 直通为 ok"）。
- `ok:` **生产点行号全集**：`119, 125, 132, 138, 141, 425, 459, 704, 711, 718, 734, 753, 761, 768, 774, 778, 815, 825, 834, 842, 845, 857, 864, 867, 885, 891`。
- `.handled` 读点：`:740`、`:864`。

### 12. `utils/frontend-tools.ts:400-410` 下载超时文案注释
**结论：属实【实测】**

```ts
407   * **已知过度保守（登记不修）**：即发即回后，该工具的超时只可能落在**前置校验**期间（尚未发起
408   * 下载），故下方下载类文案的"文件也许已落盘、请勿重复触发"偏保守 —— 方向安全（不谎报、
409   * 不诱导重复触发），是否收窄留待卡片态设计（R-84）时一并裁决。
```
「登记不修」`:407`、「留待卡片态设计（R-84）」`:409` 均在 `:400-410` 区间内。

### 13. `ExportWizardView.vue` 注册点与失败形 return
**结论：部分属实【实测】**

注册点 **5 处，行号逐字命中**：`:1136 export.nextStep`、`:1150 export.selectDefs`、`:1167 export.setConditions`、`:1198 export.start`、`:1207 PAGE_HANDLER_OPEN_EXPORT_EDITOR`；显式执行器在 `:1256 registerFrontendToolExecutor('download_export_file', async (call) => {`（函数名是 `registerFrontendToolExecutor`，草案简称"registerTool"；另 `:1290` 注销）。

区间 `1136-1240` 共 15 个 `return`，非纯成功形 **11 个，行号全对**：
- `:1139` `'任务已结束，仅供查看，不能推进步骤'`
- `:1144` `` `步骤 ${payload.step ?? index} 不在导出向导范围内（可选：…）` ``
- `:1154` `'任务已结束，仅供查看，未变更所选配置项'`
- `:1170` `'任务已结束，仅供查看，未设置查询条件'`
- `:1174` `'缺少 defCode，未设置条件'`
- `:1181` `` `已设置 ${defCode} 的查询条件：${summary}（当前未绑定任务，条件只存在于本页表单，推进步骤时会尝试落库）` ``
- `:1193` `` `已设置 ${defCode} 的查询条件（${summary}），但落库失败：${reason}。` ``
- `:1201` `'任务已结束，仅供查看，未启动导出作业'`
- `:1204` `` `导出作业 #${exportJob.value.id} 已启动` : '导出未启动（请检查配置项选择）' ``
- `:1210` `'缺少 defCode，无法打开在线编辑器'`
- `:1236` `` `${defCode} 的在线编辑器表格未就绪（任务 #${taskId.value}）：${loaded.error}。` ``

**但 `:1181` 不是"带成功支的三元形"**：它是**单一模板串 return（无三元）**，主句为成功语义 `已设置 …的查询条件：${summary}`、括注降级。真正的三元形是 `:1204`（成功支 `导出作业 #N 已启动` / 失败支 `导出未启动（请检查配置项选择）`）。⇒ 该小断言**不属实**（分类口径问题，计数不受影响）。

### 14. `ImportWizardView.vue` 注册点与失败形 return
**结论：部分属实（10 处少计 1 处）【实测】**

注册点 **6 处，行号逐字命中**：`:1006 import.nextStep`、`:1027 import.selectDefs`、`:1041 import.upload`、`:1050 import.startPrecheck`、`:1059 import.startImport`、`:1068 import.startPublish`（`:1090` 统一注销）。

列出的 10 处行号与文案全对：`:1009`「任务已结束，仅供查看，不能推进步骤」、`:1014`「步骤 …不在导入向导范围内」、`:1020`「步骤未推进：当前仍在「…」」、`:1030`「任务已结束，仅供查看，未变更所选配置项」、`:1045`「任务已结束，仅供查看；上传入口在只读页面不可用…」、`:1053`「…未启动预检查作业」、`:1056`（三元）、`:1062`「…未启动导入作业」、`:1065`（三元）、`:1071`「…未启动发布作业」。
- `:1056`/`:1065` **确是带成功支的三元**：`return checkJob.value ? \`预检查作业 #${checkJob.value.id} 已启动\` : '预检查未启动'`；`:1065` 同形 `: '导入未启动'`。
- **少计 1 处**：`:1074-1076` 与上述同形（带成功支三元 + 失败支），却未被列入 ⇒ 区间 `1006-1078` 的失败形 return 实为 **11 处**，不是 10 处：
```ts
1074    return publishJob.value
1075      ? `发布作业 #${publishJob.value.id} 已启动；发布结果按异步形态给出（作业终态 + 问题清单）`
1076      : '发布未启动'
```
（另注：`:1047`「上传文件需要浏览器文件句柄：请提示用户点击…」是降级/指引形 return，若按"非成功形"口径也应计入。）

### 15. `ExportWizardView.vue:1207-1240` ensureGridLoaded 与 throw
**结论：部分属实【实测】**

`ensureGridLoaded` 已结构化（定义在 `:776`，不在 1207-1240 内）：
```ts
776  async function ensureGridLoaded(code: string): Promise<{ ok: boolean; error: string }> {
778      return { ok: false, error: '缺少配置编码' }
781      return { ok: true, error: '' }
788      return { ok: false, error: `${code} 的结果表格组件未挂载（请重开该标签页重试）` }
802      return { ok: false, error: reason }
```
- `:1215` throw ✓ `throw new Error(\`${gate.message}。${defCode} 的在线编辑器未打开\`)`
- `:1218` throw ✓ `throw new Error(\`${noResultMessage(defCode)}。请在任务 #… 上完成…导出后重试，…本次未打开在线编辑器\`)`
- `:1259` throw ✓ `throw new Error(preflight.message)` —— **但它不在 `ensureGridLoaded` 内，也不在 1207-1240 区间**，而是 `registerFrontendToolExecutor('download_export_file', …)`（`:1256-1260`）的前置校验失败支；草案把三个 throw 并列于同一语义组，属**归位不准**。
- `:1236` 文案 ✓（`:1236-1238`）：`` `${defCode} 的在线编辑器表格未就绪（任务 #${taskId.value}）：${loaded.error}。` `` + `…请提示用户核对网络后重试，在此之前不要假设编辑器可用。`

### 16. `execution.ok` / `execution.availability` / `.outcome` 消费点（frontend/src）
**结论：清单如下【实测】**

`FrontendToolExecution` 字段的真实消费点**只有一处模块**（`stores/ai.ts`）：
```ts
1798      const execution = await executeFrontendTool({...})
1804      const status: ToolRunStatus = execution.ok ? 'succeeded' : 'failed'
1811        result: execution.result,
1815      if (execution.availability === 'degraded' || execution.availability === 'unavailable') {
1816        this.notice = execution.result
1818      await this.submitFrontendToolResult(frame.toolCallId, execution.result, 'frontend-executor')
```
即：`execution.ok` → `:1804`；`execution.result` → `:1811/:1816/:1818`；`execution.availability` → `:1815`。**前端工具模块内部无任何消费点**（本文件只生产结局）。

其余 `.outcome` / `.ok` 读点属于**其他类型**，不可与 `FrontendToolExecution` 混计：
- `utils/frontend-tools.ts:319-321`（`BudgetOutcome.kind/elapsedMs/execution`）、`:740/:864`（`dispatchUiEvent` 的 `UiEventResult.handled/message`）
- `stores/workspace.ts:464` `return outcome.handled ? ok(outcome.message) : fail(outcome.message)`（`UiEventResult`，`ok/fail` 见 `:329-335`）
- `stores/ai.ts:1278/1282/1285/1290/1311/1312`（生成式表单提交/取消回灌结果，另一类型）
- `views/TaskCenterView.vue:290-293`（`outcome.handled/message`）
- `views/ExportWizardView.vue:1039` `outcome.taskOk`、`:1046/:1065` `outcome.filesOk`（下载前置/文件就绪结局）、`:1114/:1214` `gate.ok`、`:1235` `loaded.ok`、`:1258` `preflight.ok`
- 其他 `\.ok`（`api/sse.ts:358`、`stores/workspace.ts:485` 的 `response.ok`；`AiPanel.vue:300/302` 的 `copyState.ok`；`utils/parts.ts:285/364` 的 `frame.ok`）

---

**需回改草案的 4 处**：③（`:118` 不写"给人看"）、⑥（成功支起行 `884` 非 `883`）、⑬（`:1181` 非三元形，三元形是 `:1204`）、⑭（失败形 return 实为 11 处，漏 `:1074-1076`）；另 ⑮（`:1259` 属 download 执行器而非 `ensureGridLoaded`）、10（"三分支"应为二分三元 + 判别联合）建议改述。

子智能体自报模型：DeepSeek V4 Flash（model=DeepSeek/deepseek-flash 钉定）
