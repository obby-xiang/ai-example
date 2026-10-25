> 来源=<仓外证据>/ai-full-audit/final-gap/FA2-report.md（2026-10-25 归档）· 审计分片/草稿，属历史证据，不代替终稿 `docs/research/排查-AI全链路只读验收审计-终稿-2026-10-25.md`
# FA2-report｜后端运行存储与并发原语 + 属性面

> **未运行任何测试。** 本报告为纯静态只读审查：未执行 `mvn`/`gradle`/任何测试或脚本，未启动服务，未改任何代码/测试/配置/工作流，未读取任何密钥值（`KB_HTTP_AUTH` 只作为变量名出现，未读取其值）。
> **快照**：`<仓外证据>/ai-full-audit/tree`（base `ae883fba` + `working.patch`）。
> **范围**：严格等于 `final-gap/FA2.md` 的 5 个文件，**3,578 / 3,578 行全部逐行读完**（见 §1）。为判定调用面可达性，另**定点**读了 3 处调用点片段（`ResumeService:760-790/990-1040`、`AiController:400-490`、`AiSessionPurgeService:440-470/715-740`）与测试文件，**未做递归范围扩张**（未新增清单外文件为审查对象）。
> **交付去向**：本 agent 无写权限，`final-gap/FA2-report.md` 未落盘；本消息即完整报告正文，请控制器原文写入。

## 1. 覆盖确认（逐行）

| # | 文件 | 行数 | 已读区间 | 覆盖 |
|---|---|---:|---|---|
| 1 | `ai/run/RedisFrameStore.java` | 758 | 1–400、400–758 | **100%** |
| 2 | `ai/run/FrameSizeLimiter.java` | 449 | 1–449 | **100%** |
| 3 | `ai/session/SessionGate.java` | 460 | 1–460 | **100%** |
| 4 | `ai/memory/RedisChatMemoryRepository.java` | 412 | 1–412 | **100%** |
| 5 | `ai/config/AiProperties.java` | 1,499 | 1–520、520–1040、1040–1499 | **100%** |
| | **合计** | **3,578** | | **3,578 / 3,578** |

分支类别覆盖（每文件都按此四类过了一遍）：**正常** / **错误（Redis 异常、序列化失败、读失败、写失败）** / **降级（`degraded`、进程内兜底、fail-closed、fail-open）** / **租约·fence·多实例（单键 Lua、token 归属、TTL/watchdog、跨实例窗口）**。

## 2. 与既有 canonical ID 的关系（逐文件）

### 2.1 `RedisFrameStore.java` —— 确认 RT-07 / RT-09，补充 2 条，新增 3 条

| 关系 | 条目 |
|---|---|
| **确认** | **RT-07**（`399-416`、`668-679`）：窗口裁剪只 `record(C1_STREAM_REPLAY_GAP)`，**帧内无缺口标记**，`afterSeq=0` 的全量回放路径连计数都不记（`409` 的 `afterSeq > 0L` 前置）。行号与机制逐字吻合。 |
| **确认** | **RT-09**（`183-197`、`485-499`、`741-743`）：`INCR` 失败 → `fallbackSeq` 进程内自增；消费侧同号被 `mergeBySeq` 覆盖。**精确化更正**：`fallbackSeq` 的种子是 `lastSeq()`（**已落档**末号，`742`），不是"最后发出的号"——因此撞号不仅发生在"与已归档帧同号"，更常发生在**与另一实例已发未落档的号**相同；该帧落档后两个帧同 seq，读取侧按 Stream 覆盖丢一帧，且 `repairRollback` 的判据 `issued <= fallbackLast` 只比进程内计数，看不出跨实例的这半个区间。 |
| **补充 → RT-12** | 每帧归档路径实为 `INCR` + `EXPIRE`（`186-188`）+ `XADD` + 内联 `EXPIRE`（`304-312`），且 `append` 在 `emitLock` 内被调用（`SseChatEmitter:1482-1493`）。RT-12 的"3–4 次往返"成立；此处只补"取号侧另有 2 次"。 |
| **补充 → RT-07** | 见 **FA2-N3**：缺口计数器本身是**进程内窗口口径**，跨实例会漏计。 |
| **新增** | **FA2-N2**（末号探测低估 → 假"无新帧"）、**FA2-N3**（读侧窗口与写侧不同源）、**FA2-N4**（5 张按 runId 的映射无回收）。另有一条并入 **FA2-N10**（无 seq 的 legacy 帧重发）。 |

### 2.2 `FrameSizeLimiter.java` —— 确认 PK-16 / PK-17，补充 1 条，无新编号

| 关系 | 条目 |
|---|---|
| **确认** | **PK-16**：`markTruncated`（`283-295`）**无条件** `put` 七个截断字段；`SseChatEmitter.markModelView`（`1275-1294`）先写 `MODEL_VIEW_TRUNCATED`，随后 `apply` 把它覆盖成 `FRAME_TRUNCATED`。双通道同时截断 ⇒ 用户只看到"帧超限"、看不到"模型没看全"。 |
| **补充（更正 PK-16 的覆盖面）** | 被覆盖的不只是 `noticeCode`：`ORIGINAL_CHARS_FIELD` 也由 `apply` 用**入帧时**的值重算（`277-280` + `111`），而该帧的 `text/result` 已被模型侧裁过 ⇒ 双重截断下 `originalChars` 报的是"模型侧裁后长度"，**用户看到的"被截掉多少字"偏小**；`STRATEGY_FIELD` 亦被改写为 `HEAD`；`fields.isEmpty()` 时还会 `remove(STRATEGY_FIELD)`（`286`），把模型侧的 strategy 一并抹掉。 |
| **确认** | **PK-17**：`TRUNCATABLE_FIELDS` 只有 `text`/`result`（`56`），`resultView`/`args` 等非可截字段超限时只挂 `FRAME_LIMIT_UNREACHED` 并**返回超限帧**（`138-141`）——"单帧上限"对这类帧不可兑现。补一条观察：`generative_form` 的 `args`（`SseChatEmitter:1229` 的 `frame.put("args", ...)`）是**可截集合之外**的模型可控内容，只靠 schema 上界（字段 ≤20、选项 ≤50）间接约束。 |
| **确认（无缺陷）** | `splitStringValue` 的骨架口径正确：调用方把 `runId`、`seq=Long.MAX_VALUE` 都放进骨架（`SseChatEmitter:997-1000`），且真实帧缺这两个键 ⇒ 每片必不超限；`takeChars` 的二分上界 `min(len, byteBudget)` + 代理对回退（`425-438`）不切半、不返回空片。**拆帧路径本身没找到缺陷**。 |
| **死码一致性（S4，不新开编号）** | `94-97`：`frame == null || mapper == null` 返回 `new byte[0]`，而契约是"序列化失败返回 null ⇒ 调用方跳过本帧"（`SseChatEmitter:1483-1487` 只判 null）⇒ 若真为 null 会投递/落档一条空 data 行。生产不可达。 |

### 2.3 `SessionGate.java` —— 独立复核：F 域"全读"成立，但**穷尽性不成立**；新增 2 条

| 关系 | 条目 |
|---|---|
| **确认** | 四条 Lua 的"脚本内比对 + 单键 + 不裸写"（`80-143`、`356-369`）、token 精确释放（`269-290`）、`releaseDeadHeld` 只匹配 `runId` / `runId|*`（`130-143`）、`null` 结果不当成功（`359-362`）—— 与 F 域描述一致，机制正确。 |
| **确认** | **MD-18** 的前提（"压缩 RMW 被 SessionGate 串行化"）成立：同 sessionId 只有一个 run 能持门，`acquire` 的三分支（`195-236`）没有"两个 run 同时返回 null"的路径（除降级态）。 |
| **新增** | **FA2-N5**（锁失效后静默降级为纯本地门、`degraded` 不置位，与类注承诺相反）、**FA2-N6**（`Busy.runId()` 回退成调用方自己的 runId ⇒ 被读成"取门成功"）。 |
| **已分析并排除** | ①单线程 watchdog 续期（`159-163`）：TTL 600s / 周期 30s，需 ~20 次续期全被阻塞才丢锁，不构成独立缺陷；②`ttlMs()` 用 `max(1, …)`：配 1ms 时锁等于不存在，归入 FA2-N7；③`acquire` 重入分支（`195-219`）在生产几乎不可达（`ResumeService` 在 `632` 已用 `resuming` 互斥、`AiController` 每次新 runId）——**I2 的"同 run 新 token 接管"语义实际只由单测驱动**（见 §5）。 |

### 2.4 `RedisChatMemoryRepository.java` —— 确认 ID-04，**扩边 1 条（新编号）**，新增 3 条

| 关系 | 条目 |
|---|---|
| **确认** | **ID-04**：`key("x") == "chat:mem:{x}" == legacyKey("{x}")`（`187-189` vs `202-204`）。读回退（`285`）、`saveAll` 删 legacy（`338`）、`delete` 删 legacy（`352`）三处都命中，行号准确。 |
| **扩边（新编号）** | **FA2-N1**：同一根因还有第二个别名——`sessionId="__ids__"` 的 legacy 键**就是全局记忆索引键**。 |
| **补充 → F-04 / RT-06** | **FA2-N8**：`isArmed` 只查一次（`309`），purge 可在"查过之后、写之前"完成删除 ⇒ 记忆复活。 |
| **补充 → RT-28 / 销毁完整性** | **FA2-N9**：`chat:mem:staging:{<id>}:<uuid>` 不在销毁面内；`ttl<=0` 时该键**不设 TTL**（`404-410`），与类注"finally 的 DEL 失败也不会留下无 TTL 的孤儿"（`56`）矛盾。 |
| **确认** | **MD-11**（TTL 只写时滑动）：读口 `findByConversationId` 不续期，实现与文档一致。 |
| **已分析并排除** | `findConversationIds` 的 `null` 成员分支（`233-235`）不可达（SMEMBERS 不返回 null）；`addToIndex` 失败只影响 `removedFromIndex` 报告字段——`isConversationIndexed` 要求"索引成员 ∧ 数据键存活"，索引缺失不会让销毁误判为成功（与 RT-28 的 run 索引不同类）。 |

### 2.5 `AiProperties.java` —— 新增 1 条（配置面），其余为确认

| 关系 | 条目 |
|---|---|
| **确认** | **MD-03**（`limitTokens=128000`，"未知档"默认不可达、设 0 可达）、**MD-20**（`modelsCacheTtl<=0`=永久、`reloadInterval` 缺省 1s、`summaryModel` 退役键）、**RT-30** 邻域（`clock/write-timeout` 类键确有 fail-fast）。9 段 `@PostConstruct` 校验（`234-654`）逐条读过，判据与注释一致、无自相矛盾。 |
| **新增** | **FA2-N7**：守卫覆盖**不均**（`session.lock.*`、`lease.*`、三处池容量、`resilience.max-*`、`input.max-chars` 无任何校验），最重的两档会静默打掉跨实例保证。 |
| **如实登记的边界** | `AiPropertiesT35DefaultsTest:19-20` 明确记载"启动期容量联动校验"是**被刻意删除的过度设计**（R-148/B-3）。故 FA2-N7 的定性是"覆盖不均 + 两处关键时序键缺闸"，**不是**"忘了写校验"。 |

## 3. 新条目（只含"代码可证"的）

> 格式：Sev / Cert（C=代码可证，R=需运行期条件）/ `path:line` / 触发 / 用户可见效果 / 测试证据。

### FA2-N1 记忆 legacy 键未转义：`sessionId="__ids__"` 直接命中**全局记忆索引键**
- **Sev S2 / Cert C**
- **位置**：`RedisChatMemoryRepository.java:101`（`CONVERSATION_IDS_KEY = "chat:mem:__ids__"`）、`:202-204`（`legacyKey` 用**原样** id）、`:285`（读回退）、`:338`（`saveAll` 删 legacy）、`:352`（`delete` 删 legacy）
- **触发**：`sessionId` 由客户端声明且无形状校验（ID-08；`AiController:313-316` 只判 `hasText`）。① 任何 `sessionId="__ids__"` 的记忆读（`/chat` 装载历史、`/history`）→ `opsForList().range("chat:mem:__ids__", …)` 打在 **SET** 上 ⇒ `WRONGTYPE` 抛出（`RedisChatMemoryRepository` 无 catch）；② 该 sessionId 的 `deleteByConversationId`（销毁端点 `AiSessionPurgeService:719`，或"空窗 saveAll"`316`）→ `redis.delete("chat:mem:__ids__")`。
- **效果**：该会话记忆读取 500（整轮失败）；**一次请求即可删掉全库的会话记忆索引**（`findConversationIds` 变空、`isConversationIndexed` 恒 false ⇒ 销毁响应的 `removedFromIndex` 永远报"已移出"）。索引是弱索引、可由各会话下次保存自愈，故不升 S1；但它同时是"未认证即可破坏的共享状态"。
- **同类（更低）**：`sessionId="staging:{x}:tok"` 的 legacy 键 = 会话 x 的 staging 键（`197-199`），可删在飞 staging。
- **测试**：无。`RedisChatMemoryRepositoryDeleteTest`/`ClusterSafetyTest` 只用普通 id；没有任何"id 与内部键同形"的用例。

### FA2-N2 末号探测取"末 16 条 max"：乱序 ≥17 条时**提早返回"没有新帧"**
- **Sev S3 / Cert R**（需 seq 降级 + 跨实例推进的组合）
- **位置**：`RedisFrameStore.java:107`（`TAIL_PROBE=16`）、`:580-604`（`streamTailSeq` 只取末尾 16 条的最大 seq）、`:405-407`（`tail.seq() <= afterSeq` ⇒ `return empty, degraded=false`）、`:408`（夹取下界用同一低估值）、`:464-476`（`lastSeqIfKnown` 同样低估）、`:741-743`（`fallbackSeq` 以它做种子）
- **触发**：F-07 的降级态下，某实例的进程内号持续**低于**真实尾巴（另一实例在推进）⇒ 它每次 `XADD seq-0` 都被拒、全部走自动 ID（`271-283`）。连续 ≥17 条后，末尾 16 条的最大 seq < 更早记录的真实最大 seq。
- **效果**：读者（含重挂、`stageReplay`）在 `afterSeq` 落在"低估末号 .. 真实末号"之间时拿到**空结果且 `degraded=false`**——不是"读失败"、不计数、不告警，永久缺段（与 RT-07 同类但成因不是窗口裁剪）；`lastSeqIfKnown` 同向低估 ⇒ 前端 `lastSeq` 锚点倒退（RT-09 的"lastSeq 倒退"由此多一条成因）；`fallbackSeq` 用低估尾做种子 ⇒ 自我强化。
- **测试**：`RedisFrameStoreTest:140-156` 只构造**1 条**晚到帧；`FakeFrameRedis` 已按 Redis 语义拒绝显式 ID（类注 `34-40`），构造"17 条晚到"不需要真 Redis —— **用例可写而未写**。

### FA2-N3 读侧窗口与写侧"不同源"（跨实例）：`c1.streamReplayGap` 会漏计
- **Sev S4 / Cert C**
- **位置**：`RedisFrameStore.java:136-142`（`observedPayloadBytes` 进程内口径，注释自认"全局上界退化为各实例观察之和"）、`:401`+`:682-685`（`effectiveWindow` 用**本实例**观察值）、`:408-416`（缺口计数依赖 `tail-window` 夹取）
- **触发**：实例 A 写过大帧（窗口收窄到 300），实例 B 只读（`observed=0` ⇒ 窗口 6000）后被要求 `?lastSeq=` 回放。
- **效果**：B 的 `from = max(afterSeq, tail-6000)` 落在真实幸存窗口之前 ⇒ 读到的是幸存头（`XRANGE` 对已删区间静默返回空），而 `from == afterSeq` ⇒ **不记 `c1.streamReplayGap`**。运维在 `/api/ai/health` 看到的"回放缺口=0"不能证明"没有缺口"；类注"读侧对游标早于幸存窗口单独计数、不伪装成没有新帧"（`394-397`）在多实例下不成立。
- **测试**：`RedisStreamCapacityTest:161-176` 全部在**同一实例**（同一 `observedPayloadBytes`）内断言计数，跨实例口径无用例。

### FA2-N4 五张按 runId 的映射无回收（进程内无界）
- **Sev S4 / Cert C**
- **位置**：`RedisFrameStore.java:124`（`fallbackSeqs`）、`:127`（`outOfOrderWarned`）、`:142`（`observedPayloadBytes`）、`:145`（`byteCapWarned`）、`:148`（`byteFloorWarned`）
- **触发**：任意长跑的实例持续产生新 runId；`deleteRun`/会话销毁/终态都**不清**这些映射（本类没有对应钩子）。
- **效果**：内存按"历史 run 数"线性增长（每 run 约 3 个 AtomicLong + 2 个 Set 条目），无指标、无上限；与 RT-26 的"回放队列按帧不按字节"是两条不同口径的容量问题。
- **测试**：无。

### FA2-N5 会话锁"失效/换主"后**静默**退化为纯本地门，`degraded` 不置位
- **Sev S3 / Cert R**
- **位置**：`SessionGate.java:392-403`（`renewLockOnce` 返回 false ⇒ 只 `WARN` + 取消续期；`holder.degraded` **不置位**）、`:431`（`Busy.degraded` 只反映"Redis 异常过"）、类注 `:63-65`（承诺"降级事实写进返回体与 WARN 日志，不静默"）
- **触发**：一次续期返回 0 —— 键不存在（TTL 到期 / Cluster failover 丢写 / 被 `release`、`releaseDeadHeld` 删掉）或已换主。
- **效果**：本实例继续驱动该轮且**只受进程内门约束**；另一实例此时可正常取门 ⇒ 同一 sessionId 两轮并发（ADR-5 破），而 `/chat` 的 409 体仍报 `degraded=false`、`/health.sessionLockRenewals` 停增，运维无法从任何返回面看出"跨实例互斥已失效"。类注承诺的"不静默"在这条路径上不成立（**这是与 F 域结论的差异点**：F 只记了 `null` 结果那条降级路）。
- **测试**：`SessionGateLeaseTokenTest:117-135` 只断言 `renewLockOnce` 返回 false、不写 EXPIRE；**没有**"watchdog 自停后本地门仍在放行"的断言，也没有 `degraded` 传播用例。

### FA2-N6 `Busy.runId()` 的诊断回退可等于调用方自己的 runId ⇒ 被读成"取门成功"
- **Sev S3 / Cert R**（机制 C，触发需一次 GET 失败）
- **位置**：`SessionGate.java:372-380`（`observedRunId` 读失败 ⇒ `return fallback`，`fallback` 是**调用方的 runId**）、`:227-231`（首次取门失败分支用它构造 `Busy`）；消费侧 `ResumeService.java:773-785`（判据是 `busy != null && !runId.equals(busy.runId())`，**相等即当成功**并 `gateHeld=true`）
- **触发**：ACQUIRE 脚本确定返回 0（另一轮持锁），紧随其后的诊断 `GET` 抛错（Redis 抖动 / 该单键所在 slot 故障）。
- **效果**：续跑在**没有跨实例会话门**的情况下推进（本地门仍被占，所以本实例看起来"持门"），与另一实例正在跑的会话轮并发；`gateHeld=true` 会让终态 `release` 走 token 精确匹配（无害）但掩盖了这次失败。
- **接线边界（如实）**：`SessionGate` 的**重入失败分支**（`:207-211`）同样会返回 `Busy(自身 runId)`，但该分支在生产几乎不可达（`ResumeService` 在 `632` 已用 `resuming` 互斥）。
- **测试**：无用例（`SessionGateLeaseTokenTest` 无"GET 失败 + 脚本返回 0"的组合，也无 `ResumeService` 侧该判据的用例）。

### FA2-N7 配置守卫覆盖不均：两处**时序键**与三处**池容量**无任何闸
- **Sev S3 / Cert C（缺闸）/ R（后果）**
- **位置**：`AiProperties.java` 有 9 个 `@PostConstruct` 校验（`:234-242`、`:266-295`、`:310-329`、`:347-393`、`:407-422`、`:438-501`、`:516-539`、`:566-582`、`:598-654`），但下列键**无校验**：
  - `session.lock.ttl` / `session.lock.watchdog`（`:953-981`）：`watchdog >= ttl` 或 `ttl <= 0`（`ttlMs()` 只做 `max(1,…)`，`SessionGate:382-384`）⇒ 锁在两次续期间隙必然过期/几乎不存在，**跨实例会话串行化静默失效**，且与 FA2-N5 叠加后完全无告警。
  - `lease.ttl` / `lease.renew-interval`（`:1221-1247`）：`renewInterval >= ttl` ⇒ 健康部署上每轮都丢租 ⇒ **RT-10 的"LOST 墓碑终局"由配置触发**，而不是故障触发。
  - `suspend.pool-size`（`:705`）、`sse.delivery-pool-size`（`:1008`）、`resume.trigger-pool-size`/`trigger-queue-capacity`（`:944-950`）取 0 ⇒ 挂起/投递/续跑 100% 拒绝（`SUSPEND_POOL_SATURATED`、订阅者被摘、`EXECUTOR_SATURATED`）。
  - `resilience.max-attempts=0`、`resilience.max-tool-steps=0`、`input.max-chars<=0`、`resume.max-on-startup=0`（`:777`、`:801`、`:1283`、`:934`）⇒ 分别表现为"重试关掉""每个工具都 SKIPPED/TOOL_STEP_LIMIT""每条消息 400""启动续跑一个都不做"，全部是**配置面看起来正常、运行面语义被改写**（正是本类其他键 fail-fast 的理由）。
- **旁证（不变量只有默认值被钉）**：`app.ai.sse.delivery-queue-capacity < RunStore.EVENT_WINDOW(3000)` 这条"R1 不变量"只在 `AiPropertiesT35DefaultsTest:35-37` 对**常量**断言，配置成 5000 时无任何校验。
- **测试**：`AiProperties*GuardTest` 只覆盖 context/limit/memory/sheet/stream；`grep` 全测试树，**没有任何用例引用 `session.lock.*` 或 `lease.renew-interval`**。

### FA2-N8 记忆写回守卫是"检查一次再写"：purge 可与 `saveAll` 交错 ⇒ 销毁后记忆复活
- **Sev S3 / Cert R**
- **位置**：`RedisChatMemoryRepository.java:309-313`（在**写之前**问一次 `isArmed`）、`:320-330`（随后序列化 + staging + `RENAME`）；对照 `SessionPurgeGuard` 类注"在跑轮次的终局会做整窗收敛…必须关掉这个窗口"
- **触发**：轮次收尾的 `saveAll` 通过守卫检查 → 用户并发销毁（`arm` 后删记忆）→ `saveAll` 的 `RENAME` 落地。
- **效果**：销毁返回 `complete=true`，而 `chat:mem:{<id>}` 在全量删除之后被重建、带着整轮正文存活一个会话 TTL。与 **F-04**（DB 侧同类 TOCTOU）同根因，但**记忆面这条路径未被 F-04 覆盖**，也未被 RT-06 的完整性判定覆盖（`deleteMemory` 只看"删没删"，不看"删完又出现"）。
- **测试**：`SessionPurgeGuardFailClosedTest` 只覆盖"守卫读失败 fail-closed"，无"检查后交错"用例。

### FA2-N9 销毁不覆盖 `chat:mem:staging:*`；`ttl<=0` 时 staging **无 TTL**
- **Sev S3 / Cert C（缺口与无 TTL 分支）/ R（残留是否存在）**
- **位置**：`RedisChatMemoryRepository.java:116-120`（`STAGE_WRITE` 只在 `ttl > 0` 时 `PEXPIRE`）、`:404-410`（`stagingTtlMillis()` 对 null/0/负返回 **0** ⇒ 不设 TTL）、`:394-402`（`deleteStagingQuietly`）；销毁面 `AiSessionPurgeService.java:35`（只声明 `chat:mem:*` 的 tagged + legacy）、`:719-727`（只调 `deleteByConversationId`）
- **触发**：① `app.ai.session-ttl`（或 `setTtl`）为 0/负 + 保存路径任一步失败（或进程崩溃在 `STAGE_WRITE` 与 `RENAME` 之间）⇒ staging 键**永久**保留整窗消息 JSON；② TTL>0 时同样不被销毁面删除，最多存活一个会话 TTL。
- **效果**："销毁会话"后，该会话正文仍以明文留在 Redis（②最多 24h，①永久）；且 `saveAll` 类注"数据与 TTL 同生同死…finally 的 DEL 失败也不会留下无 TTL 的孤儿"（`:56`、`:110-115`）在 ① 下不成立（文档漂移）。
- **测试**：无用例（`RedisChatMemoryRepositorySaveAllEmptyTest`/`DeleteTest` 不检查 staging 家族，也没有 `setTtl(0)` 的用例）。

### FA2-N10 无 `seq` 的 legacy 帧在每次差量读里被**无条件**重发
- **Sev S4 / Cert C**
- **位置**：`RedisFrameStore.java:485-499`（`mergeBySeq` 把 `noSeq` 放在最前，**不参与 `afterSeq` 过滤**）、`:511-516`（`collect` 的 else 分支）、`:513-514` 注释"一律保留"
- **触发**：升级期遗留的 LIST 里存在无 `seq` 帧，且该 LIST 未过期（TTL = 会话 TTL）。
- **效果**：任何 `afterSeq>0` 的差量读/重挂都会**重复**投递同一批老帧；前端按 `seq` 去重对无 seq 帧无效 ⇒ 重复渲染（例如重复的 `tool_result` 卡）。仅升级期可达，故 S4。
- **测试**：`RedisFrameStoreTest:337-351`（`legacyFramesWithoutSeqAreKeptAtTheHead`）只断言"保留"，**未断言"不应在 afterSeq>0 时重复"**；`FrameCompatReadTest` 同向。

## 4. 对新条目之外、但会影响既有定性的两点更正

1. **PK-16 的覆盖面偏窄**：被覆盖的除 `noticeCode` 还有 `originalChars` / `returnedChars` / `strategy`（`FrameSizeLimiter:283-295`）。双重截断时用户看到的"原始长度"是模型侧裁后长度 ⇒ 数字偏小，属"可观测面失真"而非仅"提示文案被覆盖"。
2. **RT-09 的成因描述可再精确**：撞号的常见形态是"降级号 vs 另一实例**在飞未落档**的号"（种子取自已落档末号，`RedisFrameStore:742`），不是"与已归档帧同号"。这不改变严重度，但决定了修复点必须落在"共享号源"而不是"落档去重"。

## 5. 测试真实性（5 个文件对应的测试面）

| 文件 | 测试 | 断言强度 | 真实性边界 |
|---|---|---|---|
| `RedisFrameStore` | `RedisFrameStoreTest`(564)、`SeqPersistenceTest`(183)、`FrameCompatReadTest`(123)、`RedisStreamCapacityTest` | **较强**：字节窗口算式（1000/997 整除点）、近似裁剪 + 闭区间、乱序 1 条、末号探测失败、`degraded` 语义、计数增量都有 kill 注释 | 全部基于手写内存替身 `FakeFrameRedis`（**无真 Redis**）。替身按 5.0.14 拒绝排他区间、按 Redis 语义拒绝显式 ID —— 这是**好**的建模；但"乱序 ≥17 条""跨实例窗口不同源"两类都**没有**用例，正是 §3 里 FA2-N2/N3 的空白 |
| `FrameSizeLimiter` | `SseFrameSizeLimitTest`(373)、`DeltaFrameSplitTest`(420)、`DeltaFrameSplitLimitGuardTest`(224) | 强：拆片守恒、代理对、骨架放不下抛异常、复测收缩 | 无"模型侧已截断 + 帧侧再截断"的组合用例（PK-16 的空白由测试侧证实）；`MIN_FRAME_BYTES` 在 `FrameSizeLimiter:80` 与 `AiProperties.ResultBudget.Ui:1129` 各写一份 1024，**没有相等性断言**（漂移风险，S4 级） |
| `SessionGate` | `SessionGateLeaseTokenTest`(536) | 强：脚本内比对、单键 KEYS、未知结果不当成功、裸写计数=0 | **Lua 原文一次都没被执行**：`FakeSessionRedis.handleScript` 按脚本标记分派、用 Java 重实现语义。因此"原子性"证明的是**意图**，不是脚本本身（与 F-03 一致）；watchdog 自停、`degraded` 传播、`Busy` 回退三条路径无用例 |
| `RedisChatMemoryRepository` | `ClusterSafetyTest`(401)、`DeleteTest`、`SaveAllEmptyTest` | 中：键形/hash tag/RENAME 路径/删除面 | 只覆盖普通 id（ID-04、FA2-N1 空白）；无 staging 残留、无 `setTtl(0)`、无"守卫通过后交错删除" |
| `AiProperties` | 5 个 `*GuardTest` + `T35DefaultsTest` + `AiResumeZombieAgeGuardTest` | 中：每个 fail-fast 都有正例/反例 | **零**：`session.lock.*`、`lease.*`、三处池容量、`max-attempts/max-tool-steps/input.max-chars`（FA2-N7 的空白由测试侧证实） |

**配置绑定检查（只读对照 `application.yml:125-420` + 各 profile）**：`app.ai` 段全部 20 个子段的 yml 键都与 `AiProperties` 字段一一对应，**未发现拼写漂移或"配置了但不绑定"的键**；`app.ai.lease.*` / `app.ai.sse.delivery-*` 只以注释显化（"7 键显化纪律"），实际值来自代码缺省（30s/10s/35s/60s、8、256），`application-*.yml` 无覆盖；`park.enabled`、`resume.on-startup` 的"代码缺省 ≠ 出厂 yml"是**已登记**的刻意不一致（`AiProperties:841-845`、`:887-892`），本次复核一致。**未发现密钥值**：`Kb.Http.authEnv` 只声明环境变量名（`:1488-1495`），本报告未读取该变量。

## 6. 未解决 / 读不出来的分支（明确保留，不作结论）

1. **真 Redis Cluster 语义**：`SessionGate` 四条脚本、`RedisChatMemoryRepository` 的 `STAGE_WRITE`/`RENAME`、`RedisFrameStore` 的 `XADD/XRANGE/XREVRANGE` 在 MOVED/ASK、slot 迁移、master 故障下的表现——本地无真 Cluster，**Lua 与命令原文未被执行过一次**（与 F-03 一致）。
2. **FA2-N2 的触发概率**：需要"降级实例 + 另一实例推进 ≥17 帧"的时序，静态只能证明路径存在，不能给频率。
3. **FA2-N6 的真实可达性**：取决于"ACQUIRE 返回 0 且紧随 GET 抛错"这一交错在生产 Redis 客户端重试/命令超时下的实际分布（未实测）。
4. **FA2-N8/N9 的残留是否存在**：需要运行期注入（RENAME 失败 + DEL 失败 / 进程在两步之间被杀），本次未构造。
5. **`chat:mem:__ids__` 的 `WRONGTYPE` 是否 100% 表现为 `/chat` 500**：取决于记忆 advisor 与 `GlobalExceptionHandler` 的包装层（`MD-13` 记录的 `/history` 500 是同族证据，但未逐层验证记忆装载路径）。
6. **`args`（`generative_form` schema）是否真的可能超过 64KB**：schema 有字段/选项/长度上界，未做上界求和证明，故只列为"非可截字段只靠约定"，**不**判定为超限可复现。
7. **`isDeadRound` → `releaseDeadHeld` 是否会删掉"活轮"的会话锁**（FA2-N5 的一个可能的触发源）：判据在 `RunLiveness`/`AiController`（不在本组 5 文件内），未展开。
8. **`FrameSizeLimiter.MIN_FRAME_BYTES` 与 `ResultBudget.Ui.MIN_FRAME_BYTES` 双常量**：未找到相等性用例，也未找到任何装配期交叉引用（只有 `SseChatEmitter:435` 用前者、`AiProperties:378` 用后者），漂移后果取决于两侧同时改动，故只记为 S4 风险。

## 7. 结论（FA2 口径）

- 5 个文件 **3,578 行逐行读完**；`§8.2` 对这 5 个文件的"未逐行"表述可撤销，但**不能**据此写成"已完整验证"——本组只做静态核对，未运行任何测试。
- **新增 10 条**（S2×1、S3×5、S4×4），其中 **FA2-N1**（记忆 legacy 键别名命中全局索引）与 **FA2-N5**（会话锁失效后静默降级）是与既有 canonical 定性差异最大、最值得并入 §5 的两条；**FA2-N2** 给 RT-07/RT-09 补了一条"假无新帧"的成因。
- **更正 2 条**：PK-16 的覆盖字段不止 `noticeCode`；RT-09 的撞号来源应写成"与在飞未落档号相同"。
- 未发现任何"代码已修"的证据，本报告不声称任何条目已修复；未改动任何文件。
