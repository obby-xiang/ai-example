# 项目记忆（ai-example）

> 同步自 Trae 本地记忆 `project_memory.md`（2026-09-04）。跨项目通用的用户偏好见同目录 [user-profile.md](user-profile.md)。

## Hard Constraints
- Project directory structure: 'ai-example' with 'ai-ui' (frontend) and 'ai-service' (backend) subdirectories
- Frontend package manager: yarn
- Backend build tool: maven (path: 'D:\Program Files\JetBrains\IntelliJ IDEA\plugins\maven-plugin\lib\maven3\')
- Technology stack: Backend Spring Boot 3.5.14 + JDK 21, Frontend Vue3 (Composition API), table component GrapeCity SpreadJS
- AI interface: 'https://api.deepseek.com/chat/completions' (OpenAI specification), model 'deepseek-v4-flash', API key must be injected via environment variable DEEPSEEK_API_KEY (never hardcoded)
- Persistence requirement: Task data must be persisted from creation (draft state) and recoverable after page refresh
- Cross-route state: Right AI panel must not be destroyed during left route switching
- Interface language: All interfaces must use Chinese
- File modification restriction: Only files under the current working directory can be modified
- AI interaction must use DeepSeek Function Calling with frontend tool registry instead of text-based JSON parsing
- Agent architecture: Backend runtime with agent loop, frontend tools use pause-resume mechanism
- Tool definition location: All tool definitions centralized in backend YAML files (tools.yaml + scenarios.yaml), frontend only传递 tool names and execute logic
- Tool inventory: 13 tools total — navigate_step, get_workspace_state, list_config_defs, get_config_def, select_definitions, confirm_complete, table_batch_set_field, table_delete_rows, table_replace_values, run_flow, collect_user_input, excel_import, excel_export
- Backend ports: ai-service runs on 8081 (8080 occupied), frontend dev server on 5173
- Scenario step validation: When scenario=null, only SELECT_SCENARIO step is allowed; jumping to subsequent steps (e.g., SELECT_DEFS) will return 400 error
- ai-ui-vercel project: Frontend uses Vercel AI SDK 6.0.221 + @ai-sdk/openai 3.0.97 for AI runtime; backend only provides AiProxyController transparent proxy (/api/ai/proxy/**); API key never leaves backend (frontend provider.js uses 'placeholder'); data persisted via localStorage
- .trae directory handling: .trae/documents/ must be included in the repository; .trae/.cache/ and *.log files must be excluded via .gitignore

## Engineering Conventions
- Database: Use embedded H2 database with file storage (jdbc:h2:file:./data/ai_config_db;DB_CLOSE_DELAY=-1;MODE=MySQL), ddl-auto:update auto-migrates schema on restart
- SpreadJS requirements: Utilize native capabilities for validation, required fields, and dropdowns to minimize custom code
- Backend AI service uses RestClient to directly connect to DeepSeek API instead of Spring AI ChatModel
- Frontend tool registry manages 'validate → needConfirm → execute' logic for tools with autoExec/needConfirm/requireDoubleConfirm分级
- Agent loop workflow: Backend runs while loop to call DeepSeek until no tool_calls; backend tools execute synchronously, frontend tools with autoExec=true run automatically, others pause for confirmation
- Tool result submission: Frontend uses POST /api/ai/tool-result with resumeToken to submit execution results
- State persistence: AgentState (messages, pendingToolCalls, workflow context, plan, stepArtifacts, scenario, step, mode) serialized to H2 with 24h expiration
- Confirmation gate: Batch operations (e.g., run_flow) require double confirmation (tool card button + ElMessageBox two-pass)
- Plan-Execute workflow: AI generates execution plan first, user confirms once before step-by-step execution; PlannerService uses separate prompt with state graph injection + JSON response_format, fallback uses state graph directly when LLM fails
- Scenario state machine: Each business scenario defined as state graph (scenarios.yaml) with steps and step-specific tool sets
- Progressive disclosure: Only expose tools applicable to current scenario and step (ToolDiscoveryService filters at runtime)
- BACKEND tools (list_config_defs, get_config_def, get_workspace_state) execute synchronously in agent loop; results回灌 as tool messages; model autonomously chains multi-round queries (list→get detail)
- System prompt principle: describe capabilities only, NEVER inject full business data; AI queries on-demand via tools (token optimization + real-time data, avoids stale snapshot)
- Tool信任分级: autoExec (自动执行, read/navigate/select类), needConfirm (用户确认, destructive), requireDoubleConfirm (二次确认弹框, high-risk flows like run_flow)
- AgentState mode field: PLAN(规划中)/EXECUTE(执行中)/AUTO(自动执行)/CONFIRM(待用户确认)/INPUT(待用户输入)/DONE(完成), frontend renders accordingly
- Frontend AiPanel auto-execution: watch on messages.length triggers tryAutoExecute for autoExec=true tools; dedup via autoExecutedCallIds Set to prevent loops
- User input collection: Use collect_user_input tool with Schema-driven form cards (supports text/textarea/number/boolean/single_select/multi_select/button_group/date controls) and agent pause-resume mechanism
- System prompt workflow guidance: When current step intent is satisfied, immediately call navigate_step to advance to next state graph step without waiting for user confirmation or repeatedly calling get_workspace_state
- Frontend AI runtime pause-resume: Tool execute function uses await waitForConfirm/waitForForm to pause; sets pendingInteraction reactive state; AiPanel watches and renders confirmation buttons/forms; user action triggers resolveInteraction to resume multi-step loop
- Vercel AI SDK 6.x configuration: Replace maxSteps with stopWhen: stepCountIs(10) for multi-step agent loop; inject synthetic user message when history is empty to avoid AI_InvalidPromptError
- Vite proxy setup: /api/ai/proxy routes to http://localhost:8081 for backend API proxy
- DeepSeek thinking mode: Proxy layer injects thinking={"type":"disabled"} in request body to disable reasoning_content requirement and save tokens
- JS comment syntax: Avoid */ in comments; expand tool name abbreviations (e.g., table_*/run_flow → table_batch_set_field/table_delete_rows/table_replace_values/run_flow) to prevent premature comment termination

## Lessons Learned
- Using Spring AI Alibaba dependency is unnecessary for this project
- Text-based JSON code block parsing is unreliable (prone to format instability and parsing errors)
- koa-connect wrapper caused ctx leaks in previous middleware attempts
- NonUniqueResultException occurs when multiple WAITING_TOOL states exist for same session; use findAllBySessionIdAndStatus
- useRouter() must be imported as singleton in frontend tool registry to avoid setup phase errors
- Parallel execution of frontend tools with dependencies causes state conflicts; enforce sequential execution
- Phase 4 token optimization: removing full config def injection from system prompt + on-demand tool queries reduces tokens and gives AI real-time data (verified: AI chains list_config_defs→get_config_def autonomously)
- contenteditable div input cannot be automated by browser_type; use browser_evaluate to set textContent for E2E tests
- autoExec tool auto-execution needs dedup Set (autoExecutedCallIds) to prevent watch infinite loop
- ddl-auto:update automatically adds new AgentState fields (plan_json, step_artifacts_json, current_scenario, current_step, mode) on backend restart, no manual schema migration needed
- Planner may generate more steps than state graph defines (e.g. 6 vs 4) due to AI自由规划; this is flexibility not a bug
- Windows file lock: maven clean fails if old jar process holds target/ai-service.jar; kill port 8081 process first
- H2 new版本 does not support AUTO_SERVER=TRUE with DB_CLOSE_ON_EXIT=FALSE simultaneously; use DB_CLOSE_DELAY=-1 instead
- Spring AI 1.1.x starter renamed to spring-ai-starter-model-openai; old artifactId spring-ai-openai-spring-boot-starter invalid
- 国内镜像 may not sync Spring AI new版本; use 阿里云 public mirror via project-level maven-settings.xml excluding spring/central interception
- DeepSeek tool name must match ^[a-zA-Z0-9_-]+$; description containing "{字段: 新值}" needs double-quote wrapping to avoid YAML mapping parse error
- OpenAI protocol 400 "insufficient tool messages following tool_calls": when AI returns multiple tool_calls but agent loop serially executes only the first, the assistant message must contain ONLY the executed tool_call_id (not all rawCalls), otherwise tool_call_ids outnumber tool messages → 400. Fix: appendAssistantMessage(messages, msgNode, List.of(c)) not rawCalls; submitToolResult's appendAssistantToolCallMessage already has dedup guard
- chat entry pre-saves user message to DB (line 121-127), then buildRawMessages listHistory reloads it AND appends current message → duplicate user messages in request body. Fix: dedup by comparing history tail role+content with current message before appending
- DeepSeek thinking mode 400 "reasoning_content must be passed back": thinking 默认开启,带 tools 参数时中间 assistant 的 reasoning_content 必须回传否则 400。根治:buildRawRequestBody 加 thinking={"type":"disabled"} 关闭思考(配置管理场景无需思维链,省token加速);兜底:appendAssistantMessage 保留 reasoning_content 字段
- Vue watch 监听 messages.length 无法捕获同消息更新: store.updateLastMsg 更新已有消息(length不变)导致 watch 不触发,tryAutoExecute 永不执行 AUTO 工具。Fix: watch 监听签名 `${length}|${last.done}|${last.toolCalls.length}` 覆盖"新消息追加"和"同消息更新"两种情况
- cleanupWaitingState 无条件清理同 session 所有 WAITING_TOOL state 导致 500: 用户发新消息时 chat 入口调 cleanupWaitingState 把前一个未回灌的 run_flow state 误设为 EXPIRED,二次确认后回灌报 500 "state 状态非 WAITING_TOOL 无法回灌: EXPIRED"。Fix: 只清理 expires_at<now 的过期 state,保留活跃的(submitToolResult 用 resumeToken 精确查找,多个活跃 WAITING_TOOL 不冲突)
- StepHeader 进度条异常 when scenario=null: scenarioMeta.scenarioSteps[null] returns empty array, el-steps renders nothing. Fix: displaySteps fallback to render current step as single node when steps array is empty
- collect_user_input impact description error: summarizeImpact missing case caused incorrect "未知工具,前端将阻断执行" message. Fix: added collect_user_input impact description "将向用户收集 {n} 个表单字段"
- Cancel button state overwritten: tool execution cancellation state was incorrectly set to failed. Fix: updated ai.js store to preserve cancel state
- Vite dev proxy 透传 SSE 正常(curl 验证),但 location.reload() 会取消前页 pending fetch 导致 ERR_ABORTED,属正常浏览器行为非 AI 流程错误
- browser_network_requests in browser_use tool loses history after multiple page switches (agent tool limitation, not a functional issue); verify network requests via UI behavior instead
- ExcelIO does not export colHeader area by default, causing missing table headers in exported Excel files
- Hardcoded data limit in preview function caused incomplete data loading in online Excel tables
- 对话归档/恢复方法论（跨项目通用）已沉淀在 user_profile.md「Trae 会话数据恢复方法论」：用户消息逐字原文在 workspaceStorage state.vscdb 的 input-history 键，轮次级记录在 .trae-cn memory 的 session_memory_*.jsonl，AI 回答正文仅在加密库/云端不可明文恢复；本仓库 docs/conversation-archive-20260904-excel-capabilities.md 是按该方法论生成的归档样例
