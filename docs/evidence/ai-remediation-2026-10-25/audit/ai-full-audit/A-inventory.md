> 来源=<仓外证据>/ai-full-audit/A-inventory.md（2026-10-25 归档）· 审计分片/草稿，属历史证据，不代替终稿 `docs/research/排查-AI全链路只读验收审计-终稿-2026-10-25.md`
# A｜AI 全链路代码与测试清单（只读验收用）

> 审计对象：`<仓外证据>/ai-full-audit/tree`（固定快照）
> base sha：`ae883fbae52be36e45a635ee276cf8298ac24bd2`
> working.patch：`<仓外证据>/ai-full-audit/working.patch`，SHA256=`6c88b47de7e77263bea84e07e17a8dea154b83ad2bc35675519697bef8dab955`
> 生成日：2026-10-25　｜　本报告不修改仓内任何源码/测试/配置/Git 历史/workflow；未启动服务、未跑真实拓扑、未触 8081/5202、未读写密钥。

---

## 0. 快照口径、审计方法与“未读”声明

### 0.1 快照工作区状态（tree-status.txt，与 working.patch 一致）

已修改（14）：`ai/gate/ConfirmGate.java`、`ai/gate/SpToolCallingManager.java`、`ai/run/SseChatEmitter.java`、`health/ReadinessProperties.java`、`health/ReadinessService.java`、`job/repo/JobItemRepository.java`、`job/service/JobProgressService.java`、`job/service/JobService.java`、`task/controller/FileController.java`、`application-cluster.yml`、`application-h2tcp.yml`、`application-postgresql.yml`、`application.yml`、`frontend/src/utils/parts.ts`

新增（7）：`health/ShutdownDrainGate.java`、`ai/conformance/ParkToolStartOrderConformanceTest.java`、`ai/run/SseChatEmitterWakeFloorTest.java`、`file/FileDownloadByteIdentityTest.java`、`health/ReadinessDrainWindowTest.java`、`job/service/JobWatchdogItemConvergenceTest.java`、`frontend/tests/park-request-tool-start-order.spec.ts`

补丁含 5 组独立动机：park 帧序（tool_start 先于 request）、跨实例唤醒下界（`?lastSeq`）、作业条目终态收敛、单文件下载字节一致性、停机 draining 窗口。**本报告把 5 组都纳入 AI 交界链路**（下载/作业/停机本身不是 AI 生产代码，但属 AI 工具与多实例运行面的必经交界）。

### 0.2 阅读深度（如实披露）

| 深度 | 对象 |
|---|---|
| L1 逐行/逐段精读 | `ai/web/AiController.java`(2009 全文)、`frontend/src/api/ai.ts`、`frontend/src/api/sse.ts`、`AiEndpointGuard`、`ShutdownDrainGate`、`ReadinessService`（补丁全读）、`CancellationRegistry`、`SessionGate`（取门/降级）、`ConfirmGate`（arm/publish/await/submit/triggerResume 区段）、`SpToolCallingManager`（预检→挂起→执行循环→resolvePending）、`SseChatEmitter`（emit/stageReplay/onWake/wakeFloor）、`ResumeService.resolvePending`、`RunStore`（键族/get/create/deleteRun）、`AiChatHistoryRecorder/Service`（append/seq）、`stores/ai.ts` 的 240–660、1590–2660、2770–3400、4190–4800、4930–5523 区段 |
| L2 结构+关键分支 | `ResilientChatService`（chat/finish/emitTurnEnd）、`AiTools`、`frontend-tools.ts`、`ai-dom-tools.ts`（导出面/守卫常量）、`parts.ts`（补丁区+tool 分支）、`AiPanel.vue`（模板/composer 禁用/停止按钮）、`markdown.ts`（净化入口）、`JobWatchdog/JobProgressService/JobService`（补丁区）、`FileController`（补丁区）、`ReadinessDrainWindowTest`/`JobWatchdogItemConvergenceTest`/`FileDownloadByteIdentityTest`（新测试全文） |
| L3 仅清点（**未审内部**） | `utils/sheet-tools.ts`(2918)、`AiPanel.vue` 其余渲染/事件细节、`FormRenderer.vue`(791)、`SpreadGrid.vue`(760)、`RunStore` 未读段（快照/台账/幂等/claim/fence 实现）、`ResilientChatService` 未读段（压缩/重试/watchdog）、`AiConfigService/AiConfigWriter` 全文、`ContextCompactor/RollingSummarizer`、`KbService` 内部、`FrontendToolGuard/GenerativeFormRules` 规则表 |

**结论口径：本清单是“文件级完整 + 关键链路段级分析”，不等于逐行审完全部 5 万行主代码。L3 清单即 §7 的覆盖缺口，必须由对应审查域补齐。**

### 0.3 非 AI 业务内部不读（边界声明）

`data/*`（ConfigDataService/ConditionEvaluator）、`definition/*`、`masterdata/*`、`excel/*`、`seed/*`、`job/service/*Runner`（Import/Export/Precheck/Publish 的步骤实现）、`task/service/TaskService`、`SpreadGrid.vue` 表格内核、`ExportWizardView`/`ImportWizardView` 的向导步骤逻辑、`TaskCenterView`/`DataBrowserView`/`DefinitionsView` —— 只读到与 AI 的交界，不审内部业务正确性。

---

## 1. 分类总览与完整文件清单

### 1.1 统计

| 类 | 后端 | 前端 |
|---|---|---|
| ① 运行时/工具/模型 | `ai/run` 31 / `ai/gate` 9 / `ai/tool` 27 / `ai/tools` 1 / `ai/exec` 1 / `ai/config` 27 | `stores/ai.ts`、`api/ai.ts`、`api/sse.ts`、`types/ai.ts`、`types/sse.ts`、`types/tools.ts` |
| ② 历史/Redis 与基础设施 | `history` 4、`memory` 3、`session` 8、`observe` 3、`context` 20、存储类 | — |
| ③ 身份权限 | 无鉴权模块；`SessionGate`/`SessionPurgeGuard`/`AiRateLimiter`/`ContextFieldGate`/`ClientIpResolver`/`SecurityHeadersFilter` | 页签身份三层握手（`stores/ai.ts` 240–660） |
| ④ 业务联动（后端） | `AiTools`、`ToolPreflightService`、`ToolTargetResolver`、`ToolResourceLocks`、`ToolUnknownReconciler`、`resultview/*`、`job/*`、`FileController`、`TaskEventBus/TaskSseService` | — |
| ⑤ 前端对话 | — | `AiPanel/*`、`utils/{parts,blocks,markdown,tool-summaries,error-card,confirm-irreversible,ai-suggestions,context-usage,form-draft,ui-receipt}`、`AiSettingsView`、`AiFeedbackView`、`api/aiConfig`、`locales`、`constants/ai-settings-copy` |
| ⑥ 前端业务联动 | — | `frontend-tools`、`ai-dom-tools`、`ai-regions`、`sheet-tools`、`resultview`、`FormRenderer`/`SheetPreviewCard`/`UploadHandoffCard`、`stores/workspace.ts`、`stores/task.ts`、`job-watch`/`polling`、`api/tasks.ts`、`api/jobs.ts`、`api/http.ts`、向导页 |

精确数字：后端 AI 主代码 **196 文件 / 50,180 行**；AI 测试 **327 文件 / 69,226 行 / 2,063 个 `@Test`+`@ParameterizedTest`**；全仓后端测试 `@Test` 2,607、`@Disabled` 0。前端单测 **134 spec / 1,511 it**；Playwright **30 spec / 294 test**。

### 1.2 ① 运行时/工具/模型（后端，按子包，行数）

- `ai/run`（31 / 14,720）：`ResilientChatService`2352、`SseChatEmitter`2206、`RunStore`2822、`ResumeService`1260、`LeaseService`1146、`RedisFrameStore`758、`FrameSizeLimiter`449、`CancellationRegistry`368、`UpstreamErrorClassifier`314、`PendingToolCall`308、`ToolActivityBeacon`293、`RunSnapshot`240、`StreamWatchdog`235、`SessionKeyRegistry`221、`StartupResumeRunner`213、`RunRegistry`196、`UsageDetails`173、`FrameWakeListener`162、`RedisFrameWakeBus`149、`RetryBackoff`112、`FrameStore`99、`RunStoreFrameStore`84、`StreamViolationException`85、`RunLiveness`63、`ReplyLanguage`72、`LeaseKeys`76、`RunParkedException`53、`ProviderGoneException`47、`FrameWakeBus`37、`InstanceId`37、`SecurityEvents`90
- `ai/gate`（9 / 5,373）：`SpToolCallingManager`1940、`ConfirmGate`1937、`ToolPreflightService`399、`ToolResourceLocks`223、`ToolTargetResolver`224、`AiRateLimiter`199、`ContextFieldGate`172、`ToolUnknownReconciler`155、`InputSanitizer`124
- `ai/tool`（27 / 4,397）：`ToolRegistry`486、`ContextBuilder`411、`WizardActionGuard`393、`SheetWriteGuard`361、`ToolResultLimiter`315、`SheetAudit`269、`ToolResultEnvelope`256、`FrontendToolGuard`226、`ToolOutcome`216、`ToolSchemaNormalizer`198、`UiPrimitiveGuard`197、`SheetSwitches`170、`ToolResultClearing`166、`TruncationContract`159、`PromptDelimiters`127、`YamlToolDefinitionSource`116、`OverriddenToolCallback`52、`PlainTextToolResultConverter`42、`ToolSnapshot`40、`ToolMeta`54、`ToolScope`30、`ToolCategory/ToolChannel/ToolRisk/ToolDefinitionOverride/ToolDefinitionSource`（小）
- `ai/tools/AiTools.java`1052：20 个 `@Tool` 桥接（清单见 §2 C15）
- `ai/exec/AiExecutorConfig.java`103：`aiRunExecutor`（容量 20/队列 0/AbortPolicy）、`aiStreamWatchdog`、`aiResumeTrigger`（2/16）
- `ai/config`（19 + `config/db` 8 = 27 / 6,295）：`AiProperties`1499、`AiConfigService`1085、`AiConfigWriter`942、`AiProviderRegistry`545、`AiConnectionProbe`287、`AiKeyCipher`281、`AiEndpointGuard`222、`AiRetryDefaults`180、`AiModelCatalog`152、`AiConfigSeeder`152、`RedisAvailability`163、`AiAvailability`44、`AiModelResolver`63、`AiRetiredKeysWarner`84、`db/*`（8）
- Spring AI 补丁：`org/springframework/ai/openai/ReasoningAwareOpenAiChatModel.java`168（**不在 ai 包**，见 §1.8）

### 1.3 ② 历史/Redis 与基础设施

- `ai/history`：`AiChatHistoryService`418、`AiChatHistoryRecorder`238、`AiChatMessage`95、`AiChatMessageRepository`52
- `ai/memory`：`RedisChatMemoryRepository`412、`MessageJsonCodec`179、`ReasoningOrigin`125
- `ai/session`：`AiSessionPurgeService`1011、`SessionGate`460、`SessionPurgePendingStore`389、`SessionPurgeRetryRunner`250、`SessionPurgeGuard`172、`RedisClusterScanSupport`150、`SessionPurgeDatabase`120、`AiSessionService`102
- `ai/observe`：`AiTrace`286、`RedisDegradation`157、`AiMdcTaskDecorator`22
- `ai/context`（20 / 3,272）：`ContextCompactor`487、`RollingSummarizer`376、`MemoryWindow`248、`ContextSummaryClient`233、`ContextBreakdown`211、`TurnBudgetPlan`187、`ContextMeter`178、`ContextTrigger`157、`ContextEstimator`155、`ContextState`141、`ContextLimitResolver`127、`CompactionFuse`79、`HardTrim`73、`ContextStateStore`32、`ConversationTurns`111、`SystemMessages`68、`ToolResultClearing`（tool 包）、`ContextOverflow`51 等
- 存储实现：`RedisFrameStore`、`RedisFrameWakeBus`、`LeaseService`（Lua+fence）、`RunStore`（快照/待决/claim/decided/idem/ledger/seq/xevents/session 索引）
- DB 迁移：`V3__ai_feedback.sql`、`V4__ai_provider_config.sql`、`V5__ai_model_reasoning_backfill.sql`、`V8__ai_chat_message.sql`、`V9__ai_chat_message_idempotency.sql`
- 线程/池：`AiExecutorConfig`、`common/sse/SseWriteBudget`275、`ContextSummaryClient` 自带池
- `ai/web`（7 + `web/dto` 6 = 13）：`AiController`2009、`AiConfigController`263、`AiSessionPurgeController`128、`AiToolsController`94、`AiChatRequest`61、`FrontendToolResultRequest`45、`ConfirmRequest`12、`dto/{AvailableModelView,DefaultsView,FetchModelsView,ModelView,ProbeView,ProviderView}`（小）
- `ai/feedback`（3）：`AiFeedback`51、`ExcerptGuard`77、`AiFeedbackRepository`14
- `ai/kb`（29 / 4,027）：`KbService`943、`MarkdownChunker`452、`CachedKbSource`359、`KbTools`347、`KbSampleSeeder`242、`HttpKbSource`212、`KbText`187、`KbCache`136、`RedisKbCacheStore`135、`LocalDirKbSource`130、`KbPromptSection`108、`AiKbController`80、`KbDocMeta`70、`KbSource`67、`KbReadResult`66、`KbManifest`62、`KbSearchResult`61、`KbLookupResult`59、`KbConfig`50、`KbSection`40、`KbCacheStore`39、`KbHashing`34、`KbTerm`26、`KbStatus`24、`KbSearchHit`24、`KbDoc`23、`KbChunk`20、`KbSourceException`18、`KbCacheException`13
- 其余 AI 小文件（22，逐一登记以保证“文件级完整”）：`config/AiConfigException`、`config/AiRetryConfig`、`config/AiUnavailableException`、`config/db/AiConfigAudit`、`config/db/AiConfigAuditRepository`、`config/db/AiProvider`、`config/db/AiProviderModel`、`config/db/AiProviderModelRepository`、`config/db/AiProviderRepository`、`config/db/AiSetting`、`config/db/AiSettingRepository`、`context/ContextConfig`、`form/FormIssue`、`form/FormIssueCodes`、`form/FormOptionSource`、`form/FormOptionSourceRegistry`、`form/GenerativeFormAttachmentController`160、`form/GenerativeFormGuard`311、`resultview/ResultViewColumn`、`resultview/ResultViewColumnType`、`resultview/ResultViewKind`、`resultview/ResultViewStatus`、`tool/AiContext`83、`tool/ToolSchemaReconciler`73

### 1.4 ③ 身份权限（无鉴权模块——设计事实）

`backend/pom.xml` 无 `spring-boot-starter-security`（依赖仅 web/jpa/h2/postgres/flyway/validation/spring-ai-openai/redis/poi/jackson/lucene/lombok/test）。`docs/02-需求设计文档-v2.2-冻结版.md` §DC-10：移除用户体系/RBAC/数据权限/认证登录，安全边界=内网/本地受信。实际承担“隔离”的只有：

- `SessionGate`：同 sessionId 单轮互斥（Redis 锁 + watchdog + 降级）
- `SessionPurgeGuard`/`SessionPurgePendingStore`：销毁后禁止写回复活
- `AiRateLimiter`：session + IP 双维限流
- `ContextFieldGate`+`ContextBuilder`：上下文白名单 —— **披露闸，不是权限闸**
- `ClientIpResolver`：可信代理网段内才认 XFF
- `SecurityHeadersFilter`：安全响应头
- 前端页签身份：`pageId` + `navigator.locks('ai-session-<sid>')` + `BroadcastChannel('ai-session-ownership')`

### 1.5 ④ 业务联动（后端交界）

- 工具→业务：`AiTools`（`list_config_defs`/`get_config_def`/`list_tasks`/`get_workspace_state`/`check_job_status`/`get_row_count`/`create_task`/`start_export`/`start_precheck`/`start_import`/`start_publish`），经 `DefinitionService`/`TaskService`/`JobService`/`ConfigDataService`
- 预检/目标/锁/对账：`ToolPreflightService`、`ToolTargetResolver`、`ToolResourceLocks`、`ToolUnknownReconciler`
- 结果视图：`resultview/ListTasksResultViewProvider`（唯一 `ToolResultViewProvider` 实现）、`ResultViews`
- 作业可观测：`JobWatchdog`376、`JobProgressService`355、`JobService`555、`JobItemRepository`103、`TaskSseService`479、`RedisTaskEventBus`355
- 文件交界：`task/controller/FileController`413（本轮补丁改单文件下载）、`file/FileStorageService`335
- 健康/停机：`health/*`（`ReadinessService`229、`ShutdownDrainGate`88、`ReadinessProbeRunner`234、`DbReadinessProbe`、`RedisReadinessProbe`、`StorageReadinessProbe`、`SeedReadinessProbe`、`HealthController`）
- 通用错误/本地化：`ErrorCodes`380、`GlobalExceptionHandler`407、`ErrorBodyFactory`125、`messages*.properties`

### 1.6 ⑤ 前端对话

`components/AiPanel/AiPanel.vue`3700、`ContextPill.vue`120、`ContextPopover.vue`79、`resultview/ResultViewRenderer.vue`68、`ResultViewTable.vue`105、`ResultViewText.vue`28、`resultview/registry.ts`15；`stores/ai.ts`5523；`api/ai.ts`263、`api/sse.ts`752、`api/aiConfig.ts`212；`types/ai.ts`984、`types/sse.ts`622、`types/form-schema.ts`832、`types/tools.ts`563、`types/resultview.ts`117、`types/ui-handoff.ts`52；`utils/parts.ts`871、`blocks.ts`585、`markdown.ts`1100、`tool-summaries.ts`265、`error-card.ts`188、`confirm-irreversible.ts`90、`ai-suggestions.ts`112、`context-usage.ts`200、`form-draft.ts`87、`form-scope.ts`20、`ui-receipt.ts`93、`time-constants.ts`21；`views/AiSettingsView.vue`834、`views/AiFeedbackView.vue`82；`constants/ai-settings-copy.ts`183；`locales/index.ts`91；`router/index.ts`

### 1.7 ⑥ 前端业务联动

`utils/frontend-tools.ts`1560、`ai-dom-tools.ts`1718、`ai-regions.ts`467、`sheet-tools.ts`2918、`resultview.ts`410、`excel.ts`865（共用）、`publish-mode.ts`61、`publish-links.ts`32、`job-watch.ts`253、`polling.ts`248；`components/AiPanel/FormRenderer.vue`791、`SheetPreviewCard.vue`221、`UploadHandoffCard.vue`111、`components/SpreadGrid.vue`760（被写目标）、`components/JobProgress.vue`226；`stores/workspace.ts`639（`buildContext`/recentActions/动作）、`stores/task.ts`424；`api/tasks.ts`156、`api/jobs.ts`48、`api/http.ts`217；向导页作为 AI 跳转/读步骤目标：`ExportWizardView.vue`1789、`ImportWizardView.vue`1697、`wizard/ConditionForm.vue`359、`wizard/DefSelector.vue`228、`TaskWizard.vue`182

### 1.8 不在以上分类中的 AI 文件（**单列，供覆盖对账，最易漏**）

1. `backend/src/main/java/org/springframework/ai/openai/ReasoningAwareOpenAiChatModel.java`168 —— 官方模型补丁子类（reasoning_content 回放/剥离），不在 `ai/` 包。测试：`org/springframework/ai/openai/ReasoningReplayRequestTest`348、`ai/run/ReasoningContentUpstreamProbeTest`110、`ReasoningMemoryConvergenceTest`295。
2. `common/sse/SseWriteBudget.java`275 + `common/sse/SseWriteBudgetTest.java`290：AI SSE 单帧写预算。
3. `config/ClientIpResolver.java`305 + `ClientIpProperties`37：仅服务 AI 限流。
4. `config/AsyncConfig.java`72：异步执行器装配（需与 `AiExecutorConfig` 一起核对）。
5. `task/service/TaskEventBus.java`63、`RedisTaskEventBus.java`355、`TaskSseService.java`479：任务域，但 AI 作业工具的进度经此到前端 `job-watch`。
6. `job/service/JobWatchdog.java`376、`JobHeartbeatRegistry.java`789、`JobTerminalWriter.java`147、`StaleJobRecoveryRunner.java`104：非 AI 生产代码，但 `/api/ai/health.jobWatchdog` 与作业工具收敛依赖。
7. `frontend/src/utils/excel.ts`865：非 AI，但导出/下载工具的产物解析经它。
8. `frontend/src/types/api.ts`306、`api/http.ts`217：`FRONTEND_ERROR_CODES`/`ApiError` 是 AI 错误面（409/429/503）唯一映射点。
9. 前端共用小文件（AI 交界，非 AI 专有）：`frontend/src/utils/format.ts`118（工具卡数字/时间格式）、`utils/spreadjs.ts`86（SpreadJS 宿主注册，被 `sheet-tools` 使用）、`api/definitions.ts`37（表单 option-source `definition_fields` 的数据源）、`composables/useCopy.ts`40（代码块复制钮）。
10. 脚本/CI 资产：`scripts/chat-panel-smoke.mjs`95,753、`m2t2a-smoke.mjs`29,852、`verify-e2e.ps1`376,909、`b23l1-dual-instance.ps1`91,373、`redis5-cluster-smoke.sh`49,080、`redis-run-migration.mjs`43,902(+test)、`ci/stub-upstream.mjs`119,691 + `ci/stub-routes.json`31,427、`ci/cluster3-run.sh`18,539 + `ci/cluster3-probe.mjs`174,044、`ci/forbid-redis-cluster-unsafe.mjs`11,798、`ci/select-tests.mjs`26,496(+test)、`ci/changed-files.mjs`17,811、`ci/check-i18n-residue.mjs`、`ci/i18n-residue-baseline.json`。
11. `spike/sp01ab|sp01c|sp01d|sp02`：独立 Maven 原型（无根 pom 聚合、CI 不构建），**不属产品面**，仅登记。
12. `docs/**`：设计/证据文档，仅引用裁决口径，不作实现证据。

### 1.9 测试清单（AI 域）

**后端 AI 测试 327 文件（2,063 例）**——按子包全列：

- `ai/config/`（37）：AiAvailabilityDynamicTest、AiChatTargetResolutionTest、AiConfigSeederTest、AiConfigServiceTest、AiConfigTestFixture、AiConfigUnavailableSpringTest、AiConfigWriterGuardTest、AiConfigWriterTest、AiConnectionProbeTest、AiConnectionProbeTimeoutTest、AiDefaultTargetBindingTest、AiEndpointGuardTest、AiKeyCipherTest、AiModelCatalogTest、AiModelReasoningBackfillMigrationTest、AiModelResolverTest、AiParkConfigTest、AiPropertiesContextGuardTest、AiPropertiesLimitGuardTest、AiPropertiesMemoryGuardTest、AiPropertiesSheetGuardTest、AiPropertiesStreamGuardTest、AiPropertiesT35DefaultsTest、AiProviderLeakTest、AiProviderRegistryConfigVersionTest、AiProviderRegistryParamsTest、AiProviderRegistryUpstreamTimeoutTest、AiResumeExactProviderBindingTest、AiResumeZombieAgeConfigTest、AiResumeZombieAgeGuardTest、AiRetiredKeysWarnerTest、AiRetryDefaultsTest、ExactProviderBindingTest、ProviderRegistryHotReloadTest、RedisAvailabilityDegradationTest、RedisAvailabilityReasonTest、ResultBudgetPropertiesTest
- `ai/conformance/`（13）：CancelLedgerConformanceTest、ChatTurnFrameSequenceConformanceTest、FrameContract（夹具）、FrameContractFalsificationTest、FrameTypeSetFrozenTest、FrameWire（夹具）、InMemoryRunStore（夹具）、ParkToolStartOrderConformanceTest（新）、ReattachLastSeqConformanceTest、SessionBusyFrameIsolationConformanceTest、SseFrameSequenceConformanceTest、ThreeArgAssemblyConformanceTest、ToolFrameSequenceConformanceTest
- `ai/context/`（22）：AiCompactionLogTraceTest、CompactionFuseTest、CompactionPairingTest、ContextBreakdownServicePercentTest、ContextBreakdownTest、ContextCompactorTest、ContextEstimatorTest、ContextLimitResolverTest、ContextMeterTest、ContextOverflowTest、ContextSummaryClientTest、ContextTriggerTest、ConversationTurnsTest、HardTrimTest、InMemoryContextStateStore（夹具）、MemoryWindowLogTraceTest、MemoryWindowTest、RollingSummarizerTest、SystemPrefixPreservationTest、TestProviderRuntime（夹具）、ToolResultClearingTest、TurnBudgetPlanTest
- `ai/form/`（8）：GenerativeFormAttachmentConsumeCleanupTest、GenerativeFormAttachmentEndpointTest、GenerativeFormAttachmentGateTest、GenerativeFormContractTest、GenerativeFormProBatchRulesTest、GenerativeFormSchemaRulesTest、GenerativeFormSubmissionRulesTest、RedisFormAttachmentStoreFailClosedTest
- `ai/gate/`（43）：AiApprovalLogTraceTest、AiRateLimiterTest、AiToolCallLogTraceTest、ConfirmGateArmTest、ConfirmGateDeadlockResumeTest、ConfirmGateDecisionAtomicTest、ConfirmGateFrontendTimeoutTextTest、ConfirmGateHumanWaitTest、ConfirmGateLeaseTest、ConfirmGateRequestEmittedTest、ConfirmGateResolvedSkipTest、ConfirmGateSheetMetaPersistTest、ConfirmGateToolKindTest、ConfirmGateWiringTest、ContextFieldGateTest、FakeRateRedis（夹具）、FakeStartImportTool（夹具）、FrontendGuardPreSuspendTest、GenerativeFormGateTest、HumanWaitParkTest、InputSanitizerTest、OrphanResumePendingUnresolvedExperimentTest、ParkedFrontendExpiryTest、ResumeDispatchRetryTest、ResumeSkipCascadeTest、SheetDisabledGateTest、SheetSampleTraceTest、SpToolCallingManagerBatchStopTest、SpToolCallingManagerGateArmTest、SpToolCallingManagerScopeGuardTest、SpToolCallingManagerStepLimitTest、SpToolCallingManagerToolTimeoutTest、SpToolCallingManagerTraceFallbackTest、SpToolCallingManagerTurnBudgetTest、SuspendDisconnectGateTest、ToolIdempotencyTest、ToolOutcomeExitTest、ToolPreflightGateTest、ToolPreflightServiceTest、ToolResourceLockGateTest、ToolTargetResolverGateTest、ToolTargetResolverTest、ToolUnknownReconcilerTest
- `ai/history/`（3）：AiChatHistoryPurgeGuardTest、AiChatHistoryRecorderTest、AiChatHistoryServiceTest
- `ai/kb/`（22）：CachedKbSourceTest、FakeKbSource（夹具）、InMemoryKbCacheStore（夹具）、KbCacheTest、KbCitationIdConformanceTest、KbDisclosurePromptTest、KbHttpSourceTest、KbI18nTest、KbLocalSourceTest、KbLookupTermTest、KbPagingBudgetTest、KbPropertiesTest、KbReadDocTest、KbSampleCorpusTest、KbSampleSeederConcurrencyTest、KbSampleSeedingTest、KbServiceIncrementalTest、KbServiceIndexTest、KbTestSupport（夹具）、KbToolResultPlainTextTest、KbToolsTest、MarkdownChunkerTest
- `ai/memory/`（5）：FakeMemoryRedis（夹具）、ReasoningMetadataCodecTest、RedisChatMemoryRepositoryClusterSafetyTest、RedisChatMemoryRepositoryDeleteTest、RedisChatMemoryRepositorySaveAllEmptyTest
- `ai/observe/`（4）：AiLogPatternConfigTest、AiMdcPropagationTest、AiTraceTest、AiTurnLogTraceTest
- `ai/resultview/`（3）：ListTasksResultViewProviderTest、ResultViewFrameTest、ResultViewTest
- `ai/run/`（96）：CancelDecisionFramesTest、CancellationRegistryFailClosedTest、ChatModelContractTest、ClusterProfileConfigTest、ClusterSlotTest、ContextFrameWiringTest、ConvergeMemoryToolCallStatesTest、DeltaFrameSplitLimitGuardTest、DeltaFrameSplitTest、EmptyReplyTerminalTest、FakeFrameRedis（夹具）、FakeLeaseRedis（夹具）、FakeRenewScheduler（夹具）、FakeWakeBus（夹具）、FrameCompatReadTest、FrameWakeListenerTest、InMemoryFrameStore（夹具）、LanguageDirectiveWiringTest、LanguagePolicyTest、LeaseEpochPerRunTest、LeaseKeysTest、LeaseLossReadOnlyTest、LeaseResourceTtlTest、LeaseServiceDistinctOwnerTest、LeaseServiceTest、LeaseServiceTriStateTest、MemoryWindowWiringTest、MultiCardParkResumeTest、ReasoningContentUpstreamProbeTest、ReasoningMemoryConvergenceTest、ReasoningOriginStampTest、RedisFrameStoreTest、RedisStreamCapacityTest、ResilientChatServiceMemoryWriteTest、ResilientChatServiceParkTest、ResumeContextCompactionTest、ResumeDescribeLockKeysTest、ResumeDriveFailureTerminalTest、ResumeEnvelopeShapeTest、ResumeFailureMessageTest、ResumeKindApprovalTest、ResumeProviderGoneTest、ResumeReasoningMetadataTest、ResumeServiceLeaseTest、ResumeServiceZombieThresholdTest、ResumeSessionGateTest、ResumeSystemRestoreTest、RetryBackoffTest、RunLivenessTest、RunMigrationFamilyContractTest、RunParamsFreezeTest、RunRegistryIdleRecycleTest、RunSnapshotEpochCompatTest、RunSnapshotModelIdentityTest、RunStoreActivityAnchorTest、RunStoreClaimEpochTest、RunStoreClaimFenceTest、RunStoreDecisionGuardTest、RunStoreFencedWriteTest、RunStoreIdempotencyIndexTest、RunStoreReadFailureFailClosedTest、RunStoreRequestEmittedAtomicTest、RunStoreRunIndexPruneTest、RunStoreSaveSuspendedTest、RunStoreSessionCurrentTest、RunStoreSessionRunIndexTest、RunStoreSlidingTtlTest、RunStoreSnapshotReadTest、RunStoreTouchTest、RunStoreWalConvergeTest、SecurityEventsTest、SeqPersistenceTest、SessionKeyRegistryTest、SseChatEmitterAuthorityFailureTest、SseChatEmitterBusinessActivityTest、SseChatEmitterCrossInstanceWakeTest、SseChatEmitterDeliveryTest、SseChatEmitterDrainHandoffTest、SseChatEmitterFrameStoreTest、SseChatEmitterFramesTest、SseChatEmitterReasoningFramesTest、SseChatEmitterWakeFloorTest（新）、SseChatEmitterWriteBudgetTest、SseChatEmitterWriteTimeoutMetricTest、SseFrameSizeLimitTest、StartupResumeRunnerSessionGateTest、StreamWatchdogGateWaitBudgetTest、StreamWatchdogSegmentBudgetTest、SystemPromptHitlConfirmTest、SystemPromptPlainTextTest、SystemPromptUiSnapshotRulesTest、SystemPromptUntrustedDataTest、ToolActivityBeaconSegmentTest、UpstreamErrorClassifierTest、UpstreamErrorRetryPolicyTest、UsageDetailsTest
- `ai/session/`（9）：AiSessionPurgeServiceTest、PurgeFakeRedis（夹具）、RedisClusterScanSupportTest、RedisFormAttachmentStoreSessionIndexTest、SessionGateLeaseTokenTest、SessionPurgeDatabaseTest、SessionPurgeGuardFailClosedTest、SessionPurgePendingStoreTest、SessionPurgeRetryRunnerTest
- `ai/tool/`（17）：ContextBuilderDelimiterInjectionTest、ContextBuilderExtraTest、ContextBuilderSelectedDefsTest、PlainTextToolResultConverterTest、PromptDelimitersTest、SheetAuditTest、SheetSwitchesTest、SheetWriteGuardTest、ToolDefinitionOverridesTest、ToolOutcomeResourceCodesTest、ToolRegistryStartupFailureTest、ToolResultDualChannelContractTest、ToolResultEnvelopeDelimiterInjectionTest、ToolResultEnvelopeTest、ToolResultLimiterTest、UiPrimitiveGuardTest、WizardActionGuardTest
- `ai/tools/`（11）：AiToolsInvocationTest、AiToolsListTasksTruncationTest、AiToolsThrowPathTest、AiToolsToolResultPlainTextTest、GenerativeFormDisclosureTest、ToolRegistryDisclosureTest、ToolRegistrySheetGateOnTest、ToolRegistrySheetGateTest、ToolRegistryUiScopeTest、ToolSchemaRefIntegrityTest、ToolScopeTagSpaceGuardTest
- `ai/web/`（34）：AiChatRequestCompatTest、AiConfigControllerTest、AiConfigWriteEndpointTest、AiConfigWritePersistenceTest、AiControllerChatGateTest、AiControllerChatModelTest、AiControllerConfirmStateUnavailableTest、AiControllerContextEndpointTest、AiControllerDeniedLogTraceTest、AiControllerErrorBodyLocaleTest、AiControllerEventsParkCloseTest、AiControllerEventsStateUnavailableTest、AiControllerFeedbackTest、AiControllerFrontendResultTest、AiControllerHealthParamsTest、AiControllerHealthSheetTest、AiControllerHealthSseDeliveryTest、AiControllerHistoryDeleteTest、AiControllerHistoryPayloadTest、AiControllerParkWiringTest、AiControllerPreStreamLocaleTest、AiControllerRunsCurrentTest、AiControllerSessionPurgedTest、AiControllerSheetMetaTest、AiControllerSseLifetimeTest、AiControllerSuspendedTouchTest、AiControllerToolKindEntryTest、AiControllerZombieGateTest、AiModelsEndpointTest、AiSessionPurgeControllerTest、AiToolsDisclosureEndpointTest、ChatSessionBusyBodyTest、FrontendResultTooLargeTest、FrontendToolResultCodeTest

**AI 相邻后端测试（非 ai 包，属交界）**：`file/FileControllerStorageLifecycleTest`、`file/FileDownloadByteIdentityTest`（新）、`file/FileStorageKeyTest`、`file/FileStorageMultiInstanceGuardTest`、`file/FileStorageMultiInstanceGuardStartupTest`、`file/FileStorageServiceTransactionCallbackTest`、`file/RedisFileKeyMigrationLockTest`、`health/ReadinessServiceTest`、`health/ReadinessDrainWindowTest`（新）、`health/ReadinessProbeTimeoutTest`、`health/ReadinessProbeRunnerTest`、`health/HealthEndpointTest`、`health/HealthEndpointWiringTest`、`job/service/JobWatchdogTest`、`JobWatchdogSharedBeatTest`、`JobWatchdogLegacyAnchorTest`、`JobWatchdogItemConvergenceTest`（新）、`JobTerminalStateCasTest`、`JobSideWriteGuardTest`、`JobProgressServiceTerminalGuardTest`、`JobTerminalWriterLateCompletionTest`、`StaleJobRecoveryMultiInstanceTest`、`task/service/TaskSseServiceCrossInstanceTest`、`task/service/TaskSseServiceTest`、`task/service/RunnerActiveMultiInstanceTest`、`task/service/TaskStatusAbsorbGateTest`、`task/controller/FileControllerTaskGoneTest`、`common/GlobalExceptionHandlerSseTest`、`common/sse/SseWriteBudgetTest`、`config/ClientIpResolverTest`、`config/Dc10ExposureSurfaceTest`、`config/ProfileConfigContractTest`、`config/MigrationSetGuardTest`、`config/SecurityHeadersFilterTest`、`org/springframework/ai/openai/ReasoningReplayRequestTest`

**前端单测（`frontend/tests`，134 文件 / 1,511 it）按域：**

- 面板/帧/渲染（72）：a10-copy-closure、a10-recovery-paths、ai-followup-suggestions、ai-panel-composer-limit、ai-panel-model-default、ai-panel-params-removed、ai-panel-parts-render、ai-panel-scroll-follow、ai-panel-security-render、ai-reasoning-frame、ai-ref-i18n-stable、ai-settings-view、ai-snapshot、ai-snapshot-default-kinds、ai-snapshot-dedupe-epoch、ai-tool-receipt、ai-tool-registry、b1-suspend-disconnect、blocks、confirm-irreversible、context-pill、context-restore、empty-panel-matrix、en-us-flow、gate-matrix、gf-draft、gf-i18n、gf-renderer、gf-schema-rules、irreversible-lifecycle、limits-matrix、markdown-mermaid-import-retry、markdown-render-perf、park-request-tool-start-order（新）、parts-double-write、parts-reducer、parts-residue-cleanup、r166-gate-link、r166b-fixes、r2-midstream-refresh、ready、restore-orphan-null-req、restore-reconcile、restore-request-emitted、resultview-renderer、resultview-utils、rg-form-boolean、rg-r4-r5-mirror-dedupe、rg-round-panel、rg-round-restore-stop、rg-s13-s14-stopped-label、rg-sse-abort、session-purge-reset、session-purged-recovery、sse-language-header、sse-parser-boundary、sse-watchdog、tab-ownership-lifecycle、tool-i18n-coverage、tool-outcome-envelope、tools-and-deadline、truncated-answer、truncated-answer-panel、ui-undo-stop-reset、aie2e-panel、aie2e-qc-safety、aie2e-refresh、aie2e-refresh-forms、aie2e-refresh-upload-region、aie2e-stop、aie2e-stream、concurrency-matrix
- DOM/感知（13）：ai-dom-click-risk、ai-dom-disabled、ai-dom-fill、ai-dom-highlight、ai-dom-select、ai-dom-undo、ai-dom-verbs、ai-navigate-idempotent、ai-perception-batch-c、ai-region-audit、ai-region-batch-b、ai-region-cond-errors、ai-regions
- 表格/前端工具（22）：sheet-applied-summary-identity、sheet-condition-parity、sheet-governance-ui、sheet-grid-rebuild、sheet-preview-apply-undo、sheet-preview-cleanup、sheet-preview-highlight、sheet-preview-identity、sheet-preview-render、sheet-tools-aggregate-sort、sheet-tools-batch-read、sheet-tools-count-preview、sheet-tools-find-rows、sheet-tools-policy、sheet-tools-read、sheet-tools-registry、sheet-tools-undo-depth、sheet-tools-write、sheet-undo-confirm、form-result-limit、frontend-tool-outcome、frontend-tool-result-terminal
- 业务联动（12）：workspace-audience、workspace-run-job-publish-confirm、watch-publish-outcome、publish-conflict-link、publish-conflict-resultview、publish-mode、job-watch-channel、import-publish-confirm、import-watch-recycle、ai-start-job-sync、ai-upload-handoff、kb-tool-cards
- 非 AI（15，仅列以证明已分类）：app-shell-source、empty-views-matrix、excel-zip、export-wizard-batch-download、export-wizard-step-outcome、goto-step-readonly-guard、i18n-bootstrap、i18n-script-copy、locale-store、router-title、spread-grid-selection、task-center-view、time-constants、polling-controller、polling-predicate（后两个为 AI 作业看门狗组件，交界）

**Playwright E2E（`frontend/e2e`，30 spec / 294 test）**：00-harness、01-stream、02-reload-stop、03-hitl、04-form、05-visop、06-sheet、07-empty-error、08-session、09-real-model、10-srp、11a-hgta、11b-hgta、11c-gf2、12a-vl-vis、12b-vl-tol、13-nfezui、13b/13c/13d-nfezui、14a/14b/14c-mdxy、15-mdo、16-kqc、16-kqc-b、16-kqc-c、17-unlock、17b-unlock-budget、18-real-model；支撑脚本 `e2e/support/*`（harness-*、global-setup/teardown、gf2/nfezui/kqc/mdo/mdxy/vl upstream stubs、helpers-*、unlock、tab 相关）；9 份 playwright config（主/vl/nfezui/gf2/mdxy/mdo/kqc×3/unlock-budget/hgta）。

**CI / E2E 资产与它们实际证明什么**

| 资产 | 触发 | 证明什么 | 不证明什么 |
|---|---|---|---|
| `.github/workflows/ci.yml` backend | PR/push | `mvn -B test` 全量 2,607 例（H2 + 进程内替身，无真实 Redis） | 真实 Redis 命令语义、跨 JVM |
| `ci.yml` frontend | PR/push | `vue-tsc --noEmit` + `vitest run` 全量 1,511 例 + `vite build` | 真实浏览器/网络 |
| `ci.yml` e2e | PR/push（相关变更） | `scripts/verify-e2e.ps1` **165 例**：真后端 + 真 Redis(单节点) + **桩上游**，真 HTTP/SSE 帧断言 | 真模型行为、多实例、无粘滞 |
| `ci.yml` redis-cluster-guard | PR/push | `forbid-redis-cluster-unsafe.mjs`（KEYS/SCAN/SELECT/多库 grep 级 + 自证） | 运行时跨槽（多键脚本同槽性由 `ClusterSlotTest` 的 CRC16 替身证明） |
| `cluster-3node.yml` | **仅 workflow_dispatch** | 真 3 JVM + Redis 7 容器 + HAProxy 无粘滞：reattach/confirm/history 跨实例、kill 接管、Redis 暂停、readiness 摘除、共享文件下载 | 不是 PR 门禁；不跑真模型 |
| `e2e-real.yml` | **仅手工** | Playwright 桩域分步（主栈/VL/NFEZUI/GF2/MDXY/MDO/KQC×3/unlock）+ 真模型 `--grep @real` | 不进 PR 门禁 |
| `nightly.yml` | schedule | 全量 backend 双 locale、frontend 全量、e2e、testmap 确定性（差异棘轮） | 不作为 required check |
| `redis-5-smoke.yml` | 手工 | 真 Redis 5.0.14 3 主 3 从语义/故障冒烟 | 不进 PR 门禁 |

---

## 2. 端到端链清单（每条链 → 建议审查域）

审查域用功能标签，便于指挥官映射到 R1–R5：**D1=流与帧运行面**、**D2=门/工具/表单/表格**、**D3=会话/历史/多实例基础设施**、**D4=配置/密钥/健康/停机**、**D5=前端对话**、**D6=前端业务联动**。

| # | 链 | 入口 | 主域 | 相邻测试（代表） |
|---|---|---|---|---|
| C01 | 首轮对话（POST SSE） | `POST /api/ai/chat` | D1/D5 | ChatTurnFrameSequenceConformance、SseFrameSequenceConformance、AiControllerChatGateTest、aie2e-stream |
| C02 | 后端工具轮（模型→工具→回填→再请求） | `/chat` 内 SpToolCallingManager | D1/D2 | ToolFrameSequenceConformance、SpToolCallingManager*、AiToolsInvocationTest |
| C03 | HITL 确认门（confirm_request → POST /confirm → resume） | `/api/ai/confirm` | D2 | ConfirmGate*（11 个）、AiControllerToolKindEntryTest、MultiCardParkResumeTest |
| C04 | 前端工具（frontend_tool_request → 浏览器执行 → 回灌） | `POST /api/ai/frontend-tool-result` | D2/D6 | FrontendResultTooLargeTest、FrontendToolResultCodeTest、frontend-tool-result-terminal |
| C05 | 生成式表单（generative_form） | `frontend_tool_request` name=generative_form | D2/D6 | GenerativeForm*（8 个 form 包 + 门/披露用例）、gf-*、aie2e-refresh-forms |
| C06 | 表格写预览/应用/撤销 | `frontend_tool_request` name=replace_values/update_rows/normalize_column/undo_last_sheet_edit | D2/D6 | sheet-preview-*、sheet-tools-*、SheetWriteGuardTest |
| C07 | 上传交接（ui handoff） | `frontend_tool_request` name∈UI_HANDOFF_TOOLS | D6 | ai-upload-handoff、aie2e-refresh-upload-region |
| C08 | 刷新/重连恢复（reattach） | `GET /api/ai/runs/current`、`GET /api/ai/events/{runId}` | D1/D3/D5 | ReattachLastSeqConformance、AiControllerRunsCurrentTest、aie2e-refresh*、restore-* |
| C09 | 取消/停止 | `POST /api/ai/cancel/{runId}` | D1/D5 | CancelDecisionFramesTest、CancellationRegistryFailClosedTest、aie2e-stop、rg-sse-abort |
| C10 | 断流/重试/看门狗 | 前端 idle/connect watchdog；后端 StreamWatchdog/RetryBackoff | D1/D5 | StreamWatchdog*、UpstreamErrorRetryPolicyTest、sse-watchdog |
| C11 | 会话销毁（purge） | `DELETE /api/ai/session/{sessionId}`（旧 `/history` 同义） | D3 | AiSessionPurgeServiceTest、SessionPurge*、session-purged-recovery |
| C12 | 历史与上下文 | `GET /api/ai/history/{sid}`、`/history/{sid}/messages`、`/context/{sid}` | D3/D5 | AiChatHistoryServiceTest、AiControllerHistoryPayloadTest、context-* |
| C13 | 模型配置/密钥/健康 | `/api/ai/providers*`、`/models`、`/settings/defaults`、`/health` | D4 | AiConfigWriterTest、AiProviderRegistryHotReloadTest、AiControllerHealth* |
| C14 | 知识库（KB） | 工具 `kb_search`/`kb_read_doc`/`kb_lookup_term` + `GET /api/ai/kb/*` | D2/D3 | KbService*、KbToolsTest、KbCitationIdConformanceTest |
| C15 | 业务工具→任务/作业 | 工具 create_task/start_export/start_precheck/start_import/start_publish | D2/D3 | AiToolsInvocationTest、JobWatchdogItemConvergenceTest、import-publish-confirm |
| C16 | 文件下载（AI 前端工具 → 后端） | 工具 download_export_files → `GET /api/tasks/{id}/files/download` | D6/D3 | FileDownloadByteIdentityTest（新）、export-wizard-batch-download |
| C17 | 赞踩反馈 | `POST/GET /api/ai/feedback` | D3/D5 | AiControllerFeedbackTest |
| C18 | 多实例与停机 | SessionGate/Lease/wake/readiness/ShutdownDrainGate | D3/D4 | ClusterSlotTest、SessionGateLeaseTokenTest、ReadinessDrainWindowTest（新） |

**未被上表覆盖的 AI 文件（必须单列）**：`ReasoningAwareOpenAiChatModel`（§1.8-1）、`SseWriteBudget`（1.8-2）、`ClientIpResolver`（1.8-3）、`AsyncConfig`（1.8-4）、`TaskEventBus/RedisTaskEventBus/TaskSseService`（1.8-5，跨 C15）、`JobWatchdog/JobHeartbeatRegistry/JobTerminalWriter/StaleJobRecoveryRunner`（1.8-6，跨 C15/C18）、`excel.ts`（1.8-7，跨 C06/C16）、`api/http.ts`（1.8-8，跨全部前端链）、`spike/*`（1.8-11，不属产品面）。

---

## 3. 每条链的逐段分析

> 统一前提（按指挥要求）：后端以 **Redis Cluster + 共享 DB/文件 + ≥3 JVM + 同一对话请求无粘滞可落任意实例** 为前提；前端以 **多页签并行 + 多用户隔离** 为前提。凡“某实例内存态”承载正确性的地方都单独标出。

### C01 首轮对话（POST /api/ai/chat → SSE）

- **入口**：`AiController.chat`（`ai/web/AiController.java:299`）；`SseEmitter(NO_TRANSPORT_DEADLINE = -1L)`（`:437`、`:1911`），传输层永不到期；真正的超时在 `StreamWatchdog` + `ResilientChatService`。
- **闸序（不可换序的既有事实）**：availability 503 → 必填 400 → Redis 探活 503 → 限流 429 → 归一化 → 归一后空 400 → 长度 400 → context 形状闸（降级不回显）→ 模型存在性 400 → 参数范围 400 → 销毁守卫 410 → 会话门 409（死轮强释放）→ 提交线程池（饱和 503）。证据 `:302-483`。
- **身份/session/run**：`sessionId` **完全由调用方提供**；`runId` 服务端生成（`:412`）。没有任何“这个 session 属于谁”的服务端事实——见 F1。
- **共享状态/外部调用**：`RunStore.create` 写快照与 `ai:session:runs`；`cancellations.register`；出帧 `SseChatEmitter`（`ai:seq` INCR → `ai:xevents` XADD → `ai:wake` PUBLISH → 本地扇出）；上游是 provider 的 OpenAI 兼容流。
- **正常分支**：`start` →（可选 `context`）→ `message_start/delta/message_end` → `done`；`finish()` 先写最终 assistant 历史（幂等 messageId）→ 存快照 DONE → `convergeMemory` → `textEnd` + `done` + `turn_end`（`ResilientChatService:964-1027`）。
- **异常分支**：503 `AI_UNAVAILABLE`/`AI_KEY_*`/`AI_REDIS_UNAVAILABLE`；429 带 `Retry-After`；400 `MESSAGE_TOO_LONG`/`INVALID_PARAM`/`MODEL_NOT_FOUND`；409 `SESSION_BUSY` 携 `runId`+`reattach`；池饱和 503 `SUSPEND_POOL_SATURATED`；worker 未捕获异常 → 只回白名单短句 `AI_RUN_FAILED`（`:460-469`）。上游失败/空回复/内容过滤分别收敛为 `error`/`EMPTY_REPLY`/`CONTENT_FILTER`。
- **重连/取消/恢复**：POST 本身不重连；前端断流后走 C08。取消不在入口拦截，靠 `ai:cancel:<runId>` 在事件边界轮询（C09）。
- **用户可见**：`parts.ts` 把帧写成“段”，`deriveProjections` 生成正文/思考/工具卡/挂起卡；断流 → `streamInterrupted` + 「结果可能不完整」提示。
- **多实例**：连接可落任意实例；运行态全在 Redis，B 实例可 `events` 回放。**但**“worker 被杀/停机”没有终态写（见 F4）。

### C02 后端工具轮（模型 → 工具 → 回填 → 再请求）

- **入口**：Spring AI 官方工具循环回调 `SpToolCallingManager.executeToolCalls`（`ai/gate/SpToolCallingManager.java:345`）；`runId` 由 `toolContext` 注入，`snapshot.context` 是本轮**冻结**的披露上下文（`:374`）。
- **身份/session/run**：工具执行的“权限”只由冻结 context 决定（`ToolRegistry.matchesContext`），context 来自客户端（见 F1 的条件）。
- **共享状态**：`saveSuspended`（assistant(tool_calls) + 全部 pendings + `step` 累计）→ `suspended` 帧 → `arm()` 预登记闸门 → 预发 `tool_start`（park）→ `publishRequestsForParkedBatch` → 执行循环（`claimExecution` 原子认领 + 台账 + 幂等索引 + 资源锁）。
- **正常分支**：逐条 `executeOnce`；`tool_result` + `role:tool` 信封回填；`settle()` 写回历史/快照 RUNNING；下一跳请求；批末 `applyTurnBudget`。
- **异常分支**：预检不通过（`PRECHECK_FAILED`/`PRECHECK_UNAVAILABLE`，不弹卡不执行）；前端入参被拒（`settleFrontendRejected`）；轮内预算耗尽（`TOOL_BUDGET_EXHAUSTED`，之后不再产生副作用）；步数到顶（`TOOL_STEP_LIMIT`，部分完成收尾）；越 scope（`SHEET_DISABLED`/`SCOPE_NOT_DISCLOSED`）；工具超时（`TOOL_TIMEOUT`，轮继续）；首错即停（余下 `SKIPPED`）；取消（边界轮询）。
- **park 分支**：首个门卡进入 `await` 后抛 `RunParkedException`（`:622`），线程交还；`finally` 里 `gate.disarm(...)`（`:714`）回收整批未消费闸门；续跑时 `resolvePending` 对 `PENDING+BACKEND` **补执行**、对 `APPROVED` 补执行恰一次、其余复用结果（`:1816-1843`）。
- **重连/取消/恢复**：`RunParkedException` 不做终帧、不判失败；快照保持 SUSPENDED 合法可恢复；`claimExecution` 是“恰好一次”的最终兜底。
- **用户可见**：每张卡先 `tool_start` 再 `tool_result`；混合批次里排在门卡之后的**后端**工具会提前显示 `running`（park 批的刻意语义，`:483-494` 已裁决，不算缺陷）。
- **多实例**：认领/台账/幂等均在 Redis；`ClusterSlotTest` 用 CRC16 替身证明同槽，真集群只在手工 cluster3 网验证。

### C03 HITL 确认门（confirm_request → POST /confirm → resume）

- **入口**：`ConfirmGate.await` 发 `confirm_request` 后：park 开 → 收流 + 交还租约 + `RunParkedException`（`ConfirmGate.java:601-622`）；park 关 → 驻留线程 2s 心跳等人（`:637-713`）。
- **身份/session/run**：`POST /api/ai/confirm` 只认 `runId`+`toolCallId`（`AiController:524`），**不校验调用方是不是发起人**（F1）。
- **共享状态**：`ai:req`（请求帧已发位）、`ai:decided`（HSETNX 唯一写者，`:1150`）、pendings 状态、`confirm_decision` 回执帧、`ai:lease:run`（等待方持租/交棒）。
- **正常分支**：`ACCEPTED` → 落库 → 回执帧 → 唤醒 → `maybeTriggerResume`（`woke=false` 才派发，`:1606-1609`）→ `ResumeService` 抢租/取会话门/重建历史 → `driveResumed` → `resolvePending` 执行。
- **异常分支**：`NOT_FOUND`/`UNKNOWN_TOOL_CALL` 409、`DUPLICATE_TOOL_CALL_ID` 409、`TOOL_KIND_MISMATCH` 409（入口预读 + 闸门双判）、reason>1024 400、Redis 读失败 503、派发池饱和 `EXECUTOR_SATURATED` + 指数退避重派（15 次 ≈360s）。
- **重连/取消/恢复**：决策成功后前端带 `runSeq[runId]` 重连（`reconnectSuspendedRun`），服务端只补 `seq>lastSeq`；取消优先于 park（`:602`）；`approved+FRONTEND` 历史假批准显式 `blocked`，不执行（`:1821-1825`）。
- **用户可见**：`confirm_decision` 帧落段 → 卡变 running/rejected；回执卡三字段（decision/decidedAt）随历史回读。
- **多实例**：决策可落 B、续跑可在 C；`ai:decided` 保证恰一个写者，`ai:lease:run` 保证恰一个驱动者；`fence` 写防旧 epoch 覆盖。

### C04 前端工具（frontend_tool_request → 浏览器执行 → 回灌）

- **入口**：`handleFrame` 的 `frontend_tool_request` 分支（`stores/ai.ts:5026`）→ `runFrontendTool`（`:5447`）→ `executeFrontendTool`（`utils/frontend-tools.ts:432`）。
- **身份/session/run**：`frame.runId ?? activeRunId`；`roundEpoch` 是“是否仍属本轮”的唯一判据（`:5456`，晚到结局整段丢弃）。
- **共享状态/外部调用**：浏览器 DOM/SpreadJS/下载接口；回灌 `POST /api/ai/frontend-tool-result` → 后端 `FrontendToolGuard` 复核 → `submitFrontendResult` → 决策/续跑。
- **正常分支**：8 态执行结局（ok/rejected/blocked/cancelled/skipped/failed/timeout/unknown）落段并回灌；`outcome`/`sheetMeta`/`code` 为 additive 键。
- **异常分支**：60s 前端预算（`FRONTEND_TOOL_TIMEOUT_BUDGET_MS`，按服务端上限折算，下限 5s）；结构化结果超 8000 字 → 后端 400 `FRONTEND_RESULT_TOO_LARGE`（不落库、可重试）；表单校验失败 400 `FORM_RESULT_REJECTED`；重复 409 幂等收敛。
- **重连/取消/恢复**：回灌成功后 `reconnectSuspendedRun`（`:3211`）——**这里与 F2 的 `suspended` 判据耦合**；停止/换会话递增 `roundEpoch`，晚到结果不回灌。
- **用户可见**：工具卡状态与回执；失败原因按 code 本地化。
- **多实例**：回灌可落任意实例；`ai:decided`/pending 守卫保证幂等；浏览器副作用天然只在执行的那一端。

### C05 生成式表单（generative_form）

- **入口**：`frontend_tool_request` name=`generative_form` → `openGenerativeForm`（`:3300`）→ `FormRenderer.vue`。
- **身份/session/run**：表单创建时捕获 `runId`（`activeForm.runId`），回灌优先用它（避免旧表单错配到新 run，`submitFrontendToolResult` 注释）。
- **共享状态**：附件走 `POST /api/ai/form/attachments`（`ai:form:att:<fileId>`，TTL 2h，消费即删 + 墓碑），值回灌走 `/frontend-tool-result`；草稿只在本页签 `sessionStorage`（`utils/form-draft.ts`）。
- **正常分支**：client 校验 → 体积预检（8000）→ 回灌 → `frontend_tool_result` 帧 → 值写回段 → 表单收起。
- **异常分支**：schema 非法 → 建只读卡 + `cancelled:true` 回灌收敛（不进通用执行器）；提交被拒 → 字段级 `issues` 点亮、`retryable`；超限 → 保持 filling、可改小重试；附件过期/越界 → 400。
- **重连/取消/恢复**：刷新 → `restoreSuspendedSnapshot` 重建卡并 `loadFormDraft`；取消 → `cancelGenerativeForm` → `cancelled:true`；无任何本地时限（等人无上限）。
- **用户可见**：表单卡可填可交；提交后段回执；`formSubmittedElsewhere` 处理多端竞争。
- **多实例**：表单状态在 Redis pending；浏览器草稿不跨端（设计）。**两张“等人”表单同批**时：交互入口缺失见 F2b，提交后的重连缺陷见 F2。

### C06 表格写（预览/应用/撤销）

- **入口**：`needsSheetPreview(name,args)` → `openWritePreview`（`:3371`）→ `SheetPreviewCard.vue`；`confirmWritePreview` / `cancelWritePreview` / `undoLastUiAction`。
- **身份/run**：`activePreview.toolCallId` + `runId`；`stalePreviewToolCallId` 表示“刷新后本地 plan 丢失 → 预览已失效，只能放弃”。
- **共享状态**：前端 plan（计数/采样/全量三档）只在内存；应用时经 SpreadJS 写单元格并 `sheetMeta` 回灌；后端 `SheetWriteGuard` 复核写范围/上界，`SheetAudit` 记录白名单元数据。
- **正常/异常**：`undo` 深度 1；`SheetWriteGuard` 拒绝越界写；预览失效、区域不在当前页、后端 `SHEET_DISABLED` 各有独立文案。
- **重连/取消/恢复**：停止 = `dropSheetPreview('stop')` + `clearUiUndo`；刷新 = 失效卡 + 放弃（不重放写）。
- **用户可见**：预览行数/影响面、应用后汇总徽标、撤销行。
- **多实例**：写发生在浏览器，后端只复核与留痕；**跨页签写同一表格**无服务端互斥（`ToolResourceLocks` 只覆盖后端工具），属业务风险面（本报告不展开）。

### C07 上传交接（UI handoff）

- **入口**：`UI_HANDOFF_TOOLS` → `openUploadHandoff`（`:3479`）→ `UploadHandoffCard.vue`；`chooseUploadFile` 必须在用户手势同步栈内调 `showOpenFilePicker`。
- **共享状态**：区域注册表（`ai-regions.ts`）在本页签内存；交接卡状态 waiting/missing；回灌带 `cancelled:true` 或结果。
- **正常/异常**：区域不在当前页 → missing（只能放弃）；刷新 → `rebuildPendingCard` 按区域是否仍注册重建 waiting/missing；停止 → 交接卡随轮收起。
- **用户可见**：交接卡四态；选完文件后由业务上传接口落盘并 `notifyAiUploadDone`。
- **多实例**：与其它前端工具同面；浏览器绑定，不可跨端恢复“已选文件”中间态（设计）。

### C08 刷新/重连恢复（reattach）

- **入口**：`loadHistory()`（`:1659`）→ `restoreSuspendedSnapshot()`（`:2074`）→ `restoreInFlightRound`（RUNNING，`lastSeq=0` 全量）/ `resumeSnapshotStream(archiveMaxSeq)`（SUSPENDED 增量）。
- **身份/session/run**：入口捕获 `sid` 与 `currentAbort` 句柄，await 后二次对账（`:2086`）；不一致整段丢弃。
- **共享状态**：服务端 `runs/current`（pendingEntries/archiveMaxSeq）、事件日志回放、`channel=ai-mirror` 本地镜像、Web Locks 页签身份。
- **正常分支**：快照待决条目串行重建（一次一张，`restoredPendingQueue`）；无卡但挂起也挂流（I-1）；终态/空态 → 封口。
- **异常分支**：`archiveMaxSeq` 读不到 → 503+degraded（不按 0 全量回放）；快照过期 → 空态；镜像缺失 → 历史重建。
- **重连/取消/恢复**：后端 `events` 在 park 且仍等人时**主动收流**（`AiController:766-769`），前端按 `awaitsInput` 视为正常；决策后由前端显式重连。
- **用户可见**：无“重挂/在途”技术文案；半截正文续写；挂起卡就地出现。
- **多实例**：任意实例可 serve；`archiveMaxSeq` 来自 `FrameStore.lastSeq`（含 LIST 兼容尾巴）。**增量锚点与镜像 flush 的关系见 F8（正常刷新已有 flush，属低概率残留）。**

### C09 取消/停止

- **入口**：前端 `stop()`（`stores/ai.ts:4339`）：本地先收口（abort、`cancelRoundParts`、`markStoppedMessage`、落镜像）→ `POST /api/ai/cancel/{runId}`。
- **身份/run**：只有 `runId`；`stopping` 防重复点击。
- **共享状态**：`ai:cancel:<runId>`（TTL=session-ttl 24h）；进程内 `flags` 加速；park 挂起轮由 `triggerResumeForParked` 触发一次收敛（`AiController:873-878`）。
- **正常/异常**：Redis 写成功 → 跨实例生效；Redis 写失败 → 仍回 `cancelled:true`（`AiController:862`）+ `redisFlagWritten:false`（F6）。取消后 `done{cancelled:true}`，未决条目转 CANCELLED，重启续跑不会执行。
- **重连/恢复**：`stop()` 不依赖连接；`stopped` 只影响被停那一轮；前端不因 abort 报断流。
- **用户可见**：半截正文标记「已停止」，不回滚已执行副作用；撤销点随轮作废。
- **多实例**：跨实例取消依赖 Redis 标志；`CancelState.UNKNOWN` 时等待方继续等（不误判取消）。

### C10 断流/重试/看门狗

- **入口**：前端 `streamChat`/`reattach` 的 idle(45s)/connect(15s) 看门狗与 4 次退避（1/2/4/8s ±25%）；后端 `StreamWatchdog` + `RetryBackoff` + `UpstreamErrorClassifier`。
- **判据**：前端 45s < 代理 60s < 后端 90s（刻意错层）；后端判据 = 首包 30s / 包间 90s / 段预算 300s，且挂起等待与工具执行期间不计静默。
- **正常/异常**：无终帧一律判断流（`error`/`done` 是唯一正常收尾）；重试仅当“未产出正文 + 无副作用 + 还有次数 + 未超预算”（`ResilientChatService:754-761`）。
- **重连**：`tryReattach` 带 `state.lastSeq`；重挂耗尽 → `terminal=false` + 提示。
- **用户可见**：`retry` 提示、`streamInterrupted` + 原因、`upstreamRetry` notice。
- **多实例**：看门狗在驱动实例本地；它只看快照/取消标志/信标，不含本实例私有状态。

### C11 会话销毁（purge）

- **入口**：`DELETE /api/ai/session/{sessionId}` 与旧 `/api/ai/history/{sessionId}`（`AiSessionPurgeController:78`）；前端 `resetSession`（`:4768`）。
- **身份**：无主体校验（F1）；`requestedBy` 只作台账。
- **顺序**（`AiSessionPurgeService` 类注 59-71）：枚举 → durable pending（无 TTL）→ 装守卫 → H2 删 → 取消活跃 run → 按 run 删 Redis（13 键同槽 DEL）→ 附件文件 → 会话键 → 守卫保留期 + 移除 pending；有活跃 run 则 `deferred` + `complete=false`。
- **正常/异常**：幂等（重复 DELETE 复用 purgeId）；任何前置失败“一个键都不删”；SCAN 逐 master（`RedisClusterScanSupport`）失败即 fail-closed。
- **重连/恢复**：销毁后 `/chat` 回 410 `SESSION_PURGED`，前端换新 id（`send` catch → `adoptFreshSession`）；写回守卫阻止记忆/历史复活。
- **用户可见**：`complete=false` 时提示“整会话未删净”；`clientCleanup` 精确清镜像/草稿。
- **多实例**：`deferred` + 重试 runner；`ai:purge:*` 跨实例；`complete` 取决于每个实例的删除结果。

### C12 历史与上下文

- **入口**：`GET /api/ai/history/{sid}`（40 条记忆窗口，可 offset/limit）、`GET /api/ai/history/{sid}/messages`（DB 完整历史，beforeSeq/afterSeq 游标）、`GET /api/ai/context/{sid}?ledger=`。
- **身份**：无鉴权；任何人可按 sessionId 读（F1）。
- **共享状态**：Redis 记忆窗口（tagged+legacy，staging→RENAME 原子覆盖）；DB `ai_chat_message`（`(session,seq)` 唯一 + `(session,run,messageId)` 唯一，冲突重试 32 次）；`ai:ctx:*`。
- **正常/异常**：历史信封解包（只暴露 `message` 原文）、截断五字段、`resultView` 按工具名重建（只对成功结局）；`/context` Redis 不可用 → 503；**`/history` Redis 不可用 → 未包，见 F5**。
- **重连/恢复**：刷新历史整体替换 + 镜像在途轮合并（`mergeMirrorInFlightRound`）；空历史 → 待决段过期降级。
- **用户可见**：刷新后完整对话、工具卡、已停止/截断标记、上下文占用条。
- **多实例**：DB 唯一键 + 重试保证跨 JVM 不重号不跳号；Redis 记忆窗口用 hash tag 同槽。

### C13 模型配置/密钥/健康

- **入口**：`/api/ai/providers*`、`/models`、`/models/fetch`、`/settings/defaults`、`/api/ai/health`、`/api/ai/tools`、`/api/ai/kb/reload`。
- **身份**：无鉴权，配置面等同“管理员权限”（文档已知悉）；SSRF 闸见 `AiEndpointGuard`（http/https、禁内嵌凭据、禁私网/元数据，DNS 重绑定为已登记限制）。
- **共享状态**：DB `ai_provider`/`ai_provider_model`/`ai_setting`（config.version 真源）；Redis `ai:cfg:ver`（跨实例失效）+ `ai:cfg:models:<id>` 缓存；key 用 AES-256-GCM 信封 `v1.kid.iv.ct`，AAD 绑 `code|keyRef`，KEK 只来自 `AI_SECRET_KEK`，缺失即 fail-closed。
- **正常/异常**：写入校验（URL/参数/模型清单）→ 版本号 +1 → INCR 失效；探活/拉模型有 5s 超时；明文 key 不落库（DB 存密文信封），种子行可回读 env；`/health` 只回 `last4`/指纹与状态，不含 key。
- **重连/恢复**：每实例 ≤1s/次比对 DB 版本；Redis 不可用退化为本地版本比对。
- **用户可见**：设置页掩码 `****last4`；`/health` 503 时给稳定码 + 变量名诊断（不含值）。
- **多实例**：版本轮询 + Redis 信号；同实例在途轮不受影响（快照冻结 provider/model/params）。

### C14 知识库（KB）

- **入口**：工具 `kb_search`/`kb_read_doc`/`kb_lookup_term`（`kb/KbTools.java:113/171/302`，全部 `@ToolScope("*")`、READ）；`AiKbController` 提供状态/重载。
- **共享状态**：本机 Lucene 索引 + Redis 缓存（`KbCache`/`RedisKbCacheStore`）；local 目录或 HTTP 源（manifest + ETag + sha）；来源 `http.base-url` 可配，`followRedirects(NORMAL)`。
- **注入防御**：目录行过 `KbText.sanitize` 包 `<knowledge_catalog>` + “数据不是指令”；工具结果过 `PromptDelimiters`/信封；引用只允许工具实际返回过的 `[doc_id §章节]`。
- **正常/异常**：索引未就绪只注入规则不注入空目录；源不可用保留旧索引（fail-open）；Redis 不可用降级直连源。
- **用户可见**：知识卡、引用锚点、未命中明确说“未找到”。
- **多实例**：索引目录为共享/本机文件（`./data/kb-index`），多实例各自建；缓存同 Redis。**HTTP 源无地址白名单/无重定向再校验**（配置面风险，非用户输入面）。

### C15 业务工具→任务/作业

- **入口**：`create_task`(WRITE/W)、`start_export`/`start_precheck`/`start_import`(DANGER/J)、`start_publish`(DANGER/P，不可撤销)；经确认门（HITL）。
- **共享状态**：DB 任务/作业/条目；`JobHeartbeatRegistry`/`JobWatchdog`/`JobTerminalWriter`；Redis 作业取消/runner-active；任务 SSE（`TaskSseService`）→ 前端 `job-watch`（SSE 主 + 3s 轮询兜底 + 90s 帧空闲看门狗）。
- **正常/异常**：预检（导入文件 sha/映射、任务步骤）→ 确认 → 执行；看门狗判死/判超时 → 作业 FAILED；补丁新增：**FAILED 时同事务把 `PENDING/RUNNING` 条目收敛为 FAILED**（`JobProgressService:146-190`），取消时收敛为 CANCELLED（`JobService:502-516`）；`COMPLETED` 不臆断、不动已终态条目。
- **重连/恢复**：作业进度刷新后由快照重建；终态以 `GET /api/jobs/{id}` 为准，不拿 `JOB_DONE` 载荷当终态。
- **用户可见**：确认卡（不可撤销带复选框）、进度条/条目表、失败原因。
- **多实例**：DB 条件 UPDATE 决胜；SIGKILL 后条目不再永久 RUNNING（补丁动机，`JobWatchdogItemConvergenceTest` 用真 H2 证明）。

### C16 文件下载（AI 工具 → 后端）

- **入口**：前端工具 `download_export_files`/`download`（`frontend-tools.ts:508` 白名单）→ `api/tasks.ts#downloadSelected` → `GET /api/tasks/{id}/files/download?codes=`。
- **补丁语义**：单编码回**文件本身字节**（不再包 zip）；多编码才 zip；每编码只取一个（EXPORT 优先→UPLOAD），按请求顺序去重（`FileController:272-330`）。
- **异常**：无命中 404；存储读失败 500；`/files/{defCode}` 仍恒 xlsx MIME（本应用上传/导出均为 xlsx，`save()` 固定 `.xlsx`）。
- **用户可见**：下载得到的 sha256 与上传一致（补丁动机）；单文件不再“套一层 zip”。
- **多实例**：文件存共享盘（`app.file.storage-path`），`FileStorageMultiInstanceGuard` 启动校验；`FileDownloadByteIdentityTest` 用真 H2 + 真存储断言字节。

### C17 赞踩反馈

- **入口**：`POST /api/ai/feedback`（upsert，rating 0=删行）、`GET /api/ai/feedback`（最新 100 条）。
- **共享状态**：DB `ai_feedback`（`message_id` 唯一）；`excerpt` 命中高置信凭据整条替换 `[已隐去]`；`reason` 截 1024。
- **异常**：参数非法 400；**并发首写撞唯一键 → 未捕获 → 500**（F7）；Redis 无关。
- **用户可见**：赞/踩选中态；失败回退并提示（`submitFeedback` 返回 false）。
- **多实例**：唯一键是唯一并发保护，缺“冲突即读后重试”。

### C18 多实例与停机

- **互斥/所有权**：`SessionGate`（Redis 单键 Lua，同 run 可接管、token 精确释放、watchdog 30s 续期、Redis 异常降级进程内并如实 `degraded`）；`LeaseService`（`ai:lease:run` + `ai:fence:run` + 三键同槽脚本）；`ai:decided` 唯一写者；`claimExecution` 原子认领。
- **帧投递**：`ai:xevents` Stream + `ai:seq` INCR + `ai:wake` PUBLISH；`FrameWakeListener` 按连接订阅；补丁后**每订阅者 `wakeFloor`**，`onWake` 读取下界取所有活订阅者最小游标（`SseChatEmitter:1556-1631`），避免“别人的回放把落后订阅者饿死”。
- **就绪/停机**：`/health/live` 恒 200；`/health/ready` 依赖探测（共享 2s 预算）或 draining 快路径 503；`ShutdownDrainGate` 在 `ContextClosedEvent` 以 HIGHEST_PRECEDENCE 置 draining 并在事件扇出期阻塞 `drain-window`（cluster/h2tcp/postgresql=5s，缺省 0），窗口后才让 Spring graceful shutdown 停接连接。
- **失败姿态**：Redis 不可用 → 入口 503（不摘 ready，`HealthController` 注释明确）；取消状态 UNKNOWN → 继续等；记忆/历史读失败 → 503（`/history` 例外，F5）。
- **用户可见**：滚动重启时应“先 503 摘流、再断”；**AI 运行面未纳入停机收敛**（F4）。

---

## 4. 多实例共享状态面（Redis/DB/文件）清单

> 这张表是“同一对话请求可落不同实例”时的正确性底座；任何在本实例内存里保存“正确性必需状态”的写法都应视为缺陷候选。

| 键/存储 | 内容 | TTL | 谁写/谁读 | 集群安全 |
|---|---|---|---|---|
| `ai:run:<runId>` | 快照（状态/历史/身份/params/step/in-flight） | session-ttl 24h 滑动 | RunStore create/save/get | 单键 |
| `ai:pending:<runId>` | 待决条目 HASH（kind/status/req/decided/executed/result） | 24h | ConfirmGate/SpToolCallingManager/ResumeService | 单键/HSETNX |
| `ai:claim:<runId>` / `ai:decided:<runId>` | 执行认领 / 决策 guard(WAL) | 24h | 原子认领/唯一写者 | 单键 HSETNX |
| `ai:ledger:<runId>` / `ai:idem:<runId>` | 执行台账 / 幂等索引（指针） | 24h | 执行/续跑防重放 | 单键 + 同槽脚本 |
| `ai:lease:run:<runId>` / `ai:fence:run:<runId>` | 续跑权（epoch / instance）/ 已落库最大 epoch | 30s 续租 / 24h | ResumeService/ConfirmGate | **三键同槽脚本** |
| `ai:session:lock:<sid>` | 同会话单轮门（runId / token） | 10m + 30s 续期 | SessionGate | 单键 Lua |
| `ai:session:current:<sid>` / `ai:session:runs:<sid>` | 当前挂起轮 / 会话 run 索引 | 24h | runs/current、purge | 单键/SET |
| `ai:cancel:<runId>` | 取消标志（权威） | 24h | cancel/等待/看门狗 | 单键 |
| `ai:seq:<runId>` | 业务帧取号计数器 | 24h | SseChatEmitter | INCR 原子 |
| `ai:xevents:<runId>` | 事件 Stream（帧归档 3000 窗口） | 24h | 回放/唤醒补帧 | Stream |
| `ai:events:<runId>` | 旧 LIST 归档（只读兼容期） | 24h | 回放拼接去重 | LIST |
| `ai:wake:<runId>` | 跨实例唤醒频道（载荷=上界 seq） | — | FrameWakeBus/Listener | pub/sub |
| `ai:beat:<runId>` / `ai:activity:<runId>` | 心跳戳 / 业务活动锚点 | 24h | 僵尸判据 | 单键（裸前缀，不进批量 DEL） |
| `ai:req:<runId>` | 请求帧已发位（req） | 24h | runs/current 重建判据 | HASH HSETNX |
| `ai:ctx:<sid>` / `ai:ctx:ledger:` / `ai:ctx:archive:` | 上下文真值/压缩台账/压缩前原文 | 24h | 计量、压缩 | 单键/LIST |
| `ai:safety:<sid>` | 安全事件台账 | 24h | /health.security | LIST |
| `ai:form:att:<fileId>` / `:used:` / `:sess:<sid>` | 表单附件登记/墓碑/会话索引 | 2h | 附件上传与消费 | 单键/SET |
| `ai:rate:session:<x>` / `ai:rate:ip:<x>` | 限流 ZSET | 60s 窗口 | /chat 闸 | 单键 Lua |
| `ai:cfg:ver` / `ai:cfg:models:<id>` | 配置版本信号 / 模型清单缓存 | 10m（缓存） | 热更新/设置页 | 单键 |
| `ai:purge:pending:<sid>` / `:retry:queue` / `:guard:<sid>` | 销毁台账/重试队列/写回守卫 | 台账无 TTL、守卫保留期 | purge/写回守卫 | 单键/LIST |
| `ai:lock:job:<taskId>:<TYPE>` / `ai:lease:epoch*` | 资源锁 / 租约纪元 | 60s / — | ToolResourceLocks/LeaseService | 同槽脚本 |
| `chat:mem:<cid>` / tagged staging/final | 记忆窗口（40 条） | 24h | 记忆读写 | **hash tag 同槽** |
| DB `ai_chat_message` | 完整历史（seq 每会话单调） | 永久 | 历史读写 | `(session,seq)`/`(session,run,messageId)` 唯一 |
| DB `ai_feedback` / `ai_provider` / `ai_provider_model` / `ai_setting` | 反馈/配置/版本 | 永久 | 反馈/配置 | `message_id` 唯一 |
| 文件 `app.file.storage-path` / `app.ai.kb.dir` / `app.ai.kb.index-dir` | 任务文件 / KB 资料与索引 | 磁盘 | 下载/知识库 | 共享盘（启动守卫） |

---

## 5. 测试可信度评估

### 5.1 分层与“各自证明什么”

| 层 | 规模 | 真实部分 | 替身部分 | 能证明 | 不能证明 |
|---|---|---|---|---|---|
| 后端单测（`mvn test`） | 2,607 例；AI 域 2,063 | 真实 Java 对象、真实状态机、真实 H2（部分）、真线程池（部分） | **Redis 全部为进程内替身**：`FakeLeaseRedis`（含 CRC16 集群模式）、`FakeFrameRedis`（INCR/XADD/XRANGE，拒绝 5.0 排他 ID）、`FakeMemoryRedis`、`FakeRateRedis`、`InMemoryRunStore`、`InMemoryFrameStore`、`PurgeFakeRedis`、`FakeKbSource`/`InMemoryKbCacheStore` | 本类分支/帧序/键名/脚本入参/降级路径 | 真实 Redis 语义（Lua 原子性、跨槽、pub/sub、Stream 截断边界）、真实网络/容器 |
| 前端单测（`vitest`+jsdom） | 1,511 it | 真实 store/reducer/DOM（jsdom） | `api.chat/reattachRun` 等 **全部被 mock**；`BroadcastChannel`/Web Locks 为测试替身/缺失 | UI 状态机、段/投影、恢复逻辑 | 真实浏览器 API（Web Locks、文件选择器）、真实网络 |
| Playwright E2E（`frontend/e2e`） | 30 spec / 294 test | 真浏览器 + 真后端 + 真 Redis（部分域） | **上游是 `stub-upstream.mjs` 桩模型**（除 `09/18-real-model`） | 用户可见链路（DOM/帧/交互） | 真模型行为、多实例（除 `15-mdo` 双 JVM 场景） |
| `verify-e2e.ps1`（CI 门禁） | 165 例 | 真后端 + 真 Redis 单节点 + 真 HTTP/SSE | 桩上游 | 帧序/门/表单/作业/取消等接口级行为 | 多实例、无粘滞、真模型 |
| `cluster-3node.yml` | 手工 | 真 3 JVM + Redis 7 集群 + HAProxy | 桩上游 | 跨实例/接管/kill/摘流 | 不是 PR 门禁；不覆盖真模型 |
| `e2e-real.yml` | 手工 | 真 key + 真模型采样 | — | 模型服从率/概率性 | 不进 PR 门禁；采样口径非确定性 |

### 5.2 结构性问题（影响“绿=可用”的推断）

1. **生产是 park=true，单测缺省 park=false**：`application.yml:258-259` 显式 `park.enabled: true`，而 `AiProperties.Park.enabled` 代码缺省 `false`（`AiProperties.java:855`，注释自认“刻意不一致”）。全仓只有 **7 个测试文件**显式 `getPark().setEnabled(true)`：`conformance/ParkToolStartOrderConformanceTest`、`gate/FrontendGuardPreSuspendTest`、`gate/HumanWaitParkTest`、`gate/ParkedFrontendExpiryTest`、`gate/ResumeDispatchRetryTest`、`web/AiControllerEventsParkCloseTest`、`web/AiControllerParkWiringTest`。其余 gate/run/tool 单测走的是**阻塞等待老路径**。因此“2,000+ 单测全绿”**不能**推出生产 park 控制流正确；park 的真实覆盖主要靠 CI 的 165 例接口脚本与手工 3 实例网。
2. **Redis 语义全靠替身**：`RedisFrameStoreTest` 类注明确“Redis 用进程内替身，不起真实 Redis”；`ClusterSlotTest` 用替身按 CRC16 判跨槽。替身能做“跨槽即报错”（比 Lettuce 更严），但无法证明真实 Lettuce 的分区/重试/超时行为。
3. **多实例真拓扑不在提交门禁**：`cluster-3node.yml` 仅 `workflow_dispatch`；`e2e-real.yml` 仅手工。PR 门禁里没有任何“≥2 JVM”执行。
4. **Playwright E2E 不在 PR 门禁**：PR 的 E2E 是 `verify-e2e.ps1`（PS 脚本 + 单实例）；30 个 Playwright spec 只在手工工作流跑。二者不是同一套用例。
5. **桩模型决定“答案”**：CI E2E 的模型输出由 `scripts/ci/stub-upstream.mjs` 按路由表生成（`stub-routes.json` 31KB）。它证明“给定模型输出，链路正确”，**不证明模型会产出该输出**（如 GF 表单、工具调用顺序）。
6. **soft-green 点**：`verify-e2e.ps1` 的 P2 红不阻塞退出码（脚本 5707-5725）；GF SKIP 在桩模式零容忍、真模型模式只要求 GF1–GF4 不全 SKIP（其余 SKIP 不判红）；`frontend/e2e/17-unlock.spec.ts:677` 有显式 `test.skip('E2E-R18 …（缺稳定抢发面，见报告）')`。
7. **时序类断言**：`ReadinessDrainWindowTest` 用 700ms 窗口 + “≥80%”阈值 + 10ms 轮询观测者；`ConfirmGate`/`SseChatEmitter` 多处 await/超时。属“真实行为”但受 CI 负载影响，存在偶发红/偶发绿的可能（该测试自称“比真实链路更严格”，但它不绑真实端口，连接器用监听器替身）。
8. **只断言 mock/200 的例子（不能当生产验证）**：
   - `AiControllerChatGateTest`（`web` 包）：`new AiController(...)` + `mock(ResilientChatService)`，断言“闸门把请求交给 service”“429 形状”。证明入口闸，不证明对话链路。
   - `AiControllerFeedbackTest`：MockMvc + mock 仓储，证明载荷/状态码，不证明并发 upsert。
   - 前端 `aie2e-*.spec.ts`：`api.chat`/`api.reattachRun` 全部 mock 为未决 Promise，证明 store 状态机。
   - `FileDownloadByteIdentityTest` / `JobWatchdogItemConvergenceTest` / `ReadinessDrainWindowTest`（三个新测试）**质量相对高**：真 H2/真存储/真 Bean/真 Spring 事件，但仍各自缺真实端口/真实 Redis。
9. **新测试的具体边界**：
   - `ParkToolStartOrderConformanceTest`：真 `ConfirmGate`+`SpToolCallingManager`+`ResumeService`，但用 `InMemoryRunStore`；证明“pre-emit 与去重”的帧序，不证明 Redis 并发下的 `req` 位与跨实例 park。
   - `SseChatEmitterWakeFloorTest`：`InMemoryRunStore` + `FrameWire`，证明 `wakeFloor` 算法，不证明真实 pub/sub 丢唤醒。
   - `frontend/tests/park-request-tool-start-order.spec.ts`：纯 `applyFrameToParts` 单测，证明前端顺序防御，不证明后端帧序（后端帧序由上面的 Java 测试证明）。

---

## 6. 发现（severity / 确定性 / path:line / 触发 / 影响 / 测试）

### 6.0 先给“可证明正确（带条件）”的部分（避免只列缺陷）

- **同会话单轮互斥**：`SessionGate.acquire` 用单键 Lua“比对后写”，token 精确释放，watchdog 续期；条件 = Redis 可用（不可用时降级为进程内并在响应体 `degraded=true` 如实标注）。证据 `session/SessionGate.java:80-143/192-231`。测试 `SessionGateLeaseTokenTest`（536 行）+ `SessionBusyFrameIsolationConformanceTest`。
- **决策恰一次**：`ai:decided` 的 `HSETNX` 抢唯一写者 + WAL 收敛；条件 = Redis 单键原子（Cluster 单键天然成立）。证据 `ConfirmGate.java:1138-1191/1296-1320`。测试 `ConfirmGateDecisionAtomicTest`、`ConfirmGateDeadlineResume*` 系列。
- **副作用恰一次**：`claimExecution` 原子认领 + 台账 + 幂等索引；条件 = `ai:claim`/`ai:ledger`/`ai:idem` 同槽（键带 `{run:<runId>}` tag，`RunStore.java:445-476`、`ClusterSlotTest`）。测试 `ToolIdempotencyTest`、`RunStoreIdempotencyIndexTest`、`RunStoreClaimFenceTest`。
- **历史 seq 不重不跳**：`(session_id,seq)` 唯一 + 冲突重试 32 次；条件 = 共享 DB 且隔离级别允许 READ COMMITTED 重试。证据 `history/AiChatHistoryService.java:50-72/289-300`、`V8/V9`。测试 `AiChatHistoryServiceTest`。
- **回放不重复不缺口**：`stageReplay` + 每订阅者 `wakeFloor` + `minDeliveredSeq`；条件 = 归档有界（3000）且客户端 `lastSeq` 真实。证据 `SseChatEmitter.java:669/841-886/1556-1631`。测试 `ReattachLastSeqConformanceTest`、`SseChatEmitterWakeFloorTest`、`SseChatEmitterCrossInstanceWakeTest`。
- **单文件下载字节一致**：条件 = 任务下同编码只有一个 EXPORT/UPLOAD 行；证据 `FileController.java:281-315`，测试 `FileDownloadByteIdentityTest`（真 H2/真存储）。

### F1｜AI 面没有身份/鉴权：sessionId/runId 由调用方声明，跨会话读写与危险工具放行无主体约束
- **severity**：Important（**条件升级**：若部署到非受信网络或多人共用同一实例 → Critical）
- **确定性**：已证实（代码与测试注释均为事实，非推断）
- **path:line**：`backend/pom.xml:45-128`（无 security starter）；`ai/web/AiController.java:115`（类级 `/api/ai`）、`:524`（confirm）、`:590`（frontend-tool-result）、`:855`（cancel）、`:974`（runs/current）、`:1251`（history）、`:1284`（fullHistory）、`:1550`（context）、`:1933/1984`（feedback）；`ai/session/AiSessionPurgeController.java:78`（DELETE session）；`ai/gate/ContextFieldGate.java:79-100`（page 只做白名单归一，不是权限）；`config/Dc10ExposureSurfaceTest.java:15-33`（测试自认“这两个面无认证”）；`docs/02-需求设计文档-v2.2-冻结版.md` §DC-10（设计上移除认证，边界=内网受信）。
- **触发步骤（任一可访问端口的客户端）**：
  1) `GET /api/ai/runs/current?sessionId=<他人/已知 sid>` 拿到 `runId` 与 `pendingEntries[].toolCallId`；
  2) `POST /api/ai/confirm {"runId":…,"toolCallId":…,"approved":true}` 放行 `start_import`/`start_publish`（P 类不可撤销写）；
  3) `GET /api/ai/history/<sid>`、`/history/<sid>/messages`、`/context/<sid>` 读全部对话与上下文；
  4) `DELETE /api/ai/session/<sid>` 物理删除他人历史与 Redis 运行态；`POST /api/ai/cancel/<runId>` 打断他人轮次。
- **用户影响**：跨用户数据泄露 + 未授权写操作（导入/发布到生产数据）+ 会话被销毁；在“多用户隔离”前提下直接违反隔离要求。
- **对应测试/缺失**：无鉴权测试；`Dc10ExposureSurfaceTest` 只锁端点路径不锁身份。**缺失**：任何“非发起者不能 confirm/purge/read”的负向用例（当前也不可能有，因为无主体）。
- **建议归属**：产品裁决（DC-10）范围确认；若坚持 demo 单用户，应把“仅监听 127.0.0.1 / 反代强制鉴权 / 网络隔离”写成部署硬门禁。

### F2｜同一批多张前端卡时，第一张回灌后 `suspended` 被无条件置 false，最后一张卡提交后不再重连 → 终帧/回答只进归档，界面停在流式光标
- **severity**：Important（与 F2b 同源：多张前端卡的交互/连接状态未按“每卡”建模）
- **确定性**：具体风险（静态代码路径已证实；未在本机跑真实拓扑复现）
- **path:line**：`frontend/src/stores/ai.ts:5090`（`frontend_tool_result` case 无条件 `this.suspended = false`）；`:2461-2463`（`reconnectSuspendedRun` 以 `if (!this.suspended) return` 为前置）；`:3211`（`submitFrontendToolResult` 成功后才调它）；后端配合条件 `backend/.../AiController.java:766-769`（park 且仍等人时 `completeSubscriptions()` 主动收流）。
- **触发步骤（两种可达形态）**：
  1) 一轮内两张**即时执行类**前端工具（如 `download_export_files` + `navigate_to`）：第 1 张回灌的 `frontend_tool_result` 帧先到 → `suspended=false`；第 2 张执行较慢，其回灌后 `reconnectSuspendedRun` 直接返回，不建连接；
  2) 一轮内一张表格写预览 + 一张表单（或上传交接 + 表单）：两者都是 `kind=FRONTEND`，第 1 张提交后 `suspended=false`，第 2 张提交不再重连。
  共同点：后端在“仍有 PENDING 的 FRONTEND 条目”时 `events` 会主动收流（`AiController:766-769`），所以第 1 张之后的连接不保活。
- **用户影响**：最后一张卡显示“已提交”，输入区仍禁（`pendingToolCall` 非空），气泡保持流式光标，**回答不出现**；只有刷新（走 C08/历史）才看到完整答案。控制台无错误，用户只会认为“AI 卡住了”。
- **对应测试/缺失**：`restore-orphan-null-req.spec.ts:132` 只覆盖**快照恢复**的多卡串行（CONFIRM，走 `clearSuspendedIfNoOtherConfirms` 保留 suspended）；`MultiCardParkResumeTest` 只测后端续跑；**没有**“两个 live 前端卡 + 第一张回灌后第二张仍待决仍会重连”的前端用例。建议补：两卡 live 帧 → 提交第 1 → 断言 `suspended` 仍真/或 `reconnectSuspendedRun` 不依赖 `suspended` → 提交第 2 → 断言 `reattachRun` 被调用且终帧落地。

### F2b｜同批多张“人机等待”前端卡共用单例 `activeForm`/`activePreview`/`activeUploadHandoff`：只有最后一张可交互，先到的卡既不能提交也不能取消
- **severity**：Important（比 F2 更确定：不依赖时序，纯状态建模）
- **确定性**：具体风险（静态代码路径已证实；未跑真模型复现“一轮两个 generative_form”）
- **path:line**：`stores/ai.ts:3328`（`openGenerativeForm` 无条件 `this.activeForm = {...}`，后到覆盖先到）；`:3371`（`openWritePreview` 同理写 `activePreview`）；`:3479`（`openUploadHandoff` 写 `activeUploadHandoff`）；`components/AiPanel/AiPanel.vue:613`（`FormRenderer` 仅当 `liveForm.toolCallId === part.toolCallId` 时渲染，而 `liveForm` 来自唯一 `activeForm`，见 `:1811-1817`）；同文件 `:632-643`（`SheetPreviewCard` 用唯一 `activePreview`）、`:645-656`（上传交接单例）。后端**没有**“一轮只允许一个 generative_form/表格预览”的限制（`SpToolCallingManager` 按批处理全部 `pendings`）。
- **触发步骤**：
  1) 模型在一轮里返回两个 `generative_form`（或 `generative_form` + `replace_values`）；
  2) 两个 `frontend_tool_request` 帧到达，前端两次调用 `openGenerativeForm`，`activeForm` 只剩第二个；第一个段 `requiresAction=true`，但对应卡里没有 `FormRenderer`（`liveForm.toolCallId` 不等）；
  3) 用户只能填/交第二张；第一张既没有填写入口，也没有取消按钮（取消按钮在 `FormRenderer` 内，见 `onFormCancel`）；
  4) 后端因第一张仍 PENDING，续跑被判 `PENDING_UNRESOLVED`；整轮无法收尾。
- **用户影响**：卡在“有一张没有按钮的表单卡”上，输入区禁用，只能点“停止”放弃整轮（已产生的正文/工具结果保留）。用户无法通过界面完成这一轮。
- **对应测试/缺失**：无“一轮两张 GF/两张人机卡”的前端或 E2E 用例；`restoreSuspendedSnapshot` 的串行队列只覆盖**恢复**路径（`restore-orphan-null-req.spec.ts:132`），实时帧路径没有同样的串行化。建议：`frontend_tool_request` 的人机等待类卡要么进 `restoredPendingQueue` 同款串行队列，要么给 `activeForm/activePreview/activeUploadHandoff` 建“按 toolCallId 的多实例”结构。

### F3｜生产路径 park=true，但单测缺省 park=false：2,000+ 单测大多验证的是“阻塞等待老路径”，绿不等于生产控制流正确
- **severity**：Important（测试可信度/假绿风险；不是产品缺陷）
- **确定性**：已证实（配置事实）
- **path:line**：`backend/src/main/resources/application.yml:250-267`（`park.enabled: true`，注释明说“代码缺省 false、本文件 true 是刻意不一致”）；`ai/config/AiProperties.java:848-855`（`private boolean enabled = false`）；仅 7 个测试文件 `getPark().setEnabled(true)`（见 §5.2-1）。
- **触发步骤**：任何依赖“单测全绿”结论去放行 AI 门/工具改动；实际运行形态是 park（请求帧提前公布、线程交还、决策后重建执行），单测多数走阻塞等待。
- **用户影响**：不是直接用户可见缺陷；风险是“改坏了 park 路径仍全绿”，上线后才在真机暴露（历史上 G-01 帧序缺陷即此类）。
- **对应测试/缺失**：park 相关真实覆盖仅 `ParkToolStartOrderConformanceTest`、`HumanWaitParkTest`、`ParkedFrontendExpiryTest`、`ResumeDispatchRetryTest`、`AiControllerParkWiringTest`、`AiControllerEventsParkCloseTest`、`FrontendGuardPreSuspendTest` + CI 的 165 例脚本 + 手工 Playwright/cluster3。**缺失**：一个 `@SpringBootTest` 级别、park=true、真 Redis 的“chat→挂起→决策→续跑→done”整链用例（当前最接近的是 `verify-e2e.ps1`，不入单测）。

### F4｜优雅停机不收敛 AI 运行面：`aiRunExecutor` 直接 `shutdownNow`（无终态写），且 `/chat` 不检查 draining；drain-window 期间仍可能接受并立刻被杀的新轮
- **severity**：Important（多实例滚动重启的常态操作）
- **确定性**：具体风险（代码事实 + Spring `ThreadPoolTaskExecutor.shutdown()` 在 `waitForTasksToCompleteOnShutdown=false` 时走 `shutdownNow` 的语义；未在本机用 SIGTERM 复现）
- **path:line**：`ai/exec/AiExecutorConfig.java:43-54`（`@Bean(destroyMethod="shutdown")` + `setWaitForTasksToCompleteOnShutdown(false)`）；`AiController.java:299-488`（`/chat` 无 `ReadinessService.isDraining()` 判断）；`ResilientChatService.java:473-477`（worker 的 `finally` 只做 unregister/clear，不写终态）；`health/ShutdownDrainGate.java:60-86`（draining 窗口内连接器仍收新连接）。
- **触发步骤**：滚动重启/发布时 SIGTERM 一个实例，且该实例上有一轮正在流式输出（或 drain-window 5s 内有新 `/chat` 被路由进来）→ `aiRunExecutor.shutdownNow()` 中断 worker → 快照停在 RUNNING（`finish()` 未被调用；若异常穿到 `AiController` 的 catch，`out.error` 可能写进归档，但客户端连接已随进程消失） → 前端重挂 4 次（约 15s）用尽 → 提示“结果可能不完整”；该轮要等 ≥180s 僵尸窗口（另一实例或重启后的 startup scan）才被接管。
- **用户影响**：发布窗口内的对话被硬切断，用户需刷新/重问；若该轮已有工具副作用，恢复语义依赖台账/认领（可证明恰一次），但用户观感是“突然失败”。
- **对应测试/缺失**：`ReadinessDrainWindowTest` 只证明 `/health/ready` 的 503 窗口，不覆盖 AI 执行器；**缺失**：`/chat` 在 draining 时返回 503（或明确接受并保证有界收尾）的用例；`aiRunExecutor` 关闭时对在飞轮写 `error`/保持可恢复快照的用例。

### F5｜`GET /api/ai/history/{sessionId}` 在 Redis 故障时返回 500（而 `/context` 同族返回 503）
- **severity**：Minor
- **确定性**：具体风险（代码路径已证实：无捕获、无异常映射；未运行验证）
- **path:line**：`AiController.java:1256`（`this.chatMemory.get(sessionId)` 未包 try）；`ai/memory/RedisChatMemoryRepository.java:281-291`（`opsForList().range` 直抛）；`AiConfig` 的 `ChatMemory` 是裸 `MessageWindowChatMemory`（`ai/config/AiModelConfig.java:69-75`）；`common/GlobalExceptionHandler.java:198-208/320-336`（无 `RedisConnectionFailureException`/`DataAccessException` 映射 → `handleGeneral` 500）。
- **触发**：Redis 短暂不可用/超时，用户刷新页面 → `loadHistory` 请求 `/history` → 500。
- **用户影响**：前端 `loadHistory` catch 后保留镜像（降级，不白屏），但错误语义误导排障（503 可重试 vs 500 不可重试），且与 `/context`、`/chat`、`/resume` 的 503 口径不一致。
- **对应测试/缺失**：`AiControllerEventsStateUnavailableTest`、`AiControllerConfirmStateUnavailableTest` 等锁了别的端点 503；**`/history` 无 Redis 失败用例**。建议补：`chatMemory.get` 抛 `RedisConnectionFailureException` → 503 `AI_REDIS_UNAVAILABLE`。

### F6｜`POST /api/ai/cancel/{runId}` 恒回 200 `cancelled:true`，即使 Redis 权威写失败；跨实例轮次会继续跑
- **severity**：Minor（依赖 Redis 写失败 + 轮次由另一实例驱动两个条件）
- **确定性**：具体风险（代码事实）
- **path:line**：`AiController.java:855-882`（`body.put("cancelled", true)` 无条件，`:862`；`redisFlagWritten`/`degraded` 只作附加字段）；`ai/run/CancellationRegistry.java:147-154`（`cancel` 先本地置位再写 Redis）；`:286-300`（`writeRedisFlag` 失败只 WARN + `degraded=true`）；前端 `stores/ai.ts:4390-4401` 只读 `alreadyCancelled`，不读 `redisFlagWritten`。
- **触发**：Redis 单次命令超时/连接抖动，用户点“停止”，驱动该轮的实例不是处理 cancel 的实例 → 本地只停了 UI，另一实例继续消费上游、继续执行后续工具（若有）。
- **用户影响**：UI 显示“已停止”，服务端实际继续生成/执行；token 继续消耗；对写类工具是安全控制失效（虽然单次工具仍需确认，但“已放行未执行”的后续会继续）。
- **对应测试/缺失**：`CancellationRegistryFailClosedTest` 覆盖“读失败=UNKNOWN”，**未覆盖“写失败时的 HTTP 语义/前端是否提示”**。建议：`redisFlagWritten=false` 时返回 202/207 或 `cancelled:false`（或前端至少提示“停止可能未生效”）。

### F7｜`POST /api/ai/feedback` 跨实例并发首写撞唯一键 → 500（未捕获冲突）
- **severity**：Minor
- **确定性**：具体风险（唯一约束 + 无重试的读-判-写）
- **path:line**：`AiController.java:1954-1976`（`findByMessageId` → `new AiFeedback` → `save`）；`db/migration/V3__ai_feedback.sql`（`uq_ai_feedback_message UNIQUE (message_id)`）；`GlobalExceptionHandler.java:292-315`（唯一键不属外键 → 500）。
- **触发**：同一 `messageId` 的两个并发请求（两个页签/接管页签 + 原页签、或用户双击）同时走“不存在 → 插入”。
- **用户影响**：其中一个 500，前端 `submitFeedback` 返回 false 并回退选中态；数据本身不脏（唯一键保护），但用户体验为“点赞偶尔失败”。
- **对应测试/缺失**：`AiControllerFeedbackTest` 用 mock 仓储，不覆盖并发；**缺失**：唯一键冲突 → 读后重试或 409 的用例。

### F8｜刷新恢复的增量锚点用 `archiveMaxSeq`：镜像 flush 失效时（崩溃/强杀/配额）存在不可补的 delta 缺口
- **severity**：Minor（**已大幅缓解**：正常刷新/切后台有 flush，见下）
- **确定性**：具体风险（残留窗口；未复现）
- **path:line**：`stores/ai.ts:2136`（`resumeSnapshotStream(current.runId, current.archiveMaxSeq, host.id)`，只收 `seq > archiveMaxSeq`）；`:1475-1520`（镜像节流 300ms，但 **`pagehide` + `visibilitychange(hidden)` 会立即 flush**）；`:851`（写 sessionStorage）；`:193`（`MIRROR_THROTTLE_MS=300`）。
- **触发**：镜像 flush **没有执行或失败**时（浏览器崩溃、进程被强杀、隐私模式/配额导致 `setItem` 抛错被 catch），本地正文落后于 `archiveMaxSeq`；恢复只补新帧，落后部分的 delta 不会再发。
- **用户影响**：该条回答少最后一截（续跑后的新正文接在后面）；刷新一次走历史 DB 可恢复。**正常刷新（pagehide 触发）不成立**，因此这是低概率残留，不应作为主要缺陷计权。
- **对应测试/缺失**：`aie2e-refresh*` 覆盖镜像/快照/帧三件套；**没有**“flush 失败 + 落后帧”的用例。建议：SUSPENDED 恢复改用 `min(镜像 runSeq, archiveMaxSeq)` 作锚点（`markApplied` 已具备 seq 去重，重复回放安全）。
- **更正说明**：本报告初稿曾把“300ms 节流”当作正常刷新的缺口；核对 `startMirror` 的 pagehide/visibilitychange flush 后，该结论只对“flush 未执行”成立。

### F9｜文档漂移（3 处，已证实代码与注释相反）
- **severity**：Minor
- **确定性**：已证实
- **path:line**：
  1) `ai/run/FrameWakeListener.java:24-26` 仍写“水位是**每写出器（每 run）一个，不是每订阅者一个**……写成‘每订阅者一个水位’会引来不必要的按连接计数”，而补丁后的 `SseChatEmitter` 已改为**每订阅者 `wakeFloor`**（`SseChatEmitter.java:53/2165-2168`）。
  2) `ai/exec/AiExecutorConfig.java:16-28` 仍写“一轮对话占用一条线程直至终帧——挂起等待就是在这一条线程上阻塞的；池容量=挂起并发上限”，而生产 `park=true` 下挂起会交还线程（`ConfirmGate.java:592-622`），挂起池不再是“等人并发上限”。
  3) `frontend/src/types/sse.ts:157-160` 仍把 `reasoning` 描述为“后端当前不产出，故不在白名单”，而后端 L-91 已改为发 `delta{kind:'reasoning'}`（`SseChatEmitter.java:922-955`）——该描述本身没错（只是解释旧帧兼容），但与“思考段”现状并列时易误读，建议同步措辞。
- **用户影响**：无直接用户影响；误导后续维护者按旧口径做容量规划或去重实现（F3 的 park 误判同源）。

### F10｜KB HTTP 源无地址白名单且跟随重定向（配置面 SSRF 邻接风险）
- **severity**：Minor（配置面等同管理员权限；不是普通用户输入）
- **确定性**：具体风险（代码事实）
- **path:line**：`ai/kb/HttpKbSource.java:71-73`（`followRedirects(HttpClient.Redirect.NORMAL)`）；`:37-40`（baseUrl 直接来自配置，无 `AiEndpointGuard` 校验）；对照写路径有 SSRF 闸 `AiEndpointGuard.java:64-113`（但仅 provider baseUrl）。
- **触发**：运维把 `app.ai.kb.http.base-url` 指向外部地址，或该地址 302 到内网/元数据地址 → 服务端代为抓取；抓到的内容会进模型上下文（间接提示词注入面，已有 `<knowledge_catalog>`/定界防护）。
- **用户影响**：单用户/内网部署下风险有限；多人/出网部署时应与 provider 写路径同闸。

### 6.1 已裁决、不列为缺陷（避免误报）
- **park 前置 `tool_start` 让混合批次里排在门卡后的后端工具提前显示 `running`**：`SpToolCallingManager.java:483-494` 明确裁决“公告/已排期事件，不是已执行证明”，续跑只结算不重发；不是缺陷。
- **确认门无超时 / generative_form 无本地时限**：用户裁决（2026-10-20），代码与测试一致。
- **两张表单的“串行呈现”在快照恢复路径只建第一张**：`revealNextRestoredPendingCard` 是有意设计（一次一张），不是缺陷；但**实时帧路径没有同款队列**（见 F2b），两者不可混为一谈。
- **`/api/ai/health` 在无 key 时 503 但 body 仍完整**：`AiController.java:1660-1671` 是既定契约，前端 `health()` 专门把 503 当正常体解析（`api/ai.ts:168-180`）。

---

## 7. 覆盖缺口（**显式列出，未悄悄略过**）

### 7.1 本报告未逐行审的生产文件（按体量排序；括号=应重点看什么）

| 文件 | 行数 | 未覆盖点（建议交给谁） |
|---|---|---|
| `frontend/src/stores/ai.ts` 未读段（1–240、660–1590、3400–4190、4800–4930） | ~2,400 | 镜像迁移/蒸馏、`deriveProjections` 调用点、表单单卡状态、`openUploadHandoff` 细节、`resetSession` 竞态（D5） |
| `frontend/src/components/AiPanel/AiPanel.vue` | 3,700 | 渲染模板/工具栏/拖拽/mermaid 卡/滚动跟随/键盘；只读了 composer 禁用与停止按钮（D5） |
| `backend/.../ai/run/RunStore.java` 未读段 | ~2,300 | 快照版本兼容、claim/decided/ledger/idem 的具体 Lua、索引 prune、TTL 续期、`sessionRunIndex` 回填（D3） |
| `backend/.../ai/run/ResilientChatService.java` 未读段 | ~1,900 | 压缩装配、retry/backoff、watchdog 段、`contentFiltered`/`emptyReply`、`convergeMemory`、reasoning 回放（D1） |
| `backend/.../ai/config/AiConfigService.java` + `AiConfigWriter.java` | 2,027 | 版本一致性、事务边界、审计 diff、key 轮换、探活写回（D4） |
| `backend/.../ai/gate/ConfirmGate.java` 未读段 | ~1,200 | `submitFrontendResult` 全分支、`scheduleParkedFrontendExpiry`、`convergeOnDecided` 细节、租约续期（D2） |
| `backend/.../ai/run/LeaseService.java` | 1,146 | 三键脚本、epoch/fence、`markDriving` 存活判据、降级三态（D3） |
| `backend/.../ai/session/AiSessionPurgeService.java` | 1,011 | 枚举/回滚/`deferred`/重试队列/守卫保留期（D3） |
| `frontend/src/utils/sheet-tools.ts` | 2,918 | 读/写/聚合/撤销/预览档位/条件校验（D6） |
| `frontend/src/components/AiPanel/FormRenderer.vue` | 791 | 联动/校验/附件/只读态的渲染细节（D5/D6） |
| `backend/.../ai/kb/KbService.java` | 943 | 增量索引/缓存穿透/多实例并发播种（D2/D3） |
| `backend/.../ai/context/*`（Compactor/RollingSummarizer/MemoryWindow/TurnBudgetPlan） | ~1,600 | 压缩触发/熔断/配对/预算（D1/D3） |
| `backend/.../ai/form/GenerativeFormRules.java` | 1,486 | schema 白名单/批量上限/原型污染（D2） |
| `frontend/src/utils/markdown.ts` 的 partial 缓存与 mermaid | ~700 | 流式前缀复用、SVG 净化、DoS 上限（D5） |
| `frontend/src/utils/frontend-tools.ts` 未读段 | ~900 | 工作区动作白名单/参数归一/去重槽/页面就绪探测（D6） |
| `scripts/verify-e2e.ps1` 165 例本体 | 376,909 字符 | 只读了头/尾与判定口径；未逐例核断言强度（D1/D2/D3） |
| `scripts/ci/stub-upstream.mjs` | 119,691 字符 | 只核了“确定性桩”定位；未审路由/帧生成是否掩盖产品缺陷（D1/D2） |

### 7.2 测试未覆盖的具体分支（代码可达、用例缺失）

1. **F2/F2b**：两张 live 前端卡 → 第一张回灌后第二张提交仍重连；两张人机等待卡（GF/表格预览/上传）只有最后一张可交互（均无用例）。
2. **F4**：`/chat` 在 `draining=true` 时的行为（接受/拒绝）；`aiRunExecutor.shutdownNow` 对在飞轮的终态/恢复（无用例）。
3. **F5**：`/history/{sid}` 的 Redis 失败 → 503（无用例）。
4. **F6**：`/cancel` 在 `writeRedisFlag=false` 时的响应语义与前端提示（无用例）。
5. **F7**：`/feedback` 并发唯一键冲突（无用例）。
6. **F8**：镜像落后 + `archiveMaxSeq` 的丢帧窗口（无用例）。
7. **`POST /api/ai/frontend-tool-result`** 的 `cancelled=true` 与 `result` 同时存在、`outcome` 非法值、`sheetMeta` 超长/非白名单键的入口归一（部分由 `FrontendToolResultCodeTest`/`AiControllerSheetMetaTest` 覆盖，未见组合矩阵）。
8. **`GET /api/ai/events/{runId}?lastSeq`** 的 `lastSeq > 末号`、`lastSeq` 负值归一（`SSE_PATHS.events` 会 `Math.max(0,…)`，后端 `stageReplay` 也归一；未见对“荒谬 lastSeq”的显式用例，`SseChatEmitterWakeFloorTest` 覆盖了尾部 0 帧场景）。
9. **`/chat` 池饱和**已有用例（`AiControllerPreStreamLocaleTest:284-322`：mock executor 抛 `RejectedExecutionException`，断言 503/`SUSPEND_POOL_SATURATED`/中英文案），**不是缺口**；仍缺的是“池饱和发生在会话门 acquire 之后 → `sessionGate.release` + `runRegistry.close` 确实执行”的断言（代码在 `AiController:477-483`，无用例直接验证锁已释放）。
10. **`/api/ai/health` 的 `security`/`sheet`/`jobWatchdog`/`sseDelivery` 字段缺省/装配分支**：`AiControllerHealth*` 三个用例覆盖部分，未见“字段缺席与全 0 可区分”的矩阵。
11. **purge 与“在飞轮”竞态**：`AiSessionPurgeServiceTest` 覆盖 deferred，但“销毁后 worker 仍尝试 save 快照/写帧”的竞态未见（记忆/历史有守卫，RunStore 写没有守卫）。
12. **`/api/ai/runs/{runId}/resume` 的 409 家族**（LEASE_HELD_ELSEWHERE/IN_PROGRESS/ALREADY_RUNNING/PROVIDER_GONE）有单测，但未见**跨实例**真实并发（只有替身）。

### 7.3 自动化测试完全未覆盖的拓扑/行为

- **≥3 JVM 无粘滞 + Redis Cluster**：仅手工 `cluster-3node.yml`；PR 门禁无。
- **真实 Redis 5.0.14 / 7.x 的 Lua 语义与 Stream 截断**：替身 + 手工 smoke。
- **真实模型行为**（工具调用顺序、表单服从率、reasoning 帧、内容过滤）：仅手工 `e2e-real.yml` 采样（`E2E_REAL_SAMPLING` 默认 10）。
- **多用户/多主体隔离**：无（产品无主体，见 F1）。
- **多页签真浏览器 Web Locks**：单测用替身/缺失环境；`tab-ownership-lifecycle.spec.ts` 是 jsdom 逻辑测试。
- **浏览器文件选择器/下载**：`ai-upload-handoff` 单测 mock；真浏览器只在手工 Playwright。
- **代理空闲超时 60s 与前端 45s 的错层**：无自动化（需要真实反代）。

### 7.4 补丁自带的覆盖缺口

- `ParkToolStartOrderConformanceTest` 只覆盖“park=true + 2 张 frontend 卡 + 真 ResumeService”，未覆盖：混合批（backend+confirm+backend）、`frontendRejected`/`preflightBlocked` 与 pre-emit 的组合、3 卡以上。
- `SseChatEmitterWakeFloorTest` 覆盖 3 个场景，未覆盖：两个订阅者都带不同 `lastSeq` + 同时唤醒 + 队列满摘除；`onWake` 与 `emit` 并发（只有注释说明同锁）。
- `ReadinessDrainWindowTest` 不绑端口（连接器用监听器替身），未覆盖真实 Tomcat `connector.pause()` 顺序；`ShutdownDrainGate` 的 `InterruptedException` 分支未覆盖。
- `FileDownloadByteIdentityTest` 未覆盖：同编码同时存在 EXPORT 与 UPLOAD、UPLOAD 非 xlsx（本应用固定 `.xlsx`，属“不可能分支”但未显式断言）、`codes` 重复/顺序。
- `JobWatchdogItemConvergenceTest` 未覆盖：`COMPLETED` 作业残留 RUNNING 条目的“不臆断”分支、CANCELLED 与 FAILED 并发、多实例同时收敛。

---

## 8. 建议的审查域分工（供最终覆盖对账）

| 域 | 必读链 | 必读文件（最小集） | 必核测试 | 本报告已给的风险 |
|---|---|---|---|---|
| **D1 流与帧运行面** | C01/C02/C08/C09/C10 | `ResilientChatService`、`SseChatEmitter`、`StreamWatchdog`、`StreamViolationException`、`UpstreamErrorClassifier`、`FrameSizeLimiter`、`FrameStore/RedisFrameStore`、`FrameWakeListener/Bus`、前端 `api/sse.ts`、`parts.ts`、`stores/ai.ts` 帧分支 | `SseFrameSequenceConformance`、`ChatTurnFrameSequenceConformance`、`ReattachLastSeqConformance`、`SseChatEmitter*`（11）+`SseFrameSizeLimitTest`、`sse-*`（4） | F2、F8、F9-1 |
| **D2 门/工具/表单/表格** | C02/C03/C04/C05/C06/C14/C15 | `ConfirmGate`、`SpToolCallingManager`、`ToolRegistry`、`FrontendToolGuard`、`ToolPreflightService`、`ToolTargetResolver`、`ToolResourceLocks`、`GenerativeFormRules`、`SheetWriteGuard`、`KbTools/KbService`、前端 `frontend-tools.ts`、`sheet-tools.ts`、`FormRenderer.vue` | `ConfirmGate*`（11）、`SpToolCallingManager*`（7）、`GenerativeForm*`（8）、`Kb*`（22）、`sheet-*`（19）、`gf-*`（5） | F3（park 覆盖）、F2（前端卡重连）、F7（反馈是 D3） |
| **D3 会话/历史/多实例** | C08/C11/C12/C18 | `SessionGate`、`LeaseService`、`RunStore`、`AiSessionPurgeService`、`SessionPurgeGuard`、`AiChatHistoryService/Recorder`、`RedisChatMemoryRepository`、`RedisClusterScanSupport`、`CancellationRegistry` | `SessionGateLeaseTokenTest`、`ClusterSlotTest`、`RunStore*`（20）、`AiSessionPurgeServiceTest`、`AiChatHistory*`、`Redis*`（5） | F5、F6、F7、F8、F1（身份面） |
| **D4 配置/密钥/健康/停机** | C13/C18 | `AiConfigService/Writer`、`AiProviderRegistry`、`AiKeyCipher`、`AiEndpointGuard`、`AiModelCatalog`、`AiConnectionProbe`、`ReadinessService/Properties`、`ShutdownDrainGate`、`HealthController`、`application*.yml` 的 ai/health 段 | `AiConfigWriterTest`、`AiProviderRegistry*`、`AiKeyCipherTest`、`AiEndpointGuardTest`、`ReadinessServiceTest`、`ReadinessDrainWindowTest` | F4、F10、F9-2 |
| **D5 前端对话** | C01/C03/C05/C09/C10/C12 | `AiPanel.vue`、`ContextPill/Popover`、`stores/ai.ts`、`types/sse.ts`、`markdown.ts`、`blocks.ts`、`tool-summaries.ts`、`error-card.ts`、`confirm-irreversible.ts`、`context-usage.ts`、`form-draft.ts`、`AiSettingsView`、`AiFeedbackView` | 72 个面板/帧 spec（含 `aie2e-*` 7 个） | F2、F8、F9-3 |
| **D6 前端业务联动** | C04/C05/C06/C07/C15/C16 | `frontend-tools.ts`、`ai-dom-tools.ts`、`ai-regions.ts`、`sheet-tools.ts`、`resultview.ts`、`stores/workspace.ts`、`job-watch.ts`、`polling.ts`、`api/tasks.ts`、向导页交界 | `ai-dom-*`（8）、`ai-region*`（4）、`sheet-tools-*`（9）、`workspace-*`（2）、`watch-publish-*`、`job-watch-channel` | C16 的补丁已单独列；F2 的前端侧 |

### 8.1 一句话结论（供指挥官）

- **覆盖**：AI 生产代码 196 文件 / 50,180 行已全部**文件级清点**并归入 6 类；18 条端到端链已给出入口/身份/共享状态/分支/恢复/用户可见的分析；327 个 AI 测试文件已全部列出并分域；CI/E2E 的真实覆盖面已按“证明什么/不证明什么”拆开。
- **最高优先级**：F1（无鉴权，决定产品边界）、F2b（同批多张人机卡只有最后一张可交互，整轮无法收尾）、F2（多张前端卡的最后一张不回连，回答不出现）、F3（park 单测覆盖错位，绿≠生产）、F4（滚动重启不收敛 AI 运行面）。
- **确定性边界**：F1 是已证实代码事实；F4/F5/F6/F7/F8 是已证实的代码路径/风险，未在本机真实拓扑复现；F3 是测试配置事实；F9 是注释与代码矛盾。**本报告不对任何“绝对正确/绝对安全”作保证。**

---

## 9. 复核命令（只读，供下一位审计员快速对齐）

```bash
# 1) 快照与补丁
cd <仓外证据>/ai-full-audit && cat base-sha.txt tree-status.txt
sha256sum working.patch

# 2) AI 文件全量（应与本报告 §1.2/§1.3 的 196 文件一致）
cd tree/backend/src/main/java/com/example/configmgr/ai && find . -name '*.java' | wc -l

# 3) AI 测试全量（327 文件 / 2063 例）
cd tree/backend/src/test/java/com/example/configmgr/ai && find . -name '*.java' | wc -l
grep -rn '@Test\|@ParameterizedTest' . | wc -l

# 4) park 覆盖错位（应只列出 7 个文件）
cd tree/backend/src/test/java && grep -rln 'getPark().setEnabled(true)' .

# 5) 生产 park 开关
grep -n 'park:' -A3 tree/backend/src/main/resources/application.yml
```

> 说明：以上命令均为只读；本报告未执行任何服务启动、真实拓扑、密钥读取或仓内写入。
