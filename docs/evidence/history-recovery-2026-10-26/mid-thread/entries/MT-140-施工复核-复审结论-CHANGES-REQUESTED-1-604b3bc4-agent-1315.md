<!-- 历史中间稿证据条目（自动生成头部，非正文）。源 = Kimi Code assistant wire 文本；不含 think / 工具输出 / 原始用户消息。 -->

# MT-140 · 施工复核 · 复审结论：**CHANGES-REQUESTED**（1 Critical + 6 Important）（604b3bc4 / agent-1315）

> **项目归属**：**项目硬锚点**（正文命中 ai-example 路径 / 项目类名 / 项目文档名 / 项目 UI 概念）。
> **归属依据**：正文命中：`RunStore`、`ConfirmGate`、`check-docs`、`backend/src/main/java`。
> **稿态**：历史中间稿 —— 该 wire 第 `m59` 条 assistant 消息（m 按 wire 内 assistant 消息计，含工具调用轮）；本条是该 wire 第 `mt2` 条有正文的 assistant 消息（共 11 条有正文），末条有正文消息为 `m392`。**非现行基线、非验收结论、非当前缺陷清单**。
> **来源**：会话 `604b3bc4` / agent `agent-1315`（`main` = 主会话）；原字符 11,790（链内 3 段、合计 31,741）。
> **链号 / 类别**：`MT-140` / **施工复核**；判定：施工复核记录（当时那次核验看到什么，不证明当前仍成立）；**不是用户批准**。
> **轮次**：位于该 wire 第 2 个用户轮之后；相邻用户轮未见决断类字样（仅记布尔，未复制用户原文）。
> **去重**：行指纹对「仓内 + 落盘语料」覆盖率 `5%`（<60% 视为仓内无等价件）；同 wire 后文覆盖率 `1%`。
> **与末条归档（138 件）的关系**：末条 `m392`（12,168 字符）未入末条归档。
> **脱敏**：路径 8 / 会话标识 3 / 身份 0 / 密钥 0 / 旧仓名与同源仓名 0 处。

---

### 〔段 1／3 · assistant 消息 m59 · 11,790 字符〕

## 复审结论：**CHANGES-REQUESTED**（1 Critical + 6 Important）

审查对象：`git diff HEAD（43d24ad）` 全部未提交改动（10 改 + 16 新文件，backend/ + scripts/）。只读复审，未改任何文件、未起服务、未提交。

**验证证据（实际跑过）**：`mvn -o -s maven-settings.xml test`（JDK 21 + IntelliJ 自带 Maven 3）→ **EXIT=0**，surefire 汇总 **672 tests / 0 failures / 0 errors / 0 skipped**（109 个 test set）。新增/改动 13 个测试类逐个核对全绿：LeaseServiceTest 10、RunStoreFencedWriteTest 5、ConfirmGateLeaseTest 8、ResumeServiceLeaseTest 7、AiControllerSuspendedTouchTest 6、RunStoreRequestEmittedAtomicTest 4、RunStoreClaimEpochTest 4、RunStoreDecisionGuardTest 3、ConfirmGateDecisionAtomicTest 3、RunStoreTouchTest 3、RunSnapshotEpochCompatTest 2、AiControllerHealthSseDeliveryTest 5（+1）。E2E 31 用例 / 前端 typecheck / build / check-docs / 脱敏未跑（无前端改动，且禁起服务）。

---

## Critical

### C1 前端工具超时出口**一边还租一边继续驱动本轮** ⇒ 双驱动窗口被本批自己打开

`backend/src/main/java/com/example/configmgr/ai/gate/ConfirmGate.java:498`（`return expireFrontend(...)`）+ `:540`（`if (lease != null && !handedOff) releaseWaitingLease`）

- 事实链：`expireFrontend` 只把条目写成 `TIMEOUT` 就返回，**不结束本轮** —— `SpToolCallingManager.awaitFrontend`（`:538`）把超时文案当工具结果回填模型，官方循环在同一线程继续跑（还会继续出帧）。但 `finally` 里的注释断言「取消/中断/前端超时都是本轮不驱动」，于是 `handedOff=false` → `releaseWaitingLease` → 租约被 DEL + 续租任务被停。
- 后果两个叠加：① 本进程仍在驱动，但 `heldRunEpoch=0` ⇒ 之后所有 `RunStore.save` 走 plain SET（`RunStore.java:286-296`，E3 分支），fence 保护整段失效；② 另一实例此时可立即抢到租约，若快照还是 `SUSPENDED`（超时到下一次 save 之间就是这样）且同批还有别的卡的决策进来，`maybeTriggerResume` 会真的派发续跑 → 同一轮被两个实例驱动。这正是本批 §5-2 第 1 条要消除的现象。
- 修法（3 行）：把「前端超时」加入交棒集合 —— `expireFrontend` 返回前 `handedOff = true` 且 `holder.markDriving(lease)`（与决策到达分支同形）；只有「取消 / 中断」才还租（取消路径还要复核：`cancel()` 同样只是回填结果，靠外层取消收敛，建议同样自查）。
- 依据：设计稿 §2.2（「续跑路径所有写回带 epoch 校验」）、方案第 1 条（交棒不释放）、§5-2 第 1 条。

---

## Important

### I1 WAL 补齐会把 `FRONTEND_RESULT` 抹成「无负载的已决」
`ConfirmGate.java:868-902`（`convergeOnDecided`），`PendingToolCall.java:34`

`statusOfDecision` 对 guard 值 `FRONTEND_RESULT` 没有分支（只处理了 `REJECTED/APPROVED/FRONTEND_CANCELLED`）：`setStatus("FRONTEND_RESULT")` 之后 `outcome` 仍为 null、`resultText` 仍为 null，**结果载荷永久丢失**。而 `resolved()` = status≠PENDING ⇒ 等待侧立刻当已决消费，`awaitFrontend` 拿到 `null → ""` 回填模型。触发窗口：双页签/双击同一张前端表单卡，抢不到 guard 的一方读到 PENDING 后写入裸状态（两个 Redis 往返），赢家随后才写全量值，也可能被这次裸写反向覆盖（读写次序任意）。
- 修法：`convergeOnDecided` 只对**可重建结论**的状态做 WAL 补齐（REJECTED / FRONTEND_CANCELLED / CANCELLED）；`FRONTEND_RESULT` 一律不写裸状态（保持 PENDING + 返回 DUPLICATE），或改成 CAS（Lua：GET 原值比对仍是我读到的那份才写）。
- 依据：方案第 5 条「WAL 语义」、§5-2 第 5 条。

### I2 WAL 只落了一半：读侧不看 guard，崩溃窗口的结论无人消费
`RunStore.java:1096`（`decidedStatus`）、`AiController.java:470-480`（`/runs/current`）、`ResumeService` 的 `PENDING_UNRESOLVED` 判据

`decidedStatus()` 的唯一消费者是 `convergeOnDecided`（必须**再来一次重复提交**才走到）。若写 guard 的进程死在 `HSETNX → putPending` 之间，且等待方也不在（A 已死），则该轮：`/runs/current` 显示一张 PENDING 卡、`ResumeService` 判 `PENDING_UNRESOLVED` 拒绝续跑 ⇒ 直到会话 TTL 或用户重提才收敛。另外 `convergeOnDecided` 补齐后**不触发续跑**（`maybeTriggerResume` 未调用），补出来的结论没有驱动者。
- 修法：`pending()/pendings()` 合并 guard 结论（与 req 位合并同一手法，grant-only）；`convergeOnDecided` 补齐后走一次 `maybeTriggerResume(runId, false)`。
- 依据：方案第 5 条、`docs/任务看板.md:402`（M-2 要处置的正是「决策丢失」的另一半）。

### I3 带租约重载的「归属校验」是纯进程内判据，Redis 换主后仍会放行
`ResumeService.java:296-300`（`heldRunEpoch(runId) != lease.epoch()`）；`LeaseService.java:184-190`（可重入短路）

`heldRunEpoch` 读的是进程内视图，`tryAcquire` 在视图存在时**根本不进脚本**。所以「A 的视图还在、Redis 里早已是 B 的更高 epoch」这一窗口里：`maybeTriggerResume` 的可重入短路返回陈旧 lease，`resume(runId,false,lease)` 的校验通过 → A 继续驱动。fence 只拦 A 的**快照写**，工具执行只靠 `claimExecution` 的 HSETNX 兜底（新 toolCallId 拦不住）。
- 视图通过 `renewTick` 的失败路径会被摘掉，窗口上界≈一个续租间隔（10s），所以不是 C 级；但 `resume` 是冷路径，加一次 `GET` 比对几乎零成本。
- 修法：`LeaseService` 增 `verifyHeld(key, epoch)`（`GET == token`），`resume(runId,force,lease)` 用它取代/补强进程内比对。
- 依据：设计稿 §2.2、方案第 6 条。

### I4 Redis 异常在 ResumeService 被报成 `LEASE_HELD_ELSEWHERE`（偏离方案第 3 条「降级放行」）
`ResumeService.java:251-257`；对照 `LeaseService.java:207-211`（异常 → `Optional.empty()`）

`tryAcquire` 对「被占用」和「Redis 不可用」返回同一个 `empty`，`resume(runId,force)` 一律翻成 `LEASE_HELD_ELSEWHERE` ⇒ Redis 抖动期间 `/api/ai/resume` 回 409「另一实例持有」，启动扫描也会把本轮记成 `outcome=LEASE_HELD_ELSEWHERE`。三处降级点只有 `ConfirmGate.await`（抢不到照常等）真正「放行」，`maybeTriggerResume`（不派发+35s 重试）与 `ResumeService`（409）都不是。
- 修法：`tryAcquire` 返回可区分结果（`Optional<Lease>` + `leaseOk()`/`Deferred`），ResumeService 在降级时走进程内互斥放行（与 `leases==null` 分支合并）。
- 依据：方案第 3 条「Redis 异常 = 显式降级放行 + leaseOk=false」。

### I5 claim / ledger 只「带」epoch，没有「校验」（设计稿 §2.2 三项写回只做了一项）
`RunStore.java:693-707`（`claimExecution`）、`:735-757`（`appendLedger`）

设计稿要求「快照 / ledger / claim 都要带 epoch 校验，不匹配即拒」。现状：快照有 fence 写（做了）、claim 只是值前缀 + 条目 `claimedEpoch`（**不拒**）、ledger 完全没动。于是「旧主人执行了副作用、新主人随后接管」的情形，台账仍会记下了执行但无人能判它属于失效的一任。
- 修法（不破坏 E3）：`claimExecution` 在 `epoch>0` 时读 fence，`fence > epoch` 直接返回 false 并留 `STALE_EPOCH` 痕迹；`epoch==0` 保持原样；`appendLedger` 加 `epoch` 字段（additive）。

### I6 四条 Lua 脚本从未在真实 Redis 上验证过，且 harness 的批②用例仍是 SKIP
`backend/src/test/java/com/example/configmgr/ai/run/FakeLeaseRedis.java:223-298`；`scripts/b23l1-dual-instance.ps1:495-499`

`FakeLeaseRedis` 是**用 Java 重写了一遍 Lua 语义**并按脚本体里的注释标记分派（`LEASE_ACQUIRE` 等）—— 断言的是「我以为的脚本」，脚本正文写错（语法、`tonumber(nil)`、KEYS/ARGV 顺序）672 条用例全绿也发现不了。而唯一能碰真 Redis 的 harness 里，`suspend-decision` / `instance-kill` 仍写在 `$pendingCases` 且理由还是「批②未落地」（本批就是批②）。
- 建议：harness 加一条 `lease-scripts` 冒烟用例（A 侧 `/api/ai/resume` 抢租 + B 侧并发 + fence 比对），或在 CI 外挂一个真实 Redis 的集成测试；同时把两条 SKIP 文案改成本批真实状态。
- 依据：§5-2 第 1/2 条；本批安全性的全部支点就是这四个脚本。

### I7 僵尸续租（施工报告自报）—— 同意**保持 Important**，不建议降级
`LeaseService.java:276-313`（`markDriving`/`renewTick`）

驱动线程异常死亡（无终态 `save`）时，调度线程仍每 10s 续租 ⇒ 租约永不过期 ⇒ 其他实例对该轮永远 409 / 启动扫描永远跳过；只有同实例的 `/api/ai/resume` 因可重入能救回来。可见性尚可（lease key 在 `describe` 里），但属**永久性资源锁死**，不是「自愈型」缺陷，不应按 Nit 处理。
- 修法（低成本）：`markDriving(lease, predicate)` 带上「本轮仍非终态且仍有驱动者」判据，`renewTick` 判据为假即 `stopDriving + release`；或加 `app.ai.lease.max-drive`（如 = 总预算 × 3）到点释放，之后由 fence + claim 兜底。

---

## Minor

- **M1 fence 键 TTL 不在读侧续期面内**：`RunStore.java:1026-1043`（touch 续了 5 类 + req + decided，**没有 fenceKey**）。长挂起（>24h 无 save、只有 touch）时 fence 先过期而快照仍活 ⇒ 陈旧写保护静默消失。修法：touch 里一并 `expire(fenceKey)`。依据：方案第 7/8 条（「fence 键 TTL 与快照一致」只在写时成立）。
- **M2 E3 分支在「视图被摘掉」后无条件放行**：`RunStore.java:286-296`。`renewTick` 失败会 `held.remove` ⇒ 旧主人降级成 plain SET，可覆盖新主人的快照。建议 `epoch==0` 时若 fence 键存在且 > 0，至少 `WARN`（并考虑按「快照自带的 runEpoch < fence」拒写，避免误伤真正的单实例降级）。
- **M3 `ai:lease:epoch` 无 TTL 但也没写运维前提**：Redis 若配 `allkeys-lru` 把它淘汰，epoch 回退 = fencing 前提崩塌（`LeaseService.java:55-61` 只讲了「不做每族计数器」）。建议在类注/部署文档写明「该键必须 pin 住（noeviction / 独立实例）」。
- **M4 `touchSuspendedRun` 名不副实**：`RunStore.java:1026` 只判 `isTerminal`，`RUNNING` 的读也会续满 24h。无害，但名字与语义不符，建议改名 `touchActiveRun` 或补 SUSPENDED 判据（后者会改变 `/events` 对 RUNNING 重挂的语义，需先确认）。
- **M5 ResumeService 的 `redis` 字段已成死字段**：`ResumeService.java:106`（只赋值、无读取），仅为保持 10 参构造器。建议把参数标注为保留/`@Deprecated` 构造，或真正删场合并迁移 2-3 个用例，避免下一位读者以为锁还在。
- **M6 harness `taskkill /PID /T /F` 的 PID 复用窗口**：`scripts/b23l1-dual-instance.ps1:159-170`。`HasExited` 判过之后到 taskkill 之间子进程若恰好退出且 PID 被系统复用，理论上会误杀无关进程树。建议执行前比对 `$proc.StartTime`（或优先 `Stop-Process -Id`，taskkill 只作 fallback）。
- **M7 harness 批②用例缺失 + 文案过期**：同上 `:495-499`。§5-2 第 2 条的五条验收里「跨实例决策 / A 死后接管」两条目前只有单测面（G1-G8、R4），没有真实双 JVM 证据。
- **M8 实例面维度未标注**：新测试与新注释没有任何一处写明「单实例（DC-11）不可达 / 仅 ≥2 实例可复现」（全仓 `DC-11` 只出现在 job 模块）。建议在 `ConfirmGateDecisionAtomicTest`/`ConfirmGateLeaseTest` 类注与 RunStore 新键族注释里点明，便于后续回归判断。
- **M9 未跟踪的 `data/` 会跟着 `git add -A` 进库**：仓库根 `data/files/6c03e7f8-….xlsx`（本地跑后端留下的导出件），`.gitignore` 只有 `backend/data/`。提交前删掉并把根 `data/` 加进 ignore。

## Nit

- `ConfirmGateLeaseTest.java:397-406`：`unusedRedis()` / `unusedLease()` 两个纯占位 helper（带 `@SuppressWarnings("unused")`），建议删。
- `RunStore.java:655-668`：`markRequestEmitted` 现在 `pending()` + `HSETNX` + `pending()` = 3 次往返，读路径也多一次 HGET（`pending()`/`pendings()`）。量级可接受，建议在 javadoc 里写明「置位是低频路径，读侧多一次 HGET 是有意换一致性」。
- `scripts/b23l1-dual-instance.ps1`：`PostJson` 修好了 Dispose，但 `GetJson` 仍未 Dispose 响应（同族）；`Stop-Children` 里 `$script:childrenCleaned` 一旦置位，后续 `-KeepAlive` 场景的二次调用会被静默跳过（当前流程无此调用，留痕即可）。
- `LeaseService.shutdown()`（`:335`）只停续租不归还租约，优雅停机后租约最长残留 30s（TTL 自清，可接受；顺手 `releaseIfHeld` 更干净）。
- `LeaseService.healthy`（`:145`）只在 `leaseOk()` 里被读，其余写入点基本无消费者，可考虑删或用于 `/health` 的「上次失败原因」。

---

## 逐条攻击面结论（对应审查大纲 a–i）

| 面 | 结论 | 要点 |
|---|---|---|
| a Lua 原子性 | **通过（有 1 处保留）** | ACQUIRE（GET→INCR→SET PX）、RENEW（GET 比对→PEXPIRE）、RELEASE（GET 比对→DEL）、FENCED_SET（GET fence 比对→SET×2）四支都在单脚本内完成，TOCTOU 封死；`>` vs `>=` 方向正确（`fence > epoch` 才拒，等于放行同任重写）；fence 与快照同一 TTL、同一脚本写入 ⇒ 一致。保留：M1（读侧续期不续 fence）与 M3（epoch 键可被淘汰）。**注意：以上是对脚本文本的静态判定，未在真 Redis 上执行过（I6）** |
| b 可重入 vs 占用拒 | **问题（I3）** | 同实例重抢**不会**拿到别人的新 epoch（视图短路 + owner 比对），这一点是对的；但因此「归属」只剩进程内判据，视图未摘时 A 认不出已被 B 接管（fence 只拦写、不拦执行）。 |
| c 交棒路径 | **问题（C1）** | 交棒后 `markDriving` 由 await 循环在 `fresh.resolved()` 分支调用（时机正确，先于 return）；但 `expireFrontend` 出口把「还在驱动」误判成「不驱动」，边还租边跑。僵尸续租见 I7（应升级，非 Nit）。 |
| d 决策 guard WAL | **部分通过（I1/I2）** | HSETNX guard 本身原子、TTL 与 pending 同源（`ttlFor`，且 touch 续期含 decided）；重复决策仍回 `DUPLICATE`（前端 409 语义不变）；但「补齐 pending」会覆盖赢家正在写的负载（FRONTEND_RESULT 最严重），且读侧不消费 guard。另外 `cancelPendings`（`RunStore.java:826-841`）是唯一不入 guard 的写者，guard 内容可能与条目终态不一致（不阻塞，留痕）。 |
| e req 位合并 | **通过** | `pending()` 多一次 HGET 合并 req，`pendings()` 一次 HGETALL，grant-only 语义与旧 JSON 兼容；`InMemoryRunStore` 镜像逐条对齐（`markRequestEmitted/tryRecordDecision/claimExecution/claimedBy/touchSuspendedRun/deleteRun/save fence`），未发现替身造假；唯一「语义不等价」是默认替身租约永远抢得到（有注释、可接受）。 |
| f 读侧 touch | **通过（时序合理）** | 四处触点齐（`/runs/current`、`/confirm`、`/frontend-tool-result`、`/events`），均**先 touch 后 replay/落库**（`/events` 在 `replayAndAttach` 之前，`AiController.java:333`），对长回放更安全；终态判据在方法内。「get 时 SUSPENDED、touch 时已 DONE」的竞态窗口**无害**：多续 24h TTL，不影响正确性（代价只是残留数据活得久）。M4 记录命名/口径问题。 |
| g 降级路径 | **部分通过（I4）** | `leaseService==null`（6/8 参构造、`RunStore` 3 参构造、InMemory 替身）整条链成立：await 照常等、`maybeTriggerResume` 无租约派发、`resume(runId,force,null)` 走进程内互斥；`leaseOk=false` 在 `/health` 透出（无设施报 false，键恒在）。但 Redis **运行期**故障被 `ResumeService` 翻成 `LEASE_HELD_ELSEWHERE`，与「降级放行」相反。`ResumeService.redis` 保留字段有注释说明，但属死字段（M5）。 |
| h E2E 兼容 | **通过** | 纯 additive：`/health.leaseOk`、`RunSnapshot.asMap.runEpoch`、`PendingToolCall.asList.claimedEpoch`、`/confirm` 的 `resumeOutcome=LEASE_HELD_ELSEWHERE`（既有枚举值集合里的**值**扩展）；前端全仓 grep 对 `leaseOk/runEpoch/claimedEpoch/resumeOutcome` **零引用** ⇒ 客户端零变更成立；`/confirm` 既有 400/409 分支未动，`/api/ai/resume` 只把 `IN_PROGRESS/ALREADY_RUNNING` 显式写进 409（原本就是 default→409，行为等价）。**未跑 E2E 31 用例**（禁起服务）。 |
| i 测试质量 | **通过（有 1 处覆盖缺口）** | 红测可信性：R4（`FirstTwoReadsBarrier` 让两线程都先读到 PENDING）与 M-2（`afterPendingGet` 在读后注入他方写）都是**结构性确定红**，反向验证成立；新设施类（Lease/InstanceId）的「红」只能算编译红，不应计入行为级红证（陈述即可，不算缺陷）。R4 是真并发（两把 submitLock + 共享外置状态 + CyclicBarrier），不是伪并发。G1-G8 覆盖了 §5-2 第 1 条可单测部分（持租/续租/交棒/抢不到/一次性重试/快路径不碰租约/迟到释放）。缺口：真 Redis 面零覆盖（I6）+ 前端超时出口未测（C1 因此没被测试网住）+ DC-11 维度未标注（M8）。 |
| 断言只升不降 | **通过（附一处口径修正）** | `git diff HEAD -- backend/src/test/` 只有两个既有文件：`InMemoryRunStore`（fixture，非断言）与 `AiControllerHealthSseDeliveryTest`（+1 用例、helper 参数化）。逐行读完 `InMemoryRunStore` 的改动：`save()` 把 `DONE/FAILED/CANCELLED` 字面量换成 `RunSnapshot.isTerminal`（**多**含 `REJECTED_ALIAS`，是向生产口径靠拢）、`pending()` 重构保留深拷贝，**没有删断言/降格**。申报的「纯新增方法」略有出入（`save`/`pending` 是被改写而非纯新增），但语义等价且更严格，不构成问题。 |

---

## 自证（wire profile.bind）

`<KIMI_HOME>/sessions/wd_temp_38dcbf7ced2f/session_604b3bc4/agents/agent-1315/wire.jsonl`，`grep -c '"type":"profile.bind"'` = **1**，三值：`modelAlias=DeepSeek/deepseek-flash`、`profileName=coder`、`thinkingEffort=high`（`agentId=agent-1315`）。

---

## 建议处置顺序（若采纳）

1. **C1**（3 行改动，先落）；2. **I1**（WAL 不写不可重建的 FRONTEND_RESULT）；3. **I6**（补一条真 Redis 冒烟，否则前两条无法在 CI 之外复验）；4. I2/I3/I4/I5 作为同批小修或明确挂账；5. I7 需要裁决（加 `max-drive` 还是「驱动者存活判据」）；6. Minor/Nit 可在收口笔顺手（M9 提交前务必处理 `data/`）。

**未做/未验证**：E2E 31 用例、前端 typecheck/build/lint、check-docs、脱敏扫描、双实例 harness 实跑（禁起服务）；`docs/任务看板.md:402/389` 两行未在本批 diff 内更新（收口手续，非代码问题）；批②的施工/红测证据文档不在仓库内（`docs/evidence/` 只有批①四件），红测证据我只能做结构性判定，无法核对原始日志。

---

### 〔段 2／3 · assistant 消息 m217 · 9,329 字符〕

## 复审结论：**CHANGES-REQUESTED**（1 Critical：前端单测 CI 红）

审查对象：`<LOCAL_WORK>/ai-example-code/wt-ai-guardrails-b1`（commit `097fa66`，基于 `d8056d5`），`git diff d8056d5..HEAD` 全量 + 新增文件全文。只读：未改文件、未起服务、未提交、未碰主工作树。

### 复跑证据（实测）

| 项 | 命令 | 结果 |
|---|---|---|
| 后端 | `cd backend && <LOCAL_WORK>/_mvn/.../mvn -q -o test` | **EXIT=0**；surefire 汇总 **728**（report 文件求和 718 + `GenerativeFormGateTest` 的 10 条 `@Nested`；与自报 728 一致 = 基线 702 + 26） |
| 新增类 | — | AiControllerChatGateTest **11** / InputSanitizerTest **8** / AiRateLimiterTest **7** = **+26** ✔ |
| typecheck | `npx vue-tsc --noEmit` | **EXIT=0** ✔ |
| 前端全量 | `node node_modules/vitest/vitest.mjs run` | 26 files / **517 passed**，但 **Errors 5** → **EXIT=1** ❌ |
| 定位 | `vitest run tests/ai-panel-parts-render.spec.ts -t '批① 入口闸门'` | 5 passed / **Errors 5** / **EXIT=1** |
| 对照 | `… -t 'composer v2 阶段二'` | 9 passed / **0 errors** / **EXIT=0** |
| CI 阻断面 | `.github/workflows/ci.yml:80` `run: yarn test`（`package.json:11` `"test": "vitest run"`） | 该步**阻断** ⇒ 本批合入即 CI 红 |

---

## 一、Critical

### C1 新增 5 个前端用例留下 5 条 unhandled rejection ⇒ `vitest run` 退出码 1（CI 阻断步红）

- 位置：`frontend/tests/ai-panel-parts-render.spec.ts:5409-5483`（新 describe「批① 入口闸门」5 例）；崩溃点 `frontend/src/stores/ai.ts:1001`（`history.messages`），触发点 `frontend/src/components/AiPanel/AiPanel.vue:2955-2960`。
- 现象（实测）：5 例**全部通过**，但每次 `mountPanel([])`（空 messages）都会让 AiPanel `onMounted` 走 `if (!ai.messages.length) await ai.loadHistory()`；本文件的全局 `beforeEach`（`:80`）已 `api.history.mockReset()`，新 describe **没有**像其它 describe 那样补 `api.history.mockResolvedValue({…messages: []})`（对照 `:378`、`:1011`、`:1075`、`:2508`）⇒ `api.history()` 返回 `undefined` ⇒ `TypeError: Cannot read properties of undefined (reading 'messages')` 成为 unhandled rejection ⇒ vitest 计 5 errors、**exit 1**。
- 影响：`ci.yml:80` 是阻断步；自报「前端 517（+5）」只报了 passed，**未报 Errors 5 / exit 1**，属实质性漏报（若按自报合流，CI 立刻红）。
- 修法（1 行，按本文件既有惯例）：新 describe 的 `beforeEach` 补
  `api.history.mockResolvedValue({ sessionId: store.sessionId, count: 0, messages: [] })`（必要时连带 `api.runsCurrent.mockResolvedValue({found:false,…})`），或让这 5 例传非空 messages 绕过 `loadHistory` 分支。
- 建议同时**把这 5 例移出该文件**（见 M6）：`ai-panel-parts-render.spec.ts` 是已登记的 mermaid 时敏 flaky 族文件，把 composer 闸门用例放进去既共享了易错 setup（本 C1 的直接成因），又让 flaky 文件更慢。

---

## 二、审查要点逐条结论

**a. 闸门顺序与绕过面 —— 通过（有 1 处设计性缺口 I2）**
- 顺序：必填校验 → **归一化** → 归一后为空 → **长度** → RedisAvailability → **限流** → 会话门 → 线程池（`AiController.java:197-240`）。归一后为空用 `BAD_REQUEST` 拒掉，避免空 user 消息进模型/记忆 ✔。
- `chat()` 新增 `HttpServletRequest` 形参：Spring 注入正常；`clientIp(null)` 兜底 `"unknown"`，直调测试因此共用同一 IP 桶（不越限即可，见既定事实）。全仓生产侧无第二处 `chat(...)` 调用点；测试侧 3 个文件全部适配（2 个既有 + 1 个新增）。
- 未漏面：`/history` 只读、不触发模型，无需闸；`/confirm.reason`、`/frontend-tool-result.result` 属"客户端文本入模"的另一条链，模型侧由 `ToolResultLimiter` 截断兜底（登记 M4）。
- **缺口 I2**：长度/归一化闸在限流**之前**，且被 400 拒掉的请求**不消耗额度**（`overlongMessageDoesNotConsumeRateQuota` 明确锁定）⇒ 超长/不可见字符消息的**洪水完全不经过限流**，每笔仍要做 8KB 级 JSON 解析 + NFKC 多趟 + 一条 INFO 日志。限流对"脚本连发"有效，对"脚本连发超长消息"无效。

**b. 限流正确性 —— 通过（语义正确；两处口径可接受）**
- Lua 单键原子：`TIME → ZREMRANGEBYSCORE(-inf, now-window) → ZCARD → ZADD → PEXPIRE → ZRANGE 0 0 WITHSCORES` 全在一个脚本内，ZADD 只在 `count < limit` 时执行 ⇒ 并发不会超发；成员用 UUID（同毫秒多请求各计一条）✔；`TIME` 用 Redis 时钟（多实例一致）✔；denied 不写成员 ⇒ ZSET 规模被 limit 天然封顶 ✔；每次 hit 都刷新 PEXPIRE ⇒ 键不泄漏 ✔。
- 顺序：会话先、IP 后；会话被拒不消耗 IP（两个独立脚本）—— 反之"IP 被拒白占会话额度"已在类注登记且量级可忽略（60≫20）✔。
- 会话维实际上可绕（sessionId 客户端自生）：类注与配置注都写明"IP 维才是真闸"，测试 `ipDimensionIsSixtyPerWindowAcrossSessions` 逐次换 sessionId 验证 ✔ —— **该口径成立**，但前提是 IP 维真的在（见 I3 的 E2E 张力）。
- 降级：异常/空返回 ⇒ 放行 + WARN（`AiRateLimiter.java:130/141`）；与批②③同口径；`/chat` 开流前的 `RedisAvailability` 503 是另一层，二者不冲突 ✔。Redis 挂 = 无限流，可接受（限流是防滥用非准入前提，且此时对话本身也基本跑不动）。

**c. 归一化安全性与副作用 —— 通过（副作用已定性登记）**
- 剥字符表：按 `Character.getType` 的 **Cf / Cs / Cc** 整类处理，不靠枚举。实测（Java 21）：U+200B/200C/200D/**U+2060**/**U+180E**/U+FEFF/U+00AD/U+202E 全为 `FORMAT`=剥离 ✔；孤立代理项 U+D800 为 `SURROGATE`=剥离，合法 emoji U+1F600 `OTHER_SYMBOL`=保留 ✔；`\n`/`\t` 保留、`\r` 剥离 ✔。
- 幂等：收敛循环 ≤3 趟 + `normalize` 直到不动点；`a\u200B\u0301 → á` 用例锁住"剥字符后可再组合" ✔；`isIdempotent` 四样本 ✔。
- NFKC 折叠面：全角 ASCII 折半角，中文原生标点（`。`/`、`）不折 —— 用例 `foldsFullWidthAsciiPunctuationButKeepsNativeCjkPunctuation` 逐字钉住，并在注释里给出"若连全角 ASCII 也不许动，需定向排除 U+FF00–U+FFEF"的后续选项 ✔。`/history` 回读的 1:1 折叠副作用真实存在（进模型/进记忆的是归一文本），属自决 5 已登记、且用例显式记载 ⇒ **可接受**。

**d. 敏感信息面 —— 通过**
- INFO 只含 `sessionId` + 长度 + 剥离计数（`AiController.java:205-208`）；用例 `logsOnlyLengthDeltaWhenSanitizingChangesTheInput` 断言日志**不含原文**子串 ✔。400/429 回体也不回显原文（`messageTooLong` 只给 length/limit）✔。
- 限流键含 `sessionId` 原文（`ai:rate:session:<sessionId>`）：与既有 `ai:` 键族（session 索引、runId 键）同口径，sessionId 非凭据；无新增暴露面 ✔。逆向风险（超长/怪字符 sessionId 直接进键）是**既有**问题的延伸（chat memory 早就用它做键），登记 N3 即可。

**e. 前端 —— 实现通过，测试 setup 破坏门禁（C1）**
- `:maxlength="MESSAGE_MAX_CHARS"`（`:732`）+ 计数器三档（`剩余 N 字` / `已达 8192 字上限` / `已超 N 字上限`）+ `canSubmit` 加 `<= MESSAGE_MAX_CHARS`（`:1307`）✔；程序化回填超限时标红 + 关发送闸 + **不截断**（用例断言行数 8193 一字不少）✔。
- Tailwind 合规：全部为工具类/任意值语法（`text-[#c0392b]`、`tabular-nums` 等），无手写 style 块 ✔；无 emoji ✔。
- 既有用例零删改：本文件只追加一个 describe（+77/−0），无既有断言变动 ✔。

**f. 断言只升不降 —— 通过（纯签名适配）**
- `SessionBusyFrameIsolationConformanceTest`：4 处 `chat(req)` → `chat(req, null)` + `ReflectionTestUtils` 灌两个闸门字段；断言（200/409、`SESSION_BUSY` 回体、帧序列）**逐字未动** ✔。
- `AiControllerSseLifetimeTest`：1 处 `chat(req, null)` + 工厂方法内 `setField`；断言未动 ✔。
- 全 diff 无其它既有测试文件改动；无删除、无放宽。

**g. 测试质量 —— 后端强，替身缺"脚本正文契约"（I1）**
- 红测可信性：`AiControllerChatGateTest` 的 8193→400、归一化→模型收到归一文本（ArgumentCaptor）、第 21 次→429、降级→200、顺序→"超长不占额度"、日志不含原文、两条 MockMvc Servlet 面 —— 都是行为级断言，去掉实现即红 ✔。`InputSanitizerTest` 8 例覆盖四类样本 + 幂等 + 收敛 ✔。
- **缺口 I1**：`FakeRateRedis` 只按标记 `RATE_SLIDING_WINDOW` 分派，`AiRateLimiterTest` 没有任何 `getScriptAsString()` 正文契约断言 ⇒ 把 Lua 改坏（`ZADD` 挪到 `ZCARD` 前、删掉 `ZREMRANGEBYSCORE`、用 `ARGV` 时间替代 `TIME`）**全绿**。批③a 已确立"按标记分派的替身测不出正文改写"的教训（`RunStoreIdempotencyIndexTest` 有正文断言），本批未复用。

**h. E2E 兼容 —— 一半成立**
- 消息长度：成立。最长的是 `$promptGf1/$promptGf6`（≈150–160 字符），远低于 8192 ✔（自报"最长 158"量级一致）。
- **"每 session 2 次"不成立（I3）**：`verify-e2e.ps1` TC18 取消后重试循环 `for ($i = 0; $i -lt 20 …) { … POST /chat … }`（`:2123`）**同一 `$sc`** 最多再发 20 次；此前 `:2102` 最后一次尝试 1 次 + `:2112` busy 探测 1 次 ⇒ **同一 session 最多 22 次 > 20/分**。取消释放在 >18 秒时，循环第 19 次会先撞 429，然后 `elseif ($r.status -ne 409) { throw … }` 直接判 TC18 失败。另：E2E 后端以默认限流启动（`ci.yml:176-180` 未传 `--app.ai.limit.*`），全流程 /chat ≈ 25–45 次（14 次 AiTurn/FormTurn + TC18 ≤23 + TC23 ≤3），与 IP 60/分 的余量也不宽裕。

---

## 三、分级清单

### Critical
- **C1** 见上：新增 5 例 unhandled rejection ⇒ `yarn test` exit 1 ⇒ CI 阻断步红。修法见上（1 行 setup + 建议移出 flaky 文件）。依据：纪律「前端单测全绿」+ `ci.yml:80` 阻断步。

### Important
- **I1** `FakeRateRedis`/`AiRateLimiterTest` 无脚本正文契约断言（批③a 教训未复用）。修法：仿 `RunStoreIdempotencyIndexTest:106-120` 加一组 `assertThat(lastScriptBody())`——`RATE_SLIDING_WINDOW`、`'ZREMRANGEBYSCORE', KEYS[1]`、`'ZCARD'`、`'ZADD'`、`'PEXPIRE'`，并断言 `indexOf(ZREMRANGEBYSCORE) < indexOf(ZCARD) < indexOf(ZADD)`；再断言正文含 `'TIME'`（时间口径）。风险：否则 `TIME`/顺序/TTL 任一改写无测试网。
- **I2** 闸门顺序使 400 类拒绝完全绕过限流：超长/纯不可见消息可无限速刷（每笔 8KB 解析 + NFKC 多趟 + INFO 日志）。修法二选一：① 把限流提到长度闸之前（所有 `/chat` 都计数，代价是"超长也占额度"，需同步改 `overlongMessageDoesNotConsumeRateQuota` 的语义与用例）；② 保留现序，另加一道"请求体 > N 直接 413/400 且计一次 IP 额度"的廉价前置。建议裁决一次，二选一落地。
- **I3** E2E TC18 同 session 最多 22 次 /chat > 20/分（+ 全流程 IP 余量不宽）。修法：E2E 后端启动参数显式抬高限流（`--app.ai.limit.session-per-minute=200 --app.ai.limit.ip-per-minute=2000`），并在 `ci.yml` 与 `verify-e2e.ps1` 头部注明"限流不在 E2E 覆盖面，另由单测覆盖"；或把 TC18 重试预算压到 ≤18 次并注明理由。

### Minor
- **M1** `app.ai.limit.*` 无正数校验：`session-per-minute: 0` ⇒ `/chat` 全 429，且 ZSET 为空时 `retry=0 → Retry-After: 1s` 永久循环。建议仿 `sse.write-timeout` 的启动期守卫做 `@PostConstruct` 校验（>0）。
- **M2** 前端 `MESSAGE_MAX_CHARS = 8192` 与后端 `app.ai.input.max-chars` 无交叉校验（现仅靠注释"同值"）。建议加一条契约测试：后端断言默认值 = 8192（已有）+ 前端 spec 里读 `application.yml` 或加一条"改动必须两处同步"的 CI grep。
- **M3** `context`（`page`/`taskType`/`step`）不做归一化，直连披露判定与快照；方向 fail-closed（零宽只会让工具不被披露），建议在 `InputSanitizer` 类注登记该边界。
- **M4** `/frontend-tool-result.result`、`/confirm.reason` 也是客户端文本入模（经 pending 条目），无长度闸；模型侧 `ToolResultLimiter` 截断兜底 ⇒ 不阻塞，建议登记到后续（输出/工具结果防护）批。
- **M5** 计数器 `aria-live="polite"`（`AiPanel.vue:746`）会在每次按键播报剩余字数（读屏噪音）。建议只在"≤500/到顶/超限"三态切换时播报（或用 `aria-describedby` 挂静态说明）。
- **M6** 新用例落进已登记 flaky 的 `ai-panel-parts-render.spec.ts`（mermaid 时敏族）。建议移入独立 `ai-panel-composer-limit.spec.ts` —— 同时天然规避 C1 这类共享 setup 陷阱。

### Nit
- **N1** 无 `\r\n` 归一 与 孤立代理项的显式用例（行为已被 Cf/Cs 规则覆盖，Java 21 实测通过；补两例可防未来重构误删该规则）。
- **N2** IP 维放行时 `Decision.scope = none`（`AiRateLimiter.java:179`），观测上分不清"哪一维放行"；字段注已说明，仅提示。
- **N3** `clientIp` 兜底 `"unknown"` 让所有无 RemoteAddr 的请求共用 60/分（测试即如此）；生产无代理不触发，登记即可。
- **N4** 自报"两处环境性红"：本次实测 **后端一次即绿（EXIT=0，无 fork 崩）**、前端全量 **mermaid 族 0 失败**（唯一红是 C1 的 5 条 unhandled）⇒ 两条环境红在本环境**未复现**，不能用来解释 C1；建议报告把"517 通过"改为"517 passed + 5 errors + exit 1"。

---

## 四、自证（wire profile.bind）

`<KIMI_HOME>/sessions/wd_temp_38dcbf7ced2f/session_604b3bc4/agents/agent-1315/wire.jsonl`，`grep -c '"type":"profile.bind"'` = 1，三值：
`modelAlias=DeepSeek/deepseek-flash`、`profileName=coder`、`thinkingEffort=high`（`agentId=agent-1315`）。

---

## 五、放行条件

1. **C1**：新 describe 补 `api.history`（及必要时的 `api.runsCurrent`）stub，使 `vitest run` **exit 0**；最好同时把这 5 例移到独立 spec。（复验命令：`node node_modules/vitest/vitest.mjs run`，期望 `517 passed / 0 errors / exit 0`。）
2. **I1**：补脚本正文契约断言（防 Lua 改写）。
3. **I2 / I3**：各需一次裁决或一次性改法（前者改闸门顺序或加前置，后者改 E2E 启动参数/重试预算）。

以上 1 必须；2、3 若指挥官决定挂账，请在收口报告里明确登记为"已知缺口 + 承接批次"，我可以按 READY-WITH-NITS 复评。

---

### 〔段 3／3 · assistant 消息 m264 · 10,622 字符〕

## 复审结论：**READY-WITH-NITS**

审查对象：`<LOCAL_WORK>/ai-example-code/wt-model-config-b1`（`6c19f63`，基点 `d8056d5`），`git diff d8056d5..HEAD` 全量 42 文件 + 新增文件全文 + 设计稿 §3/§8/§10/§11 逐条对照。只读：未改文件、未起停服务、未提交、未碰主工作树（仅读主工作树的设计稿一份，方式为直接读文件）。

### 复跑证据（实测）

| 项 | 命令 | 结果 |
|---|---|---|
| 后端 | `cd backend && <LOCAL_WORK>/_mvn/.../mvn -q -o test` | **EXIT=0**；surefire 汇总 **745**（report 求和 735 + `GenerativeFormGateTest` 的 10 条 `@Nested`）＝ 基线 **702 + 43** ✔ |
| 新增测试类 | — | 8+3+11+6+2+4+4+2+3 = **43** ✔（AiKeyCipher 8 / ProviderLeak 3 / ConfigService 11 / Seeder 6 / AvailabilityDynamic 2 / UnavailableSpring 4 / HotReload 4 / ConfigController 2 / SnapshotIdentity 3） |
| 前端 | `node node_modules/vitest/vitest.mjs run` | **512 passed / 26 files / EXIT=0**（本批无前端改动）✔ |
| typecheck | `npx vue-tsc --noEmit` | **EXIT=0** ✔ |
| check-docs | `node scripts/ci/check-docs.mjs` | **6/6 全绿** ✔ |
| E2E 证据 | `<LOCAL_WORK>/_b1-e2e-run/` | `boot3.log`：`Migrating … "4 - ai provider config"` + `Successfully applied 4 migrations … v4` + `AI 配置种子已写入：provider=deepseek key_ref=ENV`；`e2e-run3.log`：**PASS=30 / FAIL=1**（唯一 FAIL=MAJOR-2）；`artifacts/gfc` 14 个文件在位；H2 文件 `grep ci-stub-placeholder` = **0**、`grep v1.k1` = **0** ✔ |

---

## 一、审查要点逐条结论

### a. 安全面 —— **通过（1 处 Minor 偏离）**
- **V4 SQL 逐行读**：四表列类型/长度与设计稿 §3.1 表逐一对应（`key_cipher VARCHAR(2048)`、`key_last4 VARCHAR(8)`、`key_fingerprint VARCHAR(64)`、`key_ref VARCHAR(8) DEFAULT 'NONE'`、`source VARCHAR(16) DEFAULT 'DB'`）；`code` 唯一约束、`(provider_id, model_id)` 唯一约束在位；`ai_model.provider_id` 刻意不加 FK（软删口径）与设计一致；`ai_setting` 预置 `config.version=1` ✔。H2 兼容性已由 E2E（V4 实跑）与两个 `@SpringBootTest` 双重证明 ✔。
- **AiKeyCipher**：AES-256-GCM / 12B `SecureRandom` IV / 128bit tag / AAD=`code|keyRef` / 信封 `v1.<kid>.<iv>.<ct+tag>`（b64url 无填充）/ KEK=env base64 且必须解出 32 字节否则 null / 三操作全部 fail-closed / 异常消息只带类名不含密钥材料 ✔。`AiKeyCipherTest` 8 例把 AAD 两侧（code、keyRef）、换 KEK、错 kid、缺 KEK、坏 KEK 全钉住，并给出证红方法 ✔。
- **泄密面**：`AiProvider.keyCipher` 有 `@ToString.Exclude` + `@JsonIgnore` ✔（`AiProviderLeakTest` 断言 toString/日志/诊断视图三处均不含密文与明文）；`ProviderView` 只有 `keyConfigured/keyLast4/keyRef`（无 cipher/fingerprint/apiKey）✔；`AiConfigController` 只读 1 个端点、无写面、无 501 占位 ✔；`AiConfigControllerTest` 断言响应体不含 `secret`/`envelope`/`v1.k1.` 且必含三件套 ✔。
- **E2E 侧硬证据**：H2 文件里 `ci-stub-placeholder`（env key）**0 命中** ⇒「env key 不落库」得到实证（设计稿 §8-2 第一条）✔。
- **Minor M1**：`keyFingerprint` 未加 `@ToString.Exclude`（见分级）。

### b. 种子正确性 —— **通过**
- `count()==0 且 env key 非空` 双判据；写库在 `TransactionTemplate` 内；`DataIntegrityViolationException | ConstraintViolationException` 视为「别人已种」记 INFO；`run()` 外层兜住所有异常 ⇒ 启动绝不失败 ✔。
- **并发双启动有真测**：`AiConfigSeederTest.concurrentDoubleStartupSeedsExactlyOneRow` 用两线程 + latch 同刻调 `seed()`，断言零抛异常、恰一行 ✔；`uq_ai_provider_code` 是兜底 ✔。
- **strict seed 有真测**：`secondStartupDoesNotWriteAgain` / `strictSeedKeepsTheDatabaseValueEvenWhenEnvChanged` / `seedForceIsTheOnlyWayToRefillFromEnv`；`writeSeed(force)` 对「来源≠ENV_SEED」直接 return，`seed-force` 只重灌 ENV_SEED 行 ✔。
- **合成行（id=null）副作用面**：`effectiveProviders()` 仅在「表空 ∧ env 有 key」时返回合成行；`listProviders()`（`/api/ai/providers`）与 `ConfigSnapshot` **都不含**合成行 ⇒ 不进版本快照、不被当成「已配置」；`/health.providers` 能看到它（`id=null`、`source=ENV_SEED`）✔。唯一边角见 M5（种子失败时设置页会显示空但 AI 可用）。

### c. 装配改造 —— **通过（整链路面证据强）**
- 4 处置 `@Conditional` 全部移除（`AiModelConfig` 内嵌配置类整块删除、`ResilientChatService`、`ResumeService`、`StartupResumeRunner`），`AiKeyPresentCondition` 删除且**全仓零残留引用**（grep 空）✔。
- 「以前不建、现在会建」的三个 bean 的构造器均无副作用；`aiStreamWatchdog` 等执行器 bean 本就在无条件配置类（`AiExecutorConfig.java:64`）✔；`ApplicationRunner`（种子）→ `ApplicationReadyEvent`（续跑扫描）次序保证扫描看不到「种子未写」中间态 ✔。
- **`StartupResumeRunner` 自检**：`@EventListener` 与 `scan(true)` 两条入口都先问 `availability`，不可用时返回 `{scanned:false, reason:code(), message}` 且不报错 ✔；`/api/ai/startup-resume`、`/api/ai/startup-resume/scan` 也补了同形分支（`AiController.java:408-437`）✔。
- **`AiConfigUnavailableSpringTest`（真 `@SpringBootTest` + 空 H2 + 清空 env key）**：0 行、`/health` 503 且旧键值逐字（`model=deepseek-flash`、`baseUrl=https://api.deepseek.com`、`code=AI_UNAVAILABLE`、原文案）、`/chat` 503 且不建流、`/providers` 空列表 —— 这是「移除 conditional 后对外行为不变」的整链证据 ✔。

### d. 热生效 —— **通过（实现与测试都真）**
- `RuntimeKey = providerId | keyFingerprint | baseUrl | completionsPath`（`runtimeKeyOf`，模型刻意不在内）✔；版本比对 1s 节流（`reloadInterval`，单测设 0）✔；Redis `ai:cfg:ver` 优先、不可用退化为一次主键查询 ✔。
- **轻量重绑**：同 key 换 model ⇒ 复用 `cached.api()`、只重建 model+client（`AiProviderRegistry.java:105-118`）✔；`ProviderRegistryHotReloadTest` 用**两个真 HTTP 桩**做流式请求：B13 断言「旧桩 1 次、新桩 1 次、`api` 不 `isSameAs`、缓存仍 1 条」，B14 断言「`api isSameAs`、`chatClient` 不 `isSameAs`、请求体 `model` 换成新值」✔ —— 这是「事实」而非「推断」，B14 正是我要求的 `isSameAs` 形态。
- 在飞请求持旧引用：`current()` 每次返回不可变 `ProviderRuntime`，请求本地持有 → 旧实例跑完、下一轮取新 ✔（设计 §3.4 口径，无需额外测试）。
- **Minor M2**：`lastRedisVersion` 在 load 成功之前推进（批② 有写路径后才会咬人）。

### e. 行为等价 —— **通过**
- 请求面逐值：`apiKey`=chat?:connection、`baseUrl`=chat?:connection、`completionsPath`=chat?:`/chat/completions`、`model`=options.model?:`deepseek-flash` —— 与 `cd18b37` 前 `AiModelConfig.openAiApi/chatModel` 的回落规则逐条一致 ✔；默认选项以 `OpenAiChatProperties.getOptions()` 为基底且 `streamUsage` 缺省补 true（`runtimeOptionsInheritTheEnvDefaultsIncludingStreamUsage` 断言 0.2/4096/true）✔。
- `/health` **additive**：旧键（`available/code/model/baseUrl/message`）保留同义；新键（`providerId/defaultModelId/providers`）在 null 时因 `spring.jackson.default-property-inclusion: non_null`（`application.yml:55`）直接不出现 ⇒ `AiConfigUnavailableSpringTest` 的 `doesNotExist()` 断言成立、503 形态逐字不变 ✔。
- 空表+无 env 的 503 契约：`code=AI_UNAVAILABLE` + 原文案逐字（`LEGACY_UNAVAILABLE_REASON`），Spring 测试与单测双锁 ✔。

### f. 与批③合流冲突面 —— **可预判：仅 1 处冲突**
用 `git merge-file` 以 `d8056d5` 为基做三方模拟（三个版本都从本仓对象库取出，不碰主工作树）：
| 文件 | 结果 |
|---|---|
| `AiProperties.java` | **CONFLICT ×1 处**：双方都在 `private Lease lease = new Lease();` 之后插入字段（批③ 加 `Lock`、本批加 `Config`）。解法 = 两个字段都留、两个嵌套类都留 |
| `RunStore.java` / `ResumeService.java` / `InMemoryRunStore.java` / `AiController.java` | **clean**（批③ 的 hunk 与 `create` 7 参重载/`@Conditional` 移除/touch 调用区不重叠） |
| `application.yml` | 批③ **未动**该文件 ⇒ 无冲突 |

补充事实：`AiController` 的 `touchActiveRun`/`leaseOk` 是**批②提交**（`cd18b37`）带进来的，批③（`c00606e`）没碰它 —— 所以本批的 `/health` 扩展与 `leaseOk` 在合流后是**不同行的 additive 键共存**。合流后建议两侧全量各跑一次（本批新增 43 条、批③ 新增 74 条，互不覆盖）。

### g. 断言只升不降 —— **通过**
3 个既有测试类的改动逐行核完：
- `ChatTurnFrameSequenceConformanceTest` / `ConvergeMemoryToolCallStatesTest` / `EmptyReplyTerminalTest`：**只**新增 `AiProviderRegistry` 桩 + 固定 `ProviderRuntime`（包住原有 `ChatModel`/`ChatClient` 桩）+ 构造器参数从 `(chatClient, chatModel, …)` 换成 `(registry, …)`；所有既有断言一字未动 ✔。
- `InMemoryRunStore.create`：签名从 5 参改为 7 参并镜像两个 additive 字段（fixture，无断言）✔。
- 无任何删除/放宽；本批唯一「删除」是 `AiKeyPresentCondition.java`（装配闸退役，设计 §10-6 裁决 A）✔。

### h. 测试质量 —— **通过（1 处 Nit）**
- 43 条新例中，「能力不存在类」与「行为纠正类」区分清楚，每条类注都写了**证红方法**（B1 去 AAD、B2 固定 IV、B3 去 `@ToString.Exclude`、B5 缺 KEK 兜底、B10 去 availability 门、B13 RuntimeKey 去 baseUrl、B14 model 进 RuntimeKey）—— 抽核成立，可实现反向验证。
- **非 mock 自证**：`AiConfigServiceTest` 的仓储是「内存表 + Mockito 桩」（夹具自述并说明理由），但服务/注册表跑真代码；装配面、种子面、503 面各有一个**真 `@SpringBootTest`**（含 Flyway V4 + 真 H2），且仓储派生查询方法名在上下文启动时被 Spring Data 校验 ⇒ 拼错会启动失败 ✔。热生效用**真 HTTP 桩**而非 mock ✔。
- **Nit N3（E2E 口径）**：自报「29 主用例全 PASS」与日志 `PASS=30 FAIL=1` 不符；「与 d8056d5 基线逐条 diff 为空」在该证据目录里无基线 log 可核（MAJOR-2 的失败点是 `DELETE /api/jobs/28 → 409 JOB_ALREADY_FINAL`，与本批改动面无关，判断为基线既有 flake 属 plausible）。

### i. E2E 证据 —— **通过（证据真实且可抽查）**
`boot3.log`：Flyway V1→V4、`Successfully applied 4 migrations … v4`、种子写入日志、**无** `sk-`/`ci-stub-placeholder` 出现在日志；`e2e-run3.log` 有逐用例 PASS 行与末尾摘要；`artifacts/gfc` 14 个帧/HTTP 证据在位；H2 文件对 env key 与 `v1.k1` 均 0 命中。报数口径见 N3。

### j. 文档口径 —— **部分通过（Minor M4）**
- **V4 号段**：V1–V3 已存在，本批用 V4 ✔（i18n 顺延 V5 已由另案裁决，无占用冲突）。
- **`application.yml` 注释已同步**（三键语义、Q2 保留理由、`app.ai.config.*`）✔。
- **判定**：`application.yml.example` 与 `03-技术方案文档` 的环境变量语义更新**可以缓到批②**（example 里的 `DEEPSEEK_API_KEY` 注释仍成立；KEK 只在有 DB key 时才需要）；但 `README.md:103` 的「为空则不装配模型 bean」**已被本批改动作废**，且 strict seed（首启后改 env 无效）是**运维可见**的语义变化 —— 这一条建议本批补 3–5 行（或显式登记到批② 并在 README 打 TODO），否则升级后的运维会踩「我改了 env 怎么没用」。

---

## 二、分级清单

### Critical
无。

### Important
无（安全面、装配面、热生效面、行为等价面均通过，且各有真证）。

### Minor
- **M1 `AiProvider.keyFingerprint` 未排除出 toString**：`backend/.../ai/config/db/AiProvider.java:84-85`。HMAC 指纹会随实体打印进日志，偏离设计稿 §3.2 泄漏面第 3 条「审计/日志只写 last4 + 动作」的字面；`AiProviderLeakTest` 未覆盖该字段。修法：加 `@ToString.Exclude`（`@Column` 保留），并在 leak 测试补一条「toString 不含 fingerprint 值」。
- **M2 `lastRedisVersion` 在 load 成功前推进**：`AiConfigService.java:165-171` 与 `:138-140`。`load()` 内部吞异常返回旧/空快照，但版本号已被标记为「已见」⇒ 该实例会停在旧快照直到**下一次**版本变更；批① 无写路径（无人 `INCR ai:cfg:ver`）所以当前不可达，**批② 引入写路径后即成为「改配置这台实例不生效」**。修法：把赋值挪到 load 成功之后（`load()` 返回 boolean，或比较新旧 snapshot 再推进标记）。
- **M3 `buildOptions` 对 `ai_setting` 值无容错**：`AiProviderRegistry.java:171-176`。`Double.valueOf/Integer.valueOf` 遇脏值抛 `NumberFormatException` → 冒到 `/chat` 变 500。修法：try/catch + WARN + 忽略该项（批② 写路径落库前也要校验）。
- **M4 README 运维语义过期**：`README.md:103`（「为空则不装配模型 bean」）+ 未写 strict seed 与 `AI_SECRET_KEK` fail-closed。修法：本批补 3–5 行，或登记到批② 并标明「升级须知：首启后 env 只作种子」。
- **M5 合成 env 行不进 `/api/ai/providers`**：`AiConfigService.listProviders()` 只回真行。种子写失败而 env 可用时，设置页会显示「无 provider」但 AI 实际可用（`/health.providers` 可见合成行、`id=null`）。建议批② 设置页对「env 兜底未落库」给提示；本批登记即可。
- **M6 `encrypt(null)` 报 `AI_SECRET_KEK_MISSING`**：`AiKeyCipher.java:111-113`。key 为空 ≠ KEK 缺失，批② 会把它映射成误导性的 400 文案。修法：新增 `KEY_EMPTY`（或按 BAD_REQUEST 语义分离）。
- **M7 控制器字段白名单是黑名单式**：`AiConfigControllerTest` 的 `noneMatch` 只拦具体名字（`key/apiKey/keyCipher/cipher/keyFingerprint/secret`），新增 `keyCipherV2` 之类不会被拦。修法：断言精确键集（顶层 + `models[]` 两层 `containsExactlyInAnyOrder`）。

### Nit
- **N1** V4 无 CHECK 约束（`key_ref ∈ {DB,ENV,NONE}`、`source ∈ {ENV_SEED,DB}`），脏值只能靠应用层。
- **N2** `AiKeyCipher`：解码后的 KEK byte[] 未清零（Java 常规限制，可登记）；`kid` 未做字符校验（含 `.` 会破坏信封分段，建议限 `[A-Za-z0-9_-]{1,16}`）。
- **N3** E2E 报数口径：「29 主用例全 PASS」应为「PASS=30 FAIL=1（MAJOR-2 基线既有）」；基线对比缺出处。
- **N4** `encrypt` 的异常码复用 `KEY_UNREADABLE`（`AiKeyCipher.java:127`），语义偏「读」；建议 `KEY_WRITE_FAILED`。
- **N5** `/health` 的 `model/baseUrl` 取值来源改为「快照优先、env 回落」；对现行 yml 逐值等价（Spring 测试已锁），仅当只设 `spring.ai.openai.chat.base-url` 时显示值会变（属修正）。
- **N6** `GET /api/ai/runs/{runId}` 在「AI 不可用」时不再是 `{exists:false}`（ResumeService 恒存在），只在「先有轮次后撤 key」时可见，属诊断面改善。

---

## 三、合流建议（给提交官）

1. 先合本批，再合批③（或反之），**只需手工解 `AiProperties.java` 一处冲突**：保留 `private Lock lock` + `private Config config` 两个字段与两个嵌套类。
2. 合流后各跑一次全量：预期 745 + 74 −（共享类去重）= 由实测决定；两边新增测试互不覆盖，冲突面仅上述 1 文件。
3. 合流后再跑一次 check-docs（本批已 6/6）与前端全量（本批 512/512，无前端改动）。

## 四、自证（wire profile.bind）

`<KIMI_HOME>/sessions/wd_temp_38dcbf7ced2f/session_604b3bc4/agents/agent-1315/wire.jsonl`，`grep -c '"type":"profile.bind"'` = **1**，三值：
`modelAlias=DeepSeek/deepseek-flash`、`profileName=coder`、`thinkingEffort=high`（`agentId=agent-1315`）。
