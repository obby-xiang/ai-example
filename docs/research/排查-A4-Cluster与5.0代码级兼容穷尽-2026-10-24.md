> **入库注记（2026-10-24）**：本件入库日期 = **2026-10-24**；**来源 = 用户实测确认「生产 Redis = 原生 Redis Cluster（`redis_mode:cluster`、16384 槽全分配、3 主 3 从、版本 5.0.14、`CLUSTER NODES` 原生返回完整拓扑，排除哨兵 / 代理）」之后的专项分析**（仓外审计碎片 `R1-S7`，原样入库、正文未改写）。**性质如实交代** = 只读审计（未改仓内文件、未构建、未跑测试、未起停服务），且审计环境**既无 Cluster 也无 5.0.14 实例** ⇒ 全文判定为「源码 + 依赖 jar 字节码 + Redis 官方版本差集」推导，**未经真集群复现**；其 §十「批次 0 · 真 5.0.14 集群冒烟网（6 条）」即为此设的补证前置。

# R1-S7 · 原生 Redis Cluster（5.0.14，3 主 3 从）代码级兼容性 · 非 Lua 面穷尽

> ## 勘误块（2026-10-24，只追加；**不改本报告历史正文**）
>
> 本报告的判定基础 = **源码 + 依赖 jar 字节码 + Redis 官方版本差集**，审计环境**既无 Cluster、也无 5.0.14 实例**。以下 7 条是事后复核确认的**口径更正**，与正文冲突处以本块为准；正文（含 §〇 判定基准、§7-2、§8.1、§八 版本差集、§十 批次 0、§十一 覆盖面）**一律不回改**。
>
> **① 事实 F / §7-2 B8「PING 只聚合成功值 ⇒ 单节点故障不会 503」不成立。**
> `RedisAvailability#isAvailable` 走 `connection.ping()`，Cluster 下是"全节点执行"；只要有一次往 **active master 的连接落在异常窗口**（建连/握手失败、节点被 kill 后的重定向窗口、topology 尚未收敛、读超时），异常就会抛出方法外 ⇒ `available=false` ⇒ AI 端点 **503**。**慢节点同样会超时**；而拓扑收敛后，探活**可能自行恢复**（结果带正/负 TTL 缓存，恢复不是即时）。所以"比单机更宽松"的结论撤回：这一面必须在**真集群上分阶段观测**（注入后立即 / 收敛中 / 恢复后各取一次），不能只按字节码推断。
>
> **② §8.1 的爆炸半径不止「`?lastSeq>0` 的重挂回放」。**
> `stageReplay` 恒调 `readFrom(runId, afterSeq, 0)`，而读侧下限被夹到 `tail − 窗口`（`MAXLEN ~` 是近似裁剪，必须夹）⇒ **长 run（事件数 > 窗口 6000）的首次回放**（`lastSeq=null` / `afterSeq=0`）下限同样 `> 0`，**同样走这条区间路径**。表现是"首次进房也只拿到窗口内最新几条、更早的帧静默缺失"。只按"重挂差量"估影响面会低估。
>
> **③ 批次 0 的拓扑不能是 2 主 0 从。**
> 无副本时杀掉一个主节点**没有故障切换可验证**：副本提升、槽位接管、客户端重连与重订阅恢复这些路径全部测不到。**至少 3 主 3 从**（`--cluster-replicas 1`、3 分片），先把"杀主 → 副本提升 → 拓扑收敛 → 客户端重连/重订阅"整链跑通，再谈 6 条冒烟。
>
> **④ `cluster.nodes` 优先于 `host/port`（本报告按单节点 `host/port` 读配置，属口径缺口）。**
> `spring.data.redis.cluster.nodes` 一旦配置，Spring Boot 走 **Cluster 连接工厂**，`spring.data.redis.host/port` 被**忽略** —— 这不是"standalone + 一个额外开关"。因此 §〇 与 §十一 的「`application.yml:78-84` 是单节点 `host/port`」只能读作**仓库默认配置**是单节点形态，**不能据此推断生产形态**；部署侧（`cluster.nodes` / DB0 / 代理 / TLS）本报告已明示未审，需另取生产配置核对。
>
> **⑤ `TIME` in Lua / `CLIENT SETINFO` 降级为观察项。**
> 这两条本报告判【语义变化】并写进批次 2，实际应作为**观察项**：`TIME` 在 5.0 起脚本默认"按效果复制"，`AiRateLimiter` 的滑动窗口脚本（先 `TIME` 再 `ZADD`/`PEXPIRE`）在 5.0.14 上是否报 `Write commands not allowed after non deterministic commands` **尚无真集群实测**；`CLIENT SETINFO` 是 7.2+ 子命令，Lettuce 6.6 的 `RedisHandshake` 预期容错（只刷 ERROR 日志、不反复重连），同样需日志面观测。两者**都不构成批次 1 的硬前置**，观测到异常再升级。
>
> **⑥ §8.1 的实现侧现状（只作事实登记，**不作为"已验证"**）。**
> 当前工作树 `RedisFrameStore#streamFramesAbove` 已改为**闭区间 `(from+1)-0`**（类注写明"长 run 首次回放同样中招"与"下限夹到 `tail − 窗口`"两条理由）；`scripts/redis5-cluster-smoke.sh` 提供真集群侧的冒烟与对照（脚本已交付，**本机没有真集群，未运行过**）。改动是否真的让 5.0.14 补回帧，**仍需真集群复核**。
>
> **⑦ 结论措辞纪律（本报告与后续引用一律适用）。**
> 本报告所有 Cluster / 5.0.14 判定**不得被写成"已验证"或"已实测"** —— 审计环境既无 Cluster 也无 5.0.14 实例，全部属"源码 + 字节码 + 官方版本差集"推导，只能写"推导 / 待真集群复核"。真集群冒烟通过后，须**另起勘误行**写「（真集群已验证：<日期> / <拓扑> / <用例>）」，不改本块。

- **任务编号**：R1-S7（承接 A-4 分布式 Redis 适配审查；补 S2「Lua 与多键」之外的全部触点）
- **扫描范围**：`backend/src/main`（19 个持有 `StringRedisTemplate` 的类 + 4 个间接使用者 + `application.yml` + `pom`），`backend/src/test` 的替身与断言；e2e / `scripts/` 按任务约定**不审**（S2 已覆盖，且属本地单机面）。
- **只读**：未改仓内任何文件、未运行构建/测试、未起停服务。
- **判据来源**：源码 + 依赖 jar 的字节码核对（spring-data-redis `3.5.11` / lettuce `6.6.0.RELEASE`，由 `backend/pom.xml:9-10` 的 spring-boot 3.5.14 带入）+ Redis 官方版本差集。环境里没有 Cluster、也没有 5.0.14 实例，**未做真集群复现**（这一点本身即 §8.4/§9 的结论之一）。

---

## 〇、判定基准（几个必须先钉死的客户端事实）

后续所有"硬错误 / 语义变化"的判定都依赖这 6 条，逐条给了验证方式，避免复述"大概是"。

| # | 事实 | 证据 | 对本审的意义 |
|---|---|---|---|
| A | **多键 `DEL` 在 Cluster 下由 Lettuce 按 slot 拆分**，不报 CROSSSLOT，结果为各槽删除数之和 | `RedisAdvancedClusterAsyncCommandsImpl` 重写了 `del(K...)` / `exists(K...)` / `mget(...)`（字节码：方法表存在）；spring-data 的 `LettuceClusterKeyCommands` **未**重写 `del`，故直落 Lettuce cluster 命令 | `RunStore.deleteRun`（12 键）在 Cluster 下是**原子性消失**，不是报错 |
| B | 原始命令 `connection.execute("CMD", args...)` 在 Cluster 下**按第一个 key 路由** | spring-data `LettuceConnection.execute` → `CommandArgs.addKeys(所有 args)`；Lettuce `ClusterDistributionChannelWriter.doWrite` 用 `getFirstEncodedKey()` 算 slot 再取连接 | `RedisFrameStore.xadd` 的 `args[0]=key` 是**唯一**让 XADD 落到正确节点的原因（隐含约定，脆） |
| C | 脚本执行走 `EVALSHA`，`NOSCRIPT` 时自动回落 `EVAL` | `DefaultScriptExecutor.eval` 字节码：catch → `ScriptUtils.exceptionContainsNoScriptError` → `RedisConnection.eval` | Cluster 下**不需要**逐节点 `SCRIPT LOAD`；§6 的"每节点首次 EVAL"是正常代价 |
| D | `MOVED`/`ASK` 由 Lettuce 自动重定向 | `ClusterDistributionChannelWriter` 内 `getMoveTarget` / `getAskTarget` + `maxRedirections` | 普通单键命令**不会**因 resharding 永久失败，但会抛瞬时异常 ⇒ §7 的每个 catch 都会被打到一次 |
| E | 无 key 的命令落到默认连接（随机节点） | 同一路由逻辑：`getFirstEncodedKey()==null` ⇒ `defaultWriter` | PING 类探活不是"全集群探活" |
| F | `LettuceClusterConnection.ping()` = **全节点执行 + 只聚合成功值** | 字节码：`clusterCommandExecutor.executeCommandOnAllNodes(pingCommand())` → `resultsAsList()`（`toList` 只 `add(NodeResult.getValue())`，不抛） | §7-2 的探活语义变化 |

另外两条基准（沿用 S2，不重复论证）：全仓**零 hash tag**；`application.yml:78-84` 是单节点 `host/port`、**无 `database:`**（⇒ DB0）。

---

## 一、全部 Redis 操作触点清单（穷尽）

**检索方式**：`opsForValue|opsForHash|opsForList|opsForSet|opsForZSet|opsForStream|opsForGeo|opsForHyperLogLog`、`redis.execute|delete|hasKey|expire|getExpire|convertAndSend|ping` 全量 grep，逐命中归到类。生产侧共 **~100 个调用点 / 19 个持模板类**。

### 1.1 分类汇总（先给结论）

| 类别 | 数量 | 明细 |
|---|---|---|
| **全库型**（KEYS / SCAN / RANDOMKEY / FLUSHDB / FLUSHALL / DBSIZE） | **0** | 全仓零命中（`ScanOptions`、`Cursor<`、`randomKey`、`flushDb` 均无） |
| **多键命令**（一次命令带多 key） | **1** | `RunStore.deleteRun`（`:633-635`，12 键 DEL）→ 事实 A：拆槽执行 |
| **多键 Lua** | **4** | S2 已列（ACQUIRE / FENCED_SET / IDEM_PUSH / DELETE_WITH_INDEX）——本文不重复定级 |
| **单键 Lua** | **5** | RENEW / VERIFY / RELEASE / SLIDING_WINDOW / COMPARE_DELETE → §6 |
| **单键命令** | 其余全部 | 见 1.2 |
| **多键"多次往返"**（一次逻辑动作碰 N 个键，但没有单条多键命令） | 6 处 | §4.2 |

### 1.2 逐类触点表

| 类 | 主要调用点 | 键 | 形态 | 判定 |
|---|---|---|---|---|
| `ai/run/RunStore`（58 处） | :408 `SADD`；:453/:538/:1442/:1843 `SET`；:484/:556/:572/:588/:612/:1063/:1403/:1634 `GET`；:511/:1152 `execute(Lua)`；:633 `delete(List)`；:636/:712 `SREM`；:697 `SMEMBERS`；:704 `hasKey`；:749/:979/:1043/:1571 `HGET/HSETNX` 类；:759/:833/:917/:1722 `HGETALL`；:822/:905/:1078/:1216/:1576/:1587 `HGET`；:1164/:1267/:1381/:1382/:1618/:1648/:1663/:1764/:1765/:1778/:1807/:1808/:1823 `LIST`；:1383/:751/:980/:1044/:1491/:1572/:1852 `EXPIRE`；:1755 `HSET` | ~18 个前缀全为 `键前缀+id` | **单键**（唯一多键 = deleteRun :633） | 【安全】单键面；deleteRun 见 §1.3 |
| `ai/run/RedisFrameStore`（8 处） | :138 `INCR`；:166 `SET`；:226-234 `XADD`+`EXPIRE`（同一 callback）；:302-315 `XRANGE`；:374/:412 `LRANGE`；:432-443 `XREVRANGE` | `ai:xevents:` / `ai:seq:` / `ai:events:` | 单键，跨 3 个前缀 | 【语义变化】读侧独占区间（§8.1）；写侧【安全】 |
| `ai/run/LeaseService`（6 处） | :302 `execute(ACQUIRE, 2 keys)`；:345/:371/:387 单键脚本；:546 `hasKey`；:564 `ping` | `ai:lease:*` / `ai:lock:*` / `ai:lease:epoch` | 1 多键 + 4 单键 | 多键归 S2（R1-01）；单键【安全】 |
| `ai/run/CancellationRegistry`（2 处） | :218 `GET` / :234 `SET`(TTL) | `ai:cancel:<runId>` | 单键 | 【安全】；失败降级见 §7 |
| `ai/run/RedisFrameWakeBus`（2 处） | :50 `convertAndSend`；:119 `getConnectionFactory` | 频道 `ai:wake:<runId>` | 单频道 | 【安全】带 3 条注意（§3） |
| `ai/memory/RedisChatMemoryRepository`（10 处） | :108 `SMEMBERS`；:115 `hasKey`；:123 `SREM`；:138 `SISMEMBER`；:143 `LRANGE`；:163 `DEL`；:164 `RPUSH`；:166 `SADD`；:171 `execute(2 keys)`；:177 `EXPIRE` | `chat:mem:<cid>` + `chat:mem:__ids__` | 1 多键 Lua（S2）+ 9 单键 | 单键【安全】；索引见 §8.3 |
| `ai/gate/AiRateLimiter`（1 处） | :133 `execute(SLIDING_WINDOW, 1 key)` | `ai:rate:session:*` / `ai:rate:ip:*` | 单键脚本 | 【安全】 |
| `ai/kb/RedisKbCacheStore`（8 处） | :45/:59 HASH；:69 `GET`；:79 `INCR`；:90 `SETNX`；:101 脚本；:112 `DEL`；:122 `TTL` | `kb:cache:doc:*` / `:manifest` / `:manifest:version` / `:refresh:lock` | 全单键 | 【安全】（跨键不要求原子，写序 + 读侧重试兜，S2 已认） |
| `ai/session/SessionGate`（6 处） | :79 `SETNX`(TTL)；:82/:105/:122 `GET`；:107 `DEL`；:143 `EXPIRE` | `ai:session:lock:<sid>` | 单键 | 【安全】；GET→DEL 的 TOCTOU 是既有窗口（S2 已列） |
| `ai/session/AiSessionService`（4 处） | :59 `hasKey`；:64 `SET`；:76 `hasKey`；:89 `GET` | `ai:session:<sid>` | 单键 | 【安全】 |
| `ai/form/RedisFormAttachmentStore`（5 处） | :65/:104 `SET`；:82 `GET`；:102 `DEL`；:128 `hasKey` | `ai:form:att:<fid>` / `ai:form:att:used:<fid>` | 单键 | 【安全】命令面；降级面见 §7-2 |
| `ai/config/*`（4 处） | `AiConfigService:215` `GET`；`AiConfigWriter:586` `INCR`；`AiModelCatalog:95/115/118` `GET/SET` | `ai:cfg:ver` / `ai:cfg:models:<pid>` | 单键 | 【安全】 |
| `ai/config/RedisAvailability`（1 处） | :86 `RedisCallback → ping` | — | 全节点 PING | 【语义变化】§7-2 |
| `job/service/JobCancellationRegistry`（2 处） | :142 `GET`；:152 `SET` | `job:cancel:<jobId>` | 单键 | 【安全】 |

### 1.3 唯一的多键命令：`deleteRun`（定论）

- **位置**：`backend/src/main/java/com/example/configmgr/ai/run/RunStore.java:625-637`（`delete(List.of(...))` 在 `:633-635`，12 个键覆盖 `ai:run/ pending/ claim/ ledger/ idem/ events/ seq/ xevents/ lease:run/ fence:run/ req/ decided`），随后 `:636` 再 `SREM ai:runs`。
- **判定**：【语义变化】（事实 A：Lettuce 按槽拆成 N 次单键 DEL，**不报错**）。三个后果：①"一轮的全部键同生同死"不成立；②中途失败留下**半截状态**（例如 `ai:run:` 删了、`ai:idem:` 没删 ⇒ 下一轮同 id 继承前任幂等索引 ⇒ 工具不再执行）；③Round-trip 从 1 次变 ≥3 次（12 键按 3 主分）。
- **减轻事实**：全仓 `deleteRun(` 只有测试调用点（`RunStoreFencedWriteTest.java:162`、`RunStoreIdempotencyIndexTest.java:219/229`；`AiControllerHistoryDeleteTest.java:294` 甚至断言"从不调用"），当前不是活的生产路径。
- **修法方向**：随 S2 的 `{run:<runId>}` hash tag 改造同批解决（同槽后恢复"单命令、原子"）；短期在方法 Javadoc 标注"Cluster 下退化为 N 次单键删"。

---

## 二、SCAN 的语义陷阱

**结论：生产代码零 SCAN，零 KEYS ⇒ 本类缺陷不存在。** 这是本仓最该记为"正面"的一条。

- 检索证据：`grep -rn "\.scan(|ScanOptions|Cursor<|\.keys\(|randomKey|flushDb|flushAll"` 在 `backend/src/main/java` **零命中**；唯一 `scan(` 是 `backend/src/main/java/com/example/configmgr/ai/web/AiController.java:764` 的 `runner.scan(true)`（业务"扫描待办"，与 Redis 无关）。
- 需要全局枚举的地方，全部用**自建索引集合**（与分片无关，代价是 N+1 往返）：
  - `ai:runs`：写 `RunStore.java:408`（`SADD`）、清理 `:636`/`:712`；读 `:697`（`SMEMBERS`）+ 逐 id `:704`（`hasKey`）+ 惰性 `SREM`。
  - `chat:mem:__ids__`：写 `RedisChatMemoryRepository.java:166`；读 `:108`（`SMEMBERS`）+ `:115`（`hasKey`）+ `:123`（`SREM`）；单点判存 `:138`（`SISMEMBER`）。
- **判定**：【安全】（分片语义同构）。两条附注（不构成本面缺陷，转 §8.3 / A-6）：
  1. `SMEMBERS` 是单键大集合 ⇒ 只能落一个 slot（热点 + 大 key），且随 run/会话数线性增长；
  2. `SMEMBERS + N×EXISTS` 在 Cluster 下是"1 + N"次跨节点往返（单机是 N 次本地往返），启动续跑扫描（`StartupResumeRunner`）与 `GET /api/ai/history` 的 P99 会随 N 上升。
- **修法方向**：不必改。若要压热点，`ai:runs` 按 `{runs:<shard>}` 分桶（当前规模不必）；`chat:mem:__ids__` 真正的问题是原子性而非枚举（§8.3）。

---

## 三、Pub/Sub

**结论：可用，但有三条必须写进迁移说明的注意项，其中一条与 §8.1 的版本缺陷耦合。**

- 位置：`backend/src/main/java/com/example/configmgr/ai/run/RedisFrameWakeBus.java` —— `publish` `:47-56`（`:50` `convertAndSend(CHANNEL_PREFIX + runId, ...)`）、`subscribe` `:58-83`（`:75` `addMessageListener(listener, new ChannelTopic(channel))`）、容器懒启动 `:105-135`（`:119` `redis.getConnectionFactory()`）。
- **频道模型**：每 run 一个频道（`ai:wake:<runId>`），按需 `RedisMessageListenerContainer`；**只用 `ChannelTopic`，不用 `PatternTopic`**；**全仓无 keyspace notification**（`notify-keyspace-events` / `KeyspaceEvent` / `KeyExpirationEvent` / `RedisKeyExpiredEvent` 零命中 ⇒ 托管集群禁用该配置项对本案无影响）。

| # | 注意项 | 证据 | 判定 |
|---|---|---|---|
| 1 | **订阅落在单个节点**。`RedisMessageListenerContainer` 没有任何 cluster 特判（字节码方法表无 cluster 相关分支），它用一条连接订阅；该连接由 Lettuce 的 cluster pub/sub provider 提供（单节点）。节点故障/切主后靠容器重订阅恢复，窗口内的唤醒丢失 | `RedisMessageListenerContainer` 只有 `doSubscribe/createSubscriber` 一类逻辑；`LettuceConnection.doCreateSubscription` 取 `StatefulRedisPubSubConnection` | 【安全】唤醒可丢，正确性由 `?lastSeq` 重连兜底 |
| 2 | **广播放大**：一条 `PUBLISH` 经集群总线广播到**全部主节点**（3 主 = 3 倍出向流量），且每实例各维持一条订阅连接（N 实例 × 1 连接 + 频道数 × 节点数的消息分发） | Redis Cluster PUBLISH 传播语义 | 【安全】规模起来再评估 `SSUBSCRIBE`（S2 已登记） |
| 3 | **唤醒只是触发器，帧体靠 `readFrom` 取** ⇒ 与 §8.1 的 5.0.14 缺陷直接耦合：跨实例唤醒在 5.0.14 上"发得出去、补不回帧" | `SseChatEmitter.java:1260`（`readFrom(from, WAKE_BATCH_LIMIT)`，`from = lastFanoutSeq > 0`） | 【语义变化】见 §8.1-A |

- 附（非 Cluster 问题）：容器懒建但**没有 `@PreDestroy` 停止**（`RedisFrameWakeBus.java:105-135`），而 `LeaseService` 有（`:573-585`）。单实例无感，集群化后每次优雅停机留一条未关订阅连接。判【安全】，顺手可修。
- **修法方向**：①保留；②迁移说明里写明"唤醒可丢、回放不可丢"，并把 §8.1 修完才敢开多实例唤醒；③容器加停机钩子。

---

## 四、事务与管道

### 4.1 结论：全仓零 MULTI / 零管道 / 零 WATCH

- 检索证据：`executePipelined`、`.multi()`、`.exec()`、`.watch(`、`SessionCallback`、`setEnableTransactionSupport` 在 `backend/src/main/java` **全部零命中**；`@Transactional` 命中均为 JDBC/JPA（`transactionTemplate` 是 DataSource 事务）。
- **判定**：【安全】——Cluster 的「MULTI 禁跨 slot」限制在本仓**不存在**。
- 代价（要如实写进设计文档）：仓内所有"多键一致性"只能靠 ①Lua（⇒ S2 的 4 处跨槽）或 ②多次单键往返（⇒ 下列 6 处的非原子窗口）。**没有第三种**，所以 Cluster 迁移不可能"只改配置"。

### 4.2 六处"多键多往返"（不是命令级多键，但常被当原子读）

| # | 位置 | 键面（一次逻辑动作） | Cluster 下的变化 | 判定 |
|---|---|---|---|---|
| 1 | `RedisChatMemoryRepository.java:157-167`（`saveAll` 非空路径） | `chat:mem:<cid>` DEL → RPUSH → EXPIRE → `chat:mem:__ids__` SADD（**4 命令 / 2 键 / 2 槽**） | 单机窗口 = 进程崩溃；Cluster 多一层"跨节点可见性差异"（数据键已写、索引未写时，另一实例全无感知） | 【语义变化】（R1-06 已定级，这里补 Cluster 维度） |
| 2 | `RunStore.java:758-776`（`pendings`） | `ai:pending:r` HGETALL → `ai:req:r` HGETALL → `ai:decided:r` HGETALL（**3 键 3 槽**） | 读序是对的（条目先读、guard 后读），所以"决策已落、条目未跟"仍能收敛；代价是 3 次跨节点往返 + 中间窗口内**新决策不可见** | 【安全】 |
| 3 | `RunStore.java:429-472`（`save`） | `ai:run:r` SET（或 fence 脚本）→ `ai:session:current:<sid>` SET → 终态时 `ai:lease:run:r` 释放（**3 键**） | 非原子：快照写成功、索引写失败 ⇒ `GET /runs/current` 找不到在跑轮（`:540-542` 只 WARN） | 【语义变化】（体验面，非正确性） |
| 4 | `RunStore.java:1489-1493`（`renew`） | 心跳 5 键 + 读侧 4 键，逐个 `EXPIRE`（**首错即停**） | 见 §7-2 高危项 | 【语义变化】（高危） |
| 5 | `RunStore.java:1028-1057`（`tryClaimExecution`） | `ai:fence:run:r` GET（`:1063`）→ `ai:claim:r` HSETNX + EXPIRE（`:1043-1044`）→ `ai:pending:r` HSET（`:1049-1055`） | 单机窗口是 TOCTOU（`:1069` 已注释）；Cluster 下 GET 与 HSETNX 分落两槽，**没有任何办法**用 Lua 合并（除非 `{run}` tag） | 【语义变化】+（`fence` 读失败见 §7-2） |
| 6 | `ai/kb/CachedKbSource.java:254-259` | `kb:cache:doc:*` → `kb:cache:manifest` → INCR `:manifest:version`（**写序固定 + 读侧 6×25ms 重试兜**） | 与单机同构（跨槽不影响"先写文档后写清单"的可见性协议） | 【安全】（S2 已认，属正确范式） |

- **修法方向**：1 与 5 随 `{run:<runId>}` / 会话族 tag 或"索引分片"方案改造（见改造清单）；4 单独小修（逐键 try）；2/3/6 不动。

---

## 五、SELECT / 多逻辑库

**结论：零动态选库，且域隔离本来就是"键前缀"而非"库号" ⇒ 这条 Cluster 硬约束对本仓零影响。这是本仓最幸运的一处。**

- 检索证据：`setDatabase` / `select(` / `getDatabase` / `database` 在 `backend/src/main/java` **零命中**；`backend/src/main/resources/application.yml:78-84` 的 `spring.data.redis` 只有 `host/port/timeout/connect-timeout/lettuce.shutdown-timeout`，**没有 `database:`** ⇒ 恒用 DB0。
- 全仓**没有任何多逻辑库假设的注释或逻辑**（唯一提"库"的地方在 e2e harness，按任务约定不审；S2 已记）。
- **判定**：【安全】。可迁移性还体现在：所有键都是 `域前缀:<id>`（§8.3 清单），因此 Cluster 的"只有 DB0"不需要任何键名改造就能满足。
- **修法方向**：无需改。**建议把"禁止引入 `database:`/`SELECT`"写成一条 CI 守卫**（grep 级即可），因为这是迁移后最容易被人"顺手"破坏的约束（例如给 e2e 复用某段代码）。

---

## 六、单键 Lua 的隐性假设（逐条核脚本体）

被点名的 5 条（S2 已判"安全"），这里补的是**脚本体内部**是否藏了第二个键 / 全局计数器 / 非确定性命令：

| # | 脚本 | 位置 | 脚本体实际触碰 | 隐性假设核查 |
|---|---|---|---|---|
| 1 | `LEASE_RENEW` | `LeaseService.java:111-119` | 仅 `KEYS[1]`（GET + PEXPIRE） | 无第二键、无计数器、无 TIME ✔ |
| 2 | `LEASE_VERIFY` | `:122-129` | 仅 `KEYS[1]`（GET） | ✔ |
| 3 | `LEASE_RELEASE` | `:132-140` | 仅 `KEYS[1]`（GET + DEL） | ✔ |
| 4 | `RATE_SLIDING_WINDOW` | `AiRateLimiter.java:79-102` | 仅 `KEYS[1]`（ZREMRANGEBYSCORE/ZCARD/ZADD/PEXPIRE/ZRANGE）+ **`redis.call('TIME')`** | 无第二键 ✔；`TIME` 是**版本敏感点**（见下） |
| 5 | `KB_CACHE_COMPARE_DELETE` | `RedisKbCacheStore.java:26-34` | 仅 `KEYS[1]`（GET + DEL） | ✔ |

- **判定**：【安全】（Cluster 维度）。三条执行约束请写进改造纪律：
  1. **不要**在"批量 tag 化"时把 1/2/3/5 也加上 tag —— 单键脚本加 tag 只牺牲分布、零收益；`RATE_SLIDING_WINDOW` 的键按 sessionId/IP 天然分散，加 tag 会把限流热度压进一个槽（自造热点）。
  2. `TIME`（`AiRateLimiter.java:81`）：5.0 起脚本默认"按效果复制"，理论上允许；但**门禁是 7.2.16，证明不了 5.0 的行为**。判【语义变化】→ 需在 5.0.14 上冒烟；若报 `Write commands not allowed after non deterministic commands`，退路是"客户端传时间 + 保留 Lua 的原子性"（代价是多实例时钟漂移，需权衡）。
  3. 5 条脚本都经 `DefaultScriptExecutor`（事实 C）：Cluster 下每节点首次执行走一次全量 `EVAL`，属正常开销，**不需要**"逐节点 SCRIPT LOAD"这类额外基建（很容易被误加）。

---

## 七、catch 降级面（"不报错但功能没了"的放大器清单 · 共 31 处）

分级口径：**A = 静默丢正确性**（会产出错的结果/重复副作用）；**B = 功能面静默缺失**（口径上允许丢，但要能被观测）；**C = fail-closed**（Cluster 下会 5xx，但语义正确，属"响亮的失败"）。

### 7-1 A 级：静默丢正确性（必须优先改造）

| # | 位置 | 降级动作 | Cluster 下由什么触发 | 后果 | 修法方向 |
|---|---|---|---|---|---|
| A1 | `RunStore.java:1061-1073`（`fenceEpoch`） | 读失败 ⇒ 返回 `0` | resharding 期 MOVED/TRYAGAIN、节点故障 | `tryClaimExecution:1036` 的 `fence > epoch` 永远不成立 ⇒ **本拍 fencing 失效、认领放行** ⇒ 重复执行工具副作用。作者已 WARN（`:1070`） | 读失败应**区分**"键不存在"（⇒0 合理）与"读不出来"（⇒ 拒绝认领，fail-closed）；或把 GET+HSETNX 合成一条同槽脚本 |
| A2 | `RedisFrameStore.java:121-135`（`nextSeq`） | 进程内 `AtomicLong` 兜底 | 同上（INCR 瞬时失败） | 跨实例**取号不再唯一** ⇒ 同号帧在 `mergeBySeq:336-350`（同号以 Stream 为准）被**去重丢一帧** | 兜底号段改为"跳一大步"（如 +1e6）或直接 fail-closed（宁可这一轮不出帧，也不出重号帧） |
| A3 | `RedisFrameStore.java:290-322`（`streamFramesAbove`） | 返回 `List.of()` | ①5.0.14 的 XRANGE 语法错误（§8.1）；②任何读异常 | **回放/唤醒差量为空且无告警（debug 级）** ⇒ 前端重连后"什么都没补回来"、跨实例扇出彻底失效 | ①修 §8.1；②异常时**返回 null 与"确实无帧"区分开**，让调用方决定（重挂可回 5xx/重试），至少升到 WARN + 计数 |
| A4 | `RedisFrameStore.java:429-449`（`streamTailSeq`） | 返回 `0` | 读异常 | `lastSeq()` 退化为只看 LIST ⇒ `stageReplay` 从 0 重放（重复帧）且 `streamFramesAbove:292` 直接提前返回（**假"无帧"**） | 同上：区分"空流"与"读失败" |
| A5 | `CancellationRegistry.java:206-229`（`redisValue`） | 返回 `null`（= 未取消） | 读异常（含 5s 退避期） | **跨实例取消丢失**：另一个实例发的取消，运行方读不到 ⇒ 一直跑到预算/超时 | 读失败时不要把"不知道"折成"未取消"；至少暴露 `degraded` 给调用方（现在 `degraded` 只进 `status()`） |
| A6 | `RunStore.java:820-840`（`decisionMarker(s)`） | 返回 `null`/`Map.of()` | 读异常 | 读侧 WAL 收敛失效 ⇒ **已决的卡被当成"待人工输入"重新挂起** ⇒ 用户被重复问、审批语义漂移 | 决策 guard 读失败应 fail-closed（拒绝重建卡） |

### 7-2 B 级：功能面静默缺失（要能被观测）

| # | 位置 | 降级动作 | 后果 | 判定/修法 |
|---|---|---|---|---|
| B1 | `ResilientChatService.java:1204-1210`、`:1698-1704` | `clear()+add()` 整体 `catch(RuntimeException)` → WARN | **R1-04 的放大器**：`clear()`（2 键 Lua）在 Cluster 下 CROSSSLOT ⇒ 被吞 ⇒ **`add()` 不执行 ⇒ 该轮收敛后的历史根本没落库**（保留上一版）。爆炸半径从"一个 DELETE 端点"扩到"每一轮记忆写回" | 【语义变化】。修法：①先修 R1-04；②把 `clear+add` 的两步拆成"可降级的三步"（save 成功后再删旧余量），或让 `clear` 失败不阻断 `add`（`clear` 单独 try）——**否则修好 R1-04 后会变成"删除成功但写回静默跳过"的新形态** |
| B2 | `RunStore.java:1489-1493`（`renew`） | **首个键抛错即停**，整面只记一条 debug（`:1476-1478`） | 13 个键里**排在后面的全部不续期** ⇒ 长挂起（>24h）卡的后台数据在 Redis 里过期 ⇒ **人点批准时"挂起态不存在"**。Cluster 下每次瞬时异常都可能命中 | 【语义变化】。修法：逐键 `try`（一个键失败不牵连其余）+ 失败计数 |
| B3 | `ai/form/RedisFormAttachmentStore.java:67-70 / 87-89 / 109-111 / 132-134` | 回落**进程内 Map** | 多实例下登记表分裂：上传在 A、回灌在 B ⇒ **"附件不存在"/"未消费"判定失真**。这不是"加速面失效"，而是**数据面丢失**，WARN 措辞（"降级进程内登记"）掩盖了严重性 | 【语义变化】。修法：附件登记面在多实例下必须 fail-closed（登记失败就告知上传失败），不能静默回落进程内 |
| B4 | `RedisFrameStore.java:193-211`（`append`） | 落档失败只 WARN（`:199-210`） | 帧对**本实例**实时可见、对其它实例/重挂不可见 ⇒ Cluster 多实例下"一个人看得到、另一个人看不到" | 【语义变化】，口径允许（外置是"更耐久"不是"更正确"），但要计入 SLO |
| B5 | `RunStore.java:1381-1387`（外置帧） | debug 级 swallow | 同 B4（兼容路径） | 【安全】 |
| B6 | `RunStore.java:1718-1737`（`read` 上下文）、`:1775-1791`（`contextLedger`）、`:1801-1814`（`appendSafetyEvent`）、`:1817-1836`（`safetyLedger`） | 空态 / WARN | 压缩计量面、审计台账静默缺条 | 【语义变化】（审计面），Cluster 下发生频率上升 |
| B7 | `RunStore.java:973-989`（`markRequestEmitted`） | WARN，位不落 | L0 的洞换形态复现：位丢 ⇒ 刷新恢复少一张卡（**少发，不是错发**，方向良性） | 【安全】 |
| B8 | `RedisAvailability.java:71-103`（`:95-100`） | 异常 ⇒ `available=false` ⇒ AI 端点 503 | **探活语义变化**（事实 F）：Cluster 下 PING 走"全节点 + 只取成功值" ⇒ **单节点故障不会 503**（比单机更宽松）；只有全不可达才 503。与"快速失败"的初衷有偏差（§8.4 冒烟项） | 【语义变化】。修法：需要"多数派/全部可达"口径时用 `clusterGetNodes()` + 逐节点 PING 自己算，别复用 `ping()` |
| B9 | `SessionKeyRegistry` 派生与 `RunStore:1489` 的组合 | — | 见 B2 | — |

### 7-3 C 级：fail-closed（语义正确，但 Cluster 下会更频繁地 5xx）

| 位置 | 行为 | 备注 |
|---|---|---|
| `RunStore.java:508-518`（`fencedSet`） | 抛 `IllegalStateException("无法写入轮次快照")` | R1-02 的硬失败面（S2 已定级）；**注意与 A1 的错位**：A1 放行认领 + R1-02 拒写，组合起来是"执行了但状态写不回" |
| `RunStore.java:1151-1160`（`appendLedger`） | 抛 | R1-03：副作用已发生之后才炸 |
| `RunStore.java:747-756`（`putPending`）、`:1568-1582`（`tryRecordDecision`）、`:1760-1771`（上下文台账）、`:1840-1848`（`archive`） | 抛 | 语义正确 |
| `RunStore.java:611-622`（`get`）、`:758-776`（`pendings`）、`:778-795`（`pending`）、`:1163-1179`（`ledger`）、`:1028-1057`（`tryClaimExecution` 的 HSETNX`） | **不 catch**，异常上抛 | 会 500，但**这是对的**（不能把"读不到"当"没有"） |
| `RedisChatMemoryRepository` 全类（`:107-179`）**无任何 catch** | 异常直抛 | R1-04 的 500；对比 B1 —— 同一份缺陷在"端点"上响亮、在"写回路径"上静默 |
| `RedisKbCacheStore`（8 处） | 转 `KbCacheException` ⇒ `CachedKbSource` 降级直连知识源 | 良性（S2 已认） |
| `AiRateLimiter.java:146-150`、`AiSessionService.java:67-71/:78-80`、`AiModelCatalog.java:102-105/:121-123`、`AiConfigService.java:217-219`、`AiConfigWriter.java:584-591`、`JobCancellationRegistry.java:140-147/:150-158` | 放行/空/未命中 | 良性降级，不改正确性 |
| `SessionGate.java:88-92 / :104-112 / :121-126 / :151-154` | 降级为进程内单值门 | **正面范本**：降级事实写进返回体（`degraded=true`）+ WARN，不静默 —— 其余 30 处应当照此收口 |

**本节净结论**：决定 Cluster 迁移体验的不是"会不会报错"，而是**这 31 个 catch 把"报错"翻译成了什么**。凡把"异常"翻成"空/0/false/未取消/放行"的，都会在 Cluster 的瞬时异常下变成"功能消失且无告警"。建议统一加一层"Redis 降级计数器"（Micrometer / 结构化日志），否则 §8.1 这种缺陷在线上没有任何信号。

---

## 八、数据结构层面的 Cluster 差异 + 版本差集

### 8.1 Stream（`ai:xevents:<runId>`）——**本面唯一的新硬错误**

**写侧【安全】**：`RedisFrameStore.java:193-235`，`XADD key MAXLEN ~ 6000 <seq>-0 seq <n> frame <json>` + `EXPIRE`，同 key、两命令、同一 callback（`:226-234`）。Cluster 下靠事实 B（`args[0]=key`）路由到正确节点 —— 正确，但**依赖隐式约定**：任何把 key 挪到第二个参数的改动都会静默路由到随机节点（写出"看起来成功、其实写错节点"的帧）。建议在 `xadd` 的 Javadoc 里把"key 必须是 args[0]"写成硬约束。

**读侧【硬错误】+【语义变化】**：

- **位置**：`backend/src/main/java/com/example/configmgr/ai/run/RedisFrameStore.java:297-299`
  ```java
  Range<String> range = from <= 0L
          ? Range.unbounded()
          : Range.from(Range.Bound.exclusive(from + "-0")).to(Range.Bound.unbounded());
  ```
- **事实**：`Range.Bound.exclusive` 经 `RangeConverter.toRange` 保留排他语义（字节码：`Range$Boundary.excluding`），上线为 `XRANGE key (12-0 + COUNT 6000`。**排他区间（`(` 前缀）是 Redis 6.2 才引入的语法**（redis/redis#8072，[Redis XRANGE 文档](https://redis.io/docs/latest/commands/xrange/) 亦注明 "exclusive range intervals are only available from Redis 6.2"）。
- **判定**：目标 **5.0.14** 上 `ERR Invalid stream ID specified as stream command argument` ⇒ 被 `:317-319` 吞成 `List.of()` ⇒
  - ①`SseChatEmitter.java:674-675`（`stageReplay`，`afterSeq = lastSeq`）⇒ **带 `?lastSeq>0` 的重挂回放永远 0 帧**（前端只拿到之后的新帧，断连期间的全丢）；
  - ②`SseChatEmitter.java:1244-1260`（`onWake`，`from = lastFanoutSeq > 0`）⇒ **跨实例唤醒永远补不回帧** ⇒ Cluster/多实例的 SSE 扇出退化成"只有驱动那一轮的实例能推帧"；
  - ③只有 `lastSeq=null`（首次进房）走 `Range.unbounded()` 才能读到（即"能看历史、不能补差量"）。
- **为什么一直没被发现**：门禁是 `redis:7.2.16`（`.github/workflows/ci.yml:206`、`e2e-real.yml:38/184`、`nightly.yml:154`），7.2 完全支持该语法；而单测替身 `FakeFrameRedis.selectRange`（`:235-260`）在 **Java 侧**模拟 `isInclusive`，天然接受 `(` 语义。**测试拓扑与生产版本不同构**，这条永远不会红。
- **修法方向**（3 行改动级）：把下限改成闭区间 `from + 1`（`seq` 是整数，`<from+1>-0` 等价于 `>from`），或读回后客户端过滤；两者都不改变任何上层语义。**这条必须与 Cluster 改造同批或更早**——否则 Cluster 修好了、帧还是补不回来。
- 附带核查（5.0 支持性，已逐条确认可用）：`XADD MAXLEN ~`、`XREVRANGE key + - COUNT n`、显式 ID `seq-0`、`LIMIT/COUNT` 均 5.0 起可用 ✔。全仓**无** `XREAD/XREADGROUP/XACK/XGROUP/XAUTOCLAIM`（检索零命中）⇒ **不存在"多流跨 slot"问题**，也没有 consumer group 语义 —— 用 `INCR` 序号 + Stream 内显式 ID 做差量读取，是 Cluster 下的正确选择（group 反而会引入跨节点 consumer 语义）。

### 8.2 ZSET 限流窗口

- 位置：`AiRateLimiter.java:79-102`（脚本）、`:120-128`（两维：`ai:rate:session:<sid>` / `ai:rate:ip:<ip>` 各自独立单键）。
- **判定**：【安全】。①单键 ZSET，Cluster 下按 key 路由；②窗口值取脚本内 `TIME` ⇒ 多实例用同一节点时钟，**比客户端时钟更正确**；③key 天然按 sessionId/IP 分散，不形成单槽热点；④TTL 覆盖窗口（`PEXPIRE window`）⇒ 无跨节点清理需求。
- 唯一待办：`TIME` 的 5.0 实测（§6-2）。

### 8.3 SET 索引与全局单键（"单点热键 + 与数据键分家"）

| 键 | 读点 | 写点 | Cluster 形态 | 判定 |
|---|---|---|---|---|
| `chat:mem:__ids__` | `RedisChatMemoryRepository.java:108`（SMEMBERS）、`:138`（SISMEMBER） | `:166`（SADD，随 `saveAll`）、`:123`/`:171`（SREM，含删除脚本） | **全局单集合 ⇒ 只能落一个 slot**（热点 + 大 key）；与 `chat:mem:<cid>` **必然分家** ⇒ 任何"数据键 + 索引"的原子动作都要跨槽（R1-04 的根因） | 【语义变化】 |
| `ai:runs` | `RunStore.java:697`（SMEMBERS） | `:408`（SADD）、`:636`/`:712`（SREM） | 同型 | 【安全】（无原子性依赖，仅热点） |
| `ai:lease:epoch` | `LeaseService.java:302`（脚本内 INCR） | 同 | **全局单键 + 无 TTL + 每次抢租都 INCR** ⇒ Cluster 上唯一"所有节点汇聚到同一个 slot"的写热点；且结构性决定了它不可能与 N 个 runId 的租约同槽（S2 已论证） | 【语义变化】（R1-01 的结构因） |
| `ai:cfg:ver` / `kb:cache:manifest:version` / `kb:cache:refresh:lock` / `kb:cache:manifest` | `AiConfigService.java:215`；`KbCache.java:version/bumpVersion/tryLock` | `AiConfigWriter.java:586`；`RedisKbCacheStore.java:79/90` | 全局单键，低频 | 【安全】 |

**关于 `chat:mem:__ids__` 的 Cluster 特有放大**（本面补充，S2 未展开）：单机下"DEL 成功、SREM 失败"的结果是**一条 stale 索引成员**（可被 `findConversationIds` 的惰性清理兜住）。Cluster 下反向窗口更毒：**SREM 先成功、DEL 后失败**（两键在两次往返/两个节点上）⇒ 索引里没有该会话、数据键还在 ⇒ ①`GET /api/ai/history` 直接看不到它（连惰性清理的机会都没有）；②后续 `saveAll` 的 `SADD` 会把它重新拉回索引，而旧数据键仍活着 ⇒ **旧历史与新一轮追加混在一起**（`saveAll` 是 DEL+RPUSH 覆盖写，但 DEL 是先执行的，若这个 DEL 也失败就真的混写）。修法方向：索引分片（`chat:mem:ids:{<shard>}`）或"删除退化为非原子 + 定期对账"，**不要**简单给两键打同一个全局 tag（会把所有会话压进一槽）。

### 8.4 版本差集（5.0.14 vs 门禁 7.2.16）——独立于 Cluster 的第二个风险面

| 项 | 5.0.14 | 说明 | 判定 |
|---|---|---|---|
| `XRANGE ... (id` 排他区间 | ❌ 不支持（6.2+） | §8.1 | **【硬错误】** |
| `redis.call('TIME')` in Lua | ⚠️ 理论可用（5.0 起按效果复制） | CI 无法证明 | 【语义变化】需实测 |
| Lettuce 6.6 连接握手（`CLIENT SETINFO` 等） | ⚠️ 5.0 不认识该命令 | Lettuce 侧有容错（`RedisHandshake`），需确认不刷 ERROR / 不反复重连 | 【语义变化】需实测（日志面） |
| `XADD MAXLEN ~` / `XREVRANGE` / `SET PX` / `HSETNX` / `HGETALL` / `ZADD` / `ZREMRANGEBYSCORE` / `PEXPIRE` / `LTRIM` / `SISMEMBER` / `INCR` | ✅ 均 5.0 可用 | 逐条核过命令引入版本 | 【安全】 |
| Cluster 协议（MOVED/ASK/CLUSTER SLOTS） | ✅ 3.0+ | Lettuce 自动重定向（事实 D） | 【安全】 |

门禁事实：仓库内所有 Redis 镜像都是 **`redis:7.2.16`**（`ci.yml:206`、`e2e-real.yml:38/184`、`nightly.yml:154`）⇒ **5.0 差集零覆盖**。这不是"少一条用例"，而是"测试拓扑与生产拓扑不同构"（S2 对 Cluster 形态已下同样结论）。

---

## 九、测试面：Cluster / 5.0.14 形态验证时必须改造的（登记即可）

| # | 替身 / 用例 | 锁定了什么 | 为什么在新形态下失真 | 改造方向 |
|---|---|---|---|---|
| 1 | `test/.../ai/run/FakeLeaseRedis.java:101`（`epochCounter`）、`:379-393`（`acquire` 两键）、`:420-437`（`fenceSet` 两键）、`:270-282`（`delete(anyList)` 平铺循环） | 把 Lua 的两键当**同一张字符串表**里的两个 key | 单库命名空间 ⇒ **天然无法表达"不同 slot"**；CROSSSLOT 这类缺陷在替身上永远绿 | 替身加 `slot(key)` 维度：多键脚本/多键命令跨 slot 时抛 `CROSSSLOT` |
| 2 | `test/.../ai/run/FakeFrameRedis.java:136-158`（XADD 解析）、`:235-260`（`selectRange` 在 Java 侧实现 `isInclusive`） | 用 Java 模型实现 Range 语义 | **永远接受 `(id`** ⇒ §8.1 的 5.0 语法拒绝测不出 | 加"目标版本"开关：`redisVersion<6.2` 时对 `(` 前缀抛错（可复现该缺陷） |
| 3 | `test/.../ai/gate/FakeRateRedis.java` | 单键脚本语义 | Cluster 维度无影响 | 仅需保证 tag 改造时脚本常量同步 |
| 4 | `test/.../ai/memory/RedisChatMemoryRepositoryDeleteTest.java:53`、`SaveAllEmptyTest.java:64` | `assertEquals(List.of(DATA_KEY, IDS_KEY), keysCaptor.getValue())`（**逐字锁键名**） | hash tag 改造后键名必变 ⇒ 必红 | 改断言为"两键 slot 相同"（`SlotHash`/CRC16 自算），键名不再逐字锁 |
| 5 | `test/.../ai/run/RunStoreFencedWriteTest.java:151-166`、`RunStoreIdempotencyIndexTest.java:213-235` | `deleteRun` 一次清理 4~12 族键、"不碰 `ai:lock:*`" | 建立在"一次 DEL 全删"上；Cluster 下是 N 次拆槽删 | 补"逐键删除也满足语义"的断言（即断言**结果**而非"一次调用"） |
| 6 | `test/.../ai/run/RunStoreSlidingTtlTest.java:120`（`times(5)`） | 心跳面恰好 5 次 `EXPIRE` | 锁定了"顺序发、首错即停"这一实现形态（§7-2 B2 的修法会改变它） | 改成断言"5 个键都续到了"（不锁调用次数/顺序） |
| 7 | `test/.../ai/run/SessionKeyRegistryTest.java:23-54` | run 级 12 条、HEARTBEAT 5 / READ 4、session 级 5 | tag 改造会改键名（条数不变） | 键名断言改为"前缀 + tag 形态" |
| 8 | `test/.../ai/web/AiControllerHistoryDeleteTest.java:269-294` | `/history` 不调 `deleteRun` | 语义断言，与 Cluster 无关 | 不用改（登记以免误改） |
| 9 | `test/.../ai/conformance/InMemoryRunStore.java:337`（`deleteRun`） | conformance 替身"删表即全删" | 无槽概念 | 与 #1 同法加 slot 维度 |

**结论**：现有 1000+ 例里**没有一例**能在 Cluster 或 5.0.14 上失败——不是因为代码兼容，而是因为**所有替身都是"单库命名空间"**。最低成本的回归网是：给 `FakeLeaseRedis` / `FakeFrameRedis` 加"slot + 目标版本"两个开关（约 2 个测试工具类的改动），再补一个真集群冒烟 job（**镜像必须是 5.0.14**，S2 提的 7.2 版冒烟只能测 CROSSSLOT，测不出 §8.1）。

---

## 十、改造清单（按修复批次分组）

### 批次 0 · 先行（不改代码，1 天量级）
起一套 **5.0.14** 的 3 主 3 从，跑 6 条冒烟：①`XADD` + `XRANGE (id`（预期红，验证 §8.1）②`XRANGE [id`（预期绿）③`PUBLISH/SUBSCRIBE` 跨节点 ④`DEL` 12 键 ⑤`ping()` 单节点 kill 下的 `available` 取值（验证事实 F）⑥`TIME`-in-Lua。
**理由**：本审全部结论都是"代码 + jar 字节码 + 官方版本差集"推出的，没有一条被真集群验证过；先有这张网，后面每批才有回归判据。

### 批次 1 · Cluster 硬阻断（**必须同一笔**）
| 项 | 改动 | 为什么必须同批 |
|---|---|---|
| R1-01 `ACQUIRE`（S2） | 取号外置为单键 `INCR` + 单键 Lua 比对，或 `{lease}` tag | 单独修好会让 `epoch>0` **第一次真正成立**，从而激活 R1-02 的硬失败路径（见下） |
| R1-02 `FENCED_SET`（S2） | `{run:<runId>}` hash tag | 与 R1-01 单独上线 = "抢租成功 ⇒ 每次快照写 500"，比现状（静默无 fencing）更糟 |
| R1-03 `IDEM_PUSH`（S2） | 同 `{run:<runId>}` tag | 与 R1-02 共用同一 tag 才不引入第二套键名 |
| R1-05 `deleteRun` 12 键 | 随 `{run}` tag（同槽 ⇒ 恢复单命令原子） | 与 R1-02/03 共用 tag，否则要再改一次键名 |
| **禁止项** | 单键脚本（RENEW/VERIFY/RELEASE/SLIDING_WINDOW/COMPARE_DELETE）与 `ai:rate:*`/`kb:cache:*`/`ai:cfg:ver`/`ai:session:*` **一律不加 tag** | 加了只牺牲分布，且 `ai:rate:*` 会把限流热度压进单槽 |

### 批次 2 · 版本面（5.0.14 专属，**必须与批次 1 同批或更早**）
| 项 | 改动 | 为什么同批 |
|---|---|---|
| §8.1 `XRANGE` 排他区间 | `from + 1` 闭区间（或读侧过滤） | Cluster 修好、帧仍补不回来 = 多实例扇出等于没做；且它与 Cluster 的唤醒路径是**同一段代码**（`onWake` / `stageReplay`），分开改必然互相打回 |

### 批次 3 · Cluster 语义（可独立成笔）
| 项 | 改动 | 同批理由 |
|---|---|---|
| R1-04 `chat:mem` 索引 | 索引分片（`chat:mem:ids:{shard}`）或"删除退化非原子 + 对账" | — |
| §7-2 B1 `ResilientChatService` 两处 `clear()+add()` | `clear` 单独 try，不阻断 `add`；或改"先写后删余量" | **必须与 R1-04 同批**：R1-04 修好后 `clear` 不再抛 ⇒ 会掩盖"写回仍可能静默跳过"的新形态；同批才能一次性验证"每轮记忆确实落库" |
| §8.3 `ai:runs` / `chat:mem:__ids__` 分桶 | 视规模而定 | 与上一条共用键名决策 |
| §7-2 B2 `RunStore.renew` 首错即停 | 逐键 try + 失败计数 | 独立小修（与 tag 改造无耦合） |
| §7-2 B3 附件登记 fail-closed | 登记失败即告知上传失败 | 独立 |

### 批次 4 · 可观测与门禁（可与 3 并行）
| 项 | 改动 |
|---|---|
| Redis 降级计数器 | 31 处 catch 统一打到"降级事件"计数/结构化日志（先做 A1/A3/A4/A5/A6 这 5 处） |
| A1 `fenceEpoch` | 区分"键不存在=0"与"读失败=拒绝认领" |
| 替身加 slot/版本维度 | `FakeLeaseRedis` + `FakeFrameRedis` 两个工具类 |
| CI 守卫 | grep 级禁止 `database:` / `SELECT` / `KEYS` / `FLUSHDB` 进入 `backend/src/main` |

### 不需要改（正面清单，防止"过度改造"）
单键命令面（~90 个调用点）· 零 KEYS/SCAN · 零 MULTI/管道/WATCH · 零 SELECT/多库 · 零 keyspace notification · PUBLISH 广播语义 · `EVALSHA→EVAL` 回退（无需逐节点 SCRIPT LOAD）· MOVED/ASK 自动重定向 · 前缀式域隔离（`ai:*`/`chat:*`/`kb:*`/`job:*`）· `SessionGate` 的诚实降级范式。

---

## 十一、覆盖面交代

**已扫（穷尽）**
- `backend/src/main/java` 全量 grep：`StringRedisTemplate`/`RedisTemplate`/`RedisConnection`/`RedisCallback`/`SessionCallback`/`opsFor*`/`execute|delete|hasKey|expire|getExpire|convertAndSend|scan|keys|randomKey|flushDb|flushAll|multi|exec|watch|executePipelined|setDatabase|select`；命中全部归入 19 个持模板类 + 4 个间接使用者（`KbCache`、`KbCacheStore`、`ToolResourceLocks`、`SessionKeyRegistry`）。逐文件读取：`RunStore`(1859)、`ResumeService`(852，确认零直连 Redis)、`LeaseService`(650)、`RedisFrameStore`(515)、`CancellationRegistry`、`SessionGate`、`AiSessionService`、`RedisFormAttachmentStore`、`RedisKbCacheStore`、`AiRateLimiter`、`AiModelCatalog`、`RedisAvailability`、`RedisFrameWakeBus`、`RedisChatMemoryRepository`、`JobCancellationRegistry`、`AiConfigService/Writer`（Redis 段）、`KbCache`、`SessionKeyRegistry`。
- 依赖调用面：`spring-data-redis 3.5.11` / `lettuce-core 6.6.0.RELEASE` 的 `LettuceConnection.execute`、`LettuceClusterConnection`（含 `ping`、是否有 `execute` 重写）、`LettuceClusterKeyCommands`、`ClusterDistributionChannelWriter`（路由 + MOVED/ASK）、`RedisAdvancedClusterAsyncCommandsImpl`（多键 `del`/`exists`/`mget` 拆分）、`DefaultScriptExecutor`（NOSCRIPT 回退）、`RangeConverter`（排他边界）、`RedisMessageListenerContainer`、`ClusterCommandExecutor$MultiNodeResult`。
- 资源/门禁：`backend/src/main/resources/application.yml:78-84`（无 `database:`）、`**/*.lua`（零外置脚本）、`.github/workflows/{ci,e2e-real,nightly}.yml`（镜像 7.2.16，三处）。
- 测试：`FakeLeaseRedis`、`FakeFrameRedis`、`FakeRateRedis`、`InMemoryRunStore`、`RunStoreFencedWriteTest`、`RunStoreIdempotencyIndexTest`、`RunStoreSlidingTtlTest`、`SessionKeyRegistryTest`、`RedisChatMemoryRepositoryDeleteTest`、`SaveAllEmptyTest`、`AiControllerHistoryDeleteTest` 的断言面。

**未扫 / 未做**
- e2e（`frontend/e2e/**`）与 `scripts/**`：按任务约定跳过（本地单机面，S2 已记 KEYS/FLUSHDB/SELECT 需重做）。
- `spike/**`：历史实验模块，非生产路径（S2 同口径）。
- **未起真 Cluster、未跑 5.0.14 实例、未跑任何构建/测试**：§8.1 的"5.0.14 报错"依据 Redis 官方版本说明与 spring-data/Lettuce 的排他边界转换（字节码级），未做端到端复现；事实 F 的"单节点 kill 不触发 503"由字节码推断（`resultsAsList` 不抛、只聚合成功值），**都建议在批次 0 冒烟里确认**。
- 未审部署侧（`cluster.nodes`、DB0、代理/TLS、`maxmemory-policy`），按任务范围排除。
