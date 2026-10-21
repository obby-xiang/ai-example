# 施工报告 · 超时面治理批②（语义 6 项）· DS-Flash

- 日期：2026-10-21
- 施工 agent：**agent-1414**（自证：本会话 `agents/agent-1414/wire.jsonl` 的 `profile.bind` 单行 = `agentId=agent-1414`、`modelAlias=DeepSeek/deepseek-flash`、`profileName=coder`、`thinkingEffort=high`）
- 授权：用户 2026-10-21「超时治理开始实施」+ 决策看板 `docs/决策看板.md:14`（段级预算重置式 + `max-tool-steps=25` 步数笼不重置）、`:16`（批② 数值集：后端工具 120s + `TOOL_TIMEOUT` / 前端静默 45s / 前端重连 4 次 1·2·4·8s + jitter / socket 兜底 300s）
- 工地：`../wt-timeout-b2`（`<REPO_ROOT>` 下；分支 `feature/timeout-b2`，基线 `d20d75d` =「merge: AI对话日志链路追踪批合流」，即开工 fetch 到的最新一笔；`main` 已含超时批①）
- 纪律：未 commit / 未 push；未动两张看板、未动 `.gitignore`；帧契约 16 冻结（本批**零帧型新增**）；界面零 emoji；密钥 0 字面；未起任何服务。

## DC-19 首页（结论先行）

| 项 | 交付 | 状态 |
| --- | --- | --- |
| ① 段级预算重置式 | `TOTAL_BUDGET` 码名保留、判据改「段起点」；挂起点段结束、恢复点（决策/回灌/续跑）段重置；`gateWaitMs` 保留为观测面 | 完成（行为红 2 条 → 绿；旧扣除式断言 3 条**原样保留**并复跑绿） |
| ② `max-tool-steps=25` | 新键 `app.ai.resilience.max-tool-steps`；步数累计**跨挂起不重置**（`RunSnapshot.toolSteps` 外置）；到顶降级「部分完成收尾」 | 完成（结构红 3 处 → 绿：4 例） |
| ③ 后端工具独立超时 | 新键 `app.ai.tool.exec-timeout=120s`；`TOOL_TIMEOUT`（三方同步）；`executed=false`、轮继续 | 完成（4 例；码表三方同步由 `ErrorCodesContractTest` 3/3 锁） |
| ④ 前端静默 45s | `sse.ts` `idleTimeout` 90s→45s（< 代理 60s 死区）；后端 `inter-event-timeout` 90s **不动** | 完成（红：`expected 90000 to be 45000`） |
| ⑤ 前端重连 4 次 | 1/2/4/8s + jitter ±25%；耗尽仍 `settle(false)` | 完成（红 3 条 → 绿：`reconnects=4` + 5 条连接） |
| ⑥ socket 兜底 300s | 上游流式 `WebClient` 读空闲 300s（`AiProviderRegistry` 构造点；本仓无 reactor-netty ⇒ JDK 连接器，见待裁项 1） | 完成（2 例） |

## 一、矩阵先行（用例 × 维度 × 期望 × 落点）

> 维度口径：**行为** = 可观察事件/结局；**数值** = 常量/配置；**契约** = 码表/帧/信封；**观测** = 日志/取证字段。
> 落点 = 承载断言的测试类（`BE/` = `backend/src/test/java/com/example/configmgr/`，`FE/` = `frontend/tests/`）。

### ① 段级预算重置式（裁决看板 `:14`）

| # | 用例 | 维度 | 期望 | 落点 |
| --- | --- | --- | --- | --- |
| 1-1 | 单段 300s 用尽（无挂起） | 行为 | 段内用满预算即 `TOTAL_BUDGET`（旧语义**保留**） | `ai/run/StreamWatchdogGateWaitBudgetTest#totalBudgetStillFiresWithoutGateWait`（既有，不改） |
| 1-2 | 挂起期间（确认门/无超时工具） | 行为 | 挂起中**不判** `TOTAL_BUDGET`（段已结束） | 同上 `#totalBudgetPausesWhileGateWaits`（既有，不改） |
| 1-3 | 恢复点段重置 | 行为 | 等待前已用 1.2s + 等待 2.5s + 恢复后 1.5s（预算 2s）⇒ **不判**（旧扣除式 2.5s 已判死） | `ai/run/StreamWatchdogSegmentBudgetTest#resumeRestartsSegmentBudget`（新） |
| 1-4 | 跨两次挂起重置 | 行为 | 每次恢复都重新计时，累计不跨段 | `ai/run/StreamWatchdogSegmentBudgetTest#eachResumeRestartsSegment`（新） |
| 1-5 | 恢复后段内用满 | 行为 | 恢复后本段 >2s 仍判 `TOTAL_BUDGET`（不因重置变「永不超时」） | `ai/run/StreamWatchdogSegmentBudgetTest#firesOnceTheNewSegmentIsSpent`（新） |
| 1-6 | 段锚点 / 挂起态观测面 | 观测 | `segmentStartedAtMs` 在恢复点前移；`inGateWait` 与 `gateWaitMs` 语义不变、`clear` 清理 | `ai/run/ToolActivityBeaconSegmentTest`（新，4 例） |
| 1-7 | `gateWaitMs` 保留 | 观测 | 仍是累计等待量（日志批 `turn_end.gateWaitMs` 不断供） | `#beaconAccumulatesGateWaitIncludingTheOngoingWindow`（既有，不改） |
| 1-8 | 兜底 `awaitSettled` 段基 | 行为 | 兜底按「段」切片（budget+1s），挂起中暂停，恢复后新段重新拥有 budget | 由 1-3/1-5 的段语义 + `ResilientChatService#segmentElapsedMs`（与看门狗同源）承载 |

### ② `max-tool-steps=25`（裁决看板 `:14`）

| # | 用例 | 维度 | 期望 | 落点 |
| --- | --- | --- | --- | --- |
| 2-1 | 第 26 步到顶 | 行为 | 到顶调用不执行：`outcome=skipped` / `code=TOOL_STEP_LIMIT` / `executed=false` | `ai/gate/SpToolCallingManagerStepLimitTest#stepLimitSkipsTheCallBeyondTheBudget`（新） |
| 2-2 | 到顶降级「部分完成收尾」 | 行为 | 历史末尾追加收尾指令；**不判整轮失败**（正常返回 `ToolExecutionResult`） | 同上 `#stepLimitAppendsAWrapUpInstruction`（新） |
| 2-3 | 步数累计跨挂起不重置 | 契约 | `RunSnapshot.toolSteps` 外置；seed 25 后单调用即被跳过 | 同上 `#stepCountSurvivesSuspension`（新） |
| 2-4 | 未到顶行为不变 | 行为 | <25 步照常执行 | 同上 `#belowTheLimitNothingChanges` + 既有批次用例全绿 |

### ③ 后端工具独立超时（裁决看板 `:16`）

| # | 用例 | 维度 | 期望 | 落点 |
| --- | --- | --- | --- | --- |
| 3-1 | 工具超时（测试注入 250ms） | 契约 | `outcome=timeout` / `code=TOOL_TIMEOUT` / `executed=false` / 不写台账 | `ai/gate/SpToolCallingManagerToolTimeoutTest#timesOutIntoTimeoutOutcome`（新） |
| 3-2 | 到点轮继续 | 行为 | 同批后续调用照常执行；`haltsBatch` 不触发（只认 `failed`） | 同上 `#timeoutKeepsTheRoundGoing`（新） |
| 3-3 | 与前端回填超时区分 | 契约 | `TOOL_TIMEOUT` ≠ `FRONTEND_TIMEOUT` / `CONFIRM_TIMEOUT` | 同上 `#codeIsDistinctFromFrontendTimeout`（新） |
| 3-4 | 三方码表同步 | 契约 | `ErrorCodes` ↔ `frontend/src/types/api.ts` ↔ `messages_zh/en` | `common/ErrorCodesContractTest`（既有，自动覆盖，绿） |
| 3-5 | 配置缺省 | 数值 | `app.ai.tool.exec-timeout = 120s` | 同上 `#defaultTimeoutIsTwoMinutes`（新） |

### ④ 前端静默 90s→45s（裁决看板 `:16`）

| # | 用例 | 维度 | 期望 | 落点 |
| --- | --- | --- | --- | --- |
| 4-1 | 常量口径 | 数值 | `DEFAULT_IDLE_TIMEOUT_MS = 45_000` | `FE/sse-watchdog.spec.ts#常量口径`（改断言 90_000→45_000） |
| 4-2 | 45s 先于代理 60s、先于后端 90s（错层） | 数值 | 45s < 60s（代理死区）< 90s（后端 inter-event，不动） | 同上 `#45s 低于代理 60s 死区…`（新） |
| 4-3 | 45s 到点即重挂 | 行为 | `onopen` 后 44s 不动；45s 到点关连接并按退避重挂 | 同上 `#帧间静默 45s 到点即断流并重挂`（新） |

### ⑤ 前端重连 4 次 + jitter（裁决看板 `:16`）

| # | 用例 | 维度 | 期望 | 落点 |
| --- | --- | --- | --- | --- |
| 5-1 | 退避序列 | 数值 | 1/2/4/8s 基数；jitter ±25%（0.75× / 1.0× / 1.25× 三点全锁） | `FE/sse-watchdog.spec.ts#退避序列`（新） |
| 5-2 | 次数 4 | 行为 | 建连黑洞 = 5 条连接（首连 + 4 次重挂）后 `settle(false)`、`reconnects=4` | 同上 `#建连黑洞`（改断言：3→5 条连接） |
| 5-3 | 挂起即断不重连 | 行为 | B1 口径不变（收卡面帧后收流 = 正常收尾） | 既有 `FE/b1-suspend-disconnect.spec.ts`（未改，绿） |

### ⑥ socket 兜底 300s（裁决看板 `:16`）

| # | 用例 | 维度 | 期望 | 落点 |
| --- | --- | --- | --- | --- |
| 6-1 | 上游 WebClient 读空闲兜底 | 数值 | 连接器 = JDK 连接器且 `readTimeout = 300s` | `ai/config/AiProviderRegistryUpstreamTimeoutTest#streamingWebClientHasReadTimeout`（新） |
| 6-2 | 不遮蔽 watchdog | 数值 | 300s > `inter-event-timeout` 90s ⇒ 静默判定总先于 socket 层；且 ≥ 段预算 | 同上 `#socketBackstopNeverPreemptsTheWatchdog`（新） |

## 二、红测先行（留痕）

| # | 项 | RED 命令 | RED 结果（原文摘要） | GREEN |
| --- | --- | --- | --- | --- |
| R1 | ① 段预算（**行为红**，实现前跑） | `mvn -o test -Dtest=StreamWatchdogSegmentBudgetTest` | `Tests run: 3, Failures: 2`；`resumeRestartsSegmentBudget:89`「恢复点段重置：恢复后 1.4s < 2s 预算，不得判 TOTAL_BUDGET（旧扣除式会误判 2.6s）」；`eachResumeRestartsSegment:113` 同族 —— 旧扣除式在 5s 那拍把 `墙钟 5.0 − 等待 2.5 = 2.5s` 判成超预算 | 3/3 绿 |
| R2 | ② 步数笼（**结构红**，实现前跑） | `mvn -o test -Dtest=SpToolCallingManagerStepLimitTest` | 编译期 3 处：`找不到符号 方法 getToolSteps()` / `setToolSteps(int)`（`RunSnapshot`）—— 「外置计数」这个交付点当时完全不存在 | 4/4 绿 |
| R3 | ④⑤ 前端（**行为红**：`git stash` 只回退 `sse.ts` 后跑） | `vitest run tests/sse-watchdog.spec.ts` | `Tests 5 failed \| 2 passed`；`expected 90000 to be 45000`、`expected 90000 to be less than 60000`、`expected { terminal: false, … } to be null`（旧 3 轮口径下提前 settle）、`expected +0 to be 1` | 7/7 绿 |
| R4 | ③ 工具超时 / ⑥ socket 兜底 | 实现后由断言锁定 | **未做回滚式红测，如实登记**：两项都是"此前不存在的能力"（无工具执行超时、无读超时），旧代码下无从断言"到点产出 `TOOL_TIMEOUT`" —— 性质同 R2（API 不存在），但未单独留一次编译失败输出 | 4/4、2/2 绿 |
| — | ① 观测面（`ToolActivityBeaconSegmentTest`） | 新增契约用例 | 实现前无该 API，无旧行为可红 | 4/4 绿 |

GREEN 汇总（新增 5 个测试类 / 17 例，全绿）：`StreamWatchdogSegmentBudgetTest`(3) · `ToolActivityBeaconSegmentTest`(4) · `SpToolCallingManagerStepLimitTest`(4) · `SpToolCallingManagerToolTimeoutTest`(4) · `AiProviderRegistryUpstreamTimeoutTest`(2)。

**一次真实的实现缺陷（留痕，不修饰）**：首轮全量出现 **39 条失败**（`ChatModelContractTest` / `ContextFrameWiringTest` / `ConvergeMemoryToolCallStatesTest` / `EmptyReplyTerminalTest` / `ChatTurnFrameSequenceConformanceTest` / `AiTurnLogTraceTest` 六类，帧流尾部都是 `error`）。根因 = 轻装配面把 `ToolActivityBeacon` mock 掉，`segmentStartedAtMs` 读回 **0** ⇒ 段已用时长 = "从纪元到现在" ⇒ 兜底当场判 `TOTAL_BUDGET`。修法 = 两处调用点对 `start <= 0` 回落到播种值（`StreamWatchdog#tick`、`ResilientChatService#segmentElapsedMs`），六类复跑全绿。

## 三、语义变更清单（旧断言 → 新断言 → 裁决依据）

> 纪律：**能保留的旧行为断言一律保留**；确与裁决冲突的逐条列出，**禁止静默删改**。

| # | 文件 / 用例 | 旧断言 | 新断言 | 性质 | 裁决依据 |
| --- | --- | --- | --- | --- | --- |
| S1 | `StreamWatchdogGateWaitBudgetTest`（3 例） | 挂起期间不判预算；无挂起时照常判；`gateWaitMs` 含进行中窗口 | **一字未改**（3/3 复跑绿） | **保留**（段语义下仍成立：挂起中段已结束；段内用满照判；`gateWaitMs` 转观测面但累计语义不变） | 看板 `:14`（只改判据来源，不改"等人不算"与"用满就判"） |
| S2 | `FE/sse-watchdog.spec.ts`「常量口径」 | `DEFAULT_IDLE_TIMEOUT_MS === 90_000` | `=== 45_000`；并加 `DEFAULT_MAX_RECONNECTS === 4` | **改（数值）** | 看板 `:16`「前端静默 90 → 45s」「前端重连 4 次」 |
| S3 | 同上「建连黑洞」 | 3 条连接、47s 内不 settle、48s 内收敛（退避 1/2s） | 5 条连接、89s 内不 settle、90s 内收敛（退避 1/2/4/8s，注入 `random=0.5` 消抖）；**新增** `reconnects === 4` 值断言 | **改（次数）+ 加断言** | 看板 `:16`「重连 4 次 / 1·2·4·8s + jitter」 |
| S4 | 同上「onopen 后建连看门狗让位」 | 用例标题写"只留 90s 帧间看门狗"（**仅措辞**，20s 推进的断言不动） | 标题改 45s | **改（措辞）** | 同上（断言强度不变） |
| S5 | 同上其余 2 例（`abort 立即收尾`、`onopen 让位`正文） | — | **未改** | 保留 | — |
| S6 | `DEFAULT_CONNECT_TIMEOUT_MS` 注释（非断言） | 「三轮预算 15+1+15+2+15=48s ≤ 90s 帧间」 | 「建连预算按每条连接计；4 次重挂的最坏恢复窗口 = 5×15 + 15 = 90s，与 45s 帧间判据是两个维度」 | **改（口径注）** | 看板 `:16`（次数变了，旧算式自相矛盾） |
| S7 | `docs/evidence/补档-NUMSPEC-清单总账.md` F1-02 / F1-03 / F1-05 / F1-06 / B1-14 | 90_000 / 2 次 / 无抖动 / total-budget = 单轮总预算（扣除式） | 45_000 / 4 次 / 1·2·4·8s + jitter / **段**预算；追加 B2-01~B2-04（25 步 / 120s / 300s / ±25%） | **改（数字台账）** | 看板 `:14` / `:16`（"禁裸数字"的登记面必须同步） |
| S8 | `docs/03-技术方案文档-v2.1.md` | 原文「人机预算分离 = 墙钟 − 挂起等待」「total-budget = 单轮总预算」 | **不回改**，文末追加注记逐条列差异 | 追加注记（仓内体例） | 同上 |

**断言只升不降核查**：本批**未删除任何测试用例**；改动集中在 S2/S3（数值与次数，S3 还多了一条值断言）。新增 5 类 17 例。

## 四、门禁

| 门 | 命令 | 结果 | 基线 |
| --- | --- | --- | --- |
| 后端全量 | `cd backend && mvn -o test` | **1314 / 1314 绿，0 failures / 0 errors**（202 类，surefire 求和） | 1297（开工实测，≥1279） |
| 前端单测 | `node node_modules/vitest/vitest.mjs run` | **822 / 822 绿**（56 文件） | 819 |
| 前端类型 | `node node_modules/vue-tsc/bin/vue-tsc.js --noEmit` | **0 错误** | — |
| 前端构建 | `TMP=<SCRATCH>/_tmpx TEMP=<SCRATCH>/_tmpx node node_modules/vite/bin/vite.js build` | **built ✓**（48.01s；前一次亦 ✓） | — |
| 文档一致性 | `node scripts/ci/check-docs.mjs` | **6/6 断言绿** | — |

> 基线口径：开工前在 worktree（`d20d75d`）上先跑了一次全量 = **1297/1297**（未含本批任何改动）；前端 819 为批① 合流后基线。前后端均**只增不降**。

## 五、文件清单

### 生产代码（8 文件 · 后端）

| 文件 | 改动 |
| --- | --- |
| `backend/src/main/java/com/example/configmgr/ai/run/ToolActivityBeacon.java` | 新增段锚点（`markSegmentStart` / `segmentStartedAtMs` / `inGateWait`）；`endGateWait` 在恢复点前移段起点；`gateWaitMs` 降为观测面；`clear` 一并清理 |
| `backend/src/main/java/com/example/configmgr/ai/run/StreamWatchdog.java` | 预算判据改「段起点」，挂起中跳过；删扣除式字段与 `elapsedSinceTurnStart`；类注改段预算口径 |
| `backend/src/main/java/com/example/configmgr/ai/run/ResilientChatService.java` | 轮开始/续跑入口播种段起点；`awaitSettled` 改段基（budget+1s 切片 + 挂起中暂停）；重试 `overBudget` 与退避剩余改段基 |
| `backend/src/main/java/com/example/configmgr/ai/run/RunSnapshot.java` | 新增 `toolSteps`（外置步数，跨挂起不重置）+ `asMap` 透出 |
| `backend/src/main/java/com/example/configmgr/ai/gate/SpToolCallingManager.java` | 步数笼（`skipAsStepLimit` + 收尾指令 + `persistToolSteps`）；工具独立超时（`executeWithTimeout` / `settleToolTimeout` / 内部超时信号 + MDC 传递）；注入 `AiProperties`（新增 13 参构造，旧 9/10/12 参保留） |
| `backend/src/main/java/com/example/configmgr/ai/config/AiProperties.java` | 新增 `Tool#execTimeout`（120s）与 `Resilience#maxToolSteps`（25），逐键注依据 |
| `backend/src/main/java/com/example/configmgr/ai/config/AiProviderRegistry.java` | 新增 `UPSTREAM_SOCKET_IDLE_TIMEOUT = 300s` + `upstreamWebClientBuilder()`，接入 `newApi` |
| `backend/src/main/java/com/example/configmgr/common/ErrorCodes.java`、`ai/tool/ToolOutcome.java` | 新增 `TOOL_TIMEOUT` / `TOOL_STEP_LIMIT` 码 + 两个 `CODE_*` 别名 |

### 生产代码（2 文件 · 前端）

| 文件 | 改动 |
| --- | --- |
| `frontend/src/api/sse.ts` | `DEFAULT_IDLE_TIMEOUT_MS` 90s→45s；`DEFAULT_MAX_RECONNECTS` 2→4；新增 `reconnectDelayMs`（指数 + ±25% jitter）与 `SseStreamOptions.random` 注入点；注释同步 |
| `frontend/src/types/api.ts` | 错误码镜像补 `TOOL_TIMEOUT` / `TOOL_STEP_LIMIT` |

### 配置与消息（3 文件）

`backend/src/main/resources/application.yml`（`max-tool-steps: 25`、`app.ai.tool.exec-timeout: 120s` + 两段注释）、`messages_zh.properties` / `messages_en.properties`（两条新码的人话）。

### 测试（5 新 + 1 改）

新增：`ai/run/StreamWatchdogSegmentBudgetTest.java`、`ai/run/ToolActivityBeaconSegmentTest.java`、`ai/gate/SpToolCallingManagerStepLimitTest.java`、`ai/gate/SpToolCallingManagerToolTimeoutTest.java`、`ai/config/AiProviderRegistryUpstreamTimeoutTest.java`；
改：`frontend/tests/sse-watchdog.spec.ts`（S2~S4）。

### 文档（3 文件）

本件；`docs/evidence/补档-NUMSPEC-清单总账.md`（F1-02/03/05/06、B1-14 改 + B2-01~B2-04 追加）；`docs/03-技术方案文档-v2.1.md`（文末追加口径注记，原文不回改）。

**未动**：`docs/决策看板.md`、`docs/任务看板.md`、`.gitignore`、帧类型集（16 冻结）、任何密钥。

## 六、待裁项 / 登记项

1. **socket 兜底的实现形态**：裁决原文是「`responseTimeout(300s)`」（reactor-netty 口径），但本仓编译依赖里**没有** `reactor-netty-http`（`spring-webflux` 在、starter 未引），实际缺省连接器是 JDK `HttpClient`（无读超时）。本批用 `JdkClientHttpConnector#setReadTimeout(300s)` 落地（读空闲超时，语义等价、依赖零新增）；若要严格对齐 reactor-netty 的 `responseTimeout`，需新增依赖 —— 请裁。
2. **长任务 opt-in 免超时**（IMPORT/EXPORT 等作业型工具）本批不做，已在 `AiProperties.Tool` 与 `application.yml` 登记为批③ 候选。
3. **`TOOL_STEP_LIMIT` 码名**由施工拟定（看板只裁数值 25）：结局 `skipped`（不触发首错即停）。如需改名或改结局请裁。
4. **到顶后的模型行为**：本实现"每轮把越界调用结算成 skipped + 追加收尾指令"，不抛错、**不改工具集**（改工具集要动请求 options 面）。若模型执意连发工具调用，最坏由段预算兜住；是否在后续批次"到顶后不再下发 tools"取决于请求面改造，待裁。
5. **恢复窗口与帧间判据**：重挂 4 次时整段恢复窗口最坏 90s（> 45s 帧间判据）。二者是不同维度（单条流的无进展 vs 断流后的恢复），已在注释与 NUMSPEC 记录；若要求"恢复窗口也必须 < 45s"，需下调建连上限或次数 —— 请裁。
6. **诚实边界**：工具超时的 `cancel(true)` 只是"请求中断"，不响应中断的桩体会继续在池线程跑完（结果被丢弃）；池饱和时退化为"同步执行、无超时"+WARN。两点都写在代码注释里，未隐藏。
