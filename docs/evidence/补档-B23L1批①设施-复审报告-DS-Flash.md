# 补档 · B23L1 批① 设施 复审报告（DS-Flash，2026-10-21）

## 0 自证行（wire 实测，非照抄本卡）

- 会话 id：`session_081d49e8-17a5-451b-befe-c0422c23a69b`
- 复审 agent：`agent-1305`；wire = `<KIMI_HOME>/sessions/<SESSION_ID>/agents/agent-1305/wire.jsonl`
- 取数方式：首行为 `{"type":"metadata",...}`（非 bind 行），按卡要求 `grep -n 'profile.bind' wire.jsonl`，命中 **第 5 行**，原文（截取前 200 字符）：
  `{"type":"profile.bind","agentId":"agent-1305","modelAlias":"DeepSeek/deepseek-flash","profileName":"coder","thinkingEffort":"high","systemPrompt":"You are Kimi Code, ...`
- 与施工方互盲：本报告未读 `agent-1301` 的任何 wire / 中间产物；只读施工方**落库的**施工报告与工作树。

## 1 只读纪律与 diff 锚（双锚，SHA-256）

只读执行清单：零 `commit` / `push` / `stash`、零服务启停（`scripts/b23l1-dual-instance.ps1` **未运行**，只做静态审查）、零源码改动；**唯一新增文件 = 本报告**。取数 = 15 个未跟踪新件逐个 `git add -N <file>`（未用 `-A`）→ `git diff` → `git reset -q`；还原后 `git status --short` 回到原样（8 `M` + 17 `??`，其中含未跟踪的 `data/`，见 N-1）。

| 取数面 | 行数 | SHA-256 |
|---|---|---|
| 全量 diff（生产 6 新 + 4 改，测试 5 新 + 3 替身 + 1 改，脚本 1 新，文档 3 改，含 `-N` 新件） | 3270 | `2b3ecc9ba1f21ae60edf821ca977799e471ec3084a358ee6dab9c65f2dbbe678` |
| 生产-only（`backend/src/main` 6 新 + 4 改；前端零改动，`frontend/src` 无条目） | 1527 | `bc01e44728bcb0d11904ab2def9d08ce39b1906b00c4c77cdb70bb65b508d59f` |

两次独立取数（先全量、后生产）分别成锚，行数与 `--stat` 逐文件相加吻合，未见工作树漂移。

## 2 结论：**CHANGES-REQUESTED**（1C / 3I / 7M / 7N）

设计稿 §2.1 的六条设计意图（FrameStore 抽象 / Stream 单实现 / wake 扇出 / 按连接订阅 / 取号收敛 / 兼容双读 + reattach 切换）**全部落地且语义自洽**，生产代码质量高于本仓同类批次均值：取号与落档拆两步的理由（D-1）站得住，心跳三不（不取号/不落档/不发 wake）钉得住，兼容双读的同号优先级（Stream 胜）与"无 seq 老帧一律保留"同 `isOrphan` 口径一致，`RunStoreFrameStore` 的"生产装配不可达"也有装配证据（`RunRegistry.java:96` 唯一 `@Autowired` 构造恒注入 `RedisFrameStore`）。

打回的两个实质原因：

1. **C-1（交付件不可执行）**：双实例 harness 在健康等待处读了**不存在的**响应路径，按当前代码**每次运行必然 FAIL**——不是"未运行所以未验证"，而是"即使运行也一定红"；同一错误表述还写进了 `scripts/README.md`（文档与脚本同错，check-docs 不校验此面）。批① 的验收口径 §5.2 因此目前是 **0 证据**。
2. **I-1 / I-2：跨实例链路的两个窗口**——(a) reattach 时"回放读"早于"订阅建立"，窗口内落档的帧在**挂起/终局**场景没有下一次唤醒可兜底；(b) `onWake` 的"上界 ≤ 本地水位即早退"配上 `count = 物理尾 − afterSeq` 的有界尾读，在物理序 ≠ seq 序时会**永久**少一帧，且"下一次唤醒兜底"这句在 D-3 里是**不成立**的（推导见 §5.3）。

这三条都不影响单实例行为（单实例下 emit 的取号与落档相邻且同锁，物理序 = seq 序），因此**不是生产正确性事故**，但它们是批② fencing 落地后**立刻**暴露的面（批② 一旦允许跨实例续跑出帧，I-2 即从"理论"变成"可达"）。剩下的 7M/7N 是测试牙口、降级路径取证与热路径成本问题。

## 3 蓝本 §2.1 / §5 / §7 逐条核对

| 设计条款（设计稿行号） | 落实位置（实测行号） | 判定 | 核对说明 |
|---|---|---|---|
| `FrameStore`：`append(runId,frame)→seq` / `readFrom(afterSeq,limit)` / `lastSeq` | `FrameStore.java:42/48/60/63` | **PASS（带 D-1 偏差）** | 拆成 `nextSeq` + `append` 两个方法，理由（截断含 seq 必须早于落档）成立且与 `SseChatEmitter.java:921-936` 的实际顺序一致；接口 Javadoc `:15-21` 自述清楚。设计稿单方法形态若照做会真的把落档提到截断前。 |
| Redis Stream 单实现（`XADD MAXLEN ~ 6000` + `XRANGE`） | `RedisFrameStore.java:60/63/135/153`；读侧 `:196-226` | **PASS（带 D-3 偏差）** | `XAddOptions.maxlen(6000).approximateTrimming(true)` ✓；读侧用 `XREVRANGE ... COUNT` 而非 `XRANGE` 全窗（D-3，已在报告登记）。**注意**：技术方案注记仍写 `XRANGE`（见 N-6）。 |
| `SseChatEmitter.emit` 降级为 append→PUBLISH（仅上界）→本地扇出 | `SseChatEmitter.java:897-960`（取号 `:924`、截断 `:931`、落档 `:937`、水位 `:940`、发布 `:941`、扇出 `:949-959`） | **PASS** | 顺序与设计稿一致，且比设计稿多一步"发布前先登记水位"（D-4），这一项在 `SseChatEmitterCrossInstanceWakeTest#selfWakeOnTheSameInstanceDoesNotDuplicateTheFrame` 有断言（`journal` 序列 `append→publish`）。 |
| `FrameWakeListener`：按**连接** subscribe、禁止每实例全量频道、断连即 unsubscribe | `FrameWakeListener.java:53-76`（首目标准入订阅 `:59-62`、末目标退订 `:71-75`）；`SseChatEmitter.java:432`（attach 后）、`:462`（close 后） | **PASS** | 频道粒度 = run（`RedisFrameWakeBus.java:50`），未出现 `PatternTopic`/通配订阅；引用计数用目标集容量而非计数器（`:55` `first = set.isEmpty()`），漂移面小。 |
| 唤醒 → `readFrom(>lastSentSeq)` → 推本实例该 run 的连接 | `SseChatEmitter.java:992-1041` | **PASS（带 I-1/I-2）** | 差量读 `:1005`、重复帧过滤 `:1011-1012`、入队 `:1028-1034`；`upperBoundSeq<=from` 早退（`:1001`）见 I-2。 |
| `seq` 取号收敛：`INCR ai:seq`，废除进程内 AtomicLong 锚定 | `RedisFrameStore.java:115-132`（`INCR` `:119`）；旧 `AtomicLong seq` + `seqLock` 已从 `SseChatEmitter` 删除 | **PASS（带 M-1）** | 生产号源唯一；进程内号锚只在 `RunStoreFrameStore.java:44-56` 的兼容路径存续（非生产装配）。降级计数 `fallbackSeqs`（`:397`）与 M-1 的锚定问题见后。 |
| LIST 兼容读：先 LIST 尾再 Stream、按 seq 拼接去重、新写只进 Stream | `RedisFrameStore.java:172-193`（`legacyFramesAbove`）、`:240-254`（`mergeBySeq`）、`:256-272`（`collect`，同号 override=Stream） | **PASS** | 全仓已无生产调用方再写 LIST（`appendEvent` 仅 `RunStoreFrameStore.java:60` 调用，grep 已核）；同号 Stream 胜、无 seq 前置保留，两条都有用例（`RedisFrameStoreTest:173-202`）。 |
| reattach 回放源切 FrameStore、孤儿过滤/原子交接不回归 | `SseChatEmitter.java:571-604`（`stageReplay`，读源 `:573`）；`AiController.java:320`（`events`） | **PASS（带 I-1）** | 原子交接仍靠 `emitLock`（`:500-517`），`isOrphan` 保留第二道兜底（`:606-615`）；跨实例把"回放与订阅"拆成了两段，见 I-1。 |
| §5.2 双实例 harness（`scripts/` 新增） | `scripts/b23l1-dual-instance.ps1`（400 行）+ `scripts/README.md:121-167` | **FAIL（C-1）** | 形态/端口/桩/证据落盘/退出码齐备，但健康等待读错响应路径 ⇒ 必然 FAIL；且本批未运行（D-5 已如实登记）。 |
| §5.1 门禁 + 脱敏 | 见 §9、§8 | **PASS** | 后端 614/614、前端 512/512、typecheck 0、check-docs 6/6、脱敏 0 命中。 |
| §5.4 P95 帧延迟不回退 ±10% | 无 | **未取证** | emit 热路径新增"每帧一次同步 `PUBLISH`"（`SseChatEmitter.java:940`），无任何基准或压测数据支撑该口径（M-7）。 |

## 4 夹带与零改动扫描

- **批② fencing 夹带**：0。全量 diff 中 `lease` / `fencing` / `epoch` / `lock:task` / `lock:job` / `LeaseService` / `ToolTargetResolver` / `RESOURCE_LOCKED` 的**代码命中为 0**，仅出现在 SKIP 占位与说明文字（`scripts/b23l1-dual-instance.ps1:328-333`、`:19-22`、看板行、README §3.4）。
- **批③ 资源治理夹带**：0（同上，`idempot` 仅命中"重复唤醒幂等"与 SKIP 文案）。
- **前端零改动**：`frontend/src` 无 `M`/`??` 条目，`git diff --stat` 无前端文件；vitest 512/512、`vue-tsc --noEmit` exit 0 复跑均绿，与"客户端透明"目标一致。
- **未点名的行为变更（逐条）**：
  1. 每个业务帧多一次 `PUBLISH`（单实例、零订阅者时也发）——新增 Redis 往返，见 M-7。
  2. `GET /api/ai/runs/current` 的 `archiveMaxSeq` 取值源从 `RunStore.lastEventSeq` 改为 `FrameStore.lastSeq`（`AiController.java:512-514`），数值口径从"LIST 末帧号"变为"max(LIST 末帧号, Stream 末帧号)"。这是迁移必需，且注记已写入 `docs/03-技术方案文档-v2.1.md` 追加段。
  3. `RunStore.deleteRun` 多删一个键 `ai:xevents:`（`:317-321`）；`renewSuspensionTtl` 多续一个键（`:736`）——后者把"四类键"改为"五类键"，用例 `RunStoreSlidingTtlTest` 从 `times(4)` 升到 `times(5)` 并新增 `verify(expire(xeventsKey))`，**断言升维、非降级**。
  4. `SseChatEmitter` 的 8 参兼容构造改为委托 `RunStoreFrameStore`；号锚从"每 emitter 一个 AtomicLong"变为"每 FrameStore 一个 Map<runId,AtomicLong>"（`RunStoreFrameStore.java:33`）。语义等价（同锚 = `max(归档末帧, ai:seq)`），conformance 55 条零改动全绿即为证。
- **口径演进只升不降**：本批唯一被改的既有测试是 `RunStoreSlidingTtlTest`（+1 断言）；无任何既有断言被删、放宽或 `.skip`。`grep -c "@Test"` 五个新类 = 10/4/5/5/4 = 28 条，与报告自报一致。

## 5 缺陷攻击面逐条（对应审查要点 3.a–3.g）

### 5.1 `emit` 顺序的失败原子性（3.a）

逐步骤枚举"中途异常会留下什么"：

| 步骤 | 异常处置 | 残留不一致 | 判定 |
|---|---|---|---|
| 取号 `nextSeq` | `RedisFrameStore.java:126-131` 内部兜底为进程内计数（WARN） | 跨实例不重承诺失效；降级号可能与已发号重叠 ⇒ `mergeBySeq` 同号合并会**丢一帧**（同号 Stream 胜，只留一条） | 已登记（`FrameStore.java:38-41` 明示"不是续号锚点"）；与 M-1 同族 |
| 截断 `FrameSizeLimiter.apply` 返回 null | `:931-935` 直接 return | 号已消耗未落档 ⇒ 号空间出现**空洞**（对 replay 无害：`readFrom` 只按 `>afterSeq` 过滤，不要求连续） | 可接受 |
| 落档 `append` | `:158-161` **吞异常只记 debug**，调用方继续发布 + 本地扇出 | 该帧"实时可见、日志不可见"：跨实例读不到 ⇒ 只影响 reattach 与跨实例，不影响本实例在线客户端 | 与 B1 `appendEvent` 同口径，**不算回归**；但意味着"落档失败"在观测面上几乎不可见（debug 级），见 M-4 |
| 水位登记 `:940` | 无 | 先登记后发布是本批的有意设计（D-4），自唤醒重入不会重复投 | PASS |
| 发布 `publishWake` | `:966-977` 双保险吞异常 | 无（唤醒可丢，客户端 `?lastSeq` 兜底） | PASS |
| 本地扇出 `:949-959` | 队列溢出 ⇒ `evict` 主动断连 | 客户端带 lastSeq 重挂补差量 | PASS（与 S2-1 一致） |

**结论**：没有一种中间失败能造成"帧体与日志分叉"（截断先于落档、落档先于发布这两条不变量成立），失败面收敛为"号空洞"与"日志缺帧"，两者都不会让在线客户端看到错帧或重帧。**唯一的真实泄漏是观测面**：日志缺口只留 debug 痕迹（M-4）。

### 5.2 本地扇出与 wake 差量的去重（3.b）

- **读写一致性**：`lastFanoutSeq` 的三处读写（`emit:940`、`stageReplay:595`、`onWake:1001/1034`）**全部在 `emitLock` 内**，无锁外访问；`AtomicLong` 只是省事，不承担并发语义。PASS。
- **同步总线自唤醒重入**：`FakeWakeBus.publish` 是同步回调（`FakeWakeBus.java:35-42`），回调在 `emitLock` 内重入 `onWake`（`synchronized` 可重入）⇒ 看到 `upper <= from` 早退。生产 `RedisFrameWakeBus.publish` 用容器线程异步回调，走的是同一条判据。PASS，有用例。
- **但要区分两种"重复唤醒幂等"**：`repeatedWakeIsIdempotent` 用的是同一个上界重放，能证明早退；它**不能**证明"上界单调递增时中间帧一定已到齐"——那正是 I-2 的缺口。
- 一个此前未登记的性质（N-7）：水位是**每 emitter（=每 run）**一个，而回放是**每订阅者**一次。因本地扇出对所有订阅者广播、`evict` 直接摘订阅者，"同 emitter 内各订阅者恒同步"这一不变量成立，故共享水位是安全的；但 `FrameWakeListener` 类注（`:23`）写"每个目标自己记已推送到哪个 seq"，字面读像是 per-subscriber，容易让后续维护者误改。

### 5.3 `XREVRANGE COUNT` 有界尾读（D-3）会不会漏帧（3.c）

对四种边界分别验证（`RedisFrameStore.java:196-226`，`needed = min(6000, max(1, tail−afterSeq))`，`tail` = 物理最新一条的 `seq`）：

| 场景 | 推导 | 结果 |
|---|---|---|
| `afterSeq` 很旧（如 0，日志满窗 6000） | `needed = min(6000, 6000) = 6000` ⇒ 整窗 | 不漏（窗口外本就不承诺） |
| 近似裁剪保留 **>6000** 条（`~` 是近似，实测可保留到宏节点边界） | `needed` 被夹到 6000 ⇒ 只回最新 6000 | 丢的是最旧的部分 ⇒ 与窗口语义一致，不算缺陷 |
| 空洞（`INCR` 成功、`XADD` 崩溃） | `tail−afterSeq` **高估** ⇒ `COUNT` 偏大 | 过读，不漏 |
| 心跳不占号 / B1 前无 seq 老帧 | 心跳不入 Stream（`append:137`）；无 seq 老帧只在 LIST 里，走 `legacyFramesAbove` 整窗读（`:188-193`） | 不漏 |
| **物理序 ≠ seq 序**（跨实例并发出帧：A 取 11 慢落档、B 取 12 先落档） | `tail` = `XREVRANGE COUNT 1` = **12**（B 的）⇒ 若 A 尚未落档，`needed` 恰好够；一旦 A 的 11 落在 **12 之后**，`tail` 仍取物理最新（可能是 13），而 `count = 13 − 12 = 1` ⇒ **只读 13** | **11 永久不可读**（此后任何 `from ≥ 12` 的读都只按 `tail−from` 取尾） |

第三列最后一行就是 **I-2**：施工报告 D-3 的兜底句"由下一次唤醒 / `?lastSeq` 重连兜底"对**前半句不成立**——下一次唤醒的 `readFrom(from)` 仍用同一个 `count` 公式，读不到那个低号记录；只有客户端重连（`stageReplay` 用 `limit=0` 走 `legacyFramesAbove` + `needed=min(window, tail−afterSeq)`……同样按 `tail` 估算，`limit=0` 时却不受 `COUNT` 限制吗？——**受**，`streamFramesAbove` 不看 `limit`，仍按 `tail−afterSeq` 定 `COUNT`）。因此**该帧对任何读者永久不可见**，直到它被窗口裁掉。修正建议见 I-2。

另外 `onWake` 的早退（`:1001`）会把"低号帧后到"这件事**静默丢弃**：`upper=11 <= from=12` 直接 return，连一次读取都不做。

### 5.4 兼容双读的 seq 重叠/空洞（3.d）

- **重叠**：同号 Stream 胜（`collect(..., override=true)`，`:245`），有用例 `readFromMergesLegacyListAndStreamBySeq`。PASS。
- **前缀空洞**（LIST 被 3000 裁剪 + Stream 中段被 6000 裁剪）：`mergeBySeq` 只做"并集 + 排序"，**不检测连续性**；例如 LIST 留 1..3000、Stream 留 4001..10000 ⇒ 回放集为 1..3000 + 4001..，中间 3001..4000 静默缺失且无任何日志。这是窗口机制的固有后果（B1 已登记同类风险），但本批把两个窗口叠在一起，**空洞可以从"尾部"变成"中间"**——尾段缺帧客户端尚可感知（终帧未到），中段缺帧则完全无感（N-5/M 级，建议在 `mergeBySeq` 出口加一条"发现 `seq` 不连续 ⇒ WARN + 计数"）。
- **无 seq 老帧**：`mergeBySeq:247` 把它们**无条件**放在结果头部，不受 `afterSeq` 约束（有用例 `legacyFramesWithoutSeqAreKeptAtTheHead` 明确断言 `afterSeq=5` 仍返回）。于是每次 reattach 都会**重复**投递这些帧（`stageReplay` 不过滤无 seq 帧）；`onWake:1011` 明确跳过它们，所以唤醒路径不受影响。属"宁重不丢"的有意取舍（行为已文档化，"每次重挂都重发"这一面未在报告写明，见 N-5）。
- LIST 读口每次调用都要先 `LRANGE -1 -1` 探一次尾（`:189`），键空即短路 —— 迁移完成后是 O(1)，可接受。

### 5.5 `INCR` 与 `XADD` 的非原子两步（3.e）

- 崩溃窗口 = `INCR` 已回、`XADD` 未落：**跳号**。对 replay 无害（`readFrom` 是"号大于阈值"集合，不要求连续）；对孤儿过滤无害（`isOrphan` 只比 `seq <= lastSeq`）。
- 真正的后果有两个，都不在设计稿的风险表里：①**客户端会看到号断层**（`lastSeq` 视角下的"洞"），前端目前用 `seq` 只做去重与单调校验，不报错也不补——于是"某段 delta 永久缺失"在 UI 上表现为"正文少一段"，无任何提示；②`lastSeq()` 用物理尾（`:230`），跳号 + 乱序时可以低估 ⇒ `archiveMaxSeq` 偏小 ⇒ 前端全量/重复回放（方向安全）。
- 建议（低成本）：`emit` 落档失败时把级别从 `debug` 提到 `warn` 并计数（M-4）；`readFrom` 出口做一次连续性检查（见 5.4）。

### 5.6 首/末订阅者 register/unregister 的并发（3.f）

- 正常交错**收敛**：`wakeRegistered` 的 CAS 与 `subscribers.isEmpty()` 的判据组合，四种交错（attach/attach、detach/detach、attach/detach、detach/attach）都收敛到"有订阅者 ⇔ 已登记"，我逐条推演过；`FrameWakeListener` 侧再用 `LinkedHashSet` 去重、`bus.subscribe` 侧用 `listeners.put` + 移除旧监听器兜重复订阅。
- **残留问题 1（I-3）**：`bus.subscribe` 失败（Redis 抖动 / 容器启动失败）只 WARN 并移除监听器（`RedisFrameWakeBus.java:77-80`、`:66-70`），**但 `wakeRegistered` 保持 true** ⇒ 该 emitter 在订阅者归零之前**永不再试**，跨实例唤醒静默缺席，且没有任何观测面（`subscribedRuns()` 只数 `listeners`，看不到"该订阅但没订上"）。
- **残留问题 2（M-6）**：`FrameWakeListener.register/unregister` 是 `synchronized(this)` 的**全 run 共享锁**，而 `bus.subscribe` 里有 Redis I/O 与容器启动（`container()` → `afterPropertiesSet/start`）。即"某个 run 首次挂连接"会阻塞**所有 run** 的 register/unregister/`dispatch` 快照。`dispatch` 已刻意锁内快照锁外回调，唯独登记路径没做同样的隔离。
- 无任何并发用例覆盖 attach/detach 交错（M-6）。

### 5.7 conformance 既有用例口径（3.g）

- 7 个类 55 条**零改动全绿**（`ReattachLastSeqConformanceTest` 7 / `SseFrameSequenceConformanceTest` 7 / `ToolFrameSequenceConformanceTest` 11 / `ChatTurnFrameSequenceConformanceTest` 5 / `FrameContractFalsificationTest` 17 / `SessionBusyFrameIsolationConformanceTest` 3 / `ThreeArgAssemblyConformanceTest` 5）。本次全量跑里这些类均 0F/0E（见 §9 日志）。
- 口径演进**只升不降**：唯一改动是 `RunStoreSlidingTtlTest` 的 `times(4)→times(5)` + 新增一条 `verify`。
- **但要如实指出**：这 55 条现在断言的是**兼容装配**（三参 `RunRegistry` ⇒ `RunStoreFrameStore`），生产装配（六参 ⇒ `RedisFrameStore`）**不在 conformance 覆盖内**。报告 D-2 已如实登记，新用例（`FrameCompatReadTest`）也在 `RedisFrameStore` 上覆盖了 reattach 语义，补偿是充分的；但"生产口径只有测试类覆盖、没有 conformance 覆盖"这件事值得在看板上留一句。

## 6 测试矩阵完整性（对照报告 §0，找漏行）

报告矩阵 M1–M17 的每一行我都对到了具体用例方法，**无虚报**（28 条用例与 `@Test` 计数逐类吻合）。缺口如下（按审查要点 4 指定的四项 + 我在读码过程中额外发现的）：

| # | 漏行 | 现状 | 建议 |
|---|---|---|---|
| G1 | **TTL 过期后 `readFrom`** / `ai:seq` 键过期后取号 | 无用例；`FakeFrameRedis` 只**记录** `EXPIRE` 值、**不实现**过期语义（`:74-77/127-131`），因此这条现在**测不了** | 替身补过期（或换成嵌真实 Redis 的最小契约测）；重点断言"键没了以后 `nextSeq` 不许从 1 重来"（见 M-1） |
| G2 | 空 run | **已有**（`readFromBoundariesCoverEmptyFullMiddleAndBeyondTail` 的第二段） | — |
| G3 | `afterSeq > lastSeq` | **已有**（`:109`，`afterSeq=99`） | 补一条 `afterSeq` 大于 **LIST** 末号但小于 Stream 末号的跨源边界 |
| G4 | MAXLEN 裁剪后的回放起点 | 半有（`streamIsTrimmedToWindowWhileSeqKeepsAdvancing` 断言裁剪后 `readFrom(0)` 得 5..12） | 补 `afterSeq` 落在**被裁掉区间内**（如 `afterSeq=2`）的断言：期望从 5 起、且**不抛错不阻塞** |
| G5 | 跨实例乱序落档（物理序 ≠ seq 序） | **无用例，且替身建模不出来**：`FakeFrameRedis` 的 Stream 是 `ArrayList` 追加序（`:104-113`），`xRevRange` 只是 reverse ⇒ 物理序恒等于 seq 序 | 若要守住 I-2，替身需支持"按 seq 乱序插入"；或直接改 `XADD` 为显式 ID 让乱序不可能发生 |
| G6 | 首/末订阅者并发 attach/detach | **无用例** | 两线程各 100 次 attach/close，断言 `subscribedRuns()` 恒 ∈ {0,1}、`targetCount` 无残留 |
| G7 | 降级路径：`publish` 失败 / `subscribe` 失败 / `append` 失败 / `INCR` 失败 | **四条全无用例**，而四条都写进了 javadoc 当承诺（`RedisFrameWakeBus.java:29-31`、`RedisFrameStore.java:51-53`、`FrameStore.java:38-41`） | 至少补 `subscribe` 失败后的重试语义（修 I-3 时一并测） |
| G8 | `AiController.archiveMaxSeq` 的 **FrameStore 分支** | **无用例**：`AiControllerRunsCurrentTest` 是 `standaloneSetup` + `frameStore=null`（`:44-46`），只覆盖回落分支；而生产**只走** FrameStore 分支（`AiController.java:512-514`） | 补一条注入 `FrameStore` mock 的断言（`lastSeq` 返回值直接透出） |
| G9 | LIST 窗口被裁（>3000）后的拼接 | 无用例 | 低优先 |

另：`RedisFrameStoreTest` 里两条 "kill" 注释（`concurrentSeqAllocationIsUniqueAndMonotonic:57`、`streamIsTrimmedToWindowWhileSeqKeepsAdvancing:123`）**牙口声明不成立**——替身的 `XADD` 裁剪只看构造参数 `window`、不读 `XAddOptions.maxlen`（`FakeFrameRedis.java:101-113`），删掉实现的 `maxlen(...)` 该用例**不会**变红（这一条是只读推演即可判定的确定项）；另推演：`values.set` 未打桩，把 `INCR` 换成 GET+SET 更可能落进降级分支（唯一号仍成立、用例反而绿），而非"产生重号变红"。这属于"用例有牙"的申报过强（M-5）；报告 §3.1 列的四条定点变异不含这两条，倒是自洽。

## 7 harness 静态审查（未运行；对应审查要点 5）

形态面基本正确：自起子进程并只杀自己的子进程（`Stop-Children` 只遍历 `$script:children`，`:107-117`、`:396`）；两实例独立 H2 文件、避免文件锁（`:352`）；上游桩参数与 `scripts/ci/stub-upstream.mjs` 的 `--port/--dir` 用法一致（`:342`）；`AI_API_KEY` 是占位串（`:356`）；证据落盘路径在 `backend/target/`（已 gitignore）；退出码在任一 FAIL 时为 1（`:399`）；批②③ 用例 SKIP 不计入失败。

### 7.1 C-1：健康等待读错响应路径（必然 FAIL）

脚本 `:142-143` 取 `$body.data.ai` / `$body.data.redis`，而 `/api/ai/health` 返回的是**扁平**对象（`AiController.java:743-786`：`available`、`redis` 与 `code`/`model` 同级，**没有 `data` 包装，也没有 `ai` 子对象**）。同一形体的实测响应（本机既有压测附件 `<REPORTS_ROOT>/T3-5-附件-S2-health压测前.json`，逐字摘录首尾两段）：

```json
{"available":true,"code":"AI_AVAILABLE","model":"s8-stub","baseUrl":"http://127.0.0.1:18398",
 "memoryBackend":"RedisChatMemoryRepository","sessionTtl":"PT6H", ... ,
 "redis":{"available":true,"probes":2,"failures":0,"lastError":null, ...}}
```

PowerShell 实测（对同形体的最小复现，`$ErrorActionPreference='Stop'`、无 `Set-StrictMode`）：

```
dataNull=True aiNull=True redisNull=True gateNow=False gateFixed=True
```

即：`$body.data.ai.available` 恒为 `$null`（不抛错），判据恒假 ⇒ 每个实例空转满 `HealthTimeoutSec`（默认 120s）后 `throw`，`catch` 落到 `No 'harness' ...`，**整棒 exit 1**，一个用例都跑不到。**同一错误表述写进了 `scripts/README.md:135`**（"`/api/ai/health` 的 `ai.available && redis.available`"），因此文档与脚本同错、check-docs 的六条断言都碰不到这一面。

**修法（1 行）**：`:142-143` 改为 `$ai = $body; $redis = $body.redis`（或直接 `if ($body.available -and $body.redis.available)`），并同步 README §3.2 的措辞。

### 7.2 其他（N-3）

- **无端口占用预检**：`Wait-Health` 只打 HTTP；若 8081 已被一个"旧的、健康的"实例占着，新 `java` 绑定失败但健康检查照样通过 ⇒ **假绿**（且证据会全部落在旧实例上）。建议起进程前对 `$PortA/$PortB/$StubPort` 做一次 `TcpClient` 连通性探测，通即 FAIL。
- `Case-Suspend-Reattach-Wake` 用 `Start-Sleep -Milliseconds 1500` 代替"B 侧长连接已挂上"的就绪信号（`:306`），是抖动源；建议让后台作业先写一个 ready 文件/输出行再批准。
- 只有 `finally` 收尾，Ctrl-C / 终端关闭时子进程会残留（无 `Register-EngineEvent` 或 job 包裹）。
- `Register-ObjectEvent` 的输出转发从不注销，同一进程内多次 `Start-Child` 会累积订阅（单次运行无碍）。
- `$script:http` 未 `Dispose`；`Read-Sse` 的超时分支会把"读超时"吞成"帧数不足"，失败信息偏弱（但断言会兜住）。

## 8 脱敏

对全量 diff 的**新增行**做前缀/格式扫描（`sk-` / `ghp_` / `AKIA` / `xox[baprs]-` / `api_key=` / `password` / `secret` / `token=` 等）⇒ **0 命中**；脚本内唯一凭据形态是占位串 `AI_API_KEY=b23l1-stub-placeholder`（`:356`），与既有 `verify-e2e.ps1` 同口径。**PASS**。

## 9 门禁实测（本人复跑，2026-10-21 02:0x）

| 门禁 | 命令 | 实测 |
|---|---|---|
| 后端全量单测 | `<MAVEN_HOME>\bin\mvn.cmd -o test`（`backend/`） | **614/614 绿**（Failures 0 / Errors 0 / Skipped 0），`BUILD SUCCESS`，耗时 1:26 |
| 测试类数复核 | surefire `TEST-*.xml` 计数 | **98 个类**（日志里 100 条 `Tests run ... -- in` 中有 2 条是 `@Nested`：`GenerativeFormGateTest$SubmissionGate` / `$SuspensionGate`）⇒ 与报告"98 类"一致 |
| 本批新增 5 类 | 同上 | `RedisFrameStoreTest` 10 / `FrameWakeListenerTest` 5 / `SseChatEmitterCrossInstanceWakeTest` 5 / `SseChatEmitterFrameStoreTest` 4 / `FrameCompatReadTest` 4 = **28 条全绿** |
| 前端单测 | `node node_modules/vitest/vitest.mjs run`（`frontend/`） | **512/512 绿**，**26 个文件** |
| 前端 typecheck | `node node_modules/vue-tsc/bin/vue-tsc.js --noEmit` | **exit 0，0 输出** |
| 文档门禁 | `node scripts/ci/check-docs.mjs` | **6/6 绿**（exit 0，含命名纪律全量扫描 1327 路径） |

与施工报告自报基数（614/614 · 98 类、512/512、6/6）**逐项吻合**。未复核项：`mvn package` 的 jar 与工作树同代（报告 §4 自证，本次未重打包，非本复审门禁要求）；harness 因铁律未运行（C-1 的结论是静态 + 最小复现得出的，不依赖运行）。

## 10 C / I / M / N 清单

### C（阻断，必须修）

| # | 问题 | 证据 | 影响 |
|---|---|---|---|
| **C-1** | 双实例 harness 的健康判据读 `$body.data.ai.available` / `$body.data.redis.available`，而 `/api/ai/health` 返回扁平体（无 `data`、无 `ai`）⇒ 判据恒假，每实例空转 120s 后判整棒 FAIL | `scripts/b23l1-dual-instance.ps1:142-143`；真实体 `AiController.java:743-786`；README 同错 `scripts/README.md:135`；PS 最小复现 `gateNow=False / gateFixed=True` | 本批唯一验收载体**100% 不可用**；若未来无人复核直接跑，会产出一个"实例从未就绪"的假红，极易被误读成产品/Redis 故障，浪费一整轮核验 |

### I（应修，正确性风险）

| # | 问题 | 证据 | 影响 |
|---|---|---|---|
| **I-1** | reattach 的跨实例交接窗口：`stageReplay`（读日志）**早于** `syncWakeSubscription`（建订阅），窗口内落档的帧只能靠"下一次唤醒"补；而挂起/终局帧之后没有下一次唤醒 | `SseChatEmitter.java:503`（`stageReplay`）→ `:513`（`attachSubscription`，`:432` 才订阅）；`:1005` 差量起点 = 回放推进后的水位 | 窗口内若恰是 `suspended` / `done`，B 侧连接**永远等不到**，直到用户再刷新——正是 B1/L0 那一类"卡住了但看起来没坏"。**修法**：把 `syncWakeSubscription()` 提到 `stageReplay` 之前（同在 `emitLock` 内即可；迟到的唤醒会排在锁外、用已推进的水位读差量，天然去重） |
| **I-2** | `onWake` 的 `upperBoundSeq <= from` 早退 + `needed = tail − afterSeq` 有界尾读，在物理序 ≠ seq 序时让低号帧**永久**不可读；D-3 的"下一次唤醒兜底"不成立 | `SseChatEmitter.java:1001`；`RedisFrameStore.java:197-201/206`；报告 D-3（施工报告 §5） | 批② 一旦允许跨实例同 run 出帧即从"理论"变"可达"；表现为某一帧对**所有读者**永久不可见（含重连回放）。**修法（择一）**：①`XADD` 用显式 ID `seq-0`，使物理序 = seq 序（乱序落档会变成显式 append 失败而不是静默洞）；②`readFrom` 增加"上界提示"参数，`count = min(window, max(upper, tail) − afterSeq)`；③最低成本：早退条件收窄为 `upper <= from && 上次读满 upper` |
| **I-3** | `bus.subscribe` 失败（Redis 抖动 / 容器起不来）只降级不回退 `wakeRegistered` ⇒ 该 emitter 在订阅者归零前**永不再试**，跨实例唤醒静默缺席且不可观测 | `RedisFrameWakeBus.java:66-80`；`SseChatEmitter.java:479-487`；`subscribedRuns()` 只数成功项 | 单次 Redis 抖动即让某条连接的跨实例唤醒整段失效（客户端只能靠自己重连）；**修法**：失败时把 `wakeRegistered` 复位为 false（或让 `register` 返回 boolean 由调用方回退） |

### M（建议修）

| # | 问题 | 证据 |
|---|---|---|
| **M-1** | `anchored` 是**进程级永久标记**（每 run 一次），`ai:seq` 键一旦过期/被删（24h 无 append 且无心跳续期、或 `deleteRun` 后同 runId 复用），`INCR` 从 1 重来 ⇒ **号回退 + 与历史同号** ⇒ `mergeBySeq` 同号合并丢帧、前端 `lastSeq` 倒退。降级计数 `fallbackSeqs` 恢复后也有同类"号回退"问题 | `RedisFrameStore.java:80/383-395/397-399` |
| **M-2** | 兼容期 wake 路径每次唤醒都 `LRANGE -3000..-1` **整窗读并逐条 JSON 解析**（只要 LIST 非空），即"每帧 × 3000 次解析 × 每个远端实例"，迁移窗口（24h）内的热路径成本 | `RedisFrameStore.java:188-193/275-296`，由 `readFrom:173` 无条件调用 |
| **M-3** | `archiveMaxSeq` 的 **FrameStore 分支**零测试（生产只走该分支），现测只覆盖 `frameStore=null` 的回落分支 | `AiController.java:512-514`；`AiControllerRunsCurrentTest.java:44-46` |
| **M-4** | 四条降级路径（`INCR`/`append`/`publish`/`subscribe` 失败）**无一条用例**，而它们都被写进 javadoc 当承诺；`append` 失败只有 `debug` 级日志，"日志缺帧"在观测面上近乎不可见 | `RedisFrameStore.java:158-161`、`RedisFrameWakeBus.java:52-55/77-80` |
| **M-5** | 测试牙口申报过强：`FakeFrameRedis` 的 `XADD` 裁剪不读 `XAddOptions.maxlen`，两条 "kill" 注释（M1/M3）不成立；替身不建模 TTL 过期、也不建模物理序 ≠ seq 序（恰是 D-3 的风险面） | `FakeFrameRedis.java:101-113`；`RedisFrameStoreTest.java:57/123` |
| **M-6** | 首/末订阅者并发（要点 3f 点名）无用例；且 `FrameWakeListener.register/unregister` 在**全 run 共享锁**内做 Redis 订阅 I/O 与容器启动，首个连接会阻塞所有 run 的登记与唤醒快照 | `FrameWakeListener.java:53-76`；`RedisFrameWakeBus.java:59-81/103-133` |
| **M-7** | `emit` 在 `emitLock` 内每帧一次**同步** `PUBLISH`（单实例零订阅者照发）；`onWake` 也在同锁内做 1–3 次 Redis 往返。设计稿 §5.4 的"P95 不回退 ±10%"**无任何取证** | `SseChatEmitter.java:940/966-977`、`:997-1005`；设计稿 §5.4 |

### N（提示）

| # | 提示 |
|---|---|
| **N-1** | 工作树里有未跟踪的 `data/files/*.xlsx`（测试残留），`.gitignore` 只忽略 `backend/data/` ⇒ 将来 `git add -A` 会误提交。建议补 `data/` |
| **N-2** | `WAKE_BATCH_LIMIT=6000` 一批补不完时依赖"下一次唤醒"，无自续读（差量 > 6000 的长断连会半补） |
| **N-3** | harness：无端口预检（可假绿）、1.5s 睡眠代替就绪信号、无 Ctrl-C 清理、事件订阅不注销 |
| **N-4** | `RedisFrameStore.append:143` 对无 `seq` 帧会写 `seq=0` 的记录（`streamTailSeq` 会把它当末号），而替身 `InMemoryFrameStore.append:47-50` 直接跳过 ⇒ 生产/替身语义不一致（正常路径不可达） |
| **N-5** | `lastSeq` 取物理尾（`RedisFrameStore.java:230`），乱序时**低估** ⇒ `archiveMaxSeq` 偏小 ⇒ 前端重复回放（方向安全，但会放大流量）；`mergeBySeq` 不检测连续性，中段空洞静默；无 seq 老帧不受 `afterSeq` 约束（`:247`）⇒ **每次 reattach 都会重发**这些升级期老帧 |
| **N-6** | `docs/03-技术方案文档-v2.1.md` 追加注记写"`+ XRANGE`"，实现是 `XREVRANGE ... COUNT`（D-3 偏差未回写文档） |
| **N-7** | `FrameWakeListener` 类注"每个目标自己记已推送到哪个 seq"与实现层次（每 emitter 一个水位）不符，易误读为 per-subscriber |

## 11 附：本批确认通过的关键项（供收口引用）

1. **生产装配唯一性**：`RunRegistry.java:96` 是唯一 `@Autowired` 构造且恒注入 `RedisFrameStore` + wake 两件；三参构造保留只为 conformance/单测，`RunStoreFrameStore` 为包内可见。`@SpringBootTest` 全量上下文用例通过 ⇒ 新 bean 装配可达。
2. **心跳三不**：`emit:906-925` 心跳分支完全不碰 `FrameStore`（不取号/不落档/不发布），`RedisFrameStore.append:137` 二次兜底。三个用例分别从 emitter 侧、store 侧、交错侧钉住。
3. **断连即退订、无泄漏**：`closeSubscription:462` + `FrameWakeListener:71-75`，用例 `lastSubscriberDetachUnsubscribesTheChannel` 断言总线已退订且 `listener.targetCount` 归零。
4. **迁移不断档**：`anchorSeqKey` 用 `SETNX`（`RedisFrameStore.java:388`）+ 遗留 LIST 末帧号，用例 `seqAnchorSeedsFromLegacyListTail`（LIST 发到 41 ⇒ 新实例 42）与 `newFramesContinueAfterLegacyListSeq`。
5. **删除/续期覆盖新键族**：`deleteRun` 删 7 类键、`renewSuspensionTtl` 续 5 类键（有 `times(5)` + 逐键 `verify` 用例）。
6. **文档只追加不回改**：`docs/03-技术方案文档-v2.1.md` 追加段自述"上文一字未改"，check-docs ③（引用文件存在）通过。
7. **未运行/未取证的项自报如实**：D-5（harness 未运行）、D-6（无先红后绿过程证据）、L-1/L-2/L-3 均如实登记，未见粉饰。
