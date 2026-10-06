# V2 核验证据：glm-5.3 分支"范围内替换"发布语义

## 一、核验目标

被核验声明（glm-5.3 分支自述）：

> "发布语义为范围内替换——多地区并存时发布其一，另一地区数据不受影响"

该分支相关自述与代码声明：

| 位置 | 原文 |
|---|---|
| `docs/02-技术方案设计.md:49` | 决策 D4：任务数据用「暂存区」方案（status=STAGED + task_id）……"发布=范围内替换" |
| `docs/05-测试验证结果.md:42` | "PROJECT_MEMBER：80 → **50**（仅替换 PRJ-1001/1002 范围……其他 3 个项目数据原样保留——范围内替换语义精确生效）" |
| `ImportRunner.java:232`（代码注释） | "// 范围内替换（事务内）：先删同范围 PUBLISHED，再把 STAGED 提升为 PUBLISHED" |
| `ConfigDataService.java:315`（代码注释） | "/** 发布（事务内）：按配置项+适用范围替换 PUBLISHED，STAGED 行提升为 PUBLISHED */" |

附带核验声明：分支自述"8 个种子配置覆盖全局/地区/项目三层级"（`SeedService.java:27` 注释
"种子数据初始化：8 个配置项覆盖三层 + 依赖示范"）。

核验方式：**启动真实后端 + 通过 HTTP API 构造真实发布实验**，用发布前后逐字段比对替代目测。
本项**不是**通过重跑单元测试来核验（测试的自我断言不构成独立证据）。

## 二、执行环境与命令

| 项 | 值 |
|---|---|
| 被核验分支目录 | `/e/temp/ai-example-code/ai-example-glm-5.3` |
| 分支 / HEAD | `glm-5.3` / `821addb docs: 实现说明/测试用例/验证结果/README（38 自动化测试 + 10 E2E 场景全部通过）` |
| 工作树 | 核验前后 `git status --porcelain` 均为空（未改动任何跟踪文件；未执行任何 git commit/push） |
| 端口 | `18290`（`application.yml` 配置；启动前 `netstat` 无占用） |
| 数据库 | `jdbc:h2:file:./data/quickstart_db;MODE=MySQL`，启动时 `data/` 目录**不存在**，由本次启动新建并执行种子初始化 |
| JDK / Maven | JDK 21.0.12 / Apache Maven 3.9.16 |

启动命令：

```bash
cd /e/temp/ai-example-code/ai-example-glm-5.3/backend
"D:/Program Files/JetBrains/IntelliJ IDEA/plugins/maven-plugin/lib/maven3/bin/mvn.cmd" spring-boot:run
```

启动成功证据（`V2-附件-boot.log` 原文）：

```
Tomcat started on port 18290 (http) with context path '/'
Started QuickstartApplication in 3.433 seconds (process running for 3.748)
c.e.quickstart.service.SeedService : 开始初始化种子数据 ...
c.e.quickstart.service.SeedService : 种子数据初始化完成
```

### 2.1 发布链路的 API 与"范围"表达方式（读码所得）

| 步骤 | 方法 | 路径 | 关键参数 |
|---|---|---|---|
| 创建任务 | POST | `/api/tasks` | `{"type":"IMPORT_CONFIG"}` |
| 选择配置项 | POST | `/api/tasks/{id}/selection` | `{"codes":["REGION_NETWORK"]}` |
| 提交数据 | POST | `/api/tasks/{id}/data` | `{"configCode":"REGION_NETWORK","fileName":"…","rows":[…]}` |
| 检查 | POST | `/api/tasks/{id}/check/start` | — |
| 导入（暂存） | POST | `/api/tasks/{id}/import/start` | — |
| **发布** | POST | `/api/tasks/{id}/publish/start` | — |
| 查已发布数据 | POST/GET | 导出任务链路 `/api/tasks/{eid}/export/results` | 条件 `{"field":"__scope","op":"EQ","value":"<地区码>"}` |

**"范围"如何表达**：范围不是任务级参数，而是**数据行自带**的字段。`REGION_NETWORK` 的字段
定义中 `__scope`（适用地区）为 SCOPE 类型且为业务键；上传行里的 `__scope` 值即该行的范围。
发布时代码按 `__scope` 分组（`ConfigDataService.publishStaged`），**逐范围**
`deleteByDefIdAndStatusAndScopeValue(defId,"PUBLISHED",scope)` 后再把该范围的 STAGED 行提升为 PUBLISHED。
即"范围"= `__scope` 的取值集合，本次实验中即地区码。

### 2.2 种子数据实况（HTTP 实测 `GET /api/catalog/configs`）

```
配置项总数 = 8
按层级统计 = {"GLOBAL":4,"PROJECT":2,"REGION":2}
  ALARM_THRESHOLD  level=GLOBAL
  METRIC_DICT      level=GLOBAL
  ROLE_DICT        level=GLOBAL
  SYS_PARAM        level=GLOBAL
  PROJECT_ENV      level=PROJECT
  PROJECT_MEMBER   level=PROJECT
  REGION_NETWORK   level=REGION
  REGION_TARIFF    level=REGION
```

地区种子数据（`GET /api/catalog/scopes?type=REGION` 原文）：

```json
{"code":0,"msg":"ok","data":[
 {"scopeType":"REGION","code":"REGION_EAST","name":"华东地区"},
 {"scopeType":"REGION","code":"REGION_NE","name":"东北地区"},
 {"scopeType":"REGION","code":"REGION_NORTH","name":"华北地区"},
 {"scopeType":"REGION","code":"REGION_SOUTH","name":"华南地区"},
 {"scopeType":"REGION","code":"REGION_SW","name":"西南地区"}]}
```

### 2.3 实验设计

- 被操作配置项：`REGION_NETWORK`（地区级，种子 15 行，5 个地区各 3 行）
- **R1 = `REGION_NORTH`（华北地区）**：本轮发布的目标范围
- **R2 = `REGION_SOUTH`（华南地区）**：观察对象，声明要求其数据不受影响
- 上传数据只含 R1 的 2 行、`__scope` 全部为 `REGION_NORTH`，`bandwidthMbps` 使用
  9999/8888（原值为 200/450/700）以便识别是否真的发生替换
- 比对方式：发布前后分别用导出链路的条件查询读取两个地区的数据，**用脚本逐字段
  （含 `__id`）比对**，不依赖目测

## 三、实际观察结果

### 3.1 发布前基线（导出链路条件查询原文）

R1（`__scope EQ REGION_NORTH`，`rowCount=3`）：

```json
"rows":[{"__scope":"REGION_NORTH","bandwidthMbps":"200","gateway":"10.2.0.1","redundancy":"true","__id":201},
        {"__scope":"REGION_NORTH","bandwidthMbps":"450","gateway":"10.2.0.1","redundancy":"false","__id":206},
        {"__scope":"REGION_NORTH","bandwidthMbps":"700","gateway":"10.2.0.1","redundancy":"true","__id":211}]
```

R2（`__scope EQ REGION_SOUTH`，`rowCount=3`）：

```json
"rows":[{"__scope":"REGION_SOUTH","bandwidthMbps":"250","gateway":"10.3.0.1","redundancy":"false","__id":202},
        {"__scope":"REGION_SOUTH","bandwidthMbps":"500","gateway":"10.3.0.1","redundancy":"true","__id":207},
        {"__scope":"REGION_SOUTH","bandwidthMbps":"750","gateway":"10.3.0.1","redundancy":"false","__id":212}]
```

全量（无条件下导出，`rowCount=15`）：`REGION_EAST` 3 / `REGION_NE` 3 / `REGION_NORTH` 3 /
`REGION_SOUTH` 3 / `REGION_SW` 3，共 15 行。

### 3.2 对 R1 范围执行发布（第一轮，请求与响应原文）

```
##### STEP 1 创建导入任务
REQ : POST http://localhost:18290/api/tasks
BODY: {"type":"IMPORT_CONFIG"}
RESP: {"code":0,"msg":"ok","data":{"id":"6cb1b9b98d824f3abaef2168729c69fd","type":"IMPORT_CONFIG","status":"WAITING","currentStep":"SELECT_CONFIG",...,"params":{"selection":null,"uploads":{},"checkResult":null,"importResult":null,"publishResult":null}}}
TASK_ID=6cb1b9b98d824f3abaef2168729c69fd

##### STEP 2 选择配置项
REQ : POST http://localhost:18290/api/tasks/6cb1b9b98d824f3abaef2168729c69fd/selection
BODY: {"codes":["REGION_NETWORK"]}
RESP: {"code":0,"msg":"ok","data":{...,"currentStep":"PREPARE",...,"selection":["REGION_NETWORK"],...}}

##### STEP 3 提交上传数据（仅 R1 范围）
REQ : POST http://localhost:18290/api/tasks/6cb1b9b98d824f3abaef2168729c69fd/data
BODY: {"configCode":"REGION_NETWORK","fileName":"R1-only.json","rows":[{"__scope":"REGION_NORTH","bandwidthMbps":"9999","gateway":"10.9.9.9","redundancy":"true"},{"__scope":"REGION_NORTH","bandwidthMbps":"8888","gateway":"10.9.9.9","redundancy":"false"}]}
RESP: {"code":0,"msg":"ok","data":{...,"params":{"uploads":{"REGION_NETWORK":{"fileName":"R1-only.json","rows":[…2 行同上传内容…]}},"checkResult":null,...}}}

##### STEP 4 执行检查
REQ : POST http://localhost:18290/api/tasks/6cb1b9b98d824f3abaef2168729c69fd/check/start
RESP: {"code":0,"msg":"ok","data":{"started":true}}
GET  http://localhost:18290/api/tasks/6cb1b9b98d824f3abaef2168729c69fd
STATUS=WAITING CURRENT_STEP=CHECK
checkResult 原文:
{ totalErrors: 0, totalWarnings: 0, hasError: false, messages: [],
  configs: [ { configCode: 'REGION_NETWORK', configName: '地区网络配置',
               hasData: true, rowCount: 2, errorCount: 0, warnCount: 0, issues: [] } ] }

##### STEP 5 执行导入（暂存）
REQ : POST http://localhost:18290/api/tasks/6cb1b9b98d824f3abaef2168729c69fd/import/start
RESP: {"code":0,"msg":"ok","data":{"started":true}}
GET  http://localhost:18290/api/tasks/6cb1b9b98d824f3abaef2168729c69fd
STATUS=WAITING CURRENT_STEP=IMPORT
importResult 原文:
{ configs: [ { configCode: 'REGION_NETWORK', configName: '地区网络配置',
               stagedRows: 2, publishedRows: 15 } ],
  message: '全部导入完成（暂存未发布）' }
   ← 导入阶段已发布行数仍为 15，暂存未污染已发布数据

##### STEP 6 执行发布
REQ : POST http://localhost:18290/api/tasks/6cb1b9b98d824f3abaef2168729c69fd/publish/start
RESP: {"code":0,"msg":"ok","data":{"started":true}}
GET  http://localhost:18290/api/tasks/6cb1b9b98d824f3abaef2168729c69fd
STATUS=SUCCESS CURRENT_STEP=PUBLISH
publishResult 原文:
{ message: '全部发布完成',
  configs: [ { configCode: 'REGION_NETWORK', status: 'DONE', message: '已发布 2 行', percent: 100 } ] }
```

### 3.3 发布后复查（同一导出链路条件查询）

R1（`REGION_NORTH`，`rowCount=2`）——**已被替换**：

```json
"rows":[{"__scope":"REGION_NORTH","bandwidthMbps":"9999","gateway":"10.9.9.9","redundancy":"true","__id":359},
        {"__scope":"REGION_NORTH","bandwidthMbps":"8888","gateway":"10.9.9.9","redundancy":"false","__id":360}]
```

R2（`REGION_SOUTH`，`rowCount=3`）——**与发布前完全一致**：

```json
"rows":[{"__scope":"REGION_SOUTH","bandwidthMbps":"250","gateway":"10.3.0.1","redundancy":"false","__id":202},
        {"__scope":"REGION_SOUTH","bandwidthMbps":"500","gateway":"10.3.0.1","redundancy":"true","__id":207},
        {"__scope":"REGION_SOUTH","bandwidthMbps":"750","gateway":"10.3.0.1","redundancy":"false","__id":212}]
```

全量（`rowCount=15 → 14`）：分布 `{REGION_EAST:3, REGION_NE:3, REGION_NORTH:2, REGION_SOUTH:3, REGION_SW:3}`。

### 3.4 逐字段比对脚本输出（原文，非目测）

```
=== A. R2(REGION_SOUTH) before/after ===
before rows = 3  after rows = 3
row0: 一致=true
   before: __id=202 | __scope=REGION_SOUTH | bandwidthMbps=250 | gateway=10.3.0.1 | redundancy=false
   after : __id=202 | __scope=REGION_SOUTH | bandwidthMbps=250 | gateway=10.3.0.1 | redundancy=false
row1: 一致=true
   before: __id=207 | __scope=REGION_SOUTH | bandwidthMbps=500 | gateway=10.3.0.1 | redundancy=true
   after : __id=207 | __scope=REGION_SOUTH | bandwidthMbps=500 | gateway=10.3.0.1 | redundancy=true
row2: 一致=true
   before: __id=212 | __scope=REGION_SOUTH | bandwidthMbps=750 | gateway=10.3.0.1 | redundancy=false
   after : __id=212 | __scope=REGION_SOUTH | bandwidthMbps=750 | gateway=10.3.0.1 | redundancy=false
A 结论: R2 逐字段（含 __id）完全一致 = true

=== B. 全部地区（ALL）排除 R1 后的行集合 ===
before 非R1行数 = 12  after 非R1行数 = 12
集合完全一致 = true
before 有而 after 无: []
after 有而 before 无: []

=== C. R1(REGION_NORTH) before/after ===
before rows = 3
   before: __id=201 | __scope=REGION_NORTH | bandwidthMbps=200 | gateway=10.2.0.1 | redundancy=true
   before: __id=206 | __scope=REGION_NORTH | bandwidthMbps=450 | gateway=10.2.0.1 | redundancy=false
   before: __id=211 | __scope=REGION_NORTH | bandwidthMbps=700 | gateway=10.2.0.1 | redundancy=true
after rows = 2
   after : __id=359 | __scope=REGION_NORTH | bandwidthMbps=9999 | gateway=10.9.9.9 | redundancy=true
   after : __id=360 | __scope=REGION_NORTH | bandwidthMbps=8888 | gateway=10.9.9.9 | redundancy=false
是否发生替换 = true

=== D. 行数分布 ===
before ALL: {"REGION_EAST":3,"REGION_NE":3,"REGION_NORTH":3,"REGION_SOUTH":3,"REGION_SW":3} total= 15
after  ALL: {"REGION_EAST":3,"REGION_NE":3,"REGION_SOUTH":3,"REGION_SW":3,"REGION_NORTH":2} total= 14
```

### 3.5 反向对称实验（第二轮：改为发布 R2，观察 R1）

为避免"单向偶然通过"，追加了对称方向实验：对 `REGION_SOUTH` 上传 2 行
（`bandwidthMbps` 7777/6666）并发布，然后观察 `REGION_NORTH` 是否被波及。

第二轮发布结果（原文）：

```
CHECK   STATUS=WAITING STEP=CHECK   checkResult: totalErrors: 0, hasError: false, rowCount: 2
IMPORT  STATUS=WAITING STEP=IMPORT  importResult: stagedRows: 2, publishedRows: 14
PUBLISH STATUS=SUCCESS STEP=PUBLISH publishResult: { message: '全部发布完成',
        configs: [ { configCode: 'REGION_NETWORK', status: 'DONE', message: '已发布 2 行', percent: 100 } ] }
```

第二轮后逐字段比对输出（原文）：

```
=== 第二轮发布后 R1(REGION_NORTH) 对比 ===
第二轮发布前 R1 行数 = 2  第二轮发布后 R1 行数 = 2
row0: 一致=true
   before: __id=359 | __scope=REGION_NORTH | bandwidthMbps=9999 | gateway=10.9.9.9 | redundancy=true
   after : __id=359 | __scope=REGION_NORTH | bandwidthMbps=9999 | gateway=10.9.9.9 | redundancy=true
row1: 一致=true
   before: __id=360 | __scope=REGION_NORTH | bandwidthMbps=8888 | gateway=10.9.9.9 | redundancy=false
   after : __id=360 | __scope=REGION_NORTH | bandwidthMbps=8888 | gateway=10.9.9.9 | redundancy=false
R1 逐字段（含 __id）完全一致 = true

=== 第二轮：排除 REGION_SOUTH 后的行集合对比 ===
before 非SOUTH行数 = 11  after 非SOUTH行数 = 11  集合完全一致 = true
before 有而 after 无: []
after 有而 before 无: []

=== 行数分布 ===
第二轮前 ALL: {"REGION_EAST":3,"REGION_NE":3,"REGION_SOUTH":3,"REGION_SW":3,"REGION_NORTH":2} total= 14
第二轮后 ALL: {"REGION_EAST":3,"REGION_NE":3,"REGION_SW":3,"REGION_NORTH":2,"REGION_SOUTH":2} total= 13
```

第二轮后 `REGION_SOUTH` 变为 7777/6666（`__id` 361/362），`REGION_EAST`/`REGION_NE`/`REGION_SW`
共 9 行 `__id`（199,200,203,204,205,208,209,210,213）保持原值不变。

### 3.6 旁证：catalog 的 rowCount 是已发布行数的实时统计

`CatalogService.java:41-43` 显示 `rowCount` = `status="PUBLISHED"` 的行数实时统计。
第二轮发布后再次拉取 `/api/catalog/configs`，`REGION_NETWORK` 的 `rowCount=13`，
与"15 − 3 + 2（第一轮 R1）− 3 + 2（第二轮 R2）= 13"逐一对上，与逐字段比对结果互相印证。

### 3.7 副作用与收尾

- 运行产生的 `backend/target/`、`backend/data/quickstart_db.mv.db` 按指示保留；
  被核验分支 `git status --porcelain` 输出为空，**未新增/修改/删除任何跟踪文件，未执行 git commit/push**。
- 后端进程已关闭：`TaskStop` 后 `netstat -ano | grep LISTENING | grep 18290` 无输出，
  进程 PID 27312 已不存在，`curl -m 5 http://localhost:18290/...` 返回 `curl_exit=7`
  （连接被拒绝），端口 18290 已释放。

## 四、与声明是否一致

| 声明 | 实测 | 判定 |
|---|---|---|
| 发布语义为"范围内替换" | 对 R1 上传 2 行 → 发布后 R1 由 3 行（200/450/700）替换为 2 行（9999/8888），`__id` 全部更换；`publishResult` 报"已发布 2 行" | **符合** |
| 多地区并存时发布其一，**另一地区数据不受影响** | R2（`REGION_SOUTH`）3 行在发布前后逐字段（`__scope`/`bandwidthMbps`/`gateway`/`redundancy`/`__id`）完全一致；全量中排除 R1 的 12 行集合完全一致，无增无减 | **符合** |
| 对称性（换成发布 R2、R1 不受影响） | 第二轮发布 R2 后，R1 逐字段完全一致；排除 R2 的 11 行集合完全一致 | **符合** |
| "范围内替换"的粒度确为 `__scope` 单地区 | 被替换的仅 `__scope=REGION_NORTH` 的行；`REGION_EAST`/`REGION_NE`/`REGION_SW` 的 `__id` 全程不变 | **符合** |
| 8 个种子配置覆盖全局/地区/项目三层级 | `GET /api/catalog/configs` 返回 8 个配置，层级分布 `{"GLOBAL":4,"PROJECT":2,"REGION":2}` | **符合** |

补充说明（不构成不符，仅为语义边界）：本次核验的是"不同范围之间的隔离"。若一次上传中
同时包含多个地区的行，代码会按 `__scope` 分组、对**每个出现的范围**分别执行删除+提升
（`ConfigDataService.publishStaged` 的 `byScope` 循环），即这些地区会被同时替换。
要只替换单一地区，上传数据即需只含该地区的行——本实验正是这样构造的，故声明在
"发布其一不影响另一"这一表述上成立。

## 五、结论

**【实测-符合】**

glm-5.3 分支"发布语义为范围内替换——多地区并存时发布其一，另一地区数据不受影响"经真实
后端（端口 18290，HTTP API 全链路：建任务→选配置→提交数据→检查→导入→发布）实测成立：

- 发布后目标范围 R1 的行被整体替换（3 行旧值删除、2 行新值上线）；
- 非目标范围 R2 的 3 行在发布前后**逐字段含主键完全一致**，全量中非 R1 的 12 行集合无增无减；
- 反向对称实验（发布 R2 观察 R1）同样成立，排除被发布范围的 11 行集合完全一致；
- 附带声明"8 个种子配置覆盖三层级"亦实测一致（GLOBAL 4 / REGION 2 / PROJECT 2）。

---

附件（本目录）：

| 附件 | 内容 |
|---|---|
| `V2-附件-boot.log` | 后端启动完整日志（含端口 18290、种子初始化完成） |
| `V2-附件-trace-publish.txt` | 第一轮发布（R1）全链路请求/响应原文 |
| `V2-附件-trace-publish2.txt` | 第二轮发布（R2，对称方向）全链路请求/响应原文 |
| `V2-附件-trace-R1-before.txt` / `-R1-after.txt` | R1 发布前/后导出链路原始响应 |
| `V2-附件-trace-R2-before.txt` / `-R2-after.txt` | R2 发布前/后导出链路原始响应 |
| `V2-附件-trace-ALL-before.txt` / `-ALL-after.txt` / `-ALL-final.txt` | 全量 15 行前基线、第一轮后、第二轮后原始响应 |
| `V2-附件-compare-output.txt` / `-compare2-output.txt` | 两轮逐字段比对脚本输出原文 |
| `V2-附件-catalog-configs.json` | `GET /api/catalog/configs` 原始响应 |
