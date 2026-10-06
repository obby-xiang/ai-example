# baseline-combined

> S4 合流行为基线（红线 3：搬运前在源分支录基线，搬运后在 main-v2 重放对照）。
> 本文只记录本次真实运行结果，未做任何"应该是这样"的补全；凡未实测到的，均在文中显式标注为【未触发】或【录制失败】。

## 0. 录制元信息

| 项 | 值 |
|---|---|
| 来源分支 | `claude-opus-5.5+deepseek-v4-pro`（= combined 分支） |
| 来源 commit | `a98d0b2b83e9bf71671fdd44a3b8426118f7b57a`（`git log -1`；`git status --porcelain` 为空 = 未改动该分支任何源码/配置） |
| 工作副本 | `<REPO_ROOT>`（= `<MAIN_V2>` 的上游基座分支工作树） |
| 被测产物 | `<REPO_ROOT>/backend/target/config-mgr.jar`（Spring Boot 3.5.14 可执行 jar；`find src -newer jar -name '*.java'` 为空，jar 与 HEAD 源码一致；`mvn -DskipTests package` 退出码 0） |
| JDK | OpenJDK 21.0.12 LTS |
| Maven | `<MAVEN_HOME>/mvn.cmd` |
| 启动方式 | `cd <REPO_ROOT>/backend && DEEPSEEK_API_KEY=*** java -jar target/config-mgr.jar --server.port=18296`（端口仅用命令行覆盖，**未改任何配置文件**） |
| 端口 | 18296（启动前 `netstat` 确认无监听；18080/18290–18295/18301–18307/18312 未占用） |
| 数据库 | H2 文件库 `<REPO_ROOT>/backend/data/config_mgr_db`（`jdbc:h2:file:./data/config_mgr_db;DB_CLOSE_DELAY=-1`） |
| 数据库处置 | 录制前原库已存在（`config_mgr_db.mv.db` 5,062,656 B），**已完整备份**到 `<TMP>/s4-baseline-combined/db-backup-original/`（含 `data/files/`）后删除，让 Flyway+`DataSeedRunner` 重新播种，保证基线可复现 |
| AI 模型 | `spring.ai.openai.chat.options.model: deepseek-flash`，`base-url: https://api.deepseek.com`，`completions-path: /chat/completions`；Key 仅经环境变量 `DEEPSEEK_API_KEY` 注入，本文一律记 `***` |
| 真实模型调用 | 是（B4 四轮均命中真实 DeepSeek 端点，返回真实 token 用量，见 §4） |
| 录制时间 | 2026-10-07 01:16 – 01:24（+0800） |
| 后端日志 | 全程 878 行，`ERROR` 共 6 条：2 条为录制工具自身产生（1 次 curl 命令行中文编码错误、1 次无 body 的 `POST /api/definitions` 探测），4 条为 `AsyncRequestNotUsableException: disconnected client`（我主动 kill 掉 SSE 订阅客户端所致，**与业务路径无关**）。业务链路本身未产生任何 ERROR。 |
| 收尾 | 后端进程已终止；`netstat -ano | grep 18296` 无 LISTENING；`tasklist java.exe` 无残留 |

### 附件（同目录）

| 文件 | 内容 |
|---|---|
| `baseline-combined-附件-B1-导出全链路原文.txt` | B1 逐条请求/响应/作业轮询原文 + 两次导出的 SSE 原始事件流 + B1 相关 SQL |
| `baseline-combined-附件-B2-导入全链路原文.txt` | B2 逐条原文（含上传、ZIP 批量、8 类规则探针）+ PRECHECK/IMPORT/PUBLISH 三段 SSE + B2 相关 SQL + 测试用 xlsx 构造脚本 |
| `baseline-combined-附件-B3-配置定义与目录原文.txt` | 7 个配置定义详情原文 + 列表原文 + 模板下载响应头 + B3 相关 SQL |
| `baseline-combined-附件-B4-AI对话-原始SSE事件流.txt` | `/api/ai/tools` 渐进式披露 3 组 + 4 轮 run 的完整原始 SSE + 会话状态快照 + B4 相关 SQL |
| `baseline-combined-附件-数据库状态快照.txt` | 全部 SQL 取值快照（44 次查询，按录制顺序） |
| `baseline-combined-附件-后端运行日志.log` | 后端控制台全量日志（118 KB，已脱敏） |

脱敏口径：本机绝对路径 → `<REPO_ROOT>` / `<MAIN_V2>` / `<MAVEN_HOME>` / `<PYTHON_HOME>` / `<TMP>` 占位；API Key → `***`。已验证产出物中不存在真实密钥与裸绝对路径。

---

## 1. 主表（手册附录 B 格式）

| # | 场景 | 输入 | 关键输出/DB 状态 | main-v2 重放结果 | 一致性 | 定性 |
|---|---|---|---|---|---|---|
| B1.1 | 创建导出任务 | `POST /api/tasks {"type":"EXPORT","title":"S4基线-导出任务"}` | `201`；`{"id":2,"type":"EXPORT","currentStep":"SELECT_DEFS","status":"ACTIVE","version":1,"items":[]}` | 待重放 | 待比对 | 基线 |
| B1.2 | 选 2 个配置项 | `POST /api/tasks/2/select-defs {"defCodes":["PROJ_PRICE","CURRENCY"]}` | `200`；`items` 2 条，`sortOrder` 0/1，`status=PENDING`，`conditionJson=null`；`task_items` 落 2 行 | 待重放 | 待比对 | 基线 |
| B1.3 | 设查询条件（对象线格式） | `PUT /api/tasks/2/items/PROJ_PRICE/condition`，body `{"condition":{"scopeKeys":["HE-P001"],"fields":[…]}}` | `200 {"success":true}`；**DB 落成 Java `Map.toString()` 形态**：`{scopeKeys=[HE-P001], fields=[{fieldCode=unitPrice, operator=GTE, value=100}]}`——不是合法 JSON | 待重放 | 待比对 | **协议陷阱**：见 §3-① |
| B1.4 | 行数预估 | `GET /api/data/PROJ_PRICE/count?conditions={…}`（JSON 字符串） | `{"count":287}`；CURRENCY → `{"count":3}` | 待重放 | 待比对 | 基线锚点 |
| B1.5 | 导出作业 #1（条件未生效） | `POST /api/tasks/2/jobs {"jobType":"EXPORT"}` | job#1 `COMPLETED` `progress=2 total=2 errorCount=0`；job_items `PROJ_PRICE 1200/1200`、`CURRENCY 5/5`；产物 `rowCount` 1200 / 5 | 待重放 | 待比对 | **实测：对象形态条件被静默丢弃** |
| B1.6 | 导出作业 #2（条件生效） | 先把同样条件以 **JSON 字符串**写入，再 `POST .../jobs {"jobType":"EXPORT"}` | job#2 `COMPLETED` `progress=2 total=2 errorCount=0`；job_items `PROJ_PRICE 287/287`、`CURRENCY 3/3`；产物 `rowCount` 287 / 3 | 待重放 | 待比对 | 基线锚点 |
| B1.7 | 产物文件清单 | `GET /api/tasks/2/files` | 2 条 `TaskFile`；`fileType=EXPORT`；`fileName` `PROJ_PRICE_项目价格表.xlsx` / `CURRENCY_货币字典.xlsx`；`storagePath` 为**本机绝对路径** | 待重放 | 待比对 | **信息泄漏**：见 §3-③ |
| B1.8 | 单文件下载 | `GET /api/tasks/2/files/PROJ_PRICE` | `200`，`Content-Type: …spreadsheetml.sheet`，`Content-Length: 14550`，`Content-Disposition: attachment; filename*=UTF-8''PROJ_PRICE_%E9%A1%B9%E7%9B%AE…`；magic `504b0304`（真 xlsx） | 待重放 | 待比对 | 基线 |
| B1.9 | 打包下载全部 | `GET /api/tasks/2/files/download-all` | `200`，`application/zip`，`filename=export-2.zip`；zip 内 2 个 xlsx（4386 B / 44464 B） | 待重放 | 待比对 | 基线 |
| B1.10 | 勾选下载（单个 code） | `GET /api/tasks/2/files/download?codes=CURRENCY` | 声明 `Content-Type: …spreadsheetml.sheet` 且 `filename*=…CURRENCY_货币字典.xlsx`，**实际返回的是 ZIP**（`unzip -l` 显示内含 1 个 xlsx） | 待重放 | 待比对 | **形态不一致**：见 §3-② |
| B1.11 | 产物行数交叉核对 | 解包 xlsx 读 `xl/worksheets/sheet1.xml` | 作业#1：PROJ_PRICE 1201 行（1 表头+1200 数据）、CURRENCY 6 行（1+5）；作业#2：288 行（1+287）、4 行（1+3）；数据行数 = API `rowCount` = DB = `<row` 计数 −1 | 待重放 | 待比对 | 三方一致 |
| B1.12 | 导出过滤内容正确性 | 读作业#2 的 CURRENCY 产物 | 仅 EUR/GBP/USD（汇率 > 1.0，CNY=1.0 被排除，JPY=0.048 被排除）；PROJ_PRICE 287 行 `unitPrice∈[105.89,999.7]` 全部 ≥100、`projectCode` 集合 = {HE-P001} | 待重放 | 待比对 | 与 `/count` 口径一致 |
| B1.13 | 导出作业 SSE 事件 | 作业前订阅 `GET /api/tasks/2/events` | 作业#1：`HEARTBEAT` → 12×`JOB_PROGRESS`（PROJ_PRICE 每 100 行一条，pct 8/16/25/33/41/50/58/66/75/83/91/100）→ 2×`TASK_CHANGED`（`任务状态已更新: COMPLETED`、`跳转到步骤: EXPORT`）→ `JOB_DONE`；作业#2：`HEARTBEAT` → 2×`JOB_PROGRESS` → 2×`TASK_CHANGED` → `JOB_DONE` | 待重放 | 待比对 | 基线（见 §3-④） |
| B1.14 | 导出任务终态 | 作业#2 完成后 | task#2 `currentStep=EXPORT` `status=COMPLETED` `version=4`；task_items 两项 `COMPLETED` | 待重放 | 待比对 | 基线 |
| B2.1 | 创建导入任务 | `POST /api/tasks {"type":"IMPORT",…}` | `201`；`{"id":3,"type":"IMPORT","currentStep":"UPLOAD","status":"ACTIVE","version":1}`（**IMPORT 初创步骤直接是 UPLOAD**，非 SELECT_DEFS） | 待重放 | 待比对 | 基线 |
| B2.2 | 选 4 个配置项 | `{"defCodes":["CURRENCY","DOC_TYPE","APPROVE_ROLE","PROJ_APPROVE"]}` | `200`；4 条 `task_items`，`sortOrder` 0–3 | 待重放 | 待比对 | 基线 |
| B2.3 | 模板下载（单个） | `GET /api/tasks/3/files/templates?codes=CURRENCY` | `200`，`Content-Type: …spreadsheetml.sheet`，`Content-Length 6282`，`filename*=UTF-8''CURRENCY_%E8%B4%A7%E5%B8%81%E5%AD%97%E5%85%B8.xlsx`；magic `504b0304` | 待重放 | 待比对 | 基线 |
| B2.4 | 模板下载（打包） | `GET …/templates`（不传 codes = 全部 4 项） | `200`，`application/zip`，`filename=templates-3.zip`；zip 内 4 个 xlsx（6282/6566/6186/6446 B，UTF-8 文件名，无乱码） | 待重放 | 待比对 | 基线 |
| B2.5 | 模板下载（显式两项） | `GET …/templates?codes=CURRENCY&codes=DOC_TYPE` | zip 内 2 个 xlsx | 待重放 | 待比对 | 基线 |
| B2.6 | 文件名匹配规则 | 逐个单文件上传（探针） | 不匹配：`CURRENCYRATE.xlsx` / `PROJAPPROVE.xlsx` / `UNKNOWN_DEF_foo.xlsx` → 均 `400`「无法从文件名匹配配置编码…」；匹配：`currency_小写匹配.xlsx`（大小写不敏感）、`CURRENCY-连字符匹配.xlsx`（`-` 分隔）、`CURRENCY_汇率2026.xlsx`（`_` 前缀）、`CURRENCY.xlsx`（完全等值）→ 均 `200 count=1 defCode=CURRENCY` | 待重放 | 待比对 | 基线（规则见 §3-⑤） |
| B2.7 | ZIP 批量上传（混合） | `POST /api/tasks/3/files/upload -F file=@batch-mixed.zip`（2 个可匹配 xlsx + 1 个不可匹配 xlsx + 1 个 .txt） | `200`；`matchedFiles`=[CURRENCY_汇率2026.xlsx→CURRENCY, DOC_TYPE_枚举探针.xlsx→DOC_TYPE]，`unmatchedFiles`=["UNKNOWN_DEF_foo.xlsx（无法匹配配置编码）","readme.txt（非 Excel 文件）"]，`count=2` | 待重放 | 待比对 | 基线 |
| B2.8 | 伪 ZIP（xlsx 改名 .zip） | `-F file=@CURRENCY_bad.zip` | `400`；错误信息把 xlsx 内部条目全量列出：「压缩包中没有可匹配的文件…未匹配: [docProps/app.xml（非 Excel 文件）, …, xl/workbook.xml（非 Excel 文件）…]」（xlsx 本身是 zip，故被当作 zip 解包） | 待重放 | 待比对 | **报错信息冗长/泄漏内部结构**，见 §3-⑥ |
| B2.9 | 正式上传 4 个文件 | 用 UTF-8 multipart 客户端上传 CURRENCY/DOC_TYPE/APPROVE_ROLE/PROJ_APPROVE 的 xlsx | 4 次均 `200 count=1`；`task_files` 4 行 `fileType=UPLOAD`，`fileName` 中文正常，`rowCount=null` | 待重放 | 待比对 | 基线 |
| B2.10 | PRECHECK 作业（8 类规则探针） | `POST /api/tasks/3/jobs {"jobType":"PRECHECK"}` | job#3 `FAILED` `progress=4 total=4 errorCount=3`；job_items：CURRENCY `FAILED`(5/5)、PROJ_APPROVE `FAILED`(3/3)、DOC_TYPE `COMPLETED`(2/2)、APPROVE_ROLE `COMPLETED`(2/2)；task_items 同步 `FAILED`/`CHECKED` | 待重放 | 待比对 | **实测只实现 3 类**，见 §3-⑦ |
| B2.11 | 行级问题明细（≥3 类实例） | `GET /api/jobs/3/issues` | 3 条 ERROR：<br>①`CURRENCY` `rowIndex=5` `rowKey=TST` `"主键重复: TST (第 4 行已存在)"`（业务键重复）<br>②`CURRENCY` `rowIndex=6` `fieldCode=name` `"必填字段 [货币名称] 不能为空"`（必填）<br>③`PROJ_APPROVE` `rowIndex=3` `fieldCode=approveRole` `"引用字段 [审批角色] 的值 GHOST_ROLE 在配置 APPROVE_ROLE 中不存在"`（引用完整性） | 待重放 | 待比对 | 基线锚点 |
| B2.12 | 未实现规则的负向证据 | 同批文件内的定向探针 | 类型：`exchangeRate="N/A"`（NUMBER 字段填文本）→ **无 issue**；枚举域：`category="NOT_A_CATEGORY"`（ENUM 域外值）→ **无 issue**，DOC_TYPE 判 `COMPLETED`；范围有效性：`projectCode="NO_SUCH_PROJ"` → **无 issue**；依赖拓扑 → 仅用于排序、不产出行级 issue；自定义表达式 → 代码中无实现 | 待重放 | 待比对 | **能力缺口**，见 §3-⑦ |
| B2.13 | 暂存隔离基线（导入前） | SQL | `config_data_rows` 总计 1230，`config_staging_rows` 0；CURRENCY 5 / DOC_TYPE 5 / APPROVE_ROLE 4 / PROJ_APPROVE 0 | 待重放 | 待比对 | 隔离锚点 |
| B2.14 | IMPORT 作业（写暂存） | **预检 FAILED 未阻断**，直接 `POST /api/tasks/3/jobs {"jobType":"IMPORT"}` | job#4 `COMPLETED` `progress=4 total=4 errorCount=0`；4 个 job_item 全 `COMPLETED`（5/2/2/3）；task_items → `IMPORTED` | 待重放 | 待比对 | **无预检门禁**，见 §3-⑧ |
| B2.15 | 暂存隔离证据（导入后） | SQL | `config_staging_rows` 12 行（CURRENCY 5、DOC_TYPE 2、APPROVE_ROLE 2、PROJ_APPROVE 3）全 `STAGED`；`config_data_rows` **仍为 1230**，逐 def 与导入前完全相同 → 暂存/已发布强隔离 | 待重放 | 待比对 | 基线锚点 |
| B2.16 | 暂存行版本快照 | SQL `config_staging_rows` | 覆盖已存在键的行 `base_version=1`（CNY/USD/PO/DEPT_MGR）；新增键 `base_version=null`（TST×2/XXX/EX/CTO/STEP1-3）；`scope_type` GLOBAL/REGION/PROJECT 由定义层级推导，`scope_key` 取行内 `projectCode`/`regionCode` | 待重放 | 待比对 | 基线 |
| B2.17 | 暂存差异接口 | `GET /api/jobs/4/diff` | 12 条 `ConfigStagingRow`，`opType=UPSERT`，`status=STAGED`，含 `dataJson` 与 `baseVersion` | 待重放 | 待比对 | 基线 |
| B2.18 | PUBLISH 作业（MERGE 模式） | `POST /api/tasks/3/jobs {"jobType":"PUBLISH"}` | job#5 **`FAILED`** `progress=4 total=4 errorCount=1`，但 4 个 job_item **全 `COMPLETED`**；11/12 行发布成功，第 2 条 `TST` 行 `FAILED` 并落 1 条 ERROR：`"发布冲突：行 TST 在导入后被其他操作修改（快照版本 null，当前版本 1），请重新导入后再发布"` | 待重放 | 待比对 | **逐行非原子 + 重复键误报**，见 §3-⑨ |
| B2.19 | 发布前后已发布行数变化 | SQL 对比 B2.13 | APPROVE_ROLE 4→5、CURRENCY 5→7（+TST +XXX）、DOC_TYPE 5→6（+EX）、PROJ_APPROVE 0→3；总计 1230→1237；覆盖行的 `version` 由 1→3 | 待重放 | 待比对 | 基线锚点 |
| B2.20 | 预检问题未阻断发布（证据） | SQL `config_data_rows` | `PROJ_APPROVE` 已发布行含 `STEP2 approveRole="GHOST_ROLE"`（引用不存在）与 `STEP3 projectCode="NO_SUCH_PROJ"`（范围不存在）；`DOC_TYPE` 含 `EX category="NOT_A_CATEGORY"`（枚举域外） | 待重放 | 待比对 | **脏数据可入库**，见 §3-⑦⑧ |
| B3.1 | 配置定义清单 | `GET /api/definitions` | 7 个定义，`success:true`，每个含 `fields[]`（`code/label/fieldType/required/sortOrder/optionsJson/refDefCode/refFieldCode/key`） | 待重放 | 待比对 | 基线 |
| B3.2 | 层级与排序 | SQL `config_definitions` | `GLOBAL` 3 个（CURRENCY sort0 / DOC_TYPE sort1 / APPROVE_ROLE sort2）、`REGION` 1 个（TAX_RATE sort3）、`PROJECT` 3 个（PROJ_PARAM sort5 / PROJ_APPROVE sort6 / PROJ_PRICE sort8）——**7 个种子配置定义，非冻结需求里的 8 个** | 待重放 | 待比对 | 与 FR「8 配置种子」不符，见 §3-⑩ |
| B3.3 | 字段类型覆盖 | SQL `config_fields`（共 33 字段） | `STRING` 22、`NUMBER` 5、`ENUM` 2、`REFERENCE` 2、`DATE` 1、`BOOLEAN` 1；`BOOLEAN` 仅 DOC_TYPE.enabled，`DATE` 仅 TAX_RATE.effectiveDate | 待重放 | 待比对 | 基线 |
| B3.4 | 定义间依赖 | SQL `config_dependencies` + 字段 | `config_dependencies` **0 行**且代码中无任何引用；依赖完全由 `config_fields.ref_def_code` 表达：`PROJ_APPROVE.approveRole → APPROVE_ROLE.code`、`PROJ_PRICE.currencyCode → CURRENCY.code`，由 `DependencyResolver` 做 Kahn 拓扑排序 | 待重放 | 待比对 | 基线（表为死表） |
| B3.5 | 定义详情/404 | `GET /api/definitions/{code}` | 命中 `200` 含完整字段链；未命中 `404 {"success":false,"message":"配置定义 不存在: NOPE"}` | 待重放 | 待比对 | 基线 |
| B3.6 | 定义模板 | `GET /api/definitions/PROJ_PRICE/template` | `200`，`Content-Type: …spreadsheetml.sheet`，`Content-Length 6480`，`filename*=UTF-8''PROJ_PRICE_项目价格表.xlsx` | 待重放 | 待比对 | 基线 |
| B3.7 | 种子数据分布 | SQL `config_data_rows` | CURRENCY 5、DOC_TYPE 5、APPROVE_ROLE 4、TAX_RATE 4×4=16（HB/HE/HS/XN）、PROJ_PRICE 300×4=1200（HE-P001/HE-P002/HS-P001/HS-P002）；合计 1230 | 待重放 | 待比对 | 基线 |
| B4.1 | AI 健康检查 | `GET /api/ai/health` | `200 {"status":"ok","model":"deepseek-flash","sessionCount":"AiSessionStore"}` | 待重放 | 待比对 | 基线 |
| B4.2 | 工具渐进式披露 | `GET /api/ai/tools`（3 组上下文） | 无条件 `count=5`（list_tasks / list_config_defs / get_config_def / get_workspace_state / create_task）；`page=export&taskType=EXPORT&step=QUERY_COND` → `count=9`（+check_job_status / get_row_count / **set_query_condition** / navigate_to_step）；`page=import&taskType=IMPORT&step=PUBLISH` → `count=8`（+check_job_status / **start_publish** / navigate_to_step） | 待重放 | 待比对 | 基线 |
| B4.3 | 会话创建与上下文 | `POST /api/ai/sessions` → `PUT /api/ai/sessions/{sid}/context` | `201 {"sid":"95a2732b-…"}`；上下文回读 `{"page":"import","taskId":3,"taskType":"IMPORT","step":"PUBLISH","extra":{},"contextKey":"task:IMPORT/PUBLISH"}`；`usage.promptTokens=0` | 待重放 | 待比对 | 基线 |
| B4.4 | run1：只读工具调用 | `POST /api/ai/sessions/{sid}/runs {"message":"…看工作区状态并列出 GLOBAL 层级定义…"}` | 事件序列 `RUN_STARTED` → 14×`TEXT_DELTA` → `TOOL_START/TOOL_DONE`(get_workspace_state) → `TOOL_START/TOOL_DONE`(list_config_defs) → 376×`TEXT_DELTA` → `RUN_COMPLETED{usage:{promptTokens:2745,completionTokens:473}}`；工具返回 `get_workspace_state` = `"当前未打开任何任务，请先创建或选择一个任务"`（**与 session.context.taskId=3 不一致**） | 待重放 | 待比对 | **上下文断链**，见 §3-⑪ |
| B4.5 | run2/run3：模型拒绝调用高风险工具 | 用户明确要求「调用 start_publish 发布任务 #3」 | 两轮均**未产生任何 `TOOL_START`**，只输出文本并以 `RUN_COMPLETED` 结束；模型自述原因是 `get_workspace_state` 返回「当前未打开任何任务」，无法确认 taskId=3 有效，故拒绝执行 | 待重放 | 待比对 | **真实模型行为**（安全但未完成指令） |
| B4.6 | run4：触发 HITL 人机确认 | 消息要求「先 list_tasks、再 get_workspace_state(taskId=3)、然后立即 start_publish」 | 事件序列：`RUN_STARTED` → 16×`TEXT_DELTA` → `TOOL_START/TOOL_DONE`(list_tasks) → `TOOL_START/TOOL_DONE`(get_workspace_state) → 12×`TEXT_DELTA` → `TOOL_START`(**start_publish**) → **`INTERACTION_REQUEST`** → （挂起等待） | 待重放 | 待比对 | 基线（HITL 协议锚点） |
| B4.7 | HITL 确认交互形态 | `POST /api/ai/sessions/{sid}/interactions/{iid} {"approved":true,"reason":"…"}` | 请求体：`{"type":"INTERACTION_REQUEST","iid":"16cd0e96-…","runId":"dde5f5e9-…","toolCallId":"call_00_uevkSFbfbYK7Uopv3kmG4512","interactionType":"CONFIRM","summary":"即将执行高风险操作: 启动发布作业，将暂存数据正式发布到生产数","details":"工具: start_publish\n参数: {\"taskId\": 3, \"confirmed\": true}","params":{"toolName":"start_publish","args":"{\"taskId\": 3, \"confirmed\": true}"}}`；提交后 `200 {"submitted":true}`，流内续发 `TOOL_DONE{success:true,summary:"用户已批准"}` → `TOOL_DONE{success:true,summary:"\"发布作业已启动（作业 #6），正在将数据写入正式库…\""}` → 文本 → `RUN_COMPLETED{usage:{promptTokens:8475,completionTokens:413}}` | 待重放 | 待比对 | 基线锚点 |
| B4.8 | AI 触发的发布作业结果 | `GET /api/jobs/6` | 作业#6 `PUBLISH` `FAILED` `errorCount=12`（12 行全部报「发布冲突」，含 `快照版本 1，当前版本 3` 与 `快照版本 null，当前版本 1` 两种）；已发布行数未变（APPROVE_ROLE 5 / CURRENCY 7 / DOC_TYPE 6 / PROJ_APPROVE 3）——**重复发布已发布的暂存行必然全量冲突** | 待重放 | 待比对 | 基线（幂等性缺口） |
| B4.9 | 会话内存态 | `GET /api/ai/sessions/{sid}` | `runActive=false`、`hasPendingInteraction=false`、`usage={promptTokens:8475,completionTokens:413}`、`messages` 16 条（role ∈ user/assistant/tool，tool 项带 `toolName`+截断 `summary`）；H2 中**不存在 ai_session 类表**（Schema 14 张表均无），会话不落库 | 待重放 | 待比对 | 基线 |

---

## 2. 全链路终态快照（便于重放后一键对照）

`SELECT` 单行聚合（录制结束时）：

| defs | fields | deps | published | staging | tasks | task_items | task_files | jobs | job_items | issues |
|---|---|---|---|---|---|---|---|---|---|---|
| 7 | 33 | 0 | 1237 | 12 | 2 | 6 | 6 | 6 | 20 | 16 |

`jobs` 明细：

| id | task_id | job_type | status | progress | total | error_count | started_at | finished_at |
|---|---|---|---|---|---|---|---|---|
| 1 | 2 | EXPORT | COMPLETED | 2 | 2 | 0 | 01:18:50.828 | 01:18:54.869 |
| 2 | 2 | EXPORT | COMPLETED | 2 | 2 | 0 | 01:19:24.365 | 01:19:25.132 |
| 3 | 3 | PRECHECK | FAILED | 4 | 4 | 3 | 01:20:38.537 | 01:20:39.936 |
| 4 | 3 | IMPORT | COMPLETED | 4 | 4 | 0 | 01:20:52.426 | 01:20:52.526 |
| 5 | 3 | PUBLISH | FAILED | 4 | 4 | 1 | 01:21:03.259 | 01:21:03.303 |
| 6 | 3 | PUBLISH | FAILED | 4 | 4 | 12 | 01:23:46.199 | 01:23:46.249 |

`tasks` 明细：task#2 EXPORT `currentStep=EXPORT` `status=COMPLETED` `version=4` `settingsJson=null`；task#3 IMPORT `currentStep=PUBLISH` `status=ACTIVE` `version=4` `settingsJson=null`（**从未调用过 `PUT /import-mode`，默认即 MERGE**）。

---

## 3. 供 S4 合流对照的关键契约点（每条都附本次实测证据）

### ① 查询条件的线格式是"JSON 字符串"，不是嵌套对象 ★最高优先级
- 控制器 `TaskController.setCondition` 用 `body.get("condition").toString()` 取值，**对象入参会退化为 Java `Map.toString()`**。
- 实测：嵌套对象写入后 DB 为 `{scopeKeys=[HE-P001], fields=[…]}`（非 JSON），`ExportJobRunner.parseCondition` 反序列化失败后 `catch → return null`，过滤条件**被静默丢弃**：作业#1 导出 1200/5 行（全量），而同一条件以字符串写入后作业#2 导出 287/3 行。
- 前端 `frontend/src/views/export/StepQueryCond.vue:235-236` 用的是 `JSON.stringify(buildCondition(...))`，即字符串形态——**合流后必须保持这一线格式**，且建议把"对象入参"改为显式 400 而不是静默失效。
- 对应的预估接口 `GET /api/data/{defCode}/count?conditions=<JSON 字符串>` 是严格解析的（解析失败抛 `IllegalArgumentException`），与导出作业口径一致。

### ② `GET /api/tasks/{id}/files/download?codes=X` 单元素时"名为 xlsx 实为 zip"
- 代码 `FileController.downloadSelected` 先 `buildZip(...)` 再按 `files.size()==1` 把 `Content-Type` 改成 xlsx —— 内容仍是 zip。实测 `codes=CURRENCY` 返回 3712 B、`unzip -l` 可解出 1 个 4386 B 的 xlsx。前端若按 xlsx 直接给 SpreadJS 解析会失败。

### ③ `TaskFile.storagePath/originalPath` 直接下发本机绝对路径
- `GET /api/tasks/{id}/files` 与 `/overview` 的响应体含 `"storagePath":"<REPO_ROOT>\\backend\\.\\data\\files\\<uuid>.xlsx"`。合流后若保留该字段，会泄漏部署机目录结构。

### ④ 任务级 SSE 协议（导出/导入共用）
- 帧格式固定为 `data:{"type":"<TYPE>","data":{...}}\n\n`，**不设置 SSE `event:` 名**，类型在 JSON 内。
- 类型集合（实测出现）：`HEARTBEAT`（订阅瞬间即发一次）、`JOB_PROGRESS`、`TASK_CHANGED`、`JOB_DONE`。
- `JOB_PROGRESS` 只在"处理行数 % `app.job.batch-size`(100) == 0"时发送，`pct = processed*100/total` 向下取整。**行数 <100 的配置项全程不发进度**（作业#1 的 CURRENCY 5 行、作业#2 的 CURRENCY 3 行均无事件）。
- `TASK_CHANGED` 携带 `{taskId, version, currentStep, status, summary}`，`summary` 为中文动作描述（如 `任务状态已更新: COMPLETED`、`跳转到步骤: EXPORT`）。
- `JOB_DONE` 在 `finally` 中发送：EXPORT 为 `{jobId, jobType, status}`；PRECHECK 额外带 `errors`。
- 取消路径额外发 `JOB_DONE {status:"CANCELLED"}`（见 `JobService.cancel`；本次未复现，已有 W1 证据覆盖）。

### ⑤ 上传文件名匹配规则（`FileController.matchDefCode`）
- 规则：把文件名去掉 `.xlsx/.xls` 后缀并 `toUpperCase()`，命中条件为 `base == code` 或 `base.startsWith(code + "_")` 或 `base.startsWith(code + "-")`；多个命中时取 **最长 code**。
- 实测边界：`CURRENCY.xlsx`✅、`CURRENCY_汇率2026.xlsx`✅、`CURRENCY-连字符匹配.xlsx`✅、`currency_小写匹配.xlsx`✅（大小写不敏感）；`CURRENCYRATE.xlsx`❌（**缺 `_`/`-` 分隔符即不匹配**）、`PROJAPPROVE.xlsx`❌、`UNKNOWN_DEF_foo.xlsx`❌ → 均为 `400`「无法从文件名匹配配置编码…」。
- 同一 `(taskId, defCode, fileType)` **重复上传会覆盖**既有 `TaskFile` 行（实测 CURRENCY 的 `version` 累加到 7，`storagePath` 被替换为新 uuid 文件，旧文件残留）。批量 ZIP 中若某个 code 命中两次，后者覆盖前者。

### ⑥ 伪 ZIP 报错把包内全部条目回显
- `readZipEntries` 不做 zip 魔数校验；xlsx 改名为 `.zip` 后会被当成 zip 解压，`unmatched` 列表把 `docProps/app.xml`、`xl/workbook.xml`… 全量拼进 `400` 错误消息。合流后建议加魔数校验 + 截断错误明细。

### ⑦ 冻结需求 FR-3.3 的"8 类规则"实测只落地 3 类
需求原文（`<MAIN_V2>/docs/02-需求设计文档-v2.2-冻结版.md:97` FR-3.3 P0）：
> 检查（预检）：8 类规则——必填、类型、枚举域、引用完整性、业务键重复、依赖拓扑、范围有效性、自定义表达式；输出行级明细（行号/字段/原因）且可下载

本次实测对照：

| 需求规则 | 实现情况 | 本次实测证据 |
|---|---|---|
| 必填 | ✅ 已实现 | `CURRENCY` 行 6 `fieldCode=name`「必填字段 [货币名称] 不能为空」 |
| 业务键重复 | ✅ 已实现 | `CURRENCY` 行 5 `rowKey=TST`「主键重复: TST (第 4 行已存在)」 |
| 引用完整性 | ✅ 已实现（值级，含"已发布 + 本任务暂存"） | `PROJ_APPROVE` 行 3 `approveRole`「值 GHOST_ROLE 在配置 APPROVE_ROLE 中不存在」 |
| 类型 | ❌ 未实现 | `exchangeRate="N/A"` 填入 NUMBER 字段 → 0 条 issue |
| 枚举域 | ❌ 未实现 | `category="NOT_A_CATEGORY"` 填入 ENUM 字段 → 0 条 issue，DOC_TYPE 判 `COMPLETED` |
| 范围有效性 | ❌ 未实现 | `projectCode="NO_SUCH_PROJ"` → 0 条 issue，且已发布入库 |
| 依赖拓扑 | ⚠️ 仅用于处理顺序 | `DependencyResolver.sort` 决定 job_item 生成顺序；不产出行级 issue |
| 自定义表达式 | ❌ 未实现 | 代码中无对应判定分支 |
| （额外）文件缺失 | ✅ 已实现 | `"未找到上传文件，请先上传配置数据文件"`（本次因 4 个文件齐全未触发） |
| （额外）过程异常 | ✅ 已实现 | `"检查过程出错: …"` / `"导入过程出错: …"` / `"发布过程出错: …"`（本次未触发） |

- **"行级明细可下载"未实现**：`ValidationIssue` 只有 API 分页接口 `GET /api/jobs/{jobId}/issues`，无导出/下载端点。

### ⑧ 预检失败不构成门禁
- 实测：`PRECHECK` job#3 `FAILED`（`errorCount=3`，task_items 两项 `FAILED`）之后，`IMPORT`(job#4) 与 `PUBLISH`(job#5) 仍可正常发起并成功写入生产数据。合流后若要求"预检不过不得导入"，需要显式加门禁。

### ⑨ 发布是逐行、非原子的，且重复键会误报"发布冲突"
- `PublishJobRunner.run` 逐 staging 行 upsert，单行失败只把该行置 `FAILED` 并 `totalErrors++`，其余行照常提交 → 出现 **job `FAILED` 而全部 job_item `COMPLETED`、11/12 行已入库** 的状态（见主表 B2.18/B2.19）。
- `hasConflict(sr, existing)` 逻辑：`base_version != null` 时"已发布行不存在或版本不等"即冲突；`base_version == null` 时"已发布行已存在"即冲突。因此**同一批次里同一主键的第二行**（如两个 `TST`）在第一行插入后必然被判为冲突，报「快照版本 null，当前版本 1」——这是误报，掩盖了真正的原因是"预检已报主键重复却没拦住"。
- 覆盖更新的行 `version` 由 1 → 3（`entityManager.lock(..., OPTIMISTIC_FORCE_INCREMENT)` 强制递增），新增行为 1。
- 重复发布同一批暂存行必然全量冲突（作业#6 的 12 条），因为 `base_version` 仍指向导入时刻的旧版本。

### ⑩ 种子配置定义是 7 个，不是冻结需求的 8 个
- `DataSeedRunner.seedDefinitions()` 依次创建 CURRENCY、DOC_TYPE、APPROVE_ROLE、TAX_RATE、PROJ_PARAM、PROJ_APPROVE、PROJ_PRICE，共 **7 个**（SQL 实测 `7`）。手册 S4.1 验收项写的是"8 配置种子落库"——**源分支本身就只有 7 个**，重放时若按 8 验收会直接失败；需要确认第 8 个是哪一个分支补的。
- `DataSeedRunner` 的幂等条件是 `regionRepository.count() > 0` → 只要 `regions` 非空就整体跳过播种（不会补齐缺的定义）。

### ⑪ AI 侧 `get_workspace_state` 不感知会话上下文
- 会话上下文里 `taskId=3 / step=PUBLISH` 已设置成功（`GET /api/ai/sessions/{sid}` 回读确认），但 run1 里 `get_workspace_state` 工具返回 `"当前未打开任何任务，请先创建或选择一个任务"`。
- 后果（真实模型行为，非幻觉）：run2/run3 两轮，模型**主动拒绝**调用 `start_publish`，理由是"无法确认 taskId 有效"，只输出文本结束（无任何 `TOOL_START`）。run4 改为让它先 `list_tasks` + `get_workspace_state(taskId=3)` 核对后才调用成功。
- 合流时这是一个**人机信任链路**的实打实缺口，建议把 `session.context.taskId` 接入 `get_workspace_state`。

### ⑫ AI SSE 事件协议（供 S4.2 对照）
- 帧格式同样为 `data:{json}`，无 `event:` 名；但**字段是平铺的**（不像任务 SSE 那样包在 `data` 下）。
- 类型集合：`RUN_STARTED{runId}`、`TEXT_DELTA{delta}`（**逐字分片**，一次 run 可达 300+ 条）、`TOOL_START{toolCallId,toolName,title}`、`TOOL_DONE{toolCallId,success,summary}`、`INTERACTION_REQUEST{iid,runId,toolCallId,interactionType,summary,details,params}`、`UI_COMMAND{command,payload}`（本次未触发）、`ERROR{message}`（本次未触发）、`RUN_COMPLETED{runId,usage:{promptTokens,completionTokens}}`、`HEARTBEAT`（代码中存在，本次未在 run 内出现）。
- HITL 严格顺序（run4 实测）：`TOOL_START(start_publish)` → **`INTERACTION_REQUEST` 后流挂起**（不继续发任何帧）→ 用户 `POST /interactions/{iid}` → 先发 `TOOL_DONE{"用户已批准",success:true}`，**再**发一次携带真实工具结果的 `TOOL_DONE`（同一 `toolCallId` 出现两条 `TOOL_DONE`）→ 文本 → `RUN_COMPLETED`。被拒绝时（代码路径）发一条 `TOOL_DONE{success:false,summary:"操作已拒绝"}`，工具响应文本为 `"用户拒绝了此操作: <原因>"`。
- 只有 `@ToolRisk(DANGER)` 的工具触发 HITL；本次全部工具中仅 `start_publish` 是 DANGER（`set_query_condition`/`start_export`/`start_precheck`/`start_import`/`create_task`/`select_defs`/`navigate_to_step` 为 WRITE，自动执行）。

### ⑬ 其它重放时需要知道的取值细节
- IMPORT 任务创建后 `currentStep` 直接是 `UPLOAD`；`selectDefs` 会把 `currentStep` **重置**为 `SELECT_DEFS`（导出链实测 task#2 被重置），故正确顺序是"先 select-defs 再 step"。
- `selectDefs` 返回前会 `entityManager.refresh(task)`，因此响应里的 `items` 是刚写入的（含 `conditionJson`）。
- Excel 往返后所有单元格值都是**字符串**：新增行的 `dataJson` 形如 `{"enabled":"true","exchangeRate":"3.5"}`，而种子行（不经 Excel）是原生类型 `{"enabled":true,"exchangeRate":7.89}`。合流后若做类型敏感比较需注意。
- 导出的工作表结构：第 1 个 sheet 名为 `def.getName()`，表头为 `"*"+label`（`required=true` 才有 `*`）；第 2 个 sheet `_meta` 为 **hidden**，row0 = `["def_code", *labels]`、row1 = `[defCode, *fieldCodes]`；导入端 `ExcelReader.readMetaSheet` 优先读 `_meta`，读不到才回退按表头文本匹配（此时需 label 或 code 完全相等）。
- 种子里的 `PROJ_PRICE` 只覆盖 HE-P001/HE-P002/HS-P001/HS-P002 四个项目，`HS-P002` 等未覆盖；`TAX_RATE` 覆盖 4 个地区；`PROJ_PARAM`/`PROJ_APPROVE` **种子无数据**（0 行）。
- `config_dependencies` 表是死表（0 行、无 Java 引用），合流时不要把它当作依赖来源。

---

## 4. B4 真实模型调用记录（token 用量取自 SSE 的 `RUN_COMPLETED`）

| run | 触发消息（节选） | 工具调用 | 事件数（含 TEXT_DELTA） | promptTokens | completionTokens | 结局 |
|---|---|---|---|---|---|---|
| run1 | 「看工作区状态并列出 GLOBAL 层级定义，只读」 | `get_workspace_state`、`list_config_defs` | 392 | 2745 | 473 | `RUN_COMPLETED`（正常应答） |
| run2 | 「我确认要发布，请调用 start_publish」 | 无 | 326 | 1879 | 344 | `RUN_COMPLETED`（模型拒绝，自述工作区状态不一致） |
| run3 | 「明确授权，立即调用 start_publish，不要反问」 | 无 | 293 | 2263 | 312 | `RUN_COMPLETED`（模型再次拒绝） |
| run4 | 「先 list_tasks、再 get_workspace_state(3)、然后立即 start_publish」 | `list_tasks`、`get_workspace_state`、`start_publish`(HITL) | 295 | 8475 | 413 | `RUN_COMPLETED`（发布作业 #6 已启动） |

> run2/run3 的"未调用工具"是**模型真实输出**，不是录制失败；它在不确定工作区状态时选择了保守拒绝，这一点本身对 S4.2 的 Agent 编排有参考价值。

---

## 5. 录制状态自检（如实记录）

| 场景 | 状态 | 说明 |
|---|---|---|
| B1 导出全链路 | **录制成功** | 任务状态机（SELECT_DEFS→QUERY_COND→EXPORT / ACTIVE→COMPLETED）、作业状态机（PENDING→RUNNING→COMPLETED）、进度事件形态、产物清单与行数（API/DB/xlsx 三方一致）、三种下载接口形态全部取到 |
| B2 导入全链路 | **录制成功** | 模板单个+打包、上传文件名匹配 6 例、ZIP 批量 + 伪 ZIP、PRECHECK 3 类行级问题实例、暂存隔离证据、发布前后行数变化全部取到；8 类规则中 5 类以"负向证据"形式记录（实测不报错） |
| B3 配置定义与目录 | **录制成功** | 7 个定义清单/详情/层级/字段类型/依赖/模板全部取到；发现种子为 7 个而非需求的 8 个 |
| B4 AI 对话链路 | **录制成功**（含 2 次模型拒绝的真实记录） | 4 轮真实模型调用；SSE 事件协议、渐进式披露、HITL 确认交互形态、会话内存态全部取到 |
| 未做/未触发（不补全） | — | ① 任务级 SSE 的 `CANCELLED` 分支未复现（本次不涉及取消，已有 W1 证据）；② AI 的 `UI_COMMAND`/`ERROR`/run 内 `HEARTBEAT` 事件未触发；③ REPLACE 导入模式未测（未调用 `PUT /import-mode`，全程 MERGE）；④ "行级明细可下载"端点不存在，无法测 |

---

## 6. 收尾核对

- 后端自启进程已终止：`tasklist /FI "IMAGENAME eq java.exe"` 无匹配进程。
- `netstat -ano | grep 18296` **无 LISTENING**（仅剩若干内核 TIME_WAIT，属已关闭的客户端连接，会自动回收）。
- 18080 / 18290–18295 / 18301–18307 / 18312 全程未监听。
- 被测分支工作副本：`git status --porcelain` 为空，`git log -1` 仍为 `a98d0b2b83e9bf71671fdd44a3b8426118f7b57a`（无 git 写操作）。
- 录制前原库备份保留在 `<TMP>/s4-baseline-combined/db-backup-original/`（`config_mgr_db.mv.db`、`config_mgr_db.trace.db`、`files/`）。当前 `<REPO_ROOT>/backend/data/` 是本次基线运行后的库（含上述 §2 终态），供 main-v2 重放时对照"输入相同 → 输出相同"。
