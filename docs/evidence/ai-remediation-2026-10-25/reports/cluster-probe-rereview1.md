> 来源=<仓外证据>/cluster-probe-rereview1.md（2026-10-25 归档）· 仓外正式过程报告，属历史证据，不代替现行基线/决策文档（docs/02、docs/03、docs/adr/）
# 集群探针 fix1 · 复审（B1/B2 + I1-I3）

- 复审对象：`cluster-probe-fix1-report.md`、`cluster-probe-fix1-incremental.diff`
- 交付探针：`_verify-20261025-cluster-protocol/scripts/ci/cluster3-probe.mjs`（md5 `92d6ae6917532082db2ca230aa2ec2ed`，5010 行）
- 主仓工作树探针与交付文件**逐字节一致**（同 md5）
- 增量补丁以 v1 为基线可 `patch -p1` 应用；应用后与 v2 **逐字节一致**（本机实测）
- 只读复审；未起真实拓扑/CI/Docker，未使用子代理；自证 23/23 与 run 自检 53/53 为**探针自证**，不是产品行为证明

---

## 0. 判定速览

| 原复审项 | 状态 | 结论 |
|---|---|---|
| **B1** 证据仍在断言之后 | **ADDRESSED（帧类）** | `A-parked`、`A-park-transition`、`B/C-predecision`、`B/C-reattach`、`B-takeover`、`A/C-full`、`confirm.json` 均已先落盘/`finally` 兜底；`fullReplay` 4 次重试都失败也留最后一份。**但 `run-detail.json`、`takeover.json` 最终 ledger、`currentRun`/`still` 状态仍在断言之后**（新 Important N1）。 |
| **B2** 早杀窗口覆盖被移除 | **ADDRESSED（覆盖），但依赖未交付的产品补丁（Critical C1）** | `suspend-kill-a` 恢复 `waitForPark:false`：拿到 `confirm_request` 就返回并 SIGKILL，不再等 park 收流；park 屏障只保留在非 kill 编排。**但断言 `tool_start` 恰一次依赖 `SseChatEmitter#ensureToolStart`，该产品代码只存在于主仓工作树的未提交修改，HEAD/隔离副本没有**；在隔离副本上早杀命中窗口时仍会红。 |
| **I1** 只验 type+seq，内容可 false-pass | **NOT FULLY ADDRESSED** | `assertParkedPayloads`/`assertCompletedPayloads`/`tool_result`/`decision`/`delta` 已加；**但决策前 C 回放的 `payloadProjection` 不含 `toolCalls`、`runId`、`context` 字段；B/C 决策后连接只验 seq，不验 type/载荷；B 差量同样只验 seq**。仍有明确 false-pass。 |
| **I2** 缺 seq / B 差量 | **PARTIALLY ADDRESSED** | `seqsOf` 对非心跳缺 seq 直接抛错；B 差量连续 + 与 C/A 后缀一致，已落地。**`assertFrameLedger` 的 `done.seq === 归档末号` 实际比的是“全量回放 seq 列末项”，不是服务端 `archiveMaxSeq`**；回放若在 done 后截断，仍可 false-pass（新 Important N2）。 |
| **I3** park 帧集过窄 | **ADDRESSED** | `assertCardReachedFrameSet` 允许 `context/message_start/delta/message_end/retry/heartbeat`，心跳不占号；`assertParkedFrameSet` 在其上只加 `tool_start` 恰一次。自证有混排正例与两个反例。 |
| 场景 ID / A→B→C / 无粘滞 | **ADDRESSED** | 16 个 ID 与顺序不变（`suspend-kill-a` 仅标题改“早杀”）；A 挂起、B/C 重挂、C 决策、A/C 全量回放、B 接管/C 全量均保持；`proxy-no-sticky`/`same-tcp-across-abc` 未改，直连 URL 明确指定实例。 |

**合并判定：Reject as-is（见 §1 C1、§2 I1/N1）。** 若产品 `ensureToolStart` 是另一个**已合并**的前置补丁，且 I1/证据缺口补齐，才可转为可合并。

---

## 1. Critical

### C1. B2 的“帧册不变量”依赖未交付的产品 `ensureToolStart`；在 HEAD/隔离副本上早杀仍会红

- 修复报告 §1.2 明确依赖产品侧 `SseChatEmitter#ensureToolStart`：
  `_p2-review/cluster-probe-fix1-report.md:49`
- 但报告同时声明隔离副本是 `git archive HEAD`、只改探针：
  `_p2-review/cluster-probe-fix1-report.md:5`
- 实测：
  - `_verify-20261025-cluster-protocol/backend/src/main/java/com/example/configmgr/ai/run/SseChatEmitter.java`
    **没有** `ensureToolStart`（`grep` 无结果）。
  - `git show HEAD:.../SseChatEmitter.java` 同样没有。
  - 只有主仓工作树有未提交修改：
    `<主仓>/backend/src/main/java/com/example/configmgr/ai/run/SseChatEmitter.java:1139`
    （`git status` = ` M`，未提交）。
- 探针侧在 `:33`、`:1998`、`:3252` 把 `ensureToolStart` 写成产品既定行为；`caseSuspendKillA` 的 `assertFrameLedger` 要求 `tool_start` 恰一次（`:3347-3362`）。
- 后果：在**报告声明的交付基线（HEAD + 探针）**上，若 A 在窗口内没落档 `tool_start`，没有补发路径，`assertFrameLedger` 仍会以“`tool_start` 帧数 0”红——这正是 run 40174291244 的原始失败。报告 §0 的“B2 已修”只在“先合入未提交产品补丁”的前提下成立，报告没有把该补丁列为前置交付物。
- 这不是要求探针把产品缺陷改绿；问题是**B2 的验收口径与交付基线不一致**：要么把 `ensureToolStart` 纳入本批/明确前置并让隔离副本包含它，要么明确“该用例预期在 HEAD 上红、用于暴露产品缺陷”，不能同时声称 B2 已修且交付物只有探针。

**证据**：HEAD/隔离副本无 `ensureToolStart`；主仓工作树有但未提交；报告 line 5 与 line 49 互相矛盾。

---

## 2. Important

### I1. 决策前 C 回放仍有 payload false-pass；B/C 决策后连接只验 seq

**(a) `assertPreDecisionReplay` 的投影键不足**

- `PAYLOAD_PROJECTION_KEYS` 只有：
  `type/seq/toolCallId/name/kind/args/summary/ok/outcome/code/decision/approved/status/text/messageId`
  `_verify.../scripts/ci/cluster3-probe.mjs:517-533`
- 缺少 `toolCalls`（`suspended` 的待决条目列表）、`runId`（`start` 的轮标识）、`context` 的载荷、`preflight`/`irreversible`/`callback` 等卡片字段。
- `assertPreDecisionReplay` 只比较该投影：
  `.../cluster3-probe.mjs:605-613`
- 后果：C 决策前全量回放若把 `suspended.toolCalls` 清空、把 `start.runId` 换成别的 run、或把 `context` 载荷改坏，投影仍相等，用例通过。`assertParkedPayloads` 只作用于 A 的 park 帧和 A/C 全量，不作用于这条 C 预决策回放：
  - A park：`:546-567`
  - C 预决策调用：`:2662-2673`
- 报告 §1.3 说的“决策前回放逐帧载荷投影”因此**不完整**。

**(b) B/C 决策后连接只验 seq**

- B 重挂：`.../cluster3-probe.mjs:2700-2712`，只做 `assertDeltaContiguous`（seq）+ 末号 = done.seq。
- C 重挂：`:2714-2726`，只做 `seqsOf` + `checkSeqContiguous`。
- 载荷/帧型只由之后的 **A 全量** `assertFrameLedger`（`:2728-2740`）覆盖；如果 B 或 C 这条连接丢/改了一个帧的载荷但 seq 仍连续（例如 `tool_result.ok=false`、`delta.text` 为空、`toolCalls` 缺失），B/C 断言会过；A 全量是另一条连接，不能证明 B/C 的交付内容。
- 报告 §1.3 的“post-decision B/C content”没有落到 B/C 连接本身。

**(c) B 差量没有帧型/载荷校验**

- `assertDeltaContiguous` 只验 seq：`:618-622`；B 差量在 `:2707` 调用。
- B 差量里出现 `brand_new` 类型但 seq 正确、或 `tool_result` 载荷错误，`assertDeltaContiguous` 仍过；只有 C/A 全量能发现归档问题，不能发现 B 这条连接的交付问题。

**结论**：I1 只覆盖了“A 全量 + A park + C 预决策部分字段”，**没有满足“payload ... cannot silently false-pass”**。

---

### N2. `done.seq === 归档末号` 是“全量回放末项”，不是服务端 `archiveMaxSeq`

- `assertFrameLedger` 里：
  ```js
  const lastSeq = seqs[seqs.length - 1]
  checkEq(findOf(business, 'done').seq, lastSeq, 'done.seq 必须等于归档末号')
  ```
  `_verify.../scripts/ci/cluster3-probe.mjs:680-683`
- `lastSeq` 来自本次全量回放的 `seqs`，不是 `/runs/current` 的 `archiveMaxSeq`，也不是 Redis 事件日志实际末号。报告 §1.4 把“末号”定义为“全量回放 seq 列末项”，但断言文案写“归档末号”，语义不等价。
- 后果：若全量回放在 `done` 之后被窗口截断（迟到的业务帧没进回放），`done` 仍是回放末帧，断言通过，实际归档有更高号——false-pass。反过来，若回放包含 done 之后的业务帧，`business[last] !== 'done'` 会红，这部分是好的。
- 早期 kill 路径的 `archiveMaxSeq >= lastSeq`（`:3302-3315`）是有意的下界；park 路径 `archiveMaxSeq === lastSeq` 仍正确。**但终态帧册的“done = 实际归档末号”没有被真正验证**。
- 这是 I2 的残留；不是要求重新引入无法读取的 `/runs/current`（终态会清 session 索引），而是应把断言命名为“全量回放末号”，或从 `FrameStore`/事件日志读取实际末号后再比。

---

### N1. 状态/ledger 证据仍在断言之后（B1 的帧证据已修，但“证据”不止帧）

- `caseSuspendCrossInstance`：
  - `still = await currentRun('b', ...)` 后直接做 `runId/status/awaitingExternal/pending/archiveMaxSeq` 断言，**没有先保存 response**：`:2675-2684`
  - `run-detail.json` 在 `DONE/ledgerCounts/ledger/outcome/resultText/jobs/history` 全部断言之后才写：`:2742-2766`
- `caseSuspendKillA`：
  - kill 后 `currentRun` 的 response 在 `found/runId/status/awaitingExternal/pendingEntries` 五项断言期间没有先保存；`archiveMaxSeqAfterKill` 确实在 `:3308-3309` 先 assign/save、再在 `:3312` 断言（这一项做对了），但前五项失败时没有 current 证据：`:3302-3312`
  - `detail/ledger` 断言在 `:3366-3372`，最终 `takeover.json`（含 ledger/jobs/history）在 `:3382-3390`；若 `ledger[0].outcome` 或 `resultText` 断言失败，artifact 里没有 ledger 证据，只有 frame/takeover 的旧版本。
- 报告 §1.1 的 B1 表只列 frame 类和 `confirm/takeover`；对 `run-detail`/current/ledger 没有“先落盘”保证。**新加的 ledger `outcome`/`resultText` 断言恰好是最可能失败的一类，却没有对应证据**。
- 这不推翻“帧证据先落盘”的完成，但 B1 按“所有证据”解读仍未完全闭合。

---

### N3. `toolStartBackfilled` 不是“谁发的 start”的可靠观测；报告/注释的推断不成立

- 计算：
  ```js
  const toolStartBeforeKill = countOf(run.frames, 'tool_start', run.toolCallId) === 1
  ...
  toolStartBackfilled: !toolStartBeforeKill
  ```
  `_verify.../scripts/ci/cluster3-probe.mjs:3272-3273`、`:3354-3359`
- `toolStartBeforeKill` 只表示**客户端在 `createSuspendedRun` 处理到的帧里看到了 tool_start**；它为 false 不代表 A 没落档。A 完全可能在客户端关闭/未处理下一 chunk 之后、SIGKILL 之前把 `tool_start` 写进归档；此时 `ensureToolStart` 会 no-op，但 `toolStartBackfilled` 仍为 true。
- 报告 §1.2 line 50 说“记录走的是哪条路”，注释 `:3357` 说“卡前没看到 ⇒ 这一条只能来自接管方补发”，caseLog `:3391` 说“false ⇒ 由接管方补发”——**这三处推断都不成立**。
- 断言本身不依赖该标签（`assertFrameLedger` 只要求恰一条），所以不是 pass/fail 阻断；但 artifact 会误报“补发路径”，验收/排障会被误导。应改为“客户端是否在 kill 前看到”而不是“是否由接管方补发”，或结合 `archiveMaxSeqAfterKill` 判断。

---

## 3. 已确认修好的部分（避免误伤）

- **帧证据先落盘**：
  - `settleParkBoundary` 的 `finally` 写 `A-parked`：`:1977-1997`
  - `createSuspendedRun` 卡到即写 + `finally` 兜底：`:2001-2070`
  - `preB/preC` `waitClosed` 后立即写 + `finally`：`:2650-2672`
  - `B/C-reattach` `waitTerminal` 后立即写 + `finally`：`:2700-2725`
  - `B-takeover`：`:3330-3344`
  - `A/C-full` `fullReplay` 的 `finally`（含 4 次重试失败）：`:1906-1940`
  - `confirm.json` `evidenceFirst`：`:2688-2697`、`:3318-3330`
  - `takeover.json` 增量 5 次：`:3300/3309/3326/3341/3360/3390`
- **seq 严格性**：`seqsOf` 非心跳缺整数 seq 直接抛错（`:413-427`）；B 差量连续（`:618-622`）；B 差量是 C/A 全量的连续后缀（`:2736-2740`、`:3349-3353`）。
- **I3 噪声**：`PRE_PARK_NOISE_TYPES`（`:461-463`）、`assertCardReachedFrameSet`（`:477-504`）、`assertParkedFrameSet`（`:506-513`）；自证有 `context+heartbeat+message_start+delta+message_end+retry+heartbeat` 混排正例和两个反例（`:4493-4528`）。
- **I1 的 A 全量载荷**：`assertParkedPayloads`（`:546-567`）、`assertCompletedPayloads`（`:573-595`）覆盖了 name/args/summary/tool_start/args/ok/outcome/result/decision/delta。
- **场景面**：16 个 ID/顺序未变；A→B→C 路由未变；`proxy-no-sticky`/`same-tcp` 未改；直连不使用 HAProxy 的 `X-Cluster-Node`，不加该断言是正确的（报告 §1.6 的解释成立）。

---

## 4. 自证/补丁验证（本机只读复跑）

```text
node --check scripts/ci/cluster3-probe.mjs          → OK
node scripts/ci/cluster3-probe.mjs --self-check     → PASS 23 / FAIL 0
bash scripts/ci/cluster3-run.sh --self-check        → PASS 53 / FAIL 0
```

- `cluster-probe-fix1-incremental.diff` 以 `/tmp` 中的 v1 为基线 `patch -p1` 成功；应用后 md5 = `92d6ae...`，与交付文件**逐字节一致**。
- 未执行真实拓扑/CI/Docker/HAProxy/Redis；23/23 只证明断言自证，不证明 `ensureToolStart`、真实 park 时序或 B/C 连接载荷。

---

## 5. 合并建议

**Reject as-is。** 最小合并条件：

1. **C1**：把 `SseChatEmitter#ensureToolStart`（及 `SpToolCallingManager` 的结算前补发）作为**已合并的前置产品补丁**，或让隔离副本包含它；否则 `suspend-kill-a` 在交付基线仍会红，报告不能写“B2 已修”。
2. **I1**：把 `toolCalls`/`runId`/`context` 等加入 `payloadProjection`，并对 C 预决策回放调用 `assertParkedPayloads`；对 B/C 决策后连接加最小载荷/帧型断言（至少 `tool_result.ok/outcome/result`、`delta.text`、`confirm_decision`）；B 差量至少做白名单/关键载荷。
3. **N1**：`still`/`currentRun`/`run-detail`/最终 `takeover` 的 ledger 改为 `evidenceFirst` 或增量先写；否则 ledger 断言失败无证据。
4. **N2/N3**：把 `done.seq` 断言改名/改为实际末号；`toolStartBackfilled` 改为“客户端是否在 kill 前看到”，不要推断“谁补发”。

若用户只要求“探针恢复早杀覆盖、接受产品 `ensureToolStart` 另行合并”，则 B2 的**覆盖**可以算完成；但报告必须把该产品补丁列为前置，不能把当前隔离副本说成可绿。
