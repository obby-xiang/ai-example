# RunStore / Resume / 租约 / 取消与销毁链审查报告

- 基线：`52b09364`（审查时 `HEAD` 与基线一致，工作树起初无改动）。
- 审查方式：仅静态阅读生产代码与配置；未修改生产/测试代码，未运行测试、构建、CI、服务或压测，未做任何 Git 写操作。
- 重点：Redis Cluster 同槽与 Lua、快照/索引/pending/decision/claim/lease、跨实例接管、多线程与请求上下文、TTL/清理、轮询、N+1、同步阻塞，以及 `DELETE /api/ai/session/{sessionId}` 超过 30 秒的风险。
- 证据口径：“代码证实”表示控制流或状态缺口可由源码直接推出；涉及真实时延处只给出可构造条件与复杂度，不把未实测的秒数写成事实。

## 一、结论摘要

本轮确认 10 项问题。其中最需要优先处理的是：

1. **会话销毁没有整请求时间预算，且在 HTTP 线程串行完成枚举、取消收尾、50ms 轮询、逐 run 删除、两次 Cluster 扫描、附件与 DB/文件操作。**“活跃轮等待 1.5s”不是接口总时限；多 run、多 master、部分节点超时均可使总耗时超过 30 秒。见 **R2-01**。
2. **租约条件释放返回 0 后，本地视图被摘掉却不留 LOST 墓碑，后续 `authority()` 可回到 `NEVER_HELD`，重新开放无 fence 的 plain 写。**这会给已过期/已换主的旧驱动重新打开写门。见 **R2-02**。
3. **decision guard 与 pending 条目不是同一原子写；`APPROVED` 和 `FRONTEND_RESULT` 又被排除在 WAL 读侧重建之外。**写者死在两键之间后，重复决策和续跑均不能把轮子推进，形成直到 TTL/人工取消的悬轮。见 **R2-03**。
4. **取消 pending 是非原子“读—改—整写”，且 PENDING 分支不检查 claim 证据。**可覆盖并发批准/回灌，也可在工具已认领、尚未回写 pending 的窗口把它改写成“未执行取消”，与实际副作用相矛盾。见 **R2-04**。
5. **事件归档实际还有 `ai:xack:*` 与 `ai:xtrim:*`，但 `deleteRun` 只删 13 键，二者均遗漏。**`xtrim` 可留到会话 TTL；`xack` 虽只有 120s TTL，仍不符合销毁立即清净的口径。见 **R2-05**。

其余问题涉及恢复扫描与删除的 N+1/全量读、租约墓碑和客户端请求 HASH 的长期增长、取消收尾重试线程被同步 I/O 占住，以及取消/pending 轮询在慢 Redis 下的排队与 RTT 放大。

严重度口径：**高** = 可造成错误状态、重复/失控写入、永久悬轮或明显接口可用性风险；**中** = 需要规模、故障或特定时序触发，主要影响性能、资源占用或恢复时效。除特别标注“风险”外，问题本身均由代码证实。

## 二、审查范围与实际阅读文件

### 2.1 指定的七个核心文件（均完整阅读）

- `backend/src/main/java/com/example/configmgr/ai/run/RunStore.java`
- `backend/src/main/java/com/example/configmgr/ai/run/ResumeService.java`
- `backend/src/main/java/com/example/configmgr/ai/run/RunRegistry.java`
- `backend/src/main/java/com/example/configmgr/ai/run/LeaseService.java`
- `backend/src/main/java/com/example/configmgr/ai/run/ClientRequestRegistry.java`
- `backend/src/main/java/com/example/configmgr/ai/run/CancellationRegistry.java`
- `backend/src/main/java/com/example/configmgr/ai/run/CancelledRunFinalizer.java`

### 2.2 配置（阅读相关完整文件/配置段）

- `backend/src/main/resources/application.yml`：Redis 命令/连接超时均 2s；`park.enabled=true`；运行期恢复扫描 120s；销毁重试 30s；线程池及 TTL 配置。
- `backend/src/main/resources/application-cluster.yml`：Cluster 拓扑、3 次重定向、同样的 2s 超时。
- `backend/src/main/java/com/example/configmgr/ai/config/AiProperties.java`：阅读 `Lease` 配置段，确认租约 30s、续租 10s、重试 35s、资源锁 60s。

### 2.3 直接调用方、存储与并发依赖

下列文件均实际打开阅读；大文件按本报告所列调用点阅读了对应完整方法/脚本，而非只看检索摘要：

- 入口与销毁：`ai/web/AiController.java`、`ai/web/AiSessionPurgeController.java`、`ai/session/AiSessionPurgeService.java`（完整）、`ai/session/SessionPurgeRetryRunner.java`（完整）、`ai/session/SessionPurgePendingStore.java`（完整）、`ai/session/SessionPurgeGuard.java`（完整）、`ai/session/SessionPurgeDatabase.java`（完整）、`ai/session/RedisClusterScanSupport.java`（完整）。
- 决策/工具/帧：`ai/gate/ConfirmGate.java`、`ai/gate/SpToolCallingManager.java`、`ai/gate/ToolUnknownReconciler.java`、`ai/run/ResilientChatService.java`、`ai/run/SseChatEmitter.java`、`ai/run/RedisFrameStore.java`（完整）、`ai/run/StreamWatchdog.java`、`ai/run/RunLiveness.java`。
- 恢复与线程：`ai/run/StartupResumeRunner.java`、`ai/exec/AiExecutorConfig.java`（完整）。
- 键与上下文：`ai/run/SessionKeyRegistry.java`（完整）、`ai/tenant/RunKeyScope.java`（完整）、`ai/tenant/TenantKeyScope.java`（完整）、`ai/tenant/RunIdentityScope.java`（完整）、`ai/tenant/RequestContextUtils.java`（完整）。
- 记忆/附件/记录：`ai/memory/RedisChatMemoryRepository.java`、`ai/form/RedisFormAttachmentStore.java`、`ai/form/FormAttachmentStore.java`、`ai/run/RunSnapshot.java`。

未把测试实现、测试替身或既有测试结论当作生产正确性的证据。

## 三、键形状、Lua 原子性与 Cluster 同槽总账

### 3.1 run 级键

身份为 `(t,u)`、runId 为 `r` 时，核心键统一为 `<prefix>{t:u:run:r}`；无身份 legacy 为 `<prefix>{run:r}`。拼接口是 `RunKeyScope.scoped`，并拒绝 runId 中的花括号，证据见 `ai/tenant/RunKeyScope.java:39-70`。

| 状态 | Redis 类型/值 | 同槽与生命周期 |
|---|---|---|
| `ai:run:{...}` | STRING，完整 `RunSnapshot` JSON | 13 键之一；session TTL 滑动 |
| `ai:runstate:{...}` | STRING，轻量状态串 | 与快照同脚本写、同 TTL |
| `ai:pending:{...}` | HASH，`toolCallId -> PendingToolCall JSON` | 人审/前端/后端条目本体 |
| `ai:req:{...}` | HASH，`toolCallId -> 首次请求时刻` | 与 pending 分离，避免置 req 时覆盖决策 |
| `ai:decided:{...}` | HASH，`toolCallId -> status|atMs|instanceId` | 决策唯一写者与 WAL，但载荷不完整，见 R2-03 |
| `ai:claim:{...}` | HASH，`toolCallId -> epoch|instanceId` | HSETNX 首写者认领 |
| `ai:ledger:{...}` | LIST，执行结果全文，只追加 | 与 `idem` 同 Lua；不能 LTRIM |
| `ai:idem:{...}` | HASH，索引与 `~meta:<index>` 小型投影 | 与台账同 Lua、同 TTL |
| `ai:seq:{...}` | STRING，业务帧号 | 与 Stream 同脚本取号/归档 |
| `ai:xevents:{...}` | Stream，`seq + frame` | 有窗口与 TTL；回放真值 |
| `ai:lease:run:{...}` | STRING，`epoch|owner`，默认 30s | 续跑权；续/放均单键 Lua |
| `ai:fence:run:{...}` | STRING，已落库最大 epoch | 快照持租写的 fencing |
| `ai:lease:epoch:{...}` | STRING，per-run 取号计数器 | 与 lease/fence 同槽，acquire 内对账 |

`deleteRun` 的一条 13 键 `DEL` 位于 `RunStore.java:2906-2908`；以上 13 键同 tag，所以 standalone 与 Cluster 都是一条同槽命令，不存在此处跨槽拆分。

**额外键不能混入“13 键已全部覆盖”的结论：**

- `ai:xack:<token>:{t:u:run:r}`：每帧幂等标记，120s；由 `RedisFrameStore.java:914-917` 拼出。
- `ai:xtrim:{t:u:run:r}`：归档裁剪低水位，随会话 TTL；见 `RedisFrameStore.java:600-603`。
- `ai:beat:<t>:<u>:<r>`、`ai:activity:<t>:<u>:<r>`、`ai:cancel:<t>:<u>:<r>`、`ai:observed:<t>:<u>:<r>`：无 tag，只做单键命令；销毁服务另发四次 DEL，见 `AiSessionPurgeService.java:1253-1257`。

### 3.2 session / client-request / purge 键

- `ai:session:runs:<t>:<u>:<sid>`：SET，销毁轮次的唯一正常发现面；创建时 `SADD+PEXPIRE` 单键 Lua，证据为 `RunStore.java:722-727`、`RunStore.java:1194-1203`。
- `ai:session:current:<t>:<u>:<sid>`：STRING 当前 run；删除/回填/续期均为单键 CAS Lua。
- `ai:session:active:<t>:<u>:<sid>`：ZSET，score 为最近活跃时刻；`ZADD+PEXPIRE` 单键 Lua。
- `ai:creq:<t>:<u>:<sid>`：HASH，`clientRequestId -> RUN/DONE/CANCELLED...`；所有 Lua 都只有这一把 KEYS，Cluster 安全。
- `ai:purge:pending:<t>:<u>:<sid>`：无 TTL 的 durable STRING JSON；`ai:purge:retry:due` 是全局 ZSET。二者不假装同事务，而以 purgeId CAS 和读后补偿收敛。

### 3.3 多键 Lua 结论

| 脚本 | KEYS | 结论 |
|---|---|---|
| `FENCED_SET` / `FENCED_TERMINAL_SET` | run、fence、runstate | 同 tag，Cluster 合法；fence 与取消检查在脚本内 |
| `CREATE_IF_ABSENT` / fenced 变体 | run、runstate（及 fence） | 同 tag，避免恢复时无条件覆盖 |
| `IDEM_PUSH` | ledger、idem | 同 tag；RPUSH、HSETNX、元数据投影和 TTL 同步执行 |
| `LEASE_ACQUIRE` | lease、per-run epoch、fence | 同 tag；取号与占租同序，且 epoch 至少为 fence+1 |
| `RENEW_SAME_SLOT` | 按 tag 分组后的续期键 | 同组同槽；无 tag 的 cancel 单独发，不混槽 |
| `AI_FRAME_APPEND` | seq、xevents、xack、xtrim | 四键同 tag；定号、归档、幂等位和 TTL 同步执行 |
| session current/runs/active、creq、purge 各脚本 | 单键 | 无 CROSSSLOT 面 |

**因此，本轮没有发现上述既定多键脚本因现有 run tag 拼错而必然 CROSSSLOT 的问题。**真正的问题集中在脚本外的多步协议、额外键的删除遗漏和同步串行成本。

## 四、入口到存储/线程/返回的完整交叉链

### 4.1 新一轮 `/chat`

1. `POST /api/ai/chat` 在 HTTP 线程做参数、Redis PING、销毁守卫、模型与会话门检查；生成 runId，并以 `clientRequestId` 调 `ClientRequestRegistry.claim`。证据：`AiController.java:329-385`、`AiController.java:444-510`。
2. 建 `SseEmitter` 与进程内 `RunRegistry` 写出器，提交到 `aiRunExecutor`。该池固定 20、队列 0，装饰器传播 MDC 与 TTL 身份；证据：`AiController.java:544-564`、`AiExecutorConfig.java:93-113`。
3. `ResilientChatService.chat` 先发 start，注册取消位，做记忆/压缩；`RunStore.create` 要求身份，先登记 session→run，再写首笔 RUNNING 快照，最后 best-effort 补全局 `ai:runs`。证据：`ResilientChatService.java:776-856`、`RunStore.java:1166-1221`。
4. 建快照后 `takeDriveOwnership` 抢 run 租约并登记续租，再进入模型/工具循环。证据：`ResilientChatService.java:858-869`、`ResilientChatService.java:1129-1182`。
5. 返回侧是 SSE；业务帧经 `SseChatEmitter.emit -> RedisFrameStore.appendAndAllocateSeq`，再本地扇出与跨实例唤醒。终态经 `RunStore.save`、记忆/展示历史、client-request complete、本地清理，HTTP/驱动 finally 释放会话门并关闭本地注册项。

### 4.2 新挂起与 park

1. `SpToolCallingManager` 先构造 pending 和完整历史，`RunStore.saveSuspended` 逐条 HSET+EXPIRE，再读快照、写 SUSPENDED；随后才允许进入等待。证据：`SpToolCallingManager.java:1052-1088`、`RunStore.java:3314-3338`。
2. park 开启时，`ConfirmGate.publishRequestsForParkedBatch` 先写 req 再发整批请求帧；`await` 在 `switchParkedLifecycle` 的本地 run 锁内复读：已决则当前线程继续，未决则还租、摘 latch、抛 `RunParkedException` 结束本次驱动。证据：`ConfirmGate.java:771-803`、`ConfirmGate.java:893-960`、`ConfirmGate.java:1124-1134`。
3. park 关闭时才进入 2s 等待循环：取消 GET、pending/req/decision 读取、心跳、租约与长 TTL 资源续期；它持续占用运行池线程。证据：`ConfirmGate.java:975-1066`。

### 4.3 批准、拒绝、前端回灌

1. `/confirm` 或 `/frontend-tool-result` 先 `touchActiveRun`，再读条目并调 `ConfirmGate.submit*`。证据：`AiController.java:851-899`、`AiController.java:933-1015`。
2. 提交侧先 `ai:decided HSETNX`，再改 pending，随后在实例内 submit lock 中写条目、发回执帧、signal；最后按 woke/latch/SUSPENDED/lease 决定是否向 `aiResumeTrigger` 异步派发。证据：`ConfirmGate.java:1810-1876`、`ConfirmGate.java:2133-2233`。
3. `ResumeService.resume` 对 Redis 校验租约，使用进程内 `resuming`、Redis run 租约和 session gate 三层准入；读取快照/pending/cancel，重建完整历史并结算工具，写 RUNNING，再提交 `aiRunExecutor` 调 `driveResumed`。证据：`ResumeService.java:1102-1165`、`ResumeService.java:1172-1335`、`ResumeService.java:1359-1474`、`ResumeService.java:1727-1803`。
4. 池饱和、会话忙、取消未知等有界重派发；返回 HTTP 的 `resumeTriggered` 只是投递结论，不是续跑完成结论。

### 4.4 停止与取消收尾

1. `/cancel/{runId}` 或 `/cancel/client-request` 核归属后写 `ai:cancel`；同实例 listener 立即唤醒，跨实例靠 1Hz 看门狗或 2s 非 park 等待轮询。证据：`AiController.java:1495-1506`、`AiController.java:1600-1635`、`CancellationRegistry.java:220-231`。
2. park 挂起轮走 `triggerResumeForParked`；运行轮可交 `CancelledRunFinalizer`。交棒时抓记录身份，在 `aiResumeTrigger` 或重试时间线上执行 `ResumeService.cancelConfirmed`。
3. `cancelConfirmed` 依次做进程内互斥、读快照、读取消事实、归档终帧探测、session gate、run lease acquire+verify，再调 `cancelParked`。证据：`ResumeService.java:2323-2430`。
4. `cancelParked -> cancelTerminalLocked` 读快照、探测终帧、取消 pendings、写 CANCELLED 快照、写展示历史/记忆、发裁决及 done、清 client-request 和进程内状态。证据：`ResilientChatService.java:3330-3340`、`ResilientChatService.java:3389-3521`。
5. 若写门拒绝，`CancelledRunFinalizer` 以 2s 指数至 30s，或租约占用时 35s，做有界重试；耗尽只留 ERROR，后续取消/重挂/扫描可再触发。

### 4.5 刷新、重挂与恢复扫描

- `/runs/current`：current 索引 GET → 快照 GET → 读侧 TTL 续期 → pending/req/decision →归档末号，返回重建数据；见 `AiController.java:1884-1965`。
- `/events/{runId}`：读快照与归档，按 lastSeq 回放并挂订阅；SUSPENDED 且仍等人则收流，已决无驱动则触发自愈续跑。见 `AiController.java:1104-1205`。
- 启动/运行期扫描：`ai:runs SMEMBERS` → 逐成员 EXISTS → 按成员身份读快照/pending/cancel/activity → 分类为取消收尾、到期收敛、续跑或跳过。运行期默认 120s、单线程 fixed-delay，系统面用无 tag 扫描锁做单飞；证据：`StartupResumeRunner.java:497-551`、`StartupResumeRunner.java:581-734`、`StartupResumeRunner.java:1147-1170`。
- 续租线程、扫描线程、交棒线程都显式按记录身份开 `RunIdentityScope`；`RequestContextUtils` 使用 TTL，并有同线程关闭与嵌套还原，未发现目标链把请求身份句柄跨线程关闭的情况。

### 4.6 删除会话到 HTTP 返回

`DELETE` 入口只做参数、PING、身份检查后同步等待 service 返回，见 `AiSessionPurgeController.java:71-103`。完整本体是：

1. pending 登记与 due ZSET；
2. gate/current/session-runs 枚举，逐 run 读完整快照与 pending/req/decision，另读附件和 DB；同时做一次归档 SCAN 与一次 memory-staging SCAN；
3. 装 purge guard，H2 独立事务删反馈；
4. 逐活跃 run 写取消并**同步调用** `cancelConfirmed`；
5. 50ms 间隔重读未终态 run 的完整快照，设计等待窗口 1.5s；
6. 再重读状态，删记忆/transcript，逐 run 调 `deleteRun`，再删四个无 tag 锚点；
7. 逐上下文/归档键 TYPE+DEL，删会话/creq/draft，逐附件删文件和 Redis 登记；
8. 仅无错误且无 deferred 时删发现键；否则保留 pending/guard，由 30s runner 重试；最后组成 200 响应。

对应证据：`AiSessionPurgeService.java:350-418`、`AiSessionPurgeService.java:462-540`、`AiSessionPurgeService.java:566-685`、`AiSessionPurgeService.java:845-980`、`AiSessionPurgeService.java:1191-1268`。

## 五、逐项问题

### R2-01｜高｜删除接口无 30 秒总预算，同步串行链可超 30 秒

- **确定性：代码证实无整请求 deadline；超过 30 秒属于可构造风险，未做运行实测。**
- **证据：**
  - HTTP 线程直接等待 `purge`：`AiSessionPurgeController.java:87-103`。
  - 主链串行执行取消、等待、刷新和全部删除：`AiSessionPurgeService.java:462-486`。
  - 每个活跃 run 都同步执行完整 `cancelConfirmed`：`AiSessionPurgeService.java:845-871`、`AiSessionPurgeService.java:884-901`；后者又含多次快照/归档/gate/lease/条目/记忆/帧操作，见 `ResumeService.java:2323-2406`。
  - 50ms 轮询每次 GET 并反序列化完整快照：`AiSessionPurgeService.java:911-947`。deadline 只在循环顶检查，单轮内 N 次 Redis 调用不受 1.5s 硬性截断。
  - 每次销毁分别做 archive 与 staging 两次逐 master SCAN：`AiSessionPurgeService.java:637-638`、`AiSessionPurgeService.java:739-795`、`AiSessionPurgeService.java:817-828`；扫描会抽干游标，见 `RedisClusterScanSupport.java:98-147`。
  - 归档还按 turnNo 推导最多 2001 个候选，删除阶段每个候选 TYPE 后再 DEL：`AiSessionPurgeService.java:745-760`、`AiSessionPurgeService.java:1309-1352`。
- **触发条件：**多 run/多附件/多归档、多 master；Redis 慢查询、MOVED/ASK、部分 master 故障或 2s 命令超时；活跃轮在别的实例且取消收尾不能立即落定。初始 PING 成功并不排除后续不同节点/命令逐个超时。
- **standalone / Cluster：**standalone 同样受 N+1、DB/文件与轮询影响；Cluster 额外增加逐 master SCAN、跨槽单键 RTT 和重定向。同槽 13 键 DEL 本身不是慢源，慢源是其前后的串行编排。
- **多实例/多线程：**销毁服务没有用异步工作线程；并发 DELETE 可重复执行同一 pending 的重开/清理链。每个实例的 30s retry runner 又可能叠加相同存储压力。活跃轮的取消还涉及其它实例的 lease 与本地 monitor。
- **根因：**把持久化清理工作流当作一个同步 HTTP 调用；只有“活跃等待子段”有名义窗口，没有覆盖枚举、取消、扫描、删除与收尾的总预算，也没有按槽 pipeline。
- **最佳修复：**将销毁变成“登记 pending/guard + 提交持久化任务 + 立即返回 purgeId/进行态”的异步工作流，由现有 retry runner 或专用有界执行器分阶段推进；每阶段记录 checkpoint，取消与删除分开，HTTP 查询只读进度。这样可从契约上消除 30s 阻塞，而不只是调小 sleep。
- **备选与取舍：**若必须保留同步 200 明细，至少加覆盖全链的单调时钟 deadline，超时返回既有 `complete=false/deferred`；把取消收尾改为交棒而非同步调用；按槽 pipeline 状态读取/锚点 DEL；用 runstate 替代终态轮询中的整快照；复用枚举快照，限制单批 run/archive/attachment 数。它改动较小，但仍需小心“部分成功后响应”和客户端重试语义。
- **涉及修改点/影响面：**`AiSessionPurgeController`、`AiSessionPurgeService`、`RunStore` 批量读删接口、`ResumeService.cancelConfirmed` 的调用方式、`SessionPurgeRetryRunner`；若改异步需新增进度读取契约并调整前端等待方式。
- **后续验证（仅方案）：**分别构造 0/1/20 个终态与活跃 run、2000 归档、多个附件，在 standalone 与 3-master Cluster 测完整 p50/p95/p99；对单个 master 注入超时/MOVED，确认响应仍有总上界且 pending 可续清；并发两个 DELETE 与自动 retry 交叉，验证不重复取消、不提前删发现键。

### R2-02｜高｜租约“释放返回 0”会把旧写者错误恢复为 NEVER_HELD/plain 写

- **确定性：代码证实；触发需要租约已过期、被接管或释放时键值已变化。**
- **证据：**
  - `release` 的 Redis Lua 只在 token 匹配时 DEL，否则返回 0：`LeaseService.java:225-234`。
  - Java 侧无论 Redis 是否删成功，都先按本 lease 删除本地 `held` 并停续租；只有 `released>0` 才清 `ended`，但返回 0 时没有新增 LOST 墓碑：`LeaseService.java:744-767`。
  - `authority` 在“无 ended、无 held、设施健康”时返回 `NEVER_HELD`：`LeaseService.java:839-855`。
  - `RunStore.persistInScope` 此时 epoch=0，会进入 plain 分支；虽有 WARN 观察 fence，但不拒写：`RunStore.java:1432-1456`、`RunStore.java:1480-1496`、`RunStore.java:1762-1784`。
- **触发时序：**A 持 epoch=e；e 到期或 B 已接管；A 调 release，Lua 返回 0；A 的 held 被删且没有 LOST；后续 PING/任意成功命令证明设施健康；A 的旧驱动再写快照/帧/记忆时即可走无租约路径。若 B 已推进 fence，当前代码也只是 WARN。
- **影响：**破坏“LOST/换主后只读”的核心不变量，可能让旧驱动覆盖新主人的状态或继续出帧。standalone 多线程同 JVM 也可能发生；Cluster/多实例增加租约到期与接管的真实竞争面。
- **根因：**把 Redis 条件删除与本地所有权状态拆成两套结论，且把“0=我没删掉”误作“从未持租”。
- **最佳修复：**run 租约在 `released==0/null` 时按 `LOST` 或至少 `UNAVAILABLE` 写 ended，再条件摘 held/停续租；只有确认 DEL 成功才清 ended。补充不变量：凡本进程持过带 fence 的 run 租约，未成功重新 acquire 前不得回 plain 写。
- **备选与取舍：**更彻底的做法是让所有既有 fence 的 run 即使本地 NEVER_HELD 也走 fence 守卫 Lua，而非 plain SET；这还能降低 ended 本地墓碑对正确性的依赖，但会改变首轮/降级兼容路径并增加一次脚本执行。
- **涉及修改点/影响面：**主要是 `LeaseService.release/authority`；需要联动复核 `RunStore.persistInScope`、取消/终态释放和停机释放。
- **后续验证（仅方案）：**用可控租约时钟覆盖“过期未有人接管”“B 已接管”“同 key 新 epoch”“释放回包未知”四种顺序；断言旧 owner 后续快照、帧、台账、记忆全部拒写，新 owner 可正常推进。

### R2-03｜高｜decision WAL 与 pending 非原子，APPROVED/FRONTEND_RESULT 崩溃后无法收敛

- **确定性：代码证实。**
- **证据：**
  - 决策先 HSETNX guard，再由另一调用写 pending：`ConfirmGate.java:1821-1859`、`ConfirmGate.java:2162-2197`。
  - guard 只有 `status|atMs|instanceId`，没有前端结果正文：`RunStore.java:4773-4777`。
  - 读侧明确不重建 APPROVED 与 FRONTEND_RESULT：`RunStore.java:3522-3535`。
  - 重复提交遇到这两类时直接 DUPLICATE，不补 pending，仅尝试派发：`ConfirmGate.java:1981-2025`。
  - 续跑看到 Redis 原始 PENDING 后返回 `PENDING_UNRESOLVED`，不驱动：`ResumeService.java:1290-1298`。
- **触发条件：**唯一写者在 guard 成功之后、pending 写入之前崩溃，或 pending 写失败留下同样分裂。APPROVED 虽缺执行侧新字段，但原 pending 中已有完整请求参数，现状仍选择不合并；FRONTEND_RESULT 的结果正文则完全没有进 guard。
- **影响：**之后每次相同人工决策都撞既有 guard，续跑/运行期扫描又因 PENDING 拒绝驱动；刷新仍显示未决。直到 TTL、取消或人工运维，不能靠现有重试自愈。
- **单例/Cluster/多实例：**单进程异常退出也可发生；多实例让另一实例的重复提交/扫描必然经过同一分裂状态。两键同槽，因此不是 Cluster 限制导致无法修，恰恰相反可以用一条同槽 Lua 根治。
- **根因：**把 guard 当 WAL，却没有为所有结论保存可重建载荷；同时把“唯一写者”和“条目落地”拆成两个命令。
- **最佳修复：**用同一 run tag 下的单条 Lua 原子完成“检查原条目仍为 PENDING + HSETNX decided + HSET pending + 两键 PEXPIRE”。在 Java 内先把完整 PendingToolCall（含 resultText/outcome/code/reason/executed=false）序列化传入；脚本返回本次写的完整条目或既有结论。这样 guard 是互斥位，不再需要靠残缺 WAL 恢复。
- **备选与取舍：**保留两步时，guard 必须携带完整决策信封，并允许 APPROVED 按原条目安全重建；FRONTEND_RESULT 至少要持久化 resultText/outcome/code/reason。该方案仍多一次 RTT，且回执帧的崩溃补发需另作幂等设计。
- **涉及修改点/影响面：**`RunStore.tryRecordDecision/readPendings/applyDecisionMarker`、`ConfirmGate.doSubmitDecision/doSubmitFrontendResult/convergeOnDecided`、重复请求响应语义；要同时保留“唯一回执帧”约定。
- **后续验证（仅方案）：**在 guard 前后、pending 前后分别故障注入；对批准、拒绝、前端结果、放弃和超时逐一验证重复提交幂等、重挂可恢复、续跑不重复执行；在 Cluster 验证脚本 KEYS 同槽。

### R2-04｜高｜取消 pendings 的读后整写可覆盖并发决策，并抹掉已认领工具的执行证据

- **确定性：代码证实竞争窗口；是否发生取决于交叉时序。**
- **证据：**
  - `cancelPendings` 遍历快照副本后直接 `putPending`，没有版本/CAS/guard：`RunStore.java:4274-4288`；`putPending` 是无条件 HSET+EXPIRE：`RunStore.java:3340-3348`。
  - `cancellableBeforeExecution` 对 PENDING 直接 true，只有 APPROVED 才检查执行证据：`RunStore.java:4331-4341`。
  - 工具认领先 HSETNX claim，再读/写 pending 的 claimed/executed 字段：`RunStore.java:3751-3781`。因此“claim 已存在、cancel 读到的仍是旧 PENDING”是合法窗口。
  - 真正执行结束后还会写台账并再写 pending：`SpToolCallingManager.java:2903-2931`，可与上述取消副本互相覆盖。
- **触发条件：**取消与批准/前端回灌并发；或后端工具已 HSETNX 认领但尚未回写 pending 时，取消链读到旧 PENDING。
- **影响：**决策丢失、条目与 decided guard 分叉；更严重的是实际已认领/可能已产生副作用的工具被记成 `CANCELLED + executed=false`，台账、帧与条目对同一事实给出矛盾结论。
- **单例/多线程/多实例：**本地 submit lock 不能覆盖 `RunStore.cancelPendings` 的所有调用方，更不能跨实例；Cluster 下这些键同槽，可安全使用条件 Lua。
- **根因：**把条目 JSON 当作可任意整写的最新副本，没有将“判当前状态/claim + 写取消”放进同一原子步。
- **最佳修复：**新增同槽条件结算 Lua：读取 pending 当前 JSON、检查 claim HASH，并只在允许的原状态且没有执行证据时写 CANCELLED；同时按明确规则更新 decided 与 TTL。执行完成写回也必须带 claimed epoch/原状态条件，不能把取消后的新副本随意覆盖。
- **备选与取舍：**为 pending 增加 version 并做 CAS 也可，但 Java 侧 WATCH/MULTI 的 Cluster 与重试复杂度更高；单纯在现有代码前再读一次 claim 只能缩窗，不能根治。
- **涉及修改点/影响面：**`RunStore.cancelPendings/cancellableBeforeExecution/putPending`、`SpToolCallingManager.claimAndExecute`、取消帧与台账一致性；可复用 R2-03 的同槽条件结算基础设施。
- **后续验证（仅方案）：**用屏障分别停在 claim HSETNX 后、pending 回写前、决策 guard 后、台账前后，交叉取消/批准/回灌；断言任一交错下条目、guard、ledger、终帧不互相说谎。

### R2-05｜中｜销毁遗漏 `xack` 与 `xtrim` 键

- **确定性：代码证实。**
- **证据：**额外键及 TTL 见 `RedisFrameStore.java:217-235`；四键归档脚本实际使用它们，见 `RedisFrameStore.java:888-892`；`RunStore.deleteRun` 的 DEL 清单没有这两项，见 `RunStore.java:2906-2908`；销毁服务也没有其它针对这两个前缀的清理。
- **触发条件：**任何写过业务帧的 run 被销毁；xtrim 只有实际裁剪时才有值，xack 在近两分钟有出帧时存在。
- **影响：**`xtrim` 的 run 元数据可保留至会话 TTL；`xack` 虽在 120s 后自清，仍使“本次 complete 时全部 AI 数据已删”不成立。它们不含帧正文，因此本项主要是销毁完整性/元数据残留，不夸大为正文泄露。
- **单例/Cluster：**两者都发生；同 tag 让 xtrim 可直接加入原子 DEL，但 xack 的 token 在删除时未知，不能只加一个常量键。
- **根因：**后续帧幂等与裁剪键没有纳入 `SessionKeyRegistry` 的删除面，也没有 per-run 发现索引。
- **最佳修复：**把 xack 改为**每 run 一把 HASH**、token 为 field，值带 seq/expireAt，由既有有界 Lua 清理过期 field；销毁时把该 HASH 与 xtrim 一并加入 run DEL。这样避免逐 token 键和 SCAN。
- **备选与取舍：**最小改动是先直接删除 xtrim；xack 维持短 TTL并明确登记为延迟清理。若要求立即删净，可维护同槽 xack-key SET，再用 Lua 读成员并删除全部，但要处理每帧索引写入成本与崩溃残留。
- **涉及修改点/影响面：**`RedisFrameStore`、`RunStore.deleteRun`、`SessionKeyRegistry`、销毁结果复核；需要兼容旧的 per-token 键一段 TTL 时间。
- **后续验证（仅方案）：**在出帧后、裁剪后立即销毁，逐前缀断言无残留；覆盖旧版 xack 键的迁移期与 Cluster 各主节点。

### R2-06｜中｜恢复/列表/终态回指存在系统性 N+1 与大 JSON/大 HASH 读

- **确定性：代码证实复杂度；实际慢度依赖候选规模与部署。**
- **证据：**
  - `allRunIds/allRunRecords/pruneRunIndex` 都是 `SMEMBERS ai:runs` 后逐成员 EXISTS：`RunStore.java:3120-3151`、`RunStore.java:3163-3189`、`RunStore.java:3218-3261`。
  - 每个恢复候选再读完整快照、pending/req/decision 三份 HASH、cancel、activity STRING 与 Stream 尾部：`ResumeService.java:479-508`、`ResumeService.java:583-607`、`ResumeService.java:644-646`、`RunStore.java:3367-3396`、`RunStore.java:4843-4899`。
  - HTTP `listRun` 还读 ledgerCounts 与 heartbeat；投影虽避免正文，但 HGETALL 的字段数仍随工具台账线性增长：`ResumeService.java:512-557`、`RunStore.java:4158-4231`。
  - 终态 `clearSessionCurrent` 对 active ZSET `reverseRangeWithScores(0,-1)` 全量取回，并可能逐候选读完整快照；老索引回退还会 SMEMBERS+逐 run 快照：`RunStore.java:1933-1946`、`RunStore.java:2284-2342`、`RunStore.java:2378-2423`。
  - 机会式 prune 的 CAS 只在单 JVM 内，不能阻止多实例各自全表扫：`RunStore.java:3272-3288`。
- **触发条件：**全局终态 run 长期积累、单会话多 run、工具台账长、pipeline 降级、每 120s 恢复扫描或多实例同时创建清理。
- **影响：**恢复线程和 HTTP 列表线程被长串同步 Redis/JSON 工作占用；Cluster 中逐成员请求分散到不同节点，RTT 与部分故障概率随 N 增长。也会间接延长删除/取消终态链。
- **根因：**全局 SET 只有 runId/身份，没有轻量状态投影；发现、存活、展示混用完整快照；终态回指读取整个排序集而不是有界首页。
- **最佳修复：**恢复发现采用按槽分组的 pipeline EXISTS/GET runstate；只为真正需要接管的候选读必要字段，避免恢复投影物化 messageJson；active ZSET 使用有界倒序分页。长期可把恢复候选改为带状态/活动时间的 ZSET 或独立轻量 HASH，但必须保持与快照写同事务/同槽或提供可补偿对账。
- **备选与取舍：**保留现有键时可先 pipeline 并限制每拍候选；已有 `scan-max-attempts` 只限制后续动作，**不限制前面的全索引枚举成本**。prune 可加短时分布式 throttle 锁减少多实例重复，但清理可容忍重复，不必引入强一致锁。
- **涉及修改点/影响面：**`RunStore` 发现与轻量读、`ResumeService` 投影、`StartupResumeRunner` 预算边界、`clearSessionCurrent`。
- **后续验证（仅方案）：**构造 1k/10k 全局索引、单会话数百 run、长 ledger，比较命令数、网络字节、扫描时长与堆占用；覆盖 pipeline 不可用、部分 master 失败和多实例同时清理。

### R2-07｜中｜LeaseService 的 ended 墓碑在长跑 JVM 内无 TTL/容量上界

- **确定性：代码证实。**
- **证据：**`ended` 是 `ConcurrentHashMap`；注释明确没有 TTL 到期或容量淘汰，清除只依赖成功 acquire/release、进程退出：`LeaseService.java:374-397`。LOST 后若没有同 owner 再 acquire，条目就一直保留；`RunStore.deleteRun` 也刻意不清它。
- **触发条件：**实例长期运行、持续处理不同 runId，且部分轮次丢租/不可确认后不再由同 owner 成功 acquire/release。
- **影响：**内存与全键扫描成本随历史故障轮数累积。LOST 条目不自动消失本身是防 plain 写的安全选择，不能简单定时删掉了事。
- **根因：**正确性依赖一张只进不出的进程内历史表，却没有把“曾经的所有权”转成有界/持久化模型。
- **最佳修复：**先落实 R2-02 的“fence 存在时无本地租约也不得 plain 写”，再为 ended 加时间/容量治理；过期后应由 Redis fence/记录状态决定安全写法，而不是直接 NEVER_HELD。
- **备选与取舍：**短期可设硬上限并在饱和时让相关路径 fail-closed，但会影响可用性；按 run 删除墓碑虽省内存，却会给迟到旧写者开门，不建议单独采用。
- **涉及修改点/影响面：**`LeaseService.ended`、`RunStore` plain/fence 写门、run 删除生命周期。
- **后续验证（仅方案）：**连续制造不同 runId 的丢租与删除，观察堆/表大小；墓碑到期后重放旧写者，必须仍被 fence 拒绝。

### R2-08｜中｜client-request HASH 的 CANCELLED 与异常残留 RUN 字段不参与回收

- **确定性：代码证实。**
- **证据：**类注释明确 CANCELLED 不参与回收、RUN 永不回收：`ClientRequestRegistry.java:29-35`；有界 sweeper 只删除超龄 DONE：`ClientRequestRegistry.java:122-168`。而 claim/cancel/complete 与读侧 touch 会不断给整 HASH 续期。
- **触发条件：**同一会话长期活跃并积累大量取消；或运行轮因异常/身份/写失败未执行 complete，留下 RUN 字段。
- **影响：**HASH 字段数与每次 HSCAN 工作量随会话历史增长；24h 整键 TTL 在持续活动下不会提供字段级收敛。不会因此跨租户串键，但会造成内存与清理成本累积。
- **根因：**把“防重/取消早于创建”的持久语义绑定到永久 field，只实现了 DONE 的 TTL 回收。
- **最佳修复：**给取消墓碑增加可兼容的取消时刻并按明确保留期纳入同一有界 sweeper；对 RUN 增加与 runstate/终态的对账修复，避免把真正活跃绑定误删。保留期不得短于“迟到 /chat 必须看到取消”的业务窗口。
- **备选与取舍：**直接永久保留最简单且防重最强，但需明确接受容量无界；只按 DONE 保留期清 CANCELLED 会改变迟到请求语义，不能无声采用。
- **涉及修改点/影响面：**`ClientRequestRegistry` 值格式、解析器、SWEEP_LUA、`ResilientChatService.completeClientRequest`；需兼容旧值。
- **后续验证（仅方案）：**同会话构造大量 RUN/DONE/CANCELLED 混合字段，验证游标续扫无饿死、活跃 RUN 不误删、过期取消可收、迟到请求防重符合约定。

### R2-09｜中｜取消收尾的延迟重试会在单线程调度器上直接执行完整 Redis 收尾

- **确定性：代码证实。**
- **证据：**首次交棒走 `aiResumeTrigger`，见 `CancelledRunFinalizer.java:327-346`；但重试直接 `scheduler.schedule(() -> attempt(...))`，见 `CancelledRunFinalizer.java:438-447`，`attempt -> runAttempt -> cancelConfirmed` 不再转投执行器。默认重试调度器只有一条线程：`CancelledRunFinalizer.java:114-138`。
- **触发条件：**至少一个取消收尾需要重试，且 cancelConfirmed 遇到慢 Redis、归档读、session gate、租约、多个 pending 或记忆写回。
- **影响：**本进程其它 run 的取消重试排在同一单线程后，增加终态/终帧延迟；首次执行与重试执行的线程模型也不一致。
- **根因：**把“到点”与“执行完整工作”合在 single-thread scheduler 中。
- **最佳修复：**定时器到点只向既有 `aiResumeTrigger` 转投 `attempt`，保留同一 identity/inFlight/attempts；拒绝时走既有有界 reschedule。调度线程不得做 Redis I/O。
- **备选与取舍：**扩大重试调度池可缓解但不能消除阻塞耦合；转投会重新引入队列饱和，必须保留当前上限与去重，不能简单改成无界线程。
- **涉及修改点/影响面：**`CancelledRunFinalizer.reschedule/submit` 与 `AiExecutorConfig` 的触发池容量观测。
- **后续验证（仅方案）：**阻塞一条重试的 Redis 调用，确认其它 run 的到点任务仍能转投；覆盖触发池满、停机、重试耗尽与身份还原。

### R2-10｜中｜取消与 pending 轮询在慢 Redis 下有时效排队；非 park 每张卡每次多 RTT

- **确定性：代码证实调用形状；时效下降需慢 Redis/并发条件。**
- **证据：**
  - 每个运行轮独立 1Hz `cancelState`，专用调度器只有两条线程：`StreamWatchdog.java:156-159`、`StreamWatchdog.java:279-289`、`AiExecutorConfig.java:168-176`。
  - 每次取消读是同步 GET，超时配置 2s；异常后才有全局 5s 退避：`CancellationRegistry.java:381-424`。慢而未失败的 GET 不会触发退避。
  - 非 park 等待每 2s 先 cancel GET，再通过 `pendingLenient` 分别读 pending、req、decision：`ConfirmGate.java:975-1005`、`RunStore.java:3415-3431`。
- **触发条件：**大量运行轮、每条 GET 接近 2s、跨实例取消；或非 park 多等待线程。fixed-rate 同一任务不重叠，但两条工作线程可形成全局排队。
- **影响：**“跨实例取消至多一拍”只是正常 Redis 下的目标，不是慢 Redis 下的硬上界；运行池/HTTP 线程也承担同步等待。UNKNOWN 不会被误当未取消，这是现有正确性保护。
- **根因：**每轮独立高频同步轮询，且 pending 三把同槽键没有合并读取；退避只按失败而不是延迟预算触发。
- **最佳修复：**保持 Redis 为权威，增加跨实例取消 Pub/Sub 作为加速、轮询降为兜底；非 park 将 pending/req/decision 以同槽 Lua 或按槽 pipeline 一次读回，cancel 单独读。为轮询设置每轮在飞限制与基于耗时的自适应节拍，而不是仅靠异常退避。
- **备选与取舍：**仅扩大 `aiCancelPoll` 可提高吞吐但增加 Redis 压力，不解决每轮 RTT；单纯降低频率会牺牲取消时效。Pub/Sub 丢失时仍须保留权威轮询。
- **涉及修改点/影响面：**`StreamWatchdog`、`CancellationRegistry`、`ConfirmGate`、`RunStore.pendingLenient`、`AiExecutorConfig`。
- **后续验证（仅方案）：**20/100 运行轮下注入 100ms/2s GET，测跨实例取消延迟与调度排队；park 开关两种模式都验证 UNKNOWN 不执行不可逆动作、决策不丢。

## 六、轮询、TTL 与清理清单

| 路径 | 当前节拍/边界 | 阻塞与清理结论 |
|---|---|---|
| 流式跨实例取消 | 每轮 1Hz，2 条专用线程 | Redis GET；本地 listener 只加速同实例；慢查询排队见 R2-10 |
| 非 park 确认/前端等待 | 每轮 2s；即时前端工具有 120s 上限，确认/表单无上限 | 同步 cancel + 三份条目读；租约 10s、资源约 60s 续期；见 R2-10 |
| park 模式 | 生产开启，无等待线程 | 依赖决策/重挂读侧 touch、到期定时器与运行期扫描；不存在隐藏的原等待线程心跳 |
| run 租约 | TTL 30s、续租 10s、存活判据约每 3 拍 | fixed-rate 2~4 线程；LOST/UNAVAILABLE 有区分，但 release=0 缺口见 R2-02 |
| 恢复扫描 | 启动一次 + 运行期默认 120s；单线程 fixed-delay | 全索引发现发生在动作预算之前，见 R2-06 |
| 会话销毁活跃等待 | 设计窗口 1.5s、50ms 轮询 | 不是接口 deadline；单轮 N 次 GET 可越过窗口，见 R2-01 |
| 会话销毁自动重试 | 30s；每轮最多 10 个候选、每台账有界尝试 | durable pending 无 TTL；全局锁 300s 且有续租/停滞判据；能兜底 deferred，但会重复重链成本 |
| 取消交棒重试 | 2s 指数至 30s；租约占用 35s；有界 | 去重/计数在成功、放弃、停机清理；重试线程阻塞见 R2-09 |
| 全局 `ai:runs` | 无整键 TTL，惰性删死成员 | 避免活跃发现面整键过期；代价是 SMEMBERS+N×EXISTS |
| run 键族 | session TTL 滑动；挂起读/写续期 | 快照/runstate/pending/req/decision/ledger/idem/seq/stream/fence/epoch 有续期面；claim 随写续期 |
| client-request | 整 HASH 滑动 TTL；每批最多检查 256、删 32，游标持久化 | DONE 有界回收正确；CANCELLED/RUN 残留见 R2-08 |
| RunRegistry | `of()` 上摊还，默认每分钟扫、30 分钟空闲回收 | 有订阅、正在 emit、terminating 均不回收；未发现无界 idle emitter 必漏清的路径 |
| RedisFrameStore 进程内簿记 | 终态 forget + 60s 节流到期/超 4096 清理 | 有界；身份段进 bookkeeping key |

## 七、未发现问题的关键路径与既有保护

1. **核心 13 键同槽成立。**快照 fence、台账+幂等索引、run 租约+epoch+fence、13 键 DEL 都使用同一身份化 tag；未发现这些脚本在现有键形下必然 CROSSSLOT。
2. **租约取号与占租同序。**acquire 三键 Lua 会与 fence 对账，计数器先过期/被淘汰时可抬到 fence+1；续租和 Redis 侧释放均按完整 token 比较，避免直接 DEL 别人的键。
3. **快照状态有取消优先守卫。**非 CANCELLED 状态的 plain/fenced 写都会先读 runstate，缺失才回退快照；CANCELLED 不被 RUNNING/SUSPENDED/DONE/FAILED 翻案。取消自身的 plain 两键写不是单 Lua，但采用“状态先行+失败值匹配回滚”的保守方向。
4. **事件取号与归档同脚本。**`AI_FRAME_APPEND` 将 seq 分配、显式 ID XADD、xack、xtrim/TTL 放在同槽脚本；归档失败不交付业务帧，避免实时游标越过不存在于归档的帧。
5. **决策正常路径有全集群首写者。**HSETNX decided 能防两个实例都作为正常决策写者；问题是其崩溃恢复不完整，而不是 HSETNX 本身无效。
6. **工具执行有 claim 与 ledger/idem 两层防重。**claim 在副作用前 HSETNX；ledger+idem 同 Lua；idem 命中错位或读不到会回扫台账，不把“索引缺失”直接当未执行。
7. **请求身份传播覆盖目标异步链。**运行池/触发池有 TTL decorator，watchdog/cancel 调度器有 TTL 包装，LeaseService、ResumeService、StartupResumeRunner、CancelledRunFinalizer 又按记录身份显式开作用域；locale 在续跑驱动线程显式 set/reset。
8. **销毁发现面有 fail-closed 与防误删。**session-runs 读取失败会中止；快照身份/session 复核、上下文 TYPE 检查、归档 glob 转义与数字后缀、Cluster 逐 master SCAN 均存在；发现键只在主体无错误且无 deferred 时最后删除。
9. **销毁不是伪分布式事务。**H2 独立事务、Redis 顺序、pending/guard/due 的补偿职责写得清楚；本报告未把“跨 Redis/H2 非原子”误报成可实现而未实现的事务承诺。
10. **取消 UNKNOWN 多数副作用入口是 fail-closed。**续跑入口和工具认领前不会因读取失败而当作“未取消”；看门狗 UNKNOWN 只继续计时，不伪造取消。
11. **无危险的跨 Cluster 通配 DEL/KEYS/FLUSHDB。**销毁所需扫描走逐 master SCAN，run 13 键为精确同槽 DEL，无 tag 锚点逐键 DEL。
12. **本地终态竞争有明确监视器。**成功、失败、取消共用按身份+runId 条带化的 monitor；但源码也明确没有声称跨实例终帧恰一次，本报告未将其当作已经解决。

## 八、建议修复顺序与总验证计划

### 8.1 顺序

1. 先修 **R2-02、R2-03、R2-04**：它们直接关系 fencing、决策可恢复性和真实工具副作用；可共用同槽条件写基础设施，但应分别保留故障语义。
2. 再修 **R2-01、R2-05**：先给删除链接总预算/异步化，随后补齐全部键面与复核。
3. 随后处理 **R2-06、R2-09、R2-10** 的 RTT/线程模型，最后做 **R2-07、R2-08** 的长期容量治理；后两者不能以删除安全墓碑换取表面有界。

### 8.2 后续验证矩阵（只写方案，本次未执行）

- **拓扑：**standalone、单实例多线程、多实例共享 standalone Redis、3 主 Cluster；覆盖 MOVED/ASK、单 master 超时、回包丢失、连接中断。
- **交叉时序：**新轮↔销毁、挂起↔批准/回灌、批准↔停止、claim↔停止、终态↔释放/接管、删除↔自动 retry、刷新↔过期收敛。
- **故障点：**session-runs 登记后、首快照前后、decision guard 后、pending 前后、claim 后、工具返回后、ledger 前后、CANCELLED 快照后、终帧前后、pending purge CAS 与 due ZREM 之间。
- **一致性断言：**记录身份不串；取消不被翻案；旧 epoch 不写；已批准/回灌可续跑；已认领副作用不被写成未执行；终帧与快照可对账；删除完成后所有约定前缀无残留。
- **性能断言：**记录每次 HTTP 的总 deadline、每个阶段命令数/RTT/字节数、扫描节点数、调度队列等待、线程池占用、Redis slowlog/脚本耗时；不仅测空会话和正常 Redis。
- **静态 Cluster 校验：**对每个 Lua 的 KEYS 计算同一 hash tag；对多键 DEL 验证客户端未被静默拆槽；对无 tag cancel/activity/beat/observed 继续只允许单键命令或按槽 pipeline。

## 九、限制与未决事实

- 本报告没有运行任何服务或压测，因此不断言某个具体会话在当前环境“实测超过 30 秒”；R2-01 的结论是**代码不存在保证 30 秒的机制，且存在多条可超过该值的同步路径**。
- 没有读取或报告任何密钥、连接凭据或本机绝对路径；配置中的环境变量名不是密钥内容。
- `park.enabled=true` 是默认生产配置；若部署覆盖为 false，非 park 的 2s 轮询与线程驻留会成为主要成本，不能与 park 路径混为一谈。
- 现有注释中的“已修复/已验证”只作为设计意图参考；本报告结论均以实际代码控制流为准。
