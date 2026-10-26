> **历史分析 / 过程证据 — 非现行基线，非验收结论**
>
> - 归档：2026-10-26 · `docs/evidence/history-recovery-2026-10-26/wire-only/`
> - 来源：Kimi Code 会话 `<KIMI_SESSION>` / 子 agent `agent-497` 的 wire 末条 assistant 文本（只读流式解析；非原始会话转写）
> - 原始体量：13120 B / 8374 字符 / 67 行；行指纹覆盖率（仓内 + 落盘语料）8.9%
> - 类别：**施工复核**
> - 脱敏：路径 0 / 会话标识 0 / 身份 0 / 密钥 0
> - 正文为该条消息的完整文本，未删段、未改写结论；仅脱敏与行尾空白规整。当时结论不代表当前代码状态。
## 复核结论（fresh reviewer，只读、零 git 写、未改任何文件）

**已核对的证据基线**：工作树 `git diff` 与所给 `final-review.diff` **逐字节一致**（907/907 行 diff 文本相同）；实跑 `npx vue-tsc --noEmit` → **0 错**；实跑 `npx vitest run` → **11 文件 / 110 用例全绿**。未做 `build`（会写 dist）。RED 侧无法执行（不得改文件/写 git），改为**逐条对照 `git show HEAD:` 的旧代码**验证断言是否真锁新行为（结论见下）。

---

## Critical

**未见 Critical。** 未发现可见回归、崩溃，或新增的"结局谎报"生产链。

---

## Important

### I1. `export.nextStep`（W-06 撒谎点本体）的失败回灌仍是字符串 ⇒ 卡面「已成功」与卡内文本「步骤未推进」自相矛盾
- **文件/位置**：`frontend/src/views/ExportWizardView.vue:1192-1201`（新失败支返回 `步骤未推进：…` 字符串），对照同条件另一入口 `:1281-1285`（返回结构化 `{ok:false, reason}`）。
- **问题**：`runPageHandler` 的 `interpretHandlerOutcome`（`frontend/src/utils/frontend-tools.ts:970-989`）对字符串按 **D3(iii) 兼容支 ⇒ outcome 'ok'**，于是 `stores/ai.ts:1824-1826` 把该段落成 `succeeded`，AiPanel 状态 tag 显示「已成功」（`AiPanel.vue:566-581`），而同一张卡的正文写「步骤未推进」。**同一个底层事件（goStep 被 persist 阻断）在 `export.openEditor` 支落 `failed`、在 `export.nextStep` 支落 `succeeded`，同文件内不自洽。**
- **触发场景**：`confirm_step` 动作经 `runWorkspaceAction`（handlerNames `['export.nextStep','import.nextStep']`）→ handler → `goStep` 内 `persistStepData`（`selectDefs`/`setCondition`）任一失败（断网、5xx、R-137 类守卫 4xx）。
- **与规格的关系**：卡 D3(iii) 对**存量登记字符串**授权"字符串⇒ok"，但 `:1194` 是**本批新写的**失败支（不在 E19 登记的 21 处内），报告 §7-3 只覆盖了"登记的既有面"。T7① 也只断言文本，不锁 `ok/outcome`，故该矛盾在测试面上不可见。
- **建议修法（一行，测试仍绿）**：把该支改为 `return { ok: false, reason: <同文案> }`。T7① 断言 `result` 含「未推进」「selectDefs 注入失败」——`interpretHandlerOutcome` 会把 `reason` 同时写进 `result`，断言不受影响；`syncWarning` 支（步骤确实推进）保持字符串成功。

### I2. T7③ 的「不发起下载」断言是**空断言**（测试假绿的局部）
- **文件/位置**：`frontend/tests/export-wizard-step-outcome.spec.ts:146`（`expect(api.saveBlob).not.toHaveBeenCalled()`），mock 在 `:65-68`。
- **问题**：`ExportWizardView` **不 import** `@/api/http#saveBlob`，其下载走 `@/utils/excel#downloadBlob`（`ExportWizardView.vue:932-948` → `utils/excel.ts:838`）。因此该断言**在修复前的旧实现下同样通过**，无法证"未触发下载"。
- **触发场景**：任何一次运行；属"证据虚高"（测试注释与报告 §3 把它当作下载未发起的证据）。
- **建议修法**：改为 spy `@/utils/excel` 的 `downloadBlob`（`vi.mock('@/utils/excel', …)` 保留其余导出）或 spy `URL.createObjectURL`；或直接断言 `downloading.value` 未置位。核心断言（`result` 含「无法切换」+ `ok===false`）本身有效，故用例不是全假绿。

---

## Minor

| # | 文件/位置 | 问题 | 建议 |
|---|---|---|---|
| M1 | `frontend/src/utils/parts.ts:262` | 模块级注释仍写「本地已终态（**failed/rejected/blocked/succeeded/expired/cancelled**）first-wins」，未补 `unknown`（case 内注释 `:377-386` 已补）。属"注释描述旧行为"漏扫一处 | 集合补 `unknown`（与卡 D5「注释补含 unknown」对齐） |
| M2 | `frontend/src/stores/ai.ts:242-243` | 函数文档「仍在等动作的工具段**全部过期降级**」与实现（`非 pending 保持原终态`，`:251`）已不符 | 改述为「pending→expired；非 pending 只收 requiresAction」 |
| M3 | `frontend/src/components/AiPanel/AiPanel.vue:537-564` | 去 `default` 后，运行期越界 `status`（坏镜像/未来后端值）会返回 `undefined` 而非 `'primary'`；el-tag 视觉等价，但失去显式回退 | 可接受（穷尽闸是目的）；如需防御可加 `satisfies`/穷尽断言注释说明 |
| M4 | `frontend/src/utils/frontend-tools.ts:100-117, 113` | `reason` 字段**全仓零读者**（只在 `interpretHandlerOutcome` 里被复制进 `result`；`stores/ai.ts:1828-1835` 未把 `execution.reason` 落到段 `reason`，AiPanel 的「原因：」位因此永远空）。另 `makeExecution` 不滤空串 `reason`（`''` 会产键） | 或把 reason 落段（可免费获得 AiPanel 的红色「原因」行），或在报告中申报"reason 为预留字段" |
| M5 | `frontend/src/utils/frontend-tools.ts:977-987` | 结构化解读只认 `ok === false`：`{reason:'x'}`（无 `ok`）与 `{ok:'false'}` 会静默落**成功兜底**。当前无生产者，属防未来写点 | 可加「有 `reason` 无 `ok` 且非空 ⇒ failed」或注释显式声明契约 |
| M6 | `frontend/src/views/ImportWizardView.vue:729-732, 1016-1035` | D9 只把同步失败 warning 给 `ElMessage`，**回灌文本仍是干净的「已推进到…」**（导出侧 D7 明确要求文本附警告）——模型侧口径与导出不对称 | 若采纳与导出同口径，可在 `goStep` 返回结局给 `import.nextStep`（但会改 goStep boolean 签名，超 D9「最小接入」范围）；否则登记为口径差异 |
| M7 | `frontend/src/views/ExportWizardView.vue:708` | `startExport` 仍 `await persistStepData()` **丢弃结构化结局**后照旧 `createJob`——正是卡 A11 危害模型点名的 `start_export` 读服务端 `conditionJson`。**既有面，非本批引入**；可达性弱（`start_export` 是 BACKEND 通道工具，`export.start` handler 名与 AI 工具名不匹配，实际只有用户点「开始导出」按钮且存在未落库的界面条件时才触发，用户当场可见 `ElMessage.error`） | 本批已把 `persistStepData` 改成结构化返回，顺手阻断（3 行）或**新增登记项**（报告未提该处） |
| M8 | `ExportWizardView.vue:14` / `:561-583` | goStep 改为「先 persist 后切步」后：① 双击「下一步」不再跳步（旧行为会跳两步）、双击「上一步」可能只退**一步**（模板实参 `stepIndex-1` 在两次点击时同值）；② persist 在途时 prev/next 未禁用，可能并发两次 persist（多写一轮 selectDefs/setCondition） | 无崩溃/无 unhandled rejection（goStep 各支都自吞异常）；如需严格可加 in-flight 抑制 |
| M9 | `frontend/src/utils/frontend-tools.ts:917-925` | 路由 3 `handled:false → 'failed'`（D1 点名）**无直接单测**（T4 走的是动作表支）；因旧写法 `ok: outcome.handled` 与本写法语义**可证等价**，当前无红 | 建议补一条 `expect(outcome).toBe('failed')` 的 ui_event 负例，把 D1 点名点锁死 |

---

## 明确「未见问题」的复核面

1. **21 处生产点全部收口（无漏网字面量）**：`frontend-tools.ts` 现仅剩 `validateArgs` 的 5 处 `{ok:true;coerced}|{ok:false;reason}` 判别联合（非同型），`FrontendToolExecution` 类型只在 `types/tools.ts:132` 声明、仅由 `makeExecution` 生产；其余 `ok: true` 字面量（`ExportWizardView` 的 StepAdvanceOutcome/ensureGridLoaded/preflight 等）都是别的形状。23 个 `makeExecution` 调用点覆盖全部返回支。
2. **`interpretHandlerOutcome` / `normalizeExecutorReturn` 分支完备**：`null`/`undefined`/数组/数字/布尔/无 `ok` 对象 → 均落「`<label>已执行` + ok」，不抛；`{ok:true}` 无 `result` → 兜底文案（与旧一致）；`reason` 空串/非串 → 回落「`<label>执行失败`」。`normalizeExecutorReturn` 对 `null/undefined` 返回值的属性访问异常会被 `runRegisteredTool` 的 `catch`（`:881-887`）接住落 failed，不逃逸。
3. **`:922` handled:false 语义未变**：旧写 `ok: outcome.handled` 与新 `outcome: handled?'ok':'failed'`（`ok` 由工厂推导）在唯一消费点 `stores/ai.ts:1824` 上是**逐值等价**；`availability:'available'` 亦未动，故 notice 行为不变。既有调用方（`dispatchUiEvent` → `runUiEvent` 的 fail 支）无被改变的风险。
4. **`parts.ts` 三元兜底 `existing?.status ?? 'failed'`（`:388-389`）无新风险**：`localTerminal` 全由 `existing?.status === X` 构成，为真 ⇒ `existing` 必存在且 status 必为七终态之一 ⇒ `?? 'failed'` 是不可达兜底；纳入 `unknown` 未改变该性质。
5. **X1 门禁不误伤合法 pending**：`:200` 只在 `part.status === 'unknown'` 时跳过挂起投影并 `console.error`；`pending + requiresAction=true` 照常产挂起卡（`parts-residue-cleanup` 21 例 + `parts-reducer` 全绿为证）。违例段时 `pending` 会退回到更早的合法段（仅影响违例数据）。
6. **`applyPendingTerminal` 排除 unknown/failed 不影响 GF 正常收敛**：唯一生产调用方是 `cancelGenerativeForm`（`ai.ts:1318`，传 `'cancelled'`），GF 段此刻恒为 `pending`（未终态）⇒ 不触排除；`approved→running` 映射仅测试调用。`failed`+`requiresAction:true` 这一被排除形态在现有写点不可产出（提交失败回退置的是 `pending`，`openGenerativeForm` 的 schema 失败支是 `failed`+`requiresAction:false`）。
7. **`expireResidueToolParts` 与 `:233` 对齐正确**：`pending` 段仍落 `expired`；非 pending 残留保持原终态，顺带修掉了「succeeded+requiresAction 被翻成 expired」的老谎报（`suspended` 帧可在已终态段上补 `requiresAction`，`parts.ts:402-414`）。
8. **A8 三态映射无 `undefined` 逃逸**：`normalizeExecutorReturn` 恒给 `outcome`（`value.outcome ?? (value.ok?'ok':'failed')`），任何非 `'ok'`/`'unknown'` 值（含 `undefined`、非法串）一律落 `failed`；无 `undefined` status 落到段上。
9. **A2 → A8 → 回执帧 三环闭合**：`timeoutOutcome:459`（unknown+degraded，文案未动）→ `ai.ts:1824-1841`（落 `unknown`、`requiresAction:false`、degraded notice、POST 发回灌）→ `applyFrameToParts` 的 `localTerminal`（`:383-390`）first-wins。后端侧 `tool_result` 帧不会写给 FRONTEND 段的 unknown（`tool_result` 只发 BLOCKED 越 scope / BACKEND 认领路径，均发生在发起前或另一通道），与报告申报的 R-141 缺口（reattach/loadHistory）一致、未被本批虚高销记。
10. **`ExportWizardView` 先 persist 后切步未破坏既有流程**：`persistStepData`（`:639-665`）不读 `stepIndex`，故落库载荷与切步顺序无关；无 `task` watcher 复位 `stepIndex`（仅 `route.query.taskId` watcher 走 `applyTaskBinding`）；`workspace.step` watcher（`:1414-1425`）因 `stepIndex` 先改而不自回环；`viewStep`/`onTabChange`/`ensureStepLoaded(2)→refreshResults` 均未被改。`persisting` 置位时机仍是在 `persistStepData` 入口同步置真（与旧同），仅"切步时刻"推迟到落库完成后（D7 的预期语义）。与原顺序相比唯一相对变化是 `ensure` 与 `sync` 互换了发起次序（两者对 `/files` 与 `/step` 互不依赖），未见影响。
11. **`goStep` 永不 reject**：`persistStepData`/`syncStepToBackend` 各自 try-catch，`ensureStepLoaded` 依旧 `void` 发后不管，`stepIndex/setStep` 为同步赋值 ⇒ 模板 `:14 @prev` 不会产生未处理拒绝。
12. **ImportWizardView D9 未改既有 boolean 语义**：`goStep` 仍「flush 成功即 `true`」（同步失败也 `true`），`import.nextStep` 与 `nextStep()` 的结局消费方式未动。
13. **AiPanel `unknown` 呈现齐备**：`toolTagType('unknown')='warning'`、`toolStatusLabel` 为「结局未知（未确认成功/失败）」，`part.result` 正常渲染（`:194`），与验收点 2 一致。
14. **测试非假绿**（逐条对照 HEAD）：T1（旧 `localTerminal` 无 unknown ⇒ 会被 FRONTEND_RESULT 翻成 succeeded ⇒ 红）、T2（旧 `pendingStatusOf` default 折 unknown 为 pending ⇒ 会产挂起卡 ⇒ 红）、T3（旧集合无 unknown/failed ⇒ `hasDecidedPendingResidue=false` ⇒ 红）、T7①（HEAD handler 恒返「已推进到…」⇒ 无「未推进」⇒ 红）、T7②（旧 `syncStepToBackend` 整段吞 ⇒ 无「服务端步骤同步失败」⇒ 红）、T7④（旧实现 `goStep(2)` 发后不管并继续 ⇒ `ok:true` ⇒ 红）均成立；`frontend-tool-result-terminal.spec.ts` 只追加不改既有断言（符合 T9）。新增两 spec 未纳入 diff，我已直读核对。

---

## 总体结论

**可提交（无 Critical、无回归；建议把 Important-① 作为提交前的一行顺手修——`export.nextStep` 的失败支改返回 `{ok:false, reason}`，T7① 断言不受影响——并把 Important-② 的空断言换成对 `@/utils/excel#downloadBlob`（或 `URL.createObjectURL`）的 spy；若坚持不修，两条都必须在报告/台账中如实登记为残留，不得按已闭合计。**
