# 历史补档：等待治理批 A（W-01 + W-02 + 配套）施工 / 复核 / 收尾修

- **归档来源（已脱敏）**：3 件仓外原件，全部来自 `<LOCAL_WORK>/wait-fix-a/`，源件仅仓外、未移动、未改写；分件见下表
- **归档日期**：2026-10-26（本批仅归档）
- **历史取证 · 非现行基线**：本文是 2026-10-10 时点的历史证据，结论未经当前代码复验，**不表示当前已验证 / 已修复**；正文逐字保留（仅路径、会话标识、用户名/主机名与旧目录名脱敏），不改写旧结论。
- **会话**：正文未出现会话 id，来源会话见索引 `README.md` 的来源映射表（模型与 `agentId` 自证字段按 DC-19 保留原值）
- **源行 / 源字节**：1052 行 / 92,540 B

| 分件 | 原件（仓外别名） | 源行 | 源字节 | 主题 |
|---:|---|---:|---:|---|
| 1 | `<LOCAL_WORK>/wait-fix-a/施工报告.md` | 337 | 29,314 | 施工报告（方案 A 落地） |
| 2 | `<LOCAL_WORK>/wait-fix-a/复核报告.md` | 361 | 38,425 | 独立复核报告（S2-e 破防项 + N-1/N-2/N-3） |
| 3 | `<LOCAL_WORK>/wait-fix-a/收尾报告.md` | 354 | 24,801 | 收尾修报告（S2-e / N-1 / N-2 / N-3 处置） |

---

**〔分件 1／3〕** 原件：`<LOCAL_WORK>/wait-fix-a/施工报告.md`

# 等待治理批 A · 施工报告（W-01 + W-02 + 配套）

- 施工子智能体：DeepSeek V4 Flash（后台独立运行）
- 仓库：`<REPO>`（工作树实读于 2026-10-10，进入时 HEAD = `6ee96c3`，工作树干净）
- 施工依据：仓库外《等待成本付出了，结果没人消费》全项目排查报告「第 0 号案例方案 A 落地要点」节 + `W-01`/`W-02`/`W-09` 条目 + 施工卡（批 A）
- 纪律：未 `--no-verify`、未提交（提交官统一处理）、未触碰 `docs/M2-排期计划.md`（见 §3.4：该文件由**另一并发写入方**改动，本包零触碰）
- 断言标注：【实测】= 本次真实执行命令/读到产物直接得到；【实测·静态】= 对源码/产物的结构化读取（非运行时行为）；【推断】= 由实读代码推演；【旁证】= 引用仓库内既有文档/注释；【假设】= 依赖未验证前提
- 报告落盘：`<SCRATCH>/wait-fix-a/施工报告.md`（仓库外）

---

## 1. 改动清单总览

| # | 文件 | 改动 | 依据 |
|---|---|---|---|
| 1 | `frontend/src/views/ExportWizardView.vue` | 即发即回（执行体拆前置校验 + 发起不等待）+ 去吞异常（拆 `startDownload` / `downloadOne` 返回结局）+ 在途防重复（`downloading` + 在途 defCode 集合）+ ack 文案改实 | 施工卡 1a/1b/1c |
| 2 | `backend/.../ai/gate/ConfirmGate.java` | 前端工具超时文案去「未获得数据」，改「未收到前端回执，结局未知（不表示未发生）」+ `expire` javadoc 分场景写明 | 施工卡 2 |
| 3 | `backend/.../ai/config/AiProperties.java` | `Hitl#timeout` javadoc 补「两场景结局语义不同」（注释随 2 同步，属陈旧注释清理） | 施工卡 2 的连带 |
| 4 | `backend/src/test/.../ConfirmGateFrontendTimeoutTextTest.java`（**新增**） | 超时文案回归锁（新措辞在 + 旧措辞不在）+ 确认门分支文案不被一并改掉 | 施工卡 2「注意是否有对应测试断言旧文案」 |
| 5 | `frontend/src/stores/workspace.ts` | `ui_event download` 补 `AbortController` + 60s 超时（R-11）；`refresh_defs`/`refresh_data` 空回执改如实（W-09） | 施工卡 3 |
| 6 | `frontend/src/utils/frontend-tools.ts` | 两处描述「`downloadOne` 吞异常、执行器从不 reject」的注释已失实，改写并登记新的过度保守面 | 陈旧注释清理（基础纪律） |
| 7 | `docs/requirements.md:108` | 追加注记（只追加、零删）：「下载」= `download_export_file`，覆盖口径为「已发起」 | 施工卡 4 |

`docs/test-cases.md` **未改动**（施工卡 4 为条件式）：【实测】`grep -n "下载|download|前端工具|超时" docs/test-cases.md` 全命中里，与本工具语义相关的只有用户侧下载用例（`E-EX-06`/`N-DL-01`/`N-DL-02`）与 `I-EX-07`（REST 端点）；**无任何用例描述 AI 工具 `download_export_file` 的回执/超时语义**，故按「只追加」纪律无需补注记——若复核认为需要一条新用例，建议随 R-91（前端测试框架）一并立。

---

## 2. 逐处前后对照

### 2.1 `frontend/src/views/ExportWizardView.vue`（改动 165 行：+152 / −13）

#### (a) 新增在途集合 `:218-226`

- 前：无（`downloading` 单值即全部在途状态）
- 后：`const inFlightDownloads = new Set<string>()` + javadoc（`downloading` 是单值，挡不住同码并发；同一文件被触发两次会弹两次保存框；用户点击侧与 AI 侧共用本集合）

#### (b) 拆「发起」与「用户入口」 `:718-794`

前（`:710-725`，原样）：

```ts
async function downloadOne(code: string): Promise<void> {
  if (!code) { ElMessage.warning('请选择要下载的配置项'); return }
  downloading.value = code
  try {
    const { blob, fileName } = await buildXlsx(code)
    downloadBlob(blob, fileName)
    ElMessage.success(`已下载 ${fileName}`)
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '下载失败')   // ← 吞异常，无 rethrow
  } finally { downloading.value = null }
}
```

后：

- `type DownloadOutcome = { ok: true; fileName: string } | { ok: false; reason: string }`（`:719`）
- `async function startDownload(code): Promise<{ fileName: string }>`（`:728-732`）——**「发起」语义，全函数无 try/catch**：`buildXlsx` 的引擎/网络错误、未绑定任务一律向调用方传播（这是「去掉吞异常、使确定性失败能向上传播」的落点；报告 §2.1 辅修 1 明确允许「rethrow 或返回 ok:false」，本包采取**分层**：发起层不吞、调用层转结构化结局）
- `async function downloadOne(code): Promise<DownloadOutcome>`（`:740-765`）——在途防重 + 结局 toast，失败返回 `{ok:false, reason}`（不再是「只有一条 toast、调用方当没发生」）
- `function startDownloadInBackground(code): void`（`:778-794`）——AI 侧即发即回：发起后不 await，`then/catch/finally` 只更新页面态（toast / `downloading` 复位 / 集合清理）；javadoc 写明**本批不新增迟到结局二次回灌通道**（已决条目不接受二次回灌）
- 两个既有调用点（`:805` / `:830`）改为「结局由 `downloadOne` 自己 toast，此处不判读返回值、也不重复报错」的显式注释（行为不变：仍 toast 一次，避免了「rethrow + 外层 catch」造成的双 toast）

#### (c) `downloading` 复位改为身份判定 `:761-763` / `:790-792`

- 前：`finally { downloading.value = null }`（无条件）
- 后：`if (downloading.value === code) { downloading.value = null }`
- 理由：AI 后台下载与用户下载可交错，无条件复位会把别人的在途状态抹掉（【推断】，由两处调用路径交错推演）

#### (d) 前置校验抽成 `preflightDownload` `:985-1015`

- 前：四段校验内联在执行器里（`:982-996`），任意失败即 `throw new Error(...)`
- 后：`async function preflightDownload(args)` 返回 `{ok:true; defCode} | {ok:false; message}`；四段**全部保留 await**（参数 → `ensureTaskBoundForTool` 绑定门 → `exportResults` 结果存在性 → `goStep(2)` 切页），失败文案**逐字保留**（仅改为经 `message` 传递，由执行器 `throw new Error(preflight.message)`）。切页失败的 W-06/W-07 面属批 C，本包不动

#### (e) 执行体即发即回 `:1106-1121`

前（`:981-999`）：

```ts
registerFrontendToolExecutor('download_export_file', async (call) => {
  const defCode = String(call.args.defCode ?? '')
  if (!defCode) { throw new Error('缺少 defCode') }
  const gate = await ensureTaskBoundForTool(call.args.taskId)
  if (!gate.ok) { throw new Error(`${gate.message}。${defCode} 的导出文件未下载`) }
  if (!exportResults.value.some((item) => item.defCode === defCode)) { throw new Error(...) }
  if (stepIndex.value !== 2) { goStep(2) }
  await downloadOne(defCode)                                    // ← 等整条浏览器下载链路
  return `已触发下载任务 #${taskId.value} 的 ${defCode} 导出文件（在线编辑后的内容）`   // ← 对未来事件的断言
})
```

后：

```ts
registerFrontendToolExecutor('download_export_file', async (call) => {
  const preflight = await preflightDownload(call.args)
  if (!preflight.ok) { throw new Error(preflight.message) }     // 确定性失败 ⇒ 卡片 failed
  const { defCode } = preflight
  const busy = downloading.value
  if (inFlightDownloads.has(defCode) || busy !== null) {
    return `任务 #<taskId> 的 <defCode> 导出文件：页面上已有一次下载在进行中（<label>），本次未重复发起。`
      + '下载由浏览器执行，文件落盘以浏览器下载目录为准；本次调用到此结束，无需重试。'
  }
  startDownloadInBackground(defCode)                            // ← 即发即回：不 await
  return `已发起下载：任务 #<taskId> 的 <defCode> 导出文件（内容为在线编辑后的当前表格）。`
    + '下载由浏览器执行 —— 若弹出保存位置选择框或提示被拦截，请让用户在浏览器侧放行并确认；'
    + '文件落盘以浏览器下载目录为准。本次调用到此结束，无需重试。'
})
```

【实测·静态】执行器区内 `await downloadOne(` **零命中**（全文件仅剩 `:805`/`:830` 两处用户侧调用）；`:1118` 为 `startDownloadInBackground(defCode)`（不在任何 `await` 表达式内）。

#### (f) ack 文案终稿（中文字段，逐字抄录）

1. **发起成功**（`:1119-1121`）：

> 已发起下载：任务 #`<taskId>` 的 `<defCode>` 导出文件（内容为在线编辑后的当前表格）。下载由浏览器执行 —— 若弹出保存位置选择框或提示被拦截，请让用户在浏览器侧放行并确认；文件落盘以浏览器下载目录为准。本次调用到此结束，无需重试。

2. **在途未重复发起**（`:1114-1116`）：

> 任务 #`<taskId>` 的 `<defCode>` 导出文件：页面上已有一次下载在进行中（`<label>`），本次未重复发起。下载由浏览器执行，文件落盘以浏览器下载目录为准；本次调用到此结束，无需重试。

其中 `<label>` 取值由 `downloadingLabel`（`:965-974`）给出：`checked` → 「勾选项打包下载」、`all` → 「全部打包下载」、其余 → 「`<编码>` 的下载」。

3. **前置校验失败**（确定性失败，走 `runRegisteredTool` 的 catch ⇒ `ok:false, availability:'degraded'`，卡片 failed）：文案逐字保留既有三条（`缺少 defCode` / `<绑定门原因>。<编码> 的导出文件未下载` / `<无结果原因>…本次未触发下载`），仅在末尾由契约层追加既有的「。请提示用户手动重试。」

英文/机器字段：**无新增**（本包未引入新的帧字段、状态码或英文标识；`DownloadOutcome` 是前端内部类型，`ok`/`fileName`/`reason` 三个键名如上）。

#### (g) 工具卡档位（施工卡 1c 要求的最小方案）

未新增档位、未改 `types/ai.ts` 与 `AiPanel.vue`：前置校验失败 ⇒ `failed`；发起成功 ⇒ `succeeded`（卡片通用标签仍是「已成功」），语义由上面的 ack 文案承担。【旁证】报告 §2.4 已把语义精确的 `started` 档位指派给「对话时序包③a / R-84」设计，本包不二次设计。

### 2.2 `backend/.../ai/gate/ConfirmGate.java`（+12 / −2）

`:310`（`expire` 内前端工具分支）：

- 前：`"前端工具 " + fresh.getName() + " 未在 " + timeoutSeconds + " 秒内回传执行结果（前端超时），本次调用未获得数据。"`
- 后：`"前端工具 " + fresh.getName() + " 未在 " + timeoutSeconds + " 秒内收到前端回执，本次调用结局未知（不表示未发生）。"`
- `N` 用实际配置值 `timeoutSeconds`（`confirmTimeoutSeconds()` 读 `app.ai.hitl.timeout`），未硬编码
- 确认门分支（`confirm=true`）文本**未动**（该分支超时 = 确定未执行，语义不同）

`:285-296` `expire` javadoc：原「超时自动取消：把"未执行"这一结局也写进外置状态」→ 改为分场景写明：确认门 = 「未执行」（确定未发生）；前端工具 = 「回执没回来」、**结局未知**（副作用可能已发生）——避免注释与代码反向。

**测试断言旧文案的排查结论**：【实测】全仓 `grep -rn "未获得数据|未在 .* 秒内回传执行结果"` 在 `backend/src/test` **零命中**（仅 `GenerativeFormGateTest:215` 断言的是 `generative_form` 取消路径的 `FRONTEND_CANCELLED`/「未获得任何数据」，语义不同且未改动）；`ConfirmGateWiringTest#startImportIsNotExecutedOnTimeout` 断言的是**确认门**分支文案（未动）。命中的其他位置为：`spike/sp02`、`spike/sp01ab`（PoC 快照，未动，见 §5）、`docs/03-技术方案文档-v2.1.md:264`、`docs/spike/SP-01ab-决策卡.md:130`（文档，见 §5）、`frontend/src/utils/frontend-tools.ts:169`（引用旧文案的历史论证，见 §2.6）。

### 2.3 `backend/.../ai/config/AiProperties.java`（+5 / −2）

`Hitl#timeout` javadoc：原「超过本上限即自动取消（`status=TIMEOUT`，工具未执行），循环继续」→ 补明两场景结局语义不同（确认门 = 工具未执行；前端工具 = 结局未知），并指向 `ConfirmGate#expire` 与新增回归锁。属陈旧注释清理（原文对该键的共用场景给出了一刀切结论）。

### 2.4 `backend/src/test/.../ConfirmGateFrontendTimeoutTextTest.java`（新增，123 行）

两条用例（等待上限注入 1s，用例耗时合计 ≈2s，确定性无竞态）：

1. `frontendToolTimeoutSaysOutcomeUnknown`：`awaitFrontendResult` 在前端始终不回灌时 → `status=TIMEOUT` / `reason=FRONTEND_TIMEOUT` / `executed=false`；文案 **contains**「未在 1 秒内收到前端回执」「结局未知」「不表示未发生」，**doesNotContain**「未获得数据」「回传执行结果」；并 verify `emitter.frontendToolResult(resolved, false, text)`
2. `confirmTimeoutKeepsNotExecutedWording`：`awaitDecision` → 文案仍含「确认等待超过 1 秒」「系统已自动取消该操作（工具未执行）」；verify `confirmDecision(..., "timeout", "CONFIRM_TIMEOUT", 1000L)`

**说明（超出施工卡字面清单的一处）**：施工卡只要求「若有测试断言旧文案则一并更新」（实测无）。此处**新增**回归锁的理由：① 文案改实是本包唯一无编译期保障的产出，无锁则随时可被静默改回；② 「doessNotContain 旧措辞」才是防回归的关键（只断言新句存在时，旧句被追加回去仍全绿）；③ 该用例给「文案确实随代码执行产出」提供了运行时证据（见 §3.3 的植入对照）。若复核认为批 A 不宜扩测试面，删除该文件即可，主改动不受影响。

### 2.5 `frontend/src/stores/workspace.ts`（+40 / −4）

#### (a) R-11：`ui_event download` 补超时 `:327` + `:434-454`

- 新增常量：`const DOWNLOAD_FETCH_TIMEOUT_MS = 60_000`（`:327`，与 `api/http.ts:85` 的 axios 单请求上限同值——同为"拉字节"类请求，沿用仓库既有超时口径）
- 分支改写：

前：

```ts
case 'download': {
  const response = await fetch(event.url)          // 无 signal、无上限
  if (!response.ok) { return fail(`下载失败：HTTP ${response.status}`) }
  saveBlob(await response.blob(), event.filename ?? 'download')
  return ok(`已触发下载 ${event.filename ?? event.url}`)
}
```

后：`AbortController` + `setTimeout(() => controller.abort(), 60_000)`，`fetch(url, { signal })`；`catch` 内以 `controller.signal.aborted` 判定为超时 ⇒ `fail('下载失败：60 秒内未取回文件（已中止本次请求），未写入任何文件')`，非超时错误**原样 rethrow**（保持既有的「网络错误冒泡到 `runUiEvent` 外层 catch」语义）；`finally` 清理计时器。计时器覆盖 `await response.blob()` 的读体阶段（超时不会只包住响应头）。

#### (b) W-09：`refresh_defs` / `refresh_data` 空回执改如实 `:455-470`

前：`ok('已通知配置定义页刷新')` / `` ok(`已通知数据浏览页刷新（${defCode}）`) ``
后：`ok('已记录刷新意图（AI 请求刷新配置定义）：当前没有任何页面订阅该事件并重新拉取数据，配置定义页的数据不会因此变化；如需最新定义，请让用户在界面上重新打开该页，或稍后重新查询。')`（`refresh_data` 同构，带上 `defCode`）

依据：【实测】`grep -rn "onWorkspaceEvent" frontend/src` ⇒ 订阅者仅 `stores/ai.ts:379`（`workspace_changed` → 上下文 chip）与 `:382`（`ui_event_result` 提示条）；**零个页面订阅该主题去 reload**，故旧文案的「已通知…刷新」与事实不符（报告 §5.1 R-11 的处置选项②「文案改实」）。

**口径说明**：施工卡 3 括号写明「与 R-11/W-09 同批闭合」，故本包一并改 W-09 文案（仅两行文本、无行为变更）。若复核认定 W-09 属批 D 不随批 A，回滚面 = 上述两处 `return` 语句。

### 2.6 `frontend/src/utils/frontend-tools.ts`（+15 / −2，纯注释）

- `:340-343`（`runWithBudget` 的「超时分支只依赖计时器」段）：原理由「这对 `download_export_file` 是必需的（`downloadOne` 吞异常、从不 reject）」**已失实**（现在执行器会 reject、且不再等链路）→ 改写为「该性质在批 A 后依然必需：执行器只等前置校验（绑定门串行 HTTP 可以很长），'等它 settle/reject' 都不能当超时判定依据」
- `:402-408`（`timeoutOutcome` 的吞异常注释）：改为「批 A 后的口径」+ 新增**已知过度保守（登记不修）**：即发即回后该工具的超时只可能落在前置校验期间（尚未发起下载），下载类超时文案的「文件也许已落盘、请勿重复触发」因此偏保守——方向安全（不谎报、不诱导重复触发），是否收窄留待 R-84 设计时裁决
- **未改动** `timeoutOutcome` 的文案与 `FRONTEND_TOOL_TIMEOUT_BUDGET_MS`/`FRONTEND_TOOL_RESULT_HEADROOM_MS` 的取值与论证（预算机制本身不在批 A 范围，见 §5【待裁决】第 4 项）

### 2.7 `docs/requirements.md:108`（行内追加，内容零删）

前：`| 导出-执行导出 | + 启动导出、等待导出完成、打开文件编辑器、下载 |`
后：同行为「…、下载 ｜**注记（2026-10-10 等待成本治理批 A）**：…原表述按只追加纪律保留留痕。 |」（内容零删，仅追加）

---

## 3. 验证（命令 + 退出码 + 关键输出）

### 3.1 前端门禁

```bash
cd frontend && yarn typecheck    # vue-tsc --noEmit
# → "Done in 4.50s."  EXIT=0        【实测】
cd frontend && yarn build        # vue-tsc --noEmit && node scripts/build.mjs
# → "✓ built in 28.13s / Done in 33.13s."  EXIT=0   【实测】
```

均在**最终工作树**状态重跑（早前一次运行早于 `workspace.ts` 的编辑，故已在全部改动落定后重跑）。

### 3.2 文档门禁

```bash
node scripts/ci/check-docs.mjs
# → ①…⑥ 全 [PASS]；「全部通过：6/6 断言绿。」EXIT=0   【实测】
#   ⑤ 细则输出：git ls-files 918 个路径、实扫 915、豁免 3；连写组 5/5、分隔组 24/24、负对照 11 全不误伤
```

### 3.3 后端（Maven 路径含空格，经包装脚本调用）

```bash
# <SCRATCH>/wait-fix-a/mvn-gate2.sh
#   "<DRIVE>/Program Files/JetBrains/IntelliJ IDEA/plugins/maven-plugin/lib/maven3/bin/mvn.cmd"
#   -B -Dtest=ConfirmGateFrontendTimeoutTextTest,ConfirmGateWiringTest,ConfirmGateDeadlockResumeTest,GenerativeFormGateTest
#   -DfailIfNoSpecifiedTests=false test
```

关键输出（`<SCRATCH>/wait-fix-a/mvn-gate3.log`）【实测】：

```
Tests run: 9  Failures: 0  Errors: 0  Skipped: 0 -- ConfirmGateDeadlockResumeTest
Tests run: 2  Failures: 0  Errors: 0  Skipped: 0 -- ConfirmGateFrontendTimeoutTextTest      ← 新增
Tests run: 4  Failures: 0  Errors: 0  Skipped: 0 -- ConfirmGateWiringTest
Tests run: 6  Failures: 0  Errors: 0  Skipped: 0 -- GenerativeFormGateTest$SubmissionGate
Tests run: 2  Failures: 0  Errors: 0  Skipped: 0 -- GenerativeFormGateTest$SuspensionGate
Tests run: 23 Failures: 0  Errors: 0  Skipped: 0
BUILD SUCCESS   MVN_EXIT=0
```

（112 个主源 + 62 个测试源全量编译通过 ⇒ `ConfirmGate`/`AiProperties` 的改动可编译；未跑全量 `mvn test`，按施工卡口径只跑相关类。）

**回归锁的植入对照（证明断言可失效）**【实测】：临时把 `ConfirmGate` 前端工具分支文案种回旧措辞（`…（PLANTED-CONTROL）回传执行结果（前端超时），本次调用未获得数据。`）→ 单跑该测试类：

```
[ERROR] Tests run: 2, Failures: 1, Errors: 0  <<< FAILURE!  -- ConfirmGateFrontendTimeoutTextTest
  "前端工具 download_export_file 未在 1 秒内（PLANTED-CONTROL）回传执行结果（前端超时），本次调用未获得数据。"
BUILD FAILURE   MVN_EXIT=1
```

随后**逐字还原**（`grep -rn "PLANTED-CONTROL" backend/src frontend/src docs` 零命中，`git diff --stat` 回到 +12/−2），再跑一次 §3.3 全绿 ⇒ 结论：新文案确实由代码执行产出、且旧措辞一旦回归必被抓住。

### 3.4 ack 文案纪律检查（仓库外校验器 + 植入对照）

```bash
node <SCRATCH>/wait-fix-a/ack-lint.mjs         # 实盘：EXIT=0，0 条违规   【实测】
node <SCRATCH>/wait-fix-a/ack-lint.mjs seed    # 植入对照：EXIT=0，命中 5 条
```

实盘检查项：含「已发起下载」；含「无需重试」；含「浏览器」「下载目录」；去除「无需重试」后不得出现「下载成功 / 已成功 / 若未成功 / 请重试 / 若失败 / 可能失败 / 未获得数据」等禁止措辞与任何「重试」暗示。植入对照（把「已发起下载」替换为「下载成功」并追加「若未成功请重试。」）被逐条抓住（5 条命中）⇒ 检查器非空转。

### 3.5 产物级印证（[实测·静态] 读构建产物）

对 `frontend/dist/assets/ExportWizardView-BOaQZEzH.js` 实读编译结果：

- 发起层无 catch：`async function Se(e){const{blob:a,fileName:t}=await le(e);return we(a,t),{fileName:t}}`
- 用户入口返回结构化结局：`…return{ok:!0,fileName:a}}catch(a){…return{ok:!1,reason:t}}finally{I.delete(e),p.value===e&&(p.value=null)}`
- 执行器即发即回：`Fa("download_export_file",async e=>{const a=await ia(e.args); if(!a.ok)throw new Error(a.message); … return I.has(t)||n!==null ? "…已在进行中…无需重试。" : (na(t), "已发起下载：…无需重试。")})` —— `na` 即 `startDownloadInBackground`，**不在 await 表达式内**，其后立即 `return` ack

即：**用户实际收到的构建产物**在结构上已不含「等浏览器下载链路」的等待点。

### 3.6 未做的验证（如实登记）

- **未做前端运行时端到端复现**（真浏览器 + 真后端 + 真 AI 会话下，弹保存框时的回灌时延对照）：需要完整三件套环境；且 R-91（前端测试框架）尚未落地，页面级执行器无单测挂点。故 1a~1c 的**运行时行为**结论标注为【推断】（依据 = §3.5 的产物结构 + 控制流实读），**不是**【实测】。
- **未跑全量 `mvn test`**（按施工卡口径只跑相关类；施工卡未要求全量）。
- **未跑 `scripts/verify-e2e.ps1`**（需完整栈自启）——另经【实测】`grep` 确认该脚本无 `download_export_file` 的 AI 工具断言（其下载类用例只打 REST 端点 `/files/download*`），`scripts/ci/stub-routes.json` 中 `download_export_file` 仅出现在 `productTools` 披露清单、**无路由驱动**，故批 A 不会使该门禁变红。

---

## 4. 终扫（四模式 + 植入对照）

扫描器：仓库外提交官扫描器 `<SCRATCH>/commit-gov/desense-scan.mjs`（四模式：盘符绝对路径 / 本机身份[用户名·主机名·用户目录] / 内网·环回·链路本地 IP / 命名违禁，后者与 `scripts/ci/check-docs.mjs` 断言⑤同规则：NFKC + 隐形字符双态 + 去分隔符连写 + 词首守卫）。

植入对照（先跑，证明扫描器武装）【实测】：

```
正向种子 23/23 命中；负对照 12/12 未误伤；保守过匹配 2/2 已登记   SEED_EXIT=0
```

实盘（本包改动/新增的 7 个文件）【实测】：

```
实盘：7 个文件，命中 0 处，按豁免登记放过 0 处   REAL_EXIT=0
```

覆盖文件：`backend/.../ai/config/AiProperties.java`、`backend/.../ai/gate/ConfirmGate.java`、`backend/src/test/.../ConfirmGateFrontendTimeoutTextTest.java`（新增）、`docs/requirements.md`、`frontend/src/stores/workspace.ts`、`frontend/src/utils/frontend-tools.ts`、`frontend/src/views/ExportWizardView.vue`。另 `check-docs.mjs` 断言⑤（全仓 `git ls-files` 口径）6/6 绿，构成第二道独立命名纪律门禁。

---

## 5. 遗留问题与【待裁决】

1. **浏览器侧真实落盘结局不回灌（本批显式遗留）**：`a.click()` 之后浏览器侧的失败/成功不产生第二份回执（`ConfirmGate#submitFrontendResult` 对已决条目一律 409）。ack 已声明「落盘以浏览器下载目录为准」，属报告 §5.4 明示的代价。**若复核要求覆盖**，可选的折中（报告 §5.4）：保留 ≤2s 的短等待以捕获 click 后的同步异常，其余即发即回。**本包未采用**（施工卡 1a 明确「发起后不等待」）。
2. **启动前即可判定的失败也不回灌（残余面放大一点）**：即发即回后，`buildXlsx`/`toXlsxBlob`/`downloadFile` 的失败**发生在 ack 之后**，AI 侧只会看到「已发起下载」（用户侧有 `ElMessage.error` + `downloading` 复位）。这是「不等」的必然代价；报告 §2.4 曾设想 `failed` 覆盖「执行器返回前即可判定」的失败，纯即发即回下该类失败在返回前**不可判定**（`buildXlsx` 是异步的）——报告该处口径与施工卡 1a 存在**轻微张力**，本包按施工卡执行，提请复核确认。
3. **卡片标签仍是「已成功」**：最小档位方案下 `succeeded` 的通用标签未变（语义由 ack 文案承担）。语义精确的 `started` 档位属 R-84 / 对话时序包③a。**【待裁决】**是否需要在本包内先做卡面特例（例如对 `download_export_file` 的结果文案前缀「已发起」）——本包未做，避免与 ③a 二次设计。
4. **超时预算机制未随之收窄**：`FRONTEND_TOOL_TIMEOUT_BUDGET_MS` / `FRONTEND_TOOL_RESULT_HEADROOM_MS`（60s 折算）对即发即回工具已无实际承载（报告 §3.2 判定「该式对『即发即回』工具已无意义」），且下载类超时文案现在过度保守（已按「登记不修」写入 `frontend-tools.ts:402-408`）。**随 R-99（配置键拆分 / 分场景预算）处理为宜。**
5. **`ui_event download` 的 ack 文案未动**：该分支仍回 `已触发下载 <filename>`。此句在新实现下**仍属实**（`fetch` 已成功返回并调用 `saveBlob`），故按「只陈述已发生的事」保持不动；若复核要求与 `download_export_file` 口径完全对齐（同款浏览器侧不确定性交代），可另立小改。
6. **文档面遗留（未改，因超出施工卡给的文档范围）**：
   - `docs/03-技术方案文档-v2.1.md:264` 仍写「前端工具超时→回填『前端未在 N 秒内回传，本次未获得数据』」——**与改后代码不符**【实测 grep 命中】；
   - `docs/spike/SP-01ab-决策卡.md:130` 同款旧口径；`spike/sp01ab`、`spike/sp02` 的 PoC 源码内仍有旧文案（历史快照，未动）；
   - `docs/evidence/S44c-AI面板与出口验证.md:141,171` 保留了旧 ack「已触发下载…」的实测截图记录（历史证据，按既有口径不回改）。
   **【待裁决】**是否为 `docs/03-技术方案文档-v2.1.md` 追加一条只追加注记（本包未做：施工卡 4 只授权 `requirements.md` 与 `test-cases.md`）。
7. **用户点击侧的去重是新行为**：`downloadOne` 现在会因「同码在途」而拒绝重复发起（返回 `{ok:false}` + info toast），此前会重复下载两次。方向上防重复弹框，但**属行为变更**，提请确认可接受。
8. **未提交**：按纪律本包零提交，改动留在工作树（含新增测试文件 `??` 未跟踪），交提交官统一处理。
9. **`docs/M2-排期计划.md` 由另一并发写入方改动**（+9 行，登记 R-95…R-99），**本包零触碰**；提交时请注意该文件与 R-95 行内「施工中（批 A）」状态需要与本包实际完成度对齐（本包未改该行，按规定「立项登记由另一任务负责」）。

---

## 6. 复核要点速查（供复核者逐条打勾）

| 施工卡要求 | 落点 | 状态 |
|---|---|---|
| 1a 前四段保留 await | `ExportWizardView.vue:985-1015`（preflight）| 完成 |
| 1a `await downloadOne` 改不等待 + 页面态跟踪 | `:1118` + `:778-794` | 完成 |
| 1a ack 三要素（已发生的事 / 浏览器侧不确定 / 无需重试） | `:1119-1121`（文案见 §2.1f） | 完成（纪律检查 §3.4 全绿） |
| 1a 禁失败/重试暗示 | 同上（植入对照 5 条抓违规） | 完成 |
| 1b 去吞异常、拆「发起」语义 | `:728-732`（无 catch）+ `:740-765`（转结构化结局） | 完成 |
| 1b 在途防重（`downloading` + defCode 集合） | `:226` + `:1113-1117` | 完成 |
| 1c 最小档位（不新增状态档） | 未改 `types/ai.ts`/`AiPanel.vue` | 完成（§5.3 待裁决项） |
| 2 ConfirmGate 超时文案改实 + N 用配置值 | `ConfirmGate.java:310` | 完成（§3.3 有回归锁） |
| 2 是否有序旧文案断言 | 实测无（§2.2） | 已排查 + 新增回归锁 |
| 3 `ui_event download` 补 signal/超时 | `workspace.ts:327,434-454` | 完成 |
| 3 与 W-09 同批 | `workspace.ts:455-470` | 完成（§2.5b） |
| 4 文档注记（只追加 + 内容零删） | `docs/requirements.md:108` | 完成；`test-cases.md` 经实测无需改 |
| 纪律：typecheck / build / check-docs / 相关测试 | §3.1–3.3 | 全绿 |
| 纪律：终扫四模式带植入对照 | §4 | 全绿 |
| 纪律：不提交、禁 `--no-verify` | — | 遵守（零提交） |

**〔分件 2／3〕** 原件：`<LOCAL_WORK>/wait-fix-a/复核报告.md`

# 等待治理批 A · 独立复核报告（对抗反查）

- 复核子智能体：DeepSeek V4 Flash（后台独立运行，非施工者）
- 仓库：`<REPO_ROOT>`（分支 `main`，HEAD = `6ee96c3`，工作树 = 批 A 在制品）
- 复核时间：2026-10-10（实读时点见各命令）
- 纪律遵守：**零仓库写入**（未 `git add/commit/checkout`、未改任何被跟踪或未跟踪文件、未 `touch`）、报告落仓库外 `<LOCAL_WORK>\wait-fix-a\复核报告.md`、脚本与日志落 `<LOCAL_WORK>\wait-fix-a\rv\`
- 复核依据：施工报告 `<LOCAL_WORK>\wait-fix-a\施工报告.md`（其结论一律视为待证伪）+ 复核清单 S1~S4
- 断言标注：【实测】= 本次真实执行命令/读到产物直接得到；【实测·静态】= 对源码/编译产物的结构化读取；【推断】= 由实读代码推演的运行时行为；【旁证】= 引用仓库内既有文档/注释；【假设】= 依赖未验证前提
- 方法：S1 复现门禁（含「陈旧 class 掩盖」反查）→ S2 逐条构造反例攻击 → S3 工作树对账 → S4 全仓旧口径残留扫

---

## S1 门禁复现（逐命令）

### S1-1 前端 `yarn typecheck`【实测】PASS

```
cd frontend && yarn typecheck          # vue-tsc --noEmit
$ vue-tsc --noEmit
Done in 5.16s.                          TYPECHECK_EXIT=0
```

### S1-2 前端 `yarn build`【实测】PASS

```
cd frontend && yarn build              # vue-tsc --noEmit && node scripts/build.mjs
✓ 1747 modules transformed.  ✓ built in 27.58s   Done in 32.58s.   BUILD_EXIT=0
dist/assets/ExportWizardView-BOaQZEzH.js  19.52 kB │ gzip: 7.56 kB
```

产物文件名哈希 `ExportWizardView-BOaQZEzH.js` 与施工报告 §3.5 引用**逐字一致** ⇒ 报告引用的产物级证据可在本工作树复现（内容哈希同一）【实测】。

### S1-3 文档门禁 `node scripts/ci/check-docs.mjs`【实测】PASS 6/6

```
  [PASS] ① 导航/口径文档无盘符绝对路径
  [PASS] ② 技术栈版本与 pom.xml / package.json 一致
  [PASS] ③ 导航/口径文档引用的文件存在
  [PASS] ④ 端口表与 application.yml / vite.config.ts 一致
         （命名纪律扫描：git ls-files 918 个路径，实扫文本 915 个；豁免 3 个）
         （规则自检：连写组 5/5「仅第三态可捕获」；分隔组 24/24；负对照 11 全不误伤）
  [PASS] ⑤ 命名纪律禁则（git ls-files 全量）
  [PASS] ⑥ .java 探针不参与构建
全部通过：6/6 断言绿。                  DOCS_EXIT=0
```

计数与报告 §3.2 自述（918 / 915 / 3、5/5、24/24、11）**逐项一致**【实测】。

### S1-4 后端相关测试【实测】PASS 23/0/0

自行包装（未复用 `commit-gov/mvn-wrap.sh`，因该脚本跑全量 `test`；按清单口径只跑四类），脚本与日志 `<LOCAL_WORK>\wait-fix-a\rv\mvn-gate.sh` / `mvn-gate.log`：

```
"<DRIVE>/Program Files/JetBrains/IntelliJ IDEA/plugins/maven-plugin/lib/maven3/bin/mvn.cmd"
  -B -Dtest=ConfirmGateFrontendTimeoutTextTest,ConfirmGateWiringTest,ConfirmGateDeadlockResumeTest,GenerativeFormGateTest
  -DfailIfNoSpecifiedTests=false test

Tests run: 9  Failures: 0  Errors: 0  Skipped: 0 -- ConfirmGateDeadlockResumeTest
Tests run: 2  Failures: 0  Errors: 0  Skipped: 0 -- ConfirmGateFrontendTimeoutTextTest
Tests run: 4  Failures: 0  Errors: 0  Skipped: 0 -- ConfirmGateWiringTest
Tests run: 6  Failures: 0  Errors: 0  Skipped: 0 -- GenerativeFormGateTest$SubmissionGate
Tests run: 2  Failures: 0  Errors: 0  Skipped: 0 -- GenerativeFormGateTest$SuspensionGate
Tests run: 0  Failures: 0  Errors: 0  Skipped: 0 -- GenerativeFormGateTest（外框）
Tests run: 23 Failures: 0  Errors: 0  Skipped: 0
BUILD SUCCESS   MVN_EXIT=0   Total time: 7.016 s
```

逐类计数与报告 §3.3 **完全一致**【实测】。

### S1-5 反查：23/0/0 是否被「陈旧 class」掩盖？【实测】未破防

Maven 输出含 `Nothing to compile - all classes are up to date.` ⇒ 本次未重编译，需独立证明 class 与当前源码同源。三重证据：

1. 时间戳序（`ls --time-style=full-iso`）：
   - `ConfirmGate.java` 16:45:45 → `ConfirmGate.class` 16:46:02 ✅
   - `AiProperties.java` 16:45:52 → `AiProperties.class` 16:46:02 ✅
   - `ConfirmGateFrontendTimeoutTextTest.java` 16:45:04 → `.class` 16:46:06 ✅
2. **字节码常量池直接读**（对 `target/classes/.../ConfirmGate.class` 做 UTF-8 字面子串检查）【实测】：

   | 字面 | 结果 |
   |---|---|
   | `秒内收到前端回执` | FOUND |
   | `结局未知` | FOUND |
   | `不表示未发生` | FOUND |
   | `未获得数据` | **ABSENT** |
   | `回传执行结果` | **ABSENT** |
   | `已自动取消该操作（工具未执行）`（确认门分支） | FOUND |

   ⇒ 测试实际运行的那个 class **就是改后代码**（前端工具分支已无旧措辞、确认门分支原样保留）。
3. 测试类确实被执行（2 tests，耗时 2.026s ≈ 两个 1s 等待上限）。

结论：S1 四道门禁全部独立复现通过，不存在「跑的是旧字节码」的掩盖。【实测】

**S1 判定：PASS。**

---

## S2 对抗反查（逐条设法证伪）

### S2-a 「即发即回」真实性 —— **未破防**（附 3 条边界注记）

**代码实读**（`frontend/src/views/ExportWizardView.vue`）：

```
:1106  registerFrontendToolExecutor('download_export_file', async (call) => {
:1107    const preflight = await preflightDownload(call.args)   ← 唯一 await
:1108    if (!preflight.ok) { throw new Error(preflight.message) }
:1111    const { defCode } = preflight
:1112    const busy = downloading.value
:1113    if (inFlightDownloads.has(defCode) || busy !== null) { return `…已有一次下载在进行中…` }
:1118    startDownloadInBackground(defCode)                      ← 表达式语句，不在 await 表达式内
:1119    return `已发起下载：…本次调用到此结束，无需重试。`
:1122  })
```

1. **ack 路径上是否还有 await 下载链路？** 否。全文件 `await downloadOne(` 零命中（仅 `:805`/`:830` 两处用户点击侧 `await downloadOne(...)`，返回结局被显式忽略）；`preflightDownload`（`:985-1007`）内**唯一**的 await 是 `:993 await ensureTaskBoundForTool(...)`，而该函数（`:913-950`）只 await `tasksApi.getTask` / `applyTaskBinding` / 绑定在途 promise，**不触碰任何下载链路**（逐行实读）【实测·静态】。
2. **产物级印证**：对本次构建产物 `dist/assets/ExportWizardView-BOaQZEzH.js` 实读执行器编译结果【实测】：

   ```js
   Fa("download_export_file",async e=>{const a=await ia(e.args);if(!a.ok)throw new Error(a.message);
     const{defCode:t}=a,n=p.value;
     return I.has(t)||n!==null?`…已有一次下载在进行中（${ua(n??t)}）…无需重试。`
     :(na(t),`已发起下载：…无需重试。`)})
   ```
   `na` = `startDownloadInBackground`，**其后立即 return ack**，无 await 包裹。与报告 §3.5 引文一致。
3. **reject 是否造成 unhandledrejection？** 不会（AI 侧）。`startDownloadInBackground`（`:778-794`）对同一 promise 挂 `.then().catch().finally()`，`.catch` 覆盖 `buildXlsx`/`downloadBlob` 的全部拒绝；`.finally` 回调本身不抛（只做集合删除与 ref 复位）。结构等价复现（仓库外 `rv/repro-inflight.mjs`，同构 async-无 catch + then/catch/finally）**实测无 UNHANDLED_REJECTION**。【实测】+【推断】
   - 残留理论窗口：若 `ElMessage.error`（`.catch` 内）自身抛异常，则 `.finally` 产出的 promise 会成为未处理拒绝 —— 属 UI 库异常，非本批引入，**注记级**。
4. **页面卸载时在途 promise 有无清理？** 部分清理，**无脏残留**：
   - `inFlightDownloads`（`:226`）声明在 `<script setup>`（`:115` 单一块）内 ⇒ **组件实例级**，卸载即随之失引，不会跨实例残留【实测·静态】。
   - `onBeforeUnmount`（`:1131-1139`）**不**主动清空集合、不 abort 在途链路；在途 promise 继续跑完，其 `.finally` 仍会执行（对已卸载实例做集合删除与 ref 写，无害），用户侧仍会收到一次 toast。**未发现**「卸载后永久拒绝后续下载」的可达路径（重建实例即新集合）。
   - 注记（低）：卸载后 toast 仍可能弹出（`ElMessage` 全局挂载），属既有行为面，不在本批范围。

**边界注记（不构成破防，但影响「立即」二字的口径）**：

- ① 「不 await 链路」≠「ack 先于一切下载工作」。`startDownloadInBackground` 同步调用 `startDownload` → `buildXlsx`，其**同步前缀**在 ack 之前执行；grid 分支的 `SpreadGrid#toXlsxBlob`（`components/SpreadGrid.vue:325-330`）同步进入 `buildRowsXlsxBlob`（`utils/excel.ts:457-481`）。若该同步段耗时（大表序列化），ack 会被推迟同样的时长。**这是与旧实现的本质区别**：旧实现在此处之外还要等 `downloadBlob` 的 `a.click()`（`:downloadBlob` at `utils/excel.ts:839-848`）之后的浏览器侧行为（issue#6 的 120s 挂载点）。故本批**消除了无上界的等待**，但未消除同步阻塞面（该面已在 R-32 登记）【推断】。
- ② ack 内「（内容为在线编辑后的当前表格）」在「grid 未挂载」时可能不成立：`preflightDownload` 的 `goStep(2)`（`:1003-1005`）是同步的、未 `await nextTick`，紧随的 `buildXlsx` 若读不到 `gridRefs[code]` 会回落 `tasksApi.downloadFile`（服务端文件）。**该顺序与改前逐字相同**（HEAD 版同为 `goStep(2)` 后立即 `downloadOne`），旧 ack 也写「在线编辑后的内容」⇒ **非本批引入的回归**，但 ack 改写时应注意到该断言的存在。【实测·静态】+【旁证：`git show HEAD:…` 对照】
- ③ 反例构造（试图破防）：能否构造「ack 已返回但这并非真的 '已发起'」？需要 `startDownload` 的同步前缀在 `downloadBlob(blob, fileName)` 之前就返回而不抛错 —— `buildXlsx` 的两条分支都必须 `await`，故 `downloadBlob` 必然发生在 ack 之后的微任务里；也就是说 ack 的「已发起」严格来说等于「已进入发起流程」，`downloadBlob` 尚未执行完。**语义上是「已发起」而非「已点击下载」**，与 ack 措辞「已发起下载」自洽，**不构成谎报**。未破防【推断】。

### S2-b 防重复真实性（在途集合增删时机）—— **未破防**

- 集合全部写点（grep 全仓，仅本文件）：`:749 add` / `:760 delete`（downloadOne）、`:779 add` / `:789 delete`（startDownloadInBackground）、`:1113 has`、`:745 has`【实测·静态】。
- 「同步段抛异常 ⇒ 脏条目残留 ⇒ 永久拒绝」攻击：**不成立**。`startDownload`（`:728-732`）是 `async function`，其函数体任何异常（含**同步段** `buildXlsx` 调用前的异常）在语言层都转为**被拒绝的 promise**，不会同步抛给调用方；两条调用路径**都**挂了随后的清点：
  - `downloadOne`：`try { await startDownload } catch {} finally { inFlightDownloads.delete(code) }`
  - `startDownloadInBackground`：`.catch(...).finally(() => { inFlightDownloads.delete(code); … })`
  结构等价复现（`rv/repro-inflight.mjs`）【实测】：同步段即抛 → 微任务排空后 `inFlight=['A'] → []`、`downloading=A → null`，随后同码重触发**正常发起**（toast 计数递增），无「永久拒绝」。
- 另一种脏残留路径检查：`finally` 永不执行（promise 永不 settle）才会永久占位。查 `buildXlsx`：grid 分支 `toXlsxBlob` → `buildRowsXlsxBlob` → `buildXlsx(sheets)`（同步序列化，必 settle）；非 grid 分支 `tasksApi.downloadFile` 走 axios（`api/http.ts` 60s 上限）。**未发现无界 await**【推断】。
- 另注：`downloadOne` 的重复拒绝分支（`:745-748`）返回 `{ok:false}` 且**不**改 `downloading`，调用方（`:805`/`:830`）按注释忽略返回值 ⇒ 无双 toast【实测·静态】。

**结论：未破防。**

### S2-c 前置校验完整性 —— **未破防**（「四段全部 await」的表述需修正）

`preflightDownload`（`:985-1007`）四段与 HEAD 逐字对照（`git show HEAD:…:982-996`）【实测】：

| 段 | 现状 | 与 HEAD 对比 |
|---|---|---|
| ① 参数 `defCode` | `if (!defCode) return {ok:false, message:'缺少 defCode'}` | 文案逐字保留 ✅ |
| ② `await ensureTaskBoundForTool(args.taskId)` | 仍 **await**（`:993`） | 保留 ✅ |
| ③ `exportResults.value.some(...)` | `return {ok:false, message:'<noResultMessage>…本次未触发下载'}` | 文案逐字保留 ✅ |
| ④ `if (stepIndex !== 2) goStep(2)` | 同步，**仍不 await、不取结局** | 与 HEAD 同（R-97 的 W-06/W-07 面，本批声明不动）✅ |

- 失败路径是否真的走 `ok:false`：是。执行器 `throw new Error(preflight.message)`（`:1109`）→ `runRegisteredTool` 的 catch（`utils/frontend-tools.ts:842-848`）→ `{ ok:false, availability:'degraded', result:'<displayName>执行失败：<msg>。请提示用户手动重试。' }` → `stores/ai.ts:1504 status: execution.ok ? 'succeeded' : 'failed'`【实测·静态】。
- **表述修正（注记级）**：报告 §6 表与 §2.1(d) 写「四段**全部保留 await**」。严格说只有第②段需要且保留了 await；①③④ 是同步判定（④是同步调用）。**语义结论正确**（该 await 的都没被去掉），但字面表述会让读者误以为四段都是 await 点。建议改为「需 await 的段（任务绑定门）保留 await，四段校验次序与文案零改动」。

**结论：未破防。**

### S2-d 吞异常去除真实性 —— **未破防**（附一条链路级澄清）

- `startDownload`（`:728-732`）**全函数无 try/catch**，实读确认【实测·静态】；`downloadOne` 的 catch（`:755-758`）按设计把异常翻成结构化结局 `{ok:false, reason}`（**不是吞**：结局可被判读，且 JS 侧调用方可见）。
- 沿调用链逐层查「别的层又把异常吞掉」【实测·静态】：
  - `runRegisteredTool:839-848`：catch → **转为结构化失败结局**（这是契约层的设计，非静默吞没；失败原因进 `result` 文本）。
  - `runPageHandler:881-894` / `runUiEventAction:861-870`：同类结构化转换。
  - `runWithBudget:350-375`：**不**catch，只 `race` + `clearTimeout`；注释（`:362-365`）声明 `dispatchFrontendTool` 按契约不 reject，与上面三层 catch 一致。
  - `executeFrontendTool:314` → `stores/ai.ts:1490-1511`：结局照原样回灌（`submitFrontendToolResult(toolCallId, execution.result, …)`）。
- 未发现任何一层把「前置校验失败」重新变成成功。
- **但有一条链路级澄清（新发现 N-6，见后）**：回灌 POST 只送 `result` **文本**，不吃 `ok` 标志；后端 `ConfirmGate#submitFrontendResult:477` 对**非取消**回灌帧**硬编码 `ok=true`**。所以「校验失败 ⇒ 卡片 failed」成立（前端卡面），但**模型侧帧的 `ok` 仍是 true**，失败只由文本「执行失败：…请提示用户手动重试」承载。报告 §2.1f 第 3 点写的 `ok:false` 指的是**前端契约层对象字段**（我已核实确为该值），不是回灌帧字段 —— 表述易被误读，建议加一句限定。

**结论：未破防。**

### S2-e 文案回归锁有效性 —— **破防（窄洞，低危）**

实读 `backend/src/test/java/com/example/configmgr/ai/gate/ConfirmGateFrontendTimeoutTextTest.java`（126 行，报告 §2.4 写 123 行，计数不符）：

- 锁的覆盖面【实测·静态】：① 新文案三段 `contains`（`未在 1 秒内收到前端回执` / `结局未知` / `不表示未发生`）；② 旧措辞 `doesNotContain`（`未获得数据` / `回传执行结果`）；③ 状态/原因/`executed` 三断言；④ `verify(emitter).frontendToolResult(resolved, false, resolved.getResultText())`（帧出口）；⑤ 第二条用例锁**确认门分支**不被连带改掉（`确认等待超过 1 秒` + `已自动取消该操作（工具未执行）` + `confirmDecision(..., "timeout", "CONFIRM_TIMEOUT", 1000L)`）。
- 设计上是好的：`doesNotContain` 旧措辞**确实**是关键（只 contains 新句则旧句被追加回去仍绿）；等待上限压到 1s（注入 `AiProperties.getHitl().setTimeout`），确定性无竞态；且**独立验证**该锁可失效（施工报告 §3.3 的植入对照显示 FAILURE —— 我未重跑该植入，因其要求改仓库文件；但见 S1-5 的字节码证据可佐证「测试确实断言代码产出」）【旁证】。

**攻击：构造一个「通过测试但行为错误」的实现** ⇒ **能构造**（故按清单判为「测试有洞」）：

1. **洞（可构造）**：`TIMEOUT_SECONDS = 1` 与断言 `contains("未在 " + TIMEOUT_SECONDS + " 秒内收到前端回执")` **同源**。构造：

   ```java
   fresh.setResultText("前端工具 " + fresh.getName()
       + " 未在 1 秒内收到前端回执，本次调用结局未知（不表示未发生）。");   // 硬编码 1
   ```
   该实现**旁路了配置键**（`confirmTimeoutSeconds()`），但两条用例全绿（第二条只查确认门分支，不受影响）。⇒ 用例**没有真正锁住**「N 用实际配置值」这条要求。缓解：硬编码常量 `1` 是极不自然的错误实现（更现实的错误 —— 写死 120 —— 会被抓）；且报告 §2.2 的「N 用配置值」本身在代码层已实读证实（`ConfirmGate.java:310` 用入参 `timeoutSeconds`，来自 `confirmTimeoutSeconds()`）【实测·静态】。**风险 = 未来回归时可穿过测试**，非当前实现缺陷。
   - 加固建议（若采纳）：注入一个**非默认且非 1** 的上限（例如 2s 或 3s），或在断言中同时检查文案里的秒数与注入值相等而**不**用同一常量拼接；或加一条「把注入值改为 2s 后文案随之为 2」的参数化断言。
2. **洞（已知/已登记）**：本锁只覆盖**后端**超时文案。**前端**本地预算超时文案（`utils/frontend-tools.ts:410-427`，即模型在客户端预算到点时实际读到的文本）**无任何测试锁**（R-91 前端测试框架未落地）—— 报告 §3.6 已如实登记。
3. **未构成洞的两处（我尝试攻击后放弃）**：
   - 尝试「用一个不产出该文本的实现通过」：不可能，断言同时钉住 `resultText` 与帧出口实参。
   - 尝试「确认门被一起改掉却仍绿」：第二条用例钉住确认门措辞与 `confirmDecision` 实参，故不行。

**结论：破防（窄洞，低危）—— 洞在于「配置值来源」只被弱锁；`TIMEOUT_SECONDS=1` 与断言同源。当前实现无缺陷。**

### S2-f `workspace.ts` 的 AbortController 60s —— **未破防**

实读 `frontend/src/stores/workspace.ts:327, 434-454`【实测·静态】：

```ts
:327  const DOWNLOAD_FETCH_TIMEOUT_MS = 60_000
:434  case 'download': {
:437    const controller = new AbortController()
:438    const timer = setTimeout(() => controller.abort(), DOWNLOAD_FETCH_TIMEOUT_MS)
:439    try {
:440      const response = await fetch(event.url, { signal: controller.signal })
:442      if (!response.ok) return fail(`下载失败：HTTP ${response.status}`)
:445      saveBlob(await response.blob(), event.filename ?? 'download')
:446      return ok(`已触发下载 ${event.filename ?? event.url}`)
:447    } catch (error) {
:448      if (controller.signal.aborted) return fail(`下载失败：60 秒内未取回文件（已中止本次请求），未写入任何文件`)
:450      throw error
:452    } finally { clearTimeout(timer) }
```

1. **超时文案是否如实？（攻击：「未获得数据」类措辞回归 / 含糊表述）** 未破防。文案为「下载失败：60 秒内未取回文件（已中止本次请求），未写入任何文件」——① 不复用旧措辞；② 明确「已中止」+「未写入任何文件」。攻击是否可构造**不实**场景：`abort` 只能发生在两处 `await` 期间（`fetch` 派发期、`response.blob()` 读体期）——两处都尚未把 blob 交给 `saveBlob`，故「未写入任何文件」为真；`saveBlob` 是同步函数（`api/http.ts#saveBlob`），计时器回调（宏任务）不可能在同步段内插入 ⇒ 不存在「已写入却说未写入」的窗口【推断，依据 JS 任务队列语义 + `saveBlob` 同步性】。
   - 注记：`throw error`（`:450`）保留既有「非 abort 网络错误冒泡」语义，且**去向已查明**：`runUiEvent` 自身有外层 try（`workspace.ts:357`）→ catch（`:479-481`，`return fail('ui_event 处理失败：…')`），故本分支重抛的非 abort 错误被如实转成 `fail` 结局（**批 A 未新增任何逃逸路径**）；`dispatchUiEvent` 的两个调用点中，路由 3（`:862`）自带 catch（`:861-870`），路由 2（`:738`，`runWorkspaceAction`）自身无 try/catch（见下条附带观察）。**在本批改动面上未发现会逃逸成未处理拒绝的路径**，与报告 §2.5(a) 的「保持既有语义」自述一致【实测·静态】。
   - **附带观察（INFO，既有面、非批 A 引入）**：`runWorkspaceAction`（`utils/frontend-tools.ts:695-738`）**自身无 try/catch**（`:861-870` 的 catch 属 `runUiEventAction`），而 `dispatchUiEvent`（`workspace.ts:346-351`）在 `runUiEvent` 之后还有 `workspace.touch`（`:348`）与 `emitWorkspaceEvent`（`:350`）两步**不在任何 try 内** ⇒ `frontend-tools.ts:362-365` 的注释断言「dispatchFrontendTool 按契约不 reject」对路由 2 并非密不透风。本批未触碰 `runUiEvent`/`dispatchUiEvent` 的异常边界，仅登记供后续批次参考【实测·静态】。
2. **signal 中止后状态有无残留？** 未破防。`controller` / `timer` 均为**函数局部**：`finally { clearTimeout(timer) }`（`:451`）保证计时器不在返回路径上存活；controller 随作用域失引被回收；分支内**无**共享 ref / store 写入（`saveBlob` 只在成功路径）。grep 该分支无任何模块级或 store 状态被写（对比 `refresh_*` 分支只调 `notifyWorkspaceChanged`）【实测·静态】。
3. 附带核查 W-09 两处文案（`:455-470`）：改为「已记录刷新意图…当前没有任何页面订阅该事件并重新拉取数据…」，与报告 §2.5(b) 的实读依据（订阅者仅 `stores/ai.ts` 的 chip/提示条，无页面 reload）一致；我未重复验证订阅者清单（报告已给 grep 结论，属【旁证】）。

**结论：未破防。**

### S2 小结

| 条 | 结论 | 关键证据 |
|---|---|---|
| a 即发即回 | 未破防 | `ExportWizardView.vue:1106-1122`、`:778-794`、`:913-950`；产物 `ExportWizardView-BOaQZEzH.js` |
| b 防重复 | 未破防 | `:226/745-749/760/779/789`；`rv/repro-inflight.mjs` 实测无残留/无未处理拒绝 |
| c 前置校验 | 未破防（表述修正） | `:985-1007` vs `git show HEAD`；`frontend-tools.ts:842-848` |
| d 去吞异常 | 未破防（附澄清 N-6） | `:728-732`；`frontend-tools.ts:839-848/861-870` |
| e 文案锁 | **破防（窄洞，低危）** | 测试文件 `:48` 与 `:74` 同源常量可被硬编码 1 穿过 |
| f workspace 超时 | 未破防 | `workspace.ts:327/434-454` |

---

## S3 工作树完整性对账

### S3-1 `git -c core.quotepath=false status --porcelain`【实测】

```
 M backend/src/main/java/com/example/configmgr/ai/config/AiProperties.java
 M backend/src/main/java/com/example/configmgr/ai/gate/ConfirmGate.java
 M docs/M2-排期计划.md
 M docs/requirements.md
 M frontend/src/stores/workspace.ts
 M frontend/src/utils/frontend-tools.ts
 M frontend/src/views/ExportWizardView.vue
?? backend/src/test/java/com/example/configmgr/ai/gate/ConfirmGateFrontendTimeoutTextTest.java
```

**恰好 8 项**（7 修改 + 1 未跟踪新增），与清单逐项一致；无清单外文件、无删除、无重命名。`docs/M2-排期计划.md` 属另一并发写入方（R-95~R-99 追加 9 行），不在本复核范围（但见 S4/N-8 的连带提示）。

### S3-2 `git diff --stat` / `--numstat` 与报告自述对账【实测】

| 文件 | 报告自述 | 实测 | 判定 |
|---|---|---|---|
| `ExportWizardView.vue` | 改动 165 行：**+152 / −13** | **+144 / −21**（合计 165） | 合计对，**拆分不符** |
| `ConfirmGate.java` | +12 / −2 | +12 / −2 | ✅ |
| `AiProperties.java` | +5 / −2 | **+6 / −1**（合计 7） | 合计对，**拆分不符** |
| `ConfirmGateFrontendTimeoutTextTest.java` | 新增 **123 行** | **126 行** | **不符**（差 3 行） |
| `workspace.ts` | +40 / −4 | **+37 / −7**（合计 44） | 合计对，**拆分不符** |
| `frontend-tools.ts` | +15 / −2 | **+11 / −6**（合计 17） | 合计对，**拆分不符** |
| `requirements.md` | 只追加、内容零删 | +1 / −1（同一行被替换 = 行内追加注记） | ✅ 语义一致（diff 层面必然计 1 删） |
| `M2-排期计划.md` | +9 | +9 | ✅（范围外） |
| 合计 | 220/38（由分项求和） | **+220 / −38** | ✅ 合计一致 |

结论：**改动集合与总量完全对账**（220 增 / 38 删），仅**分项 +/− 拆分与新增测试文件行数**存在计数偏差（属报告笔误级，不影响改动内容判定）。另经 `git diff -U0` 逐行复核 `frontend-tools.ts`：**确为纯注释改动（0 行代码变更）**，与报告 §2.6 自述一致【实测】。

### S3-3 改动内容与报告自述逐处对账（抽查全部 8 项）【实测】

- `ExportWizardView.vue`：`git diff -U0` 全量比对 ⇒ 新增在途集合（`:219-226`）、`startDownload`（`:728-732`）、`downloadOne` 返回结局 + 判重（`:740-765`）、`startDownloadInBackground`（`:767-794`）、两处调用点注释（`:804`/`:829`）、`downloadingLabel`（`:964-973`）、`preflightDownload`（`:975-1007`）、执行器改写（`:1096-1122`）。**无报告未提及的行为改动**；`downloadChecked`/`downloadAll` 的 diff **仅新增两行注释**（见 N-2）。
- `ConfirmGate.java`：`expire` 的 javadoc 分场景（`:285-296`）+ 前端工具分支文案（`:310`），确认门分支（`:309`）与帧出口（`:314-319`）未动。✅
- `AiProperties.java`：`Hitl#timeout` javadoc（`:118-126`）补两场景语义 + 指向新测试。✅
- `workspace.ts`：常量（`:327`）、`download` 分支（`:434-454`）、`refresh_defs`/`refresh_data` 文案（`:455-470`）。✅
- `frontend-tools.ts`：纯注释（`runWithBudget` 论证改写 + `timeoutOutcome` 口径改写 + 登记「已知过度保守」）。文案与常量未动。✅
- `docs/requirements.md:108`：行内追加注记，原文逐字保留（含「…、下载 |」原表述留痕）。✅

**S3 判定：PASS（改动集合与总量对账无误；仅报告计数细节有偏差）。**

---

## S4 旧口径残留扫（全仓）

扫描命令与结果【实测】（`grep -rn`，排除 `node_modules/`、`frontend/dist/`、`backend/target/` 的二进制命中后仍列出）：

### S4-1 「未获得数据」命中清单

| # | 位置 | 性质 | 是否应留 |
|---|---|---|---|
| 1 | `backend/.../ai/gate/ConfirmGate.java:293` | **新增 javadoc 引旧措辞**（说明为何改） | 留（解释性） |
| 2 | `backend/.../ai/gate/ConfirmGate.java:441` | javadoc：取消分支「结果文本用机器可读前缀标记"未获得数据"」 | 留（**另一分支**，语义正确） |
| 3 | `backend/.../ai/tools/AiTools.java:481` | javadoc：取消终态「模型收到"用户取消，未获得数据"后自行收尾」 | 留（取消分支） |
| 4 | 新增测试 `ConfirmGateFrontendTimeoutTextTest.java:28,29,63,79` | 回归锁内的旧措辞**对照**（含 `doesNotContain`） | 留（锁的必要组成） |
| 5 | `frontend/src/utils/frontend-tools.ts:169` | 历史论证注释（报告 §2.2 已列） | 留 |
| 6 | `frontend/src/views/ExportWizardView.vue:772,1102` | **本批新增**注释引旧措辞（issue#6 背景） | 留 |
| 7 | `docs/requirements.md:108` | 本批追加注记引旧措辞 | 留 |
| 8 | `docs/M2-排期计划.md:287`（=R-95 行） | 另一任务产物引旧措辞 | 留（范围外） |
| 9 | `docs/03-技术方案文档-v2.1.md:264` | **口径文档仍按旧文案描述**（与改后代码不符） | 报告已登记【待裁决】 |
| 10 | `docs/spike/SP-01ab-决策卡.md:130` | 决策卡旧口径 | 已裁决保留 |
| 11 | `spike/sp01ab/.../SpikeToolCallingManager.java:130` | PoC 源码**实际产出**旧文案 | 已裁决保留（历史快照） |
| 12 | `spike/sp02/.../ToolCallExecutor.java:94` | 同上 | 已裁决保留 |

### S4-2 「未在 120 秒」/「未在 N 秒内回传执行结果」命中清单

`未在 120 秒`：**零命中**（该串为 issue 报告期描述，未进入仓库）。`未在 .* 秒内回传执行结果`：命中 = #4（测试内对照）、#11、#12 及 **`backend/target/{classes,test-classes}` 旧 `.class` 二进制残留**（`spike/…/target` 同理，构建产物，非源）。

### S4-3 与清单预期（「应只剩四处」）的差异结论

清单预期「只剩 ①`docs/03-…v2.1.md` ②`docs/spike/SP-01ab-决策卡.md` ③spike PoC 源码 ④S44c 历史证据」。实测差异：

- **S44c 历史证据**（`docs/evidence/S44c-AI面板与出口验证.md:141,171`）命中的是旧 **ack**「已触发下载…」，**不含**「未获得数据」/「未在 120 秒」⇒ 在本扫描的两个模式下 **0 命中**（清单对 S44c 的预期不适用）。
- **额外命中 8 处**：均为 本批新增的解释性引文（#1/#6/#7）、同批新增测试的对照字面（#4）、**其他分支**的合理用法（#2/#3）、既有历史论证（#5）、范围外并发产物（#8）。
- **判定**：**无任何「生产代码仍产出旧措辞」的残留**（唯一的实际产出点是 spike PoC 历史快照 #11/#12，已裁决保留）。清单的「只剩四处」应精确化为「**live 产出面**只剩 spike PoC + 文档口径描述（03 文档 / SP-01ab 决策卡）」，其余为引文。
- **仍有真实缺口 1 处（列为新发现 N-3）**：`backend/.../ai/tools/AiTools.java:483-484` 写「…即 `FRONTEND_TIMEOUT`（**同一条"未执行"语义**）」，**与改后代码直接矛盾**（`ConfirmGate.java:310` 现在说前端工具超时 = 结局未知）。本批以「陈旧注释清理」为由改了 `AiProperties` 的同款一刀切结论（报告 §2.3），**却漏了 `AiTools` 这一处同款**。

---

## 总评

### **带条件 PASS**

- **主交付全部独立复现通过**：S1 四道门禁（typecheck / build / check-docs 6-6 / 后端 23-0-0）逐命令复跑全绿，且经**字节码常量池**证明测试跑的是改后代码（不存在陈旧 class 掩盖）；S3 改动集合（8 项）与总量（+220/−38）完全对账；S2 的 a/b/c/d/f **五条攻击均未破防**，每条都有 文件:行号 级证据。
- **条件 = 以下新发现（无高危、无阻断）应在提交前或随批 B/C 处置**；其中 N-1/N-2 是**本批自己写下的文案/原则**的瑕疵，建议提交前小改（改动面各 ≤3 行），N-3 建议同批顺手（同属「陈旧注释清理」理由）。

### 破防项

| 编号 | 破防点 | 级别 |
|---|---|---|
| S2-e | 文案回归锁**未真正锁住「N 取配置值」**：`ConfirmGateFrontendTimeoutTextTest.java:48` 的 `TIMEOUT_SECONDS=1` 与 `:74` 的断言字符串**同源**，构造「硬编码 1」的实现可全绿通过。当前实现无缺陷（`ConfirmGate.java:310` 实读用入参 `timeoutSeconds`） | 低 |

---

## 新发现问题分级

| 编号 | 级别 | 问题 | 证据 | 建议 |
|---|---|---|---|---|
| **N-1** | 低（本批新增文案的准确性） | 在途未重复发起的 ack 文案在**在途目标是别的文件/打包**时会误导：`:1114-1116` 恒写「页面上已有一次下载在进行中（`<label>`），**本次未重复发起**」。当 `busy` 指向 `A 的下载`/`勾选项打包下载` 而请求的是 `B` 时，B 从未被发起，措辞却是「未**重复**发起」，且紧跟「无需重试」⇒ 模型可能据此认为 B 已在下载、不再补发，用户实际拿不到 B。`downloadingLabel`（`:965-973`）虽标出了在途目标，但句子主语是「任务 #N 的 **B** 导出文件」 | `ExportWizardView.vue:1112-1117`【实测·静态】 | 在途目标 ≠ 请求编码时改为「本次**未发起** `B` 的下载（页面上正在进行的是 `<label>`）…」；或对非同码直接走「发起」分支并把「页面上另有下载在进行」降为提示 |
| **N-2** | 低（自述原则未贯彻到底） | `downloading` 复位改「身份判定」只落在 `downloadOne`（`:761-763`）与 `startDownloadInBackground`（`:790-792`）；`downloadChecked`（`:817`）与 `downloadAll`（`:842`）仍是**无条件** `downloading.value = null`。报告 §2.1(c) 给出的理由是「AI 后台下载与用户下载可交错，无条件复位会把别人的在途状态抹掉」——该理由**同等地适用于这两处**（diff 显示这两处仅新增注释）。后果：AI 在途下载 X 时用户点打包下载 → 打包结束把 `downloading` 抹成 null ⇒ 执行器判重条件 `busy !== null`（`:1113`）被旁路（同码仍被 `inFlightDownloads` 拦住，故不产生重复弹框，只丢失页面态指示、并对**异码**放开并发） | `ExportWizardView.vue:817,842`；`git diff -U0`【实测】 | 两处改为 `if (downloading.value === 'checked'/'all') downloading.value = null` |
| **N-3** | 低（文档与代码矛盾，且本批理由覆盖到它） | `AiTools.java:483-484` 仍写前端工具「长时间不提交即 `FRONTEND_TIMEOUT`（**同一条"未执行"语义**）」，与改后 `ConfirmGate.java:310`（前端工具超时=结局未知）矛盾。本批已按同一理由改了 `AiProperties.java` 的同款结论，**漏改此处** | `AiTools.java:483-484` vs `ConfirmGate.java:310`【实测·静态】 | 同批顺手改写（指向 `ConfirmGate#expire` 分场景口径） |
| **N-4** | 低（范围外，已由报告登记） | S4 清单「只剩四处」与实测不符：额外 8 处命中均为**引文/对照/其他分支**；S44c 在本扫模式下 0 命中（它命中的是旧 ack）。**无生产代码旧措辞残留** | 本报告 S4 全表【实测】 | 清单口径精确化为「live 产出面 = spike PoC；文档口径 = 03 文档 / SP-01ab 决策卡」 |
| **N-5** | 低（可验证性缺口） | 新增测试文件**未跟踪** ⇒ `check-docs.mjs` 断言⑤（按 `git ls-files` 全量扫**内容**）**当前扫描不到它**；报告 §4 把它算作「第二道独立命名纪律门禁」的覆盖对象，严格说不成立（仅外部扫描器覆盖）。文件内容为纯 ASCII 文件名 + 中文 javadoc，未见命中禁则的字面【推断】 | `scripts/ci/check-docs.mjs:83/455-466`（`git ls-files`）；`git status` 显示 `??`【实测】 | 提交（staged）后 ⑤ 自动覆盖；若提交前要门禁，可先 `git add` 再跑（本复核禁用 git 写操作，故未执行） |
| **N-6** | 中（链路级事实澄清） | 「确定性失败 ⇒ `ok:false`」的表述**只对前端契约层对象成立**：回灌 POST 只送 `result` 文本（`stores/ai.ts:895-899`），后端 `ConfirmGate#submitFrontendResult:477` 对非取消回灌**硬编码 `frontendToolResult(pending, true, result)`** ⇒ 模型侧帧 `ok` 仍为 true，失败信息只靠文本「执行失败：…请提示用户手动重试」。此为**既有行为、本批未触碰**（同类已被 R-97/W-05 登记在后端工具面），但报告 §2.1f 第 3 点的写法易被读成「帧也 ok:false」 | `stores/ai.ts:1504,1510`；`ConfirmGate.java:477`；`frontend-tools.ts:842-848`【实测·静态】 | 报告加限定词「（前端契约层对象字段；回灌帧仍是 ok=true）」，或随 R-84/R-97 一并设计 |
| **N-7** | 低（已由报告登记，复核确认成立） | 前端本地预算超时文案对下载类**现在恒为过度保守**：即发即回后，`download_export_file` 的超时结局只可能落在**前置校验**期间（下载尚未发起），而文案说「该操作可能仍在后台继续，文件也许已经下载到浏览器的下载目录…不要直接重复触发下载」（`frontend-tools.ts:416-420`）⇒ 可能劝阻一次本可成功的重发。方向安全（不谎报），报告已在 `frontend-tools.ts:406-408` 与 §5.4 登记「登记不修」 | `frontend-tools.ts:406-420`；`ExportWizardView.vue:1106-1122`（无 await 链路）【实测·静态】 | 随 R-84/R-99 收窄；或按工具名前缀区分「已发起/未发起」两种超时文案 |
| **N-8** | 提示（范围外连带） | `docs/M2-排期计划.md:287`（R-95 行）状态仍写「**施工中（批 A · 先做）**」。批 A 现已在工作树完成并通过本复核，提交时该状态需要与实际完成度对齐（报告 §5.9 已提示，此处再确认一次） | `git diff docs/M2-排期计划.md`【实测】 | 由提交官/登记方决定是否改状态 |

### 未做 / 限制（如实登记）

- **未做前端运行时端到端复现**（真浏览器 + 真后端 + 真 AI 会话、真弹保存框）。故 S2-a 的「运行时时延」结论为【实测·静态】+【推断】（产物结构 + 控制流实读 + 结构等价复现），**不是**【实测】。与报告 §3.6 的登记一致。
- **未跑全量 `mvn test`**（按清单口径只跑四类；亦避免长时间占用）。四类 23 用例全绿 + 字节码证据可支撑 `ConfirmGate`/`AiProperties` 改动的编译与行为正确性。
- **未跑 `scripts/verify-e2e.ps1`**（需完整栈自启；报告 §3.6 已论证其下载类用例只打 REST 端点、`stub-routes.json` 中 `download_export_file` 无路由驱动）。
- **未重跑「植入对照」**（报告 §3.3/§3.4 的植入对照要求改仓库文件，本复核禁止）；以字节码常量池证据（S1-5）替代对其结论的独立支撑。
- **未验证** W-09 的「无页面订阅 `workspace_changed` 去 reload」订阅者清单（采信报告 §2.5(b) 的 grep 结论，属【旁证】）；未验证 `spike/`、`docs/evidence/` 中已裁决保留项的裁决依据（按清单要求「列出现状清单即可，不改」）。

**〔分件 3／3〕** 原件：`<LOCAL_WORK>/wait-fix-a/收尾报告.md`

# 等待治理批 A · 收尾修报告（复核 S2-e / N-1 / N-2 / N-3）

- 施工子智能体：DeepSeek V4 Flash（后台独立运行）
- 仓库：`<REPO_ROOT>`（分支 `main`，进入时 `HEAD=6ee96c3`，工作树 = 批 A 在制品 8 项 + 新增测试文件 1 项）
- 依据：`<LOCAL_WORK>\wait-fix-a\复核报告.md`（S2-e 破防项；新发现 N-1 / N-2 / N-3）+ 主智能体本次四项施工卡
- 纪律：**零提交**、未 `--no-verify`、未 `git add`、未触碰 `docs/M2-排期计划.md`（另一并发写入方产物）
- 断言标注：【实测】= 本次真跑命令/真读产物；【实测·静态】= 对源码或构建产物的结构化读取；【推断】= 由实读代码推演

---

## 1. 完成状态总表

| # | 施工卡 | 落点 | 状态 |
|---|---|---|---|
| 1 | 复核 S2-e：窄洞加固（注入非 1 且非默认值 + 参数化） | `backend/src/test/.../ConfirmGateFrontendTimeoutTextTest.java` | **完成**（注入 {2,3}，秒数由产物正则读回；参数化 2 例 + 确认门 1 例；植入对照双向，§4.1） |
| 2 | N-2：`downloading` 复位身份判定补齐两处 | `frontend/src/views/ExportWizardView.vue:817-819`、`:843-845` | **完成**（4/4 复位点全带身份判定，结构复现 §3.6） |
| 3 | N-1：ack 文案分支（同目标保原样 / 异目标如实） | `frontend/src/views/ExportWizardView.vue:1117-1131` | **完成**（三场景文案逐字见 §2.3；含对给定模板的一处必要微调，§2.3.3） |
| 4 | N-3：`AiTools` 前端工具超时注释分场景 | `backend/.../ai/tools/AiTools.java:483-487` | **完成**（确认门=未执行；前端工具=结局未知） |
| 5 | **范围外顺手**（同款陈旧注释，已登记可回退） | `backend/.../ai/gate/ConfirmGate.java:38-40`（类级 javadoc 三结局） | **完成**（见 §2.5；若不接受，回退面 = 1 处注释） |

四项要求 + 1 项范围外顺手，全部落地；仓库外配套件更新 2 件（`ack-lint.mjs`、新增 `close-repro-n1n2.mjs`），见 §3.5 / §3.6。

---

## 2. 逐处前后对照

### 2.1 S2-e：文案回归锁加固（`ConfirmGateFrontendTimeoutTextTest.java`，126 → 159 行）

#### (a) 被攻破的洞（复核原文）：注入值与断言字面**同源**

前（`HEAD` 工作树里本包自己写的版本）：

```java
private static final int TIMEOUT_SECONDS = 1;                                  // :48
private final ConfirmGate gate = gateWithTimeoutSeconds(TIMEOUT_SECONDS);      // :60
...
assertThat(resolved.getResultText())
        .contains("未在 " + TIMEOUT_SECONDS + " 秒内收到前端回执")               // :74  ← 与实现同源
```

断言串由**注入值自己拼出**，故「把 N 硬编码成 1」的实现照样全绿 —— 锁不住「N 取配置值」（复核 S2-e 判定为破防、低危）。

#### (b) 加固后的写法

- 注入值：`@ValueSource(ints = { 2, 3 })`（**非 1、非默认 120、两个互不相同**），参数名 `injectedSeconds`；
- 断言不再拼串，而是**从产出文案里正则读回秒数**再与注入值比较：

```java
private static final Pattern SECONDS_IN_TEXT = Pattern.compile("未在 (\\d+) 秒内收到前端回执");
...
assertThat(secondsInText(resolved.getResultText()))
        .as("文案秒数必须等于注入的 app.ai.hitl.timeout（硬编码常量过不了两个用例）")
        .isEqualTo(injectedSeconds);
```

- 另加一条与文案无关的加固：`verify(this.emitter).frontendToolRequest(entry, injectedSeconds)`（请求帧也带同一配置值）；
- 确认门用例保留（锁「分支措辞不得被连带改掉」），其上限常量更名 `CONFIRM_TIMEOUT_SECONDS`（取值 1，javadoc 说明「该用例与 N 取值无关，取最小值省时」）；
- 类 javadoc 增补「为什么注入两个非 1 值」的论证（防止后人再加回同源写法）。

#### (c) 为什么是 `{2,3}` 而不是施工卡举例的 `42`

【实测·静态】`ConfirmGate#await` 的到期判据是 `deadline = startedAt + timeoutSeconds * 1000L`（`ConfirmGate.java:204,222-225`），且 `confirmTimeoutSeconds()` 有 `Math.max(1, …)` 下限 —— **注入多少就真等多少秒**。取 42 会让本类单跑 42s+（现为 6.1s）。加固的本质是「断言不从注入值拼串 + 两个互不相同的注入值」，`{2,3}` 已使任何硬编码常量在两个用例中**至少一个必然失败**（§4.1 双向对照已证），故用 `{2,3}` 换 40s 运行时不值得。若验收方坚持字面量 42，回退面 = 一行 `@ValueSource`，代价是 +40s。

### 2.2 N-2：`downloading` 复位身份判定补齐（`ExportWizardView.vue`，2 处）

前（`:817` / `:842`，无条件复位）：

```ts
  } finally {
    downloading.value = null
  }
```

后（与 `downloadOne`（`:760-763`）/ `startDownloadInBackground`（`:789-792`）已改处**同形**）：

```ts
  } finally {                       // downloadChecked
    if (downloading.value === 'checked') {
      downloading.value = null
    }
  }
```

```ts
  } finally {                       // downloadAll
    if (downloading.value === 'all') {
      downloading.value = null
    }
  }
```

【实测·静态】源码内 `downloading.value = null` 共 4 处，**4/4 受 `if (downloading.value === <身份>)` 守卫**（结构复现脚本 A 段，§3.6）；`yarn typecheck` / `yarn build` 双绿。理由与复核一致：AI 后台下载与用户打包下载可交错，无条件复位会抹掉**别人**的在途状态并旁路执行器的 `busy !== null` 判重。

### 2.3 N-1：ack 文案分支（`ExportWizardView.vue:1108-1131`）

#### (a) 前（单分支，异目标也读作「未重复发起…无需重试」）

```ts
    const busy = downloading.value
    if (inFlightDownloads.has(defCode) || busy !== null) {
      return `任务 #${taskId.value} 的 ${defCode} 导出文件：页面上已有一次下载在进行中`
        + `（${downloadingLabel(busy ?? defCode)}），本次未重复发起。`
        + '下载由浏览器执行，文件落盘以浏览器下载目录为准；本次调用到此结束，无需重试。'
    }
```

#### (b) 后（按「在途目标是不是本次请求的编码」分两支）

```ts
    const busy = downloading.value
    const requestedInFlight = inFlightDownloads.has(defCode)
    if (requestedInFlight || busy === defCode) {          // ② 同目标：保持现文案
      return `任务 #${taskId.value} 的 ${defCode} 导出文件：页面上已有一次下载在进行中`
        + `（${downloadingLabel(busy ?? defCode)}），本次未重复发起。`
        + '下载由浏览器执行，文件落盘以浏览器下载目录为准；本次调用到此结束，无需重试。'
    }
    if (busy !== null) {                                  // ③ 异目标：如实 + 允许重试
      return `任务 #${taskId.value} 的 ${defCode} 导出文件：本次未发起 ${defCode} 的下载`
        + `（页面正有 ${downloadingLabel(busy)}在进行中），可稍后重试本项。`
    }
    startDownloadInBackground(defCode)                    // ① 空闲：不变
```

判据与旧写法**等价覆盖**（`requestedInFlight || busy === defCode` ∨ `busy !== null` ∨ 其余 = 旧 `… || busy !== null` ∨ 其余），行为面只改文案，未新增/删除发起路径。

#### (c) 三场景文案（**源码求值结果**，非手抄；结构复现脚本 C 段，§3.6）

| 场景 | 产出文案 |
|---|---|
| ① 发起成功（未动） | `已发起下载：任务 #7 的 B 导出文件（内容为在线编辑后的当前表格）。下载由浏览器执行 —— 若弹出保存位置选择框或提示被拦截，请让用户在浏览器侧放行并确认；文件落盘以浏览器下载目录为准。本次调用到此结束，无需重试。` |
| ② 同目标（未动） | `任务 #7 的 B 导出文件：页面上已有一次下载在进行中（B 的下载），本次未重复发起。下载由浏览器执行，文件落盘以浏览器下载目录为准；本次调用到此结束，无需重试。` |
| ③ 异目标·在途为编码 | `任务 #7 的 B 导出文件：本次未发起 B 的下载（页面正有 A 的下载在进行中），可稍后重试本项。` |
| ③ 异目标·在途为勾选打包 | `…本次未发起 B 的下载（页面正有 勾选项打包下载在进行中），可稍后重试本项。` |
| ③ 异目标·在途为全部打包 | `…本次未发起 B 的下载（页面正有 全部打包下载在进行中），可稍后重试本项。` |

措辞纪律（施工卡要求「只对异目标场景允许出现『重试』」）：② 与 ① 均**只**含「无需重试」、去除后无任何「重试」字面；③ 含「可稍后重试本项」且**不**含「无需重试」、不出现「未重复发起」。该纪律已固化进仓库外校验器 `ack-lint.mjs` v2（§3.5）并由结构复现脚本逐条断言。

#### (d) 与施工卡给定模板的一处微调（**请验收方确认**）

施工卡给的 ③ 目标措辞是「…（页面正有 `<在途标签>` **的下载**进行中），可稍后重试本项」，而 `<在途标签>` 由 `downloadingLabel` 给出，三种形态分别是 `A 的下载` / `勾选项打包下载` / `全部打包下载` —— 照抄模板会渲染成**重复的**「页面正有 A 的下载 的下载进行中」/「勾选项打包下载 的下载进行中」（§3.6 首次运行即抓出）。故本包只做一处最小去重：`（页面正有 ${downloadingLabel(busy)}在进行中）`，其余字面量与模板一致（「本次未发起 `<编码>` 的下载」「可稍后重试本项」逐字保留）。若不接受该微调，回退面 = 1 行。

#### (e) 副带改动（措辞纪律的注释随更）

`ExportWizardView.vue:1108-1112` 的执行器上方注释由「…明确『无需重试』，**禁止失败/重试暗示**」改为**按三场景**列出口径（①/②/③ 各自的允许与禁止），避免注释与 ③ 分支的「可稍后重试本项」自相矛盾（纯注释，无行为）。

### 2.4 N-3：`AiTools.java:483-487` 前端工具超时注释分场景

前：

```java
     * 时间上限沿用既有前端通道口径（{@code app.ai.hitl.timeout}，默认 120s）：用户长时间不提交即
     * {@code FRONTEND_TIMEOUT}（同一条"未执行"语义）。
```

后：

```java
     * 时间上限沿用既有前端通道口径（{@code app.ai.hitl.timeout}，默认 120s）：用户长时间不提交即
     * {@code FRONTEND_TIMEOUT}。但<b>不能</b>把它读成"工具未执行"—— 超时的结局语义由挂起的
     * <b>类型</b>决定（见 {@code ConfirmGate#expire}）：确认门等的是人的决策，超时 = 确定未执行；
     * 前端工具等的是<b>前端回执</b>，超时只说明"回执没回来"，<b>结局未知</b>（副作用可能已经发生，
     * 例如表单已提交、文件已落盘）。
```

【实测·静态】同款排查：全后端 `grep -rn "同一条.未执行"` 仅命中本处与 `ConfirmGate.java:39`（后者见 §2.5）；`AiTools.java:39` 的「拒绝/超时以『未执行』语义回填」**限定在「作业发起（确认门）」条目内**，语义正确，未改。

### 2.5 范围外顺手：`ConfirmGate.java:38-40` 类级 javadoc 的同款陈旧结论

前：

```java
 * <li><b>超时</b>：等待超过 {@code app.ai.hitl.timeout}（默认 120s，ADR-2 上限约束）
 * 自动取消：{@code status=TIMEOUT, executed=false}，同样以"未执行"语义回填。</li>
```

后：

```java
 * <li><b>超时</b>：等待超过 {@code app.ai.hitl.timeout}（默认 120s，ADR-2 上限约束）
 * 自动取消：{@code status=TIMEOUT, executed=false}；结果文本按<b>挂起类型</b>分场景（见 {@link #expire}）——
 * 确认门 = "工具未执行"（确定未发生），前端工具 = "回执没回来、结局未知"（副作用可能已发生）。</li>
```

**理由**：N-3 的判据是「本批已按同一理由改了 `AiProperties` 的同款一刀切结论，漏改 `AiTools`」——同一判据在 `ConfirmGate` 自己的类级 javadoc 上也成立（批 A 只改了方法级 `expire` 的 javadoc，`:285-296`）。属纯注释、零行为；若验收方认为超出范围，回退面 = 上述 3 行。**这也带来工作树数字变动**：`ConfirmGate.java` 由批 A 的 +12/−2 变为 +14/−3（§5）。

---

## 3. 验证（命令 + 日志 + 退出码 + 关键输出）

命令均在**最终工作树**（全部改动落定后）执行。

### 3.1 前端双门禁【实测】

```bash
cd <REPO_ROOT>/frontend && yarn typecheck   # EXIT=0  "Done in 4.49s."
cd <REPO_ROOT>/frontend && yarn build       # EXIT=0  "✓ built in 27.48s / Done in 32.46s."
```

### 3.2 后端四类相关测试【实测】

```bash
# 包装脚本 <LOCAL_WORK>/wait-fix-a/mvnf-gate4.sh（Maven 路径含空格）
#   mvn -B -Dtest=ConfirmGateFrontendTimeoutTextTest,ConfirmGateWiringTest,ConfirmGateDeadlockResumeTest,GenerativeFormGateTest -DfailIfNoSpecifiedTests=false test
# 日志 <LOCAL_WORK>/wait-fix-a/close-mvn-gate3.log
```

```
Tests run: 9,  Failures: 0, Errors: 0, Skipped: 0 -- ConfirmGateDeadlockResumeTest
Tests run: 3,  Failures: 0, Errors: 0, Skipped: 0 -- ConfirmGateFrontendTimeoutTextTest（2 个参数化例 + 确认门例，6.06s）
Tests run: 4,  Failures: 0, Errors: 0, Skipped: 0 -- ConfirmGateWiringTest
Tests run: 6,  Failures: 0, Errors: 0, Skipped: 0 -- GenerativeFormGateTest$SubmissionGate
Tests run: 2,  Failures: 0, Errors: 0, Skipped: 0 -- GenerativeFormGateTest$SuspensionGate
Tests run: 24 Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS   MVN_EXIT=0
```

用例总数 23 → **24**（前端工具用例由 1 例变 2 例参数化），耗时 +4s（2+3 秒真到期）。

### 3.3 文档门禁【实测】

```bash
cd <REPO_ROOT> && node scripts/ci/check-docs.mjs    # EXIT=0
# ①②③④⑤⑥ 全 [PASS]；「全部通过：6/6 断言绿。」
# （⑤ 细则：git ls-files 918 个路径、实扫 915、豁免 3；连写组 5/5、分隔组 24/24、负对照 11 全不误伤）
```

### 3.4 脱敏终扫（四模式，带植入对照）【实测】

扫描器：提交官扫描器 `<LOCAL_WORK>/commit-gov/desense-scan.mjs`。

```bash
node desense-scan.mjs <repo> seed                                                    # SEED_EXIT=0
#  正向种子 23/23 命中；负对照 12/12 未误伤；保守过匹配 2/2 已登记
node desense-scan.mjs <repo> realscan <8 个改动文件…>                                  # REAL_EXIT=0（日志 close-scan-real.log）
#  实盘：8 个文件，命中 0 处，按豁免登记放过 0 处
```

覆盖文件（8 个，含**未跟踪的新增测试文件**）：`AiProperties.java`、`ConfirmGate.java`、`AiTools.java`、`ConfirmGateFrontendTimeoutTextTest.java`（新增）、`docs/requirements.md`、`workspace.ts`、`frontend-tools.ts`、`ExportWizardView.vue`。（`docs/M2-排期计划.md` 属另一并发写入方，沿用批 A 口径**不纳入本包扫描**。）

### 3.5 ack 文案纪律校验器（仓库外）——**本次同步升级 v1 → v2**【实测】

```bash
node <LOCAL_WORK>/wait-fix-a/ack-lint.mjs          # REAL_EXIT=0  实盘结论：全绿（0 条违规）
node <LOCAL_WORK>/wait-fix-a/ack-lint.mjs seed     # SEED_EXIT=0  植入对照 通过（5/5 违规措辞被抓）
```

**升级原因（必须登记）**：v1 的规则是「执行器**全区域**不得出现『重试』（除『无需重试』外）」—— 在只存在 ①② 两个场景时成立；N-1 引入合法的 ③ 场景后该规则必然误伤（【实测】加完 ③ 后 v1 立即报 `出现「重试」且不在「无需重试」口径内（重试暗示）`，EXIT=2）。v2 改为**按 `return` 语句分场景**判定，并新增两条更强判据：③ **不得**出现「无需重试」；任一场景**分支缺失**即报错（防「删掉分支让检查器失明」）。改动记录写在文件头注释里，改动面仅仓库外校验器、不影响交付物。

### 3.6 源码驱动的结构复现（N-1 / N-2，新增）【实测】

```bash
node <LOCAL_WORK>/wait-fix-a/close-repro-n1n2.mjs    # EXIT=0，23 条 [PASS]，0 条 [FAIL]（日志 close-repro1.log）
```

方法论与复核方 `rv/repro-inflight.mjs` 一致（不手写业务逻辑），但更进一步：**分支条件与三个 ack 字面量都从 `.vue` 源码里读出**，再用 `new Function` 求值，断言的是文件里那份文本本身。

- A 段（N-2）：复位点 4 处、4/4 受身份判定守卫；
- B 段（N-1）：7 行状态矩阵全部命中预期分支（空闲⇒①；集合命中/单值同码⇒②；`checked`/`all`/异码在途⇒③）；
- C 段（N-1 措辞）：② 无重试暗示；③ 含「本次未发起 B 的下载」+ 指出在途目标 +「可稍后重试本项」，且**不**含「无需重试」/「未重复发起」；③ 在 `A`/`checked`/`all` 三种在途形态下均无「的下载」重复（§2.3d 的缺陷即由此抓出）。

### 3.7 产物级印证【实测·静态】

`frontend/dist/assets/ExportWizardView-B-W3j6t_.js`（最终构建产物）内实读：`可稍后重试本项` × 1、`本次未重复发起` × 2、`已发起下载` × 1、`在进行中` × 4；`的下载 的下载` 命中 **0**（旧重复措辞不存在）。

### 3.8 未做的验证（如实登记）

- **未做浏览器/后端运行时端到端复现**（真三件套 + 真 AI 会话）——与批 A 同款限制（R-91 前端测试框架未落地，页面执行器无单测挂点）。N-1/N-2 的结论强度 = 【实测】产物结构 + 【实测】源码驱动结构复现 + 【推断】控制流，**不是**真机【实测】。
- **未跑全量 `mvn test`**（按口径只跑四类）。
- **未 `git add`**（见 §6.3 的 N-5 影响）。

---

## 4. 植入对照汇总（3 组，全部先跑对照、再跑实盘）

### 4.1 S2-e 文案锁：硬编码秒数（**正向红 + 反向绿，双向证明**）【实测】

把 `ConfirmGate.java:310` 的前端工具分支改为硬编码 `1` 秒（`未在 1 秒内收到前端回执…`），单跑该测试类：

```
[ERROR] Tests run: 3, Failures: 2, Errors: 0  <<< FAILURE!
  [文案秒数必须等于注入的 app.ai.hitl.timeout（硬编码常量过不了两个用例）]
  expected: 2  but was: 1
  expected: 3  but was: 1
BUILD FAILURE   MVN_EXIT=1      （日志 close-plant-hardcode.log）
```

**反向对照（证明「洞」真实存在）**：保持同一植入（硬编码 1），把注入值也改成 1（= 加固前的同源情形）→

```
[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS   MVN_EXIT=0      （日志 close-plant-samesource.log）
```

即：**加固前的那套断言对该植入是失明的，加固后被两个互不相同的注入值抓住**。随后逐字还原：`grep -rn "未在 1 秒\|PLANTED" backend/src frontend/src` 零命中，`ConfirmGate.java` 回到 `+14/−3`（其中 +2/−1 为 §2.5 的注释），四类测试复跑全绿（§3.2）。

### 4.2 ack 校验器植入【实测】

`node ack-lint.mjs seed` ⇒ `植入对照 通过（5/5 违规措辞被抓）`：① 删「无需重试」并追加「若未成功请重试」（5 条命中）／② 同目标句塞「可稍后重试本项」（2 条）／③ 删「可稍后重试本项」（1 条）／③ 塞「未重复发起」（1 条）／③ 整条分支删掉（1 条，分支缺失兜底）。5 组均**只改措辞、不改分支标记**，逼出规则级判定而非只剩兜底判据。

### 4.3 终扫植入【实测】

`desense-scan.mjs seed` ⇒ `正向种子 23/23 命中；负对照 12/12 未误伤；保守过匹配 2/2 已登记`，随后实盘 0 命中（§3.4）。

---

## 5. 工作树对账

`git status --porcelain`：8 项 `M` + 1 项 `??`（新增测试文件），与批 A 的改动集合相同（本次未新增/删除文件）。

| 文件 | `git diff --numstat`（现） | 批 A 时 | 差异来源 |
|---|---|---|---|
| `backend/.../ai/config/AiProperties.java` | +6 / −1 | +6 / −1 | 未触碰 |
| `backend/.../ai/gate/ConfirmGate.java` | **+14 / −3** | +12 / −2 | §2.5 类级 javadoc 注释（+2/−1） |
| `backend/.../ai/tools/AiTools.java` | **+4 / −1** | 0（未改） | §2.4 N-3 |
| `docs/M2-排期计划.md` | +9 / −0 | +9 / −0 | **另一并发写入方**，本包零触碰 |
| `docs/requirements.md` | +1 / −1 | +1 / −1 | 未触碰 |
| `frontend/src/stores/workspace.ts` | +37 / −7 | +37 / −7 | 未触碰 |
| `frontend/src/utils/frontend-tools.ts` | +11 / −6 | +11 / −6 | 未触碰 |
| `frontend/src/views/ExportWizardView.vue` | **+157 / −22** | +152 / −13 | N-1（+3/−1 文案与注释 + 分支）+ N-2（2 处 × +3/−1） |
| `backend/src/test/.../ConfirmGateFrontendTimeoutTextTest.java` | 新增 159 行（`??`） | 新增 126 行 | §2.1 |

【实测·静态】`ConfirmGate.java` 的植入痕迹已清：`grep -rn "未在 1 秒\|PLANTED"` 零命中。

---

## 6. 新引入的风险 / 变更面 / 遗留

1. **测试类运行时长 +4s（2s → 6.1s）**：注入上限真等到期；若 CI 对单类耗时敏感，可把 `{2,3}` 降为唯一值 + 另一个「请求帧实参」锁（但会退回单值，弱化参数化）。**属取舍，已按「两个互不相同」优先**。
2. **模型可见文案的行为变更（N-1 ③）**：异目标在途时，模型现在读到的不再是「未重复发起…无需重试」，而是「本次未发起 …可稍后重试本项」——**这是修的目的**（旧措辞会让模型以为该文件已在下载而不再补发）。副作用面：模型可能因此稍后再次调用本工具；此时若在途已结束会**真的发起**一次下载（符合用户意图），若仍在途会再次收到同一条 ③ 文案，无自激放大（每次调用都只回一句话、不排队）。
3. **N-5（复核登记）未变**：新增测试文件仍是 `??` 未跟踪 ⇒ `check-docs` 断言⑤（`git ls-files` 口径）**扫不到它**；本包未 `git add`（纪律：零提交、不碰暂存区）。外部扫描器已覆盖该文件（§3.4 的 8 文件清单含它）。**提交时 staged 后 ⑤ 自动覆盖。**
4. **`ack-lint.mjs` 由 v1 升为 v2**（仓库外）：批 A 复核报告 §3.4 引用的「v1 全区域禁重试」结论在 N-1 之后**不再适用**；若日后有人重跑 v1，会看到 EXIT=2（误伤 ③）。文件头已写明版本记录，避免误判为回归。
5. **`docs/requirements.md:108` 的批 A 注记只登记了「同码」场景**（原文：「同一配置在途时回执改为『已在进行中、本次未重复发起』」）——该句对同码场景**仍然准确**，未与代码矛盾；但 ③ 异目标的新口径未登记。本包**未改**该文件（施工卡四项未含、且该文件受「只追加」纪律约束），**列为可选补记（待裁决）**。
6. **`frontend-tools.ts` 的本地预算超时文案（复核 N-7）未动**：仍是「可能仍在后台继续…请勿重复触发」的过度保守口径（批 A 已按「登记不修」写入 `:402-408`）。N-1 让 ③ 场景允许重试，与该保守口径方向不冲突（一个说「可稍后重试本项」，一个说「不要重复触发」——若同时命中，模型读到的是两句话，本包未做统一，随 R-84/R-99 处理为宜）。
7. **§2.3(d) 对给定模板的微调**：去掉一处重复的「的下载」。若不接受，回退面 1 行。
8. **`ConfirmGate.java` 的数字变动**（+12/−2 → +14/−3）来自 §2.5 的范围外顺手注释；复核报告的「改动总量对账」需按本表更新。
9. **`docs/M2-排期计划.md` 的 R-95 状态行**（「施工中（批 A · 先做）」）**仍未改**（另一并发写入方产物）；批 A 现已在工作树完成并通过复核 + 本次收尾，提交官需与该行状态对齐。

---

## 7. 可复跑清单（供验收方原样重放）

```bash
REPO=<REPO_ROOT>; S=<LOCAL_WORK>/wait-fix-a

cd $REPO/frontend && yarn typecheck && yarn build                    # 3.1
bash $S/mvnf-gate4.sh $S/verify-mvn.log                              # 3.2（24/0/0）
cd $REPO && node scripts/ci/check-docs.mjs                           # 3.3（6/6）
node <LOCAL_WORK>/commit-gov/desense-scan.mjs $REPO seed                  # 3.4 植入
node <LOCAL_WORK>/commit-gov/desense-scan.mjs $REPO realscan <8 个文件…>    # 3.4 实盘
node $S/ack-lint.mjs && node $S/ack-lint.mjs seed                    # 3.5
node $S/close-repro-n1n2.mjs                                         # 3.6
```

生产物证据：`frontend/dist/assets/ExportWizardView-B-W3j6t_.js`（§3.7）。
