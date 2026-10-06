# V6 核验：combined 分支「僵尸作业恢复 / StaleJobRecoveryRunner」

- 被核验分支：`ai-example-claude-opus-5.5+deepseek-v4-pro`（工作副本 `/e/temp/ai-example-code/ai-example-claude-opus-5.5+deepseek-v4-pro`，HEAD = `a98d0b2`）
- 核验日期：2026-10-06
- 结论：**【实测-符合】**（附带 2 条边界说明，见第 5 节）
- 原始记录附件（同目录）：
  - `V6-附件-timeline-and-logs.log`（时间线、kill/netstat、H2 直查结果、重启日志摘录）
  - `V6-附件-backend-restart.log`（崩溃后重启的完整控制台日志，67 行）
  - `V6-附件-backend-restart2-running-branch.log`（补充场景 RUNNING 分支重启的完整控制台日志，67 行）
  - 崩溃前那个后端实例的完整日志见 `V5-附件-backend-console.log`（job 19 出现在其末行 `Starting job 19 type=IMPORT taskId=10`）

---

## 1. 核验目标（分支自述原文与出处）

| # | 自述原文 | 出处（该分支内文件:行） |
|---|---------|----------------------|
| S1 | 「作业偶发卡 RUNNING \| 异步任务与创建事务竞态（ObjectOptimisticLockingFailure） \| **afterCommit 后再启动异步执行 + 僵尸作业启动恢复**」 | `docs/verification-results.md:187`（缺陷表 #8） |
| S2 | 源码自述：「服务重启后的作业恢复：把停留在 RUNNING/PENDING 的作业标记为 FAILED，避免出现永远"运行中"的僵尸作业。」 | `backend/src/main/java/com/example/configmgr/job/service/StaleJobRecoveryRunner.java:15-18` |

核验前先读源码确认实现（未做任何修改）：

- `StaleJobRecoveryRunner.java:19-38`：`@Component implements ApplicationRunner`，`run()` 上标 `@Transactional`；`jobRepository.findAll()` 过滤 `status == RUNNING || status == PENDING`，逐个 `setStatus(FAILED)`、`setFinishedAt(LocalDateTime.now())`、`save`，并 `log.warn("作业 #{} [{}] 在服务重启时中断，已标记为 FAILED", job.getId(), job.getJobType())`。
- **触发时机：仅进程启动时执行一次**。全仓 `grep -rn "@Scheduled\|EnableScheduling" backend/src/main/java` → `NONE FOUND`，即不存在定时扫描。
- **处理动作：仅标记 FAILED + 写 finishedAt，不重新排队、不重试、不改任务状态**。
- 作业状态机（`job/entity/Job.java:78`）：`PENDING / RUNNING / COMPLETED / FAILED / CANCELLED`。
- 作业记录表（`resources/db/migration/V1__schema.sql:128-160`）：`jobs`（主表）、`job_items`（条目进度）、`validation_issues`（问题）。

---

## 2. 执行环境与命令

与 V5 同一实例、同一命令，仅端口覆盖：

```
cd backend
mvn -B -DskipTests package            # BUILD SUCCESS
export DEEPSEEK_API_KEY=dummy-for-verification
java -jar target/config-mgr.jar --server.port=18292
```

- 触发长时作业的数据集：`TO_CFG_big.xlsx`（5000 行；该分支 `ImportJobRunner` 每写满 `batch-size=100` 行就 `Thread.sleep(demo-batch-delay-ms=300)`，故 5000 行导入理论耗时 ≥15s）。**未修改任何配置文件**，一切参数为该分支默认值。
- 杀进程：`taskkill //F //PID <18292 的监听 PID>`（模拟崩溃，非优雅关闭）。
- 直查库：从 fat jar 内解出 H2 → `unzip -o -j target/config-mgr.jar 'BOOT-INF/lib/h2-*.jar' -d /e/temp/v5v6/h2lib`，再用 `java -cp h2lib/h2-2.3.232.jar org.h2.tools.Shell -url "jdbc:h2:file:E:/temp/ai-example-code/ai-example-claude-opus-5.5+deepseek-v4-pro/backend/data/config_mgr_db" -user sa -password "" -sql "..."`（运行期该文件被后端独占，会报 `Database may be already in use`，故运行期一律走 REST）。

---

## 3. 实际观察结果

### 3.1 主场景：作业执行中被强杀 → 重启恢复（完整时间线）

| 时刻（HH:MM:SS.mmm） | 事件 | 证据来源 |
|---|---|---|
| 18:55:37.622 | 后端实例 PID **11484**（从 `netstat -ano \| grep 0.0.0.0:18292` 反查） | `V6-附件-timeline-and-logs.log` B 节 |
| 18:55:37.986 | 创建导入任务 `taskId=10`，上传 `TO_CFG_big.xlsx`（5000 行） | 同上 |
| 18:55:38.070 | 作业创建：`jobs` 行 `jobId=19, task_id=10, job_type=IMPORT` | `POST /api/tasks/10/jobs` → 201 |
| 18:55:38.071 | 后端日志：`AsyncJobExecutor : Starting job 19 type=IMPORT taskId=10` | `V5-附件-backend-console.log:180` |
| 18:55:39.455 / 40.658 / 41.927 | `GET /api/jobs/19` 三次（每次新连接）→ **均 `status=PENDING, progress=0, startedAt=null`** | `raw/V6.poll.jsonl` |
| **18:55:41.966** | **`taskkill //F //PID 11484`**（作业启动后约 3.9s，仍在执行中） | 同上 |
| 18:55:42.117 | `taskkill` 返回 exit=0（`成功: 已终止 PID 为 11484 的进程`） | 同上 |
| 18:55:43.193 | `netstat -ano \| grep 18292` → 只剩 TIME_WAIT 客户端套接字，**无 LISTENING**；`tasklist \| findstr java` 无 java 进程 | 同上 |
| （停机后直查库） | `jobs`：`19 \| 10 \| IMPORT \| PENDING \| 0 \| 0 \| started_at=null \| finished_at=null`；`config_staging_rows where task_id=10` → **count = 0** | `V6-附件-timeline-and-logs.log` D 节 |
| 18:56:00.161 | 重启后端：日志 `Started ConfigMgrApplication in 7.074 seconds` | `V6-附件-backend-restart.log:62` |
| 18:56:00.216 | 日志 `DataSeedRunner : Seed data already exists, skipping` | 同上 :63 |
| **18:56:00.268** | **日志 `WARN c.e.c.j.service.StaleJobRecoveryRunner : 作业 #19 [IMPORT] 在服务重启时中断，已标记为 FAILED`** | 同上 :64 |
| （重启后查 API） | `GET /api/jobs/19` → `{"status":"FAILED","finishedAt":"2026-10-06T18:56:00.262768","startedAt":null,...}` | `raw/V6.job19.after-restart.json` |

日志原文（逐字）：

```
62 2026-10-06T18:56:00.161+08:00  INFO 26180 --- [config-mgr] [           main] c.e.configmgr.ConfigMgrApplication       : Started ConfigMgrApplication in 7.074 seconds (process running for 7.661)
63 2026-10-06T18:56:00.216+08:00  INFO 26180 --- [config-mgr] [           main] c.example.configmgr.seed.DataSeedRunner  : Seed data already exists, skipping
64 2026-10-06T18:56:00.268+08:00  WARN 26180 --- [config-mgr] [           main] c.e.c.j.service.StaleJobRecoveryRunner   : 作业 #19 [IMPORT] 在服务重启时中断，已标记为 FAILED
```

**作业状态前后对照（主场景）**

| 时刻 | `jobs.status` | `started_at` | `finished_at` | 观测手段 |
|---|---|---|---|---|
| 创建后、执行中（18:55:39-41） | `PENDING` | null | null | `GET /api/jobs/19` ×3 |
| 进程被杀后（18:55:43，重启前） | `PENDING` | null | null | 停机后用 H2 Shell 直查 `jobs` |
| 重启恢复后（18:56:00） | **`FAILED`** | null | `2026-10-06T18:56:00.262768` | H2 直查 + `GET /api/jobs/19` |

### 3.2 关键观察：真实崩溃留下的是 PENDING 残留，不是 RUNNING

- 作业执行期间三次 `GET /api/jobs/19`（独立连接、读已提交数据）**全部返回 `PENDING`**，`progress=0`。
- 原因是每个作业 runner 整体是**一个 `@Transactional`**（`ImportJobRunner.java:55-56` 等）：`job.setStatus(RUNNING)` 与最终终态写在同一事务里，进程被杀时事务未提交，`RUNNING` 从未落库；连暂存行也一并回滚（`task_id=10` 的 `config_staging_rows` 计数为 0）。
- 因此文档缺陷表 #8 里「作业偶发卡 RUNNING」描述的**持久化表现**与实际不符（库中残留是 `PENDING`）；但 `StaleJobRecoveryRunner` 的过滤条件同时覆盖 `RUNNING|PENDING`，两种残留都能恢复，声明的主体行为不受影响。
- 另注：`POST /api/tasks/10/jobs` 的**创建响应体**里 `status` 显示 `RUNNING`（且带 `startedAt`），而库中实为 `PENDING`——这是异步线程与响应序列化之间的竞态，不是库中真实状态。判断状态必须重新 `GET /api/jobs/{id}`（与 V5 第 5 节第 2 点同一现象）。

### 3.3 补充场景：RUNNING 分支的人工构造验证

由于 3.2 说明的原因，正常崩溃无法留下已提交的 `RUNNING` 行。为覆盖该分支，先停止后端，再用 H2 Shell 把残留作业 19 置为 `RUNNING`，然后正常重启：

```
$ taskkill //F //PID 26180                                    # 停止上一实例
$ java -cp h2lib/h2-2.3.232.jar org.h2.tools.Shell ... -sql \
  "update jobs set status=RUNNING, started_at=CURRENT_TIMESTAMP, finished_at=NULL where id=19"
(Update count: 1, 8 ms)
$ ... -sql "select id, job_type, status, started_at, finished_at from jobs where id=19"
ID | JOB_TYPE | STATUS  | STARTED_AT                 | FINISHED_AT
19 | IMPORT   | RUNNING | 2026-10-06 18:56:22.886989 | null
(1 row, 13 ms)
$ java -jar target/config-mgr.jar --server.port=18292          # 重启
```

重启日志（`V6-附件-backend-restart2-running-branch.log`）：

```
62 2026-10-06T18:56:32.285+08:00  INFO 24708 --- [config-mgr] [           main] c.e.configmgr.ConfigMgrApplication       : Started ConfigMgrApplication in 6.931 seconds
63 2026-10-06T18:56:32.387+08:00  WARN 24708 --- [config-mgr] [           main] c.e.c.j.service.StaleJobRecoveryRunner   : 作业 #19 [IMPORT] 在服务重启时中断，已标记为 FAILED
```

`GET /api/jobs/19` → `{"status":"FAILED","startedAt":"2026-10-06T18:56:22.886989","finishedAt":"2026-10-06T18:56:32.382017",...}`

（**该场景的初始 `RUNNING` 状态是人工用 SQL 构造的，用于覆盖 RUNNING 分支；`FAILED` 结论来自真实重启运行。**）

### 3.4 恢复动作的效果边界（实测）

- 恢复只改 `jobs` 表。作业 19 的 `errorCount` 仍为 0，`job_items` 为空，`validation_issues` 无记录——即**不产生任何"失败原因"信息**，只把状态改成 FAILED。
- 关联任务未被联动：`GET /api/tasks/10/overview` 显示 `task.status=ACTIVE`、`task.currentStep=UPLOAD`、`items[0].status=PENDING`，**不会**因为作业被恢复判定而变为 FAILED/可重试。合流后若前端有"僵尸作业自动清理并提示重试"的预期，需要另行处理任务状态。
- 启动顺序实测：`DataSeedRunner`（18:56:00.216）→ `StaleJobRecoveryRunner`（18:56:00.268），两者都是 `ApplicationRunner`，均无 `@Order`。

---

## 4. 与声明逐项对照

| 声明 | 实测 | 判定 |
|---|---|---|
| S1「僵尸作业启动恢复」（重启后能发现残留作业） | 崩溃重启后 18:56:00.268 出现恢复 WARN，针对残留作业 #19 | **符合** |
| S2 恢复对象为 `RUNNING/PENDING` | 真实崩溃残留为 `PENDING` → 被恢复；人工构造的 `RUNNING` → 也被恢复 | **符合** |
| S2 处理动作 = 标记 `FAILED` | `GET /api/jobs/19` 两次重启后均为 `FAILED` | **符合** |
| S2 同时写 `finishedAt` | `finishedAt` = 恢复时刻（18:56:00.262768 / 18:56:32.382017），而非崩溃时刻 | **符合** |
| S2 触发时机 | 仅在启动时一次（无 `@Scheduled`/`EnableScheduling`；仅出现在启动后 0.1s） | **符合** |
| S1 中「作业偶发卡 **RUNNING**」的持久化表现 | 真实崩溃后库中残留状态为 `PENDING`（`RUNNING` 从未提交）；该措辞与实际不符，但恢复过滤条件覆盖两种状态，行为结论不变 | **措辞修正**（不影响行为判定） |
| S1 中「根因 ObjectOptimisticLockingFailure」 | 本次全部运行（V5 的 18 个作业 + V6 的 1 个）**未出现**任何 `ObjectOptimisticLockingFailure` / 行锁异常日志 | **本次未复现**（不作为判定依据） |

---

## 5. 结论

**【实测-符合】**

声明的主体行为——「作业执行中后端进程被杀，重启后 `StaleJobRecoveryRunner` 能检测到残留作业并标记为 FAILED」——在真实强杀 + 重启的完整链路上被直接观测到（job 19：PENDING → FAILED，日志 WARN 原文可查），且 `RUNNING` 分支经人工构造后同样被处理。

两点需要带入合流的边界说明（非不符，但影响使用预期）：

1. **恢复只在启动时执行一次，且只改 `jobs` 状态**：既不重新排队/重试，也不改关联任务状态（实测任务 10 仍为 `ACTIVE`、条目仍为 `PENDING`），也不写任何失败原因（`errorCount=0`、无 `validation_issues`）。作业在运行中"卡死"（进程存活但线程不推进）不会被这套机制清理，只有重启才会。
2. **崩溃后的残留必然是 `PENDING`，不会看到 `RUNNING` 残留**：因为每个作业 runner 的整个执行体是单个 `@Transactional`，作业进度/状态在作业结束前对其他连接完全不可见（实测三次轮询均为 `PENDING/progress=0`，暂存行也全部回滚）。合流时若其他分支依赖"作业一开跑 DB 里就能看到 RUNNING/进度"，会与 combined 分支的实际行为不符；反之，若要保留本机制，必须保留 `jobs` 表 `PENDING` 状态的残留语义。

---

## 6. 未覆盖 / 已知边界

- 未验证「作业在进程存活时卡 RUNNING（例如线程死锁/长事务）能否被恢复」——本分支无定时扫描，按源码与实测推断不会，但未构造该场景（构造需改代码或注入故障，超出本项范围）。
- 未验证恢复对 `job_items`、任务步骤、前端展示的联动（实测无联动，仅确认任务状态未变）。
- 未覆盖 `EXPORT/PRECHECK/PUBLISH` 三种作业类型被杀后的恢复（恢复逻辑与作业类型无关，仅对 `IMPORT` 做了端到端实测）。
