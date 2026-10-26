> **历史分析 / 过程证据 — 非现行基线，非验收结论**
>
> - 归档：2026-10-26 · `docs/evidence/history-recovery-2026-10-26/wire-tail/`
> - 来源：Kimi Code 会话 `<KIMI_SESSION>` / 子 agent `agent-415` 的 wire 末条 assistant 文本（消息序号 m=21；只读流式解析，非原始会话转写）
> - 历史时点：2026-10-11 11:11 → 2026-10-11 11:13（wire 事件时间窗，UTC+8）；当时模型：`DeepSeek/deepseek-flash`
> - 原基线：正文未出现基线哈希，未经本批复验
> - 原始体量：16032 B / 11365 字符 / 127 行；行指纹覆盖率（本批复算）2.3%（最高单文件 2%：`docs/任务看板.md`）
> - 类别：**施工复核**
> - 与既有件关系：施工报告；报告口径覆盖率 0.02
> - 脱敏：路径 0 / 会话标识 0 / 身份 0 / 密钥 0 / 命名纪律 0
> - 正文为该条消息的完整文本，未删段、未改写结论；仅脱敏、占位符归一与行尾空白规整。当时结论不代表当前代码状态，不得作为当前缺陷或有效裁决引用。
以下为逐类实读取证结果（全部只读；未修改任何文件、未执行任何 git 写操作）。

---

# 一、新增 5 类

## 1. `JobWatchdogTest` — 8 个 `@Test`（声称 8 ✓）

文件：`backend/src/test/java/com/example/configmgr/job/service/JobWatchdogTest.java`

手法：纯 Mockito + 可控时钟 `MutableClock`（同实例注入 `JobHeartbeatRegistry` 与 `JobWatchdog` 的包级 7 参构造器，`JobWatchdogTest.java:41-51`；生产代码 `JobWatchdog.java:93-103` 确为 `Clock` 注入）。

| # | 方法名 | 行 | 断言要点 |
|---|---|---|---|
| 1 | `heartbeatTimesThreeBoundaryJudgesDeadWithFullActionChain` | 60-78 | 推进 `90s−1ms`（行 67）→ `assertNoJudgement()`（行 69，never complete/never cancel）；再推进 `2ms`（合计 90s+1ms，行 72）→ `judgedDeadTotal==1`、`judgedTimeoutTotal==0`（行 75-76）→ `assertJudgementChain(job,"判死")`（行 77） |
| 2 | `freshHeartbeatNeverJudgedDeadEvenBeyondScanCycles` | 80-92 | 5 轮 ×25s（累计 125s > 90s），每轮先 `beat` 再 `scan`；`assertNoJudgement()` + `tracked==1` |
| 3 | `absoluteTimeoutJudgesDespiteFreshHeartbeat` | 96-107 | `startedAt=now−31min` + **心跳新鲜**（行 100）→ `judgedTimeoutTotal==1`、`judgedDeadTotal==0`、链判定"判超时" |
| 4 | `pendingJobUsesCreatedAtAsTimeoutBaseline` | 109-118 | PENDING（无 startedAt，`createdAt=now−31min`）→ `judgedTimeoutTotal==1` + 链 |
| 5 | `runningWithinLimitsNotJudged` | 120-128 | RUNNING 20min + 新鲜心跳 → `assertNoJudgement()` |
| 6 | `runningJobWithoutAnyHeartbeatIsNotJudgedDead` | 131-138 | RUNNING 5min、**从未 beat** → `assertNoJudgement()`（保守不判死） |
| 7 | `trackedReflectsLatestScanAndLastScanAtAdvances` | 142-153 | `tracked==2`、`lastScanAt != null`、`lastScanAt == clock.millis()`（`Instant.parse` 往返校验），且 `assertNoJudgement` |
| 8 | `emptyInFlightSetCausesNoAction` | 155-165 | `tracked==0`，`never()` 覆盖 `terminalWriter.complete` / `cancellationRegistry.cancel` / **`issueRepository.save`** |

链路断言本体：`assertJudgementChain`（175-187）—— `issueRepository.save` 捕获 1 次并逐字段断言 `jobId`、**`defCode == "-"`（行 180）**、`severity == ERROR`（行 181）、`message` 含"作业看门狗"+原因词（行 182）；`terminalWriter.complete(job, FAILED, anyInt×4, any())`（184-185）；**`cancellationRegistry.cancel(jobId, "job-watchdog")` 字面量钉死（行 186）**。

逐条核验你点名的 6 项：

- **① 边界**：确实是 `89999ms → 不判死` / `90001ms → 判死`（行 67、72）。判死窗 = `30s×3 = 90000ms`（`AppProperties` 缺省，测试用 `new AppProperties()`）。**精度说明（事实）**：只探测了 ±1ms，**没有探测"恰好 90000ms"**，因此该测例不能区分实现里的 `>` 与 `>=`（`JobWatchdog.java:190` 实为严格 `>`）；能区分的是"阈值位置是 3×interval，且基准是心跳而非 startedAt"。
- **② 全链路**：四要素齐备且为真实断言（非同名空壳）：ERROR（181）+ defCode 哨兵 `"-"`（180）+ `complete(..., FAILED, ...)`（184-185）+ `cancel(id, "job-watchdog")`（186）。**弱化点**：`complete` 的 4 个 int 与事件 Map 全用 `anyInt()/any()`，未钉 `errorCount=1`；未断言 `heartbeatRegistry.clear`（生产在 `JobWatchdog.java:221`）。
- **③ 30min 上限对新鲜心跳仍判**：成立（测例 3，行 96-107），且反向断言 `judgedDeadTotal==0` 排除了"其实是判死"的混淆。
- **④ PENDING 用 createdAt**：成立但**判别力偏弱**。`pendingJob` 不设 `startedAt`（行 196-200），故该测例只能排除"无基准 → 不判"这一形态；无法区分"一律用 createdAt"或"startedAt 非空优先"。另外测例 3 的 `runningJob` 把 `startedAt` 与 `createdAt` 设成同一时刻（行 191-192），**没有任何测例构造 `startedAt ≠ createdAt`**，故 Q3 "RUNNING 用 startedAt / PENDING 用 createdAt" 的**分叉规则未被判别**。
- **⑤ 从未心跳保守不判死**：成立（测例 6），且能真正反证 `lastBeat == null → 判死` 的写法。
- **⑥ 已终态 / 空集合**：**空集合成立（测例 8）；"已终态"无任何测例**。事实核验：`scan()` 的 timeout 分支不带状态守卫（`JobWatchdog.java:158-164`，只有 `dead` 分支检查 `status == RUNNING`），在途过滤完全依赖仓储查询 `findByStatusIn(IN_FLIGHT)`（`JobWatchdog.java:150`）；而测试里该调用被 `any()` 打桩，**未捕获参数、未断言 `[PENDING, RUNNING]` 集合**。若把终态作业投喂进列表，看门狗会按 timeout 判它——此风险不在用例覆盖面内。

## 2. `JobProgressHeartbeatTest` — 3 个 `@Test`（声称 3 ✓）

文件：`backend/src/test/java/com/example/configmgr/job/service/JobProgressHeartbeatTest.java`

| # | 方法名 | 行 | 断言要点 |
|---|---|---|---|
| 1 | `markRunningSnapshotAndFinishEachAdvanceHeartbeat` | 59-82 | 开工前 `lastBeatAtMs==null`（67）→ `markRunning` 后 `==1_000_005`（72）→ `snapshot` 后 `==1_000_012`（76）→ `finish` 后 `==1_000_021`（80）→ `t1 < 最终值`（81）。时钟为自增 `AtomicLong`，毫秒值精确钉死 |
| 2 | `heartbeatIsWrittenOutsideTheRequiresNewTransaction` | 84-97 | 唯一断言：`markRunning(7L,50)` 后 `lastBeatAtMs(7L)` **`isNotNull()`**（95-96） |
| 3 | `clearRemovesHeartbeatEntry` | 99-105 | 直接测 `JobHeartbeatRegistry` 的 `beat/clear`（与 Service 无关）|

### 对"事务外"判别力的独立结论（事实）

**结论：测例 2 对"心跳在 REQUIRES_NEW 事务之外生效"这一属性没有真实判别力，只是同名。把 `beat()` 移进事务内部，该测例仍会通过。**

证据链：
1. 它的全部桩设是 `getTransaction(any()) → new SimpleTransactionStatus()`（行 87），即**成功路径**，没有任何"事务管理器抛异常"的桩——与它自己的注释声称的"事务管理器抛异常也不得吞掉心跳"（行 86）**不一致**。
2. `TransactionTemplate` 在这套桩下会同步执行回调；`markRunning` 的事务体对 PENDING 作业不早退（`JobProgressService.java:70-80`）。
3. 因此无论 `heartbeatRegistry.beat(jobId)` 位于 `requiresNew.executeWithoutResult(...)` **之前**（现状，`JobProgressService.java:69`）还是**之内**，回调都会执行、`lastBeatAtMs` 都会被写入，`isNotNull()` 都通过。唯一能让它失败的改动是"从 `markRunning` 中彻底删掉 beat"。
4. 该断言与测例 1 的首个断言（行 72）在证据强度上等价；测例 1 反而更强（三个入口 + 精确毫秒）。

**什么情况下会失败 / 不会失败（可直接引用）**：
- 会失败：`markRunning` 完全不 beat（`lastBeatAtMs` 为 null）；`beat` 写入被条件分支挡住（如仅 PENDING 时跳过/仅非 PENDING 时跳过）；注入的 registry 与断言的不是同一实例。
- **不会失败**：`beat()` 从"事务外"移到 `REQUIRES_NEW` 回调内部；`beat()` 移到事务提交之后；事务提交失败（mock 的 `commit` 是 no-op，不抛）；`beat()` 被调用两次。
- 能使之具备判别力的最小改法（本次**未**采用）：`doThrow` 让 `getTransaction` 抛异常，然后断言心跳仍在（beat 在事务外则留痕、在事务内则回调不执行 → 断言失败）；或用 `InOrder` 断言 `beat` 先于 `getTransaction`。

## 3. `ExportJobRunnerConditionFailFastTest` — 3 个 `@Test`（声称 3 ✓）

文件：`.../job/service/ExportJobRunnerConditionFailFastTest.java`（真实 `@SpringBootTest` + H2 + 真实落盘，`@TestPropertySource` 行 35-39）

- **U1 `unparsableConditionFailsFastWithoutProducingAnyExportFile`（63-88）**，脏条件 `{scopeKeys=[XN], fields=[]}`（68，非法 JSON 属实：键未加引号）：
  - `job.status == FAILED`（72-73）✓、`errorCount == 1`（74）✓
  - issue：`findByJobIdAndDefCode(jobId, ROLE_DICT)` **hasSize(1)**（76-77）、`severity == ERROR`（78）、message 含 `IllegalArgumentException` + `条件 JSON 解析失败`（79）——与 `ConfigDataService.java:88` 的抛文一致 ✓
  - `task_item.status == "FAILED"`（81-82）✓；`jobItem` 用 **`anySatisfy`**（83-84）——弱形式（有任一 FAILED 即过，但列表为空会失败）
  - **零导出件：有断言，且是真实仓储查询** `taskFileRepository.findByTaskIdAndDefCodeAndFileType(taskId, ROLE_DICT, "EXPORT")` **`.isEmpty()`**（86-87）✓
- **U2 `nullAndBlankConditionStillExportEverything`（92-112）**：两任务（`conditionJson` 未设 = null；另一设 `"   "`，99）→ 均 `COMPLETED`（105）、`errorCount == 0`（106）→ 导出件 **isPresent** 且 `rowCount == 8`（107-110）。8 与种子一致（`GlmSeedService.java:213` ROLE_DICT 行数 8；`ExportFlowJobTest.java:127` 亦钉 8）✓。空白路径确认走 `ConfigDataService.java:81-83` 返回 null → 全量 ✓
- **U3 `unknownOperatorFailsItemAndJobSameCauseAsDataPlane400`（116-131）**：`NO_SUCH_OP` → `job.status == FAILED`（125）、issue `hasSize(1)` 且 message 含 `未知的查询条件操作符`（126-128，与 `ConditionEvaluator.java:53` 一致）→ **导出件 `.isEmpty()`（129-130）** ✓。比 U1 少了 errorCount / task_item 状态断言。
- 补充事实：`TaskService.setCondition` 是纯透传（`TaskService.java:130-140`），不校验 JSON/操作符，故 U1/U3 的脏数据能在 setup 阶段成功落库。

## 4. `PublishImportModeFailFastTest` — 3 个 `@Test`（声称 3 ✓）

文件：`.../job/service/PublishImportModeFailFastTest.java`

- **U4 `unparsableImportModeFailsWholeBatchWithJobLevelIssue`（61-90）**，`settingsJson = "{\"importMode\":"`（68）：
  - `status == FAILED`（75-76）✓、`errorCount == 1`（77）✓（生产 `recordFailure` 的 `errors = 1`，`PublishJobRunner.java:294,311`）
  - **作业级 `"-"` issue**：`findByJobIdAndDefCode(jobId, "-")` **hasSize(1)**（79-80）、severity ERROR（81）、message `startsWith("发布过程出错: ")` + 含 `settings_json 解析失败` + `不静默降级 REPLACE→MERGE`（82-85）——与 `PublishJobRunner.java:296-303` + `:126-128` 逐字一致 ✓
  - **"已发布数据零变化"：断言确实存在**（87-89：`已发布行数不变` + `dataJson` 相等）
  - **但该"零变化"断言在本测例中是空转的（事实）**：U4 全程**没有 `taskItemRepository.save` 也没有 `stagingRowRepository.save`**（对照同类 helper `run(task, staged...)` 行 149-157 未被调用），而 `doPublish` 在读取 task_item 之前就抛（`PublishJobRunner.java:124-129` vs `:138`）。即：即便把 UNPARSEABLE 分支改成"静默 MERGE"，本测例的数据也一行不会变——U4 的数据断言无法反证"整批回滚"。能真正反证回滚的是既有 `PublishSemanticsTest#hardErrorRollsBackWholeBatch`（有暂存行 + 断言 `stagingRow.status` 仍为 `STAGED`），U4 没有对应物。U4 的**有效**判据是"FAILED + errorCount=1 + 作业级 issue 文案"三件套。
- **U5 `missingImportModeStillFallsBackToMerge`（94-112）**：`settingsJson=null`（100）→ `COMPLETED`（107）、`errorCount==0`（108）、**行 id 保留**（110）、`dataJson` 含新值（111）✓（helper 走 `run(task, staged(...))`，确有暂存行）
- **U6 `emptySettingsObjectStillFallsBackToMerge`（115-128）**：`"{}"` → `COMPLETED`（126）+ 值生效（127）✓
- 与既有 `PublishSemanticsTest` 同构（同 `DEF = "REGION_NETWORK"`、同 `published()`/`findRow` 形态，`PublishSemanticsTest.java:41,59,208-213`；种子 REGION_NETWORK 3 行，`GlmSeedService.java:218`）。

## 5. `AppPropertiesJobWatchdogValidationTest` — 7 个 `@Test`（声称 7 ✓）

文件：`backend/src/test/java/com/example/configmgr/config/AppPropertiesJobWatchdogValidationTest.java`

| # | 方法名 | 行 | 覆盖 |
|---|---|---|---|
| 1 | `defaultsPass` | 21-26 | 缺省 `doesNotThrowAnyException`（30s/10s/3/30m）|
| 2 | `nonPositiveHeartbeatIntervalFailsFast` | 28-40 | `ZERO` + `-1s`，均 `IllegalStateException` 且 message 含 `app.job.heartbeat-interval` |
| 3 | `nonPositiveScanPeriodFailsFast` | 42-54 | `ZERO` + `-10s`，含 `app.job.watchdog.scan-period` |
| 4 | `nonPositiveDeadMultiplierFailsFast` | 56-68 | `0` + `-3`，含 `app.job.watchdog.dead-multiplier` |
| 5 | `nonPositiveMaxExecutionTimeFailsFast` | 70-82 | `ZERO` + `-30m`，含 `app.job.max-execution-time` |
| 6 | `deadWindowMustStayBelowMaxExecutionTime` | 84-100 | 15m×3=45m ≥ 30m → 抛 + **message 含 `必须 < max-execution-time`**（88-92）；**"恰好等于"边界：10m×3 = 30m == 上限 → 仍抛**（95-99，仅断 `isInstanceOf`，未断文案）|
| 7 | `relationHoldsWhenDeadWindowBelowMaxExecutionTime` | 102-109 | 60s×2=120s < 10m → 不抛 |

核验结论：四键零/负路径**全部覆盖**（4 个键 × {0, 负} = 8 组输入）✓；`恰好等于` 边界**确被覆盖**（行 95-99）✓，与生产 `deadWindow.compareTo(maxExecutionTime) >= 0` 的 `>=` 口径一致。未覆盖项（事实）：`null` 值的守卫分支（生产有 `== null` 判断，测试未触发）；也未覆盖"略小于上限"的非等价正向样本（测例 7 可充当）。

---

# 二、适配 6 类的 diff 核验

`git diff -- <file>` 逐文件结果（**无一处超出"构造参数适配 / 回显断言"范围**，无断言语义删改）：

| 文件 | diff 摘要 | 是否越界 |
|---|---|---|
| `ExportJobRunnerJobLevelIssueTest.java` | 唯一改动 = 构造器追加 `mock(ConfigDataService.class)`（diff `@@ -74,7 +74,8 @@`，实为 1 行换行 + 1 行参数） | 否。且注入的 mock **惰性**：该测例在 `cancellationRegistry.isCancelled` 处抛该（测试行 88-89），`configDataService.parseCondition` 永不被调用 → 语义零改 |
| `SessionBusyFrameIsolationConformanceTest.java` | 唯一改动 = `mock(Environment.class))` 后追加 `mock(JobWatchdog.class)`（1 行，`@@ -102,7 +102,7 @@`） | 否 |
| `AiControllerHistoryDeleteTest.java` | 唯一改动 = 追加 `mock(JobWatchdog.class)`（`@@ -315,7 +315,8 @@`） | 否 |
| `AiControllerRunsCurrentTest.java` | 唯一改动 = 追加 `mock(JobWatchdog.class)`（`@@ -165,7 +165,8 @@`） | 否 |
| `AiControllerFrontendResultTest.java` | 唯一改动 = 追加 `mock(JobWatchdog.class)`（`@@ -140,7 +140,8 @@`） | 否 |
| `AiControllerHealthSseDeliveryTest.java` | 两处：① `import JobWatchdog`；② `controller()` 内新增 `jobWatchdog` 桩（`metrics()` 返回钉死 Map，`@@ -81,16 +82,35 @@`）；③ **新增测例 `healthExposesJobWatchdogMetrics`**（断言 `$.jobWatchdog.tracked/judgedDeadTotal/judgedTimeoutTotal/lastScanAt` 四键回显，值由桩钉死为 0/0/0/`2026-10-11T00:00:00Z`）| 否 —— 与声称的"另一回显断言 1 例"**完全吻合**，且属"回显配对"性质（桩→JSON 回声），**不校验真实 `JobWatchdog` 的取值语义**；与生产 `AiController.java` 新增 `body.put("jobWatchdog", this.jobWatchdog.metrics())` 一一对应 |

补充一致性事实：仓库内 `new AiController(` 出现处恰为上述 5 个测试文件（另加 `HistoryDeleteTest` 内一处 javadoc 文本），`new ExportJobRunner(` 测试侧出现处恰为该 1 个文件 —— 6 个适配文件集合**完备**，无遗漏的旧签名调用点。

---

# 三、需要复核官注意的判别力缺口清单（仅事实）

1. `JobProgressHeartbeatTest:84-97`：对"事务外"**零判别力**；通过/失败条件见上文第 2 节。
2. `PublishImportModeFailFastTest:87-89`：U4 无暂存行/任务条目 →"整批回滚/数据零变化"断言为空转。
3. `JobWatchdogTest`：无"终态作业"测例；未钉 `findByStatusIn` 的实参集合；未探测"恰好 90s"；未断言 `errorCount`/`clear()`；`startedAt` 与 `createdAt` 从未取不同值，故 Q3 状态分叉规则未被判别。
4. `ExportJobRunnerConditionFailFastTest:83-84`：`anySatisfy` 弱形式。
5. `AppPropertiesJobWatchdogValidationTest`：未覆盖 `null` 键值守卫分支。
