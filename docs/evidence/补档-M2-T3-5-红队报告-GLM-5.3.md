> 来源=仓库外过程报告补档（2026-10-11，DC-19 前历史文件，署名信息以文内为准）

# M2-T3-5 压测报告 红队攻击报告（GLM-5.3）

> 红队棒：GLM-5.3｜日期：2026-10-09
> 攻击对象：`<SCRATCH-REPORTS>\T3-5-压测报告-DS-V4-Flash.md`（下称"主报告"）+ 同目录 15 个 `T3-5-附件-*` 文件
> ＋ 主仓库在途产物（`<MAIN_REPO>` = `<REPO_ROOT>`，HEAD=1a28216，只读）
> 红队复现产物：`<SCRATCH-REDTEAM>\`（mvn-test.log / probe/ / setfail/）
>
> **总裁决：9 条核心结论（S1–S9）零条被击破，无 S1 阻断级发现——按任务卡判据红队未胜。
> 但击伤 4 条 S2 重要级发现（3 条报告证据纪律错误 + 1 条风险登记域扩大）与 8 条 S3 观察级发现，
> 建议修正 F1–F3 文字错误、将 F4/F6/F7 补进观察项后再进回灌流程。**

---

## 0. 攻击方法与硬约束遵守

| 攻击面 | 手段 | 执行者 |
|---|---|---|
| S6 SET 失败注入口径 | 读码（`RunStore#recordIssuedSeq`）+ **自建假 RESP2 服务器**注入命令级失败（READONLY / OOM 拒写），与原 6399 连接级注入对照 | 红队本体读码 + 子智能体实跑 |
| S1 饿死规律 | 读码（`SseChatEmitter#drain/#enqueueLive/#submitDrain`、Tomcat 阻塞写机制比对）+ 探针独立复跑 2 格 ×2 次 JVM + 16 格 SIZING 原始数据重析 | 红队本体裁决 + 子智能体复跑 |
| S4 豁免第 257 条 | 读码（`#enqueueLive/#liveDequeued/#stageReplay`）与附件 EXEMPTION 逐字段对账 | 红队本体 |
| S7 测试计数 | 独立复跑 `mvn -B test` + XML 独立求和（不信日志汇总行）+ 静态态卫生读码 | 子智能体实跑 + 红队本体读码 |
| 内部一致性 | 12 项对账：15 个附件逐一与主报告数字核对 + `t35-scratch/boot.log` 时间线取证 | 子智能体扫描 + 红队本人复核全部争议行 |

硬约束遵守：零 git 写操作（仅只读 status/diff/rev-parse）；未修改仓库任何文件（`backend/target/` 构建产物按授权豁免）；探针全部另存 `<SCRATCH-REDTEAM>\`；未启停 Memurai（6379 全程 PID 5032 在跑）；假 RESP 服务器（16380/16381/16382）与 6399 死端口用后全部确认无监听、node 进程零残留。

---

## 1. 核心结论裁决表（靶子逐条）

| 核心结论 | 攻击手段 | 裁决 | 关键依据 |
|---|---|---|---|
| **S1** 矩阵 20/20、⟺ M<poolSize、pool=4 时 M≥4 确定性饿死、四档扫描一致 | 独立复跑 pool=4/M=4 ×2（饿死，逐字段确定性复现）+ pool=8/M=4（绿，0ms）+ 16 格 SIZING 重析 16/16 符合 ⟺ | **未击破**（独立复现 ✅） | `<SCRATCH-REDTEAM>\probe\run{1,2}.log`：pool=4 格 `healthyStarved=true, healthyReceivedBeforeEviction=0, poolActiveDuringBlock=4` 两次逐字节一致；pool=8 格 `healthyStarved=false, 偏差 0ms` |
| **S3** 锁内 Redis 往返占状态帧 emit 延迟 79.1% | 数字复算 + 口径攻击（见 F10） | **未击破**（方向与亚毫秒量级成立；79.1% 为差分近似，精度虚高） | 差分算式自洽：284.3/359.6=79.1%；但心跳基线自身含一次 SET（`SseChatEmitter#emit`→`RunStore#touchActivity`），见 F10 |
| **S4** 回放二分结论 + 豁免（3000 帧入队 liveQueued=0，第 257 条实时帧才摘） | 读码验证探针是否误读实现 | **未击破**（非误读） | `SseChatEmitter#enqueueLive`（`liveQueued >= queueCapacity` 闸）与 `#stageReplay`（`live=false` 不计数）逐行对上"100 心跳→liveQueued=100、257 条→摘除"；附件 EXEMPTION 8 字段全中；p50 8.86ms 由 20 轮原始值独立重算吻合 |
| **S5** 定值建议 pool 4→8 / queue 256 维持 | 约束校验复算 + 核数核对 | **未击破** | `AiProperties`：`deliveryPoolSize=4`、`DEFAULT_DELIVERY_QUEUE_CAPACITY=256`、`suspend.poolSize=20` 现值属实；20×2+8=48≤160（16 核）成立；锚定单测 `SseChatEmitterDeliveryTest#liveQueueCapacityStaysBelowTheEventWindowAndComesFromASingleSource` 存在且线绿 |
| **S6** delta 不入档 / max+1 续号 / SET 失败 WARN+继续出帧 | **命令级失败注入**（原报告只测连接级）+ 读码 | **未击破**（结论在命令级同样成立，独立复现 ✅）——但注入口径确实窄于其声称的真实场景，且带出 F4/F6/F7 三条新发现 | 假 RESP 服务器（READONLY / OOM 两模式）：`threwException=false, framesDelivered=4, deliveredSeqs=[1,2,3,-1], warnCount=3`，WARN 文案一致；`RunStore#recordIssuedSeq` 的 `catch (Exception)` 同时兜住 `RedisSystemException`（命令级）与 `RedisConnectionFailureException`（连接级） |
| **S7** 289/0/0/0、基线 284 只增不减、静态态卫生 | 独立复跑全量单测 | **未击破**（独立复现 ✅） | `<SCRATCH-REDTEAM>\mvn-test.log`：EXIT=0、Tests run 289/0/0/0；XML 独立求和 43 文件 = 289/0/0/0（flakes=0）；ThreeArgAssemblyConformanceTest 5 条 0 失败；日志 45 类行 vs 43 XML 差额 = 嵌套类，已调和 |
| **S8** 414ms 摘除 / 3ms 健康间隔 / 6004 零丢 / seq 无洞 | 附件对账 + evicted=1 来源取证 | **未击破**（数值全对；但 6e 的"首采即为 1"是事实错误，见 F2） | `pausedAt→evictedAt` = 414ms ✅；healthyMaxInterFrameGapMsInWindow=3（1460 样本）✅；6004 ✅；healthBefore 1→2 ✅；"1"来源已查实为同进程 15:48:19 更早一轮（见 F2） |
| **S9** E2E 27/0/0、GFSKIP=0、退出码 0、前端门禁绿 | 附件对账 | **未击破**（主判据全中；4 项子判据在附件中不可复核，见 F11） | `PASS=27 FAIL=0 / GFSKIP=0 / Q8 PASS=2` ✅；typecheck `Done in 4.49s (EXIT=0)`、build `✓ built in 28.52s` ✅ |
| （联动公式）16 核 160≥44 | 反射实读复核 + 4 核外推复算 | **未击破**（但【待裁决】3 内有一处算式错误，见 F3） | `Schedulers#DEFAULT_BOUNDED_ELASTIC_SIZE`=160 复算一致；4 核 40<44 不成立的外推正确 |

---

## 2. 发现清单

严重级定义：S1 阻断（击破核心结论）/ S2 重要（须修正后再回灌）/ S3 观察（登记）。

### F1【S2 重要】"响应原文"中被改动的数字：`redis.probes` 3 vs 附件 2

- **锚点**：主报告:221（§S2"响应原文（摘录）"）vs `T3-5-附件-S2-health压测前.json` 与 `.sanitized.json`（两版均 `"probes": 2`）。
- **内容**：主报告把自己标注为"响应原文（摘录）"的 JSON 里 `redis.probes` 写成 3，附件原始值为 2。摘录声明"脱敏：instanceId 已替换"，但 probes 是被改动的数字而非脱敏项。
- **影响**：不改变任何结论，但违反报告自己的证据纪律（"原文输出"即应逐字）；入仓证据文档与主报告 MD5 相同，该错误已原样入仓。
- **独立复现**：是（红队本人 grep 复核）。

### F2【S2 重要】`evicted` 时间线三处打架；【待裁决】6e"首采即为 1"是事实错误

- **锚点**：`SseChatEmitter#evict → EVICTED_TOTAL`（`static final AtomicLong`，进程级累计）＋ 主报告:212/251/718 ＋ `t35-scratch/boot.log:84,128,141`。
- **内容**：三处表述互斥——§S2 正文首采 `evicted=0`（附件 checkedAt 15:47:42.7，pid15640 进程启动后 3 秒）✅；§S2.3"压测前 evicted=1"（S8 会话内后采）✅；但 6e 写"**本棒会话内首采即为 1**（前一轮 S8 运行所留）"❌——同进程首采是 0。boot.log 取证：同一 PID 15640 全程未重启，15:48:19 有一轮更早的 S8 式运行（runId `2ff2ae15`）先摘除一次（0→1），15:48:42 才是附件记录的那轮（1→2）。"前一轮 S8 运行所留"方向正确，"首采即为 1"错误。
- **加重情节**：支撑 6e 真相的 `boot.log` **不在附件清单**——该澄清在附件范围内不可自证。
- **影响**：Δ=1 的差值算术不受影响（S8 结论成立），但"进程级累计语义"这一报告自称的硬要求（S2.2：每处引用并列）在 6e 处被自己违反。
- **独立复现**：是（boot.log 时间戳 + S2 json checkedAt 交叉，红队本人复核）。

### F3【S2 重要】【待裁决】3 次选方案算式错误：15×2+8=38 ≤ 40，却写"仍不成立"

- **锚点**：主报告:681。
- **内容**：原文"次选降专用池（20→15，要求 15×2+8=38 > 40? 仍不成立；需降到 ≤16 才在 4 核机成立）"。4 核机 boundedElastic=40，38 ≤ 40 **成立**；且 15 < 16，同句"需降到 ≤16 才成立"与"15 不成立"自相矛盾。
- **影响**：**次选方案（降专用池到 15–16）被一个算术错误错误否决**，误导【待裁决】3 的三选一决策（该裁决的推荐项"改公式"本身不受影响，但备选项评估失真）。
- **独立复现**：是（算术复核，红队本人）。

### F4【S2 重要·系统风险，非报告结论错误】只读副本下"跨进程续号回退"是稳态可达且已量化——风险登记域被低估

- **锚点**：`RunStore#recordIssuedSeq`（SET 持续失败仅 WARN）＋ `RunStore#appendEvent`（LIST 类命令照常成功）＋ `SseChatEmitter#isOrphan`（重号帧被静默丢弃）；对照 `RunStore.java:590-593` javadoc 把回退风险限定为"整体 Redis 宕机"场景。
- **内容**：红队用假 RESP 服务器构造"**SET 被拒、LIST 可用**"的持续状态（真实对应：只读副本 failover、maxmemory 触顶 OOM 拒写、ACL 只读），实测：进程 1 实发 seq 1..4（2/3/4 为不落档 delta），"重启"后锚点 = max(归档末帧=1, ai:seq=0) = 1，新写出器**重发 seq 2**，落入前端 lastSeq=4 覆盖区被孤儿过滤静默丢弃——**回退 3 帧、零报错**。归档面看起来完全健康。
- **影响**：主报告 S6.3 的 WARN 文案"跨进程续号可能回退"没错，但该风险**不是**"整体宕机"的瞬时伴生物，而是任何"SET 被拒而读/ LIST 可用"形态下的**稳态**行为；主报告与代码注释均未按此口径登记。
- **独立复现**：是（`<SCRATCH-REDTEAM>\setfail\probe-rollback.log` + `server-rollback.log`，node 服务器命令级取证：SET ai:seq 5 次全被拒、RPUSH/PEXPIRE 全成功）。

### F5【S3 观察】S6 注入口径窄：6399 是连接级失败，命令级未测（红队已补测，结论仍成立）

- **锚点**：`M2-T3-5-ProbeDelta.java` §④（127.0.0.1:6399 无监听 → `RedisConnectionFailureException` ← `ConnectException`）vs 命令级（`RedisSystemException` ← `RedisReadOnlyException` / `RedisCommandExecutionException`）。
- **内容**：指挥官指定的攻击点**部分成立**——原报告的"真故障注入"确实只覆盖连接建立失败，未覆盖"Redis 在线但拒绝写"。红队补测命令级（READONLY / OOM 两模式）后五项判据（不抛异常 / 4 帧全达 / [1,2,3,-1] / 恰 3 条 WARN / 文案一致）全部通过：`recordIssuedSeq` 的 `catch (Exception)` 对两类异常同构兜底，`warnCount=3` 有结构解释（3 业务帧走 nextSeq→recordIssuedSeq→WARN；heartbeat 走 `touchActivity`，catch 为 debug 级故不产 WARN）。
- **裁决**：S6.3 核心结论**未被击破**，但报告"SET 失败分支（真 Redis 不可达端点，非 mock）"的措辞应补一句"命令级拒写（READONLY/OOM）已另行验证同路径"（引用本红队产物即可）。
- **独立复现**：是（`<SCRATCH-REDTEAM>\setfail\probe-{readonly,oom,deadport}.log`）。

### F6【S3 观察】WARN 丢失根因：命令级失败只打 "Error in execution"，不含 READONLY/OOM 判据词

- **锚点**：`RunStore#recordIssuedSeq` 记 `ex.getMessage()`（Spring 外层包装的固定串），未遍历 cause 链。
- **内容**：三臂实测：命令级 WARN 尾巴是 `Error in execution`，连接级是 `Unable to connect to Redis`——运维拿到前者无法区分"只读副本 / OOM / ACL"三种故障性质。建议登记为可观测性观察项（与主报告【待裁决】5 的 evicted 拆分同族）。
- **独立复现**：是。

### F7【S3 观察】delta 的心跳活动戳与 seq 登记同命运：`ai:beat` 拒写仅 debug 级，僵尸判据活动证据静默失效

- **锚点**：`RunStore#touchActivity`（catch → `log.debug`）。
- **内容**：命令级拒写臂的 node 服务器取证显示每个 delta/心跳附带的 `SET ai:beat:*` 同样被拒（readonly 臂 2 次、rollback 臂 3 次），全部静默。只读副本持续态下 `ResumeService#activeAtMs` 的跨进程活动证据链失效——`lastEventAtMs` 与 `lastBeatAtMs` 双双归零，长轮可能被僵尸判据误接管（同一轮执行两次）。主报告未登记此项。
- **独立复现**：是（server-*.log 命令清单）。

### F8【S3 观察】S1 饿死规律不是 latch 构造的必然产物：机制保真，外推风险评级低-中

- **锚点**：`SseChatEmitter#drain`（`sub.emitter.send(frame.payload)` 占住投递池工作线程）＋ `#submitDrain`。
- **论证**：latch 阻塞 `SseEmitter#send` 与真实 TCP 背压在**机制上同构**——生产路径里 drain 线程执行的真实 `send` 在内核收发缓冲填满后同样阻塞（Tomcat 阻塞写），占住同一条池线程；"健康订阅者不被饿死 ⟺ M < poolSize"只依赖"慢 drain 占满全部工作线程、健康 drain 排队"这一池级事实，与慢的**来源**无关。两点真实差异均不推翻结论：① 内核缓冲先吸收若干帧——影响摘除时序（S8 实测 414ms 仍及时），不影响饿死判定；② 真实断连最终会经写超时/写失败走 `evict` 释放线程，latch 60s 永久阻塞是更极端的上界——即真实世界饿死窗口**有界**，报告"只能等慢连接被 TCP 超时/摘除后恢复"的表述与此一致。
- **残余风险**：M≥4 的**真实 HTTP** 形态未覆盖（S8 仅 1 条慢连接）——这是报告自认缺口（§S8.2"不可穷举"），非隐瞒。评级：低-中（机制已双向锚定：S8 的 M=1 真实背压 3ms 与矩阵 M=1 行 0ms 同向）。
- **独立复现**：读码论证（红队本体）＋ 格子复现（F12）。

### F9【S3 观察】S4 豁免实测与实现完全一致（非误读）；附件有单位标签缺陷

- **锚点**：`SseChatEmitter#enqueueLive`（判 `liveQueued >= queueCapacity`，非队列绝对长度）＋ `#stageReplay`（`QueuedFrame(payload, false)` 不计数）＋ `#liveDequeued`（弹出实时帧递减）。
- **内容**："3000 帧入队 liveQueued=0、100 心跳后=100、第 257 条实时帧才摘除"逐行可从实现推出且与附件 EXEMPTION 8 字段全中——探针结论不是误读。附件缺陷：`REPLAY_WALL_ALL_US` 字段名写 `_US` 实装**纳秒**（同文件 p50us=8855.0 即其 1/1000），报告换算正确但随证留存的原始标签有误。
- **独立复现**：是（读码 + 附件逐字段）。

### F10【S3 观察】S3 的 79.1% 是差分近似，精度虚高；"纯本地 ≈75µs（心跳帧全量）"表述错误

- **锚点**：`SseChatEmitter#emit` 心跳分支 → `RunStore#touchActivity`（一次 SET）。
- **内容**：心跳帧 emit 并非"纯本地"——它含一次 `SET ai:beat` 往返。因此 359.6−75.3=284.3µs 只是状态帧**相对**心跳帧的**增量** Redis 成本（SET seq + RPUSH/LTRIM/PEXPIRE），把它表述为"状态帧延迟的 ~79% 是锁内 Redis 往返"在口径上不严谨（若把心跳内那次 SET 也计入，Redis 占比只会更高——方向安全，数字失真）。佐证两循环互不校准：心跳 emit p50 75.3µs < 单测 `touchActivity` p50 82.5µs（后者不可能大于含前者的整体）。结论的**方向**（锁内 Redis 主导）与**量级**（亚毫秒、p50 0.36ms）成立。
- **独立复现**：是（报告自身数字交叉复算）。

### F11【S3 观察】可复核性缺口与 provenance 瑕疵（六项）

1. `boot.log` / `boot-e2e.log` / 桩自检 9 项输出 / `E2E_EXIT=0` 均**不在附件清单**——§S8.1"后端零 ERROR"、§S9.1"零 ERROR / 退出码 0 / 桩自检 9 项 PASS"四项判据在附件范围内不可复核（红队在清单外的 `t35-scratch/` 中验得全部为真，但按报告自己的证据标准这四项是缺口）。
2. `T3-5-附件-S1-矩阵原始stdout.log` 以**未捕获异常收尾**（`IllegalArgumentException at ProbeMatrix.runLadder`，无 `PROBE DONE`），且栈帧行号（:313，`LinkedBlockingQueue`）与入仓探针源码（`SynchronousQueue`，:315-316）对不上——**入仓"随证留存源码"是崩溃后修订版，非产出该附件的二进制**；场景 A/D 数据来自另一次重跑（附件二），报告未披露这次崩溃。
3. `t35-scratch/backend.pid` 记录 PID=1392，boot.log 实际 JVM=15640——"按 PID 停止"的 pid 文件记录的不是 JVM 进程。
4. §S1.1 括注"slowLiveQueuedPeak 峰值 1–3"，20 格实测只有 2 或 3，无 1。
5. `<SCRATCH>` 占位符指向两个目录（:4 写 `<SCRATCH>/t35-reports/`，附件自曝 `<SCRATCH>`=t35-scratch）；§0.3"5 个未跟踪文件"实为 6（差的是报告自身，自指伪影）。
6. 入仓证据文档与主报告 **MD5 完全相同**（`e338bdb97e109c0387db6134eb804458`）——是同源复制而非独立佐证，本清单全部问题原样入仓。
- **独立复现**：是（子智能体扫描 + 红队本人抽查关键行）。

### F12【复现确认·无发现】两项独立复现全绿

- **S1 格子**：pool=4/M=4 两次运行判定字段逐字节一致（`healthyStarved=true, 0/10 达帧, active=4, queue=6`），pool=8/M=4 绿（10/10、0ms）；16 格 SIZING 原始数据重析 16/16 符合 ⟺（含最锋利边界格 pool=8/M=8 与 pool=16/M=16 均饿死、pool=20/M=16 绿）。饿死是**确定性**池级头阻塞（10 个 run 里 6 个无慢订阅者的 run 也拿不到帧），不是抖动。
- **S7 全量**：独立 JVM 复跑 EXIT=0、289/0/0/0、XML 求和 43 文件与日志 45 类行（差额=嵌套类）三方一致、ThreeArg 5 条、无 flake/无 fork 崩溃、`git diff --stat` 为空（仓库零改动）。静态态卫生读码结论：`@BeforeEach` 置 null / `@AfterEach` 还原**进入时原值**并 shutdownNow 自建池——JUnit 5 默认顺序执行下自洽，对本类 5 用例与跨类均无泄漏路径；全量套件零失败为经验佐证。注意"基线 284"是推导值（289−5），未做移除新用例的独立基线实测（受"不动仓库文件"约束，合理）。

---

## 3. 红队胜负裁决与建议

**裁决**：任务卡判据为"打破任何一条核心结论都算红队胜"。红队对 9 条核心结论全部发起攻击（含 3 项独立实跑复现、1 项自建注入装置的命令级补测），**零条被击破**——矩阵规律、豁免语义、测试计数、E2E 门禁、定值依据全部站得住。**红队未胜。**

**但回灌前置条件建议**（S2 级四项，均为低成本文字/登记修正）：

1. 修正 F1（probes 3→2）、F2（6e 改为"同进程 15:48:19 更早一轮 S8 式运行所留，首采为 0；来源 boot.log 未入附件"）、F3（次选算式：15×2+8=38≤40 在 4 核机**成立**，≤16 均可，需重估次选与推荐的取舍理由）。
2. 将 F4（只读副本稳态续号回退，实测 3 帧静默丢失）、F6（WARN 丢根因）、F7（ai:beat debug 级静默失效）并入【待裁决】观察项——F4 建议与待裁决 5（evicted 拆分）同族处置，均属"故障形态可观测性"。
3. F5 建议在 S6.3 补引命令级验证结论（红队产物可直接引用）。

**对 S5 定值建议本身的攻击结论**：`delivery-pool-size 4→8` 的实证链（S1.2 ⟺ 规律 + 16/16 重析 + 独立复跑 + 48≤160 约束）经受住攻击；`queue-capacity 256` 的双向证据（不误摘/及时摘）亦然。红队对定值建议**无异议**，仅要求随 F3 修正待裁决 3 的备选项评估。

---

## 4. 红队产物索引（复现用）

| 产物 | 路径 |
|---|---|
| 全量单测复跑日志（EXIT=0 / 289/0/0/0） | `<SCRATCH-REDTEAM>\mvn-test.log`、`exit-code.txt`、`pre-run-xml-list.txt` |
| S1 探针复跑（3 格 ×2 JVM + 编译产物） | `<SCRATCH-REDTEAM>\probe\`（Probe.java / deps-matrix.txt / run1.log / run2.log） |
| 命令级 SET 失败注入（假 RESP 服务器 + 三臂探针 + 回退量化臂） | `<SCRATCH-REDTEAM>\setfail\`（fake-redis.mjs / ProbeSetFail.java / ProbeSeqRollback.java / probe-{readonly,oom,deadport,rollback}.log / server-*.log / RESULTS.md） |
| 附件对账与 boot.log 时间线取证 | 见本报告 F1/F2/F11 引用的行号（原始文件均在 `<SCRATCH-REPORTS>\` 与 `<SCRATCH>\`，红队未改动） |
