> 来源=<仓外证据>/cluster-r3-triage.md（2026-10-25 归档）· 仓外正式过程报告，属历史证据，不代替现行基线/决策文档（docs/02、docs/03、docs/adr/）
# 三实例集群验证 · run 40174291244 失败根因分诊（只读）

- 日期：2026-10-25
- 仓库：`<主仓>`，HEAD `ae883fb`（`fix: 修复集群扫描与挂起续跑`）
- 工作树：未提交的 park 帧序补丁（3 后端生产文件 + 2 后端测试 + `frontend/src/utils/parts.ts` + 1 前端测试）
- 失败日志：`<仓外证据>/cluster3-r3-failed.log`（714 行，完整作业日志）
- 佐证：`<验证副本>/_cluster3-baseline-artifacts`（run 40159406033 的逐用例证据，HEAD 之前的提交）、
  `<验证副本>/_verify-20261025-cluster-infra/backend`（候选修 A：文件字节 / 作业条目 / readiness）、
  `<仓外证据>/agent-75-final-report.md`（A 的证据链）、
  `<验证副本>/_verify-20261025-park-seq/` + `<仓外证据>/park-seq-task-review.md`（park 补丁的复审）
- 纪律：未改动仓库、未起本机真实拓扑、未读任何密钥、未用单测反推真实拓扑结论。

---

## 0. 结论速览

`PASS 9 / FAIL 7`（日志 642 行）。7 个 FAIL 按"是否已有候选修复"分三类：

| 类别 | 用例 | 判定 |
|---|---|---|
| 候选 A（`_verify-20261025-cluster-infra`）已覆盖 | `shared-file-download`、`job-kill-a`、`readiness-removal` | 产品缺陷；候选有 RED→GREEN 单测，**真实拓扑未复跑** |
| 当前 park 帧序补丁已覆盖 | `streaming-kill-a` | 产品缺陷（`SseChatEmitter` 唤醒补帧下界） |
| 产品行为变更 / 无候选 | `cold-start-3`、`suspend-cross-instance`、`suspend-kill-a` | 见 §2.1 / §2.2 / §2.4；其中 cold-start 的候选目录为空壳 |

`redis-outage-recovery`、`session-purge-cross-instance`（27.5s）、`history-pagination`、`seq-cross-instance`、
`idempotency-cross-instance`、`config-hot-reload`、`reattach-any-instance`、`same-tcp-across-abc`、
`proxy-no-sticky` 共 9 条 PASS —— 说明共享 DB/Redis/文件、无粘滞代理、跨实例 seq 主链路本身是通的。

---

## 1. 16 条用例账本（日志逐行）

| # | 用例 | 结果 | 耗时 | 日志行 |
|---|---|---|---|---|
| 1 | cold-start-3 | FAIL | ~64s | 479、525-538 |
| 2 | proxy-no-sticky | PASS | 204ms | 542 |
| 3 | same-tcp-across-abc | PASS | 61ms | 545 |
| 4 | reattach-any-instance | PASS | 1113ms | 548 |
| 5 | history-pagination | PASS | 2343ms | 551 |
| 6 | seq-cross-instance | PASS | 8143ms | 554 |
| 7 | suspend-cross-instance | FAIL | 292ms | 557 |
| 8 | idempotency-cross-instance | PASS | 1594ms | 560 |
| 9 | session-purge-cross-instance | PASS | 27501ms | 563 |
| 10 | config-hot-reload | PASS | 1445ms | 566 |
| 11 | shared-file-download | FAIL | 1087ms | 569 |
| 12 | suspend-kill-a | FAIL | 230ms | 573 |
| 13 | streaming-kill-a | FAIL | 71113ms | 578 |
| 14 | job-kill-a | FAIL | 57489ms | 584 |
| 15 | readiness-removal | FAIL | 49050ms | 592 |
| 16 | redis-outage-recovery | PASS | 23697ms | 636 |

---

## 2. 逐用例：现象 / 证据 / 根因 / 归属

### 2.1 cold-start-3 —— 三实例并发冷启动时 Flyway baseline 竞态

**现象**（日志 479、525-538）
`[FAIL] cold-start-3：三实例并发冷启动失败：c: 实例 c 启动失败（exit=1 signal=null）`；
实例 c 日志尾部：

```
o.f.core.internal.command.DbValidate : Successfully validated 9 migrations
ConfigServletWebServerApplicationContext : ... Failed to initialize dependency 'flywayInitializer'
Caused by: org.flywaydb.core.api.FlywayException:
  Unable to baseline schema history table "PUBLIC"."flyway_schema_history" as it already exists, and is empty.
Delete the schema history table, and run baseline again.
```

**证据链**
1. 用例在 `resetDatabase()`（探针 1729、1271-1288：停三实例 → 停 H2 → **删库目录与共享文件目录** → 重启 H2）之后，
   用 `startAllConcurrently()`（探针 1672-1682）**同时**拉起 a/b/c，三条 `waitReady` 并行等待 —— 这是真正的空库并发冷启动，
   不是 harness 造出来的假形态。
2. 实例 c 的时序：Hikari 建连 → Flyway 报 `flyway_schema_history` 不存在 → 校验 9 条迁移通过 →
   **尝试 baseline** → 此刻另一实例已把历史表建出来但还没写入行 ⇒ Flyway 拒绝 baseline。
3. 默认配置就是这条路径：`backend/src/main/resources/application.yml:64-67`
   `spring.flyway.enabled=true / locations=classpath:db/migration / baseline-on-migrate=true`（仅 PG profile 关掉，见
   `application-postgresql.yml:48-52`）。**全仓没有任何跨进程的 Flyway 互斥**：`PostgresMigrationConfig` 只换 Java 迁移提供者
   （`config/PostgresMigrationConfig.java:37-51`），`SeedLock` 管的是种子数据（Spring 装配之后），管不到 Flyway。
4. 顺序回退基线在同一用例内成功（探针 1746-1753），随后 9 条用例正常执行 ⇒ 不是数据库/H2/Redis 起不来。

**根因**：多实例对**同一空库**并发执行 Flyway，且 `baseline-on-migrate=true`；
Flyway 的"检查历史表 → baseline"之间没有跨 JVM 互斥，出现"表已被别人建出但为空"的窗口即 fatal。
这是产品/部署口径缺陷（滚动/多副本首次部署的常见形态），不是用例缺陷。

**归属**：候选 B `<验证副本>/_verify-20261025-migration-race/backend` **目录存在但为空壳** ——
与工作树 `backend/src` 逐文件 `diff -rq` 无差异、`target/` 为空（创建于 2026-10-25 16:16），
即 **Flyway 冷启动互斥尚未实现**。→ **仍待修**。

### 2.2 suspend-cross-instance —— 挂起轮的 reattach 连接被 park 收流主动关掉

**现象**（日志 557-558，292ms）
`[FAIL] suspend-cross-instance：等待终帧（done/error）：流已结束（已收=tool_start）`

**证据链**
1. 用例时序（探针 2149-2205）：`createSuspendedRun('a')` 在 A 上挂起 → **先** `reattachOn('b', lastSeq)` 与
   `reattachOn('c', 0)`（2157-2161）→ **后** 才 `confirmOn('c')`（2164）→ 再 `waitTerminal` 收 B/C 两条连接的 done。
2. 产品在 HEAD 的语义：对 `SUSPENDED` 且**仍有 PENDING 条目**的轮，reattach 回放完立即收流
   —— `AiController.java:761-769`（`stillWaitingForInput` 判定见 780-793），配合 park 本身的"挂起即断"
   （`ConfirmGate.java:588` 的 `completeSubscriptions()`、622 行抛 `RunParkedException`；
   `SseChatEmitter.java:73-77` 的设计说明）。也就是说：**在决策之前建立的重挂连接，产品会主动关掉**。
3. 这条收流是 `20c56bb` 引入的（`git blame` 768 行 = 20c56bb）。上一个绿色基线 run 40159406033（03:47Z，20c56bb 之前）
   该用例是 **PASS**（`_cluster3-baseline-artifacts/results.json`），该基线的 A 全量帧序为
   `start(1) → suspended(2) → tool_start(3) → confirm_request(4) → confirm_decision(5) → tool_result(6) → …done(17)`
   （`cases/suspend-cross-instance/A-full.jsonl`）。⇒ 这是 20c56bb 带来的**行为回归**，不是老问题。
4. HEAD 的帧序已被 C1 反转成 `suspended → confirm_request → tool_start`
   （`SpToolCallingManager`：`publishRequestsForParkedBatch` 在 **479** 行、执行循环里的 `out.toolStart(pending)` 在 **540** 行）。
   于是 `createSuspendedRun` 一收到 `confirm_request` 就关连接，`lastSeq` 只到 3；B 带 `?lastSeq=3` 重挂，差量恰好只剩
   一帧 `tool_start(4)`，随后 park 收流关闭 —— 与"已收=tool_start"逐字吻合。

**根因（两段，都要说）**
- 直接原因：`AiController` 对"决策前的 park 重挂"一律 `completeSubscriptions()`，连接活不到续跑终帧。
  用例要求"先挂两条连接、再在别处决策、两条都收到 done"，与该语义**相冲突**。
- 放大因素：请求帧早于 `tool_start`（C1），使差量补帧只剩一条孤立的 `tool_start`，症状看起来像"帧丢了"。

**归属**：当前 park 补丁**不覆盖**（补丁不动 `AiController`；它只把 `tool_start` 前移到请求帧之前，B 的差量会从
"一帧 tool_start"变成"零帧"，断言依旧失败）。这条要么改产品（决策前的 park 重挂不许收流、或限定连接保留窗口），
要么改用例（按产品自述的标准时序：**决策 200 之后**再 GET /events 收终帧，见 `AiController.java:762-765`
"E2E 的标准时序正是「POST 决策/回灌 200 → 立刻 GET /events 收终帧」"）。**需要产品决策，不是纯修 bug。**

### 2.3 shared-file-download —— 单编码下载被 zip 包裹

**现象**（日志 569）
`[FAIL] shared-file-download：共享文件下载（B）sha256 与上传件：期望 "f03f5b14…"，实得 "24577ee7…"`

**证据链**
1. 探针（2587-2642）：`GET /files/templates?codes=CURRENCY` 取模板字节 → 同字节 multipart 上传到 A →
   `GET /files/download?codes=CURRENCY` 到 B/C/代理 → 逐字节比 sha256（2625）。
   下载 HTTP 200 先通过，说明 B 能读到 A 写的文件（共享目录与存储键解析正常）。
2. 产品：HEAD 的 `FileController.downloadSelected`（`task/controller/FileController.java:278-297`）**不论命中几个文件
   一律 `buildZip(...)`**（287 行），只用三元表达式改 `Content-Type/filename` —— 单文件时正文是"再包一层的 zip"，
   响应头却声明 xlsx。前端契约本来就是"单个 xlsx 或 zip"（`frontend/src/api/tasks.ts:84-86`）。
3. 候选 A 的 RED 断言：`expected "6374ccd9…" but was "bb8f5396…"`（`agent-75-final-report.md` §1.1），
   与本次两个 sha 的关系同形（字节不同、长度不同、HTTP 200）。

**归属**：产品缺陷；**候选 A 已修**（单编码回原始字节 + 正确 Content-Type；多编码仍 zip；
`FileDownloadByteIdentityTest` 2/2 GREEN，`_verify-20261025-cluster-infra/backend/_green1.log`）。
真实拓扑未复跑。

### 2.4 suspend-kill-a —— 接管后 `tool_start` 计数为 0

**现象**（日志 572-573）
`[FAIL] suspend-kill-a：接管后 tool_start 恰一次：期望 1，实得 0`

**证据链**
1. 用例（2685-2753）：A 上挂起 → `killInstance('a')`（SIGKILL，端口已关）→ B 侧 `/runs/current` 校验 SUSPENDED 通过 →
   B 决策 200 → `reattachOn('b', lastSeq)` 收到 `done` 通过 → `fullReplayToTerminal('c')` 里统计
   `frameCount(full,'tool_start', run.toolCallId)` = **0**（2717）。
2. 代码上"接管执行"这条路**不会**产生 `tool_start`：`ResumeService` 用 `SpToolCallingManager.resolvePending`
   （`gate/SpToolCallingManager.java:1808-1835`）→ `executeOnce`（1364），全仓 `tool_start` 只有一个发射口
   `SseChatEmitter#toolStart`（`run/SseChatEmitter.java:1112`），只从执行循环的 5 个分支到达（HEAD 499/510/522/532/540）。
   ⇒ 接管实例不补 `tool_start`，这一帧只能来自**挂起那一次调用**。
3. 但按 HEAD 代码，挂起调用在进入 `await`（即 park）之前就已发过 `tool_start`（540 行在 switch 之前）——
   所以"全量为 0"**不能**由 park 帧序缺陷单独解释。
4. 候选 B/当前补丁都不改变这一点：park 补丁只把 `tool_start` 从"请求帧之后"挪到"请求帧之前"，并加去重
   （`emitToolStartOnce`）；对"这一帧是否存在"没有影响。

**根因**：**证据不足，不能定论**。两种机制都与日志相容，需要该用例的证据文件才能区分：
(a) 挂起侧确实发了但归档/回放丢了 —— `FrameStore` 读失败**按契约静默返回部分结果**
（`run/FrameStore.java:58-65`："空结果 ≠ 证明没有帧…读失败不会以异常表达"），
C 的全量回放完全可能少几条；
(b) 挂起侧从未发出（例如该批首个条目走了别的分支）。
建议的区分动作：取 run 40174291244 的 `cases/suspend-kill-a/C-full.jsonl` 与 `takeover.json`，
看 `full` 的 seq 是否连续、`tool_result` 是否只有 1 条；再用 `?lastSeq` 在 B 上做一次差量对照。

**归属**：**无候选覆盖，待查证**。若 (a) 成立，则不是产品缺陷而是可观测性/用例缺陷；
若 (b) 成立，park 补丁的"先发 tool_start"是必要的但不充分（仍要解释 HEAD 540 行为何没发出来）。

### 2.5 streaming-kill-a —— 唤醒补帧把已见帧重发（seq 从 1 开始）

**现象**（日志 578）
`[FAIL] streaming-kill-a：接管补帧起始 seq 必须 > kill 前 lastSeq：期望 >= 3，实得 1`

**证据链（闭环）**
1. 用例（2774-2847）：流式中 `lastSeq=2`（基线同形态记为 `已收=start,heartbeat,message_start`，
   `_cluster3-baseline-artifacts/cases/streaming-kill-a/notes.txt`）→ SIGKILL A → 等 35s 僵尸阈值 →
   `POST /api/ai/startup-resume/scan` 接管 → `reattachOn('c', runId, lastSeq)`（2809）→ 断言
   `streamC.seqs()[0] >= lastSeq+1`（2816）。
2. 代码：HEAD 的 `onWake` 只用 **emitter 级** `lastFanoutSeq` 当补帧下界
   （`run/SseChatEmitter.java:1530`、`from = lastFanoutSeq.get()` 1538、`upperBoundSeq <= from` 提前返回 1539、
   逐帧 `lastFanoutSeq.accumulateAndGet` 1575）。重挂发生在"归档还没有 3..N"的时刻（接管是异步的），
   差量读为空 ⇒ `lastFanoutSeq` 仍为 0 ⇒ 下一次跨实例唤醒把 1..N 全部补发 ⇒ 客户端第一帧 seq=1。
   与"实得 1"逐字吻合。
3. 补丁：每个订阅者记 `wakeFloor`（工作树 `SseChatEmitter.java:666`、`880`、`1580-1588`），唤醒补帧只投
   `seq > wakeFloor`；补丁注释直接点名该三实例形态。

**归属**：产品缺陷；**当前 park 补丁已覆盖**（`SseChatEmitterWakeFloorTest` 场景即"lastSeq=2、暂存 0 帧"）。
遗留：复审 F3 指出共享 `lastFanoutSeq` 在"唤醒丢失"窗口仍可饿死无游标订阅者 —— 与本条症状无关，但
不能当成"唤醒链路已完全无洞"。

### 2.6 job-kill-a —— 作业判 FAILED 后 job_items 仍停在 RUNNING

**现象**（日志 584）
`[FAIL] job-kill-a：被中断作业残留 RUNNING/PENDING 条目数（终态应一致）：[{"id":5,"jobId":5,"defCode":"CURRENCY","status":"RUNNING","processed":1,"total":5}]：期望 0，实得 1`

**证据链**
1. 用例前段全通过（作业 FAILED、finishedAt、ERROR issue、重启后死 runner 键清理、B 重试 COMPLETED、任务下 2 个作业），
   唯一红点就是"旧作业条目仍在途"（2932-2933）。
2. 代码：`JobProgressService.startItem` 用 `REQUIRES_NEW` 单独提交条目 RUNNING
   （`job/service/JobProgressService.java:161-168`），SIGKILL 后不可能再有 `finishItem/updateItem`；
   `finish(...)`（125-155）只写作业行、**不收敛条目**；看门狗 `JobWatchdog.judge`（292）→
   `terminalWriter.complete(...)`（337）同样只写作业行；唯一会归零条目的是 `StaleJobRecoveryRunner`，
   而它只扫 PENDING/RUNNING 作业 —— 已被判 FAILED 的作业不在集合里。
3. 候选 A 的对策：`JobItemRepository.convergeOpenItems`（`status IN ('PENDING','RUNNING') → 终态`），
   在 FAILED 终态同一 `REQUIRES_NEW` 事务内调用，CANCELLED 在取消事务内调用，COMPLETED 刻意不收敛
   （`agent-75-final-report.md` §1.2；`_green1.log` 3/3 GREEN）。

**归属**：产品缺陷；**候选 A 已修**，真实拓扑未复跑。
### 2.7 readiness-removal —— draining 只写日志，探测窗口为 0

**现象**（日志 592）
`[FAIL] readiness-removal：SIGTERM 后 15s 内未捕获 /health/ready 的 draining 证据（ready=false/draining=true）；
观测=[{"ms":14598,"error":"connect ECONNREFUSED 127.0.0.1:23181"}, … 共 8 条全部 ECONNREFUSED]`
同时实例日志有 `07:55:37.800 ReadinessService：实例进入 draining：/health/ready 将返回 503`（622 行）、
`07:55:37.803 GracefulShutdown：Commencing graceful shutdown`（623）、`07:55:45.871 complete`（626）。

**证据链**
1. 探针（3029-3081）刻意先预热一条 keep-alive 连接再 SIGTERM，50ms 紧轮询 `/health/ready`，
   就是为了能读到 draining 的 503 —— 设计本身是对的；15s 内一次 503 都没读到。
2. 代码：HEAD 的 `ReadinessService` 只在 `ContextClosedEvent` 里 `markDraining()` 打一行日志
   （`health/ReadinessService.java:93-102`），**没有任何等待**；同一关闭流程紧接着
   `lifecycleProcessor.onClose()` → `TomcatWebServer#stop` → `GracefulShutdown#close` = `connector.pause()` +
   `closeServerSocketGraceful()` ⇒ 监听 socket 立即关闭 ⇒ 探针只能看到连接被拒。
3. 次要缺陷：HEAD 用 `AtomicBoolean draining` + `volatile Instant drainingSince` 两个字段分别读
   （70-72、93-96 与 `check()` 内），跨线程可能读到"draining=false 但 drainingSince 已存在"的自相矛盾快照
   （候选 A 的证据里实测到过一次，见 `agent-75-final-report.md` §1.3）。
4. 候选 A：新增 `ShutdownDrainGate`（`ContextClosedEvent` + `Ordered.HIGHEST_PRECEDENCE`，先置 draining，
   再**有界等待** `app.health.readiness.drain-window`，然后才交给优雅停机）；`drain-window` 缺省 0、
   多实例 profile（h2tcp/cluster/postgresql）各 5s，启动期上界校验 ≤60s
   —— `health/ShutdownDrainGate.java`、`health/ReadinessProperties.java`、`application-h2tcp.yml` 等；
   RED→GREEN 见 `ReadinessDrainWindowTest`。

**归属**：产品/部署缺口；**候选 A 已修**（真实 Tomcat socket 时序需 Action 复跑；
HAProxy `inter 2s × fall 2` 与 5s 窗口的关系尚未实测）。

---

## 3. 候选修复覆盖矩阵

| 用例 | 当前 park 补丁（工作树 7 文件） | 候选 A `_verify-20261025-cluster-infra` | 候选 B `_verify-20261025-migration-race` | 结论 |
|---|---|---|---|---|
| cold-start-3 | 无关 | 无关 | **空壳（无任何改动）** | 待修 |
| suspend-cross-instance | 只改帧序，不改"决策前 park 重挂收流" | 无关 | 无关 | 待产品决策 |
| shared-file-download | 无关 | 已修（`FileController` 单文件原字节） | 无关 | 候选可解 |
| suspend-kill-a | 不解决"接管无 tool_start"，也不解释"挂起侧已发却为 0" | 无关 | 无关 | 待查证 |
| streaming-kill-a | 已修（`wakeFloor` 每订阅者下界） | 无关 | 无关 | 补丁可解 |
| job-kill-a | 无关 | 已修（`convergeOpenItems`） | 无关 | 候选可解 |
| readiness-removal | 无关 | 已修（`ShutdownDrainGate` + `drain-window`） | 无关 | 候选可解 |

> 候选 A 的 GREEN 全部是**单测/本机**结果（303 tests / 0 failures）；候选 B 无任何改动与日志。
> 按约束不得据此宣称真实拓扑已通过。
---

## 4. 用例缺陷 vs 产品缺陷

| 用例 | 定性 | 理由 |
|---|---|---|
| cold-start-3 | **产品/配置** | 空库并发起 3 个 JVM 是文档化形态（探针已真清库：`resetDatabase` 1271-1288）。Flyway 无跨进程互斥 + `baseline-on-migrate=true` 是产品口径问题 |
| suspend-cross-instance | **产品行为回归（20c56bb）+ 用例协议过时** | 用例按"决策前重挂、连接活到 done"断言；产品自 20c56bb 起对"仍在等"的 park 重挂主动收流（`AiController.java:761-769`），且注释明说标准时序是"决策后再 GET /events"。上一条基线该用例 PASS ⇒ 契约被改、用例没跟上，需显式裁决 |
| shared-file-download | **产品** | 探针拿上传字节比 `/files/download`，与前端契约（单个 xlsx 或 zip）一致；产品无条件 zip 且谎称 xlsx |
| suspend-kill-a | **未定** | 需区分"归档读丢"（可观测性）与"从未发出"（产品）；`FrameStore` 读失败静默返回部分结果，现有断言无法区分 |
| streaming-kill-a | **产品** | 断言直接对应 `onWake` 只按 emitter 级上界去重的缺陷 |
| job-kill-a | **产品** | "作业终态 ⇒ 条目终态"是产品不变量；看门狗只写作业行 |
| readiness-removal | **产品/部署** | 探针预热 keep-alive 轮询是正确手法；产品 draining 窗口为 0，503 不可观测 |

**harness 侧观察（不是本次 7 红的原因，但会影响以后判读）**
1. `fullReplayToTerminal`（1542-1553）只重试"没有终帧"，无法识别"帧少了几条"（`FrameStore` 读失败静默）。
   建议给全量回放加正向对照：`full` 的 seq 连续性 + B 侧独立回放交叉核对。
2. `caseSuspendCrossInstance` 用 `reattachOn('c', 0)` 在决策前建连；若产品维持 park 收流，这个时序应改为
   "决策后重挂"，否则该用例永远红。
3. readiness 用例只落 `observations.slice(-8)`（3079），取证时看不到 ECONNREFUSED 的起点；
   建议把"首个非 200 的时刻"单独写进证据文件。

---

## 5. 残差与下一步（按优先级）

| # | 事项 | 依据 |
|---|---|---|
| 1 | cold-start：实现 Flyway 跨实例互斥（或关闭 `baseline-on-migrate` 并改为"迁移单独作业"） | `application.yml:64-67`；日志 486-526 |
| 2 | suspend-cross-instance：裁决"决策前 park 重挂是否保留连接"；改产品或改用例 | `AiController.java:761-769`；探针 2157-2164 |
| 3 | suspend-kill-a：取 run 40174291244 的 `cases/suspend-kill-a/{C-full.jsonl,takeover.json}`，定位"0 帧"是读丢还是没发 | 探针 2717；`FrameStore.java:58-65` |
| 4 | 候选 A（文件/作业/readiness）、park 补丁（wakeFloor）、Flyway 修，都必须用真实三实例重跑验收；单测不作为通过依据 | 约束原文 |
| 5 | 复审 F3：共享 `lastFanoutSeq` 在"唤醒丢失"窗口仍可饿死无游标订阅者（非本条症状） | `park-seq-task-review.md` F3 |
| 6 | 复审 F1：新前端 spec 的 `kind: string` 破坏 `vue-tsc`（`npm run build` 红） | `park-seq-task-review.md` F1 |

---

## 6. 未证明 / 不要外推

- 本报告没有在真实三实例拓扑上跑任何东西；所有"已修"都只到代码+单测层面。
- `suspend-kill-a` 的"0 帧"必须由该 run 的逐用例证据定论，本报告不做推测性归因。
- `suspend-cross-instance` 的定性依赖"决策前建连是否属于受支持协议"这一产品裁决，不单方面判定产品错。
- 9 条 PASS 只证明共享 DB/Redis/文件与无粘滞代理的主链路可用，不证明 AI 挂起/接管面的整体健康。
