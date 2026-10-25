> 来源=<仓外证据>/ai-full-audit/P2-gates-full.md（2026-10-25 归档）· 审计分片/草稿，属历史证据，不代替终稿 `docs/research/排查-AI全链路只读验收审计-终稿-2026-10-25.md`
# P2-gates-full｜四文件逐行全量复核（ConfirmGate / SpToolCallingManager / LeaseService / AiSessionPurgeService）

> **只读**：未改任何代码/测试/配置，未启动服务，未读任何密钥值。本文件是唯一写入物。
> **快照**：`<仓外证据>/ai-full-audit/tree`（base `ae883fb…` + `working.patch`；`ConfirmGate.java`/`SpToolCallingManager.java` 为 M 状态，读取内容已含补丁）。
> **口径**：不作任何绝对保证；"已证实"= 代码路径可证，不等于本机复现。**不含修复方案、不含"已修"承诺。**
> **去重**：与 A–N 及 `gaps/*` 逐条比对；只列**新增**或**更正**，重复项在 §5 以编号回指。

## 0. 逐行阅读确认（无抽样、无跳读）

| 文件 | 行数 | 读取方式 | 结论 |
|---|---|---|---|
| `ai/gate/ConfirmGate.java` | 1937 | Read 1–1000 + 1001–1937（返回 `End of file reached`） | 全量读完，含末尾 `Latch` 内部类 |
| `ai/gate/SpToolCallingManager.java` | 1940 | Read 1–980 + 981–1940 | 全量读完，含 `ToolExecutionTimeoutException` 尾类 |
| `ai/run/LeaseService.java`（**路径更正**：不在 `ai/session/`，在 `ai/run/`） | 1146 | Read 1–1146 单页 | 全量读完，含 5 条 Lua 原文与 `DefaultRenewScheduler` |
| `ai/session/AiSessionPurgeService.java` | 1011 | Read 1–1011 单页 | 全量读完 |
| 合计 | **6034** | — | 与任务给的行数（1937+1940+1146+1011）逐一对上 |

**为验证分支真相额外读的（非审阅对象，仅取证）**：`RunStore.java` 的 `markRequestEmitted:1633-1649`、`pending/pendingLenient/pendingRaw:1387-1442`、`applyDecisionMarker:1502-1570`、`create:643-668`、`sessionRunIndex:863-882`、`backfillSessionRuns:891-911`、`pruneRunIndex:1213-1246`、`touch*:2211-2251`；`SseChatEmitter.java` 的 `emit:1413-1530`、`confirmRequest:1189-1204`、`completeSubscriptions:2052-2066`；`SessionPurgeGuard.java` 全文（172 行）；`RedisFrameStore.java` 的 `nextSeq:185-199`、`append:239-270`；`AiExecutorConfig.java:86-104`；`ToolOutcome.java:147-148,176-179`；测试侧 `RunStoreRequestEmittedAtomicTest`、`ConfirmGateRequestEmittedTest`、`ConfirmGateDecisionAtomicTest`、`ResumeDispatchRetryTest`、`RetryBackoffTest`、`LeaseServiceTriStateTest`、`LeaseServiceTest`、`RunStoreSessionRunIndexTest`、`AiSessionPurgeServiceTest`、`SuspendDisconnectGateTest`、`SpToolCallingManagerToolTimeoutTest` 的用例名与断言面。

---

## 1. 新增发现（4 条）

### P2-G1｜销毁"信任完整索引"但索引成员写入是 best-effort ⇒ `complete=true` 而整轮 13 键残留

- **severity**：S3（破坏性端点报成功，实际留数据）　**确定性**：已证实（异常路径可证），触发需一次失败的 `SADD`
- **path:line**
  - `ai/session/AiSessionPurgeService.java:466-473`：`fastIndex = readable && complete && !runIds.isEmpty()` ⇒ **只**用会话索引做候选，**不再**回退全局 `ai:runs`
  - `ai/run/RunStore.java:654-663`：`create` 写会话索引是**尽力而为**（`catch → WARN`）。同方法里 `RUN_INDEX` 的写入在 try 之外（强写），会话索引是弱写 ⇒ 两条索引的可靠性不对称
  - `ai/run/RunStore.java:891-911`：`backfillSessionRuns` 在只写入"本次枚举到的"成员后**无条件**写完整性 sentinel `__session_runs_complete__`；全仓**没有**在后续 `SADD` 失败时撤销该 sentinel 的路径
  - `ai/session/AiSessionPurgeService.java:388`、`:421`：`complete` 只由 `errors`/`deferred` 决定，**与"候选是否完整"无关**
  - `ai/session/AiSessionPurgeService.java:546-553`：`found` 同样不含"索引漏掉的那一轮"
- **触发步骤**
  1. 该 session 曾被销毁一次（或任一次枚举走了全局回退）⇒ `backfillSessionRuns` 落 sentinel，索引从此 `complete=true`；
  2. 用户在同一 session 继续对话，`create` 的 `opsForSet().add(sessionRunsKey, runId)` 抛一次（超时/连接重置/命令级错误）⇒ 只有 WARN，sentinel 仍在；
  3. `DELETE /api/ai/session/{sid}`：`fastIndex=true`（老成员非空）⇒ 新 run 不是候选 ⇒ 不 `get`、不 `deleteRun`。
- **effect**：响应 `complete=true`、`removedFromIndex` 正常、`pending` 被移除（**无自动重试**）；而该轮 `ai:run / ai:pending / ai:ledger / ai:idem / ai:events / ai:seq / ai:req / ai:decided / ai:claim / ai:fence / ai:lease:run / ai:lease:epoch / ai:xevents` 13 键与 `ai:beat / ai:activity / ai:cancel` 全部留到 `session-ttl`（默认 24h）；`ai:pending` 里是**工具入参与结果原文**。前端 `clientCleanup.draftToolCallIds` 也漏掉该轮 ⇒ 浏览器草稿键（`cm.ai.form.draft.<toolCallId>`）残留。
- **对应测试（覆盖真相）**：`AiSessionPurgeServiceTest:458-495` 只覆盖"非空但**未回填**"与"索引**读失败**"两种回退；`RunStoreSessionRunIndexTest:73-85` 用 `doThrow` 让 `SADD` 失败后**只断言 create 不抛异常**，既不断言 sentinel 语义、也不断言后续 purge 可见性。**"complete 但缺成员"零用例。**
- **去重**：`F §1.3` 只把 `backfillSessionRuns` 的后填标记路径登记为"未覆盖分支"；本条的**缺陷形态 + 跨文件触发面 + `complete` 判据缺口**为新增。U-03/U-04 讲 SCAN 与淘汰，不覆盖此面。

### P2-G2｜请求帧"发车位"路径重新引入抛异常读 ⇒ C2"读失败不得让等待线程退出"被击穿，且可留下"半公布批次 + 不可续跑轮次"

- **severity**：S3（Redis 局部抖动即整轮失败；park 批次可半公布）　**确定性**：已证实（异常路径可证），触发需一次 `HGET` 失败
- **path:line**
  - `ai/gate/ConfirmGate.java:533` → `:864-882`（`reserveRequestSlot`，全程 `synchronized(submitLock)`）→ `:872` `this.store.markRequestEmitted(...)`
  - `ai/gate/ConfirmGate.java:488-493`（`publishRequestsForParkedBatch`：`pendingLenient` 之后紧跟 `markRequestEmitted`，同样在锁内）
  - `ai/run/RunStore.java:1633-1649`：`markRequestEmitted` 首尾各调一次 `pending(...)`（**强读口**）→ `pendingRaw:1425-1431` 抛 `AiStateUnavailableException`、`decisionMarker:1450-1460` 抛 `AiStateUnavailableException`
  - 被击穿的承诺：`ai/gate/ConfirmGate.java:657-661`（"C2：guard 读失败不得让等待线程退出……用非抛读口"）、`:866-867`（`reserveRequestSlot` 自称用非抛读口）
- **触发步骤**
  1. 卡片即将公布：`reserveRequestSlot` 的 `pendingLenient` 成功（或降级为 null 后继续），随后 `markRequestEmitted` 内部的 `HGET ai:decided` 抛错；
  2. 异常在 `await` 的 `try`（`:637`）**之前**抛出 ⇒ 不进入等待、`finally` 不执行（本次复核确认该段无可抛点，异常只来自这里）；
  3. 若发生在 `publishRequestsForParkedBatch`，同批已公布的卡帧已出，其余卡**永远不公布**。
- **effect**：非 park 路径：`awaitConfirm/awaitFrontend` 在循环 `try`（`:572-601`）内 ⇒ 会补一条 `tool_call_end(code=TOOL_EXECUTION_THREW)`（`traceToolAborted`）再穿出，本轮以异常收尾；park 批次路径（`:499`）在任何单工具 `catch` 之外 ⇒ **连 `tool_call_end` 都没有**。两种情形下条目仍是 `PENDING`、`req` 位可能未落（或只落一部分）⇒ 刷新后不建卡（前端只为 `req=true` 建卡）、用户看不见可点的卡；`ResumeService` 见到外部 `PENDING` ⇒ `PENDING_UNRESOLVED`，该轮**无法续跑**，只能整轮取消或销毁。
- **对应测试（覆盖真相）**：`RunStoreRequestEmittedAtomicTest:49-110` 覆盖"M-1 读写之间被决""重复 mark 保留首时刻""已决不标""cancel 胜出"，**无一次失败注入**；`ConfirmGateRequestEmittedTest:125-164` 只断言"发出前先完成 mark 调用"。**"mark 抛错"零用例。**
- **去重**：C 域 F3/PK-03 讲**写回裸写**（`putPending` 非 CAS）；本条是**读口抛异常**击穿 C2 降级承诺。`RunStore` 的 fail-closed 读口是刻意设计，**缺口在调用方**（`:872`、`:493` 无 try 兜底）。

### P2-G3｜（更正）触发池饱和不重试：`EXECUTOR_SATURATED`/`DISPATCH_FAILED` 直接放弃，只有 `ResumeService` 内层饱和才走 15 次退避

- **severity**：S4（Minor；后果与 PK-01 尾部同形，成因不同）　**确定性**：已证实（代码级）
- **path:line**
  - `ai/gate/ConfirmGate.java:1661-1688`（`dispatchResume`）：`catch (RejectedExecutionException)` → 只 `releaseLeaseQuietly` + WARN + 返回 `EXECUTOR_SATURATED`（`:1676-1680`）；`catch (RuntimeException)` → `DISPATCH_FAILED`（`:1681-1685`）。两处**都没有** `retryDispatch(...)`
  - `ai/gate/ConfirmGate.java:1706-1736`（`retryDispatch`）唯一调用点 = `:1821`（`runResume` 内，**只**对 `ResumeService.Outcome.POOL_SATURATED / SESSION_BUSY`）
  - `ai/exec/AiExecutorConfig.java:88-102`：`aiResumeTrigger` = 固定线程数 + **有界队列** + `AbortPolicy`，且 `destroyMethod="shutdown"` ⇒ 停机期间提交得 `TaskRejectedException`（`RejectedExecutionException` 子类）
- **effect**：`POST /api/ai/confirm` 仍 200 + `accepted:true`，但 `resumeTriggered:false, resumeOutcome:EXECUTOR_SATURATED`，**没有任何重排**；已批准的卡停在"已决 + 无人执行"，等下一次外部输入/启动扫描。停机窗口内所有 `woke=false` 的决策都落这一支。
- **更正对象**：`A-inventory.md:231`「派发池饱和 `EXECUTOR_SATURATED` + 指数退避重派（15 次 ≈360s）」——**不成立**：15 次退避属 `ResumeService` 内层 `POOL_SATURATED/SESSION_BUSY`，触发池拒绝本身不退避。`FINAL` §2.3「决策 × park 退出」条未涉及本支。
- **对应测试（覆盖真相）**：`ResumeDispatchRetryTest:82-127,172` 覆盖"饱和一次后成功""一直饱和有界""用尽后可再武装""SESSION_BUSY 同样有界"，**全部打桩 `ResumeService` 返回内层结局，未覆盖 `resumeTriggerExecutor.execute` 抛 `RejectedExecutionException`**。

### P2-G4｜请求帧发射在 `submitLock` 之外 ⇒ 与 S5c-6 的"同一把锁"承诺不符，存在"`confirm_decision` 先于 `confirm_request`"的窄窗

- **severity**：S4（Minor；窗口窄，需跨实例/重复决策落点）　**确定性**：已证实（顺序缺口可证），危害形态为瞬时
- **path:line**
  - 承诺：`ai/gate/ConfirmGate.java:71-73`（"三件事的次序固定为 落库 → 发回执帧 → 唤醒（且同一把锁内）"）、`:464-466`（`publishRequestsForParkedBatch`"与 `reserveRequestSlot` 同序、**同一把提交锁**"）
  - 实际：`ai/gate/ConfirmGate.java:487-499` —— 锁内只有 `pendingLenient + markRequestEmitted`；`out.confirmRequest/frontendToolRequest`（`:497-503`）在**锁外**；`await` 侧同样：`:533` 锁内取"发车位"，`:563-572` 锁外发帧
  - 提交侧三处都是"锁内落库 + 回执帧 + signal"：`:1186-1190`、`:1507-1511`、`:1553-1557`
- **触发**：`toolCallId` 已被另一实例公布过（僵尸/接管/重放，同一 `toolCallId` 重建），决策在"本实例锁内 mark 返回"与"锁外 emit"之间到达（跨实例 RTT 级窗口，不是纳秒 TOCTOU）。单实例首轮不可达——卡未公布前无人能点。
- **effect**：同一 `toolCallId` 上先到 `confirm_decision`、后到 `confirm_request`；前端据后者**重建一张 pending 卡**（已决 id），再提交得 409。随后 `tool_result` 一般会收敛它；若该帧因 `LeaseAuthority.readOnly()` 被 `SseChatEmitter.emit:1422-1443` 丢弃，卡会**停在 pending 且不可提交**。
- **对应测试（覆盖真相）**：`ConfirmGateRequestEmittedTest:125-164` 只断言"emit 发生在 mark **返回之后**"（同线程顺序），**不断言 emit 先于任何决策帧**；`ToolFrameSequenceConformanceTest`/`ParkToolStartOrderConformanceTest` 校验 `suspended → tool_start → request` 的**生成序**，不覆盖"决策帧插队"。
- **去重**：不同于 PK-01（park 下 `woke` 无消费者）、PK-10（非驱动实例丢回执帧）——本条是**请求帧方向**的锁外发射。

---

## 2. 更正性陈述（2 条，无现发危害，但文档/契约与代码不一致）

| # | 原文/位置 | 更正 |
|---|---|---|
| P2-C1 | `AiSessionPurgeService.java:58-66`（类注「**顺序（用户裁决）**：先枚举 → 登记 durable pending → 装守卫 → … → 附件文件 → 会话键」） | 代码是 **① 登记 pending（`:269-297`）→ ② 枚举（`:288`）→ ③ 装守卫（`:301`）→ ④ H2（`:315`）→ ⑤ 取消活跃 run（`:372`）→ ⑥ 记忆/run/上下文（`:377-383`）→ ⑦ 会话键（`:384`）→ ⑧ 附件（`:385`）**。两处与文档相反：pending 早于枚举（`:265-267` 有正当理由——一开始就挡住同 id 的 `/chat`）、会话键早于附件。以文档为准做"顺序合规"判断会误判。 |
| P2-C2 | `LeaseService.java:272-292`（`AcquireResult.created`："`false` = 命中现值……**没有释放权**"） | `release(Lease)`（`:712-741`）**只**比对 token（`<epoch>\|<owner>`），**不校验 `created`**；"没有释放权"只靠各调用点自律（`ConfirmGate.releaseLeaseQuietly:1854-1868` 用 `fresh` 判；`releaseWaitingLease:829-837` 不判，park 出口 `:620` 正是"重入后释放"）。今日无害（`release` 成功会同时 `stopDrivingExact` 并清墓碑，且该轮已交还），但**原语本身不强制其自述不变量**，新增释放点会继承该风险。 |

---

## 3. 精确覆盖例外（读了但"无测试可证"，已与 `J`、`F §1.3`、`U-*` 去重）

| # | 分支（path:line） | 现状 | 与既有清单的关系 |
|---|---|---|---|
| E-1 | `ConfirmGate.java:588` 之后 `:684-700` 的挂起心跳 | `completeSubscriptions()` 已收流，`emit` 在 `subscribers.isEmpty()` 时直接返回（`SseChatEmitter:1500-1504`）⇒ **该心跳没有接收方**，只有 `beacon.pulse` 有效。无用例断言"订阅者挂起期收到心跳"。 | `SuspendDisconnectGateTest:173` 是 emitter 级"零订阅者仍落档"，非本分支 |
| E-2 | `ConfirmGate.java:1676-1685` 触发池拒绝/派发异常 | 见 P2-G3 | `ResumeDispatchRetryTest` 只覆盖内层结局 |
| E-3 | `ConfirmGate.java:986-1010` 到期任务的**异常**两支（内层 `catch RuntimeException`、外层排期失败） | `ParkedFrontendExpiryTest` 用注入调度器**同步执行**成功路径 | 与 PK-05（进程死即不收敛）不同：这里是"排上了但执行抛错" |
| E-4 | `ConfirmGate.java:1155-1164` 抢到 guard 后复判"已非 PENDING" | 无交错用例（需 `cancel`/`expireFrontend` 在"抢 guard → 复读"之间落库）。该支返回 `DUPLICATE` 且**不释放 guard**（`ai:decided` 留 `APPROVED` 而条目为 `CANCELLED`）；因 `applyDecisionMarker` 只合并 `PENDING`（`RunStore:1517-1524`），读侧无分叉 ⇒ 良性残留、不立项 | `ConfirmGateDecisionAtomicTest:102-197` 只覆盖并发双提交与 WAL 补齐 |
| E-5 | `ConfirmGate.java:694-698` 等待期续租失败后继续等待（租约已被接管） | `ConfirmGateLeaseTest` 覆盖抢/交棒，未覆盖"续租 LOST 而人还没决定"时的继续等待 | RT-10 讲丢租终局，不是这一拍 |
| E-6 | `AiSessionPurgeService.java:663-691` 等终态时读快照失败 | 每 50ms 一拍各加一条 warning（1.5s 窗最多 30 条）且 `anyActive=true`；无用例 | 不在 U 列表 |
| E-7 | `AiSessionPurgeService.java:291-296`、`:317-325` 复用既有 pending 时提前中止 | `ownedByThisCall=false` 时**不调用** `recordFailure`（失败计数不推进），只有 `log.warn`；无此路径用例 | `F §1.3` 列的是 `MAX_ATTEMPTS` 耗尽后的重排 |
| E-8 | `AiSessionPurgeService.java:694-713` `reloadRunStatuses` 丢掉 `gateRunId` 判据 | 枚举阶段 `active = !terminal \|\| runId.equals(gateRunId)`（`:504`），等待后重算只留 `!terminal`（`:703`）⇒ "快照已终态但会话门仍指向它"的轮被当非活跃**立即删 13 键**。无用例 | 与 RT-06（活跃轮永 defer）方向相反 |
| E-9 | `SpToolCallingManager.java:1586-1590` 超时监视线程池饱和 → 同步执行且无超时 | `SpToolCallingManagerToolTimeoutTest` 仅 5 例（超时结局/轮继续/码区分/默认 2min），无饱和分支 | 不在 U 列表 |
| E-10 | `SpToolCallingManager.java:353-360` `runId`/快照缺失 → 委托官方执行 | 注释自认"生产不可达"；一旦可达，`alwaysThrow=true` ⇒ 工具异常穿出且**无 `tool_result` 帧、无台账**。无用例 | 不在 U 列表 |
| E-11 | `LeaseService.java:1000-1015` `shutdown()` 归还仍持有的租约 + 清墓碑 | `LeaseServiceTest` 11 例无 shutdown 用例 | `F §1.3` 列的是 `facilityHealthy()` 节流与 `isHeld` 探针 |
| E-12 | `LeaseService.java:571-592` 无 tag 租约"视图丢失但 Redis 仍是我"的重入分支（`created=false`） | `LeaseServiceTriStateTest:87-110` 只覆盖**带 tag** 的 created 判定 | `F §1.3` 列的是 `ACQUIRE_NOFENCE_SCRIPT` |

---

## 4. 复核后判定为"非缺陷"的分支（记录已核，避免重复立项）

| 分支 | 结论与依据 |
|---|---|
| `ConfirmGate.java:558-637` 之间（latch 已登记、`try` 未开始）的异常泄漏（latch/租约/取消监听） | **不成立**：该段只有 `registry.of`、`cancellations.onCancel`（进程内）、`confirmRequest/frontendToolRequest`、`completeSubscriptions`。逐段核对 `SseChatEmitter.emit:1421-1520`：authority 读失败被吞、`FrameStore.nextSeq` 有进程内降级、`append` 自吞、`touchBusinessActivity/touchActivity` 自吞、`publishWake` 自吞 ⇒ 无可抛点。 |
| `SpToolCallingManager.java:416-420` 预检不通过是否触发"首错即停" | **不成立**：`ToolOutcome.java:147-148` 把 `PRECHECK_FAILED`/`PRECHECK_UNAVAILABLE` 列入可重试集合 ⇒ `haltsBatch` 不命中。 |
| `ConfirmGate.java:1143`（kind 校验）先于 `:1146`（PENDING 判定） | **刻意且正确**（错入口比重复提交更根本），与 `:1452`/`:1455` 一致。 |
| `dismiss`/`cancel`/`expireFrontend` 的 `putPending` 非 CAS | **已由 PK-03 覆盖**，不重复。 |
| `AiSessionPurgeService.retry()` 未重新 `guard.arm` | **不成立**：`SessionPurgeGuard.isArmed:100-110` 同时看 `ai:purge:pending:<sid>`，pending 全程存在 ⇒ 写回守卫仍生效。 |
| `SessionPurgeGuard.retainOrArmIfOwner` 的 `actor` 与 `owner` 可能不同 | **仅取证值差异**：`RETAIN_OR_ARM` 只比对 `<purgeId>|` 前缀（`SessionPurgeGuard:79-88`），不校验 actor ⇒ 无功能影响。 |
| `AiSessionPurgeService` 类注"绝不碰 `ai:lock:*`" | 与代码一致（`deleteRun` 只放"仍持有"的 run 租约），非缺陷。 |
| `LeaseService.verifyHeld(epoch<=0)` 返回 `LOST` 但不落墓碑（`:631-633` vs `:644-646` 注释） | **不成立**：该入参语义是"调用方没有号"（=从未持有），不是"校验失败"，`authority()` 给 `NEVER_HELD` 是本意。 |

---

## 5. 分支 → 既有编号 映射（"逐支对照"摘要）

> 四文件已逐行读完；下表按**分支族**归并，每族给结论落点。未列出的行属直线代码/字段拷贝/日志拼装，无判定。

### 5.1 `ConfirmGate.java`（1937）

| 分支族（path:line） | 归属 |
|---|---|
| 构造器 6/8/9 参 + `leases()` 兜底 `:272-330` | 无缺陷；`leases()` 以 store 为准（`:328-330`）与注释一致 |
| `arm`/`disarm` 预登记与回收顺序 `:397-442` | F8 收口；残窗方向见 C 域 F1 备注（有意选可恢复侧） |
| `publishRequestsAheadOfLoop` `:452-454` | 非缺陷（单一事实源） |
| `publishRequestsForParkedBatch` `:471-504` | C1 已覆盖；**新增 P2-G4（锁外 emit）、P2-G2（mark 抛错）** |
| `await` 主流程 `:523-734` | park 退出/PK-01；超时出口/R5；C2 降级→**P2-G2**；心跳→E-1；续租→E-5；中断出口（不写 TIMEOUT）正确 |
| `handOffLease`/`markDrivingGuarded`/`stillDriving` `:747-794` | I7 存活判据；`runIdOf` 键形反解（K 已覆盖）；无新增 |
| `reserveRequestSlot`/`consumeDecided` `:864-898` | L0/M-1 已覆盖；**P2-G2** |
| `cancel` `:905-931` | PK-03（裸写）；取消终态口径正确 |
| `expireFrontend` `:944-964` | PK-03；"结局未知"文案正确 |
| `scheduleParkedFrontendExpiry` `:978-1011` | PK-05；异常两支 = E-3 |
| `submitDecision` 全支 `:1103-1192` | R4 唯一写者 + I2② 派发；**E-4**；`kindMismatch` 位置正确 |
| `convergeOnDecided`（WAL）`:1296-1342` | PK-04（只覆盖 3 个可重建态） |
| `submitFrontendResult`/`dismiss` `:1395-1560` | DC-15 两道闸 + R4；`applySheetMeta` 二次落值正确（`:1490`） |
| `triggerResume` `:1606-1658` | 五前置判据；`UNAVAILABLE` 降级（I4）正确；**P2-G3** |
| `dispatchResume`/`runResume`/`releaseLeaseQuietly` `:1661-1868` | L-19 归还白名单正确；**P2-G3** |
| `Latch`/`signal`/`hasActiveLatch`/`pendingGates` `:1876-1935` | PK-01 备注（前缀判据让 `/cancel` 兜底也 skip）；无新增 |

### 5.2 `SpToolCallingManager.java`（1940）

| 分支族（path:line） | 归属 |
|---|---|
| `executeToolCalls` 入口 `:344-383` | 步数读快照（跨进程成立）；**E-10** |
| 预检 + 前端入参闸 `:389-436` | F5 / MINOR-1/2/4；`PRECHECK_UNAVAILABLE` 不触发首错即停（§4） |
| `expiresAt` 设定 `:444-453` | 正确（只给有时限者设） |
| 预登记 + `saveSuspended` + `suspended` + `tool_start` 前移 + 整批公布 `:455-500` | F8 / C1 / PK-14；**P2-G4** |
| 执行循环 `:502-680` | P1-10 / PK-06 / PK-07 / PK-08 / T1 / R6 已覆盖；`traceToolAborted` 的 C1 自吞正确 |
| 轮内预算 `:857-933` | 工具轮内上下文预算批；armed 跨进程缺口 = PK-07 |
| `executeOnce` 六步 `:1372-1409` | B23L1 批③b（kind 硬闸/重放/F2/L1/认领三态/台账）已覆盖 |
| `claimAndExecute` `:1422-1533` | U4 unknown 保持、STALE_FENCE 文案、LOST 二次对账正确；`executed=true`+`claimedBy`（已认领≠已执行）为刻意口径 |
| `executeWithTimeout`/`settleToolTimeout` `:1576-1643` | 独立超时、不写台账；**E-9** |
| `resolvePending`/`applyReconciliation` `:1816-1871` | APPROVED+FRONTEND 假批准、UNKNOWN 三跳对账，正确 |
| `newPendings`/`kindOf`/`settle`/`runIdOf`/`frameText` `:1874-1933` | 非缺陷 |

### 5.3 `LeaseService.java`（1146）

| 分支族（path:line） | 归属 |
|---|---|
| 5 条 Lua 原文 `:155-256` | G3 M-2（Lua 原文从未真跑）已覆盖 |
| `acquire` 入口闸 + 可重入短路 + 三/两/单键分派 + 异常折 `UNAVAILABLE` `:518-592` | I4 / L3 / M3 已覆盖；`ended.remove` on success 正确 |
| `interpret`/`parse`/`createdFlag` `:601-614,1099-1124` | `\|0`/`\|1` 判据正确；畸形值按占用（不吞并）正确 |
| `verifyHeld` `:626-662` | R3 条件摘视图 + 墓碑，正确 |
| `renewState`/`release` `:686-741` | 三态墓碑正确；**P2-C2（release 不校验 created）** |
| `authority`/`facilityHealthy`/`markEnded` `:797-850` | 四态优先级正确；`UNAVAILABLE` 墓碑只能靠成功 acquire/renew 清除（终局风险 = RT-10） |
| `markDriving`/`renewTick`/`stopDriving` `:855-951` | R9 computeIfAbsent；RT-17（资源锁 TTL 被改写为 30s）已覆盖 |
| `isHeld`/`leaseOk`/`shutdown` `:963-1015` | `shutdown` 无测试 = E-11 |
| `epochKeyFor`/`hashTagOf`/`epochTtlMillis`/`nextEpoch` `:1042-1096` | 同槽推导正确；TTL 下限 1ms 与注释一致 |

### 5.4 `AiSessionPurgeService.java`（1011）

| 分支族（path:line） | 归属 |
|---|---|
| `purge` ①–④ `:258-327` | F-04（guard TOCTOU）；顺序文档 = **P2-C1**；`ownedByThisCall` 回滚正确 |
| `retry` `:335-364` | pending 校验 + 复用 purgeId 正确；不重装 guard 但 `isArmed` 看 pending（§4） |
| `executeDeletion` `:367-431` | 守卫保留期 + pending 移除 fail-closed 正确；`complete` 判据 = **P2-G1** |
| `enumerate` `:456-554` | 快索引信任 = **P2-G1**；`{` 前缀碰撞两道防线正确 |
| `archiveKeys`/`archivesFromTurnNo`/`scanArchives` `:564-632` | glob 转义 + 纯数字后缀 + 逐 master SCAN 正确（U-03 为拓扑缺口） |
| `cancelActiveRuns`/`awaitTerminal`/`reloadRunStatuses` `:636-713` | **E-6**、**E-8** |
| `deleteMemory`/`memoryRemovedFromIndex` `:717-740` | 非缺陷（索引读失败按"未移出"报告） |
| `deleteOrDefer`/`deleteObservabilityKeys` `:742-782` | RT-06、F-14（3 键 DEL 跨槽）已覆盖 |
| `deleteContext`/`deleteKeyIfType`/`deleteKey` `:784-834` | 类型判据挡住前缀碰撞，正确 |
| `deleteSessionKeys` `:845-885` | 值感知双防线 + `sessionRuns` SET 判型，正确 |
| `deleteAttachments` `:887-943` | "resolve 后删前/删后同路径"判据正确；**枚举快照早于等待窗**（等待期新增的附件可能漏删）无写侧证据，仅登记为条件风险，不单列发现 |

---

## 6. 本报告不能主张的事

1. 未运行任何测试/服务/CI；所有结论为**静态可证**或**具体风险**，不含运行级复现（`cluster-3node` 仍按 C-5 的分层结论）。
2. P2-G1/P2-G2 的触发都依赖"一次 Redis 命令失败"，**未测过失效率**，也无法从代码断言真实集群下的失败概率。
3. P2-G4 的窗口宽度未测量（未真跑双实例），只能断言"顺序缺口存在"。
4. 我**没有**逐行复核 `RunStore`（2822 行）、`SseChatEmitter`（2206 行）、`ResumeService`、`ResilientChatService` 全文——只读了本报告引用到的段落；这些文件的行级结论仍以 B/C/F 域为准。
5. 无任何"已修/已保证"含义；`working.patch` 对 `ConfirmGate`/`SpToolCallingManager` 的改动已被本次读取覆盖，但补丁的 CI 验证状态见 `N` 与 `FINAL`。
