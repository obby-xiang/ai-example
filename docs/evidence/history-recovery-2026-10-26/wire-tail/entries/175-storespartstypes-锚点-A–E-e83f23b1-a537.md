> **历史分析 / 过程证据 — 非现行基线，非验收结论**
>
> - 归档：2026-10-26 · `docs/evidence/history-recovery-2026-10-26/wire-tail/`
> - 来源：Kimi Code 会话 `<KIMI_SESSION>` / 子 agent `agent-537` 的 wire 末条 assistant 文本（消息序号 m=13；只读流式解析，非原始会话转写）
> - 历史时点：2026-10-12 03:46 → 2026-10-12 03:47（wire 事件时间窗，UTC+8）；当时模型：`DeepSeek/deepseek-flash`
> - 原基线：`296ffe2ae20641ee1fe73a8409e0a0fa851ce1c6`（正文自动摘取的首次基线提及），未经本批复验
> - 原始体量：11529 B / 9253 字符 / 73 行；行指纹覆盖率（本批复算）3.8%（最高单文件 2%：`docs/任务看板.md`）
> - 类别：**过程核验**
> - 与既有件关系：施工报告；报告口径覆盖率 0.02
> - 脱敏：路径 3 / 会话标识 0 / 身份 0 / 密钥 0 / 命名纪律 0
> - 正文为该条消息的完整文本，未删段、未改写结论；仅脱敏、占位符归一与行尾空白规整。当时结论不代表当前代码状态，不得作为当前缺陷或有效裁决引用。
核验完成。全程只读（仅 `git rev-parse`/`git status`/`ls`/`find` + Read/Grep），未访问 `<LOCAL_WORK>\ai-example` 与 `ai-example-main`，未读任何 `.env`，`frontend-tools.ts` 只读。

**仓库基线**：`<REPO_ROOT>` HEAD = `296ffe2ae20641ee1fe73a8409e0a0fa851ce1c6`（与预期一致），工作树干净；行数 `stores/ai.ts` 1908 / `utils/parts.ts` 458 / `types/ai.ts` 442 / `utils/frontend-tools.ts` 1098（前三项与预期一致）。

## A. stores/ai.ts 锚点

| 项 | 判定 | 证据（行号 + 摘录） |
|---|---|---|
| A1 migrateMirror :193-206 | PASS | `:193 const pending = message.pendingCall`；`:200-201 // 待决条目只供 kind2 与 requiresAction 两维；status 一律以 run.status 派生为权威`；`:203 requiresAction: pending.status === 'pending'` |
| A2 sanitizeMirrorResidues :227-242 | PASS | `:231 if (part.kind === 'tool' && part.requiresAction) {`；`:235 status: part.status === 'pending' ? 'expired' : part.status` |
| A3 expireResidueToolParts :246-258 | PASS | `:254 status: part.status === 'pending' ? 'expired' : part.status`（:249-250 注释同口径） |
| A4 writeMirror :281-306 | PASS | `:291 // appliedSeqs 是内部幂等记账，**不入镜像**（Pick 不含它）`；`:292-300` 恰 7 字段：id/role/content/parts/reasoning/toolRuns/pendingCall |
| A5 isFrameDecidedPendingStatus :400-404 | PASS | `:401-403 return status === 'succeeded' \|\| 'rejected' \|\| 'blocked' \|\| 'expired' \|\| 'cancelled' \|\| 'unknown' \|\| 'failed'` |
| A6 :420/:440/:451 | PASS | `:420 notice: null as string \| null`；`:440 * 与 notice 分开，便于面板用不同级别的 Alert 呈现`；`:451 activeForm: null as ActiveGenerativeForm \| null` |
| A7 pendingToolCall :466-472 | PASS | `:462 for (let i = state.messages.length - 1; i >= 0; i -= 1)`；`:470 if (message.pendingCall.status === 'pending')`→`:471 return` |
| A8 pendingConfirm :477-481 | PASS | `:479 const pending = this.pendingToolCall`；`:480 return pending && pending.kind === 'CONFIRM' ? pending : null` |
| A9 submitFrontendToolResult :1178-1226 | PASS | `:1187 const targetRunId = runId ?? this.activeRunId`；`:1189 this.notice = '缺少 runId…'`；`:1200 DUPLICATE_TOOL_CALL_ID`→`:1210 notice 幂等拒绝`/`:1211 return {ok:false,duplicate:true}`；`:1213 FORM_RESULT_REJECTED`→`:1220 return {ok:false,rejected:detail}`（不置 notice）；`:1222-1224` 兜底 `this.notice = message` |
| A10 openGenerativeForm :1252-1263 | PASS | `:1261 // 批2：挂起投影由段派生，args 取调用方已派生好的 pendingCall`；`:1262 const args = message.pendingCall?.args ?? parseToolArgs(frame.args)` |
| A11 cancelGenerativeForm :1336-1354 | PASS | `:1341 form.status = 'cancelled'`；`:1344 applyPendingTerminal(form.toolCallId,'cancelled')`；`:1345 disarmFormExpiry()`；`:1346 await this.submitFrontendToolResult(form.toolCallId,'','http-post',true,form.runId)`；`:1352 form.error = \`${auto?'表单已超时，':''}取消回灌未送达（${outcome.message ?? '网络异常'}），后端将在超时后自动收尾\`` |
| A12 stop/resetSession 清 activeForm | PASS | `:1368-1372`（disarmFormExpiry + `:1371 this.activeForm = null`）；`:1432-1433` 同上（resetSession） |
| A13 frontend_tool_result :1665-1673 | PASS | `:1665 if (this.activeForm?.toolCallId === frame.toolCallId)`；`:1668-1670 if (status==='filling') notice='该表单已在其他窗口提交…'`；`:1672 this.activeForm = null` |
| A14 done/error 清 activeForm | PASS | `:1714-1718` done（`:1717 activeForm = null`）；`:1731-1734` error（`:1733 activeForm = null`） |
| A15 applyPendingTerminal :1765-1780 | PASS | `:1767 status === 'approved' ? 'running' : status`；`:1774 part.status !== 'unknown' && part.status !== 'failed'`；`:1775 (pending \|\| cancelled \|\| part.requiresAction)`；`:1776 splice({...part, requiresAction:false, status:mapped})`；`:1777 deriveProjections(item)` |
| A16 applyFrontendToolTerminal :1801-1826 | PASS | `:1810 const generative = part.kind2 === 'FRONTEND' && part.name === GENERATIVE_FORM_TOOL`；`:1813-1814 if (part.requiresAction) … { ...part, requiresAction:false }`；`:1796-1797 因此下面 GF 分支的"仍处 pending/cancelled 才写 status"守卫**不是死分支**` |
| A17 三处已决守卫 + 轮次句柄 | PASS | `:1569-1574` 跨宿主终帧守卫（tool_start/tool_result/frontend_tool_result/confirm_decision + hasDecidedPendingResidue + !findToolPart）；`:1615-1617` confirm_request、`:1636-1641` frontend_tool_request、`:1686-1690` suspended 三 case 均无 GF 前缀；`:1859 controllerAtStart = currentAbort` … `:1883 if (currentAbort !== controllerAtStart) return` |

## B. `.pendingCall` 全域分布

判定：**PASS（分布准确），但需补 2 处注释行**。

`grep -n "\.pendingCall\b"`（`frontend/src` 全域）共 **21 行 / 27 次出现**：

- `components/AiPanel/AiPanel.vue` 模板 9 行：`:201`(3 次，含 data-testid)、`:204`、`:205`、`:206`、`:208`、`:209`、`:212`(2)、`:233`(2)、`:238`(2)
- `AiPanel.vue` script 2 行：`:482 const pending = ai.messages[i]?.pendingCall`、`:730 …last.pendingCall ? last.pendingCall.status : ''`(2)
- `stores/ai.ts` 代码 6 行：`:193`、`:299`、`:467`、`:470`、`:471`、`:1262`
- `stores/ai.ts` **注释 2 行（草案 19 处未计）**：`:1710`（旧口径直写说明）、`:1799`（"原先直接改 item.pendingCall 的那一半已删除"）
- `types/ai.ts:262`（注释）、`utils/parts.ts:216`（唯一派生写点）

补充：草案 9+2+6+1+1=19 与实际差 2 = 上述两条纯注释行（无语义读点）。另注：`frontend/tests/` 下另有大量 `.pendingCall` 读点（`parts-residue-cleanup.spec.ts`、`parts-reducer.spec.ts`、`frontend-tool-result-terminal.spec.ts`、`restore-reconcile.spec.ts`、`parts-double-write.spec.ts`、`ai-panel-parts-render.spec.ts`），若草案口径要覆盖"全域"需显式声明只算 `src`。

## C. utils/parts.ts 锚点（文件 458 行）

| 项 | 判定 | 证据 |
|---|---|---|
| C1 头注 :1-13 | PASS | `:2 …纯函数层 —— 对话时序包 批2「权威翻转」的唯一写口`；`:6 content / reasoning / toolRuns / pendingCall 四个旧字段是**派生投影**` |
| C2 nextSeg/openStreamPart | PASS | `:29-31 nextSeg = reduce(max …)+1`；`:44-61 openStreamPart`（:46 末尾同类 streaming 段续写；:49-51 先封口交错段） |
| C3 upsertToolPart :90-103 | PASS | `:93-95 if (existing && existing.kind === 'tool') { splice(index,1,{...existing, ...patch}); return }`；`:98-100 if (!createIfMissing \|\| status === undefined) return`；`:101-102 sealTrailingStreamPart + push({kind:'tool', seg:nextSeg…, status})` |
| C4 pendingStatusOf :106-127 | PASS | 显式 6 态 running/succeeded/rejected/blocked/expired/cancelled + `:120 default: … :125 return 'pending'`（注释 :121-124 说明 unknown 违例不可达） |
| C5 toPendingCallProjection :152-164 | PASS | `:156 kind: part.kind2 === 'FRONTEND' ? 'FRONTEND' : 'CONFIRM'`；`:162 status: pendingStatusOf(part.status)` |
| C6 :172-173 + deriveProjections :178-217 | PASS | `:172-173 pendingCall = 该消息**最后一个** requiresAction === true 的工具段…没有则 null`；`:202 if (part.requiresAction)`；`:206-210 if (part.status === 'unknown') assertUnknownTerminalInvariant(part) else pending = …`；`:216 message.pendingCall = pending` |
| C7 applyFrameToParts :285-443 | PASS | `:326-328 const reason = blocked ? '…披露范围内，未执行' : rejected ? (existing?.reason ?? '用户拒绝执行') : null`；`:341-355 confirm_request`（status pending + requiresAction true + 三倒计时字段）；`:372 reason: frame.reason ?? '用户拒绝'`；`:377-390 frontend_tool_request`；`:420-438 suspended`（find CONFIRM/FRONTEND + createIfMissing 占位段） |

## D. types/ai.ts

- D PASS：`:165 export type ToolRunStatus = 'pending' | 'running' | 'succeeded' | 'failed' | 'rejected' | 'expired' | 'blocked' | 'unknown' | 'cancelled'`（9 态原文无误）；`:200 parts: MessagePart[]`；`:225 TextPart` / `:233 ReasoningPart` / `:243 ToolPart`；`:262 为什么落在段上（批2 起）：message.pendingCall 已改为由段派生…`；`:271 export type MessagePart = TextPart | ReasoningPart | ToolPart`；`:280 interface ToolPartPatch`（status 可选，:277-278 注释）；`:361 interface ActiveGenerativeForm`（:356-360 头注）。

## E. reason 域核对

**E1 — frontend-tools.ts 中 `reason` 全部出现**：`:104`（`makeExecution` 入参声明）、`:112-113`（`execution.reason = input.reason`，唯一写者）、`:151/:157/:164/:170`（`coerceArgs` 校验失败原因，局部域）、`:854`（`validated.reason` 拼入 result 文本）、`:903`（`normalizeExecutorReturn` 透传 `value.reason`）、`:945/:952`（`runPageHandler` 透传 `interpreted.reason`）、`:966/:973/:978/:980-983`（`interpretHandlerOutcome` 局部 reason，`:983 return { result: reason, outcome:'failed', reason }`）、`:1091`（defaultExecutor 透传）。
**判定 PASS（但有两点订正）**：`FrontendToolExecution.reason`（声明在 `types/tools.ts:142`，注释「结局补充说明（unknown 的原因 / failed 的原因）」）在生产代码中**无读者** —— `stores/ai.ts:1872-1905 runFrontendTool` 只读 `execution.outcome/result/availability`，且 :1893-1900 的段补丁**不含 reason**，故该字段与 `ToolPart.reason` **不同域、零传播**。订正①：测试里有一个读者 `frontend/tests/frontend-tool-outcome.spec.ts:50 expect(withReason.reason).toBe('执行器预算到期')`（"零读者"应限定为"生产零读者"）；订正②：该字段被丢弃的实际信息损失有限 —— 多数 failed 路径 `result` 已等于 reason（`:983`），只有 `:903` 透传 executor 自设 reason 的情形信息仅存于该字段。

**E2 — AiPanel.vue / parts.ts 之外的 ToolPart.reason 读者**：判定 **PASS（无其他读者）**。全域 `\breason\b` 结果为：唯一 UI 读者 `components/AiPanel/AiPanel.vue:195 <div v-if="part.reason" …>原因：{{ part.reason }}</div>`；`utils/parts.ts:199`（派生进 `toolRuns[].reason`）、`utils/parts.ts:328`（写前回读 `existing?.reason`）。`toolRuns[].reason` 亦无读者（仅 `stores/ai.ts:188 reason: run.reason ?? null` 在迁移时从它读一次）。其余命中（ai.ts:858/928/1085、api/sse.ts、ExportWizardView/ImportWizardView、excel.ts、types/sse.ts 等）皆属 other 域（断流 reason、校验 reason），与 ToolPart.reason 无关。

**E3 — ToolPart.reason 全部写点**：判定 **FAIL（草案少算 1 处）**。实际 3 处：
1. `utils/parts.ts:336 reason,`（值在 `:326-328` 计算，tool_result 支）
2. `utils/parts.ts:372 reason: frame.reason ?? '用户拒绝'`（confirm_decision rejected 支）
3. `stores/ai.ts:188 reason: run.reason ?? null`（**migrateMirror 由 toolRuns 建 tool 段的构造写点**，草案未列）

其余建段点均不写 reason：`stores/ai.ts:679-688`（loadHistory 历史整替 tool 段无 reason 键）、`utils/parts.ts:102`（新建段只 spread patch，reason 仅在上述 patch 中出现）。即"两处"只覆盖**帧路**写点，迁移路漏 1 处。

## 附：模型自证

我在环境中无法自证模型身份（`env` 仅暴露 `KIMI_CODE_REGION_MARKER=off`、OS/`OLDPWD=<DRIVE>/Program Files/Kimi Code` 等，无任何模型标识变量），故只能声明：本次核验由 Kimi Code 的只读探索子代理执行，无法从运行时取证具体模型版本。
