# 内存与资源生命周期全面审查

- 审查基线：`52b09364`（本次读取时 HEAD 的完整提交为同一基线）。
- 报告范围：生产代码与生产配置中的静态/实例缓存、注册表、监听器、Redis Pub/Sub、定时器、SSE emitter 与队列、流、文件句柄、临时文件、Lucene reader/writer/directory、线程池、HTTP/DB/Redis 连接及文件回收。
- 审查方式：只读静态审查。未修改任何生产/测试代码，未运行测试、构建、CI、服务或压测，未做 git 写操作。
- 确定性口径：“代码证实”指创建、清理或并发结构可直接由当前源码证明；触发所需要的故障、共享目录或大文件等外部条件会单独写明。“假设”用于库内部是否仍持有资源、具体操作系统表现等不能仅由本仓源码完全证明的部分。
- 严重度口径：高 = 可持续耗尽堆/磁盘/连接，或可破坏其他实例正在使用的索引；中 = 需要故障/停机竞态或较长积累，影响通常可恢复；低 = 小对象缓增、非默认装配或库实现相关的卫生问题。

## 一、结论与风险排序

共列出 14 项。高优先级 5 项，中优先级 6 项，低优先级 3 项。

| 编号 | 严重度 | 确定性 | 摘要 |
|---|---|---|---|
| RL-01 | 高 | 代码证实 | 两个 Redis Pub/Sub 容器启动失败时没有 destroy 半初始化实例，重试可反复遗留连接/任务 |
| RL-02 | 高 | 代码证实 | 表单附件只清 Redis 登记而不在 TTL 到期时删盘；消费/收口删盘失败也没有 durable 重试 |
| RL-03 | 高 | 代码证实 | ZIP 上传把全部解压内容无上限读入并同时保留，且在持有任务 DB 锁/事务时执行 |
| RL-04 | 高 | 代码证实（以共享 index-dir 为触发前提） | KB 陈旧目录清理会把其他实例正在使用/正在构建的 Lucene 目录当成垃圾删除 |
| RL-05 | 高 | 代码证实 | ZIP/单文件下载把源文件、ZIP 缓冲及最终 byte[] 聚合到堆，响应期间无法按流释放 |
| RL-06 | 中 | 代码证实 | KB `stop()` 与在途 `refreshNow()` 不同步，可在停机后重新发布一个无人退休的 reader/directory |
| RL-07 | 中 | 代码证实 | `LeaseService.ended` 对丢租/不可用轮次只增不减，没有终态、TTL 或容量出口 |
| RL-08 | 中 | 代码证实 | `ai:creq` 的取消墓碑及异常遗留 RUN 字段随活跃会话累计，现有 sweeper 明确不回收它们 |
| RL-09 | 中 | 代码证实 | 普通 xlsx 上传没有把 `MultipartFile.getInputStream()` 纳入关闭范围 |
| RL-10 | 中 | 代码证实 | 模型探活先无界读取完整响应再截断，等待超时/中断时也没有取消 `CompletableFuture` |
| RL-11 | 中 | 代码证实 | 任务事件总线没有 closed 闸，停机与并发首次订阅可重建无人 destroy 的容器 |
| RL-12 | 低 | 假设（缺少 close 已证实，残余资源取决于 POI 实现） | `SXSSFWorkbook` 只 dispose 不 close，输出流关闭不能替代 workbook 的完整关闭 |
| RL-13 | 低 | 代码证实 | AI provider 解密告警表和浏览器任务类型表都没有随删除/修复回收 |
| RL-14 | 低 | 代码证实（仅无 Redis 模板的非默认装配） | 附件进程内 fallback 的登记和消费墓碑没有 TTL/容量；生产 Redis 路径不受影响 |

优先顺序建议：先处理 RL-01/02/03/04/05，再处理停机与长期积累项 RL-06/07/08/11，最后做局部卫生项。不要以“加 TTL”“直接清 Map”替代 fencing 或业务归属判断；RL-07 和 RL-08 的修复都必须先保住陈旧写/防重语义。

## 二、审查范围与实际阅读文件

### 2.1 全量扫描范围

对以下生产树做了全量关键字检索，再按创建点、使用点和释放点回读实现：`backend/src/main/java`、`backend/src/main/resources`、`frontend/src`，以及 `backend/pom.xml`、`frontend/package.json`、`frontend/vite.config.ts` 的依赖/装配信息。扫描词覆盖 static、Map/Set、缓存、Executor/Scheduler/Future、subscribe/listener、SseEmitter、Stream/InputStream/OutputStream、Files、临时目录、Lucene reader/writer/directory、destroy/close/cancel、前端 timer/observer/EventSource/AbortController/object URL/localStorage。

这是一份资源生命周期专项报告，不把其他文件中的性能结论当成已复核事实，也不读取测试实现来反推生产行为。

### 2.2 实际逐段阅读的生产文件

按主题列出实际阅读过的文件；路径均相对仓库根目录。

- AI/SSE/运行态：`ai/run/RunRegistry.java`、`SseChatEmitter.java`、`RedisFrameStore.java`、`RedisFrameWakeBus.java`、`FrameWakeListener.java`、`CancellationRegistry.java`、`CancelledRunFinalizer.java`、`LeaseService.java`、`RunStore.java`、`ResumeService.java`、`ResilientChatService.java`、`StartupResumeRunner.java`、`StreamWatchdog.java`、`TurnKeepalive.java`、`ToolActivityBeacon.java`、`ClientRequestRegistry.java`、`SessionKeyRegistry.java`（均在 `backend/src/main/java/com/example/configmgr/` 下）。
- 线程、身份与停机：`ai/exec/AiExecutorConfig.java`、`ai/gate/SpToolCallingManager.java`、`ConfirmGate.java`、`ToolResourceLocks.java`、`ai/session/SessionGate.java`、`SessionPurgeRetryRunner.java`、`ai/tenant/RequestContextUtils.java`、`TenantIdentityFilter.java`、`RequestContextTaskDecorator.java`、`ai/observe/AiTrace.java`、`AiMdcTaskDecorator.java`、`config/AsyncConfig.java`。
- 任务/SSE/作业：`task/service/TaskSseService.java`、`RedisTaskEventBus.java`、`TaskService.java`、`task/config/TaskEventDispatchConfig.java`、`TaskSseSchedulerConfig.java`、`task/controller/TaskController.java`、`FileController.java`、`job/service/AsyncJobExecutor.java`、`JobService.java`、`JobCancellationRegistry.java`、`JobHeartbeatRegistry.java`、`JobWatchdog.java`、`ExportJobRunner.java`。
- 文件/Excel/KB：`file/FileStorageService.java`、`FileStorageMultiInstanceGuard.java`、`excel/ExcelReader.java`、`ExcelWriter.java`、`ExcelTemplateBuilder.java`、`ai/kb/KbService.java`、`KbConfig.java`、`KbSampleSeeder.java`、`LocalDirKbSource.java`、`HttpKbSource.java`、`KbTools.java`、`AiKbController.java`、`CachedKbSource.java` 的调用面由 `KbService` 一并核对。
- 配置/附件/销毁：`ai/config/AiProviderRegistry.java`、`AiConfigService.java`、`AiConnectionProbe.java`、`AiModelCatalog.java`、`ai/web/AiConfigController.java`、`ai/form/RedisFormAttachmentStore.java`、`GenerativeFormAttachmentController.java`、`GenerativeFormGuard.java`、`ai/session/AiSessionPurgeService.java`、`ai/web/AiSessionPurgeController.java`、`ai/observe/RedisDegradation.java`、`SettlementObservationStore.java`。
- 健康/DB 连接：`health/ReadinessProbeRunner.java`、`ReadinessRunnerConfig.java`、`config/FlywayConnectionPin.java`、`FlywayStartupLock.java`、`PostgresMigrationConfig.java`、`db/migration/V6__Task_child_fk.java`。
- 配置：`backend/src/main/resources/application.yml`、`application-cluster.yml`、`backend/pom.xml`、`frontend/package.json`。
- 前端：`frontend/src/api/sse.ts`、`api/tasks.ts`、`api/http.ts`、`utils/job-watch.ts`、`utils/polling.ts`、`utils/excel.ts`、`utils/spreadjs.ts`、`utils/markdown.ts`、`utils/form-draft.ts`、`stores/ai.ts`、`stores/task.ts`、`stores/workspace.ts`、`components/SpreadGrid.vue`、`components/AiPanel/AiPanel.vue`、`ReasoningHeader.vue`、`CompactionStatus.vue`、`App.vue`、`views/ImportWizardView.vue` 与 `ExportWizardView.vue` 的注册/卸载及作业跟踪段。

## 三、完整入口到资源与返回的调用链

以下链路是后续问题定位的共同底座；没有 Redis/DB 操作的环节明确写“无”。

### 3.1 AI 首轮、重挂与终态释放

1. 浏览器 `AiPanel.vue` → `stores/ai.ts` → `frontend/src/api/sse.ts:861-970`：`POST /api/ai/chat` 用 fetch + ReadableStream；首帧/帧间/建连定时器分别在 `readStream` 的 finally 清理。
2. 后端 `TenantIdentityFilter.java:115-150` 建立并关闭身份作用域 → `AiController.java:330` → `AiController.java:544-564` 创建无传输死线的 `SseEmitter`、经 `RunRegistry.of` 取得写出器，再提交 `aiRunExecutor`。
3. `ResilientChatService` 驱动模型/工具；`RunStore` 和 `RedisFrameStore` 写 Redis 快照、序号和事件 Stream；`AiProviderRegistry` 复用当前 provider 的 HTTP 运行态；`StreamWatchdog`、`TurnKeepalive` 使用独立调度器。
4. `SseChatEmitter` 将帧写入每订阅者队列，由共享 delivery pool → 有界 writer pool → Servlet emitter 返回浏览器；跨实例经 `RedisFrameWakeBus` Pub/Sub 唤醒其他实例读归档。
5. 正常/异常出口由 `ResilientChatService.java:945-952` 等 finally 清取消监听与工具信标，由 `RunRegistry.close` 和 `SseChatEmitter.complete` 收订阅、停保活并摘注册表。
6. 重挂链为 `stores/ai.ts` → `sse.ts:1004-1278` → `GET /api/ai/events/{runId}` → `AiController.java:1105-1137` → `RunRegistry/SseChatEmitter` 分页读 Redis 归档 → 同样的队列/写池 → 返回流；所有重连 timer 在 `settle` 中取消。

### 3.2 任务作业、SSE 与轮询

1. `ExportWizardView.vue`/`ImportWizardView.vue` 或 `stores/workspace.ts` → `JobService` 创建 DB 作业并在事务回调中提交 `AsyncJobExecutor`。
2. `AsyncConfig.java:66-87` 的 `applicationTaskExecutor` 执行 `AsyncJobExecutor.java:28-45`；runner 读写 DB/文件，经 `JobHeartbeatRegistry` 写 Redis 活跃键/心跳，经 `JobCancellationRegistry` 读写取消键。
3. 浏览器 `job-watch.ts:85-286` 建 `EventSource` → `TaskController.java:192-195` → `TaskSseService.subscribe`；首订阅经 `RedisTaskEventBus` 创建/复用 Redis listener container。
4. runner → `TaskSseService.publish` → 本机 emitter 表 + Redis PUBLISH → 接收实例的 listener/dispatch pool → `SseWriteBudget` writer pool → HTTP SSE 返回。
5. 快照兜底为 `job-watch` → `jobsApi.getJob` → DB → JSON；终态或 dispose 清 EventSource、轮询、可见性监听及看门狗。

### 3.3 普通文件与表单附件

- 普通上传：`ImportWizardView.vue:1063` 的 `uploadOne` → `POST /api/tasks/{taskId}/files/upload` → `FileController.java:63-97` 在事务中锁任务行 → ZIP 解压或 xlsx 流读 → `FileStorageService` 写共享/本地文件 → `TaskFileRepository` 写 DB → 事务回调清旧件/回滚新件 → JSON 返回。
- 普通下载：`GET .../download`、`download-all` → DB 查 `TaskFile` → `FileStorageService.read`/`buildZip` → byte[] HTTP 响应。
- 表单附件上传：`POST /api/ai/form/attachments` → `GenerativeFormAttachmentController.java:116-221` 先从 `RunStore` 验证 Redis 待决状态与会话归属 → 文件落盘 → `RedisFormAttachmentStore.register` 写登记和两类 Redis SET 索引 → 返回 fileId。
- 表单提交/放弃：AI 回灌入口 → `ConfirmGate`/相关结算 → `GenerativeFormGuard.java:367-421` 或 `437-469` → Redis 登记/墓碑/索引 + 物理删除 → 返回既有结算结果。
- 会话销毁：`DELETE /api/ai/session/{sessionId}` → `AiSessionPurgeController.java:71-103` → `AiSessionPurgeService.java:461-540` → DB/Redis/run/附件删除；附件在 `1439-1494` 按登记路径删盘；部分失败进入 durable pending，由 `SessionPurgeRetryRunner` 定时单飞重试。

### 3.4 KB 与模型配置探测

- KB 启动：`KbService.start` → local/HTTP source（可套 Redis cache）→ 单线程 scheduler → 新目录中建 `IndexWriter` → `DirectoryReader` → volatile snapshot；查询经 `KbTools` → acquire/release snapshot → 工具结果返回模型。管理入口 `AiKbController.java:43-48` 同步调用 `refreshNow(true)`。
- 模型探活：`AiConfigController.java:137-168` → `AiConnectionProbe`/`AiModelCatalog` → JDK HttpClient 异步请求 + 请求线程 `future.get` → 可读 Redis 模型缓存 → 返回稳定结果视图。

## 四、逐项问题、根因与修复

### RL-01 高：Redis Pub/Sub 启动失败未销毁半初始化容器

- 确定性：代码证实。具体遗留在某一失败点是否已经建连接，取决于 Spring Data Redis 的内部进度，但“失败后没有 destroy”确定存在。
- 证据：`RedisTaskEventBus.java:403-424` 在 try 内 new、`afterPropertiesSet()`、`start()`，catch 直接返回 null；`RedisFrameWakeBus.java:248-269` 完全同形。两者都在后续订阅时重试创建，见 `RedisTaskEventBus.java:390-400`、`RedisFrameWakeBus.java:229-245`。
- 调用链：AI 重挂/任务订阅 → `container()` → `startContainer()` → Redis connection/listener executors → 启动异常 → null → 上层有界重试 → 再次 new container。
- 触发条件：Redis Cluster/单例不可用、订阅连接失败、执行器装配或启动异常。多实例会按“实例数 × 订阅重试”放大。
- 影响：半初始化容器可能保留订阅任务、连接和对当前 bean 的引用；反复故障下连接/线程与堆占用上升。不能仅凭源码断言每次失败都必然新增线程，但失败路径没有资源所有权闭合。
- 根因：局部变量 `target/container` 只在 try 内可见，catch 没有保存并销毁；把“返回 null”误当成等同于“未分配资源”。
- 最佳修复：把容器引用移到 try 外；catch 中按实际进度幂等 `destroy()`，失败也保留原始异常；只有完整启动并发布到字段后才允许共享。自有执行器若仅为失败容器创建，也应按所有权关闭或在下一次复用前明确保留策略；Spring 注入池不得由总线关闭。
- 备选：直接复用 Spring 管理的 listener container 而不是手工懒建，减少生命周期代码；代价是失去“无订阅不建连接”的懒启动语义。
- 修改点/影响面：上述两个 `startContainer`；不改变频道、消息或 SSE 协议。
- 后续验证（仅方案）：分别在 `afterPropertiesSet` 前、`start` 连接中和启动后注入失败，重复订阅并比较 container、Redis 连接和线程计数；再验证成功重试不会复用已毁实例。单例与 Cluster 都做，至少两个应用实例交叉订阅。

### RL-02 高：附件 TTL 到期不等于文件回收，删盘失败也没有持久重试

- 确定性：代码证实。
- 证据：
  - 上传先落盘再写 Redis 登记/索引：`GenerativeFormAttachmentController.java:181-197`。
  - 登记、墓碑和索引有 TTL：`RedisFormAttachmentStore.java:145-188`、`107-112`。
  - `GenerativeFormGuard.java:424-429` 明确写出“失效文件没有回收入口”；`443-469` 也说明收口失败后只能等 TTL。
  - `FileStorageService.java:191-204` 明确“不重试、没有扫描器”；`delete` 在 `182-188` 吞掉 IOException。
  - 显式会话销毁能删附件，但只覆盖用户主动 DELETE：`AiSessionPurgeService.java:1439-1494`。
- 触发条件：用户上传后关闭页面/会话闲置至 TTL；登记表单解析失败；消费或收口时 Redis/文件系统失败；共享盘暂时不可用；多实例任一节点接收到清理请求。
- 影响：Redis 元数据最终消失，而文件仍可长期占盘。`FileStorageService.delete` 吞 IOException 会使一般调用方无法据此建立可靠重试。多实例共享盘不会自动修复，反而会让“哪个实例负责重试”没有答案。
- 根因：把易失的登记 TTL 当成物理文件生命周期管理；文件与元数据是两套资源，但没有 durable 回收台账、到期扫描、条件删除或失败重试。
- 最佳修复：新增 durable 文件回收台账（可用 DB 表，或复用现有带到期 ZSET 的 Redis pending 模式），登记时写 fileId、相对 storage key、归属、状态和 reclaimAt；消费/取消/会话销毁成功后标记完成；到期 worker 按记录身份和当前登记状态条件删盘，失败指数重试并保留台账。用 `LeaseService` 做每 worker 单飞、每文件唯一 owner/fence，Cluster 下不用 SCAN/多键 DEL。
- 备选：只加共享目录定时扫描会少改业务写口，但无法安全区分活跃文件与孤儿文件，可能误删；不推荐作为首选。只延长 TTL 只推迟磁盘增长。
- 修改点/影响面：`RedisFormAttachmentStore`、`GenerativeFormGuard`、`GenerativeFormAttachmentController`、`AiSessionPurgeService`、`FileStorageService`，新增回收 runner/台账及容量指标；需保持附件一次性消费和会话归属不变。
- 后续验证（仅方案）：覆盖闲置过期、上传后放弃、重复上传、消费成功、删盘失败后恢复、会话销毁与重试；两个实例交替处理同一文件，验证只删一次且绝不删活跃件；核对台账最终清空和磁盘字节下降。

### RL-03 高：ZIP 上传解压无输出上限并全量驻留，且持有 DB 事务/任务锁

- 确定性：代码证实。
- 证据：`FileController.java:63-76` 在 `@Transactional` 中先 `lockTask`，再 `file.getBytes()`；`178-196` 对每个 entry 调 `zis.readAllBytes()` 并加入列表，全部 entries 到 `80-93` 才逐个处理。`application.yml:28-34` 的 5MB multipart 限制只约束压缩包输入，不约束解压后总字节、条目数或单件展开大小。
- 调用链：浏览器上传 → HTTP multipart → DB 行锁/连接 → 全量 ZIP bytes → 每个解压文件 byte[] + 总列表 → 写盘/DB → JSON 返回。
- 触发条件：高压缩比 ZIP、大量条目、单个超大展开文件，或并发上传。无需 Redis；单例和 Cluster 的后端堆风险相同，多实例是线性放大。
- 影响：堆内存可在请求结束前达到展开总大小的多倍，出现长 GC/OOM；DB 连接和任务锁在解压期间被占用，其他写操作排队。即使未 OOM，也会产生持续内存压力。
- 根因：把压缩输入大小误当成资源上限；使用全量 byte[] 聚合而非逐条有界流式处理。
- 最佳修复：在 ZIP 入口设置条目数、单件展开字节、总展开字节和压缩比上限；用固定缓冲逐条读取，超限立即终止；每个匹配文件依次“落临时/目标键 → DB → 释放缓冲”，不要先构造全量列表。事务边界应缩短到单件 DB 写入，避免整个解压过程持任务锁；如需整单原子性，另用明确的有界批事务，不能让无界 I/O 占锁。
- 备选：先解压到受配额限制的临时目录再入库，可降低堆峰值，但增加磁盘、句柄和临时目录清理责任；必须 finally/回滚清理。
- 修改点/影响面：`FileController` 上传与事务边界、文件存储临时件接口、上传配置；前端限制仍只能作为体验层，后端才是权威。
- 后续验证（仅方案）：以高压缩比、超多条目、超限单件和并发 ZIP 做堆/DB 锁验证；断言请求在阈值处快速失败、已写临时件被清、任务锁与连接及时归还。这里只列验证设计，不执行压测。

### RL-04 高：KB 清理会把其他实例的活 Lucene 目录当成 stale 删除

- 确定性：代码证实；触发前提为多个实例/线程共用同一个 `app.ai.kb.index-dir`。删除后的具体异常取决于文件系统和 Lucene 当时打开的文件。
- 证据：`KbService.java:389-403` 每次新建目录前调用 `cleanupStaleBuildDirs`；后者只排除 `this.snapshot.dir`，其余所有 `build-*` 都删除。它没有其他实例的 live/building 清单或文件锁。`application.yml:320-348` 默认 index-dir 为相对路径；`FileStorageMultiInstanceGuard` 只管 `app.file.storage-path`，并不校验 KB index-dir。
- 调用链：实例 A 定时/手动 reload → `newBuildDir` → 枚举共享目录 → 删除实例 B 的当前 reader 目录或正在写的 build 目录 → A 继续构建；B 的查询/刷新仍引用原路径。
- 触发条件：同一主机同工作目录起多个实例，或多个容器把同一共享卷挂到同一 index-dir。单实例不同目录无此问题；Redis 单例/Cluster 不改变本地目录互斥缺口。
- 影响：B 可丢索引文件、刷新失败或保留无法继续使用的 reader；A 也会把 B 未完成的索引删除。多实例下每次刷新都可能互相破坏，同时清理失败会留下目录堆积。
- 根因：把进程内 `snapshot` 误当成全系统唯一 live 集；没有声明“每实例独占目录”，也没有跨进程锁/owner 标记。
- 最佳修复：首选每实例独立 index-dir（部署侧包含实例 ID，或运行时在配置的根目录下创建实例子目录），只清自己的子目录；并在启动时明确校验/文档化独占语义。若必须共享，则引入跨进程目录锁和带 owner/heartbeat 的 live 清单，清理前确认未引用。
- 备选：只给 `cleanupStaleBuildDirs` 加文件锁只能序列化清理，不足以识别其他实例当前 reader；不能作为完整修复。
- 修改点/影响面：`KbService.newBuildDir/cleanupStaleBuildDirs`、KB 配置和部署配置；不影响 Redis cache 的内容一致性。
- 后续验证（仅方案）：两个实例共用目录并同时 reload/查询，验证互不删目录；再改为独占目录验证重启、崩溃残留、热重载和旧 reader 延迟关闭。Windows 与 POSIX 文件系统都应覆盖，因为删除打开文件的表现不同。

### RL-05 高：文件下载把源文件和 ZIP 全部聚合到堆

- 确定性：代码证实。
- 证据：`FileController.java:273-355` 的下载全部/多选先查 DB，再逐文件 `fileStorage.read`；`FileStorageService.java:133-135` 是 `Files.readAllBytes`；ZIP 写入 `ByteArrayOutputStream`，结束又 `bos.toByteArray()`。单文件下载也在 `FileController.java:201-218` 返回完整 byte[]。
- 调用链：HTTP 下载 → DB 查文件行 → 每个源文件 byte[] → ZIP 缓冲 → 最终重复 byte[] → ResponseEntity → Servlet 写响应。
- 触发条件：大导出文件、多文件、多选/全选、多并发或慢客户端。文件量随任务历史增长；Redis 拓扑无关。
- 影响：峰值至少包含源文件、ZIP 内部缓冲和 `toByteArray` 副本；响应发送期间整个 byte[] 仍由响应持有。慢客户端延长占用，线程/堆压力可演变为 OOM。
- 根因：用内存字节数组替代流式 HTTP/文件 API；没有下载字节预算或背压。
- 最佳修复：单文件和 ZIP 都用 `StreamingResponseBody`/Servlet 输出流；逐文件 try-with-resources 打开输入流并固定缓冲拷贝，ZIP 直接写响应，不再 `toByteArray`。DB 只读元数据，不把实体/文件内容装入额外集合。设置下载并发和文件大小/总量配额。
- 备选：先写临时 ZIP 再流式返回，可把堆换为磁盘，但必须有关闭和临时文件删除；不能解决慢连接本身，仍需写超时。
- 修改点/影响面：`FileController` 四个下载/模板入口、`FileStorageService` 增加流读取接口；响应 content-length 可能改为未知/ chunked，需要前端确认下载兼容。
- 后续验证（仅方案）：大文件、多文件和慢客户端下观察堆峰值与响应完整性；断言输入流和输出流在成功、断开、写失败时都关闭；多实例共享盘交叉下载同一文件。

### RL-06 中：KB 停机与在途刷新竞态可留下无人退休的新快照

- 确定性：代码证实。
- 证据：`KbService.java:191-210` 的 `stop()` 不与 `refreshNow` 的 synchronized 锁同步；它先退休当前快照并关 analyzer。与此同时 `refreshNow` 在 `287-367` 可继续执行，`buildSnapshot` 成功后于 `352-355` 把新快照重新写回 `snapshot`；没有 stopped 复查。新建 reader/directory 在 `409-458`，只有后续 retire/stop 才会关闭。
- 调用链：SIGTERM/Context 关闭 → `stop` → shutdownNow + 退休旧快照；同时 scheduler 或 `POST /api/ai/kb/reload` → 在途 refresh → 新 reader → 发布到已停止 bean。
- 触发条件：停机时正在 HTTP 回源、写索引或管理端手动 reload；不可中断 I/O 不受 shutdownNow 立即终止。
- 影响：新快照没有后续归属者，reader/directory 和目录可残留到进程真正退出；嵌入式/同 JVM 多 Context 下表现为跨 Context 泄漏。停机过程中 analyzer 还可能被并发关闭，造成刷新失败。
- 根因：stopped 只控制 acquire，不参与 refresh 的发布；stop 与刷新没有同一生命周期锁。
- 最佳修复：refresh 入口与最终发布前都检查 stopped；stop 先置 stopped、停止调度，再使用与 refresh 相同的锁等待在途刷新到既有 I/O 超时边界，最后摘快照并关 analyzer。若最终发布时发现 stopped，应立即 retire/close 新 built，不得赋给字段。
- 备选：只加 stopped 检查而不做锁/最终复查仍有 TOCTOU；只 shutdownNow 也不能替代资源退休。
- 修改点/影响面：`KbService.start/stop/refreshNow`；管理 reload 在停机中应明确返回未重建。
- 后续验证（仅方案）：在回源、writer.commit、reader.open、最终交换四个点注入暂停并触发 stop；断言最终 snapshot 为 null、所有 reader/directory 关闭、无新 build 残留，管理请求有明确结局。

### RL-07 中：LeaseService.ended 对故障轮次只增不减

- 确定性：代码证实。
- 证据：`LeaseService.java:368-397` 声明 `ended`；字段注明确只有成功 acquire/release 和进程 shutdown 清理，没有 TTL/容量；`955-960` 用 merge 写入。`ResilientChatService.java:2606-2615` 的统一终态清理又明确“不清 ended”。
- 调用链：AI run 持租 → Redis 丢租/续租不可用 → 本地 held 视图变化 + ended 写入 → 新实例接管/旧轮终态 → 本进程 ended 仍保留。
- 触发条件：长期运行、反复 Redis 抖动、续租失败、实例接管。正常无故障路径不会积累同类 LOST 条目。
- 影响：每个参与过且丢租的 `(run, owner)` 留一个字符串键和状态，长跑 JVM 内存缓慢增长；多实例各自积累。Cluster 故障期会放大产生速度。
- 根因：为保证“曾持租但已丢”不会被误判为 NEVER_HELD，选择永久 sticky；但没有把权威终态当成安全回收边界。
- 最佳修复：不要 TTL 过期或 LRU 淘汰 LOST。增加按精确 run lease key 的清理接口，只有在权威终态已持久化、且后续所有写路径都由终态守卫拒绝后，才在统一终态出口删除 ended。先证明 `RunStore.save`、帧、台账、pending 等所有写口都存在该守卫，再接线。加 ended 容量指标和告警。
- 备选：达到硬上限时 fail-closed 拒绝相关写并告警，而不是淘汰；可防 OOM 但会牺牲可用性，只适合最后保险。直接清 Map 会重新打开陈旧写，是不可接受方案。
- 修改点/影响面：`LeaseService`、`ResilientChatService.clearTerminalRunState`、可能的会话销毁路径及健康指标。
- 后续验证（仅方案）：重复制造 LOST→终态→迟到写，确认内存条目回收且迟到写仍被拒；覆盖成功 acquire/release、UNAVAILABLE、跨实例接管和同 runId 不同身份。

### RL-08 中：ai:creq 的取消墓碑和遗留 RUN 字段没有字段级回收

- 确定性：代码证实。
- 证据：`ClientRequestRegistry.java:29-36` 明确 CANCELLED 不参与回收、RUN 永不回收；`100-118` 的 sweeper 只处理到期 DONE；脚本在 `142-148` 仅对 DONE 做 HDEL。`claim/cancel` 又会反复 PEXPIRE 整键，见 `180-202` 与 `215-250`。
- 调用链：`POST /chat` claim / 按 clientRequestId 取消 → 会话 HASH 新增 field → 终态 DONE 仅部分可清 → 后续每次操作给整个 HASH 续期 → Redis 返回。
- 触发条件：活跃会话长期反复取消；run 异常终止且 `complete` 没执行/失败，RUN 留下；持续新操作使整键 TTL 不到期。
- 影响：Hash field 与游标随历史请求数增长，HSCAN sweeper 工作量也随总字段增加；Redis Cluster 中该会话 HASH 落在单槽，大数据会集中到一个节点。不是 JVM 本地泄漏，但属于 Redis 内存生命周期缺口。
- 根因：防重/取消语义只有整键 TTL，没有字段时间戳和终态对账；活跃 RUN 被误当成永远活跃。
- 最佳修复：给 CANCELLED 和 RUN 值加入时间戳；将现有有界 HSCAN 扩展为按年龄/状态处理。RUN 不应仅凭年龄删：先在脚本外按记录身份读快照/终态，再以旧值为条件 HDEL 或转 DONE；取消墓碑需明确“迟到同 id 请求”的最长防重窗口，超过后允许回收。保持单键 Lua、Cluster 同槽和每拍扫描/删除上限。
- 备选：每 clientRequestId 独立带 TTL 键 + 会话 ZSET 索引可从结构上解决字段不失效，但改键族、销毁和 Cluster 路由，迁移成本更高。
- 修改点/影响面：`ClientRequestRegistry` 三个脚本和键值格式、`ResilientChatService.completeClientRequest`、会话销毁兼容旧值；前端协议无需改变。
- 后续验证（仅方案）：活跃会话中生成大量 DONE/CANCELLED/RUN，验证有界回收与防重窗口；覆盖崩溃遗留 RUN、迟到 complete、重复请求、取消先于 start、Cluster 单槽及会话销毁。

### RL-09 中：普通 xlsx 上传输入流未关闭

- 确定性：代码证实。
- 证据：`FileController.java:118` 直接把 `file.getInputStream()` 传给 `fileStorage.store`，没有 try-with-resources；`FileStorageService.java:89-100` 只消费输入流，不负责关闭它。对照附件上传 `GenerativeFormAttachmentController.java:181-185` 已显式关闭同类流。
- 调用链：`POST .../files/upload` → multipart 容器提供流 → `Files.copy` → 返回；异常/成功都没有本层 close。
- 触发条件：普通单 xlsx 上传，尤其 multipart 使用临时文件时；高频上传会累积到 GC/容器最终清理。
- 影响：文件句柄和底层流可能延迟释放，Windows 上还可能影响临时文件删除。具体回收时间取决于 multipart 实现，不能把“每次必然永久泄漏”写成事实。
- 根因：资源所有权在调用方，却依赖被调方或 GC；同仓附件路径已采用正确模式。
- 最佳修复：与附件路径一致，`try (InputStream in = file.getInputStream()) { stored = fileStorage.store(in, originalName); }`，保留现有事务回滚清理。
- 备选：在 `FileStorageService.store` 内统一关闭会改变所有调用方的所有权约定，不建议仅为此处这样做。
- 修改点/影响面：`FileController.upload` 单 xlsx 分支，无协议影响。
- 后续验证（仅方案）：成功、读失败、写失败、DB 回滚后检查 multipart 临时文件/句柄可立即删除；重复上传观察句柄数不随次数增长。

### RL-10 中：模型探活响应无界读入，等待超时又不取消异步任务

- 确定性：代码证实。
- 证据：`AiConnectionProbe.java:158-163` 用 `HttpResponse.BodyHandlers.ofByteArray()` 后 `future.get`；`205-224` 自己承认整段 body 读完才截断。`165-174` 的 Timeout/Interrupted 分支没有 `future.cancel(true)`。`AiConfigController.java:137-168` 的测试连接和拉模型都进入这里。
- 调用链：设置页点击 → 配置服务/DB 读 provider → JDK HttpClient 任务/连接 → 请求线程有界等待 → 超时返回 UNREACHABLE；但底层任务未由本代码取消，响应仍可能聚合。
- 触发条件：上游持续大响应、慢流、DNS/代理异常、重复点击测试。请求超时可能最终终止部分任务，但本代码没有提供确定取消保证。
- 影响：请求线程按期返回不等于底层资源释放；后台任务、连接、响应 byte[] 可继续存活，重复操作造成累积。多实例各自发生，Redis 缓存不解决上游读取。
- 根因：把 `future.get(timeout)` 当作底层取消；把解析前截断误当成内存上限。
- 最佳修复：保存 future 并在 Timeout/Interrupted 时 cancel(true)，中断位照常恢复；改用 `ofInputStream`，try-with-resources 关闭 body，按固定缓冲读取到 `MAX_BODY_BYTES + 1` 即拒绝/取消，不让完整响应进堆。限制每实例探活并发。
- 备选：继续使用 ofByteArray 只能降低解析成本，不能解决内存；只 cancel 不限制 body 也只解决超时分支。
- 修改点/影响面：`AiConnectionProbe.fetchModels/boundedBody` 和可选并发闸；保持现有稳定错误码。
- 后续验证（仅方案）：慢响应、无限流、大响应、超时和中断后检查连接/任务结束与堆峰值；验证超大响应返回 MODELS_INVALID/UNREACHABLE 的既定映射，不误改业务结果。

### RL-11 中：RedisTaskEventBus 停机后仍可被并发订阅重建

- 确定性：代码证实。
- 证据：`RedisTaskEventBus.java:323-349` 的 `stop()` 没有 closed 标志；`390-400` 的 `container()` 在字段为 null 时可再次创建，且没有与 stop 共同的关闭状态检查。对照 `RedisFrameWakeBus.java:229-245` 和 `350-377` 已有 closed + 同锁复查。
- 调用链：Context 关闭 → stop 读旧 container/置 null/destroy；同时在途或迟到 subscribe → container() 看到 null → startContainer → 新容器不属于已执行的 stop。
- 触发条件：停机与首次订阅/重试并发；普通运行期不触发。多 Context 下旧 bean 引用更容易被迟到任务持有。
- 影响：新容器及其连接/任务在停机后无人 destroy；缓存的 `subscribers/listeners` 也可被新调用重新写入。
- 根因：只处理当前容器，没有“生命周期已结束”的不可逆状态；与同仓另一个总线的防护不一致。
- 最佳修复：增加 volatile closed；`subscribe/publish` 在 closed 时直接降级；`container()` 与 stop 使用同一把锁并在锁内复查；closed 后清本地订阅表。容器创建失败仍执行 RL-01 的补偿。
- 备选：仅依赖 Spring 不再接收请求不够，因为调度器重试/在途调用不经过 HTTP 新请求闸。
- 修改点/影响面：`RedisTaskEventBus` 生命周期与订阅重试交互；关闭后本地既有 SSE 不需要再接收跨实例消息。
- 后续验证（仅方案）：在 stop 前、destroy 中、stop 后分别并发 subscribe，断言不新建容器、maps 不增长；同时验证正常运行期的懒启动不受影响。

### RL-12 低：SXSSFWorkbook 只 dispose 不 close

- 确定性：假设。源码确定只有 `dispose()`，但是否还存在可观察的 package/句柄驻留需结合当前 POI 实现验证，不把库内部行为写成事实。
- 证据：`ExcelWriter.java:30-69` 创建 `SXSSFWorkbook`，输出流在 `64-66` 关闭，finally 只 `wb.dispose()`；对照 `ExcelTemplateBuilder.java:27-66` 和 `ExcelReader.java:30-75` 使用 try-with-resources 关闭 workbook。
- 调用链：ExportJobRunner 完成过滤 → `ExcelWriter.write` → SXSSF 临时文件/内部 workbook → 输出文件 → finally dispose。
- 触发条件：导出写文件成功或异常都会进入；多线程导出会放大任何残留。
- 影响：`dispose` 的既定目的包含临时文件清理，因此不能把它误报为“临时文件一定没删”；风险仅是缺少完整 Closeable 生命周期，库内部 package 资源可能延迟到 GC。
- 根因：只使用一个清理方法，没有统一 try-with-resources。
- 最佳修复：以 try-with-resources 管理 `SXSSFWorkbook`，并保留必要的 dispose 语义；按当前 POI 版本的 close/dispose 合约避免重复清理异常。输出流和 workbook 的异常都要保留。
- 备选：若核实当前 POI 的 dispose 已覆盖全部资源，可只记录依据而不改；但统一生命周期更不易受升级影响。
- 修改点/影响面：`ExcelWriter.write`，不改变 xlsx 内容。
- 后续验证（仅方案）：成功/写失败/序列化失败下检查临时目录、句柄与文件可删除性；比较 close 前后资源计数，并验证导出内容不变。

### RL-13 低：两个局部缓存没有随业务删除/修复回收

- 确定性：代码证实；两项都只是小对象缓增，不是短期 OOM 证据。
- 证据与影响：
  1. `AiConfigService.java:123-126` 的 `unreadableWarned` 按 provider code 记录；`1024-1029` 只 putIfAbsent。读取快照/加载新配置的 `222-231` 不保留当前 code 集，也不在恢复可读后删除。长期新增/删除 provider 会积累，修复后仍会抑制再次告警。
  2. `frontend/src/stores/task.ts:233-237` 的 `knownTaskTypes` 是 Pinia store 寿命的 Record；`417`、`621-642` 写入，`427-435` 删除任务只刷新列表，不删缓存。长期单页签创建/删除大量任务会积累旧 id。
- 触发条件：反复配置 provider、修复解密后再次故障；浏览器长期不刷新且任务数持续增加。
- 单/多实例影响：后端每实例各一份，代码不共享；前端只影响当前页签，与服务端 Redis/Cluster 无关。
- 根因：缓存只有写入，没有与权威集合对账或删除出口。
- 最佳修复：后端在 applyLoad 时按当前 providers 的 code 做 retainAll，并在成功解析后删除告警位；前端在 removeTask 成功后删除对应键，必要时加小型 LRU/TTL。不要把provider code 当无限增长的全局事实源。
- 备选：仅加固定上限能控制内存但不解决告警状态陈旧，需同时处理状态复位。
- 修改点/影响面：上述两个文件；不改变 API 和展示协议。
- 后续验证（仅方案）：新增→故障→删除→重建同 code、修复→再故障，确认表大小回落且告警重新出现；前端删除任务后确认缓存无该 id，再创建任务不会读到旧类型。

### RL-14 低：无 Redis 模板的附件 fallback 没有 TTL/容量

- 确定性：代码证实；只影响 `redisProvider.getIfAvailable()` 为 null 的轻装配/特殊运行，不是当前默认生产 Redis 路径。
- 证据：`RedisFormAttachmentStore.java:121-125` 的 memory/consumed；`154-156` 登记直接 put；`225-232` 消费只新增永久 tombstone；`749-758` 查询该永久集合。没有按 `this.ttl` 清本地条目。
- 调用链：无 Redis 模板的附件上传 → memory → consume → consumed；对象只随 bean 结束而回收。
- 触发条件：特殊部署没有 StringRedisTemplate 且长期使用附件；标准生产配置有 Redis 时不走此分支。
- 影响：本地登记和消费墓碑随附件数增长；多实例下本地表本来就不是共享真源，也不能当作跨实例补偿。
- 根因：Redis TTL 语义没有映射到 fallback；本地表没有过期调度/容量。
- 最佳修复：若该装配仍是受支持运行形态，为本地 entry 加 expiresAt，读写时惰性清理并设硬上限；表单收口/会话销毁同时清 memory 和 consumed。若只允许轻量测试使用，明确禁止生产缺 Redis 的启动，而不是保留一个语义不完整的数据面。
- 备选：仅限制容量会丢登记/墓碑，可能破坏一次性消费，不如 TTL + 归属清理。
- 修改点/影响面：`RedisFormAttachmentStore` 本地分支；生产 Redis 分支不变。
- 后续验证（仅方案）：无 Redis 装配下推进虚拟时钟，验证登记/墓碑过期、活跃件续期策略明确、会话销毁清空；多实例不共享时不得将其误判为可用生产模式。

## 五、未发现同类问题的关键路径

以下是本次实际核对到、且不应因本报告其他问题而误改的路径。

- **AI emitter 注册表有终态与空闲双出口**：`RunRegistry.java:179-188` 设置终态回收钩子，`309-360` 做有节流的空闲扫描，`374-390` 在 close 时 complete 并清帧簿记；不是只增不减。
- **AI 订阅断开会清队列和跨实例订阅**：`SseChatEmitter.java:1250-1268` 清队列/回放页/计数并停保活，`4858-4878` 的 complete 幂等；共享池在 `833-877` 有 owner 驱动的关闭。`SseWriteBudget.java:241-299` 写池有 64 线程硬上限和停机释放。
- **取消/工具/续跑本地状态有明确 finally**：`CancellationRegistry.java:234-243`、`JobCancellationRegistry.java:68-75` 清 flags/listeners；`ResilientChatService.java:945-952` 清取消与 beacon；`AsyncJobExecutor.java:40-45` 清 runner/取消登记；`CancelledRunFinalizer.java:424-467` 的重试计数和 inFlight 有结束/耗尽出口。
- **任务 SSE 空表和重试句柄会回收**：`TaskSseService.java:582-590` 末订阅摘除并退订，`519-525` 取消 future；`TaskSseSchedulerConfig.java:55-66` 开 remove-on-cancel 并由 Spring shutdownNow。
- **Redis wake 正常停机有完整顺序**：`RedisFrameWakeBus.java:350-377` 先 closed，再 destroy container，再有界关两个执行器；该项不与 RL-01 的失败启动缺口矛盾。
- **Lucene 正常热替换使用引用计数**：`KbService.java:411-458` 构建失败关 reader/directory；查询 `559-560`、`724-726`、`815-817` 都 finally release；`1019-1035` 最后一个引用释放后关闭并删目录。需修的只是 RL-04 的共享目录与 RL-06 的停机竞态。
- **线程池主体均有界且有归属**：`AsyncConfig.java:66-87`、`AiExecutorConfig.java:93-207`、`TaskEventDispatchConfig.java:43-72`、`ReadinessRunnerConfig.java:16-20` 均有 Spring destroy；`ContextSummaryClient.java:216-235` 的池有界且 close。
- **DB/迁移连接清理完整**：`FlywayStartupLock.java:81-152` finally 释放并 close，解锁失败 abort；`V6__Task_child_fk.java:70-128` 的 Statement/ResultSet 均 try-with-resources；普通 JPA repository 未发现手工驻留 Connection/ResultSet。
- **前端 SSE/轮询/主要 observer 有清理**：`api/sse.ts:618-628`、`969-971`、`1065-1075` 清 reader/timer/abort 监听；`utils/polling.ts:239-248` stop 清 interval/catchup/visibility；`utils/job-watch.ts:267-282` dispose 幂等；`SpreadGrid.vue:336-359` 清 timer、ResizeObserver 并 destroy workbook。
- **前端渲染缓存有界**：`utils/markdown.ts:217-244` 高亮缓存 200，`535-568` 防抖表 64且 timer 可清，`845-846`/`1116-1124` 前缀缓存 16，`1178-1239` Mermaid 缓存 64并清 timeout。`spreadjs.ts:41-80` 动态 import promise 复用，失败不缓存。
- **静态降级计数不是动态键泄漏**：`RedisDegradation.java:111-124` 预注册固定事件；全仓生产调用点均使用常量，未保存异常对象。
- **Provider 缓存不是按请求增长**：`AiProviderRegistry.java:188-191` 只保留当前 runtime/api key，请求级派生复用 HTTP API；没有发现每轮新增缓存键。
- **其他输入流/临时目录有正确示例**：表单附件流、Yaml 资源流、Lucene Files.list/walk、KB 播种 FileChannel/FileLock 都使用 try-with-resources；这些示例可直接作为 RL-09 的修复范式。

## 六、统一后续验证计划（只写方案，不执行）

1. **资源基线**：分别记录 JVM heap/native memory、线程名与数量、打开文件数、Redis 键/字段数、共享盘字节、Lucene build 目录数；在单例 Redis 与 Redis Cluster、单实例与双实例下采用同一量测口径。
2. **故障注入点**：Redis listener start、Redis 写/删失败、文件删失败、KB 回源与索引交换、SSE 写超时、探活超时、ZIP 读中途、DB 回滚。每个故障都要验证“调用方结局”和“底层资源结局”分别成立。
3. **多实例/多线程**：交叉执行上传与下载、发布与订阅、取消与终态、reload 与 stop、删盘与重试；验证任务/租户/run 身份不串、旧 owner 不删新 owner 资源、没有重复回收活跃文件。
4. **长时间积累**：反复创建终态 run、取消请求、provider、浏览器任务与附件；确认对应 Map/Set/Redis field/本地文件最终回落，而不是只看功能返回成功。
5. **关闭完整性**：正常、异常、取消、断连、超时、停机六类出口都核对 close/cancel/destroy；关注 `shutdownNow` 只能发中断、不能保证 socket/native I/O 已结束，因此必须再观察最终资源状态。
6. **回归面**：AI 首帧/重挂/终帧顺序、任务 SSE+快照轮询、文件上传下载内容、附件一次性消费、KB 热重载、配置探活稳定错误码均应保持既有协议。任何性能改善不得以丢帧、误删文件或放宽 fencing 为代价。

本报告的所有验证项均未被本次审查执行；当前结论只基于上述基线的生产源码与配置。
