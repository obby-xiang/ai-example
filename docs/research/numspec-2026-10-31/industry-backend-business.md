# 后端非 AI 面 · 业界对标与逐 ID 复审（industry-backend-business）

> **本文性质**：只读复审产物。本次唯一改动 = 本文件；未改 `backend-business.md`、未改生产/测试/配置、未做 git 写操作、未运行测试/服务/浏览器。
> **被审对象**：`docs/research/numspec-2026-10-31/backend-business.md` 全部 **106 项**（`N-BE-001…106`，其中主表 80、附录 A 11、附录 B 15）。
> **方法**：逐行读源文档的「现值 + 文件:行」，再对照**官方一手资料**（框架文档、数据库手册、协议规范、平台文档）；查不到权威数值的，一律标〔无统一标准〕并给出**可复算的派生式**，不编造「业界主流值」。
> **版本口径**：Spring Boot **3.5.14**（`backend/pom.xml:9-11`）、内嵌 Tomcat 10.1.x、HikariCP 6.x（Boot BOM 管理）、POI **5.5.1**（`pom.xml:23`）、PostgreSQL 文档按 current（18）、H2 2.x。

---

## 0. 结论摘要（先看这 8 条）

| # | 结论 | 证据强度 |
|---|---|---|
| 1 | **容量链是「16 条作业线程抢 10 条连接」**：Hikari 上限 10（框架缺省、yml 未写键）、`AsyncConfig` max 16、Tomcat maxThreads 200。16−10=6 条作业线程会在 Hikari `connectionTimeout` 30s 内排队；PG/h2tcp profile 已把该等待降到 5s，默认 profile 仍是 30s。建议：**Async max 16→8、默认 profile `connectionTimeout` 30s→5s、`validationTimeout` 5s→2s**；Hikari 10 与 Tomcat 200 保留。 | [S1][S2] + 算术 |
| 2 | **停机链是「三段串行」，不是两个独立窗口**：Web 优雅停机（drain + phase）之后，`ThreadPoolTaskExecutor` 的 await 在 bean 销毁阶段继续等；严格上界 = drain + phase + await。当前 PG = 5 + 20 + 30 = **55s**，默认 = 0 + 30 + 30 = **60s**，都远超 K8s 默认 `terminationGracePeriodSeconds=30s`；而 `MAX_DRAIN_WINDOW=60s` 还允许配出更长的必死组合。建议：**drain 上限 60→10s、PG/Cluster drain 5→10s、await 30→10s、默认 phase 30→20s，并把多实例 grace 提到 45s**（10+20+10+5=45 ≤ 45）；若必须保留 grace 30s，则 phase 必须 ≤12s、await ≤8s、drain ≤5s。 | [S3][S12] + 算术 |
| 3 | **源文档 N-BE-018 的「默认 profile 未开 graceful，30s 不生效」与 Spring Boot 3.5 官方文档冲突**：3.5 文档明确「graceful shutdown is enabled by default」（3.4 起）。按 3.5.14 的框架缺省，默认 profile 的 30s **应为生效**（需用启动日志/Actuator 再确认一次）。这条必须先纠正，否则第 2 条的停机预算全部算错。 | [S3] |
| 4 | **分页是「10 / 50 / 20 三套缺省 + 数据端点无上限」**：GitHub 默认 30/上限 100（超限静默夹取）、Stripe 默认 10/范围 1–100、Spring Boot `pageable` 默认 20/上限 2000。建议：**三处缺省统一 20，后端硬上限 100（静默 clamp 并在响应回显实际 size）**，与 GitHub 的「不报错、降到上限」一致。 | [S9][S10][S11] |
| 5 | **上传 5MB/6MB 本身在主流区间**（Spring Boot 缺省 1MB/10MB），真正缺的是**解压后边界**：ZIP 每 entry `readAllBytes()`、Excel `XSSFWorkbook` 全量载入，只受 6MB 压缩包约束。OWASP 明确要求「按解压后大小设限、用安全方式计算 zip 大小」。建议：**先加「单 entry 解压上限 + 总解压上限 + 条目数上限 + 最大数据行数」，再决定 5MB 是否要调**；在这些上限落地前，不建议把 5MB 放大。 | [S4][S17] |
| 6 | **SSE 的 15s 写预算有实测依据，256 线程硬上限没有**：15s 对应 64KB 帧 p99 1.15ms 的四个数量级余量，保留；256 只是「投递池 8 的 32 倍」的推算，不是慢连接分布。且 nginx `proxy_read_timeout` 缺省 60s，5m 的 emitter 寿命必须配 **≤20s 的心跳/注释帧**，否则反代先断。建议：**15s 保留、5m 保留、256→64（压测后定）、补心跳**。 | [S14][S16][S20] |
| 7 | **锁等待的三个 60s 不是一回事**：PG `lock_timeout=60s` 是**全会话**（所有业务请求都可能等 60s），H2 `LOCK_TIMEOUT=60000` 只在 h2tcp profile，Flyway 启动锁 60s 与 Flyway 自身 `lockRetryCount` 缺省 50（≈50s）同量级。建议：**PG 全局 60s→10s，种子单飞另开 60s 会话；Flyway 60s 保留但配置化**。 | [S5][S7][S19] |
| 8 | **业务 DDL 的系统性缺口不是某个宽度，而是 0 个 `CHECK`、0 个 `NUMERIC/DECIMAL`**：进度/计数可被写成负数；金额/汇率/税率全在 `data_json` 文本里，DDL 不约束精度。建议：**进度/计数加 `CHECK (>=0)`；需要求和/筛选的金额落 `NUMERIC(19,4)`，否则应用层统一 `BigDecimal` 标度；`INT` 保留（Excel 上限 1,048,576 < 2^31−1）**。 | [S21][S22][S23] |

> 覆盖 **106/106（100%）**；统计：**保留 72 / 改值 21 / 配置化 8 / 需压测 2 / 需裁决 3**；按优先级 **P0 5 项、P1 22 项、P2 79 项**（见 §9）。

---

## 1. 判定口径

### 1.1 「业界口径」四档（每行必标一档）

| 标记 | 含义 | 本文例子 |
|---|---|---|
| 〔标准〕 | 有正式规范/协议/平台硬限，改它就是违规或越界 | PG `INT` 上限 2,147,483,647；Excel 单表 1,048,576 行；PG SQLState 码 |
| 〔有据〕 | 官方文档给了明确数值/缺省值，可直接引用 | Hikari `maximumPoolSize=10`、`connectionTimeout=30000`；Tomcat `maxThreads=200`、`maxConnections=8192`；POI SXSSF 窗口缺省 100 |
| 〔惯例〕 | 官方文档只描述模式，没有统一数值；可按约束推导 | 「有界池 + 饱和拒绝」「clamp 到上限而非报错」「心跳间隔 < 反代 idle 超时」 |
| 〔无统一标准〕 | 查不到权威一手数值，只能按本仓约束判 | 作业批大小 100、探测线程 4/8、判死租约 30/90s、写出池 64/256、列宽 64/128/256 |

### 1.2 「建议」五桶（文末统计按此机械计数）

| 标记 | 含义 |
|---|---|
| `【保留】` | 值不动；只补注释/文档，或只做联动核对 |
| `【改值→X】` | 给出具体新值；X 必须能由来源或算式支撑 |
| `【配置化】` | 值可留，但要变成配置键/单一事实源/显式写出，消除「配置面看不见」 |
| `【需压测】` | 值暂留，但**必须**有一次实测/实测数据才能定稿（不是「保留」的同义词） |
| `【需裁决】` | 不是纯技术值，需产品/架构/AI owner 先定口径 |

> 判定原则：**能算不等式的按不等式判，算不出的按「有据/无据」判**。源文档写「有依据」但只引旧账/设计稿的，本文降级为〔无统一标准〕+ 保留，不替它编一个业界值。

---

## 2. 引用来源表（S1–S30，官方一手资料）

| 代号 | 来源 | 本文用到的关键数值 |
|---|---|---|
| [S1] | HikariCP README《Configuration》 https://github.com/brettwooldridge/HikariCP | `maximumPoolSize` 缺省 **10**；`connectionTimeout` 缺省 **30000ms**；`validationTimeout` 缺省 **5000ms**；`idleTimeout` 缺省 **600000ms**；`maxLifetime` 缺省 **1800000ms**；`minimumIdle` 缺省 = max；官方反对「按并发放大池」 |
| [S2] | Tomcat 10.1《HTTP Connector》 https://tomcat.apache.org/tomcat-10.1-doc/config/http.html | `maxThreads` **200**；`acceptCount` **100**；`maxConnections` **8192**；`maxKeepAliveRequests` **100**；`connectionTimeout` **60000ms**（自带 server.xml 为 20000）；`connectionTimeout` 语义是等请求行/请求体 |
| [S3] | Spring Boot 3.5《Graceful Shutdown》 https://docs.spring.io/spring-boot/3.5/reference/web/graceful-shutdown.html | 3.4+ **graceful 默认开启**；`spring.lifecycle.timeout-per-shutdown-phase` 缺省 **30s**；`server.shutdown=immediate` 可关 |
| [S4] | Spring Boot《MultipartProperties》 javadoc https://docs.spring.io/spring-boot/api/java/org/springframework/boot/autoconfigure/web/servlet/MultipartProperties.html | `max-file-size` 缺省 **1MB**；`max-request-size` 缺省 **10MB**；`file-size-threshold` 缺省 0 |
| [S5] | PostgreSQL《Client Connection Defaults》 https://www.postgresql.org/docs/current/runtime-config-client.html | `lock_timeout` 缺省 **0（无限等）**；`statement_timeout` 缺省 0；`lock_timeout >= statement_timeout` 时前者无意义 |
| [S6] | PostgreSQL《Connection Settings》 https://www.postgresql.org/docs/current/runtime-config-connection.html | `max_connections` 缺省通常 **100**；`superuser_reserved_connections` 3 |
| [S7] | H2《Commands》 https://h2database.com/html/commands.html | `SET LOCK_TIMEOUT` 缺省 **1000ms**；URL 可内联 `LOCK_TIMEOUT=10000` |
| [S8] | Apache POI《Busy Developers' Guide / SXSSF》 https://poi.apache.org/components/spreadsheet/how-to.html | SXSSF 窗口缺省 **100**（`DEFAULT_WINDOW_SIZE`）；`-1` 为不限；临时文件可能远大于输入（20MB CSV → >1GB XML）；需 `dispose()`；可 `setCompressTempFiles(true)` |
| [S9] | GitHub《Using pagination in the REST API》 https://docs.github.com/en/rest/using-the-rest-api/using-pagination-in-the-rest-api | 多数端点 `per_page` 缺省 **30**、上限 **100**；超上限**不报错，自动降到上限** |
| [S10] | Stripe《Pagination》 https://docs.stripe.com/api/pagination | `limit` 缺省 **10**，范围 **1–100**；游标翻页 |
| [S11] | Spring Boot《Common Application Properties》 https://docs.spring.io/spring-boot/appendix/application-properties/index.html | `spring.data.web.pageable.default-page-size` **20**；`max-page-size` **2000**；`spring.data.redis.lettuce.shutdown-timeout` **100ms** |
| [S12] | Kubernetes《Liveness, Readiness and Startup Probes》 https://kubernetes.io/docs/concepts/configuration/liveness-readiness-startup-probes/ | `periodSeconds` 缺省 **10s**；`failureThreshold` 缺省 **3**；`timeoutSeconds` 缺省 **1s**；Pod `terminationGracePeriodSeconds` 缺省 **30s** |
| [S13] | Hangfire《Using Redis》 https://docs.hangfire.io/en/latest/configuration/using-redis.html | `InvisibilityTimeout` 缺省 **30 分钟**（2.4 起 Obsolete） |
| [S14] | MDN《Using server-sent events》 https://developer.mozilla.org/en-US/docs/Web/API/Server-sent_events/Using_server-sent_events | HTTP/1.1 下每浏览器+域最多 **6** 条 SSE 连接；HTTP/2 协商流缺省 **100**；`retry` 字段控制重连；注释行可做 keep-alive |
| [S15] | MDN《Access-Control-Max-Age》 https://developer.mozilla.org/en-US/docs/Web/HTTP/Reference/Headers/Access-Control-Max-Age | 缺省 **5s**；Chrome 上限 **7200s**；Firefox 上限 **86400s** |
| [S16] | nginx《ngx_http_proxy_module》 https://nginx.org/en/docs/http/ngx_http_proxy_module.html | `proxy_read_timeout` 缺省 **60s**（两次读之间静默即断）；`proxy_send_timeout` 缺省 **60s** |
| [S17] | OWASP《File Upload Cheat Sheet》 https://cheatsheetseries.owasp.org/cheatsheets/File_Upload_Cheat_Sheet.html | 必须设文件大小上限；**解压后**再设上限、用安全方式计算 zip 大小；ZIP 本身不推荐 |
| [S18] | Lettuce《Client Options》 https://github.com/lettuce-io/lettuce-core/wiki/Client-Options | Cluster `maxRedirects` 缺省 **5**；拓扑周期刷新缺省 **60s**；Socket 连接超时缺省 **10s**；命令超时由 `RedisURI` 决定 |
| [S19] | Redgate Flyway《Lock retry count》 https://documentation.red-gate.com/fd/flyway-lock-retry-count-setting-277579009.html | `lockRetryCount` 缺省 **50**（官方「锁重试次数」参数） |
| [S20] | Spring Framework《SseEmitter》 javadoc https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/web/servlet/mvc/method/annotation/SseEmitter.html | 无参构造「timeout 由 MVC 配置或底层服务器缺省决定」；显式 timeout 才是有界寿命 |
| [S21] | Microsoft《Excel specifications and limits》 https://support.microsoft.com/en-us/office/excel-specifications-and-limits-1672b34d-7043-467e-8e27-269d656771c3 | 单表 **1,048,576 行 × 16,384 列**；单元格 **32,767 字符**；文件名（含路径）**218 字符** |
| [S22] | PostgreSQL《Character Types》 https://www.postgresql.org/docs/current/datatype-character.html | `varchar(n)` 按**字符**计；三种字符类型无性能差异；不想设上限就用 `text`，不要「随便编一个长度」 |
| [S23] | PostgreSQL《Numeric Types》 https://www.postgresql.org/docs/current/datatype-numeric.html | `integer` 范围 **−2,147,483,648…2,147,483,647**；`numeric` 精确、推荐用于金额；显式精度上限 1000 |
| [S24] | Redis《Cluster specification》 https://redis.io/docs/latest/operate/oss_and_stack/reference/cluster-spec/ | `MOVED`/`ASK` 重定向语义；resharding 期间重定向是常态 |
| [S25] | Spring Boot《RedisProperties》 javadoc https://docs.spring.io/spring-boot/api/java/org/springframework/boot/autoconfigure/data/redis/RedisProperties.html | `timeout`/`connectTimeout`/`cluster.maxRedirects`/`lettuce.shutdownTimeout` 均为可配项；不配则用客户端缺省 |
| [S26] | Spring Boot 3.0.0-M5 配置变更 https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-3.0.0-M5-Configuration-Changelog | `spring.data.redis.lettuce.shutdown-timeout` 缺省 **100ms** |
| [S27] | Spring Boot《Embedded Web Servers How-to》 https://docs.spring.io/spring-boot/how-to/webserver.html | `server.port` 缺省 **8080**；可用 `SERVER_PORT` 环境变量覆盖 |
| [S28] | Java SE 21《Executors》 javadoc https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/Executors.html | `newCachedThreadPool` 线程空闲 **60s** 回收；`SynchronousQueue` + 无界 max 的形态 |
| [S29] | PostgreSQL《Error Codes》 https://www.postgresql.org/docs/current/errcodes-appendix.html | `23503` 外键、`23505` 唯一、`23514` CHECK、`23502` 非空；Class 23 为完整性约束违反 |
| [S30] | PostgreSQL《Explicit Locking / Advisory Locks》 https://www.postgresql.org/docs/current/explicit-locking.html | advisory lock 分 session/transaction 两级；session 级在会话结束时自动清理；由 `max_locks_per_transaction × max_connections` 限制总量 |
| [D] | 源文档 `backend-business.md` 的行内注释、实测记录与算式 | 15s 写预算的 p99 1.15ms、R4 的 15.1s、90s 判死窗、65m TTL 等 |

> 说明：S1–S30 均为官方一手文档页面。S10（Stripe）、S13（Hangfire）、S19（Flyway）、S24（Redis）在抓取时遇到 JS 渲染或 404，改用官方页面的搜索摘要核对，数值与官方文档一致；其余页面为本文直接抓取。凡正文写「业界常见 X」而 X 不在本表内，均标〔无统一标准〕或〔惯例〕，不冒充「有据」。

---

## 3. §1.1 运行容器 / 数据源 / Redis / 就绪探测（N-BE-001…039）

| ID | 现值 | 业界口径与常见做法 | 建议 | 理由与联动 | 来源 |
|---|---|---|---|---|---|
| N-BE-001 | `server.port=8081` | 〔有据〕Spring Boot 缺省 **8080**，支持 `SERVER_PORT` 覆盖；生产端口应由部署面注入，不写死在镜像里 | **【保留】** 8081；补一页「端口规划」说明，生产用 env 覆盖 | 8081 是避让本机 8080 的常见选择，无容量含义；前端 Vite 代理与 E2E 已按 8081 对齐，改端口是纯联动成本。与 N-BE-081（Redis 6379）同属「部署面可覆盖」类，不应成为隐式契约 | [S27] |
| N-BE-002 | `server.tomcat.connection-timeout=120000` | 〔有据〕Tomcat 缺省 **60000ms**，自带 server.xml 为 20000ms；语义是「接受连接后等请求行」+（`disableUploadTimeout=true` 时）读请求体；**不是响应写超时**。nginx `proxy_read_timeout` 缺省 60s | **【保留】** 120s；注释改成「连接/读请求体兜底，不保护 SSE 写」 | 5MB 上传在 0.5Mbps 链路上约 80s，120s 给上传留了余量；但反代 60s 会先断，若部署 nginx 必须同步调大，否则 120s 永远用不到。SSE 写侧由 N-BE-040 的 15s 预算与心跳负责，不能把 120s 当 SSE 预算 | [S2][S16] |
| N-BE-003 | `spring.messages.cache-duration=3600` | 〔有据〕Spring Boot 提供该键；生产常用长缓存（小时级），开发用 0/短缓存；无单位后缀按秒解释 | **【配置化】** 保留 3600 作 prod，新增 dev/test profile 覆写 `0` 或 `10s` | 文案变更最长 1h 才生效，是运维可感知行为；与 N-BE-079 的 CORS 3600s 同值但语义无关，注释必须分开写。无容量/并发含义，不需要压测 | [S11] |
| N-BE-004 | `max-file-size=5MB` | 〔有据〕Spring Boot 缺省 **1MB**；〔惯例〕SaaS 单文件常见 10–25MB，内网配置类 xlsx 通常 <5MB；OWASP 要求「解压后」再设上限 | **【保留】** 5MB；同时补解压后上限（见 N-BE-073/074 与 §4.4 缺口） | 5MB 对「配置数据 xlsx」是合理上限，但对 zip/xlsx 是**压缩后**大小：xlsx 是 zip 容器，5MB 可解压出数百 MB；`ExcelReader` 用 `XSSFWorkbook` 全量载入，堆风险不在 5MB 本身。先补行数/解压字节上限，再谈是否放宽到 10MB | [S4][S17] |
| N-BE-005 | `max-request-size=6MB` | 〔有据〕Spring Boot 缺省 **10MB**；multipart 边界与表单字段开销通常 KB 级，1MB 余量很宽 | **【保留】** 6MB；接口文档写清「这是整包上限，不是单文件上限」 | 当前 zip 批量是「一个文件」，6MB 即整包；若未来支持多文件同请求，必须改成 `n×5MB+1MB` 并把公式写进文档，否则客户端按单文件 5MB 拼 3 个文件会被 6MB 整包拒绝。与 N-BE-004 是「单文件/整包」一对，不能只改一个 | [S4] |
| N-BE-006 | `spring.data.redis.timeout=2s` | 〔有据〕Spring Boot 不设时用客户端缺省（Lettuce 命令超时为 60s 量级）；〔惯例〕缓存类 100ms–1s、协调类 1–3s | **【保留】** 2s；补一条「重命令（SCAN/大 ZSET）需单独评估」 | 源文档的 R4 实测「5 次串行各等 3s = 15.1s」是收紧到 2s 的直接依据；2s 能让端点快速失败，代价是网络抖动时误判。与 N-BE-007 的 2s 建连叠加，最坏一次调用约 4s，仍小于多数 LB 探测 5s | [S18][S25] |
| N-BE-007 | `spring.data.redis.connect-timeout=2s` | 〔有据〕Lettuce Socket 连接超时缺省 **10s**；〔惯例〕内网 1–3s、跨机房 3–5s | **【保留】** 2s；注释写明「建连 2s + 命令 2s 的最坏叠加 ≈4s」 | 比客户端缺省 10s 更严，符合「Redis 不可用时快速失败」；但 connect 失败后的自动重连不受这个 2s 约束，实际不可用窗口可能更长，需在监控上看 reconnect 次数。与 N-BE-006/026 同组，改一个必须三个一起看 | [S18] |
| N-BE-008 | `spring.data.redis.lettuce.shutdown-timeout=200ms` | 〔有据〕Spring Boot 缺省 **100ms**；关停等待只需覆盖在途命令的收尾，不需要覆盖重连 | **【保留】** 200ms；注释说明它只影响优雅停机，不影响业务超时 | 2× 缺省是保守值，成本是停机多等最多 200ms；必须小于 `timeout-per-shutdown-phase`（20s/30s），当前远小于。与 N-BE-018/023/060 同属停机链，但量级差两个数量级，不是瓶颈 | [S11][S26] |
| N-BE-009 | Hikari `maximum-pool-size` 缺省 `10` | 〔有据〕Hikari 官方缺省 **10**；官方明确「不要按并发放大池，连接过多会可测量地拖慢性能」；`minimumIdle` 缺省 = max，表现为固定池 | **【配置化】** 值仍 10，但在默认 yml **显式写出**该键（或启动日志回显） | 当前默认 profile 的配置面看不到这个键，排障时容易误以为「没配 = 无限制」；PG/h2tcp 都显式 10，三处表达不一致。显式化不改运行值，是零风险的可观测性修复。与 N-BE-010/057/058 构成容量链 | [S1] |
| N-BE-010 | Hikari `connectionTimeout` 缺省 `30000ms` | 〔有据〕Hikari 缺省 **30000ms**，最低 250ms；〔惯例〕Web 请求路径常压到 1–5s 快速失败 | **【改值→5000ms】** 默认 profile 也显式 5s（与 PG/h2tcp 一致） | 16 条作业线程抢 10 条连接时，6 条各等 30s = 180 线程·秒；普通 REST 线程也会被同一池拖住。30s 只适合「批处理可长等」的场景，而本仓把作业和 Web 放在同一池。改 5s 后 N-BE-011 必须 <5s（见下条），N-BE-058 必须 ≤10 | [S1] |
| N-BE-011 | Hikari `validationTimeout` 缺省 `5000ms` | 〔有据〕Hikari 缺省 **5000ms**，且官方要求 **必须小于 `connectionTimeout`**；PG profile 已显式 2000ms | **【改值→2000ms】** 两个 profile 统一 2s | 若 N-BE-010 改成 5s 而本项仍 5s，就违反「validation < connection」的硬约束，Hikari 启动会校正/告警；统一 2s 既满足约束，也与 PG 现有值一致。与 N-BE-020/021 同组 | [S1] |
| N-BE-012 | Hikari `idleTimeout` 缺省 `600000ms` | 〔有据〕Hikari 缺省 **600000ms**；官方注明**仅当 `minimumIdle < maximumPoolSize` 时生效**；本仓 `minimumIdle` 缺省 = 10 = max | **【保留】** 10m；注释明确「当前固定池下该值不生效」 | 这是一个「看起来在调、实际不起作用」的键：固定池不会回收空闲连接。保留它没有坏处，但必须写清语义，避免有人以为 10m 后连接会缩容。若未来要做弹性池，先把 `minimumIdle` 降到 2–5，再谈 idleTimeout | [S1] |
| N-BE-013 | Hikari `maxLifetime` 缺省 `1800000ms` | 〔有据〕Hikari 缺省 **1800000ms**；官方强烈建议显式设置，且**比数据库/基础设施的连接上限短几秒** | **【配置化】** 显式写出 30m；部署前核对 PG/LB 的 idle 切断值，若为 30m 则改 25m | 30m 是缺省不是论证值；云 PG、PGBouncer、四层 LB 都可能有 5–30m 的空闲切断。Hikari 有负向衰减避免同时过期，但仍应保持「maxLifetime < 基础设施上限」。与 N-BE-012 一起决定连接存活形态 | [S1] |
| N-BE-014 | Tomcat `max-threads` 缺省 `200` | 〔有据〕Tomcat 缺省 **200**；Servlet 3 async（SSE 进入 async 后）会释放请求线程 | **【保留】** 200；可在容量文档登记「200 是缺省，不是按本系统算出的」 | 200 远大于内网并发；SSE 不长期占用 200 条线程（async 释放），所以 200 不是 SSE 的瓶颈。真正的瓶颈是 Hikari 10 与写出池 256。与 N-BE-009/015/016 一起看，不建议单独调 | [S2] |
| N-BE-015 | Tomcat `accept-count` 缺省 `100` | 〔有据〕Tomcat 缺省 **100**；是 `maxConnections` 满后的 OS 等待队列，溢出即拒绝/超时 | **【保留】** 100；随容器调优登记，不单独改 | 内网小流量下 100 的排队深度足够；它的作用是「全忙时给一次排队机会」，不是吞吐参数。若真出现 200 线程全忙，排队 100 只会让客户端更晚收到失败，不如让上游 LB 快速摘流 | [S2] |
| N-BE-016 | Tomcat `max-connections` 缺省 `8192` | 〔有据〕Tomcat 缺省 **8192**；SSE 长连接会长期占用连接额度 | **【保留】** 8192；容量文档写「SSE 订阅者 + keep-alive + 普通请求」的占用关系 | 8192 远大于本系统可能的连接数；真正会先到上限的是浏览器侧（HTTP/1.1 每域 6 条 SSE）和反代 worker 连接数，不是 Tomcat。与 N-BE-014/017 同属「容器缺省，登记即可」 | [S2][S14] |
| N-BE-017 | Tomcat `max-keep-alive-requests` 缺省 `100` | 〔有据〕Tomcat 缺省 **100** 请求/连接；-1 为不限 | **【保留】** 100；随容器调优登记 | HTTP/1.1 复用 100 次后关连接，是 Tomcat 的常规防老化策略；对 SSE 无影响（SSE 是单条长响应，不是 100 次请求）。不需要为它单独做容量论证 | [S2] |
| N-BE-018 | `spring.lifecycle.timeout-per-shutdown-phase` 缺省 `30s` | 〔有据〕Spring Boot 3.5 文档：**graceful shutdown 默认开启**，该键缺省 **30s**；`server.shutdown=immediate` 可关；该超时只覆盖 Web 优雅停机阶段，`ThreadPoolTaskExecutor` 的 await 在其后的 bean 销毁阶段 | **【配置化】** 显式写 `server.shutdown=graceful` + `20s`（或确认默认后保留 30s）；同时修正源文档「默认不生效」的说法 | 源文档假设「默认 profile 未开 graceful ⇒ 30s 不生效」，但 3.5 文档明确默认开启，按 3.5.14 的框架缺省**应为生效**（建议启动日志确认）。严格串行上界 = 0s drain + 30s phase + 10s await = 40s，大于 K8s 默认 grace 30s；要么把 phase 降到 20s 并把 grace 提到 45s，要么把 grace 提到 45s 并保留 30s。先纠错，再定窗口 | [S3][S12] |
| N-BE-019 | PG `maximum-pool-size=10` | 〔有据〕Hikari 缺省 10；PG `max_connections` 缺省通常 **100**，其中 superuser 预留 3 | **【保留】** 10；部署检查项写「实例数 × 10 + 预留 < max_connections」 | 3 实例 × 10 = 30 < 100，余量充足；若扩到 8 实例则是 80，接近 100，必须同时调 PG 或引入 PGBouncer。与 N-BE-009/029 是同值不同部署形态，不能只看单实例 | [S1][S6] |
| N-BE-020 | PG `connection-timeout=5000ms` | 〔有据〕Hikari 缺省 30s；5s 是「探针必须在 LB 超时内给结论」的常见折中 | **【保留】** 5s；但 `/health/ready` 的 DB 探针要用更短的取连接预算（≤1s） | 5s 对业务请求可接受，对共享 2s 的就绪探测太长：池满时 DB 维度会先花 5s 等连接，端点却按 2s 记 DOWN。解决办法不是把业务 5s 降到 2s（会误伤正常请求），而是让探针走「有界 future + 1s 取连接」。与 N-BE-033/037 联动 | [S1] |
| N-BE-021 | PG `validation-timeout=2000ms` | 〔有据〕Hikari 缺省 5000ms；必须 < connectionTimeout；2s 是常见校验值 | **【保留】** 2s；注释补「与 2s 命令/探测时限同量级，但是独立机制」 | 2s < 5s 满足硬约束；它只校验连接活着，不执行业务 SQL。与 N-BE-011 的默认 profile 5s 形成差异，建议最终统一到 2s（见 N-BE-011） | [S1] |
| N-BE-022 | PG `SET lock_timeout='60s'` | 〔有据〕PG 缺省 **0（无限等）**；`lock_timeout` 只限「等锁」不限语句执行；官方注明 `lock_timeout >= statement_timeout` 时前者无意义 | **【改值→10s】** 全局 10s；种子单飞另开连接/`SET LOCAL lock_timeout='60s'` | 60s 是**全会话**设置：任何业务 UPDATE 撞上行锁都可能挂 60s，同时占住一条 Hikari 连接和一个 Tomcat 线程。种子单飞确实要等完整一轮播种，但那是**一个**场景，不该让所有请求陪跑。10s 对 OLTP 足够，60s 只留给 seed 连接。P0 | [S5] |
| N-BE-023 | PG `timeout-per-shutdown-phase=20s` | 〔有据〕Spring Boot 缺省 30s；20s 是常见「短于编排器宽限」的取值 | **【保留】** 20s；但要求 `terminationGracePeriodSeconds ≥ 45s`（10s drain + 20s phase + 10s await + 5s 余量） | 20s 本身合理；问题在它与 drain 10s、Async await 10s 串行相加 = 40s，已大于 K8s 默认 grace 30s。要么把 grace 提到 45s，要么把 drain/phase/await 压到 5/12/8（25s + 5s 余量 = 30s）。与 N-BE-024/036/060 是同一道不等式 | [S3][S12] |
| N-BE-024 | PG `drain-window=5s` | 〔惯例〕drain 必须 ≥ LB「探测间隔 × 失败阈值」+ 余量；K8s 默认 period 10s × failure 3 = 30s，但 EndpointSlice 摘除通常更快；外部 LB 常见 5s×2=10s | **【改值→10s】** | 5s 只覆盖「2s×2+1s」这一种 LB 配置；若实际 LB 是 5s 间隔×2 次，5s 窗口在第二次探测前就结束了，503 还没被看到。10s 才完整覆盖「5s×2」；与 phase 20s、await 10s 串行 = 40s，配 45s grace 仍有 5s 余量。必须先拿到真实 LB 参数 | [S12][S16] |
| N-BE-025 | Cluster `max-redirects=3` | 〔有据〕Lettuce 缺省 **5**；Redis Cluster 在 resharding 期间 `MOVED/ASK` 是常态 | **【保留】** 3；补一句「resharding 期间若出现重定向失败，再评估 5」 | 3 比客户端缺省更保守，能快速失败而不是无限跟随；代价是槽迁移抖动时多一次失败。它不影响正常读写，只影响迁移窗口。与 N-BE-026 的 2s 超时叠加：3 跳 × 2s 最坏 6s，已超过单请求预算，实际应更快失败 | [S18][S24] |
| N-BE-026 | Cluster `timeout/connect-timeout/shutdown-timeout=2s/2s/200ms` | 〔有据〕与 standalone 同值；〔惯例〕换拓扑不应改超时语义 | **【保留】** 三值不变；用 YAML anchor/共享片段消除三处重复 | 拓扑变化只改「连谁」，不改「等多久」；当前三值与 standalone 逐字一致，是正确的。风险在维护面：三份文件各写一遍，改一处会漂移。与 N-BE-006/007/008 是同一组事实源 | [S18][S25] |
| N-BE-027 | Cluster `drain-window=5s` | 〔惯例〕同 N-BE-024；Cluster 形态通常跟在 LB 后，摘流窗口更重要 | **【改值→10s】** 与 PG 对齐 | Cluster profile 也是多实例，5s 同样只覆盖「2s×2」，覆盖不了「5s×2」；与 N-BE-024 同因同果。两处必须同值，否则「PG 10s、Cluster 5s」会让运维以为拓扑影响摘流 | [S12][S16] |
| N-BE-028 | H2 `LOCK_TIMEOUT=60000` | 〔有据〕H2 缺省 **1000ms**；H2 文档明确 URL 可内联 `LOCK_TIMEOUT` | **【保留】** 60000；注释写「仅 h2tcp 共享库/冒烟，不是生产口径」 | H2 缺省 1s 正是「第二实例直接超时」的原因；60s 让等待方能等完整一轮播种，逻辑成立。但 H2 只用于开发/三实例冒烟，不应把 60s 带进生产（生产是 PG）。与 N-BE-022 同值不同库，不能互相引用为「同源」 | [S7] |
| N-BE-029 | H2 `maximum-pool-size=10` | 〔有据〕Hikari 缺省 10；H2 TCP 服务端没有 `max_connections` 这类硬上限 | **【保留】** 10；容量说明保留「3 实例 × 10 = 30」 | H2 的瓶颈是文件锁/共享库，不是连接数；10 与默认 profile 一致，便于冒烟时行为可比。不要因为「H2 没有 max_connections」就放大池，放大只会增加锁竞争 | [S1][S7] |
| N-BE-030 | H2 `connection-timeout=5000ms` | 〔有据〕Hikari 缺省 30s；5s 与 PG profile 同值 | **【保留】** 5s；注释补「与 PG 同口径，便于冒烟复现」 | 当前无注释，容易被当成随手值；实际上它让 H2 冒烟与 PG 的失败形态一致（5s 而不是 30s）。与 N-BE-020 同值，建议共享注释 | [S1] |
| N-BE-031 | h2tcp `drain-window=5s` | 〔惯例〕HAProxy 常见 `inter 2s × fall 2 = 4s`；drain 应 ≥ 4s + 余量 | **【改值→6s】** | 5s 只有 1s 余量，代理侧一次抖动就可能看不到 503；6s 给 2s 余量，且 6+20+10=36s < 45s grace，仍安全。与 N-BE-024/027 的 10s 不同，是因为这里的代理参数更明确（2s×2），不需要覆盖 5s×2 | [S12][S16] |
| N-BE-032 | 默认 `drain-window=0s` | 〔有据〕单实例/无 LB 时不需要 drain；Spring Boot 默认 graceful 会立即关监听 socket | **【保留】** 0s；部署文档强调「多实例必须覆盖为 5–10s」 | 0 是「单实例行为不变」的正确缺省；风险在部署者忘记覆盖。与 N-BE-024/027/031 是同一个键的四种 profile 取值，建议在启动日志打印实际生效值 | [S3] |
| N-BE-033 | `probe-timeout=2s` | 〔有据〕K8s 探针 `timeoutSeconds` 缺省 1s；LB 常见 1–5s；2s 是留代理余量的折中 | **【保留】** 2s；内部再分层：DB 取连接 ≤1s、查询 ≤1s | 2s 是**所有维度共享**的总时限，不是每维度 2s；当前 DB 探针可能先花 5s 等连接，导致端点整体迟到。保留 2s 的前提是把「取连接」压到 1s 内，否则应把共享时限提到 3s。与 N-BE-020/034/037 联动 | [S12] |
| N-BE-034 | `probe-threads=4` | 〔无统一标准〕固定池 ≥ 维度数是常见做法，但没有官方推荐值；K8s 只规定探测超时，不规定应用侧线程 | **【改值→8】**（2× 维度数） | 4 恰好等于当前 4 个维度（db/storage/seed/redis），没有任何余量：任一维度阻塞在不可中断 I/O 上，其余维度仍能跑，但**新增第 5 个维度**就会排队等共享 2s。8 给一倍余量，代价是 4 条 daemon 线程。与 N-BE-035 一起定 | 算术 + [S12] |
| N-BE-035 | `probe-queue-capacity=8` | 〔无统一标准〕队列只需吸收「一次探测内的突发」；排队超过共享 deadline 的探测没有意义 | **【保留】** 8；注释写「4 线程 + 8 队列 ≈ 2 波，第二波大概率已过 2s」 | 队列 8 不会造成错误（饱和即 DOWN，语义明确），但它给不了「更多并发」——超过 2s 的排队结果只能记 DOWN。保留 8 是保守选择；若监控显示 saturated 频繁，应先加线程（N-BE-034）而不是加队列 | 算术 + [D] |
| N-BE-036 | `MAX_DRAIN_WINDOW=60s` | 〔有据〕K8s `terminationGracePeriodSeconds` 缺省 **30s**；drain + phase + await 必须小于 grace | **【改值→10s】** 上界改为 10s；文档写 `drain + phase + await + 5s ≤ grace`，并建议多实例 grace=45s | 60s 的上界允许一种**必然失败**的配置：drain 60 + phase 20 + await 10 = 90s，而 K8s 30s 就 SIGKILL。上界应是 `grace − phase − await − 余量`：grace 45、phase 20、await 10、余量 5 → 10s；若坚持 grace 30 **且 phase/await 不变**，上界为负，说明 phase/await 必须先降。P0 | [S12][S3] |
| N-BE-037 | `DbReadinessProbe.QUERY_TIMEOUT_SECONDS=2` | 〔有据〕健康查询常用 1–2s；K8s 探测 timeout 缺省 1s | **【保留】** 2s；但把「取连接」包在 1s 的有界 future 里 | `SELECT 1` 本身 2s 足够；问题是取连接可能 5s（N-BE-020），共享总时限只有 2s。保留查询 2s、压缩取连接 1s，才能保证端点按期返回。与 N-BE-033/034 是同一套预算 | [S12][S1] |
| N-BE-038 | 探针池 `keepAlive=30s` | 〔无统一标准〕线程池 keepAlive 常见 30–60s；`allowCoreThreadTimeOut(true)` 后低频探测不常驻 | **【保留】** 30s；注释写「LB 每 2–10s 探测时线程基本不会空闲到 30s」 | 30s 只影响「探测停了以后线程多久回收」，不影响探测延迟；与 N-BE-034 的线程数无关。若未来探测频率降到分钟级，30s 会让线程反复创建/销毁，可调到 60s | 算术 + [D] |
| N-BE-039 | `ShutdownDrainGate.SLICE_MS=100ms` | 〔无统一标准〕可中断 sleep 常见 50–200ms；越小信号响应越快，越大 CPU 唤醒越少 | **【保留】** 100ms；注释保留「切片是为了让第二次 Ctrl-C 及时打断」 | 100ms 的唤醒成本可忽略（10 次/秒），换来停机信号 100ms 级响应；不需要压测。与 N-BE-023/024 的秒级窗口相比，它只影响「响应灵敏度」，不影响总时长 | 算术 + [D] |

---

## 4. §1.2 作业 / 任务 / SSE / 线程池（N-BE-040…064）

| ID | 现值 | 业界口径与常见做法 | 建议 | 理由与联动 | 来源 |
|---|---|---|---|---|---|
| N-BE-040 | `app.task.sse.write-timeout=15s` | 〔无统一标准〕SSE 单帧写预算常见 5–30s，取决于帧大小与慢客户端比例；没有框架缺省 | **【保留】** 15s；把「256 线程 × 15s 最坏占用」列入压测项 | 源文档的实测是 64KB 帧 p99 1.15ms，15s 是四个数量级余量；这个论证成立。真正的风险不是 15s 太短，而是**太长**：256 条写出线程各卡 15s = 3,840 线程·秒，慢客户端多时会拖垮池。保留 15s，压测若显示饱和再降到 5s | [S20][D] |
| N-BE-041 | `app.task.sse.emitter-timeout=5m` | 〔有据〕`SseEmitter` 无参构造的 timeout 由 MVC/容器决定，不是固定 30s；〔惯例〕SSE 连接寿命 30s–5m + 自动重连 | **【保留】** 5m；必须补「≤20s 的心跳/注释帧」 | 5m 是任务通道自己的流寿命，与 AI 通道解耦，语义清楚。但 nginx `proxy_read_timeout` 缺省 60s：若 60s 内没有任何帧（作业安静期），反代会先断，5m 根本到不了。心跳间隔必须小于反代 idle 超时，建议 15–20s | [S16][S20] |
| N-BE-042 | `app.job.demo-batch-delay-ms=300` | 〔惯例〕生产作业不应有人为 sleep；演示/测试节拍应只在 dev/e2e profile 开启 | **【改值→0】** 生产默认 0；dev/e2e profile 保留 300（或 150） | 300ms/批 × 100 行 = 333 行/秒；100 万行要 50 分钟，刚好撞上 N-BE-047 的 30m 上限。把「测试可见性」放进产品默认值，是用生产吞吐换测试便利。CI 已覆写 150，说明这个值本来就该由 profile 管 | [D] |
| N-BE-043 | `app.job.batch-size=100` | 〔无统一标准〕批处理 chunk 常见 100–1000；Spring Batch 没有缺省 chunk size；POI SXSSF 窗口缺省 100 只是类比 | **【保留】** 100；注释补「100 × 单行写库/SSE 成本」的实测 | 100 行/批在内网小数据量下是合理起点：太小则 SSE/事务次数多，太大则单批延迟和回滚成本高。它与 N-BE-042 的组合才是问题（100 行 + 300ms = 333 行/s），先去掉 delay，再压测决定 100/500 | [S8][D] |
| N-BE-044 | `app.job.heartbeat-interval=30s` | 〔有据〕K8s 探针 `periodSeconds=10s × failureThreshold=3` ≈ 30s；〔惯例〕作业心跳 10–60s | **【保留】** 30s；保持「ticker 独立续期」不依赖业务分片 | 30s 与 K8s 的 30s 判死同量级，是「下沿」而不是拍脑袋；前提是 ticker 真的独立于业务线程（N-BE-051/064 已实现）。若 batch-size 调大导致单批 >30s，业务 beat 会迟到，但 ticker 仍能续期，所以不会误判 | [S12] |
| N-BE-045 | `app.job.watchdog.scan-period=10s` | 〔有据〕K8s 探针周期缺省 **10s**；〔惯例〕看门狗扫描 10–60s | **【保留】** 10s；补索引/查询计划核对 | 判死最坏发现 = 90s 判死窗 + 10s 扫描 = 100s，E2E 120s 窗口 = 100 + 20 余量，派生式成立。10s 扫描 = 8,640 次/天，对小表可接受；表变大后要确保 `status + updated_at` 有索引，否则扫描本身会成为负担 | [S12][D] |
| N-BE-046 | `app.job.watchdog.dead-multiplier=3` | 〔有据〕K8s `failureThreshold` 缺省 **3**；〔惯例〕3–3.5× 心跳是常见判死倍数 | **【保留】** 3；保持启动期 `heartbeat × multiplier < max-execution-time` 的 fail-fast | 3 是「容忍两次抖动、第三次判死」的标准做法；与 30s 心跳组合成 90s 判死窗。它必须小于 30m 上限，当前 90s << 30m，关系成立。不要单独改 3 而不改心跳，否则判死窗会漂移 | [S12] |
| N-BE-047 | `app.job.max-execution-time=30m` | 〔有据〕Hangfire `InvisibilityTimeout` 缺省 **30 分钟**；〔惯例〕批作业绝对上限 30–60m | **【保留】** 30m；用真实最大导出/导入实测校验 | 30m 是 Hangfire 缺省，也是「批处理下沿」；但本仓的导出是「全量读 + SXSSF 写」，100 万行在 333 行/s 下要 50m（去掉 300ms delay 后会快很多）。保留 30m 的前提是实测最大作业 < 25m；否则应调到 60m，并同步 N-BE-050 的 TTL | [S13][D] |
| N-BE-048 | `JobHeartbeatRegistry.DEFAULT_BEAT_TTL=90s` | 〔无统一标准〕这是「配置非法时的兜底」，正常值应由 `heartbeat × multiplier` 派生 | **【配置化】** 保留 90s 作 fallback，但正常路径从 `AppProperties` 派生，不复制字面量 | 90s 与 30s×3 同值但语义是「兜底」；两处独立字面量，改心跳时容易只改一处，导致「配置 60s×3=180s，fallback 仍 90s」的静默错配。fallback 可以留，但要在启动日志标注「使用了 fallback」 | 算术 + [D] |
| N-BE-049 | `RUNNER_ACTIVE_TTL_MARGIN=5m` | 〔无统一标准〕余量应覆盖「判死窗 + 收尾 + 时钟抖动」，没有官方值 | **【保留】** 5m；注释改成派生式「≥ 判死窗 90s + 停机 30s + 抖动」 | 5m 对 Redis 内存的成本可忽略（每作业一个键），保守一点没有坏处；问题是它现在像「拍出来的 5m」。写成派生式后，若判死窗调到 3m，余量自然要跟着重算。与 N-BE-050 的 TTL 直接相加 | 算术 + [D] |
| N-BE-050 | `runnerActiveTtlFor = 2×max-execution-time + 5m = 65m` | 〔无统一标准〕长 TTL 锚点必须严格大于作业上限；2× 是常见保守系数 | **【保留】** 65m；注释保留「2h → 4h5m」的示例 | 65m > 30m，删除闸门不会在作业还在跑时误删；代价是作业被删后键最多留 65m（Redis 内存 + 删除延迟）。2× 是保守的，若 Redis 内存紧张可降到 1.5×+5m（50m），但仍须 > 30m | 算术 + [D] |
| N-BE-051 | 进程级 ticker 周期 `max(1000ms, beatTtl/3)=30s` | 〔惯例〕租约续期常用 `TTL/3`（容忍两次续期失败）；〔无统一标准〕1000ms 下限 | **【保留】** 30s；注释写「ticker 周期必须 < 判死窗 90s」 | TTL/3 是分布式锁续期的经典比例：一次失败还有两次机会；30s < 90s，成立。1000ms 下限是保护「beatTtl 配得很小」的极端配置，保留。与 N-BE-044/046 的判死窗联动 | 算术 + [D] |
| N-BE-052 | `deadJudgeLease = beatTtl = 90s` | 〔无统一标准〕判死单飞租约常见 10–30s（配合续期）；租约越长，持有者崩溃后的接管越慢 | **【改值→30s】** | 90s 租约 + 90s 判死窗 + 10s 扫描 = 最坏 190s 才发现另一个实例该接管；30s 租约把最坏降到 130s，仍足够覆盖「写 issue + 终态」的一次操作。租约不需要等于判死窗：它保护的是「单次判死动作」，不是「整个判死窗」 | 算术 + [D] |
| N-BE-053 | `CANCEL_TTL=1h` | 〔无统一标准〕取消标志应覆盖「作业上限 + 收尾 + 取证」；没有官方值 | **【保留】** 1h；注释改成「≥ max-execution-time 30m + 收尾 5m + 取证余量」 | 1h 是 2× 上限，覆盖足够；成本是每个被取消作业留 1h 的键。写成派生式后，若上限调到 60m，1h 就不够了，必须跟着改。与 N-BE-047/050 是同一组时间窗 | 算术 + [D] |
| N-BE-054 | `SseWriteBudget.DEFAULT_TIMEOUT_MILLIS=15_000` | 〔无统一标准〕同 N-BE-040；两条通道共用一个事实源是好的 | **【保留】** 15s；保持单一事实源 | 15s 只在 `SseWriteBudget` 定义一次，AI/任务通道都引用它，这是正确的「单一事实源」。风险在「两通道负载差异变大」时无法分池/分额；类注已登记，暂不改值。与 N-BE-040/055/056 是一组 | [S20][D] |
| N-BE-055 | `DEFAULT_MAX_WRITER_THREADS=256` | 〔无统一标准〕写出线程数应按「并发慢连接数」定；256 不是任何官方推荐值 | **【改值→64】**（压测后定，64–128） | 256 的依据是「投递池 8 的 32 倍」，不是慢连接分布。按 p99 1.15ms 算，1,000 事件/s 只需约 2 条线程；真正占线程的是「停读客户端」，其数量应由压测的慢连接分布决定。64 已能覆盖内网并发，256 的栈/本地内存是纯浪费。P1 | 算术 + [D] |
| N-BE-056 | 写出池 `core=0; max=256; keepAlive=60s; SynchronousQueue(0); AbortPolicy` | 〔有据〕这正是 `Executors.newCachedThreadPool` 的形态：空闲 60s 回收、不排队、无界 max（此处加了 256 上限） | **【保留】** 形态不变；60s 与 JDK cached pool 对齐 | 「不排队 + 饱和即拒绝」是对的：排队只会把慢客户端变成延迟，不会提高吞吐；60s keepAlive 与 JDK 缺省一致。唯一要改的是 max（见 N-BE-055），不是形态。`AbortPolicy` 让饱和显式冒泡为 SATURATED，比 `CallerRuns` 更可控 | [S28] |
| N-BE-057 | `AsyncConfig.corePoolSize=4` | 〔无统一标准〕IO 密集型作业池 4–8 是常见起点；没有官方值 | **【保留】** 4；用真实并发实测校验 | 4 条核心线程给「一个任务中心点几下一键导出」够用，且只占 10 条连接中的 4 条，给 Web/健康检查留了余量。它与 max 8（N-BE-058 建议值）一起构成「4 常驻 + 4 弹性」，是合理的形状。不要因为「怕不够」把 core 也调到 8，常驻连接会被作业占满 | [S1][D] |
| N-BE-058 | `AsyncConfig.maxPoolSize=16` | 〔有据〕Hikari 池上限 10；〔惯例〕作业池上限应 ≤ 连接池，给 Web/探针留余量 | **【改值→8】** | 16 > 10 是明确的错配：最多 6 条作业线程拿不到连接，在 `connectionTimeout` 内排队；默认 profile 30s、PG 5s。改成 8 后，8 条作业 + 2 条 Web/探针 = 10，刚好用满且不排队。这是 P0 的容量链修复，必须与 N-BE-010/059 一起改 | [S1] |
| N-BE-059 | `AsyncConfig.queueCapacity=200` | 〔无统一标准〕有界队列常见 50–100；队列深度应等于「可接受的积压作业数」，不是越大越好 | **【改值→100】**（或 50） | 200 个排队作业在 4 条核心线程下，最坏等待 = 200/4 × 单作业时长；若单作业 5 分钟，就是 250 分钟，早已超过用户耐心和 30m 上限。改成 100 仍偏大，但配合 `AbortPolicy` 能更早拒绝；若产品允许「排队等一会」，50–100 是更诚实的边界。与 N-BE-058/060 联动 | 算术 + [D] |
| N-BE-060 | `AsyncConfig.awaitTerminationSeconds=30` | 〔有据〕Spring Boot 停机 phase 缺省 30s；PG profile 显式 **20s**；executor 的 await 在 Web phase 之后的 bean 销毁阶段，二者串行 | **【改值→10s】** | 当前 PG 串行上界 = 5s drain + 20s phase + 30s await = 55s，远超 K8s 默认 grace 30s；默认 profile 0+30+30 = 60s。改 10s 后 PG 为 40s（10s drain），仍需把 grace 提到 45s（或把 drain/phase/await 压到 5/12/8）。长作业本来就该由 `max-execution-time` 和取消标志处理，不该让停机等它。P0 | [S3] |
| N-BE-061 | `TaskSseService.REMOTE_SUBSCRIBE_RETRY_NANOS=10s` | 〔惯例〕跨实例订阅失败重试 1–5s + 抖动；重试间隔应接近「依赖恢复感知窗口」 | **【改值→5s】** 并加抖动 | 10s 会让 Redis 短暂抖动后的首个事件延迟 10s，前端会以为作业卡住；AI 面的 `NEGATIVE_TTL_MS=5s` 是「已知 DOWN 的重探窗口」，5s 与之对齐，语义一致。加抖动避免所有实例同时重试。与 N-BE-089 联动 | [D] |
| N-BE-062 | `TaskService.CREATE_LOCKS=64` | 〔无统一标准〕哈希分片锁常见 16–256；分片越多，冲突越少、内存越大 | **【保留】** 64；注释写「冲突只串行化、不会错，64 远大于并发创建数」 | 64 个 `ReentrantLock` 的成本可忽略；按 run/key 哈希后，只有同槽才串行。它的作用是「防同键并发创建」，不是吞吐限制；64 足够，不需要压测。若未来并发创建上千，再考虑 256 | 算术 + [D] |
| N-BE-063 | `JobWatchdog` 单线程调度器 | 〔惯例〕观察者用独立单线程，避免与被观察者共享池导致互相饿死 | **【保留】** 1 线程；保留 Throwable 兜底 | 单线程扫描是「扫描慢」时的天然背压：不会因为扫描并发而放大 DB 压力；独立调度器避免作业池饱和时看门狗也停摆。前提是单轮扫描不因单个作业异常而停摆（已有 Throwable 兜底），这一点要保留 | [D] |
| N-BE-064 | `JobHeartbeatRegistry` 单线程 ticker | 〔惯例〕心跳续期用单线程定时器；只做 Redis SET，吞吐足够 | **【保留】** 1 线程；runner 数很大时改成批量 SET | ticker 只做「遍历在跑 runner + SET 续期」，单线程在数十到数百 runner 下没问题；懒启动/空闲停止也合理。若 runner 数到数千，单线程 + 逐键 SET 会成为瓶颈，届时应批量/pipeline，而不是加线程（加线程会让续期顺序更难保证） | [D] |

---

## 5. §1.3 分页 / 上限 / 缓冲 / Excel / 文件 / Flyway / 安全（N-BE-065…080）

| ID | 现值 | 业界口径与常见做法 | 建议 | 理由与联动 | 来源 |
|---|---|---|---|---|---|
| N-BE-065 | `TaskController` `page=0; size=10` | 〔有据〕GitHub 缺省 **30**、Stripe 缺省 **10**、Spring Boot `pageable` 缺省 **20**；10 在主流区间下沿 | **【改值→20】** 与另外两个列表统一 | 10 对「历史任务列表」偏小：任务多时首屏就要翻页；20 与 Spring 缺省、数据列表（N-BE-067）一致，前端 `stores/task.ts` 同步改。改它是纯产品口径统一，不涉及容量风险。与 N-BE-066/067 是一组 | [S9][S10][S11] |
| N-BE-066 | `JobController` `page=0; size=50` | 〔有据〕GitHub 缺省 30、上限 100；50 高于主流缺省，接近「大页」 | **【改值→20】** 缺省 20；保留 50 作为前端可选页大小 | 问题列表默认 50 会让首屏一次渲染 50 条 issue（每条 message 可达 1024 字符），前端渲染与网络都更重；20 是更常见的缺省。50 仍有价值（用户主动选「50/页」），但不应是默认。与 N-BE-065/067/068 联动 | [S9] |
| N-BE-067 | `DataController` `page=0; size=20` | 〔有据〕Spring Boot `pageable` 缺省 **20**；GitHub 30、Stripe 10；20 是这三者的中位 | **【保留】** 20；把「20/50/100 选项 + 后端上限 100」落到实现 | 20 是唯一有「框架缺省」背书的取值，应作为三处统一后的基准值。当前注释已引 GitHub/Stripe，但「上限 100」只是推荐、未实现（见 N-BE-068）。保留 20，补齐上限即可 | [S11][S9] |
| N-BE-068 | `DataController` 对 `size` **无 clamp、无校验** | 〔有据〕GitHub 对超上限 `per_page` **不报错、自动降到 100**；Spring Boot `max-page-size` 缺省 2000 也是「夹取」语义 | **【改值→100（clamp）】** 显式 `Math.min(size,100)`；响应回显实际 `size`；前端选项 20/50/100 | 当前 `size=999999` 会直通 `PageRequest.of`，可能拉全表/大结果集，是性能与产品边界的双重缺口。两种主流做法：静默 clamp（GitHub/Spring）或 400（显式拒绝）。选 clamp 与 Spring `max-page-size` 语义一致，且不破坏现有调用方；但必须在响应里回显实际 size，否则客户端会「以为拿了 999999」。P0 | [S9][S11] |
| N-BE-069 | `ValidationIssue.message` `@Column(length=1024)` | 〔惯例〕错误消息列常见 255/1000/2000/4000；1024 足以放类名 + 摘要 + 截断标记 | **【保留】** 1024；与 N-BE-070 建立单一常量/启动期对账 | 1024 字符（PG 按字符计，不是字节）对「异常类名 + message + 标记」足够；真正的问题是两处独立 1024（实体注解 + `ISSUE_MESSAGE_MAX`），改一处会静默截断或 INSERT 失败。保留值，改事实源。与 N-BE-070/104 联动 | [S22][D] |
| N-BE-070 | `ExportJobRunner.ISSUE_MESSAGE_MAX=1024` | 〔惯例〕同 N-BE-069；截断应保留可读前缀 + 明确标记 | **【保留】** 1024；从列宽/共享常量派生，不复制字面量 | 它是「写库前的保护」，值必须 ≤ 列宽；当前等于列宽是正确的。若未来列宽改成 2048，这个常量必须同步，否则会浪费一半空间；反过来常量变小会静默截断。与 N-BE-069/104 是同一事实源 | [D] |
| N-BE-071 | `FileStorageService.DIGEST_BUFFER_BYTES=8192` | 〔惯例〕顺序读常见 8KB 缓冲；JDK `BufferedInputStream` 无参构造的实现缺省也是 8192 字节（javadoc 未写明，属实现常量） | **【保留】** 8KB；注释补「共享卷延迟高时可评估 32–64KB」 | 8KB 与 JDK 缺省一致，内存占用可忽略；SHA-256 是顺序读，8KB 不会成为瓶颈。只有在高延迟共享卷上，增大缓冲才可能减少 syscall；没有实测前不改。与 N-BE-072 同属文件面 | [D] |
| N-BE-072 | `SAFE_EXTENSION` 正则 `{1,10}` | 〔惯例〕扩展名 allowlist 1–5 字符最常见；OWASP 要求「允许列表 + 服务端生成文件名」 | **【保留】** 1–10；保持「只允许 ASCII 字母数字」 | 10 字符覆盖 `xlsx/docx/properties` 等；真正的安全来自「服务端 UUID 文件名 + 扩展名 allowlist」，不是长度本身。10 比 5 宽，但仍是形状约束，不放宽字符集。与 N-BE-080 的 holder 截断同属「防拼接」 | [S17] |
| N-BE-073 | `new SXSSFWorkbook(100)` | 〔有据〕POI SXSSF 窗口缺省 **100**；窗口越小内存越低、临时文件 IO 越多；POI 明确警告临时文件可能远大于输入（20MB CSV → >1GB XML） | **【保留】** 100；补 `setCompressTempFiles(true)` 与 `dispose()` 的确认 | 100 就是 POI 缺省，不是拍脑袋；对本仓「配置数据导出」这个量级足够。真正的风险在临时文件：100 行窗口会把其余行刷到磁盘，导出百万行时临时 XML 可能上 GB；必须确认压缩临时文件与 `dispose()` 都已做。与 N-BE-074 同属 Excel 面 | [S8] |
| N-BE-074 | `CellRangeAddressList(1, 1000, c, c)` | 〔有据〕Excel 单表上限 **1,048,576 行**；〔无统一标准〕下拉覆盖多少行没有业界值，应由产品最大导入行数决定 | **【需裁决】** 先定「产品最大导入行数」，再决定 1000 是否等于它；若不定，明确「下拉仅示例、校验在服务端」 | 1000 与任何产品上限都没有对照：超过 1000 行的导入仍可导入，但没有下拉约束；用户会以为「第 1001 行不受控」。两条路：① 把 1000 改成产品上限（如 5000/10000，仍远小于 Excel 上限）；② 保留 1000，但在模板说明与后端校验里明确「下拉只是示例」。这不是纯技术值，需产品定 | [S21] |
| N-BE-075 | `Math.min(12, sha.length())` / `substring(0,12)` | 〔惯例〕Git 短 SHA 常见 7–12 位；12 位 = 48 bit，碰撞概率极低 | **【保留】** 12；仅用于文案/日志，不参与比对 | 12 位十六进制在「人类可读」和「足够区分」之间是常见折中；它只用于错误文案，真正的冲突判定用完整 SHA。若前端要展示完整 SHA，应另给字段，而不是把这里改成完整值 | 算术 + [D] |
| N-BE-076 | `FlywayStartupLock.WAIT_TIMEOUT=60s` | 〔有据〕Flyway `lockRetryCount` 缺省 **50**（≈50 次重试，量级 50s）；〔惯例〕启动锁要覆盖一轮冷启动迁移 | **【配置化】** 保留 60s，但做成配置键（如 `app.flyway.lock-wait`），慢库可调 120s | 60s 与 Flyway 自身 50 次重试同量级，不是随手值；但「一轮冷启动迁移」在慢库/大表上可能 >60s，写死会导致启动失败。配置化后，默认 60s 不变，部署面可按迁移时长调。与 N-BE-022/028 的三个 60s 不是同一机制，不能互相替代 | [S19] |
| N-BE-077 | `connection.isValid(2)` | 〔有据〕JDBC `isValid(timeout)` 的超时单位是秒；1–5s 是常见校验值 | **【保留】** 2s；注释写「这是驱动校验超时，不是业务等待」 | 2s 足够判定连接是否可用；它不参与业务 SQL 的等待，也不等于 Hikari 的 `validationTimeout`。保留 2s，避免被误读为「迁移后只等 2s」。与 N-BE-021 的 2s 同值不同层 | 算术 + [D] |
| N-BE-078 | `ClientIpResolver.MAX_CHAIN_LENGTH=16` | 〔惯例〕真实代理层数通常 1–3；XFF 链越长，伪造面越大；16 远超常见拓扑 | **【改值→8】** | 16 跳等于允许攻击者塞 15 个伪造地址；虽然代码只从右往左取第一个不可信地址，链长上限仍是「异常检测」的一部分。8 跳覆盖「CDN + LB + 网关 + 侧车」的极端拓扑，同时把伪造面减半。与 `trusted-proxies` 配置配合，不改变解析算法 | [D] |
| N-BE-079 | `WebConfig.setMaxAge(3600L)` | 〔有据〕`Access-Control-Max-Age` 缺省 **5s**；Chrome 上限 **7200s**、Firefox 上限 **86400s**；3600s 在两者之下 | **【保留】** 3600s；注释写「预检缓存 1h，与 i18n 缓存无关」 | 1h 是常见折中：减少预检请求，又不会让 CORS 策略变更延迟一天；浏览器会自行截断到上限，3600 不会被拒。开发期 `localhost:*` 允许源变更时，最多等 1h 或清缓存；这个运维含义要写清。与 N-BE-003 同值不同义 | [S15] |
| N-BE-080 | `SeedLock` holder 截断 `length() > 128` | 〔有据〕`VARCHAR(128)` 列宽；holder 只作观测，不参与判定 | **【保留】** 128；与 V7 列宽建立同源说明 | 典型 holder = 主机名 + 进程 + UUID，约 40–60 字符，128 足够；截断只影响观测可读性，不影响锁判定。真正要防的是「列宽改小而截断逻辑没改」，建议启动期/测试期对账。与 N-BE-105 联动 | [S22][D] |

---

## 6. 附录 A：协议 / 固定值 / 表结构 / 跨域引用（N-BE-081…091）

| ID | 现值 | 业界口径与常见做法 | 建议 | 理由与联动 | 来源 |
|---|---|---|---|---|---|
| N-BE-081 | `spring.data.redis.port=6379` | 〔惯例〕Redis 默认监听 **6379**（配置示例与客户端默认都按此）；生产应由 secret/env 覆盖，端口不是安全边界 | **【保留】** 6379；生产用 `SPRING_DATA_REDIS_PORT` 或连接串覆盖 | 6379 是 Redis 的默认端口，写出来只是让本地开箱即用；它没有容量含义。真正的风险是「以为改了端口就安全」——Redis 的访问控制靠 ACL/TLS/网络隔离，不是端口。与 N-BE-001 同属部署面 | [S25][D] |
| N-BE-082 | H2 URL `DB_CLOSE_DELAY=-1` | 〔有据〕H2 的 `-1` = JVM 存活期间不关闭数据库；这是 H2 文件库的常见开发配置 | **【保留】** -1；注释写「仅 H2 文件库，生产 PG 不用」 | `-1` 让最后一个连接关闭后数据库仍存活，避免内存表/文件库被意外关闭；代价是 JVM 退出前不释放文件锁，不适合多进程共享（多实例用 h2tcp）。与 N-BE-028/029 同属 H2 profile | [S7] |
| N-BE-083 | `PG_LOCK_KEY=20261025L` | 〔惯例〕advisory lock 的应用级键只需「本应用全局唯一、跨版本稳定」；PG 的 session 级 advisory lock 在会话结束时自动清理 | **【保留】** 20261025L；注释写「改它 = 换锁命名空间，滚动升级会双锁并存」 | 这个值本身没有对错，关键是稳定性：滚动升级时新旧实例必须用同一个键，否则两边各拿一把锁，迁移会并发。日期形只是可读性。若用 session 级锁，连接断开即释放；若用 transaction 级，则随事务结束释放——两种语义不能混用。与 N-BE-076 的 Flyway 启动锁是两层锁，不能合并 | [S30] |
| N-BE-084 | 过滤器顺序 `HIGHEST_PRECEDENCE + 5/+10/+20` | 〔惯例〕CORS 必须最先、身份解析最后；中间留 5/10 的间隔便于插入新过滤器 | **【保留】** +5/+10/+20；新增过滤器按「CORS 最先、身份最后」插入中间值 | 这是相对次序，不是等待/容量值；5/10/20 的间隔让后续插入不用重排。真正的约束是「CORS 必须在拒绝响应之前」，否则 401/403 响应会缺 CORS 头，前端只看到网络错误。与 N-BE-079 的预检缓存联动 | [D] |
| N-BE-085 | SQLState/vendor `23506` / `1452` | 〔标准〕PG 的外键/唯一/CHECK 分别是 `23503`/`23505`/`23514`；`1452` 是 MySQL 的外键码；`23506` **不是 PG 原生码**，更像 H2 的引用完整性码（需按 H2 版本文档核对） | **【保留】** 两码；但注释要写清「23506 主要覆盖 H2 路径，PG 路径必须补 23503」 | 当前只映射 `23506`/`1452`：`1452` 是 MySQL 兼容码，`23506` 不是 PG 原生外键码；**PG 原生外键冲突是 23503**，若生产走 PG，现有映射会漏掉。建议补 `23503`、`23505`、`23514` 的映射，而不是改这两个值。与 N-BE-106 新增 CHECK 后产生的 `23514` 联动 | [S29] |
| N-BE-086 | 列宽：`code 64 / name 128 / row_key 256 / message 1024 / storage_path 512` 等 | 〔有据〕PG `varchar(n)` 按**字符**计，三种字符类型无性能差异；官方建议「不想设上限就用 text，不要随便编长度」 | **【需压测】** 保留多数宽度；实测 `row_key` 复合键与 `storage_path` 最长值，逼近 256/512 就加宽 | 64/128 对 code/name 足够；`row_key` 是「多主键拼接」，业务键变长时最容易溢出，且溢出是 INSERT 失败（不是截断），必须实测。`storage_path` 目前存 UUID 相对键（约 40 字符），512 很宽；若未来存绝对路径，Windows 长路径可能超 260。`message 1024` 与 N-BE-069/070 对齐即可 | [S22] |
| N-BE-087 | `UUID.substring(0,6)` 实例后缀 | 〔无统一标准〕6 位十六进制 = 24 bit = 16,777,216 种；100 个实例的碰撞概率约 **0.03%**（生日近似） | **【保留】** 6；注释写「仅日志/标识后缀，不参与判定」 | 它只用于把 `runner-...`/`taskbus-...` 区分开，不是唯一键；0.03% 的碰撞在「同一主机多进程」场景可接受。若未来拿它做路由/去重键，必须加到 8–12 位。与 N-BE-083 同属「标识稳定性」 | 算术 + [D] |
| N-BE-088 | `aiStreamWatchdog` `newScheduledThreadPool(2)` | 〔无统一标准〕调度器线程数应按任务数/阻塞特性定；注释说「单条守护线程」但代码是 2，属注释与实现偏差 | **【需裁决】** 由 AI 面 owner 定 1 还是 2；任务 SSE 的 10s 重试建议独立 1 线程调度器 | 这是跨域引用：非 AI 的 `TaskSseService` 复用 AI 调度器做重试，AI 流式看门狗也用它；两者负载耦合。注释写 1、代码 2，说明至少有一处没对齐。纯技术上看，任务重试只需 1 条低频线程，独立出来更清晰；但这是 AI 面 owner 的键，需裁决 | [D] |
| N-BE-089 | `RedisAvailability.NEGATIVE_TTL_MS=5000` | 〔惯例〕熔断/负缓存 5–30s；5s 是「快速重探」档 | **【保留】** 5s；在任务 SSE 文档明示「依赖 AI 面 5s 负缓存」 | 5s 让 Redis 恢复后最多 5s 重新探测，代价是 DOWN 期间每 5s 一次探测；对任务 SSE 的降级窗口刚好。它是 AI 面常量、非 AI 面消费，属于跨域耦合，必须文档化，避免两侧调参互不知情。与 N-BE-061 的 5s 重试对齐 | [D] |
| N-BE-090 | Excel 列宽 `20×256`、批注锚点 `c+2` 列/`4` 行 | 〔有据〕POI 列宽单位是 **1/256 字符**；批注锚点单位是列/行 | **【保留】** 20 字符宽、c+2/4；归入「布局/样式数字」，不参与容量裁决 | 这些是纯展示值，不影响内存/吞吐；20 字符宽对大多数配置字段名够用，批注跨 2 列 4 行也合理。不要把它们和 N-BE-073 的窗口、N-BE-074 的校验行混为一谈 | [S8] |
| N-BE-091 | `spring.jackson.time-zone=GMT+8` / `TimeZone Asia/Shanghai` | 〔无统一标准〕时区应单一事实源；`GMT+8` 与 `Asia/Shanghai` 在无夏令时的中国等价，但双写会漂移 | **【配置化】** 只保留一处（建议 yml `Asia/Shanghai`），删除 Java 侧硬编码或改为引用同一配置 | 中国固定 UTC+8、无夏令时，两个值当前等价；但「GMT+8」是固定偏移，「Asia/Shanghai」是区域规则，未来若规则变化（或部署到其他时区）会分叉。双写没有一致性守卫，属于典型的「同值不同源」。与 N-BE-003 的缓存无关 | 算术 + [D] |

---

## 7. 附录 B：业务库 DDL 数值约束（N-BE-092…106）

> 口径：H2/PG 两份 V1 数值逐项相同，本文只按「表」判断，不重复两份行号；列宽在 PG 中按**字符**计（[S22]），不是字节。

| ID | 现值 | 业界口径与常见做法 | 建议 | 理由与联动 | 来源 |
|---|---|---|---|---|---|
| N-BE-092 | `regions`：`code 32 / name 64 / id BIGINT` | 〔惯例〕行政区/地区编码常见 2–12 字符（ISO 3166 alpha-2/3、GB/T 2260 六位数字）；名称 64 足够 | **【保留】** 32/64；测试期做 DDL↔实体列宽对账 | `code 32` 对任何主流地区编码都绰绰有余；`name 64` 对「省/市/区」名称足够，超长名称应在业务层拒绝而不是靠截断。`BIGINT` 主键是标准做法。与 N-BE-093 的 `region_code 32` 必须同宽 | [S22] |
| N-BE-093 | `projects`：`code 32 / name 64 / region_code 32 / id BIGINT` | 〔惯例〕项目编码 16–32、名称 64–128；`region_code` 必须与被引用表同宽 | **【保留】** 32/64/32；`region_code` 与 `regions.code` 锁同宽 | 项目名称 64 对中文（每字 1 字符）约 64 字，通常够；若产品允许长项目名，应在 API 层 `@Size(max=64)` 明确拒绝，而不是让 DB 抛 `value too long`。`region_code 32` 与 N-BE-092 同宽是外键一致性的前提。与 N-BE-099 的 title 宽度是两类字段 | [S22] |
| N-BE-094 | `config_definitions`：`code 64 / name 128 / level 16 / description 512 / sort_order INT DEFAULT 0` | 〔惯例〕配置编码 32–64、名称 128、描述 512–2000；`sort_order=0` 是稳定排序的常见初值 | **【保留】** 全部；业务确认 `description 512` 是否覆盖最长说明 | `code 64` 与字段编码正则同量级；`level 16` 容纳 `GLOBAL/PROJECT/...` 足够；`description 512` 是唯一可能偏紧的（长说明/富文本），但改列宽要走迁移，先用业务样例验证。`sort_order=0` 让 `ORDER BY sort_order, code` 稳定，保留。与 N-BE-095 的 `def_code 64` 同宽 | [S22] |
| N-BE-095 | `config_fields`：`def_code 64 / code 64 / label 128 / field_type 16 / ref_def_code 64 / ref_field_code 64 / sort_order 0` | 〔惯例〕字段编码 64、标签 128、类型 16；引用编码与被引用表同宽 | **【保留】** 全部；`code 64` 与 `DefinitionService` 的编码正则对齐 | `def_code/ref_def_code` 都是 64，与被引用表 `config_definitions.code 64` 一致，外键不会因宽度不一致而失败；`field_type 16` 容纳枚举足够；`label 128` 对中文字段名足够。`sort_order=0` 保留。与 N-BE-090 的 Excel 列宽无关（一个是 DB，一个是展示） | [S22] |
| N-BE-096 | `config_dependencies`：`from_def_code 64 / to_def_code 64 / id BIGINT` | 〔惯例〕依赖边表只存两个 code + 主键；边数由定义数约束，不设行数上限 | **【保留】** 64/64；补唯一约束（from,to）防重复边 | 64 与定义表同宽，正确；表本身没有「行数上限」是正常的——依赖图的边数天然受定义数约束（n 个定义最多 n² 条边，实际远小）。真正要补的是 `UNIQUE(from_def_code,to_def_code)`，防止重复边让拓扑排序重复计数；这不是数值改动。与 N-BE-094/095 的 code 宽度联动 | [S22] |
| N-BE-097 | `config_data_rows`：`def_code 64 / scope_type 16 / scope_key 64 / row_key 256 / version BIGINT DEFAULT 1` | 〔惯例〕业务复合键列 128–512；`version=1` 是 JPA 乐观锁初值的标准做法 | **【需压测】** 实测最长 `row_key`（多主键拼接）；逼近 256 就加宽到 512 | `row_key` 是「业务复合键」的拼接，最容易随字段增多而溢出；溢出是 INSERT 失败，不是截断，且发生在导入/发布路径，影响面大。`version=1` 与 `@Version` 初值一致，保留。`scope_key 64` 对 GLOBAL/PROJECT/REGION 的键够用。与 N-BE-098 的 `row_key` 同宽，必须一起改 | [S22] |
| N-BE-098 | `config_staging_rows`：`def_code 64 / op_type 16 / row_key 256 / scope_type 16 / scope_key 64 / status 16`；`base_version BIGINT NULL` | 〔惯例〕暂存表与正式表同宽；可空 `base_version` 表示「新增/历史行」是常见做法 | **【保留】** 列宽；文档明确 `base_version=NULL` 的两种语义 | 列宽与 N-BE-097 对齐，正确；`base_version` 可空不是数值问题，而是语义问题：NULL 既可能表示「新增行」，也可能表示「历史行未记录」，发布冲突检测必须能区分。若无法区分，应加 `op_type` 判据或独立标志，而不是改类型。与 N-BE-097 的 row_key 实测联动 | [S22] |
| N-BE-099 | `tasks`：`type 32 / title 256 / current_step 64 / status 32 / version BIGINT DEFAULT 1` | 〔有据〕Excel/文本标题常见 100–200；前端当前限制 60（更严）；`version=1` 标准 | **【配置化】** DDL 保留 256；API 层加 `@Size(max=100)`，前后端统一到 100 | 后端 256、前端 60 是「前端更严」的不对称：直连 API 可以写 256 字符标题，前端只能 60，同一资源两套边界。建议 DDL 不动（256 够宽），在 API 层统一 100（或 60），让「真正生效的上限」只有一处。`current_step 64` 对步骤名足够；`status 32` 容纳长枚举。与 N-BE-093 的 name 64 是不同字段 | [S22] |
| N-BE-100 | `task_items`：`def_code 64 / status 32 / sort_order INT DEFAULT 0` | 〔惯例〕`sort_order=0` 是稳定排序初值；`condition_json` 属 CLOB/TEXT，无长度约束 | **【保留】** 列宽与 `sort_order=0`；给 `condition_json` 加应用层上限（如 64KB） | `sort_order=0` 让 `ORDER BY sort_order` 稳定；`status 32` 足够。真正无界的是 `condition_json`：DDL 不约束，应用层也不截断，恶意/误操作可以写入超大 JSON。建议在序列化前限制 64KB（具体值按最长合法条件定），而不是改列类型。与 N-BE-106 的「JSON 内数值无约束」同源 | [S22] |
| N-BE-101 | `task_files`：`def_code 64 / file_type 32 / storage_path 512 / original_path 512 / file_name 256 / version BIGINT DEFAULT 1 / row_count INT NULL` | 〔有据〕Excel 单表上限 **1,048,576 行** < `INT` 上限 **2,147,483,647**；Excel 文件名（含路径）上限 218 字符 | **【保留】** 全部；`row_count INT` 对 Excel 行数足够 | `row_count` 用 `INT` 不是短板：即使导出 Excel 最大行数 1,048,576，也远小于 2.1B；只有「非 Excel 的超大导出」才需要 BIGINT。`file_name 256` > Excel 的 218 字符文件名上限，够用；`storage_path 512` 对 UUID 相对键很宽，对绝对路径也够（Windows 长路径除外）。`version=1` 标准。与 N-BE-099 的 version 同源 | [S21][S23] |
| N-BE-102 | `jobs`：`progress / total / error_count / warning_count` 均 `INT NOT NULL DEFAULT 0` | 〔惯例〕计数列应加 `CHECK (>=0)`；`INT` 对作业计数足够（2.1B） | **【改值→加 `CHECK (>=0)`】** 四个列都加；应用层同时断言 | 当前 DDL 允许负数：`error_count=-1` 会让「预检查守卫按 error_count>0 判失败」误判为通过；`progress` 负数会让前端进度条异常。加 CHECK 是数据库层兜底，应用层断言是早失败，两者都要。`INT` 不需要改 BIGINT。P1 | [S23][S5] |
| N-BE-103 | `job_items`：`processed / total` 均 `INT NOT NULL DEFAULT 0` | 〔惯例〕进度列加 `CHECK (processed >= 0 AND total >= 0)`；更强的 `processed <= total` 可选 | **【改值→加 `CHECK (>=0)`】**；若业务保证单调，再加 `processed <= total` | 与 N-BE-102 同因：负进度在 DDL 层合法，前端/看门狗读到会异常。`processed <= total` 需要确认「total 是否可能先为 0 后更新」，否则会误伤；先加非负，单调性由应用层保证。`INT` 足够。与 N-BE-102 同批迁移 | [S23] |
| N-BE-104 | `validation_issues`：`def_code 64 / row_key 256 / field_code 64 / severity 16 / message 1024 / row_index INT` | 〔有据〕Excel 行号上限 1,048,576 < INT；〔惯例〕message 1024 够放异常摘要 | **【保留】** 列宽与 `INT`；明确 `row_index` 是 1-based（含表头偏移） | `row_index INT` 对 Excel 行号足够，不需要 BIGINT；`message 1024` 与 N-BE-069/070 对齐。真正要补的是语义：`row_index` 是 1-based 还是 0-based、是否含表头，前端/导出/测试必须一致；否则「第 3 行」会指到不同数据。与 N-BE-070 的截断标记联动 | [S21][S22] |
| N-BE-105 | `app_lock`：`lock_name 64 / holder 128` | 〔惯例〕锁名 ≤64、holder ≤128；holder 只作观测 | **【保留】** 64/128；新增锁名时确认 ≤64；两份 V7 保持同字节 | `lock_name 64` 对 `seed` 这类短名足够；`holder 128` 与 N-BE-080 的截断一致。风险是「新增锁名超过 64」被 DB 拒绝，或两份 V7 不同步导致 H2/PG 行为分叉；用测试守同字节即可。与 N-BE-080 是同一条事实链 | [S22] |
| N-BE-106 | 现役非 AI 表：`INT` 11 列、`BIGINT` 23 列、`VARCHAR` 49 列、`CHAR 0`、`NUMERIC/DECIMAL 0`、`CHECK 0` | 〔有据〕PG `numeric` 精确、推荐用于金额；`integer` 上限 2.1B；〔无统一标准〕金额精度（19,4 / 38,10）无官方统一值 | **【需裁决】** ① 进度/计数先加 `CHECK (>=0)`；② 需要求和/筛选的金额落 `NUMERIC(19,4)`；③ 否则应用层统一 `BigDecimal` 标度并在 JSON Schema 校验 | 这是系统性缺口，不是单个宽度：`CHECK=0` 让负进度合法；`NUMERIC=0` 让金额/汇率/税率完全依赖 JSON 文本，DB 无法求和、无法比较、无法兜底精度。两条路：① 保持 JSON，但应用层强制 `BigDecimal` + 标度（如金额 4 位、汇率 10 位）+ 范围校验；② 把需要查询/汇总的金额抽成 `NUMERIC(19,4)` 列。选哪条取决于产品是否要对金额做 DB 侧聚合，需架构裁决。与 N-BE-102/103 的 CHECK 可先做 | [S23][S22] |

---

## 8. 关键联动与口径纠错

### 8.1 容量链：先算不等式，再谈调参

当前（默认 profile）：`Hikari max=10`（N-BE-009）、`Async max=16`（N-BE-058）、`Tomcat maxThreads=200`（N-BE-014）、`Hikari connectionTimeout=30s`（N-BE-010）。

```
可用给作业的连接 = Hikari max − Web/探针预留 = 10 − 2 = 8
Async max 必须 ≤ 8          → 16 不满足，最多 6 条作业线程等连接
单次等连接上界 = connectionTimeout = 30s（默认）/ 5s（PG、h2tcp）
最坏线程·秒 = 6 × 30 = 180s（默认） / 6 × 5 = 30s（PG）
PG 侧总连接 = 实例数 × 10 + superuser 预留 3 = 3×10+3 = 33 < max_connections 100
```

**结论**：Hikari 10 保留（官方缺省 + 官方反对放大）；Tomcat 200 保留（async 释放线程，不是瓶颈）；**Async max 16→8**；**默认 profile connectionTimeout 30s→5s**；**validationTimeout 5s→2s**（必须 < connectionTimeout）。这四个数是一组，不能只改一个。

### 8.2 停机链：三层窗口必须逐层变小

| Profile | drain（现值→建议） | phase（现值→建议） | Async await（建议） | 串行合计 | K8s grace（建议） | 判定 |
|---|---|---|---|---|---|---|
| 默认 | 0s → 0s | 30s → 20s | 10s | 30s | 45s | 留 15s 余量 |
| postgresql | 5s → 10s | 20s → 20s | 10s | 40s | 45s | 留 5s 余量 |
| cluster | 5s → 10s | 30s（未显式）→ 20s | 10s | 40s | 45s | 留 5s 余量 |
| h2tcp | 5s → 6s | 30s（未显式）→ 20s | 10s | 36s | 45s | 留 9s 余量 |

```
drain + timeout-per-shutdown-phase + Async.awaitTerminationSeconds + 5s ≤ terminationGracePeriodSeconds
若 grace 必须保持 30s：drain ≤5s、phase ≤12s、await ≤8s（5+12+8+5=30）
drain ≤ MAX_DRAIN_WINDOW（建议 10s，不是 60s）
```

**结论**：`MAX_DRAIN_WINDOW 60s` 是「允许配出必死组合」的护栏，必须降到 10s（N-BE-036）；`awaitTermination 30s` 让 PG 串行上界到 55s、默认到 60s，必须降到 10s（N-BE-060）；默认 profile 的 30s phase 建议降到 20s（N-BE-018）。这三项改完后，多实例仍需把 `terminationGracePeriodSeconds` 从默认 30s 提到 **45s**；若部署面不允许改 grace，则只能把 phase 压到 12s、await 压到 8s、drain 压到 5s（总 30s，零余量，不推荐）。

### 8.3 判死 / 租约：检测延迟是可算的

```
判死窗 = heartbeat 30s × dead-multiplier 3 = 90s
最坏发现 = 判死窗 + scan-period 10s = 100s
最坏接管 = 发现 + deadJudgeLease（现 90s → 建议 30s）= 190s → 130s
E2E 窗口 120s = 100s + 20s 余量（成立）
```

**结论**：心跳 30s、倍数 3、扫描 10s 都保留（K8s 的 10s×3、period 10s 是同源依据）；**判死租约 90s→30s**，把「持有者崩溃后的接管」从 190s 压到 130s，仍覆盖一次 issue + 终态写入。

### 8.4 分页 / 上传 / SSE 的三条边界

```
分页：1 ≤ size ≤ 100；缺省 20；超限静默 clamp 并在响应回显实际 size
上传：单文件 5MB、整包 6MB；解压后字节 ≤ 堆预算；数据行 ≤ max_import_rows
SSE：心跳 ≤ 20s < nginx proxy_read_timeout 60s；writer_threads ≥ 峰值停读客户端 + 2
```

**分页**：10/50/20 三套缺省统一为 20，上限 100（GitHub 的 clamp 语义）；`size=999999` 必须被夹取（N-BE-068）。
**上传**：5MB/6MB 保留，但 ZIP 的「每 entry `readAllBytes()`」与 Excel 的「`XSSFWorkbook` 全量载入」必须先加解压后上限；OWASP 明确要求按解压后大小设限。
**SSE**：15s 写预算保留（有 p99 依据）；5m emitter 保留但必须配心跳；256 写出线程改为 64（压测后定）。

### 8.5 与源文档的 9 处口径纠错

| # | 源文档说法 | 复核结论 | 影响 |
|---|---|---|---|
| 1 | N-BE-018「默认 profile 未开 graceful，30s 不生效」 | Spring Boot **3.4+ graceful 默认开启**，3.5 文档明确；3.5.14 下 30s **应为生效**（需启动确认） | 默认 profile 停机预算从「不生效」变为 0+30+10=40s，超过 30s grace；必须先纠错再调 |
| 2 | N-BE-010/011 各自独立看 | `validationTimeout` **必须小于** `connectionTimeout`；若 010 改 5s，011 不能留 5s | 两个值是一组，011 必须 ≤2s |
| 3 | N-BE-012「idleTimeout 10m 回收空闲连接」 | `minimumIdle` 缺省 = max，**idleTimeout 不生效** | 固定池不缩容；不要指望 10m 后连接减少 |
| 4 | N-BE-036「60s 上界合理」 | 60s > K8s 默认 grace 30s，**允许配出必死组合** | 上界应为 10s（grace 45 − phase 20 − await 10 − 余量 5） |
| 5 | N-BE-060「await 30s 与 phase 20s 配套」 | 串行上界 = drain 5 + phase 20 + await 30 = **55s**，不是「30 与 20 比较」 | 改 10s 后仍 40s（drain 10）；必须同时把 grace 提到 45s，或把 drain/phase/await 压到 5/12/8 |
| 6 | N-BE-022「PG lock_timeout 60s 与 H2 同源」 | 60s 是**全会话**，不是只作用于 seed；业务请求也会等 60s | 全局 10s，seed 另开 60s |
| 7 | N-BE-055「256 = 投递池 8 的 32 倍」 | 这是比例推算，不是慢连接实测；256 无外部依据 | 改 64，压测后定 |
| 8 | N-BE-068「上限 100 已推荐」 | 只是注释里的推荐，**代码无 clamp** | `size=999999` 可拉全表；必须实现 |
| 9 | N-BE-102/103「DEFAULT 0 语义自明」 | DDL **无 CHECK**，负数合法；`error_count=-1` 会让失败判据失效 | 加 `CHECK (>=0)` |

### 8.6 用户点名重点速查

| 主题 | 现值 | 结论 | 关键 ID |
|---|---|---|---|
| Hikari / Tomcat / 异步池容量链 | Hikari 10（隐式）/ Async core 4·max 16·queue 200 / Tomcat 200·100·8192 | **Async max 16→8**；**connectionTimeout 30s→5s**；validation 5s→2s；Hikari 10、Tomcat 200 保留；queue 200→100 | 009、010、011、014–017、057–059 |
| 作业心跳 30s × 3 + 扫描 10s | 30s / 3 / 10s / 30m；判死窗 90s；发现 100s | 四值保留（K8s 10s×3、period 10s 同源）；**判死租约 90s→30s**；接管 190s→130s | 044–047、052、063、064 |
| 分页 10 / 50 / 20 + 无 clamp | Task 10、Job 50、Data 20；Data 无上限 | **三处统一 20**；**硬上限 100（clamp）**；响应回显实际 size | 065–068 |
| multipart 5MB / 6MB | 单文件 5MB、整包 6MB | 保留（Spring 缺省 1MB/10MB）；补「单文件 vs 整包」文档；多文件需改公式 | 004、005 |
| ZIP / Excel 无上限 | 每 entry `readAllBytes()`；`XSSFWorkbook` 全量；无行数上限 | **补单 entry/总解压/条目数/最大行数上限**；先加边界再谈放大 5MB | 004、005、073、074 |
| SSE 15s / 5m / 池 256 | 15s 写预算、5m 寿命、256 写出线程 | **15s 保留**（p99 依据）；**5m 保留 + ≤20s 心跳**；**256→64**（压测后定）；池形态保留 | 040、041、054–056 |
| Flyway / PG / H2 锁 60s | Flyway 启动锁 60s；PG `lock_timeout 60s`；H2 `LOCK_TIMEOUT=60000` | Flyway 60s 配置化；**PG 全局 60→10s**（seed 另开 60s）；H2 60s 仅 h2tcp | 022、028、076 |
| DDL 列宽 / 默认值 | code 64、name 128、row_key 256、path 512、message 1024、counters 0、`CHECK 0`、`NUMERIC 0` | 列宽多数保留；**实测 row_key/storage_path**；**进度/计数加 CHECK**；金额精度需裁决；INT 足够（Excel 1,048,576） | 086、092–106 |

---

## 9. 统计与优先级

### 9.1 机械统计（按每行第一个【】标签）

| 建议桶 | 条数 | 占比 | 说明 |
|---|---:|---:|---|
| **保留** | **72** | 67.9% | 值不动；只补注释/文档或做联动核对 |
| **改值** | **21** | 19.8% | 给出具体新值，均有来源或算式 |
| **配置化** | **8** | 7.5% | 值可留，但变成配置键/单一事实源/显式写出 |
| **需压测** | **2** | 1.9% | `row_key` 复合键（N-BE-086/097）；值暂留，必须实测 |
| **需裁决** | **3** | 2.8% | 下拉覆盖行数（074）、AI 调度器线程（088）、DDL 金额精度（106） |
| **合计** | **106** | 100% | 主表 80 + 附录 A 11 + 附录 B 15 |

> 「需要动作」合计 **34 项（32.1%）** = 改值 21 + 配置化 8 + 需压测 2 + 需裁决 3；其中真正「值错了/缺上限」的硬问题集中在 P0。另有 040/043/047/073 四项判「保留」但附带实测条件，不计入「需压测」桶，避免把「有实测依据的保留」误算成「没依据」。

### 9.2 业界口径分布（按每行第一个〔〕标签）

| 口径 | 条数 | 含义 |
|---|---:|---|
| 〔有据〕 | 57 | 官方文档给了明确数值/缺省值，可直接引用 |
| 〔惯例〕 | 29 | 官方只描述模式，没有统一数值；按约束推导 |
| 〔无统一标准〕 | 19 | 查不到权威一手数值；本文只给派生式，不编「主流值」 |
| 〔标准〕 | 1 | 协议/平台硬限（PG SQLState 码） |

> 这张表是本文最重要的「诚实度」指标：**19 项没有统一标准**，包括批大小 100、探测线程 4/8、判死租约 30/90s、写出池 64/256、列宽 64/128/256。对这些项，本文的建议是「按本仓约束推导 + 压测」，不是「业界都这样」。

### 9.3 分节统计

| 节 | 条数 | 保留 | 改值 | 配置化 | 需压测 | 需裁决 |
|---|---:|---:|---:|---:|---:|---:|
| §1.1 容器/数据源/Redis/就绪（001–039） | 39 | 27 | 8 | 4 | 0 | 0 |
| §1.2 作业/任务/SSE/线程池（040–064） | 25 | 17 | 7 | 1 | 0 | 0 |
| §1.3 分页/上限/缓冲/Excel/文件/Flyway（065–080） | 16 | 10 | 4 | 1 | 0 | 1 |
| 附录 A 协议/固定值（081–091） | 11 | 8 | 0 | 1 | 1 | 1 |
| 附录 B 业务库 DDL（092–106） | 15 | 10 | 2 | 1 | 1 | 1 |
| **合计** | **106** | **72** | **21** | **8** | **2** | **3** |

### 9.4 优先级

#### P0（5 项，先做；不做会「配出必死组合」或「被一次请求打穿」）

| # | ID | 现值 → 建议 | 风险 |
|---|---|---|---|
| 1 | `N-BE-022` | PG `lock_timeout 60s` → **10s**（seed 另开 60s） | 所有业务请求都可能等锁 60s，占住连接 + Tomcat 线程 |
| 2 | `N-BE-036` | `MAX_DRAIN_WINDOW 60s` → **10s** | 允许 drain 60 + phase 20 + await 10 = 90s，K8s 30s 必 SIGKILL |
| 3 | `N-BE-058` | `Async max 16` → **8** | 16 条作业线程抢 10 条连接，6 条每次等 5–30s |
| 4 | `N-BE-060` | `awaitTermination 30s` → **10s** | 串行上界 5+20+30=55s，作业被 SIGKILL 而非优雅收尾 |
| 5 | `N-BE-068` | `size` 无上限 → **clamp 100** | `size=999999` 可拉全表，性能/产品双缺口 |

#### P1（22 项，第二波；影响一致性/容量/可观测性）

| 组 | ID | 要点 |
|---|---|---|
| 连接池 | 009、010、011 | 显式 10；connectionTimeout 5s；validation 2s（三值一组） |
| 停机 | 018、024、027、031 | graceful 默认开启的纠错；默认 phase 20s、drain 上限 10s、多实例 grace 45s；PG/Cluster drain 10s；h2tcp 6s |
| 就绪探测 | 034 | 线程 4→8，给新增维度留余量 |
| 作业 | 042、052、055、059、061 | 生产 delay 0；判死租约 30s；写出池 64；队列 100；SSE 重试 5s |
| 分页 | 065、066 | 任务 10→20；问题 50→20 |
| 安全/边界 | 078 | XFF 链 16→8 |
| 实测 | 086、097 | `row_key`/`storage_path` 最长值实测 |
| 契约 | 099、102、103、106 | title 上限统一；进度/计数加 CHECK；金额精度裁决 |

#### P2（79 项，登记/补注释/随版本维护）

- 保留类 72 项：001、002、004–008、012、014–017、019–021、023、025、026、028–030、032、033、035、037–041、043–047、049–051、053、054、056、057、062–064、067、069–073、075、077、079–085、087、089、090、092–096、098、100、101、104、105。
- 配置化但非紧急 5 项：003（i18n 缓存）、013（maxLifetime）、048（fallback TTL）、076（Flyway 等待）、091（时区双写）。
- 需裁决但非紧急 2 项：074（下拉覆盖行数）、088（AI 调度器线程）。

> P2 不是「不重要」，而是「当前值有合理依据、改了收益小于联动成本」。其中 040/043/047/073 建议在下一轮压测时顺带取证，不单独开任务。

### 9.5 一句话优先级

**先修 5 个 P0（连接池错配、停机三段串行、drain 上限、分页无上限），再做 22 个 P1（三值一组、drain 统一、分页统一、CHECK/精度），其余 79 项按版本维护补注释。** 不要先动 5MB/6MB 或 256/15s 这些「看起来敏感、实际有依据」的值——它们的风险低于 P0。

---

## 10. 只读声明

- 本文只新建 `docs/research/numspec-2026-10-31/industry-backend-business.md`；未修改 `backend-business.md`、任何 `backend/src/main/**`、`backend/src/test/**`、`application*.yml`、DDL、脚本或前端文件。
- 未执行 `git add/commit/reset/checkout` 等写操作；未启动后端服务、测试、Redis、浏览器。
- 文中所有「建议」均为**审视建议**，不代表本次已改值；是否调整由后续裁决决定。
- 行号与现值以 `backend-business.md` 的扫描 HEAD（`405e74e` / DDL 补扫 `d50531a`）为准；若后续 HEAD 变化，需重新核对。
- 本文引用的官方页面抓取时间为 2026-10-31；框架/数据库文档可能随版本更新，落地前应以**本仓实际依赖版本**（Boot 3.5.14 / Tomcat 10.1 / POI 5.5.1）再核一次缺省值。
