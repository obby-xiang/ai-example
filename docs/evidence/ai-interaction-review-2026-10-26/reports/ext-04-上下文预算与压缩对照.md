> 来源=agent-2wbdqk36/output.log（2026-10-26 归档）· 外部研究（上下文预算、记忆、摘要压缩、恢复的一手对照）
> 性质：本次只读分析/调研的**脱敏副本**，属历史证据；为**分析稿**，结论以 `docs/research/` 总册与「规格复核补正」为准。仅做本机路径/仓库名/个人用户名占位与空白清理，**技术结论、计数、状态词一字未改**。

# 业界对照：上下文预算、历史记忆、摘要压缩与恢复

本次仅访问公开官方文档、官方工程博客和官方 changelog，未读取项目文件或密钥，未调用真实模型。以下“官方”均指厂商一手资料；社区文章里的阈值数字只作为反例，不作为事实。

## 一、先分层：五种机制不是同一种“压缩”

| 机制 | 官方常见叫法 | 是否调用模型 | 作用域 | 留下什么 | 主要风险 |
|---|---|---|---|---|---|
| 硬截断、窗口裁剪 | trim、drop、truncate | 否 | 单次请求 | 最近 N 条或 N token | 早期约束丢失；tool call 与 result 可能被拆开 |
| 工具结果清理 | context editing、pruneMessages | 否 | 单次请求或服务端视图 | 占位符 + 工具调用元数据 | 误删证据；缓存失效；模型不知道被删了什么 |
| 思考块清理 | clear thinking | 否 | 单次请求 | 最近 N 轮 thinking | 决策依据丢失；缓存失效 |
| 保留最近 | keep last N messages/turns | 否 | 单次请求 | 最近 N 轮原文 | 早期决策、文件状态、未完成事项丢失 |
| 摘要压缩 | compaction、summarization | 是 | 当前会话 | 结构化摘要 + 最近轮 | 摘要幻觉、细节丢失、额外延迟与成本 |
| 跨会话记忆 | memory、store、sessions | 可能 | 跨会话 | 持久文件或条目 | 污染、隐私、过期、与当前事实冲突 |
| 子代理、按需检索 | subagent、just-in-time retrieval | 是或否 | 当前任务 | 摘要或引用 | 延迟、遗漏、引用失效 |

一句话：**自动截断不是摘要；摘要不是记忆；记忆不是压缩。** 项目里的 L1/L2/L3 应分别对应“清理”“摘要”“跨会话记忆”，而不是三个不同强度的“压缩”。

三个最容易混的点：

- **Anthropic 的 100k 和 150k 不是同一个东西。** Context editing 清工具结果的默认触发是 100k input tokens、默认保留 3 个 tool uses；threshold compaction 的摘要触发默认是 150k、最小 50k。
- **OpenAI 的 compaction item 不可读，Anthropic 的 on-demand compaction block 可读。** 前者适合无状态续跑，后者适合需要展示或审计摘要的产品。
- **LangGraph 的 checkpointer 不是长期记忆。** 它存线程状态；跨会话记忆在 store。不要把“能恢复会话”当成“记住了用户”。

## 二、一手来源与硬规则

### OpenAI / Codex

| 来源 | 层次 | 已核验的规则与限制 |
|---|---|---|
| [OpenAI — Compaction](https://developers.openai.com/api/docs/guides/compaction) | L2 | `context_management` 配 `compact_threshold`，示例值 200,000；服务端压缩，流里返回加密 compaction item，官方明确“不透明、不面向人类解释”。独立 `/responses/compact` 返回的是规范化的下一窗口，**不要 prune 其输出**；送入压缩的输入本身仍必须放得进上下文窗口。 |
| [OpenAI Agents SDK — Sessions](https://openai.github.io/openai-agents-python/sessions/) | L2/L3 | 自动 compaction 会等完成才让 `Runner.run()` 返回或让流关闭；压缩重时，最后一个输出 token 之后流可能再开几秒。失败或取消时，wrapper 会尝试恢复旧历史；会跳过 stale compaction；`add_items/pop_item/clear_session` 与压缩串行化。可关闭自动压缩，手动 `run_compaction()`；压缩请求的 usage 计入该 run。 |
| [OpenAI Cookbook — Building Reliable Agents with Memory and Compaction](https://developers.openai.com/cookbook/examples/agents_sdk/building_reliable_agents_memory_compaction) | L2/L3 | 官方把两者分开：compaction 帮**当前 run**继续；memory 帮**未来 run**复用经验。memory 应存 workflow lessons，不存案件结论；最终 artifact 才是 source of truth。 |
| [Codex — Config reference](https://developers.openai.com/codex/config-reference) | L1/L2 | `model_auto_compact_token_limit` 未设置时用模型默认；`model_auto_compact_token_limit_scope` 可选 `total`（默认，按完整活跃上下文）或 `body_after_prefix`（只算 carry 前缀之后的增长）。`tool_output_token_limit` 控制单个工具输出进入历史的预算；`compact_prompt` 可覆盖压缩提示词；`features.memories` 默认关闭。 |
| [Codex — Hooks](https://developers.openai.com/codex/hooks) | L2/UX | `PreCompact` / `PostCompact` 是正式生命周期事件，matcher 为 `manual` 或 `auto`；`PreCompact` 返回 `continue:false` 可以阻止压缩。压缩后 `SessionStart` 的 `source:"compact"` 会在下一次模型请求前再注入上下文；中途压缩则把 hook 上下文送到当前 turn 的立即续跑。hook 支持 `statusMessage`。 |

### Anthropic / Claude Code

| 来源 | 层次 | 已核验的规则与限制 |
|---|---|---|
| [Anthropic — Context editing](https://platform.claude.com/docs/en/build-with-claude/context-editing) | L1 | `clear_tool_uses_20250919` 默认触发 100,000 input tokens、默认保留最近 3 个 tool uses；支持 `clear_at_least`、`exclude_tools`、`clear_tool_inputs=false`。清除后用占位文本告诉模型“已移除”；响应 `context_management.applied_edits` 返回清了多少 tool uses、多少 input tokens。**服务端编辑，客户端保留完整未修改历史，不需要同步。** 思考块清理 `clear_thinking_20251015` 独立配置，按最近 N 个 assistant turns 或全部保留，默认随模型不同。 |
| [Anthropic — Compaction overview](https://platform.claude.com/docs/en/build-with-claude/compaction) | L2 | 三种：on-demand、threshold、client-side。官方推荐 on-demand；background compaction 可在工作继续时运行；可保留最近轮原文；threshold 在普通请求内运行；client-side 会替换整段历史。 |
| [Anthropic — Compaction at a token threshold](https://platform.claude.com/docs/en/build-with-claude/compaction-threshold) | L2 | `compact_20260112`；默认 trigger 150,000、最小 50,000；`pause_after_compaction` 可暂停后插入最近轮；`instructions` 会**整体替换**默认摘要提示。返回 `compaction` block；下次请求 API 自动丢弃 block 之前的内容。流式时摘要整块到达，只有一个 delta，没有中间进度；额外采样会计费；摘要必须用同一模型；带 tools 时模型可能误调工具，返回 `content:null`。 |
| [Anthropic — Compaction on demand](https://platform.claude.com/docs/en/build-with-claude/compaction-on-demand) | L2 | `compact-2026-09-04` + `compaction:{"type":"summarize"}`；block 必须放 `messages` 首位，删除被摘要消息，每请求只能有一个 block，`signature` 不能改。摘要失败仍可能返回 200 + 空 content，必须查 `stop_reason`。摘要可读；但图片、文档、`container_upload`、抓取 URL 不会保留；被摘要范围内的 `system` 消息也会失效。 |
| [Anthropic — Memory tool](https://platform.claude.com/docs/en/agents-and-tools/tool-use/memory-tool) | L3 | 完全客户端：Claude 请求 `view/create/str_replace/insert/delete/rename`，应用在 `/memories` 下执行。`view` 对超过 16,000 字符的文本会截断；文件超过 999,999 行报错。API 自动注入“先查看记忆、假设上下文随时会被重置”的指令。路径穿越、文件大小、过期、敏感信息都需要应用自己处理。 |
| [Anthropic Engineering — Effective context engineering](https://www.anthropic.com/engineering/effective-context-engineering-for-ai-agents) | 总论 | Compaction 保留架构决策、未解决 bug、实现细节，丢弃冗余工具输出；Claude Code 压缩后会重读最近 5 个文件。另有结构化笔记、子代理两条非压缩路径；子代理常只回 1,000–2,000 token 摘要。 |
| [Claude Code — Explore the context window](https://code.claude.com/docs/en/context-window) | L1/L2 | 压缩后重注入：系统提示与输出风格、项目根 CLAUDE.md、auto memory、skill 描述、git 状态、plan；最多重读 5 个最近读写的文件；单个文件超过 5,000 token 只回路径；skill body 上限 5,000/25,000 token；后台命令和子代理继续运行。`/compact` 可带 focus；`/rewind` 可“Summarize from here/up to here”；`/autocompact` 可改触发窗口；`/context` 看分类占用。 |
| [Claude Code — Model config](https://code.claude.com/docs/en/model-config) | L2 | 当前文档：原生 1M 窗口模型默认约 967K 触发；200K 模型在 200K 边界触发；`/autocompact 500k`、`--autocompact`、`CLAUDE_CODE_AUTO_COMPACT_WINDOW` 都可改。注意状态栏 `used_percentage` 仍按模型完整窗口算，环境变量设置后百分比不再代表压缩触发点。 |
| [Claude Code — Status line](https://code.claude.com/docs/en/statusline) | Token | `used_percentage` 只算 input：`input_tokens + cache_creation_input_tokens + cache_read_input_tokens`，不含 output。`current_usage` 在 `/compact` 后为 `null`，直到下一次 API 调用才恢复；`/context` 会额外估算“上次响应之后新增的消息”，所以可能比状态栏高。`/compact` 完成会触发状态栏刷新。 |
| [Claude Code — Errors](https://code.claude.com/docs/en/errors) / [How it works](https://code.claude.com/docs/en/how-claude-code-works) / [Changelog](https://code.claude.com/docs/en/changelog) | UX/恢复 | 停止压缩时对话不变，重新 `/compact` 即可；超限时会退化为“保留最新 prompt 原文、摘要其余”，甚至整段摘要；单个巨大文件或工具结果导致压缩后立刻又满时，Claude Code 几次尝试后停止并报错，而不是无限循环。官方 changelog 明确出现过 `Compacting…` spinner，也修过 Esc 中断误报、`/context` 压缩后仍显示旧 token、resume 丢失压缩刚恢复的文件等问题。 |

### LangChain / LangGraph / Vercel AI SDK

| 来源 | 层次 | 已核验的规则与限制 |
|---|---|---|
| [LangChain — Short-term memory](https://docs.langchain.com/oss/python/langchain/short-term-memory) | L1/L2 | 官方列出 trim、delete、summarize、custom 四类。`SummarizationMiddleware(model=..., trigger=("tokens",4000), keep=("messages",20))` 是**示例配置**，不是通用默认。删除消息时必须保持合法历史：很多 provider 要求 assistant tool call 后跟对应 tool result。 |
| [LangGraph — Add memory](https://docs.langchain.com/oss/python/langgraph/add-memory) | L3 | checkpointer 存线程状态，用于恢复同一 thread；store 存跨会话记忆，支持命名空间和语义检索；生产建议 Postgres 等数据库后端。官方把“短期记忆”和“长期记忆”明确分开。 |
| [Vercel AI SDK — pruneMessages](https://ai-sdk.dev/docs/reference/ai-sdk-ui/prune-messages) / [Compact Agent Context](https://ai-sdk.dev/cookbook/guides/agent-context-compaction) | L1/L2 | `pruneMessages` 默认 `reasoning:"none"`、`toolCalls:[]`（不删）、`emptyMessages:"remove"`；支持 `before-last-message`、`before-last-N-messages`、按工具细控。它是客户端消息裁剪，不是服务端摘要。cookbook 的 100k 只是示例；`prepareStep` 返回的新 `messages` 会持续影响后续 step，官方明确“可换成你自己的压缩逻辑”。 |

## 三、UX/机制对照

### A. 预算、状态、耗时、token

| UX/机制 | 官方资料核验点 | 可借鉴点 | 不可照搬的边界 |
|---|---|---|---|
| 触发与阈值 | Anthropic context editing 默认 100k/keep 3；threshold compaction 默认 150k/最小 50k；Claude Code 1M 模型约 967K、200K 模型 200K，`/autocompact` 可调；Codex 未设阈值时用模型默认，scope 可选 total/body_after_prefix | 从模型窗口、tokenizer、工具 schema 计算“整理点”；把阈值放高级设置；支持手动“整理”加 focus；明确按“总上下文”还是“增量”计数 | 这些是不同产品、不同机制的默认值，不可互抄；没有公开的“业界统一阈值”；项目必须用自己的 trace 评估 |
| 压缩中状态 | Claude Code changelog 有 `Compacting…` spinner；`/compact` 完成触发状态栏刷新；Codex `PreCompact/PostCompact` + `statusMessage`；OpenAI Agents SDK 自动压缩会等完成，流式可能多开几秒 | 明确“整理中”状态；后台压缩用轻量 badge；阻塞压缩用行内状态并禁用重复发送；显示“完成后会自动继续”；支持取消或排队 | CLI spinner 不等于 Web UX；官方没有规定状态文案和位置；不要把 L1/L2/L3 暴露给用户 |
| 耗时/进度 | Anthropic compaction block 在流中整块到达，只有 start/stop，没有中间 delta；Claude Code 未公开耗时或百分比；OpenAI 说重压缩可能多几秒 | 用不确定进度 + 已用时间；超过 1–2 秒显示“仍在整理”；分阶段文案“清理工具结果 → 生成摘要 → 恢复上下文”；不要假进度条 | 没有协议级进度；不要编造 80%；耗时应自行埋点 p50/p95；长压缩应可后台或可取消 |
| Token 展示 | Claude Code `used_percentage` 只算 input，不含 output；`current_usage` 在 `/compact` 后为 null；`/context` 可能更高；Anthropic token counting 返回 original/after；threshold compaction 用 `usage.iterations`；OpenAI `previous_response_id` 仍计费所有历史 input | 固定一个公式并标注“截至上次回复”；压缩后显示“更新中/—”，不要显示 0；分开“上下文占用”和“计费 token”；已知时显示“原始 → 压缩后”；差异用 tooltip 解释 | on-demand compaction 顶层 usage 可能为 0，不能当空上下文；不同厂商 token 数不可直接比较；“省了 token”不等于“省了钱” |

### B. 一致性、摘要、恢复、防抖

| UX/机制 | 官方资料核验点 | 可借鉴点 | 不可照搬的边界 |
|---|---|---|---|
| 历史与实时一致性 | Anthropic context editing 服务端编辑、客户端保留完整历史；threshold compaction 下次请求丢弃 block 前内容；OpenAI compact 输出是规范窗口、不要 prune；Claude Code 本地 JSONL + compaction marker；statusline 压缩后 null | 两层：canonical transcript 只追加，derived model context 可替换；UI 显示原始消息树，模型用快照；压缩边界可见、原始可展开；摘要带 source ids 和 version | “服务端编辑无需同步”只适用于 context editing；compaction 往往要求客户端替换历史；opaque item 无法展示；provider 状态不可移植 |
| 摘要可见性与审计 | Anthropic on-demand 返回可读 summary + signature；OpenAI compaction item 不透明；Claude Code 摘要替换对话、早期细节可能丢失；OpenAI cookbook 说最终 artifact 才是 source of truth | 折叠“压缩摘要”卡；列出保留、丢弃、恢复指针；链接原始消息；摘要不可手改；显示覆盖范围和生成时间 | 不要把摘要当普通 assistant 消息；不要编辑签名块；用户要改就重新摘要；opaque 方案不适合需要审计的产品 |
| 失败与自动恢复 | OpenAI Agents SDK 失败/取消恢复旧历史、跳过 stale、串行化；Anthropic on-demand 可能 200 + 空 content，要查 `stop_reason`；Claude Code 停止压缩时对话不变；changelog 有 resume 丢恢复文件 | 事务式 pointer swap；先写新快照再切换；失败保留旧指针；重试上限；文案“原对话未受影响”；恢复后校验文件和任务 | 不是所有 provider 都事务；不要先删原始记录；200 不等于成功；resume/restore 必须做端到端测试 |
| 防抖与防反复压缩 | Claude Code 巨大文件/工具结果导致反复压缩时，几次后停止并报错；changelog 修过 “compacting repeatedly” | 每轮最多 N 次；检测“压缩后仍超限”的无进展；给可操作出口：移除附件、清理工具结果、开新会话；不要静默硬截断 | 官方没有公开 N；需要自定；不要把“继续压缩”当唯一恢复；失败要说明原因 |

### C. 思考、工具、记忆、前端复杂度

| UX/机制 | 官方资料核验点 | 可借鉴点 | 不可照搬的边界 |
|---|---|---|---|
| 思考全文与工具结果 | Anthropic 分开清 tool results/thinking，默认 keep 3 tool uses，可 exclude tools，用占位符；摘要可能不带 thinking；AI SDK `pruneMessages` 默认不删 reasoning/toolCalls；Codex 有 `tool_output_token_limit` | L1 顺序：旧 thinking → 旧 tool results → 附件/大输出；保留当前轮 thinking；保留 tool 名称和参数；审计/证据工具排除；摘要重述决策 | thinking 不是摘要；不要把完整 CoT 存长期记忆；清理会破坏缓存和证据；tool call/result 必须成对 |
| 跨会话记忆 | Anthropic memory tool 客户端 `/memories`，自动“假设中断”；LangGraph checkpointer vs store；OpenAI sessions；Cookbook memory=workflow lessons | 独立 L3 store；TTL、租户、可查看、可删除；进度日志；按需注入；写入审核；不要全量注入 | memory ≠ compaction；不要存案件事实或结论；不要自动注入所有记忆；路径穿越、大小、过期是应用责任 |
| 前端长期消息树与复杂度披露 | Claude Code 保留完整 JSONL + compaction marker，`/context` 分类，`/compact` 简单命令；Codex `/status`；官方未承诺压缩会降低前端渲染成本 | 消息树虚拟化/折叠；压缩边界 marker；详情抽屉展示分类、摘要、清理统计；主界面只留“占用 / 整理 / 详情”；高级设置放阈值、自动/手动 | 压缩模型上下文 ≠ 压缩 DOM；需要单独的前端性能工作；不要把 token 数学和 L1/L2/L3 默认抛给用户 |

## 四、对本项目的落地建议

### 4.0 先做预算分配，再谈压缩

按类别设预算，而不是只有一个“总窗口”：

- 系统与规则：系统提示、工作区规则、工具说明。
- 工具 schema：MCP/工具定义；Claude Code 的 `/context` 会显示 tools 占用。
- 记忆与长期偏好：CLAUDE.md/AGENTS.md 一类规则、L3 记忆。
- 最近轮：最近 N 轮原文，保留当前任务上下文。
- 工具结果与附件：最大变量，应单独设每条/每类上限。Codex 的 `skills.max_context_tokens` 默认是模型窗口的 2%、上限 10,000 token；`tool_output_token_limit` 控制单个工具输出。这些是**产品示例**，不是通用标准。
- 输出与推理预留：压缩触发点必须低于硬上限，给输出、reasoning、摘要调用本身留空间。

`/context` 式“谁吃掉了窗口”的分类面板，比一个总百分比更能让用户采取行动。

### 4.1 L1/L2/L3 的职责边界

| 层 | 建议职责 | 触发 | 调用模型 | 用户可见 | 回滚 |
|---|---|---|---|---|---|
| L1 清理 | 清旧 thinking、旧工具结果、旧附件/大输出；保留工具调用元数据；用占位符 | 每次发送前或接近预算时 | 否 | 详情里显示“清理了多少条/多少 token” | 原始 transcript 不动，天然可回滚 |
| L2 摘要 | 摘要较早轮次，保留最近 N 轮和当前任务状态 | 阈值、任务边界或用户手动 | 是 | 折叠摘要卡 + 边界 marker | 保留原始消息，快照指针可回退 |
| L3 记忆 | 跨会话保存稳定偏好、项目约定、工作流经验 | 任务结束/会话结束/按需 | 可能 | 记忆面板，可查看/删除 | TTL、删除、版本化 |

关键原则：

- **L1 只改“发给模型的派生 payload”，不改 canonical transcript。**
- **L2 保留最近 N 轮原文 + 当前任务状态。** N 不应拍脑袋，用项目自己的任务成功率评估。Anthropic 的“最近轮原文”、LangChain 的 `keep=("messages",20)`、Claude Code 的最多 5 个文件都只是各自产品的做法。
- **L3 只存“未来还有用的经验”，不存案件事实。** OpenAI cookbook 明确说 memory 存 workflow lessons，不存 investigation conclusions；最终 artifact 才是事实来源。

### 4.2 压缩状态机与文案

建议至少区分这些状态：

| 状态 | 用户看到 | 输入框 | 可取消 | 备注 |
|---|---|---|---|---|
| idle | “上下文 68% · 整理” | 正常 | — | 不显示内部层级 |
| scheduled | “将在空闲时整理较早对话” | 正常 | 是 | 后台压缩 |
| compacting（阻塞） | “正在整理较早对话… 已 8 秒” | 禁用或排队 | 是 | 下一轮依赖压缩结果时使用 |
| compacting（后台） | 轻量 badge “后台整理中” | 正常 | 是 | 允许继续工作，但要提示新消息可能排队 |
| committing | “正在恢复上下文…” | 禁用 | 否 | 指针切换阶段，避免并发写 |
| done | “已整理，用量更新中” | 正常 | — | 等下一次 API 返回再显示新 token |
| failed | “整理未完成，原对话未受影响 [重试]” | 正常 | — | 不要显示堆栈或内部错误码 |
| token refresh pending | “用量更新中” | 正常 | — | 压缩后短暂状态 |

官方能支持“有状态”，但不能支持“有真实百分比”：

- Claude Code 有 `Compacting…` spinner，但没有公开耗时/进度。
- Anthropic 的 compaction block 在流里整块到达，只有 start/stop，没有中间 delta。
- OpenAI 明确说重压缩可能让流在最后一个 token 后继续开几秒。

所以：**用不确定进度 + 已用时间，不要假进度条。** 如果一定要分阶段，用“清理工具结果 → 生成摘要 → 恢复文件/计划”，不要用“67%”。

### 4.3 Token 展示规则

1. **固定一个公式，并标注口径。** 例如“上下文占用 = input + cache read + cache write”，并注明“不含输出”。Claude Code 的 `used_percentage` 就是这个口径，但不是行业统一口径。
2. **压缩后不要显示 0。** Claude Code 的 `current_usage` 在 `/compact` 后是 `null`，直到下一次 API 调用；应显示“—/更新中”。
3. **区分“上下文占用”和“计费 token”。** 压缩本身要额外采样；OpenAI 的 `previous_response_id` 仍会计费所有历史 input。省上下文不等于省钱。
4. **只在有依据时显示“节省”。** Anthropic 的 token counting 能给 original/after，context editing 的 `applied_edits` 能给 cleared tokens；没有这些数据时，不要估算成事实。
5. **两个数字不一致要解释。** `/context` 可能比状态栏高，因为前者估算“上次响应后新增的消息”；这不是 bug，是口径不同。

### 4.4 自动恢复与一致性

- **追加式 transcript + 快照指针。** 原始消息永不删；L2 产生一个新快照，成功后原子切换指针；失败保留旧指针。
- **摘要元数据。** 至少记录：覆盖的 message ids、生成模型、prompt 版本、时间、压缩前/后 token、触发原因、保留的最近轮范围。
- **分支/回退使摘要失效。** 用户在压缩边界之前编辑、rewind、fork 时，旧摘要不能继续复用；要么重算，要么标记 stale。Anthropic 的签名 block 和“block 必须首位、不能改”本质上也是同一种一致性约束。
- **重试上限与无进展检测。** 参考 Claude Code：压缩后仍超限、或巨大文件立刻再次填满时，停止并报错，不要循环。给用户可操作出口，而不是继续压缩。
- **恢复清单要确定性。** 压缩后重注入：当前 plan、打开/修改的文件路径、规则、运行中的任务、最后一条用户请求。Claude Code 的做法是最多 5 个文件、超过 5k token 只回路径、skill 有 5k/25k 上限；这些是产品默认，不是普适值，但“有清单、有上限、可解释”值得借鉴。
- **跨会话恢复用进度日志。** Anthropic memory tool 的自动指令是“先看记忆、假设随时会中断”；项目可以让 agent 在关键阶段写 progress log，恢复时先读它，而不是重放全部历史。
- **必须测试“压缩后 resume”。** Claude Code changelog 出现过 resume 丢失压缩刚恢复的文件、`/goal` 丢失等问题；这类问题只能靠端到端测试发现。

### 4.5 避免把内部复杂度抛给用户

主界面只保留三件事：

- **占用**：一个百分比或“充足/偏高/接近上限”。
- **状态**：是否正在整理、是否完成、是否失败。
- **动作**：整理、查看详情、继续。

详情抽屉再展示：

- 分类占用：系统/规则、工具、记忆、最近轮、工具结果/附件。
- 最近一次压缩：时间、原因、摘要、清理了多少条工具结果、保留了多少轮。
- 恢复清单：哪些文件/计划/任务被重新注入。

高级设置再放：

- 自动/手动、阈值、focus 指令、是否保留思考、是否启用跨会话记忆。

主界面不要出现 L1/L2/L3 这种内部术语；用“清理工具结果”“整理较早对话”“长期记忆”这种用户语言。

## 五、明确未知，不要当事实

- **没有公开的“业界统一阈值”。** Anthropic context editing 100k、threshold compaction 150k、Claude Code 967K/200K、Codex 模型默认，都是不同产品、不同机制、不同 tokenizer 下的值，不能互相证明，也不能直接搬到本项目。社区常见的 90%、55%、76–83% 等说法，我未在官方文档核实，不应作为依据。
- **没有官方的压缩耗时或进度百分比。** 能核实的只有“状态/生命周期”：Claude Code 的 `Compacting…`、Codex 的 `PreCompact/PostCompact`、OpenAI 的“压缩可能让流多开几秒”。不要编造进度。
- **官方没有承诺“压缩会降低前端渲染成本”。** Claude Code 保留完整 JSONL，压缩是模型上下文操作；长期打开的消息树仍需虚拟化、折叠、分页等独立前端工作。
- **OpenAI 与 Anthropic 的摘要可审计性不同。** OpenAI compaction item 不透明；Anthropic on-demand 的 summary 可读但有签名、不可改。不要把两者当成同一种“摘要”。
- **Anthropic memory tool 不托管存储。** `/memories` 只是路径前缀，实际存储、权限、TTL、路径穿越防护都在应用侧。
- **服务端“无需同步”只适用于 context editing。** Threshold compaction 会要求客户端在下一次请求中丢弃 block 前内容；on-demand 还要求 block 首位、删除被摘要消息、每请求一个 block。不能把“服务端会处理”当成通用结论。

---

以上链接均访问于 2026-10-26。若需要，我可以下一步把这些对照收敛成一张“本项目 L1/L2/L3 状态机 + UI 文案 + 埋点指标”的设计表。
