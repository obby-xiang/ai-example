# Task 2 核心组报告：对话 run 级键族 / 记忆 / 唤醒 / 控制器归属

- 状态：**完成**（下方「顾虑」列出与他组/后续任务的接口缺口）
- 代码提交：`9ffa80e4`（`feat(ai): 对话 run 级键族、记忆与唤醒的身份隔离（租户任务2 核心组）`）
- 范围：`RunStore`、`RedisFrameStore`、`RedisFrameWakeBus`、`RedisChatMemoryRepository`、`LeaseService`（仅文档/解析口）、
  `AiController`，新增 `ai/tenant/RunKeyScope`，并同步 `SessionKeyRegistry` / `FrameWakeBus` 的键形文档与
  `scripts/redis-run-migration.mjs` 的身份目标。
- 未触碰（按要求留给他组）：`AiSessionService` / `SessionGate` / `AiSessionPurge*` / `SessionPurge*` /
  `RedisFormAttachmentStore` / `AiRateLimiter` / `AiExecutorConfig` / `ConfirmGate` / `ResumeService` / `SseChatEmitter` /
  `SpToolCallingManager`。`RunSnapshot` 未改（Task 1 已带 `tenantId/userId`）。

## 键形契约（实施口径）

| 键类 | 有身份上下文 | 无身份上下文（legacy 命名空间） |
| --- | --- | --- |
| run 级 13 键 + `ai:seq` / `ai:xevents` / `ai:lease:epoch` | `<prefix>{<tenantId>:<userId>:run:<runId>}` | `<prefix>{run:<runId>}` |
| 会话/上下文级（`ai:session:current/active/runs`、`ai:ctx`、`ai:ctx:ledger/archive`、`ai:safety`） | `<prefix><tenantId>:<userId>:<sessionId>` | `<prefix><sessionId>` |
| `ai:beat` / `ai:activity` | `<prefix><tenantId>:<userId>:<runId>` | `<prefix><runId>` |
| `ai:runs` 成员 | `<tenantId>/<userId>/<runId>` | `<runId>` |
| `chat:mem` 数据键 / staging | `chat:mem:<t>:<u>:{<escapedId>}` / `chat:mem:staging:<t>:<u>:{<escapedId>}:<uuid>` | `chat:mem:{<escapedId>}` |
| `chat:mem:__ids__` 成员 | `<tenantId>/<userId>/<conversationId>` | `<conversationId>` |
| 唤醒频道 | `ai:wake:<tenantId>:<userId>:<runId>` | `ai:wake:<runId>` |

- 身份段沿用并发组已落地的 `TenantKeyScope`（`<tenantId>:<userId>:`），run 级由新增 `RunKeyScope` 拼 tag / 成员 / 反解：
  **不在代码里出现第二套身份段拼法**。
- 「无上下文 ⇒ legacy 命名空间」是 `TenantKeyScope` 的既定契约（不是"默认租户"）：带身份的路径永远读不到这些键；
  唯一**硬 fail-closed** 的写入口是 `RunStore.create`（缺上下文直接 `IllegalStateException`，不落库、不登记索引）。
- run 级 tag 同时含身份与 runId ⇒ 一轮 13 键仍同槽：`deleteRun` 的多键 `DEL` 一条原子命令；
  `FENCED_SET`（快照+fence）、`IDEM_PUSH`（台账+幂等索引）、`ACQUIRE`（租约+取号计数器+fence）三条多键 Lua 不 `CROSSSLOT`。

## 关键落点

| 项 | 落点 |
| --- | --- |
| run 级键身份化 | `RunStore.runTag/runScopedKey`（新增显式身份重载）→ `RunKeyScope`；会话级键改走 `TenantKeyScope.scoped` |
| 取号计数器同步 | `runLeaseEpochKey` 走同一拼接口；`LeaseService#epochKeyFor` 由租约键 tag 推导 ⇒ 身份自动进键；`runIdFromLeaseKey` 认新/旧/裸三种形态，新增 `runRefFromLeaseKey` 返回「身份+runId」（旧裸键/legacy tag 返回 null，不猜身份） |
| `RunStore.create` fail-closed | 缺上下文抛 `IllegalStateException`；身份同时写进快照并在 `ai:runs` 登记复合成员 |
| 快照写身份 | `openSnapshotWriteScope`：以**记录归属**为准；当前上下文与记录不一致 ⇒ 拒写（防止错身份覆盖同名 runId） |
| 快照读归属复核 | `RunStore.get` 读回后核记录身份；他人/无归属（0/0）按不存在处理 |
| 全局索引 | `create`/`deleteRun` 写/摘复合成员；`allRunIds()` 只返回**当前归属**；新增 `allRunRecords()` 返回 `IndexRef`（身份+runId）供系统恢复逐条 `useContext`；`pruneRunIndex()` 按成员自身身份判活 |
| 无归属 legacy 记忆 | `RedisChatMemoryRepository`：`legacyEligible` 增加"仅无身份上下文"前置 ⇒ 带身份时既不回落读、也不删 legacy 键 |
| 唤醒 | `RedisFrameWakeBus.channel(runId)` 带身份；`subscribe` 固定订阅时身份，监听回调内 `RunIdentityScope.open(...)` 恢复，回调结束清空 |
| 只带 runId 的接口 | `AiController#ownedRun / ownedOrAbsent / ownedByCurrentIdentity`：`events`、`probe`、`cancel`、`pending`、
  `runs/{runId}`、`runs/{runId}/resume`、`runs/{runId}/retry-model-step` 全部读快照核归属；他人/无归属按不存在回答 |
| 迁移辅助 | `scripts/redis-run-migration.mjs`：`taggedKeyFor(prefix, runId, identity)`、`parseTaggedIdentity`、`parseTaggedKey` 认两种 tagged 形、
  `supportsAtomicClaim` 认身份 tag、`--tenant-id/--user-id`（必须成对，正数；`-1` 系统账号允许）、rollback 按身份过滤发现面；
  未声明身份时 dry-run 报告显式警告"目标键是 legacy 形，不构成隔离" |
| 进程内簿记 | `RedisFrameStore` 的 `RunState` 表键改为「身份段+runId」（同进程两个租户同名 runId 不共享降级号/字节窗口） |

## 红/绿证据（全部单方法精确执行，未跑整类/批量/循环）

Maven：`<MAVEN_HOME>`（IntelliJ 自带 maven3 发行版），
统一 `-Djacoco.skip=true -DfailIfNoTests=false`，命令形态
`mvn -B -Dtest='<类>#<方法>' test`。

### 最小失败单点（先红后绿）

`RunStoreTenantIsolationTest#sameRunIdInTwoTenantsNeverSharesSnapshotKeysOrDelete`

- **红**：主工作区当时被并发组留在编译不通过状态（`TenantKeyScope` 未落地），无法取得行为红；改为在
  `git worktree add <TMP_WORKTREE> HEAD`（HEAD=`0fc6c38a`，即本批改动之前）里跑同一份用例：

  ```
  AssertionFailedError:
  [run 级 tag 必须同时含租户、用户与 runId]
  expected: "ai:run:{t1:u101:run:run-shared-20261030}"
   but was: "ai:run:{run:run-shared-20261030}"
  ```

  即：旧实现的 tag 只有 runId，两个租户同名 runId 共用一个键（后续断言会读到对方快照、删除会删掉对方轮次）。
- **绿**：实现后在同一方法上重跑（主工作区，用例期望值按最终口径改为 `{1:101:…}`）：
  `Tests run: 1, Failures: 0, Errors: 0` — `BUILD SUCCESS`。
- 该工作树用完即删（`git worktree remove --force <TMP_WORKTREE>`）。

### 其余新增用例（逐个精确执行）

| 方法 | 结果 |
| --- | --- |
| `RunStoreTenantIsolationTest#createWithoutIdentityFailsClosed` | `Tests run: 1, Failures: 0` |
| `RunStoreTenantIsolationTest#leaseEpochCounterAndLeaseParsingCarryTheSameIdentity` | `Tests run: 1, Failures: 0` |
| `RunStoreTenantIsolationTest#globalIndexRecordsCarryIdentitySoRestoreNeedsNoTenantScan` | `Tests run: 1, Failures: 0` |
| `RunStoreTenantIsolationTest#everyRunLevelKeyOfOneTurnSharesTheIdentityScopedSlot` | `Tests run: 1, Failures: 0` |
| `RedisChatMemoryTenantIsolationTest#sameConversationIdInTwoTenantsNeverSharesMemoryKeys` | 首跑红（索引成员拼成 `1:101:<id>`，期望 `1/101/<id>`）→ 修 `indexMember` 后 `Tests run: 1, Failures: 0` |
| `RedisChatMemoryTenantIsolationTest#unownedLegacyMemoryKeyIsNotReadOrDeletedOnTheNewIdentityPath` | `Tests run: 1, Failures: 0` |
| `RedisFrameWakeBusTenantIsolationTest#publishAndSubscribeUseTheIdentityScopedChannel` | `Tests run: 1, Failures: 0` |
| `RedisFrameWakeBusTenantIsolationTest#unsubscribeTargetsTheIdentityScopedChannel` | `Tests run: 1, Failures: 0` |
| `AiControllerRunOwnershipTest#eventsEndpointTreatsAnotherIdentitiesRunAsUnknown` | `Tests run: 1, Failures: 0` |
| `AiControllerRunOwnershipTest#pendingEndpointReturnsNothingForAnotherIdentitiesRun` | `Tests run: 1, Failures: 0` |
| `AiControllerRunOwnershipTest#cancelEndpointDoesNotAdoptAnotherIdentitiesSnapshot` | 首跑红（取消 mock 未打桩 → 500）→ 补桩后 `Tests run: 1, Failures: 0` |
| `AiControllerRunOwnershipTest#theSameIdentityStillSeesItsOwnRun` | `Tests run: 1, Failures: 0`（同身份正向对照） |

### 回归（存量单方法，逐个精确执行）

| 方法 | 结果 |
| --- | --- |
| `RunStoreRunIndexPruneTest#allRunIdsReturnsLiveRunsAndPrunesDeadOnes` | 绿 |
| `RunStoreSessionRunIndexTest#createAddsSessionIndexWithTtlAndDeleteRunRemovesIt` | 绿 |
| `RunStoreFirstSnapshotWriteTest#createDoesNotRegisterTheRunIndexWhenTheFirstWriteIsRefused` | 绿 |
| `RunMigrationFamilyContractTest#migrationFamiliesMatchProductKeysAndTypes` | 绿 |
| `RunMigrationFamilyContractTest#everyRunScopedProductFamilyIsEitherMigratedOrExplicitlyExcluded` | 绿 |
| `ClusterSlotTest#thirteenRunKeysShareOneSlot` | 绿 |
| `ClusterSlotTest#leaseKeyReverseParseAcceptsTaggedAndLegacyShapes` | 绿 |
| `RedisChatMemoryKeyNamespaceTest#ordinaryIdsKeepTheLegacyMigrationPath` | 绿 |
| `RedisChatMemoryRepositoryClusterSafetyTest#dataKeyShapeCarriesOnlyTheSessionHashTag` | 绿 |
| `RunStoreSlidingTtlTest#suspensionHeartbeatRenewsEveryCardBackingKey` | 绿 |
| `RunStoreFencedWriteTest#fencedWriteSucceedsAndPushesTheFence` | 绿 |
| `RunStoreActivityAnchorTest#businessTouchAdvancesTheAnchorAndIsMonotonic` | 绿 |
| `RedisFrameStoreTest#seqStartsAtOneAndNeverGoesBackwards` | 绿 |
| `AiControllerEventsProbeTest#probeOnLiveRunReturnsLightweightJsonWithoutSubscribingReplayingOrRenewing` | 绿 |
| `AiControllerResumeOutcomeMappingTest#confirmedMissingProviderMapsTo409` | 绿 |

脚本侧：`node --test scripts/redis-run-migration.test.mjs` → `tests 54 / pass 54 / fail 0`（新增 2 例：身份成对校验、身份 tag 键形）。

编译：`mvn -q -DskipTests compile`、`mvn -q -DskipTests test-compile` 均 exit 0（与并发组的在飞改动同树通过）。
提交前 `git diff --check` 无输出；提交只暂存了本组 20 个文件（并发组其余 M/?? 未纳入）；**未推送**。

### 存量用例改动（直调存储的用例显式建身份）

| 文件 | 改动 |
| --- | --- |
| `RunStoreFirstSnapshotWriteTest` | `@BeforeEach` `useContext(1,101)`，`@AfterEach` 关闭 |
| `PerDriveTerminalFrameTest` | 同上（真实 RunStore + `create`） |
| `RunStoreSessionRunIndexTest` | 同上，并把预期会话键改为 `store.sessionRunsKey(..)`、索引用例成员改为 `1/101/<runId>` |
| `RunStoreRunIndexPruneTest` | 同上，`seedIndex` 写复合成员、`members()` 归一成 runId |
| `RunSnapshotTenantIdentityTest` | `realStore.get` 也放进身份作用域（键面按身份分开） |

## 顾虑

1. **`StartupResumeRunner#resumeWithRunIdentity` 先 `store.get(runId)` 再开作用域**（并发组在飞文件）：真实
   `RunStore` 的快照键已按身份分开，无身份读只会落到 legacy 命名空间并返回 null ⇒ 恢复时拿不到
   `tenantId/userId`（退化成"无身份续跑"）。正解 = 用本批新增的 `RunStore#allRunRecords()`
   （成员自带身份）逐条 `RunIdentityScope.open(ref.tenantId(), ref.userId())` 后再读；或提供
   `get(runId, identity)`。这属 Task 3 的"启动恢复/跨实例接管"，本组未改该文件。
2. **`ResumeService#listRuns()` 用 `store.allRunIds()`**：该方法现在只返回当前归属；系统账号/启动扫描
   （无请求身份）只能看到 legacy 成员。跨身份发现必须改用 `allRunRecords()`（同上）。
3. **`AiSessionPurgeService` 的归属/索引路径**：`snapshotOwner(runId)`、全局索引枚举都随键面变成按身份；
   销毁流程必须在"该会话身份"的作用域里跑（其现有用例因无上下文而落在 legacy 命名空间，仍绿）。
4. **`CancellationRegistry`（`ai:cancel:<runId>`）尚未身份化**（不在本组文件清单）：本批在控制器侧先核归属，
   但两个租户同名 runId 仍共用同一个取消旗标 —— 需由取消/销毁组补齐键形。
5. **`InMemoryRunStore`（测试替身）的 `create` 仍允许无身份**（保持 0/0 并写进程内表）：为了不打断约 10 个
   一致性用例，本批没有把它改成 fail-closed；它因此**不覆盖**真实实现的 fail-closed 分支。
6. **"无上下文 ⇒ legacy 命名空间"是取舍不是缺口**：`create` 之外的非写读口沿用并发组 `TenantKeyScope`
   的契约（与前端/会话组一致），生产若出现"无身份写"会写进 legacy 命名空间（带身份路径读不到），
   需要 Task 3 把所有异步/恢复入口接 `useContext` 后才完全闭合。
7. **迁移脚本只到"计划 + 解析"层**：`--tenant-id/--user-id` 已进 `buildPlan/planRun/discoverRunIds`，
   apply/rollback 执行的键来自 plan，未额外改；未加环境变量入口（只有 CLI）。旧数据的身份无法从键里
   推断，**必须由操作者声明**，未声明时只产出 legacy tag 并在报告里写明"不构成隔离"。
8. **`RedisChatMemoryRepository.CONVERSATION_IDS_KEY` 成员换成复合成员**：任何直接 `SMEMBERS` 原始成员的
   外部消费者（脚本/运维面/销毁组的索引兜底）必须按 `tenantId/userId/id` 解析，否则会把整串当会话 id。
9. **未跑全量测试**：按要求只做单方法精确执行；跨文件回归（尤其 `AiSessionPurge*`、`ResumeService`、
   `SseChatEmitter` 大套）留给主控整批验收，若出现红，原因多半是顾虑 1–4 的接口尚未接线。
10. **同槽验证方式**：`FakeLeaseRedis` 的 Cluster 模式对多键跨槽抛异常，比真实 Lettuce（会悄悄拆成 N 条）
    更严；真实集群未起（本批未做集群 smoke）。
