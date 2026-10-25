# 最终 scoped 复审（提交前最后一次）— M-2′ / M-7 / I5 / unknown-holder

> **对象**：主仓 `<主仓>` 工作区（HEAD `2eb2539` + 全部候选，含 `task-8-final-fix`、`task-8-settlement-final`、`task-3-fix5`、`task-8-idempotency-contract`、`task-8-abort-settlement`）。
> **对照基线**：本目录 `final-integration-review.md`（首轮）与 `final-integration-rereview.md`（二轮：I-1 ADDRESSED、M-2/M-7 升级 Important）。
> **性质**：只读。未改代码/测试/配置，未提交、未跑全量、未起服务、未读密钥、未 clean。写入物仅本文件。

## VERDICT

**APPROVED** —— 二轮的 2 项 Important（M-2′ park-on 拒绝/取消缺结算行、M-7 复核阶段被 `MAX_ATTEMPTS` 饿死）**均已在代码层闭合**，且各有 park-on/park-off 双向反例钉住；I-1 的 unknown-holder 补丁仍在位且正确；I5 契约（BLOCKED 返回 + 无共享旧帧 + 替身 fence 同源）已按裁决落实、断言是加强而非放宽。本轮**未发现新的真实 Critical/Important**。

提交前仍需注意（都不是阻断）：①我未跑全量/未跑 Spring 上下文启动（装配无环为静态核对）；②`APPROVED 未执行即被取消` 仍是 0 条 end 的存量窄窗（见 §6-R1，属已登记 PK-02 同一窗口，且**不能**用当前仲裁安全地补）；③性能类 M-3/M-4、回执类 M-5、手工 resume 窄竞态 M-6 与 M-1 注释漂移维持上轮登记，不扩。

---

## 1. M-2′ 拒绝面（park-on CONFIRM）

| 要求 | 实测 | 位置 |
|---|---|---|
| **只拒绝** | `if (!approved && publishesRequestsAheadOfLoop())` —— 批准走续跑认领结算，拒绝才由决策侧发 | `gate/ConfirmGate.java:1317-1319` |
| **park-on** | `publishesRequestsAheadOfLoop()` = `properties.getPark().isEnabled()`（`:481-483`） | 同上 |
| **submitLock 外** | 结算调用位于 `synchronized (this.submitLock) { putPending+confirmDecision+signal }` **之后**（`:1304-1309` 的锁块已结束） | `:1296-1319` |
| **唤醒后** | `signal(runId, toolCallId)` 在锁内、结算在其后 ⇒ "落库 → 回执帧 → 唤醒 → 结算行 → 续跑派发" | 同上 |
| **resume 前** | `settleParkedToolCall` 在 `return new Submission(..., maybeTriggerResume(runId, woke))` **之前** | `:1317-1320` |
| 幂等 | 走 `ai:observed` 首写仲裁（`emitForSettledEntry`→`emitOnce`→`claim`），交接窗口里批循环那一条与决策侧互斥 | `:1762`→`gate/ToolCallEndTrace.java:59-75` |
| 载荷 | `outcome=rejected`（`pending.setOutcome(REJECTED)` 在 `:1290`）、`executed=false`、`code=null`、`skipped=false`、`sheetMeta` 空 ⇒ 计数不动 | 同上 + `tool/SheetAudit.java:151-186` |
| 反例（park-off 仍 1 条） | `inlineRejectStillSettlesExactlyOnce` | `gate/ParkSettlementAuditTest.java:377-401` |
| 正例（park-on 恰 1 条 + 重复拒绝仍 1 条） | `parkedConfirmRejectSettlesExactlyOneEnd` | `:350-375` |

## 2. M-2′ 取消面（整轮取消）

| 要求 | 实测 | 位置 |
|---|---|---|
| **可选 setter 复用 ConfirmGate** | `@Autowired(required=false) public void setParkSettlement(ConfirmGate)`，字段默认 null；缺席时取消面与引入前逐字同形 | `run/ResilientChatService.java:321-327` |
| **Spring 无循环依赖** | `ResilientChatService` 是 `@Service`（`:113`），主代码无 `new ResilientChatService`（grep 0 命中），故 setter 会被注入；两个构造器都**不含** `ConfirmGate` 参数（grep 计数 0） | 同上 + `:345/:361` |
| 依赖方向 | `ConfirmGate` 只持 `ObjectProvider<ResumeService>`（`:176`，惰性解环），**不**引用 `ResilientChatService`；`ResumeService` → `ResilientChatService` 是构造依赖，而 `RCS → ConfirmGate` 是 setter ⇒ 建 `ConfirmGate` 时不需要 `ResumeService`，链不闭合 | `gate/ConfirmGate.java:176,285-320` |
| **轻装配无新依赖** | 构造器签名未动；单测/轻装配不设 setter ⇒ `settleCancelledFromPark` 直接 return | `run/ResilientChatService.java:1427-1438` |
| **BACKEND 不无条件发** | `settleCancelledToolCalls` 只收 `CANCELLED` ∩ `{CONFIRM, FRONTEND}`（`settlesOnPark`）；BACKEND 的结算权归 `tryClaimExecution` 且 `requiresArbitration=false` ⇒ 取消侧必须挡掉 | `gate/ConfirmGate.java:1804-1834` |
| **不再重复** | ①与 `cancelledBefore`（`cancelPendings` **之前**抓的清单，`run/ResilientChatService.java:1314-1315`）取交集 ⇒ 不会补结上一轮；②同一调用被 `ai:observed` 仲裁；③重复取消走同一路径但 claim 失败 ⇒ 不放大 | `:1314-1346` + `ConfirmGate.java:1804-1830` |
| 时机与次序 | 在 `emitCancelOutcomes`（补裁决帧）之后、`out.done` 终帧之前；判定不去 acquire `submitLock`（无锁序风险） | `run/ResilientChatService.java:1340-1346` |
| park-off 不补 | `settleCancelledToolCalls` 首行 `!publishesRequestsAheadOfLoop() → return 0` | `ConfirmGate.java:1804-1808` |
| 反例/正例 | `cancelWithParkOffDoesNotSettleFromTheCancelSide`、`cancelSideNeverSettlesBackendEntries`、`parkedCancelSettlesEveryCancelledCardExactlyOnce`（含重复取消仍 2 条） | `conformance/CancelLedgerConformanceTest.java:248-300,359-400` |

## 3. M-2′ abort 面（取消恰在 park 出口被看到）

| 要求 | 实测 | 位置 |
|---|---|---|
| **abort 行保持原载荷** | 载荷原样搬进 `Runnable`：字段集/顺序/取值（`ms`,`execMs` 同值、`skipped=false`,`ok=false`,`outcome=cancelled|failed`,`code=RUN_CANCELLED|TOOL_EXECUTION_THREW`,`executed=source.isExecuted()`,`chars=0`,`cancelled`）**逐字未改** | `gate/SpToolCallingManager.java:1077-1090` |
| **PENDING 不占位** | `if (PENDING.equals(source.getStatus())) line.run();` —— 直接发、不进仲裁 ⇒ 不会挡住"之后真的执行成功"的那条结算 | `:1087-1093` |
| 非 PENDING 才仲裁 | `emitCustomOnce(...)`：`BACKEND`/无仲裁面直发，`CONFIRM/FRONTEND` 抢 `ai:observed`，抢不到一条不发，写行失败释放占位 | `gate/ToolCallEndTrace.java:104-125` |
| **取消侧不重复** | 同一条目：abort 已 claim ⇒ 取消侧 `emitForSettledEntry` claim 失败 ⇒ 0 条；未被 claim（PENDING abort）时条目还没转 CANCELLED，取消侧届时补的是**唯一**一条 | 同上 + §2 |
| 反例/正例 | `cancelSeenAtTheParkExitDoesNotDoubleSettle`（park-on 恰 1 条且仍为 cancelled 语义）、`abortLineStaysSingleWhenParkIsOff`（park-off 仍 1 条） | `conformance/CancelLedgerConformanceTest.java:301-357` |

## 4. M-7 复核阶段预算（fix5）

| 要求 | 实测 | 位置 |
|---|---|---|
| **attempts=0 只作用于 memory-only 阶段** | 唯一写点：`scheduleVerify` 构造 `Pending(..., 0, verifyAtMs, ...)`；该方法的唯一调用方是"删除主体完成且守卫保留"分支 | `session/SessionPurgePendingStore.java:308-317`（`0` 在 `:314`）；调用点 `AiSessionPurgeService.java:446` |
| **普通 pending 上限不变** | `recordFailure` 仍 `attempts+1` 且保留 `verifyAtMs`（`SessionPurgePendingStore.java:378`）；`defer` **不**重置 attempts（`:362-380`，注释明确）；普通台账 `verifyAtMs==null` ⇒ 永不经过 `scheduleVerify` | 同上 |
| 复核仍**有界** | runner 顺序未动：`attempts >= MAX_ATTEMPTS` 短路（`:183`）在 verify 分支（`:188`）之前；复核失败走 `recordFailure` 递增，到 10 后 `defer` 只重排 ⇒ 最多自动 10 次，不是无限重试 | `session/SessionPurgeRetryRunner.java:183-214,266-288` |
| 修复方向正确 | 修复前主体耗尽的台账在 `scheduleVerify` 后仍 `attempts=10` ⇒ `:183` 直接跳过 ⇒ 复核 **0 次**；现在 0 < 10 ⇒ 必执行 | 同上 |
| 断言真实性（已读，未在本轮执行该大用例集） | `verifyStillRunsAfterTheLedgerAlreadyBurnedItsRetryBudget`：先 `recordFailure` × MAX 造耗尽 → 再 `purge()` 成功 → `scheduleVerify` → **制造崩溃残留**（手工把 final 键塞回）→ `runOnce(+120s)` → 断言 `attempted()==1`、残留键被删、`pending==null`、DB 行数**不变**（复核只碰 `chat:mem:*`）、`scheduled.attempts()==0`；反例 `exhaustedNormalPendingStillOnlyDefersAndKeepsItsAttemptBudget`：普通台账耗尽后 `attempted=0`、attempts 仍 10、nextAt 推后、记忆/DB/守卫均不动 | `session/AiSessionPurgeServiceTest.java:962-1050` |

## 5. I5 契约与替身 fence（idempotency-contract）

| 要求 | 实测 | 位置 |
|---|---|---|
| **保留 BLOCKED 返回** | `settleBlocked` 仍写 `status=BLOCKED` / `outcome=blocked` / `code=ALREADY_CLAIMED` / `executed=false`，并把 BLOCKED 文本沿工具链回模型；javadoc 只改"帧面不保证"的表述 | `gate/SpToolCallingManager.java:1716-1730` |
| **无共享旧帧** | 旧驱动（`fence>epoch`）由写门丢帧；用例断言 `wire.types()` 与 `archivedTypes()` 都**不含** `tool_result` | `conformance/ToolIdempotencyTest.java`（`staleFenceHasIndependentConclusionAndText`） |
| **结果不被吞** | 同一用例新增断言：模型侧 `ToolResponseMessage` 该 toolCallId 的 `responseData` 含 `"ok":false`、`"outcome":"blocked"`、`ALREADY_CLAIMED` 与"写回权已转移，本次未执行"，且不含"实例 null"/成功文案 | 同上 |
| **替身 fence 同源** | `InMemoryRunStore` 覆写 `fenceEpoch` 读自己的 `fenceEpochs`（与 `seedFence`/`tryClaimExecution` 同表），避免"null redis ⇒ NPE ⇒ fail-closed 丢帧"把缺陷掩盖成巧合；用例直接断言 `fenceEpoch==2` 与 `permittedToWrite==false`（丢帧的**理由**是写门） | `conformance/InMemoryRunStore.java:311-313`；生产读口 `run/RunStore.java:2064-2075`（读失败抛 `AiStateUnavailableException`，写门 catch 后 fail-closed） |
| 是否"改断言" | 是**契约变更后的加强**：旧断言要求"旧驱动发 tool_result 帧"，与 RT-21 写门直接冲突；新断言把该帧**删掉**的同时新增 5 组断言（零执行/零台账/不占认领、无共享帧与无归档、模型侧结论、条目终态、丢帧理由），覆盖面更大 | 同上 |

## 6. 仍存的非阻断残项（如实登记，不扩）

- **R1（存量窄窗，不能安全补）**：用户批准后、续跑真正执行前按停止 ⇒ `cancelPendings` 只收敛 PENDING（`run/RunStore.java:2324-2341`），该条目留在 `APPROVED`，而 `settleCancelledToolCalls` 只收 `CANCELLED` ⇒ 该卡 `tool_start` 无 `tool_call_end`。**不建议**把 APPROVED 纳入取消侧结算：tool 可能正在执行，取消侧先 claim 会把一条**实际执行了**的调用标成 `cancelled`（假结算行，比少一行更糟）。与审计已登记的 PK-02（取消后仍执行同一窗口）同源，故不新立 Important；若产品要求闭环，需先定"已批准未执行"的结局语义，再在**执行方**（认领前复查取消）产生该行。
- **R2（轻装配口径）**：`settleCancelledToolCalls` javadoc 称"冷装配（无仲裁面）也不会重复"——生产成立（单例 + `ai:observed`），但轻装配下若同一轮被**重复取消**且无仲裁面，理论上可再发一行；仅测试面对，文档措辞略宽。
- **R3（失败方向）**：`settleCancelledToolCalls` 用 `pendingsLenient` 重读，读失败按空集处理 ⇒ 少一行、不报错（方向安全但静默）。
- 维持上轮登记、本次不扩：M-1（`settleParkedToolCall` 注释曾与实现相反，abort 批已改写为五类发点的准确表述 `ConfirmGate.java:1727-1745`，此项实际已修）、M-3/M-4（性能）、M-5（导出回执）、M-6（手工 resume 窄竞态）。

## 7. 复核执行证据

| 命令 | 结果 |
|---|---|
| `mvn -o -B -s maven-settings.xml -Dtest=ParkSettlementAuditTest,CancelLedgerConformanceTest -DfailIfNoTests=false test`（主仓 `backend/`，未 clean） | **BUILD SUCCESS，22/22**（13 + 9），含 `parkedConfirmRejectSettlesExactlyOneEnd`、`inlineRejectStillSettlesExactlyOnce`、`parkedCancelSettlesEveryCancelledCardExactlyOnce`、`cancelSeenAtTheParkExitDoesNotDoubleSettle`、`abortLineStaysSingleWhenParkIsOff`、`cancelSideNeverSettlesBackendEntries`、`cancelWithParkOffDoesNotSettleFromTheCancelSide`（surefire XML 逐条核对过测试名） |
| 静态核对（本轮全部结论的出处） | 上表所有 `file:line`；另核 `busy.runId()` 消费点（`AiController.java:415-443` 全部在 `!unknown` 分支或 null-safe）、`ResilientChatService` 构造器不含 `ConfirmGate`、主代码无 `new ResilientChatService`/`@Bean` 手工装配、`scheduleVerify` 唯一调用点 |
| 未执行（如实） | 全量后端/前端、Spring 上下文启动（无环为静态推演）、`AiSessionPurgeServiceTest`/`ToolIdempotencyTest` 实跑（其断言已逐行读过，仍建议由最终全量/CI 覆盖）、真实 Redis/Cluster/PG/KEK、浏览器 E2E |

## 8. 提交建议

可以进入最终提交与统一验证。合并/提交后请让最终全量至少覆盖：`ParkSettlementAuditTest`、`CancelLedgerConformanceTest`、`AiSessionPurgeServiceTest`、`ToolIdempotencyTest`、`ChatSessionBusyUnknownHolderTest`、`AiControllerHealthSessionLockTest`、`SessionPurgeRetryRunnerTest`，并在真实三实例/CI 上确认 `AIE2E-T-13`/`C-11` 与 `suspend-cross-instance` 三类用例；`@real` 仍按既定前置（三实例全绿）触发。
