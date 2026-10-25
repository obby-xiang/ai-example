> 来源=<仓外证据>/ai-full-audit/E-business-ai.md（2026-10-25 归档）· 审计分片/草稿，属历史证据，不代替终稿 `docs/research/排查-AI全链路只读验收审计-终稿-2026-10-25.md`
# E｜AI ⇄ 业务工作区交界（后端链）审计报告

- 源码快照：`<仓外证据>/ai-full-audit/tree`（base `ae883fb` + 审计开始时未提交补丁，`working.patch` SHA256=`6c88b47d…b955`）
- 审计对象：AI 侧工具/闸门调用业务侧（task / job / job_items / file / staging / SSE）的**后端交界**，以及 AI 面板驱动业务动作时的前端边界
- 非本域：业务内部算法（Excel 读写、条件求值内部规则、定义/主数据 CRUD 内部语义）只在被 AI 调用到的**契约点**处审查
- 方式：只读源码与测试；未启动服务、未跑拓扑、未触 8081/5202、未读写密钥、未派子 agent、未改产品仓任何文件
- 前提（任务要求）：Redis Cluster、共享 DB/共享文件存储、≥3 JVM、同一对话请求可无粘滞落不同实例
- 口径：正确项一律带条件；发现标注 `已证实缺陷（代码级）` / `具体风险（需特定交错或配置）` / `仅缺测试` / `未验证`

---

## 1. 覆盖清单

### 1.1 本域完整链路（入口 → 落点）

| # | 链路 | AI 入口 | 后端落点（生产代码） | 共享状态/外部调用 | 相邻测试 |
|---|---|---|---|---|---|
| L1 | 读定义/任务/作业 | `list_config_defs` `get_config_def` `list_tasks` `get_workspace_state` `check_job_status` `get_row_count` | `AiTools`→`DefinitionService`/`TaskService`/`JobService`/`ConfigDataService`；`ListTasksResultViewProvider` | DB（definitions/tasks/jobs/config_data） | `AiToolsInvocationTest`、`ListTasksResultViewProviderTest`、`AiToolsListTasksTruncationTest` |
| L2 | 建任务 | `create_task`（WRITE，不进确认门） | `AiTools.createTask`→`TaskService.create` | tasks 行 | `AiToolsInvocationTest#createTaskFromAiPersistsTaskAndReportsNextAction` |
| L3 | 起作业（导出/预检/导入/发布） | `start_export` `start_precheck` `start_import` `start_publish`（DANGER→确认门） | `AiTools`→`JobService.createAndStart`；前置 `ToolPreflightService`、`ToolTargetResolver`、`ToolResourceLocks`、认领/台账（`RunStore`） | tasks/jobs/job_items/task_files/config_staging_rows；Redis `ai:claim:` `ai:idem:` `ai:lock:task|job` `job:runner-active:` `job:beat:` `job:cancel:` | `AiToolsInvocationTest`（真 H2+真作业）、`JobMutexGuardTest`、`TaskFinalizedGuardTest`、`PrecheckGateGuardTest`、`ToolPreflightServiceTest`、`ToolTargetResolver(Gate)Test`、`ToolResourceLockGateTest`、`ToolIdempotencyTest`、`ConfirmGate*`(15) |
| L4 | 批准/拒绝/取消（HITL 决策） | `POST /api/ai/confirm`、`POST /api/ai/cancel/{runId}` | `AiController.confirm`→`ConfirmGate.submitDecision`（HSETNX 决策 guard + 落库 + 回执帧 + 唤醒）→`ResumeService.resume`→`resolvePending`→`executeOnce` | Redis `ai:decided:` `ai:run:` `ai:lease:` `ai:fence:`；DB | `ConfirmGateDecisionAtomicTest` `ConfirmGateLeaseTest` `ConfirmGateDeadlockResumeTest` `ResumeServiceLeaseTest` `RunStoreClaimEpochTest` `CancelLedgerConformanceTest` |
| L5 | 前端工具回灌（业务动作在浏览器） | `select_definitions` `set_condition` `confirm_step` `navigate_to` `open_export_file_editor` `download_export_files` `generative_form` + 9 表格工具 + 7 UI 原语；回灌 `POST /api/ai/frontend-tool-result` | `AiTools`（哨兵桩）+ `FrontendToolGuard` 家族（`WizardActionGuard`/`SheetWriteGuard`/`UiPrimitiveGuard`/`GenerativeFormRules`）+`ConfirmGate.submitFrontendResult` | 浏览器 + `PUT /api/tasks/{id}/items/{def}/condition`、`PUT /api/tasks/{id}/select-defs`、`PUT /api/tasks/{id}/step`、`GET/PUT /api/tasks/{id}/files/*` | `WizardActionGuardTest` `SheetWriteGuardTest` `UiPrimitiveGuardTest` `GenerativeForm*` `FrontendGuardPreSuspendTest` `SseChatEmitterFramesTest` |
| L6 | 作业执行与终态 | 由 L3 触发，异步 runner | `AsyncJobExecutor`→`Export/Precheck/Import/PublishJobRunner`；`JobProgressService`（独立事务进度/条目）、`JobTerminalWriter`（提交后终态+任务联动+JOB_DONE） | DB（jobs/job_items/validation_issues/config_staging_rows/config_data/task_items）+盘+Redis 心跳/取消 | `ExportFlowJobTest` `ImportFlowJobTest` `PublishSemanticsTest` `PrecheckFingerprintGuardTest` `ExportFailureCompensationTest` `ExportJobRunnerStorageLifecycleTest` `JobTerminalStateCasTest` `JobTerminalWriterLateCompletionTest` `JobProgressServiceTerminalGuardTest` `JobSideWriteGuardTest` |
| L7 | 跨实例作业接管/存活/恢复 | 无 AI 直接入口（后台） | `JobHeartbeatRegistry`（共享心跳+runner-active+判死单飞）、`JobWatchdog`、`StaleJobRecoveryRunner`、`RunnerActiveKeyCleanupRunner` | Redis 三键族；DB | `JobWatchdog(Test|SharedBeat|LegacyAnchor|ItemConvergence)` `StaleJobRecoveryMultiInstanceTest` `RunnerActiveMultiInstanceTest` `RunnerActiveTtlConfigTest` `JobHeartbeatCleanupOrderTest` |
| L8 | 任务/作业删除与清理 | AI 无删除工具（FR-5.3 的"删除任务"列人工确认项）；REST `DELETE /api/tasks/{id}` | `TaskService.delete`（task 行锁→runner 存活闸→级联→提交后删盘） | DB+盘+Redis | `TaskDeleteInFlightGuardTest` `TaskDeleteRunnerLivenessTest` `TaskDeleteJobCreateConcurrencyTest` `TaskSelectDefsDeleteConcurrencyTest` `FileControllerTaskGoneTest` |
| L9 | 共享文件上传/下载/在线编辑保存 | `download_export_files`（前端）+ 上传入口（`ui_request_upload`/IMPORT 向导） | `FileController`（upload/zip/download/download-all/download-selected/save/templates）、`FileStorageService`、`FileStorageMultiInstanceGuard` | 共享盘 + DB（task_files，@Version） | `FileDownloadByteIdentityTest` `FileControllerStorageLifecycleTest` `FileStorageKeyTest` `FileStorageServiceTransactionCallbackTest` `FileStorageMultiInstanceGuard(Startup)Test` `FileKeyMigration*` `RedisFileKeyMigrationLockTest` |
| L10 | 任务 SSE/事件 | 前端 `GET /api/tasks/{id}/events`、`utils/job-watch.ts` | `TaskSseService`（本机扇出+写预算+首末订阅）+`RedisTaskEventBus`（跨实例 pub/sub） | Redis `task:events:<taskId>` + 快照轮询兜底 | `TaskSseServiceTest` `TaskSseServiceCrossInstanceTest` `RedisTaskEventBusTest`（前端 `job-watch-channel.spec.ts`） |

### 1.2 已读生产代码（本域）

AI 侧：`ai/tools/AiTools.java`(1052)、`ai/gate/{SpToolCallingManager(1940),ConfirmGate(1937),ToolPreflightService(399),ToolTargetResolver(224),ToolResourceLocks(223),WizardActionGuard,SheetWriteGuard,AiRateLimiter(199),InputSanitizer,ContextFieldGate}`、`ai/run/{ResumeService,SseChatEmitter,RunStore,RunRegistry,LeaseService,PendingToolCall}`、`ai/tool/{ToolRegistry,ToolOutcome,ToolResultEnvelope,FrontendToolGuard,SheetAudit}`、`ai/resultview/*`、`ai/web/AiController.java`(2009)。

业务侧：`job/service/{JobService(555),JobProgressService(355),JobTerminalWriter(147),JobHeartbeatRegistry(789),JobWatchdog(376),JobCancellationRegistry(170),StaleJobRecoveryRunner(104),AsyncJobExecutor(47),ExportJobRunner(363),ImportJobRunner(222),PrecheckJobRunner(376),PublishJobRunner(384),JobProgressTotals}`、`task/{TaskService(473),TaskSseService(479),RedisTaskEventBus(355)}`、`task/controller/{TaskController(173),FileController(413)}`、`task/entity/{Task,TaskItem,TaskFile}`、`file/{FileStorageService(335),FileStorageMultiInstanceGuard(137),FileKeyMigrationRunner,RedisFileKeyMigrationLock}`、`data/service/ConfigDataService`（`parseCondition`/`upsertPublished`/`deleteRowsOutOfRange`）、`definition/service`（只读调用面）、`config/AppProperties`、`application.yml`（job/ai/file/sse）、`application-cluster.yml`。

前端交界：`stores/{ai.ts,workspace.ts,task.ts}`、`utils/{frontend-tools.ts,job-watch.ts,polling.ts,publish-*}`、`api/{tasks.ts,ai.ts,sse.ts}`、`components/AiPanel/*`（只到契约面）。

### 1.3 相邻测试（按证据强度分类）

- **真 H2 + 真 Bean + 真文件盘**（本域最可信的一层）：`AiToolsInvocationTest`、`ExportFlowJobTest`、`ImportFlowJobTest`、`PublishSemanticsTest`、`PrecheckFingerprintGuardTest`、`PrecheckGateGuardTest`、`TaskFinalizedGuardTest`、`JobMutexGuardTest`、`JobGovernanceTest`、`ExportFailureCompensationTest`、`ExportJobRunnerStorageLifecycleTest`、`JobWatchdogItemConvergenceTest`、`StaleJobRecoveryMultiInstanceTest`、`RunnerActiveMultiInstanceTest`、`TaskDelete*Test`(3)、`TaskSelectDefsDeleteConcurrencyTest`、`JobSideWriteGuardTest`、`JobProgressServiceTerminalGuardTest`、`TaskConditionJsonTest`、`FileDownloadByteIdentityTest`、`FileControllerStorageLifecycleTest`、`FileStorageKeyTest`、`FileStorageServiceTransactionCallbackTest`、`FileStorageMultiInstanceGuard*Test`、`ExportJobRunnerIssuesTest`、`PublishImportModeFailFastTest`。
- **替身/半装配**（语义靠桩件模拟）：`ToolIdempotencyTest`（`InMemoryRunStore`+假工具）、`ToolPreflightServiceTest`/`ToolTargetResolverTest`（mock 仓储）、`JobWatchdog*`（mock 仓储/心跳或 memory 模式）、`TaskSseServiceCrossInstanceTest`（假 bus）、`RedisTaskEventBusTest`（mock `StringRedisTemplate`）、`FileKeyMigration*`、`ConfirmGate*`(15)、`SpToolCallingManager*`(7)、`Resume*`(20)、`RunStore*`(18)、`ai/conformance/*`(10，`InMemoryRunStore`+`FrameContract`)。
- **前端**：`frontend/tests/*.spec.ts`（vitest+jsdom，`vi.mock('@/api/ai')` 与 `@/api/*` 全量打桩）。业务交界相关：`ai-start-job-sync`、`workspace-run-job-publish-confirm`、`workspace-audience`、`import-publish-confirm`、`gate-matrix`、`concurrency-matrix`、`tab-ownership-lifecycle`、`export-wizard-batch-download`、`job-watch-channel`、`watch-publish-outcome`、`publish-conflict-*`、`goto-step-readonly-guard`、`session-*`。
- **端到端**：`frontend/e2e/*.spec.ts`（Playwright + 桩上游 + 真后端 + 单 Redis 容器）。`ci.yml` 每提交跑 `mvn -B test` + `vitest run` + 单实例 e2e。
- **真实多实例**：仅 `.github/workflows/cluster-3node.yml`（`workflow_dispatch` 手工）：3 JVM + HAProxy 无粘滞 + **单节点 Redis 7.2.16** + **H2 TCP 共享库** + 共享盘。

### 1.4 明确未覆盖（不悄悄略过）

**产品代码未审（超出本域/只当契约）**：`excel/{ExcelReader,ExcelWriter,ExcelTemplateBuilder}` 内部；`data/service/ConditionEvaluator` 算子实现；`definition/service/{DefinitionService,DependencyResolver}` 内部；`masterdata/*`、`seed/*`、`kb/*`、`ai/config/*`、`ai/context/*`、`ai/memory/*`、`ai/history/*`、`ai/feedback/*`；`FileKeyMigrationRunner` 迁移细节（只登记 E-12）；`health/*`（另域）；前端 `AiPanel` 渲染、`sheet-*`(26)、`ui-dom-*`、`markdown-*`（非业务交界）。

**分支未覆盖（无对应用例或仅桩下可达）**：
1. `JobService.markRunnerStartFailed`（线程池拒绝后收敛 FAILED+issue）——全仓 0 用例。
2. `ImportJobRunner`/`PrecheckJobRunner` 的提交期回滚/异常后终态兜底——0 用例（EXPORT 侧有 `ExportJobRunnerStorageLifecycleTest:186-200`，见 E-02）。
3. 运行中并发修改 `task_items.condition_json` 的丢写——0 用例（E-03）。
4. 空任务（`task_items` 为空）跑完整导出/导入/发布链——0 用例（E-01）。
5. `ToolPreflightService` 影响面读真实 `task_files.row_count`——0 用例；测试手工造 `rowCount`（E-05）。
6. `FileController` ZIP 上传规模上限/内存放大——0 用例（E-07）。
7. `RedisTaskEventBus` 在真 Redis pub/sub 下的回声丢弃/退订/容器重连——只有 mock 与假 bus。
8. `job:*`/`task:events:*` 键族在真 Redis Cluster（16384 槽）的跨槽行为——`ClusterSlotTest` 只覆盖 `ai:` 键族（`FakeLeaseRedis` 模拟 CRC16）。
9. 3 JVM 真拓扑下本域链路的自动化门禁——手工触发，且非 Redis Cluster、非 Postgres。

---

## 2. 分链分析（入口→身份/session/run→共享状态→正常/异常→重连/取消/恢复→用户可见）

> 全仓**无任何身份/鉴权设施**（无 spring-security、无 `@PreAuthorize`、无 `userId` 列；ADR-11b 明确"无主体操作流水"）。因此本域"权限"只有三层：run/session 级（`SessionGate`、决策 guard、认领 fence）、资源级（task/job 守卫、资源锁）、前端页签级（Web Locks + BroadcastChannel）。**多用户隔离结构上不成立**——所有会话共享同一批任务/数据；下文按"多页签并行"审，多用户隔离归入 E-06。

### L2 `create_task`
- 身份/run：`executeOnce` 已过 scope 复查、认领（`ai:claim:`）、台账（`ai:idem:`）；本工具无 taskId，故不走 `ToolTargetResolver`（设计如此）。
- 共享状态：仅 DB `tasks`（`@Version`）；**不**写 `task_items`；`currentStep`=SELECT_DEFS/UPLOAD。
- 正常：返回"任务 #N + 下一步 `navigate_to`"；异常：类型非法→`ConflictException(ILLEGAL_ARGUMENT)`，其它→`CodedIllegalStateException(TOOL_EXECUTION_FAILED)`（`AiTools.java:265-291`）。
- 重连/取消：纯插入；同 `toolCallId` 由台账拦住，不重复建。
- 用户可见：与 REST `POST /api/tasks` 同形态（`TaskService.java:116-124`）。可证明正确（条件：上游不重发同 toolCallId）。
- 缺口：AI 建的任务无"属于本会话"的标记（E-06 同根）。

### L3 `start_*`（确认门）——本域核心
1. **入口**：模型 tool_call → `SpToolCallingManager.executeToolCalls`（`SpToolCallingManager.java:344`）。
2. **披露/作用域**：`ToolRegistry.matchesContext`（执行前复查，`ToolRegistry.java:371-376`）；越界 `SCOPE_NOT_DISCLOSED`（零副作用、不占认领）。
3. **只读预检 F5**：`ToolPreflightService.inspect`（`:106-174`）四项：任务存在/类型匹配+终态/无在途作业/预检指纹新鲜（IMPORT 还要当前 UPLOAD 件 sha256 与最近通过预检一致，`JobService.java:397-429`）。不通过 ⇒ **不弹卡**，以 `PRECHECK_FAILED` 回填模型。
4. **挂起**：`saveSuspended` → `suspended` 帧 → `tool_start` → `publishRequestsForParkedBatch` 公布 `confirm_request` → `await` 在 park 下**交还租约并结束本次执行**（`ConfirmGate.java:601-623`）。确认门**无上限**。
5. **批准（可跨实例、可数小时后）**：`POST /api/ai/confirm` → `doSubmitDecision`：读原始条目→kind 校验→PENDING 校验→`tryRecordDecision` HSETNX（全集群唯一写者）→复判→落 APPROVED→回执帧→`signal` 或 `maybeTriggerResume`（抢 `ai:lease:`+fence→`ResumeService.resume`）。
6. **执行**：续跑重建历史→`resolvePending(APPROVED 且未执行)`→`executeOnce`：kind 硬闸→台账重放→`ToolTargetResolver`→资源锁→`tryClaimExecution`（WON/LOST/STALE_FENCE）→执行（独立超时线程池）→`appendLedger`。
7. **业务守卫（执行前第二次）**：`JobService.createAndStart`（`:156-230`）在 **task 行锁内**：`TASK_FINALIZED`→`JOB_ALREADY_RUNNING`→旧 runner 存活（跨实例 Redis，失败 fail-closed）→`PRECHECK_NOT_PASSED`/`PRECHECK_STALE`→落 PENDING→`beginRunner`（Redis 写失败即回滚）→afterCommit 启动 runner。
8. **用户可见**：确认卡 `target{taskId,title,type,status}`+四项检查+影响面+`reportHash`；批准 200 带 `executed/resumeTriggered/storedBy`；进度经任务 SSE + 3s 快照兜底。
- 取消：`POST /api/ai/cancel/{runId}` 只取消**本轮**，不会撤销已启动的作业（作业需 `POST /api/jobs/{id}/cancel`）——语义正确但易误读（观察项，不计缺陷）。
- 故障恢复：批准后进程死→决策已在 Redis→任一实例抢租续跑；`STALE_FENCE` 有独立结论。

### L4 作业执行 / 终态 / 子项一致（对应 L6+L7）
- 进度/子项：`JobProgressService` 全部独立事务 + 条件 UPDATE（`finishIfInFlight`、`finishIfJobStatusAllowed`、`updateProgressIfJobInFlightAndItemOpen`），终态先到者胜；FAILED 时同事务收敛在途 `job_items`（`:146-190`）；取消时不覆盖 COMPLETED 条目（`JobService.java:510-514`）。
- 任务态联动：`JobTerminalWriter.apply`（`:103-146`）——`finish` 实际落写才联动；`tryLockForUpdate(task)` 为空（任务已删）则跳过；COMPLETED 同事务写完成步骤（`writeCompletionStep` 不走外部跳步守卫）。
- 跨实例存活：`job:beat:`（短 TTL=90s，ticker 30s 续期）+`job:runner-active:`（长 TTL=2×max-exec+5m，带 `|v2`）+`job:deadjudge:`（SET NX PX 单飞）。判死顺序：issue→FAILED→取消标志→`revokeRunner`；**不自动重排队**。
- 重启恢复：`StaleJobRecoveryRunner` 扫 PENDING/RUNNING，`isRunnerAlive` 活跃/读失败即跳过（fail-closed），否则 FAILED+条目归零+任务 FAILED。
- 一致性缺口：E-02（缺终态会悬置）、E-04（任务态联动可丢）、E-14（取消后部分暂存）。

### L5 前端工具（业务动作在浏览器）
- 后端只做：披露、下发前入参复核（`WizardActionGuard`：未知键/空 codes/小写 mode/未知 defCode/未知字段/非法算子/空条件 ⇒ 不挂起不下发）、挂起、回灌复核（`GenerativeFormRules`/`SheetWriteGuard`/`UiPrimitiveGuard`）、幂等（决策 guard + 状态机）。
- 真实写入在浏览器 → `PUT /api/tasks/{id}/…`：`select-defs`（`TaskService.selectDefs`，task 行锁，删旧增新）、`condition`（`TaskService.setCondition`→`task_items.condition_json`，**导出作业的唯一条件来源**）、`step`（`goToStep`，终态守卫+步骤白名单）、`files/{def}`（`FileController.save`，新键换引用+乐观锁 409）。
- 关键不对称：`set_condition`/`select_definitions` **不检查作业是否在跑**（模型也不知道）。不是"工具权限"问题，而是与 L6 长事务的竞态源（E-03）。
- 取消/超时：即时类前端工具 120s ⇒ `FRONTEND_TIMEOUT`（结局未知，不判未执行）；`generative_form` 无上限；用户关闭表单 ⇒ `cancelled=true` 终态。

### L9 共享文件
- 键/路径分离：DB 只存 UUID 相对键，`resolve` 按本实例 `app.file.storage-path` 还原；多实例 profile/h2tcp/postgres 强制**绝对路径**（`FileStorageMultiInstanceGuard`，配错启动失败）。
- 写入原子性：先写新键→落库→`deleteAfterCommit(旧键)`；回滚 `deleteAfterRollback(新键)`；回调异常一律吞（不阻断其它同步器）。`TaskFile.@Version` 使"在线编辑保存 vs 导出作业写同一行"以 409 暴露（`FileController.java:243-255`）。
- 字节一致：`/files/download?codes=<单码>` 回文件本字节（`FileController.java:285-310`），多码才 zip；每码 EXPORT 优先回退 UPLOAD。
- 跨实例：A 上传/B 下载字节一致的前提=共享盘+绝对路径（`FileDownloadByteIdentityTest` 只证明单实例+真存储目录）。

### L10 任务 SSE
- 本机：锁内快照、锁外带 15s 写预算、超时/失败摘除订阅者（可自愈）。
- 跨实例：`publish` **无条件**发总线（本机无订阅者也要发）；首挂末退；失败降级+10s 一次性重试；收侧按 `origin` 丢回声。
- 权威性：帧是状态投影，终态以 `GET /api/jobs/{id}` 快照为准（前端 3s 兜底 + 90s 帧空闲看门狗降级）。

### L8 删除
`TaskService.delete`（`:386-457`）：task 行锁→在途作业 409→runner 存活（跨实例 Redis，读失败 fail-closed 409）→级联（issues/job_items/jobs/staging/task_items/task_files/task）→**提交后**删盘。与 `createAndStart` 共用 task 行锁，锁序 task→job。

---

## 3. 测试可信度

### 3.1 哪一层证明了什么

| 层 | 能证明 | 不能证明 |
|---|---|---|
| `AiToolsInvocationTest`（真 H2/真 runner/真 xlsx） | "AI 工具→作业→数据面/文件"主链跑通；守卫失败不留作业行；导入只写暂存、发布落正式 | 多实例、Redis、确认门（它**直调** `aiTools.startExport`，绕过确认门/预检/目标校验/资源锁/认领全链） |
| `*FlowJobTest`/`PublishSemanticsTest`/`PrecheckFingerprintGuardTest` | 正常与异常的数据面语义、指纹失效、预检查阻断 | 跨实例接管；终态与任务态在并发下的不一致 |
| `JobWatchdogItemConvergenceTest` | 两条真实判据（绝对超时、心跳失联）+条目收敛+任务联动 | 真实 Redis TTL（心跳死亡走 **memory 模式 + `sleep(1300ms)`**；`dead-multiplier=1` 把 90s 窗压成 1s） |
| `StaleJobRecoveryMultiInstanceTest`/`RunnerActiveMultiInstanceTest` | "另一实例活着不误杀/读失败 fail-closed/本机死 PID 先清理" | **不是真 Redis**：`StringRedisTemplate` 被 mock 成 `ConcurrentHashMap`——TTL 从不生效、无 CROSSSLOT、无 pub/sub、无 MOVED；"多实例"是同 JVM 两个 `JobHeartbeatRegistry` |
| `ClusterSlotTest` | `ai:` 键族跨槽（CRC16 模拟） | `job:*`/`task:events:*` 键族；不是真 Cluster |
| `ConfirmGate*`/`SpToolCallingManager*`/`Resume*`/`RunStore*`（≈60） | 状态机与帧序契约、认领三态、租约语义（替身） | 真实 Redis 原子性/过期、真实 DB 行锁；且多处保留"替身返回 null 就降级"的接缝（`ConfirmGate.java:1133-1136`、`SpToolCallingManager.java:1425-1429`）——mock 底座能绿，生产实现若退化到 null 分支不会被发现 |
| `FileDownloadByteIdentityTest` | 单编码下载字节==上传字节（MockMvc+真存储目录） | 跨实例共享盘、绝对路径配置、旧绝对路径行 |
| 前端 `vitest`（`vi.mock('@/api/ai')`） | 给定帧/响应下前端行为（幂等、去重、恢复、只读守卫） | 后端契约本身；`concurrency-matrix`/`gate-matrix` 的"并发/权限"是**模拟调用次数**，不是真并发 |
| `e2e/*.spec.ts` | 单实例真后端+桩上游的 UI 全链（HITL、表单、表格、发布确认） | 多实例（3 JVM/无粘滞/共享盘/Redis Cluster）；真模型服从率（`ci.yml` 注释自承） |
| `cluster-3node.yml` | 3 JVM+无粘滞+SIGKILL 的真接管/收敛（若手工跑过） | 不是 Redis Cluster（单节点）、不是 Postgres（H2 TCP）、**不是 required check**；本次审计未运行 |

### 3.2 假绿/水分点（具体）

1. **同 JVM 假多实例**：`StaleJobRecoveryMultiInstanceTest`/`RunnerActiveMultiInstanceTest` 用 mock Redis（map）+ 两个 registry，TTL 语义被抹掉 ⇒ "心跳过期 ≤90s 才判死"这条**没有任何自动化用例在真 TTL 下证明**。
2. **测试自造生产不可达状态**：`AiToolsInvocationTest.upload():341-352` 手工写 `task_files.row_count`，而生产上传统（`FileController.saveUploadFile:145-161`）从不写 ⇒ 预检"影响面行数"路径的绿灯建立在假前提上（E-05）。
3. **只断言分支到达**：`ToolPreflightServiceTest`/`ToolTargetResolverTest` 全 mock 仓储；`RedisTaskEventBusTest` mock 模板；跨实例 SSE 用假 bus ⇒ 只证明"代码路径被走到"。
4. **时序水分**：`JobWatchdogItemConvergenceTest#heartbeatDeadJudgement…` 用 `sleep(1300)`+`dead-multiplier=1`+`scan-period=1h` 手动 `scan()`；语义对，但把 90s 判死窗压成 1s，真实调度叠加未覆盖。
5. **数量≠覆盖**：≈60 支 gate/run/store 用例覆盖的是**状态机与帧序**；唯一覆盖"批准→真起作业→真落文件"的是 `AiToolsInvocationTest`，而它绕过了确认门。
6. **单实例绿**：`FileDownloadByteIdentityTest`/`FileControllerStorageLifecycleTest` 用 `@DynamicPropertySource` 指定本地目录，跨实例共享盘只有手工验收记录。

---

## 4. 发现清单

> 字段：severity / 确定性 / 位置 / 触发 / 用户影响 / 测试（或缺失）。影响面按"共享 DB + ≥3 JVM + 无粘滞"陈述。

### E-01 `start_*` 服务端不校验任务是否真可执行（空选择/错步骤也能"成功"）
- **severity**：Important
- **确定性**：已证实缺陷（代码级）
- **位置**：
  - `backend/src/main/java/com/example/configmgr/job/service/JobService.java:156-166` —— `createAndStart` 只跑终态/在途/runner/预检四道守卫，**无** `task_items` 非空、步骤、文件存在性校验
  - `backend/src/main/java/com/example/configmgr/ai/tools/AiTools.java:319-326`（`start_export`）、`340-345`、`357-362`、`369-374`
  - 空集合直接"成功"：`ExportJobRunner.java:112-121`（items 空⇒循环不执行）、`PrecheckJobRunner.java:87-94`、`ImportJobRunner.java:71-78`、`PublishJobRunner.java:140-151`
- **触发**（AI 真实路径）：任务中心页 → `create_task(EXPORT)` → 直接 `start_export(taskId)`（不进向导勾选）。
  - 预检四项全过（存在/类型/无在途/导出不依赖预检）→ 弹卡 → 批准 → `ExportJobRunner` 以 `items=[]` 跑完 → `jobs.status=COMPLETED`、`tasks.status=COMPLETED`、`currentStep=EXPORT`，**`task_files` 零行**。
  - 导入侧同型且更重：空任务 `start_precheck`（0 错误 ⇒ 任务 COMPLETED）→ `start_import`（指纹检查对空 items 恒通过）→ COMPLETED → `start_publish`（任务 COMPLETED 但无 PUBLISH 成功作业 ⇒ 终态守卫放行）→ COMPLETED。四步"成功"，正式库零写入。
- **用户影响**：AI 回"导出作业已启动/已完成"，任务中心显示"已完成"，但没有任何文件/行；随后 `download_export_files` 报"码不在可导出清单"。发布侧给出**"发布成功"零数据的假事实**（审计/对账失真），且 AI 无法自我纠正（`check_job_status` 也只说 COMPLETED）。
- **测试**：无。`AiToolsInvocationTest` 每条都先 `selectDefs(...)`；`JobMutexGuardTest`/`TaskFinalizedGuardTest` 不涉空集合。
- **要点**：在 `createAndStart`（task 行锁内）按 jobType 校验：EXPORT/PRECHECK/IMPORT 需 `task_items` 非空；IMPORT 需每个 item 有 UPLOAD 件；PUBLISH 需暂存行>0；空选择以 409 结构化码回填模型。

### E-02 IMPORT/PRECHECK 缺"提交期回滚/异常"的终态兜底 → 作业悬置 RUNNING 最长 30 分钟
- **severity**：Important
- **确定性**：已证实缺陷（代码级；EXPORT 侧同类缺陷已被用例证明可复现）
- **位置**：
  - `ImportJobRunner.java:60`（`@Transactional` 包整轮）、`:183-197`（外层 catch 只写 FAILED，**未判 `rollbackOnly`、无 `afterCompletion` 补偿**）
  - `PrecheckJobRunner.java:65`（同型）
  - `ExportJobRunner.java:87-100`（**只有 EXPORT** 注册 `afterCompletion` 回滚收敛）、`:294-298`（`rollbackOnly()`）
  - `AsyncJobExecutor.java:27-45`（`catch (Exception)` 只 ERROR 日志，不写终态）
  - `JobHeartbeatRegistry.java:296-301`（`endRunner` 删 beat+anchor）、`:681-693`；`JobWatchdog.java:269-271`（`ABSENT`⇒**不判死**）
  - 证据：`ExportJobRunnerStorageLifecycleTest.java:186-200`（rollback-only ⇒ 提交期抛 `UnexpectedRollbackException`）
- **触发**：导入/预检执行中数据面事务被标 rollback-only（H2 行锁超时、约束冲突、任何 `DataAccessException` 被项级 `catch (Exception)` 吞掉），或 `progressService.markRunning` 抛错。
- **用户影响**：作业停在 `RUNNING`（进度快照已独立提交，可能停在 100%），任务仍 ACTIVE；runner 已退出 ⇒ `endRunner` 删 beat/anchor ⇒ 看门狗见 `ABSENT` **不判死** ⇒ 只能等 `app.job.max-execution-time=30m`。期间：再发起同类作业被 409 `JOB_ALREADY_RUNNING`；任务中心"正在导入"挂 30 分钟；`DELETE /api/tasks/{id}` 反而放行（runner 已不活跃）。
- **测试**：EXPORT 有 `ExportJobRunnerStorageLifecycleTest`；IMPORT/PRECHECK **零用例**。
- **要点**：把 EXPORT 的 `afterCompletion` 补偿+`rollbackOnly()` 早抛复制到两个 runner；或在 `AsyncJobExecutor` catch 里统一收敛 FAILED（条件 UPDATE 保证不覆盖先到终态）。

### E-03 作业运行期间改条件/条目状态会被静默回退（长事务+无版本列）
- **severity**：Important
- **确定性**：具体风险（代码级可推出；两种交错各一半，且无任何报错）
- **位置**：
  - `ExportJobRunner.java:112`（事务开头 `findByTaskIdOrderBySortOrder` 把 `TaskItem` 载入持久化上下文）→ `:230`（同一事务末尾 `taskService.updateItemStatus`）
  - `ImportJobRunner.java:71`→`:167`；`PublishJobRunner.java:140`→`:239`
  - `TaskService.java:348-354`（`updateItemStatus`：`findByTaskIdAndDefCode` 命中一级缓存→`setStatus`→`save`）
  - `task/entity/TaskItem.java:1-33`（**无 `@Version`、无 `@DynamicUpdate`** ⇒ 整行 UPDATE 写回旧 `conditionJson`）
  - 写入侧：`TaskService.java:162-172`（`setCondition` 只改条件+置 READY，无版本校验）
- **触发**：导出/导入作业运行中（分钟级），用户在条件表单改条件，或 AI 调 `set_condition`（前端 handler 立即 `PUT /api/tasks/{id}/items/{def}/condition`）。
  - `PUT` 在作业 flush 之前提交 ⇒ 作业提交时用**运行开始时的旧 `conditionJson`** 整行回写 ⇒ 修改**静默消失**，条目状态也被回写成 COMPLETED/IMPORTED/PUBLISHED。
  - `PUT` 在作业 flush 之后 ⇒ 阻塞等行锁（H2 默认锁超时⇒500；Postgres 长等），随后成功。
- **用户影响**：条件被静默回退，且**下一次导出/发布用的还是旧条件**——数据出口错误且无提示（`set_condition` 已 200）。本域最危险的一类：错误发生在"用户的修正"上。
- **测试**：无。`TaskConditionJsonTest` 只验证序列化；`JobSideWriteGuardTest` 只覆盖 jobs/job_items 条件 UPDATE，不含 `task_items` 并发写。
- **要点**：`TaskItem` 加 `@Version`（或 `@DynamicUpdate`+定向 UPDATE 只写 status）；或让 runner 不在长事务内改 `task_items`。

### E-04 任务态联动失败被吞 → 作业终态与任务态长期不一致
- **severity**：Important
- **确定性**：具体风险（需 `tasks` 行 `@Version` 冲突；概率低但路径确定，无重试）
- **位置**：`JobTerminalWriter.java:103-146`（`tryLockForUpdate` 后 `writeCompletionStep`/`applyJobOutcome` 在同一 `REQUIRES_NEW`；**任何异常 catch+ERROR 日志**，无重试/CAS/补偿）；`Task.java:37`（`@Version`）；`TaskService.java:297-307`（`updateStatus` 读-改-写）、`:318-343`（`applyJobOutcome`）、`:220-230`（`writeCompletionStep`）
- **触发**：作业落终态的同时有人改同一 `tasks` 行（用户点"下一步"/AI `confirm_step`→`PUT /api/tasks/{id}/step`，或另一作业的 `applyJobOutcome`）⇒ 乐观锁冲突；`finish` 已在自己 `REQUIRES_NEW` 提交（作业终态已定），任务写失败被吞。
- **用户影响**：`jobs=COMPLETED/FAILED` 而 `tasks` 仍 `ACTIVE`。后果：**PUBLISH 侧 `assertTaskNotFinalized` 放行 ⇒ 用户/AI 可二次发布**；`goToStep` 可再次跳步；作业详情与任务中心长期互相矛盾且无对账入口。
- **测试**：`JobTerminalWriterTest`/`JobTerminalWriterLateCompletionTest`/`JobTerminalStateCasTest` 覆盖"取消胜出"，**未覆盖**任务行乐观锁冲突。
- **要点**：任务态联动改条件 UPDATE，或失败时落一条作业级 issue 标记待对账。

### E-05 确认卡"影响面"数字失真：IMPORT 恒"不可估"，EXPORT 用上一次导出的行数
- **severity**：Important（审批质量：用户在错误数字上点批准）
- **确定性**：已证实缺陷（IMPORT 侧恒成立的功能缺口；EXPORT 侧为过期数字）
- **位置**：`ToolPreflightService.java:210-214`（`uploadImpact` 依赖 `task_files.row_count`）、`:216-220`（`exportImpact` 求和现存 EXPORT 行 row_count）、`:223-240`（`summedRows`：`rowCount==null` 跳过，全空⇒null⇒"不可估"）；`FileController.java:145-161`（`saveUploadFile` **不写 row_count**）；全仓唯一写点 `ExportJobRunner.java:222`
- **触发**：(a) 任一真实上传后的 `start_import`/`start_precheck` ⇒ 卡片永远"影响面暂不可估"；(b) 导出任务改过条件后再 `start_export` ⇒ 卡片显示**上一次**导出的行数。
- **用户影响**：审批人看不到真实写入/导出行数；导出侧还可能被"看起来精确"的旧数字误导（"涉及 11 行"实际 3000 行），且该数字随 `reportHash` 进台账。
- **测试**：`ToolPreflightServiceTest` 全 mock；`AiToolsInvocationTest.upload()` 手工 `setRowCount`（生产不可达）⇒ 假绿。
- **要点**：上传落库时写 `row_count`（可复用 `ExcelReader` 计数），或在预检时按需读文件头并标注"估算/实测"。

### E-06 会话与任务无服务端绑定；跨任务读取未被 F2 覆盖（多用户隔离结构上不成立）
- **severity**：Important
- **确定性**：具体风险（设计属性 + 可复现路径）
- **位置**：`ToolTargetResolver.java:119-121`（`context.getTaskId()==null` 时**跳过**"与当前工作区任务一致"校验；任务中心 `taskId` 恒 null，而 `start_export`/`start_precheck` 在 `page:tasks` 即被披露：`AiTools.java:316`、`:337`）；`AiTools.java:191-205`（`get_workspace_state` 读任意 task）、`:212-218`（`check_job_status` 读任意 job）、`:175-184`（`list_tasks` 全量）
- **触发**：任务中心 → AI"导出刚才那个任务" → `list_tasks` 截断到 20 条（`LIST_TASKS_MAX_ROWS`）→ 模型猜/串 id → `start_export(其它任务 id)`：预检只查存在+类型+无在途，**通过并弹卡**，用户须从卡片标题辨认。
- **用户影响**：可能对**非预期任务**起导出/发布（同名任务、AI 会话与页签交错时更易发生）。缓释：确认卡 `target.title`+必须点批准；无缓释部分：读数类工具可读任意任务/作业（含 items 清单）——单用户内部工具不构成越权，但"会话只读自己任务"的承诺不成立，**多用户隔离不存在**。
- **测试**：`ToolTargetResolverTest`/`ToolTargetResolverGateTest` 覆盖"有 taskId 且不一致即拒"，**未覆盖**"context.taskId 为空⇒任意 taskId 放行"这一格。
- **要点**：`page:tasks` 下要么不披露 `start_*`，要么要求 taskId 出现在本轮 `list_tasks` 结果/`recentActions` 里；读数工具加"本会话引用过的 task"白名单。

### E-07 ZIP 上传无规模上限（内存放大）——AI 上传交接卡的落点
- **severity**：Minor（安全/可用性；多实例下单个实例即可被拖垮）
- **确定性**：已证实缺陷（代码级）
- **位置**：`FileController.java:62-96`（`file.getBytes()` 整包入内存+每 entry `readAllBytes`）、`:167-186`（全量解压到 `List<byte[]>`，无 entry 数/总字节/压缩比上限；GBK 重试再解一遍全包）
- **触发**：`ui_request_upload`/IMPORT 上传页提交高压缩比 zip（数十 MB→数 GB 解压）。
- **用户影响**：OOM/长 GC，同实例 AI 轮次与作业 runner 一起受牵连；无鉴权 ⇒ 任何能开页面的人都能触发。
- **测试**：无（`excel-zip.spec.ts` 是前端打包，不是上传）。

### E-08 进度帧先于数据面提交；崩溃后前端显示过"不存在的进度"
- **severity**：Minor
- **确定性**：已证实缺陷（语义层；有 `JOB_DONE` 权威兜底）
- **位置**：`ExportJobRunner.java:184-186`、`ImportJobRunner.java:147-149`、`PublishJobRunner.java:214-216`（`progressService.snapshot` 独立事务提交后立即 `publishProgress`，而暂存/导出数据仍在外层事务里）；对照 `JobTerminalWriter` 的 afterCommit 设计（`:16-37`）
- **用户影响**：导入中看到"已处理 500 行"但 `config_staging_rows` 对外不可见（未提交）；事务最终回滚时前端仍显示过该进度；`job_items.processed` 同样"领先于数据"（E-02 的放大器）。
- **测试**：无（`JobProgressHeartbeatTest` 只覆盖心跳）。

### E-09 任务列表工具两遍全量载入（含 EAGER items）
- **severity**：Minor（性能）
- **确定性**：已证实缺陷（代码级）
- **位置**：`AiTools.java:176`（`taskService.findAll()`）+`ListTasksResultViewProvider.java:74`（同一查询再跑一遍）；`TaskService.java:58-60`；`Task.java:40-46`（`@OneToMany(fetch=EAGER)`⇒N+1 载 items）
- **用户影响**：历史任务累积后每次 `list_tasks` 变慢；多实例各付一次。

### E-10 工具执行超时的"未执行"措辞与"可能已起作业"并存
- **severity**：Minor
- **确定性**：具体风险（窗口窄；有对账提示）
- **位置**：`SpToolCallingManager.java:1473-1484`（`timeout/executed=false`、**不写台账**）、`:1617-1643`（结算条目 TIMEOUT）、`RECONCILIATION_NOTE` `:751`
- **触发**：`app.ai.tool.exec-timeout` 到期时 `createAndStart` 已提交（作业在跑）而工具线程未返回。
- **用户影响**：模型/用户看到"超时、未判定"，实际有作业在跑；重试被 `JOB_ALREADY_RUNNING` 挡住（唯一防重）；若作业恰已终态，重试会**新建第二个作业**（覆盖 `task_files.EXPORT` 与导出文件字节）。对账提示存在，但第二个作业是真副作用。

### E-11 AI 资源锁与业务守卫是两套；60s 假锁给出误导文案
- **severity**：Minor
- **确定性**：已证实缺陷（代码级）
- **位置**：`ToolResourceLocks.java:127-171`（`resource-ttl` 默认 60s、**不续租**、只在 AI 工具调用期间持有）；`SpToolCallingManager.java:1331-1332`（`RESOURCE_LOCKED_TEXT`："该任务已有作业在执行"）
- **触发**：同一任务上另一次 AI `start_*` 在 60s 内到达（即使上一个作业秒级完成）。
- **用户影响**：模型被回填"已有作业在执行，请等待"——实际没有作业在跑，只是锁的残留 TTL；用户被劝退。REST 侧不参与这把锁 ⇒ 它不是并发保护，只是 AI 侧自限流。

### E-12 旧绝对路径 `storage_path` 在滚动升级/换实例后不可读（迁移默认关闭）
- **severity**：Minor
- **确定性**：具体风险（依赖历史数据与部署形态）
- **位置**：`FileStorageService.java:109-133`（绝对路径原样 `resolve`，不重定位）；`application.yml:466-471`（`app.file.key-migration.enabled: false`）；`FileKeyMigrationRunner`（opt-in+Redis 单飞）
- **触发**：升级前落库的行（`<本机路径>\...\data\files\x.xlsx`）在另一实例/另一挂载点被读（下载、预检查、导入）。
- **用户影响**：`NoSuchFileException`⇒预检查"未找到上传文件"/导入"导入过程出错"；AI 如实回报失败但无法自愈，需人工迁移。

### E-13 本域在真实 3 JVM + Redis Cluster 下无自动化门禁（仅缺测试/未验证）
- **severity**：Important（验证缺口，非产品缺陷）
- **确定性**：未验证
- **位置**：`.github/workflows/cluster-3node.yml`（`workflow_dispatch`、单节点 Redis、H2 TCP）；`.github/workflows/ci.yml:118-120`（每提交只跑单实例 `mvn test`）
- **说明**：`job:beat:`/`job:runner-active:`/`job:deadjudge:`/`job:cancel:`/`task:events:*` 的跨槽/延迟/分区行为，`TaskEventBus` 真 pub/sub（回声、退订、容器重连）都只有替身证据；`ClusterSlotTest` 只覆盖 `ai:` 键族。
- **影响**：E-02/E-03/E-04 在多实例下触发概率更高（行锁竞争、终态联动竞态、长事务），现有门禁不会红。

### E-14 IMPORT 取消后暂存区是"部分批次"（无标记）
- **severity**：Minor
- **确定性**：已证实缺陷（代码级；影响限于可读性/审计）
- **位置**：`ImportJobRunner.java:150-164`（取消 break 后方法正常返回⇒`@Transactional` **提交**已写入的批次）、`:97`（每 def 开工先 `deleteByTaskIdAndDefCode`）
- **用户影响**：任务 CANCELLED 但 `config_staging_rows` 保留前 N 行；后续 `start_publish` 被终态守卫拒（正确），但暂存预览看到"半批数据"，与"CANCELLED=未执行"的直觉冲突；无"部分导入"标记列。

---

## 5. 可证明正确（带条件）

1. **同类作业互斥/终态守卫/删除互斥**：`JobService.java:156-166` 四个守卫全在 **task 行锁内**、落库之前；`TaskService.delete` 同一把锁、锁序 task→job。条件：DB 支持 `SELECT … FOR UPDATE`（H2/Postgres 可以）。证据：`JobMutexGuardTest`、`TaskDeleteJobCreateConcurrencyTest`、`TaskDeleteInFlightGuardTest`。
2. **终态先到者胜**：`finishIfInFlight`/`updateStatusIfInFlight`/`finishIfJobStatusAllowed` 条件 UPDATE。条件：单行 UPDATE 原子性（成立）。证据：`JobTerminalStateCasTest`、`JobTerminalWriterLateCompletionTest`。
3. **批准恰一次**：`tryRecordDecision` HSETNX + `claimExecution` 三态（含 STALE_FENCE）。条件：Redis 单键原子（Cluster 下同槽）。证据：`ConfirmGateDecisionAtomicTest`、`ToolIdempotencyTest`、`ClusterSlotTest`。
4. **预检指纹失效拦截**：`importFingerprintConflict` 从严（缺失/不可读/不一致一律 `PRECHECK_STALE`）。证据：`PrecheckFingerprintGuardTest`。
5. **取消正确性**：Redis 取消标志为准+分片边界轮询+条件 UPDATE 丢弃迟到写+取消事务内收敛条目为 CANCELLED（不动 COMPLETED）。证据：`JobWatchdogItemConvergenceTest#cancelConvergesOpenItemsToCancelledAndLeavesSuccessfulItemsAlone`、`CancelLedgerConformanceTest`。
6. **判死单一写者**：`SET NX PX` 单飞+`judgedJobIds` 去重+issue 仅 ACQUIRED 时写。条件：Redis 可用（不可用⇒心跳判死 fail-closed，超时判据只收敛终态）。证据：`JobWatchdogTest`、`JobWatchdogSharedBeatTest`。
7. **单编码下载字节一致**：`FileController.java:292-302`。证据：`FileDownloadByteIdentityTest`（真存储目录）。
8. **前端工具入参 fail-closed**：`WizardActionGuard` 在**下发前**、`suspended` 帧之前执行（空条件/未知码/小写 mode 不下发）。证据：`WizardActionGuardTest`、`FrontendGuardPreSuspendTest`。
9. **任务 SSE 写不阻塞作业线程**：锁外带预算写出+超时摘除。证据：`TaskSseServiceTest`（`SseWriteBudget` 桩）。

---

## 6. 结论

1. **E-01**（空任务/未选配置的"成功"链）——唯一能让 AI 向用户报告**完全虚假成功**的缺陷族，覆盖导出/预检/导入/发布四条链。
2. **E-02**（IMPORT/PRECHECK 终态兜底缺失）——崩溃/回滚后悬置 30 分钟，期间用户被 409 挡住、任务中心持续"进行中"；EXPORT 侧早已修好（修得不全）。
3. **E-03**（运行时改条件被静默回退）——错误发生在"用户的修正"上，无提示，直接决定数据出口。
4. **E-04**（任务态联动丢失→可二次发布）、**E-05**（审批数字失真）。
5. **E-13**：上述 2/3/4 在多实例下概率更高，但无自动化门禁（3 JVM 手工、非 Redis Cluster、非 Postgres）。

未发现 Critical 级：全仓无鉴权与用户隔离（故不存在"越权提权"面），发布侧有预检/指纹/终态/行锁四层，最坏形态是"空数据假成功"与"条件静默回退"，不是不可逆的数据破坏。
