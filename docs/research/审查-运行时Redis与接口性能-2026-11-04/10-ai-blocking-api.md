# AI Controller 同步阻塞与接口性能审查

- 审查基线：`52b09364`（审查时工作树 HEAD 即该基线，未依赖其他分支或报告结论）。
- 审查方式：只读生产源码与配置，沿 Controller 入口追踪同步服务、Redis/DB、上游网络、文件和锁；为确认前端影响，另读了相关前端生产调用代码。
- 未执行测试、构建、CI、服务或压测，未修改任何生产/测试代码。本报告的耗时均为代码结构与已配置超时推导，不是运行实测。
- 确定性口径：**代码证实**指调用、顺序、锁、读取范围或配置可以直接从源码确认；**条件推算**指只有满足所述数据量、节点故障或慢 I/O 条件才出现对应耗时，不能理解为已复现事故。
- 严重度口径：**高** = 可使常用请求长期占用 HTTP 线程、越过常见前端等待预算或随数据量显著失控；**中** = 有明确放大但通常有单次超时/数据上限；**低** = 主要是可维护性或局部优化。本文没有仅凭猜测设立“严重”项。

## 一、结论摘要

1. **删除对话的最高优先级问题不是某一条 Cluster 命令必然报错，而是整个销毁编排无接口级总时限。** 每次 DELETE 同步执行 pending 登记、逐 run 枚举、两趟全键空间 SCAN、DB 计数与删除、活跃轮取消/收尾、最多 1.5 秒轮询、逐 run 删除、逐上下文键 `TYPE+DEL`、逐附件 Redis/文件清理及守卫收尾。单次 2 秒 Redis 超时不能约束总和；Cluster 多 master 只会扩大扫描和路由成本。代码证实可越过 30 秒的条件，但未做运行复现。
2. `POST /runs/{runId}/resume` 在返回 202 前并非只做投递：它在 HTTP 线程同步抢租、读状态、重建工具，必要时还能执行后端工具及模型摘要；`POST /startup-resume/scan` 还会串行处理多轮。这两条路径应优先收紧“HTTP 只做受理”的边界。
3. `GET /history` 的分页发生在全量 `LRANGE`、全量 JSON 解析和记忆对账之后；`GET /runs` 是无分页的多级 N+1；`GET /runs/current` 在一个读接口里叠加多组续期写、两遍待决读取和附件续期。这些都是常见刷新路径上的同步 Redis 放大。
4. `GET /health` 每次同步做 Redis PING 和租约 PING，前者在实例级 `synchronized` 内；配置版本检查/DB 重载也在另一把实例级锁内。它们不应简单异步化成陈旧健康结果，而应改为有界、单飞、明确区分“缓存状态”和“实时探测”。
5. `POST /kb/reload` 在 `synchronized` 内同步读全部变更文档、访问 Redis/HTTP、建 Lucene 索引；HTTP 源下可形成 `文档数 × 10s` 的串行等待，并阻塞同实例定时刷新和其他 reload。适合改为管理任务单飞受理，而非让 HTTP 线程承担完整重建。
6. 配置 `/test` 与 `/models/fetch` 的单次上游等待有 5 秒上限，**保留用户同步等待是合理的**；需要修的是缓存故障与上游/回写串联、并发重复拉取和响应体先全量入内存的问题，而不是盲目改成后台任务。
7. 上传是同步 Redis 校验 → 同步落盘 → Redis 登记 → 可选旧件回收，单件 5MB、单请求 6MB，有体量上限但无共享盘 I/O 总时限；建议保留上传结果同步语义并优化批量读取/存储超时，不建议把文件尚未可用来换取提前 200。
8. 纯内存的 `/tools`、`/tenants`、KB `/status`/`/docs`，以及有界的反馈 Top100 查询，没有发现需要异步化的阻塞问题。run 13 键同槽原子删除、逐 master SCAN 的完整性、附件 Cluster 单键命令和线程池身份传播等关键保护均已存在，不应在性能修复中退化。

## 二、范围、配置与实际阅读文件

### 2.1 指定 Controller 全覆盖

逐一审查了以下八个 Controller 的全部 HTTP 映射，包括 `AiController` 中容易遗漏的反馈、上下文、待决、取消状态和手工扫描接口：

- `backend/src/main/java/com/example/configmgr/ai/web/AiController.java`
- `backend/src/main/java/com/example/configmgr/ai/web/AiSessionController.java`
- `backend/src/main/java/com/example/configmgr/ai/web/AiSessionPurgeController.java`
- `backend/src/main/java/com/example/configmgr/ai/web/AiConfigController.java`
- `backend/src/main/java/com/example/configmgr/ai/web/AiToolsController.java`
- `backend/src/main/java/com/example/configmgr/ai/web/TenantDirectoryController.java`
- `backend/src/main/java/com/example/configmgr/ai/form/GenerativeFormAttachmentController.java`
- `backend/src/main/java/com/example/configmgr/ai/kb/AiKbController.java`

### 2.2 配置与前端等待边界

- `backend/src/main/resources/application.yml`：Redis 命令/连接超时均 2s（`:74-84`）；配置探活 5s（`:136-150`）；摘要单次 60s（`:191-196`）；运行池 20 且无等待队列（`:216-219`）；运行段预算 300s（`:219-237`）；扫描产出/尝试预算（`:251-275`）；KB 本地/HTTP、缓存锁和轮询配置（`:315-355`）；文件路径与多实例约束（`:473-483`）；SSE 写超时 15s（`:407-424`）。
- `backend/src/main/resources/application-cluster.yml:20-36`：Cluster 仍使用 2s 命令/连接超时，最多 3 次重定向；没有删除请求级 30s 总预算。
- `backend/src/main/resources/application-postgresql.yml:22-36`：PG 连接池 10、取连接 5s、锁等待 60s；该锁等待不是 SQL 总执行时限。
- `backend/src/main/resources/application-h2tcp.yml:25-36`：共享 H2 取连接 5s；URL 中锁等待 60s。
- `frontend/src/api/http.ts:116-145`：普通 Axios 请求总上限 60s。
- `frontend/src/stores/ai.ts:11253-11305`：新建对话先本地换号，再等待取消和 DELETE；旧会话浏览器镜像/草稿的响应驱动清理要等 DELETE 返回或失败兜底。因此慢 DELETE 不一定卡住新会话画面，但会延后旧身份的本地理清，并给重复操作留下窗口。

### 2.3 实际追踪的服务/存储/线程文件

以下为实际阅读全文或按调用点阅读关键实现段的文件；路径均相对仓库根目录：

- 会话与删除：`ai/session/AiSessionService.java`、`AiSessionPurgeService.java`、`RedisClusterScanSupport.java`、`SessionPurgePendingStore.java`、`SessionPurgeGuard.java`、`SessionPurgeDatabase.java`、`SessionPurgeRetryRunner.java`、`SessionGate.java`。
- 运行态：`ai/run/RunStore.java`、`ResumeService.java`、`StartupResumeRunner.java`、`ResilientChatService.java`、`SseChatEmitter.java`、`RedisFrameStore.java`、`RunRegistry.java`、`CancellationRegistry.java`、`ClientRequestRegistry.java`、`LeaseService.java`、`CancelledRunFinalizer.java`。
- 历史/上下文/身份：`ai/history/ConversationTranscriptStore.java`、`ai/memory/RedisChatMemoryRepository.java`、`ai/context/ContextBreakdownService.java`、`ContextCompactor.java`、`ContextLimitResolver.java`、`ContextSummaryClient.java`、`ai/tenant/TenantUserDirectory.java`、`RequestContextTaskDecorator.java` 的装配点 `ai/exec/AiExecutorConfig.java`。
- 配置：`ai/config/AiAvailability.java`、`AiConfigService.java`、`AiConfigWriter.java`、`AiConfigVersionPublisher.java`、`AiConnectionProbe.java`、`AiModelCatalog.java`。
- 文件/表单/选项：`file/FileStorageService.java`、`ai/form/FormAttachmentStore.java`、`RedisFormAttachmentStore.java`、`FormOptionSourceRegistry.java`、`FormOptionSource.java`、`definition/service/DefinitionService.java`。
- KB：`ai/kb/KbService.java`、`CachedKbSource.java`、`LocalDirKbSource.java`、`HttpKbSource.java`、`RedisKbCacheStore.java`。
- 历史结果视图：`ai/resultview/ListTasksResultViewProvider.java`、`ToolResultViewProvider.java`，确认历史通道使用持久化原文而非重新查业务库。
- 工具执行确认：`ai/gate/ConfirmGate.java`、`SpToolCallingManager.java`。

## 三、统一阻塞估算模型

下文的 `R` 是会话 run 数，`A` 是附件数，`D` 是 KB 文档数，`M` 是 Cluster master 数，`K` 是全局 run 索引中属于当前身份的候选数：

- Redis 每次同步调用都可能承担连接获取、网络 RTT、Cluster 路由/重定向和服务端执行；配置的 2s 是单次命令/连接层面的超时，不是接口 deadline。连续 `n` 次调用的故障等待可以按 `n × 2s` 形成条件性上界估算，但异常提前返回、退避和快速失败会使实际值更小，不能将该乘积写成每次请求的实测耗时。
- `SCAN COUNT 256` 只是每批提示，不是结果上限；`RedisClusterScanSupport.scan(..., maxKeys=0)` 明确不按结果数截断，会排完每个 master 的游标（`RedisClusterScanSupport.java:48-55,98-147`）。
- 文件 `Files.copy/delete/exists/readAllBytes` 与 Lucene 建索引均没有本项目接口级超时；DB 还受连接池、事务和锁影响。不能用 Redis 的 2s 去推算这些部分的上限。
- “前端影响”分为直接等待响应、刷新卡片迟迟不能校正、后台旧会话清理延后三类；不把 HTTP 线程占用等同于浏览器画面必然冻结。

## 四、完整入口 → 服务 → Redis/DB/线程 → 返回调用链

### 4.1 慢删除主链

```text
DELETE /api/ai/session/{sessionId}
  → AiSessionPurgeController.purge
  → RedisAvailability.isAvailable（同步 PING）
  → AiSessionPurgeService.purge（当前 HTTP 线程，固定 RequestContext 身份）
  → SessionPurgePendingStore.register（ZADD + 单键 Lua + ZADD）
  → enumerate：
      SessionGate.currentRunId / RunStore.sessionCurrentRunId / sessionRunIds
      → 每 run：GET 快照 + HGETALL pending/req/decided
      → stagingKeys：逐 master 全游标 SCAN
      → archiveKeys：逐 master 全游标 SCAN + 按 turnNo 补 0..2000 个精确键
      → 记忆/transcript/context/session 精确键探测
      → RedisFormAttachmentStore.findBySessionId（SMEMBERS + 每件 GET）
      → SessionPurgeDatabase.snapshot（SELECT COUNT）
  → SessionPurgeGuard.arm
  → SessionPurgeDatabase.delete（REQUIRES_NEW 的 DELETE）
  → cancelActiveRuns / ResumeService.cancelConfirmed（每活跃 run 多次 Redis/租约/归档操作）
  → awaitTerminal（50ms 轮询，总等待窗 1.5s，仅约束这一段）
  → reloadRunStatuses
  → deleteMemory + deleteTranscript
  → 每 run：RunStore.deleteRun（同槽 13 键 DEL + 索引清理）+ 四个观测键 DEL
  → deleteContext（每键 TYPE 后 DEL）/ deleteSessionRecordKeys
  → deleteAttachments（每件文件 exists/delete/exists + 登记及索引删除）
  → deleteDiscoveryKeys / guard.retainOrArmIfOwner / pending.scheduleVerify
  → 200 + complete/deferred/errors；Redis 前置失败才直接 503
```

证据主点：`AiSessionPurgeController.java:71-103`；`AiSessionPurgeService.java:350-418,461-540,566-685,739-829,843-980,1191-1268,1271-1495`；`RunStore.java:2811-2934`。

### 4.2 历史、run 列表/详情与 current

- `GET /history/{sessionId}`：`AiController.history` → `ConversationTranscriptStore.read` → 全量 `LRANGE 0 -1` → 全量解析/去重/排序 → 同步 `chatMemory.get` 对账 → 四次 `EXPIRE` 和截断键探测 → Controller 过滤并做内存分页 → 本页工具结果投影 → `RunStore.events`（有界列表）→ 200。证据：`AiController.java:2136-2192`；`ConversationTranscriptStore.java:230-283,425-438`；`RedisChatMemoryRepository.java:462-470`。transcript 上限是 5000 条/8MiB，见 `ConversationTranscriptStore.java:65-69`。
- `GET /runs`：`ResumeService.listRuns/discoverRuns` → `RunStore.allRunIds`（`SMEMBERS ai:runs` + 当前身份成员逐个 `EXISTS` + 死成员 `SREM`）→ 每 run 读快照、三组 pending/req/decided、台账元数据、取消标志、活动 Stream/锚点和心跳 → 汇总返回。证据：`AiController.java:1782-1789`；`ResumeService.java:479-557`；`RunStore.java:3120-3151,3367-3396,4101-4183,4843-4942`。
- `GET /runs/{runId}`：Controller 先 `ownedOrAbsent` 读一次快照 → `ResumeService.describe` 再读快照并反序列化完整消息 → 读 pending 和完整台账 → Controller `putArchiveRecoverability` 再读归档 → 200。证据：`AiController.java:1791-1799,1822-1853`；`ResumeService.java:650-676`；`RunStore.java:3967-3982`。
- `GET /runs/current?sessionId=`：`sessionCurrentRunId` → `get` → `touchActiveRun`（多组续期写）→ `touchSuspendedResources`（读 pending，并对 PENDING 表单附件做 SMEMBERS + 批量 GET + 批量 PEXPIRE）→ `PTTL` → 再读 pending → `archiveMaxSeq` → 200/503。证据：`AiController.java:1884-1965`；`RunStore.java:4515-4593,4713-4749,4962-4974`；`ConfirmGate.java:2305-2333`；`RedisFormAttachmentStore.java:329-475`。

### 4.3 chat、resume、手工扫描与确认回灌

- `POST /chat`：HTTP 线程做 AI 可用性/配置快照检查、Redis PING、可选 clientRequest 预读、purge guard、SessionGate Lua、可选 clientRequest Lua；随后 `aiRunExecutor.execute` 才把工作交给有界平台线程池并返回 SSE。工作线程再做会话/记忆/快照 Redis、可选摘要模型调用、流式模型和工具循环。证据：`AiController.java:329-625`；`AiExecutorConfig.java:93-113,210-218`；`ResilientChatService.java:681-954`。这是一条**已正确切开长模型调用与 HTTP 受理线程**的关键路径。
- `POST /runs/{runId}/resume`：Controller 先读快照核归属、PING → `ResumeService.resume` → 同步抢租/校验、读快照和强一致 pending、取消检查、SessionGate、写权限检查 → 重建历史和工具条目 → 可选压缩/摘要/写回 → 最后才 `suspendExecutor.execute` → 202/200/409/503。证据：`AiController.java:1998-2026`；`ResumeService.java:821-935,1102-1435,1621-1820`；`ContextSummaryClient.java:145-180`。
- `POST /startup-resume/scan`：`StartupResumeRunner.scan(true)` → 有界单飞抢锁（最多 1.5s）→ `listRunsForRecovery` → 按预算逐轮调用 `resume`/取消收尾/到期收敛 → 汇总返回。证据：`AiController.java:1766-1780`；`StartupResumeRunner.java:295-340,1213-1242`；尝试预算配置见 `application.yml:260-275`。
- `POST /confirm` 与 `POST /frontend-tool-result`：HTTP 线程同步续期、读条目、可选快照写回、抢决策 guard、写 pending、发归档/实时帧，再触发续跑。真正续跑由 `aiResumeTrigger` 异步执行，不在 HTTP 线程等模型；证据：`AiController.java:851-930,933-1065`；`ConfirmGate.java:1810-1876,2134-2233,2541-2645`。附件消费/回收仍在响应前同步执行，不能误称为整个方法已异步。
- `POST /cancel/{runId}` 与 `/cancel/client-request`：同步核归属和写 Redis 取消事实；需要接管时经 `CancelledRunFinalizer` 交棒，不等待模型收尾。证据：`AiController.java:1495-1637`；`CancelledRunFinalizer.java:281-317`。

### 4.4 events、health、配置、上传、KB

- `GET /events/{runId}`：同步读快照/归档末号、续期、建立唤醒订阅、读取首页回放，再返回 `SseEmitter`；生产回放首页使用元数据 + `XRANGE COUNT 256`，Redis 读在 `emitLock` 外，后续分页/写帧由投递链执行。证据：`AiController.java:1104-1205`；`SseChatEmitter.java:1482-1576,1868-1937,1990-2015,265-271`；`RedisFrameStore.java:1251-1256,1352-1379`。`?probe=1` 只做快照/归档读，不建 SSE（`AiController.java:1235-1284`）。
- `GET /health`：多次读取本实例配置快照 → `RedisAvailability.isAvailable` PING → `LeaseService.leaseOk` 再 PING → 聚合本进程指标 → 200 或 AI 不可用时 503。没有模型上游探活。证据：`AiController.java:2700-2803`；`RedisAvailability.java:71-100`；`LeaseService.java:1210-1235`。
- 配置读：`AiConfigController` → `AiConfigService.current` → 每实例每秒至多一次版本检查，必要时锁内 Redis GET + DB 三表读取 → 内存投影返回。配置写：校验/加密 → DB 事务（数据 + 版本 + 审计）→ 提交后 Redis INCR + 本实例同步 DB reload + 本地运行缓存淘汰 → 返回。证据：`AiConfigService.java:155-209,245-309`；`AiConfigWriter.java:238-277,680-709`；`AiConfigVersionPublisher.java:74-103`。
- `/providers/{id}/test`：配置快照取 provider/key → `AiConnectionProbe.fetchModels` → `sendAsync` 后当前 HTTP 线程 `future.get(5s)` → 200 携带 ok=false/true。`/models/fetch` 还可能先读 Redis 缓存、上游后写 Redis。证据：`AiConfigController.java:136-168`；`AiConnectionProbe.java:125-186`；`AiModelCatalog.java:61-123`。
- 上传： multipart 容器先接收请求 → Controller 校验 → RunStore 读 pending/快照 → Redis 附件索引/登记读取 → `FileStorageService.store` 的 `Files.copy` → Redis 登记和两个单键索引脚本 → 可选旧登记/旧文件删除 → 200 fileId。证据：`GenerativeFormAttachmentController.java:116-221`；`FileStorageService.java:68-99`；`RedisFormAttachmentStore.java:145-190,517-593`。指定 Controller 中没有附件下载映射；AI 包内检索也未发现下载映射，故本报告不虚构一条下载调用链。
- KB：`/status` 与 `/docs` 只读 volatile 快照；`/reload` → `KbService.refreshNow(true)`（方法级 synchronized）→ `CachedKbSource`/本地或 HTTP 源 → 逐变更文档读取 → Lucene 建索引 → 原子换快照 → 再次 status → 200。证据：`AiKbController.java:38-64`；`KbService.java:287-387,830-846`；`HttpKbSource.java:86-156`。

## 五、逐项问题、根因与最佳修复

### API-BLK-001：删除对话把无界扫描和全部清理放在 HTTP 线程，没有总 deadline

- **严重度：高。确定性：代码证实；超过 30s 是条件推算，未实测。**
- **触发条件**：会话有较多历史 run/压缩归档、任意 staging 键、附件；大键空间使 SCAN 游标增多；Cluster 某 master 慢；DB/共享盘慢；活跃 run 无法立即收尾；或用户/自动重试同时触发同会话销毁。
- **证据**：Controller 直接等待完整 `purge`（`AiSessionPurgeController.java:87-103`）；两趟扫描无结果上限（`AiSessionPurgeService.java:637,739-742,778-829`；`RedisClusterScanSupport.java:55-73,98-147`）；每个归档键 `TYPE+DEL`（`AiSessionPurgeService.java:1309-1362`）；逐附件文件与 Redis 操作（`:1439-1494`）；1.5s 只限活跃等待（`:911-948`），不覆盖其它阶段。
- **单例/Cluster/多实例/多线程影响**：单例大键空间也会慢；Cluster 要串行扫所有 master，且各精确键分布在不同槽，RTT 更多；多实例下 PING 和自动 runner 锁不能替代请求 deadline。pending 复用只是复用台账，并未在 `purgeInternal/executeDeletion` 周围建立“同会话执行单飞”，所以并发手工 DELETE 可与另一手工 DELETE 或正在进行的自动重试叠加 I/O；现有 owner/CAS 保护主要解决状态归属，不等于性能去重。
- **根因**：把“必须完整、可重试的持久化清理流程”误当成一次短 HTTP 事务；扫描发现面、精确删除、文件回收和活跃轮收尾全部同步串联，且没有统一阶段计时/预算。
- **最佳修复**：将 DELETE 改为**持久化受理 + 有界后台单飞执行**。请求线程只完成身份校验、pending 登记、守卫安装和必要的活跃取消受理，返回 `202 + purgeId + statusUrl`；由现有 durable pending/due ZSET 与 `SessionPurgeRetryRunner` 推进完整删除，新增只读状态查询或复用明确的状态投影。每个候选按阶段记录进度、最后错误和完成结论；同 `(identity,sessionId)` 用执行租约防止手工与自动双跑。身份必须来自 pending 记录并显式 `useContext`，不能依赖工作线程 ThreadLocal；现有 runner 已有这一身份恢复模式。
- **备选取舍**：若暂不能改 API，先做兼容缓解：用持久化归档索引取代两趟全库 SCAN；同槽/分组 pipeline 合并 `TYPE/EXISTS/DEL`；附件批量 GET；统一请求 deadline，到期返回当前契约的 `200 complete=false` 并保留 pending。它能降低常耗时，但共享盘/DB/活跃轮仍使“同步必然快回”无法成立，因此不是最终边界。仅把 `activeWait` 调小或把 Redis 超时调小都不是根修。
- **涉及修改点/影响面**：`AiSessionPurgeController`、`AiSessionPurgeService`、`SessionPurgePendingStore`、runner、新增状态 DTO/查询口、前端 `resetSession` 对 202 的处理；必须保留守卫、身份、幂等、DB 先删顺序及 60s 记忆复核语义。
- **后续验证（仅方案）**：构造 0/1/多 run、0/多附件、2000 个派生归档、大键空间、单 master 暂停、MOVED/TRYAGAIN、共享盘延迟、DB 锁和活跃 run 不终态；分别测 standalone/Cluster、单实例/三实例、重复 DELETE 与手工/自动竞争，记录分阶段耗时、Redis 命令数、HTTP 受理时间和最终残留；验证超时后 runner 仍能收敛且不会双删/漏删。

### API-BLK-002：resume 与手工扫描在“返回受理”前做了实际重建、工具和摘要工作

- **严重度：高。确定性：代码证实；具体是否触碰工具/摘要取决于快照状态。**
- **触发条件**：续跑有已批准/待执行后端工具、需要 L2/L3 压缩、Redis/租约慢；手工扫描一次处理多个可推进轮。
- **证据**：`ResumeService.resumeInternal` 直到 `suspendExecutor.execute` 才离开调用线程（`ResumeService.java:1102-1137,1301-1435,1621-1820`）；`SpToolCallingManager.resolvePending` 对 APPROVED/BACKEND 等状态会进入 `executeOnce`（`SpToolCallingManager.java:3371-3400`）；摘要调用单次可等 60s（`ContextSummaryClient.java:145-180`；`application.yml:191-196`）。扫描逐轮同步调用 resume（`StartupResumeRunner.java:1213-1217`）。
- **影响**：普通 HTTP 60s 前端上限可被单次摘要或工具耗尽；多轮扫描进一步累加。单例与 Cluster 都有；多实例虽以租约防双驱动，但只解决所有权，不缩短调用方等待。并发扫描有单飞，但抢到单飞的 HTTP 线程仍承担全流程。
- **根因**：异步边界画在“驱动模型”之前，而不是“恢复任务受理”之前；准备阶段的模型/工具/DB/Redis 已可能很重。
- **最佳修复**：把 HTTP resume 缩成“核身份 + 持久化/确认续跑意图 + 有界投递”，完整 `resumeInternal` 在有界恢复执行器运行；202 只表示已受理，真实结局由 run 快照、事件帧和状态接口提供。手工扫描返回 `202 scanId/deferred`，后台按现有 `scan-max-attempts` 和轮转推进。保留租约、fence、SessionGate、取消优先和幂等检查，不能把校验删成盲投。
- **备选取舍**：短期可只把压缩和工具重建移入恢复线程，改动较小，但 Redis/租约慢仍拖 HTTP；不推荐以“只给工具执行再加一个线程”作为完整修复，因为嵌套占用有界池可能减少有效容量。
- **涉及修改点/影响面**：`AiController`、`ResumeService`、`StartupResumeRunner`、`AiExecutorConfig` 的任务拆分与拒绝语义；前端必须区分“受理”和“已完成”。
- **后续验证**：覆盖 RUNNING/SUSPENDED/终态、已决工具、未决外部项、摘要触发、池饱和、他实例持租、取消先到和扫描多候选；断言 HTTP 受理不等待工具/模型，且无双驱动、无漏终帧。

### API-BLK-003：`GET /runs` 对全索引做同步多级 N+1，且无分页

- **严重度：高。确定性：代码证实。**
- **触发条件**：当前身份累计 run 多、索引有 stale 成员、每轮 pending/台账较大、Redis 跨节点 RTT 或部分读取慢。
- **证据**：全量 `SMEMBERS` 后逐成员 `EXISTS`（`RunStore.java:3120-3151`）；每轮继续读快照、pending、ledger、取消、活动和心跳（`ResumeService.java:512-557`）。列表 Controller 没有分页参数（`AiController.java:1782-1789`）。
- **影响**：命令数和响应大小随 `K` 增长；Cluster 下逐键跨槽路由，多实例并发刷新会对共享 Redis 重复放大；故障时不同读取的 lenient/strict 分支混合，既可能慢又可能得到不完整展示。
- **根因**：把诊断级全量投影当成恢复列表热路径；发现、存在性清理和详情构造混在一次请求中。
- **最佳修复**：新增按身份、游标和 limit 的轻量列表；首页只返回状态/时间等必要字段，pending/ledger/历史按需详情加载。存在性校验用 Cluster 安全的单键 pipeline，不在列表请求里做全量清理；stale 清理由既有节流后台路径负责。长期可把活跃/恢复候选放入按身份的 ZSET，以分数和游标读取，避免依赖全局 SET 全扫。
- **备选取舍**：只做 pipeline 不改 DTO，兼容最好但响应仍无界；只加 `limit` 而仍先 `SMEMBERS+EXISTS` 全扫是假分页，不推荐。
- **涉及修改点/影响面**：`RunStore` 新游标读取、`ResumeService` 轻量投影、`AiController.runs`、前端列表调用与兼容旧全量接口的期限。
- **后续验证**：准备 0/1/100/大量 stale 索引，验证每页命令数不随总索引线性增长、排序/游标稳定、身份隔离、终态/挂起不漏，以及 Redis 故障时明确 503/降级而非假空列表。

### API-BLK-004：`GET /runs/current` 在读路径叠加多组写续期和重复 pending/附件读取

- **严重度：高。确定性：代码证实；故障耗时为条件推算。**
- **触发条件**：SUSPENDED 当前轮、多个 PENDING 表单条目/附件、前端多页签或多次校正、Redis 部分命令慢。
- **证据**：入口顺序见 `AiController.java:1889-1964`；`touchActiveRun` 包含心跳面、current、run 索引、clientRequest、读取面及 SUSPENDED 活动写（`RunStore.java:4713-4749`）；资源续期先读 pending，再逐 PENDING 调附件 touch（`ConfirmGate.java:2305-2333`）；之后 Controller 又读一次 pending（`AiController.java:1912`）。
- **影响**：一次 GET 不是一次 Redis 读，而是十多条命令加附件相关命令；前端在 3 秒无结局后用它校正（`frontend/src/stores/ai.ts:347-356` 的常量及注释），慢响应会延后卡片恢复。单例同样受影响；Cluster 跨槽增加 RTT；多实例读侧续期写会竞争同一批键；多线程下这些命令都直接占 HTTP 线程。
- **根因**：把“读取恢复视图”“延长 TTL”“附件保活”三个职责捆绑到每次读取；未复用刚读取的 pending，未按时间/版本去重 touch。
- **最佳修复**：Controller 一次读取 pending 后复用；续期按 `(runId, resourceVersion)` 做短间隔节流，只有临近 TTL 或真实恢复动作才写；将 pending/req/decided 的同槽读取合并为单键脚本或 Cluster 安全 pipeline；附件 touch 已由逐件改为三次批量路径，应继续复用而不是再读一次。响应优先保证状态视图，TTL 失败按现有降级语义处理。
- **备选取舍**：完全删除读侧续期会破坏 park 无心跳时的数据寿命，不可取；把所有 touch 挪到无持久化后台任务会引入身份和丢失问题，也不必要。保留“有人还在看”的语义，只做节流和合并。
- **涉及修改点/影响面**：`AiController.runsCurrent`、`RunStore` touch API、`ConfirmGate.touchSuspendedResources`、`RedisFormAttachmentStore`、前端恢复频率；需要保持 RUNNING 不被读侧写成活动证据的 I5 约束。
- **后续验证**：测量 found=false、终态、RUNNING、SUSPENDED、无附件/多附件和多页签并发下的命令数与延迟；确认挂起 24h 边界、索引续期、附件寿命和 RUNNING 僵尸判据均未退化。

### API-BLK-005：历史分页在全量读取/解析/对账之后，属于应用层假分页

- **严重度：高。确定性：代码证实。**
- **触发条件**：长会话接近 5000 条或 8MiB；只请求很小 `limit`；前端刷新/滚动频繁；工具结果 JSON 较大。
- **证据**：`ConversationTranscriptStore.read` 先 `LRANGE 0 -1` 并逐条解析（`ConversationTranscriptStore.java:242-269`），还同步读记忆对账（`:264-283`）；Controller 随后才 `subList(from,to)`（`AiController.java:2174-2179`）。容量上限见 `ConversationTranscriptStore.java:65-69`。
- **影响**：`limit=1` 也承担全量网络、堆分配、JSON CPU 和 last-wins 对账；多用户/多实例重复刷新放大 Redis 出流量和 JVM 压力。Cluster 键同槽保证一致，但不降低大 value 读取成本。
- **根因**：展示历史用 append-only LIST 保存全部版本，读取时做全局去重和记忆补偿；新增分页只改了响应切片，没有改变存储读取模型。
- **最佳修复**：建立持久化、可游标读取的展示投影：以稳定 `entryId + turnStartedAtMs + 追加序号` 建有序索引，写入时完成 last-wins 物化，读取按游标/limit 取一页；记忆补偿和 TTL touch 与正文分页解耦。短期若不能改存储，可先提供 Redis 侧 `LRANGE` 真分页加明确的“当前页尚未全局对账”限制，但不能把它冒充现有全局去重语义。
- **备选取舍**：纯 `LRANGE offset` 改动小，却仍改变 last-wins/全局总数口径；保留全量读只优化投影不能解决网络与解析，故只能做过渡。缓存整份会话可降低 Redis 读，但多实例失效和内存成本更复杂，不是首选。
- **涉及修改点/影响面**：`ConversationTranscriptStore` 读写路径、条目索引、`TranscriptEntries` 对账、`AiController.history`、历史前端 total/hasMore 契约和旧数据迁移。
- **后续验证**：比较 10/500/5000 条、8MiB 边界、重复 entryId、挂起后终态重写、baseline+run 双写、压缩事件和工具结果视图；验证第一页/中间页/越界页、total/hasMore、truncated 和旧会话回落记忆均一致。

### API-BLK-006：KB reload 在实例级 synchronized 内做全部 I/O 与 Lucene 重建

- **严重度：高。确定性：代码证实；`D×10s` 是 HTTP 源慢响应条件推算。**
- **触发条件**：手动 reload 与定时刷新/另一 reload 重叠；大量变更文档；HTTP 源慢；Redis 缓存慢后回源；本地共享盘慢。
- **证据**：`KbService.refreshNow(boolean)` 整个方法 synchronized（`KbService.java:296-386`），其中 manifest 与逐文档 `fetchContent`、建索引均同步；HTTP 每篇读超时 10s（`HttpKbSource.java:106-156`；`application.yml:350-354`）；缓存冷启动他实例持锁时还有最多 30×100ms 本地等待（`CachedKbSource.java:219-228`）。
- **影响**：HTTP 线程等待完整重建；第二 reload 和 `kb-refresh` 线程排同一把锁。多实例的缓存单飞只限制部分回源，不消除每个实例自己的 Lucene 构建；本地源会每篇读文件算 sha，慢盘同样阻塞。
- **根因**：管理操作直接复用了后台刷新的同步方法，没有“受理—执行—状态”的任务边界。
- **最佳修复**：`POST /reload` 只登记一次 force 刷新并返回 202；由专用单线程/单飞任务执行现有 `refreshNow(true)`，`status` 增加 `refreshing/requestId/startedAt/lastOutcome`。`status/docs` 继续读旧快照，保持现有原子切换。并发 reload 返回已有任务或 409，不排队等锁。
- **备选取舍**：保留同步 reload 并做超时/文档并发可降低耗时，但改变读取顺序与外部服务压力，且 Lucene 仍无统一 deadline；仅把 synchronized 删掉会破坏刷新状态与旧索引退休，不可取。
- **涉及修改点/影响面**：`AiKbController`、`KbService` 调度与状态、`CachedKbSource` 既有单飞、管理前端按钮和状态轮询；不动检索快照的原子性。
- **后续验证**：本地/HTTP、缓存命中/冷启动/故障回源、文档全变/部分变、两个并发 reload、reload 与定时刷新重叠、重建失败保留旧索引；验证受理快、状态准确、检索始终读完整旧/新快照。

### API-BLK-007：配置版本检查与 DB 重载在全局 refreshLock 内，所有读配置接口会被一起排队

- **严重度：中高。确定性：代码证实；锁等待时长取决于 Redis/DB。**
- **触发条件**：每 1s 版本检查、配置版本变化、上次 DB 加载失败后的重试、写后强制 reload；Redis/DB 慢或连接池耗尽。
- **证据**：`AiConfigService.current` 在 `synchronized(refreshLock)` 内执行 `checkReload` 和 `load`（`AiConfigService.java:155-171`）；版本检查可 Redis GET 后回落 DB（`:245-280`）；reload 读 provider/model/setting（`:284-309`）。`health`、chat 和配置读都经过此快照；配置写提交后还同步 `reload`（`AiConfigVersionPublisher.java:88-103`）。
- **影响**：一把实例内锁同时服务 chat、health、providers/models、resume 和写配置；一个慢刷新会让其它线程等待。多实例各自有锁但共享 DB/Redis，故障时形成同向压力；Cluster 只改变 Redis 路由，不解决锁内 I/O。
- **根因**：用互斥锁同时表达“只加载一次”和“调用方必须等最新结果”，未使用单飞 Future/原子在飞状态；读路径本就有 last-good snapshot，本可避免所有读者同步陪等。
- **最佳修复**：读路径采用原子 single-flight：第一个线程在锁外执行有界刷新，其余请求直接读上一份好快照并标记 `stale/refreshing`；只有首次无快照且业务必须判配置时，才在明确短预算内等待。写路径仍保持 DB 事务同步，提交后可强制本实例刷新，但应复用同一个在飞刷新，避免与读线程重复 DB 三查。
- **备选取舍**：简单缩小 reload interval 只会增加 Redis/DB 频率；简单去掉锁会产生多线程重复加载。完全异步化配置写会改变“保存后本实例立即可用”的语义，不建议。
- **涉及修改点/影响面**：`AiConfigService` 刷新状态机、`AiConfigVersionPublisher`、health 的 stale 观测、配置读写和 chat/resume 可用性口径。
- **后续验证**：首次加载、last-good、持续 DB 失败、Redis 版本变化、多实例写后传播、并发 100 个读与单写；断言刷新只执行一次、读者不无界排队、无快照时不伪造可用。

### API-BLK-008：health 串行两次 PING，RedisAvailability 的锁会把并发健康/删除/chat 前置探活串起来

- **严重度：中。确定性：代码证实。**
- **触发条件**：健康检查频率高；Redis 慢；多个请求同时进入 PING；租约设施与通用模板同时异常。
- **证据**：health 在 `AiController.java:2761-2770` 依次调用两个实时探针；`RedisAvailability.isAvailable` 在 synchronized 内 PING，健康时正缓存为 0（`RedisAvailability.java:38-53,71-100`）；`LeaseService.leaseOk` 另发 PING（`LeaseService.java:1210-1235`）。
- **影响**：健康时每次 health 多两次 RTT；Redis 卡顿时，先到的调用持锁至命令超时，其它调用在锁外排队。负缓存 5s 可减少持续触网，但不能消除已进入锁等待的线程。多实例是每实例一把锁，共享 Redis 仍承受所有实例探活。
- **根因**：把实时运维诊断与业务前置可用性合在同一个同步对象；health 又重复探测同一基础设施。
- **最佳修复**：health 复用一次有界 Redis 探测结果，租约指标使用该结果或明确标注独立检查；业务前置探活改为原子 single-flight + 短结果复用，失败快速 503。health 响应分别展示 `observedAt/live/stale`，避免把后台缓存值冒充实时值。
- **备选取舍**：给正结果加较长 TTL 可减少 RTT，但会扩大“刚探活成功后 Redis 故障”的漏检窗口；只删掉 health 探活会让健康面失真。保留短窗口与有界单飞更平衡。
- **涉及修改点/影响面**：`RedisAvailability`、`LeaseService` 自检接口、`AiController.health`、chat/delete/context/resume 的前置失败路径。
- **后续验证**：健康、PING 慢、持续失败、恢复、并发 health+chat+delete；检查响应是否标明观测时刻、503 是否有界、恢复后是否及时回绿。

### API-BLK-009：run 详情重复读快照、读完整台账，首帧完整性检查还会先读整个有效事件窗口

- **严重度：中高。确定性：代码证实。**
- **触发条件**：快照消息/台账大；事件 Stream 接近有效窗口；旧数据没有台账元数据投影。
- **证据**：Controller 与 service 各读一次快照（`AiController.java:1791-1798`；`ResumeService.java:650-659`）；详情 `ledger` 用 `LRANGE 0 -1`（`RunStore.java:3967-3982`）；`putArchiveRecoverability` 调 `readFromWithStatus(...,1)`（`AiController.java:1822-1843`），该通用方法先 `streamFramesAbove` 再 `subList`（`RedisFrameStore.java:1226-1240`），后者按有效窗口读取（`:1290-1341,1415-1491`）。
- **影响**：一个详情请求传输/解析超过展示所需的数据；列表和详情叠加时 Redis/JVM 压力更明显。单例/Cluster 均有；多实例打开同一轮会重复读取共享大值。
- **根因**：诊断接口复用了通用全量读口，且缺少轻量首帧/元数据专用 API；归属读与详情读未共享快照。
- **最佳修复**：Controller 把已核归属的快照传给 describe；详情台账分页/摘要化，完整 ledger 另设显式诊断参数；归档完整性改用 `replayMeta` 或 `readRawFrom(...,1)` 的专用首帧语义，禁止通用读口先全窗再裁一条。
- **备选取舍**：只把 `limit=1` 改其它数值无效；必须改变读取实现。台账完全从详情删掉会影响排障，分页更稳妥。
- **涉及修改点/影响面**：`AiController`、`ResumeService.describe`、`RunStore.ledger`、`RedisFrameStore` 专用读口、前端详情分页。
- **后续验证**：大消息、大台账、空/单帧/满窗/截断窗、旧投影与资源故障；断言首帧检查命令读取条数有界、详情只做一次快照读取、归档 trimmed 判定不变。

### API-BLK-010：配置探活/模型拉取的串行降级会叠加等待，且响应体上限只保护解析不保护接收内存

- **严重度：中。确定性：代码证实。**
- **触发条件**：`/models/fetch?refresh=false` 缓存读超时后访问慢上游，成功后又遇缓存写超时；多实例/多用户同时刷新同一 provider；上游返回超大 body。
- **证据**：`AiModelCatalog.fetch` 依次配置读取、可选缓存、网络、写缓存（`AiModelCatalog.java:61-123`）；探针 HTTP 线程等待 5s（`AiConnectionProbe.java:143-186`）；`ofByteArray` 先完整接收，再 `boundedBody` 截 1MiB（`:158-163,205-224`）。
- **影响**：常规 `/test` 约受 5s 网络预算约束；fetch 的“缓存故障 + 上游 + 回写故障”会在同一 HTTP 请求串联等待。并发刷新产生重复上游调用；超大 body 可在解析前造成堆压力。Cluster 缓存共享是优点，但故障降级会同时放大各实例回源。
- **根因**：cache-aside 没有请求级总预算和 per-provider single-flight；body 限制位于完整接收之后。
- **最佳修复**：保留用户主动 `/test` 的同步 5s 结果；`refresh=false` 做 per-provider 单飞并允许旧缓存/明确 miss，设置“缓存 + 网络 + 回写”的总预算；`refresh=true` 的强制刷新仍由用户同步等待，不与无关强制请求盲目合并。响应体改 `ofInputStream` 或限流 BodySubscriber，在接收阶段强转 1MiB 上限；超时取消 future。
- **备选取舍**：把所有探活后台化会让设置页无法直接得到本次结果，不必要；仅增加缓存 TTL 会增大陈旧模型清单，不解决故障串联。
- **涉及修改点/影响面**：`AiConnectionProbe`、`AiModelCatalog`、设置页 loading/重复点击控制；无需改 provider 写事务。
- **后续验证**：缓存命中/未命中/读慢/写慢、上游 5s 超时、强制刷新并发、非 JSON、1MiB 边界和超大流式响应；断言内存接收有界、失败码不变、无重复回源风暴。

### API-BLK-011：上传与回灌收口含同步文件/多次 Redis I/O，需缩小工作量而不是提前返回假成功

- **严重度：中。确定性：代码证实。**
- **触发条件**：共享文件系统慢；附件索引 stale 多；重复上传触发旧件回收；表单回灌需要消费/清理多附件。
- **证据**：上传先读 pending、`findByForm` 为 SMEMBERS+逐件 GET、再读快照、`Files.copy`、登记与索引写，最后可删旧件（`GenerativeFormAttachmentController.java:142-220`；`RedisFormAttachmentStore.java:517-593`）；回灌在 `settleAccepted` 中同步消费/回收后才返回（`ConfirmGate.java:2228-2233,2254-2265`）。文件删除只吞 `IOException` 并记录，不能提供时间上限（`FileStorageService.java:182-189`）。
- **影响**：上传按钮和表单提交等待 Redis+磁盘；多实例下共享盘使相同慢 I/O 传播到不同实例。单件 5MB/请求 6MB 限制控制体量，但不控制盘响应时间。Cluster 下登记和索引采用单键命令，未因合并成 MGET/跨槽 Lua 而破坏正确性。
- **根因**：限额读取逐件执行，文件与元数据清理处于用户响应关键路径；没有存储级等待预算。
- **最佳修复**：上传仍同步返回可用 fileId，但用 pipeline 批量读取表单登记、复用快照/pending，减少重复命令；共享存储配置明确的连接/读写超时与监控。回灌必须先持久化决策；对不 submitted 附件的清理可复用持久化回收任务异步推进，消费本身仍需保持原子且不得因提前返回而重复消费。
- **备选取舍**：上传整体后台化要求新增上传任务和轮询，且客户端不能立即把 fileId 当可用值，复杂度不划算；简单删掉旧件回收会留孤儿，不可取。
- **涉及修改点/影响面**：`GenerativeFormAttachmentController`、`RedisFormAttachmentStore`、`FileStorageService`、表单 guard 的清理钩子、共享存储配置与前端上传/提交超时。
- **后续验证**：1B/5MB/请求 6MB 边界、并发替换、登记失败回滚、索引慢、共享盘慢、A 上传 B 回灌、重复消费和销毁回收；检查无孤儿、无跨身份读取、响应不等多余非关键清理。

### API-BLK-012：反馈和选项源是同步 DB 调用，当前应保持同步，不应为“统一治理”而异步化

- **严重度：低至中（作为容量注意项登记，不是已证实慢接口）。确定性：代码证实调用链；未证实慢查询。**
- **证据**：反馈写为查询后 save/delete，列表有 Top100 限制（`AiController.java:3085-3168`）；选项源 `findByCode/findByLevel` 到 `DefinitionService` 仓储查询（`FormOptionSource.java:60-82,118-153`；`DefinitionService.java:49-55`）。配置写也在 HTTP 线程执行 DB 事务和提交后发布，见 `AiConfigWriter.java:680-709`。
- **触发条件与影响**：DB 连接池耗尽、锁等待或慢 SQL 会占 HTTP 线程；PG/H2 多实例配置的连接/锁等待可成为主要尾部延迟。代码未给出这些接口实际慢的证据，不能据此声称已有故障。
- **最佳修复**：保留同步 CRUD/反馈/选项语义；设置 DB 总观测、索引和连接池指标，限制选项结果，配置写复用在飞刷新。只有出现真实长作业才引入任务化，不能把所有 DB 接口统一切后台。
- **备选/修改点**：暂无必须改代码项；若后续测量证实慢，再针对 SQL/索引和连接池处理，而非先改 Controller。
- **后续验证**：观察 SQL 耗时、Hikari 等待、锁等待和响应大小；覆盖反馈 upsert/rating=0/Top100 与两个选项源的 DB 异常口径。

## 六、逐接口同步边界与最佳方案总表

下表覆盖指定 Controller 的全部映射。耗时栏只写代码能支持的判断，不把未测数据写成延迟事实。

### 6.1 AiController

| 接口 | 同步阻塞来源与前端影响 | 最佳同步边界 |
|---|---|---|
| `POST /chat` | HTTP 前置有配置快照、PING、guard、SessionGate/clientRequest Redis；模型/压缩/工具在有界运行池，SSE 长等待不占 HTTP 线程。Redis 故障会推迟开流。 | 保持现状；压缩前置 PING/配置读取的锁等待，不能把模型重新同步回 Controller。 |
| `POST /confirm` | 续期、pending、guard、快照写回、发帧均同步 Redis；前端等待决策接受。 | 同步到“决策已持久化+续跑已投递”即可；复用 pending，非关键结算可持久化后移。 |
| `POST /frontend-tool-result` | 同上，另有校验、附件消费/回收；大结果有字符上限但 JSON/文件工作仍在 HTTP 线程。 | 不落库不提前成功；将非关键清理从响应关键路径拆出，保持原子消费。 |
| `GET /events/{runId}` | 快照、归档、续期、唤醒订阅和首页 Redis 读同步；前端建连被推迟。生产回放已分页且 Redis 读在 emitLock 外。 | 保持同步小首页与原子挂接；减少重复读/touch，勿为快进而破坏回放—订阅衔接。 |
| `GET /events/{runId}?probe=1` | 少量快照/归档同步读，不建订阅。 | 保持轻量同步；不为 probe 做全量回放或 touch。 |
| `GET /events/{runId}`（交叉产型 406） | 纯协议拒绝，无 Redis/DB。 | 保持现状。 |
| `POST /cancel/{runId}` | 快照/归档核归属、取消读写；交棒可再读身份，但实际收尾异步。 | 同步确认权威取消事实即可，不等待模型/文件。 |
| `POST /cancel/client-request` | 单键 Lua + 已知 run 的归属/取消链。 | 同上；保持取消墓碑与映射原子性。 |
| `GET /cancel/{runId}` | 快照/归档和取消键同步读。 | 保持同步小读；失败如实 UNKNOWN/503。 |
| `GET /startup-resume` | 主要是内存报告；AI 可用性可能触发配置锁内版本检查。 | 保持同步；依赖 API-BLK-007 消除刷新陪等。 |
| `POST /startup-resume/scan` | 单飞等待、全量候选发现及逐轮 resume/取消/到期收敛均在 HTTP 线程。 | 改后台扫描受理；返回 scanId/deferred，保留每拍尝试预算。 |
| `GET /runs` | 全索引和每 run 多级 N+1，响应无分页。 | 按 API-BLK-003 做游标轻量列表，不要后台返回不完整列表。 |
| `GET /runs/{runId}` | 两次快照、完整消息/台账、归档窗口读。 | 按 API-BLK-009 做单次快照和专用小读，保持同步详情。 |
| `GET /runs/current` | 多读 + 多组续期写 + 附件 touch + 再读 pending + archiveMaxSeq。 | 按 API-BLK-004 合并/节流，不能删除 park 续期语义。 |
| `POST /runs/{runId}/resume` | 投递前同步重建、工具和摘要，可能超过普通 HTTP 60s。 | 按 API-BLK-002 把完整恢复移到有界工作线程，202 只表示受理。 |
| `GET /history/{sessionId}` | 全量 transcript + 记忆对账 + JSON CPU，再内存分页。 | 按 API-BLK-005 做真实游标分页；同步返回完整一页。 |
| `GET /context/{sessionId}` | PING、记忆窗口、上下文状态；`ledger=1` 再读有界台账。 | 保持同步小读；复用配置/Redis 探测，失败不要把未知写成 0。 |
| `GET /health` | 配置刷新锁 + 两次 PING + 本进程指标，无模型网络调用。 | 按 API-BLK-007/008 有界单飞并标注观测时刻；不要静默返回陈旧“健康”。 |
| `GET /pending/{runId}` | 核归属快照 + pending/req/decided 三组同步读。 | 可复用 current/详情读取；保持小读和身份隔离。 |
| `POST /feedback` | 同步 DB 查询与 save/delete，无 Redis/模型。 | 保持同步；按 DB 指标定位真实慢点。 |
| `GET /feedback` | 同步 DB Top100，响应有界。 | 保持同步，无需异步化。 |

### 6.2 AiSessionController / AiSessionPurgeController

| 接口 | 同步阻塞来源与前端影响 | 最佳同步边界 |
|---|---|---|
| `POST /session` | 每次至少一次 Redis 原子登记；撞号最多三次，另有 1–4ms 有界抖动 sleep（`AiSessionController.java:90-145`）。 | 保持同步发号；SET NX 多实例语义正确，不要本地发号或无限重试。 |
| `DELETE /session/{sessionId}` | 完整 Redis/DB/文件/活跃轮清理同步，可能超过 30s 和普通 HTTP 60s。 | 按 API-BLK-001 持久化受理 + 后台单飞；过渡期至少去 SCAN、pipeline 和总预算。 |

### 6.3 AiConfigController

| 接口 | 同步阻塞来源与前端影响 | 最佳同步边界 |
|---|---|---|
| `GET /providers` | 配置快照；循环 modelsOf/keyStatus 主要读内存，版本 tick 可触发锁内 Redis/DB。 | 保持同步内存读，改 single-flight 刷新。 |
| `GET /models` | 同上；可用性/key 解密为本地计算，不访问模型上游。 | 同上。 |
| `POST /providers` | DB 唯一检查、加密、事务、提交后 Redis INCR + DB reload。 | 写事务保持同步；复用刷新，避免读者陪等。 |
| `PUT /providers/{id}` | DB 读改事务 + 提交后发布/reload；URL/key 校验本地。 | 同上。 |
| `DELETE /providers/{id}` | 模型引用查询 + DB 软/硬删事务 + 发布/reload。 | 同上。 |
| `POST /providers/{id}/test` | 当前 HTTP 线程等上游，配置 5s；无 Redis 缓存。 | 保留同步探活；接收字节有界、超时取消。 |
| `POST /providers/{id}/models/fetch` | 配置 + 可选 Redis 缓存 + 5s 上游 + Redis 回写；故障可串联。 | 保留用户结果同步，增加总预算与缓存读单飞。 |
| `GET /providers/{id}/models` | provider 存在性与模型均来自配置快照，可能触发共享刷新。 | 保持同步快照读。 |
| `POST /providers/{id}/models` | DB 检查/事务；设默认时可逐模型 saveAndFlush，再发布/reload。 | 保持写事务；默认清理可改单条 DB UPDATE，需另行验证。 |
| `PUT /providers/{id}/models/{modelId}` | DB 读改事务，设默认时同上，提交后发布/reload。 | 同上。 |
| `DELETE /providers/{id}/models/{modelId}` | DB 查询/删除/版本/审计/发布。 | 保持同步。 |
| `GET /settings/defaults` | 配置快照读，可能遇刷新锁。 | 保持同步快照。 |
| `PUT /settings/defaults` | 多 setting 的查找/保存 + DB 版本/审计 + 发布/reload。 | 保持事务；避免每次重复在飞 reload。 |

### 6.4 AiToolsController / TenantDirectoryController / GenerativeFormAttachmentController / AiKbController

| 接口 | 同步阻塞来源与前端影响 | 最佳同步边界 |
|---|---|---|
| `GET /tools` | 纯注册表/内存投影，无 Redis/DB/模型/文件（`AiToolsController.java:51-92`）。 | 保持同步，无整改必要。 |
| `GET /tenants` | 返回静态不可变目录（`TenantDirectoryController.java:22-25`；`TenantUserDirectory.java:32-42`）。 | 保持同步，无整改必要。 |
| `POST /form/attachments` | Redis 校验/限额/登记 + 同步落盘/可选旧件删除；共享盘是主要未知尾部。 | 按 API-BLK-011 减 RTT 和存储有界化，不提前返回不可用 fileId。 |
| `GET /form/option-sources/{sourceId}` | 两个内置源同步 DB 查询；Controller 对未知 source 直接拒绝（`GenerativeFormAttachmentController.java:230-242`）。 | 保持同步小查询；先测量 DB，不盲目异步。 |
| `GET /kb/status` | volatile 状态快照，无 I/O。 | 保持同步；后台 reload 后增加 refreshing/requestId。 |
| `POST /kb/reload` | synchronized 内全量 Redis/文件/HTTP/Lucene 工作，并阻塞定时刷新。 | 按 API-BLK-006 改 202 单飞任务。 |
| `GET /kb/docs` | 当前快照 manifest 的内存投影（`AiKbController.java:51-64`）。 | 保持同步，无整改必要。 |

## 七、相关轮询与锁等待结论

- 删除活跃轮等待：50ms 轮询、总窗 1.5s，且已终态的轮会退出后续轮询（`AiSessionPurgeService.java:911-948`）。这是有界局部等待，**不是**整接口 1.5s 上限。
- 会话发号抖动：仅撞号重取时 1–4ms sleep，最多三次（`AiSessionController.java:95-145`），不是主要阻塞源。
- 配置版本检查：每实例约 1s 一次，但慢 Redis/DB 在锁内，频率节流不等于等待有界（`AiConfigService.java:155-171`）。
- KB：10m 定时刷新、30s 缓存版本轮询共用 `kb-refresh` 单线程（`KbService.java:165-185`）；手动 reload 与该线程争同一 synchronized。版本失败最多三次自动重建，已有退避（`:222-278`），没有发现无限 tight loop。
- KB 缓存冷启动等另一实例：最多约 3s（30×100ms），见 `CachedKbSource.java:219-228`；它会叠加在 HTTP reload 上。
- 运行取消轮询有独立调度器，不与内存 watchdog 共用线程；运行池/触发池有界且身份/MDC 有显式传播（`AiExecutorConfig.java:93-218`）。本报告未把这些后台轮询误算到 HTTP 删除耗时中。
- 手工扫描单飞最多等 1.5s，但抢到后仍同步做完整扫描（`StartupResumeRunner.java:137-141,295-340`）。
- 配置探活 5s、摘要 60s、后端工具执行 120s、运行段 300s 都是不同层级预算，不能互相替代；也不能用其中任一值声称 DELETE 已有相同上限。

## 八、未发现同步阻塞问题的关键路径与必须保留的保护

1. **run 13 键 Cluster 删除是正确的小步原子操作**：全部带 `{run:<runId>}` tag，一次 DEL；索引清理失败不会把已删主体误报为失败（`RunStore.java:2886-2934`）。性能修复应减少外围枚举/读取，不能拆坏这条原子边界。
2. **SCAN 的 Cluster 完整性已处理**：`RedisClusterScanSupport` 逐 master 并在任一失败时中止，不是单节点扫描漏键（`RedisClusterScanSupport.java:98-135`）。问题是代价和无界性，不是正确性缺失。
3. **transcript 写入键同槽且有容量上限**：5000 条/8MiB；裁剪脚本使用同槽键（`ConversationTranscriptStore.java:65-159`）。需要改读取模型，不能误称写入是跨槽脚本。
4. **历史 resultView 不重新查业务库**：生产历史通道以 `historical=true` 使用持久化结果原文（`ListTasksResultViewProvider.java:99-123`）。因此历史慢点应归于 Redis/解析，不应虚构每条历史都访问 DB。
5. **chat 长模型调用已经离开 HTTP 线程**：有界池、无队列、饱和拒绝、身份/MDC 传播都在；SSE 回放在锁外读 Redis、单帧写有 15s 预算。不能为了统一风格把 chat 改回同步等待完整回答。
6. **确认/回灌的跨实例唯一写者与续跑投递已存在**：决策 guard、pending 持久化、续跑租约和有界触发池是必要保护（`ConfirmGate.java:1810-1876,2541-2645`）。优化只应减重复读/清理，不应删 guard 或抢跑。
7. **附件没有 Redis 故障时静默落本地 Map 的生产降级**：Redis 模板存在时登记失败会 fail-closed，上传异常会回收已落盘文件（`RedisFormAttachmentStore.java:145-190`；`GenerativeFormAttachmentController.java:181-197`）。
8. **纯内存/小读接口正常**：`/tools`、`/tenants`、KB `/status`/`/docs`、事件交叉产型 406；反馈 Top100 与上下文台账有读取上限，未发现需要异步化的理由。
9. **配置传播有 DB 真源与 Redis 跨实例信号**：写路径把数据、版本和审计放同一事务，提交后发布并重载（`AiConfigVersionPublisher.java:74-103`）。性能修复要保留多实例可见性，不能只改本地缓存。
10. **文件多实例路径约束已存在**：cluster 等形态要求绝对存储路径并启动校验（`application.yml:473-483`；`application-cluster.yml:16-19`）。本报告不据此推断所有绝对路径都一定是共享盘，后续验证仍需实际检查共享读写。

## 九、建议实施顺序与整体验证清单

1. **先控事故面**：DELETE 受理/总预算与同会话单飞；resume/scan 的 HTTP 受理边界；KB reload 202 单飞。
2. **再降常见读放大**：history 真分页、runs 游标轻量列表、runs/current 合并与 touch 节流、run 详情专用小读。
3. **再治理共享等待**：配置刷新 single-flight、health 探针复用、模型拉取总预算与限流接收、上传/回灌重复 I/O 和共享盘超时。
4. **所有修改共同验证**：standalone 与 Cluster；单实例与三实例；正常、慢命令、节点故障、MOVED/TRYAGAIN；并发读写、重复请求、实例死亡和恢复；请求身份、locale、MDC、租约/fence、TTL 和终帧完整性。
5. **必须采集的验收数据**：HTTP 受理/完整执行分阶段耗时、每接口 Redis 命令数与字节数、DB 连接/锁等待、文件 I/O、线程池 active/queue/reject、SCAN 游标批数、前端历史/卡片恢复时间。没有这些数据时，只能确认结构性风险，不能宣称某个接口已完成性能达标。

本报告只完成了静态代码审查与修复/验证设计；所有运行验证均为后续方案，未在本次审查中执行。
