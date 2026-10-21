# 补档 · B23L1 批③ 资源治理复审报告（DS-Flash，2026-10-21）

> 本件为入库补档：条目编号沿用收口提交内的注释锚（`I-1` / `I-2` / `M-1`~`M-4` / `N-1`~`N-7`），逐条按**代码最终态与复跑实测**对齐。凡与施工自报不一致处，以代码与实测为准并写明。

- 复审对象：`git status` 中的批③ 全部改动（生产 4 新 + 6 改；测试 11 新 + 4 改；scripts 2 改；前端 1 生产 + 1 spec）
- 只读纪律：零 commit/push/stash、零服务启停、零代码改动（唯一新增文件 = 报告）
- 自证：本补档由提交官转录（`agentId=agent-1336`，见 `-提交报告-` §0），复审链原始自证行以复审方报告为准，**不代签**
- 复审时点门禁：后端 **776**、前端 512/512、typecheck 0、check-docs 6/6

## 0. 结论：**CHANGES-REQUESTED**（0 Critical + 2 Important + 4 Minor + 7 Nit）

一句话：**四件设施各自方向正确，"谁在什么时候持有/归还"这一层还有两处会放大成停摆的缺口**。I-1 是"资源锁按续跑权的方式续租"——它把"动作卡住/驱动线程死亡"变成**事实永锁**，且没有旁观者能发现；I-2 是 harness 跨实例变体把"竞态未命中"报成 FAIL/或静默 PASS 的界线没划清，会让证据真伪失真。M 系四条里 M-1（索引错位交出别人的结果）与 M-3（白占任务级锁）各对应一条真实错账路径；N 系七条是口径与可注释项，但 N-2/N-3/N-5/N-6/N-7 各有代价，必须**显式挂账**而不是"顺手糊过去"。

**准予修后入库**：I/M 系全部处置、N 系逐条给出"收口 / 挂账"二选一后方可进复核。

## 1. 关键路径核对（先看"钱在哪"）

| 路径 | 代码事实 | 判定 |
| --- | --- | --- |
| 执行链顺序 | `对账/重放 → F2 目标校验 → 资源锁 → 认领（三态）→ 执行 → 台账 → finally 归还`（`SpToolCallingManager:643`） | ✅ 顺序正确："已执行过的调用"永远第一优先，其次才是"这一拍该不该执行" |
| 阻断出口 | 三码统一 `settleBlocked`（`SpToolCallingManager:817`）：不 appendLedger、不占认领、不抛、不中止同批 | ✅ 一致（若三处各写一遍必然分叉） |
| 资源锁 | `ToolResourceLocks:127` 固定顺序 + 抢不到即放弃 + 三态（`ACQUIRED`/`HELD_ELSEWHERE`/`UNAVAILABLE`）+ `DISABLED` | ⚠️ 首版**带续租**，见 I-1 |
| 锁释放 | `LockHandle.close` 只放 `created` 那一任（`ToolResourceLocks:92`） | ✅ "谁创建谁释放，重入不释放"与批② 同口径 |
| 幂等索引 | `ai:idem:<runId>` + `IDEM_PUSH` 单 Lua（`LLEN`→`RPUSH`→`HSETNX`→两键 `PEXPIRE`）（`RunStore:224`） | ✅ 单脚本；⚠️ 读侧错位未校验，见 M-1 |
| 幂等只当索引 | 索引不复制正文，`LINDEX` 取回；坏值/错位/旧数据一律回落台账扫描（`RunStore:1160`） | ✅ "台账才是判据" |
| UNKNOWN 对账 | 三跳（台账 → 认领 → 资源锁），只有"台账里有非 unknown 结论"才收敛（`ToolUnknownReconciler:66`） | ✅ 不确定性不被猜成成功 |
| 前端 | `parts.ts` 两码分支（只增不改） | ✅ 与后端码一一对应 |
| harness | `resource-lock`（H3 + `-SharedDb`）/ `idempotency` 转真 | ⚠️ 竞态变体口径，见 I-2 |

## 2. Important（2 条）

### I-1 · 资源锁照抄了续跑权的"续租"机制

- **现象**：首版 `ToolResourceLocks` 在拿到锁后把租约交给 `LeaseService.markDriving(...)` 按 `renew-interval` 续租，直到 `close()`。
- **为什么是 Important**：续跑权续租的正当性来自"**有人在推进**"（存活判据 = 驱动线程在跑 ∧ 快照非终态）。资源锁没有这个判据：一次导入/发布动作若在 `createAndStart` 之后卡住（下游慢、线程被 kill、事务回滚没走到 finally），锁会被**无限续下去** —— 别的实例永远抢不到，运维也看不出"这把锁没有主人"。这与设计稿 §2.3 的"临界区持有"口径（`acquire → createAndStart → finally release`、TTL 仅上界）直接冲突。
- **修法（收口）**：**摘除续租**。`resource-ttl`（60s）只作临界区上界；动作真超过上界时宁可让锁按 TTL 放开（别的实例可接手），也不续成"事实永锁"。崩溃后的白等窗因此恒 ≤ 60s。依据落在 `AiProperties:340-364` 与 `ToolResourceLocks` 类注。
- **关联**：设计稿 §2.3 的口径订正（初稿"TTL 60s + 持有者续期 + 终态释放"→ 以临界区口径为准）。

### I-2 · harness `-SharedDb` 竞态变体的"未命中"口径未定

- **现象**：跨实例资源锁的临界区是毫秒级 `createAndStart`，两轮（两个 run、同刻发令）**可能不重叠** —— 两轮都执行是**行为正确**，但断言若按"必有一个 `RESOURCE_LOCKED`"写，就会把正确行为报成 FAIL。
- **风险**：反向也危险 —— 若为了"不误报"直接放过，则"锁根本没生效"也会 PASS（假绿）。两者都会让这一批最关键的证据失真。
- **修法（收口）**：**最多 3 轮**，任一命中「恰一个 `RESOURCE_LOCKED` + 恰一个执行 + 台账合计 1 + 作业数 1」即 PASS；3 轮全未命中判 **SKIP**（不计 FAIL，不计 PASS）。**跨实例锁的确定性证据以 H3 预置锁变体为准**（预置 `SET <jobKey> 1|other-instance PX 60000` ⇒ 必得 `RESOURCE_LOCKED`；`DEL` 后重跑必得 `ok`）。SKIP 与真故障同形这一残余，登记 **L-17** 并在证据件里标注"不完整"。

## 3. Minor（4 条）

| # | 问题 | 风险 | 收口 |
| --- | --- | --- | --- |
| **M-1** | `idemFor` 索引命中即返回，**不校验该记录是不是本调用的** | 下标错位/索引被污染时，把**别的 toolCallId** 的台账正文当成本调用结果返回（"重放错了结论"比"没查到"更坏：用户会看到别人的产物） | 增 `matchesToolCall` 比对（`RunStore:1195`）；不符 ⇒ WARN + 回落台账扫描（`#anIndexPointingAtAnotherCallsRecordFallsBackToTheScan`） |
| **M-2** | 索引下标若在 Java 侧先读 `LLEN` 再推入 | 与并发写者竞争会把下标指向别人的记录（与 M-1 同族但成因不同） | 下标在**脚本内推入之前**取（`IDEM_PUSH_SCRIPT:224`），Java 侧不参与 |
| **M-3** | 抢第二把锁（作业级）失败时，第一把（任务级）**留在外面** | 一次被拒的调用白占任务级锁一个 TTL ⇒ 同一任务上的其它动作被无谓挡 60s | 分步记录已拿到的锁，任一失败**就地归还**（`#jobLockOnlyBlocksTheCallAndReturnsTheTaskLockItJustTook`） |
| **M-4** | 认领"先布尔口、失败后再补一次三态口" | 同一判据做两遍（`HSETNX` 与 fence `GET` 各两趟），且两趟之间状态可能变 | 首次即走 `tryClaimExecution` 三态口（`SpToolCallingManager:684`）；布尔口只留轻装配面回落 |

> **M-3 的字面纠正（复审自纠，需留痕）**：复审首稿写的是"预置 **IMPORT** 锁应挡住 `start_export`" —— 该断言**不成立**：双粒度键按**作业类型**分（`ai:lock:job:<taskId>:<JOBTYPE>`），预置 `:IMPORT` 挡不住 `:EXPORT`，这是设计本意（导出与发布互不阻塞）。正确的构造是**只预置作业级 `:EXPORT` 锁**（任务级锁留空），此时 `start_export` 应被挡、且已拿到的任务级锁必须归还 —— 用例与断言已按此改写（`#jobLockOnlyBlocksTheCallAndReturnsTheTaskLockItJustTook`）。

## 4. Nit（7 条）

| # | 条目 | 处置 |
| --- | --- | --- |
| **N-1** | 降级（`UNAVAILABLE`）的 WARN 文案写成"按进程内互斥放行"，与三态口径不符：资源锁的降级**不是**"进程内互斥"，而是"这一拍判断不了 ⇒ 放行，后续由 fence 写与 `STALE_FENCE` 认领兜底" | **已订正**：`ResumeService` 与 `ToolResourceLocks` 的注释/日志口径统一（"这一拍校验不了，杀招在 fence 与 claim"） |
| **N-2** | `appendLedger` 在 `IDEM_PUSH` 脚本之后**多一次 `EXPIRE`**（脚本内已 `PEXPIRE`） | **挂账 L-13**：为保留批②"台账滑动 TTL"在模板面的可观测口径，接受一次额外往返 |
| **N-3** | `stopDriving(key)` 裸键版要"停该键全部 owner"，实现是**遍历 `driving.keySet()` 做前缀匹配** | **挂账 L-14**：仅在释放/收尾路径调用，规模 = 本实例在跑的租约数 |
| **N-4** | 旧台账**缺 `outcome` 键**时，两条路径结论不同：对账器 ⇒ UNKNOWN；`replayLedger` ⇒ OK | **保留差异并写明理由**（`ToolUnknownReconciler` 类注"口径差异"）：对账面对的是"结局未知"，缺字段不能证明成功；重放面对的是"早已执行完并留了台账"，旧记录按成功复用是历史兼容 |
| **N-5** | `describe().keys` 的 `lockTask`/`lockJob` 为 **null**（页面轮无 taskId） | **挂账 L-15**：固定键面 + null 语义写进注释；下游需容 null |
| **N-6** | `matchesToolCall` 对"记录缺 `toolCallId`"的兜底链未在注释点名 | **挂账 L-18**：行为安全（回落扫描 ⇒ 判未执行 ⇒ `ai:claim:` `HSETNX` 兜住，不会真重复执行），注释待补 |
| **N-7** | `TARGET_INVALID` **只在审批后（执行前）**校验 | **挂账 L-16**：审批前拦截属 F5 预检面；已弹卡的非法目标会等到批准后才被阻断 |

## 5. 攻击面结论表（a–j）

| # | 攻击面 | 攻击构造 | 结论 | 依据 |
| --- | --- | --- | --- | --- |
| **a** | 同任务双实例同时发起同类作业 | 两实例各 `start_export` 同一 task | **被拦**：作业级锁同键 ⇒ 恰一个 `RESOURCE_LOCKED`（H3 变体确定性命中；`-SharedDb` 竞态变体三跑第 1 轮命中） | `ToolResourceLockGateTest#concurrentCallsOnSameTaskExecuteExactlyOnce`、实机核验 §3 |
| **b** | 不同作业类型互相阻塞 | 导出在跑时发发布 | **不拦（设计本意）**：键带 JOBTYPE，导出与发布互不阻塞 | `#anotherJobTypeDoesNotBlockTheSameTask` |
| **c** | 锁顺序活锁 | 一边先抢任务锁、一边先抢作业锁 | **结构上不存在**：固定顺序 + 不等待不轮询；抢不到即 `blocked` | `ToolResourceLocks:127` 类注 |
| **d** | 持锁进程崩溃 / 动作卡死 | 杀进程或让 `createAndStart` 卡住 | **被 TTL 兜住**：不续租 ⇒ 最迟 60s 后可被接管；代价 = 崩溃后白等 ≤ 60s | I-1、`LeaseResourceTtlTest` |
| **e** | Redis 不可用 | 关掉 Redis 后发起导入 | **放行 + WARN**（availability ≠ held）；不把抖动变成全线拒绝 | `#unavailableLeaseFacilityDegradesToExecutionWithWarn` |
| **f** | 重放导致重复副作用 | 同一 `runId:toolCallId` 二次结算 / 续跑复跑 | **被拦**：索引命中即回原结果、零副作用；索引坏则回落台账（台账才是判据） | `ToolIdempotencyTest`、`RunStoreIdempotencyIndexTest` |
| **g** | 台账与索引两步之间崩溃 | 人为断在 `RPUSH` 与 `HSETNX` 之间 | **被拦**：两者同一条 Lua（不可能断在中间）；脚本内 `PEXPIRE` 两键同寿 | `#ledgerAndIndexAreWrittenInOneScript` |
| **h** | 索引错位交出别人的结果 | 污染/错位索引值 | **被拦**：`matchesToolCall` 不符 ⇒ WARN + 回落扫描 | M-1、`#anIndexPointingAtAnotherCallsRecordFallsBackToTheScan` |
| **i** | UNKNOWN 被猜成成功 | 台账写着 `unknown` / 只有认领 / 只有锁 | **不猜**：除"台账非 unknown"外一律保持 UNKNOWN 并说明原因 | `ToolUnknownReconcilerTest`（8 条） |
| **j** | 非法目标 / 越 scope 参数 | args 指向不存在的任务或别人的任务 | **被拦**：`blocked/TARGET_INVALID`，不占认领不写台账；**仓储异常 fail-open**（查不了 ≠ 不存在） | `ToolTargetResolverTest`、`ToolTargetResolverGateTest` |

## 6. 复审时点门禁

| 门禁 | 命令 | 实测 |
| --- | --- | --- |
| 后端全量 | `<MAVEN_HOME>/bin/mvn -o test` | **776/776 绿**（0F/0E/0S）→ 收口后 **778**（最终由提交官复跑 778 确认） |
| 定向 | `-Dtest='ToolResourceLockGateTest,ToolIdempotencyTest,ToolTargetResolver*Test,ToolUnknownReconcilerTest,LeaseKeysTest,LeaseResourceTtlTest,LeaseServiceDistinctOwnerTest,ResumeDescribeLockKeysTest,RunStoreIdempotencyIndexTest,ToolOutcomeResourceCodesTest,RunStoreClaimFenceTest'` | 全绿 |
| 文档门禁 | `node scripts/ci/check-docs.mjs` | 6/6 绿 |
| 前端 | `vitest run` | 512/512 · 26 文件 |

## 7. 遗留与去向

| 级别 | 条目 | 去向 |
| --- | --- | --- |
| I | I-1 摘除续租 / I-2 重试 + SKIP 口径 | 收口（已落） |
| M | M-1 / M-2 / M-3 / M-4 | 收口（已落）+ M-3 字面纠正留痕 |
| N | N-1 已订正；N-2/N-3/N-5/N-6/N-7 | 挂账 **L-13 / L-14 / L-15 / L-18 / L-16** |
| N | N-4 口径差异 | 保留并写明理由（不挂账） |
| 观察 | `-SharedDb` 变体 SKIP 与真故障同形 | 挂账 **L-17**（证据口径 = H3 确定性 + 至多一次命中，不完整须标注） |

**准予修后入库**：四件设施各自语义成立，"台账才是判据""不确定性不猜成成功""拿不到就报 blocked"三条主线未被破；I/M 系同批收口后进复核。
