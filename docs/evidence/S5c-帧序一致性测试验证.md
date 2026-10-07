# S5c 帧序一致性测试验证（AI SSE 帧协议 conformance 资产）

> 日期：2026-10-07　执行：施工子智能体（DeepSeek V4 Flash）　仓库：`<REPO_ROOT>`（分支 `main`，HEAD `972182b`）
> 范围：**只新增测试资产**（`backend/src/test`）+ 本证据文档。**未改任何 `src/main` 生产代码**，未做任何 git 写操作。
> 结论：新增 **42** 个 JUnit 用例（6 个测试类）+ 3 个测试替身/校验器；全量 **147** 用例（原 105 基线 + 42）连续两轮全绿；
> 过程中发现 **6 项帧协议问题**（全部如实记录，未修改生产代码绕行）。

---

## 1. 资产形态与文件清单

主体为 JUnit（纳入 `<REPO_ROOT>/backend/src/test`，与既有 105 例同一条 `mvn test` 管线、未来可直接进 CI）。
模型调用一律桩化（沿用仓库既有 AI 测试的写法：Mockito 桩 + 无 Spring 上下文），**不需要 Redis、不需要 HTTP 端口、不需要 AI key**。

| 文件（`<REPO_ROOT>/backend/src/test/java/com/example/configmgr/ai/conformance/`） | 角色 | 用例数 |
|---|---|---|
| `FrameWire.java` | **帧线**：把 `SseChatEmitter` 真正写到 `SseEmitter` 上的 JSON 原文按到达顺序录下来并解码（含可阻塞的"到达前钩子"，用于把并发时序确定化） | 替身 |
| `FrameContract.java` | **不变量校验器**：6 条序列规则的判据本体（seq / 终态 / 工具配对 / 正文三段式 / 因果顺序 / 终帧与终态同向） | 校验器 |
| `InMemoryRunStore.java` | **外置状态替身**：按真实 `RunStore` 语义重写（心跳不落档、末帧取号、认领先于执行、台账即重放判据），并补铺语料/取证的辅助方法 | 替身 |
| `FrameContractFalsificationTest.java` | **负向 fixture**：15 条违规流，逐条证明每条规则"会红" | 15 |
| `SseFrameSequenceConformanceTest.java` | ① ② ④ 在写出器层（心跳占号不落档 / 三段式 / 终帧收尾）+ 2 条钉住现行为 | 7 |
| `ReattachLastSeqConformanceTest.java` | ⑤ `?lastSeq=` 差量重挂（不重复、不丢失、保序、老帧兜底、跨进程续号） | 6 |
| `ToolFrameSequenceConformanceTest.java` | ③ ⑥ ⑦ 工具配对 / 前端通道挂起→回灌 / 确认门两路（跑**真实** `SpToolCallingManager` + 真实 `ConfirmGate`）+ 1 条钉住现行为 | 7 |
| `ChatTurnFrameSequenceConformanceTest.java` | ① ② ④ 在**真实编排层**（`ResilientChatService.driveResumed` + 脚本化假上游）+ 1 条钉住现行为 | 4 |
| `SessionBusyFrameIsolationConformanceTest.java` | ⑧ 409 会话串行化时的帧流隔离 + ⑤ 重挂端点参数管线 | 3 |

---

## 2. 不变量 → 用例映射表

| # | 不变量（任务书） | 用例（类.方法） |
|---|---|---|
| ① | seq 单调递增、无缺口（单次运行内） | `SseFrameSequenceConformanceTest.singleSubscriberSeesContiguousSeqAndNothingAfterTheTerminalFrame`、`heartbeatConsumesSeqButLeavesOnlyAnActivityStampInTheArchive`、`FrameContractFalsificationTest.killsSeqGapAndMissingSeq`、`killsSeqRegressionAndDuplicate`、`ChatTurnFrameSequenceConformanceTest.fullTurnFrameSequenceIsConformantAndDeltaConcatMatchesTheSettledText` |
| ② | delta 帧顺序与拼接一致 | `SseFrameSequenceConformanceTest.textSegmentBoundariesBracketTheDeltasExactlyOnce`、`ChatTurnFrameSequenceConformanceTest.fullTurnFrameSequenceIsConformantAndDeltaConcatMatchesTheSettledText`、`FrameContractFalsificationTest.killsMissingMessageEnd`、`killsCharsMismatchAgainstDeltaSum`、`killsReopenedTextSegment` |
| ③ | `tool_start` 必有配对 `tool_result`（或确认的挂起态） | `ToolFrameSequenceConformanceTest.approvedConfirmPath…`、`rejectedConfirmPath…`、`outOfScopeToolIsPairedWithASingleResultFrameWithoutAnySideEffect`、`parallelToolCallsKeepExactlyOneOutcomeFrameEach`、`frontendToolSuspendsBackfills…`、`FrameContractFalsificationTest.killsUnpairedToolStart`、`killsTwoOutcomeFramesForOneToolStart` |
| ④ | 终态帧之后不再有业务帧 | `SseFrameSequenceConformanceTest.singleSubscriber…`、`errorTerminalIsAlsoTheLastFrame`、`pinsOpenTextSegmentOnTheErrorPath`、`ChatTurnFrameSequenceConformanceTest.cancelledTurnEndsWithDoneCancelledAndClosesTheTextSegment`、`incompleteUpstreamRetriesThenEndsWithAnErrorTerminal`、`FrameContractFalsificationTest.killsFramesAfterTheTerminalFrame`、`killsDuplicateTerminalFrames`、`killsMissingStartFrame` |
| ⑤ | `?lastSeq=` 重挂：只补发 lastSeq 之后、seq 连续、无重复无丢失 | `ReattachLastSeqConformanceTest` 全 6 例（`deltaReplaySendsOnlyFramesAfterLastSeqWithoutLossOrDuplication`、`replayFromAHeartbeatSeqOnTheHoleLosesNothing`、`replayWithLastSeqAtTheArchiveTailSendsNothing`、`replayWithoutLastSeqKeepsTheLegacyStateOnlyShape`、`legacyFramesWithoutSeqAreNeverTreatedAsOrphans`、`seqContinuesFromTheArchiveTailAcrossAProcessRestart`）、`SseFrameSequenceConformanceTest.pinsHeartbeatSeqReuseAcrossEmitterRestart…`、`ChatTurnFrameSequenceConformanceTest.fullTurn…`（回放能把整轮拼回来）、`SessionBusyFrameIsolationConformanceTest.reattachEndpointAcceptsLastSeqAndRejectsUnknownRuns` |
| ⑥ | 前端通道工具挂起→回灌→续跑（`executed=false` 不重复执行） | `ToolFrameSequenceConformanceTest.frontendToolSuspendsBackfillsAndEmitsExactlyOneResultFrameWithoutBackendExecution`（含"重复回灌 → DUPLICATE 且不再出帧"） |
| ⑦ | 确认门两路（确认/拒绝）的帧序 | `ToolFrameSequenceConformanceTest.approvedConfirmPathEmitsRequestThenDecisionThenResultAndExecutesExactlyOnce`、`rejectedConfirmPathEmitsResultWithoutExecuting`、`duplicateDecisionIsRejectedWithoutASecondDecisionFrame`、`FrameContractFalsificationTest.killsConfirmOrderingInversion`、`pinsDecisionReceiptArrivingAfterItsConsequence` |
| ⑧ | 409 SESSION_BUSY 时正在运行的会话帧流不受干扰 | `SessionBusyFrameIsolationConformanceTest.sessionBusy409LeavesTheRunningTurnsFrameStreamUntouched`（跑真实 `AiController` + 真实 `SessionGate` + 真实 `RunRegistry`）、`afterTheTurnEndsTheSameSessionRunsAgainWithoutCrossTalk` |

补充：`FrameContract.terminalConsistencyContract`（终帧类型与终态同向，`cancelled` 必须是布尔）由
`FrameContractFalsificationTest.killsNonBooleanCancelledFlag` 钉住。
`FrameContract.orderingContract` 里的"`retry` 只允许出现在首次正文产出之前"由
`ChatTurnFrameSequenceConformanceTest.incompleteUpstreamRetriesThenEndsWithAnErrorTerminal`（正向）与
`FrameContractFalsificationTest.killsRetryAfterContentWasEmitted`（负向）双侧覆盖。

---

## 3. 方法论：借 ag-ui 的判据形态，不借它的协议

只读参照 `<REPO_ROOT>/research-src/ag-ui/spec/1.0/conformance`（68 个 fixture + `README.md`，未修改）。借了四件事：

1. **一份帧流 = 一个用例**：断言对象是"给定事件流，序列不变量是否成立"，而不是"某方法被调了几次"。
   本资产里 `FrameContract.violations(frames)` 就是这条：一次跑满 6 条规则，失败信息一次列全。
2. **每条断言必须写清 kill**（ag-ui fixture 的必填字段，原文："一个不可能失败的 fixture 比没有 fixture 更糟：
   它读起来像覆盖率，却什么也没证明"）。`FrameContract` 的每类规则 Javadoc 都注明"哪一行实现改动会让它红"，
   且 `FrameContractFalsificationTest` 用**手工构造的违规流**逐条证明规则真有牙齿（对应其 `kill` 字段的用法）。
3. **承认的缺口也当成一条 fixture 钉住**（ag-ui 的 `unknown-enum-value-role-fatal` 同款手法：故意钉一条与规范相悖的现行为，
   让"关闭缺口"成为一次有意的动作，而不是悄悄消失）。本资产用 `pins…` 前缀的用例钉住 §6 的 S5c-1/3/4/5/6。
4. **把"客户端判定"与"运行自身失败"分开**（ag-ui 的 `outcome` vs `runError`）。本协议里 `done`
   （含 `cancelled=true`）与 `error` **都是合法终帧**：`assertConformant` 不会因为出现 `error` 而失败，
   只会因为它出现在错误的位置（终帧之后还有帧 / 一轮两个终帧）而失败。

**明确不借的东西**：不引入 AG-UI 的事件模型、`RUN_*` 事件名、协议版本协商、era shim（DC-14 已裁决 main 不切换 AG-UI）。
本资产只把同一套"事件流 + 序列不变量 + kill"的判据形态，套在 main 自己的帧类型上。

---

## 4. 运行结果

工具链：`<MAVEN_HOME>/bin/mvn.cmd`（IntelliJ 自带 Maven），工作目录 `<REPO_ROOT>/backend`，JDK 21。
未起 HTTP 端口；未使用 Redis（`<MEMURAI_HOME>/memurai-cli.exe ping` → `PONG`，但本资产全程不需要 Redis）。

```
# 全量（含基线 105）
cd <REPO_ROOT>/backend
"<MAVEN_HOME>/bin/mvn.cmd" -o test

# 只跑本次新增的帧序一致性资产
"<MAVEN_HOME>/bin/mvn.cmd" -o test -Dtest='*ConformanceTest,FrameContractFalsificationTest'
```

全量两轮原始输出（逐类）：

| 轮次 | 结果 |
|---|---|
| 第 1 轮 | `Tests run: 147, Failures: 0, Errors: 0, Skipped: 0` → **BUILD SUCCESS** |
| 第 2 轮 | `Tests run: 147, Failures: 0, Errors: 0, Skipped: 0` → **BUILD SUCCESS** |

本次新增用例的逐类计数（`^\s*@Test(\s*$|\()` 精确匹配，规避 `@TestPropertySource` 等虚增陷阱）：

| 测试类 | Tests | Failures | Errors |
|---|---|---|---|
| `FrameContractFalsificationTest` | 15 | 0 | 0 |
| `SseFrameSequenceConformanceTest` | 7 | 0 | 0 |
| `ReattachLastSeqConformanceTest` | 6 | 0 | 0 |
| `ToolFrameSequenceConformanceTest` | 7 | 0 | 0 |
| `ChatTurnFrameSequenceConformanceTest` | 4 | 0 | 0 |
| `SessionBusyFrameIsolationConformanceTest` | 3 | 0 | 0 |
| **新增小计** | **42** | 0 | 0 |
| 全量合计（含原 105 基线） | **147** | 0 | 0 |

计数命令与结果：

```
$ grep -rnE "^\s*@Test(\s*$|\()" backend/src/test --include=*.java | wc -l
147
```

**基线未被破坏**：`git status --short` 只有一处未跟踪目录 `backend/src/test/java/com/example/configmgr/ai/conformance/`；
`src/main` 零改动；HEAD 仍为 `972182b`（本棒无 git 写操作）。

---

## 5. 覆盖边界（JUnit 层覆盖不了的部分，如实声明）

| 未覆盖 | 原因 | 现有替代证据 |
|---|---|---|
| **真实 HTTP/SSE 传输层**（`SseEmitter` 序列化、`produces=text/event-stream`、`curl -N` 原文） | 需要真后端 + Redis + 真实模型；仓库无 `@SpringBootTest` 基础设施，AI 端点在无 key 时 503，起容器也测不到帧 | `docs/evidence/DC14-微调实施验证.md` §3.6（真实 `curl`：`?lastSeq=2` 回放 2 帧 vs 无参 4 帧，附 SSE 原文） |
| **`GET /api/ai/events/{runId}` 回放帧的内容** | controller 内部自己 `new SseEmitter(...)`，JUnit 截不到那条帧线 | 写出器层逐帧断言见 `ReattachLastSeqConformanceTest`（6 例）；端点分支只断言 200 / 404 `UNKNOWN_RUN`（`SessionBusyFrameIsolationConformanceTest` 第 3 例） |
| **真实进程重启后的续跑** | 需要真进程 + Redis 存活态 | 本资产用"归档尾帧 + 新写出器 + 已计入 lastSeq 的前端"模拟（`ReattachLastSeqConformanceTest.seqContinuesFromTheArchiveTailAcrossAProcessRestart`、`SseFrameSequenceConformanceTest.pinsHeartbeatSeqReuse…`）；真实链路见 `docs/evidence/S42-P2-挂起续跑会话门验证.md`、`S42-P3` |
| **真实模型的非确定性帧序**（模型是否分片、分片是否含纯空白） | 需要真实上游 | S5b 的 `scripts/verify-e2e.ps1` 22 用例（真实 AI 驱动）负责这一层；本资产负责把它的断言口径固化成可离线复跑的不变量 |

因此"不变量全覆盖"的准确表述是：**写出器层与编排层的帧序不变量已全覆盖（8/8），传输层与跨进程层由既有真实链路证据覆盖，两者合起来才是完整证据面。**

---

## 6. 发现的帧协议问题（不擅自裁决，均未修改生产代码）

> 全部结论都可用本资产复跑：带 `pins` 前缀的用例就是每个问题的**可复现判决书**。

### S5c-1　心跳占号 + 续号锚点=归档末帧 ⇒ 跨进程续跑重号，差量重挂漏帧

- **事实链**：① `SseChatEmitter#emit` 给**每一帧**（含心跳）取号，心跳**不落归档**（T7）；
  ② 重启后的新写出器从 `RunStore#lastEventSeq`（**归档末帧**号）续号；③ 前端把收到的任何帧的 seq 计入 `lastSeq`
  （心跳也算：`scripts/verify-e2e.ps1#Invoke-AiTurn`、前端 `stores/ai.ts` 的 `runSeq[runId]`）。
  三者相乘 ⇒ 新进程可能**重用心跳用过的号**，而该号已在客户端 `lastSeq` 覆盖范围内 ⇒ 该帧在随后的
  差量重挂里被判为孤儿而**永久丢弃**（丢帧且不报错）。
- **真实证据**：`docs/evidence/DC14-微调实施验证.md` §3.6 —— 归档 seq 1..4，重挂后实时心跳拿到 **14/15/16**，
  说明挂起期已有 5..13 共 9 个号被心跳用掉且未落档；此时若进程死亡并由新实例续跑，新帧会从 5 重新开始。
- **复现**：`SseFrameSequenceConformanceTest.pinsHeartbeatSeqReuseAcrossEmitterRestartWhichCanOrphanTheFirstResumedFrame`
  （归档 1..4 + 前端 lastSeq=7 → 新首帧拿到 5 → `replayTo(...,7)` 补发 **0** 帧 = 丢帧）。
- **修复方向（待裁决）**：要么心跳不占号（心跳不参与重挂时间线），要么把续号锚点从"归档末帧"改成"已发放过的最大号"。

### S5c-2　重挂交接窗口：`replayTo` 与 `attach` 不是一个原子段落

- **代码事实**（`AiController#events`，`src/main` 未改）：`out.replayTo(emitter, false, lastSeq)` 读取归档并发送，
  **之后**才 `out.attach(emitter)`。两次调用之间若有实时帧写出，该帧既不在回放快照里、也还没被这个订阅者接住 ⇒ **丢帧窗口**。
- **性质**：静态分析结论（未实测；窗口是调度相关的窄窗，JUnit 无法确定化复现，因 controller 自建 emitter 不可拦截）。
- **修复方向（待裁决）**：先 `attach` 再 `replayTo`（重复由前端 `lastSeq` 去重），或给"回放 + 挂订阅"加同一把锁。

### S5c-3　取号 → 落归档 → 写出不是原子动作，多线程出帧时**到达顺序可能逆序**

- **事实链**：`emit()` 里 `nextSeq()`（原子）与 `emitter.send(...)`（写出）之间还夹着 `store.appendEvent`（Redis 写）；
  而**出帧线程不唯一**：运行线程在挂起等待里每 2s 发心跳（`ConfirmGate#await`），HTTP 线程在
  `POST /api/ai/confirm|frontend-tool-result` 里发决策/回灌帧。先取号的线程可能后写出。
- **复现（两条）**：
  ① **自然复现**：`ToolFrameSequenceConformanceTest.rejectedConfirmPathEmitsResultWithoutExecuting` 在本机实测出现
  `tool_result(seq=6)` 早于 `confirm_decision(seq=5)` —— 即"到达顺序非严格递增"；
  ② **确定化复现**：`SseFrameSequenceConformanceTest.pinsOutOfOrderArrivalWhenTwoThreadsEmitConcurrently`
  （把 HTTP 线程卡在写出上，运行线程的心跳取号=2 并先到达 → 到达序为 2、1）。
- **影响面**：`lastSeq` 取最大值，故不破坏差量重挂完整性；但"seq 严格递增到达"这条更强口径不成立，
  客户端若按 seq 排序渲染，可能把相邻两帧的先后画反。
- **修复方向（待裁决）**：单写点（帧队列）或对"取号 + 落档 + 写出"加同一把锁。

### S5c-4　纯空白分片让"delta 拼接"与"落定正文"分家

- **事实链**：`ResilientChatService#consume` 只在 `StringUtils.hasText(text)` 为真时发 `delta`；
  而同一个分片**无条件**进 `Accumulator`（`accept` 里 `if (chunk != null) text.append(chunk)`）。
  于是模型单独吐一个 `" "` 或 `"\n"`（流式分片边界上很常见）时：
  `message_end.chars` = 含该分片的长度；前端按 delta 拼接 = 不含该分片；`RunSnapshot.finalText` = 含该分片。
- **复现**：`ChatTurnFrameSequenceConformanceTest.pinsWhitespaceChunkBreakingDeltaConcatAgainstMessageEndChars`
  （分片 `你好` / `" "` / `世界` → 帧 `chars=5`，delta 拼接 `你好世界`=4，`finalText=你好 世界`=5）。
- **影响面**：渲染上只是少一个空格/换行，但"帧流拼接 == 落定正文"这条不变量不成立（取证/对账口径失真）。
- **修复方向（待裁决）**：要么空白分片也发 delta，要么累计器同样跳过空白分片（两者取其一，使三处同源）。

### S5c-5　失败路径以"未关闭的文本消息"收尾（`fail()` 不补 `message_end`）

- **事实链**：三条终局里只有 `finish()` 与 `cancelTerminal()` 会 `out.textEnd(...)`；
  `fail()` 直接 `out.error(...)`。于是"已产出半截正文后上游报错"这一形态下，运行关闭时文本段仍开着。
- **借来的判据**（ag-ui `open-message-at-run-finished-fatal`）：运行关闭前，它打开的一切都必须先关闭。
- **复现**：`SseFrameSequenceConformanceTest.pinsOpenTextSegmentOnTheErrorPath`
  （帧序 `start, message_start, delta, error` → 校验器报"轮次已收终帧，但正文段没有 message_end"）。
- **修复方向（待裁决）**：`fail()` 在 `out.error(...)` 前补一次 `out.textEnd(accumulator.text().length())`。

### S5c-6　决策回执帧晚于它唤起的后果（`signal()` 早于发帧）

- **事实链**：`ConfirmGate#submitDecision` / `#submitFrontendResult` 的顺序是
  `putPending → signal(唤醒等待方) → 发回执帧(confirm_decision / frontend_tool_result)`。唤醒早于发帧，
  于是运行线程可在回执帧写出去之前跑完后续动作：
  轻则 `tool_result` 早于 `confirm_decision`（客户端先看到工具结局、后看到人的决策），
  重则整轮已收尾 —— 回执帧落到 `done` **之后**（即"终态帧之后仍有业务帧"）。
- **复现（确定化）**：`ToolFrameSequenceConformanceTest.pinsDecisionReceiptArrivingAfterItsConsequence`
  （把决策线程在 signal 与 emit 之间挂起 → 帧序实测 `start, suspended, tool_start, confirm_request, tool_result, done, confirm_decision`，
  即同时违反"回执先于后果"与"终帧之后无帧"）。
- **说明**：该用例是把**真实存在的窗口**确定化（任意一次调度切换都会产生），不是构造出来的假象。
  为此，挂起轮的两条正向用例（`approvedConfirmPath…` / `rejectedConfirmPath…`）只对"序号集合的完整性与唯一性"
  做严格断言，另显式放行 `早于 confirm_decision` 与 `严格递增` 两类已知竞态消息（放行范围写死在
  `assertConformantToleratingEmitRaces` 里，其它任何违规照旧红）。
- **修复方向（待裁决）**：把回执帧的发出放到 `signal()` 之前，或对"状态落库 + 发帧 + 唤醒"加同一把锁。

### 汇总（供指挥官裁决）

| # | 问题 | 严重度（我的判断） | 是否可复跑 | 建议优先级 |
|---|---|---|---|---|
| S5c-1 | 跨进程续跑重号 ⇒ 差量重挂丢帧 | **高**（静默丢帧，且已有真实链路的号消耗证据） | 是（pins 用例） | 先修 |
| S5c-6 | 决策回执晚于后果 / 晚于终帧 | **高**（可产生"终帧之后的业务帧"，客户端状态机可能错乱） | 是（pins 用例） | 先修 |
| S5c-5 | 失败路径不关文本段 | 中（协议面自相矛盾，影响前端收口与对账） | 是（pins 用例） | 次之 |
| S5c-3 | 到达顺序可能逆序 | 中（不丢帧，但强口径不成立；已自然复现） | 是（pins 用例 + 自然复发） | 次之 |
| S5c-4 | 空白分片致 chars / 正文口径不一致 | 低-中（渲染少空白，对账失真） | 是（pins 用例） | 次之 |
| S5c-2 | 重挂交接窗口（replayTo→attach） | 中（窄窗丢帧，仅静态分析） | 否（需真实 HTTP/时序注入） | 与 S5c-1 同批看 |

**处置约定**：以上 6 项本棒**均未修改生产代码**（任务书禁止）。每条都配了可复跑的 `pins…` 用例，
修复后这些用例会变红 —— 届时按"承认的缺口被有意关闭"改写/删除它们即可。

---

## 7. 合规与脱敏声明

- 未修改 `research-src/ag-ui`（只读参照，未写入）。
- 未修改 `<REPO_ROOT>/backend/src/main` 下任何生产代码；`git status` 显示唯一变更是新增测试目录。
- 未执行任何 git 写操作（无 `add/commit/push/reset/rebase`）。
- 本文档与新增测试源码中不含本机绝对路径、不含真实用户名、不含任何密钥（路径一律用
  `<REPO_ROOT>` / `<MAVEN_HOME>` / `<MEMURAI_HOME>` 占位符；源码中已 grep 校验无本机盘符绝对路径、无用户主目录路径、无用户名泄漏）。
- 未起 HTTP 端口、未占用 18330；Redis（Memurai）仅做过一次只读 `ping`（`PONG`），测试资产本身不依赖 Redis。

---

## 裁决结论（指挥官 K3，2026-10-07）

6 项帧协议问题全部采纳修复，列入 M1 收尾棒：

| # | 问题 | 裁决 |
|---|------|------|
| S5c-1 | 跨进程续跑重用心跳占用的 seq，该帧在差量重挂中被永久丢弃 | 采纳，最高优先——心跳不应消费业务 seq（或续号锚点含心跳台账） |
| S5c-6 | 决策回执帧晚于其后果（极端时晚于 done） | 采纳，高优先——先发帧后 signal |
| S5c-3 | 取号→归档→写出非原子+多线程出帧，到达可逆序 | 采纳——出帧串行化 |
| S5c-5 | fail() 不补 message_end，与 finish/cancel 不一致 | 采纳 |
| S5c-4 | 纯空白分片致 message_end.chars 与 delta 拼接不符 | 采纳——对齐发帧与累计判据 |
| S5c-2 | 重挂交接窗口丢帧（仅静态分析） | 采纳随 S5c-1 同棒排查加固；修复棒实测无法复现则记为已加固 |

M1 收尾棒定编 10 项：守卫 3 项（预检查阻断/导出互斥 409/未知操作符抛异常）+ Q8② SSE 二次写 + 重复编码 409 + 只读 /api/ai/tools 端点 + 帧协议 S5c-1~6。排期：S5.d 完成后、M1 出口评审前。
