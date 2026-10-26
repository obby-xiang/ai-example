> 来源=agent-o30e5vky/output.log（2026-10-26 归档）· 外部研究（流式/取消/reasoning/消息操作/工具卡/可访问性一手对照）
> 性质：本次只读分析/调研的**脱敏副本**，属历史证据；为**分析稿**，结论以 `docs/research/` 总册与「规格复核补正」为准。仅做本机路径/仓库名/个人用户名占位与空白清理，**技术结论、计数、状态词一字未改**。

## 结论（先给判断）

- 本项目在**流式帧协议、取消、半截输出、工具卡、Markdown 安全渲染、复制反馈**上已经接近主流；就本次主题看，最明显的对照缺口集中在三处：**可访问性、错误重试语义、编辑重跑的后果告知**。
- 最值得直接纳入验收的普遍规则是：**停止 ≠ 断线**、**重试 ≠ 继续**、**编辑/重生成必须告知会丢弃多少后续内容**、**流式消息要有 aria-busy 与可访问名**、**reasoning 原文是否展示是合规策略而非框架默认**。
- `branch picker`、`thread list`、`reasoning 自动展开/耗时`、`对话导出`、`工具参数流式`、`AG-UI interrupt/resume` 等属于**某框架的可选能力**，不应直接当成项目必须项。
- 以下“原文规则”均来自我实际打开并读到正文的官方/一手页面；设计判断是我的归纳，已在文中标明。

---

## 一、一手来源核验（标题、URL、原文支持的规则）

> 引号内为原文摘句；中文括号内为我的转述。未用搜索摘要替代正文。

### 1. 流式、取消、重连、partial

- **AI SDK UI: Chatbot** — https://ai-sdk.dev/docs/ai-sdk-ui/chatbot
  - `status` 有四态：`submitted`、`streaming`、`ready`、`error`；官方建议用 `status` 决定 spinner、Stop 按钮、禁用发送。
  - 原文：*“When the user clicks the ‘Stop’ button, the fetch request will be aborted.”*
  - 原文：*“When the user clicks the ‘Regenerate’ button, the AI provider will regenerate the last message and replace the current one correspondingly.”*
  - 原文：*“We recommend showing a generic error message to the user, such as ‘Something went wrong.’ This is a good practice to avoid leaking information from the server.”*

- **Stopping Streams** — https://ai-sdk.dev/docs/advanced/stopping-streams
  - 原文：*“Stream abort functionality is not compatible with stream resumption. If you’re using `resume: true` in `useChat`, the abort functionality will break the resumption mechanism. Choose either abort or resume functionality, but not both.”*
  - 原文：*“`onAbort` is specifically called when a stream is aborted via `AbortSignal`.”*
  - 规则：客户端 `stop()` 只是中止 HTTP；要让服务端也停，必须把断连/信号传到模型请求。

- **Chatbot Resume Streams** — https://ai-sdk.dev/docs/ai-sdk-ui/chatbot-resume-streams
  - 原文：*“client-side aborts are treated as disconnects… `stop()` only closes the current HTTP connection and should not cancel the underlying generation.”*
  - 官方要求：增加**专用 stop 端点**，持久化 partial、取消实际工作、清掉 active stream。
  - 原文：*“DefaultChatTransport treats resumed streams as full replays by default… On the first `start` chunk, the client resets the interrupted response and rebuilds it from the replay.”* 若服务端只补差量，可设 `resumeStreamIsReplay: false`。
  - 原文：页面重新可见时会自动重连，但**仅限网络断连导致的中断**；健康流和其他错误不重连。

- **streamText 参考** — https://ai-sdk.dev/docs/reference/ai-sdk-core/stream-text
  - `finishReason` 包含 `length`、`content-filter`、`error`、`interrupted` 等。
  - 原文：*“A stream that ends cleanly without its provider completion signal can report ‘interrupted’. Partial output is preserved; interruption alone does not emit an error.”*

- **MDN: EventSource** — https://developer.mozilla.org/en-US/docs/Web/API/EventSource
  - 原文：*“When not used over HTTP/2, SSE suffers from a limitation to the maximum number of open connections… set to a very low number (6)… When using HTTP/2, the maximum number of simultaneous HTTP streams is negotiated… (defaults to 100).”*
  - 这直接关系到“多页签 + EventSource reattach”的部署前提。

### 2. Reasoning 状态、耗时、内容治理

- **AI Elements: Reasoning** — https://elements.ai-sdk.dev/components/reasoning
  - 原文：*“A collapsible component that displays AI reasoning content, automatically opening during streaming and closing when finished.”*
  - `useReasoning` 返回 `isStreaming`、`isOpen`、`setIsOpen`、`duration`。
  - 官方示例把多个 reasoning parts 合并，避免出现多个 “Thinking…” 指示器。

- **assistant-ui: Reasoning** — https://www.assistant-ui.com/elements/reasoning
  - 原文：*“a shimmering trigger while it streams, a scrollable trace once opened, and a plain collapsed row once the model moves on.”*
  - 原文：*“While `streaming` is true and the panel is open, the trace is a live, bottom-pinned preview: it autoscrolls to the newest tokens… only stops if the reader scrolls up manually…”*
  - 原文：*“When streaming ends, the open state returns to `defaultOpen`, unless the reader has already toggled the disclosure by hand at least once, at which point their choice sticks.”*
  - 关键：`duration` 只有在宿主提供 `timing: { startedAt, completedAt }` 时才显示；其他 runtime 可能完全不显示耗时。

- **AG-UI: Reasoning** — https://docs.ag-ui.com/concepts/reasoning
  - 原文：*“Surface reasoning signals (e.g., summaries) to users without exposing raw chain-of-thought.”*
  - 原文：*“Summary only: Emit a condensed summary while attaching detailed reasoning as encrypted values.”*
  - 原文：*“Provide user feedback: Even with encrypted reasoning, emit visible summaries so users know the agent is working.”*

- **OpenAI: Reasoning models** — https://developers.openai.com/api/docs/guides/reasoning
  - 原文：*“While we don’t expose the raw reasoning tokens emitted by the model, you can view a summary of the model’s reasoning using the `summary` parameter.”*
  - 在 `max_output_tokens` 截断示例中，官方先检查 `response.output_text`，再打印 `Partial output:`。

### 3. 消息操作、编辑、重生成、历史

- **AI Elements: Message** — https://elements.ai-sdk.dev/components/message
  - 原文：*“Action buttons for common operations (retry, like, dislike, copy, share) with tooltips and state management.”*
  - 原文：*“Branching is an advanced use case… While the AI SDK does not provide built-in branching support, you have full flexibility to design and manage multiple response paths.”*

- **assistant-ui: ActionBar** — https://www.assistant-ui.com/docs/primitives/action-bar
  - 原文：*“The ActionBar primitive provides message actions: copy, reload, edit, feedback, speech, and export.”*
  - 原文：*“Copy: disabled when there’s no copyable text content or an assistant message is still running.”*
  - 原文：*“Feedback buttons track submission state with `data-submitted` and expose it to assistive technology with `aria-pressed`.”*
  - 原文：*“After clicking Copy, the button gets a `data-copied` attribute for a configurable duration (default 3 seconds).”*
  - `ExportMarkdown` 可下载单条消息为 Markdown。

- **assistant-ui: Message Branching** — https://www.assistant-ui.com/docs/guides/branching
  - 原文：*“A new branch is created when: A user message is edited; An assistant message is reloaded…”*
  - 原文：*“`BranchPickerPrimitive.Previous` and `.Next` automatically disable at branch boundaries and while a run is in flight.”*

- **assistant-ui: Edit a sent message** — https://www.assistant-ui.com/elements/edit-message
  - 原文：*“a warning names how many replies sending the edit will throw away.”*
  - 原文：*“At runtime, editing does not delete anything: sending calls the message’s edit composer’s `send()`, which appends the rewrite as a new sibling of the original under the same parent… original message and everything under it stay in the thread’s history, reachable again through the branch picker’s `n / m` stepper.”*

- **assistant-ui: Message Editing** — https://www.assistant-ui.com/docs/guides/editing
  - 原文：*“Editing re-submits a message from a past point in the conversation and creates a new branch. The messages after the edited one are discarded…”*
  - 原文：*“Entering edit mode while the assistant is still generating a response does not cancel the in-progress run.”* 若产品要禁止，需自己用 `isRunning` 门控。

- **assistant-ui: Message Timing & Token Stats** — https://www.assistant-ui.com/docs/guides/message-timing
  - 原文：*“Display stream performance metrics (duration, tokens per second, time to first token).”*
  - 字段包括 `firstTokenTime`、`totalStreamTime`、`tokenCount`、`tokensPerSecond`；官方标注该能力 **experimental**，无 usage 时 token 数是按文本长度估算。

- **assistant-ui: ThreadList** — https://www.assistant-ui.com/docs/primitives/thread-list
  - 原文：*“listing, creating, switching, archiving, and deleting conversations.”*
  - `LoadMore` 在 adapter 的 `list()` 没有返回 `nextCursor` 时自动禁用。
  - 当前线程项设置 `aria-current="true"`，支持键盘上下键导航。

### 4. 工具卡与 HITL

- **AI Elements: Tool** — https://ai-sdk.dev/elements/components/tool
  - 原文：*“Support for multiple tool execution states (pending, running, completed, error).”*
  - `getStatusBadge` 还覆盖 `approval-requested`、`approval-responded`、`output-denied`。
  - 原文：*“Auto-opens completed tools by default for better UX.”* 错误态用 `errorText` 展示。

- **CopilotKit: useHumanInTheLoop** — https://docs.copilotkit.ai/reference/v2/hooks/useHumanInTheLoop
  - 原文：*“registers an interactive tool that pauses agent execution until the user responds through your custom UI.”*
  - 原文：*“internal status machine (`InProgress` -> `Executing` -> `Complete`)”*；`InProgress` 表示参数仍在流式到达，`Executing` 才拿到 `respond`。
  - 原文：*“The agent remains paused until `respond` is called with the user’s input.”* 且 `respond` 只应调用一次。

- **AG-UI: Events** — https://docs.ag-ui.com/concepts/events
  - 原文：*“The `RunStarted` and either `RunFinished` or `RunError` events are mandatory, forming the boundaries of an agent run.”*
  - 原文：`RunFinished` 可带 `outcome: { type: "interrupt", interrupts: [...] }`，表示“run paused for human input”；客户端通过**新 run + `resume` 数组**恢复。
  - 原文：`TextMessageStart → TextMessageContent(delta) → TextMessageEnd`；`ToolCallStart → ToolCallArgs(delta) → ToolCallEnd → ToolCallResult`。
  - `MessagesSnapshot` 用于初始化历史、断线后同步；官方还要求实现能容忍乱序到达。

### 5. 消息历史、可访问性、反馈

- **OpenAI ChatKit** — https://platform.openai.com/docs/guides/chatkit
  - 原文：*“Built-in response streaming”*；*“Tool and workflow integration for visualizing agentic actions and chain-of-thought reasoning”*；*“Thread and message management”*。

- **Advanced integrations with ChatKit** — https://developers.openai.com/api/docs/guides/custom-chatkit
  - 官方对比表把 **Response cancelling** 只勾在 self-hosted 一列；OpenAI-hosted 没有该能力。
  - 长工具可用 `ProgressUpdateEvent` 推进度；ChatKit 会用下一条 assistant 消息或 widget 输出替换进度事件。
  - `messages` 配置可开启 feedback、annotations 等；生命周期事件含 `chatkit.error`、`chatkit.response.start`、`chatkit.response.end`、`chatkit.thread.change`。

- **ChatKitEvents** — https://openai.github.io/chatkit-js/api/openai/chatkit/type-aliases/chatkitevents/
  - 事件表含 `chatkit.thread.load.start`、`chatkit.thread.load.end`、`chatkit.history.open`、`chatkit.history.close`，适合驱动历史加载骨架与“正在载入线程”状态。

- **MDN: ARIA log role** — https://developer.mozilla.org/en-US/docs/Web/Accessibility/ARIA/Reference/Roles/log_role
  - 原文：*“Examples include chat logs…”*
  - 原文：*“Elements with the role `log` have an implicit `aria-live` value of `polite`.”*
  - 原文：*“The `log` is required to have an accessible name. Use `aria-labelledby` if a visible label is present, otherwise use `aria-label`.”*

- **MDN: aria-live / aria-busy** — https://developer.mozilla.org/en-US/docs/Web/Accessibility/ARIA/Reference/Attributes/aria-live ；https://developer.mozilla.org/en-US/docs/Web/Accessibility/ARIA/Reference/Attributes/aria-busy
  - 原文：*“Use `aria-busy` to prevent announcements while updates are still being made.”*
  - 原文：*“set `aria-busy="true"` until loading is complete. Then set to `aria-busy="false"`. This prevents assistive technologies from announcing changes before updates are done.”*

- **Microsoft HAX: Guidelines for Human-AI Interaction** — https://www.microsoft.com/en-us/research/blog/guidelines-for-human-ai-interaction-design/
  - G9：*“Support efficient correction. Make it easy to edit, refine, or recover when the AI system is wrong.”*
  - G11：*“Make clear why the system did what it did.”*
  - G15：*“Encourage granular feedback.”*
  - G16：*“Convey the consequences of user actions. Immediately update or convey how user actions will impact future behaviors of the AI system.”*

- **Streamdown（Vercel 开源）** — https://github.com/vercel/streamdown
  - 原文：*“providing seamless formatting even with incomplete or unterminated Markdown blocks.”*
  - 特性：未闭合块解析、GFM、数学、Mermaid、代码高亮、`rehype-harden` 安全渲染、memoized rendering。它驱动 AI Elements 的 Message 组件。

---

## 二、主题 → 资料 → 设计点 → 是否适合本项目

> 资料列只放最核心的一手链接；“适合度”是我的归纳。
> **【普遍】** 跨框架/跨产品都出现，建议纳入验收；**【常见/可选】** 多数框架有，但可按产品取舍；**【框架专属】** 只是某框架能力，不必照搬；**【不适用】** 与本项目架构/产品不匹配。

| 主题 | 核心一手资料 | 应对照的设计点（我的归纳） | 适合本项目 |
|---|---|---|---|
| 流式状态与首包 | [AI SDK Chatbot](https://ai-sdk.dev/docs/ai-sdk-ui/chatbot)、[ChatKitEvents](https://openai.github.io/chatkit-js/api/openai/chatkit/type-aliases/chatkitevents/)、[assistant-ui Timing](https://www.assistant-ui.com/docs/guides/message-timing) | `submitted / streaming / ready / error` 四态；停止钮只在运行中出现；区分“已提交”和“首 token”；自动钉底 + 用户上滑后暂停跟随 + 回到底部 | **【普遍】** 状态机、滚动跟随；**【常见/可选】** TTFT、tok/s |
| 取消 vs 断线 vs 重连 | [Stopping Streams](https://ai-sdk.dev/docs/advanced/stopping-streams)、[Resume Streams](https://ai-sdk.dev/docs/ai-sdk-ui/chatbot-resume-streams)、[MDN EventSource](https://developer.mozilla.org/en-US/docs/Web/API/EventSource) | 停止是显式 cancel 端点，断线才可 resume；stop 后不得自动重连；全量 replay 还是差量 replay 要有明确口径；终态要清 active stream；EventSource 多页签有 6 连接上限（非 HTTP/2） | **【普遍】** stop/resume 分离、终态清理；**【进阶可选】** 差量 replay；**【部署核验】** HTTP/2 |
| partial 与终态 | [AI SDK streamText](https://ai-sdk.dev/docs/reference/ai-sdk-core/stream-text)、[OpenAI Reasoning](https://developers.openai.com/api/docs/guides/reasoning) | 半截正文要保留；区分 `length / content-filter / error / interrupted / cancelled`；干净中断不等于错误；UI 要有“已停止/已截断”标识 | **【普遍】** |
| Reasoning 状态与耗时 | [AI Elements Reasoning](https://elements.ai-sdk.dev/components/reasoning)、[assistant-ui Reasoning](https://www.assistant-ui.com/elements/reasoning) | 流式期有 shimmer/Thinking 指示；结束后可折叠；用户手动展开/收起后不被自动覆盖；耗时只在有真实 timing 时显示，不伪造 | **【普遍】** 不伪造耗时；**【常见/可选】** 自动展开、耗时、底部钉住预览 |
| Reasoning 内容治理 | [OpenAI Reasoning](https://developers.openai.com/api/docs/guides/reasoning)、[AG-UI Reasoning](https://docs.ag-ui.com/concepts/reasoning) | 主流是“摘要可见、原文不暴露”或“原文仅加密回传”；至少要有可见摘要让用户知道在思考；原始 CoT 涉及隐私/合规 | **【普遍】** 策略必须明确；**【可选】** 加密 reasoning、ZDR |
| 编辑 / 重跑 / 重新生成 | [AI SDK Chatbot](https://ai-sdk.dev/docs/ai-sdk-ui/chatbot)、[assistant-ui Branching](https://www.assistant-ui.com/docs/guides/branching)、[assistant-ui Edit](https://www.assistant-ui.com/elements/edit-message) | 编辑历史用户消息 → 从该点重跑；主流会提示“将丢弃 N 条回复”；重生成通常是替换最后一条 assistant；branch 可保留旧版本；运行中禁用 reload | **【普遍】** 后果提示、重试语义；**【常见/可选】** branch picker、版本切换 |
| 错误与反馈 | [AI SDK Chatbot](https://ai-sdk.dev/docs/ai-sdk-ui/chatbot)、[Microsoft HAX](https://www.microsoft.com/en-us/research/blog/guidelines-for-human-ai-interaction-design/)、[Pattern 16B](https://www.microsoft.com/en-us/haxtoolkit/pattern/g16-b-feedback-convey-the-consequences-of-user-actions-after-the-user-takes-action/) | 面向用户用通用文案，诊断码/详情分层；错误卡贴近出错位置；提供重试/改写/开新会话；反馈按钮用 `aria-pressed`；反馈失败要回滚并告知 | **【普遍】** |
| Markdown / 代码 / 复制 / 下载 | [Streamdown](https://github.com/vercel/streamdown)、[AI Elements Message](https://elements.ai-sdk.dev/components/message)、[assistant-ui ActionBar](https://www.assistant-ui.com/docs/primitives/action-bar)、[AI Elements Conversation](https://elements.ai-sdk.dev/components/conversation) | 未闭合围栏不能闪烁/破版；净化白名单；代码块复制；复制成功有 2–3s 状态；单条/整段导出 Markdown | **【普遍】** 安全渲染、复制反馈；**【可选】** Streamdown、整段导出 |
| 工具卡 / HITL | [AI Elements Tool](https://ai-sdk.dev/elements/components/tool)、[CopilotKit HITL](https://docs.copilotkit.ai/reference/v2/hooks/useHumanInTheLoop)、[AG-UI Events](https://docs.ag-ui.com/concepts/events) | 状态覆盖 pending/running/approval/complete/error/denied；审批中要暂停；参数流式到达可显示“准备参数”；结果只读回执；长任务可推进度 | **【普遍】** 状态与审批；**【可选】** 参数流式、进度、interrupt/resume |
| 消息历史 / 线程 | [ChatKit](https://platform.openai.com/docs/guides/chatkit)、[ChatKitEvents](https://openai.github.io/chatkit-js/api/openai/chatkit/type-aliases/chatkitevents/)、[assistant-ui ThreadList](https://www.assistant-ui.com/docs/primitives/thread-list)、[AG-UI MessagesSnapshot](https://docs.ag-ui.com/concepts/events) | 历史加载有 start/end 状态；断线后用 snapshot 重新同步；线程列表支持切换/归档/分页；当前线程 `aria-current` | **【普遍】** 加载状态、断线重同步；**【可选】** 线程列表/分页（单会话面板可不做） |
| 可访问性 | [MDN log role](https://developer.mozilla.org/en-US/docs/Web/Accessibility/ARIA/Reference/Roles/log_role)、[MDN aria-live](https://developer.mozilla.org/en-US/docs/Web/Accessibility/ARIA/Reference/Attributes/aria-live)、[MDN aria-busy](https://developer.mozilla.org/en-US/docs/Web/Accessibility/ARIA/Reference/Attributes/aria-busy) | `role=log` 必须有可访问名；流式/历史加载期 `aria-busy=true`；状态播报与消息正文分开；按钮用 `aria-pressed`/`aria-expanded`/`aria-current` | **【普遍】** 硬性 |

---

## 三、对 `<主仓>` 的审查落点

> 只列对照点与建议，不展开根因分析。代码位置均为只读定位。

| 审查点 | 代码定位 | 对照规则 | 建议 | 性质 |
|---|---|---|---|---|
| `role="log"` 没有可访问名 | [AiPanel.vue:148](<REPO_ROOT>/frontend/src/components/AiPanel/AiPanel.vue:148)；该元素只有 `role`、class、事件，无 `aria-label`/`aria-labelledby` | MDN：`log` **required to have an accessible name** | 给消息流容器加 `aria-label`（或 `aria-labelledby` 指向面板标题） | **高 / 普遍** |
| 流式期与历史加载期没有 `aria-busy` | 全文件检索未见 `aria-busy`；状态播报在 [AiPanel.vue:139](<REPO_ROOT>/frontend/src/components/AiPanel/AiPanel.vue:139) | MDN：更新未完成时用 `aria-busy` 阻止 AT 提前播报；完成后再置 false | 在 `role=log` 容器或流式消息上绑定 `aria-busy = ai.loading || !ai.historyLoaded`；状态仍走独立 `aria-live` 区域 | **高 / 普遍** |
| “继续”同时承担 retry 与 continue | [showContinue:3203](<REPO_ROOT>/frontend/src/components/AiPanel/AiPanel.vue:3203)、[continueRound:3219](<REPO_ROOT>/frontend/src/components/AiPanel/AiPanel.vue:3219)、[配额卡动作:1424](<REPO_ROOT>/frontend/src/components/AiPanel/AiPanel.vue:1424) | AI SDK：`regenerate` 是**重新处理最后一条消息并替换它**；错误重试与半截续写不是同一动作 | 拆成两个动作：**重试本轮**（截断失败 assistant，重发同一 user）与**继续**（仅 partial/interrupted/cancelled）；错误卡默认走“重试本轮” | **高 / 普遍** |
| 编辑重跑静默截断后续消息 | [编辑提交:3346](<REPO_ROOT>/frontend/src/components/AiPanel/AiPanel.vue:3346) 直接 `splice(cut + 1)`；[editMessage:3190](<REPO_ROOT>/frontend/src/components/AiPanel/AiPanel.vue:3190) 只在输入框非空时确认覆盖 | assistant-ui：发送编辑前要提示 **“sending discards N replies”**；runtime 还可保留旧分支 | 至少在截断前显示“将删除 N 条后续消息”的确认；若要保留历史，再考虑 branch/version | **高 / 普遍（提示）；可选（分支）** |
| reasoning 原文直接透传并展示 | [sse.ts:213](<REPO_ROOT>/frontend/src/types/sse.ts:213) 注明上游 `reasoning_content` 透传；[AiPanel.vue:294](<REPO_ROOT>/frontend/src/components/AiPanel/AiPanel.vue:294) 渲染折叠块 | OpenAI：raw reasoning tokens **不暴露**，只提供 summary；AG-UI：摘要可见、原文可加密回传 | 先做产品/合规决策：**展示摘要**、**脱敏后展示**还是**仅内部可见原文**；若继续展示原文，至少标注“模型原始思考，可能包含敏感信息”并做访问控制 | **高 / 需产品确认** |
| 错误卡直接展示后端原文 | [error-card.ts:1](<REPO_ROOT>/frontend/src/utils/error-card.ts:1) 的设计是 `detail` 逐字透传；渲染在 [AiPanel.vue:844](<REPO_ROOT>/frontend/src/components/AiPanel/AiPanel.vue:844) | AI SDK：面向用户建议通用文案，避免泄漏服务端信息；HAX G11：又要让用户能理解原因 | 两层：**用户向通用文案 + 机器码**；原始 `detail` 放“查看详情/诊断”折叠区，默认不展开 | **中 / 普遍** |
| 没有 assistant “重新生成”入口 | [操作行:772](<REPO_ROOT>/frontend/src/components/AiPanel/AiPanel.vue:772) 只有赞/踩/编辑/复制；无 reload/regenerate | AI SDK、assistant-ui、AI Elements 都把 retry/reload 作为消息操作标准项 | 对**无副作用**的轮次增加“重新生成”；有工具副作用的轮次禁用或要求确认，避免重复执行 | **中 / 常见但可选** |
| reasoning 没有 live 状态与真实耗时 | [reasoningSummary:3093](<REPO_ROOT>/frontend/src/components/AiPanel/AiPanel.vue:3093) 用字数代替耗时；[isBlockExpanded:2647](<REPO_ROOT>/frontend/src/components/AiPanel/AiPanel.vue:2647) 默认折叠 | AI Elements：流式期自动展开、结束后折叠；assistant-ui：shimmer + `duration`，但无 timing 就不显示 | 保持“不伪造耗时”是对的；可用帧里的 `atMs`/`message_start/end` 算真实耗时，显示“思考 Ns”；流式期加轻量 live 指示即可，自动展开可选 | **中 / 可选** |
| 历史加载失败静默回落本地镜像 | [ai.ts:1752](<REPO_ROOT>/frontend/src/stores/ai.ts:1752) catch 后直接 `historyLoaded = true` 并 return | HAX G11：用户应能理解系统为什么这样；ChatKit 有 thread.load start/end 事件 | 增加“本地缓存 / 未同步”提示与重试入口；至少不要让它看起来和权威历史完全一样 | **中 / 普遍** |
| EventSource reattach 与多页签连接上限 | [sse.ts:723](<REPO_ROOT>/frontend/src/api/sse.ts:723) 使用 `new EventSource(...)` | MDN：非 HTTP/2 时每浏览器+域名约 6 条 SSE 连接；HTTP/2 下协商，默认约 100 | 确认部署启用 HTTP/2；终态及时 `close()`；多页签场景评估是否改用 fetch 流式重挂 | **中 / 条件性** |
| 工具参数整体到达，没有“准备参数”状态 | [sse.ts:244](<REPO_ROOT>/frontend/src/types/sse.ts:244) `tool_start.args` 是完整字符串 | AG-UI 有 `ToolCallArgs(delta)`；AI Elements 有 pending/input-streaming | 若后端确实流式收到参数，可加“准备参数”轻状态；否则不必为了对齐而改协议 | **低 / 可选** |
| HITL 用长挂起流保活，而非 interrupt/resume | [sse.ts:392](<REPO_ROOT>/frontend/src/types/sse.ts:392) `suspended` + [sse.ts:409](<REPO_ROOT>/frontend/src/types/sse.ts:409) heartbeat | AG-UI：run 以 `interrupt` 结束，客户端用新 run + `resume` 恢复；CopilotKit：工具 promise 挂起等待 `respond` | 短确认门可保持现状；若人工等待可能很长，评估“结束本轮 + 恢复”模型，降低长连接/心跳成本 | **中 / 可选架构** |
| 只有 Mermaid SVG 下载，没有对话/消息导出 | [AiPanel.vue:1044](<REPO_ROOT>/frontend/src/components/AiPanel/AiPanel.vue:1044) 仅见 `downloadSvg`；全文件检索无对话导出 | assistant-ui `ExportMarkdown`；AI Elements `ConversationDownload` | 若产品有审计/取证/分享需求，增加“导出对话 Markdown”；否则可不做 | **低 / 可选** |

---

## 四、项目已有优势（不要误判为缺口）

- **显式取消链路**：`stop()` 本地封口、保留半截正文、立即写镜像，再 `POST /api/ai/cancel/{runId}`，并用 `stopped` 抑制断线重连误报（[ai.ts:4632](<REPO_ROOT>/frontend/src/stores/ai.ts:4632)）。这与 AI SDK“stop 要有专用端点、不要把 abort 当断线”的方向一致。
- **差量续收**：帧带 `seq`/`atMs`，reattach 用 `lastSeq` 只补差量（[sse.ts:166](<REPO_ROOT>/frontend/src/types/sse.ts:166)）。比 AI SDK 默认全量 replay 更省，但要求更强的幂等性。
- **内联错误卡**：按错误位置插入、按码分派、不顶部固定（[error-card.ts:1](<REPO_ROOT>/frontend/src/utils/error-card.ts:1)），与 HAX G11 的“解释原因”一致。
- **Markdown 安全链路**：markdown-it → DOMPurify 白名单 → v-html；未闭合围栏按纯文本降级；流式前缀缓存（[markdown.ts:1](<REPO_ROOT>/frontend/src/utils/markdown.ts:1)）。目标与 Streamdown 同类，不必为了“主流”换库。
- **工具门卡治理**：确认门、不可逆复选框、敏感值掩码（[AiPanel.vue:3104](<REPO_ROOT>/frontend/src/components/AiPanel/AiPanel.vue:3104)、[AiPanel.vue:557](<REPO_ROOT>/frontend/src/components/AiPanel/AiPanel.vue:557)）比多数通用组件更细。
- **可访问性细节**：反馈按钮 `aria-pressed`（[AiPanel.vue:778](<REPO_ROOT>/frontend/src/components/AiPanel/AiPanel.vue:778)）、代码复制按钮 `aria-label`（[markdown.ts:627](<REPO_ROOT>/frontend/src/utils/markdown.ts:627)）、独立状态播报区（[AiPanel.vue:139](<REPO_ROOT>/frontend/src/components/AiPanel/AiPanel.vue:139)）都已经做了。

---

## 五、怎么用这份对照做审查

1. **先按【普遍】项建验收矩阵**：状态机、stop/resume 分离、partial 标识、错误重试、编辑后果、工具审批、Markdown 安全、复制反馈、`role=log` + `aria-busy`。这些跨框架出现，适合作为硬指标。
2. **把【常见/可选】项做成产品决策**：是否要重新生成、是否要 branch、是否要显示 reasoning 耗时、是否要导出对话、是否要 thread list。不要因为某个框架有就判项目“缺”。
3. **把【框架专属】项只当参考**：AI Elements 的自动展开、assistant-ui 的 BranchPicker/ActionBar、ChatKit 的 widgets/self-hosted cancel、CopilotKit 的 HITL 状态机、AG-UI 的 interrupt/resume。它们描述的是“某框架的默认解”，不是行业强制。
4. **对代码看不见的行为做运行验证**：取消后服务端是否持久化 partial、跨实例归档是否一致、多页签 EventSource 是否触顶、上游返回的是原文还是摘要。这些不能从公开资料推断，也不能凭代码注释当成已验证事实。

---

## 六、边界声明

- **官方已证事实**：上文引号内的规则均来自对应官方/一手页面正文。
- **我的归纳**：主题表里的“应对照的设计点”、审查表里的“建议”、以及“适合度”判断，是我的设计分析，不是官方结论。
- **无法公开验证的产品内部行为**：本项目后端是否在 cancel 后持久化半截正文、跨实例归档/续跑的真实行为、上游实际返回的是原始 CoT 还是摘要、多页签下 EventSource 的真实连接数、以及产品是否真的需要多线程/导出——这些需要运行验证或产品决策，本报告不替它们下结论。
- 按要求**未复核** `streamRetries` 与 `tools_condition` 两个细节。
- 本次仅只读检索与阅读；**未修改任何文件/服务/代码/文档，未调用真实模型，未读取密钥**。
