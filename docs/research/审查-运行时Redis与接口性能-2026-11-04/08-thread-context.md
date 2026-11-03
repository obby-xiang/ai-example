# 08 — 线程、异步执行与请求上下文传播审查

## 1. 审查结论与证据边界

- 基线：`52b093642402c9455b7cb9627fae6dbdd6544e54`（用户指定基线 `52b09364` 的完整形式）。
- 审查方式：只读生产源码与生产配置；使用全量模式检索盘点入口，再逐段阅读实现。未修改生产/测试代码，未运行测试、构建、CI、服务或压测，未执行任何 Git 写操作。
- 本报告覆盖后端生产代码中的 `Executor`、`ThreadPoolTaskExecutor`、`CompletableFuture`、`@Async`、直接 `Thread`、定时线程、Reactor 回调、Redis Pub/Sub 回调、异步 Servlet/SSE，以及身份、用户、locale、`runId`、`sessionId`、MDC 的传播和清理。
- 严重度：**高** = 可造成整机/整机池可用性下降、OOM 或明显的请求超时放大；**中** = 需要特定并发/故障时序，或造成后台任务、语言、日志与内存持续异常；**低** = 主要影响可观测性或后续维护。
- 确定性：“代码证实”只表示静态代码可以证明所述机制；未做运行测量，不把条件性后果写成线上已发生事实。
- 总结：主 AI 链的租户/用户传播已有较强的“提交时 TTL 捕获 + 执行时记录身份覆盖 + 作用域恢复”防护，未发现主链必然串租户的证据。但仍有 **3 项高严重度可用性/内存风险**，以及定时重试 locale 丢失、MDC 断链、中断后继续重试、永久墓碑增长等问题。
- 对“删除对话接口在 Redis Cluster 下超过 30 秒”的本专项结论：删除主体在请求线程同步执行，活跃轮等待本身只有 1.5 秒；静态代码不能证明现网超时的全部原因。`TC-02` 给出了一个可代码推导的排队形态：若 PING 持续接近 2 秒，16 个同实例请求经过同一监视器的累计尾部等待可达约 32 秒。这是**条件性容量推导，不是实测结论**，不能替代删除接口的完整 Redis 扫描/命令链分析。

## 2. 审查范围与实际阅读文件

### 2.1 全量盘点方法

对 `backend/src/main/java` 检索了执行器创建、`execute/submit`、定时注册、`Future.get`、线程中断、`@Async/@Scheduled`、直接线程、Servlet 异步类型、`ThreadLocal/TTL/MDC/LocaleContextHolder`、身份作用域和生命周期回调。生产代码没有 `@Scheduled`/`@EnableScheduling`；唯一 `@Async` 业务入口是 `AsyncJobExecutor`。`spike`、文档探针和测试源码不属于本报告生产证据面。

### 2.2 实际阅读的生产文件

以下文件均至少阅读了与线程、上下文、调用链相关的实现段；超长类按入口、提交点、回调和清理出口分段阅读，不宣称无关业务分支逐行读完。

- 线程与 Web 配置：`ConfigMgrApplication.java`；`config/AsyncConfig.java`、`WebConfig.java`、`WebI18nConfig.java`、`FlywayConnectionPin.java`、`FlywayStartupLock.java`、`FlywayStartupLockConfig.java`；`ai/exec/AiExecutorConfig.java`；`task/config/TaskEventDispatchConfig.java`、`TaskSseSchedulerConfig.java`；`health/ReadinessRunnerConfig.java`。
- 上下文与日志：`ai/tenant/` 下全部 9 个文件（`RequestContextUtils`、`RequestContextTaskDecorator`、`RunIdentityScope`、`TenantIdentityFilter`、`TenantKeyScope`、`RunKeyScope`、`LocalRunKey`、`TenantUser`、`TenantUserDirectory`）；`ai/observe/AiTrace.java`、`AiMdcTaskDecorator.java`；`ai/run/ReplyLanguage.java`；`common/ErrorBodyFactory.java`。
- AI 入口与运行链：`ai/web/AiController.java`、`AiSessionController.java`、`AiConfigController.java`、`AiSessionPurgeController.java`；`ai/run/ResilientChatService.java`、`ResumeService.java`、`StartupResumeRunner.java`、`RunRegistry.java`、`RunStore.java`、`LeaseService.java`、`CancellationRegistry.java`、`CancelledRunFinalizer.java`、`StreamWatchdog.java`、`TurnKeepalive.java`。
- AI 工具与回调：`ai/gate/ConfirmGate.java`、`SpToolCallingManager.java`、`ToolExecutionEnvelope.java`；`ai/context/ContextConfig.java`、`ContextSummaryClient.java`；`ai/memory/IdentityBoundChatMemoryAdvisor.java`；`ai/config/AiModelConfig.java`、`AiProperties.java`、`RedisAvailability.java`、`AiConnectionProbe.java`、`AiModelCatalog.java`。
- SSE 与 Redis 回调：`common/sse/SseWriteBudget.java`；`ai/run/SseChatEmitter.java`、`RedisFrameWakeBus.java`、`FrameWakeListener.java`；`task/service/TaskSseService.java`、`RedisTaskEventBus.java`；`task/controller/TaskController.java`。
- 作业与定时清理：`job/service/AsyncJobExecutor.java`、`JobService.java`、`JobWatchdog.java`、`JobHeartbeatRegistry.java`；`ai/session/AiSessionPurgeService.java`、`SessionPurgeRetryRunner.java`、`SessionGate.java`、`SessionPurgeDatabase.java`；`ai/kb/KbService.java`、`CachedKbSource.java`。
- 健康与关闭：`health/HealthController.java`、`ReadinessService.java`、`ReadinessProbeRunner.java`、`ShutdownDrainGate.java`。
- 配置与依赖：`backend/pom.xml`；`resources/application.yml`、`application-cluster.yml`、`application-postgresql.yml`。

## 3. 执行器、线程与生命周期总表

| 执行体 | 形状与拒绝/等待 | 上下文与清理结论 |
|---|---|---|
| `applicationTaskExecutor` | core/max=4/8，队列 200，`AbortPolicy`；停机等待 30s | `RequestContextTaskDecorator` 捕获 TTL；`AsyncJobExecutor.finally` 清 runner/取消登记。手工 `initialize()` 与容器初始化发生在任务提交前，未发现重复存活池证据。 |
| `aiRunExecutor` | 默认 20/20，`SynchronousQueue` 语义，满即 503；停机等待 10s | MDC + TTL 装饰；控制器/续跑任务显式设置并清理 locale。 |
| `aiResumeTrigger` | 默认 2/2，队列 16，`AbortPolicy`；停机等待 10s | MDC + TTL；执行体再按快照身份覆盖。饱和由确认门转为有界重试/明确结局。 |
| `aiStreamWatchdog` | 2 条守护线程；周期任务 | TTL 包装，看门狗每拍另开记录身份；无 MDC 传播，见 `TC-07`。 |
| `aiCancelPoll` | 2 条守护线程，每轮 1Hz Redis GET | 与判定线程分离，避免慢 GET 卡死全部判定；`close()` 取消每轮任务。 |
| 摘要池 `ctx-summary` | 4 线程、队列 8、空闲回收，`AbortPolicy`；60s 父等待 | MDC + 记录身份；`close()` 关闭。超时只保证调用方退出，不保证不可中断 I/O 停止。 |
| 工具超时池 `ai-tool-timeout` | 0..默认 20，`SynchronousQueue`，满即拒绝 | MDC + 记录身份；静态池按容量共享且有 owner 计数。`cancel(true)` 不是强制停止。 |
| SSE `sse-delivery` | 默认 8 线程，池队列=`8×64`，`AbortPolicy` | 提交点用 `AiTrace.wrap`，执行体按记录身份；池拒绝摘除当前订阅者。 |
| SSE `sse-write` | 0..64，`SynchronousQueue`，守护线程，饱和即拒绝 | AI `send` 内有记录身份；写池本身不传 MDC/TTL，见 `TC-07/08`。 |
| SSE `sse-write-timeout` | 单守护调度线程 | 无身份/MDC 清理；取消任务未立即出队，见 `TC-05/08`。 |
| attach 保活/终帧宽限 | 单守护调度线程，每写出器至多一个保活任务 | 工厂启动时显式 `denied()`；保活包装 MDC；末订阅、终态和停机均停表。 |
| `LeaseService` 续租 | 2..4 条守护线程；`removeOnCancel=true` | 每拍按租约携带身份；`stopDriving/release/PreDestroy` 有明确出口。 |
| `SessionGate` 续期 | 单守护调度线程 | holder 固定锁键与身份；正常 holder 每拍恢复身份；释放/停机取消。 |
| 确认门延迟重试 | 单守护调度线程，按代与 owner 管理 | 到期、租约重试和重派发均按记录身份；locale 未随任务保存，见 `TC-06`。 |
| 取消收尾重试 | 单守护调度线程，按 owner 管理 | 身份随交棒保存；有次数上限和 `inFlight` 清理。 |
| 任务事件派发池 | core/max=2/4，队列 256；满则丢状态投影帧 | 无需 AI 租户身份；帧丢失由 DB 快照轮询兜底。任务 SSE 同步写放大见 `TC-01`。 |
| 任务订阅池 | core/max=1/2，队列 64；满则调用线程内联 | 不丢 subscribe/unsubscribe；代价是饱和时占用容器调用线程。 |
| `taskEventScheduler` | 2 条守护线程；`removeOnCancel=true` | TTL 包装且重试体 `denied()`；次数/时长有界，末订阅取消。 |
| Redis wake 派发池 | core/max=2/8，队列 1024；满则内联 | 监听器捕获订阅身份，回调再开作用域；饱和不丢唤醒，但会阻塞 Redis 调用线程。 |
| Redis wake 订阅池 | core/max=1/2，队列 64；满则内联 | 与慢派发隔离；停机有界等待 2s。 |
| KB 刷新/版本轮询 | 单守护线程，固定延迟 | 全局知识库，无请求身份需求；`PreDestroy` 停止，周期异常不杀后续节拍。 |
| 作业心跳 ticker | 按需单守护线程，无活跃 runner 即停 | 工厂与每拍都 `denied()`，避免首个提交者身份长期残留。 |
| 作业看门狗 | 单守护线程，默认 10s | 全局系统任务；异常兜底，状态集合按在途 DB 快照回收。 |
| 启动恢复直接线程 | 单次 daemon `Thread`，启动延迟+抖动 | 由 `ApplicationReadyEvent` 启动，非 HTTP 身份源；逐轮按记录身份恢复。 |
| 运行期恢复扫描 | 单守护线程，默认 120s | 有跨实例单飞、单拍动作预算、逐轮记录身份和 `finally` 清理。 |
| 会话销毁重试 | 单守护线程，30s | 每条 pending 按台账身份；跨实例锁有续租/停滞判据；异常不终止后续周期。 |
| readiness 探针池 | 默认 4 线程、队列 8，全部探针共享 2s 截止 | 饱和/超时记 DOWN；只保证 HTTP 调用方有界，不能杀死不可中断 native I/O。 |
| JDK `HttpClient` 异步探测 | 复用 client，请求线程 `future.get(timeout)` | 超时未显式取消、响应体先完整收内存，见 `TC-03/04`。 |

## 4. 完整入口 → 服务 → Redis/DB/线程 → 返回调用链

### 4.1 首轮 `POST /api/ai/chat`

1. `WebConfig` 注册 `TenantIdentityFilter`；过滤器校验 `X-Tenant-Id/X-User-Id`，在请求线程打开 `RequestContextUtils` 作用域，并在 `finally` 恢复/清空（`TenantIdentityFilter.java:116-150`）。
2. `AiController.chat` 完成输入校验、`RedisAvailability.isAvailable()` PING、`SessionGate.acquire`、clientRequest 绑定；创建无传输死线的 `SseEmitter`，通过 `RunRegistry.of` 取得 `(身份,runId)` 对应的写出器并挂接（`AiController.java:329-625`）。
3. HTTP 线程绑定 `AiTrace(runId,sessionId)`、捕获 `replyLocale`，向 `aiRunExecutor` 提交。装饰器顺序是 MDC 快照与 TTL 快照；工作线程设置 locale（`AiController.java:544-623`；`AiExecutorConfig.java:93-113,209-219`）。
4. `ResilientChatService.chat` 访问会话、记忆和 `RunStore` Redis；需要 L2 摘要时由 `ContextSummaryClient` 向四线程摘要池提交并限时等待；随后创建快照、取得租约并进入 `runAttempts`（`ResilientChatService.java:681-953`）。
5. 模型流由 Reactor 上游线程回调；每次消费按驱动线程捕获的身份开作用域。工具执行经 `SpToolCallingManager`，需要独立超时时再进入 `ai-tool-timeout` 池；执行信封携带身份、run/session 与冻结参数（`ResilientChatService.java:1460-1555`；`SpToolCallingManager.java:784-849,2991-3030`）。
6. 出帧经 `SseChatEmitter`：记录身份解析 → `RunStore`/Redis Stream 原子取号归档 → 本地订阅队列 → Redis wake Pub/Sub；`sse-delivery` 串行 drain，再把实际 socket 写交给 `sse-write`，完成/超时回调续跑或摘除（`SseChatEmitter.java:3280-3532,4445-4643`）。
7. 返回路径：控制器先返回 `ResponseEntity.ok(emitter)`；工作线程最终释放会话门、关闭注册表条目、复位 locale；主服务停看门狗/保活、注销取消监听并清 MDC。过滤器初始请求结束时清理请求线程身份，后续容器/池线程不依赖它继续存在。

### 4.2 决策、回灌与续跑

1. `POST /api/ai/confirm` 或 `/frontend-tool-result` 经过身份过滤器，读/写 `RunStore` pending 与决策 guard；`ConfirmGate` 落 Redis 决策、发回执帧并尝试唤醒本进程 latch（`AiController.java:851-1065`）。
2. 生产 `app.ai.park.enabled=true` 时，原驱动在请求帧后排定必要到期任务并 park，交还运行线程；否则旧式等待循环按 2 秒切片读 pending/取消状态（`ConfirmGate.java:920-1092`）。
3. 无本进程等待方时，`ConfirmGate` 抢续跑租约并交给 `aiResumeTrigger`；执行体按快照身份打开作用域、设置 locale，再进入 `ResumeService`（`ConfirmGate.java:2578-2645`）。
4. `ResumeService` 读快照/pending、校验取消与租约、取会话门、重建历史并可能进入摘要池；随后向 `aiRunExecutor` 交棒真正的 `driveResumed`（`ResumeService.java:756-935,1102-1200,1566-1839`）。
5. 驱动仍走第 4.1 节的 Reactor、工具、Redis 帧归档与 SSE 写链；任务结束清 locale、互斥、会话门、注册表和 MDC。HTTP 决策接口在触发派发后直接返回 accepted/outcome，不等待模型终态。
6. 池饱和、会话忙或租约在别处时进入确认门的有界延迟重试；启动/运行期扫描则直接从复合索引成员取得身份并进入同一 `ResumeService` 漏斗。延迟链只恢复身份，未保存原 locale，详见 `TC-06`。

### 4.3 `GET /api/ai/events/{runId}` 与跨实例唤醒

1. 身份过滤器 → `AiController.ownedRun` 归属校验 → 快照/帧末号 Redis 读 → 创建 emitter → `RunRegistry.of` → `replayAndAttachWithStatus`（`AiController.java:1104-1205`）。
2. 写出器先按记录身份向 `FrameWakeListener` 注册，再从 Redis FrameStore 分页回放；订阅/回放入队与实时帧共用顺序锁，网络写不在锁内。
3. `RedisFrameWakeBus` 按需建立 Redis listener container；消息进入独立派发池，监听器捕获订阅身份，`SseChatEmitter.onWake` 再按记录身份读差量并入队（`RedisFrameWakeBus.java:147-184,248-333`；`FrameWakeListener.java:190-253,380-407`）。
4. 非驱动方连接由共享 attach 保活线程每 5 秒发本地心跳，并每 30 秒把归档对账提交到 `sse-delivery`；最终仍经 `sse-write` 返回客户端。末订阅退订、清队列并停保活。

### 4.4 任务作业与任务 SSE

1. `JobService.createAndStart` 在 DB 事务中创建 PENDING 作业、登记 Redis runner；事务提交后的回调调用唯一 `@Async` 入口 `AsyncJobExecutor.execute`（`JobService.java:158-231`）。
2. `applicationTaskExecutor` 执行 Export/Precheck/Import/Publish runner，访问 DB/文件并发布进度；`finally` 清 runner、共享心跳和取消登记（`AsyncJobExecutor.java:28-45`）。池拒绝在提交后回调中转成 FAILED，不静默留下 PENDING。
3. `GET /api/tasks/{id}/events` 直接返回 `TaskSseService.subscribe` 的 emitter（`TaskController.java:192-195`）。本地/远端事件都进入 `deliverJson`；远端经 Redis Pub/Sub 与任务派发池。
4. `TaskSseService` 的每次扇出对订阅者逐个调用阻塞式 `SseWriteBudget.write`，再发布到跨实例总线；这条链的占用随慢订阅者数线性增加，详见 `TC-01`。

### 4.5 删除对话与后台重试

1. `DELETE /api/ai/session/{sessionId}` → 身份过滤器 → 单例 Redis PING → `AiSessionPurgeService.purge`（`AiSessionPurgeController.java:71-103`）。
2. 服务固定入口身份，依次登记 durable pending、枚举 Redis/DB、装守卫、在独立 `REQUIRES_NEW` 事务删反馈、取消活跃 run、等待终态、删除记忆/历史/run/上下文/附件/发现键（`AiSessionPurgeService.java:324-540`）。
3. 活跃 run 等待使用 50ms 轮询、总窗口 1.5s；不是把 HTTP 请求无限挂住（`AiSessionPurgeService.java:139-151,911-948`）。未完成时返回 `complete=false`，由 durable pending 保留义务。
4. `SessionPurgeRetryRunner` 每 30s 通过 Redis 租约单飞，逐条按台账身份调用 `retry` 或记忆复核；每轮候选数、每会话次数与单候选推进窗口均有上界（`SessionPurgeRetryRunner.java:154-357`）。

### 4.6 就绪探测与关闭

`GET /health/ready` → `ReadinessService.check` → `ReadinessProbeRunner` 并发提交所有探针 → 调用线程按同一个 deadline 汇总 → 200/503。`ContextClosedEvent` 先置 draining，在多实例 profile 下按 5s 窗口分段 sleep，再交 graceful shutdown。这里的 sleep 是有关闭预算的同步等待，不是泄漏线程。

## 5. 逐项问题

### TC-01 — 任务 SSE 单次扇出按慢订阅者数串行等待，最坏为 `N × 15s`

- **严重度：高；确定性：代码证实。**
- **证据**：`TaskSseService.deliverJson` 对快照中的 emitter 逐个同步调用 `sendFrame`（`task/service/TaskSseService.java:294-317`）；`sendFrame` 使用同步 `write`（同文件 `550-573`）；同步写在 `future.get(timeout)` 上等待，超时才返回（`common/sse/SseWriteBudget.java:337-391`）。默认单帧预算 15s（`application.yml:407-424,435-448`）。
- **触发条件**：同一任务有 N 个停读/慢写订阅者；本地作业线程、HTTP 调用线程或 Redis 任务派发线程执行一次 `deliverJson`。即使每个订阅者都恰好在 15s 超时，第 N 个也要等前 N-1 个结束。
- **单机/Cluster/多实例/多线程影响**：与 Redis 拓扑无关。单实例会占用作业/HTTP 线程；多实例中每个持有连接的实例独立放大，Redis 任务派发池的少量线程也可能被连续扇出占满。写池与 AI 通道共享 64 上限，慢任务连接还能间接导致健康 AI/任务订阅者被饱和摘除。
- **根因**：把“单帧、单订阅者预算”误当成“一次全扇出预算”；同步 `write` 被放在逐订阅者循环内。
- **最佳修复**：任务通道改用 `writeAsync`，为每个 emitter 保持有界串行队列/drain，完成回调再写下一帧；任务帧是状态投影，可在有界前提下合并为最新状态，避免 backlog 乘性增长。摘除、退订与远程订阅清理继续走现有统一出口。
- **备选取舍**：把整次 fanout 投到有界执行器改动较小，但仍需每 emitter 串行与溢出策略；单纯扩大线程池只延后故障；给整次 fanout 一个共享 deadline 会限制总等待，却可能让排在后面的健康连接得不到本次帧。
- **涉及修改点/影响面**：`TaskSseService`、`SseWriteBudget` 的复用方式、任务 SSE 背压与重连语义；不应改 AI 写出器的帧序实现。
- **后续验证（只写不执行）**：构造 1/2/8/64 个停读订阅者，证明一次 publish 的调用线程不随 N 等比阻塞；验证每连接 FIFO、慢连接摘除、快照补偿、写池饱和和关闭时无残留 drain。

### TC-02 — 每次 PING 都在单例监视器内执行，Redis 慢时所有调用方串行排队

- **严重度：高；确定性：代码证实。**
- **证据**：健康正缓存 TTL 明确为 0（`ai/config/RedisAvailability.java:38-53`）；`isAvailable()` 的双检后把整个 PING 放在 `synchronized(this)` 内（同文件 `72-100`）。Redis 命令/连接超时均为 2s（`application.yml:74-84`；`application-cluster.yml:32-36`）。`/chat` 与删除接口均在入口调用它（`AiController.java:338-359`；`AiSessionPurgeController.java:77-89`）。
- **触发条件**：多个请求同时到达同一实例，且 Redis PING 慢或接近 2s 超时。
- **影响推导**：N 个请求不是一个共享 2s，而是监视器内逐个 PING；尾部等待约为 N 次 PING 之和。16×2s 已越过 30s。该推导只说明可能形态，未证明现网请求数或 Redis 实际延迟。
- **单机/Cluster/多实例/多线程影响**：监视器是每 JVM 一个；Cluster 不会消除串行化，PING 的目标节点/重定向还可能增加延迟。多实例会把总量分摊到各实例，但单实例热点仍存在；所有相关 HTTP 线程可同时被堵住。
- **根因**：为做单飞/缓存一致性，把网络 I/O 放进全局锁；同时正缓存为 0，使每次调用都进入该锁。
- **最佳修复**：改为“短正缓存 + 原子 single-flight + 有界等待”：正缓存建议先用 100–250ms 的小窗口压平突发；过期时仅一个线程探测，其他线程至多等到本次探测截止，不能排成 N 次串行 PING。失败继续使用现有负缓存与恢复探活。
- **备选取舍**：完全移出锁且每次并发 PING 可保持零正缓存，但故障/突发时会形成探测风暴；较长正缓存性能更好，却扩大“刚判健康后 Redis 才故障”的窗口。需要在入口快速失败与新鲜度之间明确取舍。
- **涉及修改点/影响面**：`RedisAvailability` 的锁、缓存指标和所有 AI 入口的延迟语义；不改变下游 Redis 命令超时。
- **后续验证（只写不执行）**：32 并发下把 PING 固定为 2s，验证不出现 32 次串行尾部等待；分别覆盖健康、故障、恢复、single-flight 失败、Cluster 节点超时，以及删除接口与聊天接口同时到达。

### TC-03 — 模型探测先把上游响应完整读入内存，1MiB“上限”只限制后续解析

- **严重度：高；确定性：代码证实。**
- **证据**：`sendAsync(...BodyHandlers.ofByteArray())` 在 `future.get` 成功后已经得到完整 `byte[]`（`ai/config/AiConnectionProbe.java:143-163`）；`boundedBody` 此时才截取 1MiB（同文件 `205-224`）。源码注释也明确承认截断不保护 RSS。
- **触发条件**：被探测端点返回超大或持续 chunked 响应；入口包括 provider `/test` 与 `/models/fetch`（`AiConfigController.java:136-168`；`AiModelCatalog.java:60-87`）。
- **单机/Cluster/多实例/多线程影响**：与 Redis 无关。每次调用可让对应实例承担完整响应分配；并发调用会叠加，严重时 OOM。多实例只在请求打到哪个实例时影响哪个 JVM。
- **根因**：把响应收集器当成无界字节数组使用，在收集完成后才做上限检查。
- **最佳修复**：使用 `BodyHandlers.ofInputStream` 或自定义受限 `BodySubscriber`，在读取过程中累计字节，超过 1MiB 立即关闭/取消并返回 `MODELS_INVALID`；同时受请求 deadline 约束。`Content-Length` 只能作提前拒绝，不能代替实际读取上限。
- **备选取舍**：只检查 `Content-Length` 实现简单但对 chunked/伪造头无效；信任内部上游并保持现状则必须明确接受 OOM 风险。
- **涉及修改点/影响面**：仅 `AiConnectionProbe` 的响应收集与超时/取消协作，对外错误码可保持不变。
- **后续验证（只写不执行）**：覆盖声明长度超大、chunked 无限流、恰好 1MiB、超限一字节、多并发与中途取消；断言读侧及时停止且不存在完整响应分配。

### TC-04 — 异步探测等待超时/中断后未取消 `CompletableFuture`

- **严重度：中；确定性：代码证实未取消，底层继续占用的时长取决于 JDK/网络栈。**
- **证据**：future 是 `try` 内局部变量，调用 `future.get(timeout)`（`AiConnectionProbe.java:158-160`）；`TimeoutException` 与 `InterruptedException` 分支只返回结果/恢复中断位，没有 `future.cancel(true)`（同文件 `165-174`）。
- **触发条件**：慢 DNS、连接或读取使 `get` 先到期，或请求线程被中断。
- **影响**：HTTP 调用方能返回，但底层异步交换未收到这里的取消信号；重复探测可能叠加在飞工作。`HttpRequest.timeout` 已存在，因此某些交换会由 JDK 自己到期，不能把“永久泄漏”写成确定事实。
- **单机/Cluster/多实例/多线程影响**：每个实例的复用 `HttpClient` 都可能积累慢操作；与 Redis Cluster 无关。
- **根因**：只实现了调用方等待上限，没有完成异步任务所有权收尾。
- **最佳修复**：把 future 提到可在 `catch/finally` 访问的位置，超时与中断路径执行 `cancel(true)`；中断后恢复标志。保留请求级 timeout 作为第二层，并记录取消是否成功。
- **备选取舍**：完全依赖 `HttpRequest.timeout` 改动最少，但不能覆盖所有“调用方先到期”的原因，也不表达明确的任务所有权。
- **涉及修改点/影响面**：`AiConnectionProbe.fetchModels`；`TC-03` 改受限读取时应一起设计取消和流关闭。
- **后续验证（只写不执行）**：慢 DNS/连接/读取/响应体四阶段分别超时和中断，观察 future 取消、连接/流收尾和重复调用后的在飞数量。

### TC-05 — SSE 写超时任务取消后仍滞留调度队列至原到期时刻

- **严重度：中；确定性：代码证实。**
- **证据**：超时器通过 `Executors.newSingleThreadScheduledExecutor` 创建（`common/sse/SseWriteBudget.java:186-205`），未设置 `removeOnCancelPolicy`；写成功会 `timer.cancel(false)`（同文件 `423-442`），提交/定时竞态也会取消（`469-472`），超时任务默认 15s 后排（`453-463`）。
- **触发条件**：大量正常 SSE 写迅速完成，使大量 timer 提前取消。
- **影响**：已取消的延迟任务不会立即从 `ScheduledThreadPoolExecutor` 队列移除，近似滞留量为“完成写速率 × 15s”。这不是永久泄漏，但在高帧率下形成可持续的大队列与 GC 压力。
- **单机/Cluster/多实例/多线程影响**：每 JVM 一个共享超时器；AI 与任务通道都受影响。Redis 拓扑不改变该机制，多实例按各自帧率累积。
- **根因**：使用了定时取消，却没有启用取消即出队；同类问题在 `TaskSseSchedulerConfig` 和 `LeaseService` 已显式处理。
- **最佳修复**：直接创建 `ScheduledThreadPoolExecutor`，设置 `setRemoveOnCancelPolicy(true)`，并配置停机后不执行既有延迟任务；保留现有 CAS 恰好一次回调。
- **备选取舍**：定期清理取消任务实现更复杂且仍有滞留；不创建每写 timer 而改共享轮询轮可减少对象数，但会增加调度精度与实现复杂度。
- **涉及修改点/影响面**：`SseWriteBudget.timeoutScheduler`；不动写池与帧协议。
- **后续验证（只写不执行）**：高速完成写后检查调度队列不随历史任务持续增长；覆盖“先完成再排 timer”“timer 与完成同时触发”“停机”三种竞态，仍须回调恰一次。

### TC-06 — 确认门延迟重试/到期回调没有保存原请求 locale

- **严重度：中；确定性：代码证实。**
- **证据**：首次 HTTP/续跑链显式搬运 locale 的防护存在（`AiController.java:555-598`；`ResumeService.java:1753-1786`）。但 park 到期任务只捕获身份（`ConfirmGate.java:1554-1596`），重派发与租约重试也只捕获身份（同文件 `2724-2750,2893-2904`）。这些任务随后进入 `dispatchResume`，在调度线程现读 `ErrorBodyFactory.requestLocale()`（同文件 `2612-2631`）；无 locale 时明确回落中文（`ErrorBodyFactory.java:116-127`）。预算耗尽文案也直接读当前线程语言（`ConfirmGate.java:2815-2820`）。
- **触发条件**：英文请求在首次派发后遇到池饱和、会话忙、租约持有或 park 到期，后续由调度线程续跑；跨实例/重启恢复没有原 HTTP 线程时也同样没有语言来源。
- **单机/Cluster/多实例/多线程影响**：均会出现；Cluster 只改变状态存储拓扑，不保存 locale。多实例恢复时更明显，因为不能依赖原实例内存。
- **根因**：租户身份被作为记录事实保存/随任务携带，但 locale 仅作为当前线程状态搬运，没有成为轮次的持久事实。
- **最佳修复**：在轮次快照/权威执行信封中保存归一化 `replyLanguage`（只需 `zh-CN/en-US` 标签），所有恢复、重试、到期和启动扫描按记录语言建立 locale 作用域，并在退出时恢复原值。这样跨实例和重启也成立。
- **备选取舍**：只在每个定时任务中多携带一个 `Locale` 改动较小，但进程死亡/跨实例后仍丢语言，只能作为短期补丁，不是完整修复。
- **涉及修改点/影响面**：`RunSnapshot`/序列化兼容、`ToolExecutionEnvelope`、`ConfirmGate`、`ResumeService`、`ResilientChatService`、旧快照默认中文策略。
- **后续验证（只写不执行）**：英文首轮分别经过池满重试、租约重试、park 到期、跨实例接管和重启恢复；断言提示词、错误/降级文案语言一致，且共享线程执行后 locale 恢复原状。

### TC-07 — Reactor、AI 调度器与 SSE 写回调的普通日志没有完整 MDC 链

- **严重度：低；确定性：代码证实。**
- **证据**：`AiTrace.wrap/wrapCallable` 自身的快照与恢复实现正确（`ai/observe/AiTrace.java:155-200`），主池也接了装饰器。但模型回调明确运行于上游线程，代码只恢复身份，没有恢复 MDC（`ResilientChatService.java:1460-1467,1541-1552`）；`aiStreamWatchdog/aiCancelPoll` 仅做 TTL 包装（`AiExecutorConfig.java:131-176`）；看门狗普通日志依赖文本里的 `runId`（`StreamWatchdog.java:296-312`）。`SseWriteBudget` 的写任务与超时回调也没有 `AiTrace.wrap`（`SseWriteBudget.java:423-463`）。日志行首实际读取 MDC（`application.yml:505-514`）。
- **触发条件**：日志由 Reactor 上游线程、AI 定时线程、写池或超时线程直接发出，且没有调用显式 `eventOn`。
- **影响**：行首 `runId/sessionId` 为空或不是本轮，多实例合并日志时难以串联；`eventOn` 的事件体与临时绑锚可缓解部分结构化事件。未发现它直接改变业务键或身份的代码证据。
- **单机/Cluster/多实例/多线程影响**：均影响可观测性；多实例排障损失更大。
- **根因**：MDC 与 TTL 是两套上下文，只有部分执行器安装了双重装饰；消费者/回调没有统一的上下文 API。
- **最佳修复**：提供统一的“捕获 MDC → 执行前安装 → finally 恢复原图”Consumer/Scope API；在组装订阅时捕获首轮/续跑锚点并包装 `onResponse`，在调度器提交和 SSE 写回调处复用。不能在共享线程上只 `clear()`，必须恢复执行前的 MDC。
- **备选取舍**：逐条改成 `AiTrace.eventOn` 可减少改动，但会迫使所有普通日志结构化，覆盖和维护成本较高。
- **涉及修改点/影响面**：`AiTrace`、`ResilientChatService`、`AiExecutorConfig` 的提交方、`SseWriteBudget`/`SseChatEmitter` 回调；不改变业务身份。
- **后续验证（只写不执行）**：让两个 run 交替复用同一 Reactor/写池/定时线程，断言每条日志行首属于当前 run，执行前后 MDC 原图不变，显式事件与行首不分裂。

### TC-08 — SSE 写池和超时线程未清除创建者继承的 TTL 身份

- **严重度：中；确定性：代码证实污染条件；当前业务串租户影响未证实。**
- **证据**：身份容器是可继承的 `TransmittableThreadLocal`（`RequestContextUtils.java:28-34`）。写池和超时器线程工厂只设置名称/daemon（`SseWriteBudget.java:197-201,254-259`），不像 attach 保活线程那样启动时 `denied()`（`SseChatEmitter.java:905-924`）。AI 写任务只在 `send` 内打开记录身份，退出后恢复池线程旧值（同文件 `4593-4604`）；完成/超时回调在作用域外执行（`SseWriteBudget.java:439-461`）。
- **触发条件**：某个持有身份的线程首次触发这些静态线程创建；之后线程服务其它轮次，回调又读取“当前身份”而没有按记录覆盖。
- **影响**：池线程可在任务间长期保留首个创建者身份。当前已核对的 SSE 关键读写大多另有 `deliveryIdentity`/Registration 显式作用域，因此不能据此宣称已经发生跨租户读写；风险集中在未受保护的现状日志、后续新增回调和维护一致性。
- **单机/Cluster/多实例/多线程影响**：与存储拓扑无关；每个 JVM 各自污染。多线程复用使“首创建者”成为跨请求残留。
- **根因**：只处理“执行时传播”，没有给进程级裸线程定义无请求身份的基线；各组件分别补丁，缺少统一线程工厂纪律。
- **最佳修复**：为共享裸线程统一使用启动即 `RunIdentityScope.denied()` 的工厂，并在通用回调边界增加“捕获/安装/恢复”上下文；回调业务身份仍必须来自记录，不把 TTL 提交者当权威。可同步审视确认门/取消收尾的裸调度线程工厂。
- **备选取舍**：给池套 `TtlExecutors` 能规范快照恢复，但这里的目标不是传播任意提交者身份；只用 TTL 而不清理会保留不必要的状态。
- **涉及修改点/影响面**：`SseWriteBudget` 两类线程、通用线程工厂/装饰器；现有记录身份作用域保持不变。
- **后续验证（只写不执行）**：身份 A 创建线程后让 B/无身份任务复用；断言任务外无残留，任务内只读记录身份，完成、失败、超时和停机路径均恢复原状。

### TC-09 — 重试退避被中断后返回“可继续”，可能再发一次模型尝试

- **严重度：中；确定性：代码证实控制流。**
- **证据**：`awaitRetryBackoff` 在 `Thread.sleep` 被中断时恢复中断位并返回 `false`（`ResilientChatService.java:2258-2289`）；调用方只把 `true` 当取消，`false` 会继续下一次 attempt（同文件 `1500-1512`）。方法注释称“交给上层路径收尾”，实际返回值语义却与继续执行相同（同文件 `1773-1786`）。
- **触发条件**：运行线程恰好在两次模型尝试的退避 sleep 中被中断。
- **影响**：中断后不是立即收尾，而是进入下一次尝试；后续 Redis/模型调用可能在中断位下失败或产生不必要工作。AI 主池正常优雅停机配置不主动中断（`AiExecutorConfig.java:109-111`），因此不能把正常滚动发布必现写成事实。
- **单机/Cluster/多实例/多线程影响**：与拓扑无关；任何中断源均可触发。多实例各自可能出现额外尝试。
- **根因**：布尔返回同时承担“取消”和“继续”，中断分支落入了错误的一侧。
- **最佳修复**：中断时抛出既有 `StreamViolationException(CANCELLED)`，或返回 `true` 让调用方走既有取消收尾；保留中断位。若需区分用户取消与系统中止，可改成三态结果，但两者都必须停止新尝试。
- **备选取舍**：仅再次检查 `Thread.currentThread().isInterrupted()` 也能修补，但分散中断判据，不如在异常发生点直接走统一出口。
- **涉及修改点/影响面**：`ResilientChatService.awaitRetryBackoff` 及其调用方；终态/取消帧语义需沿用现有守卫。
- **后续验证（只写不执行）**：在首次退避和带 Retry-After 退避中注入中断，断言不再调用下一次 attempt、订阅/看门狗/保活均关闭、终态只写一次。

### TC-10 — `LeaseService.ended` 永久墓碑没有容量/时间回收，丢租轮次删除后仍可残留

- **严重度：中；确定性：代码证实。**
- **证据**：`ended` 是无界 `ConcurrentHashMap`，注释明确只有成功 acquire/release 与进程关闭会清除（`LeaseService.java:368-397`）。丢租/不可用会 `markEnded`（同文件 `655-688,714-740`）。统一终态清理明确“不清 ended”（`ResilientChatService.java:2606-2616`）。会话销毁实际会调用 `RunStore.deleteRun`（`AiSessionPurgeService.java:1219-1233`）；删除先尝试还租再删 13 键，但对已丢租、没有本地 held 视图的条目不会清 `ended`（`RunStore.java:2886-2908`；`LeaseService.java:785-793`）。
- **触发条件**：实例反复经历丢租/租约不可知，轮次随后终态或被会话销毁；同一实例持续运行且不再成功持有同一 `(key,owner)`。
- **影响**：每个唯一 `(租约键,owner)` 可永久留下一条记录，长跑实例内存随历史故障轮次增长。这里是累积泄漏风险，不声称某个速率下必然 OOM。
- **单机/Cluster/多实例/多线程影响**：单机同样发生；Cluster 故障、接管与多实例竞争会增加产生墓碑的机会。每个 JVM 独立累积，删除共享 Redis 数据不会清其它实例的 Map。
- **根因**：fencing 安全墓碑只有语义清理，没有与本地写者生命周期、终态或容量建立回收协议；源码关于“生产没有会话销毁调用 deleteRun”的旧注释已与当前调用链不符。
- **最佳修复**：设计显式回收协议：墓碑记录时刻与本地代次；终态且本进程无驱动/在飞写者后由统一出口清除；删除场景若仍需挡迟到写，用 Redis 侧 deleted/fence 事实承接到期保护，而不是永久 JVM Map。另设可观测容量上限，达到上限时新写应 fail-closed，不能静默淘汰 LOST 墓碑。
- **备选取舍**：只加 TTL/简单 LRU 改动小，但到期后旧写者仍可能存活，会重新打开写门；直接删除时清空最简单，却可能牺牲 fencing，不能作为无审查的默认修法。
- **涉及修改点/影响面**：`LeaseService`、`ResilientChatService` 统一终态出口、`RunStore.deleteRun`、本地驱动/在飞写者登记和会话销毁；需同步修正旧注释。
- **后续验证（只写不执行）**：循环制造“丢租→终态→删除”，验证各实例墓碑数能回落；保留一个迟到旧写者验证仍被 fence/deleted 事实拒绝；覆盖同 run 不同身份、同 key 不同 owner、进程重启和容量耗尽。

## 6. 轮询、等待与阻塞点核对

| 路径 | 节奏/上限 | 审查结论 |
|---|---|---|
| 跨实例取消 | 每轮 1Hz，独立 `aiCancelPoll`；失败有 5s 退避 | 已与看门狗判定分离；取消任务随 watchdog close。未发现慢 GET 直接卡住判定线程的现形。 |
| 旧式确认门等待 | 2s latch/Redis 轮询；生产 park=true 时不驻留 | 生产主要走外置 park + 到期任务；locale 缺口见 `TC-06`。 |
| 模型重试退避 | 250ms 切片，受段预算与 Retry-After 约束 | 有界，但中断返回错误方向见 `TC-09`。 |
| 首轮/续跑保活 | 5s 周期，终态/park/异常 close | 身份恢复明确；MDC 缺口见 `TC-07`。 |
| 非驱动方 attach | 5s 保活，30s 归档对账；单飞 | 有末订阅/终态停表和对账在飞标志；未发现每连接创建线程。 |
| 会话门与运行租约 | 会话门默认 30s；租约默认 10s | 有固定键、身份作用域、丢租处理与取消出队；墓碑回收另见 `TC-10`。 |
| 启动/运行期恢复 | 启动延迟+抖动；运行期默认 120s | 有跨实例单飞、动作预算、轮转游标与逐轮身份；直接启动线程为单次 daemon。 |
| 会话销毁活跃等待 | 50ms 轮询，总计 1.5s | 有界；不是删除接口超过 30s 的直接证据。 |
| 会话销毁后台重试 | 30s；每轮 10 候选，次数/推进窗口有界 | durable ZSET 驱动，不靠全库 SCAN；身份按台账恢复。 |
| KB 刷新/版本发现 | 单线程固定延迟；版本重试最多 3 次并退避 | 周期异常不终止后续节拍；清单写窗最多 6 次、25ms 间隔。 |
| 作业心跳/看门狗 | 心跳按 TTL/3；看门狗默认 10s | ticker 空闲即停；DB 失败/Redis 不可读有 fail-closed 处理。 |
| readiness | 请求触发，所有探针共享默认 2s | 后台池有界；不能消灭不可中断 I/O，只能保证端点返回。 |
| 关闭 drain | 100ms 切片；多实例 profile 默认 5s | 有界且可中断，不另建线程。 |
| 任务 SSE 写 | 每订阅者同步最多 15s | 总扇出无共享 deadline，见 `TC-01`。 |

## 7. 未发现问题的关键路径

以下是代码审查后的“未发现所述缺陷”，不是运行级保证：

1. **HTTP 身份边界与 ThreadLocal 清理**：身份组合经过目录校验；`try-with-resources` 在同线程恢复旧值、无旧值即 remove；作用域幂等且拒绝跨线程关闭。未发现请求线程把上一位身份带给下一请求的代码路径。
2. **AI 主池与续跑触发池的身份传播**：提交时 TTL 捕获，执行时按快照/记录再次覆盖；不是只依赖线程继承。主池队列和拒绝策略有界，饱和有明确 503/重试结局。
3. **同步首轮 locale**：`AiController` 在 HTTP 线程捕获，工作线程设置并在 `finally` reset；工具执行点还从 `toolContext` 恢复并还原旧 locale。问题集中在后续定时/恢复链，而非这条同步首链。
4. **Reactor 与官方记忆 advisor 的租户身份**：上游消费按捕获身份开作用域；`IdentityBoundChatMemoryAdvisor` 把身份绑定到调用专用对象，官方 boundedElastic/上游线程读写都走该身份。未发现这些路径依赖回调线程身份拼键。
5. **摘要池和工具超时池**：均为有界平台线程池，提交时带 MDC、执行时恢复记录身份；拒绝不降级成无界同步执行。不可中断任务仍可能占池，但有容量与结算边界。
6. **作业 `@Async` 生命周期**：事务提交后才启动；拒绝会清登记并落 FAILED；runner 正常/异常退出都在 `finally` 清理。未发现自调用绕过代理或每任务新建无界线程。
7. **SSE 注册表与唤醒生命周期**：本地键包含身份；写出器有空闲回收和终态回收；订阅按稳定 target/精确句柄注销，断连清队列并停保活。Redis 监听容器显式拆分派发与订阅池，避免默认无界线程。
8. **AI SSE 慢写隔离**：投递线程不直接等待 socket 写，完成/超时回调有 CAS 去重；写池有 64 硬上限。残留是取消 timer 队列与上下文缺口，不是无限创建写线程。
9. **会话销毁身份与等待**：HTTP 与自动重试都按入口/台账身份；活跃轮等待有 1.5s 上限，未完成保留 durable pending，不以“读不到”冒充删净。
10. **readiness 与 Flyway ThreadLocal**：探针调用方有共享 deadline、固定池与饱和 DOWN；Flyway pin 在句柄关闭时恢复/移除旧连接，未发现 ThreadLocal 永久污染。
11. **虚拟线程与重复池**：生产配置关闭虚拟线程；未发现有任务循环内反复 new 无界池。若干手工 `initialize()` 虽有双重初始化注释，但调用发生在任何任务提交前，未据此认定线程泄漏。

## 8. 建议修复顺序与整体验证计划

1. **先处理可用性/OOM**：`TC-01` 任务 SSE 同步扇出、`TC-02` 单例 PING 串行、`TC-03` 无界响应收集；它们可直接形成长请求或整机资源风险。
2. **再处理异步所有权**：`TC-04` future 取消、`TC-05` timer 出队、`TC-09` 中断收尾；这些修复应共用明确的任务生命周期，不再只保证调用方返回。
3. **随后统一上下文与状态寿命**：`TC-06` 持久化轮次语言、`TC-07` MDC 回调装饰、`TC-08` 裸线程无身份基线、`TC-10` 墓碑回收协议。
4. **后续组合验证（仅方案，未执行）**：
   - 单 Redis 与 Redis Cluster 各跑同一组多身份/多实例场景，重点不是键槽，而是验证线程模型在两种拓扑下结果一致。
   - 两个服务实例交叉持有驱动、连接、取消、删除与恢复；至少两个租户使用相同 runId/sessionId 形状，检查 Redis 键、本地注册表、MDC 和 locale。
   - 故障注入覆盖 Redis 慢而不失败、2s 超时、恢复、Pub/Sub 断连、上游慢响应/无限响应、SSE 客户端停读、池饱和和线程中断。
   - 验证结束后检查线程数、各池队列、定时取消队列、`ended`/注册表/监听器大小可回落；任何容量达到上限都必须有明确降级，不得静默串身份或重新打开旧写门。
   - 对删除接口单独记录“入口 PING 排队、枚举、DB、活跃等待、逐面删除、返回”的分段耗时；在未完成该测量前，不把超过 30s 归因于某一个线程或 Redis 命令。
