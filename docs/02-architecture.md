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

### D1：AI Runtime 放后端，Spring AI 高层 API + 受控工具循环
- **决策**：使用 Spring AI 1.1.8 的 `spring-ai-starter-model-openai`（OpenAI 兼容协议对接 DeepSeek）。**能用 Spring AI 现成能力的绝不自造**：`OpenAiChatModel.stream(Prompt)` 做流式调用、`ToolCallback`/`ToolDefinition` 定义工具、`MessageWindowChatMemory`+`InMemoryChatMemoryRepository` 管对话历史、`spring.ai.retry.*` 管非流式重试、yml 统一配置 base-url/completions-path/model。自研的只有一层薄编排：按轮驱动 Loop、BACKEND/FRONTEND 工具分流、FRONTEND 工具暂停-恢复（`internalToolExecutionEnabled=false` 是 Spring AI 官方预留的受控执行开关）。
- **理由**：① 需求要求 API Key 不出后端、工具混排（后端数据工具 + 前端 UI 工具）——纯前端 Runtime 做不到 Key 安全；② Spring AI 的自动工具循环无法表达"FRONTEND 工具要暂停、等前端执行结果回灌再恢复"的语义（HITL），框架也允许关闭内部执行由应用自控——这不是重复造轮子，而是使用框架预留的扩展点。
- **唯一的框架补丁**：`OpenAiChatModel.createRequest` 把历史 assistant 消息的 `reasoningContent` 硬编码为 null，而 deepseek-flash 思考模式必须回放（缺失 400，实测）。在同名包下放 `ReasoningAwareOpenAiChatModel extends OpenAiChatModel`，@Override 该方法仅改动这一处传播（reasoning 存于 `AssistantMessage.metadata["reasoningContent"]`——流式 chunk 由框架放入同键，闭环）。类 Javadoc 注明升级后评审移除。曾评估 `spring-ai-deepseek` 原生模块：其 `createRequest` 存在**完全相同**的缺口（也硬编码 null），换模块无实质收益且损失 OpenAI 兼容通用性，故坚持用 OpenAI 组件。
- **DeepSeek 兼容性要点**（已核实 Spring AI v1.1.8 源码 + 实测验证）：
  - **`deepseek-flash` 是思考模式模型：回填 assistant 消息必须携带本轮 `reasoning_content`，缺失会 400**（报错原文：*The `reasoning_content` in the thinking mode must be passed back to the API*）。⚠ 这与 deepseek-chat/deepseek-reasoner 的旧规则（不可回传 reasoning_content）**相反**，是实测踩坑后修正的结论；
  - `parallel_tool_calls=false`（1.1.8 流式合并器单帧只支持单调用）；
  - 模型名 `deepseek-flash` 直接传字符串；流式 usage 帧 choices 为空需判空。
- 循环上限 8 轮；一轮多 toolCalls 时 BACKEND 全部就地执行、只暂停第一个 FRONTEND 调用，恢复时未应答调用补错误 ToolResponse 防 400；流式整轮 attempt 重试（≤3 次，已产出内容不重试——Spring AI 重试只覆盖非流式）；工具结果截断 4000 字符。

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

### D9：AI 会话 —— 页签 session 为主，双保险刷新不丢
- `sessionId` 存 sessionStorage（页签内唯一，刷新保留，**新页签即全新会话无历史**）；后端用 Spring AI `MessageWindowChatMemory`（内存仓库，80 条窗口）存模型消息，会话现场（pendingCall/取消标志）存 `ConcurrentHashMap`，TTL 2h 定时清理。
- **刷新恢复双保险**：① 前端 ai store 把展示消息+待确认工具镜像进 sessionStorage，刷新即时回放；② 再与后端 `/api/ai/history` 对账——后端会话存活以后端为准（可续跑工具循环），后端已失效则保留镜像展示、挂起工具降级为"会话已失效"（不可误点），发新消息时后端自动重建会话。
- 不用 Redis：单机演示规模，引入嵌入式 Redis 收益低、复杂度高；任务态本来就在 H2。

## 3. 后端设计

### 3.1 技术栈
Spring Boot 3.5.14 / JDK 21 / spring-ai-bom 1.1.8（`spring-ai-starter-model-openai`，高层 API：`OpenAiChatModel`/`ToolCallback`/`ChatMemory`）/ spring-boot-starter-web / data-jpa / validation / H2（file: `./data/quickstart_db;MODE=MySQL`）/ Lombok / Jackson。Maven 构建。

### 3.2 包结构（`com.example.quickstart`）
- `config`：`OpenAiConfig`（`@Primary OpenAiChatModel`=补丁子类 + `ChatMemory` Bean）、异步线程池、CORS
- `entity` / `repository`：上节领域模型 + `ExportResult`（导出结果暂存）
- `service`：`ConfigDefService`（元数据+查询）、`TaskService`、`JobService`+`JobExecutor`、`ConfigValidator`、`DependencyService`（拓扑序）、`DataSeedService`
- `ai`：`AiChatService`（受控工具循环 + SSE）、`AiSessionStore`（会话现场：pendingCall/展示消息/取消标志）、`AiToolRegistry`（工具定义/按页面步骤过滤，产出 `ToolCallback`）、`RoutingToolCallback`（BACKEND 路由执行/FRONTEND 占位）、`BackendToolExecutor`、`PromptBuilder`
- `org.springframework.ai.openai.ReasoningAwareOpenAiChatModel`：同名包补丁子类（见 D1）
- `controller`：`ConfigController`、`TaskController`、`JobController`、`AiChatController`

### 3.3 校验规则（ConfigValidator）
必填、NUMBER 可解析、DATE 格式 yyyy-MM-dd、ENUM 在 options 内、BOOLEAN 为 true/false、maxLength、未知字段警告、ref 引用完整性。逐行产出错误列表（行号+字段+原因），作业明细截断 100 条。

### 3.4 AI 受控工具循环流程
```
chat(sessionId, message, context) ──SSE──▶
  system prompt 临时前置（基础人设 + 场景说明 + context JSON + 工具准则），历史取 ChatMemory
  loop ≤8 轮:
    OpenAiChatModel.stream(Prompt(messages, OpenAiChatOptions{
        toolCallbacks=按context过滤, internalToolExecutionEnabled=false}))
      文本增量 ──▶ SSE event: token
      思维链增量（chunk AssistantMessage.metadata["reasoningContent"]）──▶ SSE event: reasoning
      toolCalls（框架已合并流式分片）:
        BACKEND 工具 → 执行 → SSE event: tool_run → ToolResponseMessage 入 ChatMemory → 下一轮
        FRONTEND 工具 → 保存现场(pendingCall) → SSE event: tool_call(含needConfirm) → 暂停返回 done(waiting)
    无工具调用 → 聚合 AssistantMessage(含 reasoning) 入 ChatMemory → SSE event: done(finished)
tool-result(sessionId, callId, result, context) ──SSE──▶ 恢复现场，ToolResponseMessage 入 ChatMemory，继续 loop
```
- 取消：`/api/ai/cancel` 置标志，轮次边界生效；前端 AbortController 断开 SSE。
- 聚合入库的 AssistantMessage 在 metadata 携带本轮 reasoningContent，补丁子类在下次请求时回放（思考模式必需，见 D1）。

## 4. 前端设计

### 4.1 技术栈
Vue 3 + Vite + Pinia + Vue Router + Element Plus + TailwindCSS v3（`preflight=false` 防与 Element Plus 冲突）+ `@grapecity/spread-sheets` / `spread-excelio` / `spread-sheets-resources-zh`（全部锁定 17.1.5）+ jszip + axios + dayjs。界面全中文（SpreadJS Culture `zh-cn`）。**样式一律使用 Element Plus 现成组件与 Tailwind 工具类，不写自定义 CSS**（仅极个别如 SpreadJS 宿主动态高度、三点动画保留并注明原因）。

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
- SpreadJS 不用官方 Vue 包装组件，直接 `new GC.Spread.Sheets.Workbook(el)`，封装为组件暴露 `loadData(fields, rows)` / `collectRows()` / `setReadOnly`；ResizeObserver 自动 `refresh()` 解决隐藏容器初始化塌陷。
- LicenseKey 从 `import.meta.env.VITE_SPREADJS_KEY` 读取（无授权时仅水印，功能可用）。
- 向导步骤数据变更即调 `PUT /api/tasks/{id}/step-data` 持久化，刷新/重进可恢复。

### 4.3 AI 对话栏 ⇄ 业务工作区：联动但不耦合
- 两侧唯一接口层是 **workspace store 的公开契约**：`snapshot()`/`buildContext()`（AI→工作区取上下文）、`registerPageHandler()`（工作区页面挂载时注册自己的工具动作实现，卸载注销）、`dataVersion`。AI 侧（ai store/AiPanel/frontend-tools）只 import 该契约；业务视图不 import ai store。
- **用户无论用 AI 对话还是亲手操作，状态都走同一份 workspace store**：AI 的工具动作与用户点的按钮调用的是同一批页面 handler，天然双向联动、无两套状态。
- **可独立移除**：AiPanel 是 `defineAsyncComponent` 懒加载 + `AI_PANEL_ENABLED` 开关——关掉后工作区全部功能不受影响（所有 AI 动作均有对应 UI 按钮）；没有工作区时 AI 栏也可独立对话与调用后端工具。

### 4.4 布局
`App.vue`：左侧 `router-view`（业务工作区），右侧固定宽度 AiPanel（可折叠，懒加载）。

## 5. 风险与对策
| 风险 | 对策 |
|---|---|
| deepseek-flash 思考模式要求回填 reasoning_content（缺失 400，与旧模型规则相反） | 同名包补丁子类 `ReasoningAwareOpenAiChatModel` 在 createRequest 中传播（框架缺口，升级后评审移除）；reasoning 存 AssistantMessage.metadata 闭环（实测验证） |
| 模型一次输出多个 tool_calls 触发合并器异常 | parallel_tool_calls=false；一轮多调用时 BACKEND 全执行、FRONTEND 只暂停第一个，恢复时未应答补错误响应防 400 |
| 模型幻觉调用当前页面不可用工具 | 三道防线（过滤/提示词/执行端校验） |
| AI 导航后立即恢复 Loop 时页面未就绪（工具按旧页面过滤） | navigate_to 工具等待 workspace 切换到目标页/步骤（≤3s）后再回灌（实测修复） |
| SpreadJS 在隐藏容器（el-tabs 未激活页）中初始化后布局塌陷 | ResizeObserver 监听宿主尺寸变化自动 `refresh()`（实测修复） |
| 作业重复提交 | RUNNING 存在即复用返回 + 按钮防重 |
| 异步作业在事务提交前触发、执行线程读不到作业行 | 事务 afterCommit 后再提交执行（实测修复） |
| 大结果集进 LLM 上下文 | 工具结果截断 + 列表摘要化（>50 项只给摘要） |
| SpreadJS 无授权水印 | 演示接受；生产注入正式 License |
