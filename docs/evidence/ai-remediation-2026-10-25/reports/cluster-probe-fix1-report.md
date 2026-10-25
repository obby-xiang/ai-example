> 来源=<仓外证据>/cluster-probe-fix1-report.md（2026-10-25 归档）· 仓外正式过程报告，属历史证据，不代替现行基线/决策文档（docs/02、docs/03、docs/adr/）
# 集群探针复审修复（B1/B2 + I1-I3）· 修复报告

> **基线更正（fix2，2026-10-25）**：本报告 v1 版把基线写成"`git archive HEAD` + 只改探针"，容易被读成
> "探针单独贴到 `ae883fb` 上就该全绿"。实际口径是：**探针与"多实例挂起/接管"候选分支同批合入**，
> 该候选已含 `SseChatEmitter#ensureToolStart`、续跑结算前补发、park 退出原子切换（latch）与
> frontend-result 路径（均有独立复审与定向测试）。隔离副本 `_verify-20261025-cluster-protocol/`
> 只承载**探针**（用于自证/补丁生成），**不复制也不评判那批产品补丁**（控制器裁决：产品前置已交付，
> 不重开 C1）。因此 §0/§1.2 的"B2 已修"均**以该候选分支为基线**；单独贴到 HEAD 上跑真实拓扑时，
> `suspend-kill-a` 的 `tool_start` 不变量会按预期红（那正是用例要暴露的产品缺口）。
> 另：`toolStartBackfilled` 字段在 fix2 已改名为观测字段 `toolStartNotObservedBeforeKill`
> （不再推断"谁补发"），见 `cluster-probe-fix2-report.md`。

- 日期：2026-10-25
- 仓库基线：`<主仓>` HEAD `ae883fb` + 候选工作树（产品补丁，只读；主仓未被本批触碰）
- 隔离副本：`<验证副本>/_verify-20261025-cluster-protocol/`（`git archive HEAD` 全树；**唯一改动** = `scripts/ci/cluster3-probe.mjs`）
- 合入基线：与候选分支同批合入（见上方基线更正）
- 复审来源：`<仓外证据>/cluster-probe-task-review.md`（B1、B2、I1-I3；另对齐 I6 的表述问题）
- 纪律：未起真实三实例/容器/端口（无 8081/5202）、未改任何 backend/frontend/产品代码、未派子代理、未动主仓工作树。

---

## 0. 结论速览

| 复审项 | 判定 | 本批处理 |
|---|---|---|
| **B1** 证据仍在断言之后 | 阻断 | **已修**：`A-parked` / `A-park-transition` / `B-predecision` / `C-predecision` / `B-reattach` / `C-reattach` / `B-takeover` / `C-full` / `A-full` 全部改为**先落盘再断言**（`evidenceFirst` 或 `finally`）；`takeover.json` 增量写 5 次；`fullReplay` 失败（4 次重试都失败）也留最后一份帧。新增自证 `selfTestEvidenceBeforeAssertions`（断言必抛仍留证、且写先于断言）。 |
| **B2** 早杀窗口覆盖被移除 | 阻断 | **已修**：`suspend-kill-a` 恢复**拿到 `confirm_request` 即 SIGKILL**（`waitForPark:false`，不等 park 收流）。断言改为产品补发后的**帧册不变量**（`tool_start` 恰一次且先于 `tool_result`），并记录观测字段 `toolStartSeenBeforeKill` / `toolStartNotObservedBeforeKill`（fix2 改名，不推断来源）。park 屏障只保留在非 kill 的编排用例（`suspend-cross-instance` / 幂等）。 |
| **I1** 只验 type+seq，内容可 false-pass | 高危 | **已修**：新增 `assertParkedPayloads` / `assertCompletedPayloads`（`confirm_request.name/args/summary`、`suspended.toolCalls[].toolCallId/name`、`tool_start.name/kind/args==card.args`、`tool_result.{ok,outcome,name,result}`、`confirm_decision.{decision,approved}`、`delta.text` 非空）；决策前回放改为**逐帧载荷投影比对**；跨实例补 `ledger[0].outcome==='ok'` + `resultText` 非空。 |
| **I2** 缺 seq 被静默过滤 / B 差量未验连续 | 高危 | **已修**：`seqsOf` 对**非心跳**帧缺整数 seq **直接抛错**；`assertFrameLedger` 断言 `done.seq === 归档末号`；B 差量新增 `assertDeltaContiguous(from=lastSeq+1)`，并与 C/A 全量做"连续后缀"等价断言（不重不漏）。 |
| **I3** park 帧集过窄（会误杀心跳/文本/压缩/重试） | 高危 | **已修**：拆成 `assertCardReachedFrameSet`（卡已到：必需 start/suspended/确认卡，允许 `context/message_start/delta/message_end/retry/heartbeat` 噪声，心跳不占号）与 `assertParkedFrameSet`（收流后再多要求 `tool_start` 恰一次）。不再把"4 帧"当成唯一合法形态。 |
| I6 表述与代码不符 | 事实性 | **已修**：本报告 + 旧报告 `cluster-protocol-report.md` 顶部 v2 更正块；探针内 `summary.md` 的证据清单文字同步改写。 |
| I4 / I5 / M1-M9 | 超出本批范围 | **未动**（用户明确"只做 B1/B2 与 I1-I3"）。I4 的租约窗口另有说明见 §7。 |

**改动文件：`scripts/ci/cluster3-probe.mjs`（1 个文件；相对 v1 `+809/-227`，相对 HEAD `+1146/-114`）。**

---

## 1. 逐项落地（精确到函数/行为）

### 1.1 B1 证据先落盘（复审 §1.B1 / §6）

| 证据文件 | 落盘点 | 触发失败时还在吗 |
|---|---|---|
| `A-parked.jsonl` | `createSuspendedRun` 收到卡时写一次 + `settleParkBoundary` 的 `finally` 写一次（收流/超时/重置都写） | 是 |
| `A-park-transition.jsonl`（早杀路径） | `createSuspendedRun` 收到卡时写 + `caseSuspendKillA` 在 SIGKILL 前再写一次 + `finally` 兜底 | 是 |
| `B-predecision-reattach.jsonl` / `C-predecision-reattach.jsonl` | `waitClosed` 后立即写，再断言 `how==='end'`；`finally` 兜底（超时/重置也写） | 是 |
| `B-reattach.jsonl` / `C-reattach.jsonl` | `waitTerminal` 一到立即写，再断言 done/seq；`finally` 兜底（终帧缺失也写） | 是 |
| `B-takeover.jsonl` | 同上（`waitTerminal` 后立即写 + `finally`） | 是 |
| `C-full.jsonl` / `A-full.jsonl` | `fullReplay(...)` 的 `finally`：**成功或 4 次重试全失败**都写最后一份 | 是 |
| `confirm.json` | `evidenceFirst(write, snapshot, assertions)`：先写回执（含非 200 的 body）再断言 | 是 |
| `park-transition.json` | SIGKILL 之前写 | 是 |
| `takeover.json` | 增量写 5 次（kill 后 / `currentRun` 后 / 决策后 / 接管终帧后 / 收尾） | 是（保留最后一版） |

机制：新增 `evidenceFirst(write, snapshot, assertions)`（`cluster3-probe.mjs:440`），语义 = **先写快照、再跑断言、断言异常照常外抛**；流类证据用 `try/finally` 的 `finally` 兜底。自证 `selfTestEvidenceBeforeAssertions`（`:4425`）用"断言必抛"的用例断言调用序为 `write → assert` 且异常不被吞。

### 1.2 B2 早杀覆盖恢复（复审 §1.B2 / §4.1）

- `createSuspendedRun(..., { waitForPark:false })`（`:2001`）：**收到 `confirm_request` 就返回**，不 `waitClosed`、不做 park 帧集断言、不做 `archiveMaxSeq === lastSeq`。
- `caseSuspendKillA`（`:3261`）：返回后立刻 `killInstance('a')`（与 v1 之前的原探针同一时序）。
- 断言改为不变量：`assertFrameLedger` 要求 `tool_start` 恰一次且 `tool_start < tool_result`；若原实例在窗口内没落档，则由候选分支的 `SseChatEmitter#ensureToolStart`（续跑结算前按归档幂等补发）补上 —— **补发坏掉就会红**，这正是要测的路径。
- 证据只记录**观测**（不推断来源）：`park-transition.json.toolStartSeenBeforeKill` / `toolStartNotObservedBeforeKill`（fix2 起的字段名；v1 的 `toolStartBackfilled` 已废弃 —— A 在客户端关闭后、SIGKILL 前落档与续跑补发在客户端证据上不可区分）。
- 帧证据 `A-park-transition.jsonl` = SIGKILL 前客户端实际看到的帧（可能只有 `start/suspended/confirm_request`）。
- **能力边界（如实登记，不粉饰）**：客户端触发不可能**确定性**命中"`tool_start` 落档之前"这一毫秒窗口（同线程先发帧、客户端才看到前一帧；观测延迟 ≫ 发帧延迟）。强制命中需故障注入，由产品侧 `ResumeToolStartBackfillConformanceTest` 覆盖；本用例负责**端到端不变量 + 窗口可达性**（不再用屏障把窗口关死）。因此 B2 的"覆盖不再被移除"成立，"每次都必然走到补发分支"**不成立**，报告不再作此声明。

### 1.3 I1 载荷断言（复审 §2.I1 / §5）

| 帧 | 断言 |
|---|---|
| `confirm_request` | `name` 非空、`args`/`summary` 存在 |
| `suspended` | `toolCalls` 是数组且列出该 `toolCallId`，其 `name` 与确认卡一致 |
| `tool_start` | `name === confirm_request.name`、`kind === 'CONFIRM'`、`args` 与 `confirm_request.args` **逐字节一致** |
| `tool_result` | `ok === true`、`outcome === 'ok'`、`name` 一致、`result` 非空 |
| `confirm_decision` | `decision === 'approve'`、`approved === true` |
| `delta` | 至少一帧、每帧 `text` 是字符串、不得全空 |
| 台账（跨实例） | `outcome === 'ok'`、`resultText` 非空 |

决策前回放的比对从"type + seq 数组"升级为**逐帧载荷投影**（`payloadProjection`：`type/seq/toolCallId/name/kind/args/summary/ok/outcome/code/decision/approved/status/text/messageId`），忽略客户端侧的 `_recvAtMs/_event`。自证 `selfTestBadPayloadRejected`（`:4546`）含 8 个反例（`ok=false`、空 `result`、空 `name`、`args` 不一致、`decision=reject`、空 delta、无 delta、`toolCalls` 空）。

### 1.4 I2 seq / 连续性 / 末号（复审 §2.I2）

- `seqsOf(frames,label)`：非心跳帧缺整数 seq ⇒ 抛 `第 N 帧（type=X）缺少整数 seq —— 缺号不得被跳过`（`:413`）。所有旧调用点（含 A/C 全量、B 差量）自动受益。
- `assertFrameLedger`：`done.seq === 归档末号`（末号 = 全量回放 seq 列末项）+ `done` 是最后一个业务帧。
- B 差量：`assertDeltaContiguous(frames, lastSeq+1)`（`:618`）—— 5,7 ⇒ 红；且与 C 全量尾部做"连续后缀"等价断言（`B 差量必须是 C 全量的连续后缀（不重不漏）`）。kill 用例对 A 全量做同样断言。
- 心跳是唯一允许无 `seq` 的帧型（产品 `FrameStore#append` 对心跳 no-op）；`assertCardReachedFrameSet` 的连续性只看业务帧。

自证 `selfTestMissingSeqRejected`（`:4530`）：缺 seq 的业务帧、`done` 缺 seq、中间帧缺 seq 三个反例 + 心跳例外正例；`selfTestDeltaContiguous`（`:4625`）：5,7 / 起点不符 / 重复号 三个反例。

### 1.5 I3 允许的 pre-park 噪声（复审 §2.I3）

- `PRE_PARK_NOISE_TYPES = ['context','message_start','delta','message_end','retry', HEARTBEAT]`（`:461`）。
- `assertCardReachedFrameSet`（`:477`）：必需 `start`（首业务帧）+ `suspended`（先于确认卡）+ 该 `toolCallId` 的 `confirm_request` + 无终帧；其余业务帧型必须在 `{start,suspended,confirm_request,tool_start} ∪ 噪声` 内；业务 seq 从 1 连续。
- `assertParkedFrameSet`（`:506`）= 上述 + **`tool_start` 恰一次**（收流完成 ⇒ 必已落档）。`confirm_request` 与 `tool_start` 的先后**不作约束**（兼容 C1 前/后两种实现）。
- 自证 `selfTestAllowedPreParkFrames`（`:4493`）：`context + heartbeat + message_start + delta + message_end + retry + heartbeat` 混排仍 PASS；"卡之前出现未允许帧型"、"卡之前出现缺 seq 的业务帧"各一个反例。

### 1.6 原断言与场景面（复审 §7）

- 16 个场景 **ID 与顺序完全未变**（`cold-start-3` … `redis-outage-recovery`）；`suspend-kill-a` 只改了标题文字（标注"早杀"），ID 不变。
- `suspend-kill-a` 原有断言**逐条保留**（`/runs/current` 五项、决策 200/accepted/outcome/storedBy、B 终帧 done、C 全量 done、`tool_start`/`tool_result` 恰一次、run DONE、台账三项、jobs、history、A 死后 B/C 下载），并**加强**：B 差量连续性、`confirm_decision` 恰一次、帧册顺序/白名单/载荷、`archiveMaxSeq` 不回退。
- `suspend-cross-instance` 原有断言保留（含 `storedBy=C`、`executedBy ∈ {A,C}`、jobs=1、history 相等），另加：决策前回放逐帧载荷一致、收流后仍 SUSPENDED/PENDING、台账 `outcome`、B 差量连续后缀。
- **A→B→C / 无粘滞**：suspend 用例全部用 `directUrl(a|b|c)` 明指定实例；`proxy-no-sticky`、`same-tcp-across-abc` 未改动。说明：`X-Cluster-Node` 由 HAProxy 注入（`cluster3-run.sh` 的 `http-response set-header`），**直连实例的响应没有这个头**，所以直连路径无法用它断言节点 —— 不加会 false-fail 的断言（这一点与复审 M5 的建议不同，理由在此）。

---

## 2. 改动文件与补丁

**唯一改动文件**：`scripts/ci/cluster3-probe.mjs`
（隔离副本 `<验证副本>/_verify-20261025-cluster-protocol/scripts/ci/cluster3-probe.mjs`，LF，md5 见 §5）

| 位置（v2 行号） | 内容 |
|---|---|
| 27-45 | 文件头：park 屏障（仅非 kill 编排）/ 早杀覆盖 / 决策前重挂裁决 / 证据优先 / 允许噪声 |
| 402-417 | `HEARTBEAT_TYPE`、`isHeartbeat`、严格 `seqsOf` |
| 440-458 | `evidenceFirst`、`checkNonEmptyString`、`checkPresent` |
| 461-536 | `PRE_PARK_*`、`assertCardReachedFrameSet`、`assertParkedFrameSet` |
| 546-600 | `assertParkedPayloads`、`assertCompletedPayloads`、`payloadProjection` |
| 605-618 | 回放逐帧载荷比对、`assertDeltaContiguous` |
| 652-700 | `assertFrameLedger`（seq 存在性/done.seq/顺序/载荷） |
| 1906-1946 | `fullReplay`/`fullReplayToTerminal` 增加 `evidenceName`（失败也落盘） |
| 1977-1999 | `settleParkBoundary`：`finally` 落盘 → 再断言 → park 载荷断言 |
| 2001-2070 | `createSuspendedRun`：`waitForPark` 两种模式、证据优先、早杀分支 |
| 2098 | 用例标题（`suspend-kill-a` 标注早杀；ID 未动） |
| 2637-2775 | `caseSuspendCrossInstance`：证据前置、B 差量连续、载荷/台账 outcome、B⊂C 后缀 |
| 3261-3395 | `caseSuspendKillA`：早杀、增量 `takeover.json`、帧册不变量、kill 前 tool_start 观测（fix2 改名 `toolStartNotObservedBeforeKill`） |
| 4070-4080 | `summary.md` 证据清单文字（与实现对齐） |
| 4425-4700 | 5 条新自证（证据优先 / 允许噪声 / 缺 seq / 载荷 / 差量连续）+ 2 条加强（帧册结构 / 决策前回放载荷）+ 原 park 屏障自证 |

**补丁（repo 相对路径，`git apply` 可直接用；均验证过 `--check` 与"应用后与交付文件逐字节一致"）**：

| 补丁 | 基线 | 用途 |
|---|---|---|
| `<仓外证据>/cluster-probe-fix1-incremental.diff` | 主仓工作树当前的 v1 探针（`<主仓>/scripts/ci/cluster3-probe.mjs`） | **推荐**：在当前工作树上增量应用 |
| `<仓外证据>/cluster-probe-fix1-cumulative.diff` | `git show ae883fb:scripts/ci/cluster3-probe.mjs` | 干净 HEAD 上一次性应用 |

```bash
# 增量（在主仓根目录）
git apply <仓外证据>/cluster-probe-fix1-incremental.diff
# 或干净 HEAD
git apply <仓外证据>/cluster-probe-fix1-cumulative.diff
```

> 备选（用户允许"clean full-file delivery"）：直接取
> `<验证副本>/_verify-20261025-cluster-protocol/scripts/ci/cluster3-probe.mjs`（LF，UTF-8）。

---

## 3. RED → GREEN

### 3.1 RED（确定性复现"v1 的五处强度缺失"）

新自证是在修好之后写的，为给出可复现的 RED，用**变异法**：把 v2 的 5 处强度逐一退回 v1 形态，生成临时副本
`scripts/ci/_mutation-probe.mjs`（脚本 `<仓外证据>/_make-mutation.cjs`，跑完即删），再跑自证。

```bash
cd <验证副本>/_verify-20261025-cluster-protocol
node <仓外证据>/_make-mutation.cjs          # 5 处变异
node scripts/ci/_mutation-probe.mjs --self-check    # → EXIT=1，PASS 18 / FAIL 5
```

```
FAIL  允许的 pre-park 帧（心跳/文本/压缩/重试不得误判）：出现确认卡之前不该有的帧型（已收=start,context,message_start,delta,message_end,retry,suspended,confirm_request,tool_start）
FAIL  缺 seq 必须拒绝（不得静默过滤）：自检：缺 seq 的业务帧必须抛错
FAIL  载荷断言（tool_result/decision/delta/args 不得空壳或错值）：自检：tool_result.ok/outcome 错误必须红
FAIL  B 差量连续性（不重不漏）：自检：差量中间缺帧（5,7）必须红
FAIL  证据先落盘再断言（断言失败仍留证据）：自检：写证据必须先于断言：期望 ["write:4","assert"]，实得 ["assert"]
自证结果：PASS 18 / FAIL 5
```

日志：`<仓外证据>/cluster-probe-fix1-red.log`
→ 每条新自证都**只**在对应强度缺失时红，说明它们不是空转。

### 3.2 GREEN（真实交付文件）

```bash
node --check scripts/ci/cluster3-probe.mjs                    # OK
node scripts/ci/cluster3-probe.mjs --self-check               # EXIT=0 → PASS 23 / FAIL 0
bash scripts/ci/cluster3-run.sh --self-check                  # EXIT=0 → PASS 53 / FAIL 0
```

日志：`<仓外证据>/cluster-probe-fix1-green.log`、`<仓外证据>/cluster-probe-fix1-run-selfcheck.log`

新增/保留的自证项（全部 PASS）：

```
PASS  park 边界帧集契约（含 tool_start 与终帧禁止）（4 帧 + 6 个反例）
PASS  允许的 pre-park 帧（心跳/文本/压缩/重试不得误判）（心跳/文本/压缩/重试 + 卡已到契约 + 2 个反例）
PASS  全量回放帧册（seq 连续/恰一次/顺序/帧型白名单）（10 帧 + 5 个反例）
PASS  缺 seq 必须拒绝（不得静默过滤）（缺号 3 个反例 + 心跳例外）
PASS  载荷断言（tool_result/decision/delta/args 不得空壳或错值）（1 正例 + 8 个载荷反例）
PASS  B 差量连续性（不重不漏）（1 正例 + 3 个反例）
PASS  决策前重挂契约（回放完整且不收终帧）（1 正例 + 4 个反例）
PASS  证据先落盘再断言（断言失败仍留证据）（失败留存 + 正常返回）
PASS  park 收流屏障（真实 HTTP：收流/空回放正例 + 悬挂反例）
```

补丁验证：

```text
incremental: git apply --check OK；应用后与 v2 逐字节一致（LF 归一后）
cumulative : git apply --check OK；应用后与 v2 逐字节一致（LF 归一后）
```

---

## 4. 与复审建议的差异（有意为之）

1. **B2 没有新增"确定性命中窗口"的用例**：产品侧故障注入不可用（禁止改产品），客户端又无法确定性命中毫秒窗口；
   新增"伪命中"用例只会变成另一个抛硬币的用例。改为：**恢复早杀 + 断言不变量 + 记录走的是哪条路**，
   并在报告里显式登记"覆盖可达，但非必然命中"（复审 §4.1 的"无替代用例"因此变成了"窗口恢复可达 + 不变量加固"）。
2. **`archiveMaxSeq === lastSeq` 只在 park 基线使用**；早杀路径改为 `archiveMaxSeq >= lastSeq`（不得回退），
   因为被杀后归档**可能**比客户端多一帧（那正是"抢到了 tool_start"的证据，不能当缺陷）。
   `done.seq` 与归档末号的一致性改由"全量回放 seq 连续 + done 是末帧 + `done.seq === 末号`"承担 ——
   `runs/current` 在终态时会清掉 session→current 索引（`RunStore` 的 `clearSessionCurrent`），拿不到 `archiveMaxSeq`。
3. **未加 `X-Cluster-Node` 断言**（复审 M5）：直连实例不产生该头（只有 HAProxy 注入），加了会 false-fail。
4. **未动 I4/I5/M1-M9**：超出本批范围。

---

## 5. 交付物

| 路径 | 说明 |
|---|---|
| `<验证副本>/_verify-20261025-cluster-protocol/scripts/ci/cluster3-probe.mjs` | 交付文件（v2，LF，唯一改动；md5 见下） |
| `<仓外证据>/cluster-probe-fix1-incremental.diff` | 增量补丁（基于主仓工作树的 v1） |
| `<仓外证据>/cluster-probe-fix1-cumulative.diff` | 全量补丁（基于 HEAD `ae883fb`） |
| `<仓外证据>/cluster-probe-fix1-red.log` | 变异 RED（PASS 18 / FAIL 5） |
| `<仓外证据>/cluster-probe-fix1-green.log` | GREEN（探针自证 23/0） |
| `<仓外证据>/cluster-probe-fix1-run-selfcheck.log` | GREEN（`cluster3-run.sh --self-check` 53/0） |
| `<仓外证据>/_make-mutation.cjs` | RED 的变异生成器（可复现，非交付代码） |

```text
md5 = 92d6ae6917532082db2ca230aa2ec2ed   scripts/ci/cluster3-probe.mjs（5010 行）
增量补丁：+809 / -227（1344 行 diff）；全量补丁：+1146 / -114（1411 行 diff）
```

---

## 6. 未验证 / 边界

- **没有**真实三实例/CI 运行；所有"用例会 PASS"的判断都只到代码 + 自证层面。
- 载荷断言依据的是产品当前发射点（`SseChatEmitter#confirmRequest/toolStart/toolResult/confirmDecision/delta` 与
  `PendingToolCall#asMap`）；若产品改字段名，探针会以明确断言红（这是刻意的"协议面必须显式跟上"）。
- 早杀路径的 tool_start 观测字段（fix2 起 `toolStartNotObservedBeforeKill`）只是**观测标签**，不是断言，也不推断"谁发的 start"。
- 真实拓扑上仍需确认：`archiveMaxSeq`（park 基线）在 CI 上不会因 `XREVRANGE LIMIT 16` 的工程近似误红（复审 M9 已登记，本批未改）。

---

## 7. 给主会话的一句话

B1/B2 两个阻断项已按要求处理：**证据链真正前置**（含失败态）且**早杀窗口已恢复**、断言改为产品补发路径的
端到端不变量；I1-I3 的 false-pass/false-fail 面同时补齐。交付物是 1 个探针文件 + 两份可 `git apply` 的补丁，
`node --check` / 探针自证 23/23 / `cluster3-run.sh --self-check` 53/53 全绿；真实拓扑验收仍须 CI 复跑。
