只读准备完成。结论先说：五条都能用**现有 harness** 做成确定性 RED，不需要新 harness、不需要 e2e/探针；其中 3 条（#2 终帧、#3 静默挂起、#5 指针）已在代码层面完全成立，另 2 条（#1、#4）的**缺陷本身**成立，但"现实触发频率/可达性"仍需运行证据。

我没有写盘权限（本任务为只读 agent，且未获准编辑文件），因此报告在下面，未落到 `task-8-repro-plan.md`。

---

# Task 8 复现计划（只读准备，不含补丁）

仓库根：`<主仓>`（工作树 = `34fd8f4` + Task 1–3 未提交改动：`AiConfigSeeder`、`AiConfigWriter`、`ConfirmGate`、`SpToolCallingManager`、`ResumeService`、`SheetAudit` 等已被修改）。**行号是当前工作树的**，Task 1–3 合流后可能漂移 ±N —— 写测试时按符号名定位，不要按行号。

| # | 缺陷 | 判定 | 现有 harness | 需新增运行证据？ |
|---|---|---|---|---|
| 1 | 取消发生在历史重建中 → 仍执行已批准的不可逆工具（PK-02） | 代码已证（结构性） | `ResumeContextCompactionTest` | 触发时序需运行证据 |
| 2 | park 取消后无终帧 / 无有界收流（RT-04） | 代码已证 + 审计已有归档运行证据 | `AiControllerEventsParkCloseTest`（或 `ResumeContextCompactionTest` 的帧线） | 不需要（"其它客户端挂住"若要写进验收需一次真实观察） |
| 3 | `saveSuspended` 在只读时静默成功（RT-22） | 代码已证 | `LeaseLossReadOnlyTest` | 触发条件需故障注入/多实例证据 |
| 4 | `markRequestEmitted` 读失败把整轮打挂（PK-18） | 代码已证 | `RunStoreRequestEmittedAtomicTest` | 一次 HGET 失败的现实可达性需证据 |
| 5 | `session:current` 被第二轮顶掉 → 挂起卡不可发现（RT-23） | 代码已证 | `RunStoreSessionCurrentTest` | 不需要 |

**五个用例都加进既有测试类**（不新建类）⇒ Step 4 的"最多 4 个相邻类"自动满足（栖身类本身就是新测试的家），也不必跑全量。

---

## 1. 历史重建期间的取消 → 不可逆执行

**缺陷（已证，结构性）**：取消只在续跑入口查一次：`ResumeService.java:691`（`cancelState`）→ `:699-708` 收敛。其后重建历史（`:812-820`）、`buildResumedPrompt`（`:829`）、逐条 `resolvePending`（`:850`）**都不再查取消**；`SpToolCallingManager.resolvePending`（`:1817-1849`）与 `executeOnce`（`:1352-1390`）里也没有取消判定 —— 只有**首轮内联循环**有（`SpToolCallingManager.java:515`、`:606-608` 的 `checkCancelled`，实现在 `:1062-1075`）。取消落在"入口检查之后、`resolvePending` 之前"就是"用户已取消，不可逆工具照样执行"。

注意顺序：压缩在 `:914-951`，**晚于**结算（`:833-868`），所以"压缩窗口"不能单独成为执行前最后一道门；真正的缝是重建段，修复点必须紧贴 `resolvePending` 循环之前（编译期的顺序事实，不是设计建议，仅供定位）。

**测试缝**：`backend/src/test/java/com/example/configmgr/ai/run/ResumeContextCompactionTest.java` 加一例。该类的 harness 已备齐：`InMemoryRunStore` + 真实 `MessageJsonCodec` + `chatService`/`toolCallingManager` mock + 同步 `executor` + `cancellations` mock。

**注入方式（确定性，不靠线程时序）**：
```java
AtomicBoolean cancelled = new AtomicBoolean();
when(cancellations.cancelState(RUN_ID)).thenAnswer(i ->
        cancelled.get() ? CancelState.CANCELLED : CancelState.NOT_CANCELLED);
// 造"取消正好落在历史重建里"：buildResumedPrompt 在入口检查之后、resolvePending 之前被调用
when(chatService.buildResumedPrompt(eq(RUN_ID), eq(SESSION_ID), any(), any()))
        .thenAnswer(i -> { cancelled.set(true); return new Prompt(List.of()); });
```
种子：`SUSPENDED` 快照 + `inFlightToolCalls` = 一条 `APPROVED` / `executed=false` / `KIND_CONFIRM` / `irreversible=true` 条目（`isKindMisapproved` 只对 APPROVED+FRONTEND 告警，CONFIRM 不会被提前拦掉）。

**断言（2 条，都 RED）**：
1. `verify(toolCallingManager, never()).resolvePending(eq(RUN_ID), any(), any())` —— 取消后不得再派发不可逆工具。
2. `assertThat(store.get(RUN_ID).getStatus()).isEqualTo(RunSnapshot.CANCELLED)` —— 现状是 `:885/:890` 已把快照写成 `RUNNING`。

可选第 3 条（仅在需要"副作用级"证据时）：把 `toolCallingManager` 换成真实 `SpToolCallingManager`（参考 `ToolIdempotencyTest` 的装配）并断言 `store.ledger(RUN_ID)` 为空 —— 会让用例变大，默认不做。

**性质**：缺陷已证（代码可达、无任何取消复核）。**仍缺**：真实并发下"人点取消恰好落在重建窗口"的概率证据（审计把阻塞路径判 S3 毫秒级、续跑路径判 S2 宽窗口）；单测能把缺陷钉成确定性 RED，但不能替代对触发频率的运行证据。

---

## 2. park 取消无终态 / 无有界收流

**缺陷（已证）**：`AiController.cancel`（`AiController.java:878-906`）对 `SUSPENDED` 只做 `triggerResumeForParked`（`:896-901`）；该派发 → `ResumeService` 的取消分支（`:699-708`）只写 `CANCELLED` + `cancelPendings` 就 return，**全程不碰 `registry`/emitter**，所以归档里永远没有 `done{cancelled:true}`（终帧只在驱动线程走 `ResilientChatService.cancelTerminal`，`:1249-1300`）。客户端侧后果：`/events` 重挂时 `archivedTerminal=false`、`terminating=false` ⇒ `SseChatEmitter.java:695-701` 保留订阅；`AiController.events` 的 park 收流分支（`:766-792`）只认 `SUSPENDED`，`CANCELLED` 不落进去 ⇒ 连接挂到前端看门狗。另外 `dispatchResume` 的 skipped 分支（`ConfirmGate.java:1809/1833/1838`，及 `triggerResume` 的 `:1766/1770`）会让"快照仍 SUSPENDED、连 CANCELLED 都不保证"。

**测试缝（二选一，别两个都钉）**：
- **A（帧面，对应审计实测的"归档零终帧"）**：在 `ResumeContextCompactionTest` 把 `registry` 换成真实 `new RunRegistry(store, mapper, null)`（同 `HumanWaitParkTest`/`AiControllerEventsParkCloseTest`），`SseChatEmitter e = registry.of(RUN_ID); FrameWire wire = FrameWire.attachTo(e); e.start(SESSION_ID);`；stub `cancelState(RUN_ID) → CANCELLED`；调 `service.resume(RUN_ID)`。
  断言：`assertThat(FrameContract.types(wire.frames())).contains("done")`（+ 可选 `assertThat(store.get(RUN_ID).getStatus()).isEqualTo(CANCELLED)` 作回归保护）。现状 RED：只有 `start`。
- **B（收流面，若实现选择"有界收流"而不是补终帧）**：在 `AiControllerEventsParkCloseTest` 加一例：快照 `CANCELLED`、归档只有 `suspended/confirm_request/confirm_decision`、无 `done`；`controller.events(RUN_ID, null)`；断言 `registry.of(RUN_ID).subscriberCount()` 为 0。现状 RED（保持挂接 = 1）。
  断言条数：1（+1 可选）。

**性质**：缺陷已证，且审计已有归档运行证据（RT-04 的"归档零终帧"）。**无需新增运行证据**；只有"其它页签/刷新客户端挂到看门狗"这句结论若要写进验收，需一次真实 120s 客户端观察。**A/B 只钉一个**，否则等于强制两处都改。

---

## 3. `saveSuspended` 写权限失败静默

**缺陷（已证）**：`RunStore.saveSuspended`（`RunStore.java:1297-1318`）先 `putPending`（`:1306`）、再 `save`（`:1317`）；`save` 在 `authority.readOnly()` 时**只 WARN 就 return**（`:695-699`）。调用点 `SpToolCallingManager.java:470-474` 不检查任何结果 ⇒ 出现"待决条目已落库、快照仍 `RUNNING`"的半写态：违反类注承诺（`:1275-1278`"写失败必须抛出去"），后续 `/runs/current` 看不到挂起态、续跑入口按 `PENDING_UNRESOLVED` 永久 409。

**测试缝**：`backend/src/test/java/com/example/configmgr/ai/run/LeaseLossReadOnlyTest.java`（已有 `FakeLeaseRedis` + `LeaseService` A/B + `RunStore` A/B）。前置 5 行照抄该类 `lostLeaseTurnsTheOldHolderReadOnly` 的开头：`seedRun()` → `storeA.tryAcquireRunLease` → `fake.dropKey(LEASE_KEY)` → `storeB.tryAcquireRunLease` → `leaseA.renewState(a, ttl()) == LOST`。然后 `storeA.saveSuspended(RUN_ID, "assistant", List.of(pending(PENDING)), List.of(), codec)`。

**断言（2 条，都 RED）**：
1. 契约：调用**要么抛**、**要么**快照真的 `SUSPENDED` ——
   `boolean threw=…; assertThat(threw || SUSPENDED.equals(storeA.get(RUN_ID).getStatus())).isTrue()`。
2. 半写态禁止（核心）：`assertThat(storeA.pendings(RUN_ID)).as("条目已落库而快照未 SUSPENDED = 不可续跑的死卡").isEmpty()`（现状 1 条 + 状态 `RUNNING`）。

想断言"抛"的具体类型会让用例绑死实现；用 1+2 的组合只钉不变量，最省。
替代缝（装配层）：`ResumeProviderGoneTerminalTest` 里已有 `AuthoritySwitchingStore extends InMemoryRunStore` + `refuseWrites(authority)` 的写法可借用，但那是新类（Task 1–3 产物），优先用 `LeaseLossReadOnlyTest`。

**性质**：缺陷已证。**仍缺运行证据**：`LOST/UNAVAILABLE` 恰好落在挂起瞬间的现实可达性（CI 未观测到）；不影响 RED 的确定性。

---

## 4. `markRequestEmitted` 读失败

**缺陷（已证）**：`RunStore.markRequestEmitted`（`:1633-1649`）的**首次强读** `pending(runId, toolCallId)`（`:1634`）和**写后重读**（`:1647`）都在 try 之外；`pending` → `pendingRaw` 在 Redis 读失败时抛 `AiStateUnavailableException`（`:1423-1431`）。两处调用点 `ConfirmGate.java:509`（`publishRequestsForParkedBatch`）与 `:962`（`reserveRequestSlot`）都没有 try ⇒ 一次 HGET 失败 = 请求帧不公布（同批前几张已公布 ⇒ 半批卡）+ 本轮失败/条目留 `PENDING`，只能靠取消整轮。

**测试缝**：`backend/src/test/java/com/example/configmgr/ai/run/RunStoreRequestEmittedAtomicTest.java`（`FakeLeaseRedis` 已有一次性注入点 `afterPendingGet`，见 `FakeLeaseRedis.java:227`，正好在"读到之后、返回之前"）。**不需要改 harness**：
```java
fake.afterPendingGet = (key, field) -> { throw new IllegalStateException("HGET 失败（模拟）"); };
```

**断言（1 条 RED + 1 条防毒化）**：
1. `assertThatCode(() -> store.markRequestEmitted(RUN_ID, CALL_ID, 1_000L)).doesNotThrowAnyException()` —— 现状抛 `AiStateUnavailableException`。
2. 复位 hook 后再置位一次，`assertThat(store.pending(RUN_ID, CALL_ID).getReq()).isTrue()` —— 证明降级不是永久毒化（这一条现在就是绿的，作保护）。

注意：`afterPendingGet` 只在该 hash `get` 返回非 null 时触发；`markRequestEmitted` 路径上第一次非空读就是待决条目本身，因此注入是精确的（其余 `entries/putIfAbsent` 不受影响）。

**性质**：缺陷已证（无保护的强读 + 无 try 的调用点）。**仍缺运行证据**："一次 HGET 失败"的现实可达性（PK-18 = V1 静态）；单测用 hook 可确定性注入，不需要 Redis。

---

## 5. `session:current` 指针掩盖挂起卡

**缺陷（已证）**：`RunStore.save` 的单值指针（`:732-739`）：`RUNNING`/`SUSPENDED` 一律 `markSessionCurrent`（覆盖），任何终态一律 `clearSessionCurrent`（仅"值相等才删"，`:930-943`）。同会话第二轮（`SUSPENDED` 轮 park 期间用户再发一轮 —— 这条流程是产品承认的，见 `ResumeService.java:786-787` 的 `SESSION_BUSY` 文案）先把指针改成 `r-next`，第二轮结束再把指针删掉 ⇒ 第一轮的挂起卡从此**不可发现**（`/runs/current` 回 `found:false`，`AiController.java:997-1011`），刷新不重建、启动扫描也 skip；只有拿到 out-of-band runId 才能续跑。

**测试缝**：`backend/src/test/java/com/example/configmgr/ai/run/RunStoreSessionCurrentTest.java`（Map 支撑的 Redis mock，无租约设施 ⇒ `NEVER_HELD` 普通写，已有 `suspendedSaveWritesIndexWithSessionTtl` / `runningSaveKeepsIndex` / `clearOnlyDeletesMatchingRunId` 三例）。

**断言（2 条，第 1 条 RED）**：
```java
store.save(snapshot("r-parked", "s-1", SUSPENDED));
store.save(snapshot("r-next",   "s-1", RUNNING));
store.save(snapshot("r-next",   "s-1", DONE));
assertThat(store.sessionCurrentRunId("s-1")).isEqualTo("r-parked");   // 现状 null
store.save(snapshot("r-parked", "s-1", CANCELLED));
assertThat(store.sessionCurrentRunId("s-1")).isNull();                // 收敛方向（保护）
```

**性质**：缺陷已证，且路径已被文档/代码承认。**无需额外运行证据**。修复方向（"终态时回指本会话另一个非终态轮" vs "`/runs/current` 优先 `SUSPENDED`"）属 Task 8 实现裁决，本计划只钉"挂起卡不得因第二轮结束而消失"这一可观察契约。

---

## 执行与边界

- **只跑**五个栖身类（新增用例在其内）：`ResumeContextCompactionTest`、`AiControllerEventsParkCloseTest`（或 #2-A 变体所在的类）、`LeaseLossReadOnlyTest`、`RunStoreRequestEmittedAtomicTest`、`RunStoreSessionCurrentTest`。不跑全量、不新增 e2e/探针。
- 每个用例 1–3 条断言，全部**不依赖 sleep/线程时序**；#1 的时序用一个 flag 在 `buildResumedPrompt` 里翻转来建模。
- **不要**在本批顺带修 PK-06（`ParkSettlementAuditTest` 已在做）、RT-39（`ResumeProviderGone*Test` 已在做）或 RT-25（lenient 单条坏 entry），否则超出 Task 8 范围。
- **不要**改 Task 1 的配置语义、Task 2 的审计幂等、Task 3 的锁/销毁语义；`AiConfigSeeder`/`AiConfigWriter`/`SheetAudit`/`ErrorCodes`/messages 目前都带未提交改动，本批五个用例都不需要碰它们。
- 本计划**只是 RED 设计**，不含补丁、不含修复点选择，也未在任何文件落盘（`task-8-repro-plan.md` 尚不存在 —— 该目录当前只有 brief 与 task-6/7 的 report/patch）。
