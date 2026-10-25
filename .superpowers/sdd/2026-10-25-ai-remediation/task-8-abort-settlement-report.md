# Task 8 · M-2′ 残余（abort × 取消双发）— 施工与实测报告（2026-10-25）

**对象**：`task-8-settlement-final-report.md` §5.1 登记的残余 —— `SpToolCallingManager#traceToolAborted`
直发 `tool_call_end`（不占 `ai:observed`），与 `cancelTerminal` 新增的 `settleCancelledFromPark`
可能对**同一 toolCallId** 各发一条，把"恰好一次"变成 2 条。
**基线**：主仓 `<主仓>` **当前工作区**（HEAD `2eb2539` + 已合入的
`task-8-settlement-final.patch` 与 `task-8-idempotency-contract.patch` 等全部候选）。施工在**隔离副本**
`<隔离副本>/_m2abort-163`（基线快照 commit `547b0e6`；`core.autocrlf=true`，与主仓同配置，基线 4 个相关文件与
主仓工作区**逐字节相同**）。**主仓零改动、零提交、零推送**；主仓仅新增本报告与
`task-8-abort-settlement.patch`。
**Maven**：`<本机工具链>/JetBrains/IntelliJ IDEA/plugins/maven-plugin/lib/maven3/bin/mvn.cmd`
（`-o -B -s maven-settings.xml`；未 clean、未全量、未起服务）。

---

## 0. 结论

| 项 | 判定 | 一句话 |
|---|---|---|
| **残余是否真实存在** | **是（已确定性复现）** | `await` 在 park 出口看到取消 ⇒ 条目转 `CANCELLED` ⇒ 批循环复查处 `traceToolAborted` 发一行 ⇒ `cancelParked/cancelTerminal` 再结一行；实测同一 toolCallId **2 行** |
| **修复** | **已完成（最小）** | abort 行接入既有 `ai:observed` 仲裁（新增 `ToolCallEndTrace#emitCustomOnce`，复用 `requiresArbitration` + `claim`/`release`）；**载荷字段逐字不变** |
| **park-off** | **不回归** | 取消侧本就不结算；abort 行仍恰 1 条（新增钉住用例） |
| **普通 inline（非取消）** | **不变** | 非取消的 abort（执行段异常）在条目未决时**不占位**、照旧直发；已终态时走同一仲裁（此前也无第二个发点） |
| **BACKEND** | **不重复、不静默** | `requiresArbitration(BACKEND)=false` ⇒ abort 直发、不占位；取消侧本就只结 CONFIRM/FRONTEND（新增钉住用例） |
| **取消侧与 abort 侧** | **不再双发** | 谁先到谁占位：abort 先占 ⇒ 取消收尾的 `emitOnce` 抢位失败、一条不发 |
| **注释过时** | **已更正（仅注释）** | `ConfirmGate#settleParkedToolCall` 的"expireFrontend 超时仍不发结算行 —— 存量缺口"已过时（超时在 `scheduleParkedFrontendExpiry` 里结算一行），改为准确口径 |

实测：**RED** 同一 toolCallId `Expected size: 1 but was: 2`（两行原文见 §2）；**GREEN** 4 类 41 例全绿。

---

## 1. 真实调用链（先读后改：我上一轮对触发点的描述需要修正）

### 1.1 可达的双发链（park 开启）

1. 批循环先给整批 `tool_start`（`SpToolCallingManager.java:503-507`），并已把请求帧整批公布。
2. 逐条执行前先查一次取消（`:528` `checkCancelled`）—— 此处**假如此刻已取消，则直接穿出、不写 abort 行**
   （该检查在 try 之外）。这正是我上一轮报告 §5.1 措辞不准的地方：`checkCancelled` 本身不发 abort 行。
3. 取消落在"开始执行之后、park 之前"：`ConfirmGate#await` 在 park 出口先判取消
   （`ConfirmGate.java:630-632`）并走 `cancel(...)`（`:1020-1046`）—— **条目落 `CANCELLED` + 补
   `confirm_decision(cancel)` 帧，然后正常返回**（不抛异常）。
4. 回到批循环：执行后复查处 `checkCancelled`（`SpToolCallingManager.java:619-626`）看到 CANCELLED ⇒
   `traceToolAborted(...)` 发一条 `tool_call_end`（`cancelled=true, outcome=cancelled, executed=false`），
   再抛 `StreamViolationException`。
5. `ResilientChatService#cancelTerminal`（取消收尾）→ `cancelPendings`（该条已是 CANCELLED）→
   `emitCancelOutcomes`（帧）→ `settleCancelledFromPark` → `ConfirmGate#settleCancelledToolCalls` 把它算进
   "取消前清单 ∩ 当前 CANCELLED" ⇒ `ToolCallEndTrace.emitForSettledEntry` 又发一条
   （`outcome=cancelled, chars=18`）。
6. 结果：同一 toolCallId **两条 end**，`ai:observed` 只被取消侧 claim 过一次（abort 侧从未 claim）。

### 1.2 park 关闭时的同窗口

走的是等待循环里的取消检查（`ConfirmGate.java:696-701`）⇒ 条目同样转 CANCELLED 并产生 abort 行；
但取消侧在 park 关闭时**不结算**（`settleCancelledToolCalls` 的 park 判据）⇒ 只有 1 条（本批新增反例钉住）。

### 1.3 BACKEND

`ToolCallEndTrace#requiresArbitration` 对 `BACKEND` 返回 false ⇒ abort 行直发、不占位（**保持不变**）；
取消侧的 `settleCancelledToolCalls` 只收 `CONFIRM/FRONTEND` ⇒ BACKEND 不会被补第二条。

---

## 2. RED（确定性，真实两行）

隔离副本 `<隔离副本>/_m2abort-163-red`（基线 = 主仓当前工作区快照，**只有**新增用例、无生产改动）：

```
mvn -o -B -s maven-settings.xml -Dtest=CancelLedgerConformanceTest test
→ Tests run: 9, Failures: 1, Errors: 0
→ cancelSeenAtTheParkExitDoesNotDoubleSettle:320
   [同一条调用不得被 abort 与取消收尾各结算一次] Expected size: 1 but was: 2 in:
   ["event=tool_call_end runId=run-cancel-abort … toolCallId=c-abort-frontier tool=start_import kind=CONFIRM
     ms=0 execMs=0 skipped=false ok=false outcome=cancelled code=RUN_CANCELLED executed=false chars=0 cancelled=true",
    "event=tool_call_end runId=run-cancel-abort … toolCallId=c-abort-frontier tool=start_import kind=CONFIRM
     ms=0 execMs=0 skipped=false ok=false outcome=cancelled code=RUN_CANCELLED executed=false chars=18"]
```

- 第 1 行 = abort 发点（多 `cancelled=true`、`chars=0`）；第 2 行 = 取消收尾（`chars=18`，无 `cancelled`）。
- 复现是**确定性的、无线程**：`cancelState` 第 1 次（批首检查）= `NOT_CANCELLED`，
  第 2 次起（`await` 的 park 出口）= `CANCELLED` —— 精确对应"取消恰好落在两者之间"。
- 同批其余 8 例（含 park-off 反例、BACKEND 反例、既有 4 例）在 RED 状态即绿 ⇒ 红只落在该缺口。
- 日志：`<隔离副本>/_m2abort-163/red-final.log`。

---

## 3. 修复（4 文件 / +228 / −9；其中 ConfirmGate 为注释）

### 3.1 `ToolCallEndTrace#emitCustomOnce`（`ToolCallEndTrace.java:104`，新增 package-private）

abort 行的字段集合与常规结算不同（多 `cancelled`、`code` 取异常分类、`ms/execMs` 是"开始→异常"），
塞不进 `emitOnce` 的载荷构造；但"这条调用结算过没有"必须**同一套仲裁**。故新增一个只负责仲裁的包装：

```java
static void emitCustomOnce(SettlementObservationStore observations, String runId, String toolCallId,
        String kind, Runnable payload) {
    if (!requiresArbitration(kind) || observations == null) { payload.run(); return; }   // BACKEND / 轻装配：直发
    if (!observations.claim(runId, toolCallId)) { return; }                              // 别人已结算
    try { payload.run(); } catch (RuntimeException ex) { observations.release(runId, toolCallId); throw ex; }
}
```

与 `emitOnce` 的失败方向逐字同源（写行失败 ⇒ 释放占位，"宁重不宁缺"）。

### 3.2 `SpToolCallingManager#traceToolAborted`（`:1062-1096`）

载荷构造**一个字没改**（同样的键序、同样的取值），只是包进上面的仲裁口：

- **条目已终态**（本例 `CANCELLED`）⇒ 走 `emitCustomOnce`：抢 `ai:observed` 首写者。取消收尾随后的
  `emitForSettledEntry` 抢不到位 ⇒ 一条不发；若取消侧先到，abort 行则不发。
- **条目仍 `PENDING`** ⇒ **不占位**、照旧直发：此时 abort 行只是"本次尝试没走完"的诊断，没有结算任何结论；
  占位会把该条之后真正执行成功的结算行挡掉（把"多一行"变成"少一行"）。这是本轮唯一的语义收紧点，
  已在方法注里写清。
- `BACKEND` ⇒ `requiresArbitration=false` ⇒ 直发（与改造前逐字相同）。

### 3.3 为什么改到 `SpToolCallingManager` 之外（按要求说明）

- **`ToolCallEndTrace`**：仲裁的**唯一**判据（`requiresArbitration` + `claim`/`release` 的失败方向）在这
  个类里。若在 `SpToolCallingManager` 里再写一份 claim/release，就是第二条仲裁口径，与"ToolCallEndTrace
  是结算发点唯一入口"的设计前提相悖。改动仅 38 行、纯新增、无既有方法签名变化。
- **`ConfirmGate`**：**只改注释**（见 §5），不涉及行为。

### 3.4 没有做的事

- 未动 `RunStore`/`ResumeService`/前端/`docs`；未新增仲裁设施、未改 `ai:observed` 键语义。
- 未给 `:528`（批首 `checkCancelled`）补 abort 行 —— 那条路径本来就没有 `tool_start` 之后的 end 语义
  （工具尚未开始），补了会与"成对性只对真实执行成立"的口径冲突；属存量形态，见 §6。

---

## 4. GREEN（含反例）

```
mvn -o -B -s maven-settings.xml -Dtest=CancelLedgerConformanceTest,ParkSettlementAuditTest,\
AiToolCallLogTraceTest,ParkSettlementOwnershipTest test
→ Tests run: 41, Failures: 0, Errors: 0 — BUILD SUCCESS
```

| 类 | 例数 | 说明 |
|---|---|---|
| `CancelLedgerConformanceTest` | 9（6 既有 + 3 新） | 新：`cancelSeenAtTheParkExitDoesNotDoubleSettle`（本缺陷）、`abortLineStaysSingleWhenParkIsOff`（park-off 反例）、`cancelSideNeverSettlesBackendEntries`（BACKEND 反例） |
| `ParkSettlementAuditTest` | 13 | 结算所有权/审计面不回归（含 approve 恰 1 条两支、前端超时恰 1 条、多卡到期各 1 条） |
| `AiToolCallLogTraceTest`（相邻） | 5 | 日志链路（`tool_call_start/end` 字段面）不回归 |
| `ParkSettlementOwnershipTest`（相邻） | 14 | 仲裁契约（首写者/释放/重复）不回归 |

**反例逐条**：

- **park-off**：同一窗口下 abort 行仍恰 1 条，取消收尾后仍 1 条（`abortLineStaysSingleWhenParkIsOff`）。
- **BACKEND**：取消收尾对 `CANCELLED` 的 BACKEND 条目 **0 行**，同轮 CONFIRM 照常 1 行
  （`cancelSideNeverSettlesBackendEntries`）。
- **既有取消回归未删改**：上一轮 6 例（含"重复取消不放大""park-off 取消侧不补""无 sheetMeta 不计数"）
  原样保留并通过。
- **载荷不变**：abort 行仍是 14 键同序（`toolCallId/tool/kind/ms/execMs/skipped/ok/outcome/code/executed/chars/cancelled`），
  `AiToolCallLogTraceTest` 的字段断言全绿。

日志：`<隔离副本>/_m2abort-163/green-abort3.log`。

---

## 5. 过时注释的更正（仅 `ConfirmGate` 注释，不动其它文档）

`ConfirmGate#settleParkedToolCall` 的旧文（本轮之前的原文）：

> 覆盖边界（如实登记）：上述三类发点覆盖"已决/已执行"的结算；park 之后走 `expireFrontend`（前端回执超时）
> 终态的卡片仍不发结算行 —— 存量缺口，本批不扩。

**该语义已过时**：park 后的到期收敛（`scheduleParkedFrontendExpiry`，`ConfirmGate.java:1108`）在
`expireFrontend` 之后**立即** `settleParkedToolCall(runId, expired, …)`，`ParkSettlementAuditTest#parkedFrontendTimeoutEmitsExactlyOneSettlement`
也钉住了"超时恰一行"。改为准确口径（`ConfirmGate.java:1734-1739`）：五类发点（已决/放弃/取消/前端到期/执行认领）
+ 两条仍然存在的边界（`convergeOnDecided` 刻意不补；非 park 且全集群无等待方时仍缺席）。
同一段里"三类发点"的枚举也一并更新为当前实际发点（含取消与 abort 结算）。

---

## 6. 边界与未做（如实登记）

1. **批首 `checkCancelled`（`SpToolCallingManager.java:528`）本身不发 abort 行**：该检查在 try 之外，
   工具尚未开始（没有 `tool_start` 的呼应义务），取消后由 `cancelTerminal` 的取消结算补那一行 ——
   **本轮修复前后都是 1 行**（本缺陷不在这条路径上）。我上一轮报告 §5.1 把触发点写成这条检查，**措辞有误**，
   以此报告为准。
2. **未决条目的 abort 行不占仲裁位**（§3.2）：若出现"未决 abort → 之后该条又被取消并结算"，
   仍可能 2 行。可达性仅限基础设施异常（读/写失败）导致的未决 abort；本轮不为此引入"未决也占位"
   —— 那会把"之后执行成功的结算"挡成 0 行，方向更差。已写进方法注。
3. **`convergeOnDecided` 无结算**（决策 guard 已被别的写者持有）：发点归 guard 持有者；
   胜者死在"落库 → 结算"之间仍是 0 行（存量，未扩大）。
4. **非 park 且全集群无等待方**（进程死在等待中、尚未续跑）：结算行仍缺席（存量口径）。
5. **未跑全量、未 clean、未起服务**；未跑全上下文 `@SpringBootTest`（超出约定测试预算）。
   本次改动不新增 Spring bean、不改注入方向，装配面与上一轮一致。
6. **并发树说明**：施工期间主仓仍被其他 agent 持续写入（本轮开始时工作区 230 项改动）。隔离副本基线以
   `git diff HEAD`（含 staged 新增）落盘，四个被改文件与主仓工作区逐字节一致；补丁已对**主仓当前工作区**
   做 `git apply --check`（4 文件全部通过）。

---

## 7. 交付物与命令

| 文件 | 内容 |
|---|---|
| `task-8-abort-settlement.patch` | 4 文件 / +228 / −9（增量，基线 = 主仓当前工作区） |
| `task-8-abort-settlement-report.md` | 本报告 |

| 命令（隔离副本） | 结果 |
|---|---|
| `mvn … -Dtest=CancelLedgerConformanceTest test`（**仅新用例，无修复**，`_m2abort-163-red`） | `Tests run: 9, Failures: 1` —— 同一 toolCallId **2 行** |
| 同 4 类（**有修复**） | `Tests run: 41, Failures: 0` — BUILD SUCCESS |
| `git diff --binary HEAD > task-8-abort-settlement.patch` | 319 行补丁 / 4 文件 |
| `git apply --check --verbose task-8-abort-settlement.patch`（主仓工作区，只读） | 4 文件全部通过 |

**提交建议**：`fix(ai): abort 结算接入 ai:observed 仲裁，消除取消时的双 end（M-2′ 残余）`
