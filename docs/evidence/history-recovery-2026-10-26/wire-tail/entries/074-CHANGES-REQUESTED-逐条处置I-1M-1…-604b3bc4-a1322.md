> **历史分析 / 过程证据 — 非现行基线，非验收结论**
>
> - 归档：2026-10-26 · `docs/evidence/history-recovery-2026-10-26/wire-tail/`
> - 来源：Kimi Code 会话 `<KIMI_SESSION>` / 子 agent `agent-1322` 的 wire 末条 assistant 文本（消息序号 m=182；只读流式解析，非原始会话转写）
> - 历史时点：2026-10-21 10:29 → 2026-10-21 11:11（wire 事件时间窗，UTC+8）；当时模型：`DeepSeek/deepseek-flash`
> - 原基线：正文未出现基线哈希，未经本批复验
> - 原始体量：11952 B / 8392 字符 / 165 行；行指纹覆盖率（本批复算）24.1%（最高单文件 14%：`docs/evidence/history-recovery-2026-10-26/mid-thread/entries/MT-170-设计-一、红测节-逐条-604b3bc4-agent-1322.md`）
> - 类别：**施工复核**
> - 与既有件关系：MT-170；报告口径覆盖率 0.14
> - 脱敏：路径 0 / 会话标识 1 / 身份 0 / 密钥 0 / 命名纪律 0
> - 正文为该条消息的完整文本，未删段、未改写结论；仅脱敏、占位符归一与行尾空白规整。当时结论不代表当前代码状态，不得作为当前缺陷或有效裁决引用。
复审意见 CHANGES-REQUESTED 已逐条收口完毕。以下是交接。

## 一、逐条处置

### I-1 摘除资源锁续租（已改）

- `ToolResourceLocks.java`：**删除 `drive(Lease)` 方法**及 `:147`（task 锁）、`:162`（job 锁）两个调用点；类注「释放口径」段改为：**本层不续租，`resource-ttl` 是临界区上界**，动作超界宁可让锁按 TTL 放开，也不续成"事实永锁"。
- `AiProperties.java:358` 一带：`（由使用方续租，不是一次性租约）` → **`（不续租，TTL 仅作临界区上界）`**，并把依赖"续租间隔"的论证改成"崩溃白等窗恒不超过一分钟"；顺手修正同文件 `Lock.enabled` 字段注里已失真的「不续租」表述（原句隐含"启用时续租"）。
- `LeaseResourceTtlTest.java`：**删除 `renewKeepsTheLockAlivePastTheOriginalDeadline`（原 :96–110）**。
  **删除理由（单列）**：该用例断言的 `LeaseService.renew` 本身仍在、也对，但它证明的是"资源锁靠持有者续租越过原截止时刻"——这正是复审 I-1 判定**不成立**的产品行为。保留它等于用绿测锁定一个被否决的语义；故属「纠正错误断言」，不是降格，且只删这一例，其余 4 例（默认值/键 TTL/到期换主/过期视图失效）原样。
- **残留核查**：`grep -rn "drive(" src/main/java/.../gate src/test/java/.../gate` → **0 命中**；`grep markDriving` 在 gate 包内只剩 `ConfirmGate`（批②的**续跑权**交棒，与资源锁无关）。
- 补强（断言只升不降）：`ToolResourceLockGateTest` S1 在"持锁中"新增 `renew.activeCount()==0`——若有人把续租加回来，这条立刻红。

### M-1 索引错位校验（已改）

`RunStore.idemFor`：索引命中且 `recordAt` 取回记录后，新增 `matchesToolCall(record, toolCallId)`（`record.get("toolCallId")` 与请求严格相等，缺失/无法识别一律 false）；不符 → **WARN「幂等索引错位…回落台账扫描」** → `ledgerFor` 扫描。类注第 4 条同步说明"重放必须谁的结果回给谁"。
新用例：`RunStoreIdempotencyIndexTest.anIndexPointingAtAnotherCallsRecordFallsBackToTheScan`。

### M-2 正文契约补强（已改）

`RunStoreIdempotencyIndexTest.ledgerAndIndexAreWrittenInOneScript` 新增对脚本原文的结构断言：

```java
assertThat(body).contains("'LLEN', KEYS[1]") …（原有 RPUSH/HSETNX/双 PEXPIRE 保留）
assertThat(body.indexOf("'LLEN'")).isLessThan(body.indexOf("'RPUSH'"));
```

### M-3 「只占 job 锁」（已补，含裁决原文的口径冲突处理）

新增 2 例：

- **`jobLockOnlyBlocksTheCallAndReturnsTheTaskLockItJustTook`**（主用例）：预置 `job:7:IMPORT` 由 `other:owner` 持有、**task 锁确认为空** → `start_import` 得 `blocked/RESOURCE_LOCKED`、`executed=false`；随后断言 **`ai:lock:task:7` 已消失**（本次创建、被拒即归还）、`job:7:IMPORT` 仍是 `other:owner`（不误删）、未占 claim、未记台账。
- **`anotherJobTypeDoesNotBlockTheSameTask`**（对照）：同样预置 `job:7:IMPORT`，调 `start_export` → **不阻断**、正常执行、终态 task 锁归还、`job:7:IMPORT` 不受影响。

> **口径冲突（需提交官知悉）**：裁决原文写「预置 `job(taskId,"IMPORT")` → **start_export** 被 blocked」。但 `start_export` 的作业级键是 `job:<taskId>:EXPORT`，第二把锁**刻意按作业类型区分**（设计稿 §2.3：导出与发布互不阻塞），所以该组合在设计上不可能被挡。我按**意图**落地为「同类型 job 锁被占 → 阻断且归还 task 锁」，并把裁决字面那一面（异类型不互斥）做成对照用例；两例同跑绿。

### M-4 认领首次即走三态口（已改）

`claimAndExecute` 现在**首次即 `tryClaimExecution`**，用 `claim.won()` 判成功、`claim.outcome()` 分 `LOST / STALE_FENCE`——正常路径只做一次 `HSETNX`、一次 fence `GET`（原实现失败时要做两遍）。
`RunStore.claimExecution` 布尔口**保留**；仅在 `tryClaimExecution` 返回 null（Mockito 轻装配底座，生产两个实现恒非 null）时回落布尔口并补齐 `ClaimResult`，因此 `ToolOutcomeExitTest` / `ConfirmGateWiringTest` 等锁定用例的桩与 `verify(claimExecution)` 零改动、仍绿。

### N-1 fenceEpoch 日志级别（核完，无需改）

`RunStore.fenceEpoch`（`RunStore.java:1012`）catch 内已是 **`log.warn`**，注释即 R7 的 fail-open 口径（"放宽必须 WARN"）。与 claim 路径同口径，**不动**。

### N-4 口径差异写明（已改）

`ToolUnknownReconciler` 类注新增「口径差异」小节：

- **本类 ⇒ UNKNOWN**：对账只接受"台账里有明确且非 unknown 的结论"这一种收敛证据，旧记录**没有结局字段**无法证明成败，猜成功正是本专项要消灭的静默假设；
- **`replayLedger` ⇒ OK**：重放面对"该 toolCallId 已执行完并留下台账"，缺 `outcome` 只说明是旧数据，按成功复用属历史兼容（否则旧台账续跑会全部变"对账中"）；
- 共同底线：台账里**写着 `unknown`** 时两条路径都保持 UNKNOWN。

### N-2 / N-3 / N-5 / N-7

**未改代码**，留提交官登记。

## 二、红证据（先红后绿，跑完即逐字节还原）

**R1 · M-1**（临时把 `idemFor` 的错位校验还原成 `if (record != null) return record;`）

```
Tests run: 1, Failures: 1 — anIndexPointingAtAnotherCallsRecordFallsBackToTheScan
Expecting map: {… "resultText"="结果 1", "toolCallId"="c-1" …} to contain ["toolCallId"="c-2"]
but was: "toolCallId"="c-1"   ← 把 c-1 的结果交给了 c-2
```
还原后单跑该类 **11/11 绿**。

**R2 · M-3**（临时删掉 job 分支被拒时的 `release(created);`）

```
Tests run: 1, Failures: 1 — jobLockOnlyBlocksTheCallAndReturnsTheTaskLockItJustTook
 but was: "2|instance-conformance:run-lock-a:call-lock-1"   ← ai:lock:task:7 残留（task 锁没归还）
```
还原后该类 **10/10 绿**，且 `grep "RED-BASELINE"` 两文件均为 0。

**R3 · I-1 的删除** 不产生新红测，理由见 I-1 条（被否决语义不保留绿测）。

## 三、绿数字

**后端**（权威：非 `-q` 全量，`mvn -o test`，exit 0）

```
[INFO] Tests run: 778, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

变更前后算术：`776（=741 基数 + ③b 35）` **−1**（I-1 删 `renewKeepsTheLockAlive…`）**+1**（M-1 新例）**+2**（M-3 两例）= **778** ✔

> **基数差异说明**：复核给的 766 与我方实测 776→778 差 12。差异无法从当前工作树复现（同一命令、同一目录、退出码 0）。可能原因：复核跑在我 M-1/M-3 落盘前、或用了过滤表达式（如 `-Dtest` 子集）。**请提交官以 `cd backend && mvn -o test` 的全量 `Tests run` 行为准**；我方两次全量（10:42 = 776，11:09 = 778）均可复现，且按本轮增删可逐条对账。

**前端**（本轮未改任何前端文件；`parts.ts` 10:48、spec 10:32 之后零改动）

- 首跑：`Test Files 1 failed | 25 passed (26)`、`Tests 13 failed | 500 passed (513)`（未逐条留存用例名；落在已登记的 `ai-panel-parts-render` 族）
- **重跑：`Test Files 26 passed (26)`、`Tests 513 passed (513)`**（按复核口径"重跑绿即过"）
- 隔离复跑 `tests/ai-panel-parts-render.spec.ts`：**187/187 绿**
- `vue-tsc --noEmit`：**exit 0**

## 四、改动清单（本轮）

| 文件 | 变更 |
|---|---|
| `backend/.../ai/gate/ToolResourceLocks.java` | I-1：删 `drive()` + 2 调用点；类注重写"不续租 / TTL 作上界" |
| `backend/.../ai/config/AiProperties.java` | I-1：`:358` 注释改「不续租，TTL 仅作临界区上界」；`Lock.enabled` 字段注去失真表述（注释级） |
| `backend/.../ai/run/RunStore.java` | M-1：`idemFor` 索引命中校验 `toolCallId`，错位 WARN + 回落扫描；类注第 4 条 |
| `backend/.../ai/gate/SpToolCallingManager.java` | M-4：`claimAndExecute` 首次即 `tryClaimExecution().won()`，仅 null 时回落布尔口 |
| `backend/.../ai/gate/ToolUnknownReconciler.java` | N-4：类注新增「旧台账无 outcome」两路径口径差异与理由 |
| `backend/.../ai/run/LeaseResourceTtlTest.java` | I-1：删 1 例（理由见上） |
| `backend/.../ai/run/RunStoreIdempotencyIndexTest.java` | M-1 新增 1 例；M-2 增 `'LLEN', KEYS[1]` + `LLEN < RPUSH` 次序断言 |
| `backend/.../ai/gate/ToolResourceLockGateTest.java` | M-3 新增 2 例；S1 增"持锁期间零续租任务"断言（I-1 回归锁） |

前端：**零改动**。`scripts/`、`data/`：**零改动**。

## 五、`git status --short`（backend/frontend）

```
 M backend/src/main/java/com/example/configmgr/ai/config/AiProperties.java
 M backend/src/main/java/com/example/configmgr/ai/gate/SpToolCallingManager.java
 M backend/src/main/java/com/example/configmgr/ai/run/LeaseService.java
 M backend/src/main/java/com/example/configmgr/ai/run/ResumeService.java
 M backend/src/main/java/com/example/configmgr/ai/run/RunStore.java
 M backend/src/main/java/com/example/configmgr/ai/tool/ToolOutcome.java
 M backend/src/test/java/com/example/configmgr/ai/conformance/InMemoryRunStore.java
 M backend/src/test/java/com/example/configmgr/ai/run/FakeLeaseRedis.java
 M backend/src/test/java/com/example/configmgr/ai/run/RunStoreClaimFenceTest.java
 M backend/src/test/java/com/example/configmgr/ai/tool/ToolResultEnvelopeTest.java
 M frontend/src/utils/parts.ts
 M frontend/tests/tool-outcome-envelope.spec.ts
?? backend/src/main/java/com/example/configmgr/ai/gate/ToolResourceLocks.java
?? backend/src/main/java/com/example/configmgr/ai/gate/ToolTargetResolver.java
?? backend/src/main/java/com/example/configmgr/ai/gate/ToolUnknownReconciler.java
?? backend/src/main/java/com/example/configmgr/ai/run/LeaseKeys.java
?? backend/src/test/java/com/example/configmgr/ai/gate/ToolIdempotencyTest.java
?? backend/src/test/java/com/example/configmgr/ai/gate/ToolResourceLockGateTest.java
?? backend/src/test/java/com/example/configmgr/ai/gate/ToolTargetResolverGateTest.java
?? backend/src/test/java/com/example/configmgr/ai/gate/ToolTargetResolverTest.java
?? backend/src/test/java/com/example/configmgr/ai/gate/ToolUnknownReconcilerTest.java
?? backend/src/test/java/com/example/configmgr/ai/run/LeaseKeysTest.java
?? backend/src/test/java/com/example/configmgr/ai/run/LeaseResourceTtlTest.java
?? backend/src/test/java/com/example/configmgr/ai/run/LeaseServiceDistinctOwnerTest.java
?? backend/src/test/java/com/example/configmgr/ai/run/ResumeDescribeLockKeysTest.java
?? backend/src/test/java/com/example/configmgr/ai/run/RunStoreIdempotencyIndexTest.java
?? backend/src/test/java/com/example/configmgr/ai/tool/ToolOutcomeResourceCodesTest.java
```

（未 commit；`scripts/`、`docs/` 下的变动仍属并发写者，非本棒。）

## 六、自证三值

`grep "profile.bind" .../agents/agent-1322/wire.jsonl` → **20 条命中**（非 head -1）：

```json
{"type":"profile.bind","agentId":"agent-1322","modelAlias":"DeepSeek/deepseek-flash","profileName":"coder","thinkingEffort":"high", …}
```

- **agentId = `agent-1322`**
- **modelAlias = `DeepSeek/deepseek-flash`**
- **profileName = `coder`**

路径：`<KIMI_SESSIONS>`

## 七、仍需复审/提交官裁决的两点

1. **M-3 字面组合不成立**：`job:IMPORT` + `start_export` 在设计上不会互斥（第二把锁带作业类型）。我按意图实现「同类型 job 锁被占 → 阻断 + task 锁归还」，并补异类型不互斥的对照例；若裁决要求"任意 job 锁都挡任意作业"，则需改锁键设计（那是设计变更，非本单据范围）。
2. **测试基数口径**：复核 766 vs 我方 776→778，请以全量命令实测为准（两次全量结果均已列明、可逐条对账）。
