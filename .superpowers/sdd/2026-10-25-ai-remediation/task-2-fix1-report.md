# Task 2 · fix1 报告（复审 C-1 / I-1 / I-2 收口）

**结论：DONE** —— C-1 绑定要求（销毁删除全部 AI 数据）已按"暴露 `observedKey`/`purge` + 可复用销毁契约测试 + 迁移排除落地"落地；`HSETNX`/`EXPIRE` 改为**单键原子脚本**并带确定性 TTL 自愈；抢位后发失败会**释放占位**；观测面 Redis I/O **移出 `submitLock`**。全部聚焦测试绿，补丁对**实际 main 工作区**可干净应用。

- **基线口径（按 controller 要求）**：实际 main = `<主仓>` 的**工作区**（`34fd8f4` + 未提交的 config/audit + 上一批 park audit 候选，其中 `ToolCallEndTrace` / `ParkSettlementAuditTest` 仍是未跟踪文件）。本报告不引用任何 clone SHA；交付补丁以该工作区为基线生成并 `git apply --check` 通过。
- 未改 main、未提交、未 push、未加依赖、未派子代理；**未改 `AiSessionPurgeService` / `RunStore` / `SessionKeyRegistry`**（Task 3 owns）。
- 测试口径：聚焦 + 5 个相邻类（含迁移契约），**共 40 例**；未跑全量。

## 1. 复审项 → 修法对照

| 复审项 | 修法 | 证据 |
|---|---|---|
| **C-1（Critical）** 销毁只等 TTL，违反"删除全部 AI 数据" | ① `SettlementObservationStore` 暴露 **`observedKey(runId)`** 与 **`purge(runId)`**（一次清 Redis 键 + 进程内面，返回 `boolean` 供调用方记 `errors`）；② 新增**可复用销毁契约测试** `SettlementObservationPurgeContractTest`（4 例，含"DEL 失败必须上报""不存在轮次幂等"）；③ 类注释/报告口径改为"**销毁必须删，TTL 只是兜底**"；④ 迁移排除项落地 | `SettlementObservationStore.java:98/168`、`SettlementObservationPurgeContractTest.java`、报告 §5 |
| **I-1** `HSETNX`+`EXPIRE` 非原子 ⇒ 永久键；`EXPIRE` 只试一次 | 改为**单键 Lua**：`HSETNX` 决定首写者、同脚本内无条件 `EXPIRE` ⇒ "有键无 TTL"在 Redis 内不可表达；脚本之外每拍再做一次 `ensureTtl` 自愈（补旧版本墓碑；失败只降级）。测试锁定脚本契约（含 `HSETNX`+`EXPIRE`、只有一把 KEYS）+ 墓碑自愈 + 修复重试 | `SettlementObservationStore.java:68-78/108-130`、用例 `theClaimScriptCarriesBothHsetnxAndExpireOnASingleKey` / `aTombstoneWithoutTtlIsRepairedByTheNextClaim` / `aFailingTtlAttemptDoesNotBreakTheClaimAndIsRetriedLater` |
| **I-2** 抢位后发失败 ⇒ 永久 0 条、无释放 | `ToolCallEndTrace.emitOnce`：抢到位后 `try { emit } catch (RuntimeException) { observations.release(...); throw }` —— 释放后下一位执行体/下一次结算仍可发（**宁重不宁缺**）。`release` 幂等、失败只 WARN | `ToolCallEndTrace.java:54-77`、用例 `aFailedEmissionReleasesTheMarkerSoALaterSettlementCanRetry` |
| **I-3** 观测面 I/O 进实例级 `submitLock` | `settleParkedToolCall` 两处（回灌 `:1640` / 放弃 `:1690`）移到 `synchronized (submitLock)` **块之外**；次序不变（落库 → 结局帧 → 唤醒 → end → 续跑派发）。用例用"claim 阻塞 + 另一张卡提交"探测锁占用 | `ConfirmGate.java:1640/1690`、用例 `settlementIoRunsOutsideTheInstanceSubmitLock` |
| **I-4**（存量）park 后前端**超时** 0 条 | 按指示**不扩**（`expireFrontend` 不属本批）。改的是**措辞**：`settleParkedToolCall` 方法注如实写明"三类发点覆盖已决/已执行；超时终态仍缺（已知边界）" | `ConfirmGate.java` 方法注 |
| 迁移排除清单（两端都要落地，Task 3 已退回） | `scripts/redis-run-migration.mjs` 的 `EXCLUDED_KEY_PREFIXES` 加 `ai:observed:`（带理由注释）；`scripts/redis-run-migration.test.mjs` 的"必须排除"清单同步；`RunMigrationFamilyContractTest` 增一条 `contains(...SettlementObservationStore.KEY_PREFIX)` 断言。**未碰 `SessionKeyRegistry`**（Task 3 owns；若 Task 3 收录，见 §6 注意项） | `node --test scripts/redis-run-migration.test.mjs` **52/52**；`RunMigrationFamilyContractTest` 2/2 |

## 2. RED → GREEN

### RED（先证，再改生产）

RED 基底 = 实际 main 工作区 + 上一批 park audit 候选（= Task 2 修复前）。新增用例只用到当时存在的 API，因此可直接红。命令：

```bash
cd <tree>/backend
mvn -s maven-settings.xml -B test -Dtest=ParkSettlementOwnershipTest -DfailIfNoTests=false
```

结果（`<仓外证据>/task2-fix1-evidence/red-before-fix.log`）：

```
[ERROR] Tests run: 7, Failures: 2, Errors: 1
[ERROR] aFailedEmissionReleasesTheMarkerSoALaterSettlementCanRetry
        [占位已释放 ⇒ 重试必须真正发一次] ["readCalls"=0L (expected: 1L)]      ← 复审 I-2（永久 0 条）
[ERROR] anObservedMarkerWithoutTtlIsRepairedByALaterClaim
        Wanted but not invoked: redis.expire(...)                              ← 复审 C-1/I-1（TTL 从不修复）
[ERROR] settlementIoRunsOutsideTheInstanceSubmitLock
        java.util.concurrent.TimeoutException                                  ← 复审 I-3（锁内 I/O 挡住第二张卡）
```

> 命名说明：RED 时那条 TTL 用例叫 `anObservedMarkerWithoutTtlIsRepairedByALaterClaim`，修复后按新语义拆成
> `aTombstoneWithoutTtlIsRepairedByTheNextClaim`（墓碑自愈）+ `theClaimScriptCarriesBothHsetnxAndExpireOnASingleKey`（原子脚本契约）
> + `aFailingTtlAttemptDoesNotBreakTheClaimAndIsRetriedLater`（修复重试）。另两条用例名未变。
> **purge 无法做行为 RED**（API 本就不存在），其 RED 依据是复审 C-1 的探针（"有键无 TTL + 永不补 EXPIRE"），本批用上述第二、三条用例把同一根因转成长期回归位。

### GREEN（聚焦 + 相邻）

```
mvn -s maven-settings.xml -B test -Dtest="ParkSettlementOwnershipTest,SettlementObservationPurgeContractTest,\
ParkSettlementAuditTest,SheetSampleTraceTest,ConfirmGateSheetMetaPersistTest,\
ResumeToolStartBackfillConformanceTest,ParkToolStartOrderConformanceTest,RunMigrationFamilyContractTest" \
 -DfailIfNoTests=false
```

`<仓外证据>/task2-fix1-evidence/green-focused-and-adjacent.log`：**`Tests run: 40, Failures: 0, Errors: 0`，BUILD SUCCESS**

| 类 | 例 | 说明 |
|---|---|---|
| `gate.ParkSettlementOwnershipTest` | 11 | 原 4 例（I-1 跨实例不双记 / I-2 多卡乱序不少记 / 原语首写 / 原 Redis 面）+ 7 例：原子脚本契约、墓碑自愈、修复重试、fail-open、发失败释放、锁外 I/O、release 重抢 |
| `gate.SettlementObservationPurgeContractTest`（新） | 4 | 销毁契约（Redis+内存两面、失败上报、幂等），**Task 3 可直接复用** |
| `gate.ParkSettlementAuditTest` | 9 | 上一批 9 例全绿（载荷/计数/重复回灌/复用不放大/dismiss/executeOnce/inline 对照/非 park 对照） |
| `gate.SheetSampleTraceTest`（相邻） | 6 | inline 载荷与台账逐字不变 |
| `gate.ConfirmGateSheetMetaPersistTest`（相邻） | 2 | 决策写回条目上的 `sheetMeta` 仍在 |
| `conformance.ResumeToolStartBackfillConformanceTest`（相邻） | 4 | 续跑补 `tool_start` 与结算先后不变 |
| `conformance.ParkToolStartOrderConformanceTest`（相邻） | 2 | park 帧序不变 |
| `run.RunMigrationFamilyContractTest`（相邻，迁移契约） | 2 | 排除清单含 `ai:observed:`，10 族不变 |

其它门禁：`node --test scripts/redis-run-migration.test.mjs` → **52/52**；`node scripts/ci/check-docs.mjs` → **6/6**（含新文件的命名纪律扫描）。

## 3. 精确改动文件（相对实际 main 工作区，13 件 +1155/−47）

```
backend/src/main/java/com/example/configmgr/ai/observe/SettlementObservationStore.java   | 203 +（新）
backend/src/main/java/com/example/configmgr/ai/gate/ToolCallEndTrace.java                 |  73 +-
backend/src/main/java/com/example/configmgr/ai/gate/ConfirmGate.java                     |  82 +-
backend/src/main/java/com/example/configmgr/ai/gate/SpToolCallingManager.java            |  25 +-
backend/src/test/java/com/example/configmgr/ai/gate/ParkSettlementOwnershipTest.java     | 553 +（新）
backend/src/test/java/com/example/configmgr/ai/gate/ParkSettlementAuditTest.java         |   6 +
backend/src/test/java/com/example/configmgr/ai/gate/FakeBlockingTool.java                |  46 +（新）
backend/src/test/java/com/example/configmgr/ai/gate/FakeObservationRedis.java            | 107 +（新）
backend/src/test/java/com/example/configmgr/ai/gate/SettlementObservationPurgeContractTest.java | 97 +（新）
backend/src/test/java/com/example/configmgr/ai/run/RunMigrationFamilyContractTest.java   |   4 +-
docs/AI对话日志链路追踪手册.md                                                            |   2 +-
scripts/redis-run-migration.mjs                                                          |   3 +
scripts/redis-run-migration.test.mjs                                                     |   1 +
```

**未改**：`RunStore`、`SessionKeyRegistry`、`AiSessionPurgeService`、`AiConfig*`、`ResumeService`、`SheetAudit`、前端。
**补丁**：`<仓外证据>/park-audit-fix2b.patch`
- 基线 = 实际 main 工作区（`34fd8f4` + config/audit + park audit 候选）；
- 对实际 main 工作区 `git apply --check` → **EXIT=0**；
- 反向 `git apply --check -R` 在本树通过 ⇒ 补丁与"基线 → 最终树"逐字对应；
- 说明：`ToolCallEndTrace` / `ParkSettlementAuditTest` 在 main 工作区是**未跟踪文件**，补丁对它们是 modify hunk（`git apply` 可改工作区文件）；新文件 4 件为 add hunk。

## 4. 行为与不变量（本批之后）

| 面 | 结论 |
|---|---|
| exactly-once（≤1） | `ToolCallEndTrace#emitOnce` 是唯一发口（`emit` 已降为 private）；`CONFIRM`/`FRONTEND`（含 `kind=null` 旧数据）先抢 `ai:observed:<runId>` 的 field，抢到者发一行 **并**记一次 `SheetAudit`，同一抢占内 ⇒ 日志与计数恒同步 |
| ≥1（覆盖） | 三类发点：决策/放弃胜者（`ConfirmGate`）、内联批循环、续跑认领胜者。**park 后前端回执超时终态（`expireFrontend`）仍缺** —— 存量，未扩 |
| 失败方向 | 抢位后发失败 ⇒ 释放占位 ⇒ 后续可重试（宁重不宁缺）；Redis 整体不可用 ⇒ fail-open（可能双记，不缺行） |
| 普通 inline | 未注入 store 时 `emitOnce` 直接发，与改造前同形；载荷键序/取值零改动 |
| 业务副作用 | 零变更（只碰观测面；决策/认领/台账/幂等/续跑路径未改） |
| 放大 | 续跑"复用结果"分支仍**不是**发点（重挂 N 次 0 次新增） |
| 锁 | 观测面 Redis I/O 在 `submitLock` 之外；落库/结局帧/唤醒次序不变，end 在续跑派发之前 |

## 5. 交 Task 3 的集成契约（精确）

**组件**：`com.example.configmgr.ai.observe.SettlementObservationStore`（`@Component`，构造注入 `ObjectProvider<StringRedisTemplate>` + `AiProperties`）。

| 成员 | 签名 | 语义 |
|---|---|---|
| 键名 | `public String observedKey(String runId)` | 返回 `"ai:observed:" + runId`（裸前缀、无 `{run:}` tag；HASH，field=`toolCallId`，value=`<atMs>\|<instanceId>`） |
| 销毁 | `public boolean purge(String runId)` | 删 Redis 键 + 进程内面；`true`=清干净（无 Redis 也算），`false`=**Redis DEL 失败**（内部已 WARN） |
| 仲裁 | `public boolean claim(String runId, String toolCallId)` | 首写者 `true`；已观测 `false` |
| 释放 | `public void release(String runId, String toolCallId)` | 发失败回退占位（`emitOnce` 内部调用；幂等、不抛） |
| 前缀常量 | `public static final String KEY_PREFIX = "ai:observed:"` | 仅用于日志/断言拼串 |

**销毁接线（Task 3 在 `AiSessionPurgeService#deleteObservabilityKeys(String runId, List<String> errors)` 里加）**：

```java
// 结算观测位（Task 2）：与 beat/activity/cancel 同列 —— TTL 只是兜底，销毁必须删
if (!this.settlementObservations.purge(runId)) {
    errors.add("run[" + runId + "]: 结算观测位("
            + SettlementObservationStore.KEY_PREFIX + runId + ")删除失败（可重试）");
}
```

- 依赖注入：与既有 `runStore`/`cancellations` 同体例（构造参数，或 `@Autowired(required = false)` setter；组件无 Redis 时退化为进程内面，`purge` 仍清）。
- 失败语义：`purge` 返回 `false` ⇒ 记进 `errors` ⇒ 既有 `SessionPurgeRetryRunner` 重试；**不要**只 `redis.delete(List.of(...))` 而丢掉进程内面（轻装配/单测下那是唯一一份）。
- 可复用测试：`backend/src/test/java/com/example/configmgr/ai/gate/SettlementObservationPurgeContractTest.java`（含 `purgeAll(store, runIds)` 的接线形状与错误串样例）+ 夹具 `FakeObservationRedis`。
- **键的生命周期**：TTL = `app.ai.session-ttl`（脚本内原子置；每拍 `ensureTtl` 自愈）；**会话销毁显式 DEL**；迁移**不搬**（已在 `redis-run-migration.mjs` 排除清单与两处契约测试落地）；降级 fail-open。
- 若 Task 3 决定把它登记进 `SessionKeyRegistry`：请用 `Renewal.WRITE`（不挤占心跳 5 / 读侧 5），并同步 `SessionKeyRegistryTest` 的 `hasSize(19)` 与前缀清单断言（复审 M-4）。

## 6. 关注点 / 残余（如实登记）

1. **fail-open 的非对称窗口**（复审 §3.3）：E1 抢位遇抖动（fail-open 发一行）→ Redis 恢复 → E2 抢位成功再发一行 = 双记。方向正确（不缺行），但只有 `log.debug`，`/health` 看不见；按复审建议可挂 `RedisDegradation` 计数器（本批未做，避免扩大面）。
2. **park 后前端回执超时**（`expireFrontend`）仍 0 条 end（存量，复审 I-3/§2）；本批只在注释与报告中写明，不修。
3. **进程死在"公布请求帧"与"park 退出"之间**：决策侧当时可能 `woke=true` 未发，该条 end 仍可能缺（不引入跨世代补发）。
4. **`release` 会把已写出的行留成"重复行"**：仅当失败发生在 `AiTrace.eventOn` 之后（如台账面抛错）——这是"宁重不宁缺"的代价，方向优于永久 0 条。
5. **测试类数**：新增 2 个测试类（`ParkSettlementOwnershipTest` 沿用上一批、`SettlementObservationPurgeContractTest` 为本轮新增的销毁契约类）+ 1 个夹具 `FakeObservationRedis`；相邻跑了 5 个类（含 brief 未列的 `RunMigrationFamilyContractTest`，因迁移排除项要求两端落地）。
6. **E2E 未跑**（禁起服务）；`AIE2E-T-13` 的等价证据是单测级同链路（`readCalls`/end 恰 1）。

## 7. 复跑指引

```bash
# 1) 应用补丁（基线 = 实际 main 工作区）
cd <主仓>
git apply --check <仓外证据>/park-audit-fix2b.patch     # EXIT=0（只读核对）
git apply <仓外证据>/park-audit-fix2b.patch

# 2) 聚焦 + 相邻（40 例）
cd backend && mvn -s maven-settings.xml -B test \
  -Dtest="ParkSettlementOwnershipTest,SettlementObservationPurgeContractTest,ParkSettlementAuditTest,\
SheetSampleTraceTest,ConfirmGateSheetMetaPersistTest,ResumeToolStartBackfillConformanceTest,\
ParkToolStartOrderConformanceTest,RunMigrationFamilyContractTest" -DfailIfNoTests=false

# 3) 迁移脚本 + 文档门禁
node --test scripts/redis-run-migration.test.mjs      # 52/52
node scripts/ci/check-docs.mjs                        # 6/6

# 证据
#   <仓外证据>/task2-fix1-evidence/red-before-fix.log
#   <仓外证据>/task2-fix1-evidence/green-focused-and-adjacent.log
```
