> 来源=<仓外证据>/park-audit-review.md（2026-10-25 归档）· 仓外正式过程报告，属历史证据，不代替现行基线/决策文档（docs/02、docs/03、docs/adr/）
# park / 续跑复用绕过审计面（AIE2E-T-13）· 独立复审

- 复审对象：`<仓外证据>/park-audit-fix-report.md`、`<仓外证据>/park-audit-fix.patch`
- 基线：`34fd8f4`；交付树 `<验证副本>/_verify-20261025-park-audit`（HEAD=`34fd8f4`，工作区即补丁）
- 只读复审；**未跑任何测试/服务/拓扑**（按指示不复跑广套件）；日期 2026-10-25

## 0. 判定

| 项 | 判定 | 一句话 |
|---|---|---|
| **Spec Compliance** | **PASS（含 2 处边界残余）** | 三条路径（`frontend_result` / `dismiss` / `executeOnce`）都在所有权边界上补记且只补一次；载荷与内联逐字同源；非 park 不双记；复用分支零放大。**但**"所有权 ⇒ exactly-once"在跨实例 park 与多卡乱序两条序上不成立（见 §3，前者是本批新引入的双记） |
| **Task Quality** | **PASS（B+）** | 载荷收敛到单一构造点、文档同步、用例走真链路；扣分：发点在全局 `submitLock` 内做 Redis I/O、`emit` 的空守卫写反、续跑侧 emit 无异常兜底、测试未覆盖 timeout / 多卡 / 跨实例 |
| **Critical** | **无** | — |
| **Important** | **2** | I-1 跨实例 park 同一 `toolCallId` 双 end（新增回归）；I-2 同实例多卡乱序决策 ⇒ 该卡 0 条 end（存量，本批未覆盖） |
| **Minor** | **7** | §4 |
| **合并建议** | **建议合并** | 不阻塞 T-13 的单实例 CI 目标；多实例 park 上线前补 I-1 的所有权收紧 + I-2 用例 |

## 1. 我实读核对过的证据（不靠报告转述）

| 证据 | 核对结果 |
|---|---|
| `patch` vs 交付树 | `git diff 34fd8f4` 与补丁**除 `index` 行外逐字节相同**（`PATCH==WORKTREE`） |
| `git apply --check` | 在 `34fd8f4` 与当前 clone HEAD `04facb4`（提交信息与报告 §7 提到的 `d836397` 相同；不同 clone 的哈希不可直接比对）上均 `EXIT=0` |
| RED | `red-targeted.log`：`Tests run: 8, Failures: 7`，7 个失败用例名与报告 §4 列出的完全一致（行号 203/226/244/257/278/304/325） |
| GREEN | `green-targeted.log`：`Tests run: 9, Failures: 0` |
| 邻接 | `491` / `821` 两行汇总在日志中确有 `Failures: 0, Errors: 0` |
| 后端全量 | `green-full-backend.log`：`Tests run: 2678, Failures: 0, Errors: 0, Skipped: 0`，`BUILD SUCCESS 07:33`；**日志内含 `ParkSettlementAuditTest … 9/9`** ⇒ 全量确实跑在打补丁后的树上，不是基线 |
| 文档门禁 | 我在交付树上重跑 `node scripts/ci/check-docs.mjs`：**6/6 绿**（2212 路径 / 实扫 2209 / 豁免 3） |
| 载荷样例 | GREEN 日志中的 `aggregate_sheet` 行确有 12 基础键 + `dataClass/totalRows/sheetVer` 等 8 表格键，`defCode` 不在日志面 |

## 2. 逐条 requirement 核对

### 2.1 三处发点都在所有权边界上，且都只在本该发的分支里

| 路径 | 发点 | 是否只在胜者分支 |
|---|---|---|
| `frontend_result` | `ConfirmGate.java:1622`（`doSubmitFrontendResult` 内、`tryRecordDecision` 的 `won()` 之后、且已过 `PENDING` 复核） | ✓ 重复回灌在 `:1546`（抢 guard 前）与 `:1575`（抢到后复读）返回 `DUPLICATE`；`convergeOnDecided` 路径不经过此处 |
| `dismiss` | `ConfirmGate.java:1671` | ✓ `claim != null && !won()` 在 `:1653-1655` 提前返回 |
| 后端执行 | `SpToolCallingManager.java:1836` 传 `settleObservation=true` → `claimAndExecute` 认领胜者 `:1519` / `settleToolTimeout :1633` | ✓ 认领失败（`LOST`/`STALE_FENCE`/台账重放）都在 `:1415-1440` 提前返回；内联路径 `executeOnce(..., false)`（`:1343`）不发 |

- `executeOnce` 的重载方向正确：既有的 4 参入口硬编码 `false`（`:1343`），仅 `resolvePending` 走 5 参 `true`（`:1836`）。`resolvePending` 之外的 `executeOnce` 调用（内联循环 `:585`、同轮已批准的确认卡 `:1293`）全部落到 `false`，不会与批循环的 `:634` 重复。
- 内联批循环 `:634` 改用 `ToolCallEndTrace.emit`：`settled=source`、身份取 `pending`、`totalToolMs`/`execMsOf(source, totalToolMs)` 与改造前逐字一致（我按基线 `:628-666` 逐行对照过）。

### 2.2 exactly-once 的推理是否成立（单卡 park 场景）

读 `arm`(`:413`) → `publishRequestsForParkedBatch`(`:499`) → `await` 的 `reserveRequestSlot` + `switchParkedLifecycle`(`:794`)/`clearWaiterLatches`(`:812`)，与 `submitFrontendResult` 的 `synchronized (submitLock)`：

- 决策写在锁内先 `putPending` 再 `signal`；park 退出在**同一把锁**内复读 `resolved()`，未决才还租 + 摘闸门。两个出口互斥 ⇒ 单卡下"`woke=false` ⇔ 本进程不再有内联结算"成立，且 `woke=true` 时该闸门正是即将被 `await` 消费的那把 ⇒ 恰好一条。**结论：单卡场景推理正确**，测试 1/2/3/4/5 也从真实链路证了这一点。
- 非 park 不双记：条件里带 `publishesRequestsAheadOfLoop()`（= `park.enabled`），测试 8（真等待线程）与 9（跨实例无等待方，决策侧刻意不抢）都钉住了。
- 不放大：`resolvePending` 的"复用结果"分支**只有注释变化**，测试 3（连跑两次）与测试 4（真 `ResumeService`，二次续跑 `ALREADY_RUNNING`）证 `readCalls`/行数停 1。

### 2.3 载荷一致性

逐字段对照基线 `SpToolCallingManager:628-666` 与新 `ToolCallEndTrace.pairs`(`:78`)：键序 `toolCallId tool kind ms execMs skipped ok outcome code executed chars` + 8 个表格键，取值来源（`source/pending` → `settled/toolCallId`）一一对应；`skipped` 恒 `false`、`chars=text.length()`、`sheetMeta` 非空才追加、非表格工具一个 sheet 键都不写 —— **一致**。`defCode/page` 仍只进台账（`SheetAudit.LOG_FIELDS = ALLOWED_FIELDS[0..8)`）。

### 2.4 业务语义与敏感数据

- 决策/放弃/认领的任何判定都未改；唯一新增副作用是观测面，且 gate 侧包在 `try/catch(RuntimeException) → WARN`（`:1706-1716`）。
- 日志只记字符数 + 白名单元数据；用例断言 `doesNotContain("defCode=")`，D1 不写台账（`SheetAudit.record` 仅 `D2` 追加）。**无敏感数据外泄**。

## 3. Important

> 两条都落在同一个"批窗口"里：`publishRequestsForParkedBatch`(`:499`) 已经把本批卡公布给用户，而内联循环还没走到该卡的 `await`（期间可能在跑前序后端工具）。窗口越长（混合批次），越容易命中。两处都只影响观测面（end 行数 / `readCalls`），不改变任何业务判定。

### I-1 跨实例 park：同一 `toolCallId` 可能出现**两条** `tool_call_end`（本批新增回归）

- 位置：`backend/src/main/java/com/example/configmgr/ai/gate/ConfirmGate.java:1622`（判据只用本进程 `woke`）+ `backend/src/main/java/com/example/configmgr/ai/gate/SpToolCallingManager.java:634`（内联循环仍会发）。
- 触发序列（park 开启、多实例、混合批次 `[backend_X, frontend_C]`）：
  1. 实例 A 在 `:499` 把 C 的请求帧**整批前移**公布，随后仍在循环里跑 `backend_X`（秒级窗口）；
  2. 用户回填 C，POST 落到实例 B（B 没有本地 latch）⇒ `woke=false` 且 park 开启 ⇒ B 在 `:1622` 发一条 end（D2 时还多记一次台账）；
  3. A 跑完 `backend_X` 进入 `awaitFrontend(C)`，`reserveRequestSlot` 看到已决 ⇒ `consumeDecided` 返回既有条目，循环继续走到 `:634` **再发一条**。
- 结果：同一个 `toolCallId` 两条 end、`readCalls` 记 2。这是本批**新引入**的双记（改造前 B 不发、A 发 1 条）。窄窗口，但混合批次里窗口 = 前序后端工具的执行时长，不是微秒级；负载均衡下 POST 落点随机，属可复现面。
- 为什么测试没抓到：所有用例都是单实例装配；测试 9 只钉了**非 park** 的反方向。
- 最小收口方向（不扩大范围）：把"是否发"绑到**同一份所有权证据**——即 `triggerResume` 抢到的续跑租约（`:1783-1809`）。park 之后租约是空的 ⇒ 决策方抢到 ⇒ 它发；park 之前原实例仍持等待租约 ⇒ 决策方 `LEASE_HELD_ELSEWHERE` ⇒ 它不发（由原循环发）。实现上把 `settleParkedToolCall` 挪到 `maybeTriggerResume` 之后、按 `ResumeTrigger` 结果决定即可；无租约降级装配（单实例）保持现状。

### I-2 同实例多卡乱序决策：该卡可能**一条 end 都没有**（存量缺口，本批未覆盖）

- 位置：`ConfirmGate.java:1622`（`woke=true` 被当成"内联一定会发"）+ `ConfirmGate.java:812`（park 退出清掉**全部** latch）+ `SpToolCallingManager.java:463`（整批 `arm` 预登记）。
- 触发序列（park 开启、同实例、批次 `[frontend_A, frontend_B]`）：
  1. `arm` 给 A、B 都预登记闸门，`:499` 把两张卡的请求帧都公布；
  2. 用户先回填 **B**（C1 明确允许多卡各自决策）⇒ 命中 B 的预登记 latch ⇒ `woke=true` ⇒ 决策侧**不发**，`maybeTriggerResume` 也按 `WOKE_IN_PROCESS_GATE` 跳过；
  3. 循环走到 A 仍 PENDING ⇒ `switchParkedLifecycle` 未决 ⇒ `clearWaiterLatches` 把 B 那把**已被 signal、尚无人 await** 的闸门一并摘掉 → 抛 `RunParkedException`，循环再也不会走到 B；
  4. A 稍后被决策触发续跑，B 走 `resolvePending` 的复用分支（按设计不补）⇒ **B 永久 0 条 end**（有 start 没有 end，正是本批要消灭的形态）。
- 性质：与改造前的缺口同形，**不是回归**；但报告 §2 的"所有权 ⇒ 挂起一次一条 end"在这条序上过强，文档与注释的口径需要收窄。
- 方向：park 退出时把"已 signal 但无人 await"的预登记键交回调用方补结算（或记成待结算集，由续跑入口一次性补发），并加一条真多卡批次用例。

## 4. Minor

| # | 位置 | 问题 | 建议 |
|---|---|---|---|
| M-1 | `ConfirmGate.java:1622/1671`（emit 在 `synchronized (submitLock)` 内） | 锁内多了 `snapshotSessionId`(:352 的 `store.get`) 与 D2 的台账写（`SheetAudit.appendLedger`）两次 Redis 往返。`submitLock` 是**实例全局**的决策/挂起锁，所有 run 的决策都在它后面排队 | 把 `settleParkedToolCall` 移到锁外（`woke` 已捕获，无顺序要求） |
| M-2 | `ToolCallEndTrace.java:44` | `if (audit == null \|\| settled == null)` 在 `pairs(settled, …)` **之后**，`settled == null` 永远走不到（先 NPE）；`emitForSettledEntry`(:68) 反而没有 null 守卫 | 前置守卫或删掉死分支 |
| M-3 | `SpToolCallingManager.java:1519/1633` | 续跑侧两处 emit 无 `try/catch`（gate 侧有）。若观测面抛异常，会把"工具已成功结算"变成续跑异常 | 与 gate 同体例包一层 WARN |
| M-4 | `SpToolCallingManager.java:1911` | 每次结算多一次 `store.get(runId)`；快照缺失时该行的 session 字段回落 MDC，而续跑线程/决策线程的 MDC 未必是本 run（`PendingToolCall` 本身不带 sessionId，没有现成锚点） | 可接受（注释已声明）；若要更稳，由续跑入口把已绑定的 sessionId 显式传下来 |
| M-5 | `SheetAudit.java:21-24`（类注）、`ToolCallEndTrace` 类注 | "唯一载荷构造点"略过头：`traceToolSkipped`/`traceToolAborted`(`SpToolCallingManager:1025/1038`) 仍各自手拼 `tool_call_end`（键集不同、不带 sheet 键，属另一族行） | 注释限定为"**已结算**行的唯一构造点" |
| M-6 | `ToolCallEndTrace.java:68-76` + 手册 §2.5 | park 的 `ms/execMs` 与内联不是同一算式（内联是 `total − (resolvedAt − reqAt)`；park 是 `resolvedAt − claimedAt`，且 `resolvedAt` 取 `executedAtMs`）。前端工具 `execMs=0` 与内联近似等价；`CONFIRM` 会少算 `claim→exec` 与 `start→req` 的空隙（毫秒级）。文档已如实声明，**不算口径分叉**，但报告"逐字同口径"的说法宜降级为"字段与量纲同口径" | 文档措辞微调 |
| M-7 | `ParkSettlementAuditTest` | 测试强度/覆盖：① 用例 8 未断言 `submission.woke()==true`、也未断言该行 `outcome=ok`（理论上一行 timeout 结算也能满足 1/1 断言）；② `settleToolTimeout(..., settleObservation=true)` 无用例；③ 无多卡批次、无跨实例（两套 gate/manager）用例，而 §3 的两处边界恰好只在这两种装配下暴露；④ gate 的续跑派发被 mock 成 `NOT_FOUND`（隔离得当，但 lease/`triggerResume` 所有权路径完全没有被覆盖） | 至少补 timeout 用例与"两卡乱序"用例 |

## 5. 范围外（按要求只登记，不要求本批扩）

`submitDecision(approved=false)` 的拒绝终态、`expireFrontend`（前端回执超时）、`settleBlocked`（越 scope / 目标不成立 / 资源锁 / 认领已失）三类"未执行"终态仍无 park 侧 end —— 属口径问题（`skipped=true` 还是 `ok=false`），与本批三路径正交；跨实例**非 park** 且全集群无等待方的前端结果行仍会缺席（存量，测试 9 已如实钉住）；`/health.sheet.counters` 仍是进程内视图；旧前端不送 `sheetMeta` 时仍不计 `readCalls`；`AIE2E-T-13` 未实跑（只有单测级同链路证据）。以上均接受为已知限制，**不作为阻塞项**。

## 6. 合并建议

**建议合并（Approve）**：三条目标路径的行为、载荷一致性、非 park 零双记、复用零放大都有代码级 + 真链路用例支撑，全量 2678 / 邻接 491+821 / 文档 6/6 与交付树一致，补丁可干净应用。T-13 的 CI 目标（单实例 park ⇒ `readCalls` 0→1、end 恰 1 条）成立。

合并前**不要求**修 I-1/I-2，但建议：

1. 在报告 §8 与手册 §2.5 里把"所有权 ⇒ exactly-once"的适用范围写明为"单卡 / 单实例"，并把 I-1（跨实例 park 双记）与 I-2（多卡乱序 0 条）登记为残留；
2. 多实例 park 放量前，用 I-1 给出的"按续跑租约胜者决定发不发"收紧发点，并补两条用例（跨实例 park、双卡乱序）；
3. 顺手把 M-1（锁内 Redis）与 M-2/M-3（守卫与兜底）清掉，改动量都在 10 行内。
