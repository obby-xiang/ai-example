# S5.f 证据：M1 收尾棒-B —— 守卫与 API 语义修复（7 项）

> 施工类型：**生产代码修复/增强**（`backend/src/main` 有改动，本棒授权范围）+ 测试连带更新 + E2E 脚本更新。
> 依据裁决：`docs/evidence/S5a-glm测试移植验证.md`（§5 待裁决 #1/#2/#3 的"裁决结论"：全部采纳）
> 与 `docs/evidence/S5b-deepseek-E2E移植验证.md`（§6.2/§6.3 裁决：采纳；§6.6 建议：补只读端点）。
> 证据等级：**全部为实测**（单测真实执行、E2E 真实两轮、现场 REST 原始响应照录）；
> 未实测到的一律标注【未触发】/【待裁决】，不做"应该是这样"的补全。
> 施工日期：2026-10-07　分支：`main`　施工前 HEAD：`ea30ce4`　（本棒**未做任何 git 写操作**）

## 0. 元信息与脱敏口径

| 项 | 值 |
|---|---|
| 仓库 | `<REPO_ROOT>`（后端在 `backend/`） |
| 工具链 | JDK 21（PATH）/ Maven `<MAVEN_HOME>` / Spring Boot 3.5.14 / H2 2.x / JUnit 5 + AssertJ + MockMvc |
| 外部依赖 | Memurai `127.0.0.1:6379`（`<MEMURAI_HOME>/memurai-cli` → `PONG`，只读探活）；真实模型 `deepseek-flash`（E2E 的 AI 用例） |
| 端口 | 18330（独立库文件；启动前 `netstat` 无监听；验证后已释放） |
| 数据库 | 独立库 `./data/e2e_s5f_db`（**不动**演示库 `config_mgr_db.mv.db`；验证后已删除本棒库文件） |
| 原始记录 | `<TMP>/s5f-boot-A.log`（后端 stdout）、`<TMP>/s5f-runA.txt` / `s5f-runB.txt`（两轮脚本输出）、`<TMP>/s5f-mvn-test-final.log`（全量单测）、`<TMP>/s5f-evidence/raw.txt`（现场 REST 原始响应） |
| 脱敏 | 本机绝对路径 → `<REPO_ROOT>` / `<MAVEN_HOME>` / `<MEMURAI_HOME>` / `<TMP>`；AI key → `***`（仅经进程环境变量注入，**未落任何文件**） |

**本棒 7 项**（指挥官已定，照此实施）：
① 预检查阻断守卫　② 导出/同类作业互斥 409　③ 未知操作符抛异常　④ 重复编码创建定义 409
⑤ 只读 `GET /api/ai/tools` 端点　⑥ CI actions 升级 v5　⑦ E2E TC18 抗抖动

（S5.b 裁决里的 Q8②"SSE 二次写"不在本棒清单内 —— 该修复在本棒开工前已存在于
`GlobalExceptionHandler#isStreamingResponse`，本棒 E2E 的 Q8② 断言实测为 PASS，见 §4。）

---

## 1. 逐项：改法 → 涉及文件 → 回归测试

### 1.1 ① 预检查阻断守卫（409 `PRECHECK_NOT_PASSED`）

**改法**：在作业入口 `JobService#createAndStart`（REST `POST /api/tasks/{id}/jobs` 与 AI 工具
`start_import`/`start_publish` 的**同一漏斗**）新增 `assertPrecheckPassed`：`IMPORT`/`PUBLISH`
要求该任务**最近一次 PRECHECK** 为 `COMPLETED` **且** `errorCount == 0`，否则抛
`ConflictException("PRECHECK_NOT_PASSED", …)` → 409 + 机器可读码（对齐 DC-11 的 409 串行化风格）。
**无 PRECHECK 记录时按"从严"实现（拒绝）** —— 建议与依据见 §6.1。

- 守卫在**作业落库之前**抛出：被拒请求不产生 PENDING 作业行（否则互斥守卫会被自己钉死）。
- `EXPORT`/`PRECHECK` 不受该守卫约束（导出读已发布数据、预检查本身是该守卫的前置动作）。
- 文案里带机器可读码（如"A/B 均 409 `PRECHECK_NOT_PASSED`"），因为 AI 工具通道只把
  `e.getMessage()` 当文本回给模型，码必须在文案里才能被模型与排障看见。

| 涉及文件 | 改动 |
|---|---|
| `backend/…/job/service/JobService.java` | 新增 `IN_FLIGHT` 常量、`assertPrecheckPassed`、入口调用与 Javadoc（守卫语义/边界） |
| `backend/…/job/repo/JobRepository.java` | 新增 `findTopByTaskIdAndJobTypeOrderByCreatedAtDescIdDesc`（"最近一次"带 id 兜底，防同刻创建时取用不稳） |

**回归测试** `backend/src/test/java/com/example/configmgr/job/service/PrecheckGateGuardTest.java`（7 用例）：
反例 = 无 PRECHECK 记录（IMPORT 与 PUBLISH）、最近一次 FAILED、RUNNING/PENDING、CANCELLED
（均断言 409 + `code=PRECHECK_NOT_PASSED` + 不落作业行）；正例 = 最近一次 COMPLETED 且
errorCount==0 放行（**更早的失败不作算**，证明判据是"最近一次"）、EXPORT 不受约束；
端到端反例 = 真跑一次 `PrecheckJobRunner`（无上传文件 → FAILED）后导入被拒。

**现场实测**（`<TMP>/s5f-evidence/raw.txt` ③④，脱敏后照录）：

```
POST /api/tasks/39/jobs {"jobType":"IMPORT"}   → HTTP 409
{"success":false,"message":"任务 #39 尚无预检查记录，不能发起 IMPORT（PRECHECK_NOT_PASSED）：
 请先上传数据文件并执行预检查，通过后再继续","code":"PRECHECK_NOT_PASSED"}
POST /api/tasks/39/jobs {"jobType":"PUBLISH"}  → HTTP 409（同码同语义）
```

### 1.2 ② 同类作业互斥（409 `JOB_ALREADY_RUNNING`）

**改法**：同一入口新增 `assertNoJobInFlight`：同 `taskId` + 同 `jobType` 已存在
`PENDING`/`RUNNING` 作业 → 409 `JOB_ALREADY_RUNNING`，文案带在途作业号与状态。
动机（S5.a §5#2 实测）：无互斥时两个导出会并发写同一 `task_files(task, def, EXPORT)` 行
（后发者覆盖），且进度互不感知。终态（COMPLETED/FAILED/CANCELLED）**不互斥**，故不会把任务钉死。

| 涉及文件 | 改动 |
|---|---|
| `backend/…/job/repo/JobRepository.java` | 新增 `findFirstByTaskIdAndJobTypeAndStatusInOrderByIdDesc` |
| `backend/…/job/service/JobService.java` | `assertNoJobInFlight` + 入口调用 |

**回归测试** `backend/…/job/service/JobMutexGuardTest.java`（6 用例）：PENDING/RUNNING 被拒
（断言 409 + 码 + 文案含在途作业号 + 作业表不增行）、终态（COMPLETED/CANCELLED/FAILED）放行、
异类型（同任务 EXPORT 在途时 PRECHECK 可发起）、异任务互不影响。

**现场实测**（`raw.txt` ⑧，120 行大表 + 150ms/批 → 在途窗口内二次发起）：

```
POST /api/tasks/40/jobs {"jobType":"EXPORT"}  → HTTP 409
{"success":false,"message":"任务 #40 已有同类作业在途：作业 #53 [EXPORT] 状态 RUNNING
 （JOB_ALREADY_RUNNING）。并发执行会互相覆盖同一批文件/暂存行，请等待其结束，或先取消该作业再发起",
 "code":"JOB_ALREADY_RUNNING"}
（首个作业随后 COMPLETED 120/120）
```

### 1.3 ③ 未知操作符抛异常（端点 400 / 作业 FAILED）

**改法**：`ConditionEvaluator`
- 新增登记表 `OPERATORS`（12 个：EMPTY/NOT_EMPTY/EQ/NE/CONTAINS/LIKE/STARTS_WITH/IN/GT/GTE/LT/LTE）
  与 `validate(QueryCondition)`（**与行数据无关**的早校验）；
- 求值分支 `default:` 由 `return true`（静默忽略该条件 = 导出范围更宽）改为
  `throw new IllegalArgumentException("未知的查询条件操作符: …（字段 …），已登记的操作符: […]")`。

两条出口各自就位：
- **列表/计数端点**：`ConfigDataService#parseCondition` 在逐行求值前调用 `validate` → 400
  （行数为 0 时同样 400，不依赖"恰好有行"）；同时把 `filterRows` 的行级兜底收窄为"只兜 JSON 解析失败"，
  避免求值异常被吞成"0 行"。
- **作业内路径**：`ExportJobRunner` 逐配置项 `validate` 早失败 → 该配置项与作业 `FAILED`，
  不产出导出文件（绝不产出"更宽"的件）。

| 涉及文件 | 改动 |
|---|---|
| `backend/…/data/service/ConditionEvaluator.java` | `OPERATORS` 白名单 + `validate` + `default` 抛异常 + 类 Javadoc |
| `backend/…/data/service/ConfigDataService.java` | `parseCondition` 调 `validate`；`filterRows` 兜底收窄 |
| `backend/…/job/service/ExportJobRunner.java` | 解析条件后 `validate` 早失败（转 FAILED） |

**回归测试** `backend/…/data/service/ConditionOperatorGuardTest.java`（7 用例）：
求值器对未知操作符抛 `IllegalArgumentException`；**12 个已登记算子逐个不误伤**（关键防回归）；
`validate` 与行数据无关；列表端点 400、`/count` 端点 400、**零行定义同样 400**；
导出作业 FAILED + errorCount=1 + 无导出文件 + 条目 FAILED。

**现场实测**（`raw.txt` ⑥⑦⑨）：

```
GET /api/data/SYS_PARAM?conditions=…"operator":"BETWEEN"…        → HTTP 400
{"success":false,"message":"未知的查询条件操作符: BETWEEN（字段 paramKey），
 已登记的操作符: [NE, LTE, IN, EQ, GTE, GT, STARTS_WITH, NOT_EMPTY, LIKE, CONTAINS, EMPTY, LT]"}
GET /api/data/SYS_PARAM/count?conditions=…BETWEEN…               → HTTP 400（同上）
（作业内路径）EXPORT 作业 #54 → FAILED，errorCount=1，导出文件清单为空
```

> 观测到的边界（登记为遗留，见 §6.2）：`ExportJobRunner` 的逐配置项失败**只写日志 + 条目状态**，
> 不落 `validation_issues`，故 `GET /api/jobs/54/issues` 为空 —— 失败原因在日志
> （`Export failed for def SYS_PARAM: 未知的查询条件操作符: BETWEEN…`）里可见，但不可经 REST 查得。

### 1.4 ④ 重复编码创建定义（409 `DEFINITION_CODE_DUPLICATE`，不泄漏 SQL）

**改法**：`DefinitionService#save` 增加前置校验（`findByCode` 命中即抛
`ConflictException("DEFINITION_CODE_DUPLICATE", …)`），取代原先"唯一约束命中 →
`DataIntegrityViolationException` → 全局处理器 500 分支 + 原始 JDBC 报文"的路径
（S5.b §6.2 实测报文含 `insert into config_definitions … [23505-232]`）。
种子路径不受影响：`GlmSeedService` 先 `existsByCode` 过滤，`DataSeedRunner` 有整库跳过判据。

| 涉及文件 | 改动 |
|---|---|
| `backend/…/definition/service/DefinitionService.java` | `save` 前置重复校验 + Javadoc |

**回归测试** `backend/…/definition/service/DefinitionCodeDuplicateTest.java`（3 用例）：
重复 POST → 409 + `code` + 文案含编码 + **响应体不含 `insert into`/`Unique index`/`23505`/
`could not execute statement`** + 原定义零变化；服务层抛 `ConflictException` 且 `getCode()` 正确；
新编码仍正常 201。

**现场实测**（`raw.txt` ⑤）：

```
POST /api/definitions {"code":"CURRENCY", …}  → HTTP 409
{"success":false,"message":"配置定义编码 CURRENCY 已存在（DEFINITION_CODE_DUPLICATE），
 请换一个编码，或改用更新接口修改该定义","code":"DEFINITION_CODE_DUPLICATE"}
```

### 1.5 ⑤ 只读工具披露端点 `GET /api/ai/tools`

**改法**：新增 `AiToolsController`（`/api/ai/tools`，查询参数 `page`/`taskType`/`step`/`taskId`）。
返回 `{context, page, taskType, step, taskId, count, registeredCount, toolNames[], tools[]}`，
其中 `tools[]` 每项含 `name/displayName/riskLevel/channel/scopePatterns`。
披露口径与 `ToolRegistry#forContext` **同源**（就是同一个方法），故可作为 CI 与排障的替代取证面
（不再依赖后端 DEBUG 日志行）；**只读、无状态、不依赖 AI key 与 Redis**（`ToolRegistry` 是纯 Bean 扫描）。

| 涉及文件 | 改动 |
|---|---|
| `backend/…/ai/web/AiToolsController.java` | **新增**（端点 + Javadoc：为什么只读/无状态/不带 key 门） |
| `backend/…/ai/tool/ToolRegistry.java` | 新增只读计数 `registeredCount()`（供 `registeredCount` 字段，无其他改动） |

**回归测试** `backend/…/ai/web/AiToolsDisclosureEndpointTest.java`（6 用例）：
`page:tasks` 披露 `start_export` 等且**不含** `start_publish`；`task:IMPORT/PUBLISH` 含
`start_publish` 且**不含** `start_export`；附带 `riskLevel=DANGER`/`channel=BACKEND`/scope 元数据；
无参 = 上下文 `*`（只披露"任何上下文都可见"的子集，与 `forContext(empty)` 逐项一致）；
**与 `ToolRegistry.forContext` 三种上下文逐项同源**；调用三组上下文前后任务数/作业数/登记数不变（只读性）。

**现场实测**（`<TMP>/s5f-evidence/raw.txt` ①②）：

```
GET /api/ai/tools?page=tasks                       → context=page:tasks  count=12
GET /api/ai/tools?taskType=IMPORT&step=PUBLISH     → context=task:IMPORT/PUBLISH  count=11
  page:tasks 12 个：confirm_step, start_export, start_precheck, get_workspace_state, get_config_def,
        select_definitions, set_condition, list_config_defs, get_row_count, navigate_to, list_tasks, create_task
  task:IMPORT/PUBLISH 11 个：confirm_step, get_workspace_state, get_config_def, check_job_status,
        select_definitions, start_publish, list_config_defs, get_row_count, navigate_to, list_tasks, create_task
```

### 1.6 ⑥ CI actions 升级到 v5（本地改好，远程 run 留待推送后确认）

**改法**：`.github/workflows/ci.yml` 中三处 action 由 v4 → v5（共 4 行）：
`actions/checkout@v5`（×2）、`actions/setup-java@v5`、`actions/setup-node@v5`。

**本地可验证的部分（已实测）**：
1. `v5` 标签存在且解析到真实 commit（匿名 GitHub REST `git/ref/tags/v5`）：
   `actions/checkout@v5` → `fbc6f399…`、`actions/setup-java@v5` → `b6effb05…`、
   `actions/setup-node@v5` → `a0853c24…`；
2. 本 workflow 用到的入参在 v5 中**仍受支持**（读 `v5` 分支的 `action.yml`）：
   `setup-java` 的 `distribution/java-version/cache/cache-dependency-path` 均在；
   `setup-node` 的 `node-version/cache(=npm|yarn|pnpm)/cache-dependency-path` 均在
   （v5 新增的是"自动检测包管理器"能力，不改变显式 `cache: yarn` 的用法）；
3. 三者 v5 均为 `runs.using: node24` —— 这正是要清掉的弃用告警的根因（v4 系列为 node20，
   且上游已给 v4 加弃用告警：`setup-java v4.9.1` release note 明写 "Adds a deprecation warning for setup-java v4"）。

**【未触发】远程 run**：见 §6.5 —— 本棒实测 `ci.yml` 的触发条件是 **push/PR 到 `main`**，
推一个一次性分支**不会**触发该 workflow，而触发它需要开 PR（超出"临时分支"授权），且本机无 `gh`。
故本项以"由提交官推送到 `main` 后远程 run 必须仍绿"为最终验收条件，本棒只交本地改动。

### 1.7 ⑦ E2E TC18 抗抖动

**改法**（`scripts/verify-e2e.ps1` TC18）：
1. **fixture 换新**：原先复用 TC17 的任务发起 `start_publish`（该任务在 TC17 已真实发布过）
   → 真实模型可能据此**拒绝再次发布**，导致挂不到 `confirm_request` 而误判。现改为一个
   **从未发布过**的导入任务：新建 → 上传（复用 TC8 的 DOC_TYPE 合法件）→ PRECHECK COMPLETED →
   IMPORT COMPLETED → 步骤置 PUBLISH；并额外断言"该任务没有发布作业"以锁住 fixture 前提。
2. **加一次重试**：把"读流直到 `confirm_request`"包进最多 2 次的循环，每次换新 sessionId；
   两次都挂不到才判 FAIL（失败文案写明"2 次尝试均未挂起到确认门"）。

**连带**：TC18 之后不再依赖 `$script:t17`，与 TC17 解耦（TC17 的 fixture 保持原样）。

**实测**：两轮均一次命中（无需重试即通过），两轮输出逐字一致（§4.2）。

---

## 2. 连带更新清单（既有测试/E2E 用例的断言改动及原因）

守卫 1-3 改变了既有行为，故一并更新了**受影响的既有测试**与**E2E 用例**；
另有 3 项在 S5.a 记录为"因无实现而未移植"的 glm 用例，**本棒补齐移植**（因为现在有实现了）。

| # | 文件 | 改动 | 原因 |
|---|---|---|---|
| 1 | `ImportFlowJobTest`（用例 2 末尾 + 类 Javadoc） | 预检查 FAILED 后断言 `jobService.createAndStart(IMPORT)` 抛 `ConflictException` 且 `getCode()=PRECHECK_NOT_PASSED`、作业表仍只有 1 条 | 类注释原写"无守卫，未移植"已过期；同时**补齐 glm 的 `importRejectedWhileCheckHasError`**（S5.a §4 #5） |
| 2 | `ExportFlowJobTest`（新增用例 4 + 类 Javadoc） | 在途 EXPORT 作业存在时 `createAndStart(EXPORT)` → `JOB_ALREADY_RUNNING` + 文案含在途作业号 + 不落新作业行 | **补齐 glm 的 `exportTwiceRejectedWhileRunning`**（S5.a §4 #4）。用直接落库的在途作业表达"上一条未结束"，避免与异步执行赛跑（确定性） |
| 3 | `PublishedQueryConditionTest`（新增用例 10 + 类 Javadoc） | 未知操作符 → `IllegalArgumentException`；空白操作符仍"该字段不参与过滤"不误伤 | **补齐 glm 的 `invalidOperatorRejected`**（S5.a §4 #1）；glm 的 `invalidFieldRejected`（未知字段）仍无实现，继续留在不适用清单 |
| 4 | `AiToolsInvocationTest`（新增用例 7） | `start_import`/`start_publish` 在无预检查记录时返回文本含 `启动导入失败`/`启动发布失败` + `PRECHECK_NOT_PASSED`，且不落作业行 | AI 通道的守卫回归：模型只能看到文本，码必须出现在文本里才可据实回答 |
| 5 | `scripts/verify-e2e.ps1` TC2 | 由"必须被拒绝（实测 500）"改为 **409 + `DEFINITION_CODE_DUPLICATE` + 报文不含 SQL 片段** + 回读定义未被篡改 | 守卫④ 的行为变化 |
| 6 | `scripts/verify-e2e.ps1` TC12 | IMPORT 前**新增真实 PRECHECK**（COMPLETED/errorCount=0 才继续） | 守卫① 生效后，导入前必须先通过预检查（脚本须走真实流程） |
| 7 | `scripts/verify-e2e.ps1` TC14 | 由"无守卫"替代语义（无文件导入 FAILED + 空暂存不误删）**改写为断言守卫真实阻断**：① 无预检查记录 → IMPORT/PUBLISH 均 409；② PRECHECK 真失败（无上传文件，明细可查）→ 仍 409 且仓库零作业行、生效数据零变化；③ 放行侧：预检查通过后发布空暂存 → 生效数据零变化（**保留 S5.b §6.5 的"不误删"安全不变量**，只是改由"通过守卫的真实流程"抵达） | 守卫① 的行为变化 + 保留原用例中仍然有效的不变量 |
| 8 | `scripts/verify-e2e.ps1` TC15 | 披露断言**主口径改为只读端点** `/api/ai/tools`（两个上下文）；保留 DEBUG 日志行作为**备选交叉校验**（提供 `-BackendLog` 时校验"日志清单 == 端点清单"，排序后比对）；去掉"未提供日志即 FAIL"的硬依赖 | M1 收尾项⑤ 的目的就是让 CI 不依赖日志；两通道一致本身也是一条有价值的断言 |
| 9 | `scripts/verify-e2e.ps1` TC18 | 见 §1.7 | M1 收尾项⑦ |
| 10 | `scripts/verify-e2e.ps1` 头部注释 | 更新 `-BackendLog` 的作用范围（TC15 改端点、日志作交叉校验） | 保持脚本自述与实际一致 |

**未改动的相关测试（说明为什么不受影响）**：`ai/conformance/**`（6 个类、44 用例）全部在**帧层**
用桩件驱动（`InMemoryRunStore`、`FakeStartImportTool` 等），从不调用 `JobService#createAndStart`，
也不经过 `ConditionEvaluator` 的未知操作符分支，故与本棒三个守卫零交集；
`JobGovernanceTest`/`PublishSemanticsTest` 直接驱动执行器（`precheckJobRunner`/`publishJobRunner`），
亦绕开入口守卫 —— 这也正是本棒为守卫**新增专门测试类**（走真实 HTTP 入口）的原因。
`TaskConditionJsonTest` 覆盖的是"条件入参 → 落库 JSON 合法性"（写侧），与读侧操作符白名单不冲突。

---

## 3. 单测结果（真实执行，工作目录 `<REPO_ROOT>/backend`）

```bash
<MAVEN_HOME>/bin/mvn.cmd -B test
```

| 阶段 | 用例数 | 结果 |
|---|---|---|
| 本棒施工前基线（S5.e 口径） | 152 | 全绿（任务书给定；本棒首轮全量复跑前未单跑基线） |
| 本棒新增 5 个守卫测试类 | +29 | 全绿 |
| 本棒补齐 3 个既有类用例 | +3 | 全绿 |
| **全量（本棒终态）** | **184** | **`Tests run: 184, Failures: 0, Errors: 0, Skipped: 0` / `BUILD SUCCESS` / `Total time: 40.166 s`** |

- 计数口径：`^\s*@Test(\s*$|\()` **精确匹配**（`grep -cE`）实测 = **184**，与 Surefire 汇总一致（无参数化/嵌套/跳过）。
- 逐类（新增/改动类）：`PrecheckGateGuardTest` 7、`JobMutexGuardTest` 6、`ConditionOperatorGuardTest` 7、
  `DefinitionCodeDuplicateTest` 3、`AiToolsDisclosureEndpointTest` 6、`ImportFlowJobTest` 9（+1 断言，未增用例数）、
  `ExportFlowJobTest` 4（+1）、`PublishedQueryConditionTest` 10（+1）、`AiToolsInvocationTest` 7（+1）。
- 端口纪律：全部用例为 `@SpringBootTest` + 内存库（`jdbc:h2:mem:…`，MOCK 环境不起 Tomcat），
  未占用 18330 之外的端口，未 `kill` 任何进程；测试产物落在 `backend/target/`（已 gitignore）。

---

## 4. E2E 结果（真实两轮，同一后端实例与同一库文件）

### 4.1 运行方式

```bash
# 构建
cd <REPO_ROOT>/backend && <MAVEN_HOME>/bin/mvn.cmd -B -DskipTests package
# 启动（独立库 + 冷启；key 仅经进程环境变量注入，未落文件）
rm -f data/e2e_s5f_db.mv.db data/e2e_s5f_db.trace.db
AI_API_KEY="***" java -jar target/config-mgr.jar --server.port=18330 \
  --spring.datasource.url="jdbc:h2:file:./data/e2e_s5f_db;DB_CLOSE_DELAY=-1" \
  --app.job.batch-size=10 --app.job.demo-batch-delay-ms=150   # stdout → <TMP>/s5f-boot-A.log
# 执行（两轮）
cd <REPO_ROOT>
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/verify-e2e.ps1 \
  -Base http://127.0.0.1:18330 -BackendLog <TMP>/s5f-boot-A.log
```

前置：`netstat` 无 18330 监听；`<MEMURAI_HOME>/memurai-cli ping` → `PONG`；
`GET /api/ai/health` → `{"available":true,"model":"deepseek-flash","baseUrl":"https://api.deepseek.com",…}`。

### 4.2 两轮结果

| 轮次 | 退出码 | 末尾原文 | 耗时 |
|---|---|---|---|
| 第 1 轮 | **0** | `结果：PASS=22  FAIL=0` / `Q8 项：PASS=2  FAIL=0` | ≈68 s |
| 第 2 轮（幂等重跑，同库） | **0** | `结果：PASS=22  FAIL=0` / `Q8 项：PASS=2  FAIL=0` | ≈64 s |

**两轮输出逐字一致**（除临时目录名与日志路径两行）：`diff` 实测"两轮逐字一致"。
Q8 两项由"S5.b 时的 1 PASS / 1 FAIL"转为 **2 PASS / 0 FAIL**（Q8② 的修复在本棒开工前已在库内）。

本棒涉及用例的两轮实测文案（照录）：

```
[PASS] TC2  定义创建/重复编码拦截(HTTP 409 + DEFINITION_CODE_DUPLICATE，无 SQL 泄漏)/字段动态追加/无主键拒绝
[PASS] TC12 导入写暂存（3 行 STAGED），生效数据保持 5 行不变
[PASS] TC13 范围替换（5→3 行、upsert 保 id 递增版本、差集删除）+ 快照冲突拦截重复发布 + 409 JOB_ALREADY_FINAL
[PASS] TC14 守卫真实阻断（无预检查/预检查失败 → IMPORT、PUBLISH 均 409 PRECHECK_NOT_PASSED，零作业行；
       PROJECT_ENV 28 行不变）+ 放行侧空暂存发布不误删（DOC_TYPE 5 行不变，终态 COMPLETED）
[PASS] TC15 流式+工具调用可见+渐进披露（只读端点 /api/ai/tools）：page:tasks 12 个工具（无 start_publish）；
       task:IMPORT/PUBLISH 含 start_publish
[PASS] TC18 会话记忆隔离 + 409 SESSION_BUSY(携 runId/reattach) + 取消后释放
[PASS] Q8① 明细落库（未上传文件批次的明细可查，日志无写盘异常）
[PASS] Q8② SSE 请求不做二次 JSON 写入（日志无 No converter/HttpMessageNotWritableException）
```

真实模型参与的证据（非桩）：TC15 的 `tool_start`/`tool_result(list_config_defs)`/`done` 帧、
TC16 放行确认门后真实产出导出文件、TC17 确认后 PUBLISH COMPLETED、TC18 的 409 `SESSION_BUSY`
与取消释放、TC22 历史含工具卡片 —— 均为两轮实测到达。

### 4.3 现场 REST 取证（`<TMP>/s5f-evidence/raw.txt`）

除 §1 各处已引用的 ③④⑤⑥⑦⑧⑨ 外，另存 ①②（两个上下文的工具清单）。
这些调用创建的 `S5F-*` 任务位于独立库文件中，验证结束后随库文件一并删除。

---

## 5. 改动面清单

**生产代码（`backend/src/main`，8 个文件：7 改 1 增）**

```
backend/src/main/java/com/example/configmgr/job/service/JobService.java             （守卫①②）
backend/src/main/java/com/example/configmgr/job/repo/JobRepository.java             （两个派生查询）
backend/src/main/java/com/example/configmgr/data/service/ConditionEvaluator.java    （守卫③）
backend/src/main/java/com/example/configmgr/data/service/ConfigDataService.java     （守卫③ 端点通道）
backend/src/main/java/com/example/configmgr/job/service/ExportJobRunner.java        （守卫③ 作业通道）
backend/src/main/java/com/example/configmgr/definition/service/DefinitionService.java（守卫④）
backend/src/main/java/com/example/configmgr/ai/web/AiToolsController.java           （项⑤，新增）
backend/src/main/java/com/example/configmgr/ai/tool/ToolRegistry.java               （项⑤，registeredCount）
```

**测试（5 个新增类 + 4 个既有类改动）**

```
backend/src/test/java/com/example/configmgr/job/service/PrecheckGateGuardTest.java          （新增 7）
backend/src/test/java/com/example/configmgr/job/service/JobMutexGuardTest.java              （新增 6）
backend/src/test/java/com/example/configmgr/data/service/ConditionOperatorGuardTest.java    （新增 7）
backend/src/test/java/com/example/configmgr/definition/service/DefinitionCodeDuplicateTest.java（新增 3）
backend/src/test/java/com/example/configmgr/ai/web/AiToolsDisclosureEndpointTest.java       （新增 6）
backend/src/test/java/com/example/configmgr/job/service/ImportFlowJobTest.java              （改断言+注释）
backend/src/test/java/com/example/configmgr/job/service/ExportFlowJobTest.java              （+1 用例）
backend/src/test/java/com/example/configmgr/data/service/PublishedQueryConditionTest.java   （+1 用例）
backend/src/test/java/com/example/configmgr/ai/tools/AiToolsInvocationTest.java             （+1 用例）
```

**其他**

```
.github/workflows/ci.yml            （项⑥：三处 action v4 → v5）
scripts/verify-e2e.ps1              （项⑦ + TC2/TC12/TC14/TC15 连带 + 头注释）
docs/evidence/S5f-守卫与API语义修复验证.md（本文）
```

`git status` 复核：改动集中在上述文件；**未做任何 git 写操作**（无 add/commit/push）；
未改前端（`frontend/**` 零改动，见 §6.4 的过期注释说明）；未改既有 docs。

---

## 6. 【待裁决】

### 6.1 项①：无 PRECHECK 记录时"从严拒绝"还是"从宽放行"（**请裁决**）

- **本棒实现**：从严（无 PRECHECK 记录 → 409 `PRECHECK_NOT_PASSED`），与"预检查是导入/发布前置"的裁决语义一致。
- **实测影响面**：正常路径**不受影响** —— AI 侧的 `start_import`/`start_publish` 只在
  `task:IMPORT/IMPORT`、`task:IMPORT/PUBLISH` 步披露，而向导到达这两步的既有流程必然已跑过预检查；
  E2E 中唯一的"未跑预检查就导入"用例（原 TC12）已按真实流程补上预检查。
- **建议：维持从严**。理由：① 从宽会留下"从未检查过的数据直接入库"的缺口，而这正是本项要堵的风险；
  ② 严格口径可解释（"先检查再入库"，与 UI 步骤引导一致），且成本可控 —— 若产品日后要从宽，
  只需去掉 `assertPrecheckPassed` 里的 `orElseThrow` 分支（一行级改动）；
  ③ demo 场景没有"历史遗留任务需要免检查导入"的现实约束。
- **另一条边界（建议后续棒收紧）**：判据是"最近一次 PRECHECK"，**不追踪上传件在预检查通过后是否被替换**
  （存在"旧的一次通过 + 新的未检查数据"窗口）。候选改法：判据改为"最近一次 PRECHECK 创建时间晚于
  最近一次上传件更新时间"，或上传覆盖同一 defCode 时把该任务预检查结论置为失效/`FAILED`。

### 6.2 `ExportJobRunner` 逐配置项失败不落 `validation_issues`（可观测性缺口）

实测（§1.3 ⑨）：导出作业因未知操作符 FAILED（`errorCount=1`、条目 FAILED、无导出文件），
但 `GET /api/jobs/{id}/issues` 返回空 —— 原因只在后端日志里。影响：失败原因无法经 REST 呈递给前端/AI。
建议后续棒让 EXPORT 的逐项失败也落一条 issue（或引入统一的"作业失败原因"字段）。
本棒未改（超出 7 项范围）。

### 6.3 条件写入侧未做操作符校验（"早失败点"可以更早）

`PUT /api/tasks/{id}/items/{defCode}/condition` 仍只校验"是合法 JSON"，未知操作符可落库，
随后在**另一个请求**（导出作业 / 列表端点）才报错。建议把同一份白名单校验前移到写侧
（`TaskController#conditionJson`），让脏条件根本不入库。本棒按"只做 7 项"未改。

### 6.4 前端一处注释已过期（本棒未动前端）

`frontend/src/types/condition.ts` 顶部注释仍写"后端对未知操作符的处理是'视为通过'（不丢行）"，
与守卫③ 相反。本棒按"不动前端"纪律未修改（该注释不影响行为与构建：前端类型只收窄登记在册的算子，
从未发送未知操作符）。建议由后续前端棒同步这一行注释。

### 6.5 项⑥：远程 CI run 的验证前提不成立（**请知悉/裁决**）

- 本棒实测 `ci.yml` 的触发条件是 `on: push/pull_request: branches: [main]`；
  推一个**一次性分支不会触发**该 workflow，凭空验证不了"远程 run 仍绿"。
- 要触发它只能：把改动推到 `main`（禁止）或**开 PR 到 `main`**（本棒仅获"临时分支"授权，
  且本机无 `gh` CLI，故未做）。
- 故项⑥ 的验收按任务书前提**留给提交官推送后确认**。本棒已完成的本地验证见 §1.6（v5 标签存在、
  入参兼容、node24 运行时 = 弃用告警根因）。
- 附带信息（供裁决是否追平）：v5 之后上游已有更新的 major（`checkout` v7.0.1、`setup-java` v6.0.1、
  `setup-node` v7.0.0）。本棒按指令停在 v5；若主线希望一次到位，可直接升到对应最新 major
  （本 workflow 未用任何被这些 major 移除的入参，升级面无已知阻碍，但需一次远程 run 才能确认）。

### 6.6 既有问题：`ci.yml` 顶部注释与用例形态不符（本棒未顺手改）

该文件注释写"全部用例为 JUnit + Mockito 桩（无 Spring 上下文/无 Redis/无 AI key 依赖）"，
而实际大多数用例是 `@SpringBootTest` + H2 内存库（本棒新增的 5 个类亦然，与既有
`ToolRegistryDisclosureTest` 同风格）。注释未描述本棒改动的行为，且属既有问题，故未改（避免超范围）；
建议后续棒一并修正。
**（裁决已采纳：本次提交由提交官顺手把该注释改为如实描述，见文末"裁决结论"第 6 项。）**

---

## 7. 收尾确认

- 本棒启动的后端进程（`java -jar target/config-mgr.jar --server.port=18330`，PID 取自 `netstat`）
  已 `taskkill /F` 停止；`netstat -ano | grep 18330` 无 `LISTENING`；`tasklist` 无遗留 `java` 进程。
- 独立库文件 `backend/data/e2e_s5f_db.mv.db`/`.trace.db` 已删除；`backend/data/` 下只剩既有演示库
  `config_mgr_db.mv.db` 与既有 `files/` 目录（**未触碰**）。
- 未占用/未干扰 18080、18290–18299、18301–18321 等既有端口；未 `kill` 非本棒进程。
- 脱敏自查：本文与脚本内 **无 AI key**（`sk-` 与 `AI_API_KEY=<值>` 均 0 命中）、无本机绝对路径
  （一律占位符）、无用户名。

---

## 裁决结论（指挥官 K3，2026-10-07）

对 §6【待裁决】六项的裁决如下（"采纳"= 采纳建议并照此落地；"维持"= 保持本棒现状不变）：

| # | 事项 | 裁决 |
|---|------|------|
| 1 | 无 PRECHECK 记录从严/从宽 | 维持从严——正常路径必已过预检查，从宽留缺口；"上传件替换窗口"收紧记 M2 候选 |
| 2 | CI actions 追平 v6/v7 | 停在 v5——已清弃用告警，追新无收益 |
| 3 | 导出失败不落 validation_issues | 采纳，记 M2 可观测性候选，不阻塞 M1 |
| 4 | 条件写侧操作符校验前移 | 采纳，记 M2 候选（求值侧拦截已防静默污染） |
| 5 | 前端 condition.ts 过期注释 | 采纳，随生成式表单前端棒顺手修正 |
| 6 | ci.yml 顶部注释不实 | 采纳，本次提交顺手修正 |

落地说明：

- 第 1 项对应 §1.1 已实现的从严口径（`assertPrecheckPassed` 的 `orElseThrow` 分支保留）；
  §6.1 末尾提出的"上传件替换窗口"收紧不在 M1 范围，转入 M2 候选清单。
- 第 3、4 项不在本棒范围内，转入 M2 候选清单（§6.2、§6.3 保持原样）。
- 第 5 项待后续前端棒执行（本棒与本次提交均未动前端）。
- 第 6 项已在本次提交内修正：`.github/workflows/ci.yml` 顶部注释改为如实描述
  "以 @SpringBootTest（真实 Spring 上下文 + H2 内存库 + Flyway）为主，少量纯 JUnit + Mockito 桩，
  不依赖 Redis / AI key / Windows 专属物"。
- 第 2 项：本次提交只做 `actions/*@v4 → v5`（`checkout`/`setup-java`/`setup-node`），
  不追平 v6/v7；远程 run 结论见提交后的 CI 记录。

