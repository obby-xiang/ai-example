<!-- 历史中间稿证据条目（自动生成头部，非正文）。源 = Kimi Code assistant wire 文本；不含 think / 工具输出 / 原始用户消息。 -->

# MT-089 · 设计 · 结论（双 verdict）（45653e92 / agent-1766）

> **项目归属**：**项目硬锚点**（正文命中 ai-example 路径 / 项目类名 / 项目文档名 / 项目 UI 概念）。
> **归属依据**：正文命中：`ai-example-code/ai-example-main`、`backend/src/main/java`。
> **稿态**：历史中间稿 —— 该 wire 第 `m21` 条 assistant 消息（m 按 wire 内 assistant 消息计，含工具调用轮）；本条是该 wire 第 `mt3` 条有正文的 assistant 消息（共 5 条有正文），末条有正文消息为 `m40`。**非现行基线、非验收结论、非当前缺陷清单**。
> **来源**：会话 `45653e92` / agent `agent-1766`（`main` = 主会话）；原字符 6,812（链内 1 段、合计 6,812）。
> **链号 / 类别**：`MT-089` / **设计**；判定：助手设计方案/计划（历史稿）；**不是用户批准**。
> **轮次**：位于该 wire 第 1 个用户轮之后；相邻用户轮含决断类字样（仅记布尔，未复制用户原文）。
> **去重**：行指纹对「仓内 + 落盘语料」覆盖率 `7%`（<60% 视为仓内无等价件）；同 wire 后文覆盖率 `0%`。
> **与末条归档（138 件）的关系**：末条 `m40`（3,566 字符）未入末条归档。
> **脱敏**：路径 2 / 会话标识 0 / 身份 1 / 密钥 0 / 旧仓名与同源仓名 1 处。

---

## 结论（双 verdict）

- **规格 verdict：通过，可验收。** R1-11 与 R1-12 的验收条件均有实现并且有可复核证据，未引入 DDL，也未引入 `@Version`，API 形状未变。
- **质量 verdict：有条件可验收。** 没有 Critical；有 3 项 Important，都属于“同一缺陷类的残留问题/资源边界”问题，建议在本批或紧随的一批中修复，不构成对 R1-11/R1-12 的规格违约。

核对方式：只读。将主树 `git diff HEAD` 与 `A-job-cas.diff` 逐文件比对（生产代码部分完全一致，测试部分为 diff 尾部新增内容），把改动点回填到调用方（4 个 Runner、`JobTerminalWriter`、`JobWatchdog`、`StaleJobRecoveryRunner`、`JobController`），并抽查报告中的日志。按要求**没有**重跑测试。

证据复核（非采信报告，而是实际查看）：
- `_r1-11-12-red-final.log`：`Tests run: 12, Failures: 9`，失败描述显示 `concurrentTerminalWriters… expected: 1 but was: 5`、`completionAndCancelRace…（finishWon=true, cancelWon=true）`、`markRunningCannotResurrect… 但却是 RUNNING`、`snapshotAfterTerminal… 但却是 3`。日志中的启动目录为 `<LOCAL_WORK>\_r1-11-12-redwt\backend`，即隔离的 RED worktree。
- `_r1-11-12-green-final3.log`：12/0/0 `BUILD SUCCESS`；`_r1-11-12-green-final2.log`：job 包 102/0/0；`_r1-11-12-full-final3.log`：1724/0/0 `BUILD SUCCESS`，日志内 `started by <USER> in <LOCAL_WORK>\_r1-11-12-wt\backend`，且 `JobTerminalStateCasTest` 出现 8 次（确实在全量里跑了）。
- 工作树：`job/` 下恰好 6 个 `M` + 1 个 `??`（新测试文件），与 diff 文件集合一致；`db/migration` 和 `.sql` 零改动——**无 DDL属实**。

## 规格核查

**R1-11（终态 CAS / 开工不复活 / 进度不回退）**
- `finishIfInFlight`：`UPDATE jobs SET status,errorCount,warningCount,progress,total,finishedAt WHERE id=? AND status IN ('PENDING','RUNNING')`，用受影响行数判定胜者，正是研究报告给出的两条建议之一（条件 UPDATE）。`finish` 返回值语义与 `JobTerminalWriter` 的 `wroteTerminal` 门控自洽（迟到的终态写入不再联动任务态、不发 `JOB_DONE`）。
- `updateProgressIfInFlight`：补上了原 `updateProgress` 缺少的 `status` 条件，终态后的旧快照影响 0 行——对应报告“snapshot 把 progress 写回终态之后的旧值”。
- `markRunningIfPending`：`PENDING→RUNNING` 条件写，关闭“取消提交后整行 save 翻回 RUNNING”。
- `finishItem` + `finishIfJobStatusAllowed`：额外收紧了子项终态（超出规格的加分项），保留“父作业 CANCELLED 时条目收成 CANCELLED”的取消补偿路径，这是对的语义划分。
- 无 `@Version`、无 DDL ✔。

**R1-12（cancel 事务边界）**
- `@Transactional` 移除，显式 `REQUIRES_NEW` TransactionTemplate：事务内只剩 `findById` + 状态 CAS + `applyJobOutcome`（纯 DB，已核对 `TaskService.applyJobOutcome` 不含 SSE/Redis）。
- Redis 与 SSE 在 `afterCommit` 中按“先 Redis 后 SSE”的顺序执行，与旧顺序一致；两段各自 try/catch，失败只记日志、不回滚、`redisWritten=false` 降级语义保留。
- 端点响应字段（`jobId/cancelKey/redisWritten/wokeInProcess`）、`JOB_DONE` 载荷、409 `JOB_ALREADY_FINAL` 均未变（`JobController:49-56`）。
- “同步非 fire-and-forget”可证：`redisFailureAfterCommit…` 断言返回的 `redisWritten=false`，说明旁路在 `cancel()` 返回前已执行完；`redisIsWrittenOnlyAfterCancelIsCommitted` / `sseIsPublishedOnlyAfterCancelIsCommitted` 用**另一条连接**读 `STATUS` 证明提交先行。

**测试是否真并发**：是，不是顺序执行。RED 基线出现 `wins=5`（旧读-判-写导致 5 个写者同时“胜出”）、round 0 `finishWon && cancelWon` 同时为真——只有在真实交错下才会出现；`CyclicBarrier` + 8/6 writers + 10/12 轮 + 真实 H2 行锁，判别力已被 RED 结果证明。8 写者/12 轮的新实现无锁超时（`failures` 断言为空），H2 行锁等待后重判 WHERE 的行为被实际覆盖。

**“宣称是否过度”**：不过度。报告明文把 `updateItem`/`markItemFinal` 列进残余风险第 1 条，宣称面只到 `finish`/`markRunning`/`snapshot`/`finishItem`，与代码一致。唯一措辞过宽的是 `JobProgressService.finishItem` 的 javadoc（`JobProgressService:155-163`）“不会把取消语义翻写”——对 `finishItem` 自身成立，但对同表的 `markItemFinal` 不成立（见 Important 3）。

## Critical

无。

## Important（附修法）

**I-1 `cancel` 的旁路虽已出事务，但仍占着 Hikari 连接（与 R1-12 的动机③不完全对齐）**
`afterCommit` 触发时提交已完成、但 `cleanupAfterCompletion` 尚未执行，`EntityManager` 未关闭、JDBC 连接未归还；此时 Redis（2s 预算）+ SSE（单帧 15s 预算）仍在持有连接。R1-12 的动机明确写了“Hikari 只有 10 条连接”，因此该修复只解决了“行锁窗口”，没解决“连接占用窗口”。
修法（最小）：本方法自己拥有 `REQUIRES_NEW` 模板，模板返回即“已提交 + 已清理”，因此删掉 `TransactionSynchronization` 与 `AtomicReference`，改成模板调用返回后再执行：
```java
tx.executeWithoutResult(status -> { /* findById + CAS + applyJobOutcome + 冲突异常 */ });
return publishCancellationAfterCommit(jobId, requestedBy, taskId); // 提交后、连接已归还
```
顺带消掉 I-1 关联的 `isSynchronizationActive()` 隐患（`JobTerminalWriter:88` 有守卫，`JobService.cancel` 没有；换用非同步事务管理器时 `registerSynchronization` 会抛 `IllegalStateException`）。

**I-2 `PrecheckJobRunner` 仍对 `jobs` 行做“读-改-整行写回”，可把已提交的 CANCELLED 复活为 RUNNING（R1-11 同类残留，报告残余清单未列）**
`<REPO>/backend/src/main/java/com/example/configmgr/job/service/PrecheckJobRunner.java:303-311`：`run` 是 `@Transactional`（`:61`），末尾 `writeFingerprintSummary` 在**同一长事务**里 `findById` 受管实体 → `setResultJson` → `save(fresh)`。`Job` 无 `@DynamicUpdate`，Hibernate 脏检查生成的是全列 UPDATE，因此提交瞬间会用**读出时刻的 status**（RUNNING）覆盖这期间已提交的 `CANCELLED`。随后 `terminalWriter.complete` 的 afterCommit `finish` 又看到 RUNNING 而“胜出”，用户取消被作业自身的终态覆盖（PRECHECK 路径；改动前后均存在，非本批新引入，但正是 R1-11 要消灭的那一类，且 `JobProgressService:39-40` 的“本类是这两张表唯一的写入者”已不成立）。
修法：改为定向条件写，复用同一 in-flight 白名单：
```java
@Modifying(clearAutomatically = true)
@Query("UPDATE Job j SET j.resultJson = :json WHERE j.id = :jobId AND j.status IN :inFlight")
int writeResultJsonIfInFlight(...);
```
（或给 `Job` 加 `@DynamicUpdate`，全仓仅此一处会因此受益。）补一条回归：先 `cancel` 提交，再调 `writeFingerprintSummary`，断言 status 仍是 CANCELLED。

**I-3 子项终态不变量只对 `finishItem` 成立：`markItemFinal` 可把 CANCELLED 条目翻成 FAILED，`updateItem` 终态后照写**
`JobProgressService:194-207`（`markItemFinal` 读改写）与 `:147-153`（`updateItem` 读改写，同样是全列 UPDATE）绕过 `finishIfJobStatusAllowed`。可达路径：`PublishJobRunner:301` 硬错误补记 `markItemFinal(..., "FAILED")`，若此刻 API 已提交 CANCELLED，则作业 CANCELLED、条目 FAILED，与 `finishItem` javadoc 宣称的“冲突的迟到写影响 0 行”矛盾。报告残余 #1 已披露，故不算虚报，但建议同步收口：
- `markItemFinal` 增加父作业条件（同一 `finishIfJobStatusAllowed`，或新增只写 status 的 `finishIfJobStatusAllowed` 变体）并传 `jobId`；
- `updateItem` 加 `WHERE i.id=? AND EXISTS(parent in-flight)` 的定向更新；
- 可选增强：`finishIfJobStatusAllowed` 的 WHERE 再加 `i.status NOT IN ('COMPLETED','FAILED','CANCELLED')`，顺手关掉“同状态迟到写把 item processed 写回旧值”的残余（`matchingTerminal` 允许父 CANCELLED 时写 item CANCELLED，而 item 已是 CANCELLED 时也会重写进度）。

## Minor（单列）

1. `JobProgressHeartbeatTest:62-84`（`markRunningSnapshotAdvanceHeartbeatAndFinishClearsEntry`）：`finishIfInFlight` 未 stub → 默认 0 行，实际走的是“被拦下”路径，但断言消息仍写“终态写入成功后清理”；`when(jobRepository.findById(42L))` 已成死桩。建议 stub 返回 1 或改写断言。
2. `markRunningIfPending` 不再在“非 PENDING”时写 `total`（旧代码会 `setTotal` 后退让），被取消的作业可能显示 `0/0`；无测试覆盖。
3. `updateStatusIfInFlight` 不写 `finishedAt`（报告已披露）；取消作业 `finishedAt=null`，与 `finish` 路径不一致。
4. `JobProgressService:181-188` 的 `switch (itemStatus)` 对 `null` 抛 NPE；当前调用点全是字面量，建议 `itemStatus == null` 前置判空。
5. `finishIfJobStatusAllowed` 的 `EXISTS` 与并发父作业终态提交之间无锁，存在语句级窗口（可接受，建议注释显式声明“尽力而为”）。
6. 测试缺口：409 路径不写 Redis/不推 SSE 的断言、SSE 推送失败归因、以及“cancel 不再整行写回（`progress/total/startedAt` 保留）”均无用例。
7. `JobService.cancel` 每次 new 一个 `TransactionTemplate` 并用 `AtomicReference` 取回值；`IN_FLIGHT` 在 `JobService` 与 `JobProgressService` 各一份。建议抽字段/共用常量（style）。
8. `cancel` 并发双击的第二次由“200 覆盖写”变为 409（更正确，但与旧行为不同），建议在接口文档/前端提示里注明。
9. `JobProgressService:39-40` 类 javadoc“本类是这两张表唯一的写入者”已与事实不符（`JobService.cancel`、`StaleJobRecoveryRunner`、`PrecheckJobRunner.writeFingerprintSummary` 都写 `jobs`）。

## 未验证/限制

- 未重跑任何测试（按要求）；以上并发结论依据 RED/GREEN 日志文本 + 代码路径，未独立复现 1724 全量。
- Spring `afterCommit` 触发点位于 `doCommit` 之后、`cleanupAfterCompletion` 之前（I-1 的判断依据），为框架契约，未在本仓加插桩验证。
- 生产 `datasource.url` 为 H2 file（`application.yml:37`），故报告残余 #6“换生产同构库复测”所指的差异面较小；“多实例 + 共享 H2 file”本身不可行，不在本次评审范围。
