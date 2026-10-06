# G2 核验：glm-5.3 种子数据体系并集移植（main-v2 基座）

- **被核验仓库**：`<MAIN_V2>`（main-v2 分支，基座=combined 快照）
- **分支 / HEAD**：`main-v2` / `bc70a22`
- **移植来源（只读）**：`<REPO_ROOT>/backend`（glm-5.3 分支，`<REPO_ROOT>` 即来源仓库根 `ai-example-glm-5.3`），种子类 `com/example/quickstart/service/SeedService.java`
- **核验日期**：2026-10-07
- **核验方式**：真实执行 — 备份并清空 `backend/data/` → Maven 打包 → `java -jar` 首跑（端口 18298）→ 只读 HTTP 接口实测（定义/数据/计数）→ **不删库原地重启**验幂等 → 关进程并确认端口释放
- **工具链**：JDK 21（PATH）/ Maven `<MAVEN_HOME>` / Spring Boot 3.5.14 / H2 2.3 / Tomcat 10.1
- **AI Key**：`DEEPSEEK_API_KEY=***`（占位，仅用于启动；全程未发起真实 AI 调用）
- **结论汇总**：A ✅ 15 个定义落库（7 基座 + 8 glm，按 code 去重无重叠）；B ✅ 三层级分布 `{GLOBAL:7, REGION:3, PROJECT:5}`；C ✅ glm 8 定义数据 370 行落库、每范围行数均匀、引用完整性成立；D ✅ REGION_NETWORK 抽查 12 行=4 地区×3 行、每地区网关恒定、字段随行序变化；E ✅ 原地重启幂等（种子跳过、计数零变化、无冲突）
- **纪律声明**：**未修改基座领域模型，未修改既有 7 定义的播种逻辑（`DataSeedRunner` 零改动）**；未执行任何 `git` 写操作（未 add/commit，未触发 pre-commit hook）

---

## 一、模型映射说明（glm → main-v2）

两套种子模型逐层对照如下。映射原则：**字段类型以基座模型为准；基座表达不了的列（无对应列）与类型（SCOPE）记录差异并降级表达，不改基座领域模型**。

### 1.1 定义模型

| 维度 | glm（`entity/ConfigDef`） | main-v2（`definition/entity/ConfigDefinition`） | 映射结论 |
|---|---|---|---|
| 主键 | `id`（自增） | `id`（自增） | 一致 |
| 编码 | `code`（唯一，VARCHAR 64） | `code`（唯一，VARCHAR 64） | 一致，作为并集去重键 |
| 层级 | `level` 字符串（`GLOBAL/REGION/PROJECT`） | `level` 枚举 `ConfigLevel`（同名同值） | 直接映射 |
| 排序 | 无 | `sort_order` | glm 定义排序缺省；新增 8 定义取 100~107（排在基座 0~8 之后） |
| 行数元数据 | `rowCount`（种子生成的期望行数） | **无此列** | 差异 ①：不落库，改由数据行数体现 |
| 依赖表达 | `dependsOn`（定义级 JSON 数组，元素 `{def,field,refField,label}`） | 字段级 `field_type=REFERENCE` + `ref_def_code`/`ref_field_code`；拓扑排序由 `DependencyResolver` 依据 REFERENCE 推导 | 语义等价、载体不同（见 1.4） |
| 时间戳 | `createdAt` | `created_at`/`updated_at`（`@CreationTimestamp`/`@UpdateTimestamp`） | 由框架赋值 |

### 1.2 字段模型

| 维度 | glm（`entity/ConfigField`） | main-v2 | 映射结论 |
|---|---|---|---|
| 归属 | `defId`（外键到定义 id） | `defCode`（外键到定义 code，`@OneToMany` 集合） | 语义等价（按 code 关联） |
| 编码 / 名称 | `fieldCode` / `fieldName` | `code` / `label` | 重命名映射 |
| 类型 | `dataType` 字符串，7 种：`TEXT/INT/DECIMAL/DATE/BOOL/ENUM/SCOPE` | `field_type` 枚举，6 种：`STRING/NUMBER/DATE/ENUM/BOOLEAN/REFERENCE` | 见 1.3 类型映射表 |
| 必填 / 业务键 | `required` / `isKey` | `required` / `isKey`（`is_key`） | 一致 |
| 排序 | `sortNo` | `sort_order`（`DefinitionService.save` 按列表下标重排） | 一致，由基座按声明顺序重排 |
| 枚举选项 | `options` = `["A","B"]`（纯字符串数组） | `optionsJson` = `[{"value":"A","label":"A"}]`（对象数组） | 形状不同，值等价；本次按 **value=label=glm 选项串** 写入，规避"模板下拉显示 label、导入读回 value"的口径歧义 |
| 示例值 | `sampleValue` | **无此列** | 差异 ②：不落库（基座 UI 不展示示例值） |
| 引用目标 | 无（在定义级 `dependsOn` 里） | `refDefCode`/`refFieldCode`（字段级） | 见 1.4 |

### 1.3 字段类型映射表（glm 7 种 → 基座 6 种）

| glm `dataType` | 基座 `FieldType` | 说明 |
|---|---|---|
| `TEXT` | `STRING` | 直接映射 |
| `INT` | `NUMBER` | 基座无整型/小数型之分 |
| `DECIMAL` | `NUMBER` | 同上（数据值仍保留小数位） |
| `BOOL` | `BOOLEAN` | 直接映射 |
| `DATE` | `DATE` | 直接映射（值用 `yyyy-MM-dd` 字符串） |
| `ENUM` | `ENUM` | 选项形状改为 `{value,label}` |
| **`SCOPE`** | **无对应类型** | **差异 ③（重点）**：基座 `FieldType` 无 SCOPE。降级表达为 `STRING` + 保留 `key=true`，字段编码改用基座既有的范围载体字段名 |
| （无，由 `dependsOn` 表达） | `REFERENCE` | 见 1.4 |

### 1.4 依赖与范围（两处最关键的模型差异）

**（1）依赖：定义级 `dependsOn` → 字段级 REFERENCE**

glm：`ALARM_THRESHOLD.dependsOn = [{"def":"METRIC_DICT","field":"metricCode","refField":"metricCode","label":"指标编码"}]`，字段本身仍是 `TEXT`，依赖只用于排序/提示。

main-v2：等价表达是把该字段声明为 `REFERENCE` 并给出 `ref_def_code`/`ref_field_code`。基座两条既有链路都依赖这一点：
- `DependencyResolver.sort()` 通过扫描 REFERENCE 字段推导定义间依赖（`definition/service/DependencyResolver.java:43`）；
- `PrecheckJobRunner` 的引用完整性校验（值必须在被引用配置的已发布/暂存数据中存在，`job/service/PrecheckJobRunner.java:178-181`）。

因此移植时：
- `ALARM_THRESHOLD.metricCode` → `REFERENCE` → `METRIC_DICT.metricCode`
- `PROJECT_MEMBER.roleCode` → `REFERENCE` → `ROLE_DICT.roleCode`

播种顺序改为调用基座 `DependencyResolver.sort([8 个 code])` 求拓扑序，首跑日志实测输出：
`[SYS_PARAM, METRIC_DICT, ROLE_DICT, REGION_NETWORK, REGION_TARIFF, PROJECT_ENV, ALARM_THRESHOLD, PROJECT_MEMBER]`（`ALARM_THRESHOLD` 被排到 `METRIC_DICT` 之后、`PROJECT_MEMBER` 排到 `ROLE_DICT` 之后，语义正确）。

**（2）范围：glm `__scope` 伪字段 / `ScopeDict` → 基座"范围载体字段 + scope_key 列"**

- glm 的范围是**数据行自带的一个 SCOPE 类型字段**（`__scope`，且是业务键），区域/项目字典独立存在 `ScopeDict` 表；发布时按 `__scope` 分组。
- main-v2 的范围是**行级列** `config_data_rows.scope_type/scope_key`，且导入链路从行的 `regionCode`（REGION 级）/`projectCode`（PROJECT 级）字段反推 scopeKey（`job/service/ImportJobRunner.java:109-110`）。基座既有 `TAX_RATE`（含 `regionCode` 字段+键）、`PROJ_*`（含 `projectCode` 字段+键）即此约定。

因此：
- `__scope` **不能沿用编码**：基座 `DefinitionService.validateFields` 要求字段编码匹配 `[A-Za-z][A-Za-z0-9_]*`（下划线开头被拒），`__scope` 直接落库会被校验拦下；
- 映射为 `regionCode`/`projectCode`（`STRING`，required，key），其值同时写入行的 `scope_key` 与该字段值，与基座既有定义口径完全一致；
- 范围取值改用**基座主数据**（4 地区 `HB/HE/HS/XN`、7 项目 `HB-P001…XN-P002`），未新增 glm 的 `ScopeDict`（理由见第五节差异 ⑤）。

### 1.5 数据模型

| 维度 | glm（`entity/ConfigData`） | main-v2（`ConfigDataRow`） | 映射结论 |
|---|---|---|---|
| 归属 | `defId` | `defCode` | 按 code 关联 |
| 范围 | 行内 `__scope` 字段 | `scope_type`（GLOBAL/REGION/PROJECT）+ `scope_key`（地区/项目码；GLOBAL 为 NULL） | 见 1.4(2) |
| 业务键 | 无独立键列（键字段即普通字段，可重复） | `row_key`（由定义的主键字段拼出，如 `HB\|100`）+ 唯一约束 `(def_code,scope_type,scope_key,row_key)` | **差异 ④**：基座按业务键去重/更新，glm 允许重复行 → 影响 PROJECT_ENV 行数（见第三节） |
| 值载体 | `fieldValues`（JSON 对象） | `data_json`（JSON 对象） | 一致 |
| 状态 | `status`（PUBLISHED 等） | 无状态列（线上表即已发布；暂存另表 `config_staging_rows`） | 种子数据直接写线上表，语义等价于 glm 的 PUBLISHED |
| 写入口 | `dataRepo.saveAll` 直存 | `ConfigDataService.batchSave(defCode,scopeType,scopeKey,rows,keyFields)`（按 row_key upsert） | 采用基座入口 |

---

## 二、代码改动清单

**新增文件 2 个（均在 `backend` 的种子包内），既有文件 0 修改。**

| 类型 | 路径 | 行数 | 职责 |
|---|---|---|---|
| 新增 | `<MAIN_V2>/backend/src/main/java/com/example/configmgr/seed/GlmSeedService.java` | 260 | glm 8 定义的完整声明（层级/字段/类型/必填/业务键/枚举/引用）、模型映射、范围数据生成与落库；幂等判据 `existsByCode` |
| 新增 | `<MAIN_V2>/backend/src/main/java/com/example/configmgr/seed/GlmSeedRunner.java` | 32 | 触发点：`@EventListener(ApplicationReadyEvent.class)` |

**未修改**：`seed/DataSeedRunner.java`（基座 7 定义播种逻辑与主数据播种**逐字节未动**）、`definition/entity/*`、`definition/service/*`、`data/service/ConfigDataService.java`、`masterdata/*`、Flyway 脚本、配置。

仓库状态证据（只读命令）：

```
$ cd <MAIN_V2> && git status --porcelain
?? backend/src/main/java/com/example/configmgr/seed/GlmSeedRunner.java
?? backend/src/main/java/com/example/configmgr/seed/GlmSeedService.java
?? "docs/adr/S3-Challenger重审报告.md"      # 本次之前已存在的他人在制品，与本次无关
```

两个种子类均为**未跟踪新增**，无任何具名文件的 M（modified）条目 ⇒ 基座既有代码零改动。

### 2.1 两个设计决定的原因

1. **为什么用 `ApplicationReadyEvent` 而不是再加一个 `ApplicationRunner`**：基座 `DataSeedRunner`（`ApplicationRunner`）**未声明 `@Order`**，即 `Ordered.LOWEST_PRECEDENCE`，与新增 Runner 处于同一优先级，二者先后由 Bean 注册顺序决定、不可保证；而 glm 的范围数据生成依赖基座 `regions`/`projects` 主数据已就绪。Spring Boot 保证 `ApplicationReadyEvent` 在**所有 Runner 执行完毕之后**发布，故用它取得确定顺序，且不必改动既有 Runner 的排序或逻辑（首跑日志中 `DataSeedRunner` 的三行日志确实全部早于 `GlmSeedService`）。
2. **幂等判据按定义粒度**：`definitionRepository.existsByCode(code)` 逐个判定 8 个 code，已存在者跳过、缺失者补播 ⇒ 重复启动不重复播种，且单定义缺失时可自愈（全局"有则全跳"会漏补）。

---

## 三、8 配置移植清单（code / 层级 / 字段数 / 数据行数）

### 3.1 清单与字段明细

| # | code | name | 层级 | 字段数 | 字段（编码:类型,键/必填） | 引用/依赖 | 数据行数 |
|---|---|---|---|---|---|---|---|
| 1 | `SYS_PARAM` | 系统参数配置 | GLOBAL | 5 | paramKey:STRING* / paramValue:STRING / paramType:ENUM / description:STRING(选填) / editable:BOOLEAN | — | 40 |
| 2 | `METRIC_DICT` | 指标字典 | GLOBAL | 4 | metricCode:STRING* / metricName:STRING / metricUnit:ENUM / alarmEnabled:BOOLEAN | — | 30 |
| 3 | `ALARM_THRESHOLD` | 告警阈值配置 | GLOBAL | 5 | thresholdName:STRING* / metricCode:REFERENCE / warnThreshold:NUMBER / criticalThreshold:NUMBER / effectiveDate:DATE | → `METRIC_DICT.metricCode` | 120 |
| 4 | `ROLE_DICT` | 成员角色字典 | GLOBAL | 4 | roleCode:STRING* / roleName:STRING / permissionLevel:NUMBER / builtin:BOOLEAN | — | 8 |
| 5 | `REGION_NETWORK` | 地区网络配置 | REGION | 4 | regionCode:STRING* / bandwidthMbps:NUMBER* / gateway:STRING / redundancy:BOOLEAN | — | 12（4 地区×3） |
| 6 | `REGION_TARIFF` | 地区资费配置 | REGION | 4 | regionCode:STRING* / tariffName:STRING* / monthlyFee:NUMBER / billingCycle:ENUM | — | 20（4 地区×5） |
| 7 | `PROJECT_MEMBER` | 项目成员配置 | PROJECT | 5 | projectCode:STRING* / memberName:STRING* / memberEmail:STRING / roleCode:REFERENCE / joinDate:DATE | → `ROLE_DICT.roleCode` | 112（7 项目×16） |
| 8 | `PROJECT_ENV` | 项目环境配置 | PROJECT | 5 | projectCode:STRING* / envName:ENUM* / cpuCores:NUMBER / memoryGb:NUMBER / enabled:BOOLEAN | — | 28（7 项目×4） |

`*` = 业务键（`is_key=true`）。glm 侧类型：1→TEXT/TEXT/ENUM/TEXT/BOOL，2→TEXT/TEXT/ENUM/BOOL，3→TEXT/TEXT/DECIMAL/DECIMAL/DATE，4→TEXT/TEXT/INT/BOOL，5→**SCOPE**/INT/TEXT/BOOL，6→**SCOPE**/TEXT/DECIMAL/ENUM，7→**SCOPE**/TEXT/TEXT/TEXT/DATE，8→**SCOPE**/ENUM/INT/INT/BOOL。字段顺序、必填、业务键、枚举项均与 glm `SeedService` 逐项对齐（仅 `__scope` 改名 + 类型映射）。

枚举项（`optionsJson` 原文，value=label）：
- `SYS_PARAM.paramType` = STRING / NUMBER / BOOLEAN
- `METRIC_DICT.metricUnit` = 百分比 / 毫秒 / MB / 次
- `REGION_TARIFF.billingCycle` = 月付 / 季付 / 年付
- `PROJECT_ENV.envName` = 开发 / 测试 / 预发 / 生产

### 3.2 行数：glm 声明 vs 基座落库

| code | glm 声明行数（含每范围密度） | 基座落库行数 | 关系 |
|---|---|---|---|
| SYS_PARAM | 40 | 40 | 一致（GLOBAL 无范围） |
| METRIC_DICT | 30 | 30 | 一致 |
| ALARM_THRESHOLD | 120 | 120 | 一致 |
| ROLE_DICT | 8 | 8 | 一致 |
| REGION_NETWORK | 15（5 地区×3） | **12（4 地区×3）** | 每范围行数不变，范围数 5→4 |
| REGION_TARIFF | 25（5 地区×5） | **20（4 地区×5）** | 同上 |
| PROJECT_MEMBER | 80（5 项目×16） | **112（7 项目×16）** | 同上（范围数 5→7） |
| PROJECT_ENV | 40（5 项目×8） | **28（7 项目×4）** | 范围数 5→7，且每范围行数被基座业务键上限压到 4（见差异 ④） |
| **合计** | **358** | **370** | 基座 7 定义 1230 行 + glm 8 定义 370 行 = **1600 行** |

> 规律：`每范围行数` 沿用 glm 的每范围密度（15/5=3、25/5=5、80/5=16），`行数 = 每范围行数 × 基座范围数`；GLOBAL 定义行数完全不变。

---

## 四、启动验证记录

### 4.1 环境与命令

```
$ cd <MAIN_V2>/backend
$ cp -r data /tmp/g2-seed/data-backup        # 备份原库（1 个 mv.db，954368 字节）
$ rm -rf data                                # 清库（首跑前提）
$ export DEEPSEEK_API_KEY=***
$ "<MAVEN_HOME>/bin/mvn.cmd" -B clean compile
    [INFO] Compiling 79 source files with javac [debug parameters release 21] to target\classes
    [INFO] BUILD SUCCESS   (Total time: 6.533 s)     # 77 基座 + 2 新增 = 79，无 ERROR
$ "<MAVEN_HOME>/bin/mvn.cmd" -B -DskipTests package
    [INFO] BUILD SUCCESS   (Total time: 4.072 s)     # 产物 target/config-mgr.jar（spring-boot repackage）
$ netstat -ano | grep -E ":18298\s"          # 启动前为空（端口未被占用）
$ java -jar target/config-mgr.jar --server.port=18298
```

*编译告警与 G1 一致，仅 2 类非阻断项：javac 注解处理器通用提示、`excel/ExcelWriter.java` 的 POI 弃用 API。*

### 4.2 首跑（清库）日志原文

```
2026-10-07T01:33:42.541  INFO  o.s.b.w.embedded.tomcat.TomcatWebServer : Tomcat initialized with port 18298 (http)
2026-10-07T01:33:43.106  INFO  com.zaxxer.hikari.pool.HikariPool        : HikariPool-1 - Added connection conn0: url=jdbc:h2:file:./data/config_mgr_db user=SA
2026-10-07T01:33:43.142  INFO  org.flywaydb.core.FlywayExecutor         : Database: jdbc:h2:file:./data/config_mgr_db (H2 2.3)
2026-10-07T01:33:43.178  INFO  org.flywaydb.core.Flyway                 : All configured schemas are empty; baseline operation skipped. ...
2026-10-07T01:33:43.225  INFO  o.f.core.internal.command.DbMigrate      : Migrating schema "PUBLIC" to version "1 - schema"
2026-10-07T01:33:43.264  INFO  o.f.core.internal.command.DbMigrate      : Migrating schema "PUBLIC" to version "2 - staging base version"
2026-10-07T01:33:43.276  INFO  o.f.core.internal.command.DbMigrate      : Successfully applied 2 migrations to schema "PUBLIC", now at version v2
2026-10-07T01:33:47.292  INFO  o.s.b.w.embedded.tomcat.TomcatWebServer : Tomcat started on port 18298 (http) with context path '/'
2026-10-07T01:33:47.308  INFO  c.e.configmgr.ConfigMgrApplication       : Started ConfigMgrApplication in 7.047 seconds
2026-10-07T01:33:47.382  INFO  c.example.configmgr.seed.DataSeedRunner  : Seeding initial data...
2026-10-07T01:33:49.290  INFO  c.example.configmgr.seed.DataSeedRunner  : Definitions and seed data created successfully
2026-10-07T01:33:49.290  INFO  c.example.configmgr.seed.DataSeedRunner  : Seed data complete
2026-10-07T01:33:49.315  INFO  c.example.configmgr.seed.GlmSeedService  : GLM 种子并集移植：待播种 8/8 个定义
2026-10-07T01:33:49.339  INFO  c.example.configmgr.seed.GlmSeedService  : GLM 种子数据播种顺序（依赖拓扑序）：[SYS_PARAM, METRIC_DICT, ROLE_DICT, REGION_NETWORK, REGION_TARIFF, PROJECT_ENV, ALARM_THRESHOLD, PROJECT_MEMBER]
2026-10-07T01:33:49.374  INFO  c.example.configmgr.seed.GlmSeedService  : GLM 种子：SYS_PARAM 落库 40 行（每范围 40 行，范围 []）
2026-10-07T01:33:49.395  INFO  c.example.configmgr.seed.GlmSeedService  : GLM 种子：METRIC_DICT 落库 30 行（每范围 30 行，范围 []）
2026-10-07T01:33:49.402  INFO  c.example.configmgr.seed.GlmSeedService  : GLM 种子：ROLE_DICT 落库 8 行（每范围 8 行，范围 []）
2026-10-07T01:33:49.415  INFO  c.example.configmgr.seed.GlmSeedService  : GLM 种子：REGION_NETWORK 落库 12 行（每范围 3 行，范围 [HB, HE, HS, XN]）
2026-10-07T01:33:49.430  INFO  c.example.configmgr.seed.GlmSeedService  : GLM 种子：REGION_TARIFF 落库 20 行（每范围 5 行，范围 [HB, HE, HS, XN]）
2026-10-07T01:33:49.454  INFO  c.example.configmgr.seed.GlmSeedService  : GLM 种子：PROJECT_ENV 落库 28 行（每范围 4 行，范围 [HB-P001, HE-P001, HE-P002, HS-P001, HS-P002, XN-P001, XN-P002]）
2026-10-07T01:33:49.532  INFO  c.example.configmgr.seed.GlmSeedService  : GLM 种子：ALARM_THRESHOLD 落库 120 行（每范围 120 行，范围 []）
2026-10-07T01:33:49.592  INFO  c.example.configmgr.seed.GlmSeedService  : GLM 种子：PROJECT_MEMBER 落库 112 行（每范围 16 行，范围 [HB-P001, HE-P001, HE-P002, HS-P001, HS-P002, XN-P001, XN-P002]）
2026-10-07T01:33:49.592  INFO  c.example.configmgr.seed.GlmSeedService  : GLM 种子并集移植完成：新增 8 个定义
```

- Flyway V1+V2 全新应用（v2），`ddl-auto=validate` 通过 ⇒ **未新增/变更任何表结构**（种子移植零 DDL）。
- 无异常栈、无约束冲突；`DataSeedRunner` 先执行、`GlmSeedService` 后执行，顺序符合 2.1(1) 的设计。

### 4.3 15 个定义落库（HTTP 只读接口）

```
$ curl -s -o defs.json -w "HTTP=%{http_code}" http://localhost:18298/api/definitions
HTTP=200
```

完整响应原文（17 879 字节，`{"success":true,"data":[...15 项...]}`）作为附件保存：**`docs/evidence/G2-附件-A-15定义API原文.json`**。其精简视图（仅隐去 `id`/`createdAt`/`updatedAt`，其余键值保持原样）如下：

| # | code | name | level | 字段数 | 来源 |
|---|---|---|---|---|---|
| 1 | `CURRENCY` | 货币字典 | GLOBAL | 4 | 基座 |
| 2 | `DOC_TYPE` | 单据类型 | GLOBAL | 4 | 基座 |
| 3 | `APPROVE_ROLE` | 审批角色 | GLOBAL | 3 | 基座 |
| 4 | `TAX_RATE` | 税率配置 | REGION | 5 | 基座 |
| 5 | `PROJ_PARAM` | 项目参数 | PROJECT | 4 | 基座 |
| 6 | `PROJ_APPROVE` | 项目审批流 | PROJECT | 6 | 基座 |
| 7 | `PROJ_PRICE` | 项目价格表 | PROJECT | 7 | 基座 |
| 8 | `SYS_PARAM` | 系统参数配置 | GLOBAL | 5 | glm |
| 9 | `METRIC_DICT` | 指标字典 | GLOBAL | 4 | glm |
| 10 | `ALARM_THRESHOLD` | 告警阈值配置 | GLOBAL | 5 | glm |
| 11 | `ROLE_DICT` | 成员角色字典 | GLOBAL | 4 | glm |
| 12 | `REGION_NETWORK` | 地区网络配置 | REGION | 4 | glm |
| 13 | `REGION_TARIFF` | 地区资费配置 | REGION | 4 | glm |
| 14 | `PROJECT_MEMBER` | 项目成员配置 | PROJECT | 5 | glm |
| 15 | `PROJECT_ENV` | 项目环境配置 | PROJECT | 5 | glm |

**定义数 = 15**（= 7 + 8），**层级分布 = `{GLOBAL:7, REGION:3, PROJECT:5}`**，无 code 重叠。8 个 glm 定义（第 8~15 项）的字段级原文（字段码/名称/`fieldType`/`required`/`key`/`optionsJson`/`refDefCode`/`refFieldCode`）见附件 A 对应条目；与第三节清单逐项一致。

基座 7 定义的字段签名（同一次响应，用于确认未被改动）：

```
CURRENCY (GLOBAL, 4 字段): code:STRING,key,req / name:STRING,req / exchangeRate:NUMBER,req / decimalPlaces:NUMBER
DOC_TYPE (GLOBAL, 4 字段): code:STRING,key,req / name:STRING,req / category:ENUM,req / enabled:BOOLEAN,req
APPROVE_ROLE (GLOBAL, 3 字段): code:STRING,key,req / name:STRING,req / description:STRING
TAX_RATE (REGION, 5 字段): taxCode:STRING,key,req / taxType:ENUM,req / rate:NUMBER,req / effectiveDate:DATE,req / regionCode:STRING,key,req
PROJ_PARAM (PROJECT, 4 字段): paramKey:STRING,key,req / paramValue:STRING,req / description:STRING / projectCode:STRING,key,req
PROJ_APPROVE (PROJECT, 6 字段): stepCode:STRING,key,req / stepName:STRING,req / approveRole:REFERENCE,req(ref=APPROVE_ROLE.code) / stepOrder:NUMBER,req / docTypeCode:STRING / projectCode:STRING,key,req
PROJ_PRICE (PROJECT, 7 字段): skuCode:STRING,key,req / skuName:STRING,req / unitPrice:NUMBER,req / currencyCode:REFERENCE,req(ref=CURRENCY.code) / taxCode:STRING,req / unit:STRING,req / projectCode:STRING,key,req
```

与 `docs/evidence/G1-main-v2基线构建验证.md` 记录的基座定义逐字段一致 ⇒ **既有 7 定义结构未被本次移植改动**。

### 4.4 每定义数据行数（`GET /api/data/{defCode}/count`）

| code | 行数 | 归属 |
|---|---|---|
| CURRENCY | 5 | 基座（G1 记录一致） |
| DOC_TYPE | 5 | 基座 |
| APPROVE_ROLE | 4 | 基座 |
| TAX_RATE | 16 | 基座（4 地区×4） |
| PROJ_PARAM | 0 | 基座（仅定义，无数据） |
| PROJ_APPROVE | 0 | 基座 |
| PROJ_PRICE | 1200 | 基座（4 项目×300） |
| SYS_PARAM | 40 | glm |
| METRIC_DICT | 30 | glm |
| ALARM_THRESHOLD | 120 | glm |
| ROLE_DICT | 8 | glm |
| REGION_NETWORK | 12 | glm |
| REGION_TARIFF | 20 | glm |
| PROJECT_MEMBER | 112 | glm |
| PROJECT_ENV | 28 | glm |
| **合计** | **1600** | 基座 1230 + glm 370 |

### 4.5 引用完整性（跨配置引用示范抽查）

```
ALARM_THRESHOLD.metricCode distinct=30 -> [metric.001 … metric.030]
METRIC_DICT.metricCode      distinct=30 -> [metric.001 … metric.030]
ref-check ALARM_THRESHOLD.metricCode ⊆ METRIC_DICT.metricCode = True
PROJECT_MEMBER.roleCode distinct = [ROLE_01 … ROLE_08]   （7 个项目逐范围汇总）
ROLE_DICT.roleCode      distinct = [ROLE_01 … ROLE_08]
ref-check PROJECT_MEMBER.roleCode ⊆ ROLE_DICT.roleCode = True
```

即 glm 的两条跨配置依赖在基座上不仅"声明"成立（`REFERENCE` 字段），**值级引用也全部命中**，可直通基座 `PrecheckJobRunner` 的引用完整性校验。

### 4.6 REGION_NETWORK 抽查（`GET /api/data/REGION_NETWORK?scopeType=REGION&scopeKey=<地区>&size=50`）

各地区 `totalElements` 与行原文（`scopeKey` 取自响应的 `content[].scopeKey`，`dataJson` 为响应字段原文）：

```
scopeKey=HB  totalElements=3
   rowKey=HB|100   {"regionCode":"HB","bandwidthMbps":100,"gateway":"10.0.0.1","redundancy":true}
   rowKey=HB|150   {"regionCode":"HB","bandwidthMbps":150,"gateway":"10.0.0.1","redundancy":false}
   rowKey=HB|200   {"regionCode":"HB","bandwidthMbps":200,"gateway":"10.0.0.1","redundancy":true}
scopeKey=HE  totalElements=3
   rowKey=HE|100   {"regionCode":"HE","bandwidthMbps":100,"gateway":"10.1.0.1","redundancy":true}
   rowKey=HE|150   {"regionCode":"HE","bandwidthMbps":150,"gateway":"10.1.0.1","redundancy":false}
   rowKey=HE|200   {"regionCode":"HE","bandwidthMbps":200,"gateway":"10.1.0.1","redundancy":true}
scopeKey=HS  totalElements=3
   rowKey=HS|100   {"regionCode":"HS","bandwidthMbps":100,"gateway":"10.2.0.1","redundancy":true}
   rowKey=HS|150   {"regionCode":"HS","bandwidthMbps":150,"gateway":"10.2.0.1","redundancy":false}
   rowKey=HS|200   {"regionCode":"HS","bandwidthMbps":200,"gateway":"10.2.0.1","redundancy":true}
scopeKey=XN  totalElements=3
   rowKey=XN|100   {"regionCode":"XN","bandwidthMbps":100,"gateway":"10.3.0.1","redundancy":true}
   rowKey=XN|150   {"regionCode":"XN","bandwidthMbps":150,"gateway":"10.3.0.1","redundancy":false}
   rowKey=XN|200   {"regionCode":"XN","bandwidthMbps":200,"gateway":"10.3.0.1","redundancy":true}
```

- **12 行 = 4 地区 × 3 行**，每地区行数均匀（与 glm "每地区 3 行" 同构，地区数由 5 变 4）。
- 每地区 `gateway` 恒定（该地区出口网关）、`bandwidthMbps`（业务键之一）与 `redundancy` 随行序变化（true/false/true），与 V2 证据中 glm 的 `REGION_NORTH` 行模式（`true,false,true` + 恒定网关）一致。
- `scope_key` 与行内 `regionCode` 值一致，满足基座导入链路"由行字段反推 scopeKey"的约定。

其余 glm 定义的行内容抽查（原文）：

```
REGION_TARIFF scope=HB rows=5
   {"regionCode":"HB","tariffName":"资费套餐-1-1","monthlyFee":50.0,"billingCycle":"月付"}
   {"regionCode":"HB","tariffName":"资费套餐-2-1","monthlyFee":62.5,"billingCycle":"季付"}
   {"regionCode":"HB","tariffName":"资费套餐-3-1","monthlyFee":75.0,"billingCycle":"年付"}
   {"regionCode":"HB","tariffName":"资费套餐-4-1","monthlyFee":87.5,"billingCycle":"月付"}
PROJECT_ENV scope=HB-P001 rows=4（响应按 rowKey 排序，此处为响应顺序原文）
   {"projectCode":"HB-P001","envName":"开发","cpuCores":100,"memoryGb":100,"enabled":true}
   {"projectCode":"HB-P001","envName":"测试","cpuCores":150,"memoryGb":150,"enabled":false}
   {"projectCode":"HB-P001","envName":"生产","cpuCores":250,"memoryGb":250,"enabled":false}
   {"projectCode":"HB-P001","envName":"预发","cpuCores":200,"memoryGb":200,"enabled":true}
PROJECT_MEMBER scope=HB-P001 rows=16
   {"projectCode":"HB-P001","memberName":"用户001","memberEmail":"user001@example.com","roleCode":"ROLE_01","joinDate":"2026-01-01"}
   {"projectCode":"HB-P001","memberName":"用户002","memberEmail":"user002@example.com","roleCode":"ROLE_02","joinDate":"2026-01-02"}
   {"projectCode":"HB-P001","memberName":"用户003","memberEmail":"user003@example.com","roleCode":"ROLE_03","joinDate":"2026-01-03"}
   {"projectCode":"HB-P001","memberName":"用户004","memberEmail":"user004@example.com","roleCode":"ROLE_04","joinDate":"2026-01-04"}
SYS_PARAM（GLOBAL）rows=40，样例：
   {"paramKey":"param.1","paramValue":"10","paramType":"STRING","description":"自动生成的参数说明 #1","editable":true}
   {"paramKey":"param.10","paramValue":"100","paramType":"STRING","description":"自动生成的参数说明 #10","editable":false}
```

逐范围行数分布（实测）：

```
REGION_NETWORK rows per region : {"HB":3,"HE":3,"HS":3,"XN":3}
REGION_TARIFF  rows per region : {"HB":5,"HE":5,"HS":5,"XN":5}
PROJECT_ENV    rows per project: {"HB-P001":4,"HE-P001":4,"HE-P002":4,"HS-P001":4,"HS-P002":4,"XN-P001":4,"XN-P002":4}
PROJECT_MEMBER rows per project: {"HB-P001":16,"HE-P001":16,"HE-P002":16,"HS-P001":16,"HS-P002":16,"XN-P001":16,"XN-P002":16}
```

### 4.7 幂等重启（不删库原地重启）

执行：停掉首跑进程（确认 18298 不再 LISTENING）→ **不删除 `backend/data/`**（`config_mgr_db.mv.db` 原样保留）→ 以同端口重启。

```
2026-10-07T01:34:48.428  INFO  o.f.core.internal.command.DbValidate   : Successfully validated 2 migrations (execution time: 00:00.018s)
2026-10-07T01:34:48.438  INFO  o.f.core.internal.command.DbMigrate    : Schema "PUBLIC" is up to date. No migration necessary.
2026-10-07T01:34:52.527  INFO  c.e.configmgr.ConfigMgrApplication     : Started ConfigMgrApplication in 7.022 seconds
2026-10-07T01:34:52.593  INFO  c.example.configmgr.seed.DataSeedRunner : Seed data already exists, skipping
2026-10-07T01:34:52.635  INFO  c.example.configmgr.seed.GlmSeedService : GLM 种子定义已存在，跳过（8/8）
```

- 重启日志中 **无 ERROR / Exception / 约束冲突**，Flyway `up to date`，两次种子均跳过。
- 重启前后逐定义计数比对（脚本比对，非目测）：**定义数 15 → 15，数据行合计 1600 → 1600，逐定义计数完全一致（diff 为空）**，即不重复播种、不覆盖、无冲突。

### 4.8 端口与进程收尾

```
$ taskkill /PID <java-pid> /F          # 两次启动进程均已关闭
$ netstat -ano | grep -E ":18298\s+.*LISTENING"
（空 —— 18298 已释放）
```

18080 / 18290-18297 / 18301-18307 / 18312 全程未被本次核验使用。

---

## 五、差异与跳过项

| # | 项目 | glm 侧 | 基座侧（本次移植处理） | 原因 / 影响 |
|---|---|---|---|---|
| ① | `ConfigDef.rowCount` | 有（期望行数元数据） | **无对应列** | 基座无此列，**跳过该元数据**（不改模型）；期望行数以本节 3.2 表记录 |
| ② | `ConfigField.sampleValue` | 有（UI 展示示例值） | **无对应列** | 同上，**跳过** |
| ③ | `FieldType.SCOPE` | 7 类型之一，用于 `__scope` | **基座无该类型** | **不改基座枚举**：降级为 `STRING` + `key=true`，字段编码改用基座范围载体 `regionCode`/`projectCode`；范围本身落在 `scope_type`/`scope_key` 列。`__scope` 编码因基座校验正则 `[A-Za-z][A-Za-z0-9_]*` 不可用（下划线开头被拒） |
| ④ | 业务键唯一性 | 数据表**无**业务键唯一约束，允许重复键行（glm `PROJECT_ENV` 生成 8 行/项目，`envName` 只有 4 个取值 → 每项目 4 个键重复） | 基座 `(def_code,scope_type,scope_key,row_key)` 唯一，`batchSave` 按键 upsert | **PROJECT_ENV 每范围行数由 8 降为 4**（= 4 个环境值的全组合），总行数 40→28。这是基座"一行一业务键"的既定语义，非缺陷；若强行保留 40 行需伪造非业务键主键，已放弃 |
| ⑤ | 范围字典（地区/项目） | 自带 `ScopeDict`：5 地区 `REGION_*`、5 项目 `PRJ-100x` | 沿用基座主数据：4 地区 `HB/HE/HS/XN`、7 项目 `HB-P001…XN-P002` | **未搬运 glm 范围字典**，故未向基座 `regions`/`projects` 写入第二套编码体系。硬约束：基座 `projects.region_code` **NOT NULL**，glm 项目无地区归属，1:1 搬运必须伪造归属；同时避免基座出现两套地区命名。副作用：scoped 定义行数按"每范围行数 × 基座范围数"变化（见 3.2） |
| ⑥ | 数据生成器 | 单一全局行号 `n`，公式中同时隐含"范围序号 `n%scopeCount`"与"范围内行序 `n/scopeCount`"，隐含 `scopeCount=5` | **按语义范围索引化改写**：`s`=范围序号、`k`=范围内行序、`g`=全局行序 | 若直接把 glm 公式里的 scope 列表换成基座 4 地区：`redundancy`（`n%2`）在同一地区内恒定、`gateway`（`n%5`）在同一地区内逐行漂移，源数据的"同地区网关恒定、逐行交替"语义丢失。改写后每范围行数均匀且字段随行序变化，与 V2 证据中 glm 的行模式同构。GLOBAL 定义无范围，`k=g=n`，**数据值与原公式逐值一致** |
| ⑦ | 枚举选项形状 | `["百分比","毫秒",...]` | `[{"value":"百分比","label":"百分比"},...]` | 基座模板/前端消费 `{value,label}`；本次令 value=label，避免"下拉显示 label、导入读回 value"的口径歧义 |
| ⑧ | 定义排序 | 无排序字段 | `sort_order` | 新增 8 定义排 100~107（基座 0~8 之后），不插入基座定义之间 |
| ⑨ | `ConfigData.status` | 有 `PUBLISHED` 状态列 | 无状态列（线上表即已发布态） | 种子直接写线上表，语义等价；`status` 不落库 |
| ⑩ | `PROJECT_ENV` 的 `cpuCores`/`memoryGb` | 原公式使两字段取值相同（如 100/100） | **保持原公式**（未人为区分） | 属源分支数据特征，移植不做"顺带修复"；如后续认为不合理，应作为独立数据改进项而非本次移植内容 |

**未在本基座复现/未做的动作**：未改动 Flyway 脚本（零 DDL）；未移植 glm 的 `ScopeDict` 数据；未搬运 glm 的 `TASK`/发布相关种子（本次范围仅配置定义与范围数据）。

---

## 六、观察项（非本次改动引入，记录备查）

1. **基座 7 定义数据行实测为 1230 行**（5+5+4+16+0+0+1200），而 `G1-main-v2基线构建验证.md` 正文记为 **1246 行**（该文档自身的逐定义明细相加亦为 1230）。本次未改动基座数据，两个口径的差异属 G1 文档的合计笔误，建议在 G1 文档中更正为 1230／或"基座 7 定义 1230 + glm 8 定义 370 = 1600"。
2. **范围数据需带 `scopeKey` 查询**：`GET /api/data/{defCode}` 不带 `scopeKey` 时只返回 `scope_key IS NULL` 的行，故 REGION/PROJECT 级定义（含基座 `TAX_RATE`、`PROJ_PRICE`）不带范围参数会返回 0 行；`/count` 接口用的是 `(:scopeKey IS NULL OR ...)`，故计数是全范围的。这是基座既有行为，本次未改动，但会影响 S4.3/前端核对时的取数方式（须逐范围取）。
3. `backend/data/`（H2 运行产物）仍受 `.gitignore` 覆盖，首跑重建后当前库内含 15 定义 + 1600 行，即"并集移植后"的状态；原库备份在 `/tmp/g2-seed/data-backup`（若要回到移植前状态可直接回拷该文件，但会丢失 glm 8 定义的数据）。

---

## 七、纪律与收尾

- **代码改动面**：仅新增 2 个种子类文件；`git status --porcelain` 中无任何具名文件的修改条目 ⇒ 基座领域模型、`DataSeedRunner`、既有 7 定义及其播种逻辑、Flyway 脚本、`application.yml` **均未改动**。
- **未执行任何 `git` 写操作**：无 `add`/`commit`/`checkout`/`stash`，未触发 pre-commit hook（受影响仓库带有 hook，已在核验前后用只读 `git status` 确认状态）。
- **临时产物**（仓库外，不污染待提交状态）：`/tmp/g2-seed/` — `data-backup/`（移植前库备份）、`boot1.log`（首版生成器试跑，已废弃）、`boot2.log`（最终首跑全量日志）、`boot3-restart.log`（幂等重启全量日志）、`defs2.json`、`snapshot_before_restart.json`。
- **未使用真实 AI Key**：`DEEPSEEK_API_KEY=***` 占位启动，全程未发起 AI 调用。

## 附件

| 文件 | 内容 |
|---|---|
| `docs/evidence/G2-附件-A-15定义API原文.json` | `GET /api/definitions` 的完整原始响应（HTTP 200，15 个定义含字段级 JSON），来自首跑后的真实请求 |
