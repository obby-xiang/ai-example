# Vercel AI SDK 设计原料摘录（抓取日期 2026-10-07）

## 抓取方式与站点地图概览

**抓取方式（重要，供复现）**：`https://ai-sdk.dev/sitemap.xml` 可用（551 条 URL，HTTP 200，无 JS 渲染问题）。但站点的 **每个文档页都有等价的 Markdown 变体**：在任意文档 URL 后追加 `.md`（或 `.mdx`）即返回 `Content-Type: text/markdown` 的纯文档正文，例如 `https://ai-sdk.dev/docs/ai-sdk-ui/stream-protocol.md` → 14,117 字节 markdown，而 HTML 版本为 847,934 字节（内含大量内联 JSON，不便于摘录）。全部摘录均取自 `.md` 变体，无 404、无反爬拦截。此外站点提供 `https://ai-sdk.dev/llms.txt`（约 4.6 MB，全站文档单文件聚合）、`https://ai-sdk.dev/sitemap.md`（约 123 KB，带 Type/Summary/Prerequisites/Topics 标注的语义索引）、`https://ai-sdk.dev/agents.md`（面向 agent 的发现入口）。注意 `https://ai-sdk.dev/llms-full.txt` 返回 **308/空**，不可用。

**站点地图概览（导航结构一段话）**：站点顶层分为 AI SDK Core（模型调用原语：`generateText`、`streamText`、结构化输出、工具与工具调用、MCP、runtime/tool context）、AI SDK UI（框架无关的 chat 钩子：`useChat`、`useCompletion`、`useObject`，以及 stream protocol、transport、persistence、resume 等协议层文档）、AI SDK RSC（`createStreamableUI` 系列，服务端渲染 React 组件流）、Build Agents（`ToolLoopAgent`、`WorkflowAgent`、loop control、tool approvals、memory、subagents）、Advanced（backpressure、caching、stopping streams、multistep interfaces、rendering UI）、Migrator/Migration Guides（3.1–7.0 及 `5-0-data`）、Providers、Cookbook（node / next / rsc / guides）、Troubleshooting、Reference（`ai-sdk-core` / `ai-sdk-ui` / `ai-sdk-rsc` / `ai-sdk-workflow` / `ai-sdk-errors` / `ai-sdk-tui` 逐函数 API 参考）、Resources/Tools（第三方工具目录）。`Advanced`+`AI SDK UI`+`Build Agents` 三区覆盖了本任务的绝大部分重点。

**版本事实**：现行文档描述的 API 形态为 v5 起的形状（`UIMessage`/`parts`、transport 架构、`inputSchema`、`stopWhen`），Migration Guides 已列到 `7.0`；`ToolLoopAgent` 与 `WorkflowAgent` 属 v6+ 语义。出处：https://ai-sdk.dev/docs/migration-guides/versioning 、https://ai-sdk.dev/sitemap.md

---

## 主题 1：streamText 的工具循环（maxSteps / stopWhen）

### 1.1 `stopWhen` 的语义定义

- 「With the `stopWhen` setting, you can enable multi-step calls in `generateText` and `streamText`. When `stopWhen` is set and the model generates a tool call, the AI SDK will trigger a new generation passing in the tool result **until there are no further tool calls or the stopping condition is met**.」
- **关键边界条件（原文强调两处）**：「The `stopWhen` conditions are only evaluated **when the last step contains tool results**.」——即前一 step 有 tool result 时才评估停止条件。
- 默认行为：「By default, when you use `generateText` or `streamText`, it triggers a **single generation**.」API Reference 给出的默认值是 `Default: isStepCount(1)`。
- step 的定义：「each generation (tool call or text generation) is a step」，即一次 LLM API 调用 = 一个 step。

出处：https://ai-sdk.dev/docs/ai-sdk-core/tools-and-tool-calling#multi-step-calls-using-stopwhen ；https://ai-sdk.dev/docs/reference/ai-sdk-core/stream-text （参数 `stopWhen`，签名 `StopCondition<TOOLS> | Array<StopCondition<TOOLS>>`）

### 1.2 内置停止条件（三个）

| 条件 | 语义 |
| --- | --- |
| `isStepCount(count)` | 已完成步数达到 `count` 时停止（`count: number`） |
| `hasToolCall(...toolNames)` | 任一指定工具被调用时停止 |
| `isLoopFinished()` | 「never triggers, letting the loop run until naturally finished」 |

数组形式：`stopWhen: [isStepCount(20), hasToolCall('someTool', 'done')]`，「The loop stops when it meets **any** condition」。

`isStepCount` 参考页原文：「Creates a stop condition that stops when the number of completed steps **equals** a specified count.」返回值为 `StopCondition` 函数，用于 `generateText` / `streamText` 的 `stopWhen`。

出处：https://ai-sdk.dev/docs/reference/ai-sdk-core/is-step-count ；https://ai-sdk.dev/docs/agents/loop-control#stop-conditions

### 1.3 自定义 `StopCondition`

签名（来自 loop-control）：`StopCondition<typeof tools> = ({ steps }) => boolean`，接收**所有已完成 step** 的信息。

```ts
const hasAnswer: StopCondition<typeof tools> = ({ steps }) => {
  // Stop when the model generates text containing "ANSWER:"
  return steps.some(step => step.text?.includes('ANSWER:')) ?? false;
};
```

自定义条件也可做成本预算判断（示例中通过 `steps.reduce` 累加 `step.usage.inputTokens/outputTokens`）。

出处：https://ai-sdk.dev/docs/agents/loop-control#create-custom-conditions

### 1.4 两步循环的时序定义（原文示例）

1. **Step 1** ①prompt 发给模型 ②模型生成 tool call ③tool call 被执行
2. **Step 2** ①tool result 发回模型 ②模型结合 tool result 生成响应

出处：https://ai-sdk.dev/docs/ai-sdk-core/tools-and-tool-calling#example

### 1.5 Agent 循环的终止条件清单（状态机式枚举）

> The loop continues until:
> - A finish reasoning other than tool-calls is returned, **or**
> - A tool that is invoked **does not have an execute function**, or
> - A tool call **needs approval**, or
> - A stop condition is met

各容器的默认步数上限：`ToolLoopAgent` 默认 `isStepCount(20)`（「safety measure to prevent runaway loops that could result in excessive API calls and costs」）；`WorkflowAgent` **无默认步数限制**，需显式配置。

出处：https://ai-sdk.dev/docs/agents/loop-control

### 1.6 `maxSteps` → `stopWhen` 的迁移事实

- v5 起：「the `maxSteps` parameter has been replaced with `stopWhen`」。
- **`maxSteps` 已从 `useChat` 移除**：「You should now use **server-side** `stopWhen` conditions for multi-step tool execution control, and manually submit tool results and trigger new messages for client-side tool calls.」
- 历史语义（v4）：`maxSteps` 数值上限；v5 等价写法 `stopWhen: isStepCount(5)`，但语义收紧为「Only applies when the last step has tool results」。
- v4 更早的 `maxRoundtrips` 被 `maxSteps` 取代，且「The value of `maxSteps` is equal to roundtrips + 1」。
- 另一处迁移（`DurableAgent` → `WorkflowAgent`）：`maxSteps: 10` → `stopWhen: isStepCount(10)`。

来源与原文片段出处：https://ai-sdk.dev/llms.txt （Migration Guide 5.0 章节「Step Control: maxSteps → stopWhen」「maxSteps Removal」）

### 1.7 逐步回调与 step 结果（API 形态）

- `onStepEnd(result: StepResult)`：一步结束（该步所有 text deltas / tool calls / tool results 已就绪）时触发，**多步时每步触发一次**，回调带零基 `stepNumber`；示例解构出 `{ stepNumber, text, toolCalls, toolResults, finishReason, usage, performance }`。
- `onStepFinish`：Deprecated alias，仅在未提供 `onStepEnd` 时作为 fallback。
- 其它生命周期回调（`streamText`）：`onStart`、`onStepStart`、`onLanguageModelCallStart`（「scoped to model work only and excludes any later client-side tool execution」）、`onLanguageModelCallEnd`（「after the model response has been normalized and parsed, but **before any client-side tool execution** begins」）、`onToolExecutionStart`、`onToolExecutionEnd`、`onChunk`、`onEnd`、`onAbort`、`onError`。
- `onToolExecutionEnd` 的 `toolOutput` 是判别联合：`toolOutput.type === 'tool-result'` 时取 `.output`，`'tool-error'` 时取 `.error`；回调内抛错「are silently caught and do not break the generation flow」。
- 结果属性中与循环相关的：`steps`（每步的 text/toolCalls/toolResults/performance/usage）、`responseMessages`、`finalStep`、`content`、`toolCalls`/`toolResults`、`staticToolCalls`/`dynamicToolCalls`、`usage`（多步为各步之和，与旧版语义不同）、`textStream`、`stream`。

出处：https://ai-sdk.dev/docs/ai-sdk-core/tools-and-tool-calling#onstepend-callback 、#tool-execution-lifecycle-callbacks ；https://ai-sdk.dev/docs/reference/ai-sdk-core/stream-text

### 1.8 `responseMessages`：把多步结果写回会话历史

`generateText` 与 `streamText` 都有 `responseMessages`（`Array<ResponseMessage>` / `Promise<Array<ResponseMessage>>`），是「the accumulated response messages of all steps that were generated during the call」，可直接 push 进会话历史：

```ts
const { responseMessages } = await generateText({ model, messages });
messages.push(...responseMessages); // streamText: ...(await result.responseMessages)
```

出处：https://ai-sdk.dev/docs/ai-sdk-core/tools-and-tool-calling#response-messages

### 1.9 `prepareStep`：逐步改写运行时配置

调用时机：「called **before a step is started**」。参数（原文枚举）：

- `model`、`stopWhen`、`stepNumber`、`steps`
- `instructions`（当前步将要发送的 instructions；若返回 `instructions` override，则「carry forward as the default for later steps」）、`initialInstructions`
- `messages`（「Treat this as the loop's current message state.」返回 override 后成为后续步的基础）、`initialMessages`、`responseMessages`
- `runtimeContext`、`toolsContext`、`experimental_sandbox`

可返回的 override：`model`、`toolChoice`（如 `{ type: 'tool', toolName: 'tool1' }`）、`activeTools`、`instructions`、`messages`、`experimental_sandbox`，以及模型调用参数 `maxOutputTokens`、`temperature`、`topP`、`topK`、`presencePenalty`、`frequencyPenalty`、`stopSequences`、`seed`、`reasoning`（「overrides apply only to the current step」，未提供时用顶层值；`temperature: 0`、`seed: 0`、空 `stopSequences` 等 falsy 值被保留）。

返回 `messages` 的持久化语义（原文）：「Returned message changes **persist across steps**.」若要每步从原始输入重建，则用 `[...initialMessages, ...responseMessages.slice(-10)]`。

`experimental_sandbox` 的特殊语义：只作用于该步的 tool execution，后续步回落顶层值。

出处：https://ai-sdk.dev/docs/ai-sdk-core/tools-and-tool-calling#preparestep-callback ；https://ai-sdk.dev/docs/agents/loop-control#prepare-step

### 1.10 长循环的上下文压缩（context compaction）

`pruneMessages` 出现在 `prepareStep` 内作为内置压缩策略：

```ts
messages: pruneMessages({
  messages,
  reasoning: 'all',
  toolCalls: 'before-last-3-messages',
  emptyMessages: 'remove',
})
```

触发方式示例用 `JSON.stringify(messages).length / 4` 估算 token，阈值 `COMPACTION_THRESHOLD = 100_000`。

出处：https://ai-sdk.dev/docs/agents/loop-control#context-management

### 1.11 强制工具调用与「终止工具」模式

- `toolChoice` 取值：`auto`（默认，模型自选）/ `required`（必须调用工具，可自选哪个）/ `none`（禁止调用）/ `{ type: 'tool', toolName: string }`（必须调用指定工具）。
- 模式：`toolChoice: 'required'` + 一个**没有 `execute` 函数**的 `done` 工具。因为「a tool that has no `execute` function acts as a termination signal. When the agent calls this tool, the loop stops because there's no function to execute」，最终答案从 `result.staticToolCalls` 取出（「contains tool calls that weren't executed」）。

出处：https://ai-sdk.dev/docs/agents/loop-control#forced-tool-calling ；https://ai-sdk.dev/docs/ai-sdk-core/tools-and-tool-calling#tool-choice

### 1.12 手写循环（manual loop）

当需要完全控制时，可用 `generateText` 自己实现循环：

```ts
const messages: ModelMessage[] = [{ role: 'user', content: '...' }];
let step = 0;
const maxSteps = 10;
while (step < maxSteps) {
  const result = await generateText({ model, messages, tools: { /* ... */ } });
  messages.push(...result.responseMessages);
  if (result.text) break; // Stop when model generates text
  step++;
}
```

出处：https://ai-sdk.dev/docs/agents/loop-control#implementing-a-manual-loop

---

## 主题 2：客户端工具（client-side tools）与工具调用的暂停-恢复

### 2.1 三种工具执行模式（官方分类）

> The AI SDK supports three tool execution patterns in this context:
> 1. Automatically executed **server-side** tools
> 2. Automatically executed **client-side** tools
> 3. Tools that require **user interaction**, such as confirmation dialogs

命名差异（术语表用）：`foundations/tools` 把工具分为四类——**Function Tools**（自建 description/inputSchema/execute）、**Dynamic Tools**（输入输出类型运行时未知）、**Provider-Defined Tools**（provider 定义 schema，你实现 execute，文中亦称 "client tools"）、**Provider-Executed Tools**（完全在 provider 服务器执行，文中亦称 "server-side tools"）。本主题的「client-side tool」指 **useChat 语境下、由浏览器执行** 的工具（即 `tools` 中无 `execute` 的定义）。

出处：https://ai-sdk.dev/docs/ai-sdk-ui/chatbot-tool-usage ；https://ai-sdk.dev/docs/foundations/tools#types-of-tools

### 2.2 客户端工具的完整交互流程（9 步，原文）

1. 用户在 chat UI 输入消息
2. 消息发到 API route
3. 服务端 route 中，模型在 `streamText` 调用期间生成 tool calls
4. **所有 tool calls 都被转发到客户端**
5. 服务端工具用其 `execute` 方法执行，结果转发给客户端
6. 需自动执行的客户端工具由 `onToolCall` 回调处理，**必须调用 `addToolOutput` 提供工具结果**
7. 需要用户交互的客户端工具在 UI 中展示；「The tool calls and results are available as **tool invocation parts** in the `parts` property of the last assistant message.」
8. 用户交互完成后，用 `addToolOutput` 把结果加入 chat
9. 可配置 `sendAutomaticallyWhen`，在所有工具结果就绪时**自动提交**，从而「triggers another iteration of this flow」

出处：https://ai-sdk.dev/docs/ai-sdk-ui/chatbot-tool-usage

### 2.3 服务端如何声明客户端工具（无 `execute`）

```ts
const result = streamText({
  model: "anthropic/claude-sonnet-5.5",
  messages: await convertToModelMessages(messages),
  tools: {
    // server-side tool with execute function:
    getWeatherInformation: {
      description: 'show the weather in a given city to the user',
      inputSchema: z.object({ city: z.string() }),
      execute: async ({}: { city: string }) => { /* ... */ },
    },
    // client-side tool that starts user interaction:
    askForConfirmation: {
      description: 'Ask the user for confirmation.',
      inputSchema: z.object({
        message: z.string().describe('The message to ask for confirmation.'),
      }),
    },
    // client-side tool that is automatically executed on the client:
    getLocation: {
      description: 'Get the user location. Always ask for confirmation before using this tool.',
      inputSchema: z.object({}),
    },
  },
});
return createUIMessageStreamResponse({
  stream: toUIMessageStream({ stream: result.stream }),
});
```

**关键事实**：无 `execute` 的工具不会在服务端执行；服务端 route **无需**特殊处理，tool call 被原样写入 UI message stream 转发给客户端。

出处：https://ai-sdk.dev/docs/ai-sdk-ui/chatbot-tool-usage#api-route

### 2.4 客户端侧 API（`useChat`）

```ts
const { messages, sendMessage, addToolOutput } = useChat({
  transport: new DefaultChatTransport({ api: '/api/chat' }),
  sendAutomaticallyWhen: lastAssistantMessageIsCompleteWithToolCalls,
  async onToolCall({ toolCall }) {
    if (toolCall.dynamic) return;              // 先判 dynamic 做类型收窄
    if (toolCall.toolName === 'getLocation') {
      addToolOutput({                          // No await — avoids potential deadlocks
        tool: 'getLocation',
        toolCallId: toolCall.toolCallId,
        output: cities[Math.floor(Math.random() * cities.length)],
      });
    }
  },
});
```

- `onToolCall?: ({ toolCall: ToolCall }) => void | Promise<void>`：「You must call `addToolOutput` to provide the tool result.」
- `addToolOutput(options)`：`{ tool: string; toolCallId: string; output: unknown } | { tool: string; toolCallId: string; state: "output-error"; errorText: string }`
- 错误路径：`state: 'output-error'` + `errorText`；渲染时可用 `isToolOutputErrorUIPart(part)` 类型守卫。
- `sendAutomaticallyWhen?: (options: { messages: UIMessage[] }) => boolean | PromiseLike<boolean>`：「called when the stream is finished **or a tool call is added** to determine if the current messages should be resubmitted」。
- 死锁约束（原文两处重复强调）：`addToolOutput` **不要 `await`**（"No await - avoids potential deadlocks"）。
- `onToolCall` 内的 dynamic 判断：「Always check `if (toolCall.dynamic)` first in your `onToolCall` handler. Without this check, TypeScript will throw an error like `Type 'string' is not assignable to type '"toolName1" | "toolName2"'`」。
- `addToolResult` 是 `addToolOutput` 的 Deprecated 别名。

出处：https://ai-sdk.dev/docs/reference/ai-sdk-ui/use-chat ；https://ai-sdk.dev/docs/ai-sdk-ui/chatbot-tool-usage

### 2.5 工具部件的状态机（`ToolUIPart` 六态判别联合）

静态工具部件类型名为 `tool-${toolName}`；每个状态的字面量定义（节选自 `UIMessagePart` 定义）：

```typescript
type ToolUIPart<TOOLS extends UITools = UITools> = ValueOf<{
  [NAME in keyof TOOLS & string]: {
    type: `tool-${NAME}`;
    toolCallId: string;
  } & (
    | { state: 'input-streaming';  input: DeepPartial<TOOLS[NAME]['input']> | undefined; providerExecuted?: boolean; output?: never; errorText?: never }
    | { state: 'input-available';  input: TOOLS[NAME]['input']; providerExecuted?: boolean; output?: never; errorText?: never }
    | { state: 'approval-requested'; input: TOOLS[NAME]['input']; output?: never; errorText?: never;
        approval: { id: string; approved?: never; descriptor?: unknown; requestReason?: string; reason?: never; isAutomatic?: boolean; signature?: string } }
    | { state: 'approval-responded'; input: TOOLS[NAME]['input']; output?: never; errorText?: never;
        approval: { id: string; approved: boolean; descriptor?: unknown; requestReason?: string; reason?: string; isAutomatic?: boolean; signature?: string } }
    | { state: 'output-available'; input: TOOLS[NAME]['input']; output: TOOLS[NAME]['output']; errorText?: never; providerExecuted?: boolean }
    | { state: 'output-error';     input: TOOLS[NAME]['input']; output?: never; errorText: string; providerExecuted?: boolean }
  );
}>;
```

**暂停-恢复的状态机读法**：`input-streaming` → `input-available` →（客户端/人工提供结果）→ `output-available`（或 `output-error`）；若走审批，则在 `input-available` 后再经 `approval-requested` → `approval-responded` → `output-available` / `output-denied`。文档提示：「Typed tool parts also include the `approval-requested`, `approval-responded`, and `output-denied` states. Include these states when handling `part.state` exhaustively, **even when a tool does not require approval**.」

出处：https://ai-sdk.dev/docs/reference/ai-sdk-core/ui-message#tooluipart ；https://ai-sdk.dev/docs/ai-sdk-ui/chatbot-tool-usage

### 2.6 服务端审批即暂停-恢复（`toolApproval`）

**注意（与「暂停」措辞相反的事实）**：「When a tool requires manual approval, `generateText` and `streamText` **don't pause execution**. Instead, they **complete and return `tool-approval-request` parts** in the result content. This means the manual approval flow **requires two calls to the model**」。

四态取值（字符串或带 `type` 的对象）：

- `'not-applicable'`：正常执行，不带审批元数据（**默认**）
- `'approved'`：记录一次自动批准（同一代内发出 approval request/response parts），然后执行工具
- `'denied'`：记录一次自动拒绝并返回 denied tool output
- `'user-approval'`：发出审批请求并等待显式响应

审批函数返回 `undefined` 等价于 `'not-applicable'`。

**手动审批流程（7 步）**：
1. 带 `toolApproval` 调用 `generateText`/`streamText`
2. 模型生成 tool call
3. 本次调用在 `result.content` 中返回 `tool-approval-request` parts
4. 应用向用户请求审批并收集决定
5. 向 messages 数组加入 `tool-approval-response`
6. 用更新后的 messages **再次调用** `generateText`/`streamText`
7. 批准则执行并返回结果；拒绝则模型看到拒绝并相应回应

```ts
const approvalResponses: ToolApprovalResponse[] = [];
for (const part of result.content) {
  if (part.type === 'tool-approval-request' && !part.isAutomatic) {
    approvalResponses.push({
      type: 'tool-approval-response',
      approvalId: part.approvalId,
      approved: true,
      reason: 'User confirmed the file can be deleted',
    });
  }
}
messages.push({ role: 'tool', content: approvalResponses });
const finalResult = await agent.generate({ messages });
```

**`prepareCall` 可按请求配置审批策略**（`ToolLoopAgent` 的 `callOptionsSchema` + `prepareCall` 返回 `toolApproval`）。

**复现/续跑时的完整性校验**：「Approval requests preserve the original schema input in `inputSchemaInput` when it differs from the input presented for approval. Keep this field when persisting `responseMessages` or UI messages」「On continuation, the SDK reconstructs the transformed input and checks that it matches the approved input. It **never replaces the approved input with a different value**.」

**安全模型（原文）**：「In the standard `useChat` pattern, the server rebuilds the conversation from the messages the client sends each turn. The server does not persist conversation state between requests. This means the **message history is client-controlled input**.」→ 敏感工具需 `experimental_toolApprovalSecret`：服务端在签发时对每个审批请求做 **HMAC 签名**，回放时验签；「The signature binds the approval to the exact tool name, tool call ID, and input arguments.」未签名/被篡改的审批在执行前被拒绝（fail-closed）；未配置 secret 时行为向后兼容；「The secret is never sent to the client or included in the stream.」

出处：https://ai-sdk.dev/docs/ai-sdk-core/tools-and-tool-calling#tool-execution-approval ；https://ai-sdk.dev/docs/agents/tool-approvals

### 2.7 客户端审批 UI 与自动续跑

- 需要人工审批时 tool part 的 `state === 'approval-requested'`；自动批准/拒绝也走同一批状态，但 `part.approval.isAutomatic === true`，因此**无需**调用 `addToolApprovalResponse`。
- `addToolApprovalResponse(options: { id: string; approved: boolean; reason?: string })`：`id` 必须匹配 tool call 的 approval id。
- 字段区分：`part.approval.requestReason` = 要求审批的原因（服务端给出）；`part.approval.reason` = 响应时提供的原因；`part.approval.descriptor` = 「opaque application-specific metadata」（来自流上的 `approvalDescriptor`，在 `approval-requested` 与后续承载审批的状态中保留）。
- 审批后自动续跑：`sendAutomaticallyWhen: lastAssistantMessageIsCompleteWithApprovalResponses`；「If nothing happens after you approve a tool execution, make sure you either call `sendMessage` manually or configure `sendAutomaticallyWhen`」。

出处：https://ai-sdk.dev/docs/ai-sdk-ui/chatbot-tool-usage#tool-execution-approval ；https://ai-sdk.dev/docs/agents/tool-approvals#use-with-usechat

### 2.8 工具调用的流式传输（默认开启）

「Tool call streaming is **enabled by default** in AI SDK 5.0」；「partial tool calls are streamed as part of the data stream」，通过 tool part 的 `state` 渲染（`input-streaming` 阶段 `input` 是 `DeepPartial`）。

出处：https://ai-sdk.dev/docs/ai-sdk-ui/chatbot-tool-usage#tool-call-streaming

### 2.9 其它相关协议事实

- **`step-start` part**：「When you are using multi-step tool calls, the AI SDK will add step start parts to the assistant messages.」可用于展示 step 边界。
- **服务端多步 + 客户端工具不可混用**：服务端多步调用「works when **all invoked tools have an `execute` function on the server side**」。
- **工具执行错误默认被遮蔽**：默认显示为 "An error occurred"；但 `providerExecuted: true` 的工具执行错误「bypass this masking and the UI stream's `onError` callback」；字符串错误原样透传，其它错误值用 `JSON.stringify` 序列化；「The original error data is needed when converting persisted UI messages back into model messages, for example to preserve Anthropic tool error codes.」
- **工具执行可返回 `AsyncIterable` 作为 preliminary results**：最后一个值才是最终 tool result（用于执行期间流式上报状态）。
- **工具执行的第二参数**：`{ toolCallId, messages, abortSignal, context/toolContext, experimental_sandbox }`；多步调用时 `messages` 包含所有先前 step 的 text / tool calls / tool results。

出处：https://ai-sdk.dev/docs/ai-sdk-ui/chatbot-tool-usage ；https://ai-sdk.dev/docs/ai-sdk-core/tools-and-tool-calling#preliminary-tool-results 、#tool-execution-options

---

## 主题 3：useChat 消息协议（UIMessage / parts）

### 3.1 `UIMessage` 的定义与原话

> `UIMessage` serves as the **source of truth for your application's state**, representing the complete message history including metadata, data parts, and all contextual information. In contrast to `ModelMessage`, which represents the state or context **passed to the model**, `UIMessage` contains the full application state needed for UI rendering and client-side functionality.

```typescript
interface UIMessage<
  METADATA = unknown,
  DATA_PARTS extends UIDataTypes = UIDataTypes,
  TOOLS extends UITools = UITools,
> {
  id: string;                                     // A unique identifier for the message.
  role: 'system' | 'user' | 'assistant';
  metadata?: METADATA;
  parts: Array<UIMessagePart<DATA_PARTS, TOOLS>>;  // Use this for rendering the message in the UI.
}
```

三个泛型参数：`METADATA`（自定义消息元数据）、`DATA_PARTS`（自定义 data part 类型）、`TOOLS`（类型安全的工具定义）。

**双消息模型**：`UIMessage`（应用/UI 状态，后端渲染与客户端逻辑用）↔ `ModelMessage`（发给模型的状态/上下文）。转换函数：`convertToModelMessages(messages)`（UI → model）、`toUIMessageStream(...)`（模型流 → UI 流）。文档明确「The `useChat` message format is different from the `ModelMessage` format. The `useChat` message format is designed for **frontend display**, and contains additional fields such as `id` and `createdAt`. We recommend storing the messages in the **`useChat` message format**.」

出处：https://ai-sdk.dev/docs/reference/ai-sdk-core/ui-message ；https://ai-sdk.dev/docs/ai-sdk-ui/chatbot-message-persistence#storing-messages

### 3.2 全部 `UIMessagePart` 类型清单（原文定义）

| part 类型 | 定义要点 |
| --- | --- |
| `TextUIPart` | `{ type: 'text'; text: string; state?: 'streaming' \| 'done' }` |
| `ReasoningUIPart` | `{ type: 'reasoning'; id?: string; text: string; state?: 'streaming' \| 'done'; providerMetadata?: Record<string, any> }` |
| `ToolUIPart` | `type` 为 `tool-${NAME}`，六态判别联合（见 2.5） |
| `DynamicToolUIPart` | 动态工具用通用 `dynamic-tool` 类型（非 `tool-<name>`） |
| `CustomContentUIPart` | `{ type: 'custom'; kind: \`${string}.${string}\`; providerMetadata?: ... }`，「in the format `{provider}.{provider-type}`」 |
| `SourceUrlUIPart` | `{ type: 'source-url'; sourceId: string; url: string; title?: string; providerMetadata?: ... }` |
| `SourceDocumentUIPart` | `{ type: 'source-document'; sourceId: string; mediaType: string; title: string; filename?: string; providerMetadata?: ... }` |
| `FileUIPart` | `{ type: 'file'; mediaType: string; filename?: string; url: string }`（URL 可为托管文件 URL 或 Data URL） |
| `DataUIPart` | `{ type: \`data-${NAME}\`; id?: string; data: DATA_TYPES[NAME] }` |
| `StepStartUIPart` | `{ type: 'step-start' }` |

```typescript
type DataUIPart<DATA_TYPES extends UIDataTypes> = ValueOf<{
  [NAME in keyof DATA_TYPES & string]: {
    type: `data-${NAME}`;
    id?: string;
    data: DATA_TYPES[NAME];
  };
}>;
```

**类型安全的自定义 UIMessage**：

```typescript
const metadataSchema = z.object({ someMetadata: z.string().datetime() });
type MyMetadata = z.infer<typeof metadataSchema>;
const dataPartSchema = z.object({
  someDataPart: z.object({}),
  anotherDataPart: z.object({}),
});
type MyDataPart = z.infer<typeof dataPartSchema>;
const tools = { someTool: tool({}) } satisfies ToolSet;
type MyTools = InferUITools<typeof tools>;
export type MyUIMessage = UIMessage<MyMetadata, MyDataPart, MyTools>;
```

出处：https://ai-sdk.dev/docs/reference/ai-sdk-core/ui-message

### 3.3 `useChat` 返回值与参数（协议相关部分）

参数（节选）：`chat?`（已有 `Chat<UIMessage>` 实例，提供时其余参数被忽略）、`transport?`、`id?`、`messages?`（初始消息）、`messageMetadataSchema?`、`dataPartSchemas?`、`generateId?`、`onToolCall?`、`sendAutomaticallyWhen?`、`onFinish?`、`onError?`、`onData?`、`throttle?`（仅 React/Vue）、`resume?`（默认 false）。

返回（节选）：
- `messages: UIMessage[]`
- `status: 'submitted' | 'streaming' | 'ready' | 'error'`
- `sendMessage(message?, options?)`：不带 message 时「resubmits the current messages (**useful after adding tool outputs**)」；带 `messageId` 时替换该消息（用于编辑）
- `regenerate`、`stop`、`clearError`、`resumeStream`、`setMessages`
- `addToolOutput`、`addToolApprovalResponse`

`onFinish` 回调参数：`{ message, messages, isAbort, isDisconnect, isError, finishReason? }`，其中 `finishReason` 取值 `'stop' | 'length' | 'content-filter' | 'tool-calls' | 'error' | 'other'`。

**status 状态机（原文定义）**：
- `submitted`：消息已发往 API，等待响应流开始
- `streaming`：响应正在流式返回，接收数据块
- `ready`：完整响应已接收并处理完；可提交新的用户消息
- `error`：API 请求期间发生错误，未能成功完成

出处：https://ai-sdk.dev/docs/reference/ai-sdk-ui/use-chat ；https://ai-sdk.dev/docs/ai-sdk-ui/chatbot#status

### 3.4 useChat 消息数据流的线上协议（SSE，JSON 原文）

**协议标识**：data stream 使用 **Server-Sent Events (SSE)** 格式（「for improved standardization, keep-alive through ping, reconnect capabilities, and better cache handling」）。自定义后端必须设置请求/响应头 `x-vercel-ai-ui-message-stream: v1`。流的终止标记为字面量 `data: [DONE]`。

**全部 chunk 类型与原文 JSON 示例**：

```
data: {"type":"start","messageId":"..."}
data: {"type":"text-start","id":"msg_68679a454370819ca74c8eb3d04379630dd1afb72306ca5d"}
data: {"type":"text-delta","id":"msg_68679a454370819ca74c8eb3d04379630dd1afb72306ca5d","delta":"Hello"}
data: {"type":"text-end","id":"msg_68679a454370819ca74c8eb3d04379630dd1afb72306ca5d"}
data: {"type":"reasoning-start","id":"reasoning_123"}
data: {"type":"reasoning-delta","id":"reasoning_123","delta":"This is some reasoning"}
data: {"type":"reasoning-end","id":"reasoning_123"}
data: {"type":"reasoning-file","url":"data:image/png;base64,iVBOR...","mediaType":"image/png"}
data: {"type":"source-url","sourceId":"https://example.com","url":"https://example.com"}
data: {"type":"source-document","sourceId":"https://example.com","mediaType":"file","title":"Title"}
data: {"type":"file","url":"https://example.com/file.png","mediaType":"image/png"}
data: {"type":"custom","kind":"openai.compaction","providerMetadata":{"openai":{"itemId":"cmp_123"}}}
data: {"type":"data-weather","data":{"location":"SF","temperature":100}}
data: {"type":"error","errorText":"error message"}
data: {"type":"tool-input-start","toolCallId":"call_fJdQDqnXeGxTmr4E3YPSR7Ar","toolName":"getWeatherInformation"}
data: {"type":"tool-input-delta","toolCallId":"call_fJdQDqnXeGxTmr4E3YPSR7Ar","inputTextDelta":"San Francisco"}
data: {"type":"tool-input-available","toolCallId":"call_fJdQDqnXeGxTmr4E3YPSR7Ar","toolName":"getWeatherInformation","input":{"city":"San Francisco"}}
data: {"type":"tool-approval-request","toolCallId":"call_fJdQDqnXeGxTmr4E3YPSR7Ar","approvalId":"approval_123","approvalDescriptor":{"scope":"account:delete"},"reason":"Requires operator review"}
data: {"type":"tool-approval-response","approvalId":"approval_123","approved":false,"reason":"User denied the request"}
data: {"type":"tool-output-available","toolCallId":"call_fJdQDqnXeGxTmr4E3YPSR7Ar","output":{"city":"San Francisco","weather":"sunny"}}
data: {"type":"tool-output-denied","toolCallId":"call_fJdQDqnXeGxTmr4E3YPSR7Ar"}
data: {"type":"start-step"}
data: {"type":"finish-step"}
data: {"type":"reset-step"}
data: {"type":"finish"}
data: {"type":"abort","reason":"user cancelled"}
data: [DONE]
```

**语义注记（原文）**：

- text / reasoning 均采用 **start/delta/end 三段式 + 唯一 id** 模式（id 必须在三段之间一致，才能被识别为同一 text block）。
- `tool-approval-request`：`When isAutomatic is omitted, the request expects an explicit approval response from the client.` `reason` 可选，解释为何需要审批；`approvalDescriptor` 是可选的不透明元数据，处理为 UI message 后暴露为 `part.approval.descriptor` 并在后续审批状态中保留。provider 执行的工具，其 response 可额外带 `providerExecuted: true`。
- `start-step` / `finish-step`：「This part is **necessary to correctly process multiple stitched assistant calls**, e.g. when calling tools in the backend, and using steps in `useChat` at the same time.」`finish-step` 表示「one LLM API call in the backend」已完成。
- `reset-step`：「Removes all message parts received since the most recent `start-step` part. If there is no step boundary, it removes all parts from the current message.」用途：某步被重试时，丢弃失败尝试的部分输出。
- `data-*` 模式：`The data-* type pattern allows you to define custom data types that your frontend can handle specifically.`
- `error` part：「appended to the message as they are received」。
- 后端生成方式：`streamText` 结果流交给 `toUIMessageStream(...)`，再用 `createUIMessageStreamResponse` 返回。
- **文本流协议**（另一条线）：纯文本 chunk 顺序拼接；`useChat` 需配 `TextStreamChatTransport`，`useCompletion` 需 `streamProtocol: 'text'`；后端 `toTextStream` + `createTextStreamResponse`。「Text streams only support basic text data. If you need to stream other types of data such as tool calls, use data streams.」
- 前端默认使用 data stream protocol；`useCompletion` 仅支持 `text` 与 `data` 两类 part。

出处：https://ai-sdk.dev/docs/ai-sdk-ui/stream-protocol

### 3.5 服务端构造 UI 消息流（`createUIMessageStream`）

```tsx
const stream = createUIMessageStream({
  async execute({ writer }) {
    writer.write({ type: 'start' });          // 外层 stream 拥有 assistant message 生命周期
    writer.write({ type: 'text-start', id: 'example-text' });
    writer.write({ type: 'text-delta', id: 'example-text', delta: 'Hello' });
    writer.write({ type: 'text-end',   id: 'example-text' });
    const result = streamText({ model, prompt: 'Write a haiku about AI' });
    writer.merge(toUIMessageStream({ stream: result.stream, sendStart: false,
      onEnd: ({ outcome }) => { writer.setOutcome(outcome); } }));
  },
  onError: error => `Custom error: ...`,
  originalMessages: existingMessages,
  onEnd: ({ messages, isContinuation, outcome, responseMessage }) => { /* ... */ },
});
```

- `writer` 三方法：`write(part: UIMessageChunk)`、`merge(stream: ReadableStream<UIMessageChunk>)`、`setOutcome(outcome)`（「records the composer's policy without writing a chunk or closing the stream」；状态取值 `'completed' | 'failed' | 'aborted' | 'unknown'`；「The first outcome declared through setOutcome is retained, but a fatal execution, merge, error-handling, or downstream processing failure makes the final onEnd outcome `failed`」）。
- 默认 `onError` 返回 `"An error occurred."`，以免服务端错误细节泄漏到客户端。
- `originalMessages`：「If provided, **persistence mode is assumed** and a message ID is provided for the response message.」
- `onEnd` 参数：`{ messages, isContinuation, isAborted, isCancelled?, outcome, responseMessage, finishReason? }`；`isCancelled` 为 `true` 表示消费者在声明 outcome 前取消（如客户端断开）；`isContinuation` 表示响应消息是既有最后一条消息的延续，还是新建消息。

出处：https://ai-sdk.dev/docs/reference/ai-sdk-ui/create-ui-message-stream

### 3.6 在服务端之外消费 UIMessage 流

`readUIMessageStream({ stream, message? })` 把 `UIMessageChunk` 流转换为 `AsyncIterableStream<UIMessage>`，「allowing you to process messages as they're being constructed」，可用于终端 UI、RSC 或自定义客户端处理；传 `message` 参数可实现「Resume from this message」式续接。

出处：https://ai-sdk.dev/docs/ai-sdk-ui/reading-ui-message-streams

---

## 主题 4：data parts 与 transient parts

### 4.1 官方提供的三类 helper

- `createUIMessageStream`：创建一个 data stream
- `createUIMessageStreamResponse`：创建以流式返回数据的 response 对象
- `pipeUIMessageStreamToResponse`：把 data stream 管道到 server response 对象

「The data is streamed as part of the response stream using **Server-Sent Events**.」

出处：https://ai-sdk.dev/docs/ai-sdk-ui/streaming-data

### 4.2 类型声明（data part schema）

```tsx
export type MyUIMessage = UIMessage<
  never, // metadata type
  {
    weather: { city: string; weather?: string; status: 'loading' | 'success' };
    notification: { message: string; level: 'info' | 'warning' | 'error' };
  } // data parts type
>;
```

### 4.3 服务端写入：持久 data part、source、transient data part

```tsx
const stream = createUIMessageStream<MyUIMessage>({
  execute: ({ writer }) => {
    writer.write({ type: 'start' });

    // 2. Send initial status (transient - won't be added to message history)
    writer.write({
      type: 'data-notification',
      data: { message: 'Processing your request...', level: 'info' },
      transient: true,
    });

    // 3. Sources (useful for RAG use cases)
    writer.write({
      type: 'source',
      value: {
        type: 'source',
        sourceType: 'url',
        id: 'source-1',
        url: 'https://weather.com',
        title: 'Weather Data Source',
      },
    });

    // 4. Data part with loading state
    writer.write({
      type: 'data-weather',
      id: 'weather-1',
      data: { city: 'San Francisco', status: 'loading' },
    });

    const result = streamText({
      model: "anthropic/claude-sonnet-5.5",
      messages: await convertToModelMessages(messages),
      onEnd() {
        // 5. Update the same data part (reconciliation)
        writer.write({
          type: 'data-weather',
          id: 'weather-1', // Same ID = update existing part
          data: { city: 'San Francisco', weather: 'sunny', status: 'success' },
        });
        // 6. Completion notification (transient)
        writer.write({
          type: 'data-notification',
          data: { message: 'Request completed', level: 'info' },
          transient: true,
        });
      },
    });

    writer.merge(toUIMessageStream({ stream: result.stream, sendStart: false }));
  },
});
return createUIMessageStreamResponse({ stream });
```

**ordering 约束（原文注释）**：「Start the assistant message before writing any message parts.」（必须先 `writer.write({ type: 'start' })`。）

### 4.4 三类可流式数据的区别（原文分类）

| 类别 | 是否进入 message history / `message.parts` | 示例 |
| --- | --- | --- |
| **Data Parts (Persistent)** | 是，「added to the message history and appear in `message.parts`」 | `{ type: 'data-weather', id: 'weather-1', data: {...} }` |
| **Sources** | 是（作为 `source` part） | `{ type: 'source', value: { type: 'source', sourceType: 'url', id, url, title } }` |
| **Transient Data Parts (Ephemeral)** | **否**，「sent to the client but not added to the message history. They are only accessible via the `onData` useChat handler」 | `{ type: 'data-notification', data: {...}, transient: true }` |

### 4.5 data part reconciliation（同 id 更新）

「When you write to a data part with the **same ID**, the client automatically **reconciles and updates** that part.」文档列出的用途：Collaborative artifacts（实时更新代码/文档/设计）、Progressive data loading（loading 态转为最终结果）、Live status updates（进度条/计数器/状态指示）、Interactive components。「The reconciliation happens automatically - simply use the same `id` when writing to the stream.」

### 4.6 客户端消费

```tsx
const [notification, setNotification] = useState();
const { messages } = useChat({
  onData: ({ data, type }) => {
    if (type === 'data-notification') {
      setNotification({ message: data.message, level: data.level });
    }
  },
});
```

**关键约束（原文加粗）**：「Transient data parts are **only** available through the `onData` callback. They will not appear in the `message.parts` array since they're not added to message history.」`onData` 签名：`(dataPart: DataUIPart) => void`，处理「all data parts as they arrive (**including transient parts**)」。持久 data part 则从 `message.parts` 过滤渲染：`message.parts.filter(part => part.type === 'data-weather')`。

### 4.7 Message Metadata vs Data Parts（原文对照）

| 维度 | Message Metadata | Data Parts |
| --- | --- | --- |
| 挂载位置 | `message.metadata`（消息级） | `message.parts` 数组 |
| 发送方式 | `toUIMessageStream` 的 `messageMetadata` 回调 | `createUIMessageStream` + `writer.write()` |
| 可否用同 id 更新 | —（按 finish 覆盖） | 可以（reconciliation） |
| transient 支持 | 无 | 有 |
| 适用 | timestamps、model info、token usage、user context | 动态内容、loading 状态、交互组件 |

```ts
messageMetadata: ({ part }) => {
  if (part.type === 'finish') {
    return { model: part.response.modelId, totalTokens: part.totalUsage.totalTokens, createdAt: Date.now() };
  }
}
```

```tsx
message.parts // 渲染时读取 message.metadata?.totalTokens 等
```

**跨后端事实**：「You can also send stream data from custom backends, e.g. Python / FastAPI, using the UI Message Stream Protocol」。

出处：https://ai-sdk.dev/docs/ai-sdk-ui/streaming-data ；https://ai-sdk.dev/docs/ai-sdk-ui/chatbot#message-metadata

---

## 主题 5：Generative UI（tool 结果驱动 UI）

### 5.1 定义（原文）

> Generative user interfaces (generative UI) is the process of allowing a large language model (LLM) to **go beyond text and "generate UI"**. ... At the core of generative UI are **tools** ... **Generative UI is the process of connecting the results of a tool call to a React component.**

四步流程：
1. 向模型提供 prompt 或会话历史，以及一组工具
2. 模型可能决定调用某个工具
3. 工具执行并返回数据
4. 「This data can then be passed to a React component for rendering.」

**「Rendering UI with Language Models」版流程（6 步）**：
1. 用户给语言模型 prompt
2. 模型生成含 tool call 的响应
3. tool call 返回代表 UI 的 **JSON 对象**（而不是文本）
4. 响应发往客户端
5. 客户端收到响应并检查最新消息是否是 tool call
6. 若是，客户端基于 tool call 返回的 JSON 对象渲染 UI

关键设计动作：「instead of returning text, if you return a **JSON object** that represents the weather information, you can use it to render a React component instead.」tool 的 `execute` 返回对象，组件以该对象为 props：`<WeatherCard weather={{ temperature, unit, description, forecast }} />`。

出处：https://ai-sdk.dev/docs/ai-sdk-ui/generative-user-interfaces ；https://ai-sdk.dev/docs/advanced/rendering-ui-with-language-models

### 5.2 客户端渲染模式：按 part.type + part.state 分派

```tsx
{message.parts.map((part, index) => {
  if (part.type === 'text') return <span key={index}>{part.text}</span>;

  if (part.type === 'tool-displayWeather') {
    switch (part.state) {
      case 'input-available':  return <div key={index}>Loading weather...</div>;
      case 'output-available': return <div key={index}><Weather {...part.output} /></div>;
      case 'output-error':     return <div key={index}>Error: {part.errorText}</div>;
      default: return null;
    }
  }
  return null;
})}
```

类型命名事实：「In AI SDK 5.0, tool parts use **typed naming**: `tool-${toolName}` instead of generic types.」

多工具的 switch 形态（`tool-api-search-course` → `<Courses courses={part.output} />` 等），文档同时指出工具与组件数量增长后「the complexity of your application will grow as well」。

### 5.3 服务端渲染 UI（AI SDK RSC）

`@ai-sdk/rsc` 的 `createStreamableUI()` 可在服务端渲染 React 组件并流式送达客户端；tool 的 `execute` 内直接 `uiStream.done(<WeatherCard weather={{...}} />)`，客户端仅渲染 `message.display`。流程简化为 4 步（用户 prompt → 模型生成 tool call → tool call 渲染组件与 props → 响应流到客户端直接渲染）。

出处：https://ai-sdk.dev/docs/advanced/rendering-ui-with-language-models#rendering-user-interfaces-on-the-server

### 5.4 动态工具的 UI 判别

动态工具在 UI parts 中使用通用 `dynamic-tool` 类型（而非 `tool-<name>`），部件带 `part.toolName` 字段：

```tsx
case 'dynamic-tool':
  return (
    <div key={index}>
      <h4>Tool: {part.toolName}</h4>
      {part.state === 'input-streaming' && <pre>{JSON.stringify(part.input, null, 2)}</pre>}
      {part.state === 'output-available' && <pre>{JSON.stringify(part.output, null, 2)}</pre>}
      {part.state === 'output-error' && <div>Error: {part.errorText}</div>}
    </div>
  );
```

动态工具适用场景（原文列举两次）：MCP tools without schemas、user-defined functions at runtime / loaded at runtime、external tool providers。

出处：https://ai-sdk.dev/docs/ai-sdk-ui/chatbot-tool-usage#dynamic-tools ；https://ai-sdk.dev/docs/ai-sdk-core/tools-and-tool-calling#dynamic-tools

### 5.5 与 generative UI 相关的类型推断 API

`InferUITool` / `InferUITools` 用于从工具定义推断 UI 侧类型（`useChat` 参考文档有独立章节「Type Inference for Tools」）；`UIMessage` 的第三个泛型参数 `TOOLS` 即为 `InferUITools<typeof tools>`。

出处：https://ai-sdk.dev/docs/ai-sdk-ui/chatbot#type-inference-for-tools ；https://ai-sdk.dev/docs/reference/ai-sdk-core/ui-message

---

## 主题 6：记忆与持久化（message persistence）

### 6.1 持久化的整体形状

- 聊天页无 chat ID 时：`const id = await createChat(); redirect(\`/chat/${id}\`)`。
- 存储接口设计为可替换：示例用文件（`.chats/<id>.json`），文档注明「In a real-world application, you would use a database or a cloud storage service」。
- **`useChat` 发送 chat id 与 messages 给后端**；后端把新消息 append 到已加载历史。
- **存储格式建议**：「We recommend storing the messages in the **`useChat` message format**.」
- **落库时机**：在 `toUIMessageStream` 的 `onEnd` 回调中保存，`onEnd` 收到的是包含新 AI 响应的完整 `UIMessage[]`。

```tsx
return createUIMessageStreamResponse({
  stream: toUIMessageStream({
    stream: result.stream,
    originalMessages: messages,
    onEnd: ({ messages }) => { saveChat({ chatId, messages }); },
  }),
});
```

```ts
export async function saveChat({ chatId, messages }: { chatId: string; messages: UIMessage[] }): Promise<void> {
  const content = JSON.stringify(messages, null, 2);
  await writeFile(getChatFile(chatId), content);
}
export async function loadChat(id: string): Promise<UIMessage[]> {
  return JSON.parse(await readFile(getChatFile(id), 'utf8'));
}
```

- **chat ID 安全约束（原文）**：「The chat ID can come from the URL or the request body, so validate it as an opaque token before using it in a file path.」示例用 `chatIdRegex = /^[A-Za-z0-9_-]+$/` 加 `chatFile.startsWith(\`${chatDir}${path.sep}\`)` 双重防御。

出处：https://ai-sdk.dev/docs/ai-sdk-ui/chatbot-message-persistence

### 6.2 从存储加载后的校验（`validateUIMessages`）

> When processing messages on the server that contain tool calls, custom metadata, or data parts, you should validate them using `validateUIMessages` before sending them to the model.

```tsx
const validatedMessages = await validateUIMessages({
  messages,          // [...previousMessages, message]
  tools,             // Ensures tool calls in messages match current schemas
  dataSchemas,
  metadataSchema,
});
```

同时提供 `safeValidateUIMessages`。校验失败抛 `TypeValidationError`，可捕获后降级（示例：`validatedMessages = []` 从空历史开始）。

**`input-streaming` 态必须完整持久化（对中断恢复至关重要）**：

> An `input-streaming` tool part includes **`rawInput`** while tool arguments are still arriving. **Persist the complete UI message, including this field**, so a resumed stream can append later tool-input deltas to the exact text received before the interruption. AI SDK updates `rawInput` as each delta arrives and removes it when the tool input becomes available.

**`rawInput` 的迁移事实**：旧的 `output-error` tool part 中可能残留 `rawInput`，该字段已 deprecated（「will be removed in the next major version」）；`validateUIMessages` / `safeValidateUIMessages` / `convertToModelMessages` 遇到已定义 `rawInput` 会发出 deprecation warning。迁移规则：当 `input` 为 `null`/`undefined` 时把 `rawInput` 拷到 `input`，然后删除 `rawInput`。

来源与迁移代码出处：https://ai-sdk.dev/docs/ai-sdk-ui/chatbot-message-persistence#validating-messages-on-the-server

### 6.3 Message ID 的生成时机（持久化的关键约束）

默认 ID 生成在客户端：user message 的 ID 由 `useChat` 在客户端生成；**AI 响应消息的 ID 也由 `useChat` 在客户端生成，除非服务端在流中下发一个**。

> For applications without persistence, client-side ID generation works perfectly. However, **for persistence, you should use IDs that are stable before messages are stored** to ensure consistency across sessions and prevent ID conflicts when messages are restored.

服务端控制 assistant 响应 ID 的两种方式：
1. `toUIMessageStream` 的 `generateMessageId: createIdGenerator({ prefix: 'msg', size: 16 })`
2. `createUIMessageStream` 中写 `{ type: 'start', messageId: generateId() }`，再 `writer.merge(toUIMessageStream({ stream, sendStart: false }))`

用户消息 ID 由 `useChat` 在请求发出前生成，后端保存时「keep those client-generated IDs」，或自行生成并持久化后再发送/存储。客户端自定义：`useChat({ generateId: createIdGenerator({ prefix: 'msgc', size: 16 }) })`。

出处：https://ai-sdk.dev/docs/ai-sdk-ui/chatbot-message-persistence#message-ids

### 6.4 只发最后一条消息（少传数据）

```tsx
transport: new DefaultChatTransport({
  api: '/api/chat',
  prepareSendMessagesRequest({ messages, id }) {
    return { body: { message: messages[messages.length - 1], id } };
  },
}),
```

后端 `loadChat(id)` 取出历史，`[...previousMessages, message]` 拼接后（若含工具/元数据/data parts）先 `validateUIMessages`，并把校验后的数组作为 `originalMessages` 传给 `toUIMessageStream`。

出处：https://ai-sdk.dev/docs/ai-sdk-ui/chatbot-message-persistence#sending-only-the-last-message

### 6.5 客户端断连的处理（`consumeStream`）

> By default, the AI SDK `streamText` function uses **backpressure** to the language model provider to prevent the consumption of tokens that are not yet requested. However, this means that when the client disconnects ... the stream from the LLM will be **aborted** and the conversation may end up in a **broken state**.

解法：`result.consumeStream(); // no await`——「effectively removes the backpressure, meaning that the result is stored even when the client has already disconnected」，并确保 `onEnd` 被触发。

文档同时指出生产环境还需「track the state of the request (in progress, complete) in your stored messages」，以覆盖「客户端重载页面但流尚未完成」的情况。

出处：https://ai-sdk.dev/docs/ai-sdk-ui/chatbot-message-persistence#handling-client-disconnects

### 6.6 Agent 记忆的三种路径（`Memory` 文档事实）

| 方式 | 实现成本 | 灵活性 | Provider 锁定 |
| --- | --- | --- | --- |
| Provider-Defined Tools | 低 | 中 | 是 |
| Memory Providers | 低 | 低 | 取决于 memory provider |
| Custom Tool | 高 | 高 | 否 |

- **Provider-Defined Tools**（例：Anthropic Memory Tool）：provider 定义 `inputSchema` 与 `description`，「but you provide the `execute` function. The model has been trained to use these tools」。Anthropic memory 工具给 Claude 一个管理 `/memories` 目录的结构化接口，接收结构化命令 `view` / `create` / `str_replace` / `insert` / `delete` / `rename`，每条带 `/memories` 范围内的 `path`；你的 `execute` 映射到自己的存储后端。
- **Memory Providers**：包装外部记忆服务并暴露为标准接口（Letta、Mem0、Supermemory、Hindsight、MongoDB）；「Memory storage, retrieval, and injection happen **transparently**, and you do not define any tools yourself.」MongoDB 方案给出五个层级：Session、Semantic、Procedural、Episodic、Scratchpad；其 Session memory 有两种模式——**tool-driven**（LLM 决定读写时机，适合原型）与 **hook-driven**（运行时通过 `prepareCall` + `onEnd` 钩子持久化每一轮，「recommended for production」）。
- **Custom Tool**：两种常见形态——**Structured actions**（显式定义 `view`/`create`/`update`/`search`，安全）与 **Bash-backed**（给模型沙箱 bash 环境组合 `cat`/`grep`/`sed`/`echo`，更强但需命令校验）。

出处：https://ai-sdk.dev/docs/agents/memory

---

## 主题 7：中断与恢复（resumable streams / stop）

### 7.1 职责划分（原文）

> **The AI SDK provides:**
> - A `resume` option in `useChat` that automatically reconnects to active streams
> - Access to the outgoing stream through the `consumeSseStream` callback
> - Automatic HTTP requests to your resume endpoints
>
> **You build:**
> - Storage to track which stream belongs to each chat
> - Redis to store the UIMessage stream
> - Two API endpoints: POST to create streams, GET to resume them
> - Integration with [`resumable-stream`](https://www.npmjs.com/package/resumable-stream) to manage Redis storage

前置条件：`resumable-stream` 包（publisher/subscriber 机制）、Redis 实例、跟踪每个 chat 当前 active stream ID 的持久化层。

出处：https://ai-sdk.dev/docs/ai-sdk-ui/chatbot-resume-streams

### 7.2 请求生命周期（5 步，原文）

1. **Stream creation**：发送新消息时 POST handler 用 `streamText` 生成响应；`consumeSseStream` 回调用唯一 ID 创建 resumable stream 并经 `resumable-stream` 存入 Redis
2. **Stream tracking**：持久化层把 `activeStreamId` 存进 chat 数据
3. **Client reconnection**：客户端重连（页面重载）时，`resume` 选项触发对 `/api/chat/[id]/stream` 的 **GET** 请求
4. **Stream recovery**：GET handler 检查 `activeStreamId` 并用 `resumeExistingStream` 重连；没有 active stream 时返回 **204 (No Content)**
5. **Completion cleanup**：流结束时 `onEnd` 回调把 `activeStreamId` 置为 `null`

### 7.3 客户端开关与默认端点

```tsx
const { messages, sendMessage, status } = useChat({
  id: chatData.id,
  messages: chatData.messages,
  resume, // Enable automatic stream resumption
  transport: new DefaultChatTransport({
    prepareSendMessagesRequest: ({ id, messages }) => ({
      body: { id, message: messages[messages.length - 1] },
    }),
  }),
});
```

- 「When you enable `resume`, the `useChat` hook makes a **GET** request to `/api/chat/[id]/stream` on mount to check for and resume any active streams.」
- 「You **must** send the chat ID with each request.」
- ID 编码约束：「The default transport URL-encodes the chat ID as a single path segment. **IDs equal to `.` or `..` are rejected with an `InvalidArgumentError`** because URL parsers interpret them as path traversal segments.」
- 自定义重连端点：`prepareReconnectToStreamRequest({ id })` 可返回 `{ api, credentials, headers }`；「The callback receives the original, unencoded ID. Its returned `api` URL is used **verbatim**, so encode and validate any ID you include in a custom URL yourself.」
- `useChat` 的 `resume?` 参数默认 `false`；返回的 `resumeStream()`「resume an interrupted streaming response. Useful when a network error occurs during streaming.」

### 7.4 POST handler（创建可恢复流）

```ts
saveChat({ id, messages, activeStreamId: null });   // 清掉旧的 active stream 并保存用户消息

const result = streamText({ model: 'openai/gpt-6-luna', messages: await convertToModelMessages(messages) });

return createUIMessageStreamResponse({
  stream: toUIMessageStream({
    stream: result.stream,
    originalMessages: messages,
    generateMessageId: generateId,
    onEnd: ({ messages }) => { saveChat({ id, messages, activeStreamId: null }); },  // Clear the active stream when finished
  }),
  async consumeSseStream({ stream }) {
    const streamId = generateId();
    const streamContext = createResumableStreamContext({ waitUntil: after });
    await streamContext.createNewResumableStream(streamId, () => stream);
    saveChat({ id, activeStreamId: streamId });
  },
});
```

`after`（Next.js）的作用：「allows work to continue after the response has been sent. This ensures that the resumable stream persists in Redis even after the initial response is returned to the client, enabling reconnection later.」

### 7.5 GET handler（恢复）

```ts
export async function GET(_, { params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;
  const chat = await readChat(id);
  if (chat.activeStreamId == null) return new Response(null, { status: 204 });   // no content when there is no active stream
  const streamContext = createResumableStreamContext({ waitUntil: after });
  return new Response(await streamContext.resumeExistingStream(chat.activeStreamId), {
    headers: UI_MESSAGE_STREAM_HEADERS,
  });
}
```

### 7.6 中断语义的核心规则：abort ≠ stop

> In a resumable stream setup, **client-side aborts are treated as disconnects**. Closing a tab, refreshing the page, or calling `stop()` only closes the current HTTP connection and **should not cancel the underlying generation**.

> `useChat` includes a `stop()` function that aborts the current client request. In a resumable stream setup, **that abort is a disconnect signal, not a request to stop generation**.

> Because of this, a client-side abort (e.g. closing the page or refreshing) only closes the current HTTP connection. It is **not** a request to cancel the underlying work. If your stop button only calls `stop()`, the model request, background job, workflow, or stream writer can **continue running**, and the client can reconnect to the same active stream.

**专用的 stop 端点（4 步职责，原文）**：
1. Load the chat and read its `activeStreamId`
2. Persist the assistant snapshot if one was sent
3. Cancel the work that is producing the stream
4. Clear `activeStreamId` **only if it still points to the same stream**（用 `activeStreamId` 比对，避免清掉停请求飞行期间新起的流）

客户端在调 `chat.stop()` 之前先 POST 当前部分 assistant message 与已知 `activeStreamId` 到 `/api/chat/[id]/stop`；服务端 `markStreamAsStopped` / `cancelActiveWork` 依后端而定（Redis 关流 + abort 模型请求；workflow 取消 workflow run；job queue 取消 job 或写取消标志）。

**两条边界规则**：
- 「Do **not** call the stop endpoint from route cleanup code. Route cleanup is a disconnect, not an explicit stop.」
- 「After a user stops a stream, **avoid automatic reconnect attempts** for that chat until the user sends another message or explicitly retries.」
- 「Persist the assistant snapshot as an insert or merge. Avoid overwriting a newer server-written message with an older client snapshot.」

### 7.7 重要注意事项（原文列举）

- **Stream expiration**：Redis 中的流会在设定时间后过期（可在 `resumable-stream` 中配置）
- **Multiple clients**：多个客户端可同时连接同一条流
- **Error handling**：无 active stream 时 GET 返回 204
- **Security**：创建与恢复两条路径都要做鉴权
- **Race conditions**：启动新流时清掉 `activeStreamId`，避免恢复过期流

出处：https://ai-sdk.dev/docs/ai-sdk-ui/chatbot-resume-streams ；https://ai-sdk.dev/docs/troubleshooting/abort-breaks-resumable-streams

### 7.8 abort 与 resume 不可共存（硬约束）

> Stream abort functionality is **not compatible with stream resumption**. If you're using `resume: true` in `useChat`, the abort functionality will **break the resumption mechanism**. Choose either abort or resume functionality, but not both.

出处：https://ai-sdk.dev/docs/advanced/stopping-streams#ai-sdk-ui

### 7.9 停止流的 API 形态与清理回调

- **AI SDK Core**：`abortSignal` 参数取消流（「forward the abort signal: `abortSignal: req.signal`」）。
- `abortSignal` 的精确语义（API Reference）：「When it fires during streaming, **pending result promises such as text and steps reject with the abort reason without waiting for onAbort or provider cancellation**. Completed steps are available through onAbort.」
- `onAbort({ steps })`：只在通过 `AbortSignal` 中止时调用（区别于正常完成的 `onEnd`）；「`steps`: Array of all completed steps before the abort occurred」。用途列举：持久化部分会话历史、保存进度以便后续继续、清理服务端资源/连接、记录 abort 事件。
- `onEnd({ isAborted })` / `onEnd({ isCancelled })` 的区分（原文）：
  - 消费者在 outcome 声明前取消 UI message stream（如客户端断开）→ `isCancelled === true`，`outcome.status === 'unknown'`，`isAborted === false`
  - 流先观察到 `abort` part → `outcome.status === 'aborted'`，`isAborted === true`，`isCancelled` **absent**
  - 「Check both flags when the same cleanup should run for either case.」
- 也可在流内直接观察：`for await (const part of result.stream) { switch (part.type) { case 'abort': ... } }`
- UI message stream 需把 `consumeStream` 传给 `createUIMessageStreamResponse`：「The `consumeStream` function is necessary for proper abort handling in UI message streams. It ensures that the stream is properly consumed even when aborted, preventing potential memory leaks or hanging connections.」
- **平台约束（Vercel）**：请求取消只在 Node.js runtime 支持且需逐函数开启：

```json
{ "functions": { "app/api/chat/route.ts": { "supportsCancellation": true } } }
```

「Without `supportsCancellation`, `stop()` still stops the client-side stream but the **server-side generation may continue**.」

- AI SDK RSC：「does not currently support stopping streams.」
- 相关重试机制：`streamRetries`（`streamText` 参数，默认 `0`）——provider 在响应开始流式返回后发出错误事件时的自动重试次数。「Retries rerun **only the current model step** and preserve completed earlier steps and their tool results. Tool-related output and client-side tool work from failed attempts are discarded; other output already emitted **cannot be retracted**, but is excluded from the recovered step result, structured output parsing, response messages, and subsequent model steps.」「Provider-executed tool work cannot be undone and may repeat.」

出处：https://ai-sdk.dev/docs/advanced/stopping-streams ；https://ai-sdk.dev/docs/reference/ai-sdk-core/stream-text

---

## 术语表（本摘录中出现的核心术语，均为文档原文用词）

| 术语 | 文档语义 |
| --- | --- |
| `UIMessage` | 应用状态的事实来源（source of truth），含 `id`/`role`/`metadata`/`parts`，用于 UI 渲染与客户端逻辑 |
| `ModelMessage` | 传给模型的状态或上下文（与 `UIMessage` 相对） |
| part | `UIMessage.parts` 数组元素；类型含 text / reasoning / tool / dynamic-tool / data / source-url / source-document / file / custom / step-start |
| typed tool part | 类型名为 `tool-${toolName}` 的工具部件；动态工具用 `dynamic-tool` |
| step | 一次 LLM API 调用（tool call 或文本生成各算一步） |
| roundtrip（历史术语） | 旧版计步单位，`maxSteps = roundtrips + 1` |
| `stopWhen` | 停止条件设置；**仅当最后一步包含 tool results 时评估** |
| `StopCondition` | `({ steps }) => boolean` 形式的停止条件函数 |
| `prepareStep` | 每步开始前的配置改写钩子（model / tools / messages / instructions / sandbox / 采样参数） |
| client-side tool | 无 `execute` 定义、由浏览器执行、经 `onToolCall` + `addToolOutput` 回填结果的工具 |
| server-side tool（此处语境） | 有 `execute`、在服务端自动执行的工具 |
| provider-executed tool | 完全在 provider 服务器执行、以 `providerExecuted: true` 标记的工具 |
| `toolApproval` | 审批配置（`not-applicable` / `approved` / `denied` / `user-approval`）；**不暂停执行**，而是返回 `tool-approval-request` 并要求二次调用 |
| transient part | 只经 `onData` 送达、**不进入 message history** 的 data part（`transient: true`） |
| reconciliation | 用相同 `id` 重写 data part 以在客户端就地更新 |
| `activeStreamId` | 引导文档约定的、chat 指向可恢复流的引用；恢复端点用它找回流，stop 端点用它取消工作 |
| `consumeStream` | 消费流以移除 backpressure，使客户端断开后仍能跑完并触发 `onEnd` |
| `consumeSseStream` | `createUIMessageStreamResponse` 的回调，用于把 SSE 流接入 `resumable-stream` |
| outcome | 流的所有者声明的操作级结果：`completed` / `failed` / `aborted` / `unknown` |
| `rawInput` | `input-streaming` 态工具部件中正在累积的原始输入文本；**已 deprecated**（`output-error` 态）但 `input-streaming` 态仍需持久化 |
| `pruneMessages` | 内置的消息/部件裁剪助手，用于长循环的 context compaction |

---

## 未覆盖清单与原因

| 主题 / 页面 | 状态 | 原因 |
| --- | --- | --- |
| `docs/ai-sdk-ui/message-metadata`（完整页） | 部分覆盖 | 仅从 `streaming-data` 与 `chatbot` 摘取了 metadata vs data parts 的对照与示例；独立页未单独抓取（与主题 3/4 重叠度低，受工作量上限约束） |
| `docs/agents/building-agents`、`overview`、`subagents`、`configuring-call-options`、`workflow-agent`、`policy-tool-approvals` | 未逐页覆盖 | 已抓取 `loop-control`、`tool-approvals`、`memory` 三页覆盖 agent 循环与审批要点；其余属 `ToolLoopAgent`/`WorkflowAgent` 的独立 API 面，不属本次列出的七个重点 |
| `docs/reference/ai-sdk-core/tool-loop-agent`、`dynamic-tool`、`has-tool-call`、`tool-search`、`mcp-tools` | 未覆盖 | `hasToolCall` 语义已从 loop-control/迁移指南取得；其余为独立 API 参考页 |
| `docs/reference/ai-sdk-ui/infer-ui-tool(s)`、`prune-messages`、`read-ui-message-stream`、`pipe-ui-message-stream-to-response` | 部分/未覆盖 | 相关概念已在其上位文档中记录；`infer-ui-tool`、`prune-messages` 等参考页未单独抓取 |
| `docs/migration-guides/migration-guide-5-0-data`、`6-0`、`7-0` | 仅间接覆盖 | 只用了与 `maxSteps → stopWhen` 直接相关的 5.0 章节；`-data` 专页（数据流迁移细节）未抓 |
| `docs/ai-sdk-rsc/*`（generative-ui-state、streaming-values、streaming-react-components、multistep-interfaces） | 未覆盖 | RSC 是另一条 generative UI 实现线；本摘录只覆盖了 `rendering-ui-with-language-models` 中的服务端渲染段落 |
| `docs/troubleshooting/*`（除 `abort-breaks-resumable-streams`） | 未覆盖 | `unclosed-streams`、`missing-tool-results-error`、`tool-invocation-missing-result`、`stream-abort-handling` 等与中断/工具结果缺失相关，可能含额外设计线索 |
| `docs/cookbook/**`（含 `restore-messages-from-database`、`save-messages-to-database`、`custom-memory-tool`、`manual-agent-loop`、`agent-context-compaction`） | 未覆盖 | 均为可运行示例页；本次以概念与协议页优先（受 1–2 小时工作量上限约束） |
| `docs/ai-sdk-harnesses/*`（`HarnessAgent`、`terminal-ui`、`tools`、`ui`） | 未覆盖 | 站点新增的 harness 抽象面，与所列七个重点无直接对应 |
| `docs/agents/terminal-ui`、`docs/reference/ai-sdk-tui/*`、`ai-sdk-workflow/*` | 未覆盖 | 非列出的重点主题 |
| `llms-full.txt` | 不可用 | 返回 HTTP 308 且响应体为空（15 字节）；替代方案 `llms.txt`（4.6 MB）已可用于全文检索 |
| Redis / `resumable-stream` 包内部实现（如过期时间默认值、pub/sub 细节） | 未覆盖 | 站点文档只描述接入方式，「Streams in Redis expire after a set time (configurable in the resumable-stream package)」，未给出具体参数 |
| `maxSteps` 在 v5 之后是否以任何形式重新引入 | 未发现 | 全部 551 条 URL 与 `llms.txt` 中，`maxSteps` 仅出现在迁移指南（作为被替换/被移除的旧参数）与 `loop-control` 的手写循环示例局部变量中 |
