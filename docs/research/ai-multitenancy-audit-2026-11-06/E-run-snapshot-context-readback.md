# 独立审查报告：E. 运行快照 / 帧 / 上下文 / 草稿 / 结果回读

## 1. 身份门禁

已先读取本次新建子智能体的 wire 记录，再读取项目代码：

- `agentId`：`agent-944`
- wire：`<会话证据根>/agents/agent-944/wire.jsonl`
- `profile.bind`：第 **6** 行，`modelAlias=kimi-code/k3.1-pro`
- 首条 `llm.request`：第 **20** 行，`modelAlias=kimi-code/k3.1-pro`、`model=k3.1-pro`
- 结果：**IDENTITY_PASS**，与预期完全一致。

仓库 HEAD 已核对为 `17080859f7b6905aafbbea90f6d2281913bcab9e`，与指定 `17080859` 一致。

## 2. 范围与结论摘要

审查覆盖了指定的 `RunStore`、`RunSnapshot`、`RedisFrameStore`、`RedisContextStateStore`、`CreateTaskDraftStore`、`RunRegistry`、`ClientRequestRegistry`、`read_tool_result`、`ai/context` 全部生产文件，以及闭环所需的恢复、SSE、唤醒、锁、会话清理和前端镜像/草稿代码。

| 级别 | 数量 | 结论 |
|---|---:|---|
| P0 | 0 | 未发现跨租户正文直接读取 |
| P1 | 2 | 资源锁无身份命名空间；前端草稿待写队列跨身份误清理 |
| P2 | 3 | 超时工具线程丢 locale；恢复未冻结 locale；租约墓碑无界增长 |
| P3 | 2 | `touchActiveRun` 的身份契约风险；日志节流按裸 runId 互相抑制 |

以下“已证安全”均指**本次静态调用链证据覆盖范围内**，不等于完成运行时全链路认证。

---

# 3. 确定问题

## E-01 P1：任务/作业资源锁完全没有租户和用户命名空间

**性质：确定问题。**

### 证据

`LeaseKeys` 仅按业务 `taskId` 生成锁：

- `backend/src/main/java/com/example/configmgr/ai/run/LeaseKeys.java:38-59`
  - `ai:lock:task:<taskId>`
  - `ai:lock:job:<taskId>:<JOBTYPE>`

调用链：

1. `SpToolCallingManager` 在执行前抢锁：
   `backend/src/main/java/com/example/configmgr/ai/gate/SpToolCallingManager.java:2751-2761`
2. 锁 owner 为 `instanceId:runId:toolCallId`，但仍没有身份段：
   `backend/src/main/java/com/example/configmgr/ai/gate/SpToolCallingManager.java:3263-3272`
3. `ToolResourceLocks.acquire` 分别获取任务锁和作业锁：
   `backend/src/main/java/com/example/configmgr/ai/gate/ToolResourceLocks.java:127-170`
4. `HELD_ELSEWHERE` 直接结算为 `RESOURCE_LOCKED`，不执行：
   `backend/src/main/java/com/example/configmgr/ai/gate/SpToolCallingManager.java:2752-2755`
5. UNKNOWN 对账也把同一把锁视为“目标任务仍在执行”的证据：
   `backend/src/main/java/com/example/configmgr/ai/gate/ToolUnknownReconciler.java:113-125`
   `backend/src/main/java/com/example/configmgr/ai/gate/ToolResourceLocks.java:178-191`

### 触发场景

- 租户 A 的任务 `taskId=7` 正在执行导出，写入：
  - `ai:lock:task:7`
  - `ai:lock:job:7:EXPORT`
- 租户 B 的任务 `taskId=7` 发起导出，由于跨租户业务 ID 不保证唯一，会命中同一把锁，得到 `RESOURCE_LOCKED`。
- 同一租户不同用户的同号任务也互相阻塞。
- B 的一个 UNKNOWN 工具条目对账时，A 的锁还会被解释为“B 的任务仍在执行”，形成错误的恢复证据。

这里未发现直接正文泄露，但存在确定的**跨租户/跨用户可用性干扰和恢复判据污染**；恶意或长期任务还可形成跨租户拒绝服务。

### 修改建议

- 键重划为：
  - `ai:lock:task:<tenantId>:<userId>:<taskId>`
  - `ai:lock:job:<tenantId>:<userId>:<taskId>:<JOBTYPE>`
- 给 `LeaseKeys.task/job` 增加显式身份重载；`ToolResourceLocks` 从当前已验证的 run 执行作用域取身份，禁止无身份回退。
- `ToolUnknownReconciler` 的锁探测必须使用同一身份化键。
- `ResumeService.describeKeys` 同步更新，避免观测面仍指向旧全局锁。
- 不需要枚举租户做迁移：旧全局锁仅依赖 `resource-ttl` 自然过期。
- 滚动兼容建议：
  1. 先发布“新身份锁 + 旧全局锁双写/双探测”的版本，使新旧实例仍互斥；
  2. 超过最大 `resource-ttl` 后，再移除旧全局锁写路径；
  3. 旧键只按 TTL 退出，不扫描、不按租户循环删除。
- 后续单点验证：
  - 不同租户、不同用户的同号任务可同时获得各自锁；
  - 同身份同任务跨实例只能一个执行者获胜；
  - 新旧版本并存期间互斥仍成立；
  - A 的锁不再成为 B 的 UNKNOWN 对账证据。

---

## E-02 P1：前端草稿存储键有身份，但待写清理按裸 `toolCallId`，会误删其它身份的最新输入

**性质：确定问题。**

### 证据

正式存储键是身份化的：

- `frontend/src/utils/ai-identity.ts:156-169`
  - `cm.ai.form.draft.<tenantId>:<userId>.<toolCallId>`

但 `pendingWrites` 的清理没有按完整键匹配：

- `frontend/src/utils/form-draft.ts:115-121`
  - `discardPendingFor` 遍历所有身份条目，只要 `entry.toolCallId` 相同就丢弃。
- `frontend/src/utils/form-draft.ts:249-263`
  - `saveFormDraft` 先调用该全身份清理。
- `frontend/src/utils/form-draft.ts:272-284`
  - `clearFormDraft` 也先调用该全身份清理。
- `frontend/src/utils/form-draft.ts:300-311`
  - `pruneFormDrafts` 中，当前前缀且保留的条目会 flush，**其余所有条目——包括其它身份——都会进入 `discardPendingKey`**，与注释所称“不同身份不互相清理”不符。

### 完整调用链

`FormRenderer` 修改表单
→ store 调 `scheduleFormDraft`
→ 最新值先进入 `pendingWrites`，等待 250ms 防抖落盘
→ 另一身份执行 `saveFormDraft` / `clearFormDraft` / `pruneFormDrafts`
→ 相同裸 `toolCallId` 的其它身份待写项被删除。

### 触发场景与影响

A、B 两个身份恰好有相同 `toolCallId`。A 刚修改表单，最新值还在防抖队列中；B 清除或 prune 同 ID 表单：

- A 的 `pendingWrites` 被误删；
- A 已写入 `sessionStorage` 的旧版本仍在，但**最后一次尚未落盘的修改永久丢失**；
- 不发生服务器或跨实例条件也会触发，同一浏览器页签内即可复现。

这是跨身份静默数据损坏，不只是 UI 串味。

### 修改建议

- `discardPendingFor` 改为只删除 `formDraftKey(toolCallId)` 对应的完整身份键，不遍历清除其它身份。
- `pruneFormDrafts` 的丢弃分支也必须先满足 `key.startsWith(prefix)`；其它身份条目完全不动。
- `clearFormDraft`、`saveFormDraft` 只处理当前身份的 pending/lastWriteAt。
- 无需迁移已有存储：现有正式键形已经正确，问题只在进程内队列。
- 后续单点验证：
  - A/B 同 `toolCallId`，B clear/save/prune 后 A 的 pending 仍可 flush；
  - 最终 B 的修改写入 B 的键，A 的修改写入 A 的键；
  - 切换身份时 `adoptFreshSession → pruneOrphanFormDrafts` 不影响第三身份草稿。

---

## E-03 P2：独立超时执行线程只传播身份和 trace，没有传播语言

**性质：确定问题。**

### 证据与调用链

语言已写入工具上下文：

- `backend/src/main/java/com/example/configmgr/ai/run/ResilientChatService.java:1224` 写入 `TOOL_CONTEXT_REPLY_LOCALE`。
- 外层 `executeToolCalls` 会从 Prompt 读取并设置 locale：
  `backend/src/main/java/com/example/configmgr/ai/gate/SpToolCallingManager.java:787-813`

但进入独立超时池后：

- `backend/src/main/java/com/example/configmgr/ai/gate/SpToolCallingManager.java:2916` 调 `executeWithTimeout`
- `backend/src/main/java/com/example/configmgr/ai/gate/SpToolCallingManager.java:3058-3072` 仅用 `AiTrace.wrapCallable` 和 `RunIdentityScope.open(identity)`，没有读取或设置 `replyLocale`
- 随后 `delegateSingle` 直接调用工具。
- 工具确实依赖当前线程语言，例如：
  `backend/src/main/java/com/example/configmgr/ai/tools/AiTools.java:223-226`

### 触发场景与影响

英文请求启用 `app.ai.tool.exec-timeout` 后：

- `get_config_def` 未找到编码时，在超时池线程返回中文而非英文；
- 表单/预检等其它 `ReplyLanguage.current()` 消费点也会使用默认中文或池线程残留语言；
- 连续执行不同语言请求时，存在池线程语言残留风险。

租户/用户身份在 `SpToolCallingManager.java:3065-3070` 有显式恢复，因此这里不是身份丢失，而是**语言上下文丢失**。

### 修改建议

- 在 `executeWithTimeout` 提交前调用现有 `replyLocaleOf(prompt)`。
- Callable 内保存 `LocaleContext previous`，随后：
  - 有 locale：`LocaleContextHolder.setLocale(replyLocale)`
  - 无 locale：显式 reset，避免沿用池线程旧值
- `finally` 恢复 `previous`。
- 后续单点验证：
  - 开启超时配置后，英文 `get_config_def` not-found 仍返回英文；
  - 同一线程先后执行英文、中文任务，无语言串用；
  - 同时断言租户/用户和 MDC 仍正确。

---

## E-04 P2：跨实例恢复没有冻结原始请求语言

**性质：确定问题。**

### 证据

- `RunSnapshot` 的运行冻结字段不含 locale：
  `backend/src/main/java/com/example/configmgr/ai/run/RunSnapshot.java:67-258`
- `RunStore.create` 冻结身份、provider/model/params/context，但没有语言：
  `backend/src/main/java/com/example/configmgr/ai/run/RunStore.java:1230-1264`
- `ToolExecutionEnvelope.fromSnapshot` 同样不携带 locale：
  `backend/src/main/java/com/example/configmgr/ai/gate/ToolExecutionEnvelope.java:100-108`
- 系统恢复按记录身份执行：
  `backend/src/main/java/com/example/configmgr/ai/run/StartupResumeRunner.java:1219-1223`
- 但 `ResumeService.resumeWithReason` 从当前执行线程取 locale；定时线程只能得到默认中文：
  `backend/src/main/java/com/example/configmgr/ai/run/ResumeService.java:824-829`
- 驱动线程再设置这个值：
  `backend/src/main/java/com/example/configmgr/ai/run/ResumeService.java:1752-1760`
- SYSTEM 重建和失败文案都消费它：
  `backend/src/main/java/com/example/configmgr/ai/run/ResumeService.java:2243-2266`、`backend/src/main/java/com/example/configmgr/ai/run/ResumeService.java:1090-1102`

### 触发场景与影响

英文用户的一轮在实例 A 创建快照后崩溃，实例 B 启动恢复：

- 原快照已有 SYSTEM 时，主对话语言指令通常仍能保留；
- 但若需重建 SYSTEM、生成失败文案或生成新的本地化表单，实例 B 会按默认中文执行；
- 跨实例自动恢复因此不能完整恢复原始请求上下文。

### 修改建议

- 在 `RunSnapshot` 增加 additive `replyLocale` 字段，首轮从请求边界显式冻结。
- `ToolExecutionEnvelope` 同步携带；系统恢复优先使用快照 locale，而不是当前定时线程 locale。
- 兼容策略：
  - 老快照 `replyLocale=null` 时可从既有 SYSTEM 的语言指令恢复；
  - 无法解析才回落 `zh-CN`，不要从当前线程猜测。
- 后续单点验证：英文快照跨实例恢复后，重建 SYSTEM、失败文案和表单仍为英文；老快照回退行为明确。

---

## E-05 P2：`LeaseService.ended` 墓碑没有 TTL、容量上限或常规清理，长期运行会无界增长

**性质：确定问题。**

### 证据

- `ended` 是进程内 `ConcurrentHashMap`：
  `backend/src/main/java/com/example/configmgr/ai/run/LeaseService.java:374-397`
- 注释明确写明“没有 TTL 到期或容量淘汰”：
  `backend/src/main/java/com/example/configmgr/ai/run/LeaseService.java:385-394`
- 丢租或不可知时持续写入：
  `backend/src/main/java/com/example/configmgr/ai/run/LeaseService.java:960-966`
- 清除仅发生在特定 acquire/release 分支或进程停机：
  `backend/src/main/java/com/example/configmgr/ai/run/LeaseService.java:609`、`635`、`665`、`725`、`765`、`781`、`1255`

调用链：

`renewState / verifyHeld / acquire`
→ LOST 或 UNAVAILABLE
→ `markEnded(viewKey, …)`
→ 后续 `authority` 依赖该墓碑防止旧主人重新 plain write。

### 触发场景与影响

一个长期运行的实例经历大量轮次接管、丢租或 Redis 抖动后，如果它没有再次成功 acquire/release 那些 run，`ended` 中对应条目会一直保留。容量随历史丢租轮次数增长，而非当前活跃轮次数，形成堆内存缓慢增长。

不能简单给这张表加 TTL：注释所述风险成立——过期后 `authority` 若重新落到 `NEVER_HELD`，旧驱动会重新获得 plain write 权限。

### 修改建议

- 先拆分“首轮 create 允许无租约写”与“已有 run 的写必须持有效租约”两类权限，避免依赖永久本地墓碑区分旧主人。
- 对已有 run 的写，Redis 中租约/fence 应成为权威；本地 `ended` 只作为有界加速缓存，而不是永久正确性依赖。
- 在完成上述权限重划后，再为 `ended` 加容量/TTL；缓存 miss 不得自动等同 `NEVER_HELD`。
- 不建议仅做 `clear()` 或简单 TTL，这会重新打开陈旧写窗口。
- 后续单点验证：
  - 大量丢租后内存有界；
  - 墓碑被淘汰后，旧 epoch 的写仍被 Redis fence 拒绝；
  - 新首轮 create 不被误伤；
  - 多实例接管与重新 acquire 后权限恢复正确。

---

# 4. 风险项

## E-06 P3：`touchActiveRun(RunSnapshot)` 的注释允许无请求身份恢复线程，但实现没有建立快照身份作用域

**性质：风险；当前生产调用点安全，公共方法契约存在未来误用风险。**

### 证据

`touchActiveRun(snapshot)` 本身没有 `RunIdentityScope.open(snapshot.tenantId, snapshot.userId)`：

- `backend/src/main/java/com/example/configmgr/ai/run/RunStore.java:5132-5168`

其中以下操作依赖当前线程身份：

- `renewSuspensionTtl(runId)`：`backend/src/main/java/com/example/configmgr/ai/run/RunStore.java:5140`
- `renewSessionCurrent(...)`：`backend/src/main/java/com/example/configmgr/ai/run/RunStore.java:5141`
- `touchBusinessActivity(...)`：`backend/src/main/java/com/example/configmgr/ai/run/RunStore.java:5165-5166`

只有 `renewSessionRunsIndex` 和 client-request 键显式使用快照身份：`backend/src/main/java/com/example/configmgr/ai/run/RunStore.java:5143-5155`。

当前找到的生产调用均来自 `AiController` 的 HTTP 请求线程，身份边界已经建立，因此**当前没有构成确定的跨身份写入**。但方法注释明确声称也会在无请求身份的恢复/续租线程使用，若以后直接这样调用：

- 无身份时部分续期失败；
- 线程残留身份 B 时，会给 B 的同名 run 续期或刷新活动锚点，而传入快照属于 A。

### 建议

- 方法入口建立快照身份作用域，并在当前身份与快照身份不一致时拒绝。
- 或改为所有键都通过显式 `tenantId/userId` 构造，禁止隐式读取线程身份。
- 后续单点验证：无身份线程、错误身份线程、正确身份线程分别调用，确认只触碰快照所属键。

---

## E-07 P3：三个写权限告警节流表按裸 `runId`，同 ID 的不同身份会互相抑制 WARN

**性质：确定的可观测性问题，严重度 P3。**

### 证据

- `fenceAheadWarned`、`readOnlyWarned`、`fenceStaleWarned` 都是 `Set<String>`：
  `backend/src/main/java/com/example/configmgr/ai/run/RunStore.java:330-347`
- 添加元素时只使用 `runId`：
  - `backend/src/main/java/com/example/configmgr/ai/run/RunStore.java:1898-1908`
  - `backend/src/main/java/com/example/configmgr/ai/run/RunStore.java:3135-3150`
  - `backend/src/main/java/com/example/configmgr/ai/run/RunStore.java:3197-3223`

### 场景与影响

A、B 两个身份存在同 `runId`。A 先发生 stale/fence/read-only 并记录 WARN 后，B 的同类事件只会进入 DEBUG，导致 B 的跨实例接管或拒写缺少关键告警。不会影响 Redis 隔离或写权限本身，但会削弱排障与跨租户事件归因。

### 建议

- 节流键改为 `LocalRunKey.of(runId)` 或 `RunKeyScope.indexMember(...)`，身份取当前记录作用域。
- 后续验证同 ID 的两个身份各自都能产生首条 WARN，且容量上限仍有界。

---

# 5. 已证安全的关键路径

## 5.1 Run 快照、帧、seq、pending、台账和幂等键

- run 键统一为 `<prefix>{<tenantId>:<userId>:run:<runId>}`，且拒绝花括号注入：
  `backend/src/main/java/com/example/configmgr/ai/tenant/RunKeyScope.java:38-75`
- 首轮身份来自 `RequestContextUtils.require()`，并冻结进快照：
  `backend/src/main/java/com/example/configmgr/ai/run/RunStore.java:1245-1257`
- 后续写入以快照记录身份为准，当前线程身份不一致会拒绝：
  `backend/src/main/java/com/example/configmgr/ai/run/RunStore.java:1481-1492`
- 显式身份读取还复核 JSON 内记录身份，错误归属按不存在处理：
  `backend/src/main/java/com/example/configmgr/ai/run/RunStore.java:2619-2662`
- `ai:run/runstate/pending/claim/ledger/idem/seq/xevents/lease/fence/req/decided/epoch` 都经同一 `runScopedKey`。
- `ai:xack`、`ai:xtrim` 也使用同一身份化 run key：
  `backend/src/main/java/com/example/configmgr/ai/run/RedisFrameStore.java:607-610`、`backend/src/main/java/com/example/configmgr/ai/run/RedisFrameStore.java:930-933`
- `idemField` 虽只含 `runId:toolCallId`，但其所在 HASH 已按租户+用户+run 隔离，因此不会跨身份碰撞：
  `backend/src/main/java/com/example/configmgr/ai/run/RunStore.java:1083-1091`

## 5.2 `read_tool_result` 已有多重归属校验

调用链：

`AiTools.readToolResult`
→ 从服务端 `ToolContext` 取 `runId/sessionId`
→ `RunStore.readToolResult`
→ 强制当前身份存在
→ 按当前身份读取 run 快照
→ 核对快照 sessionId
→ 核对快照内该 `toolCallId` 的模型侧截断证据
→ 才读取同 run 的 ledger/pending 全文。

对应位置：

- `backend/src/main/java/com/example/configmgr/ai/tools/AiTools.java:352-370`
- `backend/src/main/java/com/example/configmgr/ai/run/RunStore.java:4187-4245`
- `backend/src/main/java/com/example/configmgr/ai/run/RunStore.java:4281-4324`

因此它不是“仅凭 toolCallId 读全局结果”。当前身份下不存在、session 不符、无截断证据都会拒绝。

## 5.3 本地帧状态和 Pub/Sub

- `RedisFrameStore` 的进程内降级 seq、帧大小和告警状态按 `tenant:user:runId` 记账：
  `backend/src/main/java/com/example/configmgr/ai/run/RedisFrameStore.java:1807-1834`
- `RunRegistry` 使用 `LocalRunKey`，不是裸 runId：
  `backend/src/main/java/com/example/configmgr/ai/run/RunRegistry.java:47-55`、`backend/src/main/java/com/example/configmgr/ai/run/RunRegistry.java:157-175`
- 唤醒频道为 `ai:wake:<tenantId>:<userId>:<runId>`，订阅回调捕获并恢复该身份：
  `backend/src/main/java/com/example/configmgr/ai/run/RedisFrameWakeBus.java:137-149`、`backend/src/main/java/com/example/configmgr/ai/run/RedisFrameWakeBus.java:227-242`、`backend/src/main/java/com/example/configmgr/ai/run/RedisFrameWakeBus.java:272-283`
- `FrameWakeListener` 的桶和 Registration 同样保存身份，退订不依赖回调线程当前身份：
  `backend/src/main/java/com/example/configmgr/ai/run/FrameWakeListener.java:67-85`、`backend/src/main/java/com/example/configmgr/ai/run/FrameWakeListener.java:160-172`

## 5.4 Context 存储与摘要线程

- `RedisContextStateStore` 的 `ai:ctx`、`ai:ctx:ledger`、`ai:ctx:events` 全部经 `TenantKeyScope.scoped`：
  `backend/src/main/java/com/example/configmgr/ai/context/RedisContextStateStore.java:333-343`
- 压缩 version CAS、pendingCompaction、事件幂等都在同一身份化 session 键内，未发现跨身份共享压缩状态。
- 摘要池在提交时捕获身份，池线程重新建立记录作用域，同时包装 trace：
  `backend/src/main/java/com/example/configmgr/ai/context/ContextSummaryClient.java:145-155`
- 摘要直接使用本轮 `ProviderRuntime`，不读全局模型注册表，也不读写主会话记忆：
  `backend/src/main/java/com/example/configmgr/ai/context/ContextSummaryClient.java:109-141`
- 其余 `ai/context` 文件主要是纯函数/本轮对象，没有发现额外的静态正文缓存、本地文件或跨会话注册表。

## 5.5 草稿与 clientRequestId

- `CreateTaskDraftStore` 使用
  `ai:ctdraft:{<tenantId>:<userId>:session:<escapedSessionId>}`，field 才是 taskId；taskId 不需要跨租户唯一：
  `backend/src/main/java/com/example/configmgr/ai/run/CreateTaskDraftStore.java:112-129`
- runId 到 session 的归属读取也走当前身份的 run 快照，坏值按失败而非“无候选”处理：
  `backend/src/main/java/com/example/configmgr/ai/run/CreateTaskDraftStore.java:246-276`
- `ClientRequestRegistry` 使用身份化 session HASH，HSCAN 只在本会话单键内，不是跨键或跨租户扫描：
  `backend/src/main/java/com/example/configmgr/ai/run/ClientRequestRegistry.java:25-50`、`backend/src/main/java/com/example/configmgr/ai/run/ClientRequestRegistry.java:300-303`
- 完成态使用快照里的身份显式拼键，不依赖收尾线程碰巧是谁：
  `backend/src/main/java/com/example/configmgr/ai/run/ClientRequestRegistry.java:366-376`

## 5.6 恢复发现与报告缓存

- 系统恢复读全局 `ai:recovery:due`，但 member 是 `tenantId/userId/runId`，随后按每个成员的记录身份读取和驱动：
  `backend/src/main/java/com/example/configmgr/ai/run/RunStore.java:3267-3288`、`backend/src/main/java/com/example/configmgr/ai/run/ResumeService.java:488-503`、`backend/src/main/java/com/example/configmgr/ai/run/StartupResumeRunner.java:1219-1247`
- 请求面只读当前身份的窄索引：
  `backend/src/main/java/com/example/configmgr/ai/run/RunStore.java:3242-3255`
- `lastScan` 虽为进程级 last-good，但 GET 通过报告项身份切片，且候选数重新计算：
  `backend/src/main/java/com/example/configmgr/ai/run/StartupResumeRunner.java:392-452`
- `scanCursors` 键含模式与身份，并有 64 条上限：
  `backend/src/main/java/com/example/configmgr/ai/run/StartupResumeRunner.java:216-227`、`backend/src/main/java/com/example/configmgr/ai/run/StartupResumeRunner.java:834-866`

## 5.7 清理覆盖

`RunStore.deleteRun` 的 13 键之外，`ai:xtrim` 和 `ai:xack` 由当前唯一的生产调用方 `AiSessionPurgeService` 精确补充删除：

- `backend/src/main/java/com/example/configmgr/ai/session/AiSessionPurgeService.java:1194-1217`

因此当前会话销毁路径没有发现这两类帧附加键的残留；它们也不包含正文，分别是裁剪水位和短期 token→seq 映射。

---

# 6. 租户枚举 / 列表依赖专项结论

本领域**没有发现**“循环租户列表”或“先获取全部租户，再逐租户读写”的依赖：

- 系统恢复枚举的是全局**到期 run 候选**，不是租户列表；候选 member 自带真实请求冻结的身份。
- 请求面恢复只枚举当前身份的 run，不跨身份。
- `ClientRequestRegistry` 的 HSCAN 限定单个身份化 session HASH。
- `ContextConfig` 遍历的是模型配置快照中的模型行，不是租户；随后按 `(providerId, modelId)` 精确匹配：
  `backend/src/main/java/com/example/configmgr/ai/context/ContextConfig.java:70-81`
- `ai:lock:resume-scan` 是系统级扫描单飞锁，不承载某个租户的业务对象；`LeaseService.EPOCH_KEY` 也只是全局 token 编号，不是对话数据。

身份选择处的 `/api/ai/tenants` 目录属于身份边界，不是本领域恢复、快照、帧、上下文或草稿的依赖；上述路径没有调用它来决定归属。

---

# 7. 无法判定与验证边界

- 本次按要求完全只读：**未修改代码/文档，未运行测试或批量脚本，未执行 git 写操作，未启动或停止服务，未写报告文件**。
- 最终 `git status --short` 和 `git diff --stat` 均为空，HEAD 未变。
- Redis Cluster 真实竞态、滚动升级和多实例时序未做运行验证；上述“确定问题”均来自源代码及完整调用链，不声称已动态复现。
- `TenantIdentityFilter` 的身份真实性/鉴权体系不在本独立领域结论内。这里的隔离结论以其提供的租户+用户身份可信为前提；不能把本报告解读为对伪造身份头的完整安全认证。
