# CopilotKit 设计原料摘录（抓取日期 2026-10-07）

> 抓取对象：`https://docs.copilotkit.ai/`（Mintlify 站点）
> 抓取方式：站点 `sitemap.xml` → 逐页取 Mintlify 原始 Markdown（`<url>.md`）；另用 `/llms-full.txt`（7.8 MB，1036 页，每页以 `## Source: <url>` 分隔）批量切分后离线检索。AG-UI 协议细节取自 CopilotKit 文档显式引用的上游站点 `https://docs.ag-ui.com/`（同样以 `.md` 原始形态抓取），已在对应条目旁标注出处。
> 摘录原则：只记录事实（定义、协议、JSON 原文、函数签名、流程、术语），不做评价、不与本站点之外的任何实现做对比。

---

## 站点地图概览（导航结构）

`docs.copilotkit.ai` 的 `sitemap.xml`（520 KB）共 **3894 条 URL**。顶层分为四类：

1. **框架无关的能力指南**（本站点设计原料的主要来源，均位于根路径下）：
   `generative-ui/`（生成式 UI，13 个主题页）、`human-in-the-loop/`（HITL，4 页）、`shared-state/`（共享状态，4 页）、`agentic-protocols/`（AG-UI / MCP / A2A / AG-UI Middleware）、`concepts/`（architecture / generative-ui-overview / which-hook / oss-vs-enterprise）、`agent-spec/`（Oracle Agent Spec 集成）、以及单页指南 `frontend-tools`、`agent-app-context`、`agentic-chat-ui`、`prebuilt-components`、`programmatic-control`、`headless`、`headless-threads`、`server-tools`、`mcp-servers`、`multimodal-attachments`、`threads*`、`voice`、`webmcp`、`inspector`、`quickstart` 等。
2. **按 agent 框架镜像的平行子树**（同一份指南在 18 个框架下各有镜像页，如 `langgraph-python/`、`langgraph-typescript/`、`langgraph-fastapi/`、`mastra/`、`crewai-crews/`、`pydantic-ai/`、`claude-sdk-python|typescript/`、`agno/`、`ag2/`、`llamaindex/`、`strands/`、`strands-typescript/`、`ms-agent-python|dotnet|harness-dotnet/`、`deepagents/`、`google-adk/`、`agent-spec/`）。`langgraph-*` 三个子树各 199 页，是最完整的实现示例来源。
3. **API 参考**：`/reference/v1/**`（35 页，已标记 deprecated，含 `useCopilotAction`、`useCopilotReadable`、`useCoAgent`、`useCoAgentStateRender`、`useLangGraphInterrupt` 等 v1 hook 的完整参数表）、`/reference/hooks/**` 与 `/reference/v2/hooks/**`（当前 v2 hook）、`/reference/components/**`、`/reference/core/**`、`/reference/channels/**`（Slack / Teams 用的 Channel SDK）、以及 Vue / Angular / React Native 的平行参考树。
4. **其它**：`tutorials/`、`cookbook/`、`backend/`、`deploy/`、`migrate/`、`whats-new/`、`troubleshooting/`、`slack/`（247 页）、`teams/`（247 页）。

**可达性备注**：旧版 `/coagents/*` 路径（`coagents/quickstart`、`coagents/human-in-the-loop`、`coagents/shared-state/in-app-agent-read`、`coagents/shared-state/in-app-agent-write`、`coagents/index`）**仍返回 200 且可直接以 `.md` 取得**，但已从 `sitemap.xml` 与 `llms-full.txt` 中移除——即"CoAgents"这一术语的原始文档只能通过直接构造 URL 获得。

---

## 主题 1：HITL —— 交互式工具与人工中断

### 1.1 概念定义：两种暂停模式

出处：<https://docs.copilotkit.ai/human-in-the-loop>

> "Human-in-the-loop (HITL) lets an agent pause mid-run to collect input, confirmation, or a choice from the user, then resume with that answer folded back into its reasoning."

文档给出的适用场景：quality control（高风险决策点的人工关卡）、edge cases（agent 置信度低时的兜底）、expert input（模型缺失的领域知识）、reliability（生产流量的健壮循环）。

CopilotKit 明确区分两种暂停模式，并给出选择矩阵（原文表格）：

| Pattern | Who decides to pause? | Backend surface |
| --- | --- | --- |
| `useHumanInTheLoop` | The **LLM**, by calling a registered client-side tool | A frontend-only tool description (Zod schema + `render`) |
| `useInterrupt` | The **graph**, by calling `interrupt(...)` during a node | A server-side `interrupt()` call in your LangGraph agent |

- 选 `useHumanInTheLoop`：暂停是 *agent-initiated*（模型自己决定要问用户），picker UI 内联进常规 tool-call 流。
- 选 `useInterrupt`：暂停是 *graph-enforced* checkpoint（代码路径确定性地需要人工答复），服务端契约为 `langgraph.interrupt()`。

### 1.2 `useHumanInTheLoop`（v2，当前 API）

出处：<https://docs.copilotkit.ai/reference/hooks/useHumanInTheLoop>、<https://docs.copilotkit.ai/human-in-the-loop>

函数签名（原文）：

```tsx
import { useHumanInTheLoop } from "@copilotkit/react-core/v2";

function useHumanInTheLoop<T extends Record<string, unknown>>(
  tool: ReactHumanInTheLoop<T>,
  deps?: ReadonlyArray<unknown>,
): void;
```

参数语义（原文要点）：

- `name: string`（required）——工具唯一名，agent 需要人工输入时引用此名。
- `description: string`（required）——自然语言描述，告诉 agent 该工具做什么、何时调用。
- `parameters: z.ZodSchema`（required）——Zod schema，定义 agent 传给工具的实参；这些实参被转发给 render 组件以构建上下文相关 UI。
- `render: React.ComponentType<RenderProps>`（required）——驱动人工交互的组件，按 status 分派不同 props。
- `available: "enabled" | "disabled" | "remote"`，默认 `"enabled"`。
- `deps: ReadonlyArray<unknown>`——依赖数组，任一值变化时刷新注册。

**状态机（原文逐字）**：该 hook "provides an internal status machine (`InProgress` -> `Executing` -> `Complete`) and supplies a `respond` callback to the render component while the tool is in the `Executing` state. The agent remains paused until `respond` is called."

各状态下 render 收到的 props：

| status | args | respond | result |
| --- | --- | --- | --- |
| `ToolCallStatus.InProgress` | `Partial<T>`，部分流式的参数 | `undefined` | `undefined` |
| `ToolCallStatus.Executing` | `T`，已完全解析的参数 | `(result: unknown) => Promise<void>` | `undefined` |
| `ToolCallStatus.Complete` | `T`，原始参数 | `undefined` | `string`，序列化后的结果 |

`ToolCallStatus` 枚举（原文表格）：

| Value | Description |
| --- | --- |
| `ToolCallStatus.InProgress` | Arguments are being streamed from the agent. The tool has not started executing yet. |
| `ToolCallStatus.Executing` | Arguments are fully resolved. For `useHumanInTheLoop`, the `respond` callback is available. |
| `ToolCallStatus.Complete` | Execution is finished. The `result` string is available. |

行为约定（原文 bullets）：阻塞 agent 执行；单次响应（`respond` 每次调用只应调用一次，调用即以所传值 resolve 该 tool call 的 promise）；底层构建在 `useFrontendTool` 之上（用一个内部生成的 handler 包装）；mount/unmount 生命周期注册与注销；hook 返回 `void`。

官方 Zod 示例（确认删除，原文）：

```tsx
function DeleteConfirmation() {
  useHumanInTheLoop(
    {
      name: "confirmDeletion",
      description: "Ask the user to confirm before deleting items",
      parameters: z.object({
        itemName: z.string().describe("Name of the item to delete"),
        itemCount: z.number().describe("Number of items to delete"),
      }),
      render: ({ args, status, respond, result }) => {
        if (status === ToolCallStatus.InProgress) {
          return (
            <div className="p-4 text-gray-500">Preparing confirmation...</div>
          );
        }

        if (status === ToolCallStatus.Executing && respond) {
          return (
            <div className="p-4 border rounded">
              <p>
                Are you sure you want to delete {args.itemCount} {args.itemName}
                (s)?
              </p>
              <div className="flex gap-2 mt-4">
                <button
                  onClick={() => respond({ confirmed: true })}
                  className="bg-red-500 text-white px-4 py-2 rounded"
                >
                  Delete
                </button>
                <button
                  onClick={() => respond({ confirmed: false })}
                  className="bg-gray-300 px-4 py-2 rounded"
                >
                  Cancel
                </button>
              </div>
            </div>
          );
        }

        if (status === ToolCallStatus.Complete && result) {
          const parsed = JSON.parse(result);
          return (
            <div className="p-2 text-sm text-gray-600">
              {parsed.confirmed ? "Items deleted." : "Deletion cancelled."}
            </div>
          );
        }

        return null;
      },
    },
    [],
  );

  return null;
}
```

HITL 总览页给出的完整接入示例（含 `agentId` 作用域与建议提示词）：

```tsx
useHumanInTheLoop({
  agentId: "hitl-in-chat",
  name: "book_call",
  description:
    "Ask the user to pick a time slot for a call. The picker UI presents fixed candidate slots; the user's choice is returned to the agent.",
  parameters: z.object({
    topic: z.string().describe("What the call is about (e.g. 'Intro with sales')"),
    attendee: z.string().describe("Who the call is with (e.g. 'Alice from Sales')"),
  }),
  render: ({ args, status, respond }: any) => (
    <TimePickerCard
      topic={args?.topic ?? "a call"}
      attendee={args?.attendee}
      slots={DEFAULT_SLOTS}
      status={status}
      onSubmit={(result) => respond?.(result)}
    />
  ),
});
```

前端 HITL 工具的批准流示例（`generative-ui/your-components/interactive`，出处 <https://docs.copilotkit.ai/generative-ui/your-components/interactive>）：

```tsx
useHumanInTheLoop({
  name: "humanApprovedCommand",
  description: "Ask human for approval to run a command.",
  parameters: z.object({
    command: z.string().describe("The command to run"),
  }),
  render: ({ args, respond, status }) => {
    if (status !== "executing") return <></>;
    return (
      <div>
        <pre>{args.command}</pre>
        <button onClick={() => respond?.(`Tell the user the command ran`)}>Approve</button>
        <button onClick={() => respond?.(`Tell the user the command wasn't run`)}>Deny</button>
      </div>
    );
  },
});
```

### 1.3 `useInterrupt`（v2，当前 API）

出处：<https://docs.copilotkit.ai/human-in-the-loop/useInterrupt>、<https://docs.copilotkit.ai/reference/hooks/useInterrupt>

> "`useInterrupt` lets your agent pause mid-run, hand control to the user through a custom React component, and resume with whatever the user returns."

签名（原文）：

```tsx
import { useInterrupt } from "@copilotkit/react-core/v2";

function useInterrupt<
  TResult = never,
  TRenderInChat extends boolean | undefined = undefined,
>(
  config: UseInterruptConfig<any, TResult, TRenderInChat>,
): TRenderInChat extends false
  ? React.ReactElement | null
  : TRenderInChat extends true | undefined
    ? void
    : React.ReactElement | null | void;
```

配置项语义：

- `render(props)`（required）——收到：
  - `event` —— 中断事件 `{ name, value }`，`value` 为 `any`（形状取决于 agent）。
  - `interrupt` —— 标准 AG-UI 主中断 `{ id, reason, message?, toolCallId?, responseSchema?, expiresAt?, metadata? }`；legacy 中断时为 `null`。
  - `interrupts` —— 全部处于打开状态的标准中断数组；legacy 时为空数组。
  - `result` —— 由 `handler` 返回类型推断，或 `null`。
  - `resolve(payload?, interruptId?)` —— 对标准中断记录 `{ status: "resolved", payload }`，当所有打开的中断都被答复后提交 resume；对 legacy 中断映射为 `command.resume = payload`。
  - `cancel(interruptId?)` —— 对标准中断记录 `{ status: "cancelled" }`；对 legacy 中断直接关闭待处理中断且**不**恢复执行。
- `handler`——可选预处理回调，在 render 之前运行，可返回同步或异步数据，作为 render 的 `result` 暴露；`TResult` 由 handler 返回类型推断；handler 抛错/拒绝时 `result` 为 `null`。
- `enabled: (event: InterruptEvent) => boolean`——可选过滤器，返回 `false` 时本 hook 实例忽略匹配的中断。
- `agentId: string`——可选；默认取当前配置的 chat agent。文档在 interrupt-flow 教程中特别警告：**"`agentId` must match a runtime-registered agent. If you omit `agentId`, the hook assumes `"default"`. If the IDs don't match, the interrupt will never fire."**
- `renderInChat: boolean`，默认 `true`——`true` 把中断 UI 发布进 `<CopilotChat>`；`false` 由 hook 返回中断元素供调用方自行放置。

返回值：`renderInChat: false` → `React.ReactElement | null`；`true` 或省略 → `void`；动态布尔 → `React.ReactElement | null | void`。

**两种中断传输通道（关键协议差异，原文逐条）**：

- **标准流（Standard flow）**：后端遵循 AG-UI 协议时，通过发出 `RUN_FINISHED` 事件、其 outcome 携带 interrupts 数组来表态：
  ```
  outcome.type === "interrupt"
  outcome.interrupts  // Interrupt[]
  ```
  hook 在 `onRunFinishedEvent` 检测到它，并在 `onRunFinalized` 之后才把中断暴露到 render props。
- **Legacy 流**：较旧 agent（或尚未迁移到 AG-UI interrupt 规范的 agent）发出自定义 `on_interrupt` 事件。hook 在 `onCustomEvent` 检测到，此时 `interrupt` 为 `null`、`interrupts` 为 `[]`，payload 位于 `event.value`。调用 `resolve(payload)` 经 `forwardedProps.command`（legacy resume 机制）恢复；`cancel()` 只是关闭中断而不恢复，agent 永远收不到响应。
- **优先级**：同一次 run 上两种信号都出现时（迁移期间可能出现），标准流胜出。

行为约定（原文 bullets）：

- `resolve`/`cancel` 按"每个打开的中断累积一条响应"工作；**所有**打开中断都被处理后才启动 resume run。标准 resume 在同一 thread 上使用新的 `runId`，并通过 `resume[].interruptId` 标识对应待处理中断。
- 已过期（超过 `expiresAt`）的中断不会被 resume——hook 记录错误并清空 pending 状态。
- 中断 UI 在 run finalize 时才浮现。
- 启动新 run 会清空 pending 中断状态。

多中断处理（原文示例）：

```tsx
useInterrupt({
  render: ({ interrupts, resolve, cancel }) => (
    <ul>
      {interrupts.map((i) => (
        <li key={i.id}>
          {i.message}
          <button onClick={() => resolve({ ok: true }, i.id)}>Approve</button>
          <button onClick={() => cancel(i.id)}>Cancel</button>
        </li>
      ))}
    </ul>
  ),
});
```

`responseSchema` 的边界（原文逐字）："The `Interrupt` type exposes a `responseSchema` field (a JSON Schema object) that the agent can use to describe the expected payload shape. `useInterrupt` surfaces this field on `interrupt.responseSchema` for your UI to read (e.g. to drive a form), but it does **not** validate `resolve` payloads against it. Validation is the agent's responsibility on resume."

多类型中断用 `enabled` 分派 + `handler` 预处理（原文）：

```tsx
useInterrupt({
  agentId: "gen-ui-interrupt",
  enabled: (event) => event.value.type === "ask",
  render: ({ event, resolve }) => (
    <AskCard question={event.value.content} onAnswer={resolve} />
  ),
});

useInterrupt({
  agentId: "gen-ui-interrupt",
  enabled: (event) => event.value.type === "approval",
  render: ({ event, resolve }) => (
    <ApproveCard content={event.value.content} onAnswer={resolve} />
  ),
});
```

```tsx
useInterrupt({
  agentId: "gen-ui-interrupt",
  handler: async ({ event, resolve }) => {
    const dept = await lookupUserDepartment();
    if (event.value.accessDepartment === dept || dept === "admin") {
      resolve({ code: "AUTH_BY_DEPARTMENT" });
      return; // agent will resume; card unmounts when the run starts
    }
    return { dept };
  },
  render: ({ result, event, resolve }) => (
    <RequestAccessCard
      dept={result?.dept}
      onRequest={() => resolve({ code: "REQUEST_AUTH" })}
      onCancel={() => resolve({ code: "CANCEL" })}
    />
  ),
});
```

### 1.4 服务端契约：`interrupt()` 的 payload / 返回值约定

出处：<https://docs.copilotkit.ai/human-in-the-loop/useInterrupt>、<https://docs.copilotkit.ai/langgraph-python/human-in-the-loop/interrupt-flow>

原文对服务端契约的两条要点（对设计 payload 格式直接相关）：

1. **payload 侧**：传给 `interrupt()` 的 payload "is what the frontend reads off the interrupt. Keep it a plain, serializable object. It's the 'pause-time context' the UI needs to render. Where it lands depends on the bridge: LangGraph hands it to `render` as `event.value`, while a standard AG-UI interrupt carries it on the interrupt itself, either under `metadata` or JSON-encoded into `message`."
2. **返回值侧**："The return-side contract (`{chosen_label, chosen_time}` or `{cancelled: true}`) is entirely yours. The client can send anything as the resolve payload; the tool is the one that gives it meaning."

LangGraph 服务端原文（工具内 `interrupt()`）：

```python
@tool
def schedule_meeting(topic: str, attendee: Optional[str] = None) -> str:
    """Ask the user to pick a time slot for a call, via an in-chat picker."""
    # `interrupt()` pauses the LangGraph run and forwards a structured
    # payload to the client. The frontend v2 `useInterrupt` hook renders
    # the picker inline in the chat, then calls `resolve(...)` with the
    # user's selection — that value comes back here as `response`.
    response: Any = interrupt(
        {
            "topic": topic,
            "attendee": attendee,
            "slots": _candidate_slots(),
        }
    )

    if isinstance(response, dict):
        if response.get("cancelled"):
            return f"User cancelled. Meeting NOT scheduled: {topic}"
        chosen_label = response.get("chosen_label") or response.get("chosen_time")
        if chosen_label:
            return f"Meeting scheduled for {chosen_label}: {topic}"

    return f"User did not pick a time. Meeting NOT scheduled: {topic}"
```

同页记录了一个实现细节：AG-UI adapter 会把 interrupt value **JSON 字符串化**，因此前端需要按需 parse：

```tsx
const raw = event.value ?? {};
const payload = (typeof raw === "string" ? JSON.parse(raw) : raw) as {
  topic?: string;
  attendee?: string;
  slots?: TimeSlot[];
};
```

另一个实现细节（关于 `resolve` 时机）：官方示例把 `resolve` 包在 `setTimeout(..., 500)` 中，理由是"Defer resolve so React commits the picked/cancelled state before useInterrupt clears the interrupt element. A single requestAnimationFrame is not reliable — rAF fires before React's commit in some scheduling scenarios."

### 1.5 无 UI 形态（Headless Interrupts）

出处：<https://docs.copilotkit.ai/human-in-the-loop/headless>

> "This page covers the escape hatch: a **render-less** interrupt resolver you assemble from the same primitives `useInterrupt` uses internally — a pattern that lives anywhere in your React tree, takes any shape you like (button grid, form, modal, keyboard shortcut), and resolves the interrupt without mounting a chat at all."

两种 headless 形态（原文）：bridge 把暂停暴露为 `on_interrupt` 自定义事件（LangGraph）时，订阅该事件并用 `copilotkit.runAgent({...})` 带匹配的 `resume` payload 恢复；bridge 用标准 AG-UI interrupt outcome 结束 run（AWS Strands）时，用 `useInterrupt` + `renderInChat: false`，把返回的元素放到任意位置，`resolve(...)` 恢复 run。

底层由两个公开 API 组合而成（原文）：

1. `agent.subscribe({ onCustomEvent, onRunStartedEvent, onRunFinishedEvent, onRunFinalized, onRunFailed })` —— 每个 `AbstractAgent` 都暴露的 AG-UI 事件订阅。标准中断在 `onRunFinishedEvent` 上以 `{ outcome: { type: "interrupt", interrupts: [...] } }` 到达；legacy LangGraph 中断以名为 `on_interrupt` 的自定义事件到达。
2. `copilotkit.runAgent({ agent, resume })`（标准）或 `copilotkit.runAgent({ agent, forwardedProps: { command: { resume, interruptEvent } } })`（legacy）。

原文的手写 headless 实现（含事件暂存语义）：

```typescript
const INTERRUPT_EVENT_NAME = "on_interrupt";

function useHeadlessInterrupt(agentId: string): {
  pending: InterruptEvent | null;
  resolve: (response: unknown) => Promise<unknown>;
} {
  const { copilotkit } = useCopilotKit();
  const { agent } = useAgent({ agentId });
  const [pending, setPending] = useState<InterruptEvent | null>(null);
  const pendingRef = useRef<InterruptEvent | null>(null);
  pendingRef.current = pending;

  useEffect(() => {
    let local: InterruptEvent | null = null;
    const sub = agent.subscribe({
      onCustomEvent: ({ event }) => {
        if (event.name === INTERRUPT_EVENT_NAME) {
          const raw = event.value ?? {};
          local = {
            name: event.name,
            value: (typeof raw === "string" ? JSON.parse(raw) : raw) as InterruptPayload,
          };
        }
      },
      onRunStartedEvent: () => { local = null; setPending(null); },
      onRunFinalized: () => { if (local) { setPending(local); local = null; } },
      onRunFailed: () => { local = null; setPending(null); },
    });
    return () => sub.unsubscribe();
  }, [agent]);

  const resolve = useMemo(
    () => async (response: unknown) => {
      const snapshot = pendingRef.current;
      return await copilotkit.runAgent({
        agent,
        forwardedProps: {
          command: { resume: response, interruptEvent: snapshot?.value },
        },
      });
    },
    [agent, copilotkit],
  );

  return { pending, resolve };
}
```

原文总结的三条状态机纪律：只在 `onRunFinalized` 才把事件提交到 React state（与 `useInterrupt` 一致，不在事件流中途浮现）；`onRunStartedEvent` 清掉陈旧 pending，保证新一轮从干净状态开始；`onRunFailed` 丢弃暂存事件，避免传输抖动导致 UI 卡在"永远显示 picker"。

### 1.6 受治理动作（Governed Action Approval）

出处：<https://docs.copilotkit.ai/human-in-the-loop/governed-actions>

原文要求的审批 envelope（"Keep the approval payload small, serializable, and vendor-neutral"）：

```ts
type GovernedAction = {
  id: string;
  summary: string;
  tool: string;
  reference: string;
  verdict: "allow" | "deny" | "require_approval";
  arguments: Record<string, unknown>;
};
```

verdict → UI 行为的确定性映射（原文表格）：

| Verdict | UI behavior |
| --- | --- |
| `allow` | Execute the action without asking again, optionally showing an audit note. |
| `deny` | Do not execute the action. Show the reason or reference and ask the agent to choose a safer path. |
| `require_approval` | Render a user approval card. Execute only if the user approves. |

恢复时的响应契约与校验（原文）：

```ts
type ApprovalResponse = {
  approved: boolean;
  actionId: string;
  reference: string;
};

async function handleApproval(action: GovernedAction, response: ApprovalResponse) {
  if (
    response.approved &&
    response.actionId === action.id &&
    response.reference === action.reference
  ) {
    return executeSideEffect(action.tool, action.arguments);
  }

  return {
    skipped: true,
    reason: "The user did not approve this action.",
  };
}
```

原文列出的 guardrails：策略检查放在服务端而非只在浏览器；包含稳定的 `action.id` 与 `reference` 以防审批被重放给另一个动作；审批前展示确切的动作实参；把 `deny` 视为该提议动作的终态；记录 proposal / verdict / 用户决定 / 执行结果以便审计。

### 1.7 v1 时代的 `renderAndWaitForResponse`（`useCopilotAction`）

出处：<https://docs.copilotkit.ai/reference/v1/hooks/useCopilotAction>（页面顶部标注 "v1 SDK deprecated"，并提示新代码应改用 `@copilotkit/react-core/v2`）

`useCopilotAction` 的 `action` 对象中与 HITL / 渲染相关的字段原文语义：

- `render: string | (props: ActionRenderProps<T>) => string` —— 自定义组件或字符串替代默认渲染。props：
  - `status: 'inProgress' | 'executing' | 'complete'`
    - `"inProgress"`: arguments are dynamically streamed to the function, allowing you to adjust your UI in real-time.
    - `"executing"`: The action handler is executing.
    - `"complete"`: The action handler has completed execution.
  - `args: T` —— "The arguments passed to the action in real time. When the status is `"inProgress"`, they are possibly incomplete."
  - `result: any` —— 仅 `"complete"` 时可用。
- `renderAndWaitForResponse: (props: ActionRenderPropsWait<T>) => React.ReactElement` —— 原文定义："This is similar to `render`, but provides a `respond` function in the props that you must call with the user's response. The component will remain rendered until `respond` is called. The response will be passed as the result to the action handler." 其 props 为 `status`（同上三态）、`args`、`respond: (result: any) => void`（"A function that must be called with the user's response. The response will be passed as the result to the action handler. **Only available when status is `"executing"`**"）、`result`。

同页其它字段：`name`(required)、`handler: (args) => Promise<any>`(required)、`description`、`available: 'enabled' | 'disabled' | 'remote'`（"When set to `"remote"`, the action is available only for remote agents."）、`followUp: boolean` 默认 `true`（"Whether to report the result of a function call to the LLM which will then provide a follow-up response. Pass `false` to disable"）、`parameters: Parameter[]`（数组式声明，`type` 取值 `"string" | "number" | "boolean" | "object" | "object[]" | "string[]" | "number[]" | "boolean[]"`，另有 `enum`、`required`（默认 true）、`attributes` 用于 object 嵌套）。`dependencies: any[]` 为可选的依赖数组。

### 1.8 v1 → v2 的 HITL 迁移映射（文档明确给出的等价关系）

出处：<https://docs.copilotkit.ai/reference/v1/hooks/useHumanInTheLoop>（"Migration from useCopilotAction" 小节）

原文逐字给出的两点差异：

1. The property is called `render` instead of `renderAndWaitForResponse`
2. You need to check for the `respond` function's existence

迁移前后对照（原文）：

```tsx
// Before with useCopilotAction
useCopilotAction({
  name: "confirmAction",
  parameters: [{ name: "message", type: "string", required: true }],
  renderAndWaitForResponse: ({ args, respond, status }) => {
    return (
      <ConfirmDialog
        message={args.message}
        onConfirm={() => respond(true)}
        onCancel={() => respond(false)}
        isActive={status === "executing"}
      />
    );
  },
});

// After with useHumanInTheLoop
useHumanInTheLoop({
  name: "confirmAction",
  parameters: [
    { name: "message", type: "string", description: "The message to display", required: true },
  ],
  render: ({ args, respond, status }) => {
    if (status === "executing" && respond) {
      return (
        <ConfirmDialog
          message={args.message}
          onConfirm={() => respond(true)}
          onCancel={() => respond(false)}
          isActive={true}
        />
      );
    }
    return null;
  },
});
```

另有一个 hook 级等价关系：`useLangGraphInterrupt`（v1）→ `useInterrupt`（v2），文档称 `useInterrupt` 是"the shape-equivalent replacement. It uses the same `render: ({ event, resolve }) => ...` pattern"。`useLangGraphInterrupt` 的参数为 `handler`、`render`、`enabled: (args: { eventValue: TEventValue; agentMetadata: AgentSession }) => boolean`（"Method that returns a boolean, indicating if the interrupt action should run. Useful when using multiple interrupts"）、`agentId`。出处：<https://docs.copilotkit.ai/reference/v1/hooks/useLangGraphInterrupt>

此外 v1 的 `useHumanInTheLoop` 有一处明确的功能缺口（原文）：其 `available` 属性 "is currently non-functional in this v1 hook. The value is accepted for API compatibility but ignored, so setting `"disabled"` does not prevent the tool from being registered or called."

---

## 主题 2：Generative UI（生成式 UI）

### 2.1 概念定义与六种原语

出处：<https://docs.copilotkit.ai/concepts/generative-ui-overview>、<https://docs.copilotkit.ai/generative-ui>

> "Generative UI in CopilotKit is the set of primitives that let an agent decide what appears on the screen — from rendering a specific application component you've built, to composing layouts from a catalog, to embedding sandboxed UI shipped by an MCP server."

六种原语（原文表格）：

| Primitive | What it does |
|---|---|
| **Components as Tools** | Register an application component as a frontend tool; the agent calls it and CopilotKit renders it inline with typed inputs. |
| **Tool Call Rendering** | Map your agent's existing backend tool calls to custom UI cards showing live status, arguments, and results. |
| **State Rendering** | Subscribe to the agent's streamed state and re-render UI as values arrive. |
| **Reasoning** | Render the model's reasoning tokens inline as a first-class message type (default card, or fully custom). |
| **A2UI** | Render UI from a declarative schema the agent emits, composed against a catalog you register. Two flavors: Dynamic Schema (LLM generates the schema) and Fixed Schema (you author the schema, agent supplies data). |
| **MCP Apps** | Embed UI that an MCP server ships alongside its tools, rendered in a sandboxed iframe. No frontend renderer required. |

**控制权谱系**（原文，用于选型）：

- **Controlled** —— "you wrote the component; the agent only picks *which* one to render and *what data* to pass. Highest predictability, highest engineering cost per capability." 含 Components as Tools、Tool Call Rendering、State Rendering、Reasoning。
- **Declarative** —— "the agent emits a structured spec; the frontend composes from a catalog you registered. Creativity inside a guardrail." 含 A2UI（Dynamic 与 Fixed Schema）。
- **Open-Ended** —— "the UI is invented elsewhere (an MCP server) and you sandbox it. Highest expressive range, hardest to guarantee accessibility / brand / security." 含 MCP Apps。

选择矩阵（原文表格）：

| You want to… | Use |
|---|---|
| Let the agent render a specific application component you've already built | **Components as Tools** |
| Brand the cards CopilotKit draws for your agent's existing backend tools | **Tool Call Rendering** |
| Update UI as the agent's state changes (progress, drafts, dashboards) | **State Rendering** |
| Surface the model's thinking chain in the chat | **Reasoning** |
| Let the agent compose layouts from a catalog you define | **A2UI** — Fixed Schema if the surface is known, Dynamic Schema if it isn't |
| Embed someone else's MCP-hosted UI in the chat | **MCP Apps** |

### 2.2 `useComponent`：登记组件即工具

出处：<https://docs.copilotkit.ai/generative-ui/tool-based>、<https://docs.copilotkit.ai/reference/hooks/useComponent>、<https://docs.copilotkit.ai/generative-ui/display>

> "Tool-based Generative UI is the simplest form of Generative UI: you register a React component with `useComponent`, and CopilotKit exposes it to the agent as a tool. When the agent calls the tool, CopilotKit renders your component inline in the chat, passing the tool's arguments straight through as typed props. Unlike tool rendering, which wraps a real backend tool in a custom UI, tool-based GenUI is the component. There is no handler, no user interaction, no server-side execution."

签名（原文）：

```tsx
function useComponent<TSchema extends z.ZodTypeAny | undefined = undefined>(
  config: {
    name: string;
    description?: string;
    parameters?: TSchema;
    // When parameters is provided, props are inferred via z.infer.
    // When omitted, render accepts any props.
    render: ComponentType<InferRenderProps<TSchema>>;
    agentId?: string;
    followUp?: boolean;
  },
  deps?: ReadonlyArray<unknown>,
): void;
```

参数语义（原文）：`config.name` 为 agent 调用该组件工具所用的工具名；`config.description` 为可选的、给模型的额外指引；`config.parameters` 为可选 Zod schema——提供时 `render` 的 props 由 schema 推断；省略时该工具以空参数 schema 广播（`{ "type": "object", "properties": {} }`），因此 agent 不带实参调用；`config.render` 为用解析后工具实参渲染的 React 组件；`config.agentId` 为可选 agent 作用域；`config.followUp` 表示渲染组件后 agent 是否继续，默认 `true`，设为 `false` 则在工具调用后停止。

文档记录的两条重要行为约束（原文）：

- **description 会被改写**："Prepends a default instruction to the description: *'Use this tool to display the "<name>" component in the chat...'*"。
- **名称冲突规则**（tool-based 页）："The runtime layers the configured tools over the forwarded ones, so a shared name resolves to the backend tool and the component never renders." 并强调 "Keep the `useComponent` name distinct from every tool in `config.tools`."
- **prompt 必须点名工具**："The tool arrives on every run, but a model with no instruction about it will answer in prose and never call it. Name the tool in the prompt and say what it is for."

最小示例（原文）：

```tsx
const weatherCardSchema = z.object({
  city: z.string().describe("City name"),
  unit: z.enum(["c", "f"]).default("c"),
});

function App() {
  useComponent(
    {
      name: "showWeatherCard",
      description: "Render a weather card in chat for the requested city.",
      parameters: weatherCardSchema,
      render: WeatherCard,
    },
    [],
  );
  return null;
}
```

完整接入示例（含真实 Zod schema 与 agentId 作用域）：

```tsx
import { useComponent } from "@copilotkit/react-core/v2";
import { z } from "zod";

const weatherSchema = z.object({
  city: z.string().describe("City name"),
  temperature: z.number().describe("Temperature in Fahrenheit"),
  condition: z.string().describe("Weather condition"),
});

function WeatherCard({ city, temperature, condition }: z.infer<typeof weatherSchema>) {
  return (
    <div className="rounded-lg border p-4">
      <h3 className="font-semibold">{city}</h3>
      <p className="text-2xl">{temperature}°F</p>
      <p className="text-sm text-gray-500">{condition}</p>
    </div>
  );
}

function YourMainContent() {
  useComponent({
    name: "showWeather",
    description: "Display a weather card for a city.",
    parameters: weatherSchema,
    render: WeatherCard,
  });
  return <div>{/* ... */}</div>;
}
```

多 agent 场景下按 agent 作用域登记：

```tsx
useComponent({
  name: "renderProfile",
  parameters: z.object({ userId: z.string() }),
  render: ProfileCard,
  agentId: "support-agent",
});
```

headless chat 下的补渲染（原文）：内置 chat 组件会自动绘制已注册组件；headless 或自定义 chat 自己渲染消息列表，因此必须显式渲染：

```tsx
import { CopilotChatToolCallsView } from "@copilotkit/react-core/v2";

<CopilotChatToolCallsView message={assistantMessage} messages={allMessages} />;
```

> "It looks up the sibling `tool`-role message for each tool call and hands both to the registered renderer."

后端侧配置要点（原文，两处对 config mode 与 factory mode 的说明）：config mode 下 `BuiltInAgent` 会把请求中的工具定义合并进交给模型的工具列表，因此 agent 无需自己声明任何工具，`useComponent` 注册的组件按名字即可到达模型；factory mode 下 factory 掌握模型调用，`BuiltInAgent` 忽略 `config.tools` 且不代你转发，必须从 `input` 取出工具传给模型，否则组件永不渲染。

```ts title="app/api/copilotkit/[[...slug]]/route.ts"
import { BuiltInAgent } from "@copilotkit/runtime/v2";

const builtInAgent = new BuiltInAgent({
  model: "openai:gpt-5.4-mini",
  prompt: SYSTEM_PROMPT,
});
```

```ts title="app/api/copilotkit/factory.ts"
import {
  BuiltInAgent,
  convertMessagesToVercelAISDKMessages,
  convertToolsToVercelAITools,
} from "@copilotkit/runtime/v2";
import { openai } from "@ai-sdk/openai";
import { streamText } from "ai";

const agent = new BuiltInAgent({
  type: "aisdk",
  factory: ({ input, abortSignal }) =>
    streamText({
      model: openai("gpt-4o"),
      messages: convertMessagesToVercelAISDKMessages(input.messages),
      tools: convertToolsToVercelAITools(input.tools),
      abortSignal,
    }),
});
```

### 2.3 工具调用渲染（Tool Rendering）与兜底渲染

出处：<https://docs.copilotkit.ai/generative-ui/tool-rendering>

> "Tools are a way for the LLM to call predefined, typically, deterministic functions. CopilotKit allows you to render these tools in the UI as a custom component, which we call **Generative UI**."

```tsx
import { useRenderTool } from "@copilotkit/react-core/v2";
import { z } from "zod";

const weatherParams = z.object({
  location: z.string().describe("The location to get weather for"),
});

const YourMainContent = () => {
  useRenderTool({
    name: "get_weather",
    parameters: weatherParams,
    render: ({ status, parameters }) => {
      return (
        <p className="text-gray-500 mt-2">
          {status !== "complete" && "Calling weather API..."}
          {status === "complete" && `Called the weather API for ${parameters.location}.`}
        </p>
      );
    },
  });
}
```

**关键约束**（原文 Callout，标注 "Important"）："In order to render a tool call in the UI, the name must match the name of the tool."

兜底渲染器（原文）：

```tsx
import { useDefaultRenderTool } from "@copilotkit/react-core/v2";

useDefaultRenderTool({
  render: ({ name, args, status, result }) => {
    return (
      <div style={{ color: "black" }}>
        <span>
          {status === "complete" ? "✓" : "⏳"}
          {name}
        </span>
        {status === "complete" && result && (
          <pre>{JSON.stringify(result, null, 2)}</pre>
        )}
      </div>
    );
  },
});
```

> "Unlike `useRenderToolCall`, which targets a specific tool by name, `useDefaultRenderTool` catches **all** tools that don't have a dedicated renderer."

文档给出的 `useDefaultRenderTool` 适用场景：开发期显示所有工具调用、渲染 MCP（Model Context Protocol）工具、为意外工具提供通用兜底 UI。

v1 侧的等价物 `useRenderToolCall` / `useDefaultTool`（出处 <https://docs.copilotkit.ai/reference/v1/hooks/useRenderToolCall>、<https://docs.copilotkit.ai/reference/v1/hooks/useDefaultTool>）：`useRenderToolCall` "is purely a rendering hook. It displays custom UI for tool calls without executing any logic"，`name` 支持 `"*"` 通配符捕获所有工具调用；render props 为 `status`（`'inProgress' | 'executing' | 'complete'`，其中 `"inProgress"`: Tool is being prepared or arguments are being streamed / `"executing"`: Tool is actively running / `"complete"`: Tool execution has finished）、`args`、`result`（仅 complete 时）、`name`、`description`。`useDefaultTool` 是"catches any tool that does not have a specific renderer"的兜底，其文档列出的 Common Use Cases 为：Backend Tool Visualization、Generic Tool Rendering、MCP Tool Integration、Debugging、Analytics。两个 hook 的 `available` 属性在 v1 中同样被标注为 non-functional。

### 2.4 状态渲染（State Rendering）

出处：<https://docs.copilotkit.ai/generative-ui/state-rendering>、<https://docs.copilotkit.ai/shared-state/streaming>

> "State rendering lets you build UI that reflects your agent's state in real-time. As your agent progresses through nodes and emits state updates, your frontend renders those changes, showing progress, drafts, or intermediate results."

前端订阅（原文，逐 token 重渲染文档）：

```typescript
// Subscribe to BOTH state changes and run-status changes. The former
// drives the per-token document rerender; the latter toggles the
// "LIVE" badge when the agent starts / stops.
const { agent } = useAgent({
  agentId: "shared-state-streaming",
  updates: [UseAgentUpdate.OnStateChanged, UseAgentUpdate.OnRunStatusChanged],
});
```

后端侧的状态流式映射（原文，LangGraph Python 用 `StateStreamingMiddleware` + `StateItem`）：

```python
from copilotkit import (
    CopilotKitMiddleware,
    StateItem,
    StateStreamingMiddleware,
)

class AgentState(BaseAgentState):
    """Shared state. `document` is streamed token-by-token."""
    document: str

graph = create_agent(
    model=ChatOpenAI(model="gpt-5.4"),
    tools=[write_document],
    middleware=[
        CopilotKitMiddleware(),
        # Forward every token of write_document's `document` argument
        # straight into state["document"] while the tool call is still
        # streaming. Without this, `document` would only update once
        # the tool call completes.
        #
        # NOTE: the frontend `usePredictStateSubscription` hook indexes
        # the (partial-JSON-parsed) tool args by `state_key`, so the
        # tool's argument name MUST match `state_key` ("document") for
        # per-token deltas to land in `state.document`.
        StateStreamingMiddleware(
            StateItem(
                state_key="document",
                tool="write_document",
                tool_argument="document",
            )
        ),
    ],
    state_schema=AgentState,
    system_prompt=(...),
)
```

文档列出的三条约束（原文 bullets）：state key 必须存在于 agent state 中；工具名与参数名必须与要转发的那个 LLM-facing tool call 精确匹配（此例为 `write_document.document`）；工具调用完成时其最终返回值会写入同一个 key，因此流式的部分值最终会成为权威终值。

同一机制的跨框架命名（原文）：middleware 型框架通常暴露为声明式映射——LangGraph Python 的 `StateStreamingMiddleware` + `StateItem(...)`，LangGraph TypeScript 图的 `copilotkitCustomizeConfig` 配 `emitIntermediateState` 映射；直接用 SDK adapter 的则在自身 streaming loop 中解析部分工具实参、每当被映射的值变化就发出 `STATE_SNAPSHOT`。

### 2.5 前端驱动的卡片（Activity Message）

出处：<https://docs.copilotkit.ai/generative-ui/frontend-cards>

> "An activity message is a message with `role: "activity"`. It renders in the transcript like any other message, and it is stripped from the payload sent to your agent on every run. The agent and the model never see it."

渲染器定义（原文）：

```tsx
import { z } from "zod";
import type { ReactActivityMessageRenderer } from "@copilotkit/react-core/v2";

const contentSchema = z.object({
  title: z.string(),
  detail: z.string().optional(),
});

export const eventCardRenderer: ReactActivityMessageRenderer<
  z.infer<typeof contentSchema>
> = {
  activityType: "app-event-card",
  content: contentSchema,
  render: ({ content }) => (
    <div className="rounded-lg border p-4">
      <strong>{content.title}</strong>
      {content.detail ? <p>{content.detail}</p> : null}
    </div>
  ),
};
```

注册到 provider 与从前端代码插入卡片（原文）：

```tsx
<CopilotKit
  runtimeUrl="/api/copilotkit"
  renderActivityMessages={[eventCardRenderer]}
>
  <CopilotChat />
</CopilotKit>
```

```tsx
const { agent } = useAgent();

useEffect(() => {
  const socket = new WebSocket("wss://example.com/deployments");
  socket.onmessage = (event) => {
    const deployment = JSON.parse(event.data);
    agent.addMessage({
      id: crypto.randomUUID(),
      role: "activity",
      activityType: "app-event-card",
      content: { title: "Deployment finished", detail: deployment.sha },
    });
  };
  return () => socket.close();
}, [agent]);
```

**agent 实际收到的内容**（原文表格 + 说明）：

| Where | Roles present |
| --- | --- |
| `agent.messages` (what the chat renders) | `user`, `activity` |
| Run payload (what your agent receives) | `user` |

> "Activity messages stay in `agent.messages` so the transcript renders them, and `prepareRunAgentInput` removes them before the run payload leaves the browser."
> "A `MESSAGES_SNAPSHOT` from the backend does not remove your card either. The snapshot merge preserves activity messages, so a card added in the browser survives a server-side history replay."

限制（原文）：**Not persisted**（卡片永不到达后端，刷新后消失；若需卡片回来，应由 agent 以 activity event 形式发出）；**One renderer per activity type**（注册的 renderer 的 `activityType` 必须匹配，或使用 `"*"` 通配符；无匹配 renderer 的 activity message 什么都不渲染）。

另有 Callout 警告：必须使用 `useAgent()` 返回的 agent 实例；自己构造并持有的 agent 实例不是 chat 渲染的那个实例，往它添加的消息永远不会出现。

### 2.6 A2UI（声明式生成式 UI 规范）

出处：<https://docs.copilotkit.ai/generative-ui/a2ui>

> "**A2UI** is Google's declarative, LLM-friendly Generative UI specification that enables agents to generate dynamic user interfaces."

原文列出的四个设计特征：**JSONL-based**（Uses JSON Lines format for streaming）、**LLM-friendly**、**Platform-agnostic**、**Streaming-first**。

后端启用（原文）：

```ts title="app/api/copilotkit/route.ts"
import {
  CopilotRuntime,
  createCopilotRuntimeHandler,
} from "@copilotkit/runtime/v2";

const runtime = new CopilotRuntime({
  agents: { default: myAgent },
  a2ui: {},
});

// Single-route: only POST needed, no catch-all [...path] required
const handler = createCopilotRuntimeHandler({
  runtime,
  basePath: "/api/copilotkit",
  mode: "single-route",
});

export { handler as POST };
```

> "This automatically applies `A2UIMiddleware` to all registered agents. To scope it to specific agents, you can specify agents with the `agents` property: `a2ui: { agents: ["my-agent"] }`."

前端主题覆盖（原文，渲染器自动激活，无需额外配置）：

```tsx
import { CopilotKit, type A2UITheme } from "@copilotkit/react-core/v2";

const myCustomTheme: A2UITheme = {
  // ...your theme keys
};

<CopilotKit runtimeUrl="/api/copilotkit" a2ui={{ theme: myCustomTheme }}>
  {children}
</CopilotKit>;
```

### 2.7 `useComponent` 之外的交互式生成式 UI

出处：<https://docs.copilotkit.ai/generative-ui/interactive>

> "Interactive generative UI creates flows where the agent pauses execution and waits for user input before continuing. This enables approval workflows, confirmation dialogs, and any scenario where human judgment is needed mid-execution."

该页的处理方式实际上是复用 `useInterrupt`（前后端代码与 `human-in-the-loop/useInterrupt` 页相同），即：交互式 GenUI 与 HITL 在 CopilotKit 中是同一套原语的两个入口。

---

## 主题 3：CoAgents 与 agent 状态协议

### 3.1 "CoAgents" 的定义（旧版文档原文）

出处：<https://docs.copilotkit.ai/reference/v1/hooks/useCoAgent>、<https://docs.copilotkit.ai/llamaindex/shared-state>、<https://docs.copilotkit.ai/coagents/index>

> "We call these shared state experiences agentic copilots, or CoAgents for short."（`useCoAgent` 参考页原文）

> "CoAgents maintain a shared state that seamlessly connects your UI with the agent's execution. This shared state system allows you to: Display the agent's current progress and intermediate results; Update the agent's state through UI interactions; React to state changes in real-time across your application."（shared-state 概念页原文）

`useCoAgent` 的返回对象（原文 v1 参考页，说明其在 v2 中是 `useAgent` 的薄兼容包装）：

```tsx
const {
  name,     // The name of the agent currently being used.
  nodeName, // The name of the current LangGraph node.
  threadId, // The ID of the thread the agent is running in.
  state,    // The current state of the agent.
  setState, // A function to update the state of the agent.
  running,  // A boolean indicating if the agent is currently running.
  start,    // A function to start the agent.
  stop,     // A function to stop the agent.
  run,      // A function to (re-)run the agent. Maps to the v2 agent's `runAgent()`.
} = agent;
```

`useCoAgent` 参数（原文）：`name: string`(required)、`initialState: T | any`、`state: T | any`（"State to manage externally if you are using this hook with external state management."）、`setState: (newState: T | ((prevState: T | undefined) => T)) => void`（"A function to update the state of the agent if you are using this hook with external state management."）。

`useCoAgentStateRender`（v1，出处 <https://docs.copilotkit.ai/reference/v1/hooks/useCoAgentStateRender>）：

> "The useCoAgentStateRender hook allows you to render UI or text based components on a Agentic Copilot's state in the chat. This is particularly useful for showing intermediate state or progress during Agentic Copilot operations."

参数：`name: string`(required)、`nodeName: string`（可选，节点级作用域）、`handler`、`render: ((props: CoAgentStateRenderProps<T>) => string | React.ReactElement | undefined | null) | string`。render props 为 `{ status, state, nodeName }`。原文示例：

```tsx
type YourAgentState = {
  agent_state_property: string;
}

useCoAgentStateRender<YourAgentState>({
  name: "basic_agent",
  nodeName: "optionally_specify_a_specific_node",
  render: ({ status, state, nodeName }) => {
    return (
      <YourComponent
        agentStateProperty={state.agent_state_property}
        status={status}
        nodeName={nodeName}
      />
    );
  },
});
```

官方在 `useCoAgentStateRender` 页给出的落地参照是 "Perplexity Clone"（渲染 agent 联网搜索的进度），示例站点 `https://examples-coagents-ai-researcher-ui.vercel.app/`，并存有指引跳到 `/generative-ui/state-rendering`。该页的 "in the chat" 渲染如今在 v2 中由 `useRenderTool` 或直接读 `useAgent` 的 `agent.state` 完成。

### 3.2 agent 状态读写（双向通道）

出处：<https://docs.copilotkit.ai/shared-state>、<https://docs.copilotkit.ai/shared-state/rendering-in-app>、<https://docs.copilotkit.ai/coagents/shared-state/in-app-agent-read>、<https://docs.copilotkit.ai/coagents/shared-state/in-app-agent-write>

读（原文）：

```tsx
function TaskBoard() {
  const { agent } = useAgent();

  // Read state set by the agent
  const tasks = (agent.state.tasks as any[]) ?? [];

  return (
    <div>
      <h2>Tasks</h2>
      <ul>
        {tasks.map((task, i) => (
          <li key={i}>{task.title} — {task.status}</li>
        ))}
      </ul>
    </div>
  );
}
```

> "`agent.state` is reactive — your component re-renders automatically when the agent updates state."

写（原文）：

```tsx
const handleThemeChange = (theme: string) => {
  agent.setState({
    ...agent.state,
    userPreferences: { theme },
  });
};
```

**管道机制（原文逐条）**："The Built-in Agent automatically has access to state tools (`AGUISendStateSnapshot` and `AGUISendStateDelta`) through the AG-UI protocol. When the agent calls these tools: 1. The agent sends a state update (full snapshot or delta); 2. The CopilotKit runtime delivers the update to the frontend via SSE; 3. Your `useAgent` hook receives the update and triggers a re-render. No additional backend configuration is required — state tools are available to the Built-in Agent by default."

在应用主视图（非 chat 区域）渲染 agent state（原文要点）：`useAgent` 可在 `<CopilotKit>` 下的任意组件中工作，不必靠近 chat。同一个 `agentId` 下 `<Canvas>` 与 `<CopilotSidebar>` 共享同一个 agent 实例和 state 对象——"There's nothing chat-specific about reading `agent.state`. The sidebar is not special."。原文给出的 Tips：用 `useAgent({ agentId: "research-agent" })` 指定 agent（默认是名为 `"default"` 的 agent）；`useAgent({ throttleMs })` 节流高频更新；run 进行中应把 `agent.state` 视为可能不完整的部分对象，用默认值兜底。

更新状态后重跑 agent（原文）：

```tsx
const toggleLanguage = () => {
  const newLanguage = language === "english" ? "spanish" : "english";
  agent.setState({ language: newLanguage });

  // re-run the agent with updated state
  agent.runAgent();
};
```

LangGraph 侧的状态定义（原文，Python / TypeScript 两版）：

```python
from langchain_core.runnables import RunnableConfig
from copilotkit import CopilotKitState
from typing import Literal

class AgentState(CopilotKitState):
    language: Literal["english", "spanish"] = "english"

def chat_node(state: AgentState, config: RunnableConfig):
  # If language is not defined, set a value.
  # this is because a default value in a state class is not read on runtime
  language = state.get("language", "english")
  return {
      "language": language
  }
```

```ts
import { StateSchema } from "@langchain/langgraph";
import { CopilotKitStateSchema } from "@copilotkit/sdk-js/langgraph";
import { z } from "zod";

export const AgentStateSchema = new StateSchema({
    language: z.enum(["english", "spanish"]).default("english"),
    ...CopilotKitStateSchema.fields,
});
export type AgentState = typeof AgentStateSchema.State;
```

### 3.3 `state["copilotkit"]`：agent 侧的注入协议

出处：<https://docs.copilotkit.ai/langgraph-python/generative-ui/your-components/display-only>（同页亦出现于 `langgraph-typescript`、`deepagents` 等镜像）

`CopilotKitState` 在 agent state 中提供一个 `copilotkit` 键，前端注册的能力与上下文通过它注入模型可见的工具列表：

```python title="agent.py"
from langchain_openai import ChatOpenAI
from langchain_core.runnables import RunnableConfig
from copilotkit import CopilotKitState

class AgentState(CopilotKitState):
    pass

async def chat_node(state: AgentState, config: RunnableConfig):
    model = ChatOpenAI(model="gpt-4o").bind_tools(
        state["copilotkit"]["actions"]  # includes all registered frontend tools/components
    )
    response = await model.ainvoke(state["messages"], config)
    return {"messages": response}
```

调用时序（原文四步）：1. 工具调用作为一条 message 存入 `state["messages"]`（持久化）；2. CopilotKit 把工具调用流式送到前端；3. `useComponent` 用工具实参作为 props 在 chat 中内联渲染 `WeatherCard`；4. 因为 message 被持久化，组件在重放/刷新后仍可见。

上下文条目的读取（原文，来自 agent-readonly 页的 agent 侧示例）：

```python
(item for item in state["copilotkit"]["context"] if item.get("description") == "The current user's colleagues")
```

**流式白名单控制**（原文，`copilotkit_customize_config`）：

```python
from copilotkit.langgraph import copilotkit_customize_config

async def chat_node(state: AgentState, config: RunnableConfig):
    # Only stream "showWeather" to the frontend; suppress all others
    streaming_config = copilotkit_customize_config(
        config,
        emit_tool_calls=["showWeather"],
    )

    model = ChatOpenAI(model="gpt-4o").bind_tools(
        state["copilotkit"]["actions"]
    )
    response = await model.ainvoke(state["messages"], streaming_config)
    return {"messages": response}
```

`emit_tool_calls` 取值语义（原文表格）：

| Value | Effect |
|---|---|
| `True` (default) | Stream all tool calls to the frontend |
| `False` | Suppress all tool call streaming for this LLM call |
| `"ToolName"` | Stream only the named tool call |
| `["Tool1", "Tool2"]` | Stream only the listed tool calls |

原文附带的 Callout 警告：始终把结果赋给新变量（如 `streaming_config`），不要覆写 `config`——LangGraph Python 中 `config` 会被隐式传给作用域内所有 LangChain LLM 调用。

原文还给出了一张"选哪种模式"的对照表：

| Goal | Recommended pattern |
|---|---|
| Agent imperatively triggers a browser action (toast, state update) | `copilotkit_emit_tool_call` → `useFrontendTool` |
| LLM decides when to trigger a browser action | `bind_tools(state["copilotkit"]["actions"])` → `useFrontendTool` |
| LLM renders a rich component in chat (persisted in history) | `bind_tools(state["copilotkit"]["actions"])` → `useComponent` |
| Hide certain LLM tool calls from the frontend | `copilotkit_customize_config(emit_tool_calls=[...])` |
| Hide all tool calls from the frontend for a sensitive LLM call | `copilotkit_customize_config(emit_tool_calls=False)` |

### 3.4 AG-UI：状态与生命周期的线上格式

出处：<https://docs.copilotkit.ai/concepts/architecture>、<https://docs.copilotkit.ai/agentic-protocols/ag-ui>，协议细则出处：<https://docs.ag-ui.com/concepts/events>、<https://docs.ag-ui.com/concepts/interrupts>

**分层定义**（原文）："CopilotKit is a three-layer stack — **frontend, runtime, agent** — connected by the open **AG-UI** event protocol. The runtime lives in your own application server, so the only thing between your UI and your agent is a wire format you can inspect."

- **Frontend**："A framework-native SDK and prebuilt chat components that connect your UI to a running agent."
- **Runtime**："A request handler mounted in your app server (Next.js, Express, Hono, Bun, Deno, Workers). Brokers auth, tool calls, and the AG-UI stream."
- **Agent**："Any AG-UI-compatible backend — Built-in, LangGraph, Mastra, CrewAI, Pydantic AI, MAF, or your own."
- **AG-UI**："the wire format: 16 event types, transport-agnostic, framework-agnostic. Swap any layer without rewriting the others."

AG-UI 特性（原文 bullets）：**Event-driven**（16 standardized event types：text deltas, tool calls, state snapshots and deltas, run lifecycle）、**Bidirectional**（"users send input, agents respond, agents pause for human-in-the-loop input, frontends expose frontend tools the agent can invoke"）、**Transport-agnostic**（SSE, WebSockets, webhooks）、**Framework-agnostic**。

**请求流（原文 7 步）**：1. 用户在前端应用发消息；2. 前端 agent API POST 到你的 runtime 端点；3. Runtime 与所配 agent 打开 AG-UI 会话；4. Agent 以 AG-UI 事件发出文本、工具调用、状态更新；5. Runtime 把事件流回前端并实时渲染；6. 若 agent 调用前端工具，runtime 转发请求，浏览器 handler 执行，结果回流入 agent；7. Threads、持久化、实时同步（配置后）由 CopilotKit Intelligence 中介。

**事件分类与基础属性**（AG-UI 上游原文）：

| Category | Description |
| - | - |
| Lifecycle Events | Monitor the progression of agent runs |
| Text Message Events | Handle streaming textual content |
| Tool Call Events | Manage tool executions by agents |
| State Management Events | Synchronize state between agents and UI |
| Activity Events | Represent ongoing activity progress |
| Subagent Events | Track subagents and attribute their output |
| Special Events | Support custom functionality |
| Draft Events | Proposed events under development |

所有事件的公共基础属性：`type`（The specific event type identifier）、`timestamp`（optional）、`rawEvent`（optional）、`metadata`（"an optional, open-by-key object for attaching extra information to an event — token usage, a trace id, a finish reason. It is declared once on the base event, so every event type carries it."）。多数事件另接受可选 `subagentRunId`，表该事件由哪个 subagent 产生；不带该字段的事件属于父 agent。

**生命周期事件**（原文）：run 以 `RunStarted` 开始，中间可含多组可选 `StepStarted`/`StepFinished`，并以 `RunFinished`（成功）或 `RunError`（失败）结束。"The `RunStarted` and either `RunFinished` or `RunError` events are mandatory, forming the boundaries of an agent run."

- `RunStarted` 属性：`threadId`（ID of the conversation thread）、`runId`（ID of the agent run）、`parentRunId`（Optional，lineage pointer for branching/time travel，"creating a git-like append-only log"）、`input`（Optional，"The exact agent input payload that was sent to the agent for this run"）。
- `RunError` 属性：`message`、`code`（optional）。
- `StepStarted` / `StepFinished` 属性：`stepName`（"The `stepName` must match the corresponding `StepStarted` event to properly pair the beginning and end of the step"）。

**文本消息事件**：`TextMessageStart`（`messageId`、`role`，role ∈ `"developer" | "system" | "assistant" | "user" | "tool"`）→ 多个 `TextMessageContent`（`messageId`、`delta`，"Text content chunk (non-empty)"）→ `TextMessageEnd`（`messageId`）。原文强调："Frontends should concatenate these deltas in the order received to construct the complete message. The `messageId` property links all related events"。便捷事件 `TextMessageChunk` 会自动展开为 Start → Content → End 三段（首个 chunk 必须包含 `messageId`；`TextMessageEnd` 在流切换到新 message ID 或流结束时自动发出）。

**工具调用事件**：`ToolCallStart`（`toolCallId`、`toolCallName`、`parentMessageId?`）→ 多个 `ToolCallArgs`（`toolCallId`、`delta`，原文："These deltas are often JSON fragments that, when combined, form the complete arguments object for the tool"）→ `ToolCallEnd`（`toolCallId`）→ `ToolCallResult`（`messageId`、`toolCallId`、`content`、`role?`，通常为 "tool"）。便捷事件 `ToolCallChunk` 自动展开为 Start → Args → End（首个 chunk 必须含 `toolCallId` 与 `toolCallName`）。

**状态管理事件**（对本主题最核心，原文逐条）：

> "State management in the protocol follows an efficient snapshot-delta pattern where complete state snapshots are sent initially or infrequently, while incremental updates (deltas) are used for ongoing changes."

- `StateSnapshot` —— 属性 `snapshot`（Complete state snapshot）。原文："Frontends should replace their existing state model with the contents of this snapshot rather than trying to merge it with previous state."
- `StateDelta` —— 属性 `delta`（"Array of JSON Patch operations (RFC 6902)"）。原文："Frontends should apply these patches in sequence to maintain an accurate state representation. If a frontend detects inconsistencies after applying patches, it may request a fresh `StateSnapshot`."
- `MessagesSnapshot` —— 属性 `messages`（Array of message objects）。原文对 `activity` 与 `reasoning` 消息的合并规则给出明确约定："`activity` and `reasoning` messages are all-or-nothing inside a `MessagesSnapshot`. If the snapshot carries any message of that role, it is the complete set for that role: entries it repeats replace the client's copies, and ones it leaves out are removed. If it carries none, the snapshot says nothing about that role and the client keeps the messages it already has." 并补充："Activity messages never travel back to the agent — they are stripped from `RunAgentInput` — and reasoning usually exists only as streamed `Reasoning` events."

**Activity 事件**：`ActivitySnapshot`（`messageId`、`activityType`，如 `"PLAN"` / `"SEARCH"`、`content`（Structured JSON payload representing the full activity state）、`replace`（optional，默认 `true`；为 `false` 时若消息已存在则忽略该 snapshot））与 `ActivityDelta`（`messageId`、`activityType`、`patch`（Array of RFC 6902 JSON Patch operations））。原文约束："An activity message occupies the same id space as every other message, so its `messageId` must not be reused by a text or reasoning message, and vice versa."

**Special 事件**：`Raw`（`event`（Original event data）、`source?`（Optional source identifier））；`Custom`（`name`（Name of the custom event）、`value`（Value associated with the event））。原文："The `Custom` event provides an extension mechanism for implementing features not covered by the standard event types... The `name` property identifies the specific custom event type, while the `value` property contains the associated data."

**Reasoning 事件**：`ReasoningStart` → `ReasoningMessageStart`（`messageId`、`role`（`"reasoning"`））→ `ReasoningMessageContent`（`messageId`、`delta`）→ `ReasoningMessageEnd` → `ReasoningEnd`；便捷事件 `ReasoningMessageChunk`（首个含 `messageId` 的 chunk 隐式开始，空 `delta` 或下一个非 reasoning 事件隐式关闭）。另有 `ReasoningEncryptedValue`（`subtype`（`"message"` 或 `"tool-call"`）、`entityId`、`encryptedValue`），原文说明："The client stores and forwards these encrypted values opaquely—only the agent (or authorized backend) can decrypt them."

**Subagent 事件**：`SubagentStarted`（`subagentRunId`、`name`、`description?`、`parentSubagentRunId?`、`parentToolCallId?`、`parentMessageId?`）、`SubagentFinished`（`subagentRunId`、`result?`、`outcome?`（`{ type: "success" }` 或 `{ type: "suspended", interruptIds?: string[] }`））、`SubagentError`（`subagentRunId`、`message`、`code?`）。原文强调 `subagentRunId` "identifies **one invocation**, not a reusable subagent definition"。另有一条对状态归属的明确约定："`StateSnapshot` and `StateDelta` are attributable, but attribution on them is **provenance, not ownership**... State stays run-scoped and an attributed snapshot or delta is applied to the run's one state document, exactly as an unattributed one is. There is no per-subagent state."

**废弃事件**：`THINKING_START` → `REASONING_START`；`THINKING_END` → `REASONING_END`；`THINKING_TEXT_MESSAGE_START` → `REASONING_MESSAGE_START`；`THINKING_TEXT_MESSAGE_CONTENT` → `REASONING_MESSAGE_CONTENT`；`THINKING_TEXT_MESSAGE_END` → `REASONING_MESSAGE_END`。（原文标注："will be removed in version 1.0.0"）

**三类事件流模式**（原文）：Start-Content-End Pattern（流式内容）；Snapshot-Delta Pattern（状态同步）；Lifecycle Pattern（run 监控）。实现注意事项：事件按接收顺序处理；同一 ID（`messageId`、`toolCallId`）的事件属于同一逻辑流；实现应对乱序投递保持韧性。

### 3.5 中断的线上格式（Interrupt 类型与 resume）

出处：<https://docs.ag-ui.com/concepts/interrupts>（CopilotKit 文档在 `human-in-the-loop/useInterrupt` 与 `agentic-protocols/ag-ui-middleware` 中显式引用该上游页面）

> "AG-UI exposes this as an **interrupt-aware run lifecycle** — a terminal model where the run ends with an interrupt outcome, and the client starts a new run carrying per-interrupt responses."

`RunFinished` 的 outcome 判别联合（原文）：

```typescript
type RunFinishedOutcome =
  | { type: "success" }
  | { type: "interrupt"; interrupts: Interrupt[] }

type RunFinishedEvent = {
  type: "RUN_FINISHED"
  threadId: string
  runId: string
  result?: unknown
  outcome?: RunFinishedOutcome
}
```

三个分支的语义（原文）：**omitted** —— "legacy producer that has not yet adopted the interrupt-aware lifecycle. Treated as a normal completion."；`{ type: "success" }` —— 正常完成，可选 `result` 保留在事件根部以兼容旧版；`{ type: "interrupt", interrupts: [...] }` —— run 为等待人工输入而暂停，`interrupts` 为非空数组。

`Interrupt` 类型（原文）：

```typescript theme={null}
type Interrupt = {
  id: string
  reason: string
  message?: string
  toolCallId?: string
  responseSchema?: JsonSchema
  expiresAt?: string
  metadata?: Record<string, any>
  subagentRunId?: string
}
```

字段语义（原文表格）：

| Field | Purpose |
| - | - |
| `id` | Correlation key across interrupt, resume, idempotency, and audit. |
| `reason` | Categorical routing hint — see Reason taxonomy. |
| `message` | Human-readable prompt. Universal fallback UI content. |
| `toolCallId` | Binds the interrupt to a prior `ToolCall*` sequence. |
| `responseSchema` | JSON Schema for the expected `resume.payload`. |
| `expiresAt` | Optional ISO-8601 TTL. Stale resumes produce `RunError`. |
| `metadata` | Free-form framework-specific data. |
| `subagentRunId` | The subagent whose work raised this interrupt — absent for a root-raised one. |

恢复（resume）的输入结构（原文）：

```typescript theme={null}
type RunAgentInput = {
  // ... existing fields
  resume?: Array<{
    interruptId: string
    status: "resolved" | "cancelled"
    payload?: any
    metadata?: Record<string, any>
  }>
}
```

状态语义（原文）：`resolved` —— 用户已响应，`payload` 承载响应，并按该 interrupt 的 `responseSchema` 校验；**拒绝是在 payload 内表达（例如 `{ approved: false }`），而不是单独的 status**。`cancelled` —— 用户放弃且未提供有意义输入，`payload` 应省略。`metadata` 为可选的响应信封数据（例如证明人工决定未被篡改的签名、路由键），与 `payload`（agent 请求并会据以行动的答案）区分开；按 key 开放，`ag-ui` 键为 AG-UI 保留。

**契约规则（原文 8 条，逐字要点）**：

1. **Same thread.** Resume requests must use the same `threadId` as the interrupted run.
2. **Resume linkage.** `resume[].interruptId` must reference an `id` from the interrupted run's `interrupts[]`.
3. **Cover all open interrupts.** A single `resume` array must address every open interrupt from the interrupted run. Partial resumes are not supported.
4. **Pending interrupts block new input.** If a thread has unresolved interrupts, any `RunAgentInput` on that thread must include a `resume` addressing them. Agents receiving a non-conforming input must emit `RunError`.
5. **Idempotency.** A resume with the same `(threadId, interruptId, status, payload)` must be safe to replay.
6. **Payload validation.** If an interrupt declares a `responseSchema`, the agent may validate the corresponding resume `payload` and emit `RunError` on mismatch.
7. **Expiry enforcement.** Clients must not submit a resume past an interrupt's `expiresAt`. Stale resumes produce `RunError`.
8. **Graceful handling.** Agents should handle missing or invalid resume payloads via `RunError`, not silent failures.

最小工具审批的完整 JSON 往返（原文）：

```json theme={null}
{
  "type": "RUN_FINISHED",
  "threadId": "thread-1",
  "runId": "run-1",
  "outcome": {
    "type": "interrupt",
    "interrupts": [
      {
        "id": "int-abc123",
        "reason": "tool_call",
        "message": "Send email to a@b.com with subject 'Hi'?",
        "toolCallId": "tc-001",
        "responseSchema": {
          "type": "object",
          "properties": { "approved": { "type": "boolean" } },
          "required": ["approved"]
        }
      }
    ]
  }
}
```

```json theme={null}
{
  "threadId": "thread-1",
  "runId": "run-2",
  "resume": [
    { "interruptId": "int-abc123", "status": "resolved", "payload": { "approved": true } }
  ]
}
```

> "The agent continues in `run-2`, emits `ToolCallResult` against `tc-001`, then `RunFinished { outcome: { type: "success" } }`."

该页另有一节 "State at the interrupt boundary"（在中断边界处发送快照，见生命周期时序图："Agent needs user input — emit snapshot, then interrupt"），以及 "Reason taxonomy"（Core values / Custom reasons / Client routing）、"Tool-bound interrupts" 与 "Approve with edits" 的完整审计示例。

### 3.6 AG-UI Middleware（服务端事件改写）

出处：<https://docs.copilotkit.ai/agentic-protocols/ag-ui-middleware>

> "AG-UI agents expose a middleware layer via `agent.use(middleware)`, a powerful hook for logging, guardrails, request transformation, and event rewriting. Because CopilotKit runs the middleware server-side inside the Copilot Runtime, it executes in a trusted environment where the client cannot tamper with it."

定义与挂载（原文）：

```ts title="my-middleware.ts"
import {
  Middleware,
  RunAgentInput,
  AbstractAgent,
  BaseEvent,
} from "@ag-ui/client";
import { Observable } from "rxjs";

export class LoggingMiddleware extends Middleware {
  run(input: RunAgentInput, next: AbstractAgent): Observable<BaseEvent> {
    return new Observable<BaseEvent>((subscriber) => {
      const sub = this.runNextWithState(input, next).subscribe({
        next: ({ event }) => {
          console.log("[agent event]", event.type);
          subscriber.next(event);
        },
        error: (err) => subscriber.error(err),
        complete: () => subscriber.complete(),
      });
      return () => sub.unsubscribe();
    });
  }
}
```

```ts title="app/api/copilotkit/[[...slug]]/route.ts"
import { CopilotRuntime, InMemoryAgentRunner } from "@copilotkit/runtime/v2";
import { LangGraphAgent } from "@copilotkit/runtime/langgraph";
import { LoggingMiddleware } from "./my-middleware";

const agent = new LangGraphAgent({
  deploymentUrl: process.env.LANGGRAPH_DEPLOYMENT_URL!,
  graphId: "sample_agent",
});
agent.use(new LoggingMiddleware());

const runtime = new CopilotRuntime({
  agents: { default: agent },
  runner: new InMemoryAgentRunner(),
});
```

内置 middleware（原文）：`a2ui`（对全部或部分已注册 agent 应用 `A2UIMiddleware`）、`mcpApps`（在一处为所有 agent 配置 MCP server）。

### 3.7 协议组合（AG-UI / MCP / A2A / 生成式 UI 规范）

出处：<https://docs.copilotkit.ai/agentic-protocols>

原文表格（按"连接关系"分类）：

| Connection | Protocol | Purpose |
|---|---|---|
| Agent ↔ User Interaction | **AG-UI**（Agent–User Interaction Protocol） | "The open, event-based standard that connects agents to user-facing applications — enabling real-time, multimodal, interactive experiences." |
| Agent ↔ Tools & Data | **MCP**（Model Context Protocol） | "Open standard that lets agents securely connect to external systems — tools, workflows, and data sources." |
| Agent ↔ Agent | **A2A**（Agent to Agent） | "Defines how agents coordinate and share work across distributed agentic systems." |
| Agent ↔ Generative UI | **A2UI**（Google）、**MCP Apps**（MCP Ecosystem）、**Open-JSON-UI**（OpenAI） | "Declarative, LLM-friendly generative UI specs that define *what* to render and how to structure agent responses visually. CopilotKit fully supports all of these." |

原文补充："since AG-UI also includes **handshakes** with both **MCP** and **A2A**, CopilotKit can connect to MCP or A2A supporting agents through AG-UI. This means that if your host agent connects to subagents using **MCP** or **A2A**, their UI properties can be propagated all the way through to the user-facing application — while preserving **full security, policy, and observability controls.**"

### 3.8 `useAgent`：agent 实例契约

出处：<https://docs.copilotkit.ai/reference/v1/hooks/useAgent>、<https://docs.copilotkit.ai/programmatic-control>

签名与参数（原文）：

```tsx
function useAgent(options?: UseAgentProps): { agent: AbstractAgent }
```

- `agentId: string`，默认 `'"default"'`——"Must match an agent configured in `CopilotKitProvider`."
- `updates: UseAgentUpdate[]`，默认 `[OnMessagesChanged, OnStateChanged, OnRunStatusChanged]`——控制哪些 agent 变化触发组件重渲染；三个枚举为 `UseAgentUpdate.OnMessagesChanged` / `OnStateChanged` / `OnRunStatusChanged`；传空数组 `[]` 可禁用自动重渲染。

> "**Throws error** if no agent is configured with the specified `agentId`."

`AbstractAgent` 核心属性：`agentId`、`description`、`threadId`、`messages`（"Each message contains: `id: string` / `role: "user" | "assistant" | "system"` / `content: string`"）、`state`（"Shared state object synchronized between application and agent. Both can read and modify this state."）、`isRunning`、`debug`。

`AbstractAgent` 方法（原文签名）：

- `runAgent(parameters?, subscriber?) => Promise<RunAgentResult>`——"Manually triggers agent execution. Resolves with a `RunAgentResult` (`{ result, newMessages }`) once the run finalizes." 参数 `parameters.forwardedProps?: any`（"Data to pass to the agent execution context"）；示例 `agent.runAgent({ forwardedProps: { command: { resume: "user response" } } })`。
- `setState(newState: any) => void`——"Updates the shared state. Changes are immediately available to both application and agent."
- `subscribe(subscriber: AgentSubscriber) => { unsubscribe: () => void }`——订阅事件，返回清理函数。
- `addMessage(message) => void` / `addMessages(messages) => void`（"notifies subscribers once"）/ `setMessages(messages) => void`（"Replaces the entire message history"）。
- `connectAgent(options?) => Promise<RunAgentResult>`——"Connects to a streaming agent endpoint. Similar to `runAgent` but uses the `connect()` method for persistent connections."
- `detachActiveRun() => Promise<void>`——"Detaches from the currently active agent run without aborting it. The run continues in the background but stops updating the local agent state."
- `abortRun() => void`、`clone() => AbstractAgent`、`use(...middlewares: Middleware[]) => this`。

`AgentSubscriber` 事件清单（原文）：`onCustomEvent?: ({ event: { name: string, value: any } }) => void`（"Custom events (e.g., LangGraph interrupts)"）、`onRunStartedEvent?`、`onRunFinalized?`、`onStateChanged?`、`onMessagesChanged?`。

**`copilotkit.runAgent()` 与 `agent.runAgent()` 的分工**（原文逐字）：

- "**`copilotkit.runAgent({ agent })`** — The recommended approach. Orchestrates the full agent lifecycle: executes frontend tools, handles follow-up runs when tools request them, and manages errors through the subscriber system."
- "**`agent.runAgent()`** — Low-level method on the agent instance. Sends the request to the runtime but does **not** execute frontend tools or handle follow-ups. Use this only when you need direct control over the agent execution (e.g., resuming from an interrupt with `forwardedProps`)."

停止执行：`copilotkit.stopAgent({ agent })`。原文另注："This is the same method CopilotKit's built-in `<CopilotChat />` uses internally."

---

## 主题 4：CopilotChat 的消息与上下文注入

### 4.1 上下文注入：`useCopilotReadable`（v1）

出处：<https://docs.copilotkit.ai/reference/v1/hooks/useCopilotReadable>

> "`useCopilotReadable` is a React hook that provides app-state and other information to the Copilot."

最小用法（原文）：

```tsx
import { useCopilotReadable } from "@copilotkit/react-core";

export function MyComponent() {
  const [employees, setEmployees] = useState([]);

  useCopilotReadable({
    description: "The list of employees",
    value: employees,
  });
}
```

参数语义（原文）：

- `description: string`（required）——"The description of the information to be added to the Copilot context."
- `value: any`（required）——"The value to be added to the Copilot context. Object values are automatically stringified."
- `available: 'enabled' | 'disabled'`——"Whether the context is available to the Copilot."
- `convert: (description: string, value: any) => string`——"A custom conversion function to use to serialize the value to a string. If not provided, the value will be serialized using `JSON.stringify`."

自定义序列化（原文，注意参数顺序）："It is called with the description and the value, in that order"：

```tsx
useCopilotReadable({
  description: "The current user",
  value: user,
  convert: (description, value) => `${description}: ${value.firstName} ${value.lastName}`,
});
```

条件注册（原文）："Switching to `"disabled"` removes the entry from the Copilot context; switching back to `"enabled"` re-adds it."

```tsx
useCopilotReadable({
  description: "The list of employees",
  value: employees,
  available: showEmployees ? "enabled" : "disabled",
});
```

自定义依赖（原文）："The context is refreshed whenever `description`, `value`, `convert` or `available` change. Pass a second argument to add your own dependencies"：

```tsx
useCopilotReadable(
  { description: "The selected employee", value: employee },
  [departmentId],
);
```

### 4.2 上下文注入：`useAgentContext`（v2，当前 API）

出处：<https://docs.copilotkit.ai/reference/hooks/useAgentContext>、<https://docs.copilotkit.ai/agent-app-context>、<https://docs.copilotkit.ai/shared-state/agent-readonly>

> "This is the v2 equivalent of `useCopilotReadable` -- it lets you surface any serializable application state (user preferences, selected items, computed values, etc.) as context that agents can reference when generating responses or making decisions."

签名（原文）：

```tsx
import { useAgentContext } from "@copilotkit/react-core/v2";

function useAgentContext(context: AgentContextInput): void;
```

- `description: string`（required）——"A human-readable description of the context. The agent uses this to understand what the value represents and when to reference it."
- `value: JsonSerializable`（required）——"Must be JSON-serializable: `string`, `number`, `boolean`, `null`, arrays, or plain objects with string keys and serializable values. Anything that is not already a string is stringified with `JSON.stringify` before it is sent, so the agent receives a JSON string rather than the value you passed."

**线上格式（关键契约，原文给出前后对照）**：

```tsx
useAgentContext({
  description: "Incident dashboard records",
  value: [{ id: "INC-1041", severity: "sev1" }],
});
```

delivers this to the agent:

```json
{
  "description": "Incident dashboard records",
  "value": "[{\"id\":\"INC-1041\",\"severity\":\"sev1\"}]"
}
```

原文对 `value` 为何是字符串的解释："The AG-UI protocol types it as a string, so this is not an implementation detail you can ignore when writing the agent."

agent 侧解析（原文 Python / TypeScript 两版）：

```python
import json

def dashboard_records(context):
    entry = next(
        (item for item in context if item["description"] == "Incident dashboard records"),
        None,
    )
    return None if entry is None else json.loads(entry["value"])
```

```ts
const entry = context.find(
  (item) => item.description === "Incident dashboard records",
);
const records = entry ? JSON.parse(entry.value) : undefined;
```

原文对错误的明确警告（Callout，标题 "Context values arrive as JSON strings"）："Do not stringify the value again, because that produces double encoding. A `value` that is already a string is sent unchanged, so no parse step is needed for it." 以及另一处 Callout："Do not type-check `value` against the shape you registered. `isinstance(entry["value"], list)` in Python, or `Array.isArray(entry.value)` in TypeScript, can never be true, because `value` is always a string on the wire. An agent that reads such a failed check as 'no context was sent' will refuse every request while the browser is registering context correctly, and the two cases are indistinguishable from the UI."

按框架取用（原文给出 Google ADK 的落地方式）：

```python
from ag_ui_adk import CONTEXT_STATE_KEY

def dashboard_records_for_adk(ctx):
    return dashboard_records(ctx.state.get(CONTEXT_STATE_KEY, []))
```

> "`ag-ui-adk` stores the request's context list in ADK session state under `CONTEXT_STATE_KEY`, a public export whose value is `"_ag_ui_context"`... The entries are dictionaries with string `description` and `value` fields. To make these values visible to the model, include them in an instruction provider or a before-model callback; `AGUIToolset()` alone does not inject them into the prompt."

行为约定（原文 bullets）：**Mount/Unmount lifecycle**（"The context is registered when the component mounts and automatically removed when it unmounts. There is no manual cleanup required."）；**Reactive updates**；**Serialization**（"Non-serializable values such as functions, class instances, or symbols will cause errors."）；**Multiple contexts**（"Multiple `useAgentContext` calls across your component tree are all visible to the agent concurrently. Each is identified by its description and value."）；**No return value**（"Unlike `useCopilotReadable`, it does not return an ID for parent-child hierarchies."）。

`useAgentContext` 与共享状态的定位差异（原文 `shared-state/agent-readonly`）：

> "Unlike full shared state (where the agent can call tools that mutate the state back to the UI), `useAgentContext` values are pure inputs. The agent sees them on every turn via the runtime's context injection, but it has no setter and no tool to write them back."
> "Think of it as 'props for the agent'."

原文给出的适用判据：值由 UI 拥有且对 agent 无独立含义；agent 只读不写（user identity、feature flags、selected record、scroll position）；希望值在卸载时自动注销。并强调 `description` 的重要性："it's a short human-readable label the agent sees alongside the value, so it knows what to do with it. Treat it like a parameter docstring."

同页还记录了两条 inspector 可观测性提示（原文 Callout）：`useHumanInTheLoop` 注册的工具与 schema 可在 Inspector 的 **Agents → Frontend Tools** 看到；`useAgentContext` 发布的值可在 **Agents → Context** 看到。

### 4.3 `CopilotChat` 组件与 labels 注入

出处：<https://docs.copilotkit.ai/reference/v1/components/chat/CopilotChat>、<https://docs.copilotkit.ai/agentic-chat-ui>

安装与基础用法（原文）：

```tsx
import { CopilotChat } from "@copilotkit/react-ui";
import "@copilotkit/react-ui/styles.css";

<CopilotChat
  labels={{
    title: "Your Assistant",
    initial: "Hi! 👋 How can I assist you today?",
  }}
/>
```

文档要求的包裹方式（原文）："Wrap your UI in `<CopilotKit>` once (it wires the runtime, session, and agent registry) and drop `<CopilotChat>` wherever the chat should go"：

```typescript
<CopilotKit runtimeUrl="/api/copilotkit" agent="agentic_chat">
  <Chat />
</CopilotKit>
```

与消息/上下文直接相关的 props（原文语义）：

- `instructions: string` —— "Custom instructions to be added to the system message. Use this property to provide additional context or guidance to the language model, influencing its responses."
- `makeSystemMessage: SystemMessageFunction` —— "A function that takes in context string and instructions and returns the system message to include in the chat request. Use this to completely override the system message, when providing `instructions` is not enough."
- `disableSystemMessage: boolean` —— "Disables inclusion of CopilotKit's default system message. When true, no system message is sent (this also suppresses any custom message from `makeSystemMessage`)."
- `suggestions: ChatSuggestions` —— 三态语义：`auto`（默认；在 chat 首次打开的空态与每轮消息交换完成后自动生成，使用 `useCopilotChatSuggestions` 的配置）、`manual`（用 `setSuggestions()` 设自定义、用 `generateSuggestions()` 触发 AI 生成）、`SuggestionItem[]`（静态数组，永远显示同样的建议，不涉及 AI 生成）。
- `attachments: AttachmentsConfig` —— 文件附件配置："Supports images, audio, video, and documents. Omit `accept` to allow all file types, or restrict with a MIME filter." 示例：

```tsx
<CopilotChat
  attachments={{
    enabled: true,
    accept: "image,audio,video,application/pdf",
    maxSize: 10 * 1024 * 1024, // 10MB
    onUpload: async (file) => {
      const url = await uploadToS3(file);
      return { type: "url", value: url, mimeType: file.type };
    },
  }}
/>
```

- `observabilityHooks: CopilotObservabilityHooks`（"Event hooks for CopilotKit chat events."）、`onInProgress`、`onSubmitMessage`、`onStopGeneration`、`onReloadMessages`、`onRegenerate`、`onCopy`、`onThumbsUp: (message, isActive?) => void`（原文说明 `isActive` 语义："`true` when thumbs up is being applied, `false` when it is being retracted. It is optional so existing one-argument handlers keep working."）、`onThumbsDown`、`markdownTagRenderers: ComponentsMap`（"When you want to render custom elements in the message (e.g a reference tag element)"）、`icons`、`labels`、`AssistantMessage` / `UserMessage` / `ErrorMessage` / `Messages` / `RenderMessage` / `RenderSuggestionsList` / `Input`（自定义组件替换位）、`className`、`children`、`hideStopButton`、`renderError`、`onError`。

`labels` 的注入示例（原文，来自 shared-state 页的 `CopilotChat` 用法）：

```tsx
<CopilotChat
  labels={{
    welcomeMessageText: "I can help manage your todos. Try 'Add a task to buy groceries'.",
  }}
/>
```

建议提示的配置钩子（原文 `agentic-chat-ui`）：

```typescript
export function useAgenticChatSuggestions() {
  useConfigureSuggestions({
    suggestions: [
      { title: "Write a sonnet", message: "Write a short sonnet about AI." },
      { title: "Tell me a joke", message: "Tell me a one-line joke." },
      { title: "Is 17 prime?", message: "Walk me through whether 17 is prime." },
    ],
    available: "always",
  });
}
```

### 4.4 消息模型与 headless 消息操作

出处：<https://docs.copilotkit.ai/reference/v1/hooks/useCopilotChatHeadless_c>、<https://docs.copilotkit.ai/reference/v1/hooks/useCopilotChat>、<https://docs.copilotkit.ai/programmatic-control>

消息结构（原文，来自 `useAgent` 参考页）：`messages: Message[]`，每条含 `id: string`（Unique message identifier）、`role`、`content: string`。`programmatic-control` 页额外展示了 assistant 消息上的 tool-call 结构：

```tsx
{message.role === "assistant" &&
  message.toolCalls?.map((toolCall) => {
    const toolMessage = agent.messages.find(
      (m) => m.role === "tool" && m.toolCallId === toolCall.id,
    );
    return (
      <div key={toolCall.id}>
        {renderToolCall({ toolCall, toolMessage })}
      </div>
    );
  })}
```

即：assistant 消息携带 `toolCalls[]`（每项有 `id`），结果消息 `role: "tool"` 通过 `toolCallId` 与之配对。

`useCopilotChatHeadless_c` 的返回项（原文，"for building fully custom UI (headless UI) implementations"）：

| 返回项 | 签名 / 语义 |
| --- | --- |
| `messages` | `Message[]` — "The messages currently in the chat in AG-UI format" |
| `sendMessage` | `(message: Message, options?) => Promise<void>` — "Send a new message to the chat and trigger AI response" |
| `setMessages` | `(messages: Message[] \| DeprecatedGqlMessage[]) => void` |
| `deleteMessage` | `(messageId: string) => void` |
| `reloadMessages` | `(messageId: string) => Promise<void>` — "Regenerate the response for a specific message by ID" |
| `stopGeneration` | `() => void` |
| `reset` | `() => void` — "Clear all messages and reset chat state completely" |
| `isLoading` | `boolean` |
| `runChatCompletion` | `() => Promise<Message[]>` — "Manually trigger chat completion for advanced usage" |
| `mcpServers` / `setMcpServers` | `MCPServerConfig[]` / `(servers: MCPServerConfig[]) => void` |
| `suggestions` / `setSuggestions` | `SuggestionItem[]` / `(suggestions: SuggestionItem[]) => void` |
| `generateSuggestions` / `resetSuggestions` / `isLoadingSuggestions` | 触发 AI 建议生成 / 清空 / 加载态 |
| `interrupt` | `string \| React.ReactElement \| null` — "Interrupt content for human-in-the-loop workflows" |

发消息的原文示例：

```tsx
const { messages, sendMessage, isLoading } = useCopilotChatHeadless_c();

const handleSendMessage = async () => {
  await sendMessage({
    id: "123",
    role: "user",
    content: "Hello World",
  });
};
```

v1 的 `useCopilotChat`（较旧、非 headless）返回 `visibleMessages`、`appendMessage: (message: DeprecatedGqlMessage, options?) => Promise<void>`、`reloadMessages`、`stopGeneration`、`reset`、`isLoading`、`runChatCompletion`、`mcpServers` / `setMcpServers`。其参数含 `id`（"When provided, the `useChat` hook with the same `id` will have shared states across components."）、`headers`、`initialMessages: Message[]`、`makeSystemMessage`、`disableSystemMessage`、`suggestions`、`onInProgress`、`onSubmitMessage`、`onStopGeneration`、`onReloadMessages`。文档标注 `appendMessage` 与 `visibleMessages` 为 deprecated，并说明 "This is the public v1 programmatic-send path. `sendMessage` is not part of the public v1 return type."

**程序化运行 agent（v2 路径，原文）**：

```tsx
import { useAgent } from "@copilotkit/react-core/v2";
import { useCopilotKit } from "@copilotkit/react-core/v2";
import { randomUUID } from "@copilotkit/shared";

export function RunAgent() {
  const { agent } = useAgent();
  const { copilotkit } = useCopilotKit();

  const handleRun = async () => {
    agent.addMessage({
      id: randomUUID(),
      role: "user",
      content: "Hello, agent!",
    });

    await copilotkit.runAgent({ agent });
  };

  return <button onClick={handleRun}>Send</button>;
}
```

headless 下的 kick-off（原文，来自 `human-in-the-loop/headless`）：

```tsx
const kickOff = (prompt: string) => {
  agent.addMessage({ id: crypto.randomUUID(), role: "user", content: prompt });
  void copilotkit.runAgent({ agent });
};
```

### 4.5 注入可见性对照：readable / context / state 三类通道

综合本次抓取到的原文，CopilotKit 中"前端 → agent"的三条通道及其线上形态为：

| 通道 | API | 线上形态 | agent 能否写回 |
| --- | --- | --- | --- |
| Read-only context（v1） | `useCopilotReadable({ description, value, convert, available })` | 序列化后的字符串 + description，注入 Copilot 上下文 | 否 |
| Read-only context（v2，当前） | `useAgentContext({ description, value })` | AG-UI run input 上的 `{ description, value }` 条目，`value` 恒为 JSON 字符串；LangGraph 侧经 `CopilotKitMiddleware` 进入模型消息历史，并出现在 `state["copilotkit"]["context"]` | 否（"no setter and no tool to write them back"） |
| Shared state（双向） | `useAgent` 的 `agent.state` / `agent.setState` | AG-UI `StateSnapshot` / `StateDelta`（RFC 6902 JSON Patch）事件；Built-in Agent 经 `AGUISendStateSnapshot` / `AGUISendStateDelta` 工具发出 | 是（agent 与前端双向） |

---

## 主题 5：前端动作（useCopilotAction 及其 v2 后继）

### 5.1 v1：`useCopilotAction`

出处：<https://docs.copilotkit.ai/reference/v1/hooks/useCopilotAction>

原文机制描述：

> "`useCopilotAction` is a React hook that you can use in your application to provide custom actions that can be called by the AI. Essentially, it allows the Copilot to execute these actions contextually during a chat, based on the user's interactions and needs."
> "Then you define the parameters of the action, which can be simple, e.g. primitives like strings or numbers, or complex, e.g. objects or arrays. Finally, you provide a `handler` function that receives the parameters and returns a result. CopilotKit takes care of automatically inferring the parameter types, so you get type safety and autocompletion for free."

完整参数表（原文 `<PropertyReference>` 逐项）：

| 字段 | 类型 | 语义（原文） |
| --- | --- | --- |
| `name` | `string` (required) | The name of the action. |
| `handler` | `(args) => Promise<any>` (required) | The handler of the action. |
| `description` | `string` | A description of the action. This is used to instruct the Copilot on how to use the action. |
| `available` | `'enabled' \| 'disabled' \| 'remote'` | Use this property to control when the action is available to the Copilot. When set to `"remote"`, the action is available only for remote agents. |
| `followUp` | `boolean`（默认 `true`） | Whether to report the result of a function call to the LLM which will then provide a follow-up response. Pass `false` to disable |
| `parameters` | `Parameter[]` | 见下 |
| `render` | `string \| (props: ActionRenderProps<T>) => string` | 见 1.7 |
| `renderAndWaitForResponse` | `(props: ActionRenderPropsWait<T>) => React.ReactElement` | 见 1.7 |
| `dependencies` | `any[]` | An optional array of dependencies. |

`Parameter` 子结构（原文）：

| 字段 | 类型 | 语义 |
| --- | --- | --- |
| `name` | `string` (required) | The name of the parameter. |
| `type` | `string` (required) | One of `"string"` / `"number"` / `"boolean"` / `"object"` / `"object[]"` / `"string[]"` / `"number[]"` / `"boolean[]"` |
| `description` | `string` | A description of the argument. This is used to instruct the Copilot on what this argument is used for. |
| `enum` | `string[]` | For string arguments, you can provide an array of possible values. |
| `required` | `boolean` | Whether or not the argument is required. Defaults to true. |
| `attributes` | | If the argument is of a complex type, i.e. `object` or `object[]`, this field lets you define the attributes of the object. |

`attributes` 嵌套定义示例（原文）：

```js
{
  name: "addresses",
  description: "The addresses extracted from the text.",
  type: "object[]",
  attributes: [
    { name: "street", type: "string", description: "The street of the address." },
    { name: "city", type: "string", description: "The city of the address." },
    // ...
  ],
}
```

最小示例（原文）：

```tsx
useCopilotAction({
  name: "sayHello",
  description: "Say hello to someone.",
  parameters: [
    { name: "name", type: "string", description: "name of the person to say greet" },
  ],
  handler: async ({ name }) => {
    alert(`Hello, ${name}!`);
  },
});
```

页面顶部 Callout 明确："`useCopilotAction` is still supported, but we recommend migrating to `useFrontendTool` from the v2 API."

### 5.2 v2：`useFrontendTool`（当前 API）

出处：<https://docs.copilotkit.ai/reference/hooks/useFrontendTool>、<https://docs.copilotkit.ai/frontend-tools>

签名（原文）：

```tsx
import { useFrontendTool } from "@copilotkit/react-core/v2";

function useFrontendTool<T extends Record<string, unknown>>(
  tool: ReactFrontendTool<T>,
  deps?: ReadonlyArray<unknown>,
): void;
```

参数语义：

| 字段 | 类型 | 语义（原文） |
| --- | --- | --- |
| `name` | `string` (required) | A unique name for the tool. The agent references this name when deciding to call the tool. If a tool with this name is already registered, a warning is logged. |
| `description` | `string` (required) | A natural-language description that tells the agent what the tool does and when to use it. |
| `parameters` | `z.ZodSchema` (required) | A Zod schema defining the tool's input parameters. The schema drives the advertised tool schema and type inference. **Runtime arguments are JSON-parsed but are not validated against that schema.** |
| `handler` | `(args: T, context: FrontendToolHandlerContext) => Promise<unknown>` (required) | 见下 |
| `render` | `React.ComponentType<{ name: string; args: Partial<T>; status: ToolCallStatus; result: string \| undefined }>` | An optional React component rendered in the chat interface to visualize tool execution. |
| `available` | `'"enabled" \| "disabled" \| "remote"'`（默认 `"enabled"`） | Controls tool availability. Set to `"disabled"` to temporarily prevent the agent from calling the tool, or `"remote"` to indicate the tool is handled server-side. |
| `webmcp` | `boolean \| { annotations?: { readOnlyHint?: boolean; untrustedContentHint?: boolean } }` | Also expose the tool to browser agents through the WebMCP API (`document.modelContext`). |
| `deps` | `ReadonlyArray<unknown>` | An optional dependency array, similar to `useEffect`. |

`FrontendToolHandlerContext`（原文，"exported from `@copilotkit/core`"）：

- `toolCall`（`ToolCall`）——"the raw tool call metadata"
- `agent`（`AbstractAgent | undefined`）——"the agent instance that invoked the tool; absent when the tool is invoked through WebMCP"
- `signal`（`AbortSignal | undefined`）——"an `AbortSignal` that is aborted when the user stops the agent (via `stopAgent()` or `agent.abortRun()`). Long-running handlers can check `signal.aborted` to exit early."

基础示例（原文）：

```tsx
useFrontendTool(
  {
    name: "addTodo",
    description: "Add a new item to the user's todo list",
    parameters: z.object({
      text: z.string().describe("The todo item text"),
      priority: z.enum(["low", "medium", "high"]).describe("Priority level"),
    }),
    handler: async ({ text, priority }) => {
      setTodos((prev) => [...prev, text]);
      return `Added "${text}" with ${priority} priority`;
    },
  },
  [],
);
```

带自定义渲染与 AbortSignal 的示例（原文）：

```tsx
useFrontendTool(
  {
    name: "getWeather",
    description: "Fetch and display weather information for a city",
    parameters: z.object({
      city: z.string().describe("City name"),
      units: z.enum(["celsius", "fahrenheit"]).default("celsius"),
    }),
    handler: async ({ city, units }, { signal }) => {
      const response = await fetch(`/api/weather?city=${city}&units=${units}`, { signal });
      const data = await response.json();
      return JSON.stringify(data);
    },
    render: ({ args, status, result }) => {
      if (status === ToolCallStatus.InProgress) {
        return <div className="animate-pulse">Fetching weather for {args.city}...</div>;
      }
      if (status === ToolCallStatus.Complete && result) {
        const data = JSON.parse(result);
        return (
          <div className="p-4 border rounded">
            <h3>{data.city}</h3>
            <p>{data.temperature}&deg; {data.units}</p>
            <p>{data.conditions}</p>
          </div>
        );
      }
      return null;
    },
  },
  [],
);
```

条件可用性（原文）：

```tsx
useFrontendTool(
  {
    name: "deleteUser",
    description: "Delete a user account by ID (admin only)",
    parameters: z.object({ userId: z.string().describe("The ID of the user to delete") }),
    handler: async ({ userId }) => {
      await fetch(`/api/users/${userId}`, { method: "DELETE" });
      return `User ${userId} deleted`;
    },
    available: isAdmin ? "enabled" : "disabled",
  },
  [isAdmin],
);
```

行为约定（原文 bullets）：**Duplicate detection**（"If a tool with the same `name` is already registered, the hook logs a warning. Only one tool per name is active at a time."）；**Mount/Unmount lifecycle**；**Dependency tracking**（"similar to `useEffect`"）；**WebMCP registration**（"With `webmcp` set, the tool is also registered on `document.modelContext` and unregistered on unmount. The tool needs a `description` for this (WebMCP rejects tools without one)."）；**Render component lifecycle**（"It receives streaming `args` (partial during `InProgress`, complete during `Executing` and `Complete`)."）；**No return value**。

原文对"agent 何时可靠调用前端工具"给出一条实现建议（出处同上）：对简单 UI 控制类工具，`description` 通常足够；对于**必须**调用的工具，应在 agent 的 `system_prompt` 中显式指示：

```python title="agent.py — make mandatory tools explicit in system_prompt"
graph = create_agent(
    model="openai:gpt-4o",
    tools=[],
    middleware=[CopilotKitMiddleware()],
    state_schema=CopilotKitState,
    system_prompt=(
        "You are a helpful assistant.\n\n"
        "IMPORTANT: When the user asks about current sales data, you MUST call "
        "the `fetch_sales_by_month_range` frontend tool first. "
        "Never answer from memory or previously seen context values."
    ),
)
```

### 5.3 v1：`useFrontendTool` 与 `useDefaultTool`

出处：<https://docs.copilotkit.ai/reference/v1/hooks/useFrontendTool>、<https://docs.copilotkit.ai/reference/v1/hooks/useDefaultTool>

v1 `useFrontendTool` 的 `parameters` 为数组式定义（与 `useCopilotAction` 同构，含 `properties` 用于 object 嵌套），另有 `followUp`、`render`、`available`（"Whether the tool is available. Set to 'disabled' to prevent the tool from being called."）。原文给出的迁移差异只有一条："1. The render component props include `name` and `description`"。且迁移示例显示 `useCopilotAction` → `useFrontendTool` 的代码除 hook 名外完全一致。

v1 `useDefaultTool` 的兜底渲染语义与 `useRenderToolCall` 的 `"*"` 通配相关（见 2.3）。

### 5.4 v1 → v2 前端动作的 hook 等价表

出处：<https://docs.copilotkit.ai/concepts/which-hook>（原文 "The 30-second version" 表格）、<https://docs.copilotkit.ai/reference/hooks/useFrontendTool>（Related 小节）

| Hook | Use it when you want to | v1 对应 |
| --- | --- | --- |
| `useFrontendTool` | Give the agent a client-side tool to call (run logic in the browser), with optional inline UI. | `useCopilotAction`（带 `handler`）/ `useFrontendTool` |
| `useRenderTool` | Render the UI for a specific tool call by name (typed), without defining the tool's handler. | `useRenderToolCall`（v1 同名页说明：v2 的 `useRenderTool` 承担"为既有后端工具注册渲染器"这一职责） |
| `useDefaultRenderTool` | Provide one wildcard renderer for any tool call that has no specific renderer. | `useDefaultTool`（v1） / `useCopilotAction`（仅 `render`） |
| `useComponent` | Register a React component as a named tool renderer (component-first generative UI). | `useCopilotAction`（仅 `render`）；"a component-first shorthand that wraps `useFrontendTool` internally, registering a tool whose 'handler' is rendering your component" |
| `useHumanInTheLoop` | Pause the agent on a tool call and wait for the user to approve, edit, or supply input. | `useCopilotAction` + `renderAndWaitForResponse` |
| `useInterrupt` | Handle an agent-initiated interrupt and resume execution once the user responds. | `useLangGraphInterrupt` |
| `useRenderToolCall`（v2，注意与同名的 v1 hook 语义不同） | Get a render function for tool calls to drive your own custom chat surface (headless). | —— |

原文对 v1/v2 同名异义的明确提醒（出处 `reference/v1/hooks/useRenderToolCall` 页 Callout）："This v1 `useRenderToolCall` takes a `{ name, parameters, render }` config object to **register** a renderer for an existing backend tool. In v2, use `useRenderTool` for that job. The v2 hook named `useRenderToolCall` is a different, low-level API that returns a renderer function for consuming tool calls."

`which-hook` 页给出的选型判据（原文，按动词）：

- **"The agent should *run* something in my app."** → `useFrontendTool`。
- **"The agent's tool call should *look* like something."** → 渲染类 hook：`useRenderTool`（单个具名工具）/ `useComponent`（组件绑定到工具名）/ `useDefaultRenderTool`（兜底）。
- **"The agent should *stop and ask me* before continuing."** → `useHumanInTheLoop`（工具级批准/编辑关卡）或 `useInterrupt`（agent 驱动的中断）。
- **"I'm building my *own* chat UI."** → `useRenderToolCall`。

以及工具 vs 渲染器的边界（原文）："Two hooks in this set *register a tool* the agent can call: `useFrontendTool` (schema and handler) and `useComponent`... The render-only hooks, `useRenderTool`, `useDefaultRenderTool`, and `useRenderToolCall`, *don't* define a tool; they only *render* tool calls, which can come from your frontend (`useFrontendTool` / `useComponent`) or from the agent/backend. That's why you can pair a backend tool with `useRenderTool` and never write a handler on the client."

### 5.5 前端渲染器注册的 provider 形态

出处：<https://docs.copilotkit.ai/programmatic-control>

`defineToolCallRenderer` + provider 级注册（原文）：

```tsx title="components/weather-tool.tsx"
import { defineToolCallRenderer } from "@copilotkit/react-core/v2";

export const weatherToolRender = defineToolCallRenderer({
  name: "get_weather",
  render: ({ args, status }) => {
    return <WeatherCard location={args.location} status={status} />;
  },
});
```

```tsx title="layout.tsx"
import { CopilotKit } from "@copilotkit/react-core/v2";
import { weatherToolRender } from "./components/weather-tool";

export default function RootLayout({ children }) {
  return (
    <CopilotKit
      runtimeUrl="/api/copilotkit"
      renderToolCalls={[weatherToolRender]}
    >
      {children}
    </CopilotKit>
  );
}
```

headless 消费渲染函数（原文）：

```tsx title="components/message-list.tsx"
import { useAgent, useRenderToolCall } from "@copilotkit/react-core/v2";

export function MessageList() {
  const { agent } = useAgent();
  const renderToolCall = useRenderToolCall();

  return (
    <div className="messages">
      {agent.messages.map((message) => (
        <div key={message.id}>
          {message.content && <p>{message.content}</p>}

          {message.role === "assistant" &&
            message.toolCalls?.map((toolCall) => {
              const toolMessage = agent.messages.find(
                (m) => m.role === "tool" && m.toolCallId === toolCall.id,
              );
              return (
                <div key={toolCall.id}>
                  {renderToolCall({ toolCall, toolMessage })}
                </div>
              );
            })}
        </div>
      ))}
    </div>
  );
}
```

另有 `renderActivityMessages`（provider prop，用于前端驱动卡片，见 2.5）。

---

## 术语表（本次抓取所依据的原文术语与定义）

| 术语 | 原文/定义 |
| --- | --- |
| **AG-UI** | "a lightweight, event-based protocol that standardizes how AI agents connect to user-facing applications... an open standard, developed by the CopilotKit team and several agent framework partners."（16 种事件类型，传输无关、框架无关） |
| **A2UI** | "Google's declarative, LLM-friendly Generative UI specification that enables agents to generate dynamic user interfaces."（JSONL-based、streaming-first、platform-agnostic） |
| **A2A / MCP** | A2A："Defines how agents coordinate and share work across distributed agentic systems."；MCP："Open standard that lets agents securely connect to external systems — tools, workflows, and data sources." |
| **AbstractAgent** | `useAgent` 返回的 AG-UI agent 实例类型，持有 `agentId` / `threadId` / `messages` / `state` / `isRunning`，并提供 `runAgent` / `setState` / `subscribe` / `addMessage` / `abortRun` 等方法。 |
| **Activity message** | "a message with `role: "activity"`... renders in the transcript like any other message, and it is stripped from the payload sent to your agent on every run. The agent and the model never see it." |
| **CoAgents** | "We call these shared state experiences agentic copilots, or CoAgents for short."（v1 术语，v2 中对应 `useAgent` 的共享状态能力） |
| **Frontend tool** | "client-side functions that your agent can invoke, with execution happening entirely in the user's browser." |
| **Generative UI** | "the set of primitives that let an agent decide what appears on the screen". |
| **Governed action** | 需要在执行前由用户按 `verdict`（`allow` / `deny` / `require_approval`）审批的副作用动作，envelope 为 `{ id, summary, tool, reference, verdict, arguments }`。 |
| **HITL** | "lets an agent pause mid-run to collect input, confirmation, or a choice from the user, then resume with that answer folded back into its reasoning." |
| **Interrupt** | AG-UI 的中断对象：`{ id, reason, message?, toolCallId?, responseSchema?, expiresAt?, metadata?, subagentRunId? }`；由 `RunFinished` 的 `outcome: { type: "interrupt", interrupts: [...] }` 承载。 |
| **Middleware** | 定义在 `@ag-ui/client`、经 `agent.use(middleware)` 挂载的服务端事件改写层，实现 `run(input, next): Observable<BaseEvent>`。 |
| **runId / threadId** | `RunStarted` 建立的执行上下文标识与对话线程标识；resume 必须使用与被打断 run 相同的 `threadId`。 |
| **Shared state** | "Shared state lets your frontend and agent stay in sync. The agent can update state... and your React components re-render automatically. Your app can also write state that the agent can read." |
| **State streaming** | "forwards the value of a specific tool argument straight into an agent state key *as the argument is being generated*." |
| **ToolCallStatus** | 三态枚举 `InProgress`（参数流式中）/ `Executing`（参数已完全解析；`useHumanInTheLoop` 的 `respond` 在此可用）/ `Complete`（执行结束，`result` 字符串可用）。 |
| **`respond`** | `useHumanInTheLoop` 在 `Executing` 状态提供给 render 的回调，调用后以所传值 resolve tool call 的 promise，agent 继续。 |
| **`resolve` / `cancel`** | `useInterrupt` 的两个控制面：`resolve(payload?, interruptId?)` 记录 `{ status: "resolved", payload }` 并恢复；`cancel(interruptId?)` 记录 `{ status: "cancelled" }`；标准中断下所有打开中断被处理后才提交 resume。 |
| **`renderInChat`** | `useInterrupt` 的布尔配置，`true`（默认）把中断 UI 发布进 `<CopilotChat>`，`false` 由 hook 返回元素供自行放置。 |
| **`useAgentUpdate`** | `useAgent` 的重渲染控制枚举：`OnMessagesChanged` / `OnStateChanged` / `OnRunStatusChanged`。 |

---

## 未覆盖清单与原因

| 未覆盖内容 | 原因 |
| --- | --- |
| **`https://docs.ag-ui.com/` 全站**（除 `concepts/events` 与 `concepts/interrupts` 两页） | 不属于本次对象站点；仅按 CopilotKit 文档显式引用关系抓取了这两页作为"CoAgents / 状态协议"的线上格式依据。其余 AG-UI 页面（`concepts/serialization`、`concepts/subagents`、`concepts/reasoning`、`concepts/metadata`、`sdk/js/client/*`、`drafts/meta-events`）未展开。 |
| **`/generative-ui/a2ui/dynamic-schema`（22.9 KB）与 `fixed-schema`（21.6 KB）** | 两份大页面的 A2A 目录/catalog 定义细节（`CATALOG_ID`、`default_catalog_id`、catalog 注册形态）只做了关键词定位，未逐段摘录。已覆盖 A2UI 的概念定义与后端/前端启用方式。 |
| **`/generative-ui/open-generative-ui`（10.5 KB）、`/generative-ui/hashbrown`、`/generative-ui/json-render`、`/generative-ui/mcp-apps`** | 属于同一主题下的次要实现变体；已覆盖六原语总表与四个主原语（Components as Tools / Tool Rendering / State Rendering / A2UI / Activity cards / Reasoning 的定义层面）。MCP Apps 仅取到定义与定位，未取实现步骤。 |
| **`/reference/v1/classes/CopilotRuntime`、`/reference/v1/classes/llm-adapters/*`、`/reference/v1/sdk/python/*`、`/reference/v1/sdk/js/LangGraph`** | 属服务端 runtime / adapter 层，不在"抓取重点"列出的五主题范围内。 |
| **`/reference/core/**`、`/reference/components/**`、`/reference/channels/**`、`/reference/vue|angular|react-native/**`** | 当前 v2 的组件级与多框架 API 参考；本次只取了 React v2 hooks 与 v1 hooks 两条主线。`channels/**`（Slack / Teams 的 Channel SDK，含 `InteractionContext`、`JSXCallbacks`、`defineChannelTool` 等类型）完全未取。 |
| **18 个框架镜像子树中的实现细节** | 同一份指南在 `langgraph-python` / `mastra` / `crewai-crews` / `pydantic-ai` / `claude-sdk-*` / `agno` / `ag2` / `llamaindex` / `strands*` / `ms-agent-*` / `deepagents` / `google-adk` / `agent-spec` 各有一份镜像，正文基本相同、仅服务端片段不同。本次按"每主题 2–3 个核心页面实质内容"的要求，仅对 LangGraph 系列取了服务端原文（`interrupt()`、`CopilotKitState`、`StateStreamingMiddleware`），其余框架的服务端写法未逐条摘录。 |
| **`/cookbook/*`（含 `jev-generative-ui` 21.8 KB、`oracle-agent-spec-memory` 17.4 KB）** | 属端到端示例代码，非设计原料本体；仅在 `shared-state` 页发现官方指路链接。 |
| **`/tutorials/*`（AI travel app、agent-native app、ai-todo-app 等分步教程）** | 教材性质，与五个主题的协议/API 事实重复；未取。 |
| **`/intelligence/*`（Threads、User Memories、Automatic Learning、Product Analytics）** | 属 CopilotKit 商业平台的独立能力域，不在抓取重点。站内相关入口：`threads`、`threads-lifecycle`、`threads-import`、`headless-threads` 未展开。 |
| **`/slack/*`（247 页）与 `/teams/*`（247 页）** | 渠道集成子树，规模过大且不在抓取重点。 |
| **`/migrate/v2`（v1→v2 完整迁移指南）与 `/reference/v1/export-map`** | 只从各 v1 参考页的 Callout 中取到了零散的等价关系（见 1.8、5.4），未取完整迁移矩阵。 |
| **`/whats-new/*`、`/troubleshooting/*`、`/deploy/*`、`/faq`、`/auth`、`/telemetry`、`/cli`、`/vs-code-extension`、`/inspector`、`/webmcp`、`/voice`、`/multimodal-attachments`、`/model-selection`、`/server-tools`、`/runtime-server-adapter`** | 外围功能页，不在五主题范围内。 |

**抓取方式记录（供复现）**：

- 站点地图：`https://docs.copilotkit.ai/sitemap.xml`（HTTP 200，520,064 字节，3894 条 `<loc>`）。
- 全站聚合：`https://docs.copilotkit.ai/llms.txt`（17,734 字节，人工策划索引）与 `https://docs.copilotkit.ai/llms-full.txt`（**HTTP 200，7,834,502 字节**，以 `## Source: <url>` 分隔的 1036 页拼接）。本次把 `llms-full.txt` 在本地按 `## Source:` 切分为 1036 个单页文件并建索引，再按主题挑选，避免了逐页 HTTP 抓取。
- 单页原始 Markdown：在任意页面 URL 后加 `.md` 即得 Mintlify 源文（例：`https://docs.copilotkit.ai/human-in-the-loop.md` → 200 / 6558 字节；`https://docs.copilotkit.ai/reference/v1/hooks/useCopilotAction.md` → 200 / 8246 字节）。站点为静态/预渲染，**未遇到 JS 渲染问题、404 或反爬**；`curl -sL` 直取即可，无需 UA 伪装。
- **`llms-full.txt` 未收录的页面**：`/reference/hooks/**`（当前 v2 hooks，如 `useHumanInTheLoop` / `useInterrupt` / `useAgentContext` / `useFrontendTool` / `useRenderTool` / `useAgent` / `useComponent` / `useInterrupt`）均**不在** `llms-full.txt` 内，需按 `.md` 单页直取（已验证全部 200）。
- **不在 sitemap 但仍可访问的旧路径**：`/coagents/quickstart`（200 / 33,195 字节）、`/coagents/human-in-the-loop`（200 / 7,467 字节）、`/coagents/shared-state/in-app-agent-read`（200 / 6,203 字节）、`/coagents/shared-state/in-app-agent-write`（200 / 5,429 字节）、`/coagents/index`（200 / 713 字节）。以下旧 CoAgents 路径返回 **404**：`/coagents/agentic-generative-ui`、`/coagents/shared-state/state-inputs-defined-by-agent`、`/coagents/advanced/agent-lock`、`/coagents/intermediate/agent-state`、`/coagents/coagent-state-rendering`、`/coagents/advanced/router-mode`、`/langgraph-python/coagents/quickstart`。
- GitHub 侧：`https://api.github.com/repos/CopilotKit/CopilotKit/contents/docs?ref=main` 显示 `docs` 是**符号链接**，指向 `showcase/shell-docs`；本次未沿该路径取文档，因为站点 `.md` 端点已能提供所有所需原文。
