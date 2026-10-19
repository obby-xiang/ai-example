> 归档来源：仓外正式报告（已脱敏）；归档日期：2026-10-19；本件为 2026-10-19 仓外文档补档批入库件，正文结论与数据未改。

# 问题4 composer 项目回落 · 阶段二施工报告

- 日期：2026-10-19
- 仓库：`<REPO_ROOT>`
- BASE：`cb0e926c0b7c72aacc21a27ede1171cc83b15a62`
- 修改文件：
  - `frontend/src/stores/ai.ts`（仅 `send()` 返回结构 + 结果类型）
  - `frontend/src/components/AiPanel/AiPanel.vue`（发送后焦点回收、失败/断流保稿、防重复）
  - `frontend/tests/ai-panel-parts-render.spec.ts`（同一 spec 新增阶段二用例）
- 未 commit / 未 push；未起停服务；未改后端/API/SSE/队列/demo/docs；`data/` 未触碰。

## 0. 开工自证

wire：`<SESSIONS_ROOT>/<SESSION_ID>/agents/agent-1109/wire.jsonl`

`profile.bind` 单行（节选前段）：

```json
{"type":"profile.bind","agentId":"agent-1109","modelAlias":"DeepSeek/deepseek-flash","profileName":"coder","thinkingEffort":"high", ...}
```

## 1. RED

先只改测试，新增/更新：

- 组件：发送成功清空 + `focus({preventScroll:true})`；`error/interrupted/not-sent` 草稿为空时恢复原稿并聚焦；`cancelled` 不恢复；用户已写新内容不覆盖、不设失败锚点；新消息失败后重发走编辑重跑、用户消息数不增加；编辑重跑失败沿用发送前 `editId`。
- store：`send()` 对空/loading 早退、正常 ok、开流前 error、断流 interrupted、主动 stop cancelled 的 `outcome` 与 `userMessageId` 映射；`skipUserRecord` 返回 null。
- 既有 `store.send` mock 成功路径改为 `{ outcome:'ok', userMessageId:null }`，未删除任何断言。

RED 结果（`phase2-red.log`）：**12 failed / 155 passed（167）**。失败原因均为缺少返回结构、焦点回收或保稿：
- 组件：`focus` 未被调用；失败后输入框仍为空；失败重发无锚点。
- store：`send()` 返回 `undefined`，`result.outcome` 不可读。
无测试装配错误。

## 2. GREEN

### 2.1 store `send()` outcome（`stores/ai.ts:46-50`、`1491`、`1556-1584`）

新增导出类型：

```ts
export type AiSendOutcome = 'ok' | 'error' | 'interrupted' | 'cancelled' | 'not-sent'
export interface AiSendResult {
  outcome: AiSendOutcome
  userMessageId: string | null
}
```

`send()` 改为 `Promise<AiSendResult>`，映射表：

| 场景 | outcome | userMessageId |
|---|---|---|
| 空内容 | `not-sent` | `null` |
| `loading` 早退 | `not-sent` | `null` |
| 正常完成 | `ok` | 新记录用户消息 id |
| 开流前 / 请求异常 | `error` | 新记录用户消息 id |
| 断流（`onClose terminal=false`） | `interrupted` | 新记录用户消息 id |
| 用户主动 `stop()`（abort） | `cancelled` | 新记录用户消息 id |
| `skipUserRecord` 正常完成 | `ok` | `null` |

实现要点：

- 仍保持请求前 `recordUserMessage(content)`，只把返回值捕获为 `userMessageId`。
- `settleOutcome(fallback)` 只读本轮已存在状态：身份失效 → `interrupted`；`stopped` → `cancelled`；`streamInterrupted` → `interrupted`；否则调用方兜底（`ok` / `error`）。
- 未改 `canSend` / `stop()` / `handleChatError()` / `lastRoundOutcome` 语义，未改 API/SSE。

### 2.2 composer 保稿与焦点（`AiPanel.vue:2653-2701`）

- 发送前保存原始 `draft` 与 `editId`；清空后立即 `composerRef.focus({ preventScroll: true })`。
- `ok` / `cancelled`：不恢复草稿。
- `error` / `interrupted` / `not-sent`：
  - 仅当 `inputText.value === ''`（用户未在流式中写入新内容）才恢复原始草稿并再次聚焦；
  - 编辑锚点设为 `result.userMessageId ?? editId ?? null`；
  - 若用户已写新内容：直接返回，不覆盖输入，也不把失败消息设为新锚点。
- 恢复路径只写 `inputText` / `editingMessageId` / `focus()`，绝不调用 `send()` 或 `recordUserMessage()`。
- 防重复路径：
  - 新消息失败 → `userMessageId` 作为锚点；重发走既有编辑重跑（`skipUserRecord:true`），用户消息数不增加。
  - 编辑重跑失败 → `userMessageId` 为 null，沿用发送前 `editId`；重发仍编辑同一条用户消息。

### 2.3 既有 mock 同步

同一 spec 全文扫描 `store.send` / `ai.send` mock：仅错误卡动作用例存在 `mockResolvedValue(undefined)`，已改为 `{ outcome:'ok', userMessageId:null }`。其余成功路径均走真实 store，按新结构自然返回。

## 3. 验证

| 项目 | 命令 | 结果 | 日志 |
|---|---|---|---|
| RED targeted | `yarn test tests/ai-panel-parts-render.spec.ts` | **12 failed / 155 passed（167）** | `phase2-red.log` |
| GREEN targeted | 同上 | **167 passed（167）** | `phase2-green-targeted.log` |
| 全量 | `yarn test` | **23 files / 438 tests 全通过** | `phase2-full.log` |
| 类型检查 | `yarn typecheck` | 通过（exit 0） | `phase2-typecheck.log` |

- 全量日志含既有测试环境 `Vue warn`（406 条，均为既有 EP 桩/指令解析警告），无失败；targeted 日志 0 条 `Vue warn`。
- `git diff --check` exit 0（仅 LF→CRLF 提示）。
- `git status --short`：仅三个允许文件被修改；`?? data/` 未触碰。
- 新增行静态扫描：无 emoji、无内联 style / `<style>`、无 queue/排队；无调试日志残留。
- 阶段一 153 个用例仍全部通过，未回归。

## 4. 风险与关注点

1. **并发轮次**：若旧一轮在新一轮开始后才收尾，`isCurrentRound()===false` 时本次 `send()` 统一返回 `interrupted`；这是保守口径，未针对并发多轮做更细语义。
2. **主动停止的 notice**：abort 抛错时仍走既有 `handleChatError`（本次未改其语义），可能出现取消相关 notice；`outcome` 已正确映射 `cancelled`。
3. **空白新输入**：判据是 `inputText.value === ''`。用户若在流式中只输入空白字符，视为“已写新内容”，不会覆盖；这是字面口径。
4. **草稿与记录内容的 trim 差异**：store 记录的是 trim 后文本；重发编辑时目标消息文本也用 trim 后文本。原始草稿（含首尾空白）只用于回填输入框。
5. **程序化调用**：`continueRound` 等处仍 `void ai.send(...)` 忽略返回结构；不影响 UI 行为。
6. **焦点**：恢复/清空后调用 `focus({ preventScroll:true })`；若输入框处于硬禁用（health 不可用等）且走程序化发送，focus 为 no-op，不抛错。
7. 未做浏览器实机验收；IME/焦点/草稿恢复的最终体验仍需实机确认。

---

## 5. Fix round 1（2026-10-19）

重新自证 `profile.bind`：`agent-1109` / `DeepSeek/deepseek-flash` / `coder` / `thinkingEffort: high`。仅改当前三个允许文件，未提交、未起停服务。

### 5.1 RED：error 终帧被误归 ok

新增真实 store 测试（spec:4805）：模拟 `onFrame` 收到 `type:'error'` 终帧、`aiApi.chat` 正常 resolve，期望 `{ outcome:'error' }` 且保留既有 error / 断流 / stop 映射。

RED 结果（`phase2-fix1-red.log`）：**1 failed / 167 passed（168）**，失败输出：

```text
AssertionError: expected 'ok' to be 'error'
Expected: "error"
Received: "ok"
```

### 5.2 GREEN：最小修复

- `stores/ai.ts:1521-1530`：在 `send()` 内新增本轮局部标记 `sawErrorTerminal`；`onFrame` 中仅当 `isCurrentRound()` 且 `frame.type === 'error'` 时置位，再交给既有 `handleFrame`。
- `stores/ai.ts:1582`：`aiApi.chat` 正常 resolve 后改为 `settleOutcome(sawErrorTerminal ? 'error' : 'ok')`。
- 未改 `lastRoundOutcome` / `handleChatError` / API / SSE；开流前 catch 仍走 `settleOutcome('error')`，局部标记不会污染该路径。
- `isCurrentRound()` 保护保留：旧轮迟到 error 帧不会标记到新轮，身份失效时仍按 `interrupted` 收口。

### 5.3 两处过期注释

- `stores/ai.ts:1498`：loading 守卫注释改为“流式中输入框保持可编辑，但组件 keydown 已拦截普通 Enter；程序化调用与 loading/轮次失步窗口仍可达”。
- `AiPanel.vue:2430`：错误卡注释由“不置 outcome”改为“不置 `lastRoundOutcome`”，与 `AiSendOutcome` 区分。

### 5.4 Fix round 1 验证

| 项目 | 命令 | 结果 | 日志 |
|---|---|---|---|
| RED targeted | `yarn test tests/ai-panel-parts-render.spec.ts` | **1 failed / 167 passed（168）** | `phase2-fix1-red.log` |
| GREEN targeted | 同上 | **168 passed（168）** | `phase2-fix1-green-targeted.log` |
| 全量 | `yarn test` | **23 files / 439 tests 全通过** | `phase2-fix1-full.log` |
| 类型检查 | `yarn typecheck` | 通过（exit 0） | `phase2-fix1-typecheck.log` |

- `git diff --check` exit 0（仅 LF→CRLF 提示）；`git status` 仍仅三个允许文件 + 既有 `?? data/`。
- 无调试日志残留；targeted 0 条 `Vue warn`，全量仅既有 EP 桩警告，无失败。
- 既有开流前 error、断流 interrupted、主动 stop cancelled、skipUserRecord 映射用例全部保持通过。

### 5.5 未处理事项

按要求未处理旧轮覆盖测试、锚点存在性、阶段一视觉 Minor 及其他新需求；未提交、未做浏览器验收。

---

## 6. Tooltip Escape 验收修复（2026-10-19）

重新自证 `profile.bind`：`agent-1109` / `DeepSeek/deepseek-flash` / `coder` / `thinkingEffort: high`。本批只改 `AiPanel.vue` 与同一 spec；未改 App.vue / styles / 外层布局 / stores / 其他文件，未提交、未起停服务，未碰页面级窄栏溢出（既有问题5）。

### 6.1 实际根因

composer 的两个 tip 只靠 `TIP_CLASS` 的 `opacity-0 + group-hover/tip + group-focus-visible/tip` 控制显隐，没有 demo v2 `composer-decisions.html:382-416` 的共享 `hidden` 状态机：

- Escape 不会给当前 hover/focus 的 tip 加 `hidden`；
- focusout / mouseout 离开按钮时不会清 tip；
- 没有 hover/focus 内部移动的 `relatedTarget` 防误清逻辑。

### 6.2 RED

新增 5 条测试（spec:3295-3385）：

- focus 发送钮 → Escape 隐藏且 `defaultPrevented=false` / `cancelBubble=false` / 事件继续冒泡，focusin 恢复；
- focusout 隐藏、再次 focusin 恢复；
- 停止钮同样 Escape 隐藏、focusin 恢复；
- mouseover 进入显示、内部移动不清除、外部离开隐藏、重新进入恢复；
- Escape 隐藏后，tip 内部子元素 mouseover 不误清除。

RED 结果（`tip-esc-red.log`）：**5 failed / 168 passed（173）**，5 条均因 `hidden` 从未出现而失败，非装配错误。

### 6.3 GREEN

参照 demo 共享状态机，在 composer 容器统一处理（`AiPanel.vue:729-733`、`1345-1441`）：

- `hiddenComposerTips: Set<'send'|'stop'>` 作为唯一 hidden 状态；`hoveredComposerTip` / `focusedComposerTip` 记录当前交互目标。
- 按钮只声明 `data-composer-tip="send|stop"`（`AiPanel.vue:755`、`767`），事件全部由容器代理，未逐按钮复制监听。
- `mouseover/mouseout` 用 `relatedTarget` 判断是否仍在同一按钮内，内部子元素移动不误清；`focusin` 显示、`focusout` 隐藏。
- `Escape` 只对当前 hover/focus 的 tip 加 `hidden`，**不调用 `preventDefault` / `stopPropagation`**；事件继续冒泡，mermaid 的 window 级 Escape 监听不受影响。
- tip 绑定改为 `[TIP_CLASS, hidden ? 'hidden' : '']`：`hidden` 只做 `display:none` 闸，移除后仍由原 `opacity`/`group-hover` 体系承接；`aria-hidden="true"` 保留。
- 未新增 toast/modal/提示，未改全局 Escape 或 mermaid 查看器。

### 6.4 验证

| 项目 | 命令 | 结果 | 日志 |
|---|---|---|---|
| RED targeted | `yarn test tests/ai-panel-parts-render.spec.ts` | **5 failed / 168 passed（173）** | `tip-esc-red.log` |
| GREEN targeted | 同上 | **173 passed（173）** | `tip-esc-green-targeted.log` |
| 全量 | `yarn test` | **23 files / 444 tests 全通过** | `tip-esc-full.log` |
| 类型检查 | `yarn typecheck` | 通过（exit 0） | `tip-esc-typecheck.log` |

- `git diff --check` exit 0（仅 LF→CRLF 提示）；`git status` 中本批新增改动仅 `AiPanel.vue` 与同一 spec（`stores/ai.ts` 为阶段二既有改动，本批未触碰）；`?? data/` 未触碰。
- 无调试日志残留；targeted 0 条 `Vue warn`，全量仅既有 EP 桩警告，无失败。
- 测试说明：VTU 默认把组件挂在脱离 document 的容器里，window 收不到事件；因此用组件根监听器验证“事件未被 composer 截断”，并同时断言 `defaultPrevented=false`、`cancelBubble=false`。

### 6.5 未处理事项

按要求未处理页面级窄栏溢出（问题5）、其他 Minor，未提交、未做浏览器复验。
