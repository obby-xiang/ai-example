# S5e 帧协议修复验证（S5c-1~6 + Q8②，共 7 项）

> 日期：2026-10-07　执行：施工子智能体（DeepSeek V4 Flash）　仓库：`<REPO_ROOT>`（分支 `main`，HEAD `89aa30c` + 本棒未提交的工作区改动）
> 范围：<b>修改 `src/main` 生产代码</b>（本棒经明确授权）+ 同步改写受影响的 conformance 用例 + 本证据文档。
> 未做任何 git 写操作（无 add/commit/push/reset/rebase/checkout）。
> 结论：**7 项全部修复**；单测 **152 例 × 3 轮全绿**（并发敏感子集 57 例另跑 3 轮全绿）；
> E2E `verify-e2e.ps1` **PASS=22 FAIL=0、Q8 项 PASS=2 FAIL=0（Q8② 由 FAIL 转 PASS）**；
> 修复前后帧协议不变量校验器（`FrameContract`）的 6 条规则**一字未改**，只改断言与 fixture。

---

## 1. 七项修复一览

| # | 问题（S5c 编号 / S5b 编号） | 改法一句话 | 涉及文件 | 回归测试 |
|---|---|---|---|---|
| 1 | **S5c-1** 心跳占业务 seq ⇒ 跨进程续号重号 ⇒ 差量重挂静默丢帧 | 心跳改走**独立序号空间**（`heartbeatSeq`），业务 `seq` 只发给落归档的帧 | `ai/run/SseChatEmitter.java` | `SseFrameSequenceConformanceTest.heartbeatNeverBurnsABusinessSeqSoTheFirstResumedFrameSurvivesDeltaReplay`、`…heartbeatKeepsItsOwnSequenceSpaceAndNeverTouchesTheBusinessAnchor`、`SseChatEmitterFramesTest.heartbeatIsBroadcastButNotArchived` |
| 2 | **S5c-6** 决策回执帧晚于其后果（极端时晚于 `done`） | 次序固定为**落库 → 发回执帧 → 唤醒**，三步与等待方轮询同一把锁 | `ai/gate/ConfirmGate.java` | `ToolFrameSequenceConformanceTest.decisionReceiptIsWrittenBeforeTheConsequenceItWakes`（放行/拒绝两路同时改为全量 `assertConformant`） |
| 3 | **S5c-3** 取号 → 落档 → 写出非原子，多线程出帧到达可逆序 | `emit()` 全程持**出帧锁**（单写点） | `ai/run/SseChatEmitter.java` | `SseFrameSequenceConformanceTest.twoThreadsEmittingConcurrentlyArriveInSeqOrder` |
| 4 | **S5c-5** `fail()` 不补 `message_end`，与 `finish/cancel` 不一致 | `fail()` 在 `error` 前先 `textEnd(已产出字符数)` | `ai/run/ResilientChatService.java` | `ChatTurnFrameSequenceConformanceTest.partialContentThenUpstreamFailureClosesTheTextSegmentBeforeTheErrorTerminal`、`SseFrameSequenceConformanceTest.errorPathClosesTheTextSegmentExactlyOnceWhenTheCallerAsksForIt` |
| 5 | **S5c-4** 纯空白分片致 `message_end.chars` ≠ delta 拼接 | 发帧判据改为"**非空即发**"，与累计器同源 | `ai/run/ResilientChatService.java` | `ChatTurnFrameSequenceConformanceTest.whitespaceChunkIsEmittedAsDeltaSoConcatEqualsCharsAndSettledText` |
| 6 | **S5c-2** `replayTo` 与 `attach` 非原子 ⇒ 交接窗口丢帧 | 新增 `replayAndAttach(...)`：回放 + 挂订阅在**同一把出帧锁**内完成 | `ai/run/SseChatEmitter.java`、`ai/web/AiController.java` | `ReattachLastSeqConformanceTest.replayAndAttachIsAtomicSoNoFrameEscapesTheHandoffWindow` |
| 7 | **Q8②** 向已断开客户端写帧触发全局处理器二次写 JSON | 两层防护：① 全局处理器识别"已提交 / 内容类型为 `text/event-stream`"的响应**不写体**；② `sendTo` 吞没对端断开的写失败（含异步不可用类异常） | `common/GlobalExceptionHandler.java`、`ai/run/SseChatEmitter.java` | `GlobalExceptionHandlerSseTest`（新增 3 例）+ E2E Q8② 断言 |

---

## 2. 逐项：根因 → 改法 → 回归

### 2.1 S5c-1 心跳不占业务序号（最高优先）

- **根因**（S5c §6 S5c-1 + DC-14 §3.6 实测）：① `emit()` 给每一帧（含心跳）取号；② 心跳**不落归档**（T7）；
  ③ 重启后的新写出器从 `RunStore#lastEventSeq`（**归档末帧**）续号；④ 前端/`verify-e2e.ps1` 把收到的**任何**带 `seq` 的帧计入 `lastSeq`。
  四者相乘 ⇒ 新进程重用心跳用过的号，而该号已被前端 `lastSeq` 覆盖 ⇒ 该帧在差量重挂里被判为孤儿而**永久丢弃**（静默丢帧）。
- **改法**：`emit()` 里业务帧 `frame.put("seq", nextSeq())` 并落归档；心跳帧改为 `frame.put("heartbeatSeq", heartbeatSeq.incrementAndGet())`
  + `touchActivity`。于是**归档末帧 == 已发放过的最大业务号**，续号锚点与心跳彻底解耦。心跳的"第几次"仍可观测（独立空间）。
- **旁证**：`SseChatEmitterFramesTest` 的 T6/T7 说明同步改写，并追加两条断言（心跳带 `heartbeatSeq:1` 且**不含** `seq`；其后的首个业务帧 `seq=1`）。
- **测试**：
  - `heartbeatNeverBurnsABusinessSeqSoTheFirstResumedFrameSurvivesDeltaReplay`（**本棒新增的 DC-14 §3.6 回归**）：
    旧实例跑出归档 1..4 → 挂起期三帧心跳 → 客户端已知号仍为 4 → 新实例首帧 `seq=5` → `replayTo(emitter, false, 4)` 补发 **1 帧**（修复前该帧会被判孤儿、补发 0 帧）。
  - `heartbeatKeepsItsOwnSequenceSpaceAndNeverTouchesTheBusinessAnchor`：业务序号自 1 起连续、归档与订阅者视角一致、活动戳仍在。

### 2.2 S5c-6 决策回执先于其后果

- **根因**：`submitDecision`/`submitFrontendResult` 的顺序是 `putPending → signal → 发回执帧`；唤醒早于发帧，
  被唤醒的等待方可先产出后果帧（`tool_result`），极端时回执落到 `done` 之后。
- **改法**：新增 `submitLock`，把"落库 → 发回执帧 → 唤醒"包成一段；**等待循环读待决状态也持同一把锁**
  （否则"已落库、回执还没发"的瞬间会被 Redis 轮询路径重新打开同一窗口）。
- **测试**：`decisionReceiptIsWrittenBeforeTheConsequenceItWakes` 把回执帧**卡在写出上**，断言此刻
  `tool_result` 尚未出现（帧先于其后果），释放后全序为 `start, suspended, tool_start, confirm_request, confirm_decision, tool_result, done` 并通过全量不变量。
  同时把放行/拒绝两路从"容忍竞态的宽松断言"收紧为 `FrameContract.assertConformant`（原先的放行名单 `assertConformantToleratingEmitRaces` 已删除）。
- **诚实声明**：该回归钉的是**可观测顺序**；保证它的是两道机制（发帧先于 signal **且** 同一把锁 + 出帧串行），二者冗余，
  因此单看该用例无法区分"只做其中一道"的退回。

### 2.3 S5c-3 出帧串行（取号 → 落档 → 写出原子）

- **根因**：`emit()` 中 `nextSeq()`（原子）与 `emitter.send` 之间夹着 `appendEvent`（Redis 写），而出帧线程不唯一
  （运行线程的挂起心跳 / HTTP 线程的决策与回灌帧）⇒ 先取号者可能后写出，到达顺序可逆序（S5c 实测已自然复现）。
- **改法**：`emit()` 全程持 `emitLock`；`replayTo` 与 `replayAndAttach` 亦持同一把锁（见 2.6）。
- **测试**：`twoThreadsEmittingConcurrentlyArriveInSeqOrder`（HTTP 线程卡在写出上 → 心跳线程必须等锁）：
  到达顺序 = `tool_result, heartbeat`，`seq=1` 与 `heartbeatSeq=1` 各归其位，归档无逆序无重号。

### 2.4 S5c-5 失败路径补 `message_end`

- **根因**：三条终局里只有 `finish()` 与 `cancelTerminal()` 会 `out.textEnd(...)`；`fail()` 直接 `out.error(...)`
  ⇒ "已产出半截正文后上游报错"会以"仍开着的文本消息"收尾（ag-ui 的 `open-message-at-run-finished` 判据不成立）。
- **改法**：`fail(...)` 增加 `partialChars` 参数（调用点传 `accumulator.text().length()`），在 `out.error(...)` 前
  `out.textEnd(partialChars)`（未开过段时 `textEnd` 是幂等空操作）。
- **测试**：`partialContentThenUpstreamFailureClosesTheTextSegmentBeforeTheErrorTerminal`（真实编排层：先 `delta` 后上游报错）
  ⇒ 帧序 `start, message_start, delta, message_end, error`，`chars=2`，错误码 `UPSTREAM_STREAM_ERROR_AFTER_PARTIAL`，全量不变量通过。

### 2.5 S5c-4 空白分片口径归一

- **根因**：`consume()` 只在 `StringUtils.hasText(text)` 为真时发 `delta`，而同一分片无条件进 `Accumulator`
  ⇒ 纯空白分片时 `message_end.chars`（含空白）≠ delta 拼接（不含空白）、`finalText` 与前端渲染分家。
- **改法**：判据改为 `text != null && !text.isEmpty()`（**非空即发**）。空白是模型正文的一部分，保留它比"偷偷吞掉"更正确：
  三处（delta 拼接 / `message_end.chars` / `finalText`）同源且不丢字。
- **测试**：`whitespaceChunkIsEmittedAsDeltaSoConcatEqualsCharsAndSettledText`（分片 `你好` / `" "` / `世界`）
  ⇒ 三个 delta、`chars=5`、拼接 `你好 世界` == `finalText`。

### 2.6 S5c-2 重挂原子交接

- **根因**（静态分析）：`AiController#events` 先 `replayTo(emitter, …)` 再 `attach(emitter)`；两次调用之间的实时帧
  既不在回放快照里、也还没被该订阅者接住 ⇒ 丢帧窗口（SSE 丢帧不报错）。
- **改法**：`SseChatEmitter` 新增 `replayAndAttach(emitter, lastSeq, terminal)`（回放 + `attach`/`complete` 同一把锁内）；
  `AiController#events` 改调它；`replayTo` 保留（内部转调持锁的 `replayLocked`）。
- **测试**：`replayAndAttachIsAtomicSoNoFrameEscapesTheHandoffWindow` —— 把回放首帧卡在"慢订阅者"上（交接未完成），
  另一线程发实时帧；断言新订阅者**收全** `seq 1,2,3` 且末帧是交接后的实时帧。
  未修复时该实时帧落在窗口里，新订阅者永远收不到（确定化 kill）。
- **说明**：这是"加固"而非"复现过的缺陷"—— 原窗口属调度相关的窄窗，JUnit 无法在**未加锁**实现上稳定复现，
  故按裁决口径记为**已加固**，并保留上述防护性回归。

### 2.7 Q8② SSE 响应不再二次写 JSON

- **根因（本棒实测补记，比 S5b §6.1 的描述更精确）**：客户端断开后的写帧失败有**两个出口**：
  1. **同步出口**：`SseChatEmitter.sendTo` 的 catch（记 DEBUG、摘除订阅者）——这部分**原本就已吞没**；
  2. **框架出口**：Tomcat 的 flush 失败被 Spring 作为**异步请求的错误结果**重新派发到 `DispatcherServlet`
     （栈是异常的**创建点**，即 `sendTo` 内部，所以只看栈会误以为异常从 `sendTo` 冒泡出来）。
     此时响应内容类型已固定为 `text/event-stream`，全局处理器照旧回 `ApiResponse` ⇒
     `HttpMessageNotWritableException: No converter for [... ApiResponse] with preset Content-Type 'text/event-stream'`，
     每轮 E2E ≈7 条噪音（S5b 实测）。
  这正是"只做 SseChatEmitter 层吞没"不够、必须改全局处理器的原因。
- **改法（两层都做）**：
  - `GlobalExceptionHandler#handleGeneral` 增加 `HttpServletRequest/HttpServletResponse` 参数与
    `isStreamingResponse(response)` 判定（`response.isCommitted()` 或内容类型以 `text/event-stream` 开头）
    ⇒ 该情形只记 WARN（摘要）+ DEBUG（栈），返回**空体**的 500（`ResponseEntity.status(500).build()`，空体不需要转换器）。
    非 SSE 请求行为不变（仍 `log.error` + `ApiResponse` 体）。
  - `SseChatEmitter#sendTo` 的 catch 合并为 `catch (Exception)`（IOException / `AsyncRequestNotUsableException` /
    `IllegalStateException`（emitter 已完成）一律吞没），与类注释声明的契约一致。
- **测试**：新增 `GlobalExceptionHandlerSseTest`（3 例：SSE 内容类型不写体 / 已提交不写体 / 普通请求仍回 `ApiResponse` 体）；
  真实链路证据 = E2E 的 Q8② 断言（见 §4）。

---

## 3. 单元测试

工具链：`<MAVEN_HOME>/bin/mvn.cmd`（IntelliJ 自带 Maven），工作目录 `<REPO_ROOT>/backend`，JDK 21，离线 `-o`。

```
cd <REPO_ROOT>/backend
"<MAVEN_HOME>/bin/mvn.cmd" -B -o test
# 并发敏感子集（含全部 conformance 类）
"<MAVEN_HOME>/bin/mvn.cmd" -B -o test \
  -Dtest='*ConformanceTest,FrameContractFalsificationTest,SseChatEmitterFramesTest,ConfirmGateWiringTest'
```

| 轮次 | 范围 | 结果 |
|---|---|---|
| 1 | 全量（新增 Q8② 处理器用例之前） | `Tests run: 149, Failures: 0, Errors: 0, Skipped: 0` → **BUILD SUCCESS** |
| 2 | 全量 | 149 / 0 / 0 → BUILD SUCCESS |
| 3 | 全量 | 149 / 0 / 0 → BUILD SUCCESS |
| S1~S3 | 并发敏感子集 ×3（`*ConformanceTest,FrameContractFalsificationTest,SseChatEmitterFramesTest,ConfirmGateWiringTest`） | 每次 `Tests run: 57, Failures: 0, Errors: 0, Skipped: 0` → BUILD SUCCESS（该子集含全部并发出帧 / 确认门两线程 / 重挂交接用例；后加的 Q8② 处理器用例不在此子集内，与并发无关） |
| 4 | 全量（含新增 `GlobalExceptionHandlerSseTest`，即交付态） | **152 / 0 / 0** → BUILD SUCCESS |
| 5 | 全量（交付态） | **152 / 0 / 0** → BUILD SUCCESS |
| 6 | 全量（交付态） | **152 / 0 / 0** → BUILD SUCCESS |

- 用例数从 147 → **152**：改写 5 条 `pins…`（缺口用例转正）+ 新增 3 条回归（S5c-1 跨进程续号、S5c-2 原子交接、S5c-5 编排层）+ 新增 3 条 Q8② 处理器用例。
- 无 flaky：含并发出帧、确认门两线程、重挂交接窗口的用例连续 3 轮全绿。
- 计数口径：`grep -rnE "^\s*@Test(\s*$|\()" backend/src/test --include=*.java | wc -l` → **152**。

### 3.1 改写的用例（原 `pins…` → 修复后行为）

| 原用例（钉缺陷） | 现用例（断言修复后行为） |
|---|---|
| `SseFrameSequenceConformanceTest.pinsHeartbeatSeqReuseAcrossEmitterRestartWhichCanOrphanTheFirstResumedFrame` | `heartbeatNeverBurnsABusinessSeqSoTheFirstResumedFrameSurvivesDeltaReplay` |
| `SseFrameSequenceConformanceTest.heartbeatConsumesSeqButLeavesOnlyAnActivityStampInTheArchive` | `heartbeatKeepsItsOwnSequenceSpaceAndNeverTouchesTheBusinessAnchor` |
| `SseFrameSequenceConformanceTest.pinsOutOfOrderArrivalWhenTwoThreadsEmitConcurrently` | `twoThreadsEmittingConcurrentlyArriveInSeqOrder` |
| `SseFrameSequenceConformanceTest.pinsOpenTextSegmentOnTheErrorPath` | `errorPathClosesTheTextSegmentExactlyOnceWhenTheCallerAsksForIt` |
| `ToolFrameSequenceConformanceTest.pinsDecisionReceiptArrivingAfterItsConsequence` | `decisionReceiptIsWrittenBeforeTheConsequenceItWakes` |
| `ChatTurnFrameSequenceConformanceTest.pinsWhitespaceChunkBreakingDeltaConcatAgainstMessageEndChars` | `whitespaceChunkIsEmittedAsDeltaSoConcatEqualsCharsAndSettledText` |
| `ReattachLastSeqConformanceTest.replayFromAHeartbeatSeqOnTheHoleLosesNothing` | `replayFromALastSeqThatIsNotInTheArchiveLosesNothing`（前提变了：S5c-1 后心跳不再制造归档空洞，改用"落档失败留下的洞"构造同一输入形态，**规则与 kill 不变**） |
| `ToolFrameSequenceConformanceTest.assertConformantToleratingEmitRaces`（放行名单） | 删除；放行/拒绝两路改跑 `FrameContract.assertConformant`（收紧） |

### 3.2 未动的部分（禁止事项自查）

- `FrameContract` 的 **6 条规则实现一字未改**（只更新了"心跳占号"相关的**说明性注释**，规则本体、判据、kill 语义不变）。
- 未改 `FrameWire` 的录取逻辑（仍只录 `SseEmitter#send` 真正写出的 JSON 原文）。
- `Latch`、认领语义、会话门、`RunStore` 键结构、前端代码：均未动。

---

## 4. E2E（真实模型驱动，`scripts/verify-e2e.ps1`）

启动（key 仅经会话环境变量注入，**未写入任何文件**）：

```
cd <REPO_ROOT>/backend
java -jar target/config-mgr.jar --server.port=18330 \
  --spring.datasource.url='jdbc:h2:file:./data/e2e_s5e_db;DB_CLOSE_DELAY=-1' \
  --app.job.batch-size=10 --app.job.demo-batch-delay-ms=150
cd <REPO_ROOT>
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/verify-e2e.ps1 \
  -Base http://127.0.0.1:18330 -BackendLog <TMP>/s5e-verify/boot*.log
```

前置检查：18330 启动前 `netstat` 确认空闲；Redis（Memurai，127.0.0.1:6379）只读 `ping` → `PONG`。

| E2E 轮次 | 库 | 后端实例 | 结果 | Q8 项 |
|---|---|---|---|---|
| 第 1 轮 | 全新独立库 | A | **PASS=22 FAIL=0** | PASS=2 FAIL=0（**Q8② 转 PASS**） |
| 第 2 轮 | 沿用上一轮的库 | A | PASS=21 FAIL=1（TC18） | PASS=2 FAIL=0 |
| 第 3 轮 | 沿用上一轮的库 | B | PASS=21 FAIL=1（TC18） | PASS=2 FAIL=0 |
| **第 4 轮** | 全新独立库 | C | **PASS=22 FAIL=0** | **PASS=2 FAIL=0** |
| **第 5 轮** | 沿用第 4 轮的库 | C | **PASS=22 FAIL=0** | **PASS=2 FAIL=0** |

第 4、5 轮即"同一独立库连跑两轮"的验收口径（与 S5b 的跑法一致），两轮均 **22/22 全过、Q8 两项全过**。

### 4.1 Q8② 的实测证据（日志计数）

| 日志（一次/两次完整轮的运行日志） | `No converter for` | `HttpMessageNotWritableException` | `Failure in @ExceptionHandler` | 对比 |
|---|---|---|---|---|
| 第 1 轮（1923 行，单轮） | **0** | **0** | **0** | 修复前（S5b 实测）每轮 ≈7 次 |
| 第 4+5 轮（1965 行，两轮） | **0** | **0** | **0** | 同上 |

同一批日志里，被**正常吞没**的对端断开痕迹仍在（第 4+5 轮：`SSE 写出失败`（DEBUG）12 次、
`SSE 响应上的未处理异常`（WARN 摘要 + DEBUG 栈）28 次）—— 说明这些噪音不是"没发生"，而是**不再产生二次写异常**。
另：`Unhandled exception`（ERROR 级）在两轮里仅 2 次，均为 TC2 的重复编码插入冲突（`DataIntegrityViolationException`，
属 S5b【待裁决】2 的"重复编码 500"现状，不在本棒 7 项内，故保持原样）。

### 4.2 【待裁决】TC18 在两次轮次里模型未调用工具（非本棒回归）

- **现象**：第 2、3 轮（沿用库）TC18 报 `未取得挂起轮的 runId` —— 脚本在 TC18 第②步开流等 `confirm_request`，
  但该轮**没有产生挂起**。
- **根因（用该轮的真实运行快照定位，非猜测）**：该轮 `RunStore` 快照 `status=DONE`、`finalText` 原文为模型自述:
  "我先核对了任务状态，有一个重要情况需要先告知你，因此**没有执行** `start_publish`… 该导入任务…数据已经处于 **PUBLISHED** 状态…
  重复发起发布…只会产生一次冗余的高风险作业"，随后列出 4 条让用户选择的下一步。
  即：**模型主动选择不调用 `start_publish`**（它读了 `get_workspace_state`/`list_tasks` 后判断任务已完成），
  属模型侧工具选择抖动，**不经由本棒的 7 项改动路径**。
- **为何判为抖动而非回归**：① 同一份 `src/main` 在另 3 轮（第 1、4、5 轮）TC18 全过；
  ② 本棒改动不触及模型输入（system 提示、记忆窗口、工具披露、上游请求组装均未改），
  只能改帧序/异常路径，无法影响"模型是否调用工具"；
  ③ 该用例的失败文案是脚本自带的（模型未调用工具的抖动），而 S5b §6.6.3 已登记该类抖动并为 TC15/TC17 加了重试，
  **TC18 没有重试**。
- **建议（脚本改动，本棒未做）**：与 TC15/TC17 同口径给 TC18 第②步加重试（≤3 次），
  或让 TC18 用一个"尚未发布"的任务（当前它复用 TC17 已发布完成的任务，模型有充分理由拒绝重复发布）。
- **本棒处置**：不改脚本、不放宽断言；以第 4、5 轮（同库连跑两轮 22/22）作为验收证据，并把本条如实登记。

---

## 5. 剩余风险（如实声明）

| # | 风险 | 说明 / 影响面 |
|---|---|---|
| R1 | **出帧锁把"单个订阅者的慢写出"变成"整轮的帧延迟"** | `emit()` 持锁遍历订阅者逐个写出：某个订阅者的 TCP 背压/阻塞会让本轮后续帧排队（修复前是无序并发写）。帧都很小、SSE 缓冲可吸收，E2E 与单测均未见异常；但如果将来出现大帧或多订阅者高频直播，建议改为"单写点队列 + 每订阅者独立投递"。 |
| R2 | **续号锚点仍取"归档末帧"** | S5c-1 已消除"心跳把锚点顶到前面"这一主因；但若某帧的 `appendEvent` 落档失败（Redis 抖动，当前是 DEBUG 吞没），归档末帧会落后于已发放号 ⇒ 极端情况下跨进程仍可能重号。彻底方案是把"已发放的最大号"单独持久化（改状态 schema），本棒未做。 |
| R3 | **跨进程的"回执先于后果"仍不保证** | 本文保证的是**单进程内**（同一实例发出回执帧、同一实例内的等待方）。若决策由实例 A 提交、等待方在实例 B（Redis 轮询发现），两进程各自的帧序无可比性 —— 属分布式口径的固有限制，已在此登记。 |
| R4 | **`fail()` 的 `partialChars` 取"当前尝试的累计"** | 重试时每次尝试新建 `Accumulator`，而"未产出内容"是重试的前提 ⇒ 前几次尝试无 delta，`chars` 与全程 delta 拼接一致。若将来放宽重试条件（允许已产出也重试），该口径需重新推导。 |
| R5 | **Q8② 的判定依赖"内容类型/已提交"** | 若将来有端点以 SSE 内容类型流式写**非帧**内容并真的需要错误响应体，会被这条守卫跳过写体（只会记日志）。当前 SSE 端点只有 AI 帧流，无此冲突。 |
| R6 | **`ConfirmGate#submitLock` 是**进程级**（非按 runId）** | 它同时保护"落库 + 发回执帧 + 唤醒"与等待方的 Redis 轮询：若某个订阅者写出很慢，会连带让**所有轮**的挂起轮询短暂排队。提交/轮询都是低频（每次工具决策 / 每 2s 一次），实测无影响；若将来帧变大变密，可改为按 runId 分段加锁。 |

---

## 6. 【待裁决】

| # | 事项 | 建议 |
|---|---|---|
| 1 | TC18 第②步的模型工具选择抖动（§4.2）：两轮因"模型拒绝重复发布已完成任务"而未产生挂起 | 给 TC18 加重试（与 TC15/TC17 同口径），或让 TC18 改用未发布任务。**属脚本改动，本棒按"不自行改脚本"的约束未做** |
| 2 | R1（出帧锁下慢订阅者拖慢整轮）是否需要在本轮就改成"单写点 + 每订阅者独立投递" | 当前证据面（E2E 22×3 轮、152 单测）不支持"必须现在改"；建议列入 M1 出口后的观察项 |
| 3 | R2（落档失败导致锚点落后）是否升级为"持久化已发放最大号" | 需改 `RunSnapshot` schema 与跨版本兼容口径，建议单独立棒 |

---

## 7. 合规、脱敏与收尾

- 未做任何 git 写操作（无 `add/commit/push/reset/rebase/checkout`）；`git status --porcelain` 仅本棒的改动：
  11 个 `M`（5 个 `src/main` + 6 个测试文件）、1 个新增测试文件
  `backend/src/test/java/com/example/configmgr/common/GlobalExceptionHandlerSseTest.java`、本文档，
  以及 `.github/workflows/ci.yml` 的一处**步骤名**更新（原写死"147 例"已随用例数增长失效，改为不写数字，
  见下条）。
- **顺带同步**：`ci.yml` 的步骤名 `编译并运行单测（mvn test，147 例）` → `编译并运行单测（mvn test，全量用例）`。
  只改显示名，不改任何门禁行为（`run: mvn -B test` 原样）。
- 未修改 `research-src/`、未修改 `scripts/verify-e2e.ps1`、未修改前端、未修改既有证据文档。
- 本文与代码内**无密钥**（环境变量注入，未落盘）、**无用户名**、**无本机绝对路径**（一律 `<REPO_ROOT>` / `<MAVEN_HOME>` / `<MEMURAI_HOME>` / `<TMP>` 占位符）。
- 收尾：本棒启动的后端进程（18330，三段 PID 见运行期记录）已全部 `taskkill /F`；`netstat` 确认 18330 无 LISTENING；
  独立库运行产物 `backend/data/e2e_s5e_db*` 已删除；`backend/data/` 下其他既有文件（演示库、`files/` 里 S5d 及更早的产物）未动。

---

## 裁决结论（指挥官 K3，2026-10-07）

| # | 事项 | 裁决 |
|---|------|------|
| 1 | TC18 模型工具选择抖动 | 采纳建议——TC18 加重试+改用未发布任务 fixture，列入棒-B 附带项 |
| 2 | R1 慢订阅者拖慢整轮 | 不改——当前证据不支持，记已知风险，M2 再议 |
| 3 | R2 持久化"已发放最大号" | 暂不采纳——S5c-1 修复已使续号锚点恒等于已发放最大业务号，风险闭合，记 M2 候选 |
