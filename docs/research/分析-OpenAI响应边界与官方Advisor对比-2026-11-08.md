# OpenAI 响应边界与官方 Advisor 对比分析（2026-11-08）

## 1. 范围与证据边界

- 目标：解决长历史中工具 assistant 的 `reasoningContent=""` 导致上游 HTTP 400，以及后续生成式表单无法实时出现的问题。
- 当前项目冻结 Spring AI `1.1.7`，**不升级依赖**，只允许参考新版本实现。
- 本文记录根因证据、官方 2.x 实现、候选方案比较、最终调用链和验证边界；不把静态设计或生产编译写成真实场景通过。
- 前序已提交修复：
  - `67a8566f`：工具跳正文/推理捕获、同源非空扩展回放、media 保留。
  - `b95248b7`：隐藏 reasoning 的终态 transcript 对账及 DONE/degraded 分类。

## 2. 根因证据

### 2.1 短链路修复有效，但长历史仍有缺口

原双表单场景在 `67a8566f` 后 3/3 通过，第二个表单在原 run 内 325–348ms 实时出现；终态修复后 3/3 history 均包含最终 assistant。

但在同一会话第三个用户 run（前两次均为表单提交后拒绝创建）中，仍出现：

```text
UPSTREAM_REJECTED: The reasoning_content in the thinking mode must be passed back to the API
MessageAggregator: Aggregation Error
```

证据：`<仓外证据根>/ai-interaction-c116-c122-final-20261108-193727/`。

### 2.2 逐消息元数据定位

Redis `chat:mem` 的逐 tool-call assistant 元数据表明：

- 其余工具 assistant 均有非空 reasoning 和正确来源。
- 第二个用户 run 的第二个工具 assistant `call_00_ET_huGxGOCEPZUoCiKoTayN1148` 是唯一 `reasoningContent` 长度为 0 的消息，来源仍为当前 provider/model。
- 第三个用户 run 回放该历史时，省略空扩展字段会触发 400。

证据：`<仓外证据根>/ai-reasoning-long-history-probe-20261108-194800/`。

### 2.3 时序根因

`OpenAiChatModel.internalStream` 在 Spring AI 1.1.7 中按分片 `concatMap` 执行工具，首个 tool-call 分片就可能触发 `executeToolCalls`。如果 reasoning 在 toolCalls 之后到达，assistant 已提前执行并持久化，后续分片无法补救。

父类 `OpenAiApi` 的 `windowUntil/reduce` 还有两个不可忽略的行为：

- 只保留 `choices.get(0)`，其他 choice 已经丢失。
- `content/reasoningContent` 采用当前值覆盖前值，后续空串或新片段会覆盖此前内容。

因此，合并后的 wrapper 无法恢复完整 assistant；`TurnTextCapture` 也无法阻止工具提前执行。

## 3. 官方 2.x 的实现

### 3.1 工具递归移到 response 完成之后

Spring AI 2.0.2 的 `ToolCallingAdvisor` 按以下顺序工作：

1. 调用流式链并使用 `CHAT_CLIENT_MESSAGE_AGGREGATOR` 聚合 response。
2. 原始分片继续向下游发送。
3. response 完成后才进入 `handleToolCallRecursion`。
4. 有工具时调用 `ToolCallingManager.executeToolCalls` 并递归下一跳；`returnDirect` 则直接返回。
5. 工具执行继续使用 `boundedElastic` 和 `ToolCallReactiveContextHolder`。

官方源码：

- [ToolCallingAdvisor.java](https://raw.githubusercontent.com/spring-projects/spring-ai/v2.0.2/spring-ai-client-chat/src/main/java/org/springframework/ai/chat/client/advisor/ToolCallingAdvisor.java)
- [OpenAiChatModel.java](https://raw.githubusercontent.com/spring-projects/spring-ai/v2.0.2/models/spring-ai-openai/src/main/java/org/springframework/ai/openai/OpenAiChatModel.java)
- [UsageAccumulator.java](https://raw.githubusercontent.com/spring-projects/spring-ai/v2.0.2/spring-ai-client-chat/src/main/java/org/springframework/ai/chat/client/advisor/UsageAccumulator.java)
- [Spring AI releases](https://api.github.com/repos/spring-projects/spring-ai/releases?per_page=5)

### 3.2 reasoning 是配套累计，不是简单补字段

2.0.2 的 `OpenAiChatModel` 使用：

```java
reasoningMap.merge(id + ":" + choice.index(), getReasoningContent(choice), String::concat);
```

因此后续 response 携带的是截至当前的完整 reasoning 前缀，即使 `MessageAggregator` 对 metadata 使用 `putAll`，最终也不会只剩末段。

官方修复应完整理解为：

> 工具递归位于完整 response 之后，并配套完整的 reasoning 聚合；不是简单增加 `reasoning_content` 字段。

## 4. 候选方案比较

| 方案 | 优点 | 无法接受的原因 |
| --- | --- | --- |
| 继续修改 `TurnTextCapture` | 改动最小 | 工具已经提前执行并持久化，无法处理 reasoning 后到。 |
| 合并后调用 `super.chatCompletionStream` 再延迟 | 看似复用父类 | 父类已经丢 choice、覆盖正文和推理，无法恢复。 |
| 覆写 `OpenAiChatModel.internalStream` | 能控制工具时序 | 需要复制大量私有工具递归、usage、observation 和递归逻辑，范围更大。 |
| 在 `ToolCallingManager` 内等待 | 不改上游 | 同步接口拿不到后续分片，阻塞当前 Reactor 流还可能死锁。 |
| WebClient filter | 能接触响应体 | 仍需自行解析 SSE、规避父类合并并处理背压/取消，范围更大。 |
| 关闭 `internalToolExecutionEnabled` 并仿造 2.x advisor | 避免初期 HTTP 管线 | 父类仍有损；续跑和 correction 直接调用 ChatModel 绕过 advisor；还需重做 memory 顺序、usage 和 return-direct。 |
| **合并前 `ResponseBoundOpenAiApi`** | 只复制最小 HTTP/SSE/JSON 管线，工具递归继续复用现有模型 | 需要维护少量协议代码，必须冻结并充分验证。 |

裁决：不升级、不新增 advisor、不新增供应商分支，采用合并前 response barrier。官方 2.x 只作为“完整 response 后执行工具”的架构佐证，不直接复制其 OpenAI SDK 实现。

## 5. 最终调用链

```text
AiProviderRegistry.newApi
  -> ResponseBoundOpenAiApi（自有同值 streaming WebClient）
  -> POST completionsPath
  -> additional/default headers 与原 Bearer 顺序
  -> retrieve + bodyToFlux(String)
  -> [DONE] takeUntil/filter
  -> 每个完整 SSE JSON → ChatCompletionChunk
  -> 每次订阅独立 ResponseState
       ├─ 原始 choice 正文/reasoning 独立追加
       ├─ 无工具原始 chunk 直接实时通过
       ├─ tool-only 单 choice 投影交官方 helper 合并参数
       └─ 工具 chunk 的 live 副本移除 toolCalls
  -> 正常 onComplete 后才构造 deferred chunk
       ├─ 完整正文/reasoning
       ├─ helper 合并后的完整 toolCalls
       └─ 最后非空 usage
  -> OpenAiChatModel 既有工具分支
  -> SpToolCallingManager
  -> 普通 settle / HITL saveSuspended / correction / Redis 恢复
```

关键约束：

- `onError`、取消、超时和 dispose 不进入 deferred finish，不执行工具。
- `doFinally` 清理每次订阅的工具聚合、正文、推理和 usage，不使用静态或 runId Map 状态。
- 正文/reasoning 不经过 helper，空串只追加零长度，最终是完整拼接。
- 普通无工具多 choice 原样通过；工具相关多 choice 在 helper 前失败关闭，不静默丢项。
- deferred 只进入内部工具分支，不作为普通中间正文重复上屏。
- 标准 OpenAI 无扩展时不制造 `reasoning_content`，扩展仍按来源和非空规则回放。

## 6. 实际修改

- 新增 `backend/src/main/java/org/springframework/ai/openai/api/ResponseBoundOpenAiApi.java`
  - 覆写 `chatCompletionStream`，只复制必要的请求、认证、SSE `[DONE]` 和 JSON 转换。
  - 在 helper 前聚合原始正文/reasoning 和工具参数，正常完成后释放 deferred。
  - 保留 Spring AI Apache-2.0 版权和改编说明。
- 修改 `backend/src/main/java/com/example/configmgr/ai/config/AiProviderRegistry.java`
  - 用父类同值 8 参数装配 wrapper，保留缓存、热生效、读超时和非流式行为。
- 修改 `backend/src/main/java/com/example/configmgr/ai/run/ResilientChatService.java`
  - `correctionHistory` 新前缀使用快照 provider/model 调用 `ReasoningOrigin.stamp`，未知来源不伪造，空推理不造内容。
- 修改 `backend/pom.xml`
  - 冻结说明覆盖 WebClient 构造、认证顺序、SSE `[DONE]`、raw JSON 解析和 helper 合并边界。

未修改 `SpToolCallingManager`、`TurnTextCapture`、`ReasoningAwareOpenAiChatModel` 或 `MessageAggregator`，未新增依赖、数据库表、Redis 脚本或供应商特判。

## 7. 验证要求与当前状态

必须完成的证据：

1. 原始 `content/reasoning = A → "" → B` 最终为 `AB`。
2. tool finish → reasoning C → usage → complete：C 先实时消费，deferred 含 C 后才执行工具。
3. 无工具多 choice 原样通过；工具相关多 choice 在 helper 前失败且工具管理器零调用。
4. 多片 arguments 正确合并，不误报多个 aggregate；并行 calls 不静默取一个。
5. deferred usage 非空且跨跳不重复、不丢失。
6. onError、取消、超时、看门狗 dispose 不释放工具，状态可清理。
7. 普通 settle、HITL 批准/拒绝/取消、续跑、correction、长历史和多实例恢复。
8. 标准 OpenAI 请求不含 `reasoning_content`；扩展内容只在同源且非空时回放。

当前实际结果：

- 生产 Maven 编译 356 个主源文件：`BUILD SUCCESS`。
- 独立静态复审：待完成。
- 真实 HTTP/SSE、后置分片、usage、取消、HITL、长历史、多实例及 Redis 验收：`NOT_RUN`。
- 本文件提交不代表上述动态场景已通过，后续结果应另附证据。
