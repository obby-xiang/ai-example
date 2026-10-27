# 修复-STOP 后裸 TOOL_CALLS 误判（2026-10-27）

> **性质**：本件是**本次判据修复的沉淀件**（证据 + 判据 + 用例 + 边界），不是调研件、不是裁决件、不构成新增施工承诺。
> **基线**：`HEAD 8a57040`（`chore: 补充上游乱序定位日志`）**+ 未提交工作树**（两路径：`ResilientChatService.java` / `UpstreamErrorRetryPolicyTest.java`，`git diff` 实测 +166/−16）。**行号以该工作树为准，不随码回头修订**。
> **本笔写入面**：本件 + `docs/research/README.md`（第 79 件，计数 78 → 79）+ `docs/任务看板.md`（+1 行，批次笔行 174 → 175）+ `docs/决策看板.md`（仅注记，**209 不变**）。**零代码 / 零测试 / 零服务起停 / 零 git 写**；门禁 = `node scripts/ci/check-docs.mjs`。
> **验收方式（务必连读）**：施工侧回执 = 下文两个**精确测试方法在同一命令回执中通过**；**主控未复跑**，只做**代码级验收**（读 diff 与实现）；**真实模型 / 真实上游未验**。

## 0 结论速览

| 项 | 结论 |
|---|---|
| 误判 | 上游 `stop` 之后补一个**裸** `finish_reason=tool_calls` 分片（该片 `hasToolCalls=false`）。旧判据"最后一个非空 finishReason 说了算"把它当工具轮中间态 ⇒ `sawTerminal=false` ⇒ 已完整落地的一轮被误判断流 |
| 修复 | 首个可完成终态**只锁存一次**（`terminalFinish`）+ 锁存后**真实**工具分片守卫（`realToolCallAfterTerminal`）+ `terminalSignal` **与锁存同源** |
| 刻意不动 | `lastFinish` 仍覆盖为最后一个非空 finishReason ⇒ `length` / `content_filter` 的最终安全分类照旧（`finish()` 只读它） |
| 边界 | **根治这条误判，不根治真实断流**；**不把 `MessageAggregator` 当已证根因** |

## 1 证据：run `56f71489`

> 该 run 的日志副本**不在仓内**（外部证据），下文只写本次施工依据的观测事实，不引用未能复核的行号。

- **形态**：一轮 6 片 —— 正文 2 片 + 空片 + `stop` + 空片 + **裸 `tool_calls` 标记**；末片 `hasToolCalls=false`（**没有任何 toolCalls**）。
- **观测点**：`Accumulator#warnFinishAfterTerminal(...)`（`ResilientChatService.java:2621`）在 `stop` 之后打出 `终态后又收到 finishReason terminal=STOP incoming=tool_calls hasToolCalls=false`。该 WARN 由 `8a57040` 引入，**只观测**、不改任何判定。
- **旧判据下的后果**（代码级推演，与 run 结论一致）：
  1. `accept()` 对每个非空 finishReason 无条件 `lastFinish = finishOf(...)`（`:2588`）⇒ `lastFinish = TOOL_CALLS`；
  2. `sawTerminal()` 读 `lastFinish.terminal()`（旧实现：`lastFinish != null && lastFinish.terminal()`）⇒ `TOOL_CALLS.terminal() == false` ⇒ 完成帧校验失败（`:934`）；
  3. 抛 `StreamViolationException(INCOMPLETE)` ⇒ 已有正文时落到派生码 `UPSTREAM_STREAM_INCOMPLETE_AFTER_PARTIAL`（派生规则 `:3172`）⇒ `done` 变 `error`、快照 `FAILED`。
- **未证部分**：上游为什么在 `stop` 后再发这一片（网关 / 兼容层 / 聚合器行为）**没有取证**，本件不据此归因。

## 2 修复：三处同源 + 一处刻意不动

| 件 | 语义 | 锚点 |
|---|---|---|
| `terminalFinish` | `AtomicReference`，首个 `terminal()` 终态 **CAS 只锁存一次**；`latchTerminal(...)` 仅在 `finish != null && finish.terminal()` 且 CAS 成功时写入 | `:2547` / `:2608` |
| `terminalSignal` | **改由 `latchTerminal` 写**（与终态同源）⇒ 后续裸标记不再改写它；旧实现里"每个非空 finishReason 都覆盖"的那一行已删除 | `:2529` / `:2612` |
| `realToolCallAfterTerminal` | `AtomicBoolean`；以"**本片之前**是否已锁存"（`terminalLatchedBefore`）为界，为 true 且本片 `hasToolCalls=true` 时置位（`:2592`）⇒ **真实的新工具轮不被终态吞掉**；裸标记（`hasToolCalls=false`）不置位 | `:2554` / `:2584` |
| `sawTerminal()` | 改为 `terminalFinish != null && !realToolCallAfterTerminal`，**不再读 `lastFinish`** | `:2686` |
| `lastFinish` / `finish()` | **刻意不动**：仍覆盖为最后一个非空 finishReason，`finish()` 只读它 ⇒ `length` 仍需 `truncated`、`content_filter` 仍明确失败 | `:2538` / `:2692` / `:1243` |

四条判据互补（避免"修一个破一个"）：

1. **一个终态都没有 ⇒ 断流**：`terminalFinish == null`。
2. **锁存前的 `tool_calls` 仍是断流**：工具轮真的断在中间时它不会被锁存（旧口径保留）。
3. **锁存后的真实工具分片仍是断流**：`hasToolCalls=true` ⇒ 守卫置位 ⇒ 终态不吞新工具轮。
4. **锁存后的裸标记不是证据**：`hasToolCalls=false` ⇒ 不置位 ⇒ 按完成收尾。

## 3 验证（如实）

施工侧回执：**同一命令**内两个精确方法通过（命令文本以施工侧回执为准，本件**不代抄、不补写**）：

- `UpstreamErrorRetryPolicyTest#stopThenBareToolCallsMarkerStaysDoneWhileRealToolCallsStayIncomplete`（`:356`）
- `UpstreamErrorRetryPolicyTest#stopFollowedByLengthOrContentFilterKeepsTheFinalSafetyClassification`（`:397`）

| 方法 | 锁住的语义 |
|---|---|
| 第一个 | 6 片形态 ⇒ 帧序 `start/message_start/delta/delta/message_end/done`、**无 `error`**、`terminalSignal=finishReason=stop`、`DONE`、`finalText=你好`、上游只调 1 次；**另一半**断言 `stop` 后紧跟**真实** toolCalls 分片（无 finishReason）⇒ 无 `done`、`UPSTREAM_STREAM_INCOMPLETE_AFTER_PARTIAL`、`FAILED` |
| 第二个 | `stop → length` ⇒ `done.truncated=true` 且 `terminalSignal` 仍是 `stop`、`DONE`；`stop → content_filter` ⇒ 无 `done`、`CONTENT_FILTERED`、`FAILED` |

- **kill 条件**（改坏即红，已写在测试注释）：`sawTerminal()` 改回读 `lastFinish` ⇒ 前者第一段红；去掉真实工具守卫 ⇒ 前者第二段红；`finish()` 改读锁存值 ⇒ 后者分别丢 `truncated` / 把 `content_filter` 变成 `DONE`。
- **未做的验证**：**主控未复跑**，只做代码级验收；**未跑**后端定向整类 / 后端全量 / 前端 / E2E；**未连真实模型或真实上游**，形态由桩 `Flux` 复现；`56f71489` 的日志副本不在仓内。

## 4 边界与不声称

- **根治范围**：仅"`stop` 之后的裸 `tool_calls` 标记不得翻案"这一条误判；判据集中在本类，**不改上游流、不改 SSE 帧契约、不新增帧类型 / 字段 / 配置键**。
- **不根治真实断流**：上游真的断在工具轮中间、或断后始终不回终帧，仍按 `UPSTREAM_STREAM_INCOMPLETE[_AFTER_PARTIAL]` 失败；重试判据与退避未改。
- **不把 `MessageAggregator` 当已证根因**：仓内代码无该类命中；"聚合器补发 / 改写 finishReason"只是**待证假设**，本件不写"根因 = 聚合器"。
- **不改历史安全口径**：`content_filter` 仍是明确失败、`length` 仍打 `truncated`；`terminalSignal` 只作诊断键，锁存后可能与 `lastFinish` 不一致（如 `stop → length`），这是刻意的。
- **守卫的边界**：`realToolCallAfterTerminal` 只看**锁存之后**的分片 ⇒ 同一片同时带 `stop` 与 toolCalls 不在守卫范围（现有上游形态未观察到此片形）；真出现时需另行判定。
