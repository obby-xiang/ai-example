# Task 8 · R1（已批准但未执行即被停止）— 施工与实测报告（2026-10-25）

**对象**：`final-integration-final-review.md` 登记的 **R1**（控制者裁决：属已批准 Task 8「取消后禁止不可逆执行」范围，必须闭合，不做新功能）。
**基线**：主仓 `<主仓>` **当前工作区**（HEAD `2eb2539` + 已合入的 `task-8-settlement-final.patch`、`task-8-abort-settlement.patch`、`task-8-idempotency-contract.patch` 等全部候选，234 项改动）。施工在隔离副本 `<隔离副本>/_m2pre-163`（基线快照 commit `80dfa6d`，`core.autocrlf=true` 与主仓同配置；被改文件与主仓工作区逐字节一致）。
**主仓零改动、零提交、零推送**；主仓仅新增本报告与 `task-8-preexec-cancel.patch`。
**Maven**：`<本机工具链>/JetBrains/IntelliJ IDEA/plugins/maven-plugin/lib/maven3/bin/mvn.cmd`（`-o -B -s maven-settings.xml`；未 clean、未全量、未起服务）。

---

## 0. 结论

| 面 | 判定 | 一句话 |
|---|---|---|
| **A 已批准、未认领/未执行即停止** | **闭合** | 条目可靠收敛为 `CANCELLED`（`outcome=cancelled/code=RUN_CANCELLED/executed=false`），工具不执行，该 toolCallId **恰 1 条** end；RED 实测为"停在 APPROVED + 0 条 end" |
| **A′ 认领前窗口** | **闭合** | `tryClaimExecution` 之前补取消复核：命中即放弃认领、不执行；RED 实测工具**被执行了 1 次**（`expected: 0 but was: 1`） |
| **A″ 续跑终止** | **闭合** | 认领前命中取消后，`ResumeService` 立即走取消终态（`ALREADY_CANCELLED`）；RED 实测为 `ACCEPTED`（会带着一条 CANCELLED 工具结果继续问模型） |
| **B 已认领/执行中/已完成** | **不伪装** | 任何 `executed/claimedAtMs/claimedEpoch/executedBy/executedAtMs` 或认领表命中 ⇒ 状态、结果、结算行一律不动（新增反例钉住） |
| **C 普通批准 / park-off** | **不回归** | `ParkSettlementAuditTest` 13 例（approve 成功/失败各恰 1 条、park-off 内联、重复拒绝、前端超时…）全绿 |
| **仲裁** | **仍是 `ai:observed`** | 认领前复核**不发**结算行：发点仍只有取消侧（`settleCancelledToolCalls`）与内联 abort，二者经 `emitOnce` 互斥 |

实测：**RED 26 例 / 3 失败（恰为三条缺口：APPROVED 未收敛、取消后仍执行、续跑以 ACCEPTED 收尾）**；**GREEN 4 类 55 例全绿**。

---

## 1. 真实调用链（先读后改）

1. **批准只写决策，不执行**：`ConfirmGate#doSubmitDecision` 落 `APPROVED` + `executed=false`（`ConfirmGate.java:1282-1307`），执行交给续跑。
2. **续跑的执行前检查**：`ResumeService` 在重建历史里逐条调用 `cancelStateOutcome`（`ResumeService.java:845`）→ 命中取消则 `chatService.cancelParked` 并以 `ALREADY_CANCELLED` 返回（`:1156-1171`）。**这挡住了绝大多数"批准后立即停止"**。
3. **但两步之间还有缺口**：`cancelStateOutcome` 与真正的 `tryClaimExecution`（`RunStore.java:1946`）之间还隔着台账重放、目标校验、资源锁（`SpToolCallingManager#executeOnce` → `claimAndExecute`）。取消正好落在那里时，**不可逆工具照样执行**（RED 实测 1 次）。
4. **取消侧只结 CANCELLED**：`cancelPendings` 只碰 `PENDING`（`RunStore.java:2324-2340`）⇒ 已批准条目留在 `APPROVED`；`settleCancelledToolCalls` 只收 `CANCELLED` ∩ `{CONFIRM, FRONTEND}` ⇒ **0 条 end**，且这个 `APPROVED` 还会让"下一次续跑"有机会把它执行掉。
5. **收敛判据与执行判据必须同源**：`tryClaimExecution` 一落 `HSETNX` 即写 `executed=true / claimedAtMs / claimedEpoch / executedBy`（`RunStore.java:1984-1996`）——这些就是"不能伪装未执行"的证据面。

---

## 2. RED（确定性，三条缺口一次跑出）

隔离副本 `<隔离副本>/_m2pre-163-red`（基线 = 主仓当前工作区，**只有**新增用例，无生产修复、无测试替身改动）：

```
mvn -o -B -s maven-settings.xml -Dtest=CancelLedgerConformanceTest,ParkSettlementAuditTest test
→ Tests run: 26, Failures: 3, Errors: 0
```

| 用例 | 断言 | 实测 |
|---|---|---|
| `CancelLedgerConformanceTest#approvedButNeverClaimedIsCancelledAndSettledExactlyOnce` | 批准后立即停止 ⇒ 条目收敛 `CANCELLED` | **expected: "CANCELLED" but was: "APPROVED"**（下一步的"恰 1 条 end"同样为 0） |
| `CancelLedgerConformanceTest#approvedEntryIsNotExecutedWhenStopLandsBeforeTheClaim` | 取消标志已在时不得认领/执行 | **expected: 0 but was: 1**（工具被真实调用一次） |
| `ParkSettlementAuditTest#cancelLandingBetweenThePreCheckAndTheClaimEndsTheResumeAsCancelled` | 认领窗口命中取消 ⇒ 续跑以取消终态收口 | **expected: ALREADY_CANCELLED but was: ACCEPTED** |

三条时序都是**确定性、无线程**：
- A：`park 一张确认卡 → submitDecision(approved) → cancelParked`；
- A′：条目 `APPROVED`、`cancelState=CANCELLED`，直接调用 `resolvePending`（续跑重建里紧接检查之后的那一步），Prompt 带**真实可解析工具回调**，调用即计数；
- A″：`resume()` 的调用序列 = 入口检查"未取消" → 重建内检查"未取消" → 认领前复核"已取消"（`cancelState` 连续桩）。

日志：`<隔离副本>/_m2pre-163/red-final.log`。

---

## 3. 修复（6 文件 / +341 / −5）

### 3.1 `RunStore`（收敛判据）

- 新增 public `cancellableBeforeExecution(runId, pending)`：`PENDING`（原口径）或 **`APPROVED` 且"零证据"**。
  - 零证据 = `!executed && claimedAtMs<=0 && claimedEpoch<=0 && executedBy==null && executedAtMs<=0`，
    **且** 认领表 `ai:claim:<runId>` 无该 toolCallId（覆盖跨实例窗口：对方 `HSETNX` 已落、条目字段未回写）。
  - 认领表读失败 ⇒ **不收敛**（证明不了"未认领"就不能说"未执行"），只 WARN；判据不抛。
- `cancelPendings` 改用它（**不是**"取消所有 APPROVED"）。`PENDING` 分支逐字不变。

### 3.2 `SpToolCallingManager`（认领前复核；不新增发点）

`claimAndExecute` 第一句插入 `settleIfCancelledBeforeClaim(runId, pending)`：

- `cancelState != CANCELLED` ⇒ 原样走认领（普通批准不变，C）。
- `== CANCELLED` 且条目**可证明未认领未执行** ⇒ 收敛为 `CANCELLED`（`outcome/code/reason/executed=false/resolvedAt/resultText` 全部如实写），返回"未执行"文本，**不认领、不执行**。
- 有任何认领/执行证据 ⇒ 返回 null，交既有 claim 失败口径（`STALE_FENCE`/`LOST`→`ALREADY_CLAIMED`），**绝不改写真实结果**（B）。
- 取消状态读失败 ⇒ 返回 null（不改变既有行为，认领侧 fence 仍是最终保护）。
- **不发结算行**：所有权仍在取消侧（`settleCancelledToolCalls`）与内联 abort（`traceToolAborted`→`emitCustomOnce`），两者都经 `ai:observed` 仲裁；这里补一条就会成为第三个来源。

### 3.3 `ResumeService`（为什么必须改这里 —— 根因说明）

认领前复核只能"放弃执行 + 收敛条目"，**执行方没有结束这一轮的能力**（不持有 chat/终态面）。若不管，重建循环会带着一条 CANCELLED 工具结果继续重建、交棒给模型 ⇒ 用户按下的停止被"吞掉"，轮次以 `done` 收尾（RED 实测 `ACCEPTED`）。

因此在该循环里 `resolvePending` 之后**复用同一个入口** `cancelStateOutcome(runId, body)`：命中即 `chatService.cancelParked`（取消终态 + 补裁决帧 + 结算行）并以 `ALREADY_CANCELLED` 返回 —— 与 `:845` 的执行前检查同源、同语义，只是把窗口补到了认领之后。未命中时只多一次单键读，行为不变。

### 3.4 测试替身 `InMemoryRunStore`（测试面，非生产）

它**覆盖**了 `cancelPendings`（旧 PENDING-only 实现），不跟改就会让"生产已收敛、用例仍读 APPROVED"。现改为复用同一判据 `cancellableBeforeExecution`（它已 override `claimedBy`，跨实例认领表语义一致）。

### 3.5 未做

未动前端/`docs`/配置/其它设施；未新增仲裁设施；未改 `ai:observed` 键语义；未把 `cancelPendings` 扩成"所有 APPROVED"；未在取消侧给 BACKEND 补发（结算权仍归执行认领）。

---

## 4. GREEN（4 类 55 例）

```
mvn -o -B -s maven-settings.xml \
  -Dtest=CancelLedgerConformanceTest,ParkSettlementAuditTest,ParkSettlementOwnershipTest,\
RunStoreReadFailureFailClosedTest test
→ Tests run: 55, Failures: 0, Errors: 0 — BUILD SUCCESS
```

| 类 | 例数 | 覆盖 |
|---|---|---|
| `CancelLedgerConformanceTest` | 12 | A（收敛 + 恰 1 条 end）、A′（取消后 0 次调用 + CANCELLED + 1 条）、B（已认领/已完成不动、0 条补发）、既有取消/abort/BACKEND/帧侧 9 例 |
| `ParkSettlementAuditTest` | 14 | A″（续跑以 `ALREADY_CANCELLED` 收尾 + 工具 0 次）、C（approve 成功/失败各恰 1 条、park-off 内联、拒绝、前端超时、多卡到期） |
| `ParkSettlementOwnershipTest`（相邻） | 14 | 多实例结算所有权/仲裁（首写者、释放、重复不回退） |
| `RunStoreReadFailureFailClosedTest`（相邻） | 15 | `cancelPendings` 的 lenient 读/整表读失败/guard 读失败口径不变（PENDING 分支逐字未改） |

**反例逐条**：
- B：`c-claimed`（APPROVED + `executed/claimedAtMs/claimedEpoch/executedBy`）取消后仍是 APPROVED、`executedBy=instance-b`、`resultText` 原样、**0 条** end；`c-completed`（EXECUTED + `#4242`）结果不被抹掉、0 条 end。
- C：`resumeExecutionSettlesBackendToolExactlyOnce` / `resumeExecutionFailureSettlesOnceWithTheRealOutcome` 仍各恰 1 条；`inlineRejectStillSettlesExactlyOnce`（park-off）等不变。
- A′ 的结算唯一性：收敛后由 `cancelParked` 的 `settleCancelledToolCalls` 发**一条**，`ai:observed` 首写者仲裁保证重复取消不放大（既有用例 `parkedCancelSettlesEveryCancelledCardExactlyOnce` 的二次取消仍 2 条）。

日志：`<隔离副本>/_m2pre-163/green4.log`。

---

## 5. 未能"绝对停止"外部工具的现实边界（如实登记）

1. **认领前复核 ≠ 分布式锁**：取消可能恰好落在"复核通过 → `HSETNX` 成功"之间，或工具已被认领并开始执行。此时工具**仍会执行/继续执行**（PK-02 同源窗口）。这是现实边界：要绝对阻止，需要把工具执行与取消做成同一把跨实例互斥（会改变执行架构与延迟），本轮不做。
2. **一旦有认领证据，取消只能"如实记账"**：不伪装未执行、不抹结果（B）；若工具正在执行，取消不会中断它（不可逆工具的中断语义本身不可靠），最终以真实结局结算一条 end（认领胜者）。
3. **`cancelPendings` 的收敛是尽力而为**：`pendingLenient` 读失败按空表（既有 C2 口径，少一行、不报错）；认领表读失败则该条不收敛（保守方向：宁可留 APPROVED 也不伪称未执行）。
4. **帧面变化（预期、truthful）**：已批准但未执行的卡在取消时会收到 `confirm_decision(cancel)`（此前是"停在已批准"），前端按既有 cancel 分支收敛。
5. **BACKEND 不变**：取消侧仍不结 BACKEND（结算权归执行认领/内联 abort）；`cancelPendings` 对 `PENDING` BACKEND 的收敛口径与改造前逐字相同。
6. **未跑全量/未 clean/未起服务**。建议最终全量额外覆盖 `ConvergeMemoryToolCallStatesTest`、`MultiCardParkResumeTest`、`ChatTurnFrameSequenceConformanceTest`（本轮按预算未跑，静态读过均不涉及"APPROVED + 取消收尾"组合）。

---

## 6. 交付物与命令

| 文件 | 内容 |
|---|---|
| `task-8-preexec-cancel.patch` | 6 文件 / +341 / −5（`SpToolCallingManager` +70、`RunStore` +45/−3、`ResumeService` +10、`CancelLedgerConformanceTest` +190/−1、`ParkSettlementAuditTest` +24、`InMemoryRunStore` +2/−1） |
| `task-8-preexec-cancel-report.md` | 本报告 |

| 命令（隔离副本） | 结果 |
|---|---|
| RED（仅新用例）`-Dtest=CancelLedgerConformanceTest,ParkSettlementAuditTest` | `Tests run: 26, Failures: 3`（APPROVED 未收敛 / 工具被执行 1 次 / 续跑 ACCEPTED） |
| GREEN（4 类） | `Tests run: 55, Failures: 0` — BUILD SUCCESS |
| `git apply --check --verbose task-8-preexec-cancel.patch`（主仓当前工作区，只读） | 6 文件全部通过 |

**提交建议**：`fix(ai): 批准后未执行即停止时收敛为取消并阻止认领执行（R1）`
