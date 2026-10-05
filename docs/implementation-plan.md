# 实现方案文档

**项目**：ai-example-claude-opus-5.5  
**版本**：v1.0  
**日期**：2026-10-05

---

## 1. 后端目录结构

```
backend/
├── maven-settings.xml
├── pom.xml
└── src/main/
    ├── java/com/example/configmgr/
    │   ├── ConfigMgrApplication.java
    │   ├── common/
    │   │   ├── ApiResponse.java
    │   │   └── GlobalExceptionHandler.java
    │   ├── config/
    │   │   ├── WebConfig.java          # CORS + Virtual Threads
    │   │   ├── JacksonConfig.java
    │   │   └── AppProperties.java
    │   ├── definition/
    │   │   ├── entity/   ConfigDefinition.java, ConfigField.java
    │   │   ├── repo/     ConfigDefinitionRepository.java, ConfigFieldRepository.java
    │   │   ├── service/  DefinitionService.java, DependencyResolver.java
    │   │   └── controller/  DefinitionController.java
    │   ├── masterdata/
    │   │   ├── entity/   Region.java, Project.java
    │   │   ├── repo/     RegionRepository.java, ProjectRepository.java
    │   │   └── controller/  MasterDataController.java
    │   ├── data/
    │   │   ├── entity/   ConfigDataRow.java, ConfigStagingRow.java
    │   │   ├── repo/     ConfigDataRowRepository.java, ConfigStagingRowRepository.java
    │   │   └── service/  ConfigDataService.java
    │   ├── task/
    │   │   ├── entity/   Task.java, TaskItem.java, TaskFile.java
    │   │   ├── repo/     TaskRepository.java, TaskItemRepository.java, TaskFileRepository.java
    │   │   ├── service/  TaskService.java
    │   │   └── controller/  TaskController.java, FileController.java
    │   ├── job/
    │   │   ├── entity/   Job.java, JobItem.java, ValidationIssue.java
    │   │   ├── repo/     JobRepository.java, JobItemRepository.java, ValidationIssueRepository.java
    │   │   ├── service/  JobService.java, ExportJobRunner.java, PrecheckJobRunner.java
    │   │   │             ImportJobRunner.java, PublishJobRunner.java
    │   │   └── controller/  JobController.java
    │   ├── excel/
    │   │   ├── ExcelTemplateBuilder.java
    │   │   ├── ExcelWriter.java
    │   │   └── ExcelReader.java
    │   ├── file/
    │   │   └── FileStorageService.java
    │   ├── seed/
    │   │   └── DataSeedRunner.java
    │   └── ai/
    │       ├── AiController.java
    │       ├── session/  AiSession.java, AiSessionStore.java
    │       ├── runtime/  AgentRuntime.java, SseRunEmitter.java, AgentRunContext.java
    │       ├── tool/     ToolRegistry.java, ToolScope.java, ToolMeta.java, ToolRisk.java
    │       │             ContextBuilder.java
    │       ├── tools/    GlobalTools.java, TaskTools.java, ExportTools.java
    │       │             ImportTools.java
    │       └── hitl/     HitlManager.java, InteractionRequest.java
    └── resources/
        ├── application.yml
        └── db/migration/   V1__schema.sql, V2__staging_base_version.sql
```

> 注：目录结构与最初规划略有差异——种子数据在 `seed/DataSeedRunner.java`（Java 侧幂等初始化），
> 不需要 V2__seed.sql；V2 迁移实际用于暂存行 base_version 字段；AI 的 DefinitionTools 未单列
> （定义相关能力由 GlobalTools 的 list/get 覆盖，定义编辑走 UI + REST）。

---

## 2. REST API 接口清单

### 2.1 配置定义

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/definitions` | 列出所有定义（支持 level/keyword 过滤） |
| GET | `/api/definitions/{code}` | 获取定义详情（含字段） |
| POST | `/api/definitions` | 创建配置定义 |
| PUT | `/api/definitions/{code}` | 更新配置定义 |
| GET | `/api/definitions/{code}/template` | 下载 Excel 模板 |

### 2.2 主数据

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/master/regions` | 地区列表 |
| GET | `/api/master/projects` | 项目列表（支持 regionCode 过滤）|

### 2.3 配置数据

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/data/{defCode}` | 查询发布数据（支持 scopeType/scopeKey 过滤）|
| GET | `/api/data/{defCode}/count` | 预估行数（支持 `conditions` 参数：QueryCondition JSON）|

### 2.4 任务管理

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/tasks` | 任务列表 |
| POST | `/api/tasks` | 创建任务（type: EXPORT/IMPORT）|
| GET | `/api/tasks/{id}` | 任务详情（含 items）|
| DELETE | `/api/tasks/{id}` | 删除任务 |
| POST | `/api/tasks/{id}/select-defs` | 设置选中的配置定义 |
| PUT | `/api/tasks/{id}/items/{defCode}/condition` | 设置查询条件 |
| PUT | `/api/tasks/{id}/import-mode` | 设置导入模式 |
| GET | `/api/tasks/{id}/events` | SSE：任务事件流 |

### 2.5 文件

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/tasks/{id}/files/upload` | 上传文件（multipart；xlsx 单文件或 zip 批量，自动按文件名匹配配置编码）|
| GET | `/api/tasks/{id}/files/{defCode}` | 下载文件内容（ArrayBuffer，供 SpreadJS 使用）|
| PUT | `/api/tasks/{id}/files/{defCode}` | 保存 SpreadJS 编辑后的文件 |
| GET | `/api/tasks/{id}/files/download` | 下载勾选的导出文件（`codes` 参数，子集打包 zip）|
| GET | `/api/tasks/{id}/files/download-all` | 全部导出文件打包下载 |
| GET | `/api/tasks/{id}/files/templates` | 下载模板（单个：xlsx；多个：zip）|

### 2.6 作业

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/tasks/{id}/jobs` | 创建并启动作业（jobType: EXPORT/PRECHECK/IMPORT/PUBLISH）|
| GET | `/api/jobs/{jobId}` | 作业详情 |
| DELETE | `/api/jobs/{jobId}` | 取消作业 |
| GET | `/api/jobs/{jobId}/issues` | 问题列表 |
| GET | `/api/jobs/{jobId}/diff` | 暂存区 diff（IMPORT 作业完成后）|

### 2.7 AI 会话

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/ai/sessions` | 创建会话 → 201 `{sid}` |
| GET | `/api/ai/sessions/{sid}` | 会话状态（runActive, pendingInteraction, context）|
| DELETE | `/api/ai/sessions/{sid}` | 重置会话（新对话）|
| PUT | `/api/ai/sessions/{sid}/context` | 上报当前上下文（page, taskId, taskType, step, extra）|
| POST | `/api/ai/sessions/{sid}/runs` | 发起 AI run（带用户消息）→ per-run SSE 流 |
| DELETE | `/api/ai/sessions/{sid}/runs/current` | 取消当前 run |
| POST | `/api/ai/sessions/{sid}/interactions/{iid}` | 提交 HITL 响应（approve/reject/reason/data）|
| GET | `/api/ai/health` | AI 健康检查 |

---

## 3. SSE 事件协议（AG-UI 对齐）

```json
// 文本流
{"type": "TEXT_DELTA", "delta": "正在分析..."}

// 工具执行
{"type": "TOOL_START", "toolCallId": "c1", "toolName": "list_config_defs", "title": "查询配置定义列表"}
{"type": "TOOL_DONE", "toolCallId": "c1", "success": true, "summary": "共 9 个配置定义"}

// HITL
{"type": "INTERACTION_REQUEST", "iid": "i1", "interactionType": "CONFIRM",
 "summary": "即将发布 3 个配置项，共 1200 行", "details": "...", "params": {}}

// UI 命令（前端执行）
{"type": "UI_COMMAND", "command": "navigate", "payload": {"route": "/tasks/1/export/export"}}
{"type": "UI_COMMAND", "command": "open_editor", "payload": {"taskId": 1, "defCode": "CURRENCY"}}
{"type": "UI_COMMAND", "command": "download", "payload": {"url": "/api/tasks/1/files/CURRENCY"}}

// 作业进度
{"type": "JOB_PROGRESS", "jobId": 5, "defCode": "CURRENCY", "processed": 50, "total": 200, "pct": 25}

// 运行控制
{"type": "RUN_STARTED", "runId": "r1"}
{"type": "RUN_COMPLETED", "runId": "r1", "usage": {"promptTokens": 1200, "completionTokens": 340}}
{"type": "ERROR", "message": "AI 服务暂时不可用，请稍后重试"}
{"type": "HEARTBEAT"}
```

> 作业进度（JOB_PROGRESS / JOB_DONE）走任务流 `GET /api/tasks/{id}/events`；
> 前端作业进度当前用 1.5s 轮询 `GET /api/jobs/{id}`（与 SSE 并存）。

---

## 4. 前端目录结构

```
frontend/
├── index.html
├── package.json
├── vite.config.js
└── src/
    ├── main.js
    ├── App.vue                    # AppLayout（左右双栏）
    ├── router/index.js
    ├── stores/
    │   ├── workspace.js           # 页面上下文，自动上报
    │   ├── task.js
    │   ├── ai.js                  # 会话、消息、run、HITL
    │   └── definitions.js
    ├── api/
    │   ├── request.js             # axios 实例
    │   ├── definitions.js
    │   ├── masterdata.js
    │   ├── tasks.js
    │   ├── jobs.js
    │   └── ai.js
    ├── components/
    │   ├── AiPanel/
    │   │   ├── AiPanel.vue
    │   │   ├── MessageList.vue
    │   │   ├── ToolCallCard.vue
    │   │   ├── InteractionCard.vue
    │   │   ├── ContextChip.vue
    │   │   └── SuggestionChips.vue
    │   ├── SpreadJSEditor/
    │   │   └── SpreadJSEditor.vue
    │   ├── JobProgress/
    │   │   └── JobProgressPanel.vue
    │   └── common/
    │       ├── ConfirmDialog.vue
    │       └── EmptyState.vue
    ├── views/
    │   ├── TaskListView.vue
    │   ├── export/
    │   │   ├── ExportWizardView.vue
    │   │   ├── StepSelectDefs.vue
    │   │   ├── StepQueryCond.vue
    │   │   └── StepExport.vue
    │   ├── import/
    │   │   ├── ImportWizardView.vue
    │   │   ├── StepUpload.vue
    │   │   ├── StepPrecheck.vue
    │   │   ├── StepImport.vue
    │   │   └── StepPublish.vue
    │   ├── DefinitionsView.vue
    │   └── DataBrowserView.vue
    └── styles/
        ├── variables.scss         # CSS 自定义属性 + SCSS 变量
        └── main.scss
```

---

## 5. 种子数据说明

系统预置以下配置定义（模拟企业业务配置场景）：

| 编码 | 名称 | 层级 | 字段数 | 说明 |
|------|------|------|--------|------|
| `CURRENCY` | 货币字典 | GLOBAL | 4 | 货币代码、名称、汇率、精度 |
| `DOC_TYPE` | 单据类型 | GLOBAL | 4 | 单据编码、名称、分类、是否启用 |
| `APPROVE_ROLE` | 审批角色 | GLOBAL | 3 | 角色编码、名称、描述 |
| `TAX_RATE` | 税率配置 | REGION | 5 | 税种、税率、生效日期、地区（系统字段）|
| `HOLIDAY` | 节假日 | REGION | 4 | 假期名称、日期、类型、地区（系统字段）|
| `PROJ_PARAM` | 项目参数 | PROJECT | 5 | 参数项、参数值、生效范围、项目（系统字段）|
| `PROJ_APPROVE` | 项目审批流 | PROJECT | 6 | 环节编码、审批角色（引用 APPROVE_ROLE）、顺序 |
| `PROJ_WAREHOUSE` | 项目仓库 | PROJECT | 5 | 仓库编码、名称、类型、地址 |
| `PROJ_PRICE` | 项目价格表 | PROJECT | 7 | 商品编码、名称、货币（引用 CURRENCY）、税率码（引用 TAX_RATE） |

地区种子数据：华东、华南、华北、西南（各 2-3 个项目）  
每个配置定义预置 20-5000 行数据（PROJ_PRICE 约 3000 行演示进度）
