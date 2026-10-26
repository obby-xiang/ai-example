> **历史分析 / 过程证据 — 非现行基线，非验收结论**
>
> - 归档：2026-10-26 · `docs/evidence/history-recovery-2026-10-26/wire-tail/`
> - 来源：Kimi Code 会话 `<KIMI_SESSION>` / 子 agent `agent-460` 的 wire 末条 assistant 文本（消息序号 m=11；只读流式解析，非原始会话转写）
> - 历史时点：2026-10-11 19:21 → 2026-10-11 19:25（wire 事件时间窗，UTC+8）；当时模型：`kimi-code/kimi-for-coding`
> - 原基线：`2f455ce`（正文自动摘取的首次基线提及），未经本批复验
> - 原始体量：23003 B / 18843 字符 / 455 行；行指纹覆盖率（本批复算）15.9%（最高单文件 8%：`docs/evidence/history-recovery-2026-10-26/wire-only/entries/010-只读勘探报告-<REPO>-@-HEAD-0d0dba31-agent-511.md`）
> - 类别：**过程核验**
> - 与既有件关系：010-entry；报告口径覆盖率 0.08
> - 脱敏：路径 0 / 会话标识 0 / 身份 0 / 密钥 0 / 命名纪律 1
> - 正文为该条消息的完整文本，未删段、未改写结论；仅脱敏、占位符归一与行尾空白规整。当时结论不代表当前代码状态，不得作为当前缺陷或有效裁决引用。
侦察完成。以下是完整实态锚点图（HEAD=2f455ce，批 1 已入库；全只读，未改任何文件）。

# 对话时序包子包② 施工定位锚点图

## 1. `frontend/src/stores/ai.ts`（1874 行）

### 1a. `upsertToolPart` 调用点（11 处 + 1 定义）

| 行号 | 所在函数 | 响应的帧/事件 |
|---|---|---|
| `:707` | `restoreSuspendedSnapshot` | runs/current 快照 PENDING 条目重建挂起卡 |
| `:1147` | `openGenerativeForm` 失败收尾 | generative_form schema 解析失败 → 本地 failed 收敛 |
| `:1473` | `handleFrame` · `tool_start` | 工具开始帧 |
| `:1506` | `handleFrame` · `tool_result` | 后端工具结果帧 |
| `:1529` | `handleFrame` · `confirm_request` | 确认门挂起帧 |
| `:1553` | `handleFrame` · `confirm_decision`（approved 分支） | 放行帧 |
| `:1569` | `handleFrame` · `confirm_decision`（拒绝分支） | 拒绝帧 |
| `:1590` | `handleFrame` · `frontend_tool_request` | 前端工具挂起帧 |
| `:1629` | `handleFrame` · `frontend_tool_result` | 前端工具结局帧 |
| `:1679` | `handleFrame` · `suspended`（`createIfMissing=false`） | 批量挂起帧，只更新已存在的段 |
| `:1860` | `runFrontendTool` 本地结局 | 浏览器执行器返回 |
| `:1816` | 定义本体 | — |

示例摘录（`tool_start`，`:1465-1482`）：

```ts
case 'tool_start': {
  message.toolRuns.push({
    toolCallId: frame.toolCallId, name: frame.name, kind: frame.kind,
    status: 'running', args: frame.args
  })
  this.upsertToolPart(message, {
    toolCallId: frame.toolCallId, name: frame.name, kind2: frame.kind,
    status: 'running', args: frame.args, requiresAction: false
  })
  break
}
```

注意 `tool_start` 是**直 push + 双写**（不走 `upsertToolRun`）；其余写点均为 `upsertToolRun` + `upsertToolPart` 成对。

### 1b. parts 辅助函数

`nextSeg`（`:228-230`）：
```ts
function nextSeg(parts: MessagePart[]): number {
  return parts.reduce((max, part) => Math.max(max, part.seg), -1) + 1
}
```

`openStreamPart`（`:237-251`）：
```ts
function openStreamPart(message: ChatMessage, kind: 'text' | 'reasoning'): TextPart | ReasoningPart {
  const last = message.parts[message.parts.length - 1]
  if (last && last.kind === kind && last.streaming) {
    return last
  }
  const seg = nextSeg(message.parts)
  if (kind === 'reasoning') {
    const part: ReasoningPart = { kind: 'reasoning', seg, text: '', streaming: true }
    message.parts.push(part)
    return part
  }
  const part: TextPart = { kind: 'text', seg, text: '', streaming: true }
  message.parts.push(part)
  return part
}
```

`sealTrailingStreamPart`（`:254-259`）：
```ts
function sealTrailingStreamPart(message: ChatMessage): void {
  const last = message.parts[message.parts.length - 1]
  if (last && (last.kind === 'text' || last.kind === 'reasoning') && last.streaming) {
    last.streaming = false
  }
}
```

`upsertToolPart` 定义本体（`:1816-1829`）：
```ts
upsertToolPart(message: ChatMessage, patch: ToolPartPatch, createIfMissing = true): void {
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

### 1c. `handleFrame` 主循环（`:1394-1727`）

- 帧归属守卫 `ownedByActiveRound`：`:1400`；宿主消息选择：`:1401-1404`；无宿主整帧丢弃：`:1405-1409`。
- runSeq 记录点：`:1411-1418`（`frameSeq(frame)`，按 runId 记最大 seq）。
- 旧轮迟到帧分支：`:1420-1431`（只收敛 `streaming/done`，不改轮次状态）。
- switch 起点：`:1433`。各 case 行号与当前写入：

| case | 行号 | 当前写什么 |
|---|---|---|
| `start` | `:1434-1437` | 仅轮次态：`activeRunId`/`notice=null` |
| `message_start` | `:1438-1442` | `messageId`、`streaming=true` |
| `message_end` | `:1443-1446` | `messageId`（不关消息） |
| `delta` | `:1447-1456` | **双写**：`message.content += frame.text`（旧直写 :1448）+ `openStreamPart` 续段 |
| `reasoning` | `:1457-1464` | **双写**：`message.reasoning +=`（:1459）+ reasoning 段 |
| `tool_start` | `:1465-1482` | `toolRuns.push`（直写 :1466）+ `upsertToolPart` |
| `tool_result` | `:1483-1517` | `upsertToolRun` + `upsertToolPart`（blocked/rejected 裁决 :1487-1496） |
| `confirm_request` | `:1518-1539` | `suspended=true`、`pendingCall = toPendingCall(...)`（旧直写 :1520）+ 双侧 upsert |
| `confirm_decision` | `:1540-1580` | `pending.status = ...`（旧直写 :1543）+ approved/拒绝两分支双侧 upsert |
| `frontend_tool_request` | `:1581-1608` | residue 守卫 :1582 → `pendingCall = toPendingCall(...)`（:1589）+ upsertToolPart；GF 特判 :1599-1604 |
| `frontend_tool_result` | `:1609-1655` | localTerminal 守卫 :1615-1618 → 双侧 upsert + `applyFrontendToolTerminal`（:1643）+ activeForm 收敛 :1644-1652 |
| `suspended` | `:1656-1687` | waiting 过滤 :1660；GF residue 守卫 :1662；`pendingCall = {...}`（旧直写 :1668-1675）+ `upsertToolPart(..., false)` 只更新 :1679-1684 |
| `heartbeat` | `:1688-1690` | 无 |
| `retry` | `:1691-1693` | `notice` |
| `done` | `:1694-1708` | streaming/done + `pendingCall` 条件清除（:1697，见 1d） |
| `error` | `:1709-1718` | streaming/done + notice + activeForm 收敛 |
| default | `:1719-1721` | 静默忽略 |

收尾 `isTerminalFrame → loading=false`：`:1724-1726`。

### 1d. 6 个 requiresAction / pendingCall 残留直写点

**done**（`:1694-1708`）：
```ts
case 'done': {
  message.streaming = false
  message.done = true
  message.pendingCall = message.pendingCall?.status === 'pending' ? null : message.pendingCall
  if (this.activeForm !== null) {
    disarmFormExpiry()
    this.activeForm = null
  }
  this.suspended = false
  if (frame.cancelled) {
    this.notice = '本轮已取消'
  }
  break
}
```

**error**（`:1709-1718`）：
```ts
case 'error': {
  message.streaming = false
  message.done = true
  if (this.activeForm !== null) {
    disarmFormExpiry()
    this.activeForm = null
  }
  this.notice = `本轮出错（${frame.code}）：${frame.message}`
  break
}
```
（error 不清 `pendingCall`——残留的 pending 条目靠 loadHistory 的 expired 降级收敛。）

**confirm() 乐观置位**（`:1027-1047`，核心 :1034）：
```ts
async confirm(approved: boolean, reason?: string): Promise<void> {
  const pending = this.pendingToolCall
  const runId = this.activeRunId
  if (!pending || !runId) { this.notice = '没有待处理的确认请求'; return }
  pending.status = approved ? 'running' : 'rejected'
  try {
    await aiApi.confirm({ runId, toolCallId: pending.toolCallId, approved, reason: reason ?? null })
    this.suspended = false
  } catch (error) {
    if (error instanceof ApiError && error.code === ErrorCode.DUPLICATE_TOOL_CALL_ID) {
      this.notice = '该确认已提交过（幂等拒绝），无需重复操作'
      pending.status = 'approved'
      return
    }
    pending.status = 'pending'
    this.notice = error instanceof Error ? error.message : '确认提交失败'
  }
}
```

**cancelGenerativeForm**（`:1215-1233`，残留点 :1220-1223）：
```ts
form.status = 'cancelled'
// T2a-D#2（A2）：本地 cancel 仅乐观置位 —— 只收敛仍处 pending 的 pendingCall 投影；
// 帧终态为最终裁决，后到帧可覆盖此乐观 cancelled
this.applyPendingTerminal(form.toolCallId, 'cancelled')
disarmFormExpiry()
```

**applyPendingTerminal**（`:1754-1762`，全文）：
```ts
applyPendingTerminal(toolCallId: string, status: PendingToolCall['status']): void {
  for (const item of this.messages) {
    const pending = item.pendingCall
    if (pending && pending.toolCallId === toolCallId
      && (pending.status === 'pending' || pending.status === 'cancelled')) {
      pending.status = status
    }
  }
}
```

**applyFrontendToolTerminal**（`:1772-1793`，全文）：
```ts
applyFrontendToolTerminal(toolCallId: string, status: PendingToolCall['status'] | null): void {
  for (const item of this.messages) {
    const pending = item.pendingCall
    if (!pending || pending.toolCallId !== toolCallId) {
      continue
    }
    if (pending.kind !== 'FRONTEND' || pending.name !== GENERATIVE_FORM_TOOL) {
      // 通用 FRONTEND 工具：基线语义 —— 收掉挂起投影（终态由 toolRun 卡展示）
      item.pendingCall = null
      continue
    }
    if (pending.status !== 'pending' && pending.status !== 'cancelled') {
      continue // 已落定的终态不被后续帧改写（first-wins 单调）
    }
    if (status === null) {
      item.pendingCall = null
      continue
    }
    pending.status = status
  }
}
```

其它 pendingCall 直写点（供翻转时一并清点）：`:564`（loadHistory 空历史 expired 降级）、`:690-697`（restoreSuspendedSnapshot 重建）、`:1520`、`:1589`、`:1668-1675`；content 直写：`:901-903`（handleChatError）、`:1448`（delta）。

### 1e. pendingToolCall / pendingConfirm getter（`:387-409`）

```ts
getters: {
  /** 当前待处理（待确认 / 待前端执行）的工具调用。 */
  pendingToolCall(state): PendingToolCall | null {
    for (let i = state.messages.length - 1; i >= 0; i -= 1) {
      const message = state.messages[i]
      if (!message) { continue }
      if (message.role === 'assistant' && message.pendingCall) {
        if (message.pendingCall.status === 'pending') {
          return message.pendingCall
        }
      }
    }
    return null
  },
  /** 需要人工放行的确认门（DANGER 工具）。 */
  pendingConfirm(): PendingToolCall | null {
    const pending = this.pendingToolCall as PendingToolCall | null
    return pending && pending.kind === 'CONFIRM' ? pending : null
  },
```

### 1f. loadHistory 的 parts 构造分支（`:574-612`）

```ts
this.messages = history.messages.map((item, index) => {
  const role = roleOf(item.type)
  const toolRuns: ToolRun[] = (item.toolCalls ?? []).map((call) => ({
    toolCallId: call.id ?? `history-${index}-${call.name ?? 'tool'}`,
    name: call.name ?? '未知工具',
    kind: 'UNKNOWN',
    status: 'succeeded',
    args: call.arguments,
    result: (item.toolResponses ?? []).find((response) => response.id === call.id)?.responseData ?? null
  }))
  // 双写：历史整体替换的分支同样补 parts（正文段 + 按 toolCalls 顺序的工具段，…requiresAction=false）
  const parts: MessagePart[] = [
    { kind: 'text', seg: 0, text: item.text ?? '', streaming: false },
    ...toolRuns.map((run, toolIndex): MessagePart => ({
      kind: 'tool', seg: toolIndex + 1, toolCallId: run.toolCallId, name: run.name,
      kind2: run.kind, status: run.status, args: run.args, result: run.result ?? null,
      messageId: run.messageId, requiresAction: false
    }))
  ]
  return {
    id: `history-${index}`, role, content: item.text ?? '', parts,
    reasoning: '', toolRuns, pendingCall: null, streaming: false, done: true
  }
})
```

镜像分支 parts 迁移在 `:528-542`（调 `migrateMirror`，migrated 立即 `writeMirror` 回写）。

### 1g. 两处本地收尾

**openGenerativeForm 失败收尾**（`:1137-1157`）：
```ts
if (!spec || !runId) {
  const result = '表单 schema 解析失败（或缺少 runId），前端无法渲染，已带 cancelled:true 回灌收敛（GFa §8.3）'
  this.upsertToolRun(message, {
    toolCallId: frame.toolCallId, name: frame.name, kind: 'FRONTEND',
    status: 'failed', result
  })
  // 双写：本地收尾同样落段（与上面同一字面量）
  this.upsertToolPart(message, {
    toolCallId: frame.toolCallId, name: frame.name, kind2: 'FRONTEND',
    status: 'failed', result, requiresAction: false
  })
  void this.submitFrontendToolResult(frame.toolCallId, '', 'frontend-executor', true, runId ?? undefined)
  return
}
```

**runFrontendTool 本地结局**（`:1841-1872`，段写点 `:1860-1867`）：
```ts
const status: ToolRunStatus = execution.ok ? 'succeeded' : 'failed'
this.upsertToolRun(message, {
  toolCallId: frame.toolCallId, name: frame.name, kind: 'FRONTEND',
  status, result: execution.result
})
// 双写：浏览器侧执行结局同样落段（工具段在此之前由 frontend_tool_request 建好）
this.upsertToolPart(message, {
  toolCallId: frame.toolCallId, name: frame.name, kind2: 'FRONTEND',
  status, result: execution.result, requiresAction: false
})
```

### 1h. 镜像 v2 全家桶

- `readMirror`（`:136-146`）：`JSON.parse(sessionStorage.getItem(MIRROR_PREFIX + sessionId))`，catch 返 null。
- `migrateMirror`（`:158-200`）：intact 判定 `:159-164`（`v >= MIRROR_VERSION && 每条都有 parts`）；v1→parts 构造 `:165-198`（text 段 seg 0 + toolRuns 映射 seg 1..n；pending 条目按 toolCallId 并入，只改 kind2/requiresAction，**status 以 run.status 为权威**（m-R1 注释 :190-191））。
- `writeMirror`（`:202-225`）：写 `{ v: MIRROR_VERSION, savedAt, lastRunId, messages: map 保留 id/role/content/parts/reasoning/toolRuns/pendingCall }`；`:205-206` 注释明言「旧字段仍是渲染真值（面板不读 parts），parts 是旁路真值」。
- `startMirror`（`:449-460`）：`$subscribe` detached+deep，300ms 防抖调 `writeMirror`。

### 1i. 宿主消息选择逻辑

- `restoreSuspendedSnapshot`（`:634-738`）：身份对账 `:646`；宿主选择 `:681-683`：
```ts
host = this.messages.find((item) => item.toolRuns.some((run) => run.toolCallId === entry.toolCallId))
  ?? [...this.messages].reverse().find((item) => item.role === 'assistant')
  ?? null
```
无宿主则 `appendAssistantMessage('')`（`:684-688`）；续收 `:734-737` `void this.resumeSnapshotStream(current.runId, current.archiveMaxSeq, host.id)`。
- `reattachActive`（`:952-1024`）：宿主恒为新建占位消息 `appendAssistantMessage('（正在重挂进行中的轮次…）')`（`:961`），`handleFrame(frame, assistant.id)`（`:984`）。
- `resumeSnapshotStream`（`:744-793`）：不新起消息，`activeRoundHostId = hostMessageId`（`:755`）。

### 1j. deriveProjections

**不存在**。`grep deriveProjections` 于整个 frontend 零命中——子包②需新建。

## 2. `frontend/src/types/ai.ts`（419 行）

- `ToolRunStatus`：`:165`（九态联合，含 `blocked/unknown/cancelled`）。
- `ToolRun`：`:168-181`。
- `ChatMessage`：`:184-210`，`parts: MessagePart[]` 在 `:200`，`:190-199` 的注释明确「`parts` 是**旁路真值**，content/toolRuns/pendingCall 仍是**渲染真值**……权威翻转是后续批的事」——子包②即翻转这批注释所指。
- `MessagePartBase`：`:213-215`；`TextPart`：`:218-223`；`ReasoningPart`：`:226-230`；`ToolPart`：`:236-252`（`requiresAction: boolean` 在 `:251`）；`MessagePart` 判别联合：`:255`。
- `ToolPartPatch`：`:264-274`（status 可选，注释 `:261-263` 说明 suspended 只更新语义）。
- `PendingToolCall`：`:277-305`（status 八态 :304）。
- `AiSessionMirror`：`:314-321`（messages Pick 含 `'parts'`，`:318`）；`MIRROR_VERSION = 2`：`:328`（v2 定义注释 `:323-327`）。

## 3. `frontend/src/components/AiPanel/AiPanel.vue`（779 行）

### 3a. 旧字段读取点（全量，已 grep `m.content|m.reasoning|m.toolRuns|m.pendingCall|m.parts`）

- `m.reasoning`：`:163`（v-if）、`:165`（插值）
- `m.content`：`:168`（正文 v-if+插值）、`:172`（复制按钮 v-if）、`:641`（copyMessage 取 text）
- `m.toolRuns`：`:195`（v-for）
- `m.pendingCall`：`:213`（v-if + data-testid）、`:216`、`:217`、`:219`、`:221`/`:222`（argsText）、`:225`、`:246`、`:251`、`:262-265`、`:276-297`（各终态 tag）
- **`m.parts`：全文件零读取**（仅注释 `:633-634` 说明复制口径不含工具卡/挂起卡）——渲染源切换即改这些点。

### 3b. 消息内联渲染段（`:141-301`）

- 骨架屏：`:120-126`（`v-if="!ai.messages.length && !ai.historyLoaded"`，data-testid `history-skeleton`）；空态 `:127-139`。
- 消息循环 `:141-146`；typing 三点 `:157-160`；思考链 `:162-166`；正文 `:168`；复制按钮块 `:170-192`；工具卡 v-for `:194-210`（args `:205`、result `:208`、reason `:209`）；挂起卡 `:212-299`（data-testid 锚点 `:213` `:data-testid="'pending-card-' + m.pendingCall.toolCallId"`）；CONFIRM 操作区 `:225-244`；GF 表单区 `:246-274`；FRONTEND tag 区 `:276-290`；兜底 tag 区 `:292-298`。

### 3c. 脚本区关键函数

`isTyping`（`:503-505`）：
```ts
function isTyping(message: { streaming: boolean; content: string }): boolean {
  return message.streaming && message.content.length === 0
}
```

`toolTagType`（`:512-536`）：running/succeeded→success、failed→danger、rejected/blocked→warning、expired/cancelled→info、unknown→warning、default→primary。

`toolStatusLabel`（`:538-554`）：九态中文映射（pending「等待确认」… unknown「结局未知（未确认成功/失败）」）。

滚动 watcher（`:715-721`）：
```ts
watch(
  () => {
    const last = ai.messages[ai.messages.length - 1]
    return `${ai.messages.length}|${last ? last.content.length : 0}|${last && last.pendingCall ? last.pendingCall.status : ''}|${last ? last.toolRuns.length : 0}`
  },
  () => scrollBottom()
)
```
另有 `watch(ai.pendingConfirm → resetPendingWatch, { immediate: true })`（`:729-733`）、`watch(ai.expanded)`（`:723-727`）。

## 4. `frontend/tests/`

文件：`parts-double-write.spec.ts`、`ready.spec.ts`、`restore-reconcile.spec.ts`、`setup.ts`、`sse-watchdog.spec.ts`、`tools-and-deadline.spec.ts`。

- vitest 配置 `vitest.config.ts`：jsdom、`tests/**/*.spec.ts`、`setupFiles: ['./tests/setup.ts']`。`package.json` 脚本：`test: vitest run`、`test:watch: vitest`、`typecheck: vue-tsc --noEmit`（**没有单独 lint/test:typecheck 链**）。
- **parts-double-write.spec.ts**（`:59` describe「parts 双写：文本/工具段的顺序与封口」）：
  - `:60` 文本-工具-文本序列：段序/seg 单调/tool_result 就地更新/message_end 不改段结构
  - `:148` 确认门：pendingCall 与工具段对账一致，pending→rejected 两侧同步且不新建段
  - `:200` 思考段：另起段、互不覆盖，旧投影 reasoning 同步拼接
  - `:219` 消息工厂：user 正文=唯一文本段，assistant 空文本段起头
  - `:230` 镜像迁移 §6.4：v1→parts 并立即回写 v2、待决并入工具段、幂等（async，vi.mock `@/api/ai` 的 history/runsCurrent）
  - 辅助：`toolPartsOf`/`toolRunsOf` 对账投影（`:33-58`）、`segDigest`（段结构快照）——翻转后这些对账辅助即「旧字段=派生值」的断言面。
- **restore-reconcile.spec.ts**（`:82` describe）：`:83` 负例①换会话丢弃、`:106` 负例②同会话起新轮丢弃、`:138` 正例正常重建；`deferred()` 手控 await 窗口（`:40-47`）、`suspendedSnapshot()` 工厂（`:51-66`）、`freshStore()` 用 `vi.resetModules()` 归零模块级句柄（`:69-73`）。

## 5. `frontend/src/utils/`

只有 `excel.ts`、`format.ts`、`frontend-tools.ts`、`job-watch.ts`、`spreadjs.ts`——**无 `parts.ts` / `blocks.ts`**，子包②可新建（`deriveProjections` 建议落点）。

## 6. 旧字段直写面（stores/ai.ts 之外）

`grep toolRuns.push|toolRuns =|toolRuns[|pendingCall =|.content +=|content =` 于 `frontend/src`：**全部命中都在 stores/ai.ts 内**，其它源码文件（含 .vue）零旧字段写点。旧字段直写面 = stores/ai.ts 单文件，清单见 1a/1c/1d（另有 `:564`、`:690`、`:901-903`）。这意味着子包②把写点收进 deriveProjections 后，TS 类型层面可将旧字段改为只读派生（getter 或 computed 镜像）。

## 7. scripts/

- `scripts/chat-panel-smoke.mjs` **存在**。计数方式：`assert(name, cond, detail)`（`:209-210`）写 `RESULT.assertions[name] = { pass }`，失败 push 进 `failures`；SKIP 走 `RESULT.skipped` 扩展（`:598`、`:794` 等，如剪贴板回读超时、未授权 `SMOKE_KILL_BACKEND≠1`）并 `[断言N] SKIP` 如实打印；退出码 `process.exit(failures.length === 0 ? 0 : 1)`（末尾 finally）。
- `scripts/verify-e2e.ps1` **存在**。`E2E_STUB_MODE=1` 用法：头部注释 `:23-26`（桩模式 = CI 路线，上游换 `scripts/ci/stub-upstream.mjs`，零容忍）；`:1947` 分支路由；`:2346` 摘要注记；`:2354-2355` 硬判 `if ($env:E2E_STUB_MODE -eq '1' -and $script:gfskip -ge 1) { exit 1 }`。计数：`:67-68` `$script:gfskip`/`gfskip14`，PASS `:75-76`，SKIP `:1393-1394`，29 主用例口径 `:41-49`。

## 8. 后端 `RedisChatMemoryRepository.java`（175 行，o-R3 可达性）

**saveAll**（`:150-161`）——空列表分支是两次独立往返，非原子：
```java
public void saveAll(String conversationId, List<Message> messages) {
    String redisKey = key(conversationId);
    this.redis.delete(redisKey);
    if (messages == null || messages.isEmpty()) {
        this.redis.opsForSet().remove(CONVERSATION_IDS_KEY, conversationId);
        return;
    }
    this.redis.opsForList().rightPushAll(redisKey, this.codec.serializeAll(messages));
    applyTtl(redisKey);
    this.redis.opsForSet().add(CONVERSATION_IDS_KEY, conversationId);
}
```
（`delete` + `DEL` 后 `SREM` 之间崩溃 ⇒ 留下 stale 索引成员，正是 deleteByConversationId 原子化的对称问题。）

**deleteByConversationId**（`:163-166`）+ Lua（`:65-74`）：
```java
private static final RedisScript<Long> DELETE_WITH_INDEX = new DefaultRedisScript<>(
        "local removed = redis.call('DEL', KEYS[1]) " + "redis.call('SREM', KEYS[2], ARGV[1]) "
                + "return removed",
        Long.class);
...
public void deleteByConversationId(String conversationId) {
    this.redis.execute(DELETE_WITH_INDEX, List.of(key(conversationId), CONVERSATION_IDS_KEY), conversationId);
}
```
已知的 Cluster 约束在类注释 `:54-56`（两 key 跨 slot 需 hash tag）。`applyTtl` `:168-173`。

---

**补充结论（翻转施工要点）**：① 派生翻转后 `writeMirror`/`migrateMirror`/`loadHistory`/两个消息工厂的「双写」均可简化为只写 parts + 派生旧字段（migrateMirror 届时可退役或改为只补 seg）；② `hasDecidedPendingResidue`、restoreSuspendedSnapshot 宿主选择（`:681` 按 `toolRuns.some` 找宿主）依赖旧字段，切 parts 后需同步改读 tool 段；③ 面板 `:168/:172` 两处 `v-if="m.content"`、工具卡 `:195`、挂起卡 `:213` 与 `isTyping`/`copyMessage`/滚动 watcher 是渲染侧全部改动面；④ 测试侧 `toolPartsOf`/`toolRunsOf` 对账辅助与 parts-double-write.spec 五个用例需在「旧字段=派生」新口径下改写为单向断言。
