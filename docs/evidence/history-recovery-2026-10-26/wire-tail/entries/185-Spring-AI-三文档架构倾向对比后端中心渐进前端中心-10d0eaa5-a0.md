> **历史分析 / 过程证据 — 非现行基线，非验收结论**
>
> - 归档：2026-10-26 · `docs/evidence/history-recovery-2026-10-26/wire-tail/`
> - 来源：Kimi Code 会话 `<KIMI_SESSION>` / 子 agent `agent-0` 的 wire 末条 assistant 文本（消息序号 m=3；只读流式解析，非原始会话转写）
> - 历史时点：2026-10-05 14:11 → 2026-10-05 14:14（wire 事件时间窗，UTC+8）；当时模型：`Kimi/kimi-k3`
> - 原基线：正文未出现基线哈希，未经本批复验
> - 原始体量：12607 B / 6879 字符 / 87 行；行指纹覆盖率（本批复算）1.6%（最高单文件 2%：`docs/任务看板.md`）
> - 类别：**同源分支附录**
> - 与既有件关系：项目起源期「生成 ai-example-{模型名}」任务；非主仓
> - 脱敏：路径 0 / 会话标识 0 / 身份 0 / 密钥 0 / 命名纪律 0
> - 正文为该条消息的完整文本，未删段、未改写结论；仅脱敏、占位符归一与行尾空白规整。当时结论不代表当前代码状态，不得作为当前缺陷或有效裁决引用。
三份文档已全部读完。以下是精炼的技术摘要，按你要求的设计决策要点组织。

# 技术摘要：Spring Boot + Spring AI 后端 Runtime + 前端 AI 侧边栏架构讨论

## 一、三份文档的核心立场与架构模式结论

三份文档讨论的是**同一组问题**，但得出了**明显不同的架构倾向**：

| 文档 | 核心立场 | 推荐模式 |
|---|---|---|
| **ChatGPT**（智能模式比较） | 后端中心：Spring AI 是工具箱，不是 Agent 框架；复杂场景需要自建 Agent Runtime | Plan-and-Execute + Workflow + ReAct Executor + HITL；Task-centric 模型 |
| **DeepSeek**（模式解析） | 渐进式：简单场景 Spring AI 工具循环即可，复杂场景自建或用工作流引擎 | 默认工具循环（隐式 ReAct）；HITL 用 Redis 轻量状态机或 Flowable |
| **Kimi**（AI 侧边栏架构） | 前端中心：前端重交互场景应把 AI Runtime 放前端，后端只做 OpenAI 规范网关 | 前端 AI Runtime（Vercel AI SDK 思路）+ 配置驱动任务引擎 |

**前后端 AI Runtime 对比的关键结论**（Kimi 文档最系统）：

- **前端 AI Runtime 适合**：工具主要操作前端 UI 状态（高亮、筛选、表单、Excel 编辑）、需要用户确认/弹窗交互、追求最低首 token 延迟、简单线性工具循环（<5 个工具）。
- **后端 AI Runtime 适合**：复杂 RAG、跨系统 API 编排、长时任务/跨天审批/崩溃恢复、敏感操作（转账、删除、审批）。
- **业界主流不是二选一，而是分层**：前端管流式 UI、对话状态、UI 类工具；后端管业务工具、复杂编排、鉴权限流。Kimi 给出的行业占比估计：后端全栈编排 ~55%，前后端混合 ~35%（增长最快），纯前端 ~10%。
- **决定性判据**：如果 AI 操作的对象主要是前端 UI 状态，后端编排就是"绕远路"（每次操作多 200-500ms 网络往返）；反之如果主要是后端 API，前端 Runtime 则力不从心。

## 二、Spring AI 具体用法建议

### 2.1 ChatClient/ChatModel 与 Agent 模式
- **Spring AI 不是 ReAct 框架**，它提供的是 ChatClient、Tool Calling、Advisor、Memory、RAG、MCP 等基础能力。默认的 `ChatClient + tools()` 工具调用循环（模型产出 ToolCall → 框架执行 → 结果回填 → 再调模型）本质是**隐式 ReAct**，但不强制模型输出显式 Thought 文本。
- **Plan-and-Execute 可以做，但无一键实现**：需自己实现 Planner（一次 LLM 调用输出 JSON 步骤计划）+ Executor（逐步执行、维护上下文），每个 Executor 内部可再用 ReAct。对 ToB 多步骤任务，ChatGPT 明确推荐 Plan-and-Execute + HITL 而非纯 ReAct。
- **关键架构边界**：Spring AI 负责"AI Loop"（ChatModel/Tool Calling/Memory），不负责"任务怎么跑"。Plan → Step → Pause → 用户操作 → Resume → Re-plan 这种业务级 Agent Runtime 要自己设计，不要把 UI_RENDER、审批、任务持久化全塞进 ChatClient。

### 2.2 Tool Calling 注册与回调机制 / HITL 插入点
- **Spring AI 2.0 的重要能力**：Tool Calling Loop 已做成 `ToolCallingAdvisor`，官方支持**关闭自动 Tool Loop、由应用自己掌控每轮 Tool 执行**（通过 `ToolCallingManager`）——这正是插入 HITL 的位置：模型产出 ToolCall → Agent Runtime 判断是否需审批 → 不需要则 `ToolCallingManager.execute()`，需要则挂起为 WAITING 状态。
- **HITL 不应只设计成 Tool Approval**，推荐统一抽象为 **Agent Interrupt**，类型包括：`HUMAN_APPROVAL`、`HUMAN_INPUT`、`HUMAN_SELECTION`、`FRONTEND_ACTION`（前端执行）。Task 状态保持粗粒度（PLANNING/EXECUTING/WAITING/COMPLETED/FAILED/CANCELLED），等待原因由 Interrupt 承载，避免状态爆炸。
- **工具内同步阻塞等待审批是反模式**（DeepSeek 明确反例）：阻塞线程、无法处理超时/重启，仅适合原型。

### 2.3 对话记忆 / 会话管理
- **核心 ID 分层**（三份文档共识）：
  - `userId` = 谁；`agentSessionId`/`conversationId` = 这个窗口/这次对话的 AI 上下文；`taskId` = AI 正在完成的业务任务。
  - **Conversation 是"说了什么"，Task 是"正在完成什么"，WorkspaceState 是"现在是什么"**——三者不要混。
- **多窗口/多 Tab 隔离方案**：前端用 `sessionStorage` 存 `agentSessionId`（刷新保留、新 Tab 独立、关 Tab 消失），后端 Redis 存 `agent:session:{id}`。刷新时凭 sessionId 精确恢复；**新窗口绝不能仅凭 userId 从 Redis 多个 Session 中猜一个恢复**（1:N 关系无可靠反推）。关闭窗口后默认新建 Session，旧 Session 设 TTL 自然过期；若要"找回"，应通过业务任务入口（taskId → 关联 Session）而非历史对话列表。
- **坑**：`window.open()` 打开的新页面可能继承 opener 的 sessionStorage 副本，不能盲信"sessionStorage 天然隔离"；AI 正在执行时即使浏览器断开也不能删 Session（RUNNING 状态不设短 TTL）；**页面刷新/跳转不应触发 Agent Resume**，只应 Load Task + Render State，等用户真正提交回答才 Resume。

### 2.4 SSE 流式输出
- **SSE 是 AI 流式的默认选择**：单向、标准 HTTP、浏览器原生自动重连、负载均衡/CDN 友好。仅在需要双向控制（中断生成、工具审批中途交互、多人协同）时才上 WebSocket。
- **Spring 侧**：WebFlux `Flux<ServerSentEvent<String>>`，`produces = TEXT_EVENT_STREAM_VALUE`。
- **必踩的坑**：**Nginx/代理必须关缓冲**（`proxy_buffering off`、`proxy_cache off`），否则流式变批式；前端用原生 `fetch + ReadableStream` 解析 SSE（比 EventSource 灵活，支持 POST 和自定义 Header）。

## 三、用户操作与 AI 对话状态同步（核心分歧点，含结论演化）

这是三份文档反复拉锯的问题，最终收敛的共识是：

1. **不要把整个页面状态持续同步给 AI**（上下文膨胀 + 状态抖动）。
2. **CopilotKit Shared State 的正解**（ChatGPT 精读官方文档后的修正）：Shared State ≠ 页面状态同步，而是"Agent 与 UI 共同操作的运行时工作状态"（AI 任务进度、AI 生成结果、共享工作数据），双向；而"当前页面/当前对象/当前选中项"这类 UI 拥有、AI 只读的数据，应用 **Agent Context（单向 UI→Agent，请求时注入）**，官方明确区分两者。
3. **最终推荐模型**：`AgentSession 隔离对话 + 请求时 Context 注入（当前页面/业务对象/选中内容/可用工具）+ 显式引用（Copilot 式 @引用）+ 需要双向协作的工作状态才用 Shared State + Tool Calling 执行操作`。
4. 四层状态要分清：**页面状态**（Vue 内部，表单输入、弹窗）→ **Agent Context**（UI→AI 单向）→ **Shared State**（双向）→ **业务数据**（DB，走 Tool 查询，不要塞进 Agent State）。
5. 若做双向同步（DeepSeek 方案）：Redis 存状态 + WebSocket/STOMP 推送 + 版本号乐观锁防多客户端并发覆盖；AI 执行工具前校验 stateVersion 是否变化，变化则停止/Replan。
6. Kimi 的补充视角：AI 修改业务页面应走**结构化 Action JSON**（`{type: 'SELECT_ROWS', payload: {...}}`）+ 前端统一 `executeAIAction` 分发器，而非自由文本。

## 四、不同页面/上下文提供不同工具（渐进式披露）

三份文档给出的可落地机制（按推荐度排序）：

1. **页面级工具注册（最简单，Kimi 最终推荐）**：每个页面 `onMounted` 时向 AI Store 注册自己的工具集，路由切换即换工具。AI 永远只调用当前页工具。跨页 = 用户手动跳转后重新对话。
2. **动态激活（Vercel AI SDK `prepareStep` + `activeTools`）**：注册全量工具，每步根据当前页面/工作流步骤动态决定模型可见的工具子集。Spring AI 侧对应思路是按会话/场景动态组装传入 `tools()` 的集合。
3. **元工具切换**：提供 `switchContext`/`navigateTo` 元工具，模型需要其他页面能力时显式切换上下文，切换后工具集随之更新。
4. **动态工具发现（Anthropic deferred loading 模式）**：全量工具标记 deferred，模型初始只看到 `searchTools` + `getCurrentContext` 原语，需要时语义搜索发现工具——适合工具数量大（>15）的场景。
5. **场景隔离三层防御**（防 AI 越界调用其他场景的工具）：① 只传当前场景最小工具集；② System Prompt 明确声明当前场景、可用工具、禁止事项；③ 工具执行函数内兜底校验 `wizardType`，不匹配则拒绝执行并返回明确错误（"该工具在当前页面不可用"）。

**重要反面结论**（Kimi 多轮确认）：业界主流**不做**"AI 一句话自动跨多页面连续执行"。技术上可行（System Prompt 注入完整流程计划 + 全量工具 + maxSteps + navigateTo 工具等待页面就绪），但存在用户失控感、前端渲染竞态（页面就绪无可靠信号）、出错难恢复、多步幻觉级联风险。GitHub Copilot 数据显示多步连续自动执行使用率仅 1%。正确姿势：**AI 开门 + 预填，用户逐页确认（Wizard 模式）；或后端静默编排 API、前端不动**。

## 五、坑、注意事项、版本相关 API 细节

**Spring 生态组件状态（重要，两文档互相印证/纠偏）**：
- **Spring Statemachine 已死**：2026-07-05 GitHub 仓库归档进 spring-attic，README 明确 "no longer maintained"，Boot 4 支持标记 "Closed as not planned"。**新项目不要引入**（DeepSeek 最初推荐后被用户纠正，ChatGPT 也修正过自己的建议）。
- **没有现成的 Spring 官方组件能覆盖 Agent Runtime + HITL + Pause/Resume**。推荐组合：**Spring AI + Spring Integration（仍活跃维护，Redis MessageStore 成熟）+ Spring Data Redis**，外加一层很薄的自建 Agent Runtime（核心接口可以只有 `run/resume/cancel`，200-500 行）。
- **状态管理降级路径**：简单场景 enum + Service 控制状态转换即可；复杂持久化执行需求（宕机恢复、重试、超时、补偿）才上 Temporal/Flowable/Camunda，这和"找状态机框架"是两个层次的问题。
- **Redis 方案要点**：状态转换必须原子化（Lua 脚本 / WATCH+MULTI / CAS：`只有 WAITING 才允许变成 EXECUTING`），防重复点击、多节点重复 Resume；Redis Hash 存状态、Stream 存事件、ZSet 做审批超时延迟队列（比键空间通知可靠，不依赖 Redis 配置）、分布式锁协调 Worker；Redis 只做运行时状态，**DB 才是永久存储**（Task/ToolCall/Audit）。

**Vercel AI SDK 版本细节（若前端参考其实现）**：
- `ai@6.0.221`（SDK 6.x）：有 `prepareStep`、`activeTools`、`ToolLoopAgent`、`maxSteps`——够覆盖 80% 前端工作流需求。
- SDK 7 才有：`stopWhen`（替代 maxSteps）、`runtimeContext`/`toolsContext`（替代 experimental_context）、`WorkflowAgent`、durable `needsApproval` 审批。
- **`WorkflowAgent`/`DurableAgent`/checkpointing/Pause-Resume 深度绑定 Vercel Workflow DevKit 平台**，Vue + Spring 场景用不了，需用 Pinia + localStorage/IndexedDB 自实现等价能力。

**概念澄清（避免设计跑偏）**：
- **Wizard UI ≠ AI Workflow**：用户逐页点 Next 是 Wizard（AI 只是预填助手）；AI 自动编排 API 才是 Workflow。用户主动填表单 ≠ HITL；AI 自动执行中主动暂停等人输入才是 HITL。
- **并发/边界场景清单**：Wizard 全局操作锁（同时只一个流程）、Pinia 持久化防刷新丢失、BroadcastChannel 多标签页同步锁、后端任务超时自动取消、执行按钮防重 + 后端幂等（idempotency-key）、预填数据可能过期需步骤加载时校验。
- **建议先做最小 Demo 验证三件事**（ChatGPT/DeepSeek 共识）：① 用户改页面 → AI 感知；② AI 改状态 → 页面自动更新；③ HITL（AI 询问 → 挂起 → 用户确认 → Resume → 执行后端 Tool）。跑通后再扩展。

**三方结论的最大分歧点提醒**：ChatGPT 文档假设"后端 Spring AI 持有 AI 逻辑、自建 Agent Runtime"；Kimi 文档则论证"前端重交互场景应前端持有 AI Runtime、Spring 只做透传网关"。**设计决策前需先确认：你的 AI 工具调用对象主要是前端 UI 状态还是后端业务 API**——这一个问题的答案直接决定采用哪份文档的主架构，另一份的方案则作为补充层。
