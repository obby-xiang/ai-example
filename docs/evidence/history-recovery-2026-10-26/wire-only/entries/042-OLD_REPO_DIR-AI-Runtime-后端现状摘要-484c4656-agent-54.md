> **历史分析 / 过程证据 — 非现行基线，非验收结论**
>
> - 归档：2026-10-26 · `docs/evidence/history-recovery-2026-10-26/wire-only/`
> - 来源：Kimi Code 会话 `<KIMI_SESSION>` / 子 agent `agent-54` 的 wire 末条 assistant 文本（只读流式解析；非原始会话转写）
> - 原始体量：19042 B / 15420 字符 / 178 行；行指纹覆盖率（仓内 + 落盘语料）5.5%
> - 类别：**过程核验**
> - 脱敏：路径 0 / 会话标识 0 / 身份 0 / 密钥 0
> - 正文为该条消息的完整文本，未删段、未改写结论；仅脱敏与行尾空白规整。当时结论不代表当前代码状态。
# <OLD_REPO_DIR> AI Runtime 后端现状摘要

**根路径约定**：`AI/` = `<REPO_ROOT>/backend/src/main/java/com/example/configmgr/ai/`（36 个 Java 文件，6092 行；含 `AiController.java` 的 527 行）。以下均以 `AI/<相对路径>:行号` 标注。

---

## 1. SSE 帧清单与端点

### 帧（`AI/run/SseChatEmitter.java`）— 全部为"无名 SSE 事件 + 帧内 `type` 字段"，每帧自动补 `runId`（L253）与 `atMs`（`AI/run/RunStore.java:400`）

| 帧类型 | 行号 | JSON 字段 |
|---|---|---|
| `start` | L72 | `type, runId, sessionId` |
| `delta` | L79 | `type, text` |
| `suspended` | L86 | `runId, toolCalls[]`（PendingToolCall.asMap）, `stateKey, pendingKey, writtenBy` |
| `heartbeat` | L97 | `putAll(data)`；**挂起期**（`AI/gate/ConfirmGate.java:213`）：`runId, suspendKind(confirm\|frontend-tool), toolCallId, name, status, waitedSeconds, timeoutSeconds`；**流式期**（`AI/run/ResilientChatService.java:295`）：`phase="streaming", attempt, upstreamEvents` |
| `tool_start` | L103 | `toolCallId, name, kind, args` |
| `tool_result` | L112 | `toolCallId, name, kind, ok, executed, executedBy, reused, result` |
| `confirm_request` | L125 | `toolCallId, name, args, summary(=args), timeoutSeconds, callback="POST /api/ai/confirm {runId, toolCallId, approved, reason}"` |
| `confirm_decision` | L136 | `toolCallId, name, decision(approve\|reject\|timeout\|cancel), approved, status, reason, waitedMs` |
| `frontend_tool_request` | L148 | `toolCallId, name, args, timeoutSeconds, callback="POST /api/ai/frontend-tool-result {runId, toolCallId, result}"` |
| `frontend_tool_result` | L158 | `toolCallId, name, ok, executed=false, result` |
| `retry` | L173 | `nextAttempt, code, reason, upstreamEvents, elapsedMs` |
| `done` | L186 | `runId, usage{promptTokens,completionTokens,totalTokens}`（空则不带）, `model, cancelled, attempts, upstreamEvents, terminalSignal`（取消时另带 `partialChars`） |
| `error` | L201 | `runId, code, message` |

回放 `replayTo(emitter, includeDelta)` 默认跳过 `delta`/`heartbeat`（L229）。心跳节拍：挂起 2s（`ConfirmGate.java:52 HEARTBEAT_SECONDS`），流式 5s（`ResilientChatService.java:96 STREAMING_HEARTBEAT_MILLIS`）。SSE emitter 超时 = `app.ai.resilience.total-budget`（`AI/web/AiController.java:513`）。

### 端点（`AI/web/AiController.java`，`@RequestMapping("/api/ai")` L77）

| 方法 路径 | 请求体 | 响应/错误 |
|---|---|---|
| `POST /chat` | `AiChatRequest(sessionId, message, context{page,taskType,step,taskId,extra})` | SSE；400 `BAD_REQUEST`、503 `AI_UNAVAILABLE`、503 `AI_REDIS_UNAVAILABLE`、**409 `SESSION_BUSY`**`{code,message,sessionId,runId,degraded,reattach:"/api/ai/events/{runId}"}`（L159-166）、503 `SUSPEND_POOL_SATURATED` |
| `POST /confirm` | `ConfirmRequest(runId, toolCallId, approved, reason)` | 200 `{accepted,duplicate,runId,toolCallId,status,approved,reason,executed,wokeInProcessGate,storedBy,note}`；409 `UNKNOWN_TOOL_CALL` / `DUPLICATE_TOOL_CALL_ID` |
| `POST /frontend-tool-result` | `FrontendToolResultRequest(runId, toolCallId, result, source)` | 200 同上但 `executed=false` |
| **`GET /events/{runId}`** | — | reattach：404 `UNKNOWN_RUN`；回放状态类帧 → 终态则 `complete` 否则 `attach` |
| `POST /cancel/{runId}` | — | `{cancelled,alreadyCancelled,redisFlagWritten,wokeInProcess,cancelKey,degraded,runExists,statusBeforeCancel,…}` |
| `GET /cancel/{runId}` · `GET /pending/{runId}` · `GET /startup-resume` · `POST /startup-resume/scan` · `GET /runs` · `GET /runs/{runId}` | — | ApiResponse |
| `POST /runs/{runId}/resume` | — | 202 ACCEPTED / 200 ALREADY_DONE\|ALREADY_CANCELLED / 404 / 503 / 409 |
| `GET /history/{sessionId}` | — | `{sessionId,count,messages[{type,text,toolCalls?,toolResponses?,timestamp?}]}` |
| `GET /health` | — | `available,code,model,baseUrl,memoryBackend,sessionTtl,confirmTimeoutSeconds,suspendPoolSize,activeSuspendGates,heldSessions,sessionLockRenewals,resilience{…},resumeOnStartup,redis,cancellations{…},instanceId,checkedAt` |

---

## 2. 挂起与续跑

**`AI/gate/ConfirmGate.java`**：状态机在 `PendingToolCall`（`AI/run/PendingToolCall.java:21-39`）：`PENDING → APPROVED / REJECTED / TIMEOUT / FRONTEND_RESULT / CANCELLED / EXECUTED`。等待循环 L116-136 每 2s：查取消 → 读 Redis 待决条目 `fresh.resolved()` → 到期 `expire`（`TIMEOUT`，reason `CONFIRM_TIMEOUT` / `FRONTEND_TIMEOUT`）→ `latch.await(2s)` 未唤醒则 `beacon.pulse` + 发 `heartbeat`。**进程内只放 `Latch`（key = `runId|toolCallId`，L304），不承载状态**；决策先写 Redis 再 `signal`。提交侧：状态非 `PENDING` 一律 `DUPLICATE`（L251）；**决策时不执行工具**，执行由 `RunStore.claimExecution` 认领。

**`AI/run/RunStore.java` 键结构**（全部 TTL = `app.ai.session-ttl`，L453）：

| 键 | 类型 | 内容 |
|---|---|---|
| `ai:run:<runId>` | STRING JSON | `RunSnapshot` |
| `ai:runs` | SET | runId 索引（惰性剔除过期） |
| `ai:pending:<runId>` | HASH field=toolCallId | `PendingToolCall` |
| `ai:claim:<runId>` | HASH（`putIfAbsent`=HSETNX，L267） | value=实例标识，**执行前原子认领** |
| `ai:ledger:<runId>` | LIST JSON | `{atMs,at,executedBy,toolCallId,name,kind,resultText}`（L289-296） |
| `ai:events:<runId>` | LIST JSON | 帧外置，窗口 `EVENT_WINDOW=3000`（L65） |

`RunSnapshot`（`AI/run/RunSnapshot.java`）：`runId, sessionId, status(RUNNING/SUSPENDED/DONE/FAILED/CANCELLED), round, attempts, cancelled, messageJson[]（完整历史，每条一个 codec JSON）, assistantContent, inFlightToolCalls[], context{}, finalText, error, createdBy, createdAtMs, updatedAtMs, resumedBy[]`。
`PendingToolCall`：`toolCallId, name, type="function", kind(CONFIRM/FRONTEND/BACKEND), arguments, status, reason, resultText, executed, executedBy, claimedAtMs, executedAtMs, requestedAtMs, resolvedAtMs`。
`instanceId` = `instance-<host>-pid<pid>-<uuid6>`（L80）。另有 `ledgerCountSince`（L352，重试副作用口径）、`cancelPendings`（L374）、`lastEventAtMs`（L419）。

**`AI/run/ResumeService.java`**：互斥 `ai:resume:lock:<runId>`（L62）SETNX ttl=totalBudget，仅自持值时释放（L428），Redis 异常回落进程内 `resuming` map。Outcome 枚举：`ACCEPTED, NOT_FOUND, ALREADY_DONE, ALREADY_RUNNING, PENDING_UNRESOLVED, IN_PROGRESS, POOL_SATURATED, ALREADY_CANCELLED`。**重建历史**（L297-324）：`codec.deserializeAll(snapshot.messageJson)` → `rebuildAssistant`（L392，id/type/name/arguments 原序）→ `chatService.buildResumedPrompt` → 逐条 `toolCallingManager.resolvePending` → `ToolResponseMessage.builder().responses(...)` → 快照转 RUNNING + `resumedBy.add`。结算标签 `settlementLabel`（L377）= `<toolCallId>:<status>:executed-here|reused(<实例>)|not-executed`。可续跑判据 L150-156；僵尸判据 `activeAtMs = max(snapshot.updatedAtMs, lastEventAtMs)`（L174）。

**`AI/run/StartupResumeRunner.java`**：`@EventListener(ApplicationReadyEvent)`（L66）→ 若 `app.ai.resume.on-startup` 则延迟 `startup-delay` 起守护线程 `ai-startup-resume` → `scan(force)`；每实例只自动扫一次（`scanned` CAS），上限 `max-on-startup`；僵尸轮带 `forceTakeover=true`；报告 `{scanned, at, instanceId, candidates, resumed[], skipped[], onStartup, zombieAge}`。

---

## 3. 韧性

**`AI/run/ResilientChatService.java`**（`@Conditional(AiKeyPresentCondition.class)`）：`runAttempts` L273-351。双超时/预算判定全在 `StreamWatchdog`（`TICK_MILLIS=1000`）；**重试三条件**实际为五合取：`violation.brokenStream() && !emitted && !sideEffect && hasNext && !overBudget`（L329）；`sideEffect = beacon.startedCount 增量>0 || store.ledgerCountSince(runId, attemptStartedAtMs)>0`（L324-325）。**完成帧校验**：`accumulator.sawTerminal()` 必须有 `finishReason`，否则抛 `INCOMPLETE`（L308-312）；`Accumulator` 留 `tail` 6 条（L574）。
错误码全集（`AI/run/StreamViolationException.java`）：`AI_RUN_CANCELLED`、`UPSTREAM_FIRST_BYTE_TIMEOUT`、`UPSTREAM_SILENT_TIMEOUT`、`TOTAL_BUDGET_EXCEEDED`、`UPSTREAM_STREAM_INCOMPLETE`、`UPSTREAM_CONNECTION_BROKEN`、`UPSTREAM_STREAM_ERROR`；`fail()` 追加后缀 `_AFTER_TOOL_SIDE_EFFECT` / `_AFTER_PARTIAL`（L486-491）；另有控制器 `AI_RUN_FAILED`、续跑 `AI_RESUME_FAILED`。`classify` 靠关键字匹配（L392-399）。
**`AI/run/ToolActivityBeacon.java`**：全进程内（`active` / `started` / `lastActivityAtMs` / `lastFinished`），`isActive` 抑制静默判定，`pulse` 刷新锚点；跨进程权威副作用在 `ai:ledger`。
**`AI/run/CancellationRegistry.java`**：**Redis 为准** `ai:cancel:<runId>` STRING（值=发起实例，TTL = totalBudget×2，L231）+ 进程内 `AtomicBoolean`/listener 仅加速；Redis 异常降级且轮询退避 `DEGRADED_POLL_BACKOFF_MS=5000`（L67）；轮询点两处：看门狗 1s、确认门 2s。

---

## 4. 会话与并发

**`AI/session/SessionGate.java`**：`LOCK_PREFIX = "ai:session:lock:"`（L42）。两道锁：进程内 `ConcurrentHashMap<String,Holder>`（恒定生效、降级本体）+ Redis `setIfAbsent` ttl=`app.ai.session.lock.ttl`；**watchdog 续期**每 `app.ai.session.lock.watchdog`（单条平台守护线程 `ai-session-lock-watchdog`，L58-62，仅续期）；`release` 仅在值等于本轮 runId 时 DELETE。Redis 异常 ⇒ `degraded=true` 回落进程内。返回 `Busy(runId, degraded)`，控制器组装 409 与 `reattach`。
**`AI/session/AiSessionService.java`**：`KEY_PREFIX = "ai:session:"`（L33）；`ensure()` = `hasKey` + SET `{sessionId,lastActivity,rounds}` 滑动 TTL；写失败只 WARN 不阻断。

---

## 5. 记忆

**`AI/memory/RedisChatMemoryRepository.java`**：`chat:mem:<conversationId>` LIST（每元素一条消息 JSON）+ `chat:mem:__ids__` SET 索引。**`saveAll` = DEL + `rightPushAll` + EXPIRE + SADD（整窗口覆盖写，L119-129）**；空列表则 DEL + SREM；`findByConversationId` = `LRANGE 0 -1`；TTL 构造注入 `app.ai.session-ttl`（`AI/config/AiModelConfig.java:78`），`setTtl` 可运行期覆盖。**已知未覆盖：无跨实例互斥（最后写入者胜）**（类注释 L49）。
**`AI/memory/MessageJsonCodec.java`**：字段 `type, content, metadata, toolCalls[{id,type,name,arguments}], toolResponses[{id,name,responseData}]` + 顶层 `timestamp`；时间戳键 `FIELD_TIMESTAMP="spikeTimestamp"`（L39）。
**收敛**（`ResilientChatService.java:508`）：去 SYSTEM → `trimKeepingToolPairs`（窗口首条不得是孤立 TOOL，L532）→ `chatMemory.clear` + `add`。

---

## 6. 上下文与工具

`AI/tool/ContextBuilder.java:25` `buildContextMessage` 输出纯文本段：`页面:` / `任务: #id [TYPE] title` / `状态:` / `当前步骤:` / `已选配置项:`（以 `, ` 连接 defCode）/ `额外上下文:`。**注意 `ctx.getExtra()` 以 Map 直接拼接输出**（L48-50）。首轮 system 文本 = `SYSTEM_PROMPT + "\n\n" + buildContextMessage`（`ResilientChatService.java:179`），单槽位拼接（避免两次 `.system()` 覆盖）。
`AI/tool/AiContext.java`：`page, taskId, taskType, step, extra{}`；`getContextKey()` → `task:TYPE/STEP` | `page:X` | `*`；`toMap`/`fromMap` 即快照 `context` 段。
`AI/tool/ToolRegistry.java`：`@PostConstruct` 扫全部 bean 的 `@Tool` 方法建 `metaMap`（`name, displayName=description 前 20 字, scopePatterns, riskLevel, channel`）与 `callbackMap`（`MethodToolCallbackProvider`）。`forContext(ctx)`（L81）按标签过滤；`buildTags`（L119）= `{"*", "page:<page>", "task:*", "task:<TYPE>", "task:<TYPE>/<STEP>"}`；`matchesAny` 为**严格字符串相等，无通配**。缺省回落：`riskOf→READ`（L105）、`channelOf→BACKEND`（L114）。
**渐进披露三重防线现状**：① 过滤 = `forContext`（仅两处调用：`ResilientChatService.java:193` 流式、`:261` 续跑）✅；② 提示词声明 = SYSTEM_PROMPT 原则 3/7（`ResilientChatService.java:112-118`）✅；③ **执行兜底 ❌ 未实现** —— `SpToolCallingManager.executeOnce`（L202）与 `resolvePending`（L289）都不复查 scope。

`AI/tools/AiTools.java` 共 16 个工具：

| 工具名 | scope | 风险 | 通道 |
|---|---|---|---|
| `list_config_defs` | `*` | READ | BACKEND |
| `get_config_def` | `*` | READ | BACKEND |
| `list_tasks` | `{"*","page:tasks"}` | READ | BACKEND |
| `get_workspace_state` | `*` | READ | BACKEND |
| `check_job_status` | `task:*` | READ | BACKEND |
| `get_row_count` | `{"*","task:EXPORT/QUERY_COND","task:EXPORT/EXPORT"}` | READ | BACKEND |
| `start_export` | `{"page:tasks","task:EXPORT/EXPORT"}` | **WRITE** | BACKEND |
| `start_precheck` | `{"page:tasks","task:IMPORT/PRECHECK"}` | **WRITE** | BACKEND |
| `start_import` | `task:IMPORT/IMPORT` | **WRITE** | BACKEND |
| `start_publish` | `task:IMPORT/PUBLISH` | **DANGER**（→确认门） | BACKEND |
| `open_export_file_editor` | `task:EXPORT/EXPORT` | READ | **FRONTEND** |
| `download_export_file` | `task:EXPORT/EXPORT` | READ | **FRONTEND** |
| `navigate_to` | `*` | READ | **FRONTEND** |
| `select_definitions` | `{"page:tasks","task:EXPORT","task:IMPORT"}` | READ | **FRONTEND** |
| `set_condition` | `{"page:tasks","task:EXPORT"}` | READ | **FRONTEND** |
| `confirm_step` | `{"page:tasks","task:EXPORT","task:IMPORT"}` | READ | **FRONTEND** |

挂起种类判定（`AI/gate/SpToolCallingManager.java:318`）：FRONTEND 优先 → DANGER → BACKEND。`needConfirm` 无独立字段，等价 `risk==DANGER`。

---

## 7. 配置与线程

`AI/config/AiProperties.java`（`@ConfigurationProperties("app.ai")`，唯一绑定该前缀）：

| 键 | 默认（yml 实配） |
|---|---|
| `app.ai.session-ttl` | `6h` |
| `app.ai.memory.max-messages` | `40` |
| `app.ai.suspend.pool-size` | `20` |
| `app.ai.hitl.timeout` | `120s` |
| `app.ai.resilience.first-byte-timeout` | `30s` |
| `app.ai.resilience.inter-event-timeout` | `90s` |
| `app.ai.resilience.max-attempts` | `2` |
| `app.ai.resilience.total-budget` | `300s` |
| `app.ai.resume.on-startup` | 代码 `false` / yml `true` |
| `app.ai.resume.startup-delay` | `8s` |
| `app.ai.resume.zombie-age` | `60s` |
| `app.ai.resume.max-on-startup` | `10` |
| `app.ai.session.lock.ttl` | `10m` |
| `app.ai.session.lock.watchdog` | `30s` |

模型侧：`spring.ai.openai.api-key=${AI_API_KEY:${DEEPSEEK_API_KEY:}}`、`base-url=${AI_BASE_URL:https://api.deepseek.com}`、`chat.completions-path=/chat/completions`、`options.model=${AI_MODEL:deepseek-flash}`、`temperature 0.2`、`max-tokens 4096`、`stream-usage: true`；`spring.ai.chat.client.enabled=false`、`spring.threads.virtual.enabled=false`。
`AI/exec/AiExecutorConfig.java`：`aiRunExecutor` = `ThreadPoolTaskExecutor`（core=max=`pool-size` 20、`queueCapacity=0`、前缀 `ai-suspend-`、`AbortPolicy`、不等任务完成即 shutdown）；`aiStreamWatchdog` = `ScheduledExecutorService` 2 条平台守护线程 `ai-stream-watchdog-N`。

---

## 8. 与规格的偏差清单

| # | 规格（`docs/s4/S4.2-AI-Runtime-合流规格.md`） | 实现现状 |
|---|---|---|
| 1 | §2 包结构标注 `com.example.quickstart.ai` | 实际 `com.example.configmgr.ai`（L26 规格行不符） |
| 2 | §2 列 `config/ReasoningAwareOpenAiChatModel` | 实际在 `java/org/springframework/ai/openai/ReasoningAwareOpenAiChatModel.java`（Override 包私有 `createRequest` 必须同包） |
| 3 | §2 列 `tools/ToolScopeFilter` | **不存在**；由 `tool/ToolRegistry.forContext` + `@ToolScope` 注解替代，且只做过滤，**无执行兜底**（三重防线第 3 道缺） |
| 4 | §2/§3 `runId 互斥锁（N4③）` | 已实现但**只在续跑路径**（`ai:resume:lock:`）；`/chat` 的 runId 为服务端 UUID，无 runId 级锁，会话级串行由 SessionGate 承担 |
| 5 | §3 步骤 7 顺序"done/error → SessionGate 释放 → 记忆 saveAll" | 实际记忆收敛在 `finish()`/`cancelTerminal()` 内、**早于 done 帧**；SessionGate 释放在控制器 `finally`（`AiController.java:182`） |
| 6 | §2 要求 reattach 端点 | 一致：`GET /api/ai/events/{runId}`，且额外做"外置帧回放 + 已完成轮次立即 complete" |
| 7 | §4 配置表 | 全部落地；**新增规格未列键** `app.ai.resume.*`（4 个）、`app.ai.session.lock.*` 已列 |
| 8 | §6"不做事件缓冲/回放" | **实际做了** `ai:events:<runId>` 帧外置（窗口 3000）+ `replayTo`，作为 reattach 前置条件（与不做清单字面冲突，属 ADR-5 补记驱动） |
| 9 | §2 控制器端点表（5 个） | 超规格新增 `/cancel/{runId}`(GET/POST)、`/startup-resume`、`/startup-resume/scan`、`/runs`、`/runs/{runId}`、`/pending/{runId}`、`/health` |
| 10 | 事件分级 | **未做**：无事件等级/严重度字段，`ai:events` 只是帧原样归档 |
| 11 | 幂等键 | 仅 `toolCallId` 级（confirm/frontend-tool-result 非 PENDING 即 409 `DUPLICATE_TOOL_CALL_ID`）；**`/chat` 无客户端幂等键**（无 clientMsgId/dedupe key） |
| 12 | §2 `session/AiSessionService` 在时序第 1 步 | 实现里 `sessionService.ensure` 在 `ResilientChatService.chat:172`（SessionGate 之后、开流之前），顺序与规格一致但位置在服务内而非控制器 |
| 13 | §2 `ResumeService` 硬规范② | 已按规格显式重建；且 `SpToolCallingManager` 按官方 `buildConversationHistoryAfterToolExecution` 口径返回 `prompt.getInstructions()+[assistant,tool]`（L158-163），修正了规格所述"PoC 丢 system/user"的来源 |

---

## 9. 其他值得注意

- **token 计量**：仅 `done` 帧带 `usage{promptTokens,completionTokens,totalTokens}`（取自最后一条响应 metadata；依赖 yml `stream-usage: true`）。**无持久化、无跨轮累计、无成本计算**；`Accumulator.tailSummary` 里的 usage 只进日志。
- **操作流水/AI 行为记录**：**无 audit 表或流水**（全后端 grep `audit`/`behavior` 在 ai 包内零命中）。仅有 `ai:ledger:<runId>`（工具执行台账，含 `executedBy`，是"恰好一次"对账依据）与 `ai:events:<runId>`（帧归档），两者都随 `session-ttl`（6h）过期，无长期留存。
- **多模态**：**无**。`AiChatRequest` 只接受 `message` 字符串；`ReasoningAwareOpenAiChatModel` 显式把 USER 媒体分支简化为纯文本（注释"本项目不涉及多模态输入"）；无附件/图片上传通道。
- **大工具结果截断**：仅**帧内**截断 `FRAME_TEXT_LIMIT = 800`（`SpToolCallingManager.java:75,355`，模型侧拿全文）；**模型侧无工具结果长度上限**，记忆侧仅靠窗口 `max-messages=40` + tool 配对保护裁剪，无单条消息截断。
- 其他：`RunRegistry`（`runId → SseChatEmitter`，重启后惰性新建零订阅者写出器，帧仍外置）；`RedisAvailability` 与 `AiAvailability` 提供开流前快速失败（`AI_REDIS_UNAVAILABLE` / `AI_UNAVAILABLE`）；模型侧 bean 全部条件化在 `AiKeyPresentCondition`（判据 `spring.ai.openai.api-key` 非空白）。
