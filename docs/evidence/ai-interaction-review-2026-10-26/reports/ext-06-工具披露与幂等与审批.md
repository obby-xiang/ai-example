> 来源=agent-6m5gqjhj/output.log（2026-10-26 归档）· 外部研究（工具披露 vs 执行闸、幂等、批准版本绑定、重试策略）
> 性质：本次只读分析/调研的**脱敏副本**，属历史证据；为**分析稿**，结论以 `docs/research/` 总册与「规格复核补正」为准。仅做本机路径/仓库名/个人用户名占位与空白清理，**技术结论、计数、状态词一字未改**。

> 说明：以下仅为外部只读资料研究。检索与抓取时间为 2026-10-26。未改动工作区文件、服务、代码或文档；未读取或发送密钥；未调用真实模型；未再委派。`tools_condition` 与 `streamRetries` 的原文和版本由另一 agent 核验，本报告不重复考证。

## 一、结论先行

1. **工具集是业务状态的投影，不是静态能力清单。** 披露过滤回答“模型此刻看见什么”，执行时校验回答“系统此刻允许做什么”。前者影响准确率、成本和最小暴露，后者决定安全与正确性。两者都必须有。
2. **防重复 start 的核心不是按钮置灰**，而是“逻辑任务 ID + 终态 + 唯一约束/条件更新 + 幂等键”。框架的 workflow ID、thread ID 只是实现载体。
3. **预览与批准必须绑定“版本化提案”**：参数哈希、资源版本、工具/策略版本、批准人、过期时间、checkpoint/interrupt ID。执行时按 `If-Match` 语义复核，失败返回 409/412 语义，不执行、不部分执行。
4. **副作用之后失败，先对账再重试。** 只重试“错误可重试 + 操作可重放”的步骤；commit 步骤必须有幂等键或唯一约束；结果未知时不得盲重试。
5. **自动恢复适用于**瞬时/基础设施故障、重放安全、无待决人工决策、无版本漂移、预算内的场景。永久错误、权限失败、业务规则、版本冲突、批准过期、副作用未知，必须升级人工。
6. **无条件重试会放大副作用、掩盖永久错误、造成重试风暴、绕过批准。** 重试必须是 per-step、按错误分类、有预算、可审计的策略。

---

## 二、一手来源与直接支持的规则

| S# | 来源 | 直接支持的规则（要点） |
|---|---|---|
| S1 | [OpenAI Function calling](https://developers.openai.com/api/docs/guides/function-calling) | `tool_choice` 支持 `auto`/`required`/强制单函数/`allowed_tools` 子集；`parallel_tool_calls=false` 保证一次 0 或 1 个调用；`strict=true` 保证参数符合 schema；建议一轮开始时可用函数少于 20 个（软约束）；工具定义计入上下文并按输入 token 计费。 |
| S2 | [OpenAI Tool search](https://developers.openai.com/api/docs/guides/tools-tool-search) | `defer_loading` + `tool_search` 延迟加载；namespace/server 初始只暴露名称与描述；当发现依赖 project/tenant state 时使用 client-executed search；返回的工具 schema 要仔细校验，只暴露可信定义；未列入 `tool_search_output` 的工具不可用；变更 loaded set 会破坏缓存。 |
| S3 | [OpenAI Agents SDK Tools](https://openai.github.io/openai-agents-python/tools/) | `is_enabled` 可按 context/runtime 条件启用工具，禁用工具对 LLM 完全隐藏；但“可见性/派发”不等于授权，必须在工具实现、input guardrail 或 approval 内检查，MCP server 必须自行授权；SDK approval 不提供 sandbox；programmatic tool calling 仅在 provider 明确标记 replay safe 时重试，`network_error()` 策略本身不能覆盖该边界。 |
| S4 | [OpenAI Agents SDK HITL](https://openai.github.io/openai-agents-python/human_in_the_loop/) | `needs_approval` 可按调用判定，参数缺失/畸形/非对象时 fail closed；`RunState` 可持久化；待决审批应存 agent/tool 定义版本标记；审批状态应服务端保存，认证并授权 reviewer，校验 pending 项，不接受客户端替换 tool call/arguments；原子消费防并发/重放；sticky approval 可跨 agent/权限，建议 per-call。 |
| S5 | [LangGraph Graph API](https://docs.langchain.com/oss/python/langgraph/graph-api) | 条件边调用 routing function，入参是当前 state，返回下一个或多个节点；多个 next 会在下一 super-step 并行执行；同一节点不要混用静态边与动态路由。 |
| S6 | [LangGraph Checkpointers](https://docs.langchain.com/oss/python/langgraph/checkpointers) | 每个 super-step 存 checkpoint；pending writes 保留同 super-step 已成功节点的写入，resume 不重跑；`thread_id` 是主键；durability 分 `exit`/`async`/`sync`，`exit` 无法从进程崩溃中恢复；replay 会重跑 checkpoint 之后的节点，包括 LLM、API、interrupt。 |
| S7 | [LangGraph Interrupts](https://docs.langchain.com/oss/python/langgraph/interrupts) | interrupt 保存状态并等待；resume 必须同一 `thread_id`；恢复时整个节点从头重跑，interrupt 之前的代码会再次执行，副作用必须幂等；副作用应放在 interrupt 之后或独立节点；`response_schema` 在 resume 时校验；多 interrupt 按 id 配对。 |
| S8 | [LangGraph Functional API](https://docs.langchain.com/oss/python/langgraph/functional-api) | 已完成 task 的结果从 checkpoint 恢复；已开始但未完成的 task 可能再次执行，副作用要幂等，使用 idempotency key 或先查已有结果；副作用/非确定性操作应放进 task。 |
| S9 | [LangGraph Fault tolerance](https://docs.langchain.com/oss/python/langgraph/fault-tolerance) | retry policy 可按异常类型/退避配置；默认不重试 `ValueError`、`TypeError` 等；retries 耗尽后才走 error handler；timeout 会清掉失败 attempt 的写入；支持 graceful shutdown 后 resume。 |
| S10 | [Temporal Activity Definition](https://docs.temporal.io/activity-definition) | Activity 应幂等；Activity 是原子单位，内部第 3 步失败会重跑全部 3 步，应拆细；idempotency key 可用 Workflow Run ID + Activity ID，跨重试稳定、跨 Workflow 唯一；Activity 可能执行多次，但平台观察为完成一次；已完成 Activity 在 replay 时不重跑，但未记录完成时会重试。 |
| S11 | [Temporal Workflow ID / Run ID](https://docs.temporal.io/workflow-execution/workflowid-runid) | 同一 Namespace 内同一 Workflow ID 最多一个 open；`RejectDuplicate` 禁止 closed 后复用；默认 `AllowDuplicate` 允许 closed 后新建；Conflict Policy 默认 `Fail`，可 `Use Existing`/`Terminate Existing`；去重受 retention 限制；ID 不得含 PII。 |
| S12 | [Temporal Error handling](https://docs.temporal.io/develop/python/best-practices/error-handling) | 区分 transient/intermittent/permanent；permanent 用 `non_retryable` 快速失败；Activities 是 at-least-once；通常不应给 Workflow 加 Retry Policy；部分失败用 Saga/补偿。 |
| S13 | [Temporal Durable Execution](https://docs.temporal.io/evaluate/understanding-temporal) | 崩溃后新 Worker 重建状态并从停点继续，局部变量与进度保持；Workflow 必须确定性，外部副作用放 Activity；失败 Activity 退避重试，Worker 死亡会重新调度；Event History 可 replay。 |
| S14 | [MCP Tools spec](https://modelcontextprotocol.io/specification/2025-06-18/server/tools) | Tools 是 model-controlled；SHOULD 始终有人可拒绝调用、展示工具/调用、敏感操作确认；Servers MUST 校验所有输入、访问控制、限流、清洗输出；Clients SHOULD 展示输入、确认敏感操作、校验结果、超时、审计；annotations 不可信；`listChanged` 通知目录变化。 |
| S15 | [WHATWG HTML SSE](https://html.spec.whatwg.org/multipage/server-sent-events.html) | EventSource 断线会自动重连；`retry:` 设置重连时间；重连时若 last event ID 非空会发 `Last-Event-ID`；204 停止重连；fatal 失败不再重连；重连时间可加指数退避。 |
| S16 | [WHATWG Fetch](https://fetch.spec.whatwg.org/) | 定义单次 fetch 与响应体流（ReadableStream）；没有 EventSource 式的自动重连/`Last-Event-ID` 语义；断流后是否重新请求由调用方决定。 |
| S17 | [RFC 9110 §9.2.2](https://www.rfc-editor.org/rfc/rfc9110.html#section-9.2.2) / [§13.1.1](https://www.rfc-editor.org/rfc/rfc9110.html#section-13.1.1) | 幂等方法可在通信失败后自动重复；非幂等 SHOULD NOT 自动重试，除非知道语义确实幂等或能检测原请求未生效；代理 MUST NOT 自动重试非幂等；客户端 SHOULD NOT 重试已失败的重试；`If-Match` 必须在执行方法前评估，条件为假 MUST NOT 执行，可用 412，已应用可 2xx。 |
| S18 | [CopilotKit Frontend Tools](https://docs.copilotkit.ai/reference/v2/hooks/useFrontendTool) / [useAgentContext](https://docs.copilotkit.ai/reference/v2/hooks/useAgentContext) | 前端工具 mount 注册、unmount 清理，`available: enabled/disabled` 可条件可用；handler 在浏览器执行；app state 通过 `useAgentContext` 随组件生命周期注册/移除并响应式更新，wire 上是 JSON string。 |
| S19 | [CopilotKit useInterrupt](https://docs.copilotkit.ai/human-in-the-loop/useInterrupt) | 区分 graph-enforced checkpoint 与 LLM-initiated pause；interrupt 带 `id`、`expiresAt` 等；`resolve(payload, interruptId)` 按 id 定向；`responseSchema` 只用于 UI 呈现，客户端不校验，resume 时由 agent 校验。 |

---

## 三、逐问回答

### 1. 为什么工具集要随业务状态变化

工具集应当是 `(actor, tenant, resource_state, task_phase, policy_version)` 的函数，而不是静态全集。原因有四层：

- **权限与生命周期**：已完成的任务不应再出现 `cancel`/`approve`；非审批人不应出现 `approve`；超出退款窗口不应出现 `refund`。这是设计推论；外部直接支持是 Agents SDK `is_enabled` 的 request-scoped visibility 和 MCP 的 human-in-the-loop 要求（S3、S14）。
- **准确率**：工具越多、名字越像，模型越容易选错。OpenAI 建议初始可用函数少于 20 个，并用 namespace/tool search 延迟长尾工具（S1、S2）。
- **成本**：工具定义会进入上下文并按输入 token 计费；tool search 可把不常用工具延迟到需要时加载（S1、S2）。
- **运行时/租户/UI 状态**：当工具发现依赖 project/tenant state 时，OpenAI 明确建议 client-executed tool search；CopilotKit 前端工具随组件 mount/unmount，并可用 `available` 控制；MCP 用 `listChanged` 通知目录变化（S2、S18、S14）。
- **状态路由**：LangGraph 的条件边直接以当前 state 作为 routing function 输入，状态变化会改变下一步走哪个节点（S5）。

但有一个关键边界：**披露过滤不等于隐藏，也不等于授权。** OpenAI tool search 对单个 deferred function 仍会暴露名称和描述，主要延迟的是参数 schema；要真正隐藏存在性，应使用 namespace/server 或不注册（S2）。Agents SDK 也明确说 `is_enabled` 只控制可见性与派发，不替代基于参数的授权（S3）。

### 2. 任务完成后如何防止重复 start

先区分三层 ID：

- `task_id`：业务逻辑任务，完成即终态；
- `run_id`：一次执行；
- `attempt`：一次重试。

“任务完成后再 start”应当是**新任务**，不是旧任务的 transport retry。防重复要靠四道闸：

1. **状态机 + 唯一约束**：`status` 进入 `COMPLETED`/`CANCELLED` 后不可回到 `READY`；对 `(tenant, task_type, business_key)` 建唯一约束（只约束 active 状态），或用条件更新 `UPDATE ... WHERE status IN ('READY','RUNNING')`，0 行即拒绝。
2. **start 幂等键**：同一 `idempotency_key` 的重复 start 返回原 `run_id`，不产生第二行；不同 key 但同一业务键，返回 409 语义。
3. **框架去重策略**：Temporal 同一 Workflow ID 最多一个 open；需要“完成后也不能复用”时用 `RejectDuplicate`，需要“失败可重跑、完成不可重跑”时用 `AllowDuplicateFailedOnly`；默认 `AllowDuplicate` 允许 closed 后新建，不能想当然（S11）。
4. **传输重连不触发 start**：SSE 重连只是恢复事件流，应带 `run_id`/`Last-Event-ID` 做 resume，不得调用 start（S15）。

LangGraph 侧，完成时 checkpoint 的 `next=()` 表示图已结束；`thread_id` 是指针，不应把已完成的 thread 当作“新任务”的入口，新任务应新 thread/新 run（S6）。UI 禁用按钮只是 UX，服务端唯一约束和条件更新才是并发控制。

### 3. 预览与批准为什么要绑定版本

批准不是“批准这个工具”，而是“批准这个**版本化提案**”。预览与执行之间可能发生：价格/库存变化、审批人权限变化、资源被他人修改、工具/提示词/策略版本变化。若批准不绑定版本，就是典型的 TOCTOU（检查时与使用时不一致）。

建议至少绑定：

- `approval_id` / `run_id` / `interrupt_id`；
- `tool_name` + `tool_version`；
- canonical `args_hash`；
- `resource_id` + `resource_version`/ETag；
- `policy_version`、`prompt_version`；
- `actor` / `tenant`；
- `expires_at`。

执行时重新读取资源并复核；不匹配则不执行。RFC 9110 的 `If-Match` 正是这个语义：origin server MUST 在执行方法前评估，条件为假 MUST NOT 执行，可用 412 表示失败，若已应用可返回 2xx（S17）。

外部直接支持还包括：

- LangGraph interrupt 保存 checkpoint，resume 必须同一 `thread_id`；恢复时节点从头重跑，interrupt 之前的副作用必须幂等；`response_schema` 在 resume 时校验（S7）。
- CopilotKit interrupt 带 `id`、`expiresAt`，`resolve` 按 interrupt id 定向；`responseSchema` 客户端不校验，由 agent 在 resume 时校验（S19）。
- OpenAI `RunState` 建议在待决审批旁存 agent/tool 定义版本标记；审批状态应服务端保存，不接受客户端替换 tool call/arguments；并发/重放提交要原子消费（S4）。

设计推论：**批准后模型不得重新生成参数**。如果用户编辑了参数，那是新提案，必须重新计算 hash 并重新批准；不能拿旧 approval 执行新参数。

### 4. 工具已经副作用后失败，如何只重试安全步骤

把“失败”拆成四种副作用状态：`none`、`attempting`、`applied`、`unknown`。只有 `none` 和明确 `not applied` 才适合重试；`unknown` 必须先对账。

推荐步骤：

1. **prepare**：纯校验、组装参数、读资源版本，可安全重试；
2. **commit**：真正产生副作用的一步，调用前写 intent/ledger，带稳定 idempotency key；
3. **verify**：按 key 查外部状态或回读资源；
4. **reconcile**：`applied` → 标记成功继续；`not applied` → 可重试 commit；`unknown` → 不盲重试，升级人工或进入对账任务。

外部直接支持：

- Temporal Activity 是原子单位，内部第 3 步失败会重跑全部 3 步；应拆成更细的 Activity，只让失败步骤重试；idempotency key 用 Workflow Run ID + Activity ID，跨重试稳定（S10）。
- Temporal 明确 Activities 是 at-least-once；Activity 可能成功但 Worker 在上报前崩溃，于是被重试；没有幂等键就可能重复扣款（S10、S12）。
- LangGraph pending writes 会保留同 super-step 已成功节点的写入，resume 不重跑；但失败节点恢复时从头重跑，所以 interrupt 之前的副作用必须幂等，副作用应放到 interrupt 之后或独立节点（S6、S7）。
- RFC 9110：幂等方法可自动重复；非幂等 SHOULD NOT 自动重试，除非知道语义确实幂等或能检测原请求未生效（S17）。

关键边界：**HTTP 方法是幂等的，不代表业务操作幂等。** `PUT` 一个资源可能是幂等的，但“发送邮件”“创建订单”“扣款”不是。commit 步骤没有幂等键时，不应自动重试。

### 5. 何时可以自动恢复而不需用户点重试

| 条件 | 可自动恢复 | 不可自动恢复 |
|---|---|---|
| 错误类别 | 瞬时网络、5xx、超时、限流、Worker 崩溃 | 4xx 参数/校验、认证/授权、业务规则 |
| 重放安全 | 只读、幂等、带 idempotency key | 非幂等 commit 且无 key |
| 副作用状态 | `none`、明确 `not applied`、明确 `applied` | `unknown` |
| 人工决策 | 无待决审批，或审批仍有效且版本一致 | 待决、过期、版本冲突 |
| 版本状态 | 资源/工具/策略版本未变 | 版本已变，旧计划失效 |
| 预算 | 在 max attempts/deadline 内 | 预算耗尽 |
| 用户意图 | 未取消 | 用户已取消 |

外部依据：Temporal 把失败分为 transient/intermittent/permanent，transient 可立即重试、intermittent 退避重试、permanent 不重试（S12）；Durable Execution 会在崩溃后从停点恢复（S13）；LangGraph 有 retry policy、timeout 和 error handler（S9）；SSE 的 EventSource 会自动重连（S15）。

但“自动恢复”不等于“自动重放业务计划”。如果资源版本变了、批准过期了、用户改了目标，正确动作是重新预览/重新规划/重新批准，而不是把旧计划再跑一遍。

### 6. 为什么不能把无条件重试当万能解

1. **放大副作用**：非幂等操作每重试一次就多一次扣款/邮件/订单；Temporal 明确要求 Activity 幂等（S10），RFC 明确非幂等不应自动重试（S17）。
2. **掩盖永久错误**：参数错误、权限失败、业务规则违反不会因重试而消失；Temporal 用 `non_retryable` 快速失败（S12），LangGraph 默认不重试 `ValueError`/`TypeError`（S9）。
3. **重复整个 Activity/Workflow**：Temporal Activity 是原子的，内部失败会重跑全部步骤；应拆细，而不是加大重试次数（S10）。Temporal 也明确通常不应给 Workflow 加 Retry Policy（S12）。
4. **结果未知时更危险**：请求可能已生效但响应丢失；不先对账就重试，会重复副作用（S10、S17）。
5. **重试风暴**：无上限重试会压垮下游；需要 backoff、jitter、deadline、最大次数（S12）；RFC 甚至说客户端不应重试“已经失败的重试”（S17）。
6. **绕过人工批准/版本闸门**：自动重试可能消费过期批准，或用旧参数执行新状态；审批必须绑定版本（S4、S17）。
7. **不解决非确定性与事件丢失**：LLM 重试可能生成不同计划；SSE 断线需要事件 ID/服务端 replay，不是重试业务请求（S6、S15）。

因此重试策略应是：`retry = f(error_class, replay_safety, side_effect_state, approval_state, version_state, budget)`，而不是一个全局开关。

---

## 四、两层模型：披露过滤 vs 执行时校验

| 维度 | 披露过滤 | 执行时校验 |
|---|---|---|
| 回答的问题 | 模型此刻“看见”哪些工具/状态 | 系统此刻“允许”做什么 |
| 时机 | 组装 prompt / 调用模型前 | 模型返回 tool call 后、产生副作用前 |
| 典型机制 | `allowed_tools`、`tool_search`/`defer_loading`、`is_enabled`、`available`、`listChanged`、条件路由 | schema 校验、业务规则、授权、状态机、版本/ETag、幂等键/唯一约束、审批 token、限流、审计 |
| 能防 | 工具过多、选择困难、上下文浪费、无意暴露 | 未授权、越权、过期状态、重复提交、旧版本覆盖、参数注入 |
| 不能防 | 模型幻觉调用、提示注入、目录陈旧、绕过前端直接调 API | 未编码的业务规则、该由人做的判断 |
| 失败策略 | 可以缓存、近似、降级；但未知工具应拒绝并刷新 | 必须 fail-closed；不满足条件不得执行 |

**为什么不能靠提示词代替硬闸：**

- MCP 明确 Tools 是 **model-controlled**，协议本身不强制交互模型；同时要求 Servers **MUST** 校验所有输入、访问控制和限流（S14）。这是两套东西。
- OpenAI Agents SDK 明确说 `is_enabled` 控制可见性与派发，**不替代**基于参数的授权；授权应在工具实现、guardrail 或 approval 内完成，MCP server 自行授权（S3）。
- OpenAI `strict=true` 只承诺“函数调用可靠地符合 schema”，不承诺业务合法、资源存在、用户有权限（S1）。
- RFC 9110 的 `If-Match` 是 MUST NOT perform 的硬条件，不是“建议模型检查一下”（S17）。

一句话：**披露过滤是降噪和最小暴露的优化，可以近似；执行校验是正确性和安全的硬闸，必须精确、服务端、fail-closed。**

---

## 五、逐项审查对照清单

### A. 工具披露与状态

| 编号 | 审查点 | 通过标准 | 依据 | 边界 |
|---|---|---|---|---|
| A1 | 工具清单是否按业务状态/权限投影 | registry 接收 actor/tenant/resource_state/task_phase，返回可用工具；completed/无权限状态不出现对应工具 | S3、S14、S18 | 过滤不是授权；执行层仍要校验 |
| A2 | 初始工具数量与长尾披露 | 初始少于 20 个（软约束）；长尾用 namespace/tool_search；不一次暴露全量 | S1、S2 | tool_search 对单个 function 仍暴露名称/描述；真隐藏需 namespace/不注册 |
| A3 | 工具目录变更/缓存失效 | 有 catalog version/ETag；变更时刷新；未识别工具拒绝并刷新，不执行 | S2、S14 | `list_changed` 是通知，不是强一致；客户端可能短暂陈旧 |
| A4 | 前端工具生命周期/状态同步 | mount 注册、unmount 移除；`available` 随角色/状态；state 以服务端为准 | S18 | `available`/context 只是模型可见性，不是服务端授权 |
| A5 | 条件路由与未披露工具 | routing 读取持久化 state；未知/未披露工具返回 4xx 并刷新；不靠模型自述 | S5、S2 | 路由是控制流，不是校验；并行 next 可能并发写 |

### B. 启动、幂等与并发版本

| 编号 | 审查点 | 通过标准 | 依据 | 边界 |
|---|---|---|---|---|
| B1 | 逻辑任务/运行/尝试三层 ID | `task_id` 业务唯一；`run_id` 一次执行；`attempt` 重试；completed 终态不可再 start | S11 | 框架 ID 作用域/保留期不同 |
| B2 | start 幂等键与唯一约束 | 同一 `idempotency_key` 返回原 run；DB 唯一约束/条件更新；重复 start 不产生第二行 | S10、S11 | 下游是否 honor key 要单独验证 |
| B3 | start 与 retry 分离 | 不同 API/语义；retry 复用 `run_id`；SSE 重连只 resume，不 start | S15、S11 | 客户端自动重试可能不带 key；需服务端兜底 |
| B4 | 框架去重默认值 | 明确设置 `RejectDuplicate`/`Fail` 或应用等价；检查 retention 是否覆盖业务周期 | S11 | Temporal 默认 `AllowDuplicate`；closed 后可复用；不是永久全局去重 |
| B5 | 并发版本检查 | 更新带 `version`/ETag；条件更新 0 行 → 409/412；不覆盖他人修改 | S17 | 内部 RPC 需等价实现；HTTP 方法本身不足 |

### C. 预览与批准

| 编号 | 审查点 | 通过标准 | 依据 | 边界 |
|---|---|---|---|---|
| C1 | 预览快照字段 | 存 canonical args hash、resource_version、tool_version、policy_version、actor/tenant、expires_at、checkpoint/interrupt id | S4、S17 | 预览必须可复现；否则 hash 无意义 |
| C2 | approval token 绑定 | 服务端存 approval；客户端只传 opaque id + decision；不传替换 args | S4、S19 | possession of id 不是授权；需认证 reviewer |
| C3 | 执行时条件校验 | 重新读资源；hash/version 不匹配 → 409/412；不部分执行 | S17 | RFC 412 是 MAY，但 MUST NOT perform 是硬约束 |
| C4 | 冻结批准参数 | 执行 exactly approved payload；模型不得在批准后重生成参数 | S4 | 用户编辑参数 = 新提案，需重新批准 |
| C5 | 并发/重放/过期 | 原子消费 pending；同一 approval 只 resume 一次；过期拒绝；冲突显示 diff + re-preview | S4、S19 | sticky approval 可跨 agent/权限；用 per-call |

### D. 副作用、重试、恢复与错误交互

| 编号 | 审查点 | 通过标准 | 依据 | 边界 |
|---|---|---|---|---|
| D1 | 步骤拆分 | prepare（纯）/ commit（副作用）/ verify（只读）分离；commit 独立节点 | S10、S6 | 拆太细会增加 event history/延迟 |
| D2 | intent/ledger + key | 调用前写 intent；key 稳定；DB 唯一约束/outbox | S10、S8 | 下游必须 honor key；否则仍需对账 |
| D3 | 失败先对账 | 失败后按 key 查状态；applied → 成功；not applied → 可重试；unknown → 不盲重试 | S10、S12 | 需要 provider 查询接口/本地 ledger |
| D4 | 只重试安全步骤 | 只重试 retryable + 幂等/只读；commit 无 key 不自动重试 | S17、S3 | HTTP 幂等方法不等于业务幂等 |
| D5 | 重试预算/退避/截止 | max_attempts、deadline、backoff+jitter；非重试错误 fail fast | S12、S9 | 无上限重试会造成风暴；要可取消 |
| D6 | 自动恢复条件 | 瞬时故障 + 重放安全 + 无待决批准 + 无版本漂移 + 预算内 | S12、S13 | 不得绕过批准；状态变了要 re-plan |
| D7 | 错误信封/UI | category、retryable、retry_after、attempt、correlation_id、side_effect_state；UI 三态 | S12、S4 | unknown 不能显示“失败”；不要让用户点“重试”重复副作用 |
| D8 | 审计 | run/step/attempt/tool_call_id/approval_id 全链路 | S13、S6 | 注意 PII/密钥不入日志 |

### E. 安全硬闸

| 编号 | 审查点 | 通过标准 | 依据 | 边界 |
|---|---|---|---|---|
| E1 | 服务端输入校验 | schema + 业务规则 + 范围/枚举；所有工具入口统一 | S14 | strict mode 只保证 schema，不保证业务合法 |
| E2 | 授权 | 按 subject/resource/action 检查；tenant 隔离 | S14、S3 | `is_enabled`/`available` 不是授权 |
| E3 | 敏感操作人工确认 | 展示输入与影响；确认后执行；审批状态服务端 | S14、S4 | 审批不提供 sandbox；执行环境仍需隔离 |
| E4 | prompt/strict/annotations 不作为 gate | prompt 只做引导；strict 只做 schema；annotations 不可信 | S1、S3、S14 | 提示注入/幻觉仍可能调用；必须硬拒绝 |

**建议最小字段集**：`run(task_id, run_id, status, version, idempotency_key)`；`step(step_id, attempt, side_effect_state, last_error_class)`；`approval(approval_id, args_hash, resource_version, tool_version, policy_version, expires_at, actor)`；`event(event_id, seq)`。

**错误交互建议**：

| 场景 | 后端判定 | UI 应显示 | 不应显示 |
|---|---|---|---|
| 瞬时故障自动重试 | retryable=true，attempt<max | “正在自动恢复（2/3），约 5 秒” + 取消 | “失败” |
| 永久错误 | retryable=false，validation/auth/business | 具体原因 + 修改/联系 | “重试” |
| 版本冲突 | 409/412，version mismatch | “内容已更新，请查看差异并重新确认” | “重试” |
| 副作用未知 | side_effect_state=unknown | “已提交，正在核实结果” + 查询 | “重新提交” |
| 批准过期 | approval expired | “批准已过期，请重新预览/批准” | “继续” |
| 已完成 | status=completed | “已完成” + 查看结果 | “开始” |

---

## 六、适用边界与反例

1. **披露不等于隐藏，也不等于授权。** `tool_search` 对单个 function 仍暴露名称/描述；`is_enabled`/`available` 只控制可见性；服务端必须再校验（S2、S3、S18）。
2. **去重有作用域和保留期。** Temporal 的唯一性按 Namespace，且 `RejectDuplicate` 也受 retention 限制；默认 `AllowDuplicate` 允许 closed 后复用；不要把框架 ID 当作永久全局幂等键（S11）。
3. **at-least-once 不是 exactly-once。** Temporal 保证 Activity 被观察为完成一次，但可能执行多次；幂等键必须由下游真正 honor，否则平台无法替你消除重复副作用（S10、S13）。
4. **checkpoint 不是 exactly-once。** LangGraph resume 会重跑失败节点，replay 会重跑 checkpoint 之后的节点；`exit` durability 无法从进程崩溃中恢复；副作用必须幂等（S6、S7）。
5. **SSE 重连不是业务恢复。** EventSource 只恢复传输，`Last-Event-ID` 需要服务端 replay；规范不保证 exactly-once，可能重复；fatal 失败不再重连；fetch 没有内建重连，断流后是否重新请求由调用方决定（S15、S16）。
6. **审批快照是敏感状态。** `RunState` 包含参数和审批决定；应服务端保存，认证/授权 reviewer，原子消费；客户端传来的 run ID 不是授权；sticky approval 可能跨 agent/权限，建议 per-call（S4）。
7. **框架语义不同，借原则不借 API。** LangGraph 的 interrupt/checkpoint、Temporal 的 Workflow/Activity、OpenAI 的 tool_search/RunState 都是实现手段；核心是“逻辑 ID、版本、幂等、对账、硬闸”这些原则。
8. **模型是非确定性的，且可能并行调用。** `parallel_tool_calls=false` 可避免同一轮多个写操作；重试 LLM 可能生成不同计划；非确定性/副作用应放进可 checkpoint 的 task（S1、S8）。
9. **UI 的“重试”必须有语义。** 对非幂等 commit，先显示“核实结果”，不要直接“重新提交”；对版本冲突，显示 diff + 重新预览，不要盲目重试（S12、S17）。

---

## 七、待确认项

- `tools_condition` 与 `streamRetries` 的原文、版本和行为由另一 agent 核验；本报告未重复考证，也未把它们当作已确认事实。
- CopilotKit 文档对 LangGraph（Python）interrupt 的支持矩阵存在版本差异；落地前需按实际 `@copilotkit` 版本核验，不能只依赖文档页面。
- 本项目所用框架的默认值需单独审计：Temporal 的 `AllowDuplicate`、LangGraph 的 durability mode、OpenAI `tool_search` 的模型/API 可用性。
- 下游服务是否真正支持并 honor idempotency key、是否提供按 key 查询状态、SSE 服务端是否按 `Last-Event-ID` replay，都需要在集成合同中确认；规范本身不保证。

本次仅做外部只读检索；未改动工作区文件、服务、代码或文档，未读取或发送任何密钥，未调用真实模型。
