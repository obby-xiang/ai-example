# SP-01d 决策卡：Spring AI 1.1.8 官方循环下的流式韧性三件套

- **PoC 编号**：SP-01d
- **结论日期**：2026-10-06
- **被验证方案**：AI Runtime 走 Spring AI 1.1.8 **官方循环**（官方 `ChatClient` + 官方工具循环
  `DefaultToolCallingManager` + 官方 `ChatModel.stream`），在其之上补齐三件韧性能力：
  ① 上游断流的有界重试（对应 TC-AI-14）；② 同一 tool-result 重复回灌的幂等去重（对应 TC-F-04 / TC-AI-25）；
  ③ 执行中取消（对应 TC-AI-15）。
- **spike 工程**：`<MAIN_V2>/spike/sp01d`（Spring Boot 3.5.14 + JDK 21 + Spring AI 1.1.8 BOM import + web）
- **关联决策**：DC-05（官方已有能力禁止自研）、DC-08（脱敏纪律）；参照实现 =
  `ai-example-deepseek-v4-pro` 的 `AiRuntimeService`（`.timeout(90s).retry(1)` + 确认门 SSE 心跳 + `start` 首包），
  取消语义参照 `ai-example-claude-opus-5.5+deepseek-v4-pro` 的 `JobCancellationRegistry`
- **附件**：
  | 附件 | 内容 |
  |---|---|
  | `SP-01d-附件-A-故障注入方法与探针.txt` | 故障注入代理的 7 种模式、复现步骤、Reactor 超时语义探针原始输出 |
  | `SP-01d-附件-B-Vd1断流实测记录.txt` | V-d1 共 19 个 case 的 SSE 事件序列/终态/尝试记录/代理逐请求台账 |
  | `SP-01d-附件-C-Vd2工具重复实测记录.txt` | V-d2 两个子实验的完整请求-响应与消息序列 |
  | `SP-01d-附件-D-Vd3取消实测记录.txt` | V-d3 两个 case 的时间线（毫秒级）、终态事件、工具台账、信标、注册表 |
  | `SP-01d-附件-E-运行日志关键行.log` | 应用/代理日志关键行（脱敏） |

---

## 0. 结论速览

| 验证项 | 结论 |
|---|---|
| V-d1 断流有界重试 | **有条件可行**（须自建重试边界，官方 `RetryTemplate` 与 Reactor `retry` 均不可用——见 §2.2） |
| V-d2 tool-result 重复去重 | **可行**，但**前提与 TC-F-04 的假设不同**：官方循环内根本没有 tool-result 的 HTTP 回灌面（§3.1） |
| V-d3 执行中取消 | **有条件可行**（事件边界生效；工具内需协作式检查；多实例需外部化取消标志） |

**总体结论：三件套都能在官方循环上落地，但不能靠"给官方流套一个 retry"实现。**
本卡实测出三个必须写进实现规范的硬约束：

1. **官方 `ChatClient` 的 advisor 链是一次性的**（`DefaultAroundAdvisorChain` 内部用 `Deque.pop()` 逐个取用），
   对它返回的流做任何重订阅（Reactor `.retry()` / `.repeat()`）都会立刻抛
   `IllegalStateException: No StreamAdvisors available to execute`——**重试不但不生效，还会把"上游断流"这个
   真实原因替换成一个框架内部异常**（§2.2，字节码 + 实测双证）。
2. **重试的安全条件有三个，不是两个**：本轮未对客户端产出内容、**本轮未产生工具副作用**、未超出总时长预算。
   实测到"内容还没产出但工具已经执行过"的窗口，只判"是否已产出内容"会导致工具被重复执行（§2.1-V-d1⑤、§5.4）。
3. **超时阈值必须大于最长工具耗时**：官方流式循环在工具执行期间**不向下游发送任何内容**，
   因此"事件间超时"会把正在正常运行的长工具判成断流并打断它（实测 8s 工具被 6s 超时打断，
   参照实现的单个 90s 超时同理）——参照实现里给确认门发送的 SSE 心跳**不在 Reactor 流内**，无法续期该超时（§2.1-V-d1④）。

> **诚实边界（业务方需知晓）**：
> ① 本卡验证的是**单实例 JVM 内**的取消语义，Redis 化属后续阶段（§4.3 列出多实例缺口）；
> ② 未做压测与并发验证（"同一 sessionId 并发两轮"在本 spike 里会互相污染状态，见 §6.5，属实现限制）；
> ③ 断流实验用的是**本地故障代理**（真实 TCP 层故障：RST / 静默 / 静默截断），不是生产网络抓包；
> ④ 上游为真实 `deepseek-flash`，单次实验时长与内容受模型波动影响，故每项都做了多 case 对照而非单次采样。

---

## 1. 环境与前置事实

| 项 | 实测值 | 证据 |
|---|---|---|
| JDK | 21.0.12 LTS | `java -version` |
| Spring Boot | 3.5.14 | 启动日志 |
| Spring AI | 1.1.8（`spring-ai-bom` import + `spring-ai-starter-model-openai`） | 依赖树/启动类路径 |
| Reactor | reactor-core 3.8.6 | 探针输出首行 |
| Servlet 容器 | Tomcat 10.1.54（MVC + `SseEmitter`，与参照实现同构） | 启动日志 |
| 上游 | `https://api.deepseek.com` / `deepseek-flash`，key 仅环境变量 `DEEPSEEK_API_KEY=***` | 启动参数 |
| 端口 | spike 应用 **18303**；故障代理 **18304**；启动前 `netstat` 均确认空闲 | 附件 E |
| 验证脚本 | `scripts/spike-verify.mjs`（Node 22，逐 case 配置故障→跑请求→落证据） | 附件 A/B/C/D |

### 1.1 故障注入方法（可复现，不在被验证进程内 mock）

官方 `ChatModel` 的 `base-url` 指向 spike 自带的**本地故障代理**（`scripts/fault-proxy.mjs`），代理再转发到真实上游；
代理**按 SSE 事件边界（`\n\n`）转发，绝不切碎半个事件**，故障可运行时通过 `POST /__fault` 切换：

| 模式 | 语义 | 想复现的真实故障 |
|---|---|---|
| `none` | 透传 | 对照组 |
| `reset` | 收到请求立即 RST | 连接被重置 |
| `stall` | 一个字节都不回（连响应头也不发），`stallMs` 后 RST | **上游首包静默**（最常见的"卡住不动"） |
| `stall-after-content` | 转发到"已出现 N 个带正文的 delta"后**停止发送并保持连接**，`stallMs` 后 RST | **已产出内容后的断流** |
| `fin-after-content` | 同上但立即 `res.end()`（TCP 正常 FIN，无 `[DONE]`） | **静默截断**（对端"优雅"关闭，最阴的一种） |
| `truncate` / `truncate-fin` | 转发 N 个事件后 RST / 正常 FIN | 硬截断 / 无正文的静默截断 |
| `onlyRequestIndex` | 只对该轮的第 N 次上游请求生效 | 让工具先成功执行、故障落在其后的最终作答 |

复现步骤（完整命令见附件 A）：

```bash
export DEEPSEEK_API_KEY=***                     # 仅环境变量，未写入任何文件
cd <MAIN_V2>/spike/sp01d && <MAVEN_HOME>/mvn -B clean package
node scripts/fault-proxy.mjs --port 18304 --upstream https://api.deepseek.com --evidence evidence &
java -jar target/sp01d-resilience-0.0.1-SNAPSHOT.jar \
     --spring.ai.openai.base-url=http://127.0.0.1:18304 --server.port=18303 --spring.profiles.active=verbose &
node scripts/spike-verify.mjs <case>            # case 列表见脚本内 CASES
node scripts/make-attachments.mjs               # 由 evidence/ 生成脱敏附件
```

**拓扑**：`验证脚本 --SSE--> spike(18303) --HTTP/SSE--> 故障代理(18304) --HTTPS--> deepseek`。
代理日志给出每个上游请求的 `mode/applied/status/转发字节/outcome`，是"上游侧"的独立证据。

### 1.2 三条官方实现硬事实（源码/字节码核对）

**（a）流式路径没有任何框架级重试。** `javap -p -c org.springframework.ai.openai.OpenAiChatModel` 全文检索
`retryTemplate` 只出现 4 次，其中唯一一次 `retryTemplate.execute(...)` 位于 `lambda$internalCall$3`
（**非流式** `call()` 路径）；`stream()` → `internalStream()` → `Flux.deferContextual(...)` 全程**没有**任何
retry/timeout 算子。即：**`spring-ai-retry` 的 `RetryTemplate` 覆盖不到流式断流**，流式韧性只能在调用方补。

**（b）`ChatClient` 的 advisor 链一次性，不可重订阅。** `javap -p -c
org.springframework.ai.chat.client.advisor.DefaultAroundAdvisorChain`：

```
private Publisher lambda$nextStream$6(ChatClientRequest, ContextView):
    getfield streamAdvisors : Ljava/util/Deque;
    invokeinterface Deque.isEmpty()Z        → 为空则 Flux.error(new IllegalStateException("No StreamAdvisors available to execute"))
    getfield streamAdvisors : Ljava/util/Deque;
    invokeinterface Deque.pop()             ← 破坏性取出：链用完即空
```

`Deque.pop()` 决定了**同一个链对象只能用一次**；重订阅时队列已空 → 抛 `IllegalStateException`。
这是 §2.2 全部问题的根因。

**（c）官方工具执行对重复 `toolCallId` 不做去重。** `javap -p -c
org.springframework.ai.model.tool.DefaultToolCallingManager` 的 `executeToolCall(...)`：
以 `assistantMessage.getToolCalls()` 做普通 `for` 循环（字节码 `Iterator.hasNext/next`），
每轮 `List.add(new ToolResponse(toolCall.getId(), name, result))`，
**没有已执行 id 集合、没有任何幂等判断**。

**（d）工具执行期间下游没有任何内容事件。** 官方流式循环把"工具决策"那一次 `ChatResponse` 消费在自己的循环里
（`internalStream` 的 `toolExecutionEligibilityPredicate` 分支）：实测所有 case 的 SSE 里都**没有** `tool_call` 事件、
`TOOL_CALL_ITEM` 日志计数 = 0，即下游拿不到任何"携带工具调用"的消息（附件 B/C）。
更进一步，实测 8s 工具执行期间**客户端 delta 计数 = 0**：工具开始后下游只剩
"无正文的下游事件"（`AssistantMessage.getText()` 为 `null`，`hasToolCalls()` 为 `false`；
本 spike 未实现 `reasoning` 事件下发，故这些无正文事件对客户端不可见），
**最后一个下游事件出现在工具开始后约 0.4s**，此后到工具结束一片空白。
这一点直接决定了 §2.1-V-d1④ 的超时阈值规则：**工具执行本身不会给下游产生事件，因此不会续期任何超时**。

---

## 2. V-d1 断流有界重试 —— 【实测】有条件可行

实验矩阵（4 种"流式包装策略" × 4 种故障，19 个 case，完整记录见附件 B）：

| 策略 | 实现 | 说明 |
|---|---|---|
| `guarded` | **本 spike 方案**：每次尝试重建整条 `ChatClient` 请求 + 首包/事件间双超时 + 三条件有界重试 | 待验证的落地方案 |
| `ref` | **参照实现原序**（`AiRuntimeService` 的 `.takeUntil().timeout(90s).retry(1).doOnNext()`，spike 中 90s→5s） | 被参照分支的现状 |
| `refbare` | 同上，但用**无自定义 advisor** 的 `ChatClient` | 隔离变量：证明问题在 ChatClient 本身 |
| `refmodel` | 同上，但直接调 `ChatModel.stream(prompt)`（**完全绕开 ChatClient**） | 隔离变量：证明卡点在 ChatClient 层 |
| `plain` | 无超时、无重试（基线） | 证明"没有超时会怎样" |

### 2.1 逐项结论与实测证据

**① 重试有界（次数 / 总时长上限）——【实测】可行**

```
case vd1-guarded-first  fault=stall(首包静默)  strategy=guarded
  SSE 事件: start@4ms | retry@5015ms | error@10020ms
  error 载荷: {"code":"UPSTREAM_SILENT_TIMEOUT","attempts":2,"partial":false,
              "emittedChars":0,"elapsedMs":10016}
  尝试记录: [{"attempt":1,"outcome":"ERROR","costMs":5009,...},{"attempt":2,"outcome":"ERROR","costMs":5004,...}]
  代理台账: #1 stall → STALLED_THEN_SOCKET_RESET 9001ms  |  #2 stall → 已发出
```

**实测上限 = 2 次尝试 / 10.0s**（= `max-attempts=2` × `first-event-timeout=5s`），
且另有 `total-budget=20s` 的墙钟预算兜底（超出即不再重试）。
对照组 `plain`（无超时）在同一故障下**不会自行终止**（见 ③）。

**② 已产出内容不重复重试——【实测】guarded 成立；naive 重试会重复**

guarded 在"已产出内容后断流 / 已产生工具副作用后断流"的 3 个 case 里都没有重试，客户端拼接文本无重放：

```
case vd1-guarded-mid      客户端已收到 1 个 delta(2 字符) 后上游静默
  error: {"code":"UPSTREAM_SILENT_TIMEOUT_AFTER_PARTIAL","attempts":1,"partial":true,
          "emittedChars":2,"elapsedMs":6878}
  尝试记录: [{"attempt":1,"outcome":"ERROR",...}]            ← 只有 1 次尝试
case vd1-guarded-tooldup  工具已执行 → 第 2 次上游请求静默
  error: {"code":"UPSTREAM_SILENT_TIMEOUT_AFTER_TOOL_SIDE_EFFECT","attempts":1,"toolSideEffect":true}
  工具执行台账: ["getServerTime:OK:0ms"]                      ← 工具只跑了一次
case vd1-guarded-longtool 8s 工具执行期间触发超时
  error: {"code":"UPSTREAM_SILENT_TIMEOUT_AFTER_TOOL_SIDE_EFFECT","attempts":1,"toolSideEffect":true}
  工具执行台账: ["longTask:OK:5999ms"]                       ← 只有 1 条
```

> 计数口径提示：附件 B 里"故障配置"的 `contentEvents` 是**代理侧**按
> `"content":"X` 正则数出的事件数（配置意图），与客户端 SSE `delta` 事件数可能相差 1；
> 判定以**客户端 delta 计数**与**服务端 `emittedChars`**为准（两者在上述 case 中一致）。

而"真的重订阅了上游"的 `refmodel` 路径（`ChatModel` + `.retry(1)`）实测把已产出的内容**重发了一遍**：

```
case vd1-refmodel-mid    产出 2 个 delta 后静默
  客户端收到 delta 拼接文本 = "我是我是"          ← 重放前缀长度 = 2
  上游订阅次数 = 2，代理台账 = 2 次真实上游请求
  终态 error: {"code":"UPSTREAM_SILENT_TIMEOUT_AFTER_PARTIAL","attempts":2,...}
```

独立的 Reactor 探针（附件 A，`scripts/FluxTimeoutProbe.java`）给出同一机制的干净证据：

```
D retry 重订阅 | concat(A,B,error) .timeout(500ms).retry(1).doOnNext -> 订阅次数=2 下游收到元素=4 内容=ABAB
E retry 超时重订阅 | concat(A,B,never) .timeout(400ms).retry(1).doOnNext -> 订阅次数=2 下游收到元素=4 内容=ABAB
```

**结论**：`.timeout().retry()` 这种"重订阅"式重试在语义上**天然违反"已产出内容不重发"**，
必须把重试放在**请求级**（重建请求）并用"本轮是否已产出"作为门禁。

**③ 最终给调用方明确的失败语义（不是无限挂起）——【实测】guarded 成立；无超时会挂死**

成功路径先证明链路正常（`vd1-baseline`：17 个 delta、28 字符、终态 `done{cancelled:false,terminal:OK}`）。
guarded 在所有故障下的终态都是**一个明确的 `error` 事件**，带 `code / attempts / partial / toolSideEffect /
emittedChars / elapsedMs / rawReason`，客户端不需要猜：

| case | 故障 | error code | attempts | 客户端耗时 |
|---|---|---|---|---|
| vd1-guarded-first | 首包静默 | `UPSTREAM_SILENT_TIMEOUT` | 2 | 10.0s |
| vd1-guarded-mid | 已产出后静默 | `UPSTREAM_SILENT_TIMEOUT_AFTER_PARTIAL` | 1 | 6.9s |
| vd1-guarded-tooldup | 工具后静默 | `..._AFTER_TOOL_SIDE_EFFECT` | 1 | 7.9s |
| vd1-truncate-rst | 硬截断 | `UPSTREAM_CONNECTION_BROKEN` | 2 | 1.8s |
| vd1-guarded-longtool | 工具执行期间超时 | `..._AFTER_TOOL_SIDE_EFFECT` | 1 | 7.4s |

**反例（`plain` 无超时）**——上游静默且**不关连接**时服务端线程会一直挂着，客户端放弃也无济于事：

```
case vd1-plain-hang     fault=stall(180s 内不回字节、不关连接)，客户端 15s 主动放弃
  客户端侧: 只收到 start，无终态事件，clientAborted=true
  客户端放弃后:   服务端终态=null，仍在执行本轮的线程 = spike-run-5:WAITING
  3 秒后再查:     服务端终态=null，仍在执行本轮的线程 = spike-run-5:WAITING
  阻塞栈顶: ... AbstractQueuedSynchronizer.acquireSharedInterruptibly <- CountDownLatch.await
            <- reactor.core.publisher.BlockingSingleSubscriber.blockingGet
  代理强制断开连接后: 服务端终态=FAILED（elapsed=18.3s），线程释放
```

即：**没有超时算子时，流的终止完全取决于对端是否关连接**——对端"静默但保活"就是无限挂起。

**④ 额外发现（会直接改实现规范）：工具执行期间下游无事件 → 超时误杀长工具——【实测】**

```
case vd1-guarded-longtool  模型直接调用 longTask(seconds=8)，无故障注入
  工具执行台账: ["longTask:OK:5999ms"]            ← 8s 的工具在 6.0s 处被中断（Thread.sleep 被 InterruptedException 打断）
  error@7355ms: {"code":"UPSTREAM_SILENT_TIMEOUT_AFTER_TOOL_SIDE_EFFECT","attempts":1}
  = 最后一个下游事件(约 0.94s) + interEventTimeout(6s)
case vd1-ref-longtool      同场景，参照实现(单值 5s 超时)
  工具: ["longTask:OK:5002ms"]，error@5827ms
```

**规则**：`firstEventTimeout` / `interEventTimeout` 必须 **> 最长工具执行时间**（含 HITL 确认门等待时间），
否则长工具会被当成断流打断。参照实现给确认门发的 `ping` 心跳是发往 **SSE 客户端**的，
**不进 Reactor 流**，因此对 `.timeout(90s)` 没有任何续期作用——【实测依据 + 推断】：若某个确认门等待超过 90s，
该轮会被判超时；本 spike 用 8s 工具 + 6s 超时复现了同构行为（未直接测 90s 确认门）。

**⑤ 额外发现：静默截断（TCP 正常 FIN）会被当成"成功"——【实测】**

```
case vd1-fin-content   已产出 2 个 delta(4 字符) 后 TCP 正常 FIN（无 [DONE]、无 finish_reason）
  客户端: delta 2 个(4 字符) → done{"terminal":"OK","cancelled":false,"emittedChars":4}
  服务端: terminal=OK，无 error
case vd1-truncate-fin  8 个事件后正常 FIN、无正文
  客户端: done{"terminal":"OK","emittedChars":0}        ← 空回复也算成功
```

**官方流式路径不会因为"SSE 没收到 `[DONE]`"而报错**——对端礼貌关闭连接时，下游看到的是正常完成。
必须由业务侧做完整性校验（`finish_reason` / `[DONE]` / 内容非空）才能发现这类静默数据丢失。
**这是本卡发现的、比"断流报错"更危险的失败模式。**

### 2.2 卡点定位（三层，均有字节码或实测支撑）

| 层 | 现象 | 归因 |
|---|---|---|
| **官方 `RetryTemplate`（spring-retry）** | 完全不覆盖流式 | `OpenAiChatModel` 只在**非流式** `internalCall` 里 `retryTemplate.execute(...)`；`internalStream` 无任何 retry（§1.2a） |
| **Reactor `.retry()` 包在 `ChatClient` 流上**（= 参照实现写法） | 重订阅立刻抛 `IllegalStateException: No StreamAdvisors available to execute`；**不产生第 2 次上游请求**；错误语义被污染 | `DefaultAroundAdvisorChain` 用 `Deque.pop()` 取 advisor，链一次性（§1.2b） |
| **Reactor `.retry()` 包在 `ChatModel.stream()` 上** | 能重订阅（产生真实上游请求），但**会把已发出的内容重发**、并重放整条上游交互（含工具调用） | 重订阅语义 = 重新执行整条交互；`Flux` 可重订阅本身没问题（探针 D/E + `vd1-refmodel-mid`） |

对照组数据把结论钉死（同一故障 `stall`，三种策略）：

```
vd1-ref-first       (ChatClient + retry)  上游订阅次数=2 代理台账=1 次请求  error=IllegalStateException
vd1-refbare-first   (无自定义 advisor)     上游订阅次数=2 代理台账=1 次请求  error=IllegalStateException  ← 与 advisor 无关的"内置链"同样一次性
vd1-refmodel-first  (ChatModel + retry)   上游订阅次数=2 代理台账=2 次请求  error=UPSTREAM_SILENT_TIMEOUT(语义正确)
```

### 2.3 最小绕行方案（本 spike 实现，`ResilientChatService.runGuarded`）

1. **重试不重订阅**：每次尝试都重新走一遍 `chatClient.prompt()...stream()`（**新的 advisor 链**，避开 §1.2b）；
2. **双超时**：`timeout(Mono.delay(firstEventTimeout), item -> Mono.delay(interEventTimeout))`
   ——首包与事件间分别设界（探针 B 实测单值 `timeout(Duration)` 也已覆盖两层，拆开只是为了让两个业务阈值可分别配置：
   首包等模型排队、事件间等模型吐字、且都要 > 最长工具耗时）；
3. **重试三条件**（全部满足才重试）：`!已产出内容 && !已产生工具副作用 && 未超总时长预算 && 还有剩余次数`；
   副作用判定同时看**执行台账**（已完成）与**活动信标**（**已开始执行但尚未返回**——第一版只用台账时，
   工具执行中被超时打断 → 重试 → 工具被重复执行，故补上信标）；
4. **终态明确**：`error` 事件带 `code / attempts / partial / toolSideEffect / rawReason`；成功走 `done`，取消走 `done{cancelled:true}`。
   `error.code` 采用"分类 + 后缀"命名：`UPSTREAM_SILENT_TIMEOUT` / `UPSTREAM_CONNECTION_BROKEN` /
   `UPSTREAM_STREAM_ERROR`，后缀 `_AFTER_PARTIAL` / `_AFTER_TOOL_SIDE_EFFECT` 标明不可重试的原因。

---

## 3. V-d2 tool-result 重复去重 —— 【实测】可行（但攻击面与用例假设不同）

### 3.1 先纠正一个前提：官方循环里没有"tool-result 回灌"这个 HTTP 面

TC-F-04 / TC-AI-25 写的是"**重放同一 tool-result 请求**"。在**官方内部工具执行**的架构下，
工具由框架在**进程内**执行，`tool-result` 从不经过 HTTP——实测 `TOOL_CALL_ITEM` 计数为 0、
SSE 里没有任何工具协议消息（§1.2d），因此**"重放 tool-result 请求"这类用例在官方循环下没有对应的注入面**。
本卡因此把 V-d2 拆成两个真实存在的攻击面分别验证：

- **子实验 A（官方循环内）**：模型在**同一条 assistant 消息**里给出两个 `tool_call` 共用同一个 `id`
  → 官方循环会不会重复执行？
- **子实验 B（前端执行工具 / 暂停-恢复架构，即 TC-AI-03 的形态）**：调用方通过 HTTP 回灌 tool-result
  → 同一个 `toolCallId` 提交两次会怎样？（这才是"重放请求"的真实场景）

### 3.2 子实验 A：官方循环对同一 `toolCallId` **不去重**（重复执行）——【实测】

把"两个 `tool_call` 共用 `call_dup_same_id`"的 `AssistantMessage` 直接交给官方
`ToolCallingManager.executeToolCalls(prompt, response)`（官方循环真正调用的那个方法）：

```
入参 tool_call id 列表: ["call_dup_same_id","call_dup_same_id"]
官方返回历史: [USER, ASSISTANT{toolCallIds:[call_dup_same_id,call_dup_same_id]},
               TOOL{toolResponseIds:[call_dup_same_id,call_dup_same_id]}]
历史中 tool 响应 id 序列: ["call_dup_same_id","call_dup_same_id"]  （去重后 = 1 个）
实际执行次数: 2       执行台账: [getServerTime:OK, getServerTime:OK]
```

**结论**：官方循环对重复 `toolCallId` **既不去重、也不报错**，会执行两次并落下两条同 id 的 tool 响应
（与 §1.2c 的字节码一致）。此形态下"重复"应在**入口层**处理（框架层无此能力）。

### 3.3 子实验 B：前端执行工具路径的幂等去重——【实测】可行

spike 用官方 `OpenAiChatOptions.internalToolExecutionEnabled=false`（官方提供的开关，`DefaultToolExecutionEligibilityPredicate`
会因此不做内部执行）把工具调用交回调用方，自建 pending 注册表做幂等，端到端实测：

```
① POST /api/fronttool/start          → status=PAUSED_WAITING_TOOL_RESULTS, round=1
   待回灌: [{"toolCallId":"call_00_ai09D0zPI5N47JdPkRaP1153","name":"getServerTime","arguments":"{}"}]
   消息序列: [SYSTEM, USER, ASSISTANT{toolCalls:[call_00_...1153]}]
② 第一次回灌同 id → HTTP 200 {"accepted":true,"duplicate":false,"pendingRemaining":[],
                              "status":"DONE","finalText":"现在是 **2026年10月6日 20:30:00**（服务器时间）。"}
   消息序列长度 5：追加了 TOOL{toolResponseIds:[call_00_...1153]} 与最终 ASSISTANT
③ 第二次回灌同 id → HTTP 200 {"accepted":false,"duplicate":true,"reason":"DUPLICATE_TOOL_CALL_ID",
                              "loopState":{"status":"DONE","round":2,"messageCount":5,
                                           "submittedIds":["call_00_...1153"]}}
   ★ 消息数仍为 5、round 不前进 —— 没有重复落消息、没有重复执行、循环状态未被破坏
④ 第三次回灌未知 id → HTTP 409 {"accepted":false,"unknown":true,"reason":"UNKNOWN_TOOL_CALL_ID"}
最终状态: 工具执行台账该 run 只有 1 条（FRONTEND_TOOL getServerTime），submissions 3 条（1 接受 / 1 重复拒 / 1 未知拒）
```

### 3.4 卡点定位与最小绕行

| 卡点 | 位置 | 绕行 |
|---|---|---|
| 官方循环无重复执行抑制 | `DefaultToolCallingManager.executeToolCall` 的 `for` 循环（§1.2c） | 入口层去重：同一轮内按 `toolCallId` 记集合，重复者只回"已执行"结果，不再次执行；工具自身也要幂等（写操作带幂等键） |
| 官方循环**没有外部回灌点**，无法实现"前端执行工具" | 循环完全封闭在 `OpenAiChatModel.internalStream/internalCall` 内，工具结果不经过任何可注入的 SPI | 用官方开关 `internalToolExecutionEnabled=false` 关掉内部执行，**自建显式循环**（用官方 `ChatModel` + 官方消息类型 `ToolResponseMessage` + 官方 `ToolCallingManager` 取工具定义），在自建循环里加 pending 注册表 |
| 官方工具方法拿不到"当前 toolCallId" | `ToolContext` 只暴露 `getContext()` 与 `getToolCallHistory()`，不暴露当前调用 id | 执行台账的 in-loop 记录里 `toolCallId=null`，靠 `(runId, name, arguments, 顺序)` 对账；要精确落 id 只能走 3.3 的显式循环（客户端传 id） |

---

## 4. V-d3 执行中取消 —— 【实测】有条件可行

### 4.1 逐项结论与实测证据

**① 循环在合理时间内终止 / ② 落正确终态 / ③ 对齐注册-检查模型**

```
case vd3-coop-cancel   longTask(8s, cooperative=true) 执行中取消
  工具进入执行=+1236ms   取消信号发出=+2136ms（工具已跑 900ms）  取消接口返回=+2141ms(registryRegistered=true)
  工具: CANCELLED_AFTER_6_SLICES，durationMs=1004      ← 取消后 ~57ms（≤1 个 200ms 分片）就返回
  SSE: start@5ms ... delta×6 ... done@2908ms
  done 事件: {"cancelled":true,"terminal":"CANCELLED","content":"我来执行这个长任务。","deltas":6}
  取消 → 流终止 = 776ms（含工具返回后的一次上游往返）
  注册表终态: registered=[]

case vd3-hard-cancel   longTask(3s, cooperative=false) 执行中取消（对照组：工具不检查标志）
  工具进入执行=+1346ms   取消信号发出=+2246ms
  工具: longTask:OK:3000ms                             ← 不响应取消，跑满 3s
  done 事件: {"cancelled":true,"terminal":"CANCELLED","emittedChars":0,"deltas":0}
  取消 → 流终止 = 2590ms（≈ 工具剩余时间 + 一次上游往返）  ← 【轮次边界生效】
```

**结论**：
- **终态语义正确**：两例都是 `done{cancelled:true, terminal:CANCELLED}`，与成功（`terminal=OK`）、
  失败（`error` 事件）三者互斥，不存在"成功/失败混淆"；已产出的部分文本保留在 `content` 字段里但不被当作完成。
- **生效点**：官方循环只在**事件边界**可被中断（`takeUntil` 在元素到来时判定），
  因此非协作式工具的取消落地时间 = 工具剩余时长 + 一次上游往返（实测 2590ms）；
  协作式工具在**分片边界**检查标志，可把等待压到 ≤200ms（实测 57ms），
  但工具返回后仍会走一次上游调用（实测 776ms 中约 730ms 是这次往返）——**这是官方循环无法消除的成本**，
  与 TC-AI-15「轮次边界生效」的预期一致。

### 4.2 与 `JobCancellationRegistry` 的注册-检查模型对齐

spike 的 `CancellationRegistry` 与参照分支 `JobCancellationRegistry` **四方法逐一对应**，只把 key 由 `Long jobId` 换成 `String runId`：

| `JobCancellationRegistry` | spike `CancellationRegistry` | 生命周期位置 |
|---|---|---|
| `register(Long)` | `register(String runId)` | 每轮 SSE 开始时 |
| `isCancelled(Long)` | `isCancelled(String runId)` | **`@Tool` 内按 200ms 分片检查**（= 作业执行器"批次之间检查"的同构做法） |
| `cancel(Long)` | `cancel(String runId)` | `POST /api/chat/{sessionId}/cancel` |
| `unregister(Long)` | `unregister(String runId)` | 轮次结束（`finally`） |

实测注册表生命周期：`registered=[runId]`（运行中）→ 取消后仍注册 → 轮次结束 `registered=[]`（附件 D 的 `cancelRegistryState`）。
**额外实测**：客户端断开（脚本 abort / 关页签）会走 `SseEmitter.onError` → 置取消标志 + 释放注册
（对齐参照实现的 `onError` 语义）；spike **刻意不注册 `onCompletion`**——参照实现用
`onCompletion` 置 `cancelled=true`，而该回调在**正常结束**时也会触发，会让 `done{cancelled}` 的判定产生竞态（§5.5）。

### 4.3 多实例缺口（JVM 内实现的已知边界）

- 取消标志在**本 JVM** 的 `ConcurrentHashMap` 里：多实例 + 负载均衡时，取消请求必须落在**持有该流的实例**上，
  否则 `isCancelled()` 永远是 false；本 spike 的取消接口返回里带了 `registryRegistered` 字段，
  实例 A 收到发往实例 B 的取消时可直接返回 `false`——**生产必须把这个"落到哪个实例"的问题解决**（方案见下）。
- 协作式检查依赖**工具自身可被分片**：长事务/阻塞 IO 类工具不检查标志 → 只能等轮次边界（§4.1 对照组已量化）。
- Redis 化（后续阶段）需要决定：取消标志的键结构、TTL、以及"订阅-发布"还是"轮询"；
  本卡只验证 JVM 内语义，**不覆盖**分布式一致性。

---

## 5. 被否决方案：坑与回归风险

### 5.1 方案一：`ChatClient` 流上直接 `.timeout(90s).retry(1)`（= 参照实现现状）

| 维度 | 实测发现 |
|---|---|
| 可行性 | **不可行（重试部分无效）** |
| 坑 1 | 重订阅必抛 `IllegalStateException: No StreamAdvisors available to execute`（advisor 链 `Deque.pop` 一次性）——**重试不产生第 2 次上游请求，等于没有重试**（`vd1-ref-first`：上游订阅次数 2、代理台账 1 次请求） |
| 坑 2 | 错误语义被污染：客户端拿到的是框架内部异常，而不是"上游断流"（`UPSTREAM_STREAM_ERROR` + `rawReason=IllegalStateException...`），排查会被带偏 |
| 坑 3 | `.timeout(90s)` 会**误杀长工具/长确认门**（工具执行期间下游无事件，§2.1④）；参考实现给确认门发的 ping 心跳不在 Reactor 流内，不能续期 |
| 坑 4 | 若把 advisor 链问题修好使重试真的生效，则**必然重放已产出内容与工具副作用**（`vd1-refmodel-mid` 实测客户端收到 `"我是我是"`）——即"修好一个坑掉进另一个坑" |
| 回归风险 | 参照分支已在用这套写法；任何"工具耗时可能超过 90s"的新工具（大导出、批量检查）都会触发坑 3；HITL 确认门 ≥90s 亦然 |

### 5.2 方案二：重试放在 `ChatModel.stream()` 上（绕开 ChatClient）

| 维度 | 实测发现 |
|---|---|
| 可行性 | 技术可行（能重订阅、能拿到第 2 次真实上游请求，`vd1-refmodel-first` error 语义正确） |
| 为何否决 | ① 重订阅 = 重新执行整条交互，**已产出的内容会重发**（`vd1-refmodel-mid`：`"我是我是"`）、**工具副作用会重放**；② 绕开 `ChatClient` 就丢掉了 memory advisor / 可观测 / 未来 advisor 能力（本卡对照实验因此没有历史落库）；③ "官方能力优先"的决策下，用 `ChatModel` 自建一遍 prompt 组装与记忆挂载属于重复造轮子 |
| 残留价值 | 作为**诊断手段**（区分"链一次性"与"重订阅语义"两类问题）保留；不作为落地路线 |

### 5.3 方案三：不加超时，靠客户端断开兜底（`plain`）

实测 `vd1-plain-hang`：客户端 15s 放弃后服务端线程**仍阻塞在 `blockLast()`**
（`CountDownLatch.await` → `BlockingSingleSubscriber.blockingGet`），只有对端真的关连接才解除（18.3s）。
线程池被占满即全站不可用；且"静默保活"的对端永远不会关连接。**否决。**

### 5.4 方案四：只把"是否已产出内容"作为重试门禁

这是我们**第一版实现的真实缺陷**，实测被抓到：`vd1-guarded-longtool` 的早期一轮里，
工具在超时取消时尚未写台账（台账在工具**返回**时才记录），"已产出内容"也是 false，
于是重试放行 → 工具被**执行了两次**（该轮日志出现两条 `longTask:OK:6000ms`、上游请求 2 次）。
修正：副作用判定同时看**活动信标**（工具**进入执行**即登记）；
修正后同一 case（本轮归档的 `vd1-guarded-longtool`）实测为 `attempts=1`、台账只有 1 条。
> 说明：早期那一轮的原始日志未归档（本轮开始前已清理 evidence），但缺陷与修正在
> `ResilientChatService`（`sideEffect` 判定处）与 `ToolActivityBeacon.hasActivity` 的注释里留痕，
> 且复现方式明确：把 `beacon.hasActivity(...)` 从条件里去掉即可重现。
**回归风险**：任何"发现工具副作用"的判定都必须是"开始执行"而非"执行完成"，否则并发/超时场景会漏判。

### 5.5 方案五：用 `SseEmitter.onCompletion` 置取消标志（参照实现写法）

`onCompletion` 在**正常完成**时也会触发，会让"本轮到底是取消还是正常结束"的判定产生竞态（时序决定结果）。
spike 只用 `onError` / `onTimeout` 承载"客户端断开"，`done` 的 `cancelled` 字段由运行状态决定。**建议同步修正参照分支。**

---

## 6. 生产化注意事项

1. **超时阈值规则（硬约束）**：`firstEventTimeout` 与 `interEventTimeout` 都必须 **> 最长工具执行时间**
   （含 HITL 确认门等待）。参照实现的 90s 需要按"最长工具 + 最大确认门等待"重新核定；
   若要支持"人审等待远超 90s"，必须把确认门移出流式管道（先落待确认状态、回复结束后另行推进），
   因为**官方循环在等待期间不产生下游事件**。
2. **重试只看这四件事**：剩余次数、总时长预算、本轮是否已产出、本轮是否已产生副作用。
   建议默认 `max-attempts=2`、`total-budget` = 单轮业务可接受上限（spike 用 20s 便于实验，生产应远大于超时之和）。
3. **静默截断需要业务侧完整性校验**【实测 + 推断】：官方流在 TCP 正常 FIN 且无 `[DONE]` 时会报"成功"
   （`vd1-fin-content`：`terminal=OK, emittedChars=4`；`vd1-truncate-fin`：`terminal=OK, emittedChars=0`）。
   建议：① 校验 finish_reason/结束标记；② 对"空回复"直接判失败；③ 关键结果做业务级确认（如导出任务用任务状态对账）。
4. **取消**：协作式分片检查（≤1 个分片）值得推广到所有长工具；多实例下取消标志需外部化（Redis），
   且**取消请求必须能路由到持有流的实例**（否则应返回"未找到"而不是静默忽略）。
5. **同 sessionId 并发**：本 spike 的会话状态是**按 sessionId**（不是按 runId）持有的，
   并发两轮会互相覆盖 `terminal/content/attempts`（实验中被观察到）。生产应把运行态按 runId 隔离
   （或明确禁止同会话并发），并让 `cancel` 明确指定 runId。
6. **可观测**：建议保留三类证据出口——每轮的尝试记录（次数/耗时/失败原因）、工具执行台账（含参数与耗时）、
   取消/重试事件；本 spike 的 `/api/dev/*` 是等价物（生产应收敛为内部管理端点）。

---

## 7. 证据等级标注

| 结论 | 等级 | 依据 |
|---|---|---|
| V-d1① 有界重试（2 次 / 10.0s 上限）+ 明确失败语义 | **【实测】** | 真实进程 + 真实 deepseek + 代理注入真实 TCP 故障，SSE 事件与里程碑时间戳留档（附件 B） |
| V-d1② 已产出内容不重试（guarded 成立；naive 重试会重发） | **【实测】** | `vd1-guarded-mid/tooldup` 无重放 + `vd1-refmodel-mid` 客户端收到 `"我是我是"` + 探针 D/E 的 `ABAB` |
| V-d1③ 无超时会无限挂起（线程阻塞栈） | **【实测】** | `vd1-plain-hang` 线程栈 + 终态 null + 强制断连后才解除 |
| V-d1④ 工具执行期间无下游事件 → 超时误杀长工具 | **【实测】** | 8s 工具被 6s/5s 超时打断（台账 duration 5999ms/5002ms）+ STREAM_ITEM 全程 `textLen=-1`、工具期 delta=0 |
| V-d1⑤ 静默截断被当成成功 | **【实测】** | `vd1-fin-content` / `vd1-truncate-fin` 终态 `OK` 而无 `[DONE]` |
| 官方 `RetryTemplate` 不覆盖流式 | **【实测-字节码】** | `javap -c OpenAiChatModel`：`retryTemplate.execute` 仅出现在 `lambda$internalCall$3` |
| `ChatClient` advisor 链一次性（`Deque.pop`）导致重订阅必失败 | **【实测-字节码 + 实测-报文】** | `javap -c DefaultAroundAdvisorChain` + `REF_UPSTREAM_SUBSCRIBE n=2` 后立刻 `IllegalStateException`、代理台账仅 1 次请求 |
| 官方工具循环对重复 `toolCallId` 不去重 | **【实测-字节码 + 实测】** | `javap -c DefaultToolCallingManager.executeToolCall` + `vd2-spi-dup` 执行 2 次、历史 2 条同 id 响应 |
| V-d2③ 前端执行工具路径的 pending 注册表幂等 | **【实测】** | `vd2-fronttool` 三次提交的响应 + 消息序列 + 台账 |
| V-d3①②③ 取消（776ms 协作式 / 2590ms 轮次边界）+ 终态 CANCELLED + 注册表生命周期 | **【实测】** | 毫秒级时间线、工具台账、活动信标、注册表快照（附件 D） |
| "确认门等待 >90s 会被 `.timeout(90s)` 判超时" | **【推断】** | 由 8s 工具 + 6s 超时的同构实测外推；未直接构造 90s 确认门 |
| 生产阈值取值（首包/事件间/预算）、是否要求"空回复即失败" | **【推断】** | 基于本卡数据形态推算，未压测，需业务方拍板 |
| 多实例取消标志的 Redis 化键结构与路由方案 | **未验证** | 明确留待后续阶段 |

---

## 8. 推翻条件

1. Spring AI 在 1.1.x 线修复/改变 advisor 链的重订阅行为（例如链改为可重复构建）→ §2.2 的卡点消失，
   应重新评估"能否直接用 Reactor retry"，但 §2.1② 的"内容重放"问题仍在，落地写法不应简化成 `.retry()`。
2. 官方提供**流式重试/续传**能力（如流式 API 支持 `stream resumption` 或官方 `RetryTemplate` 覆盖流式）→
   §2.3 的自建边界应改为调用官方能力（DC-05）。
3. 官方提供**工具执行的外部注入点/幂等钩子**（如可插拔的 tool-result 入口或内置去重）→ §3.4 的自建注册表应删除。
4. 业务接受"不做重试"（断流即失败 + 前端重发）→ V-d1 可简化为"双超时 + 明确失败语义"，
   但 §2.1④ 的超时阈值规则与 §2.1⑤ 的静默截断校验仍然必须落地。
5. 部署形态改为**单实例**且无水平扩容计划 → §4.3 的多实例缺口可暂缓，`CancellationRegistry` 可先用本实现。
6. 观测到"长工具被超时误杀"的实际线上问题 → 必须立即按 §6.1 重定阈值，或把长工具改成异步作业 + 轮询。

---

## 9. 收尾确认

| 项 | 结果 |
|---|---|
| 实验进程 | 仅停止本任务自启的进程（spike 应用 PID 8848 / 27728，故障代理 PID 17116，以及构建前的旧实例），未触碰其他服务 |
| 端口 | `netstat` 确认 **18303 / 18304 均无监听**（无进程占用） |
| 上游副作用 | 仅调用真实 `deepseek-flash` 做对话；无写操作、无数据落库 |
| 密钥 | 全程仅环境变量 `DEEPSEEK_API_KEY`；决策卡与全部附件、evidence 目录均无 key（`***`） |
| 脱敏 | 决策卡与附件 A–E 已复检：无本机绝对路径（`<MAIN_V2>` / `<MAVEN_HOME>` / `<JDK_HOME>` / `<USER_HOME>` 占位）、无 API key、无用户名；锚点/明文密钥未落盘 |
| 仓库改动范围 | 仅新增 `spike/sp01d/**` 与 `docs/spike/SP-01d-*`；`spike/sp01c` 只读参照，未改动；仓库其他目录未改动 |
| 原始运行日志 | `spike/sp01d/logs/`（约 2.2MB，含本机绝对路径）已删除；关键行已脱敏归档为 `docs/spike/SP-01d-附件-E`。原始结构化证据保留在 `spike/sp01d/evidence/`（56 个 JSON，无绝对路径/无密钥），可重跑 `scripts/make-attachments.mjs` 重新生成附件 |
| git | 未执行任何 git 写操作（仅 `git status` 等只读命令） |
