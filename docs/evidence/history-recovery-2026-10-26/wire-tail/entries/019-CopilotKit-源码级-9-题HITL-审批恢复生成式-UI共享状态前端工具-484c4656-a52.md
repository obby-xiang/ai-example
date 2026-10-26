> **历史分析 / 过程证据 — 非现行基线，非验收结论**
>
> - 归档：2026-10-26 · `docs/evidence/history-recovery-2026-10-26/wire-tail/`
> - 来源：Kimi Code 会话 `<KIMI_SESSION>` / 子 agent `agent-52` 的 wire 末条 assistant 文本（消息序号 m=39；只读流式解析，非原始会话转写）
> - 历史时点：2026-10-07 12:53 → 2026-10-07 12:55（wire 事件时间窗，UTC+8）；当时模型：`DeepSeek/deepseek-flash`
> - 原基线：正文未出现基线哈希，未经本批复验
> - 原始体量：24211 B / 18276 字符 / 92 行；行指纹覆盖率（本批复算）0.0%（无单文件重合）
> - 类别：**正式调研 / 设计 / 裁决 / 分析**（原判：调研）
> - 与既有件关系：`调研-CopilotKit-HITL实现-2026-10-17`（0.00，**源码级补充**）※归属仅到会话提示
> - 脱敏：路径 1 / 会话标识 0 / 身份 0 / 密钥 0 / 命名纪律 0
> - 正文为该条消息的完整文本，未删段、未改写结论；仅脱敏、占位符归一与行尾空白规整。当时结论不代表当前代码状态，不得作为当前缺陷或有效裁决引用。
已完成源码级挖掘。以下按 9 题组织，所有路径相对 `<LOCAL_WORK>/research-src/copilotkit/`。

---

## 1. HITL：审批-恢复

**结论：不是"同一 run 继续流"，而是新发一次 `POST /agent/:agentId/run`，body 携带 `resume: ResumeEntry[]`；线上 runId 是新的，客户端再把它"重盖章"回原 runId，对外表现为一个逻辑 run。**

- 状态机：【已核实：`packages/core/src/interrupt-state.ts`】`ɵInterruptState` 把 legacy（自定义事件 `on_interrupt`）与 AG-UI standard（`RUN_FINISHED.outcome.type==="interrupt"`）统一为 `ɵPendingInterrupt{kind:"legacy"|"standard"}`。`resolve(payload, interruptId?)`/`cancel(interruptId?)` 返回 `ɵInterruptDecision`：`ignored|waiting|dismiss|expired|legacy-resume|resume`。多中断必须全部 respond（`waiting`），才 `buildResumeArray(interrupts, responses)`（来自 `@ag-ui/client`）生成 `ResumeEntry[]`；`isInterruptExpired(interrupt)` 依据 `expiresAt`。tool result content：resolved → `payload ?? {"status":"resolved"}`；cancelled → `{"status":"cancelled"}`（`toolResultContent()`）。
- 回传通道：【已核实：`packages/react-core/src/v2/hooks/use-interrupt.tsx:233-360`】standard 路径：先 `agent.addMessage({role:"tool",toolCallId,content})`，再 `copilotkit.runAgent({agent, resume})`；legacy 路径：`runAgent({forwardedProps:{command:{resume, interruptEvent}}})`。**`resume` 是 `RunAgentInput` 顶层字段**（非新端点）。
- 服务端接收：【已核实：`packages/runtime/src/v2/runtime/handlers/shared/agent-utils.ts:448`】`RunAgentInputSchema.parse(normalizeLegacyRunAgentInput(...))`（会补 `resume[].payload` 的 legacy null 处理）。`BuiltInAgent.run()` 把每条 ResumeEntry 合成 `role:"tool"` 的 tool-result（toolCallId=interruptId，value=payload 或 `{status:"cancelled"}`）再调模型一次：【已核实：`packages/runtime/src/agent/index.ts:1314-1362`】。中断工具由 `config.tools[].interrupt` 标记，服务端**不执行、不发 TOOL_CALL_RESULT**（:1934、:2014），run 以 `outcome:{type:"interrupt",interrupts}` 结束（:2051）。
- 逻辑 run 归并：【已核实：`packages/core/src/core/state-manager.ts:80-98,109`】`getContinuationRunId` 用 resume 的 interruptId 集合精确匹配 → `markNextRunAsContinuation(agent, runId)`，事件被重盖章到原 runId；`packages/core/src/core/run-handler.ts:803` `pinRunIdOnWire = runId!==undefined && !continuationHandoff`（故意不在线上钉 runId）。行为契约见 `packages/core/src/__tests__/approval-resume.test.ts:107-149,206-228,268-299`。
- `useHumanInTheLoop`：【已核实：`packages/react-core/src/v2/hooks/use-human-in-the-loop.tsx`】注册 `type:"human-in-the-loop"` 的前端工具，handler 返回永不 settle 的 Promise，`respond(result)` resolve；abort 时 reject。render 按 `ToolCallStatus` 三态注入 `respond`（仅 `Executing` 态有）。支持通配名 `"*"`。`useInterrupt` 形态：`{render, handler?, enabled?, agentId?, renderInChat?}`，standard 优先于 legacy，订阅点 `onCustomEvent`/`onRunFinishedEvent(outcome==="interrupt")`/`onRunFinalized`；`renderInChat!==false` 时经 `copilotkit.setInterruptElement(element)` 发布进 chat。

## 2. 生成式 UI（OpenGenUI / A2UI）

- **OpenGenUI**：【已核实：`packages/react-core/src/v2/components/OpenGenerativeUIRenderer.tsx`】activity type `"open-generative-ui"`，content schema = `{initialHeight, generating, css, cssComplete, html[], htmlComplete, jsFunctions, jsExpressions[], jsExpressionsComplete}`。外层 1000ms 节流 + `shouldFlushImmediately`（cssComplete/htmlComplete/jsFunctions 出现等即刻 flush）+ 内层 `React.memo`。用 `@jetbrains/websandbox` 建 iframe，`useSandboxFunctions()`（`SandboxFunctionsContext`）的 `fn.name→handler` 作为 localApi 注入 iframe；流式期用预览沙箱 + `lib/processPartialHtml.ts`。工具卡 `OpenGenerativeUIToolRenderer` 只显示 `placeholderMessages`，Complete 后返回 null。事件加工在【已核实：`packages/shared/src/event-transforms/open-generative-ui-middleware.ts`】用 `clarinet` 增量解析 `generateSandboxedUi` 的 TOOL_CALL_ARGS，边解析边产出 ACTIVITY_SNAPSHOT/DELTA（messageId=`${toolCallId}-activity`，html 以 JSON Patch 追加）。
- **A2UI 是什么**：Google 的声明式、JSONL 流式 GenUI 规范；本仓库实现的是适配层（规范本体在 `@ag-ui/a2ui-middleware`、`@copilotkit/a2ui-renderer`，均未检出）。ops 形态 `createSurface/updateComponents/updateDataModel/deleteSurface`，前端按 surfaceId 分组（嵌套 `operation.updateComponents.surfaceId` 优先于顶层）【已核实：`packages/react-core/src/v2/a2ui/A2UIMessageRenderer.tsx:669`】，交给 `A2UIProvider.processMessages()` + `<A2UIRenderer surfaceId>` 渲染；入口组件必须 `id="root"`（`ROOT_COMPONENT_ID`），组件树须为 DAG/无环。
- **安全边界**：【已核实：`packages/react-core/src/v2/a2ui/A2UICatalogContext.tsx`】+【`packages/shared/src/a2ui-prompts.ts`】白名单式 catalog：开发者通过 `<CopilotKit a2ui={{catalog}}>` 提供组件注册表，`buildCatalogContextValue`/`extractCatalogComponentSchemas`（来自未检出的 a2ui-renderer）把可用组件与逐组件 JSON Schema 作为 agent context 注入（按 `copilotkit.a2uiAgents` 作用域）；prompt 原文要求 "ONLY use component names from the Available Components schema — do NOT invent component names"。**逐组件 props 的服务端/渲染器校验实现属 externa** →【未核实：`@copilotkit/a2ui-renderer` 未检出】。渲染失败由 `useA2UIError()` 兜底。生成侧走 `render_a2ui` 工具（`RENDER_A2UI_TOOL_NAME`，`A2UIToolCallRenderer.tsx` 注册为空渲染器）；用户动作 `runA2UIAction` → `copilotkit.setProperties({a2uiAction})` + `runAgent`（用 forwardedProps 通道，finally 清除）：
- **与 React 注册表对接**：A2UI/OpenGenUI 走活动消息通道（`types/react-activity-message-renderer.ts` + `hooks/use-render-activity-message.tsx`）；普通工具走 `types/react-tool-call-renderer.ts`（`{name, args: StandardSchemaV1, render, agentId}`）+ `types/defineToolCallRenderer.ts`，`hooks/use-render-tool-call.tsx` 用 `partialJSONParse` 流式解析 args 并按 `ToolCallStatus` 三态渲染。

## 3. Shared State

- v1 `useCopilotReadable`：【已核实：`packages/react-core/src/v1-deprecated/hooks/use-copilot-readable.ts`】当前文件已是 deprecation 头 + 指向 `useAgentContext`（v2）。
- **前端 → 模型：每轮请求注入**（不是 STATE 事件）。【已核实：`packages/react-core/src/v2/hooks/use-agent-context.tsx` → `packages/core/src/core/context-store.ts`】`addContext({description,value,agentIds?})` 存 `ScopedContext`；`run-handler.ts:609,811` 每次 run 调 `getContextForAgent(agentId)`（跨 agent 作用域过滤 + 剥掉 agentIds）放进 `RunAgentInput.context`；服务端将其拼进 system prompt（`runtime/src/agent/index.ts:1284`）。
- **模型 → 前端状态**：`agent.state` 随 `RunAgentInput.state` 上行（服务端 `handle-run.ts:77 agent.setState(input.state)`）；模型写状态靠**服务端工具** `AGUISendStateSnapshot`/`AGUISendStateDelta`（`agent/index.ts:1541,1553`），其 tool result 被翻译成 `STATE_SNAPSHOT`/`STATE_DELTA` 事件（:1961,:1978）经 SSE 下发；客户端侧 `StateManager.handleStateSnapshot/handleStateDelta`（`state-manager.ts:544,561`）按 agentId/threadId/runId 存深拷贝，真实 apply（JSON Patch）在 `@ag-ui/client`。
- **去重/版本**：`packages/core/src/utils/micro-redux.ts` 是自研极简 Redux（`createActionGroup/createReducer/createSelector/createStore`+effects，`@@micro-redux/*` 生命周期），**服务于 threads.ts / memory.ts 这类 store，不参与消息归约**（`micro-redux.test.ts` 657 行行为契约）。消息层的去重/修复靠：`StateManager.reconcilePendingResults`（:182）、`RunHandler._executingToolCalls`（executionKey=`[threadId,toolCallId]`）、`message-filter.ɵrepairToolCallPairs`（配对被裁剪时恢复 call/result 对齐）。仓库内**无 `AgentStateReducer`**（属 `@ag-ui/core` 外部概念）→【未核实】。

## 4. 前端工具

- 注册：【已核实：`packages/react-core/src/v2/hooks/use-frontend-tool.tsx`】`useLayoutEffect` 内 `copilotkit.addTool(tool)` + `addHookRenderToolCall({name,args:tool.parameters,render})`；重名 warn 并覆盖，卸载只移除工具不移除 renderer（历史消息仍可渲染）。类型见 `packages/core/src/types.ts`（`FrontendTool`：`type:"frontend"|"human-in-the-loop"`、`name/description/parameters:StandardSchemaV1/handler/followUp/reconnectBehavior/agentId/available/webmcp`）。
- 让模型知道：`RunHandler.buildFrontendTools(agentId)`（`run-handler.ts:1628`）过滤 `"*"`、`available===false`、agentId 不匹配、Inspector 禁用者，映射成 `{name,description,parameters:createToolSchema(tool)}` 放进 `RunAgentInput.tools`。服务端把它们并入模型工具集（`agent/index.ts:1366`）。
- 路由执行：【已核实：`run-handler.ts:853` `processAgentResult`】扫 assistant 消息的 `toolCalls` → `getTool({toolName, agentId})` → 精确名，否则 `"*"` 通配（通配参数被包成 `{toolName,args}`）→ `handler(args,{toolCall,agent,signal,isReplay})`；`parseToolArguments` 把 `""/null/undefined` 归一为 `{}`。
- **结果回传：不是独立端点**——结果 `splice` 成 `role:"tool"` 消息插到 assistant 消息之后（:1300），若 `followUp!==false` 则递归 `runAgent()` 再发一次 `/run`，消息列表带上 tool result。服务端也可先给占位结果 `"Forwarded to client"`（`core/src/core/tool-result-content.ts:7`），客户端用真结果替换占位（`__tests__/core-frontend-tool-placeholder.test.ts`）。工具卡：`useMathRenderToolCall` 三态 `InProgress/Executing/Complete`；HITL 工具在 `Executing` 态注入 `respond`（v1 的 `renderAndWaitForResponse` 已被 `useHumanInTheLoop` 取代：【已核实：`packages/react-core/src/v1-deprecated/hooks/use-human-in-the-loop.ts:65`】）。

## 5. 运行时侧

- 端点（后缀匹配）：【已核实：`packages/runtime/src/v2/runtime/core/fetch-router.ts:61-272`】`agent/:id/run`、`agent/:id/connect`、`agent/:id/suggest`、`agent/:id/stop`、`threads/{list,subscribe,clear,update,archive,:id/messages|events|state}`、`memories/{list,recall,subscribe,:id}`、`cpk-debug-events`、`info`、`transcribe`、`inspector/*`、`trajectory/:id/connect`。
- SSE handler：【已核实：`handlers/sse/run.ts:20` → `handlers/shared/sse-response.ts:57`】`createSseEventResponse`：`TransformStream` + `new EventEncoder().encodeBinary(event)` 写 `data:` 帧，`keepAliveSse` 心跳（`sse-keep-alive.ts`），`request.signal` abort → `subscription.unsubscribe()`；事件源是 `runtime.runner.run({threadId,agent,input})` 返回的 `Observable<BaseEvent>`（`runner/agent-runner.ts` 抽象类、`runner/in-memory.ts` 本地实现）。
- LLM 流 → AG-UI：【已核实：`packages/runtime/src/agent/index.ts:1224` `BuiltInAgent.run()`】先同步 emit `RUN_STARTED`，再 `streamText()`（Vercel AI SDK），`for await (const part of response.fullStream)` 逐 part 映射（:1743）：`reasoning-*`→`REASONING_*`、`text-delta`→`TEXT_MESSAGE_CHUNK`、`tool-call`→`TOOL_CALL_START/ARGS/END`（含 `started/hasArgsDelta/ended` 状态机）、`tool-result`→`TOOL_CALL_RESULT`（状态工具先翻成 STATE 事件）、`tool-error`→`TOOL_CALL_RESULT "Error: ..."`、`finish`→`RUN_FINISHED`(+usage/outcome)、`error`→`RUN_ERROR`；`converters/{aisdk,tanstack,usage}.ts`。
- 工具循环：**委托给 AI SDK**，`stopWhen: config.maxSteps ? stepCountIs(maxSteps) : undefined`（有 skills 时默认 `stepCountIs(10)`）；工具集 = `input.tools`(客户端) + `config.tools` + `AGUISendState*` + MCP（`config.mcpClients`/`mcpServers`，`mergeMCPTools` 保重名不互相覆盖）+ learned skills。
- tool filtering / 渐进披露：【已核实：`index.ts:1193` `getCapabilities()`】默认 `{tools:{supported:true,clientProvided:true}, transport:{streaming:true}, humanInTheLoop:{interrupts:true}}`，`config.capabilities` 按类别浅合并；经 `/info` 下发（`handlers/get-runtime-info.ts:133`）供客户端 `core/src/agent.ts:266` 读取。**真正的渐进披露在 `packages/runtime/src/agent/learned-skills.ts`**：`prepareLearnedSkills` 把 skills catalog 作为 system message 注入（`index.ts:1529`），只暴露两个保留工具 `copilotkit_load_skill`/`copilotkit_read_skill_file`（`assertNoSkillToolConflicts` 冲突即抛），模型按需拉取 SKILL.md。客户端侧过滤在 `buildFrontendTools`（available/agentId/Inspector 禁用）。

## 6. 记忆

- 分层与词表：【已核实：`packages/core/src/memory.ts:76-120`】`MemoryKind = "topical"|"episodic"|"operational"`、`MemoryScope = "user"|"project"`；`Memory{id,kind,scope,content,sourceThreadIds,invalidatedAt,score}`；supersede 是全量替换而非 patch。
- 存储：**服务端（Intelligence 平台）**，浏览器只持 client store。相对 runtimeUrl 的路径 `/memories`、`/memories/subscribe`、`/memories/recall`（`memory.ts:55-57`）；404/422/501 视为"未配置"（422 = MISSING_INTELLIGENCE）；实时靠 Phoenix channel `user_meta:memories:<code>`（`core/src/utils/phoenix-observable.ts`），状态 `MemoryRealtimeStatus = connecting|connected|unavailable`。服务端 threads/memories 端点全部 re-export Intelligence 实现（【已核实：`handlers/handle-threads.ts`、`handle-memories.ts`】只剩 re-export）。
- 注入方式：**不是塞进 messages/context，而是 MCP 工具**。【已核实：`handlers/shared/agent-utils.ts:274`】`attachIntelligenceEnterpriseLearning` 用 `@ag-ui/mcp-middleware` 把 Intelligence 的 MCP server（Memory 工具按 grant 每 scope 一个）挂到 per-request agent clone；grant 由 `resolveWebMemory`/`runtime.memory.access({request,user,consumer})` 决定（`handlers/shared/memory-policy.ts`），all-none = 本次 run 无 Memory 但对话继续。客户端 `useMemories`（`hooks/use-memories.tsx`）用 `useSyncExternalStore` 订阅 `ɵselectMemories*`；store 由 micro-redux 构建。threads 同理（`core/src/threads.ts`，`THREADS_CHANNEL_EVENT="thread_metadata"`，本地 runner 可选 `ɵsupportsLocalThreadEndpoints`）。

## 7. 运行轨迹与回放

- 【已核实：`packages/shared/src/event-transforms/recorded-events.ts`】`transformRecordedEvents(input, events, {a2ui?, openGenerativeUI?})`：用 `RecordedEventAgent extends AbstractAgent`（`run()` 只把录制事件转成 Observable，"cannot contact an agent or execute tools"）套上 `OpenGenerativeUIMiddleware` / `A2UIMiddleware`（先 A2UI 后 OGUI，第一个 middleware 最外层），再 `firstValueFrom(agent.run(input).pipe(toArray()))` 返回加工后事件序列。用于回归/调试；调用方负责 ID/时间戳；**不可二次加工**。测试 `recorded-events.test.ts`。
- 【已核实：`packages/shared/src/debug-event-envelope.ts`】`DebugEventEnvelope{timestamp,agentId,threadId,runId,event}`；服务端 `DebugEventBus`（`runtime/v2/runtime/core/debug-event-bus.ts`）在 SSE 每个事件后广播——**即使目标客户端已断开仍广播给调试订阅者**（`sse-response.ts:193`）。`/cpk-debug-events` SSE 端点（`handle-debug-events.ts`）仅在 `runtime.debug` 开启或 `NODE_ENV==="development"` 时提供，否则 404（授权决策，非环境细节）。另有 `handle-trajectory-connect.ts` 供 Inspector。
- 【已核实：`packages/core/src/utils/connect-replay.ts:69`】`ɵconnectWithoutEventVerification` 刻意跳过 AG-UI 的 `verifyEvents`/`enforceEvents`——`/connect` 回放天然含多个历史 run（含 RUN_ERROR 后又 RUN_STARTED），并保留 `transformChunks` 与 `ConnectionReplayLifecycle`。

## 8. token 计量、多模态、错误

- 计量：【已核实：`packages/runtime/src/agent/converters/usage.ts`】`AgentRunUsage{provider,model,inputTokens,outputTokens,totalTokens,reasoningTokens,cachedInputTokens}`；`aggregateRunUsage` 按 provider+model 分组求和；`collectStandardRunFinishedDetails` 取最新 metadata 并保留最后一次 `finishReason`，最终挂在 `RUN_FINISHED`（`agent/index.ts:2040 getAISDKRunFinishedDetails`）。
- 多模态：【已核实：`packages/shared/src/types/message.ts`】re-export `@ag-ui/core` 的 `InputContent/Image|Audio|Video|DocumentInputPart`，`TextInputContent as TextInputPart`；`ImageData` 与 0.x 的 `binary` part 已 deprecated。工具结果可带媒体：`toToolResultContent`（`core/src/core/tool-result-content.ts`）对"非空且全是合法 `ContentPart`"的数组原样透传，否则 JSON 字符串；`isContentPart` 手工镜像 AG-UI schema（有测试比对 schema）。runtime 侧有 `upgradeLegacyBinaryParts` 兼容升级（`agent-utils.ts:371`）。
- 错误：AG-UI `RUN_ERROR` 事件由 subscriber 收敛（用户主动 stop 产生的 abort 型 RUN_ERROR 被主动忽略，`run-handler.ts:1729`）；客户端枚举【已核实：`packages/core/src/core/core.ts:135`】`CopilotKitCoreErrorCode`：`runtime_info_fetch_failed / agent_connect_failed / agent_run_failed(_event) / agent_run_error_event / tool_argument_parse_failed / tool_handler_failed / tool_not_found / agent_not_found / agent_thread_locked / transcription_* / subscriber_callback_failed`，经 `onError({code,error,context})` 上报；v1 侧另有 `shared/src/types/error.ts` 的 `CopilotErrorEvent/CopilotRequestContext`。

## 9. 归约器

- 事件 → 消息列表：**核心算法在外部 `@ag-ui/client`**（`AbstractAgent.apply()/processApplyEvents()` + `transformChunks`），本仓库只是调用方：【已核实：`core/src/utils/connect-replay.ts:177-183`、`core/src/agent.ts:645,695`（`transformHttpEventStream(runHttpRequest(...))`）】。归约算法本体 →【未核实：`@ag-ui/client@1.0.2` 未检出】。
- CopilotKit 自己的"元数据归约"：【已核实：`packages/core/src/core/state-manager.ts:141 subscribeToAgent`】订阅 `onRunStartedEvent/onRunFinishedEvent/onRunErrorEvent/onRunFailed/onRunFinalized/onCustomEvent/onStateSnapshotEvent/onStateDeltaEvent/onTextMessageStartEvent/onMessagesSnapshotEvent/onNewMessage/onMessagesChanged/onToolCallResultEvent`，维护 `stateByRun / messageToRun / rawEventByMessage / activeRun`，并用 `revoked` 标记处理"pipeline 捕获订阅数组"导致的旧订阅回放（subRunId 重算：continuation→原 runId，RUN_FINISHED 后的新 run→新 UUID）。
- 乱序/重复/断线重连：重复 → `reconcilePendingResults`（按 toolCallId 找 owner assistant 消息、删重复 tool 消息、占位替换、按序插入）+ `_executingToolCalls` + `ɵrepairToolCallPairs`；续接判定 → `getContinuationRunId` 用 interruptId 集合精确匹配（不匹配就当新 run，`approval-resume.test.ts:165-177`）；重连 → `/connect`（body 额外 `lastSeenEventId`，`agent-utils.ts:40,477`），客户端用 `_lastConnectedThreadIdsByAgent/_lastActiveThreadIdsByAgent` 区分"换 thread 的全量重放"与"同 thread churn 重连"（后者不清 messages/state，`run-handler.ts:255-258,559-580`），并有 `clearReplayCursor`/`clearReconnectCursor`（`core/src/agent.ts:742`）；Intelligence 模式用 `lastSeenEventIds` Map + 自定义长连接（`intelligence-agent.ts:948,963,1000`）。**`phoenix-observable.ts` 不用于事件流**，只用于记忆/线程的实时元数据。

---

## 对后端-only AI Runtime + Vue3 前端的可移植性判断

**纯协议思想，可直接移植（与 React 无关）**

1. **HITL = "新请求 + resume 数组"**：中断信息随终止事件下发（AG-UI 1.0：`RUN_FINISHED.outcome={type:"interrupt",interrupts[]}`，或 legacy 自定义事件 `on_interrupt`），恢复走同一 `/run` 端点、body 加 `resume:[{interruptId,status:"resolved"|"cancelled",payload?}]`。这是 AG-UI 协议面，任何前端可实现；Vue3 只需一个 `useInterrupt` 等价 composable：订阅事件 → 持有 open interrupts → 收集完所有 response → 一次性 POST。
2. **前端工具 = "工具声明随请求上行 / 结果随下一次请求上行"**：`RunAgentInput.tools` 携带 `{name,description,parameters(JSON Schema)}`；客户端执行后把 `role:"tool"` 消息并入 `messages` 再次 POST。无专用回传端点，天然可移植。`"Forwarded to client"` 占位哨兵也是纯字符串约定。
3. **上下文注入 = 每次 run 全量重发**（`RunAgentInput.context[]` + `state`），服务端拼 system prompt；无需长连接或注册。仅需注意可裁剪（对应 `CopilotKitMessageFilter`/`ɵrepairToolCallPairs` 的"配对完整性"约束）。
4. **状态同步 = STATE_SNAPSHOT / STATE_DELTA(JSON Patch) 事件下行 + `state` 上行**，"服务端用 `AGUISendStateSnapshot/Delta` 工具驱动"只是提示词层约定，可照搬。
5. **SSE 帧格式**：`data: <JSON event>\n\n` + `: keep-alive` 注释心跳 + 客户端 abort → 服务端 unsubscribe；`/connect` 全量重放 + `lastSeenEventId` 游标断点续传。均可移植（Vue3 用 `EventSource`/fetch-stream）。
6. **渐进披露**：skills catalog 入 system prompt + 只暴露 `load_skill`/`read_skill_file` 两个"元工具"，是纯 prompt/tool 设计，强烈建议照搬。
7. **生成式 UI 的数据模型**：活动消息（activity message）承载"流式增量 + JSON Patch 数组"，以及"解析中工具参数用增量 JSON parser 边解析边渲染"。Vue3 完全可实现（`partialJSONParse` 等价物）。
8. **记忆**：不注入上下文、而是作为 MCP/工具暴露给模型 + 前端只做 REST+实时通知的 store —— 纯后端设计，直接适用。
9. **调试/回放**：`DebugEventEnvelope` 信封 + 广播式调试 feed（`/cpk-debug-events`）+ "录制事件离线过中间件"（`transformRecordedEvents` 的惰性 `RecordedEventAgent`）是框架无关的测试/调试模式。

**被 React 绑死（需重写，但语义可保留）**

1. hook 生命周期即注册/注销：`useFrontendTool`/`useHumanInTheLoop`/`useAgentContext` 都在 `useLayoutEffect` 注册、依赖数组驱动重注册，Vue3 需换成 `onMounted/onUnmounted/watch`（注意 `useHumanInTheLoop` 特意用 layout effect 与 `useFrontendTool` 对齐时序——Vue 里要保证"注销旧实例晚于注册新实例"的顺序不被破坏）。
2. `respond` 注入渲染 props 的形态、`ToolCallStatus` 三态联合类型与渲染器注册表（`ReactToolCallRenderer`/`defineToolCallRenderer`/`ReactFrontendTool.render?: ComponentType`）是 React 组件类型；Vue3 需以 slot/组件 + props 三态重构，`zod/StandardSchema`→JSON Schema 部分可复用。
3. `useSyncExternalStore`（`useMemories`/`useThreads`）与 `useAgent` 的 `throttleMs` 重渲染模型：Vue3 用 `shallowRef + watchEffect` 重写；但底层 micro-redux store（RxJS + 自研 reducer/selector/effects）本身**框架无关，可直接复用**，只是 `phoenix-observable` 依赖 Phoenix Socket。
4. `OpenGenerativeUIRenderer` 的 iframe 沙箱与 `SandboxFunctionsContext`、`A2UIMessageRenderer` 的 `requestAnimationFrame` 交叉淡入（loader→surface 无闪烁切换）都是 React 渲染时序技巧；沙箱本身（websandbox）可移植，时序技巧需在 Vue 里重新设计。
5. A2UI 的组件目录/渲染器在 `@copilotkit/a2ui-renderer`（React 专版，未检出）；若目标端是 Vue3，需要等价的 Vue A2UI 渲染器——**这是移植成本最高的一块**，`【未核实】`其内部实现细节（本仓库不含）。

**与文档摘录的冲突点（以源码为准）**：文档把 HITL 描述为可由 `interrupt()`/`resolve()` 恢复"暂停的 run"，本仓库源码显示传输层实际是**新发一次 `/run`**（新 runId）+ 客户端重盖章归并（`state-manager.ts:80-98`、`run-handler.ts:803`）；文档 1.4 的"client 端延迟 500ms 再 resolve"只是示例规避 React commit 时序，非协议要求（`useInterrupt` 源码在 `resolve` 内先 `agent.addMessage` 再 `runAgent`，顺序敏感）。
