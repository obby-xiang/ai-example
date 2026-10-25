> 来源=<仓外证据>/agent-56-final-report.md（2026-10-25 归档）· 仓外正式过程报告，属历史证据，不代替现行基线/决策文档（docs/02、docs/03、docs/adr/）
# Agent-56 最终报告：Flyway 冷启动迁移单飞（run 40174291244）

- 工作副本：`<验证副本>/_verify-20261025-migration-race/backend`
- 来源：当前主仓 `<主仓>/backend` 的 `src/` + `pom.xml`（HEAD `ae883fb`，含 Hikari 毫秒、扫描/续跑修复），未复制 `target/data/config/密钥`
- 未改主树/其它副本；未提交/推送；未改 docs/九册；未使用真实端口/Redis/密钥/三实例
- 模型：本子代理运行模型由外部固定，工具层无法切换 DeepSeek/deepseek-flash；如这是硬前置，请主代理侧确认

## 1. 修改文件（仅 6 个 Java 文件，全部在副本内）

新增主代码：

- `src/main/java/com/example/configmgr/config/FlywayStartupLock.java`
- `src/main/java/com/example/configmgr/config/FlywayConnectionPin.java`
- `src/main/java/com/example/configmgr/config/FlywayStartupLockConfig.java`

新增测试：

- `src/test/java/com/example/configmgr/config/FlywayStartupLockTest.java`
- `src/test/java/com/example/configmgr/config/FlywayStartupLockConfigTest.java`
- `src/test/java/com/example/configmgr/config/FlywayStartupLockContextTest.java`

未改：

- `PostgresMigrationConfig`（PG 仍只禁 Java 迁移，仍用 `classpath:db/postgresql` 方言副本，Java V6 禁用口径不变）
- `src/main/resources/db/migration/V1~V6` 与 `db/postgresql/V1~V9`（无新增版本；`MigrationSetGuardTest` 的 H2 V1~V6 指纹全绿）
- `pom.xml`、任何迁移 SQL

## 2. 实现要点

### 2.1 锁必须在 Flyway 建历史表之前

实查 Flyway 11.7.2 字节码：`Flyway.migrate()` 的调用顺序是
`SchemaHistory.create(false)` → `new DbMigrate(...)` → `DbMigrate.migrate()`（后者才触发 `BEFORE_MIGRATE` 回调）。
因此 **Flyway 回调、SeedLock、DDL 后加锁都来不及**。本实现用
`FlywayMigrationStrategy` 在 `flyway.migrate()` 外层先拿锁，覆盖整个 migrate。

### 2.2 同连接与方言

- **PostgreSQL**：在 Flyway 使用的同一物理连接上执行
  `SET lock_timeout = '60s'` → `SELECT pg_advisory_lock(20261025)`；
  最后 `SELECT pg_advisory_unlock(20261025)`，连接关闭兜底。等待有上限、失败向上抛。
  同连接通过 `FlywayConnectionPin` + `PinnedDataSource`：Flyway 拿到的 Connection 是物理锁连接的非关闭代理。
- **H2 TCP**：**H2 2.3.232 与 2.5.252 都没有 `GET_LOCK`/`RELEASE_LOCK`**
  （实机 `CALL GET_LOCK('x',1)` → `Function "GET_LOCK" not found`；全量类名/字符串检索也没有）。
  H2 可用的 `SET EXCLUSIVE` 会拒绝新连接、等待不可中断，不能作为有界启动锁。
  因此 H2 退到 **同 H2 服务上的独立锁库**（业务 URL 派生 `<db>_flyway_lock`）里的
  `flyway_startup_lock` 行锁：`SET LOCK_TIMEOUT 60000` + `SELECT ... FOR UPDATE`，
  有界等待，`rollback`/关闭释放。锁表不放进业务 schema，避免 Flyway 把 PUBLIC 判成非空而 baseline。
  **这是 H2 的硬边界：锁连接与 Flyway 执行连接不是同一物理连接**（H2 没有跨提交保持的会话级命名锁）。
  详见“未验证/未满足边界”。
- 仅 `h2tcp` / `postgresql` profile 装配（`@Profile`）；默认 profile 无任何锁 Bean，单实例 H2 文件库行为不变。
- 装配方式用 `FlywayConfigurationCustomizer` 在 Spring Boot 设好主 DataSource 后再替换为 PinnedDataSource，
  避免新增第二个 DataSource Bean 导致 `DataSourceAutoConfiguration` 退避、主库不创建。

## 3. RED（先失败，证据）

用临时探针（**已删除，不在最终 diff**）在共享 H2 上跑 3 线程冷启动、无任何启动锁，最多 100 轮：

```bash
"<工具安装目录>/JetBrains/IntelliJ IDEA/plugins/maven-plugin/lib/maven3/bin/mvn.cmd" \
  -o -Djacoco.skip=true -Dtest=TmpNoLockRaceProbeTest -DfailIfNoTests=false test
```

日志：`target/red-probe2.log`

```
[RED-PROBE] rounds=100 failures=3
[RED-PROBE] FlywayException: Unable to baseline schema history table "PUBLIC"."flyway_schema_history" as it already exists, and is empty.
[RED-PROBE] FlywayException: Unable to baseline schema history table "PUBLIC"."flyway_schema_history" as it already exists, and is empty.
[RED-PROBE] FlywayException: Unable to baseline schema history table "PUBLIC"."flyway_schema_history" as it already exists, and is empty.
```

即 run 40174291244 的 C 实例错误在共享 H2 上真实复现（100 轮命中 3 次）。

## 4. GREEN（最终代码）

命令：

```bash
"<工具安装目录>/JetBrains/IntelliJ IDEA/plugins/maven-plugin/lib/maven3/bin/mvn.cmd" \
  -o -Djacoco.skip=true \
  -Dtest=FlywayStartupLockTest,FlywayStartupLockConfigTest,FlywayStartupLockContextTest,MigrationSetGuardTest,PostgresMigrationSqlContractTest,ProfileConfigContractTest,TaskChildFkMigrationTest,AiModelReasoningBackfillMigrationTest \
  -DfailIfNoTests=false test
```

日志：`target/final-migration-tests3.log`

```
[INFO] Tests run: 28, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

分项：

| 类 | tests | failures |
|---|---:|---:|
| FlywayStartupLockTest | 4 | 0 |
| FlywayStartupLockConfigTest | 3 | 0 |
| FlywayStartupLockContextTest | 1 | 0 |
| MigrationSetGuardTest | 9 | 0 |
| PostgresMigrationSqlContractTest | 1 | 0 |
| ProfileConfigContractTest | 5 | 0 |
| TaskChildFkMigrationTest | 2 | 0 |
| AiModelReasoningBackfillMigrationTest | 3 | 0 |

编译：

```bash
... mvn.cmd -o -DskipTests -Djacoco.skip=true test-compile
```

日志：`target/final-test-compile3.log` → `BUILD SUCCESS`

### 4.1 真实共享 H2 并发迁移（非 mock）

`FlywayStartupLockTest#threeConcurrentColdStartsUnderLockCreateOneHistoryAndApplyV1ToV9Once`：

- 真实 H2 `jdbc:h2:mem:...;DB_CLOSE_DELAY=-1`，3 个线程/3 个 Flyway 同时起跑；
- H2 启动锁走独立锁库行锁，实际等待；
- 断言：`flyway_schema_history` 只有 1 张；`version IS NOT NULL` 的版本恰好 `1..9` 各一次（9 条）；无失败迁移。

### 4.2 真实 Spring 启动面

`FlywayStartupLockContextTest`（`@SpringBootTest` + `@ActiveProfiles("h2tcp")`，H2 内存库覆盖 URL）：
完整应用上下文启动成功，`@FlywayDataSource` 包装 + `FlywayMigrationStrategy` 生效，
历史表版本化行数为 9。证明启动锁装配没有破坏 JPA/其它 DataSource 注入。

### 4.3 PG 契约（精确替身，未跑真实 PG）

`FlywayStartupLockTest#postgresUsesAdvisoryLockOnTheSamePhysicalConnection` 用 Mockito 精确断言：

- `SET lock_timeout = '60s'`
- `SELECT pg_advisory_lock(20261025)`
- `SELECT pg_advisory_unlock(20261025)`
- `flyway.migrate()` 被调用、连接被关闭

另有一条 `pinnedConnectionIsBorrowedByFlywayWithoutBeingClosedByIt` 证明 Flyway 关闭代理时物理锁连接不关闭。

## 5. 实际锁语义

| 库 | 锁 | 等待上界 | 锁连接 == Flyway 连接 | 释放 |
|---|---|---|---|---|
| PostgreSQL | `pg_advisory_lock(20261025)` | `SET lock_timeout='60s'`，超时 SQLException 上抛 | **是**（PinnedDataSource） | `pg_advisory_unlock` + close |
| H2 TCP | 独立锁库 `flyway_startup_lock` 行锁（`SELECT ... FOR UPDATE`） | `SET LOCK_TIMEOUT 60000` | **否**（H2 无跨提交保持的会话锁） | `rollback` + close |

## 6. 未验证 / 未满足边界（必须知会）

1. **H2 无 `GET_LOCK`**：要求中的“H2 TCP 用真实 H2 GET_LOCK”在 H2 2.3.232/2.5.252 上均不成立（实机验证）。本实现用独立锁库行锁替代。
2. **H2 锁连接与 migrate 不是同一物理连接**：这是 H2 的硬边界，未满足原要求；PG 侧满足。若必须同连接，需要 H2 提供会话级命名锁（当前版本没有），或改用 `SET EXCLUSIVE`（会拒绝新连接、等待不可中断，且阻塞其它连接，本批判定不可用）。
3. **真实 PostgreSQL 未跑**：PG 锁调用仅精确替身契约；`lock_timeout` 对 advisory lock 的超时语义未在真实 PG 验证。
4. **真实 H2 TCP / 真实三 JVM 未跑**：按要求用共享内存 H2 多连接验证；三 JVM 的网络/服务进程行为未验证。
5. **H2 锁库假设**：锁库 URL 由业务 URL 派生（`<db>_flyway_lock`），依赖同 H2 服务且允许 `-ifNotExists`；锁连接使用业务连接的 username + **空密码**（`application-h2tcp.yml` 当前为 sa/空）。若生产 H2 设置非空密码，锁库连接会失败，需扩展配置。
6. **默认 profile**：只做了 `FlywayStartupLockConfigTest` 的 Bean 级断言（无锁 Bean），未跑默认 profile 全量应用上下文；现有迁移/上下文测试未回归到全量套件。
7. **锁表残留**：独立锁库中的 `flyway_startup_lock` 会保留（小表，幂等），未做清理作业。
8. **模型**：运行模型无法在子代理内切换为 DeepSeek/deepseek-flash（如为硬性前置，请主代理确认）。

## 7. 关键证据文件

- RED：`<验证副本>/_verify-20261025-migration-race/backend/target/red-probe2.log`
- GREEN：`<验证副本>/_verify-20261025-migration-race/backend/target/final-migration-tests3.log`
- 编译：`<验证副本>/_verify-20261025-migration-race/backend/target/final-test-compile3.log`
- 单测 H2 并发：`target/green-flyway11.log`、`target/final-lock-tests3.log`
- Spring 启动面：`target/green-context2.log`
