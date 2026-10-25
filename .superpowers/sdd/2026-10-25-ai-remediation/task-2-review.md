# Task 2 · 多实例结算审计"恰好一次" · 独立复审

- 复审对象：`task-2-report.md`、`<仓外证据>/park-audit-fix2.patch`（brief：`task-2-brief.md`）
- 基线口径：实际 main = **`34fd8f4` + 未提交的 config/audit 候选**（工作区含上一批 park audit，`ToolCallEndTrace` 仍是未跟踪文件）。报告所指 `d5916df` 是独立 clone 的提交，**不是 main HEAD**；本复审按内容评估补丁，不受标签影响。
- 只读复审（未改 main、未提交、未派子代理）；独立复跑用临时拷贝 `<隔离副本>/_r2-task2-review`（main 工作区 `backend/src` + 补丁），不污染主树。

## 0. 判定

| 项 | 判定 | 一句话 |
|---|---|---|
| **Spec Compliance** | **FAIL（仅 1 项：键销毁）** | 两条缺陷（I-1 双记 / I-2 零行）的真修法成立且可复现；但"会话销毁必须删除全部 AI 数据"这条**绑定要求**被"TTL 自清"结论直接违背，且 `HSETNX`/`EXPIRE` 非原子可留下**永不过期**的键 |
| **Task Quality** | **PASS（B）** | 仲裁点收敛到单一发口（`emit` 降为 private，编译器保证不可绕过）、载荷零改动、证据可复跑；扣分：失败路径无补偿、EXPIRE 只试一次、Redis I/O 进了实例级 `submitLock`、"至少 1 覆盖"的论证不完整 |
| **Critical** | **1** | C-1：键生命周期（purge）与绑定要求冲突（报告 §5 / 代码注释 / 销毁面三处不一致） |
| **Important** | **4** | I-1 `HSETNX`+`EXPIRE` 非原子 ⇒ 永久键；I-2 抢位后发失败 ⇒ 该条结算**永久丢行**且无释放；I-3 每次前端结算都在 `submitLock` 内多一次 Redis 往返；I-4 "至少 1 条"的覆盖论证漏了 park 后的前端超时路径（存量） |
| **Minor** | **7** | §4 |
| **合并建议** | **补齐 C-1 后可合并**（约 10 行：销毁面 + 单键脚本/自愈 TTL） | exactly-once 主机制本身**不需要重写**；阻塞点只在生命周期 |

## 1. 我自己核对过的证据（不靠报告转述）

| 证据 | 我的核对结果 |
|---|---|
| 补丁 vs 实际 main | `git apply --check`（main 工作区）**EXIT=0**；`git apply --check` 对**裸 `34fd8f4`**（`git archive HEAD`）**EXIT=1**（ConfirmGate / SpToolCallingManager / 文档三处 context 不匹配，`ToolCallEndTrace` 不存在）⇒ 补丁是**未提交候选之上的增量**，合并顺序必须先落 config/audit + 上一批 park audit |
| 27/27 复跑 | 我在干净拷贝上 `mvn -o -B test -Dtest="ParkSettlementOwnershipTest,ParkSettlementAuditTest,SheetSampleTraceTest,ConfirmGateSheetMetaPersistTest,ResumeToolStartBackfillConformanceTest,ParkToolStartOrderConformanceTest"` → **`Tests run: 27, Failures: 0, Errors: 0`，BUILD SUCCESS**（与报告 §4 的类/例数逐项一致） |
| 测试是否"承重"（变异） | 临时把 `emitOnce` 的 `claim` 短路（`if (false && …)`）⇒ `crossInstanceDecisionDoesNotDoubleSettleWhenTheOriginLoopSettlesLater:224` **确定性失败**，`readCalls=2`；同时 I-2 用例**仍绿**（说明两处改动各自承重：去掉 `!woke` 修 I-2，仲裁修 I-1）。不是同义反复 |
| RED 可复现性 | 报告证据 `park-audit-fix2-evidence/red-ownership.log`（224/263）与 `red-ownership-independent-store.log`（225/264）两次独立 RED 的失败名与 `readCalls=2` / `size 0` 形态一致；行号 253 是笔误（实际 263） |
| 文档门禁 | 我在 main 工作区重跑 `node scripts/ci/check-docs.mjs` → **6/6 绿**（2210 路径 / 实扫 2207 / 豁免 3） |
| 键是否已落地登记 | `grep ai:observed` 仅命中 `.superpowers` 报告与 **`task-3-report.md:183-190`**；`scripts/redis-run-migration.mjs:76-…` 的 `EXCLUDED_KEY_PREFIXES` **没有** `ai:observed:`，`SessionKeyRegistry` 也**没有**收录 ⇒ 报告 §5 的"交 Task 3"在 Task 3 侧被明确**退回**（"这一行留给集成方"），两端都没落地 |
| 会话销毁面 | `ai/session/AiSessionPurgeService.java:773-782` `deleteObservabilityKeys` 只删 `beat` / `activity` / `cancel`，注释写着"**它们不进 deleteRun，按 TTL 自清；销毁要一并清掉**" ⇒ 代码库自己的口径就是"TTL 型也要删"，报告的"TTL 自清不删"与既有先例相反 |

## 2. Critical / Important

### C-1（Critical）键生命周期：TTL-only 违反绑定要求，且 EXPIRE 失败会留下永久键

**事实三连**（互相对不上）：

1. 绑定要求：**会话销毁必须删除全部 AI 归属数据，不能只等 TTL**。
2. 报告 `task-2-report.md:102`：`purge = TTL 自清；不要求轮次回收主动删`（类注释同款，补丁内 `SettlementObservationStore.java:35-36`）。
3. 现有实现 `ai/session/AiSessionPurgeService.java:773-782`：**同类"TTL 型 run 锚点"（`ai:beat:` / `ai:activity:` / `ai:cancel:`）在销毁时是被显式 DEL 的**。报告的类比（"与 beat/activity 同列"）恰好证明它**应该被删**，而不是相反。

后果：`DELETE /api/ai/session/{sid}` 之后，`ai:observed:<runId>` 仍存活最长 24h（`AiProperties.java:36` / `application.yml:146`）。这就是"只等 TTL"，与绑定要求正面冲突。Task 3 已明确不接这一行（`task-3-report.md:189`），所以**当前没有任何人会在链路上补上它**。

**`HSETNX`+`EXPIRE` 非原子 = 永久键（已用探针证实）**：补丁内 `SettlementObservationStore.java:97-101` 先 `putIfAbsent` 再 `expire`，且 `expire` **只在首写尝试一次**。我在临时树写了探针 `SettlementObservationStoreReviewProbeTest`（2/2 通过）：

- `putIfAbsent` 成功、`expire` 抛错（连接抖动 / 进程在两命令之间被杀同形态）⇒ `claim` 走 `catch` **fail-open 返回 true**，键**已写入但没有任何 TTL**；
- 之后同一 field 再 `claim` 一律 `false`（`putIfAbsent` 返回 false，`if (Boolean.TRUE.equals(first))` 不成立，**永不补 EXPIRE**）；
- `verify(redis, times(1)).expire(...)` ⇒ **全生命周期只尝试过一次**。

即：一个抖动就换来一个**永不过期**的键（内存/键空间泄漏 + 永久"已观测"墓碑），且与 C-1 叠加后连兜底 TTL 也没有。

**最小正确生命周期（不需要动 `RunStore`，也不需要动决策记录）**：

1. **销毁即删**：在现有 `AiSessionPurgeService#deleteObservabilityKeys`（`:774-782`）的 `DEL` 列表里加 `SettlementObservationStore.KEY_PREFIX + runId`（或 `store.key(runId)`），与 beat/activity/cancel 同列；失败会进 `errors` ⇒ 走既有 `SessionPurgeRetryRunner` 重试语义，不需新机制。
2. **进程内面同步清**：给 store 加 `purge(runId)`（`observed.remove(key(runId))`），销毁时一并调（Redis 缺席时它就是唯一面）。
3. **写入即原子过期**：单键 Lua（`HSETNX` 失败则不 `EXPIRE`；成功则同脚本内 `EXPIRE`），或退一步"每次 claim 都确保 TTL"（自愈），保证**不存在无 TTL 的 `ai:observed:`**。
4. **迁移**：`EXCLUDED_KEY_PREFIXES` 加 `ai:observed:`（一次性仲裁位，不搬）。这条也必须有人落地——Task 3 已声明不接。

> 报告的"不进任何多键脚本，所以不用 Lua"是把**多键脚本**与**脚本**混为一谈：单键 `EVAL` 在 Cluster 下天然同槽，不需要 `{run:}` tag，正是这里的正确工具。

### I-1（Important）抢位与发行为不在同一临界区：抢到后失败 ⇒ 永久 0 条，且无释放

`ToolCallEndTrace.java:54-65`：先 `claim` 后 `emit`，`emit` 内 `AiTrace.eventOn` 与 `audit.record` 都可能抛（`ConfirmGate.java:1720-1730` 只 `WARN` 吞掉，`SpToolCallingManager.java:647` 则直接上抛）。一旦抛出：

- 抢占位已消费 ⇒ 另一个执行体（可能就是本来会成功的那一个）被判"已发过"而静默让位；
- 结果 = 该 `toolCallId` **永久 0 条 end**（改前最坏是 1 条或重复 1 条，改后可能 0 条）。

概率低（日志/计数几乎不抛），但这是"exactly-once"里方向错误的失败模式（宁重不宁缺）。最小修法：`emitOnce` 内 `try { emit(...) } catch (RuntimeException ex) { release(runId, toolCallId); throw ex; }`（`HDEL` 单 field，失败只 WARN），并补一条"发失败后可被重试"的用例。至少应在报告 §7 登记为已知窗口。

### I-2（Important）`submitLock` 内新增 Redis 往返，且从"条件触发"变成"每次触发"

`ConfirmGate.java:217` 的 `submitLock` 是**实例级单锁（跨 run 共用）**。补丁把 `settleParkedToolCall` 从 `!woke` 改为无条件（`:1636` / `:1685`），但它仍在 `synchronized (this.submitLock)` 块内（`:1597-1638`），于是 park 部署下**每次**前端回灌/放弃都在全局锁里多一次 `HSETNX`（+`EXPIRE`）往返；改前只有 `woke=false` 才付这份钱。上一轮复审（`park-audit-review.md:12`）已就"发点在全局 `submitLock` 内做 Redis I/O"扣过分，本批把它扩大成常态。

仲裁位存在之后，发点已不需要与 `switchParkedLifecycle` 互斥（谁先抢到谁发），把 `settleParkedToolCall` 移到 `synchronized` 块**之后**即可（落库/发帧/wake 的次序不变），属于零语义风险的收紧。

### I-3（Important，存量）报告"至少 1 条"的覆盖论证不完整：park 后的前端**超时**路径 0 条

报告 §3 断言覆盖来源 = ①决策/放弃胜者 ②内联批循环 ③续跑认领胜者。但 park 之后还有第四条结算路径：`ConfirmGate.java:1068-1095` `scheduleParkedFrontendExpiry` → `expireFrontend`（`:1034-1054`）把条目收敛成 `TIMEOUT/FRONTEND_TIMEOUT`、发结局帧、派发续跑，**全程不发 `tool_call_end`、不记审计**；续跑回到 `SpToolCallingManager.java:1845-1848` 的"复用结果"分支（状态既非 `APPROVED` 也非 `PENDING+BACKEND`）同样不发 ⇒ 该卡**永久 0 条 end**。

这条在上一轮复审里已被登记为"口径问题、不作为阻塞项"（`park-audit-review.md:101`、`park-audit-fix-report.md:165`），本批未扩大范围，所以**不作为本批阻塞**；但报告"至少 1"的措辞应改为"仅覆盖上述三类发点，超时终态仍缺（已知边界）"，否则读报告的人会以为 0 条已不可能。

## 3. 复核用户点名的其它问题

### 3.1 为什么 inline / 决策 / 认领三处都必须参与（结论：必须，少一个就回退到 I-1 或 I-2）

| 执行体 | 它是谁的唯一所有者 | 去掉它会怎样 |
|---|---|---|
| 内联批循环 `SpToolCallingManager.java:647` | 非 park 的 inline 结算；park 下"卡未被 park 掉、循环仍会走到"的那一支 | I-2 变体：预登记闸门被摘后 0 条 |
| 决策/放弃写胜者 `ConfirmGate.java:1636` / `:1685` | 线程已交还、且**没有**任何内联循环会走到该卡（含**跨实例**回灌：B 上根本没有 A 的闸门） | I-1/I-2 原形（0 条或只能靠侥幸） |
| 续跑认领胜者 `SpToolCallingManager.java:1532` / `:1647` | park 后真正执行了 `CONFIRM`/后端工具的那一次 | `CONFIRM` 批准后无人发 end（`submitDecision` `:1228-1282` 本身不发结算） |

三者互相不可替代，**且都不足以独占**：所有权判据（`ai:decided` 唯一写者、`tryClaimExecution` 唯一胜者、批循环唯一路径）都是"某个执行体内部"的事实，而 I-1 的窗口恰好是"无租约持有者 + 决策落在无闸门实例"，任何**进程内**判据（含 `woke`、含租约）在那里都恒为可抢/不可见——报告 §1 对租约不可用的分析成立。所以"先抢同一个持久首写位"是当前架构下的最小正确解，**不需要**引入第二套所有权。

`kind=BACKEND` 不抢位也正确：它的结算只可能由 `tryClaimExecution` 的唯一胜者发（内联循环里那次执行本身就是胜者），不存在第二个发点；`kind=null` 旧数据按"需要仲裁"处理是保守正确的方向。

### 3.2 内存面 vs Redis 面语义差异

| 维度 | `redis != null` | `redis == null`（进程内表 `:93-95`） |
|---|---|---|
| 跨 JVM 仲裁 | ✓（`HSETNX`） | ✗（每 JVM 一份 ⇒ I-1 双记原形） |
| TTL | 24h（首写置，非原子） | **无**（`observed` 无淘汰、无清理入口） |
| 会话销毁 | 不删（C-1） | 不删（C-1，且进程内表按 run 永久累积） |
| 失败语义 | fail-open ⇒ 可能双记 | 不适用 |

补正事实：生产上 `RunStore` 构造器**强制**要 `StringRedisTemplate`（`RunStore.java:422`），所以 `redis == null` 实际只在单测/轻装配出现——**内存面是测试面**。这解释了为什么 Redis 分支的原子性缺口没有任何用例能发现（`:786-800` 的 redis 用例是 Mockito 打的桩，只验证"首写调了一次 expire / 抛错返回 true"，不验证真实 Redis 语义）。建议：① 用例里补一条"`expire` 抛错后键无 TTL、后续 claim 被挡"（我用的探针就是它，可作为回归位）；② 若保留内存面，加 `purge(runId)` 或改用有界结构。

### 3.3 fail-open 的重复边界

fail-open 的重复**不是无限放大**：单卡最多被参与的 2~3 个执行体各发一次，与重挂次数无关（"复用结果"分支 `SpToolCallingManager.java:1681-1701` 一行未动，测试也钉住"重放两次仍 1 条"）。但存在一个被报告忽略的**非对称**窗口：E1 抢位失败（fail-open ⇒ 发一行）→ Redis 恢复 → E2 抢位成功 ⇒ 再发一行 = 双记。也就是说"极小概率双记"实际是"抖动恰跨两次结算"而不是"整体故障"。可接受（方向正确），但建议按项目既有手法挂 `RedisDegradation` 计数器（该类就是为这类"降级发生了但要可断言"而建），否则 fail-open 只有 `log.debug`，`/health` 上看不见。

### 3.4 旧 park / 非 park 是否有回归

| 面 | 结论 |
|---|---|
| 非 park | **无回归**：`publishesRequestsAheadOfLoop()` = `properties.getPark().isEnabled()`（`ConfirmGate.java:468-470`），决策侧仍不参与；载荷键序/取值零改动（`ToolCallEndTrace.pairs` 未动）；`SheetSampleTraceTest` 6/6 绿。唯一变化是每次 `CONFIRM`/`FRONTEND` 结算多一次 Redis 写（观测面成本，可接受） |
| park（单实例） | **行为有意改变**：inline 已答卡片的 end 现在由 HTTP 线程先发（决策侧抢位），批循环让位。行数与 `readCalls` 仍为 1；帧序（`tool_start` → 请求帧 → `tool_end`）不受影响（`tool_start` 在公布请求帧之前，必然早于任何决策）；`ParkToolStartOrderConformanceTest` / `ResumeToolStartBackfillConformanceTest` 绿 |
| 业务副作用 | **零变更**：补丁只碰观测面；决策/认领/台账/幂等路径未改；`RunStore` / `SessionKeyRegistry` / 迁移脚本一行未动（与 brief 一致） |
| 放大 | **无**：复用分支不在任何发点序列；`settleObservation=false` 的内联执行路径不变 |

## 4. Minor

| # | 位置 | 说明 |
|---|---|---|
| M-1 | `SettlementObservationStore.java:89-92` | `runId`/`toolCallId` 空白时**无条件放行**：真出现这类条目会静默失去仲裁。当前所有调用点都有主键，可接受，但应记一句"这是保观测面不沉默的取舍" |
| M-2 | `SettlementObservationStore.java:58-59` | 内存面无淘汰、无淘汰入口（同 §3.2）；`computeIfAbsent(key(runId))` 用的又是带前缀的键，命名与注释（"runId → …"）不一致 |
| M-3 | `SettlementObservationStore.java:33-34` | TTL 只按 `session-ttl` 置一次且不续期；结论上没问题（同一结算窗口是秒级），但"与 `ai:decided:` 同寿"的措辞不准确——`ai:decided:` 在读/决策面**被整额续期**，本键不续 |
| M-4 | `task-2-report.md:105` | 建议把 `ai:observed:` 登记进 `SessionKeyRegistry` 的 `Renewal.WRITE`——这会直接打挂 `SessionKeyRegistryTest.java:23` 的 `hasSize(19)` 与 `:30-33` 的前缀清单断言，登记时必须同时改用例；报告未提 |
| M-5 | RED 证据行号 | 报告写 224/**253**，实际日志为 224/**263**（独立跑为 225/264）；不影响结论 |
| M-6 | `ToolCallEndTrace.java:57-62` | 让位时只有 `log.debug`，无计数器；多实例下"这条为什么没发"只能靠 `debug` 开日志才看得见。建议同 `RedisDegradation` 记一个"结算让位"计数 |
| M-7 | `FakeBlockingTool` | `await(20s)` 兜底 + `finally` 放行是对的；但用例失败路径下工具线程最长占用 20s，建议与 `tool.exec-timeout` 用例口径统一（非阻塞） |

## 5. brief 逐条对照

| brief 要求 | 结论 |
|---|---|
| Step 1 两条确定性失败场景（跨实例不双记 / 多卡乱序不少记） | **✓** 用 `FakeBlockingTool` 的 `entered/release` 把窗口钉死，不靠 sleep；两用例都断言了复现前提（`submitted.woke()` 的 true/false、`pending` 已公布） |
| Step 2 只跑新场景确认 RED | **✓** 且有两份独立 RED；我另做变异跑确认"关掉仲裁即回到 readCalls=2" |
| Step 3 用真实所有权或最小持久幂等标记，不依赖进程内 `woke` | **✓** 弃用 `woke`、弃用租约（报告对租约不可用的分析成立），改用独立 store 的首写者仲裁；未放大回放分支 |
| Step 3 普通 inline 行为不变 / 无业务副作用 | **✓（语义）**；成本上 inline 结算多一次 Redis 写（见 I-2） |
| Step 4 聚焦 + ≤4 相邻类 GREEN | **✓** 27/27，我独立复跑一致 |
| Step 5 输出差异、测试、键生命周期 | **差异/测试 ✓；键生命周期 ✗（purge 结论错误，见 C-1）** |
| brief 产出要求"若新增键，必须给出 TTL、purge、迁移排除清单" | TTL ✓、迁移清单 ✓（但**未落地**，Task 3 已退回）、**purge ✗** |
| 绑定要求：会话销毁删除全部 AI 数据 | **✗** |

## 6. 结论与合并建议

- **exactly-once 主机制：站得住**。`emit` 降为 private + 单一 `emitOnce` 入口 + 三发点共抢同一 field，"≤1"有结构保证；"≥1"覆盖三类发点（超时终态除外，存量）；复用分支不放大；业务零副作用；两条缺陷各有确定性用例，且我验证了用例对修复"承重"。
- **不合规点只有生命周期**，但它落在用户**绑定要求**上，且报告把结论写反了（TTL 自清 = 只等 TTL），Task 3 又已明确不接 ⇒ 若按现状合并，这条要求会静默失守。
- **建议**：本批**补齐 C-1 后再合并**——
  1. `AiSessionPurgeService#deleteObservabilityKeys` 一并 `DEL ai:observed:<runId>`（+ store `purge(runId)` 清内存面）；
  2. `claim` 改为单键 Lua（`HSETNX` 成功才 `EXPIRE`）或每次 claim 确保 TTL；
  3. 更正类注释/报告 §5 的 purge 结论，并给迁移排除项指定落地人（Task 3 已退回）；
  4. 顺手：`emitOnce` 发失败时释放占位（I-1）、`settleParkedToolCall` 移出 `submitLock`（I-2）。
- I-3（park 后前端超时 0 条）建议单独立项，不阻塞本批。

## 7. 复现指引（我实际执行的命令）

```bash
# 1) 补丁对实际 main 工作区可应用；对裸 34fd8f4 不可应用（依赖未提交候选）
cd <主仓>
git apply --check <仓外证据>/park-audit-fix2.patch     # EXIT=0

# 2) 独立复跑（临时拷贝，不动主树）
cd <隔离副本>/_r2-task2-review/backend
mvn -s maven-settings.xml -o -B test \
  -Dtest="ParkSettlementOwnershipTest,ParkSettlementAuditTest,SheetSampleTraceTest,\
ConfirmGateSheetMetaPersistTest,ResumeToolStartBackfillConformanceTest,ParkToolStartOrderConformanceTest" \
  -DfailIfNoTests=false                                        # 27/27

# 3) 变异：短路 claim ⇒ I-1 确定性红（readCalls=2）
#    证据：<隔离副本>/_r2-task2-review/mutation-no-claim.log

# 4) 探针：EXPIRE 失败 ⇒ 无 TTL 永久键、不再补 EXPIRE
#    <隔离副本>/_r2-task2-review/backend/src/test/java/.../SettlementObservationStoreReviewProbeTest.java
```
