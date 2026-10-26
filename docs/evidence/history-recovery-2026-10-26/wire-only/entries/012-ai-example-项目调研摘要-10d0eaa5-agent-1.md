> **历史分析 / 过程证据 — 非现行基线，非验收结论**
>
> - 归档：2026-10-26 · `docs/evidence/history-recovery-2026-10-26/wire-only/`
> - 来源：Kimi Code 会话 `<KIMI_SESSION>` / 子 agent `agent-1` 的 wire 末条 assistant 文本（只读流式解析；非原始会话转写）
> - 原始体量：11351 B / 8025 字符 / 93 行；行指纹覆盖率（仓内 + 落盘语料）1.4%
> - 类别：**过程核验**
> - 脱敏：路径 1 / 会话标识 0 / 身份 0 / 密钥 0
> - 正文为该条消息的完整文本，未删段、未改写结论；仅脱敏与行尾空白规整。当时结论不代表当前代码状态。
# ai-example 项目调研摘要

项目根目录：`<LOCAL_WORK>\ai-example`，由三个子项目组成：`ai-service`（Spring Boot 后端）、`ai-ui`（范式1 前端，后端 Agent Loop 驱动）、`ai-ui-vercel`（范式2 前端，Vercel AI SDK 前端驱动）。以下为详细结论。

## 1. 后端结构（ai-service）

### pom.xml 依赖（`ai-service/pom.xml`）
- 父 POM：`spring-boot-starter-parent:3.5.14`，Java 21。
- Spring AI：`spring-ai-bom:1.1.8` 通过 `dependencyManagement` import 引入，并显式依赖 `spring-ai-starter-model-openai:1.1.8`（注释强调 1.1.x 新命名规范 `spring-ai-starter-model-openai`，非旧名 `spring-ai-openai-spring-boot-starter`）。
- 其他：`spring-boot-starter-web`、`spring-boot-starter-data-jpa`、`spring-boot-starter-validation`、`h2`(runtime)、`lombok`(optional)、`jackson-databind/yaml/jsr310`。
- **重要发现**：生产链路根本没用 Spring AI 的 `ChatClient`/`ChatModel`/`@Tool`（全项目 grep 无匹配）。LLM 调用是手写 `RestClient` 直接 POST DeepSeek 的 `/chat/completions`。`spring-ai-starter-model-openai` 的依赖只在 `FrontendTools.java` 里用来构造 `OpenAiApi.FunctionTool` 类型作"对照参考"（注释自承"生产链路使用 RAW_TOOLS_AS_MAPS"）。**spring-ai 依赖基本可删除，别照抄这个依赖引入。**

### application.yml 关键配置（`ai-service/src/main/resources/application.yml`）
- `server.port: 8081`；H2 文件库 `jdbc:h2:file:./data/ai_config_db;MODE=MySQL`，`ddl-auto: update`，H2 console 开启。
- AI 双份配置：
  - `spring.ai.openai.{api-key, base-url=https://api.deepseek.com, chat.options.model=deepseek-v4-flash}` —— 标准 Spring AI provider 配置方式（api-key 走环境变量 `DEEPSEEK_API_KEY`）；
  - `app.ai.*`（base-url/chat-path/model/api-key/read-timeout-seconds=180/max-retries=3）—— 实际被手写 RestClient 使用的配置；
  - `app.agent.waiting-tool-ttl-minutes: 10` —— 暂停交互超时。

### 包结构与核心代码模式
包结构：`com.example.ai` 下 `config/controller/dto/entity/repository/service/tool` 经典分层。

**核心类 `AiService.java`（1145 行，`service/AiService.java`）—— 自研 Agent Loop，不用 ChatClient**：
- 单一对话入口：`chat()` + `submitToolResult()` 两个 `@Transactional` 方法，共享 `runAgentLoopInternal()`。
- Loop 逻辑：while（最多 8 轮）→ 手写请求体（含 `thinking:{type:"disabled"}` 根治 DeepSeek 思考模式回传 400 bug）→ RestClient 调 DeepSeek → 解析 `tool_calls` → **只串行执行第一个 tool_call**（修复"N 个 tool_calls 配 1 个 tool message"导致的 400）→ BACKEND 工具 loop 内执行，FRONTEND 工具则暂停：把 messages 序列化存入 `AgentState` 表生成 `resumeToken`，返回 `done=false`。
- 前端执行工具后 POST `/tool-result` 带 `{resumeToken, callId, result}` 回灌，恢复 loop —— 即 **LangGraph checkpointer 式暂停-恢复**。
- 可靠性细节值得借鉴：429/5xx 指数退避+jitter 重试（尊重 `Retry-After`）、显式 connect 10s/read 180s 超时、工具结果 >2000 字符截断、列表 >50 项摘要化、回灌时 dataVersion 不一致则注入系统提示强制 AI 现查、协作式取消（`/cancel` 置标志 + loop 轮次边界检查）。

**工具定义单一事实源**（`tool/FrontendTools.java` + `resources/tools.yaml` + `resources/scenarios.yaml`）：
- 13 个工具，Java 端用静态 `List<ToolSpec>` 定义 JSON Schema，派生 `RAW_TOOLS_AS_MAPS`（原生 OpenAI 协议格式）直接塞进请求体。
- `tools.yaml` 声明每个工具的 `kind: FRONTEND/BACKEND`、`autoExec/needConfirm/requireDoubleConfirm` 信任分级、以及按 `scenarios+steps` 的过滤矩阵；`scenarios.yaml` 定义 4 个场景的状态图（如 EXPORT: SELECT_SCENARIO→SELECT_DEFS→QUERY_COND→RESULT）。
- `ToolDiscoveryService` 按当前 scenario+step 动态过滤工具集（progressive disclosure，AI 只看到当前步骤的工具）。

**H2 / 数据访问**：标准 Spring Data JPA。实体 5 个：`Task`（含 `stepData`/`changes` 用 `@JdbcTypeCode(SqlTypes.JSON)` 存 JSON）、`ConfigDefinition`、`ConfigDataRow`、`AgentState`（loop 暂停快照）、`AiChatMessage`。Repository 均为普通 `JpaRepository`。`ConfigController`（`/api/configs`）提供定义 CRUD + 数据分页/全量/`batch-save`/`operations`（update_where/delete_rows/replace）。启动时 `DataSeedService` 播种示例数据。

**SSE / 流式**：范式1 聊天是**同步阻塞**的（`/api/ai/chat` 一次性返回，前端用"打字点"动画假装流式）。真正的 SSE 只存在于 `AiProxyController.java`（`/api/ai/proxy/**`）：为范式2 服务的透明 SSE 代理——用 JDK 21 `java.net.http.HttpClient`（零新依赖）转发请求、注入真实 api-key、`StreamingResponseBody` 逐块 flush 透传 SSE 字节流、并归一化注入 `thinking:{type:"disabled"}`。这个"后端只做 api-key 注入代理"的模式很干净。

**任务进度上报**：没有独立进度推送机制。`Task` 实体只有 `status`(DRAFT/IN_PROGRESS/COMPLETED/CANCELLED) + `currentStep`，前端通过轮询 REST 拿状态；AI 侧靠 `currentScenario/currentStep` 元数据随每次 chat 响应下发。

## 2. 前端结构（ai-ui）

### package.json（`ai-ui/package.json`）
- `@grapecity/spread-sheets`、`spread-sheets-vue`、`spread-excelio`、`spread-sheets-charts`、`spread-sheets-resources-zh` 全部 `^17.1.10`。
- `vue ^3.5.13`、`vue-router ^4.5.0`、`pinia ^2.3.0`、`axios ^1.7.9`、`element-plus ^2.9.3`、`@element-plus/icons-vue ^2.3.1`、`dayjs`、`uuid ^11`。
- dev：`vite ^6.0.7`、`@vitejs/plugin-vue ^5.2.1`、`sass`。脚本：`yarn dev` / `yarn build`。

### vite.config（`ai-ui/vite.config.js`）
端口 5173，代理 `/api` → `http://localhost:8081`（timeout 120s），另代理 `/h2-console`。

### SpreadJS 引入与初始化（`ai-ui/src/main.js` + `components/SpreadSheet.vue`）
- main.js 全局引入：`gc.spread.sheets.excel2013white.css` + `spread-sheets-resources-zh`，`GC.Spread.Common.CultureManager.culture('zh-cn')`，LicenseKey 从 `import.meta.env.VITE_SPREADJS_KEY`（`.env.local`，不入库）注入。
- `SpreadSheet.vue` 是封装组件：未用 `spread-sheets-vue` 包装组件，直接 `new GC.Spread.Sheets.Workbook(hostRef)`，`applyColumnsToSheet`（`utils/excel-io.js`）按配置定义的 columns 建列+DataValidator 校验，行约定：col0=id、col1=变更标记（added/modified/deleted/unchanged）、col2+ 为字段。`collectRows()` 收集为后端 batchSave 格式。**暴露方式值得注意**：通过 `window.__spreadsheets[defId] = {refreshFromServer, getChanges, collectRows}` 挂全局代理供 AI 工具调用（简单但全局污染）。
- Excel 能力全在浏览器端 `@grapecity/spread-excelio`（`utils/excel-io.js`，430 行）：`generateTemplate`（离线 Workbook + Excel 原生 DataValidation 下拉）、`importExcel`、`exportExcelMultiSheet` 等，文件本体不进 LLM。

### AI 侧边栏（`components/AiPanel.vue` 678 行 + `stores/ai.js` 449 行）
- 无 SSE/WebSocket，纯 axios REST：发消息 `aiApi.chat` → 后端返回 `{reply, toolCalls, resumeToken, done, mode, pendingExpiresAt}` → AiPanel 渲染工具卡片，按信任分级三态：AUTO（autoExec 自动执行）、CONFIRM（按钮确认）、INPUT（`SchemaFormRenderer.vue` 渲染 Schema 驱动表单）→ 执行（`utils/frontend-tool-registry.js` 里的同名工具，集中管理路由跳转/Pinia/SpreadJS 副作用）→ `aiApi.toolResult` 回灌 → 追加新消息。
- 亮点机制：`/api/ai/pending` 刷新恢复（重建未过期交互卡片）、pending 倒计时、`stopRun()` 协作式停止（AbortController + 后端 /cancel + run_flow 步骤边界取消标志）、`sessionId` 存 sessionStorage。
- 状态管理：Pinia 三个 store（`ai`/`task`/`config`）+ 手写 localStorage 订阅插件（只持久化 currentTaskId/expanded）；`utils/workspace-version.js` 的 dataVersion 乐观变更感知。

## 3. 构建与启动

- 后端：`cd ai-service && mvn clean package -DskipTests -s maven-settings.xml`（自带阿里云镜像 settings），`java -jar target/ai-service.jar`，端口 **8081**。需环境变量 `DEEPSEEK_API_KEY`。
- 前端：`cd ai-ui && yarn install && yarn dev`，端口 **5173**（ai-ui-vercel 为 5174）。
- 入口文档：`README.md`、`docs/design-and-implementation.md`、`.trae/documents/`（阶段设计文档）。

## 4. 值得借鉴 vs 不要照抄

**值得借鉴**：
- Agent loop 暂停-恢复协议（`resumeToken` + `AgentState` 表持久化 messages JSON + TTL 过期 + 刷新恢复 `/pending`）——是完整生产级设计。
- 工具信任分级（autoExec/needConfirm/requireDoubleConfirm）+ 按场景状态图动态过滤工具（progressive disclosure），schema 集中在后端、前端只执行。
- DeepSeek 踩坑记录：`thinking:{type:"disabled"}` 根治 reasoning_content 400；串行只执行第一个 tool_call；tool 结果截断。
- SSE 代理（`AiProxyController`）：JDK HttpClient + StreamingResponseBody 透传，零新依赖，api-key 不出后端。
- 协作式取消语义（轮次边界停止、保留现场、不回滚）、dataVersion 变更感知。

**不要照抄**：
- **过度设计最明显的一点：引入 spring-ai-bom + starter 却完全不用 ChatClient**，手写 RestClient 调原生 API。要么删掉 spring-ai 依赖（推荐，省几百 MB 依赖），要么真用 ChatClient。两套配置（`spring.ai.openai.*` 与 `app.ai.*`）并存也是冗余。
- 工具定义双份维护：`FrontendTools.java` 静态代码 + `tools.yaml` YAML 两处描述同一批工具（README 也承认"唯一事实来源"但实际有两份），容易漂移。
- `AiService.java` 单文件 1145 行，system prompt 用 StringBuilder 硬编码在 Java 里（应抽成模板文件）。
- 前端 `window.__spreadsheets` 全局注册表、`window.__triggerAiAutoPrompt` 全局函数——demo 可行，正式项目应用 provide/inject 或事件总线。
- 范式1 聊天无流式：同步阻塞最长 8 轮 LLM 调用，用户等待体验差（靠打字点动画掩盖）。
- `sanitizeAssistantContent` / `parseActionsFromReply` 等"模型不听话输出 JSON 文本"的正则兜底属于防御性冗余，提示词约束做好后大概率用不上。
- 每个工具调用都是一次完整 DB 事务 + messages 全量序列化落库，长会话 `messagesJson` 会膨胀，无压缩机制（代码里自己也留了 TODO 性质的"上下文压缩"注释）。

**关键文件速查**：
| 主题 | 路径 |
|---|---|
| Agent Loop 核心 | `ai-service/src/main/java/com/example/ai/service/AiService.java` |
| SSE 代理 | `ai-service/src/main/java/com/example/ai/controller/AiProxyController.java` |
| 工具 Schema 源 | `ai-service/src/main/java/com/example/ai/tool/FrontendTools.java`、`src/main/resources/tools.yaml`、`scenarios.yaml` |
| Planner（Plan-and-Execute） | `ai-service/src/main/java/com/example/ai/service/PlannerService.java` |
| AI 侧边栏 | `ai-ui/src/components/AiPanel.vue`、`ai-ui/src/stores/ai.js` |
| 前端工具执行 | `ai-ui/src/utils/frontend-tool-registry.js` |
| SpreadJS 封装 | `ai-ui/src/components/SpreadSheet.vue`、`ai-ui/src/utils/excel-io.js` |
