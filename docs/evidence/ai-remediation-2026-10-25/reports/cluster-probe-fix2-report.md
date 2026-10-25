> 来源=<仓外证据>/cluster-probe-fix2-report.md（2026-10-25 归档）· 仓外正式过程报告，属历史证据，不代替现行基线/决策文档（docs/02、docs/03、docs/adr/）
# 集群探针 fix2 报告（I1 / N1 / N2 / N3）

- 日期：2026-10-25
- 复审来源：`<仓外证据>/cluster-probe-rereview1.md`（C1 已由控制器裁决关闭；本批只做 I1/N1/N2/N3）
- 交付文件：`<验证副本>/_verify-20261025-cluster-protocol/scripts/ci/cluster3-probe.mjs`
  （LF，5345 行，md5 `730b0093c00a45f6f5943a55390a9aea`）
- 增量补丁（**对当前主仓 v2 探针**，repo 相对路径，`git apply` 可用）：
  `<仓外证据>/cluster-probe-fix2-incremental.diff`（885 行；`+447/-112`）
- 基线口径（见 §0）：探针与"多实例挂起/接管"候选分支**同批合入**；候选已含 `ensureToolStart`、
  续跑结算前补发、park 退出原子切换（latch）与 frontend-result 路径。**本批不复制、不评判、不重开那批产品补丁。**

---

## 0. C1 与基线措辞（不重开，只对齐）

控制器裁决：候选分支**已包含** `SseChatEmitter#ensureToolStart`、两处续跑补发、latch 与 frontend-result 路径
（独立复审 + 定向测试），探针与该候选同批合入 —— 产品前置已交付。

据此本批只做措辞对齐，不再论证 C1：

- 探针文件头新增 **「合入基线」** 段（`:28-32`）：明确候选分支含上述补丁、且这些是**前置产品补丁**，
  `suspend-kill-a` 的 `tool_start` 不变量只有在候选分支上才预期为绿；单独贴到 `ae883fb` 上跑真实拓扑会按预期红。
- `createSuspendedRun`（`:2081`）与 `caseSuspendKillA`（`:3380`）的注释同步改为"随本探针同批合入的候选分支"。
- `cluster-probe-fix1-report.md` 顶部补 **基线更正块**（原 v1 版"`git archive HEAD` + 只改探针"容易被读成
  "贴到 HEAD 就该全绿"），并修正 §0/§1.2 中 `toolStartBackfilled` 的旧表述（已由 N3 改名）。
- 隔离副本仍然只有探针（`git archive HEAD` 全树 + 探针），**不复制产品文件**；自证与补丁生成都不需要产品源码。

---

## 1. I1 —— 载荷/帧型断言的三个空洞

### 1.1 (a) 决策前 C 回放：从"固定键投影"改为"逐帧全字段比对"（`:532-651`）

- 删除 `PAYLOAD_PROJECTION_KEYS` / `payloadProjection`（白名单漏 `toolCalls`/`runId`/`context`/`preflight` 等）。
- 新增 `serverFields(frame)`（去掉客户端解析附加的 `_recvAtMs`/`_event`，其余**全部**字段保留）与
  `stableStringify`（递归按 key 排序，避免两条序列化路径的 key 顺序不同造成假红）。
- `assertPreDecisionReplay(frames, parkedFrames, toolCallId, label)`（`:628`）现在：
  1. 逐帧全字段比对（`stableStringify(serverFields)`）；
  2. 不含终帧；
  3. **就地调用 `assertParkedPayloads`**（复审明确要求：回放既然与 park 帧集逐帧相等，park 载荷断言必须同样成立）。
- 因此 `suspended.toolCalls` 被清空、`start.runId` 被换、`context` 载荷被改坏、`atMs`/`preflight` 被改写都会红。

### 1.2 (b)(c) B/C 决策后连接与 B 差量：加帧型/载荷（`:587-717`）

- 新增 `assertConfirmedToolPayloads(frames,{toolCallId,expectedName,label})`：`tool_result.ok===true`、`outcome==='ok'`、
  `name` 一致、`result` 非空；`confirm_decision.decision==='approve'`、`approved===true`；≥1 帧 `delta` 且文本非全空。
- 新增 `assertDeltaFrames(frames,{toolCallId,expectedName,label,allowToolStart})`（`:658`），用于**差量连接**（`lastSeq+1` 起）：
  - 每个非心跳帧必须带整数 seq；帧型必须在产品 16 型白名单内；不得出现 `error`；
  - **不得重发前缀帧**（`start`/`suspended`/`confirm_request` 计数必须为 0）；
  - `done` 恰一帧且是末帧；`confirm_decision`/`tool_result` 各恰一帧并做载荷校验；
  - `tool_start`：非早杀路径必须 0（它一定在 `lastSeq` 之前）；早杀路径允许 ≤1，且若出现必须 `name` 一致、
    有 `args`、且先于 `tool_result`。
- 落到用例：
  - `suspend-cross-instance` 的 B 差量 → `assertDeltaFrames(..., allowToolStart:false)`（`:2810` 附近）；
  - 该用例的 **C 决策后连接是从 0 全量重挂** → 直接跑整本 `assertFrameLedger`（`:2830` 附近），
    不再只验 seq（这是改动里最强的一条：C 连接自己的交付内容必须自洽）；
  - `suspend-kill-a` 的 B 接管差量 → `assertDeltaFrames(..., allowToolStart:true)`。

### 1.3 自证（确定性的正/反例）

- `selfTestPreDecisionReplay`（`:4950`）扩到 **2 正例 + 7 反例**：缺 tool_start、终帧、帧序、`name` 篡改、
  `toolCalls` 清空、`start.runId` 替换、含 `context` 的正例 + `context` 载荷篡改。
- 新增 `selfTestDeltaConnectionFrames`（`:4783`）：正例（无/有 tool_start）+ 8 反例
  （缺 result、`ok=false`、空 `result`、`decision=reject`、空 delta、白名单外帧型、done 之后又有业务帧、
  suspended 前缀帧泄漏、非早杀路径出现 tool_start）。

---

## 2. N1 —— 状态/ledger 证据也先落盘（`:2755-2860`、`:3400-3520`）

| 证据点 | 现在的写法 |
|---|---|
| `suspend-cross-instance` 的 `/runs/current`（决策前重挂收流后） | `evidenceFirst((body)=>saveEvidence('runs-current-b.json', body), ()=>still, ()=>{5 项断言})`：响应先落盘再断言（含 `archiveMaxSeq` 权威值） |
| 该用例的 `run-detail` | 先写 `run-detail.json`（`status/ledgerCounts/ledger/frameLedger/lastSeqScope/parkedSeq/preDecisionReplayFrames/bDeltaSeqs/cSeqs`），再断言 DONE/台账计数/条目数/executedBy/`outcome`/`resultText` |
| 该用例的 jobs / history | `jobs` 先写进 `run-detail.json` 再断言数量；`history` 先写 `{b,c}` 再断言相等 |
| `suspend-kill-a` 的 `/runs/current`（kill 后） | 先 `saveEvidence('runs-current-b.json', current)` + 增量 `takeover.json`，再断言 found/runId/status/awaitingExternal/pendingEntries |
| 该用例的 `run-detail` / ledger / jobs / history | `takeover.json` **增量**写：`snapshotStatus/ledgerCounts/ledger` → 再断言；`jobs` → 再断言；`historyTotal` → 再断言；`run-detail.json` 另存一份台账投影 |

- 自证 `selfTestStateEvidenceOrder`（`:4859`）：按用例的真实调用序模拟 4 个证据点（current/detail/jobs/history），
  每处断言**必抛**，断言调用序列恰为 `write:current → assert:current → write:detail → … → assert:history`，
  即"先写后断言 + 失败仍留证"。

---

## 3. N2 —— 末号口径改名/对齐（`:719-760`）

- `assertFrameLedger` 的 `done.seq` 断言改为：
  `done.seq 必须等于本次全量回放的末号（replay tip，不是服务端 archiveMaxSeq）`，
  并在返回值上新增 **`lastSeqScope: 'full-replay'`**（常量 `FULL_REPLAY_TIP_SCOPE`）。
- 注释明确记录：终态会清 `session→current` 索引（`RunStore#clearSessionCurrent`），`/runs/current` 的
  `archiveMaxSeq` 在终态**不可读**；服务端权威末号只在 SUSPENDED/RUNNING 时可用 —— 因此
  **park 边界** 用 `archiveMaxSeq === lastSeq`（权威等值），**kill 后** 用 `archiveMaxSeq >= lastSeq`（不得回退），
  终态帧册则只声明 replay-tip 口径。
- 证据里带上该口径：`run-detail.json.lastSeqScope`、`takeover.json.lastSeqScope/frameLedgerTipScope`。
- 自证 `selfTestLedgerTipScope`（`:4901`）：`lastSeqScope==='full-replay'`、`lastSeq===done.seq`、缺 `done` 必红。

---

## 4. N3 —— 早杀观测改名、去掉"谁补发"的推断（`:2177`、`:3396-3420`、`:3465`）

- 新增纯函数 `killToolStartObservation(frames, toolCallId)` → `{ toolStartSeenBeforeKill, toolStartNotObservedBeforeKill }`：
  只表达"**客户端在 SIGKILL 之前有没有看到 `tool_start`**"，**不含**任何 `toolStartBackfilled` 之类来源字段。
- `caseSuspendKillA` 用它写 `park-transition.json` 与 `takeover.json`；注释与 caseLog 删掉
  "false ⇒ 由接管方补发"的推断（A 可能在客户端关闭后、SIGKILL 前落档，客户端证据不可区分）。
- 自证 `selfTestKillObservation`（`:4916`）：kill 前已见/未见两态，并断言返回对象**不得**包含 `toolStartBackfilled` 键。

---

## 5. RED → GREEN

### 5.1 RED（变异法：把 v3 的 5 处新强度退回 fix1 形态）

```bash
cd <验证副本>/_verify-20261025-cluster-protocol
node <仓外证据>/_make-mutation-fix2.cjs        # 生成 scripts/ci/_mutation-probe.mjs（5 处变异）
node scripts/ci/_mutation-probe.mjs --self-check       # → EXIT=1，PASS 21 / FAIL 6
rm -f scripts/ci/_mutation-probe.mjs
```

```
FAIL  决策后差量连接的帧型/载荷（I1(b)(c)）：自检差量允许的 tool_start 帧数：期望 1，实得 0
FAIL  状态类证据先落盘再断言（current/detail/jobs/history）：… 实得 ["assert:current","assert:detail","assert:jobs","assert:history"]
FAIL  帧册末号口径 = 本次全量回放末号（N2）：自检末号口径：期望 "full-replay"，实得 undefined
FAIL  早杀观测只记"kill 前是否已见"（N3）：自检：观测对象不得包含"谁补发"的推断字段
FAIL  决策前重挂契约（回放完整且不收终帧）：自检：回放把 start.runId 换掉必须红
FAIL  证据先落盘再断言（断言失败仍留证据）：… 实得 ["assert"]
自证结果：PASS 21 / FAIL 6
```

日志：`<仓外证据>/cluster-probe-fix2-red.log`
（变异项：M1 投影退回 type+seq、M2 差量只验 seq、M3 evidenceFirst 不写、M4 不声明末号口径、M5 恢复 `toolStartBackfilled`）

### 5.2 GREEN（真实交付文件）

```bash
node --check scripts/ci/cluster3-probe.mjs        # OK
node scripts/ci/cluster3-probe.mjs --self-check   # EXIT=0 → PASS 27 / FAIL 0
bash scripts/ci/cluster3-run.sh --self-check      # EXIT=0 → PASS 53 / FAIL 0
```

日志：`<仓外证据>/cluster-probe-fix2-green.log`、`<仓外证据>/cluster-probe-fix2-run-selfcheck.log`

新增 4 条自证（均含反例）：

```
PASS  决策后差量连接的帧型/载荷（I1(b)(c)）（2 正例 + 8 个反例）
PASS  状态类证据先落盘再断言（current/detail/jobs/history）（4 项状态证据的顺序）
PASS  帧册末号口径 = 本次全量回放末号（N2）（scope=full-replay）
PASS  早杀观测只记"kill 前是否已见"（N3）（kill 前已见/未见两态）
```

### 5.3 补丁验证

```text
基线 = 当前主仓工作树探针（v2，md5 92d6ae6917532082db2ca230aa2ec2ed）
git apply --check  → OK
git apply          → 结果与交付文件逐字节一致（LF 归一后）
```

---

## 6. 改动面（相对 v2 的增量）

| 位置（v3 行号） | 内容 |
|---|---|
| 27-44 | 文件头：合入基线（候选含 `ensureToolStart`/补发/latch/frontend-result）、早杀口径、证据优先、末号口径 |
| 532-551 | `serverFields` + `stableStringify`（替换固定键投影） |
| 554-585 | `assertParkedPayloads` 增强（`suspended.runId`、`toolCalls[].kind/arguments`、`irreversible` 类型） |
| 587-626 | 新增 `assertConfirmedToolPayloads`；`assertCompletedPayloads` 复用之 |
| 628-656 | `assertPreDecisionReplay`：全字段比对 + 就地 park 载荷断言 |
| 658-717 | 新增 `assertDeltaFrames`（差量连接的帧型/载荷/前缀帧/tool_start 策略） |
| 719-760 | `FULL_REPLAY_TIP_SCOPE` + `assertFrameLedger` 末号口径与返回值 |
| 2177-2186 | 新增 `killToolStartObservation`（观测，无来源推断） |
| 2734-2860 | `caseSuspendCrossInstance`：C 回放全字段+载荷、B 差量载荷、C 全量帧册、current/detail/jobs/history 证据前置 |
| 3389-3520 | `caseSuspendKillA`：观测改名、current/detail/ledger/jobs/history 证据前置与增量写、B 差量载荷 |
| 4228-4236 | `summary.md` 证据清单（含状态类证据与 `lastSeqScope` 口径） |
| 4579/4783/4859/4901/4916/4950 | 自证：证据优先（原）、差量帧型/载荷、状态证据顺序、末号口径、早杀观测、决策前回放 |

**未改**：场景 ID/顺序（16 条）、A→B→C 路由、`proxy-no-sticky`/`same-tcp-across-abc`、早杀覆盖、既有更强检查；
未改任何产品文件、未起本机拓扑、未用 8081/5202。

---

## 7. 残差边界（如实登记，不外推）

1. **末号权威性（N2 的残余）**：终态帧册只能声明"本次全量回放末号"；服务端 `archiveMaxSeq` 在终态不可读
   （session→current 被清）。若回放在 `done` 之后被截断（迟到的业务帧没进回放），本断言无法发现 ——
   这是端点面缺口，不是断言写法问题。缓解面：C 连接（从 0 全量）与 A 全量是两条独立读，且 B 差量必须等于其连续后缀，
   三者同时错位才会漏。
2. **客户端无法确定性命中早杀窗口**：仍成立（观测延迟 ≫ 发帧延迟）。强制命中在候选分支的
   `ResumeToolStartBackfillConformanceTest`；探针负责端到端不变量 + 窗口可达性。
3. **`assertParkedPayloads` 的字段面**依据当前发射点（`confirmRequest`/`suspended`/`PendingToolCall#asMap`）；
   产品改字段名会以明确断言红（刻意的"协议面必须显式跟上"）。
4. **`/runs/current` 的 `archiveMaxSeq`** 取 `XREVRANGE LIMIT 16` 的 max（`RedisFrameStore#streamTailSeq`）：
   仅在超过 16 条乱序（显式 ID 落档被拒后的兜底）时才可能低估，属于产品工程近似，本批未改。
5. **未跑真实拓扑**：27/27 与 53/53 都是自证层面的证明；真实三实例验收仍须 CI 复跑。
6. 复审提到的 I4/I5/M1-M9 未动（超范围）。

---

## 8. 交付物

| 路径 | 说明 |
|---|---|
| `<验证副本>/_verify-20261025-cluster-protocol/scripts/ci/cluster3-probe.mjs` | v3 交付文件（LF，md5 `730b0093c00a45f6f5943a55390a9aea`） |
| `<仓外证据>/cluster-probe-fix2-incremental.diff` | **增量补丁（对当前主仓 v2，885 行，`+447/-112`）** |
| `<仓外证据>/cluster-probe-fix2-red.log` | 变异 RED（PASS 21 / FAIL 6） |
| `<仓外证据>/cluster-probe-fix2-green.log` | GREEN（探针自证 27/0） |
| `<仓外证据>/cluster-probe-fix2-run-selfcheck.log` | GREEN（`cluster3-run.sh --self-check` 53/0） |
| `<仓外证据>/_make-mutation-fix2.cjs` | RED 的变异生成器（可复现，非交付代码） |
| `<仓外证据>/cluster-probe-fix1-report.md` | 已补基线更正块（C1 裁决口径 + N3 改名） |
