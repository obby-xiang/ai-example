# 17 — 删除对话超过 30 秒：独立根因与修复终审

- 复核基线：`52b09364`。
- 复核范围：首轮报告 `01`、`02`、`03`、`04`、`07`、`09`、`10`、`13`、`15` 的删除相关结论，以及控制器、销毁服务、pending/guard/retry、run 删除、取消收尾、帧归档、记忆、transcript、附件、文件、前端和 Redis 配置的实际源码。
- 工作方式：只读静态复核；未运行测试、构建、CI、服务或压测，未修改生产/测试代码，未执行 Git 写操作。本文件是唯一写入物。
- 证据分级：**代码事实**是源码直接证明的调用、命令、顺序、配置或键面；**静态推导**是由这些事实得到的复杂度、并发或耗时风险；**待实测**是事故请求的实际耗时占比、真实数据规模、DB 执行计划、文件系统与 Redis 节点表现。下文不把“代码允许超过 30 秒”写成“已证明某次线上请求因某一阶段超过 30 秒”。

## 一、终审结论

1. **最明确的结构性慢因是同步请求中的两趟完整键空间 SCAN，而不是某一条 13 键 DEL。** 每次首次 DELETE 和普通自动重试都会先扫 staging，再扫 archive；两者都 `COUNT 256`、`maxKeys=0`，Cluster 下逐 master、逐游标串行 drain。即使本会话没有匹配键也要支付整个键空间的检视成本。代码证据：`backend/src/main/java/com/example/configmgr/ai/session/AiSessionPurgeService.java:631-665,778-828`，`backend/src/main/java/com/example/configmgr/ai/session/RedisClusterScanSupport.java:55-80,83-147`。
2. **超过 30 秒不是单根因问题。** 无接口级 deadline 的同步编排，把两趟 SCAN、逐 run 大快照/HASH 读、DB、活跃轮完整取消收尾、逐 archive `TYPE+DEL`、附件文件 I/O 和 pending 收尾串在同一请求线程。2 秒只是单命令/连接配置，不是接口上界；1.5 秒也只覆盖取消之后的局部轮询。证据：`backend/src/main/java/com/example/configmgr/ai/web/AiSessionPurgeController.java:71-103`，`backend/src/main/java/com/example/configmgr/ai/session/AiSessionPurgeService.java:350-540,845-980`，`backend/src/main/resources/application.yml:74-84`，`backend/src/main/resources/application-cluster.yml:20-36`。
3. **活跃轮有独立的第二慢链。** 在装配生产 `ResumeService` 时，每个活跃 run 在 `awaitTerminal` 之前同步调用 `cancelConfirmed`；走完 `cancelParked` 的路径最多做三次终帧归档探测，每次是最多 6256 帧的正向 Stream 分页，不是 Redis 键空间 SCAN。证据：`backend/src/main/java/com/example/configmgr/ai/run/ResumeService.java:108-109,2323-2406`，`backend/src/main/java/com/example/configmgr/ai/run/ResilientChatService.java:3330-3341,3389-3521`，`backend/src/main/java/com/example/configmgr/ai/run/SseChatEmitter.java:1629-1640,2323-2408`。
4. **同会话没有 HTTP 与 runner 共用的执行单飞。** pending 的 `purgeId` CAS 保护台账归属，不阻止两个执行体同时跑同一套 SCAN、DB、文件和 Redis 删除；runner 的全局锁只保护自动轮次。代码事实：`backend/src/main/java/com/example/configmgr/ai/session/AiSessionPurgeService.java:350-458`，`backend/src/main/java/com/example/configmgr/ai/session/SessionPurgePendingStore.java:279-345`，`backend/src/main/java/com/example/configmgr/ai/session/SessionPurgeRetryRunner.java:198-327`。
5. **首轮报告 01 关于“frame/lease 没有遗漏”的结论错误。** 13 键确实包含 `xevents`、run lease 和 epoch，但不含 `xtrim`，也不含动态 token 的 `xack`；02、04、15 对这一点的结论正确。代码事实：`backend/src/main/java/com/example/configmgr/ai/run/RunStore.java:2792-2810,2886-2934`，`backend/src/main/java/com/example/configmgr/ai/run/RedisFrameStore.java:217-235,592-603,888-917`。
6. **直接把控件改成 202/异步不是充分修复。** 若 worker 仍重复两趟全量 SCAN、三次归档探测和无 checkpoint 的逐键清理，只是把 30 秒搬到后台；若只提前返回而回滚新 pending、未装 guard、未建立跨实例执行租约，还会破坏旧会话清理与防写回。推荐目标是“**持久化受理 + 会话级有租约/epoch 的状态机 + 精确索引 + 有界分批执行 + 存量对账**”，异步返回只是其中最外层的契约变化。

**根因排序建议：**大键空间场景先测两趟键空间 SCAN；有活跃 run 时单列 `cancelConfirmed`；多 run/archive/附件场景测串行点操作和文件 I/O；故障场景测 2 秒命令等待的累加；重复 DELETE/runner 交错场景测重复执行。事故中谁占主导仍待分阶段计时，不能仅凭静态审查指定唯一实测根因。

## 二、唯一完整时序：从前端动作到服务端返回及后台接续

以下是基线的规范时序。分支均标出触发条件，不把不同报告中的可选路径拼成“每次都会发生”。

### 2.1 请求发出前的前端时序

```text
点击新建对话
  → 捕获 oldSessionId / oldRunId / purgeIdentity
  → adoptFreshSession()：同步换号、清屏、隔离旧传输，不等 DELETE
  → await ensureSession()：先准备新会话
  → 有 oldRunId 时 await cancel(oldRunId，显式旧身份)
  → await deleteHistory(oldSessionId)，但该 DELETE 未显式传旧身份
  → 成功或失败都清本地旧会话残留；不把 complete/deferred/errors 展示成独立进度
```

代码事实：`frontend/src/stores/ai.ts:11253-11305`，`frontend/src/api/ai.ts:297-306`。普通 Axios 单请求上限为 60 秒，但这条“发号 → cancel → DELETE”链没有共享 60 秒预算；请求超时也不证明服务端工作停止，见 `frontend/src/api/http.ts:116-145,157-175,210-214`。

因此，前端不是 Redis 慢命令的根因，却有三个放大因素：DELETE 被新发号和重复 cancel 推迟；拦截器执行时现读身份导致旧会话 DELETE 可能带新身份；reset 没有同旧会话销毁单飞。它们影响请求何时发出、发给谁、发几次，不应被写进后端单次 DELETE 的命令数。

### 2.2 后端首次 DELETE 的完整主时序与命令清单

记号：`R`=候选 run 数，`A`=archive 去重目标数，`S`=staging 数，`F`=附件数，`M`=Cluster master 数，`P_m`=某 master 的实际游标页数。除明确写出的 13 键 DEL、同槽 Lua 和已有附件 pipeline 外，主销毁链没有并行或 pipeline。

| 顺序 | 实际动作 | Redis / DB / 文件与等待 | 完成与清理语义 |
|---|---|---|---|
| 1 | 身份过滤、Controller 参数与前置检查 | 一次同步 `PING`；健康正缓存为 0，真实探针在实例级 `synchronized` 内，锁等待不计入 Redis 命令时间。证据：`backend/src/main/java/com/example/configmgr/ai/web/AiSessionPurgeController.java:71-90`，`backend/src/main/java/com/example/configmgr/ai/config/RedisAvailability.java:38-53,72-100` | PING 成功不证明所有 master/槽可用；失败才直接 503 |
| 2 | 固定身份，登记/复用 durable pending | 新建通常 `ZADD due → EVAL REGISTER_IF_ABSENT → ZADD due`；复用另有 pending CAS 重置 `nextAt/verifyAt`，耗尽时 attempts 归零。证据：`backend/src/main/java/com/example/configmgr/ai/session/AiSessionPurgeService.java:341-373`，`backend/src/main/java/com/example/configmgr/ai/session/SessionPurgePendingStore.java:279-345` | pending 无 TTL，且 guard 判定也读 pending；但这不是执行锁 |
| 3 | 枚举 run 发现面 | gate 本地命中则零 Redis，否则 `GET` gate；`GET sessionCurrent`；`SMEMBERS sessionRuns`。证据：`backend/src/main/java/com/example/configmgr/ai/session/AiSessionPurgeService.java:566-592`，`backend/src/main/java/com/example/configmgr/ai/session/SessionGate.java:494-505`，`backend/src/main/java/com/example/configmgr/ai/run/RunStore.java:2191-2202,2426-2437` | `sessionRuns` 失败会中止；gate/current 读失败却折成 null，可能把“不可知”误作“不存在” |
| 4 | 逐候选 run 枚举 | 每 run 一次完整快照 `GET + JSON 解析`，再 `HGETALL pending/req/decided`，合计常见 4 次/run；req/decided 的 lenient 失败可折空。证据：`AiSessionPurgeService.java:594-629`，`backend/src/main/java/com/example/configmgr/ai/run/RunStore.java:2472-2533,3363-3397,3495-3519,3600-3609` | 归属/session 复核保留；死索引成员通常跳过，gate/current 点名的空快照按 UNKNOWN 活跃处理 |
| 5 | **第一趟键空间 SCAN：staging** | `SCAN MATCH <本会话 staging 前缀> COUNT 256`，所有匹配物化；standalone 全游标，Cluster 逐 master 串行，`maxKeys=0`。证据：`AiSessionPurgeService.java:631-640,817-828`，`RedisClusterScanSupport.java:83-147` | 结果供本次删除复用；任一节点失败则枚举中止，尚未删业务数据 |
| 6 | 记忆与 transcript 枚举 | final memory `EXISTS`；transcript data/bytes 各 `EXISTS`，data 存在再 `LLEN`。证据：`AiSessionPurgeService.java:639-658`，`backend/src/main/java/com/example/configmgr/ai/memory/RedisChatMemoryRepository.java:453-469` | 只读精确键；staging 也算 found |
| 7 | **第二趟键空间 SCAN：archive** | 先 `EXISTS ctx`、`EXISTS ctxLedger`；再 archive `SCAN MATCH ... COUNT 256` 全游标；随后 `HGET ctx turnNo` 并补 `0..turnNo` 精确键，最多 2001 个；最后 safety/events 各 `EXISTS`。证据：`AiSessionPurgeService.java:660-665,731-795` | SCAN 与 turnNo 推导取并集；turnNo 读失败只放弃推导，SCAN 失败仍整次中止 |
| 8 | 其余会话、附件与 DB 枚举 | `EXISTS sessionCurrent/sessionRecord/ctdraft`；附件 `SMEMBERS sessionIndex + GET × F`；DB `SELECT COUNT(*) WHERE session_id+tenant_id+user_id`。证据：`AiSessionPurgeService.java:667-679`，`backend/src/main/java/com/example/configmgr/ai/form/RedisFormAttachmentStore.java:262-306`，`backend/src/main/java/com/example/configmgr/ai/session/SessionPurgeDatabase.java:88-113` | 附件登记再核 sessionId；枚举失败不进入删除 |
| 9 | 安装 guard | 单键 `SET PX`，默认 TTL 24h。证据：`AiSessionPurgeService.java:389-400`，`backend/src/main/java/com/example/configmgr/ai/session/SessionPurgeGuard.java:110-114` | 失败时本次新建 pending 会尝试回滚；复用旧 pending 则保留 |
| 10 | 先删反馈 DB | `REQUIRES_NEW` 本地事务中的 `DELETE FROM ai_feedback ...`；无 Redis 分布式事务。证据：`SessionPurgeDatabase.java:57-65,98-113` | DB 失败则不进入 Redis 删除；新 pending/guard 的回滚只限本次新建 |
| 11 | 逐活跃 run 写取消事实 | 每 run 先查本地/Redis 取消态，未取消则 `SET ai:cancel ... PX`；随后在生产装配下同步进入 `cancelConfirmed`。证据：`AiSessionPurgeService.java:845-902`，`backend/src/main/java/com/example/configmgr/ai/run/CancellationRegistry.java:190-231,381-449` | 已确定 CANCELLED 不重复 SET；写失败只记 warning，本轮不会伪称该 run 已终态 |
| 12 | 活跃 run 同步取消收尾，见 2.3 | 快照、取消键、Stream 终帧探测、session gate、run lease/fence/epoch、pending、快照写、transcript/记忆和出帧均可能同步发生 | 这一阶段在 1.5 秒等待之前；无独立总 deadline |
| 13 | `awaitTerminal` | 所有活跃 run 共享名义 1500ms，未终态者每 50ms 一轮完整快照 `GET`；`deadline` 只在 while 顶检查，单轮 I/O 可越过窗口。证据：`AiSessionPurgeService.java:139-151,904-948` | 已读终态/消失者退出后续轮询；中断恢复标志并返回；未终态留待 deferred |
| 14 | 刷新全部候选状态 | 每 run 再完整 `GET + JSON 解析`；终态快照存入 `settledSnapshots`。证据：`AiSessionPurgeService.java:950-981` | 服务层终态路径随后复用此快照，不再额外做一次 service 级复读；读失败按活跃保守处理 |
| 15 | 删除记忆 | 三键同槽 `MEMORY_CLEAR` Lua：`DEL final + INCR gen + INCR ver + PEXPIRE ver/gen`；每 staging `DEL`，实际删成功者再 `EXISTS`；最后 final 再 `EXISTS`。证据：`AiSessionPurgeService.java:995-1041,1118-1177`，`backend/src/main/java/com/example/configmgr/ai/memory/RedisChatMemoryRepository.java:223-240,905-910` | gen/ver 是有意保留的防复活 tombstone，不是漏删；残留或复核不可知都使 complete=false |
| 16 | 删除 transcript | data/bytes/ready/truncated 各 `TYPE`，类型匹配再 `DEL`，最多 8 次串行往返。证据：`AiSessionPurgeService.java:1289-1307,1334-1363` | 类型校验防止键名前缀碰撞误删 |
| 17 | 逐 run 删除或 deferred | 活跃 run 在 service 层再读快照；终态可复用步骤 14 的快照。`RunStore.deleteRun` 仍做删除前快照读；本进程持租时先条件 release；随后**一次同槽 13 键 DEL**，再 `SREM ai:runs`，有快照时 `ZREM sessionActive + SREM sessionRuns`；最后 beat/activity/cancel/observed 各一次单键 `DEL`。证据：`AiSessionPurgeService.java:1191-1268`，`backend/src/main/java/com/example/configmgr/ai/run/RunStore.java:2843-2934,2966-2976` | 活跃 run 不删 13 键；13 键不是 13 次 RTT。索引清理是 best-effort；观测键不同槽所以逐键删 |
| 18 | 删除上下文 | ctx/ledger/safety/events 和 A 个 archive 各 `TYPE`，匹配再 `DEL`，最多 `2×(4+A)` 次串行往返。证据：`AiSessionPurgeService.java:1309-1363` | 即使推导的 archive 不存在也支付 TYPE；失败逐键收集并继续，complete=false |
| 19 | 删除会话记录、draft、clientRequest | session record `GET` 后按 JSON 形态决定 `DEL`；ctdraft 与 creq 各 `TYPE + 可选 DEL`。证据：`AiSessionPurgeService.java:1365-1401` | 不删 `ai:lock:*`；不把协调锁当业务数据 |
| 20 | 逐附件删文件与登记 | 每件：`resolve`（内含 `Files.createDirectories`）→ `Files.exists` → `fileStorage.delete`（再次 resolve/createDirectories + `Files.deleteIfExists`）→ `Files.exists`；确认文件不在后 `GET registration + DEL registration + DEL consumed + SREM sessionIndex + SREM formIndex`。证据：`AiSessionPurgeService.java:1439-1494`，`backend/src/main/java/com/example/configmgr/file/FileStorageService.java:68-75,108-131,182-188`，`RedisFormAttachmentStore.java:565-593` | 文件未确认删除则保留登记，供重试找到路径；不能先删登记再慢慢删文件。文件 I/O 不受 Redis 2 秒配置约束 |
| 21 | 仅主体无 errors/deferred 时删发现键 | current `GET + 可选 DEL`；sessionRuns/sessionActive 各 `TYPE + 可选 DEL`。证据：`AiSessionPurgeService.java:488-494,1404-1437` | 有任何失败/延后都保留发现键，避免重试失去入口 |
| 22 | 收尾并同步返回 200 | 成功：guard owner-CAS Lua；pending `GET + CAS_SET + ZADD`，排到 60 秒后记忆复核。失败：pending `GET + CAS_SET + ZADD` 退避。证据：`AiSessionPurgeService.java:495-540`，`SessionPurgePendingStore.java:480-530` | `complete=true` 表示本次主体完成，不表示 pending/guard/gen/ver 立即消失，也不表示 60 秒复核已做 |

快照读数应准确表述：普通终态 run 常见为“枚举一次 + 刷新一次 + `RunStore.readForDelete` 一次”，共约三次完整读取/解析；服务层已经复用刷新后的终态快照。活跃或读失败分支还会有轮询和 `deleteOrDefer` 的额外读取。不能再把已消除的那次 service 级重复读计入新方案收益，也不能为省 RTT 删除 `RunStore` 的独立归属复核。

### 2.3 活跃 run 的完整取消子链

```text
cancelConfirmed(runId)
  → 本进程 resuming 单飞；同进程已有执行体则 IN_PROGRESS
  → GET 快照并按快照身份开作用域；DONE/FAILED 不改写
  → GET 取消事实；UNKNOWN/未确认则返回，不驱动模型/工具
  → 终帧归档探测 #1；已存在则 ALREADY_TERMINAL
  → SessionGate 单键 acquire Lua；其它 run 持门则 SESSION_BUSY
  → run lease + epoch + fence 三键同槽 acquire Lua
  → verifyHeld 单键 Lua
  → cancelParked
      → GET 快照、反序列化 messageJson、重建 TurnContext
      → cancelTerminalLocked
          → 再 GET 快照
          → 终帧归档探测 #2
          → cancelPendings：读 pending/req/decided；按条目读 claim、HSET pending + EXPIRE
          → 再 pendingsLenient：HGETALL pending/req/decided
          → 写 CANCELLED 快照/runstate，并联动 current/sessionRuns/ai:runs/租约
          → 成功持久化后写 transcript、收敛记忆
          → 逐取消条目补裁决帧、park 结算、textEnd/done 等帧归档与 wake
          → 本地清理与 clientRequest 条件完成
  → 终帧归档探测 #3
  → 必要时再 GET 快照判断 DONE/FAILED
  → finally：按 token 释放 session gate；只释放本次新建 run lease
```

主证据：`backend/src/main/java/com/example/configmgr/ai/run/ResumeService.java:2323-2430`，`backend/src/main/java/com/example/configmgr/ai/run/ResilientChatService.java:3330-3362,3389-3521`，`backend/src/main/java/com/example/configmgr/ai/run/RunStore.java:1391-1508,4274-4342`。具体命令数随 pending、工具、帧、租约本地状态和写权限分支变化，不能给每次请求一个固定常数。

三次终帧探测是**最多**而非必然：第一次命中、会话门/租约提前返回、异常或收尾中途退出都会减少次数。每次探测先以 `XREVRANGE` 取末号，再从 `lastSeq-6256` 起用 `XRANGE ... COUNT <=256` 正向读，命中即停；证明不存在时可能读满约 25 页并再探末号。证据：`backend/src/main/java/com/example/configmgr/ai/run/SseChatEmitter.java:2323-2421`，`backend/src/main/java/com/example/configmgr/ai/run/RedisFrameStore.java:1344-1379,1538-1585`。这里应称“Stream 分页/归档扫描”，不能与请求枚举阶段的键空间 SCAN 混算。

另有一项代码证实的正确性缺口：`terminalScanStart` 把“物理帧数预算”直接转成“seq 差值”，而 `FrameStore` 明确允许跳号；大跳号时可能漏掉幸存目标帧。证据：`SseChatEmitter.java:2411-2421`，`backend/src/main/java/com/example/configmgr/ai/run/FrameStore.java:24-38`。它不是已证实的 30 秒主因，但任何“用终帧探测做幂等”的改造都必须同时修正，不能把错误探测结果固化为终帧事实。

### 2.4 自动重试与 60 秒复核是另一条时间线

每实例在 ready 后建立单线程 `ai-session-purge-retry`，10 秒首延、30 秒 fixed-delay；每轮抢全局 `ai:purge:retry:lock`（300 秒、续租），`ZRANGEBYSCORE due LIMIT 0 10`，再逐 pending `GET` 并按记录恢复身份。普通 pending 调 `retry`，会重新完整枚举，因而再次执行两趟键空间 SCAN、DB COUNT/DELETE 和删除主体；不是从上次失败键的 checkpoint 续跑。证据：`backend/src/main/java/com/example/configmgr/ai/session/SessionPurgeRetryRunner.java:154-175,198-327`，`backend/src/main/java/com/example/configmgr/ai/session/AiSessionPurgeService.java:429-458`。

主体完成后 60 秒的 `verifyMemoryOnly` 只做 pending/guard 检查、记忆清理和 staging 复核，但因没有前置枚举，会**再做一趟 staging 全键空间 SCAN**；成功后 pending 才经 `GET + CAS_DEL + ZREM + 重读/必要补回` 删除。证据：`AiSessionPurgeService.java:1072-1106,1136-1177`，`SessionPurgePendingStore.java:711-774`。

失败退避从 10 秒指数到 300 秒，普通阶段和复核阶段各最多 10 次自动尝试；耗尽后保留 durable pending/guard、摘除 due 成员，手工 DELETE 可重开。故“有 runner”不等于永久故障下必然最终收敛，必须有耗尽告警和人工/后续重开闭环。runner 的 5 分钟候选 stall 判据会放全局锁，但正在执行的旧调用不停止；没有记录级 epoch 时，新旧执行体仍可交叠。证据：`SessionPurgeRetryRunner.java:253-379`，`SessionPurgePendingStore.java:502-531,576-709`。

## 三、SCAN、等待与清理的独立总账

### 3.1 所有相关 SCAN/流读取，禁止混名

| 操作 | 范围与上界 | 串并行与失败姿态 | 是否计入原 HTTP |
|---|---|---|---|
| staging 键空间 SCAN | 当前 DB/所有 master 的完整键空间；`COUNT 256` 只是提示；无结果数、页数或时间上限 | master 与游标均串行；任一失败上抛 | 是，首次和普通 retry 都有 |
| archive 键空间 SCAN | 同上；另与 turnNo 推导并集 | 同上 | 是 |
| 活跃 run 终帧探测 | Stream 物理帧分页，最多 6256 帧/次；每次 cancelConfirmed 最多 3 次 | 单 run 串行，多 run 也串行；有帧数预算但无时间预算 | 仅活跃且进入相应分支时计入 |
| 60 秒记忆复核 staging SCAN | 再次完整键空间 SCAN | runner 单线程；失败保留复核 pending | 不计入原 HTTP，但计入后台 Cluster 负载 |
| due 发现 | `ZRANGEBYSCORE ... LIMIT 10` + 每成员 GET | 有界候选；不是 SCAN | 后台 |
| 全局 `ai:runs` | DELETE 枚举**不做** `SMEMBERS ai:runs`；只在 run 删除后 `SREM` 对应成员 | 无全库恢复扫描混入本请求 | 否 |

两趟键空间扫描的静态成本模型为：

```text
T_scan = T_connection/topology + Σmaster Σcursor_page(T_network + T_redis_page + T_decode) + T_close
T_DELETE ⊃ T_scan(staging) + T_scan(archive)
```

`COUNT=256` 不能保证页大小，不能用“总键数÷256”伪装成精确命令数。提高 COUNT、只扫某一 master、并行 master 或把 SCAN 放到后台，都不会自动把复杂度改为 O(本会话键数)。

### 3.2 等待与预算清单

- **Redis 单命令/连接：**配置均为 2 秒；不是总接口 deadline。`application.yml:74-84`、`application-cluster.yml:20-36`。
- **PING 锁：**健康时每 DELETE 真发一次，实例 monitor 可让多请求排队；属于可能的固定前置/竞争放大，不是全扫描的替代解释。`RedisAvailability.java:38-53,72-100`。
- **活跃轮等待：**1500ms 是共享循环窗口，50ms 是 sleep 间隔；不包含此前的 `cancelConfirmed`，也不硬截断圈内同步 GET。`AiSessionPurgeService.java:911-948`。
- **归档探测：**有 6256 帧预算，无墙钟预算；每页同步 RTT 和 JSON 解析仍累加。`SseChatEmitter.java:2323-2408`。
- **DB：**枚举 COUNT 与删除各一次同步 JDBC；是否存在慢查询/锁等待待实测，不能由 SQL 文本直接声称全表扫描。
- **文件：**逐件同步 exists/delete/exists 和目录创建；当前销毁阶段没有总 deadline，慢共享盘只能后续实测。
- **客户端：**Axios 60 秒只释放调用方等待，不取消服务端完整销毁，不覆盖“发号+cancel+DELETE”整链。
- **后台：**30 秒 fixed-delay、每轮 10 候选、每记录有界退避；全局锁/单拍候选上限同时限制吞吐，不能用加实例推定清理更快。

### 3.3 清理键面与有意保留项

| 数据面 | 当前实际清理 | 终审判断 |
|---|---|---|
| run 主体 | 一次同槽 DEL：run/runstate/pending/claim/ledger/idem/seq/xevents/runLease/fence/req/decided/leaseEpoch | 13 键清单本身完整且 Cluster 合法 |
| run 观测 | beat/activity/cancel/observed 四个单键 DEL | 正确避免跨槽多键 DEL |
| 帧附加键 | **xtrim 未删；动态 token xack 未删** | xtrim 可留至会话 TTL，xack 靠 120 秒 TTL；不能宣称所有帧关联键即时清净 |
| 记忆 | final 删除，staging 删除并复核；gen/ver 推进并续 TTL | gen/ver 是有意 tombstone；60 秒复核仍有后台义务 |
| transcript | data/bytes/ready/truncated 类型校验后删除 | 四键同槽，但当前实现逐键 TYPE+DEL |
| 上下文 | ctx/ledger/archive/safety/events 类型校验后删除 | archive 发现依赖全键空间 SCAN + turnNo 补充 |
| 会话与请求 | session record、ctdraft、creq 删除；发现键最后删 | errors/deferred 时保留发现键是必要保护 |
| 附件 | 文件确认删除后删登记、consumed 和两个索引成员 | 顺序正确；文件与 Redis 非原子，靠保留登记重试 |
| DB | 当前身份当前 session 的 ai_feedback 行 | 独立本地事务，不与 Redis 假装同事务 |
| pending/guard | 失败/ deferred 保留；主体成功后 pending 等 60 秒复核，guard 保留 TTL | 属于正确性状态，不应当作垃圾一键清除 |
| 浏览器 | 响应或失败兜底清旧镜像/草稿/工具台账 | 服务端无法直接清 sessionStorage；202 后必须区分“受理”与“完成” |

## 四、首轮报告的重复、矛盾与最终裁定

### 4.1 重复结论不代表多份独立运行证据

01/SDC-01、02/R2-01、03/MT-04、09/P-01、10/API-BLK-001、15/RC15-01 都在描述同一个“两趟无界 SCAN + 同步无总预算”机制；02/04 还共同覆盖活跃取消链；07/MR-07、10、15 都覆盖 HTTP/runner 无记录级单飞。这些结论经源码相互印证，但都是静态代码证据，不能累加成“多次实测超过 30 秒”。

### 4.2 需要明确纠正或收窄的结论

1. **“13 键已覆盖 frame 全部键”错误。** 01 第 2.4/第九节的表述过宽；02/R2-05、04/FR-10、15/RC15-08 正确。`xevents` 已覆盖，`xtrim/xack` 未覆盖。这是清理完整性问题，不应误写成 13 键 DEL 跨槽或它是主要慢源。
2. **“每次删除只有 1.5 秒等待”错误；各报告主体其实大多已正确收窄。** 终审查实 `cancelConfirmed` 在 `awaitTerminal` 之前，且后续还有完整删除主体。1500ms 只是循环顶窗口，不是硬实时上界。
3. **04 的“最多三次归档扫描”成立，但必须写全前提。** 它指一次走完收尾的 `cancelConfirmed` 的 Stream 终帧探测，不是键空间 SCAN，也不是每个 DELETE 固定三次。
4. **快照复用不能重复报成尚未实施。** 当前 `reloadRunStatuses` 已把终态快照交给 `deleteOrDefer`；剩余固定成本主要是枚举、刷新以及 `RunStore` 自有删除前读取。证据：`AiSessionPurgeService.java:958-980,1191-1227`。
5. **09 对 TurnKeepalive“无 Redis”的描述过窄，04 的活动戳结论更完整。** 心跳帧本身不归档，但 `SseChatEmitter` 在锁外可调用 `touchActivity`，见 `backend/src/main/java/com/example/configmgr/ai/run/SseChatEmitter.java:3517-3523`。这属于背景竞争面，不应计入单次 DELETE 的固定命令清单。
6. **15 的 Lettuce topology refresh 是配置缺口，不是已证实事故根因。** Cluster 配置确实未显式设置周期/自适应刷新；实际默认行为、故障时 stale topology 持续多久及其对本次请求的贡献待真实依赖与故障注入验证。不能把它排在两趟 SCAN 之前作为确定主因。
7. **DB 复合索引和 lazyfree 都只是候选优化。** 当前 SQL 与现有索引可由代码/SQL 证实，但实际执行计划、锁等待、DEL 释放成本未测；不得直接写成已发生全表扫描或大 DEL 必然阻塞 30 秒。SQL 证据：`backend/src/main/resources/db/migration/V11__ai_feedback_tenant_scope.sql:18-24`。
8. **gate/current 的 null 折叠是独立正确性问题。** 01/SDC-08 经源码成立：这两个读口把读失败折成 null，而 sessionRuns 才 fail-closed。性能改造应顺手改为严格读取，但它是“特定索引缺成员 + 读故障”下的漏删风险，不是主要耗时来源。证据：`SessionGate.java:494-505`，`RunStore.java:2426-2437`，`AiSessionPurgeService.java:566-592`。

## 五、根因证据等级：哪些已证实，哪些仅是可能

### 5.1 代码证实的根因机制

- 同步请求内的两趟全键空间 SCAN，Cluster 全 master 串行、游标完全 drain。
- 无整请求 deadline，单命令 2 秒与局部 1.5 秒不能约束总耗时。
- 逐 run/archive/staging/附件的同步串行命令，以及完整快照重复读取和 JSON 解析。
- 活跃 run 在请求线程执行完整取消收尾；走完时最多三次有帧数预算、无时间预算的终帧探测。
- HTTP 与 runner、不同实例上的 HTTP 可重复执行同一 purge；pending CAS 不是互斥。
- 首次请求、普通 retry 和 60 秒复核会持续制造全键空间扫描；runner 不是从对象级 checkpoint 续作。
- 当前前端旧身份未固定、reset 无同旧会话单飞，且 DELETE 被发号/cancel 串行推迟。
- xtrim/xack 未纳入立即清理；complete 的“全部无残留”口径需要修正。

### 5.2 静态推导，不能写成事故事实

- 大键空间、多 master、慢节点时，两趟 SCAN 很可能成为最大延迟来源。
- `turnNo=2000` 时最多 2001 个推导 archive，仅 TYPE 就有 2001 次串行命令；若全存在再同等数量 DEL。平均 RTT 较高时可单独超过 30 秒，但报告中的算式不是实测。
- 多条命令相继接近 2 秒可超过 30 秒；实际连接重试、重定向和异常提前返回会改变数值。
- 多实例重复 DELETE/retry 会线性放大 Redis、DB、文件和 Tomcat 线程压力。
- 大 Stream、多活跃 run、无终帧时，三次分页探测可能显著占用请求线程。

### 5.3 必须待实测或依赖源码/部署证据的事项

- 用户所遇请求的真实阶段耗时、每 master 页数、keyspace、R/A/S/F、快照字节和 JSON CPU。
- DB 是否全扫/锁等待、是否需要 `(tenant_id,user_id,session_id)` 索引。
- 共享文件系统是否缓慢、native I/O 是否可中断。
- Redis 服务端 lazyfree、实际 DEL 成本、MOVED/ASK/TRYAGAIN 频率与拓扑刷新行为。
- 后台恢复扫描、其他 purge、SSE/取消轮询是否在同一事故窗口形成可测竞争。
- 修复后的 HTTP 受理时间与**最终清理完成时间**；只测前者会制造虚假达标。

## 六、最小止血与根治方案的正确性评审

### 6.1 最小止血：可做，但每项都有边界

| 方案 | 评价 | 不得破坏的约束 |
|---|---|---|
| 同 pending 的执行单飞 | **优先做。** 直接消除重复 SCAN/DB/文件工作 | 必须是跨实例 per-session 租约，含唯一 owner token、TTL、续租、到期接管和 execution epoch；状态写同时校验 purgeId+epoch。后到 DELETE 返回同一 purgeId 的 inProgress/current 状态，不再跑主体 |
| 按 slot 有界 pipeline | **可做。** 降低点命令 RTT，不减少 SCAN 检视量 | 只放单键命令或明确同槽组；不能引入跨槽 MGET/DEL/Lua。必须保留逐命令结果/未知回包，pipeline 整体失败不能伪报单键成功 |
| 单键 `TYPE+DEL` Lua | **可做。** 一次往返且保留类型校验 | 每脚本仍只碰一把键；类型不符返回 skipped/warning，不得改成无条件 DEL |
| 有界并行 master SCAN | **仅临时墙钟止血。** 不降低 Redis 总工作，增加瞬时压力 | 固定小并发、共享请求预算、逐节点结果汇总；任一失败仍不得把部分结果当完整。禁止无界线程 |
| 提高 COUNT | **收益有限。** 只可能减少部分 RTT | 不宣称降低复杂度；观察单页 Redis 工作和响应体 |
| 活跃 run 快速 deferred | **可做且方向正确。** 已写取消事实而不立即终态时保留 pending | run 13 键、发现键、pending/guard 必须保留；由既有有界收尾和 retry 推进。仅把同一 `cancelConfirmed` 原样搬后台仍会重复归档扫描 |
| 整请求 deadline | **同步过渡必需。** 到期停止发新工作，返回 complete=false/inProgress | 到期不能删除新 pending；当前“枚举失败回滚本次新建 pending”逻辑必须改为明确的 durable 接续语义。同步 API 到期不能强杀已发出的命令或文件 I/O，不能承诺硬实时 |
| PING 单飞/短缓存 | **辅助优化。** 减少 monitor 排队 | 标注状态年龄，保留失败快速翻转；不能取代后续目标键 fail-closed，也不能当主修复 |
| 前端立即后台发起旧会话 DELETE | **可做。** 保留即时清屏，解除与新发号的串行 | 固定旧身份；同旧会话单飞；不得把超时/202 当已删除；本地清理与新会话隔离 |

**不推荐的最小改法：**把 `maxKeys` 截小后直接 complete；只调大 Redis timeout；只缩短 activeWait；用 `KEYS` 替代 SCAN；跨槽合并 DEL/MGET/Lua；删除 RunStore 归属复核；先删附件登记再删文件；用 JVM Map 充当多实例锁；只加前端 disabled；把控件改为 202 后仍让原请求线程跑完所有工作。

现有 `RunStore.runStatus` 会把读失败折成 null，不能直接拿它替换 purge 的严格快照读。若用 runstate 降低轮询成本，必须新增“存在/终态/活跃/不可知”分明的严格读口；不可知仍 deferred。证据：`backend/src/main/java/com/example/configmgr/ai/run/RunStore.java:2543-2580`。

### 6.2 根治索引：方向正确，但“加一个 SET”并不等于正确

**staging 推荐方案：**新增与 final/staging 相同 `{escapedSessionId}` tag 的会话级 staging index。`STAGE_WRITE` 同一 Lua 内完成 staging RPUSH、索引 SADD 和两者 TTL；`MERGE_CAS` 在 RENAME/清理时同脚本摘成员；销毁按索引精确读取、删除并复核数据键。当前 staging/final/ver/gen 同槽已具备前提，证据：`backend/src/main/java/com/example/configmgr/ai/memory/RedisChatMemoryRepository.java:155-240,313-337,833-840,905-910`。

必须处理：

- 老 staging 没有索引成员，不能直接删 SCAN 后宣布旧数据已覆盖。
- stale 成员要用数据键过滤；索引 TTL 与会话写/复核同寿，不能先过期形成不可发现面。
- 已越过 guard 入口的在飞写者与删除交错仍需 generation/version、删后复核和 60 秒复核；index 不是防复活锁。
- 最终是否删除 index 要有明确生命周期；若复核仍依赖它，就不能在复核前把唯一发现面拆掉。

**archive 推荐方案：**新写路径维护会话级 archive index，且 archive 数据与 index 必须同槽原子写。由于现有 archive 键没有统一 session hash tag，优先采用带身份和 session tag 的新键形，同槽 Lua 完成 `SET archive + SADD index + TTL`；旧键双读、迁移、旧键清理需分阶段完成。证据起点：`backend/src/main/java/com/example/configmgr/ai/run/RunStore.java:4992-5005,5190-5199`。

若坚持不改 archive 键形，则“数据 SET 后再 best-effort SADD”会重新留下崩溃窗口，不能接受为根治；必须使用持久 outbox/补偿记录和可证明最终覆盖的对账。`turnNo` 推导可继续作为有界补充，但不能证明任意异常残留的完整性。

**存量迁移：**把旧 archive/staging 的全 master SCAN 移到低频、有界后台对账，按节点/页/时间切片并记录进度；拓扑变化或失败时明确重扫，不把部分 SCAN 记为覆盖完成。未获得本会话 legacy 覆盖证明的删除保持 pending/deferred，不能删发现键或报 complete。这样才可能同时做到“HTTP 不 SCAN”和“旧会话不漏删”。

### 6.3 活跃取消根治：减少探测不等于削弱幂等

短期可合并 `cancelConfirmed` 与 `cancelParked` 的三次判定，让一次收尾共享同一份已判结果；但这只减少重复，不改善“证明无终帧”的复杂度。

根治建议在原子帧 Lua 中为 done/error 同步维护同槽 `xterminal`（终帧 seq/type），随 run 续期和删除；老流无元数据时才走兼容扫描。兼容扫描应按真实 Stream entry ID 反向/正向物理分页，不能用 `lastSeq-6256` 推断物理窗口。任何元数据缺失、读失败或回填失败都必须保持 UNKNOWN/可重试，不能把未知当无终帧。

### 6.4 幂等、多实例和 Cluster 的最终判定

- **删除幂等：**现有 DEL、DB DELETE、同路径文件删除大体可重试，记忆 clear 重试会推进 gen/ver 但结果仍安全。执行单飞是降负载，不是替代这些幂等。
- ** stalled 接管：**只有 purgeId CAS 不够；同一 purgeId 的旧执行体仍可能写台账。所有阶段推进、完成、失败退避和 verify 排程都要带 execution epoch，旧 owner 不得改新状态。
- **不可中断 I/O：**租约到期不能强杀旧线程；能承诺的是旧线程失去状态推进权、后续步骤在写前检查 epoch，文件/Redis 操作仍须幂等。
- **Cluster：**新增多键脚本必须逐键核同 tag；不同 slot 用单键 pipeline，不用跨槽事务。pending 与全局 due 仍按当前“单键事实 + 到期补偿”模型，不能为省事合并跨槽 Lua。
- **身份：**HTTP 入口固定身份，worker 从 pending 记录恢复身份；不从可含冒号的键名反解身份，不使用工作线程遗留 ThreadLocal。
- **完整性：**只有无 errors/deferred、所有精确目标确认、legacy 覆盖证明成立时才删发现键；pending/guard/gen/ver 等明确 tombstone 属有意例外，xack 若保留 120 秒必须写入完成口径而非隐式遗漏。

## 七、推荐目标架构

### 7.1 持久化销毁协调器，而非“异步 Controller”

以 `(tenantId,userId,sessionId)` 为销毁聚合，保留并扩展现有 pending/due/guard：

1. **受理：**校验并固定身份，创建/复用 purgeId，先确保 pending 与 guard 的防写回事实，再建立执行租约并发布可推进状态。重复 DELETE 返回同一任务状态。
2. **状态：**至少区分 `ACCEPTED / ENUMERATING / CANCELLING / DELETING / DEFERRED / BODY_COMPLETE / VERIFYING / COMPLETE / FAILED_RETRYABLE`；记录 phase、executionEpoch、nextAt、attempts、lastError、legacyCoverage 和必要 checkpoint。
3. **执行：**现有 runner 可先做承接体，但 HTTP 与 runner 必须走同一 per-session claim。每批有对象数、命令数、字节和单调时钟预算；到点持久化 phase 并让出执行权。全局 runner 锁继续作为批调度保护，但不能替代会话级租约。
4. **发现：**run、attachment、staging、archive 都读精确索引；失败重读精确索引即可续作，不再每次全键空间 SCAN。已删成员/登记天然形成剩余工作面，避免把超大目标列表塞进 pending JSON。
5. **取消：**受理后写权威取消事实；worker 执行有界取消收尾。仍活跃者 deferred，不能删 run 主体；终帧幂等由 xterminal/正确物理分页保证。
6. **删除：**保持 DB 先删、guard 防写回、记忆 generation/version、run 13 键同槽、附件先文件后登记、发现键最后删。独立点命令按 slot 有界 pipeline，类型保护用单键 Lua。
7. **复核：**主体完成后保留 pending 到记忆复核；完成状态要区分“HTTP 已受理”“主体完成”“复核完成”。持续失败有界退避并告警，耗尽保留 durable 义务和人工重开入口，不静默宣称最终成功。

### 7.2 HTTP 与旧会话静默清理契约

推荐最终采用 `202 + purgeId + 可查询状态`，但只有在上述持久义务、worker 接续和状态语义已经存在时才切换。过渡期可保留 `200 complete=false/deferred`，不要把 200 改成 202 当作性能本身。

前端保持当前“同步清屏、不等删除”的优点，并做三项修正：

- 旧会话销毁与新会话 ensureSession 并行，取消职责由销毁链唯一承担或明确兼容边界；
- DELETE 显式携带 reset 入口捕获的旧身份；
- 同旧身份+sessionId 的前端销毁 Promise 单飞，超时/失败不伪报服务端停止或删除完成。

本地旧镜像/草稿可以按已捕获旧身份立即、独立清理；服务端最终事实由 pending/guard/runner 和运维告警保证可追踪。无需为每次旧会话销毁新造高频前端轮询。

## 八、分步施工顺序

1. **先补观测与不变量。** 给 pending/enumerate/stagingScan/archiveScan/db/cancel/runDelete/context/attachments/finalize 分别记录耗时、命令数、SCAN 页数、R/A/S/F、节点数、错误类型和 execution 重叠；同时固定 complete/deferred、发现键最后删、guard/pending 防复活、附件先文件后登记等验收断言。
2. **先修会妨碍安全提速的正确性。** gate/current 增加严格读口；补 xtrim 删除并明确 xack 的 TTL 例外或改 per-run 有界 HASH；修 terminalScanStart 的跳号问题。不要把错误 null/UNKNOWN 变成快速 complete。
3. **统一 HTTP/runner 执行协议。** 增加 per-session owner token + TTL + 续租 + executionEpoch；所有 pending 状态写带 epoch；重复 DELETE 只查状态。覆盖 stall 后旧 owner 恢复、进程死亡和跨实例接管。
4. **做不改键形的止血。** 按 slot pipeline、单键 TYPE+DEL Lua、严格轻量 runstate 读、有效终态快照复用；设施级超时停止本轮无意义逐键重试。若仍同步返回，加覆盖全链的新工作预算，超时保留 durable pending，而非回滚义务。
5. **拆活跃取消慢链。** 请求只写取消事实并记录 deferred；由有界 worker 接续。先合并三次终帧探测，再引入同槽 xterminal 和旧流兼容；不得删除活跃 run 13 键来换延迟。
6. **建立 staging/archive 精确索引。** staging 同槽双写与 RENAME 摘成员；archive 采用新同槽键形、双读和原子索引维护。所有新写路径先证明“数据成功而索引永久不可发现”的窗口已关闭或有持久补偿。
7. **后台迁移与对账旧数据。** 有界 SCAN、持久进度、失败/拓扑变化重扫、按会话记录 legacy 覆盖结论。迁移未完成时删除请求走 deferred 接续，不在 HTTP 偷留旧全扫描，也不伪报 complete。
8. **把销毁主体改为分阶段可恢复 worker。** 以 pending phase/checkpoint 和精确索引续作，限制每批对象/命令/字节/时间；验收最终完成时间和积压年龄，而非只看后台线程是否已接手。
9. **最后切换 202 和前端契约。** 在持久受理、单飞、接续、状态查询和告警均已成立后，Controller 才缩为受理；前端并行新发号与旧销毁、固定旧身份并单飞。之后删除请求期两趟 SCAN，保留低频对账而非每次重扫。

该顺序刻意把“接口异步化”放在持久状态机与接续语义之后；若产品必须先快速 ACK，也只能在步骤 1–4 已保证 durable pending、guard、单飞和后台续作后提前切换，不能只做 Controller 改动。

## 九、后续验证清单（本次未执行）

- **归因矩阵：**standalone 与真实三主 Cluster；大/小 keyspace；0/多 run、archive、staging、附件；有/无活跃 run；记录每阶段 P50/P95/P99、游标页数、命令数、JSON 字节和最终完成时间。
- **预算与故障：**SCAN 中途失败、单 master 慢、MOVED/ASK/resharding、多个命令接近 2 秒、DB 锁、共享盘慢；验证不再发新工作、pending/guard/发现键保留、后续可续清。
- **多实例：**同实例双击、跨实例同 DELETE、HTTP 与 runner 交错、runner stall 接管、旧 owner 迟到恢复；断言只有一个当前 epoch 能推进台账，重复操作最终幂等。
- **活跃 run：**RUNNING/SUSPENDED、他实例持租、无本地驱动、无终帧、终帧在首/中/尾、大跳号、6000 附近帧窗；验证不重复终帧、不改写 DONE/FAILED、不提前删活跃 run。
- **索引与迁移：**新旧 archive/staging、stale/缺成员、写者 crash、RENAME 成功回包丢失、拓扑变化；证明新删除成本随本会话目标数而非全库增长，旧数据最终有覆盖证明。
- **完整性：**检查 run 13 键、xtrim/xack、观测键、transcript、上下文、附件、DB、pending/guard/gen/ver 的预期存留；区分有意 tombstone 与漏删。
- **旧会话体验：**清屏不等待 DELETE；新发号慢不推迟旧销毁；A 身份开始后切 B 仍用 A；30/60/60+ 秒与网络失败均不伪报完成；双击只保留一个旧会话任务。

## 十、终审边界

本次只完成静态源码复核和方案审查，没有真实 Redis Cluster、事故日志、trace、慢日志、线程转储、DB 执行计划或共享盘数据。因此可以确认“当前代码存在多条可超过 30 秒且无总预算的同步链”，不能确认某次事故的唯一实测根因，也不能确认任何修复已经达到性能目标。最终验收必须同时证明：**HTTP 占用下降、Redis 总工作不随全库无谓增长、旧会话最终可追踪地清净、失败/多实例/Cluster 下不漏删不误删。**
