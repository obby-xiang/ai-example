# Task 8 · UNKNOWN typed 结局收尾（续跑路径）— 施工与实测报告（2026-10-25）

**对象**：scoped 复核的最后一条 —— `SpToolCallingManager` 新增的 UNKNOWN guard 抛 `STATE_UNAVAILABLE`，在
`ResumeService` 重建循环里无人翻译 ⇒ HTTP 变 500，违反已批准的 UNKNOWN/503 契约。**只做这一处收尾**，不扩范围。
**基线**：主仓 `<主仓>` **当前工作区**（HEAD `2eb2539` + 已合入的 `task-8-cancel-guard-final.patch` 等全部近期补丁，244 项改动）。施工在隔离副本 `<隔离副本>/_m2unk-163`（基线快照 commit `cb274ab`，`core.autocrlf=true` 与主仓同配置；被改文件与主仓工作区逐字节一致）。
**主仓零改动、零提交、零推送**；主仓仅新增本报告与 `task-8-unknown-outcome.patch`。
**Maven**：`<本机工具链>/JetBrains/IntelliJ IDEA/plugins/maven-plugin/lib/maven3/bin/mvn.cmd`（`-o -B -s maven-settings.xml`；未 clean、未全量、未起服务）。
**改动面**：`ResumeService`（唯一生产改动）+ `ParkSettlementAuditTest`（用例）。**未改** `SpToolCallingManager`（inline 语义因此逐字不变）、前端/`docs`/配置；未加新机制、未新增错误码。

---

## 0. 结论

| 项 | 判定 |
|---|---|
| 缺陷是否真实 | **是**：`service.resume` 直接抛 `StreamViolationException(STATE_UNAVAILABLE)`（RED 见 §1），未翻译 ⇒ HTTP 500 |
| 修复 | 在 `ResumeService` 重建循环里把**同一个既有异常**翻译成**同一个既有 typed 结局**（`cancelUnknownOutcome`，与首次 UNKNOWN 共用）。不重读取消状态、不放行、不落终态 |
| inline 路径 | **不变**：`SpToolCallingManager` 一行未改，`STATE_UNAVAILABLE` 在内联批循环仍是既有语义 |
| 契约 | `CANCEL_STATE_UNKNOWN` + `reason=CANCEL_STATE_UNKNOWN` + `code=AI_REDIS_UNAVAILABLE`（既有码，无新增） |

---

## 1. RED（确定性：先成功后抖动）

`ParkSettlementAuditTest#unknownCancelStateDuringRebuildMapsToTheTypedResumeOutcome`
（真实 `ResumeService` + 真实 `SpToolCallingManager`；`cancelState` 序列 = 入口 `NOT_CANCELLED` → 逐条执行前
`NOT_CANCELLED` → 认领前复核 `UNKNOWN`，正是"先成功读再抖动"）：

```
mvn -o -B -s maven-settings.xml -Dtest=ParkSettlementAuditTest test
→ Tests run: 15, Errors: 1
→ unknownCancelStateDuringRebuildMapsToTheTypedResumeOutcome:343 » StreamViolation 取消状态未知（Redis 读失败），工具执行在认领前终止（runId=run-park-audit）
   at …SpToolCallingManager.settleIfCancelledBeforeClaim(SpToolCallingManager.java:1464)
   at …SpToolCallingManager.claimAndExecute(:1532) → executeOnce(:1425) → resolvePending(:1974)
   at …ResumeService.resumeInternal(ResumeService.java:849) → resumeWithReason(:557) → resume(:470)
```

即：**异常穿出 `resume()`**，HTTP 层只会看到未翻译的运行时异常（500）。

---

## 2. 修复（`ResumeService`，+38/−5 含注释与 helper）

1. **翻译点**：重建循环里 `resolvePending` 外层加一个小 `try/catch(StreamViolationException)`：
   - `code == STATE_UNAVAILABLE` ⇒ `return cancelUnknownOutcome(body)`；
   - 其它 `StreamViolationException` 原样再抛（不吞、不改语义）。
2. **共用 helper**：把首次 UNKNOWN 的三行结局抽成 `cancelUnknownOutcome(body)`，`cancelStateOutcome` 的
   UNKNOWN 分支改为调用它 —— 两个使用点（首次读 / 认领窗口抛出的既有异常）**逐字同形**，避免"同一个 Redis 抖动
   因落在窗口前/后给出两种对外结局"。
3. **未做的事**（按指示）：不重新读 `cancelState` 来"掩盖"刚才的 UNKNOWN；不放行认领；不写终态；不改 inline 错误语义；
   不新增错误码/机制/重试。

行为结果：不执行工具、不进入模型（重建循环直接 return，未交棒 `driveResumed`）、快照保持 `SUSPENDED`（可重试），
响应体 = 既有 `CANCEL_STATE_UNKNOWN` 契约。

---

## 3. GREEN（2 类 30 例）

```
mvn -o -B -s maven-settings.xml -Dtest=ParkSettlementAuditTest,CancelLedgerConformanceTest test
→ Tests run: 30, Failures: 0, Errors: 0 — BUILD SUCCESS
```

| 类 | 例数 | 覆盖 |
|---|---|---|
| `ParkSettlementAuditTest` | 15（14 既有 + 1 新） | 新：UNKNOWN typed 结局（outcome/reason/code、工具 0 次、未交棒模型、快照 `SUSPENDED`、0 条结算行）；既有：批准执行、认领窗口 `ALREADY_CANCELLED`、拒绝、前端超时、多卡到期 |
| `CancelLedgerConformanceTest` | 15 | 取消/收敛/仲裁面不回归（含 UNKNOWN fail-closed 的既有用例与 A/B/C 反例） |

日志：`<隔离副本>/_m2unk-163/red.log`（RED）、`<隔离副本>/_m2unk-163/green.log`（GREEN）。

---

## 4. 未验证边界（如实登记）

1. **HTTP 状态码本身未实跑**：本轮验证到 `ResumeService` 的 typed 结局（`Outcome.CANCEL_STATE_UNKNOWN` +
   `reason`/`code`），**未**起服务、未跑 `AiController` 的映射层；"503"由既有映射（该 outcome 在 controller 的既有分支）
   推定，未在本轮端到端断言。
2. **`resume()` 之外的同类路径**：`resolvePending` 也可能被其它调用方使用（当前生产仅 `ResumeService` 重建循环一处）；
   其它未来调用方若直接调它，仍会看到 `STATE_UNAVAILABLE`（inline 语义即如此，本轮刻意不改）。
3. **快照可重试性**：断言了状态仍为 `SUSPENDED` 且未写 `error`（本用例路径），但未验证"下一次 resume 在同一次抖动
   消失后能正常推进"（需真实 Redis 抖动注入，超范围）。
4. 未跑全量/未 clean/未起服务；主控随后做最终 SHA 干净树全量。

---

## 5. 交付物与命令

| 文件 | 内容 |
|---|---|
| `task-8-unknown-outcome.patch` | 2 文件 / +70 / −5（`ResumeService` +38/−5、`ParkSettlementAuditTest` +37） |
| `task-8-unknown-outcome-report.md` | 本报告 |

| 命令（隔离副本） | 结果 |
|---|---|
| RED `-Dtest=ParkSettlementAuditTest` | `Tests run: 15, Errors: 1`（异常穿出 `resume()`，stack 见 §1） |
| GREEN `-Dtest=ParkSettlementAuditTest,CancelLedgerConformanceTest` | `Tests run: 30, Failures: 0, Errors: 0` — BUILD SUCCESS |
| `git apply --check --verbose task-8-unknown-outcome.patch`（主仓当前工作区，只读） | 2 文件全部通过 |

**提交建议**：`fix(ai): 续跑重建期间的 UNKNOWN 取消状态翻译为既有 CANCEL_STATE_UNKNOWN 结局`
