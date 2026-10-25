> 来源=<仓外证据>/park-latch-fix-report.md（2026-10-25 归档）· 仓外正式过程报告，属历史证据，不代替现行基线/决策文档（docs/02、docs/03、docs/adr/）
# park 退出窗口丢续跑 —— 修复报告（主 CI run 40174289141 / H-01）

日期：2026-10-25　基线：main `ae883fb`（工作树含前序未提交批次）　隔离副本：`<验证副本>/_verify-20261025-park-seq`

## 0. 结论

主 CI E2E job 的 25 分钟超时（H-01）是一个**确定性可控的产品竞态**，不是模型/网络慢：

> `arm` 在发帧前预登记 Latch；park 分支在"预登记之后、Latch 摘除之前"还要做一次无锁的
> `releaseWaitingLease`（Redis 往返 1–3ms）。决策正好落在窗口内 ⇒ `signal` 命中这把**没人 await**
> 的 Latch ⇒ `woke=true` ⇒ 决策侧按 `WOKE_IN_PROCESS_GATE` 静默跳过续跑派发 ⇒ park 线程还租退出
> ⇒ 一轮**既没有驱动方也没有终帧**，快照永久 SUSPENDED；客户端在重挂的 `/events` 上无限阻塞。

修法把 park 退出改成**与决策写共用 `submitLock` 的原子生命周期切换**，窗口两侧各有一个确定出口
（已决 ⇒ 本线程接手驱动；未决 ⇒ 还租 + 摘闸门后 park，之后的 signal 必 miss ⇒ 死卡兜底派发恰一次），
并在 `GET /api/ai/events/{runId}` 的"条目已全部结算但仍 SUSPENDED"分支加一次自愈对账。

## 1. 根因链（含证据）

1. `SpToolCallingManager` 在 `suspended` 帧之前 `gate.arm(runId, gateWaitIds)`（`SpToolCallingManager.java:463`），
   闸门（`ConfirmGate.Latch`）先登记、随后才发请求帧 —— F8 的本意是让"帧已到、等待未进入"时的决策
   不被误判成"本进程无人等待"。
2. `ConfirmGate.await` 走到 park 分支（`ConfirmGate.java:601`，修复前）：
   `cancelState` 判定 → `approval_park` 日志 →（即时前端工具）`scheduleParkedFrontendExpiry` →
   **`releaseWaitingLease(lease, runId)`** → `throw RunParkedException`。
   这一串都在 `submitLock` **之外**，而"Latch 摘除"发生在更晚的 `SpToolCallingManager` finally
   `gate.disarm(...)`（`SpToolCallingManager.java:714`）。
3. 于是存在窗口：闸门在 `latches` 里、没有任何线程 await。
   决策落在此窗口内时 `submitFrontendResult` 在 `submitLock` 内 `putPending` + `signal`，
   `signal` 返回 **true**（唤醒的是预登记闸门），`maybeTriggerResume(runId, woke=true)` 直接
   `skipped("WOKE_IN_PROCESS_GATE")`。
4. park 线程继续还租、抛 `RunParkedException`，manager finally 摘闸门；本轮执行结束。
   **驱动方计数 = 0**（没有 in-thread 驱动、没有续跑派发），也没有终帧。
5. `AiController.events` 当时只在"仍有 PENDING 条目"时收流；条目已结算 ⇒ 保留连接等续跑产出的
   `done`，而续跑从未发生 ⇒ 客户端在这条连接上等到 CI 超时。

主 CI run 40174289141 / H-01 的时间线证据（`<仓外证据>/main-ci-cancel-analysis.md` 汇总）：

- runId `04e186af-20f5-40ca-a538-eb0551538be5`：`approval_park` 在 `.119`，`approval_decided` 在 `.120`
  （同一毫秒级窗口），此后**没有任何**续跑派发/死卡兜底日志；
- `e2e-evidence/boot.log`、`stub.log`（最后一条 req-082）、`gfc/` 目录里都没有该轮的终帧；
- 不是模型慢：挂起后事件流不再有任何业务帧。

## 2. 修法

### 2.1 `ConfirmGate`：park 退出 = 原子生命周期切换

`await` 的 park 分支改为（`ConfirmGate.java`，修复后 601–640）：

```java
if (parkEnabled) {
    if (cancelState == CANCELLED) return cancel(...);          // 主动 stop 优先，不变
    PendingToolCall settled = switchParkedLifecycle(runId, pending, lease);
    if (settled != null) {                                     // 已决 ⇒ 本线程接手
        closeCancelHandle(cancelHandle, runId);
        handedOff = true;
        handOffLease(lease);
        return settled;                                        // 不 park、不派发
    }
    lease = null;                                              // 未决 ⇒ 租已还，finally 不得再释放
    closeCancelHandle(cancelHandle, runId);                    // park 在 try 之前退出
    ... approval_park 日志 / 到期收敛 ...
    throw new RunParkedException(...);
}
```

`switchParkedLifecycle(...)`（新增私有方法）：

```java
synchronized (this.submitLock) {
    PendingToolCall fresh = this.store.pendingLenient(runId, pending.getToolCallId());
    if (fresh != null && fresh.resolved()) return fresh;   // 出口 A：决策已落库
    releaseWaitingLease(lease, runId);                     // 出口 B：先还租
    clearWaiterLatches(runId);                             //           再摘本 run 全部闸门
    return null;
}
```

`clearWaiterLatches(runId)`（新增私有方法）按前缀 `runId + "|"` 摘掉本 run 的**全部**闸门
（`latches.remove` + `armedKeys.remove`，与 `disarm` / `await.finally` 同序）。
只摘单张卡不够：同批其余卡也不会再被 `await`，残留闸门会让 `hasActiveLatch` 恒真、挡住死卡兜底。

**为什么顺序不可交换**：若先摘闸门再还租，决策方（同实例 HTTP 线程）会在锁外以同 owner 重入抢到
本线程手里的租约并派发续跑，随后本线程的还租又把这任租约删掉（fencing 被削弱）；
"先还租、后摘闸门、两步同在 `submitLock`"之后，决策要么看到"闸门还在"（signal 命中 ⇒ 本线程接手），
要么看到"闸门已摘"（signal 必 miss ⇒ 死卡兜底派发），不存在"命中却无人接力"的第三种。

**口径**：窗口内已决由**等待线程**接手驱动，因此决策侧 `woke=true` 的跳过是正确的（它唤醒的就是这个线程），
`triggerResume` 的 `woke` 早退**不改**（对真实等待方仍正确）。`drivers = (completed?1:0) + resumes` 恒等于 1。

同一处附带修正：`await` 的 park 分支原先在 `try/finally` 之前 return/throw，`cancellations.onCancel`
注册的监听者**从不注销**；新增 `closeCancelHandle(...)` 并让 finally 复用（退出 finally 时仍幂等）。

### 2.2 `AiController.events`：已决但无驱动方的自愈对账

`GET /api/ai/events/{runId}`（`AiController.java:766`）：park 开启 + `SUSPENDED` 时

- 仍有 PENDING 条目 ⇒ `out.completeSubscriptions()`（行为不变，非阻塞收流）；
- 条目已全部结算 ⇒ 保留连接（行为不变），并调一次
  `this.confirmGate.triggerResumeForParked(runId)`（现有 single-writer/幂等路径：租约 + `resuming` +
  状态/闸门判定），做"决策已落库却没有驱动方"的对账。已有驱动方时只会得到
  `ALREADY_RUNNING` / `LEASE_HELD_ELSEWHERE` 的跳过（后者另排一次有界重试）。
  不新增线程/调度器/超时。

## 3. 变更文件（4 个，均可由补丁落地）

| # | 文件 | 变更 |
|---|---|---|
| 1 | `backend/src/main/java/com/example/configmgr/ai/gate/ConfirmGate.java` | park 分支原子切换 + `switchParkedLifecycle` / `clearWaiterLatches` / `closeCancelHandle` |
| 2 | `backend/src/main/java/com/example/configmgr/ai/web/AiController.java` | `events` 的 settled-SUSPENDED 自愈对账 |
| 3 | `backend/src/test/java/com/example/configmgr/ai/gate/ConfirmGateParkExitRaceTest.java` | **新文件**，3 个用例（窗口内 / park 之后 / park 关闭对照） |
| 4 | `backend/src/test/java/com/example/configmgr/ai/web/AiControllerEventsParkCloseTest.java` | 2 个既有用例各加一条断言（settled ⇒ 必须调自愈；PENDING ⇒ 不得调） |

未改：`SpToolCallingManager`、`SseChatEmitter`、`ResumeService`、CI 超时、E2E 脚本、前端。

## 4. RED → GREEN

Maven：IDEA 自带 3.9.16（`<工具安装目录>/JetBrains/IntelliJ IDEA/plugins/maven-plugin/lib/maven3/bin/mvn.cmd`），
命令统一带 `-o -B -Dspring.data.redis.port=1`、无 `clean`。

### 4.1 先修的是测试（一次"假 RED"的教训）

第一版 RED 用 `cancellations.cancelState(RUN_ID)` 作阻塞点 ⇒ **3/3 通过**（`latch-red.log`），
原因是 `SpToolCallingManager.checkCancelled`（`SpToolCallingManager.java:1092`）在**执行工具前**也调
`cancelState`，mock 的阻塞发生在 `await` 之前 —— 决策先落库，`reserveRequestSlot` 直接按"已决"消费，
park 分支根本没被走到。

阻塞点改到 `cancellations.onCancel(...)`（`ConfirmGate.java:561`）：它**只**在 `await` 里被调用一次、
位于"闸门已登记"之后与"park 分支"之前，且已在 `reserveRequestSlot` 之后 ⇒ 确定性复现窗口。

### 4.2 RED（修复前代码，`latch-red2.log`）

```
-Dtest='ConfirmGateParkExitRaceTest'
Tests run: 3, Failures: 1, Errors: 0, Skipped: 0   → BUILD FAILURE
ConfirmGateParkExitRaceTest.aDecisionLandingInsideTheParkExitWindowStillGetsExactlyOneDriver:174
  [恰好一个驱动方：窗口内已决由等待线程接手（旧实现 0 个 ⇒ 悬轮 / CI 取消）] expected: 1 but was: 0
```

对照：窗口内断言 `hasActiveLatch==true`、`woke==true`、`resumeTrigger.triggered()==false` 都已通过
（即 H-01 的形态）；失败点正是"驱动方 = 0"。

### 4.3 GREEN（焦点，`latch-green2.log`）

```
-Dtest='ConfirmGateParkExitRaceTest,AiControllerEventsParkCloseTest'
ConfirmGateParkExitRaceTest            Tests run: 3, Failures: 0
AiControllerEventsParkCloseTest        Tests run: 2, Failures: 0
合计 Tests run: 5, Failures: 0, Errors: 0 → BUILD SUCCESS
```

### 4.4 GREEN（后端回归，29 个类，`latch-regression.log`）

```
Tests run: 177, Failures: 0, Errors: 0, Skipped: 0   → BUILD SUCCESS
```

类清单：`ParkToolStartOrderConformanceTest, SseChatEmitterWakeFloorTest, ReattachLastSeqConformanceTest,
SseFrameSequenceConformanceTest, ChatTurnFrameSequenceConformanceTest, ToolFrameSequenceConformanceTest,
SseChatEmitterFramesTest, SseChatEmitterDeliveryTest, SseChatEmitterDrainHandoffTest,
SseChatEmitterBusinessActivityTest, SseChatEmitterAuthorityFailureTest, FrameWakeListenerTest,
CancelDecisionFramesTest, SeqPersistenceTest, RunStoreActivityAnchorTest, MultiCardParkResumeTest,
HumanWaitParkTest, AiControllerEventsParkCloseTest, AiControllerParkWiringTest,
FrontendGuardPreSuspendTest, ParkedFrontendExpiryTest, SpToolCallingManagerGateArmTest,
SpToolCallingManagerBatchStopTest, SpToolCallingManagerTurnBudgetTest, ToolPreflightGateTest,
ConfirmGateRequestEmittedTest, ConfirmGateParkExitRaceTest, ConfirmGateToolKindTest, ResumeDispatchRetryTest`

日志（均在副本根）：`latch-red.log`（假 RED）、`latch-red2.log`（真 RED）、`latch-green2.log`（焦点 GREEN）、
`latch-regression.log`（回归 GREEN）。

## 5. 补丁

- 路径：`<验证副本>/_verify-20261025-park-seq/latch-fix.patch`（473 行，4 个文件；新文件以 `/dev/null` 为 a 侧）
- 校验（只读空跑，未套用）：在 `<主仓>/backend/` 的上一级执行
  `git apply --check -p1 --whitespace=nowarn latch-fix.patch` ⇒ **rc=0**
  （对当前 main 工作树；`ae883fb` 之上还有前序未提交批次，本补丁基线就是该工作树，
  对**干净 HEAD** 的套用未做承诺）
- 补丁只含上表 4 个文件；`SseChatEmitter.java` / `SpToolCallingManager.java`（与 main 仅 CRLF/LF 差异）**未**包含。

## 6. 未覆盖的边界与口径说明

1. **"还租与摘闸门之间"没有独立用例**：两者在 `submitLock` 内，外部无法在此插入决策；
   其正确性由锁内顺序保证（先还租、后摘闸门），并由窗口用例（命中 ⇒ 本线程接手）与 park 之后用例
   （未命中 ⇒ 恰一次派发）从两侧覆盖。
2. **窗口内已决走 in-thread 驱动，不派发续跑**：断言口径是
   `drivers = (completed?1:0) + resumes == 1` 且 `completed==true, resumes==0`。
   若未来改成"park + 自己派发"，该断言需要同步调整（语义仍是"恰一个驱动方"）。
3. **重挂自愈**只在 `AiControllerEventsParkCloseTest` 里用 mock `ConfirmGate` 验证"该调/不该调"
   （`verify(triggerResumeForParked)` / `never()`），没有覆盖真实 `ConfirmGate` + 控制器 + 续跑服务的
   端到端接线；真机/真实 Redis 的派发结局依赖既有 `ResumeService` 路径（`ALREADY_RUNNING` /
   `LEASE_HELD_ELSEWHERE` / `SCHEDULED`）。
4. **无租约降级装配**（`leases()==null`）下，跨实例"另一实例派发 + 本线程接手"理论上可双驱动，
   与旧代码在该模式下的既有风险同源，最终由 `claimExecution` 的"工具恰一次"兜底；
   本补丁未改变这条降级路径的互斥强度。
5. **未跑真机 E2E、未复跑主 CI**：本轮只做后端单测 + 隔离副本；CI 超时阈值与 E2E 脚本一字未动。
6. `AiController.events` 的自愈在"续跑已派发但仍未翻 RUNNING"时会命中 `LEASE_HELD_ELSEWHERE`
   并排一次有界租约重试（每次重挂最多一次，`allowRetry=false` 的重试不自我续命）。

## 7. 建议的主会话后续动作

1. 在主树 `backend/` 套用 `latch-fix.patch`（`git apply -p1`），先跑
   `ConfirmGateParkExitRaceTest,AiControllerEventsParkCloseTest` 再跑本报告 §4.4 的 29 类回归。
2. 触发一次真实 E2E（含 H-01 形态：决策与 park 帧几乎同刻），确认
   `approval_decided` 之后一定出现 `completed` 或续跑派发日志，且不再有 25m 超时。
3. 若要彻底消灭这类窗口，可后续把"arm 预登记 + 请求帧 + park 退出"整体纳入一个显式的
   生命周期状态机（本补丁已把退出侧做成原子的，登记侧仍有 `arm` 与 `await` 之间的设计冗余）。
