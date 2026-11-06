# C 领域：AI 知识库全链路多租户审查报告

## 身份门禁证据

| 项目 | 证据 | 结果 |
|---|---|---|
| `agentId` | 实际为 `agent-942`；`environment.set_binding` 第 2 行、`profile.bind` 第 6 行、首条 `llm.request` 第 20 行一致 | 通过 |
| `modelAlias` | `<会话证据根>/agents/agent-942/wire.jsonl` 第 6 行为 `kimi-code/k3.1-pro`；第 20 行相同 | 通过 |
| `model` | 同一文件第 20 行首条 `llm.request` 为 `k3.1-pro` | 通过 |

> 本文将此前过程中自报的 agentId 修正为实际 `agent-942`。身份证据文件统一记为 `<会话证据根>/agents/agent-942/wire.jsonl`。

审查仓库为 `ai-example-main-v2`，审查 HEAD 为 `17080859f7b6905aafbbea90f6d2281913bcab9e`，与指定 `17080859` 一致。

本次为独立静态只读审查：未修改产品代码、未运行测试或批量脚本、未执行 git 写操作、未启动或停止服务。本文仅为审查结果沉淀。

## 总体结论

当前 KB 实现明确是**单一、会话无关、无租户段的全局知识域**，不是“键名漏加一个 tenantId”的局部问题。文档、chunk、Lucene 索引、内存快照、Redis 清单与正文、刷新锁、ETag、重试状态、文件路径、HTTP 凭据、管理端点均没有租户边界，用户身份也完全未参与 KB 授权。

按给定的“租户+用户隔离、跨租户业务 ID 只在租户内唯一、同一用户可落任意实例”口径，结论为：

- **P0 确定问题 3 项**：检索读取面、Redis 数据面、知识源与本地文件面均不满足租户隔离。
- **P1 确定问题/风险 4 项**：管理端点越权影响全局、刷新上下文无法支撑租户化、缓存降级导致实例间分叉、共享索引目录互删。
- **P2 风险 2 项**：诊断与日志暴露面、符号链接路径逃逸。
- **P3 无法判定 1 项**：语言变体是否有独立隔离要求。
- **本领域没有租户列表/枚举依赖**。但这只是未枚举，不代表隔离安全。

---

## P0-1：三工具和单例 Lucene 快照完全无租户/用户边界

**判定：P0，确定问题。**

### 证据与关键结构

`KbService` 是 Spring 单例，只有一份 `Snapshot`、一份 ETag 和刷新状态：

- `backend/src/main/java/com/example/configmgr/ai/kb/KbService.java:117-156`
- `backend/src/main/java/com/example/configmgr/ai/kb/KbService.java:161-165`

Lucene 字段只有 `doc_id/title/section/.../body`，没有租户或用户字段：

- `backend/src/main/java/com/example/configmgr/ai/kb/KbService.java:77-96`
- `backend/src/main/java/com/example/configmgr/ai/kb/KbService.java:488-499`

检索查询没有任何租户过滤：

- `KbService.search(String query, Integer topK)`：`backend/src/main/java/com/example/configmgr/ai/kb/KbService.java:569-588`
- 查询仅由各字段 `TermQuery + BoostQuery` 组成：`backend/src/main/java/com/example/configmgr/ai/kb/KbService.java:643-651`

直接读取和术语查询只按裸 `docId`、裸归一化术语访问：

- `acquired.docs.get(docId.strip())`：`backend/src/main/java/com/example/configmgr/ai/kb/KbService.java:741-755`
- `acquired.terms.get(normalized)`：`backend/src/main/java/com/example/configmgr/ai/kb/KbService.java:861-882`
- 快照内 `docs` 按 `meta.id()` 建 Map：`backend/src/main/java/com/example/configmgr/ai/kb/KbService.java:356-379`
- 术语表也只以 `normalized` 为键，先登记者优先：`backend/src/main/java/com/example/configmgr/ai/kb/KbService.java:531-565`

未命中侧信道同样暴露全局数据：

- 近似标题：`backend/src/main/java/com/example/configmgr/ai/kb/KbService.java:692-701`
- 文档 ID 和标题候选：`backend/src/main/java/com/example/configmgr/ai/kb/KbService.java:853-856`
- 术语候选：`backend/src/main/java/com/example/configmgr/ai/kb/KbService.java:874-879`

三个工具均不接收或读取租户/用户：

- `kbSearch`：`backend/src/main/java/com/example/configmgr/ai/kb/KbTools.java:113-117`
- `kbReadDoc`：`backend/src/main/java/com/example/configmgr/ai/kb/KbTools.java:174-180`
- `kbLookupTerm`：`backend/src/main/java/com/example/configmgr/ai/kb/KbTools.java:303-305`

`KbDoc`、`KbChunk` 也只有裸 `docId`：

- `backend/src/main/java/com/example/configmgr/ai/kb/KbDoc.java:12-21`
- `backend/src/main/java/com/example/configmgr/ai/kb/KbChunk.java:18-19`

### 完整调用链

`TenantIdentityFilter` 建立请求身份：

- `backend/src/main/java/com/example/configmgr/ai/tenant/TenantIdentityFilter.java:118-150`

随后：

`ResilientChatService.systemPromptText`
→ `SpToolCallingManager` 按 run 快照/执行信封恢复身份
→ `executeWithTimeout` 在工具超时池重新建立身份
→ 官方 `ToolCallback`
→ `KbTools`
→ 单例 `KbService`
→ 唯一全局 `Snapshot`

身份传播本身有明确实现：

- `backend/src/main/java/com/example/configmgr/ai/gate/SpToolCallingManager.java:848-851`
- `backend/src/main/java/com/example/configmgr/ai/gate/SpToolCallingManager.java:3063-3072`
- 官方单工具委托：`backend/src/main/java/com/example/configmgr/ai/gate/SpToolCallingManager.java:3306-3321`

但 KB 在链路末端完全不消费该身份。

### 具体触发场景

1. 租户 A、B 都有 `docId=policy`，内容不同。
2. A 用户经任意实例调用 `kb_read_doc("policy")`，B 用户也执行同样调用。
3. 两者都进入 `acquired.docs.get("policy")`，没有第二次租户判定。
4. 同一租户的用户 U1/U2 也没有任何文档级权限差异；只要模型或用户知道/猜到 docId，就能读取当前全局快照中的正文。
5. 即使搜索被刻意限制，`readDoc`、近似标题、文档候选、术语答案和候选仍形成独立泄露面。

### 影响

跨租户正文、标题、标签、术语答案和元数据泄露；同租户跨用户越权；模型把其他租户制度/条款当作当前用户官方口径回答。由于结果会进入模型上下文和对话历史，泄露还会进一步沉淀到对话数据中。

### 修改建议

- 所有入口显式增加服务端构造的 `KbAccessContext`，至少包含 `tenantId/userId`，语言变体需要时加入 `locale`；**不得将 tenantId/userId 作为模型可填的 `@ToolParam`**。
- `KbTools` 可从已证明存在的 `RequestContextUtils.require()` 取身份，再显式传给 `KbService`；缺身份必须失败关闭。
- 推荐每个租户独立 `TenantKbRuntime + Snapshot`，使 `docs/terms/Lucene reader` 天然只含当前租户。
- 若坚持共享一个 Lucene 索引，则必须同时：
  - 每个 Lucene Document 增加 `tenant_id`，必要时增加可见性字段；
  - 每个查询强制租户过滤；
  - `docs` 改以 `(tenantId, docId)` 为键；
  - `terms` 改以 `(tenantId, normalizedTerm)` 为键；
  - 搜索去重、标题/术语/文档候选、catalog、readDoc 全部使用同一复合身份。
- 租户共享文档无需按用户复制索引；但用户私有文档或文档级 ACL 必须在**所有**读取及候选路径执行用户授权，不能只过滤 `kb_search`。

---

## P0-2：Redis 全键族全局共享，跨租户同名文档会覆盖并被差集删除

**判定：P0，确定问题。**

### 键结构

`KbCache` 明确写死全局键：

- `kb:cache:doc:<id>`
- `kb:cache:docs`
- `kb:cache:manifest`
- `kb:cache:manifest:generation`
- `kb:cache:{kb:cache:manifest}:refresh:lock`

证据：

- `backend/src/main/java/com/example/configmgr/ai/kb/KbCache.java:41-52`
- 裸 docId 拼键：`backend/src/main/java/com/example/configmgr/ai/kb/KbCache.java:74-76`
- 清单、代次和锁读写：`backend/src/main/java/com/example/configmgr/ai/kb/KbCache.java:203-261`

正文、索引和清单均不设 TTL：

- `backend/src/main/java/com/example/configmgr/ai/kb/RedisKbCacheStore.java:173-177`
- `backend/src/main/java/com/example/configmgr/ai/kb/KbCache.java:25-37`

Manifest 只保证**一份清单内部** docId 唯一：

- `backend/src/main/java/com/example/configmgr/ai/kb/KbManifest.java:47-65`

没有数据库约束可以补救跨租户唯一性；本领域也没有 DB 实现。

### 完整调用链

`kb-refresh / POST reload`
→ `KbService.performRefresh`
→ `CachedKbSource.refresh/coldStart`
→ `syncToCache`
→ 全局 `docIds()`、全局 `doc(id)`、全局 generation
→ 写同名 `doc:<id>`
→ 发布唯一全局 manifest
→ `pruneRemovedDocs`
→ 轮询全局 corpus version 的其他实例重建。

关键位置：

- `backend/src/main/java/com/example/configmgr/ai/kb/CachedKbSource.java:153-209`
- `backend/src/main/java/com/example/configmgr/ai/kb/CachedKbSource.java:279-362`
- 回收：`backend/src/main/java/com/example/configmgr/ai/kb/CachedKbSource.java:421-455`
- 跨实例传播：`backend/src/main/java/com/example/configmgr/ai/kb/KbService.java:230-250`

### 具体触发场景

1. A 发布 `policy`，正文 A、SHA-A，写入 `kb:cache:doc:policy` 和全局 manifest。
2. B 发布同名 `policy`，正文 B、SHA-B。
3. B 读取缓存时发现 SHA 不匹配，于是回源并覆盖同一正文键，随后覆盖全局 manifest。
4. A 的实例轮询到该版本后也重建为 B 的语料。
5. B 的 `current` 集合不含 A 独有文档，`pruneRemovedDocs` 会按全局 `docs` 差集删除 A 的正文和索引项。
6. 全局锁只保证“同一时刻一个刷新者”，并不保证刷新者属于同一租户；全局 generation 也只能防旧代次，不能防另一租户的新代次。

### 影响

持久性跨租户正文污染、清单替换和文档删除，并通过版本轮询扩散至全部实例。无 TTL 使错误数据不会自然消失。

### 修改建议

使用新版本、租户隔离的键族，例如：

```text
T = 经验证的十进制 tenantId
D = base64url(docId) 或其他无分隔符编码

kb:cache:v4:tenant:<T>:doc:<D>
kb:cache:v4:tenant:<T>:docs
kb:cache:v4:{tenant:<T>}:manifest
kb:cache:v4:{tenant:<T>}:manifest:generation
kb:cache:v4:{tenant:<T>}:refresh:lock
```

- manifest 与 lock 使用完全相同的 hash tag，保留双键 Lua 的 Cluster 同槽约束。
- `docIds/generation/lock/version` 全部按租户重划，不能只改正文键。
- 用户私有语料需要时增加 `user:<U>`，并让 hash tag 同时含租户和用户；租户共享语料不应按用户重复缓存。
- `CachedKbSource` 的 `lastSeenVersion/lastRemoteCheck/reconcileAfterDirect` 也必须随租户 runtime 分离，见 `backend/src/main/java/com/example/configmgr/ai/kb/CachedKbSource.java:41-47`。
- 不得从新代码回读旧的无租户键；旧键归属无法证明，自动迁移会把旧全局数据带给第一个请求租户。

---

## P0-3：本地目录、HTTP 源和样例播种均为单一静态源，Redis 降级仍回退到该全局源

**判定：P0，确定问题。**

### 证据

装配只创建一个源：

- local：`new LocalDirKbSource(Path.of(kb.getDir()))`
- http：单一 `baseUrl/connectTimeout/readTimeout/authEnv`
- 外面只包一个全局 `CachedKbSource`

见 `backend/src/main/java/com/example/configmgr/ai/kb/KbConfig.java:25-49`。

出厂配置也只有一份目录和索引目录：

- `backend/src/main/resources/application.yml:321-326`

local 源固定读取：

```text
<dir>/manifest.json
<dir>/<meta.file 或 docId.md>
```

见：

- `backend/src/main/java/com/example/configmgr/ai/kb/LocalDirKbSource.java:52-109`
- 默认路径规则：`backend/src/main/java/com/example/configmgr/ai/kb/KbDocMeta.java:51-54`

HTTP 源固定请求：

```text
<baseUrl>/manifest.json
<baseUrl>/<encoded effectiveFile>
Authorization = 单一进程环境变量 KB_HTTP_AUTH
```

见：

- `backend/src/main/java/com/example/configmgr/ai/kb/HttpKbSource.java:96-128`
- `backend/src/main/java/com/example/configmgr/ai/kb/HttpKbSource.java:141-150`
- `backend/src/main/java/com/example/configmgr/ai/kb/HttpKbSource.java:194-210`

Redis 失败时直接回源：

- manifest 降级：`backend/src/main/java/com/example/configmgr/ai/kb/CachedKbSource.java:93-115`
- 正文降级：`backend/src/main/java/com/example/configmgr/ai/kb/CachedKbSource.java:124-150`

启动时不等待任何真实请求身份即播种和建索引：

- `backend/src/main/java/com/example/configmgr/ai/kb/KbService.java:169-190`
- `backend/src/main/java/com/example/configmgr/ai/kb/LocalDirKbSource.java:41-44`
- 样例锁固定为父目录 `.kb-samples-seed.lock`：`backend/src/main/java/com/example/configmgr/ai/kb/KbSampleSeeder.java:61-64,87-120`

### 调用链与场景

`应用启动 / 定时刷新 / 手动 reload / Redis 故障`
→ 单例 `KbConfig` 创建的源
→ 同一个本地 manifest 或同一个 HTTP baseUrl+Authorization
→ 全局缓存与快照。

因此：

- 不同租户无法拥有同名但不同内容的 `<dir>/policy.md`。
- 不同租户无法使用各自 HTTP 清单和凭据。
- 若运维试图用不同实例目录区分租户，同一用户的请求落到其他实例后又会读到别的语料。
- Redis 故障不会进入“当前租户专属源”，而是进入同一无身份静态源；未来若只给缓存键加 tenantId，此路径仍会绕过隔离。

### 影响

跨租户读取、默认样例或错误租户语料混入、跨实例答案不一致。仅修改 Redis 键无法修复。

### 修改建议

- `KbSource.fetchManifest/fetchContent/prepare` 显式接收租户 scope，禁止依赖定时线程上的 ThreadLocal。
- local 根目录按租户划分，如 `<kb.dir>/tenant-<T>/manifest.json`；正文仍在该租户根内解析。
- HTTP 租户段必须来自服务端可信映射；URL path 段正确编码，租户凭据由服务端安全配置提供，不能由模型或前端任意指定。
- ETag、remote-check、重试状态均按租户保存。
- 样例只能在某租户真实请求触发其 runtime 初始化后，为该租户显式播种；禁止回退到全局默认样例。
- Redis 降级只能访问**同一租户、同一受信源版本**，不能退化为无 scope 的 `fetchDirect`。

---

## P1-4：任意已登记租户用户均可读取全局目录/错误并触发全局 reload

**判定：P1，确定问题。**

### 证据与调用链

`AiKbController`：

- `GET /status`：`backend/src/main/java/com/example/configmgr/ai/kb/AiKbController.java:38-41`
- `POST /reload`：`backend/src/main/java/com/example/configmgr/ai/kb/AiKbController.java:43-48`
- `GET /docs`：`backend/src/main/java/com/example/configmgr/ai/kb/AiKbController.java:51-65`
- 返回全局 `last_error`：`backend/src/main/java/com/example/configmgr/ai/kb/AiKbController.java:67-78`

`/api/ai/kb` 不在身份过滤器排除项中，所以请求会经过 `TenantIdentityFilter`；但过滤器只验证租户/用户组合存在：

- `backend/src/main/java/com/example/configmgr/ai/tenant/TenantIdentityFilter.java:134-150`

控制器没有管理员/运营角色检查，也没有把身份传给 `KbService`。

### 触发场景与影响

任一有效租户用户均可：

- 查看当前全局文档 ID、标题、tags、更新时间和 SHA 前缀；
- 查看其他实例/其他租户刷新产生的全局错误；
- 强制占用全局刷新 lease、触发远端 I/O 和全量索引构建，影响所有租户与实例。

### 修改建议

- 若是运营端点，应迁出普通用户 API，并增加明确的管理员授权/网络边界。
- 若保留租户自助刷新，则 `reload/status/docs` 必须只操作当前租户 runtime，并使用当前租户 lease。
- `last_error` 必须按租户保存和返回；原始路径、缓存键、堆栈不得进入用户响应。
- 后续验证两个不同租户的普通用户和管理员分别调用三类端点，确认既不能读对方元数据，也不能触发对方刷新。

---

## P1-5：启动、定时、手动和续租线程没有租户上下文传播设计

**判定：P1，确定问题；是租户化改造的直接阻断点。**

### 证据

启动即全局播种、建索引和注册定时任务：

- `backend/src/main/java/com/example/configmgr/ai/kb/KbService.java:169-190`

手动 reload 虽从请求线程进入，但被提交到裸 `kb-refresh` 线程，lambda 未捕获身份：

- `backend/src/main/java/com/example/configmgr/ai/kb/KbService.java:281-298`

定时刷新和版本轮询均无身份：

- `backend/src/main/java/com/example/configmgr/ai/kb/KbService.java:220-250`

刷新状态和单飞闸均为单份：

- `snapshot/lastEtag/lastObservedVersion/retryVersion/reloadRunning`：`backend/src/main/java/com/example/configmgr/ai/kb/KbService.java:117-156`

续租线程只持有 token 和全局 cache：

- `backend/src/main/java/com/example/configmgr/ai/kb/CachedKbSource.java:549-596`

### 触发场景与影响

若只在 `KbCache` 内改为 `RequestContextUtils.getTenantId()`：

- 启动和定时线程没有身份，刷新失败；
- 手动 reload 到 `kb-refresh` 后丢失请求身份；
- 若线程曾残留身份，则可能以错误租户刷新；
- 为“修复”而扫描全部租户又会违反生产无租户枚举能力的约束。

### 修改建议

- 建立 `ConcurrentMap<Long, TenantKbRuntime>`，每个 runtime 持有自己的 snapshot、ETag、重试、错误、lastSeenVersion 和 single-flight。
- **只从真实 KB 请求或当前租户管理请求懒创建 runtime**。定时器只能遍历这个进程已经缓存的租户 runtime，不能调用租户目录或扫描 DB/Redis 来发现租户。
- runtime 应有空闲过期和快照安全关闭机制，避免最多数十租户长期无界驻留。
- 手动 reload 在请求线程捕获不可变 `tenantId`，再将其作为参数传入 `performRefresh`；不要依赖线程上下文。
- 后台任务以系统用户语义执行，但键和查询只使用已捕获的 tenantId。
- `Lease` 对象应直接持有该租户的 manifest/lock/generation key 或对应 scoped cache，续租不读取 ThreadLocal。
- 每租户 single-flight 之外可保留全局有界调度队列，避免某租户刷新阻塞全部租户。

---

## P1-6：Redis 故障时各实例直连本地/远端源，跨实例一致性没有保证

**判定：P1，风险；触发条件为 Redis 故障且各实例源内容或响应不同。**

### 证据与调用链

`CachedKbSource.fetchManifest`
→ `KbCacheException`
→ `fetchDirect`
→ 本地文件或 HTTP
→ 当前实例私有 `Snapshot`

见 `backend/src/main/java/com/example/configmgr/ai/kb/CachedKbSource.java:93-115` 和 `backend/src/main/java/com/example/configmgr/ai/kb/KbService.java:336-409`。

本地 Lucene 快照本来就是实例私有的；共享 Redis 是唯一明确的跨实例语料协调面。

### 场景与影响

- A 实例磁盘为版本 1，B 实例磁盘为版本 2；
- Redis 故障后两者均 direct 成功；
- 同一用户的连续请求被负载均衡到 A/B，得到不同制度内容；
- 若某实例目录为空，还可能在启动时播入样例，而非生产语料。

`reconcileAfterDirect` 只控制之后对账，不保证降级期间各实例字节一致。

### 修改建议

- 生产多实例应以同一受信 HTTP/DB 语料源为真源；local 源若继续使用，必须证明共享卷及发布一致性。
- 降级结果至少携带并记录租户、manifest version/hash，便于识别实例分叉。
- 对租户私有数据，无法证明源和版本一致时应失败关闭，而不是回退默认目录。
- 后续进行 Redis 故障+A/B 实例不同版本验证，检查是否按设计拒绝或返回同一版本，不能只看“功能仍可用”。

---

## P1-7：共享 `index-dir` 时实例会互删对方仍在使用的 Lucene 构建目录

**判定：P1，风险；是否发生取决于部署是否共享索引目录，当前部署方式无法由仓库证明。**

### 证据与调用链

`refreshNow`
→ `performRefresh`
→ `newBuildDir`
→ `cleanupStaleBuildDirs`
→ 删除所有非**本实例当前 live** 的 `build-*`

见：

- `backend/src/main/java/com/example/configmgr/ai/kb/KbService.java:443-467`

快照退休也会直接删除对应目录：

- `backend/src/main/java/com/example/configmgr/ai/kb/KbService.java:1111-1134`

目录名仅为 `build-<System.nanoTime()>`，且使用 `Files.createDirectories`：

- `backend/src/main/java/com/example/configmgr/ai/kb/KbService.java:450`

### 场景与影响

若 A/B 实例挂载同一 `./data/kb-index`：

- A 刷新时只认识 A 的 live snapshot，会把 B 的 live `build-*` 当 stale 删除；
- B 随后也可能删除 A 的目录；
- 打开中的 reader 是否立即失败依操作系统和 Lucene 文件打开方式而异，但共享目录的“只清自己垃圾”前提已被破坏；
- 不同实例 `nanoTime` 不具全局唯一性，同名目录碰撞时 `createDirectories` 也不会主动报已存在。

### 修改建议

- 不共享可写 Lucene 索引目录；只共享 Redis 中的不可变语料。
- 本地路径改为 `<index-dir>/<instanceId>/tenant-<T>/build-<UUID>`。
- 每实例只清理自己的 `instanceId` 根，使用 `Files.createDirectory` 创建唯一 build 目录。
- 后续在共享根下运行双实例刷新与检索，验证任何实例都不会删除另一实例 live 目录。

---

## P2-8：公开诊断 API、错误与日志均是无 scope 的旁路/暴露面

**判定：P2，风险。**

### 证据

未 scoped 的诊断读取：

- `CachedKbSource.cachedDocIds()`：`backend/src/main/java/com/example/configmgr/ai/kb/CachedKbSource.java:523-532`
- `CachedKbSource.rawDoc(docId)` 直接返回正文：`backend/src/main/java/com/example/configmgr/ai/kb/CachedKbSource.java:535-538`
- `KbService.document(docId)` 返回完整 `KbDoc`：`backend/src/main/java/com/example/configmgr/ai/kb/KbService.java:921-925`

当前生产代码未发现这些诊断方法的调用方，因此不是已证明的外部端点，但属于必须随改造收口的无隔离 API。

日志和错误：

- Redis 异常把完整 key 写日志并放入异常：`backend/src/main/java/com/example/configmgr/ai/kb/RedisKbCacheStore.java:391-395`
- 回收日志包含 docId：`backend/src/main/java/com/example/configmgr/ai/kb/CachedKbSource.java:439-455`
- local 错误包含目录或文件路径：`backend/src/main/java/com/example/configmgr/ai/kb/LocalDirKbSource.java:57-66,79-89`
- 全局 `lastError` 最终由 `/status` 返回：`backend/src/main/java/com/example/configmgr/ai/kb/KbService.java:411-420`、`backend/src/main/java/com/example/configmgr/ai/kb/AiKbController.java:77`

### 场景与影响

某租户刷新失败产生包含 docId/key/path 的错误后，其他用户可通过全局状态或共享日志看到。未来若简单把 tenantId 拼入 key 和路径，现有日志会自动扩大为租户标识泄露。

### 修改建议

- 删除或给所有诊断 API 增加同样的 `KbAccessContext`；不得保留“内部调用方便”的无 scope 重载。
- 日志只记录脱敏租户标识、key 族、corpusVersion 和错误类别，不记录完整 key、正文或原始路径。
- 同步工具日志关联 run/session/trace；后台刷新使用独立 trace 并带租户，不带请求用户身份。
- `lastError` 按租户隔离并对用户侧脱敏。
- 日志系统的实际读取授权在本领域外，故其跨租户可见性为**无法判定**。

---

## P2-9：本地路径闸只防 lexical `../`，不能防符号链接跨目录

**判定：P2，风险；需要知识目录或 manifest 的写权限，非普通模型输入直接可利用。**

### 证据与调用链

`LocalDirKbSource.fetchManifest/fetchContent`
→ `resolve`
→ `dir.resolve(...).normalize()`
→ 仅检查 `resolved.startsWith(dir)`

见 `backend/src/main/java/com/example/configmgr/ai/kb/LocalDirKbSource.java:135-140`。

但 `Files.isRegularFile` 和读取会跟随符号链接：

- `backend/src/main/java/com/example/configmgr/ai/kb/LocalDirKbSource.java:79-95,122-128`

样例播种也只进行 lexical 检查：

- `backend/src/main/java/com/example/configmgr/ai/kb/KbSampleSeeder.java:145-156`

### 场景与影响

租户目录改造后，若 A 目录内某文档是指向 B 文档的符号链接，lexical 路径仍位于 A 下，但真实读取会进入 B。当前无租户目录时这不是新增隔离根因，但现有路径闸不能作为未来租户文件边界。

### 修改建议

- 对租户根和文件使用 `toRealPath()` 后重新检查 `startsWith(realTenantRoot)`；
- 或全程 `NOFOLLOW_LINKS` 并拒绝符号链接；
- 文件创建、读取和样例 staging 使用同一真实路径策略。
- 后续增加租户目录内 `../`、绝对路径、文件符号链接和目录符号链接四类单点验证。

---

## P3-10：语言只有提示词规则和英文别名，没有语言变体隔离

**判定：P3，无法判定是否构成产品缺陷。**

证据：

- `KbDocMeta` 无 locale/language 字段，只有 `aliases_en`：`backend/src/main/java/com/example/configmgr/ai/kb/KbDocMeta.java:27-47`
- 英文提问仍查询同一中文语料：`backend/src/main/java/com/example/configmgr/ai/kb/KbPromptSection.java:20-29`
- `ReplyLanguage` 只影响提示词，不影响 KB runtime：`backend/src/main/java/com/example/configmgr/ai/run/ResilientChatService.java:4963-4983`

当前实现明确是“单语料、英文别名桥接”，不是多语言知识库。若产品不要求独立语言版本，此行为符合现状；若同一租户同一 docId 有中英版本，则当前 manifest 会忽略未知语言字段并混入同一文档空间。

建议仅在存在语言变体时，将 locale 显式加入文档唯一键和 runtime 选择逻辑，并定义确定的 fallback；后台刷新必须携带该变体，不能从 `ReplyLanguage` 线程上下文猜测。

---

## 已证安全或明确不存在的能力

- **工具线程身份传播已证存在，但 KB 未使用**：`backend/src/main/java/com/example/configmgr/ai/gate/SpToolCallingManager.java:848-851,3063-3072` 是显式按记录身份重建作用域，不只是依赖 ThreadLocal 装饰器。
- **单一全局知识域内的锁释放/续租有 token 比对**：`backend/src/main/java/com/example/configmgr/ai/kb/RedisKbCacheStore.java:27-44`；manifest 发布同时检查 lease 和 generation：`backend/src/main/java/com/example/configmgr/ai/kb/RedisKbCacheStore.java:46-63`。这些机制正确性不等于租户隔离。
- **单实例单语料快照切换是原子的**：`backend/src/main/java/com/example/configmgr/ai/kb/KbService.java:392-405`；旧 reader 引用计数退休：`backend/src/main/java/com/example/configmgr/ai/kb/KbService.java:1096-1134`。它只能保证旧/新全局语料一致性。
- **`../` lexical 越界有拦截**：`backend/src/main/java/com/example/configmgr/ai/kb/LocalDirKbSource.java:135-140`；但不覆盖符号链接。
- **无 DB 查询、表或唯一约束**：KB 领域没有 JPA/JDBC 代码。远端 HTTP 知识服务是否有 `(tenant_id, doc_id)` 唯一约束，仓库内无法判定。
- **无向量或 embedding 实现**：实际为 Lucene BM25；chunk 是文本块，不是向量。
- **无 KB Pub/Sub**：跨实例通知使用 Redis manifest version 轮询。
- **无 KB 专属前端存储或前端管理调用**：前端只发现工具摘要；通用对话镜像/历史不在本领域裁决范围。
- **静态 ToolRegistry 只保存工具定义和回调**，未发现知识正文注册表。

## 租户枚举/列表依赖清单

**本领域当前没有租户枚举或租户列表依赖。**

具体而言：

- `ai/kb` 未调用 `TenantUserDirectory`、租户 Controller 或“全部租户”接口；
- `KbCache.docIds()` 枚举的是全局文档 ID HASH 字段，不是租户；
- `Files.list` 用于构建目录清理和样例目录判空，不是租户列表；
- 版本轮询只读一个全局 manifest version，也没有遍历租户。

后续改造不得新增启动时租户扫描、DB 全租户查询、Redis tenant-key SCAN 或租户目录遍历。定时刷新只能处理**由真实请求懒加载并仍缓存的租户 runtime**。

## 推荐迁移顺序与兼容策略

1. **先封住读取面**：所有工具、诊断、catalog/status/document/rawDoc 改为强制 `KbAccessContext`，无身份失败关闭。
2. **引入租户 runtime**：拆分 snapshot、ETag、版本、重试、错误和 single-flight。
3. **切换 v4 键族和租户源**：正文、docs、manifest、generation、lock 同步切换，不能只改 doc key。
4. **改造后台调度**：启动不再加载全局语料；真实请求懒加载，后台只刷新已知缓存租户。
5. **索引目录实例化**：实例私有根 + 租户子目录 + UUID build。
6. **最后处理旧数据**：
   - 新代码禁止 dual-read 旧全局键；
   - 旧全局键不自动认领给任何租户；
   - 各租户在首次真实请求时从自己的受信源重建；
   - 滚动期间旧版本仍会造成全局泄露，应在维护窗口禁用 KB 或协调停止旧节点后再启用新版本；
   - 验证全部节点不再读写旧键后，由显式运维操作清理旧键。

## 后续必须单点验证（本次未执行）

1. A/B 租户同 docId、同相对路径、不同正文：search/read/lookup/catalog/候选/rawDoc 全部只返回本租户。
2. B 刷新后，A 独有文档和正文键仍存在；A/B manifest、generation、lock 互不影响。
3. 同租户 U1/U2 的私有文档与 ACL，覆盖正文、标题、术语、近似候选和状态错误。
4. 同一用户请求交替落 A/B 实例，版本和正文一致；A 发布只唤醒 A 租户 runtime。
5. 手动 reload 在无请求 ThreadLocal 的 `kb-refresh` 中仍使用原请求租户；定时任务只处理真实请求缓存过的租户。
6. Redis 故障时不读取无租户旧键、不播全局样例、不进入其他租户目录。
7. 旧 `kb:cache:*` 中预置“诱饵数据”，新版本任何路径均不得读取。
8. 双实例共享索引根压力刷新，无互删 live 目录。
9. local 源 `../`、绝对路径、文件/目录符号链接均不能逃逸租户根。
10. 若支持语言变体，同租户同 docId 的中英版本及 fallback 不混用。
