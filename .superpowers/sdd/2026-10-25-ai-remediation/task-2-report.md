# Task 2 报告 · 多实例结算审计"恰好一次"

**结论：DONE**（聚焦范围全绿；补丁对当前 main 可干净应用；键生命周期契约已就绪待 Task 3 对齐）

- 基线 = **当前 main `d5916df`**（`carry-over: 续跑三态 + 种子并发 no-op`，含 34fd8f4 + config 批 + park audit 第一批）
  —— 核对时 main 已从 `d836397` → `8620ee6`（baseline，含上一批 park audit）→ `d5916df`，本报告全部改动**基于 d5916df 重新构建**，未回退任何既有提交。
- 交付树：`<隔离副本>/_verify-20261025-park-audit`（clone 当前 main + 本批 delta）
- 补丁：`<仓外证据>/park-audit-fix2.patch`（8 文件 **+618 / −37**，`git apply --check` 于干净 `d5916df` 通过、往返逐字节相同）
- 未改 main、未提交、未加依赖、未派子代理；`RunStore` / `SessionKeyRegistry` / 迁移脚本**一行未动**（Task 3 owns）
- 测试口径：**只跑聚焦 + 4 个相邻类**（27 例），**未跑全量**（按 plan cap）

## 1. 两条缺陷（复审 I-1 / I-2）与本批修法

| # | 序列 | 旧行为（RED 实测） | 根因 | 修法 |
|---|---|---|---|---|
| I-1 | 批 `[backend_X(慢), frontend_A]`：A 把整批请求帧前移公布后仍在跑 backend_X；回灌落到**另一实例** B（B 无本地闸门 ⇒ `woke=false`） | **同一 `toolCallId` 2 条 `tool_call_end`、`readCalls=2`** | 决策侧以"本进程 `woke=false`"为发点判据；源实例随后在批循环里再发一条 | 发点改为**持久首写者仲裁**：决策侧不再看 `woke`；内联批循环与决策侧都先抢 `ai:observed:<runId>` |
| I-2 | 批 `[backend_X(慢), frontend_A, frontend_B]`：先回灌 B（命中 `arm` 预登记闸门 ⇒ `woke=true`），随后循环在 A 处 park 并 `clearWaiterLatches` 摘掉全部闸门 | **B 永久 0 条 `tool_call_end`、`readCalls=0`** | `woke=true` 被当成"内联一定会发"；park 退出把"已唤醒但无人 await"的闸门一并摘掉 | 同上：决策侧在 `park` 开启时**一律**参与发点，由仲裁位保证只发一条 |

**决策**：优先评估了"续跑/租约所有权"能否收口 —— **不能**：
① `ai:lease:run:` 在首轮批循环里**不被持有**（只有 `ConfirmGate.await` / `ResumeService` / `triggerResume` 抢租），I-1 的窗口恰好是"无租约持有者"，租约判据在那里恒为"可抢"；
② I-2 的 `woke=true` 分支在 `triggerResume` 之前就短路，租约根本没被查询。
故按 brief 采用**最小持久幂等标记**，且落在**独立 store**（不动 `RunStore`）。

## 2. RED（先证两序，后改生产）

RED 基底 = 当前 main `d5916df` + 仅"注入缝"（`ConfirmGate#setSettlementObservationStore` / `SpToolCallingManager#setSettlementObservationStore`，二者不被调用）+ 新测试类。生产行为 = 复审描述的两条缺陷形态。

命令（IDEA 内置 Maven，JDK 21，无 `clean`）：

```
cd <tree>/backend
mvn -s maven-settings.xml -B test -Dtest=ParkSettlementOwnershipTest -DfailIfNoTests=false
```

结果（`<仓外证据>/park-audit-fix2-evidence/red-ownership.log`）：

```
[ERROR] Tests run: 4, Failures: 2, Errors: 0, Skipped: 0
[ERROR]   ...crossInstanceDecisionDoesNotDoubleSettleWhenTheOriginLoopSettlesLater:224
          [readCalls 不得被两个执行体各记一次]
          {"disabledRejects"=0L, "readCalls"=2L, ... }   (expected readCalls=1)
[ERROR]   ...multiCardAnsweredOutOfOrderStillSettlesTheAnsweredCardOnce:253
          [已答复的卡必须有且只有一条 end（park 摘闸门后无人再结算它）]
          Expected size: 1 but was: 0 in: []
```

I-1 的日志面同时被抓到两条同 `toolCallId` 的 `tool_call_end`（含相同的 `dataClass=D1 totalRows=5 sheetVer=1`），与 `readCalls=2` 互为印证；另 2 例（观测位原语/键生命周期）在 RED 基底上即绿（它们是"缝"的性质，不是缺陷）。

### 确定性怎么保证（不是靠 sleep/竞态）

新增受控替身 `FakeBlockingTool`（`@Tool blocking_tool`，`entered` / `release` 两把 `CountDownLatch`）：两条序的"窗口"= 前序 backend 工具的执行时长，用例用 latch 把顺序钉死为
`进入工具体 → （跨实例/第二张卡回灌）→ 放行 → 源循环继续/ park`。`release` 在 `finally` 兜底放行，避免卡住执行超时池线程。

## 3. 修法（生产 4 文件）

| 文件 | 变更 |
|---|---|
| `ai/observe/SettlementObservationStore.java`（**新增**，112 行） | 独立观测位：`claim(runId, toolCallId)` = `HSETNX` 首写者胜（Redis 面）/ 进程内表（无 Redis 时）；首写即置 `session-ttl`；Redis 异常 **fail-open**。**不参与任何判定**，与 `ai:decided:` 分键存放 |
| `ai/gate/ToolCallEndTrace.java` | 新增 `emitOnce(observations, …)`（`:54`）：`requiresArbitration(kind)`（`CONFIRM`/`FRONTEND`，含 `kind=null` 旧数据）时先 `claim`，抢不到**直接返回**（既不发日志也不记审计）；`emitForSettledEntry` 改走同一入口（`:97`）；`emit` 降级为私有（唯一发点不可绕过）。载荷字段/键序/取值**逐字未变** |
| `ai/gate/ConfirmGate.java` | 决策/放弃两处发点去掉 `!woke`（`:1636` / `:1685`），保留 `park 开启` 前提；方法注改写为"为什么不用 `woke`"（含 I-1/I-2 两序）；新增可选注入 `setSettlementObservationStore`（`:346`） |
| `ai/gate/SpToolCallingManager.java` | 内联批循环（`:647`）与续跑认领胜者（`:1532` 普通结算 / `:1647` 超时结算）全部改走 `emitOnce`；新增可选注入 `setSettlementObservationStore`（`:292`） |

**恰好一次的论证**（日志与审计同一次抢占之内 ⇒ 两面恒同步）：
- 一条 `CONFIRM`/`FRONTEND` 调用的结算行只可能由三类执行体发：决策/放弃写胜者（`ai:decided` 唯一写者）、内联批循环、续跑认领胜者（`tryClaimExecution` 唯一胜者）。三者现在都必须先抢 `ai:observed:<runId>` 的同一 field；
- 抢到者发一行并 `SheetAudit.record` 一次；未抢到者静默让位（`log.debug`）⇒ 跨实例、乱序、重挂任意交错下**恒 ≤ 1**；
- **至少 1** 的来源覆盖：批循环走到该卡（内联）／未走到但已被决策（决策侧发）／续跑执行（认领胜者发）；
- **不放大**：续跑"复用结果"分支**不在任何发点序列里**（未改动），重挂 N 次仍 0 次新增；业务副作用零变更（本批只动观测面）。

**普通 inline 行为不变**：`observations == null`（轻装配/单测未注入）时 `emitOnce` 直接发，与改造前逐字同形；非 park 部署决策侧仍不参与（`publishesRequestsAheadOfLoop()` 前提保留），`readCalls`/行数口径不变。相邻类 `SheetSampleTraceTest`（6 例，inline 8 字段与台账）与 `ParkSettlementAuditTest` 第 8 例（park 关闭 + 真等待线程 ⇒ 恰 1 条）均为该性质的钉子。

## 4. GREEN（聚焦 + ≤4 相邻类）

```
mvn -s maven-settings.xml -B test \
 -Dtest="ParkSettlementOwnershipTest,ParkSettlementAuditTest,SheetSampleTraceTest,\
ConfirmGateSheetMetaPersistTest,ResumeToolStartBackfillConformanceTest,ParkToolStartOrderConformanceTest" \
 -DfailIfNoTests=false
```

`<仓外证据>/park-audit-fix2-evidence/green-focused-and-adjacent.log`：

| 类 | 用例 | 结果 |
|---|---|---|
| `gate.ParkSettlementOwnershipTest`（新） | 4 | ✅ I-1 跨实例不双记、I-2 多卡乱序不少记、观测位原语首写者胜、Redis 面 TTL + fail-open |
| `gate.ParkSettlementAuditTest` | 9 | ✅ 上一批 9 例全绿（含 `readCalls 0→1`、重复回灌、复用不放大、dismiss、executeOnce 成败、inline 对照、非 park 对照） |
| `gate.SheetSampleTraceTest`（相邻） | 6 | ✅ inline 载荷/台账/计数逐字不变 |
| `gate.ConfirmGateSheetMetaPersistTest`（相邻） | 2 | ✅ 决策写回条目上的 `sheetMeta` 仍在 |
| `conformance.ResumeToolStartBackfillConformanceTest`（相邻） | 4 | ✅ 续跑补 `tool_start` 与结算先后不变 |
| `conformance.ParkToolStartOrderConformanceTest`（相邻） | 2 | ✅ park 帧序不变 |
| **合计** | **27** | **Failures: 0, Errors: 0** |

其它门禁：`node scripts/ci/check-docs.mjs` → **6/6**（命名纪律含新文件）；补丁对干净 `d5916df` `git apply --check` → **EXIT=0**，应用后重新 `git diff` 与本补丁**逐字节相同**（`park-audit-fix2-evidence/patch-apply-check.txt`）。

## 5. 键生命周期契约（**交 Task 3 对齐**）

| 项 | 值 |
|---|---|
| 键形 | `ai:observed:<runId>`（HASH）；field = `toolCallId`；value = `<atMs>|<instanceId>` |
| 集群槽位 | **裸前缀、不带 `{run:<runId>}` tag**：只进 `HSETNX` / `EXPIRE` 两条单键命令，不进任何多键脚本（不需要同槽；加 tag 只牺牲分布） |
| TTL | `app.ai.session-ttl`（= 待决/req/decided 同寿）；**首次写入即置** |
| 续期面 | **不需要**入心跳/读侧整额续期面：有效窗口 = 同一轮结算过程（秒级），session-ttl 已远超 |
| purge | **TTL 自清**；不要求轮次回收主动删（与 `ai:beat:` / `ai:activity:` 同列"TTL 型 run 锚点"）。轮次回收后没有 run，也就没有第二个发点，孤儿键不改变任何判定 |
| 迁移 | **不搬**：`scripts/redis-run-migration.mjs` 的 `EXCLUDED_KEY_PREFIXES` 登记 `ai:observed:` 即可（一次性仲裁位，跨世代没有要保留的事实） |
| 降级 | Redis 失败 ⇒ `claim` 返回 `true`（fail-open）：宁可极小概率双记，也不能整片缺行 |
| 登记建议（Task 3） | 若 `SessionKeyRegistry` 收录，建议 `Renewal.WRITE`（只登记、随写置 TTL），**不要**放进 `HEARTBEAT`/`READ` 面（会打乱"心跳 5 / 读侧 5"的既有断言） |

> 注：本批最初一版曾把观测位做成 `RunStore` 的键族成员（含 `deleteRun` 13 键与迁移清单），按最新指令**已整体废弃**，交付树里 `RunStore` 与迁移脚本保持 main 原样；上面的契约就是那份工作的结论移交。

## 6. 文件清单（相对当前 main `d5916df`）

```
 backend/src/main/java/com/example/configmgr/ai/gate/ConfirmGate.java            |  57 ++--
 backend/src/main/java/com/example/configmgr/ai/gate/SpToolCallingManager.java   |  25 +-
 backend/src/main/java/com/example/configmgr/ai/gate/ToolCallEndTrace.java       |  62 ++-
 backend/src/main/java/com/example/configmgr/ai/observe/SettlementObservationStore.java | 112 +++（新）
 backend/src/test/java/com/example/configmgr/ai/gate/FakeBlockingTool.java       |  46 +++（新）
 backend/src/test/java/com/example/configmgr/ai/gate/ParkSettlementAuditTest.java |   6 +
 backend/src/test/java/com/example/configmgr/ai/gate/ParkSettlementOwnershipTest.java | 345 +++（新）
 docs/AI对话日志链路追踪手册.md                                                    |   2 +-
 8 files changed, 618 insertions(+), 37 deletions(-)
```

未改：`RunStore`、`SessionKeyRegistry`、`scripts/redis-run-migration.mjs`、`AiConfigSeeder/Service`、`ResumeService`、`SheetAudit`（上一批的文案即为现状）、任何前端文件。

## 7. 关注点 / 残余（如实登记）

1. **fail-open 的代价**：Redis 抖动时 `claim` 返回 `true` ⇒ 理论上可双记（日志 + 计数）。这是刻意取舍（缺行比重行更难发现），与 `RunStore#markRequestEmitted` 的降级口径同族。
2. **非 park 部署的跨实例回灌**：决策侧仍不参与（`park 开启` 前提保留），全集群无等待方时那一条 end 仍会缺席 —— 上一批已登记为已知边界，本批未扩大。
3. **进程死在"公布请求帧"与"park 退出"之间**：已决卡可能既没被批循环结算、也没被决策侧发（决策侧当时 `woke=true`）⇒ 该条 end 仍可能缺。属"进程死亡窗口"，本批不引入跨世代补发（那正是被禁止的回放放大面）。
4. **键目前由独立 store 管，尚未进 Task 3 的键族登记表**：在 Task 3 落地前，`ai:observed:*` 是"契约已声明、登记未落"的状态（TTL 已由本类自行设置，不会永存）。
5. **未跑全量 / 未跑 E2E**：按 plan cap 只跑聚焦 + 4 相邻类；E2E `AIE2E-T-13` 未实跑（禁起服务）。
6. `SheetAudit#record` 仍只在 `sheetMeta` 非空时计数（旧前端不上送 ⇒ 不计 `readCalls`），与既有白名单口径一致，未改。

## 8. 复跑指引

```
# 生效：git apply <仓外证据>/park-audit-fix2.patch   （基线 d5916df）
cd backend && mvn -s maven-settings.xml -B test \
  -Dtest="ParkSettlementOwnershipTest,ParkSettlementAuditTest" -DfailIfNoTests=false
# 证据：<仓外证据>/park-audit-fix2-evidence/{red-ownership.log,green-focused-and-adjacent.log,patch-apply-check.txt}
```
