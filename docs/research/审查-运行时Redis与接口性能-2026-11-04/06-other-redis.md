# 06 · 其余生产 Redis 使用审查：配置、附件、观测、草案、任务事件与作业协调

- 审查基线：`52b093642402c9455b7cb9627fae6dbdd6544e54`（工作树 HEAD 与基线一致，审查开始时 `git status --short` 无输出）。
- 审查方式：只读静态代码与配置；没有修改生产/测试代码，没有运行测试、构建、CI、服务或压测，没有执行 git 写操作。
- 本报告边界：覆盖未被会话/run/memory/frame/KB 专项接管的 Redis 使用；为说明附件和草案的销毁语义，只阅读并引用会话销毁的相关段，不重复展开其它 run/session 键族。另将 Grep 反查发现的健康探针和展示历史纳入，避免遗漏。
- 确定性口径：“代码证实”指控制流、命令顺序、键形和资源边界可从基线直接推出；不代表已经在运行环境复现。所有具体耗时都取决于实际 RTT、超时和数量，本审查未做运行测量。
- 严重度口径：**高** = 可造成跨实例错误状态、取消/删除语义失效或明显无界时延放大；**中** = 需并发、故障或数据量触发的重要正确性/性能风险；**低** = 明确冗余但通常不破坏正确性。

## 一、结论摘要

共列出 **18 项**：高 8 项、中 9 项、低 1 项。优先处理顺序建议如下：

1. **先修跨实例正确性**：OR-01/02（配置失效信号与 DB 版本竞态）、OR-09（已判死 runner 被远端 ticker 复活）、OR-12（取消提交后共享标志可能未落地）、OR-03（附件上传/收口竞态）。
2. **再拆同步串行放大**：OR-04（会话销毁附件链）、OR-10（心跳 ticker）、OR-11（任务删除/新建时逐历史作业 `EXISTS`）。这些路径均可在特定数量/故障条件下超过 30 秒；这是代码复杂度与超时上界推导，不是实测结论。
3. **随后收敛轮询和读放大**：OR-13～OR-18，并保留任务 SSE 已有的有界降级与数据库快照兜底。

## 二、审查范围与实际阅读文件

### 2.1 全文或主要实现段阅读

- 配置/装配：`backend/pom.xml`；`backend/src/main/resources/application.yml`、`application-cluster.yml`；`ai/config/RedisAvailability.java`、`AiConfigVersionPublisher.java`、`AiModelCatalog.java`、`AiConfigService.java`（快照/版本/读取相关段）、`AiConfigWriter.java`（各写入口、事务与版本相关段）、`AiConfigSeeder.java`、`AiProviderRegistry.java`（缓存与版本闸相关段）、`AiModelConfig.java`、`AiProperties.java`（相关配置段）、`ai/config/db/AiSetting.java`。
- 表单附件：`ai/form/RedisFormAttachmentStore.java`、`FormAttachmentStore.java`、`GenerativeFormAttachmentController.java`、`GenerativeFormGuard.java`（校验/消费/收口段）；`GenerativeFormRules.java` 的字段上限由检索结果核对。
- 观测/草案：`ai/observe/SettlementObservationStore.java`、`RedisDegradation.java`；`ai/run/CreateTaskDraftStore.java`、`SessionKeyRegistry.java`；`ai/gate/ToolCallEndTrace.java`、`RunContextRefresher.java`（写草案段）；`ai/form/ConditionsForm.java`（读草案段）。
- 任务事件：`task/service/RedisTaskEventBus.java`、`TaskEventBus.java`、`TaskSseService.java`；`task/config/TaskEventDispatchConfig.java`、`TaskSseSchedulerConfig.java`；`task/controller/TaskController.java`（事件入口）。
- 作业：`job/service/JobHeartbeatRegistry.java`、`JobCancellationRegistry.java`、`JobWatchdog.java`、`JobService.java`、`JobProgressService.java`、`JobTerminalWriter.java`、`AsyncJobExecutor.java`、`StaleJobRecoveryRunner.java`、`RunnerActiveKeyCleanupRunner.java`；`job/controller/JobController.java`、`job/repo/JobRepository.java`；`config/AsyncConfig.java`、`AppProperties.java`（作业配置段）；`task/service/TaskService.java`（删除段）。
- 健康/拓扑：`health/RedisReadinessProbe.java`、`ReadinessService.java`、`ReadinessProbeRunner.java`、`HealthController.java`；`config/RedisClusterTopologyGuard.java`。
- 展示历史：`ai/history/ConversationTranscriptStore.java`、`ConversationTranscriptConfig.java`、`TranscriptEntries.java`（投影相关段）；`ai/web/AiController.java` 的 chat/resume/history/health 相关段。
- 身份：`ai/tenant/TenantKeyScope.java`、`RequestContextUtils.java`、`RequestContextTaskDecorator.java`、`RunIdentityScope.java`。
- 为说明销毁链：`ai/web/AiSessionPurgeController.java`；`ai/session/AiSessionPurgeService.java` 的枚举、删除、附件与草案段；`ai/session/RedisClusterScanSupport.java`。

### 2.2 反查与排除

对 `backend/src/main/java` 反查了 `StringRedisTemplate`、`RedisConnectionFactory`、`RedisCallback`、`RedisScript`、`opsFor*`、`executePipelined`、`convertAndSend`、SCAN/KEYS 及阻塞等待。直接 Redis 实现还包括 run/session/memory/frame/KB 专项文件；本报告不重复其内部审查。`spike/` 是实验代码，不属于本次后端生产装配面。

## 三、入口 → 服务 → Redis/DB/线程 → 返回的完整调用链

### 3.1 配置读、写与模型清单

- **读配置**：`GET /api/ai/providers|models|settings/defaults` 等 → `AiConfigController`（`ai/web/AiConfigController.java:75-101,201-209`）→ `AiConfigService.current()`（`ai/config/AiConfigService.java:155-171`）→ 每实例至多每秒一次 `GET ai:cfg:ver`（`:245-268`）；键不存在/读失败时查 `ai_setting.config.version`（`:254-281`），需要重载则读 provider/model/setting 三组 DB 表（`:284-309`）→ 内存快照 → 控制器 DTO/200。AI 运行侧经 `AiProviderRegistry` 的版本闸清理或重建运行态（`ai/config/AiProviderRegistry.java:149-197,206-240`）。
- **写配置**：`POST/PUT/DELETE /api/ai/providers...`、`/settings/defaults` → `AiConfigWriter` → `TransactionTemplate` 内写 DB 数据、`bumpInTransaction()` 和审计（`ai/config/AiConfigWriter.java:255-276,335-367,545-563,680-699`）→ 事务返回后 `INCR ai:cfg:ver` → 本实例 DB 重载并再次 GET Redis 版本 → 淘汰本实例运行态（`ai/config/AiConfigVersionPublisher.java:74-103`）→ 控制器 `guarded()` 返回 200 或稳定错误（`ai/web/AiConfigController.java:220-240`）。启动种子走 `ApplicationRunner → AiConfigSeeder → 同一 Publisher`（`ai/config/AiConfigSeeder.java:78-85,94-131,150-199`）。
- **拉模型**：`POST /api/ai/providers/{id}/models/fetch`（`ai/web/AiConfigController.java:160-169`）→ `AiModelCatalog.fetch()` → 配置快照与 key 解析 → 非 refresh 时 `GET ai:cfg:models:<providerId>` → miss 时同步 HTTP 上游探测 → 成功后 `SET ... PX`（`ai/config/AiModelCatalog.java:61-87,90-124`）→ 200，含 `cached/ok`。

### 3.2 表单附件上传、回灌、续期与销毁

- **上传**：HTTP 请求线程 `POST /api/ai/form/attachments`（`ai/form/GenerativeFormAttachmentController.java:116-121`）→ `RunStore.pending/get` 读 Redis 快照/待决项 → `findByForm` 做 `SMEMBERS + 逐件 GET` → 文件写共享存储 → `register` 先 `SET` 登记，再分别用单键 Lua `SADD+PEXPIRE` 写会话/表单索引（`ai/form/RedisFormAttachmentStore.java:145-190`）→ 单附件替换时再删旧登记/文件 → 返回 fileId（控制器 `:186-221`）。没有独立上传线程或 DB 事务。
- **回灌消费**：表单校验 `find/wasConsumed` → 决策先由 run 链落 Redis → `GenerativeFormGuard.settleAcceptedResult()` 再逐件 `find → SET 墓碑 → DEL 登记 → SREM 两索引 → 删文件`，最后枚举并回收未提交件（`ai/form/GenerativeFormGuard.java:357-421,452-470`；存储实现 `RedisFormAttachmentStore.java:216-259`）。
- **挂起续期**：`ConfirmGate` 续期点（`ai/gate/ConfirmGate.java:2323-2332`）→ `GenerativeFormGuard.touchSuspension()` → `SMEMBERS`、跨槽单键 GET pipeline、PEXPIRE pipeline（`ai/form/RedisFormAttachmentStore.java:328-407,420-475`）→ 无直接 HTTP 返回；失败仅记降级并等下一拍。
- **会话销毁中的附件子链**：`DELETE /api/ai/session/{sessionId}`（`ai/web/AiSessionPurgeController.java:71-103`）→ 前置 PING/身份校验 → `AiSessionPurgeService.purge()` → `enumerate()` 中 `findBySessionId`（`ai/session/AiSessionPurgeService.java:566-685`）→ DB 删除 → `deleteAttachments()` 逐件删文件和登记（`:1439-1494`）→ 200 `complete/errors/deferred`，或枚举失败时 aborted。整体 run/session 键链由专项报告负责，本报告只评估附件存储贡献。

### 3.3 结算观测位与任务草案

- **观测位**：内联批循环/决策/放弃/续跑胜者 → `ToolCallEndTrace.emitOnce/emitCustomOnce`（`ai/gate/ToolCallEndTrace.java:56-82,104-120`）→ `SettlementObservationStore.claim()` → 单键 Lua `HSETNX+EXPIRE`，随后再发一次 `EXPIRE` → 赢者发 `tool_call_end`；发射异常时 `HDEL` 释放（`ai/observe/SettlementObservationStore.java:116-170,200-214`）→ 调用方继续 SSE/收尾。Redis 异常时 fail-open 照发并计 `O1`。
- **草案写入**：`create_task` 结算 → `RunContextRefresher.persistCreateTaskDraft()`（`ai/gate/RunContextRefresher.java:308-326`）→ `RunStore.snapshotOwner` 读 Redis 快照以解析 sessionId → `HGET` 旧草案 → 本地合并 → 单键 Lua `HSET+PEXPIRE`（`ai/run/CreateTaskDraftStore.java:144-175,209-269`）→ 写失败只 WARN，本轮 extra 仍返回。
- **草案读取**：`ConditionsForm` → `CreateTaskDraftStore.read()` → 快照归属读取 → `HGET`（`ai/run/CreateTaskDraftStore.java:183-203`）→ 候选或内部失败（`ai/form/ConditionsForm.java:718-736`）。销毁时对会话 HASH 做 `TYPE+DEL`（`ai/session/AiSessionPurgeService.java:1394-1400`）。

### 3.4 任务 SSE 跨实例事件

- **订阅**：`GET /api/tasks/{id}/events`（`task/controller/TaskController.java:192-195`）→ HTTP 线程创建 emitter、首订阅经 `RedisTaskEventBus.subscribe()` → 懒启动 `RedisMessageListenerContainer` 并 `SUBSCRIBE task:events:<taskId>`（`task/service/RedisTaskEventBus.java:201-241,390-425`）→ 初始 HEARTBEAT → 返回 emitter。消息由有界派发池投递，订阅/退订使用另一个有界池；重试使用两条 `task-sse-event` 守护线程。
- **发布**：四个作业 runner 的分片/终态 → `TaskSseService.publish()`（`task/service/TaskSseService.java:235-247`）→ 本机带 15s 写预算投递 → 作业/HTTP 调用线程同步 `PUBLISH`（`task/service/RedisTaskEventBus.java:170-199`）→ 各实例收消息、按 origin 去自身回声、再本机投递。失败/池拒绝丢的是 at-most-once 投影，前端在通道降级时以 3s DB 快照轮询恢复，不作为权威状态。

### 3.5 作业创建、心跳、看门狗、取消和任务删除

- **创建**：`POST /api/tasks/{taskId}/jobs`（`job/controller/JobController.java:26-32`）或 AI 工具 → `JobService.createAndStart()` 的 DB 事务与 task 行锁 → DB 插 PENDING → 同事务内两个同步 `SET` 写 `runner-active/beat`（`job/service/JobService.java:158-231`；`job/service/JobHeartbeatRegistry.java:290-315`）→ commit 后提交到有界 `job-async` 池（core 4/max 8/queue 200，`config/AsyncConfig.java:66-87`）→ 201。异步 finally 调 `endRunner`，依次 DEL beat/anchor 并清本地取消登记（`job/service/AsyncJobExecutor.java:28-45`）。
- **心跳**：runner 分片边界 `JobProgressService.markRunning/snapshot/finish` → `beat()` 两个 SET（`job/service/JobProgressService.java:90-107,137-158`）；另有每实例单线程 ticker，默认每 30s 对所有 `activeRunners` 逐个执行两个 SET（`job/service/JobHeartbeatRegistry.java:397-445,770-800`）。
- **看门狗**：每实例 `job-watchdog` 单线程每 10s 全量查 DB PENDING/RUNNING（`job/service/JobWatchdog.java:145-154,200-230`）→ 对每个非本机 RUNNING 作业 `GET beat`，缺失再 `GET runner-active`（`job/service/JobHeartbeatRegistry.java:492-513`）→ STALE 时 `SET deadjudge NX PX` → DB issue/FAILED → Redis cancel → 清共享键 → 无 HTTP 返回。
- **取消**：`DELETE /api/jobs/{jobId}`（`job/controller/JobController.java:48-56`）→ `JobService.cancel()` 在 REQUIRES_NEW 事务内锁 task、条件更新 DB 为 CANCELLED、收敛条目（`job/service/JobService.java:489-521`）→ commit 后同步 `SET job:cancel:{jobId} PX 1h`、本地 listener、SSE PUBLISH（`:536-560`；`job/service/JobCancellationRegistry.java:102-105,141-179`）→ 200，响应显式含 `redisWritten/wokeInProcess`。runner 仅在配置项/分片边界同步 GET 取消键。
- **任务删除**：`DELETE /api/tasks/{id}`（`task/controller/TaskController.java:90-94`）→ `TaskService.delete()` 在 DB 事务中持 task 行锁 → 查该任务全部历史作业 → 逐个 `EXISTS job:runner-active:<jobId>` → 全部不活才级联删 DB，commit 后删文件（`task/service/TaskService.java:713-783`）。新建作业也在 task 行锁中对全部历史作业逐个判活（`job/service/JobService.java:297-313`）。
- **启动清理/恢复**：`RunnerActiveKeyCleanupRunner(@Order(-1))` → 逐 Cluster master `SCAN job:runner-active:* COUNT 256`，全局最多取 512 个候选 → 对同主机死 PID 做 GET + 单键比对 DEL Lua（`job/service/JobHeartbeatRegistry.java:588-738`）→ `StaleJobRecoveryRunner(@Order(0))` 分页查 DB、逐作业短事务判活和条件 FAILED（`job/service/StaleJobRecoveryRunner.java:98-179`）。

### 3.6 展示历史与健康探针

- **历史读取**：`GET /api/ai/history/{sessionId}?offset&limit`（`ai/web/AiController.java:2136-2192`）→ `ConversationTranscriptStore.read()` → `LRANGE 0 -1`、全量 JSON 解析/entryId last-wins/排序，可能再读 chat memory → 四次 EXPIRE + EXISTS truncated（`ai/history/ConversationTranscriptStore.java:242-269,425-438`）→ 控制器此时才切片并投影本页 → 200。
- **历史写入**：轮首/工具/终态写点 → 显式身份 → 同一 hash tag 四键 Lua 完成 RPUSH、条数/字节裁剪、计数与 TTL（`ai/history/ConversationTranscriptStore.java:98-159,396-420`）→ 写失败只 WARN，不影响本轮返回。
- **健康**：`GET /health/ready` → `ReadinessProbeRunner` 固定四线程、共享 2s 等待 → `RedisReadinessProbe` → 共享 `RedisAvailability.isAvailable()` → 同步 PING（`health/ReadinessProbeRunner.java:102-173`；`health/RedisReadinessProbe.java:42-58`）→ Redis DOWN 只标 degraded、不拉低 ready。`GET /api/ai/health`、chat/resume/context 和会话销毁也复用同一 PING 状态机。

## 四、键、命令、Lua、TTL、身份与 Cluster 总表

| 组件 | 键/通道与身份 | 命令/Lua | TTL | Cluster、多实例与阻塞结论 |
|---|---|---|---|---|
| 配置版本 | `ai:cfg:ver`，全局 | GET；INCR | 无 TTL | 单键无槽问题；跨实例失效依赖双写，见 OR-01/02；同步 GET/INCR |
| 模型清单 | `ai:cfg:models:<providerId>`，全局 | GET；SET/SET PX | 默认 10m；非正配置会不写 TTL | 单键；缓存 miss 可同步上游，Redis 故障只降级为 miss |
| 可用性 | 无键 | PING | 正缓存 0、负缓存 5s | 单实例全局 synchronized，见 OR-17 |
| 附件登记 | `ai:form:att:<t>:<u>:<fileId>`；无身份为 legacy | SET PX/GET/DEL | `session-ttl`，默认 24h | 单键、跨实例共享、写失败 fail-closed；同步 API |
| 附件墓碑 | `ai:form:att:used:<t>:<u>:<fileId>` | SET PX/EXISTS/DEL | 24h | 单键；墓碑先于登记删除 |
| 附件索引 | `...:sess:<t>:<u>:<sid>` 与 `...:form:<t>:<u>:<run>:<call>` | 单键 Lua SADD+PEXPIRE；SMEMBERS/SREM；续期用跨槽单键 pipeline | 24h，随 touch | Lua 无 CROSSSLOT；SMEMBERS 全量且销毁未直接 DEL 会话索引，见 OR-04/05 |
| 结算观测 | `ai:observed:<t>:<u>:<runId>` HASH | 单键 Lua HSETNX+EXPIRE；额外 EXPIRE；HDEL/DEL | 24h | Cluster 合法；Redis 故障 fail-open 是显式取舍；见 OR-07/08 |
| 任务草案 | `ai:ctdraft:{<t>:<u>:session:<escapedSid>}` HASH | HGET；单键 Lua HSET+PEXPIRE；TYPE/DEL | 24h，随写续满 | 单键无 CROSSSLOT；合并本身非原子，见 OR-06 |
| 任务事件 | `task:events:<taskId>`，job/task id 为 DB 全局 id | PUBLISH/SUBSCRIBE/UNSUBSCRIBE | 不适用 | 无多键命令；origin 去回声；PUBLISH 同步，有 5s 本地 DOWN 窗口 |
| runner 活跃 | `job:runner-active:<jobId>`，全局 jobId | SET PX/GET/EXISTS/DEL；单键 GET 比对 DEL Lua | `2×maxExecution+5m`，默认 65m | 单键；与 beat 分开 DEL 避免跨槽；远端撤销缺陷见 OR-09 |
| 作业心跳 | `job:beat:<jobId>` | SET PX/GET/DEL | 默认 90s | 单键；ticker 串行，见 OR-10 |
| 判死单飞 | `job:deadjudge:<jobId>` | SET NX PX | 默认 90s | 单键；无续租，见 OR-18 |
| 作业取消 | `job:cancel:<jobId>` | SET PX/GET | 固定 1h | 单键；分片边界同步 GET；见 OR-12/14 |
| runner 清理 | 模式 `job:runner-active:*` | 逐 master SCAN，不用 KEYS | 候选最多 512 | 有界、逐主节点；单节点失败仅留 TTL，属于 best-effort 清理 |
| 展示历史 | `ai:tx:<t>:<u>:{escapedSid}` 及 `:bytes/:ready/:truncated` | 四键同槽 Lua；LRANGE/EXISTS/SETNX/EXPIRE；销毁 TYPE/DEL | 24h，读也续期 | hash tag 一致；Lua 有 5000 条/约 8MiB 治理；读仍全量，见 OR-16 |

补充：本范围未发现生产 `KEYS *`、BLPOP/BRPOP 等阻塞 Redis 命令；主要阻塞风险来自同步 `StringRedisTemplate` 调用、监听器启动/发布、单线程调度和 Lua 服务端执行，而非 Redis 阻塞列表 API。

## 五、逐项问题

### OR-01 · 高 · 代码证实：Redis 版本未变时永不核 DB，单次 INCR 失败可让其它实例永久旧配置

**证据**：`AiConfigService.checkReload()` 在 GET 到非 null 版本时只比较 Redis 字符串（`backend/src/main/java/com/example/configmgr/ai/config/AiConfigService.java:245-255`）；只有 null/异常才读 DB。`publishAfterCommit()` 的 INCR 失败仅 WARN（`ai/config/AiConfigVersionPublisher.java:89-99`），不会写补偿标记。

**触发条件**：DB 已提交，INCR 因瞬时网络、节点写故障等失败；随后 GET 恢复且旧 `ai:cfg:ver` 仍存在。Cluster  resharding/主从切换期间也属于需覆盖的故障窗口，但本报告不断言具体运维事件必然发生。

**影响**：单例 Redis 与 Cluster 都存在；多实例中未执行写操作的实例可无限期保留旧 provider/model/options，直到下一次成功配置写或重启。单实例因写路径主动 reload 通常不受影响。多线程下各请求共享这份旧快照。

**根因**：把可选失效通知误当成可证明“DB 没有变化”的真源；注释中的“INCR 失败后其它实例回落 DB”只在 GET 也为 null/异常时成立。

**最佳修复**：DB 版本始终作为每个 `reload-interval` 的必检真源，Redis 仅作可选提示；版本不同才加载三张表。当前 fallback 本来就有同频率主键查询，统一走 DB 的实现简单且消除整类漏通知。备选用事务 outbox + 可靠发布 Redis，实现更强但增加表、发布器和运维面；仅改成 SET DB 版本仍不能解决写失败，不足以单独修复。

**修改点/影响面**：`AiConfigService.checkReload/readRedisVersion/readDbVersion`；保留 Redis 字段兼容但不再决定跳过 DB。影响所有配置读与 AI 热路径，每实例增加稳定的主键读；需用 DB 索引与频率观测确认成本。

**后续验证（不执行）**：两实例共享 DB；A 提交后故障注入只让 INCR 失败、随后 GET 正常；B 必须在一个间隔内看到新 DB 版本。再覆盖 Redis key 存在但长期不变化、Redis 全断、Cluster 主节点切换三种情况。

### OR-02 · 高 · 代码证实：DB 配置版本是非原子的读改写，并发提交可同号，运行态版本闸随之失效

**证据**：`bumpInTransaction()` 执行 find → Java `+1` → save（`ai/config/AiConfigVersionPublisher.java:66-85`）；`AiSetting` 无 `@Version` 或原子更新（`ai/config/db/AiSetting.java:51-60`）。远端运行态只按 `snapshot.version()` 是否变化清缓存（`ai/config/AiProviderRegistry.java:206-240`）。

**触发条件**：两个实例/线程并发写配置，均读到版本 N 并提交 N+1；尤其 A 的中间提交已被某实例加载，B 的最终提交仍是 N+1 且其 Redis 通知失败/未被及时处理。

**影响**：DB 最终内容可与某实例加载的同号快照不同；即便之后再加载，同号也不会触发 `AiProviderRegistry` 淘汰，默认模型参数可继续烙在旧运行态中。单例/Cluster 均一样，多实例概率更高。

**根因**：配置写把“数据行 + 版本 + 审计”放在事务里，但版本生成没有数据库串行化；代码注释所称“少跳一格不改变最终可见性”在漏通知和同号运行态闸下不成立。

**最佳修复**：低频配置写在事务**开始**即对 `config.version` 行做 `SELECT ... FOR UPDATE`，再改数据并以单调递增版本提交；或数据库原子 `UPDATE value=value+1` 并读取最终值。前者同时串行化配置写，较易证明快照链。备选乐观锁重试，吞吐高但实现/重试语义更复杂。

**修改点/影响面**：`AiConfigVersionPublisher`、setting repository、`AiConfigWriter/AiConfigSeeder` 的事务顺序；需要明确锁顺序，避免与其它 DB 锁形成环。

**后续验证（不执行）**：两个并发事务修改不同配置项，断言最终版本恰好增加两次、最终快照含两者；让另一实例先读中间版本，再确认最终版本会触发运行态淘汰。与 OR-01 的通知故障注入组合验证。

### OR-03 · 高 · 代码证实：附件上传限额/替换与表单收口均是 TOCTOU，没有跨线程/跨实例互斥

**证据**：上传先 `findByForm`，再按旧列表决定限额和 `superseded`，之后写文件、register、删旧件（`ai/form/GenerativeFormAttachmentController.java:142-213`）。收口在另一调用中重新枚举并删除（`ai/form/GenerativeFormGuard.java:437-470`）。二者没有共同锁；`assertFormIsWaiting()` 也只在文件写入和登记之前检查一次（控制器 `:315-327`）。

**触发条件**：同一表单两个并发上传；或上传已核 PENDING、尚未 register 时，另一实例完成接受/放弃和收口。

**影响**：单附件表单可留下两个新件，件数/20MiB 限额可被并发越过；已关闭表单可在收口后重新留下登记和文件，只能等 TTL。单实例多线程与多实例均可发生；Cluster 不改变该应用层竞态。

**根因**：读取索引只提供快照，登记和收口没有原子状态迁移；文件 I/O 又位于检查之后。

**最佳修复**：引入每表单分布式互斥，上传可先写临时文件，持锁后再次核 PENDING、重新计算配额并完成登记；接受/放弃收口持同一锁枚举回收。锁只覆盖 Redis/短文件操作，必须有 TTL 与 owner token。备选把附件元数据/配额迁移到 DB 事务和唯一约束，长期更易与业务状态一致，但迁移面大。

**修改点/影响面**：上传控制器、`FormAttachmentStore`/`RedisFormAttachmentStore`、`GenerativeFormGuard` 收口、锁键与配置；所有参与方必须遵守同一把锁，否则无效。

**后续验证（不执行）**：单 JVM 两线程及两实例各执行并发上传、单附件重复替换、上传与接受/放弃交错；断言件数/字节不越界、关闭后无新登记，故障时临时文件有清理。

### OR-04 · 高 · 代码证实（时延未实测）：会话销毁附件链是 N 倍串行 Redis + 文件 I/O，可超过 30 秒

**证据**：`findBySessionId` 为 SMEMBERS 后逐件 `find()` GET（`ai/form/RedisFormAttachmentStore.java:269-306`）。`deleteRegistration` 又 GET，再 DEL 登记、DEL 墓碑、两次 SREM（`:564-593,685-699`）。销毁对每件还做路径 resolve、文件存在判断和删除（`ai/session/AiSessionPurgeService.java:1439-1494`）。即健康路径约为枚举 `1+N` 次命令、删除每件再 5 次命令，全部串行且含文件 I/O。

**触发条件**：会话跨多轮/多表单积累 N 个索引成员；Cluster 下键分散不会减少应用层命令数；慢 RTT、部分槽不可用或慢共享文件系统会进一步放大。若多条命令分别等 2s，少量附件即可超过 30s；实际阈值依赖失败落点，不能仅凭默认超时断言每次都会如此。

**影响**：HTTP 销毁线程长时间占用，pending 重试还会重复枚举；多实例各自重试会放大 Redis/存储压力。单例与 Cluster 都有 N 倍问题，Cluster 还增加跨节点路由。

**根因**：已有 touch pipeline 优化没有复用到销毁；枚举已经拿到 Attachment，删除却又读一次，且缺少批量/总预算。

**最佳修复**：枚举用单键 GET pipeline；删除时复用已枚举元数据，按批 pipeline 执行单键 DEL/SREM，避免 MGET/多键 DEL 跨槽；文件删除保持有界并记录每件结局。整个 HTTP 调用增加明确时间预算，超预算保留 pending 并返回可重试进度。备选全异步 202 工作流可改善请求占用，但改变 API 契约，不应作为首选隐式变更。

**修改点/影响面**：`RedisFormAttachmentStore` 枚举/删除接口、`AiSessionPurgeService.deleteAttachments`、销毁结果进度与重试；需保持“文件/登记谁失败谁留痕”的现有语义。

**后续验证（不执行）**：构造 0/1/100/1000 个成员及 stale 成员，standalone/Cluster 分别统计命令数与 p95；对某一槽/文件系统注入延迟，验证有界返回和 pending 重试不重复删错文件。另做同会话并发销毁。

### OR-05 · 中 · 代码证实：会话附件索引没有销毁期 DEL，stale 成员可在 complete 后残留到 TTL

**证据**：枚举会过滤登记已不存在的 id（`ai/form/RedisFormAttachmentStore.java:296-305`），销毁只遍历过滤后的 live 列表（`ai/session/AiSessionPurgeService.java:1439-1494`）；该服务没有删除 `ai:form:att:sess:...` 的路径。SREM 只对实际删除的 live 登记执行。

**触发条件**：登记先过期、消费时 SREM 失败，或历史索引已有 stale 成员；随后会话销毁。

**影响**：列表不含这些 id，但会话索引键和 stale 成员仍可存在到 TTL，和“全部 AI 归属数据删除”的目标不一致；多实例/Cluster 相同。残留通常不泄露登记正文，但会保留 id 元数据并增加后续无效读取。

**根因**：把索引当隐式空集回收，忽略了 Redis 只有空 SET 才自动删除，stale 成员不会因登记过期而消失。

**最佳修复**：附件主体无错误时对该身份/会话的索引做精确单键 DEL；若有失败则保留用于重试。可暴露 store 的 `purgeSessionIndex(sessionId)`，避免在销毁服务复制键形。备选所有枚举后逐个 SREM 也能清理，但命令更多。

**修改点/影响面**：`RedisFormAttachmentStore` 键方法/清理接口与 `AiSessionPurgeService`；不得删除其它身份同名会话的键。

**后续验证（不执行）**：分别制造登记过期、SREM 故障和正常 live 三类成员，销毁后用 EXISTS/SMEMBERS 验证索引仅在主体成功后消失；两租户同 sessionId 做隔离验证。

### OR-06 · 中 · 代码证实：任务草案的“读旧值—合并—写回”会丢并发更新，读失败还可能按空旧值覆盖

**证据**：`save()` 先 `mergeWithExisting()` 再执行 PUT Lua（`ai/run/CreateTaskDraftStore.java:159-171`）；合并函数 HGET 旧 JSON 后在 JVM 拼 defCodes/conditions（`:209-235`）。HGET 的 RuntimeException 被 WARN 后按 `existing=null` 继续（`:211-220`），所以“GET 失败但后续 EVAL 成功”会以不完整副本覆盖。

**触发条件**：同一身份/会话/taskId 的两个结算并发提供不同半边信息；或旧值读取瞬时失败而写恢复。

**影响**：后写者会丢另一方新 defCodes/conditions；跨轮回退得到旧候选。单实例多线程、多实例、单例/Cluster 均一样。Lua 只保证最后 HSET+TTL 原子，并不保护前面的合并。

**根因**：把 RMW 的写步骤原子化，却没有 CAS/锁覆盖读步骤；同时把读基础设施故障降级成“没有旧值”。

**最佳修复**：读失败直接中止写；用单键 Lua 比较 ARGV 中的旧原文后 CAS，冲突时重读合并，或将 defCodes/conditions 拆成独立 HASH field，让每次只更新明确提供的 field。后者命令简单，但要迁移 JSON 读取格式；CAS 保留现结构。

**修改点/影响面**：`CreateTaskDraftStore` 写协议、序列化/读取兼容、`RunContextRefresher` 错误处理；sessionKey/TTL 可不变。

**后续验证（不执行）**：两写者 barrier 卡在 HGET 后，覆盖互补字段与同字段冲突；注入“GET 失败、EVAL 可写”，断言不覆盖旧值。Cluster 验证 CAS 仍只触碰一个键。

### OR-07 · 低 · 代码证实：观测位每次 claim 都多一次无必要 EXPIRE，正常路径翻倍为两次往返

**证据**：Lua 已无条件 EXPIRE（`ai/observe/SettlementObservationStore.java:75-79`），`claim()` 成功后每次仍调用 `ensureTtl()`（`:130-134`），后者再发 EXPIRE（`:200-207`）。

**触发条件**：每次需要仲裁的工具结算，无故障也发生。

**影响**：增加同步 RTT 与 Redis QPS；多实例/Cluster 下跨槽 RTT 更明显。无正确性损失，fail-open 语义不变。

**根因**：为旧无 TTL 数据设计的自愈被放在每拍热路径，而脚本本身已经原子保证新数据 TTL。

**最佳修复**：正常 claim 删除额外 EXPIRE；旧键修复交给一次性迁移/低频维护，或只在明确检测到无 TTL 时处理。若无法承担检测命令，保留脚本保证并依靠最终清理即可。备选维持现状，成本是可预测的额外 RTT。

**修改点/影响面**：仅 `SettlementObservationStore.claim/ensureTtl` 与对应观测计数。

**后续验证（不执行）**：对正常/旧无 TTL/脚本失败三路径分别记录命令序列，断言正常只有一条 EVAL，旧数据仍有明确清理方案。

### OR-08 · 中 · 代码证实：观测位释放没有 owner/token 条件，迟到释放可删新一代占位

**证据**：claim 值包含时刻和实例（`ai/observe/SettlementObservationStore.java:125-134`），但 API 只回 boolean；release 直接 `HDEL key toolCallId`（`:152-170`），不核对值。

**触发条件**：A 抢位后发射长时间阻塞，键因 TTL/purge 消失；B 对同 run/toolCall 建立新占位后，A 才进入异常释放。常规快速路径窗口很小，但代码未封闭。

**影响**：B 的占位被删，后续执行体可再发一条 end/审计，破坏跨实例去重。多线程/多实例均可能；Cluster 不相关于槽位。

**根因**：值里有实例信息却未形成 fencing token，释放操作缺少所有权。

**最佳修复**：claim 返回带随机 token 的 handle，release 用同键 Lua `HGET 比对 token → HDEL`；调用方保存并传递 handle。备选永不释放会转成“发不出去就永久缺行”，不建议。

**修改点/影响面**：`SettlementObservationStore` claim/release 签名及 `ToolCallEndTrace` 两个调用点；单键 Lua 保持 Cluster 兼容。

**后续验证（不执行）**：用可控发射阻塞模拟 A 过期/purge、B 重新 claim、A 迟到 release；断言 B 的 field 仍在。另验证同 owner 正常释放和失败重试。

### OR-09 · 高 · 代码证实：revokedJobIds 仅在本实例生效，远端 runner 可重写已判死作业的共享键

**证据**：`revokeRunner()` 只在当前 JVM 添加 `revokedJobIds` 并删共享键（`job/service/JobHeartbeatRegistry.java:335-345`）。真正跑作业的 A 实例若未被本地判死，其 `tickOnce()` 仍遍历 activeRunners（`:397-404`），`renewRunnerActive/writeSharedBeat` 只检查**A 自己**的 revoked 集合（`:770-800`）。类注释所称“判死后迟到 beat 不得写回”因此不跨实例成立。

**触发条件**：B 判死/取消并 revoke，A 的 runner 实际仍活或迟到运行；A 下一拍 ticker/beat 到达。

**影响**：`job:runner-active/job:beat` 被重新创建，任务删除和后续新作业又被旧 runner 阻塞至 TTL；共享状态与 DB FAILED 相互矛盾。单例多实例逻辑也可由独立 registry/进程出现，Cluster 仅改变存储拓扑。

**根因**：撤销是没有共享 fence 的本地事实；共享写也无 owner/epoch 条件。`endRunner/clearSharedRunnerState` 的非条件 DEL 同样没有代际保护。

**最佳修复**：为每次 runner 建立共享 generation/token，判死写持久 revoked/fence；begin/beat/ticker/hold/release/end 均用同槽 Lua 验证“未撤销且仍为本代”后写/删。可将 runner-active/beat/revoked 改为 `{jobId}` 同槽键族，避免 CROSSSLOT。备选每次 ticker 查 DB 终态简单但增加每作业 DB QPS，且仍需解决写竞态，不宜单独采用。

**修改点/影响面**：`JobHeartbeatRegistry` 全部共享写删、`JobWatchdog` revoke、启动清理脚本和旧键迁移；与 OR-12 的取消/outbox 协同设计。

**后续验证（不执行）**：A 持续 ticker、B 判死 revoke；断言 A 后续任何 beat 都不能重建键。覆盖迟到的 endRunner、新代 runner、进程重启、Cluster 槽迁移与 fence 过期边界。

### OR-10 · 高 · 代码证实（耗时尚未实测）：单线程 ticker 对所有 active/排队作业串行两 SET，故障时可远超 90s 判死窗

**证据**：`tickOnce()` 对 activeRunners 逐作业调用两个同步 SET（`job/service/JobHeartbeatRegistry.java:397-404,770-800`）；scheduler 是单线程 fixed-delay（`:412-445`）。`beginRunner` 在异步任务入队前就加入 activeRunners（`:290-315`），所以集合不只是 8 条运行线程，还可包含队列 200 个任务（`config/AsyncConfig.java:69-78`）。默认周期 30s、TTL 90s（`application.yml:449-472`）。

**触发条件**：activeRunners 数量增长、Redis 慢/不可用、部分键持续超时。极端但容量允许的 208 个登记若每条都等 2s，则单拍理论命令等待上界可达 `208×2×2s=832s`，远高于 TTL；这是配置与串行代码推导，不是实测。

**影响**：靠后作业心跳过期，远端看门狗可把活作业/排队作业判死；单线程被长时间占用，后续周期继续延迟。多实例各自 ticker 又会同时打 Redis。

**根因**：无批量、无单拍总预算、无 DOWN 熔断；把 PENDING 排队项也纳入高频短心跳。

**最佳修复**：先区分 RUNNING 与仅排队项：PENDING 由 DB 在途状态承担删除/超时语义，不必刷新 90s beat；RUNNING 心跳用跨槽单键 SET pipeline/按 master 有界分组，并加单拍 deadline 与负缓存熔断。每拍记录完成数/跳过数/最老成功时刻。备选少量有界并发可降 RTT 放大，但故障时仍需熔断，不能只加线程。

**修改点/影响面**：`JobHeartbeatRegistry` active 集合与 ticker、`JobService` 登记时机、`AsyncJobExecutor` 开工点、看门狗指标；需保留排队任务的 runner-active 删除闸门或明确用 DB PENDING 代替。

**后续验证（不执行）**：覆盖 1/8/200+ 登记、全 Redis 断、单槽慢、长 GC 四类场景，断言一拍有界、无活作业被误杀；standalone/Cluster 均统计每拍命令和总等待。

### OR-11 · 高 · 代码证实（时延未实测）：任务删除和新建作业在 task 行锁内逐历史作业 EXISTS

**证据**：`TaskService.delete()` 持 `@Transactional` task 行锁，取全部 jobs 后用 stream 逐个 `isRunnerAlive`（`task/service/TaskService.java:713-753`）。redis 模式每次至少 `EXISTS job:runner-active:<id>`（`job/service/JobHeartbeatRegistry.java:363-386`）。`JobService.assertNoRunnerInFlight()` 同样在创建事务/task 锁内遍历全部历史（`job/service/JobService.java:158-167,297-313`）。

**触发条件**：任务反复运行积累 H 个历史作业；每次删除/新建产生最多 H 次串行 Redis 往返。故障时第一条失败通常 fail-closed 退出，健康但高 RTT 时则会完整扫 H 条。

**影响**：接口时延 O(H)，DB 连接和 task 行锁同时被占用；同任务的其它写与删除排队。N 足够大时可超过 30s；Cluster 下分散槽位仍存在逐键 RTT。

**根因**：为覆盖“终态但 runner 未退出”而检查全历史，没有利用 TTL/状态缩小候选，也未 pipeline 或移出锁临界区。

**最佳修复**：先读轻量 job id/status；以 pipeline 批量单键 EXISTS 取代串行调用，并在持 task 锁后只对必要候选复核。更彻底可维护 task 级活跃 runner 集合，但需与 begin/end/撤销同事务/ fencing，复杂度更高。不要简单只查终态最近一条，这会漏掉较早但仍未退出的 runner。

**修改点/影响面**：`JobHeartbeatRegistry` 增加批量判活、`TaskService.delete`、`JobService.assertNoRunnerInFlight`；DB 锁顺序与 fail-closed 409 必须保持。

**后续验证（不执行）**：构造 0/10/1000 历史作业并混有活跃旧 runner，记录 Redis 命令数、task 锁持有时间和响应；Redis 不可读仍须快速、明确 fail-closed，不能误判可删。

### OR-12 · 高 · 代码证实：DB 已取消而 Redis 写失败仍返回 200，远端 runner 可继续；固定 1h TTL 还与可配作业上限脱钩

**证据**：取消先提交 DB CANCELLED，再写 Redis（`job/service/JobService.java:489-521,536-560`）；`writeRedisFlag` 失败只回 false（`job/service/JobCancellationRegistry.java:169-179`），控制器仍 `ApiResponse.ok`（`job/controller/JobController.java:48-56`）。非执行实例没有本地加速位，读侧 Redis 异常又按未取消（注册表 `:157-166`）。TTL 固定 1h（`:42-43`），而 maxExecutionTime 可配置且仅要求大于判死窗（`config/AppProperties.java:95-131,157-164`）。

**触发条件**：取消请求落在非执行实例且 Redis 写失败；或执行线程长临界段超过取消键 TTL 才再次轮询。默认 30m 上限小于 1h，TTL 项主要在调大上限/超长段时触发。

**影响**：用户得到 HTTP 成功且 DB 已 CANCELLED，但远端执行体可继续做文件/暂存等动作到下一边界，条件 DB 更新只能拦部分迟到写。响应中的 `redisWritten=false` 提供诊断，并不等于执行取消成功。单实例本地 listener 通常缓解，多实例风险最高。

**根因**：权威 DB 状态、共享取消通知和执行侧轮询没有可靠投递/重试；取消键寿命不是从作业上限派生。

**最佳修复**：取消事务内同时写 DB outbox，后台有界重试发布 Redis，执行侧在边界同时核 DB 终态/取消事实；TTL 至少覆盖配置化最大执行时间加余量，或终态前不依赖过期。备选 Redis 预写失败则整请求 503、不提交 DB，实现较小但有“Redis 已写、DB 后失败”的反向窗口，需用幂等 id 和状态复核处理。

**修改点/影响面**：`JobService.cancel`、取消 registry、runner 四个边界、outbox/重试组件、TTL 配置派生；与 OR-09 共用 fence 更安全。

**后续验证（不执行）**：A 执行、B 取消，分别注入 Redis 写失败和恢复，断言有界重试最终生效且期间不错误宣称执行已停；把作业上限配到大于 1h，并在长临界段两侧验证取消不过期。

### OR-13 · 中 · 代码证实：每实例看门狗都全量扫在途 DB，并逐作业串行 GET，故障时扫描周期失去时效

**证据**：每个实例固定每 10s 调 `findByStatusIn(PENDING,RUNNING)` 全量列表（`job/service/JobWatchdog.java:145-154,200-230`）；每个非本机 RUNNING 调 `readSharedBeat`，先 GET beat，缺失再 GET anchor（`job/service/JobHeartbeatRegistry.java:492-513`）。判死单飞只在判定之后，不减少扫描/读放大。

**触发条件**：多实例 × 多在途作业；Redis 慢时每个作业可串行等待命令超时。该扫描没有共享 deadline 或批量读。

**影响**：DB/Redis QPS 随实例数线性增长；单拍耗时可能远超 10s，fixed-delay 后续拍延后，失联发现不再符合“90s+10s”的设计直觉。与 OR-10 可互相放大。

**根因**：为避免重复 issue 加了 per-job 判死锁，但没有扫描分片/领导租约，也没有读侧 pipeline/熔断。

**最佳修复**：扫描租约或稳定分片只分配一部分实例执行，租约需续期和故障接管；候选用轻量投影分页，心跳用单键 GET pipeline，并设置整拍 deadline。备选保留全实例冗余扫描但加共享快照缓存，需防止把旧心跳当活证据。

**修改点/影响面**：`JobWatchdog` 查询/循环、repository 投影、`JobHeartbeatRegistry` 批量读、扫描指标；不能破坏 `retainTracked` 所要求的全量归属语义，分片后需按分片维护判重集合。

**后续验证（不执行）**：2/3 实例、0/100/1000 在途、Redis 全断/单节点慢；断言同一作业只由一个扫描者处理、租约可接管、每拍有界且无重复 issue。

### OR-14 · 中 · 代码证实：作业取消轮询没有 DOWN 退避，每个分片边界都可同步等 Redis 超时

**证据**：本地 flag 未置位时每次 `isCancelled()` 都 GET（`job/service/JobCancellationRegistry.java:82-91`）；异常仅返回 false（`:157-166`），没有负缓存。四个 runner 在配置项/分片边界反复调用，例如 `ImportJobRunner.java:100,174`、`ExportJobRunner.java:138,206`、`PrecheckJobRunner.java:114,220`、`PublishJobRunner.java:167,237`。

**触发条件**：Redis 不可用且作业在其它实例未被本地取消；每个边界都可能增加一次命令超时。心跳两个 SET 和 SSE PUBLISH 还可能叠加，但任务总线有自己的 DOWN 窗口。

**影响**：批量作业在故障期间按边界线性变慢，最多 8 条作业线程同时打 Redis；“读失败=false”还会看不到远端取消。单实例本地取消命中后不再 GET，风险主要在跨实例。

**根因**：取消读没有复用运行侧已有的负缓存/UNKNOWN 语义；作业取消正确性只依赖这个同步读。

**最佳修复**：由 OR-12 的 DB/outbox 提供可靠状态，同时为每作业轮询加最小间隔、负缓存和降级指标；Redis 不可读时不得把“未取消”当确定事实，可选择暂停副作用并查 DB。备选仅加 5s 熔断可改善性能但不能修复取消丢失。

**修改点/影响面**：`JobCancellationRegistry` 读侧、四个 runner 边界、指标；应防止长临界段把轮询间隔变成无限取消延迟。

**后续验证（不执行）**：Redis DOWN 时运行多分片作业，统计每边界等待和总吞吐；恢复后取消必须可见；对不可读时的 DB 复核/暂停策略做故障测试。

### OR-15 · 中 · 代码证实：readiness 将 Redis 永远视为非硬依赖，但生产作业创建实际硬依赖 Redis，错误码还误报“作业在运行”

**证据**：`RedisReadinessProbe.required()` 恒 false（`health/RedisReadinessProbe.java:42-46`），所以 ready 仍 200。生产默认 `runner-active-store: redis`，`beginRunner` 写失败即不得启动（`job/service/JobHeartbeatRegistry.java:287-310`）；`JobService` 把该故障映射成 `ConflictException(JOB_ALREADY_RUNNING)`（`job/service/JobService.java:208-217`）。

**触发条件**：Redis DOWN，LB 继续向 ready=UP 的实例分配新建作业请求；或删除/收尾状态不可读。

**影响**：实例看似可接全部流量，实际作业创建必败；客户端得到语义错误的 409，可能按“已有作业”处理而不是稍后重试/切换实例。Cluster 与单例相同。

**根因**：健康模型只把 Redis 描述为 AI 降级设施，未纳入后续作业硬依赖；基础设施异常复用了业务冲突码。

**最佳修复**：按 `runner-active-store` 和实例声明能力区分 readiness：若该实例承担作业创建，Redis DOWN 应 not-ready 或至少 `capabilities.jobs.degraded` 并配合路由；创建失败用独立 503 `JOB_REDIS_UNAVAILABLE`。备选只修错误码、保留 ready，需要接受 LB 不摘流的明确取舍。

**修改点/影响面**：`RedisReadinessProbe`/能力模型、`JobService` 错误映射、错误码与前端重试；不要简单全局 required=true 而不评估 AI 与纯业务流量的路由策略。

**后续验证（不执行）**：Redis DOWN 时分别请求 ready、新建作业、取消、任务删除，断言路由和状态码一致；memory 模式不得被误摘流。

### OR-16 · 中 · 代码证实：历史分页在全量 LRANGE/解析/去重之后执行，页面大小不能限制 Redis 与 CPU 成本

**证据**：存储读固定 `LRANGE key 0 -1` 并逐条 parse、last-wins、排序（`ai/history/ConversationTranscriptStore.java:242-269`）；控制器在 read 返回后才按 offset/limit `subList`（`ai/web/AiController.java:2146-2179`）。容量最多 5000 条/约 8MiB，另有四次串行 EXPIRE 与 EXISTS truncated（存储 `:425-438`）。

**触发条件**：长会话接近容量上限，即使只请求最新一页也读取全部；刷新/“加载更早”反复发生。多用户/多实例会增加 Redis 出站流量和应用 JSON CPU。

**影响**：响应时延、堆分配和 Redis 网络量随全会话而非 page 增长；standalone/Cluster 相同。容量有上限，因此不是无限增长，但 8MiB/5000 条每页读取仍是明确放大。

**根因**：append-only LIST 的 last-wins 去重与展示排序依赖全量物化，后来增加的控制器分页没有改变存储协议。

**最佳修复**：以 entryId HASH 保存最新值，配合按 `turnStartedAtMs/追加序号` 的 ZSET/分页索引，读取只取本页并单独维护 total/truncated；或把展示历史迁移到支持分页/upsert 的 DB。备选按会话版本缓存完整物化快照可减少重复解析，但多实例一致性和内存预算更复杂。TTL touch 可合并为单键 pipeline。

**修改点/影响面**：`ConversationTranscriptStore` 数据结构与读写迁移、`AiController` total/hasMore、销毁四键清单；必须保留 last-wins 与 preflight carry 语义。

**后续验证（不执行）**：5000 条/8MiB 会话分别读取首页/中间页/末页，断言 Redis 返回字节与解析数随 page 而非 total；并发 append 同一 entryId 时去重、排序和 total 正确，旧格式可迁移。

### OR-17 · 中 · 代码证实：共享 RedisAvailability 用全局 monitor 串行 PING，正缓存为 0 时并发等待者会逐个再 PING

**证据**：健康时 `POSITIVE_TTL_MS=0`（`ai/config/RedisAvailability.java:38-53`）；所有真探活都在 `synchronized(this)` 内（`:71-100`）。等待者拿到锁后因正缓存 0，不会共享刚完成的正结果，而会再次 PING。AI chat/resume/health、销毁和 ready 共用此 bean。

**触发条件**：多请求同时探活，尤其 Redis 慢但仍成功时；N 个等待者可形成 N 次串行 PING。持续失败时负缓存会让后续等待者快速退出，因此不能把“全断”一概推导成 N×2s。

**影响**：健康高并发增加不必要全局串行点；慢成功时接口排队。ReadinessProbeRunner 超时只取消 Future，monitor 等待不可由中断直接释放，探针线程可能稍后继续占用池位（`health/ReadinessProbeRunner.java:145-173` 的边界需一并考虑）。

**根因**：为消除正缓存漏检，把“每次调用独立 PING”和“同一时刻只允许一个 PING”组合起来，却没有 single-flight 结果共享。

**最佳修复**：用 single-flight future/原子状态让同一批并发调用共享一次刚完成的 PING，不设置长时间正缓存；负缓存仍保留 5s，新一批请求可重新探。备选极短正缓存可减 QPS，但重新引入代码注释已指出的故障漏检窗口，不应直接回退。

**修改点/影响面**：仅 `RedisAvailability` 状态机及指标；需保持任务事件总线 `isKnownUnavailable` 的零命令语义。

**后续验证（不执行）**：用受控慢 PING 并发 20 请求，断言同批只发一条且全部共享结果；随后立刻故障时下一批必须能发现，不引入长正缓存。另验证 ready 超时后的线程回收。

### OR-18 · 中 · 代码证实：判死单飞租约只覆盖固定 90s，慢判定可过期后重复写 issue

**证据**：deadjudge 为 `SET NX PX beatTtl`（`job/service/JobHeartbeatRegistry.java:518-555`），默认 90s；取得后依次写 issue、终态、取消和 revoke（`job/service/JobWatchdog.java:328-400`），没有续租或 fencing。issue 写在条件终态之前，并单独捕获异常（`:361-376`）。

**触发条件**：持锁实例在判死动作中 DB 锁等待/慢 IO 超过 90s；另一实例随后取得同一 job 的租约。

**影响**：第二个实例可再写一条作业级 ERROR issue；条件 UPDATE 只保证终态先到者胜，不会撤销第一条 issue。多实例/多线程扫描均可触发，单例/Cluster 相同。

**根因**：把固定 TTL 当互斥窗，却没有给耗时不定的 DB 动作续租；issue 又不在 DB 条件终态的同一原子结果里。

**最佳修复**：判定期间续租并携带 token，所有收尾动作前验证持有权；issue 与终态最好收敛到同一 DB 事务/唯一幂等键，避免只依靠 Redis 租约保证 DB 不重复。备选把 issue 放到终态 CAS 成功之后能减少重复，但当前完成器封装需返回“本次是否写终态”，且失败补偿要明确。

**修改点/影响面**：`JobHeartbeatRegistry` 判死租约、`JobWatchdog.judge`、`JobTerminalWriter` 结局返回、issue 幂等约束；与 OR-09 的 fencing 可复用 token 设施。

**后续验证（不执行）**：A 持租约后人为阻塞超过 TTL，B 开始判定；断言旧 A 不能继续写、最终仅一条 issue 和一个终态。覆盖 A 崩溃、DB 写失败、租约续期失败。

## 六、轮询与后台节拍汇总

| 路径 | 节奏/上界 | 审查结论 |
|---|---|---|
| 配置版本 | 请求驱动、每实例至多 1s 一次；不是独立 scheduler | 有 refreshLock，正常不全量读 DB；漏通知问题见 OR-01/02 |
| Redis 探活 | 正缓存 0、负缓存 5s；AI/health/任务事件恢复共享 | 有负缓存；正路径全局串行见 OR-17 |
| 任务 SSE 订阅补偿 | 10/20/40/60s，最多 5 次且 5m 窗口；用尽后仅 publish 驱动至多 60s 一次 | 有专用两线程调度、退订取消、显式无身份；未发现永续重试链 |
| 任务 SSE 前端快照 | 通道降级时 3s；hidden 停表、在途合并、realtime 停周期表 | 读 DB 快照而非 Redis；能兜底 Pub/Sub 丢帧 |
| 作业 ticker | 每实例单线程，默认 30s fixed-delay | 串行无界于 active 数，见 OR-10 |
| 作业 watchdog | 每实例单线程，默认 10s fixed-delay | 全实例全量扫描见 OR-13；判死租约见 OR-18 |
| 作业取消 | 配置项/分片边界触发，无独立定时器 | 长临界段取消发现可很晚；Redis 故障无退避见 OR-12/14 |
| runner 启动清理 | 每进程启动一次；SCAN 最多 512 候选、逐 master | 不用 KEYS，有界且失败留 TTL；未发现 Cluster 漏扫主节点 |
| 历史读取 | 刷新/用户加载触发，无固定 Redis 轮询 | 单次全量读见 OR-16 |

## 七、未发现问题或已有明确保护的关键路径

1. **Cluster 键与 Lua**：附件索引、观测位、草案都使用单键脚本；展示历史四键使用同一 escaped session hash tag。job 的 beat/anchor 清理刻意分两次单键 DEL，未组成跨槽 DEL。除本报告指出的应用层竞态外，未发现这些脚本存在 CROSSSLOT。
2. **跨槽 pipeline**：附件 touch 在 pipeline 内逐条 GET/PEXPIRE，而不是 MGET/多键 Lua，方向正确；实际 Cluster 网络往返不必然等于注释所称固定一次，但不应据此误判为命令正确性故障。
3. **附件失败姿态**：登记写/索引写失败 fail-closed，上传控制器回收已落盘文件；消费先墓碑后删登记，DEL 的胜负保证并发消费只有一方返回 true。这里的“一次性返回”成立，不等于整个 GET+SET+DEL 是一个原子脚本。
4. **草案销毁**：会话一个 HASH、按同键 TYPE+DEL，无需 SCAN；TTL 与写入在同一单键 Lua，未发现“有值无 TTL”的新写路径。
5. **观测降级**：Redis 异常 fail-open 会重复观测而不吞行，并写 `O1` 计数；这是显式可用性取舍，不应误报为静默失败。其额外 EXPIRE 和释放 token 问题另列。
6. **任务事件**：origin 去自身回声、首订阅/末退订、有界且分离的消息/订阅池、有界重试和 DB 快照兜底均在代码中成立。PUBLISH 是同步 API，首次故障可等命令超时，但本地 DOWN 窗口避免每帧都等；本报告未发现需改变其 at-most-once 契约的错误。
7. **runner 清理**：启动 SCAN 逐 master、跳过 replica、有游标关闭、有全局候选上限；删除使用值比对 Lua，可避免清理期间误删同 job 新值。
8. **身份传播**：配置/模型/job/task 事件使用全局 id，不需要租户键。附件/观测/草案使用 `TenantKeyScope`；作业 `@Async` 有 `RequestContextTaskDecorator`，job ticker 显式 `denied()`，任务 SSE 重试也显式清空身份。所读路径未发现把排期调用者身份误写入这些全局键的情况；run 内部恢复身份仍由专项结论覆盖。
9. **配置/模型常规性能**：配置快照避免每轮全表读；模型缓存有 TTL 且 Redis 故障可降级为 miss。除 OR-01/02 外，未发现模型缓存键的身份或 Cluster 多键问题。多实例 cache-miss 可同时打上游，属于可接受的低频人工操作取舍，未单列为缺陷。
10. **超时与就绪调用方有界**：Redis 默认命令/连接超时为 2s，Cluster profile 同值并限制 redirects；`ReadinessProbeRunner` 对 HTTP 调用方有共享 2s 等待和固定线程池。它不能保证底层不可中断 I/O 被杀死，报告未把“调用方有界”误写成“线程立即释放”。

## 八、建议的整体验证矩阵（仅方案，未执行）

- **拓扑**：standalone 与原生 Cluster 各跑同一组契约；Cluster 覆盖键分布不同槽、master 故障、MOVED/ASK、单节点慢和全 Redis DOWN。
- **实例/线程**：至少两个应用实例共享 DB/Redis；对配置写、附件上传/收口、草案合并、观测 claim/release、runner 判死/ticker 用 barrier 制造确定性交错。单 JVM 并发不能替代跨 JVM 验证。
- **删除 30s 专项**：分别测量枚举阶段、附件 Redis 命令、文件删除、其它 run/session 键阶段，记录 N、RTT 和错误点；验收应包含总预算、进度可重试、无 stale 索引，而不是只看一次健康环境耗时。
- **作业容量**：覆盖运行 8 + 排队 200 的允许容量，观察 ticker 单拍时长、心跳最老年龄、watchdog 拍时长和误判数；同时压 Redis 故障而非只压正常 RTT。
- **身份**：两个租户/用户复用相同 sessionId/runId 形状做隔离；所有后台线程断言不沿用上一提交者身份。
- **观测**：每项修复至少增加命令数、单拍耗时、降级次数或失败结局的可观测字段；不能用“接口最终 200”替代共享状态与副作用验证。
