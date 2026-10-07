# S5b 证据：deepseek-v4-pro「22 用例 PowerShell E2E」移植到 main-v2 并实跑验证

> 施工类型：测试资产移植（E2E 脚本）+ 真实运行验证。**未改任何生产代码**（`backend/src/main/**` 零改动）。
> 移植源（只读，未修改）：`<REPO_ROOT>/ai-example-code/ai-example-deepseek-v4-pro/scripts/verify-e2e.ps1`（685 行 / TC1–TC22）
> 产出物：`<MAIN_V2>/scripts/verify-e2e.ps1`（1326 行）与本文（`git status --porcelain` 实测两条 `??`，无其他改动；未修改任何既有文件）
> 执行日期：2026-10-07　分支：main-v2（HEAD 8043224）　端口：18330（独立 H2 库文件，不动演示库）
> 本文只记录真实运行结果；未实测到的一律标注【未触发】/【待裁决】，不做"应该是这样"的补全。

## 0. 元信息与脱敏口径

| 项 | 值 |
|---|---|
| 仓库 | `<MAIN_V2>`（= `<REPO_ROOT>/ai-example-code/ai-example-main-v2`） |
| 移植源 | `<REPO_ROOT>/ai-example-code/ai-example-deepseek-v4-pro/scripts/verify-e2e.ps1`（md5 与 V3 证据一致：685 行） |
| 新增产物 | `<MAIN_V2>/scripts/verify-e2e.ps1`（1326 行；md5 `bdc7a86005653e00a72437c6d3363fbe`） |
| JDK / Maven | OpenJDK 21.0.12（PATH）/ `<MAVEN_HOME>/mvn.cmd -B -DskipTests package` → `<MAIN_V2>/backend/target/config-mgr.jar`（95 390 240 字节） |
| 客户端 | Windows PowerShell 5.1（`powershell.exe`；`pwsh` 不存在，与 V3 核验一致）；本机另用 Git Bash + curl 做前置探针 |
| 数据库 | 独立库文件 `./data/e2e_s5b_db`（**不动** `<MAIN_V2>/backend/data/config_mgr_db.mv.db` 演示库；每轮验证前删除该文件冷启） |
| Redis | Memurai `127.0.0.1:6379`（`<MEMURAI_HOME>/memurai-cli.exe ping` → `PONG`） |
| AI | 真实 `https://api.deepseek.com` / `deepseek-flash`；key 仅经环境变量注入进程（`AI_API_KEY`），**未写入任何文件**；本文与脚本中 key 一律 `***` |
| 原始记录 | `<TMP>/s5b-verify/*`（后端日志 `boot6.log`、两轮脚本 stdout `runA.txt`/`runB.txt`） |
| 脱敏 | 本机绝对路径 → `<MAIN_V2>` / `<REPO_ROOT>` / `<MAVEN_HOME>` / `<MEMURAI_HOME>` / `<TMP>`；key → `***` |

## 1. 产出物与运行方式

### 1.1 脚本定位与设计取舍

源脚本面向 deepseek 分支的**两套独立对象**（`/api/export/tasks`、`/api/import/batches`）与「批次」语义；
main-v2 的对象模型是**任务（tasks）+ 作业（jobs: EXPORT/PRECHECK/IMPORT/PUBLISH）**，发布语义为
**行级 upsert + 范围差集删除**，AI 侧有**会话 409 串行化 / 确认门 / 前端工具挂起 / reattach**。
故本脚本**保持 22 个用例编号与测试意图可对照**，逐条按 main-v2 的真实 API 与语义重写断言（差异见 §2、§3）。

三条移植期硬约束（脚本头部注释同样写明）：

1. **后端启动参数**：需 `--app.job.batch-size=10 --app.job.demo-batch-delay-ms=150`。
   理由：`JOB_PROGRESS` 只在分片边界产生；默认 `batch-size=100` 时，TC11 所用的 120 行配置项只会在第 100 行出现**一处**边界
   （更小的数据集则一处都没有），不足以断言"进度事件流"。取 10 可稳定产生 12 条进度帧。
2. **`-BackendLog`**：TC15 的渐进披露断言与 Q8 的两项断言依赖后端 DEBUG 日志（`logging.level.com.example.configmgr=DEBUG` 已在 `application.yml`）。
   未提供时这两处判 **FAIL**（不静默跳过、不降级为 SKIP）。日志读取用 `FileShare.ReadWrite` 打开（后端进程持有写句柄）。
3. **上传件构造走"导出产物 + 单元格手术"闭环**：main-v2 的数据面**没有行级 CRUD 端点**（写路径只有导入/发布），
   故脚本先跑导出作业拿到合法 xlsx，再按单元格引用（`r="B2"`，main-v2 导出件为 inline string）精确改写/清空值，
   构造"必填缺失/主键重复/引用不存在/范围缺失"四类缺陷件与"3 行子集件"。zip 由 `System.IO.Compression` 显式建条目（正斜杠名）避免 OOXML 包不合规。

### 1.2 运行命令（原样）

```bash
# 构建
cd <MAIN_V2>/backend
"<MAVEN_HOME>/bin/mvn.cmd" -B -DskipTests package          # BUILD SUCCESS，产出 target/config-mgr.jar

# 启动（独立库文件 + 冷启；AI key 只进进程环境变量）
cd <MAIN_V2>/backend
rm -f data/e2e_s5b_db.mv.db data/e2e_s5b_db.trace.db
AI_API_KEY="***" java -jar target/config-mgr.jar \
  --server.port=18330 \
  --spring.datasource.url="jdbc:h2:file:./data/e2e_s5b_db;DB_CLOSE_DELAY=-1" \
  --app.job.batch-size=10 --app.job.demo-batch-delay-ms=150 > <TMP>/boot6.log 2>&1 &

# 执行 E2E（两轮：第 2 轮为幂等重跑）
cd <MAIN_V2>
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/verify-e2e.ps1 \
  -Base http://127.0.0.1:18330 -BackendLog <TMP>/boot6.log
```

端口纪律：启动前 `netstat -ano | grep 18330` 无监听；未触碰 18080 / 18290–18299 / 18301–18321。

## 2. 22 用例映射表（源 → main-v2 目标 → 断言改写点 → 结果）

编号沿用源脚本（执行顺序亦与源一致：TC1–TC14 → TC20/21 → TC15–TC18 → TC22 → TC19 收尾）。

| # | 源用例 | main-v2 目标（API） | 关键改写点 | 结果 |
|---|---|---|---|---|
| TC1 | 配置定义列表与种子数据 | `GET /api/definitions`、`GET /api/data/{code}/count` | 种子 = 基座 7 + glm 并集 8 = **15** 个定义（源为 4）；`publishedRowCount` 字段不存在 → 用 `/count`；`dependsOn` 数组 → **REFERENCE 字段**（ALARM_THRESHOLD.metricCode → METRIC_DICT.metricCode）；新增 level/keyword 过滤断言 | PASS |
| TC2 | 动态配置定义 CRUD | `POST/PUT /api/definitions` | 字段 JSON 为 `fieldType`/`key`（非 `type`/`isKey`）；重复编码由唯一约束拒绝（**实测 HTTP 500**，断言口径=必须被拒绝）；追加字段走**整份 fields 替换**语义 → 4 字段；新增"无主键定义被拒（400）" | PASS |
| TC3 | 数据行校验引擎 | `POST /api/tasks/{id}/jobs {PRECHECK}` + `GET /api/jobs/{id}/issues` | main-v2 **无行级数据 CRUD**，校验发生在预检查作业；规则集 = **必填 / 主键重复 / 引用存在性**（无枚举合法性与数值范围校验，`ImportFlowJobTest` 已记）；范围必填以 REGION 级 `regionCode`（key+required）表达；四类缺陷件由导出件手术构造，`errorCount=4` | PASS |
| TC4 | 导出（全量+进度+文件） | `EXPORT` 作业 + `GET /api/tasks/{id}/files`、`/files/{defCode}` | 文件清单含 `rowCount`（5/5/12）；`progress==total`；下载件 PK 魔数 + 长度 | PASS |
| TC5 | 导出查询条件 | `PUT /api/tasks/{id}/items/{defCode}/condition` + `EXPORT` | 条件形态改为 main-v2/前端同形的 `{scopeKeys,fields}`；行数按实况：code=CNY→**1**、HE→**3**、HE+XN→**10**（源为 3/1）；条件条目落库后状态 `READY` | PASS |
| TC6 | zip 打包下载 | `/files/download?codes=`、`/files/download-all` | 条目名 = "编码_名称.xlsx"，断言条目数与编码前缀（2 / 3 条目） | PASS |
| TC7 | 模板下载 | `/files/templates?codes=`、`/api/definitions/{code}/template` | 单个 → xlsx；多个 → zip（2 条目）；含定义级模板端点 | PASS |
| TC8 | 上传文件名匹配 | `POST /api/tasks/{id}/files/upload` | main-v2 匹配规则 = 文件名等于编码或以 `编码_`/`编码-` 开头；**源脚本支持的 "(1) 序号后缀"不被识别 → 断言为被拒绝**；新增"无法匹配单文件 400""zip 全不匹配 400"；未匹配清单字段名 `unmatchedFiles` | PASS |
| TC9 | 检查通过 + 依赖拓扑 | `PRECHECK` + `DependencyResolver` | 依赖由 REFERENCE 推出（METRIC_DICT 先于 ALARM_THRESHOLD）；**`job_items` 无 `@OrderBy`（返回数组按 (job_id,def_code) 索引）→ 拓扑序以条目 id 递升为判据**（实测 METRIC_DICT id 更小） | PASS |
| TC10 | 检查失败明细 + 未上传文件 | `GET /api/jobs/{id}/issues` | 明细**落库可查**（分页 `totalElements`/首页条数/归属）；未上传文件的配置项报 "未找到上传文件"；日志无 `NoSuchFileException`/`明细写入失败`（Q8① 行为面） | PASS |
| TC11 | SSE 进度事件流 | `GET /api/tasks/{id}/events` | 帧形态：无名事件 + `data` 内 `{type,data}`；**无 snapshot 重放 → 脚本先订阅（响应头到达即视为订阅成功）再启动作业**；12 条 `JOB_PROGRESS` 单调递增、末条 120/120、`JOB_DONE(COMPLETED)` | PASS |
| TC12 | 导入草稿隔离 | `IMPORT` + `GET /api/jobs/{importJobId}/diff` | 暂存行经 `diff` 端点读取（源为 `/drafts/{code}`）；生效数据保持 5 行、暂存 3 行 `STAGED`、任务条目 `IMPORTED` | PASS |
| TC13 | 发布：替换生效 | `PUBLISH` + `PUT /import-mode REPLACE` | 5 行 → 发布 3 行子集 → **3 行**；**upsert 保行 id、版本递增**；差集删除 GBP/JPY；"重复发布拦截"改为 main-v2 更强的**导入快照版本比对**（二次发布 → 作业 FAILED + "发布冲突…" 明细，数据零变化）；终态作业取消 → 409 `JOB_ALREADY_FINAL` | PASS |
| TC14 | 发布前置守卫 | `IMPORT`/`PUBLISH` | 源"检查失败 → 禁止导入/发布"**在 main-v2 无守卫**（`ImportFlowJobTest` 已登记为实现事实）→ 改写为两层可验证守卫：① 无上传文件 → IMPORT FAILED + 明细；② REPLACE 模式发布**空暂存集 → 生效数据零变化**（28 行不变，不误删） | PASS |
| TC20 | 任务中心 | `GET /api/tasks` | 信封为 Spring `Page`（`content`/`totalElements`，**page 从 0 起**）；行 = `TaskSummary{task,itemCount,fileCount,latestJob}`；新增状态过滤与 **`keyword=%` → 0 命中**（Q16② LIKE 转义）断言 | PASS |
| TC21 | 任务分页 | `GET /api/tasks?page=&size=` | page 0/1、size 5，无重叠且按 id DESC（Q16① 第二排序键） | PASS |
| TC15 | AI 流式 + 工具 + 渐进披露 | `POST /api/ai/chat` + 后端 DEBUG 日志 | main-v2 **无 `/api/ai/tools` 端点**（披露在请求边界由 `ToolRegistry.forContext` 裁剪）→ 按 main-v2 自身取证口径断言日志行「上下文=\<ctx\> 披露工具=[…]」：`page:tasks` 12 个工具（含 `start_export`/`create_task`/`navigate_to`，**无 `start_publish`**）vs `task:IMPORT/PUBLISH`（含 `start_publish`，**无 `start_export`**）；帧断言 start/delta/tool_start(`list_config_defs`)/tool_result/done | PASS |
| TC16 | AI 工具驱动工作区 | 确认门 + 前端工具通道 | main-v2 **无 `ui_event` 帧**：作业发起是 DANGER 工具（`confirm_request` 挂起 → `POST /api/ai/confirm` 放行后由服务层真实建作业）；工作区动作是 FRONTEND 通道工具（`frontend_tool_request` 挂起 → `POST /api/ai/frontend-tool-result` 回灌 → `GET /api/ai/events/{runId}?lastSeq=` 续读）；实测放行后真实产出导出文件，前端工具 `executed=false`（后端不直接执行） | PASS |
| TC17 | HITL：发布需确认 | 确认门 拒绝/确认 两路 | 拒绝 → 无 PUBLISH 作业、暂存仍 `STAGED`、生效行**版本零变化**；重复提交同一决策 → 409 `DUPLICATE_TOOL_CALL_ID`；确认 → PUBLISH `COMPLETED`、暂存行提升 `PUBLISHED`（源用 `confirm_tool` 帧 + `/api/ai/confirm{sessionId,approved}`） | PASS |
| TC18 | 会话隔离 / 串行化 | `history`、`chat` 409、`cancel` | main-v2 **无 `/api/ai/sessions/count` 与 `/clear`** → 改写为：① 两会话记忆互不污染（按会话标识串验证）；② 同会话第二轮 → **409 `SESSION_BUSY` 且携进行中 runId + `reattach=/api/ai/events/{runId}`**（ADR-5/Q11）；③ `POST /api/ai/cancel/{runId}` 后同会话可再次发起 | PASS |
| TC22 | 历史恢复 | `GET /api/ai/history/{sessionId}` | 响应为 `{sessionId,count,messages[{type,text,toolCalls?,toolResponses?}]}`（源为 `role/tools`）；断言 USER/ASSISTANT 计数 + 工具卡片（`toolCalls`）+ 未知会话 `count=0` | PASS |
| TC19 | 清理与演示数据恢复 | `DELETE /api/definitions/{code}`、`DELETE /api/tasks/{id}` | 任务删除为**级联清理**（作业/条目/问题/暂存/文件）：删后任务 404 + 其作业 404；E2E_TEMP 删除后 404；CURRENCY 经"全量导出件 + REPLACE 发布"恢复 5 行（源为删 E2E_TEMP + 恢复 SERVER_PARAM） | PASS |

**两轮结果：PASS=22 / FAIL=0（退出码 0），两轮均一致。**

> 未移植的源断言（均因 main-v2 语义缺失，非脚本缺陷）：枚举选项越界、数值范围校验（main-v2 预检查无此两类规则）；
> `publishedRowCount`/`published=false` 草稿计数（main-v2 数据行无 published 布尔位，草稿即 `config_staging_rows`）；
> AI `ui_event`/`confirm_tool` 帧名与 `/api/ai/tools`、`/api/ai/sessions/count`、`/api/ai/clear` 端点（main-v2 无）。

## 3. Q8 四修复的适配说明与实测

Q8 出处：`<MAIN_V2>/docs/evidence/核验衍生问题清单.md`（Q8 行）+ `<MAIN_V2>/docs/03-技术方案文档-v2.1.md` §12.4
「移植期缺陷清单（Q8 逐条修复）」。

| Q8 项 | 原始问题（V3 核验） | 移植时的适配与实测 | 结果 |
|---|---|---|---|
| ① | 未上传文件的导入批次写 issues 明细抛 `NoSuchFileException`（`ImportService.writeIssuesDetail` 未建目录），稳定 2 次 WARN | main-v2 的架构上**明细只落库**（`validation_issues` 表，经 `GET /api/jobs/{id}/issues` 读取），无"按文件写明细"的落点 → **缺陷不适用**。脚本把它变成可判定断言（TC10 + Q8 项回归）：日志无 `NoSuchFileException`/`明细写入失败`，且未上传文件批次的明细可经 REST 查得 | **通过**（日志 0 命中；明细可查） |
| ② | `GlobalExceptionHandler` 对 SSE 请求二次写 JSON（`HttpMessageNotWritableException: No converter for R with preset Content-Type 'text/event-stream'`，日志噪音） | main-v2 只修了**一半**：`AiController#json()` 给"开流前"的错误分支显式指定 `application/json`（Q2/R4 场景已消除）；但**"向已断开客户端写帧"引发的异常仍会走 `GlobalExceptionHandler#handleGeneral` 并以 `ApiResponse` 二次写 JSON**。脚本将其单列为 Q8 项断言（不计入 22 用例），第 1 轮 **7 次**、第二轮累计 **14 次**（`No converter for xN` / `HttpMessageNotWritableException xN`，两处计数同源），触发栈：`AiController.confirm → ConfirmGate.submitDecision → SseChatEmitter.confirmDecision → emit → sendTo`（客户端已断开时写帧） | **未通过（残留）→ 见 §6【待裁决】1** |
| ③ | 分支 docs 两处仍写"19 个测试组"与实际 22 矛盾 | main-v2 侧无该残留（本文与脚本口径统一为 22，映射表见 §2）；源分支按要求未回修 | **不适用**（口径已在本文与脚本中统一） |
| ④ | `verify-e2e.ps1:173` 的 `base=` 回显为空（PowerShell 把 `+` 之后当额外位置参数） | 移植脚本已修：`Say (" E2E 验证开始  base={0}  tmp={1}" -f $Base, $tmp)`，并额外回显`-BackendLog`路径。两轮输出首屏可见 `base=http://127.0.0.1:18330  tmp=<TMP>/s5b-e2e-…` | **通过**（回显非空） |

Q8 项回归的输出形态（每轮末尾）：

```
--- Q8 项回归（移植期缺陷清单） ---
  [PASS] Q8① 明细落库（未上传文件批次的明细可查，日志无写盘异常）
  [FAIL] Q8② SSE 请求不做二次 JSON 写入 => 日志出现 No converter for x14 / HttpMessageNotWritableException x14（…）
```

计数口径：22 用例计入 `PASS/FAIL` 与**退出码**（有失败即 `exit 1`）；Q8 两项单列计数、**不影响退出码**
（Q8② 属已登记的"日志质量"问题，不宜让既有 CI 门禁因日志噪音变红）。此取舍写在脚本头部注释中。

## 4. 运行环境与两轮结果

### 4.1 前置检查

```
$ netstat -ano | grep 18330          → 无输出（端口空闲）
$ "<MEMURAI_HOME>/memurai-cli.exe" ping → PONG
$ curl -s http://127.0.0.1:18330/api/ai/health | head -c 200
{"available":true,"code":"AI_AVAILABLE","model":"deepseek-flash","baseUrl":"https://api.deepseek.com",
 "memoryBackend":"RedisChatMemoryRepository","sessionTtl":"PT6H","confirmTimeoutSeconds":120,…}
```

启动日志（尾部）：`GLM 种子并集移植完成：新增 8 个定义` → `启动自动续跑完成：候选 65 轮，受理 0 轮，跳过 65 轮`
（候选来自 **Redis 中其他会话遗留的 run 快照**，均因"无待决/已终态"被跳过，不影响本验证）。

### 4.2 两轮结果

| 轮次 | 命令 | 退出码 | 末尾原文 | 耗时 |
|---|---|---|---|---|
| 第 1 轮 | `powershell.exe -File scripts/verify-e2e.ps1 -Base http://127.0.0.1:18330 -BackendLog <TMP>/boot6.log` | **0** | `结果：PASS=22  FAIL=0` | 72 s |
| 第 2 轮（幂等重跑） | 同上（同一后端实例、同一库文件） | **0** | `结果：PASS=22  FAIL=0` | 68 s |

### 4.3 第 1 轮逐用例输出（`<TMP>/runA.txt` 全文 56 行照录，仅把临时目录名与日志路径替换为占位符）

```
============================================================
 E2E 验证开始  base=http://127.0.0.1:18330  tmp=<TMP>/s5b-e2e-…
 后端日志：<TMP>/boot6.log
============================================================
--- 预清理：删除残留任务 0 个，E2E_TEMP 定义 0 个 ---
--- TC1 配置定义列表与种子数据 ---
  [PASS] TC1 15 个定义（基座 7 + glm 8）/层级/引用字段/发布行数/过滤正确
--- TC2 动态配置定义 CRUD ---
  [PASS] TC2 定义创建/重复编码拦截(HTTP 500)/字段动态追加/无主键拒绝
--- TC3 数据校验引擎（预检查四类规则） ---
  [PASS] TC3 四类规则（必填/主键重复/引用不存在/范围必填）全部命中且给出明细
--- TC4 导出任务（全量 + 进度 + 文件） ---
  [PASS] TC4 全量导出 3 文件 + 行数/进度正确 + 文件可下载（PK 魔数）
--- TC5 导出查询条件（字段条件 + 范围条件） ---
  [PASS] TC5 字段条件(code=CNY→1行)与范围条件(HE→3行、HE+XN→10行)正确，3 行子集件已保存
--- TC6 打包下载（勾选 + 全量） ---
  [PASS] TC6 勾选打包（2 条目）与全量打包（3 条目）均正确
--- TC7 导入模板下载 ---
  [PASS] TC7 模板：单个 xlsx + 多配置 zip + 定义级模板下载正常
--- TC8 上传文件名匹配 ---
  [PASS] TC8 zip 匹配/未匹配报告/前缀命名/非法命名拒绝均正确（序号后缀语义见映射说明）
--- TC9 检查通过 + 依赖拓扑 ---
  [PASS] TC9 检查全通过 + 依赖拓扑（METRIC_DICT 先于 ALARM_THRESHOLD）+ 条目状态 CHECKED
--- TC10 检查失败明细 + 未上传文件 ---
  [PASS] TC10 检查失败明细可查（分页/总数/归属正确）+ 未上传文件报错 + 日志无写盘异常
--- TC11 SSE 进度事件流 ---
  [PASS] TC11 收到进度事件流 12 条（末条 120/120）+ JOB_DONE(COMPLETED)
--- TC12 导入草稿隔离 ---
  [PASS] TC12 导入写暂存（3 行 STAGED），生效数据保持 5 行不变
--- TC13 发布：范围替换 + 幂等 + 终态冲突 ---
  [PASS] TC13 范围替换（5→3 行、upsert 保 id 递增版本、差集删除）+ 快照冲突拦截重复发布 + 409 JOB_ALREADY_FINAL
--- TC14 发布前置守卫（无文件 / 空暂存） ---
  [PASS] TC14 无文件导入 FAILED（明细可查）+ REPLACE 空暂存发布不误删（28 行不变，发布终态 COMPLETED）
--- TC20 任务中心（列表/持久化/过滤/转义） ---
  [PASS] TC20 统一列表/创建即持久化/类型与状态过滤/关键词转义（% 字符 → 0 命中）正确
--- TC21 任务分页 ---
  [PASS] TC21 任务分页正确（total=13，第 1 页 5 条 / 第 2 页 5 条，无重复）
--- TC15 AI 对话（流式 / 工具 / 渐进披露） ---
  [PASS] TC15 流式+工具调用可见+渐进披露：page:tasks 12 个工具（无 start_publish）；task:IMPORT/PUBLISH 含 start_publish
--- TC16 AI 工具驱动工作区（确认门 + 前端工具挂起） ---
  [PASS] TC16 确认放行→真实创建导出作业并产出文件；前端工具挂起+回灌续跑（navigate_to,select_definitions）
--- TC17 HITL：发布需确认（拒绝/确认） ---
  [PASS] TC17 拒绝不执行（暂存仍 STAGED、版本零变化、409 重复决策拦截）+ 确认后真实发布（PUBLISH COMPLETED）
--- TC18 会话隔离 / 409 串行化 / 取消释放 ---
  [PASS] TC18 会话记忆隔离 + 409 SESSION_BUSY(携 runId/reattach) + 取消后释放
--- TC22 AI 历史恢复 ---
  [PASS] TC22 历史恢复：6 条（USER 2 / ASSISTANT 3，含工具卡片）+ 未知会话返回空
--- TC19 清理与演示数据恢复 ---
  [PASS] TC19 清理完成（定义删除 404、任务删除级联作业 404）+ 演示数据恢复 5 行
--- Q8 项回归（移植期缺陷清单） ---
  [PASS] Q8① 明细落库（未上传文件批次的明细可查，日志无写盘异常）
  [FAIL] Q8② SSE 请求不做二次 JSON 写入 => 日志出现 No converter for x7 / HttpMessageNotWritableException x7（客户端断开 SSE 时 GlobalExceptionHandler 仍以 ApiResponse 写回，内容类型已固定为 text/event-stream）
============================================================
 结果：PASS=22  FAIL=0  （源脚本 22 用例 → 本脚本 22 用例）
 Q8 项：PASS=1  FAIL=1  （不计入 22 用例，见 docs/evidence/S5b-deepseek-E2E移植验证.md）
============================================================
```

第 2 轮输出与第 1 轮 `diff` **仅一处不同**：Q8② 的累计计数（`x7` → `x14`，同一日志文件跨轮累加）；
其余 55 行逐字一致（含 TC21 `total=13`、TC22 历史 6 条）。
数据面终态（两轮结束后）：`tasks total=0`、`defs=15`、`CURRENCY count=5`（清理与恢复到位）。

### 4.4 真实模型参与的证据（AI 用例非桩）

- `TC15` 断言 `tool_start`/`tool_result` 帧中的 `list_config_defs` 与 `done` 均实测到达（含 `delta` 正文帧）；
- `TC16` 放行确认门后**真实产出导出文件**（`GET /api/tasks/{id}/files` 出现 `EXPORT` 文件），
  且前端工具（`navigate_to`、`select_definitions`）以 `frontend_tool_request` 挂起、`executed=false`，回灌后经 reattach 续跑；
- `TC17` 确认后 PUBLISH 作业 `COMPLETED` 且暂存行提升为 `PUBLISHED`；
- `TC22` 历史中出现 `ASSISTANT.toolCalls` 工具卡片。

## 5. 构建与回归

| 项 | 命令 | 结果 |
|---|---|---|
| 后端构建 | `<MAVEN_HOME>/mvn.cmd -B -DskipTests package` | `BUILD SUCCESS`，`target/config-mgr.jar` 95 390 240 字节 |
| 单元测试 | 本轮未跑（S5a 已入库 105 全绿；本棒**未改生产代码**，无回归面） | 【未触发】 |
| E2E 脚本语法 | `[Parser]::ParseFile` | `PARSE OK` |

## 6. 问题与【待裁决】

### 6.1 【待裁决】1：Q8② 残留——向已断开客户端写帧仍触发全局处理器的二次写 JSON

- **现象**：日志每轮 ≈7 次 `HttpMessageNotWritableException: No converter for [class com.example.configmgr.common.ApiResponse] with preset Content-Type 'text/event-stream'`（第 1 轮 7 次，第二轮日志累计 14 次）。
- **触发链（实测栈）**：`AiController.confirm` → `ConfirmGate.submitDecision` → `SseChatEmitter.confirmDecision` → `emit` → `sendTo`（写帧时客户端已断开）→ 异常冒泡 → `GlobalExceptionHandler#handleGeneral` → 返回 `ApiResponse`，而响应内容类型已固定为 `text/event-stream` → 二次异常。
- **影响面**：日志噪音（与 V3 对源分支的定性一致）；本验证中 **POST `/api/ai/confirm` 仍返回 200 且决策已落库**，无功能失效。
- **本脚本为何会触发**：脚本在收到挂起帧后**按 `StopOn` 主动断开 SSE** 再提交决策（源脚本 TC17 同一手法），于是决策回执帧无处可写；
  生产前端在提交决策时**保持流未断开**，正常路径不会触发——但"客户端已断开后提交决策/回灌结果"（页面跳转后旧页签、断线重连窗口）是真实可达路径。
- **需要生产改动的裁定**（本棒未改 `backend/src/main`，故不做）：
  1. `GlobalExceptionHandler` 对"响应已提交/内容类型为 `text/event-stream`"的请求不做 JSON 写回（或直接记 DEBUG 后返回空）；或
  2. `SseChatEmitter#sendTo` 吞掉"对端已断开"的写失败（与 `TaskSseService#publish` 现状一致）。
- **裁决建议**：属日志质量问题，可与 S5 收尾一并定（二选一即可）；若裁决"接受现状"，请把 §12.4 的 Q8② 描述改为"部分修复（仅开流前分支）"。

### 6.2 【待裁决】2：重复编码创建定义返回 500 且回显原始 SQL

```
POST /api/definitions  {"code":"CURRENCY", …}   → HTTP 500
{"success":false,"message":"服务器内部错误: could not execute statement [Unique index or primary key violation: … insert into config_definitions … ] [23505-232]"}
```

- 现状：唯一约束命中 → `DataIntegrityViolationException` 落到 `handleGeneral` ⇒ **500 + 原始 JDBC 报文**（含 SQL 片段）。
- 移植影响：TC2 的"重复编码拦截"只能断言"必须被拒绝（HTTP 非 2xx）"，并把实测状态码写进 PASS 文案（当前 500）。
- 建议：改为 409 + 机器可读码（如 `DEFINITION_CODE_EXISTS` + 友好文案），与 `DefinitionService.delete` 的 409 口径一致。
  本棒未改生产代码，故保留现状待裁决。

### 6.3 【待裁决】3：源 TC14 的"检查失败 → 禁止导入/发布"在 main-v2 无守卫

- 实测：预检查失败（`PRECHECK` FAILED + 明细）后，同任务仍可 `IMPORT` 写暂存、可 `PUBLISH`（若暂存非空即会发布）。
- `ImportFlowJobTest` 类注释已把该点登记为"实现里没有守卫，未移植（见证据文档【待裁决】）"。
- 本棒处置：把 TC14 改写为**可验证的两层守卫**（无文件导入 FAILED + REPLACE 空暂存不误删），未新增守卫逻辑。
- 需裁定：产品是否要求"预检查未通过不得进入导入/发布"？若要求，属生产改动（作业执行器前置校验），请指派。

### 6.4 【待裁决】4：上传文件名不再支持 `(1)` 序号后缀

- 源脚本 TC8 断言 `SERVER_PARAM(1).xlsx` 可被识别为序号后缀；main-v2 `FileController#matchDefCode` 只认
  `编码` / `编码_…` / `编码-…`，`CURRENCY(1).xlsx` 被拒（400，提示"请使用'编码_xxx.xlsx'命名"）。
- 本棒处置：按 main-v2 现状断言为"被拒绝且提示可读"。
- 需裁定：是否为产品意图（浏览器重复下载产生的 `(1)` 后缀是否要给容错解析）。

### 6.5 【待裁决】5：REPLACE 模式下"空暂存集发布"返回 COMPLETED（0 行）

- 实测（TC14）：任务在 REPLACE 模式下、暂存集为空时发起 PUBLISH → 作业终态 `COMPLETED`、`progress=0/0`、生效数据零变化
  （`ConfigDataService.deleteRowsOutOfRange` 只遍历"暂存集中出现过的范围键"，空集即不删任何行——安全）。
- 本棒处置：断言该**安全不变量**（生效数据零变化），不把它当失败。
- 需裁定：空集发布是否应判失败/告警（语义上"什么都没发布"却报成功）；若要求，属生产改动（作业执行器前置校验）。

### 6.6 需在 CI 稳定化时注意（非缺陷）

1. **TC15 的披露断言依赖后端 DEBUG 日志**（`-BackendLog`）。若要让 CI 无日志依赖，建议增设只读端点
   （如 `GET /api/ai/tools?page=&taskType=&step=`，返回当前上下文披露的工具名），或让 `/api/ai/health` 携带最近一轮的披露清单。
2. **TC11 依赖 `--app.job.batch-size=10`**（分片边界才产 `JOB_PROGRESS`）；默认 100 时该用例会以明确文案失败。
3. **AI 用例由真实模型驱动**：TC15 对首轮"未调用 `list_config_defs`"做了一次重试；TC17 的确认轮允许最多 3 次尝试（并换新会话）；
   TC15/TC16/TC17/TC18 的挂起处理器一律"回灌并继续"——不因模型先挂起在无关工具（`navigate_to`/`confirm_step` 等 FRONTEND 通道工具，
   同样以 `frontend_tool_request` 挂起）上就提前收摊。开发期间确实观测到该抖动会让 TC17 误判一次，已按上述方式处置；
   实测两轮均一次成功。若换模型/降级模型，建议按 S5 的"模型抖动"策略改为录制回放。

## 7. 本棒未做 / 遗留

- 未改 `backend/src/main/**`、未改前端、未改 `docs/` 既有文件（仅新增本文与 `<MAIN_V2>/scripts/verify-e2e.ps1`）。
- 未做任何 git 写操作（无 commit/push/add）。
- 未移植源脚本中依赖 deepseek 专有语义的断言（枚举/数值校验、`published` 布尔位、`ui_event`/`confirm_tool` 帧名、
  `/api/ai/tools`、`/api/ai/sessions/count`、`/api/ai/clear`），理由逐条列在 §2 表下注。
- 未跑 `mvn test`（本棒无生产代码改动；S5a 的 105 例基线不在本棒范围）。

## 8. 收尾确认

- 本棒启动的后端进程（`java -jar target/config-mgr.jar --server.port=18330`，PID 见 `<TMP>/boot6.log` 的 `INFO … [main]` 行）
  在验证结束后已 `taskkill /F` 停止；`netstat -ano | grep 18330` 无 LISTENING。
- 未停止任何非本棒启动的进程；未占用 18080 / 18290–18299 / 18301–18321。
- 独立 H2 库文件（`<MAIN_V2>/backend/data/e2e_s5b_db.mv.db`）与 `data/files/**` 均为运行产物，被 `.gitignore`（`*.mv.db`、`backend/data/`）覆盖；
  工作区新增文件两个：`<MAIN_V2>/scripts/verify-e2e.ps1`、`<MAIN_V2>/docs/evidence/S5b-deepseek-E2E移植验证.md`（`git status --porcelain` 实测两条 `??`）。
- 脱敏自查：本文与脚本内**无 key**（`sk-` / `AI_API_KEY=<值>` 均 0 命中）、**无本机绝对路径**（一律占位符）。

## 裁决结论（指挥官 K3，2026-10-07）

| # | 事项 | 裁决 |
|---|------|------|
| 1 | Q8② SSE 二次写残留（向已断开客户端写帧触发全局处理器二次写 JSON） | 采纳修复——全局异常处理器识别已提交的 text/event-stream 响应不再二次写，列入 M1 收尾棒 |
| 2 | 重复编码创建定义返回 500+原始 SQL 报文 | 采纳——改 409+机器可读码，同棒实施 |
| 3 | 预检查失败无导入/发布守卫 | 与 S5.a 待裁决#1 重复，维持原裁决（采纳），同棒 |
| 4 | 上传文件名不再支持 (1) 序号后缀 | 不采纳恢复——精确匹配更严格、消除歧义，保持现行为并在文档写明 |
| 5 | REPLACE 空暂存发布 COMPLETED(0 行) 是否判失败 | 不采纳——空发布是无害空操作，"不误删"安全不变量为正确断言，建议前端/AI 层提示"暂存为空" |

另采纳 CI 稳定化建议：补只读 /api/ai/tools 端点（替代 DEBUG 日志断言），同棒实施。M1 收尾棒扩编为：守卫 3 项（预检查阻断/导出互斥 409/未知操作符抛异常）+ Q8② SSE 二次写修复 + 重复编码 409 + 只读 tools 端点。
