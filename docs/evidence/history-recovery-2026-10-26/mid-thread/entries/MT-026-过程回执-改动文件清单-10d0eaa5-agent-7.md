<!-- 历史中间稿证据条目（自动生成头部，非正文）。源 = Kimi Code assistant wire 文本；不含 think / 工具输出 / 原始用户消息。 -->

# MT-026 · 过程回执 · 改动文件清单（10d0eaa5 / agent-7）

> **项目归属**：**项目硬锚点**（正文命中 ai-example 路径 / 项目类名 / 项目文档名 / 项目 UI 概念）。
> **归属依据**：正文命中：`backend/src/`、`backend/src/main/java`、`AiChatService.java`、`AiSessionStore.java`。
> **稿态**：历史中间稿 —— 该 wire 第 `m56` 条 assistant 消息（m 按 wire 内 assistant 消息计，含工具调用轮）；本条是该 wire 第 `mt7` 条有正文的 assistant 消息（共 15 条有正文），末条有正文消息为 `m76`。**非现行基线、非验收结论、非当前缺陷清单**。
> **来源**：会话 `10d0eaa5` / agent `agent-7`（`main` = 主会话）；原字符 4,014（链内 1 段、合计 4,014）。
> **链号 / 类别**：`MT-026` / **过程回执**；判定：过程回执（自证/元数据/交付回报）；**不是用户批准**。
> **轮次**：位于该 wire 第 1 个用户轮之后；相邻用户轮含决断类字样（仅记布尔，未复制用户原文）。
> **去重**：行指纹对「仓内 + 落盘语料」覆盖率 `12%`（<60% 视为仓内无等价件）；同 wire 后文覆盖率 `12%`。
> **与末条归档（138 件）的关系**：末条 `m76`（3,604 字符）未入末条归档。
> **脱敏**：路径 0 / 会话标识 0 / 身份 0 / 密钥 0 / 旧仓名与同源仓名 0 处。

---

重构完成，`mvn -q package -DskipTests` 实际跑到 **BUILD SUCCESS**（exit 0，产物 `backend/target/quickstart-backend-1.0.0.jar` 68.8MB 新鲜生成）。

## 改动文件清单

| 文件 | 改动 |
|---|---|
| `backend/pom.xml` | `spring-ai-starter-model-openai` → `spring-ai-starter-model-deepseek`（BOM 1.1.8 不变） |
| `backend/src/main/resources/application.yml` | 删除 `spring.ai.openai.*`；改为 `spring.ai.deepseek.api-key`（`${DEEPSEEK_API_KEY:sk-...}`）+ `chat.options.model=deepseek-flash`（base-url/completions-path 走默认） |
| `backend/src/main/java/org/springframework/ai/deepseek/ReasoningAwareDeepSeekChatModel.java` | **新增**，缺口补丁类（见下） |
| `backend/src/main/java/com/example/quickstart/config/DeepSeekAiConfig.java` | **新增**，`@Bean @Primary DeepSeekChatModel`（自动配置 `ConditionalOnMissingBean` 退让）+ `@Bean ChatMemory`（`MessageWindowChatMemory` + `InMemoryChatMemoryRepository`，maxMessages=80） |
| `backend/src/main/java/com/example/quickstart/ai/AiChatService.java` | **重写**：`DeepSeekChatModel.stream(new Prompt(messages, options))` Loop；SSE 事件与语义全部不变 |
| `backend/src/main/java/com/example/quickstart/ai/AiSessionStore.java` | 删除 `messages`（迁移 ChatMemory）；`PendingCall` 增加 `roundCalls` + `completedResponses` 字段支撑暂停-恢复 |
| `backend/src/main/java/com/example/quickstart/ai/AiToolRegistry.java` | 过滤逻辑不变；产出物 `functionTools(OpenAiApi)` → `toolCallbacks(page, step)`；注入 `BackendToolExecutor`/`ObjectMapper` |
| `backend/src/main/java/com/example/quickstart/ai/RoutingToolCallback.java` | **新增**，`ToolCallback` 实现：`ToolDefinition.builder().name/description/inputSchema(jsonString)`；`call()` 对 BACKEND 路由 `BackendToolExecutor`、FRONTEND 返回错误 JSON（框架不执行它，`internalToolExecutionEnabled=false`） |

`BackendToolExecutor`（结果截断 4000 在 Service 层保留）、`PromptBuilder`、Controller/DTO 未动。

## DeepSeekChatModel 构造器最终签名（javap 核实 v1.1.8）

```java
public DeepSeekChatModel(DeepSeekApi, DeepSeekChatOptions, ToolCallingManager, RetryTemplate, ObservationRegistry)
```
（另有 6 参含 `ToolExecutionEligibilityPredicate` 的重载，补丁类用 5 参版。）

## 补丁类实现要点

- 放在同名包 `org.springframework.ai.deepseek` 下以 `@Override` 包私有的 `createRequest(Prompt, boolean)`；逻辑逐行复制父类（USER/SYSTEM→1 条；ASSISTANT→1 条；TOOL→N 条 `ToolResponseMessage`），**唯一差异**：ASSISTANT 分支 `reasoningContent` 从 `((DeepSeekAssistantMessage) message).getReasoningContent()` 传播（父类硬编码 null，deepseek-flash 思考模式多轮工具调用会 400）。
- options 合并用 `ModelOptionsUtils.merge(requestOptions, request, ChatCompletionRequest.class)`；工具定义用构造时存入的 `ToolCallingManager.resolveToolDefinitions(options)`（父类字段 private，子类自存一份）+ `new DeepSeekApi.FunctionTool(new FunctionTool.Function(desc, name, inputSchema))`。
- 类 Javadoc 注明这是 1.1.8 已知缺口补丁（reasoning_content 回放），升级后应评审移除。

## 关键行为保持

- 多 toolCalls 一轮处理：BACKEND 全部就地执行（含排在 FRONTEND 之后的），逐个 `tool_run`；只暂停第一个 FRONTEND 调用（存 `pendingCall` 发 `tool_call`+`done(waiting)`）；恢复时按 `roundCalls` 原序合成一条 `ToolResponseMessage`（已执行 BACKEND + 已应答 FRONTEND + 未应答调用补错误 ToolResponse 防 400）。
- system prompt 不入 memory，每轮 prepend；窗口淘汰保护升级：丢弃开头孤立 TOOL 消息 + tool 应答不完整的 assistant(tool_calls)。
- 幻觉工具回填错误自愈、`callId` 校验、轮次上限 8、取消轮次边界、整轮 attempt 重试（已产出内容不重试）全部保留。

## 与任务文档不同的决策

1. **`DeepSeekAssistantMessage` 无静态 `builder()`**（javap 核实，文档写法不存在），用 `new DeepSeekAssistantMessage.Builder()`——与父类源码用法一致。
2. **`DeepSeekApi` 不是独立 Bean**（`DeepSeekChatAutoConfiguration` 在方法内部构建），`DeepSeekAiConfig` 复制其构建逻辑（`DeepSeekConnectionProperties`/`DeepSeekChatProperties` + `SimpleApiKey` + RestClient/WebClient Builder + `ResponseErrorHandler`），`ObservationRegistry` 用 `ObjectProvider.getIfUnique(NOOP)` 兜底（本项目无 actuator）。
3. **流式 tool_calls 合并实际在 `DeepSeekApi.chatCompletionStream` 层**（`windowUntil` + `DeepSeekStreamFunctionCallingHelper`），不在 Model 层——结论相同（`output.getToolCalls()` 直接可用），消费端按 `getResults()` 遍历防御空 choices。
4. **杀掉了一个旧进程**：构建时 `repackage` 失败，原因是 15:22 启动的旧后端实例（PID 13380，监听 8090，`java -jar target/quickstart-backend-1.0.0.jar`）锁住 jar，已 `Stop-Process` 后构建通过。未启动任何新服务、未执行 git 命令。

## 未做/建议跟进

- 未启动服务做端到端验证（按要求）；真实 DeepSeek 多轮工具调用（reasoning 回放、FRONTEND 暂停-恢复）建议起服务后回归一轮。
- `docs/02-architecture.md` D1/3.1/3.2 仍描述旧的 OpenAiApi 方案，未改动——如需文档同步可后续更新。
