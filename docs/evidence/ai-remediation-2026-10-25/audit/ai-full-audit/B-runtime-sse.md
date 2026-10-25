> 来源=<仓外证据>/ai-full-audit/B-runtime-sse.md（2026-10-25 归档）· 审计分片/草稿，属历史证据，不代替终稿 `docs/research/排查-AI全链路只读验收审计-终稿-2026-10-25.md`
# B｜AI 对话运行与 SSE 只读验收审计报告

- 审计对象（固定快照）：`<仓外证据>/ai-full-audit/tree`，基线 `ae883fb`（`base-sha.txt`）+ 审计开始时未提交补丁（`working.patch`，SHA256 `6c88b47d…dab955`）。
- 审计方式：只读源码 + 只读现有测试；**未启动任何服务、未运行任何测试、未连 8081/5202、未读写密钥、未派子 agent、未改动快照内任何文件**。报告写在仓外本文件。
- 硬前提（以下所有后端结论均在此前提下成立）：Redis 为 **Cluster**（多键命令必须同槽）、共享 Redis/DB/文件、**≥3 个 JVM** 同时在线、**无粘滞路由**（同一会话/同一 runId 的任意请求可落在任意实例）。
- 结论分级：**可证明正确（带条件）** / **已证实缺陷** / **具体风险** / **仅缺测试或未验证**。禁止把"测试通过"当作生产验证。
- 域外不审：`ai/context/*`、`ai/memory/*`（仅引 `saveAll` 整窗覆盖语义）、`ai/kb/*`、`ai/tool/*`、`ai/form/*`、`ai/history/*`、`ai/resultview/*`、`ai/config/*` 的 provider/key 面、`AiPanel.vue` 渲染、i18n。只审它们与 AI 调用边界的接缝。

---

## 1. 覆盖清单

### 1.1 本域生产代码（逐分支阅读）

| 分组 | 文件 | 行数 | 覆盖 |
|---|---|---|---|
| 入口 | `backend/src/main/java/com/example/configmgr/ai/web/AiController.java` | 2009 | 全（chat/events/cancel/runs/current/resume/startup-resume/confirm/frontend-tool-result） |
| 入口 | `ai/web/AiSessionPurgeController.java`；DTO `AiChatRequest/ConfirmRequest/FrontendToolResultRequest` | 128 | 全 |
| 帧通道 | `ai/run/SseChatEmitter.java` | 2206 | 全（含补丁的 per-subscriber `wakeFloor`） |
| 帧通道 | `ai/run/FrameStore.java` / `RedisFrameStore.java` / `RunStoreFrameStore.java` | 99/758/84 | 全 |
| 帧通道 | `ai/run/FrameWakeBus.java` / `FrameWakeListener.java` / `RedisFrameWakeBus.java` | 37/162/149 | 全 |
| 帧通道 | `ai/run/RunRegistry.java` / `FrameSizeLimiter.java` | 196/449 | 全 |
| 驱动 | `ai/run/ResilientChatService.java` | 2352 | 全（attempts/finish/fail/empty/cancel/park/converge） |
| 驱动 | `ai/run/StreamWatchdog.java` / `CancellationRegistry.java` / `ToolActivityBeacon.java` / `RetryBackoff.java` / `UpstreamErrorClassifier.java` | 235/368/293/112/314 | 全 |
| 恢复 | `ai/run/ResumeService.java` / `StartupResumeRunner.java` / `RunLiveness.java` | 1260/213/63 | 全 |
| 状态 | `ai/run/RunStore.java` | 2822 | 全（键族/快照/fence/租约/索引/待决/活动锚点/续期） |
| 状态 | `ai/run/RunSnapshot.java` / `PendingToolCall.java` / `SessionKeyRegistry.java` / `LeaseKeys.java` / `InstanceId.java` | 240/308/221/76/37 | 全 |
| 租约 | `ai/run/LeaseService.java` | 1146 | 全（4 条 Lua、四态 authority、墓碑、续租调度） |
| 会话 | `ai/session/SessionGate.java` | 460 | 全（取门/接管/续期/释放/强释放/降级） |
| 会话 | `ai/session/AiSessionPurgeService.java` / `SessionPurgeGuard.java` / `SessionPurgePendingStore.java` / `SessionPurgeRetryRunner.java` / `SessionPurgeDatabase.java` / `RedisClusterScanSupport.java` / `AiSessionService.java` | 1011/172/389/250/120/150/102 | 全（与恢复/判活的交界重点） |
| 相邻（边界） | `ai/gate/ConfirmGate.java` / `SpToolCallingManager.java` | 1937/1940 | park/await/triggerResume/逐帧发射（含补丁的 tool_start 前移） |
| 相邻（边界） | `ai/config/RedisAvailability.java` / `AiAvailability.java` / `AiProperties.java`（lease/session/resume/sse/stream/park 键） | — | 相关键与 fail-fast |
| 相邻（边界） | `ai/observe/RedisDegradation.java` / `AiTrace.java` / `common/sse/SseWriteBudget.java` | — | 计数/锚点/写预算 |
| 前端 | `frontend/src/api/sse.ts` | 752 | 全 |
| 前端 | `frontend/src/stores/ai.ts`（send/handleFrame/restore*/reattachActive/resumeSnapshotStream/stop/页签所有权） | 5523（相关段全读） | 全 |
| 前端 | `frontend/src/utils/parts.ts`（`applyFrameToParts`，含补丁） | 871 | 帧→段投影全 |
| 前端 | `frontend/src/api/ai.ts` / `src/types/sse.ts` | — | 端点与帧类型白名单 |

### 1.2 相邻测试（仓库内）

`ai/run` 96 文件 / 549 `@Test`；`ai/web` 34 / 222；`ai/session` 9 / 58；`ai/conformance` 13 / 78；`ai/gate` 43 / 237。前端相关 spec：`sse-watchdog`(7)、`sse-parser-boundary`(29)、`b1-suspend-disconnect`(14)、`tab-ownership-lifecycle`(10)、`concurrency-matrix`(4)、`r2-midstream-refresh`(5)、`park-request-tool-start-order`(4，新)。

逐份读过断言手法的关键文件：`run/SseChatEmitterWakeFloorTest`（新）、`run/SseChatEmitterCrossInstanceWakeTest`、`run/SseChatEmitterDrainHandoffTest`、`run/SseChatEmitterDeliveryTest`、`run/SseChatEmitterFrameStoreTest`、`run/SseChatEmitterAuthorityFailureTest`、`run/SeqPersistenceTest`、`run/RedisFrameStoreTest`、`run/RedisStreamCapacityTest`、`run/RunStoreSessionRunIndexTest`、`run/RunStoreSessionCurrentTest`、`run/RunStoreFencedWriteTest`、`run/RunRegistryIdleRecycleTest`、`run/LeaseServiceTriStateTest`、`run/LeaseEpochPerRunTest`、`run/ResumeServiceLeaseTest`、`run/StartupResumeRunnerSessionGateTest`、`run/CancelDecisionFramesTest`、`conformance/*`（`FrameContractFalsificationTest` / `ReattachLastSeqConformanceTest` / `ChatTurnFrameSequenceConformanceTest` / `ParkToolStartOrderConformanceTest` 新）、`web/AiControllerEventsParkCloseTest`、`web/AiControllerRunsCurrentTest`、`web/AiControllerZombieGateTest`、`web/AiControllerSseLifetimeTest`、`session/AiSessionPurgeServiceTest`、`session/SessionPurgeGuardFailClosedTest`、替身 `FakeFrameRedis` / `FakeLeaseRedis` / `InMemoryRunStore` / `InMemoryFrameStore`。

### 1.3 明确未覆盖 / 未审（不静默略过）

**未审（域外）**：见文首列表。

**代码分支未被任何测试覆盖（逐条确证）**：
1. Redis 抖动时 `emit` 判 `UNAVAILABLE` → 业务帧被丢、**心跳仍放行**的客户端可见后果（`SseChatEmitter` 1421-1444 + 1454-1469）。`SseChatEmitterAuthorityFailureTest` 只断言单帧不落档。
2. 唤醒补帧的**归档窗口缺口**在 emitter/前端侧的表现（仅有 `RedisStreamCapacityTest#replayBelowTheSurvivingWindowIsMarkedAsAGap` 断言服务端计数）。
3. `nextSeq` 降级为进程内计数后的**号冲突**（`RedisFrameStore` 191-196 / 741-743）；`SeqPersistenceTest` 用的是"落档失败"替身，不是号冲突。
4. **park + 用户取消**不产生终帧（`ResumeService` 689-698）。
5. 僵尸轮在**后续启动扫描**被复活并覆盖会话记忆（`StartupResumeRunner` + `ResumeService` 710-729 + `convergeMemory`）。
6. 死轮导致 purge 永远 `complete=false`、守卫/台账长期锁死（`AiSessionPurgeService` 742-760、`SessionPurgeRetryRunner` 62）。
7. 真实 Redis 5.0/Cluster 上的 Lua、`XADD` 显式 ID 被拒后的自动 ID、`MAXLEN ~`、`PUBLISH` 跨节点广播：全仓**无** Testcontainers/embedded-redis/真实 Redis 集成测试；替身按注释重写语义，CI 只有一个静态检查 `scripts/ci/forbid-redis-cluster-unsafe.mjs`。
8. `/events` 对 `RUNNING` 轮做 13 键整额续期（`RunStore.touchActiveRun` 2363-2385）—— 死轮永不过期。
9. `wakeFloor` 在本地扇出时被推进导致"晚到的小号帧永久跳过"（`SseChatEmitter` 1517-1520 + 1598-1601）。
10. `evict` 在 `emitLock` 内触发 pub/sub 退订（Redis 网络调用）（1511-1515 → 574-583 → `RedisFrameWakeBus` 86-98）。
11. 前端 409 `SESSION_BUSY` 后**不使用**后端给的 `reattach`（`stores/ai.ts` 2589-2620、2699-2703）—— 现有用例（`frontend/tests/aie2e-panel.spec.ts` 368-413）在 409 响应里给足了 `reattach`，断言却只覆盖"草稿保留/无幻影气泡"，不涉及该字段的消费。
12. 真实三实例拓扑：仓内有手工脚本 `scripts/ci/cluster3-run.sh`、`scripts/ci/cluster3-probe.mjs`、`scripts/b23l1-dual-instance.ps1`，**不在自动化测试内**，按硬约束未运行 ⇒ 只能判"未验证"。

---

## 2. 链路逐条分析

> 每条按「入口 → 身份/session/run → 共享状态/外部调用 → 正常与异常分支 → 重连/取消/故障恢复 → 用户可见结果」写。

### C1 `POST /api/ai/chat` 受理与开流

- **入口**：`AiController.chat`（299-488）。闸序：AI 可用性 503 → 必填 400 → `RedisAvailability.isAvailable()` 503（`POSITIVE_TTL_MS=0`，每次真 PING）→ 限流 429 → 归一化 → 空串 400 → 长度 400 → context 形状闸 → 模型存在性 400 → 参数 400 → **purge 守卫 410**（397-411）→ `runId=UUID` → `SessionGate.acquire` → 死轮放行 → 409 携 runId/reattach。
- **身份**：`runId` 服务端生成；`sessionId` 客户端提供，**服务端不做归属校验**。`AiTrace.bind` 在提交前装锚点（442），locale 显式搬进工作线程（452-455）。
- **共享状态**：`store.create`（`RunStore` 640-668）写快照 + `ai:runs` + `ai:session:runs:<sid>`；`session:current` 由该轮每次 `save` 联动（733-735）。
- **正常**：`start`（910-915）→ 压缩帧 → 模型流 → 终帧 → `finally`：`sessionGate.release` + `runRegistry.close`（470-474）。
- **异常**：工作线程未捕获异常 → `out.error(AI_RUN_FAILED)` + `turn_exception`（459-469）；池拒绝 → 先还门再 503（477-483）。
- **重连/取消/恢复**：本条不重连；挂起即断见 C8；进程死见 C11/C9。
- **用户可见**：`start` 带 runId；失败为白名单短句 error 帧；409 为 JSON。
- **判定**：闸序**可证明正确（条件：探活期间 Redis 可用）**。但 `SessionGate.acquire` 遇 Redis 异常会静默降级为"仅进程内互斥"（221-237）→ F-E；`/chat` 无 sessionId 归属校验 → F-M。

### C2 会话门 → 死轮放行 → 409

- **入口**：`AiController.chat` 413-435；`isDeadRound` 812-838；判据 `RunLiveness.isDead` 42-62。
- **共享状态**：`ai:session:lock:<sid>`（值 `runId|token`，TTL 10m，watchdog 30s，`SessionGate` 80-96/386-404）+ 本地门。
- **正常**：取门 → 跑 → 按 token 精确释放（269-290）；同 runId 换 token = 接管（192-237）。
- **异常（死轮）**：`acquire` 返回 Busy → 三条件判死（非活跃状态或终态 / 无租约且本进程无真订阅 / 业务活动早于 180s）→ `releaseDeadHeld` 强释放（只删 `runId` 或 `runId|*`，299-310）→ 重取门。
- **关键事实**：普通 `/chat` 轮**从不持有续跑租约**（只有 `ConfirmGate.await` 与 `ResumeService` 取），所以死轮判据里的"租约"一路对它恒 false；实际只由"无订阅 + 180s 业务静默"兜底。
- **恢复**：死轮不会因这次放行而被接管（只有启动/运维扫描会）。
- **用户可见**：≤180s 内每次发送都 409；前端只显示"发送失败"。
- **判定**：判死三条件**可证明正确（条件：阈值 > 最长合法静默；`application.yml` 236-244 给出 180s > max(120s,90s)+60s）**。缺陷在 409 的消费侧（F-B）。

### C3 出帧：取号 → 截断 → 落档 → 唤醒 → 本地扇出

- **入口**：`SseChatEmitter.emit`（1413-1524）：authority 门（1421-1444）→ `emitLock` → `frames.nextSeq`（1473）→ `FrameSizeLimiter.apply`（1482）→ `touchBusinessActivity`（1491）→ `frames.append`（1493）→ `lastFanoutSeq` 登记（1496-1497）→ `publishWake(seq)`（1498）→ 本地扇出（1500-1522）。
- **身份**：`assistantMessageId="assistant-"+runId`；`seq` 自 1、跨实例由 `INCR` 唯一；心跳独立 `heartbeatSeq`、不占号、不入档（1454-1469）。
- **共享状态**：`ai:xevents:{run:<id>}`（`XADD MAXLEN ~ <seq>-0`，`RedisFrameStore` 303-313）+ `ai:seq:{run:<id>}`。
- **正常**：单订阅者 FIFO（`drain` 1720-1736 + CAS 1672-1676）；慢订阅者隔离（每订阅者队列、`liveQueued` 计数闸 1650-1657）；溢出=主动断连（1511-1516）。
- **异常**：序列化失败跳过该帧（1483-1487）；`append` 失败只 WARN（`RedisFrameStore` 278-283）但**帧仍扇出**；PUBLISH 失败只 debug（1527-1538）；写超时/中断 → 摘除（1844-1855）。
- **用户可见**：实时流；`done/error` 后不再收新帧（1500-1503）。
- **判定**：单 writer 下"到达顺序 = seq 顺序"**可证明正确**（同一 `emitLock`）；"恰好一个终帧"在驱动线程存活时正确，park 取消除外（F-C）。authority 门在 Redis 抖动下会**静默丢正文**（F-A）。

### C4 事件日志读写（seq/frame 持久化、补帧源）

- **入口**：`RedisFrameStore`：`nextSeq` 183-197、`append` 239-284、`readFromWithStatus` 342-356、`streamFramesAbove` 399-461、`lastSeqIfKnown` 469-476、`mergeBySeq` 485-499、`windowForPayload` 668-679。
- **键族**：`ai:xevents:` / `ai:seq:` / 只读兼容 `ai:events:`，与 `deleteRun` 的 13 键同 tag 同槽（`RunStore` 1029-1063）。
- **正常**：entry ID=`<seq>-0` ⇒ 物理序=seq 序 ⇒ `XRANGE <afterSeq+1>-0 +` 精确差量；读侧夹 `from=max(afterSeq, tail-window)` 防"拿最旧窗口"（399-433）。
- **异常**：显式 ID 被拒 → 自动 ID 兜底 + WARN（268-283）；读失败折成空 + `degraded`（451-460）；末号未知返回 `empty` 而非 0（469-476）；号回退自修复（217-236）。
- **关键限制**：① 窗口=`min(6000, 字节预算推导)`，下限 256；② **落后超过窗口的读者静默跳过一段，只有服务端计数**（408-416）；③ `readFrom(...,limit)` 截断的是尾部（较新）帧（352-354），`WAKE_BATCH_LIMIT=6000` 与窗口同量级，仅当窗口被字节预算收窄时才会丢最新帧。
- **重连/恢复**：C6 的回放源。
- **判定**："读差量完整"**可证明正确（条件：读者游标落在幸存窗口内）**；窗口外是**风险**（F-D）；号降级冲突见 F-F。

### C5 唤醒（PUBLISH/SUBSCRIBE）与多订阅者扇出

- **入口**：`publishWake`（1527-1538）→ `RedisFrameWakeBus.publish`（48-56）；订阅侧 `FrameWakeListener.register/unregister/dispatch`（65-160）→ `SseChatEmitter.onWake`（1556-1613）→ `minDeliveredSeq`（1622-1633）。
- **身份**：频道按 runId；目标=每个写出器单例引用（282）；游标=**每订阅者** `Subscription#wakeFloor`（补丁新增 2168）。
- **正常**：其它实例收到唤醒 → 从 `min(all wakeFloor)` 读差量 → 逐订阅者按自己 floor 跳过已见帧 → 入队。断线即退订（580-583）。
- **异常**：PUBLISH 失败只丢唤醒（正确性由 `?lastSeq` 重连兜底）；SUBSCRIBE 失败回退登记位、下次 attach 重试（599-612 + `FrameWakeListener` 84-94）；`dispatch` 单目标异常不影响其它（151-158）。
- **用户可见**：跨实例连接能否实时看到"另一实例产出的帧"。
- **判定**：补丁的 F3 修复（读下界取最小游标）**可证明正确**（`SseChatEmitterWakeFloorTest` 3 例 + `SseChatEmitterCrossInstanceWakeTest` 7 例，均用真实 `SseChatEmitter` + 语义等价替身）。但：本地扇出会推进 floor 使晚到小号帧被永久跳过（F-G）；退订在 `emitLock` 内做 Redis I/O（F-K）；`FrameWakeListener` 类注 25-26 仍写"水位是每写出器一个"，与实现相反（F-L）。

### C6 `GET /api/ai/events/{runId}`（reattach + 差量 + 收尾）

- **入口**：`AiController.events` 740-771：`store.get` → 404 → `touchActiveRun` → `lastSeqIfKnown` 预检（不可读 503）→ `replayAndAttach` → park 且 SUSPENDED 且仍有 PENDING 时 `completeSubscriptions()`。
- **身份**：路径参数 runId；**无 sessionId 归属校验**（F-M）。
- **正常（666-714）**：`emitLock` 内**先挂订阅（含唤醒）再读回放**（I-1）；回放批豁免实时容量（`QueuedFrame.live=false`）；收尾判据=**归档里有没有终帧**（`tailFrameIsTerminal` 726-743），而非快照状态（L-143）。
- **异常**：回放读抛异常 → 摘掉刚挂的订阅再上抛（680-687）；`degraded` → 补一帧 typed error 后收尾（804-823），不按"正常空回放"complete。
- **重连/取消/恢复**：终态轮回放完即收尾；非终态且无终帧则保持挂接等实时终帧；进程死在"快照终态、done 未落档"之间 → 连接挂住，**由前端看门狗收敛**（类注 136-139 明示）。
- **用户可见**：刷新/断线后同一气泡继续写；只补 `seq > lastSeq`。
- **判定**：交接原子性、差量语义、终帧收尾**可证明正确（条件：写方存活）**；写方已死是已知边界（前端 15s 建连 + 45s 帧间 + 4 次退避，`sse.ts` 31-73/634-706）。风险：死轮被 `/events` 与 `/runs/current` 反复续期 → 永不过期（F-H）。

### C7 取消 / 超时 / 终态

- **入口**：`POST /api/ai/cancel/{runId}`（855-883）→ `CancellationRegistry.cancel`（147-154）写 `ai:cancel:<runId>`（TTL=session-ttl）；同进程 `markLocal` 唤起监听者；park 且 SUSPENDED 时 `triggerResumeForParked`。
- **共享状态**：取消标志跨实例权威，本地 `AtomicBoolean` 仅加速。
- **轮询点**：`StreamWatchdog.tick` 每 1s（148-152，**先于**"工具活跃则跳过"）、`ConfirmGate.await` 每 2s（639-653）、重试退避每 250ms（843-848）。
- **正常终态**：`cancelTerminal`（1249-1302）：先 `cancelPendings` → 半截写记忆（带 stopped）→ 快照 CANCELLED → 补结局帧（`emitCancelOutcomes` 1321-1356）→ `textEnd` → `done{cancelled:true}`。
- **超时**：双超时 + 段级预算（157-187）；段起点由 beacon 播种/重置；`awaitSettled` 只做墙钟兜底（884-910）。
- **异常**：Redis 读失败 → `UNKNOWN` → 看门狗"按未取消继续"（146-148）；失败终态 `fail()`（1378-1400，先 `textEnd` 再 `error`）。
- **用户可见**：停止按钮 → 本地立即封口 → 终帧到达后对齐。
- **判定**：跨实例取消**可证明正确（条件：Redis 读成功）**；**缺陷**：park 场景取消无终帧（F-C）；**降级口径**：Redis 写失败仍回 200 且 `wokeInProcess` 名不副实（F-J）。

### C8 park 人工等待 + 决策回灌驱动续跑

- **入口（出）**：`saveSuspended`（`RunStore` 1297-1318）→ `out.suspended` →（补丁）本批 `tool_start` 前移 → `publishRequestsForParkedBatch`（`ConfirmGate` 471-504）→ `await` → park 时还租 + `RunParkedException`（601-623）。
- **入口（入）**：`POST /confirm` → `touchActiveRun` → kind 校验 → `submitDecision`（`ConfirmGate` 1138-1192）：抢 `ai:decided` HSETNX → 复判 → 落库 → **发 `confirm_decision` 帧** → 唤醒本进程闸门 → `maybeTriggerResume`。
- **共享状态**：`ai:pending`/`ai:req`/`ai:decided`/`ai:ledger`/`ai:idem` + 续跑权租约。
- **异常**：`NOT_FOUND`/`DUPLICATE`/`TOOL_KIND_MISMATCH`；guard 被他人持有 → WAL 补齐但**不补帧**（1296-1342）；`LEASE_HELD_ELSEWHERE` → 35s 后一次性重试（1763-1777）；池饱和/会话被占 → 15 次指数退避（1706-1753）。
- **重连/恢复**：park 期间断流是正常收尾（`sse.ts` 533-537/687-691）；决策后带 `lastSeq` 重连。
- **用户可见**：卡片出现 → 决策 → 回执帧 → 续跑帧。
- **判定**：决策"恰一次"**可证明正确（条件：HSETNX guard + kind + PENDING 复判同源）**；跨实例 `/confirm` 能出帧**可证明正确**（该实例 emit → 归档 → 唤醒），但若该实例此前丢过该轮租约，回执帧会被 authority 门丢弃（F-I）。补丁的 tool_start 前移修正了帧序，代价是"本批未执行的后端工具在整段人工等待期显示 running"（补丁注释自承，语义取舍，记 F-N）。

### C9 续跑与跨 JVM 驱动接管

- **入口**：`resumeWithReason`（524-570，自抢租约）→ `resumeInternal`（613-1041）。
- **准入序**：租约 → `verifyHeld`（对 Redis 真值）→ 进程内 `resuming` 互斥 → 读快照 → 读待决（失败 → `STATE_UNKNOWN` 503）→ 取消优先 → 终态 → `RUNNING` 需 `forceTakeover` → PROVIDER_GONE 精确绑定 → 外部 PENDING → **会话门一次性 token** → `runLeaseAuthority` 只读拒绝 → 重建 SYSTEM/历史/工具响应 → 窗口/压缩 → `save(RUNNING)` → 交棒挂起池。
- **异常**：交棒后未捕获异常 → `markDriveFailure`（FAILED）+ error 帧（990-996）；池饱和 → 回 SUSPENDED + 还租（1015-1025）；终态由 `RunStore.save` 释放租约（736-739）。
- **用户可见**：决策后"继续写"；失败为白名单短句。
- **判定**：幂等/互斥/fencing 链式设计**可证明正确（条件：Lua 语义与 `created`/墓碑口径成立）**；**但**僵尸 RUNNING 接管缺少"这一轮是否仍是该会话当前轮"的校验（F-E2）。

### C10 续跑权租约 / fencing / epoch

- **入口**：`LeaseService`（acquire 518-568、ACQUIRE_SCRIPT 155-180、renewState 686-709、release 712-741、authority 797-813、墓碑 844-850）+ `RunStore.save`（690-751，FENCED_SET 299-308）。
- **语义**：值 `<epoch>|<owner>`；三键同槽 Lua 保证"取号与占用同序 + 计数器先死自愈"；`created` 决定释放权；四态 authority。
- **写口覆盖**：`save`/`emit`/`tryClaimExecution`/`appendLedger`/`recordIssuedSeq` 问 authority；`putPending`/`tryRecordDecision`/`cancelPendings`/`markRequestEmitted` **不问**（人决策类写入刻意不被 fencing 拦）。
- **异常**：UNAVAILABLE ⇒ 全部只读、下一拍可复工；LOST ⇒ 墓碑终局，只有重新 acquire 或成功 release 解除。
- **判定**：`fence ≤ epoch` 拒写、token 精确释放、墓碑不自动放行 —— **可证明正确（条件：真实 Redis 执行 Lua）**。**未验证**：4 条 Lua 在真实 5.0/Cluster 的语义（§3）。**风险**：`isHeld` 吞异常返回 false（963-974），使 `isDeadRound` 里"读租约失败按可能持有处理"的兜底成为不可达代码（F-O）。

### C11 session-run index 与启动恢复

- **入口**：`RunStore.markSessionCurrent/clearSessionCurrent/sessionCurrentRunId`（815-957）、`sessionRunIndex/backfillSessionRuns`（863-911）、`pruneRunIndex`（1213-1236）；`GET /runs/current`（974-1039）；`StartupResumeRunner.onReady/scan`（69-194）。
- **语义**：`ai:session:current:<sid>` 在 `RUNNING`/`SUSPENDED` 每次 save 时写、终态按值比对清除；`ai:session:runs` 供 purge 枚举（带 `complete` 哨兵）；`ai:runs` 按"快照键是否存在"惰性清理。
- **正常**：`/runs/current` 三分支（超时/他端已提交/进程已死）由 `pendingEntries`+`awaitingExternal`+`archiveMaxSeq` 表达；`archiveMaxSeq` 不可读回 503，绝不回 0（1030-1036）。
- **异常**：索引读失败 `/runs/current` 保守空态；`sessionCurrentRunId` 读失败返回 null。
- **启动恢复**：`ApplicationReadyEvent` → 8s → **每个实例各扫一遍** → `listRuns` 判 `resumeable` → 逐轮 `resume(runId, zombie)`；租约保证只有一个实例真正驱动；`max-on-startup=10`。
- **判定**：索引是体验面（注释自认非正确性依赖）**可证明正确**；启动扫描的租约互斥**可证明正确**。**风险**：僵尸接管不校验"会话是否已前进"（F-E2）；扫描只在启动跑一次（F-B）。

### C12 启动恢复 ↔ purge 交界

- **入口**：`DELETE /api/ai/session/{sid}`（`AiSessionPurgeController` 78-112）→ `purge`（258-328）：pending 登记 → 枚举（失败即中止、一键不删）→ 装守卫 → H2 删 → `executeDeletion`（367-431）：取消失活 run → 1.5s 等终态 → 重算 active → 记忆 → 逐 run 删 13 键或 deferred → 上下文/会话键/附件 → 守卫 retain + pending 完成。
- **覆盖**：`/chat` 410、记忆写回拒绝、完整历史写入拒绝；`ResumeService`/`StartupResumeRunner` **不查守卫**。
- **异常**：前置失败 → 撤销本次新建的 pending/守卫，一个数据键不删；有 deferred/errors → 200 + `complete=false` + `deleted=false`；自动重试 `MAX_ATTEMPTS=10`。
- **判定**：删除顺序与幂等**可证明正确**（H2 → Redis → 附件；同 tag 原子 DEL）；**交界缺陷**：死轮永久 deferred → 数据残留至 TTL、会话被 guard 锁死（F-F2）；恢复侧不查守卫的竞态窗口（F-P）。

### C13 前端：连接、重连、停止、恢复、多页签

- **入口**：`sse.ts` `streamChat`（475-545）/`reattach`（577-751）；`stores/ai.ts` `send`（2481-2646）、`handleFrame`（4880-5230）、`reattachActive`（2773-2851）、`resumeSnapshotStream`（2384-2445）、`restoreSuspendedSnapshot`（2074-2155）、`restoreInFlightRound`（2169-2202）、`stop`（4339-4410）。
- **身份**：`sessionId` 存 `sessionStorage`（页签级）；`pageId` + Web Locks + BroadcastChannel 做页签所有权；轮次身份用 `roundEpoch` + `currentAbort` + `activeRoundHostId` 三重守卫。
- **正常**：帧按 seq 去重（4930-4935）后写 parts；终帧后 `state.terminal` 闸门丢弃迟到帧（`sse.ts` 270-272）。
- **异常**：断流 → `tryReattach` 带 lastSeq；建连 15s / 帧间 45s / 4 次退避；`awaitsInput` 时收流视为正常。
- **取消**：`stop()` 本地先封口再 POST cancel，失败不回滚 UI。
- **多页签**：复制页签换新 sessionId + 清继承镜像；无 Web Locks 时判 `unsupported` 按"恢复"处理（可能共享同 sessionId → 服务端 409 兜底）。
- **判定**：单连接内"停止后不落地迟到帧""终帧后不落地任何帧""跨轮帧归属"**可证明正确**（每块前检查 abort 391-401；terminal 闸门；runId 归属 282-285）。**缺陷**：409 后放弃 reattach（F-B）；**风险**：只按 seq 去重、不做连续性判定 → 服务端缺口对用户永久不可见（F-D）。

---

## 3. 测试可信度（什么被证明、什么没有被证明）

### 3.1 真实行为 / 集成 / 桩 / mock 的分层

| 层 | 代表 | 真正证明了什么 | 不能证明什么 |
|---|---|---|---|
| 真实生产类 + 语义等价替身 | `SseChatEmitter*Test`、`ReattachLastSeqConformanceTest`、`ChatTurnFrameSequenceConformanceTest`、`SeqPersistenceTest`、`RunStoreSessionRunIndexTest`（`FakeLeaseRedis` 提供真 Redis 命令语义） | 帧序/去重/差量/交接/收尾**逻辑**；这些断言有牙（`FrameContractFalsificationTest` 逐条给出"让规则失效的实现改动"） | Redis Cluster 真实行为；真实 `SseEmitter` 的 socket 背压；多 JVM 时序 |
| 纯 mock 的 controller 单测 | `AiControllerZombieGateTest`、`AiControllerEventsParkCloseTest`、`AiControllerEventsStateUnavailableTest`、`AiControllerRunsCurrentTest`、`SseChatEmitterAuthorityFailureTest` | "某状态下调用了哪个分支/返回什么码" | 该分支在真 Redis/真线程池下的实际结果；例如僵尸判据用的是 `when(runStore.lastActivityAtMs(...)).thenReturn(0L)`，不是真锚点 |
| 进程内 H2 + 假 Redis | `AiSessionPurgeServiceTest`、`SessionPurgeGuardFailClosedTest` | 删除顺序、pending/guard 幂等、集群扫描参数（部分用 `RedisClusterNode` 替身） | Redis 真实 `DEL` 原子性、真实 SCAN 游标 |
| 前端 jsdom + 桩 fetch/EventSource | `sse-watchdog`、`b1-suspend-disconnect`、`concurrency-matrix`、`tab-ownership-lifecycle` | store/sse 状态机的分支（含"停止后不落地迟到帧"、终帧闸门、页签让渡） | 真浏览器定时器抖动、真代理 60s 空闲掐断、真 Web Locks 在多页签的调度 |
| 手工拓扑脚本（**不在 CI**） | `scripts/ci/cluster3-run.sh`、`cluster3-probe.mjs`、`b23l1-dual-instance.ps1` | 只有人跑过才有效；本次按硬约束未运行 | 不构成自动化证据 |

### 3.2 关键判定

1. **没有一条本域测试在真实 Redis 上跑过**。`FakeFrameRedis`/`FakeLeaseRedis` 以脚本文本里的注释标记（`-- LEASE_ACQUIRE` 等）分派，用 Java 重写 Lua 语义。因此 4 条 Lua（`ACQUIRE`/`NOFENCE`/`FENCED_SET`/`IDEM_PUSH`/`ACTIVITY_TOUCH`）的真实原子性、KEYS/ARGV 绑定、类型错误、CROSSSLOT 全部**未验证**；`XADD` 在 5.0.14 上"显式 ID 必须递增"这一关键行为也只由替身"按注释"模拟。
2. **没有一条测试证明 ≥3 JVM 拓扑**。跨实例相关的用例都是"两个 `SseChatEmitter` 共享一个 `FrameStore` + 一个同步 `FakeWakeBus`"，`SseChatEmitterCrossInstanceWakeTest` 自承"替身是同步回调，属更严的时序"。真实 pub/sub 是异步容器线程 + 可能的丢消息，恰好是 F-D/F-G 的触发面。
3. **测试数量不构成覆盖**：`run` 目录 549 个 `@Test`，但上述 §1.3 的 12 类分支一条都没有。尤其"死轮/redis 抖动/窗口缺口/僵尸复活"这四类**恰恰是本次审计发现问题的位置**。
4. **有"给了恢复信息但断言不涉及"的用例**：`send()` 的 409 分支测试（`aie2e-panel.spec.ts` 368-413）在响应里构造了 `runId` + `reattach`，断言却只覆盖"草稿保留/无幻影气泡"；即"后端给了重挂路径但前端不用"这一事实无法被该用例发现（见 F-B）。
5. **跳过（skip）只有一处**：`RedisChatMemoryRepositorySaveAllEmptyTest` 用 `assumeTrue(connect())` 连接外部 Redis，连不上就 SKIP —— 它不是本域测试，但也说明仓内"真 Redis"路径只能靠 SKIP。全仓 `@Disabled`/`@Ignore` 为 0（本域）。
6. **时序水分**：`SseChatEmitterDrainHandoffTest` 用可暂停的测试队列复现交错（确定性好，是该批的正面样板）；`SseChatEmitterDeliveryTest` 的慢订阅者用例用 `CountDownLatch` 控交错（同样较好），但含 `耗时 < 1000ms`、`awaitSize(...,5s)` 这类墙钟断言，在 CI 抖动下有假红/假绿空间。凡以墙钟/睡眠等待断言的地方都不能当作"生产时序已被证明"。
7. **conformance 的口径价值**：`FrameContractFalsificationTest` 为每条不变量给出 kill 行，是本仓最强的"测试有牙"证据；但它校验的是**帧流 fixture**，不校验"这条帧流在生产里一定被产出"（例如 park 取消路径根本走不到 `done`，见 F-C）。
8. **前端**：`sse-parser-boundary`(29) 覆盖 BOM/CRLF/分片等真实解析边界，质量高；`tab-ownership-lifecycle`(10) 覆盖页签让渡/复制页签，但都是进程内桩，无法证明"两台真浏览器 + 无 Web Locks"的行为。

---

## 4. 发现清单

### 4.1 汇总

| ID | 严重度 | 确定性 | 一句话 | 位置 |
|---|---|---|---|---|
| F-A | **Important** | 已证实缺陷（路径） | Redis 抖动时 authority 判 UNAVAILABLE，业务帧（含正文、终帧）被静默丢弃，**心跳仍放行**，客户端察觉不到内容缺失 | `SseChatEmitter.java:1421-1444`、`1454-1469`；`LeaseService.java:797-813`、`822-841` |
| F-B | **Important** | 已证实缺陷 | 驱动实例被 kill 后，180s 内发送只得 409，而前端丢弃 409 里的 `reattach`，只提示"发送失败"；且没有周期扫描接管 RUNNING 死轮 | `AiController.java:416-435`；`stores/ai.ts:2589-2620`、`2699-2703`；`StartupResumeRunner.java:69-108` |
| F-C | **Important** | 已证实缺陷 | park 场景的"取消"只写快照，**不产生任何终帧** → 归档违反"恰好一个 done/error"，重挂该 runId 的连接挂到看门狗耗尽 | `ResumeService.java:689-698`；`AiController.java:873-878` |
| F-D | **Important** | 具体风险 | 归档窗口/字节预算裁剪造成的**中段缺口只有一个服务端计数**，没有帧内标记；前端只按 seq 去重 → 正文静默少一段、且重连也补不回 | `RedisFrameStore.java:408-416`；`FrameStore.java:58-65`；`stores/ai.ts:4930-4935` |
| F-E2 | **Important** | 具体风险 | 被 kill 的旧 RUNNING 轮在**后续启动扫描**被当僵尸接管：重建旧历史 → 整窗覆盖会话记忆，并把 `/runs/current` 指回旧轮；前端刷新会清空并重放旧轮 | `ResumeService.java:326-349`、`710-729`；`RunStore.java:733-735`；`ResilientChatService.java:1414-1490`；`RedisChatMemoryRepository.java:304-340` |
| F-F2 | **Important** | 已证实缺陷（条件：驱动进程已死） | 死轮永远不终态 ⇒ purge 永远 `complete=false`（deferred `RUN_ACTIVE`），重试用尽后 pending+guard 常驻 ⇒ 同 sessionId 永久 410、数据残留至 TTL | `AiSessionPurgeService.java:367-431`、`742-760`；`SessionPurgeRetryRunner.java:62`；`SessionPurgeGuard.java:106-123` |
| F-G | **Important** | 具体风险 | `wakeFloor` 在**本地扇出入队时**推进，唤醒补帧按自己 floor 跳过 ⇒ 晚到的小号帧对该订阅者**永久缺失**；客户端重连只带 max(lastSeq)，同样补不回 | `SseChatEmitter.java:1517-1520`、`1564-1573`、`1598-1601` |
| F-F | **Important** | 具体风险 | `nextSeq` 在 Redis 失败时降级为**进程内计数**；同 run 已有帧时从 1 起号 ⇒ 回放 `mergeBySeq` 同号合并丢帧；两实例同时降级会互撞 | `RedisFrameStore.java:183-197`、`741-743`、`485-499` |
| F-O | **Important** | 具体风险 | Redis 中断超过租约 TTL(30s) 后，驱动实例的 `renew` 返回 0 ⇒ **LOST 墓碑终局**、永久只读；而该轮是 RUNNING，`triggerResume` 只认 SUSPENDED ⇒ 无人接管，流静默停摆 | `LeaseService.java:686-709`、`844-850`；`ConfirmGate.java:1614-1618`；`SseChatEmitter.java:1437-1443` |
| F-I | Minor | 已证实缺陷 | 非驱动实例（曾丢租）上 `/confirm` 会写决策但 authority 丢回执帧；`registry.of()` 每次新建 emitter 条目从不回收（除死轮判定） | `ConfirmGate.java:1186-1191`；`SseChatEmitter.java:1437-1443`；`RunRegistry.java:138-153` |
| F-H | Minor | 已证实缺陷 | `/events` 与 `/runs/current` 对 `RUNNING` 也做 13 键整额续期 ⇒ 死轮被用户刷新无限续命（24h/次），purge 永远 deferred | `RunStore.java:2339-2385` |
| F-K | Minor | 具体风险 | 溢出摘除在 `emitLock` 内触发 pub/sub 退订（Redis 网络调用），违背"锁内不做 Redis I/O" | `SseChatEmitter.java:1511-1515` → `574-583` → `RedisFrameWakeBus.java:86-98` |
| F-J | Minor | 已证实缺陷 | 取消在 Redis 写失败时仍 200 `cancelled:true`，`wokeInProcess` 实为"本地标志本次翻转"而非"真的唤起了监听者" | `CancellationRegistry.java:147-154`、`232-247`、`294-305` |
| F-N | Minor | 语义取舍 | 补丁把本批 `tool_start` 前移后，**未执行的后端工具**在整个等待期显示 running（补丁注释自承） | `SpToolCallingManager.java:490-493`、`726-730` |
| F-L | Minor | 文档漂移 | `FrameWakeListener` 类注仍写"水位按写出器"（与补丁的 per-subscriber 相反） | `FrameWakeListener.java:25-26` |
| F-P | Minor | 具体风险 | purge 与启动/运维续跑之间无互斥、恢复侧不查 purge 守卫 | `ResumeService.java:613-1041`；`AiSessionPurgeService.java:258-328` |
| F-M | Minor | 未验证/域外 | `sessionId`/`runId` 无服务端归属校验，多用户隔离只依赖 UUID 不可猜 | `AiController.java:740-771`、`1251-1330` |

### 4.2 详述（Important）

#### F-A｜Redis 抖动期业务帧被静默丢弃，心跳照常（正文静默缺段）

- **确定性**：已证实缺陷（代码路径确定；触发只需本进程内任一租约命令失败 + Redis 仍在抖动）。
- **证据**：
  - `SseChatEmitter.emit` 在**取号之前**判 `store.runLeaseAuthority(runId)`，`readOnly()` 为真即 `return`（`SseChatEmitter.java:1421-1444`）；心跳帧被显式豁免（1422）。
  - `LeaseService.authority` 对"从未持租"的 run 返回 `facilityHealthy() ? NEVER_HELD : UNAVAILABLE`（797-813）；`facilityHealthy()` 只在 `facilityHealthy=false` 时按 1s 节流打一次 `PING`，失败则继续返回 false（822-841）。
  - `facilityHealthy` 是**进程级**：`acquire/verify/renew/release/leaseOk` 任一处异常都会把它置 false（563、655、703、737、994）。
  - `RunStore.save` 同样在入口 `readOnly()` 时直接返回（695-699），终态写回也会被跳过。
- **触发步骤**（普通纯文本轮，无租约、无卡片也成立）：
  1. 实例 A 上某轮（或同 JVM 内任一挂起轮/决策/续租/僵尸判定）触发一次租约命令异常（Cluster 单槽瞬断、`CLIENT PAUSE`、超时）。
  2. Redis 仍在抖动（同一秒内）：A 上正在流式的普通 `/chat` 出帧 → `authority()` → `facilityHealthy()` → `PING` 失败 → `UNAVAILABLE` → **所有业务帧不取号、不落档、不投递**；同时心跳（`heartbeat`）照常广播（1454-1469）。
  3. Redis 恢复后帧恢复。期间模型已产出的 `delta` **既没归档也没投递**，且**不占 seq**。
- **用户可见结果**：气泡看起来"卡了一下又继续"，但**中间一段正文永久缺失**；由于心跳不断（挂起期 2s / 流式期 5s），前端 45s 帧间看门狗不触发，用户与前端都无法感知；断线重连按 `lastSeq` 差量也补不回（这些帧从未取号）。
- **对应测试**：`run/SseChatEmitterAuthorityFailureTest` 只断言"LOST/UNAVAILABLE 时该帧不落档"（用 `mock(RunStore.class)`），**没有**断言"心跳仍发 + 正文缺失 + 客户端不可感知"这一组合；**缺失测试**：模拟 `facilityHealthy=false` + PING 失败 + 并发 `delta`/`done`，断言客户端收到的文本与"模型产出"不等且无任何缺口信号。
- **为什么不是 Critical**：窗口受 `PING` 节流限制（≥1s 且需 Redis 持续不可用）；且不导致错误答案，只是缺段。但它与"AI 对话内容完整性"直接冲突，故列 Important。

#### F-B｜kill 之后：180s 的 409 + 前端丢弃 reattach + 无周期扫描

- **确定性**：已证实缺陷（三处代码事实确定；用户影响时长由 `zombie-age=180s` 决定）。
- **证据**：
  - `/chat` 在会话锁被死轮占用时返回 409，且**明确携带** `runId` 与 `reattach:"/api/ai/events/<runId>"`（`AiController.java:424-434`）。
  - 前端拿到 `SESSION_BUSY` 后：`handleChatError` 只 `setNotice('ai.notice.sendFailed')`（`stores/ai.ts:2699-2703`），并回收乐观气泡（2616-2620）；**从不**使用 `error.body.reattach`：类型侧仍声明该字段（`frontend/src/types/api.ts:235-240`，注释写明"前端据此改走 /api/ai/events/{runId}"），但 `stores/ai.ts` 的 `SESSION_BUSY` 分支（2589-2620、2699-2703）与 `handleChatError` 均无读取者 —— 声明与消费脱节。
  - `isDeadRound` 要求 `now - max(updatedAt,lastActivity) > 180s`（`RunLiveness.java:50-62`）；在此之前一律 409。
  - 唯一的自动接管是 `StartupResumeRunner.onReady`（`@EventListener(ApplicationReadyEvent)`，69-101），且 `scan(false)` 每实例只跑一次（104-108）；运行中的实例**不会**周期扫描。
- **触发步骤**：实例 A 流式中被 kill（SIGKILL / OOM / 滚动重启未 drain）→ 用户在同会话继续发送（落 B 或 C）→ `SessionGate.acquire` 命中 A 残留锁 → 180s 内 409 → 前端"发送失败" → 用户重复点击仍失败；180s 后才放行新轮，旧轮仍停在 RUNNING。
- **用户可见结果**：最长 3 分钟无法继续对话，且提示语与真实原因（上一轮进程已死）无关；刷新页面才能看到在途轮（走 `/runs/current`）。
- **对应测试**：`web/AiControllerZombieGateTest`（mock `SessionGate`/`RunStore`，断言 200/409 与 `releaseDeadHeld`）证明后端放行逻辑，**不证明**前端恢复路径；前端 409 用例（`aie2e-panel.spec.ts:368-413`）提供了 `reattach` 但只断言草稿保留 —— 测试因此发现不了该字段无人消费。**缺失测试**：kill 后"同会话再次发送"的端到端（后端 409 → 前端应改走 `/events/{runId}` 或提示刷新）。

#### F-C｜park 场景的"取消"不产生终帧

- **确定性**：已证实缺陷（单一代码路径，无竞态要求）。
- **证据**：
  - park 期间没有等待线程（`ConfirmGate.await` 601-623 直接 `throw RunParkedException`）。
  - 用户点停止 → `POST /api/ai/cancel` → 若快照 `SUSPENDED` 则 `triggerResumeForParked`（`AiController.java:873-878`）→ `ResumeService.resumeInternal` 读到取消标志后：写 `CANCELLED` 快照 + `cancelPendings` + **直接 return**（`ResumeService.java:689-698`），**没有 `out.done/out.error`**。
  - 唯一会发 `done{cancelled:true}` 的是 `cancelTerminal`（1249-1302），它只在**驱动线程内**被调用（`runAttempts` 的 catch 750-752）。
- **触发步骤**：一轮因确认卡/表单 park（无驱动线程）→ 用户点"停止" → 快照 CANCELLED、条目 CANCELLED，但事件日志最后一帧仍是 `tool_start/confirm_request`（或更早）。
- **用户可见结果**：当前页签因为 `stop()` 已本地封口，看起来正常；但**任何**随后 reattach 该 runId 的客户端（第二页签、刷新后的 `resumeSnapshotStream`、前端重连）都要等 `45s × (1+4) + 15s×5` 才收敛为"结果可能不完整"，而不是立刻拿到终态。对协议而言，这一轮的归档违反"恰好一个 `done`/`error` 且它是最后一帧"，`FrameContract` 的不变量在此路径上不成立。
- **对应测试**：`run/CancelDecisionFramesTest`（驱动线程路径的补帧）、`conformance/ChatTurnFrameSequenceConformanceTest#cancelledTurnEndsWithDoneCancelled…`（同样是驱动线程路径）。**缺失测试**：park→cancel 后端到端，断言归档里有且仅有一个终帧。

#### F-D｜归档窗口缺口对客户端不可见（静默少正文）

- **确定性**：具体风险（需要"读者游标落后于幸存窗口"，长答案 + 慢/迟重连即可）。
- **证据**：
  - 读侧把下限夹到 `tailSeq - window`，并只记 `RedisDegradation.C1_STREAM_REPLAY_GAP`（`RedisFrameStore.java:399-416`），返回值里**没有缺口标记**；`FrameStore` 接口也明确"空结果≠证明没有帧"（`FrameStore.java:58-65`）。
  - 窗口 = `min(6000, byteBudget÷(载荷+256B))`，下限 256（`RedisFrameStore.java:668-679`、`MIN_WINDOW=256`）；`delta` 已入档（B1），长答案/思考段会产生数千帧。
  - 前端只做"同序号去重"，**明确不做连续性判定**（`stores/ai.ts:4930-4935`），`lastSeq` 取 max（`api/sse.ts:287-290`）。
- **触发步骤**：用户断线（或慢连接被摘除）→ 该轮继续产出 > window 帧 → 用户带 `lastSeq` 重挂 → 服务端从幸存窗口头开始返回（中间一段被跳过）→ 前端把缺口当连续文本拼接。
- **用户可见结果**：回答/思考段中间**静默缺一段文字**，没有任何"内容不完整"标记；`tool_result` 若落在缺口里则整张工具卡消失。刷新（`lastSeq=0`）同样只能拿到幸存窗口，无法补回。
- **对应测试**：`run/RedisStreamCapacityTest#replayBelowTheSurvivingWindowIsMarkedAsAGap` 只断言"计数 +1"，以及 `RedisFrameStoreTest` 的窗口/裁剪语义；**没有任何**测试断言"前端会因此缺正文"或"应当可见地告警"。这属于"测试证明了一个计数器，而不是用户面正确性"。

#### F-E2｜僵尸 RUNNING 轮被后续启动扫描复活，整窗覆盖会话记忆

- **确定性**：具体风险（前提：驱动进程死亡、session 空闲、>180s、任一新实例启动或人工 `/startup-resume/scan`）。
- **证据**：
  - `listRuns` 把 `RUNNING && now-activeAt > zombie-age` 判为 `zombieRunning`，`resumeable=!cancelled && 无外部 PENDING && !terminal && (zombie || !RUNNING)`（`ResumeService.java:326-349`）。
  - `StartupResumeRunner` 对 `zombie=true` 调 `resume(runId, true)`（161），`resumeInternal` 走 `forceTakeover` 分支（710-729），**只校验快照自身**，不校验"该会话是否已有更新的一轮"。
  - 续跑期间 `RunStore.save(RUNNING)` 会把 `ai:session:current:<sid>` 写回旧 runId（`RunStore.java:733-735`）。
  - 轮末 `convergeMemory` → `persistMemory` → `ChatMemoryRepository.saveAll`，而 `RedisChatMemoryRepository.saveAll` 是 **staging + RENAME 的整窗替换**（304-340）——旧轮的完整历史会覆盖掉用户之后已完成的新轮所写下的记忆。
  - 前端刷新时 `restoreSuspendedSnapshot → restoreInFlightRound`：清空宿主消息段并 `lastSeq=0` 全量重放（`stores/ai.ts:2169-2202`），于是**旧轮的部分回答会覆盖掉新轮已完成回答**的本地展示。
- **触发步骤**：A 流式中被 kill（快照 RUNNING）→ 用户在 B 上重新发送并正常完成（memory 已含新轮）→ 任一实例重启（或运维触发扫描）→ 旧轮被判僵尸并接管 → 旧轮跑完写记忆 → 用户下一问的上下文回到旧轮；若此时用户刷新，界面被旧轮重放覆盖。
- **用户可见结果**：模型"忘记"最近一轮（甚至答非所问）；界面出现"已回答完的问题又变成半截"的现象；`/history` 与记忆窗口不一致。
- **对应测试**：`run/StartupResumeRunnerSessionGateTest`（1 例，mock 掉 chatService/executor）、`run/ResumeServiceZombieThresholdTest` 只覆盖阈值判定；**没有**测试覆盖"旧轮接管 + 会话已前进"的组合。

#### F-F2｜死轮使会话销毁永不完成，并把 sessionId 永久锁死

- **确定性**：已证实缺陷（条件：该会话存在一个无人收敛的非终态 run）。
- **证据**：
  - `deleteOrDefer` 的 active 判据 = 快照非终态（`AiSessionPurgeService.java:742-760`），死轮无人写终态 ⇒ 恒 `deferred(RUN_ACTIVE)`。
  - `awaitTerminal` 最多等 1.5s（91-93、657-685），等不到即 deferred；`executeDeletion` 有 deferred ⇒ `complete=false`（388-396），保留 pending 与守卫。
  - 自动重试上限 `MAX_ATTEMPTS=10`（`SessionPurgeRetryRunner:62`、179），用尽后不再重试。
  - 守卫 `isArmed` 同时看 guard 键与**durable pending**（`SessionPurgeGuard:106-123`）⇒ 同 sessionId 的 `/chat` 永久 410（`AiController:397-411`）。
- **触发步骤**：A 被 kill（run 停在 RUNNING）→ 用户点"删除会话"/前端换会话触发 purge → 返回 `complete=false, deferred=[{runId, reason:RUN_ACTIVE}]` → 10 次重试仍失败 → 该 sessionId 的 AI 数据残留至 24h TTL，期间同 id 一律 410。
- **用户可见结果**：销毁"看似成功但没删干净"；原页签若继续用旧 sessionId 会得到"会话已销毁，请新建会话"（前端会自动换新 id，体验尚可），但服务端数据与 pending/guard 长期滞留。
- **对应测试**：`session/AiSessionPurgeServiceTest` 覆盖"活跃 run 延后 + 重试补删"，其"活跃"用的是**会收敛**的 run；**缺失测试**：死轮（永不终态）+ 重试用尽 + guard 仍 armed 的终态。

#### F-G｜`wakeFloor` 在本地扇出时推进，晚到的小号帧永久跳过

- **确定性**：具体风险（前提：同一 run 在一段时间内被 >1 个实例出帧，即 fencing/takeover 窗口或 Lua 降级）。
- **证据**：`emit` 在本地入队成功后把 `sub.wakeFloor` 直接置为本地 `liveSeq`（`SseChatEmitter.java:1517-1520`）；`onWake` 对 `seq <= sub.wakeFloor` 的帧 `continue`（1598-1601）；`minDeliveredSeq` 用同一游标做读取下界（1622-1633）。设计注释已承认"晚到的**低号**帧也没有差量…不会被实时补发"（1564-1573），但把前提写成"协议要求"。
- **触发步骤**：实例 B 先产出 `seq=100`（本连接收到并推进 floor=100）→ 实例 A（尚未观察到丢租）产出 `seq=99` 并落档 → A 的唤醒到达 B → `99 <= 100` → 跳过；客户端 `lastSeq` 已是 100 → 重连也只会要 `>100`。
- **用户可见结果**：该客户端永久缺失 `seq=99` 这一帧（正文一小段/一条工具帧），且**表现为"没有发生"**。
- **对应测试**：`run/SseChatEmitterWakeFloorTest` 覆盖的是反方向（避免重复/避免饿死）；**没有**覆盖"本地 floor 推进导致小号帧被跳过"。

#### F-F｜seq 进程内降级导致号冲突 → 回放丢帧

- **确定性**：具体风险（前提：`INCR ai:seq` 失败，而同一轮此前已发过号）。
- **证据**：`nextSeq` 捕获异常后用 `fallbackSeq(runId).incrementAndGet()`（`RedisFrameStore.java:191-196`），而 `fallbackSeq` 初始化 = `max(0, lastSeq(runId))`（741-743）——**Redis 读不出末号时就是 1**。回放侧 `mergeBySeq` 以 seq 为键去重、同号以 Stream 为准（485-499）⇒ 两个不同帧同号时**其中一帧被丢掉**。`FrameStore` 接口注释也承认"降级期间与已发放号空间可能重叠，登记为观察项"（40-42）。
- **触发步骤**：Redis 部分失败（`INCR` 超时但 `XRANGE` 可用）→ 该实例继续出帧，号从 1 重来 → 稍后重挂/刷新者读归档：同号帧只剩一帧；`lastSeq` 同时"倒退"到小号区间。
- **用户可见结果**：重连/刷新后正文缺段或顺序错乱；若撞上 `done`，可能出现"有 done 但正文不全"。
- **对应测试**：`run/SeqPersistenceTest` 覆盖"发放 3 帧、落档失败 2/3 帧 → 新实例续到 4"，**不是**号冲突场景。

#### F-O｜Redis 中断超过租约 TTL 后，驱动实例永久只读且无人接管（RUNNING 静默停摆）

- **确定性**：具体风险（前提：续跑/挂起驱动的轮，Redis 不可用 > `lease.ttl=30s`）。
- **证据**：
  - `renewState` 的 Lua 在键不存在/已换主时返回 0 ⇒ `RenewOutcome.LOST` + `markEnded(LOST)`（`LeaseService.java:686-709`、844-850）；`authority` 对 LOST 直接返回只读（803-805），**墓碑只能由成功 acquire/release 清除**（类注 389-405 明示"没有 TTL 到期或容量淘汰"）。
  - LOST 后 `emit` 丢弃一切业务帧（含终帧），`save` 丢弃快照写入（`SseChatEmitter:1437-1443`、`RunStore:695-699`）。
  - 该轮的快照是 `RUNNING`；死卡兜底 `ConfirmGate.triggerResume` 要求快照 `SUSPENDED`（1614-1618）⇒ **不会**被触发；启动扫描只在实例启动时发生。
  - `renewTick` 的 LOST 分支会 `held.remove` + `stopDriving`（935-941），之后本进程再无续租者，也不会重新抢租。
- **触发步骤**：一轮已进入续跑（或挂起等待持租）→ Redis 中断 40s（超过 30s TTL）→ 恢复后下一拍 `renew` 返回 0 → LOST 墓碑 → 该实例此后不再产出任何帧/终态；其它实例也没有触发源（快照 RUNNING）。
- **用户可见结果**：页面停在"生成中"，45s 后前端判中断并重挂 4 次；重挂只回放出停摆前的帧，**永远等不到 done**；只有人工 `POST /api/ai/runs/{id}/resume`（需 snapshot 非 RUNNING 才会 202，实际会 409 `ALREADY_RUNNING`）或 `POST /api/ai/startup-resume/scan`（180s 后按僵尸接管）能救。
- **对应测试**：`run/LeaseServiceTriStateTest`、`run/LeaseLossReadOnlyTest`、`run/ResumeDriveFailureTerminalTest` 覆盖"丢租后只读"与"驱动异常落 FAILED"，**没有**覆盖"丢租后既无终态又无接管源"的组合；也没有任何"Redis 中断 > TTL"的测试。

### 4.3 详述（Minor）

#### F-I｜非驱动实例的两处副作用（回执帧丢失 + emitter 条目泄漏）

- `POST /confirm` 落在实例 C：`ConfirmGate` 先 `store.putPending`（不做 authority 判），再 `registry.of(runId).confirmDecision(...)`（1186-1191）。若 C 曾持有该轮租约并丢过（LOST 墓碑），`emit` 直接 `return`（1437-1443）⇒ **回执帧既不落档也不广播**，而决策已生效。用户在两页签/刷新后看不到"已批准"，直到续跑产出 `tool_result` 才收敛。
- `RunRegistry.of` 每次 `computeIfAbsent` 新建条目（138-153），只有 `AiController.chat` 的 `finally`、`ResumeService` 驱动的 `finally` 会 `close(runId)`（190-194 移除条目）。`/events` 与 `/confirm` 路径**只 of 不 close**；`recycleIfIdle` 只在死轮判定里调（`AiController:835`）。⇒ 每个被本实例 serve 过 reattach/决策的 runId 都留下一个永不回收的条目。
- **用户影响**：回执延迟（F-I 前半）可感知；条目泄漏是内存层面（每条约几百字节，随 run 数线性增长）。
- **对应测试**：`RunRegistryIdleRecycleTest` 只测 `recycleIfIdle` 本身，没测"哪些路径该调用它"。

#### F-H｜`RUNNING` 死轮被读流量无限续命

- `/events`（`AiController:748`）与 `/runs/current`（990）都调 `touchActiveRun`，后者对**任何非终态**（含 RUNNING）执行 `renewSuspensionTtl` + `READ` 续期（`RunStore:2363-2385`）。因此用户每刷新一次，死轮的 13 键再活 24h；purge 的 `RUN_ACTIVE` deferred 也就永远不会因为"键过期"而自愈。
- 注意：该调用**不会**刷新业务活动锚点（只对 SUSPENDED 刷，2381-2383），所以判死仍能在 180s 后成立 —— 这是刻意设计，但把"死轮数据自动过期"这条兜底彻底关掉了。
- **缺失测试**：无。

#### F-K｜溢出摘除在 `emitLock` 内做 pub/sub 退订

- `emit` 在 `synchronized (this.emitLock)` 内因队列溢出调 `evict`（1511-1515）→ `closeSubscription`（574-583）→ `syncWakeSubscription` → `FrameWakeListener.unregister`（109-125）→ `RedisFrameWakeBus.unsubscribe` → `container.removeMessageListener`（86-98）。`FrameWakeListener` 的 M-6 只把 **register** 的 Redis I/O 移出全局锁，unregister 仍在出帧锁内。慢/抖动 Redis 会拖住该 emitter 的整条出帧路径（同 emitter 的所有订阅者一起等）。
- **缺失测试**：无（也没有任何观测面能看出"退订耗时"）。

#### F-J｜取消降级口径

- `cancel()` 先 `markLocal` 再 `writeRedisFlag`（147-154）；写失败只置 `degraded` 并返回 `redisWritten=false`，端点仍 200 + `cancelled:true`（`AiController:860-882`）。`wokeInProcess` 取 `markLocal` 的"本次是否翻转"（232-247），**与"是否真的唤起了监听者"无关**：跨实例时它同样返回 true（本进程只是置了个没人看的标志）。
- **用户影响**：Redis 不可用的跨实例场景下，用户点"停止"后界面已封口，但那一轮继续跑完并把完整 `done` 写进记忆/历史；刷新后看到"未取消"的完整回答。
- **对应测试**：`run/CancellationRegistryFailClosedTest` 覆盖读失败三态；未覆盖"写失败 + 前端已封口"的用户面。

#### F-N｜补丁的 tool_start 前移带来的可见语义

- 补丁在 `suspended` 之后、执行循环之前对本批**所有进入 suspended 的工具**发 `tool_start`（`SpToolCallingManager:490-493`），`emitToolStartOnce` 去重（726-730）。修正了 `suspended → request → tool_start` 的错序（前端会把已建立的等动作卡复位成只读 running，`parts.ts:539-557` 也补了反向防御）。
- 代价（补丁注释已自承，裁决 F2）：一个混合批次里首张卡 park 后，**排在后面的后端工具**在整个（无上限的）人工等待期显示 `running`，实际尚未执行；若用户最终拒绝/取消，它们会变成 `skipped/cancelled`。这是"公告语义"，不是"已执行"，但与"running 表示正在执行"的既有前端语义存在张力。
- **判定**：**语义取舍，不算缺陷**；记录以便验收方裁决是否接受"等待期 running"。

#### F-P｜purge 与启动/运维续跑之间无互斥

- 恢复侧（`ResumeService.resumeInternal` 613-1041）不查 `SessionPurgeGuard`，只有 `/chat`、记忆写回、完整历史写入三处查。
- 触发：对 session S 发起 DELETE（已枚举、已装守卫、正在删 13 键）的同时，另一实例的启动扫描/运维 `/startup-resume/scan` 对 S 的某个 run 判为可续跑并 `resume`。
- 后果：续跑会把快照重新写回（`save(RUNNING)`）并把 `ai:session:current` 指回该轮，而 purge 可能已删掉同一批键；记忆写回被守卫拒（WARN），表现为"删完又冒出一条不完整的 run"。删除本身幂等，重复 DELETE 可收敛，但会留下一次虚假成功与残留键。
- 用户影响：销毁后 `/runs/current` 仍可能"发现"该会话的轮次（`found:true`），刷新后出现幽灵在途轮。
- 对应测试：无（`AiSessionPurgeServiceTest` 不与恢复并发）。

#### F-M｜会话/轮次无服务端归属校验

- `/events/{runId}`（740-771）、`/history/{sessionId}`（1251-1330）、`/cancel/{runId}`（855-889）、`/runs/{runId}`（943-950）都不校验调用主体与 sessionId/runId 的归属。
- 触发：任何能构造请求的客户端拿到（或猜到）另一用户的 runId/sessionId 即可读该轮全部帧原文（含工具结果文本）、读完整历史、甚至取消。
- 用户影响：多用户隔离完全建立在"UUID 不可猜"上；若上层有鉴权但不做资源级授权，这是水平越权面。
- 判定：**未验证/域外**（本域无鉴权设施，需与平台安全边界一并验收）。

---

## 5. 场景推演（无粘滞路由 / ≥3 JVM / Redis Cluster）

### 5.1 kill 驱动实例 A（该轮处于 park 等人工）

1. A 在 `saveSuspended` 后发请求帧并 park 收流（`SseChatEmitter.completeSubscriptions` / `RunRegistry.close`）。快照 SUSPENDED，`ai:session:lock` 由 A 的 watchdog 续；A park 时**已还租**（`ConfirmGate:620`）。
2. A 被 kill：锁留 10m（无人续）、快照 SUSPENDED、13 键 24h、`ai:session:current` 指向该轮。
3. 用户点批准 → POST /confirm 落 B：`touchActiveRun` 续期 → HSETNX guard 得手 → 写条目 → 发 `confirm_decision`（B 自己的 emitter，无订阅者，只落档+唤醒）→ `maybeTriggerResume`：`hasActiveLatch=false`、快照 SUSPENDED → `acquireFenced` 成功（A 已还租，或 TTL 30s 后）→ 派发 `resume` → B 取会话门（A 的锁还在！）→ `SessionGate.acquire` 返回 Busy(A-run) → 但 `resumeInternal` 只判 `busy.runId != runId`（774），A-run == runId ⇒ **视为同轮接管** → 继续（正确：同轮不算冲突）。
4. B 重建历史/结算工具 → `save(RUNNING)` → 交棒 → 产出帧 → 落档 + 唤醒；前端（若连着 C）经 C 的唤醒补帧收到。
5. **判定**：可证明正确（条件：B 能拿到租约；A 的锁不构成阻塞）。这正是"无粘滞路由 + 多实例"被设计覆盖的路径。

### 5.2 kill 驱动实例 A（该轮正在流式，RUNNING）

1. A 的帧在归档里；快照 RUNNING；`ai:session:lock` 留 10m。
2. 前端 fetch 断流 → reattach（B）→ 回放增量 → 无终帧 → 保持挂接 → 45s 无帧 → 4 次退避 → `interrupted`（用户看到"结果可能不完整"）。
3. 用户在同一页签再发：409（<180s）→ "发送失败"（F-B）。
4. >180s 后：`isDeadRound` 成立 → `releaseDeadHeld` → 新 run 开始（R2）；旧 R1 仍 RUNNING。
5. **没有任何自动接管 R1**，直到某实例重启（`StartupResumeRunner`，zombie 接管）或人工扫描 → 此时触发 F-E2（旧轮复活、覆盖记忆、把 `/runs/current` 指回旧轮）。
6. **判定**：前半段是"已知边界 + 明确缺陷"（F-B）；后半段是**具体风险**（F-E2）。

### 5.3 多订阅者（同 run，两个页签 / 两个实例）

- 同一实例：`subscribers` 列表 + 每订阅者队列，`emit` 逐订阅者 `enqueueLive`；一个慢连接被 `liveQueued>=256` 摘除（不是静默丢帧），其余不受影响（`SseChatEmitterDeliveryTest#slowSubscriberDoesNotBlockOthersInTheSameRound` 证明）。
- 跨实例：每个持有连接的实例各自 `SUBSCRIBE ai:wake:<runId>`，读下界取 `min(wakeFloor)`；快订阅者按自己 floor 跳过（补丁修复 F3）。
- 真实缺口：① 一个订阅者带 `lastSeq=N`、另一个不带（0）时，每次唤醒都以 0 为读下界 → 读满窗口（≤6000）再逐订阅者过滤；正常态下 floor 在入队时已推进，不会持续发生，但"新 attach 的 chat 连接"在首帧前 floor=0（无害，仅一次全窗读）。② 慢订阅者被摘除后其 floor 不再参与 min；它重连时以客户端 `lastSeq` 重新回放（若落在窗口外 ⇒ F-D）。
- **判定**：多订阅者语义可证明正确（带条件：窗口内）；跨实例实时性依赖 pub/sub 且"丢唤醒可被下次唤醒/重连补齐"——**这一点在真实 Redis 上未验证**。

### 5.4 重复 / 丢失 / 乱序帧

| 现象 | 机制 | 结论 |
|---|---|---|
| 重复（自唤醒） | `lastFanoutSeq` 在 publish 前登记；`onWake` 上界 ≤ floor 即 no-op | **可证明正确**（`selfWakeOnTheSameInstanceDoesNotDuplicateTheFrame`） |
| 重复（重连重叠） | 服务端回放只发 `seq > lastSeq` + 孤儿过滤；客户端 `appliedSeqs` 幂等 | **可证明正确**（双层去重） |
| 乱序（同一 writer） | 取号/落档/入队同锁 | **可证明正确** |
| 乱序（跨 writer，接管窗口） | 两个 writer 各自 `INCR`，seq 仍单调；但队列到达顺序可能 100 在 99 之前 | **具体风险**：客户端不做顺序判定，99 被 floor 跳过（F-G）或晚到后被当作新帧追加（若跳过未发生），两者都可能造成文本错序/缺失 |
| 丢失（溢出） | 队列满 ⇒ 主动断连 ⇒ 客户端带 lastSeq 重挂 | **可证明正确（条件：帧在归档窗口内）** |
| 丢失（窗口/字节预算） | 读侧夹窗口，只有计数 | **具体风险**：F-D |
| 丢失（落档失败但已扇出） | `append` 吞异常，帧仍扇出 | 实时客户端有；**其它客户端/刷新**永久缺（洞在归档里，`SeqPersistenceTest` 用替身模拟过） |
| 丢失（心跳） | 心跳不入档 | **可证明正确**（前端从不依赖心跳序号；`heartbeatSeq` 独立空间） |

### 5.5 Redis 暂断

| 断的时长/形态 | 实际发生 | 结论 |
|---|---|---|
| < 1s，且本进程无租约命令失败 | `emit` 的 authority 仍 NEVER_HELD（不发 Redis）→ 帧正常；`nextSeq`/`append`/`touchActivity` 可能各自失败一次 | 少量丢档/号跳，实时流基本完整 |
| ≥1s，且本进程已 `facilityHealthy=false` | `authority()` 的 PING 失败 → UNAVAILABLE → **业务帧全丢、心跳照发**；`save` 拒写 | **F-A**：正文静默缺段；若断在终态，客户端收不到 done |
| 中断 > `lease.ttl=30s`（已持租的轮） | 恢复后 `renew` 返回 0 ⇒ LOST 墓碑 ⇒ 永久只读；RUNNING 无接管源 | **F-O**：整轮静默停摆 |
| 中断期间的会话门 | `SessionGate.acquire` 异常 ⇒ 降级为进程内门（221-237），**不写兜底锁** | **具体风险**：若两个实例的 PING 恰好都"能过"而个别命令失败，同会话可并发两轮（记忆整窗互写） |
| 中断期间的取消 | 写标志失败 ⇒ 仅本进程生效；跨实例不生效 | **F-J** |
| 中断期间的 purge | 前置/枚举失败 ⇒ 一个键不删、回滚 pending（安全）；部分删除失败 ⇒ `complete=false` 可重试 | 可证明正确（幂等） |

### 5.6 前端多页签并行 / 多用户隔离

- 多页签（正常）：`sessionStorage` 页签级 + Web Locks 所有权 + 复制页签换新 id（`tab-ownership-lifecycle` 10 例）⇒ 两个页签 = 两个 sessionId = 两个独立 run，服务端无共享状态。
- 多页签（同一 sessionId 被强行共用，如无 Web Locks 的 `unsupported`）：服务端只靠 `SessionGate` 串行化；第二个页签每次发送 409（"发送失败"），且它的镜像/历史会与第一个页签互相覆盖（`restoreSuspendedSnapshot` 只按 sessionId 拉）。
- 多用户：服务端**没有任何归属校验**（`/events/{runId}`、`/history/{sessionId}`、`/cancel/{runId}` 都不校验主体），隔离完全依赖 `sessionId`/`runId` 为不可猜 UUID。若系统其它地方有登录态，这仍属于"知道 id 即可读他人会话"的水平越权面（域外鉴权是否兜住，本报告不覆盖）。
- `concurrency-matrix`(4 例) 覆盖"双击发送/发送与停止同 tick/两条连接同时 reattach/reattach 与停止同 tick"，都是单 store 内的竞态，**不覆盖**两个真页签共享后端会话的场景。

---

## 6. 可证明正确 / 未验证 / 结论

### 6.1 可证明正确（附条件，不许当绝对保证）

1. **单 writer 下 seq 与到达顺序一致**：取号/截断/落档/入队同在 `emitLock`（`SseChatEmitter:1450-1523`）。条件：同一 run 只有一个实例在写（fencing 或单驱动）。
2. **重挂的"回放→实时"无交接窗口**：先挂订阅（含唤醒）再读回放，两段同锁（666-714）。条件：回放源可读（`degraded` 时走 typed error 收尾）。
3. **终帧收尾判据 = 归档里有没有终帧**，而非快照状态（L-143，694-712、726-743）。条件：写方存活并会发终帧。
4. **重复帧双层去重**：服务端 per-subscriber floor + 客户端 `appliedSeqs`。条件：`seq` 不被降级冲突污染（F-F）。
5. **决策恰一次**：`ai:decided` HSETNX + kind + PENDING 复判 + WAL 补齐（`ConfirmGate:1138-1342`）。条件：Lua/HSETNX 语义成立。
6. **租约 token 精确释放/续期/强释放**：全部单键 Lua 比对后写（`SessionGate:80-143`、`LeaseService:226-256`）。条件：真实 Redis 执行 Lua。
7. **fencing 写**：`fence ≤ epoch` 才落库，且丢失/不可知一律只读（`RunStore:690-751`、`FENCED_SET` 299-308）。条件：authority 判据本身可读（F-A 是它的反作用面）。
8. **删除会话的原子回收**：13 键同 tag 一条 `DEL`；失败即 deferred（`RunStore:1029-1063`、`AiSessionPurgeService:367-431`）。条件：Cluster 同槽（键名由 `runScopedKey` 统一拼）。
9. **前端"停止后不落地迟到帧"**：每块读之前和之后都检查 abort（`sse.ts:388-401`）；终帧后统一闸门（270-272）。
10. **前端跨轮/跨连接守卫**：`roundEpoch` + `currentAbort` 身份 + `activeRoundHostId`（`stores/ai.ts:4885-4935`）。

### 6.2 未验证（不得在验收结论里算作已覆盖）

- 真实 Redis 5.0/Cluster 上：4 条租约 Lua、`FENCED_SET`、`IDEM_PUSH`、`ACTIVITY_TOUCH`、`XADD` 显式 ID 冲突回退、`MAXLEN ~`、`XRANGE` 闭区间、`PUBLISH` 跨节点广播、`RedisMessageListenerContainer` 断线重订阅。
- ≥3 JVM 的真实接管时序（kill -9、滚动重启、网络分区、时钟偏差），以及 `zombie-age` 与 NTP 的耦合。
- 真实浏览器 + 真实代理（60s 空闲）下的 45s/15s 看门狗错层；两个真页签的真 Web Locks 调度。
- `SseEmitter` 的真实 socket 背压/写超时（`SseWriteBudget` 只在单测里用可注入时钟/线程验证过"结果分类"，未验证 Tomcat 侧行为）。
- Redis Cluster 的 `maxmemory`/淘汰对 13 键的破坏（代码假设 noeviction 或至少 key 不被淘汰；`ai:seq` 被淘汰会触发号回退自修复，但它只覆盖"INCR 返回 1"这一种形态）。

### 6.3 结论（本域）

- **可以认为成立的部分**：帧协议与序号、重挂差量、决策幂等、租约/fencing 的**逻辑**设计，是本仓里少见的高完成度实现；补丁修掉的 `wakeFloor`（per-subscriber 游标）与 park 帧序（tool_start 早于 request）都是真实缺陷的正确修法，且有能"变红"的用例。
- **不能认为成立的部分**：以上全部只证明了**单进程内 + 语义等价替身**；真实 Redis Cluster / 多 JVM / 故障时序一条都没有自动化证据（§6.2）。
- **本域最高优先级（按用户可见损害排序）**：
  1. **F-A**：Redis 抖动时正文静默缺段，且心跳掩盖了它 —— 唯一"用户与前端都无法察觉"的正确性问题。
  2. **F-E2**：僵尸 RUNNING 轮在后续启动扫描复活并**整窗覆盖会话记忆**（会把用户后一轮的上下文抹掉）。
  3. **F-B**：kill 后 180s 的"发送失败" + 前端丢弃 reattach，用户没有任何可操作的自救提示。
  4. **F-D / F-G / F-F**：三类"静默丢帧"（窗口缺口、floor 跳过、号冲突），共同特征是**重连也补不回**且**没有缺口标记**。
  5. **F-F2 / F-O**：故障后的终态收敛缺口 —— 死轮让 purge 永不完成；丢租后 RUNNING 轮无人接管。
- **本报告的边界**：以上全部基于固定快照的静态阅读；`working.patch` 之外的后续补丁由指挥官对账，未在此评估。`F-N` 与 `F-L` 属"需要裁决/文档一致性"的事项，不是运行缺陷。
