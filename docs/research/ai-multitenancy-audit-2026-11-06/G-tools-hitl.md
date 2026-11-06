# G/H 独立审查报告：工具与 HITL

## 身份与审查边界

- **身份门禁：通过**
  - `agentId=agent-946`：`<会话证据根>/agents/agent-946/wire.jsonl:2`
  - `profile.bind`：同文件第 **6** 行，`modelAlias=kimi-code/k3.1-pro`
  - 首条 `llm.request`：同文件第 **20** 行，`model=k3.1-pro`、`modelAlias=kimi-code/k3.1-pro`
- 仓库：`ai-example-main-v2`
- HEAD：`17080859f7b6905aafbbea90f6d2281913bcab9e`
- 仅审查 `ai/gate`、`ai/tool`、`ai/tools`、`ai/form`（不含附件存储）、`ai/slot`、`ai/resultview` 及闭环所需直接调用方/被调用方。
- 未修改代码、未运行测试或批量脚本、未执行 git 写操作、未启动/停止服务；原审查结束时 `git status --short` 为空。

## 结论摘要

| 编号 | 严重度 | 性质 | 结论 |
|---|---|---|---|
| G-01 | P0 | 确定问题 | 任务/作业工具及实时结果视图存在跨租户、跨用户读取 |
| G-02 | P0 | 确定问题 | `start_*` 预检、目标校验和实际执行均按裸 `taskId`，可对他租户任务发作业 |
| G-03 | P0 | 确定问题 | `create_task` 幂等键和 DB 唯一约束不含身份，可复用/改写他人任务 |
| G-04 | P0 | 确定问题 | 配置定义目录全局共享，`defCode` 全局唯一，表单/槽位/预检均会选错租户定义 |
| G-05 | P1 | 确定问题 | 资源锁按裸 `taskId`，跨租户互相阻塞；Redis 故障反而放行 |
| G-06 | P1 | 确定问题 | `sourceId` 动态选项回灌跳过服务端成员校验，可提交未披露值 |
| G-07 | P1 | 风险 | 工具超时后原线程仍可能执行，但锁已释放、条目已结算，存在跨实例重入窗口 |
| G-08 | P2 | 风险 | 浏览器表单草稿只按身份+`toolCallId`，未隔离 session/run，可能串用草稿 |
| G-09 | P2 | 确定问题 | 审批日志和待决条目没有实际操作者身份，跨租户同名 run 的审计无法归属 |
| G-10 | P2 | 确定问题 | 独立工具线程未传播语言上下文，英文请求可能回落中文 |

---

## G-01 — P0：任务/作业读取及结果视图没有租户+用户边界

### 证据

`list_tasks` 直接读取全部任务：

- `backend/src/main/java/com/example/configmgr/ai/tools/AiTools.java:289-305`
- `backend/src/main/java/com/example/configmgr/task/service/TaskService.java:70-72`
- `backend/src/main/java/com/example/configmgr/task/repo/TaskRepository.java:45-47`

关键查询：

```java
@Query("SELECT t FROM Task t ORDER BY t.createdAt DESC, t.id DESC")
List<Task> findAllOrdered();
```

单任务、作业读取同样只按裸 ID：

- `AiTools.getWorkspaceState → workspaceState → taskService.findById`：
  `backend/src/main/java/com/example/configmgr/ai/tools/AiTools.java:312-336`
- `TaskService.findById → taskRepository.findById(id)`：
  `backend/src/main/java/com/example/configmgr/task/service/TaskService.java:93-97`
- `AiTools.checkJobStatus → jobService.findById(jobId)`：
  `backend/src/main/java/com/example/configmgr/ai/tools/AiTools.java:339-349`
- `JobService.findById → jobRepository.findById`：
  `backend/src/main/java/com/example/configmgr/job/service/JobService.java:281-284`

`Task` 实体本身没有 `tenantId` 或 `ownerUserId`：

- `backend/src/main/java/com/example/configmgr/task/entity/Task.java:14-23`

实时结果视图又独立查询一次全表：

- `SpToolCallingManager.resultViewOf → ListTasksResultViewProvider.build`
- `backend/src/main/java/com/example/configmgr/ai/gate/SpToolCallingManager.java:3022-3038`
- `backend/src/main/java/com/example/configmgr/ai/resultview/ListTasksResultViewProvider.java:77-95`

### 完整调用链

```text
/chat
  → ResilientChatService
  → SpToolCallingManager
  → AiTools.listTasks
  → TaskService.findAll
  → TaskRepository.findAllOrdered   // 全表，无身份谓词
  → tool_result
  → ListTasksResultViewProvider.build
  → TaskService.findAll             // 第二次全表读取
```

### 触发场景与影响

- 租户 A 的用户调用“列出任务”，可看到租户 B 的任务 ID、标题、类型、步骤和状态。
- 同租户用户 U1/U2 之间也没有任何属主过滤，无法满足本审查要求的用户隔离。
- 知道或猜到 `taskId/jobId` 后，`get_workspace_state`、`check_job_status` 可进一步读取任务配置项或作业进度、错误数。
- 实时 `resultView` 第二次查库，可能在文本生成后读到另一批任务或更新后的任务，造成模型文本与 UI 表格不一致；即使只修 `AiTools`，视图仍有独立泄漏路径。

### 修改建议

1. 给 `tasks/jobs` 增加 `tenant_id`；若任务属于个人，再增加 `owner_user_id`。所有工具读取必须使用显式身份查询：

   ```text
   WHERE tenant_id = :tenantId
     AND (:personal = false OR owner_user_id = :userId)
     AND id = :taskId
   ```

2. 跨租户业务 ID 只在租户内唯一时，应建立 `(tenant_id, id)` 唯一约束，并让所有子表外键携带 `tenant_id`，不能继续在 Java 中用单个 `Long id` 作为完整资源身份。
3. `ListTasksResultViewProvider` 不应再次无身份查库。优先让工具执行结果同时保留一份受身份约束的结构化行快照，文本和视图从同一快照投影；至少也必须调用同一个身份化查询。
4. 迁移时只能根据已有真实记录中的租户/用户归属回填；无法归属的记录应隔离待处理，不能放入默认租户。不得通过枚举全部租户来回填。

### 后续单点验证

- 两个租户及同租户两个用户分别创建同名、同 ID 形态任务，交叉调用 `list_tasks/get_workspace_state/check_job_status`。
- 断言文本和 `resultView` 均只含当前身份数据，且二者行集完全一致。
- 任务在文本生成与视图构造之间被更新时，验证不会出现两版数据。

---

## G-02 — P0：`start_*` 可对其他租户任务发起作业

### 证据

执行前目标校验使用裸 `findById`：

- `backend/src/main/java/com/example/configmgr/ai/gate/ToolTargetResolver.java:114-161`
- 核心调用：`taskRepository.findById(taskId)`，见该文件 `:135-150`

仓储读取失败还会**放行**：

```java
catch (RuntimeException ex) {
    log.warn(...);
    return Verdict.ok();
}
```

位置：`backend/src/main/java/com/example/configmgr/ai/gate/ToolTargetResolver.java:139-142`。

预检同样通过裸任务查询获取标题、类型、条目、条件和影响面：

- `backend/src/main/java/com/example/configmgr/ai/gate/ToolPreflightService.java:241-360`
- `JobService.findTask → taskService.findByIdRaw`：
  `backend/src/main/java/com/example/configmgr/job/service/JobService.java:110-117`

最终执行也没有身份校验：

- `AiTools.startExport/startPrecheck/startImport/startPublish`：
  `backend/src/main/java/com/example/configmgr/ai/tools/AiTools.java:532-591`
- `JobService.createAndStart`：
  `backend/src/main/java/com/example/configmgr/job/service/JobService.java:151-166`
- 行锁仍是 `findByIdForUpdate(id)`：
  `backend/src/main/java/com/example/configmgr/task/repo/TaskRepository.java:28-30`

### 完整调用链

```text
模型/被注入的 taskId
  → SpToolCallingManager.executeOnce
  → ToolPreflightService.inspect
  → JobService.findTask(taskId)             // 无身份
  → ConfirmGate.submitDecision(APPROVED)
  → ToolTargetResolver.check(taskId)        // 无身份；读失败还放行
  → AiTools.startX(taskId)
  → JobService.createAndStart(taskId)
  → lockForUpdate(taskId) + 创建 Job         // 无身份
```

### 触发场景与影响

- 用户 B 在上下文或提示中放入租户 A 的 `taskId`，预检会读取 A 的任务标题、条目、条件摘要，确认后可在 A 的任务下创建导出、预检、导入或发布作业。
- 跨租户 `taskId` 不保证唯一时，同一个数值可能同时存在于 A/B；当前查询无法表达要操作哪一个。
- 若目标校验期间 DB 故障，校验直接通过，后续实际服务仍按裸 ID 执行。
- `start_publish` 可写正式业务数据，影响不限于信息泄露。

### 修改建议

1. 将 `TenantUser` 从 `RunSnapshot/ToolExecutionEnvelope` 显式传入：

   ```text
   ToolPreflightService.inspect(identity, ...)
   ToolTargetResolver.check(identity, ...)
   JobService.createAndStart(identity, taskId, ...)
   ```

   不要只依赖调用线程上的 ThreadLocal。
2. 预检、目标校验、行锁、在途作业检查、文件/条目/暂存行读取和最终 `INSERT job` 必须全部带 `tenant_id`。
3. 任务行锁改为按 `(tenant_id, task_id)` 锁定；跨实例互斥仍以该复合资源身份为准。
4. 所有“无法证明资源归属”的异常都应失败关闭，特别是 `ToolTargetResolver` 和 `WizardActionGuard`；不得将 DB 故障视为校验通过。
5. `reportHash` 应包含租户和复合资源版本，避免身份迁移后旧报告被误当成同一目标。

### 后续单点验证

- A/B 两租户各有一个相同数值 `taskId`，B 的四种 `start_*` 均不得命中 A。
- 在目标校验、预检、行锁三个点分别注入 DB 读失败，均不得创建 Job。
- 验证预检卡上的标题、条目、条件、影响面与最终执行目标属于同一租户同一任务。

---

## G-03 — P0：`create_task` 幂等可跨身份复用或改写任务

### 证据

调用方只传裸 `runId`：

- `backend/src/main/java/com/example/configmgr/ai/tools/AiTools.java:434-443`

幂等哈希只含标题、runId、类型：

- `backend/src/main/java/com/example/configmgr/task/service/TaskService.java:426-437`

```text
create_task|name=<title>|parent=<runId>|params=<type>
```

查重和唯一约束均不含租户/用户：

- `TaskService.createChecked`：
  `backend/src/main/java/com/example/configmgr/task/service/TaskService.java:196-240`
- continuation 行锁及复用：同文件 `:245-277`
- 冲突后回查：同文件 `:360-376`
- Repository：
  `backend/src/main/java/com/example/configmgr/task/repo/TaskRepository.java:32-43`
- DB 约束：
  `backend/src/main/resources/db/migration/V1__schema.sql:90-103`

```sql
CONSTRAINT uq_tasks_ai_create_run UNIQUE (ai_create_run),
CONSTRAINT uq_tasks_ai_create_key UNIQUE (ai_create_key)
```

### 完整调用链

```text
create_task
  → AiTools.createTask(runId)
  → TaskService.createIdempotent
  → createIdempotencyKey(title, runId, type)
  → findFirstByAiCreateRun... / findFirstByAiCreateKey...
  → reuseOrConflict / continueCurrentTask
  → tasks 全局唯一约束
```

### 触发场景与影响

- A、B 两个身份各自有相同 `runId`，并使用相同任务类型和规范化标题：
  - B 会命中 A 的 `ai_create_run/ai_create_key`；
  - 返回“复用任务 #A”及任务标题、步骤、条目和条件；
  - B 不得到自己独立的新任务。
- 标题不同则 B 收到包含 A 任务号的冲突，形成跨身份状态探测和拒绝服务。
- continuation 路径按裸 `continuationTaskId` 锁行并写入 `settings_json.aiCreateRuns`，可能把 B 的 runId 写到 A 的任务上。
- 同租户不同用户也发生同样冲突。

### 修改建议

1. 幂等作用域改为 **tenant + user + run + 稳定参数**：

   ```text
   material = create_task|tenant=<t>|user=<u>|parent=<runId>|...
   ```

2. 查重改为：

   ```text
   findFirstByTenantIdAndUserIdAndAiCreateRun...
   findFirstByTenantIdAndUserIdAndAiCreateKey...
   ```

3. 唯一约束改为：

   ```sql
   UNIQUE (tenant_id, user_id, ai_create_run)
   UNIQUE (tenant_id, user_id, ai_create_key)
   ```

   若业务明确允许同租户用户共享任务，也仍需将 AI 创建幂等记忆限定到操作用户，避免跨用户误复用。
4. continuation 必须按同一复合身份锁定并核验任务归属，不得只按 `continuationTaskId`。
5. 老数据迁移时，从对应 run 快照的真实身份回填；无法映射的历史幂等字段不要全局复用，可隔离为新创建路径而非返回未知旧任务。

### 后续单点验证

- 两租户、两用户分别用相同 `runId/title/type` 并发创建，必须各自得到独立任务。
- 同键重试只在原身份内复用；跨身份 continuation 必须 404/403，且原任务 `settings_json` 不变。
- 两个实例同时创建时，验证复合唯一约束仍能收敛到本身份的一条任务。

---

## G-04 — P0：配置定义目录及 `defCode` 全部跨租户共享

### 证据

工具目录直接读取全部定义：

- `AiTools.listConfigDefs → definitionService.findAll/findByLevel`：
  `backend/src/main/java/com/example/configmgr/ai/tools/AiTools.java:171-200`
- `AiTools.getConfigDef → findByCode`：同文件 `:211-243`
- `DefinitionService`：
  `backend/src/main/java/com/example/configmgr/definition/service/DefinitionService.java:38-55`
- Repository：
  `backend/src/main/java/com/example/configmgr/definition/repo/ConfigDefinitionRepository.java:11-19`

`code` 被设为全局唯一：

- `backend/src/main/java/com/example/configmgr/definition/entity/ConfigDefinition.java:13-21`
- `backend/src/main/resources/db/migration/V1__schema.sql:23-31`

同一全局目录还进入：

- 创建预检：`backend/src/main/java/com/example/configmgr/ai/gate/ToolPreflightService.java:412-448`
- 槽位目录：`backend/src/main/java/com/example/configmgr/ai/slot/DefinitionSlotCatalog.java:34-55`
- 宿主配置选择表：`backend/src/main/java/com/example/configmgr/ai/form/DefinitionSelectionForm.java:625-671`
- 动态选项：`backend/src/main/java/com/example/configmgr/ai/form/FormOptionSource.java:60-82,118-152`
- 条件动作校验：`backend/src/main/java/com/example/configmgr/ai/tool/WizardActionGuard.java:453-469`

### 调用链与触发场景

```text
list/get_config_defs、create_task、select_definitions、generative_form、set_condition
  → DefinitionService.findAll/findByLevel/findByCode
  → ConfigDefinitionRepository 全局查询
  → 当前租户看到或采用其他租户的定义和字段
```

- A 的用户可枚举 B 的定义编码、名称、说明、字段和引用关系。
- A/B 都允许存在 `defCode=CONFIG_CODE` 时，当前全局唯一约束根本不能表达该业务事实。
- 若同名编码被错误共享，创建预检和条件校验会把别的租户定义当成“已验证存在”，后续选择、条件草案和导出目标随之错绑。

### 修改建议

1. `config_definitions` 增加 `tenant_id`，唯一约束改为 `(tenant_id, code)`。
2. `config_fields/config_dependencies` 及引用字段也必须携带 `tenant_id`；关联键不能继续只有 `def_code`。
3. 所有目录、选项、槽位、预检和动作守卫使用当前请求/运行记录的租户，不允许全局 `findAll/findByCode` 作为生产路径。
4. 字典缓存若后续引入，只能以 `(tenantId, code)` 为键，并按真实请求按需加载，不预热全租户目录。

### 后续单点验证

- A/B 各自创建相同 `defCode`、不同字段结构，交叉执行目录、表单、槽位和 `set_condition`，必须各自解析到本租户版本。
- 交叉提交 B 的编码给 A，必须按不存在处理，不能通过预检。

---

## G-05 — P1：资源锁跨租户冲突，故障时又完全失效

### 证据

锁键只含 `taskId`：

- `backend/src/main/java/com/example/configmgr/ai/run/LeaseKeys.java:35-59`

```text
ai:lock:task:<taskId>
ai:lock:job:<taskId>:<JOBTYPE>
```

调用链：

- `SpToolCallingManager.acquireResourceLocks`：
  `backend/src/main/java/com/example/configmgr/ai/gate/SpToolCallingManager.java:3262-3272`
- `ToolResourceLocks.acquire`：
  `backend/src/main/java/com/example/configmgr/ai/gate/ToolResourceLocks.java:127-170`
- `ToolUnknownReconciler.isHeld`：
  `backend/src/main/java/com/example/configmgr/ai/gate/ToolUnknownReconciler.java:113-125`

Redis 不可用或抢锁异常会返回 `UNAVAILABLE`，调用方继续执行：

- `backend/src/main/java/com/example/configmgr/ai/gate/ToolResourceLocks.java:131-168,212-219`

### 触发场景与影响

- A、B 都有 `taskId=7`。A 的导出持锁时，B 对本地任务 7 的操作被误判 `HELD_ELSEWHERE`，形成跨租户拒绝服务。
- B 的锁也可能让 A 的 UNKNOWN 对账错误认为“本任务仍在执行”。
- Redis 故障时多个实例可同时执行同一任务的危险动作；锁退化为仅日志提示，不再提供互斥。
- 锁 TTL 不续租，长动作超过 TTL 后，其他实例也可进入临界区。

### 修改建议

1. 锁是业务资源互斥，**键应含租户但不应含用户**，否则同租户两个用户反而不能互斥：

   ```text
   ai:lock:task:{<tenantId>:task:<taskId>}
   ai:lock:job:{<tenantId>:task:<taskId>:<JOBTYPE>}
   ```

2. `owner` 值可记录 `tenant/user/instance/run/toolCall`，便于审计，但不要以 owner 代替资源键隔离。
3. `acquire/isHeld` 显式接收运行记录中的 `tenantId`，不读取可能缺失或残留的线程身份。
4. 危险工具在锁状态不可知时必须失败关闭；`isHeld` 改为 `HELD/NOT_HELD/UNKNOWN` 三态，UNKNOWN 不得解释成没有执行痕迹。
5. 在真实执行结束前续租；异常结束时由执行线程的 `finally` 释放。
6. 旧裸锁 TTL 很短，可在滚动发布前等待其自然过期，再启用新键；不要双写旧裸键，也不要扫描租户迁移锁。

### 后续单点验证

- 两租户相同 `taskId` 可同时执行且互不阻塞；同租户同任务跨用户、跨实例仍互斥。
- Redis 故障、锁超时、锁读失败时，危险工具不得进入实际业务服务。
- UNKNOWN 对账在锁读失败时必须保持“无法判定”，不得宣称无执行痕迹。

---

## G-06 — P1：动态选项回灌没有服务端成员校验

### 证据

动态数据源由后端闭集提供：

- `backend/src/main/java/com/example/configmgr/ai/form/GenerativeFormAttachmentController.java:230-243`
- `FormOptionSourceRegistry`：
  `backend/src/main/java/com/example/configmgr/ai/form/FormOptionSourceRegistry.java:35-48`

但回灌时，`sourceId` 类型的可选集返回 `null`，明确跳过成员校验：

- `backend/src/main/java/com/example/configmgr/ai/form/GenerativeFormRules.java:1265-1273,1382-1390,1597-1627`

`GenerativeFormGuard.inspectResult` 只调用上述静态规则，没有注入 `FormOptionSourceRegistry` 做动态复核：

- `backend/src/main/java/com/example/configmgr/ai/form/GenerativeFormGuard.java:68-105,333-354`

### 调用链与触发场景

```text
模型生成 sourceId 表单
  → GET /form/option-sources/{sourceId}    // 只影响浏览器展示
  → 用户/攻击者篡改 POST /frontend-tool-result
  → GenerativeFormGuard.inspectResult
  → runtimeOptionValues = null
  → 跳过 VALUE_OUT_OF_OPTIONS
  → 未披露的 defCode/fieldCode 被接受
```

即使浏览器只展示本租户选项，调用者也可直接提交任意值。结合 G-04，可能把其他租户或根本不存在的定义编码、字段编码带入后续槽位和工具流程。

### 修改建议

1. 在 `GenerativeFormGuard` 注入 `FormOptionSourceRegistry`，按当前回灌中的父值重新解析动态选项，并逐项校验 `enum/multi_select` 成员。
2. 动态解析必须带 `TenantUser`，不能使用全局定义服务；无法读取数据源或无法确认父值时失败关闭。
3. 对提交时选项已变化的情况，比较下发时选项快照或定义版本，避免“下发后换定义”造成错绑。
4. 保留静态规则和浏览器校验作为体验层，但不得以它们替代服务端校验。

### 后续单点验证

- 篡改 `sourceId` 表单的 `defCode`、字段值、父值，必须返回 `VALUE_OUT_OF_OPTIONS` 且 pending 不变。
- A 提交 B 的同形编码时，即使 B 的选项存在，A 也必须拒绝。
- 数据源故障、父值缺失、定义在挂起期间被替换时均不得通过。

---

## G-07 — P1：超时结算与实际执行可能并行，跨实例可重入

### 证据

执行放入独立线程池：

- `backend/src/main/java/com/example/configmgr/ai/gate/SpToolCallingManager.java:3058-3072`

超时仅 `future.cancel(true)`：

- 同文件 `:3079-3085`

代码注释也承认不响应中断的工具仍会继续执行：

- 同文件 `:3044-3056`

随后调用线程将条目标为 TIMEOUT、未执行、不写台账：

- 同文件 `:3109-3139`

而外层 `finally` 立即释放资源锁：

- 同文件 `:2751-2762`

### 触发场景与影响

```text
实例 A 执行危险工具
  → 调用线程超时，cancel(true)
  → 工具线程未响应中断，仍在运行
  → A 写 TIMEOUT、释放锁
  → 实例 B 获得锁/新调用开始
  → A 的迟到副作用与 B 并发发生
```

现有 `claim` 只能防止同一 `toolCallId` 被两个驱动者同时认领，不能撤销已经开始的实际副作用，也不能阻止新 `toolCallId` 的后一次业务动作。

### 修改建议

1. 对不可逆工具，超时结果应为 **UNKNOWN/需对账**，不能按“未执行”鼓励重试。
2. 锁的实际释放应属于工作线程的完成回调，而不是等待线程的超时分支；执行期间续租。
3. 保留“已认领且结果未知”的持久事实，直到工具线程真实结束或人工对账完成。
4. 新调用必须同时受 run 级 claim、业务资源锁和业务服务自身幂等约束，不能只靠第一层 claim。
5. 对不能取消且不能幂等的工具，不应提供会释放执行权的强制超时；可以只停止等待，不宣称执行结束。

### 后续单点验证

- 用忽略中断的工具阻塞超过超时时间，确认锁不提前释放、不会产生可重试的新业务动作。
- 工具迟到完成后，台账必须如实反映实际结局；实例 B 在此期间不得执行同资源动作。
- 进程崩溃后保持 UNKNOWN，不得自动重放不可逆工具。

---

## G-08 — P2：表单草稿缺少 session/run 维度

### 证据

草稿键为：

```text
cm.ai.form.draft.<tenantId>:<userId>.<toolCallId>
```

- `frontend/src/utils/ai-identity.ts:156-169`
- `frontend/src/utils/form-draft.ts:26-34`

加载时只使用 `toolCallId`：

- `frontend/src/stores/ai.ts:8629-8648`

活动表单/队列去重也只比较 `toolCallId`，没有同时比较 `runId`：

- 同文件 `:8629-8636`

部分宿主表单的确定性 ID 甚至只有任务号：

- `host-def-form-<taskId>`：
  `backend/src/main/java/com/example/configmgr/ai/form/DefinitionSelectionForm.java:111-148`

### 触发场景与影响

- 同一身份在同一会话的新 run 再次遇到相同 `toolCallId`，若旧草稿因异常终态、TTL、清理失败或时序问题仍存在，会把旧 run 的输入恢复到新表单。
- 活动/排队表单若跨 run 残留，新的同号表单会被误判为重复，或沿用旧 run 的回灌目标。
- 这不是跨租户直接泄漏：身份指纹和切换清理已存在；主要是同用户跨 run/会话的数据完整性与误提交风险。

### 修改建议

1. 草稿键改为：

   ```text
   cm.ai.form.draft.<tenant>:<user>.<sessionId>.<runId>.<toolCallId>
   ```

2. `activeForm/queuedForms` 以 `(runId, toolCallId)` 为复合身份；提交、取消、清理、prune 同步改造。
3. 兼容旧键时，只能在服务端证明当前 run/toolCall 属于同一会话后读取一次并立即迁移，不能无条件加载旧键。
4. 现有“调度时固定完整存储键”的做法应保留，避免身份切换后的延迟写落到新身份。

### 后续单点验证

- 同用户同 session 连续两个 run 使用相同 `toolCallId`，草稿互不可见。
- 同租户不同用户、不同租户同名 ID 仍互不可见。
- 旧 run 的延迟回包不得清理或覆盖新 run 的草稿和表单。

---

## G-09 — P2：审批审计缺少真实操作者身份

### 证据

`approval_decided` 的人工操作者只写常量 `"user"`：

- `backend/src/main/java/com/example/configmgr/ai/gate/ConfirmGate.java:1768-1772,1864-1886`

`PendingToolCall` 只有执行实例 `executedBy`，没有 `decidedByTenantId/decidedByUserId`：

- `backend/src/main/java/com/example/configmgr/ai/run/PendingToolCall.java:109-171`

`AiTrace` 固定字段只有 run/session/instance，没有 tenant/user：

- `backend/src/main/java/com/example/configmgr/ai/observe/AiTrace.java:35-50,244-257`

### 触发场景与影响

- A/B 可以有相同 `runId/sessionId/toolCallId`。合并多实例日志后，仅凭现有字段无法确定哪一身份批准了哪一条。
- 决策发生在实例 B、执行发生在实例 C 时，无法从持久条目证明实际操作者与原始 run 属主是否一致。
- 这不直接造成越权，但削弱审批追溯、 incident 调查及不可否认性。

### 修改建议

1. 在决策原子写中加入 `decidedByTenantId/decidedByUserId/decidedAt/decisionInstanceId`，来源必须是当前已验证请求身份，不能由请求体自报。
2. `AiTrace` 增加 tenant/user 字段，并纳入 `bind/wrap/wrapCallable` 的快照传播。
3. 日志中保留 run/session/toolCall，但以复合身份作为关联键；不要记录拒绝原因正文或表单值。
4. 老记录缺操作者时显示 `unknown`，不要用 run 创建者反推审批者。

### 后续单点验证

- A 挂起、B 实例接收决策、C 实例执行后， pending、决策 guard、台账和日志中的身份必须一致且可关联。
- 同形 run/session/toolCall 的两个租户日志不得串联。

---

## G-10 — P2：工具超时线程丢失语言上下文

### 证据

外层工具执行点会从 `toolContext.replyLocale` 设置语言：

- `backend/src/main/java/com/example/configmgr/ai/gate/SpToolCallingManager.java:788-813`

但提交独立工具线程时只传播了 `AiTrace` 和 `RunIdentityScope`，没有传播 `LocaleContext`：

- 同文件 `:3063-3072`

`ReplyLanguage.current()` 依赖 `LocaleContextHolder`：

- `backend/src/main/java/com/example/configmgr/ai/run/ReplyLanguage.java:41-49`

例如 `get_config_def` 的错误文案直接读取它：

- `backend/src/main/java/com/example/configmgr/ai/tools/AiTools.java:219-226`

### 触发场景与影响

英文请求的工具在独立超时线程中执行时，可能按中文生成错误和预检相关文本；线程池若残留其他语言，还可能出现非确定性语言串用。租户/用户身份本身在该路径有显式恢复，不包括在此问题内。

### 修改建议

1. 在提交前捕获 `replyLocaleOf(prompt)`。
2. 工作线程内保存旧 `LocaleContext`、设置捕获值，并在 `finally` 中恢复旧值，而不是简单 reset 后遗留池线程状态。
3. 与现有 `RunIdentityScope`、`AiTrace.wrapCallable` 共用同一个执行包装器，避免今后只补其中一类上下文。

### 后续单点验证

- 英文请求分别经过同步执行、线程池正常完成、超时、异常四条路径，语言均保持英文。
- 同一池线程随后服务中文请求时不得残留英文。

---

## 已证安全与边界保留

### 1. 当前身份键路径下，未发现可成立的审批 IDOR

- run 级 Redis 键包含 `tenantId/userId/runId`：
  `backend/src/main/java/com/example/configmgr/ai/tenant/RunKeyScope.java:37-76`
- pending/claim/ledger/idem/decided 均通过该作用域拼键：
  `backend/src/main/java/com/example/configmgr/ai/run/RunStore.java:1066-1105`
- `/confirm` 和 `/frontend-tool-result` 读取的是当前身份命名空间的 pending：
  `backend/src/main/java/com/example/configmgr/ai/web/AiController.java:878-902,942-950`
- 决策 guard 与 pending 在同一 Lua 中原子写入，并检查 PENDING 和执行证据：
  `backend/src/main/java/com/example/configmgr/ai/run/RunStore.java:5208-5240`
- 跨实例执行认领也使用身份化同槽键及 fence：
  `backend/src/main/java/com/example/configmgr/ai/run/RunStore.java:3895-3920`

因此，A 用户使用 B 的 `runId/toolCallId` 时，当前实现会查询 A 自己的键，不能直接改写 B 的 pending。**这与裸 `taskId` 业务 IDOR 不同。**

补强建议：两个入口仍未像其他 run 端点一样显式调用 `ownedRun`，可在任何 touch/写操作前统一复核快照归属；旧无身份数据必须失败关闭，不能靠键名猜归属。

### 2. 本地缓存、唤醒 Pub/Sub 有身份隔离证据

- 本地 run 键：
  `backend/src/main/java/com/example/configmgr/ai/tenant/LocalRunKey.java:22-42`
- `RunRegistry` 按身份+run 管理写出器：
  `backend/src/main/java/com/example/configmgr/ai/run/RunRegistry.java:45-55,157-175`
- `ConfirmGate` latch 也采用身份+run+toolCall；裸 run 的 submit 条带只造成串行，不共享业务状态：
  `backend/src/main/java/com/example/configmgr/ai/gate/ConfirmGate.java:238-299`
- Pub/Sub 频道为 `ai:wake:<tenantId>:<userId>:<runId>`，监听回调显式恢复身份：
  `backend/src/main/java/com/example/configmgr/ai/run/RedisFrameWakeBus.java:137-150,226-282`

### 3. 主要异步身份传播有显式记录来源

- 工具执行从快照/执行信封恢复身份：
  `backend/src/main/java/com/example/configmgr/ai/gate/SpToolCallingManager.java:826-851`
- 工具线程池重新建立身份作用域：同文件 `:3063-3072`
- HITL 续跑同时传播身份和语言：
  `backend/src/main/java/com/example/configmgr/ai/gate/ConfirmGate.java:2517-2537`
- 定时重试按提交时捕获的记录身份执行：同文件 `:2765-2809`

这些证据不能替代 G-10 的语言缺口，也不能证明业务服务内的裸 ID 查询安全。

### 4. `CreateTaskDraftStore` 的 Redis 草案键有正确身份段

```text
ai:ctdraft:{<tenantId>:<userId>:session:<escapedSessionId>}
field = taskId
```

- `backend/src/main/java/com/example/configmgr/ai/run/CreateTaskDraftStore.java:31-44,112-129`
- 读写通过 run 快照解析 session，合并使用 CAS：同文件 `:143-205,208-240`

但该安全键不能抵消 G-03 的业务任务幂等冲突。

### 5. 前端身份切换清理较完整

- 草稿和镜像带身份指纹：
  `frontend/src/utils/ai-identity.ts:90-169`
- 待写草稿在调度时固定键，延迟 flush 不会改写新身份：
  `frontend/src/utils/form-draft.ts:158-211`
- 换会话会清提交态、确认勾选、活动表单和队列：
  `frontend/src/stores/ai.ts:11177-11302`

---

## 本领域租户枚举/列表依赖

**未发现工具/HITL 依赖“获取全部租户列表”或循环租户 ID 来完成执行的代码。** 本领域的跨实例确认、认领、唤醒均按已知身份+run 直接定位，不需要枚举租户。

但存在以下**跨租户业务数据枚举**，已在 G-01/G-04 判为 P0，不能与“没有租户列表接口”混为一谈：

- `AiTools.listTasks → TaskRepository.findAllOrdered`：全局任务枚举。
- `ListTasksResultViewProvider.build → TaskService.findAll`：第二次全局任务枚举。
- `AiTools.listConfigDefs → DefinitionService.findAll/findByLevel`：全局配置定义枚举。
- `DefinitionSlotCatalog.list` 和 `FormOptionSource.DefinitionOptionSource`：同样读取全局定义目录。

`ToolRegistry`、`FormOptionSourceRegistry`、前端 `RESULTVIEW_RENDERERS` 是静态代码注册表，只包含工具/数据源/组件定义，不枚举租户，不据此判为租户隔离问题。

## 无法判定的外部条件

仓内未发现数据库行级安全、JPA 全局租户过滤器或其他能给上述裸查询补充身份谓词的机制。若部署环境另有数据库 RLS 或外部业务服务隔离，其实际规则不在本仓库证据内，**无法判定**；本报告按仓库自身可证明行为定级，没有假设存在外部补救。
