> 来源=<仓外证据>/frontend-result-start-fix-report.md（2026-10-25 归档）· 仓外正式过程报告，属历史证据，不代替现行基线/决策文档（docs/02、docs/03、docs/adr/）
# 前端卡决策侧补发 tool_start —— 修复报告（suspend-kill-a 的 FRONTEND_RESULT 残支）

日期：2026-10-25　隔离副本：`<验证副本>/_verify-20261025-park-seq`（已含 latch fix + resume 侧 backfill）
基线：current main（`ae883fb` + 前序批次，已套用 `latch-fix.patch` 与 `resume-tool-start-fix.patch`）
依据：`<仓外证据>/resume-start-task-review.md`（"Scope / coverage finding: FRONTEND_RESULT cards remain uncovered"）

## 0. 结论

这是**同一缺陷的残支**，不是新功能：前端卡的结局帧 `frontend_tool_result` 由**决策路径**在续跑之前发出，
所以"原实例死在 `tool_start` 落档之前"这一形态**无法**在 `resolvePending` 处修复（那时 result 已在归档里）。
本批把已经验证过的归档判据补发（`SseChatEmitter#ensureToolStart`）接到**决策落库的那把 `submitLock` 内、
发结局帧之前**，于是：

- 丢了 start ⇒ 决策方补**恰一条**，且排在 `frontend_tool_result` **之前**；
- start 已在归档 ⇒ 一条不补；
- 决策幂等、租约/fencing、不阻塞语义、首轮 `suspended → tool_start → request` 顺序**全部不变**。

只覆盖 `FRONTEND_RESULT`（本残支）。`FRONTEND_CANCELLED`（`dismiss`）、系统到期/整轮取消等其它已决状态
的结局帧路径**未动**（见 §6.1）。

## 1. 为什么补发点必须在决策路径

| 事实 | 依据 |
|---|---|
| 前端卡的终态帧 `frontend_tool_result` 由 `ConfirmGate#doSubmitFrontendResult` 在落库同锁内发出 | `ConfirmGate.java:1583`（修复前） |
| 续跑侧对 `FRONTEND_RESULT` 只复用 `resultText`，不发任何帧 | `SpToolCallingManager#resolvePending` 的"其余状态"尾分支 |
| park 后决策可能来自**另一实例**（HTTP 回灌），而帧归档跨实例同一份 | `ai:xevents:<runId>`（`FrameStore`） |
| 若在续跑时补，start 只能排在已存在的 result **之后** | 帧只追加、不重排 |

因此唯一的正确位置是：**决策落库与结局帧之间，且在同一把 `submitLock` 内**。

## 2. 修法

`ConfirmGate#doSubmitFrontendResult` 的写入块（`ConfirmGate.java:1580` 起）：

```java
synchronized (this.submitLock) {
    this.store.putPending(runId, pending);
    // 决策侧帧册收口：同一把锁内、发结局帧之前补 tool_start（归档已有则不补）
    SseChatEmitter out = this.registry.of(runId);
    try {
        if (out.ensureToolStart(pending)) {
            log.info("决策侧补发缺失的 tool_start runId={} toolCallId={} name={}", ...);
        }
    }
    catch (RuntimeException ex) {
        log.warn("决策侧补发 tool_start 失败（不影响决策）runId={} toolCallId={}：{}", ...);
    }
    out.frontendToolResult(pending, ok, resultText);
    woke = signal(runId, toolCallId);
}
```

要点（逐条对应任务要求）：

1. **复用归档判据**：`SseChatEmitter#ensureToolStart`（上一批引入）读**事件日志**（生产 = Redis Stream，
   跨实例同一份），该 `toolCallId` 已有 `tool_start` 则返回 false，不补；读降级/读异常保守不补。
   本批没有新增判据逻辑，也没有新增第二份实现。
2. **正确的写出器身份**：把 `this.registry.of(runId)` 提成局部变量 `out`，补发帧与结局帧由**同一个**
   写出器实例发出（同一套取号锚、落档、订阅扇出）。没有 new 第二个 emitter。
3. **决策幂等**：`tryRecordDecision` 在更早处（`ConfirmGate.java:1548`）已决出唯一写者；
   重复回灌在 `PENDING` 判定处就返回 `DUPLICATE`，**根本走不到**这段写入块，因此补发每个决策最多执行一次。
4. **fencing 不变**：出帧仍走 `SseChatEmitter#emit` 的续跑权判定（`LOST`/`UNAVAILABLE` ⇒ 丢业务帧），
   补发帧不绕开它；租约与决策落库语义未动。
5. **不阻塞、不加锁**：只是"读归档 + 一次既有出帧"，全部在既有 `submitLock` 内；不新增线程/等待。
   代价：每次被接受的 `FRONTEND_RESULT` 决策多一次全量归档读（受 6000 条窗口约束），
   决策路径本来就在这把锁内做 Redis 写（`putPending`），未引入新的锁序。
6. **失败不改决策语义**：补发抛错只 WARN，落库/结局帧/唤醒照常（与 `context` 帧同口径）。
7. **其它已决状态不扩散**：`dismiss`（`FRONTEND_CANCELLED`）、`expireFrontend`（TIMEOUT）、
   `cancel` 三处结局帧路径**一个字都没改**。

## 3. 变更文件（2 个，全部由补丁落地）

| # | 文件 | 变更 |
|---|---|---|
| 1 | `backend/src/main/java/com/example/configmgr/ai/gate/ConfirmGate.java` | `doSubmitFrontendResult` 写入块：同一 `submitLock` 内、`frontendToolResult` 之前幂等补发 `tool_start`（1 个 hunk，+19/−1） |
| 2 | `backend/src/test/java/com/example/configmgr/ai/conformance/FrontendResultToolStartConformanceTest.java` | **新文件**，4 个用例（324 行） |

未改：`SseChatEmitter`、`SpToolCallingManager`、`ResumeService`、首轮发帧路径、探针、CI。

## 4. RED → GREEN

Maven：IDEA 自带 3.9.16（`<工具安装目录>/JetBrains/IntelliJ IDEA/plugins/maven-plugin/lib/maven3/bin/mvn.cmd`），
`-o -B -Dspring.data.redis.port=1`，无 `clean`。

### 4.1 测试怎么确定性复现

复用 `ParkToolStartOrderConformanceTest` 的真装配（真 `SpToolCallingManager` + 真 `ConfirmGate` +
真 `ResumeService` + `InMemoryRunStore` 归档），一张真前端卡（`generative_form`）：

- 首轮 park 时把写出器的 `toolStart` 包一层、吞掉第一次调用（不取号、不留洞）⇒ 归档只剩
  `start / suspended / frontend_tool_request`，等价于"原实例死在 `tool_start` 落档之前"，卡仍可被回灌；
- 决策：`gate.submitFrontendResult(...)`（与 HTTP 入口同一条路径）；
- 断言：`tool_start` 恰一条且**早于** `frontend_tool_result`；随后 `ResumeService.resume` 不得再补。

### 4.2 RED（修复前代码，`frontend-result-red.log`）

```
-Dtest='FrontendResultToolStartConformanceTest'
Tests run: 4, Failures: 3, Errors: 0, Skipped: 0   → BUILD FAILURE
  decisionBackfillsTheMissingToolStartBeforeTheFrontendResult:178  [决策路径必须补发…] expected: 1L but was: 0L
  aRepeatedDecisionIsStillDuplicateAndAddsNoFrame:227               expected: 1L but was: 0L
  aCrashBetweenBackfillAndResultDoesNotDuplicateOnRepeatDecisionOrResume:243  [补发已在崩溃前落档] expected: 1L but was: 0L
```

三条失败都是同一被钉住的缺陷（决策路径没有 `tool_start`）；对照组
`anArchivedToolStartIsNotDuplicatedByTheDecision` 红前即绿（"已有就不补"的基线成立）。

### 4.3 GREEN（修复后，`frontend-result-green1.log`）

```
-Dtest='FrontendResultToolStartConformanceTest'
Tests run: 4, Failures: 0, Errors: 0, Skipped: 0   → BUILD SUCCESS
```

四个用例覆盖：

1. **核心**：归档缺 start ⇒ 决策补恰一条，`suspended < tool_start < frontend_tool_result`；
   随后的 `resume` 仍恰一条 start、一条 result 且 start 在前（两处所有权边界不互相重复补）；
   条目状态 `FRONTEND_RESULT`；
2. **对照**：原 start 已在归档 ⇒ 决策不补；同时断言首轮帧序
   `suspended → tool_start → frontend_tool_request` 未变；
3. **重复回灌**：第二次是 `DUPLICATE`，帧数不增，start/result 各恰一条（决策幂等与补发幂等互不妨碍）；
4. **补发之后、结局帧之前崩溃**（结局帧抛出）：崩溃时恰一条 start、零 result；
   重复回灌 `DUPLICATE`、随后续跑 ⇒ start 仍恰一条（不重复），result 仍为 0
   （前端卡的结局帧只由决策路径产出，崩溃点之后续跑不会伪造它，这是既有语义，本批不改变）。

### 4.4 回归（67 个类：gate / resume / frame / emitter / controller，`frontend-result-regression.log`）

```
Tests run: 358, Failures: 0, Errors: 0, Skipped: 0   → BUILD SUCCESS
```

含：本批新类 + `ResumeToolStartBackfillConformance`、`ParkToolStartOrderConformance`、
`ConfirmGate{ParkExitRace,RequestEmitted,ToolKind,DecisionAtomic,Arm,Lease,DeadlockResume,ResolvedSkip,
FrontendTimeoutText,HumanWait,SheetMetaPersist,Wiring}`、`GenerativeFormGate`、`HumanWaitPark`、
`ParkedFrontendExpiry`、`SuspendDisconnectGate`、`ResumeDispatchRetry`、`ToolIdempotency`、`ToolOutcomeExit`、
`MultiCardParkResume`、`Resume{EnvelopeShape,KindApproval,SkipCascade,ServiceLease,ContextCompaction,
SystemRestore,ReasoningMetadata,DriveFailureTerminal,DescribeLockKeys,FailureMessage,SessionGate,
DispatchRetry}`、`OrphanResumePendingUnresolvedExperiment`、`StartupResumeRunnerSessionGate`、
`ResilientChatServicePark`、`SseChatEmitter{Frames,Delivery,DrainHandoff,BusinessActivity,AuthorityFailure,
WakeFloor,FrameStore,CrossInstanceWake,ReasoningFrames,WriteBudget}`、`FrameWakeListener`、`FrameCompatRead`、
`ReattachLastSeqConformance`、`SeqPersistence`、`CancelDecisionFrames`、`SseFrameSequenceConformance`、
`ChatTurnFrameSequenceConformance`、`ToolFrameSequenceConformance`、
`SpToolCallingManager{GateArm,BatchStop,TurnBudget,ScopeGuard,StepLimit,ToolTimeout}`、
`AiController{EventsParkClose,ParkWiring}`、`ToolPreflightGate`、`ToolUnknownReconciler`。

补跑（CRLF 归一后，`frontend-result-green2.log`）：`FrontendResultToolStartConformance 4/4` +
`ParkToolStartOrderConformance 2/2` + `ResumeToolStartBackfillConformance 4/4` +
`ConfirmGateParkExitRaceTest 3/3` ⇒ **13/13** BUILD SUCCESS。

## 5. 补丁

- 路径：`<验证副本>/_verify-20261025-park-seq/frontend-result-start-fix.patch`（356 行；2 文件；2 hunk）
- 生成方式：以 **current main**（已含 `latch-fix.patch` + `resume-tool-start-fix.patch`）为 a 侧，
  副本为 b 侧；新文件以 `/dev/null` 为 a 侧；两侧同为 CRLF，hunk 之外字节完全一致。
- 校验（只读空跑，未套用）：在 `<主仓>/` 执行
  `git apply --check -p1 --whitespace=nowarn frontend-result-start-fix.patch` ⇒ **rc=0**
- 内容：① `ConfirmGate.java @@ -1580,7 +1580,25 @@`；② 新测试 `@@ -0,0 +1,324 @@`。
  与既有两个补丁文件不相交（latch：ConfirmGate/AiController/两测试；resume：SseChatEmitter/SPM/一测试）。

## 6. 未覆盖的边界与口径

1. **只做 `FRONTEND_RESULT`**：`dismiss`（`FRONTEND_CANCELLED`）、park 后到期收敛（`FRONTEND_TIMEOUT`）、
   整轮取消（`CANCELLED`）三处结局帧路径同样可能缺 start（同一缺陷类），但按任务边界**未动**；
   若要把帧册不变量推广到全部已决状态，应逐路径复用同一个 `ensureToolStart` 并各自立用例。
2. **崩溃点之后没有 result**：补发 start 与结局帧之间崩溃时，结局帧不会由续跑重建
   （前端卡只由决策路径产出终态帧）。本批只保证"start 不重复"，**不**修复该 result 缺口——
   这是既有语义，修它需要"决策帧的幂等重放"，属于另一个议题。
3. **判据不是 CAS**：`ensureToolStart` 是"读归档 → 若缺则发"。并发决策由 `tryRecordDecision`
   串成唯一写者，故同一张卡不会有两个决策方同时补发；不同卡各自的 `toolCallId` 独立。
   窗口裁剪（M2）与"读降级保守不补"（M4）与上一批同一口径，本批未改。
4. **锁内归档读的成本**：每次被接受的 `FRONTEND_RESULT` 多一次全量归档读（≤6000 帧）；
   决策不是热路径，但这把锁也保护"落库→回执帧→唤醒"，故该读会拉长临界区（毫秒级）。
   若日后成为瓶颈，可在同一判据上加"本实例已发过"的正向集合，但不能改变"归档为准"的语义。
5. **测试保真度**：沿用上一批的"吞掉首帧 / 抛错"模型（归档状态等价），不模拟杀进程、丢租约或第二 JVM；
   真实三实例与 `suspend-kill-a` 探针未跑，探针断言未改。

## 7. 建议的后续动作

1. 主树按顺序套用三个补丁（latch → resume → 本补丁；文件不相交，顺序无耦合），先跑
   §4.4 的 67 类，再跑一次含 `suspend-kill-a` 的真实 E2E；
2. E2E 可 grep `决策侧补发缺失的 tool_start`（INFO）统计真实窗口命中率；
3. 若要闭合 §6.1 的其余已决状态，按同一所有权边界逐路径补，不要把这批扩成"一次性全状态改造"。
