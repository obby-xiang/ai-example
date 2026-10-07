# S5.a 核验：glm-5.3 分支 JUnit 测试移植扩充（main-v2 基座）

- **施工目标（主仓库）**：`<MAIN_V2>`（= `ai-example-code/ai-example-main-v2`，分支 `main-v2`，后端在 `backend/`）
- **分支 / HEAD**：`main-v2` / `e247bc3`（施工前 HEAD，无 git 写操作）
- **移植来源（只读参照，全程未修改）**：`<REPO_ROOT>/ai-example-glm-5.3/backend/src/test/`（`<REPO_ROOT>` = 工作区根 `ai-example-code`）
- **核验日期**：2026-10-07
- **核验方式**：真实执行 —— Maven 全量跑基线 → 增量跑移植类 → Maven 全量回归（+ 复跑一次验稳定性）
- **工具链**：JDK 21（PATH）/ Maven `<MAVEN_HOME>` / Spring Boot 3.5.14 / H2 2.x（内存库，`MODE` 默认）/ JUnit 5 + AssertJ
- **外部依赖**：仅只读确认 `127.0.0.1:6379`（Memurai `PING` → `PONG`）；**未启动任何应用端口**（全部 `@SpringBootTest` 默认 MOCK，不起 Tomcat）
- **结论汇总**：
  - A ✅ 基线实测 **74** 个用例全绿（任务书写的"42"与实测不符，见 §1.2 说明）
  - B ✅ 移植 **31** 个用例（glm 源 38 个中 31 个有对应实现，7 个列入"不适用/待裁决"）
  - C ✅ 全量实测 **105** 个用例，`Failures: 0, Errors: 0, Skipped: 0`，`BUILD SUCCESS`
  - D ✅ 复跑一次结果一致（无抖动）；≥80 目标达成（105 ≥ 80）
  - E ✅ 发布语义断言按 **行级 upsert + 范围差集删除** 重写并实测通过（保 id、版本递增、范围内差集删除、范围外零影响）
- **纪律声明**：**未修改 `src/main`（生产代码）任何一行**；未修改 glm-5.3 分支任何文件；未执行任何 `git` 写操作（无 add/commit/push）。`git status --porcelain` 仅 5 个新增测试文件（+ 本文档）。

---

## 一、盘点

### 1.1 移植源：glm-5.3 测试清单（5 个测试类 / 38 个 `@Test`）

| 源文件（`<REPO_ROOT>/ai-example-glm-5.3/backend/src/test/java/com/example/quickstart/...`） | `@Test` 数 | 覆盖职责 |
|---|---|---|
| `BaseIntegrationTest.java` | 0 | 测试基类（`@SpringBootTest` + `test` profile），非用例 |
| `service/ConfigDataServiceTest.java` | 11 | 查询条件引擎（文本/数字/枚举/布尔/范围/多条件 AND/IN + 非法操作符与非法字段） |
| `service/CatalogServiceTest.java` | 5 | 目录与种子数据、任务创建、步骤守卫、任务列表 |
| `service/ExportFlowTest.java` | 4 | 导出全流程（建任务→选配置→条件→执行→结果→终态守卫） |
| `service/ImportFlowTest.java` | 10 | 导入全流程（上传→检查→导入暂存→发布→依赖拓扑） |
| `ai/AiToolsTest.java` | 8 | AI 工具直调（工具行为、步骤守卫、渐进披露、联动事件） |
| **合计** | **38** | |

### 1.2 基线：main-v2 现有测试清单（15 个类 / 74 个 `@Test`，实测全绿）

| 测试类 | 被测面 | 用例数 |
|---|---|---|
| `ai/gate/ConfirmGateWiringTest` | `SpToolCallingManager` + `ConfirmGate`（风险元数据→挂起） | 4 |
| `ai/gate/SpToolCallingManagerScopeGuardTest` | 防线③上下文兜底 | 3 |
| `ai/run/SseChatEmitterFramesTest` | SSE 帧 | 9 |
| `ai/run/UsageDetailsTest` | usage 细节 | 3 |
| `ai/tool/ContextBuilderExtraTest` | 上下文构建 | 5 |
| `ai/tool/ToolResultLimiterTest` | 工具结果截断 | 5 |
| `ai/tools/ToolRegistryDisclosureTest` | 工具注册/渐进披露 | 11 |
| `data/service/DataListConditionTest` | 列表端点 `conditions` + `/count` 同源 | 7 |
| `definition/service/DefinitionDeleteTest` | 定义删除级联 | 6 |
| `definition/service/DefinitionUpdateTest` | 定义更新 | 3 |
| `job/service/JobGovernanceTest` | 作业治理（僵尸恢复/取消/快照事务） | 5 |
| `job/service/JobProgressTotalsTest` | 进度分母 | 3 |
| `job/service/PublishSemanticsTest` | 发布模块行为对照（upsert/差集/冲突/回滚） | 4 |
| `task/controller/TaskConditionJsonTest` | 条件落库 JSON 合法性 | 4 |
| `task/service/TaskCenterQueryTest` | 任务中心检索/排序/转义 | 2 |
| **合计** | | **74** |

> **口径说明**：任务书写"当前后端单元测试 42 个全绿"，**实测为 74 个**（按 `@Test` 注解逐个计数，并以 Surefire 报告汇总复核）。
> 42 与 74 的差异无法用"跳过/参数化/嵌套"解释（无 `@Disabled`、无 `@ParameterizedTest`/`@Nested`：全仓 0 处）。
> 本棒以**实测 74** 为基线记账；无论按 42 还是 74，最终 105 均满足"≥80"。

### 1.3 基线全绿证据（施工前先跑）

```
cd <MAIN_V2>/backend
<MAVEN_HOME>/mvn.cmd -B -q test          # EXIT=0
# Surefire 汇总（逐类相加）
Tests run: 74, Failures: 0, Errors: 0, Skipped: 0
```

### 1.4 被测实现对照（决定"哪些能移植"）

| 关注点 | glm-5.3 | main-v2 | 移植可行性 |
|---|---|---|---|
| 任务模型 | `Task` + 参数包 `params`（会话/步骤自定义） | `Task`（type/currentStep/status）+ `TaskItem`（每个配置项一条） | 部分（语义近，载体不同） |
| 查询条件 | `ConditionDTO.Condition(field, op, value/values)`，含 `BETWEEN`、`__scope` 伪字段 | `QueryCondition(scopeKeys, fields[fieldCode/operator/value])`，`ConditionEvaluator` 求值 | 部分（操作符集合不同） |
| 导出 | `ExportRunner.run(task, session)`（内存结果进 `params.exportResult`） | `ExportJobRunner.run(job)`（写 xlsx + `task_files`） | 是（改断言读取面） |
| 预检查 | `ImportRunner.runCheck`：必填/枚举合法/引用依赖/范围合法 | `PrecheckJobRunner`：必填/主键重复/引用存在性 | 部分（少两类规则） |
| 导入 | `ImportRunner.runImport`：写暂存 | `ImportJobRunner`：读 `task_files(UPLOAD)` 的 xlsx 写暂存 | 是（需真造上传件） |
| 发布 | 范围内**删表重建** | `PublishJobRunner`：**行级 upsert + 范围差集删除**（ADR-7/Q14） | 是（**断言必须按新语义重写**） |
| AI 工具 | `BasicAiTools/ExportAiTools/ImportAiTools`（工具名不同） | `AiTools`（17 个工具，与 REST 同服务层） | 部分（名称/语义能对上的才移） |
| 步骤守卫 | `assertStep/assertSelection/assertNotTerminal` | **无**（步骤引导在前端与上下文构建里） | 否（见 §4/§5） |

---

## 二、移植清单（源 → 目标 → 用例数）

| # | 源（glm-5.3） | 目标（main-v2，新增文件） | 源用例 | 移植 | 跳过 |
|---|---|---|---|---|---|
| 1 | `service/ConfigDataServiceTest`（11） | `backend/src/test/java/com/example/configmgr/data/service/PublishedQueryConditionTest.java` | 11 | 9 | 2 |
| 2 | `service/CatalogServiceTest`（5） | `backend/src/test/java/com/example/configmgr/definition/service/GlmSeedCatalogTest.java` | 5 | 4 | 1 |
| 3 | `service/ExportFlowTest`（4） | `backend/src/test/java/com/example/configmgr/job/service/ExportFlowJobTest.java` | 4 | 3 | 1 |
| 4 | `service/ImportFlowTest`（10） | `backend/src/test/java/com/example/configmgr/job/service/ImportFlowJobTest.java` | 10 | 9 | 1 |
| 5 | `ai/AiToolsTest`（8） | `backend/src/test/java/com/example/configmgr/ai/tools/AiToolsInvocationTest.java` | 8 | 6 | 2 |
| | **合计** | **5 个新测试类** | **38** | **31** | **7** |

（`BaseIntegrationTest` 是测试基类、无用例；main-v2 不用抽象基类，各测试类自带 `@SpringBootTest` + 独立内存库 `@TestPropertySource`，与既有 15 个类同风格。）

### 2.1 逐用例映射

**① `PublishedQueryConditionTest`（9）** — 断言落在 `ConfigDataService#findPagedFiltered/#countFiltered` + `ConditionEvaluator`

| glm 用例 | 目标用例 | 期望值来源（种子实测） |
|---|---|---|
| `noConditionReturnsAll` | `noConditionReturnsAllPublishedRows` | SYS_PARAM 40 行 / METRIC_DICT 30 行（`GlmSeedService` 行密度） |
| `textContains` | `textContainsMatchesEveryRowAndPrefixFamily` | `paramKey` 含 `param.1` → param.1、param.10~19 = **11** 行 |
| `numericBetween` | `numberRangeFiltersPermissionLevel` | `BETWEEN 2..3` → `GTE 2` + `LTE 3`；`permissionLevel = g%5+1` → 4 行 |
| `numericGt` | `numberGreaterThanFiltersBandwidth` | 每地区带宽 100/150/200 → `GT 150` 命中 **4** 行（4 地区各 1） |
| `enumEq` | `enumEqualsFiltersBillingCycle` | `REGION_TARIFF.billingCycle` 周期 `月付/季付/年付` → 全行匹配 |
| `boolEq` | `booleanEqualsFiltersAlarmEnabled` | `alarmEnabled = k%2==0` → 15 行为真 |
| `scopeEqOnRegionLevel` | `scopeKeysFilterRegionRows` | `__scope` → `scopeKeys=["HB"]` |
| `multipleConditionsAnd` | `multipleConditionsAreCombinedWithAnd` | 字段条件 + 范围条件 AND |
| `textIn` | `inOperatorMatchesExactKeys` | `IN [param.1,param.2,param.3]` = 3 行 |
| `invalidOperatorRejected`、`invalidFieldRejected` | — | **未移植**（见 §4） |

**② `GlmSeedCatalogTest`（4）**

| glm 用例 | 目标用例 | 改写要点 |
|---|---|---|
| `seedProvidesEightConfigsAcrossLevels` | `seedProvidesGlmConfigsAcrossThreeLevels` | 断言 glm 8 编码 + 层级 4/2/2，并额外锁定**并集总数 15**（基座 7 + glm 8）；`dependsOn` → REFERENCE 字段；`DECIMAL` → `NUMBER`；`ALARM_THRESHOLD` 120 行 |
| `taskCreatePersistsImmediately` | `taskCreatePersistsImmediately` | `TaskService.create` 落库 + 步骤初值（EXPORT→`SELECT_DEFS`、IMPORT→`UPLOAD`）+ 未选配置无条目 |
| `taskCreateRejectsUnknownType` | `taskCreateRejectsUnknownType` | 类型是枚举，字符串入口只有 REST → MockMvc `POST /api/tasks {"type":"UNKNOWN"}` 断言 **400** |
| `listTasksReturnsCreated` | `listTasksReturnsCreated` | `TaskService#search` 命中新建任务且带 status/currentStep |
| `stepGuardsRejectWrongStep` | — | **未移植**（见 §4） |

**③ `ExportFlowJobTest`（3）**

| glm 用例 | 目标用例 | 改写要点 |
|---|---|---|
| `createAndSelect` | `createAndSelectDefsPersistsItemsAndConditionNeedsSelection` | `assertSelection` 无对应物 → 用 main-v2 真实守卫"条件只对已勾选配置项有效"（`ResourceNotFoundException`）+ `TaskItem` 落库/`READY` |
| `runExportWithConditions` | `exportJobFiltersRowsByConditionAndWritesFiles` | 结果面从 `params.exportResult` 改为 `task_files(EXPORT)` + **用生产 `ExcelReader` 回读 xlsx** 校验内容；行数 11 / 105 / 8 |
| `terminalTaskRejectsFurtherOps` | `completedTaskRejectsJobCancel` | 平等守卫改为终态作业取消 → `ConflictException` 码 `JOB_ALREADY_FINAL` |
| `exportTwiceRejectedWhileRunning` | — | **未移植**（见 §5 待裁决 2） |

**④ `ImportFlowJobTest`（9，`@Order` 顺序流程）**

| glm 用例 | 目标用例 | 改写要点 |
|---|---|---|
| `createAndPrepare` | `createAndPrepareUploads` | `params.uploads`（内存行）→ 真写 xlsx + 真落 `task_files(UPLOAD)`（生产 `ExcelWriter`） |
| `checkDetectsAllRuleTypes` | `checkDetectsAllRuleTypes` | 三类规则改为 main-v2 实际有的：**必填缺失 / 引用不存在 / 主键重复**（原"非法枚举""非法范围"无实现） |
| `fixDataThenCheckPass` | `fixDataThenCheckPass` | 重写上传件 → 新 PRECHECK 作业 → `COMPLETED`/0 错误/无 issue |
| `importStagesDataWithoutTouchingPublished` | `importStagesDataWithoutTouchingPublished` | 暂存行 `STAGED` 且已发布行数不变 |
| `publishReplacesPublishedData` | `publishUpsertsKeepsRowIdsAndDeletesOutOfRangeRows` | **核心语义改写，见 §3.1** |
| `dependentsImportedAfterDependency` | `dependentsImportedAfterDependency` | `ImportRunner.topoOrder` → `DependencyResolver.sort`（METRIC_DICT 先于 ALARM_THRESHOLD、ROLE_DICT 先于 PROJECT_MEMBER） |
| `duplicateKeyDetected` | `duplicateKeyDetected` | 文案 `业务键重复` → `主键重复` |
| `emptyUploadProducesWarning` | `emptyUploadProducesNoError` | main-v2 预检查**无警告语义** → 只保留"空文件不报错"（见 §4） |
| `missingRequiredFieldDetected` | `missingRequiredFieldDetected` | 文案 `必填字段为空` → `必填字段 [参数值] 不能为空` |
| `importRejectedWhileCheckHasError` | — | **未移植**（见 §5 待裁决 1，疑似生产缺口） |

**⑤ `AiToolsInvocationTest`（6）**

| glm 用例 | 目标用例 | 改写要点 |
|---|---|---|
| `listConfigItemsReturnsCatalog` | `listConfigDefsReturnsGlmCatalog` | `list_config_items` → `listConfigDefs`（返回面向模型的文本）；`get_config_item_fields` → `getConfigDef` |
| `noTaskToolsReject` | `workspaceStateHintsWhenNoTask` | 无"没有进行中的任务"异常语义 → 断言提示文本；有任务时快照含标题/步骤/配置项 |
| `createTaskBindsSession` | `createTaskFromAiPersistsTaskAndReportsNextAction` | 会话绑定的副作用在前端 → 断言"工具返回 id + 下一步动作"与库内任务（含非法类型如实回报） |
| `exportToolsProgressiveDisclosure` | `exportToolChainRunsRealJobAndWritesFile` | **真起异步作业**（`start_export`）→ 等终态 → `task_files` 行数 11 + 文件非空；`set_query_conditions`→`TaskService#setCondition`（后端无该工具） |
| `importToolsFlowWithGuards` | `importToolChainRunsPrecheckImportAndPublish` | 前置状态（上传件）由测试准备，**三个作业全部走 AI 工具** `start_precheck/start_import/start_publish`；并覆盖默认 **MERGE = 纯增量 upsert**（已发布只增一行） |
| `wrongConfigCodeRejected` | `unknownConfigCodeIsReported` | 工具不抛异常 → 断言 `未找到配置定义` |
| `selectionMovesToNextStep`、`stepGuardBlocksPublishBeforeImport` | — | **未移植**（见 §4/§5） |

---

## 三、语义改写说明

### 3.1 发布语义（本棒的灵魂）：删表重建 → 行级 upsert + 范围差集删除

glm 的 `publishReplacesPublishedData` 断言的是"**范围内全量替换**"（例如"发布后 SYS_PARAM 行数 = 3"），
其实现是"删掉覆盖范围内的旧行 → 插入导入行"，**行身份（id）不延续**。
main-v2 已定案（ADR-7 / Q14 / DC-06 / DC-11，S4.3 实施）为 **行级 upsert + 范围差集删除**，故该用例按下表重写：

| 断言维度 | glm 原语义（删建） | main-v2 新语义（本棒断言） |
|---|---|---|
| 覆盖到的业务键行 | 旧行被删、新行 id 变更 | **保留行 id**、`version` **递增**、字段值就地更新 |
| 覆盖范围内未导入的旧行 | 一并删除（整段替换） | **差集删除**（业务键未出现即删） |
| 覆盖范围之外的范围 | 通常一并处理/不提 | **零影响**：行 id 与 `dataJson` 逐字节不变 |
| 模式 | 只有"范围内替换" | `REPLACE` 才做差集删除；`MERGE` 为纯增量 upsert |
| 暂存行终态 | 提升为 PUBLISHED | 同（`STAGED → PUBLISHED`） |

实测断言（`ImportFlowJobTest#publishUpsertsKeepsRowIdsAndDeletesOutOfRangeRows`，`ImportFlowJobTest` 用例 5）：
`SYS_PARAM` 40 旧行 → 1 行 upsert 保 id（`"已更新"` 值可见）+ 2 行新增 = 3 行，其余 37 行差集删除；
`ALARM_THRESHOLD` 120 → 2 行；`REGION_NETWORK` 仅 HE 被覆盖 → HE 从 3 行变 1 行（`HE|100` 保 id），
HB/HS/XN 的 3 行**id 与内容零变化**；暂存行全部 `PUBLISHED`；任务 `COMPLETED` + 步骤 `PUBLISH`。

### 3.2 其他断言改写一览

| glm 原断言 | main-v2 实际语义 | 本棒做法 |
|---|---|---|
| `BETWEEN 2 3`（数字区间） | 无 `BETWEEN` 操作符 | 拆成 `GTE 2` + `LTE 3` 两条（AND），断言区间内全行匹配 |
| `__scope = "REGION_NORTH"`（伪字段） | 行内字段 `regionCode`/`projectCode` + `scopeKeys` | `scopeKeys=["HB"]`，断言 `regionCode` 全为 HB |
| 结果读 `params.exportResult`（内存） | 结果落 `task_files(EXPORT)` + 盘上 xlsx | 用生产 `ExcelReader` **回读文件**校验行数与每行条件 |
| 结果读 `params.checkResult`（内存 JSON） | `validation_issues` 表按 `(jobId, defCode)` | 断言 issue 文案 + `job.errorCount` + 条目状态 |
| `params.uploads`（会话内存） | `task_files(UPLOAD)` → `ExcelReader` 解析 | 真写 xlsx + 真落 task_files，走真实导入通道 |
| 种子行数 40/30/120 等 | `GlmSeedService` 同密度但**部分字段值不同** | 逐值实测后调整期望（带宽 100..200 而非 100..750；见 §2.1） |
| 业务键文案"业务键重复" | `主键重复: <rowKey> (第 N 行已存在)` | 断言 `contains("主键重复")` |
| 必填文案"必填字段为空" | `必填字段 [<label>] 不能为空` | 断言 `contains("必填字段")` + 字段标签 |

---

## 四、不适用清单（不移植及原因）

| # | 源用例 | 所属 | 不移植原因 |
|---|---|---|---|
| 1 | `invalidOperatorRejected` | `ConfigDataServiceTest` | main-v2 `ConditionEvaluator` 对**未知操作符**走 `default → true`（静默放行），没有"拒绝并抛 `BizException`"的语义；无等价实现（并见 §5 待裁决 3） |
| 2 | `invalidFieldRejected` | `ConfigDataServiceTest` | 同上：未知字段不抛错，而是逐操作符求得"不匹配/放行"；无等价实现 |
| 3 | `stepGuardsRejectWrongStep` | `CatalogServiceTest` | main-v2 **后端无步骤守卫**（`TaskService#goToStep` 接受任意步骤；步骤引导在前端动作表与 AI 上下文里） |
| 4 | `exportTwiceRejectedWhileRunning` | `ExportFlowTest` | main-v2 无"同任务/同类型作业互斥"守卫：第二次导出会照常起作业并复用同一个 `task_files(task,def,EXPORT)` 行；见 §5 待裁决 2 |
| 5 | `importRejectedWhileCheckHasError` | `ImportFlowTest` | main-v2 `ImportJobRunner` **不检查预检查结果**（预检查的错误只落 issue 与条目状态，不阻断导入）；见 §5 待裁决 1 |
| 6 | `selectionMovesToNextStep` | `AiToolsTest` | 该用例依赖 `set_selected_configs`（后端工具）与"选择即推进步骤"；main-v2 的选择动作是**前端通道哨兵桩**（`select_definitions` 返回 `FRONTEND_STUB`）且选配置**不推进步骤** |
| 7 | `stepGuardBlocksPublishBeforeImport` | `AiToolsTest` | 同 #3：`start_publish` 只校验任务/作业类型，无"必须在 IMPORT 步"的守卫 |
| 补充 | `emptyUploadProducesWarning` 的**警告**断言部分 | `ImportFlowTest` | main-v2 `PrecheckJobRunner` 从不产出 `WARNING` 级 issue（`warningCount` 恒 0），"空文件应产生 ≥1 警告"无对应实现；用例已按"空文件不报错"移植（`emptyUploadProducesNoError`） |

---

## 五、【待裁决】（疑似生产缺口，本棒未改生产代码）

1. **导入缺少"预检查未通过则不得导入/发布"的前置守卫**
   - glm 断言：`ImportRunner.runImport` 在检查有错时抛 `BizException("校验未通过")`。
   - main-v2 实际：`ImportJobRunner`/`PublishJobRunner` 均不读 `validation_issues`，预检查失败后仍可 `start_import`/`start_publish`（只有 `PublishJobRunner` 之外的数据完整性错误才会阻断）。
   - 候选改法：① 在 `ImportJobRunner`/`JobService#createAndStart` 入口校验"该任务最近一次 PRECHECK 作业 errorCount==0"，否则 409/400；② 明确"预检查只是提示，不阻断"，把该行为写进需求文档并在前端给强提示。
2. **同任务重复/并发导出无互斥**
   - glm 断言：导出进行中再发起导出应被拒。
   - main-v2 实际：`JobService#createAndStart` 无互斥；两个导出作业会并发写同一个 `task_files(task,def,EXPORT)`（唯一约束 → 后发者覆盖），进度也互不感知。
   - 候选改法：① 在 `createAndStart` 加"同 task + 同 jobType 存在 PENDING/RUNNING 则 409"；② 承认并发可接受，但把"同键文件覆盖"改为每作业独立文件记录（需要改表）。
3. **条件求值器对未知操作符/未知字段静默放行**
   - glm：非法操作符/字段 → 异常。main-v2：`default → true`（等于"忽略该条件"，导出可能比用户预期**更宽**）。
   - 候选改法：① `ConditionEvaluator` 对未知操作符改为 `false` 或抛 `IllegalArgumentException`（列表端点已有 400 通道，`parseCondition` 会转 400）；② 保持现状但视为"宽容解析"，需在需求文档明确。
4. **AI 工具无步骤守卫**（`start_publish` 可在 CHECK/UPLOAD 步直接调用）
   - 现状：确认门只保证"人工确认"，不保证"前置步骤已完成"；错误顺序由前端动作表兜底。
   - 候选改法：① 在工具的作业发起前校验 `task.currentStep`；② 明确"顺序由前端与用户负责"，在 FR 里写清责任边界。
5. **AI 工具缺少"当前无任务"的错误语义**（glm 抛 `BizException(没有进行中的任务)`，main-v2 工具返回提示文本）
   - 影响：模型侧只能读文本，无法据错误码做分支；是否要统一为异常/错误码，需裁决。

---

## 裁决结论（指挥官 K3，2026-10-07）

| # | 事项 | 裁决 |
|---|------|------|
| 1 | 导入/发布缺"预检查未通过则阻断"守卫 | 采纳——入口校验最近 PRECHECK errorCount==0，列入 M1 收尾守卫补强棒 |
| 2 | 同任务重复/并发导出无互斥 | 采纳——同 task+jobType 有 PENDING/RUNNING 则 409（对齐 DC-11 409 串行化），同棒实施 |
| 3 | 未知操作符静默放行 true | 采纳——改抛 IllegalArgumentException（对齐列表端点 400 通道），同棒实施，优先级最高 |
| 4 | AI 工具无后端步骤守卫 | 不采纳——交互基线为渐进披露、步骤由前端驱动，后端守卫与"一句话驱动向导"模型自由调度冲突，风险由 FR-5.3 确认门覆盖 |
| 5 | AI 工具无"无任务"错误码 | 不采纳——文本提示足够模型澄清，错误码体系属 M2 范畴 |

#1-#3 合并为 M1 收尾守卫补强棒，排期 S5 完成后、M1 出口评审前。

---

## 六、测试运行结果（真实执行）

命令（工作目录 `<MAIN_V2>/backend`，JDK 21 在 PATH）：

```bash
# ① 基线（施工前）
<MAVEN_HOME>/mvn.cmd -B -q test
# ② 增量（移植类）
<MAVEN_HOME>/mvn.cmd -B test -Dtest='PublishedQueryConditionTest,GlmSeedCatalogTest,ExportFlowJobTest' -DfailIfNoSpecifiedTests=false
<MAVEN_HOME>/mvn.cmd -B test -Dtest='ImportFlowJobTest' -DfailIfNoSpecifiedTests=false
<MAVEN_HOME>/mvn.cmd -B test -Dtest='AiToolsInvocationTest' -DfailIfNoSpecifiedTests=false
# ③ 全量回归（跑两次验稳定性）
<MAVEN_HOME>/mvn.cmd -B test
```

| 阶段 | 结果 |
|---|---|
| ① 基线 | `Tests run: 74, Failures: 0, Errors: 0, Skipped: 0`（`EXIT=0`） |
| ② 增量-1（查询条件/目录/导出） | `Tests run: 16, Failures: 0, Errors: 0, Skipped: 0` |
| ② 增量-2（导入全流程） | `Tests run: 9, Failures: 0, Errors: 0, Skipped: 0` |
| ② 增量-3（AI 工具直调） | `Tests run: 6, Failures: 0, Errors: 0, Skipped: 0` |
| ③ 全量（第 1 次） | **`Tests run: 105, Failures: 0, Errors: 0, Skipped: 0` / `BUILD SUCCESS` / `Total time: 01:05 min`** |
| ③ 全量（第 2 次，稳定性复跑） | **`Tests run: 105, Failures: 0, Errors: 0, Skipped: 0` / `BUILD SUCCESS`** |

全量逐类（第 1 次，节选含新增 5 类）：

```
Tests run: 4,  ... ConfirmGateWiringTest
Tests run: 3,  ... SpToolCallingManagerScopeGuardTest
Tests run: 9,  ... SseChatEmitterFramesTest
Tests run: 3,  ... UsageDetailsTest
Tests run: 5,  ... ContextBuilderExtraTest
Tests run: 5,  ... ToolResultLimiterTest
Tests run: 6,  ... AiToolsInvocationTest          <- 新增
Tests run: 11, ... ToolRegistryDisclosureTest
Tests run: 7,  ... DataListConditionTest
Tests run: 9,  ... PublishedQueryConditionTest    <- 新增
Tests run: 6,  ... DefinitionDeleteTest
Tests run: 3,  ... DefinitionUpdateTest
Tests run: 4,  ... GlmSeedCatalogTest             <- 新增
Tests run: 3,  ... ExportFlowJobTest              <- 新增
Tests run: 9,  ... ImportFlowJobTest              <- 新增
Tests run: 5,  ... JobGovernanceTest
Tests run: 3,  ... JobProgressTotalsTest
Tests run: 4,  ... PublishSemanticsTest
Tests run: 4,  ... TaskConditionJsonTest
Tests run: 2,  ... TaskCenterQueryTest
Tests run: 105, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

- **测试总数**：105（基线 74 + 移植 31）
- **通过数**：105（`Failures 0 / Errors 0 / Skipped 0`）
- **≥80 目标**：达成（105）
- **端口纪律**：全部用例在 `@SpringBootTest`（MOCK）下运行，**未启动任何 HTTP 端口**；未 `kill` 任何进程；仅只读 `PING 127.0.0.1:6379`（`PONG`）
- **产物落位**：测试生成的 xlsx 落在 `backend/target/s5a-test-files/`（`target/` 已 gitignore），未污染 `backend/data/`

---

## 七、遇到的问题与处置

| 问题 | 处置 |
|---|---|
| 任务书"基线 42 个"与实测 74 个不符 | 以实测为准记账，并在 §1.2 记录口径与复核方式（无 `@Disabled`/`@ParameterizedTest`/`@Nested`） |
| 两套模型差异大（任务参数包 vs `task_items`/`task_files`；`ConditionDTO` vs `QueryCondition`） | 逐个用例找 main-v2 的同职责实现，改写断言面；找不到对应实现的用例一律不移植并登记（§4） |
| glm 的 `@Test` 计数被 `@TestPropertySource` 干扰（`grep -c "@Test"` 多计 1/类） | 改用 `grep -cE '@Test\s*$'` 复核，并按 Surefire 报告二次校对 |
| `ImportFlowJobTest` 需要真实 xlsx 才能驱动导入链路 | 由生产侧 `ExcelWriter` 写真文件 + 真落 `task_files(UPLOAD)`，读侧由生产 `ExcelReader` 解析（往返闭环，未改生产代码） |
| 导出/预检查/导入执行器在测试线程里的终态写入时机（`afterCommit` / `TransactionTemplate`） | 采用与既有 `PublishSemanticsTest` 相同的"直接同步驱动执行器 + 从库重读作业"手法；AI 工具链用例则真走异步 `@Async` + 轮询终态（30s 上限） |
| AI 工具链用例涉及异步作业，存在抖动风险 | `app.job.demo-batch-delay-ms=0` + 轮询 `findTopByTaskIdAndJobTypeOrderByCreatedAtDesc`；全量复跑一次确认结果一致 |
| 编译期一处类型误用（`List.of(field(...))` 传成 `QueryCondition`） | 已修正为 `fieldCondition(...)`；`mvn test-compile` 通过 |

---

## 八、改动面清单（本棒新增，无修改生产代码）

```
<MAIN_V2>/backend/src/test/java/com/example/configmgr/data/service/PublishedQueryConditionTest.java   (9 用例)
<MAIN_V2>/backend/src/test/java/com/example/configmgr/definition/service/GlmSeedCatalogTest.java      (4 用例)
<MAIN_V2>/backend/src/test/java/com/example/configmgr/job/service/ExportFlowJobTest.java              (3 用例)
<MAIN_V2>/backend/src/test/java/com/example/configmgr/job/service/ImportFlowJobTest.java              (9 用例)
<MAIN_V2>/backend/src/test/java/com/example/configmgr/ai/tools/AiToolsInvocationTest.java             (6 用例)
<MAIN_V2>/docs/evidence/S5a-glm测试移植验证.md                                                        (本文档)
```

`git status --porcelain` 复核：仅上述 5 个测试文件为 `??`（新增未跟踪），**`src/main` 零改动**；未执行 `git add/commit/push`。

---

## 九、遗留与后续建议

1. §5 的 5 项【待裁决】需主线裁决；其中 #1（导入前置守卫）、#3（未知操作符静默放行）建议优先——它们会直接影响"导出范围比预期更宽/未通过校验的数据被发布"这类数据面后果。
2. glm 的"范围合法性校验""枚举合法性校验"在 main-v2 预检查里缺失（`PrecheckJobRunner` 只覆盖必填/主键重复/引用存在性）。若要拉齐，属生产代码改动，需另开任务。
3. glm 的 `emptyUploadProducesWarning` 所依赖的"警告"语义（`Severity.WARNING` + `warningCount`）在预检查路径从未产出，`Job.warningCount` 目前恒 0；若要支持，需在 `PrecheckJobRunner` 补写入并同步前端展示。
