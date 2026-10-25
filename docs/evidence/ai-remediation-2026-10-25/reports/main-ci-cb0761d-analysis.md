> 来源=<仓外证据>/main-ci-cb0761d-analysis.md（2026-10-25 归档）· 仓外正式过程报告，属历史证据，不代替现行基线/决策文档（docs/02、docs/03、docs/adr/）
# main CI 40192492193 @ cb0761d · 后端单测失败根因分析

> 只读取证：仓库仅读取，未改代码、未起服务、未重跑 workflow。
> 失败日志 `<仓外证据>/main-ci-cb0761d-failed.log`（20599 行，合并各 job）。
> 代码行号对应 commit `cb0761dd46db31b34d80e1335013bb4b4b8a3d47`；本地
> `<主仓>` 工作区虽脏，但脏文件不含 `PublishJobRunner.java` 与
> `PublishImportModeFailFastTest.java`，故行号与提交一致。

## 0. 结论摘要

| 项 | 结论 |
|---|---|
| 失败 job | `后端编译与单测`（121166308316），4m29s，`mvn -B test` |
| 编译 | **通过**（surefire 已执行，非编译错） |
| 口径 | `Tests run: 2684, Failures: 1, Errors: 0, Skipped: 2` |
| 唯一失败 | `PublishImportModeFailFastTest.unparsableImportModeFailsWholeBatchWithJobLevelIssue:83` |
| 失败断言 | 期望消息 `startsWith("发布过程出错: ")`，实际 `"任务 #2 没有任何配置项（0 条目），发布作业没有可发布的数据，作业未成功"` |
| 根因 | cb0761d 新加"零条目发布作业"守卫位于 `doPublish` 之前；该用例夹具**从未插入 TaskItem**，守卫抢先返回 ⇒ 解析失败路径不可达 |
| 定性 | **测试缺陷（夹具失效 / 断言过时）**；产品行为是 cb0761d 有意设计，不是产品回归，也不是环境噪声 |
| 影响面 | frontend 因**变更集仅命中 backend** 而跳过（非因后端失败）；E2E 因 `backend=failure` 跳过；汇总 job 报红（`门禁汇总` 121167312609） |
| 最小修复 | 用例里给任务补 1 个 TaskItem（1 行），不改产品代码 |
| 是否回归 | 是：上一个 commit `34fd8f4` 后端 job 绿（4m26s），该测试文件自 `8415540` 起未改 |

## 1. 失败现场（日志原文）

`main-ci-cb0761d-failed.log:17376-17389`：

```
[ERROR] Tests run: 3, Failures: 1, Errors: 0, Skipped: 0 ... in ...PublishImportModeFailFastTest
[ERROR] ...PublishImportModeFailFastTest.unparsableImportModeFailsWholeBatchWithJobLevelIssue -- Time elapsed: 0.007 s <<< FAILURE!
java.lang.AssertionError:
Expecting actual:
  "任务 #2 没有任何配置项（0 条目），发布作业没有可发布的数据，作业未成功"
to start with:
  "发布过程出错: "
	at ...PublishImportModeFailFastTest.unparsableImportModeFailsWholeBatchWithJobLevelIssue(PublishImportModeFailFastTest.java:83)
```

收尾（`20479-20492`）：`[ERROR] Tests run: 2684, Failures: 1, Errors: 0, Skipped: 2` → `BUILD FAILURE` →
`maven-surefire-plugin:3.5.5:test ... There are test failures`。

同一类里另两条用例（`missingImportModeStillFallsBackToMerge`、`emptySettingsObjectStillFallsBackToMerge`）
通过 —— 它们走 `run(...)` 辅助方法，**会插入 TaskItem**（`PublishImportModeFailFastTest.java:150`），
所以不受新守卫影响。这条对比本身就说明失败取决于夹具，而不是类上下文/顺序。

## 2. 根因（path:line）

### 2.1 产品侧新增的零条目守卫

`backend/src/main/java/com/example/configmgr/job/service/PublishJobRunner.java:84-95`（cb0761d 新增）：

```java
public void run(Job job) {
    if (taskItemRepository.findByTaskIdOrderBySortOrder(job.getTaskId()).isEmpty()) {   // :88
        saveConflictIssue(job, JOB_LEVEL_DEF_CODE, null,
                "任务 #" + job.getTaskId() + " 没有任何配置项（0 条目），发布作业没有可发布的数据，作业未成功"); // :89-90
        terminalWriter.complete(job, Job.JobStatus.FAILED, 1, 0, 0, 0, ...);            // :91-93
        return;                                                                          // :94
    }
    progressService.markRunning(job.getId(), 0);                                         // :96
```

它在 `markRunning` / `transactionTemplate.execute(doPublish)` 之前就返回，
因此 `doPublish` 里的 `resolveImportMode`（`PublishJobRunner.java:140`）及其
`UNPARSEABLE → IllegalStateException`（`:141-144`）**根本不会执行**，`recordFailure`
（`:314-326`）里拼 `"发布过程出错: " + cause.getMessage()` 的分支也不会执行。

### 2.2 测试夹具

`backend/src/test/java/com/example/configmgr/job/service/PublishImportModeFailFastTest.java:61-85`：

- `:67-69` 建任务并把 `settings_json` 写成截断 JSON；
- `:71-72` 直接 `newJob(task)` + `publishJobRunner.run(job)`，**没有** `taskItemRepository.save(item(task, DEF))`；
- `:83` 断言 `startsWith("发布过程出错: ")`。

对照同文件 `run(...)` 辅助方法 `:149-158`（`:150` 插入 TaskItem）和
`PublishSemanticsTest.java:127`（同样先插 TaskItem 再跑），可见这条用例一直缺 TaskItem；
在 cb0761d 之前这不影响结果（解析失败发生在遍历条目之前），加入零条目守卫后夹具前提被打破。

### 2.3 为什么只有文案断言红

守卫与 `recordFailure` 落库的**终态完全同构**：`FAILED`、`errorCount=1`、`def_code="-"` 的作业级
ERROR issue 一条（`PublishJobRunner.java:300-308` vs `:314-326`）。日志里失败块只包含
`startsWith` 一条断言，说明 `:75-81`（状态 / 错误数 / issue 条数与严重级）**全部通过**。
这进一步排除"零条目把产品终态改坏了"的可能：差异只在消息来源。

## 3. 为什么不是"仅全量套件才复现"的环境噪声

1. **绿跑 50/50 未覆盖该类**：`task6-clean-targeted.log` 与 `task6-main-targeted.log` 都只跑
   7 个类、50 条用例（`ListTasksHistoryProjectionTest` 7、`ListTasksResultViewProviderTest` 6、
   `AiControllerHistoryPayloadTest` 19、`ExportFlowJobTest` 4、`JobRollbackTerminalFallbackTest` 2、
   `TaskFinalizedGuardTest` 8、`ZeroItemJobTerminalTest` 4），日志中没有
   `PublishImportModeFailFastTest`。所以"50/50 全绿"与 CI 红不矛盾，也不构成反证。
2. **确定性**：守卫只依赖 `task_item where task_id=?` 是否为空；该用例对该任务从未插入条目，
   与执行顺序、并发、Redis 可用性、H2 实例状态无关（该类用独立的
   `jdbc:h2:mem:r96-publish-failfast`）。单独跑这条用例同样会红。
3. **是回归而非偶发**：`34fd8f4` 的 backend job 绿（`main-ci-34fd8f4-watch.log:1996`
   `✓ 后端编译与单测 in 4m26s`），测试文件最后一次修改是 `8415540`（2026-10-11，已是
   `34fd8f4` 的祖先），本 commit 未改该测试 ⇒ 红是 cb0761d 的产品改动与旧夹具冲突。
4. **全量面收敛**：2684 条里只红 1 条，其余（含新加的 `ZeroItemJobTerminalTest` 4 条、
   `JobRollbackTerminalFallbackTest` 2 条）全绿，不是 JVM/机器级故障。
5. **locale 无关**：断言与被断言的文案都是 Java 字符串常量（`PublishJobRunner.java:90`、
   `:317`），不走 `messages_zh/en.properties`，`LANG`/`user.language` 不影响；CI backend job
   也没有设置 locale 参数（`.github/workflows/ci.yml:95-121`）。`docs` job 的 i18n 残留扫描是
   **报告制**且面向前端，本次 docs job 绿。
6. **无跨 job 交互**：失败发生在 backend job 内；frontend job 的 skipped 是变更集判定
   （`FRONTEND_NEEDED=false`、`命中规则=backend`，见 `gate-summary.txt`），E2E 的 skipped 才是
   `needs.backend.result==success` 不满足。汇总 job 只是如实记账报红。

## 4. 实际产品行为判定

- cb0761d 的提交说明第一条就是"零条目作业有界失败并补回滚收敛"，四个 runner（Import / Export /
  Precheck / Publish）都加了同形守卫（`ExportJobRunner.java:118-124`、`ImportJobRunner.java:234`、
  `PrecheckJobRunner.java:360`、`PublishJobRunner.java:88-95`）。这是**有意的语义变更**而非误改。
- 新增用例 `ZeroItemJobTerminalTest.java:121-126`（`publishJobWithoutTaskItemsMustNotComplete`）
  已锁定"零条目发布 ⇒ FAILED + 有界终态 + 作业级 `-` issue + 任务不得 COMPLETED"，且文案只做
  `contains("配置项")` 的宽松断言（`:139-161`）。产品语义已被新用例覆盖，旧用例的红不是语义丢失。
- 真实链路里发布作业由"有配置项的任务"发起；零条目只在任务项被删空/编排异常时出现。此时
  报"没有任何配置项"比报"settings_json 解析失败"更贴近用户真正要处理的问题，优先级也更高。
- 可选的反向修法（把零条目判断挪到 `resolveImportMode` 之后）能让旧用例原样变绿，但会让
  "零条目 + 坏 settings" 报解析错，掩盖更根本的零数据问题，并需要重写新用例的语义；**不建议**作为首选。

## 5. 最小修复（1 行，测试侧）

`backend/src/test/java/com/example/configmgr/job/service/PublishImportModeFailFastTest.java:70-71`，
在 `Job job = newJob(task);` 之前补：

```java
taskItemRepository.save(item(task, DEF));   // 与 :150 / PublishSemanticsTest:127 同口径
```

效果：任务不再零条目 ⇒ 守卫不触发 ⇒ `doPublish` 在 `:140` 解析出 `UNPARSEABLE` 并抛
`IllegalStateException` ⇒ `recordFailure` 落 `"发布过程出错: ..."` 的作业级 issue，`:83` 断言恢复；
`:87-89`（已发布行数/数据零变化）仍成立（异常发生在任何写之前，事务回滚）。
用例数不变（`PublishImportModeFailFastTest` 仍 3 条），全量口径不变。

**建议同时做（可选、非必需）**：把"零条目优先于 settings 解析"的顺序语义钉进
`ZeroItemJobTerminalTest.assertZeroItemFailure`（对 PUBLISH 断言精确文案，而不只是
`contains("配置项")`），避免以后有人为救旧用例把守卫顺序调回去，导致两条用例互斥拉锯。

**验证方式（本次按要求未执行）**：
`cd backend && mvn -Dtest=PublishImportModeFailFastTest test`，再 `mvn -B test` 全量；
然后由 CI backend job 复核。

## 6. 其他观察（均不构成本次红）

- 守卫路径没有 `issueRepository.deleteByJobId`（`doPublish` 在 `:134` 才删）。若同一 `jobId`
  会被重派（僵尸作业恢复/重试复用同一 job 行），零条目路径可能累积多条 issue 且
  `errorCount` 仍为 1。本次无证据表明存在同 jobId 重派，仅记为潜在不一致。
- 守卫与 `doPublish` 各查一次 `task_item`（重复查询），可忽略。
- `ExportJobRunner` 同类守卫用方法参数 `taskId` 拼文案（`:120`），Publish 用 `job.getTaskId()`
  （`:90`），语义一致，无缺陷。
- 本 commit 的 history/result-view 改动（`ListTasksHistoryProjectionTest`、`AiControllerHistoryPayloadTest` 等）
  在两份 50 用例定向跑与 CI 全量里都绿，与本次红无关。

## 7. 引用

- 失败日志：`main-ci-cb0761d-failed.log:17376-17389`、`:20479-20492`、`:20582-20599`
- 产品：`<主仓>/backend/src/main/java/com/example/configmgr/job/service/PublishJobRunner.java:84-95`、`:140-144`、`:300-308`、`:314-326`
- 测试：`.../job/service/PublishImportModeFailFastTest.java:61-85`、`:141-158`
- 新语义锁：`.../job/service/ZeroItemJobTerminalTest.java:121-126`、`:139-160`
- 对照：`.../job/service/PublishSemanticsTest.java:127`、`:141`
- 定向跑：`_p2-review/task6-clean-targeted.log:58-1235`（7 类 50 条，无本类）
- 上一个 commit 绿：`_p2-review/main-ci-34fd8f4-watch.log:1996`
- 证据目录：`_p2-review/main-ci-cb0761d-evidence/`（surefire 未归档，见其 README）
