> 来源=agent-a2vuwrrk/output.log（2026-10-26 归档）· 外部研究（六条外部 AI 说法的逐条核验与本仓对照）
> 性质：本次只读分析/调研的**脱敏副本**，属历史证据；为**分析稿**，结论以 `docs/research/` 总册与「规格复核补正」为准。仅做本机路径/仓库名/个人用户名占位与空白清理，**技术结论、计数、状态词一字未改**。

## 结论速览

| 待核验说法 | 关键证据（一手） | 判定 | 对本项目可借鉴的安全边界 |
|---|---|---|---|
| 1. “OpenAI SDK 标准生产模式 = 断流后把 partial assistant 放回 messages 续写，前端无缝拼接，指数退避通常 3 次” | OpenAI 官方明确反对“消费输出后自动重放”；SDK 默认只重试 **2 次**（共 3 次尝试），且**流式消费不自动重试**。官方“续传”机制是 Responses API 的 `background` + `starting_after` 游标，而非回填 messages | **不成立**（作为“标准模式”）；“指数退避”方向成立，次数说法无依据 | 把“未产出内容才重试”当默认；要续传就用服务端游标或幂等键，不要重放含工具副作用的请求 |
| 2. “AI SDK 7 于 2026-10 发布 `streamRetries`…issue #2636 说明此前是 bug” | 参数名与语义基本对；但 v7 发布于 **2026-06-25**，`streamRetries` 在 **ai@7.0.91（2026-09-02）** 才加入；#2636 是 2024 年的 “retry strategies and fallbacks”（仍 open），与断流 bug 无关 | **部分成立**（语义对，版本/日期/issue 全错） | 只在“工具/审批/客户端执行尚未发生”时重跑 step；客户端必须能丢弃失败尝试的可见片段；provider 侧工具必须幂等 |
| 3. “ChatGPT 断流只自动等待/恢复，不让用户重试” | 无任何 OpenAI 一手文档；只有社区用户报告出现 “Connection interrupted. Waiting for the complete answer”，且 ChatGPT 常规 UI 本身有 Regenerate | **不可证** | 不把未证的产品行为写进设计依据；需要的话按“停住 + 明示状态 + 用户可重试/续跑”自定 |
| 4. “LangGraph `tools_condition` 就是按任务生命周期状态过滤工具，COMPLETED 不暴露 `start_*`” | 源码 5 行：最后一条 `AIMessage` 有 `tool_calls` → `"tools"`，否则 `"__end__"`。不读业务状态、不过滤工具列表 | **不成立** | 状态化裁剪要用节点/中间件 `request.override(tools=...)`；不要把“状态机能实现”说成“这个函数会” |
| 5. “`!emitted && !sideEffect` 使重试几乎永远不触发，2 次实际仅 1 次” | 五条件在 `:833`；`emitted` 是**本次 attempt** 的累计（每 attempt 新建 `Accumulator`）；测试证明 500/IO/429/仅 `tool_calls` 无产出都会真的发第 2 次 `stream()` | **不成立**（“几乎不触发”）；“2 次 = 最多 1 次重试”是算术事实但归因错 | 保留 `!emitted && !sideEffect`；若要扩大，只考虑“仅 reasoning、无工具、无可见正文”的窄情形，并接受重复思考 |
| 6. “本项目只按页面而非状态披露工具” | 披露标签除 `page:*` 还有 `task:type`、`task:type/step`、`task:*`、`ui:on`；但**确实没有任务状态闸**（`taskStatus` 根本不进上下文）。状态闸在执行面：preflight、域层终态守卫、前端只读守卫 | **部分成立**（“只按页面”不准；“无状态披露闸”属实） | 披露面可继续不加 status；执行面保持“预检 + 域层守卫 + 零副作用失败”比“让它看不见”更可靠 |

---

## 1) OpenAI SDK 的“partial 回填 + 续写”不是官方标准模式

三条一手事实：

- OpenAI 官方 Rate limits 指南（Error mitigation 末段）写得最直白：*“For streaming requests, these HTTP error responses apply before the stream starts. An error after streaming begins can arrive as a stream event; **don't automatically replay a request after consuming output**.”* —— [Rate limits](https://platform.openai.com/docs/guides/rate-limits)
- Python SDK README：*“Certain errors are automatically retried **2 times by default**, with a short exponential backoff… Requests are retried only when their body can be safely resent.”* 以及 *“**Stream consumption is not automatically retried**, because replaying a request could duplicate output already delivered to your application.”* —— [openai-python · Retries](https://github.com/openai/openai-python#retries)
- Node SDK 同口径：`maxRetries` 默认 `2` —— [openai-node · Retries](https://github.com/openai/openai-node#retries)

所以：

- **“指数退避通常 3 次”没有官方依据。** SDK 默认是 **2 次重试 = 最多 3 次尝试**；如果原话指“重试 3 次”，与官方默认不符。OpenAI 文档只要求“同时限制次数与总时长”，示例里甚至是 `stop_after_attempt(6)`，没有任何“3”的推荐值。
- **官方确实提供“断点续传”，但不是回填 messages。** Responses API 的 background 模式配合 `sequence_number` 游标：`GET /v1/responses/{id}?stream=true&starting_after=42` 从断点继续同一个 response —— [Background mode](https://developers.openai.com/api/docs/guides/background)、[Retrieve a response（`starting_after`）](https://developers.openai.com/api/reference/resources/responses/methods/retrieve/)。这是“同一份生成继续流”，不重跑模型、不重放已有字节，和“把 partial assistant 塞回 messages 再问一遍”是两件不同的事。
- **工具调用轮更不适用。** 一是 SDK 重试的是 HTTP 请求体，不会替你重放工具执行；一旦工具已执行，重放就是重复副作用（官方原话就是“不要自动重放”）。二是协议层面，一条 assistant 消息以 `tool_calls` 结尾时，后面必须跟对应的 `tool` 结果消息，把“半截 tool_calls”直接放回 `messages` 本身就是非法历史。因此“partial 回填续写”最多适用于**纯文本、且未产生任何工具副作用**的场景。

## 2) `streamRetries`：语义大半属实，版本、日期、issue 引用全错

**参数名与语义（官方文档，属实）** —— [streamText 参考](https://ai-sdk.dev/docs/reference/ai-sdk-core/stream-text)、[Error Handling](https://ai-sdk.dev/docs/ai-sdk-core/error-handling)：

- `maxRetries`：只管“开始一次 model call”阶段的失败，**默认 2**。
- `streamRetries`：管“响应已开始流式输出后收到的 provider error event”，**默认 0（不启用）**；设为 `0` 时仍允许 `onError` 返回 `{ retry: true }` 触发**一次**回调式重试；自动重试耗尽后 `onError` 还能再要一次，总恢复次数上界 `streamRetries + 1`。
- 语义：*“Retries rerun only the current model step… Earlier completed steps, including their tool calls and tool results, are not replayed.”*
- 工具边界：*“Tool input, tool calls, approval requests, tool callbacks, and client-side tool execution from a failed attempt are discarded. They are only exposed or executed after an attempt reaches a successful model-call finish.”* 但 *“it cannot undo work already performed by provider-executed tools. Retrying a step after provider-side work may repeat that work or its cost. Use idempotency controls…”*

**版本与日期（说法错）**：

- `ai@7.0.0` 发布于 **2026-06-25**（[Vercel 发布博客](https://vercel.com/blog/ai-sdk-7)，npm 时间戳一致），不是 2026-10。
- `streamRetries` 的 changeset 落在 **7.0.91**：*“Add configurable recovery for provider errors received after `streamText` response streaming begins…”* —— [packages/ai/CHANGELOG.md](https://github.com/vercel/ai/blob/main/packages/ai/CHANGELOG.md)；npm 上 `7.0.91` 的发布时间是 **2026-09-02**。
- 对应 PR 是 [#18718 “feat: add configurable, attempt-isolated recovery for mid-stream provider failures”](https://github.com/vercel/ai/pull/18718)（2026-09-02 合并，Fixes #18668）；提出问题的 issue 是 [#18667 “Mid-stream provider errors bypass maxRetries and are unclassifiable, discarding a completed generation”](https://github.com/vercel/ai/issues/18667)。

**#2636 的引用是虚构的**：[issue #2636](https://github.com/vercel/ai/issues/2636) 标题是 “retry strategies and fallbacks”，2024-08-12 创建、至今 open，内容是希望支持 fallback 模型/供应商，与“此前断流不重试是 bug”无关。

**“是否必须客户端支持 replace/invalidation”**：官方没有 replace/invalidation 原语，反而明确说不能撤回：

> “Non-tool output emitted before a provider error **cannot be retracted**. A retried model step may therefore **append repeated or divergent partial text, reasoning, files, or sources** to consumer streams. Open text and reasoning parts are ended before recovered output begins so UI consumers do not retain them in a streaming state.”

源码层面也是“关掉旧 part、用新 part 继续”：重试边界会 `textPartIds.clear()` / `reasoningPartIds.clear()`（[stream-text.ts](https://github.com/vercel/ai/blob/main/packages/ai/src/generate-text/stream-text.ts)），也就是 UI 侧会看到“前一段结束 + 新一段开始”。因此：

- **“前端无缝拼接”不是免费得到的**：失败尝试已经流出去的字节仍在用户屏幕上；要无缝，客户端必须自己按 part id/attempt 标记丢弃或回滚失败片段。逻辑结果（`result.text`、结构化输出、response messages、后续 step 上下文）只取成功尝试，这点 SDK 做了；**显示层没做**。
- 另一个容易忽略的限定：官方措辞是“**well-formed provider error events**”，源码的触发点是流分片 `type === 'error'`；裸 TCP 断开是否走到这条路径，取决于 provider 适配器有没有把它转成 error 事件——不能理解成“任意断流都必然重试”。

## 3) ChatGPT“只自动等待、不让重试”：没有一手证据

- 没有任何 OpenAI 官方文档描述 ChatGPT 断流后的恢复策略。
- 能查到的只有用户侧报告：社区帖与 `openai/codex` 仓库 issue 里反复出现 “Connection interrupted. Waiting for the complete answer”（如 [#51973](https://github.com/openai/codex/issues/51973)、[社区帖](https://community.openai.com/t/is-anyone-else-experiencing-recurring-chatgpt-loading-failures-stream-errors-and-stuck-tasks/1404071)）。这只能证明“存在等待/恢复的提示”，不能证明“没有重试入口”。
- 而且 ChatGPT 常规 UI 一直有 Regenerate；把它说成“只自动等待、不让用户重试”至少是过度概括。结论：**不可证**，不应作为设计依据。

## 4) `tools_condition` 是路由，不是状态过滤器

官方 API 参考的措辞：*“if the last `AIMessage` contains tool calls, route to the tool execution node; otherwise, end the workflow”* —— [tools_condition](https://reference.langchain.com/python/langgraph.prebuilt/tool_node/tools_condition)。

源码（`langgraph_prebuilt` 1.1.1 / 主分支 `libs/prebuilt/langgraph/prebuilt/tool_node.py`）实现就是：

```python
if hasattr(ai_message, "tool_calls") and len(ai_message.tool_calls) > 0:
    return "tools"
return "__end__"
```

它**不看**任务生命周期、不看业务 status、也**不改工具列表**；“COMPLETED 不暴露 `start_*`”在这个函数里没有任何对应逻辑。真正能做动态裁剪的是另一套机制：中间件 `wrap_model_call` 里 `request.override(tools=relevant_tools)`，官方文档明确写了 “Select relevant tools based on state/context” —— [Custom middleware · Dynamically selecting tools](https://docs.langchain.com/oss/python/langchain/middleware/custom)。

所以正确表述是：“LangGraph 的状态图/中间件**能实现**按状态裁剪工具”，而不是“`tools_condition` 就会按状态过滤”。

## 5) 本项目的重试门：不是“几乎不触发”，而是“只在产出前触发”

关键代码（`<REPO_ROOT>/backend/src/main/java/com/example/configmgr/ai/run/ResilientChatService.java`）：

- 五条件在 `:833`：`outcome.retryable() && !emitted && !sideEffect && hasNext && !overBudget`。
- `emitted` 来自**本次 attempt 新建的** `Accumulator`（`:770`），是“本次尝试”而非“整轮”：`emittedAny()` = 正文或思考非空（`:2285-2289`，思考也算）。
- `sideEffect` = 本次尝试内工具**开始执行**（`ToolActivityBeacon.enter` 在开始时自增，`:94-99`）或台账新增。
- `max-attempts: 2`（`application.yml:229`）语义是“总尝试次数 2”= 最多 1 次重试；`first-byte-timeout: 30s`、`total-budget: 300s` 是另两道闸。

逐情形（与测试一一对应，`UpstreamErrorRetryPolicyTest.java`）：

| 失败形态 | 是否重试 | 证据 |
|---|---|---|
| 连接错误 / 500 / 429（首字节前） | 会，发第 2 次 `stream()` | `:170-196`（`verify(..., times(2))`） |
| 首包超时 / 包间静默、尚无正文 | 会 | `brokenStream()` 含 `FIRST_BYTE_TIMEOUT`/`SILENT_TIMEOUT`（`StreamViolationException:79-85`） |
| 缺终帧、且只收到 `tool_calls`、无正文 | 会 | `:285-297` |
| 已发正文/思考后才断 | 不重试，直接 `error{...AFTER_PARTIAL}` | `:300-311`；`failureCode` 在 `:2737-2738` |
| 工具已开始执行后断 | 不重试，`...AFTER_TOOL_SIDE_EFFECT` | `:828-829` |
| 挂起工具轮 | 不重试、不判失败，交回 resume | `:843-846` |
| 401/403/4xx/上下文超窗/未定性异常 | 不重试 | `UpstreamErrorClassifier:99-128, 148-163` |

所以“`!emitted && !sideEffect` 使重试几乎永远不触发”是**夸大**：恰恰是“最典型的瞬时故障”（连不上、429、5xx、首包超时）全部落在可重试区。说法里唯一成立的是算术：“配置 2 次 = 最多 1 次重试”。真正值得记录的窄点是：**reasoning 一吐就算“已产出”**，推理模型几乎在第一个 token 就失去重试资格——这是为避免重复思考段的有意取舍，不是缺陷。

## 6) 披露面与状态闸：确实没有“状态披露”，但不是“只按页面”

**披露面（`ToolRegistry`）**：

- `forContext` = `scopeMatches AND sheetAllows`（`ToolRegistry.java:317-327`），未登记元数据的工具按 `*` 全量放行。
- `buildTags` 产出：`*`、`page:<page>`、`task:*`、`task:<TYPE>`、`task:<TYPE>/<STEP>`、`ui:on`（`:438-449`）；`ui:on` 由 `extra.uiRegionCount` 决定，缺失即视为存在（fail-open，`:463-478`）。
- **没有 status 标签**，而且不是遗漏：前端 `buildContext()` 严格只发 `page/taskType/step/taskId/extra`（`frontend/src/stores/workspace.ts:280-300`），`taskStatus` 只进本地 `snapshot()` 供展示诊断（`:304-315`）；后端 `ALLOWED_EXTRA_KEYS` 也没有 status（`ContextBuilder.java:61`）。所以“后端在披露时根本不知道任务状态”是结构性的。

**执行面的状态闸（都已存在）**：

| 闸 | 作用点 | 是否按状态 |
|---|---|---|
| `ToolPreflightService.inspect` | 4 个 `start_*` 进确认门前 | 是：任务存在、类型、**终态**（COMPLETED/CANCELLED 直接失败）、在途作业、预检指纹（`ToolPreflightService.java:106-174`，终态判定在 `:119-142`） |
| `JobService.finalizedConflict` / `TaskService.assertTaskNotFinalized` | 域层落库/跳步前 | 是：CANCELLED 恒拒；COMPLETED 按“最终作业是否成功”判（`JobService.java:90-110`、`TaskService.java:238-260`） |
| 前端 `isCurrentTaskReadOnly` | `ui_event` 的 goToStep/select_defs/set_condition/set_import_mode/run_job | 是：CANCELLED 只读；COMPLETED 还要核最终作业（`workspace.ts:419-435`，调用点 `:477/490/503/516/526`） |
| `ConfirmGate` | DANGER 工具执行前 | 否（人审，不按状态） |
| `WizardActionGuard` | `set_condition` / `select_definitions` 下发前 | 否：只管参数形态/未知目标/空条件放大（`WizardActionGuard.java:20-60`） |
| `matchesContext` | 执行前 scope 复查 | 否（页面/步骤/ui，不是状态） |

因此精确结论是：**披露面只有页面/任务类型/步骤/ui 四类标签，没有任务状态；状态闸全部在执行面**。所以在 COMPLETED 任务上，`start_export` 仍然“被披露”，模型仍可能调它，但会在预检阶段以“任务已 COMPLETED，不可再发起 EXPORT 作业”零副作用失败——这与“不暴露”是两种不同的安全模型，不要混为一谈。

---

## 给本项目的最小安全边界（按优先级）

1. **不要引入“partial assistant 回填 + 续写”**。它在 OpenAI 侧没有官方支持，在工具轮还会产生非法历史/重复副作用；要“续”就学 Responses 的游标式续传（同一执行继续流），或干脆按现有设计落到 `error{...AFTER_PARTIAL}`。
2. 若要借鉴 `streamRetries`，只借它的三条约束：**只重跑当前 model step**、**失败尝试的工具/审批/客户端执行一律丢弃**、**总恢复次数有界**；同时明确告诉前端“可见片段可能重复”，由前端按 attempt/part 丢弃，否则谈不上无缝。
3. `!emitted && !sideEffect` 这条线应保留。若确实想多救一点，唯一相对安全的扩展是“仅 reasoning、无工具、无可见正文”的窄窗口——但要接受重复思考段，并单独评估前端是否能丢弃。
4. `max-attempts` 的文档/配置注释建议统一写成“**总尝试次数**（2 = 1 次重试）”，避免“2 次实际 1 次”这类误读反复出现。
5. 状态判断继续放在执行面（预检 + 域层守卫 + 零副作用失败 + 前端只读守卫），不要为了“让模型看不见”而把 status 塞进披露上下文——那会引入上下文一致性和伪造面；若未来要加，必须与 `RunSnapshot` 一起冻结，且只用于收窄、不用于授权。
6. 引用外部结论时，把“业界能做”与“某函数/某版本默认就会”分开写：`tools_condition` 不是状态闸，`streamRetries` 不是 2026-10 的新特性，OpenAI 也没有“3 次重试”的标准值。
