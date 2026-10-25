# Task 6 独立审查：作业与历史真实性（BZ-01 / BZ-02 / BZ-33）

- 审查对象：`task-6-brief.md`、`task-6-report.md`、`task-6.patch`（13 文件 / 1141 行）
- 审查方式：**只读**。未修改任何文件、未跑任何测试、未起服务（按要求不重跑宽套件）
- 用到的证据：`red-1.log` / `green-1.log` / `green-2.log`（副本目录）、`git apply --check`（主线，未落盘）、补丁后的源码副本 `<隔离副本>/_verify-20261025-job-history/backend`、以及 spring-ai 1.1.7 的 `DefaultToolCallResultConverter` / `JsonParser` 字节码（用于判定"历史文本的真实形态"）
- 文中 `path:line` 的行号一律指**打补丁后**的文件

---

## 0 结论

| 维度 | 判定 | 说明 |
|---|---|---|
| **Spec Compliance** | **PASS** | 三条需求全部达成，且有真实 RED。一处**经论证的偏离**（`JobService.java` 未改），一处**范围确认项**（PUBLISH 只判"零条目"，未扩到"零暂存行"） |
| **Task Quality** | **PASS**（无 Critical / 无 Important，9 条 Minor） | 最小改动、CAS/取消语义零触碰、接口向后兼容、测试非自我实现；残留问题集中在文档陈旧与解析器的极端形态加固 |
| **合并建议** | **建议合并（Approve）** | 合并前建议补一次后端全量（本次只跑 7 类 49 例），并按 M1 更新陈旧注释；其余 Minor 可随后续批 |

---

## 1 需求对照

| Brief 要求 | 实现位置（打补丁后） | 证据 | 判定 |
|---|---|---|---|
| 零条目 EXPORT/IMPORT/PRECHECK/PUBLISH 不得 COMPLETED | `ExportJobRunner.java:118-124`、`ImportJobRunner.java:83-88`、`PrecheckJobRunner.java:97-102`、`PublishJobRunner.java:87-95` | `ZeroItemJobTerminalTest` 4 例；RED 实测 "expected: FAILED but was: COMPLETED" | ✅ |
| 零条目不得把任务假 COMPLETED（TASK_FINALIZED 永久锁死） | 四条链都不传 `successStep`，任务态由 `applyJobOutcome(FAILED)` 联动 | `JobTerminalWriter.java:129-132`；测试断言 `task.status != COMPLETED`；RED 时 EXPORT 确实把任务写成 COMPLETED | ✅ |
| IMPORT/PRECHECK 不得无限 RUNNING，须有界终态 | `ImportJobRunner.java:72` / `PrecheckJobRunner.java:74` 的 `registerRollbackConvergence`（`afterCompletion` → `completeNow(FAILED)`） | `JobRollbackTerminalFallbackTest` 2 例；RED 实测 RUNNING | ✅ |
| 历史 `list_tasks` 不得用当前 DB 改写当时结果 | `AiController.java:1494-1495` 传 `historical=true`；`ListTasksResultViewProvider.java:109-114` 走 `fromPersistedText` | `AiControllerHistoryPayloadTest` 新增 2 例（`verify(never()).findAll()`）；RED 实测 rows 7→9 | ✅ |
| （Brief 文件清单）`JobService.java` | **未改** | 报告 §5①：改在 `createAndStart` 会打红 ≥4 个守卫类；见 §3-④ | ⚠️ 经论证的偏离，接受 |

---

## 2 关键判定（逐条回答控制器点名的问题）

### ① 终态 CAS / 取消行为：未被动过，且新路径正确落在 CAS 语义内

- 零条目路径**先写 issue、后登记终态**，且不经过 `markRunning` ⇒ 作业 `PENDING → FAILED`。`JobProgressService.finish` 的条件 UPDATE 判据是 `IN_FLIGHT = {PENDING,RUNNING}`（`JobProgressService.java:89-95, 136-165`），因此 PENDING 直接收 FAILED 是被支持的；`startedAt` 为 null、`finishedAt` 有值（报告 §5⑤ 已登记，非缺陷）。
- 与取消的竞态：取消方先提交 ⇒ 新的 FAILED 被条件 UPDATE 丢弃（`JobTerminalWriter.java:113-141`：`wroteTerminal=false` 时不联动任务态、不发 JOB_DONE）；零条目先提交 ⇒ 取消得到既有 409 语义。**与 EXPORT 现有 afterCompletion 收敛完全同构**，没有引入第二套终态判据。
- 回滚兜底走 `completeNow`（`JobTerminalWriter.java:98-101`）而不是 `complete` —— 这一点是对的：`complete` 在 `afterCompletion` 里注册 `afterCommit` 将永不执行。
- `afterCompletion` 只放行 `STATUS_COMMITTED`，`ROLLED_BACK`/`UNKNOWN` 都收敛为 FAILED —— 对"提交结局不确定"取保守，合理。
- 兜底不会被基础设施故障吞掉：`JobHeartbeatRegistry.beat/renewRunnerActive/writeSharedBeat` 内部对 Redis 异常只 WARN（`JobHeartbeatRegistry.java:625-655`），`finish` 的 `heartbeatRegistry.clear` 在 finally；即使 `apply` 抛错也被 `JobTerminalWriter.apply` 的 try/catch 兜住（此时退回看门狗 30min 上限，仍是有界）。

### ② 任务态副作用：成立且是本次修复的核心价值，但有一项副作用未在报告中显式登记

- `TaskService.applyJobOutcome`（`TaskService.java:319-343`）把作业 FAILED 映射为任务 FAILED，只对 CANCELLED 吸收态设闸。
- 关键的产品后果是对的：`JobService.finalizedConflict`（`JobService.java:91-116`）对 FAILED 放行 ⇒ 零条目误触不再把任务永久锁死（原缺陷本体）。
- 前端不会因此变成死局：`ExportWizardView.vue:310-316`、`ImportWizardView.vue:402-408` 的 `isReadOnly`、`stores/workspace.ts:419-431` 的只读判据都不含 FAILED ⇒ 向导仍可编辑、可重跑。
- **未登记的副作用**：一次零条目 `start_export`（用户在配置中途误触）会把**配置中的任务**从 ACTIVE 打成 FAILED（步进条显示"已失败"）。报告只登记了"PENDING→FAILED（不经 RUNNING）"，没登记"任务被标 FAILED"。这可接受（FAILED 可恢复、且比假 COMPLETED 诚实），但建议控制器确认这是期望语义。

### ③ no-issue 回落：零条目四链都有作业级 issue；回滚兜底刻意无 issue，前端有回落文案

- 四条零条目链都写 `defCode="-"` + `severity=ERROR` + 可读 message（`ExportJobRunner.java:71` 的哨兵注释、`ImportJobRunner.java:229`、`PrecheckJobRunner.java:355`、`PublishJobRunner.java:81`）。`defCode="-"` 在前端明细区不分组（Export 注释已登记的既有缺口），但 `errorCount=1` 且 issue 存在，导出向导 `jobErrorMessage`（`ExportWizardView.vue:317-325`）能取到首条 ERROR 文案。
- 回滚兜底写 FAILED + `errorCount=1` 但**不写 issue**（报告 §5③）。前端在"无 issue 明细"时回落到 `t('views.export.jobFailed', {count})`，不会出现空白失败卡。作为"有界终态"的最小实现可接受；若要"用户可读原因"，需显式 `REQUIRES_NEW` 写一条 issue —— 属可加项，不是缺口。
- 一处口径不一致（Minor，见 M6）：Export 在零条目判断**之前**做了 `issueRepository.deleteByJobId`，Publish 的清理在 `doPublish` 里（零条目路径直接 return，旧 issue 不清）。作业一次性，实际无影响。

### ④ 把校验放 runner 而不是 `createAndStart`：**保留了合法守卫用例，判定成立**

- 我核对了报告引用的证据类：`TaskFinalizedGuardTest` 的 8 例**刻意**在零条目任务上 `createAndStart` 并断言 `PENDING`（例如 `completedImportWithoutPublishJobNotRejectedAsFinalized`，注释明写"本任务无条目/无文件…作业应成功创建"）。若把拒绝前移到 `createAndStart`，这些用例语义被改写成"校验期拒绝"，而不是"守卫放行"——那不是修 bug，是改接口契约（REST `POST /tasks/{id}/jobs`、AI `start_*` 从"建了再失败"变成 409/400）。
- 因此"runner 侧收口 + 不动 `JobService`"是**最小且不打断既有守卫矩阵**的选择；brief 只要求"零条目作业不得 COMPLETED"，未要求"建作业期即拒"。
- 代价（报告已登记）：`start_*` 仍先回"作业已启动"，毫秒后 FAILED。若产品要求"建作业期即拒"，建议单列一批（需重写 8+ 用例），或先在 `ToolPreflightService` 加只读提示（不动 `createAndStart`，零用例风险）——见 M9。

### ⑤ 新增 result-view 默认方法的接口兼容性：**安全**

- `ToolResultViewProvider.java:49-51` 的 `default build(..., boolean historical)`：源码/二进制均兼容（3 参抽象方法保留）。
- 全仓**唯一实现**是 `ListTasksResultViewProvider`（`grep "implements ToolResultViewProvider"` = 1），且没有对接口做 Mockito mock（`grep "ToolResultViewProvider.class"` = 0）⇒ 默认方法不会被 mock 语义打穿。
- 实时帧路径仍是 3 参：`SpToolCallingManager.java:1531-1545`（`resultViewOf` 未改）⇒ 实时帧继续走结构化数据源，`ListTasksResultViewProviderTest` 6 例语义不变。
- 设计层面的欠账（Minor，非缺陷）：默认实现意味着**未来的新投影器默认继续"用今天的库改写归档"**，即 BZ-33 的缺陷类会以"默认值"形式复发。当前只有 1 个实现，风险为 0；建议在接口注释里点明"未覆写 = 未迁移"，或在后续批改成强制声明。

### ⑥ 历史文本解析：准确性、最小性、是否引入新的污染（重点）

**判定：对"当前格式 + 未截断 + 同会话"的归档是精确的；失败姿态（不出视图 ⇒ 回落原文）比"用今天的库补表"更诚实，不构成新的内容污染。** 依据如下：

1. **同一字符串双通道，不可能自相矛盾**：`AiController.java:1397` 的 `responseData`（卡面原文）与 `:1486` 传给投影器的 `resultText` 是同一次 `historyMessageText(...)` 的产物 ⇒ 表格与原文不会打架（这正是 BZ-33 要的）。
2. **生产者/消费者共用常量 + 逐字锁**：`AiTools.java:176-190` 抽常量而 `listTasks()` 输出逐字不变（我逐字比对过 format 串：`- #%d [%s] %s 步骤:%s 状态:%s` ≡ `%s%d [%s] %s%s%s%s%s`）；`producerTextKeepsItsExactShape` 用**字面量**钉死生产者输出（含"（共 21 条，仅显示前 20 条）"），`producerOutputRoundTripsThroughTheArchiveProjection` 用真 `AiTools` 做往返 ⇒ 常量值本身漂移会被抓到，不是自指闭环。
3. **旧会话的"JSON 二次编码"形态是 fail-closed，不是畸形行**：仓内 `ToolResultLimiter` 明确记录后端 String 结果曾是"字面 `\n`"形态（2026-10-07~10-23，L-88 前）。我核对了 spring-ai 1.1.7：`DefaultToolCallResultConverter.convert` 对 String 走 `JsonParser.toJson`，而 `JsonParser.toJson` 对非 JSON 文本执行 `writeValueAsString` ⇒ 结果**带外层双引号**（首字符 `"`）⇒ `startsWith("- #")` 不成立 ⇒ `rows` 为空且非空态文案 ⇒ 返回 `null` ⇒ 回落原文。**所以"旧会话 ⇒ 不出视图"的结论成立，但旧会话的成因是"整段被引用包裹"，"行被切断"只对 L-88 之后（无引号）的截断成立** —— 报告 §④ 把两者并成一条，属描述精度问题，不影响实现。
4. **真实截断形态**：`ToolResultLimiter.forModel` 的字符截断是"按字符切 + 追加 `\n…[结果已截断：…]`"（`ToolResultLimiter.java:46, 231-244, 280-292`），切点落在行中 ⇒ 末行缺 ` 状态:` ⇒ `parseRow` 返回 null ⇒ 不出视图。✅
5. **"最小"判定**：不新增持久面、不动 DDL/消息编解码，符合限定；用 ~124 行换回"表格卡面"，相比"归档干脆不出视图"的 1 行方案是 UX 增量，不是必需 —— 但没有带来额外风险，属可接受的工程取舍（也是"文本即协议"这笔旧债的显式登记：`AiTools.java:167-176` 注释已写明两侧必须共用常量）。
6. **仍可被文本欺骗的三个窄口**（都列在 M2/M3/M4，其中两个我判定不可达或极低概率）：
   - **幻影行**：任务标题含换行且续行以 `- #` 开头 ⇒ 多出一行"任务"。原文里也是同样两行，属"文本即协议"的固有代价；要根治需在生产侧转义标题（超范围）。
   - **字面 `\n` 变体**：只按真实 `\n` 切行；若某历史形态是"以 `- #` 开头 + 字面 `\n`"（无外层引号），整段会被当一行，产出一行"标题吞掉后续行"的畸形行。当前实测形态带引号（见第 3 点）⇒ 不可达；建议仍加 2 行防御（含字面 `\n` ⇒ 不出视图）。
   - **截断标记未识别**：若切点恰好落在某行末尾，N 行都合法 ⇒ `totalRowsOf` 找不到"（共 N 条）"（它在文末被切掉）⇒ `status=ok` 且无 `meta.truncated`，把"截断结果"呈现为"完整列表"。可达性极低（`list_tasks` ≤20 行，模型预算 20000 字符 / 200 行，见 `application.yml:352-359`），1 行加固即可。

---

## 3 问题清单

### Critical
无。

### Important
无。

### Minor

| # | 位置 | 问题 | 建议 |
|---|---|---|---|
| M1 | `backend/src/main/java/com/example/configmgr/ai/web/AiController.java:250-257`；`backend/src/test/java/com/example/configmgr/ai/resultview/ListTasksResultViewProviderTest.java:23` | **陈旧注释**：这里仍写"历史面按工具名重建、契约是结构化数据源（禁反解 resultText）"，与本次"归档必须反解原文"正好相反。补丁更新了 `ToolResultViewProvider` / `ListTasksResultViewProvider` 的类注，漏了这一处与测试类头 | 改一句"实时帧禁反解；归档（`historical=true`）必须用当时原文" |
| M2 | `backend/src/main/java/com/example/configmgr/ai/resultview/ListTasksResultViewProvider.java:123-153`（+`totalRowsOf:193-209`） | 未识别 `ToolResultLimiter.TRUNCATION_MARKER`：极端情况下"被截断的结果"会以 `status=ok`、无 `meta.truncated` 呈现为完整列表 | `fromPersistedText` 开头加：原文含 `ToolResultLimiter.TRUNCATION_MARKER` ⇒ 返回 `null`（回落原文） |
| M3 | `ListTasksResultViewProvider.java:129-141` | 只按真实 `\n` 切行；未防"字面 `\n`"形态（仓内 `ToolResultLimiter` 明确两种形态并存）。若出现"以 `- #` 开头 + 字面 `\n`"的旧形态，会产出一行被吞掉后续行的畸形行（当前实测形态带引号 ⇒ 不可达） | 加 2 行防御 + 1 例单测："整段含字面 `\n` ⇒ 不出视图" |
| M4 | `ListTasksResultViewProvider.java:156-190` | `parseRow` 只校验非空，不校验 `type/step/status` 是否属于枚举；位置偏移/边界截断可能产出 `status="ACTI"` 这类"看似合法"的值 | 用既有枚举做白名单校验（或明确接受，登记为 Minor） |
| M5 | `ImportJobRunner.java:248`、`PrecheckJobRunner.java:372`（+ `ExportJobRunner.java:87-100` 内联版） | `registerRollbackConvergence` 三份近似复制；`jobLevelIssue` 两份复制 + 三处文案硬编码 | 抽到 `JobTerminalWriter`（如 `completeOnRollback(job, errorCount, processed, total, event)`），后续改口径只改一处 |
| M6 | `PublishJobRunner.java:87` | 零条目判断额外多查一次 `findByTaskIdOrderBySortOrder`（`doPublish` 里还会再查）；且 Publish 零条目路径未先 `deleteByJobId`（Export 有），口径不一致 | 把首查结果传入 `doPublish`；或零条目分支先清 issue（一次写，无实际影响） |
| M7 | `backend/src/test/java/com/example/configmgr/job/service/JobRollbackTerminalFallbackTest.java:131-145`；`backend/src/test/java/com/example/configmgr/job/service/ZeroItemJobTerminalTest.java:157-160` | 断言偏弱：IMPORT/PRECHECK/PUBLISH 只断言 `task.status != COMPLETED`（ACTIVE 也能过），而实现写的是 FAILED；回滚类没断言 `errorCount`、也没断言任务态联动 | 断言 `FAILED`（当前实现确实写 FAILED）；回滚类补 `errorCount>0` + `task.status==FAILED` |
| M8 | `PublishJobRunner.java:87-95` | 只判"任务零条目"；"有配置项但零暂存行"仍 COMPLETED（报告拿 FE-49 作动机之一，但未覆盖）。按 brief 的"零条目"字面口径**合规** | 请控制器确认是否要扩到"零行"；扩则属新范围，需单列 |
| M9 | `JobService.java:157`（未改） | `start_*` / `POST /jobs` 在零条目时仍先返回"已启动"（201），毫秒后 FAILED | 报告 §5① 已登记；若不允许这种"先成功后失败"，建议先在 `ToolPreflightService` 加只读提示，或单列一批改 `createAndStart` 并重写 8+ 守卫用例 |

---

## 4 测试真实性核查（是否自我实现）

**RED 证据（`red-1.log`，我逐条复核了日志正文）**：`Tests run: 25, Failures: 9`，失败信息与报告一致且指向缺陷本身——

- `ZeroItemJobTerminalTest` 4 例：`expected: FAILED but was: COMPLETED`；
- `JobRollbackTerminalFallbackTest` 2 例：`Expecting actual: RUNNING not to be in: [PENDING, RUNNING]`；
- `AiControllerHistoryPayloadTest` 3 例：`rows[0].id expected:<7> but was:<9>`、`No value at JSON path`、`Expected no value ... but found: {status=empty...}`（旧格式文本被"补"成今天的空表）。

**GREEN（`green-1.log` / `green-2.log`）**：`Tests run: 49, Failures: 0`（4+2+6+6+19+4+8），补入第 7 例后 `ListTasksHistoryProjectionTest` 7/7。数字与报告逐项一致。

**非自我实现的判据**：

| 用例 | 为何不是自我实现 |
|---|---|
| `ZeroItemJobTerminalTest`（4） | 真实 H2 + 真实四执行器（同线程直驱，无 mock 仓库、无桩终态）；断言读 `jobRepository`/`issueRepository`/`taskRepository`；RED 证明它能抓到 COMPLETED |
| `JobRollbackTerminalFallbackTest`（2） | 外层 `TransactionTemplate.setRollbackOnly()` = "数据面整批丢弃"的同义形态；该形态下 `complete` 的 `afterCommit` 必然不执行（`JobTerminalWriter.java:81-91`）⇒ 断言 FAILED 只能来自新增兜底；RED 实测 RUNNING 亦证明 |
| `ListTasksHistoryProjectionTest`（7） | 归档行值与当前库刻意不同（#7 vs #9）+ `verify(never()).findAll()`；不可解析文本要求 `null`（RED 实测旧实现产出 `status=empty` 的库表） |
| `AiControllerHistoryPayloadTest`（+2） | 走真 MockMvc + 真控制器；断言卡面字段值而不是"有没有调用" |
| `producerOutputRoundTripsThroughTheArchiveProjection` | 用**真** `AiTools.listTasks()` 产出文本再喂归档投影，不是复刻字面量 |

**一处可挑的自指（不致命）**：`archivedTruncationKeepsTheTotalsOfThatMoment` 用 `AiTools.LIST_TASKS_TOTAL_PREFIX/SUFFIX` 常量**造输入**，两侧会一起漂移；但 `producerTextKeepsItsExactShape` 用字面量钉死生产者输出（含"（共 21 条，仅显示前 20 条）"），漂移仍会被抓到 ⇒ 可接受。

**覆盖缺口**：

- 零条目 × 取消竞态未单测（CAS 有既有类覆盖，但新路径未直接覆盖）；
- "零条目 + 提交期回滚"叠加只做了推理（报告 §5⑤），无断言；
- PUBLISH 零暂存行（M8）未覆盖；
- 报告自己引用为"为何不改 `createAndStart`"的 `JobMutexGuardTest` / `RunnerActiveMultiInstanceTest` / `TaskDelete*` **不在本次 4 个相邻类里**，未复跑。我做了静态核对：这些类只断言 `createAndStart`/CAS 结果（例如 `JobMutexGuardTest.terminalJobsDoNotBlockNewOne` 只断言 201），不断言 runner 终态 ⇒ 回归风险低，但**全量套件仍欠一次**；
- 旧格式（JSON 二次编码 / 带引号）历史的"回退原文"行为无单测（M3）。

---

## 5 补丁可用性与改动面

- `git apply --check .superpowers/sdd/2026-10-25-ai-remediation/task-6.patch`（主线，未落盘）⇒ **13 个文件全部 OK**（exit 0）。
- 改动面与报告一致：生产 8 + 测试 5；未触碰 `JobService` / `JobTerminalWriter` / `JobProgressService` / `JobWatchdog` / `StaleJobRecoveryRunner` / `AsyncJobExecutor` / config / 前端 / DDL。
- 生产侧新增代码不引入新依赖、不新增表/列、不改消息编解码 ⇒ 不构成新的持久面。
- `AiTools.listTasks()` 输出经逐字比对确认**未变**（format 串与"（共 N 条…）"告知等价替换，模型侧看到的口径无漂移）。

---

## 6 未验证 / 后续

1. **未跑任何测试**（按要求）；以上关于 GREEN 的结论来自日志，不来自我的复跑。
2. 未验证前端渲染；"无 resultView ⇒ 回落原文"是依据 `frontend/src/utils/blocks.ts:529-546`（`hasToolBody` 与 resultView/原文字段）判断。
3. 未验证多实例/真 Redis（副本用 `redis.port=1` + memory 存储）。
4. 存量"已被假 COMPLETED 锁死"的任务未修（报告 §④ 已登记，业务数据零变更）。
5. 建议合并前：跑一次后端全量（本次只跑 7 类 49 例）；按 M1 更新陈旧注释；M2/M3 各加 1 行 + 1 例属低成本的顺手加固。

---

## 7 一句话给控制器

三条需求都是先红后绿、CAS/取消语义零改动、接口向后兼容、补丁可干净应用；**没有 Critical/Important**。历史归档的"反解原文"在真实形态下是精确的（失败即回落原文，不会用今天的库改历史），残留风险只集中在"截断标记/字面 `\n` 变体/枚举值未校验"这三个窄口（M2/M3/M4），以及两处陈旧注释（M1）。建议合并，合并前补一次全量后端套件。
