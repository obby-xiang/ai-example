> **历史分析 / 过程证据 — 非现行基线，非验收结论**
>
> - 归档：2026-10-26 · `docs/evidence/history-recovery-2026-10-26/wire-tail/`
> - 来源：Kimi Code 会话 `<KIMI_SESSION>` / 子 agent `agent-255` 的 wire 末条 assistant 文本（消息序号 m=23；只读流式解析，非原始会话转写）
> - 历史时点：2026-10-10 09:41 → 2026-10-10 09:43（wire 事件时间窗，UTC+8）；当时模型：`DeepSeek/deepseek-flash`
> - 原基线：正文未出现基线哈希，未经本批复验
> - 原始体量：21219 B / 16161 字符 / 161 行；行指纹覆盖率（本批复算）2.5%（最高单文件 2%：`docs/spike/SP-02-决策卡.md`）
> - 类别：**过程核验**
> - 与既有件关系：复核报告；报告口径覆盖率 0.02
> - 脱敏：路径 1 / 会话标识 0 / 身份 0 / 密钥 0 / 命名纪律 0
> - 正文为该条消息的完整文本，未删段、未改写结论；仅脱敏、占位符归一与行尾空白规整。当时结论不代表当前代码状态，不得作为当前缺陷或有效裁决引用。
I have all the facts I need. Here is the reconnaissance report.

---

# 侦察报告：实时帧 `atMs` 覆盖 — 事实图

仓库根 `<REPO_ROOT>`。所有锚点为 `file:line`（相对仓库根）。

## 1. 后端 `SseChatEmitter`

### 1.1 类与 public 方法清单

`com.example.configmgr.ai.run.SseChatEmitter` — `backend/src/main/java/com/example/configmgr/ai/run/SseChatEmitter.java:91`（1017 行，`@Slf4j`，非 Spring bean，由 `RunRegistry` new）。

| 方法签名 | 行 |
|---|---|
| `SseChatEmitter(String runId, RunStore, ObjectMapper)` | `:175` |
| `SseChatEmitter(String, RunStore, ObjectMapper, Executor, int)` | `:180` |
| `SseChatEmitter(String, RunStore, ObjectMapper, Executor, int, int maxFrameBytes)` | `:185` |
| `static synchronized void configureSharedDeliveryPool(int, int)` | `:221` |
| `static Executor sharedDeliveryExecutorOrDirect()` | `:235` |
| `static Map<String,Object> deliveryPoolMetrics()` | `:241` |
| `String runId()` | `:251` |
| `void attach(SseEmitter)` | `:256` |
| `int replayAndAttach(SseEmitter, Long lastSeq, boolean terminal)` | `:316` |
| `int replayTo(SseEmitter, Long lastSeq)` | `:357` |
| `int subscriberCount()` | `:414` |
| `void start(String sessionId)` | `:421` |
| `void delta(String text)` | `:428` |
| `void messageBoundary(String phase, String kind, Map<String,Object> extra)` | `:448` |
| `boolean textStart()` | `:459` |
| `boolean textEnd(int chars)` | `:468` |
| `void suspended(List<PendingToolCall>, String stateKey, String pendingKey, String writtenBy)` | `:477` |
| `void heartbeat(Map<String,Object> data)` | `:488` |
| `void toolStart(PendingToolCall)` | `:494` |
| `void toolResult(PendingToolCall, boolean ok, boolean reused, String result)` | `:503` |
| `void confirmRequest(PendingToolCall, int timeoutSeconds)` | `:518` |
| `void confirmDecision(PendingToolCall, String decision, String reason, long waitedMs)` | `:530` |
| `void frontendToolRequest(PendingToolCall, int timeoutSeconds)` | `:542` |
| `void frontendToolResult(PendingToolCall, boolean ok, String result)` | `:555` |
| `void retry(Map<String,Object>)` | `:572` |
| `void done(Map<String,Object> usage, String model, Map<String,Object> extra)` | `:585` |
| `void error(String code, String message)` | `:600` |
| `void complete()` | `:904` |

包内可见（非 public，但是既有测试锚点）：`int maxFrameBytes()` `:197`、`int queueCapacity()` `:209`。

### 1.2 帧构造 → 发射链路（精确）

1. **帧对象构造**：每个 public 帧方法内 `Map<String,Object> frame = frame("<type>")`，再 `frame.put(...)` 业务字段，最后 `emit(frame)`。例：`SseChatEmitter#confirmRequest` `:519-527`。私有辅助 `SseChatEmitter#frame(String type)` `:610-614`（`new LinkedHashMap<>()` + `put("type", ...)`）。
2. **`atMs` 赋值点（唯一的）**：`RunStore#appendEvent` `backend/src/main/java/com/example/configmgr/ai/run/RunStore.java:576` → `frame.putIfAbsent("atMs", System.currentTimeMillis());`
   - 时钟：`System.currentTimeMillis()`（不是 `Instant.now()`；同文件 `appendLedger` `:451-452` 用 `System.currentTimeMillis()` + `Instant.now().toString()` 双写）。
   - `appendEvent` 只被一个调用点触发：`SseChatEmitter#emit` `:690`（全仓 grep 确认）。
   - **关键**：`SseChatEmitter.java` 内部对 `atMs` 零引用 —— 发射器完全不知道 `atMs` 存在。
3. **序列化点（共 3 处，均与"发送"无关）**：
   - 实时负载：`SseChatEmitter#emit` 非心跳分支 → `FrameSizeLimiter.apply(frame, objectMapper, maxFrameBytes)` `:684`，内部 `FrameSizeLimiter#serialize` `FrameSizeLimiter.java:194-201` → `mapper.writeValueAsBytes(frame)`。**该 `byte[]` 就是投向订阅者的最终原文**（`:697` 转 `String`）。
   - 实时心跳：`SseChatEmitter#emit` 心跳分支 → `objectMapper.writeValueAsBytes(frame)` `:670`（**不走** `FrameSizeLimiter`）。
   - 归档：`RunStore#appendEvent` `:578` → `this.mapper.writeValueAsString(frame)`（在 `:576` 补 `atMs` **之后**）。
   - 回放：`SseChatEmitter#stageReplay` `:391` → `objectMapper.writeValueAsString(frame)`（读归档，已含 `atMs`）。
4. **`SseEmitter.send` 唯一调用点**：`SseChatEmitter#drain` `:796` → `sub.emitter.send(frame.payload)`，发的是**已序列化的 `String`**（`QueuedFrame.payload`，`SseChatEmitter.java:1003-1015`）。发送在共享投递池线程（`submitDrain` `:760-768`）。
5. **顺序铁证（`emit` 内，锁内）**：`frame.putIfAbsent("runId")` `:655` → `synchronized(emitLock)` `:659` → 心跳分支 `:661-676` / 非心跳分支 `frame.put("seq", nextSeq())` `:678` → `FrameSizeLimiter.apply` `:684` → `store.appendEvent` `:690`（此处才补 `atMs`）→ 扇出入队 `:698-710`。
   → **实时帧的字节在 `:684` 之前定型，`atMs` 在 `:690` 才写入 ⇒ 实时帧不含 `atMs`**。这正是排期计划 `docs/M2-排期计划.md:178` 的文字结论，现已用代码坐实。
6. **队列/缓冲机制**：每订阅者一条无界 `LinkedBlockingQueue<QueuedFrame>`（`Subscription` 内部类 `:951-993`）+ 实时段计数闸 `liveQueued`（`enqueueLive` `:729-736`，容量 `queueCapacity` 缺省 256）+ CAS `draining` 独占标志（`kickDrain` `:751`）。无批量 flush、无帧聚合；心跳由运行线程按周期显式调用（`ConfirmGate#await`，非本类）。回放批豁免容量（`stageReplay` `:379-400`）。
7. **帧类型清单（`type` 字面量，构造点即证据）**：`start` `:422`、`delta` `:429`、`message_start`/`message_end`（`"message_" + phase`）`:449`、`suspended` `:478`、`heartbeat` `:489`、`tool_start` `:496`、`tool_result` `:504`、`confirm_request` `:519`、`confirm_decision` `:531`、`frontend_tool_request` `:543`、`frontend_tool_result` `:556`、`retry` `:573`、`done` `:586`、`error` `:601`。前端同集白名单见 `frontend/src/types/sse.ts:59-75`。

**哪些类型带 `atMs`（现状）**：
- 带：所有**归档帧** = 全部业务类型 **减去 `delta`**（`RunStore#appendEvent` `:568-575` 对 `heartbeat` 与 `delta` 直接 `touchActivity` 后 return）。
- 不带：`heartbeat`（从不归档）、`delta`（跳过归档，`RunStore.java:88` 常量 `DELTA_TYPE`）、以及**所有实时帧**（含 `confirm_request` / `frontend_tool_request`）。
- 语义结论：**今天只有"归档 / 回放"路径含 `atMs`，实时路径全部不含** —— 与红队 S3-2 / 排期计划一致。

## 2. 消费侧（前端，只读）

- `frontend/src/stores/ai.ts:85-98` `foldDeadline(expiresAt?, atMs?, timeoutSeconds?, nowMs = Date.now())`：
  - `:91` 有 `expiresAt` 且有限 → `:92` 若 `atMs` 有限且 `expiresAt >= atMs` → `:93` 返回 `nowMs + (expiresAt - atMs)`（服务端剩余时长）；否则 `:95` 返回绝对 `expiresAt`；`:97` 再回落 `nowMs + timeoutSeconds*1000`，全缺 `null`。
  - 注释 `:76-78` 明写「**实时帧即走此分支**」。
- `frontend/src/stores/ai.ts:946-987` `openGenerativeForm(...)`：`:971` `const deadline = foldDeadline(frame.expiresAt, frame.atMs, frame.timeoutSeconds)`；`:980-986` `setTimeout(..., Math.max(0, deadline - Date.now()))` 武装本地过期。帧来源 `frontend_tool_request`（`:1287` 同族路径 `:1243` 走 `toPendingCall`）。
- `frontend/src/stores/ai.ts:1399-1416` `toPendingCall(...)`：`:1411` `expiresAt: frame.expiresAt`、`:1413` `atMs: frame.atMs`（原样透传）。
- 类型定义：`frontend/src/types/sse.ts:90-96`（`SseFrameBase.atMs?: number`，注释明写"仅归档/回放帧带"）；`frontend/src/types/sse.ts:165-171`（`SseConfirmRequestFrame.expiresAt`）、`:196`（`SseFrontendToolRequestFrame.expiresAt`）；`frontend/src/types/ai.ts:208-218`（`PendingToolCall.expiresAt` / `atMs`）、`:251-252`（`ActiveGenerativeForm.deadline` 口径注释）。
- `frontend/src/components/AiPanel/AiPanel.vue#resetPendingWatch` `:558-569`：`watch(() => ai.pendingConfirm)`（`:585-589`，`immediate: true`）的回调 —— 挂起调用换人时清 `rejectReason`、非 pending 则置 `confirmDeadline = null`，否则 `:568` 用 `foldDeadline(pending.expiresAt, pending.atMs, pending.timeoutSeconds)` 重算本地倒计时 deadline。倒计时秒数消费点 `:396-399`。
- 心跳内容前端忽略（`frontend/src/stores/ai.ts:1357-1359`）。
- 补充事实（设计相关）：从快照恢复的挂起投影不填 `expiresAt`/`atMs`（`ai.ts:1346-1353`，`restoreSuspendedSnapshot` 路径）—— 该路径 `foldDeadline` 必返回 `null`（`timeoutSeconds` 亦缺），本地不武装倒计时。若实时帧补 `atMs`，此路径仍无基准。

## 3. 现有测试

### 3.1 后端 SSE 帧相关测试类（`backend/src/test/java/com/example/configmgr/`）

| 类 | 包 | 测试方法 |
|---|---|---|
| `SseChatEmitterFramesTest` `ai/run/SseChatEmitterFramesTest.java:37` | `ai.run` | `everyFrameCarriesMonotonicSeq` `:48`、`seqContinuesAcrossProcessRestart` `:62`、`suspendRequestsCarryAbsoluteExpiry` `:74`、`toolResultFramesCarryMessageId` `:93`、`textSegmentHasStartContentEndBoundaries` `:104`、`heartbeatIsBroadcastButNotArchived` `:124`、`replayWithLastSeqSendsOnlyNewFrames` `:147`、`replayWithoutLastSeqStaysFull` `:164`、`subscriberCountTracksAttachment` `:213`（9 个，与 surefire XML `tests="9"` 相符） |
| `SseChatEmitterDeliveryTest` `ai/run/SseChatEmitterDeliveryTest.java:70` | `ai.run` | `slowSubscriberDoesNotBlockOthersInTheSameRound` `:89`、`liveQueueOverflowEvictsTheSubscriberAndCompletesTheEmitter` `:118`、`replayBatchBypassesLiveCapacityUpToTheEventWindow` `:151`、`liveFramesDoNotEvictAReattachingSubscriberWhileTheReplayBacklogIsDraining` `:185`、`liveSegmentOverflowDuringTheReplayBacklogStillEvictsTheSubscriber` `:218`、`drainTailRaceNeverLosesFramesWhenEnqueueOverlapsDrainExit` `:241`、`evictedSubscriberCanReattachAndBackfillWithoutSeqGaps` `:292`、`seqRecordFailureOnlyWarnsAndKeepsEmitting` `:325`、`terminalCompleteDrainsQueuedTailFramesBeforeClosingTheSubscriber` `:344`、`terminalCompleteUnderASaturatedDeliveryPoolStillDeliversEveryTerminalFrame` `:368`、`rejectedDrainSubmissionEvictsOnlyTheRejectedSubscriber` `:403`、`twoArgRegistryAlwaysDeliversInlineAndNeverReadsTheSharedPool` `:448`、`liveQueueCapacityStaysBelowTheEventWindowAndComesFromASingleSource` `:472`、`timeoutCallbackClearsTheSubscriberQueueAndResetsDeliveryState` `:495`、`completionCallbackClears...` `:500`、`errorCallbackClears...` `:505`、`terminalCompleteOnAnIdleDrainedSubscriberCompletesInline` `:550`、`terminalCompleteWithFramesWaitingRunsItsOwnDrainBeforeClosing` `:576` |
| `SseFrameSizeLimitTest` `ai/run/SseFrameSizeLimitTest.java:31` | `ai.run` | `oversizeToolResultIsTruncatedAtFieldLevelAndStillReplayable` `:46`、`oversizeDeltaPayloadIsTruncatedToo` `:68`、`truncatedFrameCarriesTruncatedAndReason` `:84`、`smallFrameIsUntouched` `:100`、`boundaryAtExactLimitIsNotTruncatedButOneByteOverIs` `:120`、`oversizedFramesStillConsumeContiguousSeqs` `:143` |
| `FrameContractFalsificationTest` `ai/conformance/FrameContractFalsificationTest.java:25` | `ai.conformance` | 校验器负向 fixture（`acceptsAConformantTurn`、`killsSeqGapAndMissingSeq` …；**无** `atMs` 相关规则） |

**没有任何测试断言 `atMs`**（全仓 `atMs` grep：test 目录仅 `InMemoryRunStore.java:254,294-295,330,344-345`，均为台账/归档替身实现，非断言）。

### 3.2 技术栈与惯例

- JUnit 5（`org.junit.jupiter.api.Test`）+ Mockito（`mock`/`verify`/`ArgumentCaptor`/`when`/`never`）+ AssertJ（`org.assertj.core.api.Assertions.assertThat`）。无 Spring 容器（纯单测）。
- 测试包布局与主代码同构：`backend/src/test/java/com/example/configmgr/` 下 `ai/{config,conformance,form,gate,run,tool,tools,web}`、`common`、`data`、`definition`、`job`、`task`。
- 共享替身 `InMemoryRunStore extends RunStore`（`ai/conformance/InMemoryRunStore.java:46`；`RunStore` 是 **class** 不是 interface，`RunStore.java:60`），含 `appendEvent` 语义镜像 `:323-332`（`copy.putIfAbsent("atMs", System.currentTimeMillis())` `:330`）。
- 实时帧线录帧工具：`FrameWire`（`ai/conformance/FrameWire.java:31`，`attachTo(SseChatEmitter)` `:70`，`frames()` `:91`，`normalize` `:109`）—— 录 `SseEmitter.send` 的 JSON 原文并反序列化，数字归一到 `Long`。
- 典型写法（`SseChatEmitterFramesTest.java:41-58` 节选，≤15 行）：
  ```java
  private final RunStore store = mock(RunStore.class);
  private final SseChatEmitter emitter = new SseChatEmitter(RUN_ID, this.store, new ObjectMapper());

  @Test
  void everyFrameCarriesMonotonicSeq() {
      this.emitter.start("s-1");
      this.emitter.delta("你好");
      this.emitter.done(Map.of(), "deepseek-flash", Map.of("cancelled", false));
      List<Map<String, Object>> archived = archivedFrames();
      assertThat(archived).extracting(f -> f.get("type")).containsExactly("start","delta","done");
  ```
  （`archivedFrames()` 用 `ArgumentCaptor<Map<String,Object>>` 捕 `appendEvent`，`:185-189`。）
- 与本次改动最贴近的既有用例：`suspendRequestsCarryAbsoluteExpiry` `:74-88`，断言 `expiresAt` 落在 `[before+120000, after+120000]` —— 补 `atMs` 后在此处加 `expiresAt - atMs == 120_000` 断言最自然。

### 3.3 前端测试框架

**确认没有。** `frontend/package.json` 只有 `dev / build / typecheck / preview` 四个脚本，无 `test`；devDependencies 无 vitest / jest / @vue/test-utils；仓库无 `*.spec.*` / `*.test.*` 文件。与排期计划 `docs/M2-排期计划.md:183` 记载一致。

## 4. 口径核查

- **Surefire XML 报告：存在。** 目录 `backend/target/surefire-reports/`，55 个 `TEST-*.xml`（mtime `Oct 9 23:07`）。聚合：**tests = 358，failures = 0，errors = 0**。与排期计划 `docs/M2-排期计划.md:199`「后端全量 357 → **358**」完全吻合（未跑构建，仅读 XML）。
- **`atMs` 在后端的全部出现位置（main + test）**：
  - 生产代码 `backend/src/main/java/com/example/configmgr/ai/run/RunStore.java`：`:451`（`appendLedger` 台账 `atMs`）、`:509`（注释）、`:516-517`（`ledgerCountSince` 读台账 `atMs`）、`:555`/`:652`/`:657`（注释）、**`:576`（帧落档 `putIfAbsent`，本议题核心）、`:666-667`（`lastEventAtMs` 读归档末帧）**。
  - 测试代码：`ai/conformance/InMemoryRunStore.java:254`、`:294-295`、`:330`、`:344-345`。
  - 其他：`spike/sp02/src/main/java/com/example/spike/core/RunStore.java:196`（独立 spike 模块，不属 backend）；`spike/sp02/scripts/sp02-experiment.mjs:156,325,599`（spike 脚本）。
  - **`SseChatEmitter.java` / `FrameSizeLimiter.java` 中 0 处引用 `atMs`**；`ResumeService.java:273` 仅注释提及。
- **`atMs > 0` 守卫：不存在（前后端均无）。** 唯一的 `atMs` 合法性判定是 `frontend/src/stores/ai.ts:92` 的 `typeof atMs === 'number' && Number.isFinite(atMs) && expiresAt >= atMs`。排期计划 `:183` 把"补 `atMs>0` 合理性守卫"列为 T4-3 后半的随行项。

## 5. `docs` 中 T4-3 相关条目

- `docs/M2-T3-设计卡.md`：**无** T4-3 / `atMs` / 实时帧落点条目（grep 仅命中 `:41` 一条"实时闸按实时段计数"施工注记，与 `atMs` 无关）。
- `docs/M2-排期计划.md` 三处（均为"去向表/闭环注记"，摘录关键句）：
  - `:178`（去向表主行）｜**实时 SSE 帧的 `atMs` 覆盖（T4-3 后半；T4-3 前半已完成）**｜**下批专项卡**（裁决②，2026-10-09）：`atMs` 由 `RunStore#appendEvent` 在**落档时**补 `putIfAbsent`，而 `SseChatEmitter#emit` 的 `payloadBytes` **早于**该落档序列化 ⇒ 实时帧不含 `atMs`……补齐需调整后端发射器的「序列化 ↔ 落档补 `atMs`」顺序，**超卡片三处落点** → 转下批专项卡，本批禁止做。
  - `:183`（S3-2 观察项）｜负 `atMs` 线性膨胀（A=−5000 → 剩余 +5000ms）；**`atMs=0` 时绝对 epoch 被当剩余时长**（E=1.7e12、A=0 → deadline≈2079 年）……**处置**：随 **T4-3 后半**（实时帧 `atMs` 专项卡）一并补单测/测试基建评估 + `atMs>0` 合理性守卫。
  - `:197` / `:209`（M2-T4 闭环注记）｜条目 14（T4-3）状态 **BLOCKED →【待裁决】**……去向 = 待裁决 →（a）独立棒次实现三处落点；或（b）接受"仅归档回放帧生效"的中间口径。

---

## 结论：改"序列化顺序"的改动点清单（类#方法级）

**核心事实**：`atMs` 今天由 `RunStore#appendEvent` 在归档时写入，而实时负载字节在 `SseChatEmitter#emit` 内**先于**该调用定型（`:684` vs `:690`），且 `drain`/`send` 只搬运已序列化字符串（`:796`）——**发送时刻无法晚绑定 `atMs`，不需要重新序列化**，唯一可行落点是 `emit` 内的序列化前。因此"改序列化顺序"实质是**把 `atMs` 的赋值与时钟取值前移进发射器**。

必须动：

1. `SseChatEmitter#emit`（`SseChatEmitter.java:654`）非心跳分支：在 `FrameSizeLimiter.apply` `:684` **之前**（建议紧随 `frame.put("seq", nextSeq())` `:678` 之后）插入 `frame.putIfAbsent("atMs", System.currentTimeMillis())`。落点在 `:684` 之前是**强制**的：`payloadBytes` 在 `:684` 定型，之后写 `atMs` 对投递无效（这正是当前缺陷形状）；同时语义上「归档 = 投递同一份帧」由 `:681-682` 的注释明确要求，`atMs` 在同一 `Map` 上只会取一次值，归档与实时必然一致。
2. `RunStore#appendEvent`（`RunStore.java:576`）：改动后此处 `putIfAbsent` 对发射器来帧退化为 no-op（保留作其他/未来调用方兜底；全仓确认当前唯一调用点是 `SseChatEmitter:690`）。
3. **时钟一致性（设计要点，非直接改动）**：`expiresAt` 由 `SseChatEmitter#expiresAtEpochMs`（`:877-879`，`System.currentTimeMillis()`）在**帧构造方法内**取值（`confirmRequest` `:525`、`frontendToolRequest` `:550`），而 `atMs` 若在 `emit` 内取值，二者之间夹着 `nextSeq()` 的 Redis `SET ai:seq`（`:645`）与 `emitLock` 争用 —— 于是 `expiresAt - atMs` 会比 `timeoutSeconds*1000` **少掉这段延迟**（ms 级），`foldDeadline` 折算出的剩余时长偏短。若要口径精确，需让两者共用一个时刻（例如帧构造时取一次 `now`，把 `atMs` 一并放入帧，`emit` 只做 `putIfAbsent` 兜底）——这会同时触及 `SseChatEmitter#confirmRequest` / `#frontendToolRequest` / `#expiresAtEpochMs`。
4. `SseChatEmitter#emit` 心跳分支（`:661-676`）：若要求"所有实时帧统一带 `atMs`"（或为将来的服务端钟同步留基准），需在 `:670` 序列化**之前**同样补 `atMs`；注意该分支**不走** `FrameSizeLimiter`，字节预算语义与业务帧不同。前端今日忽略心跳内容（`ai.ts:1357-1359`），非必需项。
5. 前端 `foldDeadline`（`frontend/src/stores/ai.ts:92`）：随行落 `atMs > 0` 守卫（排期计划 `:183` 明确挂靠 T4-3 后半）；落地后是**结构性行为变更**（实时帧从回落分支切换到折算分支）。
6. 注释/文档同步（现状描述已失真，须一并改）：`frontend/src/types/sse.ts:90-96` 与 `:167-168`、`frontend/src/types/ai.ts:214-218`、`frontend/src/stores/ai.ts:76-78`、`frontend/src/components/AiPanel/AiPanel.vue:564-567`、`docs/M2-排期计划.md:178/:183/:197`；`SseChatEmitter#emit` 的类注释（`:654-658` 附近）也该记录 `atMs` 顺序口径。

测试面：

7. 新增/扩展用例首选 `SseChatEmitterFramesTest`（`ai/run/SseChatEmitterFramesTest.java`）：在既有 `suspendRequestsCarryAbsoluteExpiry` `:74` 邻位加"实时帧带 `atMs` 且 `expiresAt - atMs == timeoutSeconds*1000`"断言；实时路径用 `FrameWire`（`ai/conformance/FrameWire.java:31`，录 `send` 原文）或 `mock(SseEmitter.class)` + `ArgumentCaptor` on `send`（该文件已有 `heartbeatIsBroadcastButNotArchived` `:124` 的现成模式）。
8. 需回归确认的既有用例：`SseChatEmitterFramesTest#heartbeatIsBroadcastButNotArchived` `:124`（断言 `doesNotContain("\"seq\"")`，只查 `seq`，不受 `atMs` 影响）；`SseFrameSizeLimitTest` 全部用例（直接测 `FrameSizeLimiter`，不受影响；但 `atMs` 约 +34 字节会改变业务帧的可用预算 —— 预算由 `FrameSizeLimiter#overhead` `:111-120` 动态计算，无硬编码，安全）；`ai/conformance` 各一致性测试（`FrameContract` 无字段白名单/`atMs` 规则，安全）；`InMemoryRunStore#appendEvent` `:323-332` 会自然观察到发射器传入的 `atMs`。
9. 无测试基建面：前端**无 vitest/jest**（`frontend/package.json` 无 `test` 脚本与相关依赖），故 `foldDeadline` 的折算与 `atMs>0` 守卫**无法以前端单测覆盖**，需按排期计划 `:183` 先做"测试基建评估"，或落到 e2e/后端帧契约断言。

已知边界（供设计权衡）：从快照恢复的挂起投影（`ai.ts:1346-1353`）不填 `expiresAt`/`atMs`，实时帧补 `atMs` 不改变该路径仍无基准的事实；`delta` 帧不归档（`RunStore.java:568-575`），前端不对其做任何折算，故 `delta` 是否补 `atMs` 无消费方。
