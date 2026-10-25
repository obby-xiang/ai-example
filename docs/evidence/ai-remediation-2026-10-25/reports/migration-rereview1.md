> 来源=<仓外证据>/migration-rereview1.md（2026-10-25 归档）· 仓外正式过程报告，属历史证据，不代替现行基线/决策文档（docs/02、docs/03、docs/adr/）
# 迁移启动锁 — Fix Round 1 复审（scoped, read-only）

- 对象：`<仓外证据>/migration-fix1-report.md` + `<仓外证据>/migration-fix1.diff`
- 状态：补丁**已落到主仓**（`<主仓>/backend`，`FlywayStartupLock.java` / `FlywayStartupLockTest.java` / `application-h2tcp.yml` mtime 17:29），本复审以主仓**实际内容**为准逐行核对，而不是只看 diff。
- 上轮评审：`<仓外证据>/migration-task-review.md`（I1–I6 / M1–M9）
- 未做：未重跑测试（按要求，38/38 只核对实现者日志）；未连真实 PostgreSQL；未起真实 H2 TCP / 三 JVM。

---

## 1. 逐项判定

| # | 上轮问题 | 判定 | 依据（主仓实际代码/证据） |
|---|---|---|---|
| 1 | **PG 解锁 false/SQLException 必须上抛并物理 abort，不能把仍带锁的池连接还回池** | **ADDRESSED** | `releasePostgres` 改为 `executeQuery`，`!rs.next() \|\| !getBoolean(1)` 直接抛 `SQLException`（`backend/src/main/java/com/example/configmgr/config/FlywayStartupLock.java:234-241`）；`finally` 捕获释放失败 → 先 `abortQuietly` 再 `close`，迁移本身成功时抛出 `FlywayException("…释放失败…")`（`:115-151`，abort 在 `:126-129`，抛出在 `:144-150`）；迁移已失败时释放失败作为 suppressed 附在原异常上（`:145-147`），不再被吞。测试：`postgresUnlockFalseIsPropagatedAndPhysicalConnectionIsAborted` / `...SqlException...` 断言 `FlywayException` + `connection.abort(any())`（`backend/src/test/java/com/example/configmgr/config/FlywayStartupLockTest.java:175-200`）；RED 用旧实现跑这两条是 2 失败（`target/round1-red-unlock.log:71,99`）。 |
| 2 | **删掉 pin 必须有用例变红** | **ADDRESSED** | `sameConnectionPinningIsAssertedFromInsideFlywayMigrate` 在 mock `Flyway.migrate()` 的 answer 里断言 `pin.current()` 就是 `pg.connection()`（`FlywayStartupLockTest.java:211-224`）。RED：移除 `pin(...)` 后 1 失败、实际值 `null`（`target/round1-red-pin.log:71,105`）。补位：`pinnedConnectionIsBorrowedByFlywayWithoutBeingClosedByIt`（`:226-244`）仍守住“Flyway 关代理不关物理连接”。 |
| 3 | **确定性竞争：确实等，然后只产出一份 V1–V9** | **ADDRESSED** | `secondMigratorWaitsAtTheLockThenOnlyOneMigrationSequenceRuns`（`:40-87`）：holder 的 mock 在 `migrate()` 内 latch 住（`:52-63`），因此 `enteredMigration` 计数即代表**已持锁**；第二个 migrator 起跑后 `sleep(300)` + `isNotDone()`（`:71-72`），释放后两者都完成，最终 `historyVersions == 1..9`、`historyCount == 9`、历史表 1 张（`:76-86`）。区分度成立：真实 Flyway 应用 9 个迁移在本机 26–63 ms（`target/round1-final-all3.log:270,290` 等），无锁时第二个会在 300 ms 内跑完从而断言失败。 |
| 4 | **等锁超时 / migrate 抛异常后的释放** | **ADDRESSED** | H2 超时：`boundedWaitTimeoutFailsClosed…`（`FlywayStartupLockTest.java:89-127`）用 300 ms 的第二把锁，断言 `FlywayException`、<5 s、且 `verifyNoInteractions(mustNotRun)`（`:119`，即拿不到锁绝不进入 migrate）；H2 迁移异常后立即重取：`migrateExceptionReleasesTheH2Lock`（`:129-145`），2 s 上界的下一把锁立刻成功并真实迁移出 V1–V9；PG 迁移异常仍解锁并传播原异常：`postgresMigrateExceptionStillUnlocksAndPropagates`（`:161-173`）。覆盖了“超时 fail-closed / 异常路径释放”两条。 |
| 5 | **H2 第二个锁库删除 + 连接有界 + liveness 检查** | **ADDRESSED（带残留窗口说明）** | 第二锁库彻底消失：无 `h2LockConnection` / `h2LockUrl` / `DriverManager`，全仓 `grep _flyway_lock` 无命中；锁表改为业务库独立 schema `FLYWAY_LOCK.flyway_startup_lock`（`FlywayStartupLock.java:53-58,156-203`）；连接取自主池（h2tcp 的 `hikari.connection-timeout: 5000`，`backend/src/main/resources/application-h2tcp.yml:36-37`），不再是无限超时的 `DriverManager` 连接；等锁上界由 `SET LOCK_TIMEOUT` 给出（`:158`，默认 60 s，`:48`）；迁移后 `verifyH2LockStillHeld` 用 `isValid(2)` + holder token 复核（`:206-219`），失败即 `FlywayException` 启动失败。两条 fail-closed 单测：`:281-289`、`:291-302`。 |
| 6 | **新独立 `FLYWAY_LOCK` schema 不能改变 Flyway baseline** | **ADDRESSED** | 锁 schema 不在 Flyway 的 configured schemas 内（H2 默认 schema 仍是 `PUBLIC`），且没有任何迁移文件涉及 `CREATE SCHEMA`/`DROP ALL OBJECTS`（`backend/src/main/resources/db/migration/*.sql` 无命中）。**实证**：h2tcp 全上下文用例在 `FLYWAY_LOCK` 已存在的前提下，Flyway 仍打印 `All configured schemas are empty; baseline operation skipped` 并创建 `PUBLIC.flyway_schema_history`（`target/round1-final-all3.log:147-148`）；确定性竞争用例同样（`:271-272`）；专项用例 `h2RowLockOnBusinessSchemaDoesNotLeakIntoFlywayBaseline`（`FlywayStartupLockTest.java:246-256`）断言 V1–V9 各一次。 |
| 7 | **holder token / liveness 必须 fail-closed** | **ADDRESSED** | 复核失败 → `SQLException` → `catch` 包装 `FlywayException` → 启动失败（`FlywayStartupLock.java:94,104-110`）；释放失败路径也会 abort + 抛出（`:126-150`）。注意语义边界：两项检查都是**迁移之后**的复核，不能阻止重叠（见 §3）。 |
| 8 | **不改 V1–V6、不新增迁移版本** | **ADDRESSED** | 补丁只改 4 个文件（2 主代码 + 1 测试 + h2tcp yml）；`git status backend/src/main/resources/db` 干净；迁移清单仍是 V1–V5 / V7–V9 SQL + Java V6（9 个版本）；`MigrationSetGuardTest` 9 条在 38/38 内全绿。 |
| 9 | **H2 同物理连接不可能的证据** | **ADDRESSED** | `h2SameConnectionRowLockIsReleasedByCommitSoItCannotCoverFlyway`（`FlywayStartupLockTest.java:258-279`）用两条真实 H2 连接证明：`FOR UPDATE` 期间第二方 200 ms 超时失败，**`first.commit()` 之后**第二方立刻拿到行锁——即行锁是事务级、Flyway 的每次 commit 都会把同连接锁放掉，所以“锁 + 整段 migrate 同连接”在 H2 上不成立。类注释也改成如实描述“H2 侧锁连接是独立连接…硬边界”（`FlywayStartupLock.java:33-40`）。`GET_LOCK` 缺失是上轮已实机验证的事实。 |
| 10 | **真实 PG / 真实三 JVM** | **仍未验证（非本轮修复项）** | 报告 §7.1/§7.2 如实标注；本次代码复核也确认 PG 全部为 Mockito 契约，H2 为单 JVM 多连接。CI 边界，不能按“已验证”签收。 |

**上轮 Minor 顺带核对**：M1（自相矛盾的 SET EXCLUSIVE / 同连接注释）已改：测试类注释改为“H2 不宣称同连接”（`FlywayStartupLockTest.java:28-33`），`FlywayConnectionPin` 注释限定为 PG（`backend/src/main/java/com/example/configmgr/config/FlywayConnectionPin.java:10-12`）；M2（吞掉所有 DDL 异常）已收窄为 `isAlreadyExists`（`FlywayStartupLock.java:159-185,252-258`）；M5（解锁失败不可见）已改为上抛 + abort。M3（60 s 单次等待 vs 舰队）/ M4（锁键裸字面量）/ M9（H2 legacy DDL 语法）未在本轮范围，仍开放。

---

## 2. 新发现（仅 Critical / Important）

### Critical（1 条，已在主仓生效，必须回滚）

**C1. 补丁顺手删掉了 h2tcp profile 的 `app.health.readiness.drain-window: 5s`（与本次 Flyway 锁无关的回归）**

- 位置：`backend/src/main/resources/application-h2tcp.yml:43-49`
  ```
  43: server:
  44:   # 与生产 profile 同口径：readiness 变 503 后优雅停机…
  46:   shutdown: graceful
  47:
  48: # 行锁等待上限（LOCK_TIMEOUT=60s…）
  ```
  `app:` / `health:` / `readiness:` / `drain-window: 5s` 整段已消失（diff 的 `-` 侧可见，fix1 diff 第 687–694 行）。
- 事实：这 8 行是主仓**工作区里另一条在途改动**（mtime 16:21；补丁落地前 `git diff -- application-h2tcp.yml` 显示它是该文件唯一新增块）；补丁在 17:29 覆盖该文件后整块丢失。现在 `git diff -- application-h2tcp.yml` 只剩新增的 4 行注释，说明该改动已被静默回滚。
- 后果：`drain-window` 回落到 `application.yml:501` 的 `0s`，而 `application.yml:499-500` 自己写着“**cluster / h2tcp / postgresql 各自覆盖为 5s**”——仓库自述契约当场失真；`ShutdownDrainGate.java:64` 在窗口为 0 时直接 return，三实例 h2tcp 冒烟的“readiness→503→LB 摘流→优雅停机”链退化为无窗口（`ReadinessProperties.java:42-53` 说明这正是必须避免的形态）。
- 为何 38/38 全绿也发现不了：没有任何测试断言 h2tcp profile 的 `drain-window`（`ReadinessDrainWindowTest` 是直接给属性赋值，不读 profile；`ProfileConfigContractTest` 只校验 h2tcp 的 URL/驱动）。
- 修复：把该块原样恢复到 `application-h2tcp.yml`（与 `application-cluster.yml:44`、`application-postgresql.yml:76` 同值 5s），并重生成补丁，确保补丁只包含“新增 FLYWAY_LOCK 注释”这一处 yml 变更。报告 §1 把该文件列为“已改”，但未提删除了什么，属于变更披露遗漏。

### Important（0 条）

未发现新的 Important 级缺陷。下面三条是**残留风险/口径提醒**，不构成新缺陷：

- **PG “绝不把仍带锁的池连接还回池”只在 abort 成功时成立**：`abortQuietly` 失败被静默吞掉（`FlywayStartupLock.java:243-250`），随后 `close()` 对 Hikari 只是归还池，最终兜底仍是“启动失败 → JVM 退出 → 池关闭 → socket 断开”。建议 abort 失败至少 WARN（当前完全无日志）。
- **`SET LOCK_TIMEOUT` 留在池化连接上**（`:158`）：h2tcp 的 URL 本来就设了 `LOCK_TIMEOUT=60000`，与默认 `WAIT_TIMEOUT` 同值，今天无行为差异；但若将来把 `waitTimeout` 调小，这个会话值会随连接回池、影响该实例后续业务行锁等待。建议恢复原值或加注释。
- **确定性用例的区分度依赖 300 ms 这个余量**（`FlywayStartupLockTest.java:71-72`：本机真实迁移 26–63 ms）。极端负载下若真实 Flyway 超过 300 ms，即使锁失效也可能“通过”。当前余量约 5×，可接受。

---

## 3. H2 方案的安全性评估（本轮重点）

**结论：H2 互斥仍然真实成立，且比上轮更强；同连接仍不成立，重叠窗口仍在，但已被如实披露并有失败即失败的复核。**

成立的部分（逐条自查，不是转述报告）：

1. **锁在建历史表之前、包住整段 migrate**：`acquireH2RowLock` 成功后才 `flyway.migrate()`（`FlywayStartupLock.java:89-95`），策略由 `FlywayStartupLockConfig` 以 `FlywayMigrationStrategy` 装配（未改，`:47-50`）。
2. **锁的物理载体正确**：`FLYWAY_LOCK` 在业务库内、但不在 Flyway 的 schema 集合里 ⇒ 不会触发 baseline（§1 第 6 项有日志实证）；锁行 `flyway_startup_lock('migrate')` 在 `autoCommit=false` 的事务里被 `FOR UPDATE` 持有（`:187-196`），直到迁移结束 `rollback()`（`:221-224`）。
3. **有界 + fail-closed**：等锁受 `SET LOCK_TIMEOUT` 约束（`:158`），连接获取受 Hikari 5 s 约束；超时/异常/复核失败一律启动失败（`:104-114,206-219`）。拿不到锁绝不进入 migrate（`:119` 的 `verifyNoInteractions`）。
4. **锁表/DDL 的并发是串行的**：`CREATE SCHEMA/TABLE IF NOT EXISTS` + `INSERT … WHERE NOT EXISTS` 的重复/竞争结果只吞“已存在/23505/唯一约束”（`:252-258`），其余上抛；H2 的 DDL 由服务端序列化，且这些语句与行锁互不冲突。
5. **不会误伤业务语义**：业务库多一个 schema 只影响 baseline 判定（已证不影响）与 `ddl-auto: validate`（Hibernate 只校验映射表）；`V6` 的 `INFORMATION_SCHEMA.TABLE_CONSTRAINTS` 兜底按 `TABLE_NAME + CONSTRAINT_NAME` 过滤（`backend/src/main/java/db/migration/V6__Task_child_fk.java:118-122`），不会被锁表的主键约束命中。

仍在的部分（如实记录）：

- **锁连接 ≠ Flyway 连接**：这是 H2 2.x 的硬边界（无跨提交保持的会话级命名锁；`SET EXCLUSIVE` 拒绝新连接且等待不可中断），本轮用 commit 释放行锁的实验作为证据（`:258-279`），结论可信。
- **迁移期间锁连接静默死亡的窗口仍在**：此时另一实例可以进锁并开始迁移，事后 `isValid`/token 复核只能让**本实例**启动失败，**不能撤销已发生的重叠**（报告 §7.5 已如实写明）。要在 H2 上彻底消除，只能等 H2 提供会话级命名锁——目前没有。
- **token 复核在正常路径上接近同义反复**：只要事务还开着，holder 一定是自己的；真正能触发的只有 `isValid`。它的价值是给“将来有人在锁连接上加 DDL 导致隐式提交”留一个探测器，不是当前的主要防线。

---

## 4. 证据核对（未重跑）

- 38/38：`target/round1-final-all3.log:366,369`（`Tests run: 38, Failures: 0` / `BUILD SUCCESS`），分类计数与报告一致（`FlywayStartupLockTest` = 14，见 `:316`；`FlywayStartupLockContextTest` = 1，`:266`）。主仓 `FlywayStartupLockTest.java` 现有 `@Test` 数量也确为 14。
- RED：`target/round1-red-unlock.log:71,99`（2 失败）、`target/round1-red-pin.log:71,105`（1 失败），与报告引用一致。
- 基线未被污染：`target/round1-final-all3.log:147-148`（h2tcp 全上下文）、`:271-272`（确定性竞争）均有 “All configured schemas are empty; baseline operation skipped”。
- 迁移集未变：`git status backend/src/main/resources/db` 干净；9 个版本未变。

---

## 5. 结论与阻断项

**判定**

- 上轮 5 个指定 Important（PG 解锁上抛+abort、pin 移除变红、确定性等待且 V1–V9 一份、超时/异常释放、H2 第二锁库移除+连接有界+liveness）**全部 ADDRESSED**；`FLYWAY_LOCK` 不影响 baseline、token/liveness fail-closed、无 V1–V6 改动与新增迁移 **也 ADDRESSED**。
- **Spec Compliance**：仍为 **Partial**——H2 同连接的字面要求依旧不成立（已用实验证明不可行并如实披露）；真实 PG、真实三 JVM 未验证。
- **Task Quality**：代码层面本轮明显改善（负向路径补齐、RED 可复现、注释与实现一致）；但**补丁带进了一处与任务无关的配置回归**，不能按当前状态签收。

**阻断项（1 条，必须在接受前修）**

1. **[Critical] 恢复 `backend/src/main/resources/application-h2tcp.yml` 的 `app.health.readiness.drain-window: 5s` 块**（`application-h2tcp.yml:43-49` 现缺失），并重生成补丁只保留注释新增。这是上一条在途改动被静默回滚，38/38 覆盖不到。

**非阻断的剩余项**

- 真实 PostgreSQL 最小验证、真实 H2 TCP / 三 JVM 冒烟仍缺（CI 边界，继续标注“未验证”，不可写成已验证）。
- H2 迁移期间锁连接断开的重叠窗口不可消除，只能事后 fail-closed；建议在运维/设计文档中与 `FLYWAY_LOCK` 同一处写明。
- `abortQuietly` 失败应至少 WARN；`SET LOCK_TIMEOUT` 的池内残留建议恢复原值（均为 Minor）。
