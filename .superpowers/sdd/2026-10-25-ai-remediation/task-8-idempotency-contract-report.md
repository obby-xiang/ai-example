# Task-8 幂等契约收口：STALE_FENCE 不写共享帧，但不吞结果（修复报告）

- 日期：2026-10-25
- 基线：当前 main 工作区（`2eb2539` + 未提交候选；本次未提交、未推送）
- 隔离副本：`<隔离副本>/_t8-idem-contract`（从 main 工作区 `backend/src` + `backend/pom.xml` 复制，`git init` 后先提交基线再改，patch 由该基线生成）
- 交付物：`task-8-idempotency-contract.patch`（3 文件，`+93 / −21`；`git apply --check` 对 main 工作区通过）
- 范围：只动 `InMemoryRunStore`（测试替身）、`ToolIdempotencyTest`（I5）、`SpToolCallingManager` 的两处注释。**未改** `ConfirmGate` / `ResilientChatService` / `SseChatEmitter` / `RunStore` 生产行为 / 前端 / 现有 docs。

## 0. 结论

原失败是**两个问题叠在一起**：

1. 测试替身 `InMemoryRunStore` 没有覆写 `RunStore#fenceEpoch`，落到生产读口去 `GET ai:fence:run:<runId>`；替身的 `redis` 为 `null` ⇒ NPE。NPE 被写门的 fail-closed 分支吞成"fence 读不出来 ⇒ 只读"，于是旧驱动的所有业务帧被丢弃 —— **丢帧理由是"读口坏了"，不是"fence 越过本进程 epoch"**，缺陷被掩盖。
2. 旧 I5 断言"stale driver 仍发一条 `tool_result` 共享帧"，与 RT-21 写门（`fence > epoch` ⇒ 拒写事件/台账/取号）正面冲突。写门落地后，该断言必然红。

按控制者裁决做最小修复：**旧驱动不得写共享事件/帧（无豁免），但 `settleBlocked` 返回的 BLOCKED 文本与机器可读结局必须沿原工具调用链回到模型**。修复后 I5 同时钉住五件事：零真实执行、零台账、无旧驱动 `tool_result` 共享帧、模型侧拿到 blocked + 独立文案、条目保持 BLOCKED 且不伪造实例。

## 1. 契约裁决（本批口径）

- 失去续跑权的旧驱动（`HELD but fence > 本进程 epoch`）**不得写共享事件/帧**；不给它任何"结算必须发帧"的豁免。
- **不得**因此吞掉结果：`settleBlocked` 的返回值（`BLOCKED` 文本 + `outcome/code`）仍进入本批 `ToolResponseMessage`，模型据此告诉用户"本次未执行、需由接管方完成"，不得让模型以为成功。
- **不允许**放宽 `SseChatEmitter` / `RunStore` 写门；**不允许**只删旧帧断言转绿；**不允许**给旧驱动加共享帧豁免。

## 2. 原失败根因与复现（基线 RED）

命令（Maven 只用 IDEA 路径；隔离副本 `backend/` 下，离线）：

```
"<本机工具链>/JetBrains/IntelliJ IDEA/plugins/maven-plugin/lib/maven3/bin/mvn.cmd" \
  -o -q -Dtest=ToolIdempotencyTest -DfailIfNoTests=false test
```

结果：`rc=1`，`Tests run: 5, Failures: 1`，唯一失败：

```
ToolIdempotencyTest.staleFenceHasIndependentConclusionAndText:210
[帧类型 tool_result 恰好一帧（实际帧流=[]）] Expected size: 1 but was: 0 in: []
```

同一份日志里出现 **5 次** 同一根因（被 fail-closed 吞掉的替身 NPE）：

```
WARN RunStore -- 读取 fence 失败 runId=run-idem（认领侧 fail-closed：拒绝认领，不按 fence=0 放行）：
Cannot invoke "org.springframework.data.redis.core.StringRedisTemplate.opsForValue()" because "this.redis" is null
```

即：`RunStore#permittedToWrite` 的 `catch (RuntimeException)` 把 NPE 当成"fence 读不出来"⇒ 丢弃帧。旧断言因此在**错误理由**下变红（不是"旧驱动没有写权"），修测试而不修替身会让它以"读口坏了"这个错误理由变绿。

证据日志：`<隔离副本>/_t8-idem-clean-baseline.log`。

## 3. 改动清单

| # | 文件 | 改动 | 生产行为 |
|---|---|---|---|
| 1 | `backend/src/test/java/com/example/configmgr/ai/conformance/InMemoryRunStore.java:298-313` | 新增 `@Override fenceEpoch(runId)`：读替身自己的 `fenceEpochs`（`save` 持租时推进、`seedFence` 铺语料；装配 `leaseFacility` 时 epoch 来自设施）。与 `tryClaimExecution` 读同一张表 | 测试替身，无生产影响 |
| 2 | `backend/src/test/java/com/example/configmgr/ai/gate/ToolIdempotencyTest.java:199-269` | I5 重写（见 §4 对照表），类注同步更新；保留"旧契约要求旧驱动发共享帧"的缺陷背景为反例注释 | 无 |
| 3 | `backend/src/main/java/com/example/configmgr/ai/gate/SpToolCallingManager.java:1718-1733` | `settleBlocked` javadoc：从"发一条 ok=false 结局帧"改为"只**请求**帧，是否落档由写门判定；旧驱动无帧豁免；文本与 outcome/code 仍沿工具链返回" | **仅注释** |
| 4 | 同上 `:1854-1857` | `resolvePending` 内联注释："都会发 tool_result"→"都会**请求**发（旧驱动那条会被写门丢弃），补帧同样受写门拦截" | **仅注释** |

## 4. I5 断言 ↔ 契约对照

| 契约 | 断言 |
|---|---|
| 零真实执行 | `tool.invocations == 0` |
| 零台账 | `store.ledger(RUN_ID).isEmpty()`，且 `claimedBy == null`（STALE_FENCE 不占认领） |
| 没有旧驱动共享帧（RT-21） | `wire.types()` 不含 `tool_result`；`store.archivedTypes(RUN_ID)` 不含 `tool_result` |
| 结果不被吞、不伪装成功 | 模型 `ToolResponseMessage`（按 `toolCallId` 定位）：含 `"ok":false`、`"outcome":"blocked"`、`TOOL_ALREADY_CLAIMED`、文案"本轮写回权已转移，本次未执行"；不含"实例 null"、不含成功文案 |
| pending 保持 BLOCKED、不伪造实例 | `status=BLOCKED`、`effectiveOutcome=BLOCKED`、`effectiveCode=TOOL_ALREADY_CLAIMED`、`executed=false`、`executedBy=null`、`resultText` 同文 |
| 丢帧理由是写门而非替身故障 | `store.fenceEpoch(RUN_ID) == 2`（替身读口与生产同语义，不再 NPE）+ `permittedToWrite(...) == false` |

> 原缺陷/旧契约的背景**保留在测试注释里**（"旧实现让旧驱动照常发一条 tool_result 共享帧…与写门直接冲突"），未用"删断言"冒充修复。

## 5. 验证证据（全部真实执行）

### 5.1 修正后：目标 3 类全绿（含写门存在）

```
mvn.cmd -o -q -Dtest='ToolIdempotencyTest,LeaseLossReadOnlyTest,RunStoreFenceAheadWriteGateTest' \
  -DfailIfNoTests=false test
rc=0
Tests run: 5, Failures: 0, Errors: 0 -- ToolIdempotencyTest
Tests run: 7, Failures: 0, Errors: 0 -- LeaseLossReadOnlyTest
Tests run: 3, Failures: 0, Errors: 0 -- RunStoreFenceAheadWriteGateTest
```

日志同时给出"丢帧理由正确"的现场：

```
WARN RunStore -- 续跑权已失效：拒绝事件帧 runId=run-idem（fence=2 已推进到本实例 epoch=1 之上…）
```

并且该次日志中 **不再出现** `读取 fence 失败 runId=run-idem`（替身 NPE 已消除）。
证据：`<隔离副本>/_t8-idem-final-green.log`、`<隔离副本>/_t8-idem-green-3classes.log`。

### 5.2 生产侧旧驱动证据（复用，未重复大改）

`LeaseLossReadOnlyTest#fenceAheadWithoutRenewalDropsProductionEmitterFrames`（真实 `RedisFrameStore` + 两个 `RunStore` 实例：A 持租、B 接管推进 fence、A 仍 HELD ⇒ A 的 `delta` 不落档、不取号）与 `RunStoreFenceAheadWriteGateTest`（台账/事件/取号三处写门）均在同一命令内通过，无需新增生产红绿。

### 5.3 RED-1：移除替身 `fenceEpoch` 覆写 ⇒ NPE（证明"不是改测试天然绿"）

在已应用新测试的副本中仅删除 `InMemoryRunStore#fenceEpoch`，跑 I5：

```
-Dtest=ToolIdempotencyTest#staleFenceHasIndependentConclusionAndText
rc=1  Tests run: 1, Failures: 0, Errors: 1
Caused by: java.lang.NullPointerException: Cannot invoke
  "org.springframework.data.redis.core.StringRedisTemplate.opsForValue()" because "this.redis" is null
```

（`fenceEpoch` 直接断言先失败，故不会出现"帧为空也算过"的假绿。）随后恢复覆写。
证据：`<隔离副本>/_t8-idem-clean-red1-npe.log`。

### 5.4 变异验证：临时关掉写门 ⇒ 旧驱动共享帧出现、断言失败

仅把 `SseChatEmitter#emit` 的写门条件临时改为 `if (false && …)`（一行变异，未动任何语义代码），跑 I5：

```
rc=1  Tests run: 1, Failures: 1
[旧驱动不得发 tool_result 共享帧（含实时扇出）]
  ["start", "suspended", "tool_start", "tool_result", "done"]
```

即：写门存在 ⇒ 无 `tool_result`；写门移除 ⇒ 旧驱动的 `tool_result` 立刻出现，对应用例失败。变异后已逐字节恢复（`git status` 仅剩 3 个预期文件）。
证据：`<隔离副本>/_t8-idem-clean-mutation-gateoff.log`。

## 6. 复现步骤

1. 以当前 main 工作区为基线建隔离副本，`git init` 并提交基线（仅 `backend/src`、`backend/pom.xml`）。
2. 打 `task-8-idempotency-contract.patch`（`git apply --check` 先验证）。
3. 在副本 `backend/` 下依次跑：
   - 基线（未打 patch）：`-Dtest=ToolIdempotencyTest` ⇒ 5 跑 1 败（frames=[]，日志含 5 次替身 NPE）；
   - 打 patch 后：`-Dtest='ToolIdempotencyTest,LeaseLossReadOnlyTest,RunStoreFenceAheadWriteGateTest'` ⇒ 15 全绿；
   - 可选变异：删 `fenceEpoch` 覆写 ⇒ NPE；关写门 ⇒ `tool_result` 出现且断言红（验证后恢复）。

## 7. 未验证边界（如实列出）

1. **未跑全量、未 `clean`**：按要求只跑上述 3 类（15 个用例），其余后端用例未在本棒复跑；全量唯一失败的前提来自交付前批次的全量日志（`_p2-review/final-backend-full-preflight.log`）。
2. **替身的 fence 不跨实例共享**：`InMemoryRunStore#fenceEpoch` 读本实例的 `fenceEpochs`；两个替身共享一份 Redis 的场景未建模。跨实例 fence 推进由生产 `RunStore` 的 `LeaseLossReadOnlyTest` / `RunStoreFenceAheadWriteGateTest` 覆盖（已绿）。
3. **未验证真实集群 / 双 JVM / E2E**：旧驱动在新主人接管后的端到端表现（多实例 SSE、事件日志竞争）未在本棒复跑；本棒证据限于单 JVM 的替身 + 生产 `RedisFrameStore` 单测。
4. **未提交、未推送**：主仓工作区只新增本报告与 patch 文件；源代码改动仅存在于隔离副本与该 patch 中。
5. **并发副本风险（重要）**：`<隔离副本>/_t8-settle-final` 在本轮期间被**另一个 agent 同时改写**（`ConfirmGate` / `ResilientChatService` / 其他测试在 22:50-22:52 有变更）。那个副本里的 `task-8-settlement-final.patch`（22:52:41 生成）**已经吸收了本报告这 3 个文件的同源 hunk**（`fenceEpoch` 覆写、I5 重写、`settleBlocked` 注释）。合并时请**二选一**，不要叠加应用两份 patch（同一 hunk 会冲突/重复）。本报告的全部证据来自独立的 `<隔离副本>/_t8-idem-contract`（基线只含 main 工作区的这 3 个文件、经 `git apply --check` 验证），不依赖那个共享副本。
