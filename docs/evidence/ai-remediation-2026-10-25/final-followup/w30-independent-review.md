> 来源=<仓外证据>/w30-independent-review.md（2026-10-26 归档）· W-30 独立只读复审 · 第一轮（REQUEST_CHANGES，历史留痕，不随后续通过而失效）；属历史证据，不代替终稿/现行基线
# W-30 取消收尾补丁 · 独立只读复审

- 审查对象：`<仓外证据>/w30-repro`（git worktree，基线 `1d7b1f0872769987c89a5083ef1aa3f15fb33183`）
- 主仓：`<主仓>`（1d7b1f0）
- 现场证据：CI run 40211303032，runId `3fef5d65-cdac-406e-b475-5da066a3a430`，`<仓外证据>/ci-40211303032/boot.log:6724-6749`，失败断言 `<仓外证据>/main-ci-40211303032-failed.log:230`
- 审查方式：只读。未改任何代码，未提交/推送，未在共享树跑 Maven。`4/4` 定向报告**未复跑、未采信**。

## 判决：REQUEST_CHANGES

补丁方向正确，且**很可能真的能让 W-30 变绿**（推理见 §3）。但新增的异步入口在终态判定、写权限语义和并发准入上有三处实质缺口，其中一处可把**已经 DONE 的轮次覆盖成 CANCELLED**。这三处都不需要推翻设计，均为小改可修。

## 1. 审查快照（sha256）

审查期间实现 agent 仍在改文件，下表是**我最终结论所依据的那一刻**（2026-10-26 00:57 +08:00）：

| 文件 | 审查开始时 | 最终（本报告依据） |
|---|---|---|
| `backend/src/main/java/com/example/configmgr/ai/gate/ConfirmGate.java` | `47774e21…f6d9bf` | `47774e21…f6d9bf`（未变） |
| `backend/src/main/java/com/example/configmgr/ai/run/ResilientChatService.java` | `604c4272…1d225d7` | `b10c5e75…f3c288e3`（**已变**） |
| `backend/src/main/java/com/example/configmgr/ai/run/ResumeService.java` | `6c97e2a9…0b75765a6` | 同左（未变） |
| `backend/src/main/java/com/example/configmgr/ai/run/CancelledRunFinalizer.java` | `5a3775b8…4c894e8e` | `14a44b8f…72ad76ac`（**已变**） |
| `backend/src/test/java/com/example/configmgr/ai/run/CancelFinalizeHandoffTest.java` | `c806725e…a860e09f8` | `365462e4…2028cd3a`（**已变**） |

变更内容经比对为 `persistAllowed` 提取成 `persisted` 变量、注释/空白调整，测试仍是同样 4 个 `@Test`（`CancelFinalizeHandoffTest.java:136/180/208/227`），不影响下列结论；但**行号以最终快照为准**。若实现 agent 在报告之后再改，需要重新核对我引用的行。

## 2. 日志能证明什么、不能证明什么（先钉事实）

实际 W-30 的时间线（`boot.log:6724-6749`）：

1. `57.466` `tool_call_start ui_scroll kind=FRONTEND`（工具线程 `boundedElastic-28`）。
2. `57.468` `StreamWatchdog`（HTTP 线程 `io-18330-exec-5`）判 `AI_RUN_CANCELLED`；同一毫秒 `RunStore`/`LeaseService` 在 `boundedElastic-28` 上抛 `Redis command interrupted` → `租约抢用降级`。
3. `57.469` `死卡兜底不触发`；`57.472` 驱动线程 `ai-suspend-19` 在 `cancelTerminal` 里被写门拒绝：**「续跑权只读：拒绝取消态 DB 历史/快照/记忆…（租约设施不可用（无法确认是否仍持有））」**；同一毫秒 `SseChatEmitter` **「续跑权 UNAVAILABLE：丢弃本轮业务帧（含终帧）type=frontend_tool_result」**。
4. `57.475-57.476` 驱动侧仍记了 `AI 轮次取消` / `turn_end status=cancelled`，但**没有任何执行体再写快照**。
5. E2E 20s 后读到 `SUSPENDED` → 失败（`main-ci-40211303032-failed.log:230`）。

由此得到两条对补丁评价很关键、且与补丁自身叙述不符的事实：

- **W-30 没有走 park 分支。** 该 runId 在整段日志里没有 `approval_park` / `tool_call_park` / `人工等待 park`（对比同一次 CI 的 W-31 `boot.log:6790+` 有这三条），`cancel()` 是从 `await` 循环（`ConfirmGate.java:724`）里出来的。所以：
  - 补丁在 `ConfirmGate.java:647-658` 新增的 park 分支交棒**不覆盖本现场**；
  - 真正覆盖 W-30 的是 `ResilientChatService.java:1394-1412` 的「persistAllowed 拒绝 → 交棒」。这条**确实在 W-30 的调用链上**。
- **「Redis 抖动/故障」没有被日志证明，而且有一个更简单的解释。** `LeaseService.facilityHealthy` 是**整个 LeaseService 实例一个 volatile 标志**（`LeaseService.java:413`），任何线程一次失败就 `markFacilityUnhealthy()`（`:860`）清掉全局健康位；`facilityHealthy()` 有 **1s 节流**（`:428 HEALTH_PROBE_INTERVAL_MS = 1000L`），窗口内**直接复用上次结论**。W-30 的拒写发生在失败后 **4ms**（57.468→57.472），因此 `authority()` 走 `:818` 的 `facilityHealthy()? NEVER_HELD : UNAVAILABLE` 必然返回 `UNAVAILABLE`——**即使 Redis 已经完全正常**。同一进程 7ms 前（`57.116`）还有另一轮 `租约设施自证恢复（PING 成功）`。所以准确说法是「一次 `RedisCommandInterruptedException` 触发了全局健康位 + 1s 节流」，而不是「Redis 抖动」。这点只影响注释根因与修法选择，不改变「需要换线程重试」的结论。

## 3. 是否闭合本现场

**按代码推理，会闭合**：交棒后 `CancelledRunFinalizer.attempt` → `ResumeService.cancelConfirmed`（`ResumeService.java:1239`）→ `store.acquireRunLease`（无视图，真抢一份）→ `verifyHeld` → 成功即清墓碑/清全局健康位 → `cancelParked` → `cancelTerminal` → `permittedToWrite` 为 HELD → `save(CANCELLED)` → `done`。首拍在 `aiResumeTrigger` 上，不等 35s 租约退避，远早于 20s。工具条目在 W-30 里已被 `ConfirmGate.cancel()` 落成 CANCELLED，迟到回灌 409、同会话下一轮也都由既有逻辑满足。

**但没有任何证据**：实现 agent 的 `4/4` 是定向单测，且没有基线红测留档；W-30 本身**尚未在新代码上跑过 E2E**。所以只能说「推理上闭合、未被证明」。

## 4. Important（需改）

### I-1 `cancelConfirmed` 只跳过 CANCELLED，延迟交棒会把 DONE/FAILED 覆盖成 CANCELLED

- 位置：`backend/src/main/java/com/example/configmgr/ai/run/ResumeService.java:1277-1280`
- 调用链：
  `CancelledRunFinalizer.attempt`（`CancelledRunFinalizer.java:183`）→ `ResumeService.cancelConfirmed` → 快照非 CANCELLED 就调 `chatService.cancelParked`（`:1280`）→ `ResilientChatService.cancelTerminalLocked` → `alreadyCancelled = "CANCELLED".equals(snapshot.getStatus())`（`:1367`）为 false → `snapshot.setStatus(CANCELLED)`（`:1389`）→ `store.save`。
  `RunStore.save`（`RunStore.java:700`）**只查租约写权限，没有任何终态保护**，因此 DONE/FAILED 会被直接改写，并额外 `historyRecorder.recordStopped(...)`、`convergeMemory(...)`、补发取消帧和第二个 `done`。
- 可达性（这是新增的窗口）：交棒在 `LEASE_HELD_ELSEWHERE` 时按 `app.ai.lease.retry-delay=35s` 重排，最多 `max-dispatch-retries=15` 次（`CancelledRunFinalizer` 里 ≈ **525s**）。若这段时间里另一个实例合法接管、把这一轮跑成 DONE 并在终态 `save` 里释放租约（`RunStore.java:748`），下一次重试就能**全新抢到租约**并通过 `verifyHeld`，随后把 DONE 改成 CANCELLED。跨实例 fencing 在这里救不了：对方是**合法释放**，不是被抢。
  单实例变体：park 取消后另一个执行体（例如已被触发的续跑）先把轮次收成 DONE，交棒任务随后才被 `aiResumeTrigger` 调度到。
- 为什么算「新引入」：`cancelParked` 缺终态保护是既有的（`cancelStateOutcome` 也有同样潜在问题），但那要一次**显式 resume 调用**；这里是**后台自动、延迟、可重试 15 次**的新入口，把窗口从「用户操作」放大到「9 分钟」。
- 最小反例（可直接加进 `CancelFinalizeHandoffTest`，约 8 行）：
  ```java
  seedRunWithStatus(RunSnapshot.DONE);           // 快照 DONE，无 pending
  when(cancellations.cancelState(RUN_ID)).thenReturn(CANCELLED);
  assertThat(resumeService.cancelConfirmed(RUN_ID)).isEqualTo(ALREADY_TERMINAL); // 现实现返回 FINALIZED
  assertThat(store.get(RUN_ID).getStatus()).isEqualTo(RunSnapshot.DONE);         // 现实现变成 CANCELLED
  ```
- 最小修复：`cancelConfirmed` 用 `RunSnapshot.isTerminal(snapshot.getStatus())` 取代 `CANCELLED.equals(...)`（：1277），并在 `cancelTerminalLocked` 的 `alreadyCancelled` 判定处补一条同义守卫（防御性，避免其它调用方直接把 DONE 交进来）：`if (RunSnapshot.isTerminal(status) && !alreadyCancelled) return;`。只改这两处，不牵动其它语义。

### I-2 `alreadyCancelled && cancelPendings==0` 用「读不到的 0」和「快照状态」冒充「终帧已发」

- 位置：`backend/src/main/java/com/example/configmgr/ai/run/ResilientChatService.java:1367-1373`
- 问题 A（读失败被当成 0）：`cancelPendings` 走 `pendingsLenient`，而 `pendingsLenient` 在 Redis 读失败时**返回空表**（`RunStore.java:1586-1602`，`:1602 return List.of()`）。于是 `cancelPendings(...) == 0` 同时表示「确实没有待收敛条目」和「读不出来」。在 Redis 刚抖过的那一拍，它会**跳过早返回**，既不补条目也不补帧。
- 问题 B（快照状态 ≠ 终帧已发）：`save(CANCELLED)` 与 `out.done(...)` 是两个存储（`:1395-1401` 与 `:1436`）。若 `save` 成功、`done` 因写门/投递失败被丢，快照已是 CANCELLED；此后任何重试都在 `:1368` 早返回，**终帧永远不会补**。同理 `cancelConfirmed` 在 `:1281-1283` 复读快照为 CANCELLED 就报 `FINALIZED`，交棒侧也认为成功、不再重试。前端重挂时 `SseChatEmitter` 的收尾判据是「归档里有没有 done」（`SseChatEmitter.java` 类注 135/648 行附近），没有 done 会**留挂等实时终帧**——那条帧已经不会再来了。
- 问题 C（自相矛盾）：`cancelConfirmed` 在 `:1277` 遇到 CANCELLED 直接返回 `ALREADY_TERMINAL`，**根本不会调用** `cancelParked`，所以 `:1374-1376`「已取消但还有残留条目就补收敛」的分支**只有别的调用方能走到**，交棒侧永远走不到。
- 定级依据：这是**本补丁新增的**去重条件（基线是每次重写 + 重发，能自愈但会重复出帧）。方向（终帧恰一次）是对的，判据选错了。W-30 本身（SUSPENDED）不受影响。
- 最小修复：判据二选一——
  1. 用「归档里是否已有终帧」判重（`store`/frame store 已有按类型查的口子），而不是快照状态；或
  2. 保留快照判据，但把「0」改为三态（`changed / none / unreadable`），`unreadable` 时必须继续补收敛；并在 `cancelTerminalLocked` 结尾用 `store.archivedTypes(runId).contains("done")` 自检，未落档则只补发 `out.done(...)`（终帧幂等靠归档判定，不靠网络）。
  同时让 `cancelConfirmed` 在 CANCELLED 时也调用一次 `cancelPendings`（或直接走 `cancelParked`），否则 C 永远不可达。

### I-3 交棒没有续跑准入，且「重入租约」反而把写权限还给原驱动

- 位置：`ResumeService.cancelConfirmed:1239-1264`；`LeaseService.acquire:546-551`；`LeaseService.authority:818`；`ResilientChatService.cancelTerminal:1340-1353`
- 三个事实：
  1. `cancelConfirmed` **不进 `resuming` 表、不拿 `SessionGate`**（对比 `resumeInternal`：`ResumeService.java:678`、`:811-819`）。同进程的续跑和交棒可以同时进行；`resuming` 本来就是为了挡「同进程两次并发驱动」而存在的。
  2. `acquire` 对同 owner 是**进程内视图短路**（`LeaseService.java:546-551`），直接返回**同一 epoch**、`created=false`，不访问 Redis、不推进 fence。于是交棒**没有把原驱动 fence 出去**，两边同一 epoch、同一写权限。
  3. 交棒的 `verifyHeld` 一旦成功会 `ended.remove(viewKey)`（`LeaseService.java:645` 附近），**顺手清掉驱动线程留下的 UNAVAILABLE 墓碑**。也就是说：交棒不仅没有隔离驱动，反而可能让驱动在 `cancelTerminal` 返回后的收尾窗口里重新变成可写。
- monitor 覆盖范围：`cancelTerminal` 的三处调用（驱动 `:816`、`cancelParked:1328`）都过 `finalizeMonitor`，**这部分是够的**；但 `finish`/`fail`/`parkTurn` 的快照写、以及 `ResumeService` 侧的 DONE 写**都不在 monitor 里**。所以「进程内 monitor 覆盖全部调用」不成立，只覆盖「取消终态」这一种写。
- 跨实例 fencing：`acquireFenced → verifyHeld → permittedToWrite(fence≤epoch) → fencedSet` 这条链是**充分**的，跨实例不会被陈旧写覆盖；不足的是 I-1 那种「对方合法释放后本端重新接管」的场景。
- 最小修复：`cancelConfirmed` 进入时 `resuming.putIfAbsent(runId, TRUE)`（占用即返回 `IN_PROGRESS`，交棒侧重试），结束时 finally 移除；并取 `SessionGate`（拿不到就 `PERSIST_DENIED` 重试）。若担心与 resume 抢租约，交棒侧可以先用**独立 owner** 或「先 release 再 acquire」拿新 epoch（`created=true`），让旧驱动的写立刻被 fence 掉——但要同时评估 `RunStore.save` 的终态释放语义，别把正在跑的 resume 的租约删掉。
- 说明：这条只影响并发正确性，不阻塞 W-30 单实例现场。

### I-4 测试证明的是「新类自己的链路」，不是 CI 现场；且无基线红测

- 位置：`backend/src/test/java/com/example/configmgr/ai/run/CancelFinalizeHandoffTest.java`
- 4 个用例实际覆盖：
  | 用例 | 覆盖 | 没覆盖 |
  |---|---|---|
  | `deniedCancelFinalizationStillReachesAFencedCancelledTerminal:136` | 真实 `LeaseService` 造出 UNAVAILABLE 墓碑 → `cancelParked` 拒写 → 1 次交棒 → 恢复后 CANCELLED、done 恰一次、条目 CANCELLED、session 索引清空 | 不是驱动线程调用（测试线程直调 `:150`）；没有 dispose/interruption；`cancellations` 是 mock（`:104`），无 runId 级状态 |
  | `handOffRetriesAfterTheLeaseFacilityIsBack:180` | 设施整体宕机 → 排重试 → 恢复后落定 | 重试上限/用尽路径；35s 租约退避 |
  | `cancelConfirmedNeverDrivesWhenTheCancelFactIsGone:208` | NOT_CANCELLED 不驱动 | UNKNOWN / NO_RUN / LEASE_HELD_ELSEWHERE / DONE（见 I-1） |
  | `parkExitAndDriverDenialHandOffOnceAndKeepDoneLast:227` | park 分支 + 驱动侧去重 + done 最后一条 | **这不是 W-30 的时序**（§2 已证 W-30 无 park）；全部在单线程顺序执行，没有真并发 |
- 其它削弱证明力的点：
  - `InMemoryRunStore.acquireRunLease` 走的是 **2 键 `acquire`**（`InMemoryRunStore.java:266-269`，`leaseFacility.acquire(key, null)`），生产的 `RunStore.acquireRunLease` 走的是 **3 键 `acquireFenced`**（`RunStore.java:1251-1258`）。三键 fence 自愈路径**没有在用例里跑过**。
  - `:264-269` 用 `catch (Throwable expected)` 吞掉控制流异常，不校验异常类型/来源。
  - 没有「先读 RUNNING、controller 不派续跑」的模拟——`AiController.cancel`（`AiController.java:888-916`）只在快照 **SUSPENDED** 时才 `triggerResumeForParked`，RUNNING 分支**什么都不做**，这是 W-30 能坏的结构性前提，单测完全没碰。
- **基线红测证据：没有。** `<仓外证据>` 下所有日志都搜不到 `CancelFinalizeHandoffTest`（`grep -rln` 为空），`w30-repro` 里也没有测试日志。所以「test-first 已完成」不成立；`4/4` 只能说明新代码自洽。
- E2E 仍必须证明（单测无法替代）：
  1. `W-30` 本用例在新代码上转绿（`scripts/verify-e2e.ps1:5141-5156`）：20s 内快照 CANCELLED、条目非 PENDING、迟到回灌 409、同会话下一轮非 409；
  2. 取消后**归档里 `done` 恰 1 条**且是最后一条（W-32 只测了停止文本轮，没测 FRONTEND 卡）；
  3. 真实 Spring 装配下 `ConfirmGate`/`ResilientChatService` 的 `cancelFinalizer` **确实非空**（两个 setter 都是 `required=false`，缺失时静默退回旧行为，无启动失败）；
  4. `aiResumeTrigger` 池在取消风暴下不饱和（交棒与续跑共用池）。

## 5. Minor

- **M-1 注释根因失实**：`CancelledRunFinalizer.java:27-35`、`CancelFinalizeHandoffTest.java:43-50` 把事件写成「同一瞬间 Redis 抖动/故障」。日志只证明「一次 `Redis command interrupted`」+ 4ms 后的 1s 节流拒写（§2）。建议改成准确表述：「取消/断流打断了本线程在飞的 Redis 命令 → `markFacilityUnhealthy` 全局健康位 + 1s 节流使本拍必为 UNAVAILABLE」。这不只是措辞：如果按「Redis 故障」理解，后续很可能继续加重试/超时；按「interruption」理解，`LeaseService` 侧还应收窄 `markFacilityUnhealthy` 的触发面（例如 `RedisCommandInterruptedException` 不计入设施故障），否则**任何一轮**的取消都可能把别的轮次一起打成只读 1s。
- **M-2 重试上界与注释不符**：`CancelledRunFinalizer.java:45-46` 说「同一套指数退避」，但 `LEASE_HELD_ELSEWHERE` 走 `leaseRetryDelay()` = 35s（`:258-260`），15 次 ≈ **525s**，不是 park 套路的 ≈360s。这直接放大 I-1 的窗口。建议单独给取消收尾一个更短/更小的上界（例如 3 次 × 租约 TTL），并在注释里写实数。
- **M-3 `submit` 的拒绝分支绕过上限**：`:158-170` 在池满时无条件 `reschedule`，不看 `maxDispatchRetries <= 0`；`rescheduleOrGiveUp`（`:205-216`）才判。建议统一走 `rescheduleOrGiveUp`。
- **M-4 线程/锁的资源面**：`DEFAULT_SCHEDULER`（`:73-86`）是**全 JVM 单线程**，所有 run 的重试串行；`attempt` 又在 `aiResumeTrigger` 上同步跑 `cancelConfirmed`（Redis 往返 + `synchronized(monitor)` + `convergeMemory`）。`finalizeMonitor` 只有 64 条带（`:95-97`），无关 run 会互相顶住「持锁做 Redis/DB I/O」。`inFlight`/`attempts` 在 `Error`（非 `RuntimeException`）下不会被清理（`:185` 只 catch `RuntimeException`）。都不构成 W-30 阻塞，但建议把重试调度改成池化、把 monitor 收窄到「读-判-写快照」那几行。
- **M-5 装配安全**：`ObjectProvider` 是懒获取，**没有构造环**（`CancelledRunFinalizer.java:114-117` 只依赖 provider/executor/properties；`ConfirmGate.java:363`、`ResilientChatService.java:344` 是可选 setter）。但正因为可选，**漏装配时静默退回 bug 行为**。建议在 `CancelledRunFinalizer` 的 `@PostConstruct` 里反向登记/或让生产 profile 下 setter 必填（至少启动 INFO 一行「取消交棒已装配」），否则只能靠 E2E 发现。
- **M-6 NOT_CANCELLED 的窄缝**：`AiController` 的取消是「本地标志 + Redis 标志」两份（`CancellationRegistry.java:147-154`、`:294-303` 写失败只降级）。交棒只认 `cancelState`（本地优先、本地清掉后再读 Redis，`:122-140`）。若取消当拍 Redis 标志写失败、且驱动在 `unregister`（`ResilientChatService.java:539`/`:607`）里先清掉本地标志，交棒会读到 `NOT_CANCELLED` 并按「完成」记 INFO（`CancelledRunFinalizer.java:195`）→ 快照仍 SUSPENDED 且不再重试。W-30 的日志里没有「取消标志写入 Redis 失败」，所以**本现场不涉及**；但这是新入口把「取消事实」跨线程搬运带来的固有缝。最小修法：交棒时把取消事实（或 runId+时间戳）随任务传递，或让 `NOT_CANCELLED` 在「快照 SUSPENDED 且曾收到过 cancel」时降级为 `PERSIST_DENIED` 重试。

## 6. 对主控 6 个问题的逐条结论

1. **DONE/FAILED 被覆盖**：**会**，见 I-1。是新增异步入口引入的窗口（原同步入口要求显式 resume 才可能触发同类问题）。最小反例与最小修复已给。
2. **并发与 fencing**：monitor **只覆盖 `cancelTerminal` 一种写**，不覆盖 `finish/fail/parkTurn` 与 ResumeService 的 DONE 写（I-3）。同实例重入租约**不产生新 epoch**，因此**不能把原驱动 fence 出去**，反而用 `verifyHeld` 成功清掉墓碑、把写权限还给驱动。跨实例 fencing（acquireFenced/verifyHeld/fence 写）本身**充分**；不足的是「对方合法释放后本端重新接管」的窗口（I-1）。
3. **`alreadyCancelled && cancelPendings==0` 堵死旧窗**：**会**，但成因与本补丁不同——基线会重写+重发（能自愈、但会重复终帧）。本补丁为「终帧恰一次」引入早返回，同时把「读失败=0」和「快照终态=终帧已发」两个不等价当成了等价，所以是**本补丁新增的恶化**；W-30 现场是 SUSPENDED，不受影响。见 I-2。
4. **测试是否覆盖真时序**：**没有**。4 个用例证明的是新类+新分支的链路（含真实 LeaseService 的 UNAVAILABLE 墓碑，这点做得不错），但不含 controller RUNNING 分支、不含 dispose/interruption、不是驱动线程、不是真并发；第 4 个用例模拟的 park 分支**不是 W-30 的实际路径**。**基线红测证据缺失**，不能说 test-first 已完成。E2E 必须补的 4 项见 I-4。
5. **触发点是否准确覆盖 W-30**：`ConfirmGate` 的 park 分支交棒**没有**覆盖 W-30（§2 实证无 park）；真正覆盖的是 `cancelTerminal` 的 persist-denied 交棒。两者都只做取消收尾，**没有**放开 RUNNING 续跑/模型/工具/跨供应商语义（`cancelConfirmed` 在非 CANCELLED 时直接返回，`cancelParked` 不重建 prompt、不发起模型）。但 `cancelTerminal` 的早返回与 monitor 是**所有取消路径**都受影响的横切改动，不止 W-30，回归面要用全量取消用例兜。
6. **资源/装配/注释**：无监听器泄漏；`inFlight`/`attempts` 在 `RuntimeException` 路径都能清；`Error` 路径会漏（M-4）。`ObjectProvider` 懒获取**不成环**，但可选 setter 会静默降级（M-5）。注释确实把「`Redis command interrupted`」写成了已证明的独立 Redis 故障，日志不支持，且掩盖了「全局健康位 + 1s 节流」这个更根本、也更容易小改的成因（M-1、§2）。

## 7. 我验证了什么 / 没验证什么

- 已做：逐行读 5 个候选文件与相对基线 diff；读 `RunStore`（`save/permittedToWrite/cancelPendings/pendingsLenient/acquireRunLease/clearSessionCurrent`）、`LeaseService`（`acquire/reentrant/verifyHeld/authority/facilityHealthy/markFacilityUnhealthy/renewState/release`）、`SseChatEmitter`（终帧/回放判据）、`CancellationRegistry`（`cancel/cancelState/writeRedisFlag`）、`AiController.cancel`、`RunSnapshot.isTerminal`、E2E `W-30` 用例；对照 `boot.log:6724-6749` 与失败日志还原时序；核对测试与生产租约入口的差异（2 键 vs 3 键）。
- 未做（受限）：**未跑 Maven/单测/E2E**（共享 worktree 上实现 agent 仍在跑相邻用例）。因此 `4/4` 未复现，`REQUEST_CHANGES` 的判据完全来自静态审查 + 日志。
- 未展开（按要求）：多用户、真 PG、集群多实例的真机验证；这些不影响本补丁在 W-30 单实例现场的判断，但 I-1 的触发前提本身就是多实例，**若集群接管在本轮范围内，I-1 应升级为 Critical**。

## 8. 建议的放行顺序

1. I-1（终态守卫 + 一条 DONE 反例测试）——最小、独立、必改；
2. I-2（终帧幂等判据改成「归档有无 done」或三态 `cancelPendings`）——与 W-32 的「done 恰一次」共用同一判据，改动小；
3. I-3（`resuming` + SessionGate 准入；评估交棒取新 epoch）——可与 I-1/I-2 同批，或单独一批并补并发用例；
4. I-4 的 E2E 四项 + 全量后端取消用例回归；
5. M-1 注释纠正（建议同批，避免下一个修复继续基于错误根因）。
