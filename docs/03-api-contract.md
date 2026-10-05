# API 契约（前后端联调唯一事实来源）

版本：v1.0　日期：2026-10-05
统一约定：REST 返回 JSON；错误返回 `{error: 消息}` + 非 200 状态码；时间格式 ISO-8601；所有列表/详情字段名 camelCase。

## 1. 配置项元数据与数据

### GET /api/config-defs
→ `[{code, name, level, description, fields, dependsOn, rowCount}]`
- `fields: [{name, label, type, required, options?, maxLength?, ref?}]`，`ref: {def, field}`
- `rowCount`：正式区数据行数

### GET /api/config-defs/{code}
→ 单个定义（结构同上，无 rowCount 可加）

### POST /api/config-defs/query
请求：`{code, conditions?: [{field, op, value, value2?}], page?: 0, size?: 50}`
- op ∈ `EQ NE LIKE GT GE LT LE BETWEEN IN`；IN 的 value 为数组；BETWEEN 用 value+value2
→ `{total, rows: [{rowNo, data: {...}}]}`

### GET /api/config-defs/{code}/template-info
→ `{code, fileName: "<code>.xlsx", fields}`（前端据此生成模板，文件不落后端）

## 2. 任务

### POST /api/tasks
请求：`{type: "EXPORT"|"IMPORT", name}`
→ Task：`{id, taskNo, type, name, status, currentStep, stepData, progress, message, createdAt, updatedAt}`
- 初始 status=DRAFT、currentStep=1、stepData=`{}`

### GET /api/tasks
→ Task 数组，按创建时间倒序。

### GET /api/tasks/{id} → Task

### PUT /api/tasks/{id}/step-data
请求：`{currentStep, stepData}`（整体替换 stepData；stepData 内容前端自定，建议 `{selectedDefs:[code...], queryConditions:{code:[cond...]}}`）
→ Task

### POST /api/tasks/{id}/cancel → Task（status=CANCELLED；若有 RUNNING 作业则取消）
### DELETE /api/tasks/{id} → 204（同时删除关联作业、暂存、导出结果）

## 3. 作业（导出/检查/导入/发布）

通用响应 JobRun：
```json
{"id":1,"taskId":1,"kind":"EXPORT","status":"RUNNING","total":3,"processed":1,
 "currentItem":"REGION_GROUP","result":null,"error":null,
 "createdAt":"...","startedAt":"...","finishedAt":null,
 "items":[{"defCode":"COUNTRY","seq":1,"status":"SUCCESS","totalRows":10,"okRows":10,"errorRows":0,"message":null,"detail":null}]}
```
- status：PENDING/RUNNING/SUCCESS/FAILED/CANCELLED；item status 另有 SKIPPED
- `detail`：检查/导入/发布时为逐行错误 `[{rowNo, field, message}]`（≤100 条）；导出时为 null
- 重复提交：同任务同 kind 有 PENDING/RUNNING 作业时，直接返回该作业（HTTP 200），不新建

### POST /api/tasks/{id}/jobs/export
请求：`{items: [{defCode, conditions?: [...]}]}`（conditions 结构同查询）
- 行为：逐配置项按条件查询正式区，写入 ExportResult；任务 status→IN_PROGRESS，完成后→COMPLETED，progress=100
→ JobRun

### GET /api/jobs/{jobId} → JobRun（含 items，前端 1s 轮询）
### POST /api/jobs/{jobId}/cancel → JobRun（轮次边界生效）

### GET /api/jobs/{jobId}/export-results
→ `[{defCode, fileName: "<code>.xlsx", rowCount, fields, rows: [{...字段: 值}]}]`（前端据此渲染 SpreadJS / 生成 xlsx / zip）

### POST /api/tasks/{id}/jobs/check
请求：`{items: [{defCode, rows: [{...字段: 值}]}]}`
- 行为：按拓扑序逐配置项校验（必填/类型/枚举/ref，ref 参考正式区 + 本作业前序配置项的数据）；不写库
→ JobRun（items[].detail 为逐行错误）

### POST /api/tasks/{id}/jobs/import
请求：同 check
- 行为：再次校验 → 通过的配置项写入暂存区（先清该 task+def 旧暂存）；有错误的配置项 FAILED，其下游依赖 SKIPPED；完成后任务 currentStep 不变（前端推进）
→ JobRun

### POST /api/tasks/{id}/jobs/publish
请求：`{}`（数据来自该任务暂存区）
- 行为：按拓扑序逐配置项：再次校验（ref 参考正式区 + 本作业已发布配置项的新数据）→ 正式区全量替换；全部成功后任务 status=COMPLETED、progress=100
→ JobRun

### GET /api/tasks/{id}/staging
→ `[{defCode, rowCount, rows: [{rowNo, data}]}]`（发布前核查用）

## 4. AI 对话（SSE）

两个端点均 `POST`，响应 `Content-Type: text/event-stream`，事件格式 `event: <名>\ndata: <json>\n\n`：

| event | data | 说明 |
|---|---|---|
| token | `{text}` | 正文增量 |
| reasoning | `{text}` | 思维链增量（可忽略） |
| tool_run | `{name, status: "ok"|"error"}` | 后端工具已执行（展示用） |
| tool_call | `{callId, name, arguments, needConfirm}` | 前端工具待执行（前端工具才会出现；一次只会有一个未决 tool_call） |
| done | `{status: "finished"|"waiting"|"cancelled"|"max_rounds"}` | 流结束；waiting=等待 tool-result |
| error | `{message}` | 失败 |

### POST /api/ai/chat
```json
{"sessionId":"uuid","message":"用户输入",
 "context":{"page":"TASKS|EXPORT|IMPORT","step":1,"taskId":null,"selectedDefs":[],"dataVersion":0}}
```

### POST /api/ai/tool-result
```json
{"sessionId":"uuid","callId":"call_xxx",
 "result":{"success":true,"data":{}} ,
 "context":{同上}}
```
- 前端工具被拒绝/失败时也回灌：`{success:false, error:"用户拒绝执行"}`

### GET /api/ai/history?sessionId=xxx
→ `{messages: [{role: "user"|"assistant", content, toolName?}], pendingCall: {callId, name, arguments, needConfirm} | null}`（内存会话，无则返回空 messages）

### POST /api/ai/cancel `{sessionId}` → `{ok:true}`

## 5. AI 工具清单（后端注册表唯一事实来源）

页面取值：`TASKS`（任务列表）、`EXPORT`（导出向导）、`IMPORT`（导入向导）。`steps` 表示仅在该向导步骤可用；未标则该页面全步骤可用。

### 后端工具（Loop 内执行，前端只见 tool_run 事件）
| name | 参数 | 说明 | 页面 |
|---|---|---|---|
| list_config_defs | `{level?}` | 配置项列表（code/name/level/依赖/行数） | 全部 |
| get_config_def | `{code}` | 单个配置项完整字段定义 | 全部 |
| count_config_data | `{code, conditions?}` | 按条件统计正式区行数 | 全部 |
| list_tasks | `{type?, status?}` | 任务列表（≤20 条摘要） | 全部 |
| get_task | `{taskId}` | 任务详情+最近作业状态 | 全部 |
| create_task | `{type, name}` | 创建任务，返回 {taskId, taskNo} | 全部 |

### 前端工具 — 自动执行（needConfirm=false）
| name | 参数 | 说明 | 页面/步骤 |
|---|---|---|---|
| navigate_to | `{page, taskId?}` | 跳转页面（EXPORT/IMPORT 需带 taskId） | 全部 |
| get_workspace_state | `{}` | 获取当前页面/任务/步骤/已选配置项/条件快照 | 全部 |
| select_config_defs | `{codes, mode:"REPLACE"|"ADD"}` | 在向导第 1 步勾选配置项 | EXPORT/IMPORT 第1步 |
| set_query_conditions | `{defCode, conditions}` | 设置某配置项查询条件 | EXPORT 第2步 |
| download_templates | `{codes}` | 下载模板（1 个=xlsx，多个=zip） | IMPORT 第1步 |
| download_export_files | `{codes?}` | 下载导出文件（缺省全部；1 个=xlsx，多个=zip） | EXPORT 第3步 |

### 前端工具 — 需用户确认（needConfirm=true）
| name | 参数 | 说明 | 页面/步骤 |
|---|---|---|---|
| start_export | `{}` | 用当前选择与条件启动导出作业 | EXPORT 第2、3步 |
| start_check | `{}` | 启动预检查作业（数据来自当前编辑区） | IMPORT 第2步 |
| start_import | `{}` | 启动导入作业 | IMPORT 第3步 |
| start_publish | `{}` | 启动发布作业 | IMPORT 第4步 |

## 6. 种子数据（启动播种，H2 为空时）

| code | name | level | 关键字段 | dependsOn |
|---|---|---|---|---|
| COUNTRY | 国家字典 | GLOBAL | code*(STRING), name*, currency*(ENUM: CNY/USD/EUR/JPY) | - |
| REGION_GROUP | 区域分组 | REGION | code*, name*, countryCode*(ref COUNTRY.code) | COUNTRY |
| PROJECT_TYPE | 项目类型 | GLOBAL | code*, name*, enabled(BOOLEAN) | - |
| PROJECT_INFO | 项目信息 | PROJECT | code*, name*, typeCode*(ref PROJECT_TYPE.code), regionCode*(ref REGION_GROUP.code), budget(NUMBER), startDate(DATE) | PROJECT_TYPE, REGION_GROUP |
| TAX_RATE | 税率配置 | REGION | countryCode*(ref COUNTRY.code), taxType*(ENUM: VAT/CIT/CT), rate*(NUMBER) | COUNTRY |
| UNIT_CONVERSION | 单位换算 | GLOBAL | fromUnit*, toUnit*, factor*(NUMBER) | - |

每个配置项播种 8~30 行数据。

## 7. 端口与代理
- 后端端口：`8090`（启动前检测占用，占用则顺延 8091…）
- 前端端口：`5271`（vite strictPort，占用则顺延）；vite 代理 `/api` → 后端
