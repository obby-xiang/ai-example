> **历史分析 / 过程证据 — 非现行基线，非验收结论**
>
> - 归档：2026-10-26 · `docs/evidence/history-recovery-2026-10-26/wire-tail/`
> - 来源：Kimi Code 会话 `<KIMI_SESSION>` / 子 agent `agent-5` 的 wire 末条 assistant 文本（消息序号 m=7；只读流式解析，非原始会话转写）
> - 历史时点：2026-10-05 15:40 → 2026-10-05 15:44（wire 事件时间窗，UTC+8）；当时模型：`Zhipu AI/glm-5.3`
> - 原基线：正文未出现基线哈希，未经本批复验
> - 原始体量：44042 B / 29049 字符 / 469 行；行指纹覆盖率（本批复算）1.9%（最高单文件 2%：`docs/research/调研-无向量知识问答业界惯例-2026-10-21.md`）
> - 类别：**同源分支附录**
> - 与既有件关系：属「五分支评测」，对应 `docs/merge/01-分支评价报告.md` 口径
> - 脱敏：路径 0 / 会话标识 0 / 身份 0 / 密钥 0 / 命名纪律 0
> - 正文为该条消息的完整文本，未删段、未改写结论；仅脱敏、占位符归一与行尾空白规整。当时结论不代表当前代码状态，不得作为当前缺陷或有效裁决引用。
8 份文档已全部通读完毕（含三份共约 1.7 万行的对话记录全文），并做了关键词精确核查。以下为提炼报告。

---

# 一、Spring AI 1.1.8 确切 API（文档1、2，全部经源码/javap 逐字核对）

## 1.0 先记住两个大版本事实（决定骨架写法）

来自 `spring-ai-1.1.8-api-report.md` §0（javap 反汇编实证）：

> 1. **工具循环已从 ChatClient 移进 ChatModel**。`internalCall(Prompt, ChatResponse)` 与 `internalStream(Prompt, ChatResponse)` 内部直接调用 `toolCallingManager.executeToolCalls(prompt, chatResponse)`……即 **ChatClient 在 1.1.8 不自动注册 ToolCallAdvisor**。
> 2. 因此 HITL/SSE 可见性的正确切入点就是**自定义 `ToolCallingManager`（组合包装）并注入 `OpenAiChatModel`**（`OpenAiChatModel$Builder.toolCallingManager(...)`）。

来自 `spring-ai-1.1.8-agent-runtime-quickref.md` §0：1.1.x 仓库重构，`spring-ai-openai` → `models/spring-ai-openai/...`，自动配置类改名 **`OpenAiChatAutoConfiguration`**（v1.1.8 无 `OpenAiAutoConfiguration`）。

## 1.1 ChatClient 创建与链式 API

**创建方式**：两份文档均**未提及**“自动配置的 ChatClient.Builder Bean”（`ChatClientAutoConfiguration` 未被核对）。文档验证的写法是静态工厂 + 注入自动配置的 `OpenAiChatModel` Bean：

```java
public interface ChatClient {
  public static ChatClient create(ChatModel);
  public static ChatClient.Builder builder(ChatModel);   // ← 文档骨架用的入口
  public abstract ChatClient.ChatClientRequestSpec prompt();
  public abstract ChatClient.ChatClientRequestSpec prompt(String);
  public abstract ChatClient.ChatClientRequestSpec prompt(Prompt);
  public abstract ChatClient.Builder mutate();
}
```

自动配置侧（quickref 附录，源码核对）：`OpenAiChatAutoConfiguration` 上有 `@ConditionalOnProperty(name="spring.ai.model.chat", havingValue="openai", matchIfMissing=true)`，提供 `openAiApi`、`openAiChatModel` 两个 `@ConditionalOnMissingBean` Bean，`baseUrl/apiKey` 由 `resolveConnectionProperties(common, chat, "chat")` 解析——**chat 级属性优先于顶级**。

**`ChatClient$ChatClientRequestSpec` 确切签名（用户点名的每一项）**：

```java
ChatClientRequestSpec advisors(Consumer<AdvisorSpec>)   // 逐请求追加 Advisor / 传参
ChatClientRequestSpec advisors(Advisor...)
ChatClientRequestSpec messages(Message...) / messages(List<Message>)
<T extends ChatOptions> ChatClientRequestSpec options(T)
ChatClientRequestSpec toolNames(String...)
ChatClientRequestSpec tools(Object...)                  // 只此一个 tools 重载，接受 @Tool 方法宿主对象
ChatClientRequestSpec toolCallbacks(ToolCallback...)    // ToolCallback 版叫 toolCallbacks！
ChatClientRequestSpec toolCallbacks(List<ToolCallback>) / toolCallbacks(ToolCallbackProvider...)
ChatClientRequestSpec toolContext(Map<String,Object>)
ChatClientRequestSpec system(String) / system(Resource) / system(Consumer<PromptSystemSpec>)
ChatClientRequestSpec user(String) / user(Resource) / user(Consumer<PromptUserSpec>)
ChatClient.CallResponseSpec call()
ChatClient.StreamResponseSpec stream()
```

**1.1.8 中不存在（旧版 API，编译会报错）**：
- ❌ `system(Message)` —— 用 `messages(new SystemMessage(...))`
- ❌ `tools(ToolCallback...)` / `tools(List<ToolCallback>)` —— 改名 `toolCallbacks(...)`

**返回类型**：
```java
// call()
ChatResponse chatResponse();  String content();  <T> T entity(Class<T>);
// stream()
Flux<ChatResponse> chatResponse();   // 每个增量一个 ChatResponse
Flux<String> content();              // 纯文本流
```

**Builder 上的默认值方法**：`defaultAdvisors(...)`、`defaultOptions(ChatOptions)`、`defaultSystem(...)`、`defaultUser(...)`、`defaultTools(Object...)`、`defaultToolCallbacks(...)`、`defaultToolContext(Map)`、`defaultToolNames(String...)`、`clone()`、`build()`。

**流式取文本增量**：`cr.getResult().getOutput().getText()`（`Generation.getOutput()` → `AssistantMessage.getText()`）。

## 1.2 @Tool / @ToolParam 与按请求动态传工具

注解成员（javap 核对，`org.springframework.ai.tool.annotation`）：

```java
public interface Tool {
  String name();            // default ""
  String description();
  boolean returnDirect();
  Class<? extends ToolCallResultConverter> resultConverter();
}
public interface ToolParam {
  boolean required();
  String description();
}
```

用法（含 ToolContext 透传，来自文档2代码骨架）：

```java
class WeatherTools {
    @Tool(description = "查询指定城市天气，需要人工审批")
    public String getWeather(@ToolParam(description = "城市名", required = true) String city,
                             ToolContext toolContext) {          // 两参 call(String, ToolContext) 重载
        Map<String, Object> ctx = toolContext.getContext();      // 即 .toolContext(Map) 传入的数据
        return "北京晴 25℃ (conversationId=" + ctx.get("conversationId") + ")";
    }
}
```

**`.tools(Object...)` 与 `ToolCallback` 的区别**：
- `.tools(Object...)`：只接受 **@Tool 标注的实例/类**，Spring AI 自动反射生成 schema（`MethodToolCallback`），业务代码首选；
- `toolCallbacks(ToolCallback...)`：接受**手工构建的回调**，需自备 `ToolDefinition`（`DefaultToolDefinition.Builder: name/description/inputSchema → build()`）+ `ToolMetadata`；
- 工具类：**包名变了** —— `org.springframework.ai.support.ToolCallbacks`（不是 `.tool`），唯一静态方法 `ToolCallbacks.from(Object...)`；
- **按请求换工具集**：`prompt().tools(当前页面的工具对象...)` 或 `prompt().toolCallbacks(该步骤的回调...)`；按名引用已注册工具用 `toolNames(String...)`；
- ❌ `FunctionCallingOptions`、`ToolCallingContext` 类在 1.1.8 **已删除**（全 jar 扫描无果）；等价物是 `ToolCallingChatOptions`（含 `getToolContext()/setToolContext(Map)`）与 `org.springframework.ai.chat.model.ToolContext`（有 `TOOL_CALL_HISTORY` 常量与 `getToolCallHistory()`）。

## 1.3 ChatMemory 全套（含 conversation id 确切写法）

```java
// model jar
public interface ChatMemory {
  String CONVERSATION_ID;                    // 常量，即参数 key
  default void add(String, Message);
  abstract void add(String, List<Message>);
  abstract List<Message> get(String);
  abstract void clear(String);
}
public interface ChatMemoryRepository {
  List<String> findConversationIds();
  List<Message> findByConversationId(String);
  void saveAll(String, List<Message>);
  void deleteByConversationId(String);
}
public final class MessageWindowChatMemory implements ChatMemory {
  // Builder: chatMemoryRepository(ChatMemoryRepository) / maxMessages(int) / build()
}
// client-chat jar
public final class MessageChatMemoryAdvisor implements BaseChatMemoryAdvisor {
  public static MessageChatMemoryAdvisor.Builder builder(ChatMemory);   // ✔ 唯一入口
  // Builder: order(int) / scheduler(Scheduler) / build()
}
```

**conversation id 传递的确切写法**（文档2 第602行，javap 验证 `AdvisorSpec` 有 `param(String,Object)`）：

```java
.advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId)) // advisors(Consumer<AdvisorSpec>)
```

组装代码（文档骨架原样）：

```java
ChatMemory memory = MessageWindowChatMemory.builder()
        .chatMemoryRepository(/* ChatMemoryRepository 实现 */)
        .maxMessages(20)
        .build();
ChatClient client = ChatClient.builder(model)
        .defaultAdvisors(MessageChatMemoryAdvisor.builder(memory).build())
        .defaultTools(new WeatherTools())
        .defaultSystem("...")
        .build();
```

**MessageChatMemoryAdvisor 行为（javap -c 实证，写内存时机）**：
- `before()`：按 conversationId 取历史与本次 instructions 合并；**第一条 SystemMessage 被移出并重插到 index 0（重排不剔除）**；随后把 `prompt.getLastUserOrToolResponseMessage()` 写入 memory（即**用户消息先落库再调 LLM** 的顺序由它保证）；
- `after()`：所有 AssistantMessage（**含带工具调用的**）+ ToolResponseMessage 都会入 memory；
- `adviseStream()`：用 `ChatClientMessageAggregator` 聚合流，**流结束后**回调 `after()` 写入。

⚠ 注意：`InMemoryChatMemoryRepository` 这个类名**在两份文档中均未出现**（只核对了接口和 `MessageWindowChatMemory` 的 Builder）。落地前建议自行在 jar 里确认实现类名（api-report 环境的 .m2 里有 1.1.8 全套 jar 可 javap）。

## 1.4 HITL / 工具执行拦截（如果需要对工具调用做审批与 SSE 可见性）

- `ToolCallingManager` 接口仅 2 方法：`resolveToolDefinitions(ToolCallingChatOptions)` + `executeToolCalls(Prompt, ChatResponse): ToolExecutionResult`；**toolContext 从 `prompt.getOptions()` 里取**（`instanceof ToolCallingChatOptions → getToolContext()`），没有独立入参；
- `DefaultToolCallingManager` 是 **final**，唯一 public 构造 3 参（ObservationRegistry, ToolCallbackResolver, ToolExecutionExceptionProcessor）——**不能继承，只能组合包装**；但 `ToolCallingManager.builder().build()` 可无参 build（`OpenAiChatModel` 静态块即如此）；生产环境直接注入 `ToolCallingAutoConfiguration` 提供的 `ToolCallingManager` Bean 再包装；
- 拒绝执行时构造与框架字节码一致的消息形状：

```java
ToolResponseMessage toolResponse = ToolResponseMessage.builder()
        .responses(assistant.getToolCalls().stream()
            .map(tc -> new ToolResponseMessage.ToolResponse(tc.id(), tc.name(), "REJECTED_BY_HUMAN"))
            .toList())
        .build();
return ToolExecutionResult.builder()
        .conversationHistory(List.of(assistant, toolResponse))
        .returnDirect(true)      // 可改 false 让模型继续
        .build();
```

- ⚠ `executeToolCalls` 在 `internalStream` 流内被**同步调用**——阻塞等待审批要放到 `boundedElastic`/专用线程，避免占死 Netty 线程；
- `ToolExecutionResult` 是**接口**（实现为 record `DefaultToolExecutionResult(List<Message>, boolean)`）；`ToolResponseMessage`、`ToolCallAdvisor` 构造是 protected，一律用 builder；`ChatGenerationMetadata` 包名是 `org.springframework.ai.chat.metadata`（不是 `chat.model`）且是接口。

## 1.5 OpenAI 兼容 DeepSeek 的配置项（properties 键名）

```yaml
spring:
  ai:
    openai:
      base-url: https://api.deepseek.com        # 默认 https://api.openai.com，必改
      api-key: ${DEEPSEEK_API_KEY}              # 无默认；缺省时自动配置直接 Assert 报错
      chat:
        completions-path: /chat/completions     # 默认 /v1/chat/completions，DeepSeek 必须覆盖
        # base-url / api-key 也可在此覆盖（chat 级优先于顶级）
        options:
          model: deepseek-flash                 # 默认 gpt-4o-mini（源码 DEFAULT_CHAT_MODEL）
          temperature: 0.7                      # 源码默认 0.7（官方文档表格写 0.8，以源码为准）
          max-tokens: 4096                      # DeepSeek 用 max_tokens，与 max-completion-tokens 互斥
          top-p: 0.9
          stream-usage: true                    # 流式末尾追加 usage chunk（= StreamOptions.INCLUDE_USAGE）
          parallel-tool-calls: false            # 建议 false，见 1.7
```

属性来源：`OpenAiConnectionProperties`（prefix `spring.ai.openai`）、`OpenAiChatProperties`（prefix `spring.ai.openai.chat`）、`OpenAiParentProperties`。完整 options 字段（`OpenAiChatOptions`）：`model, frequency-penalty, logit-bias, logprobs, top-logprobs, max-tokens, max-completion-tokens, n, modalities, audio, presence-penalty, response-format(type/name/schema/strict), stream-options/stream-usage, seed, stop, temperature, top-p, tools, tool-choice, user, parallel-tool-calls, store, metadata, reasoning-effort, verbosity, web-search-options, service-tier, prompt-cache-key, safety-identifier, extra-body, http-headers, tool-callbacks, tool-names, internal-tool-execution-enabled`。

其他开关：
- `spring.ai.model.chat=openai` 启用（`matchIfMissing=true` 默认即启用；`none` 关闭）；
- 重试：`spring.ai.retry.max-attempts=10`、`backoff.initial-interval=2s`、`backoff.multiplier=5`、`backoff.max-interval=3m`、`on-client-errors=false`、`on-http-codes` —— **仅对高层 `OpenAiChatModel` 生效**，手动 `OpenAiApi` 循环不享受 RetryTemplate；
- Maven：模块 `spring-ai-openai`，starter 为 `spring-ai-starter-model-openai`，版本走 `org.springframework.ai:spring-ai-bom:1.1.8`；
- **备选（1.1.8 新增原生 DeepSeek 模块）**：`spring-ai-deepseek`，`spring.ai.deepseek.base-url` 默认即 `https://api.deepseek.com`、`chat.completions-path` 默认 `/chat/completions`、`options.model` 默认 `deepseek-chat`、temperature 默认 1.0；原生模块多 `ChatCompletionMessage.prefix` 字段（Beta 前缀补全）。

## 1.6 流式输出转 SSE 的推荐写法

**高层（推荐主路径）**——文档骨架原文：

```java
return client.prompt()
        .user(userText)
        .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
        .toolContext(toolContext)
        .stream()                    // StreamResponseSpec
        .chatResponse()              // Flux<ChatResponse>
        .map(cr -> {
            Generation g = cr.getResult();
            String delta = g.getOutput().getText();
            // OpenAI 模块 reasoning 增量（仅流式路径存在该键，§J 字节码证据）
            Object reasoning = g.getOutput().getMetadata().get("reasoningContent");
            return reasoning != null ? "[思考] " + reasoning + delta : delta;
        });
// 仅要纯文本流可用 .stream().content() → Flux<String>
```

WebFlux 侧：`flux.map(c -> ServerSentEvent.builder(...).build())` 返回给前端。文档5补充的前端/代理侧要点：**用原生 `fetch` + `ReadableStream` 解析（支持 POST；EventSource 不支持 POST）**；Nginx 必须 `proxy_buffering off; proxy_cache off; proxy_read_timeout 3600s`，否则流式变批式。

**低层（手动 Agent Runtime，需要自己控制工具循环时）**——`Flux<ChatCompletionChunk> chatCompletionStream(ChatCompletionRequest)`：
- 内部 `bodyToFlux(String)` → `.takeUntil("[DONE]")` → `.filter("[DONE]")` → 逐行转 `ChatCompletionChunk`；
- `include_usage=true` 时**最后一帧 choices 为空、usage() 非空**，要单独处理发 usage 事件；
- SSE 事件建议：`event=token`（delta.content）、`event=reasoning`（delta.reasoningContent）、`event=tool_call`（合并完成的调用）、usage 事件。

## 1.7 DeepSeek 特有注意点（坑清单）

1. **路径**：必须 `base-url=https://api.deepseek.com` + `completions-path=/chat/completions`（两个默认值都是 OpenAI 的）；
2. **SSE**：DeepSeek 返回标准 SSE 且以 `data: [DONE]` 结束，与 `OpenAiApi` 完全兼容；**流式+工具调用支持**——`chatCompletionStream` 用 `OpenAiStreamFunctionCallingHelper` 的 `windowUntil + reduce` **自动合并 arguments 分片**（从首个 `delta.toolCalls` 非空 chunk 开窗，到 `finish_reason=tool_calls` 收窗），手动循环无需自己拼接；但合并实现只检查 `choices.get(0)`，且**同帧多个 tool call 会抛 `IllegalStateException("Currently only one tool call is supported per message!")"`** → 设 `parallel_tool_calls=false`；合并后 `ToolCall.index` 丢失，回填时不要依赖 index；
3. **reasoning_content**：`ChatCompletionMessage` 第 9 组件 `@JsonProperty("reasoning_content") String reasoningContent` **真实存在**，流式可直接读思维链增量；**致命坑：下一轮请求 messages 中不能携带 reasoning_content，否则 400**（回填 assistant 消息时传 null）；高层 `OpenAiChatModel` 不把 reasoningContent 暴露为消息文本，且**只在流式路径**进入 `AssistantMessage.getMetadata().get("reasoningContent")`（非流式 call 路径的元数据 Map 不含该键——javap 字节码实证）；原生 DeepSeek 模块才有 `DeepSeekAssistantMessage.getReasoningContent()`；文档6补充的实战结论：代理层统一注入 `thinking={"type":"disabled"}`，避免带 tools 时中间轮 reasoning_content 未回传导致 400（兼省 token）；
4. **token 上限**：用 `max_tokens`（DeepSeek 不接受 `max_completion_tokens`，两者互斥且 Builder 会告警互清）；
5. **temperature**：DeepSeek 允许 0~2；
6. **模型名**：`deepseek-flash` 不在 `DeepSeekApi.ChatModel` 枚举里（只有 deepseek-chat/deepseek-reasoner），但 `model` 是普通 String，直接传即可；
7. **多余字段**：`store/metadata/service_tier/safety_identifier` 等 OpenAI 专属保持 null；额外参数走 `extra_body`（`@JsonAnyGetter` 展平到请求顶层；DeepSeek 对未知参数可能 400，谨慎）；
8. **认证**：默认追加 `Authorization: Bearer <apiKey>`（仅当请求头没有 Authorization 且 apiKey 非 NoopApiKey）；想完全自控传 `new NoopApiKey()`；每次请求自动带 `User-Agent: spring-ai`。

## 1.8 手动 Agent Runtime 关键差异（若不走 ChatClient 高层）

- `OpenAiApi` 1.1.8 **只有 8 参公开构造 + Builder**（旧版 3 参构造已移除）；Builder 方法全集：`baseUrl/apiKey(ApiKey)/apiKey(String)/headers/completionsPath/embeddingsPath/restClientBuilder/webClientBuilder/responseErrorHandler/build`；构造内部 `clone()` 共享 Builder，不污染容器 Bean；
- **`ChatCompletionRequest` 在 v1.1.8 没有 Builder**（1.0.x 的 `builder()` 已删），是 32 组件 record；便捷构造器 4 个，**要同时带 tools + stream 只能走 32 参 canonical 构造**，建议封装工厂方法；`ToolChoiceBuilder.AUTO`（"auto"）、`StreamOptions.INCLUDE_USAGE` 常量真实存在；
- 非流式走 RestClient，流式走 WebClient（Reactor Netty 默认**无** response timeout，对 SSE 长流合适；只想兜底再设 `responseTimeout`）；
- `chatCompletionStream` 断言 `stream()==true`，`chatCompletionEntity` 断言 `false`，不能混用；终止枚举 `ChatCompletionFinishReason`（`STOP/LENGTH/CONTENT_FILTER/TOOL_CALLS/TOOL_CALL/UNKNOWN`）；
- 回填消息：assistant 带 tool_calls 的消息 content 为 null、**reasoningContent 必须 null**；tool 结果消息 `toolCallId` 必须对上，`ChatCompletionFunction.arguments` 是 **JSON 字符串**。

## 1.9 文档点名的其他坑

- PS 5.1 下 javap 嵌套类名要单引号包 `'$Builder'`（工具链问题，非 API 问题）；
- 官方文档 1.1.x 页面：`docs.spring.io/spring-ai/reference/1.1/api/chat/openai-chat.html` 与 `deepseek-chat.html`；
- 文档1结论一句话：**“手动用 `OpenAiApi` 反而最直接”**（指读 DeepSeek 思维链时）。

---

# 二、AI 侧边栏/工作区联动架构经验（文档3、4、5）

三份文档是同一问题的三场独立咨询（README 确认：ChatGPT 18 轮、DeepSeek 9 轮、Kimi 30 轮，DeepSeek 第 7 轮粘贴的正是 ChatGPT 的回答，可交叉对照）。

## 2.1 各自的核心架构方案

**文档3（ChatGPT，18 轮）——分层 + Task-centric + 最终收敛为“四层状态模型”**：
- 核心分层观：“**Spring AI ≠ Agent Runtime**”：

```text
│          Your Agent Runtime         │   ← Planner / Workflow / State Machine /
│                                     │      Re-planning / Human Approval /
│                                     │      Task Persistence / Frontend Interaction
│             Spring AI               │   ← ChatClient / ChatModel / Tool Calling /
│                                     │      Advisors / Memory / RAG / MCP
│          LLM / Tool Providers       │
```

- 会话模型：**Task-centric AI**——“AI 对话不能绑定页面生命周期，而应该绑定 Agent Task/Conversation。页面只是这个任务当前的 UI 工作区”；三个 ID 分工：`userId`=谁、`agentSessionId`=这个浏览器窗口里的 AI 上下文、`taskId`=AI 正在帮用户完成什么业务事情；
- AI 反问 = **Interrupt**，不是“聊天页面在等输入”：`{taskId, status: WAITING, interrupt: {type: USER_INPUT, question: ...}}`，刷新后重放该问题而非重新发起；
- **最终定稿（第 18 轮读完 CopilotKit 官方文档后修正）**——四层状态表：

| 类型 | 谁拥有 | 方向 | 例子 |
|---|---|---|---|
| 页面状态 | Vue | UI 内部 | 表单输入、弹窗开关 |
| **Agent Context** | UI | **UI → Agent 单向** | 当前页面、当前配置、当前选择 |
| **Shared State** | Agent + UI | **双向** | AI 任务进度、AI 生成结果、当前工作数据 |
| 业务数据 | 后端/DB | Tool 访问 | 配置、任务、用户、权限 |

原文结论：“如果某个数据只是 UI 拥有、Agent 只读，就不要用 Shared State，用 Agent Context”；“不要把你的整个业务数据库状态塞进 Shared State”；“主业务页面和右侧 Chat 本质上都是同一个 Agent State 的两个消费者”。

**文档4（DeepSeek，9 轮）——Redis Shared State + WebSocket 双向同步**：
- 方案：`AgentState`/`SharedState` 集中为一个 JSON（含 `conversationId/currentStep/form/status(WAITING_USER|AI_PROCESSING|WAITING_APPROVAL|COMPLETED)/pendingApproval`），存 Redis `conv:{id}:state`，通过 **WebSocket(STOMP `/topic/state/{id}`)** 广播；前端 Pinia 订阅更新；AI 工具里 `stateService.patchState(...)` 改状态并推送；
- 会话：`conv:{id}:messages`(List)/`state`/`context` + TTL 30 分钟；`GET /conversation/{id}` 恢复；多标签页用乐观锁/版本号防冲突；刷新流程 = 读 ID → GET 状态 → 重连 WebSocket → 按 status 渲染（WAITING_USER 显示输入框、AI_PROCESSING 显示处理中、WAITING_APPROVAL 显示审批按钮）；
- 对 CopilotKit Shared State 的解读：`useCoAgent` 的 `state/setState`；AG-UI 协议的 `STATE_SNAPSHOT`（全量）/`STATE_DELTA`（增量）/`STATE_PATCH`（前端改状态的补丁）；状态与 threadId 绑定做隔离；
- 结论：“不需要引入 CopilotKit，但可以借鉴它的 Shared State 思想……Redis + WebSocket 构建轻量级双向状态同步”。

**文档5（Kimi，30 轮）——前端 AI Runtime vs 后端 AI Runtime 的系统对比 + 向导场景落地**：
- 总判断：“**后端 AI Runtime 仍是绝对主流（~55%），‘前端轻编排 + 后端重编排’混合模式（~35%）增长最快，纯前端 AI Runtime（~10%）是趋势但尚未主流**”；但对“前端重交互（点击、输入、Excel 编辑）”场景明确改口：“前端 AI Runtime 是正确且主流的选择”；
- 通信：“AI 流式输出首选 SSE，需要双向控制（中断/取消、工具审批、协同）时才用 WebSocket”；
- 工具分两类：前端本地工具（操作 Pinia store，<16ms）+ 后端代理工具（前端注册但执行体里发 HTTP）——“后端仍然不知道‘保存’是一个 AI 工具”；
- 对**向导式业务**（正是新项目的“快速实施任务向导”）给出成体系的结论（详见 2.3/2.5）；
- 最终通用解法：**配置驱动任务引擎**——`templates/export.json`（steps/fields/tools 映射/systemPrompt）+ `UniversalWizard.vue` + `task-engine.ts`，“新增场景 = 新增一个 JSON 文件，不需要改引擎代码”。

> 对新项目的映射：新项目定为**后端 AI Runtime**（文档3 的分层图 + 文档6 的范式1 即此路线），文档5 的“前端 Runtime 更优”结论是针对纯前端交互场景的，但其 SSE/渐进披露/场景隔离/全局锁/配置驱动引擎等工程结论对前后端任一范式都适用（文档6 中两范式正是共享 tools.yaml/scenarios.yaml 单一事实源）。

## 2.2 状态同步/解耦的推荐模式与结论

三份文档 + 文档6 的落地验证，形成清晰的决策链：

1. **push（事件流同步）被否决**（文档6 的正式决策，直接教训自文档3 早期方案）：“**pull 否决 push**：不上 WORKSPACE_CHANGED 事件流——划不清‘影响决策’边界会退化成全量推送、token 膨胀；改‘**工具现查 + dataVersion 版本对比**’”。文档3 后期也自我修正：“我现在不会推荐你做‘实时 Workspace State 同步’……**Session 隔离 + 请求时上下文注入 + 显式引用 + Tool Calling**，比‘所有页面状态实时同步给 AI’要准确得多”。
2. **请求时上下文注入（Cursor/Copilot 模式）是第一版正解**（文档3 第 17-18 轮）：每次用户发消息，前端组装 Request Context（当前页面、当前业务对象、当前表单关键数据、用户选中内容、可用 Frontend Tools），AI 只处理“历史对话 + 本次明确提供的上下文”，不偷偷监听页面。
3. **版本号防“AI 用旧数据决策”**（文档3 + 文档6）：Task State 带 `version`；AI 开始执行时记录 `stateVersion`，执行工具前比对 `currentVersion`，不一致则停止/Replan/重查。文档6 的落地语义是“**提示并强制现查，不阻断**”——“配置场景下用户手工改后再确认是合法路径；仅快照缺失/损坏才拒绝”。
4. **若确需双向**（文档3 第 18 轮 / 文档4）：用 Shared State（AG-UI 的 STATE_SNAPSHOT/STATE_DELTA 思想），但只放“AI 与页面共同协作的运行时工作状态”，业务真实数据仍走 Tool 查 DB。
5. **数据准确性铁律**（文档7 非功能需求，架构层依据）：“AI 的写操作决策必须基于决策时刻的最新工作区数据，不得依赖对话历史中的旧数据”。

## 2.3 工具渐进式披露（不同页面/步骤不同工具）

文档5 给出三种业界解法 + 一个极简版：

1. **动态工具激活**：Vercel AI SDK 的 `prepareStep: async ({stepNumber, messages}) => ({ activeTools: getToolsForPage(currentPage) })`；在 Spring AI 侧等价物就是**每轮请求按当前 scenario/step 组装 `prompt().tools(...)`/`toolCallbacks(...)`**；
2. **元工具切换**：给模型一个 `switchContext/navigateTo` 元工具（描述里写清各页面能做什么），跨页时先切换再执行；
3. **动态工具发现**（Anthropic Advanced Tool Use 模式）：全量注册但标记 deferred，模型初始只见 `searchTools` + `getCurrentContext` 元工具，需要时语义搜索发现；
4. **极简版**（用户喊“太复杂了”之后）：“一页一套工具，路由切换就换工具……跨页面 = 路由跳转 + 重新对话”。

**流程式场景（新项目的向导）的正解是“工作流状态机限定工具”**（文档5 第 7 轮，最接近新项目）：

```text
Workflow Definition: steps: [{id, name, page, tools: [...], completionCondition, next}]
Workflow Store (Pinia): currentStepId / stepHistory / workflowData / currentTools
AI 每轮只见「当前步骤 tools + globalTools(navigate_step/get_workflow_state/complete)」
System Prompt 注入：【当前进度】【当前步骤】【可用工具】【工作流数据】【操作规则】
```

关键原则表（原文）：

| 原则 | 说明 |
|---|---|
| 预定义流程 | 工作流步骤预先定义，AI 是“执行者”不是“规划者” |
| 状态驱动页面 | 页面跳转由 goToStep 触发，不是 AI 直接操作路由 |
| 步骤隔离工具 | 每步只暴露该步骤的工具，AI 不会“手滑”调用其他步骤的工具 |
| 数据流明确 | workflowData 跨步骤共享，上一步输出自动成为下一步输入 |
| 可中断可恢复 | 工作流状态持久化，刷新/重开可从断点继续 |

**场景隔离三层防御**（文档5 第 20 轮，直接可抄）：
1. **动态工具注册**（最关键）：只传当前场景工具，“AI 看不到其他场景的工具，自然调不了”；
2. **System Prompt 场景约束**：明确列出可用工具 + forbiddenHint（“当前是导出场景，不要调用导入相关工具”）；
3. **执行层兜底校验**：每个工具执行前 `if (wizardType !== sceneType) return {success:false, error:"..."}`，即使幻觉也拦截。

文档6 的落地验证（后端 Runtime 版）：`GET /api/ai/tools?scenario=X&step=Y`——"AI 每轮只能看到当前步骤暴露的工具”；收益三条："token 优化 + 实时数据（按需查询）+ 防越权（错误步骤调不到错误工具）"；且“System prompt 只描述能力、不注入全量业务数据——AI 通过工具链式现查（list_config_defs → get_config_def）"。

## 2.4 会话管理（浏览器页签 session、刷新保持）

**最终共识（文档3 第 12-16 轮，与文档6/7 的正式需求一致）**：

- **`sessionStorage` 存 `agentSessionId`**（不叫 conversationId，因为用户看不到“对话”概念）。生命周期表（原文）：

| 操作 | sessionStorage |
|---|---|
| F5 刷新 / SPA 路由切换 / 页面跳转 | ✅ 保留 |
| 关闭 Tab / 关闭窗口 | ❌ 清除 |
| 新开 Tab / 新浏览器窗口 | 新的一份（隔离） |
| 浏览器崩溃恢复 | 不应依赖 |

- **sessionStorage 只存 ID，真实会话数据存后端**（Redis/DB）：“sessionStorage 适合保存‘这个 Tab 当前是哪一个 Agent Session？’而不是保存 Agent Session 的全部数据”；
- **关窗后不恢复、不猜测**：“新窗口不能仅根据 userId 从 Redis 的多个 Session 中猜一个出来”——userId→Session 是 1:N，“必须有明确的恢复依据：sessionId 精确恢复，或 taskId 找到关联 session 恢复”（业务任务入口是恢复凭证，不是历史对话列表）；
- **刷新不触发 Agent Resume**（易犯错误）：“页面加载 → Load Task → Render State → 等待用户动作”，只有用户提交回答才 `POST /interrupt/{id}/resolve → Resume`，否则会“再问一遍”；
- Redis 里的多 Session 只是运行时数据，按状态设 TTL（文档3 示例：ACTIVE 2h / WAITING 24h / RUNNING 不立即删 / COMPLETED、FAILED 30min）；**AI 正在执行时浏览器关了，任务继续跑完再过期**；
- sessionStorage 陷阱：`window.open` 新窗口可能拿到 opener 的 sessionStorage 副本——初始化时要有明确的创建规则而非依赖“天然隔离”；
- 后端接口形态：`POST /api/agent/chat {agentSessionId, message, context}`，所有 AI 状态以 `userId + sessionId` 为隔离边界；
- 文档6 的实战补充（已验证落地）：**任务数据 localStorage（跨刷新）、对话 sessionStorage（随窗口）**分开存；“对话随窗口”三条需求：窗口独立、刷新存活、关窗即毁。

## 2.5 文档批评过的问题与教训（不要重蹈覆辙）

1. **AI 自动跨页面跳转是反模式**（文档5 第 11-13、15、29-30 轮反复论证）：“**页面导航是用户主权，不是 AI 主权**”“业界不做‘AI 自动跨页面跳转’。页面是用户的领地，AI 只动数据，不动导航”；数据佐证：GitHub Copilot 多步连续自动执行使用率仅 **1%**；正确做法 = 后端静默跑完 API 前端不动，或 AI 给可点击的“跳转建议”由用户点。
   - 但**新项目注意**：文档5 第 29-30 轮承认“如果一定要做”，给出折中方案——**前端状态机驱动 + AI 每步执行一次 + 检查点（auto:false 步骤等用户确认）**；且文档6 的 ai-example 实际上**做了**“AI 通过 navigate_step 工具驱动步骤流转”，其安全前提正是“路由由 navigate_step 工具 + TaskService 状态机共同保证 + 步骤级工具隔离 + 跨场景跳步 400 拒绝”。即：**可以做 AI 驱动向导步骤，但必须状态机护航 + 每步工具限定 + 高危步骤确认**。
2. **Wizard ≠ AI Workflow**（文档5 第 23、26-28 轮）：“选择场景→调整数据→确认数据→执行任务”是 **Wizard UI** 不是 AI Workflow（LangGraph 编排）；“用户主动填表单 = 普通交互。AI 自动执行时停下来问用户 = HITL”——HITL 严格指 AI 自动流程中的暂停审批点；对该场景“**根本用不上** LangGraph 这类工作流引擎”。
3. **Spring Statemachine 已死**（文档3 第 4 轮，用户纠错后 ChatGPT 确认）：2026-07-05 仓库已归档进 spring-attic，“Spring Statemachine is no longer maintained”——“不要引入”；替代顺序：普通对话→Tool Calling；HITL→**自己实现 Interrupt/Resume**；多步任务→DB 持久化 Task/Step/State；长时/宕机恢复→Temporal/Flowable/Camunda；“自己实现一个 200~500 行左右的 Agent Task Runtime，比引入 StateMachine 框架更合理”。状态设计要点：**Task 状态保持粗粒度（PLANNING/EXECUTING/WAITING/COMPLETED/FAILED/CANCELLED），“为什么 WAITING”由 Interrupt 类型表达（HUMAN_APPROVAL/HUMAN_INPUT/HUMAN_SELECTION/FRONTEND_ACTION）**，避免 WAITING_FOR_XXX 状态爆炸。
4. **不要过度工程**（文档6 明确“不做清单”）：STATE_DELTA JSON Patch 增量流、BroadcastChannel 多标签实时同步、content hash、独立 meta LLM 生成建议——全部显式拒绝；“任务可以独立于对话存在”（scenario/step/plan 存 AgentState 而非对话历史，Rasa slot 同构，“任何消息压缩都不破坏流程”）。
5. **打断语义**（文档6）：“轮次边界停止 + 保留现场（AgentState 可答‘刚才做到哪了’），不做指令级硬中断、不做自动回滚”。
6. **LLM 传输层健壮性**（文档6 改造 H）：错误三分类（429/5xx 退避、401/400 干预、403 终止）+ 指数退避（1s×2^n+jitter，≤3 次，尊重 Retry-After）+ 超时 180s + **断连不自动重跑**（仅提示重发）。
7. **ChatGPT 对“用户粘贴的另一 AI 结论”的评审方法论**（文档3 第 17 轮，给 80 分并列出对的/不够准确的）——值得作为后续引入外部建议时的甄别范式：大方向对但“业界主流就是业务对象绑定”不是唯一主流、“根本没有人做状态同步”不成立（CopilotKit/AG-UI 就是）、“AI 反问到一半切走了回来答错算用户的”在企业场景不能这么粗暴。

---

# 三、业务需求要点（文档6、7、8）

## 3.1 配置项数据结构与任务流程（文档6 + 文档7）

- **业务领域**：“AI 辅助的配置管理系统（配置项增删改查 + Excel 导入导出）”；种子数据“4 个配置定义 **CONFIG_A/B/C/D**（DataSeedService 初始化）”。配置项的形态是**配置定义（含字段 schema）**——工具里有 `list_config_defs`（列摘要）与 `get_config_def`（字段详情）两级查询，SpreadJS 表格“按配置定义动态渲染，利用原生校验/必填/下拉减少自写代码”。两份文档未给出配置定义的完整字段级 schema（那在仓库源码里），但明确了它是**动态的、Schema 驱动的**（动态配置项管理）。
- **四个场景与步骤状态机（确切要求）**：

| 场景 | 代码 | 步骤状态图 |
|---|---|---|
| 导出配置 | EXPORT | SELECT_SCENARIO → SELECT_DEFS → QUERY_COND → RESULT |
| 导入配置 | IMPORT | SELECT_SCENARIO → VIEW_DEFS → PRECHECK → REVIEW → PUBLISH |
| 新增配置 | ADD | 同 IMPORT 序列，数据以新条目方式录入 |
| 修改配置 | MODIFY | 同 IMPORT 序列，数据在现有条目上编辑 |

- **规则（文档7 原文）**：“任务必须先选择场景才能进入后续步骤，不允许跨场景跳步”；“任务从创建起即被保存，刷新或重开页面后可继续进行”；“顶部进度条、当前页面、任务状态三者始终保持一致”（文档6：`el-steps` 进度条与路由、任务状态**三方联动**，App.vue watch currentStep → stepToRoute；`scenario=null` 时只允许 SELECT_SCENARIO，跨场景跳步 400 + 前端拦截 + displaySteps 兜底）。

## 3.2 AI 可执行操作与风险分级（文档7 §4 + 文档6 的 13 工具表）

文档7 授权范围：流程导航（切场景/跳步骤/完成/结束）、信息查询（工作区状态/列配置项/字段详情）、数据选择（批量选/取消）、表格加工（批量改字段/删行/查找替换）、流程执行（一键跑完整导出或发布）、文件操作（Excel 导入/导出）。

文档6 的 13 工具信任分级（安全需求核心，“AI 永远不能越权直接执行破坏性操作”）：

```
AUTO     自动执行（只读/导航/选择）   → watch 自动执行 + autoExecutedCallIds 去重防循环
CONFIRM  用户确认（破坏性/不可逆）    → 工具卡片「确认执行」按钮
CONFIRM+ 二次确认（run_flow 高危）    → 卡片按钮 + ElMessageBox 二次弹窗
INPUT    表单输入（collect_user_input / excel_import）→ SchemaFormRenderer 渲染表单/上传卡片
```

13 工具清单（执行位置 FRONTEND/BACKEND + 适用步骤）：`navigate_step`（全步骤）、`get_workspace_state`、`list_config_defs`、`get_config_def`、`select_definitions`、`confirm_complete`、`table_batch_set_field`（需确认）、`table_delete_rows`（需确认）、`table_replace_values`（需确认）、`run_flow`（**二次确认**）、`collect_user_input`（表单）、`excel_import`（表单/上传）、`excel_export`（自动）。

## 3.3 用户输入收集与 Excel 能力

- **8 类控件**：text / textarea / number / boolean / single_select / multi_select / button_group / date（后增 file 支持 excel_import）；设计原则：“前端只做 required/min/max/pattern 最小契约校验，语义校验交给模型”；选型过程：五方案对比（Adaptive Cards / JSON Schema Form / Slack Block Kit / LangChain HumanInputTool / 多轮问答）后选 **Schema-driven 表单卡片 + pause-resume**，“零新协议复用现有暂停-恢复机制”。
- **Excel（SpreadJS ExcelIO，纯浏览器）**：
  - 模板下载：离线 Workbook + **内嵌 Excel 原生 DataValidation**（下拉/数字范围/必填），Excel/WPS 打开即用；
  - 导入：表头归一化（trim+半角+忽略大小写+去 `* ` 前缀）→ 类型转换 → 必填/选项校验 → batchSave 灌入；
  - 在线编辑：只读↔编辑切换（`:key` 强制重挂切 isProtected）；
  - 导出：单配置 exportExcelByRows / 多 sheet 合并 exportExcelMultiSheet（sheet 名截断 31 字符 + 非法字符替换）；
  - 边界：select 长选项（join >200）改隐藏列/辅助 sheet + DV 公式引用规避 Excel List 255 字符上限；>5MB 文件提示 + `setTimeout(0)` 防冻结；
  - 已修 4 bug（commit ed0b7c2）：预览 100 条硬编码、表格不展示、导出缺表头（ExcelIO 不导 colHeader 区 → template 模式改写 viewport 行 0）、DV 看似未生效（同根因）。

## 3.4 会话与状态需求（文档7 §5 + 文档6 改造 A-H）

文档7 原文七条：对话随窗口（独立/刷新保留/关窗即毁/不做跨窗口历史列表）；任务独立保存；**中断恢复**（等待确认或表单时刷新，恢复后重现该确认/表单可继续提交）；**等待超时**（约 10 分钟自动失效，提示重新发起）；**变更感知**（等待期间用户手工改了数据，恢复时 AI 应被告知“数据已变化”并先重查再决策，不得用旧数据）；**执行打断**（随时停止，停止发生在当前步骤完成之后，现场保留，可问“做到哪了”）；**断连与重试**（有限自动重试；中断后不自动重复已执行操作，仅提示重发）。

文档6 的痛点→改造映射（7 痛点 → A-H）：P1/P2 会话随窗口→sessionStorage；P3 pending 交互刷新恢复→`GET /api/ai/pending`（检测尾部未应答 toolCall 重建卡片）；P4 无限悬挂→WAITING_TOOL TTL 10min + 过期拒绝回灌 + 前端倒计时置灰；P5 挂起期间手工改数据→ToolResultReq 补 workspaceState 快照 + dataVersion 对比（提示不阻断）；P6 手动操作不被感知→三拦截点（store `$onAction` 白名单 + 路由 afterEach + SpreadJS EditEnded）；P7 打断/穿插/上下文→prompt 行为准则 + 轮次边界停止 + 窗口化 N=20 + 大结果截断；H 传输层健壮性（见 2.5 第 6 条）。

## 3.5 硬约束（文档6 memory/project-memory.md 的不可谈判项）

| 类别 | 约束 |
|---|---|
| 技术栈 | Spring Boot 3.5.14 + JDK 21；Vue3 Composition API + Vite + Pinia + Element Plus；表格 SpreadJS（新项目为 17.1.5，旧项目 17.1.10） |
| AI | DeepSeek API（OpenAI 规范），温度 0.2，max-token 4096；**必须 Function Calling，禁止文本 JSON 解析（可靠性）** |
| 密钥安全 | `DEEPSEEK_API_KEY` 仅存后端环境变量，绝不硬编码；前端占位符；pre-commit 强制密钥扫描 |
| 持久化 | 任务创建即持久化、刷新可恢复 |
| 会话 | 对话 sessionStorage、任务 localStorage |
| 状态一致性 | **pull 模式（工具现查 + dataVersion），不上 push 事件流** |
| 工具定义 | **全部集中在后端 YAML（tools.yaml + scenarios.yaml 单一事实源），前端只传工具名 + 执行逻辑** |
| 端口/参数 | 后端 8081、前端 5173/5174；read-timeout 180s、max-retries 3、waiting-tool-ttl 10min |
| 界面 | 全中文 |

## 3.6 范围边界（文档7 §7，明确不做）

不做多用户协作/权限体系/审批流引擎；不做跨窗口实时状态同步（业务上窗口相互隔离）；不做 AI 操作的自动回滚（失败后人工处理）；快捷建议已设计未实现，列下一迭代候选。其他缺口：ai-ui-vercel 缺 Plan-Execute、auto-compact 后置、SpreadJS 正式授权是上线硬门槛；测试策略为端到端浏览器验证，无单元测试，“适合演示/教学，不宜直接当生产模板”。

## 3.7 文档8（README.md）说了什么

README.md **不是项目文档**，而是三份对话记录的**提取说明与完整性核对报告**：
- 三份 .md 是用 Kimi Browser Extension 控制真实浏览器从 ChatGPT（18 问 18 答）、DeepSeek（9 问 9 答）、Kimi（30 问 30 答）抓取的完整对话，均经各平台自有接口/本地缓存交叉核对（ChatGPT 官方 conversations API 返回 87 条记录、分页无截断；DeepSeek 站点 IndexedDB `history-message` 18 条；Kimi 服务端 ListMessages 61 条）；
- 组织方式：`## 用户 #n` / `## ChatGPT/DeepSeek/Kimi #n`，思考过程与工具调用折叠在 `<details>` 中；
- 内容提示：“三个对话主题相近（Spring AI / Agent 模式 / 前端 AI Runtime / CopilotKit Shared State），DeepSeek 的第 7 条用户消息粘贴的正是 ChatGPT 的回答，可以做交叉对照”——这正是三份文档结论可互证的原因；
- 未包含：ChatGPT 联网搜索活动摘要、引用 URL 清单、附件内容。

---

## 对新项目最直接的落点（一句话提炼）

1. **后端 Runtime 主干**：`OpenAiChatAutoConfiguration` 注入/自建 `OpenAiChatModel` → `ChatClient.builder(model).defaultAdvisors(MessageChatMemoryAdvisor).build()` → 每轮按 scenario/step 用 `.tools(当前步骤工具对象...)` + `.advisors(a -> a.param(ChatMemory.CONVERSATION_ID, sessionId))` + `.stream().chatResponse()` 映射 SSE；前端工具走暂停-恢复（resumeToken / tool-result 回灌）。
2. **DeepSeek 配置四件套**：`base-url=https://api.deepseek.com`、`chat.completions-path=/chat/completions`、`options.model=deepseek-flash`、`options.max-tokens` + `parallel-tool-calls=false`；reasoning_content 只出不进（回传 400）。
3. **联动但解耦**：契约层六件套（状态查询/能力清单/dataVersion/命令/渲染/审批），pull 不 push，请求时上下文注入 + 工具现查；任务与对话分离（localStorage vs sessionStorage）。
4. **向导**：状态机（粗状态 + Interrupt 类型）护航 + 步骤级最小工具集 + 三层场景隔离 + 四档信任分级 + 配置驱动（YAML 单一事实源）。
