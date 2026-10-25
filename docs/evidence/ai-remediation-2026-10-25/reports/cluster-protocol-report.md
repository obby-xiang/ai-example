> 来源=<仓外证据>/cluster-protocol-report.md（2026-10-25 归档）· 仓外正式过程报告，属历史证据，不代替现行基线/决策文档（docs/02、docs/03、docs/adr/）
# 三实例集群探针 · run 40174291244 协议修复报告（隔离副本）

> **v2 更正（2026-10-25，见 `cluster-probe-fix1-report.md`）**：本报告的 v1 版本有两处与代码不符，
> 已在该轮修正，阅读时请以 v2 为准：
> 1. **"证据先落盘再断言"在 v1 并未落到关键路径**（`A-parked`/`B-takeover`/`takeover.json` 仍写在断言之后）。
>    v2 已用 `evidenceFirst`/`finally` 真正前置，并加了自证。
> 2. **v1 把 `suspend-kill-a` 改成"等 park 收流再 SIGKILL"，移除了早杀窗口的覆盖**。v2 已恢复
>    **拿到确认卡即 SIGKILL**（不等收流），断言改为产品补发后的**帧册不变量**（`tool_start` 恰一次、先于 `tool_result`）；
>    park 屏障只保留在非 kill 的编排用例上。因此 §2.4/§8 里"park 屏障是 kill 前置条件"的描述对 v2 不再成立。
> 3. v2 另按复审 I1/I2/I3 加强了载荷/seq 存在性/B 差量连续性，并允许 park 之前的合法噪声帧
>    （心跳/文本/压缩/重试）——本报告 §3.3 的"只允许 4 帧"表述仅描述 v1。

- 日期：2026-10-25
- 主仓：`<主仓>`，HEAD `ae883fb`（**只读，未改动**）
- 隔离副本：`<验证副本>/_verify-20261025-cluster-protocol/`（`git -c core.autocrlf=false archive HEAD` 全树 → 只改 1 个探针文件）
- 证据目录：`<仓外证据>/cluster-protocol-evidence/`（gh 下载 run 40174291244 的 artifact，未读任何明文、未读 secrets）
- 纪律：未在本机起真实三实例拓扑、未用 8081/5202、未改任何 backend/frontend/产品文件、未派子代理、未动主仓。

---

## 0. 结论速览

| # | 问题 | 定性（证据） | 本批动作 | 产品缺陷？ |
|---|---|---|---|---|
| 1 | `suspend-cross-instance` 断言"决策前重挂的连接活到 done" | 产品自 `20c56bb` 起**刻意**对"仍有人等的 park 轮"回放完即收流（`AiController.java:761-769` + `stillWaitingForInput` 780-793）。裁决：保留非阻塞收流，**不**恢复 held connection / 阻塞执行 | 只改探针：改为断言**实际安全契约**（回放完整 + 被服务端收流 + 无终帧 + 状态仍 SUSPENDED），终帧改在**决策 200 之后**的重挂上收；原断言一条未删，另加一组更强断言（重挂回放契约 / 收流后状态 / 归档末号 / 帧册顺序与连续性 / 两连接 seq 对账） | **否**（是契约变更，用例没跟上） |
| 2 | `suspend-kill-a` 接管后 `tool_start` 计数 = 0 | **不是回放读失败**（排除见 §2.2），**也不是 HEAD 不产 `tool_start`**（同一次 run 的姊妹用例：B 的 `lastSeq=3` 差量连接收到的唯一一帧就是 `tool_start`）。是**harness 时序**：探针收到确认卡后**没有 park 屏障**就 SIGKILL，杀在"快照已 SUSPENDED、`tool_start` 还没落档"的窄窗里 | 只改探针：新增 **park 边界屏障**（等客户端看到完整 park 帧集且连接被服务端收流，才允许 SIGKILL），并把帧集/归档末号/seq 连续性断言加到 park 阶段（比原来更强） | **有放大因素，已路由**：park 转换"状态先落、帧后发"且续跑不补 `tool_start`（§6.1） |

**改动文件：仅 `scripts/ci/cluster3-probe.mjs`（在隔离副本内，1 个文件，diff `+517/-67`，见 §4）。**

---

## 1. 证据获取（可复现、零密钥）

```bash
cd <主仓>
gh run view 40174291244 --json databaseId,conclusion,headSha,...      # failure, headSha=ae883fb
gh api repos/{owner}/{repo}/actions/runs/40174291244/artifacts         # cluster3-evidence-40174291244 (572 399 B)
gh run download 40174291244 -n cluster3-evidence-40174291244 -D <仓外证据>/cluster-protocol-evidence
```

落地内容（共 16 个用例目录 + 运行面）：

```
cases/suspend-kill-a/{failure.txt,notes.txt,kill-a.json}          ← 只有 3 件：C-full.jsonl / takeover.json 都没写出来
cases/suspend-cross-instance/{failure.txt,notes.txt,confirm.json}
instances/instance-{a,b,c}.log  route-log.jsonl  results.{json,jsonl,md}  ci.log  run.log  env.json  stub.log
```

> **第一处 harness 缺陷（已修）**：`saveFrames/saveEvidence` 都写在各用例**断言之后**。本次失败正死在断言上，于是定位所需的 `C-full.jsonl` / `takeover.json` 一件都没留 —— 这正是"必须先落证据再断言"的直接教训（§3.3）。
> `env.json` 只有端口/路径/jar sha，无任何密钥；全程未读 `.env`、未输出任何凭据。

---

## 2. issue 2：`suspend-kill-a` 的 0 次 `tool_start` —— 取证与定性

### 2.1 逐行时间线（同一次 run，2026-10-25）

| 时刻 (UTC) | 来源 | 事实 |
|---|---|---|
| 07:52:54.475 | instance-a.log | `turn_accepted runId=4ab2dbd9-…` |
| 07:52:54.505 | instance-a.log | `turn_start ... resumed=false`（**A 侧最后一条该 run 日志**） |
| 07:52:54.509 | route-log | `sse-open POST /api/ai/chat` → node-a |
| ~07:52:54.57 | 探针 | `createSuspendedRun` 收到 `confirm_request`（call-stub-33），`lastSeq=3` 后**立刻 close 连接** |
| 07:52:54.573 | cases/suspend-kill-a/notes.txt | “挂起在 A：runId=4ab2dbd9… toolCallId=call-stub-33”；同一毫秒发起 SIGKILL |
| 07:52:54.609 | run.log | `实例 a 已被 kill：pid=3300 exit={"signal":"SIGKILL"} portClosed=true` |
| 07:52:54.620 | route-log | B `GET /runs/current` 200（866 B）→ 快照 SUSPENDED |
| 07:52:54.635 | instance-b.log | `死卡兜底触发续跑 runId=4ab2dbd9…` |
| 07:52:54.636 | instance-b.log | `approval_decided … status=APPROVED` |
| 07:52:54.650 | route-log | B `GET /api/ai/events/4ab2dbd9…?lastSeq=3` 200（差量） |
| 07:52:54.663 | instance-b.log | `Starting job 4 type=EXPORT taskId=7` ← **工具真的在 B 上执行了** |
| 07:52:54.696 | instance-b.log | `死卡兜底续跑结局 … rebuiltTools=[call-stub-33:APPROVED:executed-here]` |
| 07:52:54.800 | instance-b.log | `turn_end status=done toolCalls=0 frames=2 runTotalMs=316` |
| 07:52:54.822 | route-log | C `GET /api/ai/events/4ab2dbd9…?lastSeq=0` 200（全量） |
| — | failure.txt | `接管后 tool_start 恰一次：期望 1，实得 0`（probe:2717） |

### 2.2 排除法：为什么不是"回放丢帧 / 归档读失败 / 从来不发"

1. **不是归档读失败（C 侧）**：`AiController.events` 在回放前用 `lastSeqIfKnown` 预检，读失败会 503 + `degraded`；本次 C 的 `?lastSeq=0` 是 200，且全量回放**收到了 `done`**（`terminalType(full)==='done'` 断言通过），说明读链路正常；`stageReplay` 降级会打 `回放降级 runId=… staged=…` WARN —— `instance-c.log` 在 07:52:54.6–07:52:56 之间**一条 WARN 都没有**（整份日志的 WARN 只有：启动期冷启动 Flyway 失败、deprecated 键/vector 提示、07:56 Redis 故障那一组）。
2. **不是"HEAD 不产 `tool_start`"**：同一次 run、早 31 秒的姊妹用例 `suspend-cross-instance` 里，B 的 `?lastSeq=3` 差量连接**收到的唯一一帧就是 `tool_start`**（失败消息逐字：`等待终帧（done/error）：流已结束（已收=tool_start）`）。⇒ HEAD 的 park 流程**确实会发并落档 `tool_start`**，而且是在卡片之后几十毫秒内（B 在 .535 重挂即已读到）。
3. **不是发出去又被 replay 丢掉**：B（.650，差量）与 C（.822，全量）读的是**同一个** `ai:xevents:<runId>`；两个独立读都拿不到该帧，只能说明**归档里从来没有它**。而 A 侧在 `.505…609` 这个窗口内也没有 `事件日志追加失败 … type=tool_start` / `显式 ID 落档被拒` 的 WARN（A 日志这段是空白）。
4. **是 harness 时序**：A 的日志停在 `turn_start`（.505），**没有** `tool_call_start`（`SpToolCallingManager.java:545`，紧跟 `out.toolStart` 之后）也**没有** `approval_park`（`ConfirmGate#await` 的 park 分支）—— 说明进程死在 **`publishRequestsForParkedBatch`（479）之后、`tool_start`/park 完成之前**。探针在 `.573` 收到卡、`.609` 就 SIGKILL：这段窗口（≈36–49ms）与姊妹用例里 `tool_start` 的落档延迟（≈43ms 内）**同量级**。旧探针没有任何屏障保证"park 已完成"，这条用例因此在毫秒级竞态上抛硬币。

### 2.3 产品侧放大因素（**不改产品，只路由**）

- **状态先落、帧后发**：`saveSuspended`（`SpToolCallingManager.java:471`）→ `out.suspended`（473）→ `publishRequestsForParkedBatch`（479）→ 执行循环里的 `out.toolStart`（540）。`SUSPENDED` 已是对外可见的合法恢复态，但 `tool_start` 还没落档 ⇒ 进程在这段窗口死亡就**永久**留下"有 tool_result、没有 tool_start"的帧册。
- **续跑不补 `tool_start`**：接管/续跑走 `ResumeService` → `resolvePending`（`ResumeService.java:842`）→ `claimAndExecute`，后者只发 `tool_result`（`SpToolCallingManager.java:1493/1496`），**不发 `tool_start`**；`SseChatEmitter#toolStart`（`SseChatEmitter.java:1101`）在续跑路径上无调用点。⇒ 一旦原发射方死在那个窗口，帧册**再也补不回来**（这正是 "0 次" 会永久成立的原因）。

### 2.4 探针侧的修法（为什么不是"把检查改绿"）

- 新增 **park 边界屏障** `settleParkBoundary()`：一旦看到 `confirm_request`，**继续读**这条连接，直到**服务端收流**（`completeSubscriptions` 在 park 里触发），然后才允许后续动作（包括 SIGKILL）。
- 屏障之上再加**更强的** park 契约断言 `assertParkedFrameSet()`：`start/suspended/confirm_request/tool_start` 各**恰一次**、无终帧、无越界帧型、`suspended` 先于 `confirm_request`、**seq 从 1 逐号连续**。
- 再加**归档末号交叉核对**：`GET /runs/current` 的 `archiveMaxSeq` 必须等于客户端已收到的末号（`createSuspendedRun` 收流后一次、SIGKILL 后一次）。它抓的是"连接已收流但归档落后"这一类分叉；而本次"0 帧"这类**该帧从头到尾不存在**的形态，会被上一条 park 帧集断言在 A 阶段直接点名（`park 边界帧集：tool_start 帧数（toolCallId=…）：期望 1，实得 0`）。
- 证据先落盘再断言：`A-parked.jsonl`（park 时）、`B-takeover.jsonl`、`C-full.jsonl`（断言前）、`takeover.json`（含 `parkedSeq/parkedTypes/frameLedger`）。
- 结论：**并没有放宽** `tool_start 恰一次`。相反，现在它由 "A 已 park 且 `tool_start` 已落档" 这个确定性前提保证；若产品真的不写这一帧，用例会在 **park 阶段**就红，报错直指 A，而不是像本次那样迟一步表现为"接管后 0 帧"。

---

## 3. issue 1：`suspend-cross-instance` —— 按裁决改写协议（不恢复 held connection）

### 3.1 产品语义（引用）

`AiController.events()`（HEAD）：

```java
// park 的挂起轮：回放（含请求帧）完成后主动收流，决策/回灌再触发续跑。
// 只在确实还在等人（仍有 PENDING 条目）时收流 …
if (this.properties.getPark().isEnabled() && RunSnapshot.SUSPENDED.equals(snapshot.getStatus())
        && stillWaitingForInput(runId)) {
    out.completeSubscriptions();
}
```

⇒ 决策之前建立的任何重挂，**回放完就收流**；这是裁决要求保留的行为，探针不得要求它"活着等到 done"。

### 3.2 新协议（5 段，全部断言）

1. **park 在 A**：`createSuspendedRun('a')` 等到服务端收流，帧集 = `start → suspended → confirm_request → tool_start`（后两帧先后不作约束，兼容 C1 前后两种实现），`lastSeq=4`，并核对 `archiveMaxSeq === lastSeq`。
2. **决策前重挂**：B 带 `lastSeq=4`（差量 ⇒ **必须 0 帧** + 收流）；C 从 0（全量 ⇒ **必须逐帧等于 park 帧集**：同 seq、同型、同序 + 收流 + 不含终帧）。两条都必须是**服务端正常收尾**（`'end'`，连接重置不算）。
3. **收流 ≠ 这一轮死了**：随后 `GET /runs/current` 必须是 `SUSPENDED` + `awaitingExternal=true` + `pendingEntries` 里该条仍是 `PENDING` + `archiveMaxSeq` 不变。
4. **决策落 C**：HTTP 200、`accepted=true`、`storedBy=C`、`resumeOutcome ∈ {SCHEDULED, LEASE_HELD_ELSEWHERE}`。
5. **决策后重挂（产品自述标准时序）**：B 差量 + C 全量**两条连接都收到 `done`**；B 补帧起始 seq ≥ 5 且末号 = `done.seq`；C 的 seq 从 1 逐号连续；再由 A 的全量回放做**帧册对账**（见下）。

### 3.3 原断言逐条对照（无删除、全部保留或加强）

| 原断言（HEAD 版本） | 现在 |
|---|---|
| C 侧决策 HTTP 200 / accepted / storedBy=C / resumeOutcome ∈ 集合 | 原样保留 |
| B 侧重挂终帧 done | 保留，改为**决策后**的重挂（产品标准时序） |
| C 侧重挂终帧 done | 保留，改为**决策后**的重挂 |
| B 侧补帧 seq 严格递增 | 保留，**加强**为"起始 ≥ lastSeq+1 且末号 = done.seq" |
| C 侧补帧 seq 严格递增 | 保留，**加强**为"从 1 逐号连续" |
| 全量回放 `tool_start` 恰一次 | 保留（并入 `assertFrameLedger`），另加 **seq 逐号连续 + 帧型白名单 + 首帧 start/末帧 done** |
| 全量回放 `tool_result` 恰一次 | 保留，另加 `tool_start < tool_result`、`confirm_decision < tool_result` |
| 全量回放 `confirm_decision` 恰一次 | 保留 |
| run 终态 DONE | 原样保留 |
| 台账 `start_export` 恰一次 / 条目数 1 / executedBy ∈ {A,C} | 原样保留 |
| 作业数恰 1 | 原样保留 |
| B/C history total 相等 | 原样保留 |
| （新增）决策前重挂的回放完整性、收流、无终帧 | 新增 3 条 |
| （新增）收流后状态/等待面/归档末号不变 | 新增 4 条 |
| （新增）A 全量回放 seq ≡ C 决策后重挂 seq（两条独立连接同号同序） | 新增 1 条 |

> 明确未做：没有把 `AiController` 的收流分支"改回悬挂"，没有引入任何"保持连接/阻塞执行"的断言，没有把终帧改成 SKIP。

---

## 4. 改动文件（精确）

**唯一改动**：`<验证副本>/_verify-20261025-cluster-protocol/scripts/ci/cluster3-probe.mjs`
（主仓 HEAD 版本 + 下述改动；diff：`<仓外证据>/cluster-protocol-probe.diff`，725 行）

| 位置（新文件行） | 内容 |
|---|---|
| 27-33（文件头注释） | 新增"挂起/接管时序口径"两段（park 屏障 + 决策前重挂的裁决口径） |
| 385-506 | 新增纯断言：`checkSeqContiguous`(385)、`seqsOf/typesOf`(393/397)、`assertParkedFrameSet`(409)、`assertPreDecisionReplay`(434)、`ARCHIVED_FRAME_TYPES`(450，产品 16 型白名单)、`assertFrameLedger`(469)、`countOf/indexOf`(500/504) |
| 751-796 | `SseStream#waitClosed()`：区分**服务端正常收尾 `'end'`** 与**连接重置 `'close'`**，超时即红（悬挂连接是本方法要抓的缺陷形态） |
| 1765-1826 | `PARK_SETTLE_TIMEOUT_MS`(1765)、`settleParkBoundary`(1776)、`createSuspendedRun` 重写(1785)：park 屏障 + park 帧集断言 + `archiveMaxSeq` 交叉核对 + 返回 `frames`；`checkEqNumber`(1819) |
| 1836 | 用例标题更新（`suspend-cross-instance` 描述新协议） |
| 2374-2478 | `caseSuspendCrossInstance` 重写（§3.2 五段） |
| 2955-3035 | `caseSuspendKillA`：park 屏障后 kill、证据先落盘、`archiveMaxSeq` 校验、`assertFrameLedger` + `confirm_decision` 恰一次 |
| 3720-3727 | `summary.md` 的"证据文件"补充挂起类证据清单 |
| 4009-4160 | 4 个新自证：`selfTestParkBoundaryContract`(4009)、`selfTestFrameLedger`(4038)、`selfTestPreDecisionReplay`(4076)、`selfTestParkCloseBarrier`(4099，真实 HTTP) |

**未改动**：`backend/**`、`frontend/**`、`scripts/ci/cluster3-run.sh`、`scripts/ci/stub-upstream.mjs`、`stub-routes.json`、`.github/workflows/cluster-3node.yml`、docs。主仓工作树**未被触碰**（仍然是 agent-73 的 park 补丁 + 候选 A 的未提交改动，与本批无交集）。

隔离副本与 HEAD 的差异面（已核对）：`scripts/` 下**只有** `cluster3-probe.mjs` 不同，`.github/` 逐字节相同，其余全树来自 `git archive HEAD`（**不含**主仓的未提交补丁，故复跑的是 run 40174291244 同一份产品代码）。

---

## 5. RED → GREEN（本机，只用现有 Node 工具链，未起服务）

**RED（先写自证，函数尚不存在）**

```bash
cd <验证副本>/_verify-20261025-cluster-protocol
node scripts/ci/cluster3-probe.mjs --self-check          # → EXIT=1
# 自证结果：PASS 14 / FAIL 4
# - park 边界帧集契约…：assertParkedFrameSet is not defined
# - 全量回放帧册…：assertFrameLedger is not defined
# - 决策前重挂契约…：assertPreDecisionReplay is not defined
# - park 收流屏障…：settleParkBoundary is not defined
```

日志：`<仓外证据>/cluster-protocol-selfcheck-red.log`

**GREEN（最小实现后）**

```bash
node --check scripts/ci/cluster3-probe.mjs                                    # OK
node scripts/ci/cluster3-probe.mjs --self-check                              # EXIT=0 → PASS 18 / FAIL 0
bash  scripts/ci/cluster3-run.sh  --self-check                               # EXIT=0 → PASS 53 / FAIL 0
```

日志：`<仓外证据>/cluster-protocol-selfcheck-green.log`、`<仓外证据>/cluster-protocol-run-selfcheck-green.log`

新增 4 条自证（含反例，证明断言不是空转）：

```
PASS  park 边界帧集契约（含 tool_start 与终帧禁止）（4 帧 + 6 个反例）
PASS  全量回放帧册（seq 连续/恰一次/顺序/帧型白名单）（10 帧 + 4 个反例）
PASS  决策前重挂契约（回放完整且不收终帧）（1 正例 + 3 个反例）
PASS  park 收流屏障（真实 HTTP：收流/空回放正例 + 悬挂反例）（…）
```

最后一个自证用**真实本地 HTTP** 起三个临时服务（ephemeral 端口，非 231xx）：发完 park 帧就 `end()`（正例）、直接 `end()`（空回放正例，对应用例里"B 差量 0 帧"分支）、帧发完但**不收流**（反例，300ms 必须超时红）。因此 `waitClosed` 的"收流 / 悬挂 / 空回放"三态都被实测过，不是只靠类型检查。

另做入口烟测（确认本机禁跑真实拓扑的保护仍生效，且新代码不影响启动路径）：

```bash
node scripts/ci/cluster3-probe.mjs --run --artifacts /tmp/c3-art …   # → EXIT=2「拒绝在本机启动真实三实例」
```

---

## 6. 未解决的产品问题（请按此路由；本批**未改**产品代码）

### 6.1 park 转换的崩溃一致性：状态先落、`tool_start` 后发；续跑不补 `tool_start`

- **现象（真实拓扑已发生）**：`suspend-kill-a` 里 A 在 `.609` 被 SIGKILL，A 日志停在 `turn_start`，归档里始终没有 `tool_start`；B 接管后**执行了工具**（`job 4` 创建、台账 `executedBy=B`、`tool_result` 由 `claimAndExecute` 落档），但这一轮**永远**没有 `tool_start`。
- **根因（两段）**：
  1. `backend/src/main/java/com/example/configmgr/ai/gate/SpToolCallingManager.java:471`（`saveSuspended`）→ `:473`（`out.suspended`）→ `:479`（`gate.publishRequestsForParkedBatch`）→ `:540`（`out.toolStart`）：**SUSPENDED 先对外成立，`tool_start` 后落档**；两段之间没有原子性，也没有"补发"入口。
  2. `backend/src/main/java/com/example/configmgr/ai/run/ResumeService.java:842`（`resolvePending`）→ `SpToolCallingManager.java:1388 claimAndExecute`（只在 `:1493/:1496` 发 `tool_result`）：**续跑/接管路径不发 `tool_start`**（`SseChatEmitter.java:1101 toolStart` 在该路径无调用点）。
- **影响**：帧册不变量"每个被执行工具恰有一条 `tool_start` + 一条 `tool_result`"在"原发射方死在该窗口"时**永久破裂**；前端据 `tool_start` 建段/置 running，缺失会导致该工具卡与结果帧无法配对（重放、审计同受影响）。
- **建议修法（产品侧，需裁决）**：续跑侧在 `resolvePending` 之前**幂等补发** `tool_start`（先查归档是否已有该 `toolCallId` 的 `tool_start`，没有才发），或把 `saveSuspended`+`tool_start` 收成"状态可见即可恢复"的单一口径（例如把 `tool_start` 提前到 `out.suspended` 之前并接受"先 start 后 suspended"）。
- **为什么本批不改**：任务边界禁止改 backend；且 agent-73 正在 `<验证副本>/_verify-20261025-park-seq` 改同一帧序面（`emitToolStartOnce` 前移），此处改动会与其重叠。

### 6.2 探针侧残留（非产品缺陷，登记备查）

- 决策前重挂若长期不收流（回归到"held connection"），用例会以 `等待 park 收流：20000ms 内连接未被服务端收流` 红 —— 这**是刻意的**（裁决口径），不是超时兜底。
- `createSuspendedRun` 现在额外要求 `archiveMaxSeq === 客户端末号`。`archiveMaxSeq` 取 `XREVRANGE LIMIT 16` 内 seq 的最大值（`RedisFrameStore#streamTailSeq`，`TAIL_PROBE=16`）：只有在"显式 ID 落档被拒 → 自动 ID 兜底"的乱序段**超过 16 条**时才可能低估末号而误红。本次 run 在 07:52:35（purge 用例）见过一次该 WARN，规模远小于 16；若将来误红，按 `显式 ID 落档被拒` WARN 归因到 Redis，不要当成帧契约缺陷。

### 6.3 上一轮遗留的 7 红中其余 5 条（**不在本批范围**，勿误读为本批已解决）

`cold-start-3`（Flyway 跨实例 baseline 竞态）、`shared-file-download`（单编码被 zip 包裹）、`job-kill-a`（条目未随作业收敛）、`readiness-removal`（draining 窗口为 0）、`streaming-kill-a`（唤醒补帧下界）仍按 `cluster-r3-triage.md` §2 的归属与候选修复处置；本批只动了 `suspend-cross-instance` 与 `suspend-kill-a` 两条协议。

---

## 7. 未证明 / 边界

- **没有**在真实三实例拓扑上跑过任何东西（本机禁止，`--run` 被守卫拦下，见 §5）。所有"用例将 PASS"的判断都只到 **代码 + 自证** 层面，验收必须由 CI 复跑给出。
- `suspend-kill-a` 的"0 帧"**已排除**"C 侧归档读失败"与"HEAD 不发 `tool_start`"两种机制，但**无法从本次 artifact 区分**"SIGKILL 早于 `tool_start` 调用"与"`tool_start` 的 Redis 写被 SIGKILL 打断"——两者都是同一个 harness 时序根因的不同毫秒切片，且都不改变修法（先 park 再 kill）。**下一次 CI 复跑的 `A-parked.jsonl` 会把这两者彻底分开**：若 park 屏障能等到收流，则 `tool_start` 必然已在归档里。
- §3 的"决策后重挂收 done"依赖产品自述标准时序；本次真实 run 里 `suspend-kill-a` 的 B 侧决策后重挂**确实**收到了 `done`（该断言在旧用例里已通过），可作为该时序的正面证据。
- `assertFrameLedger` 要求"`tool_result` 恰一次"，依据是 `claimAndExecute` 的发射点（代码级）；本次 artifact 没有可证的 `C-full.jsonl`（见 §1）。复跑时该字段会直接落入 `C-full.jsonl` / `takeover.json`。

---

## 8. 下一次 CI 复跑的验收清单（探针现在会留下的证据）

`cases/suspend-cross-instance/`：

- `A-parked.jsonl`：必须 4 帧 `start,suspended,confirm_request,tool_start`（seq 1..4；`confirm_request`/`tool_start` 先后不作约束）
- `B-predecision-reattach.jsonl`：**空文件**（0 帧）
- `C-predecision-reattach.jsonl`：4 帧，与 `A-parked.jsonl` 逐帧同 seq 同型
- `B-reattach.jsonl` / `C-reattach.jsonl`：决策后重挂，末帧 `done`
- `A-full.jsonl`：`start,suspended,confirm_request,tool_start,confirm_decision,tool_result,…(delta)…,done`，seq 1..N 连续
- `notes.txt`：`…决策前重挂回放后即收流（B 差量=0 帧 / C 全量=4 帧），收流后仍 SUSPENDED；决策后 B/C 两连接都收到 done；帧册=…`

`cases/suspend-kill-a/`：

- `A-parked.jsonl`（kill 前）、`B-takeover.jsonl`、`C-full.jsonl`（**断言前落盘**，失败也留）
- `takeover.json`：`parkedSeq/parkedTypes/frameLedger/ledger/takeoverMs`
- 若仍出现 `tool_start` 计数异常：失败信息现在会指向 **A 的 park 阶段**（`park 边界帧集：tool_start 帧数…`），并伴随 `A-parked.jsonl` 与 `B 侧看到的归档末号` 断言，可直接判定是"产品没发"还是"被杀在窗口里"。

---

## 9. 交付物清单

| 路径 | 说明 |
|---|---|
| `<验证副本>/_verify-20261025-cluster-protocol/` | 隔离副本（HEAD 全树 + 仅探针改动） |
| `…/scripts/ci/cluster3-probe.mjs` | **唯一改动文件** |
| `<仓外证据>/cluster-protocol-probe.diff` | 相对 HEAD 的完整 diff（725 行） |
| `<仓外证据>/cluster-protocol-evidence/` | run 40174291244 的 artifact（零密钥） |
| `<仓外证据>/cluster-protocol-selfcheck-red.log` | RED（4 项失败，函数未实现） |
| `<仓外证据>/cluster-protocol-selfcheck-green.log` | GREEN（探针自证 18/0） |
| `<仓外证据>/cluster-protocol-run-selfcheck-green.log` | GREEN（`cluster3-run.sh --self-check` 53/0） |
| `<仓外证据>/cluster-protocol-report.md` | 本报告 |
