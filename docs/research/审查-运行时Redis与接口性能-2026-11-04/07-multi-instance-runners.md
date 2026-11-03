# 多实例后台运行器与调度审查

- 审查基线：`52b093642402c9455b7cb9627fae6dbdd6544e54`
- 审查范围：生产代码与生产配置中的后台运行器、周期调度、启动/关闭事件、相关 Redis/DB/线程与请求身份传播。
- 审查方式：只读静态调用链审查。未修改生产/测试代码，未运行测试、构建、CI、服务或压测，未执行 Git 写操作。
- 时间口径：配置值用于静态推算；本报告不把静态推算写成实测耗时。尤其“删除对话接口在 Redis Cluster 下超过 30 秒”在本次审查中没有被实测证实；本报告只指出可能放大该接口耗时的后台并发与扫描路径。

## 1. 结论摘要

本次确认的主要风险不是“所有实例都会把同一任务各执行一遍”这么简单，而是四种不同边界：

1. **作业看门狗有实际正确性缺口**：一次终态写失败会被本地判重集合永久压住；共享心跳的两键读取与判死之间存在竞态，可能把刚恢复心跳的远端活作业判死。
2. **扫描单飞不等于扫描有界**：`StartupResumeRunner` 的全局扫描锁能挡住正常情况下的多实例同扫，但候选发现仍先执行全量 `SMEMBERS`、逐成员 `EXISTS` 和逐轮多键读取；`JobWatchdog` 则根本没有扫描级单飞，每实例每 10 秒读取全量在途实体。
3. **会话销毁只有“自动轮次锁”，没有“pending 记录级执行租约”**：手工 DELETE 可与自动重试交错；候选卡死时当前调用仍继续，而全局锁会被释放，另一实例可接手同一记录。删除动作大多按幂等设计，但重复 DB/文件/Redis 工作与竞态窗口是真实存在的。
4. **HTTP 摘流与后台任务停机不是同一套生命周期**：`ShutdownDrainGate` 只处理 readiness 和 Web 连接器；后台调度器直到 bean 销毁才停止，且多数不等待在飞扫描结束。启动续跑另有一条完全未登记的裸 daemon 线程。

### 1.1 问题总表

| 编号 | 严重度 | 确定性 | 摘要 |
|---|---|---|---|
| MR-01 | 高 | 代码证实 | 看门狗终态写失败后，本地 `judgedJobIds` 阻止后续重试，单实例下可直到重启才恢复 |
| MR-02 | 高 | 代码证实时序 | 心跳 `GET beat → GET anchor` 与判死之间无复核，可把已重新续拍的远端 runner 判为 STALE |
| MR-03 | 高 | 代码证实成本模型；实际耗时取决于规模 | 启动/运行期续跑发现面无分页，预算在进入管线后才生效；Cluster 下全局索引还是单主节点热点 |
| MR-04 | 中 | 代码证实 | `job:deadjudge` 只有 90 秒 TTL、无续租/结果 fencing，慢判定或崩溃后可重复写 issue |
| MR-05 | 中 | 代码证实 | 看门狗每实例全量水合在途 `Job`、EAGER `items` 与 CLOB；无扫描级单飞或分页 |
| MR-06 | 中 | 代码证实 | 每实例启动恢复都扫共享 DB，并在持 task 行锁时同步读 Redis；故障时启动时间随候选数放大 |
| MR-07 | 中 | 代码证实控制流 | 手工 DELETE 与自动 purge 不使用同一把执行锁，可并发执行同一 `purgeId` 的删除主体 |
| MR-08 | 中 | 代码证实控制流 | purge 候选超 5 分钟后放锁但原调用不停止，另一实例可重复接手同一 pending |
| MR-09 | 中 | 代码证实生命周期；业务后果依时序 | drain 期间后台任务仍可跑，销毁时 `shutdownNow` 不等在飞任务；启动裸线程完全未纳入停止 |
| MR-10 | 中 | 代码证实 | readiness 无请求合并，超时取消的队列任务不主动出队，慢依赖可转化为持续饱和和假 DOWN |
| MR-11 | 中 | 代码证实；默认配置有缓解 | 启动续跑发现失败后会标记已扫描且不重试；关闭运行期 sweep 时只剩手工/重启入口 |
| MR-12 | 中 | 代码证实时钟依赖；误判需要时钟/时区条件 | 作业超时混用 DB `LocalDateTime`、实例默认时区和墙钟；purge 卡死窗也用非单调墙钟 |
| MR-13 | 中 | 代码证实锁边界；重复回源需长刷新条件 | KB HTTP 刷新锁 30 秒且不续租，多文档同步可越过锁；local 源共享缓存同步不走该锁 |
| MR-14 | 低 | 代码证实调度形状 | purge 所有实例同一 30 秒节拍抢全局锁，锁与 due ZSET 无抖动/故障节拍退避，形成可预测的争用与热点 |
| MR-15 | 低 | 代码证实探针覆盖边界 | readiness/删除前置探活只有一次 `RedisConnection.ping`，代码不提供“Cluster 全部 master/槽位可用”的证明 |

严重度含义：**高**表示可导致错误终态、恢复停摆或明显无界资源放大；**中**表示需要特定并发/故障/规模，但有明确重复副作用、假死或可用性风险；**低**主要是可预测压力、观测缺口或已有后续兜底的治理问题。

## 2. 实际审查范围与阅读文件

### 2.1 核心入口及其直接依赖

实际逐段阅读了以下生产文件；路径均相对仓库根目录：

- `backend/src/main/java/com/example/configmgr/ai/run/StartupResumeRunner.java`
- `backend/src/main/java/com/example/configmgr/ai/run/ResumeService.java`
- `backend/src/main/java/com/example/configmgr/ai/run/RunStore.java`
- `backend/src/main/java/com/example/configmgr/ai/run/LeaseService.java`
- `backend/src/main/java/com/example/configmgr/ai/run/CancelledRunFinalizer.java`
- `backend/src/main/java/com/example/configmgr/ai/run/StreamWatchdog.java`
- `backend/src/main/java/com/example/configmgr/ai/run/TurnKeepalive.java`
- `backend/src/main/java/com/example/configmgr/ai/session/SessionPurgeRetryRunner.java`
- `backend/src/main/java/com/example/configmgr/ai/session/SessionPurgePendingStore.java`
- `backend/src/main/java/com/example/configmgr/ai/session/AiSessionPurgeService.java`
- `backend/src/main/java/com/example/configmgr/ai/session/RedisClusterScanSupport.java`
- `backend/src/main/java/com/example/configmgr/ai/session/SessionGate.java`
- `backend/src/main/java/com/example/configmgr/ai/web/AiSessionPurgeController.java`
- `backend/src/main/java/com/example/configmgr/ai/web/AiController.java`
- `backend/src/main/java/com/example/configmgr/job/service/StaleJobRecoveryRunner.java`
- `backend/src/main/java/com/example/configmgr/job/service/RunnerActiveKeyCleanupRunner.java`
- `backend/src/main/java/com/example/configmgr/job/service/JobWatchdog.java`
- `backend/src/main/java/com/example/configmgr/job/service/JobHeartbeatRegistry.java`
- `backend/src/main/java/com/example/configmgr/job/service/JobTerminalWriter.java`
- `backend/src/main/java/com/example/configmgr/job/service/JobProgressService.java`
- `backend/src/main/java/com/example/configmgr/job/service/JobCancellationRegistry.java`
- `backend/src/main/java/com/example/configmgr/job/service/AsyncJobExecutor.java`
- `backend/src/main/java/com/example/configmgr/job/service/JobService.java`
- `backend/src/main/java/com/example/configmgr/job/repo/JobRepository.java`
- `backend/src/main/java/com/example/configmgr/job/entity/Job.java`
- `backend/src/main/java/com/example/configmgr/task/service/TaskService.java`

### 2.2 就绪、关闭、其他 Runner 与周期调度

- `backend/src/main/java/com/example/configmgr/health/ReadinessProbeRunner.java`
- `backend/src/main/java/com/example/configmgr/health/ReadinessService.java`
- `backend/src/main/java/com/example/configmgr/health/ReadinessRunnerConfig.java`
- `backend/src/main/java/com/example/configmgr/health/ReadinessProperties.java`
- `backend/src/main/java/com/example/configmgr/health/HealthController.java`
- `backend/src/main/java/com/example/configmgr/health/DbReadinessProbe.java`
- `backend/src/main/java/com/example/configmgr/health/StorageReadinessProbe.java`
- `backend/src/main/java/com/example/configmgr/health/RedisReadinessProbe.java`
- `backend/src/main/java/com/example/configmgr/health/SeedReadinessProbe.java`
- `backend/src/main/java/com/example/configmgr/health/ShutdownDrainGate.java`
- `backend/src/main/java/com/example/configmgr/ai/config/RedisAvailability.java`
- `backend/src/main/java/com/example/configmgr/seed/DataSeedRunner.java`
- `backend/src/main/java/com/example/configmgr/seed/GlmSeedRunner.java`
- `backend/src/main/java/com/example/configmgr/seed/GlmSeedService.java`
- `backend/src/main/java/com/example/configmgr/seed/SeedLock.java`
- `backend/src/main/java/com/example/configmgr/seed/SeedStatus.java`
- `backend/src/main/java/com/example/configmgr/ai/config/AiConfigSeeder.java`
- `backend/src/main/java/com/example/configmgr/ai/kb/KbService.java`
- `backend/src/main/java/com/example/configmgr/ai/kb/CachedKbSource.java`
- `backend/src/main/java/com/example/configmgr/ai/kb/KbCache.java`
- `backend/src/main/java/com/example/configmgr/ai/kb/KbConfig.java`
- `backend/src/main/java/com/example/configmgr/ai/kb/AiKbController.java`
- `backend/src/main/java/com/example/configmgr/ai/exec/AiExecutorConfig.java`
- `backend/src/main/java/com/example/configmgr/config/AsyncConfig.java`
- `backend/src/main/java/com/example/configmgr/config/AppProperties.java`

### 2.3 配置与约束证据

- `backend/src/main/resources/application.yml`
- `backend/src/main/resources/application-cluster.yml`
- `backend/src/main/resources/application-postgresql.yml`
- `backend/src/main/resources/application-h2tcp.yml`
- `backend/src/main/resources/db/migration/V1__schema.sql`
- `backend/src/main/resources/db/postgresql/V1__schema.sql`
- AI provider/model 唯一约束迁移文件。

全量注解/调度搜索结论：生产代码中没有实际 `@Scheduled` 或 `@EnableScheduling`；命中只出现在 `JobWatchdog` 的说明注释。实际入口是 4 个 `ApplicationRunner`（`RunnerActiveKeyCleanupRunner`、`StaleJobRecoveryRunner`、`DataSeedRunner`、`AiConfigSeeder`）、3 个 `ApplicationReadyEvent` 监听器（`StartupResumeRunner`、`SessionPurgeRetryRunner`、`GlmSeedRunner`）、2 个 `ContextClosedEvent` 监听器（`ShutdownDrainGate`、`ReadinessService`），以及 `@PostConstruct` 自建的 `JobWatchdog`、`KbService` 等调度器。另有每轮/每会话调度器，见第 3.7 节。

## 3. 配置与完整调用链

### 3.1 生效配置基线

| 项目 | 值与出处 | 运行时含义 |
|---|---|---|
| Redis 命令/连接超时 | `backend/src/main/resources/application.yml:74-84`，2s/2s | 单次 Redis 慢调用有命令预算，但多次串行命令的总预算不是 2 秒 |
| Cluster | `application-cluster.yml:20-36`，显式 nodes、最多 3 次重定向 | 不改变键形；单键命令与逐 master SCAN 仍是代码责任 |
| AI 启动恢复 | `application.yml:238-262`：启动开、8s+0..5s 抖动、每拍产出 10/动作尝试 20 | 动作预算不限制发现阶段大小 |
| AI 运行期 sweep | `application.yml:263-274`：开、120s fixed-delay | 默认能补启动失败；关闭后 MR-11 完全暴露 |
| purge retry | `SessionPurgeRetryRunner.java:79-110`：10s 首延、30s 周期、10 候选、10 次尝试、300s 锁与候选卡死窗 | 每实例注册，Redis 锁正常时全局仅一轮执行 |
| job watchdog | `application.yml:449-472`：10s 扫描、30s×3=90s 判死窗、30m 绝对上限 | 每实例都扫描；90s 同时是 deadjudge TTL |
| runner-active | `JobHeartbeatRegistry.java:94-103,206-253`：长锚点 TTL=2×30m+5m，beat TTL=90s | beat 由进程级 ticker 以 TTL/3 续期 |
| readiness | `application.yml:484-503`：2s 共享时限、4 线程、队列 8 | 每请求现算，无快照缓存 |
| drain | 默认 0s；cluster/postgresql/h2tcp 为 5s | 只覆盖 Web 摘流，不等待后台运行器 |
| 业务作业池 | `AsyncConfig.java:66-87`：4/8 线程、队列 200、关闭等待 30s | 与 watchdog 独立，避免观察者和作业共用线程 |
| AI 运行池 | `AiExecutorConfig.java:93-113`：20 线程、零队列、关闭等待 10s | 续跑扫描只负责派发，真正长任务在该池 |

### 3.2 `StartupResumeRunner`：入口到返回

**启动与周期入口**

```text
ApplicationReadyEvent
  → StartupResumeRunner.onReady()
    → startRuntimeSweep()：本实例 ai-runtime-sweep 单 daemon 线程，120s fixed-delay
    → 新建 ai-startup-resume 裸 daemon 线程
      → startup-delay + 有界抖动
      → startupScan()（单飞让位时最多 3 次）
      → scan(false)
```

证据：`StartupResumeRunner.java:254-292,356-379,1147-1192`。

**单次扫描**

```text
scan()/sweep()
  → tryStartScan()
    → 本进程 ReentrantLock
    → LeaseService.acquire(ai:lock:resume-scan[identity])
      → 无 tag 资源锁：GET；空时 INCR 全局 token 计数器 + 单键 Lua SET PX
      → markDriving()：每拍唯一 owner，10s 续租，60s TTL
  → ResumeService.listRunsForRecovery()
    → discoverRuns(true)
    → 系统线程：RunStore.allRunRecords()
      → SMEMBERS ai:runs
      → 逐成员 EXISTS 对应身份快照键；死成员 SREM
    → 每记录 RunIdentityScope.open(record tenant/user)
    → recoveryRun()
      → 读快照、pending/request/decision、cancel 状态、activity 锚点与 Stream 尾部
  → runPipeline()
    → 身份/终态/未决/预算/轮转
    → 续跑：ResumeService.resume()
    → 或取消收尾：ResumeService.cancelConfirmed()
    → 或前端 park 到期：ConfirmGate.convergeExpiredParkedFrontend()
  → 组装 lastScan/lastSweep
  → ScanFlight.close()：停续租 → 条件 release → 放本地锁
  → 返回 Map 报告
```

证据：`StartupResumeRunner.java:295-354,497-552,581-792,915-998,1080-1099,1213-1246`；`ResumeService.java:471-608`；`RunStore.java:3163-3189,3363-3397,4843-4898`；`LeaseService.java:527-615,1010-1023,1108-1156`。

**续跑派发不是同步完成**

```text
ResumeService.resume()
  → 每轮带身份租约 acquire + Redis verifyHeld
  → 本进程 resuming 互斥
  → 读快照/强一致 pending/cancel/终态/会话门/写权限
  → 重建历史与上下文
  → aiRunExecutor.execute(...)
    → 工作线程按快照身份执行 ResilientChatService.driveResumed()
    → finally 清 resuming、关闭 registry、按 token 还会话门
  → 当前扫描线程立即收到 ACCEPTED
```

证据：`ResumeService.java:825-935,1102-1249,1321-1335,1752-1839`。因此长模型调用不占 `ai-runtime-sweep` 线程，但扫描本身的候选发现和每条续跑重建会占该线程。

### 3.3 `SessionPurgeRetryRunner`：自动销毁与手工 DELETE

**自动入口**

```text
ApplicationReadyEvent
  → onReady()：本实例 ai-session-purge-retry 单 daemon 线程
  → 10s 后每 30s fixed-delay 调 runOnceSafely()
  → runOnce()
    → 每轮唯一 owner 抢 ai:purge:retry:lock（300s）
    → markDriving() 续租；存活判据含 5 分钟候选推进窗
    → SessionPurgePendingStore.listDue(now, 10)
      → ZRANGEBYSCORE ai:purge:retry:due -inf now LIMIT 0 10
      → 逐 member GET pending JSON；按记录恢复身份
    → 普通：AiSessionPurgeService.retry(sessionId,purgeId)
    → 复核态：verifyMemoryOnly()
    → 失败按 attempts 记录 10s→300s 指数退避；10 次后退役发现项
    → finally：roundLive=false → stopDriving → 条件 release
  → 返回 RetryReport
```

证据：`SessionPurgeRetryRunner.java:154-183,198-358`；`SessionPurgePendingStore.java:390-459,502-531,604-639,830-833`。

**retry 删除主体**

```text
retry()
  → 再读 pending 并核对 purgeId
  → enumerate()
    → 会话→run 索引、gate/current 点名、逐 run 快照/pending
    → 对 archive 与 memory staging 分别逐 Cluster master SCAN
    → transcript/context/session/attachment 等存在性读取
    → DB feedback 快照
  → deleteFeedback()：DB 删除
  → executeDeletion()
    → 取消活跃 run；必要时 ResumeService.cancelConfirmed()
    → awaitTerminal()：1.5s 总窗、50ms 轮询
    → 重读状态
    → 删 memory/transcript/每 run 13 键/context/session/附件文件与登记
    → 主体无错误才删发现键
    → 完整则 guard 收尾 + 60s 后排记忆复核；否则 pending 退避
  → 返回 PurgeResult
```

证据：`AiSessionPurgeService.java:429-540,566-685,784-828,843-977`；`RedisClusterScanSupport.java:48-150`。

**手工入口没有经过自动轮次锁**

```text
DELETE /api/ai/session/{sessionId}
  → RedisAvailability.isAvailable() + 请求身份检查
  → AiSessionPurgeService.purge()
    → register()：已有 pending 也复用 purgeId，并把 nextAt 重置为 now
    → 与 retry 共用的 enumerate/deleteFeedback/executeDeletion
  → HTTP 200 PurgeResult（含 complete/deferred/errors）
```

证据：`AiSessionPurgeController.java:71-103`；`AiSessionPurgeService.java:324-418`；`SessionPurgePendingStore.java:266-345`。这条差异是 MR-07 的直接证据。

### 3.4 `StaleJobRecoveryRunner` 与启动清理

```text
Spring Boot ApplicationRunner 阶段（每实例）
  → @Order(-1) RunnerActiveKeyCleanupRunner.run()
    → redis 模式才执行
    → 逐 master SCAN job:runner-active:*，跨节点合计最多检视 512 键
    → GET 值 → 同主机且 PID 不存在 → 单键 Lua 按原值 DEL
    → 单节点失败只留 TTL；启动继续
  → @Order(0) StaleJobRecoveryRunner.run()
    → keyset 分页：id > afterId AND status IN(PENDING,RUNNING)，每页 50 个标量投影
    → 每作业独立 REQUIRED 事务
      → SELECT task ... FOR UPDATE
      → JobHeartbeatRegistry.isRunnerAlive()
        → 本地 active/lastBeat 优先；否则 Redis EXISTS runner-active
        → Redis 不可知则本作业 fail-closed 跳过
      → 条件 UPDATE jobs：仍在途才 FAILED
      → 条件收敛 job_items
      → 锁内联动 task FAILED
    → 单作业 RuntimeException 回滚并继续；游标仍前进
  → Runner 全部结束后才 ApplicationReadyEvent
```

证据：`RunnerActiveKeyCleanupRunner.java:26-55`；`JobHeartbeatRegistry.java:588-738`；`StaleJobRecoveryRunner.java:69-180`；`JobRepository.java:32-58,136-142`。没有全局恢复扫描锁，但终态条件 UPDATE 提供“先到者胜”。

### 3.5 `JobWatchdog` 与共享心跳

**看门狗**

```text
@PostConstruct JobWatchdog.start()
  → 本实例 job-watchdog 单 daemon 线程，10s fixed-delay
  → scanSafely() 兜住 Throwable
  → scan()
    → DB findByStatusIn(PENDING,RUNNING)：无分页完整 Job 列表
    → 回收本地 judged/failClosed 记忆
    → 每 job 计算绝对年龄；RUNNING 再判共享心跳
      → 本实例 held/active 直接按活
      → 否则 GET job:beat；无则 GET job:runner-active
      → beat 在=ALIVE；仅 anchor 在=STALE；都无=ABSENT；异常=UNAVAILABLE
    → timeout 或 dead
      → SET job:deadjudge:<jobId> NX PX 90s
      → ACQUIRED 才写 validation_issue
      → JobTerminalWriter.complete()
        → JobProgressService.finish()：REQUIRES_NEW、条件终态 + open items 收敛
        → 实际写终态才锁 task、联动 task、发 JOB_DONE
        → 所有异常在 writer 内被吃掉并只记日志
      → JobCancellationRegistry.cancel()：写 Redis 权威取消旗标
      → 心跳判死 revokeRunner()；超时仅 clear() 本地心跳
  → 无业务返回值；指标通过 metrics() 读取
```

证据：`JobWatchdog.java:145-231,256-402`；`JobHeartbeatRegistry.java:492-555`；`JobTerminalWriter.java:79-145`；`JobProgressService.java:137-165`。

**共享心跳生产线**

```text
JobService 创建作业事务
  → beginRunner()
    → SET runner-active 长 TTL
    → SET beat 90s
    → 写失败则不启动作业并回滚
    → 本地 activeRunners 登记 + ensureTicker()
  → job-heartbeat-ticker 单线程按 beatTtl/3 fixed-delay
    → 每 active job：SET runner-active → SET beat
  → AsyncJobExecutor.finally
    → endRunner()：删本地登记，按 beat→anchor 顺序清共享键，空闲则停 ticker
```

证据：`JobService.java:208-230`；`JobHeartbeatRegistry.java:283-345,389-474,764-868`；`AsyncJobExecutor.java:28-45`。

### 3.6 readiness 与关闭

```text
GET /health/ready
  → HealthController.ready()
  → ReadinessService.check()
    → draining 时直接 503，不探依赖
    → 否则 ReadinessProbeRunner.probe(全部 probes)
      → 固定 4 线程 + ArrayBlockingQueue(8)
      → DB：SELECT 1，query timeout 2s
      → storage：目录存在/可写，native I/O 可能不可中断
      → seed：本实例 SeedStatus
      → redis：RedisAvailability.isAvailable()，一次 PING；非硬依赖
      → 所有 Future 共用 System.nanoTime 截止时刻，超时 cancel(true)
    → 任一 required DOWN ⇒ HTTP 503；否则 200
```

证据：`HealthController.java:53-70`；`ReadinessService.java:130-185`；`ReadinessProbeRunner.java:66-209`；各 probe 实现。

```text
SIGTERM/ContextClosedEvent
  → 最高优先级 ShutdownDrainGate.drainBeforeConnectorStops()
    → ReadinessService.markDraining()（永久终态）
    → 按 System.nanoTime 切片等待 drain-window
  → 继续事件扇出与 Spring graceful shutdown
  → bean destroy
    → JobWatchdog/KbService/JobHeartbeatRegistry/SessionPurgeRetryRunner 等 shutdownNow()
    → StartupResumeRunner.stopRuntimeSweep() 只停周期 executor
    → LeaseService 停续租、尽力释放持有租约、关续租池
    → AI/业务任务池按各自 awaitTerminationSeconds 等待
```

证据：`ShutdownDrainGate.java:60-86`；`ReadinessService.java:101-128`；各 `@PreDestroy`；`AiExecutorConfig.java:43-70,93-113,190-206`。后台调度与 Web drain 没有统一相位，见 MR-09。

### 3.7 其他 Runner、调度与轮询

- `DataSeedRunner`：每实例执行，但在事务内用 `app_lock` DB 行锁包住“判重+写入”，提交后才把本实例 seed 状态置 DONE/SKIPPED。证据：`DataSeedRunner.java:36-83`；`SeedLock.java:49-68`。
- `GlmSeedRunner`：`ApplicationReadyEvent` 同步调用 `GlmSeedService.seedIfAbsent()`；同样使用 DB 行锁，失败只把本实例 GLM 状态记 FAILED，按既有设计仍 ready。证据：`GlmSeedRunner.java:33-46`；`GlmSeedService.java:52-79`；`SeedStatus.java:95-105`。
- `AiConfigSeeder`：每实例执行 DB count/条件写；正常冷启动由 provider/model 唯一约束处理竞态，只有实际写才 bump DB 版本并 `INCR ai:cfg:ver`。证据：`AiConfigSeeder.java:78-133,150-200`。`seed-force=true` 或并发补不完整种子时没有专用行锁，可能多写/多 bump；这是低风险的重复工作，不与 BASE/GLM 的强单飞混同。
- `KbService`：每实例 `@PostConstruct` 建一条 `kb-refresh` 线程，立即首次刷新，之后每 10m 刷新；缓存源另每 30s 读版本。两条任务同一线程、fixed-delay，因此本实例不并发。每实例本地 Lucene 重建是必要工作；HTTP 回源由 Redis token 锁尝试单飞。证据：`KbService.java:163-219,222-278`；`CachedKbSource.java:140-229`。
- `LeaseService`：进程内 2~4 条续租线程，按 `(key,owner)` 去重，fixed-rate；每任务自身不并发。证据：`LeaseService.java:1010-1023,1364-1409`。
- `CancelledRunFinalizer`：每 run 的有界交棒/指数退避，不是全局扫描；`inFlight` 按身份+runId 去重，关闭后不再派发。证据：`CancelledRunFinalizer.java:177-184,229-257,281-345,387-467`。
- `StreamWatchdog`：每轮 1Hz 内存节拍，取消状态在独立 2 线程调度器上每轮 1Hz GET；同一任务 fixed-rate 不自我并发。证据：`StreamWatchdog.java:138-159,191-294`；`AiExecutorConfig.java:131-176`。
- `SessionGate`：每持有一把会话锁一个续期任务，共用单调度线程；脚本按 runId+token 比对，身份保存在 holder。证据：`SessionGate.java:247-253,571-592,727-765`。
- `TurnKeepalive`：每轮 5s 观测帧任务，可由 close/cancel 停止；不是跨实例全局工作。证据：`TurnKeepalive.java:144-197`。

## 4. 逐项问题

### MR-01 看门狗把一次终态写失败变成“不再重试”

- **严重度：高。确定性：代码证实。**
- **证据**：`JobWatchdog.java:214-216` 对已判 id 直接跳过；`JobWatchdog.java:349` 在任何 DB 落地之前加入 `judgedJobIds`；`JobWatchdog.java:373-376` 调用终态 writer。`JobTerminalWriter.java:103-145` 包住整个终态过程并捕获所有 `Exception`，不向调用方返回成功/失败。`JobWatchdog.java:244-254` 只在作业离开在途快照后回收该 id。
- **触发条件**：看门狗判定 timeout/dead 后，issue 之外的终态写、task 联动或事务提交发生暂时性 DB 故障；作业仍为 PENDING/RUNNING。
- **影响**：
  - 单实例：该 id 每拍被跳过，且作业仍在快照里，判重记忆不会回收；没有运行期重试。通常只能等重启后的 `StaleJobRecoveryRunner`，或执行线程恰好另走一条成功收尾。
  - 多实例：其他实例可在 deadjudge TTL 后补判，但不是可靠保证；若它们也失败，同样各自压判重。若第一实例已把 job 写成 FAILED、只是 task 联动失败，则所有实例都不再扫描该 job，task 不一致没有这条链的补偿。
  - 多线程：writer 吞异常使调度线程无法区分“已被别人收终态”和“本次设施失败”。
- **根因**：把“issue 防重”和“终态收敛完成”合成一个永久本地判定；同时 writer 的 API 消除了失败信号。
- **最佳修复**：让 `JobTerminalWriter.complete` 返回明确结果（`WROTE / ALREADY_TERMINAL / RETRYABLE_FAILURE`），不要用吞异常作为终态协议。将 issue 去重与终态重试拆开：issue 用持久唯一键（例如 jobId+verdict 类型）或单独 `issueWritten` 标记；终态失败不得进入“永不重试”集合。对 job 已终态但 task 联动失败的场景增加可重试补偿记录/扫描。
- **备选取舍**：最小改法是在 `JobWatchdog.judge` 中保留 issue 判重、每拍仍调用幂等 terminalWriter；改动较小，但若 writer 继续吞异常，观测和 task 部分失败仍不完整。只在 catch 中 remove `judgedJobIds` 不可行，因为当前调用链没有异常可捕。
- **修改点/影响面**：`JobWatchdog`、`JobTerminalWriter` 返回值、可能的 `validation_issues` 唯一约束及迁移、task 联动补偿；所有执行器共用 writer，需保持自然完成/取消先到者胜语义。
- **后续验证（仅方案）**：分别注入 job 条件 UPDATE 失败、task 锁失败、task 联动失败；断言下一拍会重试且 issue 不重复，最终 job/item/task 一致。再跑单实例与双实例交叉失败，不只验证“日志有错误”。

### MR-02 共享心跳读取存在 TOCTOU，可误杀刚续拍的远端活作业

- **严重度：高。确定性：代码证实时序；发生频率未实测。**
- **证据**：`JobHeartbeatRegistry.java:492-510` 先 GET beat，beat 不存在后再 GET anchor；`JobWatchdog.java:282-311` 直接把该快照折成 STALE；`JobWatchdog.java:328-399` 拿 deadjudge 后不再读 beat，就写 issue/FAILED/取消。生产 ticker 在 `JobHeartbeatRegistry.java:397-405` 对每个 job 先写 anchor、再写 beat。
- **触发时序**：B 第一次 GET beat 时键恰好过期或尚未续上；A 随后续上 anchor 和 beat；B 第二次 GET anchor 读到存在，返回 STALE。即使 B 判死时 beat 已重新存在，也没有复核。Redis 延迟、GC 停顿、滚动重启和 Cluster 单槽超时都会扩大窗口。
- **影响**：单实例本机 runner 通常先被 `isRunnerActive` 拦住，主要风险在跨实例。误写 FAILED 后，A 的迟到自然完成由 DB 条件 UPDATE 拦住；取消旗标可让 A 在下一分片停止，但用户看到的是活作业被误判。Cluster 不改变该逻辑，只增加两键可能位于不同 master、读取耗时不同的事实。
- **根因**：把两个非原子读取产生的历史快照当成当前判死事实；判死锁只防其他判死者，不与 runner 续拍互斥，也没有 fencing。
- **最佳修复**：至少在获取 deadjudge 后、任何不可逆写入前重新读取 beat，并再次核对 DB 仍在途；若 beat 恢复则放弃判定。更完整的方案是给 runner 分配 epoch/token，续拍与判死使用同一所有权协议，判死后的取消/终态带 fencing，旧 runner 必须停写。
- **备选取舍**：将读取顺序改成 anchor→beat 可消除上述特定“续拍落在两读之间”的假 STALE，实现简单，但仍不能消除 beat 在最后读取后恢复、以及判死锁等待期间的竞态，因此只能作为补充，不应作为完整修复。
- **修改点/影响面**：`JobHeartbeatRegistry` 增加判死后复核/必要时释放判定锁；`JobWatchdog.judge` 增加 abort 结果与指标；若做 fencing，还需改 `JobProgressService`、各 runner 和取消协议。
- **后续验证**：用可控 Redis/线程闩锁精确复现“首读无 beat→对端续拍→次读有 anchor”，断言不写 issue、不写 FAILED、不取消。再覆盖判死锁等待期间续拍、Cluster 两键不同槽、A 真正死亡三种分支。

### MR-03 续跑恢复发现面无界，动作预算不能限制 Redis 扫描成本

- **严重度：高。确定性：代码证实成本模型；实际延迟取决于索引和快照规模。**
- **证据**：`ResumeService.java:479-493` 系统面遍历 `store.allRunRecords()` 全量结果；`RunStore.java:3163-3189` 对 `ai:runs` 使用一次 `SMEMBERS`、逐成员 `EXISTS` 并排序；随后 `ResumeService.java:583-607` 每轮仍读快照、pending 和取消/活动信息。`StartupResumeRunner.java:581-600` 的 limit/attemptLimit 只在候选已经全部发现之后进入循环时生效。
- **触发条件**：`ai:runs` 有大量未过期终态/挂起/在途记录，或死成员积累；每 120s 系统 sweep、启动扫描或用户手工扫描。
- **影响**：
  - 单 Redis：`SMEMBERS` 是 O(N) 单键命令，大结果在网络和 JVM 内一次性物化；随后 N 次串行 EXISTS 与每轮多键读占住扫描线程。
  - Cluster：`ai:runs` 仍是单个 SET，全部成员集中在一个 master；其他键虽分布到各槽，但不会分摊该 SET 的读取。逐键命令没有 CROSSSLOT，却是热点加大量 RTT。
  - 多实例：正常系统扫描由全局锁单飞；锁不可用时 `StartupResumeRunner.java:936-973` 退到进程内单飞，多实例可重复发现。手工扫描按身份使用不同锁，也可与系统扫描并行；每轮租约主要防双驱动，不消除扫描压力。
  - 删除接口：purge 自己也做 Cluster SCAN 和多键串行删除；恢复扫描造成的 Redis 热点/队列可间接放大其耗时，但本报告没有实测证明它就是“超过 30 秒”的唯一根因。
- **根因**：把“单拍动作数有界”误当成“整拍工作量有界”；发现、清理和报告 `skipped` 都可随全索引增长。
- **最佳修复**：把恢复发现改为真正分页/流式管线：`SSCAN ai:runs` + 有界游标持久化、每页限量，按页做批量/pipeline EXISTS，再只取恢复所需轻量字段；将清理游标与动作轮转游标合并为一个可恢复进度。长期更优是维护带过期/状态的恢复索引，而不是每拍遍历全部终态记录。
- **备选取舍**：只做 SSCAN 仍要面对 SET 不排序和成员变化导致的重复/遗漏，需要以“多拍最终覆盖”而非严格快照为契约；改 ZSET（score=active/update 时间）便于按序分页，但需要迁移和双写。pipeline 能减少 RTT，却不降低 SMEMBERS 本身复杂度。
- **修改点/影响面**：`RunStore.allRunRecords/pruneRunIndex`、`ResumeService.discoverRuns/recoveryRun`、`StartupResumeRunner` 游标/报告、`AiProperties` 新页大小与观测；索引格式变更还需迁移。
- **后续验证**：构造大量终态、活跃、死成员混合索引，分别测 standalone/Cluster 每拍命令数、单键最大返回字节、扫描时长和后排任务接管延迟；验证预算用尽有界、多拍无永久饿死、扫描中增删成员不造成永久漏跑。

### MR-04 deadjudge 是短租约而非完整判定事务，可能重复 issue

- **严重度：中。确定性：代码证实。**
- **证据**：`JobHeartbeatRegistry.java:251-254,518-555` 以 beat TTL（默认 90s）做 `SET NX PX`，无续租，成功后不删除；`JobWatchdog.java:328-399` 在锁内依次做 DB issue、终态 writer、Redis cancel 和清理，没有任何一步携带 deadjudge token。`validation_issues` 只有普通索引，没有判定唯一约束：`db/migration/V1__schema.sql:158-165` 与 PostgreSQL 对应文件同形。
- **触发条件**：首次判定的 DB/Redis/SSE 工作超过 90s，或实例在 issue 写入后、终态前崩溃；另一实例在 TTL 后获得新 deadjudge。
- **影响**：重复 ERROR issue、重复指标和重复取消/清理；job 终态有条件 UPDATE，通常不会重复翻写，但 issue 没有等价保护。单实例进程崩溃后也有其他恢复路径，不能宣称必然重复；多实例 TTL 接管是直接风险。
- **根因**：把 TTL 锁当作“永久只判一次”证据，且 issue 与终态不共享持久幂等键。
- **最佳修复**：为 `(job_id, verdict_type)` 建持久唯一幂等记录，可用 DB 唯一约束或判定状态表；终态恢复可重试。锁只负责降低重复工作，不承担正确性。若判定可能很长，为锁增加 token 续租，并在写前验证。
- **备选取拾**：仅延长 TTL 会降低概率但不能解决崩溃/长任务；仅续租仍不能防进程在 issue 后死亡。因此持久幂等优先于调 TTL。
- **修改点/影响面**：`JobHeartbeatRegistry`、`JobWatchdog`、validation issue schema/仓储、判定指标；迁移需兼容既有重复行。
- **后续验证**：在 issue 后、终态前分别模拟进程崩溃与 90s 停顿，另一实例接管；断言最终只有一条对应判定 issue，job/task 仍收敛。

### MR-05 看门狗每实例每 10 秒全量水合在途实体

- **严重度：中。确定性：代码证实。**
- **证据**：`JobWatchdog.java:200-230` 明确要求无上限 `findByStatusIn`；`JobRepository.java:57-58` 返回完整 `Job`；`Job.java:39-47` 含 CLOB `resultJson` 和 EAGER `items`。
- **触发条件**：共享 DB 中 PENDING/RUNNING 作业多、单作业 items 多或 resultJson 大；N 个实例同时运行。
- **影响**：每实例都付出 DB 查询、实体/集合水合和堆占用；EAGER items 的额外 SQL 数量受 Hibernate 抓取策略影响，但至少集合并非恢复判定所需。fixed-delay 只防本实例重入，不防 N 实例重复扫描；长扫描会占住唯一 `job-watchdog` 线程，推迟本实例下一拍。
- **根因**：为安全回收本地判重集合而要求全量快照，同时直接复用了完整实体仓库查询；扫描单飞只在判死动作层，不在观察层。
- **最佳修复**：改为标量投影和 keyset 分页，字段只含 id/status/createdAt/startedAt/progress/total/taskId/jobType 等真正使用列；本地集合回收不能再用“本页没出现=永不回场”，应按持久游标完成一轮完整迭代后再回收，或直接依据 DB 当前终态/有界 TTL 管理判重。
- **备选取舍**：扫描级全局锁能减少 DB 重复，却会让其他实例失去本地观测并且锁故障时减少安全网；不建议用它替代分页。只把 items 改 LAZY 是独立实体优化，仍不能解决全量在途扫描。
- **修改点/影响面**：`JobRepository`、`JobWatchdog.scan/retainTracked`、扫描指标与游标；保持 MR-01 修复后的重试语义。
- **后续验证**：在大量在途作业和多大items下比较 DB 返回列/行数、堆占用、单拍时长；双实例验证不错误回收待重试 id，分页期间作业终态/新增均不漏判。

### MR-06 启动恢复重复扫描，并在 DB 行锁内等 Redis

- **严重度：中。确定性：代码证实。**
- **证据**：`StaleJobRecoveryRunner.java:98-129` 每实例无全局锁地扫全部在途页；`StaleJobRecoveryRunner.java:141-178` 每作业事务先 `tryLockForUpdate`，再 `isRunnerAlive`；`TaskService.java:100-118` 证实这是 `SELECT ... FOR UPDATE`；Redis 读在 `JobHeartbeatRegistry.java:363-386`。
- **触发条件**：多实例同时启动、共享 DB 有在途作业、Redis 慢/不可用，或多实例争同一 task 行。
- **影响**：重复 DB 扫描和行锁争用；每作业持 task 行锁等待一次 Redis RTT，故障时可接近单命令超时后继续下一作业。ApplicationRunner 位于 ready 之前，启动耗时和不可接流窗口随候选数放大。Cluster 下 runner-active 键分散，但每作业仍是一次独立网络读。
- **根因**：为统一 task→job 写锁顺序，把外部活跃探测也放入行锁临界区；没有区分“廉价候选过滤”和“必须锁内复核”。
- **最佳修复**：在事务外先做 runner-active 预过滤，锁内仍保留一次必要复核以关闭竞态；对启动恢复增加有界批处理和锁等待指标。若增加全局恢复租约，租约不可用时不要无锁执行破坏性恢复，可交给运行期 watchdog。
- **备选取舍**：只加全局锁可减少重复扫描，但单实例长恢复仍阻塞启动，且锁故障带来漏恢复；条件 UPDATE 已能解决重复终态，因此优先缩短临界区而非先加锁。
- **修改点/影响面**：`StaleJobRecoveryRunner.recoverOne`、可能的批量心跳读取接口、启动观测；必须保留 task→job 顺序和 Redis 不可知 fail-closed。
- **后续验证**：双实例同时启动并制造相同候选、Redis 2s 延迟、task 删除竞争；记录行锁持有时长和 ready 时间，确认活跃作业不失败、死作业只由一个写者实际迁移。

### MR-07 手工 DELETE 与自动 purge 可并发执行同一删除主体

- **严重度：中。确定性：代码证实控制流；具体数据后果受各删除操作幂等性影响。**
- **证据**：自动轮次锁仅在 `SessionPurgeRetryRunner.java:203-236`。手工链 `AiSessionPurgeController.java:71-103` 直接调 `purge`，不抢该锁；`AiSessionPurgeService.java:350-418` 与 retry 共用删除主体。已有 pending 时 `SessionPurgePendingStore.java:322-340` 复用同一 purgeId 并把 nextAt 重置为 now；`AiSessionPurgeService.java:429-458` 的 retry 入口只检查 pending/purgeId，没有执行中 token。
- **触发条件**：后台 retry 正在枚举/删除时用户再次 DELETE；或手工 DELETE 把记录重置为 due 后自动线程立即拾取。
- **影响**：同一 purgeId 可在两个线程/实例同时做 Cluster SCAN、DB 删除、run 取消、附件文件删除和多键 DEL。 pending CAS 能防旧 owner 清掉新台账，但这里两者本来使用同一 purgeId，CAS 不构成执行互斥。重复删除多按幂等目标设计，主要确定风险是重复 I/O、响应变慢和交错错误报告；不能在没有更细证据时宣称一定误删其他会话。
- **单例/Cluster/多实例**：单实例多线程也可发生；多实例跨节点更不可见；Cluster 下两次全 master 扫描会直接加倍相关压力。
- **根因**：全局锁的粒度是“一轮自动批处理”，而正确性需要粒度是“某个 pending 正在被执行”；手工入口完全在锁外。
- **最佳修复**：为每个 pending 增加 `executionOwner/leaseUntil` 的 Redis 原子认领与续租，手工和自动都走同一入口。手工请求若遇执行中，可返回明确的 `inProgress=true`/202 或短暂有界等待后返回当前状态，不要并发跑完整主体。
- **备选取舍**：手工直接复用全局轮次锁会把所有会话串行到一次手工请求后，锁等待长且吞吐差，不推荐。仅依赖 purgeId CAS 改动小，但无法阻止同 owner 并发。
- **修改点/影响面**：`SessionPurgePendingStore` 记录结构与 Lua、`SessionPurgeRetryRunner`、`AiSessionPurgeService.purge/retry`、`AiSessionPurgeController` 响应；需处理执行者死亡后的租约接管。
- **后续验证**：用枚举闩锁让自动 retry 停在 SCAN 中，再从同/异实例发 DELETE；断言删除主体只一个执行者，HTTP 明确返回进行中，租约到期后能接管且不重复报错。

### MR-08 purge 卡死接管故意放锁，但原执行体没有 fencing

- **严重度：中。确定性：代码证实。**
- **证据**：`SessionPurgeRetryRunner.java:203-235` 的续租判据含五分钟的推进窗；`SessionPurgeRetryRunner.java:253-263,360-379` 判死后停止剩余候选，但正在执行的候选仍在原调用栈；`LeaseService.java:1127-1133` 在 alive=false 时主动 release。类注 `SessionPurgeRetryRunner.java:58-63` 也明确承认正在执行的一条可与新 owner 并发。
- **触发条件**：单会话 retry/verify 卡在 DB、共享文件、Redis 或 native I/O 超过 5 分钟；续租线程判死并放锁；另一实例从仍然 due 的 ZSET 取到同一 pending。
- **影响**：同一记录的 DB/文件/Redis 删除并发；原执行体后续 pending 更新即使发现 stalled，也没有新的 owner token 可拒绝它。新旧执行体可互相覆盖 attempts/errors 或交错完成/退避，尽管 purgeId CAS 与删除幂等提供部分保护。
- **根因**：把“停止续租”当成“停止工作”；没有记录级认领 epoch，也没有让原调用在关键写点前验证所有权。
- **最佳修复**：与 MR-07 共用记录级执行租约，但增加 epoch fencing：pending 的状态更新必须带执行 epoch；DB/文件等不可原子纳入 Redis 的步骤要拆分短步骤、记录检查点，并在每步前检查取消/所有权。对于不可中断 I/O，不承诺强杀，只承诺旧执行体不能继续推进台账。
- **备选取舍**：把 stall 判据改为无限续租会避免接管双跑，却把一次卡死变成全局永久锁，不可取；仅把 5 分钟调大只延后问题。
- **修改点/影响面**：与 MR-07 相同，另涉及 `LeaseService` 的存活回调语义和 `AiSessionPurgeService` 分阶段检查点。
- **后续验证**：让旧 owner 在文件删除前卡住超过窗口，新 owner 接管后恢复旧线程；断言旧线程不能改写 pending/守卫/完成状态，最终记录只反映新 owner 的结果。

### MR-09 后台运行器没有统一关闭相位，启动裸线程还游离在停止逻辑之外

- **严重度：中。确定性：代码证实生命周期；是否产生半写取决于关闭时序。**
- **证据**：
  - `StartupResumeRunner.java:275-291` 新建裸 `ai-startup-resume` daemon 线程，没有保存句柄；`StartupResumeRunner.java:1175-1192` 的 `@PreDestroy` 只取消 `sweepFuture/sweepExecutor`。
  - `SessionPurgeRetryRunner.java:177-183`、`JobWatchdog.java:161-170`、`JobHeartbeatRegistry.java:463-474`、`KbService.java:191-209` 都是 shutdownNow/清理，不 await 在飞任务。
  - `ShutdownDrainGate.java:60-86` 只标 readiness 并等待连接器摘流；没有在 ContextClosedEvent 起始时停后台扫描。
- **触发条件**：启动延迟期间收到停机；drain/graceful 期间到达下一扫描拍；bean destroy 时某轮正在 DB/Redis/文件调用。
- **影响**：后台任务可在 readiness 已 503 后继续启动新工作；destroy 返回后在飞 daemon 工作仍可能使用正在关闭的 Redis/DB/文件资源。启动裸线程甚至可在 `stopRuntimeSweep` 之后睡完再开始扫描。各业务的条件写/CAS 能限制部分后果，但关闭日志、错误响应和任务接管时点会不稳定。
- **多实例**：其他实例可接管 durable 工作，但当前实例没有清晰交棒点，增加 MR-08 一类旧/新执行体交叠。单实例则更容易表现为关闭中异常或重启后重复恢复。
- **根因**：Web 优雅关闭、后台调度器、业务运行池分别管理；没有“停止接新后台工作→有界等待在飞→关闭共享设施”的统一生命周期。
- **最佳修复**：用 `SmartLifecycle`/明确 phase 或一个协调器，在 drain 开始先设置各 runner `accepting=false` 并取消未来拍；对在飞扫描/清理有界 await，超时后交 durable 租约给别实例。启动延迟线程改为由同一 scheduler 提交并保存 Future，关闭时取消；共享 Redis/DB 资源应在这些运行器之后关闭。
- **备选取舍**：只在各 `@PreDestroy` 加 await 可缩短问题，但仍覆盖不到 drain 期间新拍和裸线程；全局限定一个 shutdown 管理器改动更大，却能统一观测和顺序。
- **修改点/影响面**：六个核心类、`KbService`、`JobHeartbeatRegistry`、`ShutdownDrainGate`、配置的总停机预算；AI/业务池现有 10s/30s 等待必须纳入整体宽限期。
- **后续验证**：分别在启动 sleep、候选发现、purge 文件删除、watchdog 终态写期间发关闭；断言不再开新拍、在飞任务有明确完成/交棒结果、Redis 关闭后无迟到的后台命令。双实例验证接管不双跑。

### MR-10 readiness 并发请求可自我放大为池饱和，取消任务还占队列

- **严重度：中。确定性：代码证实。**
- **证据**：`ReadinessService.java:130-168` 每次请求都现算，无合并/短缓存；`ReadinessProbeRunner.java:82-85,102-143` 每请求提交全部维度到固定 4 线程/队列 8；`ReadinessProbeRunner.java:145-173` 超时仅 `Future.cancel(true)`。普通 `ThreadPoolExecutor` 配合 `ArrayBlockingQueue` 不会因为 cancel 自动把尚未执行的 Future 从队列摘除。
- **触发条件**：DB/NFS/SMB 等探针慢或不可中断，同时 LB、编排器和人工多次并发请求；前序请求超时后继续以同样频率探测。
- **影响**：4 个线程被慢 I/O 占用后队列迅速填满；已超时且取消的排队任务仍可占槽直到 worker 取到它们，新维度被 AbortPolicy 拒绝并记 DOWN。required DB/storage 的假 DOWN 会使实例 503；若共享依赖同时慢，多实例可能一起摘流。Redis 是 required=false，不直接拉低 ready，但会标 AI degraded。
- **根因**：每个 HTTP 请求独立制造一组探针工作；调用方有界返回被正确实现，但底层工作没有去重，取消也未配套队列清理。
- **最佳修复**：按实例对一轮探测做 single-flight，或使用很短的快照 TTL+后台刷新，让并发请求共享同一组 Future；超时取消后从队列移除未开始任务（自定义 Future/队列移除或有控制地 `purge()`），并记录 submitted/running/queued/cancelled/saturated 指标。
- **备选取舍**：增加线程/队列只推迟饱和，且增加 DB/共享卷压力；长缓存会延迟真实 DOWN 和 drain 以外的恢复，不建议超过 LB 可接受时效。draining 快路径仍必须直接 503。
- **修改点/影响面**：`ReadinessProbeRunner`、`ReadinessService`、可能的 `ReadinessProperties` 快照间隔；保持每请求共享 2s 截止和饱和即 DOWN。
- **后续验证**：四个及以上并发 ready 请求配合不可中断 storage 桩，观察队列中取消任务是否及时移除、后续依赖恢复后是否能重新 UP；确保同一时刻只一组真实 DB/Redis/存储探测。

### MR-11 启动扫描发现失败后不重试；关闭 sweep 时会留下启动盲区

- **严重度：中。确定性：代码证实；默认 `runtime-sweep-enabled=true` 是明确缓解。**
- **证据**：`StartupResumeRunner.java:295-306` 在真正扫描前把 `scanned` 置 true；`StartupResumeRunner.java:316-334` 发现异常后生成 failed 报告并返回；`StartupResumeRunner.java:363-379` 启动重试只处理 `deferred`，不处理 discovery error。配置允许关闭运行期 sweep：`application.yml:263-271` 与 `StartupResumeRunner.java:1147-1157`。
- **触发条件**：启动时 Redis/Cluster 暂时故障，候选发现失败，同时运行期 sweep 被关闭；AI 后续恢复。
- **影响**：启动入口不会自动再扫；待恢复轮次只能等手工 POST、其他实例成功扫描或下次重启。多实例提高其他实例覆盖概率，但不能把本实例状态当成全局完成证明。单实例直接表现为漏自动恢复。
- **根因**：`scanned` 同时表示“已经尝试过”和“已有可靠结果”，失败路径没有重置或排程重试；启动重试仅覆盖锁竞争。
- **最佳修复**：将一次性启动扫描改成有界基础设施重试：发现失败保持可重试状态，按指数退避+抖动执行若干次；是否成功由真实报告决定。若产品要求最终一定有兜底，可在配置校验中禁止 `on-startup=true` 与所有运行期恢复入口同时缺失。
- **备选取舍**：失败后简单 `scanned=false` 但不再触发没有作用；无限重试会把持续故障变成永久后台压力，不建议。保留手工 force 作为最后入口。
- **修改点/影响面**：`StartupResumeRunner.startupScan/scan`、启动线程调度（可与 MR-09 一起修）、失败指标和配置校验。
- **后续验证**：首次 SMEMBERS 失败、第二次成功，分别覆盖 sweep 开/关；断言有界重试后接管一次、失败次数有上界、关闭过程中不再重试。

### MR-12 作业年龄和 purge 卡死窗依赖可跳变墙钟/实例时区

- **严重度：中。确定性：代码证实依赖；误判需要部署时钟或时区条件。**
- **证据**：`JobWatchdog.java:256-265` 用 `Clock.millis()` 减 DB `LocalDateTime`，并通过 `ZoneId.systemDefault()` 转 epoch；`Job.java:49-57` 的时间列确实是无时区 `LocalDateTime`。`SessionPurgeRetryRunner.java:208-210,263,367-378` 的候选推进 deadline 用 `System.currentTimeMillis()`。
- **触发条件**：实例默认时区不一致、NTP 回拨/跳变、DB 写入时间和读取实例对 LocalDateTime 的解释不同。
- **影响**：作业可能被提前判超时或长期不判；purge 卡死窗在时钟回拨时延长、前跳时提前放锁，放大 MR-08。共享 beat 的存在性由 Redis TTL 决定，不受该问题影响，不能把两条判据混为一谈。
- **根因**：跨实例持久时间使用本地日期时间，运行间隔又使用非单调墙钟。
- **最佳修复**：DB 时间和计算统一为 UTC `Instant`/带时区类型；持久时间与单进程超时间隔分开。purge stall 和一切“距现在多久”的进程内预算改用 `System.nanoTime()`；分布式到期时间若继续用 epoch，应明确 NTP 要求或采用 Redis 服务端时间。
- **备选取舍**：只统一 JVM `-Duser.timezone=UTC` 和 NTP 是低成本运维修复，但代码仍依赖外部纪律；改 schema/类型更彻底，迁移成本高。
- **修改点/影响面**：`Job` 实体、写入时间的各 service、`JobWatchdog.ageMs`、H2/PostgreSQL 迁移、`SessionPurgeRetryRunner`；AI zombie 也使用墙钟，需作为相邻面一并评估而非本次直接改。
- **后续验证**：双实例不同时区读取同一作业，结论必须一致；注入时钟前跳/回拨验证 purge 不会提前/无限持锁；已有历史时间的迁移须做兼容核对。

### MR-13 KB 刷新锁不覆盖长刷新的整个可靠所有权期

- **严重度：中。确定性：代码证实锁边界；实际重复回源需要刷新超过 TTL。**
- **证据**：`CachedKbSource.java:157-189,192-217` 用 `kb:cache:refresh:lock` 包住 HTTP 回源和 `syncToCache`，但只 finally unlock，没有续租；锁 TTL 来自 `application.yml:343-348`，默认 30s。`CachedKbSource.java:261-319` 的同步可逐文档回源、写 Redis、回收，工作量随文档数和源延迟增长。local 路径 `CachedKbSource.java:131-138` 不走该锁。
- **触发条件**：HTTP 源慢、变更多、逐文档同步超过 30s；另一实例随后获得锁。多实例 local 盘内容不一致时，各实例也会分别同步共享缓存。
- **影响**：重复上游请求和 Redis 写入，版本 bump/其他实例重建增多。`generation` 条件写与回收能阻止较旧代次覆盖较新代次，属于重要缓解；但不能消除重复 I/O。local 源若各盘内容不同，最后的较高 generation 成为缓存内容，这是部署一致性问题，不能仅靠锁修复。
- **根因**：把固定 30s TTL 当作刷新单飞上限，没有续租或按文档认领；local 共享缓存同步与 HTTP 回源的互斥范围不一致。
- **最佳修复**：HTTP 刷新使用唯一 token+有界续租，完成/失败按 token 释放；长同步按文档记录进度，避免一次全局长任务。local 多实例应指定共享权威源或仅允许一个同步者，其他实例只从缓存重建。
- **备选取舍**：generation fencing 已保证旧写不覆盖新写，若只关心数据正确性可保留现状并增加锁续租；把整轮刷新放 DB 行锁会引入长事务/跨资源等待，不推荐。
- **修改点/影响面**：`CachedKbSource`、`KbCache` 锁接口、`KbService` 停止配合、`application.yml` 锁/续租参数；local 部署文档与 guard。
- **后续验证**：让首轮 HTTP 同步超过 30s，第二实例到点启动；断言上游只有一个权威刷新者或旧者失去写权，缓存 generation/版本单调，其他实例最终索引一致。

### MR-14 purge 的实例节拍、全局锁和 due ZSET 没有抖动/故障退避

- **严重度：低。确定性：代码证实调度形状。**
- **证据**：`SessionPurgeRetryRunner.java:154-175` 每实例都是 10s 首延+30s fixed-delay；`SessionPurgeRetryRunner.java:213-220` 每拍都尝试同一 `ai:purge:retry:lock`；`SessionPurgePendingStore.java:402-410` 每获胜轮读同一个 `ai:purge:retry:due`。没有启动抖动或“锁设施失败后的额外周期退避”。
- **触发条件**：多实例同批启动、Redis 故障/恢复、大量实例同时到拍；持续有大量 due 项。
- **影响**：可预测的同拍锁争用；失败时各实例按各自固定周期重试。Cluster 下锁和 due 都是单键热点，正常 30s 频率本身不高，因此不定为高收益/高严重度问题。全局每轮 10 候选还意味着清理吞吐受单获胜者串行限制。
- **根因**：以低频周期足够为默认假设，没有做实例错峰和基础设施失败退避。
- **最佳修复**：首次和周期加入有界确定性抖动；锁获取 UNAVAILABLE 时按实例做有界退避，HELD_ELSEWHERE 则正常下拍。扩容前先根据 due 积压测算“每 30s 最多 10 候选”的全局吞吐，而非仅增加实例。
- **备选取舍**：提高候选上限会减少积压，却延长全局锁持有和单拍 Cluster/DB/文件压力；分片 due 队列可提高吞吐，但需要记录级锁和更复杂公平性。
- **修改点/影响面**：`SessionPurgeRetryRunner` 调度与指标，必要时 `SessionPurgePendingStore` 分片；不改变 recordFailure 已有的每记录指数退避。
- **后续验证**：多实例同拍启动和 Redis 故障恢复，统计每拍 acquire 次数/冲突率/单 master QPS；制造超过单拍容量的 due 积压，验证最终收敛且无候选饿死。

### MR-15 Redis readiness/前置探活不等于全 Cluster 可用

- **严重度：低。确定性：代码证实覆盖边界；具体 Lettuce 内部节点选择未在本次运行验证。**
- **证据**：`RedisAvailability.java:72-100` 只执行一次 `RedisConnection.ping()`；`RedisReadinessProbe.java:54-59` 直接把该结果映射为 Redis UP/DOWN；删除端点在 `AiSessionPurgeController.java:77-79` 也把它当前置闸。代码没有逐 master PING、slot 覆盖检查或试读目标键。
- **触发条件**：Cluster 部分 master/槽不可用，但本次 PING 调用成功；或目标会话键恰好落在故障槽。
- **影响**：readiness 可显示 Redis UP/AI 未 degraded，删除请求通过前置后才在具体 SCAN/键命令失败。由于 Redis 探针 required=false，这不会错误摘流；主要问题是观测精度和“前置通过”的误解。
- **根因**：把单连接健康检查当作整个分布式键面的可用性证明。
- **最佳修复**：保持轻量 PING 作为进程级健康指标，但响应/文档明确其覆盖范围；对 AI 能力可增加带节流的逐 master 检查或基于实际命令失败的槽位降级指标。删除等具体接口仍以目标键操作的 fail-closed 结果为最终判定。
- **备选取舍**：每次 ready 逐 master PING 会增加 Cluster 压力，而且不能证明未来目标键一定可用；不建议为追求绝对语义在 2s 探针里做全量命令。
- **修改点/影响面**：`RedisAvailability` 状态模型、`RedisReadinessProbe` 文案/指标、AI capability 观测；无需让 Redis 变成全站 required 依赖。
- **后续验证**：Cluster 单 master 故障，分别检查 PING 维度、AI capability、落在健康/故障槽的删除请求；断言观测不声称全集群健康，具体失败仍返回可重试错误而非 complete。

## 5. 未发现问题的关键路径与已有防护

以下是本次代码阅读中确认的防护，不表示经过运行测试：

1. **续跑扫描正常路径有进程内+跨实例单飞和续租**：`StartupResumeRunner.java:915-985` 使用本地 ReentrantLock、每拍唯一 owner 的 Redis 租约；`1080-1099` 先停续租再条件释放。运行期拍不等待锁，避免调度线程为竞争停留。
2. **每轮续跑还有独立租约/fencing/会话门**：`ResumeService.java:825-935,1102-1124,1321-1335` 与 `RunStore.java:1224-1248` 共同阻止已丢租者继续推进。扫描锁不是唯一防双驱动措施。
3. **续跑后台身份不是继承裸线程上下文**：`StartupResumeRunner.java:1213-1246` 按索引/台账记录身份开作用域；`ResumeService.java:1752-1802,2433-2444` 工作线程又按快照身份恢复并清理；`LeaseService.java:1108-1113` 续租也携带身份。未发现系统扫描把上一租户身份带给下一轮的直接路径。
4. **purge pending 有持久发现与有限重试**：`SessionPurgePendingStore.java:390-459` 使用有界 ZRANGEBYSCORE，不做 KEYS/全库 SCAN；`502-531,830-833` 有 10s..300s 指数退避；耗尽后保留 durable 台账、摘发现项，手工 DELETE 可重开。
5. **purge 的 Cluster 扫描覆盖所有 master且失败不冒充完整**：`RedisClusterScanSupport.java:98-150` 只扫 master、逐 cursor close、任一节点失败上抛；删除侧逐键 DEL，未做跨槽多键 Lua。
6. **启动恢复 DB 扫描本身已做 keyset+标量投影**：`JobRepository.java:32-55` 每页 50 且 id 前进，不水合全库历史；`StaleJobRecoveryRunner.java:164-175` 条件 UPDATE 和条目收敛避免多实例重复翻写。MR-06 指的是剩余重复扫描与锁内 Redis，不否定这些已有改进。
7. **runner-active 启动清理有界且 Cluster 安全**：`JobHeartbeatRegistry.java:588-738` 用 SCAN 而非 KEYS，逐 master、总检视 512、按值原子 DEL；其他主机/活 PID/解析失败都保留到 TTL，属于保守方向。
8. **共享 beat 不用跨实例时间戳比大小**：`JobHeartbeatRegistry.java:479-514` 以 Redis TTL 的键存在性为主；ticker 与业务分片解耦。MR-02 是读取/判死竞态，不是“值内时间戳漂移”问题。
9. **job 终态先到者胜**：`JobProgressService.java:137-165` 仅在 PENDING/RUNNING 时条件 UPDATE；`JobTerminalWriter.java:113-140` 只有实际写终态才联动 task/发 JOB_DONE，能挡住自然完成与看门狗迟到写互相覆盖。
10. **readiness 调用线程有共享单调截止**：`ReadinessProbeRunner.java:102-173` 使用 `System.nanoTime()`，所有维度合计 2s，不降级到调用线程执行；固定线程与有界队列保证不无限建线程。MR-10 是底层工作合并/取消队列问题。
11. **readiness drain 是终态且摘流顺序正确**：`ShutdownDrainGate.java:60-86` 在连接器停前标 503并有界等待；`ReadinessService.java:101-128,135-169` drain 后不再探依赖。Redis required=false 是有意避免共享 Redis 故障把全部业务实例摘流。
12. **BASE/GLM 播种用 DB 行锁而非 Redis 锁**：`DataSeedRunner.java:47-62`、`GlmSeedService.java:52-79` 在事务内锁后判重，提交后更新本实例状态；多实例重复入口不会各自插同一份种子。
13. **AI 配置正常冷启动有唯一约束和提交后版本发布**：`AiConfigSeeder.java:112-131,150-200` 处理并发冲突，只有真实写才 bump/INCR。未把正常 no-op 每实例执行误报成重复写。
14. **KB 本地索引按实例重建但本实例不并发**：`KbService.java:173-185,287-387` 单线程 fixed-delay+ synchronized refresh；快照原子替换和引用计数保护在途查询。`KbCache.java:222-230,271-283` 另有 generation fencing 和 token 条件解锁。
15. **周期线程异常不会普遍杀死后续拍**：`StartupResumeRunner.sweepQuietly`、`SessionPurgeRetryRunner.runOnceSafely`、`JobWatchdog.scanSafely`、`KbService.refreshQuietly` 都兜住周期异常；fixed-delay 避免同一调度器同一任务自我并发。

## 6. 建议修复顺序与影响面汇总

1. **先修正确性**：MR-01、MR-02；随后 MR-04。它们直接影响 job 是否误判/是否还能收敛。
2. **统一销毁执行协议**：合并设计 MR-07、MR-08，避免分别加两把不一致的锁；同时评估 MR-14 吞吐。
3. **治理无界扫描**：MR-03、MR-05；MR-06 可随启动恢复一起缩短锁临界区。
4. **统一生命周期**：MR-09 与 MR-11 共用可取消启动调度器；所有后台停止纳入总停机预算。
5. **降低探针和辅助调度放大**：MR-10、MR-13、MR-15；MR-12 需要独立的数据时间迁移评估。

不建议的“快捷修法”：单纯增加锁 TTL、单纯加实例、单纯扩大看门狗/探针线 pool、把 Redis 改成 required readiness、让 purge 卡死后永久续锁。这些都只移动症状，且可能扩大故障半径。

## 7. 后续验证方案（只列方案，本次未执行）

### 7.1 静态与单元级

- 为 MR-01 建立 writer 三态结果和逐拍重试用例；覆盖 job 已终态但 task 未联动的补偿。
- 用线程闩锁确定复现 MR-02 两读之间续拍；同时验证真正进程死亡仍能判死。
- 为 deadjudge 增加 lease 到期/崩溃接管用例，断言 issue 持久去重。
- 为 pending 执行租约覆盖手工/自动、stall 接管、旧 owner 迟到写、请求身份恢复。
- 用假时钟/时区覆盖 MR-12；用受控调度器覆盖启动失败重试和关闭不再开新拍。

### 7.2 单 Redis 与 Redis Cluster 对照

- 同一组用例分别跑 standalone 和多主 Cluster；记录命令是否 CROSSSLOT、每个 master 的 QPS/延迟、SCAN 是否覆盖全部 master。
- 对恢复扫描构造大 `ai:runs`：比较 SMEMBERS 与分页方案的键空间阻塞、网络字节、每拍动作公平性。
- 对删除接口在有/无后台恢复扫描、有/无自动 purge 并发下分别测量；分解 preflight、枚举、两次 Cluster SCAN、DB、逐键 DEL、附件和 pending 收尾耗时。只有这样才能判断“超过 30 秒”的具体贡献者。
- 故障注入单 master 不可用、MOVED/ASK、2s 命令超时；确认观测降级与实际目标键结果一致。

### 7.3 多实例与多线程

- 至少双实例同时启动，覆盖 seed、cleanup、stale recovery、resume scan 的争锁和条件写；核对同一业务对象实际执行次数而非只看日志。
- watchdog 扫描期间持续新增/完成/删除作业，验证分页游标和判重记忆不漏、不误回收。
- 对 readiness 制造慢 DB 和不可中断 storage，叠加多个并发请求，检查饱和是否恢复、取消任务是否仍堵队列。
- 关闭测试覆盖启动延迟、扫描中、purge 中、judge 中；记录“停止接新拍→在飞结束/交棒→共享资源关闭”的有序事件。

### 7.4 性能验收指标

- 每拍 Redis 命令数、最大单键返回值、单 master 热点、DB 查询/锁等待、堆中实体数、扫描线程 occupied time。
- pending 积压量与最老 due 等待时间；watchdog 最老在途作业的发现/收敛延迟。
- ready 的 submitted/queued/cancelled/saturated 计数与假 DOWN 恢复时长。
- 关闭总时长必须包含 drain、Web graceful、后台扫描等待、AI/业务池等待及共享设施关闭，不得只看 drain-window。

## 8. 审查边界

- 本报告针对运行器与调度及其直接调用链，不替代对 `AiSessionPurgeService` 每一个删除命令、前端轮询、SSE Pub/Sub、全部 AI 热路径的专门审计。
- 未读取测试实现作为生产事实依据，未执行任何代码；涉及 Hibernate 实际 SQL 数量、Lettuce 对 Cluster PING 的具体节点选择、生产数据量与真实 30 秒耗时的部分均保留为后续验证项。
- 报告中的“代码证实”指控制流、锁边界、查询形状或时钟来源可直接由基线代码证明；需要特定时序/部署才能产生业务后果的地方已单独标注，未将假设写成线上事实。
