# scoped 复审 — task-8-preexec-cancel（R1：已批准未执行即停止）

> **对象**：主仓 `<主仓>` 工作区（已含 `task-8-preexec-cancel`）。
> **对照**：本目录 `final-integration-final-review.md` §6-R1（该轮把"approve-then-stop 未执行 ⇒ 0 条 end"列为非阻断残项）。
> **性质**：只读。未改代码/测试/配置，未提交、未跑全量、未起服务、未读密钥、未 clean。写入物仅本文件。
> **范围**：仅本小项。**不重开**已通过的 M-2′/M-7/I5，不扩其它 Minor 与真实环境未验证项。

## VERDICT

**APPROVED** —— R1 已闭合：`APPROVED 且零证据`的条目被可靠收敛为 `CANCELLED` 且**恰 1 条** end；有认领/执行/完成证据的一律不伪装取消；普通批准与 park-off 语义不变；"认领表比 pending 字段更可靠"的跨实例窗口在代码上真正被查（发点顺序已核实），但**该窗口没有直接用例**（见 R-3，建议补 1 例）。另有两处**非阻断**的判据姿态问题（R-1 `UNKNOWN` 该处按"未取消"放行、R-2 已是 `CANCELLED` 的条目会继续走认领），都只在已登记的 RT-02/单轮单驱动前提下可达。

## 1. A：已批准未执行 → 不执行且恰 1 条 end

| 环节 | 实现 | 位置 |
|---|---|---|
| 取消收尾把 `APPROVED` 也收敛 | `cancelPendings` 判据由 `PENDING` 改为 `cancellableBeforeExecution` | `run/RunStore.java:2330-2343` |
| 判据只认"零证据" | `PENDING` ⇒ true；`APPROVED` 时若 `isExecuted / claimedAtMs>0 / claimedEpoch>0 / executedBy!=null / executedAtMs>0` 任一 ⇒ false；再查认领表 `claimedBy==null`；读失败 ⇒ false（WARN） | `run/RunStore.java:2360-2384` |
| 认领前最后一道复核 | `claimAndExecute` 首句 `settleIfCancelledBeforeClaim`：`cancelState==CANCELLED` ⇒ 重读条目 ⇒ 可收敛则写 `CANCELLED/outcome=cancelled/code=RUN_CANCELLED/executed=false/resultText="…该工具未执行。"` 并直接返回文本（**不进 claim、不执行**） | `gate/SpToolCallingManager.java:1445-1480`、调用点 `:1509-1514` |
| 文案一致 | 与 `cancelPendings`/`stoppedRoundMessages` 的取消措辞逐字一致 | `run/RunStore.java:2339`、`:1445-1480` |
| 不补结算行（防第三来源） | 本方法刻意不发 `tool_call_end`；结算仍归取消侧 `settleCancelledToolCalls`（`CANCELLED` ∩ `{CONFIRM,FRONTEND}`）或批循环 abort 行，两者经 `ai:observed` 仲裁 | `gate/ConfirmGate.java:1804-1834`；`gate/ToolCallEndTrace.java:104-125` |
| 续跑侧终止 | `resolvePending` 之后**立即**再走 `cancelStateOutcome`（`cancelParked` → 取消终态 + 裁决帧 + 结算行），并且在 `responses.add(...)` **之前** return ⇒ 不会把 CANCELLED 工具结果喂给模型 | `run/ResumeService.java:854-859`（`responses.add` 在 `:867`） |
| 不误当普通失败 | `haltsBatch` 只认 `FAILED && !retryable`，取消不会触发；且早退发生在 `skipAsUpstreamFailed` 之前 ⇒ 后续条目不补 SKIPPED | `gate/SpToolCallingManager.java:741-752`；`run/ResumeService.java:856-859` |
| 返路径无泄漏 | 早退走外层 `finally`（`resuming.remove` + 未交棒时按 token 释放会话门） | `run/ResumeService.java:1045-1055` |
| 用例 | `approvedButNeverClaimedIsCancelledAndSettledExactlyOnce`（APPROVED→CANCELLED + 恰 1 end）、`approvedEntryIsNotExecutedWhenStopLandsBeforeTheClaim`（**真实工具回调计数=0** + 恰 1 end）、`cancelLandingBetweenThePreCheckAndTheClaimEndsTheResumeAsCancelled`（`ALREADY_CANCELLED` + 未执行 + `verify(cancelParked)`） | `conformance/CancelLedgerConformanceTest.java`、`gate/ParkSettlementAuditTest.java` |

## 2. B：已认领/执行/完成不伪取消

- 判据以**条目证据**为准：`executed / claimedAtMs / claimedEpoch / executedBy / executedAtMs` 任一命中即拒；认领表有记录也拒（`RunStore.java:2371-2382`）。
- `tryClaimExecution` 的写入顺序印证"证据一定先于 pending 字段之外可见"：先 `HSETNX ai:claim`（`:1969`），**之后**才回写 `claimedAtMs/claimedEpoch/executed/executedBy` ⇒ 认领表中段那段时间里 pending 仍是"零证据"，正是需要查表的原因（见 §3）。
- 结论：取消侧 `cancelPendings` 不动这类条目 → `settleCancelledToolCalls` 也不会为它们补"取消"行；正在执行的那一条由认领胜者按既有口径结算（`claimAndExecute` 的 `settleObservation` 分支）。
- 用例：`claimedOrCompletedEntriesAreNeverRelabelledAsCancelled`（已认领仍 `APPROVED` 且 `executedBy`/resultText 原样、已完成仍 `EXECUTED` 且结果含 `#4242`、两者 end 均为 0 条）。

## 3. C：普通 approve / park-off 不变 + 跨实例 claim 表窗口

- **普通 approve**：`cancelState != CANCELLED` ⇒ `settleIfCancelledBeforeClaim` 返回 null，照常 `tryClaimExecution` ⇒ 行为不变，仅多一次 `cancelState` 读（`CancellationRegistry.cancelState` 先看进程内标志，未取消时一次 Redis 读；取消未发生时零副作用）。Redis 不可用时该读返回 `UNKNOWN` 且随后 `tryClaimExecution` 因 authority 只读 ⇒ STALE_FENCE，不会偷偷执行。
- **park-off**：等待线程仍在 ⇒ 结算权不被取消侧接管（`settleCancelledToolCalls` 首行 `!publishesRequestsAheadOfLoop() → 0`）；新增的认领前复核对 park-off 是**加强**（批准后、认领前的取消窗口同样被拦住），用例 `cancelWithParkOffDoesNotSettleFromTheCancelSide` / `abortLineStaysSingleWhenParkIsOff` 仍绿。
- **跨实例 claim 表窗口**（是否真正处理）：**是**。发点顺序 `HSETNX ai:claim` → 回写条目字段（`run/RunStore.java:1969` → `:1984-1996`），而 `cancellableBeforeExecution` 在"条目零证据"时必查 `claimedBy`（同一 `ai:claim:<runId>` 的同一 field，`:2078-2081`），命中即拒；查表失败按拒（保守）。替身侧同语义：`InMemoryRunStore.claimedBy`（`:603`）读与 `tryClaimExecution` 共用的 `claims` 表，`cancelPendings` 复用生产判据（`:694-706`）。

## 4. 新问题复核（未发现 Critical/Important）

- **取消判据读失败是否被吞**：`settleIfCancelledBeforeClaim` 的 catch 是 `log.debug` + `return null`（继续认领）；`cancelState` 自身的读失败会返回 `UNKNOWN` 并计 `RedisDegradation.A5_CANCEL_UNKNOWN`，此处 `state != CANCELLED` ⇒ 同样继续。**这不是静默**（A5 计数器可观测），且上游两道门对 `UNKNOWN` 都是 fail-closed（`SpToolCallingManager.checkCancelled:1097-1110`，其 UNKNOWN 分支 `:1103-1109` 抛 STATE_UNAVAILABLE；`ResumeService.cancelStateOutcome:1166-1179` 返回 `STATE_UNKNOWN` 503），因此"标志已置 + 读不出来"在校验早于此处的路径上已被拦住。可复现的窗口只有"两次相邻读之间刚好抖动并恢复"。→ 记为 **R-1（非阻断）**。
- **重复 end**：本方法不发结算行；取消侧/批循环 abort 各由 `ai:observed` 仲裁 ⇒ 恰 1（用例均断言 `.hasSize(1)`）。
- **业务副作用**：本方法只写条目状态，不调用任何工具、不写台账、不发帧；`cancellableBeforeExecution` 无写。
- **状态/文案不符**：取消态、`outcome/code`、`resultText` 与既有取消口径同字；不会把"已批准"改成"失败"。
- **取消被当普通失败**：`haltsBatch` 不含 CANCELLED，且续跑在 `responses.add` 之前早退 ⇒ 不会给模型一条"CANCELLED 工具结果"，也不会连锁 SKIPPED。
- **正常执行被误拦**：仅当 `cancelState == CANCELLED` 才动作；普通批准/未取消运行不受影响（既有 26 例回归含批准执行链）。

## 5. 非阻断残项（如实登记，建议随下一次小改一并处理）

- **R-1（判据姿态，`UNKNOWN` 处**）：`gate/SpToolCallingManager.java:1456` 为 `if (state != CANCELLED) return null` ⇒ `UNKNOWN` 与 `NOT_CANCELLED` 同路（继续认领），与紧邻的 `checkCancelled` 对 `UNKNOWN` 的 fail-closed 姿态不一致。最小改法：`UNKNOWN` 时抛 `StreamViolationException(STATE_UNAVAILABLE)`（不执行、**不**改写条目），与 `checkCancelled:1103-1109` 同口径。可达性限于"相邻两读之间抖动并恢复"，故不阻断。
- **R-2（已是 `CANCELLED` 的条目仍会走认领）**：`cancellableBeforeExecution` 对 `CANCELLED` 返回 false ⇒ `settleIfCancelledBeforeClaim` 返回 null ⇒ 继续 `tryClaimExecution`，而 `tryClaimExecution` **不校验条目状态**（`run/RunStore.java:1946-1996`）。也就是说，若另一执行体已把该条目标成 CANCELLED 而本执行体仍走到 claim（只可能发生在已登记的 `RT-02/E3` 单轮多驱动前提：无租约/manual resume），工具仍会执行。非本次回归（补丁前同样如此），最小加固：`CANCELLED` 且无认领证据时直接返回取消文本、不 claim（可判性同 `settleIfCancelledBeforeClaim`）。
- **R-3（测试缺口）**：`cancellableBeforeExecution` 的"**只有认领表、条目零证据**"那一格没有直接用例 —— 现有 R1-B 用例给的是条目字段证据（`claimedAtMs/executedBy`）。建议补一条确定性用例：`APPROVED` 零证据 + 仅 `putIfAbsent(ai:claim)`（或替身 `claimExecution` 前后取窗口） ⇒ `cancelPendings` 必须**不**改写、且不得补 end。这是"认领表比 pending 更可靠"这一改动的唯一未被直接钉住的判据格。

## 6. 本轮执行证据

| 命令 | 结果 |
|---|---|
| `mvn -o -B -s maven-settings.xml -Dtest=CancelLedgerConformanceTest,ParkSettlementAuditTest -DfailIfNoTests=false test`（主仓 `backend/`，未 clean） | **BUILD SUCCESS，26/26**（12 + 14）；surefire XML 逐条核对到新用例已真跑：`approvedButNeverClaimedIsCancelledAndSettledExactlyOnce`、`approvedEntryIsNotExecutedWhenStopLandsBeforeTheClaim`、`claimedOrCompletedEntriesAreNeverRelabelledAsCancelled`、`cancelLandingBetweenThePreCheckAndTheClaimEndsTheResumeAsCancelled` |
| 静态核对 | 上表全部 `file:line`；另核 `cancelPendings` 仅被 `cancelTerminal`/`emitCancelOutcomes` 调用（无正常路径调用）、`tryClaimExecution` 的 claim→字段写入顺序、`haltsBatch/isUnsettled` 不含 CANCELLED、`ResumeService` 早退的 `finally` 归还、`InMemoryRunStore` 的 `claimedBy`/`cancelPendings` 同语义 |
| 未执行（如实） | 全量后端/前端、Spring 上下文启动、真实 Redis/Cluster/PG/KEK、浏览器 E2E；`ResumeService` 端到端仅以 `ParkSettlementAuditTest` 的续跑用例覆盖 |

## 7. 提交意见

本小项可以随最终提交一起走；建议在最终全量里保留 `CancelLedgerConformanceTest`、`ParkSettlementAuditTest`、`AiSessionPurgeServiceTest`、`ToolIdempotencyTest`、`ChatSessionBusyUnknownHolderTest`、`AiControllerHealthSessionLockTest`、`SessionPurgeRetryRunnerTest` 七类，并在真实三实例上复跑 `suspend-cross-instance`（R1 直接改写了"已批准未执行"的取消路径）。R-1/R-2/R-3 建议作为提交后的第一个小项（三处都很小），不影响本次提交。
