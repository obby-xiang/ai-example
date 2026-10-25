> 来源=<仓外证据>/w30-fix-report.md（2026-10-26 归档）· W-30 取消收尾交棒 · 第二轮返修报告（含最终 E2E 断言；该断言本机仅做 PowerShell 语法检查）；属历史证据，不代替终稿/现行基线
# W-30 取消收尾交棒 · 第二轮（复审 REQUEST_CHANGES 修正）报告

- 基线：`1d7b1f0872769987c89a5083ef1aa3f15fb33183`；隔离工作树 `<仓外证据>/w30-repro`（未触碰主仓、未 commit/push/reset/rebase）
- 补丁：`<仓外证据>/w30-fix.patch`（1309 行；`git diff` + 两个新文件的 `git diff --no-index /dev/null`；`git apply --check` 后在干净 `git archive HEAD` 树上应用，七个文件（含 `scripts/verify-e2e.ps1`）与本工作树逐字节相同）
- 复审输入：`<仓外证据>/w30-independent-review.md`（REQUEST_CHANGES）。本轮只闭合主控裁决的 6 项，未扩专项。

## 0. 现场纠偏（采纳复审 §2）

W-30（`ci-40211303032/boot.log:6724-6748`）**没有** `approval_park`/`tool_call_park`：取消来自 `ConfirmGate#await` 的等待循环，
真正覆盖它的是 `ResilientChatService#cancelTerminal` 的 **persist-denied 交棒**；`ConfirmGate` park 分支的交棒只是**条件覆盖**
（写权限已丢时才交棒），测试与注释已按此改正，不再称其为"W-30 全链复现"。

根因表述也按日志纠偏（M-1）：只证明**一次在飞的 Redis 命令被打断**（`Redis command interrupted`）→ `LeaseService`
**整实例**健康位被打假 + 自证恢复探针 1s 节流 ⇒ 4ms 后的拒写**必然**是 UNAVAILABLE（同一进程 7ms 前刚有另一轮
`租约设施自证恢复`）。不称"Redis 故障"，不改通用健康检查，也不把"Reactor 中断=根因"写死。

## 1. I-1：延迟交棒不得改写 DONE/FAILED、不得补第二个 done（已改）

- 代码：`ResumeService#cancelConfirmed` 用 `RunSnapshot.isTerminal` 判终态（DONE/FAILED 一律 `ALREADY_TERMINAL`，不取租、不写）；
  `ResilientChatService#cancelTerminalLocked` 增加防御性守卫：`isTerminal && !CANCELLED ⇒ 直接返回`（不重写、不出帧）。
- **基线红（真实存在，repro 源码一并留档）**：`<仓外证据>/w30-red-baseline-terminal-guard.log`
  - 用例：`w30-red-baseline-tests/CancelTerminalTerminalGuardRedTest.java`（只用基线 API）
  - 结果：`Tests run: 1, Failures: 1`，`expected: "DONE" but was: "CANCELLED"` —— 基线 `RunStore#save` 只查租约、不查原状态。
  - 同一条用例在修复树上转绿：`<仓外证据>/w30-green-baseline-terminal-guard.log`（`Tests run: 1, Failures: 0`）。
- **本轮修复前红**（上一版补丁的行为）：`<仓外证据>/w30-red-review-fixes.log` →
  `cancelFinalizationNeverRewritesADoneOrFailedRun:267 [DONE 不是取消：交棒入口直接判终态]`。

## 2. I-2：终帧幂等判据改为「归档里有没有终帧」（已改）

- 撤回了上一版不安全的 `alreadyCancelled && cancelPendings==0` 早返回：`cancelTerminalLocked` 恢复基线的"每次收敛 + 补帧"语义
  （`cancelPendings` 无论快照状态都跑；不再把"读失败=空表"当证据）。
- 新增 `SseChatEmitter#terminalFrameArchived()`（三态：`TRUE/FALSE/null=读降级`，与回放 `terminalFrame` 同源、走
  `FrameStore#readFromWithStatus` 的 `degraded` 信号）+ `ResilientChatService#cancelTerminalArchived`。
  - `cancelTerminalLocked`：`TRUE ⇒ 跳过（不重写/不重发）`；`FALSE/null ⇒ 继续收尾`（读不出来时按未归档，写门会再拦一次，
    并留 WARN）。于是"快照已 CANCELLED、done 缺失"能被补上，不再成为新死路。
  - `cancelConfirmed`：先看归档，再决定是否 `cancelParked`；收尾后复核归档（`TRUE ⇒ FINALIZED`，否则 `PERSIST_DENIED` 重试）。
    这修掉了复审 I-2-C（CANCELLED 时早返回导致"已取消但残留条目/缺终帧"分支不可达）。
- **红→绿**：
  - 红（上一版补丁）：`w30-red-review-fixes.log` → `aCancelledSnapshotWithNoArchivedDoneIsRepairedExactlyOnce:295
    [快照终态不等于终帧已发：必须补收尾]`。
  - 绿：`w30-green-new-r2.log` 中该用例断言 `FINALIZED` + `done` 恰一次，第二次调用 `ALREADY_TERMINAL` 且仍恰一次。
- 边界如实记录：`save(CANCELLED)` 成功而 done 落档失败在基线上也只是"下一次重复收尾才能补"；本批让该补写变得**可达且可判**，
  但没有新增服务端定时器，也没有扩大"终态快照 + 无 done"的既有边界（见 §7 未证明项）。

## 3. I-3：与续跑共用准入（resuming + SessionGate 一次性 token）（已改）

`ResumeService#cancelConfirmed` 现在：
1. `resuming.putIfAbsent(runId)` —— 同进程已有执行体推进这一轮 ⇒ `IN_PROGRESS`（交棒有界重试），finally 精确移除；
2. `SessionGate.acquire(sessionId, runId, newToken)` —— 被别的轮占着 ⇒ `SESSION_BUSY`；finally 用**同一 token** 释放（可再重试）；
3. `store.acquireRunLease`（生产=三键 `acquireFenced`）+ `verifyHeld` 对 Redis 真值校验 —— 别人持有/确定丢租 ⇒
   `LEASE_HELD_ELSEWHERE`；设施不可确认 ⇒ `UNAVAILABLE`；
   **不换 owner、不强推新 epoch、`fresh=false` 不释放任何租约**（原驱动/等待方那一任不动），也不声称"隔离了原驱动"——
   同 owner 重入是同一 epoch，进程内隔离靠 `resuming` 互斥 + 取消收尾监视器；
4. 收尾后 `ALREADY_TERMINAL / FINALIZED / PERSIST_DENIED` 由"归档 + 终态复读"给出，不靠"复读快照=CANCELLED"。

- **红**（上一版补丁）：`w30-red-review-fixes.log` → `anInProgressResumeBlocksTheCancelHandoff:350`、`aBusySessionLeavesTheRunForARetry:369`、
  `deniedCancelFinalization…:238`（sessionGate 调用契约）。
- **绿**：`w30-green-new-r2.log`（9/9）——包括"一次性 token ≠ runId""按同一 token 释放""`resuming` 用后清空"。
- 锁/清理评估：保留 `finalizeMonitor`。理由（也写进代码注释）：`resuming` **盖不住驱动线程**（驱动不是 resume），
  "读归档判幂等 + 写终态 + 发终帧"仍需要驱动线程与交棒执行体之间的进程内原子段；没有引入第二把全局锁，条带化避免常驻增长。

## 4. 现场纠偏 / 测试可信度（复审 I-4，已改）

- 测试命名/注释：`parkExitHandsOffOnlyWhenTheWriterIsAlreadyReadOnly`、`parkExitDoesNotHandOffWhenWriteAuthorityIsHealthy`
  （明确标注"**不是** W-30 的时序，只锁条件"）；类注释写明 W-30 走 `cancelTerminal`、取消来自 await 循环。
- **handoff 在非调用线程执行**已有断言：用真实单线程池（线程名 `cancel-finalize-handoff`），在 `appendEvent(done)` 处记录落档线程，
  断言 == `cancel-finalize-handoff` 且 != 测试线程。
- **租约入口与生产对齐**：用例在 `InMemoryRunStore` 子类里把 `acquireRunLease` 显式改走生产的 `acquireFenced`（三键同槽），
  不再是两键替身；类注释明示该差异与"仍不能替代 E2E"。
- 仍在 mock/单线程上，不能声称已证明：controller 读到 RUNNING 前提、Reactor dispose 后真线程中断、真 Redis interruption、真并发。

## 5. M-1 注释（已改）

`CancelledRunFinalizer` 类注释、`CancelFinalizeHandoffTest` 类注释、`ResilientChatService` 交棒注释均改为
"一次在飞 Redis 命令被打断 → 全局健康位 + 1s 节流 ⇒ 这一拍 UNAVAILABLE"，并注明"不是 Redis 故障"。
未改 `LeaseService` 健康检查（如要收窄 `markFacilityUnhealthy` 的触发面，属另一专项，需主控单独裁决）。

## 6. 简单安全项（已闭合）

- **M-3**：`submit` 的 `RejectedExecutionException`/派发失败分支统一走 `rescheduleOrGiveUp`，受 `maxDispatchRetries` 约束。
  红（上一版补丁）：`w30-red-review-fixes.log` → `submitRejectionRespectsTheRetryBudget:387 [上限 0 ⇒ 提交被拒也不得无界重排]`；
  绿：`w30-green-new-r2.log`。
- **M-6**：`cancelConfirmed` 在"取消事实不可见 + 快照非终态"返回 `CANCEL_FACT_UNCONFIRMED`，交棒按未知**有界重试**，
  不再记"完成"，也绝不驱动；终态 + 非取消仍 `ALREADY_TERMINAL`。红：`theFinalizerKeepsRetryingWhenTheCancelFactIsNotVisible:324`；
  绿：`w30-green-new-r2.log`。
- M-2（租约在别处 35s×15 的窗口）未缩短，但**每轮重试都受 I-1 终态保护**，注释已写实数（≈525s）；不再有改写风险。

## 7. 红/绿命令与确切计数

命令统一在 `<仓外证据>/w30-repro/backend` 下使用
`"<工具安装目录>/JetBrains/IntelliJ IDEA/plugins/maven-plugin/lib/maven3/bin/mvn.cmd" -o -Dtest=… -DfailIfNoTests=false test`。

| 阶段 | 选择 | 结果 | 日志 |
|---|---|---|---|
| 基线红（W-30 形态，只用基线 API） | `CancelFinalizeDeniedRedTest` | `Tests run: 1, Failures: 1`，`expected "CANCELLED" but was "SUSPENDED"` | `w30-red-baseline.log`（源码 `w30-red-baseline-tests/`） |
| 基线红（I-1 终态保护） | `CancelTerminalTerminalGuardRedTest` | `Tests run: 1, Failures: 1`，`expected "DONE" but was "CANCELLED"` | `w30-red-baseline-terminal-guard.log`（源码同上） |
| 本轮修复前红（上一版补丁） | `CancelFinalizeHandoffTest` | `Tests run: 9, Failures: 7`（I-1/I-2/I-3/M-3/M-6） | `w30-red-review-fixes.log` |
| 修复绿（新用例） | `CancelFinalizeHandoffTest` | `Tests run: 9, Failures: 0, Errors: 0` | `w30-green-new-r2.log` |
| 基线红用例在修复树 | `CancelTerminalTerminalGuardRedTest,CancelFinalizeHandoffTest` | `Tests run: 10, Failures: 0` | `w30-green-baseline-terminal-guard.log` |
| 相邻定向（22 类，含 gate/park/取消/续跑/租约/出帧/会话索引） | 见下 | `Tests run: 425, Failures: 0, Errors: 0` · BUILD SUCCESS | `w30-green-adjacent-r2.log` |
| Spring 上下文装载 | `AiConfigUnavailableSpringTest` | `Tests run: 4, Failures: 0` | `w30-green-spring-context-r2.log` |
| 生产装配探针（临时用例，已删除，不进补丁） | `TmpFinalizerWiringProbeTest` | `Tests run: 1, Failures: 0`（finalizer 非空且注入 `ConfirmGate`/`ResilientChatService`） | `w30-wiring-probe.log` |

相邻定向选择（未跑全量）：
`ConfirmGate*Test, HumanWaitParkTest, ParkSettlement*Test, ParkedFrontendExpiryTest, ResumeDispatchRetryTest,
ResumeProviderGoneDispatchTest, ResumeSkipCascadeTest, SpToolCallingManager*Test, CancelDecisionFramesTest,
CancellationRegistryFailClosedTest, Lease*Test, MultiCardParkResumeTest, ResilientChatServiceParkTest, Resume*Test,
RunStoreSessionCurrentTest, SseChatEmitter*Test, StartupResumeRunnerSessionGateTest, CancelLedgerConformanceTest,
ChatTurnFrameSequenceConformanceTest, AiControllerResumeOutcomeMappingTest, AiControllerRunsCurrentTest,
AiControllerZombieGateTest`

## 8. 仍未由本机证明、必须由 GitHub E2E/CI 验证

1. **W-30 本用例在新代码上转绿**（20s 内快照 CANCELLED、条目非 PENDING、迟到回灌 409、同会话下一轮非 409）；
   本机没有真实 controller RUNNING 前提、Reactor dispose 后的真实线程中断与真实 Redis `RedisCommandInterruptedException`。
2. **真实归档里 `done` 恰 1 条且是最后一条**（本机用 InMemory 归档；生产是 Redis Stream，`degraded` 信号只在真实现上有）。
3. **生产装配**下两个 setter 的生产 profile 行为（本机探针证明 bean 存在并注入；但"漏装配时静默退回旧行为"仍是设计选择）。
4. **`aiResumeTrigger` 池在取消风暴下不饱和**（交棒与续跑共用池；本机未压测）。
5. **多实例窗口**：`LEASE_HELD_ELSEWHERE` 重试期间另一实例合法接管跑成 DONE —— 终态保护只有单测证明，需要集群 E2E 或集成测试。
6. **"快照已 CANCELLED 但 done 缺失"的真实恢复**（本机模拟了 save 成功/帧缺失；生产需要真实写门/投递失败注入）。

## 9. 变更文件（7）

- 改 `scripts/verify-e2e.ps1`（**W-30 专项新增验收断言**，见 §8.1；+11 行，仅 W-30 区域）
- 新增 `backend/src/main/java/com/example/configmgr/ai/run/CancelledRunFinalizer.java`
- 新增 `backend/src/test/java/com/example/configmgr/ai/run/CancelFinalizeHandoffTest.java`（9 条用例）
- 改 `backend/src/main/java/com/example/configmgr/ai/run/ResumeService.java`（`CancelFinalize` 结局 + `cancelConfirmed`：终态保护/归档判据/resuming/SessionGate/租约纪律）
- 改 `backend/src/main/java/com/example/configmgr/ai/run/ResilientChatService.java`（`cancelTerminalArchived`；`cancelTerminalLocked`：归档幂等 + 终态守卫 + 拒写交棒 + 收尾监视器）
- 改 `backend/src/main/java/com/example/configmgr/ai/run/SseChatEmitter.java`（`terminalFrameArchived()` 三态判据）
- 改 `backend/src/main/java/com/example/configmgr/ai/gate/ConfirmGate.java`（park-cancel 分支：写权限已丢才交棒；可选 setter）

红线未动：fencing 判据/`permittedToWrite`/租约墓碑、Reactor 行为、超时、通用健康检查均未改；交棒不发起模型/工具；
正常 park/决策/确认/多实例路径零改动（新逻辑只在"写门已拒绝"之后触发）。

## 10. W-30 真实归档验收断言（本轮新增，**待 GitHub 验证**）

### 10.1 加了什么

`scripts/verify-e2e.ps1` 的 W-30 用例（原有的 20s 状态等待、条目非 PENDING、迟到回灌 409、同会话下一轮断言**全部保留**），
在"取消已收敛"之后、发下一轮之前，新增（+11 行）：

```powershell
$null = Ai-WaitArchivedDone $req.runId          # 既有有界等待（内部 300ms 轮询、上限 15s；未新增/调大任何超时）
$archive = Ai-Events $req.runId 0 @()           # 空 stopOn ⇒ 读服务端回放的完整归档
$cancelDone = Ai-Done $archive                  # done 恰 1 条，否则 throw
if ($cancelDone.cancelled -ne $true) { throw ... }
Ai-DoneIsLast $archive                          # 归档里 done 之后不得再有帧
Ai-SeqStrict $archive                           # seq 严格递增（跨整个归档）
```

### 10.2 为什么是"真归档"而不是"第一个 done 就断流"的帧集（先读了 helper 源码）

- `Read-Sse`（`scripts/verify-e2e.ps1:268-306`）的 `$stopOn -contains $frame.type` 命中即 `break`；`Ai-Events` 只是它的薄包装。
  因此 `Ai-Events $runId 0 @('done','error')` 得到的帧集**按构造**以第一个 done 结尾 —— 在这种帧集上跑
  `Ai-DoneIsLast` 是同义反复（主控点名不许做的事）。
- 本批改用**空 `stopOn`**：不早退，读 `/api/ai/events/{runId}?lastSeq=0` 的服务端回放；终态轮的回放在排空后由服务端收尾
  （`SseChatEmitter` 的 L-143 判据：归档里有终帧则回放排空即收尾），读到 EOF 为止 ⇒ 帧集就是**当前真实归档**。
- `Ai-WaitArchivedDone` 只当作"done 已落档"的**有界等待**（其返回值被丢弃，因为它内部同样以第一个 done 早退，
  不能拿来做尾部断言）。
- `Ai-Done` / `Ai-DoneIsLast` / `Ai-SeqStrict` 均为既有 helper 的既有语义（分别：恰 1 条 done / done 后无帧 / seq 严格递增），未改它们。

### 10.3 本机校验了什么、没校验什么

- 已做：**仅 PowerShell Parser 语法检查**（Windows PowerShell 5.1，`powershell.exe` 可用、`pwsh` 不在 PATH）：
  - 基线 `git show HEAD:scripts/verify-e2e.ps1`：`PARSE-OK tokens=47819 lines=5726`
  - 修改后：`PARSE-OK tokens=47870 lines=5737`（+51 tokens / +11 行）
  - 命令：`powershell.exe -NoProfile -ExecutionPolicy Bypass -File <parse-check.ps1> -Path <ps1>`（脚本用 `[Parser]::ParseInput` 以 UTF-8 读取）
  - 日志：`<仓外证据>/w30-green-ps1-parse.log`
- **没做（也不声称）**：没有真跑 `verify-e2e.ps1`、没有起后端/Redis/stub/前端、没有执行 W-30 用例。
  **语法检查不是 E2E 通过**；"done 恰 1 条且是最后一帧、seq 严格递增"在真实 Redis Stream 归档上的成立与否，
  以及"空 stopOn 在终态轮上读完即 EOF"的端到端时延，都必须由 GitHub 上的 E2E 证明（新增的"待验证"项见 §10.4，与 §8 的 1/2 项同批验证）。
- 同一 ps1 在主仓另有 G12 的两处提示词改动（不在本工作树）：本补丁的 ps1 diff 只有 W-30 区域 **+11 行**，
  不含也未撤销 G12 的任何行；`git apply` 到基线树后 7 个文件逐字节一致。

### 10.4 由此新增的 GitHub 必验项

- W-30 在真归档上：`done{cancelled:true}` 恰 1 条、是最后一帧、seq 严格递增（新增断言本身要通过）；
- 空 `stopOn` 的整读在真服务上按时 EOF（不撞 `HttpTimeoutMs` 兜底）——若撞了，用例会变慢但仍会给出帧集判据，需在 CI 时长上看出来。
