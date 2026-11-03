# 业务运行时审查：事务、作业、并发、文件与迁移

- 审查基线：`52b09364`；读取时 `HEAD` 与该基线一致，范围内 `git diff --stat 52b09364 -- ...` 无输出。
- 审查方式：仅静态阅读生产代码与配置；未运行测试、构建、CI、服务或压测，未执行任何写库、写 Redis 或 Git 写操作。
- 本报告边界：负责 `task/job/data/definition/masterdata/file/excel/common/config/db.migration` 的非测试运行时代码。不重复“接口阻塞清单”，不评价对话删除接口的实现；文中提到入口仅为交代调用链和资源归属。
- 严重度：**高**表示可造成数据正确性破坏、作业长期不收敛、OOM 或明显的连接池耗尽风险；**中**表示有明确放大/延迟路径，但影响取决于数据量、实例数或故障窗口。
- 确定性：下文所有编号问题均为**代码证实**其机制；没有将“必然 OOM”“必然超过某秒数”等运行时结果写成事实。实际耗时、内存峰值和触发概率仍需按后续方案测量。

## 一、结论摘要

| 编号 | 严重度 | 确定性 | 结论 |
|---|---|---|---|
| BR-01 | 高 | 代码证实 | 四类作业把文件/Excel、人工休眠、Redis、SSE 与大量 DB 写放在长数据事务内，作业线程和连接被慢 I/O 同步占用。 |
| BR-02 | 高 | 代码证实 | 终态链存在嵌套 `REQUIRES_NEW`；原事务 `afterCommit` 中峰值可到 3 条连接，且 job 已提交、task 失败后只记日志，无补偿。 |
| BR-03 | 高 | 代码证实 | ZIP、XLSX、导出和打包下载均有多层全量物化；5MB multipart 上限不约束 ZIP 解压后字节或 DB 导出量。 |
| BR-04 | 高 | 代码证实 | `GLOBAL` 行以 `scope_key=NULL` 参与普通唯一约束，H2/PostgreSQL 下并发新增同业务键可产生重复行。 |
| BR-05 | 高 | 代码证实 | 预检指纹校验与真实导入之间没有固定文件快照，允许上传换件使实际导入内容不同于已通过预检的内容。 |
| BR-06 | 高 | 代码证实 | 执行器异常只记日志；看门狗又在终态结果未知时先记 `judgedJobIds`，后续永久跳过该在途作业，直至进程重启。 |
| BR-07 | 中 | 代码证实 | 导出按“命中行数”而非“扫描行数”轮询取消；全不命中、全表载入和 Excel 写盘段均可能长期不响应取消。 |
| BR-08 | 中 | 代码证实 | 建作业、删任务、启动恢复的锁/事务内串行执行 N 次 Redis 或文件摘要；删除和失败补记还为计数加载整批 CLOB 行。 |
| BR-09 | 中 | 代码证实 | 每实例每 10 秒全量读取在途 `Job` 实体及其 EAGER 条目；`jobs.status` 无索引，判死单飞只限制写、不限制重复扫描。 |
| BR-10 | 中 | 代码证实 | 定义更新无乐观锁，定义删除的引用/数据/活跃任务检查与删除之间无共同锁，跨实例守卫结论可过期。 |
| BR-11 | 中 | 代码证实 | 条件查询为分页/计数先加载并解析全部行；任务摘要逐任务读取全部历史作业和文件，形成 N+1 与历史量放大。 |

优先级建议：先处理 BR-04、BR-05、BR-06 的正确性与不收敛问题，同时做 BR-01/BR-02 的事务重排；随后以 BR-03 的流式化和硬上限降低内存峰值，再处理其余放大项。不要用 Redis 锁替代数据库唯一约束、版本或原子条件写。

## 二、审查范围与实际阅读文件

### 2.1 完整阅读的范围文件

以下路径均相对于仓库根目录；各目录列出的生产文件均已阅读。

- `backend/src/main/java/com/example/configmgr/task/`：
  - `service/`：`TaskService.java`、`TaskSseService.java`、`RedisTaskEventBus.java`、`TaskEventBus.java`、`TaskSteps.java`。
  - `config/`：`TaskEventDispatchConfig.java`、`TaskSseSchedulerConfig.java`。
  - `controller/`：`TaskController.java`、`FileController.java`、`TaskSummary.java`。
  - `entity/`：`Task.java`、`TaskItem.java`、`TaskFile.java`。
  - `repo/`：`TaskRepository.java`、`TaskItemRepository.java`、`TaskFileRepository.java`。
- `backend/src/main/java/com/example/configmgr/job/`：
  - `service/`：`JobService.java`、`AsyncJobExecutor.java`、`ExportJobRunner.java`、`ImportJobRunner.java`、`PrecheckJobRunner.java`、`PublishJobRunner.java`、`JobProgressService.java`、`JobTerminalWriter.java`、`JobHeartbeatRegistry.java`、`JobCancellationRegistry.java`、`JobWatchdog.java`、`StaleJobRecoveryRunner.java`、`RunnerActiveKeyCleanupRunner.java`、`JobProgressTotals.java`。
  - `controller/JobController.java`；`entity/`：`Job.java`、`JobItem.java`、`ValidationIssue.java`；`repo/`：`JobRepository.java`、`JobItemRepository.java`、`ValidationIssueRepository.java`。
- `backend/src/main/java/com/example/configmgr/data/`：
  - `service/`：`ConfigDataService.java`、`ConditionEvaluator.java`、`ConditionOperators.java`、`QueryCondition.java`。
  - `controller/DataController.java`；`entity/`：`ConfigDataRow.java`、`ConfigStagingRow.java`；`repo/`：`ConfigDataRowRepository.java`、`ConfigStagingRowRepository.java`。
- `backend/src/main/java/com/example/configmgr/definition/`：
  - `service/`：`DefinitionService.java`、`DependencyResolver.java`；`controller/DefinitionController.java`；`entity/`：`ConfigDefinition.java`、`ConfigField.java`；`repo/`：`ConfigDefinitionRepository.java`、`ConfigFieldRepository.java`。
- `backend/src/main/java/com/example/configmgr/masterdata/`：`MasterDataController.java`（controller）、`Project.java`/`Region.java`（entity）、`ProjectRepository.java`/`RegionRepository.java`（repo）。
- `backend/src/main/java/com/example/configmgr/file/`：`FileStorageService.java`、`FileStorageMultiInstanceGuard.java`、`StoredFile.java`。
- `backend/src/main/java/com/example/configmgr/excel/`：`ExcelReader.java`、`ExcelWriter.java`、`ExcelTemplateBuilder.java`。
- `backend/src/main/java/com/example/configmgr/common/`：`ApiResponse.java`、`GlobalExceptionHandler.java`、`ErrorBodyFactory.java`、`ErrorCodes.java`、`ResourceNotFoundException.java`、`ConflictException.java`、`CodedException.java`、`CodedIllegalArgumentException.java`、`CodedIllegalStateException.java`、`AiStateUnavailableException.java`、`WebI18nConfig.java`、`sse/SseWriteBudget.java`、`id/IdGenerator.java`、`id/TimestampIdGenerator.java`。
- `backend/src/main/java/com/example/configmgr/config/`：`AsyncConfig.java`、`AppProperties.java`、`WebConfig.java`、`JacksonConfig.java`、`FlywayConnectionPin.java`、`FlywayStartupLock.java`、`FlywayStartupLockConfig.java`、`PostgresMigrationConfig.java`、`RedisClusterTopologyGuard.java`、`SecurityHeadersFilter.java`。
- `backend/src/main/java/db/migration/V6__Task_child_fk.java`。
- `backend/src/main/resources/`：`application.yml`、`application-cluster.yml`、`application-h2tcp.yml`、`application-postgresql.yml`。
- `backend/src/main/resources/db/migration/` 与 `backend/src/main/resources/db/postgresql/`：两套 V1～V12 中实际存在的全部 SQL；即 V1、V2、V3、V4、V5、V6（PG SQL）、V7、V8、V9、V10、V11、V12，以及 H2 侧对应的 SQL 集合。

### 2.2 为闭合调用链而额外阅读的依赖

- `backend/src/main/java/com/example/configmgr/ai/tenant/RequestContextTaskDecorator.java`：确认 `@Async` 的 TTL 上下文装饰方式。
- `backend/src/main/java/com/example/configmgr/ai/tools/AiTools.java:460-559`：确认四类 AI 工具均进入同一个 `JobService.createAndStart`，未据此扩展审查整个 AI 模块。
- `backend/pom.xml:1-260`：确认 Spring Boot、Java、Apache POI、TTL、Redis/JPA/Flyway 依赖及版本来源。

未阅读测试代码，也未把测试名称或历史注释中的“实测”当作本次验证结果。

## 三、入口到线程、Redis、DB 与返回的完整调用链

### 3.1 建作业与异步返回

1. REST：`backend/src/main/java/com/example/configmgr/job/controller/JobController.java:26-31` → `JobService.createAndStart`。
2. AI：`backend/src/main/java/com/example/configmgr/ai/tools/AiTools.java:489-495`、`:510-514`、`:527-531`、`:539-543` → 同一 `createAndStart`。
3. `backend/src/main/java/com/example/configmgr/job/service/JobService.java:158-173`：开启事务，锁 task，查终态/同类在途作业/预检；`:297-313` 对历史作业逐个查 runner；IMPORT 还在 `:401-431` 逐件流式算磁盘摘要。
4. `JobService.java:174-218`：写 `jobs`，登记本地取消状态，并在事务内由 `JobHeartbeatRegistry.beginRunner` 写 Redis。后者在 `backend/src/main/java/com/example/configmgr/job/service/JobHeartbeatRegistry.java:290-315` 依次 SET `job:runner-active:<jobId>` 与 `job:beat:<jobId>`。
5. 事务提交后的 `afterCommit`：`JobService.java:190-204` → `AsyncJobExecutor.execute`。线程来自 `backend/src/main/java/com/example/configmgr/config/AsyncConfig.java:65-87` 的 `applicationTaskExecutor`，平台线程 core 4 / max 8 / queue 200，饱和拒绝；`RequestContextTaskDecorator` 在提交时捕获并回放 TTL 身份。
6. 池拒绝时：`JobService.java:195-203` 清 runner/取消登记 → `markRunnerStartFailed` 写 issue 和 FAILED → REST 仍按既有流程返回已同步为 FAILED 的 job 对象。正常提交则 Controller/AI 工具先返回 PENDING job，真实执行在池线程继续。

### 3.2 四类 runner、进度与终态

- 分发点：`backend/src/main/java/com/example/configmgr/job/service/AsyncJobExecutor.java:28-45`。
- EXPORT：`ExportJobRunner.run`（`:80-311`）→ DB 读取任务项/定义/全部已发布行 → 条件过滤 → `ExcelWriter` 写共享文件 → `task_files` → 终态。
- PRECHECK：`PrecheckJobRunner.run`（`:67-303`）→ `FileStorageService.read` → `ExcelReader` → DB 逐条写 `validation_issues`/引用集读取 → 指纹写 `jobs.result_json` → 终态。
- IMPORT：`ImportJobRunner.run`（`:62-221`）→ 文件与 Excel → 每行查已发布版本 → 批写 `config_staging_rows` → 终态。
- PUBLISH：`PublishJobRunner.run`（`:84-124`）→ `TransactionTemplate` 调 `doPublish`（`:131-265`）→ 暂存行读取、版本冲突检查、行级 upsert、REPLACE 差集删除 → 模板返回后终态。
- 进度：各 runner → `JobProgressService.updateItem/snapshot`；`snapshot` 在 `backend/src/main/java/com/example/configmgr/job/service/JobProgressService.java:104-108` 先 `heartbeatRegistry.beat` 再开 `REQUIRES_NEW` 写 DB。`beat` 在 `JobHeartbeatRegistry.java:257-265` 分别续 runner-active 与 beat，对应 `:770-800` 两次独立 Redis SET。
- SSE：各 runner → `TaskSseService.publish`（`backend/src/main/java/com/example/configmgr/task/service/TaskSseService.java:235-247`）→ 本机 emitter 逐个经 `SseWriteBudget.write` 等最多 15 秒 → `RedisTaskEventBus.publish` 在 `backend/src/main/java/com/example/configmgr/task/service/RedisTaskEventBus.java:170-199` 执行单频道 PUBLISH。
- 终态：`JobTerminalWriter.complete` 在 `backend/src/main/java/com/example/configmgr/job/service/JobTerminalWriter.java:79-100` 注册/直接执行 → `apply`（`:103-145`）→ `progressService.finish` 条件写 jobs/job_items → 锁并更新 task → 发 JOB_DONE → runner 返回 → `AsyncJobExecutor.finally` 清本地和 Redis runner 状态。

### 3.3 取消与轮询

1. `JobController.java:48-55` → `JobService.cancel`。
2. `JobService.java:489-521`：`REQUIRES_NEW` 中锁 task → jobs 条件 UPDATE → 收敛 job_items → 更新 task；模板返回后才执行 Redis/SSE。
3. `JobService.java:536-560` → `JobCancellationRegistry.cancel`：`backend/src/main/java/com/example/configmgr/job/service/JobCancellationRegistry.java:169-180` SET `job:cancel:<jobId>`，再唤醒本机 listener；随后发 JOB_DONE，最后返回 `redisWritten/wokeInProcess`。
4. 执行线程通过 `isCancelled`（`:82-92`）读本地加速位或 GET Redis；IMPORT/PRECHECK/PUBLISH 在配置项与批次边界轮询，EXPORT 的具体盲区见 BR-07。
5. `JobWatchdog` 每 10 秒扫描 DB 在途作业，判死时获得 `job:deadjudge:<jobId>` 单飞租约、写 FAILED、写取消键并撤销 runner；它不强杀工作线程。

### 3.4 文件上传、读取与下载

- 上传：`FileController.upload`（`backend/src/main/java/com/example/configmgr/task/controller/FileController.java:63-138`）在事务内锁 task；ZIP 先 `file.getBytes` 和全量解压，再逐件 `FileStorageService.store` 写盘并保存 `task_files`；提交后删旧文件，回滚删新文件。
- 预检/导入：`FileStorageService.read`（`backend/src/main/java/com/example/configmgr/file/FileStorageService.java:133-135`）整文件读成 `byte[]` → `ExcelReader.read` 构造 `XSSFWorkbook` 并返回全部行 Map。
- 导出：DB 全量行 → `filtered` 全量 Map → `ExcelWriter.write` 使用 `SXSSFWorkbook(100)` 写入分配的新文件；SXSSF 只限制 workbook 输出侧窗口，不限制上游列表。
- 下载：`FileController.java:201-218` 直接回整文件字节；`:273-355` 将多个文件逐个全量读入并写入内存 `ByteArrayOutputStream`，最后作为 `byte[]` 返回。

### 3.5 定时、启动恢复与迁移

- 心跳：`JobHeartbeatRegistry.java:412-474` 懒启动单线程 ticker，按 TTL/3 固定延迟续期；每拍显式清除身份上下文。
- 看门狗：`JobWatchdog.java:145-170` 自建单 daemon 线程，`:200-231` 每拍全量在途 DB 扫描并逐作业判定。
- 启动清理/恢复：`RunnerActiveKeyCleanupRunner`（@Order(-1)）→ Cluster 逐主节点有界 SCAN → `StaleJobRecoveryRunner`（@Order(0)）按 50 条 keyset 页读取 DB → 每作业短事务，判活后条件写 FAILED。
- Flyway：`application-postgresql.yml`/`application-h2tcp.yml` 选择共享库 profile → `FlywayStartupLockConfig` → `FlywayStartupLock.migrate` 包住整段迁移。PG 用同一物理连接的会话 advisory lock；H2 TCP 用独立连接上的行锁；`FlywayConnectionPin` 的 ThreadLocal 在 try-with-resources 中恢复/清除。

### 3.6 同步查询、任务删除与订阅返回

- 数据：`DataController.list/count`（`backend/src/main/java/com/example/configmgr/data/controller/DataController.java:71-97`）→ `ConfigDataService` 或 `ConfigDataRowRepository` → DB；条件分支在 `ConfigDataService.java:72-117` 全量读取/解析后构造 Page 或 count → 请求线程直接序列化返回，不经过 Redis/异步线程。
- 定义：`DefinitionController` 的 list/get/save/update/delete（`backend/src/main/java/com/example/configmgr/definition/controller/DefinitionController.java:27-71`）→ `DefinitionService` → 定义/字段/数据/暂存/任务项 DB 查询或事务写 → 请求线程返回实体或删除计数；模板下载另经 `ExcelTemplateBuilder` 在内存生成 byte[] 后返回。
- 主数据：`MasterDataController.java:22-32` → `RegionRepository/ProjectRepository` → DB `findAll` 或按 region 查询 → 请求线程返回列表；无本地缓存、Redis 或额外线程。
- 任务删除：`TaskController.java:90-93` → `TaskService.delete`（`backend/src/main/java/com/example/configmgr/task/service/TaskService.java:713-784`）→ 请求线程内锁 task、读 jobs 并逐项查本地/Redis runner、删除 DB 子表与 task → 提交后仍由该请求线程逐个删物理文件 → 返回统一响应。
- 任务订阅：`TaskController.java:192-195` → `TaskSseService.subscribe`（`backend/src/main/java/com/example/configmgr/task/service/TaskSseService.java:202-221`）→ 本机 emitter 表 + 按需 Redis 订阅 + 初始 HEARTBEAT → 返回 `SseEmitter`；后续帧由作业线程或 Redis 分发池经写预算线程写出。

## 四、逐项问题、根因与修复

### BR-01 高：长数据事务内执行文件、Excel、休眠、Redis 与阻塞 SSE

**证据**

- EXPORT 整体 `@Transactional`：`backend/src/main/java/com/example/configmgr/job/service/ExportJobRunner.java:80-81`；事务内全量 DB 读取与 JSON 解析在 `:179-197`，休眠/进度/Redis/SSE 在 `:199-210`，Excel 写盘在 `:224-225`。
- IMPORT 整体事务：`backend/src/main/java/com/example/configmgr/job/service/ImportJobRunner.java:62-63`；文件全量读取和 Excel 在 `:123-131`，每行 DB 查版本在 `:157-161`，批写、休眠、Redis/SSE 在 `:164-177`。
- PRECHECK 整体事务：`backend/src/main/java/com/example/configmgr/job/service/PrecheckJobRunner.java:67-68`；文件读取、二次摘要和 Excel 在 `:157-165`，逐条 issue 写 DB 在 `:175-225`，引用值全量加载在 `:229-255`、`:403-419`。
- PUBLISH 虽无类方法注解，但 `backend/src/main/java/com/example/configmgr/job/service/PublishJobRunner.java:100-113` 的模板包住 `doPublish`；其中 `:188-240` 在同一事务内做全量暂存读取、独立进度事务、休眠、取消 GET 和 SSE。
- 单次 `snapshot` 实际不是注释所称“纯内存”：`backend/src/main/java/com/example/configmgr/job/service/JobProgressService.java:104-108` → `backend/src/main/java/com/example/configmgr/job/service/JobHeartbeatRegistry.java:257-265` → `:775`、`:796` 两次 Redis SET。
- 配置的人工节拍默认每 100 行睡 300ms：`backend/src/main/resources/application.yml:449-451`。Redis 命令/连接超时均为 2 秒：`:74-84`；任务单订阅者写预算为 15 秒：`:435-448`，而 `TaskSseService.java:309-317` 对本机多个 emitter 逐个等待。

**触发条件**

大文件、大行数、多字段引用、多订阅者、Redis/共享盘变慢任一出现即可放大；不需要 Redis Cluster 特有故障。默认人工休眠本身就会让事务时长随批次数线性增长。

**单例 Redis / Cluster / 多实例 / 多线程影响**

- 单例与 Cluster 都有同步 RTT 和 2 秒故障等待；本路径 Redis 键均单键操作，没有证据表明存在 CROSSSLOT，问题不是 Cluster 语法，而是调用位置和串行次数。
- Cluster 下不同 job 键可落不同主节点，网络/节点故障会反映到作业线程；不能因为单键合法就忽略长事务占用。
- 多实例各自有 8 条作业线程和独立 Hikari 池，会把长事务、DB 锁与共享盘/Redis 压力按实例放大。慢客户端位于另一实例时还会增加 PUBLISH；本机多个慢 emitter 则逐串等待。
- 每个作业的数据事务持有连接；进度又开独立事务，DB 连接占用与 BR-02 叠加。

**根因**

把“数据一致性事务”和“耗时编排/通知”放在了同一个方法边界；同时用独立事务实时写进度，导致网络与连接嵌套。`demo-batch-delay-ms` 还将演示节拍带入生产事务。

**最佳修复方案**

1. 将四类 runner 改为“非事务编排器 + 明确短事务模板”：文件读取、Excel 解析/写盘、JSON 大对象转换不持有 DB 事务。
2. IMPORT/PRECHECK 按批处理与提交；EXPORT 使用流式 DB 读取和流式 Excel 输出，只在一个短事务内保存最终 `task_files` 元数据。
3. PUBLISH 若必须维持“整批原子”，保留一个**纯 DB** 数据事务，不在其中做 SSE、人工休眠或同步 Redis 进度心跳。进度由事务外的有界 reporter 基于线程安全快照发送；取消由独立轮询器更新本地单调标志，执行线程只读内存。不能让为了取消而进行的网络调用重新进入长事务。
4. 生产关闭/移除 demo sleep；如演示确需保留，用独立 profile 隔离。
5. 事件只在相应 DB 提交后发布；通知失败不影响已提交数据，但必须保留恢复/重查机制。

**备选取舍**

仅把 `fileStorage.read`、`excelWriter.write` 移到事务外，改动较小，能立刻缩短连接占用，但 IMPORT/PRECHECK 的大量 DB 写仍需要长事务；直接分批提交则不再整批回滚，需要按配置项记录提交状态与重跑清理。PUBLISH 若强要求全批原子，不应为缩短事务而擅自分批。

**涉及修改点/影响面**

四个 runner、`JobProgressService`、`JobCancellationRegistry`、`TaskSseService` 的调用边界；`application.yml` 演示节拍；作业取消、进度顺序、失败回滚与文件回收语义均需联动回归。

**后续验证方案（只写不执行）**

在 H2 TCP 与 PostgreSQL、standalone Redis 与 Cluster 各构造大输入；记录作业线程的事务/连接持有时间、每批 Redis 命令数、SSE 停读时的 DB 连接数。分别验证提交前取消、提交后通知失败、Redis 断连、共享盘停顿和池饱和，断言数据事务内无文件/SSE/Redis 调用。

### BR-02 高：终态嵌套事务占多连接，且 job/task 部分失败后无补偿

**证据**

- `backend/src/main/java/com/example/configmgr/job/service/JobTerminalWriter.java:81-89` 在原数据事务 `afterCommit` 中执行 `apply`；类注释 `:27-31` 明确此时原事务资源仍绑定。
- `apply` 在 `:113-135` 开一个 `REQUIRES_NEW`，内部又调用 `progressService.finish`；后者在 `backend/src/main/java/com/example/configmgr/job/service/JobProgressService.java:137-158` 再开自己的 `REQUIRES_NEW`。
- 因而 EXPORT/IMPORT/PRECHECK 的 afterCommit 路径在 inner finish 阶段可同时保留：已提交但未清理的原数据连接、terminal 连接、progress 连接，峰值 3 条。普通无外层事务路径也可同时使用 terminal + progress 两条。
- job 条件 UPDATE 已在独立事务提交后，才锁/写 task：`JobTerminalWriter.java:119-133`。任一后续异常被 `:142-145` 捕获并仅记日志；没有重试、回滚 job 或待补偿记录。job 已终态后看门狗不再扫描它。
- 作业池上限 8：`backend/src/main/java/com/example/configmgr/config/AsyncConfig.java:69-78`；PG/H2 TCP Hikari 上限 10：`backend/src/main/resources/application-postgresql.yml:22-29`、`application-h2tcp.yml:33-36`。

**触发条件**

多个作业同时收尾可放大连接需求；4 个上述 afterCommit 同时到 inner finish 的理论需求已为 12，超过单实例池上限 10。task 锁冲突、DB 抖动或写 task 失败则形成 job 已终态、task 未联动的持久分裂。

**单例 Redis / Cluster / 多实例 / 多线程影响**

该分裂由 DB 事务边界造成，与 Redis 单例/Cluster 无关；Redis/SSE 仅增加 afterCommit 的停留时间。多实例共享同一 DB 时，各实例池上限之和增加库侧压力；同实例多线程可触发连接排队，跨实例 task 锁与乐观锁冲突可触发部分提交。

**根因**

为避免 job→task 锁反而拆成多个独立事务，却没有把最终结果纳入同一个原子 DB 单元或持久补偿；`apply` 吞异常又使调用方无法判断是否需要重试。

**最佳修复方案**

把终态写改成一个短 DB 事务，统一采用既有 **task → job** 锁顺序：先尝试锁 task（不存在仍允许收 job），再在同事务内执行 jobs 条件 UPDATE、job_items 收敛、task 状态/步骤更新并一次提交。为 `JobProgressService` 提供加入当前事务的终态方法，移除 terminal→progress 的嵌套 `REQUIRES_NEW`。同时把 runner 编排改为数据事务模板返回后再调用终态服务，避免原 afterCommit 仍占数据连接；JOB_DONE 在终态提交后发送。

**备选取舍**

若必须保持 job 与 task 分库/分事务，需引入同事务 outbox 或持久补偿表、定时重试和幂等消费，不能只把异常改日志；该方案支持最终一致但实现和运维成本更高。单纯放大 Hikari 只能推迟耗尽，不能修复状态分裂。

**涉及修改点/影响面**

`JobTerminalWriter`、`JobProgressService`、四个 runner 的事务编排、task/job 锁顺序、看门狗/启动失败/取消共用终态语义；需更新所有依赖“job 先独立提交”的注释和测试设计。

**后续验证方案（只写不执行）**

并发执行 job 收尾、任务删除、取消和步骤更新，验证无死锁且 task/job/job_items 同生共死；注入 task 写失败，断言 job 不会单独终态；统计 afterCommit/正常终态峰值连接数，并验证多实例同时收尾不超过容量预算。

### BR-03 高：ZIP/XLSX/导出/下载存在多层无界内存物化

**证据**

- ZIP 上传先读完整压缩包：`backend/src/main/java/com/example/configmgr/task/controller/FileController.java:73-76`；`extractZip` 在 `:186-196` 对每个 entry 调 `readAllBytes` 并把所有 entry 保存在列表。文件名出现替换符时 `:178-183` 还会完整解压第二遍。
- multipart 的 5MB/6MB 只限制请求：`backend/src/main/resources/application.yml:28-34`；代码没有 entry 数、单 entry 解压大小、总解压大小或压缩比上限。
- 文件通用读取为 `Files.readAllBytes`：`backend/src/main/java/com/example/configmgr/file/FileStorageService.java:133-135`。
- `backend/src/main/java/com/example/configmgr/excel/ExcelReader.java:30-75` 同时持有输入 `byte[]`、完整 `XSSFWorkbook` 和全部行 Map。
- EXPORT 在 `backend/src/main/java/com/example/configmgr/job/service/ExportJobRunner.java:179-197` 同时持有全部 JPA 实体 `allRows` 与全部命中 Map `filtered`；`ExcelWriter.java:30-69` 的 SXSSF 不回收这两个输入列表。
- PRECHECK 的引用集合在 `backend/src/main/java/com/example/configmgr/job/service/PrecheckJobRunner.java:403-419` 对每个引用字段重新加载全部已发布与暂存行。
- 打包下载在 `FileController.java:344-355` 逐文件全量读取并写入内存 ZIP；`:273-282` 最终再返回整个 `byte[]`。

**触发条件**

高压缩比 ZIP、大量 entry、大 XLSX、大行数导出、多并发上传/下载任一即可增加堆压力。是否达到 OOM 取决于实际堆、压缩内容、行宽与并发，当前静态审查不宣称已发生 OOM。

**单例 Redis / Cluster / 多实例 / 多线程影响**

与 Redis 拓扑无直接关系；Cluster 不提供文件容量保护。多实例分别承受本机堆压力，共享后端不能隔离 OOM。8 条作业线程加上传/下载请求线程可并行物化多份数据；同一作业内 `allRows + filtered + workbook` 形成叠加峰值。

**根因**

API 以 `byte[]`/`List` 为核心数据契约，把压缩大小误当成内容大小；输出侧虽用了 SXSSF，上游仍全量物化，缺少端到端字节/行数预算。

**最佳修复方案**

- ZIP 使用流式逐项处理，设置 entry 数、单件/总解压字节、最大压缩比和允许扩展名上限；超出即拒绝并清理临时文件，不做二次全量解压。
- Excel 读改为 POI 事件/SAX 流式读取，以行回调交给预检/批写；保留必要的每配置项内存索引，但设置硬上限。
- EXPORT 使用只读流/游标按主键或分页前进，命中行直接交给 `ExcelWriter`，不在内存保留 `allRows` 和 `filtered`。
- 下载使用 `StreamingResponseBody`/资源流，ZIP 边读边写响应，并设置文件数与总字节预算。
- 引用校验按字段批量查询/缓存当前作业只读快照，避免每个字段重复全表加载；缓存必须有行数/字节上限。

**备选取舍**

短期只加“上传解压后大小 + Excel 行数 + 下载总大小”硬上限，改动小且能防止失控，但会拒绝合法大文件；完整流式化支持更大业务数据，改动面和兼容验证更大。不能仅把 XSSFWorkbook 换成另一个全量模型。

**涉及修改点/影响面**

`FileController`、`FileStorageService`、`ExcelReader/Writer`、四个 runner 中的数据消费方式、下载响应契约、文件回滚清理；需要同步定义最大文件/行数/错误数规格。

**后续验证方案（只写不执行）**

使用高压缩比 ZIP、超entry数、大行列 XLSX、宽 JSON 行和多文件导出，在固定堆下测峰值与老年代增长；并发跑 8 个作业及多个上传/下载，验证超预算明确失败、临时文件清理、输出内容逐行等价。

### BR-04 高：GLOBAL 可空 scope_key 使唯一约束无法阻止并发重复插入

**证据**

- `scopeKey` 可空：`backend/src/main/java/com/example/configmgr/data/entity/ConfigDataRow.java:25-26`；GLOBAL 调用实际传 null，例如 `ConfigDataService.save` 在 `:119-132` 只把 scopeType 默认成 GLOBAL，不规范化 scopeKey。
- 唯一约束为 `(def_code, scope_type, scope_key, row_key)`：`backend/src/main/resources/db/migration/V1__schema.sql:58-70` 与 `backend/src/main/resources/db/postgresql/V1__schema.sql:71-83`。
- 本仓另一迁移明确记录 H2/PG 普通唯一约束允许多个 NULL：`backend/src/main/resources/db/migration/V12__task_ai_create_idempotency.sql:9-12`；同一 NULL 语义适用于这里的可空列。
- 写入采用“先 `findRow`、无则 new/save”：`ConfigDataService.java:123-132`；发布路径 `:209-225` 也先查再 upsert。`@Version`（`ConfigDataRow.java:34-35`）只能保护已存在行的更新，不能为两个新 INSERT 提供版本裁决。

**触发条件**

两个线程或两个实例同时新增同一 GLOBAL `(defCode,rowKey)`，双方都查不到现有行，随后分别 INSERT；普通唯一约束中的 NULL 不冲突。 REGION/PROJECT 的非空 scopeKey 不受这一特定空值漏洞影响。

**单例 Redis / Cluster / 多实例 / 多线程影响**

共享 DB 下单 JVM 多线程和多 JVM 都可触发，Redis 是否单例/Cluster 不影响结果。之后 `findRow` 返回 `Optional` 还可能因多行而失败，导出和发布会读到重复业务键。

**根因**

把可空维度直接放入需要“NULL 也相等”的业务唯一键，同时依赖应用层 check-then-insert；没有可由两种数据库一致执行的权威唯一判据。

**最佳修复方案**

统一把 GLOBAL 的 `scope_key` 规范化成非空哨兵（例如固定内部值，需与真实编码命名空间隔离）：先盘点并人工/规则合并已有重复行，再回填 NULL、改列 NOT NULL，保留现有四列唯一约束。所有读、写、导入、种子与迁移均使用同一规范化函数；新写入仍保留唯一冲突的原子兜底。

**备选取舍**

PostgreSQL 15+ 可用 `NULLS NOT DISTINCT`，但 H2 需要另一套生成列/表达式索引，双数据库分叉和迁移验证成本高；数据库 `MERGE`/upsert 可减少应用竞态，但若唯一键仍含可空 NULL，仍不能解决根因。

**涉及修改点/影响面**

`ConfigDataRow`、两套新 Flyway 迁移、`ConfigDataService`、导入/发布/种子写入和所有 scope 查询；存量 NULL 数据与重复行需上线前核查。

**后续验证方案（只写不执行）**

在 H2/PG 上用两个独立 DB 会话同时插入同一 GLOBAL 键，断言一笔成功、一笔唯一冲突；覆盖 NULL 存量升级、重复存量拒绝升级、REGION/PROJECT 不变、单实例多线程与双实例同键发布。

### BR-05 高：预检通过与实际导入之间没有固定文件快照

**证据**

- 建 IMPORT 作业时，`backend/src/main/java/com/example/configmgr/job/service/JobService.java:158-167` 在事务内完成守卫；`:401-431` 只对“当时的 UPLOAD 路径”重算并比对摘要。源码自身在 `:398-399` 明确登记 guard→落库→afterCommit 的 TOCTOU 窗口。
- 作业直到事务提交后才异步启动：`JobService.java:190-194`。
- 真正导入在 `backend/src/main/java/com/example/configmgr/job/service/ImportJobRunner.java:123-131` 重新查询当前 `task_files` 并读取其路径，没有使用建作业时摘要对应的固定 storageKey。
- 上传可在 `backend/src/main/java/com/example/configmgr/task/controller/FileController.java:156-170` 换 `task_files` 引用，提交后删除旧文件；runner 不持 task 锁，上传与 runner 之间没有共同的文件代次锁或快照引用。

**触发条件**

用户在预检通过后、IMPORT 创建/执行的任意窗口替换 UPLOAD 文件；也包括 A 实例建作业、B 实例上传换件。若旧文件被删除，可能失败；若新文件可读，则可能导入未经该次预检证明的内容。

**单例 Redis / Cluster / 多实例 / 多线程影响**

与 Redis 拓扑无关；共享 DB + 共享文件使跨实例换件同样成立。TaskFile 的 `@Version` 只能防止两个并发写同一元数据行互相覆盖，不能把此前的指纹检查绑定到之后的实际读取。

**根因**

预检凭证只保存摘要，没有把“作业输入 = 不可变 storageKey + 摘要 + 文件代次”持久化为作业快照；异步执行又扩大了检查和读取之间的时间窗口。

**最佳修复方案**

在作业创建时持久化每个 defCode 的不可变 storageKey、摘要和 TaskFile 版本；runner 只读该快照。旧文件只有不再被任何在途/可恢复作业引用时才能删除，可采用引用计数或保留代次并由有界清理器回收。启动前在当前事务内再核对当前文件代次，若产品要求“必须仍是最新上传件”，不一致则拒绝；否则明确允许按快照导入。

**备选取舍**

简单方案是“相关作业在途时禁止上传换件”，利用现有 runner 闸门，改动小但降低可用性；仅延长 task 锁到整个导入会再次制造长锁和慢事务，不建议。

**涉及修改点/影响面**

jobs 新增输入快照存储（或关联表）、两套迁移、`JobService`、`ImportJobRunner`、上传替换和旧文件回收；预检指纹摘要格式与历史作业兼容需明确。

**后续验证方案（只写不执行）**

在指纹检查后、afterCommit 前、Excel 读取前分别换件，验证 runner 读取的 SHA-256 恒等于作业快照；覆盖旧件保留/回收、双实例上传与导入、任务删除和失败重跑。

### BR-06 高：执行器与看门狗的失败收敛可中断，后者会永久跳过仍未终态的作业

**证据**

- `backend/src/main/java/com/example/configmgr/job/service/AsyncJobExecutor.java:38-45` 对 runner 逃逸异常只记录日志，finally 清 runner/心跳/取消登记，没有立即写 FAILED。
- 清掉共享键后，看门狗对 ABSENT 不判死：`backend/src/main/java/com/example/configmgr/job/service/JobWatchdog.java:293-307`；这类作业通常只能等 `:219-225` 的 30 分钟绝对上限。
- 更严重的是 `judge` 在 `:349` 先把 id 加入 `judgedJobIds`，随后 `:373-376` 调 `terminalWriter.complete`；而 complete/apply 在 `JobTerminalWriter.java:142-145` 吞掉异常，不向看门狗报告失败。
- 下一拍 `JobWatchdog.java:214-217` 对 judged id 直接跳过；`retainTracked` 在 `:244-253` 只保留仍在在途快照中的 id，所以 DB 写失败而 job 仍 PENDING/RUNNING 时，该判重记忆不会被移除，直到进程重启。
- issue 又先于终态单独保存：`JobWatchdog.java:361-371`，简单重试还需避免重复 issue。

**触发条件**

runner 在任何自收敛之外抛出异常；或看门狗已判定、issue 写完后，终态 DB 写遇到锁超时/连接故障/约束异常。后者发生时，作业可能无限保持在途并持续阻止同任务新作业。

**单例 Redis / Cluster / 多实例 / 多线程影响**

Redis 正常时也不能修复 DB 终态写失败；Redis 故障会进一步削弱取消通知。每个实例有自己的 judged 集合，另一实例可能偶然补上，但代码不保证另一实例一定能通过单飞/DB 写，因此不能把多实例当作可靠补偿。多线程不会使本地判重状态自动过期。

**根因**

把“已尝试判定”误当成“已完成终态收敛”；终态 API 没有结果/异常契约，执行器与看门狗也没有统一、可重试的失败出口。

**最佳修复方案**

让终态服务返回明确的提交结果或对失败抛出异常；只有 DB 终态提交成功后才记录 judged。失败时清除/不设置 judged，并以有界退避重试；issue、job、job_items、task 尽量纳入 BR-02 的同一原子事务。`AsyncJobExecutor.catch` 调同一幂等 FAILED 收敛入口，finally 仍负责资源清理。

**备选取舍**

保留分步写时，取消本地永久判重，每拍依赖 DB 条件 UPDATE 重试，并给 issue 增加 `(job_id,kind)` 唯一键或确定性幂等键；实现较小但会增加重复扫描/写尝试。单纯缩短 30 分钟上限不能修复 judged 永久跳过。

**涉及修改点/影响面**

`AsyncJobExecutor`、`JobWatchdog`、`JobTerminalWriter`、`JobProgressService`、issue 唯一性/迁移及所有终态调用方；需保持取消先到者胜和事件不重复。

**后续验证方案（只写不执行）**

分别注入 runner 开工前异常、终态 jobs 写失败、task 写失败、issue 写失败和 Redis 不可用；跨多个看门狗周期验证最终 FAILED、无重复 issue、无永久 RUNNING、取消/完成竞态仍由条件写裁决。

### BR-07 中：EXPORT 取消轮询由命中行数驱动，存在长盲区

**证据**

- 扫描计数是 `rowsSeen`，但批次条件使用 `filtered.size()`：`backend/src/main/java/com/example/configmgr/job/service/ExportJobRunner.java:191-211`。只有条件命中且累计命中数达到 100 的倍数，才更新进度并调用 `isCancelled`。
- 全表 DB 载入 `:183`、逐行 JSON/条件处理 `:191-197`、Excel 写盘 `:224-225` 都没有独立的时间型取消检查。
- 对比 IMPORT/PRECHECK/PUBLISH，它们均有配置项或批次边界检查；问题不是整个系统都没轮询，而是 EXPORT 的计数依据错误及重 I/O 段无检查。

**触发条件**

大多数行不命中、单配置项行数很多、条件解析/Excel 写盘较慢时，用户或看门狗已把 job 写成 CANCELLED/FAILED，runner 仍继续扫描/写文件，直到下一个命中批次或区段结束。

**单例 Redis / Cluster / 多实例 / 多线程影响**

跨实例取消完全依赖 Redis GET，盲区期间不会发起读取；单例与 Cluster 都受影响。作业已终态后的进度/条目条件 UPDATE 能拒绝部分迟到写，但不能停止 CPU、文件 I/O 和数据事务占用，删除也会继续被 runner 存活闸门拒绝。

**根因**

把“输出进度批次”误作“扫描取消节拍”，且将取消视为仅批次边界动作，没有为不可中断的 POI/DB 区段设置输入上限。

**最佳修复方案**

以 `rowsSeen` 驱动批次与取消；另加基于单调时钟的最大检查间隔，在全表读取前后、过滤循环、Excel 前后检查。结合 BR-01/BR-03 将长区段拆小；取消后停止产生新文件，并统一走幂等终态与文件回收。

**备选取舍**

只把 `filtered.size()` 改成 `rowsSeen` 是最小修复，能解决不命中盲区，但不能打断一次 POI 写盘或单条慢 DB 查询；要限制这类不可中断段，仍需流式分块和硬上限。

**涉及修改点/影响面**

`ExportJobRunner` 的进度分母/分子、批次事件频率、取消与文件回收；其它 runner 可复核但无需照搬修改。

**后续验证方案（只写不执行）**

构造 0 命中、每 101 行才命中和大型 sheet，分别在扫描/写盘期从另一实例取消；断言最大发现延迟、无迟到成功事件、无新孤儿文件，进度数字仍与扫描量一致。

### BR-08 中：守卫锁/事务内串行 Redis 与磁盘，计数还误用全量实体查询

**证据**

- 建作业事务与 task 锁：`backend/src/main/java/com/example/configmgr/job/service/JobService.java:158-167`；`assertNoRunnerInFlight` 在 `:297-313` 对任务全部历史作业逐个 `isRunnerAlive`，每次可落到 `backend/src/main/java/com/example/configmgr/job/service/JobHeartbeatRegistry.java:363-386` 的 Redis `hasKey`。
- 同事务 IMPORT 还逐配置项读取磁盘摘要：`JobService.java:401-431`；`beginRunner` 又在提交前做两次 Redis SET：`JobHeartbeatRegistry.java:290-315`。
- 删除任务持 task 锁与事务：`backend/src/main/java/com/example/configmgr/task/service/TaskService.java:713-717`，`:719-753` 同样逐作业查 Redis；`:755-758` 为日志中的 stagingRows 数字调用 `findByTaskId(...).size()`，会加载全部暂存实体及 `data_json` CLOB。
- 启动恢复在 `backend/src/main/java/com/example/configmgr/job/service/StaleJobRecoveryRunner.java:141-178` 的每作业事务内先锁 task，再于 `:152-160` 读 Redis。
- 发布失败补记也用全量列表计数：`backend/src/main/java/com/example/configmgr/job/service/PublishJobRunner.java:334-337`。

**触发条件**

历史作业多、配置项/文件多、Redis 或共享盘慢、多个请求争同一 task 行时，锁与 DB 连接会随 N 次 I/O 串行占用。每个 Redis 命令配置超时 2 秒，不代表整链最多 2 秒。

**单例 Redis / Cluster / 多实例 / 多线程影响**

所有 Redis 操作都是单键，Cluster 下无跨槽错误；但 N 个键可能跨节点，Cluster 不能用一个不顾槽位的 MGET 作为通用修复。多实例共享 DB 时 task 行锁跨 JVM 生效，因此某实例慢 I/O 会阻塞其他实例的同任务操作。多线程对同一 task 串行等待，对不同 task 则放大 DB/Redis 总压力。

**根因**

把 fail-closed 的正确性要求误解为必须在锁内逐条做远程调用；日志计数和失败总数也复用了实体列表查询而非聚合投影。

**最佳修复方案**

- 立即把 staging 计数改成 `countByTaskId`，失败补记同样使用聚合查询。
- 建作业先读取最小文件元数据并在锁外算摘要，锁内用 TaskFile 版本/代次复核，避免锁内磁盘 I/O。
- runner 存活查询改为按 task 聚合的共享状态/代次，或按 Cluster slot 分组 pipeline 批量读取，再在最短锁内完成条件复核与写入。
- 启动恢复使用每作业恢复租约和 DB 条件写，将远程判活从持 task 锁的写入事务中剥离；若状态在间隙变化，以条件 UPDATE/代次复核为权威。

**备选取舍**

保留锁内批量 pipeline 改动较小，但仍持锁等网络；锁外预检 + 锁内复核能缩短占用，却增加版本设计和代码量。直接取消 fail-closed 会产生孤儿数据，不可作为性能修复。

**涉及修改点/影响面**

`JobService`、`TaskService`、`StaleJobRecoveryRunner`、`JobHeartbeatRegistry`、`ConfigStagingRowRepository`、TaskFile 版本复核；任务删除/建作业互斥与 Redis 不可用的 409 语义必须保持。

**后续验证方案（只写不执行）**

构造每任务大量历史作业、暂存行和上传件，分别注入每个 Redis 节点超时与磁盘延迟；记录 task 锁持有时间、连接占用和命令数，验证双实例删除/建作业仍互斥，Redis 故障仍 fail-closed。

### BR-09 中：看门狗每实例全量实体扫描，且 jobs.status 无索引

**证据**

- `backend/src/main/java/com/example/configmgr/job/service/JobWatchdog.java:145-155` 每实例启动单线程固定延迟扫描；`:200-225` 每拍调用无分页 `findByStatusIn` 并遍历全部在途作业。
- `backend/src/main/java/com/example/configmgr/job/repo/JobRepository.java:57-58` 返回完整 `Job` 实体；`Job` 的 items 是 EAGER，且有 CLOB `result_json`：`backend/src/main/java/com/example/configmgr/job/entity/Job.java:39-47`。
- 两套 V1 均只建立业务数据、暂存和 issue 等索引，jobs 定义见 `backend/src/main/resources/db/migration/V1__schema.sql:127-152` 与 `db/postgresql/V1__schema.sql:140-165`，没有 `jobs(status,...)` 索引；后续已读迁移也未补该索引。
- 判死单飞只在候选已需判定时获取：`JobWatchdog.java:328-349`，不减少健康作业在每实例上的重复 DB/Redis 读。

**触发条件**

实例数或在途作业数增加、作业长期排队、结果 JSON 较大时，每 10 秒产生重复 DB 扫描和实体水合；远端 RUNNING 作业还需共享心跳读取。

**单例 Redis / Cluster / 多实例 / 多线程影响**

单实例也有 DB 放大；多实例按实例数线性重复。Cluster 只改变心跳 GET 的节点分布，不减少读取。单飞能防重复 issue，不等于扫描单飞。每实例只有一个看门狗线程，因此本机扫描线程有界，但 DB/Redis 总负载无界于实例数。

**根因**

扫描查询直接复用含 EAGER 关联的领域实体，且把“判定写幂等”当作“读路径已去重”；迁移未跟上看门狗的高频谓词。

**最佳修复方案**

新增只含 `id/taskId/jobType/status/createdAt/startedAt/progress/total` 的标量投影，避免 items/CLOB；为两套 DB 增加合适的 `(status,id)` 索引并用真实执行计划复核。保留全量 id 快照以满足 judged 集合回收，但可以分页投影 + 完整 id 集，不能对判定本身悄悄漏作业。多实例可增加可续租的扫描 leader 或以实例分片候选，故障时必须能接管。

**备选取舍**

只做投影 + 索引改动小、保留每实例独立判定的容错；全局 leader 进一步减读，但引入租约续约与单点延迟。不能为了减少扫描直接限制 `findByStatusIn` 的 Top/Page 而不改回收逻辑，否则现有注释所述的 judged 回收会失真。

**涉及修改点/影响面**

`JobRepository`、`JobWatchdog`、两套 Flyway 新迁移、判重集合回收与健康指标；DB 索引上线需考虑锁表/并发建索引策略。

**后续验证方案（只写不执行）**

在不同在途量和 1/3/多实例下记录每拍 SQL、水合行数、Redis 命令和扫描耗时；查看 H2/PG 执行计划，杀死 leader 验证接管，确认全量 id 回收与重复 issue 防护不回退。

### BR-10 中：定义更新与删除缺少跨实例并发保护

**证据**

- `ConfigDefinition` 没有 `@Version`：`backend/src/main/java/com/example/configmgr/definition/entity/ConfigDefinition.java:14-52`。
- 更新是读取实体后逐项覆盖并合并 fields：`backend/src/main/java/com/example/configmgr/definition/service/DefinitionService.java:173-190`、`:197-226`；两个写者可基于同一旧值互相覆盖，后提交者没有版本冲突信号。
- 删除在 `:115-147` 依次检查已发布数据、引用字段、ACTIVE task，再删 staging 和定义；这些检查与删除之间没有锁定定义。
- 选择定义只锁 task：`backend/src/main/java/com/example/configmgr/task/service/TaskService.java:444-488`，没有定义行锁或定义存在性复核；数据保存 `backend/src/main/java/com/example/configmgr/data/service/ConfigDataService.java:119-132` 也不与定义删除共享锁。

**触发条件**

两个管理员/实例同时更新同一定义会丢失一方变更；删除与选择定义/数据写入并发时，删除前的“无引用/无数据/无活跃任务”结论可在提交前过期。

**单例 Redis / Cluster / 多实例 / 多线程影响**

共享 DB 下跨线程和跨实例一致存在；Redis 拓扑无关。JPA 一级缓存只在本事务内，不提供跨实例守卫。现有部分子表 FK 不包含 `task_items.def_code`/`config_data_rows.def_code` 对定义 code 的引用，不能自动封堵该竞态。

**根因**

定义实体没有版本；删除采用多项 check-then-act，而其他写路径没有共同的定义锁或数据库引用约束，检查并未成为不变量。

**最佳修复方案**

给定义增加 `@Version` 和两套 DB version 列迁移，更新/删除冲突显式映射 409。对“定义删除 vs 新增引用/数据”建立共同的定义行悲观锁或命名锁：所有相关写路径按固定顺序锁 task/definition（必须全局统一，避免与现有 task→job 形成反向环），锁内重查并写/删。能用 FK 表达的定义引用应补 FK；ACTIVE 条件仍需应用守卫。

**备选取舍**

只加 `@Version` 可修更新丢失，但不能修删除检查 TOCTOU；只在删除时锁定义而其它写路径不锁同样无效。引入完整引用 FK 更稳，但当前“历史 task_items 保留、定义可删”的产品语义需先调整，不能直接加会破坏该语义的约束。

**涉及修改点/影响面**

`ConfigDefinition`、两套迁移、`DefinitionService`、`TaskService.selectDefs`、数据保存/发布、引用字段变更及 409 映射；需审核锁顺序和历史定义展示语义。

**后续验证方案（只写不执行）**

双 DB 会话并发更新验证一方 409；并发执行删除与 selectDefs/数据新增/REFERENCE 字段新增，验证不会出现删除后新活跃引用；运行全锁顺序矩阵检查无死锁。

### BR-11 中：条件查询与任务摘要存在全量和 N+1 数据访问放大

**证据**

- 条件过滤在 `backend/src/main/java/com/example/configmgr/data/service/ConfigDataService.java:102-117` 读取某定义全部实体并逐行解析 JSON；`findPagedFiltered` 在 `:72-79` 先得到完整 matched 再 subList，`countFiltered` 在 `:58-61` 为计数也构造完整列表。
- 请求 size 无硬上限的现有契约由 `backend/src/main/java/com/example/configmgr/data/controller/DataController.java:51-56` 明确记录，`:71-84` 直通 PageRequest。
- 任务页每行进入 `backend/src/main/java/com/example/configmgr/task/controller/TaskController.java:68-75`：读取该任务全部历史 jobs，再取第一件；又读取全部 files 只为 size。页入口 `:44-55` 对每个 task 都执行该逻辑。
- jobs 的 EAGER items/CLOB 见 `backend/src/main/java/com/example/configmgr/job/entity/Job.java:39-47`，所以这里不仅是两条 N+1 SQL，还可能加载不需要的历史明细。

**触发条件**

定义行数大、任意大页大小、任务历史作业多或任务页行数增加时，DB、JSON CPU、堆和响应序列化同步放大。

**单例 Redis / Cluster / 多实例 / 多线程影响**

不经过 Redis，单例/Cluster 同构；多实例与多请求线程把同一共享 DB 的扫描和各自 JVM 堆消耗叠加。该问题不会因异步作业池有界而受限，因为读取发生在请求线程。

**根因**

用内存集合实现本可由投影/流式聚合完成的工作；摘要复用完整实体查询而非“最新一条 + 聚合计数”。

**最佳修复方案**

为任务摘要写批量投影：按本页 taskIds 分组统计 files/items，并用窗口函数或相关子查询只取每任务最新 job 的必要列；避免加载 items/resultJson。条件查询使用只读游标/stream 逐行求值，只物化当前页并单独累计总数；为 size 设明确上限。若后续把 JSON 条件下推 DB，需分别验证 H2/PG 语义，不能假设两边 JSON 函数一致。

**备选取舍**

先加 size 上限和最新 job/count 投影是小改动；条件过滤流式化仍需扫描全部行来计算精确总数，但可把堆峰值降到页大小。若接受不返回精确总数，可用 keyset 分页进一步减少工作，但属于接口语义变化。

**涉及修改点/影响面**

`ConfigDataService`、`DataController` 参数校验、`TaskController`/`TaskSummary`、仓储投影与必要的 DB 索引；分页总数、排序与条件语义必须保持一致。

**后续验证方案（只写不执行）**

测量不同历史量/行数下的 SQL 数、读取行数、堆峰值和响应时间；核对无条件/有条件、NULL scope、跨页稳定顺序、size 超限以及最新 job 同刻 id 决胜。

## 五、后续整体验证方案（仅计划，未执行）

1. **静态门禁**：新增事务边界检查，禁止 runner 数据事务中调用 `FileStorageService`、`ExcelReader/Writer`、`StringRedisTemplate`、`TaskSseService` 和 `Thread.sleep`；禁止 terminal 中嵌套 `REQUIRES_NEW`。
2. **双数据库**：H2 TCP 与 PostgreSQL 都覆盖并发唯一、版本冲突、锁顺序、迁移升级和执行计划；不能只以 H2 结果推断 PG。
3. **双 Redis 拓扑**：standalone 与真实 Cluster 分别验证所有 job 键、pub/sub、逐主节点 SCAN、故障恢复；不需要用跨槽 MGET 作为通过条件。
4. **多实例矩阵**：至少两个应用实例，覆盖 A 建作业/B 上传、A 执行/B 取消、A 死亡/B 判定、DB 写失败后多拍恢复、同一 task 跨实例删除/建作业。
5. **资源观测**：记录每实例作业线程、Hikari 活跃/等待连接、DB 事务时长、Redis 命令数/耗时、SSE 写等待、JVM 堆与临时文件；验收以资源曲线和状态一致性为准，不只测接口耗时。
6. **故障注入**：Redis 命令超时、Cluster 单主节点不可用、共享盘延迟、DB 锁超时、终态写失败、POI 处理中取消、线程池拒绝；每类故障都要有终态或明确可重试状态，不允许永久 PENDING/RUNNING。
7. **大数据回归**：以明确业务上限验证 ZIP 解压总量、Excel 行/列/单元格、导出总行数、issue 数和下载总字节；超限时错误明确且资源可释放。

## 六、未发现问题的关键路径与既有防护

以下是代码中已看到的防护，不等价于已通过运行时测试：

- **任务 AI 幂等创建**：`TaskService.createIdempotent` 的 JVM 分片锁在独立事务外持有，跨实例由 `ai_create_run/ai_create_key` 唯一约束兜底；冲突后在新事务回查，不依赖 Redis 锁。
- **任务删除/建作业互斥**：两条路径共用 task 行锁，job 终态和条目使用条件 UPDATE；runner 存活在 Redis 不可读时 fail-closed，未把“查不到”当成“可删”。BR-08 仅指出其 I/O 位置与放大，不否定该正确性设计。
- **取消主事务**：`JobService.cancel` 在事务模板返回后才写 Redis/SSE，取消状态、task 和开放条目在一个 DB 事务内；该路径没有发现事务内 SSE/Redis。
- **Cluster 键操作**：心跳、runner、取消和判死键均单键 GET/SET/DEL；清理 Lua 只有 KEYS[1]，两键删除显式拆开，未发现 CROSSSLOT。启动扫描使用有界 SCAN、Cluster 逐主节点，不使用 KEYS。
- **心跳语义**：共享新鲜度由 Redis TTL 而不是跨实例本地时间戳决定；进程级 ticker 与业务分片解耦，清理顺序对 beat/anchor 失败有补偿。
- **SSE 线程与本地表**：任务订阅重试有次数/时间上限，退订清理句柄和本地映射；Redis 分发/订阅池分离且有界，分发饱和丢的是状态投影帧而非 DB 权威状态。SSE 单帧写调用线程有 15 秒预算；本报告没有验证前端快照轮询实现。
- **上下文传播**：业务 `@Async` 池使用 `RequestContextTaskDecorator` 捕获/回放 TTL 身份；心跳 ticker 和任务订阅重试显式 `RunIdentityScope.denied()`，未把排期者身份带入全局线程。FlywayConnectionPin 的 ThreadLocal 在句柄关闭时恢复前值或 remove。
- **业务本地缓存**：本次范围内未发现 `@Cacheable`、CacheManager 或 definition/masterdata/data 的可变本地缓存，因此没有可报告的本地缓存跨实例失效问题。现有 ConcurrentHashMap 主要承载 emitter、runner、取消和判重状态；除 BR-06 的判重语义外，其清理路径有对应出口。
- **文件多实例基础**：DB 保存 UUID 相对键而不是某实例绝对路径；cluster/multi-instance/h2tcp/postgresql 或共享 DB URL 会触发绝对路径启动校验。该守卫只能验证路径形态，是否真正共享同一文件系统仍是部署前提。
- **摘要与文件回收**：文件 SHA-256 使用 8KB 流式读取；上传/导出新旧文件有提交后删旧、回滚删新，单个删除失败不阻断其它同步器。BR-05 指出的是输入代次绑定，而不是摘要算法本身的内存问题。
- **Excel 输出侧**：`SXSSFWorkbook(100)` 配合 `dispose()` 限制 workbook 行窗口并清理临时文件；未发现输出 worksheet 本身无限驻留，但上游列表问题仍见 BR-03。
- **启动恢复读取**：`StaleJobRecoveryRunner` 已用在途标量 keyset 分页代替全历史实体 `findAll`，并按作业隔离异常；BR-08 不涉及恢复候选分页的遗漏。
- **Flyway 多实例**：PG/H2 TCP profile 的启动锁包住完整 migrate，PG 锁与迁移钉在同一物理连接；PG 禁止混用 Java V6，H2/PG 两套迁移版本对应完整，未发现 Redis Cluster profile 改变迁移目录的问题。
- **主数据与条件算子**：masterdata 仅无共享可变状态的 DB 读取；条件求值器为无静态可变数据的纯函数，未发现多线程串改条件或本地缓存脏读。
- **业务 ID**：task/job/data 等 DB 主键使用数据库 IDENTITY；范围内没有把 `TimestampIdGenerator` 的毫秒值直接当作这些表的唯一主键，因而不存在本范围内的同毫秒主键碰撞问题。

---

本报告仅写入 `docs/research/审查-运行时Redis与接口性能-2026-11-04/16-business-runtime.md`。未修改任何生产/测试代码或其他文档，未执行验证方案中的任何动作。
