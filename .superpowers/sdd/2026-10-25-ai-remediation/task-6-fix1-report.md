# Task 6-Fix1 报告：`PublishImportModeFailFastTest` 失效夹具（main CI 40192492193）

- 失败源：main CI `40192492193`（`cb0761d`），唯一红 = `PublishImportModeFailFastTest.unparsableImportModeFailsWholeBatchWithJobLevelIssue`，根因分析见 `<仓外证据>/main-ci-cb0761d-analysis.md`
- 基线：main HEAD `b3e8932`（工作树另有 29 项与本任务无关的未提交候选）
- 施工副本：`<隔离副本>/_verify-20261025-task6-fix1/backend`（由当前 main 工作树全量复制，含 `docs/kb-samples` 供 `copy-kb-samples` 使用）
- 补丁：`<隔离副本>/_verify-20261025-task6-fix1/task-6-fix1.patch`（副本：`.superpowers/sdd/2026-10-25-ai-remediation/task-6-fix1.patch`）
- **生产代码零改动**；主仓零落盘（仅 `git apply --check` 干跑）；未跑全量、未提交/推送；Maven 用 IDEA 内置 `maven3/bin/mvn.cmd -o`，未 `clean`

---

## 0. 结论

| 项 | 结论 |
|---|---|
| 定性 | **测试夹具失效**（不是产品回归）。零条目守卫是 `cb0761d` 有意语义（BZ-01），产品行为正确且已被 `ZeroItemJobTerminalTest` 锁定 |
| 修复 | 纯测试侧 **1 条语句 + 2 行注释**：给该用例的任务补 1 个 TaskItem（复用文件内既有 `item(...)` 辅助 + 既有 `taskItemRepository` 字段） |
| 不动的东西 | 守卫顺序、`doPublish`/`resolveImportMode`、`recordFailure` 文案、既有断言（仍要求 `startsWith("发布过程出错: ")` + `contains("settings_json 解析失败")` + `contains("不静默降级 REPLACE→MERGE")`） |
| 用例数 | 不变（`PublishImportModeFailFastTest` 仍 3 条） |
| 证据 | RED：`Tests run: 3, Failures: 1`（消息为守卫文案）；GREEN：`Tests run: 11, Failures: 0`（本类 3 + `PublishSemanticsTest` 4 + `ZeroItemJobTerminalTest` 4） |

---

## 1. 精确改动（唯一文件）

`backend/src/test/java/com/example/configmgr/job/service/PublishImportModeFailFastTest.java`（+3 / −0）：

```diff
@@ -67,6 +67,9 @@
         Task task = task("MERGE");
         task.setSettingsJson("{\"importMode\":"); // 截断的坏 JSON：readTree 必抛 ⇒ UNPARSEABLE
         taskRepository.save(task);
+        // 夹具前提：任务必须有 1 个配置项，否则零条目守卫（PublishJobRunner#run 入口，BZ-01）
+        // 会在 doPublish 解析 settings_json 之前就收口 FAILED —— 本用例要守的是"解析失败"这条路径。
+        taskItemRepository.save(item(task, DEF));

         Job job = newJob(task);
         publishJobRunner.run(job);
```

为什么这是最小且同口径的：
- `taskItemRepository`（`:49`）与 `item(Task, String)`（`:141-147`）**本文件已有**，同文件 `run(...)` 辅助在 `:150` 正是这样插入条目；`PublishSemanticsTest:127` 同口径。没有新增辅助、没有改导入、没有新依赖。
- 插入点选在"坏 settings 落库之后、`newJob` 之前"：任务已有 id，且守卫读的是 `task_item`（与 job 创建顺序无关）。
- 目的只在于让"任务有可执行性"，从而把流程交回 `doPublish → resolveImportMode → UNPARSEABLE → IllegalStateException → recordFailure`，即**原 JSON 解析失败路径**。守卫本身（及"零条目优先于 settings 解析"的顺序语义）一字未动。

---

## 2. RED（先跑，未改任何东西）

副本里只跑这一个类（日志 `red.log`）：

```
cd <隔离副本>/_verify-20261025-task6-fix1/backend
"<本机工具链>/JetBrains/IntelliJ IDEA/plugins/maven-plugin/lib/maven3/bin/mvn.cmd" -o -s maven-settings.xml \
  -Dtest=PublishImportModeFailFastTest -DfailIfNoSpecifiedTests=false test
```

`red.log:241-249 / 257-265`：

```
[ERROR] PublishImportModeFailFastTest.unparsableImportModeFailsWholeBatchWithJobLevelIssue -- Time elapsed: 0.503 s <<< FAILURE!
java.lang.AssertionError:

Expecting actual:
  "任务 #2 没有任何配置项（0 条目），发布作业没有可发布的数据，作业未成功"
to start with:
  "发布过程出错: "

	at ...PublishImportModeFailFastTest.unparsableImportModeFailsWholeBatchWithJobLevelIssue(PublishImportModeFailFastTest.java:83)
...
[ERROR] Tests run: 3, Failures: 1, Errors: 0, Skipped: 0
[ERROR] BUILD FAILURE
```

与 CI 现场（`main-ci-cb0761d-failed.log:17376-17389`）**逐字一致**：同一用例、同一行号 `:83`、同一"期望 `发布过程出错: ` / 实际零条目文案"。

同一条失败块里**只有** `startsWith` 一条断言失败 ⇒ `:75-78`（`FAILED`、`errorCount==1`）、`:79-81`（作业级 `-` issue 恰 1 条、ERROR）、`:87-89`（已发布行数与数据零变化）都通过。这说明零条目守卫与 `recordFailure` 的**终态同构**，红只在文案来源 —— 与分析的判断一致，也证明本次不需要（也不应该）动产品终态语义。

---

## 3. GREEN（本类 + 最近相邻两类，小上限）

同一个副本，补上夹具后跑 3 个类（日志 `green.log`）：

```
mvn.cmd -o -s maven-settings.xml \
  -Dtest=PublishImportModeFailFastTest,PublishSemanticsTest,ZeroItemJobTerminalTest \
  -DfailIfNoSpecifiedTests=false test
```

`Tests run: 11, Failures: 0, Errors: 0, Skipped: 0`：

| 类 | 例数 | 选的用由 |
|---|---|---|
| `PublishImportModeFailFastTest` | 3 | 本次修复对象（3/3 绿，`:83` 断言恢复） |
| `PublishSemanticsTest` | 4 | 最近的发布语义类：MERGE/REPLACE upsert、差集删除、baseVersion 冲突等主路径不被夹具改动/守卫影响 |
| `ZeroItemJobTerminalTest` | 4 | **零条目语义回归锁**：证明"没守卫被挪走/挪松"—— `publishJobWithoutTaskItemsMustNotComplete` 仍要求零条目 ⇒ FAILED + 有界终态 + 作业级 `-` issue + 任务非 COMPLETED |

两条相邻类各自封住一个方向：`PublishSemanticsTest` 说明"有配置项的发布照旧"，`ZeroItemJobTerminalTest` 说明"没配置项仍然不假成功"。二者与本次修复后的 `PublishImportModeFailFastTest` 同时绿 ⇒ 修复没有把守卫顺序挪走，也没有放宽终态。

---

## 4. 补丁可用性（已实证）

- 补丁源 = **HEAD `b3e8932` 的 blob**（`git show b3e8932:<path>`）与修复后的副本文件，用 `diff -u --strip-trailing-cr` 生成（LF hunk；目标工作树是 CRLF，`core.autocrlf=true`）。已核对：工作树该文件与 HEAD blob **除 CRLF 外逐字相同**，故补丁可视为对 HEAD 的精确补丁。
- `cd <主仓> && git apply --check <隔离副本>/_verify-20261025-task6-fix1/task-6-fix1.patch` ⇒ **OK，未落盘**。
- 另取一份 main 的 `backend/` 全新副本 `git apply` ⇒ 成功，且该文件与"跑绿的那份副本"**byte-for-byte 相同**。
- 主仓工作树条目数 29（= 开工前 16 个 ` M` + 13 个 `??`，逐项同名），本次**未新增/未修改任何主仓文件**。

---

## 5. 有意没做的事（与分析的差异 / 后续）

1. **没有采纳"把零条目判断挪到 `resolveImportMode` 之后"的反向修法**（分析 §4 末段已建议不作为首选）：那会让"零条目 + 坏 settings"报解析错、掩盖零数据这个更根本的问题，并需要重写已入库的 `ZeroItemJobTerminalTest` 语义。本次严格只补夹具。
2. **没有顺手把零条目 PUBLISH 文案收紧为精确断言**（分析 §5 的可选建议）：那会第二次改测试语义，且超出"修一条失效夹具"的范围。若要钉"零条目优先于 settings 解析"，建议单列一笔（`ZeroItemJobTerminalTest` 对 PUBLISH 断言完整文案）。
3. 分析 §6 的观察项（守卫路径未调 `issueRepository.deleteByJobId`，同 jobId 重派可能累积 issue）**本轮不动**：无证据表明存在同 jobId 重派，且本次要求生产零改动，仅登记。
4. 未跑全量（2684 条）、未提交/推送；CI 复跑由控制器统一发起。

---

## 6. 一句话

`PublishImportModeFailFastTest` 缺的是"任务可执行性"这个前提，不是产品语义 —— 补 1 个 TaskItem（既有辅助 + 既有仓库）后，坏 `settings_json` 又能走到 `doPublish` 的解析失败路径，`:83` 的 `发布过程出错: ` 断言恢复；守卫顺序、终态、错误文案与用例数全部未变，补丁可直接 `git apply` 到 `b3e8932`。
