> **历史分析 / 过程证据 — 非现行基线，非验收结论**
>
> - 归档：2026-10-26 · `docs/evidence/history-recovery-2026-10-26/wire-only/`
> - 来源：Kimi Code 会话 `<KIMI_SESSION>` / 子 agent `agent-533` 的 wire 末条 assistant 文本（只读流式解析；非原始会话转写）
> - 原始体量：9466 B / 7611 字符 / 95 行；行指纹覆盖率（仓内 + 落盘语料）3.0%
> - 类别：**施工复核**
> - 脱敏：路径 1 / 会话标识 0 / 身份 0 / 密钥 0
> - 正文为该条消息的完整文本，未删段、未改写结论；仅脱敏与行尾空白规整。当时结论不代表当前代码状态。
## 核验结果（逐条）

文件：`<REPO_ROOT>\frontend\src\stores\ai.ts`
HEAD 核对：`git rev-parse --short HEAD` = **296ffe2** ✅（该文件最近一次改动为 fc6e5a2，与 HEAD 一致可用）

| # | 锚点 | 实测行号 | 判定 |
|---|---|---|---|
| 1 | 文件总行数 1908 | `wc -l` = 1908；末行 `:1908` = `})` | **PASS** |
| 2 | migrateMirror :193/:200-201/:203 | 函数体 **:168-210**（:`193-206` 为其内子块，非函数首行） | **PASS（子块）** |
| 3 | sanitizeMirrorResidues :227-242 | **:227-242** 精确命中 | **PASS** |
| 4 | expireResidueToolParts :246-258 | **:246-258** 精确命中 | **PASS** |
| 5 | writeMirror :281-306 | **:281-306** 精确命中 | **PASS** |
| 6 | isFrameDecidedPendingStatus :400-404 | **:400-404** 精确命中 | **PASS** |
| 7 | :420 notice / :440 notice 注释 / :451 activeForm | 全部命中 | **PASS** |
| 8 | pendingToolCall getter :466-472 | getter 实体 **:461-476**；倒序扫在 **:462**，`:466-472` 只是"命中判定"段 | **FAIL（行号偏移）** |
| 9 | pendingConfirm getter :477-481 | 注释 :477、函数 **:478-481** | **PASS** |
| 10 | submitFrontendToolResult 兜底 :1222-1224 | **:1222-1224** 精确命中 | **PASS** |
| 11 | hasDecidedPendingResidue :1235 | 定义 **:1235-1244** | **PASS**（调用点见下） |
| 12 | openGenerativeForm :1252-1263 / :1262 | **:1252-1254** 签名；**:1262** 命中 | **PASS** |
| 13 | cancelGenerativeForm :1336-1354 | **:1336-1354** 全部命中（:1341/:1344/:1345/:1346/:1352） | **PASS** |
| 14 | stop() :1368-1372；resetSession :1433 | **:1368-1372** 命中；**:1432-1433** 命中（:1433 = `this.activeForm = null`） | **PASS** |
| 15 | 跨宿主已决终帧守卫 :1569-1574 | **:1569-1574** 精确命中 | **PASS** |
| 16 | 三 case 去 GF 前缀守卫 | confirm_request **:1615-1617**；frontend_tool_request **:1636-1641**；suspended **:1688-1690** | **PASS** |
| 17 | frontend_tool_result 清 activeForm :1665-1673 | **:1665-1673** 命中，**:1672** = `this.activeForm = null` | **PASS** |
| 18 | done :1714-1718 / error :1731-1734 | 两处均精确命中 | **PASS** |
| 19 | applyPendingTerminal :1765-1780 | **:1765-1780** 全命中（:1767/:1774/:1775/:1776/:1777） | **PASS** |
| 20 | applyFrontendToolTerminal :1801-1820 | **:1801-1826**；:1810 判定、:1813-1814 命中 | **PASS** |
| 21 | runFrontendTool 轮次句柄比对 :1856-1885 | 函数 **:1852-1906**；捕获 :1859、比对 :1883-1885 | **PASS** |
| 22 | restoreSuspendedSnapshot :765-811 | 函数实体 **:725-817**；`:765-811` 仅为内部"重建挂起卡"循环 | **FAIL（行号偏移）** |
| 23 | DERIVE_FRAME_TYPES :267-279（11 帧型） | **:267-279**，计数 = 11 | **PASS** |
| 24 | suspended case 遍历口径 | :1686 find / :1688-1690 已决 break / :1691 suspended=true / :1695 applyFrameToParts 全命中 | **PASS** |

### 关键原文摘录

**#2** `:193 const pending = message.pendingCall` → `:200-201 // 待决条目只供 kind2 与 requiresAction 两维；status 一律以 run.status 派生为权威 //（存量 v1 镜像 toolRun 已终态、pendingCall 仍 pending/approved 时不得被覆盖）` → `:203 requiresAction: pending.status === 'pending'`

**#3** `:231 if (part.kind === 'tool' && part.requiresAction) {` / `:234 requiresAction: false,` / `:235 status: part.status === 'pending' ? 'expired' : part.status`

**#4** `:248 if (part.kind === 'tool' && part.requiresAction) {` / `:254 status: part.status === 'pending' ? 'expired' : part.status`（无 `changed` 记账，返回 void）

**#5** `:292-300` 仅 7 字段：`id / role / content / parts / reasoning / toolRuns / pendingCall`；`:290-291` 明注「`appliedSeqs` 是内部幂等记账，**不入镜像**」——无 appliedSeqs ✅

**#6** `:400-404` 7 态：`succeeded / rejected / blocked / expired / cancelled / unknown / failed` ✅

**#8（实际行号）**
```
461  pendingToolCall(state): PendingToolCall | null {
462    for (let i = state.messages.length - 1; i >= 0; i -= 1) {
467      if (message.role === 'assistant' && message.pendingCall) {
470        if (message.pendingCall.status === 'pending') { return message.pendingCall }
```
倒序扫描在 **:462**，命中条件在 **:467/:470**（草案的 `:466-472` 少了前 5 行结构）。

**#9** `:477` 注释「需要人工放行的确认门（DANGER 工具）。」`:478-481` 函数体，判定 `pending.kind === 'CONFIRM'` ✅

**#10** `:1222 const message = error instanceof Error ? error.message : '前端工具结果回灌失败'` / `:1223 this.notice = message` / `:1224 return { ok: false, message }` ✅

**#12** `:1262 const args = message.pendingCall?.args ?? parseToolArgs(frame.args)` ✅（前有 `:1260 disarmFormExpiry()`）

**#13** `:1341 form.status = 'cancelled'` / `:1344 this.applyPendingTerminal(form.toolCallId, 'cancelled')` / `:1345 disarmFormExpiry()` / `:1346 const outcome = await this.submitFrontendToolResult(form.toolCallId, '', 'http-post', true, form.runId)` / `:1351 if (!outcome.ok && !outcome.duplicate) {` / `:1352 form.error = \`${auto ? '表单已超时，' : ''}取消回灌未送达（${outcome.message ?? '网络异常'}），后端将在超时后自动收尾\`` ✅（:1347-1350 为「表单已被收敛 ⇒ 不写错误」早退守卫）

**#15** `:1569-1572` 帧型准入 = `tool_start | tool_result | frontend_tool_result | confirm_decision`，`&& this.hasDecidedPendingResidue(frame.toolCallId) && !findToolPart(message, frame.toolCallId)` → `:1573 return` ✅

**#16**
- confirm_request `:1615 if (this.hasDecidedPendingResidue(frame.toolCallId)) { break }`（注释 :1612-1614「守卫去 GF 前缀、扩到全部工具」）
- frontend_tool_request `:1636-1641`：守卫前移到写段之前，`break`（不落 requiresAction 段、不置 suspended、不武装 activeForm）
- suspended `:1688-1690`：见 #24

**#17** `:1665 if (this.activeForm?.toolCallId === frame.toolCallId) {` → `:1668-1670` 其他窗口提交提示 → `:1671 disarmFormExpiry()` → `:1672 this.activeForm = null` ✅

**#18** done `:1714-1718`（`disarmFormExpiry(); this.activeForm = null`）、error `:1731-1734`（同形）✅，两者前均 `:1713/:1730 expireResidueToolParts(message)`

**#19** `:1767 const mapped: ToolRunStatus = status === 'approved' ? 'running' : status` / `:1774 && part.status !== 'unknown' && part.status !== 'failed'` / `:1775 && (part.status === 'pending' || part.status === 'cancelled' || part.requiresAction))` / `:1776 item.parts.splice(index, 1, { ...part, requiresAction: false, status: mapped })` / `:1777 deriveProjections(item)` ✅

**#20** `:1810 const generative = part.kind2 === 'FRONTEND' && part.name === GENERATIVE_FORM_TOOL` / `:1813-1814 if (part.requiresAction) { item.parts.splice(partIndex, 1, { ...part, requiresAction: false }) }`；GF 分支 `:1817-1823`（仅 pending/cancelled，`mapped === null` 只收 requiresAction）✅

**#21** `:1859 const controllerAtStart = currentAbort` … `:1883 if (currentAbort !== controllerAtStart) { return }`（:1856-1858 为 D10 说明注释）✅

**#22（实际行号）** `:725 async restoreSuspendedSnapshot(): Promise<void> {`（函数体至 :817）；`:726-738` 取数+身份对账、`:740-743` 空态早退、`:748-756` 已决条目收敛（内含 `:753 hasDecidedPendingResidue`）、`:761-811` 才是在建宿主循环。

**#24** `:1686 const waiting = frame.toolCalls.find((call) => call.kind === 'CONFIRM' || call.kind === 'FRONTEND')` / `:1688-1690` `if (this.hasDecidedPendingResidue(waiting.toolCallId)) { break }` / `:1691 this.suspended = true` / `:1695 applyFrameToParts(message, frame)`（:1681-1685 注「suspended 帧无单数 toolCallId，判据钉在写面条目首个 CONFIRM/FRONTEND」）✅

## hasDecidedPendingResidue 全部调用点（特别标注）

- 定义：**:1235**（`:1235-1244` 函数体，判据读段：`item.parts.some(part => part.kind==='tool' && part.toolCallId===toolCallId && isFrameDecidedPendingStatus(part.status))`）
- 调用点（6 处，全部实测）：**:753**（restoreSuspendedSnapshot 已决条目收敛）、**:765**（同上·重建循环跳过）、**:1571**（跨宿主已决终帧守卫）、**:1615**（confirm_request case）、**:1636**（frontend_tool_request case）、**:1688**（suspended case）
- 仅文档注释提及、非调用：**:376、:385、:759、:1257、:1564**

## 结论摘要

- **24 条中 22 条与草案完全吻合，2 条行号需修正**：
  1. **#8**：`pendingToolCall` getter 实体为 **:461-476**（倒序扫描 :462，命中判定 :467/:470）；草案的 `:466-472` 只覆盖判定段，缺 5 行结构。
  2. **#22**：`restoreSuspendedSnapshot` 函数实体为 **:725-817**；草案 `:765-811` 是函数内部「重建挂起卡」循环子区间（且循环真实收尾在 :811，:813-816 为该函数末尾的续收分支）。
- 另有两处「范围 vs 实体」的轻微偏差（不影响内容核验）：**#2** `migrateMirror` 实体为 :168-210，:`193-206` 是其 pending 子块；**#21** `runFrontendTool` 实体为 :1852-1906，:1856-1885 是句柄比对相关段。
- 语义层面**未发现任何与草案矛盾的实现**：7 态闸、写段前前置守卫、双写点收口（frontend_tool_result 唯一段写点 = applyFrameToParts；applyFrontendToolTerminal 仅剩 409/快照两条非帧路径，见 :1789-1794）、镜像 7 字段无 appliedSeqs、GF/通用 FRONTEND 收口差异均与草案口径一致。
