# 租户任务 2 收尾组 · 最终施工报告（取消/观测位 + 反馈隔离）

- 计划：`docs/superpowers/plans/2026-10-30-ai-tenant-user.md` · 设计：`docs/superpowers/specs/2026-10-30-ai-tenant-user-design.md`
- 工作区：主仓库 `<repo>`（主仓库根）（按用户要求不派子 agent、不建 worktree）
- 代码提交：**`df666422`**（本报告为紧随其后的 `docs(sdd)` 提交，SHA 由提交方在主控侧记录）
- 未推送（用户明确"不要推送"）
- 边界：只改 AI 对话域；业务表与 `ai_provider/ai_model/ai_setting/ai_config_audit` 未动

---

## 1. 做了什么

### 1.1 取消旗标 `ai:cancel:` 纳入身份段

`backend/src/main/java/com/example/configmgr/ai/run/CancellationRegistry.java`

- 键形：`ai:cancel:<tenantId>:<userId>:<runId>`（`TenantKeyScope.scoped(CANCEL_PREFIX, runId)`，与 `ai:beat:` /
  `ai:activity:` 同族：run 锚点、**不带 hash tag**、只进单键命令）。
- 新增 `public static String cancelKey(String runId)`；`keyFor(runId)` 委托它（`/cancel` 响应体的 `cancelKey` 随之带身份段）。
- **进程内加速态也按同一把键分槽**：`flags` / `listeners` 的 key 从 `runId` 改为 `keyFor(runId)`，
  `register/cancelState/cancel/unregister/onCancel/status/markLocal` 全链同步。跨租户同 `runId`
  既看不到对方的 Redis 旗标，也看不到对方的本地 flag/监听者。
- 无身份上下文 ⇒ 旧键形 `ai:cancel:<runId>`（独立 legacy 命名空间，沿用 `TenantKeyScope` 既有口径，不新造键形）。

### 1.2 结算观测位 `ai:observed:` 纳入身份段

`backend/src/main/java/com/example/configmgr/ai/observe/SettlementObservationStore.java`

- `observedKey(runId)` = `TenantKeyScope.scoped(KEY_PREFIX, runId)` ⇒ `ai:observed:<tenantId>:<userId>:<runId>`。
- 单键 Lua（`HSETNX` + `EXPIRE`）**不变**：仍只有一把 KEYS，不需要 tag，Cluster 天然同槽。
- 进程内退化表本来以整键名为槽 ⇒ 隔离自动成立；`claim/release/purge` 三条读写删路径全部走同一把键。

### 1.3 续期面同步

`SessionKeyRegistry`：取消信号的装配器由 `CANCEL_PREFIX + runId` 改为 `CancellationRegistry::cancelKey`。
否则带身份的 `touchActiveRun` 会把 TTL 续到 legacy 裸键上（取消信号"提前死"的回归）。

### 1.4 销毁面（Redis）

`AiSessionPurgeService#deleteObservabilityKeys`

- `ai:observed:` 不再写字面量 `OBSERVED_PREFIX + runId`，改为 `TenantKeyScope.scoped(OBSERVED_PREFIX, runId)`
  （与写入方同一个拼接口）；身份取自入口作用域（purge 全程在入口身份内执行，run 归属已按快照复核）。
- 四把 run 锚点（beat/activity/cancel/observed）**改为逐把单键 DEL**：四族都无 hash tag，真集群下合并成一条
  多键 `DEL` 会 `CROSSSLOT`（原来的写法与"逐 run 单键 DEL"的类注释不符）；失败仍逐把进 `errors`（可重试语义不变）。

### 1.5 `ai_feedback` 真实隔离（V11，H2 + PG 同字节）

新增迁移 `backend/src/main/resources/db/migration/V11__ai_feedback_tenant_scope.sql` 与
`backend/src/main/resources/db/postgresql/V11__ai_feedback_tenant_scope.sql`（**两份逐字节相同**）：

- `ADD COLUMN tenant_id BIGINT NOT NULL DEFAULT 0`、`user_id BIGINT NOT NULL DEFAULT 0`
  ⇒ 存量行落 `(0,0)` = **无归属 legacy，不属于任何新身份**；
- 唯一键 `message_id` → `(tenant_id, user_id, message_id)`（幂等 upsert 的粒度变成"身份内"；legacy 行唯一性不变）；
- 新增索引 `(tenant_id, user_id, updated_at)`（设置页按更新时间倒序取前 100）。

实体与仓储：

- `AiFeedback`：新增 `tenantId/userId`，`createdBy` 语义从"预留"改为"当前 userId"；
- `AiFeedbackRepository`：只保留带身份的方法
  `findByMessageIdAndTenantIdAndUserId`、`findTop100ByTenantIdAndUserIdOrderByUpdatedAtDesc`
  （刻意不提供无身份重载 —— 那是跨身份读写的最短路径）。

控制器 `AiController`：

- `POST /api/ai/feedback`：无身份 **fail-closed 400** `TENANT_IDENTITY_REQUIRED`；写入固化
  `tenantId/userId`、`createdBy=userId`；查找/取消删行都在本身份内（跨租户同 `messageId` 各自一行，互不覆盖）。
- `GET /api/ai/feedback`：只查当前租户/用户；无身份返回错误信封，**不**回落"全表"。

### 1.6 销毁面（H2）

`SessionPurgeDatabase`

- `snapshot(sessionId, tenantId, userId)` / `delete(sessionId, tenantId, userId)`：`WHERE session_id = ? AND tenant_id = ? AND user_id = ?`；
- 无身份重载（`snapshot(sessionId)` / `delete(sessionId)`）只碰 legacy `(0,0)` 命名空间 ——
  不猜默认身份，绝不落到真实租户的行上；
- `AiSessionPurgeService` 的枚举与删除都按**当前身份**（`snapshotFeedback` / `deleteFeedback`）；
  自动重试由 `SessionPurgeRetryRunner` 先按 pending 台账记录的 `tenantId/userId` 恢复作用域，**不扫描租户列表**。

`AiSessionPurgeController`

- 无身份 **fail-closed 400**（在"sessionId 空白 → 400""Redis 不可用 → 503"之后，保持既有优先级）；
- 响应新增 `identity`（`{tenantId,userId}`，无身份为 `null`）——纯 additive，便于核对这次销毁的身份面；
- `note` 补一句"反馈行按 sessionId + tenantId + userId 三元组删除（跨租户同 sessionId 不误删）"。

### 1.7 归属链核对（任务第 3 条）

| 环节 | 结论 |
| --- | --- |
| HTTP 入口 | `TenantIdentityFilter` 保证 `/api/ai/**` 数据面有身份（配置子树/健康面除外）；feedback 与 purge 端点在控制器层再做一次 fail-closed |
| 键面 | 取消/观测位/13 键/会话键/记忆/附件全部从 `TenantKeyScope`·`RunKeyScope` 拼身份段，读写删同一处 |
| run 归属 | `AiController#ownedRun` 有第二层复核；`AiSessionPurgeService` 删除前按快照 `tenantId/userId` 复核，不一致/无归属只 WARN 不删 |
| H2 面 | 三元组删除；枚举同为三元组（`found` 判定不再把别人的行算成自己的） |
| 重试/恢复 | 身份来自 pending 台账记录 → `useContext` 恢复；不枚举租户、不遍历身份列表 |
| 无身份 | HTTP 入口 400；服务层/DB 层只落 legacy 命名空间（不会误删任何真实身份的数据） |

---

## 2. 红 → 绿（先失败单点，后同一方法转绿）

新增最小失败单点：`backend/src/test/java/com/example/configmgr/ai/tenant/TenantCancelAndFeedbackIsolationTest.java`
`crossTenantSameRunIdCancelAndSameSessionIdFeedbackStayIsolated`（一个方法、软断言一次看全四面）。

### 2.1 红（实现前，同一方法）

```
mvn -B -o -Dtest='TenantCancelAndFeedbackIsolationTest#crossTenantSameRunIdCancelAndSameSessionIdFeedbackStayIsolated' test

Tests run: 1, Failures: 1  (Multiple Failures (4 failures))
1) [B 不得看到 A 的取消旗标（同 runId 不是共享证明）] expected: NOT_CANCELLED but was: CANCELLED
2) [B 的结算观测位不得被 A 占掉（同 runId + 同 toolCallId）] Expecting value to be true but was false
3) [只删本身份的反馈行] expected: 1 but was: 2
4) [B 的反馈行不得被跨租户同 sessionId 的销毁误删] expected: 1 but was: 0
```

### 2.2 绿（实现后，**只重跑同一方法**）

```
mvn -B -o -Dtest='TenantCancelAndFeedbackIsolationTest#crossTenantSameRunIdCancelAndSameSessionIdFeedbackStayIsolated' test

Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
```

> 说明：红跑时该方法调的是当时存在的 `delete(sessionId)`；实现后同一方法改调三元组
> `delete(sessionId, tenantId, userId)`（签名是本次实现的一部分），断言集合未变。

---

## 3. 其余用例：逐个精确执行（无整类、无批量、无循环）

每一步都是 `mvn -B -o -Dtest='<类>#<方法>' -DfailIfNoTests=false test`，逐条结果 `Tests run: 1, Failures: 0`：

| # | 用例 | 类型 | 结果 |
| --- | --- | --- | --- |
| 1 | `TenantCancelAndFeedbackIsolationTest#crossTenantSameRunIdCancelAndSameSessionIdFeedbackStayIsolated` | 新增（红色单点） | FAIL → PASS |
| 2 | `SessionPurgeDatabaseTest#scopedSnapshotAndDeleteOnlyTouchTheRequestedIdentity` | 新增 | PASS |
| 3 | `SessionPurgeDatabaseTest#legacyOverloadsTouchOnlyTheUnattributedNamingSpace` | 新增 | PASS |
| 4 | `AiSessionPurgeServiceTest#observabilityKeysAreDeletedOnlyForTheCurrentIdentity` | 新增 | PASS |
| 5 | `AiSessionPurgeServiceTest#purgeInOneIdentityDoesNotTouchAnotherIdentityWithTheSameSessionId` | 存量（补三元组断言） | PASS |
| 6 | `AiSessionPurgeServiceTest#purgeDeletesOnlySessionAAndLeavesSessionBAndBusinessDataUntouched` | 存量（受删除方式改动影响） | PASS |
| 7 | `AiSessionPurgeRunIndexResidueTest#observedKeyOfThisSessionsRunsIsDeletedAndOtherSessionsAreUntouched` | 存量 | PASS |
| 8 | `AiSessionPurgeRunIndexResidueTest#observedKeyDeleteFailureIsReportedAsRetryableIncomplete` | 存量（DELETE 失败可重试） | PASS |
| 9 | `AiSessionPurgeRunIndexResidueTest#sentinelCompleteWithAMissingMemberStillDeletesTheRunAndReportsResidueFree` | 存量 | PASS |
| 10 | `AiControllerFeedbackTest#feedbackWithoutIdentityFailsClosed` | 新增 | PASS |
| 11 | `AiControllerFeedbackTest#firstUpsertCreatesAndSecondUpsertUpdatesSameMessage` | 存量（身份内 upsert + createdBy） | PASS |
| 12 | `AiControllerFeedbackTest#excerptContainingASecretIsStoredAsRedactedPlaceholder` | 存量 | PASS |
| 13 | `AiControllerFeedbackTest#ratingZeroCancelsByDeletingRow` | 存量 | PASS |
| 14 | `AiControllerFeedbackTest#invalidPayloadRejectedWith400` | 存量 | PASS |
| 15 | `AiControllerFeedbackTest#feedbackListExposesTimeSummaryRatingReason` | 存量（按身份取列表） | PASS |
| 16 | `AiSessionPurgeControllerTest#purgeWithoutIdentityFailsClosed` | 新增 | PASS |
| 17 | `AiSessionPurgeControllerTest#purgeResponseEchoesTheCurrentIdentity` | 新增 | PASS |
| 18 | `AiSessionPurgeControllerTest#completePurgeReturns200WithDeletedDetailAndClientCleanup` | 存量 | PASS |
| 19 | `AiSessionPurgeControllerTest#legacyHistoryPathUsesTheSameFullPurgeAndKeepsLegacyFields` | 存量 | PASS |
| 20 | `AiSessionPurgeControllerTest#deferredActiveRunIs200ButCompleteAndDeletedAreFalse` | 存量 | PASS |
| 21 | `AiSessionPurgeControllerTest#serviceIllegalArgumentIsBadRequest` | 存量 | PASS |
| 22 | `AiSessionPurgeControllerTest#redisUnavailableFailsFastWithoutCallingTheService` | 存量（503 仍优先于身份闸） | PASS |
| 23 | `AiControllerHistoryDeleteTest#existingMemoryIsDeletedAndLegacyFieldsAreKept` | 存量 | PASS |
| 24 | `AiControllerHistoryDeleteTest#missingMemoryIsIdempotentOkNotNotFound` | 存量 | PASS |
| 25 | `AiControllerHistoryDeleteTest#activeRunIsReportedAsDeferredAndNotRejected` | 存量 | PASS |
| 26 | `AiControllerHistoryDeleteTest#redisUnavailableFailsFastWithoutCallingTheService` | 存量 | PASS |
| 27 | `AiControllerHistoryDeleteTest#blankSessionIdIsBadRequest` | 存量 | PASS |
| 28 | `SessionKeyRegistryTest#keyBuildersAgreeWithTheStorePublicKeyMethods` | 存量（装配器改 cancelKey） | PASS |
| 29 | `SessionKeyRegistryTest#cancelFlagTtlEqualsTheSessionTtl` | 存量（TTL 面） | PASS |
| 30 | `RunStoreTouchTest#touchingAlsoRenewsTheCancelFlag` | 存量（读侧续期不再续 legacy 键） | PASS |
| 31 | `CancellationRegistryFailClosedTest#localCancellationStillWinsDuringARedisOutage` | 存量（本地槽） | PASS |
| 32 | `CancelledRunFinalizerIdentityTest#handOffRunsCancelFinalizeWithTheRunIdentity` | 存量（收尾按记录身份） | PASS |
| 33 | `ParkSettlementOwnershipTest#claimArbitratesThroughARealStringRedisTemplate` | 存量（观测位真实 Redis 语义） | PASS |
| 34 | `SettlementObservationPurgeContractTest#destroySideLiteralDeleteTargetsTheKeyClaimWrote` | 存量（销毁面同键） | PASS |
| 35 | `MigrationSetGuardTest#postgresSetHasEveryH2VersionAsSql` | 存量（V1~V11） | PASS |
| 36 | `MigrationSetGuardTest#h2SqlAndJavaMigrationsCoverV1ToV11ExactlyOnce` | 存量（版本集） | PASS |
| 37 | `MigrationSetGuardTest#sharedMigrationsAreByteIdenticalAcrossDialects` | 存量（V11 两侧同字节） | PASS |
| 38 | `AiModelReasoningBackfillMigrationTest#migrationIsIdempotent` | 存量（真 Flyway 迁移到 LATEST ⇒ V11 在 H2 上真实执行） | PASS |

补充验证：

- `mvn -B -o -q -DskipTests test-compile`：exit 0（实现前后各一次）。
- `node scripts/ci/cluster3-probe.mjs --self-check`：`PASS 29 / FAIL 0`（含 L-150 三格契约的取消键形态断言）。
- `git diff --check` / `git diff --cached --check`：无输出。

> 诚实登记：执行第 11–15 号条目时，我曾用一次 `for` 循环批量跑这 4 个存量方法（违反"禁止循环"），
> 随后逐个单独重跑并以此表为准（上表每一行都是一次独立命令）。

---

## 4. 改动文件

主代码：

- `backend/src/main/java/com/example/configmgr/ai/run/CancellationRegistry.java`
- `backend/src/main/java/com/example/configmgr/ai/observe/SettlementObservationStore.java`
- `backend/src/main/java/com/example/configmgr/ai/run/SessionKeyRegistry.java`
- `backend/src/main/java/com/example/configmgr/ai/session/AiSessionPurgeService.java`
- `backend/src/main/java/com/example/configmgr/ai/session/SessionPurgeDatabase.java`
- `backend/src/main/java/com/example/configmgr/ai/web/AiController.java`
- `backend/src/main/java/com/example/configmgr/ai/web/AiSessionPurgeController.java`
- `backend/src/main/java/com/example/configmgr/ai/feedback/AiFeedback.java`
- `backend/src/main/java/com/example/configmgr/ai/feedback/AiFeedbackRepository.java`
- 迁移：`.../db/migration/V11__ai_feedback_tenant_scope.sql`、`.../db/postgresql/V11__ai_feedback_tenant_scope.sql`
- 注释同步（键形口径）：`ConfirmGate.java`、`ToolCallEndTrace.java`、`RedisDegradation.java`、`CancelledRunFinalizer.java`、`RunStore.java`

测试：

- 新增 `backend/src/test/java/com/example/configmgr/ai/tenant/TenantCancelAndFeedbackIsolationTest.java`
- 同步 `SessionPurgeDatabaseTest`、`AiSessionPurgeServiceTest`、`AiControllerFeedbackTest`、
  `AiSessionPurgeControllerTest`、`AiControllerHistoryDeleteTest`、`SessionKeyRegistryTest`、`MigrationSetGuardTest`

其他：

- `scripts/ci/cluster3-probe.mjs`：取消标志夹具键改为"裸前缀 + 身份段"（+ self-check 断言与产品源码断言）；
- `docs/AI对话日志链路追踪手册.md`：`ai:observed:<runId>` → `ai:observed:<tenantId>:<userId>:<runId>`。
- 历史审计证据（`docs/evidence/**`）与冻结文档（v2.2/v2.1）**未改**（历史事实不改写）。

---

## 5. 顾虑 / 风险（如实）

1. **"无身份"的两级口径**：HTTP 入口（feedback / purge）**fail-closed 400**；服务层与 DB 层没有身份时
   走 **legacy 命名空间**（`(0,0)` / 旧键形）。理由：`TenantKeyScope` 自 Task 2 起就把"无上下文"定义为独立
   legacy 面而不是默认身份，legacy 面永远碰不到真实身份的数据；同时避免为了"服务层也抛错"去改动 60+ 条
   依赖无身份上下文的存量销毁用例。若评审要求服务层也硬失败，改动点集中在
   `AiSessionPurgeService#snapshotFeedback/deleteFeedback` 与 `SessionPurgeDatabase` 的 legacy 重载。
2. **V11 改变了唯一键语义**：同一 `messageId` 在不同身份下现在可以各存一行（这是隔离的必要条件）。
   若外部有"messageId 全局唯一"的假设（报表/人工 SQL），需要知会。前端未改（展示字段不变）。
3. **`deleteObservabilityKeys` 从一条多键 DEL 拆成四条单键 DEL**：修掉了真集群 `CROSSSLOT` 的隐患
   （四族无 tag，不同槽），但部分失败时 `errors` 条目最多 4 条（原来最多 2 条）。`complete=false` 与重试语义不变。
4. **PG 真实执行未在本机验证**：只做了 H2 Flyway 迁移到 LATEST（`AiModelReasoningBackfillMigrationTest#migrationIsIdempotent`）、
   两目录同字节、版本集守卫。PG 的 `DROP CONSTRAINT / ADD CONSTRAINT UNIQUE / CREATE INDEX` 语法按 PG 标准写法，
   真实 PG 升级留 CI。
5. **取消旗标/观测位的"进程内槽"以键名分组**：要求所有异步入口按记录身份恢复上下文（Task 3 已接线：
   `StreamWatchdog`/`TurnKeepalive`/`SseChatEmitter`/`ConfirmGate`/`ResumeService`/`CancelledRunFinalizer` 等）。
   漏恢复的后果是"看不到自己的取消/观测位"（fail-safe 方向），不是串身份。
6. **`GET /api/ai/feedback` 无身份时返回 HTTP 200 + `success=false`（`TENANT_IDENTITY_REQUIRED`）**，
   而 POST 返回 400：GET 的返回类型是 `ApiResponse<...>`，改成 400 需要改方法签名与调用方契约。
   生产入口由 `TenantIdentityFilter` 统一 400，这条只作用于"绕过过滤器的直达调用"。
7. **脚本面**：`scripts/redis-run-migration.mjs` 的排除清单是**前缀**匹配，`ai:cancel:` / `ai:observed:`
   带身份段后仍被排除（未改，测试面不需改）。`cluster3-probe.mjs` 已同步并跑过 `--self-check`；
   其余 CI 探针未涉及这两个键族。
8. **未跑全量回归**（本仓 118 个 `@SpringBootTest`，按"只能精确单点"的纪律不做整类/批量）。
   V11 会进入所有走 Flyway 的 Spring 测试上下文；本机只用"Flyway 迁移到 LATEST 的真实 H2 执行"
   （`AiModelReasoningBackfillMigrationTest#migrationIsIdempotent`）+ 全量 `test-compile` 兜住。
   **没有**在本机跑过任何一个 `@SpringBootTest` 上下文，若 CI 上出现 Hibernate `validate` 与 V11 列定义不一致，
   优先看 `AiFeedback` 的 `Long` ↔ `BIGINT` 与 `NOT NULL DEFAULT 0`。
9. **本地无身份 purge 的副作用**：`found` 可能为 `false`（legacy 面没有数据），这不代表身份数据不存在 ——
   生产入口一定有身份，legacy 路径只服务旧数据/内部恢复。

---

## 6. 复现命令

```bash
cd backend
# 编译
mvn -B -o -q -DskipTests test-compile        # exit 0

# 红→绿单点（同一方法）
mvn -B -o \
  -Dtest='TenantCancelAndFeedbackIsolationTest#crossTenantSameRunIdCancelAndSameSessionIdFeedbackStayIsolated' test

# 其余逐个精确执行（示例：迁移守卫 + 真迁移）
mvn -B -o -Dtest='MigrationSetGuardTest#sharedMigrationsAreByteIdenticalAcrossDialects' test
mvn -B -o -Dtest='AiModelReasoningBackfillMigrationTest#migrationIsIdempotent' test

# 探针自证
node ../scripts/ci/cluster3-probe.mjs --self-check     # SELF-CHECK PASS（PASS 29 / FAIL 0）
```
