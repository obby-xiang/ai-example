# 05 — ai/kb：Redis Cluster、并发刷新与接口性能审查

## 0. 审查口径与结论边界

- 基线：`52b09364`；审查时工作树未显示改动。
- 范围：`backend/src/main/java/com/example/configmgr/ai/kb/` 下全部 28 个生产 Java 文件，以及与 KB 直接相关的生产配置、属性校验、工具执行和请求上下文代码。
- 方法：只读静态审查。未读取测试实现，未运行测试、构建、CI、服务或压测，未执行任何 Git 写操作，未修改生产/测试代码。
- 严重度：**高**表示可造成错误语料发布、跨实例索引破坏，或同步接口很容易超过 30 秒；**中**表示明确的资源放大、长尾或降级重复工作；**低**表示诊断、运维或罕见损坏场景的收敛问题。
- 确定性：“代码证实”表示控制流和数据流可从代码直接推出；涉及具体耗时、共享目录、源响应顺序或 Redis 损坏的部分，只证明机制存在，不宣称已在运行环境复现。

### 总体结论

1. **未发现 Redis Cluster 的跨槽 Lua 错误。** 所有脚本均只有一个 `KEYS[1]`，锁释放、正文写入、索引写入和代次删除均为单键操作；也没有 `KEYS` 或 `SCAN`。
2. **发现 KB 自身同步重载超过 30 秒的明确机制。** `/api/ai/kb/reload` 在容器请求线程内依次完成 manifest、逐篇正文、逐篇 Redis、切块和全量 Lucene 建索引；只有单次 HTTP/Redis 操作超时，没有整次刷新总预算。文档数没有上限，因此总耗时随文档数线性增长，并会串住同实例其他 reload 和两条定时任务。
3. **HTTP manifest 缺少可选 `sha` 时，缓存与增量语义实际失效。** 冷启动每篇正文可回源两次；后续即使 manifest 返回 304，也会逐篇回源。这是代码可证的高优先级性能问题。
4. **30 秒刷新锁不续租，且 local 源完全不用该锁。** 锁过期后并发发布仍靠 generation 防止“低代次覆盖高代次”，但 generation 只代表取号先后，不代表源数据新旧；迟到的旧源快照仍可取得更高代次并覆盖新快照。
5. **manifest 与通知 version 不是一个原子发布单元。** 并发刷新可产生重复的内部版本号和两个 `INCR`，而 `fromCache` 重试后即使版本仍不一致也会继续返回旧清单。
6. **如果多个实例共享 `index-dir`，一个实例会把其他实例的活跃 Lucene `build-*` 目录当成 stale 删除。** 默认相对目录通常是实例本地路径，因此该项依赖部署方式，但一旦共享，影响为高。
7. **本模块没有删除对话入口，也没有会话删除调用。** 因而不能用本模块直接解释“删除对话超过 30 秒”。KB 的大 Lua/大 HASH 操作可能间接增加同 Redis 节点排队，但这只是需实测验证的假设，不是已证实的删除慢根因。

## 1. 实际阅读文件

### 1.1 ai/kb 全部生产代码

- 核心：`CachedKbSource.java`、`KbCache.java`、`KbCacheStore.java`、`RedisKbCacheStore.java`、`KbService.java`、`KbConfig.java`。
- 数据源：`HttpKbSource.java`、`LocalDirKbSource.java`、`KbSource.java`、`KbSourceException.java`、`KbCacheException.java`。
- 控制器与工具：`AiKbController.java`、`KbTools.java`、`KbPromptSection.java`。
- 文档处理：`KbManifest.java`、`KbDocMeta.java`、`KbDoc.java`、`KbChunk.java`、`KbSection.java`、`KbTerm.java`、`MarkdownChunker.java`、`KbHashing.java`、`KbText.java`。
- 结果与状态：`KbSearchResult.java`、`KbSearchHit.java`、`KbReadResult.java`、`KbLookupResult.java`、`KbStatus.java`。
- 启动播种：`KbSampleSeeder.java`。

### 1.2 直接相关生产配置与跨模块入口

- `backend/src/main/resources/application.yml`：Redis 2 秒连接/命令超时、虚拟线程关闭、KB 全部配置和工具 120 秒执行超时。
- `backend/src/main/resources/application-cluster.yml`：Cluster 节点、最多 3 次重定向、同样的 2 秒超时。
- `backend/src/main/java/com/example/configmgr/ai/config/AiProperties.java:583-653,1470-1565`：KB 配置默认值和启动校验。
- `backend/pom.xml:20-35,94-137,182-206`：Java/Spring AI、Redis、Lucene 9.12.2、TTL 和样例资源装配。
- `backend/src/main/java/com/example/configmgr/ai/run/ResilientChatService.java:4975-5002`：每轮 system prompt 调用 KB 目录渲染。
- `backend/src/main/java/com/example/configmgr/ai/gate/SpToolCallingManager.java:221-340,785-855,2991-3024,3230-3246`：工具注册后的实际执行、120 秒超时池、身份/MDC 恢复。
- `backend/src/main/java/com/example/configmgr/ai/tool/ToolRegistry.java` 中回调注册/获取相关段。
- `backend/src/main/java/com/example/configmgr/ai/exec/AiExecutorConfig.java`、`RequestContextTaskDecorator.java`、`AiMdcTaskDecorator.java`：线程池及上下文传播基座。

其他生产 profile 未发现 KB 覆盖项；`cluster` 只改变 Redis 拓扑和通用健康配置，不改变 KB 键名。

## 2. 配置、线程与存储事实

| 项目 | 代码事实 | 审查含义 |
|---|---|---|
| 默认源 | `local`，目录 `./data/kb` | 每个实例会读盘并参与共享缓存同步 |
| HTTP 超时 | 连接 5 秒、单请求 10 秒 | 不是整次刷新超时；N 篇文档可累计约 N 个读超时 |
| Redis 超时 | standalone/Cluster 均 2 秒 | 是单命令客户端预算，不是整次刷新预算，也不会终止服务端 Lua |
| 刷新 | 10 分钟 fixed-delay | 与首启刷新、版本轮询共用一条 `kb-refresh` 线程 |
| 版本轮询 | 30 秒 fixed-delay | 正常每实例每拍一次 GET；慢刷新会推迟轮询 |
| 刷新锁 | `SET NX PX`，30 秒，不续租 | 超过 TTL 后第二个实例可进入回源和发布 |
| 工具执行 | 独立平台线程池，`Future.get(120s)`，超时中断 | KB 工具本身只读本地快照；CPU/不可中断 I/O 仍可能在后台继续 |
| Lucene | 每实例内存快照 + `FSDirectory` | 检索不经过 Redis；重建是本地 CPU 和文件 I/O |
| DB | ai/kb 无 Repository、JPA、JDBC 调用 | KB 调用链没有数据库等待或数据库事务 |

配置校验覆盖了 refresh 间隔、锁 TTL、轮询间隔正数以及检索/分页参数，但没有文档数、单文档字节数、manifest 字节数、总语料字节数或整次刷新时长上限（`AiProperties.java:598-653`）。

## 3. 完整入口 → 服务 → Redis/文件/网络/线程 → 返回链路

### 3.1 装配与启动

```text
KbConfig.kbSource
  ├─ local → LocalDirKbSource
  └─ http  → HttpKbSource（同步 HttpClient）
       ↓ cache.enabled 且存在 StringRedisTemplate
CachedKbSource(delegate, KbCache(RedisKbCacheStore))
       ↓
KbService.start @PostConstruct
  ├─ source.prepare：local 同步执行 KbSampleSeeder（文件锁、复制、rename）
  ├─ 创建单线程 ScheduledExecutorService：kb-refresh
  ├─ submit(refreshQuietly)                 首次建索引
  ├─ scheduleWithFixedDelay(refreshQuietly) 10m
  └─ scheduleWithFixedDelay(pollVersionQuietly) 30s，仅 CachedKbSource
```

`prepare()` 在 `@PostConstruct` 调用线程同步执行，首次索引才异步；样例很多或文件系统很慢时，启动本身也会被播种 I/O 拖住（`KbService.java:165-185`，`KbSampleSeeder.java:87-125,137-169`）。

### 3.2 管理控制器

```text
GET /api/ai/kb/status
  → AiKbController.status → KbService.status → 读 volatile 快照/状态 → ApiResponse

GET /api/ai/kb/docs
  → AiKbController.docs → KbService.catalog → 当前 manifest → 截断 sha 前缀 → ApiResponse

POST /api/ai/kb/reload
  → AiKbController.reload（容器请求线程）
  → KbService.refreshNow(true)，synchronized
  → CachedKbSource.fetchManifest(lastEtag, true)
  → local syncLocal / http refreshHttp 或 coldStartHttp
  → KbService 逐篇复用或 source.fetchContent
  → MarkdownChunker + 新 build-* 目录全量 Lucene writer/commit/reader
  → volatile snapshot 切换，旧快照按引用计数关闭并删目录
  → status + rebuilt → ApiResponse.ok
```

reload 返回的 `rebuilt=false` 同时涵盖“未变化”和“失败”，具体原因在 `last_error`；HTTP 响应仍是成功信封（`AiKbController.java:43-49`，`KbService.java:282-387`）。

### 3.3 local 刷新

```text
LocalDirKbSource.fetchManifest
  → 读完整 manifest.json
  → 对每篇 Files.readAllBytes + SHA-256 + 文件时间
  → 返回实算 sha 的 manifest
CachedKbSource.syncLocal
  → syncToCache
     ├─ INCR generation
     ├─ HGETALL manifest + HGETALL docs 索引
     ├─ 单键 Lua 批量登记全部 known id
     ├─ 每篇 HGETALL doc（含完整 content）
     │    ├─ sha 变/缺正文：Files.readString → 索引 Lua → 正文 Lua
     │    └─ 未变：单键 Lua 提升 generation
     ├─ GET version → 条件 HSET manifest → INCR version
     └─ 对下线 id：正文删除 Lua → 索引删除 Lua
  → fromCache：GET version + 1..6 次 HGETALL manifest
KbService
  → sha 相同的旧 KbDoc 直接复用；变化篇从 Redis 取 content
  → 任一有效变化都全量重建本地 Lucene
```

local 路径不抢 `kb:cache:refresh:lock`，所以 M 个实例默认每 10 分钟各自执行上述流程。

### 3.4 HTTP 刷新、单飞和降级

```text
CachedKbSource.refreshHttp
  → HGETALL manifest
  ├─ 空 → coldStartHttp
  │    ├─ SET NX PX lock
  │    ├─ 成功：GET manifest.json → syncToCache（逐篇 GET 正文）→ fromCache → unlock Lua
  │    └─ 失败：30 × (HGETALL manifest + sleep 100ms)，仍无则抛错
  └─ 非空 → GET version
       ├─ version != lastSeenVersion → fromCache，不回源
       ├─ 未到 jittered interval 且非 force → fromCache
       ├─ SET NX PX 失败 → fromCache
       └─ 获锁 → GET manifest.json（If-None-Match，force 也带 ETag）
            ├─ 5xx/超时 → fromCache 旧料（stale-if-error）
            ├─ 304 → reconcileCachedRemoval → fromCache
            └─ 200 → syncToCache → fromCache
       → finally：token 比对删除锁
```

外层只捕获 `KbCacheException` 后直连 delegate；源错误由 `KbService` 保留旧索引。注意“Redis 不可用”不是一个持久熔断：`cacheDownLogged` 只限制 WARN，下一拍仍会再次访问 Redis（`CachedKbSource.java:96-105,476-480`）。

### 3.5 lazy 补写与回收

`KbService` 需要某篇正文时，`CachedKbSource.fetchContent` 先 `HGETALL kb:cache:doc:<id>`；sha 匹配则返回，否则回源并执行 `INCR generation → 索引写入 Lua → 正文写入 Lua`（`CachedKbSource.java:108-123,241-244`）。这保证正常路径先留索引再写无 TTL 正文。

下线回收按全局索引与当前 manifest 的差集逐篇执行两个单键 Lua；删除正文成功后才摘索引，代次更高则让位。304 也会补做回收（`CachedKbSource.java:339-422`）。该设计能重试普通删除失败，但依赖索引键本身不丢失。

### 3.6 工具、prompt 与返回

```text
AI 工具循环 → SpToolCallingManager.executeWithTimeout
  → ai-tool-timeout 平台线程（恢复身份 + MDC）
  → KbTools.kbSearch / kbReadDoc / kbLookupTerm
  → KbService.search / readDoc / lookup
  → acquire 当前 Snapshot（引用计数）
  → Lucene 查询/读取 stored fields，或纯内存分页/术语表
  → release → KbText 限长/定界 → ToolResponseMessage → 模型/工具结果
```

工具稳态不访问 Redis、文件源或 DB；只有 Lucene stored-field 读取涉及本地索引文件。`kb_read_doc` 为适配预算最多进行 4 次缩页重读，仍是内存操作（`KbTools.java:215-251`）。

另一个每轮入口是 `ResilientChatService.systemPromptText → KbPromptSection.render → KbService.catalog`，它遍历全部文档后才把目录截到 4000 字。

## 4. 键、Lua 与 Cluster 逐项核查

| 键 | 类型/TTL | 主要操作 | Cluster 结论 |
|---|---|---|---|
| `kb:cache:doc:<id>` | HASH；无 TTL；`content/etag/sha/updatedAt/generation` | HGETALL、条件 HSET、条件 DEL | 单键，无跨槽；大 content 会造成大响应和大脚本参数 |
| `kb:cache:docs` | HASH；field=id、value=generation；无 TTL | HGETALL、批量条件 HSET、条件 HDEL | 单键，无跨槽；全部 id 集中在一个槽/主节点 |
| `kb:cache:manifest` | HASH；无 TTL；`etag/version/json/generation` | HGETALL、条件 HSET | 单键，无跨槽；大 json 是热点和热点槽 |
| `kb:cache:manifest:version` | String；无 TTL | GET、INCR | 单键；与 manifest 是两次独立命令，不原子 |
| `kb:cache:manifest:generation` | String；无 TTL | GET、INCR | 单键全局取号点 |
| `kb:cache:refresh:lock` | String；默认 30 秒 TTL | SET NX PX、GET+DEL 单键 Lua | 单键且 token 比对正确，无误删新锁 |

`RedisKbCacheStore.java:27-112` 中七个脚本都只传 `List.of(key)`；调用点见 `:156-213,263-305`。因此不需要为这些脚本强行加 hash tag。反之，**把 manifest 和 version 合成一个多键 Lua 前才必须加共同 hash tag**，不能直接合并成现状键名的跨槽脚本。

键名没有应用、环境、租户或源命名空间。这是“全局公共知识域”的显式设计；只有共享同一 Redis 逻辑库的部署实际承载不同语料时，才构成碰撞风险，不能默认认定已经发生。

## 5. 逐项问题

### KB-01（高，代码证实）：同步 reload 没有整次时间预算，逐篇串行工作可轻易超过 30 秒

**证据**

- 控制器同步等待：`AiKbController.java:43-48`。
- `synchronized` 覆盖 manifest、全部正文、Redis、切块和 Lucene：`KbService.java:287-387`。
- 每篇依次读取缓存、必要时同步回源：`CachedKbSource.java:279-293`；HTTP 每次 `client.send`：`HttpKbSource.java:106-110,142-156`。
- 单次 HTTP 读超时 10 秒、Redis 单命令 2 秒，但没有文档数/总时长上限：`application.yml:343-353`，`AiProperties.java:598-653`。

**触发条件与影响**

- N 篇变化文档、慢源、Redis 慢、网络文件系统或大 Lucene 语料。最坏总时长含 manifest 请求、N 个正文请求、O(N) Redis 操作和全量索引提交；N 无配置上限，所以不存在 30 秒总上界。
- 同实例并发 reload 在 monitor 上排队；排队时间也不受 KB 预算限制。单条 `kb-refresh` 同时被占住，定时刷新和版本轮询都延后。
- standalone 与 Cluster 都有该问题；Cluster 只改变每条 Redis 命令路由，不把串行调用变并行。多实例会各自承担本地 Lucene 重建，local 源还会各自做缓存同步。
- KB 搜索/读取不抢 refresh monitor，可继续用旧快照；主要受损的是 reload 请求和刷新及时性，不是所有检索都被锁住。

**根因**：把“单次 I/O 超时”误当成“整次刷新超时”，并在请求线程和单调度线程里执行无总量约束的 N+1 工作。

**最佳修复**：reload 只提交有界异步任务并返回任务 ID/202；刷新 worker 使用整体 deadline，将剩余预算传给每个 HTTP/Redis 阶段，限制单轮最大文档数和字节数；超过预算则保留旧快照并明确超时。任务状态应可查询，重复 reload 返回同一个进行中任务。

**备选取舍**：保留同步接口但增加整次 deadline，改动较小，却仍占用 Tomcat 线程且客户端可能先超时；仅调小单次 HTTP 超时会增加慢源失败率，不能解决 N 篇累计。

**修改点/影响面**：`AiKbController`、`KbService`、`CachedKbSource`、`HttpKbSource`、`AiProperties.Kb` 和配置；需要定义 reload API 的异步状态、并发去重、取消与失败语义。

### KB-02（高，代码证实；触发条件为 HTTP 文档未提供 sha）：可选 sha 使缓存和增量回源失效

**证据**

- `sha` 被声明为可空：`KbDocMeta.java:23-25`。
- manifest sha 为空时，只有 cachedSha 非空才命中：`CachedKbSource.java:462-467`。
- 写入缓存的 sha 直接来自 `meta.sha()`，HTTP 没有补算：`CachedKbSource.java:241-244`，`HttpKbSource.java:106-110`。
- `KbService.sameSha` 对空值恒 false：`KbService.java:320-337,911-913`。

**确定的结果**

- 冷启动/正文缺失时，`syncToCache` 先回源并写入空 sha；随后 `KbService.fetchContent` 再次判定 miss，再回源同一篇，形成每篇两次 GET。
- 后续即使远端 manifest 为 304，`CachedKbSource` 仍返回缓存 manifest 的 modified 结果；`KbService` 因空 sha 不复用旧 `KbDoc`，每篇又回源一次。即使最终发现文档未变而不重建 Lucene，网络成本已经发生。
- 多实例各自建索引时会分别产生这些回源；generation 还因 lazy 补写频繁 INCR，放大全局热点。

**根因**：缓存写入口没有建立“空 manifest sha 的可命中表示”，服务层与缓存层对空 sha 的语义也不一致；HTTP 源没有履行“由源实算”的可选 sha 注释口径。

**最佳修复**：对 HTTP 生产源强制每篇提供合法 SHA-256，并在 manifest 校验/启动后的源契约检查中 fail-fast；这是唯一能可靠判断远端正文是否变化的简洁契约。

**备选取错**：首次下载后计算实际 sha，并把“有效 manifest”改写成带实算 sha 后再发布。可兼容旧源，但增加缓存清单改写、版本和迁移复杂度；不能简单把空 sha 当作永远命中，否则远端正文变更会永久不可见。

**修改点/影响面**：`KbDocMeta`/`KbManifest` 校验、`HttpKbSource`、`CachedKbSource.shaMatches/cacheDoc`、`KbService.sameSha`；需同步知识服务发布契约和旧缓存迁移。

### KB-03（高，代码证实的竞态机制）：30 秒锁无续租，generation 不能证明源快照更新

**证据**

- 锁默认 30 秒且明确不续租：`application.yml:343-348`，`AiProperties.java:1533-1540`。
- 获锁后，远端请求、全部正文和缓存发布都在锁内，但没有任何续租：`CachedKbSource.java:157-189,192-217`。
- generation 在远端 manifest 已返回、开始 `syncToCache` 时才 INCR：`CachedKbSource.java:142-179,261-265`。
- Lua 只比较 generation 数值：`RedisKbCacheStore.java:37-76`。

**竞态示例**

1. A 获锁并请求旧快照 v1，因慢源/大文档超过 30 秒，锁过期。
2. B 获锁，较快取得并发布新快照 v2，使用 generation G2。
3. A 的旧 v1 请求随后返回，再取得 generation G3>G2；其正文和 manifest 均允许覆盖 v2。

因此“高 generation 优先”只防低代次写回，不防**较旧源内容较迟取号**。锁 token 比对删除只能避免 A 删除 B 的锁，不能避免上述发布。

**影响**：standalone/Cluster 均有；多实例和超过 30 秒的刷新是必要条件。可能表现为语料回退、不同实例在不同轮询拍构建不同清单、额外全量重建。具体是否发生取决于源响应时序，未宣称运行环境已复现。

**最佳修复**：使用可续租的分布式 lease，在每次发布前验证 owner/token；同时将源侧不可变版本/ETag 纳入发布 CAS，只有源版本符合预期才提交。长任务必须由独立续租线程或具备明确所有权的 scheduler 续租，续租失败则禁止发布。

**备选取错**：只把 TTL 调大只能降低概率，不能消除竞态；只做 generation fencing 也不足够。若知识服务能提供单调递增且不可变的 corpus version，可省略显式锁并以源版本 CAS 决定胜负，但要求源契约支持。

**修改点/影响面**：`CachedKbSource`、`KbCache`、`RedisKbCacheStore` 新续租/校验脚本、`AiProperties.Kb.Cache`；涉及锁 TTL、续租间隔、停机释放和旧锁兼容。

### KB-04（高，代码证实清理逻辑；共享目录为部署条件）：共享 index-dir 会跨实例删除活跃 Lucene 目录

**证据**

- 新目录仅由 `build- + System.nanoTime()` 命名：`KbService.java:389-394`。
- 每次构建前列出 index-dir，删除除**本实例当前 live**外的全部 `build-*`：`KbService.java:396-407`。
- 没有实例 ID、owner 文件、跨进程锁或“其他实例活跃”判定；Cluster profile 也没有 KB index-dir 隔离配置。

**触发条件与影响**

- 多实例把 `app.ai.kb.index-dir` 指向同一共享卷目录。A 刷新时会删除 B 正在使用或正在构建的目录；B 的后续 stored-field 查询、reader 关闭或构建可能失败。
- 默认相对路径在独立工作目录/容器文件系统下通常不共享，此时不触发。不能仅凭默认配置断言生产已经共享。
- 与 Redis standalone/Cluster 无关；是本地/共享文件系统生命周期问题。

**最佳修复**：明确 Lucene index-dir 必须实例本地，并按稳定 instance ID 建独立子目录；构建目录使用 UUID，清理只处理本实例拥有的前缀/登记项。启动文档和多实例配置检查应把 KB index-dir 与业务共享文件存储区分开。

**备选取错**：跨进程目录锁和活跃登记可支持共享卷，但复杂度高，且网络文件系统锁语义不一致；没有必要为本来可实例本地的 Lucene 索引引入共享协调。

**修改点/影响面**：`KbService.newBuildDir/cleanupStaleBuildDirs`、KB 配置和多实例部署说明；需要处理旧 `build-*` 目录的清理方式。

### KB-05（中高，代码证实的并发窗口）：manifest/version 非原子发布，版本不一致重试后仍接受旧清单

**证据**

- `next = version()+1`、条件写 manifest、`INCR version` 是三条独立 Redis 操作：`CachedKbSource.java:301-310`。
- 两个并发发布者都可读到相同 version 并写入相同 next，随后各自 INCR，使全局 version 比 manifest 内部 version 多推进。
- `fromCache` 最多读 6 次并等待约 125ms，但循环结束后不再次拒绝 `entry.version < version`，直接解析并返回：`CachedKbSource.java:424-450`。
- `KbService` 成功/无变化路径随后把 `lastObservedVersion` 设为当前全局 version：`KbService.java:340-360`。

**触发与影响**

- 主要依赖 KB-03 的锁过期并发或 local 多实例并发。可造成重复 manifest 版本、无意义的额外轮询/重建，或在持续不一致时把旧清单当作本次版本结果并标记已观察。
- 正常单写者顺序“manifest 后 INCR”降低了单实例命中概率；因此不是每次刷新都会发生。
- standalone/Cluster 都有；Cluster 不应把两个现状键直接放多键 Lua，否则会产生跨槽错误。

**最佳修复**：把通知 version 作为 manifest HASH 的一个字段，在同一个单键 Lua 中与 JSON/ETag/generation 一起发布；version 可取本次 generation，轮询用 `HMGET manifest version` 而不是 GET 独立键。`fromCache` 在预算耗尽后必须抛出缓存暂时不一致错误，让旧索引保留并重试，不得返回旧 entry 后标记成功。

**备选取错**：给 manifest/version/generation 设计共同 hash tag 后用一个 Lua 原子发布，也能成立，但需要多键脚本和全量键迁移，Cluster 运维影响更大。

**修改点/影响面**：`KbCache` 键契约、`RedisKbCacheStore` 发布脚本、`CachedKbSource.syncToCache/fromCache`、`KbService` 版本观察；需双读/迁移旧 version 键，避免升级期间版本倒退。

### KB-06（中高，代码证实）：每轮 O(N) Redis 往返并传输全部正文，大索引 Lua 会阻塞单个 Redis 节点

**证据**

- 每轮先 `HGETALL` 全部文档索引：`CachedKbSource.java:267-278`，`KbCache.java:138-145`。
- 每篇用 `HGETALL doc` 取回完整 content，却只为比较 sha：`CachedKbSource.java:279-287`，`KbCache.java:89-96`。
- 未变文档仍每篇执行一个条件 Lua；变化文档另有索引和正文 Lua：`CachedKbSource.java:279-293`。
- `PUT_INDEX_FIELDS_IF_GENERATION_AT_MOST` 在一个服务端脚本里对全部 known id 逐个 HGET/HSET：`RedisKbCacheStore.java:52-62,185-200`。
- 回收按每篇两个 Lua 串行执行：`CachedKbSource.java:388-421`。

**影响**

- N 篇、总正文 B 字节的一轮同步约有 O(N) RTT，并可为“未变判断”传输 O(B) 正文。local 多实例默认每实例都执行，成本约随 M×N 和 M×B 增长。
- docs 索引 Lua 是 O(N) Redis 服务端原子工作；standalone 上会阻塞该 Redis 的全部其他命令，Cluster 上主要阻塞 `kb:cache:docs` 所在主节点。其他键是否受影响取决于是否路由到同节点。
- 2 秒 Lettuce 超时会放开客户端等待，但**不会自动停止已经在服务端执行的 Lua**。因此缓存降级后，服务端工作仍可能继续。
- 没有代码证据能断言该脚本必然超过 30 秒；需要文档数、字段大小和 Redis 慢日志实测。

**最佳修复**：sha 比较改 `HMGET sha/generation`，索引差集只需要 id 时用 `HKEYS`；使用有界批次/pipeline 降低 RTT，并缩小索引脚本每批字段数。不要为了避免跨槽而把全部文档键加同一 hash tag，那会把所有正文也集中到一个热点槽。

**备选取错**：用 Redis  pipeline 可减少网络往返但不减少 Lua 服务端 O(N) 工作；把 docs 拆成多个桶可缩短单脚本，却增加全局回收清单和 Cluster 一致性复杂度。

**修改点/影响面**：`KbCacheStore` 增加字段级读取、`KbCache.doc/docIds`、`RedisKbCacheStore`、`CachedKbSource.syncToCache`；需保留代次条件写语义。

### KB-07（中，代码证实）：正文已成功回源但缓存写失败时，会重复回源同一正文

**证据**：`CachedKbSource.fetchContent` 在第 115 行取得 content，第 116 行 `cacheDoc` 抛出 `KbCacheException` 后，catch 在第 121 行再次 `delegate.fetchContent(meta)`，而不是返回已经取得的 content（`CachedKbSource.java:108-123`）。

**触发与影响**：Redis 在正文写索引/写正文阶段超时或失败。HTTP 大文档/慢源会多一次最长 10 秒的请求，local 会多读一次文件；多实例缓存故障时同步放大源压力。standalone/Cluster 相同。

**根因**：一个覆盖“读缓存 + 回源 + 写缓存”的大 catch 没有区分失败发生在回源前还是回源后。

**最佳修复**：缓存读失败后只回源一次；回源成功后，缓存补写失败仅记录降级并返回已有 content。可将缓存补写拆成独立 try/catch。

**备选取错**：重试同一 Redis 写入而不是直接放弃可减少缓存缺失，但会延长刷新，必须受 KB-01 总预算约束。

**修改点/影响面**：只涉及 `CachedKbSource.fetchContent` 及缓存失败语义；不改变键结构。

### KB-08（中高，代码证实资源无界；实际 OOM/长阻塞需规模条件）：manifest/正文全量入内存，超长块切分存在二次复制

**证据**

- HTTP 对 manifest 和正文均使用 `BodyHandlers.ofByteArray()`，没有 Content-Length 或流式上限：`HttpKbSource.java:91-110,142-145`。
- local 对 manifest 和每篇正文 `readAllBytes`，正文之后又 `readString`：`LocalDirKbSource.java:60-83,99-103`。
- Redis 文档读取把完整 content 放入 String，manifest 也完整 HGETALL：`KbCache.java:89-96,206-212`。
- 每个 `KbDoc` 同时保留原文、sections、chunks；每个 chunk 同时保留 body 和 indexText，Lucene 又将 indexText 以 `Field.Store.YES` 存储：`KbDoc.java:12-22`，`KbChunk.java:16-19`，`KbService.java:422-433`。
- `MarkdownChunker.splitLong` 每切约 800 字就执行 `rest = rest.substring(cut)`；对单个超长无换行块会反复复制剩余字符串，形成近似 O(L²) 字符复制：`MarkdownChunker.java:225-247`。
- `sections/pathOf` 对标题反复向前扫描/找结束标题，标题极多时也会出现二次量级 CPU：`MarkdownChunker.java:41-59,421-439`。

**触发与影响**：超大文档、超长单段/表格/围栏块、超大 manifest 或总语料过大。会放大堆占用、GC、Redis 响应字节、Lucene 构建时间和磁盘占用；构建期间旧快照仍存活，新文档 Map 与新 Lucene 目录额外并存。多线程 reload 在同实例串行，但工具可并发读取旧快照，内存峰值不会因 refresh monitor 消失。

**最佳修复**：配置并强制单文档、manifest、总语料和单轮变化字节上限；HTTP 使用有上限的流式 body subscriber，local 在读取前后检查文件大小；Redis 写入前也做防御性限制。`splitLong` 改为基于原字符串起止下标，只复制最终 piece；标题扫描改为单遍栈和结束位置回填。

**备选取错**：只对工具输出限长没有帮助，因为 8000 字闸发生在所有读取/缓存/切块之后；只降低 chunk 大小也不能限制原文和缓存占用。

**修改点/影响面**：`HttpKbSource`、`LocalDirKbSource`、`KbManifest`、`CachedKbSource/KbCache`、`MarkdownChunker`、`KbService`、`AiProperties.Kb`；需定义超限是整份拒绝还是跳过单篇，建议整份拒绝并保留旧索引，避免无声缩料。

### KB-09（中，代码证实）：任何有效 manifest 变化都在每实例全量重建 Lucene，并阻塞唯一刷新线程

**证据**

- 虽然未变 `KbDoc` 会复用，但只要文档集合、元数据或 manifest.version 有变化，就创建新目录并调用 `buildSnapshot`：`KbService.java:318-367`。
- `buildSnapshot` 用 `OpenMode.CREATE` 遍历所有文档所有 chunk，commit 后打开新 reader：`KbService.java:409-440`。
- 首刷、10 分钟刷新和 30 秒版本轮询共用一个 single-thread scheduler：`KbService.java:173-185`。

**触发与影响**：只改一个标题或一篇正文，也会让所有收到版本的实例重建整个 Lucene 索引。语料大时产生全量 CPU、磁盘写和目录清理；reload 等待更久，版本轮询被推迟。旧快照检索仍可用，这是正确的降级面。

**最佳修复**：短期先把刷新移出 reload 请求线程并设置构建预算/速率限制；语料规模需要更高时效时，改为复用 Lucene index、按 doc_id 更新/删除文档，再原子 reopen reader。长期方案必须重新设计快照目录清理和失败回滚。

**备选取错**：全量重建实现简单、回滚清晰，对小语料合理；如果明确语料始终很小，可保留全量重建，只增加规模上限和异步化，而不是过早引入增量索引复杂度。

**修改点/影响面**：`KbService.buildSnapshot/newBuildDir/Snapshot`；若改增量索引，还涉及术语表、引用计数、reader reopen、崩溃恢复和索引兼容性。

### KB-10（中高，代码证实；源不一致或非不可变发布为触发条件）：local 无单飞，正文 sha 不符只告警，共享缓存可发布混合/回退语料

**证据**

- `syncLocal` 直接 `delegate.fetchManifest → syncToCache`，不经过 `tryLock`：`CachedKbSource.java:131-138`。
- 类注释明确允许各实例挂载不同盘并共享同一缓存：`CachedKbSource.java:21-29`。
- 正文实际 sha 与 manifest 声明不符时只 WARN，仍继续切块和入索引：`KbService.java:328-337`。
- local manifest 扫描和随后正文读取不是一个文件系统快照：`LocalDirKbSource.java:55-96,99-106`。

**触发与影响**

- 多实例 local 盘内容不同，或发布过程中 manifest 与正文非原子切换。各实例可轮流写同一全局缓存，最后高 generation 者获胜，实例间随轮询重建不同版本；源内容回退与 KB-03 同类。
- 即使单实例，manifest 读取后正文被替换也可能把新正文装入旧元数据/旧 sha；下一次扫描通常能修复，但当前索引已可暴露不一致内容。
- Redis Cluster 不会改变结果；无 TTL 缓存还会让错误内容保留到下一轮成功修复。

**最佳修复**：local 生产语料采用不可变、内容寻址的发布包，manifest 与正文作为同一快照整体切换；缓存发布前对实际 sha 不符 fail-fast，保留旧快照。多实例若使用 local 源，应选择单发布者写 Redis，其他实例只读缓存，或直接改用具备不可变 corpus version 的 HTTP 源。

**备选取错**：允许“sha 不符仍服务”有利于容错，但会牺牲知识内容正确性；对制度/条款语料不宜继续仅 WARN。给 local 也加同一把锁只能减少同时写入，不能证明各盘内容一致。

**修改点/影响面**：`CachedKbSource.syncLocal`、`KbService` sha 校验、local 发布流程、`KbCache` 源版本 fencing；涉及运维发布契约。

### KB-11（中，代码证实）：每轮 prompt 和未命中建议做全量遍历/排序，输出有界但计算无界

**证据**

- 每轮 system prompt 都调用 `KbPromptSection.render`：`ResilientChatService.java:4986-5001`。
- render 遍历全部 docs、构造完整 catalog 字符串，最后才截断 4000 字：`KbPromptSection.java:73-106`。
- 搜索零命中排序全部标题：`KbService.java:620-629`；术语 miss 复制并排序全部术语：`KbService.java:802-807`；错误 doc/section 也分别排序全部候选：`KbService.java:775-785`。

**触发与影响**：文档/术语数量大、频繁新轮次、大量零命中或错误 ID。消耗 AI 请求/工具线程 CPU 和临时内存；多实例各自承担，与 Redis 拓扑无关。并发工具越多，越容易挤占有界工具池。

**最佳修复**：把已渲染 KB prompt 后缀随 Snapshot 缓存，仅刷新时重算；Top-3 建议使用固定大小堆/单次选择，避免完整排序，或随快照预计算用于建议的归一化特征。

**备选取错**：直接限制 manifest 文档数可同时收敛所有 O(N) 路径，但属于语料容量约束，不能替代明显的每轮重复渲染浪费。

**修改点/影响面**：`KbPromptSection`、`KbService.Snapshot` 和建议算法；保持 prompt 文本字节不变，以免破坏模型缓存前缀。

### KB-12（中低，代码证实）：中断后的本地等待不退出，停机也未等待刷新任务收口

**证据**

- `sleep` 捕获中断后只恢复标志，不通知调用循环：`CachedKbSource.java:483-489`。
- cold start 的 30 次循环和 fromCache 的 6 次循环随后仍继续；一旦中断标志已设置，后续 `Thread.sleep` 会立即抛出，循环可能变成连续 Redis 读取而不是正常等待：`CachedKbSource.java:219-228,424-437`。
- `stop` 直接 `shutdownNow`，没有 await；`refreshNow` 构建完成前不检查 stopped，仍可能发布 snapshot：`KbService.java:191-209,296-367`。

**触发与影响**：应用停机或线程被中断时，等待中的 cold start/fromCache 可能继续打 Redis；不响应中断的 Lucene/文件工作可继续到结束，甚至在 stop 后留下新快照/目录。正常请求路径不受影响。

**最佳修复**：让 sleep 返回 false 或抛出中断异常并立即结束当前刷新；stop 设置 stopped 后有界等待 scheduler，发布 snapshot 前再次检查 stopped，若已停止则关闭并清理 built。

**备选取错**：完全不等待可缩短 destroy，但把后台 I/O 和文件清理留给进程退出，滚动发布时更难证明资源收口。

**修改点/影响面**：`CachedKbSource.sleep` 两个调用循环、`KbService.stop/refreshNow`；需保持正常失败仍保留旧索引。

### KB-13（低，代码证实机制；索引键丢失为外部条件）：无 TTL 正文的回收完全依赖索引，索引丢失会留下不可发现的孤儿

**证据**：正文无 TTL（`RedisKbCacheStore.java:133-149`），回收只遍历 docs 索引与 previous manifest 的有限并集（`CachedKbSource.java:267-278,318,388-421`），代码没有 SCAN 或其他全量发现通道。

**触发与影响**：`kb:cache:docs` 被误删、逐出或恢复到旧备份，而部分正文键仍存在；已不在当前/previous manifest 的孤儿正文会失去删除线索，长期占内存。正常单篇删除失败不会触发该问题，现有“保留索引、下轮重试”对此是有效的。

**最佳修复**：把“索引整体丢失”纳入运维恢复：从可信 manifest/备份重建索引，并提供低频、分批、维护态的 Cluster 每主节点 SCAN 对账工具；不要把它放到请求线程或每 30 秒轮询里。

**备选取错**：给正文加很长安全 TTL 可最终回收孤儿，但会削弱远端长期故障时的 stale-if-error，并非等价替代。

**修改点/影响面**：缓存运维工具、备份/恢复流程；正常热路径无需改变。

### KB-14（低，配置条件性风险）：全局固定键前缀没有环境/语料命名空间

**证据**：全部键固定为 `kb:cache:*`（`KbCache.java:43-55`），配置没有 key-prefix/corpus-id；注释明确为全局公共知识域。

**触发与影响**：只有多个环境、多个应用或不同语料实际共用同一 Redis 逻辑库时，才会互相覆盖 manifest、正文、锁和代次。单一应用单一公共语料下是设计选择，不是问题。多实例同一语料应继续共享，不能按 instance ID 分键。

**最佳修复**：若部署存在上述共享，增加显式 `app.ai.kb.cache.corpus-id`，键前缀包含稳定环境/语料标识；所有实例同一语料必须使用相同值。

**备选取错**：通过 Redis 逻辑库隔离在 Cluster 不可通用（Cluster 通常只支持 database 0），不能作为 Cluster 方案；按实例分键会破坏跨实例共享。

**修改点/影响面**：`KbCache` 所有键、`CachedKbSource` 诊断、配置和上线迁移；改前缀相当于缓存冷启动，需预发布或双读。

## 6. 全部轮询与阻塞 API 汇总

| 路径 | 周期/等待 | 阻塞内容 | 结论 |
|---|---|---|---|
| 定时刷新 | 10m fixed-delay | local 全文件 SHA、HTTP、Redis、Lucene | 有界节拍，无单次总预算 |
| 版本轮询 | 30s fixed-delay | 正常一次 GET；版本变化时完整刷新 | 有 3 次退避预算，不是死循环；与刷新共线程会被长任务拖延 |
| HTTP 冷启动等待 | 最多 30 次、名义 3s | 每次 HGETALL manifest + sleep | Redis 慢时实际可更久；中断处理有 KB-12 |
| fromCache 写窗等待 | 最多 6 次、名义 125ms | 每次 HGETALL manifest + sleep 25ms | 版本不一致最终仍返回，见 KB-05 |
| HTTP manifest/正文 | 每次连接 5s/读 10s | 同步 `HttpClient.send` | N 篇串行累计，见 KB-01/02 |
| Redis | 每命令 2s | StringRedisTemplate 同步命令和 Lua | O(N) 命令可累计；客户端超时不终止 Lua |
| Lucene 构建 | 无独立超时 | 全量 addDocument/commit/open、文件删除 | 在 reload 或 kb-refresh 线程执行 |
| Lucene 查询 | 工具外层 120s | 查询和 storedFields 本地 I/O | topK≤10、候选数有界；超时中断不保证停止不可中断 I/O |
| 工具分页 | 最多 4 次缩页 | 内存字符串处理 | 无 Redis/网络循环，页大小有上限 |

没有发现 `while(true)` 式 KB 轮询，也没有 KEYS/SCAN 轮询。版本重试 `MAX_VERSION_REBUILD_ATTEMPTS=3` 和 fixed-delay 能防止失败后每拍无退避重建（`KbService.java:125-150,235-278`）。

## 7. 请求上下文传播结论

- KB 三工具经 `SpToolCallingManager` 的独立超时池执行时，提交侧捕获身份，执行侧用 `RunIdentityScope` 恢复，并用 `AiTrace.wrapCallable` 传播 MDC（`SpToolCallingManager.java:2991-3017`）。因此未发现 KB 工具因自身另起线程而丢失租户/用户或 run/session 日志锚点。
- `KbService` 的 `kb-refresh` 是直接用 `Executors.newSingleThreadScheduledExecutor` 创建的，没有 TTL/MDC 装饰（`KbService.java:173-185`）。但刷新是全局语料任务，不读取请求身份、不访问租户 DB，鉴权也只从 HTTP 源环境变量读取，因此**没有代码证据表明这会造成越权或错误租户数据**。其影响主要是调度日志没有请求锚点；若要排障，建议增加固定 `kbRefreshId/instanceId` 日志上下文，而不是把某个用户的请求身份长期捕获到全局调度器。
- `HttpKbSource` 使用同步 `send`，没有异步回调上下文边界。Authorization 每次从环境变量读取，不进入 Redis 键值；本报告未读取或记录任何密钥。

## 8. 与“删除对话超过 30 秒”的关系

本模块控制器只有 status/reload/docs（`AiKbController.java:28-64`），没有 conversation/session delete 映射；全部 ai/kb 生产文件也没有会话删除服务调用。故：

- **直接调用链：未发现。** 删除对话慢不能归因于 KB 的 refresh lock；该锁只保护 KB 回源，不与对话删除共用锁键。
- **KB 自身超过 30 秒：存在明确机制。** KB-01 的 reload 可超过 30 秒，KB-02 会进一步放大，KB-03 的 30 秒 TTL 也会因此失效。
- **间接 Redis 排队：仅为假设。** KB-06 的大 docs Lua 或大 HASH 在 standalone 上可能延迟所有 Redis 请求，在 Cluster 上可能延迟同主节点请求；是否让删除接口超过 30 秒，必须结合删除接口命令数、键槽分布、Redis slowlog/耗时和线程转储验证。当前静态证据不足以作此结论。

## 9. 建议修复顺序与影响面

1. **先修 KB-02、KB-07**：范围小、收益直接，立即减少无意义回源；HTTP sha 契约变更需协调知识服务。
2. **再修 KB-01、KB-03、KB-05**：异步/总预算、可续租 lease、源版本 CAS、manifest 单键原子通知是一组发布协议改造，应一起设计，避免只延长 TTL 或只加锁造成假修复。
3. **部署侧立即核查 KB-04**：确认每实例 index-dir 不共享；若共享，先隔离目录再考虑代码改造。
4. **随后做 KB-06、KB-08、KB-09**：字段级 Redis 读取、有界语料、线性切块和索引构建策略，按实际语料规模决定全量或增量 Lucene。
5. **最后处理 KB-10 至 KB-14**：local 发布契约、prompt/建议缓存、停机收口和运维恢复；KB-14 仅在实际共享不同语料时立项。

## 10. 后续验证方案（仅设计，未执行）

| 覆盖问题 | 验证场景 | 必须断言/观测 |
|---|---|---|
| KB-01 | 多实例、慢 manifest、N 篇变化正文、慢 Redis、慢 Lucene；并发多个 reload | 请求按新异步契约及时返回；整次任务在 deadline 内结束；旧快照可检索；不堆积 reload 线程 |
| KB-02 | HTTP manifest 分别包含有 sha、缺 sha、空 sha；冷启动、200 变化、304 | 有 sha 时未变正文零 GET；缺 sha 按新契约明确拒绝或补算；同篇绝不重复 GET；记录每篇请求数 |
| KB-03/05 | 用可控屏障让 A 旧响应超过锁 TTL，B 新响应先发布，再放行 A；local 两实例同时发布不同语料 | 旧源版本不能覆盖新源版本；续租失败禁止发布；manifest.version 与通知版本一致；无旧清单被标记为最新 |
| KB-04 | 两实例使用各自目录，以及故意共享目录的隔离测试环境 | 实例只清自己的构建目录；另一实例 reader 全程可查；共享配置按新守卫拒绝或隔离 |
| KB-06 | standalone 与 3 主 Cluster，N 篇大文档、M 实例 local 刷新 | 无 CROSSSLOT；Redis RTT/传输字节从 O(B) 降到只读元数据；Lua 单批有上界；观察 slowlog、各主节点命令延迟 |
| KB-07 | 正文 GET 成功后分别让索引写、正文写失败 | 每篇最多一次源 GET；返回已取到正文；旧索引保留；下轮缓存能补偿 |
| KB-08 | 超大 manifest、超大单文档、超长单段/表格/围栏、总语料超限 | 在读取前/流式读取中按配置拒绝；无无界 byte[]；切块耗时随长度近似线性；旧索引不变 |
| KB-09 | 只改一元数据、只改一文档、大语料版本传播 | 明确验证所选全量或增量策略；每实例重建工作量符合设计；检索在构建期间持续使用完整旧快照 |
| KB-10 | manifest 与正文原子/非原子发布，实际 sha 不符，多实例不同 local 盘 | 不符时 fail-fast 或按明确快照契约处理；不会索引错配正文；各实例最终同一 corpus version |
| KB-11 | 大 docs/terms，连续新轮次、零命中、错误 ID | prompt 在快照不变时不重复全量渲染；建议只维护 Top-3；输出文本与基线一致 |
| KB-12 | cold start/fromCache 等待中停机，Lucene 构建中停机 | 中断后立即停止本地等待和 Redis 读取；stopped 后不得发布新 snapshot；目录/reader 最终关闭 |
| KB-13/14 | 索引恢复、不同 corpus 共用 Cluster 的隔离环境 | 孤儿仅由维护工具分批处理；不同 corpus 键完全隔离；同 corpus 多实例仍共享 |
| 删除对话关联假设 | 在独立环境同时制造 KB 大脚本和对话删除，分别记录键槽与 Redis 节点 | 只有时间相关且同节点排队证据充分时才建立关联；同时记录删除接口自身调用链，不能只看 KB 日志 |

建议为上述场景补充的观测点包括：每次刷新的 corpus version、generation、源 ETag、文档数、变化数、回源次数、各阶段耗时、Redis 命令数/字节数、锁续租失败、Lucene 构建耗时和目录 owner。指标不得包含正文或 Authorization。

## 11. 未发现问题的关键路径与正向确认

- **Cluster 单键约束**：全部 Lua 单键；无跨槽事务、KEYS、SCAN，未发现 CROSSSLOT 代码路径。
- **锁释放**：随机 token + GET 比对 + DEL 原子执行，旧持有者不会误删新持有者的锁；但锁续租不足另见 KB-03。
- **lazy 补写顺序**：先索引后正文，正常失败窗口不会制造“正文存在而从未有回收线索”的中间态。
- **普通下线重试**：正文删除成功才摘索引，删除失败留索引，304 也会对账重试；代次闸能阻止低代次回收高代次正文。
- **故障降级**：远端 5xx/超时保留旧缓存和旧索引；manifest 为空/非法不当成空知识库清空线上检索面。
- **热切换**：Snapshot 为 volatile，检索按引用计数持有旧 reader，最后一个使用者释放后关闭；reload 与检索之间没有全局读锁。
- **停机自旋**：stop 先摘 snapshot 再 retire，`acquire` 同时检查 stopped，未发现注释所述“retired 快照导致 100% CPU 自旋”的现存路径。
- **工具入参/出参**：topK 最大 10、读取页最大 8000 字、分析词最多 10 个、缩页最多 4 次；输出定界和截断是在本地快照上完成。
- **工具请求上下文**：超时池显式恢复身份与 MDC，KB 工具没有再创建自己的异步线程。
- **DB**：本模块无 DB 调用，不存在 KB 刷新持有数据库事务/连接的情况。
- **删除对话**：本模块无删除对话入口或调用，不把其他模块的慢接口强行归因到 KB。
