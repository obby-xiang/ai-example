# Task 6 报告：作业与历史真实性（BZ-01 / BZ-02 / BZ-33）

- 施工副本（唯一改动地）：`<隔离副本>/_verify-20261025-job-history/backend`（由当前 main 全量复制，含 `docs/kb-samples`）
- 主线：`<主仓>` —— **零改动**（只 `git apply --check` 干跑，未落盘；`git status` 与开工前逐项一致）
- 补丁：`<隔离副本>/_verify-20261025-job-history/task-6.patch`（同件副本：`.superpowers/sdd/2026-10-25-ai-remediation/task-6.patch`）
- 未提交、未推送、未跑全量、未 `clean`；Maven 用 IDEA 内置 `maven3/bin/mvn.cmd`，`-o`，测试 Redis 端口 `1`

---

## 0. 结论先行

| 审查项 | 判定 | 处置 |
|---|---|---|
| BZ-01 零条目 `start_*` 假成功（四链 COMPLETED，EXPORT 还把任务永久 `TASK_FINALIZED`） | **确认是缺陷**（RED 实测） | 四个执行器各加"零条目 = FAILED + 作业级 issue"收口 |
| BZ-02 IMPORT/PRECHECK 缺提交期回滚兜底（停在 RUNNING 挡重跑，等看门狗 30min） | **确认是缺陷**（RED 实测） | 两处补 `afterCompletion` 回滚收敛（与 EXPORT 同款） |
| BZ-33 `/history` 的 `list_tasks` 用当前库重投影 | **确认是缺陷**（RED 实测，且实测到 rows 直接从 7 变成 9） | 归档通道改用**当时持久化的结果原文**，实时帧路径逐字不动 |
| 计划里点名的 `JobService.java` | **不需要改** | 见 §5-①：改在 `createAndStart` 会打红 4+ 个既有守卫类，产品事实由 runner 收口同样达成 |

RED→GREEN 全部为真实 H2 + 真实 Bean，无 mock 仓库、无桩终态。

---

## 1. 精确改动（8 个生产文件 + 5 个测试文件）

### 生产（+317 / −11）

| 文件 | 改动 | 为什么最小 |
|---|---|---|
| `job/service/ExportJobRunner.java` | +12：`items.isEmpty()` ⇒ 作业级 ERROR issue（`defCode="-"`）+ `terminalWriter.complete(FAILED, errors=1, 0/0)` + `return` | 不改循环、不改分母、不改成功路径；关键是**不再带 `successStep="EXPORT"`**，任务不会被写成 COMPLETED/EXPORT |
| `job/service/ImportJobRunner.java` | +56：① `run` 开头 `registerRollbackConvergence(job)`；② `items.isEmpty()` 收口；③ 两个私有辅助（`jobLevelIssue` / `registerRollbackConvergence`） | 循环体与数据面零改动 |
| `job/service/PrecheckJobRunner.java` | +52：同 Import（文案改为"预检查没有可校验的数据"） | 同上 |
| `job/service/PublishJobRunner.java` | +14：`run` 里 `markRunning` 之前做零条目收口，复用既有 `saveConflictIssue(job, "-", null, msg)`；新增 `JOB_LEVEL_DEF_CODE` 常量 | `doPublish` 事务体零改动 |
| `ai/tools/AiTools.java` | +31/−4：把 `list_tasks` 行格式/空态/超限告知抽成 6 个公开常量，`listTasks()` 用它们拼装（**输出逐字不变**），并把"归档投影按同一组常量反解"写进注释 | 只是把字面量换成常量；新增 `producerTextKeepsItsExactShape` 用例逐字钉住 |
| `ai/resultview/ToolResultViewProvider.java` | +20/−2：新增 `default build(id, name, text, historical)`（缺省回落 3 参版）；修订类注释 —— "实时帧必须用结构化数据源"这条**保留**，另立"归档例外，方向相反" | 缺省实现让其它投影器行为逐字不变 |
| `ai/resultview/ListTasksResultViewProvider.java` | +124/−4：`historical=true` 分支 `fromPersistedText`（按 `AiTools` 常量反解 + 还原"共 N 条"），`parseRow` / `totalRowsOf`；3 参 `build`（实时帧，查库）**一字未改** | 两条通道彻底分开：实时帧照旧查库，归档绝不查库 |
| `ai/web/AiController.java` | +8/−1：仅 `historyResultView` 内把 `provider.build(id, name, text)` 改成 `provider.build(id, name, text, true)` + 注释 | **只动历史投影**，`/chat`、`/events`、`/runs` 全未触碰 |

### 测试（+583 / −12）

- 新增 `job/service/ZeroItemJobTerminalTest.java`（4 例，真实 H2 + 真实四执行器同步驱动）
- 新增 `job/service/JobRollbackTerminalFallbackTest.java`（2 例，外层事务 `setRollbackOnly` 复现"整轮数据面被丢弃"）
- 新增 `ai/resultview/ListTasksHistoryProjectionTest.java`（7 例：归档不查库 / 当时的 N 条 / 空态 / 不可解析⇒不出视图 / 实时帧仍查库 / 生产↔归档往返 / 回填文本逐字锁）
- 改 `ai/web/AiControllerHistoryPayloadTest.java`（+84/−11）：`historyDerivesResultViewByToolName` 改用完整行格式并让库返回空表；新增 `historyListViewKeepsTheResultOfThatMomentNotTheCurrentDatabase`（库给 #9，卡面必须是 #7，且 `verify(taskService, never()).findAll()`）与 `unparseableHistoryTextYieldsNoViewInsteadOfLiveRows`
- 改 `ai/resultview/ListTasksResultViewProviderTest.java`（+3/−1）：仅修订 kill 注记（反解文本的是**归档**分支，实时帧不许反解）

**未触碰**：`ResumeService` / `ConfirmGate` / `SpToolCallingManager` / `RunStore` / `JobService` / `JobTerminalWriter` / `JobProgressService` / `JobWatchdog` / `StaleJobRecoveryRunner` / `AsyncJobExecutor` / config / 前端 / 任何 DDL 与业务表。

---

## 2. RED（先写测试，只跑聚焦类）

命令（副本 `backend/` 下，日志 `red-1.log`）：

```
"<本机工具链>/JetBrains/IntelliJ IDEA/plugins/maven-plugin/lib/maven3/bin/mvn.cmd" -o -s maven-settings.xml \
  -Dtest=ZeroItemJobTerminalTest,JobRollbackTerminalFallbackTest,AiControllerHistoryPayloadTest \
  -DfailIfNoSpecifiedTests=false test
```

`Tests run: 25, Failures: 9` —— 9 条红，逐条都指向缺陷本身：

```
[ERROR] ZeroItemJobTerminalTest.exportJobWithoutTaskItemsMustNotComplete   [零条目作业不得报 COMPLETED] expected: FAILED but was: COMPLETED
[ERROR] ZeroItemJobTerminalTest.importJobWithoutTaskItemsMustNotComplete   expected: FAILED but was: COMPLETED
[ERROR] ZeroItemJobTerminalTest.precheckJobWithoutTaskItemsMustNotComplete expected: FAILED but was: COMPLETED
[ERROR] ZeroItemJobTerminalTest.publishJobWithoutTaskItemsMustNotComplete  expected: FAILED but was: COMPLETED
[ERROR] JobRollbackTerminalFallbackTest.importJobMustNotStayRunningWhenDataPlaneRollsBack    Expecting actual: RUNNING not to be in: [PENDING, RUNNING]
[ERROR] JobRollbackTerminalFallbackTest.precheckJobMustNotStayRunningWhenDataPlaneRollsBack  Expecting actual: RUNNING not to be in: [PENDING, RUNNING]
[ERROR] AiControllerHistoryPayloadTest.historyListViewKeepsTheResultOfThatMomentNotTheCurrentDatabase
        JSON path "$.data.messages[0].toolResponses[0].resultView.data.inline.rows[0].id" expected:<7> but was:<9>
[ERROR] AiControllerHistoryPayloadTest.historyDerivesResultViewByToolName
        No value at JSON path "...resultView.data.inline.rows[0].id"
[ERROR] AiControllerHistoryPayloadTest.unparseableHistoryTextYieldsNoViewInsteadOfLiveRows
        Expected no value at JSON path "...resultView" but found: {...status=empty...}
```

三条历史红的读法（这也是 BZ-33 的正面实证）：
- 归档文本记的是 **#7**，卡面出来的却是库里**今天**的 **#9** —— 重投影坐实；
- 让库返回空表后，历史卡面**连行都没有了**（说明旧实现的行值 100% 来自库，与文本无关）；
- 旧格式文本也照样被"补"成一张库里的空表 —— 归还在用当前库改写历史。

RED 只在副本；生产代码是在全部红之后才动的（`red-1.log` 的 `BUILD FAILURE` 先于任何 `src/main` 编辑）。

---

## 3. GREEN（新增测试 + 4 个相邻类，未跑全量）

命令（日志 `green-1.log`）：

```
mvn.cmd -o -s maven-settings.xml \
  -Dtest=ZeroItemJobTerminalTest,JobRollbackTerminalFallbackTest,ListTasksHistoryProjectionTest,\
ListTasksResultViewProviderTest,AiControllerHistoryPayloadTest,ExportFlowJobTest,TaskFinalizedGuardTest \
  -DfailIfNoSpecifiedTests=false test
```

`Tests run: 49, Failures: 0, Errors: 0, Skipped: 0`：

| 类 | 例数 | 归属 |
|---|---|---|
| `ZeroItemJobTerminalTest` | 4 | 新增 |
| `JobRollbackTerminalFallbackTest` | 2 | 新增 |
| `ListTasksHistoryProjectionTest` | 6 | 新增（另有 1 例随后补入，见下） |
| `ListTasksResultViewProviderTest` | 6 | 相邻① |
| `AiControllerHistoryPayloadTest` | 19 | 相邻② |
| `ExportFlowJobTest` | 4 | 相邻③ |
| `TaskFinalizedGuardTest` | 8 | 相邻④ |

补入"回填文本逐字锁"后再跑新增类（`green-2.log`）：`ListTasksHistoryProjectionTest` **7/7 绿**。

相邻类选择理由：`ListTasksResultViewProviderTest`（实时帧语义须逐字不变）、`AiControllerHistoryPayloadTest`（历史通道）、`ExportFlowJobTest`（既有导出成功链不被零条目收口影响）、`TaskFinalizedGuardTest`（**最容易被 runner 侧收口误伤的一类**：它刻意在零条目任务上建作业并断言 `PENDING`——实测 8 例全绿，说明收口没有反向改守卫语义）。

---

## 4. 补丁可用性（已实证）

1. `cd <主仓> && git apply --check <隔离副本>/_verify-20261025-job-history/task-6.patch` ⇒ **OK，且未落盘**（主线 `git status` 与开工前逐项一致）。
2. 另取一份 main 的全新 `backend/` 副本，`git apply task-6.patch` ⇒ 成功；`diff -r -x target -x data` 与"已跑绿的那棵副本" ⇒ **byte-for-byte 相同**。
3. 补丁按 `--strip-trailing-cr` 生成（LF hunk）：在 `core.autocrlf=true` 的仓里落盘后仍是 CRLF，无 trailing-whitespace 警告；新增文件带 `new file mode 100644`。

补丁规模：13 个文件、1141 行 diff（生产 8 个 + 测试 5 个）。

---

## 5. 取舍与未做（请控制器裁决 / 知悉）

### ① `JobService.java` 故意未改（与计划文件清单的差异）

计划把 BZ-01 定位在 `JobService:156-165`（"四守卫无可执行性"）。我把"空任务校验"放在**四个 runner 的终态收口**，而不是 `createAndStart` 的建作业守卫，理由：

- `TaskFinalizedGuardTest`（8 例）、`RunnerActiveMultiInstanceTest`、`JobMutexGuardTest`、`TaskDeleteJobCreateConcurrencyTest` 都**刻意**在零条目任务上 `createAndStart` 并断言成功 —— 那是有意的守卫矩阵用例；
- 若在 `createAndStart` 里拒绝，会同时打红 ≥4 个守卫类、改变 REST `POST /tasks/{id}/jobs` 与 AI `start_*` 的对外契约（从"建了再失败"变成"直接 409/400"），明显超出"最小修复"，且超出本任务的相邻类预算。

代价（如实登记）：`start_export` 仍会先回"作业已启动"，随后毫秒级落 FAILED + 一条作业级 issue。产品事实上"零数据不假成功、任务不再被假 COMPLETED 钉死"成立。若控制器要求**建作业期即拒**，那是产品裁决 + 守卫用例重写，建议单列。

### ② 历史投影确实"反解文本"了（与接口旧注释相反的一步，请确认）

`ToolResultViewProvider` 原注释写死"不得把 `resultText` 反解回来"。历史归档没有第二条忠实来源（仓规"AI 对话数据不落库"，唯一持久事实就是那段回填文本；`AiController` 的 `historyResultView` 面向的就是它）。因此我：
- **保留**该禁令并限定到实时帧（实时帧仍走服务层结构化数据，`ListTasksResultViewProviderTest` 6 例全绿）；
- 为归档另立通道（`historical=true`），按 `AiTools` 的公开格式常量反解，并补 `producerTextKeepsItsExactShape` 把生产者文本逐字锁住，防止两侧漂移；
- 反解失败（旧会话格式 / 模型侧裁剪切断行）⇒ **不出视图**，卡面回落原文，绝不查库补表。

如果控制器认为"归档也必须结构化"，正确解法是新增 resultView 快照持久面（要 DDL + 消息编解码 + purge/TTL 契约），属 Task 3/8 的文件所有权，本任务不做。

### ③ 回滚收敛的形态（与 EXPORT 对齐，但数字口径不同）

新增的回滚兜底写 `FAILED, errorCount=1, progress=0, total=0`，**不写 issue**；EXPORT 既有的兜底写的是"最后一个已完成项"的进度快照、也不写 issue。选择 0/0 的理由：数据面已整批回滚，此前 `REQUIRES_NEW` 提交的进度是"从未兑现的宣称"，回滚后归零比沿用更诚实。若要在这条路径补一条 issue，需要显式 `REQUIRES_NEW` 写（新事务），本轮未做 —— 属于可加项，不是缺口（`FAILED` + `finishedAt` 已给出有界终态）。

### ④ 未覆盖/未验证边界

- 多实例 + 真 Redis 未跑（按要求 `redis.port=1` + memory 存储）；回滚兜底在单 JVM 验证，跨实例语义未变（它只写条件终态）。
- 模型侧 `ToolResultLimiter` 截断过的历史 `list_tasks`（行被切断）⇒ 归档不出视图（设计如此，已登记为体感降级）。
- 历史归档不修**已被假 COMPLETED 锁死**的存量任务（任务/业务数据不动；不写迁移脚本）。
- `job_items` 零条目判定是"作业开工时"的一次快照；作业运行中新增条目仍不会被处理（既有语义，未改）。
- `check_job_status` / `get_workspace_state` 等其它工具的历史投影：仍走 `build` 默认实现（本任务只认领 `list_tasks`，与 BZ-33 的原始范围一致）。
- 看门狗仍是其它"终态丢失"场景（SIGKILL / 进程停机）的外层上限，未改。

### ⑤ 观察项（未修，仅登记）

- 零条目作业的终态是 `PENDING → FAILED`（不经 RUNNING，因为收口在建 `markRunning` 之前）。纯展示细节，`startedAt` 为 null。
- 回滚兜底与零条目收口都在**同一作业**上可能先后触发（收口已落 FAILED 后事务又回滚）：`finish` 的条件 UPDATE 使其先到者胜，实测无双重写（`green-1` 的 4+2 例即覆盖两种路径各自的单次落写）。

---

## 6. 一句话交付

零条目不再假成功（四链 FAILED + 作业级 issue，任务不再被假 COMPLETED 钉死）、IMPORT/PRECHECK 的回滚有了和 EXPORT 同款的有界终态、`/history` 的 `list_tasks` 卡面改成显示"当时那次调用的结果"而非今天的库 —— 三件事都是先红后绿、CAS/幂等语义未动、业务数据零变更，补丁可直接 `git apply`。
