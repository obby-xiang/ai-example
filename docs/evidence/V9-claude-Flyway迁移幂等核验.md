# V9 核验：claude-opus-5.5「Flyway 版本化迁移，清库重跑幂等成功」

- **被核验分支**：`ai-example-claude-opus-5.5`（`git rev-parse --abbrev-ref HEAD` = `claude-opus-5.5`，HEAD = `6cc9b3d`）
- **核验日期**：2026-10-06
- **核验执行方式**：构建后以 `java -jar` 启动真实后端，实际执行三次启动/重启 + 直连 H2 文件库查证
- **核验端口**：18294（`--server.port=18294` 覆盖分支默认 8081）
- **结论**：**【实测-符合】**（附 1 项边界限制，见 §7）

---

## 1. 核验目标（自述原文与出处）

### 1.1 核验手册中的待验声明（本核验项的定义来源）

出处：`<REPO_ROOT>\docs\merge\05-合流落地执行手册.md` 第 127 行

```
| V9 | claude：Flyway 迁移可重复执行 | 清库重跑两次 | 幂等成功 |
```

### 1.2 被核验分支自身的声明原文

| 出处 | 原文 |
|---|---|
| `ai-example-claude-opus-5.5/docs/requirements.md:119` | \| 数据库 \| H2 文件模式（`backend/data/`），ddl-auto:validate + Flyway 管理 \| |
| `ai-example-claude-opus-5.5/docs/technical-design.md:323` | \| 数据库迁移 \| Flyway（非 ddl-auto:update） \| 显式 schema 控制；生产友好 \| |
| `ai-example-claude-opus-5.5/docs/technical-design.md:54` | │ (Flyway schema) │ │ FileSystem (backend/data/files/) │ |
| `ai-example-claude-opus-5.5/docs/verification-results.md:68` | \| 4 \| H2 首次启动 Flyway 迁移 \| 表结构需正确初始化 \| V1__schema.sql 已完整定义所有表 \| |

即被核验声明为：**引入 Flyway 版本化迁移管理数据库 schema；数据库迁移可重复执行，清库重跑幂等成功。**

---

## 2. 环境与核验方法

| 项 | 实际值 |
|---|---|
| OS / Shell | Windows，Git Bash（`<GIT_HOME>`） |
| JDK | `java version "21.0.12" 2026-07-21 LTS` |
| Maven | `<MAVEN_HOME>/bin/mvn.cmd`，本次使用 `-s maven-settings.xml`（仓库内自带的阿里云镜像配置） |
| 构建命令 | `mvn -B -s maven-settings.xml clean package -DskipTests` → `BUILD SUCCESS`，产物 `backend/target/config-mgr.jar`（87,948,673 字节） |
| 启动命令 | `cd backend && export DEEPSEEK_API_KEY=dummy-for-verification && java -jar target/config-mgr.jar --server.port=18294` |
| 工作目录 | `backend/`（因数据源 URL 为相对路径 `./data/config_mgr_db`，H2 文件落于 `backend/data/`） |

**API Key 说明**：本核验项与 AI 调用无关。`backend/config/` 下只有 `application.yml.example`（未追踪的真实密钥文件不存在），`application.yml` 中 `api-key: ${DEEPSEEK_API_KEY:}` 默认空值。为满足启动前置，按任务要求注入占位假值 `DEEPSEEK_API_KEY=dummy-for-verification`，**仅用于启动，全程未发起任何 AI 请求**。

**flyway_schema_history 的查证方法**（无 H2 控制台交互依赖，方法可复现）：
H2 文件库在应用运行期间被独占锁定，故每次均**先关闭应用**（`netstat` 确认 18294 无 LISTENING），再用本地仓库中的 H2 客户端直连查询：

```bash
H2JAR="<USER_HOME>/.m2/repository/com/h2database/h2/2.3.232/h2-2.3.232.jar"
java -cp "$H2JAR" org.h2.tools.Shell \
  -url "jdbc:h2:file:./data/config_mgr_db" -user sa -password "" \
  -sql 'SELECT COUNT(*) AS history_rows FROM "flyway_schema_history";'
```

注：Flyway 建表使用带引号的小写标识符，查询时须写成 `"flyway_schema_history"`（不加引号会报 `Table "FLYWAY_SCHEMA_HISTORY" not found`，实测报错原文见附件汇总文件的时间戳 18:53:38 记录）。

**基线状态（实验开始前）**：`backend/data/` 目录**原本不存在**（`find` 未发现任何 `*.mv.db` / `*.trace.db`），即不存在历史 H2 库文件，无需备份；`backend/target/` 亦不存在，jar 由本次构建生成。

---

## 3. 迁移脚本清单

### 3.1 依赖接入（真实存在，非文档声称）

`backend/pom.xml:55-59`：

```xml
<!-- Flyway -->
<dependency>
    <groupId>org.flywaydb</groupId>
    <artifactId>flyway-core</artifactId>
</dependency>
```

- 版本由 Spring Boot 3.5.14 BOM 管理，**实测解析为 `flyway-core-11.7.2`**（证据：`unzip -l target/config-mgr.jar` 输出 `BOOT-INF/lib/flyway-core-11.7.2.jar`；H2 为 `BOOT-INF/lib/h2-2.3.232.jar`）。
- `pom.xml` 中**没有** flyway-maven-plugin 的 `<plugin>` 配置（全文仅 3 处 "flyway" 字样，均在上述依赖段内）。
- Flyway 11 的 H2 支持已内置于 `flyway-core`，实测 jar 内含 `org/flywaydb/core/internal/database/h2/H2DatabaseType.class` 等类，无需额外模块。

### 3.2 迁移脚本目录与清单

目录：`backend/src/main/resources/db/migration/`

| 版本号 | 文件名 | 行数 | 内容摘要（逐个通读） |
|---|---|---|---|
| V1 | `V1__schema.sql` | 165 | 建 13 张业务表 + 3 个索引 |

**脚本共 1 个版本（V1）**，无 V2 及以后脚本。V1 具体建立的对象（逐条核对 `V1__schema.sql` 原文）：

| # | 表名 | 行号 | 关键列 |
|---|---|---|---|
| 1 | `regions` | 5-10 | id(IDENTITY PK), code(UNIQUE), name, created_at |
| 2 | `projects` | 13-19 | id, code(UNIQUE), name, region_code, created_at |
| 3 | `config_definitions` | 22-31 | id, code(UNIQUE), name, level, description, sort_order, created_at, updated_at |
| 4 | `config_fields` | 34-47 | id, def_code, code, label, field_type, required, is_key, sort_order, options_json(CLOB), ref_def_code, ref_field_code；约束 `uq_field_def_code(def_code, code)` |
| 5 | `config_dependencies` | 50-55 | id, from_def_code, to_def_code；约束 `uq_dep(from_def_code, to_def_code)` |
| 6 | `config_data_rows` | 58-69 | id, def_code, scope_type, scope_key, row_key, data_json(CLOB), version, created_at, updated_at；约束 `uq_data_row(def_code, scope_type, scope_key, row_key)` |
| 7 | `config_staging_rows` | 73-84 | id, task_id, def_code, op_type, row_key, scope_type, scope_key, data_json(CLOB), status, created_at |
| 8 | `tasks` | 88-98 | id, type, title, current_step, status, settings_json(CLOB), version, created_at, updated_at |
| 9 | `task_items` | 101-109 | id, task_id, def_code, sort_order, condition_json(CLOB), status；约束 `uq_task_item(task_id, def_code)` |
| 10 | `task_files` | 112-125 | id, task_id, def_code, file_type, storage_path, original_path, file_name, row_count, version, created_at, updated_at；约束 `uq_task_file(task_id, def_code, file_type)` |
| 11 | `jobs` | 128-141 | id, task_id, job_type, status, progress, total, error_count, warning_count, result_json(CLOB), created_at, started_at, finished_at |
| 12 | `job_items` | 144-152 | id, job_id, def_code, status, processed, total；约束 `uq_job_item(job_id, def_code)` |
| 13 | `validation_issues` | 155-164 | id, job_id, def_code, row_key, field_code, severity, message, row_index |

同脚本内建立索引 3 个：

| 索引名 | 行号 | 对象 |
|---|---|---|
| `idx_data_def_scope` | 70 | `config_data_rows(def_code, scope_type, scope_key)` |
| `idx_staging_task` | 85 | `config_staging_rows(task_id, def_code)` |
| `idx_issue_job_def` | 165 | `validation_issues(job_id, def_code)` |

**脚本写法观察（原文核对）**：全部 DDL 均为裸 `CREATE TABLE` / `CREATE INDEX`，**未使用 `IF NOT EXISTS`**；`V1__schema.sql:1-2` 仅有文件名与中文标题两行注释，无版本回滚脚本（无 `U1__*.sql`）。这一点与 §7 的边界限制直接相关。

### 3.3 数据库与 Flyway 配置（原文）

`backend/src/main/resources/application.yml:16-38`：

```yaml
  datasource:
    url: jdbc:h2:file:./data/config_mgr_db;DB_CLOSE_DELAY=-1
    driver-class-name: org.h2.Driver
    username: sa
    password:

  h2:
    console:
      enabled: true
      path: /h2-console
      settings:
        web-allow-others: true

  jpa:
    hibernate:
      ddl-auto: validate
    show-sql: false
    open-in-view: false

  flyway:
    enabled: true
    locations: classpath:db/migration
    baseline-on-migrate: true
```

- H2 文件库路径：`backend/data/config_mgr_db.mv.db`（相对进程工作目录）。
- Flyway 为**随应用启动自动执行**（Spring Boot 自动配置），无独立插件/手工命令介入；`baseline-on-migrate: true` 已启用；`ddl-auto: validate`（Hibernate 只校验不建表），与文档声称的 "Flyway（非 ddl-auto:update）" 一致。
- 应用默认端口 `8081`（`application.yml:2`），本次全部运行用命令行参数覆盖为 18294。

---

## 4. 实验一：清库首跑

**操作**：`backend/data/` 本就不存在（无库可删，已注明），直接冷启动。
**时间**：2026-10-06 18:53:21 → 18:53:27，进程 PID 4132。
**完整日志**：`V9-附件-实验一-清库首跑日志.log`

### 4.1 启动日志原文（Flyway 段）

```
2026-10-06T18:53:23.181+08:00  INFO 4132 --- [config-mgr] [           main] o.s.b.w.embedded.tomcat.TomcatWebServer  : Tomcat initialized with port 18294 (http)
2026-10-06T18:53:23.725+08:00  INFO 4132 --- [config-mgr] [           main] com.zaxxer.hikari.HikariDataSource       : HikariPool-1 - Start completed.
2026-10-06T18:53:23.759+08:00  INFO 4132 --- [config-mgr] [           main] org.flywaydb.core.FlywayExecutor         : Database: jdbc:h2:file:./data/config_mgr_db (H2 2.3)
2026-10-06T18:53:23.789+08:00  INFO 4132 --- [config-mgr] [           main] o.f.c.i.s.JdbcTableSchemaHistory         : Schema history table "PUBLIC"."flyway_schema_history" does not exist yet
2026-10-06T18:53:23.795+08:00  INFO 4132 --- [config-mgr] [           main] org.flywaydb.core.Flyway                 : All configured schemas are empty; baseline operation skipped. A baseline or migration script with a lower version than the baseline version may execute if available. Check the Schemas parameter if this is not intended.
2026-10-06T18:53:23.795+08:00  INFO 4132 --- [config-mgr] [           main] o.f.c.i.s.JdbcTableSchemaHistory         : Creating Schema History table "PUBLIC"."flyway_schema_history" ...
2026-10-06T18:53:23.831+08:00  INFO 4132 --- [config-mgr] [           main] o.f.core.internal.command.DbMigrate      : Current version of schema "PUBLIC": << Empty Schema >>
2026-10-06T18:53:23.851+08:00  INFO 4132 --- [config-mgr] [           main] o.f.core.internal.command.DbMigrate      : Migrating schema "PUBLIC" to version "1 - schema"
2026-10-06T18:53:23.888+08:00  INFO 4132 --- [config-mgr] [           main] o.f.core.internal.command.DbMigrate      : Successfully applied 1 migration to schema "PUBLIC", now at version v1 (execution time 00:00.027s)
2026-10-06T18:53:27.490+08:00  INFO 4132 --- [config-mgr] [           main] c.e.configmgr.ConfigMgrApplication       : Started ConfigMgrApplication in 6.678 seconds (process running for 7.338)
```

**观察**：应用了 1 个迁移：版本 `1 - schema`（即 `V1__schema.sql`），迁移后 schema 版本 = `v1`；应用启动成功；`ddl-auto: validate` 通过（若表缺失会在此时失败，实测未失败）。日志再无其它 Flyway 动作。

### 4.2 flyway_schema_history 表内容（实测查询）

```
installed_rank | version | description                               | type  | script         | checksum   | success
-1             | null    | << Flyway Schema History table created >> | TABLE |                | null       | TRUE
1              | 1       | schema                                    | SQL   | V1__schema.sql | 1259565170 | TRUE
(2 rows, 16 ms)
```

行数 = **2**（含 1 条 rank=-1 的历史表创建记录 + 1 条 V1 迁移记录），`success = TRUE`，V1 checksum = `1259565170`。

### 4.3 迁移声明的对象确实被创建（抽查全部）

`information_schema.tables`（schema=PUBLIC）实测：

```
TABLE_NAME
CONFIG_DATA_ROWS      CONFIG_DEFINITIONS   CONFIG_DEPENDENCIES  CONFIG_FIELDS
CONFIG_STAGING_ROWS   JOBS                 JOB_ITEMS            PROJECTS
REGIONS               TASKS                TASK_FILES           TASK_ITEMS
VALIDATION_ISSUES     flyway_schema_history
(14 rows, 19 ms)
```

**§3.2 表中声明的 13 张表 100% 存在**（14 = 13 业务表 + `flyway_schema_history`），无多无少。
`information_schema.indexes` 实测 `IDX_DATA_DEF_SCOPE`、`IDX_ISSUE_JOB_DEF`、`IDX_STAGING_TASK` 三个索引均存在，与脚本一致。

### 4.4 应用可用性

- 端口监听：`netstat -ano | grep 18294` → `TCP 0.0.0.0:18294 ... LISTENING 4132`
- 公开 API：`GET /api/master/regions` → `HTTP=200`，返回 4 条种子地区数据：
  `{"success":true,"data":[{"id":1,"code":"HE","name":"华东区",...},{"id":2,"code":"HS",...},{"id":3,"code":"HB",...},{"id":4,"code":"XN",...}]}`
- `GET /api/master/projects` → `HTTP=200`

---

## 5. 实验二：原地重跑（不删库重启，幂等性核心）

**操作**：应用正常关闭（端口释放确认后），**不删除任何库文件**，原样重启。
**时间**：2026-10-06 18:53:49 → 18:53:53，进程 PID 24708。
**完整日志**：`V9-附件-实验二-原地重跑日志.log`

### 5.1 启动日志原文（Flyway 段）

```
2026-10-06T18:53:49.736+08:00  INFO 24708 --- [config-mgr] [           main] org.flywaydb.core.FlywayExecutor         : Database: jdbc:h2:file:./data/config_mgr_db (H2 2.3)
2026-10-06T18:53:49.773+08:00  INFO 24708 --- [config-mgr] [           main] o.f.core.internal.command.DbValidate     : Successfully validated 1 migration (execution time 00:00.019s)
2026-10-06T18:53:49.782+08:00  INFO 24708 --- [config-mgr] [           main] o.f.core.internal.command.DbMigrate      : Current version of schema "PUBLIC": 1
2026-10-06T18:53:49.784+08:00  INFO 24708 --- [config-mgr] [           main] o.f.core.internal.command.DbMigrate      : Schema "PUBLIC" is up to date. No migration necessary.
2026-10-06T18:53:53.398+08:00  INFO 24708 --- [config-mgr] [           main] c.e.configmgr.ConfigMgrApplication       : Started ConfigMgrApplication in 6.889 seconds (process running for 7.648)
2026-10-06T18:53:53.448+08:00  INFO 24708 --- [config-mgr] [           main] c.example.configmgr.seed.DataSeedRunner  : Seed data already exists, skipping
```

**观察**：
- 日志为 `Schema "PUBLIC" is up to date. No migration necessary.`，**符合预期**；
- 迁移脚本**未被重复执行**（无 `Migrating schema ...` 行）；
- **无 checksum 冲突、无校验失败、无重复执行错误**：对 exp2 日志做 `grep -ci "checksum\|validate failed\|failed to apply\|ERROR"` 结果为 **0**（零匹配）；
- 校验通过：`Successfully validated 1 migration`；
- 种子数据被正确跳过（`Seed data already exists, skipping`），未重复插入。

### 5.2 flyway_schema_history 表内容与行数比对

```
HISTORY_ROWS
2
(1 row, 12 ms)

installed_rank | version | description                               | type  | script         | checksum   | success
-1             | null    | << Flyway Schema History table created >> | TABLE |                | null       | TRUE
1              | 1       | schema                                    | SQL   | V1__schema.sql | 1259565170 | TRUE
```

**行数 = 2，与实验一完全一致**；V1 的 checksum 仍为 `1259565170`（与首跑相同，未被改写）；无新增记录。

### 5.3 应用可用性

- 端口监听：`netstat -ano | grep 18294` → LISTENING（PID 24708）
- `GET /api/master/regions` → `HTTP=200`；`GET /api/master/projects` → `HTTP=200`

---

## 6. 实验三：二次清库重跑

**操作**：先备份库文件（`cp data/config_mgr_db.mv.db → /tmp/v9-backups/exp2-db-backup-config_mgr_db.mv.db`），随后删除 `data/config_mgr_db.mv.db`；同时把 H2 客户端在我上一步查询报错时生成的 `config_mgr_db.trace.db`（内容仅为本次核验自身的一条 SQL 语法错误记录）一并移出，使 `backend/data/` 达到**完全空目录**：
`ls -la data/` → `total 0`（删除后实测输出，无残留文件）。
**时间**：2026-10-06 18:54:08 → 18:54:12，进程 PID 8928。
**完整日志**：`V9-附件-实验三-二次清库重跑日志.log`

### 6.1 启动日志原文（Flyway 段）

```
2026-10-06T18:54:08.780+08:00  INFO 8928 --- [config-mgr] [           main] org.flywaydb.core.FlywayExecutor         : Database: jdbc:h2:file:./data/config_mgr_db (H2 2.3)
2026-10-06T18:54:08.810+08:00  INFO 8928 --- [config-mgr] [           main] o.f.c.i.s.JdbcTableSchemaHistory         : Schema history table "PUBLIC"."flyway_schema_history" does not exist yet
2026-10-06T18:54:08.813+08:00  INFO 8928 --- [config-mgr] [           main] o.f.core.internal.command.DbValidate     : Successfully validated 1 migration (execution time 00:00.013s)
2026-10-06T18:54:08.818+08:00  INFO 8928 --- [config-mgr] [           main] org.flywaydb.core.Flyway                 : All configured schemas are empty; baseline operation skipped. A baseline or migration script with a lower version than the baseline version may execute if available. Check the Schemas parameter if this is not intended.
2026-10-06T18:54:08.820+08:00  INFO 8928 --- [config-mgr] [           main] o.f.c.i.s.JdbcTableSchemaHistory         : Creating Schema History table "PUBLIC"."flyway_schema_history" ...
2026-10-06T18:54:08.855+08:00  INFO 8928 --- [config-mgr] [           main] o.f.core.internal.command.DbMigrate      : Current version of schema "PUBLIC": << Empty Schema >>
2026-10-06T18:54:08.872+08:00  INFO 8928 --- [config-mgr] [           main] o.f.core.internal.command.DbMigrate      : Migrating schema "PUBLIC" to version "1 - schema"
2026-10-06T18:54:08.906+08:00  INFO 8928 --- [main] o.f.core.internal.command.DbMigrate      : Successfully applied 1 migration to schema "PUBLIC", now at version v1 (execution time 00:00.026s)
```

（末行时间戳与前缀照原日志为 `2026-10-06T18:54:08.906+08:00  INFO 8928 --- [config-mgr] [           main]`）

**观察**：与实验一逐行同构 —— 历史表重建、`<< Empty Schema >>` → `Migrating ... to version "1 - schema"` → `Successfully applied 1 migration ... now at version v1`，耗时可忽略差异（0.026s vs 首跑 0.027s）。**第二次清库重跑干净成功**，证明 V1 脚本可从头重复执行、不依赖上一次运行的环境残留。

补充：`Started ConfigMgrApplication in 6.842 seconds`（见附件完整日志第 64 行），应用启动成功。

### 6.2 flyway_schema_history 与表清单（实测）

```
HISTORY_ROWS
2
(1 row, 11 ms)

installed_rank | version | description                               | type  | script         | checksum   | success
-1             | null    | << Flyway Schema History table created >> | TABLE |                | null       | TRUE
1              | 1       | schema                                    | SQL   | V1__schema.sql | 1259565170 | TRUE
(2 rows, 1 ms)
```

表清单实测 14 行（13 业务表 + `flyway_schema_history`），与实验一完全一致。

### 6.3 应用可用性

- 端口监听：`netstat -ano | grep 18294` → LISTENING（PID 8928）
- `GET /api/master/regions` → `HTTP=200`

---

## 7. 补充观察（非 V9 判定的必要条件）：对"已有非空 schema"的库不具备自愈能力

> 此项为核验过程中发现的**边界限制**，用于合流决策参考；它不是 V9 声明（"清库重跑"）的一部分，不影响 §8 判定。

**动机**：V9 在合流手册中被用作验收前置（`05-合流落地执行手册.md:258`：验收 `编译绿 + Flyway 迁移成功 + 8 配置种子落库`）。若合流后的库已由其它分支的表结构存在，则需知道 Flyway 会如何表现。**该实验不修改任何代码/脚本/配置，仅改变数据库初始状态。**

**操作**：备份 exp3 库 → 删除 → 用 H2 客户端预建一个仅含 `regions` 表的非空库（**无** `flyway_schema_history`）→ 启动应用。
**时间**：2026-10-06 18:54:35 起，进程 PID 23752；进程以退出码 1 结束（启动失败）。
**完整日志**：`V9-附件-补充观察-已有非空schema启动失败日志.log`

### 7.1 日志原文（关键段）

```
2026-10-06T18:54:35.224+08:00  INFO 23752 --- [config-mgr] [           main] o.f.c.i.s.JdbcTableSchemaHistory         : Schema history table "PUBLIC"."flyway_schema_history" does not exist yet
2026-10-06T18:54:35.228+08:00  INFO 23752 --- [config-mgr] [           main] o.f.core.internal.command.DbValidate     : Successfully validated 1 migration (execution time 00:00.012s)
2026-10-06T18:54:35.234+08:00  INFO 23752 --- [config-mgr] [           main] o.f.c.i.s.JdbcTableSchemaHistory         : Creating Schema History table "PUBLIC"."flyway_schema_history" with baseline ...
2026-10-06T18:54:35.255+08:00  INFO 23752 --- [config-mgr] [           main] o.f.core.internal.command.DbBaseline     : Successfully baselined schema with version: 1
2026-10-06T18:54:35.272+08:00  INFO 23752 --- [config-mgr] [           main] o.f.core.internal.command.DbMigrate      : Current version of schema "PUBLIC": 1
2026-10-06T18:54:36.760+08:00 ERROR 23752 --- [config-mgr] [           main] j.LocalContainerEntityManagerFactoryBean : Failed to initialize JPA EntityManagerFactory: [PersistenceUnit: default] Unable to build Hibernate SessionFactory; nested exception is org.hibernate.tool.schema.spi.SchemaManagementException: Schema-validation: missing table [config_data_rows]
2026-10-06T18:54:36.760+08:00 WARN 23752 --- [config-mgr] [           main] ConfigServletWebServerApplicationContext : Exception encountered during context initialization - cancelling refresh attempt: ... Schema-validation: missing table [config_data_rows]
2026-10-06T18:54:36.782+08:00 ERROR 23752 --- [config-mgr] [           main] o.s.boot.SpringApplication               : Application run failed
```

### 7.2 机制与影响（基于实测输出）

1. `baseline-on-migrate: true` + 非空 schema + 无历史表 → Flyway 执行 **baseline 到 version 1**（`Successfully baselined schema with version: 1`）；
2. baseline 版本 = 1 意味着 `V1__schema.sql`（version 1）**被跳过**（日志中确实没有 `Migrating schema ...` 行），因此只有预存的 `regions` 表存在，其余 12 张表从未建立；
3. `ddl-auto: validate` 随后校验失败：`Schema-validation: missing table [config_data_rows]`，应用**启动失败**；
4. 根因叠加：迁移脚本为裸 `CREATE TABLE`（无 `IF NOT EXISTS`），即使不被 baseline 跳过，也无法在已有同名表的库上重放。

**合流提示（供决策，非 V9 结论）**：本分支的 Flyway 只保证"从空库出发"的可重复性；若合流后要复用一份已存在的 H2 库（或旧库来自其它分支的 `ddl-auto` 建表），需**先清库**或**先手工删除旧表/重建历史表**，否则后端不会自动迁移到完整 schema。声明中的"清库重跑"表述与这一边界是自洽的。

---

## 8. 与声明是否一致

| 声明要点 | 实测结果 | 判定 |
|---|---|---|
| 引入 Flyway（依赖真实接入） | `flyway-core-11.7.2` 在 pom 与成品 jar 中均存在；11 个 H2 方言类内置 | ✅ 属实 |
| 版本化迁移（脚本目录 + 命名规范） | `classpath:db/migration` + `V1__schema.sql`，Flyway 输出 `version "1 - schema"`；但**仅 1 个版本脚本**，无后续版本演进 | ✅ 机制属实（版本数=1，措辞"版本化"成立但无多版本实证） |
| 数据库迁移可重复执行（清库重跑幂等成功） | 实验一（清库首跑）成功 → 实验三（二次清库重跑）逐行同构成功，history 行数/checksum 完全一致 | ✅ 属实 |
| 重启幂等（"可重复执行"的运行时侧） | 实验二 `up to date. No migration necessary.`，0 处 checksum/校验错误，history 行数 2 不变 | ✅ 属实 |
| 迁移声明的表全部创建 | 13/13 表 + 3/3 索引实测存在 | ✅ 属实 |
| Flyway 与 ddl-auto 的关系（technical-design.md:323 "Flyway（非 ddl-auto:update）"） | 配置为 `ddl-auto: validate`，由 Flyway 建表、Hibernate 仅校验 | ✅ 属实 |

**差异/边界（不构成声明不符，但需登记）**：
1. 迁移可重复执行的前提是**库为空**；对非空且无历史表的库，`baseline-on-migrate` 会跳过 V1 并导致启动失败（§7 实测）。
2. 只有 1 个迁移脚本，因此"可重复执行"只被单脚本验证；若将来新增 V2，其幂等性不在本次证据覆盖范围内。
3. `application.yml` 的三处 Flyway 输出中有一条 `All configured schemas are empty; baseline operation skipped...` 为 Flyway 11 的常规提示，非异常。
4. 分支自述文档 `verification-results.md` 中 H2/Flyway 相关条目原本标注为"待验证"类描述；其"V1__schema.sql 已完整定义所有表"的说法经本次实测成立（13/13 表）。

---

## 9. 结论

**【实测-符合】**

核验项 V9「claude-opus-5.5：引入 Flyway 版本化迁移，数据库迁移可重复执行（清库重跑幂等成功）」在真实运行环境下**成立**：

- Flyway 真实接入（`flyway-core-11.7.2`，随应用启动自动执行，非文档虚写）；
- 迁移脚本 1 个（`V1__schema.sql`，165 行，建 13 表 3 索引），实测声明的对象全部落库；
- 实验一（清库首跑）：`Successfully applied 1 migration ... now at version v1`，history 2 行，13 表齐全，API 200；
- 实验二（原地重跑）：`Schema "PUBLIC" is up to date. No migration necessary.`，零 checksum/重复执行错误，history 始终 2 行、checksum 不变；
- 实验三（二次清库重跑）：与首跑逐行同构成功，证明脚本可从头重复执行、无环境残留依赖。

**唯一需登记的边界**：迁移的可重复性以"空库"为前提；对已有非空 schema 且无 `flyway_schema_history` 的库，baseline 会跳过 V1 并导致 `Schema-validation: missing table [config_data_rows]` 启动失败（§7 实测）。合流时若复用旧库须先清库。

---

## 10. 附件清单（同目录）

| 文件 | 内容 |
|---|---|
| `V9-附件-实验一-清库首跑日志.log` | 实验一完整启动日志（10,284 字节，Spring Boot 3.5.14 / PID 4132 / 端口 18294 / Java 21.0.12） |
| `V9-附件-实验二-原地重跑日志.log` | 实验二完整启动日志（9,087 字节，PID 24708） |
| `V9-附件-实验三-二次清库重跑日志.log` | 实验三完整启动日志（10,284 字节，PID 8928） |
| `V9-附件-补充观察-已有非空schema启动失败日志.log` | §7 补充观察的完整失败日志（14,375 字节，PID 23752，退出码 1） |
| `V9-附件-H2查询输出汇总.txt` | 各次 `flyway_schema_history` / 表清单 / 索引清单的 H2 客户端原始输出 |

---

## 11. 现场清理与遗留状态

- **端口**：三次启动的应用进程（PID 4132 / 24708 / 8928）均已终止；补充观察进程（23752）自行退出（码 1）。
  `netstat -ano | grep 18294` 最终只余若干来自本机 curl 健康检查的 **客户端 TIME_WAIT** 条目（`[::1]:2086|2089|8959|14500|14501 → [::1]:18294`），**无任何 LISTENING**，端口 18294 已释放。
  `tasklist | grep java.exe` 仅剩 PID 11808、11484 两个与本次核验无关的进程（本次四个 PID 均不在其中）。
- **数据库文件**：`backend/data/config_mgr_db.mv.db` 已从备份恢复为实验三结束时的完整库（868,352 字节）；无 `*.trace.db` 残留。
- **备份位置**：`/tmp/v9-backups/`（`exp2-db-backup-config_mgr_db.mv.db`、`exp2-db-backup-config_mgr_db.trace.db`、`exp3-db-backup-config_mgr_db.mv.db` 于恢复时移回）。实验前分支本身不存在库文件，无需备份。
- **仓库改动**：被核验分支 **未做任何源代码/迁移脚本/配置修改，未执行任何 git 写操作**。仅新增构建产物 `backend/target/`（按任务要求保留）与运行期数据库 `backend/data/`（两者均被 `.gitignore` 忽略）。
- **未执行项**：无。三次实验 + 补充观察全部完成，无失败重试，无环境受限项。
