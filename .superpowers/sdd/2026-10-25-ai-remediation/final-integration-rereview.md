# 最终独立复审 · scoped 复核（task-8-final-fix + M-2/M-7 重评）

> **对象**：`.superpowers/sdd/2026-10-25-ai-remediation/task-8-final-fix.patch`（408 行，4 文件）与报告 `task-8-final-fix-report.md`；基线 = 主仓 `<主仓>` 当前工作区（HEAD `2eb2539` + 全部候选）。
> **性质**：只读。未改任何代码/测试/配置、未提交、未跑全量、未起服务、未读密钥。
> **树状态（复核期间变化，如实记录）**：本轮开始时补丁**未**在主仓工作区（`grep busy.unknown()` 0 命中、两个新测试不存在）；复核过程中**另一 agent 已把它落到主仓工作区**（SessionGate/AiController 现为已改状态、两个新测试已存在）。我先在隔离副本 `<隔离副本>/_t8-final-fix` 跑 7 例，随后在**主仓工作区**复跑两个新类 5 例，两者均绿（§1.2）。补丁内容未被我修改。
> **范围**：①I-1 是否真正闭合；②仅重评我上一轮的 M-2、M-7。多用户、性能 Minor（M-3/M-4）、M-5/M-6、M-1 文档漂移**不扩**。
> **前端类型 / ADR-5 文档**：按指示不计入本次判断（另一 agent 在同一面补齐）。

## 0. 结论

| 项 | 判定 | 一句话 |
|---|---|---|
| **I-1**（unknown-holder NPE / `/events/null`） | **ADDRESSED** | 守卫 + 分支 + 用例三处闭合；未知分支不再触碰 `isDeadRound`/`RunStore`/`RunRegistry`，已知分支行为不变；隔离副本 7/7 绿，`git apply --check` 通过 |
| **M-2**（park-on CONFIRM 被拒绝/取消缺 `tool_call_end`） | **升级为 Important（仅观测结算面，非数据面）** | 已证实：park-on 下拒绝与取消**确定 0 条**（拒绝在 `ConfirmGate` 无发点、取消在 `cancelTerminal` 无发点），与脚本手册"start ⇒ end"不变量及 park-off 同动作的 1 条不对称；`SheetAudit` 计数/台账**不受影响**（CONFIRM 工具无 `sheetMeta`），故不是数据/计数失真，而是"结算行丢" |
| **M-7**（60s memory-only verify 被 `MAX_ATTEMPTS` 饿死） | **升级为 Important（残留 + 永不收敛）** | 已证实：`verifyAtMs` 台账仍沿用同一 `attempts`，被 `runOnce` 的 `attempts >= MAX_ATTEMPTS` 短路；**耗尽台账 + 手工重删成功 ⇒ 复核 0 次**；主体耗 9 次后成功 ⇒ 复核**仅 1 次**。之后 pending（无 TTL）与 guard 永久驻留 |

上一轮的唯一阻断（I-1）已闭合；按用户目标（"所有工具的观测结算不丢""会话销毁 AI 数据不残留"），M-2/M-7 各自**确实**违反其中一条，因此**当前仍不应判 APPROVED**，但阻断项已从"1 项"变为"2 项且都不是数据面损坏"，修复面都很小（M-2 两处发点、M-7 一处 attempts 归属）。

---

## 1. I-1 复核 → ADDRESSED

### 1.1 闭合点（逐条对齐我上一轮报告的链）

| 我报告的问题点 | 补丁处理 | 位置 |
|---|---|---|
| `isDeadRound(busy.runId())` 收到 `null` | 前置守卫 `!busy.unknown()`，未知时**根本不调用** `isDeadRound` ⇒ 不进 `RunStore.get` / `RunRegistry.hasLiveSubscriber` / `recycleIfIdle` | `AiController.java:415-417`（补丁上下文） |
| 强释放猜 runId | 未知分支不执行 `releaseDeadHeld`，也不二次 `acquire` | 同上（仅在 else 路径） |
| 409 体 `runId=null` + `reattach=/api/ai/events/null` | 未知时**不写** `runId`/`reattach`，改写 `unknown=true`；`degraded` 保留；状态码仍 409 + `SESSION_BUSY` | `AiController.java:430-439` |
| 审计行 `heldBy=null` | 未知时 `heldBy="unknown"`（不猜、不删锁），`deny` 的 runId 仍为 null → `AiTrace.eventOn(null,…)` 已有空值保护 | `AiController.java:442-443`；`AiTrace.java:222-242` |

**全量消费点复核**（补丁后，`grep busy.runId()`）：`AiController:418/420/437/438` 全部位于 `!unknown` 分支内；`:442` 传入 `deny`（null-safe）；`:443` 三元；`ResumeService:777-784` 的 `runId.equals(busy.runId())` 对 null 天然为 `false` ⇒ 仍读成"被占用"（正确方向）。**没有残留的 null 解引用**。

### 1.2 证据

- `git apply --check --verbose task-8-final-fix.patch`（主仓工作区，只读）：4 文件全部 `Checking patch … ` 通过，无冲突。
- 隔离副本 `<隔离副本>/_t8-final-fix`（补丁已应用）实跑：
  `mvn -o -B -s maven-settings.xml -Dtest=ChatSessionBusyUnknownHolderTest,AiControllerHealthSessionLockTest,ChatSessionBusyBodyTest test`
  → **Tests run: 7, Failures: 0, Errors: 0 — BUILD SUCCESS**（3 + 2 + 2）。
  日志实证：未知分支 `event=turn_denied … status=409 code=SESSION_BUSY heldBy=unknown degraded=true`（runId 渲染为 `-`，无异常）；已知分支 `heldBy=run-holder` 且 409 体仍含 `runId`+`reattach`（`knownHolderStillCarriesTheReattachPointer`）。
- 健康面：`sessionLockLost` / `sessionLockDegraded` 两个 additive 键，且 `sessionLockDegraded = lockLost()>0 || degradedSessions()>0`（取门降级不累加 `lockLost`，只置 holder ⇒ 只按计数会漏报，实现已避开）；`AiControllerHealthSessionLockTest` 三个方向（健康 false / 取门降级 true 而 lost=0 / 续期丢锁 true 且 lost=2）全绿。

### 1.3 非阻断备注（不要求改）

1. **NPE 现场仍未被"真实对象"钉住**：`ChatSessionBusyUnknownHolderTest` 里 `RunRegistry` 仍是 mock，`ConcurrentHashMap.get(null)` 这段不会被真正执行；守卫是靠 `verifyNoInteractions(runStore)`（`isDeadRound` 第一句就调 `runStore.get`）反证。逻辑上足够，但若想留一条"删守卫即红"的硬回归，可换成真实 `RunRegistry` + 只桩 `RunStore`（可选，不阻塞）。
2. 已知分支的 JSON 键序由 `runId,degraded,reattach` 变为 `runId,reattach,degraded`；无消费方依赖键序，`ChatSessionBusyBodyTest` 仍绿。
3. `ResumeService:780` 在 unknown 时仍会写 `heldBy=null`（resume 409 体，不编造 reattach）；当前前端不读该字段，未构成假指针。

---

## 2. M-2 重评：park-on 的 CONFIRM 被拒绝/被取消 → `tool_call_end` 确实缺

### 2.1 真实调用链（两条，均为 0 条 end）

**（a）用户拒绝确认卡（park-on）**
1. 批循环为**整批**挂起条目先发 `tool_start`：`SpToolCallingManager.java:507-515`（`for (suspended) emitToolStartOnce`）→ 于是"start 存在"。
2. 首张卡进入 `awaitConfirm` → `ConfirmGate.park` 走到 park 出口 `throw new RunParkedException`（`ConfirmGate.java:678-679`）⇒ 批循环在 `SpToolCallingManager.java:608-612` 直接穿出，**不执行** `:647` 的 `ToolCallEndTrace.emitOnce`。
3. 用户点拒绝（另一请求/另一实例）：`ConfirmGate.doSubmitDecision`（`:1254-1307`）写 `status=REJECTED` + `outcome=rejected`（`:1289-1293`），锁内 `putPending` + `confirmDecision(reject)` + `signal`，随后 `maybeTriggerResume`；**全程无 `tool_call_end` 发点**。
4. 续跑重建：`ResumeService` → `resolvePending`，`shouldExecute=false`（REJECTED 不在 `:1842-1845` 的集合里）⇒ 走复用分支 `:1860` `String text = pending.getResultText()`，方法注明确写"本方法**不是** `tool_call_end` 的发点"。
5. 结论：该 toolCallId **0 条 end**（`ai:observed` 槽位也从未被 claim）。
   - 竞态例外：决策恰落在 `switchParkedLifecycle` 内（`ConfirmGate.java:645-655`）时由等待线程接手，批循环继续走到 `:647` ⇒ 此时 **1 条**。同一语义"0 或 1"。

**（b）用户停止（`POST /api/ai/cancel`）**
`AiController.cancel` → `triggerResumeForParked` → `ResumeService.cancelStateOutcome` → `ResilientChatService.cancelParked` → `cancelTerminal`：`:1287` `cancelPendings`（条目转 CANCELLED）、`:1313` `emitCancelOutcomes`（只补 `confirm_decision(cancel)`）；**无 end**。`ResilientChatService` 既没有 `SettlementObservationStore` 也没有 `SheetAudit`（`grep` 0 命中），所以现在"想补也没有入口"。

对比 park-off（同动作）：`awaitConfirm`（`SpToolCallingManager.java:1303-1311`）在拒绝后正常返回 → 批循环继续 → `:647` `emitOnce` ⇒ **1 条 end**（`outcome=rejected, executed=false`），并顺带 `SheetAudit.record`。

### 2.2 仲裁 / SheetAudit / 计数语义是否要求补上

- `ToolCallEndTrace.requiresArbitration(kind)` = `!KIND_BACKEND`（`:137-139`）⇒ **CONFIRM 在设计上就属于"要仲裁的结算"**；现在 park-on 拒绝时这条通道**从未被 claim** ⇒ 违反"挂起一次 ⇒ 一条 end"的所有权对应，不是"少了个可选日志"。
- `SheetAudit.record`（`SheetAudit.java:151-186`）的计数口径是**结算次数**（读工具结算 ⇒ `readCalls`，与 outcome 无关），但它要求 `sheetMeta` 非空才记账，而 `sheetMeta` **只**在 `ConfirmGate.submitFrontendResult` 路径写入（`ConfirmGate.java:1555` / `applySheetMeta:1547-1557`）⇒ CONFIRM 工具从来没有 `sheetMeta` ⇒ **拒绝/取消缺 end 不会影响 `/health.sheet.counters` 与 `ai:safety` 台账**。这条要把话说准：M-2 **不是**计数失真，而是"结算行（日志 + 仲裁位）丢失"。
- 可观测后果（真实、非标签）：
  1. `tool_call_end` 行缺失 ⇒ 手册 `docs/AI对话日志链路追踪手册.md` §2.5 的两条口径同时被击穿：它既不是"未执行（有 end 无 start）"，也不再满足"start 之后必定有 end"，于是**一次正常的用户拒绝在日志里与"进程死在 start 与 end 之间"不可区分**（那正是手册第 3 条用来做故障判定的形态）。
  2. `ai:observed:<runId>` 少一个 field ⇒ 若将来对账按"start 数 = end 数 = observed 数"做一致性校验，必然误报。
  3. 用户可见面**不受影响**：拒绝的卡由 `confirm_decision(reject)` 收敛，`tool_result` 帧在 park-off 才有、park-on 本就不发（前端按 decision 收敛）。
- 结论：按用户目标"所有工具的观测结算不丢" ⇒ **是丢**。定级 **Important**（观测/诊断面，非数据面；不改任何判定、不改计数）。

### 2.3 最小修复方向（不动 park-off）

- **拒绝**（覆盖最常见的 (a)）：在 `doSubmitDecision` 的 guard-winner 路径、`maybeTriggerResume` 之前补一处，与前端两条路径逐字同形：
  `if (publishesRequestsAheadOfLoop()) { settleParkedToolCall(runId, pending, pending.getResultText()); }`
  放在 `:1307` 的 `return` 之前、`submitLock` **之外**（与 `:1646-1654` 同一理由：锁内不做 Redis 往返）。
  - 幂等：走 `ai:observed` 首写仲裁 ⇒ 与"交接窗口里批循环那一条"天然只发一条（谁先 claim 谁发）。
  - park-off 不变：`publishesRequestsAheadOfLoop()` 为 false ⇒ 该调用不发生，批循环的 1 条照旧。
  - `convergeOnDecided`（`:1410-1450`，另一实例已落决策）**不要**补：帧与结算的发点归 guard 持有者，补在这里会变双发。
- **取消**（(b)）：`emitCancelOutcomes` 是唯一收口点，但它拿不到仲裁/审计面 ⇒ 需要一次接线决策（`ResilientChatService` 增加可选 `SettlementObservationStore`/`SheetAudit` 注入，或由 `ConfirmGate` 暴露 `settleCancelled(runId, entries)` 供其调用）。这是 M-2 里"贵"的一半，可单列；若本轮只做拒绝侧，删除/保留 `tool_call_end` 的缺口请**显式登记**，不要留成"看起来已收口"。

### 2.4 推荐最小确定性测试

1. `ParkedConfirmRejectSettlementTest`（park-on）：真实 `ConfirmGate` + `SpToolCallingManager`（沿用 `ParkSettlementAuditTest` 的装配），挂起一张 CONFIRM 卡 → `submitDecision(..., approved=false)` → 断言该 `toolCallId` 的 `tool_call_end` **恰 1 条**且 `outcome=rejected, executed=false, skipped=false`；再断言 `ai:observed` 该 field 存在。**今日为 0 ⇒ 红。**
2. 同文件反例一（park-off）：`properties.getPark().setEnabled(false)` + 同一批流程 → 仍 **恰 1 条**（证明修复没有把 park-off 变成 2 条）。
3. 同文件反例二（批准路径）：`approved=true` → 由续跑认领胜者发 1 条（现状已绿，防"改 reject 逻辑时顺手动了 approve"）。
4. 取消面（若做 (b)）：park 一张 CONFIRM 卡 → 走 `/api/ai/cancel` 的等价服务调用 → 恰 1 条 `outcome=cancelled, code=RUN_CANCELLED`。

---

## 3. M-7 重评：60s memory-only verify 会被 `MAX_ATTEMPTS` 饿死

### 3.1 真实调用链

1. 销毁主体成功且守卫保留后：`AiSessionPurgeService.java:441-459` → `pendingStore.scheduleVerify(sessionId, purgeId, now+60s)`。
2. `scheduleVerify`（`SessionPurgePendingStore.java:299-315`）只改 `nextAtMs=verifyAtMs` 并保留 **`pending.attempts()` 原值**（`:304`），台账键由 `SET` 写入（`REGISTER_IF_ABSENT`/`CAS_SET`，**无 TTL**）。
3. 调度器 `SessionPurgeRetryRunner.runOnce`：
   - `:182-185` `if (pending.attempts() >= MAX_ATTEMPTS) { skipped++; continue; }` —— **排在** verify 分支之前；
   - `:187-211` 才是 `if (pending.verifyAtMs() != null) → verifyMemoryOnly(...)`；
   - `:266-288` `hasExhausted` + `deferExhausted`：把耗尽项 `nextAt` 推到 `now+MAX_BACKOFF_MS(5min)` 并移到队尾（保留 `verifyAtMs`），**永远不再执行**。
4. 两个确定路径：
   - **0 次**：台账先耗尽（`attempts=10`，例如销毁期间有活跃 EXPORT 作业，`deferred` 使每轮 `recordFailure`，10 次后耗尽），此后用户/运维**手工再发一次 DELETE**：`purge()` 复用既有台账（`register` 走 `REGISTER_IF_ABSENT` 返回原记录 ⇒ `attempts=10` 保留），成功后 `scheduleVerify` 写 `attempts=10` 的 verify 台账 ⇒ 运行器**永不执行**复核。
   - **仅 1 次**：主体在第 9 次失败后成功（`attempts=9` 时才可能成功，因为 `>=10` 已不执行）⇒ 复核预算只剩 1 次；这一次若失败（守卫读失败/Redis 抖动），`recordFailure` 把 `attempts` 推到 10 ⇒ 之后同样永不执行。
5. 结果：`verifyAtMs != null` 的台账长期驻留（无 TTL）+ `SessionPurgeGuard.isArmed` 因 pending 存在**恒为 true**（`SessionPurgeGuard.java:106-123`）⇒ 该 sessionId 永久 410、历史/记忆写口永久拒绝；`ai:purge:retry:queue` 永久保留该 id。

### 3.2 可观测后果（按用户目标）

- **残留**：复核存在的唯一目的就是补"写侧在 RENAME 已生效、复检完成前 crash"的那一份 `chat:mem:*`（`AiSessionPurgeService.java:436-448` 的 fix4 注释与 `RedisChatMemoryRepository.purgeArmedAfterWrite`）。复核 0 次 ⇒ 该残留只能等 `chat:mem:*` 自身 TTL（≤ `app.ai.session-ttl`，默认 24h）到期，**销毁 API 已报 `complete=true`**。
- **永不收敛**：pending + guard + 队列项三者永久驻留；运行器每 5 分钟为它做一次 defer（有界但无终点）。
- 与上一轮 M-2 不同，这一项**触碰"删除面是否收敛"**（不是纯日志）：按用户目标"会话销毁 AI 数据不残留" ⇒ 升级 **Important**。
- 现测：`SessionPurgeRetryRunnerTest` 只有 1 例（`exhaustedPendingsAreDeferredAndDoNotStarveDueSessions`，并断言"defer 不得重置 attempts"），**verify 分支零覆盖**。

### 3.3 最小修复方向（不改普通 pending 的退避上限）

- **首选（1 个字段）**：`scheduleVerify` 构造 `Pending` 时把 `attempts` 写 **0**（并同步改该方法的 javadoc "不改 attempts"→"复核阶段重开自己的一次有界预算"）。理由：`verifyAtMs != null` 是**新阶段**（删除主体已完成），普通 pending 永远不带该字段 ⇒ 它们的 10 次上限/退避/`defer` 语义**逐字不变**（`SessionPurgeRetryRunnerTest:67` 仍绿，因为它测的是 `defer`）。
- 备选（不改 attempts）：`runOnce` 把 verify 分支**提到** `:182` 之前，并引入 `VERIFY_MAX_ATTEMPTS`（独立上限，例如 3）—— 改动更大，但语义更显式。
- 另建议（不阻塞）：台账耗尽且带 `verifyAtMs` 时打一条 WARN/`/health` 计数，避免"复核被饿死"再次成为静默。

### 3.4 推荐最小确定性测试

1. `SessionPurgeRetryRunnerTest.verifyRunsEvenWhenTheLedgerIsAlreadyExhausted`：
   以 `Pending(sessionId, purgeId, "t", MAX_ATTEMPTS, now-1, now-1h, null, now-1)` 直接铺进 fake store（`verifyAtMs` 非空 + `attempts=MAX`），桩 `purgeService.verifyMemoryOnly` 返回 `MemoryVerify(true, List.of())` → `runOnce(now)` → 断言 `verifyMemoryOnly` **被调用 1 次**、`pendingStore.complete` 被调用、台账消失；并断言 `purgeService.retry` **未被调用**。**今日为 0 次 ⇒ 红。**
2. `SessionPurgeRetryRunnerTest.verifyFailureKeepsVerifyModeAndItsOwnBudget`：`attempts=9` + `verifyAtMs`，第一次 verify 返回失败 → 断言 `recordFailure` 后仍是 verify 台账（`verifyAtMs` 保留），且下一次 `runOnce` **仍会再试**（今日第二次被 `attempts>=10` 短路 ⇒ 红）。
3. 反例（防过度修复）：普通 pending（`verifyAtMs=null`）`attempts=MAX` → 仍只 defer、**不执行** `retry()`（既有用例已覆盖，保留即回归锁）。

---

## 4. 更新后的阻断清单

| # | 项 | 面 | 判定 | 修复量 |
|---|---|---|---|---|
| I-1 | unknown-holder NPE / `/events/null` | 发送路径错误码 | **ADDRESSED**（补丁已闭合，7/7 绿） | 已交 |
| M-2′ | park-on CONFIRM 拒绝/取消缺 `tool_call_end`（0 或 1） | 观测结算（日志 + 仲裁位） | **Important（升级）** | 拒绝侧 2 行；取消侧 1 处接线（可选本轮） |
| M-7′ | 60s 记忆复核被 `MAX_ATTEMPTS` 饿死（0/1 次）+ 台账/守卫永久驻留 | 销毁残留与收敛 | **Important（升级）** | `scheduleVerify` 1 字段 + javadoc |

**未扩入**（保持上轮定性）：M-1 注释漂移；M-3/M-4 性能；M-5 导出回执；M-6 手工 resume 窄竞态；多用户专题。

---

## 5. 本次执行的命令（全部只读于主仓）

| 命令 | 结果 |
|---|---|
| `git apply --check --verbose …/task-8-final-fix.patch`（主仓工作区） | 4 文件通过，无冲突 |
| `mvn -o -B -s maven-settings.xml -Dtest=ChatSessionBusyUnknownHolderTest,AiControllerHealthSessionLockTest,ChatSessionBusyBodyTest test`（**隔离副本** `<隔离副本>/_t8-final-fix`） | BUILD SUCCESS，**7/7** |
| 同两新类在**主仓工作区**（补丁由另一 agent 落地后）复跑 | BUILD SUCCESS，**5/5**（3+2） |
| 静态核对 | `busy.runId()` 全部消费点、`doSubmitDecision`/`convergeOnDecided`/`resolvePending`/`cancelTerminal` 发点、`SheetAudit.record` 与 `applySheetMeta` 的耦合、`scheduleVerify`/`runOnce`/`deferExhausted`/`isArmed`/`purge()` 复用 attempts |

主仓生产代码与测试**我没有改动任何一行**（唯一写入物是本报告）；主仓 `target/` 因上述两次 `mvn test` 有增量编译产物。
