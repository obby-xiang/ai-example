# 后端非 AI 数字规格逐项审视（backend-business）

- **仓库**：`<REPO_ROOT>`（主仓分支 `main`）
- **扫描时 HEAD**：
  - 初扫（业务代码 / yml）：`405e74ebf96b3340c9443c6e3087708814e996f6`（2026-10-31 01:11:19 +0800，`refactor(ai): 删除未上线Redis迁移工具链`）
  - DDL 补扫：`d50531a294436f1cf8329362b536880d0eeb3fd8`（2026-10-31 01:40:36 +0800，`refactor: 清理未上线文件迁移与旧运行兼容`）
- **工作树现态**：DDL 补扫时 `git status --porcelain` = **14 个 M / 6 个 ??**；收尾复核时 = **24 个 M / 6 个 ??**（新增 M 均为 `ai/**` 测试，非本文件、非非 AI 生产代码）。改动集中在 `ai/**` 测试、`docs/任务看板.md` 与本文所在目录，**非 AI 生产代码/配置已干净**。初扫时（`405e74e`）工作树曾为 47 M / 19 D / 7 ??，其中 `application.yml`、`AppProperties.java`、`FileStorageService.java`、`JobHeartbeatRegistry.java`、`JobWatchdog.java` 的在制品已在 `d50531a` 提交；补扫时逐一复核，数值与行号未变。
- **任务边界**：只读代码与配置。未改生产/测试/配置，未执行 git 写操作，未启动服务/测试/浏览器。本文是本次唯一新建文件。
- **扫描对象**：`backend/src/main/java/com/example/configmgr/**`（排除 `ai/**`；全仓 335 个主源文件，非 AI 面 106 个）+ `backend/src/main/resources/application*.yml` 的非 AI 段 + **`backend/src/main/resources/db/migration/**` 与 `backend/src/main/resources/db/postgresql/**` 的非 `ai_*` 业务表 DDL**（含 H2 Java 迁移 `backend/src/main/java/db/migration/V6__Task_child_fk.java`）+ 框架缺省（server / datasource / redis / servlet / lifecycle）。

> **现态变化提示**：初扫时工作树删除的历史文件键迁移工具链（3 个类 + `app.file.key-migration.enabled` 配置键 + `AppProperties.KeyMigration`）已在 `d50531a` 提交，现态不存在；`JobHeartbeatRegistry` / `JobWatchdog` 的 `LEGACY` 锚点分支也已提交删除。相关数值未变（30s / 90s / 10s / 3 / 30m 仍成立），只删了状态分支与注释。DDL 补扫新增 §1.5，业务表数值约束不再列为“未扫描面”。

---

## 0. 口径

### 0.1 路径缩写

| 缩写 | 实际路径 |
|---|---|
| `J` | `backend/src/main/java/com/example/configmgr` |
| `R` | `backend/src/main/resources` |

### 0.2 三个判定维度

| 维度 | 取值 | 判定口径 |
|---|---|---|
| **可调性** | `配置键` | `application*.yml` 有键，或有 `@ConfigurationProperties` 字段且 yml 可覆写；不重编译可改 |
| | `硬编码` | 代码内字面量 / `static final` / 注解内默认值；改值须改代码重编译 |
| | `框架缺省` | 本仓未显式配置，取 Spring Boot / Tomcat / HikariCP 的默认值 |
| **依据** | `有依据` | 注释或文档给出了数值论证、实测、外部规范或正式裁定 |
| | `部分依据` | 注释说明了用途、派生关系或对齐来源，但取值本身无数值论证 |
| | `裸数字` | 无注释、无文档、无实测，仅一个数；含“应有上限但未实现”的缺失项 |
| **异常** | 括号内注明来源 | 例：`裸数字（框架缺省，仓内零说明）` |

### 0.3 分类词表

`超时 / 重试 / 退避 / 轮询 / 池 / 分页 / 缓冲 / 心跳 / TTL / 阈值 / 上限 / 分片 / 其他`

### 0.4 与旧 NUMSPEC 的关系

- 参考 `docs/evidence/补档-NUMSPEC-清单总账.md`（2026-10-10 首查 + 后续追加），但**所有值、行号、存在性均按当前磁盘重查**，不沿用旧结论。
- “旧 NUMSPEC ID”列只做映射；旧文档中已退役/已更名的键按现态标注。旧表未编号的后端项（作业看门狗、就绪探测、Flyway 锁等）标“无（旧表未编号）”。
- 同一数字不同语义**分行登记**；同一语义的“配置键 + 代码缺省”合并在同一行，并在可调性列注明代码出处。
- **业务库 DDL**（`V1`/`V2`/`V6`/`V7` 的现役非 AI 表）此前未被旧清单系统覆盖（旧表只把 `validation_issues.message 1024` 散记在 B2-28）；本次补在 **§1.5 附录 B（N-BE-092..106）**，不再作为“未扫描面”。`ai_*` 表与已删除 `ai_chat_message` 按边界排除/标注历史。

---

## 1. 总账

### 1.1 运行容器 / 数据源 / Redis / 就绪探测（N-BE-001..039）

| 唯一ID | 分类 | 文件:行 | 键或常量/表达式 | 现值+单位 | 作用与消费点 | 配置键/硬编码/框架缺省 | 依据 | 审视建议（不修改） | 旧 NUMSPEC ID |
|---|---|---|---|---|---|---|---|---|---|
| N-BE-001 | 其他 | `R/application.yml:2` | `server.port` | `8081`（端口） | Tomcat 监听端口；前端 Vite 代理、E2E 脚本按此端口连 | 配置键 | 裸数字（yml 无注释；跨端对齐见 `frontend/vite.config.ts`，不在本清单面） | 建议补一行端口规划说明；`application-h2tcp.yml:5-7` 示例的 8081/8082/8083 分配也未成文 | 无 |
| N-BE-002 | 超时 | `R/application.yml:6` | `server.tomcat.connection-timeout` | `120000` ms（=120s） | 连接器读/写空闲超时；`SseWriteBudget` 类注实测写侧取本值（不读客户端 ≈116s 释放），是 SSE 15s 预算之外的宽兜底 | 配置键 | 部分依据（`J/common/sse/SseWriteBudget.java:19-33` 有实测与派生关系；120000 本身无取值论证） | 建议明确“写侧兜底”语义，并评估与 15s 写预算的约 8 倍差是否仍需保留 | B1-01 |
| N-BE-003 | 其他 | `R/application.yml:26` | `spring.messages.cache-duration` | `3600`（秒，=1h） | `ResourceBundle` 消息缓存；`messages_zh/en.properties` 变更后最长 1h 才生效 | 配置键 | 裸数字（无注释；Spring Boot 对无后缀 Duration 按秒解释） | 建议注明单位与“改文案需重启/等待缓存”的运维含义 | 无 |
| N-BE-004 | 上限 | `R/application.yml:33` | `spring.servlet.multipart.max-file-size` | `5MB` | 单文件上传上限；`J/task/controller/FileController.java` 的 xlsx/zip 上传与 AI 附件上传等所有 multipart 请求共用容器限制 | 配置键 | 有依据（`R/application.yml:30-32`：与 `GenerativeFormAttachmentController.MAX_ATTACHMENT_BYTES` 同口径，请求体留 1MB 余量） | 建议核对“5MB 文件 + multipart 边界 + 表单字段”是否确实落在 6MB 内；zip 批量上传时该值是整包上限 | 无（旧 B2-32 记的是框架缺省 1MB/10MB） |
| N-BE-005 | 上限 | `R/application.yml:34` | `spring.servlet.multipart.max-request-size` | `6MB` | 整个 multipart 请求上限；`FileController` 上传、附件表单 | 配置键 | 有依据（同上，1MB 余量口径） | 建议把“单文件 5MB 与整包 6MB 的余量关系”写进接口文档，避免客户端按单文件大小拼多文件包 | 无（同上） |
| N-BE-006 | 超时 | `R/application.yml:81` | `spring.data.redis.timeout` | `2s` | Redis 单命令超时；AI 端点、限流、作业取消标志、共享心跳、任务 SSE 总线 | 配置键 | 有依据（`R/application.yml:74-76`：R4 裁决，实测 5 次串行各等 3s = 15.1s 后收紧到 2s） | 建议保持；若 Redis 跨机房需重新评估 2s 与“端点 ≤2s 失败”承诺 | B1-02 |
| N-BE-007 | 超时 | `R/application.yml:82` | `spring.data.redis.connect-timeout` | `2s` | Redis 建连超时；与命令超时共同决定不可用时的快速失败上界 | 配置键 | 部分依据（同段 R4 注释覆盖，未单独论证） | 建议在注释中把“建连 2s + 命令 2s 的最坏叠加”写明 | B1-03 |
| N-BE-008 | 超时 | `R/application.yml:84` | `spring.data.redis.lettuce.shutdown-timeout` | `200ms` | Lettuce 关停等待；优雅停机时释放 Redis 连接 | 配置键 | 裸数字（无注释；与 2s 命令超时无对照说明） | 建议说明 200ms 是否覆盖连接池关闭与订阅容器停止 | B1-04 |
| N-BE-009 | 池 | `R/application.yml:41-49`（未写键） | `spring.datasource.hikari.maximum-pool-size`（缺省） | `10` 连接 | 默认 profile 的 Hikari 连接池上限；所有 JPA/JDBC 访问共用 | 框架缺省（yml 只写注释，不写键；`minimum-idle` 同样缺省 = 10） | 有依据（`R/application.yml:42-49` 引 HikariCP 官方默认 10 与“不要按并发放大池”的官方建议） | 建议显式写出该键或至少加启动日志回显，避免“配置面看不到”；与 `AsyncConfig` 16 线程的张力见 N-BE-058 | B2-32（合并行） |
| N-BE-010 | 超时 | 无显式键 | Hikari `connectionTimeout`（缺省） | `30000` ms（30s） | 池满时业务线程等连接的上界；作业线程（最多 16）超过 10 条连接时在此排队 | 框架缺省 | 裸数字（框架缺省；`R/application.yml:46-47` 只提“在 connectionTimeout 内排队”，未给值） | 建议与 N-BE-009/058 一并复核：16 作业线程 - 10 连接 = 最多 6 条等 30s | 无（旧 B2-32 未展开） |
| N-BE-011 | 超时 | 无显式键 | Hikari `validationTimeout`（缺省） | `5000` ms（5s） | 连接有效性校验等待；PG profile 显式 2000ms（N-BE-021） | 框架缺省 | 裸数字（框架缺省，仓内零说明） | 建议默认 profile 与 PG profile 的 5s/2s 差异补一句口径 | 无 |
| N-BE-012 | TTL | 无显式键 | Hikari `idleTimeout`（缺省） | `600000` ms（10m） | 空闲连接回收；与 `maxLifetime` 共同决定连接存活 | 框架缺省 | 裸数字（框架缺省，仓内零说明） | 建议与数据库侧连接回收策略对照后再定是否显式化 | 无 |
| N-BE-013 | TTL | 无显式键 | Hikari `maxLifetime`（缺省） | `1800000` ms（30m） | 连接最长寿命；须小于数据库/代理的空闲切断时间 | 框架缺省 | 裸数字（框架缺省，仓内零说明） | 建议核对 PG/负载均衡侧的空闲超时是否大于 30m | 无 |
| N-BE-014 | 池 | 无显式键 | Tomcat `max-threads`（缺省） | `200` 线程 | Web 请求工作线程上限；SSE 进入 async 后释放，但仍影响普通 REST/上传/下载并发 | 框架缺省 | 裸数字（框架缺省，仓内零说明） | 建议与 Hikari 10、Async 16 一起做一次容量式复核（旧 R-68 同面） | B2-32（合并行） |
| N-BE-015 | 池 | 无显式键 | Tomcat `accept-count`（缺省） | `100` 个连接 | 全忙时的等待队列；溢出即拒绝 | 框架缺省 | 裸数字（框架缺省，仓内零说明） | 建议在压测/容量文档中登记，不要只存在于框架默认 | 无 |
| N-BE-016 | 池 | 无显式键 | Tomcat `max-connections`（缺省） | `8192` 个连接 | 连接器最大连接数；SSE 长连接会长期占用连接额度 | 框架缺省 | 裸数字（框架缺省，仓内零说明） | 建议评估“SSE 订阅者 + 普通请求”与 8192 的关系 | 无 |
| N-BE-017 | 上限 | 无显式键 | Tomcat `max-keep-alive-requests`（缺省） | `100` 请求/连接 | HTTP keep-alive 复用上限 | 框架缺省 | 裸数字（框架缺省，仓内零说明） | 建议随容器调优一并登记 | 无 |
| N-BE-018 | 超时 | 无显式键 | `spring.lifecycle.timeout-per-shutdown-phase`（缺省） | `30s` | 仅在 `server.shutdown=graceful` 时生效；默认 profile 未开 graceful，PG/h2tcp 显式 20s | 框架缺省 | 裸数字（框架缺省；仅 PG profile 显式 20s，见 N-BE-023） | 建议在默认 profile 的停机说明中写明“未开 graceful，30s 不生效” | 无 |
| N-BE-019 | 池 | `R/application-postgresql.yml:25` | `spring.datasource.hikari.maximum-pool-size` | `10` 连接 | PG profile 连接池上限；多实例时库侧总连接 = 实例数 × 10 | 配置键（profile） | 有依据（`R/application-postgresql.yml:22-24`：与默认 profile 同口径，并提示多实例总连接核对） | 建议把“实例数 × 10 与库 max_connections”做成部署检查项 | 无（旧 B2-32 Hikari 项） |
| N-BE-020 | 超时 | `R/application-postgresql.yml:28` | `spring.datasource.hikari.connection-timeout` | `5000` ms（5s） | PG 池满时等连接上限；`/health/ready` 的 DB 探针与业务查询共用此池 | 配置键（profile） | 有依据（`R/application-postgresql.yml:26-27`：探针必须在 LB 探测超时内给结论，不能吊 30s） | 建议与 readiness 共享 2s 时限（N-BE-033/037）对齐复核：5s 连接等待 > 2s 探测总时限 | 无 |
| N-BE-021 | 超时 | `R/application-postgresql.yml:29` | `spring.datasource.hikari.validation-timeout` | `2000` ms（2s） | 连接有效性校验等待 | 配置键（profile） | 部分依据（无独立注释；与相邻 connection-timeout 同段） | 建议补一句与 2s 命令/探测时限的关系 | 无 |
| N-BE-022 | 超时 | `R/application-postgresql.yml:36` | `connection-init-sql: SET lock_timeout = '60s'` | `60s` | PG 会话级**锁等待**上限；种子单飞、行锁等；与 H2 `LOCK_TIMEOUT=60000` 同一上界 | 配置键（profile） | 有依据（`R/application-postgresql.yml:30-35`：等待方要等完整一轮播种，但不能无限等；PG 缺省 0 会一直挂） | 建议把“只限等锁、不限语句执行”的口径同步到运维手册 | 无 |
| N-BE-023 | 超时 | `R/application-postgresql.yml:61` | `spring.lifecycle.timeout-per-shutdown-phase` | `20s` | 优雅停机时在途请求收尾上限；与 drain-window 相加是停机总时长 | 配置键（profile） | 部分依据（`R/application-postgresql.yml:60` 只说与 graceful 配套；20s 无取值论证） | 建议与 drain-window 5s 一起给出“总计 25s < 编排器宽限”的关系式 | 无 |
| N-BE-024 | 超时 | `R/application-postgresql.yml:76` | `app.health.readiness.drain-window` | `5s` | PG 多实例关闭时先 503、保持连接器可接受连接，让 LB 摘流 | 配置键（profile） | 部分依据（`R/application-postgresql.yml:73-75`：5s ≥ 常见 LB 2s×2 + 余量；未给具体 LB 配置） | 建议按实际 LB 的探测间隔/失败阈值核算，而不是沿用“常见” | 无 |
| N-BE-025 | 其他 | `R/application-cluster.yml:31` | `spring.data.redis.cluster.max-redirects` | `3` 跳 | Cluster 跨槽迁移期间单请求跟随 MOVED/ASK 的最大跳数 | 配置键（profile） | 部分依据（`R/application-cluster.yml:30`：说明用途；3 跳无独立论证） | 建议登记 resharding 场景的实测或上游推荐值 | 无 |
| N-BE-026 | 超时 | `R/application-cluster.yml:33-36` | `spring.data.redis.timeout` / `connect-timeout` / `lettuce.shutdown-timeout` | `2s` / `2s` / `200ms` | Cluster profile 下与 standalone 同口径；换拓扑不应改超时 | 配置键（profile） | 有依据（`R/application-cluster.yml:7-8,32`：明写与 standalone 同值，R4 的 2s 前提不变） | 建议保持；若未来调 standalone，需同步本组三键 | B1-02 / B1-03 / B1-04 |
| N-BE-027 | 超时 | `R/application-cluster.yml:44` | `app.health.readiness.drain-window` | `5s` | Cluster 多实例关闭摘流窗口 | 配置键（profile） | 部分依据（同 N-BE-024） | 同 N-BE-024 | 无 |
| N-BE-028 | 超时 | `R/application-h2tcp.yml:29` | JDBC URL `LOCK_TIMEOUT=60000` | `60000` ms（60s） | H2 TCP 共享库的锁等待上限；种子单飞等待方要等完整一轮播种（H2 缺省 1s 会在第二实例直接超时） | 配置键（profile，URL 内联） | 有依据（`R/application-h2tcp.yml:56-57`：明确说明 H2 缺省 1s 的失败形态与 60s 的用途） | 建议与 N-BE-022 的 PG 60s 建立同源说明（两库同一上界） | 无 |
| N-BE-029 | 池 | `R/application-h2tcp.yml:35` | `spring.datasource.hikari.maximum-pool-size` | `10` 连接 | H2 TCP 三实例共享库形态；每实例 10，总计 30 | 配置键（profile） | 部分依据（`R/application-h2tcp.yml:34`：与默认口径一致，H2 服务侧无 max_connections） | 建议在共享库容量说明中保留“实例数 × 10”的推导 | 无 |
| N-BE-030 | 超时 | `R/application-h2tcp.yml:36` | `spring.datasource.hikari.connection-timeout` | `5000` ms（5s） | H2 TCP 池满时等连接上限 | 配置键（profile） | 裸数字（无注释） | 建议与 PG 的 5s 统一口径并注明理由 | 无 |
| N-BE-031 | 超时 | `R/application-h2tcp.yml:54` | `app.health.readiness.drain-window` | `5s` | H2 TCP 三实例冒烟/多实例关闭摘流 | 配置键（profile） | 部分依据（`R/application-h2tcp.yml:51-53`：5s ≥ HAProxy inter 2s × fall 2 + 余量） | 同 N-BE-024：按实际代理参数复核 | 无 |
| N-BE-032 | 超时 | `R/application.yml:513` | `app.health.readiness.drain-window` | `0s`（默认关闭） | 默认 profile 不等待；关闭时 `/health/ready` 变 503 后立即交给 graceful shutdown | 配置键 | 有依据（`R/application.yml:508-512` + `J/health/ReadinessProperties.java:41-53`：单实例行为不变，多实例 profile 各自覆盖 5s） | 建议保持并在部署文档中强调“多实例必须覆盖为 5s” | 无 |
| N-BE-033 | 超时 | `R/application.yml:505` | `app.health.readiness.probe-timeout` | `2s` | 所有就绪维度**共享**的总时限；超时维度记 DOWN，端点按期返回 | 配置键（代码缺省同值 `J/health/ReadinessProbeRunner.java:52`） | 有依据（`R/application.yml:496-498`：LB/编排器探测超时通常 1–5s，取 2s 留代理余量） | 建议与 DB 探针 2s 查询超时、PG 5s 连接等待（N-BE-020/037）一起复核：共享 2s 内取不到连接就整维 DOWN | 无 |
| N-BE-034 | 池 | `R/application.yml:506` | `app.health.readiness.probe-threads` | `4` 线程 | 固定 daemon 线程池大小；应 ≥ 探针维度数（当前 db/storage/seed/redis 共 4） | 配置键（代码缺省同值 `J/health/ReadinessProbeRunner.java:54`） | 部分依据（`R/application.yml:499`：应 ≥ 维度数（当前 4）；4 由维度数决定，非独立容量论证） | 建议新增探针维度时同步加线程，否则会排队等共享 2s | 无 |
| N-BE-035 | 池 | `R/application.yml:507` | `app.health.readiness.probe-queue-capacity` | `8` 个等待位 | 探针池等待队列；池满 + 队列满 ⇒ 该维度直接 DOWN（saturated），不改用调用线程 | 配置键（代码缺省同值 `J/health/ReadinessProbeRunner.java:56`） | 部分依据（`R/application.yml:500-501`：必须 >0、饱和即 DOWN；8 无独立论证） | 建议说明“4 线程 + 8 队列”能吸收多少并发探测，避免 LB 高频探测时饱和误报 | 无 |
| N-BE-036 | 上限 | `J/health/ReadinessProperties.java:23` | `MAX_DRAIN_WINDOW` | `60s` | `drain-window` 的启动期硬上界；与 `timeout-per-shutdown-phase` 相加即停机总时长 | 硬编码 | 部分依据（类注 `:22`：必须有上界，过大会被编排器 SIGKILL 宽限期截断；60 无外部数据） | 建议与部署侧 terminationGracePeriodSeconds 对照后确认 60s 合理 | 无 |
| N-BE-037 | 超时 | `J/health/DbReadinessProbe.java:26` | `QUERY_TIMEOUT_SECONDS` | `2s` | `SELECT 1` 的 JDBC 查询超时；取连接上界由 Hikari `connection-timeout` 决定 | 硬编码 | 部分依据（类注 `:14-16`：查询 2s，探针须在 LB 探测超时内给结论；2s 与共享总时限同值但未论证叠加） | 建议明确“查询 2s + 取连接 5s 可能超出共享 2s”时的实际表现（应记 DOWN） | 无 |
| N-BE-038 | 池 | `J/health/ReadinessProbeRunner.java:82` | 探针池 `keepAlive` | `30s` | 探针池线程空闲存活；`allowCoreThreadTimeOut(true)` 后低频探测不常驻线程 | 硬编码 | 裸数字（30s 无注释） | 建议与探测频率一起说明；若 LB 每 2s 探测，线程基本不会空闲到 30s | 无 |
| N-BE-039 | 轮询 | `J/health/ShutdownDrainGate.java:49` | `SLICE_MS` | `100ms` | draining 窗口的等待切片；让第二次 Ctrl-C/编排器信号能及时打断 | 硬编码 | 部分依据（`:48`：说明切片目的；100ms 无取值论证） | 建议保持；切片过大会让停机信号响应变慢 | 无 |

### 1.2 作业 / 任务 / SSE / 线程池（N-BE-040..064）

| 唯一ID | 分类 | 文件:行 | 键或常量/表达式 | 现值+单位 | 作用与消费点 | 配置键/硬编码/框架缺省 | 依据 | 审视建议（不修改） | 旧 NUMSPEC ID |
|---|---|---|---|---|---|---|---|---|---|
| N-BE-040 | 超时 | `R/application.yml:446` | `app.task.sse.write-timeout` | `15s` | 任务通道（`GET /api/tasks/{id}/events`）单帧写出预算；超预算摘除订阅者，调用线程（作业线程/HTTP 线程）按预算脱身 | 配置键（代码缺省同值 `J/config/AppProperties.java:232`，来源 `SseWriteBudget.DEFAULT_TIMEOUT_MILLIS` 见 N-BE-054） | 有依据（`R/application.yml:436-445` + `J/common/sse/SseWriteBudget.java:106-115`：64KB 帧、p99 1.15ms、15s 为四个数量级余量） | 建议保持；若前端改为长轮询/WebSocket，需重算 | B1-28 |
| N-BE-041 | 超时 | `R/application.yml:447` | `app.task.sse.emitter-timeout` | `5m` | 任务 SSE 订阅者（`SseEmitter`）生命周期上限；到点容器关连接，阻塞写抛 IOException | 配置键（代码缺省同值 `J/config/AppProperties.java:253`） | 部分依据（`J/config/AppProperties.java:234-252`：说明 5m 是任务通道自己的流寿命、与 AI 通道解耦；5m 本身无数据） | 建议与前端 EventSource 重连/快照轮询周期一起复核，避免“5m 到点断流”被误判为作业失败 | B1-29 |
| N-BE-042 | 轮询 | `R/application.yml:449` | `app.job.demo-batch-delay-ms` | `300` ms | 作业分片间人为节拍；Export/Import/Precheck/Publish 四个 Runner 的 `Thread.sleep` 调用点 | 配置键（代码缺省同值 `J/config/AppProperties.java:137`） | 裸数字（yml 与字段均无注释；E2E 依赖该节拍可见性，CI 覆写为 150，见旧 S-08） | 建议明确“演示/可观测性节拍”与生产口径，或提供 profile 关闭；不要让产品默认值承担测试可见性 | B1-30 |
| N-BE-043 | 分片 | `R/application.yml:450` | `app.job.batch-size` | `100` 行/批 | 作业分片大小；导出按命中间隔、导入/预检查/发布按行号取模触发快照与 SSE | 配置键（代码缺省同值 `J/config/AppProperties.java:138`） | 裸数字（无注释；CI/E2E 覆写为 10 以造分片边界，见旧 S-08） | 建议给出“分片大小 × 每片同步写库/SSE 成本”的依据，并说明与 300ms 节拍的组合效果 | B1-31 |
| N-BE-044 | 心跳 | `R/application.yml:464` | `app.job.heartbeat-interval` | `30s` | 作业执行器在分片边界续期心跳；判死窗 = 本值 × dead-multiplier；`JobProgressService.markRunning/snapshot/finish` 与进程级 ticker 消费 | 配置键（代码缺省同值 `J/config/AppProperties.java:151`，`@DurationUnit(SECONDS)`） | 有依据（`J/config/AppProperties.java:140-151`：业界 §12 心跳 30–60s 下沿、K8s 10s×3 ≈30s；裁决 Q4） | 建议保持；若 batch-size 调大导致分片间隔 >30s，需依赖 ticker（已有）而非业务 beat | 无（旧 S-10 派生提及，未编号） |
| N-BE-045 | 轮询 | `R/application.yml:469` | `app.job.watchdog.scan-period` | `10s` | 看门狗扫描周期；判死最坏发现延迟 = 判死窗 + 本值 ≈ 100s；E2E 120s 窗口由此派生 | 配置键（代码缺省同值 `J/config/AppProperties.java:177`，`@DurationUnit(SECONDS)`） | 部分依据（`J/config/AppProperties.java:168-177`：1 分钟扫表对本项目偏保守；10s 本身无外部数据） | 建议把“判死窗 + scan-period + 余量 = E2E 窗口”的派生式固定进测试文档 | 无（旧 S-10） |
| N-BE-046 | 阈值 | `R/application.yml:470` | `app.job.watchdog.dead-multiplier` | `3`（倍） | 失联判死倍数；判死窗 = heartbeat-interval × 本值 | 配置键（代码缺省同值 `J/config/AppProperties.java:184`） | 有依据（`J/config/AppProperties.java:179-184`：K8s failureThreshold 3 / TIBCO 约 3.5×心跳；裁决 Q4） | 建议保持；与 max-execution-time 的“必须小于”关系已有启动期 fail-fast | 无（旧 S-10） |
| N-BE-047 | 超时 | `R/application.yml:471` | `app.job.max-execution-time` | `30m` | 作业绝对上限；RUNNING 用 `startedAt`、PENDING 用 `createdAt` 计时，到点判超时 FAILED | 配置键（代码缺省同值 `J/config/AppProperties.java:164`，`@DurationUnit(SECONDS)`） | 有依据（`J/config/AppProperties.java:156-164`：Hangfire InvisibilityTimeout 官方缺省 30min、§12 30–60min 下沿；裁决 Q3） | 建议与 CANCEL_TTL 1h、runner-active TTL 65m 一起说明“三个时间窗”的关系（见 N-BE-050/053） | 无（旧 S-10） |
| N-BE-048 | TTL | `J/job/service/JobHeartbeatRegistry.java:92` | `DEFAULT_BEAT_TTL` | `90s` | 共享心跳 TTL 的回落值（配置非法/缺失时）；正常值 = heartbeat × multiplier = 90s | 硬编码 | 有依据（`:91`：缺省判死窗 30s×3；`:201-211` 配置非法时回落本值） | 建议保持；与 N-BE-044/046 的派生值同值但语义是“兜底”，避免误改一处 | 无 |
| N-BE-049 | TTL | `J/job/service/JobHeartbeatRegistry.java:90` | `RUNNER_ACTIVE_TTL_MARGIN` | `5m` | runner-active 长 TTL 的额外余量；TTL = 2×max-execution-time + 本值 | 硬编码 | 部分依据（`:89`：在 2 倍之上再留 5m 覆盖判死/收尾延迟；5m 无数据） | 建议用“判死窗 + 收尾上限”推导该余量，而不是固定 5m | 无 |
| N-BE-050 | TTL | `J/job/service/JobHeartbeatRegistry.java:186` | `runnerActiveTtlFor` 表达式 | `2×max-execution-time + 5m` = `65m`（默认 30m 时） | `job:runner-active:<jobId>` 长 TTL 锚点；删除闸门按它判断 runner 是否活着 | 硬编码（派生） | 有依据（`:178-192`：TTL 必须严格大于 max-execution-time，取 2×+5m；示例 2h→4h5m） | 建议保持；若 max-execution-time 调到 >30m，TTL 自动放大，注意 Redis 内存与删除闸门延迟 | 无 |
| N-BE-051 | 轮询 | `J/job/service/JobHeartbeatRegistry.java:392` | 进程级 ticker 周期 | `max(1000ms, beatTtl / 3)` = `30s`（默认） | 共享心跳续期节拍；覆盖长时间无分片节拍的 runner | 硬编码 | 部分依据（类注 `:196-199`：TTL/3 可容忍两次续期抖动；1000ms 下限无注释） | 建议说明下限用途（beatTtl 配得很小时的保护），并确保 ticker 周期 < 判死窗 | 无 |
| N-BE-052 | TTL | `J/job/service/JobHeartbeatRegistry.java:224-226` | `deadJudgeLease()` | `= beatTtl` = `90s`（默认） | 判死单飞键 `job:deadjudge:<jobId>` 的 `SET NX PX` 租约；防多实例重复写 issue | 硬编码（派生） | 部分依据（方法注 `:223`：持有者崩溃后其他实例到期接管；未独立论证） | 建议明确“单飞租约 = 判死窗”是否足够覆盖一次 issue + 终态写入 | 无 |
| N-BE-053 | TTL | `J/job/service/JobCancellationRegistry.java:42` | `CANCEL_TTL` | `1h` | `job:cancel:<jobId>` 取消标志存活时长；覆盖单作业最长运行窗口，终态后仍保留取证 | 硬编码 | 部分依据（`:41`：覆盖单作业最长运行窗口；1h 无数据，且与 max-execution-time 30m 是 2× 关系但未写明） | 建议写成“≥ max-execution-time + 收尾余量”的派生式，避免两个数各自漂移 | B2-29 |
| N-BE-054 | 超时 | `J/common/sse/SseWriteBudget.java:116` | `DEFAULT_TIMEOUT_MILLIS` | `15_000L` ms（15s） | 两条 SSE 通道写侧预算的**单一事实源**；AI 与任务通道共用 | 硬编码 | 有依据（`:106-115`：64KB 帧、p99 1.15ms、15s 为四个数量级；并说明筛掉停读客户端而非慢客户端） | 建议保持；跨通道共用时若两通道负载差异变大，需评估分池/分额（类注已列） | B2-01 |
| N-BE-055 | 池 | `J/common/sse/SseWriteBudget.java:151` | `DEFAULT_MAX_WRITER_THREADS` | `256` 线程 | 进程级 SSE 写出线程硬上限；达上限提交被拒 ⇒ SATURATED ⇒ 摘除本次订阅者 | 硬编码 | 有依据（`:130-151`：256 = 投递池 8 的 32 倍，按每线程栈内存收敛；跨通道共用并如实登记误伤面） | 建议用真实并发/慢连接分布复核 256；跨通道隔离仍挂账 | 无（旧 B2-02 记的是旧 max=Integer.MAX_VALUE） |
| N-BE-056 | 池 | `J/common/sse/SseWriteBudget.java:209` | 写出池形态 | `core=0`；`max=cap(256)`；`keepAlive=60s`；`SynchronousQueue(容量 0)`；`AbortPolicy` | 弹性写出池：空闲即回收、不排队、饱和快速失败 | 硬编码 | 部分依据（类注 `:156-166`：弹性是刻意的，有界常驻池会把慢连接数变成停投阈值；60s 存活无注释） | 建议保持“不排队”语义；60s 存活可随 JVM/容器调优复核 | B2-02 |
| N-BE-057 | 池 | `J/config/AsyncConfig.java:69` | `setCorePoolSize` | `4` 线程 | 业务 `@Async` 作业执行器核心线程；作业并发下限 | 硬编码 | 部分依据（类注 `:28-40`：只做“足以覆盖一个任务中心点几下一键导出”的定性论证，明示“值不动、补依据”） | 建议补真实并发数据；与 N-BE-058/009 的连接池张力一并裁决 | B2-05 |
| N-BE-058 | 池 | `J/config/AsyncConfig.java:70` | `setMaxPoolSize` | `16` 线程 | 作业执行器最大线程；大于 Hikari 10 连接，最多 6 条等连接 | 硬编码 | 部分依据（同上；类注 `:47-50` 已如实登记“已知张力”） | 建议以“作业并发实测”决定是调低 16 还是调高连接池；两者必须一并复核 | B2-05 |
| N-BE-059 | 池 | `J/config/AsyncConfig.java:71` | `setQueueCapacity` | `200` 个任务 | 作业执行器等待队列；饱和即 `AbortPolicy` 拒绝并冒泡 | 硬编码 | 部分依据（类注 `:28-40` 定性论证；200 无数据） | 建议说明 200 队列对应的“最大积压作业数/最坏等待时长” | B2-05 |
| N-BE-060 | 池 | `J/config/AsyncConfig.java:77` | `setAwaitTerminationSeconds` | `30s` | 关停时等待作业线程收尾上限；与 `waitForTasksToCompleteOnShutdown(true)` 配套 | 硬编码 | 裸数字（`:77` 无注释；类注只说改它等于改停机阻塞时间） | 建议与 `timeout-per-shutdown-phase` 20s/30s 一起核对：作业收尾 30s 可能晚于 Web 优雅停机窗口 | B2-06 |
| N-BE-061 | 退避 | `J/task/service/TaskSseService.java:78` | `REMOTE_SUBSCRIBE_RETRY_NANOS` | `10s` | 任务 SSE 跨实例订阅失败后的一次性延迟重试退避；只对仍有本机 emitter 的任务重试 | 硬编码 | 部分依据（类注 `:69-71,114-121`：说明一次性重试与 publish 双兜底；10s 无论证） | 建议说明 10s 与 Redis 恢复感知（AI 面 `NEGATIVE_TTL_MS` 5s）的关系 | 无 |
| N-BE-062 | 池 | `J/task/service/TaskService.java:202` | `CREATE_LOCKS` | `64` 个分片锁 | 同进程内按 run/key 哈希分片，串行化 `createIdempotent` 的并发创建 | 硬编码 | 裸数字（`:198-211` 只说明按 run/key 分片；64 无注释） | 建议说明 64 与预期并发创建数的关系；哈希冲突只会串行化、不会错，但可能增加等待 | 无 |
| N-BE-063 | 池 | `J/job/service/JobWatchdog.java:140` | `newSingleThreadScheduledExecutor` | `1` 线程 | 作业看门狗扫描线程；刻意不复用 `AsyncConfig` 作业池，避免观察者与被观察者互相饿死 | 硬编码 | 有依据（类注 `:68-72`：明确自建单线程 daemon 调度器及理由；1 由单线程语义决定） | 建议保持；单线程扫描是“扫描慢”时的天然背压，但需确保单轮扫描不因单作业异常停摆（已有 Throwable 兜底） | 无 |
| N-BE-064 | 池 | `J/job/service/JobHeartbeatRegistry.java:393` | `newSingleThreadScheduledExecutor` | `1` 线程 | 进程级共享心跳 ticker；只为在跑 runner 续期共享键 | 硬编码 | 有依据（`:384-408`：单线程 ticker、懒启动、空闲停止；1 由单线程语义决定） | 建议保持；ticker 只做 Redis SET，若 runner 数很大需评估单线程吞吐 | 无 |

### 1.3 分页 / 上限 / 缓冲 / Excel / 文件 / Flyway / 安全（N-BE-065..080）

| 唯一ID | 分类 | 文件:行 | 键或常量/表达式 | 现值+单位 | 作用与消费点 | 配置键/硬编码/框架缺省 | 依据 | 审视建议（不修改） | 旧 NUMSPEC ID |
|---|---|---|---|---|---|---|---|---|---|
| N-BE-065 | 分页 | `J/task/controller/TaskController.java:48-49` | `@RequestParam(defaultValue)` | `page=0`；`size=10` | 历史任务列表默认分页；`GET /api/tasks` | 硬编码（注解内） | 裸数字（无注释；与前端 `stores/task.ts` 的 10 同源但后端未论证） | 建议明确“默认 10 条”的产品口径；与 N-BE-067 的 20 不一致，是否统一需裁决 | B2-31 |
| N-BE-066 | 分页 | `J/job/controller/JobController.java:60-61` | `@RequestParam(defaultValue)` | `page=0`；`size=50` | 作业问题列表默认分页；`GET /api/jobs/{jobId}/issues` | 硬编码（注解内） | 裸数字（无注释；与前端 `loadIssues` 的 50 同源） | 建议与 N-BE-067 一并裁决是否统一为 20；50 无独立依据 | B2-31 |
| N-BE-067 | 分页 | `J/data/controller/DataController.java:76-77` | `@RequestParam(defaultValue)` | `page=0`；`size=20` | 数据列表默认分页；`GET /api/data/{defCode}` | 硬编码（注解内） | 有依据（`:44-49`：引 GitHub 默认 30/上限 100、Stripe 默认 10/1–100，2026-10-10 裁决⑥统一为 20） | 建议保持 20，并把“20/50/100 选项 + 后端上限 100”的完整口径落到实现（上限尚未强制，见 N-BE-068） | B2-31（旧表记 50，已按现态改为 20） |
| N-BE-068 | 分页 | `J/data/controller/DataController.java:51-55,82-84` | `size` 无 clamp、无校验 | **无上限**（`size` 直通 `PageRequest.of`；推荐上限 100 未实施） | 数据列表可传任意大 `size`（如 999999），不被拒也不截断；可能导致全表分页/大结果集 | 硬编码（缺失） | 裸数字（`:51-55` 自认“无 clamp、无校验”，候选 `Math.min(size,100)` 或 `@Max(100)` 未实施） | 建议把“后端硬上限 100”从推荐值落成实现（显式 400 或静默夹取二选一），并同步前端页大小选项 | 无（旧 B2-31 行内已提示未强制） |
| N-BE-069 | 上限 | `J/job/entity/ValidationIssue.java:31` | `@Column(length = 1024)` | `1024` 字符 | `validation_issues.message` 列宽；写入前必须保护，否则 INSERT 失败 | 硬编码（列宽） | 部分依据（与 N-BE-070 同值；两处独立字面量，无同源声明） | 建议与 N-BE-070 建立单一常量或迁移期校验，防止改一处导致“截断长度 ≠ 列宽” | B2-28 |
| N-BE-070 | 上限 | `J/job/service/ExportJobRunner.java:75` | `ISSUE_MESSAGE_MAX` | `1024` 字符 | 异常摘要截断预算：类名 + 摘要 ≤ 1024，超出加“已按列宽截断”标记 | 硬编码 | 部分依据（`:64-78,356-372`：说明列宽保护与截断标记；1024 来自列宽而非独立论证） | 建议把 N-BE-069 的列宽作为唯一事实源（或启动期对账），避免两个 1024 漂移 | B2-27 |
| N-BE-071 | 缓冲 | `J/file/FileStorageService.java:56` | `DIGEST_BUFFER_BYTES` | `8192` 字节（8KB） | 文件 sha256 流式摘要的读缓冲；预检查/导入指纹比对，避免整文件进内存 | 硬编码 | 裸数字（`:55` 只说明“流式读、不整文件进内存”；8KB 为常见 IO 缓冲经验值，仓内无引证） | 建议补一句 8KB 与顺序读吞吐/内存的取舍；若共享卷延迟高可评估调大 | B2-24 |
| N-BE-072 | 上限 | `J/file/FileStorageService.java:59` | `SAFE_EXTENSION` 正则 `{1,10}` | `1–10` 个 ASCII 字母数字 | 新存储键扩展名形状；挡 `./../` 等路径拼接 | 硬编码 | 部分依据（`:58`：说明形状与防拼接目的；10 字符上限无独立论证） | 建议保持；若未来支持更长扩展名需同步改正则与注释 | 无 |
| N-BE-073 | 缓冲 | `J/excel/ExcelWriter.java:31` | `new SXSSFWorkbook(100)` | `100` 行滑动窗口 | 大导出流式写的内存行窗口；超过窗口的行刷到临时文件 | 硬编码 | 裸数字（`:31` 无注释；100 无取值论证） | 建议用“最大导出行数 × 单行字节”评估 100 是否足够；窗口过小会增加临时文件 IO | B2-25 |
| N-BE-074 | 上限 | `J/excel/ExcelTemplateBuilder.java:107` | `new CellRangeAddressList(1, 1000, c, c)` | POI 行索引 `1–1000`（表头下的第 1–1000 个数据行） | 模板 ENUM 下拉校验覆盖行范围；超过 1000 个数据行的导入不再有下拉约束 | 硬编码 | 裸数字（`:107` 无注释；1000 与产品最大导入行数无对照） | 建议与真实导入行数上限对齐，或明确“下拉仅示例、校验在服务端” | B2-26 |
| N-BE-075 | 上限 | `J/job/service/JobService.java:427,429` | `Math.min(12, sha.length())` / `substring(0,12)` | `12` 字符 | 预检查指纹冲突文案里的 sha256 前缀；仅可读性，不参与比对 | 硬编码 | 裸数字（只用于日志/错误文案截断，无注释） | 建议保持；若前端按完整 sha 展示，需另给字段而非截断 | 无 |
| N-BE-076 | 其他 | `J/config/FlywayStartupLock.java:48` | `WAIT_TIMEOUT` | `60s` | Flyway 启动单飞等待上限（H2 行锁 / PG advisory lock）；覆盖一轮冷启动迁移 | 硬编码 | 部分依据（`:47`：说明覆盖一轮冷启动迁移；60s 无实测） | 建议与迁移总时长、实例启动超时一起复核；慢库上 60s 可能不够 | 无 |
| N-BE-077 | 超时 | `J/config/FlywayStartupLock.java:207` | `connection.isValid(2)` | `2s` | H2 锁连接在迁移后的有效性复核；失效即 fail-closed | 硬编码 | 裸数字（`2` 无注释） | 建议说明 2s 是 JDBC 驱动校验超时，不是业务等待 | 无 |
| N-BE-078 | 其他 | `J/config/ClientIpResolver.java:44` | `MAX_CHAIN_LENGTH` | `16` 跳 | `X-Forwarded-For` 最大解析跳数；超过即整条头作废，回落 `RemoteAddr` | 硬编码 | 部分依据（`:43`：异常长链是攻击面，不是拓扑；16 无独立论证） | 建议按实际代理层数（通常 1–3）给一个更紧的上限，减少伪造面 | 无 |
| N-BE-079 | 其他 | `J/config/WebConfig.java:59` | `config.setMaxAge(3600L)` | `3600s`（1h） | CORS 预检结果缓存时长；`/api/**` 跨源请求 | 硬编码 | 裸数字（无注释；与 `spring.messages.cache-duration` 同值但语义无关） | 建议说明 1h 与开发期 `localhost:*` 允许源的组合是否会缓存过期策略 | 无 |
| N-BE-080 | 上限 | `J/seed/SeedLock.java:76` | holder 截断 `length() > 128` | `128` 字符 | 种子单飞锁 `app_lock.holder` 的写入值截断；只作观测，不参与判定 | 硬编码（列宽保护） | 部分依据（`:71-76`：说明 holder 只进 `app_lock.holder` 与日志；128 来自列宽） | 建议与 `V7__app_lock` 的列宽建立同源说明 | 无 |

### 1.4 附录 A：协议 / 固定值 / 表结构 / 跨域引用（N-BE-081..091，不计入主表统计）

> 这些数字是协议固定值、表结构固定宽度、标识格式或“消费方在非 AI 面、事实源在 AI 面”的跨域引用。单列备查，不作为可调运行规格。

| 唯一ID | 分类 | 文件:行 | 键或常量/表达式 | 现值+单位 | 作用与消费点 | 配置键/硬编码/框架缺省 | 依据 | 审视建议（不修改） | 旧 NUMSPEC ID |
|---|---|---|---|---|---|---|---|---|---|
| N-BE-081 | 其他 | `R/application.yml:80` | `spring.data.redis.port` | `6379`（端口） | Redis 默认端口；standalone 连接 | 配置键 | 裸数字（默认端口，无注释） | 建议生产用 env/secret 覆盖，不依赖默认端口 | 无 |
| N-BE-082 | 其他 | `R/application.yml:37` | H2 URL `DB_CLOSE_DELAY=-1` | `-1`（标志） | H2 文件库在 JVM 存活期间不关闭；`-1` 是 H2 的“永不关闭”语义 | 配置键（URL 内联） | 部分依据（H2 协议语义；仓内无注释） | 建议在数据源注释中说明 `-1` 与停机/文件锁的关系 | 无 |
| N-BE-083 | 其他 | `J/config/FlywayStartupLock.java:51` | `PG_LOCK_KEY` | `20261025L` | PG advisory lock 的应用级固定键；本应用全局唯一 | 硬编码 | 裸数字（`:50` 只说“应用级键，本应用全局固定”；日期形常数无进一步说明） | 建议保持稳定；改它等于换锁命名空间，滚动升级期间会双锁并存 | 无 |
| N-BE-084 | 其他 | `J/config/WebConfig.java:38,73,88` | 过滤器顺序偏移 `HIGHEST_PRECEDENCE + n` | `+5` / `+10` / `+20` | CORS → 安全响应头 → 租户身份过滤器的相对顺序；顺序错会导致拒绝响应缺 CORS 头 | 硬编码 | 部分依据（`:20-28`：说明 CORS 必须早于身份过滤器；5/10/20 为相对次序，非等待/容量值） | 建议只在新增过滤器时按“CORS 最先、身份最后”的规则插入中间值 | 无 |
| N-BE-085 | 其他 | `J/common/GlobalExceptionHandler.java:311` | SQLState / vendor code | `23506` / `1452` | 外键冲突映射 409 的数据库协议码（PG/H2 与 MySQL 兼容码） | 硬编码（协议） | 有依据（数据库协议标准错误码；非业务可调数字） | 建议保持；若换库需补对应 SQLState | 无 |
| N-BE-086 | 上限 | `J/job/entity/ValidationIssue.java:21,24,27,31` 等 | 表结构列宽（分组行，含多值） | `def_code=64`；`row_key=256`；`field_code=64`；`severity=16`；`message=1024`；`Job/JobItem status=32`；`Task title=256`；`current_step=64`；`TaskFile storage_path=512`；`file_name=256`；`ConfigDataRow row_key=256`；`scope_type=16`；`scope_key=64`；`ConfigDefinition code=64`；`name=128`；`level=16`；`description=512`；`ConfigField code=64`；`label=128`；`type=16`；`ref_*=64`；`ConfigStagingRow row_key=256`；`scope_*=16/64`；`Region/Project code=32`；`name=64`；`Flyway 锁表 lock_name=64`；`holder=128` | 数据库列宽；超长即 INSERT 失败或被业务层截断；`message=1024` 与 N-BE-070 耦合 | 硬编码（schema） | 部分依据（`V1__schema.sql`/实体注解；列宽本身无容量论证） | 建议对 `row_key=256`、`storage_path=512` 做最大业务键/路径实测；对 `message=1024` 建立常量对账（N-BE-069/070） | B2-28（仅 1024 项） |
| N-BE-087 | 其他 | `J/job/service/JobHeartbeatRegistry.java:101`；`J/task/service/RedisTaskEventBus.java:111` | `UUID...substring(0, 6)` | `6` 字符 | 实例标识后缀（`runner-...` / `taskbus-...`）；同 JVM 多实例/多总线可区分 | 硬编码 | 裸数字（可读性后缀；6 无碰撞概率论证） | 建议说明 6 字符在同一主机多进程下的碰撞概率是否可接受 | 无 |
| N-BE-088 | 池 | `J/ai/exec/AiExecutorConfig.java:79`（事实源在 AI 面，消费方在非 AI 面） | `Executors.newScheduledThreadPool(2)` | `2` 线程 | `aiStreamWatchdog` 调度器；非 AI 的 `TaskSseService` 复用它做跨实例订阅延迟重试 | 硬编码（AI 面） | 部分依据（AI 类注 `:51-60` 说“单条平台守护线程”，但实际 `newScheduledThreadPool(2)`；2 无论证） | 建议由 AI 面 owner 复核“注释说 1、代码是 2”的偏差；任务 SSE 的重试也挂在这 2 条线程上 | B2-04 |
| N-BE-089 | 退避 | `J/ai/config/RedisAvailability.java:53`（事实源在 AI 面，消费方在非 AI 面） | `NEGATIVE_TTL_MS` | `5000` ms（5s） | Redis 不可用负缓存；非 AI 的 `RedisTaskEventBus` 用它做已知 DOWN 的快速降级窗口 | 硬编码（AI 面） | 裸数字（AI 面注释只说“每本间隔重探一次”；5s 无论证） | 建议在任务 SSE 的文档中明示“依赖 AI 面 5s 负缓存”，避免两侧调参互不知情 | B2-15 |
| N-BE-090 | 其他 | `J/excel/ExcelWriter.java:44`；`J/excel/ExcelTemplateBuilder.java:40,46` | 列宽/批注锚点 | 列宽 `20 × 256`；批注锚点 `c+2` 列、`4` 行 | xlsx 表头列宽约 20 字符；字段说明批注框跨 2 列 4 行 | 硬编码 | 裸数字（纯布局；POI 列宽单位 1/256 字符） | 建议归入“布局/样式数字”，不参与运行时容量裁决 | 无（旧 B2-25/26 只登记窗口/校验行） |
| N-BE-091 | 其他 | `R/application.yml:71`；`J/config/JacksonConfig.java:23` | `spring.jackson.time-zone` / `TimeZone` | `GMT+8` / `Asia/Shanghai` | JSON 日期时区；与 `date-format` 一起决定 API 时间字段呈现 | 配置键 + 硬编码（双写） | 部分依据（业务时区固定；两处双写但无一致性守卫） | 建议只保留一处事实源（yml 或 JacksonConfig），避免夏令时/时区规则变化时分叉 | 无 |

### 1.5 附录 B：业务库 DDL 数值约束（N-BE-092..106，不计入主表统计）

> 扫描 `R/db/migration/**` 与 `R/db/postgresql/**` 的全部 `CREATE TABLE` / `ALTER TABLE`，只登记**非 `ai_*` 表**的数值 DEFAULT、VARCHAR 列宽、INT/BIGINT 列、NUMERIC/DECIMAL 精度标度与 CHECK 约束。H2 与 PG 两份 V1 的数值**逐项相同**（仅 `CLOB`→`TEXT` 方言差异与行号不同），下表同时给两目录行号。
>
> **边界**：下表 14 张**现役表**才是当前运行约束；`ai_chat_message` 已被 V10 删除，只作迁移历史，其 `CHECK (seq > 0)` 等不生效（见本节末尾）；`ai_*` 现役表按任务边界排除。

| 唯一ID | 分类 | 文件:行 | 键或常量/表达式 | 现值+单位 | 作用与消费点 | 配置键/硬编码/框架缺省 | 依据 | 审视建议（不修改） | 旧 NUMSPEC ID |
|---|---|---|---|---|---|---|---|---|---|
| N-BE-092 | 上限 | `R/db/migration/V1__schema.sql:5-10`；`R/db/postgresql/V1__schema.sql:18-23` | `regions`：`code` / `name` / `id` | `VARCHAR(32)` / `VARCHAR(64)`（字符）；`id BIGINT IDENTITY`；无数值 DEFAULT | 地区主数据；`RegionRepository` 取数；任务 REGION 级 `scopeKey` 取 `regions.code` | 硬编码（DDL 迁移，现役表） | 部分依据（与 `Region` 实体 `@Column(length=...)` 对齐；宽度本身无容量论证） | 建议对 DDL 列宽与实体注解做测试期对账；确认 `code 32` 覆盖现有与未来地区编码 | 无 |
| N-BE-093 | 上限 | `R/db/migration/V1__schema.sql:13-19`；`R/db/postgresql/V1__schema.sql:26-32` | `projects`：`code` / `name` / `region_code` / `id` | `VARCHAR(32)` / `VARCHAR(64)` / `VARCHAR(32)`（字符）；`id BIGINT IDENTITY`；无数值 DEFAULT | 项目主数据；`ProjectRepository`；任务 PROJECT 级 `scopeKey` 取 `projects.code` | 硬编码（DDL 迁移，现役表） | 部分依据（与 `Project` 实体 `@Column(length=...)` 对齐；宽度无容量论证） | 建议同 N-BE-092；`region_code` 32 与 `regions.code` 32 必须保持同宽 | 无 |
| N-BE-094 | 上限 | `R/db/migration/V1__schema.sql:22-31`；`R/db/postgresql/V1__schema.sql:35-44` | `config_definitions`：`code` / `name` / `level` / `description`；`sort_order INT NOT NULL DEFAULT 0` | `64/128/16/512` 字符；`sort_order=0`（整数） | 配置定义目录；`DefinitionService.findAll` 按 `sortOrder, code` 稳定排序；AI `list_config_defs` 同源 | 硬编码（DDL 迁移，现役表） | 部分依据（宽度与 `ConfigDefinition` 实体注解对齐；`sort_order` 初值语义自明，无容量论证） | 建议保持 `sort_order=0`；对 `description 512` 是否覆盖长说明做业务确认；DDL 与实体列宽对账 | 无 |
| N-BE-095 | 上限 | `R/db/migration/V1__schema.sql:34-47`；`R/db/postgresql/V1__schema.sql:47-60` | `config_fields`：`def_code` / `code` / `label` / `field_type` / `ref_def_code` / `ref_field_code`；`sort_order INT NOT NULL DEFAULT 0` | `64/64/128/16/64/64` 字符；`sort_order=0`（整数） | 字段结构；`DefinitionService.validateFields` 编码校验；Excel 表头/模板列映射 | 硬编码（DDL 迁移，现役表） | 部分依据（与 `ConfigField` 实体注解对齐；`sort_order` 初值自明） | 建议 `code 64` 与 `DefinitionService` 的字段编码正则一致；对账实体列宽 | 无 |
| N-BE-096 | 上限 | `R/db/migration/V1__schema.sql:50-55`；`R/db/postgresql/V1__schema.sql:63-68` | `config_dependencies`：`from_def_code` / `to_def_code` / `id` | `VARCHAR(64)` / `VARCHAR(64)`（字符）；`id BIGINT IDENTITY`；无数值 DEFAULT | 定义依赖图；`DependencyResolver.sort` 拓扑排序 | 硬编码（DDL 迁移，现役表） | 部分依据（宽度与实体/依赖解析无容量论证；仅 64 对齐） | 建议确认依赖边数无上限是否可接受（表无行数限制，仅靠定义数约束） | 无 |
| N-BE-097 | 上限 | `R/db/migration/V1__schema.sql:58-70`；`R/db/postgresql/V1__schema.sql:71-83` | `config_data_rows`：`def_code` / `scope_type` / `scope_key` / `row_key`；`version BIGINT NOT NULL DEFAULT 1` | `64/16/64/256` 字符；`version=1`（整数）；`scope_type` 默认 `'GLOBAL'`（非数值） | 已发布配置行；`ConfigDataService.upsertPublished` 用 `OPTIMISTIC_FORCE_INCREMENT`；`baseVersion` 冲突检测依赖 `version` | 硬编码（DDL 迁移，现役表） | 部分依据（`version=1` 与 JPA 乐观锁初值一致；`row_key 256` 是业务复合键硬上限，无实测） | 建议实测最长 `row_key`（多主键拼接）是否逼近 256；`version` 保持从 1 起 | 无 |
| N-BE-098 | 上限 | `R/db/migration/V1__schema.sql:73-85`；`R/db/postgresql/V1__schema.sql:86-98`；`R/db/migration/V2__staging_base_version.sql:4`；`R/db/postgresql/V2__staging_base_version.sql:4` | `config_staging_rows`：`def_code` / `op_type` / `row_key` / `scope_type` / `scope_key` / `status`；`base_version BIGINT`（V2 新增） | `64/16/256/16/64/16` 字符；无数值 DEFAULT；`base_version BIGINT` 可空、无默认 | 导入暂存行；发布冲突检测读 `base_version`；`status` 驱动发布/失败态 | 硬编码（DDL 迁移，现役表） | 部分依据（列宽与 `ConfigStagingRow` 实体对齐；`base_version` 可空语义由发布流程解释） | 建议明确 `base_version=null` 的两义（新增行/历史行）；`row_key 256` 同 N-BE-097 实测 | 无 |
| N-BE-099 | 上限 | `R/db/migration/V1__schema.sql:88-98`；`R/db/postgresql/V1__schema.sql:101-111` | `tasks`：`type` / `title` / `current_step` / `status`；`version BIGINT NOT NULL DEFAULT 1` | `32/256/64/32` 字符；`version=1`（整数） | 任务台账；`TaskService` 状态机；`@Version` 乐观锁；前端标题输入上限更严（旧 F2-04 为 60） | 硬编码（DDL 迁移，现役表） | 部分依据（`version=1` 与 JPA 初值一致；`title 256` 与前端 60 不一致但后端更宽） | 建议统一 `title` 前后端上限口径（当前后端 256、前端 60，前端更严）；`current_step 64` 足够 | 无 |
| N-BE-100 | 上限 | `R/db/migration/V1__schema.sql:101-109`；`R/db/postgresql/V1__schema.sql:114-122` | `task_items`：`def_code` / `status`；`sort_order INT NOT NULL DEFAULT 0` | `64/32` 字符；`sort_order=0`（整数） | 任务选中配置项；`findByTaskIdOrderBySortOrder` 排序；条件 JSON 在 CLOB 无长度约束 | 硬编码（DDL 迁移，现役表） | 部分依据（`sort_order` 初值自明；`status 32` 容纳长枚举） | 建议保持 `sort_order=0`；`condition_json` 无长度上限，已在观察项登记 | 无 |
| N-BE-101 | 上限 | `R/db/migration/V1__schema.sql:112-125`；`R/db/postgresql/V1__schema.sql:125-138` | `task_files`：`def_code` / `file_type` / `storage_path` / `original_path` / `file_name`；`version BIGINT NOT NULL DEFAULT 1`；`row_count INT` | `64/32/512/512/256` 字符；`version=1`（整数）；`row_count` INT 可空、无默认 | 任务文件引用；`FileController` 上传/下载；导出结果行数；`@Version` 锁 | 硬编码（DDL 迁移，现役表） | 部分依据（宽度与 `TaskFile` 实体对齐；`version=1` 与 JPA 初值一致；`row_count` 可空语义明确） | 建议确认 `file_name 256` 覆盖中文长文件名；`storage_path 512` 对相对键足够；`row_count` INT 上限约 21 亿行 | 无 |
| N-BE-102 | 其他 | `R/db/migration/V1__schema.sql:128-141`；`R/db/postgresql/V1__schema.sql:141-154` | `jobs`：`progress` / `total` / `error_count` / `warning_count`（均 `INT NOT NULL DEFAULT 0`） | 四个 `0`（整数） | 作业进度与计数；`JobProgressService` 条件 UPDATE；预检查守卫按 `error_count > 0` 判失败；看门狗读 `progress/total` | 硬编码（DDL 迁移，现役表） | 部分依据（初始态 0 语义自明；`error_count` 被业务判据消费） | 建议为 `progress/total/error_count/warning_count` 加 `CHECK (>= 0)` 或在应用层断言；当前 DDL 允许负数 | 无 |
| N-BE-103 | 其他 | `R/db/migration/V1__schema.sql:144-152`；`R/db/postgresql/V1__schema.sql:157-165` | `job_items`：`processed` / `total`（均 `INT NOT NULL DEFAULT 0`） | 两个 `0`（整数） | 配置项级进度；`JobProgressService.startItem/updateItem/finishItem`；详情抽屉逐项进度 | 硬编码（DDL 迁移，现役表） | 部分依据（初始态 0 语义自明；无 CHECK） | 建议同 N-BE-102 加 `CHECK (>= 0)` 或应用层不变量 | 无 |
| N-BE-104 | 上限 | `R/db/migration/V1__schema.sql:155-165`；`R/db/postgresql/V1__schema.sql:168-177` | `validation_issues`：`def_code` / `row_key` / `field_code` / `severity` / `message`；`row_index INT` | `64/256/64/16/1024` 字符；`row_index` INT 可空、无默认 | 校验问题明细；预检查写 issue；`message 1024` 与 `ExportJobRunner.ISSUE_MESSAGE_MAX` 耦合；`row_index` 为 Excel 行号 | 硬编码（DDL 迁移，现役表） | 部分依据（`row_key 256`/`message 1024` 与实体及常量对齐；`row_index` 无 CHECK） | 建议把 `message` 1024 与 N-BE-070 做单一事实源；明确 `row_index` 是 1-based（含表头偏移） | B2-28（1024 列宽） |
| N-BE-105 | 上限 | `R/db/migration/V7__app_lock.sql:20-24`；`R/db/postgresql/V7__app_lock.sql:20-24` | `app_lock`：`lock_name` / `holder` | `VARCHAR(64)` / `VARCHAR(128)`（字符）；无默认；仅预置 `lock_name='seed'` | 种子单飞行锁；`SeedLock.lock` 以 `FOR UPDATE` 抢 `seed` 行；`holder` 只作观测并被截断到 128 | 硬编码（DDL 迁移，现役表） | 部分依据（`holder 128` 与 `SeedLock` 截断一致；`lock_name 64` 语义自明） | 建议保持两份 V7 同字节；新增锁名时确认 ≤64 字符 | 无 |
| N-BE-106 | 其他 | `R/db/migration/V1__schema.sql` 全表；`R/db/postgresql/V1__schema.sql` 全表；`R/db/migration/V2__staging_base_version.sql:4`；`R/db/postgresql/V2__staging_base_version.sql:4`；`R/db/migration/V7__app_lock.sql:20-24`；`R/db/postgresql/V7__app_lock.sql:20-24` | 现役非 AI 表的数值类型 / 精度 / CHECK 盘点 | `INT` 11 列；`BIGINT` 23 列（`V1` 22 列 = 13 个 `id` + 4 个 `task_id` + 2 个 `job_id` + 3 个 `version`；`V2` 再加 1 个 `base_version`）；`VARCHAR` 49 列（`V1` 47 + `app_lock` 2）；`CHAR` **0**；`NUMERIC/DECIMAL` **0**；`CHECK` **0** | 数值存储边界：整数计数/进度/版本；业务金额、汇率、税率全部在 `data_json` 的 CLOB/TEXT 内，DDL 不约束精度；无 CHECK 意味着进度/计数可被写成负数 | 硬编码（DDL 迁移，现役表） | 有依据（逐表实读全部 `CREATE/ALTER`；`grep` 无 `NUMERIC/DECIMAL`/`CHECK`；结论可复核） | 建议评估：① 为进度/计数加 `CHECK (>=0)`；② 金额/汇率/税率在应用层用 `BigDecimal` + 精度/标度校验（DDL 层无约束）；③ 建立 DDL 列宽与实体 `@Column(length)` 的对账 | 无 |

> **现役表与历史表边界**：
> - **现役表（本附录登记对象）**：`regions`、`projects`、`config_definitions`、`config_fields`、`config_dependencies`、`config_data_rows`、`config_staging_rows`、`tasks`、`task_items`、`task_files`、`jobs`、`job_items`、`validation_issues`、`app_lock`（共 14 张）。
> - **已删除表（仅迁移历史，不构成本项目前运行约束）**：`ai_chat_message`（V8 建表、V9 加幂等列、V10 `DROP TABLE`）。它含 `seq BIGINT NOT NULL` 与 `CHECK (seq > 0)` 等数值约束，但表已删除，且属 `ai_*` 表，按任务边界不计入本附录。
> - **现役但属 AI 面（按任务边界排除）**：`ai_feedback`（V3 建表、V11 加 `tenant_id/user_id BIGINT NOT NULL DEFAULT 0` 与 `rating INT`）、`ai_provider` / `ai_model` / `ai_setting` / `ai_config_audit`（V4，含 `sort_order INT DEFAULT 0` 等）。这些表的 DDL 数值由 AI 分册或后续 AI DDL 清单登记，本文不重复。
> - **非 AI 业务表无已删除表**：V1/V2/V6/V7 没有 `DROP TABLE`；`DROP` 只出现在 `V10__drop_ai_chat_message.sql`。

---

## 2. 统计

> 口径：主表统计 §1.1–1.3；附录 A（协议/固定值）与附录 B（业务库 DDL）单列。DDL 每行按“一张现役表”分组，组内逐列列出宽度/默认值/类型。

| 指标 | 值 |
|---|---|
| **主表条数** | **80** |
| 附录 A 条数 | 11 |
| 附录 B（业务库 DDL）条数 | 15 |
| 附录条数合计 | 26 |
| **合计条数** | **106** |
| 主表折算独立数值 | ≈85（N-BE-026 含 3 值、N-BE-056 含 4 值，其余一行一值或一表达式） |
| 配置键 | 33 |
| 硬编码 | 37 |
| 框架缺省 | 10 |
| 有依据 | 22 |
| 部分依据 | 31 |
| 裸数字 | 27 |
| 含多值的分组行 | N-BE-026（3 值）、N-BE-056（4 值）、N-BE-086（附录 A，约 26 个列宽）、N-BE-092..105（附录 B，按表分组：49 个 VARCHAR 列宽 + 12 个数值 DEFAULT + INT/BIGINT 类型） |

**分布结论**：

- 主表 80 条中，**可配置 33 条（41.3%）**，**硬编码 37 条（46.3%）**，**框架缺省 10 条（12.5%）**。后端非 AI 面的“硬编码率”显著低于旧总账的全仓 73.8%，因为作业/任务/健康/文件域把主要等待与容量做成了配置键；但**分页、Excel、文件缓冲、锁等待、调度线程数**仍是硬编码集中区。
- 依据分布：**裸数字 27 条（33.8%）**、部分依据 31 条（38.8%）、有依据 22 条（27.5%）。裸数字集中在：Hikari/Tomcat 框架缺省（N-BE-010..018 中的多数）、分页默认值（065/066）、Excel 窗口/校验行（073/074）、CORS/缓存（079）、Flyway/连接校验（076/077）。
- 旧总账中的“后端配置键 31 条全可调、硬编码 32 条中 96.9% 不可调”结论，在当前现态下**已被作业看门狗四键、就绪探测四键、SSE 写预算、Hikari/PG profile 等新增项改变**；不能直接沿用旧统计。
- **附录 B（业务库 DDL）15 条**：14 张现役表逐表登记（均为“部分依据”：列宽/默认值与实体 `@Column`/字段初值对齐，但无容量论证），另 1 条全库数值类型/精度/CHECK 盘点（有依据）。结论：现役非 AI 表只有 `INT`/`BIGINT`，**`NUMERIC/DECIMAL` 为 0、`CHECK` 为 0**；金额/汇率/税率等业务数值都在 `data_json` 的 CLOB/TEXT 内，DDL 不约束精度；进度/计数列可被写成负数。
- **DDL 双份事实源**：H2 `V1__schema.sql` 与 PG `V1__schema.sql` 的数值逐项相同，但行号不同；实体 `@Column(length=...)`、`ExportJobRunner.ISSUE_MESSAGE_MAX=1024`、`SeedLock` holder 128 又各存一份，改一处会漂移。

---

## 3. 重点待裁项（只登记，不建议本次改值）

| # | 条目 | 现状 | 为什么需要裁决 | 建议的裁决问题 |
|---|---|---|---|---|
| 1 | **容量链：Hikari 10 / Async 16 / Tomcat 200**（N-BE-009/014/057-059） | 默认 profile Hikari 上限 10（框架缺省）、作业池 max 16、Tomcat 200；PG/h2tcp 显式 10 | 16 条作业线程中最多 10 条能同时持有连接，其余在 Hikari `connectionTimeout` 30s 内排队；旧 R-68 已登记，但至今没有“作业并发实测”数据 | 是调低作业池到 ≤10，还是把连接池调高？两者必须与 DB `max_connections` 一起定；Tomcat 200 是否也需要显式化？ |
| 2 | **作业看门狗四键 + 三个时间窗**（N-BE-044..053） | 心跳 30s × 判死倍数 3 = 90s；扫描 10s；绝对上限 30m；取消 TTL 1h；runner-active TTL 65m | 这是旧总账之后新增的一整套运行数字，目前只有“业界下沿”依据；90s 判死窗与长事务/大 sheet 的关系、65m 锚点与删除闸门的延迟、1h 取消标志的保留成本都未实测 | 90s 判死窗在真实最大分片/长事务下是否安全？`CANCEL_TTL` 是否应写成 `max-execution-time + 收尾余量` 的派生式？ |
| 3 | **分页默认值与缺失上限**（N-BE-065..068） | 任务 10、问题 50、数据 20；数据端点对 `size` 无 clamp、无校验 | 同一产品内三套默认页大小；数据端点可传 `size=999999` 拉全表，既是性能风险也是产品边界缺失 | 是否统一默认 20？是否强制后端上限 100（静默夹取还是 400）？三处默认值是否要同源常量？ |
| 4 | **上传 5MB / 请求 6MB**（N-BE-004/005） | 单文件 5MB、整包 6MB，与 AI 附件上限同口径，留 1MB 余量 | 这是产品级“多大 xlsx 能传上来”的硬边界；zip 批量上传时 6MB 是整包上限，可能比用户预期小；与 Excel 大导入/导出能力不匹配 | 5MB 是否覆盖真实配置数据文件？zip 批量是否需要单独上限？是否要把“单文件/整包”两个口径写进前端提示？ |
| 5 | **SSE 写预算 15s + 写出池 256**（N-BE-040/054-056） | 15s 单帧写预算；进程级 256 线程硬上限；core 0、60s 存活、SynchronousQueue、AbortPolicy；AI 与任务通道共用 | 256 的依据是“投递池 8 的 32 倍”和线程栈内存，不是真实慢连接分布；池满时可能摘除健康连接或另一通道的订阅者（类注已如实登记） | 是否需要按通道分池/分额？256 是否应以压测的“并发慢连接数”定？15s 与容器 120s 写超时的 8 倍差是否保留？ |
| 6 | **问题消息 1024 双份事实源**（N-BE-069/070） | `ValidationIssue.message` 列宽 1024；`ExportJobRunner.ISSUE_MESSAGE_MAX` 也是 1024，两处独立字面量 | 改一处会静默截断（常量变小）或 INSERT 失败（列宽变小）；旧 B2-27/28 已提示“疑同源但未声明”，至今未合并 | 是否把列宽作为唯一事实源（启动期校验或迁移常量）？1024 是否覆盖最坏异常消息？ |
| 7 | **就绪探测 2s 共享时限 vs DB 2s 查询 / PG 5s 取连接**（N-BE-020/033/037） | 所有维度共享 2s；DB 探针自身查询超时 2s；PG 池满时取连接可等 5s | 共享 2s 内如果 DB 探针先花 5s 等连接，整个端点会按 2s 记 DOWN；4 线程恰好等于 4 个维度，新增维度会排队 | 是否把 DB 探针的取连接等待压到 <2s，或把共享时限调大？新增探针维度时线程数是否自动扩展？ |
| 8 | **Excel 1000 行下拉校验 + SXSSF 100 行窗口**（N-BE-073/074） | 模板下拉只覆盖 1–1000 行；导出流式窗口 100 行 | 超过 1000 行的导入没有下拉约束，但仍可导入；SXSSF 100 行窗口没有按最大导出量论证 | 1000 是否应等于产品最大导入行数？SXSSF 窗口是否应按导出规模/内存预算调整？ |
| 9 | **Flyway / H2 / PG 三个 60s 等待**（N-BE-022/028/076） | Flyway 启动锁 60s；PG `lock_timeout` 60s；H2 `LOCK_TIMEOUT` 60s | 三个 60s 分别约束“迁移单飞、会话锁等待、H2 行锁等待”，同值不同机制；没有冷启动/慢库实测 | 是否统一写成“一轮冷启动迁移的最坏时长 + 余量”？慢库/大迁移下 60s 是否会导致启动失败？ |
| 10 | **默认 profile 连接池“隐式 10” vs profile 显式 10**（N-BE-009/019/029） | 默认 yml 只写注释不写键；PG/h2tcp 显式 `maximum-pool-size: 10` | 同一容量三种表达：框架缺省、显式 10、显式 10；运维在默认 profile 的配置面看不到该键，排障时容易误判 | 是否在默认 yml 显式写出 10（或至少启动日志回显）？三处是否要同源注释？ |
| 11 | **调度线程数：看门狗 1 / 心跳 ticker 1 / AI 调度器 2**（N-BE-063/064/088） | 看门狗与心跳各 1 条 daemon；`aiStreamWatchdog` 实际 2 条，任务 SSE 重试也挂在其上 | AI 类注写“单条平台守护线程”，代码却是 `newScheduledThreadPool(2)`；任务 SSE 的 10s 重试与 AI 流式看门狗共享这 2 条线程，负载耦合 | 是否修正注释或线程数？任务 SSE 重试是否应独立调度器？ |
| 12 | **CORS 缓存 1h 与消息缓存 1h 同值不同义**（N-BE-003/079） | `spring.messages.cache-duration=3600s`；CORS `maxAge=3600s` | 同值纯属巧合，但都影响“改配置后多久生效”；排障时容易被误读为同源 | 是否在注释中分别说明“i18n 缓存”与“预检缓存”的独立语义？ |
| 13 | **ZIP 批量上传无解压上限**（见 §4.4） | `FileController.readZipEntries` 对每个 entry `readAllBytes()`，只受 6MB 压缩包限制；没有单条目/总解压大小、条目数上限 | 6MB 的 zip 可解压出远大于 6MB 的内容（zip bomb），且所有条目一次性进堆内存；这是“文件大小/缓冲”面上唯一完全缺失的数字边界 | 是否加“单条目解压上限 + 总解压上限 + 条目数上限”？阈值应与 JVM 堆/上传并发一起定 |
| 14 | **业务库 DDL 无 CHECK、无 NUMERIC/DECIMAL**（N-BE-106） | 现役非 AI 表只有 `INT`/`BIGINT`；`jobs.progress/total/error_count/warning_count`、`job_items.processed/total` 仅 `NOT NULL DEFAULT 0`，无 `CHECK (>=0)`；金额/汇率/税率在 `data_json` CLOB/TEXT | 负进度/负计数在 DDL 层合法；金额精度完全由应用/JSON 字符串决定，数据库无法兜底；这是“数值约束”面的系统性缺口，不只是某个数 | 是否为进度/计数加 `CHECK (>=0)`？金额/汇率/税率是否要在应用层统一 `BigDecimal` 精度/标度校验？是否接受“JSON 内数值无 DDL 约束”？ |
| 15 | **DDL 列宽与实体/常量多份事实源**（N-BE-092..105、N-BE-069/070、N-BE-080） | H2/PG 两份 V1 逐项同值；实体 `@Column(length)`、`ExportJobRunner.ISSUE_MESSAGE_MAX=1024`、`SeedLock` holder 128 各存一份 | 改一处会静默漂移：列宽变小 → INSERT 失败；常量变小 → 静默截断；两份 V1 不同步 → H2/PG 行为分叉 | 是否建立启动期/测试期 DDL↔实体↔常量对账？是否接受“两份 V1 靠 MigrationSetGuardTest 守同字节、其余靠人工”？ |

---

## 4. 扫描模式 / 排除面 / 可能遗漏

### 4.1 扫描模式（实际执行）

| 扫描面 | 模式/方法 |
|---|---|
| Java 主源（非 AI） | `find` 列出 106 个 `.java`，排除 `ai/**`；逐文件读取约 60 个核心文件（config/job/task/file/data/definition/excel/health/seed/common/security 相关） |
| 数值字面量 | 自写脚本遍历非 AI Java，剥离注释与字符串后匹配 `\d[\d_]*(\.\d+)?`，人工筛掉下标/枚举/状态码 |
| 常量 | `grep -nE "static final"` 与 `"private static final"` 两轮，逐个回溯消费点 |
| 时间/并发 | `Duration.of*`、`TimeUnit.*`、`Thread.sleep`、`scheduleWithFixedDelay`、`newSingleThread*`、`newScheduledThreadPool`、`ThreadPoolTaskExecutor`、`new ThreadPoolExecutor`、`SynchronousQueue`、`ArrayBlockingQueue` |
| 定时任务 | `@Scheduled` / `@EnableScheduling` / `fixedDelay` / `cron`：非 AI 面 **0 命中**（`JobWatchdog` 类注也明确“仓内无 @EnableScheduling”） |
| 分页/上限 | `PageRequest.of`、`@RequestParam(defaultValue`、`@Column(length`、`@Size`、`@Max`、`setMaxResults`、`.limit(`、`slice(`、`maxlength` |
| 配置键 | 全读 `R/application.yml`（524 行）、`application-cluster.yml`、`application-postgresql.yml`、`application-h2tcp.yml`；`grep` 非 AI 段的 `server/spring.datasource/spring.data.redis/spring.servlet/spring.lifecycle/management` |
| 业务库 DDL | 逐文件读 21 个 SQL（`R/db/migration` 10 个 + `R/db/postgresql` 11 个）与 1 个 Java 迁移 `J/db/migration/V6__Task_child_fk.java`；提取全部 `CREATE TABLE` / `ALTER TABLE` 的 `VARCHAR/CHAR(n)`、`INT/BIGINT`、`DEFAULT n`、`NUMERIC/DECIMAL(p,s)`、`CHECK (...)`；`ai_*` 表排除，已删除表标注为迁移历史（见 §1.5 末尾） |
| 框架缺省 | 对未出现的键按 Spring Boot 3.5 / Tomcat 10.1 / HikariCP 默认值登记（Hikari 池 10、connectionTimeout 30s、validationTimeout 5s、idleTimeout 10m、maxLifetime 30m；Tomcat max-threads 200、accept-count 100、max-connections 8192、max-keep-alive-requests 100；lifecycle 30s） |
| 跨域引用 | 非 AI 代码引用 AI 面常量（`aiStreamWatchdog`、`RedisAvailability.NEGATIVE_TTL_MS`）单独列附录，避免“非 AI 面看不见事实源” |
| 旧账对照 | 读 `docs/evidence/补档-NUMSPEC-清单总账.md` 全文，只用于 ID 映射与差异核对；所有行号/值以当前磁盘为准 |

### 4.2 明确排除的面

- `backend/src/main/java/com/example/configmgr/ai/**`（AI 运行时、模型配置、上下文/压缩、工具、SSE 投递池等；仅跨域引用列附录）。
- `backend/src/test/**` 与 `backend/src/test/resources/application.properties`（测试覆写，如 `scan-period=1h`、`runner-active-store=memory`、`cross-instance-store=memory`；属断言/测试装配，不是生产规格）。
- `frontend/**`、`scripts/**`、`.github/**`、`spike/**`、`docs/**` 内数字（由其他分册负责；本文只在“消费关系”处引用，如 E2E 120s、前端默认页大小）。
- 纯业务种子值：`DataSeedRunner` 的汇率/税率/300 行价格数据/`Random(42)`、`GlmSeedService` 的各定义 `rowsPerScope`（3/4/5/8/16/30/40/120）与生成式字段取值；`sortOrder` 0..8、100..107 等业务元数据。
- 状态码/错误码/枚举序号：HTTP 400/409/503、`ErrorCodes.*`、`JobStatus`/`TaskStatus` 等枚举、`SharedBeatState` 分支；`GlobalExceptionHandler` 的 `23506/1452` 仅在附录作为协议码登记。
- 算法下标/循环边界/数组长度：`for (int i=0;...)`、`substring` 下标、`pct = processed*100/total`、`rowIndex = i+2`（1-based + header）、`colLetter` 的 26 进制、CIDR 位运算等。
- 依赖版本：`pom.xml`、`package.json`、Spring Boot 3.5.x、POI 版本等。
- 纯布局/样式：列宽 `20*256`、批注锚点、`max-height`、`px`、日志 pattern `%5p` 宽度等（仅 N-BE-090 作为 Excel 布局附录登记）。
- **AI 表 DDL（`ai_*`）与已删除 `ai_chat_message` 的数值约束**：按任务边界排除；`ai_chat_message` 的 `CHECK (seq > 0)` 等只在 §1.5 末尾标注为迁移历史，不计入附录 B。**业务表 DDL 已在 §1.5 逐表登记**，不再列为未扫描面。
- 环境变量/CLI 覆盖、profile 叠加顺序、运行时实际生效值（未启动服务验证）。

### 4.3 可能遗漏（如实登记）

1. **框架缺省没有穷尽**：Tomcat 还有 `max-swallow-size`、`max-http-form-post-size`、`keepAliveTimeout`、`processorCache` 等；Hikari 还有 `keepaliveTime`、`initializationFailTimeout`、`registerMbeans` 等；Lettuce 连接池参数未展开。本文只登记了与“等待/容量/上限”最相关的项。
2. **未启动服务读取运行时值**：没有调用 `/health/ready`、`/api/ai/health`、Actuator（本仓无 actuator）或 JVM 参数回显；所有值来自静态源码/配置。
3. **profile 组合未实测**：`postgresql,cluster` 叠加、env 覆盖 yml、`--server.port` 覆写、测试 classpath 覆写均未运行验证；只按文件字面登记。
4. **默认 profile 的 `server.shutdown` 未设置**：因此 `spring.lifecycle.timeout-per-shutdown-phase` 默认 30s 在默认 profile 实际不生效；本文按框架缺省登记并注明。若部署时统一加 `server.shutdown=graceful`，该值会立即变成有效规格。
5. **`management.*` 不存在**：本仓无 actuator 依赖，`HealthController` 自建 `/health/live`、`/health/ready`；因此没有 `management.endpoints.*`、`management.server.*` 等运行数字可登记。
6. **`app.file.key-migration.*` 已删除**：`d50531a` 已提交删除 3 个迁移类和配置键，现态不存在；旧文档若引用该键，已不再适用。本文未把它列为现役项。
7. **`JobHeartbeatRegistry` 非 Spring 构造器**用 `Duration.ofMinutes(30)` 作为 `max-execution-time` 的兜底（`:141/155/160`），仅供单测/手工装配；生产 Spring 路径读 `AppProperties`。本文未单列该测试装配值，30m 本身已在 N-BE-047 登记。
8. **`ReadinessProbeRunner.defaults()`**（2s/4/8）用于测试/极简装配；生产由 `ReadinessProperties` 注入。本文合并在 N-BE-033..035，未单列。
9. **`TaskSseService` 的 10s 重试与 AI 调度器耦合**：只登记了消费关系（N-BE-088），未评估 AI 面调度器负载对任务 SSE 重试延迟的影响。
10. **`SseWriteBudget` 跨通道共用**：15s/256 的数值已登记，但“AI 通道 + 任务通道”共用同一写出池的容量切分仍无数据；类注已列为后续项。
11. **数据库侧参数**：`max_connections`、PG `statement_timeout`（明确不设）、H2 `MAX_MEMORY_ROWS` 等未在仓内配置，未登记；它们会影响 N-BE-009/019/029 的实际可用性。
12. **时间/时区**：`GMT+8` / `Asia/Shanghai` 双写只在附录登记；未核对 DST/多时区部署（本项目固定中国时区）。
13. **本地化缓存**：`spring.messages.cache-duration=3600` 的实际生效还受 `fallback-to-system-locale=false`、`basename=messages` 影响；本文只登记数字。
14. **未覆盖 `FileController` 的 ZIP 解压条目数/单条目大小**：代码没有显式条目数上限，只有 multipart 总请求 6MB 兜底；这是一个“缺失的上限”，详见 §4.4。
15. **DDL 只做静态文件核对**：未连库查 `information_schema`/`pg_catalog`；生产库若被手工改过列宽、索引或约束，可能与 `V1`/`V2`/`V6`/`V7` 文件不一致。迁移的 checksum/已应用版本也未验证。
16. **DDL 非数值默认未登记**：`CURRENT_TIMESTAMP`、`BOOLEAN DEFAULT FALSE`、`VARCHAR DEFAULT 'GLOBAL'/'PENDING'/'STAGED'/'UPSERT'/'ACTIVE'` 等不是数值规格，未逐列列出；`NUMERIC/DECIMAL=0`、`CHECK=0` 的结论只针对现役非 AI 表，AI 表（如 `ai_feedback.rating`、`ai_chat_message.seq`）另有数值约束。
17. **`V6` 外键迁移只核对了 `ON DELETE CASCADE`**：H2 Java 版与 PG SQL 版都无数字默认/精度；其“孤儿行前置检查”是 0/非 0 判据，未作为数字规格登记。

### 4.4 无数字但构成“上限缺口”的观察项（不编号、不计入 106 条）

以下位置没有可登记的数字，但“没有上限”本身就是规格缺口，单列供后续裁决：

- **ZIP 批量上传**：`J/task/controller/FileController.java:186-197` 的 `extractZip` 对每个 entry `zis.readAllBytes()`，条目数与解压后总大小均无上限；只受 `max-request-size=6MB`（压缩包大小）约束。6MB zip 可解压出远大于 6MB 的内容，且全部条目一次性进堆内存。
- **Excel 读取**：`J/excel/ExcelReader.java:30-75` 用 `XSSFWorkbook` 全量载入并遍历到 `getLastRowNum()`，无最大行数/工作表数/单元格数上限；xlsx 是压缩容器，5MB 上传件解压后可能远大于 5MB，全部行进入堆内存。
- **定义列表**：`J/definition/controller/DefinitionController.java:27-39` 的 `GET /api/definitions` 全量返回，无分页/上限；当前定义数约 15，随自定义定义增长会线性变大。
- **任务概览/作业/文件列表**：`J/task/controller/TaskController.java:59-65` 的 `overview` 返回全部作业与文件；`J/job/controller/JobController.java:34-37` 的 `listJobs` 返回任务全部作业；`J/job/controller/JobController.java:65-70` 的 `getDiff` 返回任务全部暂存行；均无分页。
- **下载全部**：`J/task/controller/FileController.java:273-283,344-356` 的 `download-all` 把全部文件读进 `ByteArrayOutputStream` 再返回，无文件数/总字节上限。
- **启动僵尸恢复**：`J/job/service/StaleJobRecoveryRunner.java:55` 用 `findAll()` 扫描全部作业，无分页；历史作业量大时启动耗时会线性增长。
- **启动 runner-active 清理**：`J/job/service/RunnerActiveKeyCleanupRunner.java:34` 用 `findAllIds()` 遍历全部作业并逐个查 Redis，无批量/上限。
- **作业看门狗扫描**：`J/job/service/JobWatchdog.java:189` 的 `findByStatusIn` 取全部 PENDING/RUNNING 作业，无分页；表规模小时可接受，但无显式边界。
- **任务 SSE 订阅表**：`J/task/service/TaskSseService.java` 的 `emitters` 按 taskId 持有订阅者，单任务订阅者数无上限；写出池有 256 线程硬上限，但订阅者表本身无上限。

---

## 5. 与旧 NUMSPEC 的主要差异（现态重查结论）

| 项 | 旧账（2026-10-10 及追加） | 现态（2026-10-31 磁盘） | 影响 |
|---|---|---|---|
| 后端配置键数量 | 31 条（B1-01..31），后追加至 63 | 本文主表 33 条非 AI 配置键（含 profile/健康/任务/作业） | 旧统计不能直接复用 |
| `app.ai.*` | 大量条目 | 全部排除（AI 分册负责） | 本文不重复登记 |
| `app.job.*` 看门狗 | 旧表正文无独立 ID，只在 S-10 派生提及 | 四键（heartbeat 30s / scan 10s / dead 3 / max 30m）已是生产配置，且有启动期 fail-fast | 需补正式编号与依据复核（旧表未编号） |
| `app.health.readiness.*` | 旧表未覆盖 | 四键（2s / 4 / 8 / 0s）+ 5s profile + 60s 上限 | 新增面，本文首次登记 |
| `app.task.sse.*` | B1-28/29 | 值未变（15s / 5m），但代码默认与校验更完整 | 旧值仍成立 |
| `app.file.key-migration.*` | 旧表未列（后续新增） | `d50531a` 已提交删除 3 个类与配置键，现态不存在 | 旧引用失效 |
| Hikari / Tomcat 缺省 | B2-32 一行合并（Hikari≈10 / multipart 1MB·10MB / Tomcat 200） | multipart 已显式 5MB/6MB；Hikari 默认 10 仍隐式；Tomcat 200/100/8192/100 仍隐式 | B2-32 需拆分，本文已拆为 N-BE-004/005/009..018 |
| 业务表 DDL 数值 | 旧表未系统覆盖；仅把 `validation_issues.message 1024` 散记在 B2-28 | `V1`/`V2`/`V6`/`V7` 的 14 张现役表已逐表登记在 §1.5；无 `NUMERIC/DECIMAL`、无 `CHECK`；`ai_chat_message` 已删除仅作历史 | 旧表无法回答“金额/汇率精度”“进度/计数能否为负”等问题 |
| 分页默认值 | B2-31：50/50/10 | DataController 已改 20（2026-10-10 裁决⑥）；Task 10、Job 50 未变；Data 无上限 | 旧 B2-31 的 50 已过时，本文按 20 登记 |
| 问题消息 1024 | B2-27/28 疑同源未声明 | 仍是两处独立 1024，未合并 | 旧观察项仍开放 |
| Excel 1000 / 100 | B2-26 / B2-25 | 值未变，仍无注释 | 旧观察项仍开放 |
| `SseWriteBudget` 256 | 旧 B2-02 记 `max=Integer.MAX_VALUE`（资源修复前） | 现为硬上限 256（资源修复批），旧 B2-02 已不完全适用 | 本文 N-BE-055 按 256 登记并注明旧值 |
| 作业取消 TTL 1h | B2-29 | 值未变 | 仍开放（与 30m/65m 的关系未写明） |

---

## 6. 只读声明

- 本文只新建 `docs/research/numspec-2026-10-31/backend-business.md`；未修改任何 `backend/src/main/**`（含 `db/migration/**`、`db/postgresql/**` DDL）、`backend/src/test/**`、`application*.yml`、脚本或前端文件。
- 未执行 `git add/commit/reset/checkout` 等写操作；未启动后端服务、测试、浏览器或 Redis。
- 文中所有“建议”均为**审视建议**，不代表本次已改值；是否调整由后续裁决决定。
- 扫描时 HEAD 与工作树状态见文首；若后续 HEAD/工作树变化，行号与“现态”结论需重新核对。
