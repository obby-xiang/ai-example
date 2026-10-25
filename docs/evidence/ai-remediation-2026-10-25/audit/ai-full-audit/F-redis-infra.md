> 来源=<仓外证据>/ai-full-audit/F-redis-infra.md（2026-10-25 归档）· 审计分片/草稿，属历史证据，不代替终稿 `docs/research/排查-AI全链路只读验收审计-终稿-2026-10-25.md`
# F｜AI 相关 Redis / 共享基础设施 横切审计报告

- 源码快照（只读）：`<仓外证据>/ai-full-audit/tree`（base `ae883fb` + 审计开始时未提交补丁
  `working.patch` SHA256=`6c88b47d…dab955`）。**未读会变化的主仓**，所有结论以该快照为准。
- 本报告内路径均相对快照根（`tree/`）。
- 审计域：AI 调用边界上全部 Redis / 共享基础设施用法与相邻测试 —— Redis Cluster 的 hash tag/槽、Lua 原子性、
  `KEYS/SCAN/FLUSH/SELECT`、pub/sub 丢唤醒、租约/锁/幂等/CAS、节点故障与只读降级；共享 H2-TCP/PostgreSQL/Flyway
  并发冷启动、配置绑定、共享存储、readiness/liveness/draining、多 JVM 启动与关闭；前端多页签/多用户隔离中的共享态。
- 不审：业务内部（Excel/条件求值/作业执行算法/任务 CRUD 语义）→ E 域；协议帧内容与工具语义 → C 域；前端渲染细节 → H 域。
  本报告对其只审"共享状态怎么读写、失败怎么降级、跨实例是否成立"。
- 方法：逐行读本域生产代码 + 相邻测试源码 + 打包 jar 字节码（spring-data-redis 3.5.11 / spring-core 6.2.18）；
  未启动任何服务、未跑测试/构建、未起真实拓扑、未触 8081/5202、未读写任何密钥、未派子 agent、未改快照任何文件。
  唯一执行的命令是仓库自带的只读静态扫描（`node scripts/ci/forbid-redis-cluster-unsafe.mjs`，结果为 0 命中）。
- 因此：**本报告没有"实测通过"**。确定性分四档：`已证实缺陷（代码级）` / `具体风险（需特定交错或配置）` /
  `仅缺测试` / `未验证`。"可证明正确"一律带前提条件。
- 前提（任务要求，全部结论按此陈述）：原生 Redis Cluster（3 主 3 从、16384 槽）、共享 DB/共享文件存储、≥3 JVM、
  同一对话请求可无粘滞落到不同实例。

---

## 1. 覆盖清单

### 1.1 本域完整链路（入口 → 共享状态 → 相邻测试）

| # | 链路 | AI 入口 | 共享状态 / 外部调用（键族与落点） | 相邻测试（证据强度见 §3） |
|---|---|---|---|---|
| L1 | 发起一轮对话 | `POST /api/ai/chat`（`ai/web/AiController.java:300-500`） | 会话门 `ai:session:lock:<sid>`（单键 Lua）；限流 `ai:rate:session:*` / `ai:rate:ip:*`（单键 Lua）；快照 `ai:run:{run:<rid>}` + 索引 `ai:runs` / `ai:session:runs:<sid>` / `ai:session:current:<sid>`；记忆 `chat:mem:{<cid>}` | `ChatSessionBusyBodyTest`、`AiRateLimiterTest`、`RunStoreSnapshotReadTest`、`SessionGateLeaseTokenTest`、`RedisChatMemoryRepositoryClusterSafetyTest` |
| L2 | 帧出站 / 事件日志 / 跨实例唤醒 | `ai/run/SseChatEmitter.java:1413-1613`、`RedisFrameStore`、`RedisFrameWakeBus`、`FrameWakeListener` | Stream `ai:xevents:{run:<rid>}`；序号 `ai:seq:{run:<rid>}`（INCR）；唤醒 `PUBLISH ai:wake:<rid>`；兼容 LIST `ai:events:{run:<rid>}` | `RedisFrameStoreTest`、`RedisStreamCapacityTest`、`SseChatEmitterFrameStoreTest`、`SseChatEmitterCrossInstanceWakeTest`、`SseChatEmitterWakeFloorTest`（未提交新文件）、`FrameWakeListenerTest`、`FrameCompatReadTest`、`LeaseEpochPerRunTest` |
| L3 | 重挂 / 回放（`?lastSeq`） | `GET /api/ai/events/{runId}`（`AiController.java:730-770`） | 同上 Stream + `lastSeqIfKnown`；本实例订阅生命周期 | `ReattachLastSeqConformanceTest`、`AiControllerEventsParkCloseTest`、`SseChatEmitterWakeFloorTest` |
| L4 | 挂起（park）/ 决策 / 回灌 | `ConfirmGate`（`ai/gate/ConfirmGate.java:520-800`）、`POST /api/ai/confirm`、`POST /api/ai/frontend-tool-result` | `ai:pending:` `ai:req:` `ai:decided:`（HSETNX guard）`ai:lease:run:` `ai:fence:run:` `ai:claim:`；决策后 `ResumeService` 抢租 | `ConfirmGateLeaseTest`、`ConfirmGateDecisionAtomicTest`、`RunStoreDecisionGuardTest`、`RunStoreRequestEmittedAtomicTest`、`HumanWaitParkTest` |
| L5 | 续跑（启动 / 僵尸 / 死卡 / 运维） | `StartupResumeRunner`、`POST /api/ai/runs/{id}/resume`、`ResumeService` | 租约三键 Lua（租约/计数器/fence）；`ai:runs` 全量扫描；会话门重取 | `ResumeServiceLeaseTest`、`ResumeServiceZombieThresholdTest`、`RunLivenessTest`、`StartupResumeRunnerSessionGateTest`、`LeaseService*`（6 个） |
| L6 | 工具执行认领 / 幂等 / 资源锁 | `SpToolCallingManager`、`ToolResourceLocks`、`RunStore.tryClaimExecution/appendLedger` | `ai:claim:`（HSETNX）`ai:ledger:` + `ai:idem:`（同槽 Lua）`ai:lock:task|job:*`（全局取号） | `ToolIdempotencyTest`、`RunStoreClaimEpochTest`、`RunStoreClaimFenceTest`、`RunStoreIdempotencyIndexTest`、`ToolResourceLockGateTest`、`LeaseResourceTtlTest`、`LeaseServiceDistinctOwnerTest` |
| L7 | 取消 | `POST /api/ai/cancel/{runId}`、`CancellationRegistry` | `ai:cancel:<rid>`（读失败=UNKNOWN） | `CancellationRegistryFailClosedTest`、`CancelDecisionFramesTest` |
| L8 | 会话销毁 | `DELETE /api/ai/session/{sid}`、`AiSessionPurgeService`/`SessionPurgePendingStore`/`SessionPurgeGuard`/`SessionPurgeRetryRunner` | 逐 master `SCAN`（归档键）；`ai:purge:pending:`（无 TTL）、`ai:purge:retry:queue`、`ai:purge:guard:`、`ai:run:*` 13 键、DB 两表、共享盘附件 | `AiSessionPurgeServiceTest`、`SessionPurgePendingStoreTest`、`SessionPurgeGuardFailClosedTest`、`SessionPurgeRetryRunnerTest`、`RedisClusterScanSupportTest`、`AiChatHistoryPurgeGuardTest`、`AiControllerSessionPurgedTest` |
| L9 | 完整历史（共享 DB） | `GET /api/ai/history/{sid}`、收敛写 `AiChatHistoryService` | `ai_chat_message`（`(session_id,seq)` 唯一 + `(session_id,run_id,message_id)` 唯一） | `AiChatHistoryServiceTest`、`AiChatHistoryRecorderTest`、`AiChatHistoryPurgeGuardTest`、`AiModelReasoningBackfillMigrationTest` |
| L10 | 表单附件（跨实例上传/消费） | `POST /api/ai/forms/attachments`、`generative_form` 提交 | `ai:form:att:<fileId>` + `ai:form:att:sess:<sid>` 索引 + 共享盘 `storagePath` | `RedisFormAttachmentStoreFailClosedTest`、`RedisFormAttachmentStoreSessionIndexTest`、`GenerativeFormAttachment*` |
| L11 | 知识库缓存 | `kb` 工具、`KbService`、`CachedKbSource` | `kb:cache:doc:*` / `kb:cache:manifest` / `kb:cache:manifest:version`（INCR）/ `kb:cache:refresh:lock`（SET NX PX + compare-delete Lua） | `KbCacheTest`、`CachedKbSourceTest`、`KbServiceIncrementalTest`、`KbServiceIndexTest`、`KbSampleSeederConcurrencyTest` |
| L12 | 模型配置热生效 | `AiConfigWriter` / `AiConfigService` / `AiProviderRegistry` | DB 真源 + `ai:cfg:ver`（INCR）+ `ai:cfg:models:<pid>`（TTL） | `AiConfigWriterTest`、`AiConfigServiceTest`、`AiProviderRegistryConfigVersionTest`、`ProviderRegistryHotReloadTest`、`AiConfigSeederTest` |
| L13 | 多 JVM 启动 / 就绪 / 关闭 | `/health/ready`、`ShutdownDrainGate`、`FileStorageMultiInstanceGuard`、`RedisClusterTopologyGuard`、`application-{cluster,h2tcp,postgresql}.yml` | Redis PING（全 master）、DB、共享盘目录、种子、draining 窗口 | `ReadinessServiceTest`、`ReadinessDrainWindowTest`、`ReadinessProbeTimeoutTest`、`ReadinessProbeRunnerTest`、`FileStorageMultiInstanceGuard*Test`、`ClusterProfileConfigTest`、`ProfileConfigContractTest`、`MigrationSetGuardTest`、`PostgresMigrationSqlContractTest` |

### 1.2 已逐行阅读的生产代码（本域）

- Redis 键族/租约/帧：`ai/run/{LeaseService(1146), LeaseKeys(76), InstanceId(37), RunStore(2822，本域相关段全读), RedisFrameStore(758),
  RedisFrameWakeBus(149), FrameWakeListener(162), SseChatEmitter(2206，emit/onWake/replay/attach/wake 段), FrameStore(99),
  RunRegistry(196), SessionKeyRegistry(1-60 及登记表), CancellationRegistry(368), RunLiveness(63), StartupResumeRunner(213),
  ResumeService(1260，准入/租约/交棒段), ToolActivityBeacon(293)}`
- 会话/销毁/守卫：`ai/session/{SessionGate(460), AiSessionPurgeService(1011，主流程/删除面/扫描面), RedisClusterScanSupport(150),
  SessionPurgePendingStore(389), SessionPurgeGuard(172), SessionPurgeRetryRunner(250), SessionPurgeDatabase(接口面)}`
- 记忆/历史/表单/知识库/配置：`ai/memory/RedisChatMemoryRepository(412)`、`ai/history/AiChatHistoryService(1-330)`、
  `ai/form/RedisFormAttachmentStore(321)`、`ai/kb/{KbCache(136), RedisKbCacheStore(135), CachedKbSource(1-220)}`、
  `ai/config/{AiConfigService(150-260), AiConfigWriter(620-740), AiConfigSeeder, AiProviderRegistry(类注+版本闸), RedisAvailability(163)}`、
  `ai/gate/{AiRateLimiter(199), ToolResourceLocks(223)}`、`ai/observe/RedisDegradation(157)`
- 基础设施：`health/{ReadinessService(229), ReadinessProbeRunner(234), ShutdownDrainGate(88), RedisReadinessProbe(61)}`、
  `config/RedisClusterTopologyGuard(64)`、`file/{FileStorageMultiInstanceGuard(137), RedisFileKeyMigrationLock(89)}`、
  `application.yml` / `application-{cluster,h2tcp,postgresql}.yml`（全文）
- 迁移：`db/migration/{V3,V4,V5,V8,V9}` 与 `db/postgresql/` 同版本副本（目录比对：仅 `V6__task_child_fk.sql` 只存在于 PG 目录）
- 工程/门禁：`scripts/ci/{cluster3-run.sh(531), cluster3-probe.mjs(3978 抽样), forbid-redis-cluster-unsafe.mjs(334)}`、
  `scripts/redis5-cluster-smoke.sh(抽样)`、`.github/workflows/{ci.yml, cluster-3node.yml, redis-5-smoke.yml}`、`backend/pom.xml`
- 前端（只读共享态相关）：`frontend/src/api/sse.ts`（看门狗/重连/终帧）、`frontend/src/stores/ai.ts`（sessionId/镜像/lastSeq/页签所有权）

### 1.3 明确未覆盖（不悄悄略过）

**生产代码未逐行**：`ResilientChatService(2352)`（只读 park/错误/终态收敛相关段）、`ContextCompactor`/`CompactionFuse`/`ContextStateStore` 家族
（只核"状态落在 `ai:ctx:*`、读写非原子"这一事实）、`FileKeyMigrationRunner`、`ClientIpResolver`、db/storage/seed 三个探针实现、
`AiModelCatalog` 全文、`task/RedisTaskEventBus` 与 `job/JobHeartbeatRegistry`（非 AI，E 域）、`frontend/components/AiPanel/AiPanel.vue`。
这些只在与本域接缝处被引用。

**未覆盖分支（读到了但无测试覆盖，或只有替身覆盖）**：
- `RedisFrameStore`：兼容期 LIST 读（`legacyFramesAbove`）、显式 ID 被拒后的自动 ID 兜底与乱序读（`append:268-283`）、
  `C1_STREAM_BYTE_FLOOR` 分支（`noteByteGovernance:705-727`）、`readFromWithStatus.degraded` 在 API 层被 503（`AiController.java:751`）。
- `LeaseService`：`ACQUIRE_NOFENCE_SCRIPT`（只有测试替身走）、`UNAVAILABLE` 墓碑后的恢复、`isHeld` 对账探针、`facilityHealthy()` 的 1s PING 节流。
- `RunStore`：`pruneRunIndex` 失败路径、`deleteRun` 部分失败、`backfillSessionRuns` 的后填标记路径。
- `SessionGate`：脚本结果不可知（`null`）的降级分支、`releaseDeadHeld`。
- `AiSessionPurgeService`：`MAX_ATTEMPTS` 耗尽后的 `defer` 重排、附件文件删除失败、多键 `DEL` 部分失败。
- `RedisClusterScanSupport`：`maxKeys>0` 的有界截断语义（只被 `recoverOrphans(200)` 使用）。
- 集群/拓扑：**全部真实 Cluster 行为**（CROSSSLOT、MOVED/ASK、slot 迁移、单 master 故障、只读副本、5.0.14 语法）无产品级测试。
- 共享 DB：**真实 PostgreSQL 一次都没跑过**（workflow 无 PG service；`ProfileConfigContractTest`/`PostgresMigrationSqlContractTest` 是文件级断言）。
- 前端：多用户隔离（服务端无身份）、多页签并发写竞态。

---

## 2. 分链分析（入口 → 身份/session/run → 共享状态 → 正常与异常 → 重连/取消/恢复 → 用户可见）

> 每条链按"3 JVM + 无粘滞 + Redis Cluster + 共享 DB/盘"陈述；"可证明正确"均附条件。

### L1 `POST /api/ai/chat`（首轮）

- **入口/身份**：`sessionId` 由客户端给（页签级，`sessionStorage`），`runId` 服务端 `UUID.randomUUID()`（`AiController.java:412`）。
  没有用户身份参与任何键名 —— 跨用户隔离**不靠服务端**，只靠 sessionId 不可猜（见 F-12 备注）。
- **共享状态**：`RedisAvailability.isAvailable()`（PING）→ 限流（两个单键 Lua）→ `SessionPurgeGuard.isArmed`
  → `SessionGate.acquire`（单键 Lua，跨实例互斥）→ `RunStore.create/save`（plain SET，见下）→ 记忆读写 → 帧出站。
- **正常分支（可证明正确，条件：Redis 健康）**：会话门是 Redis 单键 Lua（`SET PX` + 值比对），两个实例对同一 sessionId
  必然只有一个拿到（`SessionGate.java:80-96`）；限流单键 Lua（`AiRateLimiter.java:79-102`），跨实例一致。
- **异常分支**：Redis 不可用 ⇒ `503 AI_REDIS_UNAVAILABLE`（≤2s，`RedisAvailability.java:86-104`）；会话门脚本结果不可知
  ⇒ 降级为进程内门并标 `degraded=true`（`SessionGate.java:232-236`）——**此时跨实例互斥失效**（单实例正确）。
- **run 身份与 fencing**：**首轮不取续跑租约**（`AiController.java:412-476` 无 `acquireRunLease`；全仓只在 `ResumeService.java:530`
  与 `ConfirmGate.java:547` 取租）。因此首轮全程 `runLeaseAuthority == NEVER_HELD`（`RunStore.java:1121-1129` +
  `LeaseService.java:797-813`），`save()` 走 **plain SET**（`RunStore.java:718-726`），出帧也不受只读保护
  （`SseChatEmitter.java:1421-1444`）。详见 F-01。
- **用户可见**：正常；Redis 抖动时 503 快速失败（文案在 `RedisAvailability.reason()`，不含内部地址）。

### L2 帧出站 / 事件日志 / 唤醒

- **共享状态**：`INCR ai:seq:{run:<rid>}` 取号 → `XADD ai:xevents:{run:<rid>} MAXLEN ~ n seq-0`（显式 ID）
  → `PUBLISH ai:wake:<rid>` → 本地扇出（`SseChatEmitter.java:1450-1523`）。
- **正常（可证明正确，条件：单实例或客户端在线）**：物理序 = seq 序（显式 ID），差量读用闭区间 `(from+1)-0`
  （`RedisFrameStore.java:431-433`），对 Redis 5.0 友好；`MAXLEN ~` 与字节预算换算窗口读写同源（`:668-685`）；
  唤醒只带上界，重复唤醒幂等（`SseChatEmitter.java:1568-1573`）。
- **异常**：序号 INCR 失败 ⇒ **进程内兜底计数**（`RedisFrameStore.java:191-196`），降级期可能撞号（F-07）；
  落档失败只丢"跨实例可见性"，不中断本轮（`:278-283`）；读失败 `degraded=true` 由 API 层 503（`AiController.java:751`）。
- **跨实例**：pub/sub 是 fire-and-forget（`RedisFrameWakeBus.java:48-56`）；丢唤醒的兜底**只在前端**
  （45s 空闲看门狗 → 带 `lastSeq` 重挂，`frontend/src/api/sse.ts:36-40/525/645`），服务端无周期性补帧（F-08）。
- **重连**：`replayAndAttach` 先挂订阅再读回放（同一把 `emitLock`，`SseChatEmitter.java:670-678`），窗口内不丢帧；
  订阅建立失败会回退登记位（`syncWakeSubscription:593-618`），但**没有独立定时重试**，只能等下一次 attach。
- **用户可见**：正常逐字流；丢唤醒时最长约 45s 静默后自愈（后台页签计时器节流会放大）。

### L3 重挂 / 回放

- `?lastSeq` 入口；`lastSeqIfKnown` 预检失败直接 503（`AiController.java:751`），不静默空回放。
- `stageReplay` 用 `max(读到的最大号, lastSeq)` 推进**每订阅者**游标（`SseChatEmitter.java:878-883`）；差量为空且
  `lastSeq` 恰是终帧时用尾部探测收尾（`tailFrameIsTerminal:726-740`）。
- 可证明正确（条件：客户端携带真实 lastSeq；归档未被裁剪）。归档被 `MAXLEN` 裁掉时记 `c1.streamReplayGap`
  （`RedisFrameStore.java:409-416`）但**不向客户端标缺口** —— 用户只看到"少了一段"（已知取舍）。

### L4 挂起 / 决策 / 回灌

- 挂起前 `saveSuspended` 外置（硬规范①）；进入等待时**抢租**（`ConfirmGate.java:536-550`），决策到达把租约**交棒**给驱动方、
  不释放（`:553-556`）；park 模式交还租约，由决策方重新抢（`:616-622`）。
- 决策 guard `HSETNX`（`RunStore.tryRecordDecision`）⇒ 两实例并发提交同一张卡只有一次落库；条目未写而 guard 已写时读侧收敛。
- 跨实例可证明正确（条件：Redis 健康）：决策端点在任何实例都只写 guard/条目并触发 `ResumeService`，后者按租约互斥。
- 已知窗口（设计如此）：park 期间没有线程/心跳，`ai:run:*` 只在 24h 会话 TTL 内存活；用户 24h 后决策会得到"轮次不存在"。

### L5 续跑（启动/僵尸/死卡/运维）

- 准入：取消 → 终态 → RUNNING（僵尸例外）→ PROVIDER_GONE → PENDING_UNRESOLVED；租约 `verifyHeld` 对 Redis 真值校验
  （`ResumeService.java:613-630`），不用进程内视图。
- 正常（条件：Redis 健康）：三键同槽 Lua 保证"号与占用同序"，`epoch = max(INCR, fence+1)` 自愈"计数器先死"
  （`LeaseService.java:155-180`）。
- 异常：`UNAVAILABLE` ⇒ **当无租约继续**（`ResumeService.java:530-543`）——一次 Redis 抖动可能让两个实例同时续跑同一轮
  （工具副作用仍由 `ai:claim:` HSETNX 拦；LLM 调用与出帧不拦）。见 F-05。
- 配额：`max-on-startup=10` 按"尝试数"计，被别人持有的轮次也吃配额（`StartupResumeRunner.java:136-167`）→ F-06。
- 用户可见：正常"刷新/重启后自动跑完"；异常时可能重复一次模型调用。

### L6 工具执行认领 / 幂等 / 资源锁

- `tryClaimExecution`：先判写权限（LOST/UNAVAILABLE 直接 `STALE_FENCE`），持租时再与 fence 对账，然后 `HSETNX` 认领，
  失败值比对回滚（`RunStore.java:1688-1781`）。可证明正确（条件：Redis 健康 + 该键所在 slot 可用）。
- 台账+索引同槽 Lua，索引下标在脚本内取 `LLEN`（`RunStore.java:340-352`），跨实例并发不会指错记录。
- 资源锁：双锁固定顺序、`resource-ttl` 不续租、只释放自己创建的那一任（`ToolResourceLocks.java:43-48/194-210`）；
  设施不可用 ⇒ **放行**（`:143-169`）→ F-05。

### L7 取消

- `ai:cancel:<rid>` 是权威态；同进程监听者加速；跨进程靠 1s 看门狗 / 心跳节拍轮询（`CancellationRegistry.java:18-44`）。
- 读失败 ⇒ `UNKNOWN`，布尔口抛异常（`:100-140`）。可证明正确（条件：调用方不把 `UNKNOWN` 当"未取消"——本域已核对
  `StreamWatchdog`/`ConfirmGate`/`AiSessionPurgeService` 都按三态使用）。

### L8 会话销毁

- 顺序：pending（无 TTL）→ 枚举（任一失败即中止）→ 装 guard → DB 删 → Redis 逐键删 → 附件文件 → 会话键 → 保留期 guard
  （`AiSessionPurgeService.java:246-330`）。
- 集群安全：归档键**逐 master SCAN**（`RedisClusterScanSupport.java:98-136`），任一 master 失败 fail-closed；13 键一条 DEL
  （同 tag ⇒ 同槽，条件：真客户端按槽路由）。
- 风险：guard 只在写入前查一次，删除与在飞 INSERT 之间无 fencing（F-04）；观测键 3 键 DEL 跨槽（F-14）。

### L9 完整历史（共享 DB）

- `(session_id, seq)` 唯一 + `(session_id, run_id, message_id)` 唯一（`db/migration/V8,V9`）；`MAX(seq)+1` 冲突重试 32 次
  （`AiChatHistoryService.java:94/148-167`），每次 attempt 一个 `REQUIRES_NEW`。可证明正确（条件：PG/H2-TCP 唯一约束语义一致；
  同会话被会话门串行化）。风险仍是 F-04（删除 vs 追加）。

### L10 表单附件

- 登记/消费/墓碑全部 fail-closed 到 Redis（`RedisFormAttachmentStore.java:24-32/80-161`），不回落进程内 Map；会话索引写失败
  回滚登记并报错（`:94-108`）。文件本体落 `app.file.storage-path`，多实例由 `FileStorageMultiInstanceGuard` 强制绝对路径
  （`FileStorageMultiInstanceGuard.java:111-136`）。可证明正确（条件：共享盘真被多实例挂到同一绝对路径）。

### L11 知识库缓存

- 版本号 + 刷新锁 + "索引从缓存重建"（`CachedKbSource` 类注）；http 源单飞 `SET NX PX` + compare-delete；Redis 不可用降级直连。
  风险：**local 源把本机磁盘当共享缓存的内容来源**（F-13）。

### L12 配置热生效

- DB 真源 + `ai:cfg:ver` 失效信号 + 每实例 1s 版本比对；Redis 不可用退化 DB 版本比对（`AiConfigService.java:236-256`）；
  加载失败不推进版本（`:187-206`）；运行态按 `config.version` 版本闸淘汰（`AiProviderRegistry` I2 注释）。
  可证明正确（条件：各实例按 1s 轮询且 DB 版本单调）。

### L13 多 JVM 启动 / 就绪 / 关闭

- `/health/ready`：硬依赖 db/storage/seed，Redis 只是降级维度（`ReadinessService.java:26-38/135-185`）；探针有界执行、
  共享 2s 总时限、饱和记 DOWN（`ReadinessProbeRunner.java:102-143`）。
- draining：`ContextClosedEvent` 最高优先级先置 503 再等窗口（`ShutdownDrainGate.java:60-86`）；`server.shutdown=graceful`
  只在 `h2tcp`/`postgresql` profile（`application-h2tcp.yml:42`、`application-postgresql.yml:68`），`cluster` profile 没有
  （`application-cluster.yml:38-44` 只有 drain-window）→ F-11。
- 冷启动：共享 H2-TCP 并发冷启动只有 CI 三实例网（`cluster3-run.sh`，`workflow_dispatch`）；PG 从未真跑。
- 关闭：`@PreDestroy` 见 `LeaseService.shutdown`（还租约）、`SessionPurgeRetryRunner.shutdown`；`RedisFrameWakeBus`
  的监听容器**没有**销毁（F-09）。

---

## 3. 测试可信度

### 3.1 各层证明什么（按证据强度）

| 层 | 运行形态 | 证明了什么 | 没证明什么 |
|---|---|---|---|
| 后端单测（2607 个 `@Test`，0 `@Disabled`） | **不连 Redis**（`backend/src/test/resources/application.properties:1-3` 明确"避免 CI 依赖 Redis"）；Redis 全部用替身（`FakeLeaseRedis`/`FakeFrameRedis`/`FakeMemoryRedis`/`FakeRateRedis`/`PurgeFakeRedis`/`FakeWakeBus`/`InMemoryFrameStore`） | 产品**调用序列**与**替身复刻的脚本语义**；状态机分支；错误码与文案 | Lua **原文**是否可执行/原子/同槽；Cluster 路由；网络超时与重连 |
| 三实例网（`cluster-3node.yml`，仅 `workflow_dispatch`） | 3 JVM + **单节点 Redis 7.2.16**（`cluster3-run.sh:355-373`，无 `--cluster-enabled`）+ H2-TCP + HAProxy | 无粘滞多实例：会话门/路由/重挂/跨实例确认/跨实例销毁/并发冷启动实例 ID 互异/readiness 摘流/**整个 Redis 停掉**后的恢复 | **不含 Redis Cluster**：CROSSSLOT、MOVED/ASK、slot 迁移、单 master 故障、`ping()` 逐节点语义 |
| 5.0.14 集群冒烟（`redis-5-smoke.yml`） | 真 3 主 3 从，但只用 `redis-cli` 打**等价探针**（`scripts/redis5-cluster-smoke.sh:605-700`；`:848` 的 `TIME`-in-Lua 是**另写的一段**） | 环境级事实：5.0.14 拒绝 `(id` 排他区间、无 tag 双键 CROSSSLOT、`MAXLEN ~`/显式 ID 可用、`TIME`-in-Lua 可写、`CLIENT SETINFO` 兼容；外加一条源码 grep 门 | 产品 jar 在 Cluster/5.0.14 上的**行为**；租约/帧/唤醒/限流的真脚本；`available`/503 取值（脚本头注自认未验） |
| CI E2E（`ci.yml:241-245`） | 单节点 redis 7.2.16 + 桩上游 | 单实例端到端（含 Redis 停掉的 503） | 多实例、Cluster、真 Lua 边界 |
| 前端 vitest / Playwright | 浏览器/jsdom | 45s 空闲看门狗、重连退避、lastSeq 增量、多页签所有权（Web Locks） | 服务端跨实例补帧（用桩后端） |

### 3.2 具体的"假绿/水分"点

1. **Lua 原文从未被执行**。所有脚本靠"正文首行标记分派"，由 Java 逐行复刻（`FakeLeaseRedis.java:36-45` 自述
   "测试断言的是脚本语义，不是脚本原文"）。改脚本不改替身 ⇒ 全绿地放过。`backend/pom.xml` 无 Testcontainers/embedded-redis。
2. **Cluster 只是"槽号计算器"**。`FakeLeaseRedis.enableClusterMode()` 复刻 CRC16 + hash tag、跨槽抛 `CrossSlotException`
   （`ClusterSlotTest.java:274-298` 自证），证明的是"键名同槽"，不是"客户端在 Cluster 上真能跑"。
3. **跨实例唤醒是"两个对象 + 一条内存总线"**（`SseChatEmitterCrossInstanceWakeTest` 用 `InMemoryFrameStore` + `FakeWakeBus`）：
   证明扇出/幂等/游标逻辑，不证明 pub/sub 传输、容器重连、跨节点广播。
4. **`SCAN` 测试用 `PurgeFakeRedis` 模拟 master 拓扑**（`RedisClusterScanSupportTest`）：证明控制流（逐 master + 失败上抛 +
   cursor 关闭），不证明 Spring Data 3.5.11 的 `clusterGetNodes()`/`scan(node, options)` 在真集群上的返回形状。
5. **purge guard 测试是"查询点"断言**（`AiChatHistoryPurgeGuardTest` 打桩 `isArmed` 为 true/false），没有"删除与写入交错"的用例
   —— F-04 的结构性缺口。
6. **"多实例"测试里的第二实例是同一 JVM 的第二个对象**（`InMemoryRunStore`、第二个 `LeaseService`），共享同一个 `Map`：
   能证明"协议在共享态上自洽"，不能证明进程/网络边界。
7. **没有一条测试断言"单 master 故障时 AI 端点仍可用"或"应 503"**（仓库自己把这条列为待真集群观测）。
8. **没有"首轮无租约 + 僵尸接管"的交错用例**（F-01），而 `RunStoreFencedWriteTest` 反而把 `NEVER_HELD ⇒ plain 写` 断言为正确行为。
9. Playwright 有多个按批分片的配置（`playwright.{hgta,kqc,vl,mdo,…}.config.ts`），但本域未发现 `@Disabled`/条件跳过（全套 0 个）。

---

## 4. 发现清单

> 字段：severity / 确定性 / 位置 / 触发 / 用户影响 / 测试（或缺失）。影响面按"共享 DB + 共享盘 + ≥3 JVM + 无粘滞 + Redis Cluster"陈述。
> 本域**未发现**可确证的 Critical：最高一档（F-01/F-02/F-03/F-04/F-05）都是"路径确定但后果需要特定交错/拓扑条件"或"验证缺口"。

### F-01 首轮从不取续跑租约 ⇒ 常规轮次全程无 fence；接管方写入可被旧实例 plain SET 覆盖
- **severity**：Important
- **确定性**：已证实缺陷（代码级）——"首轮无租约"确定；"覆盖"需要一次僵尸接管 + 旧实例苏醒（具体风险面）
- **位置**：
  - `backend/src/main/java/com/example/configmgr/ai/web/AiController.java:412-476`（`/chat` 只取 `SessionGate`，**不取** `ai:lease:run:`）
  - `backend/src/main/java/com/example/configmgr/ai/run/RunStore.java:690-726`（`authority==NEVER_HELD` ⇒ `opsForValue().set(...)` plain 写）、
    `:764-787`（fence 已在前面也只 WARN，不拒写）
  - `backend/src/main/java/com/example/configmgr/ai/run/LeaseService.java:797-813`（四态：无墓碑 + 无视图 + PING 正常 ⇒ `NEVER_HELD`）、
    `:822-841`（`facilityHealthy` 的 1s PING 自证）
  - `backend/src/main/java/com/example/configmgr/ai/run/SseChatEmitter.java:1421-1444`（出帧只挡 `LOST`/`UNAVAILABLE`）
  - 对照：全仓取租点只有三处 —— `ResumeService.java:530`（续跑准入）、`ConfirmGate.java:547`（进入人工等待）、
    `ConfirmGate.java:1638-1650`（死卡兜底派发）；`/chat` 与 `ResilientChatService` 均不在其中
- **触发**（≥3 JVM、无粘滞）：
  1. 实例 A 处理一轮**纯文本**（无工具、无 park）对话，正常出帧、正常 `save(RUNNING/DONE)` —— 全程没有 `ai:fence:run:<rid>` 键。
  2. A 长停（STW / 虚机挂起 / 网络分区）超过 `app.ai.resume.zombie-age=180s`；B（启动扫描或运维端点）按
     `RUNNING + 业务活动超龄` 判僵尸并 `resume(forceTakeover=true)`（`ResumeService.java:710-726` 对 RUNNING+force 不要求有未决条目，
     会**重新驱动整轮**）：抢到 `epoch=1`，`fencedSet` 把 fence 写成 1，写 DONE + `done` 帧。
  3. A 苏醒：`authority == NEVER_HELD`（它从没取过租）⇒ `save()` 走 **plain SET**（覆盖 B 的 DONE/FAILED）；`emit()` 放行
     （会再发一个 `done`，用 INCR 拿新 seq）。`fence ≤ epoch` 的拒绝路径根本不会走到。
- **用户影响**：同一轮两个终态帧/两份回答；快照状态被旧实例改回（B 已 FAILED，A 又写回 DONE），
  `/api/ai/runs/current`、历史收敛、最终卡片三处可能不一致；模型调用与出帧被重复计费。
- **测试**：`LeaseLossReadOnlyTest` / `SseChatEmitterAuthorityFailureTest` 只覆盖"持过租后丢租"（LOST/UNAVAILABLE）；
  `RunStoreFencedWriteTest` 把 `NEVER_HELD ⇒ plain 写` 当**正确行为**断言；**没有**"首轮 + 僵尸接管"的交错用例
  （`ResumeServiceZombieThresholdTest`/`RunLivenessTest` 只测判死）。
- **要点**（不改代码，仅指出方向）：`/chat` 建立 run 时按持有者身份取租（至少写一次 fence 基线），让 `NEVER_HELD` 不再等于"可写"。

### F-02 Cluster 下 `PING` 逐 master 执行：单 master 故障窗口内 **全站 AI 端点 503**
- **severity**：Important
- **确定性**：具体风险（字节码已确证路径；真集群未观测）——不能断言"必然 503"，窗口长度取决于节点判死与拓扑收敛
- **位置**：
  - `backend/src/main/java/com/example/configmgr/ai/config/RedisAvailability.java:86-104`（`connection.ping()`；`pong != null` 即判活）
  - 字节码证据（本机 `~/.m2` 的 `spring-data-redis-3.5.11.jar`）：
    `LettuceClusterConnection.ping()` → `ClusterCommandExecutor.executeCommandOnAllNodes(...)` → `MultiNodeResult.resultsAsList()`；
    `ClusterCommandExecutor.collectResults` 只要任一节点 future 抛错就 `throw ClusterCommandExecutionFailureException`；
    `ClusterTopology.getActiveMasterNodes()` 过滤 `isMaster && isConnected && !isMarkedAsFail`
  - 调用面：`AiController.java:318`（`/chat` 前置）、`:751`（`/events`）、`:1079`（其它 AI 端点）、`RedisReadinessProbe.java:56`（readiness）
- **触发**：3 主 3 从中 kill/失联一个 **master**（未到 `cluster-node-timeout`，或客户端拓扑尚未刷新）→ `ping()` 抛
  `ClusterCommandExecutionFailureException` → `available=false` → 所有实例的 `/chat`、`/events` 等返回 `503 AI_REDIS_UNAVAILABLE`
  （负缓存 5s）。**真正不可用的只有该 master 的 slot**，但 503 是全局的。
  反向不对称：`collectResults` 对 `TimeoutException` **静默跳过**（不进异常收集器）；`ping()` 某节点返回非 PONG 时给空串 `""`，
  本类只判 `!= null` ⇒ **慢/半坏节点的可观测性被抹平**（两个方向）。
- **用户影响**：一次节点故障 ≈ 一次全站 AI 短时中断（通常几秒到十几秒，取决于 `cluster-node-timeout` 与 Lettuce 拓扑刷新）；
  期间业务面正常（Redis 不是 ready 硬依赖，`ReadinessService.java:143-169`），LB 不摘流，用户反复撞 503。
- **测试**：零。三实例网是单节点 Redis（`cluster3-run.sh:355-373`），其"Redis 故障恢复"用例（`cluster3-probe.mjs:3230-3320`）
  停的是**整个 Redis**；`redis5-cluster-smoke.sh` 的故障注入只断言环境级 `redis-cli` 事实，且头注声明"产品 `available`/503 口径未验"。

### F-03 真实 Redis Cluster 上**没有任何产品级自动化验证**；Lua 原文一次都没被执行
- **severity**：Important（对"能否上 Cluster"的结论是证据缺口，不是已知缺陷）
- **确定性**：已证实（工程事实，可复核）
- **位置/证据**：
  - 三实例网用单节点：`scripts/ci/cluster3-run.sh:355-373`（`docker run ... redis:7.2.16 --maxmemory-policy noeviction --appendonly no`，
    **无** `--cluster-enabled`）；同文件 `:8-14` 自述"同一个 Redis 容器"——用例名里的"集群"指 3 个**应用**实例
  - 真集群只跑 `redis-cli` 等价探针：`scripts/redis5-cluster-smoke.sh:605-700`（XRANGE/CROSSSLOT）、`:726-731`（12 键 DEL）、
    `:848`（另写的 `TIME`-in-Lua）、`:1130-1150`（日志门）；**不启动产品 jar**
  - 单测不连 Redis：`backend/src/test/resources/application.properties:1-3`；`backend/pom.xml` 无 testcontainers/embedded-redis
  - 替身自述："测试断言的是脚本语义，不是脚本原文"（`backend/src/test/java/com/example/configmgr/ai/run/FakeLeaseRedis.java:36-45`）
  - CI E2E 也是单节点：`.github/workflows/ci.yml:241-245`
- **逐条含义**：hash tag/同槽设计在代码与替身上**自洽**（§5），但以下全无自动化证据：(a) 多键 `EVAL` 的 KEYS 真同槽；
  (b) `RENAME staging→final` 同槽；(c) `del(13 键)` 是一条命令；(d) 原始命令 `connection.execute("XADD", key, ...)` 在 Cluster 下
  按第一个 key 路由；(e) MOVED/ASK 与 `max-redirects=3`；(f) 5.0.14 语法差集（只有环境级证据）；(g) 单 master 故障（F-02）。
- **用户影响**：任一条不成立时的故障形态是**静默退化**（`ACQUIRE` 的 CROSSSLOT 会被 `LeaseService.java:562-567` 折成 `UNAVAILABLE`
  ⇒ 租约/fencing/资源锁三族一起失效，只有一条 WARN）或**500**（`FENCED_SET` 硬抛）。这正是最需要门禁的形态。
- **测试**：`ClusterSlotTest`(6)+`ClusterProfileConfigTest`(5)+`RedisClusterScanSupportTest`(3)+`RedisChatMemoryRepositoryClusterSafetyTest`
  —— 全部基于"替身开槽模式"，不是真拓扑。

### F-04 会话销毁与在飞写入的 TOCTOU：guard 只查一次，删除先于 INSERT 提交 ⇒ 已销毁会话的数据可"复活"
- **severity**：Important
- **确定性**：具体风险（时序窗口已定位；未在真多实例复现）
- **位置**：
  - 销毁顺序：`backend/.../ai/session/AiSessionPurgeService.java:246-330`（`pending.register` → `enumerate` → `guard.arm`(`:301`)
    → `database.delete`(`:315`) → `executeDeletion`；Redis 面在 DB 之后）
  - 写回侧（各查一次，之后才写）：`backend/.../ai/history/AiChatHistoryService.java:136-150`（`isArmed` 在**事务外**，
    `insertNext`(`:288-301`) 在 `REQUIRES_NEW` 内、**无二次校验**）；`backend/.../ai/memory/RedisChatMemoryRepository.java:304-340`
    （`isArmed` 在 staging/RENAME 之前，之后不再校验）
  - guard 语义：`backend/.../ai/session/SessionPurgeGuard.java:95-123`
- **触发**：实例 B 的终局收敛在 **t0** 通过 `isArmed=false`；实例 A 在 t1 装 guard、t2 提交
  `DELETE FROM ai_chat_message WHERE session_id=?`；B 在 t3 提交 INSERT。结果：`ai_chat_message` 多一行（或 `chat:mem:*` 被写回），
  而 pending 已 `complete`、guard 只剩保留期墓碑。刷新后完整历史里出现"已销毁会话"的内容。（反向顺序无害。）
- **用户影响**：隐私语义被破坏（用户点了销毁，几秒后历史又出现一条）；`complete=true` 的响应与事实不符；无补偿路径
  （`SessionPurgeRetryRunner` 只处理 `complete=false` 的 pending）。
- **测试**：`AiChatHistoryPurgeGuardTest` 只打桩 `isArmed` 的返回值（true ⇒ 跳过 / 异常 ⇒ 上抛）；`AiSessionPurgeServiceTest` 不构造并发写。
  **无交错用例**。

### F-05 租约/资源锁设施不可用时"降级放行" ⇒ 破坏性动作失去跨实例互斥（节点故障窗口内 `available` 可能仍为 true）
- **severity**：Important
- **确定性**：已证实（代码级；这是**刻意**的降级口径，但后果未按"Cluster 单节点故障"评估）
- **位置**：
  - `backend/.../ai/gate/ToolResourceLocks.java:143-169`（`UNAVAILABLE` ⇒ 句柄返回 + WARN，调用方按放行执行）
  - `backend/.../ai/run/LeaseService.java:562-567`（任何异常 ⇒ `UNAVAILABLE`；入口闸 `:520-536` 只挡 fence 与租约不同 tag 的编程错误）
  - `backend/.../ai/run/ResumeService.java:530-543`（`UNAVAILABLE` 与"没有租约设施"同义）
  - 对照：写路径与认领在 `readOnly()` 时 fail-closed（`RunStore.java:695-699/1690-1694`），两套口径并存
- **触发**：承载 `ai:lock:*` / `ai:lease:*` 的 slot 所在 master 故障（或迁移中、命令 2s 超时）：`acquire` 抛错 ⇒ `UNAVAILABLE`
  ⇒ 两个实例上的两个 run 同时对同一 task 发起导入/发布。`ai:claim:` 仍拦**同一 toolCallId**，但拦不住**两个不同 run**。
- **用户影响**：同一任务被并发导入/发布（业务侧是否另有守卫属 E 域：`JobMutexGuardTest`/`TaskFinalizedGuardTest` 存在）；
  `/api/ai/health` 上只体现一次 WARN + 降级计数。
- **测试**：`ToolResourceLockGateTest`/`LeaseServiceTriStateTest`/`LeaseResourceTtlTest` 覆盖三态语义本身；
  **无**"设施不可用 + 两实例并发起同一作业"的组合用例。

### F-06 启动续跑配额按"尝试数"计 ⇒ 第 11 个及以后的可续跑轮次可能永不自动续跑
- **severity**：Minor
- **确定性**：已证实（代码级）
- **位置**：`backend/.../ai/run/StartupResumeRunner.java:136-167`（`resumed.size() >= limit` 才跳过；`LEASE_HELD_ELSEWHERE`/`IN_PROGRESS`
  也 `resumed.add(...)` 并吃配额）、`:151-157`
- **触发**：≥3 JVM 同时启动，`ai:runs` 里有 20 个"可续跑"轮次。A 起得早，抢到 1..10；B/C 起得晚，尝试 1..10 全部
  `LEASE_HELD_ELSEWHERE`（吃满 10 个配额），11..20 直接 `MAX_ON_STARTUP_REACHED`。此后没有第二次自动扫描（`scanned` 一次性）。
- **用户影响**：重启后部分"已批准未执行"的轮次停在 SUSPENDED，直到用户手动决策或运维端点；用户看到 `runs/current` 一直是挂起卡。
- **测试**：`StartupResumeRunnerSessionGateTest` 覆盖会话门被占；**无**配额/多实例用例。

### F-07 序号 INCR 失败降级为进程内计数 ⇒ 可能与其它实例/接管方撞号，回放与前端差量会丢帧
- **severity**：Minor
- **确定性**：具体风险（降级路径确定；撞号需要"降级期间另一侧继续发号"）
- **位置**：`backend/.../ai/run/RedisFrameStore.java:183-197`（catch ⇒ `fallbackSeq` 进程内自增）、`:217-236`（`repairRollback` 只在
  `issued<=1 或 issued<=fallbackLast` 时抬号，**抬起后仍可能落在已用区间**）、`:741-743`；消费侧去重 `:485-499`（同号 Stream 覆盖）、
  `SseChatEmitter.java:892-899`（`seq <= lastSeq` 视作孤儿）
- **触发**：本轮 seq 键所在 slot 短暂不可用（节点故障/迁移）→ INCR 抛错 → 用进程内号（可能与另一实例已发号相同或更小）→
  该帧落档后与已有同号帧合并/被前端判孤儿。
- **用户影响**：该帧在前端与回放里**静默消失**（内容缺口），`lastSeq` 仍单调；只有 WARN。
- **测试**：`RedisFrameStoreTest`/`SeqPersistenceTest` 覆盖"降级单调 + 自修复"，**无**"降级号与真实号碰撞"的消费侧后果。

### F-08 跨实例唤醒（pub/sub）丢失没有服务端兜底；恢复完全依赖前端 45s 空闲看门狗
- **severity**：Minor
- **确定性**：已证实（代码级：服务端无周期补帧；兜底只在前端）
- **位置**：`backend/.../ai/run/RedisFrameWakeBus.java:48-56`（失败只 debug）、`SseChatEmitter.java:1526-1538`（发布失败只 debug）；
  消费侧只有 `onWake`（`:1556-1613`），**没有**定时器读归档；前端兜底 `frontend/src/api/sse.ts:36-40`（`DEFAULT_IDLE_TIMEOUT_MS=45s`）、
  `:525/645`（空闲 → reattach，最多 4 次退避）
- **触发**：实例 B 出帧并 `PUBLISH`，订阅连接抖动/容器重连期间丢消息；此后该轮不再有新帧（终帧正好是那条）⇒ A 侧连接静默。
- **用户影响**：最长约 45s"回答停住"后自愈；后台页签的 `setTimeout` 被浏览器节流时，检测/恢复可被拉到分钟级；
  若客户端不是浏览器（或 JS 被挂起），**没有**服务端兜底 ⇒ 永久停住。
- **测试**：无。`FrameWakeListenerTest`/`SseChatEmitterCrossInstanceWakeTest` 都假设唤醒必达。

### F-09 唤醒监听容器手工创建、无销毁：订阅连接与线程不随上下文关闭回收
- **severity**：Minor
- **确定性**：已证实（代码级）
- **位置**：`backend/.../ai/run/RedisFrameWakeBus.java:105-135`（`new RedisMessageListenerContainer()` + `afterPropertiesSet/start`，
  无 `@PreDestroy`/`DisposableBean`）；容器内部 `SimpleAsyncTaskExecutor`（spring-core 6.2.18 字节码：继承
  `CustomizableThreadCreator`，默认 `daemon=false`），且它不是 Spring bean ⇒ 不受容器生命周期管理
- **触发**：进程收到 SIGTERM/上下文关闭。
- **用户影响**：SIGTERM 路径（shutdown hook）仍能退出；但"`context.close()` 后主线程返回"的形态（测试/嵌入式/自管进程）里，
  非守护线程会阻止 JVM 退出；订阅连接依赖 `LettuceConnectionFactory` 关闭被动断开。无数据面影响。
- **测试**：无生命周期用例。

### F-10 Cluster 安全静态门禁有盲点：真实的 `SCAN` 调用点不被规则匹配
- **severity**：Minor
- **确定性**：已证实（规则与代码对照）
- **位置**：`scripts/ci/forbid-redis-cluster-unsafe.mjs:41`（接收者白名单）、`:71-76`（`redis-scan-command` 只匹配 `<receiver>.scan(`）；
  实际调用是 `backend/.../ai/session/RedisClusterScanSupport.java:86`（`connection.keyCommands().scan(...)`）与 `:118`
  （`cluster.scan(node, options)`）—— **都不会被命中**
- **触发**：未来有人在别的类写 `connection.keyCommands().scan(...)` 扫全库；门禁全绿。
- **用户影响**：无直接用户面；影响的是"唯一能拦 Cluster 不安全写法"的自动化门禁的可信度（本报告实跑该脚本：308 文件、0 命中）。
- **测试**：脚本自带 `--self-check`（18 个正反样例 + 数量断言），样例里没有 `keyCommands().scan` 这种形态。

### F-11 `cluster` profile 没有 `server.shutdown=graceful`：draining 窗口结束后不等待在途请求
- **severity**：Minor（仅当部署只用 `cluster`、不叠加 `postgresql`/`h2tcp`）
- **确定性**：已证实（配置对照）
- **位置**：`backend/src/main/resources/application-cluster.yml:38-44`（只有 `app.health.readiness.drain-window: 5s`，
  **无** `server.shutdown`、无 `spring.lifecycle.timeout-per-shutdown-phase`）；对照 `application-h2tcp.yml:42`、
  `application-postgresql.yml:61/68`
- **触发**：`--spring.profiles.active=cluster`（+ 默认 H2 文件库或其它非 PG/H2TCP 数据源）收到 SIGTERM：`ShutdownDrainGate`
  等 5s，然后走 Boot 默认（immediate）停机 ⇒ 在途 SSE 被直接切断。
- **用户影响**：滚动发布时被切实例上的长回答断流（前端带 `lastSeq` 重挂到别处可恢复，但会看到一次"重连"提示）；
  注释里"与生产 profile 同口径"的说法与实际机制（在 PG profile 里）不一致。
- **测试**：`ReadinessDrainWindowTest`、`ProfileConfigContractTest`（只断言 PG/h2tcp 的 `shutdown=graceful`）；
  **无** cluster profile 的停机契约断言。

### F-12 `sessionId` 无长度/形状校验，却直接做 Redis 键名与 hash tag；与历史端点的 128 上限不一致
- **severity**：Minor
- **确定性**：已证实（代码级）
- **位置**：`backend/.../ai/web/AiController.java:313-316`（只判 `hasText`）、`:326`（进限流键）、`:364`（进会话门/记忆键）；
  对照 `backend/.../ai/history/AiChatHistoryService.java` 的 `requireId(sessionId, 128)`；键形 `chat:mem:{<escapedId>}`
  （`RedisChatMemoryRepository.java:187-221`，转义正确但长度不设限）
- **触发**：`POST /api/ai/chat` 带超长 `sessionId`（受 Tomcat body 上限与 60/min/IP 限流约束，仍可达数百 KB）。
- **用户影响**：单个会话在 Redis 里产生数百 KB 的键名（`ai:session:lock:`/`ai:rate:session:`/`ai:session:runs:`/`chat:mem:{…}` 各一份），
  且 `/history` 对同一 id 会 400 —— 前后端语义不一致。
  **备注（多用户隔离）**：本仓没有用户身份，`sessionId` 是客户端自持的唯一隔离依据，服务端不校验"这轮属于谁"；
  "多用户隔离"在结构上等于"sessionId 不可猜 + 浏览器同源"。这是边界事实，不是要求修的缺陷。
- **测试**：`ChatSessionBusyBodyTest` 覆盖 409 形状；`AiChatHistoryServiceTest` 覆盖 128 截断；**无** `/chat` 的 sessionId 长度用例。

### F-13 知识库 local 源把"本机磁盘"当成共享缓存的内容来源（与 `app.file.storage-path` 的守卫不对称）
- **severity**：Minor
- **确定性**：具体风险（需要各实例 `app.kb.dir` 内容不一致；默认从 classpath 播种，通常一致）
- **位置**：`backend/.../ai/kb/CachedKbSource.java:126-137`（`syncLocal`：读本机盘 → 变更就 `syncToCache`）、`:44-49`（"local 源同样过缓存，
  保证多实例同一份字节"的注释）；`KbService.java:312-315`（`index-dir` 各自建目录）；`application.yml`（`kb.dir: ./data/kb`、
  `kb.index-dir: ./data/kb-index`）；对照 `FileStorageMultiInstanceGuard.java:83-136`（只校验 `app.file.storage-path`）
- **触发**：三实例的 `./data/kb` 内容不同（滚动升级一新一旧，或某实例手工改过一篇）：A 刷新 → 写共享缓存 + `INCR` 版本 → B 发现
  版本变化 → 用自己盘刷新 → 再 `INCR` …来回抖动，KB 正文与索引在两版本间跳变，每次跳变都重建 Lucene 索引。
- **用户影响**：同一时刻不同实例答出的知识不一致；索引重建抖动带来延迟尖刺；引用可能指向刚被覆盖的内容。
- **测试**：`KbServiceIncrementalTest`/`KbCacheTest`/`CachedKbSourceTest` 用 `InMemoryKbCacheStore` 与单实例源，
  **无**"两个不同 local 源共享一个缓存"的用例。

### F-14 会话销毁的观测键用 3 键 `DEL`（跨槽）——非原子且失败只记一条 error
- **severity**：Minor
- **确定性**：已证实（代码级；跨槽具体行为取决于客户端：Lettuce 按槽拆分，不报错）
- **位置**：`backend/.../ai/session/AiSessionPurgeService.java:775-781`（`redis.delete(List.of(beatKey, activityKey, cancelKey))`）
- **触发**：Cluster 下销毁一个有轮次的会话：三个键必然不同槽 ⇒ 拆成 3 条命令；失败只 `errors.add(...)`，不进 `complete` 判定也不重试。
- **用户影响**：最坏情况 `ai:cancel:<rid>` 残留到 TTL（24h），期间同 runId 不可能出现，无实际影响；属可观测性/原子性瑕疵。
- **测试**：`AiSessionPurgeServiceTest`（`PurgeFakeRedis` 平铺多键删除）无法表达跨槽。

### F-15 限流第二维按 `RemoteAddr`，反代后全站共用一个 IP 桶（配置已登记，生产形态未验证）
- **severity**：Minor
- **确定性**：已证实（配置 + 代码；影响面取决于是否配 `trusted-proxies`）
- **位置**：`backend/.../ai/gate/AiRateLimiter.java:120-128`、`AiController.java:326`、`application.yml`（`web.trusted-proxies: []` 缺省）；
  `scripts/ci/cluster3-probe.mjs:941`（三实例网显式加 `--app.web.trusted-proxies=127.0.0.1/32` 才能跑）
- **触发**：HAProxy/Nginx 后不配 `trusted-proxies`：`ai:rate:ip:<LB-IP>` 每分钟只有 60 次，一个用户刷满即全员 429。
- **用户影响**：AI 端点对全站"随机"429；`Retry-After` 指向 LB 的桶，用户无法自解。
- **测试**：`AiRateLimiterTest` 只测脚本语义与降级；端到端只有三实例网的自配形态。

### F-16 Cluster 拓扑 fail-fast 是 **profile 门控**而不是"属性门控"
- **severity**：Minor
- **确定性**：已证实（代码级）
- **位置**：`backend/.../config/RedisClusterTopologyGuard.java:29-31`（`@Component @Profile("cluster")`）；
  `application-cluster.yml:27-33`（`nodes: ${AI_REDIS_CLUSTER_NODES}`）
- **触发**：运维直接给 `spring.data.redis.cluster.nodes`（或 `SPRING_DATA_REDIS_CLUSTER_NODES`）而**不**激活 `cluster` profile：
  Spring Boot 照样建 Cluster 连接工厂（`host/port` 被忽略），但 guard 不装配 ⇒ 占位符残文/漏端口的形状错误不再 fail-fast，
  `drain-window` 也仍是默认 0s。
- **用户影响**：与"配错即启动失败"的设计目标相反的一类静默半配置；首次 Redis 命令才炸（`unknown host ${...}`）。
- **测试**：`ClusterProfileConfigTest` 全部带 `spring.profiles.active=cluster`（`:44`），**无**"无 profile + 只配 cluster.nodes"用例。

---

## 5. 可证明正确（带条件）

以下均为**代码级可证**，条件如括注；不是运行期证明。

1. **多键 Lua 的 KEYS 同槽（Cluster 前提）**：`ACQUIRE_SCRIPT` 三键由 `epochKeyFor/hashTagOf` 从租约键推导
   （`LeaseService.java:1042-1067`）；`FENCED_SET`（快照+fence）、`IDEM_PUSH`（台账+索引）、`deleteRun` 的 13 键都走
   `runScopedKey`（`RunStore.java:466-474`）；记忆的 staging/final 用同一 escaped tag（`RedisChatMemoryRepository.java:187-199`）。
   *条件*：所有键都由这些入口生成（`ClusterSlotTest:149-178` 断言 13 键同槽 + 计数器键与推导一致）。
2. **比对式续/放（不会删别人的锁）**：`RENEW/RELEASE/VERIFY` 全在 Lua 内比对完整 `<epoch>|owner>`（`LeaseService.java:226-256`）；
   会话锁 `acquire/renew/release/releaseDead` 四条脚本都在脚本内比对（`SessionGate.java:80-143`）。*条件*：单键命令语义。
3. **"号与占用同序"**：`INCR` 与 `SET` 在同一脚本，取号时与 fence 对账 `epoch=max(INCR,fence+1)`（`LeaseService.java:155-180`）；
   计数器 TTL 与会话同寿（`:1077-1082`）。*条件*：三键真同槽（见 1）。
4. **台账与幂等索引原子**：`LLEN` 在推入前取、`HSETNX` 首写胜（`RunStore.java:340-352`）。*条件*：同槽 + 脚本原文正确（后者无测试，F-03）。
5. **认领 fail-closed**：写权限 LOST/UNAVAILABLE ⇒ 不认领（`RunStore.java:1690-1694`）；fence 读失败不再折 0（`:1796-1810`）；
   认领后读/写失败值比对回滚（`:1765-1781`）。
6. **序号跨实例不重**（正常路径）：`INCR ai:seq:{run:<rid>}`（`RedisFrameStore.java:199-205`）+ 回退自修复（`:217-236`）。
   *条件*：INCR 不降级（F-07）。
7. **差量读正确且 5.0 兼容**：闭区间 `(from+1)-0` + 下限夹到 `tail-window`（`RedisFrameStore.java:399-461`）；空流与读失败可区分
   （`StreamTail.ok`）；`lastSeqIfKnown` 失败返回 empty（`:469-476`，API 层 503）。
8. **回放与挂订阅原子**：同一把 `emitLock` 内先 attach 再读档（`SseChatEmitter.java:670-678`）；每订阅者游标取最小下界（`:1622-1633`）。
9. **会话销毁的集群安全枚举**：逐 master `SCAN` + 每 cursor/连接关闭 + 任一失败上抛（`RedisClusterScanSupport.java:55-136`）；
   `maxKeys=0` 时无截断（归档枚举走这条路，`AiSessionPurgeService.java:608-612`）。
10. **durable pending 与 guard**：pending 无 TTL、guard 读失败 fail-closed、完成/撤销全部 `purgeId` 条件 CAS
    （`SessionPurgePendingStore.java:78-96`、`SessionPurgeGuard.java:106-170`）。*条件*：见 F-04 的交错窗口。
11. **附件跨实例 fail-closed**：登记/读/消费/墓碑任一 Redis 失败都抛，不回落进程内（`RedisFormAttachmentStore.java:80-161`）；
    会话索引写失败回滚登记（`:94-108`）。
12. **完整历史跨实例不重**：两个唯一约束（`db/migration/V8__ai_chat_message.sql:36`、`V9__ai_chat_message_idempotency.sql:12`）
    + 32 次重试（`AiChatHistoryService.java:94/148-167`）。*条件*：PG/H2 唯一索引语义一致（PG 未真跑，§1.3）。
13. **配置跨实例收敛**：DB 版本 + `ai:cfg:ver` + 加载失败不推进 + 运行态版本闸（`AiConfigService.java:187-256`、`AiProviderRegistry` I2）。
14. **ready/draining**：drain 先置 503 再等窗口（`ShutdownDrainGate.java:60-86`），draining 是终态（`ReadinessService.java:102-114`）；
    Redis 只是降级维度（`:143-169`），探针有界（`ReadinessProbeRunner.java:102-143`）。
15. **多实例共享盘闸门**：`app.file.storage-path` 在多实例判据下必须绝对路径 + 占位符/非法路径 fail-fast（`FileStorageMultiInstanceGuard.java:83-136`）。
16. **配置面禁 SELECT/多库/KEYS/FLUSH**：`forbid-redis-cluster-unsafe.mjs` 在快照上实跑 308 文件 0 命中（本报告唯一执行的命令）；
    `application*.yml` 无 `database:`；`RedisClusterScanSupport` 是唯一 `SCAN` 使用者（但门禁有盲点，F-10）。
17. **无 XREAD/XGROUP/XACK**：Stream 只用 `XADD/XRANGE/XREVRANGE`（`RedisFrameStore.java:304-313/436-449/583-595`、`RunStore.java:2540`），
    不存在消费组跨 slot 语义。
18. **`maxmemory-policy=noeviction` 在门禁与三实例网一致**（`.github/workflows/ci.yml:241-245`、`cluster3-run.sh:365`），与
    "AI 键宁报错不丢帧"一致（容量治理把字节上界折算成条数窗口，`RedisFrameStore.java:668-727`）。

---

## 6. 覆盖缺口与"不得当成已验证"的清单

| # | 事项 | 现状 | 风险指向 |
|---|---|---|---|
| G1 | 产品 jar 跑在真 Redis Cluster（3 主 3 从、5.0.14）上 | **无任何自动化**（单测替身 / 三实例网单节点 / 5.0 冒烟只打 `redis-cli`） | F-03、F-02 |
| G2 | Lua 原文执行 | 替身按语义复刻，原文从未执行 | F-03 |
| G3 | 单 master 故障 / MOVED·ASK / slot 迁移 | 未覆盖 | F-02、F-05 |
| G4 | 真实 PostgreSQL（AI 三表 + Flyway + 唯一约束 + `lock_timeout`） | 只有文件级契约断言；workflow 无 PG service | L9、§1.3 |
| G5 | 三实例**并发**冷启动（共享 H2-TCP + Flyway 锁） | 只有 `workflow_dispatch` 的三实例网（本审计未运行） | §2-L13 |
| G6 | 前端多页签并行 + 多用户隔离 | 多页签靠 Web Locks + `sessionStorage`（客户端权威）；服务端无身份 | F-12 备注 |
| G7 | 会话销毁 vs 在飞写入交错 | 无用例 | F-04 |
| G8 | 唤醒丢失后的服务端兜底 | 无 | F-08 |
| G9 | 启动续跑配额（>10 轮、多实例） | 无 | F-06 |
| G10 | `cluster` profile 的停机契约 | 无断言 | F-11 |
| G11 | `sessionId` 形状/长度 | 无 | F-12 |
| G12 | KB local 源多实例一致性 | 无 | F-13 |
| G13 | 观测键/多键 DEL 的跨槽行为 | 替身平铺，无法表达 | F-14 |
| G14 | `ResilientChatService`/`ContextCompactor` 内部 | 本域未逐行（只在接缝处引用） | — |
| G15 | 业务侧并发守卫（导入/发布的 DB 级互斥） | 属 E 域；F-05 的"用户影响"取决于它 | F-05 |
| G16 | PG 与 H2 迁移集差异（PG 独有 `V6__task_child_fk.sql`） | 无跨方言一致性断言（只有各自契约测试） | L9 / §1.2 |

---

## 附录 A：本报告用到的关键证据命令（均只读）

```
# 1) Cluster 安全静态门禁（仓库自带、只读扫描；本报告唯一执行的命令）
cd <仓外证据>/ai-full-audit/tree && node scripts/ci/forbid-redis-cluster-unsafe.mjs
→ "Redis Cluster 安全守卫：扫描 308 个文件，0 命中。"

# 2) jar 字节码（只读反编译，确认 ping() 在 Cluster 下的执行语义）
~/.m2/repository/org/springframework/data/spring-data-redis/3.5.11/spring-data-redis-3.5.11.jar
  .../lettuce/LettuceClusterConnection.class   → ping(): executeCommandOnAllNodes + resultsAsList
  .../connection/ClusterCommandExecutor.class  → collectResults: 任一节点异常 ⇒ ClusterCommandExecutionFailureException
  .../connection/ClusterTopology.class         → getActiveMasterNodes: isMaster && isConnected && !isMarkedAsFail
~/.m2/repository/org/springframework/spring-core/6.2.18/.../SimpleAsyncTaskExecutor.class
  → 继承 CustomizableThreadCreator（默认非守护线程）
```

## 附录 B：与相邻报告的边界

- E 域已指出"AI 资源锁与业务守卫是两套"（E-11）与"任务态联动失败被吞"（E-04）；本报告 F-05 只补"设施不可用时 AI 侧**放行**"
  这一层，不重复评估业务侧守卫强度。
- C 域已覆盖 park/confirm 的帧与决策时序；本报告只在"取租点/租约交棒"处与之相接（F-01 的取租点清单来自同一批代码）。
- H 域覆盖前端渲染；本报告只引其 45s 空闲看门狗与 lastSeq 重挂作为 F-08 的兜底证据。
