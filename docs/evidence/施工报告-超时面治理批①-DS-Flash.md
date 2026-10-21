# 施工报告 · 超时面治理批①（正确性/漂移 12 项）

> DC-19 首页：第一屏即可判断"谁施工、基于哪个 sha、12 项各落在哪、门禁什么数、还剩什么待裁"。

## 0. 身份、授权与结论（DC-19）

- 日期：2026-10-21
- 施工官自证（`profile.bind` 单行，取自本 agent 自己的 wire）：
  `{"type":"profile.bind","agentId":"agent-1406","modelAlias":"DeepSeek/deepseek-flash","profileName":"coder","thinkingEffort":"high"}`
  → **modelAlias `DeepSeek/deepseek-flash` / `coder` / `high`**；身份核对 = 同 session 目录内 `agent-1405` 首句为「文书提交官」，
  `agent-1406`（本棒）首句含「超时面治理批①」+「文书提交官」，二者互斥。
- 授权：用户 2026-10-21「超时治理开始实施」。
- 开工基线：**`cb420ff`**（开工时 = `origin/main`）。施工期间文书提交官推进一笔 **`0dad9ba`**
  （超时面治理与可观测性：调研 4 件 + 决策看板 + 任务看板 3 行立项）；按"撞车即基于新笔"的纪律，
  收工时已把工作树 rebase 到 **`0dad9ba`**（stash → `reset --hard 0dad9ba` → stash pop，零冲突），
  故本批实际基 = `0dad9ba`（重跑门禁见 §4 注）。
- 工地：仓库外 worktree `<WORKTREE>/wt-timeout-b1`（分支 `feature/timeout-b1`，基点已 `cb420ff → 0dad9ba`，**未 commit**）。
- 结论：**12 项全部落地，门禁全绿**（数字见 §4）。

## 1. 测试矩阵（先行；用例 × 维度 × 期望 × 落点）

| # | 项 | 用例（新增/加强） | 维度 | 期望 | 落点文件 |
|---|---|---|---|---|---|
| 1 | 流式重试退避 | `RetryBackoffTest`（新） | 公式/抖动/预算收缩 | 第 n 次重试基线 `500·2^(n-1)`；抖动 ∈ `[0.75,1.25]×基线`；剩余预算不足则收缩到 `剩余−1s` 且 ≥0；剩余 ≤0 ⇒ 0 | `ai/run/RetryBackoff.java`（新） |
| 1b | 同上（接线） | `ChatTurnFrameSequenceConformanceTest#incompleteUpstreamRetriesThenEndsWithAnErrorTerminal`（加强） | 端到端墙钟 | 重试公告到第二次尝试之间 ≥350ms（退避真的睡） | `ai/run/ResilientChatService.java` |
| 2 | 摘要重试 | `AiRetryDefaultsTest`（新） | 瞬时/永久/退避/预算占比 | 5xx(TransientAiException)/ResourceAccessException 重试至 3 次；`NonTransientAiException`(!=4xx) 只 1 次；退避实测第 1 次 450–900ms / 第 2 次 950–1400ms（抖动中性 ⇒ 500/1000）；生产常量总退避 ≤ `0.2 × summary-timeout(60s)` | `ai/config/AiRetryDefaults.java`（新）+ `AiRetryConfig` bean |
| 3 | /health 写预算 | `SseChatEmitterWriteTimeoutMetricTest`（新） | 指标=配置值 | `configureSharedDeliveryPool(.., 24000)` 后 `deliveryPoolMetrics().writeTimeoutMs == 24000`；未装配时回落 15000 | `ai/run/SseChatEmitter.java`、`RunRegistry.java` |
| 4 | 前端 60s 字面 | `tests/time-constants.spec.ts`（新；`tools-and-deadline.spec.ts` 一并复跑） | 单源三处 | `HTTP_TIMEOUT_MS === FRONTEND_TOOL_RESULT_HEADROOM_MS === DOWNLOAD_FETCH_TIMEOUT_MS === 60_000`（同一绑定） | `frontend/src/utils/time-constants.ts`（新）、`api/http.ts`、`utils/frontend-tools.ts`、`stores/workspace.ts` |
| 5 | 摘要线程池 | `ContextSummaryClientTest`（加强） | 线程形态/超时中断 | 摘要线程**非虚拟**且池有界；超时仍 fail-fast 且底层调用被中断 | `ai/context/ContextSummaryClient.java` |
| 6 | 7 键显化 | `check-docs` + 人工核对 | 配置面 | yml 注释列出 7 键 = 默认值 + `AiProperties` 出处行；**值不变**（键仍走代码缺省） | `application.yml` |
| 7 | 退役键告警 | `AiRetiredKeysWarnerTest`（新） | 命中/未命中 | 4 个退役键任一出现 ⇒ 1 条 WARN 且点名新键；全不出现 ⇒ 零 WARN | `ai/config/AiRetiredKeysWarner.java`（新） |
| 8① | NUMSPEC | 人工对账 + `check-docs` | 超时面现行值 | session-ttl 24h、退役键改述、行号按现态、死引用清理、编号唯一 | `docs/evidence/补档-NUMSPEC-清单总账.md` |
| 8② | 无超时名单 | 人工核对源码 | javadoc | `AiProperties.Hitl` 类注列全 6 个无上限工具 | `ai/config/AiProperties.java` |
| 8③ | 会话锁 TTL | 人工核对 | 自述语义 | 「= 轮上限」→「互斥窗口上界（非 total-budget）」 | `AiProperties.java`、`application.yml` |
| 8④ | 看板两行 | 人工核对 | 倒计时描述 | 仅 R-27 / R-25 两行改为现行语义（倒计时已退役 / 确认门无超时）；开工时行号 :271/:273，`0dad9ba` 在表头插入 3 行批次行后实际落在 **:274/:276**，`git diff --numstat` = `2 2`（只有这两行） | `docs/任务看板.md` |
| 8⑤ | resume 缺省 | 人工核对 | 代码缺省 vs 出厂 | 注释钉明「代码 false / 出厂 yml true」 | `AiProperties.java` |
| 9 | E2E 常量 | 脚本自检（`-WhatIf` 不执行） | 公式化 | 180s 与 5min → `(total-budget 300s + 60s)` 派生；注释同步 | `scripts/verify-e2e.ps1` |
| 10 | 探活超时 | `AiConnectionProbeTimeoutTest`（新） | 每次读取 | 同一实例两次不同 `probe-timeout` ⇒ 两次 `client.connectTimeout()` 各异 | `ai/config/AiConnectionProbe.java` |
| 11 | 键族清点 | `SessionKeyRegistryTest`（新）、`RunStoreTouchTest`（加强）、`CancellationRegistry` 新用例 | 登记/续期/TTL | 登记表 17 条（run 级 12 + session 级 5）；`touchActiveRun` 续期面 = 5(心跳)+4(契约)+1(cancel)；`renewSuspensionTtl` 仍 **exactly 5**；`ai:cancel:` TTL = `session-ttl` | `ai/run/SessionKeyRegistry.java`（新）、`RunStore.java`、`CancellationRegistry.java` |
| 12 | 24h 共亡 | 人工核对 | 文档语义 | technical-design 超时/HITL 节含「24h 滑动共亡 / 无待审批例外 / 取消随会话 / 不落库」 | `docs/technical-design.md` |

**红测留痕口径**：本批用例与实现同批落盘，首跑 6 红（真实红因见 §3，未事后补造）；另以"临时回滚实现"
做了反向红验证（4 红）证明用例有牙齿。既有断言一律不降（基线 1243 / 814 → 1279 / 819）。

## 2. 关键设计（先写清再动手）

### 2.1 第 11 项：会话级键族统一注册表

现状（分散的三处 + 一处写死 TTL）：

| 续期点 | 键 | 计数 |
|---|---|---|
| `RunStore#renewSuspensionTtl`（ConfirmGate 2s 心跳） | run / pending / events / seq / xevents | 5（`RunStoreSlidingTtlTest` 锁 exactly 5） |
| `RunStore#touchActiveRun`（读/决策） | 上式 5 + req / decided / fence:run + session:current | 9 |
| `CancellationRegistry#writeRedisFlag` | cancel（TTL 写死 `total-budget×2 = 600s`） | 1 |

设计：`SessionKeyRegistry` 作为**唯一登记表**，每条登记 = `键前缀 + 作用域(RUN/SESSION) + 续期面(HB/READ/WRITE) + TTL 来源 + 说明`。

- 续期面 `HB`（5 条）= `renewSuspensionTtl` 的取键来源（**计数不变**，零回归）；
- 续期面 `READ`（req / decided / fence / **cancel**，4 条）= `touchActiveRun` 在 HB 之上追加的取键来源
  —— 两面**刻意不重叠**（心跳键不再标 READ），否则 `touchActiveRun` 会对同 5 个键发两遍 EXPIRE；
- `WRITE` = 随写续期（beat / ledger / idem / safety / ctx 三件套 / session:current），只登记不参与两个续期面；
- **不进本表**（显式分类，不动）：全局键 `ai:cfg:ver`、限流窗口 `ai:rate:*`；短命协调键 `ai:lease:run:`(30s) / `ai:lock:*`(60s) / `ai:claim:`(随 run) / `ai:lease:epoch`(无 TTL)。
- `ai:cancel:` 的 TTL 从 `total-budget×2`(600s) 改 `session-ttl`(24h) —— 用户已裁「取消信号随会话共亡」；
  并登记进 `READ` 面（读侧 touch 时整额续期；键不存在时 `EXPIRE` 为无操作）。

### 2.2 第 2 项：默认 RetryTemplate 与 autoconfig 的优先级

`spring-ai-autoconfigure-retry` 在 classpath 上（`mvn dependency:tree` 实测），其 `retryTemplate` bean 带
`@ConditionalOnMissingBean`，默认 **maxAttempts=10 / 退避 2s×5 上限 3min / 仅 Transient+ResourceAccess+WebClientRequestException**。
即：`AiProviderRegistry:216` 的 `RetryTemplate::new` 回落**在生产态根本不生效**（被 autoconfig bean 抢占），
且 10 次 / 3min 的退避远超摘要 60s 父预算。

故本项 = ① 把默认策略抽成 `AiRetryDefaults`（0.5s ×2 上限 5s + 抖动；仅瞬时：`TransientAiException` /
`ResourceAccessException` / `WebClientRequestException` / `IOException` 族）；② 以自有 `@Bean RetryTemplate`
（`AiRetryConfig`）**显式覆盖** autoconfig（同样吃 `@ConditionalOnMissingBean`），使"配置面写的策略"与
"运行态真用的策略"同一份。③ 用例 `AiRetryDefaultsTest` 锁：3 次尝试、4xx/未知异常不重试、退避实测 450–900ms 与 950–1400ms、
   最坏退避总和 1875ms ≤ 0.2×60s、以及 `AiRetryConfig` 给出的 bean 同样只有 3 次（不是 autoconfig 的 10 次）。

### 2.3 第 5 项：摘要超时收口（如实登记的技术边界）

摘要走 `ChatModel#call`（非流式）→ Spring AI 内部是 **RestClient** 阻塞调用，**不经过 WebClient**
（`OpenAiApi#chatCompletionEntity`）。因此"改用 WebClient responseTimeout"对这条路径没有收口作用。

本项实际交付：**有界平台线程池**（`core=max=4`、队列 8、`AbortPolicy`、守护线程、空闲回收）替代
`newVirtualThreadPerTaskExecutor()`；`cancel(true)` 在平台线程上能真正打断阻塞 socket 读（虚拟线程 + 阻塞
native 读不可靠正是本项动机）；外层 `future.get(summary-timeout)` 仍是硬上限，池饱和拒绝映射为
`AI_SUMMARY_UNAVAILABLE`（可判定）。**HTTP 层请求工厂超时**需要 provider 层暴露"专用摘要运行态"
（`RestClient.Builder#requestFactory`），属结构性改动 → 登记为待裁（§5）。

## 3. 红测留痕

命令统一为：`cd backend && mvn -o test -Dtest='<类>'`（前端 `node node_modules/vitest/vitest.mjs run <文件>`）。

| 轮次 | 结果 | 红因（逐条） | 处置 |
|---|---|---|---|
| 首跑（10 类 / 55 例） | **6 红** | ① `AiRetryDefaultsTest#backoffBase…`：断言把 `baseMs(4)` 写成 5000（实为 4000，上限在 5 次）② `#backoffSleeps…`：用 `RetryListener#open` 记时间戳，但 Spring Retry 只在整次操作开头调一次 `open`（期望 3 个采样、实得 1）③ `AiRetiredKeysWarnerTest`：断言字符串多了一个空格（`app.ai.session-ttl `）④⑤⑥ `SessionKeyRegistryTest` 3 条：登记表把「心跳 5 键」同时标成 READ，`READ` 面算成 9 条（期望 4） | ①②③ 改断言；④⑤⑥ **改实现**（分面不重叠）→ 复跑 55/55 绿 |
| 编译期红 | `SessionKeyRegistry` 两个 `entries()` 重载冲突 + 方法引用 `RunStore::runKey` 用错（实例方法） | — | 改名 `buildEntries()`；键装配器改 `runId -> 前缀 + runId` |
| 转绿 | 10 类 55 例全绿 | — | `Tests run: 55, Failures: 0, Errors: 0` |

> 如实说明：本批多数项的用例与实现同批落盘（同一轮工具调用内），因此"红"多数出现在首跑而非"先红后写"；
> 上表是本批真实的首跑红清单，未事后补造。**断言只升不降**：既有用例仅在 3 处"加断言"（重试退避墙钟、
> touch 续期 cancel、摘要线程形态），未删除/放宽任何既有断言。

**反向红（回滚实现验用例有牙齿）**：把两项实现临时改回旧行为 —— ① `SseChatEmitter` 的指标改回
`SseWriteBudget.DEFAULT_TIMEOUT_MILLIS`；② `CancellationRegistry` 的 TTL 改回 `total-budget × 2` ——
同一命令复跑得 **4 红**：`expected: 24000L but was: 15000L` / `expected: 8000L but was: 15000L`
（第 3 项）、`expected: 24H but was: 10M` ×2（第 11 项）。恢复实现后同命令 **11/11 绿** ⇒ 两条用例确实
锚定"配置面 vs 运行面"而不是空转。

## 4. 门禁（全绿）

| 门禁 | 命令 | 基线 | 本次 | 结论 |
|---|---|---|---|---|
| 后端单测 | `cd backend && mvn -o test` | 1243 | **1279**（+36） | 0 failures / 0 errors / BUILD SUCCESS（surefire TEST-*.xml 求和：190 类 1279 例） |
| 前端单测 | `node node_modules/vitest/vitest.mjs run` | 814 | **819**（+5） | 56 文件全过 |
| 前端类型 | `node node_modules/vue-tsc/bin/vue-tsc.js --noEmit` | 0 | 0 | exit 0 |
| 前端构建 | `TMP/TEMP=<scratch> node node_modules/vite/bin/vite.js build` | 成功 | 成功 | `built in 47.35s` |
| 文档门禁 | `node scripts/ci/check-docs.mjs` | 6/6 | **6/6** | 全通过 |
| 脚本语法 | `PowerShell Parser::ParseFile verify-e2e.ps1` | — | PARSE_OK | 无语法错误 |

新增用例分布：`RetryBackoffTest` 6 / `AiRetryDefaultsTest` 10 / `AiConnectionProbeTimeoutTest` 3 /
`AiRetiredKeysWarnerTest` 3 / `SessionKeyRegistryTest` 8 / `SseChatEmitterWriteTimeoutMetricTest` 3 /
`ContextSummaryClientTest` +2 / `RunStoreTouchTest` +1（合计 +36）；前端 `time-constants.spec.ts` +5。
**基线条注**：上表后端/前端数字先在 `cb420ff` 基上取得；rebase 到 `0dad9ba` 后（该笔只增
`docs/research/` 四件与两份看板，零 Java/TS 改动）后端全量**复跑仍 1279/1279**（0F/0E），前端只复跑受影响的
2 个 spec（22/22）与 check-docs（6/6）。

**未起任何服务**（无 Spring Boot 启动命令、无 vite dev、无 stub）；`127.0.0.1:1` 只作为"立刻 ECONNREFUSED
的不可达地址"用于探活用例。

## 5. 待裁项（不阻断本批，供决策面）

1. **摘要 HTTP 层请求级超时**（第 5 项残余）：摘要走非流式 `ChatModel#call`（RestClient），
   `WebClient responseTimeout` **到不了这条路径**。要真正在 HTTP 层收口，需 provider 层暴露"专用摘要运行态"
   （独立 `OpenAiApi` + `RestClient.Builder#requestFactory` 带读超时），属结构性改动；本批以
   "平台线程 + cancel(true) + 父预算"收口。
2. **`ai:run` 索引键 `ai:runs` / `ai:beat` 的实际作用**：`ai:runs`（SET 索引）未在本次清点范围内判定
   "是否仍被读写"，登记为观察项（本批只登记键族，不动索引语义）。
3. **`app.ai.sse.write-timeout` 与 `app.task.sse.write-timeout` 同值**（两条通道负载特性不同）——
   NUMSPEC B1-27/28 早已登记"变化时复核"，本批未动值。
4. **`docs/s4/S4.2-AI-Runtime-合流规格.md:73` 仍写「会话锁 TTL = 轮上限」**：那是合流规格的历史表述
   （ADR-5 原文），本批只订正代码与 `application.yml` 的自述（第 8③ 项指定落点），未动规格文档 —— 是否回改
   属冻结文档处置，交提交官/决策面。
5. **`AiRetryConfig` 接管 autoconfig 后，`spring.ai.retry.*` 键全部失效**（配置面不再有该前缀的入口）：
   若将来需要运维可调，应把 `AiRetryDefaults` 的四个数（3 次 / 0.5s / ×2 / 5s）提升为 `app.ai.retry.*` 键
   —— 本批按"值不写死、小面改动"不做键化。

## 6. 文件清单（未 commit；`git status` 实测 **20 改 / 14 新增**）

**后端主代码（9 改 5 新）**
- 改：`ai/config/AiConnectionProbe.java`（:10 每次读取）、`ai/config/AiProperties.java`（:8② 6 工具名单 / :8③ 锁语义 / :8⑤ resume 双缺省）、
  `ai/config/AiProviderRegistry.java`（:216 默认策略）、`ai/context/ContextSummaryClient.java`（:5 有界池 + 拒绝收口）、
  `ai/run/CancellationRegistry.java`（:11 cancel TTL）、`ai/run/ResilientChatService.java`（:1 退避接线）、
  `ai/run/RunRegistry.java`（:3 写预算注入）、`ai/run/RunStore.java`（:11 续期走登记表）、`ai/run/SseChatEmitter.java`（:3 指标）
- 新：`ai/config/AiRetryDefaults.java`、`ai/config/AiRetryConfig.java`、`ai/config/AiRetiredKeysWarner.java`、
  `ai/run/RetryBackoff.java`、`ai/run/SessionKeyRegistry.java`

**后端测试（3 改 6 新）**：改 `conformance/ChatTurnFrameSequenceConformanceTest`、`context/ContextSummaryClientTest`、`run/RunStoreTouchTest`；
新 `config/AiRetryDefaultsTest`、`config/AiConnectionProbeTimeoutTest`、`config/AiRetiredKeysWarnerTest`、
`run/RetryBackoffTest`、`run/SessionKeyRegistryTest`、`run/SseChatEmitterWriteTimeoutMetricTest`

**前端（3 改 1 新 + 1 测试）**：改 `src/api/http.ts`、`src/utils/frontend-tools.ts`、`src/stores/workspace.ts`；
新 `src/utils/time-constants.ts`、`tests/time-constants.spec.ts`

**配置 / 脚本 / 文档（6 改 1 新）**：`backend/src/main/resources/application.yml`（:6 七键注释 + :8③ 锁注释）、
`scripts/verify-e2e.ps1`（:9 窗口派生）、`docs/technical-design.md`（:12）、
`docs/evidence/补档-NUMSPEC-清单总账.md`（:8①）、`docs/任务看板.md`（**仅** R-27 / R-25 两行 = 现 :274/:276，:8④）、
新 `docs/evidence/施工报告-超时面治理批①-DS-Flash.md`（本文件）

**未触**：`docs/决策看板.md`、`.gitignore`、帧契约（16 冻结）、任何界面文案/emoji、密钥字面。
