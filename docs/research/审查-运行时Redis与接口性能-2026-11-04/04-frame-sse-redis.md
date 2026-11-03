# 帧归档、SSE 与 Redis 唤醒专项审查

## 1. 审查口径与结论摘要

- 审查基线：`52b093642402c9455b7cb9627fae6dbdd6544e54`；只读检查时工作区无已列出的改动。
- 范围：`RedisFrameStore`、`RedisFrameWakeBus`、`FrameStore`、`SseChatEmitter`、`FrameSizeLimiter`、`TurnKeepalive`、`StreamWatchdog`、`common/sse`，以及直接相关的入口、装配、身份传播、轮次回收和会话销毁代码。
- 只阅读生产代码与配置；未阅读或修改测试代码，未运行测试、构建、CI、服务、压测，也未执行任何 Git 写操作。
- 本文中的“代码证实”仅表示调用关系、条件分支、资源边界或竞态窗口可由源码直接推出；任何实际耗时、吞吐、内存曲线和超过 30 秒的发生频率都必须另行验证。
- **删除接口专项结论**：代码证实活跃轮销毁会串行执行取消、归档扫描、终态写入、状态轮询和逐 run 删除；无终帧时一次 `cancelConfirmed` 最多进行三次正向归档扫描。它是超过 30 秒的明确风险路径，但本文没有运行测量，不能把“实际必然超过 30 秒”写成事实。
- Cluster 方面，帧原子写脚本的四个键使用同一身份化 hash tag，未发现该脚本的 `CROSSSLOT` 缺陷。主要问题转为：同 run 同槽的热点、每帧同步 Lua/PUBLISH、跨实例重复读档，以及删除时新增键族漏清。

### 问题总览

| 编号 | 严重度 | 确定性 | 问题 |
|---|---|---|---|
| FR-01 | 高 | 代码证实竞态窗口 | fence 检查与帧落档分离，旧驱动可在接管后写入并交付帧 |
| FR-02 | 高 | 代码证实；时延需测量 | `emitLock` 内仍有同步 Lua、XRANGE 和故障回退，阻塞同轮所有生产者 |
| FR-03 | 高 | 代码证实放大结构 | 每帧 PUBLISH，挂载该 run 的每个实例再各自读档，形成多实例扇出放大 |
| FR-04 | 高 | 代码证实路径；30 秒阈值未实测 | 活跃轮删除在一次收尾中重复扫描归档最多三次，且各 run 串行 |
| FR-05 | 高 | 代码证实逻辑缺口 | 归档扫描以“末号减条数预算”为起点，大跳号时会漏掉仍幸存的目标帧 |
| FR-06 | 高 | 代码证实共享阻塞面 | 看门狗/保活共享调度器仍执行同步 Redis 写或等待含 Redis I/O 的锁 |
| FR-07 | 高 | 代码证实资源边界 | SSE 写超时不释放阻塞 socket 写；64 条共享写线程可波及其他健康连接和任务通道 |
| FR-08 | 中 | 代码证实 | 唤醒页全部不可解析时游标不前进，后续帧只能等对账或重挂 |
| FR-09 | 中 | 代码证实调用次数；时延需测量 | 重挂与空差量路径重复尾探/空读，故障时空原文结果还会触发更多回退读 |
| FR-10 | 中 | 代码证实 | `ai:xtrim` 未进入续期登记和删除键集；每帧 `ai:xack` 也只靠短 TTL |
| FR-11 | 中 | 代码证实 | 不可截字段主导的超限帧仍会越过单帧上限进入 Redis 和 socket |
| FR-12 | 中 | 代码证实算法结构；耗时需测量 | 大 delta 分片存在余串复制，并在同轮锁内逐片同步归档 |
| FR-13 | 中 | 代码证实的已知边界 | Stream 字节预算是进程本地观察值，跨实例/重启后不是全局硬界 |
| FR-14 | 中 | 代码及 JDK 默认语义证实；队列规模需测量 | 每次异步 SSE 写都创建 15 秒定时器，成功取消后任务可按默认策略滞留到到期 |

---

## 2. 实际阅读的生产文件与配置

### 核心实现（逐段完整阅读）

- `backend/src/main/java/com/example/configmgr/ai/run/RedisFrameStore.java`
- `backend/src/main/java/com/example/configmgr/ai/run/RedisFrameWakeBus.java`
- `backend/src/main/java/com/example/configmgr/ai/run/FrameStore.java`
- `backend/src/main/java/com/example/configmgr/ai/run/FrameWakeBus.java`
- `backend/src/main/java/com/example/configmgr/ai/run/FrameWakeListener.java`
- `backend/src/main/java/com/example/configmgr/ai/run/SseChatEmitter.java`
- `backend/src/main/java/com/example/configmgr/ai/run/FrameSizeLimiter.java`
- `backend/src/main/java/com/example/configmgr/ai/run/TurnKeepalive.java`
- `backend/src/main/java/com/example/configmgr/ai/run/StreamWatchdog.java`
- `backend/src/main/java/com/example/configmgr/common/sse/SseWriteBudget.java`
- `backend/src/main/java/com/example/configmgr/ai/run/RunRegistry.java`
- `backend/src/main/java/com/example/configmgr/ai/exec/AiExecutorConfig.java`
- `backend/src/main/java/com/example/configmgr/ai/run/CancellationRegistry.java`
- `backend/src/main/java/com/example/configmgr/ai/run/SessionKeyRegistry.java`
- `backend/src/main/java/com/example/configmgr/ai/tenant/RunKeyScope.java`
- `backend/src/main/java/com/example/configmgr/ai/tenant/TenantKeyScope.java`

生产代码中 `FrameStore` 的实现只有 `RedisFrameStore`，`FrameWakeBus` 的生产实现只有 `RedisFrameWakeBus`；未发现另一套生产内存帧日志。

### 为追踪入口、取消、删除和 fence 而定向阅读

- `backend/src/main/java/com/example/configmgr/ai/web/AiController.java`：首轮提交/回收、重挂、探测和异常出口。
- `backend/src/main/java/com/example/configmgr/ai/web/AiSessionPurgeController.java`：`DELETE /api/ai/session/{sessionId}`。
- `backend/src/main/java/com/example/configmgr/ai/run/ResilientChatService.java`：首轮/续跑、保活、看门狗、取消终局和终帧幂等。
- `backend/src/main/java/com/example/configmgr/ai/run/ResumeService.java`：续跑线程交接和 `cancelConfirmed`。
- `backend/src/main/java/com/example/configmgr/ai/run/RunStore.java`：run 键、fence 写门、续期、13 键删除。
- `backend/src/main/java/com/example/configmgr/ai/run/LeaseService.java`：租约/fence 原子协议和进程内权威视图。
- `backend/src/main/java/com/example/configmgr/ai/session/AiSessionPurgeService.java`：取消、50ms 状态轮询、逐 run 删除及观测键清理。
- `backend/src/main/java/com/example/configmgr/ai/config/AiProperties.java`：SSE、Stream、TTL 和预算配置。
- `backend/src/main/java/com/example/configmgr/task/service/TaskSseService.java`：共享 SSE 写池的第二条使用通道（定向阅读）。

### 配置

- `backend/src/main/resources/application.yml`
- `backend/src/main/resources/application-cluster.yml`

关键运行参数：Redis 命令/连接超时均 2s；Cluster 最多 3 次重定向；会话 TTL 24h；Stream 条数窗口 6000、字节预算 64MiB；每订阅者实时队列 256；AI 投递池 8 线程；SSE 写超时 15s；共享写池最大 64 线程；看门狗和取消轮询调度器各 2 线程；非驱动方 attach 保活 5s、归档对账 30s。

---

## 3. 完整调用链

### 3.1 首轮与续跑：入口 → 服务 → Redis/线程 → 返回

1. `POST /api/ai/chat` 在 `AiController` 完成会话门、runId 和请求绑定，创建无整条流传输死线的 `SseEmitter`，经 `RunRegistry.of` 取得按“身份 + runId”隔离的写出器并挂接订阅者（`AiController.java:462-546`）。
2. 控制器把 `ResilientChatService.chat` 提交到有界 `aiRunExecutor`；任务执行结束后释放会话门并调用 `RunRegistry.close`（`AiController.java:560-600`）。续跑由 `ResumeService` 把 `driveResumed` 再提交到同一运行池（`ResumeService.java:1753-1771`）。
3. 驱动入口调用 `SseChatEmitter.beginDrive`，先读一次归档末号作为本次驱动下界（`ResilientChatService.java:681-686`，`SseChatEmitter.java:1670-1695`）。随后启动 `TurnKeepalive`；每次上游尝试建立 `StreamWatchdog`，取消读由独立 `aiCancelPoll` 执行（`ResilientChatService.java:1534-1535`）。
4. 每个业务帧进入 `SseChatEmitter.emit`：解析/缓存记录身份，建立 `RunIdentityScope`，检查本地租约权威态；持租时再通过一次 Redis `GET fence` 做 `permittedToWrite`（`SseChatEmitter.java:3280-3305,3365-3402`；`RunStore.java:2993-3000,3040-3062,3850-3853`）。
5. 在 `emitLock` 内，`FrameSizeLimiter` 先按 18 位序号占位序列化/截断；`RedisFrameStore.appendAndAllocateSeq` 用同一 Lua 完成序号 `INCR`、必要的尾部重锚、`XADD <seq>-0`、近似裁剪、ACK、TTL 和 xtrim 低水位（`SseChatEmitter.java:3417-3496`；`RedisFrameStore.java:810-882`）。失败则不交付、不推进游标；终帧失败另发连接级 typed error。
6. 归档成功后，帧原文进入每个本地订阅者队列并推进各自 `wakeFloor`；若游标落后，会先在锁内读归档补齐。锁外再执行活动戳写和 `PUBLISH ai:wake:<tenant>:<user>:<runId> <seq>`（`SseChatEmitter.java:3502-3531,3752-3811,3929-3977`）。
7. 投递池执行 drain；生产路径每次只提交一帧到 `SseWriteBudget.writeAsync`，实际 `SseEmitter.send` 在共享写池执行，完成/超时回调再决定续 drain 或摘除（`SseChatEmitter.java:4448-4564,4573-4643`）。控制器/恢复入口最终返回 SSE emitter；帧写出不占用最初 HTTP 请求线程。

### 3.2 跨实例唤醒与读回

1. 首个本地订阅者出现时，`SseChatEmitter.ensureWakeSubscribed` 以记录身份调用 `FrameWakeListener.register`；最后一个离开时按精确句柄退订（`SseChatEmitter.java:1311-1408`）。
2. `FrameWakeListener` 按“身份 + runId”分桶，同一个桶可含多个 emitter 目标；Redis I/O 在监听器全局锁外执行（`FrameWakeListener.java:190-254,291-345`）。
3. `RedisFrameWakeBus` 懒建 `RedisMessageListenerContainer`，派发池为 2/8 线程 + 1024 队列，订阅池为 1/2 线程 + 64 队列；两者饱和均改为调用线程内联（`RedisFrameWakeBus.java:248-333`）。
4. 收到上界 seq 后，回调恢复订阅时捕获的身份，`SseChatEmitter.onWake` 取所有非回放订阅者的最小游标，在 `emitLock` 内读 `XRANGE` 并按每订阅者游标入队去重；超过一批时在本地投递池续读，不再广播（`SseChatEmitter.java:3996-4099,4124-4235`）。
5. 非驱动方每 5s 发本地心跳，并每 30s 在投递池单飞执行一次归档对账，兜底丢失的 Pub/Sub（`SseChatEmitter.java:1093-1228`）。

### 3.3 重连、分页回放与终态

1. `GET /api/ai/events/{runId}?lastSeq=N` 先读快照、续期活跃资源并做归档末号预检；不可读返回 503（`AiController.java:1104-1138`）。
2. `replayAndAttachWithStatus` 先建立唤醒订阅，再在锁外读取 `replayMeta`（尾号 + xtrim）和首屏 256 条原文；回锁后挂本地订阅、按 64 帧/512KiB 分批入队（`SseChatEmitter.java:1482-1576,1879-1981,2074-2103`）。
3. 随后做尾部复查并调用一次 `onWake(Long.MAX_VALUE)` 收口读取期间的竞态（`SseChatEmitter.java:1555-1574,1990-2015`）。剩余页由 drain 在队空时续读；回放中的实时业务帧不直接插队（`SseChatEmitter.java:2172-2292,3779-3784`）。
4. 回放读到 done/error，或 `lastSeq` 正好是终帧，则排空后完成；降级回放补 typed error。快照终态但归档无终帧时，非驱动实例每 3s 复查，忙时最多重排三次，再给当前连接补合成 error（`SseChatEmitter.java:1589-1611,1838-1866,3591-3744`）。
5. 终态 `complete` 先停止接收新实时帧，再逐订阅者排空；最后回收注册表条目。`RunRegistry.close` 另调 `FrameStore.forget` 清进程内簿记，不删除 Redis 归档（`SseChatEmitter.java:4904-4933`；`RunRegistry.java:363-390`）。

### 3.4 删除对话接口与帧链路的交汇

`DELETE /api/ai/session/{sessionId}` → `AiSessionPurgeService.purge` → durable pending/guard → H2/反馈删除 → 对每个活跃 run 写取消事实并调用 `ResumeService.cancelConfirmed` → 最多 1.5s 的 50ms 快照轮询 → 重新读状态 → 逐 run `RunStore.deleteRun` → 同槽 13 键 DEL → 逐把删除 beat/activity/cancel/observed → 删除其余会话资源并返回 `PurgeResult`（`AiSessionPurgeController.java:71-103`；`AiSessionPurgeService.java:341-417,462-540,911-980,1191-1268`）。

`cancelConfirmed` 内部还会读快照、校验取消、扫描归档终帧、抢会话门和 run 租约、执行 `cancelParked`、再次扫描归档终帧（`ResumeService.java:2323-2428`）。`cancelParked` 进入 `cancelTerminalLocked` 后又先扫描一次归档，然后写取消快照、补各未决条目帧、写终帧（`ResilientChatService.java:3330-3341,3389-3521`）。这条链同时消耗 HTTP 请求线程、取消轮询结果、run 驱动/租约状态、Stream 读带宽和出帧锁。

---

## 4. 逐项问题、根因与修复

### FR-01：fence 检查与帧落档不是同一原子操作

- **严重度 / 确定性**：高；代码证实竞态窗口，未做并发复现。
- **证据**：业务帧先在 `SseChatEmitter.java:3374-3402` 调 `runLeaseAuthority`/`permittedToWrite`；后者在 `RunStore.java:3040-3062` 读取 fence 并返回布尔值。之后才在 `SseChatEmitter.java:3418-3455` 持锁调用帧 Lua。`RedisFrameStore.java:265-394` 的 `APPEND_BODY` 四键仅为 seq/xevents/xack/xtrim，没有 fence 或期望 epoch。
- **触发条件**：旧实例通过 fence 读取后、执行帧 Lua 前，另一实例完成接管并推进 fence；旧实例随后仍写入并本地交付，甚至可写终帧。`emitLock` 只是进程内锁，不能排除该窗口。
- **影响**：单实例无跨进程接管时难触发；多服务实例在单 Redis 或 Cluster 都会发生。Cluster 同槽已经具备修复前提。多线程下不同实例的旧/新帧可交错，破坏“丢租后只读”的 fencing 语义。
- **根因**：权限“读—判—写”跨两次 Redis 往返，原子帧脚本只保证 seq 与归档，不保证写权限。
- **最佳修复**：把 `fenceKey` 和本进程 epoch 传入 `APPEND_BODY`，在任何 `INCR` 前检查 `fence <= expectedEpoch`；不满足返回明确的 `STALE_FENCE`，调用方不归档、不扇出、不唤醒。无租约的 legacy 写口需显式分支，不能隐式绕过生产租约。这样还可省掉每帧独立的 fence GET。
- **备选与取舍**：在 Lua 同时校验租约键值和 epoch 更强，但键/参数更多；仅用分布式锁包住出帧不能解决锁到期后的旧主人写入，不建议作为替代。
- **修改点/影响面**：`RedisFrameStore` 脚本与结果、`FrameStore` 写口参数或上下文、`SseChatEmitter` 写门、`RunStore`/`LeaseService` epoch 提供口；需覆盖单实例 legacy、多实例接管和 Cluster 同槽。
- **后续验证（不执行）**：在旧主人完成 fence 读与执行 Lua 之间注入暂停，推进新 fence 后恢复旧主人；断言旧帧无 seq、无 Stream 记录、无本地交付、无 PUBLISH。

### FR-02：`emitLock` 内仍存在可累积的同步 Redis 阻塞

- **严重度 / 确定性**：高；锁内调用由代码证实，实际阻塞时长取决于 Redis/网络。
- **证据**：`SseChatEmitter.java:3418-3455` 在锁内执行帧 Lua；`RedisFrameStore.java:845-871` 对未知回包重试一次，对不可证序号最多三次尝试。交付前补齐在 `SseChatEmitter.java:3752-3762,3828-3870` 锁内读原文，空结果还回退类型化读；唤醒在 `SseChatEmitter.java:4026-4094` 持锁调用 `loadWakeFrames`，其 `4128-4180` 同样可连续读两次。
- **触发条件**：同轮运行线程、HTTP 决策/回灌线程、心跳、跨实例回调并发；任一 Redis 调用接近 2s 命令超时，其它生产者都排队。原始读失败后的类型化回退还会增加尾号、xtrim 和 range 读（`RedisFrameStore.java:1290-1342`）。
- **影响**：单实例也会卡住同轮所有线程；多实例把远端唤醒读加入同一锁；Cluster 重定向/槽迁移可能进一步增加单命令时延。它还会反向阻塞共享保活调度器，见 FR-06。
- **根因**：为保持“归档—补齐—入队”同序，把网络 I/O 与顺序临界区绑定；异常回退缺乏统一状态结果，导致锁内命令数继续放大。
- **最佳修复**：按 run 分片到有序单写者执行器，把归档、补齐和入队编成异步有序链；Redis 回调完成后再推进该 run 的下一动作。不要简单把 Lua 移出锁，否则会重开交付号/归档号乱序。原始读应返回带 `degraded` 的结果，失败时结束本拍并调度下拍，不在锁内做第二条读链。
- **备选与取舍**：短期保留锁，但限制每临界区一次 Redis 命令、移出所有可延后读并缩短出帧专用超时；实现较小，却仍不能保证慢 Redis 不堵同轮。
- **修改点/影响面**：`SseChatEmitter` 的 emit/onWake/catchUp，`FrameStore` 原文读结果，异步有序执行器及身份恢复；终帧、回放交接、背压都受影响。
- **后续验证**：分别对 Lua、XRANGE、尾探注入慢响应，叠加运行线程、HTTP 决策、心跳和跨实例 wake；记录锁等待、每拍命令数、帧序及终帧时限。

### FR-03：每帧 Pub/Sub + 每实例读档的放大

- **严重度 / 确定性**：高；放大结构代码证实，吞吐拐点需测量。
- **证据**：每个成功业务帧最终都调用 `requestWakePublish`（`SseChatEmitter.java:3531`），`RedisFrameWakeBus.publish` 是同步 `convertAndSend`（`RedisFrameWakeBus.java:132-143`）。每个挂载该 run 的实例收到后都会从 Stream 读差量（`SseChatEmitter.java:3996-4044`）。当前只有嵌套锁内上界合并，没有时间/批次维度的发布合流（`SseChatEmitter.java:3929-3948`）。
- **触发条件**：高频 delta；同一 run 在 N 个实例有订阅者。每帧至少一次 PUBLISH，远端实例再各自 XRANGE；本地已完成扇出的实例也收到自唤醒并做游标判断。发布虽然移出 `emitLock`，仍占用驱动/回调线程。
- **影响**：单实例多一次自发布开销；多实例读流量和 JSON 解析近似随实例数增长；Cluster 下同一 run 的 Stream 落在同一槽，热点不会因增加实例而分散。
- **根因**：Pub/Sub 只传上界，每个通知都要求接收方补读；出帧粒度与通知粒度一一绑定。
- **最佳修复**：每 run 单飞异步唤醒发布器，以最大 seq 覆盖中间值，短窗口/批次合流；终帧可立即冲刷。接收侧同样合流在飞 wake，保持 30s 对账和 lastSeq 重挂为正确性兜底。
- **备选与取舍**：Pub/Sub 直接携带帧原文可省 XRANGE，但会扩大 Pub/Sub 流量且仍需归档对账；改 Stream consumer group 是更大协议改造。只增加派发线程不能减少 Redis 总命令量。
- **修改点/影响面**：`SseChatEmitter` 发布上界、`RedisFrameWakeBus` 异步发布与停机冲刷、实例身份和背压指标。
- **后续验证**：固定帧率与帧大小，分别测 1/2/3 个挂载实例的 PUBLISH、XRANGE、JSON 解析、槽级热点和端到端延迟；另验证终帧 wake 不被合流延迟吞掉。

### FR-04：删除活跃轮时重复全窗口正向扫描

- **严重度 / 确定性**：高；调用链代码证实，是否超过 30 秒必须以运行数据确认。
- **证据**：`ResumeService.cancelConfirmed` 在 `ResumeService.java:2360-2363` 先调用一次终帧扫描，在 `2395-2397` 收尾后再扫一次。中间的 `cancelParked` 进入 `ResilientChatService.cancelTerminalLocked`，又在 `ResilientChatService.java:3406-3419` 扫描一次。`SseChatEmitter.scanArchive` 每页 256 帧、预算 6256 帧（`SseChatEmitter.java:2323-2408`），即单轮单次扫描最多 25 次页读，并逐帧 JSON 解析；终点在末尾时正向扫描仍需读到末尾。
- **触发条件**：活跃 run 尚无终帧，会话销毁触发 `cancelConfirmed`。无终帧的第一次和第二次前置判定都需证明“没有”；写入 done 后第三次仍从旧窗口起点正向找到表尾。多个 run 在 `AiSessionPurgeService.java:481-483` 串行处理。
- **影响**：单 Redis 与 Cluster 都有 RTT、带宽和 JSON CPU 放大；多实例不共享扫描结果，取消接管/重试可重复付出。默认 64MiB 窗口下，三次扫描的读取量可达到约三倍幸存帧正文规模，实际还受 MAXLEN 近似和页内容影响。删除前的 1.5s 等待只是另一部分，并非唯一来源。
- **30 秒判断**：健康低延迟小帧环境未必超过 30 秒；大窗口、慢但成功的命令、多个 run 或故障回退下存在明确超限风险。本文未实测，不给虚假确定性。
- **根因**：终帧事实没有 O(1) 元数据；同一高层操作的三层实现分别做幂等扫描，且扫描方向对“刚写到表尾的终帧”不友好。
- **最佳修复**：在原子帧 Lua 中，对 done/error 同步维护同槽 `ai:xterminal`，至少记录最新终帧 seq/type，随会话续期并随 run 删除。整轮取消直接读它；按驱动判定时比较 `terminalSeq > driveFloorSeq`。老流无标记时才走兼容扫描，且只有完整证明后回填。
- **备选与取舍**：先合并三次调用并让 `cancelParked` 接收已判定结果，可把常态降为一次；增加 XREVRANGE 反向分页可让“终帧在表尾”快速命中，但证明“不存在”仍需整窗，不能替代元数据方案。
- **修改点/影响面**：`RedisFrameStore`、`FrameStore` 查询口、`SseChatEmitter` 终帧判定、`ResilientChatService`、`ResumeService`、键续期与删除清单。
- **后续验证**：构造 0/1/256/6000 帧及终帧在首/中/尾的归档，测量 DELETE 的页读数、字节数、JSON 次数、总时延；再做多 run、Cluster 迁移和慢命令场景，分别验证 complete/deferred 结果。

### FR-05：扫描起点把条数预算误当成 seq 区间

- **严重度 / 确定性**：高；代码证实逻辑缺口。
- **证据**：`SseChatEmitter.terminalScanStart` 在 `SseChatEmitter.java:2416-2421` 使用 `lastSeq - ARCHIVE_SCAN_BUDGET`。但 `FrameStore.java:32-34` 明确允许跳号，`RedisFrameStore.java:78-80` 还允许失败重试和遗留墙钟量级造成大间隔。`scanArchive` 只从这个数值下界往后读（`SseChatEmitter.java:2323-2408`）。
- **触发条件**：幸存 Stream 不足 6256 条，但 `lastSeq` 与目标帧 seq 的差大于 6256；目标可为旧终帧或 `ensureToolStart` 查找的 tool_start（`SseChatEmitter.java:2801-2820`）。大跳号不是异常前提，而是接口明文允许的常态。
- **影响**：可能把“未扫到”误报为“不存在”，造成重复终帧或重复 tool_start；重挂/多实例接管后更容易遇到历史号源变化。单实例连续小号不触发；Cluster 不改变逻辑错误。
- **根因**：把物理条数窗口转换为序号差值，混淆“条数”和“ID 区间”。
- **最佳修复**：提供按 Stream entry ID 反向分页的原文读口，从真实末尾逐页向前；RawFrame 同时携带 entry ID 供下一页排他续读。这样扫描范围由物理条目数约束，与 seq 跳变无关。
- **备选与取舍**：从 0 开始扫描全部幸存记录较简单，但正放大 FR-04；仅扩大减法常数仍不具正确性，不建议。
- **修改点/影响面**：`FrameStore`/`RedisFrameStore` 反向读口、`SseChatEmitter.scanArchive`、终帧和工具补发调用方。
- **后续验证**：构造只有少量条目但 seq 为 `1, 2, 10^12` 等形态的 Stream，目标分别位于最早/中间/最新条目；断言 FOUND/NOT_FOUND/UNKNOWN 不被号差误导。

### FR-06：看门狗和保活调度器仍有共享阻塞面

- **严重度 / 确定性**：高；共享线程与 I/O/锁调用代码证实，实际延迟需测量。
- **证据**：
  - `TurnKeepalive` 使用生产 `aiStreamWatchdog`（`TurnKeepalive.java:85-94`；`ResilientChatService.java:1074-1077`）。每拍 `out.stage` 进入心跳出帧，锁外仍同步调用 `store.touchActivity`（`SseChatEmitter.java:3517-3523`），其实现为带 TTL 的 Redis SET（`RunStore.java:4408-4411`）。5s 心跳间隔大于 1s 节流，因此通常每拍都会写。
  - `aiStreamWatchdog` 只有 2 条线程，同时承载每轮看门狗和保活（`AiExecutorConfig.java:116-140`）。虽然取消 GET 已分离到 `aiCancelPoll`，这条 SET 与 `emitLock` 等待没有分离。
  - 非驱动方 attach 的单线程调度器还执行离线终帧宽限任务；任务内调用 `snapshotStillTerminal` 同步读 Redis（`SseChatEmitter.java:3620-3657,3673-3688`），会推迟其它 attach 的 5s 心跳。
- **触发条件**：多个活跃/attach run；Redis 写或快照读接近 2s；某 run 的 `emitLock` 被 FR-02 占用。
- **影响**：单实例内其他 run 的看门狗/心跳被连带延迟；多实例每个进程各自发生，不能互相代偿本实例 SSE。Cluster 故障或槽热点会扩大影响。
- **根因**：取消轮询做了 I/O 隔离，但保活活动戳、快照复核和锁等待仍留在时间调度线程上。
- **最佳修复**：调度线程只计算状态和提交任务；活动戳写入专用有界、按 run 合流的执行器，终帧宽限的快照读及后续收尾转到既有投递池或独立恢复池。保留看门狗 tick 只读内存快照。
- **备选与取舍**：增加调度线程只能提高阻塞容量，不消除任务互相拖延；取消 ai:beat 可减少 I/O，但会损失进程存活证据，不应静默删除语义。
- **修改点/影响面**：`TurnKeepalive`/`SseChatEmitter` 活动戳交接、离线宽限、`AiExecutorConfig` 新执行器和停机顺序、健康指标。
- **后续验证**：对活动 SET、快照 GET 和 emitLock 分别注入停顿，观察其它 run 的 1Hz tick、5s 心跳和终帧宽限；验证池拒绝后仍有明确降级而非静默停表。

### FR-07：SSE 写超时只摘除订阅者，不能释放已阻塞的 socket 写

- **严重度 / 确定性**：高；代码及注释明确承认该资源边界，未做压力复现。
- **证据**：`SseWriteBudget.writeAsync` 超时只 `future.cancel(true)` 并回调（`SseWriteBudget.java:453-463`）；类注说明线程中断对阻塞 socket 写无效（`SseWriteBudget.java:62-79`）。共享写池为 64 线程 + `SynchronousQueue`，AI 与任务通道共用（`SseWriteBudget.java:138-178,241-264`；`TaskSseService.java:197-198`）。AI 超时回调只摘除并清队列，实际 complete 仍交给写线程返回后执行（`SseChatEmitter.java:4776-4805`）。
- **触发条件**：足够多的客户端不读也不断开；每次卡在 `SseEmitter.send` 的写仍占线程，直到客户端/容器写超时等外部条件释放。池满后，新到的健康连接被 `SATURATED` 摘除。
- **影响**：单实例 AI 和任务 SSE 可互相误伤；多实例只把硬上限变成每实例 64，不消除单点停投；与 Redis Cluster 无直接关系。超时定时器线程还会执行摘除/退订链，若该链做容器 I/O，会推迟其它写超时处理。
- **根因**：以线程池模拟阻塞写超时，无法强制中止底层 socket 写；跨通道共享一个无配额资源池。
- **最佳修复**：把 SSE 传输改为容器支持的非阻塞/async I/O 写出与背压，不在阻塞 socket 上靠线程中断兜底；按连接建立真实关闭/取消机制。
- **备选与取舍**：短期按 AI/任务分池并设置各自上限，配合连接级全局限额和更短容器写超时；可减少跨通道误伤，但不能让已经卡住的写立即释放。仅扩大 64 会增加内存和停顿风险。
- **修改点/影响面**：`SseWriteBudget`、两个 SSE 通道、容器连接配置、连接限额和前端重挂策略。
- **后续验证**：用停读客户端逐步达到写池上限，观察阻塞写实际释放时刻、健康连接触发 SATURATED 的比例、跨通道影响和重挂恢复；另检查超时定时器是否被退订阻塞。

### FR-08：不可解析的整页唤醒数据会使续读游标停滞

- **严重度 / 确定性**：中；代码证实，需损坏/不兼容归档触发。
- **证据**：`loadWakeFrames` 对每条原文调用 `parseRawFrame`，失败只跳过（`SseChatEmitter.java:4139-4153,4183-4191`）。`onWakeScoped` 的 `processedUpTo` 只随已解析 `WakeFrame` 前进（`SseChatEmitter.java:4044-4083`）。只有 `maybeMore && processedUpTo > readFloor` 才续读（`4084-4090`）。整页都不可解析时，即使 `readCount` 已满或唤醒上界更大，也不会续读。
- **触发条件**：一页 256 条原文均 JSON 损坏/不兼容，或当前批次里没有一条能转换为 WakeFrame；之后每次 wake 从相同游标读同页。
- **影响**：该 emitter 的实时补帧停在此页，后续有效帧不能及时投递；30s 对账复用同类读链，客户端重挂的分页路径按原始 seq 推进，才可能绕开。单/多实例和 Cluster 均相同。
- **根因**：传输游标与“成功解析并交付的帧”绑定，没有记录本页最后物理条目。
- **最佳修复**：WakePage 另带 `lastReadEntryId/lastReadSeq`，续读按物理读取位置推进；对坏帧计数并明确标记本拍降级。不能仅靠 `processedUpTo` 表示读进度。
- **备选与取舍**：整页失败时摘除连接触发重挂，改动小且利用回放游标推进，但会把归档损坏转化为断连；适合作为短期保守方案。
- **修改点/影响面**：`SseChatEmitter` 的 WakePage/onWake/对账，必要时 `FrameStore.RawFrame` 增加 entry ID。
- **后续验证**：构造全坏页、首坏/尾坏和跨页坏帧，断言后续好帧能被投或有界地明确降级，且不会无限重复读同页。

### FR-09：重挂和空差量读存在重复 Redis 探测与回退放大

- **严重度 / 确定性**：中；调用序列代码证实，时延需测量。
- **证据**：重挂入口先 `lastSeqIfKnown`（`AiController.java:1125-1132`）；`readReplayStart` 再读 `replayMeta`、原文页，原文为空时再读末号并可能走类型化读（`SseChatEmitter.java:1879-1937`）；带 lastSeq 还会做终帧单条探测（`1493-1496`）；挂接后又做尾部复查并无条件 `onWake(Long.MAX_VALUE)`（`1564-1571`）。原文读的异常被折成空列表（`RedisFrameStore.java:1352-1379`），调用方因此无法区分“确实为空”和“读失败”，随后继续付出回退命令。
- **触发条件**：带 lastSeq 重挂、空差量、归档尾部无变化，尤其 Redis 读抖动时。一次正常重挂除续期/订阅外，可依次发生多次尾探、xtrim 和空 range 读。
- **影响**：HTTP 重挂线程同步承担这些 RTT；多实例重连风暴会同时放大 Cluster 同一 run 槽负载。单实例也付出固定命令数。
- **根因**：元数据、原文、终帧和交接证明分别读取；`RawFrame` 读口缺少状态，使空结果触发额外探测/回退。
- **最佳修复**：提供同槽的一次性回放读脚本/复合结果，返回 tail、xtrim、首页原文及读状态；用首次权威 tail 和已建立的 wake 订阅做交接证明，避免固定重复尾探。原文读空且状态正常时不再类型化回退生产实现。
- **备选与取舍**：先给 `readRawFrom` 增加带状态结果并删除“生产原文空即回退”的命令放大，改动较小；跨调用合并仍可后续做。
- **修改点/影响面**：`FrameStore` 读结果、`RedisFrameStore`、`SseChatEmitter` 重挂/唤醒、`AiController` 预检和 archive 响应头。
- **后续验证**：分别对 lastSeq 在尾部、落后一页、终帧、读超时做调用计数，确认正确性不依赖重复读，并测重连风暴下同槽 RTT 数。

### FR-10：新增 xtrim/xack 没有完整纳入删除生命周期

- **严重度 / 确定性**：中；代码证实。
- **证据**：`RedisFrameStore.java:592-603,914-917` 创建同槽的 stream/seq/trim/ack 键；但 `RunStore.deleteRunKeys` 的 13 键 DEL 列表（`RunStore.java:2906-2908`）没有 xtrim。`SessionKeyRegistry.java:142-180` 的续期登记也没有 xtrim。`AiSessionPurgeService.java:1236-1268` 的额外删除只覆盖 beat/activity/cancel/observed。
- **触发条件**：流发生裁剪后删除会话，xtrim 留在原 TTL 内；每个业务帧产生的 ACK 键在删除后最多留存 120s（`RedisFrameStore.java:232-235`）。同 runId 在残留期内被人为复用时，旧水位还可能影响新轮判断。
- **影响**：Cluster 下这些键同槽，不会 CROSSSLOT，但销毁不是立即完整收敛；高频出帧时 ACK 键数随“帧率 × 120s”累积。xtrim 只有序号元数据，不包含帧正文，不应夸大成正文泄漏。
- **根因**：键族后来增加，但删除/续期表仍维持旧的 13/14 键口径；ACK 采用每帧一个动态键，无法在不扫描的前提下逐枚删除。
- **最佳修复**：把 xtrim 加入同槽原子 DEL 和续期登记；若采用 FR-04 的 xterminal 也同步纳入。ACK 改为每 run 固定、限量且可裁剪的幂等 HASH，或明确把 120s 短键列为销毁的临时例外并监测其数量。
- **备选与取舍**：保留动态 ACK 可减少协议改动，但删除接口不能立即枚举清净；使用 KEYS/SCAN 按 token 删除会引入全槽/全集群扫描，不建议。
- **修改点/影响面**：`RunStore`、`SessionKeyRegistry`、`RedisFrameStore`、销毁结果复核及键数指标。
- **后续验证**：写入并触发裁剪后删除会话，断言 xtrim 不存在；高频出帧后删除，测 ACK 残留量和到期收敛；同 runId 复用场景不得继承旧水位。

### FR-11：不可截字段可让实际帧超过配置上限

- **严重度 / 确定性**：中；代码证实。
- **证据**：`FrameSizeLimiter.apply` 最多迭代收缩，仍超限时只挂标记并返回 `serialize(frame)`（`FrameSizeLimiter.java:120-140`）。只有 `text`/`result` 被截（`55-56`）；resultView、args、preflight、context 等字段不在该集合。该返回字节会继续进入帧 Lua、订阅者队列和 socket。
- **触发条件**：非 text/result 字段本身很大，或截断标记/结构化信封的固定开销使预算无法达到上限。
- **影响**：64KiB 不是全局硬上限；大帧增加 Lua 参数、Redis Stream、每订阅者 String 队列和阻塞写成本。单实例/Cluster 相同，多订阅者按份放大。
- **根因**：超限收尾选择“标记但仍返回”，而下游和容量论证把 max-frame-bytes 当成上界。
- **最佳修复**：将不可安全收缩的帧明确失败并走 typed error；各结构化字段在生产点设置独立规模预算，最终 `apply` 只做最后守卫。若允许超限，必须把配置改名为软阈值并同步所有容量假设，但不符合当前契约。
- **备选与取舍**：对更多字段做通用投影/摘要可减少失败，但需要逐字段语义设计，不能字节砍 JSON。
- **修改点/影响面**：`FrameSizeLimiter`、大字段生产点、`SseChatEmitter` 失败出口、Redis 容量与 SSE 写预算说明。
- **后续验证**：构造超大 args/resultView/preflight/context 和极小合法下限边界，断言任何交付/归档帧不超上限，失败均有明确终局。

### FR-12：大 delta 分片的复制与锁内逐片归档放大

- **严重度 / 确定性**：中；算法和锁范围代码证实，实际 CPU 需测量。
- **证据**：`FrameSizeLimiter.splitStringValue` 每轮执行 `value.substring(offset)` 生成余串（`FrameSizeLimiter.java:223-227`），后续 `takeChars` 又多次序列化前缀（`419-437`）。`SseChatEmitter.emitDeltaText` 在整个分片循环外持有 `emitLock`，每个片段再进入完整 Lua 归档（`SseChatEmitter.java:2592-2617`）。
- **触发条件**：上游给出远大于 64KiB 的单个 delta；片数增加时余串复制总量近似呈平方增长，且每片还有 Redis RTT。
- **影响**：同轮其它出帧和唤醒等待 CPU 分片与逐片 I/O；多实例远端又承担逐片 wake/读档。Cluster 下所有片仍落同一槽。
- **根因**：拆分器按余串而不是原串 offset 工作，且为保证片间不插帧，把 CPU 拆分也放进出帧锁。
- **最佳修复**：拆分器直接在原字符串上以 offset/length 二分，避免构造余串；在锁外先计算全部片段，再持锁按序归档/入队，保持片间原子顺序。
- **备选与取舍**：限制上游单事件大小并拆成更上游的流式片段最简单，但需供应商协议支持；批量 Lua 一次写多片能减少 RTT，却增大单脚本与失败恢复复杂度。
- **修改点/影响面**：`FrameSizeLimiter` 分片算法、`SseChatEmitter.emitDeltaText` 锁边界、序号与失败回滚语义。
- **后续验证**：以不同字符类型和大小测分片耗时、分配量、片数和锁等待；验证中途 Redis 失败时已归档前缀与后续失败面的行为明确。

### FR-13：Stream 字节预算只按各实例本地观察值收窄

- **严重度 / 确定性**：中；代码和注释均明确登记，属于已知但真实的多实例容量边界。
- **证据**：`observedPayloadBytes` 存在 `RedisFrameStore.RunState` 进程内（`RedisFrameStore.java:513-524`）；窗口由本实例观察值计算（`1650-1667`）。`AiProperties.java:1264-1267` 明确承认跨实例不共享，预算退化为各实例观察之和。`MAXLEN ~` 仍是近似裁剪，MIN_WINDOW 还允许在预算过小时保留 256 条。
- **触发条件**：同一 run 在不同实例交替/并发出帧，一实例见过大帧而另一实例没见过；或进程重启后本地观察归零。
- **影响**：64MiB 不是严格的单 run 全局硬界；Cluster 同槽节点的实际内存可超过配置预期。单实例正常路径较接近预算，多实例接管和故障期偏差更大。
- **根因**：为避免每帧共享最大值的额外 Redis I/O，容量输入只保存在本地；预算本身是估算而非 Redis 实占字节。
- **最佳修复**：在现有原子帧 Lua 内维护同槽 `maxFrameBytes`/窗口状态，所有写实例从同一值计算窗口；用固定开销和帧实际长度更新，不增加 Java 往返。读取侧不要依赖本地观察值判断物理窗口。
- **备选与取舍**：保持软上限并设置更大的全局安全余量、按槽监控实际内存，改动最小；不能把配置继续称为可证全局上界。
- **修改点/影响面**：`RedisFrameStore` 脚本、RunState、新键续期/删除、健康指标和容量文档。
- **后续验证**：两个实例分别写小帧/大帧并交叉接管，重启清空本地状态后继续写；测量同槽 Stream 实际内存、回放窗口和预算偏差。

### FR-14：每次异步写出创建一个 15s 定时器，取消任务可滞留

- **严重度 / 确定性**：中；创建/取消路径与 JDK 默认 remove-on-cancel=false 语义可确定，实际队列长度未测。
- **证据**：`SseWriteBudget.writeAsync` 对每次写都调用单线程调度器 `schedule(..., timeoutMillis)`（`SseWriteBudget.java:403-463`）；写成功只执行 `timer.cancel(false)`（`435-438,469-472`）。调度器由 `Executors.newSingleThreadScheduledExecutor` 创建，未开启 `removeOnCancelPolicy`（`189-205`）。默认 15s 来自 `123`。
- **触发条件**：高帧率且大多数写成功；取消的 ScheduledFuture 在默认 JDK 调度队列中可保留到原到期时刻，形成约“写速率 × 15s”的待清理对象量级。
- **影响**：增加每帧 Future/定时器/原子对象分配和调度队列扫描/内存压力；AI 与任务通道共用。多实例按实例累积，Cluster 无关。
- **根因**：以每写一次一个延迟任务实现预算，成功路径只 cancel、不确保从队列移除。
- **最佳修复**：直接使用 `ScheduledThreadPoolExecutor` 并启用 `setRemoveOnCancelPolicy(true)`；补充定时器队列深度指标。
- **备选与取舍**：共享时间轮/按连接合并 deadline 能进一步减少对象数，但改造更大；只缩短 15s 会改变慢客户端语义，不是纯粹的资源修复。
- **修改点/影响面**：`SseWriteBudget` 调度器创建、停机、健康指标；两通道共同验证。
- **后续验证**：固定高写速率运行超过多个 15s 周期，比较修复前后定时器队列深度、分配速率和 Old GC；验证真正超时仍恰好回调一次。

---

## 5. 全部相关轮询、空闲等待与线程资源清单

| 机制 | 周期/等待 | 线程与 I/O | 审查结论 |
|---|---|---|---|
| StreamWatchdog 判定 tick | 1s | `aiStreamWatchdog` 2 线程；生产 tick 只读内存取消快照 | 设计正确，但与 TurnKeepalive 共池，受 FR-06 连带 |
| 跨实例取消轮询 | 每尝试 1s | `aiCancelPoll` 2 线程，每拍同步 GET；读失败进入全局 5s 退避 | 隔离方向正确；正常成本为活跃尝试数×1 GET/s，需随容量监控 |
| TurnKeepalive | 每 run 5s | 与看门狗共池；心跳不写归档/PUBLISH，但活动戳仍 SET | FR-06 |
| 非驱动 attach 保活 | 每 emitter 5s | 进程级单线程；心跳无 Redis，终帧宽限有快照 GET | FR-06 |
| attach 归档对账 | 每 emitter 30s | 单飞后提交有界投递池，读在锁外、入队在锁内 | 是丢失 wake 的有效兜底，但重复读受 FR-02/FR-09 影响 |
| 离线终帧宽限 | 3s；忙时最多重排 3 次 | attach 调度器；读快照、可能合成连接级 error | 有界，不应永久 loading；线程阻塞见 FR-06 |
| Redis 订阅失败恢复 | 未显式配置，使用容器缺省退避 | 独立订阅池 1/2 + 64；避免与消息派发共池 | 关键路径未发现共池睡眠；实际恢复行为留运行验证 |
| wake 派发 | 2/8 + 1024 队列 | 饱和时调用线程内联；onWake 内含同步读档 | 不丢通知，但饱和会把阻塞转回 Redis 订阅调用线程，纳入 FR-02/03 |
| SSE drain | 每拍最多 64 帧或 25ms | 投递池 8；生产每帧异步写 | 有界时间片正确；Redis 续读和写池仍有 FR-02/07 |
| SSE 单帧写预算 | 15s | 64 写线程 + 单线程 timeout scheduler | 只释放调用/投递线程，不保证 socket 写释放；FR-07/14 |
| RedisFrameStore 本地簿记清理 | 写路径至多每 60s 检查；超 4096 立即清 | 单拍 CAS 成功者在调用线程扫描/排序 | 有界；超限排序是 O(4096 log 4096) 兜底，不是常驻线程 |
| RunRegistry 空闲回收 | `of()` 上至多每 1 分钟扫描；30 分钟无触达才收 | 无独立线程；跳过有订阅/在写/收尾条目 | 关键路径未发现无界 timer；扫描随本地 emitter 数增长 |
| 会话删除终态等待 | 50ms 轮询，生产总窗口 1.5s | 当前 DELETE 请求线程，逐 run GET 快照 | 有界但耗时含 GET；超过窗口转 deferred，不是无限等待 |
| 会话销毁自动重试 | 30s 周期（直接相关配置/注释） | 独立重试设施，不在本报告展开 | 删除超时不等于放弃；deferred 可续清 |

未发现 Redis Stream 的 `XREAD BLOCK`、 busy-spin 或“每条连接一个无界线程”的生产路径。空闲等待主要来自 `Thread.sleep(50ms)` 的删除状态轮询、调度器周期任务和 socket 写阻塞，而不是 Stream 阻塞读。

---

## 6. 未发现问题的关键路径

1. **Cluster 原子写键族**：seq、xevents、xack、xtrim 都经 `RunStore.runScopedKey` 使用同一个 `{tenant:user:run:runId}` tag；runId 的花括号会被拒绝（`RunKeyScope.java:53-65`）。`APPEND_BODY` 四键同槽，未见跨键脚本 CROSSSLOT。
2. **序号与归档顺序**：正常生产出帧只走 `appendAndAllocateSeq`，号与 XADD 同 Lua；显式 ID `<seq>-0` 与读区间一致，未知回包以同 token + 精确 ID + 帧体判幂等，且未知回包只重试一次（`RedisFrameStore.java:810-882`）。归档失败不交付的方向正确。
3. **读回有界**：原文 XRANGE 使用 COUNT；重挂页 256、锁内入队 64/512KiB、wake 批默认 256、drain 64/25ms，未发现正常路径一次物化整个 Stream 的写法。
4. **每订阅者背压**：实时队列以 `liveQueued` 计数，回放帧不误占实时额度；溢出/写失败/池拒绝均摘除并允许 lastSeq 重挂。drain 收尾有 CAS 抢回和在飞 emit 判定，未发现明显“队空即抢在尾帧前 complete”的旧窗口。
5. **Pub/Sub 生命周期**：按身份 + run 分桶，不把全实例所有 run 塞到一个频道；精确句柄避免旧退订摘掉继任监听器。派发和订阅池均有界且分离。
6. **身份传播**：TurnKeepalive、StreamWatchdog、wake 订阅回调、drain 和实际 writer 都捕获或解析记录身份并显式开作用域；attach 调度线程主动 denied，避免首个触发者身份常驻。未看到这些核心异步边界直接依赖裸 ThreadLocal 继承。
7. **心跳语义**：业务 seq 与 heartbeatSeq 分离；心跳不进 Stream、不推进业务游标、不发布 wake；驱动与非驱动 attach 分别有保活，终帧/最后订阅者离开会停表。
8. **本地资源回收**：FrameStore 簿记有终态 forget、TTL 清理和 4096 上限；RunRegistry 有终态钩子与空闲扫描；RedisFrameWakeBus 停机有容器 destroy 和执行器有界关闭。
9. **帧内容处理**：正常 delta 按 JSON 转义字节分片并保护代理对；工具结果采用字段级处理而非字节砍 JSON。除 FR-11 的不可截字段兜底外，未发现故意产出坏 JSON 的路径。
10. **读失败可观测性**：尾探/范围读/取消读有降级计数或 WARN，重挂末号不可知可返回 503/typed error；未发现把 Redis 不可用直接宣称为多实例顺序正常的实现。

这些“未发现问题”只表示本次静态阅读没有发现相应缺陷，不等于运行时已证明；尤其 Cluster 重定向、容器订阅恢复和真实 TCP 背压仍需后续验证。

---

## 7. 建议修复顺序与总体验证方案

### 修复顺序

1. **先正确性**：FR-01 原子 fence；FR-05 按物理 entry 扫描；FR-08 坏页游标。
2. **再删除与主链路时延**：FR-04 终帧元数据/去重扫描；FR-02 锁内 I/O；FR-03 唤醒合流。
3. **再共享资源**：FR-06 调度器隔离；FR-07 非阻塞写/通道配额；FR-14 定时器移除。
4. **最后容量与收尾**：FR-09 重挂命令合并；FR-10 键生命周期；FR-11 硬帧上限；FR-12 分片算法；FR-13 全局容量状态。

### 后续验证计划（只描述，本次不执行）

- **静态/单元级**：针对原子 fence、跳号扫描、坏页续读、xtrim 删除、不可截字段和分片边界建立确定性用例；同时保留现有帧序、重挂和终态契约。
- **Cluster 集成**：至少三主拓扑，覆盖同槽 EVAL、MOVED/ASK、槽迁移、单节点不可用和恢复；记录每帧命令数、重定向次数、失败后的归档/交付一致性。
- **多实例并发**：驱动在 A、连接在 B/C，交替重挂、断连、取消和接管；断言每订阅者 seq 单调、无重复终帧、丢 wake 后 30s 对账可恢复。
- **删除接口**：按 run 数、归档条数、单帧大小、是否活跃和 Redis 延迟分层，分别测 pending/guard、取消扫描、1.5s 等待、13+ 键 DEL 和附件/DB 阶段耗时；验收应看 `complete/deferred` 与残留键，不只看 HTTP 200。
- **线程与内存**：对 Lua、XRANGE、活动 SET、快照 GET 和 socket 写分别注入停顿，采集各池 active/queue、调度延迟、写池 SATURATED、定时器队列和每订阅者队列字节；验证慢 run 不拖垮其它 run。
- **返回与恢复**：首轮/续跑/重挂/降级回放/终帧未归档/写超时/池饱和均检查客户端能看到明确终局或可重挂，且前端 lastSeq 能补回所有已归档帧。

## 8. 审查边界

本报告没有使用运行时日志、测试结论或历史报告作为缺陷证据；代码注释只用于理解设计意图，问题判断均回到当前基线源码。未验证具体部署的 Redis 版本实际响应、Lettuce/Spring 容器运行细节、Tomcat 实际 socket 释放时间、真实帧率、实例数和客户端行为，因此相关容量数字只表示配置/算法边界，不是实测结论。
