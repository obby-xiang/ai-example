# Task 3 报告：会话互斥、记忆键与销毁完整性

**结论：DONE**（4 个缺陷全部以确定性 RED 证明，最小修复后 GREEN；补丁对当前 main 可干净应用；未改 main、未提交、未派子代理）

- 隔离副本（唯一改动位置）：`<隔离副本>/_verify-20261025-session-integrity/backend`（从 main `backend/` tar 复制，排除 `data/`、`target/`）
- RED 基底：`<隔离副本>/_verify-20261025-session-integrity/red-baseline`（main 生产代码原样 + 仅 4 个新测试类）
- 补丁：`<主仓>/.superpowers/sdd/2026-10-25-ai-remediation/task-3-session-integrity.patch`
- 日志：`<隔离副本>/_verify-20261025-session-integrity/logs/red-baseline.log`、`.../logs/green-final.log`
- main 的三份候选文件与我复制时逐字节相同（未漂移）；main 目录内只新增了本报告与补丁（其余改动与我无关）

命令口径（全程）：IDEA 自带 Maven 3.9.16 + JDK 21，离线（`-o`），**不 clean**，**不起真实 Redis/拓扑**，只用测试替身。

```bash
export JAVA_HOME="<本机工具链>\Java\jdk-21.0.12"
export PATH="<本机工具链>/JetBrains/IntelliJ IDEA/plugins/maven-plugin/lib/maven3/bin:$JAVA_HOME/bin:$PATH"
mvn -o -B -Dtest='<聚焦类清单>' test
```

---

## 1. 逐缺陷：RED 证据 → 最小修复

### D1 · 记忆键命名空间互相穿透（`RedisChatMemoryRepository`）

**缺陷**：数据键是 `chat:mem:{<escapedId>}`，迁移期 legacy 键却是 `chat:mem:<id>`（原样 id，不转义）。
某些 id 的 legacy 键与别的键族**逐字相同**：

| sessionId | legacy 键 | 实际撞上 |
|---|---|---|
| `"{x}"` | `chat:mem:{x}` | 会话 `x` 的 tagged 数据键（读→读到别人历史；删→删别人历史；保存后清理 legacy→同样删别人） |
| `"__ids__"` | `chat:mem:__ids__` | **全局会话索引 SET**（删这个"会话"= 删掉所有会话的索引） |
| `"staging:{…}:…"` | `chat:mem:staging:{…}:…` | 别的会话在飞写回的 staging 中间态 |

**RED**（`RedisChatMemoryKeyNamespaceTest`，5/6 失败）：

```
bracedSessionIdReadsNothingFromTheSessionItCollidesWith
  expected: <[]> but was: <[UserMessage{content='victim', ...}]>
bracedSessionIdDeleteKeepsTheCollidingSessionMemoryAndIndex
  会话 x 的记忆键必须保留 ==> expected: <true> but was: <false>
bracedSessionIdWriteKeepsTheCollidingSessionMemory
  会话 x 的记忆内容必须原样保留 ==> expected: <[...victim...]> but was: <[]>
reservedIndexNameIsNeverTreatedAsConversationMemory
  sessionId=__ids__ 不得把全局索引键当 legacy 记忆键删掉 ==> expected: <true> but was: <false>
reservedIndexNameWriteNeverDeletesTheGlobalIndex
  索引成员必须保留 ==> expected: <true> but was: <false>
```

**修复**（`legacyEligible` 命名空间守卫，接到全部 5 个 legacy 触点：读回落 / 诊断键 / 保存后清理 / 删除 / 存活判据）：

```java
private static boolean legacyEligible(String conversationId) {
    String legacy = legacyKey(conversationId);
    return !legacy.startsWith(KEY_PREFIX + "{") && !legacy.startsWith(STAGING_PREFIX + "{")
            && !legacy.equals(CONVERSATION_IDS_KEY);
}
```

特殊 id 只操作自己的 tagged 键，别人的数据键与全局索引一个字节都不动。**代价如实登记**：这类 id 的老数据不再做 legacy 回落/清理（与新键形无法区分），按原 TTL 自清——宁少一次迁移，也不读写别的会话/删索引。

### D2 · 销毁与记忆写回交错 ⇒ 会话在 `complete=true` 之后复活（`RedisChatMemoryRepository.saveAll`）

**缺陷**：`saveAll` 只在**入口**问一次销毁守卫，然后 staging 写入 + `RENAME`。销毁流程完全可以在这个窗口里装守卫并删完记忆——那一笔 RENAME 就把刚销毁的历史整窗写回来；此时索引成员已被 SREM，`isConversationIndexed` 根本看不见这份残留。

**RED**（`RedisChatMemoryWriteBackRaceTest`，2/4 失败；两个"入口检查/正常写回"用例按预期本来就是绿的）：

```
renameThatLandsAfterThePurgeArmedTheGuardIsUndone
  RENAME 落地后必须复检守卫并撤销写回（销毁后不得复活） ==> expected: <false> but was: <true>
guardReadFailureAfterRenameIsFailClosed
  守卫状态不可知 ⇒ 撤销本次写回（fail-closed） ==> expected: <false> but was: <true>
```

**修复**：RENAME 成功后**复检**守卫（`purgeArmedAfterWrite`）：命中 ⇒ 删掉刚写出的键 + 不写索引（`discardWrittenMemory`）；复检读失败按"已装守卫"处理（fail-closed，与入口检查同口径，WARN 留痕）。空列表删除路径不经过此复检（它本来就是删）。

### D3 · 销毁完整性：staging 中间态不在删除面内、且"不在索引"被当成"已删净"（`AiSessionPurgeService`）

**缺陷**：
1. `chat:mem:staging:{<sid>}:<uuid>` 是在飞 `saveAll` 的中间态，销毁只删 tagged + legacy + 索引成员 ⇒ 只带 staging 的会话被报 `found=false`、`complete=true` 之后仍残留（且它的 RENAME 一落地就是 D2 的复活）；
2. 残留判据缺一道"删完复核"：索引成员已被 SREM，`removedFromIndex=true` 反而掩盖了数据键还在的事实（"索引缺成员"场景）。

**RED**（`AiSessionPurgeMemoryResidueTest`，3/4 失败；索引缺成员那例本来已绿，作为回归钉子保留）：

```
stagingKeysOfThePurgedSessionAreDeletedAndReportCompleteOnlyAfterwards
  本会话的 staging 键必须删除（否则 RENAME 一落地就是残留）
stagingOnlySessionIsFoundAndClearedInsteadOfReportedCompleteWithResidue
  只在 staging 上存在的会话也必须算 found
writeBackLandingAfterTheMemoryDeletionIsNotReportedAsComplete
  删除后又出现记忆残留 ⇒ 不得报 complete
```

**修复**（最小、只删本会话 AI 键，不碰业务数据）：
- 枚举阶段用**已有的 Cluster 安全扫描**（与归档键同口径）扫出本会话 staging 键：glob 用 `escapedId`（无花括号 ⇒ 无 tag 碰撞），并**逐字前缀过滤**（id 自带 `* ? [ ] \` 也不会命中别的会话）；扫描失败上抛 = 枚举中止（不半删）。`memoryExisted`/`found` 现在包含 staging-only 会话。
- 删除阶段逐个**单键 DEL** staging 键（多键 DEL 在 Cluster 下会 CROSSSLOT），然后**复核**：`hasLiveMemory`（新公开的删净判据）+ 再次扫描 staging，仍有残留 ⇒ `memory.deleted=false` + `errors` 记 "memory: 删除后仍有残留 …" ⇒ `complete=false`、pending 保留，由调度/再次 DELETE 补删。

### D4 · 锁设施"自证健康"的探针结论比观测更旧 ⇒ 设施已知不可用时仍可写（`LeaseService`）

**缺陷**：`authority()` 的"从未持租"分支靠 `leaseOk()`（PING）自证，探针按 1s 节流复用缓存 `lastProbeOk`。旧实现**初值 true** 且设施失败时只清 `facilityHealthy`、**不清缓存**：
- 探针在飞时另一个线程抢不到节流令牌 ⇒ 拿到初始 `true` ⇒ `NEVER_HELD` ⇒ `RunStore.save` 走 plain SET（此刻设施正是已知不可用）；
- 上一拍探针成功、随后设施失败 ⇒ 1s 窗口内仍复用那个 `true` ⇒ 同一放大。

**RED**（`LeaseAuthorityProbeFailClosedTest`，2/2 失败，用 latch 固定"探针在飞"窗口，无 sleep）：

```
concurrentAuthorityDuringAFailingProbeStaysReadOnly
  设施已知不可用且探针尚无结论 ⇒ 不得复用初始的"健康"结论进入可写
facilityFailureInvalidatesThePreviouslyProbedVerdict
  刚观测到设施失败 ⇒ 不得复用上一拍的"健康"结论进入可写
```

**修复**：`lastProbeOk` 初值 = `false`（= 还没有结论 ⇒ 只读），并新增 `markFacilityUnhealthy()`（`facilityHealthy=false` **且** `lastProbeOk=false`），替换全部 5 处失败赋值（acquire / verifyHeld / renewState / release / leaseOk）。不变式：**节流窗口内复用的结论永远不比最后一次观测更乐观**；探针成功仍立即回到 `NEVER_HELD`（现状行为不变）。

---

## 2. 明确未改动（已证明无缺陷，不猜改）

| 文件 | 结论 | 证据 |
|---|---|---|
| `SessionGate` | **未改**。五条写路径已是单键原子 compare-and-write，异常/结果不可知都不做裸写兜底也不宣称成功；`acquire` 在 Redis 不可用时返回 `degraded=true` 的**进程内门**是 §13.1 既定设计（改它=改 `AiController` 契约与既有用例），不属"假取锁成功"缺陷 | `SessionGateLeaseTokenTest`（离线跑通，含 `redisFailureNeverFallsBackToPlainWritesOrClaimsOwnership`、`unknownScriptResultIsNotTreatedAsAcquiredOrRenewed`）、`ResumeSessionGateTest` |
| `RunStore` | **未改**。写权限四态（`HELD/LOST/UNAVAILABLE/NEVER_HELD`）与 `fenceEpoch` fail-closed 已到位；"续租失败 ⇒ 只读/拒绝写"这条验收由 `LeaseService` 墓碑 + 本报告的 D4 闭环 | `LeaseLossReadOnlyTest`（5 例，GREEN）：续租 LOST / 续租异常都拒写快照、拒认领、拒落帧、拒台账 |
| `ResilientChatService` | **未改**。记忆写回只有一个写口 `persistMemory → saveAll`，销毁拦截由守卫 + D2 的复检在同一调用内完成，服务层不需要第二套判定 | 本次 77 例 GREEN 中 `AiSessionPurgeServiceTest`（含 `purgeGuardBlocksLateMemoryWriteBack`、`pendingKeepsGuardArmedAfterGuardTtlWouldHaveExpired`） |

> 说明：计划里 Task 2 提醒的那句"锁续期失败/诊断 GET 失败不得进入可写或假取锁成功"，落到代码上就是 **D4（诊断探针 fail-open）** 与 **续租失败已只读（既有 GREEN）** 两件事；没有第三处可证缺陷，故 `RunStore`/`SessionGate` 一行未动。

---

## 3. 测试账目

新增（4 类 / 16 例）：

| 测试类 | 例数 | RED 时失败 |
|---|---|---|
| `ai/memory/RedisChatMemoryKeyNamespaceTest` | 6 | 5（第 6 例是 legacy 迁移回归钉子，本来就绿） |
| `ai/memory/RedisChatMemoryWriteBackRaceTest` | 4 | 2（入口检查/正常写回本来就绿） |
| `ai/run/LeaseAuthorityProbeFailClosedTest` | 2 | 2 |
| `ai/session/AiSessionPurgeMemoryResidueTest` | 4 | 3（索引缺成员本来就绿） |
| **合计** | **16** | **12** |

- RED 基底（main 生产代码原样）：`Tests run: 16, Failures: 12, Errors: 0`（`logs/red-baseline.log`）
- GREEN（新增 4 类 + 每域 2 个相邻类，共 10 类）：`Tests run: 77, Failures: 0, Errors: 0`（`logs/green-final.log`）

相邻类（每域 2 个，遵守上限）：

| 域 | 相邻类 | 例数 |
|---|---|---|
| 记忆 | `RedisChatMemoryRepositoryClusterSafetyTest`、`RedisChatMemoryRepositoryDeleteTest` | 18 / 4 |
| 会话锁/租约 | `LeaseLossReadOnlyTest`、`LeaseServiceTriStateTest` | 5 / 6 |
| 销毁 | `AiSessionPurgeServiceTest`、`SessionPurgeGuardFailClosedTest` | 23 / 5 |

未跑：全量套件、真实 Redis/Cluster/三实例拓扑、需要密钥或真实出网的用例（按计划由最终统一验证负责）。

按上限**刻意没跑**的第 3 个记忆相邻类：`RedisChatMemoryRepositorySaveAllEmptyTest`（空窗删除路径）。它用
**三参构造**（守卫为 null）⇒ 新增的 RENAME 后复检直接返回 `false`、零额外 Redis 操作；`legacyEligible`
对普通 id 恒真 ⇒ 与改造前逐字同形。留给最终统一验证兜底。

---

## 4. 补丁

`task-3-session-integrity.patch`（7 个文件，含 4 个新测试类；`git apply --check` 干净通过，应用后与验证树**逐字节一致**）：

```
backend/src/main/java/com/example/configmgr/ai/memory/RedisChatMemoryRepository.java   (改)
backend/src/main/java/com/example/configmgr/ai/run/LeaseService.java                   (改)
backend/src/main/java/com/example/configmgr/ai/session/AiSessionPurgeService.java      (改)
backend/src/test/java/com/example/configmgr/ai/memory/RedisChatMemoryKeyNamespaceTest.java        (新)
backend/src/test/java/com/example/configmgr/ai/memory/RedisChatMemoryWriteBackRaceTest.java       (新)
backend/src/test/java/com/example/configmgr/ai/run/LeaseAuthorityProbeFailClosedTest.java         (新)
backend/src/test/java/com/example/configmgr/ai/session/AiSessionPurgeMemoryResidueTest.java       (新)
```

应用（**建议用 `core.autocrlf=false`**：本仓库 `core.autocrlf=true`，直接 `git apply` 会把整文件改写成 CRLF，产生"全文件 changed"的假差异）：

```bash
git -c core.autocrlf=false apply .superpowers/sdd/2026-10-25-ai-remediation/task-3-session-integrity.patch
```

验证方式：`<隔离副本>/_verify-20261025-session-integrity/patchcheck2`（main 原样拷贝）→ `git -c core.autocrlf=false apply --check` 通过 → 实际应用后 7 个文件与验证树 `diff` 全部一致。

---

## 5. Task 2 的 observation key：契约已收到，本批**不改代码**（只登记集成需求）

Task 2 报告（`task-2-report.md` §5）给出的契约：键 `ai:observed:<runId>`（HASH，field=`toolCallId`），
TTL=`app.ai.session-ttl` 首次写入即置，**purge = TTL 自清、不要求轮次回收主动删**，迁移 = 进 `EXCLUDED_KEY_PREFIXES`。

- 该键**不在当前 main**（`ai/observe/` 下没有 `SettlementObservationStore`，Task 2 只交了补丁），按其契约 purge 侧**无功能缺口**，故本批不引用、不硬编码 `ai:observed:` 前缀。
- 若最终集成为"立即收敛"口径（与 `ai:beat:` / `ai:activity:` 同列），需要的一行改动在 `AiSessionPurgeService#deleteObservabilityKeys`：用 `SettlementObservationStore` 的公开键方法取 `ai:observed:<runId>` 一起单键 DEL。**这一行留给集成方**，因为它的常量/入口在 Task 2 的补丁里，我这边引不到（会编译失败或猜错键形）。
- 另外：该键是"一次性仲裁位"，与本批的记忆/staging/索引面**没有交集**，不会影响 D1–D4 的判据。

---

## 6. 未验证边界与残余风险（如实登记）

1. **真实 Redis / Cluster 未验证**：全部结论来自进程内替身（`FakeMemoryRedis` / `PurgeFakeRedis` / `Mockito`）。需要真机复核的点：① 新 staging SCAN 在真 Cluster 下逐 master 的完整性（代码沿用既有 `RedisClusterScanSupport`，单键 DEL 不跨槽）；② `RENAME` + 复检之间的真实网络时序；③ 真 Redis 的 TTL/过期语义。
2. **D2 是"事后撤销"，不是事务**：复检命中后那次撤销 DEL 本身失败 ⇒ 会留下一份**带 TTL** 的键（WARN 带键名；销毁重试/对账补删）。不为此引入跨键事务（Cluster 下做不到）。
3. **D3 复核存在"复核之后才落地"的窗口**：purge 复核通过后仍有写回落地时，只能由 D2 的写侧复检兜住（这正是两处修复互补的原因）；若写侧撤销也失败，属第 2 条同一残留面。
4. **D4 的第二个用例有极小的"假绿"可能**：它依赖"上一拍探针成功"到"本次失败观测"之间不超过 1s 节流窗口（实际是微秒级）。机器极端卡顿时它可能直接走重探（重探必然失败 ⇒ 仍断言只读），因此不会假红；回归能力在那种情况下会下降。
5. **D4 的代价（有意选择 fail-closed）**：进程刚观测到设施失败、且另一线程的探针仍在飞时，本线程在 ≤1s 节流窗口内的 `authority()` 会返回 `UNAVAILABLE` ⇒ 该笔快照写被拒（既有 `warnReadOnly`，只读不推进）。这正是"设施不可知不得当可写"的方向；丢的那笔写由该轮后续状态变更/续跑收敛，与既有 LOST/UNAVAILABLE 口径一致，不是新增的数据面风险。
6. **行为面变化（对调用方可见，都是本次的目标）**：
   - purge 的 `complete` 现在会因为"记忆残留"变成 `false`（以前会报 `true`）——这是要求；
   - `found` 现在包含"只有 staging 中间态"的会话（以前报 `found=false`）；
   - `memory.deleted` 现在反映"是否真的删净"（以前只要没抛异常就是 true）。
7. **`SessionGate` 的 §13.1 降级未动**：Redis 不可用时 `acquire` 仍返回"已取门 + `degraded=true`"（进程内互斥）。跨实例互斥在降级期不存在，安全网是 run 租约 fencing（`LeaseLossReadOnlyTest` 覆盖）。若产品要求"降级期拒绝新轮"，那是 §13.1 的语义变更，需要单独立项（且要改 `AiController`，不在本任务范围）。
8. **开销增量**：`saveAll` 每次多一次守卫复检（`isArmed` = 2 次单键 EXISTS，仅在装配了守卫时；每轮一次收敛写，热路径无影响）；purge 每次多 2 次 SCAN（枚举 + 删除后复核，仅销毁端点）。
9. **legacy 迁移的例外面**：`"{…}"` / `staging:{…}` / `__ids__` 这类 id 的老 legacy 键不再被回落读取或清理（按原 TTL 过期）。这是为"不读写别人的数据、不删全局索引"付的代价，已在类注释的"迁移注意/例外"里写明。
