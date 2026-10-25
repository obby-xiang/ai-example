> 来源=<仓外证据>/migration-task-review.md（2026-10-25 归档）· 仓外正式过程报告，属历史证据，不代替现行基线/决策文档（docs/02、docs/03、docs/adr/）
# 迁移启动锁（Flyway cold-start single-flight）独立评审

- 评审对象：主仓 `<主仓>`（backend），六份新文件见 `<仓外证据>/migration-review.diff`
- 实现者报告：`<仓外证据>/agent-56-final-report.md`
- 评审方式：只读。已实读主树中六份文件、`PostgresMigrationConfig`、`application-h2tcp.yml`、`application-postgresql.yml`、`application.yml`、`PostgresMigrationSqlContractTest`/`MigrationSetGuardTest`/`ProfileConfigContractTest` 的相关断言，并核对了实现者工作副本里的 `target/final-migration-tests3.log`、`target/red-probe2.log`。
- 未做：未重跑测试（按要求）；未连真实 PostgreSQL；未起真实 H2 TCP 服务/三 JVM。

---

## 1. 结论（verdicts）

| 维度 | 结论 |
|---|---|
| **Spec Compliance** | **Partial（核心机制达标，两项字面要求未达标/未验证）**：锁确实在 `flyway.migrate()` 之外、因此覆盖 `flyway_schema_history` 创建与整段 migrate；H2/PG 都实现了有界等待、异常上抛、收尾释放；无 V1–V6 改动、无新版本。**但** H2 未使用（也无法使用）同连接会话锁，PG 侧只在 Mockito 替身上验证过，真实 PG 与真实三 JVM 均未跑。 |
| **Task Quality** | **Acceptable-with-gaps（H2 部分可以接受，PG 部分不能算“已验证”）**。代码紧凑、装配方式正确（未新增第二个 DataSource Bean）、profile 闸门正确、失败一律 fail-closed；主要缺陷集中在**证据面**（PG 与负向路径几乎无测试）、**测试区分度**（单轮竞态用例即使去掉锁也约 97% 通过）、以及一处**自相矛盾的测试注释**。 |

**有无 blocker**：代码层面没有发现会让 H2 互斥失效的 blocker；**验收层面有 blocker**——“PG 同物理连接 + 有界等待 + finally 解锁”与“3+ JVM 并发冷启动”这两条**没有真实环境证据**，不能按“已验证”签收。建议把本任务标记为“H2 机制已实现并有多轮外部 RED 证据，PG/多进程待真机验证”。

---

## 2. 需求逐条对照

| # | 需求 | 判定 | 依据 |
|---|---|---|---|
| 1 | 三实例冷启动必须在**建历史表之前**、并包住整段 migrate | **PASS（机制）** | `FlywayStartupLock.migrate()` 在调用 `flyway.migrate()` 之前取锁（`backend/src/main/java/com/example/configmgr/config/FlywayStartupLock.java:70-115`），锁持有到 `flyway.migrate()` 返回；装配走 `FlywayMigrationStrategy`（`.../FlywayStartupLockConfig.java:47-50`）。附带证据：RED 失败信息是 `Unable to baseline schema history table ... already exists, and is empty`（`target/red-probe2.log:889-892`），说明历史表创建/基线判定确实发生在 `Flyway.migrate()` 内部、且早于迁移应用，因此锁必须在 `migrate()` 外层——与实现者所报的 Flyway 11 字节码顺序一致（该字节码结论本次未独立复现，但 RED 形态与之一致）。 |
| 2 | H2-TCP 与 PostgreSQL 共享库都成立 | **PASS（配置面）** | h2tcp 数据源仍是共享 TCP URL（`application-h2tcp.yml:25`），PG 仍是环境注入（`application-postgresql.yml:18-21`）；两 profile 都装配同一策略（`FlywayStartupLockConfig.java:24`）。 |
| 3 | PG：同物理连接 advisory lock + 有界等待 + finally 解锁 + 失败上抛 | **PARTIAL / 真机未验证** | 实现与要求逐字对应：`SET lock_timeout='60s'` + `pg_advisory_lock(20261025)`（`FlywayStartupLock.java:156-161`）、`pin(lockConnection)` 后调用 migrate（`:88`）、finally `pg_advisory_unlock`（`:104-106`、`:163-170`）、SQLException→`FlywayException`（`:93-95`）。但测试只断言了 mock 上的 SQL 文本（`.../FlywayStartupLockTest.java:74-95`）；真实 PG 上 `lock_timeout` 对 advisory lock 的约束、以及解锁失败后的实际后果均未跑过。 |
| 4 | H2-TCP 用真实 H2 `GET_LOCK` | **不成立（合理替代）** | H2 2.3.232 无 `GET_LOCK`/`RELEASE_LOCK`，实现者用 `CALL GET_LOCK(...)` 实机取证；本评审未复跑，但该结论与 H2 事实一致（MySQL 专有函数）。替代方案见 §3。 |
| 5 | 不改 V1–V6、不新增迁移版本 | **PASS** | `git status` 中 `backend/src/main/resources/db/**` 无改动；`MigrationSetGuardTest` 的 V1–V6 字节指纹与“两目录恰好覆盖 1–9”未改且全绿；Maven 目标里 9 个迁移仍是 V1–V5/V7–V9 SQL + Java V6。 |
| 6 | 三 JVM 并发冷启动 | **证据不足** | 唯一并发用例是**单 JVM 三线程 + 进程内 `jdbc:h2:mem:`**（`FlywayStartupLockTest.java:37-70`），没有 TCP 服务、没有多进程。行锁语义是服务端的，结果有理由外推，但 URL 派生/建库/凭据这条 TCP 专有链路只有字符串级单测。 |
| 7 | 配置只激活预期 profile | **PASS** | `@Profile({"h2tcp","postgresql"})`（`FlywayStartupLockConfig.java:24`）；默认 profile 无任何锁 Bean（`FlywayStartupLockConfigTest.java:14-25`）；全仓测试里 h2tcp 的 `@SpringBootTest` 只有新增的那一个（`FlywayStartupLockContextTest.java:26`），不会牵连既有启动面用例；`application.yml` 无 profile group/include，`cluster` 不会顺带激活。 |

---

## 3. H2 替代方案到底安不安全

**结论：在“互斥”这个目标上是安全的，但不等于要求里的“同连接”，并且有三处弱化。**

为什么安全（逐条核对）：

1. **锁在 migrate 之前**：`h2LockConnection` → `acquireH2RowLock` 成功后才 `flyway.migrate()`（`FlywayStartupLock.java:79-83`），覆盖历史表创建与全部回调。
2. **锁是真的跨连接/跨进程的**：锁表 `flyway_startup_lock` 位于**同一 H2 服务上的独立库** `<业务库>_flyway_lock`（`:117-129`），`SELECT ... FOR UPDATE`（`:145-153`）在 `autoCommit=false` 的事务里取行锁，事务持续到整段 migrate 结束才由 `close()` 回滚释放。H2 的行锁是服务端（TCP 服务进程内）语义，三实例共享同一服务即共享同一把锁。
3. **有界等待**：连接上先 `SET LOCK_TIMEOUT 60000`（`:133`），超时抛 `SQLException` → `FlywayException`（`:93-95`），启动失败而不是带病继续。RED 探针里“无锁会坏”的失败形态（历史表空表 baseline 冲突）不会再出现。
4. **不污染业务 schema**：锁表放在独立库，Flyway 不会因此把 PUBLIC 判成非空而 baseline——这一点很关键，否则会把“修复”变成“每次冷启动都 baseline”。
5. **不会碰业务库的 DDL/事务**：H2 连接上只有 `SET/CREATE/MERGE/SELECT FOR UPDATE`，没有 DDL 落在业务库。

弱化项（与“同物理连接”的差距，按实际风险排序）：

- **a) 锁是“带外”的**：锁连接与 migrate 连接不是一条链，若锁连接在 migrate 期间静默断掉（TCP 重置、服务端回收空闲会话），行锁会在迁移仍在跑时被释放，而代码没有任何“锁还持有吗”的复核，竞态窗口会在无人察觉的情况下重新打开。这是“不同连接”换来的固有代价，也是本方案最值得写进运维手册的一条。
- **b) 锁连接没有连接/网络超时**：`DriverManager.getConnection`（`:120`）不走 Hikari 的 `connection-timeout: 5000`，H2 客户端 socket 连接超时缺省为 0（无上限）。H2 服务半死不活时启动会无限挂起，60s 只约束“等锁”，不约束“连不上”。PG 侧因为复用池连接反而是有界的。
- **c) 凭据是“业务用户名 + 空密码”**：`:120` 硬编码 `""`。**对首次创建锁库是可行的**（H2 建新库时用你给的用户名/密码建管理员），所以实现者报告 §6.5“生产 H2 密码非空就会失败”的说法**不准确**；真实失效场景是：锁库已被别人以别的密码创建、或服务端策略不允许空口令。副作用是同一 H2 服务上多了一个 `sa/空密码` 的库（安全面 Minor）。

新增的部署前置：H2 服务必须允许**远程创建**第二个库（`-ifNotExists`）。h2tcp 的业务库 `mem:config_mgr_shared` 本来就是远程创建的，所以这条不算新增依赖；但如果运维改成“预建业务库、服务端不开 `-ifNotExists`”，本改动会让**所有实例启动失败**（fail-closed，不会静默不安全）。profile 头注只写了 `-ifNotExists` 的启动命令（`application-h2tcp.yml:4`），没有写“会额外创建一个锁库”。

---

## 4. PG 路径评审（区分“已读代码”与“已验证”）

已读代码侧没有发现逻辑错误：

- 会话级 `pg_advisory_lock` 不受 Flyway 内部 commit/rollback 影响（这正是选 session lock 而非 xact lock 的理由），解锁在 finally，路径正确。
- 锁定连接复用池连接并被 `FlywayConnectionPin` 钉住（`FlywayConnectionPin.java:20-32`、`:53-57`），`PinnedDataSource` 只在本线程有 pin 时返回非关闭代理，其余时间回落真实池——不会改变应用其余部分的行为。
- 失败路径：`acquired` 只在取锁成功后才置 true，取锁超时不会误发 unlock；取锁失败/解锁失败都向上抛或至少不覆盖原始异常。

**未验证（必须如实知会）**：

1. 真实 PostgreSQL 上 `SET lock_timeout='60s'` 确实能约束 `pg_advisory_lock()` 的等待（按 PG 文档应成立：advisory lock 走同一个锁管理器、受 `lock_timeout` 约束），但本改动没有任何真机证据；实现者自己也只做了 Mockito 契约（报告 §6.3）。
2. **解锁失败的真实后果被低估**：`releasePostgres` 吞掉 `SQLException`（`:167-169`），注释写“连接关闭自动释放会话级 advisory lock”——但 `lockConnection` 是 **Hikari 池连接**，`close()` 只是**归还池**，物理会话仍然活着，advisory lock 仍在。若迁移失败后事务处于 aborted 状态、`SELECT pg_advisory_unlock` 被 PG 拒绝（25P02），这条连接就会带着锁回到池里，下一次同键取锁要等 60s 才失败。实际生产中“迁移失败→启动失败→JVM 退出→池关闭→socket 断开→锁释放”通常能兜住，但这是**依赖进程退出**兜底，不是 finally 兜底；在测试/同进程重启场景会变成 60s 卡顿。
3. `pg_advisory_unlock` 的布尔返回值被忽略（`:165`），解锁“没解掉”与“解掉了”在日志上完全不可区分（连 WARN 都没有）。

---

## 5. 发现清单（Critical / Important / Minor）

> 路径均相对仓库根 `<主仓>`。**Critical：0 条。**

### Important

| # | 位置 | 问题 |
|---|---|---|
| I1 | `backend/src/test/java/com/example/configmgr/config/FlywayStartupLockTest.java:74-95`；`backend/src/main/java/com/example/configmgr/config/FlywayStartupLock.java:88,163-170` | **PG 的字面要求从未被真实执行验证**，且“同连接”这一属性**没有被任何测试端到端断言**：该用例把 `Flyway` 做成 mock，只 verify SQL 文本与 `connection.close()`。把 `:88` 的 `pin(lockConnection)` 整行删掉，28 条测试仍全绿（`pinnedConnectionIsBorrowedByFlywayWithoutBeingClosedByIt` 是独立测 pin 工具的）。建议至少让 mock 的 `migrate()` 回调里断言 `pin.current() == lockConnection` / `PinnedDataSource.getConnection()` 返回的是该连接的代理；PG 真机至少跑一次 `docker run postgres` 级别的最小验证。 |
| I2 | `FlywayStartupLockTest.java:37-70`；证据 `target/red-probe2.log:889-892` | **单轮竞态用例几乎没有区分度**：实现者自己的 RED 探针是“100 轮命中 3 次”，也就是去掉锁后该用例约 **97% 仍会通过**。它也没有任何“确实等过”的断言（无迁移内 latch、无耗时下界），本次日志里 thread-3 在 thread-1 结束后 4ms 才开始（`target/final-migration-tests3.log:283-291`），只能算旁证。建议改成 N 轮（如 50–100）或在 `beforeMigrate` 回调里放 latch 强制三路进入竞态窗口，并断言第二/第三路被阻塞。 |
| I3 | `FlywayStartupLock.java:79-83,117-129`；`FlywayStartupLockTest.java:37-39` | **“三 JVM”要求未验证**：用例是单 JVM 三线程 + 进程内 `mem:`，没有 H2 TCP 服务、没有多进程。锁的语义在服务端所以有理由外推，但 TCP 专有的 URL 派生、建库、凭据链路只在字符串层面测过（`h2LockUrl` 两条断言，`:97-100`）。 |
| I4 | `FlywayStartupLock.java:93-101`（两个方言都缺） | **负向路径零测试**：①等锁超时→`FlywayException`（H2 的 `LOCK_TIMEOUT`、PG 的 `lock_timeout` 各一条）；②`flyway.migrate()` 抛异常后锁是否释放（H2 靠 close 回滚、PG 靠 finally unlock）。这两条正是需求原文点名的“有界等待/finally 解锁/失败上抛”，实现写了但没人验证过；I1 里提到的 PG aborted-transaction 解锁失败也落在这个空白里。 |
| I5 | `FlywayStartupLock.java:117-129`、`:131-154` | **H2 锁连接无连接超时 + 锁丢失不可检测**：`DriverManager.getConnection` 不受 Hikari 5s 约束，H2 客户端 socket 连接超时缺省无上限；另外锁是带外的（§3-a），锁连接中途死掉时迁移会继续跑而互斥已失效、无复核、无日志。建议 `DriverManager.setLoginTimeout`（或 URL 加 `;NETWORK_TIMEOUT=...`），并在 `migrate()` 返回后用同一锁连接做一次 `SELECT 1`/锁有效性检查（失败则至少 WARN/失败）。 |
| I6 | `backend/src/main/resources/application-h2tcp.yml:4`；`FlywayStartupLock.java:117-121` | **部署前置与凭据契约没写清**：h2tcp 现在需要 H2 服务允许创建**第二个**库（`-ifNotExists`），否则所有实例启动失败；锁库用“业务用户名 + 空密码”，首次创建可用、预存在则可能失败。profile 头注/类注释都没有这两条，报告 §6.5 的失效描述也不准确（见 §3-c）。 |

### Minor

| # | 位置 | 问题 |
|---|---|---|
| M1 | `FlywayStartupLockTest.java:24-30`；`FlywayConnectionPin.java:11` | **注释与实现自相矛盾**：测试类注释写“锁与 migrate **同物理连接**：用 H2 会话级 **`SET EXCLUSIVE`** 圈住整段 migrate”，还写“RED 用例保留…多轮重试”——最终代码里 H2 是“独立锁库行锁、不同连接”，`SET EXCLUSIVE` 恰恰是主类注释里被否决的方案（`FlywayStartupLock.java:34`），文件里也没有 RED 用例。`FlywayConnectionPin.java:11` 的“锁与 migrate() 必须同连接”只对 PG 成立。这类反向描述比没注释更危险，建议按实际语义重写。 |
| M2 | `FlywayStartupLock.java:134-140` | `CREATE TABLE IF NOT EXISTS` 的**所有** `SQLException` 被整段吞掉（权限、磁盘满、并发 DDL 失败都吞），随后 MERGE 会以更难读的错误失败。建议只吞“已存在/锁等待”这类预期错误码，其余上抛。 |
| M3 | `FlywayStartupLock.java:46,133,158` | 60s 是**单次等锁**上界，不是整支舰队上界：第三个实例可能先等 MERGE 的行锁、再等 FOR UPDATE 的行锁，合计可达约 2× 迁移时长；慢迁移场景下冷启动会变成崩溃循环而不是继续等。当前迁移实测 37ms（`target/final-migration-tests3.log:283`，三路用例里的一次完整 V1–V9），暂无实际风险，但值得配置化并写清语义。 |
| M4 | `FlywayStartupLock.java:49` | PG 锁键是裸字面量 `20261025`，同一库内本应用的所有部署实例共用（这正是目的），但同库的**另一个应用**若也用该键会被牵连。PG advisory lock 按库隔离，跨库无碰撞；H2 锁键是独立库里的固定行，碰撞面更小。建议配置化或加注释说明命名约定。 |
| M5 | `FlywayStartupLock.java:163-170` | 解锁失败/返回 false 时既不上抛也不打日志，运维无法从日志区分“正常解锁”与“靠连接关闭兜底”。至少 WARN 一次。 |
| M6 | `FlywayStartupLock.java:75-83` | H2 路径为了读 URL 而把一条业务池连接**从取锁一直占到迁移结束**（`:103-113` 才 close），白占一个池位。URL 可以在 `getMetaData()` 后立刻归还。 |
| M7 | `FlywayStartupLock.java:124-129` | `h2LockUrl` 盲目把业务 URL 的全部参数继承给锁库；将来有人加 `;IFEXISTS=TRUE`（锁库不存在→启动失败）或 `;AUTO_SERVER=TRUE`（锁库变成进程内文件库→跨 JVM 失效且**静默**失去互斥），派生逻辑就会变成坑。建议显式白名单参数或至少加注释约束。 |
| M8 | `FlywayConnectionPin.java:53-57,105-127` | `PinnedDataSource` 在本线程把**同一条物理连接**返回给任意调用方，`NonClosingConnection.isClosed()` 恒返回 false（物理连接真关了也看不出来）。Flyway 11 的 migrate 是单连接顺序使用，今天没问题，但这是“一旦 Flyway 改成并行/多连接就会静默串线”的脚枪，值得在注释里点明前提。 |
| M9 | `FlywayStartupLock.java:141-142` | `MERGE ... KEY (...)` 是 H2 的 legacy 语法（当前 2.x 可用），升级 H2 大版本时需复核；可换 `INSERT ... ON CONFLICT` 等更稳写法。 |

---

## 6. 测试真实性小结

- **值得肯定**：`FlywayStartupLockContextTest` 是真实 `@SpringBootTest` + h2tcp profile + 覆盖 URL，证明“用 customizer 换 DataSource”没有破坏 JPA/上下文启动，这一条比 bean 级断言有价值得多；`FlywayStartupLockTest` 的三路用例用的是**多个独立连接**打同一个库，锁竞争是真的（不是 mock）。
- **不够**：并发用例单轮且无“等过”断言（I2）；“三 JVM/TCP”名不副实（I3）；PG 全部是替身（I1）；负向路径空白（I4）。28/28 里真正覆盖新风险的是 4 条 `FlywayStartupLockTest` + 1 条 context 测试，其余 23 条是既有迁移集/配置契约回归（有价值但不是本次改动的新证据）。
- **已核实的外部证据**：`target/final-migration-tests3.log` 确实显示 `Tests run: 28, Failures: 0`、三路用例中仅 thread-1 应用 9 个迁移、thread-3/thread-2 报 “Schema is up to date. No migration necessary.”，与串行化一致；`red-probe2.log` 确实有 `rounds=100 failures=3` 的 baseline 冲突复现。

---

## 7. 建议的收口动作（按优先级）

1. 补一条“PG 同连接”端到端断言（mock `migrate()` 回调 + `pin.current()`），让 I1 的回归可被测试抓住。
2. 至少跑一次真实 PostgreSQL 最小验证（`lock_timeout` 拦截 advisory lock 的等待、解锁、迁移失败后锁释放），把结果写进报告；没跑就继续标注“未验证”。
3. 并发用例改成多轮/latch 强制重叠（I2），并加一条 H2 TCP 真服务（或真三进程）冒烟（I3）。
4. 补两条负向测试：等锁超时→启动失败；migrate 抛异常→锁已释放（H2/PG 各一条）。
5. 修 `FlywayStartupLockTest.java:24-30` 与 `FlywayConnectionPin.java:11` 的反向注释（M1）。
6. 解锁失败打 WARN；给 H2 锁连接加连接/网络超时；把“额外锁库 + `-ifNotExists` + 空密码”写进 h2tcp profile 头注（I5/I6/M5）。
