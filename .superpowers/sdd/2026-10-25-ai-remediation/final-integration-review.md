# 最终独立代码复审（只读）

> **审查对象**：主仓 `<主仓>`，HEAD=`2eb2539594412293493398faac04d681a7cf895a` + 当前工作区全部 tracked/untracked 候选（51 改 + 27 新，排除 `data/`、`test-results/`、运行日志、`.superpowers`）。
> **依据**：计划 `docs/superpowers/plans/2026-10-25-ai-remediation.md`、审计 `<仓外证据>/ai-full-audit/FINAL-AUDIT.md`（223 条）、台账 `progress.md`。
> **性质**：只读。未改任何生产代码/测试/配置，未提交、未推送、未起服务、未读密钥、未跑全量。写入物仅本文件。
> **限定**：多用户专题、九册正文不在范围；已登记的 Minor/真实环境未验证项（真 Cluster/PG/KEK/真模型/≥3 JVM 真时序）不复述、不作为阻断。

## VERDICT

> **2026-10-25 追补**：I-1 已由增量补丁 `task-8-final-fix.patch` 闭合（复核见 `final-integration-rereview.md` §1，**ADDRESSED**）；原 M-2 / M-7 经重证后**升级为 Important**（同文件 §2/§3）。本节的"1 项阻断"已不适用，请以 re-review 的阻断表为准。

**NOT APPROVED（1 项 Important 阻断）** —— 其余全部变更链路在代码与聚焦用例层面成立；修复后的 T-13 / C-11 与真实 CI 断言**逐条对应**（断言未被放宽）。阻断项是一处**新引入的空指针**：会话门"持有者不可知"（RT-35 修复）的下游消费点未同步，`POST /api/ai/chat` 在该竞态下由可恢复的 409 退化为 **500**。修法是 3 行空值守卫，不动任何语义。

---

## 1. 阻断项（Important，唯一）

### I-1 `SessionGate.Busy(runId=null, unknown=true)` 未同步到 `AiController`：发送路径抛 NPE → HTTP 500

**结论**：RT-35 的修复让 `Busy.runId()` 首次可以合法为 `null`（"持有者不可知"），但 `AiController` 的**死轮判定**直接把它当 runId 用 ⇒ `ConcurrentHashMap.get(null)` NPE ⇒ 500。这是新引入的"错误失败"（本该 409 可恢复）。

**完整调用链**

1. `backend/src/main/java/com/example/configmgr/ai/web/AiController.java:411-415`
   `String runId = UUID…; SessionGate.Busy busy = this.sessionGate.acquire(sessionId, runId);`
   → `if (busy != null && isDeadRound(busy.runId()))`
2. `SessionGate.acquire` 在 `taken == FALSE`（脚本说"不是我的锁"）后调用
   `busyHeldByAnother(sessionId)`（`ai/session/SessionGate.java:405-417`）：
   诊断 GET 抛错或键已消失 ⇒ `return new Busy(null, true, true)`（`:414`，`runId=null`）。
3. `AiController.isDeadRound(null)`（`AiController.java:834-859`）：
   - `this.runStore.get(null)` → 键 `{run:null}` GET 返回 `null`（Redis 正常时不抛）
   - `this.runRegistry.hasLiveSubscriber(null)`（`:848`）→ `RunRegistry.java:167-170` → `this.emitters.get(null)`
   - `emitters` 是 `new ConcurrentHashMap<>()`（`ai/run/RunRegistry.java:48`）⇒ **NPE**
   - 若上一步未先炸，`recycleIfIdle(null)`（`:180-186`，`emitters.get(null)`/`remove(null)`）同样 NPE。
4. 该段无 try/catch（`AiController.java:380-460` 连续代码），异常穿到全局处理器 ⇒ **500 INTERNAL_ERROR**。

**具体触发输入**
- `POST /api/ai/chat`，`sessionId=S`，且此刻会话锁 `ai:session:lock:S` 正被另一轮持有（脚本返回 0），而紧随其后的诊断 `GET` 失败或键已消失：
  - 锁在 `EVAL` 与 `GET` 之间到期（`session.lock.ttl` 边界）；
  - 另一实例恰在此刻 `release`；
  - Redis 单命令抖动（入口 `RedisAvailability` 探活已过，故请求仍会走到这里）。
- 复现最小面：`sessionGate.acquire` 返回 `new SessionGate.Busy(null, true, true)` 时打一次 `/chat`。既有用例全部给的是非 null（`ChatSessionBusyBodyTest.java:88`、`AiControllerZombieGateTest.java:86/105/134/166`、`AiControllerDeniedLogTraceTest.java:131`），且 `RunRegistry` 在这些用例里是 mock（mock 的 `get(null)` 不抛）⇒ **正是"mock 掩盖真实行为"的典型点**。

**依据（运行证据）**
- JDK 实测：`new ConcurrentHashMap<>().get(null)` / `.remove(null)` 均抛 `java.lang.NullPointerException`（本机 JDK 11.0.27；JDK 21 契约相同）。
- `SessionGateBusyUnknownTest` 3/3 绿，日志明确产出"会话门被占用但持有者不可知 … 按 unknown 拒绝"——即该 `Busy(null,true,true)` 在真实代码路径上**会**被返回。

**最小修复方向**（不扩语义）
- 在 `AiController.java:415` 前加空值分流：`if (busy != null && busy.runId() != null && isDeadRound(busy.runId()))`；
- 或者更稳：`RunRegistry.hasLiveSubscriber/recycleIfIdle` 对 `null/blank` 直接返回 `false`；
- 同处 `:429` 的 `reattach = "/api/ai/events/" + busy.runId()` 在未知时应省略/置 null（现在会下发 `…/events/null` 这一不存在指针，前端当前不消费，属顺带修正）。

---

## 2. Minor（单列，不扩、不阻断）

| # | 位置 | 事实 | 影响 |
|---|---|---|---|
| M-1 | `ai/gate/ConfirmGate.java:1720-1722`（`settleParkedToolCall` javadoc，方法在 `:1744-1751`） | 注称"走 `expireFrontend` 的卡片仍不发结算行 —— 存量缺口"，但同文件 `:1107-1108` 已在到期收敛后调用 `settleParkedToolCall` | 注释与实现相反，取证时会误判覆盖面 |
| M-2 | `SpToolCallingManager.java:1846-1860`（`resolvePending` 复用分支不发 end）+ `ConfirmGate.submitDecision` 拒绝分支不调用 `settleParkedToolCall`（只有 `:1108/1653/1703` 三处） | park 模式下 **CONFIRM 卡被拒绝/被 `/cancel`** 时：`tool_call_start` 存在而 `tool_call_end` 缺失（0 条）；若决策恰在 `switchParkedLifecycle` 交接窗口到达、批循环接手，则会有 1 条 ⇒ 同一语义"0 或 1" | 仅日志/审计配对不齐（与文档手册 §2.5-2 的"start 必有 end"不变量冲突），无数据面后果；属 PK-06 的残留面，非本次回归 |
| M-3 | `ai/run/RunStore.java:940-976`（`clearSessionCurrent`） | 每轮终态后遍历会话 run 索引并 `get(candidateId)` **完整反序列化快照**（含整窗 `messageJson`）找"同会话仍活跃的最新一轮" | 会话 run 数增长后，轮末写路径 O(N×快照大小)；同文件已有 `snapshotOwner` 的轻读范式可复用 |
| M-4 | `ai/run/SseChatEmitter.java:1477-1483` + `RunStore.permittedToWrite` | 每业务帧多一次 `ai:fence:<runId>` GET（RT-12 的每帧往返 +1）；fence 读失败按 fail-closed 丢帧，**含终帧** | 慢 Redis 下串行化更明显；单命令抖动可能丢失终帧（与 RT-01 同族，口径已登记） |
| M-5 | `frontend/src/views/ExportWizardView.vue:1339-1400`（`saveEdits`） | 已载入的表格**先 PUT 成功**，随后因存在未载入项而 `ElMessage.warning('…本次未保存')` 并提前 return，`gridDirty` 不复位 | 回执反向失真（已保存却说未保存）+ 卸载时"未保存"误报；无数据损失（同 FE-50 的修复方向是对的） |
| M-6 | `ai/run/ResumeService.java:691`（`cancelStateOutcome` 调用点；方法 `:1156-1173`） | 取消收敛在 `isTerminal` / `RUNNING` 判定与**会话门 acquire 之前**执行；`POST /runs/{id}/resume` 对"仍在跑的 RUNNING 轮 + 已置取消标志"会并发 `cancelParked` | 与活驱动方各收一次尾 ⇒ 仓库多一个 `done`、`cancelTerminal` 以 `snapshot.assistantContent` 再补一条 stopped 助手消息。触发仅限手工/运维 resume（启动扫描按 `resumeable` 过滤、`/cancel` 只对 SUSPENDED 派发） |
| M-7 | `ai/session/AiSessionPurgeService.java:441-459` + `SessionPurgeRetryRunner.java:187-211` | 60s 记忆复核台账沿用同一 `attempts`；若删除主体是先失败 10 次后成功，`verifyAtMs` 台账一出生即"已耗尽" ⇒ 只重排不执行 ⇒ pending/守卫长期保留（该 sessionId 继续 410） | 有界且仅影响该会话 id（页签 UUID），可手工 DELETE 收敛 |

---

## 3. 真实 CI T-13 / C-11 与修复的对应性（逐条核对）

- **T-13**（`scripts/verify-e2e.ps1:3752-3810`）：断言 `frontend_tool_request → 回灌 → frontend_tool_result(恰 1) → done` 且 `readCalls` **严格增长**。本次 diff **未改动该断言**。
  生产链已接通：`ConfirmGate.submitFrontendResult` → `settleParkedToolCall`（`ConfirmGate.java:1646-1654`）→ `ToolCallEndTrace.emitForSettledEntry` → `ai:observed` 首写仲裁 → `SheetAudit.record`；续跑执行的工具另由认领胜者发（`SpToolCallingManager.java:1527-1531`）。⇒ 对应真实缺陷（park 化后丢 end/丢记账），不是放宽断言。
- **C-11**（`verify-e2e.ps1:4070-4097`）：park 挂起取消后 20s 内 `/context.usedTokens ≥ 1`。断言未改。
  生产链已接通：`POST /api/ai/cancel` → `ConfirmGate.triggerResumeForParked` → `ResumeService.cancelStateOutcome`（`:1156-1173`）→ `ResilientChatService.cancelParked`（`:1253-1262`）→ `cancelTerminal` → `contextTelemetry`（`:2288` `ContextCompactor.recordTurn`，`est=true`）+ `done{cancelled}` ⇒ 既有"零终帧、无 recordTurn"确实被堵。
- **冷启动跨实例失败（MD-09）**：`cluster3-probe.mjs:2242-2350` 新增"三实例必须看到同一行库行 provider"的有界断言 + 5 条自检反例（含"只有 env 合成行"这一实测形态），不再被 `available===true` 掩盖 ⇒ 与 `AiConfigSeeder` 种子内 `bumpInTransaction` + 提交后 `publishAfterCommit` 同源。

---

## 4. 逐域核对结论（要点，不复述已登记项）

| 面 | 结论 | 关键落点 |
|---|---|---|
| 配置/密钥边界 | 成立 | 种子/人工写路径共用 `AiConfigVersionPublisher`（同事务 +1、提交后 INCR/reload/evict），no-op 与并发冲突不 bump；`AiConfigWriter` 在"配对产生处"拦 `origin` 变更保留旧 key（`clearKey` 不误伤）；`reloadWithOutcome` 区分"刷新成功仍为空"与"没刷成功" |
| 跨实例审计恰好一次 | 成立（降级口径如实） | `SettlementObservationStore` 单键脚本 `HSETNX+EXPIRE`、ARGV 全字符串、TTL=session-ttl、purge 由销毁面逐 run DEL、迁移排除清单已加；Redis 失败 fail-open 且计 `o1.observationClaim`（台账已登记为有界双记，不是恰好一次承诺） |
| SessionGate/Lease/fence | 成立 | 续期返回 0 与异常同口径置 `degraded` + `lockLost` 计数；诊断 GET 失败 ⇒ `Busy(null,true,true)`，`releaseDeadHeld` 拒绝猜着删锁，`ResumeService:777` 把 null 正确读成"被占用"；`lastProbeOk` 初值 false 堵住"失败后 1s 窗口被读成从未持租"；`permittedToWrite` 把 RT-21 第三态与答案面（DB 历史/记忆）纳入同一写门 |
| 记忆键与 purge | 成立 | `legacyEligible` 排除 `{`、`staging:{`、`__ids__`；staging 进销毁面（glob 转义 + 逐字前缀过滤）；删后复核（数据键 + staging）不足即 `complete=false`；`saveAll` 的 finally 复检并撤销（含 RENAME 已生效回包丢失） |
| complete 后 60s memory-only verify | 成立 | `scheduleVerify` 保留台账、`verifyAtMs` 走 `nextAtMs`；runner 只做"守卫 → 记忆面 delete+复核 → 移除台账"，不重跑 DB/附件（见 M-7 的边界） |
| Resume 取消/park/UNKNOWN | 成立 | 取消门在入口与每次工具派发前各一次；`STATE_UNKNOWN` 不判死、只收订阅，503；`PROVIDER_GONE` 仅"刷新成功 + 行级 ABSENT"才落 FAILED + `AI_PROVIDER_GONE` 终帧（只读/丢租时不收敛，交给新主人） |
| saveSuspended/requestMarker/session:current/SSE 帧 | 成立（成本见 M-3/M-4） | `saveSuspended` 无写权限即抛（异常路径有 start/end 收口）；`markRequestEmitted` 两处强读降级为 lenient 不再逐卡悬死；`clearSessionCurrent` 只清理指向自己的索引并回指同会话最新活跃轮 |
| 前端多卡/409/恢复 | 成立 | `hasOtherPendingCards` 同时计段与未建段队列（已决条目出队）；`reconcileRestoredQueueWithAuthority` 用同一份权威分流（APPROVED→只读 running，真终态→不重建）；`armGateSync` 3/6/12s 有界、用尽解锁；`frontend_tool_result` 409 后补下一张并按真实待决重算 |
| 隐藏步骤/快照新鲜 | 成立 | `isElementVisibleForAi` 覆盖 `hidden/inert/aria-hidden/display/visibility`+`checkVisibility`，快照与写动词双闸（`UI_TARGET_HIDDEN`）；`domVersion` 同时进 dom 缓存与 frontend 去重作用域（两层都失效，无写动作仍去重） |
| 文件/作业代次 | 成立 | 导入：清单先于 `selectedDefs`、`editorState` 带代次与来源、写回三闸（清单未定/换任务/来源不匹配），作业回执区分"已建但本页未跟踪"与"未启动"；导出：`loadedGrids` 只在载入成功后置位、`persistStepData` 失败不建作业、`startExport/saveEdits/syncStep` 全部入口捕获 `(generation, taskId)` |
| 数值保真 | 成立 | `numericCellValue` 以"解析后回序列化逐字相同"为判据，`007`/`1.50`/`>2^53` 保文本；三处出口同源 |

**测试真实性抽查**：`ai-region-batch-b.spec.ts` 的改动是 FE-32 后的**加强**（隐藏步骤不得披露 + `expect(refs…).not.toHaveLength(0)` 先证明量到 COND 快照），不是放宽；`verify-e2e.ps1` 的 T-13/C-11 断言原文未动。

---

## 5. 本次实际执行的验证（仅 2 个小用例 + 1 条 JDK 事实）

| 命令 | 结果 |
|---|---|
| `mvn -o -B -s maven-settings.xml -Dtest=SessionGateBusyUnknownTest test`（`backend/`） | **BUILD SUCCESS**，Tests run: 3, Failures: 0；日志含"会话门被占用但持有者不可知 … 按 unknown 拒绝""会话门强释放缺少 runId … 不删任何锁" |
| `vitest run tests/ai-multi-card-mixed-resume.spec.ts`（`frontend/`） | **14/14 passed** |
| 独立 JDK 片段：`new ConcurrentHashMap<>().get(null)` / `.remove(null)` | 均抛 `java.lang.NullPointerException`（I-1 的机制证据） |

未跑全量、未起服务、未读写密钥、未改动工作区任何文件（除本报告）。

---

## 6. 结论与建议顺序

1. 先落 I-1 的 3 行守卫（并补一个"`Busy(null,true,true)` ⇒ 409 带 `degraded=true`、不 NPE"的聚焦用例；注意别再用 mock 的 `RunRegistry` 掩盖 `get(null)`）。
2. M-1 顺手纠正注释（避免下一轮复审误判覆盖边界）；M-2 若要收口"start⇒end"不变量，最小做法是在 `ConfirmGate` 的拒绝分支复用 `settleParkedToolCall`（同一 `ai:observed` 仲裁，不会双发）。
3. M-3/M-4 属轮末/每帧成本，可留待性能批（不影响正确性）。
4. 其余（M-5..M-7）为回执/窄竞态/有界残留，建议登记而不在本批扩改。

**除 I-1 外，本轮未再发现经代码或测试证实的 Critical/Important；T-13 与 C-11 的修复与真实 CI 断言一一对应，未见以放宽断言或 mock 掩盖真实序列化/脚本类型的方式换取绿色。**
