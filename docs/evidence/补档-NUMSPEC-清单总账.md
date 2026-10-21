> 来源=仓库外过程报告补档（2026-10-11，DC-19 前历史文件，署名信息以文内为准）

# 数字规格清单总账（numeric-spec-audit）

- **排查对象**：`<REPO_ROOT>`（分支 `main`，HEAD `6ee96c3`，**工作树现态**含批 A 施工在制品）
- **排查时点**：2026-10-10
- **任务边界**：**只读**。本报告落盘于仓库外，未修改仓库任何文件，未做任何 git 写操作。
- **本报告的回答**：项目里每一个「数字规格」的**出处、现值、可调性、有无论证依据**。
- **不做的事**：不与业界主流方案对照裁决（另一并行任务负责）；不重复登记 `<SCRATCH>\wait-audit\排查报告.md` §3 已列的超时/预算键（**只标「引用」**），本报告补的是它未覆盖的维度：重试、退避、轮询、池、分页、缓冲、心跳、TTL、阈值。

---

## 0. 口径约定

### 0.1 三个标注维度

| 维度 | 取值 | 判定口径 |
|---|---|---|
| **可调性** | `配置键` | 有 `application.yml` 键或 `@ConfigurationProperties` 字段，不重编译可改 |
| | `硬编码` | 代码内字面量 / `static final` 常量，改值须改代码重编译 |
| | `框架缺省` | 本仓未显式配置，取 Spring/Tomcat/浏览器/引擎的默认值 |
| **依据现状** | `有依据` | 代码注释或文档**给出了取值的数值论证、实测数据或正式裁定** |
| | `部分依据` | 注释说明了用途/对齐来源，但**取值本身无数值论证**；或明示「初值/待压测」 |
| | `裸数字` | 无注释、无文档、无实测，仅一个数 |
| **断言强度** | `【实测】` | 本次实读源码/配置原文得到 |
| | `【推断】` | 由上下文推导（如「框架默认值」未在本仓出现） |

### 0.2 分类词表

超时 / 重试 / 退避 / 轮询 / 池 / 分页 / 缓冲 / 心跳 / TTL / 阈值 / 其他

### 0.3 与 wait-audit §3 的关系

wait-audit §3 已列：后端 18 组超时/预算键 + 前端 13 组 + 7 项「一刀切共用」键。本报告对其**只做引用标注**（行内写「见 wait-audit §3」），**不重复造表**；同时对这些键补上它没给的「依据现状 / 可调性」两列。

---

## 1. 总账表

> 表内一行 = 一条。分组行（如生成式表单 10 项上限）在「现值」列枚举全部取值，并在「编号」列注明本行含多条。

### 1.1 后端配置键（`application.yml` + `@ConfigurationProperties` 默认值）

| 编号 | 分类 | 位置 / 键名 | 现值 | 作用场景 | 可调性 | 依据现状 |
|---|---|---|---|---|---|---|
| B1-01 | 超时 | `application.yml:6` `server.tomcat.connection-timeout` | `120000` | Tomcat 建连/请求行空闲超时；**同时是 SSE 写侧事实兜底上界（≈116s 实测）** | 配置键 | 裸数字（yml 内无注释；口径澄清见 wait-audit §3.1/R-38） |
| B1-02 | 超时 | `application.yml:73` `spring.data.redis.timeout` | `2s` | Redis 单命令超时 | 配置键 | 有依据（yml:70-72 注释：R4 裁决，实测「5 次串行各等 3s = 15.1s」后收紧）【实测】 |
| B1-03 | 超时 | `application.yml:74` `spring.data.redis.connect-timeout` | `2s` | Redis 建连超时 | 配置键 | 部分依据（同上注释覆盖，未单独论证） |
| B1-04 | 超时 | `application.yml:76` `spring.data.redis.lettuce.shutdown-timeout` | `200ms` | Lettuce 关停等待 | 配置键 | **裸数字** |
| B1-05 | 其他 | `application.yml:111` `spring.ai.openai.chat.options.temperature` | `0.2` | 模型采样温度 | 配置键 | **裸数字** |
| B1-06 | 缓冲 | `application.yml:112` `spring.ai.openai.chat.options.max-tokens` | `4096` | 单轮输出 token 上限 | 配置键 | **裸数字**（无注释；与 DeepSeek 模型上下文无对照说明） |
| B1-07 | TTL | `application.yml:138` `app.ai.session-ttl` | `24h` | **会话级键族的统一滑动 TTL**（记忆 + run 快照/待决/帧归档/序号/事件日志/req/decided/fence/session:current/ctx 三件套/safety/ledger/idem + **`ai:cancel`**）：每次写/心跳/读侧 touch 整额续期，无绝对上限 | 配置键 | 有依据（2026-10-20 用户裁决第 4 条 6h→24h + 2026-10-21「取消信号随会话共亡」；类注 `AiProperties.java:28-33`、键族登记 `ai/run/SessionKeyRegistry`）【实测：`RunStoreSlidingTtlTest` / `SessionKeyRegistryTest`】 |
| B1-08 | 缓冲 | `application.yml:140` `app.ai.memory.max-messages` | `40` | `MessageWindowChatMemory` 窗口条数 | 配置键 | **裸数字**（`AiProperties.java` 的 `Memory` 注只说「官方实现负责裁剪」，没论证 40） |
| B1-09 | 超时 | `application.yml:181` `app.ai.hitl.frontend-tool-timeout` | `120s` | **只**管 6 个即时执行类前端工具（navigate_to / select_definitions / set_condition / confirm_step / open_export_file_editor / download_export_files）的**机器回执**兜底；确认门与 6 个等人工具（generative_form / replace_values / update_rows / normalize_column / undo_last_sheet_edit / ui_request_upload）**无上限** | 配置键 | 有依据（yml:172-180 注释 + 业界依据 §6；`confirm-timeout` 键**已退役**）。见 wait-audit §3.1 与 §3.3 第 1 项（拆分已闭环，退役面见本批 §1.1c） |
| B1-10 | 池 | `application.yml:184` `app.ai.suspend.pool-size` | `20` | 挂起专用有界平台线程池容量（队列 0，饱和 503） | 配置键 | 有依据（压测证据 §S1.3：并发 20 受理 / 25 路 5 拒绝，阶梯线性）【实测引自 docs】 |
| B1-11 | 超时 | `application.yml:194` `app.ai.resilience.first-byte-timeout` | `30s` | 上游首包静默上限 | 配置键 | 部分依据（yml:186-193 明示「三个阈值均为初值，待 §13.2 #2 压测裁决」；压测证据标【推断】无数据） |
| B1-12 | 超时 | `application.yml:195` `app.ai.resilience.inter-event-timeout` | `90s` | 事件间静默上限 | 配置键 | 部分依据（同上；压测证据标【推断】无数据） |
| B1-13 | 重试 | `application.yml:196` `app.ai.resilience.max-attempts` | `2` | 单轮最大尝试次数（首轮 + 重试 1 次）；两次尝试之间有 **0.5s 起 ×2 + 抖动** 的退避（`ai/run/RetryBackoff`，只从剩余总预算里出） | 配置键 | 部分依据（类注只注「首轮 + 重试次数-1」；退避算式见超时面治理批① 第 1 项） |
| B1-14 | 超时 | `application.yml:197` `app.ai.resilience.total-budget` | `300s` | 单轮总预算（墙钟 − 挂起等待）；同时是 E2E 脚本 HTTP/SSE 等待窗口的派生根源（+60s 余量） | 配置键 | 部分依据（压测证据 §S8：6004 帧全程 < 3s，预算**远未触及**；撞预算路径未构造） |
| B1-15 | 轮询 | `application.yml:202` `app.ai.resume.startup-delay` | `8s` | 启动后延迟多久开始自动续跑 | 配置键 | **裸数字** |
| B1-16 | TTL | `application.yml:203` `app.ai.resume.zombie-age` | `60s` | 判定「死卡」的僵尸时长阈值 | 配置键 | **裸数字** |
| B1-17 | 阈值 | `application.yml:204` `app.ai.resume.max-on-startup` | `10` | 单次启动最多续跑多少轮 | 配置键 | **裸数字** |
| B1-18 | 池 | `application.yml:207` `app.ai.resume.trigger-pool-size` | `2` | 死卡兜底触发池线程数 | 配置键 | 部分依据（yml:205-206 注释解释「容量小 + 队列有界 + 饱和拒绝」的理由，但 2 这个数无论证） |
| B1-19 | 缓冲 | `application.yml:208` `app.ai.resume.trigger-queue-capacity` | `16` | 触发池队列容量 | 配置键 | 部分依据（同上） |
| B1-20 | TTL | `application.yml:214` `app.ai.session.lock.ttl` | `10m` | **会话锁 = 互斥窗口上界**（持锁进程死后别人多久能接管；watchdog 续期 + 轮终态释放）。**不是**轮预算上限（轮预算 = `total-budget` 300s） | 配置键 | 有依据（超时面治理批① 第 8③ 项订正自述：原「= 轮上限」是误述，已改为现行语义） |
| B1-21 | 轮询 | `application.yml:215` `app.ai.session.lock.watchdog` | `30s` | 会话锁续期节拍 | 配置键 | 部分依据（同 ADR-5；`M2-T2b-施工红队` 实测续期实际来自 ConfirmGate 2s 心跳） |
| B1-22 | 缓冲 | `application.yml:231` `app.ai.tool-result.max-rows` | `200` | 工具结果**回填模型**前的行数上限 | 配置键 | 部分依据（yml:230 注「初值依 R10-1 建议…实际取值随 §13.2 #9 压测复核」） |
| B1-23 | 缓冲 | `application.yml:232` `app.ai.tool-result.max-chars` | `8000` | 工具结果回填模型前的字符上限 | 配置键 | 部分依据（同上） |
| B1-24 | 缓冲 | `application.yml:254` `app.ai.frame.max-bytes` | `65536`（64KB） | SSE 单帧 UTF-8 字节上限，超限字段级截断 | 配置键 | 有依据（yml:253-253 注：T3-10 裁定；口径边界在技术方案「帧上限」节明文） |
| B1-25 | 池 | `AiProperties.java:479` `app.ai.sse.delivery-pool-size`（**代码缺省，yml 不写**） | `8` | SSE 投递池工作线程数 | 配置键 | 有依据（压测证据 §S1.2：M<poolSize 才不饿死健康订阅者）【实测引自 docs】 |
| B1-26 | 缓冲 | `AiProperties.java:506` `app.ai.sse.delivery-queue-capacity`（**代码缺省，yml 不写**） | `256` | 每订阅者实时帧队列容量 | 配置键 | 有依据（压测证据 §S4.3：256 条实时 + 3000 回放不误摘） |
| B1-27 | 超时 | `application.yml:272` `app.ai.sse.write-timeout` | `15s` | **单帧** SSE 写出预算，超预算摘除订阅者（`/api/ai/health` 的 `writeTimeoutMs` 报**本键实际值**，批① 第 3 项） | 配置键 | 有依据（yml:243-271 长篇论证 + tomcat 字节码反证实测「写侧 = connection-timeout ≈116s，是 15s 的 8 倍宽」）【实测】 |
| B1-28 | 超时 | `application.yml:285` `app.task.sse.write-timeout` | `15s` | 任务通道单帧写预算 | 配置键 | 有依据（yml:262-284 注「与 AI 通道同一缺省来源 `SseWriteBudget#DEFAULT_TIMEOUT_MILLIS`」+ 启动期 fail-fast 守卫） |
| B1-29 | 超时 | `application.yml:286` `app.task.sse.emitter-timeout` | `5m` | 任务 SSE 订阅者生命周期（AI 通道**不设**传输层死线） | 配置键 | 部分依据（注释只说「与 AI 通道的 emitter 生命周期同量级」） |
| B1-30 | 轮询 | `application.yml:288` `app.job.demo-batch-delay-ms` | `300` | 作业分片间人为节拍（演示/可观测性） | 配置键 | **裸数字**（`AppProperties` 无注释；E2E 依赖此节拍可见性） |
| B1-31 | 缓冲 | `application.yml:289` `app.job.batch-size` | `100` | 作业分片大小 | 配置键 | **裸数字**（`AppProperties` 无注释） |

### 1.1b 代码缺省键（`application.yml` **不显式写出**，值取 `@ConfigurationProperties` 字段初值）

> 追加登记（2026-10-21 超时面治理批① 第 6 项）。这 7 个键此前只活在代码里 —— 配置面 grep 不到，
> 运维无从发现「租约 30s / 续租 10s / 资源锁 60s」这类时间口径，也无法判断改的是哪一层。
> 本批在 `application.yml` 的 `app.ai.session` 段后补了**注释段**（只标默认值与出处行，**不写出值**：
> 写出来就等于给配置面加第二份事实源）。下表是那份注释的机器可读版。

| 编号 | 分类 | 键名 | 现值 | 作用场景 | 可调性 | 依据现状 |
|---|---|---|---|---|---|---|
| B1-57 | TTL | `app.ai.lease.ttl`（`AiProperties.java:567`） | `30s` | 续跑权（fencing）租约有效期 = 续租间隔 ×3 | 配置键（代码缺省） | 有依据（类注：TTL 30s = 持租进程死后「多快能接管」上界） |
| B1-58 | 轮询 | `app.ai.lease.renew-interval`（`:570`） | `10s` | 租约持有者续租间隔（= TTL/3，容忍两次连续失败） | 配置键（代码缺省） | 有依据（同上） |
| B1-59 | 退避 | `app.ai.lease.retry-delay`（`:573`） | `35s` | 抢租失败后的退避重试间隔（TTL + 5s 余量） | 配置键（代码缺省） | 有依据（同上） |
| B1-60 | TTL | `app.ai.lease.resource-ttl`（`:588`） | `60s` | L1 资源互斥锁（`ai:lock:task|job:*`）有效期；**不续租**，只作临界区上界 | 配置键（代码缺省） | 有依据（B23L1 批③b 复审 I-1：动作超上界宁可放开，也不把「事实永锁」续出来） |
| B1-61 | 开关 | `app.ai.lock.enabled`（`:603`） | `true` | 资源互斥总开关（关掉只该用于单实例对照，不是故障开关） | 配置键（代码缺省） | 有依据（裁决 13：降级会让「锁坏了」与「锁关了」在日志里长得一样） |
| B1-62 | 池 | `app.ai.sse.delivery-pool-size`（`:479`） | `8` | SSE 投递池工作线程数（与 B1-25 同一项，此处补出处行） | 配置键（代码缺省） | 同 B1-25（T3-5 压测定值 8） |
| B1-63 | 缓冲 | `app.ai.sse.delivery-queue-capacity`（`:506`） | `256` | 每订阅者实时队列容量（与 B1-26 同一项，此处补出处行） | 配置键（代码缺省） | 同 B1-26（T3-5 压测定值 256） |

### 1.1c 超时面退役键与订正（2026-10-21 批①）

| 退役键 | 原口径 | 现行替代 | 落点 |
|---|---|---|---|
| `app.ai.hitl.confirm-timeout` | 确认门等待上限（240s 过渡值 → 曾拟 600s） | **无上限**（等人不设超时）；机器回执上限走 `app.ai.hitl.frontend-tool-timeout` | 2026-10-20 用户裁决第 2 条；启动期 WARN 见 `AiRetiredKeysWarner` |
| `app.ai.session-ttl-minutes` | 会话 TTL 分钟数（6h） | `app.ai.session-ttl`（Duration，24h） | 同上（批① 第 7 项加退役告警） |
| `app.ai.max-iterations` | 工具循环迭代上限 | 官方工具循环的退出条件 + `total-budget` | 同上 |
| `app.ai.thinking-disabled` | 关闭思考 | 不再下发 `extraBody.thinking`（由模型侧参数决定） | 同上 |

> **编号唯一性复核**：本文件 B1-01..B1-63 逐号唯一（`grep -o '^| B1-[0-9]*' | sort | uniq -d` 为空）。
> 任务卡点名的「B1-50 编号重复」在 `cb420ff` 基线**已不可复现**（`summary-timeout` 行仅此一条，位于 §7），
> 本批以本次逐号清点作为收口证据，不另造改动。
>
> **数值/行号订正说明（批①）**：B1-01..B1-31 的 `application.yml` 行号统一按 `cb420ff` 现态重标（原表按
> 2026-10-10 的旧版 yml 编号，整体偏移 10~40 行）；B1-07 现值 `6h → 24h`；B1-09 键名由
> `app.ai.hitl.timeout`（三场景共用）改为 `app.ai.hitl.frontend-tool-timeout`（只管机器回执）；
> B1-20 自述由「= 轮上限」改为「互斥窗口上界」；B1-25/26 标注「代码缺省，yml 不写」。

### 1.2 后端硬编码常量（代码内 `static final` / 字面量）

| 编号 | 分类 | 位置 | 现值 | 作用场景 | 可调性 | 依据现状 |
|---|---|---|---|---|---|---|
| B2-01 | 超时 | `backend/.../common/sse/SseWriteBudget.java:86` | `15_000L` | 两通道写侧预算的**唯一缺省来源** | 硬编码 | 有依据（类注释整段论证；见 wait-audit §3.1 引用） |
| B2-02 | 池 | `common/sse/SseWriteBudget.java:133-134` | `core=0 / max=Integer.MAX_VALUE / keepAlive=60s / SynchronousQueue` | 弹性写出池（慢连接隔离） | 硬编码 | 有依据（:98 注「弹性是刻意的：有界池会把慢连接数变成池饱和」） |
| B2-03 | 池 | `ai/run/SseChatEmitter.java:278` | `keepAlive=60L, TimeUnit.SECONDS` | 投递池空闲线程存活 | 硬编码 | **裸数字**（投递池 core=max=配置的 8，仅存活时间写死 60s） |
| B2-04 | 池 | `ai/exec/AiExecutorConfig.java:62` | `Executors.newScheduledThreadPool(2, …)` | 流式看门狗调度器线程数 | 硬编码 | 部分依据（:51-60 注「单条平台守护线程」，但实数 2 无论证；推断 2 = 主节拍 + 余量）【推断】 |
| B2-05 | 池 | `config/AsyncConfig.java:44-46` | `core=4 / max=16 / queue=200` | 业务作业执行器（`@Async`） | 硬编码 | 部分依据（类注释「为什么池大小是这几个数」做**定性**论证：「足以覆盖一个任务中心点几下一键导出」，无数值论证） |
| B2-06 | 池 | `config/AsyncConfig.java:49` | `awaitTerminationSeconds(30)` | 关停等待 | 硬编码 | **裸数字** |
| B2-07 | 池 | `ai/exec/AiExecutorConfig.java:40-44` | `core=max=配置(20) / queue=0 / AbortPolicy` | 挂起池形态（饱和快速失败 503） | 硬编码（容量走配置） | 有依据（DC-12 + ADR-2 重审 N1 注释；压测证据 §147 行核实） |
| B2-08 | 池 | `ai/exec/AiExecutorConfig.java:81-87` | `core=max=2 / queue=16 / AbortPolicy` | 续跑触发池形态 | 硬编码（容量走配置） | 同 B1-18 / B1-19 |
| B2-09 | 阈值 | `ai/config/AiPoolCapacityBudget.java:31` | `CPUS_PER_THREAD_BUDGET = 10` | reactor `boundedElastic` 容量推导系数（容量 = 10 × CPU） | 硬编码 | 有依据（类注释 + 压测证据 §S1.5：16 核实读 160，要求 48）【实测引自 docs】 |
| B2-10 | 轮询 | `ai/run/StreamWatchdog.java:42` | `TICK_MILLIS = 1000L` | 看门狗节拍（判定首包/静默/预算/取消） | 硬编码 | 部分依据（注释说明节拍体只做常数次原子读 + 一次 Redis GET，成本已实测；但 1000ms 这个数无论证） |
| B2-11 | 心跳 | `ai/gate/ConfirmGate.java:75` | `HEARTBEAT_SECONDS = 2` | 挂起等待循环的 heartbeat 帧间隔 | 硬编码 | 部分依据（注释说明帧内容；压测证据 §S4 有「2s 心跳在 8.9ms 级锁内成本」实测，但**间隔取值本身无依据**） |
| B2-12 | 心跳 | `ai/run/ResilientChatService.java:96` | `STREAMING_HEARTBEAT_MILLIS = 5000L` | 流式产出期补帧节拍 | 硬编码 | 部分依据（`AiController.java:578-579` 注「T3-5 维持现值，未改」；技术方案 §673 行明示「心跳 2s/5s 不在 §13.2 #2 闭环内」） |
| B2-13 | 缓冲 | `ai/run/ResilientChatService.java:650` | `TAIL_SIZE = 6` | 末帧摘要保留条数（日志/诊断） | 硬编码 | **裸数字** |
| B2-14 | TTL | `ai/config/RedisAvailability.java:46` | `POSITIVE_TTL_MS = 0L` | Redis 探活**成功**结果缓存时长（0 = 不缓存，每次真发 PING） | 硬编码 | 部分依据（:25-27 注解释了正/负缓存分离的语义，0 的意义自明） |
| B2-15 | 退避 | `ai/config/RedisAvailability.java:52` | `NEGATIVE_TTL_MS = 5000L` | Redis 不可用时的重试间隔（负缓存） | 硬编码 | **裸数字**（注释只说「每 NEGATIVE_TTL_MSms 才重试一次」，没有论证 5s） |
| B2-16 | 退避 | `ai/run/CancellationRegistry.java:67` | `DEGRADED_POLL_BACKOFF_MS = 5000L` | Redis 降级期取消标志轮询退避 | 硬编码 | 部分依据（:206-208 注说明退避理由「同步 GET 会阻塞到 redis.timeout」，但 5s 这个数无论证；**且与 B2-15 同值不同义**） |
| B2-17 | 缓冲 | `ai/run/RunStore.java:100` | `EVENT_WINDOW = 3000` | 每轮最多留存的外置帧数（归档窗口） | 硬编码 | 部分依据（:92-99 注「与 SP-02 同取 3000」；不变量「实时队列容量 < 本窗口」由单测锚定） |
| B2-18 | TTL | `ai/run/RunStore.java:107` | `EXPIRED_RENEWAL = 1s` | 已过绝对上限时的「立即过期」TTL | 硬编码 | 有依据（:101-106 注完整论证：SET/EXPIRE 不接受非正 TTL） |
| B2-19 | 重试 | `ai/run/FrameSizeLimiter.java:39` | `MAX_ITERATIONS = 8` | 单帧截断的迭代收缩上限（防死循环） | 硬编码 | 部分依据（注释「防病态输入下的死循环」，8 这个数无论证） |
| B2-20 | 缓冲 | `ai/run/FrameSizeLimiter.java:42` | `STRUCTURE_MARGIN_BYTES = 64` | 截断预算里让给 JSON 结构的字节数 | 硬编码 | 部分依据（注释说明用途「键名/冒号/逗号等」，64 无论证） |
| B2-21 | 阈值 | `ai/form/GenerativeFormRules.java:100-118`（本行含 **10 条**） | `MAX_FIELDS=20 / MAX_OPTIONS=50 / MAX_KEY_CHARS=40 / MAX_TITLE_CHARS=100 / MAX_LABEL_CHARS=100 / MAX_PLACEHOLDER_CHARS=100 / MAX_OPTION_TEXT_CHARS=100 / MAX_TEXT_VALUE_CHARS=200 / MAX_FORM_CHARS=8000 / MAX_RESULT_CHARS=8000` | 生成式表单 schema 白名单上限 | 硬编码 | 部分依据（有**对账机制**：`stub-upstream.mjs --self-check` 逐值读事实源对账；但取值本身无论证来源） |
| B2-22 | 缓冲 | `ai/tool/ContextBuilder.java:63` | `EXTRA_VALUE_MAX_CHARS = 300` | 上下文 extra 单值字符上限 | 硬编码 | **裸数字** |
| B2-23 | 缓冲 | `ai/tool/ContextBuilder.java:66` | `EXTRA_TOTAL_MAX_CHARS = 800` | 上下文 extra 总字符上限 | 硬编码 | **裸数字**（与 B1-23 `tool-result.max-chars` 8000 差一个数量级，两者无交叉说明） |
| B2-24 | 缓冲 | `file/FileStorageService.java:23` | `DIGEST_BUFFER_BYTES = 8192` | 文件摘要流式读取缓冲 | 硬编码 | **裸数字**（8KB 是常见 IO 缓冲经验值，但仓内无说明） |
| B2-25 | 缓冲 | `excel/ExcelWriter.java:31` | `new SXSSFWorkbook(100)` | 大导出流式写的行滑动窗口 | 硬编码 | **裸数字** |
| B2-26 | 上限 | `excel/ExcelTemplateBuilder.java:107` | `new CellRangeAddressList(1, 1000, …)` | 模板数据校验（下拉）覆盖到第 1000 行 | 硬编码 | **裸数字** |
| B2-27 | 上限 | `job/service/ExportJobRunner.java:68` | `ISSUE_MESSAGE_MAX = 1024` | 校验问题消息截断长度 | 硬编码 | **裸数字** |
| B2-28 | 上限 | `job/entity/ValidationIssue.java:31` | `@Column(nullable=false, length=1024)` | 问题消息列宽 | 硬编码 | **裸数字**（与 B2-27 同值；**需核实是否同源**，现为两处独立字面量） |
| B2-29 | TTL | `job/service/JobCancellationRegistry.java:42` | `CANCEL_TTL = Duration.ofHours(1)` | 作业取消标志键 TTL | 硬编码 | **裸数字** |
| B2-30 | 上限 | `ai/tools/AiTools.java:139` | `.limit(10)` | `list_tasks` 工具返回条数硬截断 | 硬编码 | **裸数字**（🔴 高影响：直接决定模型「看见多少任务」，且无分页/无游标/无说明） |
| B2-31 | 分页 | `data/controller/DataController.java:49-50` / `job/controller/JobController.java:60-61` / `task/controller/TaskController.java:45-46` | `size=50 / 50 / 10`；`page=0` | 三处列表接口分页默认值 | 硬编码（注解内） | **裸数字**（三处三种取值：50/50/10，无统一口径说明） |
| B2-32 | 框架缺省 | 无配置 | Hikari 连接池 ≈10；`spring.servlet.multipart` 1MB/10MB；Tomcat `max-threads` 200 | 连接池 / 上传大小 / 容器并发 | 框架缺省 | **裸数字**【推断】（三者在仓内**完全未出现**，取框架默认；wait-audit §3.1 已登记 Hikari 一项） |

### 1.3 前端代码常量（`frontend/src`）

| 编号 | 分类 | 位置 | 现值 | 作用场景 | 可调性 | 依据现状 |
|---|---|---|---|---|---|---|
| F1-01 | 超时 | `api/http.ts:85` | `timeout: 60000` | 所有 axios 请求单次上限（业务 REST + **回灌 POST** 共用） | 硬编码 | **裸数字**（无任何注释；见 wait-audit §3.2 与 §3.3 第 2 项） |
| F1-02 | 超时 | `api/sse.ts:22` | `DEFAULT_IDLE_TIMEOUT_MS = 90_000` | 帧间静默上限，超即判断流 | 硬编码 | 部分依据（注释说明语义与后端 heartbeat 的关系；90s 取值无独立论证） |
| F1-03 | 重试 | `api/sse.ts:25` | `DEFAULT_MAX_RECONNECTS = 2` | 断流后自动重挂最大次数 | 硬编码 | **裸数字** |
| F1-04 | 退避 | `api/sse.ts:28` | `DEFAULT_RECONNECT_DELAY_MS = 1_000` | 首次重连等待（后续指数退避） | 硬编码 | 部分依据（注释「后续指数退避」，base=1s 无独立论证） |
| F1-05 | 退避 | `api/sse.ts:540`（`baseDelay` 定义在 `:433`） | `delay = baseDelay * 2 ** (attempts - 1)` | 指数退避公式 = 1s → 2s | 硬编码 | 部分依据（同 F1-04；**无上限封顶、无抖动/jitter**） |
| F1-06 | 超时 | `api/sse.ts:43` | `DEFAULT_CONNECT_TIMEOUT_MS = 15_000` | reattach **建连**看门狗 | 硬编码 | 有依据（:31-42 注释完整论证：落在审计建议 10~15s 上界；三轮预算 15+1+15+2+15=48s < 90s） |
| F1-07 | 轮询 | `api/jobs.ts:51` | `intervalMs ?? 1000` | `waitForJobFinal` 轮询间隔 | 硬编码（可传参覆写） | **裸数字** |
| F1-08 | 超时 | `api/jobs.ts:52` | `timeoutMs ?? 300_000` | `waitForJobFinal` 上限（超时**返回非终态不抛错**） | 硬编码（可传参覆写） | **裸数字**（与后端 `total-budget` 300s 同值但语义无关 ⇒ 数值巧合） |
| F1-09 | 退避 | `api/tasks.ts:106` | 无 | 任务通道 `new EventSource(...)` **未接管重连**，完全依赖浏览器默认 retry（约 3s） | 硬编码（缺配置） | **裸数字**【推断】（浏览器默认值；与 AI 通道 `api/sse.ts` 的显式退避策略不对称） |
| F1-10 | 轮询 | `utils/job-watch.ts:21` | `JOB_FALLBACK_POLL_MS = 3000` | SSE 兜底轮询间隔 | 硬编码 | 部分依据（注释「SSE 正常时只是低频自愈，不构成负载」；3000 无独立论证） |
| F1-11 | 轮询 | `stores/task.ts:32` | `TASK_AUTO_REFRESH_MS = 4000` | 任务中心无条件自刷新 | 硬编码 | 部分依据（注释「规格：4s」，指需求文档口径） |
| F1-12 | 分页 | `stores/task.ts:65` | `size: 10` | 任务列表默认页大小 | 硬编码 | **裸数字**（与后端 `TaskController` 默认 10 一致 ⇒ 同源同义） |
| F1-13 | 分页 | `stores/task.ts:275` | `loadIssues(jobId, page=0, size=50)` | 作业问题列表默认页大小 | 硬编码 | **裸数字**（与后端 `JobController` 默认 50 一致） |
| F1-14 | 轮询 | `stores/task.ts:299` | `intervalMs: 1000` | 调用 `waitForJobFinal` 时显式覆写 | 硬编码 | **裸数字** |
| F1-15 | 超时 | `stores/workspace.ts:327` | `DOWNLOAD_FETCH_TIMEOUT_MS = 60_000` | `ui_event download` 的下载拉取上限 | 硬编码 | 部分依据（注释「与 `api/http.ts` axios 上限同值——都是拉字节类请求，延迟分布同类」；60s 本身沿用 F1-01 的裸值）**批 A 在制品新增**【实测】 |
| F1-16 | 上限 | `types/tools.ts:192` | `WORKSPACE_ACTION_LIMIT = 5` | 一轮对话最多携带的历史动作条数 | 硬编码 | 部分依据（注释「只带最近若干条，避免上下文膨胀」；5 无独立论证） |
| F1-17 | 其他 | `types/tools.ts:125` | `WORKSPACE_CONTRACT_VERSION = 1` | 工作区契约版本号 | 硬编码 | **裸数字** |
| F1-18 | 轮询 | `utils/frontend-tools.ts:541 / :583` | 轮询周期 `50ms` | `waitForPageReady` / `waitForTaskBound` 的页面就绪探测间隔 | 硬编码 | **裸数字** |
| F1-19 | 超时 | `utils/frontend-tools.ts:191` | `FRONTEND_TOOL_TIMEOUT_BUDGET_MS = 60_000` | 前端工具执行器自有预算缺省 | 硬编码 | 有依据（:161-231 长篇论证 + issue#6 实测）。见 wait-audit §3.2 引用 |
| F1-20 | 超时 | `utils/frontend-tools.ts:200` | `FRONTEND_TOOL_RESULT_HEADROOM_MS = 60_000` | 折算时让给回灌 POST 的余量 | 硬编码 | 有依据（同上；直接引用 F1-01 的 60s） |
| F1-21 | 超时 | `utils/frontend-tools.ts:208` | `MIN_FRONTEND_TOOL_TIMEOUT_BUDGET_MS = 5_000` | 病态小上限时的预算下限 | 硬编码 | 部分依据（注释「防预算过短」；5s 无论证） |
| F1-22 | 其他 | `utils/frontend-tools.ts:278` | `TIMER_MAX_DELAY_MS = 2_147_483_647` | `setTimeout` delay 上界（防溢出假超时） | 硬编码 | 有依据（引擎约束 + MDN 引证，:252-276 长篇） |
| F1-23 | 超时 | `utils/frontend-tools.ts:516` | `PAGE_READY_TIMEOUT_MS = 3_000` | 导航后页面就绪等待上限 | 硬编码 | **裸数字** |
| F1-24 | 轮询 | `components/SpreadGrid.vue:177` | `50ms` | 宿主尺寸变化的 `refresh` 防抖 | 硬编码 | **裸数字** |
| F1-25 | 超时 | `components/SpreadGrid.vue:99` | `READY_TIMEOUT_MS = 60_000` | 表格引擎就绪看门狗 | 硬编码 | 有依据（:92-97 注释论证「与 axios 上限同族故同值；且 < 后端 120s」。见 wait-audit §3.2/§3.3 第 6 项） |
| F1-26 | 防抖 | `stores/ai.ts:360` | `300ms` | 会话镜像写 sessionStorage 的防抖 | 硬编码 | **裸数字** |
| F1-27 | 超时 | `stores/ai.ts:99 / :984` | `now + timeoutSeconds*1000`（帧式折算） | 确认卡/表单本地 deadline | 硬编码（输入来自帧） | 有依据（= 服务端 `expiresAt` 折算，同钟单点保证） |
| F1-28 | 轮询 | `components/AiPanel/AiPanel.vue:595` | `1000ms` | 确认卡/表单倒计时节拍 | 硬编码 | 部分依据（注释「倒计时节拍（每秒推进一次）」） |
| F1-29 | 防抖 | `components/AiPanel/AiPanel.vue:582` | `60ms` | 展开面板后滚动到底的延迟 | 硬编码 | **裸数字** |
| F1-30 | 缓冲 | `utils/excel.ts:27` | `DEFAULT_SHEET_ROWS = 200` | 新工作簿默认行数 | 硬编码 | **裸数字** |
| F1-31 | 其他 | `utils/excel.ts:847` | `3000ms` | 导出 blob 后延迟 `revokeObjectURL` | 硬编码 | **裸数字** |
| F1-32 | 其他 | `utils/excel.ts:309-310` | `new Date(2000,0,1)` / `new Date(2099,11,31)` | 日期选择器可用范围 | 硬编码 | **裸数字** |

### 1.4 前端视图/模板内数字（业务可见的硬上限与页大小）

| 编号 | 分类 | 位置 | 现值 | 作用场景 | 可调性 | 依据现状 |
|---|---|---|---|---|---|---|
| F2-01 | 分页 | `views/DataBrowserView.vue:185` | `size = ref(20)` | 数据浏览器默认页大小 | 硬编码 | **裸数字**（与后端 `DataController` 默认 50 **不一致**） |
| F2-02 | 分页 | `views/DataBrowserView.vue:129` | `[20, 50, 100]` | 页大小选项 | 硬编码 | **裸数字** |
| F2-03 | 分页 | `views/TaskCenterView.vue:114` | `[10, 20, 50]` | 页大小选项 | 硬编码 | **裸数字**（与任务 store 默认 10 一致） |
| F2-04 | 上限 | `views/TaskCenterView.vue:135` | `maxlength="60"` | 创建任务标题长度上限 | 硬编码 | **裸数字**（后端是否有对应校验待核） |
| F2-05 | 上限 | `views/ImportWizardView.vue:148 / :166` | `slice(0, 100)` + 「仅展示前 100 行」 | 暂存区预览行数上限 | 硬编码 | 部分依据（有配套文案说明上限，但 100 无来源） |
| F2-06 | 上限 | `views/ImportWizardView.vue:188` | `issueKeys.slice(0, 8)` | 发布冲突行提示条数 | 硬编码 | **裸数字** |
| F2-07 | 分页 | `views/ImportWizardView.vue:841` | `jobsApi.getIssues(jobId, 0, 200)` | 拉取问题列表页大小 | 硬编码 | **裸数字**（= B2-27/B2-28 的 1024 无关；200 与 `tool-result.max-rows` 200 **同值不同义**） |

> 说明：纯布局数字（`width` / `px` / `max-height` / `:image-size` 等）不计入本总账 —— 它们不是「等待/容量/上限」语义的规格。`views/ImportWizardView.vue:148` 的 `max-height="300"` 属此类，已排除。

### 1.5 环境 / 构建设置

| 编号 | 分类 | 位置 | 现值 | 作用场景 | 可调性 | 依据现状 |
|---|---|---|---|---|---|---|
| V-01 | 其他 | `frontend/vite.config.ts:10` | `DEFAULT_API_BASE='http://localhost:8081'` | 代理目标默认（端口 8081 与后端 `server.port` 对齐） | 硬编码（可被 `VITE_API_BASE` 覆盖） | 有依据（:6-9 注释明写口径三条） |
| V-02 | 其他 | `frontend/vite.config.ts:11` | `DEFAULT_DEV_PORT = 5200` | 前端 dev server 端口默认 | 硬编码（可被覆盖） | 部分依据（同注释） |
| V-03 | 超时 | `frontend/vite.config.ts:33-34` | `timeout: 300000` / `proxyTimeout: 300000` | dev 代理上限（生产不继承） | 硬编码 | **裸数字**（300s 与后端 `total-budget` 同值但无关联说明；见 wait-audit §3.2 引用 R-01） |
| V-04 | 阈值 | `frontend/vite.config.ts:47` | `chunkSizeWarningLimit: 8192` | 构建告警阈值 | 硬编码 | 部分依据（:46 注释解释「SpreadJS 单包 ~4.7MB，4096 阈值不够」） |
| V-05 | 其他 | `frontend/.env.example` | `VITE_DEV_PORT=5200` / `VITE_API_BASE=http://localhost:8081` | 示例环境（入库） | 配置键 | 部分依据（注释说明默认值对齐口径） |
| V-06 | 其他 | `frontend/.env.local` | `VITE_DEV_PORT=5202` / `VITE_API_BASE=http://localhost:18318` | **本机现态**（不入库） | 配置键 | 部分依据（本地调试态，与 CI 的 18330/18399 不同套） |

### 1.6 CI 与脚本内数字

| 编号 | 分类 | 位置 | 现值 | 作用场景 | 可调性 | 依据现状 |
|---|---|---|---|---|---|---|
| S-01 | 超时 | `.github/workflows/ci.yml:34 / :56 / :105 / :257 / :292`（backend / frontend / e2e / docs / scan-history） | `timeout-minutes: 20 / 20 / 25 / 10 / 10` | 各 job 墙钟上限 | 硬编码 | **裸数字**（无注释论证机器时预算） |
| S-02 | 阈值 | `.github/workflows/ci.yml:116-118` | `--health-interval 5s / --health-timeout 3s / --health-retries 12` | redis service 健康检查 | 硬编码 | **裸数字** |
| S-03 | 轮询 | `.github/workflows/ci.yml:184-191` | `for i in $(seq 1 90)` + `sleep 1` = **90s 上限** | 后端就绪等待（`/api/ai/health` 双 true） | 硬编码 | 部分依据（注释说明判定口径与「环境坏 vs 用例失败」的分工；90s 无独立论证） |
| S-04 | 轮询 | `.github/workflows/ci.yml:200-208` | `for i in $(seq 1 60)` + `sleep 1` = **60s 上限** | 种子就绪等待（定义数 ≥ 15） | 硬编码 | 部分依据（注释引 T12-E#8 说明为何不用 health 判定；60s 无论证） |
| S-05 | 超时 | `.github/workflows/ci.yml:161 / :185 / :201` | `curl -m 5` / `-m 3` / `-m 3` | 桩探活 / 健康探活 / 种子探活 | 硬编码 | **裸数字** |
| S-06 | 轮询 | `.github/workflows/ci.yml:160` | `sleep 2` | 桩进程拉起后等待 | 硬编码 | **裸数字** |
| S-07 | 其他 | `.github/workflows/ci.yml:111-112 / :156 / :172` | 端口 `6379 / 18399 / 18330` | 容器/桩/后端端口分配 | 硬编码 | 部分依据（脚本与文档多处口径一致，但无端口规划说明） |
| S-08 | 其他 | `.github/workflows/ci.yml:174-175` | `--app.job.batch-size=10` / `--app.job.demo-batch-delay-ms=150` | E2E 覆写作业分片参数（1/10 / 1/2 of 默认） | 硬编码 | 部分依据（`verify-e2e.ps1:31-32` 注「TC11 断言需分片边界」；150 无独立论证） |
| S-09 | 其他 | `.github/workflows/ci.yml:232 / :345`（upload-artifact ×2） | `retention-days: 7` | 证据归档保留 | 硬编码 | **裸数字** |
| S-10 | 超时 | `scripts/verify-e2e.ps1:157-164` | `for ($i=0; $i -lt 240)` × `400ms` = **96s** | `Wait-Job` 作业终态等待上限（**隐式**，无注释） | 硬编码 | **裸数字**（🔴 与后端 `total-budget` 300s / 前端 `waitForJobFinal` 300s 口径不一致的三套上限）**→ 行尾注记（2026-10-11 批 B / R-96，只追加）**：已改 **120s 显式常量** `$script:JobWaitTimeoutMs`（`Wait-Job` 函数上方，29 处调用零改动）。派生关系（写成关系而非裸数字）：后端看门狗判死窗 = `app.job.heartbeat-interval`(30s) × `watchdog.dead-multiplier`(3) = **90s**；最坏发现延迟 = 判死窗 + `watchdog.scan-period`(10s) ≈ **100s**；本窗口 = 100s + 20s 余量 = **120s ≥ dead-timeout + scan-period + 余量** —— 保证「看门狗判死」新行为在 E2E 内可观测（依据业界依据 §12 心跳 30–60s ×3 / Hangfire 30min，裁决 Q4/Q5）。 |
| S-11 | 超时 | `scripts/verify-e2e.ps1:1181 / :2020 / :2236` | `Wait-Until … 60` = **60s** ×3 | 确认后等待作业创建 | 硬编码 | **裸数字** |
| S-12 | 轮询 | `scripts/verify-e2e.ps1:166-174` | `Start-Sleep -Milliseconds 500` | `Wait-Until` 轮询节拍 | 硬编码 | **裸数字** |
| S-13 | 超时 | `scripts/verify-e2e.ps1:236` | `$cts.CancelAfter(180000)` | `Read-Sse` 读流总预算（未读到终帧也让读循环收摊） | 硬编码 | 部分依据（`GFc-端到端执行验证` 提过「原流 180s 预算」口径；120s 挂起 < 180s 的制约关系成立） |
| S-14 | 超时 | `scripts/verify-e2e.ps1:1810 / :1880 / :2195` | `3500 / 3000 / 3000` ms | `Read-Sse-Brief` 有界读窗 | 硬编码 | **裸数字**（🔴 与 `ConfirmGate` 2s 心跳同量级，窗口过短会漏帧） |
| S-15 | 重试 | `scripts/verify-e2e.ps1:276（默认参数）` | `$maxSuspend = 6` | 一轮对话最多处理几次挂起 | 硬编码 | **裸数字** |
| S-16 | 轮询 | `scripts/verify-e2e.ps1` 全文 | `Start-Sleep -Seconds 2` ×**13 处**；`-Seconds 3` ×1；`-Seconds 1` ×1；`-Milliseconds 500` ×3；`-Milliseconds 400` ×1 | 各用例的固定等待（替代条件等待） | 硬编码 | **裸数字**（🔴 13 处 2s 固定等待 = 时序脆弱面） |
| S-17 | 阈值 | `scripts/verify-e2e.ps1:1387` | 断言 `timeoutSeconds > 0`（**不写死 120**） | 前端工具挂起上限与后端解耦的守卫 | 硬编码 | 有依据（注释明写「不硬编码 120，与 `app.ai.hitl.timeout` 解耦」——**本仓少数「主动避免硬编码」的正面案例**） |
| S-18 | 阈值 | `scripts/verify-e2e.ps1:485 / :501` | 定义数 `≥15`（基座 7 + glm 8）；行数 `CURRENCY=5 / DOC_TYPE=5 / SYS_PARAM=40 / REGION_NETWORK=12 / REGION_TARIFF=20 / METRIC_DICT=30` | 种子数据量断言 | 硬编码 | 有依据（与 `DataSeedRunner` / `GlmSeedService` 种子事实对齐） |
| S-19 | 阈值 | `scripts/ci/stub-upstream.mjs:86-90`（5 条上限） | `MAX_TITLE_CHARS=100 / MAX_OPTIONS=50 / MAX_OPTION_TEXT_CHARS=100 / MAX_PLACEHOLDER_CHARS=100 / MAX_FORM_CHARS=8000` | 桩对 `GenerativeFormRules` 的**副本** | 硬编码（副本） | 部分依据（脚本头 :72-76 明写「自检③已改为读事实源对账」，**同时如实登记残留观察项**） |
| S-20 | 阈值 | `scripts/ci/stub-upstream.mjs:84 / :688 / :696` | `KEY_PATTERN {0,39}`（→40 字符）；`fields 1..20`；`label ≤100` | 桩侧的 schema 校验行内字面量 | 硬编码 | 部分依据（**脚本自己承认**：「③-b 模板校验里的 fields 数量上限（1..20）与 label 长度上限（100）仍是行内字面量，未纳入逐值对账集合」）【实测】 |
| S-21 | 缓冲 | `scripts/ci/stub-upstream.mjs:373 / :426 / :269` | SSE 分片 `{1,24}` 字符；日志 `slice(0,300)`；`slice(0,160)` | 桩的输出分片与日志截断 | 硬编码 | **裸数字** |
| S-22 | 其他 | `scripts/ci/stub-upstream.mjs:383-387` | `prompt_tokens 1200 / completion_tokens 40 / total 1240 / cached 640 / reasoning 16` | 桩伪造的 usage 终帧值 | 硬编码 | **裸数字**（断言依赖它们；与真实模型 token 数无关，属**故意造数**） |
| S-23 | 阈值 | `scripts/ci/stub-routes.json:25-26` | `convergence.maxCallsPerTurn = 6` | 桩单轮最多被调用的工具次数（收敛性硬闸） | 硬编码（JSON 配置） | 部分依据（`note` 说明路由表设计口径；6 无独立论证） |
| S-24 | 缓冲 | `scripts/ci/check-docs.mjs:83` | `maxBuffer: 128 * 1024 * 1024` | `git ls-files` 输出缓冲 | 硬编码 | **裸数字** |
| S-25 | 阈值 | `scripts/ci/check-docs.mjs:92-93 / :108` | 盘符路径 `{1,60}`；`/<[^>]{1,80}>/` | 门禁正则的量词上限 | 硬编码 | 部分依据（注释说明「防拆形绕过」，量词上限本身无论证） |
| S-26 | 阈值 | `scripts/ci/check-docs.mjs:338-339` | `NAMING_SEEDS_CONCAT_MIN = 5` / `NAMING_SEEDS_SEPARATED_MIN = 24` | 自检种子数量下限 | 硬编码 | 有依据（:336-337 注完整论证：**「与数组长度解耦，删种子行会被下限抓住」**） |
| S-27 | 阈值 | `scripts/githooks/scan-lib.sh:67-85` | 各密钥前缀后 `{20,}/{30,}/{16}/{36}` 等 15 条长度阈值 | 脱敏规则的最小长度 | 硬编码 | 部分依据（各 token 由真实规格决定，属**外部事实**而非拍脑袋；但仓内未逐条注明来源） |
| S-28 | 缓冲 | `scripts/githooks/scan-lib.sh:186-187` | `length(text) > 4000` 跳过；`<[^>]{1,80}>` 剥离 | 扫描时的预处理阈值 | 硬编码 | **裸数字** |
| S-29 | 缓冲 | `scripts/githooks/scan-history.sh:21` | `max_size_kb=2048` | 跳过大 blob 的阈值 | 硬编码 | 部分依据（:13 用法示例给了 `--max-size 512`，说明可调；默认 2048 无论证） |
| S-30 | 超时 | `scripts/m2t2a-smoke.mjs:273 / :362` | `AWAIT_MS = 240_000` / `deadline = Date.now() + 150_000` | 等前端工具帧 / 等回灌收敛 | 硬编码 | **裸数字**（🔴 240s > 后端 `hitl.timeout` 120s，意味着该断言**必然**靠超时收口） |
| S-31 | 重试 | `scripts/m2t2a-smoke.mjs:143 / :239` | 合成帧 `timeoutSeconds: 120, expiresAt: Date.now()+120000`；轮询 `12 × 500ms` | 手造挂起帧 / 等 cancelled 收敛 | 硬编码 | **裸数字**（🔴 与 `S-17` 的「不硬编码 120」纪律**相反**：这里把 120 写死了） |
| S-32 | 轮询 | `scripts/m2t2a-smoke.mjs:91 / :131 / :160 / :226 / :303 / :338 / :347 / :417` | `sleep 500 / 500 / 400 / 600 / 500 / 500 / 1500 / 1500` ms | CDP 操作与等待节拍 | 硬编码 | **裸数字** |
| S-33 | 其他 | `scripts/m2t2a-smoke.mjs:63 / :79` | `SMOKE_CDP_PORT 9333`；`--window-size=1400,1800` | CDP 端口与浏览器窗口 | 硬编码（端口可用 env 覆写） | **裸数字** |

### 1.7 spike（`spike/` 下四个独立试验工程 —— **非产品路径**，单列备查）

| 编号 | 分类 | 位置 | 现值 | 作用场景 | 可调性 | 依据现状 |
|---|---|---|---|---|---|---|
| SP-01 | 超时 | `spike/sp01ab/.../application.yml:22 / :24 / :26 / :6` | `frontend-tool-timeout-seconds 60` / `confirm-timeout-seconds 30` / `heartbeat-ms 2000` / `max-messages 40` | SP-01a/b 试验配置 | 配置键 | **裸数字**（无注释） |
| SP-02 | 超时 | `spike/sp01c/.../application.yml:12 / :23 / :24` | `redis timeout 3s` / `ttl 6h` / `max-messages 40` | SP-01c 试验配置（**注：redis 3s 与产品 2s 不同**） | 配置键 | **裸数字** |
| SP-03 | 重试 | `spike/sp01d/.../application.yml:23-25 / :30` | `first-event-timeout 5s` / `inter-event-timeout 6s` / `max-attempts 2` / `threads 8` | SP-01d 韧性试验（**5s/6s 是产品 30s/90s 的试验值**） | 配置键 | **裸数字** |
| SP-04 | 池 | `spike/sp02/.../application.yml:23-35` | `frontend-tool-timeout-seconds 120` / `confirm-timeout-seconds 120` / `exec-delay-ms-after-approve 0` / `run-ttl-hours 6` / `max-rounds 5` / `suspend-pool-size 16` | SP-02 试验配置 | 配置键 | **裸数字**（`max-rounds 5`、`suspend-pool-size 16` 与产品值不同） |

---

## 2. 硬编码裸数字专组（**裁决重点**）

> 定义：`可调性 = 硬编码`（含副本、注解内、框架缺省）**且** `依据现状 = 裸数字`。以下按影响面从大到小排列。

| # | 位置 | 值 | 为什么是裁决重点 |
|---|---|---|---|
| H-01 | `frontend/src/api/http.ts:85` | `60000` | **一刀切**：既是全部业务 REST 上限，又是「前端工具预算折算」的减数，还是 `DOWNLOAD_FETCH_TIMEOUT_MS` 的对齐来源。改它牵动三处。wait-audit §3.3 第 2 项已点。 |
| H-02 | `backend/.../ai/tools/AiTools.java:139` | `.limit(10)` | AI 主链路上「`list_tasks` 让模型看见多少任务」被一个无注释的 10 决定，**无分页、无告知、无截断标记** —— 模型会把不完整清单当作完整清单。全仓唯一此类硬截断。 |
| H-03 | `backend/.../config/AsyncConfig.java:44-46` | `4 / 16 / 200` | 全部业务作业的并发上限；类注释只有定性论证。wait-audit R-68 已登记「与连接池不匹配」。 |
| H-04 | 无配置（框架缺省） | Hikari≈10 连接 / multipart 1MB·10MB / Tomcat max-threads 200 | 全仓**完全没出现**：连接池容量、上传文件大小硬顶、容器并发都是框架默认。前者与 `AsyncConfig` 16 线程不匹配；后者决定「多大 xlsx 传不上来」这条产品级边界。 |
| H-05 | `scripts/verify-e2e.ps1:1810 / :1880 / :2195` | `3500 / 3000 / 3000` ms | `Read-Sse-Brief` 有界读窗。后端 `ConfirmGate` 心跳 2s/帧 ⇒ 3s 窗口**只兜得住 1 个心跳周期**，抖动即漏帧；反之窗口调大又拖长每个用例。门禁可信度直接挂在这三个数上。 |
| H-06 | `scripts/verify-e2e.ps1`（13 处） | `Start-Sleep -Seconds 2` | 用**固定等待**替代条件等待，是 E2E 时序脆弱的根源（注释里已声明理由的地方 ≤3 处）。 |
| H-07 | `backend/.../application.yml:180-181` | `demo-batch-delay-ms 300` / `batch-size 100` | 直接决定产品可见的作业进度节拍与分片边界；CI 里被改成 150/10 才能过用例（S-08），说明这两个数是**为了测试而调**的，而产品默认值本身无依据。 |
| H-08 | `ai/config/RedisAvailability.java:52` 与 `ai/run/CancellationRegistry.java:67` | 两处 `5000L` | **同值不同义**：一个是 Redis 不可用的负缓存 TTL，一个是降级期取消标志轮询退避。同值极易被误读为同源；两者的最优值应随 Redis 恢复检测需求分别定。 |
| H-09 | `frontend/src/api/tasks.ts:106` | 无（浏览器默认 retry ≈3s） | 任务进度 SSE 通道**完全没接管重连**，与 AI 通道 `api/sse.ts` 的显式 `maxReconnects/backoff/connectTimeout` 三件套严重不对称 —— 同一个工程里两套不同的失败恢复策略，而这一套是「靠浏览器」。 |
| H-10 | `utils/excel.ts:27 / :847 / :309-310` + `ExcelWriter.java:31` + `ExcelTemplateBuilder.java:107` | `200 / 3000ms / 2000-2099 / 100 / 1000` | Excel 面（默认行数、blob 回收延迟、日期范围、流式写窗口、模板校验覆盖行数）**全是裸数字**，且彼此无口径说明。产品的主工件就是 xlsx，这一面完全没有取值记录。 |

**次一级（已列但影响面较小）**：`B2-13` TAIL_SIZE 6、`B2-22/23` ContextBuilder 300/800、`B2-24` DIGEST_BUFFER 8192、`B2-27/28` 双 1024、`B2-29` CANCEL_TTL 1h、`B2-31` 分页默认 50/50/10、`F1-03` MAX_RECONNECTS 2、`F1-07/08/14` jobs 轮询/超时、`F1-17` 契约版本、`F1-23` PAGE_READY 3s、`F1-24` 50ms、`F1-26` 300ms、`F1-29` 60ms、`F1-31/32`、`F2-01/02/03/04/06/07`、`V-03` proxy 300s、`S-01` 各 job 分钟数、`S-02/05/06/09/11/12/15`、`S-21/22`、`S-24/28`、`S-30~S-33`。

---

## 3. 统计

**断言：【实测】**（对 `清单总账.md` 表格逐行机读统计）

### 3.1 总量

| 指标 | 值 |
|---|---|
| **表行（条）总数** | **145** |
| 其中分组行含多条 | 3 行（B2-21 含 10 项、B2-31 含 3 项、B2-32 含 3 项） |
| **折算独立数值总数** | **≈ 158** |
| 断言来源 | 【实测】 |

> 口径说明（2026-10-21 只追加）：本节分母 = **2026-10-10 首次普查时的表体**（145 行）。此后 §7 追加
> 16 行（上下文键 13 + 常量 3）、本次追加 §1.1b 的 7 行（代码缺省键）**均不并入本节**——保留历史口径，
> 避免「统计结论」随追加批次来回漂移。现表体行数用 `grep -c '^| B[0-9]-'` 复核（本批追加后 = 86 行，
> 其中 §1.1b 新增 7 行）；**本节 145 这个分母不随之改写**。

### 3.2 按分类分布

| 分类 | 条数 | 占比 |
|---|---|---|
| 超时 | 33 | 22.8% |
| 缓冲 | 20 | 13.8% |
| 轮询 | 17 | 11.7% |
| 其他 | 14 | 9.7% |
| 阈值 | 13 | 9.0% |
| 池 | 11 | 7.6% |
| 上限 | 8 | 5.5% |
| 分页 | 7 | 4.8% |
| 重试 | 6 | 4.1% |
| TTL | 6 | 4.1% |
| 退避 | 5 | 3.4% |
| 防抖 | 2 | 1.4% |
| 心跳 | 2 | 1.4% |
| 框架缺省 | 1 | 0.7% |
| **合计** | **145** | **100%** |

> 任务清单点名的维度全部覆盖：重试 6 / 退避 5 / 轮询 17 / 池 11 / 分页 7 / 缓冲 20 / 心跳 2 / TTL 6 / 阈值 13。

### 3.3 硬编码 vs 配置键

| 可调性 | 条数 | 占比 |
|---|---|---|
| **硬编码**（含副本 / 注解内 / JSON 配置 / 不可覆写） | **107** | **73.8%** |
| **配置键**（`application.yml` 或可被 env/参数覆写） | **37** | **25.5%** |
| 框架缺省（本仓未出现） | 1 | 0.7% |

**结论**：本仓的「数字规格」里，**约四分之三改不了**。且集中在**前端全部业务常量**（32 条中仅 6 条算配置键，其余全硬编码）与**脚本/CI 全部数字**（33 条全硬编码）。

按层拆分：

| 层 | 条数 | 硬编码占比 |
|---|---|---|
| 后端配置键（B1） | 31 | 0%（全可调） |
| 后端硬编码（B2） | 32 | 31/32 = **96.9%**（唯一例外是框架缺省行） |
| 前端代码（F1） | 32 | 32/32 = **100%** |
| 前端视图（F2） | 7 | 7/7 = **100%** |
| 环境/构建（V） | 6 | 4/6 = 66.7% |
| CI/脚本（S） | 33 | 33/33 = **100%** |
| spike（SP） | 4 | 0%（全可调，但非产品路径） |

> **口径提醒**：后端「改配置即可」是**名义**上的可调 —— 但 `application.yml` 里绝大多数键没有注释，且多个键的取值论证只存在于 `docs/evidence/` 的历史证据文档中。**运维手上只有数字，没有依据。**

### 3.4 有依据 vs 裸数字

| 依据现状 | 条数 | 占比 |
|---|---|---|
| **裸数字**（无注释、无文档、无实测） | **72** | **49.7%** |
| 部分依据（说明用途/对齐来源，取值无论证） | 49 | 33.8% |
| 有依据（有数值论证 / 实测 / 正式裁定） | 23 | 15.9% |
| 其他（跨行引用） | 1 | 0.7% |

**结论**：**近一半的数字规格没有任何依据可查**；有实证/裁定的只有约六分之一，且高度集中在 AI 韧性包与 SSE 写侧（`B1-10/24/25/26/27/28`、`B2-01/02/07/09`、`B1-02`）—— 因为那几个棒次做过压测（`docs/evidence/M2-T3-5-压测证据-DS-V4-Flash.md`）并写了长注释。

按层看「裸数字率」：

| 层 | 裸数字 | 占该层 |
|---|---|---|
| 后端配置键（B1） | 8 / 31 | 25.8% |
| 后端硬编码（B2） | 18 / 32 | 56.3% |
| 前端代码（F1） | 15 / 32 | 46.9% |
| 前端视图（F2） | 6 / 7 | 85.7% |
| 环境/构建（V） | 2 / 6 | 33.3% |
| CI/脚本（S） | 22 / 33 | 66.7% |
| spike（SP） | 4 / 4 | 100% |

**脚本与 CI 是裸数字密度最高的一层**（三分之二），而**它恰好是所有其他结论的证据生产线**。

---

## 4. 同值 / 同源一致性核对【实测】

> 判定口径：**同源** = 代码/注释明确声明「取自同一常量」或「刻意对齐」；**巧合** = 同值但无逻辑关系。

### 4.1 同源（可接受，但需在通道特性变化时复核）

| 值 | 出现点 | 判定 |
|---|---|---|
| `15s` | `SseWriteBudget.DEFAULT_TIMEOUT_MILLIS`（B2-01）→ `app.ai.sse.write-timeout`（B1-27）→ `app.task.sse.write-timeout`（B1-28） | **同源**（B1-28 注释明写「同一缺省来源」）；但两通道负载特性不同，wait-audit §3.3 第 5 项已提复核 |
| `2s` | `ConfirmGate.HEARTBEAT_SECONDS`（B2-11）→ spike `heartbeat-ms`（SP-01） | **同源**（spike 移植） |
| `40` | `app.ai.memory.max-messages`（B1-08）→ spike `max-messages`（SP-01/02）→ `stub-upstream KEY_PATTERN {0,39}`（S-20） | **同源**（同一窗口约束的移植与副本） |
| `8000` | `GenerativeFormRules.MAX_FORM_CHARS` / `MAX_RESULT_CHARS`（B2-21）→ `stub-upstream MAX_FORM_CHARS`（S-19） | **同源**（且有 `--self-check` 逐值对账） |
| `10` | `TaskController` 分页默认（B2-31）→ `stores/task.ts:65 size`（F1-12） | **同源**（前后端约定） |
| `50` | `JobController` 分页默认（B2-31）→ `stores/task.ts:275 loadIssues size`（F1-13） | **同源** |
| `120s` | `app.ai.hitl.timeout`（B1-09）→ `m2t2a-smoke.mjs:143 timeoutSeconds`（S-31） | **同源但反向**：S-17 的纪律是「不硬编码 120」，而 smoke 脚本恰恰写死了 120 ⇒ **同一仓内两种相反做法** |
| `60s` | `http.ts`（F1-01）← `FRONTEND_TOOL_RESULT_HEADROOM_MS`（F1-20）← `READY_TIMEOUT_MS`（F1-25）← `DOWNLOAD_FETCH_TIMEOUT_MS`（F1-15） | **同源**（后三者注释均写「取 `http.ts` 的 60s」）；⇒ **F1-01 是这一整条链的根**，根是裸数字 |
| `1024` | `ExportJobRunner.ISSUE_MESSAGE_MAX`（B2-27）→ `ValidationIssue @Column(length=1024)`（B2-28） | **疑同源但未声明**：两处独立字面量，改一处会静默截断/超列。**建议核实并合并** |

### 4.2 数值巧合（**同值不同义，极易误读**）

| 值 | 出现点 | 语义差异 |
|---|---|---|
| `120s` | `server.tomcat.connection-timeout`（B1-01）/ `app.ai.hitl.timeout`（B1-09）/ smoke 合成帧（S-31）/ spike sp02 | 容器空闲超时 vs 人的决策等待 vs 测试造数 —— wait-audit §3.3 第 4 项已登记 |
| `300s` | `app.ai.resilience.total-budget`（B1-14）/ `api/jobs.ts timeoutMs`（F1-08）/ `vite.config.ts` proxy（V-03） | 轮次预算 vs 作业等待 vs dev 代理 —— **三处同值，零关系** |
| `3000` | `JOB_FALLBACK_POLL_MS`（F1-10）/ `PAGE_READY_TIMEOUT_MS`（F1-23）/ `Read-Sse-Brief 3000`（S-14）/ `RunStore.EVENT_WINDOW`（B2-17） | 轮询间隔 / 就绪等待 / 读流窗口 / **帧数**（量纲都不同！） |
| `5000` | `STREAMING_HEARTBEAT_MILLIS`（B2-12）/ `NEGATIVE_TTL_MS`（B2-15）/ `DEGRADED_POLL_BACKOFF_MS`（B2-16）/ `MIN_FRONTEND_TOOL_TIMEOUT_BUDGET_MS`（F1-21） | 心跳节拍 / Redis 负缓存 / 降级退避 / 预算下限 |
| `1000` | `TICK_MILLIS`（B2-10）/ `jobs.ts intervalMs`（F1-07）/ `AiPanel tick`（F1-28）/ `DEFAULT_RECONNECT_DELAY_MS`（F1-04） | 看门狗节拍 / 作业轮询 / UI 倒计时 / 重连首退避 |
| `65536` | `app.ai.frame.max-bytes`（B1-24） | 唯一，无冲突 |
| `8192` | `DIGEST_BUFFER_BYTES`（B2-24）/ `chunkSizeWarningLimit`（V-04） | IO 缓冲 vs 构建告警阈值 —— 同值零关系 |
| `8000` | `app.ai.tool-result.max-chars`（B1-23）/ `GenerativeFormRules.MAX_FORM_CHARS`·`MAX_RESULT_CHARS`（B2-21） | 「回填模型的工具结果上限」vs「表单序列化长度上限」—— **语义无关却同值**，两处独立字面量，改一处不影响另一处 |
| `60s` | `Wait-Until 60`（S-11）/ spike `frontend-tool-timeout-seconds: 60`（SP-01） | 与 4.1 的 60s 链**无关系**，是第四/第五个 60 |
| `100` | `app.job.batch-size`（B1-31）/ 表单 4 项 `*_CHARS=100`（B2-21）/ `ImportWizard slice(0,100)`（F2-05）/ `SXSSFWorkbook(100)`（B2-25）/ `stub MAX_TITLE_CHARS`（S-19） | 分片大小 / 字符上限 / 预览行数 / 流式写窗口 —— **八个 100，五种语义** |
| `200` | `app.ai.tool-result.max-rows`（B1-22）/ `DEFAULT_SHEET_ROWS`（F1-30）/ `getIssues size 200`（F2-07） | 回填模型行数 / 表格默认行数 / 分页页大小 |
| `20` | `app.ai.suspend.pool-size`（B1-10）/ `MAX_FIELDS`（B2-21）/ `DataBrowserView size`（F2-01）/ `stub fields 1..20`（S-20） | 线程数 / 字段数 / 页大小 / 校验上限 |
| `10` | `AiTools limit(10)`（B2-30）/ `resume.max-on-startup`（B1-17）/ `CPUS_PER_THREAD_BUDGET`（B2-09）/ `TaskCenter page-sizes[0]`（F2-03） | 任务条数 / 续跑轮数 / 容量系数 / 页选项 |
| `300` | `demo-batch-delay-ms`（B1-30）/ `mirrorTimer 300ms`（F1-26）/ `EXTRA_VALUE_MAX_CHARS`（B2-22） | 节拍 ms / 防抖 ms / 字符数（量纲不同） |

**结论**：`60s` 出现 5 次、`3000` 4 次、`5000` 4 次、`1000` 4 次、`120s` 4 次、`100` 8 次 —— 仓内**没有「同一量纲的取值收敛说明」**，读者只能靠逐处读注释来区分「刻意同源」与「纯属巧合」。

### 4.3 前后端/多端口径不一致（**已发现的实际冲突**）

| 项 | 前端 | 后端 | 冲突 |
|---|---|---|---|
| 数据浏览器页大小 | `DataBrowserView.vue:185 size=20`（F2-01） | `DataController` 默认 `size=50`（B2-31） | **同一个资源两套默认页大小**（前端总显式传参故未爆，但口径已分叉） |
| 作业等待上限 | `api/jobs.ts timeoutMs=300_000`（F1-08） | 无对应（作业侧无超时） | 前端 300s vs E2E `Wait-Job` 96s（S-10）—— **同一场景三个上限：96s / 300s / 后端无** → **批 B 更新（2026-10-11 R-96，只追加）**：三上限已变为 **E2E 120s（显式派生：判死窗 90s + 扫描周期 10s + 余量 20s，见 S-10 行尾注记）/ 前端 300s（维持，`api/jobs.ts` 未动）/ 后端作业看门狗（新：`app.job.heartbeat-interval` 30s × `dead-multiplier` 3 = 90s 判死窗 + `max-execution-time` 30m 绝对上限，四键启动期 fail-fast 校验）** —— 「后端无」终结，口径闭环 |
| 任务通道重连 | `api/tasks.ts` 无（F1-09） | 无 | AI 通道有 `maxReconnects=2`+退避+建连看门狗；任务通道什么都没有 |
| 前端工具预算来源 | `timeoutSeconds` 帧值（F1-19/20） | `app.ai.hitl.timeout` 120s（B1-09） | E2E 已用「断言 >0 而非 ==120」解耦（S-17），smoke 却写死 120（S-31） |
| 会话锁 TTL | — | `lock.ttl=10m`（B1-20）自称「= 轮上限」，轮上限实为 `total-budget=300s`（B1-14） | **同一仓内两个「轮上限」口径**（wait-audit §3.3 第 7 项） |
| 上传大小硬顶 | — | 未配置 ⇒ Spring 默认 1MB/10MB（B2-32） | 产品主工件是 xlsx，1MB 单文件顶是**隐式产品边界** |
| 连接池 vs 作业池 | — | Hikari≈10（缺省）vs `AsyncConfig max=16`（B2-05） | wait-audit R-68 已登记 |

---

## 5. 优先裁决建议（**Top 5**）

> 选取原则：① 影响面（牵动多少下游）② 是否位于「证据生产线」上 ③ 是否已有其他登记的关联（避免重复立项）。

| 优先级 | 条目 | 一句话理由 |
|---|---|---|
| **1** | `frontend/src/api/http.ts:85` 的 `60s`（H-01 / F1-01 / wait-audit §3.3 第 2 项） | 它是**四个前端超时常量的根**（回灌余量、表格就绪、下载上限、工具预算折算），且是**裸数字**—— 根一动，整条链的数学保证全部平移，是投入产出比最高的一处裁决。 |
| **2** | `ai/tools/AiTools.java:139` 的 `.limit(10)`（H-02 / B2-30） | AI 主链路上**唯一**把「模型可见数据量」写死的地方，无分页、无截断标记、无告知 —— 模型会把不完整清单当完整清单，直接影响 AI 决策质量，且改成参数化几乎零风险。 |
| **3** | `app.ai.hitl.timeout` 120s 三场景共用 + 其下游 2s/5s 心跳（B1-09 / B2-11 / B2-12 / wait-audit §3.3 第 1 项） | 「人的决策延迟」与「机器回执延迟」混在同一键上，而前端预算折算、挂起心跳、emitter 寿命、写超时全部以它为锚；拆键是批 A（R-95）的**必要收尾**，不拆则改造不闭环。 |
| **4** | 容量联动链上的四组裸数：`AsyncConfig 4/16/200`（H-03）+ Hikari≈10（H-04）+ `boundedElastic=10×CPU`（B2-09）+ `suspend.pool-size=20`（B1-10） | 一条 `专用池×2 + 投递池 ≤ 10×CPU` 的容量式上挂了四个数，其中**两个是裸数字/框架缺省**，且 `M2-T3-5` 已实测「4 核机 48 > 40 不成立」—— 压测只覆盖了有注释的那两个，没覆盖作业池与连接池。 |
| **5** | E2E/脚本时序数字：`Read-Sse-Brief 3000ms`（H-05）+ 13 处 `Start-Sleep 2s`（H-06）+ smoke `AWAIT_MS 240s`（S-30） | 这三个数是**全项目结论的证据生产线**：3000ms 窗口对 2s 心跳只兜一个周期、13 处固定等待靠运气、240s 超过后端 120s 上限意味着该断言**必然靠超时收口**（恒真断言）。门禁的可信度本身需要用这些数的裁决来加固。 |

---

## 6. 本次排查的方法与覆盖面（供复核）

### 6.1 方法

1. **实读现态**：`git status` 确认工作树在制品，逐行实读**磁盘现态**而非 HEAD。批 A 新增的 `DOWNLOAD_FETCH_TIMEOUT_MS`（F1-15）已计入并标注。
2. **机械扫描**：对下表模式做全仓 grep（排除 `.git` / `node_modules` / `target` / `dist`）。

| 扫描面 | 命中模式 |
|---|---|
| 后端 Java（112 个主源文件） | `Duration.of*` / `TimeUnit.*` / `*_MILLIS` / `*_SECONDS` / `static final (int\|long\|Duration)` / `ThreadPoolTaskExecutor` / `setCorePoolSize` / `setQueueCapacity` / `new ArrayBlockingQueue` / `SynchronousQueue` / `@Scheduled` / `fixedDelay` / `@RequestParam(defaultValue=…)` / `PageRequest.of` / `Thread.sleep` / `.limit(n)` / `setMaxInMemorySize` / `MultipartFile` |
| 前端 TS/Vue（46 个文件） | `setInterval` / `setTimeout` / `axios timeout` / 全部 `_MS` 常量 / `EventSource` / `slice(0,n)` / `page-size` / `page-sizes` / `maxlength` / 退避公式 |
| 配置 | `application.yml`（189 行全读）/ `.env.example` / `.env.local` / `vite.config.ts` / `backend/config/application.yml.example` |
| CI/脚本 | `ci.yml` / `verify-e2e.ps1` / `stub-upstream.mjs` / `stub-routes.json` / `check-docs.mjs` / `scan-lib.sh` / `scan-history.sh` / `m2t2a-smoke.mjs` / `build.mjs` |
| 试验工程 | `spike/sp01ab·sp01c·sp01d·sp02` 的 `application.yml` |
| 文档 | `docs/` 下 `03-技术方案文档-v2.1.md`、`docs/evidence/M2-T3-5-压测证据-DS-V4-Flash.md`、`docs/adr/*`（用于判定「依据现状」） |

3. **依据判定**：对每条回溯其所在文件的注释与 `docs/` 引用，分为 有依据 / 部分依据 / 裸数字。
4. **统计**：报告表格行首统一编码（`B1-`/`B2-`/`F1-`/`F2-`/`V-`/`S-`/`SP-`），机读聚合得到 §3 全部数字。

> ### ⚠ 并发修改如实登记【实测】
>
> 本报告运行期间，**批 A 施工（并行任务）在同一工作树上持续写入**，实读到的在制品集合发生了变化：
>
> | 时点 | 工作树在制品 |
> |---|---|
> | 开工（`git status` 首读） | 6 个 `M`：`ConfirmGate.java` / `docs/M2-排期计划.md` / `docs/requirements.md` / `frontend/src/stores/workspace.ts` / `frontend/src/utils/frontend-tools.ts` / `frontend/src/views/ExportWizardView.vue` |
> | 收工（复核） | 7 个 `M`（新增 `ai/config/AiProperties.java`）+ 1 个 `??`（新增测试 `ConfirmGateFrontendTimeoutTextTest.java`） |
>
> **对本次结论的影响：无。** 逐一比对 `git diff` 确认增量只有**注释与文案**（`AiProperties.java:120` 为 `app.ai.hitl.timeout` 补了「两个场景结局语义不同」的 javadoc；`ConfirmGate.java` 改了一句超时文案），**未改动任何数字规格的取值**（`git diff --stat` 从 `+214/-37` 变为 `+220/-38`，增量 6 增 1 删全部落在注释/文案）。因此 §1 表中所有**现值**在收工时点仍然成立。
>
> 唯一受影响的是 **B1-09 的「依据现状」**：它现在多了一段注释说明「结局语义」，但**仍未对 120s 这个取值给出数值论证** —— 判定维持 `部分依据`。
>
> 复核时点：HEAD 仍为 `6ee96c3`（未提交）。

### 6.2 明确不计入的

- **纯布局/样式数字**（`width` / `px` / `max-height` / `:image-size` / Tailwind 类名）—— 非等待/容量语义。
- **数据种子值与断言期望值**（`DataSeedRunner` 的税率 13.0/9.0、种子行数 5/12/40、E2E 的 15 个定义等）—— 属业务数据事实，非「规格」。唯一例外：S-18 因它是「跨文件一致性断言」而列入。
- **依赖版本号**（`pom.xml` 的 Spring Boot 3.5.14 / POI / `package.json` 的 `^17.1.5` 等）—— 属依赖管理，非运行期规格。
- **HTTP 状态码 / 错误码 / 枚举**（400/409/503、`TIMEOUT`/`REJECTED` 等）—— 属契约，非可调数字。

### 6.3 可能遗漏的面（如实登记）

- **`docs/` 内文档自身的数字**（如 `03-技术方案文档` §549 行的键表、各 evidence 文档的实测值）未逐条登记 —— 它们是依据来源而非配置项，但在裁决时应作为「业界对照」之外的**仓内既有论证**一并读。
- **`docs/evidence/` 中可能存在的、已废弃键的历史值**未追查（例如曾出现过后来被删的 `app.ai.first-event-timeout`）。
- **运行时实际生效值**未验证（未启服务读 `/api/ai/health` 的 `sseDelivery`/`heartbeatIntervalMs` 回灌键）—— 本报告只覆盖**静态源码/配置面**。
- **测试代码**（`backend/src/test/**`）内的数字未纳入 —— 它们是断言值而非产品规格，但 `S-17` 那类「主动避免硬编码的守卫」如果只存在于测试中，会构成另一类风险。

## 7. 追加登记：上下文统计与压缩专项批①（2026-10-21）

> 登记范围 = `app.ai.context.*` 全部新增键（施工 = `backend/src/main/java/com/example/configmgr/ai/config/AiProperties.java`
> 的 `Context` 内部类 + `application.yml` 同段注释）。**设计稿 §3.7 的标题写「11 键」而表内实际列了 13 行**，
> 本批按表实现 **13 个键**并逐行登记（数字口径以本表为准，标题的「11」属设计稿笔误，已在施工报告登记）。
> 依据链：`docs/research/调研-上下文统计与压缩业界惯例-2026-10-21.md` §②（索引第 31 件）→
> `docs/research/设计-上下文统计与压缩专项-2026-10-21.md` §3.7（索引第 34 件）。

| 编号 | 分类 | 键名 | 现值 | 作用场景 | 可调性 | 依据现状 |
|---|---|---|---|---|---|---|
| B1-41 | 容量 | `app.ai.context.limit-tokens` | `128000` | 上下文上限（占用条的分母）的**过渡键**；模型配置专项的 `ai_model.max_context` 落地后由它覆盖（唯一消费点 = `ContextLimitResolver`）。`0` = 主动声明未知（降级态） | 配置键 | 有依据（本仓默认模型 128K 窗口；设计稿 §3.2「波次 1 分母」）。**待模型配置批②③ 接线后复核**：届时本键只是回落值 |
| B1-42 | 缓冲 | `app.ai.context.reserve-tokens` | `13000` | 预留输出缓冲；比例阈值对 `limit − reserve` 取比 | 配置键 | 有依据（调研 §① Claude Code 的 13K；覆盖 `max-tokens 4096` + 一轮工具回环余量） |
| B1-43 | 阈值 | `app.ai.context.l1-percent` | `70` | L1（工具结果清除）触发线 | 配置键 | 有依据（调研 Q2 谱系 70–75 取中值） |
| B1-44 | 阈值 | `app.ai.context.l2-percent` | `80` | L2（滑窗 + 递归摘要）触发线（批③ 已消费：`ContextCompactor` 逐级升格，每次最多一级） | 配置键 | 有依据（同上 80–85 取中值） |
| B1-45 | 阈值 | `app.ai.context.l3-percent` | `95` | L3（硬截断兜底）触发线（批③ 已消费：`HardTrim` 兜底） | 配置键 | 有依据（同上 95）；自洽性 = 128K 下 `0.05 × 115K = 5.75K > max-tokens 4096` |
| B1-46 | 阈值 | `app.ai.context.hysteresis-percent` | `60` | 滞回：压缩后 `used / limit < 60%` 才允许再次触发 | 配置键 | 有依据（调研 Q4「压缩到 60% 以下才允许再次触发」）；启动期校验要求 ≤ `l1-percent`（否则抖动链） |
| B1-47 | pin | `app.ai.context.keep-tool-pairs` | `5` | L1 不清理的最近工具结果对（**按 `ToolResponseMessage` 里的 response 条数计**，不是按消息条数：一条 tool 消息可含多份响应，按消息计会把 pin 窗口放大成 N 倍） | 配置键 | 有依据（调研「保留最近 3–5 对」**取上限** = 保守） |
| B1-48 | pin | `app.ai.context.keep-recent-turns` | `6` | L2 逐字保留的最近轮数（批③ 已消费：`RollingSummarizer`） | 配置键 | 有依据（调研「保留最近 6–8 轮」**取下限** = 省 token 优先） |
| B1-49 | 缓冲 | `app.ai.context.chunk-tokens` | `8000` | 递归摘要的分块大小（批③ 已消费：分块 + 递归合并，单请求正文 ≤ 本值） | 配置键 | 有依据（设计稿 §3.7：必须小于 `reserve-tokens`，保证摘要请求自身不越窗） |
| B1-50 | 超时 | `app.ai.context.summary-timeout` | `60s` | 一次摘要请求的硬上限（批③ 已消费：`ContextSummaryClient` 到点中断） | 配置键 | 部分依据（设计稿 §3.7 缺省；**无压测数据**，与 `first-byte-timeout 30s` 同量级 + 摘要多分块余量） |
| B1-51 | 熔断 | `app.ai.context.fuse-threshold` | `3` | 连续失败几次熔断（批③ 已消费：`CompactionFuse`，只被 L2/L3 摘要尝试驱动） | 配置键 | 有依据（调研 §①「连续失败 3 次熔断」） |
| B1-52 | 熔断 | `app.ai.context.fuse-cooldown` | `10m` | 熔断冷却窗（到点半开重试一次，不升指数） | 配置键 | 部分依据（设计稿 §3.7 缺省；取值意图 = 「一次失败不阻塞太久」+「不放大上游长尾」，无数据） |
| B1-53 | 模型 | `app.ai.context.summary-model` | `""` | 递归摘要用模型；空 = 主模型（D3 裁决） | 配置键 | 有依据（D3：本仓当前只有一套 provider 配置，`summary-model` 留空即主模型；模型配置专项落地后改配置即可切） |

**本批另有的三个非配置常量**（一并登记，避免"只登记配置文件里的"这种口径漏洞）：

| 编号 | 分类 | 位置 | 现值 | 作用场景 | 依据现状 |
|---|---|---|---|---|---|
| B1-54 | 估算 | `ai/context/ContextEstimator.java` `CJK_CHARS_PER_TOKEN` | `1.7` | 中文折算 token 的除数 | 有依据（调研 §②「中文按字符/1.6~1.8 兜底」取中值） |
| B1-55 | 估算 | 同上 `OTHER_CHARS_PER_TOKEN` | `4.0` | 其余字符折算 token 的除数 | 有依据（GPT 系分词器 4 字符/token 的通行经验值） |
| B1-56 | 容量 | `ai/run/RunStore.java` `CONTEXT_LEDGER_MAX` | `20` | 压缩台账保留条数 | 有依据（设计稿 §3.6「最近 20 条」） |

**自洽性复核（与既有数字的关系）**：`reserve-tokens 13000` 覆盖 `spring.ai.openai.chat.options.max-tokens 4096`
（B1-06）；L3 触发线在 128K 下剩余 `5.75K`，仍大于 4096 ⇒ **触发线不会把输出空间吃掉**。
换更小窗口模型（如 32K）时必须重算这两条不等式 —— 已写进 `AiProperties.Context` 的类注与 `application.yml` 同段注释。
