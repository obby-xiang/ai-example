> **历史分析 / 过程证据 — 非现行基线，非验收结论**
>
> - 归档：2026-10-26 · `docs/evidence/history-recovery-2026-10-26/wire-only/`
> - 来源：Kimi Code 会话 `<KIMI_SESSION>` / 子 agent `agent-53` 的 wire 末条 assistant 文本（只读流式解析；非原始会话转写）
> - 原始体量：19923 B / 14263 字符 / 118 行；行指纹覆盖率（仓内 + 落盘语料）1.1%
> - 类别：**正式独立报告**
> - 脱敏：路径 0 / 会话标识 0 / 身份 0 / 密钥 0
> - 正文为该条消息的完整文本，未删段、未改写结论；仅脱敏与行尾空白规整。当时结论不代表当前代码状态。
# Vercel AI SDK（commit e7f55a4 / ai@7.0.130）源码级机制挖掘

## 1. 流协议（UIMessage 流 SSE 线格式）

**现行格式 = JSON-in-SSE，不是旧 `'0:'/'2:'/'8:'/'b:'` 前缀格式**（7.0 仓库内无任何旧格式痕迹）。每帧 `data: <JSON>\n\n`，无 `event:`/`id:` 字段，结尾 `data: [DONE]\n\n`。【已核实：`packages/ai/src/ui-message-stream/json-to-sse-transform-stream.ts`】响应头固定为 `content-type: text/event-stream`、`cache-control: no-cache`、`connection: keep-alive`、`x-vercel-ai-ui-message-stream: v1`、`x-accel-buffering: no`。【已核实：`packages/ai/src/ui-message-stream/ui-message-stream-headers.ts`】

全部 chunk（zod union + TS 联合类型，共 29 类）【已核实：`packages/ai/src/ui-message-stream/ui-message-chunks.ts`】：

| chunk | 关键字段 |
|---|---|
| `start` | `messageId?`、`messageMetadata?` |
| `finish` | `finishReason?`(stop/length/content-filter/tool-calls/error/other)、`messageMetadata?` |
| `message-metadata` | `messageMetadata`（唯一必填 metadata 的帧） |
| `text-start/delta/end` | `id`；delta 加 `delta` |
| `reasoning-start/delta/end` | `id`；delta 加 `delta` |
| `tool-input-start` | `toolCallId`、`toolName`、`dynamic?`、`title?`、`providerExecuted?`、`toolMetadata?` |
| `tool-input-delta` | `toolCallId`、`inputTextDelta` |
| `tool-input-available` / `tool-input-error` | `toolCallId`、`toolName`、`input`（error 加 `errorText`） |
| `tool-approval-request` | `approvalId`、`toolCallId`、`approvalDescriptor?`、`inputSchemaInput?`、`reason?`、`isAutomatic?`、`signature?` |
| `tool-approval-response` | `approvalId`、`approved`、`reason?` |
| `tool-output-available` | `toolCallId`、`output`、`preliminary?` |
| `tool-output-error` | `toolCallId`、`errorText` |
| `tool-output-denied` | `toolCallId` |
| `source-url` / `source-document` | `sourceId`、`url` / `mediaType`、`title`、`filename?` |
| `file` / `reasoning-file` | `url`、`mediaType` |
| `custom` | `kind`（`` `${string}.${string}` ``） |
| `data-<name>` | `id?`、`data`、`transient?` |
| `start-step` / `finish-step` / `reset-step` | 无字段（reset-step 删除本步 parts） |
| `error` | `errorText` |
| `abort` | `reason?` |

- **start 帧可带 message metadata**（`messageMetadata`，客户端 mergeObjects 合并进 `message.metadata`，可多次发）。【已核实：`packages/ai/src/ui/process-ui-message-stream.ts:441-462,958-987`】
- **心跳保活存在但默认关闭**：`createUIMessageStreamResponse({keepAliveMs})` → 流首插 `: stream-open\n\n`，随后空闲时发 SSE 注释帧 `: keep-alive\n\n`（仅在 `desiredSize>0` 时发）。示例与文档均未传该参数。【已核实：`create-sse-stream-with-keep-alive.ts`、`create-ui-message-stream-response.ts`、`ui-message-stream-response-init.ts`】
- **文档缺口（以源码为准）**：`50-stream-protocol.mdx` 未收录 `tool-input-error`、`tool-output-error`、`message-metadata` 三类（schema 中存在）。

## 2. HITL 工具审批

- **暂停发生在工具执行前、但不抛错**。循环继续的判据是 `clientToolCalls.length === clientToolOutputs.length + deniedApprovals.length`；`user-approval` 的工具既不执行也无 output，所以本轮不再 streamStep，直接发 `finish` 结束——**不是抛 ToolApprovalError 终止，而是"本轮正常结束"**。【已核实：`packages/ai/src/generate-text/execute-tools-from-stream.ts:154-167`、`stream-text.ts:3119-3157`】
- 配置面：`toolApproval`（`GenericToolApprovalFunction` 或按工具 map），四态 `not-applicable/approved/denied/user-approval`；工具自身的 `needsApproval` 仍被 `resolveToolApproval` 读取但文档标为 deprecated。【已核实：`tool-approval-configuration.ts`、`resolve-tool-approval.ts`、`content/docs/04-ai-sdk-ui/03-chatbot-tool-usage.mdx:410`】
- **拒绝/通过后的流程 = 前端重发带审批结果的新请求**（这是硬事实）：服务端把流上的 `tool-approval-response` 与历史 assistant 消息配对（`collectToolApprovals`/`validate-tool-approvals`），批准则执行 `execute` 并把结果作为 `tool-result` 回填；拒绝则写入 `{type:'execution-denied', reason}` 给模型，UI 侧对应 `tool-output-denied` → `part.state='output-denied'`。【已核实：`collect-tool-approvals.ts`、`stream-text.ts:2160-2223`、`process-ui-message-stream.ts:785-842`】
- 客户端：`part.state='approval-requested'` → `addToolApprovalResponse({id,approved,reason})` 本地改 `approval-responded` → `sendAutomaticallyWhen: lastAssistantMessageIsCompleteWithApprovalResponses` 自动重发（或手动 `sendMessage()`）。自动批准/拒绝也走同样状态，但 `approval.isAutomatic===true`，无需人工响应。【已核实：`packages/ai/src/ui/chat.ts:544-614`、`ui/last-assistant-message-is-complete-with-approval-responses.ts`、`ui-messages.ts:298-397`】
- 安全：`experimental_toolApprovalSecret` 对审批请求做 HMAC（绑 toolCallId+toolName+input），回放验签；错误类 `InvalidToolApprovalError`/`InvalidToolApprovalSignatureError`/`ToolCallNotFoundForApprovalError`。【已核实：`tool-approval-signature.ts`、`error/*approval*`】
- **示例冲突**：`examples/ai-e2e-next/app/api/chat/human-in-the-loop/` 用的是**老式"无 execute 的客户端工具"**（`addToolOutput({output:APPROVAL.YES})` + `sendMessage()`），不是 approval 状态机；approval 状态机的 e2e 只在 `api/chat/tool-approval`（agent 版）与文档中出现。【已核实：`examples/.../human-in-the-loop/page.tsx`、`utils.ts`、`tool-approval/route.ts`】

## 3. 可恢复运行（重点）

- 客户端链路：`useChat({resume:true})` → mount 时 `registerAutomaticResume` → `chat.resumeStream()` → `transport.reconnectToStream()` → **GET `/api/chat/{encodeURIComponent(id)}/stream`**；`204` 视为无活跃流 → 返回 `null`，状态保持 `ready`。`id==='.'|'..'` 被拒。【已核实：`packages/react/src/use-chat.ts:65-95,275-282`、`packages/ai/src/ui/chat.ts:530-532,791-844`、`http-chat-transport.ts:232-307`】
- **不存在 stream-id 头、`Last-Event-ID`、`lastPartId`、offset 查询参数**（全仓 grep 无命中）。核心重连请求不带任何游标。【已核实：`http-chat-transport.ts`、全仓 grep】
- 服务端原理（`resumable-stream` 为外部 npm，已核实其 GitHub main 源码）：
  - Redis 结构：哨兵 key `resumable-stream:rs:sentinel:<streamId>`（活跃值 `"1"`，结束置 `"DONE"`，两者均 `EX 24*60*60`）；pub/sub 频道 `...:request:<streamId>` 与 `...:chunk:<listenerId>`。**流内容不存在 Redis 里**，而是生产者进程内 `chunks[]` 累积，新订阅者加入时 `chunks.join('').slice(skipCharacters)` 一次性补发，再实时转发。
  - 续传游标是 **`skipCharacters`（SSE 文本字符偏移）**，`resumeExistingStream(streamId, skipCharacters?)` 的函数参数。AI SDK 官方示例调用 `resumeExistingStream(streamId)` 不传参数 → **每次恢复都从字符 0 整段重放**。【已核实：`examples/.../resume/[id]/stream/route.ts`；外部源码 [resumable-stream/src/runtime.ts](https://raw.githubusercontent.com/vercel/resumable-stream/main/src/runtime.ts)】
- **客户端补发算法（回答"是不是按 messageId/partId 去重"）：否。是"整段重放 + 覆盖式重建"**：
  - `resume-stream` 分支默认 `createStreamingUIMessageState({lastMessage: undefined})` 建**全新空 assistant 消息**（唯一例外：最后一条 assistant 里存在 `input-streaming`/`approval-responded` 的工具 part 时快照复用，以便重放的工具结果能命中该 tool call）。写入时若新消息 id 等于最后一条消息 id 就 `replaceMessage(末尾)`，否则 push。【已核实：`packages/ai/src/ui/chat.ts:857-960`】
  - `processUIMessageStream` 的 `resetStateOnMessageIdChange=true`（仅 resume 时）：`start.messageId !== state.message.id` 时整个 state 重置为空，避免把两个响应拼进一条消息。【已核实：`ui/process-ui-message-stream.ts:123,958-975`】
  - 测试佐证：`should not copy the previous assistant message when resuming a stream`（新 id → 追加为新消息）、`should resume an approved tool result in the existing assistant message`（同 id → 落在原消息）。【已核实：`packages/ai/src/ui/chat.test.ts:2113-2326`】
  - 风险点：**网络异常断开时 `resumableStreamState` 会被保留并复用（同一 state 对象）**，此时服务端又从字符 0 重放，代码里没有按 partId/消息 id 的去重逻辑，重复 part 的可能性属推断（未见覆盖此场景的测试）。【已核实代码路径：`chat.ts:1016-1034`；重复后果为推断】
- **文档 vs 示例冲突（以源码为准）**：`03-chatbot-resume-streams.mdx` 的 GET 用 `activeStreamId` 单值 + 返回 `UI_MESSAGE_STREAM_HEADERS`；仓库示例用 `appendStreamId/loadStreams` 列表取最后一个，且 `new Response(resumedStream)` **不带任何 SSE 头**。两者都在仓库内，示例为准。【已核实：`examples/.../resume/[id]/stream/route.ts` vs `content/docs/04-ai-sdk-ui/03-chatbot-resume-streams.mdx:172-199`】
- **abort 与 resume 的关系**：现行文档（已核实）明确 `stop()` 只是断开；要真停必须自建 stop 端点（持久化部分消息 + 取消生产 + 比对 activeStreamId 后清空）。文档摘录里"abort 与 resume 不可共存（troubleshooting 页）"**该页未检出，无法源码核实**，且已被上述更细的现行文档取代，应视为过时约束。

## 4. Chat 状态机与 useChat

- 状态：`submitted | streaming | ready | error`；单一状态源，`SerialJobExecutor` 串行化每个 chunk 的应用以及 `addToolOutput`/`addToolApprovalResponse`，避免并发写。【已核实：`packages/ai/src/ui/chat.ts:243-297,924-962`】
- 并发请求：resume 会 abort 上一个 `activeResumeRequest`，并用 `isCurrentRequest()` 丢弃过期结果（测试 `should only apply the latest overlapping resumed stream`）。【已核实：`chat.ts:769-844`、`chat.test.ts:1291`】
- `stop()` = abort `pendingMessagePreparations` / `activeResumeRequest` / `activeResponse` 三个 controller，**不通知服务端停止生成**（服务端无 onAbort 回调通道，取消语义见 `ui-message-stream-cancellation.node.test.ts`：客户端 abort ≠ 取消生成）。【已核实：`chat.ts:702-708`】
- `onFinish({message, messages, isAbort, isDisconnect, isError, finishReason})`：`isDisconnect` 靠 `TypeError` 文案匹配（fetch/network/load failed）判定，并据此保留 `resumableStreamState`。`onError`、`onData`、`onToolCall`（仅 `input-available` 且非 providerExecuted 时触发）、`sendAutomaticallyWhen`（流结束、加工具结果、审批后可递归发起下一轮）。【已核实：`chat.ts:999-1071,161-241`】
- React 接入：不用 `useSyncExternalStore`（注释解释会同步流式更新、干扰导航）；自管 `useState` 快照 + 三个订阅（messages 走 `throttle(waitMs)`，status/error 不节流），`chat.react.ts` 每次写 state 都回调并用 `snapshot()` 复制 parts 保证引用可比较，`equalChatSnapshots` 引用短路减少渲染。【已核实：`packages/react/src/use-chat.ts:196-259`、`packages/react/src/chat.react.ts`、`packages/react/src/throttle.ts`】

## 5. 工具循环

- `stopWhen` 为数组，**默认 `isStepCount(1)`**（不显式配置就只跑一步）；工具内建 `isStepCount`/`isLoopFinished`/`hasToolCall`，`isStopConditionMet` = any()。【已核实：`stream-text.ts:423,888`、`stop-condition.ts`】
- 继续下一 step 的条件（关键算式）：本步 `clientToolCalls.length === clientToolOutputs.length + deniedApprovalResponses.length` 且（有 client tool 调用或存在 `pendingDeferredToolCalls`）且 `!isStopConditionMet(...)`；provider 延后结果（programmatic tool calling）经 `pendingDeferredToolCalls` 追踪。【已核实：`stream-text.ts:3068-3157`】
- `prepareStep`：每步前可改 `model/messages/instructions/system/toolChoice/activeTools/toolOrder/providerOptions/runtimeContext/toolsContext/sandbox`。【已核实：`stream-text.ts:2342-2436`】
- **上下文管理与大工具结果截断：本仓不存在**。grep 无 `experimental_contextManagement`、`retainLastAssistantMessages`、任何 truncate 逻辑（唯一 truncat 命中在 realtime）。可用手段只有调用方显式使用 `pruneMessages({reasoning, toolCalls:'all'|'before-last-message'|'before-last-N-messages'|{tools}, emptyMessages})` 与 `tool.toModelOutput` 自定义工具结果转模型形态（默认 string→`{type:'text'}`、其它→`{type:'json'}`、错误→`error-text`）。【已核实：`generate-text/prune-messages.ts`、`prompt/create-tool-model-output.ts`】

## 6. 生成式 UI

- **没有 `writeData`/`writeMessageData` 这类便捷方法**：writer 只有 `write(chunk)` / `merge(stream)` / `onError` /（createUIMessageStream 版）`setOutcome`；注入 data 就是 `writer.write({type:'data-xyz', id?, data, transient?})`。【已核实：`ui-message-stream/ui-message-stream-writer.ts`】
- data part 客户端语义：默认按 `type+id` 命中已有 part 则**覆盖 data**（同 id 流式更新），`transient` 只回调 `onData` 不入消息；`onData` 回调 + `dataPartSchemas` 可选校验。【已核实：`process-ui-message-stream.ts:1008-1070`】
- `useObject` 是**独立 hook**（自管 `useState` + `abortControllerRef` + `onFinish/onError`），与 Chat 状态机无关；生成式 UI 主要靠 tool part + data part 在前端 `switch(part.type)` 渲染。【已核实：`packages/react/src/use-object.ts`】
- 多模态工具结果：UI 侧 tool part 的 `output` 是 `unknown`（可承载文件类结构）；转模型侧经 `tool.toModelOutput` 得 `ToolResultOutput`——**其 union 定义在 `@ai-sdk/provider-utils`，本次 sparse checkout 未包含该包，未能枚举**。【部分未核实】另：任务提到的 `toToolResultContentPart` **本仓不存在**。【已核实：全仓 grep 无命中】

## 7. token 计量

- **UI 流协议里没有 usage**：`UIMessageChunk` 的 `finish` 只有 `finishReason` + `messageMetadata`，无 usage/usage 帧。要在前端显示 token，必须走 `messageMetadata` 或自定义 data part。【已核实：`ui-message-chunks.ts`】
- 服务端形态：`stepResult.usage`（单步 `LanguageModelUsage`，含 input/output/total 等）、`streamText` 结果的 `totalUsage`（跨步累加，`addLanguageModelUsage`）、事件侧 `GenerateTextStepEndEvent.usage`、`GenerateTextEndEvent.usage`（+`totalUsage` 已 deprecated）。【已核实：`generate-text/step-result.ts:248`、`stream-text-result.ts:247`、`generate-text-events.ts:249-257`、`stream-text.ts:3062,3153`】

## 8. 遥测 / 轨迹

`packages/ai/src/telemetry/` 提供的是**集成注册模型，不是 OpenTelemetry 实现**（本仓无 otel 包）：`Telemetry` 接口 + `registerTelemetry(...)`（挂到 `globalThis.AI_SDK_TELEMETRY_INTEGRATIONS`）+ `createTelemetryDispatcher`/`createRestrictedTelemetryDispatcher` + `tracing-channel(.publisher)` 内部事件通道 + `TelemetryOptions{isEnabled, recordInputs, recordOutputs, functionId, includeRuntimeContext, includeToolsContext}`。span 生成由外部集成负责。【已核实：`telemetry/{telemetry.ts,telemetry-options.ts,telemetry-registry.ts,create-telemetry-dispatcher.ts}`】

## 9. workflow 包（@ai-sdk/workflow 2.0.62）

- 组成：`WorkflowAgent`（流式/生成 + 工具循环）、`WorkflowChatTransport`、`toUIMessageChunk`/`createModelCallToUIChunkTransform`、`normalizeUIMessageStreamParts`、`Output`。【已核实：`packages/workflow/src/index.ts`】
- **持久化本体不在本仓**：peerDep `workflow@^5.0.0-beta.42`，`import { getWorkflowMetadata } from 'workflow'`；每个模型调用/工具执行是带 `'use step'` 指令的步骤函数（`do-stream-step.ts:55`、`do-generate-step.ts:36`、`workflow-agent.ts` 多处），由 workflow 运行时做 step 级持久化/重试。**"World" 概念在本仓无任何出现，未核实**。【已核实依赖与 `'use step'`；World 未核实】
- **续传语义与核心完全不同：chunk 索引游标**。POST 必须返回 `x-workflow-run-id` 头；重连 GET `{api}/{runId}/stream?startIndex=N`（支持负数尾读，用响应头 `x-workflow-stream-tail-index` 换算绝对位置）；未收到 `finish` 自动重连，最多 3 次连续错误；负游标可能落在 part 中间，故有"孤儿过滤"丢弃无 `*-start` 的 delta/end（否则核心处理器会抛 `UIMessageStreamError`）。【已核实：`packages/workflow/src/workflow-chat-transport.ts:36-101,323-528`】

---

## 与我方（Spring AI 后端 + Vue3）对齐时的可移植性判断

**可移植（建议照搬）**
- SSE 帧约定：`data: {json}\n\n` + `data: [DONE]`，`type` 字段下每类字段名与语义（尤其 `id`/`toolCallId`/`inputTextDelta`/`preliminary`）；`x-vercel-ai-ui-message-stream: v1` 头可作为自描述协议标识。
- part 状态机：tool part 七态 `input-streaming → input-available → approval-requested → approval-responded → output-available/output-error/output-denied`；`text/reasoning` 用流上 id 聚合、`data-*` 按 type+id 覆盖、`transient` 不入历史。
- `start/finish/step-start(step 边界)/reset-step/error/abort` 语义；`message-metadata` 独立帧用于增量合并业务元数据。
- `: keep-alive` 注释帧作为长连接保活（我们可默认开启，比 AI SDK 更主动）。
- 前端渲染契约（Vue3 可用同一 reducer 思路）：串行队列应用 chunk、消息快照不可变替换、messages 节流渲染而 status/error 直通。

**不可移植 / 需自研（关键差异）**
- **恢复机制**：AI SDK 核心是"客户端整段重放 + 覆盖重建"，游标只有 `skipCharacters` 字符偏移且默认不传；我们没有 Redis pubsub + 单进程内存缓冲这套 serverless 约束，直接在 Spring 侧持有会话 + 落库 chunk 游标（索引续传）会更接近 workflow 包的做法，比照搬核心方案更优——即**不要**实现成"每次重连从头重放"，否则必须依赖客户端"整段覆盖"，会撞上我们自己的文本重复问题。
- **审批**：AI SDK 是"两次模型调用 + 无服务端会话态 + 客户端控制消息历史 + HMAC 签名防伪"。若我方已有服务端会话，可做"同轮挂起—恢复（Suspend/Resume）"，省一次模型调用；但对前端仍要保留同一套 approval part 状态与字段名，否则 UI 无法复用。
- `resumable-stream` 的 24h 哨兵 + 事件频道模型、`activeStreamId` 比对清空、stop 端点三件套，属实现细节，不必照搬，但"stop 与会话清理"这条语义必须补齐（我们可直接 Cancel 服务端任务）。
- **上下文管理与大工具结果截断 AI SDK 不提供**，`pruneMessages`/`toModelOutput` 只是钩子；这一块必须自研（工具结果入库/hash 化 + 按需回填），不能指望移植。
- usage 不进流协议；若前端要显示 token，需我方自定义扩展（建议加一条 `data-usage` 或塞入 `messageMetadata`，注意与 AI SDK 客户端兼容性——它会忽略未知 `data-*`）。
- 遥测：只有注册式接口，OTel 集成需自备；我方直接上 Micrometer/OTel，按 `GenerateTextStepEndEvent/End` 的字段粒度（step + usage + tool executions）对齐事件即可。

**文档摘录（`raw-ai-sdk.md`）需要纠正的两点**
1. 流协议 part 清单不全（缺 `tool-input-error`、`tool-output-error`、`message-metadata`、`keepAliveMs` 保活帧）。
2. "abort 与 resume 不可共存"是过时硬约束：现行 `03-chatbot-resume-streams.mdx` 已给出 `stop()` + 专用 stop 端点的兼容方案（该 troubleshooting 页本轮未检出，未核实）。
