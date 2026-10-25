# Task 8 · R1 三反例收口（取消范围最后小补丁）— 施工与实测报告（2026-10-25）

**对象**：scoped 复核（APPROVED）列的 3 条反例，属同一条"取消后禁止不可逆执行"范围，不另开专题。
**基线**：主仓 `<主仓>` **当前工作区**（HEAD `2eb2539` + 已合入的 `task-8-preexec-cancel.patch`、`task-8-abort-settlement.patch`、`task-8-settlement-final.patch`、idem 注释等全部近期补丁，242 项改动）。施工在隔离副本 `<隔离副本>/_m2guard-163`（基线快照 commit `5a61a7a`，`core.autocrlf=true` 与主仓同配置；被改文件与主仓工作区逐字节一致）。
**主仓零改动、零提交、零推送**；主仓仅新增本报告与 `task-8-cancel-guard-final.patch`。
**Maven**：`<本机工具链>/JetBrains/IntelliJ IDEA/plugins/maven-plugin/lib/maven3/bin/mvn.cmd`（`-o -B -s maven-settings.xml`；未 clean、未全量、未起服务）。
**改动面**：`RunStore`、`SpToolCallingManager`、`CancelLedgerConformanceTest`、`InMemoryRunStore`（测试缝）。未动 `ResumeService`/前端/`docs`/配置/其它设施；未加新机制、未引入重试。

---

## 0. 逐项结论

| # | 反例 | 判定 | 处置 |
|---|---|---|---|
| **1** | `settleIfCancelledBeforeClaim` 对 `cancelState=UNKNOWN` 仍放行 claim | **真缺陷（已闭合）** | 与 `checkCancelled` 同口径 fail-closed：`UNKNOWN`（含读异常）⇒ 抛既有 `StreamViolationException(STATE_UNAVAILABLE)`，不认领不执行；**条目状态/终帧一概不动** |
| **2** | 条目已 `CANCELLED` 且无执行证据时 `cancellableBeforeExecution=false` ⇒ 返回 null ⇒ 继续 `tryClaimExecution` | **真缺陷（已闭合）** | 有执行证据 ⇒ 绝不伪装（原样返回 null 走既有 claim 失败口径）；**无证据 + 已 CANCELLED** ⇒ 返回既有取消文本、不认领不执行 |
| **3** | 缺"`ai:claim` 哈希已写、pending 零证据"的真实用例 | **不是代码缺陷（反证 + 补测）** | 现有判据已读认领表；RED 阶段该用例即绿。本次补一条显式用例把跨实例窗口钉住，并加测试缝 `InMemoryRunStore#seedClaimOnly` |

实测：**RED 15 例 / 2 失败（恰为 #1、#2；#3 在基线即绿 = 反证）**；**GREEN 4 类 49 例全绿**。

---

## 1. 第 1 项：UNKNOWN 必须 fail-closed（RED → GREEN）

### 真实行为（修复前）

`settleIfCancelledBeforeClaim` 只把 `CANCELLED` 当命中，`UNKNOWN` 落到 "state != CANCELLED ⇒ return null" ⇒ 继续 `tryClaimExecution` 并执行不可逆工具。而相邻的 `checkCancelled`（`SpToolCallingManager.java:528/620` 使用）对 `UNKNOWN` 是明确 fail-closed：抛 `STATE_UNAVAILABLE`（"取消状态未知（Redis 读失败），工具执行在事件边界终止"）。两者对同一事实给出相反处置。

### RED

`CancelLedgerConformanceTest#unknownCancelStateBeforeTheClaimFailsClosedLikeCheckCancelled`：

```
expected/实际：Expecting code to raise a throwable.（没有抛；工具会被执行）
```

### 修复（`SpToolCallingManager`）

```java
catch (RuntimeException ex) { … state = CancellationRegistry.CancelState.UNKNOWN; }   // 读异常也按 UNKNOWN
if (state == CancellationRegistry.CancelState.UNKNOWN) {
    throw new StreamViolationException(StreamViolationException.STATE_UNAVAILABLE,
            "取消状态未知（Redis 读失败），工具执行在认领前终止（runId=" + runId + "）");
}
```

- **不认领、不执行**；**不改条目状态**（不写 CANCELLED）；**不发终帧**（基础设施不可知不是"已取消"）。
- 沿用既有异常类型与 `STATE_UNAVAILABLE` 语义；下游（`ResilientChatService` 的失败路径）按既有 `AI_REDIS_UNAVAILABLE` 收口，与 `:528` 的同名检查一致。

### GREEN 断言

工具 0 次调用；条目仍 `APPROVED`、`executed=false`；该 toolCallId **0 条**结算行。

---

## 2. 第 2 项：已 CANCELLED 且无证据时不得掉回认领（RED → GREEN）

### 真实行为（修复前）

`cancellableBeforeExecution` 只认 `PENDING`/`APPROVED` ⇒ 对已 `CANCELLED` 的条目返回 false ⇒ `settleIfCancelledBeforeClaim` 返回 null ⇒ **继续 `tryClaimExecution`**。调用方手里常是"读到之后才被取消"的旧副本（`APPROVED`），此时 `resolvePending` 的 `shouldExecute` 仍为真 ⇒ 一条**已取消**的调用被真正执行。

### RED

`CancelLedgerConformanceTest#alreadyCancelledEntryWithStaleApprovedCopyIsNotClaimedOrExecuted`：

```
[已取消的调用不得再被认领/执行] expected: 0 but was: 1     ← 工具被执行了一次
```

（场景：store 里条目 `CANCELLED` + 取消文本 + 零执行证据；传给 `resolvePending` 的仍是 `APPROVED` 旧副本；`cancelState=CANCELLED`。）

### 修复

- `RunStore` 抽出共用判据 `hasExecutionEvidence(runId, pending)`（条目五字段 + 认领表；**证明不了就当有**：读失败/null 都返回 true），`cancellableBeforeExecution` 改为 `PENDING || (APPROVED && !hasExecutionEvidence)`（原语义不变）。
- `SpToolCallingManager#settleIfCancelledBeforeClaim` 分三支：
  1. `hasExecutionEvidence` ⇒ 返回 null（**绝不伪装未执行**，交给既有 claim 失败/对账口径）；
  2. 无证据且**已 CANCELLED** ⇒ 返回**既有取消文本**（`resultText`，缺失才用统一文案），**不认领、不执行**；
  3. 无证据且 `PENDING`/`APPROVED` ⇒ 收敛为 `CANCELLED` 并返回取消文本（上一轮既有行为）。
- 统一文案提为 `SpToolCallingManager.CANCELLED_BEFORE_CLAIM_TEXT`（与 `cancelPendings`/`ConfirmGate#cancel` 逐字同源），未新增措辞。

### GREEN 断言

工具 0 次调用；返回文本 == 存储里的既有取消文本；条目仍 `CANCELLED`、`executed=false`；`claimedBy(...) == null`（**没有占认领位**）；0 条结算行。

---

## 3. 第 3 项：认领表已写、pending 零证据（反证 + 补测）

### 结论：不是代码缺陷

`cancellableBeforeExecution` 自上一轮起就在"条目五字段"之外读认领表（`claimedBy`），所以"`HSETNX` 已落、`ai:pending` 未回写"的条目**不会**被 `cancelPendings` 转成 `CANCELLED`。

### 反证（RED 阶段即绿）

新用例 `CancelLedgerConformanceTest#claimHashWrittenBeforePendingIsNeverRelabelledAsCancelled`（在**未修任何生产代码**的 RED 副本里运行）通过：

- 构造：`APPROVED` 条目（`executed=false`、`claimedAt/claimedEpoch/executedBy` 全空、`resultText` 有值）+ `store.seedClaimOnly(runId, toolCallId, "1|instance-b")`（只写认领表，**不回写条目**）；
- 断言：`cancelParked` 后条目仍 `APPROVED`、`resultText` 原样、该 toolCallId **0 条**结算行。

### 为什么要补测试缝

`InMemoryRunStore` 原先只能经 `claimExecution` 同时写"认领表 + 条目"，无法单独构造这个中间窗口。新增 `seedClaimOnly(...)`（测试文件内，带注释说明它模拟的正是跨实例 `HSETNX` 已落、pending 未回写的窗口）。生产代码为支撑它只做了 §2 的判据抽取（语义不变）。

---

## 4. GREEN（4 类 49 例）

```
mvn -o -B -s maven-settings.xml -Dtest=CancelLedgerConformanceTest,ParkSettlementAuditTest,\
ToolIdempotencyTest,RunStoreReadFailureFailClosedTest test
→ Tests run: 49, Failures: 0, Errors: 0 — BUILD SUCCESS
```

| 类 | 例数 | 覆盖 |
|---|---|---|
| `CancelLedgerConformanceTest` | 15 | #1/#2/#3 三条新用例 + 既有 12 例（A 收敛、认领前窗口、已认领/已完成不伪装、abort 仲裁、BACKEND、park-off、重复取消） |
| `ParkSettlementAuditTest` | 14 | 批准执行、认领窗口以 `ALREADY_CANCELLED` 收尾、拒绝、前端超时、多卡到期（取消面不回归） |
| `ToolIdempotencyTest` | 5 | 认领/幂等契约（取消复核在 claim 之前，不得改变"恰好一次"） |
| `RunStoreReadFailureFailClosedTest` | 15 | `cancelPendings` 的 lenient/整表读失败/guard 读失败口径；认领表读失败 ⇒ 不收敛（保守方向） |

日志：`<隔离副本>/_m2guard-163/red1.log`（RED）、`<隔离副本>/_m2guard-163/green-all.log`（GREEN）。

---

## 5. 现实边界（如实登记）

1. **已开始执行的外部工具无法被"绝对停止"**：本轮把"取消后不得**开始**执行不可逆工具"的窗口一路收窄到 `HSETNX` 之前（执行前检查 → 认领前复核），但取消仍可能落在"复核通过 → `HSETNX` 成功"之间，或工具已被认领并在执行中。此时工具继续执行、以真实结局结算一条 end；取消**不会**把它伪标成 cancelled（#2 的证据分支），也不会中断已发出的副作用的对账（既有 `ALREADY_CLAIMED`/台账口径）。
2. **UNKNOWN 在续跑路径的传播**：`resolvePending` 抛 `STATE_UNAVAILABLE` 会穿出 `ResumeService` 的重建循环（该方法无兜底 catch），快照仍是 `SUSPENDED`（重建完成前才置 `RUNNING`）⇒ 不执行、不落终态、可由后续续跑重试（**无无界重试**，沿用既有派发口径）。正常情况下 `ResumeService` 的入口与逐条 `cancelStateOutcome` 已先把 `UNKNOWN` 转成 `CANCEL_STATE_UNKNOWN`，故只有"读状态先成功后失败"的抖动会走到这里。
3. **inline 路径的 abort 行**：`claimAndExecute` 内的 `STATE_UNAVAILABLE` 会被批循环的既有 catch 记一条 abort **日志行**（`outcome=failed`），随后按既有失败路径收口；这不是"终帧"，条目状态未被改写。
4. 未跑全量/未 clean/未起服务；未跑 Spring 上下文启动。主控随后做最终全量。

---

## 6. 交付物与命令

| 文件 | 内容 |
|---|---|
| `task-8-cancel-guard-final.patch` | 4 文件 / +176 / −24（`SpToolCallingManager` +31/−8、`RunStore` +32/−16、`CancelLedgerConformanceTest` +104、`InMemoryRunStore` +9） |
| `task-8-cancel-guard-final-report.md` | 本报告 |

| 命令（隔离副本） | 结果 |
|---|---|
| RED（仅新用例，生产/替身未改）`-Dtest=CancelLedgerConformanceTest` | `Tests run: 15, Failures: 2`（#1 无抛出；#2 工具执行 1 次；#3 绿=反证） |
| GREEN（4 类） | `Tests run: 49, Failures: 0` — BUILD SUCCESS |
| `git apply --check --verbose task-8-cancel-guard-final.patch`（主仓当前工作区，只读） | 4 文件全部通过 |

**提交建议**：`fix(ai): 取消复核 fail-closed（UNKNOWN）并阻止已取消条目被认领执行（R1 反例收口）`
