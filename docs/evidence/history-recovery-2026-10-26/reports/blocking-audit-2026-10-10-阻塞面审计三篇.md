# 历史补档：阻塞面审计三篇（后端 AI/SSE/轮次域 · 后端基建域 · 前端全域）

- **归档来源（已脱敏）**：3 件仓外原件，全部来自 `<LOCAL_WORK>/blocking-audit/`，源件仅仓外、未移动、未改写；分件见下表
- **归档日期**：2026-10-26（本批仅归档）
- **历史取证 · 非现行基线**：本文是 2026-10-10 时点的历史证据，结论未经当前代码复验，**不表示当前已验证 / 已修复**；正文逐字保留（仅路径、会话标识、用户名/主机名与旧目录名脱敏），不改写旧结论。
- **会话**：正文未出现会话 id，来源会话见索引 `README.md` 的来源映射表（模型与 `agentId` 自证字段按 DC-19 保留原值）
- **源行 / 源字节**：654 行 / 101,806 B

| 分件 | 原件（仓外别名） | 源行 | 源字节 | 主题 |
|---:|---|---:|---:|---|
| 1 | `<LOCAL_WORK>/blocking-audit/backend-ai.md` | 184 | 33,008 | 后端 AI/SSE/轮次域阻塞面（S1–S4 分级 + 证据标记） |
| 2 | `<LOCAL_WORK>/blocking-audit/backend-infra.md` | 157 | 32,389 | 后端基建域（除 ai/ 外的 job/task/data/definition/… 全域） |
| 3 | `<LOCAL_WORK>/blocking-audit/frontend.md` | 313 | 36,409 | 前端全域 46 个源文件阻塞面审计 |

---

**〔分件 1／3〕** 原件：`<LOCAL_WORK>/blocking-audit/backend-ai.md`

# 阻塞面审计报告 —— 后端 AI/SSE/轮次域

- 审计范围：`<MAIN_REPO>/backend/src/main/java/com/example/configmgr/ai/**`（重点 `run/` 与 `gate/`，含 `config/`、`exec/`、`session/`、`memory/` 中参与阻塞链的装配点）
- 仓库基线：HEAD = `550c7c4`，工作树干净（`git status --porcelain` 空）
- 审计方式：只读（grep + 逐点实读源码 + 读既有压测证据文档）；**未运行构建/测试**，未做任何仓库写操作
- 严重度：S1=可致主流程死锁/无限悬挂/资源耗尽；S2=分钟级阻塞或超时后状态不一致；S3=理论风险有兜底/效率问题；S4=观察项
- 证据标记：【实测】=仓库内证据文档的实测数据；【推断】=从代码结构+框架语义推演；【假设】=需进一步验证的前置假设

---

## 一、范围与方法

### 1. 检索口径
对 `ai/**` 全量执行：`await `、`Thread.sleep`、`.get(`、`.join(`、`take()`、`await(`、`synchronized`、`Lock`、`CountDownLatch`、`CompletableFuture`、`subscribe(`、`block(`、`ThreadPool`、`ScheduledExecutor`、`SseEmitter.send` 等，再逐点实读被命中的类。

### 2. 关键参数现状（单一事实源实读）

| 参数 | 值 | 位置 |
|---|---|---|
| Redis 命令/连接超时 | `2s` / `2s` | `backend/src/main/resources/application.yml:55-56` |
| 挂起等待上限 `hitl.timeout` | 120s | `application.yml:103` |
| 首包/事件间/总预算 | 30s / 90s / 300s | `application.yml:115-118` |
| 挂起专用池 | 20 线程，队列 **0**，AbortPolicy | `ai/exec/AiExecutorConfig.java:38-48` |
| 续跑触发池 | 2 线程，队列 16，AbortPolicy | `ai/exec/AiExecutorConfig.java:81-91` |
| 流式看门狗调度器 | **2** 线程，节拍 1000ms | `ai/exec/AiExecutorConfig.java:60-67`、`ai/run/StreamWatchdog.java:42` |
| 共享投递池 | 池 8 线程（core=max）、任务队列 **512**、AbortPolicy、守护线程 | `ai/run/SseChatEmitter.java:227-238`、`ai/run/RunRegistry.java:60-65` |
| 每订阅者实时队列容量 | 256（回放批豁免） | `ai/config/AiProperties.java:230` |
| 归档窗口 `EVENT_WINDOW` | 3000 | `ai/run/RunStore.java:100` |
| `SseEmitter` 超时 | `budgetMillis()` = 300s | `ai/web/AiController.java:174,295,664-666` |

### 3. 全局锁序（先给结论，供后文引用）
- 唯一跨类锁序为 `ConfirmGate.submitLock → SseChatEmitter.emitLock`（`ConfirmGate.java:411-415,465-469,500-504`）；`emit` 内部只取 `emitLock`/`seqLock`，从不回取 `submitLock`。
- **未发现锁序反转 ⇒ 未发现经典死锁环**【推断】。但存在"锁内做 Redis I/O"导致的长时间持锁（见 B-01/B-08）。

---

## 二、阻塞点清单表

| # | 位置（类#方法 / 锚点） | 机制 | 对端 / 唤醒条件 | 超时预算 | 最坏阻塞时长 | 失效安全方向 | 严重度 |
|---|---|---|---|---|---|---|---|
| B-01 | `SseChatEmitter#emit`（`run/SseChatEmitter.java:678-730`，锁内 I/O：`nextSeq`→`recordIssuedSeq` `:662`、`appendEvent` `:709`、心跳 `touchActivity` `:687`） | `synchronized(emitLock)` 内做 Redis 写 | 自身（无对端） | 单命令 2s；**业务帧 2s + 3×2s = 8s** | ~8s/帧持锁；跨线程（运行线程/HTTP 线程）排队 | Redis 异常被内部 catch → 继续出帧（降级为慢） | **S2** |
| B-02 | `SseChatEmitter#drain` → `sub.emitter.send(payload)`（`:804-826`，尤其 `:815`） | 同步 socket 写出，**无写超时** | 订阅者 TCP 读速 | **无**（Servlet 阻塞写无写超时；`server.tomcat.connection-timeout=120000` 只管请求头读取） | 线程无限悬挂（TCP keepalive 默认小时级） | 队列溢出→`evict`（414ms【实测】）；**被卡线程不释放** | **S1** |
| B-03 | `SseChatEmitter#stageReplay`（`:385-406`）在 `emitLock` 内 `store.events()` = `LRANGE 0 -1` + 最多 3000 次 `writeValueAsString`（`:397`） | 锁内 Redis 全量读 + 批量序列化 | 无 | Redis 2s + CPU | 稳态 p50 8.86ms / p95 17.64ms / 冷启动 **125.68ms**【实测】 | 无界队列入队（B-04） | **S2** |
| B-04 | `SseChatEmitter#stageReplay` → `sub.queue.add`（`:397`，队列本体无界，回放批豁免容量） | 无界内存入队 | 订阅者消费 | 无 | 单订阅者 ≤3000 帧 × ≤64KB ≈ **192MB** 上限 | 无上限、无背压 | **S2** |
| B-05 | `ConfirmGate#await`（`gate/ConfirmGate.java:189-253`） | `CountDownLatch.await(min(remain,2000ms))` 循环，上限 120s；阻塞运行线程 | `signal()`（同进程）或 Redis 轮询（跨进程） | 120s（`hitl.timeout`） | 单次 120s；并发 20 路即占满挂起池 | 超时→`expire()` 写 TIMEOUT 并回填"未执行" | **S2**（issue#6 争议核心） |
| B-06 | 超时与提交并发：`ConfirmGate#expire`/`#cancel`（`:260-312`）**不在** `submitLock` 内，而 `#submitDecision`/`#submitFrontendResult` 在锁内写同一 Redis 条目（`:411,465`） | 无 CAS 的两写者竞争 | — | 无 | 边界毫秒窗 | 后写者胜；HTTP 已回 200 accepted，状态可能终为 TIMEOUT | **S2** |
| B-07 | `ConfirmGate#maybeTriggerResume` → `resumeTriggerExecutor.execute`（`:559-569`） | 触发池 2 线程 / 队列 16 / AbortPolicy | — | 无重试 | 饱和即拒绝 | 吞 `RejectedExecutionException` → `EXECUTOR_SATURATED`，**无自愈无重试** | **S2**（已知观察项，坐实） |
| B-08 | `ConfirmGate#submitDecision`/`#submitFrontendResult`：`submitLock` 内 `registry.of().confirmDecision()`（`:413`）→ 即 `emit`（B-01） | 锁内 Redis I/O | — | — | 持 `submitLock` 至多 ~8s | 等待侧每 2s 的 `store.pending` 读被推迟 | **S3** |
| B-09 | `SseChatEmitter#evict` → `completeOne`（`:862-893`）被 `emit` 锁内调用（`:725`）；`replayAndAttach` 终态分支亦在锁内 `completeOne`（`:328`） | `emitLock` 内 `emitter.complete()`（触碰 servlet 写路径） | — | 无 | 通常毫秒级，惰性时受 socket 状态影响 | 异常被 catch，仅日志 | **S3** |
| B-10 | `ResumeService#resume`（`run/ResumeService.java:209-373`）在 **HTTP 请求线程**内联执行 `resolvePending`→`executeOnce`→`delegateSingle`（`:310,331-401`） | 内联执行已决后端工具 | 工具自身 | **无工具级超时** | 工具时长（不可知） | 无；请求线程被占用 | **S2** |
| B-11 | `SpToolCallingManager#executeOnce`（`gate/SpToolCallingManager.java:331-383`）在运行线程内联执行后端工具 | 同上 | 工具自身 | 仅看门狗 `total-budget` 300s 间接约束（`StreamWatchdog.java:130`） | 300s 后判定成立但**不中断工具本身** | 工具线程继续卡住 | **S2** |
| B-12 | `CancellationRegistry#isCancelled`（`run/CancellationRegistry.java:87-103`）在 `StreamWatchdog#tick`（`StreamWatchdog.java:125`，**每 1s 每轮**）同步 Redis GET；调度器只有 **2** 线程 | 共享 2 线程 + 每轮每秒同步 Redis | Redis | 单命令 2s | 20 轮 × 慢 Redis ⇒ tick 严重滞后，静默/预算判定整体推迟 | 仅异常路径降级退避 5s（`CancellationRegistry.java:67,210-213`）；"慢而不失败"**无退避** | **S2** |
| B-13 | `RunStore` 全部方法依赖 2s 命令超时；复合操作放大：`appendEvent` 3 命令（`:584-586`）、`saveSuspended` N×2 命令（`:354-369`）、`allRunIds` N+1 次 `hasKey`（`:325-345`）、`events` LRANGE 3000（`:713-729`） | 同步 Redis，无重试无整体预算 | Redis | 单命令 2s（**无整体预算**） | 锁内调用点见 B-01/B-03；HTTP 线程调用点成倍放大 | 读写失败口径不一（帧落档/序号仅 WARN 继续） | **S2** |
| B-14 | 上游 AI HTTP 客户端：`AiModelConfig#openAiApi`（`ai/config/AiModelConfig.java:83-90`）`OpenAiApi.builder().baseUrl().apiKey().completionsPath().build()` **未配置任何连接/读超时**；yml 无 `spring.ai.openai.*timeout*` 键 | WebClient/Reactor-Netty 默认（无响应超时） | 上游 DeepSeek | **无**（仅 `StreamWatchdog` 30s/90s/300s + `awaitSettled` 301s 兜底） | 流式路径受看门狗约束；**非流式/连接建立路径无兜底** | 流式：看门狗 fire→dispose；非流式：悬挂 | **S2** |
| B-15 | `AiController#events`（`web/AiController.java:288-300`）挂载 reattach **不做** `RedisAvailability` 前置探活（`/chat`、`/resume` 有，`:155,476`） | 开流前即触 Redis | — | 2s/命令 | `get`+`replayAndAttach` 在 Redis 故障下 ~2-4s 后抛 | `stageReplay`→`store.events` 异常穿透 `emitLock`（synchronized 自动释放），返回体走错误分支；**无悬挂** | **S3** |
| B-16 | `SseChatEmitter#complete`（`:932-963`，不同步等待——**正向设计**）；`completeWhenDrained` 依赖"跑着的 drain"（`:947-963`） | 收尾委托给被 `send` 卡住/池排队的 drain | drain 结束 | 仅 `SseEmitter` 300s 超时 | 收尾可推迟至 300s | onTimeout 兜底清理 | **S3** |
| B-17 | `SseChatEmitter#submitDrain` 拒绝分支（`:779-787`）"池拒绝=摘除" | AbortPolicy | — | — | — | 因每订阅者至多 1 个在飞 drain 任务，池队列深度 ≤ 订阅者数 ≪ 512 ⇒ **该分支实际不可达**（失效安全形同死代码） | **S4** |
| B-18 | `RunRegistry#of`（`run/RunRegistry.java:69-76`）：终态轮 reattach 新建的 emitter 从不移除（仅 `close()` 才 `remove`） | 进程内 Map 泄漏 | — | — | 小（每 runId 一个对象 + 空订阅表） | 无 | **S4** |
| B-19 | `StartupResumeRunner#onReady`（`run/StartupResumeRunner.java:75-91`）`Thread.sleep(8s)` + `#scan` 串行 `resume` ≤10 轮（`:120-159`） | 专用 daemon 线程内串行阻塞 | — | 8s + 每轮工具时长 | 分钟级（后台线程） | 异常兜底为"不自动续跑" | **S4** |
| B-20 | `SessionGate` watchdog（`session/SessionGate.java:141-155`）：单线程每 30s `EXPIRE` | 单线程 + Redis | — | 2s | 锁 TTL 10m 余量大 | 降级为进程内门 | **S4** |
| B-21 | `ConfirmGate#await` 的 deadline 用 `System.currentTimeMillis()`（`:203-204,222`） | 挂钟 | — | — | NTP 回拨可延长等待 | 无单调钟 | **S4** |
| B-22 | `RunStore#recordIssuedSeq`/`touchActivity`/`markSessionCurrent` 失败仅 WARN/DEBUG（`:624-650,228-238`） | —— | —— | —— | —— | **失效安全为"继续"（正向）** | **S4** |

**阻塞点合计：22 条**（S1 × 1、S2 × 11、S3 × 4、S4 × 6）。

> 说明：B-17/B-18/B-19/B-20/B-21/B-22 及两条正向项（`ConfirmGate` Latch 清理、`/health` 投递池指标）为 S4 观察项，未计入上表编号的另有 2 条正向项（见第四节末）。

---

## 三、S1/S2 详述

### B-01（S2）`emitLock` 内的 Redis I/O —— 锁内操作清单与最坏持锁
**证据**【实测 + 推断】：`emit` 的 `synchronized(this.emitLock)` 段（`SseChatEmitter.java:678-730`）内含：
1. 心跳分支：`heartbeatSeq.incrementAndGet()` + **`store.touchActivity` = Redis SET**（`:686-687`）+ 序列化；
2. 业务分支：**`nextSeq()`**（`:697`）→ 首次续号读 `lastEventSeq`(LRANGE) + `lastIssuedSeq`(GET)（`:653`）→ **`store.recordIssuedSeq` = Redis SET（每帧必做，`SET ai:seq`）**（`:662`）→ `FrameSizeLimiter.apply`（1 次全量序列化，超限时 `overhead()` + 最多 `MAX_ITERATIONS+1` 次重序列化，`:86-101`）→ **`store.appendEvent` = rightPush + trim + expire 三条命令**（`:709`，`RunStore.java:584-586`）；
3. 扇出：`enqueueLive` + `kickDrain`（本地，快）；溢出时 **`evict` → `emitter.complete()`（见 B-09）**。

**推演**：Redis 命令超时 2s/条 ⇒ 最坏持锁 = 2s（`SET ai:seq`）+ 6s（`appendEvent` 三条）= **~8s/业务帧**，心跳最坏 2s/帧。持锁期间所有其他出帧线程（同轮心跳线程、`POST /confirm|frontend-tool-result` 的 HTTP 线程、`/events` reattach 线程）全部排队。既有压测实测（本机 Memurai、16 核）：状态帧 emit p50 359.6µs / p99 1154.6µs，回放窗口内 emit 上界 4.30ms，定点等锁 p50 1.63ms / max 4.16ms，**冷启动 125.68ms**【实测，`docs/evidence/M2-T3-5-压测证据-DS-V4-Flash.md` §S3.2/§S4.1/§S4.2】。该文档同时明确：**跨机 Redis 下单次往返 0.5–2ms，状态帧锁内成本升至 2–8ms；Redis 抖动/故障时按 2s 超时放大到秒级**。
**失效安全方向**：Redis 异常被 `RunStore` 内部 catch（`appendEvent:588-591`、`recordIssuedSeq:627-631`），出帧不中断 = **降级为慢，不失败**（方向正确），代价是持锁期间全轮出帧停摆。
**修法方向（不改代码）**：(a) `recordIssuedSeq` 改为本地批量/异步登记或移出 `emitLock`（序号单调与到达序已由本地 `AtomicLong` + 锁保证，`ai:seq` 只需最终一致）；(b) `appendEvent` 用 pipeline/事务把 3 条命令压成 1 次往返；(c) 给"锁内 Redis 总耗时"加预算（超过 N ms 即跳过外置保投递），把"外置失败降级"从隐式变显式；(d) 把 `emitLock` 拆为"取号+入队"（必须串行）与"外置"（可异步单线程消费者，天然保序）。

### B-02（S1）`drain` 的 `SseEmitter.send` 同步阻塞且无写超时
**证据**：网络写出是 `SseEmitter` 的同步写（`SseChatEmitter.java:815`），全仓无任何 servlet 写超时配置（`application.yml:1-6` 仅 `server.tomcat.connection-timeout: 120000`，语义是"等待请求行到达"，**不是写超时**）；既有证据文档 S8【实测】明确"真实 TCP：客户端 `pause()` → 内核收发缓冲填满 → 服务端 write 阻塞"，慢客户端在 **414ms** 内因**队列溢出**被摘除，但被卡住的 drain 线程本身要等客户端恢复读或 TCP 层报错才释放【实测，同上 §S8/§S5.2】。
**推演**：投递池 = 进程级共享、8 线程（`RunRegistry.java:60-65`）。证据文档 §S1.2【实测】给出规律：**健康订阅者不被饿死 ⟺ 慢连接数 M < 池容量**；`M == 池容量` 时全部工作线程被占满，**同轮乃至全进程**全部健康订阅者（含心跳）停投。生产池 8 ⇒ **8 条"不读也不断开"的常驻连接即可让全进程 SSE 停投**；由于无写超时，这 8 个线程可被挂住数小时（TCP keepalive 默认量级），且**无任何自愈机制**（无池扩容、无写超时中断、无健康探针驱逐）。单人桌面工具场景概率低，但这是一条真实的"无限悬挂 + 资源耗尽"路径。
**失效安全方向**：队列溢出 → `evict` + `emitter.complete()`（前端带 `lastSeq` 重挂补帧，方向正确）；但**卡住的线程不在失效安全覆盖范围内**——`closeSubscription` 只清队列/标志，不打断已阻塞的 write。附带损失：`delta` 帧跳过归档（`RunStore.java:574-581`），断连窗口内的正文**重挂补不回来**【实测，§S8 遗留观察：实测断档 seq 1227→1712】。
**修法方向**：(a) 写出路径引入**写超时/可中断写**（Tomcat async 写超时，或下游 `setWriteTimeout`；非 Servlet 形态天然具备）；(b) 池容量改为"每轮/每订阅者隔离 + 全局上限"（虚拟线程或 semaphore 配额），避免跨轮饥饿；(c) 慢连接数作为健康指标接入 `/health`，超阈值时拒绝新订阅者（当前 `evicted` 只计数不设防）；(d) 保留 delta 归档或改用 delta 快照压缩，以消除重挂补帧缺口。

### B-03 + B-04（S2）`stageReplay` 锁内全量回放 + 回放批无界入队
**证据**：`replayAndAttach`/`replayTo` 在 `emitLock` 内调 `stageReplay`（`SseChatEmitter.java:322-342,363-375`）；`stageReplay` 做 `store.events()`（`LRANGE 0 -1` + 逐条 `readValue`）与逐帧 `writeValueAsString` 后 `sub.queue.add`（`:385-406`）。类注释自认"回放批豁免实时容量，上界 = 归档窗口 3000"（`:87-89`）。
**推演**：(a) 锁内成本与归档窗口线性正相关，实测稳态 p50 8.86ms / p95 17.64ms、**冷启动 125.68ms**，期间同轮所有出帧被挡（§S4.1/S4.2）【实测】；跨机 Redis 或大帧（单帧上限 64KB）时按倍数放大【推断】。(b) 内存上限 = 3000 × 64KB ≈ **192MB/订阅者**，多订阅者与多轮叠加且无任何背压或总量上限（`LinkedBlockingQueue` 无界，`SseChatEmitter.java:983`）。
**失效安全方向**：入队不失败；代价转移为内存与锁占用。**修法方向**：(a) 回放改分片/限量（`LRANGE` 分批 + 每批让出锁），或回放走独立队列与实时队列分离（代价是混合 FIFO 语义需重设计）；(b) 回放批加总量上限与背压（超限从最旧丢弃 + 明确告知回放不完整）；(c) 要求前端必传 `lastSeq` 走差量（机制已存在）。

### B-05（S2）`ConfirmGate#await` —— 120s 阻塞挂起的线程预算与失效安全（issue#6 争议核心）
**证据**：`await` 用进程内 `CountDownLatch` 只做唤醒（`ConfirmGate.java:629-641`），等待循环 `latch.await(min(remain, 2000ms))`（`:226`），`deadline = startedAt + timeoutSeconds*1000`（`:203-204`），`remain<=0` → `expire()`（`:222-225`）。它挂在**官方循环执行工具的那条线程**上：按 ADR-2 补记/`AiExecutorConfig` 注释，一次挂起实占**挂起专用池 1 条 + Spring AI 硬编码 `boundedElastic` 1 条**（`AiExecutorConfig.java:22-23`）。
**推演**：120s 是**单次挂起**上限；每轮可有多次挂起（多工具 → 多次 `await`），且 300s 总预算之外没有对"多次挂起累计"的额外闸。20 路并发挂起即占满挂起池（队列 0、AbortPolicy）⇒ 后续 `/chat` 全量 503 `SUSPEND_POOL_SATURATED`（`AiController.java:192-197`）——这是**设计内**准入语义（ADR-2 N1），不是缺陷；但对 issue#6 的口径很重要：**"await 阻塞 120s" 阻塞的是 (a) 官方循环执行线程、(b) boundedElastic 线程、(c) 挂起期心跳的驱动线程（心跳由 `await` 循环自己每 2s 发出，`:234`）**，三者是同一条线程，因此"挂起期连接是否活着"完全取决于该线程能否按时醒来。
**失效安全方向（完备性核查）**：`finally` 清 `latches.remove(key, latch)`（**值匹配删除，正确**）+ `cancelHandle.close()`（`:244-252`）⇒ **超时后无 Latch/监听者泄漏**【推断，实读】。超时落地写 `status=TIMEOUT` + `executed=false` + 结局帧（`:286-312`），跨进程可读。
**修法方向**：(a) 明确"多挂起累计预算"（每轮挂起总时长纳入 `total-budget`）；(b) 挂起期心跳改为独立调度（否则线程被 Redis/GC 拖慢时心跳与倒计时一起漂移）；(c) 为 issue#6 补一条端口口径说明：120s 是**外部输入等待上限**（`hitl.timeout`），前端 `expiresAt` 用同帧 `atMs` 同钟折算（`SseChatEmitter.java:903-907`），二者不应混为"服务端处理耗时 120s"。

### B-06（S2）超时/取消写入与决策提交的竞态 —— "超时后状态不一致"
**证据**：`submitDecision`/`submitFrontendResult`/`dismiss` 三处都在 `synchronized(this.submitLock)` 内 `store.putPending`（`ConfirmGate.java:411,465,500`）；而等待侧的 `expire`（`:286-312`）与 `cancel`（`:260-283`）**完全没有取 `submitLock`**，直接 `store.putPending`。两者都是"读—改—写"（先 `store.pending` 再 `putPending`），**无 CAS、无 `resolvedAtMs` 比较**。
**推演**：在 `remain<=0` 与外部提交之间的毫秒窗内并发发生 ⇒ 后写者胜。可复现顺序：① `submitDecision` 读到 `PENDING` → 写 `APPROVED` → HTTP 返回 `accepted:true, status:APPROVED, wokeInProcessGate:false`；② 同时 `expire` 写入 `TIMEOUT`；③ 最终 Redis 为 `TIMEOUT` ⇒ **用户的批准被静默丢弃**（工具不执行），而 HTTP 已宣告本轮已决；随后 `maybeTriggerResume` 因 `woke=false` 走死卡兜底，`resume` 看到 `TIMEOUT`（非 PENDING）正常续跑 —— 系统自洽但**用户意图丢失且无痕迹**。反向顺序（expire 先，提交后）则 TIMEOUT 被 APPROVED 覆盖（可接受）。
**失效安全方向**：无——两写者互为"最后一个写赢"，没有可判定的仲裁。**修法方向**：把 `expire`/`cancel` 也纳入 `submitLock`（或在同一锁内做"仅在仍 PENDING 时写"的比较写），并把"决策与超时竞争"的败者信息返回给调用方（而不是只回 `accepted:true`）。

### B-07（S2）续跑触发池饱和 → `EXECUTOR_SATURATED` 无自愈（坐实已知观察项）
**证据**：`maybeTriggerResume` 四个前置判据后 `resumeTriggerExecutor.execute`，捕获 `RejectedExecutionException` → 仅 `log.warn` + 返回 `ResumeTrigger.skipped("EXECUTOR_SATURATED")`（`ConfirmGate.java:559-569`）；池 = 2 线程 / 队列 16 / AbortPolicy（`exec/AiExecutorConfig.java:81-91`、`AiProperties.java:165-171`）。
**推演**：该分支正是"同进程已无等待者 + 快照仍 SUSPENDED"的**死卡兜底**唯一出口。饱和（极短窗内 >18 个死卡事件，或 2 个触发任务被 Redis 拖慢）时兜底静默失效，调用方（HTTP 响应体）只把 `resumeOutcome=EXECUTOR_SATURATED` 带回前端——**没有重试、没有降级到同步执行、没有延迟重投**。该轮停留 `SUSPENDED` 直到下一次外部输入或重启扫描（`StartupResumeRunner` 只在启动时跑一次）。
**失效安全方向**：外部输入本身已落库成功（不报错，方向正确），但"挂起轮永不收敛"无兜底。**修法方向**：(a) 饱和时退化为**调用线程内联执行**（触发任务很短：一次 Redis 读 + 重建 + 转投）；(b) 或改定时延迟重投（带次数上限）；(c) 把 `EXECUTOR_SATURATED` 计入 `/health` 与告警面。

### B-10 + B-11（S2）工具执行无超时预算
**证据**：`executeOnce` 直接 `delegateSingle(prompt, pending)`（`SpToolCallingManager.java:358`），无超时/无中断；`ResumeService#resume` 在 **HTTP 请求线程**内联跑同一路径（`ResumeService.java:310`）。看门狗唯一相关约束是 `tick()` 的 `total-budget` 分支（`StreamWatchdog.java:130`），而 `fire()` 只 `dispose()` 上游订阅（`:165-173`），**不中断正在执行的工具线程**；工具执行期间 `beacon.isActive` 只抑制"静默判定"（`:136-138`），不影响预算分支。
**推演**：单个慢工具可让运行线程/HTTP 请求线程阻塞到工具返回，最坏无界；`POST /api/ai/runs/{runId}/resume` 因此在工具时长内不返回（`resolvePending` 循环内联执行全部已决工具），前端可能超时重试 → 由 `resume` 互斥锁（`ResumeService.java:409-426`：进程内 `putIfAbsent` + Redis `setIfAbsent`）兜住重复驱动，但仍会放大 `ai:resume:lock:` 占用。
**失效安全方向**：`total-budget` 让**轮次**在 300s 收敛为失败终态；**工具线程本身不收敛**。**修法方向**：(a) 为工具执行引入超时（`CompletableFuture.orTimeout` + 中断/取消，并明确"超时后副作用未知"的记账）；(b) `ResumeService#resume` 的结算阶段移出 HTTP 线程（当前只有"驱动模型"被转投，`resolvePending` 仍在请求线程——与 T3-6a "HTTP 立即返回"的目标不一致）。

### B-12（S2）看门狗调度器 2 线程 + 每轮每秒同步 Redis 轮询
**证据**：`Executors.newScheduledThreadPool(2, ...)`（`AiExecutorConfig.java:60-67`）；每次尝试 `scheduleAtFixedRate(this::tick, 1000ms, 1000ms)`（`StreamWatchdog.java:85`）；`tick()` 第一件事即 `cancellations.isCancelled(runId)`（`:125`），而 `isCancelled` 对**已登记且本地标志为 false** 的轮次仍会读一次 Redis（`CancellationRegistry.java:87-103`）——即每个活跃轮每秒一次同步 GET。
**推演**：调度器线程数固定 2，与并发轮数无关。20 路并发挂起 ⇒ 每秒 20 次同步 GET 分摊到 2 条线程；Redis 往返若升到 1–2s（跨机/抖动，且"慢而不失败"**不触发**5s 降级退避，退避只覆盖异常路径），tick 将严重滞后甚至停摆 ⇒ 首包/事件间/总预算判定整体推迟，`awaitSettled` 的 301s 墙钟兜底成为唯一约束【推断】。该调度器**跨轮共享**，一次 Redis 故障同时压制所有轮的看门狗。
**修法方向**：(a) 调度器容量随并发，或改为单线程 + 合并"取消轮询"任务；(b) 取消标志改为 Redis pub/sub 或把轮询绑定事件边界（事件到达时才查），去掉"空闲也每秒查"；(c) 把"慢而不失败"的 Redis 也纳入自适应降频。

### B-13（S2）Redis 超时预算只到"单命令级"
**证据**：`spring.data.redis.timeout=2s` / `connect-timeout=2s`（`application.yml:55-56`），全仓无整体/单操作预算；`RedisAvailability` 前置探活只覆盖 `/chat` 与 `/resume`（`AiController.java:155,476`），且**正缓存 TTL = 0**（每次请求都真 PING，`RedisAvailability.java:52-58`）。
**推演**：复合操作最坏 = 命令数 × 2s（`appendEvent` 3 条 = 6s；`saveSuspended` N 个挂起项 × 2 条 = 2N×2s；`allRunIds` (N+1)×2s；`events` LRANGE 3000 单条但 payload 巨大）。在 `emitLock` 内即 B-01；在 HTTP 线程内即单请求秒级~十几秒等待（历史遗留的 15.1s 形态正是这类串行放大，`RedisAvailability` javadoc 记录）。
**修法方向**：(a) 为锁内/请求内路径设总预算并对批操作改用 pipeline；(b) 探活扩展到 `/events`（B-15）；(c) 明确"Redis 半死（慢而不错）"的降级策略（当前只对异常降级）。

### B-14（S2）上游 AI HTTP 客户端无连接/读超时
**证据**：`OpenAiApi.builder().baseUrl(...).apiKey(...).completionsPath(...).build()`（`AiModelConfig.java:83-90`）——**未传 WebClient/RestClient，未设任何超时**；对 `spring-ai-openai-1.1.8.jar` 的 `OpenAiApi` / `OpenAiApi$Builder` 类文件做字节级检索，**无任何 `timeout` 标识串** ⇒ 框架侧也未设超时【实测：jar 字节检索】；`application.yml` 无相关键（`:79-91`）。
**推演**：连接建立/非流式等待落在 Reactor Netty 默认行为（连接超时默认 30s、**无响应超时**）。流式路径下（主路径）由 `StreamWatchdog` 30s/90s/300s 与 `awaitSettled` 的 `total-budget+1s` 提供墙钟兜底（`ResilientChatService.java:373-390`），因此**流式**不会无限悬挂【推断】；但：① 连接建立本身无显式超时（首个事件前的静默由 30s 首包阈值覆盖）；② 若将来出现 `chatModel.call(...)` 等非流式调用点，则**无任何兜底**；③ `describe()` 把 `"Timeout on blocking read"` 归因为"AI 调用超过总预算"（`ResilientChatService.java:753-757`）——该文案更像 Lettuce/Redis 超时串，属排障误归因风险。
**修法方向**：(a) 显式配置连接/读超时（走 `webClientBuilder`/`RestClient.builder` 注入带超时的 client），使阈值成为**可查配置项**而非框架默认；(b) 补非流式路径兜底；(c) 校正 `describe()` 的超时归因文案。

---

## 四、S3 / S4 简表

| # | 位置 | 一句话 | 严重度 |
|---|---|---|---|
| B-08 | `ConfirmGate` `submitLock` 内 emit | 锁内 Redis I/O 使等待侧每 2s 的 `store.pending` 读被推迟最多 ~8s（决策发现延迟，无死锁） | S3 |
| B-09 | `SseChatEmitter#evict` / `replayAndAttach` 锁内 `completeOne` | `emitLock` 内触碰 servlet 收尾写路径（通常毫秒级） | S3 |
| B-15 | `AiController#events` | reattach 无 Redis 前置探活；`stageReplay` 的 Redis 异常穿透出 `emitLock`，错误响应形态不友好 | S3 |
| B-16 | `SseChatEmitter#complete` | 收尾不同步等待（正向），但依赖被卡住的 drain，可推迟到 `SseEmitter` 300s 超时 | S3 |
| B-17 | `SseChatEmitter#submitDrain` 拒绝分支 | 因"每订阅者至多 1 个在飞 drain"，池队列 512 实际不可达 ⇒ 池拒绝摘除近乎死代码 | S4 |
| B-18 | `RunRegistry#of` | 终态轮 reattach 新建的 emitter 从不移除（进程内轻微泄漏） | S4 |
| B-19 | `StartupResumeRunner` | daemon 线程 `sleep(8s)` + 串行 resume ≤10 轮（含内联工具执行） | S4 |
| B-20 | `SessionGate` watchdog | 单线程每 30s `EXPIRE`，TTL 10m 余量大 | S4 |
| B-21 | `ConfirmGate#await` deadline | 用 `System.currentTimeMillis()`，NTP 回拨可延长等待 | S4 |
| B-22 | `RunStore` 写入失败口径 | `recordIssuedSeq`/`touchActivity`/`markSessionCurrent` 失败仅 WARN/DEBUG 继续（**正向失效安全**） | S4 |
| — | `ConfirmGate` Latch 清理 | `finally` 值匹配 remove + 监听者 close，**超时后无泄漏**（正向） | S4 |
| — | 投递池指标 | `/health` 有 `poolSize/active/queueDepth/evicted`（正向可观测；但 `evicted` 未按原因拆分、无"慢连接数"） | S4 |

### 范围内遇见的"已知观察项"现状坐实（不重复深挖）
- `EXECUTOR_SATURATED` 无自愈：坐实（B-07）。触发池 2/16，AbortPolicy，拒绝仅 WARN 且不回给业务任何重试路径。
- 归档窗口/`EVICTED_TOTAL` 拆分：`EVICTED_TOTAL` 是单一计数（`SseChatEmitter.java:105,866`），溢出/写出失败/池拒绝三类共用，`/health` 只能看到总数（B-17 亦因此不可观测）——现状坐实。
- 回放分片：不存在分片，`stageReplay` 单次全量（B-03），现状坐实。
- `ai:beat` 静默失效：`touchActivity` 失败仅 DEBUG（`RunStore.java:643-650`），僵尸判据会因此少一份活动证据，现状坐实（未深挖）。
- A9 双驱动竞态：`maybeTriggerResume` 的 `hasActiveLatch` 前缀判据 + `claimExecution` 原子认领仍是兜底（`ConfirmGate.java:540-607`），现状坐实（未深挖）。
- WARN 丢根因：本次审计未发现 `emitLock` 路径的 WARN 丢根因；`appendEvent` 失败为 `log.debug`（非 WARN），是本范围可登记的一处"失败不响亮"（`RunStore.java:588-591`，归 S4）。

---

## 五、与 issue#6（"await 阻塞回灌 120s"）的关联点

本范围内与 issue#6 直接相关的确凿事实（供指挥官口径裁定，不含价值判断）：

1. **120s 是配置的"外部输入等待上限"，不是处理耗时**：`app.ai.hitl.timeout=120s`（`application.yml:103`）同时是确认门与前端工具回灌的等待上限（`ConfirmGate.java:166-187`）。
2. **等待是"进程内 Latch 阻塞 + Redis 轮询"双通道**：同进程 `signal()` 立即唤醒；跨进程靠每 2s 一拍读 Redis 发现（`ConfirmGate.java:208-237`）⇒ 同进程回灌的**实际唤醒延迟 ≈ Latch 语义（毫秒级）**，120s 只在上限意义上存在；跨进程最坏 +2s 发现延迟【推断】。
3. **被阻塞的是"官方循环执行工具的那条线程"（+ `boundedElastic` 一条）**：挂起期心跳由该线程每 2s 发出（`ConfirmGate.java:234`），故挂起期 SSE 保活与该线程强绑定（ADR-2 补记）。
4. **并发上限与快速失败**：20 路挂起占满池（队列 0）→ 新轮 503 `SUSPEND_POOL_SATURATED`；这是 ADR-2 N1 的**设计内准入**，不是本审计认定的缺陷。
5. **超时收敛是完备的**：`expire()` 写 TIMEOUT + `executed=false` + 结局帧，`finally` 清 Latch 与监听者 ⇒ **无泄漏**；**但**存在 B-06 的"边界批准被静默丢弃"竞态（超时写不经 `submitLock`，与提交写互为后写者胜）——这是本范围内唯一确凿的"超时后状态不一致"点。
6. **投放侧风险与 120s 无关但同域**：真正可能"分钟级~无限悬挂"的是 B-02（`send` 无写超时 + 8 线程全进程共享）与 B-12（2 线程看门狗 + 每秒同步 Redis），建议在 issue#6 的结论里与之分离陈述。

---

## 附：S1/S2 一句话索引

- **B-01（S2）** `SseChatEmitter#emit` 在 `emitLock` 内做 Redis 写：`SET ai:seq` + `appendEvent`(rightPush/trim/expire)，最坏持锁 ~8s/业务帧。
- **B-02（S1）** `SseChatEmitter#drain` 的 `SseEmitter.send` 同步写出无写超时 ⇒ 8 条不读的常驻连接可让全进程 SSE 停投且线程无自愈。
- **B-03（S2）** `stageReplay` 在 `emitLock` 内 `LRANGE` 全量 + 最多 3000 次序列化（冷启动实测 125.68ms）。
- **B-04（S2）** 回放批豁免容量 ⇒ 单订阅者队列上限 ≈192MB 且无背压。
- **B-05（S2）** `ConfirmGate#await` 用 Latch 阻塞运行线程（含 boundedElastic）至多 120s，20 路即占满挂起池。
- **B-06（S2）** `expire`/`cancel` 不经 `submitLock`，与决策提交互为后写者胜 ⇒ 边界批准可被静默丢弃。
- **B-07（S2）** 续跑触发池饱和回 `EXECUTOR_SATURATED`，无重试无自愈（已知观察项坐实）。
- **B-10（S2）** `ResumeService#resume` 在 HTTP 线程内联执行已决工具，无工具级超时。
- **B-11（S2）** `executeOnce` 工具执行无超时预算，看门狗只收敛轮次不中断工具线程。
- **B-12（S2）** 看门狗调度器仅 2 线程而每轮每秒同步 Redis GET，Redis 慢时全部轮超时判定滞后。
- **B-13（S2）** Redis 超时只到单命令级（2s），复合操作按命令数放大且在 `emitLock`/HTTP 线程内。
- **B-14（S2）** 上游 AI HTTP 客户端未配连接/读超时（jar 字节级检索无 timeout），仅流式路径有看门狗兜底。

**〔分件 2／3〕** 原件：`<LOCAL_WORK>/blocking-audit/backend-infra.md`

# 阻塞面审计 · 后端基建域（backend-infra）

范围：`<MAIN_REPO>/backend/src/main/java/com/example/configmgr/` 下 **除 `ai/` 外全部**（`job/ task/ data/ definition/ file/ excel/ masterdata/ seed/ common/ config/` + 主类）。
时机：HEAD = `550c7c4`，`git status --porcelain` 输出为空（工作树干净）。全程零 git 写操作、零文件修改（本报告除外）、未跑构建/测试。

---

## ① 范围与方法

### 1.1 范围边界
- 覆盖 49 个非 `ai/` 的 `.java` 文件（仓内 `configmgr` 下共 66 个，`ai/` 子树 17 个不在本报告）。
- 配置面一并纳入：`backend/src/main/resources/application.yml`、`pom.xml`、`db/migration/*.sql`（池/超时/约束的唯一真源）。
- 不在本报告：`ai/` 全域（SseChatEmitter / ConfirmGate / RunStore / ResilientChatService / AiExecutorConfig 等）、前端执行器 120s 回灌、`downloadOne` 下载旁支（另案）。

### 1.2 方法
系统性 grep（对 `src/main/java/com/example/configmgr` 全量，再按前缀剔除 `ai/`）：
`Thread.sleep` `.join()` `.get()` `CountDownLatch` `BlockingQueue` `.take()` `.await()` `synchronized` `ReentrantLock` `ReadWriteLock` `Semaphore` `CompletableFuture` `Future<` `Callable<` `Executors.` `ThreadPool` `@Scheduled` `while(true)` `for(;;)` `RestTemplate|RestClient|WebClient|OkHttp|HttpClient|HttpURLConnection|Socket` `redis` `setSoTimeout|setConnectTimeout|setReadTimeout` `@Transactional|Propagation|LockModeType|@Version` `@PreDestroy|addShutdownHook|destroyMethod|DisposableBean|ApplicationRunner|ApplicationReadyEvent` `Files.|readAllBytes|MultipartFile`。
再对每个命中逐点实读代码坐实（含调用方与事务边界）。

### 1.3 关键结论（先说三条）
1. **本域零 HTTP 客户端**——`RestTemplate / RestClient / WebClient / OkHttp / HttpURLConnection / java.net.Socket` 在非 `ai/` 范围内 **0 命中**（唯一 WebClient 引用在 `ai/run/ResilientChatService.java`）。故"无超时的 HTTP 调用点 = S2"这一条**在本域不成立**；本域唯一的外部 RPC 是 **Redis**（`JobCancellationRegistry`），其超时在 `application.yml:55-56` 有 2s 预算（见 B12）。
2. **本域零无界等待原语**：无 `while(true)`、无 `for(;;)`、无重试/退避循环、无 `CountDownLatch`/`BlockingQueue`/`Future.get()`/`join()`/`Semaphore`。全仓非 `ai/` 只有 **4 处 `Thread.sleep`**（全部在作业执行器，全部是 `demo-batch-delay-ms`），**2 处 `synchronized`**（同一文件 `TaskSseService`），**0 处 `ReentrantLock`**。这是正面结论：本域的阻塞风险不来自"等待原语写错"，而来自 **等待时的资源占用**（事务/连接/锁）。
3. **本域真正的阻塞面 = 作业执行链上的"长事务 × 连接池 × SSE 锁内写"三者叠加**，且失效路径（获取不到连接时）没有任何终态兜底 → 作业永停 `PENDING`/`RUNNING`，配合同类互斥守卫可把任务钉死。细节见 ③。

### 1.4 推演口径与限制
- 本次审计**未运行任何构建或测试**（按指令），故所有"最坏阻塞时长"均为**代码推演**【推断】；凡涉及框架默认值的，标注【假设：框架默认】。
- 涉及"连接是否在 `Thread.sleep` 期间仍被持有""`REQUIRES_NEW` 悬挂期间外层连接是否归还"等结论，依据 Spring `AbstractPlatformTransactionManager.doSuspend`（连接随挂起事务一起保留在 `SuspendedResourcesHolder`）与 Hibernate 默认 `DELAYED_ACQUISITION_AND_RELEASE_AFTER_TRANSACTION` 连接处理模式 —— 属**强推断**，建议后续用「连接池 active 数 × 作业在跑数」做一次实测复核。

---

## ② 阻塞点清单表

> 位置列以 `类#方法` 为主锚，`file:line` 为辅。严重度：S1 可致主流程死锁/无限悬挂/资源耗尽；S2 可致分钟级阻塞或超时后状态不一致；S3 理论风险有兜底/仅效率；S4 观察项。
> 依据列：【推断】= 代码推演；【假设】= 框架默认值；【实测】= 本仓既有可引记录。

| # | 位置（类#方法 / file:line） | 阻塞机制 | 对端 / 唤醒条件 | 超时预算 | 最坏阻塞推演 | 失效安全方向 | 严重度 | 依据 |
|---|---|---|---|---|---|---|---|---|
| B1 | `TaskSseService#publish` `task/service/TaskSseService.java:54-64`（`synchronized(list)` 内 `emitter.send` 于 :58） | 持任务级 list 监视器锁做**阻塞式 servlet 网络写** | SSE 客户端 TCP 读；客户端消费/关闭/断链 | **无**（`SseEmitter(0L)` :26；无写超时、无背压） | 单条慢/停读客户端 → 该次 SSE 写停住 → **持锁** → 该任务所有 publish 排队；调用者若为作业线程，则**作业线程 + 其外层事务 + 一条 DB 连接**一并停住，理论上无上界（Windows 下 TCP 重传可拖数分钟或更久） | 无。既不超时也不异步，无法自愈 | **S1** | 【推断】 |
| B2 | `TaskSseService#subscribe` `TaskSseService.java:26,27,76-79` | `SseEmitter(0L)` 无超时 + `emitters` map 条目**只删元素不删 key** | 客户端关闭 / onError / onTimeout | 无（0L = 永不超时） | 每个新 taskId 永久留一个（可能为空的）list；无 SSE 超时则悬挂 emitter 不被回收 | 弱：`onCompletion/onTimeout/onError` 会移除元素，但 map 条目与悬挂 emitter 无兜底 | S3 | 【推断】 |
| B3 | `ImportJobRunner#run` `job/service/ImportJobRunner.java:145`（`@Transactional` :60） | `Thread.sleep(300)` **在作业长事务内**，每个分片边界一次 | 仅时钟；关停时 `InterruptedException` | 单次 300ms；**累计无上限** | N 行 → ⌈N/100⌉ × 300ms：10 万行 = **300s**（含 1000 次分片边界）；期间一条 DB 连接被本事务独占 | 中断会被项级 `catch(Exception)` 转成"该项 FAILED"（不静默），但**不释放已占用连接** | **S1** | 【推断】 |
| B4 | `ExportJobRunner#run` `job/service/ExportJobRunner.java:137`（`@Transactional` :73） | 同 B3（按**命中行**每 100 行） | 同上 | 单次 300ms；累计无上限 | 命中 N 行 → ⌈N/100⌉ × 300ms，同 B3 持连接 | 同上 | **S1** | 【推断】 |
| B5 | `PrecheckJobRunner#run` `job/service/PrecheckJobRunner.java:188`（`@Transactional` :61） | 同 B3；判据 `i % batchSize == 0` **含 i=0** ⇒ 每个配置项第 1 行必 sleep | 同上 | 单次 300ms；累计无上限 | 同 B3；额外每配置项多一次固定 300ms | 同上 | **S1** | 【推断】 |
| B6 | `PublishJobRunner#doPublish` `job/service/PublishJobRunner.java:200`（事务由 `transactionTemplate.execute` :86 持有） | 同 B3，在**数据面单事务**内 | 同上 | 单次 300ms；累计无上限 | 暂存行 N → ⌈N/100⌉ × 300ms；整批 upsert+差集删除的事务全程持连接 | 中断 → `PublishAbort` → **整批回滚**（方向正确，不产半批） | **S1** | 【推断】 |
| B7 | `AsyncConfig#getAsyncExecutor` `config/AsyncConfig.java:49,50` | 关停期 `waitForTasksToCompleteOnShutdown(true)` + `awaitTerminationSeconds(30)` | 作业线程自然结束 | 30s（**仅等待上限，非硬界**） | 超 30s 后 `shutdownNow()` 只发中断：作业线程在 `Thread.sleep` 之外的循环**不响应中断**，且线程非 daemon ⇒ JVM 等非守护线程退出 ⇒ 关停可悬挂至**剩余作业跑完**（分钟级） | 弱：中断打断 sleep 会被项级 catch 吞成 FAILED 后继续下一项，作业不会因此停下 | S2 | 【推断】 |
| B8 | `AsyncConfig#getAsyncExecutor` :48 `AbortPolicy` + `JobService#createAndStart` :93-98 `afterCommit` | 提交被拒时异常从 `afterCommit` 回调冒泡 | 池 16 + 队列 200 有余量 | 无（拒绝立即抛出） | 队列满（>216 作业）→ 抛 `TaskRejectedException` → 接口 500，但**作业行已提交为 PENDING** ⇒ 孤儿 PENDING 作业 + 同类互斥守卫永久 409 钉死该任务 | 无：不回滚作业行，无补偿收尾（注释宣称"如实冒泡不静默丢"，但后果仍是悬挂行） | S2 | 【推断】 |
| B9 | `JobProgressService`（`JobProgressService.java:55` `REQUIRES_NEW`）被分片边界调用（:60,75,82,96,107,116,129） | 每个分片边界开**独立新事务** → 外层事务悬挂但**不归还连接** | 连接池有空闲连接 | Hikari `connectionTimeout` = 30s【假设：框架默认】 | 每个运行中作业峰值占 **2 条连接**（外层 + REQUIRES_NEW）；10 万行导入 = 1000 次边界，即 1000 次"再要一条连接" | 无：取不到连接即 30s 后抛 `SQLTransientConnectionException`，作业中途失败 | S2（放大器；与 B10 叠加成 S1） | 【推断】 |
| B10 | 全仓 Hikari **零配置**（`application.yml:19-20` 仅 `url/driver/username/password`）；作业池 16 线程（`AsyncConfig.java:44-46`） | 连接池容量 < 作业并发 × 每作业连接数 | 任一事务提交/回滚 | `connectionTimeout` **30s**【假设：默认 30000ms；`maximumPoolSize`=10；无 `leak-detection-threshold`】 | 6 个并发作业即可占满 10 条连接（6×2=12>10）→ 第 7 个作业的分片边界写等 30s 后失败；10 个并发作业 ⇒ **任何** REQUIRES_NEW 都必然 30s 超时；同时 Tomcat 侧默认 200 请求线程对 10 连接争用 → 普通 REST 也 30s 后 500 | 无。无池满快速失败、无泄漏检测、无排队上限 | **S1** | 【推断】+【假设】 |
| B11 | `JobProgressService#markRunning` 在**四个执行器 `try` 之外**调用（`ImportJobRunner.java:78`、`ExportJobRunner.java:97`、`PrecheckJobRunner.java:90`、`PublishJobRunner.java:82`） | 取连接/开事务失败时异常**绕过**任何终态写入 | 连接池空闲 | 30s（同上） | B10 触发时：作业线程抛 `SQLTransientConnectionException` 直接出 `run()` → 被 `AsyncJobExecutor#execute` 的 `catch` 仅记日志 ⇒ 作业行**永停 PENDING**（无 RUNNING、无终态）；且同类互斥守卫把 PENDING 计入 in-flight ⇒ 该任务该类型后续作业**永久 409**，只能靠重启（`StaleJobRecoveryRunner`）或取消（取消也需连接）解套 | 无。本域最直接的"有限输入 → 永久悬挂"路径 | **S1** | 【推断】 |
| B12 | `JobCancellationRegistry#isCancelled` → `redisFlag` `job/service/JobCancellationRegistry.java:82,140-148` | 分片边界同步 Redis GET，**无进程内缓存、无熔断、无背压** | Redis 返回 / 连接异常 | `spring.data.redis.timeout`=2s、`connect-timeout`=2s（`application.yml:55-56`） | 每个分片边界一次（B3 的 1000 次边界 = 1000 次 RTT）；Redis 黑洞化时每次 ~2s（+建连 2s）⇒ 单作业可额外增加**数十分钟**，且全程持连接 | **方向正确**：异常被 catch → 降级为进程内标志（跨实例失效），不抛错 | S2 | 【推断】 |
| B13 | `JobCancellationRegistry#onCancel` `:105-114` | 定义在册但**全仓（非 `ai/`）零调用点** ⇒ "当场唤起监听者"的加速通道是死代码 | 无注册者，永不触发 | N/A | 取消生效只能等下一个分片边界（≤1 批次）；本身不阻塞 | 有兜底（轮询），无风险 | S4 | 【实测：`grep -rn "onCancel"` 仅命中定义与类注释】 |
| B14 | `JobService#cancel` `job/service/JobService.java:249-271`（`@Transactional` :249，`:268` 调 `publish`） | 取消请求线程在**持 DB 事务**期间进入 B1 的锁内网络写 | 同 B1 | 无 | 一个停读的 SSE 客户端可把取消接口连同其事务/连接一起挂住；取消是本域唯一"人工解套"手段，被拖住即解套失败 | 无 | S2 | 【推断】 |
| B15 | `JobTerminalWriter#apply` `job/service/JobTerminalWriter.java:79-91` | 终态写走 `REQUIRES_NEW`（:55），失败仅 `log.error` 后吞掉 | 连接池 / 事务提交 | 30s（连接获取） | 连接耗尽或任务侧写失败时 ⇒ 数据面可能已提交，而作业行**永留 RUNNING**、任务态不联动、`JOB_DONE` 不推；列表永久显示"运行中"，且 RUNNING ∈ in-flight 又是一条互斥钉死路径 | 无重试、无补偿（注释自称"快照下一次读取仍能修正"，但无任何代码路径实现该修正） | S2 | 【推断】 |
| B16 | `PrecheckJobRunner#writeFingerprintSummary` `PrecheckJobRunner.java:303-314` | 在**数据面长事务内** `findById` + `save(job)`，**无状态守卫**；`Job` 无 `@Version`【实测：`grep @Version` 仅 ConfigDataRow/Task/TaskFile】 | 事务提交时 flush | 无 | 与 `JobService#cancel`（并发把 status 改 CANCELLED）竞态：本类读改写整行会在提交时把 status 覆盖回旧值 ⇒ 取消态被抹回 RUNNING（A9 双驱动竞态的岗位点之一，此处仅坐实现状） | 无守卫（对照：`JobProgressService#markRunning` 有 `status != PENDING` 让位守卫，本处缺） | S2 | 【推断】 |
| B17 | H2 连接串 `application.yml:20` 无 `LOCK_TIMEOUT` | 行/表锁等待用 H2 默认值 | 持锁事务提交/回滚 | **1000ms**【假设：H2 默认 `DEFAULT_LOCK_TIMEOUT`】 | B3–B6 的分钟级长事务持有 `config_staging_rows`/`task_items`/`job_items` 行写锁；任何并发同范围写在 1s 后抛 JdbcSQLTimeoutException，被项级 catch 吞成"该项 FAILED"，根因（长事务）在报障面不可见 | 有兜底（快速失败而非无限等），但归因错位 | S2 | 【推断】+【假设】 |
| B18 | `server.tomcat.connection-timeout: 120000` `application.yml:6` | 易被误当"响应超时"的配置 | N/A | 120s（**仅建连/请求行空闲**） | 对 SSE 与本域阻塞点**无约束力**：不限制响应写出、不限制 servlet 线程内的锁等待/事务等待 | N/A | S3 | 【推断】 |
| B19 | `ConfigDataService#upsertPublished` `ConfigDataService.java:180` `OPTIMISTIC_FORCE_INCREMENT` | 版本强制递增在 B6 的长事务内逐行执行 | 同事务提交 | 无 | 与并发直接写 API 冲突时抛 `OptimisticLockException` → 在 `PublishJobRunner` 内包成 `PublishAbort` ⇒ **整批回滚**（口径一致，但长事务把冲突窗口放大到分钟级） | 方向正确（整批回滚，不产半批） | S3 | 【推断】 |
| B20 | `TaskService#delete` `TaskService.java:259-293`；`DefinitionService#delete` `DefinitionService.java:110` | 单事务多表级联删；`afterCommit` 内删盘 | 事务提交 | 无 | 大任务（暂存行多）时单事务逐表 DELETE，持连接期间 HTTP 线程同步等待；删盘在提交后（方向正确） | 有：删盘在 afterCommit，回滚不丢文件 | S3 | 【推断】 |
| B21 | `DataSeedRunner#run` `seed/DataSeedRunner.java:32-44`（`@Transactional` :33） | **启动期**单事务全量播种（PROJ_PRICE 4×300 行 + 其余；逐行 `findRow` SELECT + INSERT） | 事务提交 | 无 | 首次启动（空库）启动窗口被拉长；无超时、无上限；失败即启动失败 | 无（失败即上下文启动失败，属可接受方向） | S3 | 【推断】 |
| B22 | `StaleJobRecoveryRunner#run` `job/service/StaleJobRecoveryRunner.java:44-73`（`@Transactional` :45，`@Order(0)` :36） | 启动期单事务扫**全表 jobs** 并逐条回写 + 逐 job_item 回写 | 事务提交 | 无 | 历史作业多时启动窗口线性拉长（`findAll()` 全表 + 逐行 save） | 有：幂等（处理完写终态，二次重启集合为空） | S4 | 【推断】 |
| B23 | `GlmSeedRunner#seedGlmDefinitions` `seed/GlmSeedRunner.java:24-31`（`ApplicationReadyEvent`） | 同步事件监听 ⇒ 阻塞 Spring 主线程至播种完成 | 事务提交 | 无 | 阻塞"启动完成"信号（Tomcat 已可服务，故不阻塞请求，仅拖慢就绪上报）；异常被吞为 `log.error` | 有：`try/catch` 不阻断启动 | S4 | 【推断】 |
| B24 | `FileStorageService#read` `file/FileStorageService.java:46-48`（`Files.readAllBytes`）、`FileController#upload` :53,135（`getBytes`/`readAllBytes`）、`ExcelReader#read`（`XSSFWorkbook` DOM 全解析） | 全量文件进堆 + 同步文件 I/O（HTTP 线程） | 磁盘 / GC | 无显式；受 `spring.servlet.multipart` **默认 1MB / 10MB** 隐式约束 | 单请求峰值内存 ≈ 多个整文件副本；若将来放开该默认（现未配置），百 MB 级件 → OOM（属"资源耗尽"而非"悬挂"） | 有（当前默认 1MB 上限兜住）；无显式 `max-file-size` 声明，属隐式保护 | S4 | 【推断】 |
| B25 | `TaskController#overview` `task/controller/TaskController.java:66-84`、`JobController#getDiff` `job/controller/JobController.java:64-70`、`DataController` 条件查询 → `ConfigDataService#filterRows` `:95-109` | 请求线程同步全量读 + 内存过滤，**无分页** | 客户端读取 | 无 | 大任务/大配置项时单请求 CPU+堆+连接占用拉长，拖慢同池请求 | 无（纯效率问题，最终会返回） | S4 | 【推断】 |

**合计 25 个阻塞/争用点**（S1 × 7、S2 × 8、S3 × 4、S4 × 6）。

---

## ③ S1 / S2 详述

### S1-1｜B1 `TaskSseService#publish` —— 锁内无超时网络写
**证据**（`task/service/TaskSseService.java`）：
```
26:  SseEmitter emitter = new SseEmitter(0L);              // no timeout
54:  synchronized (list) {
55:      for (SseEmitter emitter : list) {
58:          emitter.send(SseEmitter.event().data(json));  // ← 阻塞式 servlet 写出，持锁
```
调用者包含作业线程（`ImportJobRunner#publishProgress`、`ExportJobRunner`、`PrecheckJobRunner`、`PublishJobRunner` 四处在分片边界调 `taskSseService.publish`）、取消通路（`JobService#cancel:268`）与任务态联动（`TaskService#publishTaskChanged`）。
**推演**：`SseEmitter.send` 最终落到 servlet 容器的阻塞式输出流写。当客户端 TCP 接收窗口饱和（慢客户端 / 停读页面 / 被挂起的 DevTools），写会阻塞且**无写超时**（`server.tomcat.connection-timeout: 120000` 只作用于建连与请求行空闲，见 B18）。此时：①该任务 list 的监视器被持有 ⇒ 同任务所有 SSE 推送排队（含 `JOB_DONE`）；②调用者为作业线程 ⇒ 作业分片循环停在此处 ⇒ **外层事务不提交、DB 连接不归还**（与 B3–B6、B9、B10 叠加）；③调用者为 `JobService#cancel` ⇒ 取消接口（唯一人工解套手段）也停在此处，且它自己持一个事务。无上界，无看门狗、无超时、无异步隔离。**本域唯一的"无限悬挂"型阻塞点。**
**修法方向（仅建议）**：① 推送与锁解耦——锁内只快照 emitter 列表，锁外逐个 `trySend`；② 单 emitter 加发送超时/熔断与背压丢弃策略（进度帧可丢、终态帧不可丢），或改异步派发 + 有界队列 + 饱和丢进度帧；③ 给 `SseEmitter` 设非 0 超时并加**周期**心跳（当前仅订阅时发一次 HEARTBEAT，仓内无周期心跳）。

### S1-2｜B3 / B4 / B5 / B6 —— 作业长事务跨越 demo 节拍
**证据**：`@Transactional` 包裹整个 `run()`（`ImportJobRunner.java:60`、`ExportJobRunner.java:73`、`PrecheckJobRunner.java:61`），`PublishJobRunner` 由 `transactionTemplate.execute`（:86）包裹 `doPublish`；四者在分片边界内 `Thread.sleep(appProperties.getJob().getDemoBatchDelayMs())`，`demo-batch-delay-ms: 300` / `batch-size: 100`（`application.yml:148-149`）。
**推演**：`Thread.sleep` 语义 = **纯节拍**（无唤醒条件、无超时，只为让进度条可见），但它位于事务内部 ⇒ 睡眠期间 DB 连接被独占。累计量 = ⌈行数/100⌉ × 300ms：10 万行 ⇒ 1000 边界 ⇒ **300s** 纯睡眠（不含 1000 次 `REQUIRES_NEW` 取连接、1000 次 Redis GET、1000 次 SSE 推送）；100 万行 ⇒ **3000s（50 分钟）** 单连接占用。叠加 B9/B10 后并发作业数一上来即池耗尽。B5 额外在 `i = 0` 触发（每配置项固定多 300ms）。
**修法方向**：① demo 节拍与事务解耦（sleep 移出事务，或"仅在前端 SSE 连接存在时才休眠"）；② 数据面改逐分片短事务（当前只把"进度"拆到 REQUIRES_NEW，数据面仍整批长事务）；③ 给 `demo-batch-delay-ms` 加总预算上限（如 `demo-max-total-delay`）或按规模自适应；④ 明确其为 demo 装饰并在生产 profile 置 0。

### S1-3｜B10 + B9 —— Hikari 零配置 vs 作业线程池 16
**证据**：`application.yml:19-20` 只有 `url/driver-class-name/username/password`，全仓**无 `spring.datasource.hikari.*`**（grep `hikari|maximum-pool|leak-detection` 仅命中注释）。作业池 `AsyncConfig.java:44-46`：core 4 / max 16 / queue 200。`JobProgressService` 每次分片边界 `REQUIRES_NEW`（:55）。`spring.threads.virtual.enabled: false`（:20）⇒ Tomcat 平台线程（默认 max 200）也争抢同一 10 连接池。
**推演**（【假设：Spring Boot 3.5 Hikari 默认 `maximumPoolSize=10`、`connectionTimeout=30000ms`、`leakDetectionThreshold=0`】）：每运行作业峰值 **2 条**连接（外层长事务 + 瞬态 REQUIRES_NEW；挂起不归还连接）⇒ **6 个并发作业**即打满 10 条；第 7 个作业的分片边界写等 30s 后抛 `SQLTransientConnectionException`；**10 个并发作业**时任何作业的进度/条目写都必然 30s 超时 → 作业中途失败；同一时刻普通 REST（列表/详情/取消）在同一池排队 → 30s 后 500，含"取消"这一解套手段（见 B14）；无 `leak-detection-threshold` ⇒ 分钟级占用连接不会留任何泄漏告警。
**修法方向**：① 显式配 `spring.datasource.hikari`，并与作业池**联合定容**（`maximum-pool-size` ≥ 作业池 max × 2，或把作业池 max 降到 `pool/2`）；② `leak-detection-threshold` 设为略大于最长正常作业时长以留证；③ 若 H2 文件库是硬约束，干脆把作业池 max 降到 4（与 core 一致）并显式承认串行化；④ 分片边界写改为复用外层事务或合并批量进度写，消除 +1 连接。

### S1-4｜B11 —— 取连接失败 ⇒ 作业永停 PENDING + 互斥守卫钉死任务
**证据**：四个执行器的 `progressService.markRunning(...)` 均在 `try {` **之前**：`ImportJobRunner.java:78`（try 在 :80）、`ExportJobRunner.java:97`（:99）、`PrecheckJobRunner.java:90`（:92）、`PublishJobRunner.java:82`（:85 才 `try`）。`AsyncJobExecutor#execute` 的 `catch (Exception e)` 只 `log.error`（`AsyncJobExecutor.java:37-38`），不做终态补偿。`JobService#assertNoJobInFlight` 把 `PENDING` 计入 in-flight（`JobService.java:37-38,113-122`）。
**推演**：B10 触发时 `markRunning` 抛异常 → 直接穿出 `run()` → `AsyncJobExecutor` 仅记日志 ⇒ 作业行保持 `PENDING`、`started_at` 为空、无终态、无 `JOB_DONE`、任务态不联动。由于 `PENDING ∈ IN_FLIGHT`，同任务同类型的新作业一律 409 `JOB_ALREADY_RUNNING` ⇒ **该任务该类型流水线永久不可用**，唯一出路是取消（取消端点自身也需连接，池未缓解时同样失败）或重启（由 `StaleJobRecoveryRunner` 兜住）。"有限并发 → 永久悬挂 + 状态机钉死"，符合 S1 口径。
**修法方向**：① 把 `markRunning` 纳入 `try`，失败路径写 FAILED 终态；或引入不依赖业务连接池的独立收尾线程；② `AsyncJobExecutor#execute` 的兜底 catch 增加"写终态 + 任务态联动"补偿（当前只有 `unregister`）；③ 互斥守卫把"PENDING 且超过 N 分钟无 `started_at`"视为可自愈（现有 `StaleJobRecoveryRunner` 只在启动时跑）。

### S2-1｜B7 —— 关停期 `awaitTerminationSeconds(30)` 不是硬界
**证据**：`AsyncConfig.java:49-50`；作业线程由 `ThreadPoolTaskExecutor` 默认线程工厂创建（**非 daemon**），作业循环仅在 `Thread.sleep` 处响应中断。**推演**：30s 后 `shutdownNow()` 只投递中断；中断被项级 `catch(Exception)` 消费后循环继续（`ImportJobRunner:169`、`ExportJobRunner:180`、`PrecheckJobRunner:243`），`PublishJobRunner` 则整批回滚后继续外层循环；JVM 因非守护线程未退出而停留在关停中 ⇒ 关停时长 ≈ 剩余作业时长。**修法方向**：作业线程标记 daemon 或注册可中断的取消标志；关停序列改为"先写取消标志（Redis 已具备）再等待"，并把 30s 作为真实上限（超时后强制中断 + 放弃未提交事务）。

### S2-2｜B8 —— `afterCommit` 内提交被拒 ⇒ 孤儿 PENDING 作业
**证据**：`JobService.java:93-98`（`afterCommit` → `asyncJobExecutor.execute`）、`AsyncConfig.java:48`（`AbortPolicy`）。**推演**：容量 16+200 打满后拒绝时，作业行已在提交范围内无从回滚 ⇒ 与 B11 同型悬挂（PENDING + 互斥钉死）。**修法方向**：`CallerRunsPolicy`（把执行拉回请求线程，行为可见），或拒绝时在 `afterCommit` 内补写 FAILED 终态。

### S2-3｜B12 —— 分片边界一次同步 Redis GET
**证据**：`JobCancellationRegistry.java:82`（`isCancelled` → `redisFlag`）、`:140-148`（`opsForValue().get`），超时见 `application.yml:55-56`（2s / 2s）。**推演**：进程内标志为假时**每次都问 Redis**（无 TTL 内缓存、无本地负缓存、无熔断），边界数 ∝ 行数；Redis 黑洞化时每次约 2s（连接被拒时快失败，可忽略）⇒ 单作业额外数十分钟，且全程持连接 / 过 B1 锁。失效方向正确（catch → 降级为进程内标志，不抛错）。**修法方向**：本地负缓存（每作业每 1s 最多问一次 Redis）+ 连续失败熔断（N 次后只读本地标志并记降级），把 Redis 从分片热路径移开。

### S2-4｜B14 —— 取消通路自身会被 B1 拖住
**证据**：`JobService.java:249`（`@Transactional`）→ `:268` `taskSseService.publish`（进入 B1 的锁内写）。**推演**：取消是本域唯一的人工解套，但它①需一条 DB 连接（池耗尽时同样 30s 失败）、②需过 SSE 锁。**修法方向**：取消路径的 SSE 推送异步化（`afterCommit` + 有界队列），取消事务只做"写标志 + 改状态"。

### S2-5｜B15 —— 终态写失败无补偿
**证据**：`JobTerminalWriter.java:82-90`（`requiresNew.executeWithoutResult` 内写作业终态 + 任务态；异常仅 `log.error` 吞掉）。**推演**：连接耗尽/校验失败时 ⇒ 数据面已提交或已回滚，但作业行永留 `RUNNING`、任务态不联动、无 `JOB_DONE`；列表永久"运行中"，且 `RUNNING ∈ IN_FLIGHT` ⇒ 又一条互斥钉死路径。**修法方向**：终态写加有限次重试与退避；引入"未落终态"的独立巡检（复用 `StaleJobRecoveryRunner` 判定，但按 `started_at` 超时周期性跑）。

### S2-6｜B16 —— `writeFingerprintSummary` 在长事务内无守卫写 `jobs` 行（A9 岗位点）
**证据**：`PrecheckJobRunner.java:303-314`（长事务内 `findById` + `save`）；`Job` 实体**无 `@Version`**（对照 `Task`/`TaskFile`/`ConfigDataRow` 有）。**推演**：与并发 `JobService#cancel`（置 status=CANCELLED 并提交）竞态时，本类在提交时以脏检查整行 UPDATE 覆盖 status ⇒ 取消事实被抹回。`JobProgressService#markRunning` 用 `status != PENDING` 让位守卫防的正是同类问题，此处缺守卫。**修法方向**：改为定向 UPDATE（只写 `result_json`，与 `JobRepository#updateProgress` 同款），或给 `Job` 加 `@Version`。仅坐实现状，不深挖。

### S2-7｜B17 —— H2 默认 1s 锁超时 vs 分钟级长事务
**证据**：连接串无 `LOCK_TIMEOUT`（`application.yml:20`）。**推演**：B3–B6 持有 `config_staging_rows`/`job_items`/`task_items` 行写锁达分钟级；任何并发同范围写在 1s 后抛 H2 锁超时，被作业项级 catch 吞成"该项 FAILED"，根因（长事务）在报障面上不可见。**修法方向**：显式 `LOCK_TIMEOUT`（与最长分片事务对齐）+ 缩短数据面事务本身；锁超时异常单独归类为可重试/告警，不与业务校验失败同码。

---

## ④ S3 / S4 简表

| # | 位置 | 一句话 | 严重度 |
|---|---|---|---|
| B2 | `TaskSseService#subscribe:26,27,76-79` | `SseEmitter(0L)` 无超时；`emitters` map 条目只删元素不删 key，逐 taskId 永久留存 | S3 |
| B13 | `JobCancellationRegistry#onCancel:105-114` | "加速通道"全仓零调用者（死代码），取消只能等分片边界 | S4 |
| B18 | `application.yml:6` | `tomcat.connection-timeout: 120000` 易被误读为响应超时；对 SSE 与本域锁/事务等待无约束力 | S3 |
| B19 | `ConfigDataService.java:180` | `OPTIMISTIC_FORCE_INCREMENT` 在长事务内逐行执行，冲突窗口被放大到分钟级（失效方向正确：整批回滚） | S3 |
| B20 | `TaskService.java:259-293` / `DefinitionService.java:110` | 单事务多表级联删 + afterCommit 删盘（删盘时机正确） | S3 |
| B21 | `DataSeedRunner.java:32-44` | 启动期单事务全量播种（逐行 SELECT+INSERT），无超时；空库首启窗口被拉长 | S3 |
| B22 | `StaleJobRecoveryRunner.java:44-73` | 启动期单事务全表扫描 + 逐行回写；幂等，有兜底 | S4 |
| B23 | `GlmSeedRunner.java:24-31` | `ApplicationReadyEvent` 同步监听阻塞 Spring 主线程（Tomcat 已可服务，仅拖慢就绪）；异常已吞 | S4 |
| B24 | `FileStorageService.java:46-48` / `FileController.java:53,135` | 全量文件进堆 + 同步盘 I/O；受 multipart 默认 1MB/10MB 隐式约束（未显式声明） | S4 |
| B25 | `TaskController.java:66-84` / `JobController.java:64-70` / `ConfigDataService.java:95-109` | 请求线程同步全量读 + 内存过滤，无分页 | S4 |

**范围内确认"无阻塞"的点（正面结论，供交叉核对）**：`DependencyResolver`（拓扑排序，纯内存）、`ConditionEvaluator`（逐行纯内存求值）、`JacksonConfig`/`WebConfig`/`ApiResponse`/`GlobalExceptionHandler`（无等待原语）、`ExcelTemplateBuilder`、`common/*`、`masterdata/*`（仅 JPA 读）、全部 `repo` 接口（除 `@Modifying` 定向 UPDATE 外无锁语义）、`ConfigDataService#batchSave`（逐行同步写，属效率问题，归 B25 同类）。本域**不含**任何 `CountDownLatch` / `BlockingQueue` / `ReentrantLock` / `Future.get()` / `join()` / `Semaphore` / `@Scheduled` / 重试循环。

---

## ⑤ 与 issue#6（"await 阻塞回灌 120s"）争议的关联点

**一句话**：本域**不存在** 120s 量级的"回灌/挂起等待"，也没有任何 `await`-类可配置等待预算（`app.ai.hitl.timeout: 120s` 属 `ai/` 域，不在本报告）；但 **B1（`TaskSseService#publish` 锁内无超时网络写）是同源缺陷的另一半**——issue#6 争的是"等待侧的 120s 预算够不够"，而本域证据表明**写侧（SSE 推送）连超时预算都没有**，且它被作业线程与取消通路直接调用，能把 issue#6 语境下的"分钟级等待"直接放大成无上界悬挂并连带占住 DB 连接。若后续要统一"SSE 通路超时口径"，本域需与 `ai/run/SseChatEmitter` 一起定：**推送必须可超时、可丢弃（进度帧）且不得持锁**。

其余交叉点仅两条（放大/解套关系，非同一缺陷）：
- **B14**（取消通路在事务内 publish）说明 issue#6 的"回灌超时后往哪倒"在本域对应的是 **"超时后状态不一致"**：取消可能既没写进作业终态、又把接口挂住。
- **B11 / B15** 说明本域"超时后的失效安全方向"整体偏弱：**超时往往不落到终态，而落到永久的 PENDING/RUNNING**——与 issue#6 争的"超时后应否立即失败还是悬挂"是同一类判据，本域现状偏向**悬挂**。

---

### 附：方法与证据可复现命令（只读）
```
git -C <MAIN_REPO> rev-parse HEAD && git -C <MAIN_REPO> status --porcelain
grep -rn -E "Thread\.sleep|synchronized|CountDownLatch|BlockingQueue|\.take\(\)|\.await\(|ReentrantLock|CompletableFuture|\.join\(\)|Semaphore" <MAIN_REPO>/backend/src/main/java/com/example/configmgr --include=*.java   # 再剔除 ai/
grep -rn -E "RestTemplate|RestClient|WebClient|OkHttp|HttpURLConnection|Socket" <MAIN_REPO>/backend/src/main/java/com/example/configmgr --include=*.java   # 非 ai/ 零命中（exit=1）
grep -rn "onCancel" <MAIN_REPO>/backend/src/main/java/com/example/configmgr --include=*.java  # 仅定义+注释，零调用
grep -rn "@Version" <MAIN_REPO>/backend/src/main/java/com/example/configmgr --include=*.java   # ConfigDataRow/Task/TaskFile；Job 无
grep -rn -iE "hikari|maximum-pool|leak-detection" <MAIN_REPO>/backend                                          # Hikari 池零配置
```

**〔分件 3／3〕** 原件：`<LOCAL_WORK>/blocking-audit/frontend.md`

# 阻塞面审计报告 — 前端全域（frontend/src）

审计仓库：`<MAIN_REPO>`（`本仓`）
基线：HEAD = `550c7c4`，`git status --porcelain` 为空（工作树干净，审计期间零写操作）
范围：`frontend/src/` 全部 46 个源文件（约 12.9k 行）
审计者：阻塞面审计子智能体（DeepSeek V4 Flash）

---

## ① 范围与方法

**审计范围**（仅 `frontend/src/`，不含 `frontend/dist`、`node_modules`、`scripts`）：

| 层 | 文件 |
|---|---|
| SSE / HTTP 通道 | `api/sse.ts`、`api/http.ts`、`api/ai.ts`、`api/jobs.ts`、`api/tasks.ts` |
| stores | `stores/ai.ts`(1503)、`stores/workspace.ts`(452)、`stores/task.ts`(400) |
| 执行器 / 工具层 | `utils/frontend-tools.ts`(703)、`utils/job-watch.ts`、`utils/excel.ts`(865)、`utils/spreadjs.ts` |
| 组件 / 视图 | `components/AiPanel/{AiPanel,FormRenderer}.vue`、`components/SpreadGrid.vue`、`components/JobProgress.vue`、`views/*.vue`(5)、`components/wizard/*` |

**方法**（全部结论均有实读代码位置坐实，非推测）：

1. `git status --porcelain` 与 `git rev-parse HEAD` 确认基线（零 git 写操作，报告外零文件修改）。
2. 系统性 grep：`await `、`alert(|confirm(|prompt(`、`XMLHttpRequest`、`document.write`、`setTimeout`、`setInterval`、`requestAnimationFrame`、`new Promise`、`.then(`、`JSON.(parse|stringify)`、`for (;;)`、`while (true)`、`Thread.sleep|synchronized|Lock|CountDownLatch|CompletableFuture`、`.get(|.join(|take()`。
3. 逐点实读代码：对每个 `await` 点读其**对端**（Promise 由谁 resolve）、**有无超时预算**、**失效安全方向**；对每个 `new Promise` 读其所有 settle 分支是否穷尽。
4. 未运行任何构建/测试；未修改被审仓库任何文件。

**判定口径**：`S1`=可致主流程死锁/无限悬挂/资源耗尽；`S2`=可致分钟级阻塞或超时后状态不一致；`S3`=理论风险有兜底/仅效率问题；`S4`=观察项。
**证据等级**：`【实测】`=grep/读码直接坐实；`【推断】`=读码坐实但触发条件依赖外部环境；`【假设】`=需进一步验证。

**遍历结论（先给排除项）**：
- 原生 `alert/confirm/prompt`：**0 处**（命中项均为 `ElMessageBox.confirm` / store 的 `confirm()` 业务方法，非原生 API）【实测】
- 同步 `XMLHttpRequest`：**0 处**（唯一 HTTP 通道是 axios/fetch/EventSource）【实测】
- `document.write`：**0 处**【实测】
- 本仓**无** Java 侧阻塞原语（`Thread.sleep`/`synchronized`/`Lock`/`CountDownLatch`/`CompletableFuture`/`take()`）出现在前端【实测】
- 前端**没有任何 120s 常量**：`grep 120` 仅命中 `min-width="120"` 等样式。所谓「`frontend_tool_request` 120s 回灌超时」在**前端不存在定义点**，它是后端（`ConfirmGate`）的挂起超时；前端只把它当作帧字段消费（`timeoutSeconds` / `expiresAt` / `atMs` → `foldDeadline`）。这一点对 issue#6 争议是关键事实，见 §5。

**前端自有的超时预算清单（全仓仅此 5 个）**：

| 预算 | 值 | 定义点 | 覆盖对象 |
|---|---|---|---|
| axios 实例 timeout | 60 000 ms | `api/http.ts:85` | 所有 `get/post/put/del/getBlob/upload` |
| SSE 帧间静默上限 | 90 000 ms | `api/sse.ts:22` | 已建流的帧间隔（`readStream` / `reattach`） |
| 断流重挂次数 | 2 次（退避 1s→2s） | `api/sse.ts:25,28` | reattach |
| 页面/任务就绪等待 | 3 000 ms | `utils/frontend-tools.ts:226` | `waitForPageReady` / `waitForTaskBound` |
| 作业终态等待 | 300 000 ms | `api/jobs.ts:52` | `waitForJobFinal` |

**结论：除上述 5 个外，`await` 链一律无超时预算**。这正是本次审计的核心风险面。

---

## ② 阻塞点清单表

> **总数 36 条**（其中 B-19/B-20/B-21 三条为「实测不存在」的排除项；实际风险条目 33 条）。
> 位置主锚用 `文件#方法`，辅以 `文件:行`（相对 `<MAIN_REPO>`）。
> `downloadOne` / `downloadBlob` 单链标注「**专查中**」，本报告只做清单级覆盖。

| ID | 位置（主锚 / file:line） | 阻塞机制 | 对端 / 唤醒条件 | 超时预算 | 最坏阻塞时长 | 失效安全方向 | 级别 |
|---|---|---|---|---|---|---|---|
| **B-01** | `sse.ts#streamChat` / `api/sse.ts:326` | `await fetch('/api/ai/chat')` 等响应头 | 后端 SseChatEmitter 首次 flush 响应头 | **无（FE 侧）**；dev 由 vite 代理 `timeout:300000` 兜底（`vite.config.ts:33`） | 无界；dev≈300s | 非 2xx/网络异常 → 抛 `ApiError` → `handleChatError` → notice；无悬挂泄漏 | **S2** |
| **B-02** | `sse.ts#readStream` / `api/sse.ts:251` | `await reader.read()` 等下一块 | 后端下一帧/心跳字节 | 90s 静默看门狗（`sse.ts:237-247`）→ abort | 90s | abort → `tryReattach`（有界）→ `finish(terminal=false)` → ADR-6 提示 | S3 |
| **B-03** | `sse.ts#reattach` / `api/sse.ts:478-484` | EventSource `open()` 后 **`onopen` 之前无看门狗**（idleTimer 只在 `onopen` 里 `startIdle()`） | EventSource 连接建立 → `onopen` | **无** | **无界** | 无（promise 永不 settle）→ `send/reattachActive/resumeSnapshotStream` 的 `loading` 永不复位、输入区永久禁用 | **S1** |
| B-04 | `sse.ts#reattach` / `api/sse.ts:471` | 重连退避 `setTimeout` 未纳入 `settle()` 清理 | 定时器自然到期 | 1s/2s | 2s | 回调内有 `if (!settled)` 守卫，不会重开连接 | S4 |
| **B-05** | `ai.ts#send` / `stores/ai.ts:694-698` | 整轮 `await aiApi.chat(...)` 的**复合**最坏值 | 见 B-01/B-02/B-03 之和 | 无总预算 | ≈ 300 + 90 + (90+1+90+2+90) ≈ **663s** | finally 复位 `loading`（仅当代际仍是本轮）；期间输入区全程 disabled | **S2** |
| **B-06** | `ai.ts#runFrontendTool` / `stores/ai.ts:1481-1501` | 前端工具执行+回灌链，**无任何本地看门狗** | `executeFrontendTool` 返回 → `submitFrontendToolResult` resolve | 无 | 无界（取决于下游 B-07/B-08/B-09/B-10/B-11） | 无；仅靠后端 `ConfirmGate` 超时收尾 + 后端 `frontend_tool_result` 帧回传收敛 | **S2** |
| **B-07** | `SpreadGrid.vue` / `components/SpreadGrid.vue:87,203,118,94` | `await readyPromise`，该 Promise **只有 resolve、无 reject、无 timeout**；两条路径永不 resolve：①`loadSpreadCore()` 拒绝（4.7MB spreadjs chunk 404/CSP/离线）→ `catch` 置 `engineState='failed'`，`readyResolve` 从未调用；②`if (!host.value) return` 早退 | `onMounted` 成功路径的 `readyResolve?.()` | **无** | **无界** | 无。链式放大：`loadFromJson/loadFromXlsx` → `ensureGridLoaded` → `loadExistingResults`/`refreshResults` → `loadTask` → `applyTaskBinding`/`ensureTaskBoundForTool` → `export.openEditor`/`export.selectDefs` → `executeFrontendTool` → **回灌永不发出** | **S1** |
| **B-08** | `excel.ts#blobToWorkbookJson` / `utils/excel.ts:639-649` | `await new Promise`，仅 ExcelIO 的 success/error 回调 settle | `excelIo.open(blob, success, error)` 之一被调用 | **无** | 无界（回调闭塞/worker 卡死） | 无 reject 兜底；失败时抛中文错误（有 error 回调时） | **S2** |
| **B-09** | `ExportWizardView.vue#ensureTaskBoundForTool` / `views/ExportWizardView.vue:827` | `await pending.promise`（`inFlightBinding`，`:423,442`） | `router.replace` + `loadTask`（含 `loadExistingResults` → `ensureGridLoaded`）全部完成 | **无** | 无界（继承 B-07/B-08/B-10） | 无；工具执行器挂住 → 回灌不发 | **S2** |
| **B-10** | `workspace.ts#runUiEvent` / `stores/workspace.ts:351,357,413,417,421` | `await router.push({name})`，对端含**懒加载路由 chunk**（`router/index.ts:25,31,37,43,49` 的 `() => import(...)`） | 导航确认（含目标 chunk 求值 + 组件 mount） | **无** | 无界（chunk 请求停滞）；chunk 404/网络错误会 reject | reject 被外层 try/catch 吞 → `fail(...)` 返回可读文案（**这条有兜底**）；停滞则无兜底 | **S2** |
| **B-11** | `workspace.ts#runUiEvent` / `stores/workspace.ts:425-429` | `await fetch(event.url)`（`download` ui_event），**无 signal、无 timeout** | 下载 URL 响应 | **无** | 无界 | 无；挂住整条 ui_event 分发 → `executeFrontendTool` → 回灌不发 | **S2** |
| B-12 | `ExportWizardView.vue#persistStepData` / `views/ExportWizardView.vue:504-513` | 逐配置项**串行** await（`selectDefs` + N×`setCondition` + `getTask`） | 各 axios 响应 | 单请求 60s | (N+2)×60s | `ElMessage.error` + `finally persisting=false` | S3 |
| B-13 | `jobs.ts#waitForJobFinal` / `api/jobs.ts:50-68` | `for(;;)` 1s 轮询 | 快照 `isJobFinal` | 300s（默认） | 300s | 超时后**返回非终态 job 而不抛错**；`taskStore.watchPublishOutcome` 据此把 `publishConflict` 置 null → 超时后状态不一致 | S3 |
| B-14 | `job-watch.ts#watchJob` / `utils/job-watch.ts:86-121` | 3s `setInterval` 兜底轮询 + 每作业一个 `EventSource`，**无最大时长**；`ImportWizardView.vue:748-795` 每次 `beginWatchJob` 只 push 不回收同 kind 的旧 watcher | 快照终态 / 组件卸载 | 无（仅终态或 unmount 收敛） | 无界（作业长期 RUNNING 时） | 状态自愈（轮询推进），但 EventSource/定时器随重跑累积 | S3 |
| B-15 | `frontend-tools.ts#waitForPageReady / waitForTaskBound` / `utils/frontend-tools.ts:238-295` | 50ms 轮询等待 | 页面 id + handler 注册 / `workspace.taskId` 绑定 | **3s**（显式） | 3s | 到点如实返回，调用方降级文案（设计良好） | S4 |
| B-16 | `AiPanel.vue#decide` → `ai.confirm` / `components/AiPanel/AiPanel.vue:536-545`、`stores/ai.ts:850-870` | `await aiApi.confirm(...)` | 后端确认接口 | axios 60s | 60s | `deciding` 守卫 + catch 复位 `pending.status` | S3 |
| B-17 | `ExportWizardView/ImportWizardView#onMounted` / `views/ExportWizardView.vue:972-976`、`ImportWizardView.vue:983-984` | `Promise.all` + 串行加载链 | 各 axios | 单请求 60s | 数分钟级 | 各段独立 try/catch | S4 |
| B-18 | `ExportWizardView.vue#saveEdits` / `views/ExportWizardView.vue:760-788` | 逐文件串行：`toXlsxBlob()` → `arrayBuffer()` → `saveFile` | 各 axios + JSZip | 单请求 60s | N×(CPU+60s) | `ElMessage.error`，`continue` 跳过本地校验失败项 | S3 |
| B-19 | 原生 `alert/confirm/prompt` | — | — | — | — | **实测不存在**（0 处） | S4 |
| B-20 | 同步 `XMLHttpRequest` | — | — | — | — | **实测不存在**（0 处） | S4 |
| B-21 | `document.write` | — | — | — | — | **实测不存在**（0 处） | S4 |
| B-22 | `TaskCenterView.vue#cancelTask` / `views/TaskCenterView.vue:388-395` | `await ElMessageBox.confirm`（无 `timeout`/`closeOnClickModal` 兜底） | 用户点确定/取消 | **无** | 无界（悬挂 promise） | 用户不点则后续取消逻辑永不执行；组件卸载后模态框与 promise 悬空（非业务挂起，`cancellingId` 尚未置位） | S3 |
| B-23 | `TaskCenterView.vue#removeTask` / `views/TaskCenterView.vue:414-421` | 同上 | 用户点选 | **无** | 无界 | 同上（删除请求不发） | S3 |
| B-24 | `ImportWizardView.vue#handleUpload` / `views/ImportWizardView.vue:539` | `await ElMessageBox.alert`（未匹配文件提示）**位于业务流中段** | 用户关闭弹窗 | **无** | 无界 | 弹窗不关 → 后续 `ElMessage.success` + `await loadEditors(true)`（`:541-544`）永不执行，且 `finally` 不达 → `busy.value` 永久为 `'upload'`（上传按钮持续禁用） | S3 |
| B-25 | `AiPanel.vue` 确认卡 / `components/AiPanel/AiPanel.vue:181-199` | 等待人工点「确认/拒绝」（无本地自动兜底） | 用户点击 或 后端结局帧 | 后端权威超时（帧 `timeoutSeconds`/`expiresAt`）；本地倒计时仅置灰按钮 | 后端超时窗口 | 倒计时到点置灰（`confirmExpired`），由后端结局帧收敛 —— 已有兜底 | S3 |
| B-26 | `task.ts#startAutoRefresh` / `stores/task.ts:76,187` | 模块级 4s `setInterval` | 手动 stop / 卸载 | tick 前 `if (!this.loading)` 防重叠 | 单次 ≤60s | `onBeforeUnmount` → `stopAutoRefresh` | S4 |
| B-27 | `AiPanel.vue` tickTimer / `components/AiPanel/AiPanel.vue:326,595-597` | 1s `setInterval` 推进倒计时 | 卸载 | — | — | `onBeforeUnmount` clearInterval | S4 |
| B-28 | `SpreadGrid.vue` resizeTimer / `components/SpreadGrid.vue:83,127` | 50ms 防抖 `setTimeout` + ResizeObserver | 卸载 | 50ms | 50ms | 卸载 clearTimeout + `disconnect()` | S4 |
| B-29 | `ai.ts#openGenerativeForm` / `stores/ai.ts:984-989` | `setTimeout` 本地表单过期 | deadline 到期 | 按帧 `expiresAt−atMs` 折算 | 帧给定 | 5 个出口均 `disarmFormExpiry()`（done/error/stop/resetSession/onClose、帧结局） | S4 |
| B-30 | `ai.ts#startMirror` / `stores/ai.ts:52,356-361,148-167` | 300ms 防抖后**主线程全量 `JSON.stringify(messages)`** 写 sessionStorage | 防抖到期 | 300ms | 与消息体积成正比（同步） | 全部包在 try/catch（配额/隐私模式忽略）；模块级 timer 不随组件卸载清理（有意） | S3 |
| B-31 | `excel.ts#downloadBlob` vs `http.ts#saveBlob` | `setTimeout(() => revokeObjectURL, 3000)` vs 立即 revoke | 3s | 3s | 3s | 仅 Blob URL 多存活 3s（两处实现不一致） | S4 |
| **B-32** | `excel.ts#buildXlsx` / `utils/excel.ts:544-636` | **主线程同步**构造整份 OOXML：`rows.map(cells.map(...).join('')).join('')` + 每共享串一次 `escapeXmlText` 正则；首个 `await`（JSZip `generateAsync`）在 `:632` 才出现 | 无对端（纯 CPU） | **无** | O(行×列)；5 万行×20 列≈百万单元格 → 秒级~十秒级卡死主线程 | 卡顿期间所有定时器被冻结：可能**误触发** 90s 帧间静默判流（见 B-02）；打包下载路径 `Promise.all(map(async…))`（`:717-721, 741-745`）把各文件 XML 构建**串行化**在同一主线程上 | **S2** |
| B-33 | `excel.ts#readRows/validateRows/setRows` / `utils/excel.ts:334-440` | 逐单元格同步循环 | 无对端 | 无 | O(行×列) | 无（纯 CPU，调用点均为用户/AI 触发的同步段） | S3 |
| B-34 | `SpreadGrid.vue#loadFromJson` / `components/SpreadGrid.vue:210` | `instance.fromJSON(json)` 同步解析整本工作簿 | 无对端 | 无 | O(工作簿体积) | 无 | S3 |
| B-35 | `ai.ts` 深订阅 / `stores/ai.ts:356-361`、`handleFrame:1204-1211` | `$subscribe(..., {deep:true})` + 每 token `message.content += text` | Pinia 响应式通知 | — | 每 token O(消息总量) 遍历 | 无泄漏（防抖写入） | S4 |
| B-36 | `ConditionForm.vue` / `components/wizard/ConditionForm.vue:152` | 处理器内 `JSON.stringify` 双比较 | 无对端 | 无 | 小对象可忽略 | 无 | S4 |

**分级统计**：S1 = 2 条（B-03、B-07）；S2 = 8 条（B-01、B-05、B-06、B-08、B-09、B-10、B-11、B-32）；S3 = 13 条；S4 = 13 条；合计 36 条（含 3 条排除项）。

**`downloadOne` / `downloadBlob` 链**：标注「**专查中**」——本报告只做清单级覆盖（见 B-32 的 CPU 侧、B-18、B-31、`views/ExportWizardView.vue:689-752`、`utils/excel.ts:839-848`），网络/字节链深度让给专查子智能体。

---

## ③ S1 / S2 详述（证据 + 推演 + 修法方向，未改任何代码）

### S1-1 / B-07：`SpreadGrid` 的 `readyPromise` 只有 resolve 一侧，两条路径永久悬挂

**证据**（【实测】读码坐实；全仓 `grep readyPromise` 仅 2 处命中）

```
components/SpreadGrid.vue:86-89
  let readyResolve: (() => void) | null = null
  const readyPromise = new Promise<void>((resolve) => { readyResolve = resolve })

:91-96   onMounted(async () => {
           try {
             const gs = await loadSpreadCore()
             if (!host.value) { return }            // ← 早退，readyResolve 从未调用
:118       readyResolve?.()                        // ← 唯一的 settle 点
:132-135   } catch (error) {
             engineState.value = 'failed'          // ← 失败分支不 resolve、不 reject
             engineError.value = `表格引擎加载失败：…` }

:202-207 async function loadFromJson(...) {
           await readyPromise                        // ← 无 timeout 的永久 await
```

**对端**：`onMounted` 成功路径的 `readyResolve?.()`。**无 reject 分支、无超时**——这是全仓唯一一个「可永久悬挂且无可达 settle 路径」的 Promise。

**触发路径（两条，均为现实可达）**

1. `loadSpreadCore()` 拒绝：`utils/spreadjs.ts:45-66` 动态 `import('@grapecity/spread-sheets')` 及其资源包/styles CSS（chunk 约 4.7MB，见 `vite.config.ts:46` 注释）。离线、CDN/静态资源 404、CSP 拦截、部署漏包任一成立即拒绝；`spreadCorePromise` 置 null 只保证"下次可重试"，但**已经 `await` 着 `readyPromise` 的调用者不会被打断**。此时 UI 显示"表格引擎加载失败"，而工具调用侧仍在永久等待。
2. `if (!host.value) return`：`SpreadGrid` 在 `el-tabs` 懒渲染场景下宿主尺寸为 0/未挂载（`SpreadGrid.vue:122` 注释自认该场景常见），早退后 `engineState` 仍是 `'loading'`（UI 永远"表格引擎加载中…"），`readyPromise` 悬空。

**最坏阻塞时长**：无界。

**失效安全方向（最危险处）**：向上游传播为**无限悬挂**，且链条穿透到 AI 回灌：
`SpreadGrid#loadFromJson` → `ExportWizardView#ensureGridLoaded(:652)` / `ImportWizardView.vue:569,575` → `loadExistingResults`/`refreshResults`/`loadEditors` → `loadTask`/`applyTaskBinding(:433-451)` → `ensureTaskBoundForTool(:827)` / 页面能力 handler（`export.openEditor :928-948`、`export.selectDefs`、`import.*`）→ `utils/frontend-tools.ts#runRegisteredTool/runPageHandler(:517-605)`，唯一兜底是 `try/catch`（**catch 不住悬挂**）→ `stores/ai.ts#runFrontendTool:1485` → `submitFrontendToolResult` **永不执行**。
即：**后端 `frontend_tool_request` 的挂起只能等它自己的超时收尾，前端侧不留任何痕迹、不报错、不降级**。用户看到的是工具卡永久停在"执行中…"。

**修法方向（建议，未实施）**

- 给 `readyPromise` 加**双侧兜底**：把 `readyResolve`/`readyReject` 成对保存，在 `catch` 与早退分支显式 `readyReject(new Error('表格引擎不可用'))`；消费者侧用可超时包装替换 `await readyPromise`，超时即抛可读错误。
- 早退分支同时 `engineState.value = 'failed'`（现在停在 `'loading'`，UI 在说谎）。
- 更上位：给整个执行器链（`executeFrontendTool`）加**统一超时预算**，让前端先失败并回灌一条"前端执行超时"的可行动文本，任何单点悬挂都不会让回灌沉默（详见 §5）。

### S1-2 / B-03：`reattach` 在 `onopen` 之前没有看门狗 → 该轮次无限悬挂

**证据**（【推断】：读码坐实控制流，触发条件依赖连接形态）

```
api/sse.ts:413   return await new Promise<StreamCloseInfo>((resolve) => {
:419-424  clearIdle() 只清 idleTimer
:426-436  settle() 是唯一 resolve 出口；其调用点仅：
            · onAbort（外部 signal）
            · startIdle 的 idle 回调（:444-452）
            · scheduleReconnect 的耗尽/aborted 分支（:459-465）
            · source.onmessage 收到终帧（:493-495）
            · source.onerror 分支（:497-509）
:442-453  startIdle() —— **只在 `source.onopen` 里调用（:482-485）**
:478-484  open() { source = new EventSource(...); source.onopen = () => { handlers.onOpen?.(); startIdle() } … }
```

**控制流推演**：`EventSource` 构造后，若连接**一直不 `open` 也不 `error`**（TCP 已建、服务端迟迟不 flush 响应头；或中间代理黑洞/静默挂起），则 `idleTimer` 从未被武装，`scheduleReconnect` 无从触发，`settle` 无可达路径 → **promise 永久 pending**。

**最坏阻塞时长**：无界（浏览器对 `EventSource` 无原生命中；`vite.config.ts:33` 的 `proxyTimeout:300000` 只在 dev 生效，生产部署无此保证）。

**失效安全方向**：三个调用点的收尾全部被跳过（`finally` 属同一条 promise 链，不会执行）：

- `stores/ai.ts#send`（`:703-717`）：`loading` 永不复位 → `canSend` 恒 false → **输入区与发送按钮永久禁用**，只能靠用户手动点「停止」（`stop()` 走 abort，可救）或刷新页面。
- `stores/ai.ts#reattachActive`（`:838-846`）：同上，且 `busyConflict` 不被清（`:833`）。
- `stores/ai.ts#resumeSnapshotStream`（`:616-623`）：同上（`loading=true` 在 `:583` 置位）。

另外 `currentAbort` 不被释放（只被下一次轮次覆盖），故"停止"按钮可用是唯一的现场自救手段。

**修法方向（建议，未实施）**：在 `open()` 里**构造 `EventSource` 之后立即 `startIdle()`**（把看门狗从"连接建立后"前移到"发起连接起"），并让退避窗口也共享同一计时器兜底；或在 `new EventSource` 外包一层 `Promise.race` 连接超时（10~15s），超时即 `settle(false, '重挂连接超时')`。

### S2-1 / B-01：`await fetch('/api/ai/chat')` 等响应头段无前端预算

**证据**（【实测】读码）
`api/sse.ts:237-247` 的 `resetIdle()` 在 `readStream()` **内部**首次调用，而 `readStream` 的入参是已经拿到的 `response`（`:350`）。因此 `streamChat` 的 `await fetch(...)`（`:326`）到"响应头到达"之间**没有任何前端计时器**。唯一的外部约束是 dev 代理 `timeout: 300000` / `proxyTimeout: 300000`（`vite.config.ts:33-34`），生产部署（nginx / 网关 / 直连）不继承该值。

**推演**：后端 `POST /api/ai/chat` 挂起（上游 LLM 连接创建期阻塞、线程池排队、`SseChatEmitter` 尚未 commit 响应）期间，前端在这条 await 上无界等待；用户看不到任何倒计时/提示（`notice` 尚未写、`loading` 已 true）。

**失效安全方向**：一旦响应头到达即进入 90s 看门狗保护（B-02），故爆炸半径限于"响应头之前"；超时后（网络异常）走 `catch` → `ApiError(NETWORK_ERROR)` → `handleChatError` → `notice`，状态可收敛，**不产生悬挂/泄漏**。故判 S2 而非 S1。

**修法方向**：给 `streamChat` 的 fetch 加 `AbortSignal.timeout(...)`（或复用 90s 预算的 watchdog）作为"响应头超时"，与帧间静默超时用不同文案区分。

### S2-2 / B-05：单轮总预算 ≈ 11 分钟，全程 `loading=true`、输入区禁用

**证据 + 推演**（【实测】常数与分支读码）
`DEFAULT_IDLE_TIMEOUT_MS=90_000`（`sse.ts:22`）、`DEFAULT_MAX_RECONNECTS=2`（`:25`）、`DEFAULT_RECONNECT_DELAY_MS=1_000`（`:28`，退避 `baseDelay * 2**(attempts-1)`，`sse.ts:469`）。
`send()` 的 await 链最坏路径：

1. fetch 响应头：≈300s（仅 dev 代理兜底，见 B-01；生产无界）
2. 首流帧间静默 90s → `idleTriggered` → `tryReattach`（`sse.ts:357-365`）
3. reattach 三轮：90s + 1s退避 + 90s + 2s退避 + 90s = 273s（`scheduleReconnect` 在 `attempts >= maxReconnects` 时 settle，`sse.ts:463-465`）

合计 ≈ **663s（约 11 分钟）**，期间 `stores/ai.ts:650` 的 `loading=true` 让 `canSend`（getter `:339`）恒 false，输入区 `:disabled="!ai.canSend"`（`AiPanel.vue:267`）**全程不可用**。

**失效安全方向**：最终以 `finish(terminal=false)` 收尾 → `onClose` → `streamInterrupted=true` + notice（`:670-685`）→ `finally` 复位 `loading`（`:703-717`）。**状态最终可收敛，但耗时是分钟级且用户无进度感**。

**修法方向**：为整轮设置 wall-clock 上限（例如 120~180s）并显式提示"本轮已超过预期时长"，或把 90s×2 次重挂的乘积预算算清后按产品口径收紧。

### S2-3 / B-06 + B-09 + B-10 + B-11：前端工具执行器是**无预算的复合 await 链**（回灌的沉默面）

**证据**（【实测】）
`stores/ai.ts:1298` 用 `void this.runFrontendTool(message, frame)` 调用 —— 这一点是**好消息**：SSE 帧处理循环（`handleFrame`）**不会**被执行器挡住，帧照常流动、UI 不冻结。坏消息是执行器本身**完全没有本地超时**（`stores/ai.ts:1481-1501` 全文无 timer/signal 逻辑），其最坏值由下游各点的最大值决定：

| 子链 | 位置 | 预算 |
|---|---|---|
| `await pending.promise`（等他人发起的任务绑定） | `views/ExportWizardView.vue:827` | 无（继承下一行） |
| 绑定链含 `loadTask` → `loadExistingResults` → `ensureGridLoaded` | `views/ExportWizardView.vue:433-451, 341, 351-374, 641-659` | 无（含 B-07/B-08 的无界段） |
| `await router.push`（懒加载 chunk + 目标页 mount） | `stores/workspace.ts:351,357,413,417,421` | 无 |
| `await fetch(event.url)`（`download` ui_event，无 signal） | `stores/workspace.ts:425` | 无 |
| `await waitForPageReady/waitForTaskBound` | `utils/frontend-tools.ts:238-295` | 3s（唯一的显式预算） |
| `await handler(args)`（页面能力，含 grid 加载） | `utils/frontend-tools.ts:592` | 无（仅 try/catch） |
| `await submitFrontendToolResult` | `stores/ai.ts:895-899` | axios 60s |

**失效安全方向**：任一子链悬挂 → `submitFrontendToolResult` 永不调用 → `POST /api/ai/frontend-tool-result` 永不发出 → 后端挂起**只能等它自己的超时**收尾，且**前端没有任何超时/失败提示**（`runFrontendTool` 连 `finally` 都没有，工具卡停在"执行中…"，见 `AiPanel.vue:234`）。若该 await 在后端超时**之后**才返回，迟到的回灌会落 409 幂等拒绝并被吞成 notice（`stores/ai.ts:902-913`），**不会造成双重执行** —— 这一条是安全的。

**修法方向**：见 §5 的根因建议（统一执行器超时 + 超时后主动回灌一条"前端执行超时"的可行动文本）。

### S2-4 / B-32：`buildXlsx` 是主线程同步大活，且会与 SSE 看门狗互相伤害

**证据**（【实测】读码；`utils/excel.ts:544-636`）
`buildXlsx` 的首个 `await` 出现在 `:632`（`zip.generateAsync`）。此前全部是同步字符串构建：三层嵌套 `map/join`（`:558-577`）为每个单元格拼 `<c r="…">`，`toShared` 对每个共享串建 Map 条目（`:547-556`），最后 `escapeXmlText` 对每个共享串跑 5 个正则替换（`:530-537`）。

**推演**：以 5 万行 × 20 列（100 万单元格）计，单次调用可占满主线程数秒至十余秒。期间**所有 `setTimeout`/`setInterval` 被冻结**，其中包括 `sse.ts:242` 的 90s 帧间静默看门狗与 `AiPanel.vue:595` 的 1s 倒计时节拍。
更值得注意的是**误判风险**：若主线程被同步大活阻塞超过 90s（大工作簿 `fromJSON` / 大表 `validateRows` / 极端大导出），看门狗回调会在阻塞结束后立即到期触发 `onIdle()` → `controller.abort()` → 前端把**正常的流**判成"帧间静默超时，判定断流"（`sse.ts:358`）并进入重挂，UI 弹出"连接中断，结果可能不完整"。这是"主线程阻塞"与"流看门狗"的耦合缺陷。【推断】

**放大项**：打包下载路径 `Promise.all(checkedDefs.map(async (code) => { … buildXlsx(code) … }))`（`views/ExportWizardView.vue:717-721` 与 `741-745`）—— `Promise.all` 只并行"等待"，而 `buildXlsx` 内的同步构建**仍串行占用同一主线程**，等价于所有文件的 XML 构建时间相加。该链属 `downloadOne` 家族，**专查中**，此处仅记录 CPU 侧。

**修法方向**：把 XML 构建切块让出主线程（分片 `await` 或 Web Worker）；至少给看门狗加"主线程可用性"豁免（例如用 `performance.now()` 实测帧间隔而非纯 wall-clock 定时器）。本报告不涉及 `downloadOne` 的网络链。

### S2-5 / B-08：`blobToWorkbookJson` 依赖 ExcelIO 回调，缺兜底

**证据**（`utils/excel.ts:639-649`）：`await new Promise((resolve, reject) => excelIo.open(blob, resolve, reject))`，**无 timeout、无 signal**。ExcelIO 是 UMD 异步 API，若其内部 worker 停滞或既不走 success 也不走 error，则 promise 永不 settle。消费链：`SpreadGrid#loadFromXlsx:227-229` → `ensureGridLoaded` → 与 B-07 同一条回灌链（故影响面等同于 S1-1，但触发概率更低）。判 S2。

### S2-6 / B-10、B-11 补充说明（有/无兜底的精确边界）

- `router.push`（B-10）：**chunk 加载失败（404/网络错误）会 reject**，被 `stores/workspace.ts:449-451` 的 `catch` 兜成 `fail('ui_event 处理失败：…')` → 回灌一条可读失败文本，状态可收敛。**只有"停滞不返回"才是无兜底**。
- `fetch(event.url)`（B-11）：**没有任何兜底**（无 signal、无 timeout），且是 `download` ui_event（`stores/workspace.ts:424-431`）与工具名路由 3（`utils/frontend-tools.ts:562-581`）的共用路径。注意这条**不是** `downloadOne` 链，勿与专查子智能体的范围混淆。

---

## ④ S3 / S4 简表

| 级别 | ID | 一句话结论 |
|---|---|---|
| S3 | B-02 | SSE 帧间 `reader.read()` 有 90s 看门狗，超时后 reattach 且 ADR-6 提示，兜底完整 |
| S3 | B-12 | `persistStepData` 逐配置项串行落库，(N+2)×60s 级；失败有提示、`persisting` 有 finally |
| S3 | B-13 | `waitForJobFinal` 5 分钟上限**返回非终态而不报错**；`watchPublishOutcome` 据此把 `publishConflict` 置 null → **超时后状态不一致**（发布悬置会被静默吞掉） |
| S3 | B-14 | `watchJob` 无最大时长；`ImportWizardView` 同 kind 重跑只 push 不回收旧 watcher → EventSource/定时器可累积 |
| S3 | B-16 | `ai.confirm` 走 axios 60s；`deciding` 守卫 + catch 回填 `pending` |
| S3 | B-18 | `saveEdits` 逐文件串行 CPU+上传，N 倍放大 |
| S3 | B-22 | `ElMessageBox.confirm`（取消作业）无 timeout 兜底，用户不点则取消逻辑永不执行 |
| S3 | B-23 | 同上（删除任务） |
| S3 | B-24 | 业务流中段的 `ElMessageBox.alert`（未匹配文件）不点则 `loadEditors` 不跑且 `busy` 永久占位（上传按钮持续禁用） |
| S3 | B-25 | AI 确认卡等人工点击，本地仅置灰按钮，权威超时在后端 —— 已知观察项范围，现状已具备兜底 |
| S3 | B-30 | 镜像 300ms 防抖后**主线程全量 `JSON.stringify`**，长会话时每次停顿做一次全量序列化 |
| S3 | B-33 | `readRows/validateRows/setRows` 逐单元格同步循环 |
| S3 | B-34 | `instance.fromJSON(workbook JSON)` 同步解析整本工作簿 |
| S4 | B-04 | 重连退避 `setTimeout` 未纳入 `settle` 清理，但有 `!settled` 守卫，不会重开连接 |
| S4 | B-15 | `waitForPageReady`/`waitForTaskBound` 有显式 3s 预算并如实降级（**范本式实现，建议其余 await 点对齐**） |
| S4 | B-17 | 页面 `onMounted` 加载链，各段独立 try/catch，单请求 60s |
| S4 | B-19/20/21 | 原生 `alert/confirm/prompt`、同步 XHR、`document.write`：**实测全仓 0 处** |
| S4 | B-26 | 任务中心 4s 自刷：模块级 timer + tick 前 `loading` 防重叠 + 卸载清理（形态良好） |
| S4 | B-27 | AI 面板 1s 倒计时 timer，卸载清理 |
| S4 | B-28 | SpreadGrid 50ms 尺寸防抖 + ResizeObserver，卸载清理 |
| S4 | B-29 | 生成式表单本地过期 timer，5 个收尾出口均 `disarmFormExpiry()` |
| S4 | B-31 | `excel.ts` 延迟 3s 回收 Blob URL，`http.ts` 立即回收 —— 两处实现不一致（无害） |
| S4 | B-35 | Pinia `deep:true` 订阅 + 每 token 追加，长会话有 O(size) 遍历开销 |
| S4 | B-36 | `ConditionForm` 处理器内 `JSON.stringify` 双比较，对象小可忽略 |

**另记两条不在上表的状态一致性问题（属 §5 关联面）**：

- `stores/ai.ts:670-685`：用户点「停止」（`stop()` → `currentAbort.abort()`，`sse.ts:357-364` 走 `finish(terminal=false, '连接已中止')`）后，`onClose` 里**不检查 `this.stopped`**，于是用户主动取消也会看到"连接中断，结果可能不完整"的 error 级 Alert。`stopped` 字段（`:266`）在此分支未被使用。【实测】
- `stores/ai.ts:984-989` 与 `:552-561`：`restoreSuspendedSnapshot` 重建表单时 `deadline: null`（不武装本地计时器），仅靠后端超时收敛 —— 与已知观察项口径一致，**此处仅坐实现状，不深挖**。

---

## ⑤ 与 issue#6 争议（"await 阻塞回灌 120s"）的关联点

**范围内关联一句话**：前端**不存在 120s 常量**，也不存在"帧处理器被 `await` 挡住"的路径（`frontend_tool_request` 走 `void this.runFrontendTool(...)`，`stores/ai.ts:1298`，SSE 帧循环与 UI 均不阻塞）；真正的"回灌被卡 120s"来自**前端工具执行器这条无预算的复合 await 链**（B-06 及其下游 B-07/B-08/B-09/B-10/B-11/B-32），其最坏值无界，唯一时间边界是后端 `ConfirmGate` 的挂起超时（120s 应即此处口径）。

**支撑事实（均为【实测】读码结论）**：

1. **120s 不在前端**：全仓 `grep 120` 仅命中样式（`min-width="120"`）。前端消费该超时只通过帧字段：`toPendingCall`（`stores/ai.ts:1402-1419`）读 `timeoutSeconds/expiresAt/atMs`，`foldDeadline`（`:87-100`）折算成本地 deadline，`openGenerativeForm`（`:974-989`）据此武装本地表单过期 timer。**`frontend_tool_request` 的 120s 只能是后端权威值。**
2. **帧处理器不被阻塞**：`handleFrame` 的 `frontend_tool_request` 分支（`:1282-1300`）对通用前端工具用 `void this.runFrontendTool(...)`、对生成式表单用 `void this.submitFrontendToolResult(...)`（`:968`）/`void this.cancelGenerativeForm(true)`（`:987`），全部非 await 调用；`sse.ts#consumeFrame`（`:149-167`）同步调用 `handlers.onFrame`。故**"前端 await 把 SSE 帧处理拖住"这一指控在前端侧不成立**；能拖住的是回灌动作本身。
3. **回灌链确实无界**：`stores/ai.ts:1481-1501` 全文无 timer/AbortSignal；下游 `SpreadGrid.readyPromise`（B-07）是**唯一可永久悬挂且无 settle 路径**的点，`ensureTaskBoundForTool` 的 `await pending.promise`（B-09）、`router.push`（B-10）、`fetch(ui_event download)`（B-11）与 `buildXlsx` 同步主线程块（B-32）无预算。**这与 issue#6 的"120s 才收尾"现象完全吻合**：前端不求超时、不主动失败，后端 120s 到点是唯一收敛力量。
4. **迟到回灌不会双重执行**：后端超时后前端若才返回，`submitFrontendToolResult` 收到 409 `DUPLICATE_TOOL_CALL_ID` 时按响应体 status 收敛投影并吞为幂等提示（`stores/ai.ts:902-913`）。此点在争议中可作"安全侧"证据。
5. **根因修法方向（未实施）**：给 `executeFrontendTool`（`utils/frontend-tools.ts:148`）加**统一超时预算**并把超时转成一条可行动的回灌文本（例如"前端执行超时，未完成；请提示用户手动操作"），使前端在**后端 120s 之前**就主动失败并回灌 —— 这样任何单点悬挂（B-07/B-08/B-09/B-10/B-11/B-32）都不会再表现为"沉默 120s"。这也与 `waitForPageReady`（B-15，3s 预算 + 如实降级）的既有做法一致，属低风险改动。

**已知观察项（本报告不深挖，仅坐实）**：`EVICTED_TOTAL` 拆分、`WARN` 丢根因、`ai:beat` 静默失效、`EXECUTOR_SATURATED` 无自愈、A9 双驱动竞态、回放分片三触发 —— 在 `frontend/src/` 范围内未发现对应实现（前端无 `ai:beat`、无 `EXECUTOR_SATURATED` 文案；`grep` 均无命中），故本报告不对其表述。与前端确有关联的是"回放分片三触发"的消费侧，即 `resumeSnapshotStream`（`stores/ai.ts:580-624`）的 `lastSeq` 增量续收，其连接层风险已并入 B-03。

---

## 附录：审计可复现命令（只读）

```
git -C <MAIN_REPO> status --porcelain          # 空
git -C <MAIN_REPO> rev-parse HEAD              # 550c7c4
# 原生阻塞 API（0 命中）
grep -rnE '\b(alert|confirm|prompt)\s*\(' frontend/src
grep -rn 'XMLHttpRequest\|document\.write' frontend/src
# UI Promise 等待点
grep -rn 'ElMessageBox' frontend/src
# 定时器与无法 settle 的 Promise
grep -rn 'setTimeout\|setInterval\|new Promise' frontend/src
grep -rn 'readyPromise' frontend/src          # 仅 SpreadGrid.vue:87,203
# 前端超时预算（全仓仅 5 处）
grep -rn 'TIMEOUT_MS\|timeout:' frontend/src
```
