# M2-T3a 施工与验证证据（K2.8）

- 日期：2026-10-09 ｜ 施工：K2.8 ｜ 批次：M2-T3a（T3-1 慢订阅者隔离 + T3-2 seq 持久化，强耦合同批）
- 事实源：`docs/M2-T3-设计卡.md` v1.2 的 T3-1 / T3-2 两节 + R1 裁决小节 + 施工注记 R2/R3
- 基线：HEAD = 2125126（施工前 `git pull --ff-only` 确认 Already up to date，工作树干净）
- 纪律：全程零 git 写操作，完工全部改动未提交；机制开发未用真实模型 key；未读写任何 .env
- 行号口径：本证据引用的 `文件:行号` 均为施工后工作树实测读取【实测】

## 一、T3-1 慢订阅者隔离（R1 裁决 + 施工注记 R2/R3）

### 1.1 emitLock 收窄（设计卡"线程模型 1"）

- 设计卡条款：`emitLock` 内只保留取号、落归档、`touchActivity`（S3-9：必须留锁内同步）、帧入各订阅者队列。
- 实现落点：`SseChatEmitter.java:557-600`（`emit`：心跳分支 555-563 锁内 `touchActivity`；业务帧 565-567 锁内取号+落档；575-579 锁内单次序列化；581-589 锁内逐订阅者入队 + `kickDrain`，网络写出零出现在锁内）。
- 验证证据：`SseChatEmitterDeliveryTest.java:61` 慢订阅者卡住时同轮快订阅者 <1s 收帧【实测绿】；`SseChatEmitterDeliveryTest.java:145` 4 生产者并发入队到达顺序恒等于 seq 顺序【实测绿】。
- 结论：达成。

### 1.2 共享有界投递池 + 每订阅者有界队列 + CAS drain 独占（含 R2/R3）

- 设计卡条款：共享有界投递池（容量初值 4 = 工作线程数，AiProperties 可配）；每订阅者有界队列（256 初值可配）；CAS drain 独占标志（单订阅者 FIFO、慢连接只占一个工作线程）；drain 收尾固定"先清标志、再复查非空则重抢"（R2）；池拒绝 = 摘除触发本次提交的订阅者（R3）。
- 实现落点：
  - 池装配：`SseChatEmitter.java:161-179`（`configureSharedDeliveryPool`，corePool=maxPool=池容量、有界任务队列、AbortPolicy），`RunRegistry.java:44-52`（Spring 路径用 `app.ai.sse.*` 装配，幂等）；未装配时回落同步直执（`SseChatEmitter.java:142` 三参构造，单测确定性路径）。
  - 队列与标志：`SseChatEmitter.java:727-751`（`Subscription`：无界队列本体 + CAS `draining` + 摘除去重 `evicted`）；实时容量闸 `enqueueLive`（`SseChatEmitter.java:608-614`，生产者在 emitLock 内串行故 `size()` 判定精确）。
  - 入队/抢权/提交：`kickDrain` 617-621、`submitDrain` 626-636（拒绝对 `RejectedExecutionException` 摘除该订阅者，R3）。
  - drain 收尾 R2：`SseChatEmitter.java:661-668`——`finally` 内先 `draining.set(false)`，再"队列非空 ∧ 重新 CAS 成功 ∧（终态回放 ∨ 仍在订阅表）"则补投。
- 验证证据：
  - R2 并发用例：`SseChatEmitterDeliveryTest.java:145`（4 线程 × 100 帧 + 写出抖动，400 帧一帧不丢且 seq 严格 1..400）【实测绿】。
  - 溢出摘除（S2-1）：`SseChatEmitterDeliveryTest.java:90`（容量缩放 4，第 6 帧溢出 → `verify(emitter).complete()` + `subscriberCount()==0`）【实测绿】。
    **覆盖口径更正（修复批，详见第七节 7.3）**：本条锚定的是"实时队列溢出摘除"，与 R3（投递池任务提交被拒 → 摘除触发本次提交的订阅者）**不是同一条路径**，施工时把本条记为 R3 验证属口径偏差；R3 分支（`SseChatEmitter.java` 的 `submitDrain` catch）当时无任何用例，修复批已补定点用例（`SseChatEmitterDeliveryTest.java:321`，修复批行号）。
  - 配置面：`AiProperties.java:160-183`（`app.ai.sse.delivery-pool-size=4`、`delivery-queue-capacity=256`，注释注明 T3-5 压测定值）。
- 结论：达成。

### 1.3 replay 路径并轨（S1-2 修订 + R1 裁决回放豁免）

- 设计卡条款：`replayAndAttach` 在 emitLock 内完成"收集回放帧（LRANGE 只读，不做 IO 写）+ 创建订阅者 + 回放帧入队首 + 挂订阅"原子交接；网络写出统一走异步队列；回放批豁免 256 容量，上界 = EVENT_WINDOW（3000）；"队列容量 < 归档窗口"只约束实时段。
- 实现落点：`SseChatEmitter.java:218-238`（`replayAndAttach`）、`259-272`（`replayTo`）、`281-301`（`stageReplay`：锁内只读 `store.events`、跳过 delta/heartbeat、孤儿过滤、豁免容量入队）。
- 验证证据：
  - 回放 3000 帧全量入队（R1 不变量②）：`SseChatEmitterDeliveryTest.java:117`（实时容量 256 下 3000 帧全部送达且保序，第 3001 帧实时续接）【实测绿】。
    **覆盖口径更正（修复批第 2 轮，详见第八节 8.2）**：该用例的构造是"回放批**先排空**、再发实时帧"，修复前按队列绝对长度判定的闸同样放行 —— 它只锚定 `stageReplay` 的**入队**豁免，**不能**作为"回放批豁免实时容量"的验证证据（属假安全）。红队 S1 击破后，该不变量改由定点用例 `SseChatEmitterDeliveryTest.java:185`（backlog 未排空期间混入实时帧不摘除）与 `:218`（实时段自身超限仍摘除）锚定。
  - 原子交接回归：`ReattachLastSeqConformanceTest.java:176`（S5c-2 用例原样通过，未修订）【实测绿】。
- 结论：达成。

### 1.4 溢出摘除 = 主动断连 + 前端可重挂（S2-1）

- 设计卡条款：摘除时必须显式 `emitter.complete()` 触发前端 onerror/onclose → lastSeq 重挂补帧；补"摘除后断点续收 seq 无洞"用例。
- 实现落点：`SseChatEmitter.java:685-703`（`evict`：移出订阅表 + 清队列 + 计数 + `completeOne`）；`completeOne` 705-714。
- 验证证据：`SseChatEmitterDeliveryTest.java:196`——状态帧流溢出摘除（complete 已验证）→ `lastSeq=1` 重挂补回 seq 2..6，无洞【实测绿】。
- 结论：达成。

### 1.5 容量联动注释（S2-8）

- 设计卡条款：公式改为"专用池 ×2 + 投递池 ≤ boundedElastic"，纳入 AiProperties 注释。
- 实现落点：`AiProperties.java:53-62`（Suspend.poolSize 注释改联动公式）、`AiProperties.java:151-159`（Sse 节类注释）。
- 验证证据：文本实测读取；压测定值本身属 T3-5（本批不做）。
- 结论：达成（注释口径）；定值留 T3-5。

### 1.6 /api/ai/health 投递池指标

- 设计卡条款：poolSize / active / queueDepth / evicted。
- 实现落点：`SseChatEmitter.java:181-190`（`deliveryPoolMetrics`）、`AiController.java:531`（`sseDelivery` 指标块）。
- 验证证据：编译期类型核对 + 单测锚定摘除计数（evicted 语义随用例 `liveQueueOverflow…` 走通）【实测】；HTTP 端点实调属 T3-5 压测收尾，本批未起服务实调【推断：指标块为纯读静态池的 Map 装配，无分支逻辑】。
- 结论：达成（机制面）；端点实测归 T3-5。

## 二、T3-2 seq 持久化 + delta 跳过归档

### 2.1 ai:seq:<runId> 键与续号口径

- 设计卡条款：String 键存已发放最大业务 seq；`nextSeq`（emitLock 内）候选 = max(归档末帧, ai:seq) + 1，发放即 SET；每帧同刷 TTL=session-ttl（与归档同寿）。
- 实现落点：
  - 键定义与读写：`RunStore.java:90`（`SEQ_PREFIX`）、`146-148`（`seqKey`）、`568-583`（`lastIssuedSeq`）、`588-600`（`recordIssuedSeq`：SET + `ttl()`，即 session-ttl，随每帧刷新）；类注 44-47 行登记键结构。
  - 续号：`SseChatEmitter.java:535-556`（`nextSeq`：惰性 anchor = `max(lastEventSeq, lastIssuedSeq)`，发放即登记）。
  - 键生命周期对齐：`RunStore.java:288-292`（`deleteRun` 纳入 seqKey，池饱和回滚不留半截）。
- 验证证据：`SeqPersistenceTest.java:64` 重号场景——发放 3 帧、落档第 2/3 帧失败（`FlakyArchiveStore` 注入）→ 新实例续跑首帧 seq=4、归档 `[1,4]` 无重号【实测绿】。
- 结论：达成。

### 2.2 SET 失败口径（S2-4）

- 设计卡条款：WARN 级日志 + 继续出帧；承诺收窄为"归档部分失败场景"，整体 Redis 宕机重号风险登记观察项。
- 实现落点：`RunStore.java:588-600`（内部 catch → WARN + 不抛出）；`SseChatEmitter.java:549-554`（发放侧再兜一层：任何实现异常不得中断出帧）。
- 验证证据：`SseChatEmitterDeliveryTest.java:229`——`recordIssuedSeq` 抛异常时 3 帧照常送达且 seq=1,2,3 连续【实测绿】。
- 结论：达成。观察项（整体 Redis 宕机下 ai:seq 停更、重启续号可能回退）按设计卡登记，不新增缓解措施。

### 2.3 delta 跳过归档

- 设计卡条款：`appendEvent` 对 delta 帧跳过（与心跳同待遇）；强耦合声明：与 ai:seq 同批上线（本批已同批落实）。
- 实现落点：`RunStore.java:537-556`（delta/heartbeat 统一跳档 + `touchActivity` 留活动戳）；`RunStore.java:87`（`DELTA_TYPE` 常量）；替身同步 `InMemoryRunStore.java`（appendEvent 同口径，T3-2 语义段落）。
- 验证证据：`SeqPersistenceTest.java:89`——`LLEN`（归档帧数）只计状态帧（delta×2、heartbeat 均不入档），业务序号仍连续（`lastIssuedSeq=6`）【实测绿】。
- 结论：达成。注：delta 跳档后与心跳同待遇留活动戳，纯 delta 长轮不被僵尸判据误判——活动戳写频率观察项登记（原 delta 落档时本就有 Redis 写，现改为 STRING SET，量级相当）【推断】。

### 2.4 includeDelta 死分支与 persistedFrameCount 处置

- 施工前必查（设计卡要求）：四个帧协议测试的 delta 入档依赖盘点【实测】：
  - `SseChatEmitterFramesTest`：以 mock(RunStore) 断言 `appendEvent` 捕获——**跳过归档发生在 RunStore 实现内部，mock 口径下 delta 仍会到达 appendEvent，无入档断言依赖**；仅 `replayTo` 签名（传 `false`）需随参数删除同步（2 处）。
  - `ReattachLastSeqConformanceTest`：回放语料经 `seedArchive` 直铺（不走 appendEvent），**无 delta 入档依赖**；`replayTo` 签名 6 处同步。
  - `ChatTurnFrameSequenceConformanceTest`：`clientView()` 旧实现 = 纯归档视角，`fullTurn/whitespace/partialContent/cancelled` 四个用例的"客户端完整流"断言**依赖 delta 入档**（事实依赖确认，设计卡预判"连测试都一律传 false"之外的隐性依赖）——已按 T3-2 语义修订（见 2.5）。
  - `SseFrameSequenceConformanceTest`：`heartbeatKeepsItsOwnSequenceSpace…` 的 `archivedTypes/archivedSeqs/lastEventSeq` 断言**依赖 delta 入档**——已修订（见 2.5）。
- 处置：`replayTo(SseEmitter, boolean, Long)` 形参删除（`SseChatEmitter.java:259`），delta/heartbeat 跳过改为无条件（状态帧回放口径不变，老归档里的历史 delta 帧仍被跳过）；`persistedFrameCount()` 全仓零调用（grep 实测），按"删除"选项处置并留痕于此。
- 结论：达成；两处隐性 delta 入档依赖已识别并同步修订（设计卡允许"有则同步修订并说明"）。

### 2.5 InMemoryRunStore 替身同步

- 设计卡条款：替身同步 ai:seq 语义。
- 实现落点：`InMemoryRunStore.java`——`issuedSeqs` 表 + `lastIssuedSeq/recordIssuedSeq` 覆写（发放即登记、跨实例可读）、`appendEvent` delta 跳档同口径、`deleteRun` 清表、`seqKey` 同形；类注新增"T3-2 语义同步"段落；类去 `final`（供 `FlakyArchiveStore` 失败注入子类化，仅测试范围）。
- 验证证据：`SeqPersistenceTest` 三用例全部跑在替身语义上【实测绿】；四个帧协议测试类全量回归绿【实测】。
- 结论：达成。

### 2.6 events 端点全量读一致性

- 设计卡条款：`RunStore.events`（:618-634）本就是回放唯一读者路径，随 replay 并轨自然一致。
- 验证证据：回放实现唯一读档口为 `stageReplay` → `store.events`（`SseChatEmitter.java:283`），无第二读档路径【实测（代码阅读）】。
- 结论：达成。

## 三、既有测试修订清单（随施工同步修订并说明）

| 测试 | 修订 | 原因 |
|---|---|---|
| `ChatTurnFrameSequenceConformanceTest` | `clientView()` 改为"归档状态帧 + 实时 delta 按 seq 归并"（:303），5 处调用点随签名更新；其中 1 处补绑定 `FrameWire wire = runTurn()`（:226） | T3-2 后归档不再是客户端完整流超集；该归并视角即前端实际持有的帧集（回放状态帧 + 实时 delta），与不变量⑤"回放+实时拼完整一轮"同构 |
| `SseFrameSequenceConformanceTest` | `heartbeatKeepsItsOwnSequenceSpace…`（:138）归档断言改为"`archivedTypes=[start]`、`lastEventSeq=1`、锚点迁至 `lastIssuedSeq=2`" | delta 跳档后归档只剩状态帧，"归档=续号锚点"旧断言与新语义冲突，按 T3-2 语义同步 |
| `ReattachLastSeqConformanceTest` | `replayTo` 6 处删 `includeDelta=false` 实参 | 形参删除的机械同步 |
| `SseChatEmitterFramesTest` | `replayTo` 2 处删实参 + 1 处注释措辞 | 同上；该类的 `appendEvent` 捕获断言无需改（见 2.4 盘点） |
| `InMemoryRunStore` | 见 2.5 | 替身语义同步 |

## 四、验证汇总（本棒实跑，全部【实测】）

- 后端全量：`cd backend && mvn test` —— **Tests run: 272, Failures: 0, Errors: 0, Skipped: 0，BUILD SUCCESS**（2026-10-09 本机实跑）。
- XML 口径独立重算：42 个 surefire XML 的 `tests/failures/errors` 属性求和 = **272 / 0 / 0**，与控制台口径一致；基线 263 ⇒ **272（+9 = 新单测 DeliveryTest 6 + SeqPersistenceTest 3），只增不减**。
- 前端零改动：`npm run typecheck`（vue-tsc --noEmit）通过，零错误。
- scripts 零改动：`git status` 全量清单仅含 backend 下 10 改 2 增（见脱敏自查），无 frontend/scripts 条目。
- 集成冒烟（桩上游挂起轮 + 慢订阅者）：**未执行**——设计卡定位"可用……非强制"，单测已锚定双不变量与摘除语义，端口径实测归 T3-5 压测收尾。

## 五、【待裁决】与偏差登记

1. **无设计冲突**：施工全程未发现设计卡与代码现实冲突处，未停工项。
2. 偏差（实现层自由裁量，均不违背设计卡文字）：
   - 投递池任务队列容量取 `poolSize × 64`（`RunRegistry.java:49-51`）——设计卡只规定"工作线程数 4"与"拒绝即摘除"，未规定池内队列长度；本值为初值，随 T3-5 定值复核。
   - 实时队列"有界"实现为"无界队列 + emitLock 内精确容量闸"（`SseChatEmitter.java:608`）——生产者持锁串行使 `size()` 判定精确，回放批豁免（R1）因此免第二批入队通道；若指挥官要求物理有界（ArrayBlockingQueue），需为回放批另设暂存道，可回改。
   - `replayAndAttach/replayTo` 返回值语义由"实际发出帧数"变为"入队帧数"（异步写出前口径，`SseChatEmitter.java:218,259`）——唯一消费方是 `AiController.java:293` 的 DEBUG 日志，无行为影响。
   - 观察项（设计卡已登记口径的落实确认）：整体 Redis 宕机下 ai:seq 停更、重启续号可能回退（S2-4 承诺收窄）；delta 活动戳为每 delta 一次 STRING SET（僵尸判据输入的必要代价）。

## 六、脱敏自查（以 `git status` 全量为准，含本文档自身）

- 全量改动清单（12 项，均 backend 内，未提交）：AiProperties / RunRegistry / RunStore / SseChatEmitter / AiController（main，5 改）；ChatTurn / InMemoryRunStore / ReattachLastSeq / SseFrameSequence / SseChatEmitterFramesTest（test，5 改）；SeqPersistenceTest / SseChatEmitterDeliveryTest（test，2 增）。**frontend、scripts 零改动**。
- 禁用字面量扫描：全部改动文件 + 本文档不含旧主仓目录名组合字样（按命名纪律禁则）、不含本机盘符路径（统一 `<REPO_ROOT>` 或相对路径表述）、无 key 明文（本批未涉及凭证，符合"key 记 ***"纪律——无 key 可记）。
- 本文档自身：路径一律相对仓库根；无盘符、无禁则字样组合、无凭证。
- git 写操作零发生（无 add/commit/push/stash），完工保持工作树未提交状态。

## 七、修复批（验收发现处置，2026-10-09）

- 触发：M2-T3a 验收盘点（红队视角复盘）提出 1 项阻塞（S1-1）+ 2 项应闭环（S2-1/S2-2）+ 1 项范围外改动 + 1 项不变量未锚定（S3-1），指挥官裁决修复；本批为修复处置留痕。
- 修复批开工前基线：全量 `mvn -B test`（<MAVEN_HOME> 下 mvn）控制台与 XML 口径均为 **272 / 0 / 0 / 0**，与本证据第四节一致【实测】。
- 行号口径：本批改动使 `SseChatEmitter.java`、`AiProperties.java`、`RunRegistry.java`、`RunStore.java` 行号整体位移。**前六节的 `文件:行号` 是修复批前的读数**；跨批引用以本节为准。
- 纪律：仍是零 git 写操作（无 add/commit/push/stash/checkout/restore），修复批改动全部未提交；未读写任何 .env 与凭证。

### 7.1 S1-1（阻塞）：终态 `complete()` 不排空队列丢帧

- 缺陷形态（修复前）：`RunRegistry.close()` → `SseChatEmitter.complete()` 直接对每个订阅者 `completeOne`（立刻 `emitter.complete()`）。生产调用点在轮终态的 `finally`（`AiController` / `ResumeService`），此刻慢订阅者队列里还压着尾帧（`delta`/`done`/`error`/`suspended`）；而真实 `SseEmitter` 在 complete 之后再写必失败 ⇒ **确定性丢帧**。
- 修复实现（行号为本节实测读数）：
  - `SseChatEmitter.java:770-775`（`complete()`）：置轮终态标志 + 逐订阅者 `completeWhenDrained`，**不同步等排空**（调用点是轮终态 `finally`，等排空会拖住轮次收尾）。
  - `SseChatEmitter.java:785`（`completeWhenDrained`）：先把终态标志落到订阅者上下文（`Subscription.terminating`，`:828`），再按 drain 独占权分流 —— 抢到且队空 ⇒ 立即收尾；抢到且队非空 ⇒ 起 drain 排空；抢不到 ⇒ 交给在跑的 drain。
  - `SseChatEmitter.java:702-704`（`drain` 的 `finally`）：`(sub.terminal || sub.terminating) && queue.isEmpty() ⇒ completeOne`——即"**drain 循环队空分支**才收尾"，与既有终态回放路径（`sub.terminal`）复用同一分支。
  - `SseChatEmitter.java:603-607`（`emit`）：`subscribers.isEmpty() || terminating` 时不再扇出 —— 终态后不收新帧，否则"排空"会被新帧无限推迟；帧照旧取号落档，重挂回放不受影响。
  - `SseChatEmitter.java:727-739`（`completeOne`）：以 `Subscription.completed`（`:831`）CAS 去重，轮终态排空 / drain 队空 / 摘除三条路径并发命中只 `complete()` 一次。
- 新用例（`SseChatEmitterDeliveryTest`）：
  - `:262` 慢订阅者（首帧写出阻塞）+ 队列压 `delta×2 + done`，轮终态 `complete()` 后释放 ⇒ 尾帧按序全达、恰好一次 complete、订阅表清空。
  - `:286` 池饱和（1 工作线程 + 慢写出）：慢订阅者占住工作线程、另一订阅者的 drain 排在池队列里，`complete()` 后**两者都收到全部终帧**。
  - 判据口径：订阅者桩 `TerminalSemanticsEmitter`（`:410`）在 `complete()` 之后的 `send` 抛 `IllegalStateException`，与真实 `ResponseBodyEmitter` 同口径 —— 若桩件照单全收，"先 complete 再排空"的错误实现也会假绿。
- 验证（**【实测】红→绿**，TDD 顺序：先写用例 → 未修复代码跑红 → 实施修复 → 绿；不是"事后注释修复代码"）：
  - 红（未修复代码，日志 `m2t3a-fix-red2.log`）：两条用例的"终态尾帧必送达"断言超时失败（`:278` / `:306`）【实测红】；
  - 中间态（首版修复只置了写出器级终态标志、未把标志落到订阅者上下文）：尾帧已送达，但"排空后收尾"断言超时（`:280` / `:308`）—— 定位为"在跑的 drain 看不到终态标志、空转退出后不再收尾"，在 `completeWhenDrained` 补 `sub.terminating = true`（`:787-789`）后同用例全绿【实测】。
- 结论：达成。

### 7.2 S2-1：静态投递池泄漏到测试路径

- 缺陷形态（修复前）：`RunRegistry.of()` 每次调用 `SseChatEmitter.sharedDeliveryExecutorOrDirect()`；`@SpringBootTest` 装配进程级静态池后，**两参构造（单测/非 Spring）也由同步直执变异步投递** = 同一 JVM 内测试顺序相关竞态。
- 修复实现：`RunRegistry.java:49 / :65 / :69-74` —— 投递执行器在**构造期定格**：Spring 三参（`properties != null`）装配静态池后取共享池（池先装配后取用，`configureSharedDeliveryPool` 幂等），两参构造固定 `Runnable::run`（同步直执），**不再读进程静态池**；生产三参装配路径的池形参、幂等装配、`AiProperties` 取值均未变（行为不变）。
- 口径注释：`RunRegistry.java:39-48`（字段注释写明"两参固定直执，否则静态池一旦被 @SpringBootTest 装配，帧序用例会同 JVM 变异步"）。
- 新用例：`SseChatEmitterDeliveryTest.java:360`（两参注册表：写出发生在调用线程 + 投递执行器不是 `ThreadPoolExecutor`）。
- 回归：三个两参构造的帧序一致性用例（`ChatTurnFrameSequenceConformanceTest`、`ToolFrameSequenceConformanceTest`、`SessionBusyFrameIsolationConformanceTest`）全量绿，无需修订（它们本就要求同步直执的确定性）【实测绿】。
- 结论：达成。

### 7.3 S2-2：R3 池拒绝分支补单测（并更正 1.2 节覆盖口径）

- 缺陷形态：`submitDrain` 的 `RejectedExecutionException → 清 draining 标志 → evict` 分支（`SseChatEmitter.java:655-664`）**无任何用例**；1.2 节把"实时队列溢出摘除"用例记为 R3 验证，属**覆盖口径偏差**（两者不是同一条路径：溢出摘除在 `emit` 的容量闸，池拒绝在 `submitDrain` 的提交点）。1.2 节已就地更正（见该节"覆盖口径更正"一行）。
- 新用例：`SseChatEmitterDeliveryTest.java:321` —— 池 = 1 工作线程 + 池队列容量 1 + `AbortPolicy`：慢订阅者占住工作线程、第二个订阅者占满池队列，第三个订阅者的提交**必被拒**（构造上确定，不靠概率）；断言：
  1. 被拒订阅者被摘除（显式 `complete()` 恰一次、订阅表 -1）；
  2. 其 `draining` 独占标志被清干净；
  3. 其他订阅者不受影响（释放后照常收帧）。
- 白盒说明：`draining` 是私有内部标志、无对外可观测面，用例以反射读字段（`fieldOf`，`:532`）——这是断言"标志被清理"的唯一途径，已登记在此。
- 结论：达成（分支有定点用例 + 口径偏差已更正）。

### 7.4 撤销范围外改动（`frontend_tool_result` 的 `kind` 字段）

- 先核后撤：`git diff HEAD -- SseChatEmitter.java` 实测确认 `frontend_tool_result` 分支的 `frame.put("kind", pending.getKind())` 确为本批新增行（HEAD 同方法无此行），**只撤这一处**（`SseChatEmitter.java:485-496` 还原 HEAD 形态；`tool_start`/`tool_result` 的既有 `kind` 不动）。
- 复核依赖：`grep frontend_tool_result` 全量盘点 —— 测试只断言 `executed`/`ok`/`status`/`messageId`，无任何断言依赖该字段；撤销后全量绿【实测】。
- 结论：达成（范围外改动已清零）。

### 7.5 S3-1：不变量锚定 + 消除 256 双处字面量

- 单一事实源：`AiProperties.java:181` 新增 `public static final int DEFAULT_DELIVERY_QUEUE_CAPACITY = 256`；配置项缺省（`:190`）、`SseChatEmitter` 三参构造、`RunRegistry.of()` 缺省分支全部引用它；`SseChatEmitter` 内原先的同名常量（第二处字面量）已删。
- 归档窗口公开口径：`RunStore.java:99` 的 `EVENT_WINDOW` 由 private 改 public（**值不变，仍为 3000**），供不变量断言直接锚定。
- 测试锚点：`SseChatEmitter` 增加包内可见 `queueCapacity()`（`:177`），仅测试使用（登记：这是本次为"值改坏即红"新增的唯一生产侧可见性）。
- 新用例：`SseChatEmitterDeliveryTest.java:378` —— 断言「缺省实时队列容量 = 单一事实源 < `EVENT_WINDOW`」，且写出器缺省路径与注册表缺省路径同源；配置项改 128 时注册表取配置值。
- "值改坏即红"的三种破坏方式：缺省改成 ≥ 3000、`EVENT_WINDOW` 改小于缺省、任一条装配路径重新写死字面量 —— 任一都会让该用例失败。
- 结论：达成。

### 7.6 S3-4 后果登记（delta 不入档）

- 后果（验收发现登记）：T3-2 让 delta 跳过归档后，**断流期间的正文尾部不可由回放复原** —— 断点续收（`lastSeq` 差量补发）只保证**状态帧** `seq` 无洞，正文尾部能否补齐取决于前端兜底（本地已渲染文本 + 后续 delta 续接，或 T3b 的正文补齐方案）。
- 处置：本批**不新增机制**（设计与验收口径未要求），仅登记；前端兜底路径待 T3b 确认。
- 设计卡同步：`M2-T3-设计卡.md` T3-2 节补一行后果注记（注明"验收发现登记"），不改设计口径。
- 结论：登记完成。

### 7.7 修复批验证汇总（全部【实测】）

| 项 | 命令 | 结果 |
|---|---|---|
| 后端全量（修复前基线） | `mvn -B test` | Tests run: 272, Failures: 0, Errors: 0, Skipped: 0，BUILD SUCCESS；XML 口径 272 / 0 / 0 / 0 |
| 后端全量（修复后） | `mvn -B test` | Tests run: **277**, Failures: 0, Errors: 0, Skipped: 0，BUILD SUCCESS；XML 口径 **277 / 0 / 0 / 0**（42 个 surefire XML 求和） |
| 增量 | — | 272 ⇒ 277，**+5 全为新用例**（S1-1 ×2、R3 ×1、S2-1 ×1、S3-1 ×1），无用例被删除或改写口径；0 失败 0 错误 |
| 新用例抗抖动 | `-Dtest=SseChatEmitterDeliveryTest` ×3 轮 | 每轮 11 / 0 / 0 / 0，BUILD SUCCESS |
| 修复 1 红→绿 | 先写用例 → 未修复代码跑 | 红：`:277` / `:305` 尾帧断言失败；修复后绿 |
| 前端兜底 | `yarn typecheck`（vue-tsc --noEmit） | 通过，零错误（前端本批零改动，仅复跑） |

- 未执行项：真实 HTTP + 慢客户端的集成冒烟仍未做（口径同第四节：归 T3-5 压测收尾）；`/api/ai/health` 投递池指标实调同样归 T3-5。

### 7.8 修复批脱敏自查（以 `git status` 全量为准）

- 全量改动：12 个 backend 文件（5 main 改 + 5 test 改 + 2 test 增，与第六节清单一致，**修复批未新增文件**）+ 本证据文档 + 设计卡（`M2-T3-设计卡.md`，一行注记）= 14 项；**frontend / scripts 零改动**（typecheck 仅运行，未产生改动）。
- 禁用字面量扫描：本批新增与改动文本不含本机盘符路径（本文件统一用仓库相对路径或 `<REPO_ROOT>` / `<MAVEN_HOME>` 占位）、不含主仓目录名带版本后缀的组合字样（命名纪律）、无任何凭证（本批未涉及密钥，无 key 可记）。
- git 写操作零发生；修复批全部改动保持未提交状态。

## 八、修复批第 2 轮（红队 S1 击破处置 + 假安全用例更正 + S3-1/S3-3/S3-5，2026-10-09）

- 触发：DS-V4-Pro 红队以**仓库外探针**（`<REPO_ROOT>` 之外的 `m2t3a-probe/ProbeR1.java`，只读引用、未改动）击破第一轮的"双口径不变量"——"回放批豁免实时容量"只在**入队**口径成立，实时**容量闸**仍按队列绝对长度判定，两者不一致构成确定性饥饿。指挥官裁决修复，本批为处置留痕。
- 开工基线：全量 `mvn -B test`（`<MAVEN_HOME>` 下 mvn）= **277 / 0 / 0 / 0**（控制台与 42 个 surefire XML 求和一致），与 7.7 节一致【实测】。
- 行号口径：本节 `文件:行号` 为**本批施工后**工作树实测读数【实测】；第一~七节的读数为更早形态，跨批引用以本节为准。
- 纪律：仍是零 git 写操作（无 add/commit/checkout/restore/stash），改动全部保持未提交；未读写任何 .env 与凭证。

### 8.1 红队 S1（阻塞）：实时闸与回放 backlog 未分离 ⇒ 慢重挂者确定性饥饿

- **缺陷形态（修复前）**：`SseChatEmitter.enqueueLive` 的容量闸判 `sub.queue.size() >= queueCapacity`（**队列绝对长度**），而回放批 `stageReplay` 入的帧与实时帧共用同一条队列。慢重挂者回放 3000 帧入队、drain 还在慢慢写出时，挂起期每 2s 的一条实时心跳（生产上 `ConfirmGate#await` 的节拍）就会看到"队列里压着 2999 帧"，被误判为实时溢出 → `evict` + `complete` 摘除一个**健康**订阅者；前端带 `lastSeq` 重挂后剩余回放仍 ≥256，再被摘除 ⇒ **确定性饥饿**，且与 R1 裁决"回放批豁免有界容量"的意图直接相悖。
- **红队击破证据（本批复跑，仓库外探针原样编译运行）**：
  - 缺陷形态（把闸还原为队列绝对长度后复跑；探针日志 `m2t3a-fix2-probe-before.log`）：`[场景1 回放中混入实时帧] staged=3000 首帧阻塞中已收=0 实时心跳后 complete=true 最终收到=0`；`[场景2 断点续收重挂] staged=2999 实时心跳后 complete=true 本次只收到=0 帧即被摘除` —— 两场景都在**第一条**实时心跳上被摘除【实测】。
  - 修复后（工作树；探针日志 `m2t3a-fix2-probe-after.log`）：`[场景1] staged=3000 … complete=false 最终收到=-1`；`[场景2] staged=2999 … complete=false 本次只收到=-1` —— `complete=false` + `deliveredAtComplete=-1`（从未收尾）即"未被摘除"，回放宽限期结束前订阅者存活【实测】。（行尾"（回放剩余被摘除）"是探针的**静态文案**，非本次判定字段。）
- **修复口径（实时段独立计数器，采指挥官指定方案）**：
  - `Subscription.liveQueued`（`AtomicInteger`，`SseChatEmitter.java:924`）= 该订阅者**队列中的实时帧条数**，实时容量闸改判 `liveQueued >= queueCapacity`（`SseChatEmitter.java:692-698`）；回放批不计入。
  - 队列元素改为 `QueuedFrame{payload, live}`（`SseChatEmitter.java:966-978`）：实时入队 `live=true`（`:696`）、回放入队 `live=false`（`:370`）；`drain` 弹出实时帧时递减（调用点 `SseChatEmitter.java:751-757`，`liveDequeued` 定义 `:709-711`）。
  - **单队列 FIFO 混合序保留**：回放批与实时帧仍走同一条 `BlockingQueue`（未拆双通道），seq 连续性与"到达顺序 = 取号顺序"语义不变——标记法只把"归哪条闸管"这一位信息放在元素上。
  - 计数兜底为**不减到负**（`updateAndGet(current -> current > 0 ? current - 1 : 0)`）：唯一能造成"计数已归零却又弹出实时帧"的来源是并发清空（清空的 `set(0)` 落在本次 `poll` 与递减之间），此时该订阅者已被关闭，保持 0 即正确口径（为负会让容量闸永久失效，比轻微少计更危险）。
- **`liveQueued` 全路径归置核对（"不为负、不留残"）**：

  | 路径 | 落点 | 归置方式 |
  |---|---|---|
  | 实时入队 | `enqueueLive` `:692-698` | 闸判通过后 `+1`（生产者在 emitLock 内串行，判定精确） |
  | 回放入队 | `stageReplay` `:370` | **不计数**（`live=false`） |
  | drain 弹出 | `:751-757` | 实时帧 `-1`（`liveDequeued` `:709-711` 兜底不为负） |
  | 摘除 `evict` | `:806-813` | 走 `closeSubscription` ⇒ 清队列 + `set(0)` |
  | 收尾 `completeOne` | `:824-838` | 走 `closeSubscription` ⇒ 清队列 + `set(0)`（所有调用点队列本已空，不丢帧） |
  | 生命周期三回调 | `:248-252` ⇒ `closeSubscription` `:275-282` | 同上（本批新增，见 8.3） |
  | drain finally 重抢 | `canTakeOverDrain` `:793-796` | 不开新计数；CAS 后置，条件不成立即退出（不留 `draining` 残影） |
  | `completeWhenDrained` | `:882-897` | 不碰队列与计数，只分流收尾（队空 ⇒ 直接 `completeOne`；队非空 ⇒ 自跑 drain） |

- **drain finally 的 CAS 后置加固**：旧形态 `!queue.isEmpty() && CAS(false→true) && (terminal || terminating || subscribers.contains(sub))` 在"CAS 抢到但订阅者刚被摘除"时会让 `draining` 永远停在 `true`（残影）；本批把守卫合并为 `canTakeOverDrain`（`:793`）并置于 CAS **之前**——抢权只在"确实要提交"时发生，残影窗口消失。R2 的固定顺序（先清标志、再复查、后 CAS 抢回）未变，只去掉"抢到却放弃"的可能。
- **新用例（红队场景的一对一定点等价覆盖，`SseChatEmitterDeliveryTest`）**：
  1. `liveFramesDoNotEvictAReattachingSubscriberWhileTheReplayBacklogIsDraining`（`:185`）= 探针场景 1（全量回放）：seed 3000 帧 → 慢订阅者 `replayAndAttach` 首帧写出阻塞 → 发心跳 + 业务 delta + 心跳 → 断言**不摘除**（`completes()==0`、`subscriberCount()==1`）→ 释放后断言 3003 帧全量按 FIFO 混合序送达（前 3000 `tool_start`，后 `heartbeat/delta/heartbeat`）且实时帧 `seq=3001` 续接归档末帧；
  2. `liveSegmentOverflowDuringTheReplayBacklogStillEvictsTheSubscriber`（`:218`）= 反向锚定：排空期实时段**自己**塞满 256 条不摘除、第 257 条摘除（S2-1 语义不变）；
  3. 探针场景 2（带 `lastSeq` 的差量重挂，`staged=2999`）与场景 1 走的是**同一条**闸判代码路径（`enqueueLive`），差别只在 `stageReplay` 的孤儿过滤帧数 → 由用例 1 等价覆盖。
- 结论：达成（红队 S1 闭合；探针复跑与定点用例双证）。

### 8.2 假安全用例更正登记

- **被更正对象**：`replayBatchBypassesLiveCapacityUpToTheEventWindow`（`:151`）。
- **假安全成因**：该用例的构造是"`replayAndAttach` → `awaitSize(3000)` **等回放批排空** → 再发实时帧"，实时帧到达时队列已空 ⇒ 修复前按队列绝对长度判定的闸同样放行，**用例在缺陷代码上也绿**，却挂在"回放批豁免实时容量"的名下（1.3 节曾以它作为不变量②的验证证据，属覆盖口径偏差）。
- **更正处置**：① 用例保留但**收窄声明**——类注与用例注写明它只锚定 `stageReplay` 的**入队**豁免，不覆盖"backlog 未排空期间实时帧到达"的竞态；② 真正的不变量②验证移交 8.1 的两条定点用例（缺陷代码上必红，见 8.6）；③ 1.3 节就地补一行更正指引。
- 结论：更正完成。

### 8.3 S3-1：生命周期三回调补齐清理（原仅摘表）

- **缺陷形态（修复前）**：`attachSubscription` 的三个回调只做 `subscribers.remove(sub)`（旧 `:230-232`）——慢连接**超时/断开**（`onTimeout`/`onError`）时，队列里滞留的帧（生产上是重挂回放的千级状态帧）无人释放 = **内存滞留**，drain 独占标志也留在原地。
- **修复实现**：三回调统一走 `closeSubscription`（`SseChatEmitter.java:248-252` 登记，`:275-282` 清理）——摘表 + `closed=true` + `queue.clear()` + `liveQueued.set(0)` + `draining.set(false)`；`evict`（`:806-813`）与 `completeOne`（`:824-838`）复用同一条清理路径（"清理口径只有一处"）。
- **`closed` 标志 = "安全取消挂起 drain"**：置位后 `emit` 的扇出跳过该订阅者（`SseChatEmitter.java:666-670`，覆盖 CopyOnWriteArrayList 快照与摘除之间的窄窗），于是一个已被清理的订阅者不会再被入队/抢 drain；在跑的 drain 最多多空转一轮（队列已空，`poll` 即退出），不会把清理结果改回去。
- **与在跑 drain 的并发口径**：`closeSubscription` 只置 `draining=false`，不等待在跑的 drain；其 `finally` 因 `canTakeOverDrain` 的 `!closed` 与空队列双重判假而不再抢权（残留窗口已消，见 8.1）。
- **新用例（此前零覆盖）**：`timeoutCallbackClears…`（`:495`）、`completionCallbackClears…`（`:500`）、`errorCallbackClears…`（`:505`）共用 `assertLifecycleCallbackCleansDeliveryState`（`:516`）——构造"首帧卡住 + 4 帧滞留队列"（断言触发前队列恰 4 帧、实时段计数 4），显式触发回调后断言：**队列清空 / 实时段计数归零 / `draining` 复位 / 移出订阅表**；释放后断言"不再收帧（仅 1 帧在途）、不重复 complete 已终态 emitter"。桩件 `TerminalSemanticsEmitter` 新增三回调登记与显式触发（`:661-673`）。
- 结论：达成。

### 8.4 S3-3：两参注册表直执用例加固（原构造对缺陷不敏感）

- **原用例缺陷**：`twoArgRegistryAlwaysDeliversInlineAndNeverReadsTheSharedPool`（`:448`）在"静态池未装配"的隔离运行时里，即使两参路径去读静态池，读到的也是 null ⇒ 回落同步直执 ⇒ **修复前代码照样绿**（对缺陷不敏感）。
- **加固**：用例内先按生产 Spring 路径 `configureSharedDeliveryPool(poolSize, poolSize×64)` 装配进程级静态池，并先断言 `sharedDeliveryExecutorOrDirect()` **确为** `ThreadPoolExecutor`（前置条件成立，断言才有意义），再构造两参注册表断言"写出在调用线程 + 投递执行器不是 `ThreadPoolExecutor`"。
- **敏感性实测（红→绿）**：把 `RunRegistry.of()` 临时还原为第一轮的旧守卫形态（每次取 `sharedDeliveryExecutorOrDirect()`）→ 该用例红（`[S2-1：两参构造的写出发生在调用线程（同步直执）]`，日志 `m2t3a-fix2-red-s33.log`）→ 还原后绿【实测】。临时改动已字节级还原（`md5` 前后一致 `79b0b8779ab0e368c161ff270e3b6e56`），未留痕。
- 结论：达成（用例对缺陷敏感）。

### 8.5 S3-5：`completeWhenDrained` 两分支定点用例

- 分支①（CAS 抢到 + 队空 ⇒ 直接 `completeOne`）：`terminalCompleteOnAnIdleDrainedSubscriberCompletesInline`（`:550`）——注入只记录不执行的 `RecordingExecutor`（`:771`），空订阅者 `complete()` 后断言 `completes()==1`、complete 线程 = 调用线程、**零** drain 任务提交（证明走的是同步收尾而非换手）。
- 分支②（CAS 抢到 + 队非空 ⇒ 自跑 `submitDrain` 排空后收尾）：`terminalCompleteWithFramesWaitingRunsItsOwnDrainBeforeClosing`（`:576`）——入队一帧后把 `draining` 复位（白盒构造"队列非空 ∧ 无 drain 在跑"这一生产上位于 `enqueue` 与 `kickDrain` 之间的窄窗，理由同 `fieldOf`），`complete()` 后断言"**多提交一个** drain（第 2 个任务）"且"队列未空不得提前收尾"，驱动该任务后断言帧写出、随后恰好一次收尾、订阅表清空。
- 结论：达成（分支各有定点用例，均可确定复现）。

### 8.6 TDD 红→绿证据（全部【实测】）

- 顺序：**先补/改用例 → 未修复代码跑红 → 实施修复 → 转绿**（非事后注释）。
- 红（未修复代码，日志 `m2t3a-fix2-red.log`）：`SseChatEmitterDeliveryTest` **18 用例 / 5 失败 / 0 错误**，失败点与断言意图一一对应：
  - `:200` 红队 S1：`[回放 backlog 排空期间的实时帧不得摘除健康订阅者（红队 S1）] expected: 0 but was: 1` —— 第一条实时心跳即摘除（`completes()==1`）；
  - `:229` 反向锚定：`expected: 0 but was: 1`（同一机制的另一面：该用例要求"256 条不摘除"）；
  - `:534`（×3，经 `:496/:501/:506`）：三回调用例的"队列必须清空"失败——实际残留 `d2..d5` 四条 delta 帧（日志逐帧打印），即内存滞留的原始形态。
- 红（S3-3，日志 `m2t3a-fix2-red-s33.log`）：旧守卫形态下 1 用例 / 1 失败（`:464`）。
- 绿（修复后，日志 `m2t3a-fix2-green1.log`）：`SseChatEmitterDeliveryTest` **18 / 0 / 0 / 0**，BUILD SUCCESS；同用例类连跑 3 轮均为 18 / 0 / 0 / 0（抗抖动）。
- 探针复跑：见 8.1（缺陷形态 `complete=true` / 修复后 `complete=false`）。

### 8.7 验证汇总（全部【实测】）

| 项 | 命令 | 结果 |
|---|---|---|
| 后端全量（修复前基线） | `mvn -B test` | Tests run: 277, Failures: 0, Errors: 0, Skipped: 0，BUILD SUCCESS；XML 口径 277 / 0 / 0 / 0 |
| 后端全量（本批修复后） | `mvn -B test` | Tests run: **284**, Failures: 0, Errors: 0, Skipped: 0，BUILD SUCCESS；XML 口径 **284 / 0 / 0 / 0**（42 个 surefire XML 求和） |
| 增量 | — | 277 ⇒ 284，**+7 全为新用例**（红队 S1 ×2、生命周期回调 ×3、`completeWhenDrained` 分支 ×2）；无删除、无改写既有断言口径（`replayBatchBypasses…` 仅收窄声明 + 抽出语料辅助方法） |
| 新用例抗抖动 | `-Dtest=SseChatEmitterDeliveryTest` ×3 轮 | 每轮 18 / 0 / 0 / 0，BUILD SUCCESS |
| 红→绿 | 见 8.6 | 红 5 失败（缺陷代码）⇒ 绿 18/0/0/0 |
| 红队探针复跑 | `java @run.args ProbeR1`（仓库外，原样编译运行） | 缺陷形态两场景 `complete=true`；工作树两场景 `complete=false` |
| 前端兜底 | `yarn typecheck`（vue-tsc --noEmit） | 通过，零错误（前端本批零改动，仅复跑） |

- 未执行项（口径同前）：真实 HTTP + 慢客户端的集成冒烟、`/api/ai/health` 投递池指标实调，仍归 T3-5 压测收尾；探针为进程内等价复现，非 HTTP 端到端。

### 8.8 本批脱敏自查（以 `git status` 全量为准）

- 全量改动：**14 项，与修复批第 1 轮清单完全一致**（11 改 + 3 增，全在 backend 下 12 个文件 + 设计卡 + 本证据文档）——本批**未新增任何文件**，`frontend` / `scripts` 零改动（typecheck 仅运行）。
- 本批实质改动文件共四处（均在既有清单内）：`backend/.../ai/run/SseChatEmitter.java`（S1 计数闸 + S3-1 清理 + CAS 后置）、`backend/.../ai/run/SseChatEmitterDeliveryTest.java`（用例）、`backend/.../ai/config/AiProperties.java`（**仅注释口径**：容量闸判据按实时段计数）、本证据文档 + 设计卡一行注记；另有一处**临时探针编辑已字节级还原**（`RunRegistry.java`：`md5` 前后一致，非持久改动）。
- 禁用字面量扫描：本批改动与新增文本不含旧主仓目录名带版本后缀的组合字样（命名纪律禁则）、不含本机盘符路径（统一相对路径或 `<REPO_ROOT>` / `<MAVEN_HOME>` 占位）、无任何凭证（本批未涉及密钥，无 key 可记）。
- 本文档自身：路径一律仓库相对或占位符；无盘符、无禁则字样组合、无凭证。
- git 写操作零发生（无 add/commit/push/stash/checkout/restore）；本批全部改动保持未提交。

### 8.9 【待裁决】与观察项

1. **【待裁决】计数器口径的备选**：`liveQueued` 以 `set(0)` 归零 + 递减兜底（不为负）。它的已知代价是"清空与递减并发时可能**少计**一条"——但该窗口只出现在订阅者已被关闭时（`closed`），对容量闸无实际影响；若指挥官要求"计数严格等于队列里的实时帧数"，需为清空/递减引入同一把每订阅者锁（代价：drain 路径加锁）。
2. **观察项**：`twoArgRegistry…` 用例现在会**确定性**装配进程级静态池（仅当此前无人装配时才装，`configureSharedDeliveryPool` 首装配者胜），池形参 = `AiProperties` 缺省（4 线程 / 队列 256），与生产 Spring 路径同形；全量回归 284 / 0 / 0 / 0 未见顺序相关影响。
3. 观察项（沿用）：回放批仍无**物理**容量上限（上界 = 归档窗口 3000），R1 裁决口径未变；T3-5 压测定值时可一并复核"实时 256 + 回放 3000"的内存峰值。
