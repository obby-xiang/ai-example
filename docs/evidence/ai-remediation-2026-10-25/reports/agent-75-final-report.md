> 来源=<仓外证据>/agent-75-final-report.md（2026-10-25 归档）· 仓外正式过程报告，属历史证据，不代替现行基线/决策文档（docs/02、docs/03、docs/adr/）
# 三实例集群基础设施：失败 run 40173316364 的三项修复报告（agent-75）

- 日期：2026-10-25
- 主仓：`<主仓>`（**未改动**：本报告所有改动只在验证副本里）。
  复制时的基线是 `965e6ef`；之后主仓已被其他 agent 推进（现 `ae883fb`，改动集中在
  `ai/gate`、`ai/session`、`ai/web`、`frontend`、`scripts`）—— 与本报告改动的
  `health/`、`job/`、`task/controller`、`resources/application*.yml` **零重叠**，可直接按 §3 清单移植。
- 验证副本：`<验证副本>/_verify-20261025-cluster-infra/backend`（只从主仓复制 `backend/src` + `backend/pom.xml`；
  不含 `target/` `data/` `config/`、不含任何密钥；保留了 scanner/默认模型/任务总线、RedisClusterScanSupport、
  park 帧序等最新在飞改动）
- 只读基线对照副本：`<验证副本>/_verify-20261025-cluster-infra/_baseline`（同一时刻从主仓复制、未做任何修改，
  用于区分"我的回归"与"本机环境噪声"）
- 构建：`<工具安装目录>/JetBrains/IntelliJ IDEA/plugins/maven-plugin/lib/maven3/bin/mvn.cmd`，**全程无 `clean`**，
  `-o` 离线，未起服务/容器、未触真实端口（8081/5202 未出现）、未提交推送、未改九册。

## 0. 结论

| 验收用例 | 失败现象（run 40173316364） | 根因 | 状态 |
|---|---|---|---|
| `shared-file-download` | A 上传 sha256 `4172fd…`，B 下载 sha256 `2207a8…` | `/files/download` **无论命中几个文件都 buildZip**：单编码回的是"再包一层的 zip"，而响应头仍声明 xlsx | 已修（单文件回原始字节，多文件仍打包） |
| `job-kill-a` | job #5 判 FAILED，`job_items` 残留 `RUNNING, processed=1` | 看门狗只写作业行；**条目收敛只存在于 `StaleJobRecoveryRunner`**，而它只扫 PENDING/RUNNING 作业——被判 FAILED 的作业它根本不看 | 已修（FAILED 在终态事务内收敛、CANCELLED 在取消事务内收敛，COMPLETED 不覆盖） |
| `readiness-removal` | SIGTERM 后探针只看到 `+2ms 200` → `+53ms socket hang up` → 之后 ECONNREFUSED，从未读到 503 | draining 只被写进日志：`ContextClosedEvent` 之后**没有任何等待**，同一关闭流程立即走 lifecycle stop → `TomcatWebServer#stop` → `GracefulShutdown#close`（`connector.pause()` + `closeServerSocketGraceful()`） | 已修（新增 `ShutdownDrainGate`：先置 draining，再有界等待 `drain-window`，然后才交给优雅停机） |

**未验证边界**：真实三实例拓扑（3 JVM + HAProxy + docker raise/pause + SIGKILL/SIGTERM 的 socket 时序）
本机不跑（用户约束），仍需 GitHub Action 重跑；Tomcat 真实 socket 关闭时刻在本机用"最紧排布的
同序监听器"建模（见 §3.3）。

## 1. 逐项根因与修复

### 1.1 `shared-file-download`：单编码下载被 zip 包裹（不是共享目录失效）

**证据链（不是猜测）**

1. `cluster3-probe.mjs` 的用例先 `GET /files/templates?codes=CURRENCY` 拿到 `template.body`，
   把**同样字节**multipart 上传到 A，再 `GET /files/download?codes=CURRENCY` 到 B/C/代理，
   逐字节比 sha256（`scripts/ci/cluster3-probe.mjs:2568-2626`）。
2. 探针在比对 sha 之前先断言 `status===200` 并通过了 —— 若 A 写的文件在 B 上找不到，
   `buildZip` 里 `fileStorage.read(...)` 会抛异常（500/404），不可能返回 200 + 另一串字节。
   ⇒ **共享目录/相对键解析是好的**，差的只是字节。
3. 代码上唯一能改变字节的就是 `downloadSelected` 无条件打包：
   `FileController.java`（修复前）`byte[] zip = buildZip(taskId, files);` + 三元表达式只改
   `Content-Type/filename`，正文永远是 zip。用一个 xlsx 再包一层 zip ⇒ 字节必然不同
   （zip 有本地文件头/时间戳/重新压缩），且响应头谎称 xlsx。
4. 前端契约也是一致的：`frontend/src/api/tasks.ts:84-86` 注释"下载勾选的文件（单个 xlsx 或 zip）"，
   `downloadFile(id, defCode)` 走的是 `GET /files/{defCode}`（原始字节）。

**修复**（`backend/src/main/java/com/example/configmgr/task/controller/FileController.java`）

- 新增 `selectOnePerCode(...)`：每个请求编码只挑**一个**文件，`EXPORT` 优先、回退 `UPLOAD`
  （与 `GET /files/{defCode}` 同一优先级），保持请求顺序并对编码去重；
  顺带修掉"同一编码同时有 EXPORT+UPLOAD 时 zip 里出现两个同名 entry"的隐患。
- `selected.size()==1` ⇒ 直接 `fileStorage.read(storagePath)` 返回原始字节 + xlsx 类型 + 原文件名；
  `>1` ⇒ 仍然 `buildZip` + `application/zip`。`download-all` 语义不变（本来就是 zip）。
- 断言未做任何放宽：探针的 `checkEq(digest, uploadedSha)` 原样保留。

**RED → GREEN**

- RED（`_red1.log`，修复前代码）：
  `[单编码下载必须与上传件同字节（修复前是 zip 包裹 ⇒ 与上传件不同）] expected: "6374ccd9…" but was: "bb8f5396…"`
- GREEN（`_green_final2.log`）：`FileDownloadByteIdentityTest` 2/2 通过（单文件字节相等 + 非 zip 容器 +
  Content-Type/Disposition；多编码仍是 zip）。

### 1.2 `job-kill-a`：看门狗只收敛作业行，`job_items` 永久 RUNNING

**证据链**

1. 失败证据：`cluster3-r2-evidence/cases/job-kill-a/failure.txt` ——
   `被中断作业残留 RUNNING/PENDING 条目数…[{"id":5,"jobId":5,"defCode":"CURRENCY","status":"RUNNING","processed":1,"total":5}]`；
   同一用例里"作业 FAILED / finishedAt / ERROR issue / 重试 COMPLETED"都已通过 ⇒ 失败点只在条目。
2. 代码：条目行由 `JobProgressService#startItem` 以 `REQUIRES_NEW` 独立提交为 RUNNING
   （`JobProgressService.java`），SIGKILL 后**不再有任何** `finishItem/updateItem`。
   看门狗 `JobWatchdog#judge` 只调 `terminalWriter.complete(...)`（作业行 + 任务态 + SSE），
   **没有条目收敛**；唯一会归零条目的是 `StaleJobRecoveryRunner`，而它的扫描集合是
   `PENDING/RUNNING` 作业 —— 作业已被判 FAILED，重启时**压根不在集合里**（实例 a 重启日志：
   "僵尸作业恢复：无需处理（PENDING/RUNNING 作业 0 个）"）。

**修复**

- `backend/src/main/java/com/example/configmgr/job/repo/JobItemRepository.java`
  新增 `convergeOpenItems(jobId, terminalStatus)`：
  `UPDATE JobItem SET status=:terminalStatus WHERE jobId=:jobId AND status IN ('PENDING','RUNNING')`，
  `@Modifying(flushAutomatically = true, clearAutomatically = true)`。
  `flushAutomatically` 是必须的：取消路径的同一事务里 `applyJobOutcome` 只把 `Task` 标脏未 flush，
  少了它，随后的 bulk update + `clear()` 会把任务态更新丢掉（回归实测：task 仍 ACTIVE）。
- `backend/src/main/java/com/example/configmgr/job/service/JobProgressService.java`
  `finish(...)` 在**终态实际落写且为 FAILED** 的同一 `REQUIRES_NEW` 事务里调用收敛（`convergeOpenItemsLocked`）。
  覆盖看门狗判死/判超时、执行器失败、导出回滚收敛三条路径。`COMPLETED` 刻意不收敛（不掩盖缺陷）。
- `backend/src/main/java/com/example/configmgr/job/service/JobService.java`
  `cancel(...)` 在**它自己的 REQUIRES_NEW 事务内**（`updateStatusIfInFlight` + `applyJobOutcome` 之后）
  收敛为 `CANCELLED`。这样"作业 CANCELLED 但条目 RUNNING"在事务原子性上不可能出现，
  且不抢取消路径的条目写归属（`finishItem/markItemFinal` 的 CANCELLED 分支有专门用例守着：
  父作业已 CANCELLED 时仍允许补记 CANCELLED 及其 processed/total）。
- 不触碰 `StaleJobRecoveryRunner`（它仍是重启兜底，语义不变）。不自动重排队、不产生业务副作用：
  重跑仍是用户显式发起的新作业；旧作业条目为终态，任务态只联动一次。

**RED → GREEN**

- RED（`_red1.log`）：`JobWatchdogItemConvergenceTest` 2/2 失败（超时路径与心跳失联路径都
  `expected: "FAILED" but was: "RUNNING"`）。
- 再次 RED（`_red_final.log`，最终用例 + 临时去掉取消收敛）：
  `expected: "CANCELLED" but was: "RUNNING"`。
- GREEN（`_green_final2.log`）：`JobWatchdogItemConvergenceTest` 3/3
  （超时→FAILED、心跳 STALE→FAILED、取消→CANCELLED 且 COMPLETED 条目不被覆盖），
  同一批 `JobSideWriteGuardTest` 22/22、`JobTerminalStateCasTest` 12/12 全绿（这两类是条目写的守卫用例）。

### 1.3 `readiness-removal`：draining 只有日志，没有可观测窗口

**证据链**

1. `cases/readiness-removal/draining-observations.json`：298 次观测里只有 1 次 200
   （`draining:false, ready:true`，且响应体里已经带了 `drainingSince` —— 说明 `markDraining` 与
   `check()` 并发、字段跨线程读到半更新状态），随后 `+53ms socket hang up`、其余 296 次
   `ECONNREFUSED`。**一次 503/draining 都没读到。**
2. 实例日志：`07:39:21.553 ReadinessService: 实例进入 draining` →
   `07:39:21.555 GracefulShutdown: Commencing graceful shutdown` → `07:39:29.618 complete`
   （在途 AI 流跑完）。即 draining 后约 2ms 就进入停机。
3. Spring Boot 3.5.14 字节码核对（`javap`，`~/.m2/.../spring-boot-3.5.14.jar`）：
   `GracefulShutdown` 不是事件监听器；`TomcatWebServer#stop` 会走到 `GracefulShutdown#close(Connector)`，
   其实现就是 `Connector.pause()` + `ProtocolHandler#closeServerSocketGraceful()`。
   而 `AbstractApplicationContext#doClose` 的次序是「先扇出 `ContextClosedEvent`，再
   `lifecycleProcessor.onClose()`」⇒ **事件阶段是唯一能"先宣布未就绪、还不关 socket"的窗口**，
   但原实现没有任何等待，于是窗口 = 0。

**修复**

- 新增 `backend/src/main/java/com/example/configmgr/health/ShutdownDrainGate.java`：
  `@EventListener(ContextClosedEvent) @Order(Ordered.HIGHEST_PRECEDENCE)` —— 先
  `readiness.markDraining()`（幂等、终态），再有界等待 `app.health.readiness.drain-window`
  （100ms 切片、可被中断、上界 60s），返回后才允许事件扇出结束 ⇒ lifecycle stop ⇒ 连接器关闭。
  等价于进程内 `preStop: sleep N`，不依赖编排清单。
- `ReadinessProperties`：新增 `drainWindow`（缺省 **0**，即单实例行为不变）+ 启动期校验
  （非负、≤60s）。`application.yml` 显式写 `drain-window: 0s`（配置可见），
  `application-cluster.yml` / `application-h2tcp.yml` / `application-postgresql.yml` 各写 **5s**
  （≥ HAProxy `inter 2s × fall 2`，并与 `timeout-per-shutdown-phase: 20s` 相加为停机总时长）。
- `ReadinessService`：
  - `drainingSince` 成为**唯一真源**（volatile，先写后读一次取齐），`isDraining()` 即
    `drainingSince != null`；删掉 `AtomicBoolean` 双字段 ⇒ 消除"draining=false 但 drainingSince 已存在"
    的自相矛盾快照；draining 一旦置位**永不清除**（不会在 draining 期间恢复 available）。
  - 关闭事件兜底监听加 `@Order(HIGHEST_PRECEDENCE)`，只做幂等置位（窗口由 gate 负责）。
  - 硬依赖（db/storage/seed）与 Redis 降级不摘流的语义**完全未动**（`required()/capability()` 契约、
    draining 快路径、probe 池都保持原样）。

**RED → GREEN**

- RED（`_red3.log` / `_red_final.log`，最终用例 + 临时去掉等待）：
  `[从 draining 可见到连接器关闭必须 ≥ drain-window 的 80%（实得 3ms/5ms，配置 700ms）]`。
- GREEN（`_green_final2.log`）：`ReadinessDrainWindowTest` 1/1 ——
  ① 连接器关闭那一刻 `HealthController#ready()` 已是 503 + `draining=true` + `ready=false` + `checks={}`；
  ② 从 `drainingSince` 到关闭 ≥ 80% 窗口；
  ③ 并发观测者在窗口**前段**（<50% 窗口）就看到了 503，且早于关闭；
  ④ 关闭后仍 503/draining，不恢复 available。

## 2. 测试命令与结果（全部用 IDEA Maven，无 clean，离线）

| 阶段 | 命令（在副本 backend 下） | 结果 |
|---|---|---|
| 基线编译 | `mvn -B -o -DskipTests test-compile` | BUILD SUCCESS（711 java 源） |
| RED ①文件+②作业 | `mvn -B -o test -Dtest=FileDownloadByteIdentityTest,JobWatchdogItemConvergenceTest` | 4 tests, 3 failures（sha 不等；两条路径 item 仍 RUNNING） |
| RED ③readiness | `mvn -B -o test -Dtest=ReadinessDrainWindowTest` | 1 test, 1 failure（窗口 3.7ms vs 700ms） |
| RED（最终用例） | `mvn -B -o test -Dtest=ReadinessDrainWindowTest,JobWatchdogItemConvergenceTest`（临时去掉等待/取消收敛） | 4 tests, 2 failures（5ms vs 700ms；CANCELLED 仍 RUNNING） |
| GREEN（三项+冲突出例） | `mvn -B -o test -Dtest=FileDownloadByteIdentityTest,JobWatchdogItemConvergenceTest,ReadinessDrainWindowTest,JobSideWriteGuardTest,JobTerminalStateCasTest` | **40/40 通过** |
| 最终回归 | `mvn -B -o test -Dspring.data.redis.port=1 -Dtest='Job*Test,Export*Test,Import*Test,Precheck*Test,PublishJob*Test,TaskFinalizedGuardTest,TaskDelete*Test,File*Test,Health*Test,Readiness*Test,ProfileConfigContractTest,ClientIpResolverTest,TaskCenterQueryTest,RunnerActive*Test,StaleJobRecovery*Test,MigrationSetGuardTest,PostgresMigrationSqlContractTest'` | **306 tests / 0 failures / BUILD SUCCESS** |

### 2.1 回归数字

- 修复前（我的第一轮）: 295 tests / 17 failures —— 经未改动基线 A/B 证明**其中 13 个是本机环境噪声**：
  本机 127.0.0.1:6379 上有 Redis，测试上下文连上去后读到历史 `job:cancel:<id>` 键
  （作业 id 每个新库从 1 开始），`JobCancellationRegistry.isCancelled` 命中 ⇒ 执行器把作业收成
  CANCELLED。未改动基线 `_baseline` 在同样 ambient Redis 下同样报
  `ExportFlowJobTest … expected: COMPLETED but was: CANCELLED`（`_baseline/_base_ambient.log`）。
- 修复后（无 Redis: `-Dspring.data.redis.port=1`，与 CI 同环境）：**306 tests / 0 failures / BUILD SUCCESS**
  （`_regress3.log`），包含 job/file/health/readiness/task/runner-active/stale-recovery 全量相关类，
  以及 `MigrationSetGuardTest`(9) 与 `PostgresMigrationSqlContractTest`(1)
  （迁移集未改动，V1..V6 指纹校验仍绿）。
- 关键类逐个：`JobSideWriteGuardTest` 22/22、`JobTerminalStateCasTest` 12/12、
  `JobWatchdogItemConvergenceTest` 3/3、`FileDownloadByteIdentityTest` 2/2、
  `ReadinessDrainWindowTest` 1/1、`FileControllerStorageLifecycleTest` 7/7、
  `HealthEndpointWiringTest` 4/4、`ReadinessServiceTest` 12/12、
  `ExportFlowJobTest` / `ImportFlowJobTest` / `ExportFailureCompensationTest` /
  `ExportJobRunner*Test` 全绿。

> 说明：为了让用例在任何机器上都与 CI 同环境，两个全上下文用例的 `@TestPropertySource` 里显式钉了
> `spring.data.redis.port=1`（CI 无 Redis；这样本机也不会把 `job:cancel:*` 写进开发机的 Redis）。

## 3. 改动清单（副本内精确路径）

新增：

- `backend/src/main/java/com/example/configmgr/health/ShutdownDrainGate.java`（draining 窗口闸门）
- `backend/src/test/java/com/example/configmgr/file/FileDownloadByteIdentityTest.java`
- `backend/src/test/java/com/example/configmgr/job/service/JobWatchdogItemConvergenceTest.java`
- `backend/src/test/java/com/example/configmgr/health/ReadinessDrainWindowTest.java`

修改（生产）：

- `backend/src/main/java/com/example/configmgr/task/controller/FileController.java`（单文件原始字节）
- `backend/src/main/java/com/example/configmgr/job/repo/JobItemRepository.java`（`convergeOpenItems`）
- `backend/src/main/java/com/example/configmgr/job/service/JobProgressService.java`（FAILED 收敛）
- `backend/src/main/java/com/example/configmgr/job/service/JobService.java`（CANCELLED 收敛）
- `backend/src/main/java/com/example/configmgr/health/ReadinessService.java`（单真源 draining + 事件顺序）
- `backend/src/main/java/com/example/configmgr/health/ReadinessProperties.java`（`drainWindow` + 校验）

修改（配置）：

- `backend/src/main/resources/application.yml`（`drain-window: 0s` + 说明）
- `backend/src/main/resources/application-h2tcp.yml`（`5s`）
- `backend/src/main/resources/application-postgresql.yml`（`5s`）
- `backend/src/main/resources/application-cluster.yml`（`5s`）

未触碰：`scripts/ci/cluster3-probe.mjs`、`cluster3-run.sh`、workflow、迁移 SQL、AI/park 帧序
（agent-73 范围）、九册与历史结论。生产代码**无** `System.out`/`printStackTrace`/TODO/TEMP 标记、
无 3 位以上 sleep 常量；`git diff --check` 等价检查（见 §5）。

## 4. 未验证边界（不得当成已通过）

1. **真实三实例拓扑未跑**：3 JVM + HAProxy + docker pause/unpause + SIGKILL/SIGTERM 的 socket 时序
   只有 GitHub Action 能证。本机只证到：业务代码在真实 H2/Flyway 下行为正确、Spring 事件/生命周期
   顺序正确（含 Spring Boot 3.5.14 字节码核对）。
2. **readiness 的"Tomcat 真实 socket 关闭时刻"**：本机用 `Ordered.LOWEST_PRECEDENCE` 监听器建模
   （比真实链路更紧：真实关闭发生在事件扇出之后的 lifecycle stop）。这证明"事件阶段等待 ⇒
   连接器关闭被推迟"，但"5s 窗口足以让 HAProxy `fall 2 × inter 2s` 完成摘流"仍需 Action 实测
   （理论：检测 ≈2–4s < 5s）。
3. **取消路径的 job_item 进度数字**：取消事务会把在途条目先记 CANCELLED，执行器随后若还活着，
   它的 `finishItem(CANCELLED, processed, total)` 会因条目已终态而返回 false ⇒ 条目 processed/total
   停在取消时刻的值（状态正确、数字更保守）。job 级 progress/total 不受影响。
4. **本机 ambient Redis**：本机 6379 有 Redis（未读写其内容，只在跑测时把端口钉到不可达端口）。
   第一轮回归的 13 个 CANCELLED 失败由此产生，已用未改动基线 A/B 证明与本次改动无关。
5. **PostgreSQL / Redis 5.0.14 Cluster**：本次改动不涉及迁移与 Redis 键布局；真实 PG/Cluster 仍未验证
   （沿用既有结论，不因本报告而改变）。
6. **probe 断言未改**：`shared-file-download` 的 sha 比对、`job-kill-a` 的"0 条在途条目"、
   `readiness-removal` 的 503 捕获窗口全部原样；本次只改产品行为。

## 4.1 移植到主仓的清单（下一步由主 agent/集成方执行）

从 `<验证副本>/_verify-20261025-cluster-infra/backend` 复制以下 14 个文件到主仓同名路径：

```
src/main/java/com/example/configmgr/health/ShutdownDrainGate.java        （新增）
src/main/java/com/example/configmgr/health/ReadinessService.java
src/main/java/com/example/configmgr/health/ReadinessProperties.java
src/main/java/com/example/configmgr/job/repo/JobItemRepository.java
src/main/java/com/example/configmgr/job/service/JobProgressService.java
src/main/java/com/example/configmgr/job/service/JobService.java
src/main/java/com/example/configmgr/task/controller/FileController.java
src/main/resources/application.yml
src/main/resources/application-h2tcp.yml
src/main/resources/application-postgresql.yml
src/main/resources/application-cluster.yml
src/test/java/com/example/configmgr/file/FileDownloadByteIdentityTest.java        （新增）
src/test/java/com/example/configmgr/job/service/JobWatchdogItemConvergenceTest.java （新增）
src/test/java/com/example/configmgr/health/ReadinessDrainWindowTest.java          （新增）
```

随后：主仓 backend 全量测试（无 Redis）→ 提交 → 重跑三实例 Action（`gh workflow run cluster-3node.yml`），
重点看 `shared-file-download` / `job-kill-a` / `readiness-removal` 三条由 FAIL 转 PASS，且其余用例不退化。
副本里的 `_*.log` 与 `target/` 是运行产物，不属于交付。

## 5. 复核清单（写报告前已核对）

- 正式代码无临时/调试残留：`grep -rn "TEMP-RED|System\.out|printStackTrace|TODO|FIXME" src/main` → 无命中。
- 副本与基线逐文件 diff 仅 11 个生产/配置文件 + 3 个新用例（无其他意外改动）。
- 三项需求各有一条真实行为用例（H2 + 真实存储目录 + 真实 Spring 事件总线，非 mock 自证），
  且都经历了"先 RED 后 GREEN"，断言文本未放宽。
- draining 语义：置位后不可逆；`drain-window` 缺省 0（单实例行为不变），多实例 profile 5s。
- 条目收敛：只匹配 `PENDING/RUNNING`；`COMPLETED` 永不覆盖；FAILED/CANCELLED 各自在"写作业终态的同一事务"内。
