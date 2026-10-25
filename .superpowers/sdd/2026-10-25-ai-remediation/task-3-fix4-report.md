# Task 3 Fix-4 报告：complete 之后的一次有界"记忆面"复核（RENAME 生效后写者 crash 的残留补偿）

**结论：DONE**（3 个生产文件 + 2 个测试文件；API 语义不变；RED 当前 main 上确定性复现；增量补丁可干净应用、行尾保持）

- 工作副本：`<隔离副本>/_verify-20261025-session-integrity/fix4-work`（= 当前 main 的 `backend/src`，main 已含 fix3）
- RED 基线：`<隔离副本>/_verify-20261025-session-integrity/fix4-base`（当前 main 原样 + 仅本轮测试改动）
- 补丁应用验证树：`.../patchcheck-fix4b`（当前 main + `task-3-fix4.patch`）
- 交付：`.superpowers/sdd/2026-10-25-ai-remediation/task-3-fix4.patch`（**增量**，基线 = 当前 main，5 文件，27,551 字节）
- 未改 main、未提交/推送、未跑全量、未派子代理；Maven 仅
  `<本机工具链>/JetBrains/IntelliJ IDEA/plugins/maven-plugin/lib/maven3/bin/mvn.cmd`，不 clean。
- 未触碰 `RunStore` / `ResumeService` / `ResilientChatService` / `ConfirmGate`；未改 guard TTL / `session-ttl` 配置 / 读侧语义。

---

## 1. 未覆盖窗口（已证实）与补偿设计

### 1.1 窗口（写侧无法闭合）

`RedisChatMemoryRepository#saveAll` 的守卫复检在 `finally` 里（fix1 的 I2 修复），但**JVM 死/线程被 `Error`
打断**时 `finally` 里的复检根本不会完成：RENAME 已在服务端生效、历史落在 `chat:mem:{sid}` 上，
而写索引与撤销都没发生。此时销毁可能**早已报 `complete=true` 且移除了 pending** —— 残留只能等 TTL。

确定性证据（无 sleep/无线程运气）：`RedisChatMemoryWriteBackRaceTest#crashBeforeTheFinallyRecheckLeavesResidueThatNoWriterWillUndo`
—— 在 `afterStageWrite` 这一拍把守卫置 armed 并让复检点抛 `OutOfMemoryError`（`purgeArmedAfterWrite`
只 catch `RuntimeException`），断言最终键**仍在**、索引里没有它。

### 1.2 补偿：一次短期、有界、只碰记忆面的复核（复用 pending/runner）

| 环节 | 行为 |
|---|---|
| complete 时 | 不再立刻 `pendingStore.complete(...)`，改为 `pendingStore.scheduleVerify(sessionId, purgeId, now + MEMORY_VERIFY_DELAY_MS)`：台账保留，`nextAtMs = verifyAtMs = now + 60s`（`MEMORY_VERIFY_DELAY_MS` 静态常量，**不新增配置面**） |
| API 语义 | `PurgeResult.complete=true` / `RetryReport.completed` 的含义不变："本次删除主体已完成"。差异只在**内部**台账生命周期（多背一次到点复核） |
| 到点 | 既有 runner 的 `listDue` 到期取到该台账；`verifyAtMs != null` ⇒ 只调 `AiSessionPurgeService#verifyMemoryOnly`，**不**重跑整轮 `retry` |
| 复核内容 | 守卫复核（`guard.isArmed`，**读失败一律不删**）→ 记忆面 delete（tagged/legacy）+ staging 扫描/单键 DEL + 删后复核（`hasLiveMemory` + 再扫 staging）→ 成功才 `pendingStore.complete` |
| 失败 | 沿用 `recordFailure` 的指数退避（10s…300s 封顶）与 `MAX_ATTEMPTS=10`；`recordFailure`/`defer` **保留 `verifyAtMs`**，所以后续仍是记忆面复核（不会退化成再删 DB 的整轮 retry）；耗尽后既有逻辑只重排、不再自动执行 |
| 绝不碰 | DB、附件、上下文、会话键、run 级 13 键、业务数据 —— 复核路径只调记忆面删除与台账完成 |
| 兼容 | `Pending` 新增 additive `Long verifyAtMs`（`@JsonInclude(NON_NULL)`：普通台账 JSON 形态逐字不变）；新增**7 参兼容构造**，既有 `new Pending(...)` 调用点零改动；老 JSON 无该字段 ⇒ `null` = 普通台账（有专门用例锁住） |

改动文件（3 生产 + 2 测试）：

```
backend/src/main/java/com/example/configmgr/ai/session/SessionPurgePendingStore.java  +61 -4
backend/src/main/java/com/example/configmgr/ai/session/AiSessionPurgeService.java     +94 -4
backend/src/main/java/com/example/configmgr/ai/session/SessionPurgeRetryRunner.java   +40 -0
backend/src/test/java/com/example/configmgr/ai/session/AiSessionPurgeServiceTest.java +127 -3
backend/src/test/java/com/example/configmgr/ai/memory/RedisChatMemoryWriteBackRaceTest.java +33 -0
```

关键落点：`SessionPurgePendingStore.java:299`（`scheduleVerify`）、
`AiSessionPurgeService.java:119`（`MEMORY_VERIFY_DELAY_MS`）、`:445`（complete 分支改排程）、
`:833`（`deleteMemoryKeys` 共用记忆面删除）、`:895`（`verifyMemoryOnly`）、
`SessionPurgeRetryRunner.java:187`（复核态分支）。

---

## 2. RED → GREEN（真实日志）

测试只动 3 个类（≤3 上限）：`AiSessionPurgeServiceTest`（27 例）、
`RedisChatMemoryWriteBackRaceTest`（6 例）、`SessionPurgeRetryRunnerTest`（1 例，作为第 3 类跑回归）。

| 用例 | 性质 | 当前 main（fix3） | 修复后 |
|---|---|---|---|
| `completedPurgeKeepsAOneShotVerifyPendingForCrashResidue` | **RED** | ❌ `[complete 后必须保留一次记忆复核台账…] expected not null`（台账被立即移除） | ✅ |
| `crashResidueIsRemovedOnlyAfterTheOneShotVerifyAndDbAttachmentsBusinessAreUntouched` | **RED** | ❌ `[到点后崩溃残留必须删净]`（台账已无，runner 无事可做；残留留到 TTL） | ✅ |
| `failedVerifyKeepsThePendingAndSucceedsAfterTheFailureClears` | **RED** | ❌ `failed==1` 不成立（没有复核可失败） | ✅ |
| `legacyPendingJsonWithoutVerifyFieldIsTreatedAsANormalRetry` | **RED**（老 JSON 兼容 + 路由） | ❌ `[retry 完成后转入一次性记忆复核]` | ✅ |
| `failedPurgeIsRetriedByRunnerToCompletionAndGuardEntersRetention` / `runnerRecoversOrphanPendingWhenQueueEntryWasLost` / `secondPurgeDatabaseFailureKeepsFirstRoundPendingAttemptsAndGuard` | **既有用例的断言按新契约更新**（"完成后 pending 移除" → "完成后保留一次性复核台账"；3 处，均在报告 §3 说明） | ❌ 3 条 | ✅ |
| `crashBeforeTheFinallyRecheckLeavesResidueThatNoWriterWillUndo`（race 类） | **证据用例**（证明窗口真实存在；修复前后都应为绿，修复不改变写侧） | ✅ | ✅ |

- **RED**：`Tests run: 33, Failures: 7`（`logs/fix4-red.log`；race 证据用例 6/6 绿）
- **GREEN**（工作副本）：27 + 6 = `Tests run: 33, Failures: 0`；runner 类 1/1
- **GREEN（**从补丁应用后的树**重跑，交付物自证）**：`Compiling 306 + 445 source files` ⇒
  `Tests run: 34, Failures: 0` / `BUILD SUCCESS`（`logs/fix4-green-from-patch.log`）

命令：

```bash
export JAVA_HOME="<本机工具链>\Java\jdk-21.0.12"
"<本机工具链>/JetBrains/IntelliJ IDEA/plugins/maven-plugin/lib/maven3/bin/mvn.cmd" -o -B \
  -Dtest='AiSessionPurgeServiceTest,RedisChatMemoryWriteBackRaceTest,SessionPurgeRetryRunnerTest' test
```

---

## 3. 契约变化（仅内部台账，不是 API）

- **pending 生命周期**：`complete=true` 的销毁/重试不再立刻删除台账，而是把它转为"复核态"（`verifyAtMs`），
  到点复核成功才移除。既有 3 条用例里"完成后 pending 必须移除"的断言因此改为"完成后保留一次性复核台账"，
  并注明 API 语义未变；这是本轮**唯一**的既有断言改动。
- `SessionPurgeGuard#isArmed` 在复核窗口内仍为真（pending 在）——守卫覆盖不减弱。
- 老台账 JSON（无 `verifyAtMs`）逐字兼容：解析为普通台账，走原 retry；retry 完成后同样转入复核态。
- 未引入新配置项：`MEMORY_VERIFY_DELAY_MS = 60_000L` 是类内静态常量。

---

## 4. 仍未覆盖的时序边界（如实登记，不宣称 100% crash 覆盖）

1. **残留晚于复核时刻落地**：复核窗口是 `complete + 60s`。写者若在"通过入口检查"后**被长时间挂起**
   （进程暂停、GC 停顿、机器休眠）超过 60s 才完成 RENAME，则复核之后仍会留下这一笔 —— 此时只剩
   guard 保留期（`session-ttl`，默认 24h）与 `chat:mem:*` 的 TTL 兜底。**不宣称 100% 覆盖**。
2. **复核完成后再出现残留**：复核成功后台账移除；之后任何写回仍被 guard 拦住（守卫键按保留期仍在），
   但"已通过入口检查的在飞写者"同样不受复检保护（同第 1 条）。
3. **retainOrArmIfOwner 与 scheduleVerify 之间 crash**：台账停留在普通态（nextAt 已过期）⇒ runner
   下一轮会做**整轮 retry**（幂等：DB/附件已无数据，只是重新走一遍），完成后转入复核态。安全但不"只碰记忆面"，
   属极窄崩溃窗口的降级行为。
4. **多实例**：复核只由持有 runner 租约的实例执行（`runOnce` 内已有 `ai:purge:retry:lock`），不存在并发复核；
   但**另一个**实例的在飞写者仍受第 1 条约束。
5. **复核连续失败**：耗满 `MAX_ATTEMPTS` 后台账保留、`nextAt` 推到有界未来，仅手动
   `DELETE /api/ai/session/{sid}` 可继续推进（既有语义）；此时残留同样留到 TTL。
6. 真实 Redis/Cluster 未验证（替身口径）；本轮未改任何读侧语义。
