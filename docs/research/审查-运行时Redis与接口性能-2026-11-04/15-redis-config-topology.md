# 15 — Redis/Lettuce 配置、Cluster 拓扑与基础设施审查

## 1. 审查结论摘要

- 审查基线为 `52b09364`，只读检查开始时 HEAD 与该基线一致，工作区无变更。本报告只覆盖 **Redis/Lettuce 配置、拓扑、键与脚本基础设施**，并把这些基础设施映射到会话销毁、恢复扫描、缓存与 Pub/Sub 调用链；请求上下文、全部业务轮询和其它接口的完整结论应由对应专项报告承接。
- **最需要优先处理的是 `DELETE /api/ai/session/{sessionId}`：同步 HTTP 线程内存在两次无结果上限的逐 master 全键空间 SCAN，以及按 run、归档、staging、附件逐对象展开的同步命令；没有整次删除的墙钟预算。** `spring.data.redis.timeout=2s` 是单次命令/连接口径，不是接口总超时。代码能够证明“延迟可以叠加且没有接口级上界”，但本次禁止压测，**不把“现网每次必然超过 30 秒”写成事实**。
- Cluster 适配不是简单缺失：run 级 13 键、记忆、transcript、事件帧的多键脚本均使用一致的 hash tag；会话销毁有逐 master SCAN；pending 与全局 due ZSET 明知不同槽而采用单键脚本加补偿。**未发现生产代码调用 Redis 全库 `KEYS`，未发现 `BLPOP/BRPOP/XREAD BLOCK` 等阻塞式 Redis API，也未发现跨槽 `MGET`。** Lua 源码中的 `KEYS[1]` 是脚本参数数组，不是 Redis 的 `KEYS` 全库命令。
- 配置面仍有重要缺口：Cluster 只显式配置了种子、重定向与 2 秒超时，**未显式配置 Lettuce 周期/自适应 topology refresh**；配置缺失是代码事实，故障时的具体 stale-topology 表现仍须在真实依赖版本上验证。没有自定义连接工厂、序列化器或连接池配置；主服务统一使用 Boot 自动装配的 `StringRedisTemplate`。
- 其它显著风险为：`ai:runs` 的 `SMEMBERS + 逐成员 EXISTS` 在创建/恢复路径随索引规模增长，JVM 内 CAS 不能抑制多实例重复工作；`RedisAvailability` 健康时每次 PING 都在 `synchronized` 内执行，形成实例级串行点；同会话并发 DELETE 没有 HTTP 执行单飞；事件帧新增的 `ai:xtrim`/`ai:xack` 未进入 `deleteRun` 清单。

### 严重度口径

- **高**：可造成接口超时、明显资源放大或集群故障时大面积退化。
- **中**：需要特定规模/配置/故障触发，或影响局限于单一数据面。
- **低**：主要是生命周期、可观测性或硬化缺口，有既有 TTL/补偿兜底。
- **确定性**分为“代码证实”和“条件性影响/待运行验证”；后者不表示已复现。

---

## 2. 审查范围与实际阅读文件

### 2.1 配置、依赖与连接基础设施

全文阅读：

- `backend/pom.xml`
- `backend/src/main/resources/application.yml`
- `backend/src/main/resources/application-cluster.yml`
- `backend/src/main/resources/application-h2tcp.yml`
- `backend/src/main/resources/application-postgresql.yml`
- `backend/src/main/java/com/example/configmgr/config/RedisClusterTopologyGuard.java`
- `backend/src/main/java/com/example/configmgr/ai/config/RedisAvailability.java`
- `backend/src/main/java/com/example/configmgr/health/RedisReadinessProbe.java`
- `backend/src/main/java/com/example/configmgr/ai/observe/RedisDegradation.java`

另对 `backend/src/main/resources` 做了 properties/yml/yaml/xml/lua 文件枚举，对主代码做了 `RedisConnectionFactory`、`LettuceClientConfiguration`、`ClusterClientOptions`、连接池、序列化器、过期事件监听等全局检索。没有发现独立的 `.lua` 资源或自定义 Lettuce/RedisTemplate Bean。

### 2.2 会话销毁与键生命周期

全文阅读：

- `backend/src/main/java/com/example/configmgr/ai/web/AiSessionPurgeController.java`
- `backend/src/main/java/com/example/configmgr/ai/session/AiSessionPurgeService.java`
- `backend/src/main/java/com/example/configmgr/ai/session/RedisClusterScanSupport.java`
- `backend/src/main/java/com/example/configmgr/ai/session/SessionPurgePendingStore.java`
- `backend/src/main/java/com/example/configmgr/ai/session/SessionPurgeGuard.java`
- `backend/src/main/java/com/example/configmgr/ai/session/SessionPurgeDatabase.java`
- `backend/src/main/java/com/example/configmgr/ai/session/SessionPurgeRetryRunner.java`
- `backend/src/main/java/com/example/configmgr/ai/session/AiSessionService.java`
- `backend/src/main/java/com/example/configmgr/ai/run/SessionKeyRegistry.java`
- `backend/src/main/java/com/example/configmgr/ai/tenant/TenantKeyScope.java`
- `backend/src/main/java/com/example/configmgr/ai/tenant/RunKeyScope.java`
- `backend/src/main/java/com/example/configmgr/ai/memory/SessionKeyCodec.java`

`RunStore.java` 共 5211 行，采用定点阅读而非声称逐行全文阅读：重点读了键定义/hash tag（约 47–320、850–955）、会话索引（1820–2254）、`deleteRun`（2760–2935）、全局索引与清理（3120–3288）、TTL/上下文键（4930–5211）以及全部脚本/扫描/多键命令的检索结果。

### 2.3 Redis 数据面、脚本与消息基础设施

全文阅读：

- `backend/src/main/java/com/example/configmgr/ai/memory/RedisChatMemoryRepository.java`
- `backend/src/main/java/com/example/configmgr/ai/history/ConversationTranscriptStore.java`
- `backend/src/main/java/com/example/configmgr/ai/history/ConversationTranscriptConfig.java`
- `backend/src/main/java/com/example/configmgr/ai/form/RedisFormAttachmentStore.java`
- `backend/src/main/java/com/example/configmgr/ai/run/RedisFrameStore.java`
- `backend/src/main/java/com/example/configmgr/ai/run/RedisFrameWakeBus.java`
- `backend/src/main/java/com/example/configmgr/task/service/RedisTaskEventBus.java`
- `backend/src/main/java/com/example/configmgr/task/config/TaskEventDispatchConfig.java`
- `backend/src/main/java/com/example/configmgr/ai/run/CancellationRegistry.java`
- `backend/src/main/java/com/example/configmgr/ai/run/CreateTaskDraftStore.java`
- `backend/src/main/java/com/example/configmgr/ai/run/ClientRequestRegistry.java`
- `backend/src/main/java/com/example/configmgr/ai/observe/SettlementObservationStore.java`
- `backend/src/main/java/com/example/configmgr/ai/kb/RedisKbCacheStore.java`
- `backend/src/main/java/com/example/configmgr/ai/kb/KbConfig.java`
- `backend/src/main/java/com/example/configmgr/ai/kb/CachedKbSource.java`
- `backend/src/main/java/com/example/configmgr/ai/config/AiConfigVersionPublisher.java`
- `backend/src/main/java/com/example/configmgr/ai/config/AiModelCatalog.java`
- `backend/src/main/java/com/example/configmgr/job/service/JobCancellationRegistry.java`

定点阅读/检索：

- `backend/src/main/java/com/example/configmgr/ai/run/LeaseService.java`：脚本、hash tag 校验、抢租/续租/释放。
- `backend/src/main/java/com/example/configmgr/ai/session/SessionGate.java`：单键锁脚本和读取路径。
- `backend/src/main/java/com/example/configmgr/ai/run/ResumeService.java`：恢复发现与取消收尾接线。
- `backend/src/main/java/com/example/configmgr/ai/run/StartupResumeRunner.java`：启动/运行期扫描触发面。
- `backend/src/main/java/com/example/configmgr/ai/config/AiConfigService.java`：1 秒版本检查、Redis 失败后的 DB 回退。
- `backend/src/main/java/com/example/configmgr/ai/config/AiConfigWriter.java`：DB 事务与提交后 Redis 版本发布。
- `backend/src/main/java/com/example/configmgr/ai/config/AiProperties.java`：TTL、容量、索引清理等绑定与启动校验。
- `backend/src/main/java/com/example/configmgr/job/service/JobHeartbeatRegistry.java`：作业心跳与启动 SCAN。
- `backend/src/main/java/com/example/configmgr/ai/kb/KbCache.java`、`KbService.java`：无 TTL 缓存键、刷新/版本轮询。

### 2.4 实验模块与运维配置的边界

- 阅读了 `spike/sp01c/src/main/resources/application.yml`、`spike/sp02/src/main/resources/application.yml`，并检索 spike 的 Redis 依赖和使用点。它们是独立实验应用：sp01c 仅 standalone + 3 秒 timeout，sp02 未显式 Redis timeout；未把这些实验模块的行为混入 backend 主服务结论。若它们也要作为生产服务交付，需另做完整审查，不能直接继承 backend 的 Cluster 结论。
- 只读检索了 CI/脚本中的 Redis 5.0.14/7.2.16 与 `noeviction` 配置，**未运行任何 CI、脚本、服务或测试**。这些文件证明仓库存在相应验证/实验配置，不证明生产 Redis 的实际服务端配置。

---

## 3. 配置与自动装配事实

### 3.1 依赖、模板与序列化

- `backend/pom.xml:7-11` 使用 Spring Boot parent `3.5.14`；`backend/pom.xml:94-98` 引入 `spring-boot-starter-data-redis`。未显式引入 Jedis、Redisson 或 `commons-pool2`，也未单独钉 Lettuce 版本。本次未执行依赖解析，具体运行版本仍应以交付物依赖清单为准。
- 所有主服务存储/总线注入 `StringRedisTemplate`。业务对象先经 Jackson `ObjectMapper` 编成 JSON 字符串，例如记忆 `RedisChatMemoryRepository.java:464-469`、附件 `RedisFormAttachmentStore.java:158-160`、transcript `ConversationTranscriptStore.java:216-225`；Stream 原始帧在 `RedisFrameStore.java:1187-1196` 使用 byte[] 命令参数。
- 未发现自定义 `RedisSerializer` 配置。`RedisFormAttachmentStore.java:455-465` 在 pipeline 中显式用 `StringRedisSerializer` 编 key。现有序列化路径统一，未见 JDK 原生序列化或 Jackson 多态默认类型所带来的跨实例兼容负担。
- **未配置连接池不自动等于缺陷。** 当前没有 Redis 阻塞命令，普通 Lettuce 同步 API 可复用线程安全连接；加池不会减少 SCAN 的键空间工作量，也不会给接口增加总 deadline。只有在实测证明连接获取/共享连接成为瓶颈，或未来引入阻塞命令/长事务时，才应增加 `commons-pool2` 并配置有界池及借出超时。

### 3.2 standalone 与 Cluster 的共同配置

- 默认：`application.yml:74-84` 为 host/port、`timeout: 2s`、`connect-timeout: 2s`、Lettuce `shutdown-timeout: 200ms`。
- Cluster：`application-cluster.yml:20-36` 覆盖为 `cluster.nodes=${AI_REDIS_CLUSTER_NODES}`、`max-redirects: 3`，并保持同样 2 秒连接/命令超时与 200ms 关闭预算。
- `RedisClusterTopologyGuard.java:29-61` 只在 `cluster` profile 装配，校验节点非空、占位符已解析、条目为 host:port。这是有效的启动硬阻断；它不检查真实拓扑、端口可达性或集群槽覆盖，因此不是 readiness 的替代品。
- 两种拓扑使用同一套存储类与键名；只有 SCAN 按连接类型分支。键名不随 profile 分叉，避免数据面分裂，方向正确。
- `server.tomcat.connection-timeout: 120000`（`application.yml:5-6`）不是会话销毁的业务处理总超时，不能把它当作 30 秒或 120 秒的删除 deadline。

### 3.3 未显式配置项

Cluster 配置没有 Lettuce `ClusterTopologyRefreshOptions`、周期刷新、自适应刷新触发器、动态刷新源、socket keepalive、连接池参数，也没有独立的全局请求预算。尤其是：

- `max-redirects=3` 只限制一次命令跟随 MOVED/ASK 的跳数；它不能等价为周期拓扑刷新或所有故障场景的自适应刷新。
- 2 秒 timeout 对每一条同步命令分别生效。删除服务有多个逐对象 catch-continue 循环，所以不存在“一次 DELETE 最多 2 秒”的保证。
- 未看到对 `spring.data.redis.timeout/connect-timeout` 非正值的应用级启动校验。是否允许 0、0 的具体框架语义需按实际 Boot/Lettuce 版本确认；建议部署校验明确拒绝非正值，而不是依赖框架默认解释。

---

## 4. 删除对话的完整入口 → 服务 → Redis/DB/线程 → 返回链

### 4.1 HTTP 与身份边界

1. `DELETE /api/ai/session/{sessionId}` 进入 `AiSessionPurgeController.purge`（`AiSessionPurgeController.java:71-72`）。
2. Controller 先校验 sessionId，再调用 `RedisAvailability.isAvailable()`（`:73-79`）。PING 失败/缓存不可用时返回 503 `AI_REDIS_UNAVAILABLE`；PING 成功不代表后续所有 master、所有槽和所有命令都可用。
3. 身份必须已由过滤器放进请求上下文；Controller 对无上下文 fail-closed（`:80-86`），随后在同一线程调用 `purgeService.purge(sessionId, InstanceId.get())`（`:87-90`）。这里没有异步化、没有请求级 deadline，也没有并发删除执行锁。
4. `AiSessionPurgeService.purge` 固定身份并用 `RequestContextUtils.useContext` 包住全部下游键拼接（`AiSessionPurgeService.java:324-347`），所以本次同步调用链内身份传播一致。

### 4.2 durable pending、枚举、守卫与 DB

`purgeInternal` 的顺序见 `AiSessionPurgeService.java:350-418`：

1. **pending 登记**：`SessionPurgePendingStore.register`（`AiSessionPurgeService.java:354-367`）。实际先对全局 `ai:purge:retry:due` 做 ZADD，再对身份化 pending 键执行单键 Lua，最后再次 ZADD 对齐（`SessionPurgePendingStore.java:279-345`）。pending 无 TTL，是 durable 事实。
2. **枚举**：`enumerate`（`AiSessionPurgeService.java:566-685`）依次读取：
   - 会话门与 `sessionCurrent`、会话→run SET（`:566-592`）；
   - 每个 run 的快照和 pending（`:594-629`）；
   - 记忆正式键、staging SCAN（`:631-646`）；
   - transcript 两个存在性读及可选 LLEN（`:647-658`）；
   - context/ledger/archive/safety/events，archive SCAN + turnNo 推导（`:660-665`、`:731-795`）；
   - session 记录、草案、附件索引、DB 反馈 COUNT（`:667-679`）。
   枚举失败在数据删除前中止。
3. **写回守卫**：`SessionPurgeGuard.arm` 用带 TTL 的 SET 写 guard（`AiSessionPurgeService.java:389-400`；`SessionPurgeGuard.java:110-114`）。
4. **DB**：`SessionPurgeDatabase.delete` 在 `REQUIRES_NEW` 本地事务中按 sessionId + tenantId + userId 删除 `ai_feedback`（`SessionPurgeDatabase.java:57-65`、`:98-113`）。它不是 Redis/DB 分布式事务。

### 4.3 取消、等待与 Redis 删除主体

`executeDeletion` 位于 `AiSessionPurgeService.java:461-540`：

1. 对活跃 run 逐个读取消状态、写取消键，必要时调用 `ResumeService.cancelConfirmed` 做收尾（`:843-902`）。
2. `awaitTerminal` 在 Tomcat 请求线程上以 50ms 间隔、默认总计 1.5 秒轮询快照（`:139-151`、`:904-948`）；每个活跃 run 都可能重复读 Redis。
3. 再逐 run 重读状态（`:958-980`），然后依次删除：
   - 记忆：三键同槽 `MEMORY_CLEAR` Lua；staging 逐键 DEL，成功项再逐键 EXISTS 复核（`:995-1041`、`:1118-1177`；`RedisChatMemoryRepository.java:905-910`）。
   - transcript：data/bytes/ready/truncated 四键逐个 TYPE + DEL（`AiSessionPurgeService.java:1289-1307`）。
   - 每个终态 run：`RunStore.deleteRun` 先读快照校验身份，再对同 tag 的 13 键做一次 DEL，随后 best-effort 清全局/会话索引（`:1179-1234`；`RunStore.java:2843-2934`）；另逐个删 beat/activity/cancel/observed 四键（`AiSessionPurgeService.java:1236-1268`）。
   - context、ledger、全部 archive、safety、events：逐键 TYPE + DEL（`:1309-1363`）。
   - session 记录、create_task 草案、client-request HASH（`:1365-1401`）。
   - 每个附件：文件系统删除后，再读/删登记、墓碑与两个索引成员（`:1439-1494`；`RedisFormAttachmentStore.java:564-594`）。因此总耗时还包含同步文件 I/O，不只是 Redis。
4. 仅当主体无错误且无 deferred 时才删 sessionCurrent/sessionRuns/sessionActive 三把发现键（`AiSessionPurgeService.java:488-494`、`:1404-1437`）。
5. 成功后 guard 进入保留期，pending 排到 60 秒后的记忆复核；失败则记录 pending 退避（`:495-529`）。`complete=true` 不代表 HTTP 返回时已做完 60 秒后的复核，也不表示 guard/代次 tombstone 立即消失。
6. Controller 将 `PurgeResult.toBody()` 转为 200 裸对象并附身份快照（`AiSessionPurgeController.java:95-103`）；枚举/守卫/DB 中止也按既有契约返回 200 + `aborted/phase`，不是都由 503 表达。

### 4.4 后台线程与多实例

- 首次 DELETE 全程占用请求线程；没有独立删除执行器。
- 自动重试由每实例一个 `ai-session-purge-retry` 单线程调度器每 30 秒触发（`SessionPurgeRetryRunner.java:154-175`），但用全局 `ai:purge:retry:lock` 做跨 JVM 单飞，每轮最多 10 个候选（`:79-89`、`:198-240`）。身份按 pending 记录恢复（`:272-275`）。
- 自动 runner 的单飞**不覆盖 HTTP DELETE 主体**。并发 HTTP 请求仍可同时执行相同扫描与删除。

---

## 5. 逐项问题、证据与修复建议

### RC15-01 — 高 — 删除接口的两次全键空间 SCAN 无结果/工作量上界

**确定性：代码证实；超过 30 秒属于条件性结果，本次未实测。**

**证据**

- `AiSessionPurgeService.java:778-795` 对 archive 调 `RedisClusterScanSupport.scan(... count(256), 0)`；`:817-828` 对 staging 再以 `maxKeys=0` 调一次。
- `RedisClusterScanSupport.java:51-53` 明确 `maxKeys<=0` 不截断；`:83-95` standalone 扫完整游标；`:98-135` Cluster 顺序遍历所有 master；`:138-147` 持续 `hasNext/next` 直到游标结束。
- `COUNT 256` 只是每批提示，不是全扫描的键数或时间上限。返回前还把全部匹配键物化为 `LinkedHashSet/List`。

**触发条件与影响**

- 键空间很大、某会话匹配键很多、某 master 响应慢或 SCAN 多页超时时触发。MATCH 在服务端筛选，不会避免检查无关键。
- standalone 也需两次完整 SCAN；Cluster 额外按 master 数放大。staging 虽有会话 hash tag，当前 helper 并未据此只扫所属 master。
- 多线程/多实例删除不同会话会并发重复扫同一键空间，相互放大 Redis CPU、网络与 Tomcat 占用。
- 任一 master 失败时销毁枚举中止（`RedisClusterScanSupport.java:123-126`），完整性姿态正确，但不能降低已经消耗的等待时间。

**根因**

把“找一个会话的 archive/staging 键”实现成“遍历整个 Redis keyspace”，并把该重操作放在同步删除请求中；没有索引、游标续跑、扫描页预算或总 deadline。

**最佳修复**

1. 为 archive 与 staging 建立**身份化、会话级显式索引**，写入时同槽登记、正常清理时摘成员，删除时只读本会话索引；stale 成员按目标键是否存在过滤。索引也应带 TTL，并有写失败补偿，不能重新制造无 TTL 孤儿。
2. 将存量未索引键的 SCAN 变成一次性迁移或低频后台对账，不再由每次 HTTP DELETE 全扫。
3. 删除接口以现有 pending/due 为持久化基础，采用“短时同步快路径 + 到预算后 202/后台续跑”或全异步 202；避免长期占用请求线程。

**备选取舍**

- 给所有 session 级键加统一 hash tag、再只扫所属 master：改动少于完整索引但仍需扫描该 master 全键空间，而且改 tag 需要双读/迁移，不能原地换键名。
- 仅提高 `COUNT`、增大 timeout 或并行扫所有 master：可能缩短某次延迟，却增加 Redis 峰值压力，仍无工作量上界，不是根治。

**涉及修改点/影响面**

`RunStore` archive 写入与键登记、`RedisChatMemoryRepository` staging 生命周期、`AiSessionPurgeService` 枚举/删除、`RedisClusterScanSupport` 调用面、pending/status API、存量数据迁移与前端对 202/`complete=false` 的处理。

---

### RC15-02 — 高 — 删除按对象串行扇出，单命令超时可逐键累加

**确定性：代码证实；具体时间取决于对象数、RTT 与故障分布。**

**证据**

- `AiSessionPurgeService.java:154-157` 允许从 `turnNo` 推导至多 `0..2000` 共 2001 个 archive 键；`:745-760` 生成全部候选，`:1315-1317` 对每个 archive 调 `deleteKeyIfType`。
- 每个实际/推导键先做 TYPE，再做 DEL（`:1334-1363`）。即使很多键不存在，也仍各付一次 TYPE RTT。
- staging 对每个目标 DEL，再对每个“已删”目标 EXISTS（`:1150-1175`）；每个 run 另有 13 键 DEL + 四个单键观测 DEL + 索引维护（`:1225-1228`、`:1253-1268`；`RunStore.java:2906-2928`）。
- 附件逐件做文件与 Redis 清理（`AiSessionPurgeService.java:1439-1494`）。
- 多处 catch 只把错误加入列表后继续下一键，例如 `:1158-1174`、`:1260-1268`、`:1338-1361`。若故障命令各等待 2 秒，等待时间会重复发生。
- 配置只有单命令 2 秒（`application.yml:77-84`；`application-cluster.yml:32-36`），删除主流程没有总 deadline。

**量化说明（不是实测）**

仅 `turnNo=2000` 的推导面就产生 2001 次 TYPE；若所有键存在则再加 2001 次 DEL。只要平均每次同步 RTT 约 15ms，单这一段就约 30 秒；故障时每键还可能接近 2 秒。该算式用于证明 30 秒并非理论不可能，不代表仓库已有此规模会话或当前部署 RTT。

**单例/Cluster、多实例、多线程影响**

- standalone 同样承担串行 RTT；Cluster 还跨多个槽/节点，逐键错误可能只在部分槽出现。
- 多实例并发删除会线性放大命令数；每个请求长期占 Tomcat worker，线程堆积会继续降低其它接口容量。
- 逐键 catch-continue 使“部分节点坏”比快速失败更耗时。

**根因**

以对象数为单位组织同步命令，且把完整性复核也做成另一轮逐对象命令；没有按槽 pipeline、分页、总预算或设施级熔断。

**最佳修复**

- 先落实 RC15-01 的精确候选索引；对候选按槽分组，以有界 pipeline 做 TYPE/DEL/EXISTS，限制每批数量和整批 deadline。
- 对连接/超时类设施故障停止后续无意义尝试并保留 pending；只对 WRONGTYPE、单键业务冲突等局部错误逐键继续。
- 优先使用现有 `complete=false + pending` 或新增 202，让超过短预算的工作交给后台，不在 HTTP 中无限收尾。

**备选取舍**

- 多键 DEL 可减少 RTT，但只能对明确同槽的键执行；archive、附件、观测键不能盲目合成一个跨槽 DEL。run 级 13 键已经通过同 tag 做到一次 DEL，应保持。
- 单纯把所有异常改成立即抛出可缩短失败请求，但会失去已收集的部分结果，也不替代正常大对象场景的批量化。

**涉及修改点/影响面**

`AiSessionPurgeService` 的 memory/run/context/session/attachment 删除循环、`RunStore.deleteRun` 索引维护、`RedisFormAttachmentStore` 回收接口、错误语义与 pending 重试。

---

### RC15-03 — 高 — 同会话并发 DELETE 没有执行单飞，重复扫描与删除会跨实例放大

**确定性：代码证实。**

**证据**

- Controller 每次都直接调用 `purgeService.purge`（`AiSessionPurgeController.java:87-90`）。
- `purgeInternal` 即使复用已有 pending，也继续枚举、装 guard、删 DB 并进入主体（`AiSessionPurgeService.java:354-418`）。
- `SessionPurgePendingStore.register` 对已有 pending 做的是复用 purgeId、重置手工推进时间，而不是返回“正在执行/请查询”（`SessionPurgePendingStore.java:322-340`）。
- 全局租约只出现在自动 runner `SessionPurgeRetryRunner.java:203-235`，不在 HTTP 主流程。

**触发条件与影响**

用户重复点击、代理重试、两个浏览器线程、或负载均衡把相同 DELETE 分到不同实例时，多个执行体重复全集群 SCAN、DB DELETE、文件删除和 Redis 命令。幂等/CAS 降低了部分正确性风险，但不消除性能放大和文件/网络竞态成本。

**根因**

pending 是 durable 义务与 owner 标识，不是互斥执行租约；HTTP 与后台 runner 的协调面不同。

**最佳修复**

增加身份化 purge 执行租约/状态机：同一身份同一 sessionId 同时只允许一个主体执行；后到请求返回当前 purgeId 与进行中状态（推荐 202），或只做有界等待后返回。执行租约需 owner token、TTL、续租与失锁停写，不能只用进程内 Map。

**备选取舍**

- 复用 pending 而不新增键：已有 pending 且正在推进时直接返回状态，改动较小；但需能区分“正在执行”和“失败待重试”，否则手工重试入口会被误挡。
- 只做 JVM 内 single-flight：可减少本实例重复，却不能解决多实例，不满足目标形态。

**涉及修改点/影响面**

Controller 响应语义、pending 状态字段、`AiSessionPurgeService` 入口、runner 接管与前端重试/状态查询。

---

### RC15-04 — 中 — `RedisAvailability` 把健康探活网络 I/O 放在实例级互斥锁内

**确定性：代码证实。**

**证据**

- 健康时 `POSITIVE_TTL_MS=0`（`RedisAvailability.java:38-47`），所以健康请求不会因正缓存跳过锁。
- `isAvailable()` 在 `synchronized(this)` 内执行 `redis.execute(...ping())`（`:71-100`）。PING、锁等待和异常记录都共用同一实例 monitor。
- 删除端点和 AI/readiness 都复用此探针（`AiSessionPurgeController.java:77-79`；`RedisReadinessProbe.java:54-58`）。

**触发条件与影响**

多线程同时进入 AI/DELETE/readiness 时，健康态 PING 也逐个串行；Redis 慢时，一个线程可在锁内等到命令超时，其它线程排在 monitor 外。多实例只把锁分散到各 JVM，不消除单实例热点。

**根因**

为避免重复探活使用了覆盖网络 I/O 的粗粒度 synchronized；正缓存为 0 使该锁成为每次调用的必经路径。

**最佳修复**

不要在全局 monitor 内做阻塞 PING。健康路径可无锁执行，或使用原子 in-flight + 很短的正缓存/后台复核；负缓存继续限制故障重探。删除性能不能只靠探活优化，仍需 RC15-01/02。

**备选取舍**

- 恢复较长正缓存可减少 PING，但会重新扩大“刚探活后故障”的漏检窗口；建议以明确的小窗口和请求级失败处理换取容量，而非恢复长缓存。
- 完全删除预检可减少一次 RTT，但失去既有 503 快速失败语义，不应作为默认首选。

**涉及修改点/影响面**

`RedisAvailability` 并发状态机、AI/DELETE/readiness、任务总线的已知 DOWN 复用及健康指标口径。

---

### RC15-05 — 中 — Cluster topology refresh 未显式配置；PING 不能覆盖逐 master SCAN 所需可用性

**确定性：配置与调用关系代码证实；具体 failover/resharding 故障表现为条件性影响，须按实际 Lettuce 版本验证。**

**证据**

- `application-cluster.yml:28-36` 只有 nodes/max-redirects/timeout/connect-timeout/shutdown-timeout；没有周期或自适应刷新配置。
- 全主代码没有 `ClusterTopologyRefreshOptions`/`ClusterClientOptions` 自定义。
- `RedisAvailability` 只执行 PING（`RedisAvailability.java:86-98`）；`RedisClusterScanSupport` 则需要读取拓扑并逐一访问每个 master（`:98-135`）。因此一次预检成功不能证明这次全集群扫描完整可执行。
- `max-redirects=3` 不能代替后台拓扑维护。

**触发条件与影响**

主从切换、节点替换、扩缩容、resharding、种子节点部分失效时，若实际客户端默认未覆盖对应刷新触发器，可能出现拓扑更新滞后、重定向增加或 SCAN 到失效节点。此处不声称当前 Lettuce 一定不刷新；MOVED/ASK 等机制仍可能更新路由。

**根因**

把 Cluster 可用性建立为“种子 + 重定向 + 单探针”，没有把拓扑维护策略纳入可审查配置，也没有为全 master 操作提供分节点观测。

**最佳修复**

- 在 `cluster` profile 专用 Lettuce customizer 中显式配置 `ClusterClientOptions`：周期刷新（建议以 60 秒为待验证初值）、动态刷新源和适配当前版本的自适应触发器；保留 `max-redirects=3` 与 2 秒命令/连接超时。
- 记录拓扑刷新成功/失败、最后刷新时刻、节点数与 SCAN 每节点失败，不在日志输出凭据。
- 全 master SCAN 的失败应带节点与阶段指标；readiness 仍不应因一个非硬依赖 Redis 把整站摘流，但删除操作必须如实中止/延后。

**备选取舍**

- 只缩短周期会增加 CLUSTER NODES 流量；只开自适应触发器可能漏掉未触发事件；两者组合更稳妥。
- 对所有 AI 请求做逐 master PING 成本高且仍有时效窗口，不建议；按实际操作暴露分节点错误更有效。

**涉及修改点/影响面**

新增 cluster 专用 Lettuce 配置类、`application-cluster.yml` 说明、健康/观测面；需核对交付物实际 Lettuce API 与既有 client options 合并，避免覆盖其它配置。

---

### RC15-06 — 高 — `ai:runs` 全局索引清理/恢复是 O(N) 串行命令，JVM 内 CAS 不能抑制多实例放大

**确定性：代码证实。**

**证据**

- `RunStore.pruneRunIndex` 先 SMEMBERS 全量成员，再逐成员 EXISTS，最后批量 SREM（`RunStore.java:3218-3262`）。
- 它在创建路径被 `maybePruneRunIndex` 调用（`:1217-1221`、`:3265-3288`），默认间隔 10 分钟（`application.yml:369-372`；`AiProperties.java:1286-1294`）。
- 节流是实例字段 `AtomicLong indexPrunedAtMs`（`RunStore.java:342-348`、`:3278-3287`），只能合并同 JVM 线程；每个实例都有自己的 10 分钟节拍。代码注释中“多实例并发创建不会每个实例都扫”的表述与实际进程边界不一致。
- 启动/运行恢复 `allRunRecords` 也做 SMEMBERS + 逐成员 EXISTS（`:3163-3189`），由 `ResumeService.listRunsForRecovery` 使用（`ResumeService.java:471-484`），运行期 sweep 默认 120 秒（`application.yml:263-271`）。

**触发条件与影响**

历史 run 索引成员很多、实例多、创建频繁或恢复扫描时，产生大 SMEMBERS 响应和 N 次同步 RTT。standalone 与 Cluster 都受影响；Cluster 的 EXISTS 还分散到不同槽。实例数增加会重复相同全局清理工作，而不是总量恒定。

**根因**

用无成员 TTL 的全局 SET 承担全部发现面，再在请求/恢复线程做全量对账；节流和单飞主要是进程内状态，缺少分布式工作租约与增量游标。

**最佳修复**

- 把清理从创建请求线程移到后台，使用共享清理租约。
- 短期改为 SSCAN 分页 + 按槽 pipeline EXISTS + 有界批量 SREM，持久化游标/进度，每拍有键数和时间预算。
- 长期可把可过期发现面建模为按到期时刻排序的 ZSET/分代索引，以到期范围取候选，避免每次遍历全部成员；需保留活跃续跑发现与身份归属。

**备选取舍**

- 仅增大 `index-prune-interval` 降低频率但延长 stale 成员滞留，启动/运行恢复的全量成本仍在。
- 只加 Redis 分布式锁而不分页，可消除多实例重复，却仍让单实例承担无界一轮工作。

**涉及修改点/影响面**

`RunStore` 索引格式与清理、`ResumeService`/启动恢复、`AiProperties` 配置、后台调度、存量 SET 迁移与观测指标。

---

### RC15-07 — 中 — 记忆 SPI 枚举仍有独立的无界全 SCAN，并在失败时返回部分结果

**确定性：代码证实；生产主链路低频是该类自述，实际调用频率未做运行验证。**

**证据**

- `RedisChatMemoryRepository.findConversationIds` 以 `chat:mem:<身份>:{*` 匹配，standalone 全游标读取，Cluster 逐 master 全游标读取（`RedisChatMemoryRepository.java:353-414`）。
- 没有结果数、扫描页数或时间上限；异常时返回“已扫描到”的 id 集（`:409-413`），不是完整全集。
- 它与销毁专用 `RedisClusterScanSupport` 是第二套类似实现，失败语义却不同：销毁 fail-closed，枚举返回部分结果。

**影响**

若被会话列表/诊断接口频繁调用，会与删除 SCAN 叠加；部分结果可能被调用方误当完整列表。多实例只是增加并发来源，Cluster 增加逐 master 成本。

**根因**

全局会话索引退役后，SPI 用 keyspace 代替目录，同时没有显式分页/不完整体契约。

**最佳修复与备选**

生产目录应使用有界、身份化的会话索引/DB 目录并分页；诊断 SCAN 至少增加游标续传、页/时间预算与 `complete=false` 标记。若 SPI 必须返回完整 List，则不应把部分结果伪装成正常完成，可抛明确不可用异常；这与现有“不抛错”的取舍不同，需要调用方契约一起改。

**涉及修改点/影响面**

`RedisChatMemoryRepository.findConversationIds`、全部调用方、会话列表 API 与诊断面；不影响正常 `findByConversationId` 的单键读。

---

### RC15-08 — 低 — `deleteRun` 清单未覆盖事件帧新增的 `ai:xtrim` 与短 TTL `ai:xack`

**确定性：代码证实。**

**证据**

- `RedisFrameStore` 新增 `ai:xtrim`（`RedisFrameStore.java:220-229`）和每帧幂等 `ai:xack`（`:217-235`），二者都与 run 同 tag。
- `ai:xack` TTL 为 120 秒（`:231-235`）；`ai:xtrim` 随 session TTL（`:377-391`）。
- `RunStore.deleteRun` 的 13 键 DEL 列表没有这两项（`RunStore.java:2906-2908`）；`SessionKeyRegistry.java:43-65` 的删除登记也未覆盖它们。

**触发条件与影响**

会话/run 销毁后，xack 最长再存留约 120 秒，xtrim 可存留到 session TTL。它们不含帧正文，且都有 TTL，因此不是永久泄漏；但“销毁即清”的键面不一致，高频大量 run 时会形成短期额外键数。

**根因**

新键写入与 run 回收清单分离，未进入统一删除登记。

**最佳修复**

将 xtrim 与当前 run 关联的 xack 纳入同槽回收设计。xtrim 可直接加入同槽 DEL；xack 每帧一个 token 键，不能靠猜 token 精确删除，宜在出帧生命周期结束主动清理、维护有界短索引，或接受短 TTL 但把该例外写进销毁语义与容量口径。不要为了清 xack 新增每次全库 SCAN。

**备选取舍**

保留 120 秒 xack 是最小改动且已有明确上界；若合规要求立即删除所有 run 关联键，则需额外索引/生命周期管理，成本更高。

**涉及修改点/影响面**

`RedisFrameStore`、`RunStore.deleteRun`、`SessionKeyRegistry`、容量统计和销毁结果语义。

---

### RC15-09 — 低 — 模型清单 TTL 的非正值会静默变成永久缓存，缺少配置守卫

**确定性：代码行为证实；非正值是否有意作为运维语义未知。**

**证据**

- 默认 TTL 为 10 分钟（`AiProperties.java:151-158`；`application.yml:149-150`）。
- `AiModelCatalog.writeCache` 对 `null/0/负值` 走不带 TTL 的 SET，仅正值带 TTL（`AiModelCatalog.java:108-119`）。
- `AiProperties` 对 session TTL 和 stream 参数有启动校验（`AiProperties.java:253-267`、`:398-413`），未看到 models-cache-ttl 的同等校验。

**影响**

配置笔误可让 `ai:cfg:models:<providerId>` 永久存留并长期提供旧模型清单。standalone/Cluster 语义相同，多实例共享后旧值影响所有实例。

**最佳修复与备选**

明确配置契约：若不允许永久缓存，启动时要求 `>0`；若需要“关闭缓存”，应跳过读写而不是永久 SET；若确实需要永久缓存，则用单独显式开关和文档表达，不让 0/负值隐式承担第三种语义。

**涉及修改点/影响面**

`AiProperties` 校验、`AiModelCatalog.writeCache`、配置文档与存量永久键清理。

---

### RC15-10 — 中 — transcript Lua 在计数修复时可在服务端全量读取并循环处理整个 LIST

**确定性：脚本行为代码证实；阻塞时长取决于条目数/字节数与 Redis 负载。**

**证据**

- `ConversationTranscriptStore.APPEND_SCRIPT` 是四键同槽 Lua（`ConversationTranscriptStore.java:94-159`）。
- 计数器缺失/不可信或算出负值时，脚本执行 `LRANGE key 0 -1` 并遍历全部元素（`:117-143`）；超字节预算时还按最多 200 条循环 LRANGE/LTRIM（`:144-152`）。
- 单会话上限为 5000 条、约 8MiB（`:65-69`），所以存在明确业务上界，但整个修复过程在一次 Lua 执行中占用对应 Redis master。

**影响**

升级后旧键、计数器先过期或计数漂移时，一次 append 可能把大量读取/裁剪放进 Redis 单线程脚本；同槽同 master 的其它命令会等待。Cluster 只把影响限制在所涉 master，不消除该 master 的阻塞；多实例写同一会话会争用同一脚本面。

**根因**

为了原子修复计数，把全量核算与在线 append 合并到同一脚本。

**最佳修复**

常规路径继续原子维护计数；异常计数修复改为有界分块、后台对账或显式重建流程，不在在线 Lua 中 `LRANGE 0 -1`。为脚本执行时长、修复次数和裁剪次数增加观测。

**备选取舍**

保留全量 Lua 实现简单且原子，适合小 LIST；现有 5000/8MiB 上界降低无限风险，但不能保证低延迟。分块修复会失去一次性原子视图，需要版本/代次防止与并发 append 交错。

**涉及修改点/影响面**

`ConversationTranscriptStore` 脚本、计数键迁移/修复、并发 append 语义与 Redis slowlog/script 观测。

---

### RC15-11 — 中 — Stream 字节治理的最大载荷观察值是实例本地的，多实例全局预算不严格

**确定性：代码与注释均证实该设计边界。**

**证据**

- 每实例 `RunState.observedPayloadBytes` 保存本进程观察值（`RedisFrameStore.java:504-524`）。
- 写侧据本进程观察值计算 MAXLEN 窗口（`:780-785`、`:834-837`），读侧也取本进程状态（`:1663-1667`）。
- `AiProperties.java:1264-1269` 明确说明跨实例不共享，全局上界退化为各实例观察之和。

**影响**

单实例可按本进程见过的最大帧收窄窗口；多实例交叉写同一 run 时，没见过大帧的实例可能继续使用更宽窗口，新实例/重启后的读侧也可能按 0 载荷估计。它不是删除 30 秒的直接根因，但会影响 Cluster 容量和回放深度的一致性。

**根因**

为了不为每帧增加共享读写，把容量输入保存在本地簿记。

**最佳修复与备选**

若必须严格全局预算，应把该 run 的容量代次/最大载荷放进同槽共享状态，并随原子落档脚本更新；代价是脚本复杂度和额外状态。若接受软预算，则保留现实现，但用静态保守帧上界计算最低安全窗口、按 Redis 实际内存指标告警，并把文档中的“上界”明确为实例/软口径。

**涉及修改点/影响面**

`RedisFrameStore` 原子落档脚本、RunState、读侧窗口、容量指标与多实例回放兼容。

---

### RC15-12 — 中（部署风险） — 生产 `noeviction` 与服务端容量配置无法由本仓主配置证明

**确定性：仓库覆盖范围证实；生产实际策略未知，不能写成“生产已配错”。**

**证据**

- `application.yml:356-372` 的注释把 `maxmemory-policy=noeviction` 作为“不丢 AI 帧”的部署前提。
- 主服务 application 配置无法设置 Redis 服务端 maxmemory 策略；检索到的实际 `noeviction` 启动参数位于 CI/集群脚本，而非可证明的生产编排。
- 代码未看到启动时逐 master 校验 maxmemory/maxmemory-policy。

**影响**

若生产某个 master 使用会淘汰 AI 键的策略，快照、Stream、计数器或会话索引可能消失，不同键的淘汰还会破坏同寿假设；Cluster 需所有 master 一致。若严格 noeviction，则内存触顶会使写入失败，必须配合容量监控，不是“更安全就无需容量治理”。

**最佳修复**

在真实部署清单中为所有 master 明确 maxmemory/noeviction、内存水位和 OOM 告警；发布验收读取脱敏的有效配置。若环境允许 CONFIG GET，可做启动/定期观测；若托管 Redis 禁用 CONFIG，不应为此影响启动，应由平台证据和监控承担。

**备选取舍**

启动硬校验能 fail-fast，但可能因托管权限或拓扑抖动阻碍部署；只告警更易部署，却要求运维闭环。不要把 CI 的 Redis 参数当生产证明。

**涉及修改点/影响面**

生产编排/平台参数、发布检查、Redis 指标与告警；应用代码仅需可选观测，不建议把整站 readiness 绑定到 CONFIG 权限。

---

### RC15-13 — 低 — 少数兼容/观测写路径仍是“数据命令后再 EXPIRE”，崩溃窗口可留下无 TTL 键

**确定性：代码证实存在非原子顺序；只在特定崩溃/中断窗口形成残留。**

**证据**

- `RedisFrameStore.appendExact` 的原始 callback 先 XADD 后 EXPIRE（`RedisFrameStore.java:1187-1196`）；普通异常有后续幂等读与续期补偿，但进程在两命令之间崩溃无法执行补偿。
- `RunStore.appendLedger` 是 LPUSH → LTRIM → touch/EXPIRE（`RunStore.java:5054-5065`）；`appendSafetyEvent` 同形（`:5096-5108`）。
- 其它许多关键写已使用 SET with TTL 或 Lua，例如 `SessionPurgeGuard.java:110-114`、`CreateTaskDraftStore.java:82-87`，说明并非全仓都有此问题。

**影响**

极端崩溃下可留下无 TTL 的 Stream/LIST，增加后续 SCAN 的键空间与容量占用。Cluster 与 standalone 都有该窗口，多实例不会自动清理别实例的孤儿。

**最佳修复与备选**

把仍需长期保留的单键“写 + 裁剪 + TTL”改为单键 Lua；对兼容写口至少增加低频有界对账。优先级低于删除 SCAN，因为窗口窄且部分键已有显式删除面。不要为了极少数孤儿引入高频全库扫描。

**涉及修改点/影响面**

`RedisFrameStore.appendExact`、`RunStore` 两个台账写口、对应失败/幂等语义与 TTL 验证。

---

## 6. 配置与代码的最佳组合方案

### 6.1 推荐落地顺序

1. **先限制接口爆炸半径**：给 DELETE 增加整次短预算与可恢复的 202/`complete=false` 返回；同会话执行单飞；设施级超时停止本轮而不是逐键继续等。
2. **再消除全 SCAN 与串行扇出**：archive/staging 会话索引、按槽有界 pipeline、后台存量对账；保留现有 pending/guard 作为持久化与防复活基础。
3. **补齐 Cluster 客户端策略**：显式 Lettuce topology refresh、重定向、超时和分节点观测；不通过单纯增大 timeout 掩盖扫描问题。
4. **治理全局索引**：将 `ai:runs` 对账移出创建热路径，分布式单飞 + 增量分页；之后再评估 ZSET/分代索引的结构性替换。
5. **最后做容量与生命周期硬化**：生产 noeviction/水位证据、transcript 脚本修复、xtrim/xack 回收、TTL 配置校验。

### 6.2 建议配置基线（均为待验证方案，不是本次已修改）

| 面 | 推荐组合 | 取舍 |
|---|---|---|
| standalone / Cluster 连接 | 保持 connect-timeout 与 command timeout 为明确正值；当前 2s 可先用 | 不调大来掩盖串行命令；过短也可能误杀正常慢命令 |
| Cluster 路由 | 种子覆盖全部主节点；max-redirects 保持有界；显式动态刷新源 | 多种子不替代周期/自适应刷新 |
| Cluster 刷新 | 周期刷新 + 当前 Lettuce 支持的自适应触发器；周期可从 60s 验证起步 | 更频繁刷新有少量拓扑命令成本 |
| 连接池 | 默认不因本次问题盲目加池；先观测连接/命令指标 | 加池不能减少 keyspace 工作，且会提高峰值连接数 |
| 删除 API | 短同步预算 + durable pending 后台续跑；推荐 202 表达进行中 | 需要前端处理异步状态；复用 200 complete=false 则协议改动较小 |
| Redis 服务端 | 每 master 明确 maxmemory/noeviction、容量水位和 OOM/慢脚本告警 | noeviction 防静默淘汰，但触顶会拒绝写入 |
| 过期事件 | 不启用 keyspace 事件作为正确性依赖 | 当前没有过期监听；显式索引/pending/TTL 已足够，额外事件增加复杂度 |

### 6.3 观测所需最小指标

后续实现至少应按实例和全局可聚合地记录：删除各阶段耗时、SCAN 页数/节点数/检视键数/匹配数、每类命令数、逐节点失败、pending 年龄与重试次数、同会话单飞拒绝数、`ai:runs` 成员数/每拍处理数、Lettuce 拓扑最后刷新时刻、Redis 慢日志与 Lua 执行异常。日志不得包含连接密码、API key 或附件正文。

---

## 7. 后续验证方案（只列方案，本次未执行）

### 7.1 删除接口与 SCAN

1. 分别在 standalone 与真实 3 主 3 从 Cluster 构造：空会话、多 run、多附件、多 staging、`turnNo=2000`、大量无关键；记录端到端 p50/p95/p99、每阶段耗时和命令数，验证不再随全局键数线性增长。
2. 在 SCAN 中途制造多页响应、单 master 暂停、MOVED/ASK、resharding 与主从切换；验证扫描要么完整，要么明确失败/延后，不能把部分结果当完整，也不能无限占请求线程。
3. 验证接口预算：在预检后故障、SCAN 故障、逐键 TYPE/DEL 故障、文件删除慢等场景，HTTP 都在承诺预算内返回可恢复结果，pending 保留，后台最终收敛。
4. 同身份同 sessionId 在同进程多线程和跨实例并发 DELETE；确认只有一个主体执行，后到者获得进行中/幂等结果，文件和 Redis 不重复产生不可控副作用。

### 7.2 Cluster 与 Lua

1. 对每族多键脚本打印/断言实际 key 的 CRC slot：run FENCED_SET/IDEM_PUSH/ACQUIRE/deleteRun、memory MERGE_CAS/MEMORY_CLEAR、transcript APPEND、frame APPEND；覆盖含 `%`、花括号、冒号、glob 字符的 id。
2. 节点替换、扩缩容、failover 后验证 topology refresh 的实际触发与恢复时间；核对交付物 Lettuce 版本，避免只凭配置名推断行为。
3. SCRIPT FLUSH/冷启动后验证 `DefaultRedisScript` 执行回退与结果类型；制造 transcript 计数器缺失/坏值，测量修复脚本耗时，确认不会长时间阻塞 master。
4. 对 pipeline 验证不同槽命令均成功、结果顺序正确、单批大小/总预算有效；不要把多键 MGET/EVAL 误引入跨槽路径。

### 7.3 索引、TTL 与容量

1. 构造大 `ai:runs`，多实例同时创建与恢复；验证分布式清理单飞、每拍键数/时间上限、游标续跑、stale 成员最终清除，创建请求不再承担全量对账。
2. 销毁后检查 xtrim/xack、memory ver/gen、guard、pending 等每类键的预期存留；区分“有意 tombstone”和“漏删”，验证 TTL 或显式清理都按契约发生。
3. 对 models-cache-ttl 的 0/负值、session-ttl、Redis timeout 做启动配置验证；对生产每个 master 核对脱敏 maxmemory 策略与容量告警。
4. 多实例交叉写同一 run 的大小帧，验证 Stream 字节软预算的实际边界、回放缺口信号与新实例读侧行为。

---

## 8. 未发现问题的关键路径与正向结论

1. **run 级同槽设计**：`RunStore.runScopedKey` 统一经 `RunKeyScope`（`RunStore.java:897-910`；`RunKeyScope.java:53-75`），13 键 DEL 使用同 tag（`RunStore.java:2901-2908`）；租约/epoch/fence 还有同 tag 入口校验（`LeaseService.java:528-565`）。未发现该链路的明显 CROSSSLOT。
2. **记忆与 transcript**：memory staging/final/ver/gen 共用会话 tag（`RedisChatMemoryRepository.java:177-204`、`:223-240`），transcript 四键同 tag（`ConversationTranscriptStore.java:350-381`、`:416-419`）。花括号转义集中在 `SessionKeyCodec.java:21-36`。
3. **pending 与全局 due ZSET**：代码明确承认二者不同槽，未强行做跨槽 Lua；完成/退役后的重读补回用于处理并发登记（`SessionPurgePendingStore.java:64-81`、`:711-774`）。这是合理的 Cluster 取舍。
4. **pipeline 而非跨槽 MGET**：附件续期与 run 状态批量读都逐条下单键命令（`RedisFormAttachmentStore.java:410-430`、`:447-465`；`RunStore.java:2124-2146`），未把不同槽键塞进一个多键命令。
5. **无生产全库 KEYS/阻塞 Redis API**：检索到的 SCAN 使用游标而不是 KEYS；未找到 BLPOP/BRPOP/BLMOVE/XREAD BLOCK 等调用。当前延迟主要来自同步调用数量与扫描范围，而不是 Redis 阻塞原语。
6. **Lua 加载方式一致**：脚本以静态 `DefaultRedisScript` 内嵌并交给 `StringRedisTemplate.execute`，无外部文件路径和手工连接编译；未发现自定义脚本加载器造成的多实例差异。脚本执行仍受同一命令 timeout 与 Redis 单线程约束。
7. **Pub/Sub 执行器有界**：`RedisFrameWakeBus.java:248-333` 与 `RedisTaskEventBus.java:403-478` 都为派发和订阅配置独立有界池，并有停机关闭；频道按 run/task，未订阅全局通配频道。唤醒丢失还有游标/快照兜底。
8. **过期事件不是依赖**：未发现 keyspace notification 配置或监听；会话/pending/KB 的回收依赖显式索引、台账与 TTL，不需要额外开启 `notify-keyspace-events`。
9. **配置版本与 KB 轮询有边界**：AI 配置读路径默认至多每秒每实例一次版本检查，Redis 失败回退 DB（`AiConfigService.java:154-169`、`:245-280`）；KB 刷新和版本轮询使用固定延迟调度（`KbService.java:178-185`），缓存刷新用 SET NX PX 单飞。未发现它们使用 KEYS/全库 SCAN。
10. **作业启动 SCAN 有单独的上限与 TTL 兜底**：`JobHeartbeatRegistry.java:588-696` 按 master SCAN、最多处理调用方给定的匹配键数，单节点失败不阻断其它节点，失败结果留给 TTL。它不是删除链的根因；但其“上限按匹配结果而非全部检视键”仍意味着大键空间下 SCAN 页数不是严格有界，后续硬化时可增加扫描页/时间预算。
11. **身份在关键后台恢复点显式传播**：purge runner 按记录恢复身份（`SessionPurgeRetryRunner.java:272-275`），wake 回调捕获订阅时身份（`RedisFrameWakeBus.java:147-156`），避免 Redis 监听线程落到 legacy 键。未发现这些键路径依赖无身份默认租户。

---

## 9. 本次验证边界

本次仅执行只读文件检索/阅读与只读 Git 状态、HEAD 核对；**未运行测试、构建、CI、服务、压力测试或任何 Redis 故障注入，未修改生产/测试代码，未做 Git 写操作。** 因此报告中的“超过 30 秒”“拓扑刷新恢复时间”“Lua 阻塞时长”等运行指标均未被本次实测确认；代码已证实的是相应无界工作量、串行等待与配置缺口。唯一写入物为本报告。
