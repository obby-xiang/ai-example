# 业务与通用 Controller 阻塞 API 审查

- 审查基线：`52b093642402c9455b7cb9627fae6dbdd6544e54`
- 报告编号：11
- 审查对象：`TaskController`、`JobController`、`DataController`、`DefinitionController`、`MasterDataController`、`FileController`、`HealthController` 及其直接/间接服务、仓库、实体、执行器和相关生产配置。
- 方法边界：只进行静态代码与配置审查；未运行测试、构建、CI、服务或压测，未修改生产/测试代码。下文“代码证实”表示控制流和资源调用可由代码直接推出，不表示已经测得实际耗时。
- 特别说明：本报告不含 AI 对话删除 Controller，不能据此判定“删除对话接口超过 30 秒”的根因。本范围内的 `DELETE /api/tasks/{id}` 是任务删除，不是对话删除。对其只确认存在可导致长等待的代码机制，不宣称已在 Redis Cluster 实测超过 30 秒。

## 一、结论与修复优先级

### P1：优先消除同步长等待和长事务

1. **任务删除、作业创建把全量历史作业读取、逐作业 Redis、文件摘要/级联删除放在同步请求与任务行锁内**；历史数据量和 Redis RTT 都会线性放大等待（BBA-01、BBA-02）。
2. **四种作业 runner 的长事务/长连接占用与全量 Excel/DB 处理叠加**，分片进度又通过 `REQUIRES_NEW` 再取连接；默认单实例 8 个作业线程、10 条 DB 连接并不构成安全余量（BBA-03）。
3. **条件数据列表/计数、任务列表、作业轮询和 diff 存在全量读取或 N+1/EAGER 放大**；SSE 降级后的 3 秒轮询会持续放大重响应（BBA-04～BBA-06）。
4. **上传把解压和文件 I/O 包在任务行锁事务内，下载/打包全部进入堆内存**；ZIP 只限制压缩请求大小，不限制解压后总量（BBA-07、BBA-08）。
5. **任务 SSE 对每个订阅者串行同步等待，单订阅者预算是 15 秒**；两个慢订阅者即可形成约 30 秒的调用线程占用，且作业进度发布时外层数据事务仍开放（BBA-09）。

### P2：随后收敛跨实例语义和资源边界

- Redis 写取消失败后，远端 runner 可能继续执行到当前配置项结束，API 却已返回取消成功（BBA-10）。
- 定义删除的跨表检查不是跨实例串行化协议，可与发布/任务选择竞态（BBA-11）。
- 分页大小、模板编码数、主数据列表等缺少统一上限（BBA-12）。
- 文件共享存储只有“绝对路径”校验，没有跨实例同存储证明；Redis readiness 也没有表达作业能力的降级（BBA-13、BBA-14）。
- readiness 并发探测可能因固定池饱和把健康实例判为不就绪（BBA-15）。

## 二、实际阅读范围

### 控制器

- `backend/src/main/java/com/example/configmgr/task/controller/TaskController.java`
- `backend/src/main/java/com/example/configmgr/job/controller/JobController.java`
- `backend/src/main/java/com/example/configmgr/data/controller/DataController.java`
- `backend/src/main/java/com/example/configmgr/definition/controller/DefinitionController.java`
- `backend/src/main/java/com/example/configmgr/masterdata/controller/MasterDataController.java`
- `backend/src/main/java/com/example/configmgr/task/controller/FileController.java`
- `backend/src/main/java/com/example/configmgr/health/HealthController.java`

### 业务服务、作业与 Redis/线程设施

- `task/service/`：`TaskService.java`、`TaskSseService.java`、`RedisTaskEventBus.java`
- `job/service/`：`JobService.java`、`AsyncJobExecutor.java`、`ExportJobRunner.java`、`PrecheckJobRunner.java`、`ImportJobRunner.java`、`PublishJobRunner.java`、`JobProgressService.java`、`JobTerminalWriter.java`、`JobCancellationRegistry.java`、`JobHeartbeatRegistry.java`、`JobWatchdog.java`、`StaleJobRecoveryRunner.java`
- `data/service/ConfigDataService.java`
- `definition/service/`：`DefinitionService.java`、`DependencyResolver.java`
- `file/`：`FileStorageService.java`、`FileStorageMultiInstanceGuard.java`
- `excel/`：`ExcelTemplateBuilder.java`、`ExcelReader.java`、`ExcelWriter.java`
- `common/sse/SseWriteBudget.java`
- `ai/config/RedisAvailability.java`（仅阅读本范围调用的可用性共享设施）
- `ai/tenant/RequestContextTaskDecorator.java`（仅核对异步身份传播）
- `task/config/`：`TaskEventDispatchConfig.java`、`TaskSseSchedulerConfig.java`
- `config/AsyncConfig.java`

### 仓库、实体和 schema

- 仓库：`TaskRepository`、`TaskItemRepository`、`TaskFileRepository`、`JobRepository`、`JobItemRepository`、`ValidationIssueRepository`、`ConfigDataRowRepository`、`ConfigStagingRowRepository`、`ConfigDefinitionRepository`、`ConfigFieldRepository`、`ProjectRepository`、`RegionRepository`。
- 实体：`Task`、`TaskFile`、`Job`、`ConfigDefinition`、`ConfigDataRow`、`ConfigStagingRow`。
- 数据库：`backend/src/main/resources/db/postgresql/V1__schema.sql`、`V6__task_child_fk.sql`；同时检索了 H2/PG 迁移中的表和索引定义。

### 配置与前端等待/轮询

- `backend/src/main/resources/application.yml`
- `application-cluster.yml`
- `application-postgresql.yml`
- `application-h2tcp.yml`
- `frontend/src/api/http.ts`、`tasks.ts`、`jobs.ts`
- `frontend/src/utils/job-watch.ts`、`polling.ts`
- 另检索了前端对下载、条件查询、作业列表和问题列表的实际调用点。

## 三、完整入口→服务→DB/Redis/线程→返回调用链

下表中的“DB”均通过 Spring Data JPA/JdbcTemplate；普通 Controller 方法在 HTTP 请求线程同步执行。只有明确列出的 `@Async` 作业体切换到作业池。

### 3.1 TaskController

| 接口 | 完整调用链与返回 |
|---|---|
| `GET /api/tasks` | HTTP 线程 → `TaskService.search` → `TaskRepository.search` 分页 DB → `Page.map(toSummary)`；每条任务再查全部 jobs 和全部 files，取最新 job/文件数；`Task.items` 与 `Job.items` 为 EAGER → 组装 `Page<TaskSummary>` 同步返回。无 Redis。 |
| `GET /api/tasks/{id}/overview` | HTTP 线程 → `TaskService.findById` 查 task + items → 直接查全部 jobs（含 EAGER items/resultJson）→ 查全部 files → `Map` 同步返回。无 Redis。 |
| `POST /api/tasks` | HTTP 线程 → `TaskService.create` 短事务 → insert task → 201。无 Redis、无异步。 |
| `GET /api/tasks/{id}` | HTTP 线程 → `TaskService.findById` → task DB + items DB → 返回实体。 |
| `DELETE /api/tasks/{id}` | HTTP 线程 → `TaskService.delete` 事务 → task `SELECT FOR UPDATE` → 全部 jobs/EAGER items → 逐 job `JobHeartbeatRegistry.isRunnerAlive`，远端非本地 job 发单键 `EXISTS job:runner-active:<id>` → 全部 files、全部 staging 实体 → 逐表删除 issues/job_items/jobs/staging/items/files/task → 提交 → 请求线程逐个物理删除文件 → 200。 |
| `POST /api/tasks/{id}/select-defs` | HTTP 线程 → 事务内锁 task → 删除不在选择集的 items → 查现有 items → 逐新增 item insert → 保存/flush/refresh task → 返回 task。无 Redis。 |
| `PUT /api/tasks/{id}/items/{defCode}/condition` | HTTP 线程 → Controller JSON 序列化/校验 → 事务内查 item、更新 condition/status → 查 task → 200。无 Redis。 |
| `PUT /api/tasks/{id}/step` | HTTP 线程 → 事务内查 task → 必要时查最终成功 job → 更新 step → 返回 task。 |
| `PUT /api/tasks/{id}/import-mode` | HTTP 线程 → 事务内查 task → 读/写 settingsJson → 更新 task → 返回 task。 |
| `GET /api/tasks/{id}/events` | HTTP 线程 → `TaskSseService.subscribe` 建 emitter、注册本机列表 → 首订阅时 `RedisTaskEventBus.subscribe`，可能同步懒启动 `RedisMessageListenerContainer` → 注册 Redis Cluster Pub/Sub channel `task:events:<taskId>` → 首帧经共享 SSE 写池，但调用线程同步等待写结果 → 返回 `SseEmitter`，之后由容器保持长响应。 |
| `GET /api/tasks/{id}/files` | HTTP 线程 → `TaskService.getFiles` → 全部 task files → 返回列表。 |

### 3.2 JobController

| 接口 | 完整调用链与返回 |
|---|---|
| `POST /api/tasks/{taskId}/jobs` | HTTP 线程 → `JobService.createAndStart` 事务 → 锁 task → 终态/同类 job/预检查 DB 查询 → 全部历史 jobs → 逐 job Redis `EXISTS` 判 runner → IMPORT 时逐 item 查 UPLOAD 文件并流式读盘算 SHA-256 → insert job → 本地取消登记 → Redis 依次 `SET runner-active`、`SET beat` → commit → `afterCommit` 调独立 Bean 的 `@Async AsyncJobExecutor.execute` → 有界作业池运行对应 runner → HTTP 返回 201（若池拒绝，会在 afterCommit 同步补 FAILED 数据后再返回）。 |
| `GET /api/tasks/{taskId}/jobs` | HTTP 线程 → `JobService.findByTaskId` → 全部 jobs，逐 job EAGER items，并携带 resultJson → 返回完整列表。 |
| `GET /api/jobs/{jobId}` | HTTP 线程 → `JobService.findById` → job + EAGER items + resultJson → 返回；这是 SSE 降级时每 3 秒一次的快照端点。 |
| `DELETE /api/jobs/{jobId}` | HTTP 线程 → `JobService.cancel` 的 `REQUIRES_NEW` 模板 → 查 job、锁 task、条件 UPDATE job、更新 task、收敛 job items → **事务提交并释放资源后** → 同步 Redis `SET job:cancel:<id>` + 本机 listener → 同步 `TaskSseService.publish`：本机逐 emitter 写 + Redis `PUBLISH task:events:<taskId>` → 返回取消落地字段。 |
| `GET /api/jobs/{jobId}/issues` | HTTP 线程 → `JobService.findIssues` → issue DB 分页和 count → 返回 Page；size 无上限。 |
| `GET /api/jobs/{jobId}/diff` | HTTP 线程 → 查 job（含 EAGER items/resultJson）→ 按 **taskId** 查全部 staging 行及 dataJson → 返回完整数组。 |

### 3.3 作业提交后的四条异步链

共同线程模型：`afterCommit → AsyncJobExecutor @Async → job-async-*`；池为核心 4、最大 8、队列 200、AbortPolicy，并配置请求身份装饰器。各 runner 的进度调用都会进入 `JobProgressService`，先通过心跳登记簿写 Redis，再以 `REQUIRES_NEW` 写 jobs/job_items；分片边界还读取消键并发 SSE。

- **EXPORT**：`ExportJobRunner.run @Transactional` → 清 issues、查 items、逐定义 count → 每定义全量 `ConfigDataRow` + 逐行 JSON 解析/条件过滤到 List → 分批 sleep、进度 DB/Redis、取消 GET、SSE → `ExcelWriter` 写盘 → 保存 TaskFile/TaskItem → 整事务提交 → `JobTerminalWriter` 新事务写终态/任务 → SSE JOB_DONE → finally 清 Redis runner/beat。
- **PRECHECK**：`PrecheckJobRunner.run @Transactional` → 依赖排序时加载全部定义/字段 → 每定义读完整文件 byte[]、再次流式摘要、`ExcelReader` 构造 XSSFWorkbook 和完整 rows → 逐字段校验并逐 issue insert → REFERENCE 时加载被引用定义全部已发布行及本任务暂存行 → 分批进度/取消/SSE → 写指纹 → 提交后终态/SSE → 清理 runner。
- **IMPORT**：`ImportJobRunner.run @Transactional` → 依赖排序 → 每定义先删旧 staging → 读完整 Excel → 每行查一次已发布行取 baseVersion → 每 100 行 saveAll、sleep、进度/取消/SSE → 提交后终态/SSE → 清理 runner。
- **PUBLISH**：`PublishJobRunner.run` → 外层无事务但 `TransactionTemplate` 包住整个 `doPublish` → 查全部 staging → REPLACE 时按范围加载已发布行并差集删除 → 每行 JSON 解析、查当前 published row、upsert、保存 staging → 分批进度/取消/SSE → 整批提交 → 终态/任务/SSE → 清理 runner。

### 3.4 DataController

- `GET /api/data/{defCode}`：HTTP 线程 → Jackson 解析/校验 conditions；有条件时 `ConfigDataService.findPagedFiltered` → **按 defCode 读全部行** → 逐行 JSON 解析和 Java 条件过滤 → 内存 subList 分页；无条件时先可能查 definition 推导 scopeType → DB 分页 + count → 返回 Page。
- `GET /api/data/{defCode}/count`：HTTP 线程 → 同样的条件解析；有条件时仍读全部行并过滤后取 `size()`；无条件时 `countByDefCode` → 返回 count。scopeType/scopeKey 在 count 中按既有注释刻意不参与最终计数，不作为本次性能缺陷。

### 3.5 DefinitionController

- `GET /api/definitions`：HTTP 线程 → 全部或按 level 查 definitions，EAGER fields → 可选关键词在 Java 中过滤 → 返回完整列表。
- `GET /api/definitions/{code}`：HTTP 线程 → definition + EAGER fields → 返回。
- `POST /api/definitions`：HTTP 线程 → `DefinitionService.save` 事务 → code 预查、字段校验 → 保存 definition/fields → 201。
- `PUT /api/definitions/{code}`：HTTP 线程 → 事务 → definition + EAGER fields → 校验/按 field code 合并 → 保存及 orphanRemoval → 返回。
- `DELETE /api/definitions/{code}`：HTTP 线程 → 事务 → definition → published count、反向 REFERENCE 查询、ACTIVE task 引用 count、staging count → 按 defCode 批量删 staging → 删 definition 并级联 fields → 返回计数。
- `GET /api/definitions/{code}/template`：HTTP 线程 → definition + fields → `ExcelTemplateBuilder` 在堆中构建 XSSFWorkbook → `ByteArrayOutputStream` → byte[] 响应。

### 3.6 FileController

- `POST /api/tasks/{taskId}/files/upload`：HTTP 线程 → **同一事务先锁 task** → 逐个 defCode 查 definition 或查 items → ZIP 时 `file.getBytes`、全部 entry `readAllBytes` 到 List，必要时再按 GBK 全量解一遍 → 逐 entry 写共享文件系统并保存/更新 TaskFile → commit/rollback 文件清理由同事务同步器处理 → 返回匹配结果；单 XLSX 同样先锁 task，再写盘和落库。
- `GET /api/tasks/{taskId}/files/{defCode}`：HTTP 线程 → EXPORT/UPLOAD 两次以内 DB 查询 → `Files.readAllBytes` → byte[] 响应。
- `PUT /api/tasks/{taskId}/files/{defCode}`：HTTP 线程 → 请求体先成为 byte[] → 事务内锁 task → 查 EXPORT/UPLOAD → 分配新键并同步写盘 → saveAndFlush → commit 后同步删旧文件 → 200。
- `GET /api/tasks/{taskId}/files/download-all`：HTTP 线程 → 查全部 files → 逐文件 `readAllBytes` → 写入内存 ZIP → `toByteArray` → byte[] 响应。
- `GET /api/tasks/{taskId}/files/download`：HTTP 线程 → 查全部 files → Java 按 codes 选择 → 单文件全量 byte[]，多文件内存 ZIP → 响应。
- `GET /api/tasks/{taskId}/files/templates`：HTTP 线程 → task + items → 每 code 查 definition/fields → 每份模板堆内 Excel 构建 → 单文件 byte[] 或多文件内存 ZIP → 响应。

### 3.7 MasterDataController、HealthController

- `GET /api/master/regions`：HTTP 线程 → `regionRepository.findAll` → 完整列表；无 Redis/文件/线程切换。
- `GET /api/master/projects`：HTTP 线程 → 有 regionCode 时派生查询，否则 `findAll` → 完整列表。
- `GET /health/live`：HTTP 线程 → 只读内存 draining 标志和当前时间 → 200；无外部 I/O。
- `GET /health/ready`：HTTP 线程 → `ReadinessService.check` → `ReadinessProbeRunner` 固定 4 线程/队列 8，并行执行 DB `SELECT 1`、存储目录检查、seed 内存状态、Redis PING → 请求线程等待一个共享 2 秒截止时刻 → 200/503。慢 native 文件 I/O 可能仍占探针线程，但不继续占 HTTP 请求线程。

## 四、逐项问题

### BBA-01：任务删除的同步耗时随历史作业、暂存行和文件数线性增长

- **严重度：高（P1）；确定性：代码证实，实际是否超过 30 秒未实测。**
- **证据**：`TaskService.delete` 在事务中先锁 task，再读取全部 jobs，并逐 job 调 `isRunnerAlive`：`backend/src/main/java/com/example/configmgr/task/service/TaskService.java:713-755`。远端作业每个都执行一次 Redis `hasKey`：`backend/src/main/java/com/example/configmgr/job/service/JobHeartbeatRegistry.java:363-386`。Job 还 EAGER 加载 items：`backend/src/main/java/com/example/configmgr/job/entity/Job.java:39-47`。暂存行为了日志计数被完整水合后仅取 `size`：`TaskService.java:755-762`。之后串行删多张表：`TaskService.java:764-772`，提交后请求线程逐文件删除：`TaskService.java:774-795`。
- **触发条件**：任务积累大量历史 jobs/items、staging 大行数/CLOB、文件数多；Redis RTT 增大；共享存储删除慢；其它请求持有同一 task 行锁。
- **单例 Redis / Cluster**：两者都是每个历史远端 job 一次单键 EXISTS，没有 CROSSSLOT；Cluster 的跨节点 RTT 可能进一步放大 N 次串行调用，但不是拓扑特有逻辑错误。
- **多实例/多线程**：任务在 A 删除、历史 runner 登记由 B 写入时，本地集合不能短路，必须访问 Redis。数据库行锁跨实例有效；同任务创建、取消、上传/保存和删除会等待同一把锁。PG/H2 共享库配置锁等待上限为 60 秒，见 `backend/src/main/resources/application-postgresql.yml:30-36` 与 `application-h2tcp.yml:29-36`；这不是 30 秒响应保证。
- **根因**：审计/守卫使用实体全量读取而非标量投影；Redis 按 job 串行；DB 行锁覆盖 Redis 和大量级联；物理文件清理仍占用请求响应时间。
- **最佳修复**：删除分两段。第一段做有界预检和短事务逻辑删除/标记 DELETING；jobs 只取 id/status/taskId 标量，staging 改 `countByTaskId`，files 只取存储键。第二段由持久化清理作业异步级联和删文件，接口返回 202 + operationId。runner 存活检查按本任务当前可能存活的 job 集查询，并使用 Cluster 安全的按槽分组 pipeline/并发单键命令；不要直接改成跨槽 MGET。必须保留 Redis 不可用时 fail-closed。
- **备选取舍**：若必须同步删除，至少改标量查询、COUNT、限制单请求总预算并异步删文件；实现较小，但大事务和级联锁仍在。简单并行 Redis 可缩短墙钟，却会增加连接与 Cluster 压力，必须限并发。
- **涉及修改点/影响面**：`TaskController`、`TaskService`、相关 Repository 投影、`JobHeartbeatRegistry` 批量接口、文件清理任务、前端删除状态与轮询；DB 锁和恢复语义需一起回归。

### BBA-02：作业创建在 task 行锁和 DB 事务内串行访问 Redis、读完整文件摘要

- **严重度：高（P1）；确定性：代码证实。**
- **证据**：创建入口从事务开始即锁 task：`backend/src/main/java/com/example/configmgr/job/service/JobService.java:158-167`。`assertNoRunnerInFlight` 遍历全部历史 jobs 并逐个查 Redis：`JobService.java:297-313`。IMPORT 还会逐 item 查文件并同步计算摘要：`JobService.java:401-431`；摘要实现虽为 8 KB 流式读取，但仍需读完整个文件：`backend/src/main/java/com/example/configmgr/file/FileStorageService.java:147-164`。随后 beginRunner 串行写 runner-active 和 beat 两个键，失败时还尝试清理：`JobHeartbeatRegistry.java:290-315`。
- **触发条件**：历史 jobs 多、IMPORT 文件大/共享盘慢、Redis RTT 慢；并发请求竞争同 task。
- **单例/Cluster**：runner-active、beat 都是单键命令，Cluster 无 CROSSSLOT；两个键不要求同槽，但需两次往返。逐历史 job 检查在两种拓扑均为 O(N)。
- **多实例/多线程**：所有实例共享 DB task 锁；Redis/文件 I/O 期间持续占锁和 DB 连接。201 尚未返回时，同任务上传、删除、取消和其它启动作业可排队。线程池拒绝后的 FAILED 补记也发生在 afterCommit 的请求线程：`JobService.java:191-203`、`238-267`。
- **根因**：把“全部历史”当作 runner 候选；在权威文件上重复算摘要，而非使用上传时持久化摘要；Redis 注册与 DB 锁事务耦合。
- **最佳修复**：用 DB 只选近期/可能活跃的 runner 引用，优先利用终态 + 本机/共享登记的精确候选；上传/替换文件时计算并保存 sha256/size，创建 IMPORT 只做已存指纹比对。将任务锁和 job insert 收敛为短 DB 事务；runner 注册采用可补偿的 Saga/明确状态机，避免持 task 锁等网络。
- **备选取舍**：保留现有顺序而只做 Repository 投影、Redis 有界 pipeline、文件摘要缓存，改动小且能降低耗时；但提交前共享键登记与任务行锁的耦合仍存在。
- **涉及修改点/影响面**：`JobService`、`JobHeartbeatRegistry`、文件上传/保存元数据、schema 指纹列、并发创建/删除/取消协议、前端 201/202 语义。

### BBA-03：四种 runner 的长事务、双连接和同步 Redis/SSE/文件处理相互放大

- **严重度：高（P1）；确定性：代码证实。**
- **证据**：EXPORT/PRECHECK/IMPORT 整轮分别为 `@Transactional`：`ExportJobRunner.java:80-81`、`PrecheckJobRunner.java:67-68`、`ImportJobRunner.java:62-63`；PUBLISH 用单个 TransactionTemplate 包全批：`PublishJobRunner.java:99-105`。全量行/文件及分批 sleep 见 `ExportJobRunner.java:179-225`、`PrecheckJobRunner.java:157-220`、`ImportJobRunner.java:130-174`、`PublishJobRunner.java:188-237`。进度服务固定 `REQUIRES_NEW`：`JobProgressService.java:72-80`，其入口还先写 Redis：`JobProgressService.java:90-107`。
- **触发条件**：大 Excel、大行数、REFERENCE 多、共享存储慢、Redis 慢、慢 SSE 订阅者、多个作业并发。配置还保留每 100 行 300ms 的演示延迟：`backend/src/main/resources/application.yml:449-452`。
- **单例/Cluster**：进度 heartbeat 每拍两个 SET、取消检查 GET、事件 PUBLISH 均为单键/单频道；Cluster 不跨槽，但网络往返处于作业关键路径，慢时会延长事务。
- **多实例/多线程**：单实例作业池最大 8，Hikari 最大 10：`AsyncConfig.java:69-78`、`application-postgresql.yml:22-28`。外层事务占 1 条连接时，`REQUIRES_NEW` 再取 1 条；代码自身也承认峰值两条：`AsyncConfig.java:47-51`。因此 8 个作业可需求 16 条连接，另有 HTTP/health 竞争；多实例总连接数按实例数倍增。IMPORT 删除/插入和 PUBLISH 更新还会扩大锁与 WAL/事务日志压力。
- **根因**：数据面原子事务与逐行处理、演示 sleep、外部网络通知和进度事务混在同一生命周期；没有按“计算/文件 I/O”和“短 DB 写入”分层。
- **最佳修复**：移除生产路径演示 sleep；Excel 解析、过滤、摘要和文件生成在无 DB 事务阶段完成。IMPORT 先写作业私有临时区/文件，再用短事务或受控分块落 staging；EXPORT 每定义独立短事务换文件引用。PUBLISH 若必须整批原子，预计算所有映射并用 set-based SQL/临时表，把最终 upsert/差集删除缩成短事务。进度与通知采用独立、有界、非阻塞事件通道；DB 持久化进度可在不持外层事务时提交，或使用事务后事件/outbox。
- **备选取舍**：仅删除 sleep、把 SSE 改为异步、降低作业池到与连接预算匹配，可快速止血，但全量内存和长 DB 事务仍在。简单把每 100 行改独立事务会破坏 PUBLISH 整批原子性，不能直接采用。
- **涉及修改点/影响面**：四个 runner、`JobProgressService`、`JobTerminalWriter`、作业池/Hikari 容量、staging 模型、回滚与取消语义、SSE 顺序、前端进度显示。

### BBA-04：条件数据列表和条件计数始终全量读取并逐行解析 JSON

- **严重度：高（P1）；确定性：代码证实。**
- **证据**：`filterRows` 首先 `findByDefCodeOrderByRowKey(defCode)`，随后才在 Java 中应用 scopeKey 和字段条件，并逐行 Jackson 解析：`backend/src/main/java/com/example/configmgr/data/service/ConfigDataService.java:102-117`。列表即使只取一页也先构造完整 matched List：`ConfigDataService.java:72-79`；计数同样调用它再取 size：`ConfigDataService.java:58-61`。dataJson 是大文本：`backend/src/main/java/com/example/configmgr/data/entity/ConfigDataRow.java:28-32`。
- **触发条件**：任一配置行数大、JSON 大、scopeKey 高选择性、仅请求第一页、页面同时调用 list/count。
- **单例/Cluster**：不访问 Redis，两种拓扑相同。
- **多实例/多线程**：每个 HTTP 请求线程都独立执行 O(N) DB 传输、JSON 解析和内存分配；多实例不会分担同一请求，反而共同放大共享 DB 的 IO/CPU。
- **根因**：把数据库分页退化为“全量过滤后 subList”；为得到 Page total，count 也无法早停。
- **最佳修复**：生产 PG 将 dataJson 规划为 JSONB，并为常用字段/操作符制定可索引查询；至少先把 defCode/scopeKey 下推 SQL，使用稳定 keyset 分页。大结果 count 改异步/按需精确计数或明确展示上限。保留统一 ConditionEvaluator 语义时，可用 DB 候选收窄 + Java 精确复验。
- **备选取舍**：为兼容 H2 而使用有界 keyset 批次流式求值可降低堆峰值，列表可按需停止，但精确 total 仍 O(N)；只在 Java 加 size 上限不能解决慢查询。
- **涉及修改点/影响面**：`ConfigDataService`、Repository 查询、PG/H2 方言、数据迁移/索引、Page 契约、导出与列表条件一致性。

### BBA-05：任务列表和任务/作业读取存在 N+1、EAGER 与无界历史载荷

- **严重度：高（P1）；确定性：代码证实。**
- **证据**：任务列表每条都读取全部 jobs 和 files，分别只为最新 job 和 count：`backend/src/main/java/com/example/configmgr/task/controller/TaskController.java:53-74`。Task.items EAGER：`backend/src/main/java/com/example/configmgr/task/entity/Task.java:61-67`；Job.items EAGER：`backend/src/main/java/com/example/configmgr/job/entity/Job.java:42-47`。`listJobs` 和 overview 无分页：`JobService.java:457-459`、`TaskController.java:60-65`。`getJob` 返回完整 job：`JobService.java:270-273`，而前端降级时每 3 秒调用一次：`frontend/src/utils/job-watch.ts:49-56`、`233-253`。
- **触发条件**：任务页 size 大、历史 jobs/items 多、resultJson 大、SSE 断线后多个页面同时轮询。
- **单例/Cluster**：主要是 DB；Cluster 仅因 SSE 丢帧/降级间接增加轮询，不是 Redis 命令问题。
- **多实例/多线程**：每个实例都重复构造相同重响应；共享 DB 承受 N+1 查询。轮询有在途合并，能避免单页面重叠请求，但不能降低单次响应成本，也不能合并不同页面/实例。
- **根因**：Controller 直接返回持久化实体；用完整集合表达 count/latest；EAGER 关联使看似简单的快照查询携带额外数据。
- **最佳修复**：为 TaskSummary/JobSnapshot 建立 DTO 和单 SQL/少量聚合投影：文件 COUNT、最新 job 用窗口函数/相关子查询；关联改 LAZY 并禁止实体直接序列化。jobs/overview 增加分页或增量 since 协议；轮询快照只含状态与进度标量。
- **备选取舍**：仅把 jobs/files 改为每任务两条批量 IN 查询可消除请求内 N+1，但仍返回过多历史；仅改 LAZY 在 open-in-view=false 下还需同步设计 DTO，不能只改注解。
- **涉及修改点/影响面**：Controller DTO、Repository 聚合查询、实体映射、前端类型和轮询、overview/listJobs 兼容性。

### BBA-06：diff、定义依赖和预检查引用校验产生额外全量数据放大

- **严重度：高（P1）用于 diff/引用校验，中（P2）用于定义目录；确定性：代码证实。**
- **证据**：`/jobs/{jobId}/diff` 不按 job 或 def 分页，而是按 task 读全部 staging：`backend/src/main/java/com/example/configmgr/job/controller/JobController.java:65-69`，每行含 dataJson：`ConfigStagingRow.java:35-36`。依赖排序无论选择多少定义都 `findAllOrdered` 并 EAGER 加载全部 fields：`backend/src/main/java/com/example/configmgr/definition/service/DependencyResolver.java:35-49`。每个 REFERENCE 字段都重新加载被引用定义全部已发布行及本任务暂存行：`PrecheckJobRunner.java:229-255`、`400-419`。定义列表也无分页并做 Java keyword 过滤：`DefinitionController.java:27-38`。
- **触发条件**：staging 多、定义/字段多、多个 REFERENCE 指向大配置、重复打开 diff。
- **单例/Cluster**：无 Redis，表现相同。
- **多实例/多线程**：异步 precheck 与 HTTP diff 可同时把相同大文本读入不同线程/实例，增加 DB 与堆压力。
- **根因**：查询边界按“全表/全任务”而非当前工作集；同一 REFERENCE 目标没有按作业缓存。
- **最佳修复**：diff 改按 job 快照和 defCode 游标分页，必要时只返回变化字段；依赖查询只取选中定义及必要闭包；precheck 按 `(refDefCode, refFieldCode)` 一次构建值集并复用，长期应维护可查询的引用键索引而非解析全部 JSON。
- **备选取舍**：先加 diff 分页和单次作业缓存即可大幅降低峰值；定义目录在确为小表时可暂缓 DB 关键词下推。
- **涉及修改点/影响面**：`JobController`、staging 快照模型、`DependencyResolver`、`PrecheckJobRunner`、前端 diff 分页和导出/校验语义。

### BBA-07：上传/保存在 task 行锁事务内做解压和文件 I/O，ZIP 解压后大小无界

- **严重度：高（P1）；确定性：代码证实。**
- **证据**：upload 方法 `@Transactional` 且第一步 lockTask：`backend/src/main/java/com/example/configmgr/task/controller/FileController.java:63-75`。ZIP 先 `file.getBytes`，每个 entry 又 `readAllBytes` 并全部保存在 List：`FileController.java:75-92`、`178-197`；出现文件名替换符时还会完整解压第二次。逐 entry 写盘和 DB 保存仍在同一事务：`FileController.java:80-97`、`140-171`。save 也在锁内写完整 byte[]：`FileController.java:221-269`。配置仅限制 multipart 文件 5MB/请求 6MB：`backend/src/main/resources/application.yml:28-34`，没有解压后总量、entry 数或压缩比限制。
- **触发条件**：高压缩比 ZIP、大量 entry、慢共享盘、大原始 PUT body、同任务并发写入/删除/启动。
- **单例/Cluster**：无 Redis，差异来自共享文件系统；Cluster profile 只是触发共享存储路径校验。
- **多实例/多线程**：DB task 锁跨实例串行化所有持有者，慢文件 I/O 使其它实例也等待。每个上传线程同时持有压缩 bytes、解压 bytes 和 entries；多实例各自堆独立受压。
- **根因**：事务和锁范围覆盖非 DB 工作；ZIP 采用全量内存模型；raw byte[] 保存没有本层显式上限。
- **最佳修复**：上传先流式落临时文件并执行 entry 数、单件/总解压大小、压缩比和实际 XLSX 内容检查；随后开一个短事务锁 task、批量换 TaskFile 引用，失败清理临时件。save 使用受上限的 InputStream/临时文件而非 byte[]。保持唯一约束、乐观锁和 afterCommit/afterRollback 清理。
- **备选取舍**：不重构事务时，至少将解压移到锁外并加硬上限；但“写盘后任务被删”的窗口仍需短事务锁和补偿，不能仅删除 lockTask。
- **涉及修改点/影响面**：`FileController`、文件存储临时件协议、multipart/raw body 限制、前端上传进度/超时、事务清理回调。

### BBA-08：文件下载、全部打包和模板打包均同步全量进内存

- **严重度：高（P1）；确定性：代码证实。**
- **证据**：单文件 `Files.readAllBytes`：`FileController.java:201-218`、`FileStorageService.java:133-135`；ZIP 使用 `ByteArrayOutputStream`，每个文件又 readAllBytes，最后 `toByteArray`：`FileController.java:273-282`、`344-355`。多模板逐 code 查定义、逐份构建 Excel 后写入内存 ZIP：`FileController.java:358-391`。前端 axios 还以 Blob 接收完整响应：`frontend/src/api/http.ts:216-219`。
- **触发条件**：大导出件、多文件、多模板、慢存储、多并发下载。响应首字节要等全部读取/打包完成。
- **单例/Cluster**：无 Redis；Cluster 多实例共享盘时，远程文件系统延迟是共同瓶颈。
- **多实例/多线程**：HTTP 线程被同步 I/O 和压缩占用；服务端堆同时可有原始文件 byte[]、ZIP buffer 和 toByteArray 副本，浏览器另有 Blob。多实例分别承受各自下载流量。
- **根因**：`ResponseEntity<byte[]>` 与内存 ZIP 代替流式响应；模板 codes 无上限。
- **最佳修复**：单文件用 `Resource`/流式响应；ZIP 用 `StreamingResponseBody` + 有界 servlet 异步执行器，逐项流式 copy，设置总时限和背压。模板限制 codes 数并可缓存不变模板；大文件前端使用浏览器原生下载，避免额外 Blob 复制。
- **备选取舍**：先设置文件数/总字节上限能快速防 OOM，但合法大下载仍慢；改用异步 byte[] 只释放等待模型，不降低堆占用，不是充分修复。
- **涉及修改点/影响面**：`FileController`、`FileStorageService` 流式 API、WebMVC 异步配置、代理响应缓冲/超时、前端下载方式。
- **附带正确性问题**：`downloadAll` 未像 `downloadSelected` 一样每 code 去重，EXPORT/UPLOAD 同名 entry 可能触发 ZIP duplicate entry；证据为 `FileController.java:275-278` 对比 `293-341`。应在流式改造时统一文件名冲突策略。

### BBA-09：任务 SSE 发布对本机订阅者逐个同步等待，单帧预算不是整次发布预算

- **严重度：高（P1）；确定性：代码证实。**
- **证据**：`deliverJson` 对 snapshot 中 emitter 逐个调用 sendFrame：`backend/src/main/java/com/example/configmgr/task/service/TaskSseService.java:294-317`。sendFrame 使用同步 `writeBudget.write`：`TaskSseService.java:550-573`；其实现每个 emitter 都 `future.get(timeout)`：`backend/src/main/java/com/example/configmgr/common/sse/SseWriteBudget.java:337-378`。默认每帧 15 秒：`backend/src/main/resources/application.yml:435-448`。之后还同步 Redis PUBLISH：`RedisTaskEventBus.java:170-198`。
- **触发条件**：同任务两个或更多“不读也不断开”的本机订阅者；写池未饱和且每次写都等到 15 秒。两个这样的订阅者可形成约 30 秒调用线程等待，Redis 发布另计。
- **单例/Cluster**：本地慢 SSE 与 Redis 拓扑无关；跨实例帧在订阅方还会走相同串行写。Pub/Sub 是单频道，不存在 CROSSSLOT。
- **多实例/多线程**：作业在 A、浏览器在 B 时，A 的发布线程和 B 的消息分发线程都可能受阻。EXPORT/PRECHECK/IMPORT 的外层事务仍开放时，进度发布会延长 DB 连接/事务占用；取消接口虽已在 DB commit 后调用 SSE，不再持连接，但 HTTP 响应仍等待。
- **根因**：把“单订阅者单帧预算”误当成“一次发布预算”；同步 `write` 用于任务通道，而类中已有回调式 `writeAsync` 未被该通道使用。
- **最佳修复**：任务 publish 只做帧序列化、每订阅者有界入队和 Redis 异步发布；每订阅者单写者串行 drain，使用 `writeAsync` 或等效非阻塞完成回调。设置每任务/整次发布总预算、队列上限和慢消费者摘除。JOB_DONE 以 DB 为权威，保留现有轮询兜底。
- **备选取舍**：把所有 emitter 有界并行写出可缩短墙钟，但会放大共享 64 线程写池压力；简单把 15 秒调小会提高慢链路误删率，不能解决结构问题。
- **涉及修改点/影响面**：`TaskSseService`、`SseWriteBudget`、订阅者队列/背压、RedisTaskEventBus、作业与取消时序、前端重连和 3 秒轮询。

### BBA-10：Redis 取消标志写失败后，跨实例 runner 可继续执行，而 API 已报告取消

- **严重度：高（P1，跨实例取消语义）；确定性：代码证实降级路径，继续时长取决于 runner 当前阶段。**
- **证据**：DB 先把 job/task 写成 CANCELLED：`backend/src/main/java/com/example/configmgr/job/service/JobService.java:489-521`；Redis 写失败被转换为 `redisWritten=false`，端点仍 200：`JobService.java:536-560`、`JobCancellationRegistry.java:169-180`。远端没有本地加速位；读取失败又按未取消处理：`JobCancellationRegistry.java:157-166`。runner 主要在配置项入口和每 100 行边界检查，例如 `ExportJobRunner.java:137-141`、`199-210`，长 Excel 写入期间不检查。
- **触发条件**：取消请求到 B、runner 在 A，Redis 写失败或 A 后续读取失败；当前配置项行数大或正在 Excel/DB 长段。
- **单例/Cluster**：单实例可靠地还有本机 listener 加速；多实例失去共享传播。Cluster 故障/分区使该窗口更突出，非命令槽位问题。
- **多实例/多线程**：UI/DB 已终态，但 A 线程仍占连接、写文件或扫描；删除因 runner 活跃守卫会继续 409。终态条件 UPDATE 能阻止部分迟到状态覆盖，不能停止 CPU/文件工作。
- **根因**：取消只有 Redis 权威标志和本地加速，没有 DB 取消轮询兜底；“取消请求已接受”和“执行体已停止”混在一个 200 响应中。
- **最佳修复**：jobs 增加持久化 cancel_requested_at/version，runner 在分片边界同时以 DB 条件检查兜底；取消接口返回 202，区分 accepted/propagated/stopped，前端以实际 runner 退出/终态快照为准。共享 Redis 仍是低延迟通知，DB 是降级真源。
- **备选取舍**：Redis 写失败直接返回 503 能提示传播不可靠，但 DB 已提交，必须明确“已请求、传播降级”，不能声称未取消；只缩短检查批次不能覆盖 Excel 长段。
- **涉及修改点/影响面**：`JobService`、`JobCancellationRegistry`、四个 runner、schema、前端取消状态、删除闸门和 watchdog。

### BBA-11：定义删除的跨表依赖检查与并发写入没有共同锁/约束

- **严重度：高（P1，可能产生依赖不一致）；确定性：代码证实竞态窗口，是否发生取决于并发时序。**
- **证据**：删除按顺序检查 published count、REFERENCE、ACTIVE task 引用，再删 staging/definition：`backend/src/main/java/com/example/configmgr/definition/service/DefinitionService.java:115-154`。没有 definition 行锁或跨表锁。`config_data_rows.def_code` 没有到 definitions 的 FK：`backend/src/main/resources/db/postgresql/V1__schema.sql:70-83`。发布可并发 upsert：`PublishJobRunner.java:203-226`；选择任务也可并发插 task item：`TaskService.java:444-475`。
- **触发条件**：实例 A 删除定义时，实例 B 正在发布该定义数据、选择该定义或更新引用字段。
- **单例/Cluster**：不访问 Redis，两种拓扑相同。
- **多实例/多线程**：JVM 内也没有统一互斥，多实例更无共享锁；DB 各自事务的 count 检查不能阻止随后插入。
- **根因**：check-then-act 只靠应用层读取，跨表业务约束未下沉为共同加锁协议或 DB FK。
- **最佳修复**：对数据引用增加合适的 DB FK/RESTRICT；定义删除与所有会创建引用的路径共用 definition 行锁/状态机（如 DELETING），并在提交前复核。更新增加乐观版本。历史 task_items 要保留，不能直接加一个会阻止所有定义删除的普通 FK，应只约束活跃引用或采用独立引用表。
- **备选取舍**：只给 delete 加 `SELECT FOR UPDATE` 不够，因为发布/选择路径不锁同一行；只重试删除只能降低概率，不能闭合竞态。
- **涉及修改点/影响面**：`DefinitionService`、`TaskService.selectDefs`、作业启动/发布、schema FK/版本、并发冲突码和历史任务兼容。

### BBA-12：分页大小和批处理选择集缺少统一硬上限

- **严重度：中（P2）；确定性：代码证实。**
- **证据**：DataController 注释明确承认 size 无 clamp：`backend/src/main/java/com/example/configmgr/data/controller/DataController.java:51-55`，并直通 PageRequest：`DataController.java:71-84`。Task list 和 Job issues 同样直通：`TaskController.java:44-54`、`JobService.java:461-463`。模板 codes、上传 defCodes、selectDefs 列表也无数量上限：`FileController.java:358-386`、`413-432`、`TaskService.java:444-475`。
- **触发条件**：调用方传超大 size/codes/defCodes；selectDefs 还使用 `existingCodes.contains`，新增项逐个 save，较大列表会产生 O(N²) 比较和大量 SQL。
- **单例/Cluster**：无直接 Redis；大列表会间接延长 task 锁和作业启动后续处理。
- **多实例/多线程**：任何实例都可被大请求占用 HTTP 线程、堆和 DB；共享 DB 同时承压。
- **根因**：输入只校验形状，不校验资源预算。
- **最佳修复**：统一分页策略（建议明确 1..100，超限 400 或 clamp 需与 API 契约一致）；codes/defCodes 限制数量和总长度；selectDefs 转 Set 并使用批量持久化。限制不能替代 BBA-04～BBA-08 的结构性修复。
- **备选取舍**：clamp 对旧客户端兼容但隐藏调用错误；显式 400 更易治理，需要前端同步。
- **涉及修改点/影响面**：三个 Controller、校验组件、前端分页选项、AI/直连调用方契约。

### BBA-13：多实例文件存储只验证“绝对路径”，不能证明实例间真是同一存储

- **严重度：中（P2，部署错误时可上升为高）；确定性：代码证实校验边界，实际误配属于部署假设。**
- **证据**：guard 只检查多实例信号、非空和 `Path.isAbsolute`：`backend/src/main/java/com/example/configmgr/file/FileStorageMultiInstanceGuard.java:74-80`、`111-135`。storage readiness 只检查本实例目录存在/可写：`backend/src/main/java/com/example/configmgr/health/StorageReadinessProbe.java:46-68`。
- **触发条件**：两个实例配置不同绝对目录、不同挂载或不同对象存储前缀；每个目录自身都可写。
- **单例/Cluster**：单实例无影响；Cluster/多实例下 A 上传、B 下载/摘要会找不到文件。Redis 正常也不能弥补文件不共享。
- **多实例/多线程**：DB 中相对 key 全局可见，物理字节却可能只在写入实例可见；轮询打到不同实例会表现间歇性失败。
- **根因**：路径形状被误当成存储一致性证明；没有共享存储身份/读写探测协议。
- **最佳修复**：部署层使用明确共享卷/对象存储，并在启动或多实例验收中做带实例标识的写入—跨节点读取校验；配置存储 namespace/cluster ID，避免同目录不同环境混用。健康检查只报告本地能力，不宣称跨实例一致。
- **备选取舍**：持久化存储节点/键位置并路由到原实例会形成实例亲和，扩容和故障转移复杂，不推荐替代共享存储。
- **涉及修改点/影响面**：部署配置、readiness/诊断、文件键元数据和跨实例验收。

### BBA-14：Redis DOWN 时 ready 仍 UP，但作业创建和安全任务删除实际不可用

- **严重度：中（P2）；确定性：代码证实，是否应整站摘流属于产品取舍。**
- **证据**：Redis probe `required=false`：`backend/src/main/java/com/example/configmgr/health/RedisReadinessProbe.java:42-58`；ReadinessService 只把它映射到 AI capability：`ReadinessService.java:143-182`。但作业创建在 runner 登记失败时拒绝：`JobService.java:208-217`，任务删除在 Redis 状态不可读时 409：`TaskService.java:736-747`。
- **触发条件**：DB/存储健康、Redis DOWN，LB 继续均匀送业务流量。
- **单例/Cluster**：两者均出现；Cluster 整体故障时所有实例都 ready=UP，但作业面不可用。
- **多实例/多线程**：不会错误重启实例，这是优点；但运维只看到 AI degraded，看不到 jobs/deletion degraded，调用方才收到 409。
- **根因**：readiness 的 capability 模型只声明 AI，没有表达业务作业对 Redis 的硬依赖。
- **最佳修复**：保留“Redis 不拖垮整站 ready”的设计，同时增加 `capabilities.jobs`、`capabilities.taskDeletion` 或统一 `orchestration` 降级字段和清晰文档；前端/网关按能力提示。若作业是核心流量，再经产品裁决把 Redis 升为 required。
- **备选取舍**：直接把 Redis 改 required 简单，但一次 Redis 故障会摘掉全部实例，连纯 DB 查询也不可用，爆炸半径更大。
- **涉及修改点/影响面**：健康响应、监控告警、前端降级提示、部署探针；无需改变业务锁协议。

### BBA-15：readiness 并发请求可能因固定池/队列饱和把健康实例判 DOWN

- **严重度：中（P2）；确定性：代码证实饱和分支，实际触发取决于探测并发。**
- **证据**：每次 ready 不缓存并提交全部 probes：`ReadinessService.java:130-145`；执行器为 4 线程、队列 8，拒绝即 DOWN：`ReadinessProbeRunner.java:66-85`、`122-130`。配置同值：`application.yml:484-497`。
- **触发条件**：多个 LB/运维调用同时 ready，或先前 native 存储探测卡住占线程；第三到第四个并发检查即可开始排队，继续并发会拒绝后续维度。
- **单例/Cluster**：与 Redis 拓扑无关；多实例各自可能同时被同一 LB 探测，出现相关摘流。
- **多实例/多线程**：池饱和保护 HTTP 线程是有益的，但 required DB/存储维度被拒会直接 503，健康实例也可能被摘流。
- **根因**：没有 single-flight/短缓存合并同一实例几乎同时的探测。
- **最佳修复**：对相同实例的 readiness 检查做 single-flight，并缓存一个很短（例如 100～500ms，需按 LB 指标定）的快照；draining 必须保持即时 503 快路径。饱和仍返回 DOWN，不改为调用线程执行。
- **备选取舍**：增大线程/队列可推迟饱和，但慢 native I/O 会消耗更多资源，不是根治。
- **涉及修改点/影响面**：`ReadinessService`、探针指标和 LB 验证；不改变各 probe 的业务判据。

## 五、未发现问题的关键路径与已有有效护栏

1. **本范围 Redis 键访问未发现 Cluster 多键 CROSSSLOT**：取消是单键 SET/GET；runner 活跃是单键 EXISTS/SET；runner 清理明确分开 DEL 两键，见 `JobHeartbeatRegistry.java:814-868`；条件删除 Lua 只操作 KEYS[1]，见 `JobHeartbeatRegistry.java:110-125`；任务事件是单频道 Pub/Sub。不能由此推断其它 AI/对话模块也无 Cluster 问题。
2. **取消的 DB 事务边界正确**：Redis/SSE 在 TransactionTemplate 返回后执行，不再持取消 DB 连接等网络，见 `JobService.java:477-521`。剩余问题是 HTTP/作业线程等待，已在 BBA-09 描述。
3. **作业启动确实异步**：`@Async` 位于独立 Bean，且在创建事务 afterCommit 后调用，避免 Excel 主流程直接阻塞启动 HTTP 请求，见 `JobService.java:190-205`、`AsyncJobExecutor.java:28-45`。
4. **作业线程池和身份传播有界/有清理**：4/8/200 + AbortPolicy，并配置 `RequestContextTaskDecorator`，见 `AsyncConfig.java:65-87`；SSE 重试线程显式 denied，见 `TaskSseService.java:489-516`。本范围未发现把请求身份无界遗留到这些池线程的直接证据。
5. **任务并发控制有跨实例 DB 基础**：删除、建作业、上传/保存共用 task 行锁，且主要顺序为 task → job；job 状态采用条件 UPDATE，TaskFile 有 @Version。它不是纯 JVM synchronized 方案。
6. **进度轮询本身有良好治理**：实时 SSE 时真正停表；隐藏页停表；单页面在途请求合并；dirty 只补一次；终态以 DB 快照为准，见 `frontend/src/utils/job-watch.ts:92-153`、`225-285` 和 `polling.ts:107-164`。需要修的是快照响应成本，而不是再加一层重复轮询。
7. **健康 live/ready 的调用方时间边界有效**：live 无外部 I/O；ready 共享 2 秒截止、固定 daemon 池、超时不把 HTTP 线程无限挂住。不可中断 native I/O 占后台线程的边界已被代码明确处理。
8. **文件键与事务清理设计合理**：DB 只存相对 UUID key，路径拒绝上跳/越界；新文件回滚清理、旧文件提交后删除；摘要使用流式读取。问题是这些操作所在的同步/锁范围，而非摘要算法本身。
9. **无条件数据列表走 DB 分页**：存在 `(def_code, scope_type, scope_key)` 索引，见 `ConfigDataRowRepository.java:34-42` 和 `db/postgresql/V1__schema.sql:81-83`；本报告未把这条路径误报为全量扫描。
10. **主数据接口无 Redis、SSE、文件或异步调用**：地区/项目只是简单 DB 查询。它们没有分页，数据量增长时应加限制，但在既有小主数据前提未发现额外线程或 Cluster 风险。

## 六、后续验证方案（仅设计，本次未执行）

### 6.1 任务删除与作业创建

- 用三实例 + 共享 PG + Redis Cluster，构造 0/100/1000 历史 jobs、不同 staging/files 量级，测量 DELETE 和 POST jobs 的 DB 查询数、Redis 命令数、task 锁等待、响应分位数。
- 通过可控网络代理分别给单例/Cluster 注入 100ms、500ms、1s RTT 和 DOWN，验证响应有界、409/202 语义、无孤儿行/文件；不要把一次实测值外推为固定 SLA。
- 同任务并发执行删除、上传、保存、取消和创建作业，验证共同锁协议与连接池峰值；确认新实现不持 task 锁等 Redis/文件 I/O。

### 6.2 作业与 DB 池

- 分别运行大 EXPORT/PRECHECK/IMPORT/PUBLISH，记录作业线程数、每作业连接数、Hikari 等待、长事务时长、DB 锁、堆峰值和 Redis/SSE 时间。
- 并发 8 个作业再叠加普通 HTTP 与 ready 探测，验证不会出现连接饿死；检查移除 demo sleep 后的处理时延。
- Redis DOWN 时从另一实例取消，验证 DB cancel fallback 能在明确分片边界停止；长 Excel 段应有可取消检查或明确不可取消状态。

### 6.3 查询与轮询

- 对条件列表/count 使用 1 万/10 万行和大 JSON，比较改造前后 DB 传输、解析行数、堆和 p95；验证 H2/PG 条件语义一致。
- SSE 正常、断线、丢帧三种模式分别观察 3 秒轮询和 JobSnapshot 查询，确认 DTO 不加载 items/resultJson，单页面无重叠、多页面有合理请求上界。
- diff 验证按 job 快照而不是后续 task 当前值，翻页顺序稳定且不全量加载。

### 6.4 文件与 SSE

- ZIP 测试 entry 数、总解压字节、压缩比、非法 XLSX、GBK 文件名和写盘失败；验证短事务外预处理、回滚清理和并发删除无孤儿。
- 大文件/多文件下载验证首字节时间、堆平坦性、连接背压、客户端中断后的资源释放；EXPORT/UPLOAD 同名时 ZIP 不失败且命名可预期。
- 同任务建立 1/2/10 个停读 SSE 客户端，验证取消接口和作业进度不再按订阅者数串行等待；共享写池饱和不得误伤无界扩散，重连后轮询能恢复权威状态。

### 6.5 定义、多实例存储和健康

- 三实例并发执行定义删除与发布/选择/引用更新，验证 FK/共同锁能阻止依赖破坏，冲突码可重试。
- 两实例使用同一 key 做跨节点写入—读取—删除验证；错误挂载必须在验收中失败，而不是仅靠绝对路径通过。
- 多 LB 并发 ready 和慢存储场景验证 single-flight，draining 仍立即 503；Redis DOWN 时 jobs 能力降级可见，纯 DB 业务按产品裁决继续服务。

## 七、本次审查边界

本次没有运行任何动态验证，因此所有性能结论均为代码路径、复杂度和资源占有的静态结论；没有给出未经测量的“必然超过 30 秒”断言。Redis Cluster 下重点应优先实测 BBA-01、BBA-02、BBA-09 的串行等待，并与对话删除所属模块的专项报告合并定位，不能将任务删除与对话删除混为一谈。
