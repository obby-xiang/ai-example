# L 领域独立审查报告

## 1. 身份证据与审查边界

- `agentId`：`agent-951`
- 身份日志：`<会话证据根>/agents/agent-951/wire.jsonl`
- `profile.bind`：第 **6** 行，`modelAlias=kimi-code/k3.1-pro`
- 首条 `llm.request`：第 **20** 行，`model=k3.1-pro`，`modelAlias=kimi-code/k3.1-pro`
- 身份门禁结果：**通过**
- 仓库 HEAD 已核对：`17080859f7b6905aafbbea90f6d2281913bcab9e`，与指定 `17080859` 一致。
- 本次仅阅读代码和配置；未修改仓库文件，未运行测试、批量脚本或服务，未执行任何 git 写操作。

一个需要区分的口径：当前 `tasks.id/jobs.id` 使用数据库 `IDENTITY`，在现有单库中数值 ID 实际不会跨租户重复。但当前**跨租户、跨用户访问已经成立**；裸 ID Redis 键的碰撞则是按“ID 只在租户内唯一”的目标口径改造后必须消除的设计风险，二者不能混为一谈。

---

# 2. 确定问题与风险

## L-01　P0｜确定问题：任务、作业及 AI 工具全链没有租户+用户授权，存在完整 IDOR 和越权写

### 证据

任务、作业实体没有 `tenantId/userId`：

- `backend/src/main/java/com/example/configmgr/task/entity/Task.java:14-18`
- `backend/src/main/java/com/example/configmgr/job/entity/Job.java:12-20`

仓库查询均只按 ID/taskId/jobId：

- `TaskRepository.findByIdForUpdate`：`backend/src/main/java/com/example/configmgr/task/repo/TaskRepository.java:28-30`
- 全量任务列表和搜索：同文件 `40-64`
- 作业查询： `backend/src/main/java/com/example/configmgr/job/repo/JobRepository.java:19-30`
- 作业取消/终态条件写同样只按 `jobId`：同文件 `61-77`、`109-149`

REST 端点直接读写路径 ID：

- 任务列表、overview：`backend/src/main/java/com/example/configmgr/task/controller/TaskController.java:44-75`
- 任务读取、删除、选定义、设条件：`85-107`
- 跳步、导入模式、SSE、文件列表：`182-199`
- 创建/列举/读取/取消作业、问题、差异：`backend/src/main/java/com/example/configmgr/job/controller/JobController.java:26-69`

AI 工具没有任何属主检查：

- 全量任务：`backend/src/main/java/com/example/configmgr/ai/tools/AiTools.java:289-305`
- 工作区任务：同文件 `324-336`
- 按 jobId 查询：`339-349`
- 创建任务及 continuation：`415-442`
- 启动导出/预检/导入/发布：`537-590`

确认门前的目标校验只核对“工作区 taskId 是否一致、任务类型是否匹配”，不核对租户或用户：

- `backend/src/main/java/com/example/configmgr/ai/gate/ToolTargetResolver.java:114-161`

实际执行入口：

- `backend/src/main/java/com/example/configmgr/job/service/JobService.java:151-166`
- 裸 `findById`：同文件 `281-283`
- 取消：同文件 `499-531`

身份过滤器只覆盖 `/api/ai/**`，上述 `/api/tasks`、`/api/jobs` 不在边界内：

- `backend/src/main/java/com/example/configmgr/ai/tenant/TenantIdentityFilter.java:85-112`

### 完整调用链

- 只读：`GET /api/tasks|jobs` → Controller → `TaskService/JobService` → 无身份 Repository → DB。
- AI 写：`start_export/start_precheck/start_import/start_publish` → `ToolTargetResolver` → `ToolPreflightService` → `JobService.createAndStart` → 无身份 task 锁和 job 插入 → `AsyncJobExecutor`。
- AI continuation：`create_task` → 当前上下文 `taskId` → `TaskService.createIdempotent(..., continuationTaskId)` → 无属主 `findByIdForUpdate`。

### 触发场景

- 租户 1 用户 101 直接请求租户 2 的任务 overview、jobs、issues、diff，即可读取标题、条件、文件元数据、校验问题和暂存数据。
- 同租户用户 101 对用户 102 的任务同样可读写；不是只有跨租户问题。
- 知道或猜到一个 taskId/jobId 后，可删除任务、替换条件、取消他人作业。
- AI 会话中传入他人的 taskId，经本人确认后即可对该任务启动导出、预检、导入或发布。确认门证明的是“当前用户批准了这个 ID”，不是“当前用户拥有这个 ID”。
- 多实例下，请求落到 A/B 任一实例结果相同，因为 DB 共享且两边都没有属主查询。

### 影响

任务、作业、暂存差异和校验信息泄露；任务和文件被篡改；他人作业被取消；最严重时可对他人的导入任务执行发布，改变生产配置数据。属于 **P0**。

### 修改建议

1. 在 `tasks/jobs` 增加不可空 `tenant_id/user_id`；创建时从经过认证的真实请求身份固化，禁止请求体指定。
2. 所有对外 Repository 方法改为显式身份参数，例如：
   - `findByIdAndTenantIdAndUserId(id,t,u)`
   - `findByTaskIdAndTenantIdAndUserId(taskId,t,u)`
   - `findByIdForUpdate(id,t,u)` 的 JPQL 同时带三元组。
   - `UPDATE/DELETE ... WHERE id=:id AND tenant_id=:t AND user_id=:u AND ...`
3. `TaskController/JobController/AiTools/ToolTargetResolver/ToolPreflightService` 共用同一个 `OwnedTaskService.requireOwned(taskId, identity)`，不能只在一部分入口补检查。
4. 子表查询通过父任务/父作业的归属约束，或在子表冗余 `tenant_id/user_id` 并建立复合外键；禁止仅凭子表 `task_id/job_id` 认为已经隔离。
5. `findAllOrdered/search/findByStatusIn` 按用途拆开：
   - 用户列表必须 `WHERE tenant_id=:t AND user_id=:u`；
   - 系统 watchdog/recovery 可全局扫描，但必须按每条 Job 记录的身份建立执行上下文，不得借用当前请求或线程残留身份。
6. 历史数据无法证明归属时进入隔离区或只读待认领状态，不能统一回填“默认租户/默认用户”。

**后续单点验证：**两个租户各一名用户加同租户第二用户，逐一验证 get/list/overview/selectDefs/setCondition/delete/createJob/getJob/cancel/issues/diff 对非属主统一 404/403；AI `start_publish` 对他人 taskId 必须在确认卡出现前拒绝。

---

## L-02　P0｜确定问题（部署认证无法判定部分另述）：AI 身份头可由客户端自称，静态公开目录使绕过身份隔离成本为零

### 证据

过滤器直接读取客户端头，只校验数字和静态目录组合：

- `backend/src/main/java/com/example/configmgr/ai/tenant/TenantIdentityFilter.java:118-149`

没有签名、会话认证或可信 principal 校验；`TenantUserDirectory.exists` 仅确认组合在演示清单中：

- `backend/src/main/java/com/example/configmgr/ai/tenant/TenantUserDirectory.java:32-57`

全部合法身份又通过无身份接口公开：

- `backend/src/main/java/com/example/configmgr/ai/web/TenantDirectoryController.java:22-24`

### 调用链与场景

攻击者先 `GET /api/ai/tenants` 获得 `tenantId=2,userId=201`，再携带对应两个 header 调用 `/api/ai/chat`。过滤器将其作为真实身份建立上下文，AI 工具继而进入 L-01 的任务/作业链。

### 影响

在当前应用自身边界内，租户+用户上下文不构成认证证据。即使后续只给 DB/Redis 键补上 tenant/user，也不能阻止攻击者冒充属主。

### 修改建议

- 由后端认证或可信网关产生 principal；网关必须剥离外部传入的同名头并重新签名/注入。
- 过滤器只接受已验证 principal，不接受浏览器自由声明身份。
- 无法证明上游可信时默认拒绝，而不是“目录存在即通过”。

**后续单点验证：**从公网/直连后端分别发送伪造头；直连后端必须拒绝，只有经认证链路的请求能建立身份。

**无法判定部分：**仓库未包含生产网关配置，因此无法判定是否另有受信认证层；但 L-01 的无身份业务端点不依赖这一未知条件，仍然确定成立。

---

## L-03　P0｜确定问题：附件/导出文件可跨身份下载、覆盖和上传；作业 Runner 使用全局定义及数据行

### 证据

文件下载按 `taskId+defCode` 取行后直接返回字节：

- `backend/src/main/java/com/example/configmgr/task/controller/FileController.java:201-218`
- 全量/选择下载：`273-320`
- 覆盖保存：`221-270`
- 上传：`63-167`
- 模板：`358-391`

前端 AI 下载工具最终进入这些端点：

- 后端工具声明：`backend/src/main/java/com/example/configmgr/ai/tools/AiTools.java:624-632`
- 前端 API：`frontend/src/api/tasks.ts:76-100`

作业执行器同样只按 taskId/defCode：

- 导出读取定义及全部业务行：`backend/src/main/java/com/example/configmgr/job/service/ExportJobRunner.java:161-183`
- 预检读取上传文件： `backend/src/main/java/com/example/configmgr/job/service/PrecheckJobRunner.java:130-158`
- 导入删除/写入暂存： `backend/src/main/java/com/example/configmgr/job/service/ImportJobRunner.java:116-159`
- 发布读取暂存并写正式数据： `backend/src/main/java/com/example/configmgr/job/service/PublishJobRunner.java:184-222`

### 触发场景与影响

- 下载他人 `EXPORT` 或 `UPLOAD` 文件，造成业务数据直接泄露。
- 上传/PUT 覆盖他人文件，可让后续预检、导入处理攻击者提供的内容。
- 对他人任务启动导出会读取全局 `config_data_rows`；启动发布会把他人暂存数据写入全局正式表。
- A 实例被 AI 触发上传，B 实例下载同样越权；共享文件存储不会改变授权缺失。

### 修改建议

- `FileController` 每个方法先 `requireOwnedTask(taskId)`，再查询文件；`defCode` 还必须属于该任务或该请求允许的选择集。
- Runner 启动时使用不可变 `JobExecutionContext`，其中包含已授权的 tenant/user；所有定义、数据、暂存查询携带该上下文。
- 文件行唯一键改为 `(tenant_id,user_id,task_id,def_code,file_type)` 或经父任务复合外键保证。
- 禁止通过让前端传 tenant/user 参数替代服务端授权。

**后续单点验证：**非属主调用单文件、download-all、download-selected、upload、PUT、templates 全部拒绝；属主在 A 上传后可在 B 下载，非属主在 B 仍拒绝。

---

## L-04　P1｜确定问题：定义编码被全局唯一约束，既不满足租户内唯一，又会向 AI 披露全局定义

### 证据

- 全局唯一：`backend/src/main/java/com/example/configmgr/definition/entity/ConfigDefinition.java:20-21`
- H2/PostgreSQL 基线均为 `code UNIQUE`：
  - `backend/src/main/resources/db/migration/V1__schema.sql:23-31`
  - `backend/src/main/resources/db/postgresql/V1__schema.sql:23-31`
- 全量、按 code 查询： `backend/src/main/java/com/example/configmgr/definition/repo/ConfigDefinitionRepository.java:11-21`
- AI 工具直接列出/读取：`backend/src/main/java/com/example/configmgr/ai/tools/AiTools.java:171-243`
- 创建任务预检也按裸 code 验证：`backend/src/main/java/com/example/configmgr/ai/gate/ToolPreflightService.java:412-448`

### 触发场景与影响

- 租户 1 已存在 `CUSTOMER`，租户 2 创建同 code 会被全局唯一约束拒绝，直接违反“只在租户内唯一”。
- 任一 AI 身份可列出全局定义名称、字段、引用关系，可能读取其他租户定义。
- 同名 code 如果后续仅删除唯一约束而不改查询，`findByCode` 会返回不确定租户的定义并用于导出/发布。

### 修改建议

- 定义表增加 `tenant_id`，唯一键改为 `(tenant_id,code)`。
- `config_fields/config_dependencies/task_items/config_data_rows/config_staging_rows` 等以 `def_code` 关联的表必须同时携带 tenant，或改用内部 definition 主键关联。
- `findAllOrdered/findByLevel/findByCode/existsByCode` 全部带 tenant；AI 工具只能查询当前租户。
- 用户隔离若要求“用户私有定义”，还需 `owner_user_id` 和可见性规则；若定义是租户共享资源，则明确为“租户级共享”，不要把用户字段机械加到所有定义上。
- 迁移先建立复合约束和新索引，再按已证实归属回填；不能仅删除旧唯一键。

**后续单点验证：**两个租户创建相同 code 均成功，各自 `list_config_defs/get_config_def` 只能看到本租户版本，导出/预检/发布不会跨用另一租户定义。

---

## L-05　P1｜风险（目标口径下的确定设计缺陷）：AI 创建幂等、continuation 和 JVM 锁均未包含租户+用户

### 证据

全局唯一幂等列：

- `backend/src/main/java/com/example/configmgr/task/entity/Task.java:14-18`
- DB 约束：`backend/src/main/resources/db/migration/V1__schema.sql:97-103`

查询和锁：

- `backend/src/main/java/com/example/configmgr/task/service/TaskService.java:175-240`
- `CREATE_LOCKS` 仅按 `run:<runId>` 或 `key:<hash>` 分槽：同文件 `402-429`
- 哈希材料为 type/title/runId，无 tenant/user：同文件 `426-437`
- continuation 直接按 ID 锁任务：同文件 `245-277`

### 场景与影响

- 两个身份出现相同 runId 时，`findFirstByAiCreateRun...` 会复用或冲突到其他身份的任务，且返回任务标题、条目条件。
- `runId=null` 时，相同 type/title 的幂等键跨所有身份一致，可能错误复用。
- continuationTaskId 无属主校验，可把新的 runId 写入他人任务 `aiCreateRuns`。
- 正常 `runId` 来自 UUID（`AiController.java:465`），碰撞概率低，因此此项主要是目标口径和 fallback 路径风险；但 `runId=null` 和 continuation 的隔离缺口是代码确定存在的。

### 修改建议

- 唯一约束改为 `(tenant_id,user_id,ai_create_run)`、`(tenant_id,user_id,ai_create_key)`。
- 所有查重和冲突回查带完整身份；哈希材料加入 tenant/user。
- JVM 锁 scope 改为 `t:u:run:<runId>` / `t:u:key:<key>`，跨实例仍由 DB 复合唯一约束兜底。
- continuation 先锁并验证 `id+tenant+user`，否则视为不存在。
- 旧唯一约束不能在回填前直接删除；无归属历史行不得共享同一幂等命名空间。

**后续单点验证：**两身份强制相同 runId/null runId 和相同标题，分别应创建各自任务；相同 continuationTaskId 仅属主可续写。

---

## L-06　P1｜确定设计缺陷：作业 Redis 键、CHM、锁、幂等和恢复扫描均以裸 jobId/taskId 为域

### 键结构及位置

AI 资源锁：

- `ai:lock:task:<taskId>`
- `ai:lock:job:<taskId>:<JOBTYPE>`
- `backend/src/main/java/com/example/configmgr/ai/run/LeaseKeys.java:47-60`

作业取消：

- `job:cancel:<jobId>`
- `flags/listeners/negativeCacheUntilMs` 均以 `Long jobId` 为键
- `backend/src/main/java/com/example/configmgr/job/service/JobCancellationRegistry.java:42-80`

心跳、runner、判死：

- `job:runner-active:<jobId>`
- `job:beat:<jobId>`
- `job:deadjudge:<jobId>`
- `job:runner-active-host:<host>` 的 hash field 也是裸 `jobId`
- `backend/src/main/java/com/example/configmgr/job/service/JobHeartbeatRegistry.java:96-103`、`193-233`、`743-756`

任务锁使用点：

- `backend/src/main/java/com/example/configmgr/ai/gate/ToolResourceLocks.java:127-170`

### 跨实例场景

在目标“jobId 仅租户内唯一”下：

- 租户 A 取消 `job:cancel:7`，租户 B 的 job 7 在分片边界读到同一键而停止。
- 两个 job 7 的 runner-active/beat 互相覆盖，一个实例可能误判另一个实例作业失联或活跃。
- `job:deadjudge:7` 让不同租户互相阻塞判死。
- 本地 `listeners.get(7)` 可能唤醒错误 runner。
- `ai:lock:task:7` 造成跨租户误互斥；若检查/写入路径身份不一致，还可能互不误判。

当前 DB IDENTITY 使这些“同号”场景尚未成为主利用面，但裸键设计与目标约束不兼容。

### 异步上下文

`AsyncConfig` 确实传播身份：

- `backend/src/main/java/com/example/configmgr/config/AsyncConfig.java:53-57`、`69-76`

但 `Job` 不保存 tenant/user/run/session/trace，watchdog/recovery 也无法从记录恢复身份；`JobHeartbeatRegistry` 的全局 ticker 使用 `denied()` 防止串身份是正确姿态，却不能提供业务授权上下文。

### 修改建议

- 新键统一为带版本的完整作用域，例如：
  - `v2:job:cancel:<t>:<u>:<jobId>`
  - `v2:job:beat:<t>:<u>:<jobId>`
  - `v2:job:runner-active:<t>:<u>:<jobId>`
  - `v2:ai:lock:task:<t>:<u>:<taskId>`
- host 索引 field 改为完整 scoped job key。
- 所有 CHM 使用不可变 `JobScope(tenantId,userId,jobId)` 作为键。
- 键生成方法接收显式上下文，不在 watchdog/ticker 中读取随机的 ThreadLocal 身份。
- Redis value 同时记录 tenant/user，读取时与 DB 中 Job 归属复核，防止错误键或迁移串读。
- `jobs` 持久化 `tenant_id/user_id/run_id/session_id/trace_id/locale`；`AsyncJobExecutor.execute` 接收不可变执行上下文，afterCommit 捕获该对象，而非只捕获当前线程环境。
- 不双写旧裸键；旧 job 仅在 DB 记录证明身份后继续走短期 legacy 路径，且新身份路径不得盲回退旧键。

**后续单点验证：**两租户构造相同 jobId，在 A/B 实例分别取消、心跳、判死和释放锁，必须互不影响；请求在 A 创建、B 取消、C runner 轮询时也只能命中同一身份的作业。

---

## L-07　P1｜确定问题：任务 SSE 本地订阅表和 Redis Pub/Sub 频道没有身份域

### 证据

订阅入口没有属主校验：

- `backend/src/main/java/com/example/configmgr/task/controller/TaskController.java:192-195`

本地状态全部按 `Long taskId`：

- `emitters`：`backend/src/main/java/com/example/configmgr/task/service/TaskSseService.java:108-142`
- 注册订阅：`202-217`
- 发布：`235-246`

Redis 频道：

- `task:events:<taskId>`：`backend/src/main/java/com/example/configmgr/task/service/TaskEventBus.java:27-36`
- 发布/订阅：`backend/src/main/java/com/example/configmgr/task/service/RedisTaskEventBus.java:170-231`
- 本地 subscribers/listeners 同样裸 taskId：同文件 `81-85`

### 场景与影响

- 非属主连接 `/api/tasks/{id}/events`，即可持续收到 `JOB_PROGRESS/JOB_DONE`、jobId、defCode、进度和状态。
- A 实例运行作业，B 实例上任何用户订阅同一 taskId，都会收到跨实例帧。
- 若改为租户内 taskId，两个租户的事件会直接进入同一频道。

### 修改建议

- 订阅前验证 `taskId+tenant+user`，emitter/subscriber/retry 全部以完整 `TaskEventScope` 为键。
- 新频道采用 `v2:task:events:<t>:<u>:<taskId>`；信封也携带作用域，接收侧复核，不能只信频道名。
- 不把旧公共频道桥接到新身份频道，避免迁移期扩大泄露。
- 延迟重试保持 `denied()` 防残留身份，但应从订阅记录显式取得 scope，而不是依赖 ThreadLocal。

**后续单点验证：**两个身份订阅同号和不同号 taskId，在 A 发布、B 接收时只收到本人任务事件；取消/终态事件也不能跨用户。

---

## L-08　P1｜确定问题：前端切换身份未清任务缓存、共享 SSE 和 watcher，旧身份数据继续留显并可能被操作

### 证据

身份切换只调用 workspace 复位：

- `frontend/src/stores/ai.ts:10977-10986`

workspace 复位仅清任务绑定、选择和条件：

- `frontend/src/stores/workspace.ts:241-251`

任务 store 中的列表和 `knownTaskTypes` 不在复位范围：

- `frontend/src/stores/task.ts:210-238`

任务 SSE 按 taskId 全局共享：

- `frontend/src/api/tasks.ts:125-146`

原生 `EventSource` 不带身份头；HTTP 拦截器只给 AI 对话路径加头：

- `frontend/src/api/http.ts:153-174`
- `frontend/src/utils/ai-identity.ts:209-227`

发布 watcher 也是全局 `taskId:jobId`：

- `frontend/src/stores/task.ts:149-154`、`485-545`

### 场景与影响

同一浏览器页签从租户 1 用户 101 切换到租户 2 用户 201 后：

- 旧任务列表和类型缓存仍在内存中；
- 旧 taskId 的 EventSource 不关闭，新页面仍可能收到旧身份事件；
- 旧 watcher 继续轮询旧 jobId 并调用冲突明细；
- 后续即使后端增加身份头校验，前端旧连接和旧请求也不会自动获得正确的新身份。

### 修改建议

- 在身份写入前同步完成：停止全部 watcher、关闭 taskEventStreams、取消在途请求、清空 `useTaskStore` 和向导页面任务相关缓存。
- 所有内存 Map key 加 `tenant:user`，回调执行时核对捕获身份与当前身份。
- 业务请求也携带经认证的身份；不能只覆盖 `/ai`。
- 原生 EventSource 不能自定义 header，改用支持 header 的 fetch SSE，或使用短时、单次、绑定 tenant+user+task 的订阅票据；不要把长期身份 secret 放 query。
- 旧响应落地前必须校验身份代次。

**后续单点验证：**A 身份任务运行中切到 B，旧列表立即消失、连接和轮询全部停止，B 收不到 A 的后续帧；切回 A 也不得恢复已失效的旧 watcher。

---

## L-09　P1｜确定问题：本领域存在明确的租户枚举/目录依赖，违反生产硬口径

### 全部发现的枚举点

1. 静态全租户清单：
   - `backend/src/main/java/com/example/configmgr/ai/tenant/TenantUserDirectory.java:32-42`
2. 无身份全租户接口：
   - `backend/src/main/java/com/example/configmgr/ai/web/TenantDirectoryController.java:22-24`
3. 每次身份验证循环租户列表：
   - `backend/src/main/java/com/example/configmgr/ai/tenant/TenantUserDirectory.java:44-57`
4. 前端加载全目录并默认选择第一租户第一用户：
   - `frontend/src/stores/ai.ts:10808-10817`、`10870-10884`

### 影响

不仅是信息泄露：实现把“能否得到全租户目录”变成身份启动和校验的前置能力，且默认身份不是来自真实用户请求。生产没有该能力时，整条身份初始化不可用。

### 修改建议

- 删除 `/api/ai/tenants` 生产端点和默认第一身份逻辑。
- 身份来自登录请求或可信 principal；需要校验单个组合时按 `(tenantId,userId)` 点查，不能遍历目录。
- 只允许缓存真实请求中已经验证的身份；缓存 key 同样是 tenant+user。
- 前端只显示当前 principal，不提供任意身份切换目录。

**后续单点验证：**无目录接口的环境下完成登录、会话创建、AI 请求和任务操作；运行日志与代码路径均不得出现全租户扫描。

**除此之外：**L 领域未发现其他“枚举租户列表”的依赖。`JobWatchdog/StaleJobRecoveryRunner` 是全局作业扫描，不是租户目录枚举，但改造后必须使用每条作业记录的身份，不能把它改为循环租户列表。

---

## L-10　P1｜无法判定部署共享性；确定代码缺口：多实例文件守卫只验证绝对路径，不能证明实例间共享文件

### 证据

- `backend/src/main/java/com/example/configmgr/file/FileStorageMultiInstanceGuard.java:74-80`
- 实际校验仅要求路径为绝对路径：`111-135`
- 默认存储：`backend/src/main/resources/application.yml:476-486`

### 场景与影响

A、B 都配置 `/data/files`，但分别是本机目录，启动守卫会通过。A 上传成功，B 根据共享 DB 的 `storage_path` 查文件时不存在，导致下载、预检、导入失败。

代码无法证明生产是否使用 NFS/共享卷/对象存储，因此共享性本身列为无法判定；守卫无法覆盖目标“同一用户请求可落任意实例”则是确定缺口。

### 修改建议

- 使用统一对象存储、真正的共享卷或 DB 文件服务，并给所有实例相同存储命名空间。
- 启动期增加同命名空间写读探针和明确配置，而不是把绝对路径等同共享存储。
- 探针只验证基础设施，不替代 L-03 的属主授权。

**后续单点验证：**A 上传/导出，B 下载、计算指纹并预检；B 保存，C 再下载，字节和 SHA-256 完全一致。

---

## L-11　P2｜确定问题：观测日志和健康指标缺少 tenant/user 维度，业务异步缺 run/session/trace 传播

### 证据

MDC 只有 run/session/instance：

- `backend/src/main/java/com/example/configmgr/ai/observe/AiTrace.java:37-50`
- 日志 pattern：`backend/src/main/resources/application.yml:513-517`

业务作业池只装饰身份，不装饰 MDC：

- `backend/src/main/java/com/example/configmgr/config/AsyncConfig.java:53-57`、`75-76`

`Job` 不保存 run/session/trace/language：

- `backend/src/main/java/com/example/configmgr/job/entity/Job.java:19-60`

watchdog 指标为全局聚合：

- `backend/src/main/java/com/example/configmgr/job/service/JobWatchdog.java:172-185`
- 通过无身份 `/api/ai/health` 暴露：`backend/src/main/java/com/example/configmgr/ai/web/AiController.java:2621-2625`、`2694-2700`

### 场景与影响

- 多租户日志合并后，同一个 taskId/jobId/defCode 无法确定归属，跨实例排障容易误判。
- AI 发起的作业与原始 run/session 无法可靠串联。
- 健康接口泄露跨租户作业总量和异常计数，虽不含正文，仍是全局运行信息。

### 修改建议

- MDC 增加 `tenantId/userId/traceId`，业务 Job 执行时从持久化上下文显式绑定并 finally 恢复。
- run/session/language 只在确有业务需要时传播；语言不得依赖调度线程默认 Locale。
- 全局 watchdog 指标仅限认证运维面；用户健康面不得返回全局明细。需要租户指标时按真实请求身份计算，不枚举租户。
- 日志只记元数据，不增加文件内容、条件正文或对话正文。

**后续单点验证：**两租户相同业务 ID 的 A/B 实例日志可按 tenant+user+run+trace 唯一串联；定时器日志不带上一次请求的身份。

---

## L-12　P2｜确定问题：feedback 行级隔离正确，但 message/session/摘录归属可由客户端伪造

### 证据

写入只验证 messageId/sessionId 非空和 rating 合法，随后直接接受客户端 sessionId/excerpt：

- `backend/src/main/java/com/example/configmgr/ai/web/AiController.java:3007-3061`

没有验证 messageId 是否属于当前身份、是否属于 sessionId、是否为可评价的 assistant 消息，也没有服务端生成摘录。

### 场景与影响

同一用户可用另一个 messageId/sessionId 创建或覆盖自己命名空间下的反馈，并提交任意 excerpt。不会覆盖其他用户的行，但会破坏反馈审计完整性，也可把反馈挂到不属于该会话的消息上。

### 修改建议

- 根据当前 tenant+user 查询消息归属，校验 messageId 与 sessionId 的服务端关系。
- sessionId 从消息记录取得，excerpt 由服务端内容生成，忽略客户端这两个字段。
- 保留现有 `(tenant_id,user_id,message_id)` 唯一约束；并发 upsert 应使用数据库原子 upsert 或处理唯一冲突。

**后续单点验证：**伪造他人 messageId、跨 session messageId、伪造 excerpt 均拒绝；同身份重复反馈仍幂等。

---

# 3. 已证安全或局部安全的实现

## S-01　表单附件身份隔离链基本完整

- 登记、读取、消费、墓碑、表单/会话索引均由 `RequestContextUtils.require()` 得身份：
  - `backend/src/main/java/com/example/configmgr/ai/form/RedisFormAttachmentStore.java:144-158`、`192-223`、`691-711`
- 进程内 fallback 也使用带身份的 registration key，而不是共享 fileId：
  - 同文件 `268-280`、`518-530`
- 上传 sessionId 不信任请求参数，而取 run 快照：
  - `backend/src/main/java/com/example/configmgr/ai/form/GenerativeFormAttachmentController.java:142-189`、`245-265`
- 回灌复核 runId/toolCallId：
  - `backend/src/main/java/com/example/configmgr/ai/form/GenerativeFormGuard.java:506-539`

这些路径没有发现跨身份直接读取/消费证据。物理文件仍需与 L-03 的业务文件授权区分。

## S-02　feedback 的租户+用户行隔离和删除面正确

- 唯一键 `(tenant_id,user_id,message_id)`：
  - `backend/src/main/java/com/example/configmgr/ai/feedback/AiFeedback.java:24-43`
- 所有 Repository 查询带身份：
  - `backend/src/main/java/com/example/configmgr/ai/feedback/AiFeedbackRepository.java:14-20`
- 会话销毁按 `session_id+tenant_id+user_id` 枚举和删除：
  - `backend/src/main/java/com/example/configmgr/ai/session/SessionPurgeDatabase.java:71-90`

因此 L-12 是归属完整性问题，不是跨用户行覆盖问题。

## S-03　文件路径穿越防护和随机存储键实现正确

- 新键为 UUID+受限扩展名：`backend/src/main/java/com/example/configmgr/file/FileStorageService.java:78-99`
- 拒绝绝对路径、`..` 和越出基目录：`108-130`

这能防路径穿越，但不能替代 FileController 的属主检查；当前 IDOR 是先合法读到他人 DB 行，再读取其中的合法 UUID。

## S-04　静态缓存/观测面中未发现额外业务数据泄露

- `TaskSteps` 是不可变步骤注册表，不含业务数据：
  - `backend/src/main/java/com/example/configmgr/task/service/TaskSteps.java:50-56`
- `FormOptionSourceRegistry` 是编译期闭集数据源注册表，不缓存租户选项：
  - `backend/src/main/java/com/example/configmgr/ai/form/FormOptionSourceRegistry.java:20-48`
- `RedisDegradation` 只按固定事件名保存计数/时间：
  - `backend/src/main/java/com/example/configmgr/ai/observe/RedisDegradation.java:111-155`
- `SettlementObservationStore` 实际代码使用 `TenantKeyScope.scoped`，无身份会失败：
  - `backend/src/main/java/com/example/configmgr/ai/observe/SettlementObservationStore.java:114-137`
  - `backend/src/main/java/com/example/configmgr/ai/tenant/TenantKeyScope.java:69-76`

注意 `SettlementObservationStore` 类注释仍描述无身份 legacy fallback，但当前实现并非 fallback，不能据注释误判为实际无身份键。

## S-05　部分异步防串身份措施有效，但不构成业务隔离

- `JobHeartbeatRegistry` ticker 显式 `denied()`：
  - `backend/src/main/java/com/example/configmgr/job/service/JobHeartbeatRegistry.java:482-516`
- 任务 SSE 重试同样显式 `denied()`：
  - `backend/src/main/java/com/example/configmgr/task/service/TaskSseService.java:489-516`
- `AsyncConfig` 的 TTL 装饰器确实覆盖业务 `@Async` 作业池。

这些措施能防止“沿用上一请求身份”，但任务/作业 DB、键和查询不消费身份，所以不能据此判定安全。

---

# 4. 建议的迁移顺序

1. **先建立可信身份边界**：业务 `/tasks/jobs/definitions` 与 AI 使用同一认证 principal；移除自称 header 和全租户目录依赖。
2. **增加归属模型**：`tasks/jobs/config_definitions` 及子表回填 tenant/user；无法证明归属的数据隔离，不设默认租户。
3. **重写所有读写路径**：包括条件 UPDATE、悲观锁、删除、issues/diff、AI preflight、目标校验和 Runner，不能只改 GET。
4. **重划唯一约束**：定义 `(tenant_id,code)`；AI 幂等 `(tenant_id,user_id,run/key)`；子表通过复合外键或父级归属查询保证一致。
5. **持久化 JobExecutionContext**：tenant/user/run/session/trace/locale，异步提交和 watchdog 均显式传递。
6. **切换 versioned Redis/PubSub 键**：新数据只走新键，不做无身份 fallback，不桥接旧公共频道。
7. **最后清前端现场**：身份切换先停连接和 watcher，再更新身份并重新拉取本人数据。

---

# 5. 最小后续验证集合（仅建议，未执行）

- 两租户、同租户两用户的全 REST/AI 工具授权矩阵。
- 强制相同 taskId/jobId/defCode/runId/sessionId 的 DB、Redis、Pub/Sub、锁、心跳和取消隔离。
- A 创建、B 执行、C 取消/下载/订阅的跨实例链。
- 进程重启后的 watchdog/recovery 是否按每条 Job 记录恢复正确身份。
- 浏览器身份切换后是否仍有旧列表、旧 SSE、旧轮询或旧响应回写。
- 无租户目录接口的完整生产启动和 AI 对话流程。
- feedback 同 messageId 跨身份不冲突，但伪造 message/session/excerpt 必须失败。
