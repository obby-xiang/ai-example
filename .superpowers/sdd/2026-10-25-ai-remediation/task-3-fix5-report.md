# Task 3 Fix-5 报告：记忆复核阶段拥有自己的有界 attempts 预算（M-7 收口）

**结论：DONE**（1 行行为修复 + 4 处注释同步 + 2 个确定性用例；增量补丁 `git apply --check` 干净且在基线上复现 GREEN；真实 RED/GREEN 均为实跑）

- 基线：主仓 `<主仓>` **当前工作区**（HEAD `2eb2539` + 全部候选，含 Task3fix4 与 task8-final-fix）
- 隔离副本（唯一写入物）：`<隔离副本>/_t3-fix5`（= 当前 main 的 `backend/` 拷贝，`git init` 后首个提交 = 基线）
- 交付：`.superpowers/sdd/2026-10-25-ai-remediation/task-3-fix5.patch`（179 行 / 13,092 字节，4 文件）
- 未改 main 的源码/测试/配置，未提交、未推送；未跑全量、未 `clean`；Maven 仅
  `<本机工具链>/JetBrains/IntelliJ IDEA/plugins/maven-plugin/lib/maven3/bin/mvn.cmd`
- 未触碰 `ConfirmGate` / `ResilientChatService` / 前端 / docs；未改 `MAX_ATTEMPTS`、退避公式、`defer` 语义、`/health` 面。

---

## 0. 复审 M-7 的复核与最小修复点

复审所指的两条真实路径，代码上确认成立：

1. `SessionPurgePendingStore.scheduleVerify`（`backend/src/main/java/com/example/configmgr/ai/session/SessionPurgePendingStore.java:308`）
   构造复核台账时写的是 `pending.attempts()` —— 主体阶段的旧计数被原样带进复核阶段。
2. `SessionPurgeRetryRunner.runOnce`（`backend/src/main/java/com/example/configmgr/ai/session/SessionPurgeRetryRunner.java:182`）
   的 `if (pending.attempts() >= MAX_ATTEMPTS) { skipped++; continue; }` **排在** `verifyAtMs` 分支（`:187`）之前，
   而 `hasExhausted` / `deferExhausted`（`:168`、`:265-288`）又会把 `attempts >= 10` 的条目推到 `now + 5min` 并移到队尾。
   ⇒ `attempts=10` 的复核台账**永远不会进入** verify 分支；`attempts=9` 时只剩 1 次机会。

**修复（1 行）**：`scheduleVerify` 把复核台账的 `attempts` 写 **0**（`:313-315`）。

为什么这一行就够了、且不破坏"有界"：

- `verifyAtMs != null` 只可能由 `scheduleVerify` 产生（`recordFailure` / `defer` / `register` 都只**保留**该字段，从不新建）；
  所以"重开 attempts"只影响"删除主体已完成"这一新阶段。
- 复核失败仍走 `recordFailure`：`attempts 0→1→…→10`，退避 10s/20s/40s/80s/160s/300s（封顶），到 10 后 `deferExhausted`
  接管（推 5min + 队尾）—— **有界，不是无限重试**，与普通待重试台账的收敛方式完全相同。
- 未新增字段、未新增状态机、未新增配置项；`Pending` JSON 形态不变（`attempts` 本就在）。

同步更新的注释（不改行为）：`Pending` record 的 `verifyAtMs` 说明（`:121-124`）、`scheduleVerify` javadoc
（`:289-306`，新增 fix5 段说明"为何必须重开预算"）、`SessionPurgeRetryRunner` 类注释的"有界"条目（`:31-32`）、
`AiSessionPurgeService` 里 complete 分支的注释（`:442-443`）。这些正是 fix4 留下的"attempts/MAX_ATTEMPTS 语义不变"
这类**已经描述旧行为**的文字，已逐处改到与代码一致。

---

## 1. 两个确定性用例（真正走 runOnce / verifyMemoryOnly，不是只测 getter）

都加在 `backend/src/test/java/com/example/configmgr/ai/session/AiSessionPurgeServiceTest.java`（**只跑 1 个类为主**，
另跑 `SessionPurgeRetryRunnerTest` 作邻接回归）。该类已装配**真** `AiSessionPurgeService` + 真 `SessionPurgePendingStore`
+ 真 `SessionPurgeGuard` + 真 `RedisChatMemoryRepository` + H2 + `PurgeFakeRedis`，只有 `SessionGate` 与租约是 mock，
所以下面的断言都穿过真实 `purge → scheduleVerify → runOnce → verifyMemoryOnly → pendingStore.complete`。

### 1.1 `verifyStillRunsAfterTheLedgerAlreadyBurnedItsRetryBudget`（`:962`，M-7 的"0 次"路径）

```
seedFullSession(...)                       // 真会话：记忆/run/上下文/DB 两行/附件
pendingStore.register(...) + recordFailure ×10   // 台账精确铺到 attempts=10（= 生产里"主体连续失败后耗尽"）
service.purge(SESSION_A, "test")           // 运维再发一次 DELETE，这次主体成功 ⇒ scheduleVerify
runner.runOnce(completeAt + 120_000)       // 到点（真实 MEMORY_VERIFY_DELAY_MS=60s）
```
断言：`report.attempted()==1`、`report.completed()==1`、`scheduled.attempts()==0`、`chat:mem:*` 残留被删净、
台账消失、DB 两表行数与复核前**完全相等**（复核不碰 DB）。时间全部由 `runOnce(nowMs)` 注入，无 sleep、无线程运气。

### 1.2 `exhaustedNormalPendingStillOnlyDefersAndKeepsItsAttemptBudget`（`:1009`，防过度修复）

普通台账（`verifyAtMs=null`）同样铺到 `attempts=10`，然后 `runOnce(now+1h)`，断言：`attempted==0`、`skipped==1`、
`attempts` 仍为 10、`verifyAtMs` 仍为 null、`nextAt > now`、记忆键仍在、DB 两表各 1 行仍在（证明 `retry` **一次都没跑**）、
墓碑键未写；再跑一轮（`now+1s`）仍 `attempted==0` 且 `attempts` 不重置。
⇒ 普通 pending 的 10 次上限 / 退避 / defer 语义**逐字不变**。

---

## 2. 真实 RED → GREEN 日志（全部实跑，未编辑输出）

命令（隔离副本 `<隔离副本>/_t3-fix5/backend`，离线，不 clean）：

```
mvn -o -B -s maven-settings.xml -Dtest=AiSessionPurgeServiceTest test
mvn -o -B -s maven-settings.xml -Dtest=AiSessionPurgeServiceTest,SessionPurgeRetryRunnerTest test
```

| 轮次 | 树状态 | 结果 | 关键行 |
|---|---|---|---|
| 基线 | 当前 main 原样（未加用例） | **BUILD SUCCESS，27/27** | `Tests run: 27, Failures: 0, Errors: 0` |
| RED-1 | + 两个新用例，未修 | 29 跑 / **2 失败** | 失败点是我写错的一条断言（`guard.isArmed` 恒为 true，因为 `isArmed` 同时看 pending，`SessionPurgeGuard.java:106-123`），与 M-7 无关 |
| RED-2 | 修正该断言，仍未修 | 29 跑 / **1 失败** | `verifyStillRuns…:979 [复核是新阶段…] expected: 0 but was: 10` |
| **RED-3** | 调整断言顺序（行为断言优先），把 `scheduleVerify` 回滚成 `pending.attempts()` | 29 跑 / **1 失败** | `verifyStillRuns…:991 [复核必须真的被执行（修复前被 attempts 短路，恒为 0）] expected: 1 but was: 0` —— 这是 M-7 的**症状本身**：runner 对该台账 **0 次** verify |
| **GREEN** | 恢复修复（`attempts=0`） | **BUILD SUCCESS，30/30** | `Tests run: 29 … AiSessionPurgeServiceTest` + `Tests run: 1 … SessionPurgeRetryRunnerTest` = `Tests run: 30, Failures: 0` |

RED-3 原文片段：

```
[ERROR] com.example...AiSessionPurgeServiceTest.verifyStillRunsAfterTheLedgerAlreadyBurnedItsRetryBudget -- Time elapsed: 0.172 s <<< FAILURE!
org.opentest4j.AssertionFailedError:
[复核必须真的被执行（修复前被 attempts 短路，恒为 0）]
expected: 1
 but was: 0
	at ...AiSessionPurgeServiceTest.verifyStillRunsAfterTheLedgerAlreadyBurnedItsRetryBudget(AiSessionPurgeServiceTest.java:991)
```

GREEN 原文片段：

```
[INFO] Tests run: 29, Failures: 0, Errors: 0, Skipped: 0 -- in com.example.configmgr.ai.session.AiSessionPurgeServiceTest
[INFO] Tests run: 1,  Failures: 0, Errors: 0, Skipped: 0 -- in com.example.configmgr.ai.session.SessionPurgeRetryRunnerTest
[INFO] Tests run: 30, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

补充说明（诚实记录）：**RED-1 的第二个失败是我自己的断言错**（"没有成功销毁就不得装守卫"），已改成
"墓碑键 `ai:purge:guard:<sid>` 不存在（守卫只因 pending 而 armed）"。第 2 个用例**在修复前后都是绿的**（它是反例锁，
不是 RED 用例）—— 它的作用是钉住"复核新预算没有泄漏到普通 pending"。

RED-3 的做法：`cp` 出修好的 `SessionPurgePendingStore.java` → `git checkout --` 该文件回基线 → 只为观察真实症状跑一次 →
`cp` 回来。补丁即最终 GREEN 的那棵树（179 行），`git diff` 生成，无手工编辑。

---

## 3. 是否影响普通 pending / API / 业务数据

| 面 | 结论 | 依据 |
|---|---|---|
| 普通 pending（`verifyAtMs=null`） | **不受影响**：10 次上限、`backoffMs`、`defer`、队尾重排逐字不变 | `scheduleVerify` 是唯一的 `attempts` 重置点，普通台账永不经过它；用例 §1.2 直测；既有 `SessionPurgeRetryRunnerTest#exhaustedPendingsAreDeferredAndDoNotStarveDueSessions`（断言"defer 不得重置 attempts"）仍绿 |
| `register` 复用旧台账不重置 attempts | 不受影响 | 未改 `register`；`AiSessionPurgeServiceTest:618/638/666-677` 仍绿 |
| API `complete` 语义 | **不变**：仍是"本次删除主体已完成" | 未改 `executeDeletion` 的 `complete` 计算，只改台账字段值；`complete=true` 用例仍绿 |
| 删除主体 | 不变（一行未动） | 补丁只改 `scheduleVerify` 的入参 + 注释 |
| 业务数据 / DB / 附件 / 上下文 / 会话键 | **不碰** | 复核路径只调 `deleteMemoryKeys` + `pendingStore.complete`；用例断言 DB 两表行数前后相等、业务键/文件不变 |
| 老 JSON 兼容 | 不变 | `attempts` 是老字段，仍写出；无字段增删 |
| 复核预算上限 | 有了自己的预算：最多 10 次自动复核，退避 10s→300s（累计约 30 分钟），之后只 defer | `backoffMs` 求和 + `deferExhausted`；与主体阶段同一收敛口径 |

---

## 4. 未验证的真实边界（明确列出，未声称已验证）

1. **真实 Redis / Redis Cluster 未启动**：本轮全部证据跑在 `PurgeFakeRedis` 上。CAS 脚本（`CAS_SET`）、
   `opsForList().remove/rightPush` 的调用序列**未改动**，但"脚本在真 Redis 上的原子性与回放"本轮未执行。
2. **Cluster 分片**：`ai:purge:pending:<sid>` 与 `ai:purge:retry:queue` 可能落在不同 slot —— 这是既有实现的
   限制（本补丁未加剧也未修复），Cluster 下 `listDue`/`scheduleVerify` 的行为本轮未验证。
3. **多实例并发**：两个 runner 同时处理同一台账（租约抢不到则跳过）本轮未压测；`scheduleVerify` 的 CAS 与
   `recordFailure` 的交错窗口未做红队。
4. **已经在 Redis 里"带 verifyAtMs 且 attempts 已耗尽"的旧台账不会被本补丁自动救活**：本修复是"排程时重开预算"，
   不追溯历史台账。若某个实例跑过 fix4 那版代码并留下 `attempts=10 + verifyAtMs` 的台账，它仍会被短路 ——
   补齐办法是**再发一次 `DELETE /api/ai/session/{sid}`**：`purge` 复用该台账 → 主体成功 → `scheduleVerify`（现已重开为 0）
   → 复核执行。fix4 从未部署（与本次同批未提交），生产面理论上不存在这种台账，故按最小化口径**不做** runner 侧追溯。
5. **未跑全量**（按指示）：只有上述 2 个类；未跑 `SessionPurgePendingStoreTest`、`SessionPurgeGuardFailClosedTest`、
   `AiSessionPurgeMemoryResidueTest`、前端测试。改动面（1 个字段值）对它们是"更宽松的台账"，但这是**未验证**。
6. **60s 真实等待未跑**：复核时刻由 `runOnce(nowMs)` 注入；`MEMORY_VERIFY_DELAY_MS` 只按构造核对（`nextAt=now+60s`），
   既有 `crashResidueIsRemovedOnlyAfterTheOneShotVerify…` 覆盖"未到点不执行"。

已知非阻断观察（不改）：复核台账的 `lastError` 仍保留主体阶段的最后一条错误（例如 `deferred runs: …`），
重开后语义已是"复核阶段"，字段值会显得陈旧；fix4 起就是如此，没有任何逻辑读它（只作取证）。

---

## 5. 补丁与落地检查

```
backend/src/main/java/com/example/configmgr/ai/session/SessionPurgePendingStore.java   +12 -2   （核心 1 行 + 注释）
backend/src/main/java/com/example/configmgr/ai/session/AiSessionPurgeService.java      +2 -1    （注释）
backend/src/main/java/com/example/configmgr/ai/session/SessionPurgeRetryRunner.java    +2 -1    （注释）
backend/src/test/java/com/example/configmgr/ai/session/AiSessionPurgeServiceTest.java  +93 -0   （2 个用例）
4 files changed, 110 insertions(+), 5 deletions(-)
```

只读校验（主仓工作区，未写入任何源码）：

```
$ git apply --check --verbose .superpowers/sdd/2026-10-25-ai-remediation/task-3-fix5.patch
Checking patch backend/src/main/java/com/example/configmgr/ai/session/AiSessionPurgeService.java...
Checking patch backend/src/main/java/com/example/configmgr/ai/session/SessionPurgePendingStore.java...
Checking patch backend/src/main/java/com/example/configmgr/ai/session/SessionPurgeRetryRunner.java...
Checking patch backend/src/test/java/com/example/configmgr/ai/session/AiSessionPurgeServiceTest.java...
（无冲突，APPLY_CHECK_OK）
```

增量性：基线 = 当前 main 工作区（含 fix4/task8-final-fix）。补丁只含本轮 4 文件差异，不含任何 fix4 已落地的内容；
隔离副本基线提交里的 4 个被改文件与 main 当前文件**内容一致**（行尾按 `core.autocrlf=true` 规范化后 `diff` 零差异：
main 工作区 CRLF / git 索引 LF，与 SDD 里既有补丁同一口径）；其余 `backend/src` 文件 `diff -rq` 零差异。

**补丁即验证物**：另起一棵"当前 main 的 `backend/` 原样拷贝"（`<隔离副本>/_t3fix5-patchcheck`），只 `git apply` 本补丁
（4 文件 `Applied cleanly`），再跑同一命令 → `Tests run: 30, Failures: 0, Errors: 0` / `BUILD SUCCESS`。
即交付的 patch 本身在基线树上可复现 GREEN（不是只在我的工作副本里绿）。

本轮对主仓的写入仅两件交付物：`task-3-fix5.patch`、`task-3-fix5-report.md`。主仓源码/测试/配置一行未动、未提交、未推送。
