> 来源=<仓外证据>/cluster-infra-fix1-report.md（2026-10-25 归档）· 仓外正式过程报告，属历史证据，不代替现行基线/决策文档（docs/02、docs/03、docs/adr/）
# Fix round 1：评审 I-1（终态作业仍可被"迟到开工"填入 RUNNING 条目）

- 日期：2026-10-25
- 范围：仅评审 Important I-1；未处理 10 个 Minor、未做任何无关改动。
- 主仓：`<主仓>`（HEAD `ae883fb`，工作树已含上一轮 14 文件补丁 +
  其他 agent 的 park 改动）—— **本机未编辑主仓**。
- 工作副本：`<验证副本>/_verify-20261025-cluster-infra/backend`（含上一轮 14 文件补丁 + 本轮修复）
- 构建：`<工具安装目录>/JetBrains/IntelliJ IDEA/plugins/maven-plugin/lib/maven3/bin/mvn.cmd`，无 `clean`，离线，
  `-Dspring.data.redis.port=1`（CI 无 Redis；且不污染本机 Redis）。
- 补丁：`<仓外证据>/cluster-infra-fix1.patch`（**git apply 兼容**，对当前主仓工作树验证通过）

## 1. 结论

**I-1 确认成立，已按"父作业行锁 + 在途判定"原子封口。** 修复前可确定性复现：终态收敛提交后
`startItem` 仍插入一条 `RUNNING` 条目，且该条目此后再无任何路径能收敛（其 `finishItem`/`updateItem`
因父作业终态恒为 0 行）。修复后：迟到开工返回 `null`、不插行；与终态写并发时在**同一把数据库行锁**
上排队（实测等待 ~400ms 而非修复前的 3ms），终态先提交则开工被拒，开工先提交则终态写在同事务里把它
收敛掉 —— 两种次序都不留在途条目，且跨 JVM 成立（锁在数据库里）。

## 2. 根因与可达窗口（评审 I-1 复核）

`JobProgressService#startItem`（修复前 `:195-203`）无条件 `save(RUNNING)`。收敛只发生在"写作业终态"
那一刻（`finish` 的 FAILED、`JobService#cancel` 的 CANCELLED），因此**收敛之后**的开工不会再被收敛。
三个真实窗口（评审列出，我逐条核对代码确认）：

| # | 窗口 | 代码事实 |
|---|---|---|
| 1 | 取消 | `JobService#cancel`：`updateStatusIfInFlight` + `applyJobOutcome` + `convergeOpenItems` 同一事务提交**之后**，`publishCancellationSideEffects` 才写共享取消标志；runner 在条目循环**顶部**才检查标志 ⇒ 两次检查之间正好走到下一项 `startItem` |
| 2 | 判死/判超时 | `JobWatchdog#judge`：先 `terminalWriter.complete(...)`（→ `finish` + 收敛），后 `cancellationRegistry.cancel(...)`，同样的顺序窗口 |
| 3 | 跨实例 + Redis 降级 | `JobCancellationRegistry#cancel` 先写 Redis 再置本地标志；Redis 写失败只本地生效 ⇒ 另一实例的 runner 读不到共享标志，会在 FAILED/CANCELLED 作业下按条目继续推进 |

## 3. 修复（原子、跨 JVM）

### 3.1 `JobProgressService#startItem`：父作业行锁 + 在途判定（条件插入）

```java
return requiresNew.execute(status -> {
    // 与 finish/cancel 的 jobs 条件 UPDATE 串行化（同一把行锁）；markItemFinal 的插入分支同法。
    Job parent = jobRepository.findByIdForUpdate(jobId).orElse(null);
    if (parent == null || !IN_FLIGHT.contains(parent.getStatus())) {
        log.info("作业 #{} 已离开在途态（{}），拒绝迟到开工的配置项 {}", ...);
        return null;
    }
    ...save RUNNING...
});
```

**为什么是行锁而不是"先查后插"**：终态写是 `UPDATE jobs ... WHERE status IN (PENDING,RUNNING)`
（语句级判据 + 行锁）。`SELECT ... FOR UPDATE` 取同一行锁后：

- 终态先提交 ⇒ 本方法读到终态，返回 `null`，不插行；
- 本方法先提交 ⇒ 终态写随后在同一 `REQUIRES_NEW` 事务里 `convergeOpenItems`，把这条新条目收成终态。

无锁的 check-then-insert 在 READ COMMITTED 下会读到旧快照并插到终态之后（正是本用例 RED 测到的
"3ms 返回 id"），因此不采用。实现复用了仓内既有先例（`markItemFinal` 的插入分支同样用
`findByIdForUpdate`），锁顺序与取消/删除一致（task → jobs → job_items），无新增环。

### 3.2 四个 runner 的开工点：`null` ⇒ 停止开工（复用既有收尾）

`ExportJobRunner` / `ImportJobRunner` / `PrecheckJobRunner` / `PublishJobRunner` 的循环体：

```java
Long itemId = progressService.startItem(jobId, defCode);
if (itemId == null) {
    // 父作业已终态（收敛已提交）：终态作业下不得再新增在途条目（评审 I-1）。
    // 走既有取消收尾：本方法末尾的终态写会被 CAS 丢弃，不会翻写任务态、不发 JOB_DONE。
    cancelled = true;
    break;
}
startedItems++;
```

- `cancelled=true` 复用既有取消口径：末尾 `terminalWriter.complete(job, CANCELLED, ...)` 被
  `finishIfInFlight` 的 CAS 丢弃（0 行）⇒ 不联动任务态、不发 `JOB_DONE`、不覆盖已有终态。
- `startedItems` 不递增：`sorted.size() - startedItems` 的分母保持"未开工"语义，与真实情况一致。

### 3.3 不变量核对（逐条）

| 不变量 | 结论 |
|---|---|
| 在途作业的开工照常 | 保持；用例显式断言在途 `startItem` 返回非 null 且条目 RUNNING |
| 终态 CAS 不被放宽 | 未改 `finishIfInFlight` / `updateStatusIfInFlight` / `finishItem` 任何判据 |
| `processed/total` 语义 | 未改；收敛 SQL 仍只改 `status`，不碰 processed/total |
| 不覆盖 COMPLETED | 收敛谓词仍 `status IN ('PENDING','RUNNING')`；取消用例断言 COMPLETED 条目 status/processed 不变 |
| 不产生业务副作用 | 拒绝开工只 `break`；终态写被 CAS 丢弃；无重排队、无新作业 |
| 死锁 | 四 runner 的外层事务**不写 jobs 行**（grep 确认；`PrecheckJobRunner.writeFingerprintSummary` 在循环之后，且终态写走 afterCommit）。锁序 task → jobs → job_items 与取消/删除一致 |

## 4. RED → GREEN

新增两条用例（同一真实 H2 + 真实 Bean 的既有类 `JobWatchdogItemConvergenceTest`，非 mock）：

1. `lateStartAfterCommittedTerminalIsRejectedAndLeavesNoOpenItem` —— 终态写 + 收敛提交后调用
   `startItem`；同时钉住"在途开工照常成功"。
2. `startItemSerializesWithTerminalWriteOnParentJobRow` —— **真实并发/两个真实连接**：终态线程
   `findByIdForUpdate` 持行锁 400ms 后再 `finishIfInFlight` + `convergeOpenItems` 并提交；另一线程
   在持锁期间调 `startItem`，断言"必须等待 ≥200ms"且"返回 null"。

| 阶段 | 命令 | 结果 |
|---|---|---|
| RED（修复前） | `mvn -B -o test -Dspring.data.redis.port=1 -Dtest=JobWatchdogItemConvergenceTest` | **5 tests / 2 failures**：`expected: null but was: 3L`（迟到开工插了新条目）；`startItem 必须在父作业行锁上等待终态事务（实得 3ms）` |
| GREEN（修复后） | 同上 | **5/5 通过** |
| 相邻回归 | `mvn -B -o test -Dspring.data.redis.port=1 -Dtest='Job*Test,Export*Test,Import*Test,Precheck*Test,PublishJob*Test,TaskFinalizedGuardTest,TaskDelete*Test,File*Test,Readiness*Test,Health*Test'` | **259 tests / 0 failures / BUILD SUCCESS** |

RED 的两条断言都不依赖调度顺序：修复前 `startItem` 不参与任何数据库序列化，恒为毫秒级返回并插行；
修复后恒在行锁上等待。

## 5. 补丁（git apply 兼容）

`<仓外证据>/cluster-infra-fix1.patch`（182 insertions / 1 deletion，6 文件）：

```
backend/src/main/java/com/example/configmgr/job/service/JobProgressService.java   (+32/-1)
backend/src/main/java/com/example/configmgr/job/service/ExportJobRunner.java      (+6)
backend/src/main/java/com/example/configmgr/job/service/ImportJobRunner.java      (+6)
backend/src/main/java/com/example/configmgr/job/service/PrecheckJobRunner.java    (+6)
backend/src/main/java/com/example/configmgr/job/service/PublishJobRunner.java     (+5)
backend/src/test/java/com/example/configmgr/job/service/JobWatchdogItemConvergenceTest.java (+128)
```

生成与验证方式（未触碰主仓）：

1. 以**主仓当前工作树**这 6 个文件建临时 git 仓并提交（即补丁的 `a/` 侧，含主仓各自的 EOL 风格）；
2. 覆盖为副本内容后 `git diff` 生成补丁（行尾按 `a/` 侧风格归一化，避免整文件重写）；
3. 全新目录只放主仓当前版本 → `git apply --check` **通过** → `git apply` 成功 →
   与副本逐字节比对（EOL 归一化）**全部一致**。

应用：`git apply <仓外证据>/cluster-infra-fix1.patch`（在仓库根）。

## 6. Concerns / 边界

1. **每项开工多一次 `SELECT ... FOR UPDATE`**：配置项数量级（≤ 数十），且该事务本来就已存在
   （`REQUIRES_NEW`），实测四个 runner 流程用例（`ExportFlowJobTest`/`ImportFlowJobTest`/
   `Precheck*`/`PublishJob*`）全绿、整组回归 259/0。未做性能压测（不在本次范围）。
2. **真实三实例拓扑未跑**：Redis 降级跨实例窗口（场景 3）由数据库行锁封口，理论上与实例数无关；
   但"Redis 不可用时另一实例 runner 继续推进"的端到端形态仍需 Action/手工拓扑验证。
3. **未处理的 Minor**：M-1 注释矛盾（`ReadinessService:58` 与 gate 说明不一致）、M-2 cluster 缺
   `server.shutdown=graceful`、M-3 单/多文件判定按命中数、M-4 `filename*` 的 `+` 空格、M-5 多编码
   entry 未断言、M-6 FAILED 路径未断言"COMPLETED 不被覆盖"、M-7 draining 时序余量、M-8 配置合同用例
   等 —— 按指令本轮一律不动。
4. **取消路径的 `cancelled=true` 复用**：拒绝开工后末尾会尝试写 CANCELLED，但被 CAS 丢弃；
   因此该路径不会把已 COMPLETED 的作业翻写成 CANCELLED（与既有取消竞态语义一致）。
5. **`startItem` 契约变更**：返回 `null` 现在有明确语义（父作业已终态/已删除）。仓内四个调用点已处理；
   若将来新增调用点，必须同样处理 `null`（javadoc 已写明）。
