> 来源=agent-g5lj98fb/output.log（2026-10-26 归档）· 外部研究（工具审批、HITL 谱系、生成式表单与长表单适用边界）
> 性质：本次只读分析/调研的**脱敏副本**，属历史证据；为**分析稿**，结论以 `docs/research/` 总册与「规格复核补正」为准。仅做本机路径/仓库名/个人用户名占位与空白清理，**技术结论、计数、状态词一字未改**。

# 外部资料对标：AI 工具 / HITL / 生成式表单 / 长表单

核验时间：2026-10-26。以下仅基于公开文档与规范做只读研究；未读取/发送任何密钥，未调用真实模型，未修改文件、服务、代码或文档。

## 一、结论先行

1. **一手资料不支持“所有写操作都必须确认”。** 更准确的共同模式是：**默认自动执行 + 按风险/参数/策略确认 + 始终保留拒绝/取消能力 + 可审计/可恢复**。
2. **HITL 是一个谱系，不是单个“确认框”**：询问缺失信息、预览、批准、编辑后批准、拒绝、取消、升级/接管，都是不同交互。
3. **首要设计选择是“谁决定暂停”**：模型自己决定调用一个“询问工具”，还是图/策略/代码路径强制 `interrupt`。前者灵活但可能多问/漏问；后者确定但需要预先编排。
4. **恢复不是重新发一条用户消息**，而是用**稳定 ID + 持久化状态 + 结构化 resume payload + 幂等**，把用户决定回填到原动作。
5. **生成式表单/UI 的可靠做法是“模型给参数/选择，应用拥有组件、校验、权限”**；不是让模型直接写 HTML 塞进主 DOM。
6. **短表单、渐进收集、按风险确认都有依据；但“一次一个字段”不适合所有场景，字段级“填完自动跳下一步”也没有通用规范**。工作流级“审批完成后自动续跑”倒是有明确 API。
7. **框架功能不等于本项目必须引入框架。** 可复用的是契约与交互规则，不是某个 hook 或某个运行时。

---

## 二、一手来源核验

### A. CopilotKit / LangGraph / Vercel AI SDK / OpenAI Agents SDK

| # | 来源 | URL | 能直接支持的交互规则 |
|---|---|---|---|
| 1 | CopilotKit — HITL Overview | https://docs.copilotkit.ai/agent-spec/human-in-the-loop | HITL 有两种暂停路径：`useHumanInTheLoop`（模型调用前端工具，**模型决定暂停**）与 `useInterrupt`（图/节点 `interrupt()`，**代码路径强制暂停**）。适用场景被明确列为高风险决策点、低置信度兜底、专家输入、生产可靠性。 |
| 2 | CopilotKit — useHumanInTheLoop | https://docs.copilotkit.ai/reference/v2/hooks/useHumanInTheLoop | 注册一个交互式工具；agent 暂停直到 UI 调用 `respond`；状态机为 `InProgress → Executing → Complete`；一次调用只应 `respond` 一次；结果作为工具结果回到 agent。可用于确认、审批、表单收集。 |
| 3 | CopilotKit — Which Hook | https://docs.copilotkit.ai/concepts/which-hook | 把“在浏览器执行”（`useFrontendTool`）、“只渲染工具调用”（`useRenderTool`/`useComponent`）、“暂停等人”（`useHumanInTheLoop`/`useInterrupt`）拆成三个独立注册面。不要因为要生成 UI 就默认加审批。 |
| 4 | CopilotKit — Tool Rendering | https://docs.copilotkit.ai/generative-ui/tool-rendering | 生成式 UI 是按工具名绑定**应用自有组件**，渲染 `status`/`parameters`；模型只提供调用与参数，组件与呈现由应用控制；有 `useDefaultRenderTool` 兜底。 |
| 5 | CopilotKit — Frontend Actions | https://docs.copilotkit.ai/pydantic-ai/frontend-actions | 前端工具是浏览器内执行的客户端函数，可读写 React 状态、`localStorage`/cookies、触发 UI 更新；适合 UI 控制、生成式 UI、HITL。敏感业务不应只放在前端。 |
| 6 | LangGraph — Interrupts | https://docs.langchain.com/oss/python/langgraph/interrupts | `interrupt(value)` 保存图状态并等待；用同一 `thread_id` + `Command(resume=...)` 恢复；resume 值成为 `interrupt()` 返回值；**节点会从开头重跑**，interrupt 前的副作用必须幂等；`response_schema` 可让客户端渲染类型化表单；支持 approve/reject/edit/review。 |
| 7 | LangChain — Human-in-the-loop Middleware | https://docs.langchain.com/oss/python/langchain/human-in-the-loop | `interrupt_on` 按工具配置 `True`/`False`/`allowed_decisions`；`read_data: False` 表示安全操作**无需审批**；`when` 谓词按参数决定是否中断；决策有 `approve`/`edit`/`reject`/`respond`；支持 batched 与 per-call；`reject` 可带反馈。 |
| 8 | Vercel AI SDK — Tools and Tool Calling | https://ai-sdk.dev/docs/ai-sdk-core/tools-and-tool-calling | 有 `execute` 的工具**默认自动运行**；`toolApproval` 可为 `not-applicable`/`approved`/`denied`/`user-approval`，或按参数动态返回；手动审批是两次调用：第一次返回 `tool-approval-request`，应用收集决定，第二次带 `tool-approval-response` 继续；拒绝产生 `execution-denied`；`reason` 不是传数据/改参数的通道。 |
| 9 | Vercel AI SDK — Chatbot Tool Usage | https://ai-sdk.dev/docs/ai-sdk-ui/chatbot-tool-usage | 三种模式：服务端自动、客户端自动、需要用户交互；审批 UI 状态为 `approval-requested`/`approval-responded`/`output-denied`；`sendAutomaticallyWhen` 可在决定后自动续跑；`output-error` 是一等状态；客户端工具用 `addToolOutput` 回填。 |
| 10 | OpenAI Agents SDK — Human-in-the-loop | https://openai.github.io/openai-agents-js/guides/human-in-the-loop/ | `needsApproval` 可为 `true` 或按参数的 async 函数；暂停返回 `interruptions`；用 `state.approve/reject` 恢复；支持 `alwaysApprove`/`alwaysReject`、部分解决、`RunState` 序列化；审批状态应服务端保存，并做认证、授权、防重放，不能信任客户端提交的完整快照。 |

### B. AG-UI / MCP

| # | 来源 | URL | 能直接支持的交互规则 |
|---|---|---|---|
| 11 | AG-UI — Interrupts | https://docs.ag-ui.com/concepts/interrupts | `RunFinished.outcome={type:"interrupt",interrupts:[...]}`；`Interrupt` 含 `id`/`reason`/`message`/`toolCallId`/`responseSchema`/`expiresAt`；恢复用同 thread 的 `resume[]`，每项 `interruptId` + `status`（`resolved`/`cancelled`）+ `payload`；必须覆盖所有 open interrupts；同参数重放必须幂等；过期/校验失败返回 `RunError`；`editedArgs` 是**全量替换**；恢复后直接发 `ToolCallResult`，不重发 `ToolCallArgs`。 |
| 12 | AG-UI — Capabilities | https://docs.ag-ui.com/concepts/capabilities | `humanInTheLoop` 下显式声明 `approvals`/`interventions`/`feedback`/`interrupts`/`approveWithEdits`；客户端按声明显示审批/编辑 UI；缺省字段表示未知，不等于不支持。 |
| 13 | MCP — Elicitation | https://modelcontextprotocol.io/specification/2025-06-18/client/elicitation | 服务端在工作流中请求用户输入，客户端用 `requestedSchema` 渲染表单；schema **仅限扁平对象 + 原始类型**（无嵌套/数组）；响应 `accept`/`decline`/`cancel` 三态；servers **MUST NOT** 收集敏感信息；客户端应表明请求方、允许修改、提供拒绝/取消。 |
| 14 | MCP — Tools | https://modelcontextprotocol.io/specification/2025-06-18/server/tools | 工具是 **model-controlled**，模型可自动发现/调用；但应始终有可拒绝的人类监督；对敏感操作提示确认；工具注解必须视为不可信（除非可信服务器）；执行错误用 `isError` 返回，供模型自我修正。 |
| 15 | MCP — Tool Annotations（官方博客） | https://blog.modelcontextprotocol.io/posts/2026-03-16-tool-annotations/ | `readOnlyHint` 可跳过确认；`destructiveHint` 应警告；`idempotentHint` 可安全重试；`openWorldHint` 要审查输出/标记信任边界；默认是悲观假设（非只读、可能破坏、非幂等、开放世界）；注解只是 hint，不是保证/强制执行。 |

### C. 表单与渐进披露

| # | 来源 | URL | 能直接支持的交互规则 |
|---|---|---|---|
| 16 | GOV.UK Design System — Question Pages | https://design-system.service.gov.uk/patterns/question-pages/ | 一屏一问；每页有 Back、标题、Continue；进度指示可选且应先测试；不要重复问已提供的信息；可选字段标 `optional`；允许 “I don't know”；浏览器返回要保留状态；不可逆动作返回时给提示而不是重复执行。 |
| 17 | NN/g — Progressive Disclosure | https://www.nngroup.com/articles/progressive-disclosure/ | 先展示少量核心选项，高级选项按需展开；staged disclosure/wizard 适合**线性、低耦合**步骤；步骤相互依赖时拆分反而有害；步骤太多会增加导航负担；单屏 vs 多屏要看任务耦合度。 |
| 18 | MCP Apps — 官方扩展站 | https://apps.extensions.modelcontextprotocol.io/ | 可选扩展：工具声明 `ui://` HTML 资源，host 在 **sandboxed iframe** 渲染，UI 可与 host 双向通信并调用工具；适合富交互（图表、表单、画布），不是简单表单的默认方案。 |

**版本注意：** CopilotKit 当前 v2 hook 是 `useFrontendTool`/`useRenderTool`/`useHumanInTheLoop`；v1 的 `useCopilotAction` + `renderAndWaitForResponse` 是旧 API，语义相近但不要混用。Vercel AI SDK 当前用 `toolApproval`，`needsApproval` 已标记 deprecated。AG-UI/MCP 文档有版本演进，落地前按锁定版本复核。

---

## 三、什么适合自动、什么必须用户决定

### 3.1 更适合自动的动作

| 类型 | 典型动作 | 适合自动的条件 | 依据 |
|---|---|---|---|
| 只读/检索/预览/校验 | 查询、搜索、diff、预检、生成预览 | 不改状态；结果可复核 | Vercel 默认自动执行；MCP `readOnlyHint`；LangChain `read_data: False` |
| 草稿/内部转换 | 生成草稿、摘要、字段归一化 | 不对外发布；可编辑 | Vercel 默认；CopilotKit 前端工具 |
| 可逆低风险写入 | 创建草稿/任务、应用筛选、UI 状态、保存偏好 | 可撤销/可编辑；无外部副作用 | CopilotKit `useFrontendTool`；Vercel 默认；LangChain `False` |
| 幂等/预批准范围 | 重试只读、重复同步、批内安全操作 | `idempotentHint`；同一幂等键 | MCP 注解；AG-UI 幂等重放；LangGraph 幂等要求 |
| 只读导出 | 导出当前视图/报表 | 无敏感外发；有权限与审计 | MCP `readOnly`/`openWorld`；Vercel 动态审批 |

注意：**“只读”不等于“无风险”**。只读导出如果涉及敏感数据、跨租户或外发，仍应按 `openWorldHint` 的逻辑审查输出，而不是一律自动。

### 3.2 必须由用户决定的，通常不是“写”本身

| 类型 | 例子 | 为什么必须人决定 | 依据 |
|---|---|---|---|
| 不可逆/破坏性 | 删除、覆盖、发布、权限变更、扣费 | 失败不可逆，影响范围大 | Vercel 敏感操作；MCP `destructiveHint`；LangChain `edit/reject` |
| 外部副作用 | 发邮件、通知、导出给第三方、公开发布 | 影响外部主体、声誉、合规 | CopilotKit HITL；AG-UI `tool_call`；MCP `openWorldHint` |
| 敏感数据/同意 | PII、凭据、跨境/跨租户共享 | 合规与信任 | MCP elicitation **MUST NOT** 收集敏感信息；OpenAI 审批安全；Vercel 审批签名 |
| 高不确定/信息不足 | 意图歧义、缺关键字段、约束冲突 | 模型不应猜 | CopilotKit HITL 的 edge cases；LangGraph `response_schema`；MCP elicitation |
| 策略例外 | 超阈值、工作区外、异常收件人 | 需要人判断/授权 | Vercel 动态审批；LangChain `when` 谓词；OpenAI `needsApproval` 函数 |
| 法律/合同/审计 | 条款、审批、发布版本 | 责任归属与可追溯 | AG-UI 三段审计；LangGraph checkpoint；OpenAI `RunState` |

### 3.3 纠正一个常见误解：不是“所有写操作都必须确认”

一手资料显示的共同模式是“默认自动 + 风险分级”，不是“默认阻断所有写操作”：

- **Vercel AI SDK**：文档原话是“By default, tools with an `execute` function run automatically as the model calls them.” `toolApproval` 的默认状态是 `'not-applicable'`；只有显式配置 `'user-approval'` 才暂停。
- **LangChain HITL**：`read_data: False` 明确表示“Safe operation, no approval needed”；`when` 谓词可以让同一工具只在参数越界时暂停。
- **CopilotKit**：`useFrontendTool` 的 handler 会直接执行；HITL 是另一个显式注册的 hook，不是所有工具默认带确认。
- **MCP**：工具是 model-controlled，模型可以自动调用；规范要求“有可拒绝的人类监督”，并对敏感操作提示确认，但这不是“每次调用都阻塞”。注解也是 hint，不是强制。
- **OpenAI Agents SDK**：`needsApproval` 是按工具/参数配置的，不是全局开关；只对需要审批的工具返回 `interruptions`。
- **AG-UI**：中断是显式协议事件；agent 不声明中断能力，就不会有审批流。

所以更准确的表述是：**行业共同模式 = 默认自动 + 风险/参数/策略分级 + 始终可拒绝/取消 + 可审计/可恢复。** “所有写操作都必须确认”既不是规范要求，也不是主流实现。

### 3.4 工具结果如何恢复

通用恢复链路：

1. 模型提出动作（工具名 + 参数）。
2. 运行时/策略决定暂停；**先持久化**（checkpoint / RunState / messages），再展示。
3. UI 展示预览 + 决策控件；可编辑时用全量替换字段。
4. 用户 approve / edit / reject / cancel。
5. 客户端发送与原动作关联的 **resume payload**，不是新的一条用户消息。
6. 运行时校验 schema；执行或跳过；错误按可重试/不可重试处理。
7. 结果作为工具结果/返回值回到模型；模型继续。
8. 审计记录 proposal → decision → result。

| 层 | 暂停标识 | 恢复输入 | 结果回填 |
|---|---|---|---|
| LangGraph | `thread_id` + interrupt id | `Command(resume=...)` | `interrupt()` 返回值 / `ToolMessage` |
| LangChain HITL | interrupt id | decisions（approve/edit/reject/respond） | `ToolMessage`；reject 为反馈，respond 为人类回复 |
| CopilotKit | tool call / interrupt | `respond(payload)` | 工具结果回到 agent |
| Vercel AI SDK | `approvalId` | `tool-approval-response` + 再次调用 | 执行结果或 `execution-denied` |
| AG-UI | `interrupt.id` | `resume[{interruptId,status,payload}]` | `ToolCallResult` against original `toolCallId` |
| OpenAI Agents SDK | interruption | `state.approve/reject` + `RunState` | 恢复运行，工具执行/拒绝 |
| MCP | elicitation request | `accept`/`decline`/`cancel` | `content` 回到 server workflow |

**失败与恢复的关键差异：**

- **校验失败**：应带同一 ID 重新提问。LangGraph 会抛 `ValidationError`；AG-UI 返回 `RunError`；MCP 客户端应先本地校验。
- **工具执行失败**：Vercel 用 `output-error`；MCP 用 `isError`；不要把它伪装成成功结果。
- **用户取消**：AG-UI 用 `cancelled`；MCP 用 `cancel`；其他框架多为应用层语义。**取消不等于拒绝**：拒绝是“我不同意，你可以改方案”，取消是“我放弃这次操作”。
- **超时/过期**：AG-UI 有 `expiresAt`，过期 resume 返回 `RunError`；LangGraph 会无限等待，生产上需要应用层 TTL；Vercel/OpenAI 也需要应用层过期策略。
- **进程崩溃**：LangGraph 靠 checkpointer；OpenAI 靠 `RunState` 序列化；AG-UI 靠 `StateSnapshot`/`MessagesSnapshot`；Vercel 靠应用持久化 messages。
- **重复提交**：AG-UI 同 `(threadId, interruptId, status, payload)` 必须幂等；OpenAI 要求原子消费 pending request；LangGraph 要求 interrupt 前的副作用幂等。
- **部分成功**：批量操作要逐项状态，不能把“部分成功”说成“全部成功”。
- **审批/恢复不等于 exactly-once**：OpenAI 文档明确说，消费 pending request 只能防止同一快照被重复恢复，**不保证工具副作用恰好执行一次**；恢复前要核对已完成或不确定的副作用。

---

## 四、业务操作预览、编辑、取消与恢复

### 4.1 预览应该展示什么

综合各框架的 interrupt/approval payload，一个“可审视的业务预览”至少应包含：

- 动作名与版本；
- 目标对象/ID/范围/条数；
- 将改变什么（diff / before-after）；
- 不会改变什么；
- 外部副作用、收件人、发布目标；
- 不可逆的部分；
- 校验结果与警告；
- 来源：模型生成 / 用户输入 / 系统默认；
- 决策选项：批准 / 编辑 / 拒绝 / 取消；
- 有效期/超时；
- 幂等键与审计字段。

CopilotKit 的 `render` 能拿到 `args`/`status`；AG-UI 的 `Interrupt` 带 `message`/`responseSchema`/`toolCallId`；LangGraph 的 `interrupt` payload 可以放 `action_details`；Vercel 的 `approval-requested` part 带 `input`。这些都能支撑“先预览、再决定”。

### 4.2 决策语义不要混用

| 语义 | 典型表达 | 对模型/流程的影响 |
|---|---|---|
| 批准 | AG-UI `resolved + approved:true`；MCP `accept`；LangGraph `approve`；Vercel `approved:true`；CopilotKit `respond({approved:true})` | 执行原动作 |
| 编辑后批准 | AG-UI `editedArgs`（全量替换）；LangGraph `edit`；MCP `accept` + 修改后的 content；CopilotKit `respond(edited)` | 用新参数执行；审计保留原提案 + 编辑 |
| 拒绝 | AG-UI `resolved + approved:false`；MCP `decline`；LangGraph `reject` + message；Vercel `approved:false` + reason；CopilotKit `respond({approved:false})` | 不执行；反馈给模型，可改方案 |
| 取消/放弃 | AG-UI `cancelled`；MCP `cancel`；其他框架应用层 | 不执行；通常不继续劝说/重试 |
| 错误 | Vercel `output-error`；MCP `isError`；AG-UI `RunError` | 记录错误；按可重试/不可重试处理 |
| 过期 | AG-UI `expiresAt` → `RunError` | 重新确认/重新授权 |

**注意：** LangGraph 没有独立的 `cancel` 协议状态；`reject` 是决策，取消可由图路由到 cancel 节点。Vercel/OpenAI/CopilotKit 也没有统一的 cancel 协议态，需要应用层定义。不要把 `cancel` 和 `reject` 当成同义词。

### 4.3 审批安全

- **审批人身份与权限必须服务端校验**。OpenAI 文档明确：不要从审批请求体里取身份；要认证、授权、校验 decision ID、原子消费、防重放。
- **审批状态应服务端保存**。浏览器/移动端只拿展示信息和 opaque ID；不要把完整 `RunState` 交给客户端再信任它。
- **敏感工具加签名/secret**。Vercel 提供 `experimental_toolApprovalSecret`，否则客户端可以伪造审批响应。
- **工具参数是不可信展示内容**。OpenAI 文档要求过滤敏感值、转义 HTML；MCP 规范要求注解不可信。
- **MCP elicitation 不能收集敏感信息**；凭据应走独立安全流程。
- **前端工具不要放密钥/授权逻辑**；它适合 React 状态和浏览器 API，不适合作为敏感业务的可信执行层。
- **生成式 UI 要用应用自有组件或 sandboxed iframe**；不要让模型输出任意 HTML 进主 DOM。

---

## 五、短表单、渐进收集、按风险确认、完成后自动下一步

| 设计主张 | 直接依据 | 适用边界 |
|---|---|---|
| **短表单/一屏一问** | GOV.UK Question Pages：一屏一问、Back/Continue、进度可选、不问重复信息；NN/g：staged disclosure 适合线性低耦合步骤 | 相互依赖的配置/筛选不要硬拆；拆太多步会增加导航负担；少数字段且相互依赖时，一屏可能更好 |
| **渐进收集** | LangGraph：每个节点一次 `interrupt`，用条件边回到节点重新提问；MCP elicitation：一次请求一组扁平字段；CopilotKit：HITL 内可渲染表单；GOV.UK：只问必要信息、允许返回修改 | 每次只问必要信息；支持跳过/返回；复杂嵌套、数组、多选不适合通用 schema；不要重复问 |
| **按风险确认** | Vercel：`toolApproval` 可按参数动态返回；LangChain：`when` 谓词按参数中断；OpenAI：`needsApproval` 可为 async 函数；MCP：`readOnly`/`destructive`/`idempotent`/`openWorld` 注解 | 只对敏感/不可逆/外部副作用/策略例外确认；默认自动；确认不是越多越好；注解不可信，不能当保证 |
| **完成后自动下一步** | Vercel：`sendAutomaticallyWhen` 在审批/工具结果完成后自动续跑；CopilotKit：`respond` 后 agent 自动继续；LangGraph：`Command(resume)` 恢复；AG-UI：`resume` 恢复运行 | **工作流级自动续跑有明确依据；表单字段级“填完自动跳下一题”没有通用规范**。GOV.UK 用的是 Continue，并要求 Back 可改 |

### 5.1 对“筛选条件一次一个字段”的审视

把“筛选条件一次一个字段”当默认，需要证据支持：

- GOV.UK 的 one thing per page 面向**服务问答**，不是探索性筛选。
- NN/g 明确说 staged disclosure 在**步骤相互依赖、用户需要来回修改**时是有问题的；筛选往往正是这种场景——用户要边看结果边调条件。
- 更稳的对照是：**常用筛选一屏 + 实时结果 + 高级条件渐进披露**；只有首次引导/长尾条件才逐项问。
- LangGraph 的“一次一个 interrupt + 条件边重问”适合“缺一个关键字段”，不适合“十个相互影响的筛选条件”。
- MCP elicitation 的扁平 schema 也说明：协议层并不要求“一次一个字段”，它一次可以问一组扁平字段。

### 5.2 对“配置项多选”的审视

- 多选本身是一个认知任务；拆成逐项追问容易制造步骤膨胀。
- NN/g 的酒店预订例子说明：**相互依赖的选项应在一屏**，否则用户难以做 what-if 比较；只有支付等后续阶段才适合 staged disclosure。
- GOV.UK 也允许把相关问题分组在同一页，前提是用户研究支持。
- MCP elicitation 的 schema **不支持数组/嵌套对象**，所以复杂多选不应硬套通用 elicitation 表单；用应用自有组件（如 CopilotKit `useRenderTool`/`useComponent`）更合适。
- 对照项应是：默认安全值、全选/清空、已选摘要、最终预览，而不是“逐项确认”。

### 5.3 对“完成后自动下一步”的审视

分两层看：

- **工具/审批完成后自动恢复 agent 循环**：有明确 API。Vercel `sendAutomaticallyWhen`、CopilotKit `respond`、LangGraph `Command(resume)`、AG-UI `resume` 都支持。适合低风险、线性、结果明确的下一步。
- **表单字段填完后自动跳下一题**：没有通用规范。GOV.UK 要求 Continue 按钮，并保留 Back；字段级自动前进只在“单选/明确答案、可见进度、可返回、可取消、通过无障碍测试”时才值得做。

所以，如果项目要追求“完成后自动下一步”，更稳的落点是：**在“动作完成/审批完成”这一层自动续跑；在“字段填写”这一层保留显式 Continue。** 不要把两者混成一个规则。

---

## 六、生成式表单/生成式 UI 的适用边界

| 层级 | 机制 | 适合 | 注意 | 来源 |
|---|---|---|---|---|
| 1. 工具结果渲染 | 按工具名绑定应用自有组件；模型给参数 | 状态卡、预览、小表单、审批卡 | 组件、校验、权限都归应用 | CopilotKit `useRenderTool` |
| 2. Schema 驱动表单 | JSON Schema → 表单；accept/decline/cancel | 缺一个字段、小型扁平表单、枚举选择 | 不适合嵌套/数组/敏感数据 | MCP elicitation；AG-UI `responseSchema` |
| 3. 声明式 UI | agent 提议组件树/约束；应用校验后挂载 | 需要一定灵活性的组合 UI | 需要组件目录、校验、权限 | AG-UI 概览 |
| 4. Sandboxed HTML App | `ui://` 资源，host 在 iframe 渲染 | 富交互图表、画布、复杂表单 | host 支持、安全面、成本高 | MCP Apps |

对本项目更现实的判断：

- **1–2 层通常足够**：创建任务、配置项选择、审批预览，都可以用应用自有组件 + schema 校验完成。
- **3–4 层只在确实需要富交互时考虑**；不要因为“生成式 UI”这个概念就引入 iframe 应用。
- **MCP elicitation 适不适用？** 适用于“问一个缺失字段/一小组扁平字段/枚举选择”，不适用于嵌套配置、多选数组、凭据/PII。它要求客户端支持，且是 server-initiated；把它当**交互模式参考**可以，不必为了表单引入 MCP。
- **MCP Apps 适不适用？** 它是可选扩展，适合需要嵌入式富 UI 的场景；简单表单不需要。若采用，必须接受 sandboxed iframe、host 兼容性和额外安全面。

---

## 七、本项目场景对照项（供审视，不是改法）

| 场景 | 供审视的对照项 | 依据 |
|---|---|---|
| **创建任务** | 1）创建是否可逆/低风险？若是，提交前二次确认是否反而多余，改成创建后编辑/撤销？2）创建是否会触发通知、外部系统、计费？若是，是否只对“副作用步骤”确认？3）表单是否短到一屏可完成？字段是否相互依赖？ | Vercel 默认自动；LangChain `read_data: False`；MCP 注解；GOV.UK/NN/g |
| **配置项多选** | 1）是否把多选拆成逐项追问，导致步骤膨胀？2）选项是否相互依赖？若依赖，是否应一屏/分组而非逐项？3）是否有默认安全值、全选/清空、已选摘要、最终预览？4）通用 schema 是否支持数组/嵌套？若不支持，是否用应用自有组件？ | NN/g；GOV.UK；MCP elicitation 扁平 schema；CopilotKit 组件 |
| **筛选条件一次一个字段** | 1）筛选是探索性/相互依赖的吗？若是，逐字段是否比“一屏 + 实时结果”更差？2）是否只对首次引导/长尾条件逐步问，常用筛选保留面板？3）每次回答后是否自动应用并显示结果数？4）是否可跳过/返回修改/清除？ | GOV.UK；NN/g staged disclosure 边界；LangGraph 重问模式；MCP 扁平 schema |
| **导出** | 1）导出是只读吗？若是，是否可直接执行，仅在敏感数据/外发时确认？2）是否显示范围/条数/字段/脱敏规则？3）长任务是否可取消/查看进度/失败重试？ | MCP `readOnly`/`openWorld`；Vercel 默认自动；AG-UI `cancelled`；Vercel `output-error` |
| **导入** | 1）是否先解析/校验/预览差异，再写入？2）覆盖/新增/跳过策略是否明确？3）失败是原子回滚还是部分成功？4）是否有幂等键/任务 ID/取消/重试？5）批量审批是整批还是按异常项？ | LangGraph 幂等；AG-UI resume；LangChain batched/per-call；OpenAI 部分解决 |
| **发布审批** | 1）预览的是最终发布内容还是原始输入？2）是否支持批准时编辑，且编辑是全量替换？3）拒绝是否带原因并可修改后重提？4）审批是否绑定版本/有效期/幂等？5）审批人是否服务端认证/授权，客户端能否伪造？ | AG-UI `editedArgs`/`expiresAt`；LangChain approve/edit/reject；Vercel 审批 secret；OpenAI 服务端状态 |
| **失败与恢复** | 1）每个可暂停动作是否有稳定 ID/幂等键？2）恢复是否只重放安全步骤？3）cancel/reject/error/expiry 是否分开处理？4）部分成功是否可见？5）审批状态是否服务端保存、防重放？ | LangGraph 节点重跑；AG-UI 幂等/过期；OpenAI `RunState`；MCP `isError` |
| **完成后自动下一步** | 1）哪些步骤可自动续跑（只读、已批准、结果展示）？2）哪些必须停（高风险、不可逆、新授权、结果不确定）？3）字段级自动前进是否只在单选/明确答案且可返回时使用？4）是否始终有停止/取消？ | Vercel `sendAutomaticallyWhen`；CopilotKit `respond`；LangGraph resume；GOV.UK Continue |

---

## 八、不要把框架功能等同“本项目必须引入框架”

- CopilotKit 的 `useHumanInTheLoop`、LangGraph 的 `interrupt`、Vercel 的 `toolApproval`、AG-UI 的 `resume`，都是**契约实现**，不是产品必需。
- 如果项目不是 React/TS，CopilotKit 的 hook 不能直接用；如果编排不是 LangGraph，`checkpointer`/`thread_id` 不能直接搬；如果只做内部表单，MCP elicitation/Apps 可能过度。
- 引入框架的收益是运行时、生态、协议兼容；成本是耦合、迁移、版本锁定。是否引入，应由现有技术栈和运维能力决定，不由“它有 HITL”决定。
- 更值得复用是下面这组**与框架无关的契约清单**：
  1. 动作注册表（稳定名称/版本）；
  2. 风险策略（动作、参数、角色、环境）；
  3. 预览 payload；
  4. 决策枚举（approve/edit/reject/cancel/expire/error）；
  5. 恢复关联（thread + action ID）；
  6. 状态持久化；
  7. 幂等与防重放；
  8. 过期与重新授权；
  9. 审计（proposal → decision → result）；
  10. 服务端认证与授权。

---

## 九、研究限制

- 本文是**文档核验**，没有运行示例代码；框架行为以其文档为准，落地前应按锁定版本做 spike。
- 未读取项目代码与密钥，未调用真实模型；场景对照基于你列出的“创建任务、配置项多选、筛选条件一次一个字段、导出/导入/发布审批、失败与恢复”。
- CopilotKit、AI SDK、AG-UI、MCP 都在演进；文中 API 名称和版本语义需要在实现前复核。
- 厂商文档自带立场；协议规范（AG-UI/MCP）比框架文档更接近“契约”，但也不等于本项目必须遵循。

**如果只记一句：** 把“确认”从布尔开关升级为“**风险策略 + 预览 + 决策语义 + 可恢复契约**”，比决定“要不要引入某个框架”更重要。
