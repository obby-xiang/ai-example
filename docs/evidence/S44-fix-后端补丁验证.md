# S4.4b 移交的 5 项后端缺口 —— 修复与验证报告

> 日期：2026-10-07　执行：S4.4-fix（后端补丁工程师）
> 来源：`<REPO_ROOT>/docs/evidence/S44b-五页视图验证.md` §7 缺陷 2/3、§8 遗留 1/2/3/7/8（前端联调实测移交）
> 仓库：`<REPO_ROOT>`（分支 main）　分支基准：`9113792`（S4.4b 收尾提交）
> 路径脱敏：`<REPO_ROOT>`=仓库根、`<MAVEN_HOME>`=本机 Maven 安装目录的 `bin`、`<TEMP_ROOT>`=本机证据目录（原始产物与截图，不入库）
> 纪律：全程<b>未执行任何 git 写操作</b>；未改 `ai/` 包、发布模块、种子、前端；`docs/` 仅新增本文件。

---

## 0. 环境与产物

| 项 | 值 |
|---|---|
| JDK / Maven | JDK 21（PATH）；`"<MAVEN_HOME>/mvn.cmd" -o`（离线，本地仓库已就绪） |
| 后端 | `<REPO_ROOT>/backend`，`mvn -o clean test`（V1）、`mvn -o package -DskipTests` → `target/config-mgr.jar` |
| 实跑 | `java -jar target/config-mgr.jar --server.port=18317`，`AI_API_KEY=dummy`，H2 文件库 `<REPO_ROOT>/backend/data/`（15 定义种子态）+ Flyway，Redis 未启 |
| 端口 | 起服务前 `netstat` 复核 18317 空闲；18080/18290-18298/18301-18316/18320/18321 全程未占用 |
| 原始产物 | `<TEMP_ROOT>/v2-report.txt`、`v3-list-conditions.txt`、`v4-progress-sequence-{FIXED,OLDCODE,FINAL-FIXED}.txt`、`v4-snapshot-timeline-*.txt`、`v5-sse-raw-FINAL-FIXED.txt`、`v5v6-oplog.txt`、`v6-delete-endpoint.txt`、`v3-inventory.txt`、`cleanup-report.txt` |
| DB 备份/还原 | 起服务前 `backend/data/` → `/tmp/s44f/data-backup`；收工后原样还原并复检（§6） |

---

## 1. 改动文件清单

### 1.1 新增

| 文件 | 职责 |
|---|---|
| `backend/src/main/java/com/example/configmgr/job/service/JobProgressTotals.java` | 作业行级进度分母的唯一计算入口（预估值 / 已发现 / 未开工预留三者取大），P3 |
| `backend/src/test/java/com/example/configmgr/definition/service/DefinitionUpdateTest.java` | P1 回归（HTTP 200 + 读回一致 + 原位合并保留行 id），3 例 |
| `backend/src/test/java/com/example/configmgr/definition/service/DefinitionDeleteTest.java` | P5 回归（404 / 三种 409 / 放行级联），6 例 |
| `backend/src/test/java/com/example/configmgr/data/service/DataListConditionTest.java` | P2 回归（枚举 EQ / 数字范围 / 文本 LIKE+CONTAINS / IN / 条件后分页 / 无条件回归 / 非法 JSON），7 例 |
| `backend/src/test/java/com/example/configmgr/job/service/JobProgressTotalsTest.java` | P3 单元用例（逐帧模拟"配置项收尾不得到 100%"），3 例 |

### 1.2 修改

| 文件 | 一句话职责（本次改动） |
|---|---|
| `definition/service/DefinitionService.java` | P1：`update` 改为按字段编码**原位合并**（未改动字段保留行 id、缺失编码删除、新编码才插入）；P5：新增 `delete(code)`（三种被引用检查 + 级联清理 + 无主体操作流水）；`save/update/delete` 各落一条结构化操作流水 |
| `definition/controller/DefinitionController.java` | P5：新增 `DELETE /api/definitions/{code}`（409 带机器可读码；回显级联计数） |
| `definition/repo/ConfigFieldRepository.java` | P5：`findByRefDefCode`（反向引用检查） |
| `data/service/ConfigDataService.java` | P2：抽出 `parseCondition / filterRows`，新增 `findPagedFiltered`、`countFiltered` —— 列表端点与 `/count`、导出作业共用同一份条件求值 |
| `data/controller/DataController.java` | P2：列表端点新增 `conditions` 入参（有条件走同口径过滤后分页，无条件保持 DB 分页原路径）；`/count` 改调同一份服务方法 |
| `data/service/ConditionEvaluator.java` | P2：`LIKE` 作为 `CONTAINS` 的等价别名入白名单（原先未知操作符会被 `default` **静默放行**，等于不过滤） |
| `data/service/QueryCondition.java` | 操作符注释补 `LIKE` |
| `data/repo/ConfigDataRowRepository.java` | P3/P5：`countByDefCode`（导出分母预估 / 删除前"有已发布数据"检查） |
| `data/repo/ConfigStagingRowRepository.java` | P5：`countByDefCode` + `deleteByDefCode`（删除定义时的暂存行级联清理） |
| `task/repo/TaskItemRepository.java` | P5：`countByDefCodeAndTaskStatus`（被未终态任务选中的检查） |
| `job/service/ExportJobRunner.java` | P3：开工前按各配置已发布行数预数 → 分母开工即终值；P4：进度帧补 `currentItem/currentItemName` |
| `job/service/PublishJobRunner.java` | P3：按暂存行数预估分母（同上）；P4：同上 |
| `job/service/ImportJobRunner.java` | P3：无便宜预估 → 分母取"已发现 + 未开工配置项数"；P4：同上 |
| `job/service/PrecheckJobRunner.java` | P3/P4：同导入（并顺手把 `items`/拓扑序求值提到 `markRunning` 之前，使开工分母可用） |
| `job/service/JobProgressService.java` | 分母口径的类注释改到 `JobProgressTotals`（行为不变） |

---

## 2. 五项修复说明

### P1（必修，FR-1.4 阻断）`PUT /api/definitions/{code}` 对已有定义恒 500

- **现象**：前端"配置定义 → 编辑字段 → 保存"对已有定义恒 500（新建 POST 正常）。
- **根因**（复现报文见 §3 V2 的红检）：旧 `update()` 先 `existing.getFields().clear()` 再挂入 `id=null` 的新实体；Hibernate 的动作顺序是**先 INSERT 后 DELETE**，于是旧行还在，同 `(def_code, code)` 的新行先插 → `UQ_FIELD_DEF_CODE` / `UQ_FIELD_DEF_CODE_INDEX_2` 唯一键冲突：
  `insert into config_fields (...) values (...) [23505-232]`。
- **改法**（`DefinitionService`，`update` @149 / `mergeFields` @172）：按**字段编码合并**——
  ① 入参编码命中旧集 → 在旧实体上原地改属性（**保留行 id，不产生 INSERT**）；
  ② 入参没有的旧编码 → 从集合移除，由 `orphanRemoval` 删除；
  ③ 全新编码 → 才新建实体。
  删除项与新增项编码互不相交，故与"先插后删"无冲突；`sortOrder` 一律按入参顺序重排。
- **边界**：入参 `id` 不再被采信（以库中行为准），避免前端传错 id 造成错行更新；校验失败（重复编码/无主键/REFERENCE 缺目标）仍在写库前抛 400 且整事务回滚，不留半成品。
- 前端无需改动（前端就是按"整份 fields 替换"提交的，语义未变）。

### P2 数据浏览列表端点补 `conditions` 入参

- **现象**：`GET /api/data/{defCode}` 无 `conditions`，只有 `/count` 有 → 前端只能在当前页做镜像求值。
- **改法**：列表端点新增 `conditions`（`QueryCondition` 的 JSON，形态与 `/count` 完全一致）。
  过滤逻辑从 `/count` 的控制器内联代码**下沉到 `ConfigDataService`**（`filterRows`），列表与 `/count` 现在调用同一份实现，口径不可能再分叉；导出作业本就用同一个 `ConditionEvaluator`，三处同源。
  - 无条件：保持原路径（DB 分页 + `scopeType`/`scopeKey` 收窄），零行为变化。
  - 有条件：按该配置全部已发布行逐行求值（AND），**过滤后再分页**（`totalElements` = 命中总数）。
  - 条件 JSON 非法 → 400（与 `/count` 一致）。
  - 附带：`ConditionEvaluator` 把 `LIKE` 纳入白名单（等价 `CONTAINS`）——原先未知操作符会被 `default: return true` **静默当成"不过滤"**，是隐患。
- **边界（如实记录）**：有条件时沿用 `/count` 既有语义——只按 `scopeKey` 收窄、不再按 `scopeType` 收窄（见 §4 待裁决 ①）。

### P3 作业进度分母下限（消灭"首配置收尾瞬间 100%"）

- **现象**：分母只累计"已开工配置项的行数"，分子是"已扫描行数"，两者在每个配置项收尾瞬间相等 → 进度条闪现 100% 再回落（S44b §7.3）。
- **改法**：分母统一走 `JobProgressTotals.denominator(estimatedRows, discoveredRows, unstartedItems)`
  `= max(预估总行数, 已发现行数 + 未开工配置项数)`：
  - **导出**：开工前按各配置项 `countByDefCode`（已发布行数）预数 → 分母从第一帧起就是终值（1200 行级任务实测 total 恒 1325，全程单调）；
  - **发布**：同理按 `countByTaskIdAndDefCode`（暂存行数）预估；
  - **导入/预检**：行数来自上传的 Excel，开工前无法便宜地预读 → 以"每个未开工配置项各预留 1 行"给分母下界（开工首帧 = 配置项条数），配置项开工时其真实行数并入"已发现"并上调。
  - 两条不变量：`total ≥ processed`（百分比不溢出）与 `total ≥ 已发现行数`（分母不小于真实工作量）；终态分母回落为"真实工作量"（未开工预留清零），全部跑完时恰好 100%。
- **边界**：真实行数为 0 的配置项收尾瞬间分母会回落 1（见 §4 待裁决 ②）；末配置项最后一批扫描完成的那一帧已是 100%（此时只剩写文件与收尾，之后不再回落）。

### P4 SSE 进度帧补 `currentItem`

- **改法**：四个执行器的 `publishProgress` 增加**纯增量**字段
  `currentItem`（当前处理项**编码**，与既有 `defCode` 同值、语义显式）与 `currentItemName`（配置项**名称**，供"正在处理：<名称>"直接展示）。
  帧类型、既有字段（`jobId/jobType/defCode/processed/total/pct`）、发布时机（分片边界）**均未变**；前端 `JobProgressPayload` 未声明的多余键被忽略，无需前端改动。
- 证据见 §3 V5（SSE 原文 + 13 帧全帧齐备）。

### P5 定义删除端点核实与补齐

- **核实结论**：`DELETE /api/definitions/{code}` **原先不存在**（全仓 `@DeleteMapping` 仅 `TaskController#delete`（任务）与 `JobController#cancelJob`（作业取消），见 `S44b` §8 遗留 8 的现状描述得到确认）。故本次**新补**。
- **被引用检查（命中即 409 + 机器可读码，不级联删除）**：
  | 码 | 触发 | 依据 |
  |---|---|---|
  | `DEFINITION_HAS_DATA` | `config_data_rows` 中该定义行数 > 0 | 已发布数据是业务事实，删除定义**不连带删除业务数据**（ADR-7 发布语义：数据由导入/发布通路清理） |
  | `DEFINITION_REFERENCED` | 仍被他定义的 REFERENCE 字段指向 | FR-1.2 引用完整性；否则引用方字段级校验/动态渲染指向不存在的配置 |
  | `DEFINITION_IN_ACTIVE_TASK` | 被 ACTIVE 任务选中 | 该任务的检查/导入/发布作业随后仍按 `defCode` 取定义，删掉即任务中途断裂 |
- **级联策略（放行时）**：`config_fields` 随定义删除（`cascade=ALL + orphanRemoval`）；`config_staging_rows` 清理该定义的未发布暂存行（定义已不存在，这些行没有可发布的落点）；**保留**历史任务的 `task_items`（任务台账是历史事实，删掉等于篡改已结束任务的内容，只让该条目失去可打开的定义详情）。
- **操作流水**：`save/update/delete` 各落一条**无主体操作流水**（动作/对象/前值摘要/来源/时间，无操作人与 IP），落地形态与既有 `TaskService.delete`（FR-4.4）一致：结构化日志行 + `TASK_CHANGED`。**落库的 AuditLog 表属 DC-10 的 P2，本次仍未建**（见 §4 待裁决 ③）。
- 响应形态：`200 {"success":true,"data":{"code","deleted":true,"cascadedFields","cascadedStagingRows"}}`；不存在 → 404；命中检查 → 409。

---

## 3. 验证（V1-V6）

### V1 `mvn -o clean test` 全绿

```
[INFO] Tests run: 7, Failures: 0, Errors: 0, Skipped: 0 — DataListConditionTest      （P2，新增）
[INFO] Tests run: 6, Failures: 0, Errors: 0, Skipped: 0 — DefinitionDeleteTest       （P5，新增）
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0 — DefinitionUpdateTest       （P1，新增）
[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0 — JobGovernanceTest          （既有，不回退）
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0 — JobProgressTotalsTest      （P3，新增）
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0 — PublishSemanticsTest       （既有，不回退）
[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0 — TaskCenterQueryTest        （既有，不回退）
[INFO] Tests run: 30, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```
- 既有 11 例（5+4+2）全过，无回退；新增 19 例全过。命令：`cd <REPO_ROOT>/backend && AI_API_KEY=dummy "<MAVEN_HOME>/mvn.cmd" -o clean test`。
- **新用例对旧实现是红的**（避免"测试和实现一起错"）：
  - P1 红检：临时把 `update()` 回退回"clear + id=null 新实体"，`DefinitionUpdateTest` 2/3 失败，报文正是移交单里的 500 ——
    `DataIntegrityViolationException: ... Unique index or primary key violation: "PUBLIC.UQ_FIELD_DEF_CODE_INDEX_2 ON PUBLIC.CONFIG_FIELDS(DEF_CODE NULLS FIRST, CODE NULLS FIRST) VALUES ( /* key:75 */ 'S44F_EDIT', 'exchangeRate')"`。
  - P2 红检：临时把列表端点的 `conditions` 置空（模拟旧行为），`DataListConditionTest` 6/7 失败（`totalElements expected:<2> but was:<6>` 等），仅"无条件回归"例按预期仍通过。
  - 两次红检后源码已按备份逐字节还原并复检（`diff` 无差异、全仓无 `RED-CHECK-OLD` 残留）。

### V2 P1：前端同形态请求（整份 fields 替换 + 新增字段）→ 200 → 读回一致

实跑（端口 18317，种子定义 `CURRENCY`）。请求体 = 前端 `updateDefinition(code, definition)` 的形态（4 个既有字段整份带 id 回传 + 新增 1 个字段 + 改动其中 1 个 label）：

```
PUT /api/definitions/CURRENCY → HTTP 200
读回 GET /api/definitions/CURRENCY:
  name= 货币字典
    id=1   code=code            label=货币代码                    sortOrder=0 required=True  key=True
    id=2   code=name            label=货币名称                    sortOrder=1 required=True  key=False
    id=3   code=exchangeRate    label=对人民币汇率（S44F 编辑）   sortOrder=2 required=True  key=False
    id=4   code=decimalPlaces   label=小数位数                    sortOrder=3 required=False key=False
    id=97  code=s44fTmp         label=S44F 新增字段               sortOrder=4 required=False key=False
```
- 改动生效、新增字段落库；**`id=1..4` 原样保留** → 走的是"原位合并"而非"删旧建新"（这正是绕开唯一键冲突的机制证据）。
- 回退（再 PUT 原始 4 字段，删除自产字段）：HTTP 200，字段数与属性逐项（含行 id、required、key、optionsJson、ref*、sortOrder）与测试前完全一致：
  `字段集逐项一致（含行 id / 属性）= True`、`name/level/description 一致 = True`（`<TEMP_ROOT>/v2-report.txt`）。
- 操作流水留痕：`操作流水: 动作=UPDATE_DEFINITION 对象=def#CURRENCY 摘要=name=货币字典,level=GLOBAL,fields=5([code, name, exchangeRate, decimalPlaces, s44fTmp]) 来源=界面/API 时间=...`（同文件 `v5v6-oplog.txt`）。

### V3 P2：列表端点带 `conditions` 实测（枚举 EQ / 数字范围 / 文本 LIKE 各一 + 同构校验）

每条都同时请求列表端点与 `/count`，要求**同数**（`<TEMP_ROOT>/v3-list-conditions.txt`，9 项全 `[OK]`）：

| 用例 | 请求 | 列表 totalElements | /count | 判定 |
|---|---|---|---|---|
| 无条件基线 | `GET /api/data/SYS_PARAM` | 40 | — | 与旧行为一致（DB 分页） |
| 枚举 EQ | `paramType EQ "NUMBER"` | 13（`param.11/14/17/2/20/...`） | 13 | OK |
| 数字范围 | `warnThreshold GTE 100 AND LTE 200` | 27（`阈值规则-005..013, 045..053, 085..093`） | 27 | OK |
| 文本 LIKE | `thresholdName LIKE "规则-01"` | 10（`阈值规则-010..019`） | 10 | OK |
| 文本 CONTAINS | `paramKey CONTAINS "param.1"` | 11（`param.1, 10..19`） | 11 | OK |
| 枚举 IN | `paramType IN [STRING, BOOLEAN]` | 27 | 27 | OK |
| 条件后分页 | `NOT_EMPTY`，size=15 翻页 | totalElements=40，第 0 页 15 行、第 1 页 15 行，**两页无重叠** | — | OK |
| 范围键 + 条件 | `REGION_TARIFF?scopeKey=XN` 且 `monthlyFee GTE 75` | 3（`XN\|资费套餐-3-4/4-4/5-4`） | — | OK |
| 非法 JSON | `conditions={不是 JSON` | — | — | HTTP 400 |

### V4 P3：跑一次导出作业，观察进度曲线无 100% 闪现

任务：EXPORT，配置项 `CURRENCY(5 行) / ALARM_THRESHOLD(120 行) / PROJ_PRICE(1200 行)`（合计 1325 行）；同时抓 SSE 与 REST 快照（`<TEMP_ROOT>/v4-*`）。

**修复后（`v4-snapshot-timeline-FINAL-FIXED.txt`，0.2s 轮询作业快照）**
```
11:29:25.797 PENDING   progress=0    total=0     pct=0
11:29:26.002 RUNNING   progress=5    total=1325  pct=0     ← 首配置项 CURRENCY 收尾：0%（旧实现此处 100%）
11:29:27.027 RUNNING   progress=125  total=1325  pct=9     ← ALARM_THRESHOLD 收尾：9%（旧实现此处 100%）
11:29:27.436 RUNNING   progress=225  total=1325  pct=16
...（单调不减：24 → 32 → 39 → 47 → 54 → 62 → 69 → 77 → 84 → 92）
11:29:30.711 RUNNING   progress=1325 total=1325  pct=100   ← 只在"最后一行扫完"这一帧到 100%
11:29:30.918 COMPLETED progress=1325 total=1325  pct=100
```
**对照（把分母临时回退为旧口径后同任务同参数重跑，`v4-snapshot-timeline-OLDCODE.txt`）**
```
11:28:13.937 RUNNING progress=5   total=5    pct=100   ← 闪现
11:28:14.552 RUNNING progress=5   total=125  pct=4     ← 回落
11:28:14.757 RUNNING progress=125 total=125  pct=100   ← 再闪现
11:28:14.962 RUNNING progress=125 total=1325 pct=9     ← 再回落
```
- 分母取值集合：旧口径 `{[5,125,1325]}` 跳变；修复后 `{[1325]}` 恒定（预数生效）。
- 事件序列（SSE，13 帧 JOB_PROGRESS）：`105/1325=7% → 225=16% → 325=24% → … → 1225=92% → 1325=100%`，`processed` 单调不减；帧类型计数 `HEARTBEAT 1 / JOB_PROGRESS 13 / TASK_CHANGED 2 / JOB_DONE 1`（帧类型集合未变）。
- 结论：**收尾瞬间的 100% 闪现消失**；唯一出现 100% 的时刻是最后一行已被扫描（此时只剩写 Excel 与收尾，随后即 COMPLETED，无回落）。

### V5 P4：进度帧含 `currentItem`（SSE 原文）

`<TEMP_ROOT>/v5-sse-raw-FINAL-FIXED.txt` 原文（逐行带本地时间戳）：
```
11:29:24.298 data:{"data":{},"type":"HEARTBEAT"}
11:29:26.846 data:{"data":{"currentItem":"ALARM_THRESHOLD","processed":105,"pct":7,"defCode":"ALARM_THRESHOLD","total":1325,"jobType":"EXPORT","jobId":65,"currentItemName":"告警阈值配置"},"type":"JOB_PROGRESS"}
11:29:27.274 data:{"data":{"currentItem":"PROJ_PRICE","processed":225,"pct":16,"defCode":"PROJ_PRICE","total":1325,"jobType":"EXPORT","jobId":65,"currentItemName":"项目价格表"},"type":"JOB_PROGRESS"}
```
- 13 条 `JOB_PROGRESS` 帧**全部**携带 `currentItem`（编码）与 `currentItemName`（名称）：`currentItem 全帧齐备 = True`。
- 既有字段全部保留（`jobId/jobType/defCode/processed/total/pct`）、帧类型与发布时机未变 → 对前端是纯增量（前端未声明的键被忽略，无需改动）。

### V6 P5：删除端点行为实测

`<TEMP_ROOT>/v6-delete-endpoint.txt` 全流程原文（端口 18317）：

| # | 动作 | 结果 |
|---|---|---|
| 1 | `DELETE /api/definitions/S44F_NOT_EXIST` | 404 `配置定义 不存在: S44F_NOT_EXIST` |
| 2 | `DELETE /api/definitions/CURRENCY`（5 行已发布数据） | 409 `{"code":"DEFINITION_HAS_DATA","message":"配置定义 CURRENCY 存在 5 行已发布数据，禁止删除（不级联删除业务数据）"}` |
| 3 | `DELETE S44F_DEL_TARGET`（被 `S44F_DEL_REFS.refCode` 以 REFERENCE 指向） | 409 `{"code":"DEFINITION_REFERENCED","message":"...被他定义引用（S44F_DEL_REFS.refCode），请先解除引用再删除"}` |
| 4 | `DELETE S44F_DEL_TASKED`（被进行中任务 task#3 选中） | 409 `{"code":"DEFINITION_IN_ACTIVE_TASK","message":"...被 1 个进行中任务选中，请先结束或删除这些任务"}` |
| 5 | 解除引用后 `DELETE S44F_DEL_REFS` | 200 `{"code":"S44F_DEL_REFS","deleted":true,"cascadedFields":2,"cascadedStagingRows":0}` |
| 6 | `DELETE S44F_DEL_TARGET`（此时无数据/无引用/无在途任务） | 200 `{"cascadedFields":1,...}` |
| 7 | 读回 `GET /api/definitions/S44F_DEL_TARGET` | 404（确已删除） |
| 8 | 任务跑到终态（作业 3 `COMPLETED 0/0` → 任务 `COMPLETED`）后 `DELETE S44F_DEL_TASKED` | 200（历史 `task_items` 保留，见 §2 P5 级联策略） |

操作流水留痕（`<TEMP_ROOT>/v5v6-oplog.txt`）：
```
操作流水: 动作=DELETE_DEFINITION 对象=def#S44F_DEL_TARGET 前值摘要=name=S44F_DEL_TARGET 名称,level=GLOBAL,fields=1,stagingRows=0,publishedRows=0,referencedBy=0 来源=界面/API 时间=2026-10-07T11:27:04.176584
操作流水: 动作=CREATE_DEFINITION 对象=def#S44F_DEL_REFS 摘要=name=S44F_DEL_REFS 名称,level=GLOBAL,fields=2 来源=界面/API 时间=...
```
> 说明：`DELETE /api/definitions/{code}` 是**新增**端点，前端（S4.4b 棒的 `DefinitionsView`）当前没有调用它，故本次无前端联调面；前端若要加"删除定义"入口，契约已就绪（409 带码可分支、200 回显级联计数）。

---

## 4. 遗留【待裁决】

| # | 项 | 现状与影响 | 建议 |
|---|---|---|---|
| ① | 有条件查询的 `scopeType` 口径 | 列表与 `/count` 在**带 conditions 时只按 `scopeKey` 收窄、忽略 `scopeType`**（沿用 `/count` 既有实现，本次为"同构"而未改）。对 REGION/PROJECT 级配置，若只传 `scopeType` 不传 `scopeKey`，无条件走 DB 分页（按 scopeType+scopeKey），有条件则跨范围逐行过滤 → 两种路径结果集可能不同 | 裁决是否统一为"两种路径都按 scopeType+scopeKey 收窄"。改 `/count` 会影响既有前端"预估命中行数"口径，故本次不动 |
| ② | 0 行配置项的分母回落 | 分母含"每个未开工配置项预留 1 行"，某配置项真实行数为 0 时，它收尾瞬间分母回落 1，可能出现一次 100%→下一项开工后的回落（仅导入/预检在 0 行文件时触发；导出/发布有精确预估，不触发） | 若要彻底消除，需在开工前预读全部 Excel 行数（成本 = 多读一遍文件）；或前端在 RUNNING 且 `pct=100` 时显示"收尾中" |
| ③ | 落库的操作流水表（AuditLog） | 本次 P5 与 FR-1.4 的"变更记录流水"落地为**结构化日志行 + TASK_CHANGED**（与既有 FR-4.4 同口径）；DC-10 把 AuditLog 表降为 P2，仓库至今无该表与检索页 | 裁决是否在 M2 建 `audit_logs` 表 + 检索页；届时定义写路径只需把 `log.info` 换成落库调用 |
| ④ | 删除定义的"暂存行"级联 | 放行删除时会清理该定义在**历史任务**里的未发布暂存行（`config_staging_rows`）。这类残留行本就无发布落点，但确实改变了历史任务的"暂存视图" | 裁决是否保留为"拒绝删除（要求先清任务）"；当前选择"清理"，因为留存会成为永久孤儿 |
| ⑤ | `LIKE` 操作符 | 后端 `ConditionEvaluator` 现接受 `LIKE`（≡`CONTAINS`）；前端 `types/condition.ts` 的操作符白名单**未含** `LIKE`（其镜像求值器与后端 case 全量对齐）。前端仍可继续用 `CONTAINS`，但两侧白名单不再字面一致 | 裁决是否把 `LIKE` 同步进前端白名单（前端棒），或从后端白名单撤掉 |
| ⑥ | 数据浏览列表端点的有条件查询是"全量加载后内存过滤" | 与 `/count`、导出作业同源实现，对本 demo 数据量（单配置 ≤ 1200 行）无问题；数据量增长后应下推为 SQL/JSON 条件（H2 限制下未做） | 留给压测/规模裁决（技术方案 §13.2 同类项） |
| ⑦ | 前端可撤掉条件镜像求值器 | P2 已让列表端点支持 `conditions`，但前端 `types/condition.ts` 的镜像求值器（及数据浏览页的 `el-alert` 说明）仍在，属**前端棒工作**（本次禁止改前端） | 移交前端棒：列表查询改为把 conditions 直接下发给后端 |

---

## 5. 未做的事（如实声明）

- 未触碰 `ai/` 包、发布模块（`PublishJobRunner` 只改进度分母与进度帧，发布数据面语义未动）、种子（`seed/DataSeedRunner`、`seed/GlmSeedRunner` / `GlmSeedService`）、前端与 `docs/` 其他文件。
- 未执行任何 git 写操作（`git status` 仅显示本次改动；HEAD 仍是 `9113792`）。
- P3 只对**导出**做了端到端曲线实测（V4）；导入/预检/发布改动为同构改法 + 单元用例（`JobProgressTotalsTest`）+ 既有用例不回退，未另跑导入/预检全链路。

## 6. 清理与复核

- **自产任务**：4 条 `S44F-*` 任务（含分页/进度实测任务）经 `DELETE /api/tasks/{id}` 全部删除 → 复核 `GET /api/tasks` → `totalElements: 0`（`<TEMP_ROOT>/cleanup-report.txt`）。
- **自产定义**：`S44F_EDIT`（测试库内）、`S44F_COND`、`S44F_DEL_TARGET/REFS/TASKED`、`S44F_DEL_*` 全部清除；种子 `CURRENCY` 的字段集已按原样 PUT 回退（行 id 1-4 一致）。
- **DB 还原**：停止服务 → `backend/data/` 用测试前备份整体还原 → 重起一次复检：`定义数=15`、`任务数=0`、`S44F 残留定义=[]`、`CURRENCY 字段 id=[1,2,3,4]`、15 个定义的已发布行数与测试前清单逐项一致（5/5/4/16/0/0/1200/40/30/120/8/12/20/112/28）。
- **文件存储**：`backend/data/files/` 导出产物随任务删除清空（还原后 0 个文件）。
- **进程/端口**：18317 上的后端进程已停止，`netstat` 复核 18317 **无 LISTENING**（仅 TIME_WAIT 残留）；未使用 18080/18290-18298/18301-18316/18320/18321。
- **工作区**：`git status --short` 只剩本次 15 个修改 + 5 个新增源码/测试文件（无临时文件、无 `RED-CHECK-OLD` 残留、无 `target/` 入库）。
