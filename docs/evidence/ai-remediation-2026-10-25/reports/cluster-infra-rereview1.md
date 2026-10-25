> 来源=<仓外证据>/cluster-infra-rereview1.md（2026-10-25 归档）· 仓外正式过程报告，属历史证据，不代替现行基线/决策文档（docs/02、docs/03、docs/adr/）
# 复审 round 1 · 原 Important I-1（迟到 startItem 留下永久 RUNNING 条目）

- 日期：2026-10-25
- 范围：**只复审 I-1**（评审 `cluster-infra-task-review.md` §I-1）；10 个 Minor 按指令全部视为延期，本轮不评。
- 输入：`<仓外证据>/cluster-infra-fix1-report.md`、`<仓外证据>/cluster-infra-fix1.patch`（182+/1-，6 文件）
- 代码位置：主仓 `<主仓>`（修复已应用）；对照 `<验证副本>/_verify-20261025-cluster-infra/backend`（打补丁并跑出 RED/GREEN 的那份）
- 只读复审：未改代码、未重跑测试、未起服务

## 0. 结论

| 项 | 结论 |
|---|---|
| **原 Important I-1** | **ADDRESSED** |
| **本轮新增 Critical / Important** | **无** |
| **新增 Minor（不计入本轮判定，仅记录）** | 3 条测试/证据类观察（见 §5），均不阻塞 |
| **阻塞项** | **无** |

修复方向正确且封闭面完整：条目插入点与作业终态写现在争用**同一把 `jobs` 行锁**，且两个方向都能收敛（终态先提交 ⇒ `startItem` 读到终态返回 null；`startItem` 先提交 ⇒ 终态事务的 `convergeOpenItems` 收掉新条目）。四个生产调用点都正确处理了 null，且拒绝路径复用的收尾不会翻写终态、不产生额外副作用。

## 1. 应用一致性（先确认"审的就是跑过的"）

- 主仓已应用：`git status` 显示 5 个 runner/service 为 `M`、新测试为 `??`；与工作副本逐字节对比（`diff --strip-trailing-cr`）**6 个文件全部一致**，差异仅为 CRLF/LF（主仓 CRLF，工作副本 LF）。即"跑过 RED/GREEN 的字节 == 主仓字节（忽略行尾）"。
- 证据时序自洽：工作副本源码 mtime 16:32:51–16:34:20 → 测试类编译 16:35:06 → `_green_i1.log` 16:35:37（5/5）→ `_regress_i1.log` 16:39:40（259/259 BUILD SUCCESS）。最终字节被 GREEN 与回归共同覆盖。
- `_red_i1.log`（16:34:07）失败点与报告一致，且是行为断言而非环境噪声：
  - `startItem 必须在父作业行锁上等待终态事务（实得 3ms）` — 实得 `3L`；
  - 另一条 `expected: null but was: 3L`（迟到开工插了行）。
  两条都不依赖调度顺序（修复前根本不参与任何 DB 序列化），RED 有效。

## 2. 逐项核对（本轮指定的五个问题）

### 2.1 终态后的迟到 startItem 不能再留下 RUNNING —— 成立

`backend/src/main/java/com/example/configmgr/job/service/JobProgressService.java:218-235`（守卫在 `:221-228`）

```java
Job parent = jobRepository.findByIdForUpdate(jobId).orElse(null);
if (parent == null || !IN_FLIGHT.contains(parent.getStatus())) { log...; return null; }
```

- `findByIdForUpdate` = `@Lock(PESSIMISTIC_WRITE) SELECT j FROM Job j WHERE j.id = :jobId`（`job/repo/JobRepository.java:42-45`）⇒ 真 `SELECT … FOR UPDATE`，H2/PG 同为行锁。
- 终态作业 ⇒ 返回 null 且**不插行**（测试 `lateStartAfterCommittedTerminalIsRejectedAndLeavesNoOpenItem`，`:175-196`）。
- 在途作业 ⇒ 照常插 RUNNING（同一测试显式断言，防止把守卫做成"一律拒绝"）。
- 作业行被删除 ⇒ 也返回 null（`parent == null` 分支），比修复前的 FK 异常更干净。

### 2.2 两种次序的行锁串行化 —— 两种都收敛（源码 + READ COMMITTED 前提已核）

前提：仓内无任何隔离级别覆盖——`resources/*.yml` 无 `isolation`/`hibernate.connection.isolation`，job/task/config 无 `@Transactional(isolation=…)`，服务层无自定义隔离 ⇒ H2/PG 默认 READ COMMITTED（语句级快照）。

- **次序 A（终态先提交）**：`finishIfInFlight`/`updateStatusIfInFlight` 的 `UPDATE jobs` 先拿锁并提交；`startItem` 的 `SELECT … FOR UPDATE` 拿锁后读到**已提交**的 FAILED/CANCELLED ⇒ `IN_FLIGHT` 不匹配 ⇒ null，不插行。✔
- **次序 B（开工先提交）**：`startItem` 先拿锁、插 RUNNING、提交；随后终态 `UPDATE jobs … WHERE status IN (PENDING,RUNNING)` 拿到锁（行未被改，条件仍成立）⇒ 同事务 `convergeOpenItems` 是**新语句**，READ COMMITTED 下看到刚提交的 RUNNING 条目 ⇒ 收成终态。✔
- 终态事务若已在等待（次序 B 的变体）：H2/PG 在锁释放后重判 WHERE 并应用更新，效果同上。✔
- 两条写路径都覆盖：`finish` 的 FAILED 与 `cancel` 的 CANCELLED 都在"翻 job 行的同一事务"里收敛（`JobProgressService.java:146-153`、`JobService.java:499-515`）；`StaleJobRecoveryRunner` 也先写 job 行再逐条收敛（`:84-96`），与同一把锁相容。
- 未见"翻终态但不收敛"的第三个写点：全仓 `setStatus(Job.JobStatus…)`/`updateStatusIfInFlight`/`finishIfInFlight` 仅 `JobService:170`（建作业 PENDING）、`JobService:499`、`StaleJobRecoveryRunner:85`、`JobProgressService:147`（后三处均已收敛）。
- 测试证据：`startItemSerializesWithTerminalWriteOnParentJobRow`（`:206-262`）两个真实连接、终态线程持锁 400ms，断言 `startItem` 等待 ≥200ms 且返回 null；RED 实测 3ms。

### 2.3 四个 startItem 调用点都正确处理 null —— 成立

全仓 `startItem(` 只有 4 个生产调用点（`ExportJobRunner:134`、`ImportJobRunner:86`、`PrecheckJobRunner:102`、`PublishJobRunner:157`），逐个核对：

- 都在 `itemId == null` 处立即 `cancelled = true; break;`，`startedItems++` 在其后 ⇒ 不存在使用 null id、不存在分母把"未开工"算成"已开工"。
- `cancelled` 变量在四个 runner 的循环前均已声明，且循环后的既有收尾走 `terminalWriter.complete(..., CANCELLED, …)`（Export `:287-297`、Import `:197-202`、Precheck `:278-286`、Publish `run` `:100-107`），与既有取消路径完全同一条代码。
- 测试类里 3 处调用均传在途作业或显式期望 null，无遗漏；仓内无其它调用点（含 AI/工具面）。

### 2.4 本地取消不覆盖 job/task 终态、不产生重复副作用 —— 成立

- 终态写仍走 `JobProgressService.finish` 的**条件 UPDATE**（CAS）。父作业已 FAILED/CANCELLED/COMPLETED 时影响 0 行 ⇒ `wroteTerminal=false` ⇒ `JobTerminalWriter.apply` 不执行 `tryLockForUpdate`、不 `writeCompletionStep`、不 `applyJobOutcome`、不 `publish(JOB_DONE)`（`JobTerminalWriter.java:113-146`）。即不会把已 COMPLETED 的作业/任务翻成 CANCELLED。
- 被拒绝的开工分支本身只写一行 info 日志 + `break`：不 `saveIssue`、不 `publishProgress`、不重排队、不新建作业；`ExportJobRunner` 的 `complete(..., "EXPORT")` 成功步骤联动也因 `wroteTerminal=false` 跳过。
- `PrecheckJobRunner` 在循环后仍会执行 `writeFingerprintSummary`（`:282`），但它是 `writeResultJsonIfInFlight` 条件写（`PrecheckJobRunner.java:327`），父作业终态时 0 行 —— 与既有取消路径行为一致，非新增副作用。
- 唯一的"多一次动作"是外层数据面事务照常提交（与既有 cancel 语义相同：已完成的配置项数据/任务项状态保留），不是本次引入。
- 端点/事件面：无 `JOB_DONE`、无重复 issue；`JOB_PROGRESS` 只在条目循环内发，break 后不再发。

### 2.5 锁顺序是否引入具体死锁 —— 未引入可复现死锁

现网存在的加锁顺序（全部已核）：

| 路径 | 顺序 |
|---|---|
| `startItem`（新） | `jobs` → `job_items`(insert) |
| `finish` / `cancel` | 任务行（cancel） → `jobs`(UPDATE) → `job_items`(UPDATE) |
| `markItemFinal` / `compensateItemFailure`(按 jobId) 插入分支 | 读 `job_items` → `jobs`(FOR UPDATE) → `job_items`(insert) |
| `StaleJobRecoveryRunner` | 任务行 → `jobs` → `job_items` |
| `TaskService.delete` | 任务行 → `job_items`(delete) → `jobs`(delete) → `staging` → `task_items` → `task_files` → `task` |

- 与 `startItem` 同向的路径（jobs → job_items）不存在环；`finishItem`/`updateItem`/`convergeOpenItems` 对 `jobs` 只做无锁 `EXISTS` 读，不会"持 job_items 等 jobs"。
- 唯一反向对是 `TaskService.delete`（job_items → jobs）。理论上它与"持 jobs 等 job_items 索引项"的 `startItem` 可成环，但触发需要 delete 在一个**仍有活 runner**的任务上推进：delete 在 `TaskService.java:390-426` 有两道闸（存在 PENDING/RUNNING 作业 ⇒ 409；runner 仍登记/心跳未清 ⇒ 409；Redis 不可读 ⇒ fail-closed 409），且 `startItem` 返回 null 的前提正是作业已终态而其 runner 仍活 ⇒ 第二道闸必然拦住。因此该环**不可达**，属与既有 C1 防护同前提的理论形状，不是本次引入的具体死锁。
- 新增等待是"每项一次 `jobs` 行锁等待"：可能的持有者是 `finish`/`cancel`（短事务）、看门狗（`scan` 非事务，`judge` 内 REQUIRES_NEW 短事务）、`StaleJobRecoveryRunner`（启动期、跳过活 runner）、`PrecheckJobRunner.writeFingerprintSummary`（循环之后的长事务尾段）。均有界，不构成新的长阻塞面。
- 另核：四个 runner 的外层事务都不写 `jobs` 行（`grep jobRepository` 仅 Precheck 的 `writeResultJsonIfInFlight`，且在循环之后），因此不存在"外层自持 jobs 锁 → 内层 REQUIRES_NEW 等自己的锁"的自死锁。
- H2 `LOCK_TIMEOUT`：h2tcp profile 60s、PG `lock_timeout=60s`；等待对象都是短事务，未引入新的超时面。

## 3. 修复本身的质量（仅 I-1 相关）

- 守卫放在 `REQUIRES_NEW` 内、与终态写同锁，而不是应用层 check-then-insert —— 与报告论证一致，是本问题的正确解法。
- javadoc 写清了 null 契约与三条可达路径（`JobProgressService.java:195-217`），并保留了"跨 JVM 靠数据库锁"的边界说明。
- 未放宽任何既有 CAS 判据（`finishIfInFlight`/`updateStatusIfInFlight`/`finishIfJobStatusAllowed` 全部未改），未改收敛谓词（仍只匹配 PENDING/RUNNING）。
- 不改 `processed/total` 语义；拒绝开工不写任何行。

## 4. 结论性判定

**I-1：ADDRESSED。** 迟到开工在两种次序下都被封口，`startItem` 的 null 契约被四个调用点完整处理，拒绝路径不产生终态翻写或重复副作用，锁顺序没有引入具体死锁。原评审给出的三条真实窗口（取消、看门狗、跨实例 + Redis 降级）现在都由同一把数据库行锁覆盖，且与实例数无关——这一条比"再收敛一次"的替代方案更强，因为它把不变量从"事后补救"改成"插入点必要条件"。

## 5. 记录性观察（非 findings，不影响判定；按要求不展开）

1. 并发用例用 `finishIfInFlight + convergeOpenItems` 直接建模终态事务，而不是调 `progressService.finish`；且并发只测"终态先持锁"一个方向，反向（开工先提交、终态随后收敛）由确定性用例 + 既有收敛用例间接覆盖。属测试建模取舍。
2. 该用例的 H2 内存库 URL 未设 `LOCK_TIMEOUT`（默认 1–2s 量级），终态线程持锁 400ms；极慢 CI 上余量会变窄（表现为等待超时而不是断言失败）。属既有测试风格。
3. 主仓 CRLF / 工作副本 LF（内容一致），补丁按主仓行尾生成；后续 diff 审阅时看不出差异，但 `git diff` 会提示行尾归一化——已知且无害。

## 6. 未做

未重跑任何测试/构建（按要求）；未起进程或容器；未访问真实三实例拓扑；未改任何文件。真实三实例下"跨实例 Redis 降级 + 迟到开工"的端到端形态仍需 Action/手工拓扑验证（与上一轮结论相同）。
