# G1 核验：main-v2 基座后端构建 / Flyway 清库首跑 / 种子落库 / 原地重跑幂等 / 前端构建侦察

- **被核验仓库**：`<MAIN_V2>`（`<MAIN_V2>`）
- **分支 / HEAD**：`main-v2` / `ed6745b`（`docs: S4.2 AI Runtime 合流执行规格书…`，2026-10-07）
- **基座定义**：`claude-opus-5.5 + deepseek-v4-pro` 快照（backend 为后端）
- **核验日期**：2026-10-07
- **核验方式**：真实执行 — Maven 编译 → 打包 jar → `java -jar` 两次真实启动（首次清库 / 二次原地）+ HTTP 只读接口实测
- **核验端口**：18297（`--server.port=18297` 首跑、`SERVER_PORT=18297` 环境变量二次启动）
- **工具链**：Apache Maven 3.9.16（`<MAVEN_HOME>`）/ JDK 21.0.12（Oracle）/ Spring Boot 3.5.14 / Spring 6.2.18 / H2 2.3.232 / Tomcat 10.1.54
- **AI Key**：`DEEPSEEK_API_KEY=***`（占位值，仅用于启动，不发起真实 AI 调用）
- **结论汇总**：G1 ✅ 干净编译通过；G2 ✅ 清库首跑 Flyway 应用 V1+V2；G3 ✅ 种子 7 定义 / 1230 数据行落库并经只读 API 复核；G4 ✅ 原地重跑 `up to date` 无冲突；G5 ✅ 侦察完成（未安装、未构建）
- **纪律声明**：除本证据文档外未修改任何源码/配置；未执行任何 `git` 写操作（未 commit，未触发 pre-commit hook）

---

## G1 编译：`mvn clean compile`

### 执行的命令

```
cd <MAIN_V2>/backend
export DEEPSEEK_API_KEY=***
"<MAVEN_HOME>/bin/mvn.cmd" -B clean compile
```

> 说明：手册建议用 `mvn -q clean compile`，但 `-q` 会吞掉 `BUILD SUCCESS`/耗时行，为使证据可复核改用 `-B`（非交互批量模式，日志同样无进度条噪声），其余参数一致。

### 输出原文关键行

```
[INFO] --- clean:3.4.1:clean (default-clean) @ config-mgr ---
[INFO] --- resources:3.3.1:resources (default-resources) @ config-mgr ---
[INFO] Copying 1 resource from src\main\resources to target\classes
[INFO] Copying 2 resources from src\main\resources to target\classes
[INFO] --- compiler:3.14.1:compile (default-compile) @ config-mgr ---
[INFO] Recompiling the module because of changed source code.
[INFO] Compiling 77 source files with javac [debug parameters release 21] to target\classes
[INFO] 由于在类路径中发现了一个或多个处理程序，因此启用了
  批注处理。未来发行版的 javac 可能会禁用批注处理，
  除非至少按名称指定了一个处理程序 (-processor)，
  或指定了搜索路径 (--processor-path, --processor-module-path)，
  或显式启用了批注处理 (-proc:only, -proc:full)。
  可使用 -Xlint:-options 隐藏此消息。
  可使用 -proc:none 禁用批注处理。
[INFO] .../configmgr/excel/ExcelWriter.java: ...使用或覆盖了已过时的 API。
[INFO] .../configmgr/excel/ExcelWriter.java: 有关详细信息, 请使用 -Xlint:deprecation 重新编译。
[INFO] ------------------------------------------------------------------------
[INFO] BUILD SUCCESS
[INFO] ------------------------------------------------------------------------
[INFO] Total time:  10.355 s
[INFO] Finished at: 2026-10-07T01:22:52+08:00
```

- 退出码 `EXIT=0`；墙钟耗时 **13.2 s**（Maven 自报 `Total time: 10.355 s`）。
- 编译告警仅 2 类，均为非阻断：① javac 未显式指定注解处理器路径的通用提示；② `excel/ExcelWriter.java` 使用 Apache POI 已过时 API（`-Xlint:deprecation`）。**无 ERROR、无 failOnWarning 触发**。
- 原始日志：`<VERIFY_TMP>/g1-compile.log`（仓库外临时目录，避免污染待提交状态）。

### 测试代码编译（`mvn test-compile`）

```
[INFO] --- maven-resources-plugin:3.3.1:testResources (default-testResources) @ config-mgr ---
[INFO] Not copying test resources
[INFO] --- maven-compiler-plugin:3.14.1:testCompile (default-testCompile) @ config-mgr ---
[INFO] Nothing to compile - all classes are up to date.
[INFO] No sources to compile
[INFO] BUILD SUCCESS
[INFO] Total time:  2.603 s
```

- **本基座 backend 不存在 `src/test/` 目录**（`find src/test -type f` 计数 = 0），故 `test-compile` 恒为 `No sources to compile`；`src/main/java` 下 `*.java` 共 77 个。G1 无测试代码可编译，后续 S4.4 若要跑测试需先确认测试来源。

### 结论

**【实测-通过】** `main-v2` 基座后端在 JDK 21 + Maven 3.9.16 下 **干净编译通过**（`clean` 已删 target，77 源文件全量重编，10.4 s），无编译错误；仅有 javac 注解处理器提示与 1 处 POI 弃用 API 告警。

---

## G2 Flyway 清库首跑

### 清库前状态（“备份并删除 backend/data/”）

```
$ ls -la <MAIN_V2>/backend/data
NO data dir before start
$ cp -r data <VERIFY_TMP>/data-backup
nothing to back up
```

- 启动前 **`backend/data/` 目录不存在**（此前从未在本机启动过该基座），因此无残留库可删、无需备份 —— 满足“真正清库首跑”的最强前提，比“删库后重跑”更干净。

### 启动命令

```
cd <MAIN_V2>/backend
export DEEPSEEK_API_KEY=***
java -jar target/config-mgr.jar --server.port=18297
```

先打包：`"<MAVEN_HOME>/bin/mvn.cmd" -B -DskipTests package` → `BUILD SUCCESS`，`Total time: 4.905 s`，产物 `target/config-mgr.jar`（83.9 MB，spring-boot repackage 完成，日志 `Replacing main artifact … adding nested dependencies in BOOT-INF/`）。

启动前 `netstat -ano | grep :18297` = 空（端口未被占用；18080/18290-18296/18301-18307/18312 均未使用）。

### Flyway 日志原文（本次迁移版本）

```
2026-10-07T01:23:22.671  INFO  HikariPool-1 - Added connection conn0: url=jdbc:h2:file:./data/config_mgr_db user=SA
2026-10-07T01:23:22.671  INFO  org.flywaydb.core.FlywayExecutor : Database: jdbc:h2:file:./data/config_mgr_db (H2 2.3)
2026-10-07T01:23:22.722  INFO  o.f.c.i.s.JdbcTableSchemaHistory : Schema history table "PUBLIC"."flyway_schema_history" does not exist yet
2026-10-07T01:23:22.726  INFO  o.f.core.internal.command.DbValidate : Successfully validated 2 migrations (execution time 00:00.021s)
2026-10-07T01:23:22.734  INFO  org.flywaydb.core.Flyway : All configured schemas are empty; baseline operation skipped. A baseline or migration script with a lower version than the baseline version may execute if available. Check the Schemas parameter if this is not intended.
2026-10-07T01:23:22.736  INFO  o.f.c.i.s.JdbcTableSchemaHistory : Creating Schema History table "PUBLIC"."flyway_schema_history" ...
2026-10-07T01:23:22.783  INFO  o.f.core.internal.command.DbMigrate : Current version of schema "PUBLIC": << Empty Schema >>
2026-10-07T01:23:22.812  INFO  o.f.core.internal.command.DbMigrate : Migrating schema "PUBLIC" to version "1 - schema"
2026-10-07T01:23:22.877  INFO  o.f.core.internal.command.DbMigrate : Migrating schema "PUBLIC" to version "2 - staging base version"
2026-10-07T01:23:22.898  INFO  o.f.core.internal.command.DbMigrate : Successfully applied 2 migrations to schema "PUBLIC", now at version v2 (execution time 00:00.063s)
```

**应用的迁移版本：V1 `schema`、V2 `staging base version`，终态 v2，2 条全部成功，耗时 63 ms。**

脚本与配置对应关系（`<MAIN_V2>/backend/src/main/resources/`）：

| 版本 | 脚本 | 行数 | 内容 |
|---|---|---|---|
| V1 | `db/migration/V1__schema.sql` | 165 | 建 12 张表：`regions`/`projects`/`config_definitions`/`config_fields`/`config_dependencies`/`config_data_rows`/`config_staging_rows`/`tasks`/`task_items`/`task_files`/`jobs`/`job_items`/`validation_issues` |
| V2 | `db/migration/V2__staging_base_version.sql` | 4 | `ALTER TABLE config_staging_rows ADD COLUMN base_version BIGINT;`（发布并发冲突检测） |

配置：`spring.flyway.enabled=true`、`locations=classpath:db/migration`、`baseline-on-migrate=true`；`spring.jpa.hibernate.ddl-auto=validate`（故 schema 必须由 Flyway 建出，`validate` 通过本身即证明 V1 建表与实体一致）。

### 启动成功与端口监听确认

```
2026-10-07T01:23:21.617  INFO  o.s.b.w.embedded.tomcat.TomcatWebServer : Tomcat initialized with port 18297 (http)
2026-10-07T01:23:27.622  DEBUG  c.e.configmgr.ai.tool.ToolRegistry : …（注册 16 个 AI 工具）
2026-10-07T01:23:28.891  INFO  o.s.b.a.h2.H2ConsoleAutoConfiguration : H2 console available at '/h2-console'. Database available at 'jdbc:h2:file:./data/config_mgr_db'
2026-10-07T01:23:28.991  INFO  o.s.b.w.embedded.tomcat.TomcatWebServer : Tomcat started on port 18297 (http) with context path '/'
2026-10-07T01:23:29.017  INFO  c.e.configmgr.ConfigMgrApplication : Started ConfigMgrApplication in 11.287 seconds (process running for 12.126)
```

```
$ netstat -ano | grep -E ":18297\s"
  TCP    0.0.0.0:18297          0.0.0.0:0              LISTENING       28936
  TCP    [::]:18297             [::]:0                 LISTENING       28936
$ ls -la <MAIN_V2>/backend/data
-rw-r--r-- 1 <USER> 197609 954368 Oct  7 01:23 config_mgr_db.mv.db
```

- **启动成功**（11.287 s，无异常栈、无 Flyway 报错）；**端口 18297 双栈 LISTENING**（PID 28936）；H2 文件库已落盘（954 KB）。
- 空 key 相关：以 `DEEPSEEK_API_KEY=***` 占位启动，Spring AI 自动装配**未阻断启动**（与已知 Q2 结论一致：AI 功能不可用但业务功能不受影响）。

### 结论

**【实测-通过】** 在无库/空 schema 前提下首跑，Flyway 自建 `flyway_schema_history` 并顺序应用 **V1、V2** 至 `v2`，`ddl-auto=validate` 校验通过，服务在 18297 正常监听。

---

## G3 种子落库

### 启动日志中的种子初始化输出原文

```
2026-10-07T01:23:29.098  INFO  c.example.configmgr.seed.DataSeedRunner : Seeding initial data...
2026-10-07T01:23:31.412  INFO  c.example.configmgr.seed.DataSeedRunner : Definitions and seed data created successfully
2026-10-07T01:23:31.412  INFO  c.example.configmgr.seed.DataSeedRunner : Seed data complete
```

种子实现：`<MAIN_V2>/backend/src/main/java/com/example/configmgr/seed/DataSeedRunner.java`（`ApplicationRunner` + `@Transactional`），幂等判据为 `regionRepository.count() > 0` → 打 `Seed data already exists, skipping` 后直接返回。

### 只读 API 实测：配置定义列表

```
$ curl -s -w "HTTP=%{http_code}" http://localhost:18297/api/definitions
HTTP=200
{"success":true,"data":[ … 7 项 … ]}
```

**返回配置项数量 = 7**，名称与层级清单：

| id | code | name | level | 字段数 | sortOrder |
|---|---|---|---|---|---|
| 1 | `CURRENCY` | 货币字典 | GLOBAL | 4 | 0 |
| 2 | `DOC_TYPE` | 单据类型 | GLOBAL | 4 | 1 |
| 3 | `APPROVE_ROLE` | 审批角色 | GLOBAL | 3 | 2 |
| 4 | `TAX_RATE` | 税率配置 | REGION | 5 | 3 |
| 5 | `PROJ_PARAM` | 项目参数 | PROJECT | 4 | 5 |
| 6 | `PROJ_APPROVE` | 项目审批流 | PROJECT | 6 | 6 |
| 7 | `PROJ_PRICE` | 项目价格表 | PROJECT | 7 | 8 |

**层级分布：GLOBAL 3 个（CURRENCY / DOC_TYPE / APPROVE_ROLE）、REGION 1 个（TAX_RATE）、PROJECT 3 个（PROJ_PARAM / PROJ_APPROVE / PROJ_PRICE）。**

字段级抽样核对（`GET /api/definitions` 原文）：`CURRENCY.fields` = `code`(STRING,required,key) / `name` / `exchangeRate`(NUMBER) / `decimalPlaces`(NUMBER)；`DOC_TYPE.category` 为 ENUM，`optionsJson` = `[{"label":"采购单","value":"PURCHASE"},{"label":"销售单","value":"SALES"},{"label":"调拨单","value":"TRANSFER"},{"label":"盘点单","value":"INVENTORY"}]`；`PROJ_APPROVE.approveRole` 与 `PROJ_PRICE.currencyCode` 为 REFERENCE（分别指向 `APPROVE_ROLE.code`、`CURRENCY.code`）。

### 主数据与数据行落库复核（同为只读接口）

```
$ curl -s http://localhost:18297/api/master/regions   → HTTP=200  count=4
    HE=华东区, HS=华南区, HB=华北区, XN=西南区
$ curl -s http://localhost:18297/api/master/projects  → HTTP=200  count=7
    HE-P001=上海智慧园区, HE-P002=杭州数字工厂, HS-P001=广州南沙项目, HS-P002=深圳湾科技园,
    HB-P001=北京朝阳总部, XN-P001=成都天府新区, XN-P002=重庆两江新区
$ curl -s http://localhost:18297/api/data/<defCode>/count
    CURRENCY      → 5
    DOC_TYPE      → 5
    APPROVE_ROLE  → 4
    TAX_RATE      → 16
    PROJ_PARAM    → 0
    PROJ_APPROVE  → 0
    PROJ_PRICE    → 1200
```

与种子代码逐项对齐（全部命中，无偏差）：

| 定义 | 传入行数 | 落库请求返回 | 对齐说明 |
|---|---|---|---|
| CURRENCY | 5（CNY/USD/EUR/GBP/JPY） | 5 | GLOBAL，`key=code` |
| DOC_TYPE | 5（PO/SO/TO/IC/PR） | 5 | GLOBAL |
| APPROVE_ROLE | 4（DEPT_MGR/FINANCE/VP/CEO） | 4 | GLOBAL |
| TAX_RATE | 4 区 × 4 行 = 16 | 16 | REGION，按 HE/HS/HB/XN 分别写入 |
| PROJ_PARAM | 0 | 0 | 仅建定义，不预置项目参数行 |
| PROJ_APPROVE | 0 | 0 | 仅建定义，不预置审批流行 |
| PROJ_PRICE | 4 项目 × 300 行 = 1200 | 1200 | 演示大数据集，`Random(42)` 确定性生成 |

**种子数据行合计 1230 行**（5+5+4+16+0+0+1200）。

### 结论

**【实测-通过】** combined 基座自带 **7 个配置定义**（GLOBAL 3 / REGION 1 / PROJECT 3）、**4 个地区、7 个项目、1230 行配置数据**，全部落库并经只读 API 逐项复核一致。

---

## G4 原地重跑幂等（不删库直接重启）

### 执行

- 停掉首跑进程（确认 `18297 NOT LISTENING`）后，**不删除 `backend/data/`**（`config_mgr_db.mv.db` 原样保留），改用环境变量方式重启以额外验证端口注入路径：

```
cd <MAIN_V2>/backend
export DEEPSEEK_API_KEY=***
export SERVER_PORT=18297
java -jar target/config-mgr.jar
```

### Flyway 日志原文

```
2026-10-07T01:24:15.126  INFO  org.flywaydb.core.FlywayExecutor : Database: jdbc:h2:file:./data/config_mgr_db (H2 2.3)
2026-10-07T01:24:15.170  INFO  o.f.core.internal.command.DbValidate : Successfully validated 2 migrations (execution time 00:00.022s)
2026-10-07T01:24:15.180  INFO  o.f.core.internal.command.DbMigrate : Current version of schema "PUBLIC": 2
2026-10-07T01:24:15.182  INFO  o.f.core.internal.command.DbMigrate : Schema "PUBLIC" is up to date. No migration necessary.
2026-10-07T01:24:20.616  INFO  o.s.b.w.embedded.tomcat.TomcatWebServer : Tomcat started on port 18297 (http) with context path '/'
2026-10-07T01:24:20.632  INFO  c.e.configmgr.ConfigMgrApplication : Started ConfigMgrApplication in 9.913 seconds (process running for 10.732)
2026-10-07T01:24:20.718  INFO  c.example.configmgr.seed.DataSeedRunner : Seed data already exists, skipping
```

- **无 Flyway 冲突**：已存在 schema 时不再走 baseline，直接 `Successfully validated 2 migrations` → `Current version of schema "PUBLIC": 2` → `Schema "PUBLIC" is up to date. No migration necessary.`；无 `checksum mismatch` / `Detected failed migration` / `FlywayException`。
- **种子幂等**：`Seed data already exists, skipping`，未重复插入（数据行计数与首跑一致）。
- `SERVER_PORT=18297` 环境变量注入生效（Tomcat started on port 18297），启动 9.913 s，比首跑快约 1.4 s。
- 交叉印证：`<MAIN_V2>/docs/evidence/V9-claude-Flyway迁移幂等核验.md` 曾在 claude 分支（端口 18294）实测同一迁移集的清库首跑/原地重跑/二次清库三实验，结论为【实测-符合】；本次在 main-v2 上于 18297 复现出完全一致的日志语义。

### 结论

**【实测-通过】** 原地重启幂等成立：Flyway 报告 `up to date` 且应用正常启动，种子跳过，无任何迁移冲突。

---

## G5 前端构建侦察（只侦察，未安装、未构建）

### 目录与工具链

```
<MAIN_V2>/frontend$ ls -a
.env.example  index.html  package.json  public  src  vite.config.js  yarn.lock
node_modules ABSENT        ← 未安装依赖
```

| 项 | 实测值 |
|---|---|
| `node -v` | **v22.23.2** |
| `npm -v` | **10.9.8** |
| `yarn -v` | **1.22.22**（仓库存在 `yarn.lock` → 预期包管理器为 yarn 1.x） |
| `pnpm -v` | 10.32.1（本机亦可用，但仓库无 `pnpm-lock.yaml`，非既定选择） |
| `node_modules/` | **不存在**（`frontend/node_modules/` 已在 `.gitignore` 中排除） |
| `dist/` | 不存在 |

### `package.json` 依赖清单

**scripts**：`dev: vite`、`build: vite build`、`preview: vite preview`（无 `lint` / `test` / `typecheck` 脚本）

**dependencies（11 个）**

| 包 | 版本 |
|---|---|
| `vue` | ^3.5.13 |
| `vue-router` | ^4.5.0 |
| `pinia` | ^3.0.4 |
| `element-plus` | ^2.9.3 |
| `@element-plus/icons-vue` | ^2.3.1 |
| `@grapecity/spread-sheets` | 17.1.5（精确锁定） |
| `@grapecity/spread-sheets-vue` | 17.1.5 |
| `@grapecity/spread-sheets-resources-zh` | 17.1.5 |
| `@grapecity/spread-excelio` | 17.1.5 |
| `axios` | ^1.7.9 |
| `dayjs` | ^1.11.13 |

**devDependencies（5 个）**

| 包 | 版本 |
|---|---|
| `vite` | ^7.3.6 |
| `@vitejs/plugin-vue` | ^6.0.9 |
| `tailwindcss` | 4.3.3（精确锁定） |
| `@tailwindcss/vite` | 4.3.3 |
| `sass` | ^1.83.4 |

### 与 S4.4（前端构建/联调）相关的关键侦察结论

1. **必须 `yarn install` 后才能构建**：`node_modules` 缺失，`yarn.lock` 存在，建议 `yarn install --frozen-lockfile` 以锁定 17.1.5 / 4.3.3 等精确版本；SpreadJS 4 个包为商业组件，需关注镜像可达性与授权（`.env.example` 提示 `VITE_SPREADJS_KEY` 不填则评估模式带水印）。
2. **dev server 端口 5173，`/api` 代理硬编码指向 `http://localhost:8081`**（`vite.config.js`）。本次后端跑在 18297，**直接 `yarn dev` 会导致前端请求打不到后端**；S4.4 需按合流端口约定调整代理目标（或另行注入），并注意代理已对 SSE 做 `Accept-Encoding: identity` 处理（不缓冲）。
3. **生产构建入口**：`yarn build` → `vite build` → 输出 `frontend/dist`（`sourcemap: false`）；无 `eslint`/`prettier`/测试脚本，故构建门禁只能以 `vite build` 成功为准。
4. **未执行任何安装或构建动作**（按手册要求仅侦察）；本项不消耗网络与磁盘。

### 结论

**【实测-侦察完成】** 依赖清单与脚本已固化；`node_modules` 缺失、代理目标为 8081，是 S4.4 前必须处理的两个前置条件。

---

## combined 基座自带种子清单（汇总）

| 类别 | 数量 | 明细 |
|---|---|---|
| 配置定义 | **7** | GLOBAL：`CURRENCY`(货币字典,4 字段)、`DOC_TYPE`(单据类型,4)、`APPROVE_ROLE`(审批角色,3)；REGION：`TAX_RATE`(税率配置,5)；PROJECT：`PROJ_PARAM`(项目参数,4)、`PROJ_APPROVE`(项目审批流,6)、`PROJ_PRICE`(项目价格表,7) |
| 层级分布 | 3 / 1 / 3 | GLOBAL 3 个、REGION 1 个、PROJECT 3 个 |
| 地区 | **4** | HE 华东区、HS 华南区、HB 华北区、XN 西南区 |
| 项目 | **7** | HE-P001 上海智慧园区、HE-P002 杭州数字工厂、HS-P001 广州南沙项目、HS-P002 深圳湾科技园、HB-P001 北京朝阳总部、XN-P001 成都天府新区、XN-P002 重庆两江新区 |
| 配置数据行 | **1230** | CURRENCY 5、DOC_TYPE 5、APPROVE_ROLE 4、TAX_RATE 16(4 区×4)、PROJ_PARAM 0、PROJ_APPROVE 0、PROJ_PRICE 1200(4 项目×300) |
| AI 工具 | 16 | `ToolRegistry: registered 16 tools`（list_config_defs、get_config_def、create_task、start_export/publish 等） |
| Flyway 迁移 | 2 | V1 schema（12 张表）、V2 staging base_version（`config_staging_rows.base_version`） |

---

## 纪律与收尾

### 仓库状态（未引入待提交噪音）

```
$ git status --short          # 核验前后一致，均为本次核验开始前已存在的改动
 M scripts/githooks/pre-commit
?? docs/adr/S3-Challenger重审报告.md
?? scripts/githooks/README.md
?? scripts/githooks/scan-history.sh
?? scripts/githooks/scan-lib.sh
```

- `git status --short | grep -i data` → **无 `data` 条目**；`git check-ignore -v backend/data/config_mgr_db.mv.db` → `.gitignore:3:backend/data/`，即 **H2 运行产物 `backend/data/` 已被忽略，不会出现在待提交**。
- `backend/target/`、`*.jar`、`*.db` 亦在 `.gitignore` 覆盖范围内。
- **`backend/data/` 按要求保留**（`config_mgr_db.mv.db`），未删除也未纳入版本库。
- 未执行任何 `git` 写操作；未 commit，未触发 pre-commit hook。

### 进程与端口

```
$ netstat -ano | grep -E ":(18297|18296)\s+.*LISTENING"   → （空）
$ tasklist /FI "IMAGENAME eq java.exe"                    → 无运行中的 java 进程
```

- **18297 已释放**，自启后端进程全部关闭（首跑、G4 重跑两次启动均已确认不再监听）。
- 附带观察（与本次核验无关，非本代理启动）：核验期间曾发现一个残留 `java -jar target/config-mgr.jar --server.port=18296` 进程（属其他并行核验遗留）；收尾时该进程同样已不存在，**18296 亦已释放**。18080/18290-18296/18301-18307/18312 全程未被本代理使用。

### 临时产物位置（仓库外，不污染待提交状态）

`<VERIFY_TMP>/`：`g1-compile.log`、`g1-testcompile.log`、`g2-package.log`、`g2-boot1.log`、`g4-boot2.log`、`defs.json`、`regions.json`、`projects.json`、`defs-summary.txt`。

---

## 遗留与建议

1. **本基座 backend 无任何测试代码**（无 `src/test/`）：G1 的 `test-compile` 无实际约束力，S4.4 若期望“测试通过”出口，需先确认测试套件来源（是各分支自带还是合流后补）。
2. **前端代理端口硬编码 8081**，与本次合流核验端口（18297）不一致；S4.4 联调前必须处理，否则前后端联不通。建议以环境变量或 `vite --port` + 代理目标可配置的方式解决，而非手工改源码。
3. **空 key 占位启动可行**：`DEEPSEEK_API_KEY=***` 不阻断启动，但任何真实 AI 调用都会失败；G1-G5 全程未触发 AI 调用，AI 链路能力仍需以真实 key 另行核验。
4. 首跑日志中 `baseline-on-migrate: true` 在空 schema 上打印 `All configured schemas are empty; baseline operation skipped`，属正常语义（未插入 baseline 记录）；但**若在已有非空 schema 的库上启动，会走 baseline 路径并可能跳过 V1**，该边界已在 V9 证据中单独记录，本次未复现。
