> 来源=<仓外证据>/cluster-probe-task-review.md（2026-10-25 归档）· 仓外正式过程报告，属历史证据，不代替现行基线/决策文档（docs/02、docs/03、docs/adr/）
# 集群探针 harness 变更 · 独立只读复审

- 复审对象：`scripts/ci/cluster3-probe.mjs`（相对 `ae883fb` 的未提交变更；diff `_p2-review/cluster-protocol-probe.diff`，725 行）
- 隔离副本：`_verify-20261025-cluster-protocol/`（`git archive HEAD` 全树 + 仅探针改动；探针 md5 与主仓工作树一致）
- 基线：`<主仓>` HEAD `ae883fb`；主仓工作树另有 agent-73 的 park 补丁与候选 A 未提交改动，本次未触碰
- 方法：只读 diff / 产品源码 / run 40174291244 artifact / 本机 `--self-check`；未起三实例、未跑 CI、未改任何文件（本文件除外）
- 结论：**逻辑方向成立，但不是“可直接接收”的完成态**。`--self-check` 18/18 属实，`cluster3-run.sh --self-check` 53/53 属实；两者都不是真实拓扑证明。下面 B1/B2 是阻断项，I1-I4 是高危质量项。

---

## 0. 结论速览

| 项 | 判定 | 说明 |
|---|---|---|
| 无粘滞路由 | **符合** | 改动用例全部 `directUrl(a/b/c)` 明确指定实例；`proxy-no-sticky`/`same-tcp-across-abc` 未改。只是新流程没有额外断言 `X-Cluster-Node`，属构造性保证。 |
| 不恢复 held park 执行 | **基本符合** | 决策前 B/C 重挂等 `waitClosed` 后断言 `end`、仍 `SUSPENDED`、`awaitingExternal=true`、条目仍 `PENDING`、归档末号不变。没有直接断言“未产生 job/台账副作用”，靠 `preBDiff=0` + `archiveMaxSeq` 间接覆盖。 |
| 决策前重挂：回放可用帧 + 仍 PENDING 则收流 | **符合（形状层面）** | C 全量逐帧同型同 seq；B 差量 0 帧；两者都要求服务端 `end`。**但只比较 type/seq，不比较载荷**，损坏的回放可 false-pass（见 I1）。 |
| 决策后 B/C 都到 done、内容/状态完整 | **部分符合** | B/C 都 `done`；C 全量 seq 连续；A 全量帧册；`DONE`/台账/作业/history 均查。**B 差量没有连续性断言；帧内容（`ok`/`result`/delta 文本）未验；跨实例台账 `outcome` 未验**（见 I1）。 |
| suspend-kill 不在 park 边界前误杀 | **符合（frame 层面）** | `createSuspendedRun` 等到服务端收流再返回，SIGKILL 在 park 帧集之后。**但 park 帧集过窄可能对慢模型/心跳 false-fail；且 close 早于 park 退出/还租，仍可能落进 lease 窗口**（见 I3、I4）。 |
| 原断言保留/加强 | **大体符合** | `suspend-kill-a` 原 check 全部保留；`suspend-cross-instance` 原 check 语义保留，三个 frameCount 被更严格的 `assertFrameLedger` 吸收。**但“决策前同一条连接活到 done”的语义被替换成决策后新连接**——这是产品契约变更下的有意替换，不是字面“保留”。 |
| 证据先落盘再断言 | **不符合（阻断）** | `A-parked`、`B/C-predecision`、`B/C-reattach`、`B-takeover` 都在关键断言/等待之后才 `saveFrames`；原始 `tool_start` 缺失这类失败仍不会留下 `A-parked.jsonl`。report/summary 的相反表述是事实错误（见 B1）。 |
| 自证 18/18 | **属实，但仅自证** | 实测 `node --check` 通过、`--self-check` PASS 18 / FAIL 0、`cluster3-run.sh --self-check` PASS 53 / FAIL 0。未证明真实拓扑；新自证也未覆盖 `waitClosed` 的 `'close'` 分支、`archiveMaxSeq`、`saveFrames` 时序。 |

---

## 1. 阻断项（Critical）

### B1. “证据先落盘再断言”没有落到关键路径；原始失败形态仍无 `A-parked.jsonl`

**事实链**

1. `settleParkBoundary` 先 `waitClosed` → `checkEq(how,'end')` → `assertParkedFrameSet`，**最后才把 frames 交给调用方**：
   `_verify-20261025-cluster-protocol/scripts/ci/cluster3-probe.mjs:1776-1782`
2. `createSuspendedRun` 内部调用它，**不保存任何帧**：
   `.../cluster3-probe.mjs:1785-1816`（尤其 `:1802`、`:1815`）
3. 调用方在 `createSuspendedRun` **返回之后**才写 `A-parked.jsonl`：
   `.../cluster3-probe.mjs:2381`（suspend-cross）、`:2963`（suspend-kill）
4. 因此，如果 `assertParkedFrameSet` 失败（正是原始 run 40174291244 的形态：`tool_start` 缺失），
   `createSuspendedRun` 直接抛错，**`A-parked.jsonl` 不会产生**。report §2.4 / §8 所说“A-parked 是断言前落盘、失败也留”不成立。
5. 同类问题：
   - `B-predecision` 在 `checkEq(how,'end')` 之后才保存：`:2388-2391`
   - `C-predecision` 在 `checkEq(howC,'end')` 之后才保存：`:2396-2399`
   - 决策后 `B-reattach` 在终帧/seq 断言之后才保存：`:2432-2437`
   - 决策后 `C-reattach` 在终帧/seq 断言之后才保存：`:2440-2445`
   - `B-takeover` 在 `waitTerminal`、`done`、seq 断言之后才保存：`:2985-2990`
   - `C-full` 确实是断言前保存：`:2992-2996`（这一条做对了）
   - `takeover.json` 在所有断言、下载校验之后才写：`:3018-3031`
6. 生成物 `summary.md` 仍宣称这些证据“先落盘再断言”：`:3722-3724`；report 第 83、150、231、250、252 行也作了相同/更强承诺。

**影响**：本次改动的主要卖点之一（“失败时留得下当时收到了哪些帧”）在最关键的 park 断言、pre-decision 收流断言、B 接管终帧断言上仍然不成立。下一次 CI 若在 park 阶段红，仍然只能看到 `failure.txt` 的错误字符串；无法从 artifact 区分“产品没发 `tool_start`”与“SIGKILL/收流截断”。这与 report §1“第一处 harness 缺陷（已修）”直接矛盾。

**建议**：把 `saveFrames`/`caseLog` 移入 `settleParkBoundary`（在任何 `check` 之前写 `A-parked`），在 `waitClosed`/`waitTerminal` 后立即写 B/C 帧（不要等断言），并用 `try/finally` 保存 `takeover.json` 的已完成部分。自证里加一条“断言失败仍落盘”的测试，否则 18/18 证明不了这个纪律。

---

### B2. park 屏障把“真实的早杀窗口”从 `suspend-kill-a` 移除了，且没有替代覆盖

**事实链**

- HEAD 的 park 顺序是：`saveSuspended`（SUSPENDED 已落）→ `out.suspended` → `publishRequestsForParkedBatch` → 执行循环里的 `out.toolStart` → `awaitConfirm` → `completeSubscriptions` → `RunParkedException`。
  - `<主仓>/backend/src/main/java/com/example/configmgr/ai/gate/SpToolCallingManager.java:471`（saveSuspended）
  - 同文件 `:473`（suspended）、`:479`（publish requests）、`:540`（toolStart）、`:564`（awaitConfirm）
  - `.../gate/ConfirmGate.java:576`（completeSubscriptions），`:610`（RunParkedException）
  - `.../run/ResumeService.java:842`（resolvePending）；`.../run/SseChatEmitter.java:1101`（toolStart；续跑路径不再发）
- 旧探针在收到 `confirm_request` 后立即 SIGKILL；新探针 `createSuspendedRun` 等到 `completeSubscriptions` 之后才返回，随后才 SIGKILL：
  `_verify-20261025-cluster-protocol/scripts/ci/cluster3-probe.mjs:2959-2966`
- 于是“SUSPENDED 已可见、`tool_start` 尚未落档”的窗口**不再被任何用例触发**。这个窗口是产品真实的崩溃一致性缺陷（report §6.1 自己承认：续跑不补 `tool_start`），不是纯 harness 假象。
- 新断言会在 `tool_start` **永久缺失**时红（`assertParkedFrameSet` 的计数），所以不是完全掩盖；但**只掩盖了“恰好死在该窗口”的回归**。若产品以后在这个窗口退化，`suspend-kill-a` 会绿。
- 没有新增替身用例：`streaming-kill-a` 杀在流式阶段，`job-kill-a` 杀在作业阶段，均不覆盖 park 转换窗口；`suspend-kill-a` 的标题仍是“挂起态 kill A”，但实际语义已变成“完整 park 之后再 kill”。

**判定**：如果验收目标是“CI 不再因毫秒竞态抛硬币”，屏障本身是正确的；如果验收目标是“不削弱崩溃覆盖”，本变更**削弱了关键覆盖**，且 report 未把它列为 coverage loss，只列为产品问题路由。

**建议**：保留本屏障作为“正常 park 后接管”的确定性基线，同时新增一个**确定性的早杀用例**（故障注入点，或杀在 `confirm_request` 之后但用受控方式让 `tool_start` 未落档），并断言产品文档化后的不变量（例如续跑必须补 `tool_start`，或明确接受“无 start 的 result”）。若决定接受覆盖损失，必须在 report / 看板中显式登记，不能写“原断言全部保留/未削弱”。

---

## 2. 高危质量项（Important）

### I1. 帧册/回放断言只验 type+seq，不验载荷；内容完整性可 false-pass

- `seqsOf` 只保留 `typeof f.seq === 'number'` 的帧：`:393-395`
- `assertFrameLedger` 只断言帧型、次数、相对顺序、seq 连续：`:469-497`
- `assertPreDecisionReplay` 只比较 type 数组和 seq 数组：`:434-444`
- `assertParkedFrameSet` 只对 `confirm_request`/`tool_start` 校验 `toolCallId`，不校验 `tool_start.name/args`、`suspended.toolCalls`：`:409-431`

**后果**：C 决策前全量回放即使 `confirm_request` 载荷损坏、`tool_start.name` 错误、`tool_result.ok=false`、`delta.text` 为空，只要 type/seq 对就能过。跨实例用例又没有 kill 用例的 `ledger[0].outcome === 'ok'`（对比 `:2462-2466` 与 `:3007-3008`），因此“内容/状态完整”没有被证明。

**建议**：回放对比去掉 `_recvAtMs` 后逐帧比较关键字段（至少 `toolCallId/name/decision/ok/outcome`）；跨实例补 `ledger[0].outcome`、`tool_result.ok/result` 非空、最终 assistant 文本非空。

---

### I2. seq 连续性可 false-pass；B 侧差量根本没有连续性断言

- `checkSeqContiguous` 只看“带 seq 的帧”：`:385-391`。任意一帧缺失 `seq` 会被 `seqsOf` 静默跳过；只要其余 seq 仍为 1..N，连续性照样通过。
- `assertFrameLedger` 不检查 `done.seq` 是否存在/等于归档末号；一个没有 `seq` 的 `done` 仍满足 `types[last] === 'done'`、`countOf(done)===1`、其余 seq 连续：`:469-497`。
- 决策后 B 差量只做 `checkStrictlyIncreasing` + 起始下界 + 末号：`_verify.../cluster3-probe.mjs:2434-2436`；kill 后 B 只做 start 下界：`:2987-2988`。B 补帧中间缺一帧（5,7）仍可通过；report 说的“加强为起始/末号”属实，但“不重不漏”没有加强。
- `archiveMaxSeq` 只取最大值（`Math.max(legacyTailSeq, streamTail)`，`RedisFrameStore.lastSeqIfKnown`），无法发现中间缺口；它只能与 park 帧集/全量 seq 连续性组合使用。report §6.2 已承认 `XREVRANGE LIMIT 16` 的 false-fail 风险。

**建议**：加 `frames.every(f => f.type === 'heartbeat' ? true : Number.isInteger(f.seq))`；断言 `done.seq === archiveMaxSeq`；对 B 差量也跑 `checkSeqContiguous(seqs, run.lastSeq+1)`；在 `assertFrameLedger` 里断言 `seqsOf(frames).length === frames.length - heartbeats`。

---

### I3. park 帧集过窄，会把慢模型/心跳/文本/压缩帧误判为缺陷

`assertParkedFrameSet` 只允许 `start/suspended/confirm_request/tool_start` 四种：`:409-431`。

- 产品在流式阶段可以发 `heartbeat`（`ResilientChatService.java:720` 的 `out.heartbeat`）；心跳**没有 seq**，但会出现在 `stream.frames`。一个响应超过心跳间隔的模型就会让 park 帧集直接红。
- 模型若在 tool call 前输出 `message_start/delta/message_end`，或触发 `context`/`retry`，同样红。旧探针只等 `confirm_request`，不会因此失败。
- 本次 run 40174291244 的单个 stub 证据只能证明“那一次是 4 帧”，不能证明“唯一合法形态”。report 把 4 帧写成唯一合法形态，是把一次观测固化成产品契约。

**后果**：这与“不要在 park 边界前误杀/误判”的目标相反——屏障等到了正确的边界，却在帧集上引入了新的 false-fail 风险。

**建议**：`assertParkedFrameSet` 改为“记录全部帧，断言业务帧的子集/尾部满足 4 帧契约”，或显式允许 `heartbeat/message_start/delta/message_end/context/retry`；至少把 `heartbeat` 视为合法噪声。自证里加“带心跳/文本的 park 帧集仍应通过”的正例。

---

### I4. park 屏障只保证“帧已落档”，不保证 park 退出/还租完成；SIGKILL 仍可能落进 lease 窗口

- HEAD 的收流点在 `ConfirmGate.java:576`，`RunParkedException` 在 `:610`；还租/生命周期切换发生在其间。
- 新探针在客户端看到 `end` 后立即 `killInstance('a')`：`_verify.../cluster3-probe.mjs:2963-2966`。
- 若 SIGKILL 落在“已收流、未还租”的几毫秒内，B 侧 `confirm` 可能得到 `LEASE_HELD_ELSEWHERE`，续跑要等 lease 重试（默认 35s）；`RUN.killTakeoverTimeoutMs` 默认 120s，通常仍能过，但这是新的时序延迟/潜在 flake，report 没有分析。
- `currentRun` 后的 `archiveMaxSeq === lastSeq` 断言（`:2968-2975`）也假设 B 侧在查询前没有因死卡兜底产生新帧；PENDING 条目下 `ResumeService` 返回 `PENDING_UNRESOLVED`、通常不产帧，所以目前大概率成立，但这是隐式前提。

**建议**：把“park 边界”与“park 退出/还租”分开表达；若要彻底消除早杀时序，等 A 侧 `approval_park` 日志/租约释放事件，或明确记录“帧边界而非生命周期边界”。至少在 report 里登记 lease 窗口。

---

### I5. `suspend-cross-instance` 的“决策前同一条连接活到 done”被替换，不是保留

- 旧用例：B/C 在决策前打开，决策后同一条连接 `waitTerminal` 收 `done`。
- 新用例：决策前 B/C 重挂收流后关闭；决策后**重新**打开 B/C 收 `done`：`:2428-2445`。
- 这是产品“park 不悬挂”的正确契约，report 也明确说了“改为决策后重挂”；但 §3.3 的“原断言一条未删/全部保留或加强”在语义上不准确。**同一条连接跨决策存活**的覆盖被有意删除；若未来产品回退到“held connection”，本用例不会发现（它反而会要求 close）。
- 该替换本身符合用户“no held park execution”的要求，故不作为阻断，但必须作为 coverage change 明示。

---

### I6. 报告/生成物的事实性表述与代码不符

- report `_p2-review/cluster-protocol-report.md:83`、`:150`、`:250` 说 `A-parked`/`B-takeover`/`C-full` “断言前落盘”；实际 `A-parked`、`B-takeover` 不是（见 B1）。
- report `:231` 说“下一次 CI 的 `A-parked.jsonl` 会把两种机制彻底分开”；如果断言失败，文件根本不会产生。
- report `:252` 说“若 tool_start 计数异常，会伴随 `A-parked.jsonl`”；同样不成立。
- 生成的 `summary.md` `:3722-3724` 仍然写“先落盘再断言”。

这类表述会误导下一次排障和验收；建议先修代码，再回写报告。

---

## 3. 次要项（Minor）

- `M1` `saveFrames` 对 0 帧写的是 1 个换行，不是真正的空文件；report §8 写“空文件（0 帧）”：`_verify.../cluster3-probe.mjs:1874-1876`。
- `M2` `fullReplayToTerminal` 最多重试 4 次（`:1724-1734`）；新帧册断言只作用于成功的那一次，瞬时的“无 done/连接失败”会被重试掩盖（中间帧丢但 done 到了不会重试，会红）。原逻辑已有，但新断言依赖它，应在 report 里保留这一限定；最好保存每次尝试的 frames。
- `M3` `waitClosed` 的 `'close'` 分支没有被自证覆盖：`selfTestParkCloseBarrier` 只测 `end`、空回放、hold-open 超时（`:4099-4147`，调用 `:4362`）。连接 reset 的 `close` 路径、`res.destroyed` 已关闭但事件已过的路径未测。`waitClosed` 在 `this.ended` 为真时先于 `endError` 返回 `'end'`（`:751-753`），错误面被吞的边角也未测。
- `M4` `PARK_SETTLE_TIMEOUT_MS = 20000` 硬编码（`:1765`）；慢 CI/慢 Redis 下可能误红，且与 `killTakeoverTimeoutMs` 120s 不联动。
- `M5` 新流程没有断言 pre/post reattach 实际命中的 `X-Cluster-Node`；`directUrl` 已保证目标，但 route-log 只记录不断言。若端口/实例 ID 配错，会在别的断言上以更难读的方式红。
- `M6` 决策前“没有 held execution”没有直接副作用断言；建议在收流后加 `jobsOf('c', task.id).length === 0`（或台账不存在）。目前靠 `preBDiff=0` + `archiveMaxSeq` + 仍 PENDING 间接覆盖。
- `M7` `caseSuspendCrossInstance` 的 `historyStable` 只校验用户消息文本（`:2116-2129`），不校验最终 assistant 内容；与“content intact”要求有差距。
- `M8` `assertFrameLedger` 要求 `confirm_decision/suspended` 各恰一次、16 型白名单（`:450-497`），这些在真实拓扑上未验证（report §7 自己承认）；代码面上 `submitDecision`/`saveSuspended` 支持，但新增严格断言仍有引入新红的可能。
- `M9` `createSuspendedRun` 的 `archiveMaxSeq === lastSeq` 检查被加到所有 4 个挂起用例（`:1812-1815`），把 `FrameStore` 末号探测的工程近似（`XREVRANGE LIMIT 16`）变成通用断言；report §6.2 已登记，但失败会表现为“park 后归档末号”，归因成本高。

---

## 4. 覆盖削弱（明确清单）

1. **真实早杀窗口（最关键）**：旧 `suspend-kill-a` 在 `confirm_request` 后立即 SIGKILL，覆盖“SUSPENDED 已落、`tool_start` 未落”的崩溃窗口；新用例等完整 park 收流后才 SIGKILL，该窗口不再被触发。产品侧对应缺陷（`SpToolCallingManager.java:471/473/540` + `ResumeService.java:842` + `SseChatEmitter.java:1101`）仍在，report §6.1 也承认。**无替代用例。**
2. **决策前同连接跨决策存活**：`suspend-cross-instance` 改为决策后重挂，旧语义不再覆盖。这是产品契约变更下的有意替换，但应登记为 coverage change（I5）。
3. **B 差量“不重不漏”**：旧断言本来只有严格递增；新 report 声称“加强”，但代码没有加连续性（I2）。严格说不是相对旧版的削弱，而是“加强声明”不成立。
4. **未覆盖 `waitClosed` 的 reset/close 分支**：自证只有 end/超时，`close` 路径没有真实 HTTP 反例（M3）。
5. **未覆盖失败时的证据落盘**：所有关键帧文件在断言之后，等于“失败取证”覆盖为零（B1）。

---

## 5. 断言 false-pass 分析（逐项）

| 断言 | 可 false-pass 的形态 | 依据 |
|---|---|---|
| `assertParkedFrameSet` | `tool_start` 有 type/seq/toolCallId 但 `name/args` 错误；`suspended.toolCalls` 缺失；某帧无 seq 被 `seqsOf` 跳过 | `:409-431`、`:393-395` |
| `assertPreDecisionReplay` | C 回放的载荷被清空/替换，只要 type 和 seq 与 A 相同 | `:434-444` |
| `assertFrameLedger` | `done` 无 seq 仍过；`tool_result.ok=false`/空 result 仍过；中间某帧无 seq 仍过；`heartbeat` 可多次出现 | `:469-497` |
| `archiveMaxSeq` | 只比最大值，无法发现“末号相同但中间缺帧”；若缺的帧无 seq 则完全不可见；`Number(null)=0` 只在 lastSeq=0 时才可能误过（park 场景 lastSeq>0，实际不会） | `:1819-1824`、`RedisFrameStore#lastSeqIfKnown` |
| B 差量 seq | 5,7 这种中间缺帧可通过 `checkStrictlyIncreasing` + 起始/末号 | `:2434-2436`、`:2987-2988` |
| `assertFrameLedger` 的 `tool_result` 恰一次 | 只数 type，不验 `ok/result`；跨实例台账不验 outcome | `:469-497`、`:2462-2466` |
| `fullReplayToTerminal` | 4 次重试可掩盖一次“瞬时无 done/连接失败”；新帧册断言只在成功那次执行。若 done 到了但中间帧丢，重试不会发生，反而会红 | `:1724-1734` |

---

## 6. HTTP close/timeout 与 artifact 持久化

**做得对的部分**

- `waitClosed` 区分 `'end'` 与 `'close'`，超时 reject 并带已收帧型：`:751-792`。
- `openSse` 的 socket timeout 通过 `req.destroy(error)` 传递：`:819-871`。
- `settleParkBoundary` 对 `how !== 'end'` 直接红，避免“连接重置冒充已 park”：`:1776-1779`。
- `C-full`/`A-full` 在 `assertFrameLedger` 之前保存：`:2995`、`:2453`。
- `runOneCase` 失败仍写 `failure.txt`：`:3780-3786`。

**问题**

- `'close'`/超时/`waitTerminal` 失败时，**帧本身不落盘**；错误信息只有类型列表（超时）或 expected/actual（close），没有 `A-parked`/`B-*` 文件。B1。
- `fullReplayToTerminal` 4 次都失败时，`A-full`/`C-full` 都不会写；而这正是最需要帧证据的“done 缺失”形态。
- `waitClosed` 对“已 close 但调用时 `ended` 仍为 false”的流可能等到 20s 超时（只注册了 `once`；没有检查 `res.destroyed/closed` 状态），会把 reset 误报成 timeout（false-fail，不是 false-pass）。
- `summary.md` 仍宣称“先落盘再断言”，与实现不符：`:3722-3724`。

---

## 7. 原用例 ID 与 A→B→C 路由

- 16 个场景 ID 全部保留、顺序未变：`:1830-1845`；`REQUIRED_CASE_IDS` 16 项、自证“16 条”通过。
- `suspend-cross-instance`：A 挂起/收流 → B 差量重挂 + C 全量重挂（均收流）→ C 决策 → B 差量 + C 全量决策后重挂收 done → A 全量帧册 → B 状态/台账/作业 → B/C history。路由 A/B/C 明确，无粘滞。
- `suspend-kill-a`：A 挂起/收流 → SIGKILL A → B `runs/current`/决策/差量重挂 → C 全量回放/history → B/C 下载。路由 A/B/C 明确。
- 直接 URL 构造保证目标实例；但没有新增 `X-Cluster-Node` 断言（M5）。

---

## 8. 实际跑过的验证（本机，只读）

```text
node --check scripts/ci/cluster3-probe.mjs
  → OK

node scripts/ci/cluster3-probe.mjs --self-check
  → PASS 18 / FAIL 0；SELF-CHECK PASS（EXIT=0）

bash scripts/ci/cluster3-run.sh --self-check
  → PASS 53 / FAIL 0；SELF-CHECK PASS（EXIT=0）
```

- 以上在 `<验证副本>/_verify-20261025-cluster-protocol` 执行；探针 md5 与主仓工作树一致。
- `cluster-protocol-probe.diff` 与 `git diff ae883fb -- scripts/ci/cluster3-probe.mjs` 内容一致（14 个 hunk，725 行）。
- **未执行**：真实三实例拓扑、CI run、Docker/HAProxy/Redis；因此本文不把 18/18 当作产品行为证明。report §7 的边界声明基本诚实，除了 B1 的“证据已先落盘”与 I5 的“原断言全部保留”。

---

## 9. 建议的修复顺序

1. **先修 B1**：`settleParkBoundary` 内先 `saveFrames` 再任何 `check`；`waitClosed`/`waitTerminal` 后用 `finally` 落 B/C/B-takeover；`takeover.json` 增量写。加一条“断言故意失败仍落盘”的自证。
2. **处理 B2**：明确接受还是拒绝早杀覆盖损失；若拒绝，新增确定性早杀用例；若接受，在 report/看板登记 coverage loss，删除“原断言全部保留/未削弱”的表述。
3. **修 I1/I2**：逐帧载荷对比、seq 存在性、`done.seq === archiveMaxSeq`、B 差量连续性、跨实例 `ledger.outcome`/`tool_result.ok`。
4. **修 I3**：park 帧集允许心跳/文本/context/retry，或只断言业务子集；加“带心跳/文本的 park 仍过”的正例。
5. **修 I4/M3/M4**：把 park 边界与还租边界分开记录；自证覆盖 `'close'`；超时可配置并和 120s 联动。
6. **回写 report/summary**：删掉“A-parked 失败也留”“B-takeover 断言前落盘”等不实表述；§3.3 改为“同连接语义被产品契约替换”。

---

## 10. 最终判定

- **Spec Compliance：部分符合，2 个阻断项**（B1 证据时序；B2 早杀覆盖）。其余要求（无粘滞、不 held park、pre-decision 回放+收流、post-decision B/C done、A→B→C、场景 ID）在代码结构上成立。
- **Task Quality：逻辑自洽、18/18 自证属实，但不能按 report 的“无覆盖削弱 / 证据先落盘”声明接收**。I1-I4 是真实的高危 false-pass/false-fail 面；I6 是报告事实性问题。
- **给主会话的一句话**：这个改动可以修掉 run 40174291244 的两个红，但它是“把早杀窗口从测试里移走 + 仍不在失败时留 A-parked”的版本；在 B1、B2 未被显式接受/补齐前，不要把它描述成不削弱覆盖的完成态。
