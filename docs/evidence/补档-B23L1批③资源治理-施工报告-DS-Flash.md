# 补档 · B23L1 批③ 资源治理（资源互斥租约 / 业务幂等键 / F2 ToolTargetResolver / UNKNOWN 首跳）施工报告

- 日期：2026-10-21
- 范围：`docs/research/设计-B23L1多实例与资源治理专项-2026-10-21.md` §2.3（L1 主件）+ §2.4（UNKNOWN 首跳）+ §7 批③ + §5 验收中批③相关项。§2.1（设施）=批①（已入库 `de7095a`）、§2.2（fencing）=批②（已入库 `cd18b37`），**本批不做**。
- 施工链：批③a 设施（**741** 绿）→ 批③b 接线（自报 776，实为当时真值）→ 复审收口（**778**）。
- 本件为**补档**：由提交官按**代码最终态 + 工作树实测**对齐转录，条目内凡与施工自报不一致处，以代码与复跑实测为准并写明。施工 / 复审 / 复核 / 实机核验四链的原始报告结论见同批另四件（`-复审报告-` / `-复核确认-` / `-实机核验-` / `-提交报告-`）。
- 提交官自证行（见 `-提交报告-DS-Flash.md` §0）：`agentId=agent-1336`、`modelAlias=DeepSeek/deepseek-flash`、`profileName=coder`、`thinkingEffort=high`。

## 0. 测试矩阵（先行登记；每条均落为用例）

### 0.1 资源互斥（L1 · `ToolResourceLocks` / `LeaseKeys` / `LeaseResourceTtl`）

| # | 面 | 断言 | 落点（用例） | 状态 |
| --- | --- | --- | --- | --- |
| R1 | 互斥本体 | 同 task 并发两个 `start_export`：**恰一个执行**、恰一个 `RESOURCE_LOCKED` | `ToolResourceLockGateTest#concurrentCallsOnSameTaskExecuteExactlyOnce` | ✅ |
| R2 | 阻断形态 | 被挡下的调用 `status=BLOCKED / outcome=blocked / executed=false / retryable=true`，帧序不变、**不中止同批** | `#blockedCallKeepsFrameOrderAndDoesNotHaltBatch` | ✅ |
| R3 | 不占认领 | 被挡下的调用**不占 `ai:claim:`**；锁释放后同一 `toolCallId` 可正常执行 | `#blockedCallDoesNotConsumeClaimAndCanRunAfterLockRelease` | ✅ |
| R4 | 双粒度 | 只有**另一作业类型**的同 task 调用不互斥（导出不挡发布） | `#anotherJobTypeDoesNotBlockTheSameTask` | ✅ |
| R5 | 双粒度（M-3） | 只占**作业级**锁 ⇒ 阻断，且**先拿到的任务级锁必须就地归还**（不白占一个 TTL） | `#jobLockOnlyBlocksTheCallAndReturnsTheTaskLockItJustTook` | ✅ |
| R6 | TTL | 锁按 `resource-ttl`（60s）自动放开，过期后**新持有者可接管** | `LeaseResourceTtlTest#theLockBlocksUntilTheTtlExpiresAndThenANewOwnerTakesOver` | ✅ |
| R7 | TTL 口径 | 过期 owner 的**进程内视图仍输给 Redis 真值**（不做"我还持有"的自我安慰） | `#anExpiredOwnerViewStillLosesAgainstRedisTruth` | ✅ |
| R8 | 释放权 | **谁创建谁释放**：重入命中（`created=false`）**没有释放权** | `#reentrantAcquireHasNoReleaseRight` | ✅ |
| R9 | 释放权 | 错 owner 不能释放别人的那一任 | `#expiredLockCanBeTakenAndWrongOwnerCannotRelease` | ✅ |
| R10 | 释放时机 | 工具抛异常时锁仍在 `finally` 归还（不留 TTL 尾巴） | `#locksAreReleasedInFinallyEvenWhenToolThrows` | ✅ |
| R11 | 降级（fail-open） | 租约设施不可用 ⇒ **放行执行** + WARN（不把"不知道"报成"有人持有"） | `#unavailableLeaseFacilityDegradesToExecutionWithWarn` | ✅ |
| R12 | 开关 | `app.ai.lock.enabled=false` ⇒ 整段跳过、**零 Redis 往返** | `#disabledLockSkipsWholeSectionWithoutRedisRoundTrip` | ✅ |
| R13 | 键形 | `ai:lock:task:<id>` / `ai:lock:job:<id>:<JOBTYPE>` 形状固定、JOBTYPE **大写归一**、前缀不与其他键族碰撞 | `LeaseKeysTest`（5 条） | ✅ |
| R14 | 非法入参 | `taskId<=0` / 空 jobType **拒绝构造**，不产出"所有调用方都能命中"的野键 | `LeaseKeysTest#invalidInputsAreRejectedInsteadOfProducingAWildcardKey` | ✅ |
| R15 | 配置面 | `resource-ttl` 默认 60s、`lock.enabled` 默认 true，且 TTL 真的作用到锁键 | `LeaseResourceTtlTest`（前两条） | ✅ |
| R16 | owner 维度 | 同一键上**不同 owner 是不同租约**（后者 `HELD_ELSEWHERE`），同 owner 才是重入 | `LeaseServiceDistinctOwnerTest`（9 条） | ✅ |
| R17 | 兼容红线 | owner 缺省的旧方法**全部等价于 `instanceId`**，批② 语义与用例零变化 | `LeaseServiceDistinctOwnerTest#defaultOwnerIsTheInstanceIdSoLegacyCallsAreUnchanged` | ✅ |
| R18 | 运维面 | `describe().keys` 增 `lockTask` / `lockJob`，由**上下文**推出（step 缺省回落 taskType） | `ResumeDescribeLockKeysTest`（3 条） | ✅ |

### 0.2 幂等键与认领三态（`ai:idem:` / `IDEM_PUSH` / `tryClaimExecution`）

| # | 面 | 断言 | 落点（用例） | 状态 |
| --- | --- | --- | --- | --- |
| I1 | 索引命中 | 台账命中 ⇒ **原结果重放**、零副作用（不取锁、不认领、不执行） | `ToolIdempotencyTest#ledgerHitReplaysOriginalResultWithoutExecuting` | ✅ |
| I2 | 结局口径（U4） | 台账 `outcome=unknown` ⇒ **保持 unknown**（旧实现当成功重放） | `#ledgerUnknownKeepsUnknownInsteadOfReportingSuccess` | ✅ |
| I3 | 二次对账 | 认领失败后再查一次台账（对方可能刚写完）⇒ 命中则重放 | `#lostClaimSecondLedgerCheckReplaysOriginalResult` | ✅ |
| I4 | 认领失败文案 | 无台账 ⇒ 如实报"已被实例 X 认领" | `#lostClaimWithoutLedgerReportsAlreadyClaimed` | ✅ |
| I5 | 三态（STALE_FENCE） | fence 被推进 ⇒ **独立结论**"写回权已转移"（旧实现误写"实例 null 认领"） | `#staleFenceHasIndependentConclusionAndText` | ✅ |
| I6 | 原子步 | 台账 `RPUSH` 与索引 `HSETNX` **同一条 Lua**（脚本内 `LLEN` 先于 `RPUSH`） | `RunStoreIdempotencyIndexTest#ledgerAndIndexAreWrittenInOneScript` | ✅ |
| I7 | 下标正确性 | M-2：下标在**推入前**取；`toolCallId` 前缀相同的两条记录不串号 | `#indexPointsAtTheRightRecordWhenIdsShareAPrefix` | ✅ |
| I8 | O(1) | 索引命中**不回落全表扫描**（`LRANGE` 零调用） | `#indexHitsWithoutScanningTheLedger` | ✅ |
| I9 | 索引错位（M-1） | 索引指向**别人的**记录 ⇒ 回落扫描，绝不把别人的结果交出去 | `#anIndexPointingAtAnotherCallsRecordFallsBackToTheScan` | ✅ |
| I10 | 索引坏值 | 索引坏/取不回/旧数据 ⇒ 回落扫描（"台账才是判据"） | `#aBrokenIndexFallsBackToTheScanInsteadOfReportingNotExecuted` / `#recordsWithoutAnIndexFallBackToTheScan` | ✅ |
| I11 | 寿命 | 索引与台账**同寿**（同 TTL 同过期）；`deleteRun` 清索引、**不清资源锁** | `#indexAndLedgerShareTheSessionTtlAndExpireTogether` / `#deleteRunClearsTheIdemIndexWithTheLedger` / `#deleteRunLeavesResourceLocksAlone` | ✅ |
| I12 | 兼容 | 记录增 `idemKey` 字段（additive），旧记录照读 | `#recordCarriesAnAdditiveIdemKeyFieldAndLegacyRecordsStayReadable` | ✅ |
| I13 | 三态本体 | `tryClaimExecution` 区分 `WON` / `LOST` / `STALE_FENCE`，布尔旧口保留（两种失败皆 false） | `RunStoreClaimFenceTest#claimOutcomeDistinguishesWonLostAndStaleFence`（本批新增） | ✅ |

### 0.3 F2 目标资源硬校验（`ToolTargetResolver`）

| # | 面 | 断言 | 落点 | 状态 |
| --- | --- | --- | --- | --- |
| T1 | 存在性 | 无 `taskId` / taskId 不存在 / 非法形状 ⇒ `blocked/TARGET_INVALID` | `ToolTargetResolverTest#missingTaskIdIsInvalid` / `#unknownTaskIsInvalid` / `#illegalTaskIdShapesAreInvalid` | ✅ |
| T2 | 类型匹配 | 任务类型与作业类型不符（导出任务发导入 / 非 EXPORT 任务发导出）⇒ 非法 | `#taskTypeMismatchIsInvalid` / `#exportJobNeedsExportTask` | ✅ |
| T3 | 上下文一致 | args 的 taskId 与**当前上下文**的 taskId 不同 ⇒ 非法（防"拿着别人的 taskId 执行"） | `#taskIdDifferentFromContextIsInvalid` | ✅ |
| T4 | 覆盖范围 | 四个 `start_*` 全走校验；`create_task` 等非作业发起工具**不校验**（它创建目标） | `#validTargetPassesForAllFourJobTools` / `#nonJobToolsAreNotChecked` | ✅ |
| T5 | 归属 | `defCode` 必须属于该任务（**预留**：当前四工具 args 不带 defCode，判定为"不适用"不放行也不拒） | `#defCodeMustBelongToTheTask` | ✅ |
| T6 | 失败开放 | 仓储异常 ⇒ **放行** + WARN（availability ≠ invalidity） | `#repositoryFailureFailsOpen` | ✅ |
| T7 | 接线 | 非法目标 ⇒ `blocked`、**不占认领、不写台账**，同批后续调用照常 | `ToolTargetResolverGateTest#invalidTargetIsBlockedWithoutClaimOrLedgerAndBatchContinues` | ✅ |
| T8 | 接线 | 越 scope 的 taskId 同样被拦 | `#taskIdOutsideContextIsBlocked` | ✅ |
| T9 | 顺序 | **台账重放优先于目标校验**（已执行过的调用不因今天任务没了就翻案） | `#ledgerReplayWinsOverTargetValidation` | ✅ |
| T10 | 顺序 | `create_task` 无 taskId 不触发校验 | `#createTaskWithoutTaskIdIsNotValidated` | ✅ |

### 0.4 UNKNOWN 首跳对账（`ToolUnknownReconciler`）+ 信封码

| # | 面 | 断言 | 落点 | 状态 |
| --- | --- | --- | --- | --- |
| U1 | 台账优先 | 台账有**非 unknown** 结论 ⇒ 采用（原文 + 执行者一并取回） | `ToolUnknownReconcilerTest#ledgerHitWithConcreteOutcomeIsAdopted` | ✅ |
| U2 | 不猜成功 | 台账写着 `unknown` ⇒ **保持 unknown** | `#ledgerHitWithUnknownOutcomeStaysUnknown` | ✅ |
| U3 | 第二跳 | 无台账但有认领 ⇒ 保持 UNKNOWN + "已在执行中" | `#pendingClaimKeepsUnknownWithInFlightText` | ✅ |
| U4 | 第三跳 | 台账与认领都没有、但有资源锁 ⇒ 保持 UNKNOWN + 锁文案 | `#heldResourceLockKeepsUnknownWithLockText` | ✅ |
| U5 | 兜底 | 三条痕迹都没有 ⇒ 保持 UNKNOWN + "需人工对账" | `#noTracesKeepsUnknownWithManualReconciliationText` | ✅ |
| U6 | 不参与通道 | FRONTEND 条目**不对账**（返回 null，调用方保持原样） | `#frontendChannelIsNotReconciled` | ✅ |
| U7 | 写回 | 续跑结算路径把 adopted 结论写回条目；无可采用则只更文案 | `#resolvePendingAdoptsLedgerOutcomeForUnknownEntry` / `#resolvePendingKeepsUnknownWhenNothingToAdopt` | ✅ |
| U8 | 码值 | `RESOURCE_LOCKED` / `TARGET_INVALID` 的 wire 值与"仅前者可重试" | `ToolOutcomeResourceCodesTest`（6 条） | ✅ |
| U9 | 信封 | 同一 `blocked` 下新码可重试、旧码不可重试（扩写不是放宽） | `ToolResultEnvelopeTest`（改，+2 断言） | ✅ |

### 0.5 前端（一处生产 + 一处 spec）

| # | 面 | 断言 | 落点 | 状态 |
| --- | --- | --- | --- | --- |
| F1 | 卡面文案 | `RESOURCE_LOCKED` / `TARGET_INVALID` 各给独立 reason，`status` 仍 `blocked`（不被降级） | `frontend/tests/tool-outcome-envelope.spec.ts`（新增 1 例） | ✅ |
| F2 | 只增不改 | 既有两支（`TOOL_ALREADY_CLAIMED` / 默认"未披露"）原样通过 | 同上（该 spec 既有 3 例） | ✅ |

### 0.6 回归面（不改语义的既有面）

| 面 | 断言 | 落点 | 状态 |
| --- | --- | --- | --- |
| conformance | 三参 `RunStore` 装配路径语义不变 | `ai/conformance` 全族 | ✅ |
| 帧契约 | 帧类型集零新增；`tool_result` 只多 `code` 取值，字段集不变 | `FrameContract*` / `ToolFrameSequenceConformanceTest` | ✅ |
| 批② fencing | 无租约写回（红线 E3）、认领 fence、WAL 收敛全绿 | `RunStoreFencedWriteTest` / `RunStoreClaimFenceTest` / `RunStoreWalConvergeTest` | ✅ |
| 前端既有 | 512 例原样通过（本批只加 1 例） | `frontend` 全量 | ✅ |

## 1. 改动清单（带行号，行号按最终态）

### 1.1 新增 · 生产代码（4 个类）

| 文件 | 关键行 | 作用 |
| --- | --- | --- |
| `ai/run/LeaseKeys.java`（新，72 行） | `LOCK_PREFIX`:27、`TASK_PREFIX`:30、`JOB_PREFIX`:33、`task(long)`:44、`job(long,String)`:53、`normalizeJobType`:64 | 资源锁键名**唯一入口**：`ai:lock:task:<taskId>` / `ai:lock:job:<taskId>:<JOBTYPE>`（JOBTYPE 大写归一），非法 taskId 直接拒绝构造 |
| `ai/gate/ToolResourceLocks.java`（新，223 行） | `LockOutcome`:55、`LockHandle`:78、`close`:92、`supports`:116、`acquire`:127、`isHeld`:178 | `LeaseService` 之上的薄包装：两把锁**固定顺序**（先任务级后作业级）、抢不到即放弃（不等待不轮询 ⇒ 结构上无活锁）、三态 + `DISABLED`、只释放 `created` 那一任、**不续租** |
| `ai/gate/ToolTargetResolver.java`（新，224 行） | `supports`:65、`jobTypeOf`:70、`taskIdOf`:86、`check`:101、`Verdict`:212 | F2 执行前硬校验：存在性 / 类型匹配 / 与上下文一致 / defCode 归属（预留）；仓储异常 fail-open |
| `ai/gate/ToolUnknownReconciler.java`（新，152 行） | `reconcile`:66、`Reconciliation`:142 | UNKNOWN 首跳对账：台账 → 认领 → 资源锁三跳；**只有"台账里有非 unknown 结论"才收敛** |

### 1.2 修改 · 生产代码（6 个类）

| 文件 | 行号 | 改动 |
| --- | --- | --- |
| `ai/config/AiProperties.java` | `lock` 字段:56、`resourceTtl`:364、`Lock` 内部类:376 | 新增 `app.ai.lease.resource-ttl`（默认 **60s**，**不续租**、仅临界区上界）与 `app.ai.lock.enabled`（默认 **true**） |
| `ai/run/LeaseService.java`（+178/−…） | `tryAcquire(key,ttl,owner)`:…、`acquire(key,ttl,owner)`:…、`verifyHeld(key,epoch,owner)`、`releaseIfHeld(key,owner)`、`heldEpoch(key,owner)`、`heldLease(key,owner)`、`stopDriving(key,owner)`、`viewKey`/`owner` 助手 | **owner 维度**：进程内视图与续租表按 `key + "\0" + owner` 记账 —— 同一 Redis 键上**不同 owner 是不同租约**，同 owner 才是重入；owner 缺省的旧方法等价于 `instanceId`（批② 语义零变化） |
| `ai/run/RunStore.java`（+290/−…） | `IDEM_PREFIX`:124、`IDEM_PUSH_SCRIPT`:224、`ClaimOutcome`:241、`ClaimResult`:261、`idemKey`:305、`idemField`:315、`tryClaimExecution`:979、`appendLedger`:1068、`idemFor`:1160、`matchesToolCall`:1195 | ①幂等索引 `ai:idem:<runId>`：`field=<runId>:<toolCallId>`、`value=<listIndex>|<outcome>|<code>|<executedBy>|<atMs>`，**不复制正文**；②台账与索引**同一条 Lua**（`LLEN` 先于 `RPUSH`、`HSETNX` 首写者胜、两键同寿）；③认领**三态化**（`WON`/`LOST`/`STALE_FENCE`），布尔旧口保留；④`deleteRun` 清索引、**不清资源锁** |
| `ai/gate/SpToolCallingManager.java`（+259/−…） | `executeOnce(runId,prompt,pending,context)`:643、`claimAndExecute`:684、`ledgerOf`、`replayLedger`:790、`settleBlocked`:817、`acquireResourceLocks`:833、`contextOf`:846、`RESOURCE_LOCKED_TEXT`:614、`targetInvalidText`:618、`applyReconciliation`:946 | 执行链改为 **对账/重放 → 目标校验 → 资源锁 → 认领（三态）→ 执行 → 台账 → finally 归还**；三条阻断统一走 `settleBlocked`（落终态 + 一帧 + 回填，**不 appendLedger、不占认领、不抛异常、不中止同批**）；续跑结算前对 UNKNOWN 做三跳对账 |
| `ai/run/ResumeService.java`（+65/−…） | `describeKeys`:231、`contextTaskId`、`contextJobType`、`resume(...)` 内 `verifyHeld(lease.owner())`:… | 运维面键表增 `lockTask` / `lockJob`（由上下文推出，页面轮为 null）；归属校验按**租约自己的 owner** 做（不再隐含 instanceId） |
| `ai/tool/ToolOutcome.java`（+39/−…） | `CODE_RESOURCE_LOCKED`、`CODE_TARGET_INVALID`、`retryable(code)` | 两个新码 + 重试表扩写：`BLOCKED` 下**只有 `RESOURCE_LOCKED` 可重试**（等锁 = 等对方收尾），`TARGET_INVALID` 不可重试（参数本身就是错的） |

### 1.3 新增 · 测试（11 个用例类，75 条用例）

| 文件 | 条数 | 覆盖 |
| --- | --- | --- |
| `ai/gate/ToolResourceLockGateTest.java` | 10 | R1–R12（互斥 / 阻断形态 / 不占认领 / 双粒度 / M-3 归还任务锁 / 重入无释放权 / finally / 降级 / 开关） |
| `ai/gate/ToolIdempotencyTest.java` | 5 | I1–I5 |
| `ai/gate/ToolTargetResolverTest.java` | 10 | T1–T6 |
| `ai/gate/ToolTargetResolverGateTest.java` | 4 | T7–T10 |
| `ai/gate/ToolUnknownReconcilerTest.java` | 8 | U1–U7 |
| `ai/run/LeaseKeysTest.java` | 5 | R13–R14 |
| `ai/run/LeaseResourceTtlTest.java` | 4 | R6–R7、R15 |
| `ai/run/LeaseServiceDistinctOwnerTest.java` | 9 | R16–R17（owner 维度九面） |
| `ai/run/ResumeDescribeLockKeysTest.java` | 3 | R18 |
| `ai/run/RunStoreIdempotencyIndexTest.java` | 11 | I6–I12 |
| `ai/tool/ToolOutcomeResourceCodesTest.java` | 6 | U8 |

### 1.4 修改 · 测试（4 个文件）

| 文件 | 改动 |
| --- | --- |
| `ai/conformance/InMemoryRunStore.java`（+92/−…） | 实现新增面（`idem` / 三态认领 / 资源锁可注入）的**同一语义内存版**；`acquireRunLease` 的 `created` 与真实实现同口径 |
| `ai/run/FakeLeaseRedis.java`（+231/−12） | 新增 `IDEM_PUSH` 脚本语义（`LLEN`+`RPUSH`+`HSETNX`）、台账 LIST 面、**可控时钟** `advanceTime` + TTL 到期清键、脚本原文/KEYS 留证（`lastScriptBody` / `lastScriptKeys`）、`scriptPushes` 与 `templatePushes` 分开计数 |
| `ai/run/RunStoreClaimFenceTest.java`（+37） | 新增 1 例 `claimOutcomeDistinguishesWonLostAndStaleFence`（I13：三态 + 布尔旧口） |
| `ai/tool/ToolResultEnvelopeTest.java`（+3） | 新增 2 条断言（U9：新码可重试 / 目标不成立不可重试） |

### 1.5 脚本与文档

| 文件 | 改动 |
| --- | --- |
| `scripts/b23l1-dual-instance.ps1`（+537/−…） | ①`resource-lock` / `idempotency` 从 SKIP 占位转**真用例**；②新增 H3 预置锁变体（`SET <lock> 1|other-instance PX 60000` → 期望 `RESOURCE_LOCKED` → `DEL` 后重跑 `ok`）；③`-SharedDb` 开关（两实例共用一个 H2 文件、**绝对路径** + `AUTO_SERVER=TRUE`）+ 与 `instance-kill`/`lease-scripts` 的**同棒守卫**（杀 A = 杀 H2 服务端）；④跨实例竞态变体**最多 3 轮**、任一命中即 PASS、3 轮未命中判 **SKIP**；⑤`Invoke-RedisWrite` 只允许 `SET … PX` / `DEL`（作用域固定 harness 的 Redis）；⑥终态回放窄窗短延迟重读（最多 4 次，不掩盖真 done 缺失）；⑦`instance-kill` 断言**两形态皆可**（甲=共享库 `executedBy=B`；乙=H2 隔离 `TARGET_INVALID` 前置拦截） |
| `scripts/README.md`（+21/−…） | §3 用例面扩为 9 条（批③两条转真 + 变体口径）、`-SharedDb` 边界与守卫、L-12 形态甲/乙、桩指纹约束、只读/可写 RESP 指令面 |
| `frontend/src/utils/parts.ts`（+8/−2） | `blocked` 的 reason 由**单码分支**扩为三码分支（`TOOL_ALREADY_CLAIMED` / `RESOURCE_LOCKED` / `TARGET_INVALID` / 默认"未披露"）——**只增不改** |
| `frontend/tests/tool-outcome-envelope.spec.ts`（+42） | F1/F2（新增 1 例，覆盖两码 + 既有两支原样） |

## 2. 设计条款逐项落实（§2.3 / §2.4 / §7 批③）

| 设计条款 | 落实 | 位置 |
| --- | --- | --- |
| 资源租约键 `ai:lock:task:<taskId>` / `ai:lock:job:<taskId>:<jobType>`，**临界区持有口径** `acquire → createAndStart → finally release`，TTL 60s 仅上界、**不续租**（§2.3 口径订正） | 键名收口 `LeaseKeys`；`ToolResourceLocks.acquire` → `SpToolCallingManager` 的 `try { claimAndExecute } finally { locks.close() }`；TTL = `app.ai.lease.resource-ttl` 60s，**代码内无 markDriving** | `LeaseKeys:44/53`、`ToolResourceLocks:127/92`、`SpToolCallingManager:643`、`AiProperties:364` |
| 跨实例「作业在途」互斥由共享库 `inFlightConflict` 承接 | 未改动作业在途判据（F5 预检仍用它）；资源锁**只承担临界区互斥**，语义不重叠 | `ToolPreflightService`（零改动） |
| 冲突 = 工具结果 `blocked` 带码 `RESOURCE_LOCKED`，**不抛异常不断流** | `settleBlocked(code=RESOURCE_LOCKED)`：条目落 BLOCKED、发一帧 `ok=false`、回填模型、返回 | `SpToolCallingManager:662/817` |
| F2 `ToolTargetResolver`：执行前校验 taskId 存在 / 属当前上下文 / defCode 属该任务；失败 `blocked` + `TARGET_INVALID`；与 F5 预检两层并存 | 四个 `start_*` 全接；`create_task` 不接（它创建目标）；defCode 归属**预留**（当前 args 不带） | `ToolTargetResolver:101`、`SpToolCallingManager:650-657` |
| 业务幂等键 `runId:toolCallId`；重放命中已执行记录**直接返回原结果** | `ai:idem:<runId>` 索引（O(1)）+ 台账回落；`replayLedger` 三面同源、零副作用；命中 `unknown` **保持 unknown** | `RunStore:124/1068/1160`、`SpToolCallingManager:790` |
| §2.4 UNKNOWN 首跳：先查 ledger 幂等键 → 再查资源租约 → 最后才允许"重试语义"上报；完整对账器留 L3 | 三跳落在 `ToolUnknownReconciler`；**任何一跳都不把结局改成 OK**，只有"台账里有非 unknown 结论"才收敛 | `ToolUnknownReconciler:66`、`SpToolCallingManager:946` |
| §7 批③ = 资源租约 + resolver + 幂等键 + UNKNOWN 首跳 | 四项全落（见 §1） | — |
| §5 验收：双实例 harness 用例面 | `resource-lock`（H3 必跑 + `-SharedDb` 竞态变体）/ `idempotency` 交付并**实跑通过** | `scripts/b23l1-dual-instance.ps1`、同批 `-实机核验-` |

## 3. 红测取证

> 口径：本批用例与实现同批落地，**没有完整"先写用例再写实现"的过程证据**。证据分两类：**(a) 定点变异探针**（批③a，八条 A–H，变异只存在临时工作副本、未入库）；**(b) 行为纠正回退实证**（批③b，把"新行为"回退成"旧行为"，看用例是否红）。

### 3.1 批③a 变异探针 A–H（设施层）

| 探针 | 变异（临时） | 期望红态 | 结论 |
| --- | --- | --- | --- |
| A | `LeaseKeys.job` 不做大写归一（`import` 与 `IMPORT` 各成一把锁） | 键形用例红：两条路径互不可见 | 红 → 复原绿 |
| B | `LeaseKeys.task` 允许 `taskId<=0`（拼出"空任务"野键） | 非法入参用例红 | 红 → 复原绿 |
| C | `ToolResourceLocks` 抢第二把锁失败时**不归还**已拿到的任务级锁 | `jobLockOnlyBlocksTheCallAndReturnsTheTaskLockItJustTook` 红 | 红 → 复原绿 |
| D | `LockHandle.close` 见 `held` 即释放（不看 `created`） | `reentrantAcquireHasNoReleaseRight` 红（重入者放掉创建者的锁） | 红 → 复原绿 |
| E | `IDEM_PUSH` 脚本改"先 `HSETNX` 再 `RPUSH`" | `ledgerAndIndexAreWrittenInOneScript` 红（索引指向不存在的记录） | 红 → 复原绿 |
| F | 索引下标改 Java 侧先读 `LLEN` 再推入 | `indexPointsAtTheRightRecordWhenIdsShareAPrefix` / M-2 红 | 红 → 复原绿 |
| G | `idemFor` 索引命中即返回（不校验记录身份） | `anIndexPointingAtAnotherCallsRecordFallsBackToTheScan` 红（把别人的结果交出去） | 红 → 复原绿 |
| H | `appendLedger` 只 `RPUSH` 不写索引 | `indexHitsWithoutScanningTheLedger` 红（回落全表扫描） | 红 → 复原绿 |

### 3.2 批③b 行为纠正回退实证（接线层）

| # | 回退构造 | 红态（原文） | 修法 |
| --- | --- | --- | --- |
| B-1 | `replayLedger` 退回旧实现：`outcome==null \|\| "unknown".equals(...)` 都当成功 | `ledgerUnknownKeepsUnknownInsteadOfReportingSuccess` 红：unknown 台账被写成 `ok`（前端"执行了但不知道结果"变"成功"） | 旧台账**缺键**才兼容成功；写着 `unknown` 一律保持 unknown |
| B-2 | 认定领失败后直接走"已认领"文案（不区分 fence） | `staleFenceHasIndependentConclusionAndText` 红：文案落成"由实例 null 认领" | 三态化，`STALE_FENCE` 独立结论"写回权已转移" |
| B-3 | 前端 `parts.ts` 不加两码分支（落默认"未披露"） | `tool-outcome-envelope.spec.ts` 新例红：`RESOURCE_LOCKED` 被说成"不在披露范围" | 三码分支（只增不改） |

### 3.3 收口 M-1 / M-3 红证据

| # | 构造 | 红态 | 收口 |
| --- | --- | --- | --- |
| M-1 | 索引命中即返回（不比对 `toolCallId`） | `anIndexPointingAtAnotherCallsRecordFallsBackToTheScan` 红：下标错位时把**别人的**台账正文当本调用结果返回（比"没查到"更危险） | `matchesToolCall` 比对 + 错位回落扫描（`RunStore:1177/1195`） |
| M-3 | 只占作业级锁、把已拿到的任务级锁留在外面 | `jobLockOnlyBlocksTheCallAndReturnsTheTaskLockItJustTook` 红：一次被拒的调用白占任务级锁一个 TTL | 分步记录已拿到的锁，失败即就地归还（`ToolResourceLocks:127-176`） |

## 4. 施工链（三段门禁，数字为后端用例数）

| 段 | 内容 | 门禁（后端） | 增 |
| --- | --- | --- | --- |
| 批③a 设施 | `LeaseKeys` + `resource-ttl`/`lock.enabled` + `LeaseService` owner 维度 + `ai:idem:` 索引与 `IDEM_PUSH` 脚本 + 认领三态 + `ToolResourceLocks` + `describe().keys`（**只建设施，不接门**） | **741** | +39（自批② 702） |
| 批③b 接线 | `SpToolCallingManager` 执行链改造（对账/重放 → F2 → 资源锁 → 认领三态 → 台账 → finally）+ `ToolTargetResolver` / `ToolUnknownReconciler` 接入 + `ToolOutcome` 两码 + 前端 `parts.ts` 两码分支 | **776**（自报，与当时真值一致） | +35 |
| 复审收口 | I-1 摘除资源锁续租、I-2 重试 + SKIP 口径、M-1 索引错位、M-2 下标时序、M-3 任务锁归还、M-4 认领首次即走三态口、N 系逐条 | **778** | +2 |

> 口径：741 为批③a 收尾实测（`_b3a-green4.log`：`Tests run: 740 → 741`）；776/778 为批③b 与收口段实测；最终门禁以**提交官复跑** 778 为准（§5）。

## 5. 门禁实测（提交官复跑，2026-10-21 11:2x–11:5x）

| 门禁 | 命令 | 实测 |
| --- | --- | --- |
| 后端单测 | `<MAVEN_HOME>/bin/mvn -o test`（Maven 3.9.16 离线，非 `-q`） | **exit 0 / BUILD SUCCESS**；mvn 汇总 `Tests run: 778, Failures: 0, Errors: 0, Skipped: 0`；surefire `TEST-*.xml` 的 `tests` 属性求和 = **778**（**123 个 suite**）——**两口径一致** |
| 前端单测 | `cd frontend && node node_modules/vitest/vitest.mjs run` | **513/513 · 26 文件**（`Test Files 26 passed` / `Tests 513 passed`），**首跑即绿**（`ai-panel-parts-render` 族 12 例既有 flaky 本跑未触发） |
| 前端类型检查 | `node node_modules/vue-tsc/bin/vue-tsc.js --noEmit` | **exit 0** |
| 前端构建 | `node scripts/build.mjs`（`package.json` 的 `build` = `vue-tsc --noEmit && node scripts/build.mjs`） | **✓ built in 1m 24s**（exit 0） |
| 文档门禁 | `node scripts/ci/check-docs.mjs` | **6/6 绿**（exit 0；命名纪律扫 1369 路径、实扫 1366） |
| 脱敏扫描 | 仓内 `scripts/githooks/scan-lib.sh` 三规则（工作树已跟踪 diff + 未跟踪新件全文） | 纳入本批的路径 **0 命中**；唯一命中在**在飞调研件** `docs/research/调研-AI操作在线表格能力业界惯例-2026-10-21.md`（本批**不提交**） |
| harness | `scripts/b23l1-dual-instance.ps1` | 由实机核验方七跑 + SharedDb 三跑完成（见 `-实机核验-`）；**提交官不重跑**（零服务启停纪律） |

> **门禁实测的唯一异常**：全量首跑出现 1 例红（`ResumeServiceLeaseTest#concurrentSameRunResumesDoNotLetTheLoserReleaseTheWinnersLease`，期望"败方不删胜方租约"实得删掉）。经**批② HEAD（`d8056d5`）独立导出副本复跑**，同一用例同样红 ⇒ **既有问题、非本批引入**。根因与未修理由见 §7.2。

## 6. 施工自决（七条）与偏差

### 6.1 自决项（七条，均有代码锚）

| # | 自决 | 理由 | 落点 |
| --- | --- | --- | --- |
| S-1 | 资源锁 owner 取 **`<instanceId>:<runId>:<toolCallId>`**，而不是裸 `instanceId` | 同一实例内两个不同业务持有者必须能区分（不同 owner = 不同租约）；同时同一次调用的重入仍是重入（`created=false`，无释放权） | `SpToolCallingManager:833-844`、`LeaseService#viewKey` |
| S-2 | 两把锁**固定顺序**（先任务级、后作业级）+ **抢不到即放弃**（不等待/不轮询） | 顺序不一致 + 等待才会活锁；"拿不到就报 blocked"把并发冲突变成一次可重试的业务结论，而不是线程互相阻塞 | `ToolResourceLocks:127-176`（类注） |
| S-3 | 抢第二把失败时**就地归还**第一把 | 否则一次被拒的调用白占任务级锁一个 TTL（60s），把一次冲突放大成一分钟的业务停摆（M-3 收口） | `ToolResourceLocks:150-170` |
| S-4 | 三条阻断（锁 / 目标 / 写回权）**统一走 `settleBlocked`** | 三处若各写一遍，落库字段、帧的 `ok`、文案、日志迟早分叉；统一出口也保证"不 appendLedger、不占认领、不中止同批" | `SpToolCallingManager:817-831` |
| S-5 | 认领**首次即走三态口** `tryClaimExecution`，布尔口仅在轻装配面回落 | 先布尔口、失败后再补一次三态口会把 `HSETNX` 与 fence 的 `GET` 各做两遍（M-4 收口）；回落只为不改动 Mockito 底座既有用例 | `SpToolCallingManager:684-726`、`RunStore:979` |
| S-6 | `ledgerOf` **两跳**（`idemFor` → 未命中回落 `ledgerFor`） | 真实实现的 `idemFor` 内部已回落一次；第二跳只保护轻装配面，同时让"台账才是判据"只有一个出口 | `SpToolCallingManager:770-780` |
| S-7 | 资源锁**不续租**（复审 I-1 落定）：TTL 只是临界区上界 | 续租会让"驱动线程已死/动作卡住"变成事实永锁（没有旁观者能发现）；宁可按 TTL 放开让别的实例接手，崩溃后的白等窗因此恒 ≤ 60s | `ToolResourceLocks` 类注、`AiProperties:340-364` |

### 6.2 偏差（设计与实现的显式落差）

| # | 偏差 | 理由 | 影响 |
| --- | --- | --- | --- |
| D-1 | F2 的 **defCode 归属校验预留**（当前四工具 args 不带 defCode，判定为"不适用"） | 四个 `start_*` 只吃 `taskId`；defCode 属"定义级"动作（`select_definitions` 是前端工具、不走本链）。硬判会凭空拦截合法调用 | 预留位保留，注释写明触发条件是"args 带 defCode" |
| D-2 | 资源锁覆盖 **J/P 四工具**（`start_export` / `start_precheck` / `start_import` / `start_publish`），不含 `create_task` | `create_task` 没有 taskId（它**创建**目标），锁什么无从谈起；W 类的并发冲突由任务标题唯一性与 DB 约束兜底 | 已写入 `ToolResourceLocks.supports` 注释 |
| D-3 | `ai:lock:*` **不随 run 回收**（`deleteRun` 不清） | 锁按 **taskId** 记、寿命由 TTL 决定；在轮次回收里删锁会把**别人**的业务互斥一起放掉 | `RunStore#deleteRun` 注 + `#deleteRunLeavesResourceLocksAlone` 锁住 |
| D-4 | harness 跨实例资源锁变体"**3 轮未命中判 SKIP**" | 跨实例临界区是毫秒级 `createAndStart`，两轮可能不重叠（都执行是完全正确的行为）；判 FAIL 会把"行为正确"报成缺陷 | 跨实例证据以 **H3 确定性变体**为准（见 `-实机核验-`） |
| D-5 | `instance-kill` 断言**两形态皆可** | 默认 H2 隔离下 B 接管后没有该任务 ⇒ `TARGET_INVALID` 前置拦截（设计内，L-12）；共享库下才是"台账 1 条 `executedBy=B`" | 两形态都打印实际形态，避免"只认一种"的假绿 |

## 7. 挂账（本批不销记，逐条带依据）

### 7.1 本批复审 / 收口转出的挂账（看板 L-13 ~ L-18）

| # | 条目 | 依据 |
| --- | --- | --- |
| L-13 | `appendLedger` 在 `IDEM_PUSH` 脚本之后**多一次 `EXPIRE` 往返**（脚本内已 `PEXPIRE` 两键，模板面再续一次台账键） | 为保留批②"台账滑动 TTL"的可观测口径（脚本内 `PEXPIRE` 不体现在模板调用上）；多一次往返、低频路径（N-2） |
| L-14 | `LeaseService.stopDriving(key)` 按**裸键前缀全表扫描**（`key + "\0"` 前缀匹配 `driving.keySet()`） | owner 维度后续租任务按 `(key, owner)` 记账，裸键版要"停该键全部 owner"只能扫（N-3）；只在释放/收尾路径调用 |
| L-15 | `describe().keys` 的 `lockTask` / `lockJob` **可以是 null**（页面轮无 taskId） | 键面形状固定 vs 值可为 null 的取舍：固定键面便于前端按 key 取值，null 语义写进注释（N-5）；下游消费方需容 null |
| L-16 | `TARGET_INVALID` **只在审批后（执行前）校验**，审批前不校验；只有恢复路径的重放才"回头兜住" | 设计稿 F2 定位是"执行前硬校验"；审批前拦截属 F5 预检面（只读四项）。因此一张已弹卡的非法目标卡会等到批准后才被阻断（N-7） |
| L-17 | `-SharedDb` 跨实例变体的 **SKIP 与真故障同形**（都表现为"没拿到互斥证据"） | 竞态窗口未命中时行为可能完全正确；口径 = **跨实例锁证据以 H3 确定性变体为准**，不完整证据必须标注，不得当"已证"（I-2 / D-4） |
| L-18 | `idemFor` 的记录**缺 `toolCallId` 字段**的极端分支：`matchesToolCall` 返回 false ⇒ 回落扫描 ⇒ 仍查不到 ⇒ 判"未执行"，由 `ai:claim:` 的 `HSETNX` 兜住（不会真重复执行） | `matchesToolCall` 当前注释未点名这条兜底链（N-6）；行为安全，注释待补 |

### 7.2 提交官复跑发现的既有缺陷（本批**不修**，须另立项）

| # | 条目 | 证据 |
| --- | --- | --- |
| **L-19** | `ResumeServiceLeaseTest#concurrentSameRunResumesDoNotLetTheLoserReleaseTheWinnersLease` **时敏抖动（批② 起，非本批引入）**：`ResumeService.resume(runId,forceTakeover)` 是"**先抢租、后撞进程内互斥**"，因此 `created=true` 的那一方**可能输掉** `resuming` 互斥 → 走"非 ACCEPTED ⇒ 当场还租"分支 → 删掉**胜方正在用的同一 token** 租约（同 owner 同 epoch 同值）。"谁创建谁释放"只覆盖"败方是重入者"，**未覆盖"创建者输掉互斥"** | ①提交官全量首跑红 1/778；②本机对该类单独复跑 **2/5、3/10 红**（约 40–50%，与"互斥胜者与创建者独立"一致）；③给 `FakeLeaseRedis.handleScript` 加 `synchronized`（排除"替身非原子导致双 `created`"）后**仍 3/10 红** ⇒ 排除替身因素；④**批② HEAD `d8056d5` 独立导出副本**复跑同一类：首跑即红、第 5 轮再红 ⇒ **非批③ 引入**；⑤最终全量复跑绿（778/778） |
| 影响 | 单实例生产路径下需"同一 runId 两线程并发 resume"才触发；后果 = 胜方后续写回退化为 plain SET（fence 不推进），不产生重复执行 | 回到 §7.2 表格首行 |
| 处置 | **提交官不扩权修**（属生产所有权口径，须复审 + 专项）；登记为挂账并在提交报告 §4 显著标注；CI 若因此红，按"取失败日志摘要报回"处置 | — |
