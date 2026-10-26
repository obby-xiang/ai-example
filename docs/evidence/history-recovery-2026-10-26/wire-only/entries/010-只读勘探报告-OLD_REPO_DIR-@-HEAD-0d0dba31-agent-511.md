> **历史分析 / 过程证据 — 非现行基线，非验收结论**
>
> - 归档：2026-10-26 · `docs/evidence/history-recovery-2026-10-26/wire-only/`
> - 来源：Kimi Code 会话 `<KIMI_SESSION>` / 子 agent `agent-511` 的 wire 末条 assistant 文本（只读流式解析；非原始会话转写）
> - 原始体量：42361 B / 31850 字符 / 683 行；行指纹覆盖率（仓内 + 落盘语料）9.5%
> - 类别：**正式独立报告**
> - 脱敏：路径 0 / 会话标识 0 / 身份 0 / 密钥 0
> - 正文为该条消息的完整文本，未删段、未改写结论；仅脱敏与行尾空白规整。当时结论不代表当前代码状态。
# 只读勘探报告：`<REPO_ROOT>` @ HEAD 505f6d4

勘探范围：`frontend/src/stores/ai.ts`（1844 行）、`frontend/src/utils/parts.ts`（436 行）、`frontend/src/utils/frontend-tools.ts`（1098 行）。三个文件已全文读取；仓内其它命中（`types/ai.ts`、`types/sse.ts`、`components/AiPanel/AiPanel.vue`、`tests/*.spec.ts`）一并列出。零写操作、未跑任何构建/测试命令。

---

## 0. 关键结论速览（供红队直接引用）

| 维度 | 记账载体 | 作用域 | 持久化 | 判定口径 |
|---|---|---|---|---|
| 帧级幂等 | `ChatMessage.appliedSeqs?: number[]` | **单条消息**内的 seq 集合 | **不入镜像**（`writeMirror` 的 Pick 不含） | `alreadyApplied` = 集合 `includes`，**无连续性判定** |
| 轮级水位 | `state.runSeq: Record<runId, number>` | **单 runId** 的单调最大 seq | 内存态，**不入镜像、`resetSession` 也不清** | `Math.max(旧值, 新值)`，只用于 `reattach` 的 `lastSeq` |
| 挂起守卫 | `hasDecidedPendingResidue(toolCallId)` | **跨全部消息全部段** | — | 只读 `part.kind/toolCallId/status`，状态集判定 |
| 段定位 | `findToolPart` / `upsertToolPart` | **单条 message 的 parts 数组** | — | 无跨消息回退；未命中即**无条件新建段** |

两维（`appliedSeqs` 消息级 vs `runSeq` 轮次级）**实现上完全独立**：读写在两处代码、无交叉校验；`runSeq` 的更新发生在 `alreadyApplied` 短路**之前**，`markApplied` 发生在旧轮早退**之后**（时序不对称，详见 §4）。

---

## 1. 逐符号：定义 + 调用点

### 1.1 `hasDecidedPendingResidue`

**定义**：`frontend/src/stores/ai.ts:1210`（store action，`this.messages` 全量扫）

```ts
hasDecidedPendingResidue(toolCallId: string): boolean {
  return this.messages.some(
    (item) => item.parts.some(
      (part): boolean => part.kind === 'tool' && part.toolCallId === toolCallId
        && isFrameDecidedPendingStatus(part.status)
    )
  )
}
```

**调用点（源）**

| 位置 | 上下文 |
|---|---|
| `frontend/src/stores/ai.ts:728` | `if (mapped && this.hasDecidedPendingResidue(entry.toolCallId))` → `applyFrontendToolTerminal`（快照非 PENDING 条目收敛） |
| `frontend/src/stores/ai.ts:740` | `if (this.hasDecidedPendingResidue(entry.toolCallId)) { continue }` → 快照重建时跳过已决条目 |
| `frontend/src/stores/ai.ts:1588` | `if (frame.name === GENERATIVE_FORM_TOOL && this.hasDecidedPendingResidue(frame.toolCallId))` → `frontend_tool_request` case 入口守卫 |
| `frontend/src/stores/ai.ts:1635` | `if (waiting.name === GENERATIVE_FORM_TOOL && this.hasDecidedPendingResidue(waiting.toolCallId))` → `suspended` case 入口守卫 |

**调用点（测试）**：`frontend/tests/frontend-tool-result-terminal.spec.ts:431`、`:432`（T3：unknown / failed 段被拦截）
**注释提及**：`ai.ts:369`、`ai.ts:378`、`ai.ts:734`、`ai.ts:1204-1206`、`ai.ts:1232`

> 注：两处守卫均带 `frame.name === GENERATIVE_FORM_TOOL` 前缀 —— 通用 FRONTEND 工具的重投帧**不经**本守卫（`ai.ts:383-386` 自述保护面）。

### 1.2 `isFrameDecidedPendingStatus`

**定义**：`frontend/src/stores/ai.ts:388`（模块私有函数）

```ts
function isFrameDecidedPendingStatus(status: PendingToolCall['status'] | ToolRunStatus): boolean {
  return status === 'succeeded' || status === 'rejected' || status === 'blocked'
    || status === 'expired' || status === 'cancelled'
    || status === 'unknown' || status === 'failed'
}
```

**调用点**：仅 `frontend/src/stores/ai.ts:1216`（`hasDecidedPendingResidue` 内）
**注释提及**：`ai.ts:367-386`（长注释，含「保护面如实申报」段）、`ai.ts:734`、`ai.ts:1637`

### 1.3 `localTerminal`

**定义**：`frontend/src/utils/parts.ts:383` — `applyFrameToParts` 的 `case 'frontend_tool_result'` 内**局部常量**（非函数）

```ts
const localTerminal = existing?.status === 'failed' || existing?.status === 'rejected'
  || existing?.status === 'blocked' || existing?.status === 'succeeded'
  || existing?.status === 'expired' || existing?.status === 'cancelled'
  || existing?.status === 'unknown'
```

**消费点**：`frontend/src/utils/parts.ts:388` — `const status: ToolRunStatus = localTerminal ? (existing?.status ?? 'failed') : fromFrame ?? (frame.ok ? 'succeeded' : 'failed')`
**注释提及**：`parts.ts:379`、`ai.ts:1824`
**测试**：`frontend/tests/frontend-tool-result-terminal.spec.ts:10`、`195`、`201`、`207`、`366`

> 与 `isFrameDecidedPendingStatus` 的关系：**两个独立枚举**，成员集合恰好相同（7 项：succeeded / rejected / blocked / expired / cancelled / unknown / failed），但一个是段级判定（`parts.ts` 局部）、一个是守卫判定（`ai.ts` 模块函数），无共享常量、无双向同源约束。

### 1.4 `applyPendingTerminal`

**定义**：`frontend/src/stores/ai.ts:1713`（store action，跨全部消息就地写段 + `deriveProjections`）

**调用点**

| 位置 | 上下文 |
|---|---|
| `frontend/src/stores/ai.ts:1319` | `this.applyPendingTerminal(form.toolCallId, 'cancelled')`（`cancelGenerativeForm` 本地乐观置位） |

**注释提及**：`ai.ts:1731`、`ai.ts:1750`；**测试**：`frontend/tests/parts-residue-cleanup.spec.ts:185`、`:199`、`:204`、`:209`、`:215`、`:217`、`:220`

### 1.5 `expireResidueToolParts`

**定义**：`frontend/src/stores/ai.ts:244`（模块级函数，非 action）

**调用点**

| 位置 | 上下文 |
|---|---|
| `frontend/src/stores/ai.ts:636` | `expireResidueToolParts(message)` — `loadHistory` 后端历史为空的降级分支（前置 `requiresAction` 段存在性过滤 + `deriveProjections`） |
| `frontend/src/stores/ai.ts:1661` | `expireResidueToolParts(message)` — `handleFrame` 的 `case 'done'` |
| `frontend/src/stores/ai.ts:1678` | `expireResidueToolParts(message)` — `handleFrame` 的 `case 'error'` |

### 1.6 `findToolPart`

**定义**：`frontend/src/utils/parts.ts:77`

**调用点**（**全在 `applyFrameToParts` 内，且都传同一个 `message` 参数**）

| 位置 | 上下文 |
|---|---|
| `frontend/src/utils/parts.ts:304` | `case 'tool_result'`：`const existing = findToolPart(message, frame.toolCallId)`（取 `rejected`/`blocked` 保持） |
| `frontend/src/utils/parts.ts:382` | `case 'frontend_tool_result'`：`const existing = findToolPart(message, frame.toolCallId)`（取 `localTerminal`） |

> 无其它调用点；store 层未导入 `findToolPart`（`ai.ts:20-28` 的导入清单不含它）。

### 1.7 `upsertToolPart`

**定义**：
- 纯函数：`frontend/src/utils/parts.ts:90`
- store 薄包装：`frontend/src/stores/ai.ts:1786`（函数体仅 `writeToolPart(message, patch, createIfMissing)`）
- 导入别名：`frontend/src/stores/ai.ts:27` — `upsertToolPart as writeToolPart`

**调用点（`parts.ts` 纯函数）**

| 位置 | 上下文 |
|---|---|
| `parts.ts:292` | `case 'tool_start'`（status 'running'） |
| `parts.ts:311` | `case 'tool_result'`（BLOCKED > REJECTED > ok 三元） |
| `parts.ts:324` | `case 'confirm_request'`（status 'pending'，`requiresAction: true`） |
| `parts.ts:341` | `case 'confirm_decision'` approved 支（status 'running'） |
| `parts.ts:349` | `case 'confirm_decision'` rejected 支（status 'rejected'） |
| `parts.ts:360` | `case 'frontend_tool_request'`（status 'pending'，`requiresAction: true`） |
| `parts.ts:391` | `case 'frontend_tool_result'`（`status` = localTerminal/fromFrame/ok 三元） |
| `parts.ts:408` | `case 'suspended'`（**`createIfMissing = false`**，不传 status） |

**调用点（store action）**

| 位置 | 上下文 |
|---|---|
| `ai.ts:760` | `restoreSuspendedSnapshot` 快照重建落段（`status: 'pending'`, `requiresAction: true`） |
| `ai.ts:1102` | `confirm()` 乐观置位（running / rejected） |
| `ai.ts:1119` | `confirm()` 409 DUPLICATE 分支（approved 并入 running） |
| `ai.ts:1132` | `confirm()` 提交失败回退（status 'pending'，requiresAction true） |
| `ai.ts:1242` | `openGenerativeForm` schema 非法分支（status 'failed'） |
| `ai.ts:1808` | `runFrontendTool` 开跑落 running（**`createIfMissing = false`**） |
| `ai.ts:1829` | `runFrontendTool` 结局落终态（succeeded / unknown / failed） |

**测试**：`frontend/tests/frontend-tool-result-terminal.spec.ts:24,91,372,386,401,423,427`；`frontend/tests/parts-reducer.spec.ts:25,188,241,252,322`
**类型契约**：`frontend/src/types/ai.ts:274`（`ToolPartPatch` 定义）

### 1.8 `sealTrailingStreamPart`

**定义**：`frontend/src/utils/parts.ts:69`

| 位置 | 上下文 |
|---|---|
| `parts.ts:101` | `upsertToolPart` 内新建段前封口 |
| `parts.ts:276` | `applyFrameToParts` 的 `case 'message_end'` |
| `ai.ts:26` | 导入（store） |
| `ai.ts:1660` | `handleFrame` `case 'done'` |
| `ai.ts:1677` | `handleFrame` `case 'error'` |

测试：`frontend/tests/parts-reducer.spec.ts:24,320,324`

### 1.9 `openStreamPart`

**定义**：`frontend/src/utils/parts.ts:44`

| 位置 | 上下文 |
|---|---|
| `parts.ts:279` | `case 'delta'` → `openStreamPart(message, 'text')` |
| `parts.ts:286` | `case 'reasoning'` → `openStreamPart(message, 'reasoning')` |
| `ai.ts:25` | 导入（store） |
| `ai.ts:962` | `handleChatError` 内补占位文案段：`const part = openStreamPart(message, 'text')` |

测试：`frontend/tests/parts-reducer.spec.ts:23,303,305,312,317`

### 1.10 `alreadyApplied` / `markApplied` / `appliedSeqs`

**定义**

| 符号 | 位置 | 说明 |
|---|---|---|
| `alreadyApplied` | `frontend/src/utils/parts.ts:424` | `Array.isArray(message.appliedSeqs) && message.appliedSeqs.includes(seq)` |
| `markApplied` | `frontend/src/utils/parts.ts:429` | 惰性建数组 + `includes` 去重后 `push` |
| `appliedSeqs` | 字段声明 `frontend/src/types/ai.ts:216`（`appliedSeqs?: number[]`） | 读：`parts.ts:425`；写：`parts.ts:430,431,433,434` |

**调用点**

| 位置 | 上下文 |
|---|---|
| `ai.ts:21` / `ai.ts:24` | 导入 |
| `ai.ts:1534` | `if (seq !== null && alreadyApplied(message, seq)) { return }`（**handleFrame 唯一读点**） |
| `ai.ts:1697-1699` | `if (seq !== null) { markApplied(message, seq) }`（**handleFrame 唯一写点**） |
| `ai.ts:289` | 注释：`appliedSeqs` 是内部幂等记账，**不入镜像**（Pick 不含它） |

测试：`frontend/tests/parts-reducer.spec.ts:19,22,290,292-298,295,340,374`；`frontend/tests/parts-double-write.spec.ts:289-290`

### 1.11 `runSeq` / `runId` / `isCurrentRound`

**`runSeq`**

| 位置 | 方向 | 代码 |
|---|---|---|
| `ai.ts:444` | 定义 | `runSeq: {} as Record<string, number>`（注释：「按 runId 记录 —— 换轮次即从零开始，不跨轮混用序号」） |
| `ai.ts:800` | 写 | `resumeSnapshotStream`：`this.runSeq[runId] = Math.max(this.runSeq[runId] ?? 0, lastSeq)`（`lastSeq = current.archiveMaxSeq`） |
| `ai.ts:1514` | 写 | `handleFrame` T6：`this.runSeq[runId] = Math.max(this.runSeq[runId] ?? 0, seq)`，`runId = frame.runId ?? this.activeRunId` |
| `ai.ts:1022` | 读 | `reattachActive`：`const lastSeq = this.runSeq[targetRunId]` → 传 `{ signal, lastSeq }`（`undefined` = 服务端全量回放） |

**`runId`（`activeRunId` 及局部）关键读写**

| 位置 | 说明 |
|---|---|
| `ai.ts:403` | 定义 `activeRunId: null as string \| null` |
| `ai.ts:598` | `loadHistory` 镜像分支：`this.activeRunId = mirror.lastRunId ?? null` |
| `ai.ts:785` | `restoreSuspendedSnapshot`：`this.activeRunId = current.runId` |
| `ai.ts:1016` | `reattachActive`：`explicit ?? this.busyConflict?.runId ?? this.activeRunId` |
| `ai.ts:1091` | `confirm()`：`const runId = this.activeRunId` |
| `ai.ts:1162` | `submitFrontendToolResult`：`runId ?? this.activeRunId` |
| `ai.ts:1239` | `openGenerativeForm`：`frame.runId ?? this.activeRunId` |
| `ai.ts:1339` / `1348` | `stop()`：取 `activeRunId`、判空 |
| `ai.ts:1402` | `resetSession`：`this.activeRunId = null` |
| `ai.ts:1512-1514` | `handleFrame` 水位记账取值 `frame.runId ?? this.activeRunId` |
| `ai.ts:1540` | `case 'start'`：`this.activeRunId = frame.runId` |
| `ai.ts:518` | `startMirror`：`writeMirror(this.sessionId, this.messages, this.activeRunId)` |

**`isCurrentRound` —— 四个**互不相同**的局部闭包**

| 位置 | 定义 | 语义 |
|---|---|---|
| `ai.ts:811` | `const isCurrentRound = () => currentAbort === controller` | `resumeSnapshotStream` 连接身份 |
| `ai.ts:883` | `const isCurrentRound = () => currentAbort === controller` | `send` 轮次身份 |
| `ai.ts:1037` | `const isCurrentRound = () => currentAbort === controller` | `reattachActive` 轮次身份 |
| `ai.ts:1428` | `const isCurrentRound = () => this.sessionId === nextId && this.messages.length === 0` | `resetSession` 收尾提示守卫（**判据不同族**：会话 id + 空消息表） |

消费点：`ai.ts:821,827,840`（resumeSnapshotStream）、`888,894,926,930`（send）、`1048,1054,1074,1078`（reattachActive）、`1433`（resetSession）。注释：`ai.ts:582`、`ai.ts:1425`。
另有同族但**非同名**的守卫：模块级 `activeRoundHostId`（`ai.ts:49` 定义；`809`/`881`/`1035` 置位；`844`/`936`/`1083`/`1388` 清空）与 `handleFrame` 内的 `ownedByActiveRound`（`ai.ts:1498`）。

### 1.12 `deriveProjections` / `pendingStatusOf` / `pendingCall` / `toolCallId`

**`deriveProjections`** — 定义 `frontend/src/utils/parts.ts:172`（唯一派生写口）

调用点（`ai.ts`）：`23`（导入）、`604`（loadHistory 镜像归一）、`637`（空历史降级）、`676`（历史整体替换）、`768`（快照重建落段后）、`965`（handleChatError）、`1109`/`1126`/`1139`（confirm 三支）、`1250`（openGenerativeForm 失败支）、`1454`（recordUserMessage）、`1472`（appendAssistantMessage）、`1597`（frontend_tool_request）、`1694`（handleFrame 帧末，条件 `DERIVE_FRAME_TYPES.has(frame.type)`）、`1725`（applyPendingTerminal）、`1763`/`1770`（applyFrontendToolTerminal）、`1815`/`1837`（runFrontendTool）
注释：`ai.ts:283,650,1551,1658,1711,1747`；`types/ai.ts:196`；`parts.ts:7,40,123,132`

**`pendingStatusOf`** — 定义 `frontend/src/utils/parts.ts:106`（模块私有）；调用点 `parts.ts:156`（`toPendingCallProjection` 内）；`toPendingCallProjection`（`parts.ts:146`）唯一调用点 `parts.ts:203`（`deriveProjections` 内）

**`pendingCall`** — 字段 `types/ai.ts:201`；派生写点 `parts.ts:210`（`message.pendingCall = pending`）；迁移读点 `ai.ts:192`；镜像写点 `ai.ts:297`；getter `ai.ts:449-464`（`pendingToolCall`，扫全部消息、非 pending 终态跳过继续前扫）；`ai.ts:1237`（`openGenerativeForm` 读 `message.pendingCall?.args`）；视图消费 `AiPanel.vue:200-284`

**`toolCallId`** — 段字段 `types/ai.ts:298`；主要判据读写：`ai.ts:194`（迁移并入）、`656`（历史段 id）、`728/740/749/761/774`（快照）、`1099/1103/1112/1120/1133`（confirm）、`1215`（守卫）、`1588/1635`（case 守卫）、`1717`（applyPendingTerminal findIndex）、`1753`（applyFrontendToolTerminal findIndex）、`parts.ts:78`（findToolPart）、`parts.ts:91`（upsertToolPart findIndex）、`parts.ts:382`（localTerminal 取值）

---

## 2. 完整函数体：判据实现

### 2.1 `isFrameDecidedPendingStatus`（`frontend/src/stores/ai.ts:366-392`，含其注释即判据自述）

```ts
/**
 * T2a-D#2：该 pendingCall.status 是否已是终态。集合**含** `cancelled` —— 本地乐观 cancelled
 * 也算终态：用户取消后同一 toolCallId 不应再被重新挂起，`frontend_tool_request` case 的入口
 * 守卫（`hasDecidedPendingResidue`）据此把后到的同 toolCallId 帧挡在**任何新挂起投影**之外，
 * 防"取消竞态重挂"成一张 filling 死卡。
 * 帧终态覆盖乐观 cancelled 属**写入路径**，两条路各自的口径（F1/F2 收口修后）：
 * - `frontend_tool_result` 帧路：`applyFrameToParts` 把乐观 cancelled 一并算作本地终态
 *   （first-wins），帧 status 只改非终态段；
 * - 非帧路（409 响应体 / 续收快照）：`applyFrontendToolTerminal` 仍按后端终态覆盖
 *   pending/cancelled 段。
 * 两者都与本函数的**入口拦截**语义互不冲突（入口拦截在 `frontend_tool_request` / `suspended`）。
 *
 * 批2（权威翻转）：入参同时接受段侧 `ToolRunStatus` —— 判据改读段（`hasDecidedPendingResidue`
 * 读 `part.status`），两个枚举的终态集合在这里取并集。
 *
 * 批3③a D6(A)：集合纳入 `unknown` 和 `failed`（两者均为终态）—— 否则 unknown 段不被视为
 * 已决，重投帧可再次挂起（挂起卡复活）。
 * **保护面如实申报（不得写成"全覆盖"）**：两条帧路守卫（frontend_tool_request / suspended）
 * 均带 GENERATIVE_FORM_TOOL 前缀，故本扩充实际保护 = ①快照恢复路（restoreSuspendedSnapshot
 * 全条目）+ ②GF 帧路；**通用 FRONTEND 工具的重投帧现状不经本守卫**（handleFrame 直跑
 * runFrontendTool）—— 既有缺口归 ③b（R-141③），本批不闭合。
 */
function isFrameDecidedPendingStatus(status: PendingToolCall['status'] | ToolRunStatus): boolean {
  return status === 'succeeded' || status === 'rejected' || status === 'blocked'
    || status === 'expired' || status === 'cancelled'
    || status === 'unknown' || status === 'failed'
}
```

**判据提炼**：纯函数、**单参 status 白名单**。读的字段只有一个：段上的 `status`。判断「已决」的条件 = status ∈ {succeeded, rejected, blocked, expired, cancelled, unknown, failed}。**不读** `requiresAction`、`name`、`kind2`、`messageId`、`done`、`streaming`；不区分消息角色；不区分 CONFIRM/FRONTEND。反向（判为**未决**）只有 `pending` 与 `running` 两个合法 `ToolRunStatus`（`approved` 非 `ToolRunStatus` 合法值，见 `ai.ts:1715`）。

### 2.2 `hasDecidedPendingResidue`（`frontend/src/stores/ai.ts:1203-1219`，含注释）

```ts
/**
 * T2a-D#3（B3 防御守卫）/ T2a 返修（S3）：该 toolCallId 是否已有**已决**的工具段
 * （终态判定看全部消息，含本次帧即将写入的那条）。调用方必须在写入新挂起语义**之前**用它判定
 * —— 已决 toolCallId 不得产生任何新挂起段（含 requiresAction 段 / suspended / activeForm）。
 *
 * 批2（权威翻转）：判据从 `pendingCall` 投影改读**段**（见下方实现注释）。
 */
hasDecidedPendingResidue(toolCallId: string): boolean {
  // 批2（权威翻转）：判据改读**段** —— 挂起投影不再保留终态（终态段 requiresAction=false，
  // 派生出的 pendingCall 为 null），若继续读投影这个守卫会静默失效（取消竞态重挂复活）。
  return this.messages.some(
    (item) => item.parts.some(
      (part): boolean => part.kind === 'tool' && part.toolCallId === toolCallId
        && isFrameDecidedPendingStatus(part.status)
    )
  )
}
```

**判据提炼**：
- 查域 = `this.messages` **全部消息** × 每条消息的 **全部段**（双层 `some`，命中即真）。
- 读的字段仅三个：`part.kind === 'tool'`、`part.toolCallId === 入参`、`part.status`（交 `isFrameDecidedPendingStatus`）。
- **不读** `requiresAction`、不读 `message.done`、不读 `message.role`、不做「最后一个」之类的序判定 → 只要历史上**任意一条消息的任意一个段**曾落到终态，该 toolCallId 永久被判「已决」。
- 反向：**不**从 `pendingCall` 投影读（投影已不再保留终态）。
- 该守卫**不在** `utils/parts.ts` 层：`applyFrameToParts` 内部**没有任何**已决拦截（已核全文，436 行内只有 `localTerminal` 的 first-wins 保护，作用于同一 toolCallId 的**终态改写**，不是「禁止新建挂起段」）。

---

## 3. 完整函数体：`findToolPart` / `upsertToolPart` 查域

### 3.1 `findToolPart`（`frontend/src/utils/parts.ts:76-80`）

```ts
/** 定位工具段（不建段）。 */
export function findToolPart(message: ChatMessage, toolCallId: string): ToolPart | null {
  const part = message.parts.find((item) => item.kind === 'tool' && item.toolCallId === toolCallId)
  return part && part.kind === 'tool' ? part : null
}
```

**查域答案**：**是**，只在「单条 message 的 `parts` 数组」内查。
- 签名第一参就是 `message: ChatMessage`（调用方传谁就只查谁）；
- 函数体内**没有**任何跨消息循环（只有一次 `message.parts.find`）；
- 返回值可能是**另一条消息**里的同 id 段完全不知情 —— 两个调用点（`parts.ts:304`、`parts.ts:382`）传的都是 `applyFrameToParts(message, frame)` 的那个 `message`。
- 检索条件**不含** `requiresAction`、**不含** `status`（纯 id + kind 定位），也**不排除已决段**。

### 3.2 `upsertToolPart`（`frontend/src/utils/parts.ts:82-103`，含注释）

```ts
/**
 * 工具帧 → `parts` 的**唯一写点**：与旧的 `upsertToolRun` 同语义
 * （按 toolCallId 就地 spread 合并，不产生重复段）。
 *
 * 新建时先**封口末尾的流式段**（工具调用之后到达的正文属于新的一段，seg 单调递增）；
 * `createIfMissing=false` = 只更新语义（suspended 帧不写旧投影时用，避免凭空多出一段
 * 只有 parts 有的卡）。status 省略时保留段上原值，理由见 {@link ToolPartPatch}。
 */
export function upsertToolPart(message: ChatMessage, patch: ToolPartPatch, createIfMissing = true): void {
  const index = message.parts.findIndex((part) => part.kind === 'tool' && part.toolCallId === patch.toolCallId)
  const existing = index >= 0 ? message.parts[index] : undefined
  if (existing && existing.kind === 'tool') {
    message.parts.splice(index, 1, { ...existing, ...patch })
    return
  }
  const status = patch.status
  if (!createIfMissing || status === undefined) {
    return
  }
  sealTrailingStreamPart(message)
  message.parts.push({ kind: 'tool', seg: nextSeg(message.parts), ...patch, status })
}
```

**查域 / 建段答案**：
- 查域同样只有**单条 message**（一次 `findIndex`），**无跨消息回退**。
- 命中 → `splice(index, 1, {...existing, ...patch})` 就地合并，**不新建**、`seg` 不变。
- 未命中 → 只有两个提前返回条件：`createIfMissing === false` **或** `patch.status === undefined`。二者都不成立时，**无条件 `push` 新建段**：`sealTrailingStreamPart(message)` 后以 `nextSeg(message.parts)` 递增 `seg` 落段。
- **新建前不查任何「已决」状态**：不调用 `hasDecidedPendingResidue`、不检查其它消息、不检查同 toolCallId 是否已在别处终态、不检查 `name`/`kind2` 冲突。唯一「护栏」是 `createIfMissing` 开关（调用点：`parts.ts:408` 的 `suspended` 与 `ai.ts:1808` 的 `runFrontendTool` running 支传 `false`）。
- store 层同名 action（`ai.ts:1786-1788`）是纯转发，**不追加任何守卫**。

**竞态含义（供红队）**：`applyFrameToParts(message, frame)` 的宿主 `message` 由 `handleFrame` 的 `assistantId` 解析决定；`send`/`reattachActive` 每次连接都 `appendAssistantMessage` 新建宿主（`ai.ts:872`、`ai.ts:1023`），`resumeSnapshotStream` 用快照宿主（`ai.ts:790`）。因此同一 `toolCallId` 的帧若投到**不同宿主消息**，`findToolPart` / `upsertToolPart` 都会 miss → 在**新消息里新建一个段**；`tool_result` case 的 `rejected`/`blocked` 保持（`parts.ts:305-306`）也随之失效（新消息里查不到旧段）。已决拦截只在 store 层两处 GF 前缀守卫，`parts.ts` 写口本身无守卫。

---

## 4. 两维记账：读写位置与独立性

### 4.1 消息级：`appliedSeqs`

| 项 | 内容 |
|---|---|
| 载体 | `ChatMessage.appliedSeqs?: number[]`（`types/ai.ts:216`，可选字段） |
| 读 | `alreadyApplied(message, seq)`（`parts.ts:424`）→ `handleFrame` `ai.ts:1534` |
| 写 | `markApplied(message, seq)`（`parts.ts:429`）→ `handleFrame` `ai.ts:1697-1699` |
| 去重语义 | 集合 `includes`，**只去重、不做连续性判定**（`parts.ts:423` 注释自述 R2-1；`ai.ts:1531-1533` 说明心跳不占 seq、归档续收本就跳号） |
| 幂等 | `markApplied` 先 `includes` 再 `push`，重复调用不产生重复项 |
| 持久化 | **不入镜像**：`writeMirror` 的 `messages.map` Pick（`ai.ts:290-298`）只取 id/role/content/parts/reasoning/toolRuns/pendingCall；`ai.ts:289` 显式注释 |
| 作用域 | **单条消息**。宿主消息不同则集合不同 |

`handleFrame` 中该维的**精确位置**（`ai.ts:1509-1704`）：
```
1510-1516  seq = frameSeq(frame)；若非 null → 写 runSeq（水位）
1518-1529  ownedByActiveRound === false → 收尾该消息 done/streaming 后 return  ← 不读也不写 appliedSeqs
1531-1536  seq !== null && alreadyApplied(message, seq) → return                ← 读点
1538-1689  switch(frame.type)（部分分支 break/内部提前 return 无影响）
1691-1695  DERIVE_FRAME_TYPES.has(frame.type) → deriveProjections(message)
1696-1699  seq !== null → markApplied(message, seq)                             ← 写点
1701-1703  isTerminalFrame(frame) → this.loading = false
```
另有早退点 `ai.ts:1503-1507`（宿主消息不存在 → 整帧丢弃，**不记账**）。

### 4.2 轮次级：`runId` / `runSeq`

| 项 | 内容 |
|---|---|
| 载体 | `state.runSeq: Record<string, number>`（`ai.ts:444`），key = runId |
| 写① | `resumeSnapshotStream` `ai.ts:800`：`Math.max(this.runSeq[runId] ?? 0, lastSeq)`，`lastSeq = current.archiveMaxSeq`（快照侧输入，**非**帧输入） |
| 写② | `handleFrame` `ai.ts:1511-1516`：`runId = frame.runId ?? this.activeRunId`，`Math.max(旧, seq)`，**位于 `ownedByActiveRound` 判定与 `alreadyApplied` 短路之前** |
| 读 | `reattachActive` `ai.ts:1022`：`const lastSeq = this.runSeq[targetRunId]`；`undefined` → 不传 lastSeq → 服务端**全量回放**（`ai.ts:1010-1012`、`1030-1032` 文案分支） |
| runId 源 | `frame.runId`（`case 'start'` `ai.ts:1540` 落 `activeRunId`）；水位记账用 `frame.runId ?? this.activeRunId` 兜底 |
| 语义 | 单调递增水位（只增不减，无删除路径） |
| 持久化 | 内存态；`writeMirror` 只存 `lastRunId`（`ai.ts:287`），**不存 runSeq**；`resetSession`（`ai.ts:1376-1437`）清 sessionId/messages/activeRunId/notice/busyConflict/poolSaturated/suspended/activeForm/stopped/streamInterrupted/historyLoaded —— **未清 `runSeq`**（`loadHistory` 亦不清） |

### 4.3 两维是否相互独立

**是，实现上完全独立、无交叉校验**：

1. **键不同**：`appliedSeqs` 键在**消息对象**上（局部集合）；`runSeq` 键在**store state 的 runId 字典**上（全局水位）。二者互不引用。
2. **时序不对称**（`handleFrame` 单次调用内）：
   - `runSeq` 更新（1511）先于 `ownedByActiveRound` 早退（1518）与 `alreadyApplied` 短路（1534）→ **旧轮迟到帧、重复投递帧都仍会推高 `runSeq`**。
   - `markApplied` 写点（1698）在 switch **之后**、且在旧轮早退路径（1518-1529 `return`）**之后** → **旧轮帧不会被任何消息记账**，而 `alreadyApplied` 短路帧早已记过（`return` 发生在写点之前，语义自洽）。
3. **作用域错配**：`appliedSeqs` 是消息级，宿主消息可随连接更换（`send`→新消息 `ai.ts:872`；`reattachActive`→新消息 `ai.ts:1023`；`resumeSnapshotStream`→快照宿主 `ai.ts:790`）。`runSeq` 是 runId 级、跨消息共享。故「同一 seq 被投到不同宿主消息」时：`runSeq` 认为「已见过」（水位不变/推高），`appliedSeqs` 认为「没见过」（不同消息的空集合）→ **帧会被重放一次**。两维之间**没有**任何「消息级 seq 是否已由轮级水位覆盖」的一致性检查。
4. **刷新后不对称**：`appliedSeqs` 不入镜像、`runSeq` 不入镜像 → 双双重置；但 `loadHistory` 只恢复 `activeRunId = mirror.lastRunId`，`reattachActive` 此时 `this.runSeq[targetRunId] === undefined` → 服务端**全量回放**，而回放落到新 append 的消息（`ai.ts:1023`）上、`appliedSeqs` 为空 → 逐帧全部按新帧应用（`delta` 走 `message.content +=` 锁步，`ai.ts:1553`）。
5. **`runSeq` 跨会话残留**：`resetSession` 不清 `runSeq`（见上表），key 为 runId 故仅当后端复用 runId 时才会串味 —— 属需红队判定的边界。

---

## 5. `handleFrame` 函数体全文（`frontend/src/stores/ai.ts:1491-1704`）

入口守卫：**帧归属判定** `ownedByActiveRound = assistantId === undefined || assistantId === activeRoundHostId`（1498）→ 宿主解析（1499-1502）→ 宿主缺失整帧丢弃（1503-1507）→ seq 水位记账（1509-1516）→ 旧轮迟到帧早退（1518-1529）→ seq 幂等短路（1531-1536）→ `switch(frame.type)` → 派生重算（1693）→ 幂等记账（1697）→ 终帧清 `loading`（1701）。

```ts
/**
 * SSE 帧 → store（判别联合 switch，覆盖后端全部帧类型）。 */
handleFrame(frame: SseFrame, assistantId?: string): void {
  /**
   * R2/R6（issue#5）帧归属判定：只有"本轮"（宿主消息仍是当前轮的宿主）的帧才允许改写
   * **轮次级**状态（activeRunId / loading / suspended / notice / activeForm）——旧轮迟到帧
   * （resetSession 之后、或新轮已起）只收敛它自己那条消息，不能把新轮标记成空闲。
   */
  const ownedByActiveRound = assistantId === undefined || assistantId === activeRoundHostId
  const message = assistantId
    ? this.messages.find((item) => item.id === assistantId)
      ?? (ownedByActiveRound ? this.currentAssistantMessage() : null)
    : this.currentAssistantMessage()
  if (!message) {
    // 宿主消息已不在（会话已重置/历史已整体替换）且该帧不属于当前轮 ⇒ 整帧丢弃，
    // 不再凭空 append 一条孤儿助手消息（G5）
    return
  }

  // T6：按 runId 记录最大帧序号（reattach 时回传，服务端只补增量）
  const seq = frameSeq(frame)
  if (seq !== null) {
    const runId = frame.runId ?? this.activeRunId
    if (runId) {
      this.runSeq[runId] = Math.max(this.runSeq[runId] ?? 0, seq)
    }
  }

  if (!ownedByActiveRound) {
    /**
     * 旧轮迟到帧（R2/R6）：只收敛它自己那条消息的收尾，**一律不改写轮次级状态**
     * （`loading`/`activeRunId`/`suspended`/`notice`/`activeForm`）——否则旧轮的 done/error
     * 会把正在跑的新轮标记成空闲，旧轮的 retry/取消提示会覆盖新轮提示（G2/G3/G7）。
     */
    if (isTerminalFrame(frame)) {
      message.streaming = false
      message.done = true
    }
    return
  }

  // R2-1（批2）：seq 幂等 —— 同一帧被重复投递（reattach 全量回放 / 双连接竞态）时整帧跳过：
  // 不重复累加文本、不重建段、不重放工具卡。只做"同序号去重"，**不做连续性判定** ——
  // 心跳不占业务 seq、归档续收本就会跳号，序号断层是正常现象（R2-3）。
  if (seq !== null && alreadyApplied(message, seq)) {
    return
  }

  switch (frame.type) {
    case 'start':
      this.activeRunId = frame.runId
      this.notice = null
      break
    case 'message_start':
    case 'message_end':
      // T5 三段式的 start/end：本段边界（正文/思考段共用同一套帧）—— 只写消息身份与段边界
      // （message_end 顺带封口末尾流式段，o-R1），**不改段结构**（R2-2：段收口只发生在工具类帧
      // 与轮终态）。批2：写入统一走 applyFrameToParts。
      applyFrameToParts(message, frame)
      break
    case 'delta': {
      // 高频路径（§6.1 性能注）：段文本与派生投影 content **锁步** `+=`，不调 deriveProjections
      //（全量重算会每 token 替换 toolRuns 数组 ⇒ 工具卡列表整列重渲染）
      message.content += frame.text
      applyFrameToParts(message, frame)
      break
    }
    case 'reasoning': {
      // 裁决⑦：有 reasoning 帧才渲染思考链（无帧则 reasoning 恒空 → 面板不显示折叠块）
      message.reasoning += frame.text
      applyFrameToParts(message, frame)
      break
    }
    case 'tool_start': {
      applyFrameToParts(message, frame)
      break
    }
    case 'tool_result': {
      // 补丁③ / DC-14 T1 的终态裁决口径（BLOCKED > REJECTED > ok 三元）落在段上，见
      // utils/parts.ts 的 applyFrameToParts —— 本 case 不再有任何投影直写
      applyFrameToParts(message, frame)
      break
    }
    case 'confirm_request': {
      this.suspended = true
      // 工具卡四态之"等待确认"：确认门未决期间该工具卡显示为等待确认（段 status=pending +
      // requiresAction=true ⇒ 派生出挂起卡）；倒计时元数据随段持久化
      applyFrameToParts(message, frame)
      break
    }
    case 'confirm_decision': {
      // approved → running、rejected → rejected 的段裁决在 applyFrameToParts；
      // 挂起卡随 requiresAction=false 派生为 null（结局由工具卡承载）
      applyFrameToParts(message, frame)
      this.suspended = false
      break
    }
    case 'frontend_tool_request': {
      if (frame.name === GENERATIVE_FORM_TOOL && this.hasDecidedPendingResidue(frame.toolCallId)) {
        // T2a 返修（S3）：守卫前移到**写段之前** —— 已决 toolCallId 不产生任何新挂起语义
        //（不落 requiresAction 段、不置 suspended、不武装 activeForm），
        // 防 reattach 回放先渲染一张瞬时挂起卡、再等后续结局帧收敛
        break
      }
      this.suspended = true
      applyFrameToParts(message, frame)
      // 表单武装前先派生：openGenerativeForm 读派生出的 pendingCall.args
      deriveProjections(message)
      if (frame.name === GENERATIVE_FORM_TOOL) {
        // GF-B：表单挂起走渲染器通道等用户填写 —— 绝不能让通用执行器兜底
        // （那段说明文本不是值 JSON，会被闸门 400 拒、挂起拖到超时，GFa §8.3）
        this.openGenerativeForm(message, frame)
        break
      }
      // 前端工具没有 HITL：立即在浏览器执行并回灌结果
      void this.runFrontendTool(message, frame)
      break
    }
    case 'frontend_tool_result': {
      // 段侧终态裁决（帧 status 优先 → ok 兜底，本地已终态 first-wins）与 requiresAction 收口
      // 全在 applyFrameToParts —— 它是本帧型**唯一**的段写点（F2 收口修：此处原先还二次调用
      // applyFrontendToolTerminal。F1 之后该调用对绝大多数段是空写（段早已非 pending/cancelled），
      // 唯一不空的是本地乐观 cancelled 段：applyFrameToParts 的 first-wins 保留了 'cancelled'，
      // 二次调用却按帧 status 把它翻成另一种终态 —— 同一段两个写点给出不同裁决，正是要收掉的
      // 双写口径分裂面）。挂起卡随 requiresAction=false 由派生抽掉。
      // 非帧路径（409 响应体 / 续收快照）仍用 applyFrontendToolTerminal 就地收敛，见其注释。
      applyFrameToParts(message, frame)
      if (this.activeForm?.toolCallId === frame.toolCallId) {
        // 结局帧已到：表单收敛（提交/取消/超时皆自此收尾）。
        // T2a-D#2（B2 缓释）：帧到达时本地仍在填写（未在提交在途）→ 该表单已被他端提交/处理
        if (this.activeForm.status === 'filling') {
          this.notice = '该表单已在其他窗口提交，本地填写已停止'
        }
        disarmFormExpiry()
        this.activeForm = null
      }
      this.suspended = false
      break
    }
    case 'suspended': {
      // 后端对**每一批工具调用**都会先发 suspended（把挂起态外置，见 SpToolCallingManager），
      // 其中只有 CONFIRM/FRONTEND 两类需要人工或浏览器介入：BACKEND 工具随即执行完毕，
      // 不该被判成"挂起等待"（否则运行状态与输入区提示会误报）。
      const waiting = frame.toolCalls.find((call) => call.kind === 'CONFIRM' || call.kind === 'FRONTEND')
      if (waiting) {
        if (waiting.name === GENERATIVE_FORM_TOOL && this.hasDecidedPendingResidue(waiting.toolCallId)) {
          // T2b 顺手项③（GLM 复核 F4）：与 frontend_tool_request case 同款的 residue 守卫 —
          // 已决 toolCallId 不产生新挂起语义（对齐 isFrameDecidedPendingStatus 口径，勘误 4）
          break
        }
        this.suspended = true
        // 本帧**只更新已存在的工具段**（requiresAction=true，status 不改；createIfMissing=false）
        // —— 段尚不存在则留给紧随其后的 confirm_request / frontend_tool_request 建立
        applyFrameToParts(message, frame)
      }
      break
    }
    case 'heartbeat':
      // 心跳只用于保活与倒计时，不产生展示内容
      break
    case 'retry':
      this.notice = '上游连接中断，正在重试（本轮尚未产出内容）'
      break
    case 'done': {
      message.streaming = false
      message.done = true
      // o-R1：轮终态封口末尾流式段（否则已完成的消息里仍留着 streaming 段的假态，且会被
      // 镜像持久化）+ 批2 前置：仍在等动作的工具段一并过期降级（与空历史 expired 降级惯例一致）。
      // 旧口径的 `message.pendingCall = pendingCall?.status === 'pending' ? null : ...` 直写
      // 已删除 —— 挂起卡由段派生（requiresAction=false ⇒ 派生为 null）
      sealTrailingStreamPart(message)
      expireResidueToolParts(message)
      if (this.activeForm !== null) {
        // 轮次收尾时表单未决（如后端侧超时/放弃）：本地一并收起，计时器随之中和
        disarmFormExpiry()
        this.activeForm = null
      }
      this.suspended = false
      if (frame.cancelled) {
        this.notice = '本轮已取消'
      }
      break
    }
    case 'error': {
      message.streaming = false
      message.done = true
      // 出错收尾与 done 同口径：封口末尾流式段 + 待决工具段过期降级
      sealTrailingStreamPart(message)
      expireResidueToolParts(message)
      if (this.activeForm !== null) {
        disarmFormExpiry()
        this.activeForm = null
      }
      this.notice = `本轮出错（${frame.code}）：${frame.message}`
      break
    }
    default:
      // 联合类型已穷尽；运行时多出的未知帧（后端加帧）在此静默忽略
      break
  }

  // 工具类帧 / 段边界帧 / 轮终态帧到达后：段是唯一真值 ⇒ 全量重算派生投影（§6.1）。
  // delta / reasoning 已在各自 case 里与段文本锁步维护，故不在此列（高频路径不重算）。
  if (DERIVE_FRAME_TYPES.has(frame.type)) {
    deriveProjections(message)
  }
  // R2-1：记账"已应用"（放在 case 处理之后 —— 半途 break 的帧同样算处理过，不被重放）
  if (seq !== null) {
    markApplied(message, seq)
  }

  if (isTerminalFrame(frame)) {
    this.loading = false
  }
},
```

### 5.1 `onFrame` 回调注册处（3 处，全部转调本函数）

| 位置 | 注册上下文 | 宿主消息 |
|---|---|---|
| `frontend/src/stores/ai.ts:886` | `send()` 内 `const handlers: SseFrameHandlers = { onFrame: (frame) => this.handleFrame(frame, assistant.id), ... }`（`aiApi.chat(..., handlers, options)`，`ai.ts:920-924`） | 本轮新 append 的 `assistant.id`（`ai.ts:872`） |
| `frontend/src/stores/ai.ts:1046` | `reattachActive()` 内 `aiApi.reattachRun(targetRunId, { onFrame: (frame) => this.handleFrame(frame, assistant.id), ... }, { signal, lastSeq })` | 重挂新 append 的 `assistant.id`（`ai.ts:1023`） |
| `frontend/src/stores/ai.ts:819` | `resumeSnapshotStream(runId, lastSeq, hostMessageId)` 内 `onFrame: (frame) => this.handleFrame(frame, hostMessageId)` | 快照重建就地就位的宿主（`ai.ts:790` 传 `host.id`） |

配套辅助：`frameSeq`（`frontend/src/types/sse.ts:323`，取 `frame.seq` 有限数或 `null`）、`isTerminalFrame`（`types/sse.ts:294`，`done`/`error`）、`DERIVE_FRAME_TYPES`（`ai.ts:265-277`：message_start / message_end / tool_start / tool_result / confirm_request / confirm_decision / frontend_tool_request / frontend_tool_result / suspended / done / error）。

---

## 6. 其它文件命中汇总（非三主文件）

| 文件 | 命中符号 |
|---|---|
| `frontend/src/types/ai.ts` | `appliedSeqs:216`；`ToolPart`（`toolCallId:298`、`requiresAction`、`timeoutSeconds/expiresAt/atMs`）；`ToolPartPatch:274-…`；`pendingCall:201`；`PendingToolCall.toolCallId:281`；`ActiveGenerativeForm.runId:363`；`ChatMessage.runId` 族（41/53/67/84/120/152/342/435） |
| `frontend/src/types/sse.ts` | `frameSeq:323`、`isTerminalFrame:294` |
| `frontend/src/components/AiPanel/AiPanel.vue` | `pendingCall`（200-284）、`toolCallId`（164/168/201/236/239/240/493-505）、`runId`（59/702-704） |
| `frontend/src/api/sse.ts` | `runId` 状态与 reattach（88/97/175/390-431/552）、`lastSeq` |
| `frontend/src/api/ai.ts` | `runId` 路径参数（34-60/104-116） |
| `frontend/tests/parts-reducer.spec.ts` | `openStreamPart` / `sealTrailingStreamPart` / `alreadyApplied` / `markApplied` / `upsertToolPart` / `deriveProjections` 的直测（含 seq 幂等与「delta 不调 derive」结构性断言 `:383`） |
| `frontend/tests/parts-double-write.spec.ts` | `appliedSeqs` 不入镜像断言 `:289-290`；`handleFrame` 集成（`:66-211`） |
| `frontend/tests/frontend-tool-result-terminal.spec.ts` | `hasDecidedPendingResidue`（`:419-432`）、`localTerminal`（`:195-207`、`:366`）、`handleFrame` 链路 |
| `frontend/tests/parts-residue-cleanup.spec.ts` | `applyPendingTerminal` / `applyFrontendToolTerminal` / `cancelGenerativeForm` |

---

实际执行模型自证：本子智能体运行环境无法自省底层模型 ID，仅能声明按父棒显式下发 `model=DeepSeek/deepseek-flash` 派发，未做独立自证。
