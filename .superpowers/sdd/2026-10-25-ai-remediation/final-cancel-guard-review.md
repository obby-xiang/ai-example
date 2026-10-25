# scoped 复审（代码冻结前最后一项）— task-8-cancel-guard-final

> **对象**：主仓 `<主仓>` 工作区（已含 `task-8-cancel-guard-final`）。
> **对照**：`final-preexec-cancel-review.md` 的 R-1（UNKNOWN 放行）/ R-2（已 CANCELLED 仍走认领）/ R-3（claim-only 无用例）。
> **性质**：只读。未改代码/测试/配置，未提交、未跑全量、未起服务、未读密钥、未 clean。唯一写入物是本文件。
> **范围**：只本小项；**不重开**已审过的 M-2′/M-7/I5/preexec-cancel 结论，不扩其它 Minor 与纯理论崩溃窗（只在 §4 一行登记，供冻结决策知情）。

## VERDICT

**APPROVED**（可冻结、可提交）—— 三项反例全部闭合；本轮**未发现新的 Critical/Important**。判据方向单调收紧（"证明不了就当有"），且没有把"无证据"变成可执行或可取消的空白。

## 1. 逐项核实

| 要求 | 实现 | 位置 |
|---|---|---|
| **UNKNOWN（含读异常）fail-closed，且不动条目/终帧** | 读异常 ⇒ `log.warn` + `state=UNKNOWN`；`UNKNOWN` ⇒ 抛既有 `StreamViolationException(STATE_UNAVAILABLE)`（= `AI_REDIS_UNAVAILABLE`），**在任何条目写入/帧发射之前**返回 | `gate/SpToolCallingManager.java:1453-1466` |
| 与相邻门同口径 | 与 `checkCancelled` 的 `UNKNOWN ⇒ STATE_UNAVAILABLE` 一致；内联路径由 `ResilientChatService` 按 `AI_REDIS_UNAVAILABLE` 失败收尾（只有 `CANCELLED` 才走 `cancelTerminal`） | `:1097-1110`；`run/ResilientChatService.java:777-778` |
| **已 CANCELLED + 无证据 ⇒ 返回既有取消文本、不认领不执行** | 先查证据；无证据且状态 `CANCELLED` ⇒ 直接返回 `resultText`（空则 `CANCELLED_BEFORE_CLAIM_TEXT`），**不 `putPending`、不 `tryClaimExecution`、不发结算行** | `:1484-1493`；常量 `:1353` |
| **hasExecutionEvidence：五字段 + 认领表；读失败/null 视为有证据** | `pending==null` ⇒ true；`executed / claimedAtMs>0 / claimedEpoch>0 / executedBy!=null / executedAtMs>0` ⇒ true；否则查 `claimedBy`（同一 `ai:claim:<runId>` 字段）；异常 ⇒ WARN + true | `run/RunStore.java:2359-2376` |
| **取消收尾判据** | `PENDING` ⇒ 可取消；`APPROVED` ⇒ `!hasExecutionEvidence`；其余状态一律不可取消 | `run/RunStore.java:2387-2398`；调用点 `:2334` |
| **认领/执行/完成不伪取消** | 证据命中即 `cancellableBeforeExecution=false` ⇒ 取消侧不改写、不补"取消"行；gate 侧命中证据则返回 null 走既有 claim 失败/对账口径（`LOST` ⇒ "已由实例 X 认领" / `STALE_FENCE` ⇒ 写回权已转移） | `:2360-2376`、`:2387-2398`；`gate/SpToolCallingManager.java:1480-1483`；`run/RunStore.java:1946-1996` |
| **claim-only 不转 CANCELLED** | 测试缝 `seedClaimOnly` 只写认领表 ⇒ `claimedBy != null` ⇒ 有证据 ⇒ 保持 `APPROVED`、end 为空 | `conformance/InMemoryRunStore.java:895-897`；用例 `CancelLedgerConformanceTest.claimHashWrittenBeforePendingIsNeverRelabelledAsCancelled` |
| **end 不重复** | 本 gate 始终不发结算行；发点仍是取消侧 `settleCancelledToolCalls` 与批循环 abort，均经 `ai:observed` 首写仲裁 | `gate/ConfirmGate.java:1804-1834`；`gate/ToolCallEndTrace.java:104-125` |
| **普通 approve 仍正常** | `NOT_CANCELLED` ⇒ `:1467-1468` 返回 null ⇒ 照常认领执行（多一次 `cancelState` 读，零副作用；Redis 不可用时 `tryClaimExecution` 本就 `STALE_FENCE` 拒执） | `:1467-1468` |
| **无证据不是空白** | 四条显式分支：有证据 ⇒ 认领路径；`CANCELLED` 无证据 ⇒ 返回文本不认领；`PENDING/APPROVED` 无证据 ⇒ 写 `CANCELLED` 并返回文本；其余状态 ⇒ 走既有认领语义（`resolvePending.shouldExecute` 只允许 `APPROVED` / `PENDING+BACKEND` / `EXECUTED&&!executed`，内联批只给 `PENDING`） | `:1480-1509`；`gate/SpToolCallingManager.java:1965-1968` |
| 文案一致 | `CANCELLED_BEFORE_CLAIM_TEXT` 与 `cancelPendings`、`ConfirmGate#cancel`、`stoppedRoundMessages` 的取消措辞逐字同源 | `:1353`；`run/RunStore.java:2341` |
| 取消不被当普通失败 | `haltsBatch` 只认 `FAILED && !retryable`；续跑在 `responses.add` 之前早退 ⇒ 不给模型 CANCELLED 工具结果、不连锁 SKIPPED | `:741-752`；`run/ResumeService.java:854-867` |

## 2. 三项反例的对应证据（新用例均已真跑）

| 反例 | 用例 | 断言要点 |
|---|---|---|
| UNKNOWN | `unknownCancelStateBeforeTheClaimFailsClosedLikeCheckCancelled` | 抛 `STATE_UNAVAILABLE`；工具调用计数 **0**；条目仍 `APPROVED`、`executed=false`；**无任何 end**（`endsFor` 为空） |
| 已 CANCELLED + 旧副本 | `alreadyCancelledEntryWithStaleApprovedCopyIsNotClaimedOrExecuted` | 计数 0；返回文本**逐字等于**存储里的取消文本；条目仍 `CANCELLED/executed=false`；`claimedBy == null`（不占认领位）；无 end |
| claim-only 窗口 | `claimHashWrittenBeforePendingIsNeverRelabelledAsCancelled` | 条目仍 `APPROVED`、`executed=false`、resultText 原样；无"取消"end |

## 3. 执行证据

| 命令 | 结果 |
|---|---|
| `mvn -o -B -s maven-settings.xml -Dtest=CancelLedgerConformanceTest,ParkSettlementAuditTest -DfailIfNoTests=false test`（主仓 `backend/`，未 clean） | **BUILD SUCCESS，29/29**（15 + 14）；surefire XML 逐条确认三个新用例与既有 park/取消用例同批通过 |
| 静态核对 | 上表全部 `file:line`；另核 `cancellableBeforeExecution` 唯一调用点（`cancelPendings`，`RunStore.java:2334`）、`hasExecutionEvidence` 唯一外部调用点（gate `:1480`）、`settleIfCancelledBeforeClaim` 调用链（`claimAndExecute` 首句 `:1509`→`executeOnce`）、`ResumeService` 早退的 `finally` 归还、`InMemoryRunStore` 与生产判据同源 |
| 未执行（如实） | 全量后端/前端、Spring 上下文启动、真实 Redis/Cluster/PG/KEK、浏览器 E2E；真实三实例 `suspend-cross-instance` 由最终 CI 覆盖 |

## 4. 冻结决策用的知情登记（均非本轮引入，无需为本项改动）

- **PENDING 分支不查认领表**：`cancellableBeforeExecution` 对 `PENDING` 直接返回 true（`RunStore.java:2391-2393`），因此"条目仍 PENDING、认领表已写、字段未回写"这一格在**取消收尾**侧不查证据；`hasExecutionEvidence` 只在 `APPROVED` 分支被用。此为前两轮既有形态，且只在已登记的"单轮多驱动"/RT-02 前提（无租约手工 resume 与取消收敛并发）下可达；若要对称收敛，最小改法是 `PENDING && !hasExecutionEvidence(...)`，代价是每张被取消的卡多一次 HGET。不改也不影响本项冻结。
- **UNKNOWN 从续跑重建循环抛出**：`resumeInternal` 的外层 `try` 只 `catch (RejectedExecutionException)`（`ResumeService.java:654/1032`），故 gate 新抛的 `STATE_UNAVAILABLE` 会穿过到 `POST /runs/{id}/resume`（HTTP 层未捕获）成为 500；触发条件只是"循环头检查与认领前复查之间那一次读刚好失败"（同一毫秒级抖动窗），且**不改任何状态**（条目/终帧均未动），重试即恢复；派发路径（`ConfirmGate.runResume`）与启动扫描各自有 `catch (Exception)`，不受影响。属既有"该循环内异常不包装"的形态，非本项引入，不建议为此动冻结。
- **内联路径在 UNKNOWN 时会补一条 abort 行**：`traceToolAborted` 对非 PENDING 条目走 `emitCustomOnce`（上一轮已审的 abort 规则），该行是 `outcome=failed/code=TOOL_EXECUTION_THREW/executed=false`（不是"取消"行，也不是假成功），且 `SheetAudit` 不记账；只有在"该轮随后被僵尸接管并真的执行成功"时才会让那一次成功的结算行被同一 `ai:observed` 槽位挡掉，同属已登记的接管/降级窗。无需为本项改动。
- **`CANCELLED` 分支回显存储文本**：若历史行里带了非取消文案，模型侧仍以 `outcome=CANCELLED` 的封套读它（不可能被读成成功），且 `cancelPendings` 写取消时必定覆盖文本；无需改动。

## 5. 提交意见

本项可随最终提交一起冻结。最终全量请至少包含：`CancelLedgerConformanceTest`、`ParkSettlementAuditTest`、`AiSessionPurgeServiceTest`、`ToolIdempotencyTest`、`ChatSessionBusyUnknownHolderTest`、`AiControllerHealthSessionLockTest`、`SessionPurgeRetryRunnerTest`；真实三实例复跑 `suspend-cross-instance`、`AIE2E-T-13`、`C-11`。§4 四项建议作为提交后的第一批小项（都只是判据对称性与异常包装），不影响本次提交。
