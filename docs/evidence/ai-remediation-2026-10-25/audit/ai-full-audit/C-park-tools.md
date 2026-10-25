> 来源=<仓外证据>/ai-full-audit/C-park-tools.md（2026-10-25 归档）· 审计分片/草稿，属历史证据，不代替终稿 `docs/research/排查-AI全链路只读验收审计-终稿-2026-10-25.md`
# C｜park / confirm / approval / frontend_tool_request·result / 工具执行边界 审计报告

- 审计对象（只读快照）：`<仓外证据>/ai-full-audit/tree`（base `ae883fb` + 审计开始时未提交补丁
  `working.patch` SHA256=`6c88b47d…dab955`）。
- 审计域：AI 调用边界（park/confirm/approval、frontend_tool_request/result、拒绝/取消/过期、多卡与混合后端工具、
  ResumeService 单写者与幂等、SpToolCallingManager、ConfirmGate、预检/预算、前端工具回调、业务工具调用的通用编排边界）。
- 不审：业务工具内部（JobService/TaskService/TaskFile 等实现细节、Excel 计算、KB 检索质量）→ E 域。本报告对其只审
  "AI 侧怎么调用、失败怎么回填、边界在哪"。
- 方法：逐行读本域生产代码 + 相邻测试源码；不做任何修改、不启服务、不跑真实拓扑、不触 8081/5202、不读写密钥。
  因此**本报告不含任何"实测通过"的证据**，所有"已证实"均指**代码路径可证**，不是运行期复现。

---

## 1. 覆盖清单

### 1.1 生产代码（已逐行阅读）

| 文件 | 行数 | 本报告用到/覆盖的面 |
|---|---|---|
| `backend/src/main/java/com/example/configmgr/ai/gate/ConfirmGate.java` | 1937 | 全部：arm/disarm、publishRequestsForParkedBatch、await（park/非 park）、reserveRequestSlot、cancel、expireFrontend、scheduleParkedFrontendExpiry、submitDecision、submitFrontendResult、dismiss、convergeOnDecided、maybeTriggerResume/triggerResume/dispatchResume/retryDispatch/scheduleLeaseRetry、handOffLease/releaseWaitingLease、hasActiveLatch/signal |
| `.../gate/SpToolCallingManager.java` | 1940 | 全部：预检前置、前端入参闸、saveSuspended、tool_start/request 前置、执行循环、三通道（backend/confirm/frontend）、executeOnce（kind 闸→台账重放→目标校验→资源锁→认领→执行→台账）、skipAs*/settleBlocked/blockOutOfScope/blockSheetDisabled、轮内预算、resolvePending、delegateSingle、结果信封 |
| `.../gate/ToolPreflightService.java` | 399 | 位置与失败语义（pass 判定属 E 域） |
| `.../gate/ToolResourceLocks.java` | 223 | 任务级/作业级双锁、TTL 不续租、谁创建谁释放、降级三态 |
| `.../gate/ToolTargetResolver.java` | 224 | 目标资源硬校验与 jobType/taskId 取值（判定规则属 E 域） |
| `.../gate/ToolUnknownReconciler.java` | 155 | UNKNOWN 三跳对账；前端通道不参与 |
| `.../run/ResumeService.java` | 1260 | 全部：准入（取消/终态/RUNNING/僵尸/PROVIDER_GONE/PENDING_UNRESOLVED）、租约自抢与归属校验、会话门、SYSTEM 恢复、in-flight 重建、记忆窗口、压缩、交棒、失败落终态 |
| `.../run/PendingToolCall.java` | 308 | 状态集/resolved()/effectiveOutcome()/effectiveCode()/kind/req/irreversible |
| `.../run/RunParkedException.java` | 52 | park 控制流载体 |
| `.../run/StartupResumeRunner.java` | ~200 | 启动扫描的可续跑判据（resumeable 由 listRuns 给） |
| `.../run/CancellationRegistry.java` | 368 | 三态取消、Redis 权威、监听者加速、TTL=session-ttl |
| `.../run/ToolActivityBeacon.java` | 293 | 工具脉冲、段预算、gateWait、budgetArmed/budgetSkippedBatches |
| `.../run/RunStore.java` | 2822 | 本域相关段：`save`(fence/authority)、`saveSuspended`、`putPending`、`pending/pendingRaw/pendingLenient/pendings(Lenient)`、`applyRequestMarker`、`markRequestEmitted`、`applyDecisionMarker/mergeableDecisionStatus`、`tryRecordDecision`、`tryClaimExecution/claimExecution/rollbackClaim`、`appendLedger`、`cancelPendings`、`runLeaseAuthority/heldRunEpoch`、键族与 hash tag |
| `.../run/SseChatEmitter.java` | 2206 | 只审本域相邻面：emit/seq 归档、`completeSubscriptions`、`replayAndAttach`、订阅生命周期、跨实例唤醒调用面 |
| `.../run/RunRegistry.java` | 196 | `of/find/hasLiveSubscriber/close` |
| `.../run/SessionGate.java` | 460 | 单键 Lua、token 归属、`acquire(sessionId,runId,token)`、`release(token)` |
| `.../run/LeaseService.java` | 1146 | 三键同槽 ACQUIRE/RENEW/VERIFY/RELEASE Lua、可重入 `created`、墓碑 authority、`markDriving` 存活判据 |
| `.../run/ResilientChatService.java` | 2352 | park 唯一处理点 `parkTurn`、`runAttempts` 错误隧道、`cancelTerminal`、重试三条件 |
| `.../web/AiController.java` | 2009 | `/chat` 会话门、`/confirm`、`/frontend-tool-result`、`/events/{runId}`、`/cancel/{runId}`、`/runs`、`/runs/{runId}`、`/runs/current`、`/runs/{runId}/resume` |
| `.../web/ConfirmRequest.java`、`FrontendToolResultRequest.java` | — | 入参形状与 code 归一 |
| `.../tool/FrontendToolGuard.java` | 226 | 两道闸接口（入参/回灌/结构化判定） |
| `.../form/GenerativeFormGuard.java`、`.../tool/SheetWriteGuard.java`、`.../tool/UiPrimitiveGuard.java` | — | 只审与 gate 的接缝（supports/inspect* 的调用位置与失败语义） |
| `frontend/src/stores/ai.ts` | 5523 | `confirm`、`submitFrontendToolResult`、`restoreSuspendedSnapshot`、`restoreInFlightRound`、`resumeSnapshotStream`、`reconnectSuspendedRun`、`handleFrame`（confirm_request / confirm_decision / frontend_tool_request / frontend_tool_result / suspended）、`applyGateReceipt`、`markGateSyncing/armGateSync`、多页签所有权（Web Locks + BroadcastChannel + pageId） |
| `frontend/src/api/sse.ts` | 752 | `awaitsInput` / `suspended` close 判定、终帧判定、看门狗 |
| `frontend/src/utils/parts.ts` | 871 | `tool_start` 不得撤销等动作卡的防御、段 first-wins |
| `frontend/src/utils/frontend-tools.ts` | 1560 | 即时前端工具执行器与回灌（含 outcome/code） |
| `frontend/src/components/AiPanel/AiPanel.vue` | 3700 | 抽样：按钮禁用/提交态/停止入口（未逐行） |
| `frontend/src/utils/confirm-irreversible.ts` | — | 不可撤销勾选态 |

### 1.2 相邻测试（已读源码，判定其证明力）

后端 `backend/src/test/java/com/example/configmgr/ai/`：

- gate（43 文件）：`ConfirmGateArmTest`、`ConfirmGateDeadlockResumeTest`、`ConfirmGateDecisionAtomicTest`、
  `ConfirmGateFrontendTimeoutTextTest`、`ConfirmGateHumanWaitTest`、`ConfirmGateLeaseTest`、`ConfirmGateRequestEmittedTest`、
  `ConfirmGateResolvedSkipTest`、`ConfirmGateSheetMetaPersistTest`、`ConfirmGateToolKindTest`、`ConfirmGateWiringTest`、
  `HumanWaitParkTest`、`ParkedFrontendExpiryTest`、`FrontendGuardPreSuspendTest`、`OrphanResumePendingUnresolvedExperimentTest`、
  `ResumeDispatchRetryTest`、`ResumeSkipCascadeTest`、`SuspendDisconnectGateTest`、`ToolIdempotencyTest`、`ToolOutcomeExitTest`、
  `ToolPreflightGateTest`、`ToolPreflightServiceTest`、`ToolResourceLockGateTest`、`ToolTargetResolver(Gate)Test`、
  `ToolUnknownReconcilerTest`、`SpToolCallingManager*`（BatchStop / GateArm / ScopeGuard / StepLimit / ToolTimeout / TraceFallback / TurnBudget）、
  `GenerativeFormGateTest`、`SheetDisabledGateTest`、`SheetSampleTraceTest`、`ContextFieldGateTest`、`InputSanitizerTest`、
  `AiApprovalLogTraceTest`、`AiToolCallLogTraceTest`、`AiRateLimiterTest`（与 Fakes）
- run：`MultiCardParkResumeTest`、`ResumeServiceLeaseTest`、`ResumeSessionGateTest`、`ResumeDriveFailureTerminalTest`、
  `ResumeEnvelopeShapeTest`、`ResumeFailureMessageTest`、`ResumeKindApprovalTest`、`ResumeProviderGoneTest`、
  `ResumeReasoningMetadataTest`、`ResumeServiceZombieThresholdTest`、`ResumeSystemRestoreTest`、`ResumeContextCompactionTest`、
  `ResumeDescribeLockKeysTest`、`CancelDecisionFramesTest`、`CancellationRegistryFailClosedTest`、`RunStore*`（Claim/Fence/DecisionGuard/
  Idempotency/SaveSuspended/ReadFailureFailClosed/RequestEmitted/WalConverge…）、`LeaseService*`、`LeaseEpochPerRunTest`、
  `LeaseLossReadOnlyTest`、`ResilientChatServiceParkTest`、`ResilientChatServiceMemoryWriteTest`、`StartupResumeRunnerSessionGateTest`、
  `StreamWatchdog*`、`SseChatEmitter*`、`ConvergeMemoryToolCallStatesTest`
- conformance（13）：`ParkToolStartOrderConformanceTest`（**未提交新文件**）、`ToolFrameSequenceConformanceTest`、
  `SseFrameSequenceConformanceTest`、`ChatTurnFrameSequenceConformanceTest`、`CancelLedgerConformanceTest`、
  `ReattachLastSeqConformanceTest`、`SessionBusyFrameIsolationConformanceTest`、`ThreeArgAssemblyConformanceTest`、
  `FrameContractFalsificationTest`、`FrameTypeSetFrozenTest`、`InMemoryRunStore`（测试替身基类）
- web：`AiControllerParkWiringTest`、`AiControllerEventsParkCloseTest`、`AiControllerZombieGateTest`、`AiControllerRunsCurrentTest`、
  `AiControllerFrontendResultTest`、`AiControllerToolKindEntryTest`、`AiControllerConfirmStateUnavailableTest`、
  `AiControllerSuspendedTouchTest`、`FrontendResultTooLargeTest`、`FrontendToolResultCodeTest`、`ChatSessionBusyBodyTest`
- run（新增）：`SseChatEmitterWakeFloorTest`（未提交新文件）

前端 `frontend/tests/`（139 文件，本域相关）：
`park-request-tool-start-order.spec.ts`（**未提交新文件**）、`b1-suspend-disconnect.spec.ts`、`gate-matrix.spec.ts`、
`concurrency-matrix.spec.ts`、`restore-request-emitted.spec.ts`、`restore-reconcile.spec.ts`、`restore-orphan-null-req.spec.ts`、
`frontend-tool-result-terminal.spec.ts`、`frontend-tool-outcome.spec.ts`、`tool-outcome-envelope.spec.ts`、`irreversible-lifecycle.spec.ts`、
`confirm-irreversible.spec.ts`、`tools-and-deadline.spec.ts`、`aie2e-stop.spec.ts`、`aie2e-refresh.spec.ts`、`aie2e-refresh-forms.spec.ts`、
`tab-ownership-lifecycle.spec.ts`、`r166-gate-link.spec.ts`、`r166b-fixes.spec.ts`、`gf-*.spec.ts`、`sheet-preview-*.spec.ts`、
`ai-navigate-idempotent.spec.ts`、`ai-upload-handoff.spec.ts`、`ai-tool-receipt.spec.ts`


### 1.3 未覆盖（明确列出，不静默略过）

1. **≥2 JVM 的真实并发**：无任何测试或证据。所有 gate/run 用例都在单 JVM 内（`InMemoryRunStore` 子类或 Mockito 桩）。
   最接近跨实例的只有 `ResumeServiceLeaseTest`（两个 `LeaseService` 共享一个 `FakeLeaseRedis`），仍是同进程。
2. **真实 Redis Cluster**：无 Testcontainers/embedded Redis。Lua 脚本**正文从未被执行**：`FakeLeaseRedis` 用 Java 逐行复刻
   `-- LEASE_ACQUIRE/RENEW/RELEASE` 语义，`ClusterSlotTest` 用 Java 复刻 CRC16 槽算法。
3. **真实 Spring AI 内部工具循环**：park 依赖 `executeToolCalls` 抛出的 `RunParkedException` 原样穿出官方循环；仓内证据是
   `Flux.error(new RunParkedException(...))` 的替身（`ResilientChatServiceParkTest`）或直接调 `manager.executeToolCalls`。
4. **跨实例帧唤醒**：`FrameWakeBus`/`SseChatEmitterWakeFloorTest` 用假总线；`RedisFrameStore` 只在 `FakeFrameRedis` 上验证。
5. **前端真实浏览器/真实后端**：`frontend/tests` 全部 vitest+jsdom，`@/api/ai` 全量 `vi.mock`。
6. 业务工具内部（`AiTools` 四个 `start_*`、`JobService.createAndStart` 及守卫、Excel、表格 plan、KB）→ E 域。
7. 压缩/记忆窗口/摘要（`ContextCompactor`、`MemoryWindow`）只审其在续跑中的**位置**与"取消检查在其之前"这一时序事实。
8. `SseChatEmitter` 的写入预算/限速/投递池策略只审调用面；`AiConfigService` 只审 `PROVIDER_GONE` 判据位置。
9. 多用户/多租户隔离：系统无认证（见 F9），"隔离"只能是"不存在"，无法作为通过项。

---

## 2. 链路分析（入口 → 身份/session/run → 共享状态/外部调用 → 正常与异常 → 重连/取消/恢复 → 用户可见结果）

前提（按任务要求）：后端在 **Redis Cluster + 共享 DB + ≥3 JVM、同一对话请求无粘滞** 下成立；前端覆盖**多页签并行**与
（若存在）**多用户隔离**。以下每条链都标出该前提下成立与否。

### 链 1｜确认门（confirm）从请求到执行

入口：`POST /api/ai/confirm`（`AiController.java:524`）→ 必填校验 → `reason` ≤1024 → `touchActiveRun`（读侧续命）→
`pendingLenient` 预读做 kind 校验（`AiController.java:540-548`）→ `ConfirmGate.submitDecision`（`:1103`）。

- **身份/session/run**：无用户身份（只有 runId+toolCallId）；session 由 `runId→snapshot.sessionId` 反查，仅用于续期索引与日志。
- **共享状态**：`ai:decided:<run>` 的 `HSETNX` 抢唯一写者（`ConfirmGate.java:1150` → `RunStore.java:2408`）→ 复判原始条目
  （`pendingForWrite` → `pendingRaw`）→ 改写条目 → `submitLock` 内 **落库 → 发 `confirm_decision` → 唤醒**（`:1186-1190`）。
- **正常**：ACCEPTED，`woke` 视本实例是否有闸门；之后执行在**等待方线程**（非 park）或 **ResumeService 重建**（park）。
- **异常**：`NOT_FOUND`/`TOOL_KIND_MISMATCH`/`DUPLICATE` 分别 409；守卫写失败抛 `AiStateUnavailableException`（503 语义，由
  `AiControllerConfirmStateUnavailableTest` 覆盖）。
- **取消/恢复**：见链 5/6。
- **用户可见**：`200 {accepted,status,wokeInProcessGate,resumeTriggered,resumeOutcome}`。**注意**：`wokeInProcessGate=true`
  只表示"某把闸门被 countDown"，**不表示**一定有线程会消费它（见 F1）。

判定：**单写者与"帧恰一次"在代码上成立（带条件：`submitLock` 只在实例内有效，跨实例唯一性由 `HSETNX` 提供；
条目写回本身不是 CAS，后写覆盖先写的问题见 F3）**。

### 链 2｜前端工具请求/回灌（frontend_tool_request / frontend-tool-result）

入口 A：生成侧 `SpToolCallingManager.awaitFrontend`（`SpToolCallingManager.java:1262`）与 park 前置整批公布
`ConfirmGate.publishRequestsForParkedBatch`（`ConfirmGate.java:471`）。
入口 B：`POST /api/ai/frontend-tool-result`（`AiController.java:590`）→ 体积/结构判定（截断 vs 400）→ `submitFrontendResult`
（`:1395/1407/1419`）。

- **两道闸**：下发前 `inspectArguments`（在 `saveSuspended`/公布帧**之前**，`SpToolCallingManager.java:427-434`）；回灌前
  `inspectResult`（在换状态**之前**，`ConfirmGate.java:1465`），不合法 → `REJECTED`（400），条目仍 PENDING 可重试。
- **取消（cancelled=true）**：`dismiss`（`:1536`）→ `FRONTEND_CANCELLED` + 结局帧 + 唤醒 + 可能续跑；**先抢 guard**，两页签同时关
  表单只落一次。
- **共享状态**：`ai:pending` + `ai:req`（独立 HASH，`HSETNX`，首写者胜）+ `ai:decided` + `ai:claim` + `ai:ledger`/`ai:idem`。
- **用户可见**：帧 `frontend_tool_result(ok,status,code)`；409 时前端 `markGateSyncing`+3s 后拉 `runs/current` 落权威裁决。

判定：**两道闸与"拒绝不消费挂起"可证明正确（带条件：kind 为 null 的旧数据放行 + WARN）**。
**超时/取消路径的写回不经 guard（F3）**。

### 链 3｜park（人工等待不驻留线程）

`SpToolCallingManager.executeToolCalls` → 预检/入参闸 → `saveSuspended`（快照 SUSPENDED + 全部 pendings 外置 + assistant text/
metadata + 历史）→ `out.suspended(...)` → （park）逐张 `tool_start` → `publishRequestsForParkedBatch`（置 `req` + 发请求帧）→
执行循环到该卡 → `ConfirmGate.await`：`reserveRequestSlot` → 抢等待租约 → 登记 latch → ±发帧 → `completeSubscriptions()` →
**park 分支**（取消优先 → 日志 → 即时前端工具排到期 → `releaseWaitingLease` → `throw RunParkedException`）→
`SpToolCallingManager` 捕获后记录 `tool_call_park` 并原样穿出 → `finally gate.disarm` → `ResilientChatService.runAttempts`
识别 `RunParkedException` → `parkTurn`（什么都不写）→ 返回 → `AiController` 任务 finally：`sessionGate.release` +
`runRegistry.close(runId)`。

- **共享状态**：合法恢复态全在 Redis；`suspendMs/waitedMs` 只是本实例时钟。
- **异常**：`saveSuspended` 抛错 → 异常穿出（不 park、不执行），由上层按失败收尾；`reserveRequestSlot` 读失败 → 非抛读口降级。
- **用户可见**：SSE 在请求帧之后被服务端收流，前端据 `awaitsInput` 判"正常挂起收尾"（`api/sse.ts:294-297,347`）。

判定：**"线程交还 + 状态外置"可证明正确（带条件：`RunParkedException` 必须原样穿过官方循环——未验证，见 §4）**；
**park 退出与决策/取消的竞争存在缺陷（F1）**。

### 链 4｜多卡与混合 backend 工具

一批 `[backend_a, confirm_b, backend_c]`：`backend_a` 执行；`b` 的 await park → **本次执行结束**，`backend_c` 不执行
（其 `tool_start` 已在 park 前公告，属刻意语义）；续跑时 `ResumeService` 按快照 in-flight 顺序逐条 `resolvePending`：
`a` 已 EXECUTED → 复用；`b` APPROVED → 补执行一次；`c` PENDING+BACKEND → 执行。
多卡同时挂起：`publishRequestsForParkedBatch` 一次公布整批（修 C1 的"第二张卡永远无请求帧"）。

判定：**可证明正确（带条件）**：不重不漏依赖"快照 in-flight 顺序 + `claim` 原子 + 台账重放"三重，
且前提是**同一"批"的 in-flight 不会被第二次 `saveSuspended` 覆盖**（续跑期间若模型又发新工具调用，会写新的 in-flight，
此时旧批已结算完毕——顺序上成立）。**缺陷**：批内"已结算未发帧"的条目在 park 后永远没有帧（F6）；
步数到顶/首错即停时"已公告的卡被 skip"（F8）。

### 链 5｜取消（stop）在六个位置的行为

`POST /api/ai/cancel/{runId}`（`AiController.java:855`）→ 写 `ai:cancel:<runId>`（Redis 权威，TTL=session-ttl）→ 同进程唤醒
latch/看门狗 → 若 park 且快照 SUSPENDED → `triggerResumeForParked`（`:873-878`）。

取消被检查的位置：`SpToolCallingManager` 事件边界（`:515`，执行前；`:607`，执行后）、`ConfirmGate.await` 等待循环
（`:639-641`，2s 节拍 + 本地加速）、`ConfirmGate` park 分支（`:602`）、`ResumeService` 准入（`:681-698`，**仅一次**）、
`StreamWatchdog` 每 tick。

判定：**"取消作为整轮终止"可证明正确**；**"取消之后不再有新副作用"不成立**（F2）：`awaitConfirm`→`executeOnce` 与
`resolvePending`→`executeOnce` 之间没有取消复查，而 `ResumeService` 的复查与执行之间隔着 SYSTEM 恢复、记忆窗口、L2 压缩。

### 链 6｜续跑（ResumeService）单写者与幂等

入口：启动扫描（`StartupResumeRunner`）、运维 `POST /runs/{id}/resume`、`ConfirmGate` 死卡兜底、`/cancel` 的 parked 兜底。
准入顺序（`ResumeService.resumeInternal:613-802`）：租约归属校验（Redis 真值 `verifyHeld`）→ 进程内 `resuming` → 快照存在 →
`pendings` 强一致读（失败 503）→ 取消三态（UNKNOWN → 503）→ 终态 → RUNNING（僵尸接管）→ PROVIDER_GONE →
**外部 PENDING → 409 PENDING_UNRESOLVED** → 重新 acquire 会话门（token 一次性）→ `runLeaseAuthority` 只读拒绝 →
SYSTEM 恢复 → in-flight 重建/补执行 → 记忆窗口 → 压缩 → 交棒 `aiRunExecutor` 驱动。

- **幂等**：`ALREADY_DONE/ALREADY_CANCELLED/ALREADY_RUNNING/PENDING_UNRESOLVED` 都不驱动；
  工具级"恰好一次"= `tryClaimExecution` 的 `HSETNX`（执行**前**）+ 台账重放 + fence 复核（`:1688-1760`）。
- **单写者**：跨实例 = `ai:lease:run:{run}`（三键同槽 Lua 取号 + fence 对账 + 可重入 `created`）；同进程 = `resuming` 表；
  写面 = `RunStore.save` 的 fence 写 + `runLeaseAuthority` 只读拒绝。
- **租约归还白名单**（`Outcome.releasesLease()`，`:279-285`）默认不还，避免"创建者输掉互斥后删掉胜方的租约"（L-19）。

判定：**"同一轮同一时刻只有一个驱动者"在代码上可证明（带条件：Redis 可用；设施不可用时降级为进程内互斥，
此时多实例可能双驱动，唯一兜底是 `claim`）**。**`putPending` 不受 fence 约束**，是本链路唯一的写面裂缝（见 F3 根因）。

### 链 7｜"同一会话不同 JVM"

`/chat`：`sessionGate.acquire(sessionId, runId)`（单键 Lua，token=runId）；僵尸轮放行 `isDeadRound`（租约/活连接/业务活动三路）。
park 释放会话门 → 续跑方以一次性 token 重新 acquire（`ResumeService:772-786`）→ 交棒后由驱动任务按同 token 释放；
首轮迟到 `finally` 用 token=runId 释放不会误删（I2）。

判定：**可证明正确（带条件）**：同会话并发两轮被 409 挡住；但**"用户 park 期间又发一轮"→ 旧轮的决策到达时 `SESSION_BUSY`，
由有界重派发兜底（2s 起指数退避 ×15 ≈ 360s）**——若新一轮超过 360s，旧轮决策会停在"已决 + 无人执行"（有 ERROR 日志，
下一次交互/启动扫描可再触发）。属可接受的设计上限，但**用户可见**："我批准了，很久没反应"。

### 链 8｜重连（reattach）与帧序

`GET /events/{runId}?lastSeq=N`（`AiController.java:740`）→ `touchActiveRun` → `lastSeqIfKnown` 不可知则 503 →
`replayAndAttach`（锁内"收集回放 + 入队 + 挂订阅"）→ park 且 SUSPENDED 且**仍有 PENDING** → `completeSubscriptions`（收流）。

帧序契约（`ParkToolStartOrderConformanceTest`/`ToolFrameSequenceConformanceTest`）：`suspended → tool_start(每卡恰一条) →
confirm_request/frontend_tool_request`；提交侧固定"落库 → 回执帧 → 唤醒"（`ConfirmGate.java:72-74,1184-1190`）。

判定：**可证明正确（带条件：`SseChatEmitter` 的 seq/归档与唤醒总线工作正常）**。**缺陷见 F1（决定已落库但无人驱动时，
重连会停在"输入禁用 + 无卡/卡已决"的死胡同）**。

### 链 9｜预检与预算

预检（F5）：只对 `start_export/precheck/import/publish` + `matchesContext` 为真者执行，在 `saveSuspended` **之前**；
不通过 → `PRECHECK_FAILED`（retryable，不进确认门、不弹卡）；预检自身抛错 → `PRECHECK_UNAVAILABLE`（安全侧不放行）。
预检报告随条目外置并随帧下发；`reportHash` 只作审计绑定，**不参与执行判定**（执行时业务守卫照旧复核）。
预算：步数笼读**快照**（跨进程成立）；轮内上下文预算每批评估，达收尾线追加指令并 `armBudget`（**进程内**），
armed 后工具一律 skip；`MAX_ARMED_BATCHES` 有上界。

判定：预检**可证明正确（带条件：业务判定属 E 域）**；预算的**步数**跨进程成立，**armed 标记不跨进程、且在 park 时被
`beacon.clear`** → 上界只在单实例单段内有效（F7）。

### 链 10｜业务工具调用边界（通用编排）

`executeOnce` 的固定顺序（`SpToolCallingManager.java:1372-1409`）：入口 kind 硬闸 → 台账重放 → 目标校验 → 资源互斥 →
原子认领（三态 WON/LOST/STALE_FENCE）→ 执行（独立超时线程池）→ 台账 + `tool_result` 帧。
失败分类：`ConflictException/ResourceNotFoundException` 取码，否则 `TOOL_EXECUTION_FAILED`；执行中抛错追加对账措辞；
超时 → `timeout/TOOL_TIMEOUT` + `executed=false` + **不写台账**。`haltsBatch` 只认 W/P/J 的确定性失败。

判定：**可证明正确（带条件）**：`cancel(true)` 只能"请求中断"，不响应中断的桩体会继续跑完（注释已承认）；
线程池饱和退化为同步无超时；资源锁 TTL 60s 不续租，超过上界的执行会让锁先失效（注释已承认）。

---

## 3. 测试可信度

### 3.1 底座
- 后端本域**没有一条 `@SpringBootTest`**（grep 确认：`gate/`、`run/`、`conformance/` 下零命中），也没有 Testcontainers/嵌入式 Redis。
  底座只有两种：`Mockito.mock(...)` 与 `conformance/InMemoryRunStore`（`extends RunStore`，用 `ConcurrentHashMap` 覆写全部方法）。
- `InMemoryRunStore` 默认 `deepCopyPending=false`（`InMemoryRunStore.java:129-143`）：`putPending` 存的是**同一个对象引用**，
  `pending()` 返回同一对象。⇒ **"拿旧副本整条写回覆盖新状态"这一类缺陷在该底座上结构性不可见**；
  只有显式 `new InMemoryRunStore(true)`（如 `ConfirmGateDecisionAtomicTest`）才部分规避。这正是 F3 没有任何用例能发现的原因。
- `FakeLeaseRedis` 按脚本正文里的注释标记（`-- LEASE_ACQUIRE` 等）分派并在 Java 侧复刻语义 ⇒ 断言的是"复刻版语义"，
  真实 Lua 的正确性（含 `|0` 可重入、`epoch<=fence` 自愈）**只有人工评审**。
- 前端：vitest + jsdom，`vi.mock('@/api/ai')` 全量替换；SSE 帧由测试直接调用 `handlers.onFrame`。证明的是 store 归约逻辑，
  **不是**与真实后端的契约（但帧形状由 `types/` + 后端 `FrameTypeSetFrozenTest` 双向锁定，属"形状可信"）。

### 3.2 各层"证明了什么"
| 层 | 代表用例 | 真正证明 | 不能证明 |
|---|---|---|---|
| 状态机/协议 | `ConfirmGate*Test`（13）、`HumanWaitParkTest`、`ParkedFrontendExpiryTest` | 分支与帧序、`woke`/`resumeOutcome` 取值、`RunParkedException` 是控制流 | 跨 JVM 竞争、真实 Redis 时序、真实模型循环 |
| 幂等/认领 | `ToolIdempotencyTest`、`RunStoreClaimFenceTest`、`ToolUnknownReconcilerTest` | 台账重放/二次对账/STALE_FENCE 文案 | `claim` 在真实 Redis 的原子性（HSETNX 属 Redis 保证，但仓内无脚本级证据） |
| 续跑 | `MultiCardParkResumeTest`、`ResumeServiceLeaseTest`、`ResumeSkipCascadeTest`、`ResumeEnvelopeShapeTest` | 准入分支、派发次数、重建信封形状、会话门 token 归属 | **`SpToolCallingManager` 在 `MultiCardParkResumeTest` 里是 mock** ⇒ "每张卡各执行一次"只是"派发了两次 resolvePending" |
| 帧契约 | `ParkToolStartOrderConformanceTest`、`ToolFrameSequenceConformanceTest`、`ChatTurnFrameSequenceConformanceTest` | seq 单调、类型集合冻结、`tool_start` 先于 request | 真实 emitter 投递/跨实例唤醒 |
| web 层 | `AiControllerParkWiringTest`、`AiControllerEventsParkCloseTest`、`AiControllerToolKindEntryTest` | 端点分支与 HTTP 码 | 真实容器/真实 Redis（`InMemoryRunStore` + mock 依赖） |
| 前端 | `gate-matrix`、`restore-*`、`b1-suspend-disconnect`、`concurrency-matrix`、`park-request-tool-start-order` | 段 first-wins、恢复判据（req/kind/已决）、重连去重、`tool_start` 不回退等动作卡 | 真实 SSE、真实多页签（Web Locks 在 jsdom 不存在，走 'unsupported' 分支） |

### 3.3 是否存在"假绿 / 时序水分 / 测试自造答案"
- **把缺陷窗口固化为期望**：`ConfirmGateDeadlockResumeTest#armedGateBeforeAwaitPreventsDeadCardResume`（`:330-346`）断言
  "arm 之后、await 之前提交决策 ⇒ `woke=true` 且**不触发续跑**"。在非 park 语义下这是对的（等待者会被唤醒）；在 park 语义下
  同一断言正是 F1 的缺陷形态——该用例把"没有消费者也会被算作已唤醒"这件事钉成了契约。
- **只断言 mock 调用**：`MultiCardParkResumeTest` 全部断言 `verify(resolvePending, times(2))` + 历史形状；`ResumeDispatchRetryTest`
  断言 `resumeService.resume(...)` 调用次数。它们证明"编排调用了几次"，**不证明"副作用发生了几次"**。
- **测试自己造的答案**：`ParkedFrontendExpiryTest` 用注入的 `RetryScheduler` 直接执行到期任务（等价于"手动把定时器拨到点"），
  无法证明"生产调度器真的在 120s 后跑"（进程死了就没有下一次，见 F5）。
- **时序水分**：`HumanWaitParkTest` 用真实 `ThreadPoolTaskExecutor`（pool=2）+ future 等待，属较扎实的一档；但它把
  `ResumeService` 换成 mock，因此"决策后由续跑重建执行"只是"派发了"。
- **无跳过/无 @Disabled**：本域未见 `@Disabled`/`assumeTrue` 式跳过，也未见只断言 `status==200` 的"存在性测试"
  （`AiController*Test` 都断言 body 字段）。
- 结论：**没有任何一条测试能替代真实拓扑验收**；本域的正确性主张目前建立在"代码评审 + 单 JVM 契约测试"上。

---

## 4. 发现清单

严重度：Critical / Important / Minor。确定性三档：**已证实缺陷**（代码路径可证，无需运行）/ **具体风险**（需特定崩溃或时序，
代码可证缺口）/ **仅缺测试**（行为无法证伪也无证据）。

---

### F1｜决定撞上 park 退出：`woke=true` 但没有任何线程会消费它 → 批准被静默丢弃、轮次挂死

- **severity**：Important　**确定性**：已证实缺陷（触发依赖时序，未在真实拓扑复现）
- **path:line**
  - `ai/gate/ConfirmGate.java:558`（park 分支**之前**就 `computeIfAbsent` 登记 latch）
  - `ai/gate/ConfirmGate.java:588`（发完请求帧即 `completeSubscriptions`）
  - `ai/gate/ConfirmGate.java:601-622`（park 分支：**不消费 latch**，直接 `throw RunParkedException`）
  - `ai/gate/ConfirmGate.java:714`（`finally` 摘 latch）属于**另一个** `try`，park 分支在其之前抛出，故不执行
  - `ai/gate/SpToolCallingManager.java:713-715`（`finally { gate.disarm(...) }` 才摘掉 latch）
  - `ai/gate/ConfirmGate.java:1596-1613`（`triggerResume`：`woke=true` → `WOKE_IN_PROCESS_GATE`，**不派发**）
  - `ai/gate/ConfirmGate.java:1890-1897`（`signal` 只判"latch 存在"，不判"有人会消费"）
- **触发步骤（park=on，生产默认）**
  1. 模型发出确认卡/前端卡 → `saveSuspended` → `suspended` 帧 → `tool_start` → `publishRequestsForParkedBatch`
     （置 `ai:req` + 发 `confirm_request`/`frontend_tool_request`）。
  2. 浏览器收到请求帧；**服务端尚未走到 park 抛出**（窗口 = `reserveRequestSlot` → 抢租 Lua → `completeSubscriptions`
     的 drain → park 分支 → 抛异常穿出官方循环 → `disarm`；含一次 Redis DEL 与一次 emitter 收尾）。
  3. 该窗口内**落在同一实例**的决策到达（人工快速点击；更现实的是**自动回灌**：`runFrontendTool` 收到帧立刻 POST、
     `generative_form` schema 非法自动 `cancelled=true`、上传交接失败自动取消——这些都在收到帧后毫秒级发出；
     localhost/内网 RTT 可落在窗口内）。
  4. `submitDecision`：guard 抢到 → 落库 APPROVED → 发 `confirm_decision` 帧 → `signal` 命中**预登记的 latch** →
     `woke=true` → `maybeTriggerResume` 返回 `WOKE_IN_PROCESS_GATE`，**不派发续跑**。
  5. park 线程继续走到 `throw`，`disarm` 摘掉 latch，本轮结束——**没有任何线程执行该工具，也没有任何续跑被派出**。
- **用户影响**
  - HTTP 200 `{accepted:true, status:APPROVED, wokeInProcessGate:true, resumeOutcome:WOKE_IN_PROCESS_GATE}`。
  - 卡显示"已批准"，但 `tool_result` 永不出现；快照停在 `SUSPENDED`；前端无倒计时（去超时裁决），故**没有任何本地超时**。
  - 再点一次 → `409 DUPLICATE`（`ConfirmGate.java:1146-1148` 在 `maybeTriggerResume` 之前返回，**也不会派发**）。
  - 刷新后：`/runs/current` 的 `awaitingExternal=false`（条目非 PENDING，`AiController.java:1021-1028`）→ 前端走
    `restoreInFlightRound`（`stores/ai.ts:2097-2102`）→ `lastSeq=0` 全量重放 + 挂流，**永远等不到 done**，`loading=true`
    使输入框静默不接受发送（`stores/ai.ts:2487-2492`）。
  - 唯一出口：用户点"停止"（取消 → parked 兜底续跑 → 收敛为 cancelled，**批准被丢弃**）、运维 `POST /runs/{id}/resume`、
    或等下一次外部输入/重启扫描。
- **对应测试**：**缺失**。`ConfirmGateDeadlockResumeTest#armedGateBeforeAwaitPreventsDeadCardResume`（`:330-346`）把该窗口
  判据固化为"正确"；`HumanWaitParkTest#stopAfterParkTriggersResumeSoCancellationConverges` 只在 park **完全结束并 disarm 之后**
  才提交，绕过了窗口。
- **备注（同族但更窄）**：`hasActiveLatch` 前缀判据（`:1876-1887`）在同一窗口内为真，也会让
  `triggerResumeForParked`（`/cancel`）返回 `ACTIVE_LATCH_IN_PROCESS` 而**不派发**——即"停止"在该窗口内也不收敛。

---

### F2｜取消之后仍有残留执行：`awaitConfirm`/`resolvePending` → `executeOnce` 之间没有取消复查

- **severity**：Important（对 P 类不可逆写是实质危害）　**确定性**：已证实缺陷
- **path:line**
  - `ai/gate/SpToolCallingManager.java:515`、`:607`（**唯一**两处取消检查，均在执行循环的事件边界）
  - `ai/gate/SpToolCallingManager.java:1320-1328`（`awaitConfirm`：`awaitDecision` 返回 APPROVED 后**直接** `executeOnce`）
  - `ai/gate/SpToolCallingManager.java:1816-1831`（`resolvePending`：续跑路径**没有** `checkCancelled`）
  - `ai/run/ResumeService.java:681-698`（取消检查**只做一次**，位置在准入）→ 之后 `restoreSystem`、`MemoryWindow.plan`、
    `ContextCompactor.compact`（L2 可多次串行摘要、每次最长 60s，`:906-943`）→ 才 `resolvePending`/`executeOnce`
- **触发步骤**
  - 路径 A（首轮/非 park）：用户批准 → 决策唤醒等待线程 → （同一毫秒内）用户点停止/另一个页签取消 → 等待线程醒来后
    `awaitConfirm` 直接执行已放行的工具，`cancelState` 只在下一次事件边界（`:607`）才被看到。
  - 路径 B（park，窗口大得多）：`/cancel` 写入取消标志；同时/随后决策派发续跑 → `ResumeService` 已在 `:681` 读到
    `NOT_CANCELLED` → 重建历史 + 压缩（数十秒）→ 期间用户停止 → 续跑继续 `resolvePending` → `executeOnce` **执行**
    `start_import`/`start_publish` 等不可逆动作并写台账。
  - 反证：`/cancel` 触发的 `triggerResumeForParked` 此时返回 `IN_PROGRESS`（`ResumeService:632-635`），不会打断在跑的续跑。
- **用户影响**：用户已看到"已停止"，但发布/导入等不可逆动作仍被执行；台账/帧显示成功；与 ADR-8"取消即整轮终止"的承诺不符。
- **对应测试**：`SpToolCallingManagerBatchStopTest`、`CancelDecisionFramesTest` 只覆盖"事件边界取消"（执行前/执行后），
  **不含**"决定已落库、执行尚未开始"的窗口；`ResumeService` 侧无取消后执行的用例。

---

### F3｜超时/取消的写回不经 guard、不是 CAS：整条覆盖并发决策/回灌（丢用户输入 + 双结局帧 + guard 与条目分叉）

- **severity**：Important　**确定性**：已证实缺陷
- **path:line**
  - `ai/gate/ConfirmGate.java:944-963`（`expireFrontend`：`pendingLenient` → 判 PENDING → `setStatus(TIMEOUT)` → `putPending`
    → **无条件**发 `frontend_tool_result`；不抢 guard、不复核条目是否刚被决）
  - `ai/gate/ConfirmGate.java:905-931`（`cancel`：同样"读-判-写"；且**条目已非 PENDING 时仍然发 `confirm_decision(cancel)` 帧**）
  - `ai/gate/ConfirmGate.java:1307-1312` + `ai/run/RunStore.java:1557-1560`（读侧只能合并 REJECTED/FRONTEND_CANCELLED/CANCELLED
    三种 guard，APPROVED/FRONTEND_RESULT 不合并）
  - `ai/run/RunStore.java:1320-1328`（`putPending` 是整条 HASH 覆盖，**不受 fence/authority 约束**——与快照写、认领、台账不同源）
- **触发步骤**
  1. 即时类前端工具（`navigate_to` 等，`suspendWaitSeconds` 非 null）park 后由 `scheduleParkedFrontendExpiry` 在 120s 到期；
     同一瞬间浏览器回灌到达：`submitFrontendResult` 抢到 guard=FRONTEND_RESULT、写 FRONTEND_RESULT、发 `ok=true` 帧；
     `expireFrontend` 的"读 PENDING"发生在之前 → 用旧副本覆盖成 `TIMEOUT/FRONTEND_TIMEOUT/executed=false` → 再发 `ok=false` 帧。
     结果：用户看到"成功→超时失败"两帧、条目为 TIMEOUT；guard 仍是 FRONTEND_RESULT（状态与 guard 分叉，后续 `decisionOf` 与条目互相打脸）。
  2. 表单值/表格结果（用户输入）**丢失**，模型在续跑里读到"结局未知"，可能重做一次写操作。
  3. `cancel()` 与 `submitDecision` 同时：`cancel` 读到 PENDING → 决策写入 APPROVED + 发 approve 帧 → `cancel` 写 CANCELLED + 发 cancel 帧；
     或反之。两张结局帧、条目与 guard 分叉；若最终落 CANCELLED，用户刚批准的卡被"取消"。
- **用户影响**：数据/输入丢失；同一卡两条互相矛盾的结局帧；卡片显示与后端真相不一致；审计台账（`ai:safety`/`ai:ledger`）与条目不同源。
- **对应测试**：`ParkedFrontendExpiryTest`（只测"到期 → TIMEOUT + 派发一次"）、`ConfirmGateFrontendTimeoutTextTest`（只测文案）、
  `CancelDecisionFramesTest`（只测"未决条目发 cancel 帧、已结算条目不发"——恰好**没有**覆盖"已决但仍在等待写回"的竞争）。
- **根因**：本域存在两套写入口径——"决策/回灌"走 `ai:decided` 唯一写者 + `submitLock`；"超时/取消"是**裸写**。
  只要两套入口可能对同一 `toolCallId` 并发，就必须让裸写也至少"读-判-CAS（HINCR/HSETNX on status）"或先抢同一把 guard。

---

### F4｜决策 guard 的 WAL 只覆盖 3 个可重建状态：`APPROVED`/`FRONTEND_RESULT` 落在"guard 已写、条目未写"之间 → 永久死卡

- **severity**：Important　**确定性**：具体风险（需要进程在毫秒窗口内死亡/超时，代码路径可证）
- **path:line**
  - `ai/gate/ConfirmGate.java:1150`（先 `tryRecordDecision` 写 guard）→ `:1186-1190`（后在 `submitLock` 内 `putPending`）
  - `ai/gate/ConfirmGate.java:1296-1342`（`convergeOnDecided`：若条目仍 PENDING 且 guard 状态**不可重建**，**直接返回 DUPLICATE**，
    既不补写也不派发续跑）
  - `ai/run/RunStore.java:1517-1527` + `:1557-1560`（读侧只合并 3 种）
- **触发步骤**
  1. 实例 A 的 HTTP 线程：`HSETNX ai:decided` 成功（值 `APPROVED|<ts>|A`）→ 进程被 kill / 容器被驱逐 / 线程池强制关闭 /
     Redis 单命令超时（`putPending` 抛 `AiStateUnavailableException`）→ 条目仍是 `PENDING`。
  2. 用户重试、另一实例提交、或前端 409 后重发：`tryRecordDecision` 失败 → `convergeOnDecided` → `APPROVED` 不可合并 →
     `DUPLICATE`、**不派发**。
  3. `ResumeService`：`APPROVED` 不是 PENDING，但条目是 PENDING → `unresolvedExternal` 非空 → `409 PENDING_UNRESOLVED`。
     启动扫描同样 skip（`StartupResumeRunner.java:139-148`）。`/runs/current` 仍显示 `PENDING`（读侧不合并 APPROVED）。
  4. **无任何出口**：不能批准（409）、不能拒绝（409）、不能续跑（409）、不能超时（确认门无上限）。只有整轮取消能收场。
  5. 同型：`FRONTEND_RESULT`（用户填的表单值已丢失）——重提也是 `DUPLICATE`，值再也补不回来。
- **用户影响**：一张永远点不动的卡 + 一轮永远不结束的对话；需要用户自己"停止"整轮。
- **对应测试**：**缺失**。`RunStoreWalConvergeTest`/`ConfirmGateDecisionAtomicTest` 只覆盖 REJECTED/CANCELLED 两种可合并结论
  （它们本来就能自愈）。
- **建议方向（仅指明缺口，不越界改代码）**：guard 里已带 `atMs`，可把"不可重建"的结论在**读侧只作"已决但缺载荷"标记**并允许
  重新提交/重新批准一次；或让 `convergeOnDecided` 在"guard 有值 + 条目 PENDING + 距 guard 时刻超过阈值"时回写裸状态并派发续跑。

---

### F5｜park 后的前端工具到期收敛是"进程内一次性任务"，不持久：实例死亡后该卡永不收敛

- **severity**：Important　**确定性**：具体风险（需要实例在 120s 内退出，含优雅停机）
- **path:line**
  - `ai/gate/ConfirmGate.java:617-619` + `:978-1011`（`scheduleParkedFrontendExpiry` 用 `DEFAULT_RETRY_SCHEDULER`，
    即 `Executors.newSingleThreadScheduledExecutor` 守护线程，`:258-269`；进程退出即丢失，无持久化、无重启重排）
  - `ai/gate/ConfirmGate.java:374-382`（到期点只由 `suspendWaitSeconds` 决定，条目上虽有 `expiresAt`，但**没有任何扫描器**读它）
  - `ai/run/StartupResumeRunner.java:139-148`（`unresolvedExternal` 非空 ⇒ skip，不重建等待/不重排到期）
  - `ai/run/ResumeService.java:757-766`（`/runs/{id}/resume` 同样 `PENDING_UNRESOLVED`）
- **触发步骤**：`navigate_to` 一类即时前端工具请求帧已发出 → 实例崩溃/滚动重启/优雅停机（`ShutdownDrainGate` 不覆盖该调度器）
  → 另一实例起来：快照 `SUSPENDED`、条目 `PENDING`（`req=true`）→ 启动扫描 skip、手工续跑 409 → 用户不刷新页面就永远没有结局。
- **用户影响**：该轮"输入禁用 + 等待中"直到 Redis TTL（=session-ttl，24h）到期；用户只能刷新页面（重放
  `frontend_tool_request` 让浏览器重新执行一次）或停止。
- **对应测试**：`ParkedFrontendExpiryTest` 注入自己的 `RetryScheduler` 并**同步执行**任务，证明的是"到期逻辑正确"，
  **不证明**"到期一定有人来跑"。
- **备注**：确认门/generative_form/表格写/上传交接无上限是用户裁决，本条只针对"有时间上限的即时前端工具"。

---

### F6｜park 之后，同批"已结算但未发帧"的条目永远不会有 `tool_start`/`tool_result`

- **severity**：Minor　**确定性**：已证实缺陷
- **path:line**：`ai/gate/SpToolCallingManager.java:528-548`（`frontendRejected`/`preflightBlocked` 的帧**只**在执行循环里发）、
  `:490-494`（park 前只对本批 `suspended` 列表预发 `tool_start`，被拒条目不在其中）、
  `ai/run/ResumeService.java:842` + `SpToolCallingManager.java:1839-1842`（续跑对已终态条目**只复用 resultText，不发帧**）
- **触发步骤**：一批 `[confirm_a, start_import_b(预检未通过)]`；park 发生在 `a` → `b` 的 `tool_start`/`tool_result` 从未发出 →
  续跑只把 `b` 的失败文本回填给模型。
- **用户影响**：前端缺一张"预检未通过"的工具卡；用户只看到模型口头解释，无法在卡上看到原因/重试入口（首次非 park 路径有卡，
  park 路径没有）。属可见性不一致，不影响执行正确性。
- **对应测试**：无。

---

### F7｜预算 armed 只在本进程与本段有效：park 后 `MAX_ARMED_BATCHES` 上界与"不再产生副作用"的硬约束失效

- **severity**：Minor　**确定性**：具体风险（需要 park/换实例）
- **path:line**：`ai/gate/SpToolCallingManager.java:380-383`（`budgetArmed(runId)` 读进程内信标）、`:900-909`（`armBudget`）、
  `ai/run/ToolActivityBeacon.java:242-276`（全为 `ConcurrentHashMap`，`:266-276` `clear` 清空）、
  `ai/run/ResilientChatService.java:473-477`（**park/失败/正常结束都会 `beacon.clear(runId)`**）
- **触发步骤**：轮内预算达收尾线 → 追加 `TURN_BUDGET_WRAP_UP` 并 arm → 该批里有确认卡 → park → `beacon.clear` →
  续跑（可能另一个实例）`budgetArmed=false` → 模型再要工具时**照常执行**（`MAX_ARMED_BATCHES` 计数也归零）。
- **用户影响**：软/硬约束都在"跨 park/跨实例"处退化；context 再次逼近窗口时依赖下一批的 `applyTurnBudget` 重新判定（会重新 arm），
  因此**通常自愈**，但"armed 之后不再产生新副作用"的承诺在跨实例场景不成立；用户可能看到 park 之后又冒出一批新的写操作。
- **对应测试**：`SpToolCallingManagerTurnBudgetTest` 全在单实例单段内。

---

### F8｜"已公告的卡"可能注定不执行：步数到顶 / 首错即停时，请求帧已公布但条目被 skip

- **severity**：Minor　**确定性**：已证实缺陷
- **path:line**：`ai/gate/SpToolCallingManager.java:495-500`（**整批**先公布请求帧）→ `:540-548`（预检未通过 skip）、
  `:550-557`（`stepsUsed >= maxToolSteps` → `skipAsStepLimit`）、`:506-512`（`haltedBy` → `skipAsUpstreamFailed`）
- **触发步骤**：`stepsUsed` 已到上限（跨轮累计，`persistToolSteps`）；模型仍发一张确认卡 → `confirm_request` 已发出、卡已渲染 →
  循环把它结算成 `SKIPPED` 并补 `tool_result(skipped)`。用户若在 skipped 帧到达前点击批准 → `submitDecision` 看到非 PENDING →
  `409 DUPLICATE` → 前端 `markGateSyncing` + `armGateSync`（`stores/ai.ts:3120-3128`）→ 3s 后拉 `runs/current`，
  `gateReceiptOfStatus('SKIPPED')` 无对应回执 → `ai.notice.decisionPending`（"决策已提交，正在同步"）**永远不收敛**。
  同型：批内首错即停后，后续已公布的卡（`:506-512`）。
- **用户影响**：闪一张卡、点下去得到"正在同步"的假瞬态；需要刷新才能看到 `skipped` 结局。
- **对应测试**：`SpToolCallingManagerStepLimitTest` 只断言 skipped 结算与帧类型序列，不覆盖"用户点击"的交互后果。

---

### F9｜AI 边界无身份/无授权：任何能访问该端口的人都能枚举全部轮次、读历史、批准/拒绝任意挂起卡

- **severity**：Important（作为部署前提已声明，但它同时决定了"多用户隔离"这一验收项**无法通过**）
- **确定性**：已证实（架构事实）
- **path:line**：`ai/web/AiController.java:524`（`/confirm`）、`:590`（`/frontend-tool-result`）、`:855`（`/cancel`）、
  `:934`（`/runs` → `ResumeService.listRuns` 遍历 `ai:runs` 全部 runId）、`:943`（`/runs/{runId}` 返回 `snapshot`+完整
  `messageJson` 角色序列+`pending`+`ledger`）；全仓无 Spring Security（`grep SecurityFilterChain` 零命中），
  仅 `SecurityHeadersFilter`；`AiEndpointGuard` 类注自述"本项目是单用户本地应用、配置面本身已是管理员权限"。
- **触发步骤**：同一网络中另一使用者（或多用户部署下的另一个用户）→ `GET /api/ai/runs` 拿到所有 runId →
  `GET /api/ai/runs/{runId}` 读到对话历史与工具台账 → `POST /api/ai/confirm` 用 runId+toolCallId 批准一张 `start_publish`。
- **用户影响**：审批（HITL）在这个边界上**没有"谁批准的"语义**（`traceDecision` 只能记 `actor=user` 或 `system`，
  `ConfirmRequest` 没有用户字段）；审计留痕无法定位到人；跨用户数据可读。
- **对应测试**：无（不属实现缺陷，属边界声明）。**结论**：任务要求的"多用户隔离"在本快照中**不存在**，应在验收口径里按
  "单用户/单机部署前提"记录，而不是记为"已隔离"。

---

### F10｜`RunRegistry.close(runId)` 无条件收尾本轮 emitter，可能掐掉同实例刚启动的续跑

- **severity**：Minor　**确定性**：具体风险
- **path:line**：`ai/web/AiController.java:470-474`（首轮任务 `finally`：`sessionGate.release` + `runRegistry.close(runId)`）、
  `ai/run/RunRegistry.java:189-194`（`close` = `emitters.remove(runId)` + `emitter.complete()`）、
  `ai/run/ResumeService.java:967-1026`（续跑用 `registry.of(runId)` 取/建 emitter 并交棒）
- **触发步骤**：park 抛出后 → `disarm` → `parkTurn` 返回 → 首轮 `finally` 尚未执行；此窗口内同实例的 `/cancel`（parked 兜底）
  或决策派发成功启动续跑 → 续跑取到**同一个** emitter → 首轮 `finally` 把它 `complete()` 并移出表 → 续跑的实时帧只落归档，
  已连接的客户端提前收到 close。
- **用户影响**：客户端可能把"续跑中"当成"挂起收流"（`awaitsInput=true` 时 `suspendedClose`），静默等待而不再重连 →
  需要刷新才能看到后续。窗口极小，属排障困难的边缘形态。
- **对应测试**：无。

---

### F11（观察项，不计为缺陷）｜`publishRequestsForParkedBatch` 缺少 `reserveRequestSlot` 的 M-1 复判

`ai/gate/ConfirmGate.java:487-501` 在 `markRequestEmitted` 之后**没有**再判 `resolved()`（对比 `:864-882` 有）。
理论上"置位后被决"会先发 `confirm_decision` 再发 `confirm_request`，让前端把已收敛的卡复活。但该窗口要求"另一实例在请求帧
公布之前就已决策该 `toolCallId`"——而 `toolCallId` 此刻才由模型产出，跨实例无从得知，**故实际不可达**。记录在此以说明已检查。

---

## 5. 可证明正确（带条件）清单

以下是本轮**读代码可判定为正确**的机制，均附成立条件；超出条件的部分见第 6 节"未验证"。

| # | 机制 | 证据 | 成立条件 |
|---|---|---|---|
| P1 | 决策"全集群唯一写者 + 回执帧恰一次" | `ConfirmGate.java:1150`（HSETNX）+ `:1186-1190`（同锁内落库→帧→唤醒） | Redis 单键原子性；`putPending` 失败时该卡进 F4 死态 |
| P2 | 工具"恰好执行一次" | `RunStore.tryClaimExecution:1688-1760`（执行前 HSETNX + fence 复核 + 回滚 CAS）、`replayLedger:1686-1706` | 认领键 TTL 内；台账/幂等索引写成功；`STALE_FENCE` 分支不执行 |
| P3 | 跨实例"谁驱动这一轮"唯一 | `LeaseService` 三键同槽 ACQUIRE（`:155-188`）、`ResumeService:613-631` 的 `verifyHeld` **对 Redis 真值**、`RunStore.save` fence 写 + `runLeaseAuthority` 只读拒写 | Redis 可用；设施 `UNAVAILABLE` 时降级为进程内互斥（此时唯一兜底 = P2） |
| P4 | park 不驻留线程、状态全外置 | `ConfirmGate:592-623`、`saveSuspended:1280-1318`、`parkTurn:2003-2008` | `RunParkedException` 原样穿出官方循环（§6-U1） |
| P5 | 多卡 park 后每张卡都有请求帧 | `publishRequestsForParkedBatch:471-504`（逐条"先置 req 再发帧"，同一把锁） | park=on；同批条目在同一 `pendings` 列表 |
| P6 | 帧序 `suspended → tool_start → request` 与"落库→帧→唤醒" | `SpToolCallingManager:490-500` + `emitToolStartOnce:726-730`（每卡恰一条）；`ParkToolStartOrderConformanceTest` | park=on；执行循环不重发 |
| P7 | 前端工具两道闸（下发前/回灌前）"拒绝不消费挂起" | `SpToolCallingManager:427-434`、`ConfirmGate:1465-1471` | 条目 kind 非 null；旧数据放行+WARN |
| P8 | 入口 kind 校验不污染状态 | `ConfirmGate:1270-1283`、`:1143-1148`、`AiController:540-548` | 条目带 kind |
| P9 | 取消的权威态跨实例、续跑 fail-closed | `CancellationRegistry`（Redis 权威 + UNKNOWN 三态）、`ResumeService:681-698`、`:793-802` | Redis 可读；`UNKNOWN` → 503 而非"未取消" |
| P10 | 同会话跨 JVM 单轮 | `SessionGate` 单键 Lua + 一次性 token、`ResumeService:772-786` 与 `:1028-1037` 的 token 释放、`AiController:413-435` | Redis 可用；死轮判定 `RunLiveness` 三路证据 |
| P11 | 续跑历史重建完整（system/user/tool 配对、reasoning metadata 原样过桥） | `ResumeService:804-878`、`rebuildAssistant:1236-1249`、`restoreSystem:1092-1146` | 快照 `messageJson` 未被窗口/压缩破坏（有 `restoreSystem` 兜底） |
| P12 | 业务工具边界：kind 硬闸 → 重放 → 目标校验 → 资源锁 → 认领 → 执行 → 台账，且"未执行"三条路径不写台账 | `SpToolCallingManager:1372-1533` | 资源锁设施可用（`UNAVAILABLE` 时放行 + WARN，属已知取舍） |
| P13 | 前端段/卡"已决不复活" | `stores/ai.ts:5002-5004`、`:5031-5036`（`hasDecidedPendingResidue` 8 态）、`parts.ts` first-wins | 本页签曾收到该 `toolCallId` 的结局帧/快照（否则只能靠后端 L0 不补发，见 F11） |

## 6. 未验证（无法在本快照内证伪，需真实拓扑或外部证据）

- **U1｜`RunParkedException` 穿越真实 Spring AI 工具循环**。当前证据是 `Flux.error(...)` 替身 + 直接调 `executeToolCalls`。
  若任一环节（Reactor 操作符、WebClient、`RetryTemplate`、未来版本升级）把它包成别的异常，`runAttempts` 的
  `instanceof` 判定失效 → 走"上游错误"路径：`beacon.startedCount>0` ⇒ `sideEffect=true` ⇒ 不重试 ⇒ 落 `error` 终帧 + 快照
  FAILED，而卡仍在 PENDING（用户看到"AI 服务暂时不可用"，卡片悬空）。**风险等级：中**（一旦发生，是所有 park 轮的共性故障）。
- **U2｜≥3 JVM 下的真实竞争**：并发决定同一卡、并发决定不同卡、决定与取消、决定与超时、续跑与会话占用——
  仅有同进程替身证据，无任何真实拓扑证据。
- **U3｜Redis Cluster 行为**：三键 Lua 的 `CROSSSLOT` 边界、`MOVED`/`TRYAGAIN` 在决定/认领路径上的表现（代码把异常折成
  `UNAVAILABLE` 或抛出 503），只有 Java 复刻的槽算法与假 Redis。
- **U4｜跨实例帧唤醒端到端**：客户端连在 A、续跑在 B 时，`ai:wake:` PUBLISH + 差量拉取的时延与丢帧边界。
- **U5｜前端多页签**：真实 Web Locks 行为（jsdom 无 `navigator.locks`，测试走 `'unsupported'` 分支）、
  `pagehide/pageshow` + 宽限接手的时序。
- **U6｜"用户 park 期间又发一轮"导致 `SESSION_BUSY`，重派发用尽后（约 360s）的可见性**：只有 `ERROR` 日志，无用户面。
- **U7｜`/cancel` 的 parked 兜底在 F1 窗口内不生效**（返回 `ACTIVE_LATCH_IN_PROCESS`）后，用户面只有"已取消"提示，
  实际未收敛——需要真实时序确认影响面。

---

## 7. 覆盖缺口汇总（本域未覆盖的分支/文件）

| 缺口 | 说明 |
|---|---|
| 决定 × park 退出窗口 | 无任何用例（F1） |
| 取消 × 已决未执行 | 无任何用例（F2） |
| 超时/取消 × 回灌/决策 竞争 | 无任何用例（F3） |
| guard 已写/条目未写（APPROVED、FRONTEND_RESULT） | 无任何用例（F4） |
| 到期任务在实例重启后的重建 | 无任何用例（F5） |
| 同批"已结算未发帧"条目（park 后） | 无任何用例（F6） |
| 跨 park/跨实例的预算 armed 与 `MAX_ARMED_BATCHES` | 无（F7） |
| 已公布卡被 step-limit / haltsBatch skip 后的用户点击后果 | 无（F8） |
| 多用户/身份/授权 | 设计上不存在（F9） |
| `AiController` 的 `/runs`、`/runs/{id}`、`/pending/{runId}` 的越权面 | 未审（依赖 F9 结论） |
| `SseChatEmitter` 写入预算、`RedisFrameStore` 容量淘汰对"回执帧/终帧"的影响 | 只审调用面，未审内部 |
| `ShutdownDrainGate` 与 park 调度器/续跑触发池的关系 | 相邻但属健康检查域，未审 |
| `AiTools` 四个 `start_*` 的业务契约（预检→执行→作业中心一致性） | E 域 |
| `frontend/src/components/AiPanel/AiPanel.vue` 的逐行渲染/按钮禁用 | 抽样，未逐行 |

---

## 8. 结论（一段话）

本域在"**单实例 + Redis 正常 + 无并发外部输入**"这一前提下，主干设计（决策唯一写者、认领恰好一次、租约单驱动、
park 状态外置、帧序契约、两道前端闸）是自洽且可证明正确的；但它有**三处系统性裂缝**：
①**park 把"等人"从线程里拿走之后，`woke`/`hasActiveLatch` 仍以"闸门存在"当作"有人会驱动"**（F1，丢失 resume）；
②**"决策/回灌"与"超时/取消"是两套写入口径**，后者裸写整条覆盖（F3），并对"取消后不再产生副作用"不设第二道闸（F2）；
③**guard WAL 只对可重建的三种状态自愈**，`APPROVED`/`FRONTEND_RESULT` 的崩溃窗口会留下永久死卡（F4）。
三者都不是"测试不足"，而是**代码可证的边界缺失**，且现有测试**恰好把第一条的窗口固化成了期望**、把第二条的底座
（`InMemoryRunStore` 默认共享对象引用）造成了"覆盖类缺陷不可见"。建议按 F1→F2→F3→F4 的顺序处理，并补齐"≥2 JVM +
真 Redis Cluster"的验收证据（当前为零）。
