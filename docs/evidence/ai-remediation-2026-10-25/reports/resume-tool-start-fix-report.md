> 来源=<仓外证据>/resume-tool-start-fix-report.md（2026-10-25 归档）· 仓外正式过程报告，属历史证据，不代替现行基线/决策文档（docs/02、docs/03、docs/adr/）
# 续跑补发 tool_start —— 修复报告（suspend-kill-a 的产品侧缺口）

日期：2026-10-25　隔离副本：`<验证副本>/_verify-20261025-park-seq`（已含 latch fix）
基线：main 当前工作树（`ae883fb` + 前序未提交批次；控制器已/将套用 `latch-fix.patch`）
来源：`<仓外证据>/cluster-protocol-report.md` §2（issue 2 取证）与 §6.1（建议修法）

## 0. 结论

真实集群里"原实例在挂起状态对外成立之后、`tool_start` <b>落档之前</b>死亡"会永久留下
**有 `tool_result`、没有 `tool_start`** 的帧册；续跑/接管（`ResumeService#resolvePending` →
`claimAndExecute`）只发结局帧，从不补开始帧。本批在**续跑结算的唯一入口**加了一次
**归档判据 + 幂等补发**：结算前读事件日志（跨实例同一份），该 `toolCallId` 没有 `tool_start`
才补一条，已有则一条不补。首轮帧序、决策幂等、租约/fencing 均未改动。

## 1. 缺口（来自已批准的集群调研）

- 证据（`cluster-protocol-report.md` §2）：`suspend-kill-a` 里 A 在 `.609` 被 SIGKILL，
  A 的日志停在 `turn_start`（既没有 `tool_call_start`，也没有 `approval_park`）⇒ 死在
  `publishRequestsForParkedBatch` 之后、`tool_start` 落档之前；B（接管方）读同一个
  `ai:xevents:<runId>` 的差量/全量都拿不到该帧 ⇒ **归档里从来没有它**。
  B 随后确实执行了工具（台账 `executedBy=B`），`tool_result` 由 `claimAndExecute` 落档。
- 机制（§6.1）：`saveSuspended`（`SpToolCallingManager.java:471`）→ `out.suspended`（473）→
  本批 `tool_start`（490–494）→ 请求帧（499）：**状态先对外成立、开始帧后落档**，两段之间没有原子性；
  而 `resolvePending`（`ResumeService.java:842`）只结算条目、`claimAndExecute` 只在
  `SpToolCallingManager.java:1527/1530` 发 `tool_result` —— 续跑路径上 `toolStart` 无调用点。
- 影响：帧册不变量"每个被执行工具恰一条 `tool_start` + 一条 `tool_result`"永久破裂；
  前端据 `tool_start` 建段/置 running，缺 start 时工具卡与结果帧无法配对（重放、审计同受影响）。

## 2. 修法

### 2.1 `SseChatEmitter#ensureToolStart(PendingToolCall)`（新增）

- 判据 = **事件日志**（生产 = Redis Stream，跨实例同一份）：`frames.readFromWithStatus(runId, 0, 0)` 里
  是否已有 `type=tool_start` 且 `toolCallId` 相等的帧；
- 已有 ⇒ 返回 false（**一条不补**，避免前端把同一工具渲染成两段）；
- 没有 ⇒ `toolStart(pending)` 补一条，返回 true；
- **读降级 / 读异常 ⇒ 保守不补**（"读不到"不是"不存在"的证明；宁可留缺口，也不重复建段）；
- 不阻塞、不新增锁：读归档在主流程里同步完成，取号/落档仍在既有 `emitLock` 内。

### 2.2 `SpToolCallingManager#resolvePending`（续跑结算入口，两处调用）

```java
if (APPROVED && FRONTEND) {
    ensureToolStartBeforeSettlement(runId, pending);   // 该分支也会发 tool_result
    return settleBlocked(...);
}
if (shouldExecute) {                                   // APPROVED / PENDING+BACKEND / EXECUTED&&!executed
    ensureToolStartBeforeSettlement(runId, pending);   // 本分支所有出口都发 tool_result
    return executeOnce(runId, prompt, pending);
}
```

`ensureToolStartBeforeSettlement` 只做"调一次 + 留痕"，异常只 WARN，**绝不阻断结算**
（发帧是观测面，与 `context` 帧同口径）。

**为什么挂在这里（而不是 `executeOnce` / 决策路径 / 首轮）**：

- `executeOnce` 也是**首轮**执行路径的出口（`SpToolCallingManager.java:585`）。首轮帧序
  `suspended → tool_start → 请求帧 → 执行` 由 `emitToolStartOnce` 保证，不能被"每个后端工具一次归档读"
  改写（也不该为它付全量归档读的代价）；
- `resolvePending` 是**续跑结算的唯一入口**（只被 `ResumeService` 调用），覆盖面与 `executeOnce`
  相同（台账重放 / 目标校验 / 资源锁 / 认领 / 执行 / 超时 / 认领失败），工作面最小；
- 决策路径（`ConfirmGate` 的 `frontend_tool_result` 等）不属于"续跑/执行的所有权边界"，本批不动（见 §6.1）。

## 3. 变更文件（3 个，全部由补丁落地）

| # | 文件 | 变更 |
|---|---|---|
| 1 | `backend/src/main/java/com/example/configmgr/ai/run/SseChatEmitter.java` | 新增 `ensureToolStart(PendingToolCall)`（1 个 hunk） |
| 2 | `backend/src/main/java/com/example/configmgr/ai/gate/SpToolCallingManager.java` | `resolvePending` 两处调用 + 私有 `ensureToolStartBeforeSettlement`（2 个 hunk） |
| 3 | `backend/src/test/java/com/example/configmgr/ai/conformance/ResumeToolStartBackfillConformanceTest.java` | **新文件**，4 个用例（313 行） |

未改：首轮发帧顺序、`ConfirmGate`、`ResumeService`、探针与 E2E 脚本、CI 配置、前端。

## 4. RED → GREEN

Maven：IDEA 自带 3.9.16（`<工具安装目录>/JetBrains/IntelliJ IDEA/plugins/maven-plugin/lib/maven3/bin/mvn.cmd`），
命令统一 `-o -B -Dspring.data.redis.port=1`、无 `clean`。

### 4.1 测试怎么"确定性地"模拟原实例死亡

新用例复用 `ParkToolStartOrderConformanceTest` 的真装配（真 `SpToolCallingManager` + 真 `ConfirmGate`
+ 真 `ResumeService` + `InMemoryRunStore` 归档 + `FakeStartImportTool`），只把写出器的 `toolStart`
包一层：首轮 park 时吞掉接下来的 N 次 `tool_start`（不取号、不留洞）——等价于"原实例在落档之前死亡"，
归档里只剩 `start / suspended / confirm_request`，卡仍可被另一实例决策。随后：

- `gate.submitDecision(runId, callId, true, ...)` 落库 `APPROVED` 并公开 `confirm_decision`；
- `ResumeService.resume(runId)` 走真续跑（真认领 + 真委托执行 + 真归档）。

### 4.2 RED（修复前代码，`start-backfill-red.log`）

```
-Dtest='ResumeToolStartBackfillConformanceTest'
Tests run: 4, Failures: 3, Errors: 0, Skipped: 0   → BUILD FAILURE
  crossInstanceResumeBackfillsTheMissingToolStartBeforeTheResult:179  [接管方必须补发…] expected: 1L but was: 0L
  aCrashBetweenBackfillAndResultDoesNotDuplicateTheStartOnTheNextResume:220  [补发已在崩溃前落档] expected: 1L but was: 0L
  repeatedResumeAfterACompletedRoundAddsNoFrames:245  expected: 1L but was: 0L
```

三条失败都是同一个被钉住的缺陷（`tool_start` 计数 0）；对照组
`resumeKeepsExactlyOneToolStartWhenTheOriginalOneIsAlreadyArchived` 红前即绿（说明"已有就不补"的基线成立）。

### 4.3 GREEN（修复后，`start-backfill-green1.log`）

```
-Dtest='ResumeToolStartBackfillConformanceTest'
Tests run: 4, Failures: 0, Errors: 0, Skipped: 0   → BUILD SUCCESS
```

四个用例覆盖：

1. **核心**：归档缺 `tool_start` ⇒ 接管方补发恰一条，且 `suspended < tool_start < tool_result`；
   工具恰执行一次（`invocations==1`）、条目 `executed=true`；
2. **不重复**：归档已有 `tool_start` ⇒ 仍恰一条（不补第二帧），结局帧恰一条；
3. **补发之后、结局帧之前崩溃**（让台账写抛错）：崩溃时"start 已落档、result 未落档"；
   再次续跑仍恰一条 start、恰一条 result（认领已失 ⇒ `ALREADY_CLAIMED` 结算，不重放副作用）；
4. **重复续跑**：一轮跑完（快照 RUNNING）后再挂一次 ⇒ `ALREADY_RUNNING`，不产生任何新帧、不重复补发。

### 4.4 回归（58 个类：frame / emitter / gate / resume，`start-backfill-regression2.log`）

```
Tests run: 326, Failures: 0, Errors: 0, Skipped: 0   → BUILD SUCCESS
```

清单：`SseChatEmitterFrames/Delivery/DrainHandoff/BusinessActivity/AuthorityFailure/WakeFloor/FrameStore/
CrossInstanceWake/ReasoningFrames/WriteBudget`、`FrameWakeListener`、`FrameCompatRead`、
`ReattachLastSeqConformance`、`SeqPersistence`、`CancelDecisionFrames`、`RunStoreActivityAnchor`、
`SseFrameSequenceConformance`、`ChatTurnFrameSequenceConformance`、`ToolFrameSequenceConformance`、
`ParkToolStartOrderConformance`、`ResumeToolStartBackfillConformance`、`MultiCardParkResume`、
`HumanWaitPark`、`ConfirmGate{ParkExitRace,RequestEmitted,ToolKind,DecisionAtomic,Arm,Lease,DeadlockResume,
ResolvedSkip}`、`Resume{EnvelopeShape,KindApproval,SkipCascade,ServiceLease,ContextCompaction,SystemRestore,
ReasoningMetadata,DriveFailureTerminal,DescribeLockKeys,FailureMessage,SessionGate,DispatchRetry}`、
`OrphanResumePendingUnresolvedExperiment`、`StartupResumeRunnerSessionGate`、
`SpToolCallingManager{GateArm,BatchStop,TurnBudget,ScopeGuard,StepLimit,ToolTimeout}`、
`ToolPreflightGate`、`ToolUnknownReconciler`、`ParkedFrontendExpiry`、
`AiController{EventsParkClose,ParkWiring}`、`ResilientChatServicePark`。

补充（`start-backfill-green2.log`，CRLF 转换后复跑）：`ResumeToolStartBackfillConformance 4/4` +
`ParkToolStartOrderConformance 2/2` + `SseChatEmitterCrossInstanceWake 9/9` ⇒ 15/15 BUILD SUCCESS。

**一次与本次修复无关的回归失败（已排除）**：首轮 58 类回归（`start-backfill-regression.log`）出现
`SseChatEmitterCrossInstanceWakeTest.selfWakeOnTheSameInstanceDoesNotDuplicateTheFrame` 1 条失败。
原因是副本的 `SseChatEmitter.java` 落后于 main：main 已含"自唤醒不重复投递"的落档顺序修正
（`lastFanoutSeq` 在 `publishWake` 之前登记 + 扇出跳过 `liveSeq <= sub.wakeFloor`），副本没有；
该用例文件只有注释与 main 不同、用例体一致。把副本的 `SseChatEmitter.java` **对齐到 main** 后
（本补丁即建立在该基线之上，只多一个 `ensureToolStart` 方法），回归 326/326 全绿。

## 5. 补丁

- 路径：`<验证副本>/_verify-20261025-park-seq/resume-tool-start-fix.patch`（424 行；3 文件；4 个 hunk）
- 生成方式：以 **main 当前工作树**为 a 侧（`SseChatEmitter`、`SpToolCallingManager`），
  副本为 b 侧；新文件以 `/dev/null` 为 a 侧。两侧行尾一致（CRLF），因此 hunk 之外的字节完全相同。
- 校验（只读空跑，未套用）：在 `<主仓>/` 执行
  `git apply --check -p1 --whitespace=nowarn resume-tool-start-fix.patch` ⇒ **rc=0**
- 内容（hunk 一览）：
  1. `SseChatEmitter.java @@ -1121,6 +1121,51 @@`（`toolStart` 之后新增 `ensureToolStart`）
  2. `SpToolCallingManager.java @@ -1821,12 +1821,17 @@`（`resolvePending` 两处调用）
  3. `SpToolCallingManager.java @@ -1843,6 +1848,33 @@`（`ensureToolStartBeforeSettlement`）
  4. `ResumeToolStartBackfillConformanceTest.java @@ -0,0 +1,313 @@`（新文件）

## 6. 未覆盖的边界与口径

1. **前端卡结局帧不经过续跑**：`FRONTEND_RESULT` 条目的 `frontend_tool_result` 由决策路径
   （`ConfirmGate#submitFrontendResult`、park 后的到期收敛）发出，续跑只复用文本。若原实例同样死在
   该卡的 `tool_start` 之前，那条路径**仍会**出现"result 无 start"。它不属于本批的
   "续跑/执行所有权边界"（任务明确限定），需要单独裁决（候选：决策落库的同锁内做同一幂等补发）。
2. **批策略跳过的条目**：`skipAsUpstreamFailed` 发出的 `tool_result`（续跑与首轮共用）不在补发范围内；
   首轮该路径本身也不发 `tool_start`（既有行为，本批未动）。
3. **判据不是 CAS**：`ensureToolStart` = "读归档 → 若缺则发"。同进程由 `ResumeService` 的
   `resuming` + 租约即派发前置挡住并发续跑；跨实例由续跑权租约（`dispatchResume` 先抢租、
   `resume` 入口再验 `runLeaseAuthority`）串行化。极端情况下（租约降级）两个实例可能各补一条 start；
   执行侧的"恰一次"仍由 `claimExecution`/fence 兜底。
4. **读降级时保守不补**：`readFromWithStatus(...).degraded()==true` 或读异常 ⇒ 不补（保留缺口而不造重复帧）。
   代价是 Redis 抖动期间补发可能缺席；重挂回放不受影响。
5. **成本**：补发判据是一次全量归档读（生产受 6000 条窗口约束），按"每个被结算的工具一次"计；
   续跑不是热路径（一次人工决策一次）。未做缓存（缓存需要一个"只增不减"的正向集合，会增加出帧路径的状态面）。
6. **序号**：补发的 `tool_start` 取续跑时刻的新 seq（不改写归档、不重排），因此它的位置在
   `confirm_decision` 之后；探针原有的 `tool_start < tool_result`、`confirm_decision < tool_result` 与
   "park 阶段帧集"断言都不受影响（未改探针）。
7. **未跑真机 E2E / 未复跑主 CI**：本轮证据是后端单测 + 隔离副本回归；`suspend-kill-a` 的新探针
   （park 屏障 + `park 边界帧集` 断言）未改。

## 7. 建议的后续动作

1. 主树套用 `resume-tool-start-fix.patch`（在 `latch-fix.patch` 之后；两者文件不相交，顺序无关），
   先跑 §4.4 的 58 类，再跑一次含 `suspend-kill-a` 的真实 E2E；
2. E2E 里可加一条 grep：接管方结算时若补发过 start，日志有
   `续跑补发缺失的 tool_start runId=... toolCallId=...`（INFO），据此判断真实窗口的命中率；
3. 若要闭合 §6.1（前端卡），在 `ConfirmGate` 的决策落库同锁内复用同一个
   `SseChatEmitter#ensureToolStart`——那是另一处所有权边界，需单独立项与用例。
