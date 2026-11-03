# DELETE 对话在 Redis Cluster 下超过 30 秒：专项审查

- 基线：`52b093642402c9455b7cb9627fae6dbdd6544e54`（与工作树 `HEAD` 一致）。
- 审查方式：只读生产代码与生产配置；未读测试实现，未运行测试、构建、CI、服务或压测，未执行任何 git 写操作。
- 专项边界：`DELETE /api/ai/session/{sessionId}` 的请求线程、自动重试线程及其全部已接线清理下游。
- 证据口径：“代码证实”只表示控制流、命令形态、串并行关系或配置值可由代码直接推出；不表示已复现线上耗时。本文没有运行期测量，因此“某次请求确实因某阶段超过 30 秒”均列为待实测，不把代码风险写成事故事实。

## 一、结论先行

1. **代码证实：一次首次 DELETE 在删除任何业务数据之前，会依次执行两趟完整键空间遍历：记忆 staging 一趟、上下文 archive 一趟。Cluster 下每趟都逐 master、逐游标串行执行，`COUNT=256` 只是每页提示，`maxKeys=0` 不限制总结果数。** 成本取决于整个 master 的键空间，而不是该会话的键数。这是大键空间下最明确、最优先的耗时来源。
2. **代码证实：请求没有端到端 deadline，也没有每阶段 Redis 命令预算。** 默认每个 Redis 命令/游标推进可等到 2 秒；枚举、取消、删除、复核均为同步串行链。任意 16 次相继耗尽 2 秒的操作就已超过 30 秒；这只是预算算术，不是实测结论。
3. **代码证实：即使 SCAN 很快，删除主体仍有明显的 N 倍串行点操作。** 每个终态 run 常见路径会读取并反序列化整份快照约 3 次、读 3 个 HASH、执行同槽 13 键 DEL、清理索引及 4 个观测键；每个 archive 最多 `TYPE+DEL` 两次往返；每个附件还包含同步文件 I/O 和多次 Redis 操作。
4. **代码证实：pending 的 `purgeId` 是台账 CAS 所有权，不是会话级执行锁。** 并发 DELETE、DELETE 与 runner、不同实例上的 DELETE 可以同时重复执行同一套 SCAN/DB/文件/删除链。runner 的全局租约只约束 runner，不约束 HTTP。
5. **活跃 run 是另一条独立的慢链。** 写入取消旗标后，请求线程会同步进入 `ResumeService.cancelConfirmed`；必要时还会执行 `cancelParked`、终帧归档探测、会话门/租约、pending 收敛、快照和 transcript/记忆收尾。1.5 秒 `awaitTerminal` 只在这些调用之后开始，绝不是整个取消阶段的超时。
6. **不能因为慢就跳过 SCAN、提前报 `complete=true`，也不能只做“异步化”。** 当前 SCAN 承担 legacy/异常残留的发现义务；异步重试若仍执行同一全量 SCAN，只会把成本搬到后台并可能叠加。根治需要可枚举的会话级 archive/staging 索引或等价的持久化工单/游标设计，同时保留遗留数据对账和“未完成不得报 complete”的语义。

综合判断：**大键空间下首先测两趟 SCAN；多 run/多归档/多附件下测串行点操作；有活跃 run 时单独测同步取消收尾；故障或抖动下测 2 秒命令超时的累乘。** 哪一项在实际事故中占主导，必须由分阶段计时和 Cluster 指标确认。

## 二、审查范围与实际阅读文件

### 2.1 完整阅读：控制器与 `ai/session` 全部生产文件

- `backend/src/main/java/com/example/configmgr/ai/web/AiSessionController.java`
- `backend/src/main/java/com/example/configmgr/ai/web/AiSessionPurgeController.java`
- `backend/src/main/java/com/example/configmgr/ai/session/AiSessionService.java`
- `backend/src/main/java/com/example/configmgr/ai/session/AiSessionPurgeService.java`
- `backend/src/main/java/com/example/configmgr/ai/session/SessionPurgeGuard.java`
- `backend/src/main/java/com/example/configmgr/ai/session/SessionPurgePendingStore.java`
- `backend/src/main/java/com/example/configmgr/ai/session/SessionPurgeRetryRunner.java`
- `backend/src/main/java/com/example/configmgr/ai/session/SessionGate.java`
- `backend/src/main/java/com/example/configmgr/ai/session/RedisClusterScanSupport.java`
- `backend/src/main/java/com/example/configmgr/ai/session/SessionPurgeDatabase.java`

`AiSessionController` 是会话发号接口，不是 DELETE 入口；阅读它用于核对会话键和身份来源。`ai/session` 下没有其它未阅读的生产 Java 文件。

### 2.2 完整阅读：直接下游、键登记与运行配置

- `backend/src/main/java/com/example/configmgr/ai/memory/RedisChatMemoryRepository.java`
- `backend/src/main/java/com/example/configmgr/ai/history/ConversationTranscriptStore.java`
- `backend/src/main/java/com/example/configmgr/ai/form/FormAttachmentStore.java`
- `backend/src/main/java/com/example/configmgr/ai/form/RedisFormAttachmentStore.java`
- `backend/src/main/java/com/example/configmgr/file/FileStorageService.java`
- `backend/src/main/java/com/example/configmgr/file/FileStorageMultiInstanceGuard.java`
- `backend/src/main/java/com/example/configmgr/ai/run/CancellationRegistry.java`
- `backend/src/main/java/com/example/configmgr/ai/run/ClientRequestRegistry.java`
- `backend/src/main/java/com/example/configmgr/ai/run/CreateTaskDraftStore.java`
- `backend/src/main/java/com/example/configmgr/ai/run/LeaseService.java`
- `backend/src/main/java/com/example/configmgr/ai/run/RedisFrameStore.java`
- `backend/src/main/java/com/example/configmgr/ai/run/SessionKeyRegistry.java`
- `backend/src/main/java/com/example/configmgr/ai/config/RedisAvailability.java`
- `backend/src/main/java/com/example/configmgr/ai/tenant/RequestContextUtils.java`
- `backend/src/main/java/com/example/configmgr/ai/tenant/TenantKeyScope.java`
- `backend/src/main/java/com/example/configmgr/ai/tenant/RunKeyScope.java`
- `backend/src/main/java/com/example/configmgr/ai/tenant/RunIdentityScope.java`
- `backend/src/main/java/com/example/configmgr/ai/tenant/TenantIdentityFilter.java`
- `backend/src/main/resources/application.yml`
- `backend/src/main/resources/application-cluster.yml`
- `backend/src/main/resources/db/migration/V3__ai_feedback.sql`
- `backend/src/main/resources/db/migration/V11__ai_feedback_tenant_scope.sql`

### 2.3 按实际调用点阅读的目标区段

以下大文件阅读了本链实际进入的方法及其必要相邻实现；未声称逐行通读与 DELETE 无关的其它业务方法：

- `backend/src/main/java/com/example/configmgr/ai/run/RunStore.java`：会话索引、快照读、pending 三表读、13 键删除、run 租约、frame/lease 键、观测键、上下文键与 TTL。
- `backend/src/main/java/com/example/configmgr/ai/run/ResumeService.java`：`CancelFinalize` 与 `cancelConfirmed` 全链。
- `backend/src/main/java/com/example/configmgr/ai/run/ResilientChatService.java`：`cancelParked`、取消终态、transcript、记忆收敛和统一终态清理。
- `backend/src/main/java/com/example/configmgr/ai/run/SseChatEmitter.java`：终帧归档探测及有预算的分页扫描。
- `backend/src/main/java/com/example/configmgr/ai/config/AiProperties.java`：会话锁、purge retry、lease 默认值。

### 2.4 明确不在删除面

代码没有从 DELETE 调用 KB 刷新、KB 缓存、知识文件或业务文件清理；`AiSessionPurgeService` 的负向清单也明确排除 KB、业务作业和业务文件目录。frame 不通过 `RedisFrameStore` 的删除 API，而是由 `RunStore.deleteRun` 把 `ai:xevents` 放进同槽 13 键 DEL。lease 的 run 键和 epoch 键也在该 DEL 中；`ai:lock:*` 资源锁有意不删。draft/form/transcript 的具体命令见后文。

## 三、完整入口 → 服务 → Redis/DB/线程 → 返回调用链

### 3.1 HTTP 与身份

1. `TenantIdentityFilter` 覆盖该 DELETE 路径，读取并校验身份头，调用 `RequestContextUtils.useContext`，在 `finally` 对应的作用域关闭时清理/还原线程身份。证据：`backend/src/main/java/com/example/configmgr/ai/tenant/TenantIdentityFilter.java:84-151`。
2. `AiSessionPurgeController.purge` 校验 path 参数，先调用 `RedisAvailability.isAvailable()`；健康时正缓存 TTL 为 0，因此每次都会 PING。随后再次要求身份存在，同步调用 `purgeService.purge(sessionId, InstanceId.get())`。证据：`backend/src/main/java/com/example/configmgr/ai/web/AiSessionPurgeController.java:71-103`、`backend/src/main/java/com/example/configmgr/ai/config/RedisAvailability.java:38-53,72-100`。
3. `AiSessionPurgeService.purge` 固定入口身份，并在整个同步主体上建立 `RequestContextUtils` 作用域；RunStore、记忆、附件、transcript 等据此拼键。证据：`backend/src/main/java/com/example/configmgr/ai/session/AiSessionPurgeService.java:324-347`。
4. 控制器最后调用 `result.toBody()`，附带身份快照，记录一条汇总日志并返回 200；`complete=false` 也在同步主体结束后才返回。证据：`backend/src/main/java/com/example/configmgr/ai/web/AiSessionPurgeController.java:87-103`、`backend/src/main/java/com/example/configmgr/ai/session/AiSessionPurgeService.java:264-311`。

### 3.2 主服务的实际顺序

`purgeInternal` 与 `executeDeletion` 的真实顺序如下；这里按代码而不是只按类注释排列：

1. **登记 pending**：新 pending 通常 `ZADD due → EVAL REGISTER_IF_ABSENT → ZADD due`；复用 pending 还会 CAS 重置 nextAt/attempts。证据：`AiSessionPurgeService.java:350-373`，`SessionPurgePendingStore.java:279-345`。
2. **完整枚举**：先读 gate/current/sessionRuns，再逐 run 读快照和 pending/req/decided；然后依次做 staging SCAN、记忆存在性、transcript、archive SCAN+turnNo 推导、其它上下文、会话记录、draft、附件和 DB COUNT。证据：`AiSessionPurgeService.java:566-685`。
3. **装 guard**：单键 `SET PX`，TTL 默认 24h。证据：`AiSessionPurgeService.java:389-400`，`SessionPurgeGuard.java:110-114`。
4. **DB 先删**：`ai_feedback` 在 `REQUIRES_NEW` 本地事务里按 session+tenant+user DELETE；失败则不进入 Redis 删除。证据：`SessionPurgeDatabase.java:57-65,98-113`。
5. **取消活跃 run**：逐 run 读/写取消键，可同步进入 `ResumeService.cancelConfirmed`。证据：`AiSessionPurgeService.java:845-902`。
6. **等终态**：所有活跃 run 共享 1500ms 窗口，50ms sleep，未收敛者每拍重读整份快照。证据：`AiSessionPurgeService.java:139-151,904-948`。
7. **刷新 run 状态**：每个候选再 `GET` 并反序列化快照。证据：`AiSessionPurgeService.java:950-981`。
8. **删除主体**：记忆 → transcript → 逐 run → 上下文 → 会话记录/draft/clientRequest → 附件文件与登记。证据：`AiSessionPurgeService.java:461-487`。
9. **仅主体无错误且无 deferred 才删发现键**：current/sessionRuns/sessionActive。证据：`AiSessionPurgeService.java:488-494,1404-1437`。
10. **收尾**：guard owner-CAS 续期；pending 不立即删，而是排到 60 秒后做记忆复核。证据：`AiSessionPurgeService.java:503-529`，`SessionPurgePendingStore.java:480-500`。
11. **返回**：只有无 errors 且无 deferred 才 `complete=true`；部分失败会保留 pending 并排退避。证据：`AiSessionPurgeService.java:495-540`。

### 3.3 自动重试线程不是 HTTP 的异步替身，但会重复同一重链

- 每实例创建一条 `ai-session-purge-retry` 守护线程，initial delay 10s，fixed delay 30s，每轮最多 10 条。证据：`SessionPurgeRetryRunner.java:77-89,154-175`。
- runner 用 `ai:purge:retry:lock` 做跨 JVM 单飞，TTL 300s，并有续租与 5 分钟候选停滞判据；但 HTTP DELETE 不取这把锁。证据：`SessionPurgeRetryRunner.java:198-237`。
- 普通 pending 到点后调用 `purgeService.retry`，会重新完整枚举，因此重新执行两趟 Cluster SCAN、DB COUNT/DELETE 和删除主体。证据：`SessionPurgeRetryRunner.java:253-327`，`AiSessionPurgeService.java:429-459`。
- 主体 complete 后 60 秒的 `verifyMemoryOnly` 还会单独进行一趟 staging 全主节点 SCAN；它不在原 HTTP  latency 内，但属于后台 Redis 负载。证据：`AiSessionPurgeService.java:1072-1106,1136-1177`。
- 每条台账按记录恢复身份，不扫描租户列表。证据：`SessionPurgeRetryRunner.java:272-305,392-401`。

## 四、Redis 命令、键数、扇出、游标和阻塞清单

下表按一次首次 DELETE 列出实际命令形态。`R`=候选 run 数，`A`=扫描与 turnNo 推导去重后的 archive 数，`S`=staging 数，`F`=附件数，`M`=Cluster master 数，`P_m`=某 master 实际游标页数。除 SCAN 外，点命令均只路由到目标键所在节点；当前 purge 主链没有 pipeline 或并行执行器。

| 阶段 | Redis/DB/文件命令与键数 | SCAN/KEYS 与 Cluster 扇出 | 串/并行、游标/批大小、等待 |
|---|---|---|---|
| 前置探活 | `PING` ×1 | 无键扫描 | 同步；健康结果不缓存；`synchronized` 串行化真实探针 |
| pending 登记 | 新：`ZADD` due + 单键 Lua + `ZADD`；复用另有 CAS Lua | pending 与 due 不同槽，刻意分开发送 | 串行，无跨槽 Lua |
| run 发现 | gate 本地 miss 时 `GET` lock；`GET` current；`SMEMBERS` sessionRuns | 不读全量 `ai:runs` | 串行；gate/current 读失败目前折成 null，见 SDC-08 |
| 每个 run 枚举 | 整快照 `GET`；`HGETALL` pending、req、decided，共 4 次/run | 4 个键；run 键同 tag，但这里不是多键命令 | 逐 run 串行；快照完整 JSON 反序列化；lenient 的 req/decided 失败可折空 |
| staging 枚举 | `SCAN MATCH chat:mem:staging:...:* COUNT 256`，收集全部匹配键 | **全 master 串行扇出；无 KEYS** | 每节点游标走到 0；`maxKeys=0`；无总页数/时间上限 |
| 记忆枚举/删除/复核 | 枚举 `EXISTS` final；删除一条 3 键同槽 Lua：`DEL final + INCR gen + INCR ver + PEXPIRE`；删后 `EXISTS` final | 3 键同 hash tag，一次单节点脚本 | staging 每键 `DEL`，DEL 成功再 `EXISTS`，共最多 2S；串行 |
| transcript 枚举/删除 | 枚举 data/bytes `EXISTS`，data 存在再 `LLEN`；删除 data/bytes/ready/truncated 各 `TYPE`，匹配再 `DEL` | 4 个精确键、同会话 tag；不扫描 | 删除最多 8 次串行往返 |
| archive 枚举 | `SCAN MATCH ai:ctx:archive:...:* COUNT 256`；另 `HGET ctx turnNo` 并推导 0..turnNo | **第二次全 master 串行扇出；无 KEYS** | 游标全部耗尽；与扫描结果去重；turnNo 有效时最多额外推导 2001 个精确键 |
| 其它上下文枚举/删除 | 枚举 ctx/ledger/safety/events 各 `EXISTS`；删除这 4 键及 A 个 archive，各 `TYPE`，匹配再 `DEL` | 精确键，不扫描 archive 之外的键族 | 最多 `2×(4+A)` 次串行往返 |
| 会话/draft/client 请求 | 枚举 current/session record/draft `EXISTS`；删除 record 先 `GET` 再 `DEL`；ctdraft 和 creq 各 `TYPE`+可选项 `DEL` | 全部精确键；不扫描 | 串行；类型校验用于前缀碰撞保护 |
| 活跃 run 取消 | `GET`/`SET PX` cancel；可继续执行会话门 Lua、run 租约三键 Lua、verify/release Lua、frame `XREVRANGE/XRANGE`、pending 读写、快照写等 | run 租约三键同槽；frame 为单 run Stream | 每个活跃 run 串行；不是只有 1.5s 等待；详见 SDC-05 |
| 等终态/刷新 | 活跃 run 每 50ms 整快照 `GET`；之后所有候选再整快照 `GET` | 点命令 | 1500ms 是共享等待窗，不含此前取消耗时；读耗时会占窗口 |
| 每个终态 run 删除 | deleteRun 内再读快照；本进程持租时先条件 release；13 键一次 `DEL`；`SREM ai:runs`；有快照时 `ZREM` sessionActive、`SREM` sessionRuns；另 4 个单键 `DEL` beat/activity/cancel/observed | 13 键同 tag，**一次同槽 DEL**；4 个观测键不同槽所以逐键 DEL | 全部串行；常见有快照路径枚举+刷新+删除约 3 次整快照 GET/解析 |
| 附件枚举/删除/文件 | `SMEMBERS` session index；枚举每附件 `GET`；删除登记时再 `GET` + `DEL` registration + `DEL` consumed + 两个 `SREM` | 精确键/索引；不使用跨槽 MGET | 每附件合计常见 6 次 Redis 操作；文件 I/O 串行同步，详见 SDC-06 |
| 发现键 | current `GET`+可选 `DEL`；sessionRuns/sessionActive 各 `TYPE`+可选 `DEL` | 精确键 | 仅无 errors/deferred 才执行 |
| 完成收尾 | guard 单键 Lua；pending `GET` + CAS Lua + `ZADD` verifyAt | pending/due 分键串行 | 60 秒后另有记忆复核，不删 pending 于当前请求内 |
| 失败记台账 | pending `GET` + CAS Lua + `ZADD` 退避 | 精确键 | 串行；基础退避 10s，封顶 300s |
| DB | 枚举 `SELECT COUNT(*)`；删除 `DELETE FROM ai_feedback ...`，独立事务 | 无 Redis 扇出 | 两次同步 JDBC 阶段；索引问题见 SDC-07 |

### 4.1 13 个 run 键的准确边界

`RunStore.deleteRun` 的一次 DEL 包含：run、runstate、pending、claim、ledger、idem、seq、xevents、run lease、fence、req、decided、run lease epoch。证据：`backend/src/main/java/com/example/configmgr/ai/run/RunStore.java:2792-2810,2886-2908`。因此不能把“13 键”误算成 13 次网络往返，也不能把 frame/lease 漏报为未清。

### 4.2 SCAN 的实际成本模型

`RedisClusterScanSupport.scan` 每趟重新取得连接，Cluster 下读取拓扑，再按节点顺序执行：

```text
T_scan = T_connection/topology + Σ(master m) Σ(cursor page p)
         (网络等待 + Redis 该页 SCAN 工作 + 结果解码) + cursor close
```

证据：`backend/src/main/java/com/example/configmgr/ai/session/RedisClusterScanSupport.java:55-80,98-147`。`COUNT=256` 不保证每页恰好 256，也不限制总页数；应以实测 `P_m` 计算，不能简单把总键数除以 256 当成精确命令数。首次 DELETE 的预算为 `T_scan(staging) + T_scan(archive)`，两者顺序执行，不是并行。单例 Redis 仍有同样两遍完整键空间遍历，只是没有 `M` 的节点遍历与拓扑维度。

## 五、逐项问题

### SDC-01：首次 DELETE 串行执行两趟无界全键空间 SCAN，Cluster 下逐 master 放大

- **严重度：高。**
- **确定性：代码证实机制；实际超过 30 秒待实测。**
- **证据：**
  - staging 在枚举阶段调用全扫描：`backend/src/main/java/com/example/configmgr/ai/session/AiSessionPurgeService.java:631-640,817-828`。
  - archive 在 ContextTargets 构造中调用全扫描：`backend/src/main/java/com/example/configmgr/ai/session/AiSessionPurgeService.java:660-665,739-742,778-795`。
  - 两处都 `count(256)` 且 `maxKeys=0`；Cluster master 依次扫描，游标完全 drain：`backend/src/main/java/com/example/configmgr/ai/session/RedisClusterScanSupport.java:51-55,98-147`。
- **触发条件：** 任意首次/重试 DELETE，即使没有本会话 archive/staging 也会扫描；大 keyspace、慢节点、多 master、SCAN 页 RTT 增高时更明显。
- **单例/Cluster/多实例/多线程影响：** 单例是两次 O(keyspace)；Cluster 是两次 `Σmaster O(node keyspace)` 且串行；多实例并发 DELETE 会各自重复；runner 重试也会重复。
- **根因：** archive 与 staging 没有供销毁直接消费的完整会话级枚举索引；为覆盖 legacy/异常残留，把全键空间对账放进了同步请求。
- **最佳修复：** 根治见第 7.2 节：写入侧建立完整、同槽/原子维护的会话级索引，遗留数据迁移/对账移出单次请求；删除只消费精确集合，并以数据键复核。
- **备选取舍：** 增大 COUNT 只减少部分 RTT、增加单页 Redis 工作和响应体；并行 master 只降 wall time、增加瞬时 Redis 压力；两者都不是复杂度根治。禁止用 KEYS 替代，禁止无迁移地直接删 SCAN。
- **涉及修改点/影响面：** `AiSessionPurgeService`、`RedisClusterScanSupport` 的调用面、`RunStore.archive`、`RedisChatMemoryRepository` staging/CAS 写路径、键迁移与删除完整性验证。

### SDC-02：没有请求级总预算；默认 2 秒是每命令超时，不是接口超时

- **严重度：高。**
- **确定性：代码证实；具体故障序列和耗时待实测。**
- **证据：** Cluster 与 standalone 都配置 `timeout: 2s`、`connect-timeout: 2s`：`backend/src/main/resources/application.yml:74-84`、`backend/src/main/resources/application-cluster.yml:20-36`。DELETE 主流程没有共享 deadline，多个循环catch后继续：`AiSessionPurgeService.java:462-540,1150-1175,1261-1268,1334-1362,1439-1494`。
- **触发条件：** 节点慢、连接池/网络排队、多个点命令相继接近超时；或大量正常命令的 RTT 累加。
- **影响：** 单例也会累加；Cluster 增加节点和跨槽目标数量；多线程会占住 Tomcat 请求线程；多实例没有共享接口预算或全局限速。
- **根因：** 把“单命令快速失败”误当成了“整请求有界”；`RedisAvailability` 的 PING 只证明探针时刻可用，不能约束后续链。
- **最佳修复：** 建立端到端 phase budget/deadline，并把预算传入枚举、SCAN、run、DB、文件阶段；超预算必须保留 durable pending/guard 和发现键，返回可重试的未完成状态。
- **重要正确性约束：** 当前“本次新建 pending 在枚举失败时回滚”的逻辑位于 `AiSessionPurgeService.java:375-387`。若只新增一个超时异常就返回“后台继续”，会与回滚语义冲突；必须先明确 durable 工单是否保留、由谁续做，不能伪报后台已接收。
- **备选取舍：** 单纯调大 Redis timeout 会进一步拉长请求；只加 Tomcat 超时可能断开客户端却不终止服务端工作；两者都不能替代服务端预算和持久化进度。
- **修改点/影响面：** controller/service 结果契约、pending 状态机、runner 接续语义、所有阻塞下游的预算传递。

### SDC-03：run/archive/transcript/context 的同步串行点操作与重复大快照解析形成第二条线性慢链

- **严重度：高（大会话）；中（小会话）。**
- **确定性：代码证实。**
- **证据：**
  - 每个候选先整快照 GET+JSON 解析：`AiSessionPurgeService.java:594-629`，`backend/src/main/java/com/example/configmgr/ai/run/RunStore.java:2472-2533`。
  - 每个候选再读 pending/req/decided 三份 HASH：`AiSessionPurgeService.java:624-628`，`RunStore.java:3363-3397,3495-3519,3600-3609`。
  - await 后 reload 又整读一次：`AiSessionPurgeService.java:958-980`；deleteRun 的 `readForDelete` 又读一次：`RunStore.java:2843-2883`。
  - run 主体逐 run 调用：`AiSessionPurgeService.java:479-483`；上下文逐键 TYPE+DEL：`AiSessionPurgeService.java:1309-1363`。
- **触发条件：** R 或 A 大、快照 messageJson 大、Redis RTT 变高；archive turnNo 接近 2000 时，即使 SCAN 无结果也会生成大量精确删除目标。
- **影响：** Cluster 下键分布更多但当前不并行，不能把分布当成降延迟；多实例/多线程线性增加命令与 JSON CPU；standalone 同样存在。
- **根因：** 请求内以简单逐键调用组合所有清理步骤，没有跨阶段快照复用/按槽 pipeline；`RunStore` 已有的轻量状态和 pipeline 能力没有被该 DELETE 链用于消除这些重复读。
- **最佳修复：** 保留归属和状态复核前提下，按 slot 分组 pipeline 独立 GET/HGETALL/单键命令；终态快照在身份、状态仍有效时传递只读删除上下文，避免无意义重复反序列化。`TYPE+DEL` 可改成单键 Lua 一次完成，transcript 同槽键可做有类型校验的同槽脚本。
- **备选取舍：** 直接删掉 RunStore 内最后一次归属读会减少 RTT，但削弱独立防线，不建议；跨槽 MGET/多键 Lua/DEL 会引入 CROSSSLOT，不能用它们代替按槽 pipeline。
- **修改点/影响面：** `AiSessionPurgeService`、`RunStore` 删除接口、快照归属不变量、transcript/context 的前缀碰撞保护；必须保持活跃 run 不删和发现键最后删。

### SDC-04：同会话没有执行级单飞，HTTP 与 runner 可重复执行同一 purge

- **严重度：高（并发/故障重试时）；正确性主要依赖幂等，性能放大为代码证实。**
- **确定性：代码证实存在并发窗口；实际发生频率待实测。**
- **证据：** 重复 DELETE 复用同一个 purgeId：`backend/src/main/java/com/example/configmgr/ai/session/SessionPurgePendingStore.java:279-340`；`purge` 随后仍完整执行枚举和删除：`AiSessionPurgeService.java:350-418`。runner 对同一 pending 也直接 retry：`SessionPurgeRetryRunner.java:253-305`。全链没有以 pending key 为锁的“执行中 claim”；runner 全局锁只在 `SessionPurgeRetryRunner.java:213-236` 包住 runner 自己。
- **触发条件：** 用户重复点击/客户端重试、多实例同时收到 DELETE、手动 DELETE 将 nextAt 重置为 now 后 runner 到点、前一次请求尚未结束。
- **影响：** 两趟 SCAN、DB、文件删除、所有点命令可被翻倍或多倍；多实例共享 Redis/DB/文件系统时互相放大。单例多线程也同样可重叠。
- **根因：** 把 pending 的 owner-CAS 当成销毁执行互斥；它实际只保证“旧执行体不得改新台账”，并不保证“只有一个执行体”。
- **最佳修复：** 在 pending 上增加带唯一 execution token、到期/续租和 stalled 接管语义的 per-session 执行 claim；重复 HTTP 读取并返回当前执行状态，不再跑第二套主体。不要复用会话门代替 purge 执行锁，以免与正常 run 生命周期混淆。
- **备选取舍：** 仅加 JVM 锁不能覆盖多实例；仅全局 runner 锁不能覆盖 HTTP；简单 SETNX 且无续租/停滞恢复会把慢清理变成永久卡死。
- **修改点/影响面：** `SessionPurgePendingStore` 状态、`AiSessionPurgeService.purge/retry`、controller 响应、runner 接管；需保持 pending/guard 的防写回语义。

### SDC-05：活跃 run 的同步取消收尾可在 1.5 秒等待之前执行大量工作

- **严重度：高（活跃/多 pending run）；无活跃 run 时不触发。**
- **确定性：代码证实调用链；实际耗时待实测。**
- **证据：**
  - `cancelActiveRuns` 先对所有活跃 run 同步执行，再进入 `awaitTerminal`：`backend/src/main/java/com/example/configmgr/ai/session/AiSessionPurgeService.java:462-470,845-902`。
  - `cancelConfirmed` 依次可读快照、取消态、终帧归档、取会话门、抢/验 run 租约、`cancelParked`、再探归档/快照：`backend/src/main/java/com/example/configmgr/ai/run/ResumeService.java:2323-2430`。
  - `cancelParked` 会反序列化快照历史并进入取消终态：`backend/src/main/java/com/example/configmgr/ai/run/ResilientChatService.java:3330-3341,3389-3521`。
  - 单次终帧探测可从归档尾部有预算地分页扫描；预算为 `WAKE_BATCH_LIMIT+256`，其中 WAKE_BATCH_LIMIT=6000：`backend/src/main/java/com/example/configmgr/ai/run/SseChatEmitter.java:278,1629-1640,2323-2408`，`backend/src/main/java/com/example/configmgr/ai/run/FrameStore.java:51`。
- **触发条件：** 删除时有 RUNNING/SUSPENDED/UNKNOWN run；无本地驱动、需接管租约、归档无终帧或读不可知时链更长。
- **影响：** Cluster 下每个阶段路由到不同节点但仍是同步串行；多活跃 run 累加；请求线程还承担 JSON、transcript、帧归档等工作。
- **根因：** 为避免活跃 run 复活数据而正确设置了 guard/deferred，但把“无人收尾的完整补偿”也放进了 HTTP 同步链。
- **最佳修复：** 请求内完成取消事实写入和必要的状态判定后，把未立即终态者明确 deferred，保留 pending、guard 和发现键，由既有有界收尾设施推进；DELETE 不得删除仍活跃 run 的 13 键。这样是带持久义务的异步接续，不是掩盖错误的异步化。
- **备选取舍：** 简单并发多个 run 的取消会放大会话门/租约竞争和帧写压力；简单延长 awaitTerminal 只会更慢；删除活跃 run 来换取延迟是正确性倒退。
- **修改点/影响面：** `AiSessionPurgeService.cancelActiveRuns/awaitTerminal`、`ResumeService` 收尾接线、重试和终帧幂等。

### SDC-06：附件把同步文件 I/O 与每附件多次 Redis 往返放在请求线程

- **严重度：中高（多附件/慢共享盘）；无附件时不触发。**
- **确定性：代码证实阻塞 API 和命令形态；文件系统阻塞时长待实测。**
- **证据：** `deleteAttachments` 逐件 `resolve → Files.exists → fileStorage.delete → Files.exists → deleteRegistration`：`backend/src/main/java/com/example/configmgr/ai/session/AiSessionPurgeService.java:1439-1494`。`FileStorageService.resolve` 每次经 `baseDir()`，后者还执行 `Files.createDirectories`；delete 再 resolve 一次：`backend/src/main/java/com/example/configmgr/file/FileStorageService.java:68-75,108-131,182-188`。登记删除命令见 `backend/src/main/java/com/example/configmgr/ai/form/RedisFormAttachmentStore.java:565-593`。
- **触发条件：** F 大、网络文件系统/杀软/磁盘卡顿、登记或索引异常；多实例共享存储时尤其需要测。
- **影响：** 所有实例都阻塞各自请求线程并竞争共享存储；Java 文件 I/O 没有由 Redis 的 2 秒配置约束。
- **根因：** 文件事实与 Redis 登记需要按序收敛，但实现同时做了重复路径解析/目录检查和逐件同步 Redis 调用。
- **最佳修复：** 每附件只解析一次真实路径并复用；保持“文件删除确认后才删登记”的顺序。Redis 点命令按槽 pipeline；慢文件清理可由有界 worker 接续，但 pending/登记必须保留到事实确认，不能用 `Future.get(timeout)` 假装底层 I/O 已被取消。
- **备选取舍：** 先删登记再慢慢删文件会让重试失去文件路径，不可接受；无界新建线程会把共享盘卡顿转为线程资源事故。
- **修改点/影响面：** `AiSessionPurgeService.deleteAttachments`、可复用的文件删除结果/路径、`RedisFormAttachmentStore` 索引清理、pending 部分失败语义。

### SDC-07：反馈表两次同步 DB 操作缺少以 session 为后续列的复合索引，可能放大枚举与删除

- **严重度：中；不是 Redis Cluster 主因。**
- **确定性：代码/SQL 证实查询与现有索引形态；实际执行计划和是否全扫待实测。**
- **证据：** COUNT 与 DELETE 都按 `session_id + tenant_id + user_id`：`backend/src/main/java/com/example/configmgr/ai/session/SessionPurgeDatabase.java:89-113`。现有索引为 `(tenant_id,user_id,updated_at)` 和 `(tenant_id,user_id,message_id)` 唯一约束，没有 `(tenant_id,user_id,session_id)`：`backend/src/main/resources/db/migration/V11__ai_feedback_tenant_scope.sql:18-24`。
- **触发条件：** 单身份反馈行很多、DB 锁等待、共享 H2/PostgreSQL 负载高。
- **影响：** 枚举和 DELETE 各付一次查询/锁成本；多实例共享 DB 时可能互相影响。
- **根因：** 现有索引服务“按身份列反馈”，未覆盖销毁的会话定位顺序。
- **最佳修复：** 用 EXPLAIN/锁数据确认后，在 H2 与 PostgreSQL 两套迁移中补 `(tenant_id,user_id,session_id)`；保留独立短事务和失败不进入 Redis 删除的顺序。
- **备选取舍：** 新索引有写入和存储成本；若身份基数很小且表很小，实测可能决定无需新增，不能仅凭 SQL 文本声称已经发生全表扫描。
- **修改点/影响面：** 两套 DB migration、查询计划、反馈写路径索引开销。

### SDC-08：gate/current 两个补充发现读失败被折成 null，与“枚举失败整体中止”不一致，条件成立时可漏删并提前结束

- **严重度：高（正确性，需索引缺成员的前置条件）；不是主要性能来源。**
- **确定性：代码证实错误折叠；残留需要特定数据/故障交错，未实测。**
- **证据：** `SessionGate.currentRunId` 读异常返回 null：`backend/src/main/java/com/example/configmgr/ai/session/SessionGate.java:494-505`；`RunStore.sessionCurrentRunId` 同样返回 null：`backend/src/main/java/com/example/configmgr/ai/run/RunStore.java:2426-2437`。`enumerate` 仅在 sessionRuns 读取处显式 fail-closed，gate/current 的 null 被当成无候选：`backend/src/main/java/com/example/configmgr/ai/session/AiSessionPurgeService.java:566-592`。
- **触发条件：** 某轮因历史原因不在 sessionRuns，仅由 gate/current 点名；对应 GET 恰好失败，而后续枚举/删除又成功。
- **影响：** standalone/Cluster/多实例均可能出现；最坏是该 run 未进入取消/删除清单，后续发现键还可能在无 errors 时被删，形成误报 complete 的风险。
- **根因：** “索引不存在”和“索引状态不可知”在这两个读口被合并成同一个 null。
- **最佳修复：** 为 gate/current 提供严格读取口，异常上抛并让整个枚举在删除前中止；保留真正键不存在的 null。不要为减少延迟而吞掉该故障。
- **备选取舍：** 回退全局 `ai:runs` 全量扫快照会增加大量成本且改变唯一发现面，不应采用。
- **修改点/影响面：** `SessionGate`、`RunStore`、`AiSessionPurgeService` 枚举错误语义与 aborted 响应。

## 六、最可能耗时预算链：代码事实与待实测拆分

### 6.1 预算公式

```text
T_DELETE = T_ping + T_pending_register
         + T_gate/current/sessionRuns
         + ΣR(T_snapshot_JSON + T_pending/req/decided)
         + T_SCAN_staging + T_SCAN_archive
         + T_transcript/context/session/attachment_enumeration + T_DB_COUNT
         + T_guard + T_DB_DELETE
         + Σactive(T_cancelConfirmed) + T_awaitTerminal + ΣR(T_reload_JSON)
         + T_memory + T_transcript_delete
         + ΣR(T_run_delete + 4 observation DEL)
         + ΣA(TYPE+DEL) + T_session/draft/client
         + ΣF(T_file_io + T_registration_delete)
         + T_discovery + T_guard/pending_finish
```

代码能证明这些阶段的串行顺序和上述命令，但不能证明每阶段的实际毫秒数。尤其 `T_SCAN_*` 随整个键空间变化，`T_file_io` 不受 Redis timeout 控制，`T_cancelConfirmed` 随 run 状态分支变化。

### 6.2 按场景的优先级

| 场景 | 最可能首要来源 | 当前证据级别 | 首先应测什么 |
|---|---|---|---|
| 大 keyspace、少 run 的普通删除 | 两趟串行全主节点 SCAN | 机制代码证实，排序待实测 | 每趟连接/拓扑、每 master 页数、游标总时长、键空间大小 |
| R/A/F 大 | 点命令 RTT、整快照 JSON、archive TYPE+DEL、附件 I/O | 机制代码证实 | R/A/F 与阶段命令数、快照字节、解析 CPU、共享盘延迟 |
| 有活跃 run | cancelConfirmed/cancelParked 和终帧探测，然后才是 1.5s await | 机制代码证实 | 每个 run 的 CancelFinalize 结局、归档扫描页数、租约/会话门等待 |
| Redis 慢/故障 | 无总 deadline 下的 2s 单命令超时累乘 | 配置与控制流证实 | 每次异常发生在哪条命令、是否重连/重定向、请求线程堆栈 |
| 重复删除/自动重试重叠 | 同一主体的重复 SCAN/DB/文件/Redis 工作 | 并发窗口代码证实 | 同 purgeId 并发执行数、execution 重叠时间、runner/HTTP 来源 |

**超过 30 秒的判定必须看整链而非只看最后一次 Redis 异常。** 一个常见误区是把 1500ms `ACTIVE_WAIT` 当成 DELETE 的等待上界，或把 2s Redis timeout 当成接口上界；代码均不支持这种结论。

## 七、最小止血与根治方案

### 7.1 最小止血：不改删除完整性，不先引入新键族

按风险从低到高组合，而不是只选“异步”：

1. **先消除重复执行：** 对同 pending 增加 per-session execution claim；重复 DELETE 返回当前进度，不再执行第二份 SCAN/删除。保留 owner-CAS、guard、pending 和现有幂等结果。
2. **减少现有命令的 wall time：** 对独立点读/点删按 slot 做有界 pipeline；把单键 TYPE+DEL 合成单键 Lua；run 枚举复用仍有效的终态快照。13 键 DEL 和记忆同槽脚本保持不动。
3. **SCAN 只做有界并行止血：** 可对独立 master 使用很小的固定并发上限并汇总全部结果；任一节点失败仍整次中止。它降低墙钟但不降低总 SCAN 工作，必须限制并发，不能每请求创建无界线程。
4. **活跃 run 快速 deferred：** 已写取消旗标且不能立即确认终态时，不等待完整 `cancelParked` 才返回；保留 run 13 键、发现键和 pending，由有界收尾/重试推进。
5. **增加分阶段观测：** pending/enumerate/stagingScan/archiveScan/db/cancel/runDelete/context/attachments/finalize 分别记录耗时、页数、R/A/S/F、节点数和错误类型。没有这些指标，不应宣称某修复已解决 30 秒问题。

若同时引入请求 deadline，必须接续 durable 工作：只返回快而把本次新建 pending 回滚掉，会让用户得到“未完成但无人续做”的假止血。

### 7.2 根治：把“发现本会话键”从 O(全键空间) 改为 O(本会话键数)

**推荐设计：**

- **archive：** 新写路径维护会话级 archive 成员集合，集合与归档写入需要同槽原子维护；若为此引入 session hash tag/新键形，要制定双读、迁移和旧键清理方案。`turnNo` 精确推导可继续作为完整性补充，但不能替代异常残留对账。
- **staging：** 在与 final/staging 相同的 `{escapedId}` tag 下维护 staging index；STAGE_WRITE 同一 Lua 内写 staging 并登记，RENAME/MERGE_CAS/清理时摘成员。这样 Cluster 同槽且不会因“ staging 成功、索引未写”产生不可发现中间态。
- **删除：** 从 sessionRuns、archive index、staging index、attachment session index 得到精确目标；按 slot pipeline 删除并复核数据键。遗留/无索引数据由一次性迁移或有界后台对账处理，完成前不得把相应旧命名空间宣称为完全无残留。
- **状态机：** pending 记录枚举/执行阶段和 owner；runner 接续的是持久义务，不是简单重复同一慢请求。只有全部数据面确认完成才删发现键并排最终记忆复核。

**取舍：** 这是写路径、键结构和迁移面的变更，成本高于 pipeline/并行 SCAN，但它同时解决请求 latency、后台重复 SCAN 和随全库增长的退化。若暂不引入 archive 新键形，可设计独立索引加持久补偿，但必须证明索引写失败不会留下永远不可发现的归档；不能为了少改键而弱化这条不变量。

## 八、后续验证方案（只列方案，本次未执行）

### 8.1 基准与耗时归因

1. 在独立测试环境建立 standalone 与真实 3-master Cluster，对同一身份、同一数据量分别测 DELETE；在键空间放入大量与本会话无关的键，证明旧实现随全库增长、新实现只随本会话目标数增长。
2. 固定 R/A/S/F 矩阵，至少覆盖空会话、仅记忆、仅 staging、仅 archive、多 run、2000 附近 turnNo、多附件；记录 P50/P95/P99、每阶段耗时、每 master 游标页数、Redis 命令数和 JSON 字节数。
3. 用测试专用网络代理引入受控 RTT/抖动，分别定位单命令 2s 超时与正常 RTT 累加；不得用生产 KEYS 或生产压测取证。
4. 验证目标应先约定，例如“在明确的数据规模与网络条件下 P99 小于 30 秒”，并同时报告总完成时间；不能只证明 HTTP 更快返回而后台永远清不完。

### 8.2 Cluster 正确性

1. 检查无 KEYS、无跨槽 MGET/多键 Lua；13 键 DEL、记忆三键脚本、run 租约三键脚本保持同槽。
2. 在 MOVED/ASK、单 master 慢/失败、游标中断下，验证枚举失败时一个数据键都不删；部分删除失败时 `complete=false`、pending/guard/发现键保留。
3. 对 archive/staging 新旧键形、索引缺失、 stale 成员、重复 SCAN 返回、异常高 turnNo 做迁移与对账验证；确认其它会话、其它身份、KB 和业务文件不受影响。
4. 验证记忆写回与删除交错、RENAME 成功但回包丢失、写者 crash、60 秒复核；`complete=true` 后不得复活正式记忆/transcript。

### 8.3 多实例、多线程与活跃 run

1. 同实例并发 DELETE、跨实例同身份同 sessionId DELETE、HTTP 与 runner 同时推进：应只有一个执行主体，其它调用只查询/等待状态。
2. 不同身份复用同一 sessionId/runId：所有键仍按身份隔离；runner、watchdog、取消收尾按记录身份恢复，不落入 legacy。
3. RUNNING/SUSPENDED、无本地驱动、租约在他实例、会话门被占、终帧缺失等情况下：活跃 run 的 13 键不得提前删； deferred 后必须能续清，不能只改善 HTTP 时间。
4. 持续故障下确认请求线程数、SCAN 并发、pipeline 大小、runner 线程均有界；pending 重试有上限和退避，不产生SCAN风暴。

### 8.4 DB 与文件

1. 对 COUNT/DELETE 获取 H2/PostgreSQL 实际执行计划与锁等待，决定并验证 `(tenant_id,user_id,session_id)` 索引；两个方言迁移都要覆盖。
2. 在共享文件存储注入延迟/失败：文件未确认删除时登记必须保留；重复执行幂等；不能因客户端超时而伪造文件删除成功。
3. 验证每个登记只按 storagePath 删除，不扫目录、不碰 KB/业务文件；多实例使用共享绝对路径的启动约束仍生效。

## 九、未发现问题的关键路径与已有良好约束

以下是代码审查中的正向结论，不等于运行期性能已经合格：

- **没有使用 KEYS/FLUSHDB/通配 DEL。** 两趟必要枚举使用 SCAN，其它会话/run/transcript/draft/form 大多由精确索引或精确键定位。
- **Cluster SCAN 的覆盖与失败姿态正确。** 逐 master、游标关闭、全局去重；任一节点失败上抛，不把部分扫描冒充完整。问题是同步成本，不是单节点漏扫实现。
- **run 的 13 键同槽。** `deleteRun` 用一次 DEL 原子删除 run/runstate/pending/claim/ledger/idem/seq/xevents/lease/fence/req/decided/epoch；frame 与 lease 没有遗漏。
- **观测键的跨槽处理正确。** beat/activity/cancel/observed 没强行放进多键 DEL，而是四个单键 DEL，避免 CROSSSLOT。
- **记忆删除不是简单 DEL final。** 同槽脚本同时推进 generation/version，防止在飞写回把旧历史复活；guard 在删除前安装，pending 未完成时即使 guard TTL 到期仍阻止写回。
- **发现键最后删。** 有 errors/deferred 时 sessionRuns/sessionCurrent/sessionActive 保留，避免重试失去枚举入口。
- **run 发现不扫全局 ai:runs。** 主枚举只读本身份 sessionRuns 并补 gate/current；全局 ai:runs 在删除时仅 SREM 对应复合成员。
- **pending 发现面有界。** 自动重试用 due ZSET 的有界 ZRANGEBYSCORE，不用 SCAN；每轮 10 候选、每会话 10 次自动尝试、指数退避和耗尽退役均存在。
- **runner 有多实例单飞与停滞处理。** 300s 锁、续租、唯一 owner、5 分钟候选推进窗口和先停续租后释放均有代码；但它不覆盖 HTTP 并发，见 SDC-04。
- **身份传播主链完整。** HTTP filter → service 作用域 → 下游拼键，runner 按 pending 记录恢复身份，run 租约/watchdog 也有记录身份作用域。
- **transcript/context/draft/clientRequest 有精确键与前缀碰撞保护。** transcript 删除覆盖 data/bytes/ready/truncated；上下文覆盖 ctx/ledger/archives/safety/events；ctdraft 和 creq 都按单 HASH 删除。
- **附件不扫目录。** 只按登记 storagePath 删文件，再删登记/墓碑/两个索引成员；多实例共享存储路径有启动闸门。
- **KB 没有被误调用。** 会话销毁与 KB 刷新/缓存无线索上的调用关系，负向清单明确排除；因此 KB 不是本 DELETE 的隐藏耗时点。

## 十、审查限制

本次没有真实 Redis Cluster、应用日志、请求 trace、慢日志、线程转储或数据规模证据，也未按用户禁令运行任何验证。因此：

- 可以确认“存在多条可超过 30 秒的无总预算同步链”，不能确认某次实际请求的唯一根因。
- `COUNT=256` 的实际页大小、Lettuce 连接/拓扑耗时、DB 执行计划、共享盘阻塞、active cancel 的分支耗时均需后续实测。
- 修复优先级基于代码复杂度和调用顺序：先 SDC-01/02/03/04，再按是否有活跃 run/附件处理 SDC-05/06；SDC-08 是独立的删除正确性问题，不应因性能改造而延后遗忘。
