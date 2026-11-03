# 后端轮询、定时等待与主动探测审查

- 审查基线：`52b09364`（核对时 `HEAD` 为同一提交，工作区当时无变更）。
- 审查对象：`backend/src/main` 下生产 Java 与资源配置；没有读取或修改测试代码。
- 工作方式：静态、只读审查。未运行测试、构建、CI、服务或压测，未做任何 Git 写操作。
- 结论口径：“代码证实”表示控制流与后端调用可由源码直接推出；耗时超过 30 秒、QPS 和容量阈值均未实测，不能当线上事实。下文把触发条件单独列出。

## 一、结论摘要

1. **删除对话接口存在代码可证的超 30 秒风险，且不是单点原因。** 每次删除都会同步执行“归档键全键空间扫描 + 记忆 staging 全键空间扫描”；Cluster 下逐 master 串行，两个调用都传 `maxKeys=0`，即不限制扫描结果数/游标轮数。之后还有按 run、归档、附件逐项串行的 Redis/文件/DB 操作。单命令 2 秒超时不构成整请求超时。见 **P-01、P-02**。
2. **运行期死轮恢复扫描的发现面没有工作量上界。** `scan-max-attempts` 只限制后续动作，不限制发现阶段的 `SMEMBERS + 每成员 EXISTS + 每活轮多键读取`。大索引、慢 Cluster 下，一个周期任务可长时间占用扫描线程；正常时有跨实例扫描租约，但 Redis 不可确认时会退化为各实例本地单飞。见 **P-03**。
3. **Redis 前置 PING 在健康态没有正缓存，并用一个实例级 `synchronized` 包住真实 PING。** 多线程下所有使用者串行；如果 PING 慢但未失败，吞吐会受单条时间线限制。见 **P-04**。
4. **作业看门狗在每个实例每 10 秒重复扫同一共享 DB 的在途作业，并逐作业读 Redis；只有“判死动作”有单飞，扫描本身没有。** 见 **P-05**。作业心跳又是单线程逐 job 两次 SET，慢 Redis 下没有整拍失败短路，见 **P-06**。
5. **AI 跨实例取消仍是每个活跃 attempt 1 Hz 的 Redis GET；这是当前真实轮询，不应被同进程监听器或 SSE 心跳掩盖。** 默认 20 并发时约 20 GET/s/实例（仅驱动方实例），见 **P-07**。
6. 生产 `app.ai.park.enabled=true` 使长期人工等待退出线程，避免了确认门长期 2 秒轮询；若关闭 park，确认门会同时轮询取消标志和待决条目，并与流式取消轮询叠加，见 **P-08**。
7. KB 冷启动等待和缓存写窗等待的名义 3 秒/150 ms 不是实际硬上界，因为每圈还含可达 2 秒的同步 Redis 读，且 sleep 的中断被吞后继续；见 **P-09**。模型主动探测超时后未取消异步 HTTP future，见 **P-10**。
8. 多数定时任务有生命周期关闭、异常保底和有界次数；不能据此推断“没有性能问题”。主要空缺是：周期任务普遍无抖动、部分没有跨实例扫描单飞、若干部件把“sleep 时长”误当成包含 I/O 的总期限。

### 严重度与确定性总表

| 编号 | 严重度 | 确定性 | 结论 |
|---|---|---|---|
| P-01 | 高 | 代码证实机制；未实测耗时 | 删除接口每次两轮无界逐 master SCAN，且整请求同步无总死线 |
| P-02 | 高 | 代码证实机制；未实测耗时 | 活跃 run 的 1.5 秒轮询窗口不约束圈内同步 I/O，删除后续逐项操作也无界串行 |
| P-03 | 高 | 代码证实 | 恢复扫描在动作预算前做全索引、逐成员、逐活轮同步读取 |
| P-04 | 高 | 代码证实 | 健康 Redis 每请求 PING + 实例级同步锁，慢 PING 串行化多线程 |
| P-05 | 中 | 代码证实 | 作业看门狗扫描无跨实例单飞，N 实例重复 DB/Redis 压力 |
| P-06 | 中 | 代码证实 | 作业心跳单线程逐 job 两写，慢/失败命令无整拍短路或抖动 |
| P-07 | 中 | 代码证实 | 每活跃 attempt 1 Hz 取消 GET；事件通知可替代大部分稳态轮询 |
| P-08 | 中（仅 park=false） | 代码证实，出厂配置规避 | 非 park 等待每 2 秒读取消和 pending，可能重复且没有 pending 读退避 |
| P-09 | 中 | 代码证实 | KB 两段等待不含 I/O 的真实总期限，且中断后继续循环 |
| P-10 | 中 | 代码证实 | 主动模型探测超时只释放调用方，不取消 future；响应仍先完整入内存 |
| P-11 | 低 | 代码证实 | 会话清理空载时每 30 秒仍逐实例抢全局锁，固定同拍且无抖动 |
| P-12 | 低 | 代码证实 | 多条失败重试链的进程内单飞不能跨实例去重，部分链无抖动 |

---

## 二、范围、检索闭合与实际阅读文件

### 2.1 检索闭合

对 `backend/src/main` 做了以下全量模式检索，并逐条回到源码阅读：

- `@Scheduled`、`@EnableScheduling`、`fixedRate`、`fixedDelay`、`scheduleAtFixedRate`、`scheduleWithFixedDelay`、一次性 `schedule(...)`；
- 全部 `while (...)`/`do` 命中；
- `Thread.sleep`、`TimeUnit.sleep`、`LockSupport`、`wait`、`await`、`awaitTermination`、`tryLock`、Future `get/join`、Reactor `block`、阻塞队列 `take/poll`；
- `retry/backoff/attempt/poll/probe/heartbeat/keepalive/watchdog`；
- `SseEmitter` 生命周期、Redis pub/sub、HTTP `send/sendAsync`、DB/Redis/文件主动探测；
- `application.yml`、`application-cluster.yml`、`application-postgresql.yml`、`application-h2tcp.yml` 中的周期、超时和线程池配置。

**结果：没有生产 `@Scheduled`，也没有 `@EnableScheduling`；`ConfigMgrApplication` 只有 `@EnableAsync`（`backend/src/main/java/com/example/configmgr/ConfigMgrApplication.java:7-11`）。没有业务代码直接调用 `LockSupport.park/parkNanos`，没有 Reactor `block()` 命中。这里的 “park” 是结束当前驱动并依靠 Redis 状态续跑，不是 JDK 线程 park。**

### 2.2 实际深入阅读的生产文件

以下列出本报告直接阅读并用于判断的文件；路径均相对仓库根目录。除此之外还阅读了本报告非轮询 `while` 附录中点名的循环现场。

- 配置/入口：`backend/src/main/resources/application.yml`、`application-cluster.yml`、`application-postgresql.yml`、`application-h2tcp.yml`；`ConfigMgrApplication.java`、`config/AppProperties.java`、`config/AsyncConfig.java`、`ai/config/AiProperties.java`、`ai/exec/AiExecutorConfig.java`、`task/config/TaskSseSchedulerConfig.java`、`task/config/TaskEventDispatchConfig.java`。
- 删除会话：`ai/web/AiSessionPurgeController.java`、`ai/web/AiSessionController.java`、`ai/session/AiSessionPurgeService.java`、`SessionPurgeRetryRunner.java`、`SessionPurgePendingStore.java`、`SessionPurgeGuard.java`、`SessionPurgeDatabase.java`、`RedisClusterScanSupport.java`、`SessionGate.java`、`ai/memory/RedisChatMemoryRepository.java`、`ai/run/RunStore.java`、`ai/run/ResumeService.java`。
- AI 运行与 SSE：`ai/run/ResilientChatService.java`、`StreamWatchdog.java`、`CancellationRegistry.java`、`TurnKeepalive.java`、`ToolActivityBeacon.java`、`RunLiveness.java`、`StartupResumeRunner.java`、`LeaseService.java`、`CancelledRunFinalizer.java`、`RetryBackoff.java`、`DispatchRetryBackoff.java`、`SseChatEmitter.java`、`RedisFrameStore.java`、`RedisFrameWakeBus.java`、`FrameWakeListener.java`、`RunRegistry.java`、`ai/gate/ConfirmGate.java`、`SpToolCallingManager.java`、`ai/context/ContextSummaryClient.java`、`common/sse/SseWriteBudget.java`、`ai/web/AiController.java`。
- 作业：`job/service/JobWatchdog.java`、`JobHeartbeatRegistry.java`、`StaleJobRecoveryRunner.java`、`RunnerActiveKeyCleanupRunner.java`、`JobCancellationRegistry.java`、`AsyncJobExecutor.java`、`ImportJobRunner.java`、`ExportJobRunner.java`、`PublishJobRunner.java`、`PrecheckJobRunner.java`、`job/repo/JobRepository.java`。
- KB/主动探测：`ai/kb/KbService.java`、`CachedKbSource.java`、`RedisKbCacheStore.java`、`HttpKbSource.java`、`KbConfig.java`、`KbSampleSeeder.java`、`AiKbController.java`；`ai/config/RedisAvailability.java`、`AiConnectionProbe.java`、`AiConfigService.java`、`AiConfigVersionPublisher.java`、`AiModelCatalog.java`、`AiProviderRegistry.java`、`AiRetryConfig.java`、`AiRetryDefaults.java`、`AiEndpointGuard.java`、`ai/web/AiConfigController.java`。
- 健康与停机：`health/HealthController.java`、`ReadinessService.java`、`ReadinessProbeRunner.java`、`ReadinessRunnerConfig.java`、`ReadinessProperties.java`、`RedisReadinessProbe.java`、`DbReadinessProbe.java`、`StorageReadinessProbe.java`、`SeedReadinessProbe.java`、`ShutdownDrainGate.java`。

---

## 三、完整入口 → 服务 → Redis/DB/线程 → 返回链

### 3.1 删除对话：同步 HTTP 主链与后台重试链

**HTTP 主链：**

`DELETE /api/ai/session/{sessionId}`
→ `AiSessionPurgeController.purge`（`backend/src/main/java/com/example/configmgr/ai/web/AiSessionPurgeController.java:71-103`）
→ 先经 `RedisAvailability.isAvailable()` 做 PING（`:77-79`；实际 PING 在 `ai/config/RedisAvailability.java:72-100`）
→ 当前 HTTP 线程调用 `AiSessionPurgeService.purge/purgeInternal`（`ai/session/AiSessionPurgeService.java:324-418`）
→ 固定身份作用域，登记 pending（`SessionPurgePendingStore.register`，`SessionPurgePendingStore.java:279-345`；ZADD + 单键 Lua + ZADD）
→ `enumerate`（`AiSessionPurgeService.java:566-685`）：
  - `SessionGate.currentRunId` / `RunStore.sessionCurrentRunId/sessionRunIds`；
  - 每候选 `RunStore.get`（同步 GET，见 `RunStore.java:2472-2516`）及 pending 读取；
  - `stagingKeys` 一次全键空间扫描（`AiSessionPurgeService.java:817-828`）；
  - `archiveKeys` 又一次全键空间扫描，再按 `turnNo` 推导 0..turnNo（`:739-795`）；
  - 另读 transcript/context/session/draft/attachment，并做反馈表 COUNT。
→ `SessionPurgeGuard.arm` → `SessionPurgeDatabase.delete` 独立 DB 事务（`SessionPurgeDatabase.java:98-113`）
→ `cancelActiveRuns`：每活跃 run 读/写取消键，并尝试 `ResumeService.cancelConfirmed`（`AiSessionPurgeService.java:845-902`）
→ `awaitTerminal`：每 50 ms 重读活跃 run 快照，名义窗口 1.5 秒（`:911-948`）
→ `reloadRunStatuses` → 记忆/transcript/run/上下文/附件逐项删除；`RunStore.deleteRun` 内 13 个同槽 run 键是一条 DEL（`RunStore.java:2886-2928`），但不同 run、观测键、上下文键仍逐次调用
→ 成功时保留 guard，并把 pending 排到 60 秒后的记忆复核（`AiSessionPurgeService.java:503-540`）
→ 同步返回 200 + `complete/deferred/errors`；Redis 前置失败才返回 503。

**后台链：**

`ApplicationReadyEvent`
→ `SessionPurgeRetryRunner.onReady` 建每实例单线程调度器（`SessionPurgeRetryRunner.java:154-175`）
→ 10 秒首拍、之后 fixed-delay 30 秒
→ `runOnce` 先抢 `ai:purge:retry:lock`（TTL 300 秒，`:198-240`）
→ `listDue` 最多 10 条（`SessionPurgePendingStore.java:402-459`：全局 ZSET + 每成员 GET）
→ 按台账恢复身份，调 `retry` 或 `verifyMemoryOnly`
→ 失败写回 10 秒起步、300 秒封顶的指数退避，最多 10 次（`SessionPurgePendingStore.java:94-101,510-530`）
→ 停续租/释放锁，后台无 HTTP 返回；下一次手动 DELETE 或后续到期拍才能看到最终状态。

### 3.2 AI 对话：每轮看门狗、取消轮询、保活、租约

`POST /api/ai/chat`（`ai/web/AiController.java:329-330`）
→ AI 可用性与 Redis PING（`:332-355`）
→ 建无传输死线 SSE emitter，并挂到 `RunRegistry/SseChatEmitter`（`:544-546`）
→ 任务装饰器传播身份，提交到 `aiRunExecutor`（默认 20 平台线程、零等待队列；`ai/exec/AiExecutorConfig.java:93-113`）
→ `ResilientChatService.chat/driveResumed`（`ResilientChatService.java:681` 起）
→ `TurnKeepalive` 每 5 秒出本地 SSE 心跳（`:791,1031,1075-1077`；实现 `TurnKeepalive.java:171-209`）
→ `runAttempts` 的受控循环（`ResilientChatService.java:1460-1555`）
→ 每 attempt 建 `StreamWatchdog`：
  - `aiStreamWatchdog` 两线程上每 1 秒做纯内存超时判定（`StreamWatchdog.java:156,191-258`）；
  - `aiCancelPoll` 两线程上每 1 秒做 `CancellationRegistry.cancelState` → Redis GET（`:158,279-294`；`CancellationRegistry.java:381-425`）；
  - `CompletableFuture` 在驱动线程等待上游完成，兜底由段预算控制（`ResilientChatService.java:2334-2360`）。
→ 失败重试经 0.5 秒起指数 + 抖动退避，最多两次预算内 attempt；模型内部另有最多 3 次调用的 Spring Retry。
→ 终态/异常/park 都取消看门狗与保活；`SessionGate` 和 `LeaseService` 分别释放或停止续期；最终 SSE 帧经有界投递/写池回客户端。

### 3.3 启动/运行期恢复扫描

`ApplicationReadyEvent`
→ `StartupResumeRunner.onReady`（`StartupResumeRunner.java:254-292`）
→ 注册运行期 fixed-delay 扫描（默认 120 秒；`:1147-1173`），另建启动守护线程，睡 `8s + [0,5s]` 后执行一次启动扫描（`:275-291`）
→ `scan/sweep`（`:295-354,497-552`）
→ 进程内 `ReentrantLock` + Redis 扫描租约（`:915-985`）；启动/手工抢锁失败可在 100–200 ms 抖动切片内等待，启动拍最多 3 次
→ `ResumeService.listRunsForRecovery`（`ResumeService.java:471-607`）
→ `RunStore.allRunRecords`：`SMEMBERS ai:runs` + 每成员 EXISTS（`RunStore.java:3163-3189`）
→ 每活轮读快照、pending、取消键、业务活动锚点与 Stream 尾帧；运行期再读租约/本地订阅，必要时取消收尾、到期收敛或派发续跑
→ 扫描报告写内存，供 `GET /api/ai/startup-resume`；手工 `POST /api/ai/startup-resume/scan` 同步返回报告（`AiController.java:1736-1779`）。定时链本身无 HTTP 返回。

### 3.4 作业轮询与心跳

- `JobWatchdog @PostConstruct` → 每实例一个 daemon 单线程，fixed-delay 10 秒（`JobWatchdog.java:145-159`）→ `findByStatusIn(PENDING,RUNNING)` 读 DB（`:200-231`）→ 每 RUNNING 非本地 runner 读 `job:beat`，缺失再读 `job:runner-active`（`JobHeartbeatRegistry.java:492-513`）→ 命中判死时先 `SET NX PX` 单飞，再写 DB issue/FAILED、Redis 取消键并清理 runner 状态（`JobWatchdog.java:328-401`）→ 方法 void；指标由健康面读取。
- `JobHeartbeatRegistry.beginRunner` → 有活跃 runner 时懒启动每实例一个 ticker（`JobHeartbeatRegistry.java:290-315,412-446`）→ 每 30 秒遍历本实例 activeRunners，逐 job 写 runner-active + beat 两键（`:397-405`）→ runner 退出/撤销/无活跃时关闭 ticker；无直接 HTTP 返回。
- 作业 HTTP 创建后由 `AsyncJobExecutor @Async` 进入 4/8 线程有界池（`AsyncJobExecutor.java:28-45`）→ 四种 runner 每批 DB/文件处理、SSE 发布、Redis 取消 GET；每批另有 300 ms 装饰 sleep → finally 注销 runner/取消状态。
- 启动时 `RunnerActiveKeyCleanupRunner(@Order(-1))` 先做一次有最多 512 键视野的逐 master SCAN（`RunnerActiveKeyCleanupRunner.java:42-54`；`JobHeartbeatRegistry.java:614-695`）→ `StaleJobRecoveryRunner(@Order(0))` 再以每页 50 的 keyset `while(true)` 扫 DB 在途作业，逐个判活并条件写 FAILED（`StaleJobRecoveryRunner.java:98-180`）。两者仅启动一次，不是周期任务。

### 3.5 KB、任务 SSE 与健康探测

- KB：`KbService @PostConstruct` → 单线程先异步首刷；之后 10 分钟 refresh + 30 秒缓存版本 GET（`KbService.java:165-189,212-278`）→ `CachedKbSource` → Redis cache-aside / local 文件 / HTTP 源；HTTP 回源用 Redis 锁单飞，成功写缓存并 INCR 版本 → 重建本地 Lucene 快照；`POST /api/ai/kb/reload` 可同步走同一 `synchronized refreshNow`（`AiKbController.java:43-48`）。
- 任务 SSE：`GET /api/tasks/{id}/events`（`TaskController.java:192-195`）→ `TaskSseService.subscribe` → 本机 emitter + Redis pub/sub 首订阅；注册失败进入专用 2 线程调度器的 10/20/40/60 秒有界退避（`TaskSseService.java:361-526`）→ 每次发布本机直投 + `PUBLISH task:events:<id>`；末订阅取消重试并退订。
- AI SSE 非驱动方：`GET /api/ai/events/{runId}` → 回放并 attach → 每写出器 5 秒本地心跳；每 30 秒把归档对账交给有界投递池，读 XRANGE 差量（`SseChatEmitter.java:1024-1159,1162-1228,4128-4180`）→ 终帧/末订阅/转为驱动方即停。
- readiness：`GET /health/ready`（`HealthController.java:63-70`）→ `ReadinessService.check` → 固定 4 线程/队列 8 并行跑 DB、存储、seed、Redis 探针（`ReadinessService.java:130-185`）→ HTTP 线程只等待共享 2 秒期限，超时取消 future 并回 DOWN/503（`ReadinessProbeRunner.java:97-203`）。外部 LB 的调用频率不在仓内配置，不能把它写成固定 QPS。

---

## 四、全部周期调度、真实轮询与定时重试清单

“每实例频率”给的是源码周期；实际频率受同步 I/O、fixed-delay 和线程池排队影响，不等于实测吞吐。`N` 为服务实例数，`R` 为本实例活跃 run/attempt 数，`J` 为本实例活跃 job 数，`A` 为本实例非驱动方且仍挂载的 SSE 写出器数。

### 4.1 周期任务与真实轮询（无遗漏主表）

| 文件:line | 机制与周期/条件 | 每实例频率与后端调用 | 退避/抖动/单飞/生命周期判断 |
|---|---|---|---|
| `backend/src/main/java/com/example/configmgr/job/service/JobWatchdog.java:145-159,200-231` | `@PostConstruct` 即注册，fixed-delay 默认 10 秒 | 每实例约 0.1 拍/s；每拍一次全量在途 DB 查询，逐 RUNNING 远程候选 GET beat，必要时再 GET anchor；判死另有 Redis/DB 写 | 无抖动；只有判死动作跨实例单飞；`scanSafely` 兜 Throwable；`@PreDestroy shutdownNow`。**P-05** |
| `.../job/service/JobHeartbeatRegistry.java:397-446` | Redis 模式且有本地 runner 才懒启动，fixed-delay `beatTtl/3`，默认 30 秒 | 每实例每拍对本地 J 个 job 各两次 SET，即约 `2J/30` 命令/s | 无抖动、无失败整拍短路；runner 全退出和 `@PreDestroy` 都停。**P-06** |
| `.../ai/session/SessionPurgeRetryRunner.java:154-183,198-358` | ApplicationReady 后 10 秒首拍，fixed-delay 30 秒；可由配置只关周期注册 | 每实例每 30 秒抢锁；赢家每拍 ZRANGEBYSCORE + 最多 10 个 GET，再执行删除；输家至少一次 GET | 全局锁单飞、每轮唯一 owner、续租与停滞判死完整；失败按台账指数退避；固定周期无抖动。**P-11** |
| `.../ai/run/StartupResumeRunner.java:1162-1169,497-552` | 出厂开启，fixed-delay 120 秒 | 每实例每 120 秒尝试；持锁者全量 `SMEMBERS` + O(索引) EXISTS + 每活轮多次 GET/HGETALL/XREVRANGE | 正常跨实例单飞，无周期抖动；`@PreDestroy` 停。**P-03** |
| `.../ai/run/StartupResumeRunner.java:254-291,363-378` | 启动一次：8 秒 + 0..5 秒抖动；启动单飞失败最多 3 次，间隔 0.5–1 秒随机 | 每实例一次完整恢复扫描 | 有启动抖动/次数上限；starter 为 daemon，但 `@PreDestroy` 只关运行期 sweep，没有保存/中断 starter；退出依赖 JVM 结束或 sleep 自醒 |
| `.../ai/run/StreamWatchdog.java:156,191-258` | 每活跃 attempt fixed-rate 1 秒 | 每实例 R 次/s，纯内存读，无 Redis/DB | 无可需要的事件替代全部超时判定；`close` 取消；纯判定节拍与 Redis 已隔离 |
| `.../ai/run/StreamWatchdog.java:157-159,279-294` | 生产每活跃 attempt 在 `aiCancelPoll` 上 fixed-rate 1 秒 | 每实例约 R GET/s；跨实例只由实际运行该 attempt 的实例轮询 | 同实例监听立即加速；Redis 失败进入全实例 5 秒读退避；`close` 取消；无启动抖动。**P-07** |
| `.../ai/session/SessionGate.java:730-765` | 每个成功持锁会话 fixed-rate，默认 30 秒 | 每持锁会话每 30 秒一条单键续租 Lua | 无抖动；确定换主即停，异常则下拍再试；释放/`@PreDestroy` 停；每实例单线程，慢命令会串行延后其它锁 |
| `.../ai/run/LeaseService.java:1010-1023,1108-1156,1380-1408` | 每份 driving 租约 fixed-rate，默认 10 秒；自定义 TTL 可覆盖 | 每租约每 10 秒一条续租 Lua；存活判据首拍及之后约每 3 拍执行，可能再读 Redis | 每 `(key,owner)` 去重；2–4 线程；丢租即停，不可用下拍重试；release/shutdown 停，无抖动 |
| `.../ai/run/TurnKeepalive.java:171-209` | 每轮一个 fixed-rate 5 秒任务，压缩/流式阶段均覆盖 | 每轮 0.2 次/s；只序列化并入本机 SSE 队列，无 Redis | 纯观测；终态/异常/park 停止；不需要事件替代“无事件时也要保活”这一功能 |
| `.../ai/run/SseChatEmitter.java:1024-1124` | 每个非驱动方且有订阅者的写出器 fixed-rate 5 秒 | A×0.2 次/s，本机心跳入队；不写 Redis | 单共享调度线程；每写出器 CAS 去重；末订阅/终态/转驱动即停 |
| `.../ai/run/SseChatEmitter.java:521-526,1131-1228` | 复用上述节拍，但每写出器 30 秒才做一次归档对账 | A/30 次/s；每次在投递池按游标读一批 XRANGE，原文口空时还有兼容读 | 每写出器 `archiveReconcileInFlight` 单飞；末订阅停；无抖动；是丢 wake 的必要低频兜底，不宜简单删除 |
| `.../ai/kb/KbService.java:173-184,212-220` | 启用 KB 即首刷一次 + fixed-delay 10 分钟 | 每实例每 10 分钟读源；HTTP 还有 manifest/变更文档请求，local 读盘，Redis 做缓存同步 | 每实例 synchronized refresh 单飞；HTTP 回源另有 Redis 锁；无周期抖动但真实回源间隔有 ±10% 抖动；停机 shutdownNow |
| `.../ai/kb/KbService.java:182-184,235-278` | source 为 CachedKbSource 时 fixed-delay 30 秒 | 每实例每 30 秒一次版本 GET；版本变化才刷新 | 同版本失败最多 3 次，线性退避封顶 10 分钟；单线程与 refresh 串行；无周期抖动。可 Pub/Sub 加速，保留此兜底 |
| `.../ai/session/AiSessionPurgeService.java:911-948` | 删除请求内有活跃 run 时，50 ms 一圈，名义 1.5 秒 | 每请求每圈对仍未终态 run 各一次 GET；不是后台固定流量 | 无抖动/异步单飞；HTTP 调用方独占等待；圈内 I/O 可突破期限。**P-02** |
| `.../ai/gate/ConfirmGate.java:976-1066` | **仅 park=false 的长期等待**，每 2 秒 latch 超时一圈；有即时前端超时会缩短末圈 | 每等待卡每圈一次取消 GET + 一次 pending 读；每 2 秒 SSE 心跳；约每 60 秒续 TTL，按 10 秒续租 | 有 latch 即时唤醒；取消读有全局失败退避，pending 读没有；生产 park=true 不进入长期驻留。**P-08** |
| `.../ai/kb/CachedKbSource.java:219-228` | HTTP 缓存冷启动且别的实例持刷新锁时，30×100 ms | 单刷新最多 30 圈，每圈 Redis manifest GET，版本命中再 GET version | 次数有限但没有含 I/O 的硬期限；无抖动；中断被吞。**P-09** |
| `.../ai/kb/CachedKbSource.java:424-449` | 缓存写窗读比不一致时最多 6 圈、25 ms 间隔 | 单刷新最多 6 次 manifest GET | 同上；不是独立后台线程，运行在当前刷新/管理请求上 |
| `.../job/service/ImportJobRunner.java:164-179`、`ExportJobRunner.java:191-211`、`PublishJobRunner.java:203-242`、`PrecheckJobRunner.java:175-225` | 每 100 行业务分片轮询一次取消键；不是独立调度线程 | 每作业约每批一次 Redis GET；另有 DB/SSE；每批还 sleep 300 ms | 同实例监听加速；Redis 读失败按未取消且无退避；runner finally 清本地状态；事件驱动可替代跨实例轮询但需保留分片兜底 |
| `.../ai/config/AiConfigService.java:154-171,245-280` | 请求驱动的版本检查，最快每 1 秒/实例；不是后台定时器 | 到期一次 Redis GET；不可用/缺键则一次 DB 主键读；版本变或前次失败才全量读配置表 | 实例内 double-check 锁单飞；无后台线程；多实例各自节流。可保留作失效通知丢失后的兜底 |

### 4.2 一次性定时器与失败重试链

| 文件:line | 触发与延迟/上限 | 后端调用、单飞与生命周期 |
|---|---|---|
| `.../task/service/TaskSseService.java:425-526`；配置 `task/config/TaskSseSchedulerConfig.java:55-66` | Redis 订阅失败后 10/20/40/60 秒退避，最多记 5 次失败且总窗 5 分钟；耗尽后只有新帧时每 60 秒门控再试 | 每次 Redis subscribe；`retryPending` 每任务单飞，成功/末订阅取消；专用 2 线程；无抖动 |
| `.../ai/gate/ConfirmGate.java:1554-1601` | park 后即时前端工具到 `expiresAt`（默认 120 秒）排一次到期收敛 | 到点读快照/pending、抢 decision guard、写超时并触发续跑；guard 防多实例双写；返回的取消句柄未保存，已决任务到点才空退，运行期扫描提供进程死亡后的兜底 |
| `.../ai/gate/ConfirmGate.java:2705-2756,2847-2857` | 续跑池饱和/会话忙/取消不可知：2、4、8、16 秒后 30 秒封顶，最多 15 次/外部触发周期 | 每拍读快照并走 ResumeService；计数按本实例身份+run，耗尽发中性未确认帧；无抖动，跨实例不共享计数 |
| `.../ai/gate/ConfirmGate.java:2893-2908` | 续跑租约在别处时 35 秒后只重试一次 | 再抢租/读快照；不再自我续排；无抖动；进程级 scheduler 有 owner 计数与 1 秒停机等待（`:377-495`） |
| `.../ai/run/CancelledRunFinalizer.java:375-491`；默认调度器 `:114-147` | 取消收尾未落定：2、4、8、16 秒后 30 秒封顶，最多 15 次；租约他持时固定 35 秒 | 每拍 `ResumeService.cancelConfirmed`；同实例同 run `inFlight` 单飞；多实例各自重试但 run 租约/终态保护收敛；无抖动；`@PreDestroy` 清位并停默认调度器 |
| `.../ai/run/SseChatEmitter.java:3591-3664` | 离线终态重挂无终帧：3 秒后检查；忙时每 3 秒重排，最多 3 次重排 | 每拍读一次快照并核对本机驱动代次；每订阅 CAS 防重复；失败/代次变化即停；无抖动 |
| `.../common/sse/SseWriteBudget.java:403-473` | 每异步 SSE 写排一个 15 秒一次性超时 | 到点 cancel 写 future 并回调；完成即 cancel timer；静态 scheduler 随共享写池关闭；不是轮询 |
| `.../ai/session/SessionPurgePendingStore.java:510-530` | 没有自己的线程：失败台账 nextAt 为 10 秒指数至 300 秒，最多 10 次，最终退出 due 集合 | 实际执行频率受 30 秒 runner 量化；pending 与 due 跨槽所以用单键脚本+补偿，不引入 Cluster 跨槽 Lua |

### 4.3 立即重试（不睡眠、不是轮询，但防止漏报“重试”）

| 文件:line | 次数/条件与后端调用 |
|---|---|
| `backend/src/main/java/com/example/configmgr/ai/run/RedisFrameStore.java:649-680` | 取号最多 2 次；每次执行同槽 Lua，必要时读 Stream 尾；失败转进程内降级号 |
| `.../ai/run/RedisFrameStore.java:809-882` | 落档最多 3 次，其中未知回包只允许一次重试；每次同 token 同槽 Lua，幂等判重；无 sleep |
| `.../ai/memory/RedisChatMemoryRepository.java:554-622` | 记忆合并 CAS 最多 5 次；每圈读版本/窗口、写 staging、执行四键同槽 Lua；代次变化立即停 |
| `.../ai/run/ResilientChatService.java:4692-4735` | 请求前窗口/压缩写回冲突最多 5 次；每圈可能重做摘要并重读 Redis；不是固定轮询，但慢上游可延长首轮线程占用 |
| `.../ai/web/AiSessionController.java:90-137` | 发号最多 3 次；仅撞号重试，重试前 HTTP 线程随机 sleep 1–4 ms；每次 `SET NX` 语义登记 |
| `.../ai/config/AiRetryDefaults.java:74-163` | Spring Retry 最多 3 次模型调用；仅瞬时故障，0.5/1 秒基数、±25% 抖动，单次封顶 5 秒；`Thread.sleep` 可中断；由 `AiRetryConfig.java:24-27` 注册，`AiProviderRegistry.java:427-434` 注入模型 |
| `.../ai/run/ResilientChatService.java:1489-1513,2258-2290` | 外层每段最多 2 次预算内 attempt；重试间 0.5 秒起指数+±25% 抖动，且受剩余预算；250 ms 切片中每次还读取消 Redis；可中断 |
| `.../ai/run/DispatchRetryBackoff.java:31-37` | 纯计算循环，不触网；被 ConfirmGate/CancelledRunFinalizer 用于上述延迟 |

---

## 五、逐项问题、根因与修复取舍

### P-01 高：删除接口每轮两次无界全键空间 SCAN，Cluster 下同步串行放大

**确定性：代码证实；未做运行计时。**

**证据：**

- 每次 `enumerate` 都调 `stagingKeys`，且 `archiveKeys` 又先调 `scanArchives`：`AiSessionPurgeService.java:637,660-665,739-742,817-822`。
- 两处都使用 `RedisClusterScanSupport.scan(..., 0)`：`AiSessionPurgeService.java:784-785,821-822`。
- `maxKeys<=0` 明确表示不按结果数截断；游标 `while(cursor.hasNext())` 直至节点扫完：`RedisClusterScanSupport.java:49-55,138-147`。
- Cluster 对所有 master 逐个扫描，节点之间与游标之间都串行：`RedisClusterScanSupport.java:98-135`。
- `COUNT 256` 只是单命令提示，不是总上限；Redis 单命令/连接超时是 2 秒：`application.yml:74-84`、`application-cluster.yml:28-36`。

**触发条件：** 键空间大、某 master 慢、SCAN 需要很多游标往返，或并发多个删除请求。即使会话没有任何归档/staging，也先支付扫描成本。

**拓扑/并发影响：** 单实例 Redis 也扫整个当前 DB 键空间，只是少了多 master 串行；Cluster 把每次删除变成“所有 master × 两个模式”的全局扫描。多服务实例并不会分摊单次请求，反而各自增加全集群负载。当前删除在 HTTP 线程同步执行，多线程并发删除可形成多条全扫描时间线。

**根因：** 用全局键空间扫描弥补“归档后缀”和“随机 staging 后缀”的发现面，且没有异步任务、扫描预算或请求级截止时间。

**最佳修复：** 正常路径彻底移除这两次全扫描，而不是只把 COUNT 调大：

1. 为会话归档和 staging 建同身份的精确成员索引；写入时登记，正常 RENAME/删除时摘成员，删除接口只按索引做单键命令。归档已有 `turnNo` 推导可保留为补偿，但应处理 `turnNo` 缺口和旧数据，不可假定它覆盖所有历史异常。
2. 沿用现有 durable pending，把删除主体转为可追踪的后台任务；HTTP 立即返回已受理/进行中状态，后台按 slot 做有界批量处理。若必须保持同步 200 契约，至少在请求内设置端到端 deadline，超时返回现有 `complete=false/deferred`，不能继续删除到无限晚。
3. 老数据的一次性补偿扫描放入低频后台迁移任务，按 master、游标、扫描键数/时长切片，记录可恢复游标，不与用户请求同线程。

**备选与取舍：** 仅给 `maxKeys` 设上限实现最小，但会把部分扫描当完整结果的风险引入删除正确性；若采用，必须显式标记“不完整”并 deferred，不能报 complete。按 slot pipeline/批量 UNLINK 可减少 RTT，却不能消除 SCAN 的全键空间成本。

**修改点/影响面：** `AiSessionPurgeService` 的枚举与删除、`RedisClusterScanSupport` 的调用方、`RedisChatMemoryRepository` staging 写入/清理、RunStore 上下文归档写入、pending 状态与前端进度展示；需保留 Cluster 单键/同槽约束和身份隔离。

### P-02 高：1.5 秒“等终态”不是硬期限，且后续逐项删除也没有总工作量/时间上界

**确定性：代码证实；未做运行计时。**

**证据：**

- 名义 `DEFAULT_ACTIVE_WAIT=1500 ms`、`ACTIVE_POLL_MILLIS=50 ms`：`AiSessionPurgeService.java:139-151`。
- `deadline` 只在 while 入口检查；圈内对每个活跃 run 同步 `RunStore.get`，然后才 sleep：`AiSessionPurgeService.java:915-947`；每次 GET 都可到 Redis 2 秒超时，多个 run 在一圈内串行。
- 等完后还重读所有 run（`:958-980`），逐 run 删除、逐观测键删除（`:1191-1268`）。
- 归档删除每键先 TYPE 再 DEL（`:1309-1363`）；`archivesFromTurnNo` 可推导 0..2000 共 2001 个键（`:745-760`）。附件还含同步文件删除与删后存在性检查（`:1439-1494`）。

**触发条件：** 多活跃/历史 run、多归档、多附件，或任一依赖持续慢。16 个各等待 2 秒的圈内 GET 已可超过 30 秒；这不是说该请求一定如此，而是源码没有阻止它。

**单例/Cluster/多实例/线程：** Cluster 的跨 slot 单键删除是正确但增加 RTT；多实例不会减少单请求工作量；每个 HTTP 删除请求都独占一个容器线程，慢依赖下造成线程堆积。

**根因：** 把 sleep 周期当等待预算，未把 I/O 纳入 deadline；删除接口把可无限增长的工作全部放在同一个同步调用中。

**最佳修复：** 与 P-01 合并为异步、可恢复工作流。活跃 run 在写入取消并尝试一次 `cancelConfirmed` 后，若不能立刻确认终态就直接 deferred，不在 HTTP 内 50 ms 轮询；终态事件/现有重试台账推动下一步。所有循环使用有界页和端到端 deadline，超限保留 pending，绝不把不可知当删除完成。

**备选：** 保留轮询时，至少每轮只检查仍未 settled 的 run、给每轮设置含 I/O 的剩余时间，并在到期后不再发起新 Redis 命令；但同步 Redis API 不能精确取消在飞命令，因此这只能限制新工作，不能承诺硬实时 1.5 秒。

**修改点/影响面：** `AiSessionPurgeService.awaitTerminal/reloadRunStatuses/deleteOrDefer/deleteContext/deleteAttachments`，现有 `SessionPurgeRetryRunner` 可复用；需验证 deferred 后不会提前删发现键。

### P-03 高：恢复扫描的“动作上限”不覆盖发现成本，且每活轮是多次串行 Redis 调用

**确定性：代码证实。**

**证据：**

- 运行期 fixed-delay 120 秒：`StartupResumeRunner.java:1147-1173`。
- 每拍先执行完整 `listRunsForRecovery`，再进入带 `attemptLimit` 的 `runPipeline`：`StartupResumeRunner.java:511-530,581-602`。所以 `scan-max-attempts=20` 不限制发现读取量。
- `allRunRecords` 先 SMEMBERS，再逐成员 EXISTS：`RunStore.java:3163-3189`。
- 每个活 run 还读快照、pending、取消标志（`ResumeService.java:583-607`）；活动锚点还会 GET `ai:activity` + XREVRANGE 尾帧（`RunStore.java:4843-4899`）。运行期接管判定再读租约（`StartupResumeRunner.java:1269-1301`），F2 分支还会再读 pending（`:665-684`）。

**触发条件：** `ai:runs` 有大量仍存活快照（包括 24 小时内的终态）、Cluster RTT 变高、某节点慢。周期 fixed-delay 不会重叠同一实例自己的下一拍，但会把扫描线程长期占住。

**多实例影响：** 正常时 `ai:lock:resume-scan` 让系统扫描跨实例单飞；每个实例仍每 120 秒醒一次抢锁。Redis 不可确认时 `tryStartScan` 明确退回本进程单飞并继续（`StartupResumeRunner.java:937-973`），因此故障时可能 N 实例各扫一次；这是可用性优先的设计，但不能当成全局单飞仍成立。

**根因：** 恢复发现面复用持久 SET 全索引，没有“非终态/下次应检查时间”的窄索引；动作预算与读取预算分离不完整。

**最佳修复：** 新建按记录身份可恢复的 `recovery-due` ZSET/等价窄索引，score 为下次判死/到期时刻；状态推进、终态、取消和删除时同步维护。每拍只 `ZRANGEBYSCORE ... LIMIT` 有界候选，扫描锁保持不变。全索引扫描降为更长周期的对账任务，并加游标/键数/时间预算。多键写若跨槽，沿用现有“单键事实 + 到期成员补偿”而不是做跨槽 Lua。

**备选与取舍：** 保留全扫描但做 pipeline，可降低 RTT 数，不降低总键数；只在 `runPipeline` 加分页无效，因为昂贵发现已先完成。把终态立即从现有 `ai:runs` 删除会改变列表/诊断语义，不应直接复用为修复。

**修改点/影响面：** `RunStore` 索引写入/清理、`ResumeService.discoverRuns/recoveryRun`、`StartupResumeRunner`、删除和终态写路径、恢复报告的候选语义。

### P-04 高：RedisAvailability 健康时每调用 PING，并在实例级锁内串行

**确定性：代码证实。**

**证据：** `POSITIVE_TTL_MS=0`，负缓存 5 秒（`RedisAvailability.java:38-53`）；`isAvailable` 在 double-check 后于 `synchronized(this)` 内执行同步 PING（`:72-100`）。调用面包括删除（`AiSessionPurgeController.java:77`）、chat（`AiController.java:355`）、context、AI 健康与 readiness。

**触发条件：** 任何 AI 请求/readiness；特别是 PING 慢但仍成功时，正缓存始终不生效。Redis 故障转负后，5 秒负缓存能减流，但状态刚翻转时仍会让等待锁的线程先堵到在飞 PING 结束。

**单例/Cluster/多实例/多线程：** 单例和 Cluster 都有同一实例锁；多实例各自一个锁，不共享压力也不共享健康结果。多线程下健康 PING 强制串行，实例吞吐上限受每次 PING 时长约束；这不是可由 2 秒超时推出的“每请求固定 2 秒”，而是慢时形成排队。

**根因：** 为消除正缓存漏检，把每次 PING 与全局同步锁组合，没有用异步单飞或连接事件把调用方与探针解耦。

**最佳修复：** 把探针改成“返回最近状态 + 共享异步单飞刷新”，由独立有界调度/触发器执行 PING；请求不直接占锁等网络。必须有明确的最大状态年龄和失败即快速翻转策略。对强一致前置有要求时，可在状态超龄时允许有限等待，但应使用可超时 future，而不是监视器锁。

**备选与取舍：** 健康态加很短的正缓存（例如百毫秒级）能降低 QPS，却会重新引入“刚坏仍走后续 Redis 操作”的窗口；只去掉 synchronized 可消除排队，但会增加并发 PING。两者都不如异步单飞完整。Cluster 下还应明确 PING 的节点覆盖口径，不能把一个节点响应当作全拓扑所有 master 都健康。

**修改点/影响面：** `RedisAvailability`、AI 端点前置、`RedisReadinessProbe`、`RedisTaskEventBus.redisKnownDown`；需要重新验证快速失败目标与探针恢复延迟。

### P-05 中：JobWatchdog 每实例重复全量扫描，共享压力随实例数线性增长

**确定性：代码证实。**

**证据：** 每实例 `@PostConstruct` 都建调度器并 10 秒 fixed-delay（`JobWatchdog.java:145-159`）；每拍无分页读取全部 PENDING/RUNNING（`:200-231`；`JobRepository.java:57-58`）；远程 RUNNING 每拍读 beat/必要时 anchor（`JobHeartbeatRegistry.java:492-513`）。判死时才使用 `job:deadjudge` 单飞（`JobWatchdog.java:328-348`）。

**触发条件与影响：** N 个实例、I 个在途作业时，共享 DB 每秒约 N/10 次在途查询，Redis 每秒约 N×I/10 次 beat 读（本地 runner 短路会降低部分读）。Cluster 均为单键命令，没有 CROSSSLOT；压力来自重复扫描而非拓扑命令错误。

**根因：** 用每实例看门狗保证接管可用性，但没有让“扫描”本身选主/错峰。

**最佳修复：** 给扫描本体加一个短 TTL、带续租的共享席位，落选者本拍不查 DB；或复用现有基础设施做 leader 扫描。加小幅随机抖动避免启动同拍。保留每 job 判死单飞作为第二层，不可替代。

**备选取舍：** 数据库按 `next_check_at` 做条件 UPDATE 抢占可减少 Redis 锁依赖，但需要新字段/迁移和时钟语义；简单把现有查询改分页会破坏 `retainTracked` 依赖的全量快照（`JobWatchdog.java:200-206,233-254`），必须先重做本地判重回收，不能只加 Pageable。

**修改点/影响面：** `JobWatchdog` 启动/扫描、共享锁配置与指标、判重记忆回收；需验证持锁实例死亡后的接管和 Redis 不可用 fail-closed。

### P-06 中：JobHeartbeatRegistry 单线程逐 job 两次写，慢 Redis 可拖延整拍

**确定性：代码证实；误杀条件需运行时验证。**

**证据：** `tickOnce` 串行遍历 activeRunners，每个先 `renewRunnerActive` 再 `writeSharedBeat`（`JobHeartbeatRegistry.java:397-405`）；两者各自同步 SET 并只各自 catch（`:770-800`）；调度器只有一条线程，默认 30 秒 fixed-delay（`:420-446`）。没有“本拍已知 Redis 慢/失败后跳过余下命令”的短路。

**触发条件：** 活跃 job 多且 Redis 每命令接近 2 秒；整拍耗时随 J 增长。其它实例的看门狗读失败时 fail-closed；如果键已过期而读取又恢复，可能进入失联判死，因此需要专门验证慢恢复边界，不能仅凭静态代码宣布一定误杀。

**拓扑/多实例：** 每个实例只续自己的 job，Cluster 不会天然合并两键。多实例总写入量约 `Σ2J/30`；单线程延迟只影响本实例的续期，但判死发生在其它实例。

**根因：** 每 job 两键独立写、串行执行，没有设施级失败短路或批量 pipeline。

**最佳修复：** 每拍先做一次带健康判断的轻量检查，已知不可用就整拍跳过；将两键写入按 job 做 pipeline，减少等待次数。若要合并成单命令，需给两键增加相同 hash tag 并使用同槽脚本，但这会改键名，必须做双读/迁移评估，不能直接上线换键。

**备选：** 多线程续期能缓解队头阻塞，但增加 Redis 故障期并发压力，且不减少命令数；不是首选。

**修改点/影响面：** `JobHeartbeatRegistry.tickOnce/renewRunnerActive/writeSharedBeat`、键生命周期、JobWatchdog 判死；验证 runner-active 与 beat 的先后和删除闸门不回退。

### P-07 中：活跃 AI attempt 的 1 Hz 取消 GET 是稳态固定成本

**确定性：代码证实。**

**证据：** 每 attempt 两个 fixed-rate 任务（`StreamWatchdog.java:156-159`）；独立取消任务每拍 `cancelState`（`:279-294`），最终 GET `ai:cancel:<identity>:<runId>`（`CancellationRegistry.java:381-399`）。默认运行池 20，故正常满配约 20 GET/s/实例；重试换 attempt 时旧任务 close、新任务开始，不叠加正常残留。失败后有实例级 5 秒退避（`CancellationRegistry.java:381-425`）。

**触发与影响：** 所有活跃轮都有，即使从未取消。单实例 Redis 同样承担；Cluster 按 run 键分散，但命令量不变。多实例只让实际驱动方轮询，因此不是 N 实例对同一轮都 GET；线程池只有 2 条，慢 GET 会推迟同池其它轮的取消发现，虽然已和纯超时判定隔离。

**根因：** Redis key 是唯一跨进程权威态，但只有同进程 listener 加速，没有跨进程取消事件。

**最佳修复：** 新增按 run/身份取消频道的 Pub/Sub 或复用现有消息容器，取消端点在权威 SET 成功后发布；监听方只加速，权威键仍用于重挂/续跑。把 1 Hz GET 降为较低频的权威对账，并保留失败退避与最终取消时效指标。不要让 Pub/Sub 成功替代 SET，因为消息会丢。

**备选取舍：** 简单拉长到 2–5 秒会削减 QPS，却直接增加跨实例停止延迟；自适应退避更适合长轮，但实现和可观测性更复杂。当前若接受默认 20 并发和 1 秒取消时效，可保留，不必为极低流量提前改造。

**修改点/影响面：** `CancellationRegistry` 写入/监听、`StreamWatchdog`、`AiExecutorConfig.aiCancelPoll`、AI health 指标、跨实例续跑与删除取消路径。

### P-08 中（仅 park=false）：ConfirmGate 长期双轮询且与 StreamWatchdog 叠加

**确定性：代码证实；出厂 `application.yml:284-285` 开启 park，因此不是默认长期路径。**

**证据：** `while(true)` 顶部每圈读取消（`ConfirmGate.java:976-991`），锁内再读 pending（`:992-1005`）， latch 最多 2 秒（`:1031`）；过期失败时也等 2 秒再试（`:1017-1026`）。pending 的 lenient 读失败会按空结果降级，下一圈继续，自身没有失败退避。与此同时 StreamWatchdog 的 1 Hz 取消 GET 仍存在。

**触发与影响：** 显式关闭 park，或直接使用代码缺省 `new AiProperties()` 的非 Spring 装配；多人确认/表单长期等待时，每卡约 0.5 次取消 GET + 0.5 次 pending 读/s，并占一个驱动线程。多实例同一轮只由一个等待线程执行，但取消仍可来自其它实例。

**根因：** 用周期读同时承担跨实例取消与决策发现，同进程 latch 只优化本实例。

**最佳修复：** 默认保持 park=true；不需要为废弃阻塞模式新建复杂基础设施。若必须支持 park=false，则给 pending/取消增加跨实例事件通知，周期读降为低频对账，并给 pending 读失败加退避；长期等待不应驻留有限平台线程。

**备选：** 仅延长 2 秒周期可减压，但增加跨实例决策/取消延迟；与 P-07 共用通知设施更合理。

**修改点/影响面：** `ConfirmGate.await`、park 配置、ResumeService 续跑、等待期 TTL/租约、兼容非 Spring 装配的语义。

### P-09 中：KB 等待圈没有把同步 Redis 时间计入名义期限，且中断继续

**确定性：代码证实。**

**证据：** 冷启动 30 圈，每圈 `cache.manifest()` 后 sleep 100 ms（`CachedKbSource.java:219-228`）；写窗 6 圈，每圈 manifest GET 后 sleep 25 ms（`:424-437`）；统一 `sleep` 捕获中断后只恢复标志，不让循环退出（`:483-489`）。因此注释中的“本地等待给 3 秒”只是 sleep 总和，不含可达 2 秒的每次 Redis 调用。

**触发与影响：** HTTP KB 冷启动另一实例持锁、缓存写窗可见性延迟、Redis 慢。当前可跑在唯一 `kb-refresh` 线程或同步 `/reload` HTTP 线程；多实例同时冷启动会扩大等待和 Redis 读量。

**根因：** 次数上限代替时间上限，I/O 不在预算内；中断语义没有传递到循环条件。

**最佳修复：** 使用 `System.nanoTime()` 的总 deadline，每圈先计算剩余预算；到期/中断立即返回可重试错误，并停止发新 Redis 命令。冷启动等待优先改为锁释放/版本变化事件 + 低频兜底，或直接把本实例交给下一刷新拍而不是堵住管理请求。

**备选：** 仅减少圈数会缩短最坏工作量，但仍不是硬时间保证；不改变现有 Redis API 时不能强行中断已发出的同步命令。

**修改点/影响面：** `CachedKbSource.coldStartHttp/fromCache/sleep`、`KbService.refreshNow` 错误语义和 HTTP reload 响应。

### P-10 中：模型主动探测的调用方超时不取消在飞 HTTP，且完整响应先进入内存

**确定性：代码证实。**

**证据：** `/test` 与 `/models/fetch` 最终都到 `AiConnectionProbe.fetchModels`（`AiConfigController.java:136-166`）；`sendAsync` 后 `future.get(timeout)`（`AiConnectionProbe.java:143-160`），TimeoutException 分支只返回 UNREACHABLE，没有 `future.cancel(true)`（`:165-180`）。`BodyHandlers.ofByteArray()` 会先形成完整字节数组，之后 `boundedBody` 才截到 1 MiB（`:156-163,205-224`）。没有重试，这是正确边界。

**触发与影响：** 慢 DNS/代理/不结束响应体的上游。HTTP 调用方按默认 5 秒返回，但异步 HTTP 工作可能继续，重复点击会积累在飞请求；超大响应的 RSS 不受 1 MiB 截断保护。多实例各自积累，无共享限流。

**根因：** 只实现了调用方等待预算，没有管理底层任务生命周期；响应体限制放在读取完成之后。

**最佳修复：** 保存 future 引用并在 timeout/interrupt 时 cancel；将响应改为 `ofInputStream` 或等效流式读取，按实际字节上限和总 deadline 读，超限立即关闭。必要时给每个实例/ provider 加有界并发，避免重复人工点击形成探测风暴。

**备选取错：** `cancel` 对底层网络操作的停止效果需实测，不能宣称取消即断开；若 JDK 实现不能可靠停止，应使用能显式关闭连接的客户端/请求句柄。只加 future cancel 不解决完整响应入内存。

**修改点/影响面：** `AiConnectionProbe`、模型测试与拉取响应语义；不需要改 chat 的 RetryTemplate。

### P-11 低：会话清理在空载时也按实例同拍抢锁

**确定性：代码证实。**

**证据：** 所有实例 ApplicationReady 后 10 秒首拍，之后固定 30 秒（`SessionPurgeRetryRunner.java:154-175`）；每拍先抢锁，输家立即返回，赢家才 `listDue`（`:198-245`）。没有启动/周期抖动。无到期项时仍产生锁竞争。锁本身、续租、候选上限和耗尽退役都完整，因此这不是无限重试问题。

**影响：** N 实例约每 30 秒 N 次抢锁尝试；赢家多空读一次 ZSET。单例/Cluster 都是少量单键命令，Cluster 不会有跨槽问题。压力低，但滚动启动时同拍明显。

**最佳修复：** 首拍与后续拍加小的确定性/随机抖动；可在无锁时先看“是否有到期项”，只有非空才抢锁。后一种方案在有空载时省锁命令，但在有工作时会让每实例多读一次 ZSET，应按指标选择。若改为长持 leader，必须保留失效接管，复杂度未必值得。

**修改点/影响面：** `SessionPurgeRetryRunner.onReady/runOnce`、锁指标与到期发现时延；不得绕开现有 pending 身份恢复和有界次数。

### P-12 低：失败重试链的“单飞”多数是实例内，不应误认为多实例去重

**确定性：代码证实。**

**证据与影响：**

- `CancelledRunFinalizer.inFlight` 是本实例 Set（`CancelledRunFinalizer.java:177-181,281-295`）；多实例可同时各有一条重试链。真正防双终帧的是 run 租约、终态条件和 finalizer monitor，而不是这个 Set。
- `ConfirmGate.dispatchRetries` 也是本实例 Map（`ConfirmGate.java:301-326`）；不同实例的重挂/触发可各有预算，且 2/4/8…链没有抖动。
- Task SSE 的 `retryPending` 只保证本实例同任务一个延迟任务（`TaskSseService.java:467-480`）；多实例有各自浏览器订阅时，本应各自订阅，因此不是错误，只是压力会随“有订阅者的实例×任务”增长。
- 上述操作最终有 decision guard、run 租约或订阅状态机收敛，因此定低严重度；风险是故障期重复读取/延迟任务，不是已证实的双写。

**最佳修复：** 不为低流量单独引入全局重试队列；先加小抖动和指标。取消/续跑若确需跨实例单飞，应把“下次重试时刻 + owner”放到 Redis 台账，并保留终态 fencing；不要为了去重牺牲进程死亡后的接管。

**备选取舍：** 当前有界次数已能限制单实例风暴；多实例上限应按实例数评估。强行全局去重会把“其它实例挂了”变成无人重试，需要额外租约和持久状态。

---

## 六、主动探测与阻塞等待逐项清单

| 文件:line | 等待/探测及期限 | 调用链与判断 |
|---|---|---|
| `backend/src/main/java/com/example/configmgr/ai/config/RedisAvailability.java:72-100` | AI 端点/readiness 主动 PING；健康无缓存、失败 5 秒缓存；命令 2 秒 | 实例级同步锁内真实 Redis；**P-04** |
| `.../ai/run/LeaseService.java:1204-1235,874-899` | `leaseOk()` 每次 PING；设施失败后 authority 自证 PING 以 1 秒 CAS 节流 | 失败时才进入热路径自证；没有后台周期；正常设施健康不额外触网 |
| `.../ai/config/AiConnectionProbe.java:125-187` | 管理端点主动 GET /models，默认 5 秒 future 等待；无重试 | **P-10**；不是后台轮询 |
| `.../ai/kb/HttpKbSource.java:86-110,142-156` | KB manifest/文档同步 `HttpClient.send`，连接默认 5 秒、请求读默认 10 秒 | 由定时刷新/手动 reload 触发；无应用层重试；失败保留旧索引 |
| `.../health/ReadinessProbeRunner.java:97-203` | `/health/ready` 每次并行探测，HTTP 等共享 2 秒；超时 cancel(true) | 调用方有界，native I/O 可能残留；固定 4 线程、队列 8，饱和 DOWN；`completed(future)` 在 `:176-191` 仅对 isDone 调用，无额外等待 |
| `.../health/DbReadinessProbe.java:40-53` | 每次 SELECT 1；查询 2 秒 | PG 取连接配置 5 秒可能超过 runner 2 秒，但 runner 会按期取消等待；不承诺底层连接线程立刻释放 |
| `.../health/StorageReadinessProbe.java:47-68` | 每次 createDirectories/isDirectory/isWritable | 可能不可中断 native I/O；runner 只保证 HTTP 返回有界；报告不应含本机路径 |
| `.../health/SeedReadinessProbe.java:33-36` | 仅读内存 SeedStatus | 无外部调用 |
| `.../common/sse/SseWriteBudget.java:337-393` | 同步写 API 的 `future.get(write-timeout)`，默认 15 秒 | 任务 SSE 使用这条；超时 cancel，慢 socket 未必可中断；不是轮询 |
| `.../common/sse/SseWriteBudget.java:403-473` | AI 异步写改一次性 15 秒定时器 | 调用线程不等 future；生命周期随共享写池关闭 |
| `.../ai/gate/SpToolCallingManager.java:2991-3030` | 后端工具提交独立池并 future.get，默认 120 秒 | 超时 cancel，但不可中断工具仍可在后台跑；不是轮询；必须保留这一边界 |
| `.../ai/context/ContextSummaryClient.java:144-181` | 摘要独立池 future.get，默认 60 秒 | 超时/中断 cancel；与上游模型 RetryTemplate 的 0.5/1 秒退避可叠加，但有界 |
| `.../ai/run/ResilientChatService.java:2334-2360` | 等上游 CompletableFuture；每次 get 至少 1 秒，圈外复查段预算 | 不是 Redis 轮询；人工挂起状态会暂停段预算；正常 300 秒段上限 |
| `.../ai/run/ResilientChatService.java:2258-2290` | 重试退避每 250 ms sleep，并在每圈读取消 | 默认只剩一次重试，0.5 秒级；每次取消 GET 可长于切片，属于线程占用而非后台固定流量 |
| `.../ai/gate/ConfirmGate.java:976-1066,3072-3083` | CountDownLatch 2 秒切片；取消/决策 signal 可立即唤醒 | park=false 才有长期等待；见 P-08 |
| `.../ai/run/StartupResumeRunner.java:1000-1028` | 进程内扫描锁 tryLock 0 或最多 1.5 秒；抢 Redis 锁失败间 sleep 100–200 ms | 单次 acquire 自身可达 Redis 超时，1.5 秒不是含 I/O 的硬上界；运行期传 0，不等待 |
| `.../ai/web/AiSessionController.java:90-137` | 发号撞号重试前 sleep 1–4 ms，最多 3 次 | 有抖动且可中断；HTTP 短占用，不判为问题 |
| `.../job/service/ImportJobRunner.java:169`、`ExportJobRunner.java:200`、`PublishJobRunner.java:230`、`PrecheckJobRunner.java:214` | 每批默认 300 ms 装饰性 sleep | 业务 runner 线程；不是状态轮询，但附近有每批取消 GET；大批量会延长作业时间，这是显式配置行为 |
| `.../health/ShutdownDrainGate.java:60-86` | ContextClosedEvent 后每 100 ms 睡到 drain deadline | 单实例默认 0；cluster/postgresql/h2tcp 为 5 秒；可中断、上界 60 秒；设计正确，不是后台轮询 |
| `.../common/sse/SseWriteBudget.java:280-299`、`.../ai/run/RedisFrameWakeBus.java:379-394`、`.../ai/run/SseChatEmitter.java:862-877`、`.../ai/gate/ConfirmGate.java:402-418`、`.../ai/gate/SpToolCallingManager.java:302` | bean/静态资源关闭时 awaitTermination，分别约 2 秒/2 秒/1–2 秒/1 秒/2 秒 | 只停机发生，有界；不属于运行期轮询 |
| `.../ai/kb/KbSampleSeeder.java:108-134` | 首次播种 `FileChannel.tryLock` | 非阻塞，无等待/重试 |
| `.../ai/run/RedisFrameWakeBus.java:73-89` | RedisMessageListenerContainer 的订阅失败恢复由框架负责；本类注释记录默认 5 秒恢复 sleep，并为其独立订阅池 | 这是框架间接边界，不是本仓显式调度命中；本次未读依赖源码/实测，故不把默认数值提升为代码事实。重连属于 SSE/订阅边界，不是业务状态轮询 |

---

## 七、全部 `while` 命中分类附录

下表是 `backend/src/main/java` 的全部源码 `while` 命中。它用于证明没有把普通循环漏成“未知”，也避免把有限字符串/集合处理误报成轮询。未在“周期/等待”主表出现的项均为单次请求/任务内的计算或游标遍历，没有独立周期线程。

### 7.1 真轮询、等待、分页或后台循环

| 文件:line | 分类 |
|---|---|
| `backend/src/main/java/com/example/configmgr/health/ShutdownDrainGate.java:70` | 停机 drain 的有界 100 ms 等待循环 |
| `.../job/service/StaleJobRecoveryRunner.java:103` | 启动一次 DB keyset 分页，每页 50；空页退出，不是常驻轮询 |
| `.../ai/gate/ConfirmGate.java:976` | park=false 的 2 秒 latch + Redis 轮询 |
| `.../ai/memory/RedisChatMemoryRepository.java:399,421` | SPI 会话枚举 SCAN 游标；Cluster 逐 master；主代码无其它调用点，属诊断/SPI 边界，不是周期任务 |
| `.../ai/session/RedisClusterScanSupport.java:112,139` | 逐 master 与 SCAN 游标；删除接口两次无界调用，见 P-01 |
| `.../ai/session/AiSessionPurgeService.java:917` | 删除内 50 ms 活跃 run 轮询；见 P-02 |
| `.../ai/session/AiSessionPurgeService.java:1510` | 异常 cause 链遍历，非轮询 |
| `.../ai/kb/KbService.java:587` | Lucene token 流遍历，非轮询 |
| `.../ai/kb/KbService.java:885` | 热切换快照引用 CAS 重读；stopped/null/成功退出，非定时等待 |
| `.../job/service/JobHeartbeatRegistry.java:684` | 启动清理 SCAN 游标，最多检视 512 键 |
| `.../ai/run/ResilientChatService.java:1489` | 有 maxAttempts、空回复/纠正预算和终态出口的模型尝试循环 |
| `.../ai/run/ResilientChatService.java:2270` | 250 ms 可中断重试退避循环，含取消 GET |
| `.../ai/run/ResilientChatService.java:2337` | CompletableFuture 超时切片 + 段预算循环 |
| `.../ai/run/ResilientChatService.java:5316` | 异常 cause 链寻找 WebClientResponseException，非轮询 |
| `.../ai/run/RunStore.java:2650,2733` | Jackson 流式字段游标，非轮询 |
| `.../ai/run/SseChatEmitter.java:2077,2107` | 一页回放暂存的帧数/字节有界入队；后者只用于一页降级收口 |
| `.../ai/run/SseChatEmitter.java:2327` | 归档探测 COUNT 分页，受 ARCHIVE_SCAN_BUDGET 限制，非周期轮询 |
| `.../ai/run/SseChatEmitter.java:4513` | SSE drain 每拍 64 帧/25 ms、回放最多 4 页；异步写出时立即交还 |
| `.../ai/run/SseChatEmitter.java:4684,5013` | drain/complete 的 CAS 交接重试，无 sleep、无外部状态轮询 |
| `.../ai/run/StartupResumeRunner.java:931` | 启动/手工扫描租约的有界等待；运行期不等待 |
| `.../ai/run/TurnKeepalive.java:124` | 阶段 AtomicReference CAS 更新，成功或同相即退出，非轮询 |

### 7.2 有限计算、字符串、集合、文件或结果集遍历（均非轮询）

| 文件:line | 实际工作 |
|---|---|
| `backend/src/main/java/db/migration/V6__Task_child_fk.java:110` | JDBC 外键元数据 ResultSet 遍历 |
| `.../definition/service/DependencyResolver.java:89` | 拓扑排序队列 |
| `.../ai/config/AiEndpointGuard.java:127` | 去 URL 尾斜杠，长度递减 |
| `.../task/controller/FileController.java:190` | ZIP entry 遍历 |
| `.../excel/ExcelTemplateBuilder.java:155` | 数字转 Excel 列名，数值递减 |
| `.../ai/context/SystemMessages.java:36` | 连续 SYSTEM 消息计数 |
| `.../ai/context/MemoryWindow.java:227` | 按轮裁历史，cut 单调且有边界 |
| `.../ai/context/RollingSummarizer.java:222` | 递归合并摘要；partials 减少或 mergeGroups 不缩小时退出；每次合并是真实模型调用，不是轮询 |
| `.../ai/memory/RunMemoryMerge.java:304` | tool 配对裁剪下标 |
| `.../ai/gate/RunContextRefresher.java:486` | 文本数字解析 |
| `.../ai/kb/HttpKbSource.java:198` | 去 base URL 尾斜杠 |
| `.../ai/form/GenerativeFormRules.java:1685` | issue 队列去重 |
| `.../ai/resultview/ResultViews.java:170` | 行数二分 |
| `.../ai/slot/TaskSlotParser.java:344,385` | 正则匹配遍历 |
| `.../ai/tool/PromptDelimiters.java:87` | 正则匹配遍历 |
| `.../ai/tool/FrontendToolGuard.java:178` | JSON 前导空白跳过 |
| `.../ai/slot/ConditionAssessment.java:212` | JSON 字段迭代 |
| `.../ai/tool/ContextBuilder.java:337` | 按字符预算构造字符串 |
| `.../file/FileStorageService.java:160` | 文件摘要流读取 |
| `.../ai/kb/MarkdownChunker.java:83,161,228,370,377,392,433` | Markdown 行/栈/长度/标题遍历；下标或长度均推进 |
| `.../ai/tool/ToolSchemaNormalizer.java:89,119,144` | JSON Schema 字段迭代 |
| `.../ai/tool/ToolRegistry.java:88` | 启动期代理类父级遍历；到非代理类或空父级即退出，非轮询 |
| `.../ai/run/FrameSizeLimiter.java:224,425` | 帧分片 offset 推进、字节二分 |

---

## 八、事件驱动替代总评

| 路径 | 是否可事件替代 | 必须保留的兜底/取舍 |
|---|---|---|
| AI 跨实例取消 | **可以替代大部分 1 Hz 稳态 GET**：权威 SET 后 Pub/Sub | 消息可丢；重挂/续跑必须读 key；保留低频对账和失败退避，不能删权威态 |
| park=false 的确认/前端决策 | 可以，用决策事件 + latch | 默认生产已 park，优先不扩展旧阻塞模型；低频读仍用于丢消息/重启 |
| 会话删除等终态 | 可以，终态事件驱动后续删除；最佳是后台任务 | 现有 pending/deferred 必须保留，进程死亡后由 runner 接管；不能靠一次通知保证删除完成 |
| 恢复死轮扫描 | **不宜只靠事件**；进程本来就可能死在无法发事件处 | 用窄 due 索引替代全索引，保留低频对账、单飞、僵尸窗口 |
| JobWatchdog | 不宜只靠事件；它是失联检测本体 | 可减少重复扫描、加选主/分页改造；TTL 与判死单飞仍需 |
| Job/Session/Run 租约续期 | 不能用业务事件替代 TTL 续期 | 可 pipeline/合并同槽命令、失败短路；释放与过期接管必须保留 |
| KB 版本发现 | Pub/Sub 可替代大部分 30 秒版本 GET | 每实例仍需建本地 Lucene；保留 10 分钟源刷新与低频版本对账 |
| SSE 心跳 | 不能用“有业务事件才发”替代；其目的就是静默期保活 | 保留 5 秒节拍和停止条件；归档对账 30 秒可保留，Pub/Sub 仅加速 |
| readiness | 外部 LB 探测本质不可由内部事件取代 | 可缓存极短状态/异步探测，但摘流需要新鲜结果；调用方有界不等于底层 I/O 停止 |

---

## 九、后续验证方案（仅设计，未执行）

### 9.1 删除接口与 Cluster

1. 在真实三主 Cluster 构造可控键空间与多会话数据，分别覆盖空会话、单 run、多 run、2000 归档边界、staging 残留、附件和活跃 run；记录 DELETE 各阶段耗时、每 master SCAN 次数、命令数、HTTP 线程占用与 complete/deferred。
2. 对 SCAN 和 GET 注入可中断的延迟/失败，验证当前超 30 秒路径能被观测；修复后验证到达端到端期限即不再发起新工作，pending 仍存在且后续可恢复。
3. 验证删除期间跨实例写回、RENAME、终态与取消竞态；检查 pending/guard/发现键不顺序倒置，Cluster 无 CROSSSLOT，无误删其它身份。
4. 对异步化方案验证 HTTP 受理响应、任务状态查询、重试耗尽、手动 DELETE 重开、60 秒记忆复核与进程死亡接管。

### 9.2 恢复扫描与作业

1. 构造大 `ai:runs`（含未过期终态）和慢 Cluster，分别记录发现阶段与动作阶段命令数；证明修复后的每拍读取量由候选上限约束，而非仅动作数受限。
2. N 实例同时启动/运行，统计扫描锁输赢、DB 查询和 Redis QPS；在持锁实例死亡、Redis 不可确认、恢复三种情况下验证不会长期双扫或永久不扫。
3. 对 JobHeartbeat 注入慢写，构造足够多 active job，验证整拍超时/失败短路、TTL 到期、其它实例判死之间没有误杀；检查删除闸门仍 fail-closed。
4. 若给 JobWatchdog 加分页，必须额外验证 judgedJobIds 回收语义已替换，不允许沿用“本拍结果就是全量在途”的旧假设。

### 9.3 取消、等待、KB 与主动探测

1. 同时运行默认上限的 AI 轮次，记录 cancel poll QPS、调度延迟和跨实例停止时延；改 Pub/Sub 后人为丢消息，验证低频权威对账仍能取消，续跑仍拒绝已取消轮。
2. 分别验证 park=true/false；park=false 下核对 ConfirmGate 与 StreamWatchdog 是否重复读、pending 读故障是否被退避、线程是否按设计驻留。
3. KB 冷启动/写窗注入每命令 2 秒延迟和中断，记录真实总等待；修复后应到期/中断即退出，旧索引保持，下一拍可恢复。
4. `/providers/{id}/test` 使用慢响应、不结束响应和超大 body，验证调用方超时后底层连接/任务确实收敛，RSS 有流式上限；不能只断言 HTTP 已返回。
5. 对 RedisAvailability 做并发慢 PING，记录锁等待、实例吞吐和负缓存恢复；异步单飞方案需验证故障翻转速度与“刚坏仍命中正缓存”的取舍。

### 9.4 生命周期与多实例通用

- 检查每个 fixed-rate/fixed-delay 任务在终态、末订阅、release、ContextClosedEvent 后都停止；统计调度线程和延迟队列无残留。
- 对同一 run/任务同时从多个实例触发重试，确认有界次数是“每实例”还是“全局”的口径被明确记录；最终只能有一个业务 winner。
- 所有周期测试都同时测 standalone 与 Cluster；Cluster 额外记录逐 master 调用，不把单节点 SCAN 结果当全局完整。
- 不在验证中把框架订阅重连次数算作业务轮询；分别测 Pub/Sub 丢帧、重连与真正的权威状态补偿。

---

## 十、未发现问题的关键路径与正面证据

以下“未发现问题”仅限本次轮询/生命周期主题，不代表全模块绝对无缺陷，也不代表做过运行验证。

1. **调度注解面干净：** 没有 `@Scheduled`/`@EnableScheduling`；不会因 Spring 默认调度器再出现一组未登记周期任务。
2. **AI 超时判定与取消 Redis I/O 已拆池：** `aiStreamWatchdog` 的 1 秒判定只做内存读，取消 GET 在独立两线程池，避免 Redis 慢直接冻结所有首包/静默/段预算判定（`AiExecutorConfig.java:116-177`）。
3. **TurnKeepalive 和 attach keepalive 停止条件完整：** 终态、异常、park、末订阅和驱动方切换均有取消路径；attach 对账另有每写出器在飞单飞，不是每连接一个线程。
4. **默认生产 park 真正交还人工等待线程：** 等待状态落 Redis，决策/回灌走续跑，不是 LockSupport 空挂；即时前端工具还有到期定时器和运行期扫描兜底。
5. **会话清理重试不是无限循环：** 30 秒扫描每轮最多 10 候选，每会话最多 10 次，指数退避有 300 秒封顶，耗尽退出 due 发现面；跨实例锁带续租与候选停滞判死。
6. **SessionGate/LeaseService 的续租与释放使用单键或同槽 Lua：** 未发现这些周期续租引入 Cluster CROSSSLOT；release 会停对应任务，默认租约调度池有 remove-on-cancel。
7. **Task SSE 注册重试已从永久自续改为有界链：** 有专用调度器、每任务排期去重、末订阅取消、次数与总窗上限；Redis 总线本身是事件驱动，不应误报成轮询。
8. **SSE 回放不是定时全表轮询：** 采用游标 + COUNT 分页，drain 每拍有帧数/时间/页数预算；30 秒归档对账只在非驱动方且有订阅者时存在。
9. **Readiness 调用方等待有共享总期限：** 固定线程与队列、饱和即 DOWN，没有无界创建探测线程；`/health/live` 不触外部依赖。仍需牢记不可中断 I/O 的底层残留边界。
10. **模型与流式重试均有上限：** Spring Retry 仅瞬时故障、3 次、指数+抖动且可中断；外层流式重试受 attempt 与段预算限制；没有“永久立即重试”的模型循环。
11. **启动 runner-active 清理不是全历史逐 ID 探测：** 使用 SCAN、Cluster 逐 master、最多 512 键，并以按值条件 DEL 防误删；StaleJobRecoveryRunner 使用 keyset 每页 50，不是常驻 while  polling。
12. **普通 `while` 大多有明确推进条件：** 字符串下标、正则游标、集合队列、文件流、JSON 游标、二分与 CAS 均有退出/推进；除本报告点名项外，没有把普通计算误判为 Redis/DB 轮询。

---

## 十一、审查边界

- 本报告只依据静态生产源码和配置；没有证明线上一定发生超 30 秒，也没有给出未经测量的 QPS/容量合格结论。
- SSE 前端重连和快照轮询不在本次 `backend/src/main` 范围内，因此只作为后端注释中的边界提及，没有把前端实现当作已核验事实。
- RedisMessageListenerContainer 的内部恢复细节、JDK HttpClient 取消是否真正停止网络 I/O、数据库/native 文件调用的中断效果，都需要依赖源码或运行验证；报告未把这些推测写成已证实行为。
- 未修改任何生产/测试代码或其它文档；本文件是唯一写入物。
