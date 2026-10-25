> 来源=<仓外证据>/park-audit-fix-report.md（2026-10-25 归档）· 仓外正式过程报告，属历史证据，不代替现行基线/决策文档（docs/02、docs/03、docs/adr/）
# 修复报告 · AIE2E-T-13：park/续跑复用绕过审计面（SheetAudit + tool_call_end）

> 基线 `main @ 34fd8f4`（`fix: 收口多实例运行与 AI 会话可靠性`）。
> 全部工作在隔离副本 `<验证副本>/_verify-20261025-park-audit`（`git clone` 自 main 工作树，HEAD = `34fd8f4`，clean）。
> **未改 main、未起本地服务、未跑 workflow/拓扑、未读取任何密钥**；单测全程离线依赖本地 `~/.m2` + IDEA 内置 Maven（无 `clean`）。

## 0. 结论摘要

| 项 | 结论 |
|---|---|
| 缺陷 | park 化之后，工具的结算从"内联批循环"挪到"续跑复用结果"，而 `tool_call_end` 与 `SheetAudit.record` 的唯一发点仍留在内联循环 ⇒ 9 个表格工具（全部 `FRONTEND` 通道）的 `readCalls` 恒 0、日志出现"有 start 没有 end" |
| 修法 | 把观测面挂到**所有权边界**上：① 前端工具的决策/放弃写入方（`tryRecordDecision` 胜者）在**本进程已无等待方且 park 开启**时补一行；② 后端执行在**认领胜者**（`tryClaimExecution`）补一行；载荷构造收敛到唯一构造点 `ToolCallEndTrace`。续跑的"复用结果"分支**不补**（反放大） |
| T-13 断言 | 单测级已闭合：`aggregate_sheet` 回灌 ⇒ `readCalls 0 → 1`、`tool_call_end` 恰 1 条且带 `dataClass=D1 totalRows=5 sheetVer=1` |
| 回归 | 后端**全量 2678 用例 / 0 失败 / 0 错误 / 0 跳过**（`BUILD SUCCESS`，7:33）；`check-docs` **6/6**；补丁对 `34fd8f4` `git apply --check` 通过且往返生成逐字节相同 |
| 范围 | 只碰 `gate` + `SheetAudit` 文档；`AiConfigSeeder` / `AiConfigService` / `PROVIDER_GONE` 与 `ConfirmGate` 的续跑派发/provider-gone 逻辑**零改动** |
| 交付物 | 补丁 `<仓外证据>/park-audit-fix.patch`（6 文件，+708/−44） |

## 1. 根因（与 `main-ci-34fd8f4-analysis.md` 一致，附代码定位）

| 事实 | 位置（34fd8f4） |
|---|---|
| 内联批循环是**唯一**发 `tool_call_end` + `SheetAudit.record` 的地方 | `SpToolCallingManager.java:662` / `:664-666` |
| park：请求帧投递后线程交还，不再等待 | `ConfirmGate.java:601-641`（`approval_park` + `RunParkedException`） |
| 续跑结算走"复用结果"，不执行、不发 end、不记账 | `SpToolCallingManager.java:1844-1847`（`续跑结算复用结果`） |
| 决策写只有一个全集群写者（`ai:decided` HSETNX） | `RunStore#tryRecordDecision`（`:2390-2420`） |
| 执行只有一个全集群认领者（`ai:claim`） | `RunStore#tryClaimExecution`（`:1679-1759`） |

因 9 个表格工具全部是 `FRONTEND` 通道（`SheetSwitches.READ_TOOLS/WRITE_TOOLS/DUAL_TOOLS`），它们的每一次结算都在 park 之后发生 ⇒ 审计面与 end 行 100% 缺席（红跑 64 park 工具 / 64 条无 end）。

## 2. 设计：exactly-once 的三条所有权边界

| 结算种类 | 谁发（唯一） | 何时不发（防双记） |
|---|---|---|
| 前端 `frontend_result` | `ConfirmGate#doSubmitFrontendResult` 中 `tryRecordDecision` 胜者（`ConfirmGate.java:1622`） | `woke=true`（等待线程接手，内联循环会发）；`park 关闭`（跨实例时结算权在另一实例的等待线程，`signal` 的 miss 不代表没人结算） |
| 前端 `dismiss/cancel` | `ConfirmGate#dismiss` 中同一把 guard 的胜者（`ConfirmGate.java:1671`） | 同上 |
| 后端/确认工具执行（成功/失败/超时） | `SpToolCallingManager#claimAndExecute` / `#settleToolTimeout` 中**认领胜者**（`SpToolCallingManager.java:1518-1520` / `:1632-1634`），仅 `settleObservation=true`（续跑入口 `resolvePending`） | 内联路径 `settleObservation=false`（批循环自己发）；认领失败（`LOST`/`STALE_FENCE`/台账重放）不发 |
| 续跑"复用结果"分支 | **不发**（`resolvePending` 兜底分支保持原样） | 重挂/重试会反复经过它；在此补记 = 把计数放大成"重挂次数"（用户明确点名的反向坑） |

`woke` 是**本进程**事实，而决策写与 park 退出共用 `submitLock`（`switchParkedLifecycle`，`ConfirmGate.java:778-789`），两个出口互斥 —— 所以"`woke=false`"与"本进程不会再有任何内联结算"等价。跨实例的非 park 部署由 `park 开启` 这一条兜住（`publishesRequestsAheadOfLoop()`，与 `tool_start` 前移同一判据）。

### 载荷唯一构造点

新增 `gate/ToolCallEndTrace`（包内可见，无 Spring 依赖）：

- `emit(...)`：**内联批循环与 park 结算共用的唯一 `tool_call_end` 载荷构造点**（字段集合、键序、取值口径一份）。原 `SpToolCallingManager:634-666` 的 30 行载荷拼装搬入，内联路径取值逐字不变（`ms` 仍含等待、`execMs` 仍扣 `reqAtMs→resolvedAtMs`、`skipped=false`、`chars=text.length()`、仅非空 `sheetMeta` 追加 8 键）。
- `emitForSettledEntry(...)`：park 结算的耗时口径（钟点只能取自条目）：`ms = 结算时刻 − 最早起点(reqAtMs/claimedAtMs)`、`execMs = 结算时刻 − 认领时刻`（前端工具无认领 ⇒ `0`），缺项按 `executedAtMs → resolvedAtMs → now` 逐级回落。
- 审计计数与台账与这一行**同一时点**（`SheetAudit.record(sessionId, settled)`，仅当 `sheetMeta` 非空）。

`ConfirmGate` 新增**可选**注入 `setSheetAudit`（`@Autowired(required=false)`，与 `SpToolCallingManager#setSheetAudit` 同体例，不动任何构造器 ⇒ 既有 6/8/9 参装配面零变化）。

## 3. 文件清单（6 件，+708/−44）

| 文件 | 变更 |
|---|---|
| `backend/src/main/java/com/example/configmgr/ai/gate/ToolCallEndTrace.java` | **新增**（123 行）：`tool_call_end` 唯一载荷构造点 + park 结算耗时口径 + 审计记账 |
| `backend/src/main/java/com/example/configmgr/ai/gate/ConfirmGate.java` | +62：可选 `SheetAudit` 注入；`doSubmitFrontendResult` / `dismiss` 在决策胜者处按 `!woke && park` 补结算；新增 `settleParkedToolCall`（失败只 WARN）。**未触碰** `submitDecision` / `maybeTriggerResume` / `triggerResume` / `runResume` |
| `backend/src/main/java/com/example/configmgr/ai/gate/SpToolCallingManager.java` | +68/−44：内联载荷改用 `ToolCallEndTrace.emit`；`executeOnce/claimAndExecute/settleToolTimeout` 增 `settleObservation` 标志（续跑入口 `resolvePending` 传 `true`）；新增 `sessionIdOf` 事件锚点。`resolvePending` 的复用分支**行为不变**，仅补文档说明"这里不是发点" |
| `backend/src/main/java/com/example/configmgr/ai/tool/SheetAudit.java` | +4/−2：仅类注/方法注订正"由谁发射"（旧文案说只有 `SpToolCallingManager`） |
| `backend/src/test/java/com/example/configmgr/ai/gate/ParkSettlementAuditTest.java` | **新增**（459 行，9 例）：真闸门 + 真 `ResumeService` + 真 store（往返深拷贝）复现 park → 回灌 → 续跑链路 |
| `docs/AI对话日志链路追踪手册.md` | +2/−1：`tool_call_end` 的 park 结算发点与 `ms`/`execMs` park 口径登记（§2.5 成对性口径第 2 条扩写） |

未改：`AiConfigSeeder.java`、`AiConfigService.java`、`Any*ProviderGone*`、`ResumeService.java`、`AiController.java`、任何前端文件、任何 `scripts/*`。

## 4. RED → GREEN

命令（IDEA 内置 Maven 3.9，JDK 21，无 `clean`，离线依赖本地仓库）：

```
cd <repo>/backend
set JAVA_HOME=<工具安装目录>/Java/latest/jdk-21
"<IDEA>/plugins/maven-plugin/lib/maven3/bin/mvn" -s maven-settings.xml -B test \
  -Dtest=ParkSettlementAuditTest -DfailIfNoTests=false
```

| 阶段 | 结果 | 证据 |
|---|---|---|
| RED（仅加了 `setSheetAudit` 注入缝、无任何发点/记账） | **Tests run: 8, Failures: 7**（唯一绿的是"非 park 行为不变"的对照用例） | `<仓外证据>/park-audit-evidence/red-targeted.log` |
| GREEN（实现后，含新增第 9 例） | **Tests run: 9, Failures: 0** | `<仓外证据>/park-audit-evidence/green-targeted.log` |

RED 的关键失败行（原文）：

```
[ERROR] ParkSettlementAuditTest.parkedFrontendResultSettlesReadCallAndExactlyOneEnd:203
        [读工具结算必须进审计面（T-13 的红就在这一行）] ["readCalls"=0L (expected: 1L)]
[ERROR] ParkSettlementAuditTest.parkedDismissSettlesCancelledWriteOnce:278
        [写工具放弃 = writePlans + writeCancelled（与内联口径同源）] ["writePlans"=0L (expected: 1L)]
[ERROR] ParkSettlementAuditTest.resumeExecutionSettlesBackendToolExactlyOnce:304
        [续跑执行同样要有 end（不是内联循环专有）]
[ERROR] ParkSettlementAuditTest.resumeExecutionFailureSettlesOnceWithTheRealOutcome:325
[ERROR] ParkSettlementAuditTest.resumeReplayOfTheSettledResultDoesNotAmplify:244
[ERROR] ParkSettlementAuditTest.duplicateFrontendResultIsNotASecondSettlement:226
[ERROR] ParkSettlementAuditTest.fullResumeAfterParkKeepsTheAuditAtOne:257
```

### 用例清单（`ParkSettlementAuditTest`）

| # | 用例 | 钉住什么 |
|---|---|---|
| 1 | `parkedFrontendResultSettlesReadCallAndExactlyOneEnd` | **T-13 复现**：park → 回灌 `aggregate_sheet` ⇒ `readCalls=1`、end 恰 1 条、8 字段齐（`dataClass=D1/totalRows=5/sheetVer=1`、缺键渲染 `-`）、`defCode` 不进日志面、`executed=false`/`outcome=ok`/`code=-` 与内联口径一致、D1 不写台账 |
| 2 | `duplicateFrontendResultIsNotASecondSettlement` | 重复回灌 = `DUPLICATE`，计数与行数不涨 |
| 3 | `resumeReplayOfTheSettledResultDoesNotAmplify` | **`resolvePending` 复用分支跑两次**（重挂/重试）⇒ 仍 1/1 |
| 4 | `fullResumeAfterParkKeepsTheAuditAtOne` | 真 `ResumeService.resume` ⇒ `ACCEPTED` 且 1/1；二次续跑 `ALREADY_RUNNING` 仍 1/1 |
| 5 | `parkedDismissSettlesCancelledWriteOnce` | 放弃：`writePlans=1`、`writeCancelled=1`、`writeApplied=0`、end 1 条（`outcome=cancelled`/`code=FRONTEND_CANCELLED`/`executed=false`/`planId`）；重复放弃不双记 |
| 6 | `resumeExecutionSettlesBackendToolExactlyOnce` | 确认放行 → 续跑执行 `start_import`：`invocations=1`、end 1 条（`executed=true`/`ok=true`）、非表格工具无 sheet 键；二次续跑不双记 |
| 7 | `resumeExecutionFailureSettlesOnceWithTheRealOutcome` | 执行失败：end 1 条（`ok=false`/`outcome=failed`/`code=TOOL_EXECUTION_FAILED`/`executed=true`） |
| 8 | `inlineWaitStillSettlesExactlyOnce`（**非 park 对照**） | park 关闭 + 真等待线程：决策侧不发，内联循环发 1 条 ⇒ `readCalls=1`、end 1 条（无双重计数） |
| 9 | `nonParkDecisionFromAnInstanceWithoutTheWaiterDoesNotStealTheSettlement` | 跨实例非 park（本进程无等待方）：决策侧**不抢**结算权（`readCalls=0`、无 end），避免"另一实例的等待循环 + 决策侧"两条 end |

## 5. 载荷实证（`aggregate_sheet` 映射与 `tool_call_end` 审计面）

`aggregate_sheet` → `SheetSwitches.READ_TOOLS` → `SheetAudit.readCalls`（`SheetAudit.java:160-162`）。GREEN 跑捕获的**真实事件行**（`ms` 为实测，随运行浮动；其余键稳定）：

```
event=tool_call_end runId=run-park-audit sessionId=s-park-audit toolCallId=call-park-audit tool=aggregate_sheet
  kind=FRONTEND ms=11 execMs=0 skipped=false ok=true outcome=ok code=- executed=false chars=68
  dataClass=D1 sampleRows=- sampleChars=- projCols=- totalRows=5 sheetVer=1 planId=- previewLevel=-
```

```
event=tool_call_end ... tool=replace_values kind=FRONTEND ms=16 execMs=0 skipped=false ok=false
  outcome=cancelled code=FRONTEND_CANCELLED executed=false chars=74
  dataClass=D2 sampleRows=3 sampleChars=120 projCols=- totalRows=- sheetVer=- planId=plan-write-9 previewLevel=-
event=tool_call_end ... tool=start_import kind=CONFIRM ms=16 execMs=6 skipped=false ok=true outcome=ok code=- executed=true chars=35
event=tool_call_end ... tool=start_import kind=CONFIRM ms=97 execMs=63 skipped=false ok=false outcome=failed code=TOOL_EXECUTION_FAILED executed=true chars=41
```

与内联路径对照（既有 `SheetSampleTraceTest` 输出，改造后逐字不变）：键序同为
`toolCallId tool kind ms execMs skipped ok outcome code executed chars` + 8 个表格键；`defCode/page` 只进台账。

## 6. 门禁与验证

| 验证 | 命令 | 结果 |
|---|---|---|
| 聚焦（新用例） | `mvn -s maven-settings.xml -B test -Dtest=ParkSettlementAuditTest` | **9/9 绿** · `green-targeted.log` |
| 邻接① gate+conformance+tool | `-Dtest="com.example.configmgr.ai.gate.*Test,com.example.configmgr.ai.conformance.*Test,com.example.configmgr.ai.tool.*Test"` | **491/491 绿** · `green-adjacent-gate-conformance-tool.log` |
| 邻接② run+web | `-Dtest="com.example.configmgr.ai.run.*Test,com.example.configmgr.ai.web.*Test"` | **821/821 绿** · `green-adjacent-run-web.log` |
| **后端全量** | `mvn -s maven-settings.xml -B test`（无 `clean`） | **2678 / 0F / 0E / 0S，BUILD SUCCESS（7:33）** · `green-full-backend.log` |
| 文档门禁 | `node scripts/ci/check-docs.mjs` | **6/6 绿**（命名纪律扫 2212 路径 / 实扫 2209 / 豁免 3） |
| 补丁可应用性 | 干净 clone @34fd8f4 → `git apply --check` → `git apply` → 再 `git diff` | `APPLY-CHECK-OK`（EXIT=0）；往返 diff **逐字节相同**；6 件文件内容与工作副本一致（两个新文件仅换行风格差异，内容同） · `<仓外证据>/park-audit-evidence/patch-apply-check.txt` |

复跑口径：除 E2E 外，全部为进程内单测（`InMemoryRunStore` / 内存 H2），**不依赖 Redis、不起任何服务**。`TaskChildFkMigrationTest` 日志里的 `Migration ... failed!` 是该用例的负向断言（用例本身 2/2 绿）。

## 7. 交付补丁

`<仓外证据>/park-audit-fix.patch`（unified diff，LF/UTF-8，针对 `34fd8f4`，`git apply` 直接可用）：

```
 ConfirmGate.java                   |  62 +++
 SpToolCallingManager.java          |  99 +++--
 ToolCallEndTrace.java              | 123 ++++++
 SheetAudit.java                    |   6 +-
 ParkSettlementAuditTest.java       | 459 +++++++++++++++++++++
 docs/AI对话日志链路追踪手册.md      |   3 +-
 6 files changed, 708 insertions(+), 44 deletions(-)
```

应用：`git apply <仓外证据>/park-audit-fix.patch`（工作副本在 `<验证副本>/_verify-20261025-park-audit`，同分支同基线）。

> 旁证（只读核对，未改 main）：核对时 main 已推进到 `d836397`（`fix: 种子写发布配置版本 + 续跑硬否定收敛终态`，即 agent-128 的 `AiConfigSeeder` / `ResumeService` 面）。`34fd8f4..d836397` 的改动文件与本补丁 6 件**零交集**，且本补丁在 `d836397` 的干净 clone 上同样 `git apply --check` 通过 ⇒ 仍可直接落到当前 main。

## 8. 边界与残留（**本批未做，如实登记**）

1. **E2E 未跑**：施工纪律禁起服务/拓扑，`scripts/verify-e2e.ps1` 的 `AIE2E-T-13` 未实跑；闭合证据是单测级的同一条链路（park → 回灌 → 续跑 ⇒ `readCalls 1`、end 1 条）。CI 上应看到 `T-13 PASS` 与 `start 去重数 − end 去重数 ≈ 0`。
2. **确认卡"被拒绝"的 park 结算仍无 end**：`submitDecision(approved=false)` 不属本批三路径（`frontend_result` / `dismiss` / 后端执行），未补。表格工具全部 `FRONTEND`，故不影响 `readCalls`；只影响"start 必有 end"的完全性。
3. **`expireFrontend`（前端工具回执超时）与 `settleBlocked`（越 scope / 目标不成立 / 资源锁 / 认领已失）同样不补**：它们是"未执行"的终态，是否也算一条 end 属口径问题（`skipped=true` 还是 `ok=false`），按"不扩大范围"留待单独立项。
4. **跨实例非 park + 全集群无等待方**时，前端结果的那一行仍可能缺席（决策侧刻意不抢结算权）：这是本缺陷之前的存量口径，本批不为它新造跨实例幂等键。
5. **`/health.sheet.counters` 仍是进程内视图**（`SheetAudit.metrics()` 自述）；多实例下 T-13 式的"增量"断言只能落在单实例，本批不改该设计边界。
6. `SheetAudit.record` 只在 `sheetMeta` 非空时记账 —— 旧前端（不上送 `sheetMeta`）的表格工具结算仍不计 `readCalls`。这是入口白名单口径（业务值不进日志），未改。
