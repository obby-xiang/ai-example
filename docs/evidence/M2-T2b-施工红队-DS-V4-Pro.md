# M2-T2b 施工红队审查（DeepSeek V4 Pro）

> 角色：T2b 施工成果攻击式审查官。对象：主仓库工作区未提交的 T2b 施工成果（11 处已改 + 2 新测试类 + 施工证据文档）。
> HEAD `2354721`。全程零 git 写操作、零既有文件修改；唯一写操作即本报告文件。
> 证据等级：**【实测】** = 本棒实读代码/实跑命令确认；**【推断】** = 静态推演未实跑；**【假设】** = 基于设计意图的推测。
> 脱敏：路径一律相对 `<REPO_ROOT>`；Maven 记 `<MAVEN_HOME>`；不含任何 API key；不含仓库目录名。
> 审阅范围：`backend/src/main/java/com/example/configmgr/ai/{run,gat,web,form}`、`frontend/src/{stores,api,components,types}`、`scripts/m2t2a-smoke.mjs`、两个新测试类。

---

## 0. 总体判定

**有条件通过。**

- **无 S1（不构成打回）**：未发现会推翻验收结论的严重问题。`save()` 状态机联动为唯一通道、三分支口径对齐、增量续收 `lastSeq` 语义、快照重建守卫同口径，均经读码【实测】确认成立。
- **S2 修复建议 2 项**（均不阻断）：进程已死窗口期的前端死卡兜底缺失；前端核心路径零自动回归的延续性风险。
- **S3 观察若干**：索引 GET-then-act 非原子、TTL 选值依据表述偏差、冒烟脚本时序脆弱性、测试对硬编码 TTL 的耦合等。
- 已裁决项与验收基准（见 §2）**均未找到推翻性新证据**。

独立复验结果（补充 K2.8 证据之外的本棒实测）：

| 项 | 结果 |
|---|---|
| 前端 `yarn typecheck`（vue-tsc --noEmit） | EXIT=0，Done in 4.22s【实测】 |
| 两个新测试类 surefire 报告 | `RunStoreSessionCurrentTest` 7/0/0、`AiControllerRunsCurrentTest` 7/0/0【实测】 |
| 后端全量日志 `<REPO_ROOT>/backend/target/t2b-mvn-test.log` | `Tests run: 263, Failures: 0, Errors: 0 — BUILD SUCCESS`【实测】 |
| 前端 `yarn build` | 未独立复跑（依赖 K2.8 证据，typecheck 已复跑通过）【推断】 |

---

## 1. 结论速览（按严重度）

| # | 级别 | 主题 | 落点 |
|---|---|---|---|
| S2-1 | 修复建议 | 进程已死窗口（0~10m）前端重建不可服务死卡，无"零帧超时收敛"兜底 | `stores/ai.ts:542-572`、`sse.ts:21-22` |
| S2-2 | 修复建议 | 前端快照重建/增量续收零自动回归（已接受风险的延续） | `stores/ai.ts:446-572` |
| S3-1 | 观察 | `clearSessionCurrent`/`renewSessionCurrent` GET-then-act 非原子（TOCTOU） | `RunStore.java:203-231` |
| S3-2 | 观察 | `deleteRun` 不清 session 索引（当前无调用方，属潜在残留面） | `RunStore.java:267-270` |
| S3-3 | 观察 | 索引 TTL 取 `lock.ttl` 的"对齐会话锁 watchdog"表述与实际续期来源不完全一致 | `RunStore.java:249-251`、`ConfirmGate.java:190-193` |
| S3-4 | 观察 | `/runs/current` 在"已终态但索引未清"窗口返回 `found:true`+终态 `snapshotStatus`（非空态），契约略歧义 | `AiController.java:416-420` |
| S3-5 | 观察 | `restoreSuspendedSnapshot` 在 `awaitingExternal=false` 时跳过"已决条目收敛"（当前 loadHistory 已兜底，属无害冗余） | `stores/ai.ts:457-473` |
| S3-6 | 观察 | 冒烟断言①时序脆弱：`readSse` 停读时丢弃局部缓冲、CDP 轮询/真实模型轮 240s 上界 | `scripts/m2t2a-smoke.mjs:183-205,243-284` |
| S3-7 | 观察 | `RunStoreSessionCurrentTest` 对 `Duration.ofMinutes(10)` 硬编码，耦合默认 TTL | `RunStoreSessionCurrentTest.java:76` |
| S3-8 | 观察 | 快照重建续收路由到固定宿主消息，最终回答 delta 合并进工具卡气泡（与 reattachActive 同型，非新缺陷） | `stores/ai.ts:554`、`1035-1038` |

---

## 2. 验收基准与已裁决项复核（结论：无推翻性新证据）

| 已裁决项 | 复核结论 |
|---|---|
| ① 断言①以快照重建形态为验收基准 | **判别力成立**【实测】。`formRendererCount===1 && toolRunCount===1` 直接对撞 T2a 缺陷形态（`>1`）。清镜像键足以证明走快照路径：`loadHistory` 的历史映射分支（`stores/ai.ts:413-433`）把所有 toolCalls 落 `status:'succeeded'`、`pendingCall:null`，**永不**产生挂起卡或 FormRenderer；故任何 `formRendererCount>=1` 只能来自 `restoreSuspendedSnapshot`。`toolRunCount===1` 进一步锁定"upsert 更新而非 append 追加"（历史已持该 toolCall 时为 1，追加则成 2）。详见 §4。 |
| ② 多 PENDING 共用宿主单槽位后写覆盖 | **代码确认**【实测】`stores/ai.ts:489-505` 逐条覆写 `host.pendingCall`，后写覆盖先写；`resumeSnapshotStream` 只挂最后一个 host。现行单轮单卡场景无影响，观察项成立。 |
| ③ 快照路径 onClose 不清 activeForm、deadline=null 无本地超时 | **成立但补充边界**【实测】后端权威超时收敛成立；但进程已死时后端无活 gate/emitter 产帧，前端 reattach 只能靠 90s 静默超时退出，卡片保留为"不可服务死卡"（见 S2-1）。受 index TTL 10m 上界约束，刷新后自愈，非永久死态。 |
| ④ unresolvedExternal 含 CONFIRM → HITL 确认卡也重建 | **确认【实测】** `AiController.java:434-438` 仅按 `status==PENDING && kind!=BACKEND` 过滤，CONFIRM 计入。属范围外增益，与已裁决一致。 |

---

## 3. S2 修复建议（非阻断）

### S2-1 进程已死窗口：前端重建死卡无"零帧超时收敛"兜底
- **证据**：`stores/ai.ts:542-572` `resumeSnapshotStream` 在 `onClose(!terminal)` 时仅置 `streamInterrupted=true` 与 notice，**保留挂起卡**；`sse.ts:21-22` 默认 90s 静默超时、`DEFAULT_MAX_RECONNECTS=2`。
- **场景推演**【推断】：进程在挂起期崩溃后，index（TTL 10m）未过期前，`/runs/current` 返回 `found:true`+SUSPENDED+PENDING（条目仍 PENDING，因为死进程从未把它们标 TIMEOUT）。前端据此重建挂起卡并 reattach 到已死 emitter（无 producer、无心跳）→ 90s 静默超时 → onClose 非终态 → 卡保留但**永远收不到结局帧**（用户填表提交也只是 `frontendToolResult` 写库 + `signal` 唤醒一个不存在的闸门，无 emitter 产结局帧）。用户靠该卡无法自救；只能等 index TTL 过期后刷新，历史/空历史 expired 降级才接手。
- **边界（非永久死态）**：index TTL 10m 过期后 `found:false` 自愈；后端 run 残留 SUSPENDED 至 snapshot TTL（6h）为**既有**缺陷（`ResumeService.resume` 对 PENDING 外部条目 409 `PENDING_UNRESOLVED`，启动扫描不会接管），非 T2b 引入。
- **建议**：`resumeSnapshotStream` 在"连接后 N 秒内零帧（含零 heartbeat）"时把挂起卡就地收敛为 `expired`（`applyFrontendToolTerminal` 同口径），与"空历史 expired 降级"对齐；或后端在 `runs/current` 命中但活 gate 缺失（`RunRegistry` 无对应闸门）时直接落 `found:false`。均不阻断验收，登记为后续改进。

### S2-2 前端快照重建/增量续收零自动回归（已接受风险的延续）
- **证据**：`stores/ai.ts:446-572` 新增约 160 行核心前端逻辑（`restoreSuspendedSnapshot`/`resumeSnapshotStream`），但前端无任何单测；判别力完全依赖 CDP 冒烟断言①（`scripts/m2t2a-smoke.mjs:366-378`）连续两轮 PASS。
- **定性**：这是 T2a-D#5"不引 vitest + CDP 冒烟"已裁决风险的延续，且 T2b 把该路径复杂度显著抬高（三级宿主优先、守卫前移、lastSeq 续收）。若冒烟因网络/CDP 时序 flaky（S3-6），快照重建正确性无第二道门禁可验证。
- **建议**：T4 引入 vitest 时，优先覆盖 `restoreSuspendedSnapshot`（空态早退/已决不重建/PENDING 重建+upsert 唯一性）与 `resumeSnapshotStream`（abort 防双连、lastSeq 锚定）两个 action。不阻断。

---

## 4. 断言①判别力专项论证（响应验收基准①）

**问题 A：断言①能否区分 T2a 缺陷形态与修复形态？**
能。【实测】T2a 缺陷（双投影分离失败/重复工具卡）的确定性外在表现就是 `formRendererCount>1` 或 `toolRunCount>1`；断言①要求 `formRendererCount===1 && toolRunCount===1`，是对其直接否定。且该缺陷是结构性（双渲染/重复 append），非时序性，`===1` 不会被偶发时序掩盖。

**问题 B：清镜像键 `ai-mirror:<sid>` 是否足以证明重建走快照路径？**
足以。【实测】三点独立证据链：
1. `loadHistory` 镜像分支（`stores/ai.ts:383-390`）只在 `readMirror` 命中时恢复 `pendingCall`；镜像键被清后该分支不产生任何 pending 投影。
2. 历史映射分支（`stores/ai.ts:413-433`）对每个 toolCall 落 `status:'succeeded'`、`pendingCall:null`，**结构性无法**产生挂起卡/FormRenderer。
3. 唯一能写 `pendingCall`（进而渲染 `FormRenderer`）的路径是 `restoreSuspendedSnapshot`（`stores/ai.ts:498-529`）与帧 handler；刷新后无帧到达，故 `formRendererCount>=1` 当且仅当快照重建执行。
因此 `formRendererCount===1` 是"快照重建已发生"的充分证据，`toolRunCount===1` 进一步证明 `upsertToolRun` 的"更新而非追加"语义生效（否则历史已持该 toolCall 会叠成 2）。

---

## 5. 重点攻击面逐项结论

### 5.1 RunStore.save() 状态机联动
- **无绕过 save() 的状态写入**【实测】。快照状态写路径全集：`RunStore.create`(RUNNING, :148)、`RunStore.saveSuspended`(SUSPENDED, :316)、`SpToolCallingManager.settle`(RUNNING, :469)、`ResilientChatService`(DONE :445 / CANCELLED :475 / FAILED :508)、`ResumeService`(CANCELLED :260 / RUNNING :332,337 / SUSPENDED :369)。全部经 `save()`；索引联动（SUSPENDED 建 / 终态清 / RUNNING 保，`RunStore.java:161-170`）覆盖全部终态常量 `{DONE,FAILED,CANCELLED}` 与 `SUSPENDED`，无漏清面。
- **TTL 取 lock.ttl（非 session-ttl）**：正确【实测】。挂起期每次 await ≤ `app.ai.hitl.timeout`（120s），心跳每 2s 续期（`ConfirmGate.java:190-193`），10m TTL 远大于续期节拍，活轮不会过期；进程死后 10m 过期恰为"进程已死分支"的触发阈值。表述"对齐会话锁 watchdog"略松（实际续期来源是 ConfirmGate 心跳，与 SessionGate 锁无关），见 S3-3。
- **watchdog 续约与锁释放竞态**：`renew`/`clear` 用 GET-then-act（非 Lua 原子），存在值匹配校验（`RunStore.java:208-211,224-227`）挡掉"删错/续错他人轮"的大类错误，但 GET 与 EXPIRE/DEL 之间的窗口仍可被并发覆盖（S3-1）。单页签 B2 约束下概率极低、且定位为体验优化，不阻断。

### 5.2 /runs/current 三分支与并发
- **sessionCurrentRunId 指向已终态 run 的窗口期**：`AiController.java:416-420` 已处理——快照已 DONE/FAILED/CANCELLED 时 `awaitingExternal=false`（`SUSPENDED && 非空` 才为 true），前端早退不重建。唯一瑕疵是该窗口返回 `found:true`+终态 `snapshotStatus` 而非空态（S3-4），无功能影响。
- **400 裸 Map 与全局异常处理一致性**：一致【实测】。`AiController.java:414` 与 `confirm`/`chat`/`frontend-tool-result` 的 400 分支同构（`json(BAD_REQUEST, Map.of(...))`），无全局 @ExceptionHandler 包信封；前端 `http.ts:88-101` 按 `success` 布尔拆信封、非 2xx 走 `normalizeError` 读裸 `code/message`，前后端零漂移。
- **unresolvedExternal 口径对齐**：`AiController.java:434-438` 与 `ResumeService.java:290-293` 逐字同口径（`PENDING && !KIND_BACKEND`）【实测】；`awaitingExternal` 的 D1 消歧（SUSPENDED 但全决→false）由单测 `decidedEntryDisambiguatesSuspendedSnapshot` 锁定。

### 5.3 前端快照重建与增量续收
- **lastSeq=archiveMaxSeq 是否漏帧/重帧**：不漏不重【实测】。`SseChatEmitter.java:378-384` `isOrphan` 用 `seq <= lastSeq` 过滤，`replayAndAttach`(:122-124) 在 `emitLock` 内原子完成"回放+挂订阅"，消除"回放读完、订阅未挂"的丢帧窗；`archiveMaxSeq=lastEventSeq(runId)`（`AiController.java:445`）取归档末帧 seq，心跳不入归档（T7），故无序号空洞导致误判。端点计算 archiveMaxSeq 与 reattach 之间新产的帧会因 `seq>lastSeq` 被回放，不丢。
- **reattachRun 与 abort 防双连竞态**：`resumeSnapshotStream`(:543-549) 先 `currentAbort.abort()` 再换新 controller；`currentAbort` 为模块级单例（`stores/ai.ts:32`），与 `send`/`reattachActive` 共用。刷新场景下 `restoreSuspendedSnapshot` 有 `if (this.loading) return`（:447）入口守卫 + 只在 loadHistory 尾部触发一次，双连概率低；但"模块级单例被跨流覆写/置 null"是既有设计，T2b 只是新增一个使用者（S3，非新缺陷）。
- **historyLoaded 置位时序**：`stores/ai.ts:406-409,435-438` 两分支均先 `historyLoaded=true` 再 `await restoreSuspendedSnapshot()`，且 catch 分支不重建【实测】；消除了历史整体替换与挂起重建的写后写竞态。

### 5.4 冒烟脚本断言①稳定性
- **flaky 风险**：`readSse`（`scripts/m2t2a-smoke.mjs:183-205`）停读返回时丢弃局部 `buf`（已 read 未解析的字节），若结局帧与 `frontend_tool_request` 同 chunk 可能丢失 → 断言① `frontendToolResult/done` 偶发 FAIL；真实模型轮 240s 上界、刷新重建 15s 轮询，均受网络/CDP 时序影响。当前两轮连续 PASS，未触发"三次误报"推翻条件，维持观察（S3-6）。
- **"结局帧比 POST 回执先到"竞态**：脚本已处理（`submitting → submitted || activeForm===null` 判定收敛，`:314-321`），属合理防御。

### 5.5 两个新测试类是否假绿
- **非假绿【实测】**。`RunStoreSessionCurrentTest` 7 条用 HashMap 桩 mock StringRedisTemplate，覆盖：SUSPENDED 建索引+锁 TTL verify、三终态清、RUNNING 保、renew/clear 值匹配守卫、Redis 异常降级、同 toolCallId 二次 putPending 单条目；`AiControllerRunsCurrentTest` 7 条走 standalone MockMvc 真实构造 controller，覆盖 400、空态七字段、stale 索引、awaitingExternal 三态与 D1 消歧、BACKEND 不计 unresolvedExternal。均实跑通过（surefire 7+7=14）。前端关键路径零覆盖见 S2-2。

---

## 6. 遗留与建议

1. **S2-1/S2-2** 建议登记为 T4 跟进项（不阻断 T2b 闭环）。
2. **S3-1** 若未来放开同 session 多页签（撤销 B2 约束），`clearSessionCurrent`/`renewSessionCurrent` 应改 Lua 原子（GET 匹配 + DEL/EXPIRE 单脚本）。
3. **S3-7** 测试中 TTL 断言建议改读 `AiProperties` 实际值而非硬编码字面量，避免默认值漂移时测试静默失效或误报。

> 本报告仅为红队审查结论；S2 均不构成打回，T2b 施工成果可依既有验收基准进入闭环。
