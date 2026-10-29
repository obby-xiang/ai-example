# Task 2 报告（会话/销毁组）：会话、销毁、附件、限流的身份隔离

- 状态：**完成**（代码 + 对应测试已提交；未推送）
- 代码提交：`0787b9fd`（`feat(ai): 会话/销毁/附件/限流的身份隔离与按记录恢复（租户任务2 会话销毁组）`）
- 范围：`AiSessionService`、`SessionGate`、`AiSessionPurgeService`、`SessionPurgeGuard`、
  `SessionPurgePendingStore`、`SessionPurgeRetryRunner`、`RedisFormAttachmentStore`、`AiRateLimiter`、
  `AiSessionPurgeController` 及对应测试；新增共享键形工具 `ai/tenant/TenantKeyScope`。
- 未改：`RunStore`、`LeaseService`、`RedisFrameStore`、`RedisChatMemoryRepository`、`AiController`、
  `AiExecutorConfig`、`ConfirmGate`、`ResumeService`；未改业务数据与 AI 模型配置。

## 键形契约（本组）

| 族 | 键形 | 归属 |
| --- | --- | --- |
| 会话记录 | `ai:session:<t>:<u>:<sid>` | `AiSessionService` |
| 会话门 | `ai:session:lock:<t>:<u>:<sid>` | `SessionGate` |
| 销毁台账 | `ai:purge:pending:<t>:<u>:<sid>` | `SessionPurgePendingStore` |
| 销毁重试队列 | `ai:purge:retry:queue`（成员 = **pending 键**） | `SessionPurgePendingStore` |
| 销毁守卫 | `ai:purge:guard:<t>:<u>:<sid>` | `SessionPurgeGuard` |
| 附件登记 / 墓碑 | `ai:form:att:<t>:<u>:<fileId>` / `ai:form:att:used:<t>:<u>:<fileId>` | `RedisFormAttachmentStore` |
| 附件会话索引 | `ai:form:att:sess:<t>:<u>:<sid>` | 同上 |
| 附件表单索引 | `ai:form:att:form:<t>:<u>:<runId>:<toolCallId>` | 同上 |
| 会话维度限流 | `ai:rate:session:<t>:<u>:<sid>`（IP 维度保持全局 `ai:rate:ip:<addr>`） | `AiRateLimiter` |
| 浏览器镜像 / 草稿 | `ai-mirror:<t>:<u>:<sid>` / `cm.ai.form.draft.<t>:<u>.<toolCallId>` | `AiSessionPurgeService#clientCleanup`（与前端 `mirrorStorageKey`/`formDraftPrefix` 逐字一致） |

身份段 `<t>:<u>:` 由 `TenantKeyScope` 唯一生成（含结尾冒号）；`sessionId` 允许含冒号，所以
**身份只认记录里的 `tenantId/userId`，绝不用键名反解**。

`current/runs/active/ctx/archive/safety` 属 `RunStore`（核心组已在其提交里按 `TenantKeyScope`/`RunKeyScope`
带身份）。本组在销毁编排里改为调用 `runStore.contextKey/contextLedgerKey/contextArchiveKey/sessionCurrentKey/
sessionRunsKey/sessionActiveKey`，并对全局索引成员 `RunKeyScope.parseIndexMember` 做**身份过滤**，不再裸拼前缀。

## 交付要点

1. **会话与门锁**：记录/锁键按复合身份分桶；进程内单值门按同一把锁键分桶 ⇒ 跨租户同 `sessionId`
   不互斥、不互相强释放、不互相诊断。
2. **锁 watchdog 按记录恢复身份**：`Holder` 记下 `sessionId/gateKey/identity/runId/token`；watchdog 线程
   （无请求身份）每次续期先 `useContext(记录身份)`，再用**记录里的锁键**续期。
3. **销毁入口固定身份**：`purge` 取当前上下文（或显式重载）→ 登记 pending 时写入 `tenantId/userId` →
   删除主体全程在该身份的 `useContext` 作用域内执行（下游 RunStore/记忆/附件按上下文算键）。
4. **销毁重试/SCAN 按记录恢复**：队列成员是 pending 键；`listDue`/`recoverOrphans` 先按键取值、
   身份从 JSON **记录**读出，再 `useContext` 恢复后处理 —— 不扫描租户列表。
5. **run 归属二次核对**：全局索引成员先按身份过滤；删除前再用快照的 `tenantId/userId` 复核，
   别的身份或无归属 legacy 的 run 一律不删（只 WARN），也不写进本会话的 run 索引。
6. **附件不能跨身份**：四族键全带身份段；别的身份 `find`/`consume`/`deleteRegistration`
   根本查不到 ⇒ 拿不到 `storagePath`、删不到文件；进程内兜底 Map 也按带身份的键分桶。
7. **complete 收尾**：`clientCleanup` 输出带身份键形；会话级 `ai:session:active:<t>:<u>:<sid>`（ZSET）
   也按类型删除，避免活跃轮加速面指向已删轮。

## 红 / 绿证据（全部单方法精确执行，未跑整类/批量/循环）

### 最小失败单点（跨租户相同 sessionId）

方法：`AiSessionTenantIsolationTest#sameSessionIdUnderTwoTenantsDoesNotShareTheSessionRecord`

- 红（实现前）：

```
[ERROR] Tests run: 1, Failures: 1, Errors: 0, Skipped: 0
org.opentest4j.AssertionFailedError:
[B 不得看到 A 的会话记录（同 sessionId）]
Expecting value to be false but was true
    at ...AiSessionTenantIsolationTest.sameSessionIdUnderTwoTenantsDoesNotShareTheSessionRecord(AiSessionTenantIsolationTest.java:38)
```

- 绿（实现后，只重跑同一方法）：

```
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0 -- in ...AiSessionTenantIsolationTest
[INFO] BUILD SUCCESS
```

### 新增用例（逐个精确执行，均 BUILD SUCCESS）

| 方法 | 覆盖 |
| --- | --- |
| `AiSessionTenantIsolationTest#sameSessionIdUnderTwoIdentitiesHoldsTwoIndependentGates` | 门锁分桶、A 释放不摘 B 的门 |
| `AiSessionTenantIsolationTest#watchdogRenewsTheIdentityScopedLockFromTheHolderRecord` | watchdog 无上下文时按记录身份/锁键续期，`lockLost=0`，legacy 键不出现 |
| `AiSessionTenantIsolationTest#purgeGuardAndPendingAreArmedOnlyForTheIdentityThatOwnsThem` | 墓碑/pending 只对本身份 armed |
| `AiSessionPurgeServiceTest#purgeInOneIdentityDoesNotTouchAnotherIdentityWithTheSameSessionId` | 同 sessionId 两身份：A 删净（记录/run/附件/文件），B 的记录/run/pending/守卫/附件/文件全留；clientCleanup 键形 |
| `SessionPurgePendingStoreTest#scanRecoveryRestoresTheCompositeIdentityFromTheRecord` | 队列成员=键；SCAN 恢复按记录身份回填，两条同 sid 台账各归其主 |
| `SessionPurgeRetryRunnerTest#runOnceRestoresTheRecordedIdentityBeforeRetrying` | 定时线程无身份时按记录 `useContext`，处理后不残留身份 |
| `RedisFormAttachmentStoreSessionIndexTest#sameSessionIdInTwoIdentitiesDoesNotShareRegistrations` | 跨身份 find/consume/deleteRegistration 全部不可达 |
| `AiRateLimiterTest#sessionBudgetIsPerIdentity` | 同 sessionId 两身份各自 20/分，键分别为 `ai:rate:session:1:101:*`、`...:2:201:*` |

### 存量受影响的精确回归（逐个单方法）

- `AiSessionPurgeServiceTest#purgeDeletesOnlySessionAAndLeavesSessionBAndBusinessDataUntouched`
- `AiSessionPurgeServiceTest#activeRunIsCancelledOnceAndItsRunKeysAreDeferredUntilTerminal`
- `AiSessionPurgeServiceTest#crashResidueIsRemovedOnlyAfterTheOneShotVerifyAndDbAttachmentsBusinessAreUntouched`
- `AiSessionPurgeMemoryResidueTest#stagingScanEscapesGlobMetacharactersInSessionIds`
- `AiSessionPurgeRunIndexResidueTest#sentinelCompleteWithAMissingMemberStillDeletesTheRunAndReportsResidueFree`
- `SessionPurgePendingStoreTest#scanRecoveryFindsPendingWhoseQueueEntryWasLost`
- `SessionPurgeRetryRunnerTest#exhaustedPendingsAreDeferredAndDoNotStarveDueSessions`
- `SessionPurgeGuardFailClosedTest#pendingKeepsArmedAndRetentionCannotBeStolenByOldPurgeId`
- `SessionGateLeaseTokenTest#staleReleaseDoesNotDeleteTheResumedHoldersLock`
- `SessionGateBusyUnknownTest#unknownHolderIsNeverReportedAsTheCallersOwnRun`
- `RedisFormAttachmentStoreSessionIndexTest#registerAddsToSessionIndexAndConsumeRemovesIt`
- `RedisFormAttachmentStoreFailClosedTest#consumeAndTombstoneFailuresAreCountedAndFailClosed`
- `RedisFormAttachmentStoreFailClosedTest#registerFallsBackToTheProcessMapOnlyWhenTheTemplateIsAbsent`
- `RedisFormAttachmentStoreLifetimeTest#touchRenewsTheDataKeyTheFormIndexAndTheSessionIndex`
- `AiRateLimiterTest#usesTheAiRateKeyFamilyWithWindowTtl`

改了两处存量断言（队列成员语义从 `sessionId` 变为 pending 键，属内部发现面）：
`SessionPurgePendingStoreTest#oldPurgeIdCannotUpdateOrCompleteNewPending`、
`SessionPurgeRetryRunnerTest#exhaustedPendingsAreDeferredAndDoNotStarveDueSessions`。

收尾：`git diff --check` / `git diff --cached --check` 无输出；`mvn -DskipTests test-compile` 通过；未 `clean`、未推送。

## 顾虑

1. **无上下文时回落 legacy 键形，而不是 fail-closed**。`TenantKeyScope` 没有默认租户/默认用户：
   没有上下文时给出**不含身份段**的旧键形，那是独立命名空间，带身份的读写永不触及（"无归属 legacy
   不进新身份"成立）。代价是：Task 3 接线前的异步路径若漏了上下文，会静默操作 legacy 面（不会串身份，
   但也不会操作到"该身份的数据"）。若评审要求严格 fail-closed，把各入口的 `currentOrNull()` 换成
   `require()` 即可（影响面：上述 6 个组件的无上下文调用与多条存量单测）。
2. **`ai_feedback` 表只有 `session_id`，没有 `tenant_id/user_id`**：跨租户同 `sessionId` 的销毁会删掉
   对方的反馈行（DB 面不受 Redis 键隔离保护）。修它需要一次 schema 迁移 + `SessionPurgeDatabase`
   与反馈写入路径改造；`SessionPurgeDatabase` 不在本组负责清单内，故本批未动，列为待办。
3. **`ai:observed:<runId>` 仍是裸 runId 键**（`SettlementObservationStore` 不在本组）。销毁按旧键形删除；
   若后续把它改成 run tag/身份段，需要同步 `AiSessionPurgeService#deleteObservabilityKeys`。
4. **重试队列成员语义变更**：旧部署遗留的裸 `sessionId` 队列项会被 `listDue` 摘掉，由 `recoverOrphans`
   的 SCAN 重新按 pending 键补回（台账不丢，只是多一次有界 SCAN）。
5. **无归属 legacy run 不会被带身份的销毁删除**（快照 `tenantId/userId=0` 无法证明归属）：留 WARN，
   `complete` 仍可为 true。它们不会被新身份读到，但也不会被新身份代清理 —— 需要一次迁移或手工清理。
6. **`SessionGate.release`/`releaseDeadHeld` 的兜底分支**：线程**无身份**时按 record（sessionId+runId+token）
   定位 holder 并用其锁键释放（避免 Task 3 接线前异步收尾摘不掉门）；线程**有身份**时严格按身份桶，
   不做跨身份兜底。Task 3 接线后该分支只服务 legacy。
7. **并发工作区**：核心组/脚本组在同一 worktree 并发提交，验证期间出现过 `target/classes` 被并发构建
   改写导致的临时编译失败（`NoSuchFileException`/类文件损坏），重试后绿；`TenantKeyScope` 是核心组
   已提交的 `RunKeyScope` 所依赖的文件，本提交补齐它之后该依赖才闭环。
8. **未做真实 HTTP + Redis + LLM 全链路**：本批以替身 + 真 RunStore/真记忆仓储的精确单点验证；
   未启动前端、未跑 E2E，未推送。
