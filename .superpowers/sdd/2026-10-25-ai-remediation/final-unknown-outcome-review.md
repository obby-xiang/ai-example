# scoped 复审 — task-8-unknown-outcome（续跑重建期 UNKNOWN 的 typed 收口）

> **对象**：主仓 `<主仓>`（已含 `task-8-unknown-outcome`）。
> **对照**：`final-cancel-guard-review.md` §4 的第二项登记（"UNKNOWN 从续跑重建循环抛出 ⇒ HTTP 500"）。
> **性质**：只读。未改代码/测试/配置，未提交、未跑全量、未起服务、未读密钥、未 clean。唯一写入物是本文件。
> **范围**：只本小项；不重开已通过的 M-2′/M-7/I5/preexec-cancel/cancel-guard，不扩其它 Minor 与纯理论窗。

## VERDICT

**APPROVED**（可冻结、可提交）—— 重建期的 `STATE_UNAVAILABLE` 已被翻译成与首次 UNKNOWN **同一个** typed 结局；真实 `CANCELLED` 不会被吞成 UNKNOWN；已执行工具不会被重复执行；快照保持可重试且无伪终态；inline 路径未变。**未发现新的 Critical/Important。**

## 1. 逐项核实

| 要求 | 实现 | 位置 |
|---|---|---|
| **helper 首次与重建路径语义一致** | 唯一实现 `cancelUnknownOutcome(body)`：写 `resumed=false` / `reason=CANCEL_STATE_UNKNOWN` / `code=AI_REDIS_UNAVAILABLE` 并返回 `Outcome.CANCEL_STATE_UNKNOWN`；首次 UNKNOWN（`cancelStateOutcome`）与重建期翻译**都只调它**，没有第二份构造 | 定义 `run/ResumeService.java:1204-1209`；首次 `:1183-1185`（调用在 `:1185`）；重建 `:864` |
| 契约键一致（可忽略的附加键） | 重建路径的 `body` 此时已含 `systemRestored` 等先前写好的 additive 键，三枚契约键与首次路径逐字相同 ⇒ 对外结局同形 | 同上 |
| **不把真实 CANCELLED 吞成 UNKNOWN** | 认领前复核只有 `UNKNOWN` 才抛（`CANCELLED` 是"返回取消文本"、不抛）；因此 catch 分支在语义上不可能由 CANCELLED 触发。真实 CANCELLED 走的仍是 `resolvePending` 返回文本 → 紧跟的 `cancelStateOutcome` ⇒ `ALREADY_CANCELLED` | `gate/SpToolCallingManager.java:1461-1466`（UNKNOWN 才抛）、`:1484-1493`（CANCELLED 返回文本）；`run/ResumeService.java:872-875` |
| 只翻译既有码，其它原样再抛 | `catch (StreamViolationException ex)` 后 `if (!STATE_UNAVAILABLE.equals(ex.getCode())) throw ex;` —— 其它 `StreamViolationException` 与所有非该类型的运行异常都照旧上抛（行为不变） | `:858-864` |
| **不重读 cancelState** | catch 分支只打 WARN + 返回；不调用 `cancelState`、不重试、不放行 | `:858-864` |
| **不写终态、不进入模型** | `return` 早于 `cancelStateOutcome` 之后的历史重建、`responses.add`、`store.save` 与 `driveResumed`；`finally` 仍清 `resuming` 并按 token 归还会话门（未交棒） | `:864`；`:1063-1069` |
| **快照可重试、不伪终态** | 该分支不写快照 ⇒ 仍为 `SUSPENDED`；条目未被改写（UNKNOWN 发生在任何条目写之前）；下一次 resume 是普通重试 | `gate/SpToolCallingManager.java:1461-1466` 之前无写；用例断言 `SUSPENDED` |
| **已执行工具不重复执行** | ①`executeOnce` 在进入 `claimAndExecute` **之前**就做台账重放（`replayLedger`）⇒ 已执行条目在重试时直接回放原结果；②万一台账缺失，`tryClaimExecution` 因认领表已有记录返回 `LOST` ⇒ "已由实例 X 认领"，不执行；③gate 的证据判据先于 CANCELLED 分支，已执行条目永不被改写成"未执行" | `gate/SpToolCallingManager.java`（`executeOnce` 的 `replayLedger` 早于 `claimAndExecute`）；`run/RunStore.java:2359-2376`、`:1946-1996` |
| **inline 路径不变** | 补丁只动 `ResumeService` + 一个用例；`SpToolCallingManager`/abort/取消面未改；inline 里 `STATE_UNAVAILABLE` 仍由 `ResilientChatService` 按 `AI_REDIS_UNAVAILABLE` 失败收尾（只有 `CANCELLED` 走 `cancelTerminal`） | 补丁范围；`run/ResilientChatService.java:777-778` |
| **typed 结局与 503 映射** | `Outcome.CANCEL_STATE_UNKNOWN` ⇒ `AiController` 的 `case POOL_SATURATED, CANCEL_STATE_UNKNOWN, STATE_UNKNOWN` ⇒ **503**（与 `STATE_UNKNOWN` 同一 switch 臂，编译期耦合） | `run/ResumeService.java:1204-1209`；`web/AiController.java:1121-1122` |

## 2. 关键边界（为什么不会误吞/误判）

1. **catch 的触发面就是 UNKNOWN 本身**：`resolvePending` 内能抛 `STREAM STATE_UNAVAILABLE` 的只有认领前复核的 UNKNOWN 分支（它是 `claimAndExecute` 的第一句，先于认领与执行）；其余 Redis 类失败抛的是 `AiStateUnavailableException`（不同类型，不被本 catch 捕获，保持既有传播）。
2. **"取消已真实发生但随后读不出来"只是延后、不是吞掉**：条目已被取消侧写成 `CANCELLED`，本轮返回可重试的 503（不写终态、不发假帧），下一次 resume 会读到 `CANCELLED` 并收敛为 `ALREADY_CANCELLED`。
3. **不会误把已执行说成未执行**：证据判据（五字段 + 认领表，读失败按"有证据"）先于取消分支；同类判据也用在 `cancelPendings`。

## 3. 执行证据

| 命令 | 结果 |
|---|---|
| `mvn -o -B -s maven-settings.xml -Dtest=ParkSettlementAuditTest,AiControllerResumeOutcomeMappingTest -DfailIfNoTests=false test`（主仓 `backend/`，未 clean） | **BUILD SUCCESS，17/17**（15 + 2）；surefire XML 确认 `unknownCancelStateDuringRebuildMapsToTheTypedResumeOutcome` 真跑（`ParkSettlementAuditTest` 共 15 例全绿） |
| 用例可信度 | 新用例断言：`Outcome.CANCEL_STATE_UNKNOWN` + `reason`/`code`/`resumed` 三键、工具调用计数 **0**、`driveResumed` 未被调用、快照仍 `SUSPENDED`、无 `tool_call_end`。其中 `driveResumed` 的 `never()` 只覆盖 5 参重载（6 参重载未被断言），但同一用例的 outcome 断言已证明流程在交棒前返回，故不构成假绿 |
| 静态核对 | 上表全部 `file:line`；另核 `cancelUnknownOutcome` 只有两个调用点、`AiController` 的同一 switch 臂、`executeOnce` 的 `replayLedger` 早于 `claimAndExecute`、`ResumeService` 早退的 `finally` 归还 |
| 未执行（如实） | 全量后端/前端、Spring 上下文启动、真实 Redis/Cluster/PG/KEK、浏览器 E2E。**HTTP 层目前没有直接钉 `CANCEL_STATE_UNKNOWN ⇒ 503` 的用例**（`AiControllerResumeOutcomeMappingTest` 钉的是同一臂上的 `STATE_UNKNOWN`）；映射由 `AiController.java:1121-1122` 的同一 case 列表保证，属编译期耦合，非阻断，如需可加一行对应用例（不属本次范围） |

## 4. 提交意见

本项可随最终提交冻结。建议最终全量至少包含：`ParkSettlementAuditTest`、`ResumeServiceLeaseTest`、`CancelLedgerConformanceTest`、`AiControllerResumeOutcomeMappingTest`、`AiSessionPurgeServiceTest`、`ToolIdempotencyTest`、`ChatSessionBusyUnknownHolderTest`、`AiControllerHealthSessionLockTest`、`SessionPurgeRetryRunnerTest`，并在真实三实例复跑 `suspend-cross-instance`、`AIE2E-T-13`、`C-11`。
