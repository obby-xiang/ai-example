> **历史分析 / 过程证据 — 非现行基线，非验收结论**
>
> - 归档：2026-10-26 · `docs/evidence/history-recovery-2026-10-26/wire-tail/`
> - 来源：Kimi Code 会话 `<KIMI_SESSION>` / 子 agent `agent-1402` 的 wire 末条 assistant 文本（消息序号 m=36；只读流式解析，非原始会话转写）
> - 历史时点：2026-10-21 21:02 → 2026-10-21 21:09（wire 事件时间窗，UTC+8）；当时模型：`DeepSeek/deepseek-flash`
> - 原基线：正文未出现基线哈希，未经本批复验
> - 原始体量：10107 B / 7197 字符 / 30 行；行指纹覆盖率（本批复算）8.7%（最高单文件 9%：`docs/research/调研-LLM超时惯例对照-2026-10-21.md`）
> - 类别：**正式调研 / 设计 / 裁决 / 分析**（原判：调研）
> - 与既有件关系：`调研-HITL与表单超时设计业界惯例`（0.00）※归属仅到会话提示
> - 脱敏：路径 0 / 会话标识 0 / 身份 0 / 密钥 0 / 命名纪律 0
> - 正文为该条消息的完整文本，未删段、未改写结论；仅脱敏、占位符归一与行尾空白规整。当时结论不代表当前代码状态，不得作为当前缺陷或有效裁决引用。
**自证**：`$HOME/.../agents/` 下最新目录是 `agent-1402`（21:02），其 `wire.jsonl` 的 `profile.bind` 行：`"agentId":"agent-1402","modelAlias":"DeepSeek/deepseek-flash","profileName":"coder","thinkingEffort":"high"`。下面每个条目都标注了证据类型：[文档]=官方文档原文，[源码]=源码实现，[推导]=基于文档定义的必要推论。

### Q1 挂起后计时口径：逐对象

- **LangGraph**：只有步数预算，没有 wall-clock。`recursion_limit` 表示单次执行可跑的超步（super-step）上限，默认 1000；`invoke` 没有 timeout 参数。恢复后**不重置**，因为步数计数器保存在 checkpoint metadata 中，恢复时按 `step = saved.metadata.get("step", -1) + 1` 继续累计。[文档](https://docs.langchain.com/oss/python/langgraph/graph-api) + [源码 pregel/main.py](https://github.com/langchain-ai/langgraph/blob/main/libs/langgraph/langgraph/pregel/main.py) [源码]
- **Temporal**：分三级。`WorkflowTaskTimeout` 仅覆盖"worker 执行一个 Workflow Task"（默认 10s），SDK 注释明确写道"**this timeout is not related to the overall Workflow duration in any way**"——等待 signal 时没有 task 在跑，因此 task 计时不走；`WorkflowRunTimeout` / `WorkflowExecutionTimeout` 则是整段 run / 整条链的日历时长（后者"includes run retries and continue as new"），而 Open 状态的定义本身包含"waiting on something"，所以**等待期照样累计**。官方还反对用它做业务超时，建议用 Timer。[timeouts](https://docs.temporal.io/develop/python/workflows/timeouts) + [sdk-go](https://raw.githubusercontent.com/temporalio/sdk-go/master/internal/workflow.go) + [workflow-execution](https://docs.temporal.io/workflow-execution) [文档+源码]
- **AWS Step Functions**：`TimeoutSeconds` 从 `TaskStarted` 开始计时，原文是"Step Functions waits for a success or failure response ... within the specified TimeoutSeconds"；callback token 的等待发生在 TaskStarted 之后，因此**算在内**（默认 99,999,999s）。若没有设置 state 超时，则回调任务会一直等到 execution 上限 1 年，原文是"will wait until the execution reaches the one year service quota"。idle time 也是 1 年，并明确写着"constrained by maximum execution time"。[state-task](https://docs.aws.amazon.com/step-functions/latest/dg/state-task.html) + [connect-to-resource](https://docs.aws.amazon.com/step-functions/latest/dg/connect-to-resource.html) + [limits](https://docs.aws.amazon.com/step-functions/latest/dg/limits-overview.html) [文档]
- **Vercel AI SDK**：`totalMs` 表示"整个 call 含所有 steps"；`stepMs` 表示"每个完整 step，含模型生成、重试与 local tool 执行"，并且这两个预算"remain active across retries and local tool execution"——都算，不豁免。二者都属于 per-call 的 RequestOptions（abort），而人工审批的挂起/恢复由 `WorkflowAgent` 的 durable suspend/resume 承担（"approvals suspend and resume durable workflow execution"）；也就是说，暂停期间没有任何 call 在计时，恢复即是新调用、新时钟（推导）。[settings](https://ai-sdk.dev/docs/ai-sdk-core/settings) + [tool-approvals](https://ai-sdk.dev/docs/agents/tool-approvals) + [AI SDK 7](https://vercel.com/blog/ai-sdk-7) [文档，最后半句为推导]
- **Claude Code**：只有 `--max-turns`（仅限 print 模式，默认无限制）和 `--max-budget-usd`（金额预算），没有轮级 wall-clock；权限确认没有时钟可停，因此不存在"重置"问题。[cli-reference](https://docs.claude.com/en/docs/claude-code/cli-reference) [文档]
- **OpenAI Agents SDK**：`max_turns` 按步数计算（`None` 表示不限）；`ModelSettings.timeout` 只作用于单次 model call（`ModelTimeoutError`），工具超时是独立的 `ToolTimeoutError`。HITL 暂停后从 `RunState` 再次调用 `Runner.run` 属于新一次 run，没有跨暂停的墙钟。[running_agents](https://openai.github.io/openai-agents-python/running_agents/) [文档]
- **CrewAI**：只有 `max_execution_time`（秒，墙钟）/ `max_iter`（轮数），文档没有给出"HITL 等待是否扣除"的口径，实际按墙钟计——包括 `human_input` 阻塞等待在内。[concepts/agents](https://docs.crewai.com/en/concepts/agents) [文档]
- **AutoGen**：终止条件是消息数/文本（`MaxMessageTermination`、`TextMentionTermination` 等），没有轮级墙钟预算。[termination](https://microsoft.github.io/autogen/stable/user-guide/agentchat-user-guide/tutorial/termination.html) [文档]
- **旁证（明确选"停表"的一类）**：Inngest 原文"A wait does not keep a worker running, and your compute will entirely pause during the wait"，超时靠 `waitForEvent` 的显式 timeout 表达。[inngest](https://www.inngest.com/docs/learn/how-functions-are-executed) [文档]

**有无共识**：没有。业界分成两派，而且分界清晰——(a) **编排/持久化引擎**（Temporal、Step Functions、Inngest）：总时长按墙钟累计，挂起不"停表"，靠显式的业务 deadline（Timer、`waitForEvent` timeout、`HeartbeatSeconds`）来兜，并且都劝告你别把总超时当业务超时用；(b) **调用/轮次型 SDK**（AI SDK、OpenAI Agents SDK、Claude Code、AutoGen）：根本没有跨暂停的墙钟，只有单次调用预算或步数预算，恢复即新时钟。**唯一真正无争议的是步数类预算（LangGraph `recursion_limit`、`max_turns`）：一律继续累计，绝不重置。**

### Q2 取消信号/标志的保留期

- **gRPC / HTTP**：说法成立。cancel 是调用/流级的一次性信号（deadline 到期或 I/O 错误也会触发），服务端 handler 必须自己周期性检查"cancelled?"并停止；它既不落盘，也没有 TTL 概念，流/连接结束即消失。[grpc.io cancellation](https://grpc.io/docs/guides/cancellation/) [文档]
- **Temporal**：取消不是"标志"，而是往 Event History 写一条 **`WorkflowExecutionCancelRequested`** 事件，永久可查；terminate 写 `WorkflowExecutionTerminated`。取消请求是持久的，且可以被 workflow 代码处理。[workflows/cancellation](https://docs.temporal.io/develop/python/workflows/cancellation) [文档]
- **AWS Step Functions**：`StopExecution` 后执行关闭，**执行历史保留 90 天**（可申请缩到 30 天），过期后"you can no longer retrieve or view the execution history"；redrive 窗口为 14 天。→ 取消状态终态可查，但有期限。[limits](https://docs.aws.amazon.com/step-functions/latest/dg/limits-overview.html) [文档]
- **Kubernetes**：删除是 `deletionTimestamp` + grace period（`terminationGracePeriodSeconds` 默认 30s），finalizer 挂住对象直到清理完成；Lease 用 `leaseDurationSeconds`/`renewTime` 表达 TTL，apiserver 的过期 Lease "garbage collected ... after 1 hour"。[pod-lifecycle](https://kubernetes.io/docs/concepts/workloads/pods/pod-lifecycle/) + [leases](https://kubernetes.io/docs/concepts/architecture/leases/) [文档]
- **Tombstone 保留期的权威表述**：Cassandra 的 `gc_grace_seconds`（默认 864000s = 10 天），tombstone 必须活过"所有副本可能失联/未 repair"的窗口——"If a node remains down or disconnected for longer than gc_grace_seconds, its deleted data will be repaired back ... and reappear"，即删除会被复活。[cassandra tombstones](https://cassandra.apache.org/doc/latest/cassandra/managing/operating/compaction/tombstones.html) [文档]
- **AWS Builders' Library（最接近"要活到所有引用者消亡"的原文）**："it's impractical to retain that knowledge indefinitely ... We have found that, for EC2 instances, it works to **limit the time period to the lifetime of the resource, plus an interval after which it is reasonable to assume that any late arriving requests would either have arrived or would no longer be valid.**"[builders-library](https://aws.amazon.com/builders-library/making-retries-safe-with-idempotent-APIs/) [文档]
- **幂等键对照**：Stripe API v1 幂等键"you can remove keys ... after they're at least **24 hours** old"，清除后复用同一个 key 被视为新请求；Redis 用 `SET key val NX EX <seconds>` 的显式 TTL 是同一套惯例。[stripe](https://docs.stripe.com/api/idempotent_requests) + [redis SET](https://redis.io/docs/latest/commands/set/) [文档]
- **LangGraph / LangSmith**：run 与 thread 状态本身持久，保留期由 `checkpointer.ttl` 配置（示例 43200 分钟 = 30 天），"**If omitted, threads do not expire by default**"，且 `delete` 策略"does not refresh with activity"（取消后的 thread 不会被后续活动续命）。[configure-ttl](https://docs.langchain.com/langsmith/configure-ttl) [文档]

**有无共识**：有，而且分两层。**传输层**的 cancel 是瞬时、连接/流级的信号，从不落盘，因此没有"保留期"（gRPC 的口径可确认为行业默认）。**编排层**的 cancel 一定落成一条永久事件或终态记录（Temporal 事件、SFN 执行历史、LangGraph thread 状态），保留期由产品策略给定：SFN 90 天、LangGraph 默认不过期（可配 30 天）。而"防止复活"的那类标志（tombstone / 幂等键 / Lease）的保留期公式是公认的：**资源寿命 + 最大迟到请求窗口**——Stripe 取 24h、Cassandra 取 10 天、K8s Lease 取 ~1h 级、AWS 的说法是"resource lifetime plus a reasonable late-arrival interval"。类比到 Agent 的取消标志，业界落点就在 **24h–30d** 这个区间，往 24h 靠是"重试语义"取向，往 30d 靠是"审计/可追溯"取向。

**未覆盖/存疑**：Temporal 的"run timeout 是否把等 signal 的时间算进去"没有找到一句逐字原文，上面的结论由三处官方定义合成（Run 时长定义 + Open 状态含 waiting + task timeout 与总时长无关），属于强推导而非原句引用；CrewAI 的 HITL 是否扣除等待时间属于文档空白（按实现是墙钟），若要写进设计文档建议标注为"框架未定义"。
