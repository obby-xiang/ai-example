# 施工报告 · AI 对话日志链路追踪批（DeepSeek/deepseek-flash）

> **DC-19 首页（执行面登记）**
>
> | 项 | 值 |
> |---|---|
> | 执行者 | **DeepSeek/deepseek-flash**（profile `coder`，thinkingEffort `high`，agent id `agent-1407`；自证方式 = 该 agent 目录 `wire.jsonl` 的 `profile.bind` 单行） |
> | 授权来源 | 用户 2026-10-21 直授「AI 可观测就做日志打印就行，你做最佳裁决直接施工」 |
> | 角色 | 施工官（含本批内的施工裁决；不改既有裁决、不动帧契约） |
> | 仓库 / 工地 | `<REPO_ROOT>`（主仓）→ 工作树 `<REPO_ROOT>/../wt-ai-logtrace`，分支 `feature/ai-logtrace` |
> | 开工基线 | `main @ 0dad9ba`（`git rev-parse main` == `origin/main`，无需 pull） |
> | 施工日期 | 2026-10-21 |
> | 证据等级 | **【实测】** = 本次真实命令输出；**【推演】** = 由代码路径推断、未实跑 |
> | 脱敏 | 路径用 `<REPO_ROOT>` / `<WORKTREE>`；无密钥字面量；日志样例无对话正文（制度上也不允许出现） |
> | 未提交 | 全部产出留在工作树（未 commit / 未 push），符合硬纪律 |
> | 与批①（超时面）关系 | 批① 也在改 `ResilientChatService` / `SseChatEmitter` / `ContextSummaryClient` / `application.yml`。本批在这些文件里**只做新增行**（见 §5 删除行清单：11 行，全部是签名/构造点必要的替换），合流顺序 **批① 先合** |

---

## 0. 结论速览

| 项 | 值 |
|---|---|
| 目标 | 仅凭日志可完整追踪一轮 AI 对话（零新增依赖：slf4j + logback 现成） |
| 事件面 | 10 类结构化事件（`turn_accepted`/`turn_start`/`upstream_first_byte`/`turn_retry`/`tool_call_start`/`tool_call_end`/`approval_suspend`/`approval_decided`/`compaction`/`turn_cancelled`/`turn_end`/`turn_rejected`，共 12 个名字） |
| MDC 面 | `runId`/`sessionId` 覆盖：chat 入口、挂起池（`TaskDecorator`）、续跑触发池、`ConfirmGate` 等待、`ResumeService`、`StartupResumeRunner`、`SseChatEmitter` 投递线程、`ContextSummaryClient` 摘要旁路 |
| 行首可见性 | `logging.pattern.level: "%5p %X{runId:-} %X{sessionId:-}"`（无锚点行渲染为空，既有行形不变） |
| 安全 | 事件只记元数据；**对话正文 / 工具入参 / 工具出参全文一律不进日志**（负断言用例锁死） |
| 新增文件 | 2 主代码（`ai/observe/AiTrace.java`、`AiMdcTaskDecorator.java`）+ 7 测试类（28 条）+ 2 文档 |
| 后端门禁 | `mvn -o test` **1271 执行 / 0 失败**（基线 1243 + 新增 28）【实测】 |
| 前端 | **零改动**（确认：`git status` 无 `frontend/` 变更；数据来自后端事件面） |
| 文档门禁 | `node scripts/ci/check-docs.mjs` **6/6 断言绿**【实测】 |

---

## 1. 范围

按批单五项，全部落地：

1. **MDC 全链**（入口 / 挂起心跳 / 续跑 / 工具执行 / 摘要 / 投递线程 + 线程池边界装饰 + logback pattern）；
2. **关键节点结构化单行日志**（成功路径的 INFO 补课 + 既有 DEBUG 关键行的并入同体例）；
3. **安全红线**（禁正文/入参/出参全文，只记元数据）；
4. **排查手册** `docs/AI对话日志链路追踪手册.md`（字段字典 + grep 菜谱 + 故障模式 + 端点配合）；
5. **测试**（ListAppender 断言：关键事件必现、runId 贯穿、MDC 跨线程不丢、字段齐、内容类字段不出现）。

---

## 2. 施工内自决（本批裁决记录）

| # | 议题 | 裁决 | 理由 |
|---|---|---|---|
| D1 | 既有 DEBUG「AI 轮次开始 … 上下文=… 披露工具=[…]」怎么处理 | **原行逐字保留在 DEBUG**，另起一条结构化 `turn_start` INFO | 该行的中文键被 `scripts/verify-e2e.ps1:439` 的正则 `上下文=(\S+)\s+披露工具=\[([^\]]*)\]` 钉死（TC15 日志断言）。改格式 = 破 E2E；沿用类同"帧契约 16 冻结"的**日志契约冻结** |
| D2 | logback pattern 落点 | 用 `logging.pattern.level`（`application.yml`），不新增 `logback-spring.xml` | 仓内无 logback 配置文件，Boot 默认 console 已带颜色/异常转换；只替换「级别字段」是改动面最小、且不丢既有形态的做法。无锚点时 `%X{..:-}` 渲染为空 ⇒ 非 AI 行与批之前逐字相同 |
| D3 | 事件行在非锚点线程（HTTP 决策、跨实例唤醒）怎么保证 runId 正确 | 新增 `AiTrace.eventOn(runId, sessionId, …)`：显式值优先、缺省回落 MDC | 若只靠 MDC，`POST /confirm` 这类线程漏绑就产出 `runId=-` 的孤儿行；显式锚点让"行里是谁"不依赖调用线程的运气 |
| D4 | `turn_end` 覆盖哪些终态 | **五个出口全覆盖**：`done` / `failed` / `empty_reply`（归入 failed）/ `blocked` / `cancelled` | "按 runId grep 一定有收尾行"是手册第一条承诺；少一个出口就等于少一条线索 |
| D5 | `upstream_first_byte` 在哪打 | 在**驱动线程**上、本次尝试落定后补写（时刻值仍取真首包时刻） | `subscribe` 回调跑在 Reactor 线程（MDC 已不在），在那里打会得到无锚点行 |
| D6 | 空回复/阻断路径的 `TurnContext` | 给 `emptyReply`/`fail` 各补一个 `TurnContext` 参数（私有方法， caller 已有） | 避免为"少改签名"而把 `totalMs` 写成 ≈0；有真值就用真值 |
| D7 | 事件速率 | 每轮新增 INFO ≤ 15 行（实测一条工具轮：`turn_accepted`+`turn_start`+`first_byte`+2×`tool_call_*`+2×`approval_*`+`turn_end` = 8 行）；**不在 delta 循环里打** | 硬纪律；`consume()`/`drain()` 零改动即为证据 |
| D8 | 工具事件与审批事件的"唯一出口" | 工具：`SpToolCallingManager` 的 toolStart/结算两点；审批：`ConfirmGate.traceDecision`（submitDecision/submitFrontendResult/cancel/expireFrontend 四处共用） | 出口唯一 = 不会出现"某个分支忘了打" |
| D9 | 既有中文日志行要不要全量改格式 | **不改**（只在关键节点旁新增结构化行） | 全量改写既有 60+ 条日志 = 大面积改动 + 与批① 冲突；本批目标是"可追踪"，不是"重写日志" |

---

## 3. 事件矩阵（事件 × 落点 × 字段）

| 事件 | 级别 | 落点（file:line，施工后） | 关键字段 |
|---|---|---|---|
| `turn_accepted` | INFO | `ai/web/AiController.java` chat 提交前 | `context` `model` `chars` |
| `turn_rejected` | INFO | 同上（池饱和分支） | `code=SUSPEND_POOL_SATURATED` |
| `turn_start` | INFO | `ai/run/ResilientChatService.java` chat / driveResumed | `model` `provider` `attempt` `resumed` `context` `tools`/`history` `compact` `maxAttempts` `budgetMs` |
| `upstream_first_byte` | INFO | `ResilientChatService.runAttempts`（成功/异常两出口） | `attempt` `ms` `chars` `events` |
| `turn_retry` | INFO | `ResilientChatService.runAttempts` 重试分支 | `attempt` `nextAttempt` `code` `reason` `emitted` `sideEffect` `upstreamEvents` `elapsedMs` |
| `tool_call_start` | INFO | `ai/gate/SpToolCallingManager.java` toolStart 之后 | `toolCallId` `tool` `kind` `approval` `index` |
| `tool_call_end` | INFO | 同文件、结算之后 | `toolCallId` `tool` `kind` `ms` `ok` `outcome` `code` `executed` `chars` |
| `approval_suspend` | INFO | `ai/gate/ConfirmGate.java` await（卡已发、等待开始） | `toolCallId` `tool` `kind` `timeoutS` `preflight` |
| `approval_decided` | INFO | `ConfirmGate.traceDecision`（4 处决策来源） | `toolCallId` `decision` `actor` `outcome` `waitedMs` `status` |
| `compaction` | INFO | `ai/context/ContextCompactor.java` 压缩成功处 | `level` `reason` `before` `after` `savedTokens` `lostTokens` `turns` `clearedPairs` `limit` |
| `turn_cancelled` | INFO | `ResilientChatService.cancelTerminal` | `attempts` `partialChars` `upstreamEvents` |
| `turn_end` | INFO | `ResilientChatService.emitTurnEnd`（5 个终态出口共用） | `status` `code` `attempts` `totalMs` `activeMs` `gateWaitMs` `toolCalls` `chars` `inputTokens` `outputTokens` `totalTokens` `est` `model` `upstreamEvents` |

MDC 落点清单：

| 位置 | 手法 |
|---|---|
| `AiController.chat` | 提交前 `AiTrace.bind(runId, sessionId)`，`finally` 摘除 |
| `aiRunExecutor` / `aiResumeTrigger` | `AiMdcTaskDecorator`（`TaskDecorator`，提交时刻抓快照） |
| `SseChatEmitter.submitDrain` | `AiTrace.wrap(...)`（投递池是裸 `ThreadPoolExecutor`） |
| `ContextSummaryClient.summarize` | `AiTrace.wrapCallable(...)`（虚拟线程池，MDC 不继承） |
| `ResilientChatService.chat` / `driveResumed` | 入口 `bind`，`finally` 摘除 |
| `ConfirmGate.await` | 取出 `sessionId` 后 `bind`（覆盖挂起期心跳/续租/取消） |
| `ResumeService.resume` | 读快照后 `bind`，`finally` 摘除（驱动线程由 `driveResumed` 再绑） |
| `StartupResumeRunner` | 每轮循环 `bind` + `finally` 摘除 |
| `AiController.confirm` / `frontendToolResult` | 包住 `ConfirmGate` 调用（`bind` + `finally` 摘除） |

---

## 4. 红测留痕

### 4.1 红（先写用例、未写实现）

```
$ mvn -o test -Dtest='AiTraceTest,AiTurnLogTraceTest,AiMdcPropagationTest,AiApprovalLogTraceTest'
[ERROR] Tests run: 20, Failures: 10, Errors: 0, Skipped: 0
  AiApprovalLogTraceTest.enteringTheGateEmitsApprovalSuspend     缺少事件行 event=approval_suspend；实收 = []
  AiApprovalLogTraceTest.rejectedSubmissionStillLeavesATraceLine 缺少事件行 event=approval_decided；实收 = []
  AiApprovalLogTraceTest.submittingADecisionEmitsApprovalDecidedWithWaitTime 同上
  AiMdcPropagationTest.summaryCallRunsWithTheCallerAnchor        [摘要旁路线程同样带着这一轮的锚点]
  AiMdcPropagationTest.productionSuspendPoolIsWiredWithTheDecorator [挂起池（aiRunExecutor）必须装 AiMdcTaskDecorator]
  AiTurnLogTraceTest.successfulTurnEmitsStartFirstByteAndEndInOrder [一轮至少要有起点、首包、终点三行]
  AiTurnLogTraceTest.everyEventOfATurnCarriesTheSameRunId
  AiTurnLogTraceTest.eventLinesNeverCarryConversationContent
  AiTurnLogTraceTest.failedTurnEmitsRetryAndFailedTurnEnd
  AiTurnLogTraceTest.resumedTurnIsMarkedResumedAndStillEndsWithTurnEnd
```

`AiTraceTest`（8 条）当时即绿：它测的是本批新增的**设施本身**（`AiTrace` 先写好并单独自洽），
其余三类的红全部来自"事件/装饰器还没接上生产代码"——正是本批要补的缺口。

### 4.2 绿（实现后）

```
$ mvn -o test -Dtest='AiTraceTest,AiTurnLogTraceTest,AiMdcPropagationTest,AiApprovalLogTraceTest,AiToolCallLogTraceTest,AiCompactionLogTraceTest,AiLogPatternConfigTest'
[INFO] Tests run: 28, Failures: 0, Errors: 0, Skipped: 0
```

### 4.3 用例矩阵（断言只升不降）

| 类 | 条 | 锁的是什么 |
|---|---|---|
| `AiTraceTest` | 8 | MDC 装/摘/换轮、`wrap` 跨线程传且不留痕、事件行形态、单行化 + 截断、缺锚点渲染 `-` |
| `AiTurnLogTraceTest` | 8 | `turn_start→first_byte→turn_end` 有序且字段齐；runId 贯穿；**正文负断言**；失败轮有 `turn_retry`；空回复/阻断/续跑三个出口都有 `turn_end`；轮后不留锚点 |
| `AiApprovalLogTraceTest` | 3 | `approval_suspend` 必现（含 kind/timeoutS）；`approval_decided` 带 `decision/actor/waitedMs/outcome`；`NOT_FOUND` 也留痕 |
| `AiToolCallLogTraceTest` | 2 | `tool_call_start/end` 成对且 toolCallId 对齐、`approval=false`、`ms/ok/outcome/executed` 齐；**入参/出参负断言** |
| `AiCompactionLogTraceTest` | 2 | `compaction` 带 `level/reason/savedTokens/limit`；未压缩不出行 |
| `AiMdcPropagationTest` | 3 | `TaskDecorator` 传锚点；**生产 bean `aiRunExecutor` 真装了装饰器**；摘要旁路线程带锚点 |
| `AiLogPatternConfigTest` | 2 | ①配置字面含 `%X{runId}` / `%X{sessionId}`；②**把该 pattern 真塞进 `PatternLayout` 排版**：绑锚点的行出现 runId/sessionId、未绑的行什么都不多出来（配置回归最难被发现，故双锁） |

---

## 5. 门禁

| 门禁 | 命令 | 结果 |
|---|---|---|
| 后端全量单测 | `cd backend && <MAVEN_HOME>/bin/mvn -o test` | **Tests run: 1271, Failures: 0, Errors: 0, Skipped: 0**；BUILD SUCCESS【实测】 |
| 基线核对 | 基线 1243 + 新增 28 = 1271 | 数目吻合，无既有用例被删除/跳过 |
| 门禁稳定性 | 同一命令多跑 | 1268/0/0（用例集 27，代码未定稿）→ 1271/0/0（用例集 28）→ **1271/0/0（最终代码，权威数字）**。中间一次全量跑出现 **1 个测试侧 `ConcurrentModificationException`**（`ListAppender.list` 被另一线程追加时直接 for-each）→ 已改为快照拷贝后重跑，全绿【实测】 |
| 前端 | 本批零改动 | 未跑 typecheck/build（无变更面）；如需强制，可按批单约定说明 |
| 文档一致性 | `node scripts/ci/check-docs.mjs` | **6/6 断言 PASS**【实测】 |
| 帧契约 16 | 未新增/未改帧型 | `SseChatEmitter` 的帧方法零改动（仅投递线程加 MDC 包装）；`FrameContract` 相关用例全绿 |

删除行清单（全量 11 行，均为必要替换，供合流评审）：

```
ContextSummaryClient.java    executor.submit(lambda)           → wrapCallable(lambda)
ConfirmGate.java             public submitDecision(...)         → 拆成 public 包装 + private doSubmitDecision
ResilientChatService.java    fail(..., int partialChars, ...)   → fail(..., Accumulator, ...)
ResilientChatService.java    emptyReply(...)                    → 多一个 TurnContext 参数
SseChatEmitter.java          deliveryExecutor.execute(lambda)   → AiTrace.wrap(lambda)
AiController.java            submission = confirmGate.submit…   → 包 try/finally 装锚点（两处）
```

---

## 6. 文件清单

### 6.1 新增（主代码，2）

| 文件 | 行数量级 | 职责 |
|---|---|---|
| `backend/src/main/java/com/example/configmgr/ai/observe/AiTrace.java` | ~200 | MDC 装/摘、`wrap`/`wrapCallable`、`event`/`eventOn`、单行化 + 截断 |
| `backend/src/main/java/com/example/configmgr/ai/observe/AiMdcTaskDecorator.java` | ~25 | `ThreadPoolTaskExecutor` 的 MDC 传递装饰 |

### 6.2 修改（11）

| 文件 | 改动性质 |
|---|---|
| `backend/src/main/resources/application.yml` | +4 行（`logging.pattern.level`） |
| `backend/src/main/java/.../ai/exec/AiExecutorConfig.java` | +5 行（两个池装装饰器） |
| `backend/src/main/java/.../ai/web/AiController.java` | +38/−3（入口锚点、`turn_accepted`/`turn_rejected`、confirm/回灌锚点） |
| `backend/src/main/java/.../ai/run/ResilientChatService.java` | +101/−4（锚点、`turn_start`/`first_byte`/`turn_retry`/`turn_cancelled`/`turn_end`、首包计时） |
| `backend/src/main/java/.../ai/gate/ConfirmGate.java` | +58/−1（`approval_suspend`/`approval_decided`、等待期锚点） |
| `backend/src/main/java/.../ai/gate/SpToolCallingManager.java` | +14（`tool_call_start`/`tool_call_end`） |
| `backend/src/main/java/.../ai/context/ContextCompactor.java` | +5（`compaction`） |
| `backend/src/main/java/.../ai/context/ContextSummaryClient.java` | +3/−1（摘要旁路 MDC） |
| `backend/src/main/java/.../ai/run/SseChatEmitter.java` | +3/−1（投递线程 MDC） |
| `backend/src/main/java/.../ai/run/ResumeService.java` | +6（续跑入口锚点） |
| `backend/src/main/java/.../ai/run/StartupResumeRunner.java` | +6（启动扫描逐轮锚点） |

### 6.3 新增（测试，7 类 28 条）

`ai/observe/AiTraceTest.java`、`ai/observe/AiTurnLogTraceTest.java`、`ai/observe/AiMdcPropagationTest.java`、
`ai/observe/AiLogPatternConfigTest.java`、`ai/gate/AiApprovalLogTraceTest.java`、`ai/gate/AiToolCallLogTraceTest.java`、
`ai/context/AiCompactionLogTraceTest.java`

### 6.4 文档（2）

`docs/AI对话日志链路追踪手册.md`（字段字典 + grep 菜谱 + 故障模式表 + 端点配合 + 安全边界）、
本件（施工报告）。

---

## 7. 样例日志段【实测，单测实收】

> 环境说明：JUnit 直跑（无 Spring 日志系统），故行首**不带** `logging.pattern.level` 渲染的锚点；
> 生产形态请按 `%5p %X{runId:-} %X{sessionId:-}` 在级别后补上两个锚点列。
> 数据来自 `InMemoryRunStore` + `ChatClient` 桩（非真实上游），字段与取值域与生产一致。

### 7.1 一轮成功的完整链（首轮，无工具）

```
event=turn_start runId=run-logtrace-1 sessionId=s-logtrace-1 model=test-model provider=test-provider attempt=1 resumed=false context=* tools=0 inputEst=8 compact=none newSession=true maxAttempts=2 budgetMs=300000
event=upstream_first_byte runId=run-logtrace-1 sessionId=s-logtrace-1 attempt=1 ms=5 chars=6 events=0
event=turn_end runId=run-logtrace-1 sessionId=s-logtrace-1 status=done code=none attempts=1 totalMs=13 activeMs=13 gateWaitMs=0 toolCalls=0 chars=12 inputTokens=517 outputTokens=0 totalTokens=517 est=true model=test-model upstreamEvents=2
```

### 7.2 断流 → 重试 → 失败收尾

```
event=turn_start runId=run-logtrace-1 sessionId=s-logtrace-1 model=test-model provider=test-provider attempt=1 resumed=false ...
event=turn_retry runId=run-logtrace-1 sessionId=s-logtrace-1 attempt=1 nextAttempt=2 code=UPSTREAM_STREAM_ERROR reason=upstream stream error emitted=false sideEffect=false upstreamEvents=0 elapsedMs=7
event=turn_end runId=run-logtrace-1 sessionId=s-logtrace-1 status=failed code=UPSTREAM_STREAM_ERROR attempts=2 totalMs=12 activeMs=12 gateWaitMs=0 toolCalls=0 chars=0 inputTokens=517 outputTokens=0 totalTokens=517 est=true model=test-model upstreamEvents=0
```

### 7.3 工具调用（成对）

```
event=tool_call_start runId=run-toollog sessionId=s-toollog toolCallId=call-toollog-1 tool=echo kind=BACKEND approval=false index=0
event=tool_call_end runId=run-toollog sessionId=s-toollog toolCallId=call-toollog-1 tool=echo kind=BACKEND ms=34 ok=true outcome=ok code=- executed=true chars=12
```

### 7.4 审批（挂起 → 决策）

```
event=approval_suspend runId=run-approval-log sessionId=- toolCallId=call-approval-1 tool=start_export kind=confirm timeoutS=none preflight=false
event=approval_decided runId=run-approval-log sessionId=- toolCallId=call-approval-1 decision=approve actor=user outcome=ACCEPTED waitedMs=4334 tool=start_export status=APPROVED
event=approval_decided runId=run-approval-log sessionId=- toolCallId=call-missing decision=approve actor=user outcome=NOT_FOUND waitedMs=-1 tool=- status=-
```

### 7.5 压缩

```
event=compaction runId=run-compact-log sessionId=s-compact-log level=L1 reason=l1_tool_result_clearing before=1128 after=978 savedTokens=150 lostTokens=0 turns=0 clearedPairs=1 summaryChars=0 limit=128000
```

### 7.6 安全负断言（用例实收，等价于"没有这些行"）

```
AiToolCallLogTraceTest.toolArgumentsAndResultsNeverReachTheTraceLines   PASS
AiTurnLogTraceTest.eventLinesNeverCarryConversationContent            PASS
```

---

## 8. 未做 / 挂账 / 风险

| # | 项 | 说明 |
|---|---|---|
| G1 | **未做运行时实证**（未起服务、未连 Redis/真实上游） | 按硬纪律"不起任何服务"。事件由单测在真实代码路径上产出（`ResilientChatService` / `SpToolCallingManager` / `ConfirmGate` / `ContextCompactor` 全为生产类），但"真上游 + 真 Redis"下的行数与耗时分布未抽样。建议合流后跑一次 `scripts/verify-e2e.ps1 -BackendLog` 顺带核对 |
| G2 | `turn_end.totalMs` 在**取消**与**阻断**路径上的起点 | 用调用方传下来的 `TurnContext.turnStartedAtMs`（真值）；只有 `fail()` 从重试循环带出的同一 `turn` 对象，无近似值 |
| G3 | `turn_end.est` 的语义 | 与 `done.usage.est` 同源；`est=true` 时三维 token 是估算。手册已写明 |
| G4 | 日志 pattern 的渲染效果 | 已用 `PatternLayout` 做**离屏渲染实证**（`AiLogPatternConfigTest#boundLinesRenderTheAnchorAndUnboundLinesStayClean`）：绑锚点行出现两列、未绑行不多出任何字符。真实进程内渲染仍建议合流后按 7.1 的形态抽一眼 |
| G5 | 与批① 的合流 | 双方都改 `ResilientChatService`/`SseChatEmitter`/`ContextSummaryClient`/`application.yml`。本批在这 4 处只增行（`application.yml` 改动在 `logging:` 段末尾，批① 改的是 `app.ai.resilience` 段，位置不重叠）。**合流顺序：批① 先** |
| G6 | 事件行的字段顺序是契约 | 手册 §2 已按产出顺序登记；后续新增字段请**追加在末尾**，不要插队（grep/awk 菜谱依赖位置无关的 `k=v`，但人工比对依赖顺序稳定） |
| G7 | `compaction` 只在压缩成功时写 | 跳过/失败路径不写（保持"没发生"与"发生 0 次"可区分）；若要追"为什么没压"，看既有 DEBUG「上下文压缩跳过」行 |
| G9 | 测试侧并发 | `ListAppender` 的 `list` 会被悬挂线程并发追加，遍历必须走快照（本批已改）。后续复用同类断言请照此写法 |
| G8 | 速率上限 | 每轮新增 INFO 实测 8 行（一条工具轮）；无 delta 循环内打点。多工具轮按 2 行/工具线性增长（仍远低于 15 的常见形态；工具数 > 4 时会逼近上限，属可接受） |

---

## 9. 复核命令（可直接跑）

```bash
cd <WORKTREE>/backend && <MAVEN_HOME>/bin/mvn -o test            # 期望 1271 / 0 / 0
cd <WORKTREE> && node scripts/ci/check-docs.mjs                  # 期望 6/6 PASS
cd <WORKTREE> && git status --short                              # 期望：11 改 + 7 新增（均未提交）

# 只跑本批用例
cd <WORKTREE>/backend && <MAVEN_HOME>/bin/mvn -o test \
  -Dtest='AiTraceTest,AiTurnLogTraceTest,AiMdcPropagationTest,AiLogPatternConfigTest,AiApprovalLogTraceTest,AiToolCallLogTraceTest,AiCompactionLogTraceTest'
```
