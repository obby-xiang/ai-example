# Task 2 fix1 · 范围复审（C-1 / I-1 / I-2 / I-3 / 迁移 / 销毁接线）

- 复审对象：`task-2-fix1-report.md`、`<仓外证据>/park-audit-fix2b.patch`（本 patch **累积包含** fix2 的全部改动 ⇒ 只合这一份，不要再叠 fix2）
- 基线：实际 main 工作区（`34fd8f4` + 未提交 config/audit + 上一批 park audit 候选）。`git apply --check` 于该工作区 **EXIT=0**；`git apply -R --check` 于打补丁后的树 **通过** ⇒ 补丁与"基线 → 最终树"逐字对应
- 只读复审：未改 main、未提交、未派子代理。独立验证在临时拷贝 `<隔离副本>/_rr2-task2` 上做（40 例 + 迁移 52 例 + 2 次变异/探针），**未跑全量、未起服务**
- 按要求**不扩** `expireFrontend`（park 后前端回执超时 0 条）——该残余仍登记，本复审只核对它是否被如实例外登记

## 0. 判定

| 项 | 判定 | 一句话 |
|---|---|---|
| **Spec Compliance** | **FAIL（1 个新 Critical）** | C-1/I-1/I-2/I-3/迁移/销毁接线逐项都做了，但**仲裁在生产上恒失效**：Lua 的 ARGV 传了 `Long`，`StringRedisTemplate` 会 `ClassCastException` ⇒ 被 `catch` 成 fail-open ⇒ I-1 双记原形回归；40 例全绿因为测试替身把 `execute` 整体桩掉了 |
| **Task Quality** | **PASS（B+）** | 单键脚本 + 自愈 + `release` + 锁外 I/O + 迁移两端落地 + 销毁契约测试，方向全对；扣分：ARGV 类型（致命）、`ensureTtl` 冗余、`release` 边界无测试、字面量双写 |
| **Critical** | **2** | R-C-1（新）Lua ARGV 装箱 `Long` ⇒ 生产仲裁失效；R-C-2（交付完整性）本 patch 单独**没有** `AiSessionPurgeService` 调用点（已核实在 Task 3 fix1 里，必须同批合并） |
| **Important** | **4** | release 边界的重复 vs 永久 0 未被测试钉住；`ai:observed:` 字面量两处双写无交叉校验；`ensureTtl` 冗余；fail-open 双发无计数器（正是 R-C-1 隐形的原因） |
| **Minor** | **5** | §5 |
| **合并建议** | **本 patch 先别合**；改一行 `String.valueOf(ttlSeconds())` + 补一条序列化级用例后再合，且**必须与 Task 3 fix1 同批**（否则 C-1 静默失守） | exactly-once 机制本身不需要重写，只需要让它在真实序列化下能跑起来 |

## 1. 我自己核对过的证据（不靠报告转述）

| 证据 | 结果 |
|---|---|
| 40 例 | 临时树上 `-Dtest="ParkSettlementOwnershipTest,SettlementObservationPurgeContractTest,ParkSettlementAuditTest,SheetSampleTraceTest,ConfirmGateSheetMetaPersistTest,ResumeToolStartBackfillConformanceTest,ParkToolStartOrderConformanceTest,RunMigrationFamilyContractTest"` → **`Tests run: 40, Failures: 0, Errors: 0`，BUILD SUCCESS**（11+4+9+6+2+4+2+2 = 40，与报告逐类一致） |
| 迁移 52 | `node --test scripts/redis-run-migration.test.mjs` → **52/52 pass** |
| 文档门禁 | `node scripts/ci/check-docs.mjs` → **6/6** |
| RED 内容 | `task2-fix1-evidence/red-before-fix.log`：`Tests run: 7, Failures: 2, Errors: 1`，三条失败名与报告一致（`aFailedEmission…` 读 `readCalls=0`、`anObservedMarkerWithoutTtl…` 走 `redis.expire` 未调用、`settlementIoRunsOutside…` 抛 `TimeoutException`）——方向对、可复现 |
| 变异（承重性） | 临时禁用 `emitOnce` 的 `release` ⇒ `aFailedEmissionReleasesTheMarkerSoALaterSettlementCanRetry:407` **确定性红**（`readCalls` 期望 1）⇒ 用例不是同义反复 |
| 变异（`ensureTtl`） | 把 `ensureTtl` 改成 no-op ⇒ **`aTombstoneWithoutTtlIsRepairedByTheNextClaim` 仍绿** ⇒ TTL 自愈实际由脚本里的无条件 `EXPIRE` 完成，`ensureTtl` 是冗余（见 §4） |
| **ARGV 序列化（新）** | 临时探针 `ObservationArgvSerializationProbeTest` **3/3 通过**：`StringRedisSerializer.serialize(Object)` 对 `Long` 抛 `ClassCastException`；**真实** `StringRedisTemplate.execute(script, keys, "c1", "1|i", 5L)` 在发出命令前就抛 CCE；`store.claim("r1","c1")` 连调两次**都返回 true**（fail-open，零仲裁） |

## 2. 原复审发现逐条结论

| 原编号 | 判定 | 依据 |
|---|---|---|
| **C-1** 销毁只等 TTL | **ADDRESSED（联合交付；本 patch 单独不完备 → R-C-2）** | store 侧：`observedKey`/`purge`（`SettlementObservationStore.java:97-100/168-185`），类注改为"销毁必须删，TTL 只兜底"（`:28-30`）；销毁侧：`AiSessionPurgeService#deleteObservabilityKeys` 已逐 run 单键 DEL（Task 3 fix1 补丁，见 §3.2），失败进 `errors` 走既有重试 |
| **I-1** `HSETNX`+`EXPIRE` 非原子 ⇒ 永久键 | **ADDRESSED（设计）；但被 R-C-1 作废** | 单键 Lua 同时 `HSETNX`+无条件 `EXPIRE`（`:68-72`）：只要脚本跑到，就不存在"有键无 TTL"；单键 ⇒ Cluster 天然同槽。问题在于**脚本在生产上根本跑不到**（R-C-1） |
| **I-2** 抢位后发失败 ⇒ 永久 0 条 | **ADDRESSED** | `ToolCallEndTrace.java:63-73`：抢到位后 `emit` 抛 ⇒ `release` 再抛原异常；`release` 幂等、失败只 WARN（`SettlementObservationStore.java:133-158`）。方向正确（宁重不宁缺） |
| **I-3** 观测面 I/O 进 `submitLock` | **ADDRESSED** | `ConfirmGate.java:1639-1641`（回灌）/`:1689-1691`（放弃）都在 `synchronized` 块（`:1610`/`:1683`）**之外**；次序仍为落库 → 结局帧 → 唤醒 → end → 续跑派发；用例 `settlementIoRunsOutsideTheInstanceSubmitLock` 用"claim 阻塞 + 另一张卡提交"探锁，RED 侧抛 `TimeoutException` 证明确实钉住了锁 |
| **迁移排除** | **ADDRESSED（两端落地）** | `scripts/redis-run-migration.mjs` 排除清单 + `scripts/redis-run-migration.test.mjs` 断言 + `RunMigrationFamilyContractTest` 断言 `KEY_PREFIX` |
| M-1 空白锚点放行 | 未变（可接受） | `:109-112` |
| M-2 内存面无界 | 部分（`purge` 现已可清） | `:168-185`；生产恒有 Redis（`RunStore` 构造器强制），内存面是测试面 |
| M-4 `SessionKeyRegistry` 建议 | 未变；报告 §5 已补"会打挂 `hasSize(19)`"的提醒 ⇒ **这条 advice 现在是安全的** | — |
| M-6 让位/降级无计数器 | 未做；**正是 R-C-1 隐形的直接原因** | 见 §4.3 |
| M-5 RED 行号 | 已由报告 §2 的命名说明覆盖 | — |

## 3. 新发现

### R-C-1（Critical）Lua 的第 3 个 ARGV 是 `Long` ⇒ 生产上每次 `claim` 都抛 CCE 并被吞成 fail-open

**证据链（都跑过/读过）**：

1. 代码：`SettlementObservationStore.java:119-120`
   ```java
   Long claimed = this.redis.execute(OBSERVE_SCRIPT, List.of(key), toolCallId,       // String
           System.currentTimeMillis() + "|" + InstanceId.get(),                      // String
           ttlSeconds());                                                            // long → Long
   ```
   `ttlSeconds()` 返回 `long`（`:197-201`），进 `Object...` 自动装箱成 `Long`。
2. Spring 实现：`DefaultScriptExecutor.keysAndArgs(...)` 对每个 ARGV 调 `RedisSerializer.serialize(Object)`（我反编译了本地 `spring-data-redis-3.5.11.jar`，字节码里**没有** `toString()`），而 `RedisConnectionUtils`/`RedisTemplate` 传的是模板的 **valueSerializer**；`StringRedisTemplate` 默认就是 `StringRedisSerializer`。
3. `StringRedisSerializer` 的桥方法 `serialize(Object)` 里有 `checkcast java/lang/String`（字节码已核）⇒ **`Long` 直接 `ClassCastException`**。
4. 探针（3/3 绿）：真实 `StringRedisTemplate`（mock 连接工厂，命令根本不会发出）执行与 `claim` 同形的调用 ⇒ `ClassCastException`；`store.claim` 连调两次**都 true** ⇒ `catch` 把 CCE 当"依赖抖动"，fail-open。
5. 项目既有约定反证：所有其它 Lua 调用都显式 `String.valueOf(...)` —— `AiRateLimiter.java:133`、`LeaseService.java:553/558/589`、`RunStore.java:793-794`。本批是唯一一个传裸数字的。

**后果（生产、非降级路径）**：

- `claim` 恒返回 `true` ⇒ 三个发点全部照发 ⇒ **I-1 跨实例双记原形回归**（同 `toolCallId` 两条 `tool_call_end`、`readCalls=2`），I-2 仍因"决策侧一律参与"而表面正常；
- `ensureTtl` 永远执行不到（异常在它之前抛出）；
- 无任何日志噪音（只有 `log.debug`），`/health` 也看不见（没挂 `RedisDegradation`）⇒ 这个故障在现网是"静默的全量失效"。

**为什么 40 例全绿也发现不了**：`FakeObservationRedis` 把 `StringRedisTemplate.execute(...)` 整个桩掉并用 Java 模拟脚本语义 ⇒ **参数序列化从未发生**；`theClaimScriptCarriesBothHsetnxAndExpireOnASingleKey` 只 grep 脚本文本（含 `HSETNX`/`EXPIRE`/单 `KEYS[1]`），不校验 ARGV 类型。

**最小修法（1 行）**：`ttlSeconds()` 改为 `String.valueOf(ttlSeconds())` 传入（与其它 4 处调用同形）。

**必须同时补的回归位（否则同类问题还会回来）**：一条**真实模板**级用例（mock 连接工厂即可，如我用的探针）断言 `claim` 在 `StringRedisTemplate` 下能走到脚本；或退一步在现有脚本契约用例里用 `ArgumentCaptor<Object[]>` 断言"所有 ARGV 都是 `CharSequence`"。

### R-C-2（交付完整性）本 patch 单独没有调用点；调用点在 Task 3 fix1（已核实）

- 报告 §1 的 "C-1 已落地" 只对"**store 侧契约 + 迁移排除**"成立；报告 §3 也如实写了"未改 `AiSessionPurgeService`（Task 3 owns）"。
- 我核对了 Task 3 侧：**`task-3-fix1.patch` 确实有真实调用点**（不是空话）：
  - `AiSessionPurgeService.java:101`（补丁后行号）新增 `static final String OBSERVED_PREFIX = "ai:observed:"`，注写"不引用 Task 2 的类（未并入时编译会炸），单键 DEL 不存在的键是 no-op"；
  - `deleteObservabilityKeys`（补丁 hunk `+979,12`，即 `:982-987`）在既有三键 DEL 之后**单独 try** `redis.delete(OBSERVED_PREFIX + runId)`，失败 `errors.add("…结算观测位删除失败（可重试）")` ⇒ 进既有 `complete=false` + `SessionPurgeRetryRunner` 重试语义；
  - 两条**真服务**用例：`AiSessionPurgeRunIndexResidueTest.observedKeyOfThisSession'sRunsIsDeletedAndOtherSessionsAreUntouched`（本会话两轮删净、他会话不动）与 `observedKeyDeleteFailureIsReportedAsRetryableIncomplete`（DEL 失败 ⇒ `complete=false` + 错误含"结算观测位"）。
- **但**：Task 2 的 `SettlementObservationPurgeContractTest` 只测 store 的 `purge`，`purgeAll` 是测试内的本地 helper ⇒ **Task 2 的绿不能证明"销毁真的删了"**。两个 patch 无文件重叠（`comm -12` 为空，T2 13 件 / T3 17 件），可同批合并；一旦 Task 3 fix1 被丢弃或回退，C-1 会**静默失守而 Task 2 的 40 例仍全绿**。
- 结论：**本 patch 不能单独称"完成"**；签收口径必须是"Task 2 fix2b + Task 3 fix1 同批"。

## 4. exactly-once 的失败模型（用户点名的两个取舍）

### 4.1 `release` 之后"已写出行"导致的重复 vs 永久 0

- 代码现状（`ToolCallEndTrace.java:63-73`）：`emit` 抛任何 `RuntimeException` 都 `release`。
- 实际可达性（我逐个查了 `emit` 的抛出点）：
  - **写前**：`pairs(...)`（含 `settled.effectiveOutcome()/getSheetMeta()`）——测试用 `getSheetMeta()` 首次抛错钉的就是这一段 ⇒ 释放后重试 = 1 行，**没有重复**；
  - **写后**：`AiTrace.eventOn`（logback 内部吞 appender 异常）与 `audit.record`。后者在 sheet 面**不会抛**：`SheetAudit.appendLedger` 自己 `catch (RuntimeException) → WARN`（`SheetAudit.java:227-233`），计数器是 `AtomicLong` ⇒ 写后抛错只剩"日志框架自身炸"这个理论窗。
- 所以报告 §6.4 的"已写出行仍可能重复"是**诚实的理论残余**，不是现实高发路径；但**没有用例钉住这个边界**（现有用例只覆盖写前）。建议二选一：① 补一条"`audit` 抛错后不释放/释放"的显式用例把选择固化；② 干脆把 `pairs(...)` 提到 `claim` 之前（写前失败就不消费、不释放），把残余收窄成"只有写后抛错才重复"并写进注释。
- **是否违反"恰好一次"**：在**无故障路径上不违反**（`claim` 只放行一个，日志与计数同一临界区）；在"写后抛错"这一理论窗里会变成 2 —— 属于**已知且方向正确**的偏差（0 条比 2 条更糟），我判定**可接受**，但要求它被一条用例或一句"仅此一窗"的注释固定住，而不是留在报告里。

### 4.2 fail-open 的双发（Redis 不可用/抖动）

- 未变：`claim` 异常 ⇒ `true`。E1 抖动放行发一行 → Redis 恢复 → E2 抢位成功再发一行 = 2 行；或整体不可用 ⇒ 参与的 2~3 个执行体各发一行。
- **有界**：上界 = 参与发点个数（≤3），与重挂次数无关（"复用结果"分支仍不是发点，用例 `multiCardAnswered…` 末尾两次 `resolvePending` 仍 1 条）。
- **是否违反要求**：严格意义上**违反**（要求"恰好一条"），但这是"仲裁者在不可达时"的经典不可判定区：要 0 风险就得放弃可用性（fail-closed ⇒ 整片缺行）。当前选择（宁重不宁缺）与项目既有 `RunStore#markRequestEmitted` 同族，可接受，**前提是**：① 作为显式条款写进契约（"无故障恰好一次；Redis 降级 at-most-N 且永不缺行"），② 挂 `RedisDegradation` 计数让"降级发生了"可断言（见 R-I-4），③ 不把它当成"已实现 exactly-once"对外宣称。
- **注意叠加**：R-C-1 让"降级路径"变成了"生产唯一路径"，所以这 40 例的绿灯目前**不能**作为 exactly-once 的证据。

### 4.3 为什么"没有降级计数器"现在是 Important

`RedisDegradation` 就是为"降级发生了但只在 `log.debug` 里"建的（类注写明"日志会被轮询节拍刷掉，且无法按事件聚合"）。`claim` 的 fail-open 只 `log.debug`，所以 R-C-1 这种"每次 claim 都 CCE"的故障在现网**零信号**。建议加一个计数（如 `b2.observationClaim`），与既有 A1/A5/A6 同列。

## 5. Important / Minor

| # | 级别 | 位置 | 说明 |
|---|---|---|---|
| R-I-1 | Important | `ToolCallEndTrace.java:63-73` | `release` 的写后重复边界无用例钉住（§4.1）；建议 `pairs` 前移（写前失败不消费）+ 补一条写后用例，或写明"仅此一窗" |
| R-I-2 | Important | `AiSessionPurgeService.java:101`（T3） vs `SettlementObservationStore.java:58`（T2） | `ai:observed:` 两处**字面量双写**、无交叉校验：任一侧改名 ⇒ Task 3 删错键、C-1 静默回归而两边测试都绿。最便宜的钉子：Task 2 加一条 `assertThat(KEY_PREFIX).isEqualTo("ai:observed:")`，或在两者合并后用一条联合用例引用对方常量 |
| R-I-3 | Important | `SettlementObservationStore.java:121-122/187-195` | `ensureTtl` **冗余**：脚本每次 claim 都无条件 `EXPIRE`（已含"旧墓碑自愈"），变异实测关掉它 `aTombstoneWithoutTtlIsRepairedByTheNextClaim` 仍绿 ⇒ 纯多一次 RTT/claim，且该用例名在说谎（它钉的是脚本，不是自愈方法）。建议删 `ensureTtl` 并把用例名/注释改为"脚本无条件 EXPIRE ⇒ 有键必有 TTL" |
| R-I-4 | Important | `SettlementObservationStore.java:125-130` | fail-open 只有 `log.debug`，无计数器（§4.3） |
| R-M-1 | Minor | T3 `deleteObservabilityKeys` | Task 3 用 `redis.delete(prefix+runId)` 而**不是** `store.purge(runId)`，与 Task 2 §5"不要只 redis.delete（会丢进程内面）"的交接词相反。生产等价（内存面只在 `redis==null` 时才有数据，而那时销毁服务也没有 Redis），但两份契约文本应统一，否则下一个人无法判断哪条是准绳 |
| R-M-2 | Minor | `SettlementObservationPurgeContractTest` | 它是 store 契约，不是销毁集成证据（`purgeAll` 是本地 helper）。报告应明说"绑定要求的证明在 T3 的 `AiSessionPurgeRunIndexResidueTest`"，不要让人以为 Task 2 的 4 例覆盖了销毁面 |
| R-M-3 | Minor | `claim` 的 `claimed == null` | 视为首写者（放行）。生产 `execute` 不会返回 null，属防御式口径，可接受；但与"缺行比重行难发现"的方向相反，建议只在这一句注释里说明 |
| R-M-4 | Minor | 内存面 | `observed` 仍无淘汰；`purge` 存在但只有销毁方调用 ⇒ 无 Redis 的装配下按 run 累积（测试面，影响有限） |
| R-M-5 | Minor | 文档 | `docs/AI对话日志链路追踪手册.md` 只改了发点段落，未记 `release`/fail-open/purge 三条失败语义；`SettlementObservationStore` 类注有，手册没有。可选，但"手册是排障入口"的口径下建议补两行 |

## 6. 合并建议（结论）

1. **R-C-1 必须先修**（1 行 + 1 条序列化级用例）——不修就是**生产上 I-1 双记回归**、且测试面完全看不见。这是唯一的代码阻塞项。
2. **必须与 Task 3 fix1 同批合并**（R-C-2）：调用点、错误上报与两条真服务用例都在那边；单独合 Task 2 等于"交了一个没人调用的 helper"，绑定要求仍在失守状态。同批合并后 C-1 才算真正闭合。
3. 建议同批（不阻塞）处理 R-I-2（字面量钉子）、R-I-3（删冗余自愈）、R-I-4（降级计数）。
4. fail-open 双发与"写后重复"按 §4 的写法**显式记录为降级条款**，不要以"恰好一次"表述交付。
5. `expireFrontend` 残余按指示不扩；本批已在方法注与报告中如实登记，符合"不扩大范围"。

## 7. 复现（我实际执行的）

```bash
# 1) 补丁可应用性（实际 main 工作区）
cd <主仓>
git apply --check <仓外证据>/park-audit-fix2b.patch          # EXIT=0
# 反向：在打补丁后的树上
git apply -R --check <仓外证据>/park-audit-fix2b.patch      # 通过

# 2) 40 例（临时树 <隔离副本>/_rr2-task2）
cd backend && mvn -s maven-settings.xml -o -B test -Dtest="ParkSettlementOwnershipTest,\
SettlementObservationPurgeContractTest,ParkSettlementAuditTest,SheetSampleTraceTest,\
ConfirmGateSheetMetaPersistTest,ResumeToolStartBackfillConformanceTest,\
ParkToolStartOrderConformanceTest,RunMigrationFamilyContractTest" -DfailIfNoTests=false   # 40/40

# 3) 迁移 + 文档
node --test scripts/redis-run-migration.test.mjs                     # 52/52
node scripts/ci/check-docs.mjs                                       # 6/6

# 4) 变异：禁用 release ⇒ aFailedEmission… 确定性红（证据 <隔离副本>/_rr2-task2/mutation.log）
# 5) 探针：真实 StringRedisTemplate 下 Long ARGV ⇒ CCE ⇒ claim 恒 true
#    <隔离副本>/_rr2-task2/backend/src/test/java/.../ObservationArgvSerializationProbeTest.java（3/3 绿）
#    证据 <隔离副本>/_rr2-task2/probe-serialize.log
```
