> 来源=<仓外证据>/cluster-infra-task-review.md（2026-10-25 归档）· 仓外正式过程报告，属历史证据，不代替现行基线/决策文档（docs/02、docs/03、docs/adr/）
# 三实例集群基础设施修复 · 独立任务评审（agent-75）

- 日期：2026-10-25
- 仓库：`<主仓>`（基线 `ae883fb`，评审路径的候选内容与该工作区一致）
- 候选目录：`<验证副本>/_verify-20261025-cluster-infra/backend`（对照基线副本 `_baseline`）
- 评审输入：`<仓外证据>/cluster-infra-review.diff`（1361 行，14 个文件）
- 只读评审：未改任何代码、未运行任何构建/测试、未起服务、未触碰真实拓扑

## 0. 结论

| 维度 | 结论 | 说明 |
|---|---|---|
| **Spec Compliance** | **PASS** | 三项失败修复 + 关闭/配置要求（单文件原始字节/多文件 zip、FAILED/CANCELLED 收敛且不覆盖 COMPLETED、draining 先于有界 5s 窗口、单实例缺省 0 + 三 profile 覆盖）全部落地；探针断言未改、无密钥、无新增端口、未在本机跑真实拓扑 |
| **Task Quality** | **PASS WITH ISSUES** | 无 Critical、无阻塞项；1 个 Important（收敛是一次性写，条目插入点无父作业守卫的残余窗口）+ 10 个 Minor（注释不实、profile 不对称、测试覆盖/命名、时序用例抖动余量、证据时序） |
| **Blockers** | **无** | 可合入/可重跑三实例 Action；Important 项建议作为后续 hardening，不影响本次验收路径 |

## 1. 需求逐条核对

### 1.1 单文件回原始字节，多文件仍 zip —— 满足

- `task/controller/FileController.java:288-311`：`selected.size()==1` 走 `fileStorage.read(storagePath)` 直出原始字节 + xlsx 类型 + 原文件名；`>1` 走 `buildZip` + `application/zip`。
- `FileController.java:313-327`：每个请求编码只取一个文件，**EXPORT 优先、回退 UPLOAD**，与既有 `GET /files/{defCode}`（`:190-211`）优先级一致；编码去重、保持请求顺序。修复前"同一编码 EXPORT+UPLOAD ⇒ zip 里两个同名 entry"的隐患一并消除。
- 头部正确：单文件 `Content-Type` xlsx + `Content-Disposition: attachment; filename*=UTF-8''…`，与 `GET /files/{defCode}` 逐字同口径。上传端点只接受 `.xlsx`（`FileController.java:98-100`、zip 分支 `:70-73`），因此硬编码 xlsx 类型不会错标。
- 证据：`_red1.log` 单编码 sha 不等（`expected 6374cc…`），`_regress3.log` `FileDownloadByteIdentityTest` 2/2 绿。

### 1.2 FAILED/CANCELLED 原子收敛、不覆盖 COMPLETED —— 满足

- `job/repo/JobItemRepository.java:42-44`：单条条件 UPDATE，`WHERE jobId=? AND status IN ('PENDING','RUNNING')` ⇒ COMPLETED/CANCELLED/FAILED 永不匹配；幂等（重复/多实例并发 0 行）。
- `job/service/JobProgressService.java:144-152` + `:181-189`：FAILED 实际落写时，在**同一个 `REQUIRES_NEW` 事务**内收敛；只对 FAILED 收敛（COMPLETED/CANCELLED 的归属刻意不抢）。
- `job/service/JobService.java:499-513`：取消在**自己那个 `REQUIRES_NEW` 事务**内收敛，job CAS / task 联动 / 条目收敛同事务 ⇒ 无"作业 CANCELLED、条目还 RUNNING"的中间态。
- 事务/持久化上下文行为（重点核对）：
  - `flushAutomatically=true` 是必需的：`applyJobOutcome` 只把 `Task` 标脏（`TaskService.java:319-343`，`save` + dirty check），批量 UPDATE 前不 flush、随后 `clear()` 会丢掉任务态更新。当前顺序（CAS → 任务联动 → 收敛）与 `flushAutomatically` 匹配。
  - `clearAutomatically=true` 的两处调用点都安全：`JobService.cancel` 在收敛后只读 `job.getTaskId()`（标量，已加载）；`JobProgressService.finish` 的事务里收敛后无实体写；`JobTerminalWriter.apply` 的 `tryLockForUpdate/applyJobOutcome` 在 `finish`（内层 REQUIRES_NEW，独立 EM）**之后**执行，内层 clear 不会污染外层 EM（`JobTerminalWriter.java:113-137`）。
  - `updateStatusIfInFlight` 自身已带 `clearAutomatically`（`JobRepository.java:105-110`），所以取消路径的 `job` 引用在 CAS 后本就是游离态，不存在"批量写后回写覆盖"。
- 迟到写：收敛后 `finishItem` 因 `ITEM_OPEN` 不匹配返回 0 行（`JobProgressService.java:227-244`、`JobItemRepository.java:63-72`），不翻写终态。取消后 runner 的 `finishItem(CANCELLED, processed, total)` 也会变 0 行（条目已 CANCELLED）⇒ 条目 processed/total 停在取消时刻（报告 §4.3 已声明，属保守口径）。
- 其它 FAILED 写点都已覆盖：`JobWatchdog.java:337`、`ExportJobRunner.java:96/275`、`ImportJobRunner.java:186`、`PrecheckJobRunner.java:267`、`PublishJobRunner.java:314`、`JobService.java:256` 全走 `JobTerminalWriter → JobProgressService.finish`；`StaleJobRecoveryRunner.java:84-96` 本就自己收敛条目（未改动，仍成立）。

### 1.3 draining 先可观测、再有界窗口 —— 满足（机制已源码级复核）

- `health/ShutdownDrainGate.java:49,60-87`：`@EventListener(ContextClosedEvent) + @Order(HIGHEST_PRECEDENCE)` → `markDraining()` → 100ms 切片有界等待 → 返回后事件继续扇出。
- 机制成立性（独立复核，不依赖报告结论）：
  - `spring-context 6.2.12` 源码 `AbstractApplicationContext#doClose`：先 `publishEvent(new ContextClosedEvent(this))`（`:1166`），**之后**才 `lifecycleProcessor.onClose()`（`:1175`）——后者才是停 web server/连接器的位置。因此在事件监听里等待确实推迟连接器关闭。
  - `ApplicationListenerMethodAdapter#resolveOrder`（`:156-158`）读取方法上的 `@Order` ⇒ 方法级 `@Order` 生效，`HIGHEST_PRECEDENCE` 先于所有缺省/LOWEST 监听器。
  - 全仓 `ContextClosedEvent` 监听器只有 `ReadinessService.java:120-124` 与 `ShutdownDrainGate.java:60-62`，都只做幂等置位，无"抢占关闭资源"的第三方监听器。
  - 等待期间连接器未 pause、DispatcherServlet 未销毁 ⇒ 探针能读到 `/health/ready` 的 503，`/health/live` 仍 200。
- `ReadinessService.java:102-114,135-166`：`drainingSince` 成为唯一真源（volatile + 双检），`check()` 一次读取 `since` 同时决定 `ready` 与响应体 ⇒ 修掉实测到的"`draining:false` 但已带 `drainingSince`"的自相矛盾快照；draining 终态不可逆；draining 快路径不探测依赖（`checks={}`）。
- 有界性：`ReadinessProperties.java:23,53,56-79` 校验非负且 ≤60s；等待按 deadline 切片，中断时 `Thread.currentThread().interrupt()` 后立即返回（`ShutdownDrainGate.java:76-82`），不吞中断、不无界 sleep。

### 1.4 缺省 0、三 profile 5s、Redis 降级不影响 ready —— 满足

- `application.yml:501` `drain-window: 0s`（单实例行为不变）；`application-h2tcp.yml:50`、`application-cluster.yml:44`、`application-postgresql.yml:76` 各 5s。
- 三个 profile 文件各自只有**一个**顶层 `app:` 键（无 YAML 重复键覆盖风险）；profile 只覆盖 `drain-window`，其余 readiness 参数继承。
- 绑定链路：`@Component + @ConfigurationProperties(prefix="app.health.readiness")` + `@PostConstruct verify()`；属性绑定发生在初始化前 ⇒ 非法值启动期 fail-fast。
- 5s 与真实反代参数吻合：`scripts/ci/cluster3-run.sh:104` `inter 2s fall 2` + `httpchk GET /health/ready` + `expect status 200` ⇒ 约 4s 判定 DOWN，落在 5s 窗口内。
- Redis 降级语义零改动：`ReadinessService.check()` 的 required/degraded 分支与 `aiCapability()` 原样；Redis DOWN 仍只进 `degraded` + `capabilities.ai.degraded`，不拦 ready。

### 1.5 约束核对

- **探针断言未改**：`scripts/ci/cluster3-probe.mjs` 在工作区无改动（`git status` 无 `scripts/` 变更）；`job-kill-a` 的"0 条 RUNNING/PENDING 条目"断言仍在（`:2933`），`shared-file-download` 的 sha 比对仍在，`readiness-removal` 仍要求窗口内真的捕到 503/draining（`:3056-3081`）。
- **未跑本机真实拓扑**：候选目录只有单测/集成测试日志与 `target/`，无三 JVM/HAProxy/端口占用证据；报告 §4 如实声明。
- **无密钥、无新增端口**：diff 内无凭据、无 8081/5202 新增（h2tcp 用例注释里的 8081 为原文上下文）；测试把 Redis 钉到不可达端口而非真实实例。
- **diff 保真**：对照主仓工作区 `git diff --numstat`，10 个已跟踪文件的新增/删除行数与评审 diff 完全一致（26/0、37/15、24/0、44/3、12/0、47/14、8/0、8/0、12/4、6/0）；4 个新文件为新增。改动集与报告 §4.1 的 14 个文件一致，无夹带。

## 2. Findings

### Important

**I-1 收敛是一次性写，条目插入点无父作业守卫 ⇒ 终态作业仍可能被"迟到开工"重新填入 RUNNING 条目**
`backend/src/main/java/com/example/configmgr/job/service/JobProgressService.java:195-203`

`startItem` 无条件插入 `RUNNING`，没有"父作业仍在途"的守卫（对比 `markItemFinal` 的插入分支专门对父作业行加悲观锁收紧，`:323-359`）。收敛只发生在写终态的那一刻，因此**收敛之后**新插入的条目不会再被收敛，而它们的终态写又恒为 0 行（`ITEM_OPEN` 已不匹配）⇒ 永久 RUNNING：

1. 取消：`JobService.cancel` 提交收敛后，`publishCancellationSideEffects` 才写共享取消标志（`JobService.java:532-537`）；runner 在条目循环顶部检查取消（`ExportJobRunner.java:125`、`ImportJobRunner.java:82`、`PrecheckJobRunner.java:98`、`PublishJobRunner.java:152`），所以窗口只有毫秒级——但确实存在。
2. 看门狗判死/判超时：`JobWatchdog.java:337-345` 同样先写终态再写取消标志，窗口同上。
3. 跨实例 + Redis 降级：`JobCancellationRegistry.cancel` 先写 Redis 再置本地标志（`JobCancellationRegistry.java:94-97`），Redis 写失败时只剩本地生效；另一实例上的 runner 读不到共享标志 ⇒ 会继续按条目推进并在 FAILED/CANCELLED 作业下插入新的 RUNNING 条目，直到自己跑完。这与"多实例 + Redis 降级"的真实形态直接相关。

影响面：验收用例 `job-kill-a` 是 SIGKILL（无迟到 runner），`job-kill-a`/`cancel` 相关单测也没有"活 runner + 收敛并发"的场景，故 RED→GREEN 与 306 都不会暴露它；但它是本次不变量（"终态作业不得残留 PENDING/RUNNING 条目"）唯一的未封闭入口。
建议（不阻塞本次）：把 `startItem` 改成条件插入/先锁父作业行（父作业不在途 ⇒ 返回 null，runner 直接 `break`），或在 runner 退出路径（`AsyncJobExecutor` finally 的 `endRunner`）对"作业已终态"再收敛一次。后者同时能兜住 Redis 不可用时的跨实例情形。

### Minor

**M-1 `ReadinessService` 注释把关闭机制说错**
`backend/src/main/java/com/example/configmgr/health/ReadinessService.java:58`
"Spring Boot 的 `GracefulShutdown` 是同一个 `ContextClosedEvent` 的监听器" 不成立（报告自己也写了"GracefulShutdown 不是事件监听器"）。实际链是"事件扇出 → `lifecycleProcessor.onClose()` → `TomcatWebServer#stop` → `GracefulShutdown#close`"。`ShutdownDrainGate.java:18-38` 的描述是对的，两处注释互相矛盾，会误导后来者（尤其"能不能把等待挪到别处"这类判断）。建议按 gate 的措辞改写。

**M-2 cluster profile 没有 `server.shutdown=graceful`，注释却以 graceful 为前提**
`backend/src/main/resources/application-cluster.yml:41-42`（配置块 `:38-44`）
`application-h2tcp.yml:42` 与 `application-postgresql.yml:68` 都有 `server.shutdown: graceful`，cluster 没有；`application.yml` 也没有。因此"cluster 单独叠加"的部署形态下，draining 窗口结束后连接器是**立即**停止（在途请求被切断），与注释/`ReadinessProperties` 的"它 + timeout-per-shutdown-phase = 停机总时长"叙述不符。要求本身（先宣布未就绪、再有界 5s）仍满足，但建议补 `server.shutdown: graceful`（必要时补 `spring.lifecycle.timeout-per-shutdown-phase`）以与另两个多实例 profile 同口径，或把注释改成"graceful 与否都会立刻关闭监听 socket"。

**M-3 单文件/zip 的判定按"命中数"而非"请求编码数"**
`backend/src/main/java/com/example/configmgr/task/controller/FileController.java:288-311`
`codes=A,B` 但盘上只有 A 时，现在返回单个 xlsx（修复前返回只含 A 的 zip）。按 `codes.length` 分支的调用方会误判响应形态；错误语义也从"打包了能找到的"变成"静默当成单选"。前端 `downloadSelected` 目前无任何调用点（`frontend/src/api/tasks.ts:85` 仅导出），风险低。建议：`selected.size() != requestedCodes.size()` 时返回 404/409，或在注释里把该口径写明。

**M-4 单文件下载沿用 `URLEncoder` 拼 `filename*`（空格变 `+`）**
`FileController.java:296-301`
RFC 5987 的 `filename*` 里 `+` 是字面加号，不是空格；含空格的文件名会被下载成 `a+b.xlsx`。这是从 `GET /files/{defCode}`（`:199-209`）继承的既有缺陷，但单文件分支成为主下载路径后暴露面变大。建议统一改 `UriUtils.encodePathSegment(...)`（中文仍会正确 %XX）。

**M-5 多编码用例名与断言不符，"每编码一条 entry"未被验证**
`backend/src/test/java/com/example/configmgr/file/FileDownloadByteIdentityTest.java:113-127`
`multipleCodesStillReturnAZipWithOneEntryPerCode` 只断言"是 zip 容器"，没有校验 entry 数量/名字，因此"同一编码 EXPORT+UPLOAD 只取一个（消除同名 entry）"这条修复其实没有被钉住。建议解 zip 断言 entry 名集合（`CURRENCY.xlsx`、`DOC_TYPE.xlsx`，恰好 2 条）。

**M-6 FAILED 路径未覆盖"已 COMPLETED 条目不被覆盖"**
`backend/src/test/java/com/example/configmgr/job/service/JobWatchdogItemConvergenceTest.java:110-123`
"不覆盖 COMPLETED"只在取消用例里断言；两条 FAILED 用例只有一个 RUNNING 条目（`persistOpenItem`）。SQL 谓词是同一个，风险很低，但建议在超时/判死用例里再放一条 COMPLETED 条目并断言其 status/processed 不变。

**M-7 draining 用例有一个偏紧的时序断言**
`backend/src/test/java/com/example/configmgr/health/ReadinessDrainWindowTest.java:127-130`
"503 必须出现在窗口前段（< window/2，700ms 窗口即 <350ms）"依赖观测线程被及时调度；在负载高的 CI 上存在抖动风险（断言本身不过宽也不过严，属 flake 余量问题）。可把阈值放宽到 80% 窗口或把窗口放大到 1.5s，不削弱"窗口期可见"的语义。

**M-8 配置值（0s/5s）没有合同用例钉住**
`backend/src/test/java/com/example/configmgr/config/ProfileConfigContractTest.java`
该类正是按 profile 钉参数的地方（已断言 `server.shutdown`、hikari 等），但没有 `app.health.readiness.drain-window`。本次"缺省 0 + 三 profile 5s + ≤60s 校验"只靠评审，未来误改不会被测试拦住。建议补：`application.yml`=0s、三个 profile=5s，并用 `Binder` 绑到 `ReadinessProperties` 验证 relaxed binding 与 60s 上界。

**M-9 `convergeOpenItems` 的 `clearAutomatically` 依赖一条隐含不变量**
`backend/src/main/java/com/example/configmgr/job/repo/JobItemRepository.java:42-44`
"调用方在它之后不再写实体"目前成立（`JobService.cancel` 收敛后只读标量，`JobProgressService.finish` 之后无实体写），但没有任何地方记录这条约束；将来在该事务里于收敛之后再 `save` 一个实体，变更会被 `clear()` 静默丢弃。建议在方法 javadoc 里点名这条不变量（或把收敛放到事务末尾的最后一条语句并注释）。

**M-10 证据时序：40/40 那次 green 跑的不是最终字节**
`_green_final2.log`（Finished 16:14:21）期间 `JobItemRepository.java` / `JobProgressService.java` 的 mtime 是 16:14:08，即编译发生在最后一次编辑之前；最终字节由 `_regress3.log`（Finished 16:17:01，`.class` mtime 16:15:08–16:15:10）覆盖，且该轮包含三个新用例 2/2、3/3、1/1 全绿。**结论不变**（最终代码已由 306/306 覆盖），只是报告里"GREEN（三项+冲突出例）40/40"应标注"最后一版字节由后续 306 轮覆盖"，避免读者以为 40/40 就是终态证据。

## 3. 已做的验证（全部只读）

| 验证 | 方式 | 结果 |
|---|---|---|
| diff 与工作区一致 | `git diff --numstat`（health/job/task-controller/resources）对比评审 diff 逐文件 +/- | 10 个已跟踪文件完全一致；4 个新文件为纯新增 |
| 基线是 HEAD 内容 | `diff` 工作区 vs `_baseline` 的 FileController | 差异方向与评审 diff 相反且逐行相同 ⇒ 基线 = `ae883fb`，来源一致 |
| 关闭链机制 | 解 `spring-context-6.2.12-sources.jar` 读 `AbstractApplicationContext#doClose`、`ApplicationListenerMethodAdapter#resolveOrder` | 事件先于 lifecycle stop；方法级 `@Order` 生效 |
| 监听器集合 | 全仓 grep `ContextClosedEvent` | 仅 2 个，均幂等置位 |
| YAML 绑定 | 逐 profile grep 顶层 `app:` / `drain-window` / `shutdown` | 单个 `app:`；0s 缺省、三 profile 5s；cluster 缺 graceful |
| 持久化上下文 | 读 `applyJobOutcome`、`updateStatusIfInFlight`、`JobTerminalWriter.apply`、`convergeOpenItems` | `flushAutomatically` 必需且充分；`clearAutomatically` 当前安全 |
| 迟到写 | 读 `finishItem`/`ITEM_OPEN`/`allowedJobStatuses` | 终态后 0 行，不翻写 |
| runner 取消点 | grep 四个 runner 的 `isCancelled` | 均在条目循环顶部 + 分片边界 ⇒ I-1 窗口为毫秒级 |
| 探针未被放宽 | `git status` 无 `scripts/` 改动；读 `cluster3-probe.mjs:2933/3056-3081` 与 HAProxy 配置 | 断言原样；`inter 2s fall 2` 与 5s 自洽 |
| 测试证据 | 读 `_red1.log`/`_red_final.log`/`_green_final2.log`/`_regress3.log` + 文件 mtime | RED 如实失败、GREEN 40/40、回归 306/306；最终字节由最后一轮覆盖（见 M-10） |
| 残留物 | grep `System.out`/`printStackTrace`/`TODO`/`FIXME`/`TEMP-RED`（改动范围） | 无命中 |

未做（按指令）：未重跑测试、未起进程/容器、未访问真实三实例拓扑、未改任何文件。

## 4. 边界与残余风险（供主 agent 决策）

1. **真实三实例拓扑仍未验证**：Tomcat 真实 socket 关闭时刻、HAProxy 摘流时序、5s 是否稳定覆盖 `inter 2s × fall 2` 只能由 Action 复核。本机结论的强度止于"Spring 事件/生命周期顺序 + 配置自洽"。
2. **等待发生在 `ContextClosedEvent` 扇出期间**：这依赖 Spring `doClose` 的固定次序（已按 6.2.12 源码确认）。若未来升级到把 graceful shutdown 挪进事件阶段的版本，注释与设计需要重新审视。
3. **I-1 的残余窗口**在 Redis 降级 + 跨实例取消时会被放大；本次不阻塞验收，但建议下一批收口。
4. **取消/判死后条目 processed/total 不再被迟到写更新**（数字更保守），属已声明的行为变化；若产品侧需要"取消时条目进度精确到最后一次分片"，需改为在收敛时带上当时的 processed（当前 UPDATE 未写该列）。
