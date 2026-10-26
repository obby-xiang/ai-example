> **历史分析 / 过程证据 — 非现行基线，非验收结论**
>
> - 归档：2026-10-26 · `docs/evidence/history-recovery-2026-10-26/wire-tail/`
> - 来源：Kimi Code 会话 `<KIMI_SESSION>` / 子 agent `agent-6` 的 wire 末条 assistant 文本（消息序号 m=13；只读流式解析，非原始会话转写）
> - 历史时点：2026-10-05 17:38 → 2026-10-05 17:52（wire 事件时间窗，UTC+8）；当时模型：`Kimi/kimi-k3`
> - 原基线：正文未出现基线哈希，未经本批复验
> - 原始体量：21235 B / 17823 字符 / 152 行；行指纹覆盖率（本批复算）5.0%（最高单文件 4%：`docs/evidence/V9-claude-Flyway迁移幂等核验.md`）
> - 类别：**同源分支附录**
> - 与既有件关系：同上
> - 脱敏：路径 0 / 会话标识 0 / 身份 0 / 密钥 0 / 命名纪律 0
> - 正文为该条消息的完整文本，未删段、未改写结论；仅脱敏、占位符归一与行尾空白规整。当时结论不代表当前代码状态，不得作为当前缺陷或有效裁决引用。
调研完成。以下为基于 **v1.1.8 tag 源码逐字核实** 的报告（所有结论均附 GitHub raw 源码链接）。

# Spring AI 1.1.8 `spring-ai-deepseek` 模块调研报告

## 1. Maven 坐标与自动配置

**坐标确认**（见 [starter pom.xml](https://raw.githubusercontent.com/spring-projects/spring-ai/v1.1.8/starters/spring-ai-starter-model-deepseek/pom.xml)）：

```xml
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-model-deepseek</artifactId>  <!-- 版本由 spring-ai-bom 1.1.8 管理 -->
</dependency>
```

它拉入 `spring-ai-autoconfigure-model-deepseek`、`spring-ai-deepseek`、`spring-ai-autoconfigure-model-chat-client`、`spring-ai-autoconfigure-model-chat-memory`。

**自动配置类**：`org.springframework.ai.model.deepseek.autoconfigure.DeepSeekChatAutoConfiguration`（[源码](https://raw.githubusercontent.com/spring-projects/spring-ai/v1.1.8/auto-configurations/models/spring-ai-autoconfigure-model-deepseek/src/main/java/org/springframework/ai/model/deepseek/autoconfigure/DeepSeekChatAutoConfiguration.java)），暴露 Bean `deepSeekChatModel(...)`（返回 `DeepSeekChatModel`）。条件注解：

```java
@ConditionalOnProperty(name = SpringAIModelProperties.CHAT_MODEL, havingValue = SpringAIModels.DEEPSEEK, matchIfMissing = true)
// 即 spring.ai.model.chat=deepseek，缺省即启用
```

**配置属性**（[DeepSeekConnectionProperties](https://raw.githubusercontent.com/spring-projects/spring-ai/v1.1.8/auto-configurations/models/spring-ai-autoconfigure-model-deepseek/src/main/java/org/springframework/ai/model/deepseek/autoconfigure/DeepSeekConnectionProperties.java)、[DeepSeekChatProperties](https://raw.githubusercontent.com/spring-projects/spring-ai/v1.1.8/auto-configurations/models/spring-ai-autoconfigure-model-deepseek/src/main/java/org/springframework/ai/model/deepseek/autoconfigure/DeepSeekChatProperties.java)、[DeepSeekParentProperties](https://raw.githubusercontent.com/spring-projects/spring-ai/v1.1.8/auto-configurations/models/spring-ai-autoconfigure-model-deepseek/src/main/java/org/springframework/ai/model/deepseek/autoconfigure/DeepSeekParentProperties.java)）：

- `spring.ai.deepseek`（`DeepSeekConnectionProperties.CONFIG_PREFIX`）：`api-key`、`base-url`（默认 `https://api.deepseek.com`）
- `spring.ai.deepseek.chat`（`DeepSeekChatProperties.CONFIG_PREFIX`，继承 `DeepSeekParentProperties`）：`api-key` / `base-url`（覆盖全局）、`enabled`、`completions-path`（默认 `/chat/completions`）、`beta-prefix-path`（默认 `/beta`）
- `spring.ai.deepseek.chat.options.*`：内嵌 `DeepSeekChatOptions`（默认 `model=deepseek-chat`、`temperature=1.0`），可配 `model`、`temperature`、`max-tokens`、`top-p`、`stop`、`frequency-penalty`、`presence-penalty`、`logprobs`、`top-logprobs`、`response-format`、`tool-choice`。注意 `toolCallbacks`/`toolNames`/`internalToolExecutionEnabled`/`toolContext` 均标注 `@JsonIgnore`，**只能在代码里设，不走配置文件**。

```yaml
spring:
  ai:
    deepseek:
      api-key: ${DEEPSEEK_API_KEY}
      base-url: https://api.deepseek.com   # 或你的网关
      chat:
        options:
          model: deepseek-flash
          temperature: 1.0
```

## 2. `DeepSeekChatModel`（[源码](https://raw.githubusercontent.com/spring-projects/spring-ai/v1.1.8/models/spring-ai-deepseek/src/main/java/org/springframework/ai/deepseek/DeepSeekChatModel.java)）

- `public class DeepSeekChatModel implements ChatModel`；`ChatResponse call(Prompt)`、`Flux<ChatResponse> stream(Prompt)`。
- 构造：`DeepSeekChatModel.builder()`，方法：`deepSeekApi(...)`、`defaultOptions(DeepSeekChatOptions)`、`toolCallingManager(...)`、`toolExecutionEligibilityPredicate(...)`、`retryTemplate(...)`、`observationRegistry(...)`。也有两个 public 构造器。
- **传 ToolCallback**：通过 options，不走 ChatModel 方法参数：
  - `DeepSeekChatOptions.builder().toolCallbacks(ToolCallback... / List).toolNames(String... / Set).build()`
  - 或任何 `ToolCallingChatOptions` 实例（`buildRequestPrompt` 中 `instanceof ToolCallingChatOptions` → `ModelOptionsUtils.copyToTarget(toolCallingChatOptions, ToolCallingChatOptions.class, DeepSeekChatOptions.class)`）。运行时 options 的 toolCallbacks/toolNames **非空则整体覆盖** default（`ToolCallingChatOptions.mergeToolCallbacks`）。
- **关闭内部工具自动执行**：`DeepSeekChatOptions implements ToolCallingChatOptions`（接口，不是继承类），自己声明了字段 `@JsonIgnore private Boolean internalToolExecutionEnabled` + getter/setter；Builder 方法 `.internalToolExecutionEnabled(Boolean)`。判定链：
  - `ToolCallingChatOptions.isInternalToolExecutionEnabled(chatOptions)`：为 `null` 时取 `DEFAULT_TOOL_EXECUTION_ENABLED = true`（[源码](https://raw.githubusercontent.com/spring-projects/spring-ai/v1.1.8/spring-ai-model/src/main/java/org/springframework/ai/model/tool/ToolCallingChatOptions.java)）。
  - `DefaultToolExecutionEligibilityPredicate.test`：`isInternalToolExecutionEnabled(promptOptions) && chatResponse.hasToolCalls()`（[源码](https://raw.githubusercontent.com/spring-projects/spring-ai/v1.1.8/spring-ai-model/src/main/java/org/springframework/ai/model/tool/DefaultToolExecutionEligibilityPredicate.java)）。设 `false` 后 `internalCall`/`internalStream` 不再走 `toolCallingManager.executeToolCalls`，tool_calls 原样返回给调用方——**正好支撑你的手动 Agent Loop**。
  - 另一种全局方式：声明一个 `ToolExecutionEligibilityPredicate` Bean（自动配置用 `ObjectProvider<ToolExecutionEligibilityPredicate> ... .getIfUnique(DefaultToolExecutionEligibilityPredicate::new)` 注入）。

```java
DeepSeekChatOptions options = DeepSeekChatOptions.builder()
        .model("deepseek-flash")
        .toolCallbacks(myToolCallbacks)          // List<ToolCallback>
        .internalToolExecutionEnabled(false)     // 关键：手动控制工具执行
        .build();
Flux<ChatResponse> flux = deepSeekChatModel.stream(new Prompt(messages, options));
```

## 3. `DeepSeekAssistantMessage` 与 reasoning_content 回放（关键结论）

[DeepSeekAssistantMessage 源码](https://raw.githubusercontent.com/spring-projects/spring-ai/v1.1.8/models/spring-ai-deepseek/src/main/java/org/springframework/ai/deepseek/DeepSeekAssistantMessage.java)：

- `extends AssistantMessage`，字段逐字：`private Boolean prefix;`、`private String reasoningContent;`；getter `getPrefix()` / `getReasoningContent()`。
- Builder：`new DeepSeekAssistantMessage.Builder().content(...).reasoningContent(...).properties(...).toolCalls(...).media(...).prefix(...).build()`；静态工厂 `DeepSeekAssistantMessage.prefixAssistantMessage(String)` / `prefixAssistantMessage(String, String reasoningContent)`。
- **流式 chunk 取 reasoning**：`DeepSeekChatModel.buildGeneration` 中 `String reasoningContent = choice.message().reasoningContent();` 再 `.reasoningContent(reasoningContent)`——所以每个 chunk 的 `chatResponse.getResult().getOutput()` 强转为 `DeepSeekAssistantMessage` 后 `getReasoningContent()` 即是 delta 里的思维链文本。底层字段是 `DeepSeekApi.ChatCompletionMessage` record 的 `@JsonProperty("reasoning_content") String reasoningContent`（chunk 的 `ChunkChoice.delta()` 同类型）。

**回放问题——1.1.8 没有解决**。`DeepSeekChatModel.createRequest` 的 ASSISTANT 分支逐字如下：

```java
return List.of(new ChatCompletionMessage(assistantMessage.getText(),
        ChatCompletionMessage.Role.ASSISTANT, null, null, toolCalls, isPrefixAssistantMessage, null));
```

`ChatCompletionMessage` record 组件顺序为 `(rawContent, role, name, toolCallId, toolCalls, prefix, reasoningContent)`——**第 7 个参数 `reasoningContent` 被硬编码为 `null`**，只有 `prefix` 会从 `DeepSeekAssistantMessage.getPrefix()` 传播。且 record 标了 `@JsonInclude(Include.NON_NULL)`，请求 JSON 里根本不会出现 `reasoning_content` 字段。

结论：**即便你把带 reasoningContent 的 `DeepSeekAssistantMessage` 存进历史，1.1.8 的 ChatModel 也不会回放它**。对 deepseek-flash 思考模式"必须回放 reasoning_content 否则 400"的网关，可选方案：

1. 在 `DeepSeekApi` 低层自己组请求（`ChatCompletionMessage` record 本身支持 reasoning_content 序列化，缺口只在 ChatModel 的消息转换）；
2. Fork/patch `DeepSeekChatModel.createRequest`（该方法是包私有，可在同名包 `org.springframework.ai.deepseek` 下写子类/副本）；
3. 给 `WebClient.Builder`/`RestClient.Builder` 加拦截器在 HTTP 层改写请求体。

另注意：`internalStream` 末尾 `new MessageAggregator().aggregate(flux, observationContext::setResponse)`——[MessageAggregator](https://raw.githubusercontent.com/spring-projects/spring-ai/v1.1.8/spring-ai-model/src/main/java/org/springframework/ai/chat/model/MessageAggregator.java) 只拼接 `getText()` 且聚合结果**只回调给 observation，不追加进 Flux**。所以 `stream()` 发出的全是原始 chunk，入库用的完整 AssistantMessage（content + reasoning + toolCalls）要你自己聚合。

## 4. 流式 tool_calls 分片合并

**自动合并，发生在 `DeepSeekApi.chatCompletionStream`**（[DeepSeekApi 源码](https://raw.githubusercontent.com/spring-projects/spring-ai/v1.1.8/models/spring-ai-deepseek/src/main/java/org/springframework/ai/deepseek/api/DeepSeekApi.java)）：内部持有 `private DeepSeekStreamFunctionCallingHelper chunkMerger = new DeepSeekStreamFunctionCallingHelper();`，通过 `windowUntil(...)`（从首个含 toolCalls 的 chunk 起到 `isStreamingToolFunctionCallFinish` 即 `finishReason == ChatCompletionFinishReason.TOOL_CALLS` 的 chunk 止）+ `reduce(... chunkMerger.merge(...))` 把同一工具调用的分片合并成**一个** chunk。合并逻辑（[DeepSeekStreamFunctionCallingHelper](https://raw.githubusercontent.com/spring-projects/spring-ai/v1.1.8/models/spring-ai-deepseek/src/main/java/org/springframework/ai/deepseek/api/DeepSeekStreamFunctionCallingHelper.java)）拼接 `function.arguments`、保留首个非空 `id`/`name`/`type`，id 非空的新 ToolCall 开启下一个调用。因此 `DeepSeekChatModel.stream()` 里工具 chunk 已是完整的，`AssistantMessage.getToolCalls()` 直接可用。

限制/坑：

- `merge` 中 `if (current.toolCalls().size() > 1) throw new IllegalStateException("Currently only one tool call is supported per message!")`——**单个 chunk 内含多个并行 tool_calls 会直接抛异常**（跨 chunk 顺序到达的多个调用没问题）。
- 合并时用 5 参构造 `new ChatCompletionMessage(content, role, name, toolCallId, toolCalls)`，工具窗口内的 `reasoningContent`/`prefix` 会被丢弃（思考文本通常在 tool_calls 之前到达，不受影响）。
- 关于 `org.springframework.ai.model.tool.ToolCallAggregator`：**v1.1.8 不存在此类**——我逐一核实了 v1.1.8 与 main 分支的 `spring-ai-model/.../model/tool/` 目录（含 `internal/` 子包），均无该文件；Spring AI 没有官方流式聚合器，各模型模块自带 helper（OpenAI 是 `OpenAiStreamFunctionCallingHelper`，DeepSeek 是上面这个）。

## 5. 工具定义（`spring-ai-model` 模块）

- **`MethodToolCallback`**（`org.springframework.ai.tool.method`，final，[源码](https://raw.githubusercontent.com/spring-projects/spring-ai/v1.1.8/spring-ai-model/src/main/java/org/springframework/ai/tool/method/MethodToolCallback.java)）：
  ```java
  MethodToolCallback.builder()
      .toolDefinition(ToolDefinition)      // 必填
      .toolMethod(Method)                  // 必填
      .toolObject(Object)                  // 非 static 方法必填
      .toolMetadata(ToolMetadata)          // 可选
      .toolCallResultConverter(...)        // 可选
      .build();
  ```
- **`FunctionToolCallback<I,O>`**（`org.springframework.ai.tool.function`，[源码](https://raw.githubusercontent.com/spring-projects/spring-ai/v1.1.8/spring-ai-model/src/main/java/org/springframework/ai/tool/function/FunctionToolCallback.java)）：静态入口 4 个重载——`builder(String name, Function<I,O>)`、`builder(String name, BiFunction<I,ToolContext,O>)`、`builder(String name, Supplier<O>)`、`builder(String name, Consumer<I>)`；Builder 方法 `description(...)`、`inputSchema(String)`、`inputType(Type)` / `inputType(ParameterizedTypeReference<?>)`、`toolMetadata(...)`、`toolCallResultConverter(...)`。`inputSchema` 不给时用 `JsonSchemaGenerator.generateForType(inputType)` 自动生成。
- **`ToolDefinition`**（`org.springframework.ai.tool.definition`，[源码](https://raw.githubusercontent.com/spring-projects/spring-ai/v1.1.8/spring-ai-model/src/main/java/org/springframework/ai/tool/definition/ToolDefinition.java)）：接口方法逐字 `name()`、`description()`、`inputSchema()`（JSON 字符串）；实现是 record `DefaultToolDefinition(String name, String description, String inputSchema)`，用 `ToolDefinition.builder().name(...).description(...).inputSchema(json).build()`。
- **从 JSON Schema Map 直接构建**：没有现成的 "schema-only ToolCallback" 类，但两条路都通：
  1. `ToolCallback` 接口很小（`getToolDefinition()`、`getToolMetadata()`、`call(String)`、`call(String, ToolContext)`），自实现 + `DefaultToolDefinition`（Map→JSON 用 `JsonParser.toJson`/`ModelOptionsUtils.toJsonString`）；
  2. `FunctionToolCallback.builder(name, (Map<String,Object> in) -> ...).inputType(new ParameterizedTypeReference<Map<String,Object>>(){}).inputSchema(json).build()`。
- **`ToolMetadata`**：`ToolMetadata.builder().returnDirect(true).build()`——`returnDirect=true` 时工具结果直接返回客户端、不再回调模型（对"暂停 Loop 等前端"类工具是官方机制；`DefaultToolCallingManager` 里 `returnDirect = toolCallback.getToolMetadata().returnDirect()`）。
- **`ToolCallingManager`**（`org.springframework.ai.model.tool`，[接口](https://raw.githubusercontent.com/spring-projects/spring-ai/v1.1.8/spring-ai-model/src/main/java/org/springframework/ai/model/tool/ToolCallingManager.java)、[默认实现](https://raw.githubusercontent.com/spring-projects/spring-ai/v1.1.8/spring-ai-model/src/main/java/org/springframework/ai/model/tool/DefaultToolCallingManager.java)）：两个职责——`resolveToolDefinitions(ToolCallingChatOptions)`（ChatModel 内部用它生成请求里的 `tools` 参数，你手动控制执行时它依然会被调用来下发工具定义）；`executeToolCalls(Prompt, ChatResponse)` → `ToolExecutionResult`（`conversationHistory()`、`returnDirect()`）。内部执行逻辑：逐个 toolCall 找 ToolCallback 调 `call(arguments, toolContext)`，结果组装成 `ToolResponseMessage`（`new ToolResponseMessage.ToolResponse(toolCall.id(), toolName, toolCallResult)`），history = 原消息 + AssistantMessage + ToolResponseMessage。

## 6. ChatMemory 与消息体系（均在 `spring-ai-model` 模块）

- **`MessageWindowChatMemory`**（`org.springframework.ai.chat.memory`，[源码](https://raw.githubusercontent.com/spring-projects/spring-ai/v1.1.8/spring-ai-model/src/main/java/org/springframework/ai/chat/memory/MessageWindowChatMemory.java)）：`MessageWindowChatMemory.builder().chatMemoryRepository(...).maxMessages(20默认).build()`；方法 `add(String conversationId, List<Message>)`、`List<Message> get(String conversationId)`、`clear(String conversationId)`。SystemMessage 特殊：新 SystemMessage 替换旧的；淘汰时保留 SystemMessage。
- **`InMemoryChatMemoryRepository`**（同包，[源码](https://raw.githubusercontent.com/spring-projects/spring-ai/v1.1.8/spring-ai-model/src/main/java/org/springframework/ai/chat/memory/InMemoryChatMemoryRepository.java)）：`ConcurrentHashMap` 实现 `ChatMemoryRepository`（`findConversationIds`/`findByConversationId`/`saveAll`/`deleteByConversationId`）。
- **消息构造**：`new SystemMessage(String)`、`new UserMessage(String)`、`AssistantMessage.builder().content(...).properties(...).toolCalls(List<AssistantMessage.ToolCall>).build()`；`AssistantMessage.ToolCall` 是 record `(String id, String type, String name, String arguments)`（DeepSeek 模块里用 `new AssistantMessage.ToolCall(id, "function", name, arguments)`）。
- **`ToolResponseMessage`**（[源码](https://raw.githubusercontent.com/spring-projects/spring-ai/v1.1.8/spring-ai-model/src/main/java/org/springframework/ai/chat/messages/ToolResponseMessage.java)）：
  ```java
  ToolResponseMessage.builder()
      .responses(List.of(new ToolResponseMessage.ToolResponse(toolCallId, toolName, resultJson)))
      .build();
  ```
  内嵌 record `ToolResponse(String id, String name, String responseData)`——`id` 就是 tool_call_id。`createRequest` 的 TOOL 分支把每条 ToolResponse 展开成 `new ChatCompletionMessage(tr.responseData(), Role.TOOL, tr.name(), tr.id(), null)`，且断言 `id != null`（`"ToolResponseMessage must have an id"`）。
- ⚠️ 窗口淘汰可能把 `assistant(tool_calls)` 与其 `tool` 响应消息拆开（assistant 被淘汰、tool 消息留下，或反之），DeepSeek 会因 tool_call_id 不匹配返回 400——`maxMessages` 要留足余量。

## 7. 重试（`spring.ai.retry.*`）

**生效，但只覆盖非流式**。[SpringAiRetryProperties](https://raw.githubusercontent.com/spring-projects/spring-ai/v1.1.8/auto-configurations/common/spring-ai-autoconfigure-retry/src/main/java/org/springframework/ai/retry/autoconfigure/SpringAiRetryProperties.java)（`CONFIG_PREFIX = "spring.ai.retry"`）：`max-attempts`(10)、`backoff.initial-interval`(2s)、`backoff.multiplier`(5)、`backoff.max-interval`(3min)、`on-client-errors`(false)、`on-http-codes`、`exclude-on-http-codes`。[SpringAiRetryAutoConfiguration](https://raw.githubusercontent.com/spring-projects/spring-ai/v1.1.8/auto-configurations/common/spring-ai-autoconfigure-retry/src/main/java/org/springframework/ai/retry/autoconfigure/SpringAiRetryAutoConfiguration.java) 提供 `RetryTemplate` + `ResponseErrorHandler` Bean，`DeepSeekChatAutoConfiguration` 注入二者。

关键限制：`DeepSeekChatModel.internalCall` 里 `retryTemplate.execute(ctx -> this.deepSeekApi.chatCompletionEntity(request))`——**只有同步 `call()` 走重试**；`internalStream` 直接 `this.deepSeekApi.chatCompletionStream(request)`，不经 `RetryTemplate`，且 `DeepSeekApi` 的 `webClient` 构建时**没有**挂 `responseErrorHandler`（流式 4xx/5xx 由 `retrieve()` 默认抛 `WebClientResponseException`，不重试）。你的场景是流式 Agent Loop，重试要自己包（如 Reactor `retryWhen`）。

## 8. 已知坑汇总

1. **reasoning_content 不回放**（见第 3 节）——对 deepseek-flash 思考模式是最大障碍，需要第 3 节的三种绕行之一。
2. **`deepseek-flash` 不在枚举里，但无影响**：`DeepSeekApi.ChatModel` 只有 `DEEPSEEK_CHAT("deepseek-chat")`、`DEEPSEEK_REASONER("deepseek-reasoner")`；model 本质是 String，`DeepSeekChatOptions.builder().model("deepseek-flash")` 或配置 `spring.ai.deepseek.chat.options.model` 均可，仅 `builder.model(DeepSeekApi.ChatModel)` 重载受枚举限制。`DeepSeekApi.DEFAULT_CHAT_MODEL = ChatModel.DEEPSEEK_CHAT`。
3. **prefix 字段**：`DeepSeekAssistantMessage.getPrefix()` 为 true 时，`DeepSeekApi.getEndpoint` 检测到任一消息 prefix=true 就把端点切到 `betaPrefixPath + completionsPath`（默认 `/beta/chat/completions`）——是 **URL 路径前缀**而非 beta 请求头，由 `spring.ai.deepseek.chat.beta-prefix-path` 配置。
4. **流式无 usage**：`ChatCompletionRequest` 没有 `stream_options`/`include_usage` 字段，流式 chunk 的 usage 基本为空（`EmptyUsage`）。
5. **并行 tool_calls**：单 chunk 多 toolCalls 抛 `IllegalStateException("Currently only one tool call is supported per message!")`（见第 4 节）。
6. **聚合丢 reasoning**：`MessageAggregator` 只拼 `getText()`，聚合 AssistantMessage 是普通 `AssistantMessage`（无 reasoningContent），且聚合结果不进 Flux。
7. **工具相关 options 不可配置化**：`toolCallbacks`/`toolNames`/`internalToolExecutionEnabled` 均 `@JsonIgnore`，只能代码设置。
8. **ChatMemory 窗口拆散 tool 对**导致 400（见第 6 节）。
9. **流式不重试**（见第 7 节）。
10. `ToolCallingChatOptions.DEFAULT_TOOL_EXECUTION_ENABLED = true`：`internalToolExecutionEnabled` 不设置时默认开启内部执行，重构时**必须显式设 `false`**，否则工具会被框架同步执行掉（流式下还在 `Schedulers.boundedElastic()` 上执行）。

**重构可行性一句话**：除"reasoning_content 回放"外，DeepSeek 原生模块完全覆盖你的需求（手动工具执行用 `.internalToolExecutionEnabled(false)`、流式 tool_calls 已自动合并、`DeepSeekAssistantMessage.getReasoningContent()` 逐 chunk 拿思维链）；回放缺口需要 patch `createRequest`、HTTP 拦截改写，或继续在 `DeepSeekApi` 低层自己组请求体（`ChatCompletionMessage` record 本身支持 `reasoning_content` 序列化）。
