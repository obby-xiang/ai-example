> ⚠️ 本文档为 combined 基座分支遗留参考件（描述对象为源分支 v1.0 实现）。如与 docs/adr/ 决策（DC-01~13、DECISION-CARDS）冲突，一律以决策为准（例：本文含虚拟线程相关表述，已被 DC-12 禁用）。

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

#### Excel 读取（Apache POI XSSFWorkbook）
- 先读隐藏 `_meta` 工作表获取列字段映射，再读数据工作表
- 降级：若无 `_meta` 工作表，按表头文字匹配字段（标签/编码双匹配）
- 跳过空行、trim 首尾空格；表头在 Excel 第 1 行，数据从第 2 行开始

#### 上传文件匹配（单文件与 ZIP）
- 单文件：文件名 `编码_xxx.xlsx` / `编码-xxx.xlsx` / 纯 `编码.xlsx`（最长前缀匹配）
- ZIP：解压后逐文件匹配；文件名编码先 UTF-8 解析、出现乱码（U+FFFD）回退 GBK；
  未匹配文件明确报告，不静默挂到第一个配置项
- 上传覆盖：同配置项重复上传覆盖旧文件；在线编辑保存同样覆盖

### 2.5 AI 模块设计

#### 会话生命周期
```
前端（sessionStorage: sid）
  │ POST /api/ai/sessions → 201 {sid}
  │
  ├── PUT /api/ai/sessions/{sid}/context  (每次路由变化时上报)
  │    body: {page, taskId, taskType, step, extra}
  │
  ├── POST /api/ai/sessions/{sid}/runs    (发起一轮对话，返回 per-run SSE 流)
  │    event types: RUN_STARTED, TEXT_DELTA（真流式逐块）,
  │                 TOOL_START, TOOL_DONE,
  │                 INTERACTION_REQUEST（HITL，携带 iid，先推送再阻塞等待）,
  │                 UI_COMMAND（导航/打开编辑器/下载）, ERROR, HEARTBEAT,
  │                 RUN_COMPLETED（携带 usage: promptTokens/completionTokens）
  │
  ├── POST /api/ai/sessions/{sid}/interactions/{iid}  (批准/拒绝 HITL)
  └── DELETE /api/ai/sessions/{sid}/runs/current      (取消本轮)

任务/作业进度走另一条流：
  GET /api/tasks/{id}/events → TASK_CHANGED / JOB_PROGRESS / JOB_DONE
  （后端已实现 SSE 推送；前端作业进度当前采用 1.5s 轮询 GET /api/jobs/{id}，
    二者并存，互不冲突）
```

#### AgentRuntime 主循环（虚拟线程）

约束：能用 Spring AI 的能力就不自研——
- 会话记忆：Spring AI `ChatMemory`（`MessageWindowChatMemory` 窗口裁剪）
- 流式汇总：Spring AI `MessageAggregator`（聚合 text + toolCalls + usage）
- 工具执行：Spring AI `ToolCallingManager`（`DefaultToolCallingManager` +
  `StaticToolCallbackResolver` 装载当前上下文工具；异常转错误响应、对话历史组装由 Spring AI 完成）
- 自研部分仅为 Spring AI 未提供的编排：迭代循环、HITL 人机确认、SSE 事件推送

```java
// 伪代码
void runAgentLoop(RunInput input, SseRunEmitter emitter) {
    emit(RUN_STARTED)
    List<Message> history = session.getMemory().get(sessionId) // Spring AI ChatMemory
    // 注入当前上下文快照
    history.add(buildContextSystemMsg(session.getContext()))
    history.add(new UserMessage(input.getMessage()))

    long promptTokens = 0, completionTokens = 0
    for (int iter = 0; iter < MAX_ITER; iter++) {
        List<ToolCallback> tools = toolRegistry.forContext(session.getContext())
        // 真流式：逐块 emit(TEXT_DELTA)，MessageAggregator 汇总
        StreamResult sr = streamModel(new Prompt(history, opts(tools)), emitter)
        promptTokens += sr.usage().promptTokens; completionTokens += ...

        if (sr.toolCalls().isEmpty()) break
        history.add(sr.assistantMessage())

        ToolCallingManager tcm = DefaultToolCallingManager.builder()
                .toolCallbackResolver(new StaticToolCallbackResolver(tools)).build()
        for (ToolCall tc : sr.toolCalls()) {
            if (riskOf(tc) == DANGER) {
                // 关键顺序：先创建交互并 emit(INTERACTION_REQUEST{iid})，
                // 再阻塞 awaitResponse——否则前端收不到卡片只能超时
                InteractionRequest req = hitl.createInteraction(session, tc)
                emit(INTERACTION_REQUEST, req)
                Result r = hitl.awaitResponse(session, req)
                if (!r.approved) appendRejected(history, tc, r); continue
            }
            // 执行工具（工具可经 AgentRunContext 下发 UI_COMMAND）
            String result = executeTool(tc, session)
            emit(TOOL_DONE, tc.name(), result)
            history.add(new ToolResponseMessage(tc.id, result))
        }
    }
    session.saveHistory(history)
    emit(RUN_COMPLETED, usage)   // 前端展示本轮 token 消耗
}
```

#### 工具渐进式披露

工具按 `@ToolScope` 注解声明可用上下文（支持通配符，标签匹配）：

```java
@ToolScope("*")                        // 全局工具
@ToolScope({"*", "page:tasks"})        // 全局 + 任务中心页
@ToolScope({"task:EXPORT/SELECT_DEFS", "task:IMPORT/UPLOAD"})  // 指定任务类型+步骤
```

`ToolRegistry.forContext(ctx)` 在每轮模型调用前按 `{*, page:xxx, task:*, task:TYPE, task:TYPE/STEP}`
标签集合动态解析可用工具集；运行中上下文变化（跳步骤）后下一轮自动生效。

#### HITL 协议

```
后端：emit INTERACTION_REQUEST {iid, runId, toolCallId, interactionType, summary, details, params}
      （先推送再 awaitResponse；future 由 HitlManager 以 iid 为键独立持有）
前端：用户点击确认/拒绝 → POST /api/ai/sessions/{sid}/interactions/{iid}
后端：future.complete(result) → 继续循环；拒绝结果同样作为工具结果回填模型
超时（5min）/取消：按拒绝处理，保证 tool_calls 与 tool 结果配对完整
```

#### UI_COMMAND 协议（AI → 前端）

工具通过 `AgentRunContext`（ThreadLocal）拿到当前发射器，可下发前端指令：

```json
{"type":"UI_COMMAND","command":"navigate","payload":{"route":"/tasks/1/export/EXPORT"}}
{"type":"UI_COMMAND","command":"open_editor","payload":{"taskId":1,"defCode":"CURRENCY"}}
{"type":"UI_COMMAND","command":"download","payload":{"url":"/api/tasks/1/files/CURRENCY","fileName":"CURRENCY.xlsx"}}
```

前端：navigate 走 router.push，download 触发浏览器下载，其余经 window 事件广播给业务组件。

#### 状态同步

- 前端路由变化 → `PUT /api/ai/sessions/{sid}/context`
- AI 工具修改业务数据 → `TaskChangedEvent` → `GET /api/tasks/{id}/events` SSE 推送
- AI 下发的导航/编辑器指令 → UI_COMMAND（见上）

#### 联动而不耦合（关键架构原则）

**单一事实来源**：任务/作业/配置的全部状态只存在后端。用户在工作区的操作与
AI 的工具调用**走同一套 REST 接口修改后端状态**，并通过**同一条任务 SSE 事件流**
广播变更。前端只订阅事件流、按事件刷新本地快照——事件来源（用户 or AI）对消费方透明：

```
用户操作 ──► REST(同一接口) ──┐
                              ├──► 后端状态 ──► TaskSseService ──► SSE ──► 工作区订阅刷新
AI 工具调用 ─► 同一服务(直接) ─┘
```

由此保证解耦与可拆卸性：
- **去掉 AI 面板**：工作区操作零依赖 AI 模块，功能完全不受影响
- **去掉工作区**：AI 工具直接操作后端服务，任务/作业照常流转，事件无人订阅也不报错
- 工作区步骤组件"认领"事件流中对应类型的作业（AI 启动的作业自动接管展示进度），
  外部变更同步本地表单状态（选配置勾选、查询条件等），本地未保存编辑受快照守卫保护

**会话与页签**：sid 存于 sessionStorage（跟随浏览器页签）；新页签 = 新会话（无历史）；
同页签刷新 = 从后端内存会话恢复全部对话显示（用户消息/AI回复/工具卡片/token 行）。
后端内存会话 30 分钟 TTL，过期或后端重启则开新会话（符合"不持久化"约束）。

---

## 3. 前端设计

**样式体系**：Element Plus（组件库）+ TailwindCSS v4（布局/间距/气泡等原子化样式）。
约束：能用 Element Plus 现成组件的（步进条 el-steps、表格、对话框、进度条等）一律使用 EP 组件，
能用 Tailwind 原子类的样式一律用原子类（全局复用样式以 @apply 组合），
仅保留 Tailwind/EP 未提供的部分（中文字体栈、滚动条、光标闪烁动画等）。
引入顺序为 Tailwind（含 preflight）→ Element Plus CSS → 业务样式，
避免 preflight 重置覆盖 Element Plus 组件样式。

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
App.vue（Tailwind flex 双栏：flex-1 工作区 + w-[380px] AI 栏）
├── 左侧业务工作区（<RouterView />，独立可替换）
└── 右侧 AiPanel.vue（常驻，不随路由销毁）
```

### 3.3 Pinia Stores

| Store | 职责 |
|-------|------|
| `useWorkspaceStore` | 当前页面上下文（page, taskId, step），上报给后端 |
| `useTaskStore` | 任务数据、任务项、文件、作业状态；**订阅任务 SSE 事件流**（connectTaskStream），维护 liveJobs 片段供步骤组件认领 AI 启动的作业 |
| `useAiStore` | 会话管理、消息列表（含刷新恢复）、当前 run 状态、HITL 交互 |
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
| DeepSeek thinking | 关闭（extraBody: {thinking:{type:disabled}}）+ reasoning_effort=low | 实测：thinking=disabled + stream=true 时模型不发起工具调用，需同时设置 reasoning_effort=low 才恢复；新版 deepseek-flash 第二轮回传无需 reasoning_content |
| 会话存储 | Caffeine 内存（TTL 30min） | 无需持久化；前端 sessionStorage 页签唯一 |
| 历史裁剪 | 保持 tool_calls/tool message 配对完整 | OpenAI 协议要求配对，断对会 400 |
| 进度推送 | SSE（任务级别 + AI run 级别两条流） | 简单、无状态；与 fetch 兼容 |
| Excel 读写 | Apache POI 5.4（SXSSFWorkbook + SAX 读） | 大文件流式处理；无需外部服务 |
| 数据库迁移 | Flyway（非 ddl-auto:update） | 显式 schema 控制；生产友好 |
| 并发控制 | 乐观锁（@Version）+ 业务守卫 | 轻量；H2 环境足够 |
| 任务取消 | AtomicBoolean cancelled + 检查点 | 简单；与虚拟线程兼容 |
