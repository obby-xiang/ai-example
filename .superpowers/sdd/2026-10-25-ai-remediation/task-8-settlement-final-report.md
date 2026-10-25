# Task 8 · M-2′（park 结算收口）— 施工与实测报告（2026-10-25）

**对象**：`final-integration-rereview.md` 升级为 Important 的 **M-2′**：park 开启时 ①用户拒绝确认卡、②用户取消整轮，两条真实路径都缺 `tool_call_end`（复审实读为 **0 条**）。
**基线**：主仓 `<主仓>` 当前工作区（HEAD `2eb2539` + 全部已合流候选，含 task8-final-fix）。施工在**隔离副本** `<隔离副本>/_t8-settle-m2-163`（基线快照 commit `502e0b6`），基线中本次要改的 4 个文件与主仓工作区**逐字节相同**（md5 一致）。
**主仓零改动、零提交、零推送**；主仓仅新增本报告与 `task-8-settlement-final.patch` 两个 SDD 交付件。
**范围**：只改 4 文件 —— `gate/ConfirmGate.java`、`run/ResilientChatService.java` 与两个既有测试类。未动 `RunStore`/`ResumeService`/前端/`docs`/`SheetAudit`/`ToolCallEndTrace`/`SpToolCallingManager`。
**Maven**：`<本机工具链>/JetBrains/IntelliJ IDEA/plugins/maven-plugin/lib/maven3/bin/mvn.cmd`（`-o -B -s maven-settings.xml`；未 clean、未全量、未起服务）。

---

## 0. 结论

| 面 | 判定 | 一句话 |
|---|---|---|
| **① 拒绝（park-on）** | **ADDRESSED** | `doSubmitDecision` 的 guard 胜者路径在锁外补一处 `settleParkedToolCall` ⇒ 该 toolCallId **恰 1 条** `outcome=rejected, executed=false, skipped=false`；RED 实读 0 条 |
| **② 取消（park-on）** | **ADDRESSED** | `ConfirmGate` 新增 `settleCancelledToolCalls`（复用同一结算口），`cancelTerminal` 在补帧之后调用 ⇒ 每张被取消的 CONFIRM/FRONTEND **各恰 1 条** `outcome=cancelled, code=RUN_CANCELLED`；RED 实读 0 条 |
| **park-off 原有 1 条** | **不回归** | 拒绝 1 条（内联循环自己发）、取消侧 **0 条**（结算权在等待线程）；两条都有新用例钉住 |
| **approve** | **不回归** | 决策侧显式排除放行（结算权归续跑认领胜者），既有 2 例仍断言恰 1 条 |
| **重复结算** | **由既有 `ai:observed` 仲裁** | 重复拒绝返回 `DUPLICATE` 不再结算；二次取消各条目 claim 失败 ⇒ 不增行；无新仲裁设施 |
| **决策帧 / resume / 业务副作用** | **零变化** | 新增代码在落库 + 回执帧 + 唤醒**之后**、`maybeTriggerResume` **之前**；不执行工具、不发帧、不改 guard |
| **SheetAudit 计数** | **不虚构** | 只补结算行；审计仍只认条目自带 `sheetMeta`，取消/拒绝会计数保持 0（正确口径），用例断言 `readCalls/writePlans/writeCancelled` 全 0 |

实测：RED **19 例 / 2 失败（恰为两条新缺口断言）/ 0 error**；GREEN **5 类 43 例全绿**。

---

## 1. 真实调用链与缺口（先查后改）

### 1.1 拒绝（park-on）—— 0 条

1. 批循环把整批 `tool_start` **前移**公布：`SpToolCallingManager.java:503-507`。
2. 首张确认卡 `awaitConfirm` → `ConfirmGate` 在 park 出口 `throw new RunParkedException`（`ConfirmGate.java:681`）⇒ 批循环在 `SpToolCallingManager.java:605-616` 直接穿出，**不执行** `:647` 的 `ToolCallEndTrace.emitOnce`（内联唯一发点）。
3. 用户拒绝（另一请求/另一实例）→ `ConfirmGate#doSubmitDecision`（`:1254`）写 `REJECTED` + `outcome=rejected`（`:1289-1293`），锁内 `putPending` + `confirmDecision(reject)` + `signal`（`:1302-1306`），随后 `maybeTriggerResume` —— **全程无结算发点**。
4. 续跑重建走"复用结果"分支（`ResumeService#resolvePending`，方法注明确写不是发点）⇒ 该 toolCallId **永久 0 条 end**，`ai:observed` 槽位从未被 claim。
5. 对照 park-off：`await` 正常返回 → 批循环走到 `:647` ⇒ **1 条**（并顺带 `SheetAudit.record`）。

### 1.2 取消（park-on）—— 0 条

`AiController.cancel → ResumeService.cancelStateOutcome → ResilientChatService#cancelParked`（`:1253`）→ `cancelTerminal`（`:1275`）：`cancelPendings` 收敛条目 → `emitCancelOutcomes`（`:1313`，只补 `confirm_decision(cancel)` / `frontend_tool_result`）⇒ **无 end**。`ResilientChatService` 既无 `SettlementObservationStore` 也无 `SheetAudit`（grep 0 命中），"想补也没有入口"。

### 1.3 为什么这不是"计数失真"（口径说准）

`SheetAudit#record` 要求条目带 `sheetMeta`，而 `sheetMeta` 只在 `ConfirmGate#submitFrontendResult` 的 `applySheetMeta` 路径写入（调用点 `ConfirmGate.java:1590` / `:1619`）⇒ 确认卡从来没有元数据，超时/取消的**计数为 0 是正确的**。M-2′ 丢的是"结算行（日志 + 仲裁位）"：手册"start ⇒ end"不变量被击穿，一次正常拒绝与"进程死在 start 与 end 之间"在日志里不可区分。

---

## 2. 修复（4 文件 / +270 行 / 0 删除）

### 2.1 拒绝面：`ConfirmGate#doSubmitDecision`（`ConfirmGate.java:1307-1319`）

锁外、回执帧与唤醒之后、`maybeTriggerResume` 之前：

```java
if (!approved && publishesRequestsAheadOfLoop()) {
    settleParkedToolCall(runId, pending, pending.getResultText());
}
```

- **只拒绝**：放行后结算权归续跑认领（`claimExecution`）的胜者，决策侧补会与执行侧双发（既有用例 `resumeExecutionSettlesBackendToolExactlyOnce` 即是这条口径的锁）。
- **只 park-on**：判据与前端回灌/放弃同源（`publishesRequestsAheadOfLoop()`）；park 关闭时等待线程接手，1 条仍由批循环发。
- **锁外**：`submitLock` 是实例级跨 run 锁，锁内不做 Redis 往返（与 `submitFrontendResult:1646-1654` 同一理由）。
- **幂等**：与批循环那一条（决策恰落在 `switchParkedLifecycle` 交接窗口时）由 `ai:observed` 首写者仲裁，只发一条。
- **不补 `convergeOnDecided`**（`:1412-1458`）：帧与结算的发点归 guard 持有者，补在那里会变双发（按复审 §2.3 指示）。

### 2.2 取消面：`ConfirmGate#settleCancelledToolCalls`（`:1799`，新增 public）

复用**同一个** `settleParkedToolCall` → `ToolCallEndTrace.emitForSettledEntry` → `emitOnce`（`ai:observed` HSETNX + 同一次抢占内记审计），不复制载荷构造：

- 入参 `cancelledBefore` = `cancelTerminal` 在 `cancelPendings` **之前**抓的清单；只结算"同时出现在当前 CANCELLED 集合里"的条目（防上一轮已取消的条目被再结一次，与 `emitCancelOutcomes` 的重载同形）。
- 只 `CANCELLED` 且 kind 为 `CONFIRM`/`FRONTEND`（`settlesOnPark`，`:1827`）。**`BACKEND` 必须在入口挡掉**：`ToolCallEndTrace#requiresArbitration` 对 `BACKEND` 返回 false（`ToolCallEndTrace.java:137-139`），`emitOnce` 对它**不走仲裁直接发**，由取消侧补发就会变成无条件一条。
- 不判 `woke`（实例内事实），不写计数捷径，不碰帧/判定。

### 2.3 接线：`ResilientChatService`（构造器零改动）

- `:321-326` 新增可选 setter（`@Autowired(required=false) setParkSettlement(ConfirmGate)`），沿用本类既有的"字段注入保构造器不变"先例（`contextCompactor`/`kbPromptSection`/`historyRecorder`/`messageSource` 同款理由：多处 conformance/run 用例直接 `new`）。
- `cancelTerminal` 在 `emitCancelOutcomes(...)`（`:1345` 前）之后、`done` 终帧之前调用 `settleCancelledFromPark(runId, pendingsBeforeCancel)`（`:1346`）：落库 → 帧 → **结算行** → 终帧，与决策路径同序。
- `settleCancelledFromPark`（`:1427`）：未注入（轻装配/直接 new 的单测）即空操作；失败只 WARN，不改变取消语义。
- **不成环**：`ConfirmGate` 的构造参数 = `RunStore`/`RunRegistry`/`AiProperties`/`CancellationRegistry`/`ToolActivityBeacon`/`List<FrontendToolGuard>`/`ObjectProvider<ResumeService>`/`Executor aiResumeTrigger`/`LeaseService`，**没有任何一条回到 `ResilientChatService`**；续跑方向本来就是 `ObjectProvider` 惰性解环。setter 注入发生在消费方**实例化之后**，且当前图中 `ConfirmGate` 本就晚于 `ResilientChatService` 创建（`ResumeService` 同时构造注入二者）⇒ 反方向 setter 不会构成构造环。
- 既有轻装配用例**零改签名**：`new ResilientChatService(...)` 的全部调用点原样编译通过（本轮 testCompile 447 源文件全过），未注入闸门的用例行为与改造前逐字同形。

---

## 3. RED（确定性，缺口即红）

隔离副本（基线 = 主仓 T0 工作区快照，仅加两个新用例、**不加**任何生产修复与接线）：

```
mvn -o -B -s maven-settings.xml -Dtest=ParkSettlementAuditTest,CancelLedgerConformanceTest test
→ Tests run: 19, Failures: 2, Errors: 0
```

| 用例 | 断言 | 实测 |
|---|---|---|
| `ParkSettlementAuditTest#parkedConfirmRejectSettlesExactlyOneEnd` | park-on 拒绝后该 toolCallId 恰 1 条 end | **Expected size: 1 but was: 0**（`[]`） |
| `CancelLedgerConformanceTest#parkedCancelSettlesEveryCancelledCardExactlyOnce` | park-on 取消后每张卡恰 1 条 end（本例 2 张） | **Expected size: 2 but was: 0**（`[]`） |

其余 17 例（含两条反例：park-off 拒绝恰 1 条、park-off 取消侧 0 条）在 RED 状态下即绿 ⇒ 红**只**落在两条缺口上，不是用例本身的问题。日志：`<隔离副本>/_m2-163/red-evidence.log`。

---

## 4. GREEN（含反例）

同一副本加修复后（3 个指定类 + 2 个相邻类）：

```
mvn -o -B -s maven-settings.xml -Dtest=ParkSettlementAuditTest,CancelLedgerConformanceTest,\
ResilientChatServiceMemoryWriteTest,ParkSettlementOwnershipTest,CancelDecisionFramesTest test
→ Tests run: 43, Failures: 0, Errors: 0 — BUILD SUCCESS
```

| 类 | 例数 | 说明 |
|---|---|---|
| `ParkSettlementAuditTest` | 13（11 既有 + 2 新） | 新增：park-on 拒绝恰 1 条 + 重复拒绝不增行；park-off 拒绝恰 1 条且不执行 |
| `CancelLedgerConformanceTest` | 6（4 既有 + 2 新） | 新增：park-on 取消 2 张卡各 1 条、二次取消仍 2 条、审计计数全 0；park-off 取消侧 0 条 |
| `ResilientChatServiceMemoryWriteTest` | 8 | 既有轻装配（不注入闸门）零回归 |
| `ParkSettlementOwnershipTest` | 14 | 结算所有权/仲裁契约不变 |
| `CancelDecisionFramesTest` | 2 | 取消裁决帧不变 |

**反例逐条**：

- **park-off 拒绝仍恰 1 条**：`inlineRejectStillSettlesExactlyOnce` —— 等待线程在场时决策侧不发，内联循环发；并断言 `start_import` 未被执行。
- **park-off 取消不补**：`cancelWithParkOffDoesNotSettleFromTheCancelSide` —— 取消侧 0 条（结算权在等待线程）。
- **approve 不回归**：`resumeExecutionSettlesBackendToolExactlyOnce` / `resumeExecutionFailureSettlesOnceWithTheRealOutcome` 仍断言恰 1 条（成功/失败两支），`claimExecution` 胜者口径未动。
- **重复不放大**：重复拒绝返回 `DUPLICATE` 且仍 1 条；`cancelParked` 连调两次仍 2 条（`ai:observed` 首写者仲裁）。
- **不虚构 sheetMeta**：取消用例注入真实 `SheetAudit`，断言 `readCalls/writePlans/writeCancelled` 全 0（两张卡都没有元数据面）。

日志：`<隔离副本>/_m2-163/green-evidence.log`。

---

## 5. 边界与未做（如实登记，不假装收口）

1. **`traceToolAborted` 的窄窗口（存量，本轮范围外）**：`SpToolCallingManager#traceToolAborted`（`:1051-1072`）**直发** `AiTrace.eventOn`、**不走** `ai:observed`。若取消恰好在续跑驱动的"批首卡 `checkCancelled` 抛错"处被看到，该卡已有 abort 行，本轮的取消结算还会补一条（同一 toolCallId 2 条）。窗口很窄（park 静止 → 取消的主路径无此问题，本批用例正是该主路径）；根治要把 abort 行也纳入 `emitOnce`，需改 `SpToolCallingManager`（本轮明确不许动），建议单列。
2. **`convergeOnDecided` 刻意不补**（`ConfirmGate.java:1412-1458`）：按复审 §2.3，发点归 guard 持有者。"跨实例胜者死在落库与结算之间"仍可能 0 条 —— 存量，未扩大。
3. **`expireFrontend`（前端回执超时）终态**：既有缺口不变（`settleParkedToolCall` 的方法注 `:1733-1735` 已登记的"覆盖边界"），本批不扩。
4. **计数口径**：取消/拒绝 0 条计数是**正确语义**；本轮只补"结算行"，`SheetAudit.record` 未改、未为计数构造元数据。
5. **未跑全上下文 `@SpringBootTest`**（超出约定测试预算）：Spring 装配以"依赖图论证（§2.3）+ 全量 testCompile + 单测显式接线"为准；建议合流 CI 由任一既有 `@SpringBootTest`（如 `AiToolsInvocationTest`）兜底确认无 bean 环。
6. **施工期间的树变动（证物说明）**：主仓工作区在本轮被其他 agent 持续写入（条目 56+37 → 227），且我最初的隔离目录 `<隔离副本>/_t8-settle-final` 曾出现**非我所改**的 3 个文件（`SpToolCallingManager` / `InMemoryRunStore` / `ToolIdempotencyTest`，内容为他人的 fence 写门改动）。为保纯净，最终 RED/GREEN 与补丁全部改在**新副本** `<隔离副本>/_t8-settle-m2-163` 完成，补丁只含本任务 4 文件；已用 `git apply --check --verbose` 对**主仓当前工作区**（227 项改动状态下）验证：4 文件全部通过、0 冲突。
7. 未跑全量、未 clean、未起服务、未动 `RunStore`/`ResumeService`/前端/`docs`。

---

## 6. 交付物与合流指引

| 文件 | 内容 |
|---|---|
| `task-8-settlement-final.patch` | 4 文件 / +270 / -0，基线 = 主仓当前工作区（HEAD `2eb2539` + 全部候选）。`git apply --check` 已对主仓当前工作区通过。 |
| `task-8-settlement-final-report.md` | 本报告 |

**合流注意**：本 patch 与"fence 写门（`SpToolCallingManager` / `InMemoryRunStore` / `ToolIdempotencyTest`）"等其他并发改动**无文件交集**，可独立应用；若后续有他人改动 `ConfirmGate#doSubmitDecision` 末尾或 `ResilientChatService#cancelTerminal`，只需按 §2.1/§2.3 的三行语义重放（hunk 很小）。

**提交建议**：`fix(ai): park 开启时拒绝/取消补齐 tool_call_end 结算（M-2′）`

---

## 7. 本轮命令清单（全部在隔离副本执行）

| 命令 | 结果 |
|---|---|
| `git apply --check <隔离副本>/_m2-163/m2-settlement.patch`（新副本基线） | OK |
| `mvn -o -B -s maven-settings.xml -Dtest=ParkSettlementAuditTest,CancelLedgerConformanceTest test`（**无修复**） | `Tests run: 19, Failures: 2`（两条缺口断言） |
| 同 5 类（**有修复**） | `Tests run: 43, Failures: 0, Errors: 0` — BUILD SUCCESS |
| `git diff --binary HEAD > task-8-settlement-final.patch` | 4 文件 / 365 行补丁 / +270 内容行 |
| `git apply --check --verbose task-8-settlement-final.patch`（主仓工作区，只读） | 4 文件全部 `Checking patch …`，无冲突 |
