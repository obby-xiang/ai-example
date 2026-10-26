> **历史分析 / 过程证据 — 非现行基线，非验收结论**
>
> - 归档：2026-10-26 · `docs/evidence/history-recovery-2026-10-26/wire-only/`
> - 来源：Kimi Code 会话 `<KIMI_SESSION>` / 子 agent `agent-59` 的 wire 末条 assistant 文本（只读流式解析；非原始会话转写）
> - 原始体量：14632 B / 9108 字符 / 98 行；行指纹覆盖率（仓内 + 落盘语料）0.0%
> - 类别：**正式独立报告**
> - 脱敏：路径 0 / 会话标识 0 / 身份 0 / 密钥 0
> - 正文为该条消息的完整文本，未删段、未改写结论；仅脱敏与行尾空白规整。当时结论不代表当前代码状态。
# AI Runtime「自研 vs 现成组件」审计报告

**结论先行（Q1）**：`SpToolCallingManager` **不是**重写 `ToolCallingManager`，而是**装饰器**——构造器里 `ToolCallingManager.builder().toolExecutionExceptionProcessor(...).build()` 拿到官方 `DefaultToolCallingManager` 作 delegate（`gate/SpToolCallingManager.java:272-277`），`resolveToolDefinitions` 原样委托（:294-297），单次工具执行也交给 delegate（:1413-1428）。官方 1.1.7 的接缝（`ToolCallbackResolver`、`ToolExecutionExceptionProcessor`、`ObservationRegistry`、`ToolCallAdvisor`）都**存在且可插拔**，项目用了其中 3 个。真正的自研集中在「多调用编排」层，那是官方私有逻辑（`buildConversationHistoryAfterToolExecution`），HITL 挂起/park 也确实只能从这一层发出——所以大方向合理，但有 2 处属可避免的手搓。

---

## 一、确凿：明确在造轮子

**1. 工具入参校验：手写 JsonNode 逐键判定，而 Bean Validation 依赖已声明却零使用**
- 证据：`tool/SheetWriteGuard.java:70-85,118+`、`tool/UiPrimitiveGuard.java:84-110`、`tool/WizardActionGuard.java`、`form/GenerativeFormGuard.java` 均 `mapper.readTree` 后逐键 `if` 判枚举/长度/互斥/正则可编译；`FrontendToolGuard.java:107` 连「是否结构化结果」也手写解析。`pom.xml:64-67` 声明了 `spring-boot-starter-validation`，但全后端 `jakarta.validation.constraints` 命中 **0** 次。而工具本身已有 `inputSchema`（`ToolRegistry` 注册的 `ToolDefinition`），执行时**从不校验**——后端工具靠 Jackson 反序列化兜类型，FRONTEND 工具方法体是哨兵桩，等于**完全无校验**，这正是要手写 guard 的根因。
- 可用组件：`com.networknt:json-schema-validator`（直接拿 `ToolDefinition.inputSchema()` 校验 `arguments`，含 `$ref`/`$defs`/enum），业务不变式再落到 Hibernate Validator（`@Pattern`、`@AssertTrue` 表达 `wildcard/regex` 互斥、`commit=true` 规则子集）。
- 判断：**确实重复造轮子**（约 2700 行 guard 中，约一半是可声明式表达的约束）。
- 成本/风险：中。错误文案被前端卡面与测试逐字断言（如「共 N 尾行」「未下发到前端」），迁移必须保留 `Verdict.code/reasons` 外形；schema 对后端工具是「自证」（schema 由同一批 Java 类型生成），只有 FRONTEND 工具收益真实；`networknt` 对 `$ref` 跨 `$defs` 的行为需与 `ToolSchemaNormalizer` 的输出核对。

**2. 限流：Redis ZSET 滑动窗口 Lua 手写**
- 证据：`gate/AiRateLimiter.java:79-96`，`ZREMRANGEBYSCORE → ZCARD → ZADD` 全在自定义 `DefaultRedisScript` 里，连 `TIME` 都取 Redis 的以避免时钟漂移。
- 可用组件：Redisson `RRateLimiter`（令牌桶）、`bucket4j-redis`（分布式 Bucket4j）。二者都是「多实例一致 + 原子」的成熟实现。
- 判断：**确实重复造轮子**（滑动窗口语义 bucket4j 可用「greedy refill」近似，精确滑动窗口 Redisson 不直接给）。
- 成本/风险：中低。这是唯一有精确「任意 60s ≤ N」语义的现成写法；替换会影响已有降级口径（Redis 抖动放行）与多实例时钟测试；如果保留，理由应写成「需要精确滑动窗口而非令牌桶」，而不是「没有现成组件」。

**3. 指标与审计计数：`AtomicLong` 手搓，理由「不引 Micrometer」与事实不符**
- 证据：`tool/SheetAudit.java:22` 明写「不引 OTel / Micrometer，计数用 AtomicLong 手写快照」，:57-71 八个 `AtomicLong`；但 `io.micrometer:micrometer-core:1.15.11` 是 `spring-ai-commons` 的 **compile** 依赖（已实测 pom），同仓 `AiProviderRegistry.java:432` 已在用 `ObservationRegistry`。
- 可用组件：Micrometer `Counter`/`Gauge`/`DistributionSummary`（`/health.sheet` 改读 `MeterRegistry` 快照），trace 侧可用 Observation 替代手写 `AiTrace.eventOne`。
- 判断：**确实重复**。依赖重量的前提判断错误；但「不落库、零日志组件」的裁决是产品决策，代码本身没毛病。
- 成本/风险：低。只需一个 `MeterRegistry` 装配；风险是 `/health` 载荷形状变化（前端/脚本可能读它）。

**4. token 估算：字符启发式替代已在 classpath 的分词器**
- 证据：`context/ContextEstimator.java:21-27` 称「刻意不引入分词器依赖……打包体积与启动开销多一份无收益的负担」，:52-68 用 `ceil(cjk/1.7 + other/4)`；但 `com.knuddels:jtokkit:1.1.0` 已随 `spring-ai-commons` 传递进来（compile），Spring AI 1.1.7 还自带 `org.springframework.ai.tokenizer.TokenCountEstimator` SPI 与 `JTokkitTokenCountEstimator`。
- 可用组件：`JTokkitTokenCountEstimator`（BPE 真值）或自定义 `TokenCountEstimator` 实现，缓存每消息结果。
- 判断：**确实重复造轮子**，且注释里的「无收益」前提不成立（依赖已在，不加包）。
- 成本/风险：中。换真分词器后所有数字都会漂移（中文 token 数通常**变大**），`ContextTrigger` 的 70/80/95%、`memory.token-budget`、结果预算都要重新标定；BPE 编码比字符循环慢一个量级，必须有缓存，否则每轮多次全量估算会变成 CPU 热点。

**5. 单工具执行：伪造 ChatResponse 再回捞结果，而非直接用公开 API**
- 证据：`gate/SpToolCallingManager.java:1413-1428` 把一条 `ToolCall` 包成 `AssistantMessage`→假 `ChatResponse`→再调 `delegate.executeToolCalls`，最后在 `conversationHistory` 里按 `toolCallId` 找 `responseData`。`ToolCallback#call(args, context)` 是公开 API，官方 `DefaultToolCallingManager` 内部执行单个工具走的就是它。
- 可用组件：`ToolRegistry#getCallback(name).call(arguments, toolContext)`（自己已有注册表可直接取）。
- 判断：**确实重复**（绕一大圈，还依赖 `toolCallId` 原样回传这一隐性契约）。
- 成本/风险：低。改动点集中，但要注意 `ToolCallback#call` 不走 delegate 的 observation 与 `ToolExecutionExceptionProcessor`，需自己补 try/catch 映射；现有单测大量桩在 `delegate.executeToolCalls` 上，需同步改底座。

**6. 小项：线程上下文传播手写**——`observe/AiTrace.java:31-33` 的 `AiMdcTaskDecorator` + `wrap(Runnable)` 手写 MDC 快照/恢复；Spring 6.1 已有 `org.springframework.core.task.support.ContextPropagatingTaskDecorator`，`micrometer-context-propagation` 也已在 classpath。判断：重复，但收益小、替换收益也小。

---

## 二、存疑：部分重复 / 有合理理由

**7. `SpToolCallingManager` 的「多调用编排」**
- 手搓的是：一次 assistant 消息里 N 个 tool_call 的**串行编排**——步数笼、首错即停、`SKIPPED` 补齐、`conversationHistory` 拼装（`history = prompt.getInstructions() + assistant + toolMessage`，:566-577）。
- 证据：上述逻辑在 `DefaultToolCallingManager` 里是私有方法 `buildConversationHistoryAfterToolExecution`，**无法从外部复用**；异常处理已正确复用 `DefaultToolExecutionExceptionProcessor.builder().alwaysThrow(true)`（:276），observation 由 delegate 覆盖。
- 可用的接缝：`ToolCallAdvisor`（1.1.7 `spring-ai-client-chat` 已存在，builder 暴露 `toolCallingManager`/`conversationHistoryEnabled`/`streamToolCallResponses`）负责把工具循环搬进 advisor 链、拿到流式工具事件；`DefaultToolCallingManager.builder()` 也支持 `toolCallbackResolver`；逐工具拦截可用 `ToolCallback` 装饰器。
- 判断：**部分重复但有合理理由**。HITL 确认门 + park（`RunParkedException` 穿出整轮）只能从「循环之上」发出，`ToolCallAdvisor` 与 `ToolCallback` 装饰器都做不到「暂停整轮、外置状态、交还线程」。
- 成本/风险：不加新代码；风险是**与 1.1.7 字节码语义锁死**（`pom.xml:143-146` 的 enforcer 已禁止升级），官方循环的后续改进（并发工具调用、流式工具结果）拿不到；注释里「1.1.8 逐字节一致」这类实测结论会随版本失效。

**8. 工具注册表：没抛弃 `@Tool`/`MethodToolCallbackProvider`**
- 证据：`tool/ToolRegistry.java:117-119` 对每个 bean 调 `MethodToolCallbackProvider.builder().toolObjects(bean).build()`，自建的只是**扫描循环 + 元数据面**（`@ToolScope/@ToolRisk/@ToolChannel`）与渐露披露 `forContext`。
- 判断：**合理自研**（Spring AI 无「按请求/上下文过滤工具」的 API，只能传子集；按 bean 逐个建 provider 是为了把创建失败归因到 bean，L-94）。
- 成本/风险：唯一可简化处是手工 `getBeanDefinitionNames()` 遍历（可用 `ObjectProvider`/`getBeansOfType`），但会丢掉「创建失败逐个点名」的能力，不建议动。

**9. Redis 租约与资源锁：5 段 Lua 手写**
- 证据：`run/LeaseService.java:154-250`（`ACQUIRE` 三键脚本含 `INCR`+`SET PX`+fence 对账、`RENEW`、`RELEASE` 的 GET-比对-DEL）；`gate/ToolResourceLocks.java:127-171` 只是薄包装（任务级 + 作业级、固定顺序、抢不到即放弃）。
- 可用组件：Redisson `RLock`（watchdog 续租、可重入、`tryLock(0, ttl, unit)`）、Spring Integration `RedisLockRegistry`（TTL + 线程重入）。
- 判断：**L1 资源互斥部分重复但有合理理由**（owner 是逻辑主体 `instanceId:runId:toolCallId`，跨线程重入，两者都只按 thread 判重入）；**fencing/epoch 属框架能力不足**（Redisson/RedisLockRegistry 都不给单调写回权令牌，`STALE_FENCE` 语义无处可借）。`ConfirmGate` 的等待期续租循环（:479-490, 737-749）与 Redisson watchdog 重叠，是可剥离的一块。
- 成本/风险：高。租约同时承担「续跑权」与「fence 写保护」，换 Redisson 会拆掉单一原语、引入两套失效语义；Cluster 下的同槽（hash tag）约束也需重新验证。

**10. 上下文压缩/窗口：官方能力已用满，余下是真空**
- 证据：**官方部分确实在用**——`config/AiModelConfig.java:70-80` 装配 `MessageWindowChatMemory` + `MessageChatMemoryAdvisor`；`memory/RedisChatMemoryRepository.java:34-46` 是官方 `ChatMemoryRepository` SPI 实现，并已核实「1.1.x 官方 Redis 仓储未发布、2.0 版依赖 RedisJSON/RediSearch」——这条判断正确。自研的是 `ContextCompactor`（L1/L2/L3 升级 + 熔断）、`RollingSummarizer`（分块 + 递归摘要）、`MemoryWindow`（token 预算 + 按 Turn 淘汰）、`ToolResultClearing`、`HardTrim`。
- 可用的现成能力：1.1 只有 `LastMaxTokenSizeContentPurger`（按 token 裁剪消息列表，无 turn 感知、无 HITL pin）；`spring-ai-rag` 的 `QueryTransformer`/`CompressionQueryTransformer` 是「为检索把历史+问题压成一句 query」，**不改写存储历史**，不能替代 L2；没有官方 summary advisor。
- 判断：**框架能力不足，自研合理**。两处小重复：`ContextEstimator`（见 #4）、`ResilientChatService.java:395-400` 手工复刻 `MessageChatMemoryAdvisor.before` 的拼接顺序（为了快照等于模型所见，理由成立，但绑定实现细节）。
- 成本/风险：若改用 Advisor 承载 L1/L3，可省掉「首轮/续跑两个调用点」的同步义务，但摘要请求会进主链、污染帧序与超时预算，风险大于收益；建议只把 L2 抽到 `QueryTransformer` SPI 外形以便将来对接。

**11. 输入安全闸（`ContextFieldGate`/`InputSanitizer`/spotlighting 定界）**
- 证据：`gate/InputSanitizer.java` 做 NFKC + 剥 `Cf/Cs/Cc`；`gate/ContextFieldGate.java` 白名单化 + 降级不回显；`tool/ToolResultEnvelope.java:52-64` 用 `<tool_result>` 定界。这些是 prompt-injection 防护，没有成熟 Java 库提供「归一但不拦截」这一层（OWASP Encoder 是编码、commons-text 是转义，语义不同）。
- 判断：**合理自研**；`ContextFieldGate` 若只想做字段形状约束，本可用 Bean Validation（与 #1 同源）。

---

## 三、合理自研（不建议替换）

**12. `ToolSchemaNormalizer`（$defs 提升）+ `ToolSchemaReconciler`（YAML vs 反射对账）**
- 证据：`tool/ToolSchemaNormalizer.java:52-81` 把参数级 `$defs` 合并到文档根并自检悬空 `$ref`；`ToolSchemaReconciler.java:25-42` 启动期比对 property/type/required。
- 判断：**框架能力不足**——这是 victools 按参数生成 schema + Spring AI 组装 object schema 产生的上游缺陷，没有库做「$defs 提升」；对账是为消除双源（YAML/注解）。风险是绑定 schema 生成器行为，值得向上游报 issue。

**13. `ToolResultLimiter` / `TruncationContract` / `ToolResultEnvelope`**
- 证据：`tool/ToolResultLimiter.java:137+` 行/字符双预算、结构化 JSON 不按字符切（`TruncationContract#jsonMarker`）、双通道分离（模型裁剪/前端全文）。
- 判断：**框架能力不足**，Spring AI 无结果预算机制；`ToolCallResultConverter` 管序列化不管预算。`PlainTextToolResultConverter` 更是**正确使用官方扩展点**（`@Tool(resultConverter=...)`），不是手搓。

**14. `ConfirmGate` 的 HITL 状态机**
- 证据：`gate/ConfirmGate.java:457-560`（预登记闸门 → 发给前端 → park 交还线程 → 决策经 Redis 落定 → 续跑重建）。
- 判断：**没有现成组件**（人机审批 + 跨实例恢复 + 跨进程唤醒是业务状态机）；只有「等待期续租」一块与 #9 重叠。

**15. `ToolTargetResolver` / `ToolPreflightService` / `ToolUnknownReconciler`**
- 判断：**合理**。三者都是「业务守卫复用」的编排（预检直接调 `JobService` 的只读守卫，`ToolPreflightService.java:40-45` 明写「判据只借不抄」），不是可替换的通用设施。

---

## 附：给主代理的三条判断

- **最高性价比替换**：`ContextEstimator` → Spring AI 自带 `JTokkitTokenCountEstimator`（依赖已在，零新增包），一次性消除「估算 vs 阈值」的系统性偏差；其次是 guard 的 schema 校验（`networknt`）。
- **最不该动**：`LeaseService` 的 fence/epoch（框架真的没有），以及 `SpToolCallingManager` 的编排层（HITL 语义决定）。
- **两个「前提事实错误」值得回写注释**：`SheetAudit` 说「不引 Micrometer」但 `micrometer-core` 已在 classpath；`ContextEstimator` 说「引入分词器要多一份打包负担」但 `jtokkit` 已随 `spring-ai-commons` 进来。二者都是政策选择披着技术理由的外衣，容易误导后续评审。
