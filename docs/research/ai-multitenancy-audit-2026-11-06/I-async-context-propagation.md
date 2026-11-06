# I. 异步线程池与上下文传播专项报告

## 1. 身份门禁与审查边界

身份门禁通过，非 `IDENTITY_FAIL`。

- 实际 `agentId`：**`agent-948`**，由工具回执路径确认。
- 证据文件：`<会话证据根>/agents/agent-948/wire.jsonl`
- `profile.bind`：**第 6 行**
  - `agentId=agent-948`
  - `modelAlias=kimi-code/k3.1-pro`
- 首条 `llm.request`：**第 20 行**
  - `model=k3.1-pro`
  - `modelAlias=kimi-code/k3.1-pro`
- 仓库 HEAD：`17080859f7b6905aafbbea90f6d2281913bcab9e`，与指定 `17080859` 一致。
- 仅进行源码和配置的只读审查；未修改项目代码，未运行测试、批量验证脚本或服务，未执行 Git 写操作。

## 2. 总体结论

| 严重度 | 数量 | 结论 |
|---|---:|---|
| P0 | 0 | 未发现可确认的跨租户对话正文泄露或破坏 |
| P1 | 0 | 未发现主数据面可确认的跨用户读写、错误身份续跑 |
| P2 | 4 | 两条语言上下文断链；两类 MDC/日志链断链 |
| P3 | 1 | 裸线程可继承首位提交者 TTL，当前主要是防御纵深缺口 |

主数据面的设计不是单纯依赖 TTL：多数恢复型入口会再按 `RunSnapshot`、执行信封或索引成员显式建立 `RunIdentityScope`。这一点是本次未发现 P0/P1 隔离问题的关键原因。

---

# 3. 确定问题与风险

## I-01　P2／确定问题：工具超时池丢失本轮 locale

### 证据

- `backend/src/main/java/com/example/configmgr/ai/gate/SpToolCallingManager.java:788-799`
  - 外层从 `toolContext` 取得 `replyLocale` 并在当前 Reactor/工具调用线程设置、恢复。
- 同文件 `3058-3072`
  - 二次提交到 `toolTimeoutExecutor` 时只显式携带：
    - `TenantUser identity`
    - `AiTrace.wrapCallable(...)` 的 MDC
  - 没有携带 locale。
- 同文件 `3306-3323`
  - 工作线程调用 `delegateSingle(...)`，进入真实工具。
- `backend/src/main/java/com/example/configmgr/ai/tools/AiTools.java:216-226`
  - `getConfigDef` 在未知编码时调用 `ReplyLanguage.current()` 选择中英文。
- `backend/src/main/java/com/example/configmgr/common/ErrorBodyFactory.java:122-126`
  - 无线程 locale 时固定回落 `zh-CN`。

### 完整调用链

`ResilientChatService.toolContext` 写入语言标签
→ Reactor 工具线程 `executeToolCalls` 设置 locale
→ `executeWithTimeout` 再提交到 `ai-tool-timeout-*`
→ 新线程只恢复身份和 MDC
→ `delegateSingle`
→ `AiTools.getConfigDef`
→ `ReplyLanguage.current()` 读取空 locale
→ 中文文案。

### 触发场景与影响

英文用户调用 `get_config_def` 并传入不存在的编码，且 `app.ai.tool.exec-timeout` 为正：

- 不开超时池的同步路径返回英文；
- 开超时池后返回中文。

与租户、实例无关，任一实例都可复现。影响是同一工具在两种执行形态下用户可见语言不一致；未发现该断链直接导致跨身份数据读写。

### 修改建议

在 `executeWithTimeout` 提交前取得 `replyLocaleOf(prompt)`，并在工作线程中：

1. 保存 `LocaleContextHolder.getLocaleContext()`；
2. 设置本轮 locale；
3. `finally` 恢复原 `LocaleContext`，而不是一律 `resetLocaleContext()`，避免覆盖外层已有上下文。

更长期的方案是建立统一的不可变 `AiContextSnapshot`，同时携带身份、locale 和 MDC，避免每个池各漏一项。

### 后续单点验证（本次未执行）

- 同一英文工具调用分别在启用/禁用超时池时比较结果语言。
- 同一工作线程连续执行 `en-US`、`zh-CN` 两个任务，验证互不串语言。
- 工具正常返回、抛异常、超时取消三种路径都验证 locale 恢复。

---

## I-02　P2／确定问题：自动恢复没有持久化本轮语言，跨实例/定时续跑回落中文

### 证据

- `backend/src/main/java/com/example/configmgr/ai/run/RunSnapshot.java:67-208`
  - 快照包含身份、历史、模型和参数等，没有 `replyLocale` 或语言标签。
- `backend/src/main/java/com/example/configmgr/ai/run/ResumeService.java:824-825`
  - 自动恢复入口从**当前执行线程**调用 `ErrorBodyFactory.requestLocale()`。
- `backend/src/main/java/com/example/configmgr/ai/run/StartupResumeRunner.java:1219-1223`
  - 启动/运行期扫描只显式恢复租户和用户，然后调用上述入口。
- `backend/src/main/java/com/example/configmgr/ai/gate/ConfirmGate.java:2520-2534`
  - 确认门重试同样在派发时从当前线程取 locale；定时线程没有原 HTTP locale。
- `backend/src/main/java/com/example/configmgr/ai/run/ResumeService.java:1752-1777`
  - 驱动池只恢复传入的 locale。
- `backend/src/main/java/com/example/configmgr/ai/run/ResilientChatService.java:1217-1224`
  - 新一轮工具上下文又从当前线程 `ReplyLanguage.current()` 生成语言标签。

### 完整调用链

实例 A 接收 `en-US` 首轮并挂起
→ HTTP 请求结束
→ 实例 B 的运行期扫描，或实例 A/B 的租约重试、前端回执到期任务被触发
→ `StartupResumeRunner.resumeWithRunIdentity` / `ConfirmGate.dispatchResume`
→ 当前定时线程无 locale，`ErrorBodyFactory` 回落 `zh-CN`
→ `ResumeService` 将中文传入驱动池
→ 后续工具上下文、错误短句、必要的 SYSTEM 重建使用中文。

### 触发场景与影响

- 英文轮次挂起后由另一实例恢复；
- 租约重试、前端工具到期收敛、启动恢复、运行期死轮扫描接管；
- 已持久化的 SYSTEM 若仍完整，主模型提示可能仍保留英文，因此**不是断言整个回答必然变中文**；
- 但工具错误、续跑失败文案、后续工具语言标签及 SYSTEM 缺失时的重建可出现中英混杂。

这是跨实例上下文恢复不完整，不是跨用户身份混淆。

### 修改建议

- 在首轮创建 `RunSnapshot` 时增加 additive 字段，例如 `replyLanguageTag`。
- 首轮从请求 locale 归一化为 `zh-CN`/`en-US` 后写入现有快照：
  - `ai:run:{<tenantId>:<userId>:run:<runId>}`
  - **键结构、查询和索引均不需要改变**。
- 所有 HTTP、定时、跨实例续跑统一从快照恢复语言；不要从恢复线程重新猜测。
- 旧快照字段缺失时回落 `zh-CN`，与当前行为兼容；`@JsonIgnoreProperties(ignoreUnknown=true)` 已支持滚动升级。
- 不建议从既有 SYSTEM 文本反解析语言，容易与历史提示分叉。

### 后续单点验证（本次未执行）

- 实例 A 写入英文挂起轮，实例 B 无 HTTP 头自动恢复，检查工具与错误文案。
- 分别覆盖启动扫描、运行期扫描、35 秒租约重试、前端回执到期四条入口。
- 旧快照无语言字段时验证仍为中文且反序列化正常。

---

## I-03　P2／确定问题：多个裸异步入口没有 MDC 传播，普通日志失去 run/session/trace 锚点

`RequestContextTaskDecorator` 只传播 TTL 身份；`TtlExecutors` 同样不等于 MDC 装饰器。

### 已确认的未覆盖入口

1. **看门狗与保活调度器**
   - `backend/src/main/java/com/example/configmgr/ai/exec/AiExecutorConfig.java:131-140`
   - 同文件 `168-176`
   - 两个调度器仅使用 `TtlExecutors`，没有 `AiTrace.wrap` 或 `AiMdcTaskDecorator`。
   - `backend/src/main/java/com/example/configmgr/ai/run/StreamWatchdog.java:156-159` 直接排程，`191-195`、`279-289` 只恢复身份。
   - `backend/src/main/java/com/example/configmgr/ai/run/StreamWatchdog.java:301-302` 的普通 WARN 因而没有保证的 MDC 行首锚点。
   - `backend/src/main/java/com/example/configmgr/ai/run/TurnKeepalive.java:179-195` 同样只恢复身份。

2. **模型流式回调**
   - `backend/src/main/java/com/example/configmgr/ai/run/ResilientChatService.java:1334-1345`
   - `subscribe` 的 `onNext` 只建立 `RunIdentityScope`，没有 MDC 包装。
   - error/complete 回调只完成 `CompletableFuture`，本身不读业务上下文，这部分是安全的。

3. **SSE 二级写池和写超时回调**
   - `backend/src/main/java/com/example/configmgr/common/sse/SseWriteBudget.java:423-463`
   - 写任务及超时任务都没有统一上下文快照。
   - `backend/src/main/java/com/example/configmgr/ai/run/SseChatEmitter.java:4862-4874`
     - 只有实际 `emitter.send` 被 `RunIdentityScope` 包裹；
     - `onDone` 回调在该作用域之外执行。
   - 后续 `finishDrain` 会重新提交到带 `AiTrace.wrap` 的 delivery pool，因此数据面有补偿，但写池/超时线程上的日志没有同等保证。

4. **裸重试调度器**
   - `backend/src/main/java/com/example/configmgr/ai/gate/ConfirmGate.java:392-397`、`backend/src/main/java/com/example/configmgr/ai/run/CancelledRunFinalizer.java:108-123`
   - 线程工厂未做 MDC 清理或传播；业务身份由任务体另行恢复，但 MDC 没有统一处理。

### 影响

- 日志可能只有自由文本中的 `runId`，没有统一的行首 `runId/sessionId/traceId`。
- 多实例日志合并后，超时、看门狗、写失败等关键事件难以可靠归属到一轮。
- 这是观测断链；未据此认定对话数据发生跨身份读写。

### 修改建议

- 对“任务体”和“完成回调”使用**同一份提交时刻快照**：
  - tenant/user；
  - locale；
  - 完整 MDC。
- `writeAsync` 应同时包装 `task.send()` 与 `notifyDone(...)`，两个区间都在 `finally` 恢复原上下文。
- 周期任务应在每次 tick 按该任务固定的 run/session 重新绑定，不能依赖线程首次创建时的状态。
- Reactor `onNext` 可在现有 `RunIdentityScope` 外增加 MDC 包装；error/complete 若新增业务日志或数据操作，也必须采用同一快照。

### 后续单点验证（本次未执行）

- 同一工作线程交错执行两个 run，检查普通日志与结构化事件的行首。
- 分别覆盖写成功、写失败、写超时、看门狗触发、保活节拍、Reactor `onNext`。
- 验证无 MDC 提交时不会把池线程旧值带入任务。

---

## I-04　P2／确定问题：`AiTrace.eventOn` 遇到已有其它 run 的 MDC 时不改绑，允许行首与事件体分叉

### 证据

`backend/src/main/java/com/example/configmgr/ai/observe/AiTrace.java:222-241`

```java
if (currentRunId() == null && runId != null && !runId.isBlank()) {
    bind(runId, sessionId);
}
```

只有当前 MDC **没有** runId 时才绑定显式 runId。

如果当前线程残留 `runId=A`，调用：

```java
AiTrace.eventOn("B", "session-B", ...)
```

则：

- 事件体使用显式的 `B/session-B`；
- 日志行首仍可能是 `A/session-A`。

此外，`AiTrace.bind` 在同 runId 且传入空 sessionId 时不会清除既有 sessionId，见同文件 `69-81`，也需要防止旧 session 残留。

### 触发场景与影响

裸写池、定时器或 Reactor 线程先处理 run A，再处理 run B；B 使用 `eventOn` 补救时，由于 A 的 MDC 非空，仍可能产生：

```text
行首: runId=A sessionId=A
事件体: event=... runId=B sessionId=B
```

这会造成跨轮、跨会话的错误观测归属；不影响 Redis 键本身。

### 修改建议

- 只要显式 `runId` 非空，就临时绑定该显式值，不应以“当前 MDC 是否为空”为条件。
- 显式事件若没有提供 sessionId，应明确是否清除旧 sessionId，不能静默继承另一轮的值。
- 继续在 `finally` 恢复之前的完整 MDC。
- 与 I-03 的回调包装一起实施，避免仅靠每个日志点补救。

### 后续单点验证（本次未执行）

- MDC 为 A 时调用 `eventOn(B)`，断言行首与事件体都为 B，结束后恢复 A。
- 覆盖同 runId 不同 sessionId、显式空 sessionId、空 MDC 三种情况。

---

## I-05　P3／风险：部分裸线程可继承首位提交者 TTL，当前未形成已证实的数据泄露

### 证据

- `backend/src/main/java/com/example/configmgr/ai/tenant/RequestContextUtils.java:33` 使用 `TransmittableThreadLocal`，具有创建线程时继承语义。
- 以下线程工厂没有显式 `denied`：
  - `backend/src/main/java/com/example/configmgr/common/sse/SseWriteBudget.java:254-259`
  - `backend/src/main/java/com/example/configmgr/ai/run/RedisFrameWakeBus.java:342-353`、`374-384`
  - `backend/src/main/java/com/example/configmgr/ai/gate/ConfirmGate.java:392-397`
  - `backend/src/main/java/com/example/configmgr/ai/run/CancelledRunFinalizer.java:108-113`
- 对比已有正确防御：
  - `backend/src/main/java/com/example/configmgr/ai/run/SseChatEmitter.java:885-897`
  - `backend/src/main/java/com/example/configmgr/job/service/JobHeartbeatRegistry.java:491-512`
  - 两者都在守护线程任务外显式 `RunIdentityScope.denied()`。

### 具体风险场景

请求 A 首次触发共享写池或重试线程创建，线程继承 A 的 TTL。之后处理 B：

- B 的发送体或业务体会显式打开 B 的作用域；
- 关闭后恢复到线程原有的 A；
- 作用域之外的回调、日志或未来新增代码可能再次看到 A。

当前闭环中：

- SSE 数据面使用 `deliveryIdentity()` 的记录身份；
- Pub/Sub 监听器显式打开订阅时捕获的身份；
- 重试业务体也显式打开记录身份。

因此这是**确定的上下文残留风险，但不是当前已证实的跨用户数据读写**。

### 修改建议

- 所有全局/共享裸线程工厂采用现有 `denied()` 模式，保证基线无身份。
- 同时清理 MDC，避免首位创建者的日志上下文成为线程永久底噪。
- 业务任务仍按记录显式打开身份，不能用“线程已 denied”替代任务级交棒。

---

# 4. 线程入口 → 装饰器 → 实际上下文矩阵

下表中的“安全”仅指本专项的身份传播和清理，不扩展裁决其它存储领域。

| 线程入口 | 装饰/显式交棒 | tenant/user | locale | run/session/trace/MDC | finally 与结论 |
|---|---|---|---|---|---|
| `AiController` → `aiRunExecutor`，`backend/src/main/java/com/example/configmgr/ai/web/AiController.java:562-605` | `aiTaskDecorator`：TTL + MDC | HTTP 身份经 TTL 到工作线程 | 显式 set，`600-602` reset | 提交前 `AiTrace.bind`，装饰器捕获 | 会话门、registry、locale 均清理；**已证安全** |
| `ResumeService` → `aiResumeTrigger` → `aiRunExecutor`，`backend/src/main/java/com/example/configmgr/ai/run/ResumeService.java:1752-1793` | 两级 TTL+MDC；任务内再按快照开身份 | 快照身份优先 | HTTP 路径显式传递；自动恢复有 I-02 | 两级 MDC | `1813-1829` 清 MDC/身份，任务内释放门；**身份安全** |
| 唯一 `@Async`：`backend/src/main/java/com/example/configmgr/job/service/AsyncJobExecutor.java:28-45` | `backend/src/main/java/com/example/configmgr/config/AsyncConfig.java:66-87` 仅 `RequestContextTaskDecorator` | 提交者身份可传播 | 不传播；当前作业未读 locale | 不传播 AI MDC；当前作业未读 run/session | 作业注册表在 finally 清理；**当前安全，复用到 AI 有风险** |
| 摘要池 `backend/src/main/java/com/example/configmgr/ai/context/ContextSummaryClient.java:145-180` | `AiTrace.wrapCallable` + `RunIdentityScope` | 显式捕获提交者身份 | 工作体未读 locale | MDC 显式传播 | `future.cancel` 与作用域关闭；**已证安全** |
| 工具超时池 `backend/src/main/java/com/example/configmgr/ai/gate/SpToolCallingManager.java:3058-3072` | MDC + 身份显式交棒 | 正确 | **丢失，I-01** | MDC 正确 | 身份作用域关闭；语言不完整 |
| 模型流 `backend/src/main/java/com/example/configmgr/ai/run/ResilientChatService.java:1253-1345` | `onNext` 按捕获身份开作用域 | 正确 | 提示和工具主要经显式工具上下文 | **未统一传播，I-03** | error/complete 仅发信号；身份数据面安全 |
| 官方记忆 advisor，`backend/src/main/java/com/example/configmgr/ai/memory/IdentityBoundChatMemoryAdvisor.java:107-175`、`227-265` | 每次调用构造绑定身份的 memory；读写各开作用域 | 优先执行信封，失败关闭 | 不依赖 | turn 可从信封/MDC 解析 | boundedElastic 上也不依赖线程身份；**已证安全** |
| `aiStreamWatchdog` / `aiCancelPoll` | TTL 调度器 + 每拍记录身份 | 正确 | 不读取 | **MDC 缺口，I-03** | `backend/src/main/java/com/example/configmgr/ai/run/StreamWatchdog.java:315-327` 取消任务和监听器；**身份安全** |
| `TurnKeepalive` | TTL 调度器 + 建立时身份 | 正确 | 不读取 | MDC 缺口 | `backend/src/main/java/com/example/configmgr/ai/run/TurnKeepalive.java:159-163` 停表；**身份安全** |
| 租约续期 `backend/src/main/java/com/example/configmgr/ai/run/LeaseService.java:1016-1028`、`1114-1118` | 裸池，但显式携带租约身份 | 正确，含 null 时 denied | 不读取 | 普通日志无统一 MDC | 停租约、释放有明确路径；**身份安全** |
| 会话门看门狗 `backend/src/main/java/com/example/configmgr/ai/session/SessionGate.java:731-766` | Holder 固定身份和锁键 | 正确 | 不读取 | 无统一 MDC | watchdog cancel；**身份安全** |
| 确认门重试、取消收尾 | 裸 scheduler；任务内显式身份；后续 trigger 池有双装饰 | 正确 | 自动路径有 I-02 | MDC 缺口 | 作用域关闭、重试有界；**身份安全** |
| SSE delivery pool | `AiTrace.wrap` + `AiTrace.bind` + `deliveryIdentity` | 记录身份优先，未知即停投 | 不读取 | delivery 有包装 | `backend/src/main/java/com/example/configmgr/ai/run/SseChatEmitter.java:4720-4740`；**数据面安全** |
| SSE writer / timeout | 发送体显式身份；回调无统一快照 | 当前下游仍用记录身份 | 不读取 | **回调/MDC 风险，I-03/I-05** | 发送作用域关闭；未见当前数据泄露 |
| Redis wake Pub/Sub | 订阅时捕获身份，监听器开作用域；`onWake` 再按记录身份 | 正确 | 不读取 | 裸派发线程无统一 MDC | 精确句柄退订；**身份安全** |
| 启动/运行期恢复扫描 | 裸线程；索引成员带身份，逐轮开作用域 | 正确 | **I-02** | 每轮有 AiTrace 清理 | 身份不跨候选残留；**身份安全** |
| Session purge retry | 裸 scheduler；每条 pending 记录恢复身份 | 正确 | 不读取 | 无统一 MDC | 每条作用域关闭、全局锁 finally 释放；**已证安全** |
| KB refresh/cache lease | 裸 scheduler；全局语料路径未读请求身份或 locale | 不依赖 | 不读取 | 无 run/session 语义 | **本专项内安全** |
| Task event bus/retry | 事件处理不读请求上下文；retry 显式 denied | 不依赖当前线程身份 | 不读取 | 无 AI MDC 语义 | **本专项内安全**；其它任务隔离问题不在本报告裁决 |
| Job watchdog/heartbeat、readiness probe | 无对话上下文读取；heartbeat 显式 denied | 不依赖 | 不读取 | 无 run/session 语义 | **本专项内安全** |
| `AiConfigService`、`RedisAvailability`、`AiConnectionProbe`、`AiSessionPurgeService` 的 `CompletableFuture` | 仅 single-flight 信号、`sendAsync().get` 或 Lettuce future `join`，无额外业务 continuation | 不产生新的上下文入口 | 同左 | 同左 | **不是额外未装饰业务线程入口** |

---

# 5. 已证安全的隔离基础与清理语义

- `backend/src/main/java/com/example/configmgr/ai/tenant/RequestContextTaskDecorator.java:17-20` 通过 `TtlRunnable` 捕获并恢复身份；它不是 locale/MDC 装饰器。
- `backend/src/main/java/com/example/configmgr/ai/observe/AiTrace.java:161-172`、`181-192` 的 Runnable/Callable 包装都在 `finally` 恢复旧 MDC。
- `backend/src/main/java/com/example/configmgr/ai/tenant/RequestContextUtils.java:114-130`：
  - 同线程关闭；
  - 无旧值则 remove；
  - 有旧值则恢复；
  - 重复关闭幂等。
- `backend/src/main/java/com/example/configmgr/ai/tenant/RunIdentityScope.java:55-81`：
  - 无记录身份时显式 `denied()`，不会沿用调用者身份。
- Redis run 键：
  - `backend/src/main/java/com/example/configmgr/ai/tenant/RunKeyScope.java:51-76`
  - `{<tenantId>:<userId>:run:<runId>}`，同 ID 不同身份不会共键。
- 本地互斥：
  - `backend/src/main/java/com/example/configmgr/ai/tenant/LocalRunKey.java:27-42`
  - 同样使用身份 + runId，不是裸 runId。
- 恢复索引：
  - 全局 `ai:recovery:due` 的 member 为 `tenantId/userId/runId`；
  - 请求面使用 `ai:recovery:due:<tenantId>:<userId>`、`ai:runs:<tenantId>:<userId>`；
  - `backend/src/main/java/com/example/configmgr/ai/run/RunStore.java:3271-3283`、`3292-3302` 按当前身份选择窄索引。
- 本报告发现的问题不要求重划 DB 查询或 Redis 身份键；语言修复只需在现有 run 快照中增加字段。

---

# 6. 租户枚举/列表依赖专项结论

**本领域异步执行路径没有依赖“循环租户列表”或“获取全部租户列表后来恢复对话”的设计。**

需要区分的实际情况：

- 仓内确实存在静态目录接口：
  - `backend/src/main/java/com/example/configmgr/ai/web/TenantDirectoryController.java:22-24` 的 `GET /api/ai/tenants`；
  - `backend/src/main/java/com/example/configmgr/ai/tenant/TenantUserDirectory.java:32-41` 返回两个预置租户。
- 该接口用于身份选择和 `exists` 校验，**没有被本次审查的任何异步恢复、定时、流式或投递入口调用**，因此不构成本专项的租户枚举依赖。
- 启动/运行期恢复扫描读取的是全局**到期 run 记录索引**，不是租户列表；每条记录自带身份，再逐条开作用域。
- Session purge 扫描的是全局到期销毁台账，JobWatchdog 扫描的是在途作业；均不是“逐租户循环”。
- 请求面手工扫描只读当前身份的窄 ZSET，不会枚举其它租户。

后续修改也不应引入 `TenantUserDirectory.tenants()` 作为恢复或缓存预热来源；只能使用真实请求身份或持久化记录身份，按需缓存。

---

# 7. 无法判定与未覆盖声明

- 按禁令未运行测试或并发实验，因此“具体线程在某次运行中实际残留了哪份 MDC”不能动态取证；但哪些入口缺少包装、哪些回调在身份作用域之外，均可由源码确定。
- Task 事件通道本身的业务授权、任务 ID 跨租户唯一性，以及 DB/Redis 其它键族不属于本专项，本报告不作安全背书。
- 除本文档外，没有项目代码被修改，也没有遗留待执行的后台任务。
