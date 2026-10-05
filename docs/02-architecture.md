# 技术方案（架构设计）

版本：v1.0　日期：2026-10-05

## 1. 总体架构

```
┌─────────────────────────── 浏览器（单页签 = 一个 AI 会话） ───────────────────────────┐
│  Vue 3 SPA                                                                          │
│  ┌───────────────────────────────┐  ┌────────────────────────────────────────────┐  │
│  │ 左侧业务工作区（任务向导）      │  │ 右侧 AI 对话栏（常驻）                      │  │
│  │ 任务列表 / 导出向导 / 导入向导  │  │ 消息流 + 工具卡片 + 确认按钮                 │  │
│  └──────────────┬────────────────┘  └───────────────────────┬────────────────────┘  │
│                 │ REST / 轮询作业进度                        │ POST SSE（流式）        │
└─────────────────┼───────────────────────────────────────────┼───────────────────────┘
                  ▼                                            ▼
┌──────────────────────────────── Spring Boot 后端 ────────────────────────────────────┐
│  REST API（配置/任务/作业）    AI Runtime（手动 Agent Loop，Spring AI OpenAiApi）      │
│  JobExecutor（异步作业+进度）   工具注册表（BACKEND 直接执行 / FRONTEND 暂停-恢复）      │
│  H2 文件库（配置/任务/作业/暂存）  AI 会话内存存储（TTL，不持久化）                      │
└──────────────────────────────────────────────────────────────────────────────────────┘
                  │ OpenAI 兼容协议（SSE 流式）
                  ▼
        DeepSeek API（https://api.deepseek.com/chat/completions, deepseek-flash）
```

## 2. 关键架构决策（含权衡）

### D1：AI Runtime 放后端，手动 Agent Loop（不用 ChatClient 高层封装）
- **决策**：使用 Spring AI 1.1.8 的 `spring-ai-starter-model-openai`，注入自动配置好的 `OpenAiApi` Bean（`base-url=https://api.deepseek.com`、`completions-path=/chat/completions` 走 yml 配置），自研一个很薄的 Agent Loop（流式消费 + 工具分发 + 暂停/恢复）。
- **理由**：① 需求要求 API Key 不出后端、工具混排（后端数据工具 + 前端 UI 工具）——纯前端 Runtime 做不到 Key 安全；② Spring AI 的 ChatClient 自动工具循环无法表达"FRONTEND 工具要暂停、等前端执行结果回灌再恢复"的语义，而 `OpenAiApi.chatCompletionStream` 已自动合并流式 tool_calls 分片，手动循环成本低、控制力强（业界类比 LangGraph checkpointer 模式）。
- **DeepSeek 兼容性要点**（已核实 Spring AI v1.1.8 源码）：
  - 回填 assistant 消息时 `reasoning_content` 必须为 null，否则 400；
  - `parallel_tool_calls=false`（1.1.8 流式合并器单帧只支持单调用）；
  - 模型名 `deepseek-flash` 直接传字符串；用 `max_tokens` 而非 `max_completion_tokens`；
  - 流式末帧 `stream_options.include_usage=true` 时 choices 为空、usage 非空，需判空。
- 循环上限 8 轮；每轮串行执行工具；429/5xx 指数退避重试（≤3 次）；工具结果截断 4000 字符。

### D2：工具协议 —— 后端工具就地执行，前端工具暂停-恢复
- 工具统一定义在后端 `AiToolRegistry`：名称、描述、JSON Schema、`kind(BACKEND/FRONTEND)`、`needConfirm`、适用页面/步骤。
- BACKEND 工具：Loop 内直接执行，结果回填，前端只看到 `tool_run` 事件。
- FRONTEND 工具：Loop 暂停，通过 SSE `tool_call` 事件下发 `{callId, name, arguments, needConfirm}`，后端在内存会话中保存待恢复现场（完整 messages）；前端执行后 POST `/api/ai/tool-result`（SSE 响应）恢复 Loop。
- 信任分级：导航/选择/填条件等可自动执行（auto）；启动导出/检查/导入/发布等动作 `needConfirm=true`，前端渲染确认卡片，用户点击确认后才真正执行并回灌结果。

### D3：用户操作与 AI 对话状态同步 —— 请求时上下文注入（Agent Context），不做持续双向同步
- 每次 chat/tool-result 请求，前端携带 `context: {page, step, taskId, selectedDefs, dataVersion}`；后端据此组装 system prompt 与可用工具集。
- AI 想读更细的界面状态时调用 `get_workspace_state` 前端工具按需拉取（拉模式），避免把页面状态持续推给模型造成上下文膨胀与状态抖动。
- AI 改界面：通过前端工具（`navigate_to`、`select_config_defs`、`set_query_conditions`…）走 Pinia store，单一数据源，界面自动响应 —— 不存在两套状态。
- 此模型对应 CopilotKit 的 Agent Context（UI→AI 单向、请求时注入）+ 显式工具调用，是本场景（工具以 UI 操作与后端 API 混合）的主流推荐做法。

### D4：渐进式披露（不同页面不同工具）
- 工具注册表按 `page + wizardStep` 过滤：模型每次请求只看到当前页面/步骤可用的工具子集（第一道防线）；
- system prompt 明确声明当前场景、可用动作与禁区（第二道）；
- 前端工具执行函数内兜底校验当前页面/步骤，不匹配直接返回错误结果给模型（第三道）。
- `navigate_to` 在所有页面可用，AI 可引导用户跳转（"开门+预填，用户逐步确认"的 Wizard 模式，不追求 AI 全自动跨页执行）。

### D5：长作业 —— 异步执行 + DB 记录进度 + 前端轮询
- 导出/检查/导入/发布统一为 JobRun/JobItem 落库，`JobExecutor`（线程池）异步逐配置项处理并按拓扑序推进，逐项更新进度。
- 前端 1s 轮询作业详情。权衡：SSE 推送进度实时性更好，但轮询实现简单、天然兼容刷新恢复（作业状态在 DB，刷新后重连即可继续看进度），对本规模足够；后续可平滑升级 SSE。
- 配置项 `app.job.item-delay-ms`（默认 300ms）为演示节流，让进度在小数据量下可观察；生产可设 0。
- 幂等：同一任务同一 kind 存在 RUNNING 作业时拒绝重复提交（返回现有作业）；页面按钮防重。

### D6：导入数据发布 —— 暂存区 + 全量替换
- 采用"是否发布"的物理隔离变体：导入写入 `staging_config_data`（按 taskId 隔离），发布时按配置项**全量替换**正式表（先删后插，同事务）。
- 权衡：标识位方案侵入主表查询逻辑（所有查询都要带 published 条件），临时表方案隔离干净、任务取消即清，选择后者。

### D7：依赖处理
- `dependsOn` 构成 DAG，作业创建时对所选配置项做 Kahn 拓扑排序（未选中的依赖项视为以正式区数据为准）；循环依赖直接报错。
- ref 字段校验数据源：正式区数据 + 本作业中排在前面的配置项已导入/已发布的暂存数据。

### D8：Excel 生成与解析在前端
- 模板生成、上传解析、导出 xlsx/zip 全部在浏览器端用 `@grapecity/spread-excelio` + `jszip` 完成；文件本体不经过后端、不进 LLM 上下文。
- 权衡：后端做需引入 POI 且大文件传输重；前端做可利用 SpreadJS 原生能力（含 Excel 原生 DataValidation 下拉），交互即时。导出的"数据"由后端 API 提供 JSON。

### D9：AI 会话 —— 单机内存 + TTL，不持久化
- `sessionId` 存 sessionStorage（页签内唯一，刷新保留，新页签独立）；后端 `ConcurrentHashMap` 存消息列表与暂停现场，TTL 2h 定时清理。
- 刷新恢复：凭 sessionId 拉 `/api/ai/history` 重建对话展示；若有未完成的 FRONTEND 工具调用（未过期），重建确认卡片。
- 不用 Redis：单机演示规模，引入嵌入式 Redis 收益低、复杂度高；任务态本来就在 H2。

## 3. 后端设计

### 3.1 技术栈
Spring Boot 3.5.14 / JDK 21 / spring-ai-bom 1.1.8（`spring-ai-starter-model-openai`）/ spring-boot-starter-web / data-jpa / validation / H2（file: `./data/quickstart_db;MODE=MySQL`）/ Lombok / Jackson。Maven 构建。

### 3.2 包结构（`com.example.quickstart`）
- `config`：AI 配置（OpenAiApi 注入、超时定制）、异步线程池、CORS
- `entity` / `repository`：上节领域模型 + `ExportResult`（导出结果暂存）
- `service`：`ConfigDefService`（元数据+查询）、`TaskService`、`JobService`+`JobExecutor`、`ConfigValidator`、`DependencyService`（拓扑序）、`DataSeedService`
- `ai`：`AiChatService`（Agent Loop + SSE）、`AiSessionStore`（内存会话）、`AiToolRegistry`（工具定义/过滤）、`BackendToolExecutor`、`PromptBuilder`
- `controller`：`ConfigController`、`TaskController`、`JobController`、`AiChatController`

### 3.3 校验规则（ConfigValidator）
必填、NUMBER 可解析、DATE 格式 yyyy-MM-dd、ENUM 在 options 内、BOOLEAN 为 true/false、maxLength、未知字段警告、ref 引用完整性。逐行产出错误列表（行号+字段+原因），作业明细截断 100 条。

### 3.4 AI Agent Loop 流程
```
chat(sessionId, message, context) ──SSE──▶
  会话不存在则新建（system prompt = 基础人设 + 场景说明 + context JSON + 工具使用准则）
  loop ≤8 轮:
    OpenAiApi.chatCompletionStream(messages, tools=按context过滤, parallel_tool_calls=false)
      文本增量 ──▶ SSE event: token
      思维链增量 ──▶ SSE event: reasoning
      finish=tool_calls（框架已合并分片）:
        BACKEND 工具 → 执行 → SSE event: tool_run → 回填 tool 消息 → 下一轮
        FRONTEND 工具 → 保存现场(pendingCall) → SSE event: tool_call(含needConfirm) → 暂停返回 done(waiting)
    无工具调用 → SSE event: done(finished)
tool-result(sessionId, callId, result, context) ──SSE──▶ 恢复现场，回填 tool 消息，继续 loop
```
- 取消：`/api/ai/cancel` 置标志，轮次边界生效；前端 AbortController 断开 SSE。
- 回填 assistant 消息时 reasoningContent=null。

## 4. 前端设计

### 4.1 技术栈
Vue 3 + Vite + Pinia + Vue Router + Element Plus + `@grapecity/spread-sheets` / `spread-excelio` / `spread-sheets-resources-zh`（全部锁定 17.1.5）+ jszip + axios + dayjs。界面全中文（SpreadJS Culture `zh-cn`）。

### 4.2 结构
```
src/
  api/          axios 实例与 REST 封装；sse.js（fetch+ReadableStream 解析 POST SSE）
  stores/       ai.js（会话/消息/待确认工具）、workspace.js（页面/任务/步骤/选择，供 context 注入与 get_workspace_state）、task.js
  router/       /tasks、/export/:taskId、/import/:taskId，默认重定向 /tasks
  views/        TaskListView、ExportWizardView、ImportWizardView
  components/   AiPanel.vue（对话栏+工具卡片+确认）、SpreadSheet.vue（SpreadJS 封装：按字段定义建列/校验/收集行）
  utils/        excel-io.js（模板生成/上传解析/xlsx导出/zip打包）、frontend-tools.js（前端工具执行器，含页面兜底校验）
```
- SpreadJS 不用官方 Vue 包装组件，直接 `new GC.Spread.Sheets.Workbook(el)`，封装为组件暴露 `loadData(fields, rows)` / `collectRows()` / `setReadOnly`。
- LicenseKey 从 `import.meta.env.VITE_SPREADJS_KEY` 读取（无授权时仅水印，功能可用）。
- 向导步骤数据变更即调 `PUT /api/tasks/{id}/step-data` 持久化，刷新/重进可恢复。

### 4.3 布局
`App.vue`：左侧 `router-view`（业务工作区），右侧固定宽度 AiPanel（可折叠）。

## 5. 风险与对策
| 风险 | 对策 |
|---|---|
| DeepSeek 返回 reasoning_content 导致回填 400 | 回填置 null（已在 Loop 中强制） |
| 模型一次输出多个 tool_calls 触发合并器异常 | parallel_tool_calls=false + 只顺序处理 |
| 模型幻觉调用当前页面不可用工具 | 三道防线（过滤/提示词/执行端校验） |
| 作业重复提交 | RUNNING 存在即复用返回 + 按钮防重 |
| 大结果集进 LLM 上下文 | 工具结果截断 + 列表摘要化（>50 项只给摘要） |
| SpreadJS 无授权水印 | 演示接受；生产注入正式 License |
