> **历史分析 / 过程证据 — 非现行基线，非验收结论**
>
> - 归档：2026-10-26 · `docs/evidence/history-recovery-2026-10-26/wire-tail/`
> - 来源：Kimi Code 会话 `<KIMI_SESSION>` / 子 agent `agent-1135` 的 wire 末条 assistant 文本（消息序号 m=24；只读流式解析，非原始会话转写）
> - 历史时点：2026-10-19 15:28 → 2026-10-19 15:33（wire 事件时间窗，UTC+8）；当时模型：`DeepSeek/deepseek-flash`
> - 原基线：正文未出现基线哈希，未经本批复验
> - 原始体量：30624 B / 17805 字符 / 257 行；行指纹覆盖率（本批复算）1.2%（最高单文件 1%：`docs/evidence/history-recovery-2026-10-26/wire-only/entries/049-结论先行-579204cf-agent-420.md`）
> - 类别：**正式调研 / 设计 / 裁决 / 分析**（原判：调研）
> - 与既有件关系：`调研-HITL交互型谱`；报告口径覆盖率 0.00
> - 脱敏：路径 0 / 会话标识 1 / 身份 0 / 密钥 0 / 命名纪律 0
> - 正文为该条消息的完整文本，未删段、未改写结论；仅脱敏、占位符归一与行尾空白规整。当时结论不代表当前代码状态，不得作为当前缺陷或有效裁决引用。
自证：我的 wire 是 `<KIMI_SESSIONS>`，第 5 行 `profile.bind` 显示 `agentId=agent-1135`、`modelAlias=DeepSeek/deepseek-flash`、`profileName=explore`、`thinkingEffort=high`，与当前会话路径一致。全程只读，未修改任何文件或服务。

## 结论先行

- **没有跨框架的统一标准。** 目前只有 AG-UI 把“多个 interrupt 如何一起恢复”写成了协议规则；MCP elicitation 只定义“问/答/取消”的动作，不定义并发与队列。
- **三种主流编排模型**：批屏障（LangChain `batched`、AG-UI）、逐调用（LangChain `per_call`、OpenAI Agents SDK）、单激活阻塞（AutoGen）。
- **“处理一个时其他怎么办”差异最大**：AG-UI 是“全部答完才恢复”，OpenAI SDK 允许“先处理部分、其余继续挂起”，AutoGen 是“整个人机回合阻塞”。
- **取消/超时最不统一**：LangGraph 的 `interrupt()` 默认无限等待、没有单 interrupt 取消原语；AG-UI 有 `cancelled` 与 `expiresAt`；Dify/n8n/Power Automate 各有自己的超时分支。
- **副作用控制没有协议级自动分类**：主流做法是“逐工具策略 + 显式工作流门禁 + 幂等/审计”，而不是让 UI 猜测哪个工具危险。
- **多工具卡 UI 只有相关性字段，没有依赖协议**：AG-UI 提供 `toolCallId`、`subagentRunId`、`message`、`responseSchema`、`expiresAt`，但没有 `dependsOn`、`groupId`、`blocking` 这类字段。

## 横向对照

| 系统 | 同批多个待处理？ | 处理一个时其他 | 取消/超时 | 只读 vs 副作用 | 多卡 UI |
|---|---|---|---|---|---|
| **LangGraph** | 并行分支可同时多个；同一节点内多个 `interrupt` 是顺序的 | 其余保持 pending；恢复按 interrupt id 映射 | 无内置超时；用 `reject` 单条拒绝，或取消整个 run | 无内建分类；靠工具策略或节点隔离 | interrupt payload / `response_schema`；无依赖字段 |
| **LangChain HITL middleware** | `batched` 一批；`per_call` 每个 gated call 一个 | `per_call` 未回答的保持 pending，已回答的工具可先跑 | `reject` / `respond`；无内置超时 | `interrupt_on` + `when` 谓词；`per_call` 下只读工具可先跑 | 客户端按 schema 渲染；批决策按顺序 |
| **CopilotKit + AG-UI** | 一个 run 可带多个 interrupt | 全部 open interrupt 都处理完才 resume，否则 `waiting` | 单条 `cancel`；`expiresAt`；legacy cancel 只 dismiss | 无自动分类；后端决定哪些工具 interrupt | 一个 `render` 拿到全量数组；`toolCallId` 关联 |
| **Power Automate** | 一个审批动作可多审批人；并行分支可多个审批 | 各分支独立；顺序审批逐人 | 拒绝可提前结束；动作级 Timeout/`TimedOut`；可取消请求 | 工作流门禁，无自动分类 | 审批中心逐条；流程画布表达顺序/并行 |
| **Copilot Studio** | 同 agent flow/Power Automate | 同上 | 同上 | per-tool/per-agent 审批开关（产品公告） | 审批请求卡；无统一队列语义 |
| **Dify** | 并行分支可出现多个 Human Input 表单 | 每个表单独立提交/resume；快速并发有竞态 | 默认 3 天；走 timeout 分支；无分支则工作流结束 | 节点门禁，无自动分类 | 每个节点一张表单；画布表达分支 |
| **n8n** | 多个 Wait 节点可同时；agent 工具逐个审查 | Wait 节点按顺序 resume；分支独立 | Limit Wait Time；deny 取消该工具 | 选择性工具审查 | 每个工具一条审批消息；画布 |
| **AutoGen** | 否，单激活 | 整个 team 阻塞 | 自定义 input 可接 cancellation token；无批 | 无 per-tool 策略 | 聊天轮次 |
| **OpenAI Agents SDK** | 多个 `interruptions` | 允许部分处理；已解决调用可继续，未解决再次暂停 | `reject` 单条；无内置超时 | per-tool `needs_approval`；参数异常 fail-closed | `interruptions` 列表 |
| **MCP elicitation** | 未规定 | 未规定 | `accept` / `decline` / `cancel` | 未规定 | 未规定 |

性质划分：AG-UI、MCP 是协议；LangGraph、LangChain、AutoGen、OpenAI SDK 是框架默认语义；Power Automate、Copilot Studio、Dify、n8n 是产品选择。

## 逐系统证据

### 1. LangGraph：最细的“节点/中断”语义

**同批多个审批。** LangGraph 的 `interrupt()` 是动态暂停点，状态由 checkpointer 保存，恢复时用同一个 `thread_id` 和 `Command(resume=...)`。并行分支可以各自 `interrupt()`，文档明确给出“用 interrupt id 映射恢复多个 interrupt”的示例；同一个节点内也可以有多个 `interrupt`，但匹配是**严格按索引顺序**的，不能条件性跳过，也不能用不确定的循环，否则恢复值会错位。参考 [Interrupts](https://docs.langchain.com/oss/python/langgraph/interrupts)。

**处理一个时其他怎么办。** 并行分支的多个 interrupt 是同时 pending 的；官方推荐一次恢复所有 pending interrupt，用 `{interrupt_id: value}` 映射。没有回答的 interrupt 不会自动取消。LangChain 的 HITL middleware 在 `per_call` 模式下说得更直白：**未回答的 interrupt 保持 pending，回答后对应工具才执行**。参考 [Human-in-the-loop](https://docs.langchain.com/oss/python/langchain/human-in-the-loop)。

**取消/超时。** `interrupt()` 的文档语义是“无限等待直到 resume”；官方没有提供按单个 interrupt 取消的 API。实践上要么用 `reject` 决策拒绝该工具，要么在外部取消整个 run。超时也要应用自己实现（定时器 + 外部恢复/取消）。这是**框架默认**，不是协议。

**副作用与只读。** `resume` 会**从节点开头重新执行**，`interrupt()` 之前的代码会再跑一遍；官方明确要求 `interrupt` 前的副作用必须幂等，或把副作用放到 `interrupt` 之后/独立节点。参考 [Rules of interrupts](https://docs.langchain.com/oss/python/langgraph/interrupts)。LangChain middleware 则提供逐工具策略：`interrupt_on` 里 `read_data: False` 可以直接放行；`when` 谓词可以只拦截写 SQL、工作区外写文件等。`batched` 默认在模型回合末统一暂停；`per_call` 下文档示例明确说 `read_data` 会**立刻执行**，两个 gated 调用各自 interrupt。参考 [Human-in-the-loop](https://docs.langchain.com/oss/python/langchain/human-in-the-loop)。

**一个重要的批处理风险。** 官方文档提醒：如果一批回答里有一个非法，其他工具可能已经执行，而它们的 interrupt 仍显示 pending；再次回答会导致这些工具**执行两次**。所以批提交前要按 `response_schema` 校验，或者逐个提交。

### 2. CopilotKit + AG-UI：协议层最完整，但“全有或全无”

CopilotKit 有两条 HITL 路径：

- `useHumanInTheLoop`：**LLM 主动调用一个前端工具**，agent 在 tool promise 上暂停，`render` 里调用 `respond` 一次，agent 把答案当作工具结果继续。参考 [HITL Overview](https://docs.copilotkit.ai/human-in-the-loop) 与 [useHumanInTheLoop](https://docs.copilotkit.ai/reference/hooks/useHumanInTheLoop)。
- `useInterrupt`：**graph 强制暂停**，后端调用 `interrupt()`，前端通过 AG-UI 标准 interrupt 流恢复。`render` 同时拿到 `interrupt`（主 interrupt）和 `interrupts`（全部 open interrupt），`resolve(payload, interruptId)` / `cancel(interruptId)` 按 id 处理。参考 [useInterrupt](https://docs.copilotkit.ai/human-in-the-loop/useInterrupt)。

AG-UI 的协议规则很明确，参考 [Interrupts](https://docs.ag-ui.com/concepts/interrupts)：

- `RUN_FINISHED` 的 `outcome.type === "interrupt"`，`interrupts` 是非空数组；每个 interrupt 有 `id`、`reason`、`message`、`toolCallId`、`responseSchema`、`expiresAt`、`metadata`、`subagentRunId`。
- 恢复时下一次 `RunAgentInput` 带 `resume` 数组；**必须覆盖该 run 的所有 open interrupt，部分恢复不支持**。
- 同一 thread 有未解决 interrupt 时，新输入必须带 `resume`，否则 agent 应返回 `RunError`。
- 恢复要幂等：相同 `(threadId, interruptId, status, payload)` 可安全重放。
- `expiresAt` 由客户端遵守，过期提交产生 `RunError`。
- 取消用 `{status:"cancelled"}`，拒绝则放在 payload 里（例如 `{approved:false}`）。并行示例：两个批准、一个取消，agent 执行前两个、第三个视为未执行。

这解释了 CopilotKit 当前实现的几个关键行为（源码）：

- `ɵInterruptState.#respond` 会等所有 interrupt 都有 response 才返回 `resume`，否则返回 `waiting`：`_cpk-src/repo/packages/core/src/interrupt-state.ts:133`。
- 最后提交时只要发现**任意一个** interrupt 已过期，就把整个集合 seal 成 `expired`，清空 pending、不 resume：`_cpk-src/repo/packages/core/src/interrupt-state.ts:137`。
- 多 interrupt 时如果 `resolve()`/`cancel()` 不传 id，会警告并默认第一张：`_cpk-src/repo/packages/react-core/src/v2/hooks/use-interrupt.tsx:239`。
- 标准 cancel 会为 `tool_call` 类型合成 `{"status":"cancelled"}` 的 tool result 再 resume；legacy `on_interrupt` 的 cancel 只 dismiss、不 resume：`_cpk-src/repo/packages/react-core/src/v2/hooks/use-interrupt.tsx:303`。
- `setInterruptElement` 是单槽，多个 `useInterrupt` 同时挂载会互相覆盖：`_cpk-src/repo/packages/react-core/src/v2/lib/react-core.ts:122`。

**性质**：AG-UI 的多 interrupt 生命周期是**协议强制**；“全部答完才恢复”是 CopilotKit 对协议规则的实现；legacy cancel 不恢复是**兼容层产品选择**，也是潜在卡死点。

**多工具卡 UI**：CopilotKit 的 tool rendering 支持一个回合多个工具调用，每个调用可以有自己的渲染器，默认卡片显示 `Running → Done`，但没有批次、依赖、阻塞原因的原生表达。参考 [Tool Rendering](https://docs.copilotkit.ai/langgraph-python/generative-ui/tool-rendering)。

### 3. Microsoft：Power Automate 是审批引擎，Copilot Studio 是编排层

Power Automate 的 `Start and wait for an approval` 是一个**阻塞动作**，官方列了四类审批：

- `Approve/Reject - Everyone must approve`：所有审批人都要响应，或出现一个拒绝就结束。
- `Approve/Reject - First to respond`：任意一人响应即完成。
- `Custom Responses - Wait for all responses`：所有审批人都要响应。
- `Custom Responses - Wait for one response`：任意一人响应即完成。
- 此外还有 `Sequential approval`：按顺序逐个请求，前一个响应后才到下一个。

参考 [Get started with Power Automate approvals](https://learn.microsoft.com/en-us/power-automate/get-started-approvals)。

**同批多个审批**：一个审批动作可以带多个审批人；多个审批动作可以通过**并行分支**同时存在。官方并行审批文档说明：每个分支的审批相互独立，分支之后的步骤要等所有分支完成才执行。参考 [Create parallel approval workflows](https://learn.microsoft.com/en-us/power-automate/parallel-modern-approvals)。顺序审批则是明确的队列语义，参考 [Manage sequential approvals](https://learn.microsoft.com/en-us/power-automate/sequential-modern-approvals)。

**取消/超时**：审批请求可以由发起人取消（文档主要针对 `Create an approval (v2)`）；审批动作支持动作级 Timeout，超时后通常走 `TimedOut` 分支。这些是**产品选择**，不是跨系统协议。官方审批教程没有定义“一个审批超时后，同批其他审批是否自动取消”；在并行分支里，其他分支通常继续，除非你显式加门禁。

**Copilot Studio**：官方 agent flows 文档把 `Human in the loop` 列为一种 action 类型，包括“approval requests or providing information”；也就是说 Copilot Studio 的审批语义主要继承 agent flow/Power Automate，而不是自己定义一套多 interrupt 协议。参考 [Agent flows overview](https://learn.microsoft.com/en-us/microsoft-copilot-studio/flows-overview)。2026 年 9 月的产品公告增加了 per-tool/per-agent 的审批开关，支持 approve、approve for session、deny，属于**产品选择**；它解决的是“哪些工具要拦”，不解决“同批多卡如何排队”。

### 4. Dify：工作流节点语义，并行 Human Input 有已知竞态

Dify 的 Human Input 是一个**节点**：工作流到达节点后暂停，渲染表单；表单支持 Web app / Email；用户可以填字段并选择预定义 action，每个 action 路由到不同下游；超时默认 3 天，走 timeout 分支，没有 timeout 分支则工作流结束。参考 [Human Input](https://docs.dify.ai/en/cloud/use-dify/nodes/human-input)。API 侧每个表单有 `form_token`，提交后恢复工作流，参考 [Human Input API Integration Flow](https://docs.dify.ai/en/api-reference/guides/human-input-flow)。

**同批多个表单**：Dify 支持并行分支，所以两个分支各有一个 Human Input 时，两个表单可以同时存在。但 GitHub issue #42103 记录了一个真实竞态：两个并行表单都被服务端接受，重叠的 resume 任务仍可能让工作流停在 `paused`，第二个表单的提交没有有效唤醒。该 issue 是 1.17.0 自托管源码环境的 bug report，不是官方设计承诺，但它说明**“多个表单同时待处理”在产品层是可能的，运行时恢复却不是为并发提交设计的**。参考 [issue #42103](https://github.com/langgenius/dify/issues/42103)。

**性质**：Human Input 的交付方式、action 路由、默认 3 天超时是**产品选择**；并行多表单的恢复语义**没有统一标准**，当前实现存在竞态风险。

### 5. n8n：工具级审批 + Wait 节点的顺序恢复

n8n 的 AI Agent 可以给工具加 Human review：agent 决定调用某个受审查工具时，工作流暂停，向指定渠道发审批请求，审批人看到工具名和参数，选择 Approve / Deny；批准后按 AI 给的参数执行，拒绝则取消并把拒绝告知 AI。审批渠道可以是 n8n Chat、Slack、Discord、Telegram、Teams、Email 等，甚至可以和主交互渠道不同。参考 [Human-in-the-loop for tools](https://docs.n8n.io/build/integrate-ai/ai-examples/human-in-the-loop-for-tools)。

**多个待处理**：n8n 的 Wait 节点明确支持一个工作流里有多个 Wait 节点；文档说当 resume webhook 被调用时，会**按顺序逐个恢复 Wait 节点**（"as the webhook URL is called it will resume each Wait node sequentially"）。Wait 节点还支持 `Limit Wait Time`，到时间自动恢复。参考 [Wait node](https://docs.n8n.io/integrations/builtin/core-nodes/n8n-nodes-base.wait/)。

**性质**：工具审查的“单工具、Approve/Deny”是**产品选择**；多 Wait 节点的顺序恢复是**产品实现**；n8n 没有为“同批多个工具审批”定义批量决策协议。

### 6. AutoGen：单激活阻塞，官方自己提示不适合长等待

AutoGen 的 `UserProxyAgent` 在 team run 期间把控制权交给用户，**阻塞整个 team** 直到用户输入或报错。官方明确警告：这会拖住 team 进度，并把 team 置于**无法保存或恢复的不稳定状态**；因此只推荐用于需要即时反馈的短交互，比如批准/拒绝按钮。参考 [Human-in-the-Loop](https://microsoft.github.io/autogen/stable/user-guide/agentchat-user-guide/tutorial/human-in-the-loop.html)。

如果要做可持久化的异步审批，官方建议不要用阻塞式 `UserProxyAgent`，而是让 run 结束（`max_turns` 或 `HandoffTermination`），保存状态，下一次 `run()` 再带用户反馈继续。

**性质**：单激活、阻塞、不可保存恢复，是**框架默认/已知限制**；没有多审批并发或队列语义。

### 7. OpenAI Agents SDK：逐调用审批，允许部分处理

OpenAI Agents SDK 用 `needs_approval` 标记工具（布尔或按调用判断的异步函数），run 暂停后 `RunResult.interruptions` 里是一个个 `ToolApprovalItem`。关键语义：

- **不需要一次解决所有 pending approval**。可以只批准/拒绝其中一部分；已解决的调用可以继续，未解决的仍留在 `interruptions` 里，让 run 再次暂停。
- 每个 per-call 决策绑定具体 call ID；`always_approve` / `always_reject` 是 sticky，作用域受 agent/tool 类型限制。
- `RunState` 可序列化，支持跨进程恢复；但官方警告反序列化前必须做认证/授权，不能信任客户端提交的 snapshot。
- 参数缺失、空、非法 JSON、非对象等情况下，审批规则 **fail-closed**，要求人工审批。

参考 [Human-in-the-loop](https://openai.github.io/openai-agents-python/human_in_the_loop/)。

**性质**：逐调用、部分恢复、sticky 决策是**框架默认**。这与 AG-UI 的“所有 open interrupt 必须一起恢复”形成鲜明对比。

### 8. MCP elicitation：协议只定义动作，不定义编排

MCP 的 elicitation 允许 server 通过 `elicitation/create` 请求用户输入，响应有三种：`accept`（提交数据）、`decline`（明确拒绝）、`cancel`（关闭/忽略）。协议明确说：**实现可以自由选择任何界面模式，协议本身不强制特定的用户交互模型**；客户端应允许用户随时拒绝，并实现限流。参考 [Elicitation](https://modelcontextprotocol.io/specification/2025-06-18/client/elicitation)。

**性质**：这是**协议层但故意不规定编排**的典型：没有多请求并发、队列、超时、取消兄弟请求的规则，全部留给客户端/宿主产品。

## 五个问题的横向答案

**1. 一批里多个审批/表单是否允许同时待用户处理？**

- **并发待处理**：LangGraph 并行分支、LangChain `per_call`、AG-UI/CopilotKit、OpenAI SDK、Dify 并行分支、n8n 多 Wait 节点。
- **批决策**：LangChain `batched` 是“一个 interrupt 里放多个 action_requests”，一次给有序决策列表。
- **单激活/队列**：AutoGen 单激活；Power Automate 顺序审批是队列；LangGraph 同一节点内多个 `interrupt` 是索引顺序，不是并发。
- **没有统一默认**：同一产品里也可能同时存在“并发 pending”和“顺序审批”两种模式。

**2. 用户处理一个交互工具时，其他工具暂停、继续，还是开发者选择？**

- **AG-UI/CopilotKit**：其他 interrupt 继续等待；但**整个 run 不会恢复**，直到所有 open interrupt 都被处理。这是协议强制。
- **LangChain `per_call` / OpenAI SDK**：可以部分处理；已解决的调用可以继续，未解决的保持 pending/再次暂停。这是框架默认。
- **LangChain `batched`**：整批在模型回合末暂停，按顺序给决策；不是“处理一个就放行一个”。
- **AutoGen**：整个人机回合阻塞，其他工具没有并发机会。
- **Power Automate / Dify / n8n**：由工作流拓扑决定；并行分支独立，顺序节点/审批逐个走。开发者通过画布显式选择。

**3. 取消/超时/中断时，同批其他工具怎么处理？**

- **AG-UI**：`cancel` 只标记该 interrupt；同批其他 interrupt 仍要各自处理，全部处理完才 resume；已批准的其他工具会执行，取消的不会。`expiresAt` 过期由客户端阻止提交，但协议没有说“一个过期自动取消其他”。
- **CopilotKit 当前实现**：最后一个 response 到达时，只要任一 interrupt 过期，整个集合作废、不 resume；这是一个值得单独研究的实现选择。
- **LangGraph**：没有单 interrupt 取消 API；用 `reject` 拒绝单条，其他条按各自决策执行；超时无限等待，需要外部定时器；取消整个 run 会中止全部。
- **LangChain middleware**：`reject` 跳过该工具并回注拒绝信息；`respond` 是“人代替工具回答”，不能用来拒绝副作用工具；批提交有“部分成功、重试双跑”的风险。
- **Power Automate**：`Everyone must approve` 在一个拒绝后提前结束；`First to respond` 任意一人响应即结束；超时走 `TimedOut` 分支；并行分支通常互不自动取消。
- **Dify**：超时走 timeout 分支；没有 timeout 分支则工作流结束；并行表单的取消/超时兄弟语义没有明确文档，且有竞态 issue。
- **n8n**：Deny 取消该工具调用；Wait 节点到时间自动恢复；多个 Wait 节点按顺序恢复，不是一起取消。
- **AutoGen**：自定义 input 函数可接 cancellation token；但 team 处于不可保存恢复状态，谈不上“同批其他工具”。
- **OpenAI SDK**：拒绝单条，其他条可继续；未解决的再次暂停；没有内置全局超时。

**4. 有副作用工具与只读工具并发的主流风险控制**

- **逐工具策略**：LangChain `interrupt_on` + `when` 谓词是最具体的例子：只读工具直接放行，写 SQL/工作区外写文件才中断；`per_call` 下只读工具可以先跑。
- **批屏障**：`batched` 默认把整个模型回合的 gated calls 放到一个 interrupt，用“慢一点但一致”换安全。
- **Fail-closed**：OpenAI SDK 在参数无法安全解析时强制人工审批；n8n 的 Deny 直接取消工具。
- **显式工作流门禁**：Power Automate、Dify、n8n 都把审批放在副作用节点之前，由开发者画拓扑，而不是自动识别读写。
- **幂等/审计**：LangGraph 恢复会重跑节点，要求 `interrupt` 前的副作用幂等；AG-UI 要求 resume 可重放；OpenAI 要求 `RunState` 服务端保管并认证。没有这些，重试/双击/并发提交都可能导致重复副作用。
- **协议不分类**：AG-UI 的 `reason` 只是 `tool_call` / `input_required` / `confirmation` 等路由提示，不是风险等级；MCP 也不规定。是否把某个工具变成 interrupt，完全由 agent/后端决定。

**5. 多工具卡 UI 如何表达顺序、依赖、等待、取消**

现有可用的“原语”只有：

- `toolCallId`：把 interrupt 绑回之前的 `ToolCallStart/Args/End` 序列，能表达“这张卡对应哪个工具调用”。
- `subagentRunId`：一个 run 里可能有多个 subagent 的 interrupt；协议建议 subagent 以 `outcome: {type:"suspended"}` 结束。
- `responseSchema`：告诉客户端这张卡该渲染什么表单；AG-UI 本身不做客户端校验。
- `expiresAt`：可以显示倒计时/过期态。
- `metadata`：可放框架私有信息，但跨产品没有统一键。
- **没有** `dependsOn`、`groupId`、`order`、`blocking`、`sideEffect` 这些字段。

因此：

- **CopilotKit**：一个 `render` 拿到全部 interrupt，由开发者自己决定是列表、队列还是单卡；默认工具卡只有 `Running → Done`，没有批次/依赖。
- **LangGraph Studio**：按 `response_schema` 渲染类型化表单；多个 interrupt 需要自定义 UI。
- **Dify**：一个 Human Input 节点一张表单，画布表达分支；没有统一审批收件箱。
- **Power Automate**：审批中心逐条展示，流程画布表达顺序/并行；没有“依赖箭头”到卡片层。
- **n8n**：每个工具一条审批消息，Wait 节点在画布上；没有多卡批次。
- **AutoGen**：聊天轮次，不是卡片。

**结论**：多工具卡的“顺序/依赖”目前只能靠 **tool call 在消息中的顺序 + `toolCallId` 关联 + 开发者自定义 metadata/布局** 来表达；没有跨框架标准。

## 当前项目（CopilotKit/AG-UI）可研究的问题清单

以下仅为研究问题，不含实现方案或代码改动。当前项目可从 `_cpk-src/repo` 与 `_cpk-agui` 两个入口看。

### A. 批处理与部分提交

1. **AG-UI 的“全有或全无”是否适合 CopilotKit 的默认 UX？**
   当前 `ɵInterruptState.#respond` 要等所有 interrupt 都有 response 才提交 resume（`_cpk-src/repo/packages/core/src/interrupt-state.ts:133`）。对比 OpenAI SDK 允许部分处理、LangChain `per_call` 允许逐个回答：CopilotKit 是否应提供“先提交已完成的卡，其余保持 pending”的模式？后端是否支持同一 run 的多次 partial resume？

2. **快审批被慢表单拖住时，产品应该怎么表现？**
   一个批里有“立即批准转账”和“填写长表单”两张卡；当前必须等长表单完成才恢复。研究：是否应允许快卡先提交、慢卡继续等待？如果允许，如何防止已批准的副作用在其余审批未完成时执行？

3. **多卡时默认目标第一张是否安全？**
   `useInterrupt` 在 `interrupts.length > 1` 且未传 id 时警告并默认 `interrupts[0]`（`_cpk-src/repo/packages/react-core/src/v2/hooks/use-interrupt.tsx:239`）。研究：是否应改为强制显式选择，或按卡片焦点/最近交互决定目标，避免误批？

### B. 取消与过期

4. **单卡过期是否应作废整批？**
   当前最后提交时只要任一 interrupt 过期，就整个集合 `expired`、清空、不 resume（`_cpk-src/repo/packages/core/src/interrupt-state.ts:137`）。研究：更合理的语义是“只取消过期卡，其他卡继续”，还是“整批失效”？谁负责倒计时、自动提交或提示用户？

5. **`cancel` 对模型应表达什么？**
   标准路径会为 `tool_call` 合成 `{"status":"cancelled"}` tool result 再 resume；legacy 路径只 dismiss、不 resume（`_cpk-src/repo/packages/react-core/src/v2/hooks/use-interrupt.tsx:303`）。研究：模型应看到“用户拒绝”“工具未执行”还是“操作取消”？legacy 不 resume 是否会导致 run 永久停在 pending？迁移策略是什么？

6. **`cancelled` 与 `resolved` 里的 `approved:false` 如何区分？**
   AG-UI 协议把取消放在 status，把拒绝放在 payload；两者在审计和 UI 上语义不同。研究：多卡 UI 是否需要“取消整张卡”和“拒绝这个工具”两个动作？后端如何区分并记录？

### C. 顺序、依赖与归属

7. **是否需要依赖/批次字段？**
   AG-UI 只有 `toolCallId`、`subagentRunId` 和数组顺序，没有 `dependsOn` / `groupId` / `order` / `blocking`。研究：多工具卡要表达“B 依赖 A 的批准结果”时，应该用 `metadata` 扩展，还是推动 AG-UI 增加协议字段？现有 `toolCallId` 能否仅靠 ToolCall 序列推导依赖？

8. **同一节点内的顺序 interrupt 与并行分支的并发 interrupt，UI 是否应区分？**
   LangGraph 同一节点多个 `interrupt` 是严格索引顺序；并行分支才是并发。研究：CopilotKit 的卡片 UI 是否需要“顺序表单”和“并发审批”两种布局，避免把顺序节点画成并行卡片？

9. **多 subagent 的 interrupt 如何分组？**
   AG-UI 允许一个 run 携带多个 subagent 的 interrupt，用 `subagentRunId` 归属；subagent 还可以 `suspended` 结束。研究：UI 是否应按 subagent 分组、显示“哪个 agent 被阻塞”？当前 `useInterrupt` 只给全量数组，没有分组契约。

### D. 安全、幂等与并发

10. **CopilotKit 是否需要一个 `interruptMode`（batched vs per_call）？**
    LangChain 默认 `batched`，可选 `per_call`；`per_call` 明确只读工具可以先跑。AG-UI/CopilotKit 目前没有等价开关。研究：当前项目默认应选哪种？如果提供开关，放在 agent 后端、runtime 还是前端 hook？

11. **resume 会不会重复执行工具？**
    LangGraph 恢复会重跑节点；AG-UI 要求 resume 幂等；OpenAI 警告 snapshot 未认证。研究：CopilotKit 在 resume 前合成的 tool result 和 `runAgent` 调用，是否可能触发工具重复执行？需要哪些幂等键、去重表、审计字段？

12. **“只读”工具隐藏副作用怎么办？**
   审计日志、计费 LLM、带副作用的 GET 都可能被当成只读。研究：是否需要在工具定义/AG-UI `metadata` 中携带 `sideEffect` / `risk` 标记？谁来分类：开发者、runtime 还是策略服务？

13. **多个 `useInterrupt` 同时挂载会不会冲突？**
    每个 hook 有独立的 `ɵInterruptState`，而 `setInterruptElement` 是单槽（`_cpk-src/repo/packages/react-core/src/v2/lib/react-core.ts:122`）。研究：两个 hook 同时处理同一 agent 的 interrupt 时，谁渲染、谁提交？是否需要一个全局 interrupt store/queue，而不是每个 hook 各自为政？

### E. UX 与产品策略

14. **pending interrupt 期间用户发新消息，应该排队、阻止还是自动取消？**
    AG-UI 规定同一 thread 有未解决 interrupt 时，新输入必须带 `resume`，否则 `RunError`。研究：产品应排队用户消息、禁用输入并提示“先处理审批”，还是允许转到新 thread？跨 thread 是否允许？

15. **要不要“全部批准 / 本会话不再询问 / 批准同类工具”？**
    Copilot Studio 有 approve-for-session，OpenAI SDK 有 `always_approve` sticky。CopilotKit 当前没有策略层。研究：这类策略应放在前端、runtime 还是 agent 后端？作用域是“本 run”“本 thread”“本工具”“本 agent”还是“本会话”？如何撤销和审计？

16. **多工具卡的状态模型应该是什么？**
    建议研究一套与传输无关的状态：`waiting`（等待依赖/其他卡）、`ready`（可操作）、`blocked`（依赖未满足）、`resolved`、`cancelled`、`expired`、`superseded`。研究：如何在卡片上表达“2/3 已处理”“已批准但未执行”“等待其他卡片”？是否需要批次头、折叠队列、依赖箭头？

17. **审批审计需要哪些最小字段？**
    至少包括 `interruptId`、`toolCallId`、`decision`（approve/edit/reject/cancel/respond）、`editedArgs`、操作者身份、时间、`expiresAt`、resume 重放 id。研究：AG-UI 现有 `metadata` 是否足够？如何把审批记录绑定到服务端授权，而不是信任客户端提交的 snapshot？

**不确定性说明**：Dify 的并行 Human Input 竞态来自具体版本的 bug report，不是稳定契约；Copilot Studio 的 per-tool 审批是 2026 年产品公告，官方 Learn 正文尚未给出完整的并发/队列语义；Power Automate 的审批超时属于动作级产品设置，官方审批教程没有统一定义“超时后同批其他审批”的行为。上述均建议在实际项目中以目标版本复测后再作为设计依据。
