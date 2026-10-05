# 技术方案文档

**项目**：ai-example-claude-opus-5.5  
**版本**：v1.0  
**日期**：2026-10-05

---

## 1. 整体架构

### 1.1 分层架构图

```
┌───────────────────────────────────────────────────────────────────────┐
│                          浏览器 (Vue3 SPA)                              │
│                                                                         │
│  ┌─────────────────────────────┐  ┌───────────────────────────────────┐│
│  │     业务工作区 (65%)          │  │       AI 对话栏 (35%)              ││
│  │  router-view (路由视图)       │  │  AiPanel.vue (常驻，不随路由销毁)  ││
│  │  - TaskListView              │  │  - 消息列表 + 工具执行进度          ││
│  │  - ExportWizardView          │  │  - HITL 确认卡片                   ││
│  │  - ImportWizardView          │  │  - 快捷建议按钮                    ││
│  │  - DefinitionsView           │  │  - 上下文芯片（当前AI视野）          ││
│  │  - DataBrowserView           │  │                                   ││
│  └──────────────┬──────────────┘  └──────────────────┬────────────────┘│
│                 │  axios                              │ fetch (SSE)     │
└─────────────────┼─────────────────────────────────────┼───────────────┘
                  │                                     │
                  ▼                                     ▼
┌───────────────────────────────────────────────────────────────────────┐
│                    后端 (Spring Boot 3.5.14 / JDK 21)                   │
│                                                                         │
│  REST Controllers                  AI Module                           │
│  ┌──────────────┐  ┌───────────┐  ┌─────────────────────────────────┐  │
│  │DefinitionCtrl│  │ TaskCtrl  │  │ AiController                     │  │
│  │ DataCtrl     │  │ JobCtrl   │  │  POST /api/ai/sessions           │  │
│  │ MasterDataCtrl│  │ FileCtrl  │  │  PUT  /api/ai/sessions/{id}/ctx  │  │
│  └──────┬───────┘  └─────┬─────┘  │  GET  /api/ai/sessions/{id}/run  │  │
│         │                │        │  POST /api/ai/sessions/{id}/msg  │  │
│         ▼                ▼        └──────────────────┬──────────────┘  │
│  ┌──────────────────────────┐                        │                  │
│  │  Domain Services          │   ┌─────────────────────────────────────┐│
│  │  DefinitionService        │   │  AgentRuntime                        ││
│  │  ConfigDataService        │◄──│  - 自管工具循环 (虚拟线程)             ││
│  │  TaskService              │   │  - ToolRegistry (步骤→工具集)         ││
│  │  JobService               │   │  - ContextBuilder (工作区快照)        ││
│  │  ExcelService             │   │  - SseRunEmitter (AG-UI 协议)         ││
│  │  FileStorageService       │   │  - HITL (toolCallId→等待→回填)        ││
│  └──────────┬───────────────┘   └─────────────────────────────────────┘│
│             │                                                            │
│             ▼                                                            │
│  ┌──────────────────┐  ┌─────────────────────────────────────────────┐  │
│  │  H2 File DB       │  │  Caffeine Session Store (TTL 30min)          │  │
│  │  (Flyway schema)  │  │  FileSystem (backend/data/files/)            │  │
│  └──────────────────┘  └─────────────────────────────────────────────┘  │
└───────────────────────────────────────────────────────────────────────┘
                                   │ OpenAI 兼容 API
                                   ▼
                        deepseek-flash (api.deepseek.com)
```

---

## 2. 后端设计

### 2.1 包结构

```
com.example.configmgr
├── common/              # 通用：ApiResponse, GlobalExceptionHandler
├── config/              # Spring 配置：CORS, Async, Jackson
├── definition/          # 配置定义：entity, repo, service, controller
├── masterdata/          # 主数据：Region, Project
├── data/                # 配置数据：live rows + staging rows
├── task/                # 任务：task entity, task item, state machine
├── job/                 # 异步作业：JobService, ExportJob, ImportJob, etc.
├── excel/               # Excel：PoiExcelWriter, ExcelTemplateBuilder, ExcelReader
├── file/                # 文件存储：FileStorageService
├── ai/                  # AI 模块（详见 §2.5）
└── ConfigMgrApplication
```

### 2.2 数据模型

#### 核心实体关系

```
config_definitions (1) ──< config_fields (N)
config_definitions (1) ──< config_dependencies (N) >── config_definitions
config_definitions (1) ──< config_data_rows (N)
config_definitions (1) ──< config_staging_rows (N)

tasks (1) ──< task_items (N) ──< task_files (N)
tasks (1) ──< jobs (N) ──< job_items (N)
jobs (1) ──< validation_issues (N)
```

#### 关键表说明

| 表 | 说明 |
|---|---|
| `config_definitions` | 配置定义（code, name, level, desc） |
| `config_fields` | 字段定义（code, label, type, required, is_key, options_json, ref_def_code, ref_field_code） |
| `config_dependencies` | 定义间依赖关系（from_code → to_code） |
| `config_data_rows` | 已发布配置行（def_code, scope_type, scope_key, row_key, data_json, version） |
| `config_staging_rows` | 暂存行（task_id, def_code, op_type, row_key, data_json, status） |
| `tasks` | 任务（type, title, current_step, status, settings_json, version） |
| `task_items` | 任务中的配置条目（task_id, def_code, status, file_id） |
| `task_files` | 任务文件（task_id, def_code, file_type, storage_path, row_count） |
| `jobs` | 异步作业（task_id, job_type, status, progress, total, result_json） |
| `job_items` | 作业项进度（job_id, def_code, status, processed, total） |
| `validation_issues` | 校验问题（job_id, def_code, row_key, field_code, severity, message） |
| `regions` | 地区主数据（code, name） |
| `projects` | 项目主数据（code, name, region_code） |

### 2.3 任务状态机

#### 导出任务步骤

```
SELECT_DEFS → QUERY_COND → EXPORT
```

#### 导入任务步骤

```
UPLOAD → PRECHECK → IMPORT → PUBLISH
```

步骤转换规则（guard）：
- `SELECT_DEFS → QUERY_COND`：至少选择 1 个配置项
- `QUERY_COND → EXPORT`：无限制（条件可为空）
- `UPLOAD → PRECHECK`：所有选中配置项均已关联文件
- `PRECHECK → IMPORT`：最新预检通过（无 ERROR 级别问题）
- `IMPORT → PUBLISH`：所有暂存行状态为 STAGED
- `PUBLISH → 完成`：发布成功

### 2.4 Excel 处理方案

#### 模板生成（Apache POI SXSSFWorkbook）
- 工作表名称：配置定义中文名
- 第 0 行：表头（字段标签，必填字段加 `*`，加批注说明类型/选项）
- 第 1 行起：示例数据（可选）
- 隐藏工作表 `_dict`：长选项列表（>20 项）
- 隐藏工作表 `_meta`：`version`, `def_code`, 列字段编码

#### 数据导出
- 使用 SXSSFWorkbook（内存 100 行刷盘）
- 按 100 行一批写入，每批触发进度事件

#### Excel 读取（POI SAX 流式）
- `XSSFReader` + `XSSFSheetXMLHandler`
- 先读 `_meta` 工作表获取字段映射，再读数据工作表
- 降级：若无 `_meta` 工作表，按表头文字匹配字段

### 2.5 AI 模块设计

#### 会话生命周期
```
前端（sessionStorage: sid）
  │ POST /api/ai/sessions → 201 {sid}
  │
  ├── PUT /api/ai/sessions/{sid}/context  (每次路由变化时上报)
  │    body: {page, taskId, taskType, step, extra}
  │
  └── GET /api/ai/sessions/{sid}/stream  (SSE 长连接)
       event types: SESSION_SNAPSHOT, TEXT_DELTA, TOOL_START, TOOL_DONE,
                    INTERACTION_REQUEST, UI_COMMAND, JOB_PROGRESS, ERROR, HEARTBEAT
```

#### AgentRuntime 主循环（虚拟线程）

```java
// 伪代码
void runAgentLoop(RunInput input, SseEmitter emitter) {
    emit(RUN_STARTED)
    List<Message> history = session.getHistory()
    // 注入当前上下文快照
    history.add(buildContextSystemMsg(session.getContext()))
    history.add(new UserMessage(input.getMessage()))

    for (int iter = 0; iter < MAX_ITER; iter++) {
        List<ToolCallback> tools = toolRegistry.forContext(session.getContext())
        ChatResponse resp = chatModel.stream(new Prompt(history, opts(tools)))
        // 收集流式 text + toolCalls
        collectStream(resp, emitter)

        if (noToolCalls(resp)) break

        for (ToolCall tc : resp.getToolCalls()) {
            ConfirmSpec cs = toolRegistry.getConfirmSpec(tc)
            if (cs.requiresConfirmation()) {
                // 暂停：推送 INTERACTION_REQUEST，await future
                Result r = suspendForHITL(tc, cs, emitter)
                if (r.isRejected()) appendRejected(history, tc, r); continue
            }
            // 执行工具
            String result = executeTool(tc, session)
            emit(TOOL_DONE, tc.getName(), result)
            history.add(new AssistantMessage(tc))
            history.add(new ToolResponseMessage(tc.id, result))
        }
    }
    session.saveHistory(history)
    emit(RUN_COMPLETED)
}
```

#### 工具渐进式披露

工具按 `@ToolScope` 注解声明可用上下文（支持通配符）：

```java
@ToolScope(pages = {"*"})                      // 全局工具
@ToolScope(pages = {"task"}, taskTypes = {"*"}) // 所有任务页
@ToolScope(pages = {"task"}, taskTypes = {"EXPORT"}, steps = {"SELECT_DEFS"})
```

`ToolRegistry.forContext(ctx)` 在每轮模型调用前动态解析可用工具集。

#### HITL 协议

```
后端：emit INTERACTION_REQUEST {runId, iid, type:CONFIRM, summary, params}
前端：用户点击确认/拒绝 → POST /api/ai/sessions/{sid}/interactions/{iid}
后端：future.complete(result) → 继续循环
超时（5min）：future.completeExceptionally(TimeoutException) → 拒绝
```

#### 状态同步

- 前端路由变化 → `PUT /api/ai/sessions/{sid}/context`
- AI 工具修改业务数据 → `TaskChangedEvent` → SSE Task 流推送版本号
- 前端订阅 `GET /api/tasks/{id}/events` 接收任务更新

---

## 3. 前端设计

### 3.1 路由结构

```
/ → 任务中心 (TaskListView)
/tasks/create → 创建任务
/tasks/:id/export/:step → 导出向导
/tasks/:id/import/:step → 导入向导
/definitions → 配置定义管理
/definitions/:code → 配置定义详情
/data → 配置数据浏览
```

### 3.2 布局组件

```
AppLayout.vue
├── WorkspacePanel.vue (left, 65%)
│   └── <RouterView />
└── AiPanel.vue (right, 35%, keepalive)
```

### 3.3 Pinia Stores

| Store | 职责 |
|-------|------|
| `useWorkspaceStore` | 当前页面上下文（page, taskId, step），上报给后端 |
| `useTaskStore` | 任务数据、任务项、文件、作业状态 |
| `useAiStore` | 会话管理、消息列表、当前 run 状态、HITL 交互 |
| `useDefinitionsStore` | 配置定义缓存 |

### 3.4 事件流（AG-UI 协议）

前端使用 `fetch` + `ReadableStream` 读取 SSE：

```javascript
const reader = response.body.getReader()
// 解析 SSE data 行 → JSON → dispatch 到 AiStore
```

事件类型：
- `SESSION_SNAPSHOT`：恢复对话记录
- `TEXT_DELTA`：流式文本
- `TOOL_START / TOOL_DONE`：工具执行状态
- `INTERACTION_REQUEST`：HITL 卡片
- `UI_COMMAND`：前端动作（navigate, open_editor, download）
- `JOB_PROGRESS`：作业进度
- `ERROR`：错误
- `HEARTBEAT`：保活

### 3.5 SpreadJS 集成

```javascript
// 初始化
const spread = new GC.Spread.Sheets.Workbook(containerEl, {sheetCount: 1})
GC.Spread.Sheets.LicenseKey = import.meta.env.VITE_SPREADJS_KEY || ''

// 加载 Excel（从后端下载）
const io = new GC.Spread.Excel.IO()
io.open(arrayBuffer, (workbook) => spread.fromJSON(workbook))

// 保存 Excel（上传到后端）
io.save(spread.toJSON(), (blob) => uploadBlob(blob), {fileType: GC.Spread.Excel.FileType.excel})
```

---

## 4. API 接口契约

详见 [implementation-plan.md](implementation-plan.md) 中的 REST API 部分。

---

## 5. 关键设计决策

| 决策 | 选择 | 原因 |
|------|------|------|
| AI Runtime 位置 | 后端 | API Key 安全；业务逻辑一致性 |
| 工具循环 | 自管循环（非内置） | 每轮刷新工具集；HITL 暂停；流式逐步输出 |
| DeepSeek thinking | 关闭（extraBody: {thinking:{type:disabled}}） | 带 tools 时避免 reasoning_content 导致的 400 错误 |
| 会话存储 | Caffeine 内存（TTL 30min） | 无需持久化；前端 sessionStorage 页签唯一 |
| 历史裁剪 | 保持 tool_calls/tool message 配对完整 | OpenAI 协议要求配对，断对会 400 |
| 进度推送 | SSE（任务级别 + AI run 级别两条流） | 简单、无状态；与 fetch 兼容 |
| Excel 读写 | Apache POI 5.4（SXSSFWorkbook + SAX 读） | 大文件流式处理；无需外部服务 |
| 数据库迁移 | Flyway（非 ddl-auto:update） | 显式 schema 控制；生产友好 |
| 并发控制 | 乐观锁（@Version）+ 业务守卫 | 轻量；H2 环境足够 |
| 任务取消 | AtomicBoolean cancelled + 检查点 | 简单；与虚拟线程兼容 |
