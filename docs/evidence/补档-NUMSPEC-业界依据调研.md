> 来源=仓库外过程报告补档（2026-10-11，DC-19 前历史文件，署名信息以文内为准）

# 「数字规格」业界依据调研（在线实读版）

> 项目背景：Spring Boot 3 + Vue3 的企业内部配置管理平台。功能含 AI 对话辅助、SSE 实时推送、任务/作业编排、导入导出向导、人机确认门（HITL）。
> 场景特征：**内网部署、用户量小（数十~数百人）、单实例为主、无公网大流量与多租户争抢**。
> 检索日期：2026-10-10。方法：WebSearch 定位来源 → FetchURL/curl 实读原文，逐条摘录。
> 阅读约定：**置信度** 三档 —— `多源一致`（≥2 个独立来源，含官方或多方一致）/ `单源`（仅一处，或仅官方文档一处）/ `类推`（由已读来源推导，非原文直述）。
> 「未检到可靠来源」的条目一律显式标注，不做数字填充。

---

## 1. HTTP 客户端超时（connect / read / write）

### 主流结论

| 客户端 | connect | read | write | 整体 call | 说明 |
|---|---|---|---|---|---|
| OkHttp（源码默认） | 10s | 10s | 10s | 0（不限制） | `callTimeout=0` 意味着"没有整体上限"，是踩坑主因 |
| axios（源码默认） | — | — | — | **0 = 不超时** | 默认无限等待，必须显式设置 |
| Java 11 `HttpClient` / `HttpRequest` | 未设置 = 无限 | 未设置 = 无限 | — | 未设置 = 无限 | 官方 javadoc 只对 `HttpRequest.timeout` 明确"不设置即无限" |
| AWS SDK for Java 2.x | 官方示例 5s | 30s（socketTimeout） | 30s（Netty） | **默认不设** | 官方明确"SDK 默认不设置 API 超时" |
| OpenAI / Anthropic Python SDK | 5s | — | — | **600s (10 min)** | 云 AI API 的业界实际默认值 |

**推荐区间（对内网企业应用）**：connect 3–5s、read 30–60s、write 30–60s、整体 callTimeout 60–120s；前端 axios 必须显式设 timeout（否则永挂）。

### 来源与原文摘录

1. **OkHttp 官方源码** `OkHttpClient.kt`（实读 raw 源码，第 615–619 行）
   - 摘录：`internal var callTimeout = 0` / `internal var connectTimeout = 10_000` / `internal var readTimeout = 10_000` / `internal var writeTimeout = 10_000` / `internal var pingInterval = 0`
   - URL: https://github.com/square/okhttp/blob/master/okhttp/src/commonJvmAndroid/kotlin/okhttp3/OkHttpClient.kt
   - 置信度：**单源（官方源码，等同权威）**

2. **axios 官方源码** `lib/defaults/index.js`（实读 raw，第 129–132 行）
   - 摘录："A timeout in milliseconds to abort a request. If set to 0 (default) a timeout is not created." → `timeout: 0,`
   - URL: https://github.com/axios/axios/blob/main/lib/defaults/index.js （文档页：https://axios-http.com/docs/req_config ，仅描述语义未写默认值）
   - 置信度：**单源（官方源码）**

3. **Oracle javadoc `HttpRequest.Builder#timeout`**（实读）
   - 摘录："The effect of not setting a timeout is the same as setting an infinite Duration, i.e. block forever."
   - URL: https://docs.oracle.com/en/java/javase/21/docs/api/java.net.http/java/net/http/HttpRequest.Builder.html
   - 关于 `HttpClient.Builder#connectTimeout`：javadoc 只说明语义（"the duration to allow the underlying connection to be established"），**未写出默认值**；未设置即无限制为**类推**。
   - 另：JDK 模块参数 `jdk.httpclient.keepalive.timeout` **默认 30 秒**（Oracle 官方 module-summary，来自检索摘要）URL: https://docs.oracle.com/en/java/javase/25/docs/api/java.net.http/module-summary.html
   - 置信度：`HttpRequest.timeout` **单源（官方）**；connectTimeout 默认值 **未检到官方原文（类推）**

4. **AWS SDK for Java 2.x「Configure timeouts」官方文档**（实读）
   - 摘录："The SDK for Java 2.x doesn't set API call timeouts or individual API call attempt timeouts by default. Set timeouts for both individual attempts and the entire request."
   - 官方示例值：`connectionTimeout(Duration.ofSeconds(5L))`、`socketTimeout(Duration.ofSeconds(30L))`、Netty `readTimeout/writeTimeout 30s`、`tlsNegotiationTimeout 3s`、`apiCallAttemptTimeout 30s`、`apiCallTimeout 2min`；约束"API call timeout ≥ API call attempt timeout ≥ HTTP client timeouts"。
   - URL: https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/timeouts.html
   - 置信度：**单源（官方）**

5. **OpenAI / Anthropic Python SDK 源码常量**（实读 raw）
   - OpenAI `_constants.py`：`# default timeout is 10 minutes` / `DEFAULT_TIMEOUT = httpx2.Timeout(timeout=600, connect=5.0)` / `DEFAULT_MAX_RETRIES = 2`
     URL: https://github.com/openai/openai-python/blob/main/src/openai/_constants.py
   - Anthropic `_constants.py`：`# default timeout is 10 minutes` / `DEFAULT_TIMEOUT = httpx2.Timeout(timeout=10 * 60, connect=5.0)` / `DEFAULT_MAX_RETRIES = 2`
     URL: https://github.com/anthropics/anthropic-sdk-python/blob/main/src/anthropic/_constants.py
   - 置信度：**多源一致（两家 SDK 完全同构）**

6. Retrofit 2 默认值（次级）："Call timeout – 0 (no timeout); Connection timeout – 10 seconds; Read timeout – 10 seconds; Write timeout – 10 seconds"。URL: https://howtodoinjava.com/retrofit2/connection-timeout-exception/ —— 与 OkHttp 源码一致，作为佐证。

### 对本项目的适用性

- 全内网 ⇒ 不设 connect 超时=不设下限，一旦后端半死会长期占用 Tomcat 线程，**必须显式设 connect 3–5s**。
- AI 对话属于长响应：不要把全局 axios timeout 设成 30s，会切掉长回答；建议 **分通道配置**：普通接口 30–60s，AI 通道 120–300s（配合 SSE 流式，见第 2 类），AI 后端向上游的 HTTP 客户端 read 设 60–120s。
- 公网大流量场景常用"read 10s + 快速重试"；内网小用户量应反过来：**少重试、长超时**（重试带来的重复副作用比超时代价更大）。

---

## 2. SSE 心跳间隔与写超时（含代理 60s 切断）

### 主流结论

- **心跳间隔：15–30s**。规范作者建议"每 15 秒左右发一条注释行"；实践公式为 `heartbeat < min(nginx proxy_read_timeout, ALB idle timeout, CF proxy read timeout)`。
- **代理侧硬限制：nginx `proxy_read_timeout` 默认 60s、AWS ALB idle timeout 默认 60s** —— 两者是"SSE 60s 断流"的头号原因。
- **Spring 侧坑**：`SseEmitter` 无参构造继承 `spring.mvc.async.request-timeout`，而该值未设置时取 Servlet 容器默认 —— **Tomcat 是 30s**，比 nginx 的 60s 更早切断。必须显式设置（建议 0=永不过期或 ≥30min）。

### 来源与原文摘录

1. **WHATWG HTML Living Standard §9.2.6（权威规范）**（实读）
   - 摘录："Legacy proxy servers are known to, in certain cases, drop HTTP connections after a short timeout. To protect against such proxy servers, authors can include a comment line (one starting with a ':' character) every 15 seconds or so."
   - URL: https://html.spec.whatwg.org/multipage/server-sent-events.html
   - 置信度：**单源（但为权威规范）** → 15s 是"规范级建议"

2. **nginx 官方文档 `proxy_read_timeout`**（实读原文表格）
   - 摘录：`Default: proxy_read_timeout 60s;`
   - URL: https://nginx.org/en/docs/http/ngx_http_proxy_module.html
   - 置信度：**单源（官方）**

3. **AWS ALB idle timeout 默认 60s**：未直读 AWS 官方页面；多个独立来源一致引用（"AWS Application Load Balancer (ALB): The default idle timeout is 60 seconds"）URL: https://fixdevs.com/blog/econnreset-socket-hang-up/ 、https://subodhkc.com/blog/fastapi-websocket-1006-llm-streaming-disconnect
   - 置信度：**多源（次级，一致引用官方值）** —— 已在"未检到"清单登记

4. **Spring Framework 官方文档 Asynchronous Requests**（实读摘要）
   - 摘录："The default timeout value for async requests depends on the underlying Servlet container, unless it is set explicitly."
   - URL: https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-ann-async.html
   - **Spring Boot issue（维护者更正）**：Javadoc 写"e.g. 10 seconds on Tomcat"，维护者指出 "actually the default timeout in Tomcat is 30 seconds"。
     URL: https://github.com/spring-projects/spring-boot/issues/19056
   - 另一 issue 观察："it happens with embedded Tomcat that arrives with Boot, where asyncTimeout appears to be 30 seconds." URL: https://github.com/spring-projects/spring-boot/issues/4021
   - 置信度：**多源一致（Spring 官方 + 维护者线程）**

5. **SseEmitter javadoc**：`Create a SseEmitter with a custom timeout value. By default not set in which case the default configured in the MVC Java Config or the MVC namespace is used` URL: https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/web/servlet/mvc/method/annotation/SseEmitter.html
   - 置信度：**单源（官方）**

6. 实践公式（次级）：`heartbeat < min(nginx proxy_read_timeout # default 60s, ALB / ingress idle timeout # default 60s on AWS ALB, Cloudflare proxy read timeout)` URL: https://medium.com/boot-camp-java/server-sent-events-in-spring-boot-4-sseemitter-flux-and-the-one-that-actually-scales-d2010e8642cf
   - 置信度：**类推（由 1–3 的数值组合而来）**

### 对本项目的适用性

- **推荐心跳 15s**（规范值，且 < nginx 60s 有 4 倍余量）；若走网关/反代，取 `min(上游代理超时)/2`。
- Spring 侧务必显式 `spring.mvc.async.request-timeout`（或 `new SseEmitter(0L)`）；内网无代理时可放宽，但**只要有 nginx/网关就必须对齐**。
- SSE 写超时要区分两个概念：**连接空闲超时**（靠心跳保活）与**单次事件写阻塞**（靠 `emitter.completeWithError` + 前端重连 + `Last-Event-ID` 补发）。内网单实例下没有粘性会话/多实例广播问题（大流量场景才需要 Redis pub/sub，本项目可跳过）。

---

## 3. 前端轮询间隔与「条件轮询 / 指数退避」

### 主流结论

- **机制层（有官方文档）**：`refetchInterval` 支持传**函数**返回 `false` 停止轮询 → 这就是业界标准做法"条件轮询"；默认**页面失焦即暂停**轮询。
- **数值层（无规范，只有实践）**：
  - 仪表盘/列表自动刷新默认档位常见 **30s / 1m / 5m**（Grafana 默认档位包含 5s、10s、30s、1m、5m……1d；服务器 `min_refresh_interval` 默认 5s 作为下限保护）。
  - 任务状态轮询常见 **1–5s**（在建任务/长任务进行中）；错误后转指数退避，上限 30s。
  - Temporal 官方轮询设计模式示例使用 **60s** 固定间隔（低频外部状态探测）。

### 来源与原文摘录

1. **TanStack Query 官方 Polling 文档**（实读）
   - 摘录："Pass a function instead of a number to compute the interval from the current query. The function receives the Query object and should return a number in ms or false to stop polling." / "By default, polling pauses when the browser tab loses focus."
   - URL: https://tanstack.com/query/latest/docs/framework/react/guides/polling
   - 置信度：**单源（官方）** —— 但这是"条件轮询"的标准机制出处

2. **SWR 官方 Error Handling 文档**（实读）
   - 摘录："SWR uses the exponential backoff algorithm to retry the request on error." + `onErrorRetry` 示例（`if (retryCount >= 10) return`、`setTimeout(() => revalidate({ retryCount }), 5000)`）
   - URL: https://swr.vercel.app/docs/error-handling
   - 置信度：**单源（官方）** —— 轮询失败后指数退避的官方实现依据

3. **Temporal 官方轮询设计模式**（检索摘要）
   - 摘录："Setting the backoff coefficient to 1 creates a fixed retry interval. The initial interval of 60 seconds sets the polling frequency."
   - URL: https://docs.temporal.io/design-patterns/polling
   - 置信度：**单源（官方）**

4. Grafana 默认刷新档位与下限：`grafana.ini` / `defaults.ini` 中 "Minimum dashboard refresh interval... Per default this is 5 seconds."，档位含 5s/10s/30s/1m/5m/30m/1h/…（次级，一致引用官方配置）
   URL: https://questdb.com/blog/increase-grafana-refresh-rate-frequency/ 、https://community.grafana.com/t/grafana-prometheus-windowsexporter-works-to-star-with-but-then-breaks/148006
   - 置信度：**多源（次级）**

### 对本项目的适用性

- 本项目轮询只发生在**任务/作业状态**这一处，用户量小 → 不需要省流量式长间隔。
- **推荐**：任务进行中 2s（条件轮询，仅当存在 running 任务时启动）；连续失败后指数退避（2s→4s→8s→16s→30s 封顶，对齐 SWR 的 `retryCount` 上限思路）；任务完成/页面隐藏立即停止（`refetchInterval` 返回 `false`）。
- 列表页若需要"自动刷新"，建议 **30s 且默认关闭**（用户手动开启），避免内网浪费+用户输入被打断；大流量公网场景的"全员 5s 轮询"模式在本项目没有收益。

---

## 4. 重试次数与退避策略

### 主流结论

- **算法**：exponential backoff **+ jitter** 是业界标准（AWS 官方结论原文："should be considered a standard approach for remote clients"）。变体：Full / Equal / Decorrelated Jitter，**Full Jitter 综合最优**。
- **maxAttempts 常见值**：**3**（AWS SDK 默认 = 1 次初始 + 2 次重试；DynamoDB 4）；Azure SDK **3 次重试**、base 0.8s、max delay 60s、max total 120s；大模型 SDK **2 次重试**（base 0.5s、max delay 8s）。
- **退避基值与上限**：AWS 新默认 transient base 50ms / throttling base 1s，单次延迟上限 20s；Azure 0.8s/60s 帽。

### 来源与原文摘录

1. **AWS Architecture Blog《Exponential Backoff And Jitter》（Marc Brooker，实读全文）**
   - 摘录："The return on implementation complexity of using jittered backoff is huge, and it should be considered a standard approach for remote clients."
   - 摘录："Of the jittered approaches, 'Equal Jitter' is the loser. It does slightly more work than 'Full Jitter', and takes much longer."（Full Jitter 为 `sleep = random_between(0, min(cap, base * 2^attempt))`）
   - URL: https://aws.amazon.com/blogs/architecture/exponential-backoff-and-jitter/
   - 置信度：**单源（但为业界公认原始出处，各后续文章均回溯至此）**

2. **AWS SDK 官方 Retry behavior 文档**（实读）
   - 摘录："With the default of 3 max attempts (1 initial request + 2 retries), the backoff cap is never reached."
   - 摘录公式："delay = random(0, 1) × min(20,000 ms, base_delay × 2^retry)"；"base_delay is 50 ms for transient errors or 1,000 ms for throttling errors"；"The maximum backoff cap is 20 seconds."
   - URL: https://docs.aws.amazon.com/sdkref/latest/guide/feature-retry-behavior.html
   - 置信度：**单源（官方）**

3. **Azure SDK 官方（MS Learn）**
   - 摘录："Maximum retry attempts: 3 · Retry mode: Exponential backoff · Base delay: 0.8 seconds · Maximum delay: 60 seconds · Maximum total retry time: 120 seconds"
   - URL: https://learn.microsoft.com/en-us/azure/developer/python/sdk/fundamentals/http-pipeline-retries
   - 置信度：**单源（官方）**

4. **OpenAI / Anthropic Python SDK 源码**：`DEFAULT_MAX_RETRIES = 2`、`INITIAL_RETRY_DELAY = 0.5`、`MAX_RETRY_DELAY = 8.0`（两处源码，见第 1 类 URL）
   - 置信度：**多源一致**

### 对本项目的适用性

- 内网企业应用的失败多是"目标服务没启动/权限错/数据错"，**重试大多无效且会放大副作用**。建议：
  - 幂等读/查询类：maxAttempts=3，Full Jitter，base 200ms，cap 5s。
  - 有副作用的写/调外部系统类：**默认不自动重试**（或仅对连接层瞬断重试 1 次），改由任务编排层做"显式重试"按钮。
  - 至少一层重试（不要浏览器 axios 重试 + 后端重试 + 网关重试三层叠加）——AWS 文章强调多层的代价，本项目单实例下更要在后端统一收口。

---

## 5. 人机确认门（HITL approval）超时

### 主流结论

**业界没有统一"标准值"** —— 这是本次调研中唯一"无主流共识"的类别。可确证的只有三条事实：

1. **主流 AI Agent 框架默认不设超时**：LangGraph `interrupt()` "The thread waits indefinitely. LangGraph has no built-in timeout or escalation."（需自己用 `asyncio.timeout`/`Command(resume=...)` 包一层）。
2. **工作流引擎默认也不设超时，且官方明确警告会永久卡死**：AWS Step Functions "By default, the Amazon States Language doesn't specify timeouts... an execution is stuck waiting for a response that will never come." 官方最佳实践要求"specify a reasonable timeout"。
3. **惯例区间**（按审批形态分）：
   - 交互内即时确认（弹窗"是否执行"）：**数分钟**（5–15 min）；
   - 工单式人工审批：**24 小时**（IAM 领域最常引用的到期值）/ 1–3 个工作日 SLA；
   - 访问申请（Access Request）类：**7–90 天**（SailPoint 默认 90 天）。

### 来源与原文摘录

1. **LangGraph 无内置超时**（次级，博客明确指出）
   - 摘录："What happens if nobody responds to a LangGraph interrupt? The thread waits indefinitely. LangGraph has no built-in timeout or escalation."
   - URL: https://thehandover.xyz/blog/langgraph-interrupt-approval （官方 HITL 模式文档：https://docs.langchain.com/oss/python/langchain/frontend/human-in-the-loop ）
   - 置信度：**单源（次级）**，但与 LangGraph 只提供 pause/resume 的官方 API 形态一致

2. **AWS Step Functions 官方最佳实践（超时缺失会卡死）**（检索摘要，官方域名）
   - 摘录："Without an explicit timeout, Step Functions often relies solely on a response from an activity worker to know that a task is complete. If something goes wrong and the TimeoutSeconds field isn't specified for an Activity or Task state, an execution is stuck waiting for a response that will never come."
   - URL: https://docs.aws.amazon.com/step-functions/latest/dg/sfn-best-practices.html
   - 置信度：**单源（官方）**

3. **人工审批 24 小时到期**（次级）
   - 摘录："Approval requests typically expire after 24 hours. If a request expires, the user must submit a new activation request."
   - URL: https://www.penthara.com/auto-vs-manual-approval-for-microsoft-pim/ （Microsoft PIM 实施博客）
   - 置信度：**单源（次级）**

4. **Step Functions 回调式人工门的实践取值**：`"TimeoutSeconds": 86400, "HeartbeatSeconds": 3600`（即 24h 超时、1h 心跳）URL: https://hidekazu-konishi.com/entry/step_functions_orchestration_patterns_for_generative_ai.html
   - 置信度：**单源（次级）**

5. **SailPoint 访问申请默认到期 90 天**（厂商公告）
   - 摘录："By default, access requests will now expire after 90 days if they have not been fully approved by that time. Admins can configure a shorter span..."
   - URL: https://developer.sailpoint.com/discuss/t/enhancement-approvals-expiration-governance-group-visibility-and-more/193947
   - 置信度：**单源（厂商文档）**

6. **企业审批 SLA 案例**：Oracle BPM 社区贴出"每级 L1/L2/L3 有 3 个工作日 SLA，未响应则自动升级；L3 超时自动批准" URL: https://community.oracle.com/customerconnect/discussion/974330/
   - 置信度：**单源（社区案例）**

### 对本项目的适用性

- 本项目是**内网、用户量小、有明确操作人**的配置管理平台：HITL 更多是"AI 要动生产配置前让人点确认"，不是跨部门审批流。
- **推荐值**：
  - 交互式确认门（AI 工具调用/危险操作）超时 **10 分钟**，超时后**自动拒绝并释放锁**（fail-closed，不自动放行）；
  - 若确认门背后是"待办工单"形态，超时 **24 小时**，到期自动拒绝 + 通知发起人；
  - 无论哪种，**必须有兜底超时**（这是唯一有官方依据的硬结论：不设超时 = 永久卡死），且超时后状态机必须能前进（拒绝/升级），不能留在 pending。
- 不要把 HITL 超时设得比上层代理/网关超时还长——SSE 断线不影响审批本身（状态在服务端），但前端要能重新拉取"待确认列表"。

---

## 6. 前端工具调用超时（AI 工具调用执行预算）

### 主流结论

- **没有"协议规定的"默认值**：MCP 规范只要求 SHOULD 设超时；两家大模型 SDK 的默认超时是**整体请求 600s（10 分钟）+ connect 5s**；工具级超时都要开发者显式配置。
- **可套用的分层预算**（Vercel AI SDK 官方给出的命名维度可作事实标准）：
  - 单工具执行 `toolMs`：**5–60s**（工具是外部 HTTP 调用时取 30–60s）；
  - 单步 `stepMs`（模型生成 + 重试 + 本地工具执行）：**10–60s**；
  - 整体 `totalMs`：**5–10 分钟**；
  - 流式首块 `firstChunkMs` 10s / 块间 `chunkMs` 5s（官方示例值）。
- MCP 实现层的实际默认常见 **60s**；某些托管网关更激进（**7–10s** 无响应即断开）。

### 来源与原文摘录

1. **MCP 规范官方 Lifecycle → Timeouts**（实读）
   - 摘录："Implementations SHOULD establish timeouts for all sent requests, to prevent hung connections and resource exhaustion." / "However, implementations SHOULD always enforce a maximum timeout, regardless of progress notifications, to limit the impact of a misbehaving client or server."
   - URL: https://modelcontextprotocol.io/specification/2025-06-18/basic/lifecycle
   - 置信度：**单源（官方规范）** —— 只规定"要设"，不规定"设多少"

2. **OpenAI / Anthropic Python SDK**：整体默认 **600s**、connect **5s**（源码见第 1 类）
   - 置信度：**多源一致**

3. **OpenAI Agents SDK 官方 Tools 文档**（实读）
   - 摘录："You can set per-call timeouts for async function tools with `@function_tool(timeout=...)`." / 默认行为 `timeout_behavior="error_as_result"` "/ the SDK treats both 0 and None as no explicit timeout"
   - URL: https://openai.github.io/openai-agents-python/tools/
   - 置信度：**单源（官方）** —— 官方**未给出默认秒数**（默认即不超时）

4. **Vercel AI SDK 官方 Settings 文档**（实读）
   - 摘录：`toolMs`："The default timeout for all tool executions. If a tool takes longer, it aborts and returns a tool-error so the model can respond or retry."；`stepMs`："The timeout for each complete step, including model generation, retries, and local tool execution."；另 `maxRetries` "Default: 2"
   - 官方示例：`toolMs: 5000`、`firstChunkMs: 10000`、`chunkMs: 5000`、`stepMs: 10000`、`totalMs: 60000`
   - URL: https://ai-sdk.dev/docs/ai-sdk-core/settings
   - 置信度：**单源（官方）**

5. **MCP 实现默认 60s**（次级）："Set up an MCP server with a tool designed to take longer than the default client timeout (e.g., >60 seconds)" URL: https://github.com/lmstudio-ai/lmstudio-bug-tracker/issues/727 ；Reddit r/mcp "How to increase default timeout limit of 60sec"
   - 置信度：**多源（次级）**
6. **托管网关 7–10s 即断**（次级）："If the tool doesn't respond within ~7-10 seconds, the connection may drop with a 424 (Failed Dependency)" URL: https://dev.to/aws/fix-mcp-timeouts-async-handleid-pattern-8ek
   - 置信度：**单源（次级）**

### 对本项目的适用性

- 本项目"前端工具调用"= AI 对话里触发的前端函数（如打开某个配置页、拉取表单数据）或后端带 tool 的调用。
- **推荐**：单工具 30s（纯前端/内网接口足够）、需要查库或跨系统的工具 60s；整体 AI 轮次 5 分钟；超时一律走 `error_as_result` 语义（把"超时"作为工具结果回给模型，让模型改口径/换工具），而不是抛异常崩掉对话——这是 OpenAI Agents SDK 的默认行为。
- 长耗时动作（导出、批量下发）**不要放在工具调用预算内**，应改为"提交任务 → 返回 taskId → 靠轮询/SSE 报进度"（与本项目任务编排能力天然契合）。

---

## 7. 数据库连接池（HikariCP）

### 主流结论（官方默认值，直接可套）

| 参数 | 官方默认 | 说明 |
|---|---|---|
| `maximumPoolSize` | **10** | 池上限（含 idle + in-use） |
| `connectionTimeout` | **30000ms** | 借连接等待上限，最低 250ms |
| `idleTimeout` | **600000ms (10min)** | 仅当 `minimumIdle < maximumPoolSize` 生效，最低 10s |
| `keepaliveTime` | **120000ms (2min)** | "a value in the range of minutes is most desirable"，最低 30s |
| `maxLifetime` | **1800000ms (30min)** | 官方强烈建议设置，且**必须比 DB/基础设施的连接上限短若干秒** |
| `validationTimeout` | **5000ms** | 必须 < connectionTimeout |
| `minimumIdle` | 默认 = maximumPoolSize | 官方建议**不设**，让池表现为固定大小 |
| `leakDetectionThreshold` | 0（关闭） | 最低可启用值 2000ms |

### 来源与原文摘录

1. **HikariCP 官方 README（实读 dev 分支全文）**
   - `maximumPoolSize`："Default: 10"；`connectionTimeout`："Default: 30000 (30 seconds)"
   - `keepaliveTime`："The minimum allowed value is 30000ms (30 seconds), but a value in the range of minutes is most desirable. Default: 120000 (2 minutes)"
   - `maxLifetime`："We strongly recommend setting this value, and it should be several seconds shorter than any database or infrastructure imposed connection time limit. Default: 1800000 (30 minutes)"
   - `minimumIdle`："for maximum performance and responsiveness to spike demands, we recommend not setting this value and instead allowing HikariCP to act as a fixed size connection pool. Default: same as maximumPoolSize"
   - 顶部 IMPORTANT："In order to avoid a rare condition where the pool goes to zero and does not recover it is necessary to configure TCP keepalive."
   - URL: https://github.com/brettwooldridge/HikariCP
   - 置信度：**单源（官方源码级文档）**

2. **官方池大小方法论**（未直读 wiki 正文，仅 README 指向）：`About-Pool-Sizing` —— "too many connections have a clear and demonstrable negative impact on performance"（README 摘要原文，实读）
   - URL: https://github.com/brettwooldridge/HikariCP/wiki/About-Pool-Sizing
   - 置信度：**类推**（README 转述 + 经典公式 `connections = (core_count * 2) + effective_spindle_count` 常见于该 wiki，但本次未直读原文）

### 对本项目的适用性

- 内网小用户量、单实例 ⇒ **默认值基本可直接用**：`maximumPoolSize: 10`（甚至 5–8）、`connectionTimeout: 3000–5000`（内网借连接慢就是异常，快速失败更好）。
- 唯一必须主动做的：
  - `maxLifetime` **小于** 任何中间层（云 RDS/ProxySQL/防火墙）的连接回收时间（若 MySQL `wait_timeout=28800s` 则 30min 默认无风险）；
  - 与 DB 的空闲断连问题用 `keepaliveTime`（2min 默认）解决，别靠调大 `maxLifetime`；
  - 打开 `leakDetectionThreshold: 60000`（内网排障成本低，值得开）。
- 不要去"按并发数×N"放大池子——官方明确反对，内网更是没有必要。

---

## 8. 分页大小主流默认

### 主流结论

- **默认 20–50，上限 100** 是事实标准。两个可直读的官方锚点：**GitHub 默认 30 / 上限 100**；**Stripe 默认 10 / 范围 1–100**。
- 大厂 API 页大小普遍落在 10（Stripe）、20（多数内部 API 约定）、30（GitHub）、100（上限）。

### 来源与原文摘录

1. **GitHub 官方文档 Using pagination in the REST API**（检索摘要，docs.github.com）
   - 摘录："For most endpoints, the maximum value of per_page is 100. If you specify a value greater than the maximum, GitHub does not return an error."
   - 默认 30 的表述见 GitHub 文档/历史文档："Requests that return multiple items will be paginated to 30 items by default... a custom page size up to 100 with the ?per_page parameter."
   - URL: https://docs.github.com/rest/using-the-rest-api/using-pagination-in-the-rest-api
   - 置信度：上限 **单源（官方）**；默认 30 **多源（次级，含官方历史文档转载）**

2. **Stripe 官方 API 文档**（检索摘要，docs.stripe.com）
   - 摘录："A limit on the number of objects to be returned. Limit can range between 1 and 100, and the default is 10."
   - URL: https://docs.stripe.com/api/pagination
   - 置信度：**单源（官方）**

### 对本项目的适用性

- 内网 + 小数据量 ⇒ **默认 20，前端提供 20/50/100 切换，后端硬上限 100**。
- 真正的风险不在"每页多少"，而在"导出/全量"。分页上限之外的需求（如配置批量对比）应走**游标（cursor/keyset）或异步导出**，不要用 `pageSize=10000` 绕过——这是 GitHub/Stripe 都把上限锁在 100 的隐含理由（深分页性能）。本项目可用 id/时间游标替代 offset。

---

## 9. 防抖 / 节流主流取值

### 主流结论

- **搜索输入防抖 300ms** 是最广为引用的默认值（区间 150–500ms，多数文章给 300ms 或 300–500ms）。但**没有权威规范/原始论文给出"300ms"** —— 它是 UX 感知阈值（NN/g：0.1s 瞬时 / 1s 打断思路流）与工程经验收敛出的惯例。
- 节流常见 200–300ms（滚动/拖拽/resize），低于 100ms 无明显收益。

### 来源与原文摘录

1. **Nielsen Norman Group《Response Time Limits》**（权威 UX 依据，实读摘要）
   - 摘录："0.1 second is about the limit for having the user feel that the system is reacting instantaneously, meaning that no special feedback is necessary" / "1 second 是保持思路流不断的上限"
   - URL: https://www.nngroup.com/articles/response-times-3-important-limits/
   - 置信度：**单源（权威）** —— 300ms 落在"可接受但不瞬时"区间，是其工程折中的依据

2. **300ms 的多方实践表述**（均为次级，彼此一致）
   - "Search bars usually have a debounce time of 300–400ms." URL: https://www.wix.com/seo/learn/resource/how-to-optimize-inp
   - "Keep debounce delay between 300–500ms for best UX." URL: https://www.c-sharpcorner.com/article/how-to-handle-debounced-search-input-properly-in-react/
   - "Debounce your search queries (300-500ms is typical)." URL: https://www.sanity.io/answers/nextjs-sanity-how-to-implement-search-and-press-cmd-k-to-open-a-module
   - "Add a 300ms debounce to search input before firing the query." URL: https://learn.social.plus/use-cases/social/search-and-discovery
   - "A good starting delay is 150-300ms." URL: https://koder.ai/blog/instant-in-app-search-ux
   - 置信度：**多源一致（次级）**

3. **未检到**：lodash / underscore `debounce` 的官方默认值原文未直读（不在本次实读范围内），故不引用其默认值。

### 对本项目的适用性

- 内网 + 本地后端 ⇒ 300ms 完全够用且体验好；对纯前端过滤（已在内存中的配置列表）建议 **150ms**，对走后端的搜索用 **300ms**。
- 必须配 **AbortController 取消在途请求**（或请求序号丢弃过期响应），否则防抖了仍可能乱序覆盖结果——这条比选 200 还是 300 更重要。
- 节流用于表格滚动/拖拽排序时取 **100–200ms** 即可，内网无带宽压力。

---

## 10. EventSource 浏览器同域并发上限

### 主流结论

- **HTTP/1.1 下浏览器每源（per-origin）约 6 个并发连接**，每个 `EventSource` 长期占用 1 个 —— 这是 WHATWG 规范承认的**"per-server connection limitation"**，具体数字（6）来自 Chromium 实现（`g_max_sockets_per_group = 6`），规范本身不给数字。
- **HTTP/2 下多路复用**，不再受 6 限制（RFC 9113 建议服务端至少允许 100 并发流）。
- 规范给出的规避方式：**用 shared worker 共享单个 EventSource**，或每连接独立域名，或让用户按页开关。

### 来源与原文摘录

1. **WHATWG HTML Living Standard §9.2.6 作者注意事项**（实读）
   - 摘录："Clients that support HTTP's per-server connection limitation might run into trouble when opening multiple pages from a site if each page has an EventSource to the same domain. Authors can avoid this using the relatively complex mechanism of using unique domain names per connection, or by allowing the user to enable or disable the EventSource functionality on a per-page basis, or by sharing a single EventSource object using a shared worker."
   - URL: https://html.spec.whatwg.org/multipage/server-sent-events.html
   - 置信度：**单源（权威规范，但只有"存在限制"没有数字）**

2. **Chromium 源码常量 + 官方 issue（"6"的出处）**
   - 源码注释（多个来源一致引用 `net/socket/client_socket_pool_manager.cc`）：`// Default to allow up to 6 connections per host. Experiment and tuning may try other values, but the comment is "may cause many problems, such as home routers blocking the connections"`
     URL（导读）: https://allahabadi.dev/frontend-system-design/questions/design-cloud-file-storage/
   - Chromium issue 40180988："Chrome limits the number of connections per 'group' to 6. This is particularly bad when using a proxy server, since there can be at most 6 open connections" URL: https://issues.chromium.org/40180988
   - 置信度：**多源一致（引用 Chromium 源码同一行）**；本次未直读 source.chromium.org 原始文件（登记为未直读项）

3. **HTTP/2 建议值**（次级）："on HTTP/2 the streams multiplex on one connection and RFC 9113 recommends servers allow at least 100" URL: 同上 allahabadi.dev
   - 置信度：**单源（次级）**

### 对本项目的适用性

- 内网 + 单实例 + 小用户量 ⇒ **6 连接上限几乎是理论问题**，但要避开典型踩法：
  - **一个页面只开 1 条 SSE 连接**（全局单例 store/Composable 持有），不要每个组件各开一条；
  - 若用户习惯同时开多个标签页（比如 4 个页面 × 每条 1 个 = 4），仍 < 6，安全；但应用 **HTTP/2** 后彻底无虞；
  - 服务端按"在线连接数 = 用户数 × 标签页数"估算容量，Spring 用异步（SseEmitter/WebFlux）而非阻塞线程池；内网几十~几百连接对 Tomcat NIO 无压力。
- 公网大流量场景还需考虑负载均衡广播（Redis pub/sub），**本项目单实例不需要**。

---

## 11. 文件上传 / 下载大小与超时（含大 xlsx 导出）

### 主流结论

- **默认限制都很小，必须显式改**：nginx `client_max_body_size` 默认 **1m**；Spring Boot `max-file-size` 默认 **1MB**、`max-request-size` 默认 **10MB**。三层里"最小的那个生效"，且往往返回 HTML 错误页而非 JSON。
- **xlsx 硬上限**：Excel 2007+ `.xlsx` 单表 **1,048,576 行 × 16,384 列**；`.xls` 仅 65,536 行。
- **大导出必须流式**：Apache POI `SXSSFWorkbook` 默认只保留 **100 行**在内存（`DEFAULT_WINDOW_SIZE = 100`），其余刷盘。

### 来源与原文摘录

1. **nginx 官方文档 `client_max_body_size`**（实读原文）
   - 摘录：`Default: client_max_body_size 1m;`
   - URL: https://nginx.org/en/docs/http/ngx_http_core_module.html#client_max_body_size
   - 置信度：**单源（官方）**

2. **Spring Boot 官方 javadoc `MultipartProperties`**（检索摘要，spring.io 官方 API 文档）
   - 摘录："max-file-size specifies the maximum size permitted for uploaded files. The default is 1MB" / "max-request-size specifies the maximum size allowed for multipart/form-data requests. The default is 10MB."
   - URL: https://docs.spring.io/spring-boot/api/java/org/springframework/boot/autoconfigure/web/servlet/MultipartProperties.html
   - 置信度：**单源（官方）**

3. **Microsoft Support《Excel specifications and limits》**（检索摘要，官方）
   - 摘录："In Excel 2010, the maximum worksheet size is 1048576 rows by 16384 columns."；另一篇官方页："The .xls file format has a limit of 65,536 rows in each sheet, while the .xlsx file format has a limit of 1,048,576 rows per sheet."
   - URL: https://support.microsoft.com/en-us/excel/excel-specifications-and-limits
   - 置信度：**多源一致（官方 + POI/SheetJS 等实现方一致）**

4. **Apache POI 官方 How-To（Streaming APIs / SXSSF）**（检索摘要，官方）
   - 摘录："The default window size is 100 and defined by SXSSFWorkbook.DEFAULT_WINDOW_SIZE."
   - URL: https://poi.apache.org/components/spreadsheet/how-to.html
   - 置信度：**单源（官方）**

### 对本项目的适用性

- **推荐值**：
  - 上传（配置导入）：单文件 **10–50MB**，`max-request-size = 2× max-file-size`；同步把 nginx `client_max_body_size` 设 ≥ 该值（否则 413）。导入还要加"行数上限"校验（如 10 万行）与"异步解析 + 进度"。
  - 导出 xlsx：**流式 SXSSF（window 100–1000）**、**异步任务 + 下载令牌/一次性链接**、单文件行数**建议 ≤ 20 万行**（`类推`：由 Excel 104,8576 行硬上限与浏览器/内网传输体验推导，非官方推荐值）；HTTP 响应超时按"异步导出"处理，不占用同步请求超时。
  - 下载超时：内网直连可不设或设 5–10 min；走代理时要把 `proxy_read_timeout`（默认 60s）同步放大到 ≥ 导出耗时。
- 公网大流量做法（分片上传/断点续传/OSS 直传）在本项目属于过度设计；内网 10–50MB 直接 multipart 即可。

---

## 12. 任务看门狗 / 僵尸任务判定超时

### 主流结论

没有统一标准值，但**各主流调度/队列框架的默认值高度聚集在 30s ~ 1h**，且模式一致：**"心跳间隔 × 3"或"N 分钟无心跳即判死并重新入队"**。

| 框架 | 参数 | 默认/推荐 | 备注 |
|---|---|---|---|
| Quartz | `misfireThreshold` | **60000ms (60s)** | 触发时间点过后多久算 misfire |
| Hangfire | `InvisibilityTimeout` | **30 分钟** | 超过则视为 worker 死亡并重新入队 |
| Celery (Redis broker) | `visibility_timeout` | 官方文档示例 **18000s (5h)**；社区一致称默认 **3600s (1h)** | 超过则消息被重新投递给另一个 worker |
| Kubernetes | liveness probe | `periodSeconds` 默认 **10s**、`failureThreshold` 默认 **3**、`timeoutSeconds` 默认 **1** | 约 30s 连续失败才重启 |
| Temporal | Heartbeat Timeout | 官方："a relatively short Heartbeat Timeout and a frequent Heartbeat"；社区常用 **30s** | 长任务必须定期 heartbeat |
| AWS Step Functions | `TimeoutSeconds` | **无默认**（不设则永久卡住） | 官方最佳实践要求显式设置 |

### 来源与原文摘录

1. **Quartz 官方文档**（检索摘要，quartz-scheduler.org / Quartz.NET 官方）
   - 摘录："jobStore.misfireThreshold: Milliseconds a trigger may pass its next-fire-time before it counts as misfired. Default 60000 (60 seconds)."
   - URL: https://www.quartz-scheduler.net/documentation/quartz-3.x/configuration/reference.html （Quartz Java：https://github.com/quartz-scheduler/quartz/blob/main/docs/configuration.adoc 中同样为 60000）
   - 置信度：**多源一致（官方）**

2. **Hangfire 存储选项**（检索摘要，官方 NuGet/文档一致）
   - 摘录：`InvisibilityTimeout` | `30 minutes` | "Time after which an abandoned job can be considered invisible"（多来源一致）；"To solve this issue, set the value to 30 minutes like in the SqlServerStorageOptions."
   - URL: https://www.nuget.org/packages/Hangfire.Mongo/1.12.0
   - 置信度：**多源一致（官方包文档 + 长期社区共识）**

3. **Celery 官方配置文档**（实读官方页面）
   - 摘录（原文示例）：`broker_transport_options = {'visibility_timeout': 18000}  # 5 hours`
   - URL: https://docs.celeryq.dev/en/stable/userguide/configuration.html
   - 官方页面只给示例值；默认 3600s 来自多个独立来源（"The default is 3600 seconds (one hour) as of recent Celery versions"）URL: https://faaaizan.space/blogs/celery-redis-gotchas/ 、https://thedeadcoder.com/celery
   - 置信度：示例值 **单源（官方）**；默认 3600s **多源（次级）**

4. **Kubernetes 官方文档 Probes**（检索摘要，kubernetes.io）
   - 摘录："The default for periodSeconds is 10s."
   - `failureThreshold` 默认 3、`timeoutSeconds` 默认 1（官方文档同一页）
   - URL: https://kubernetes.io/docs/concepts/workloads/pods/probes/
   - 置信度：**单源（官方）**

5. **Temporal 官方 Detecting Activity failures**（检索摘要，docs.temporal.io）
   - 摘录："For long-running Activities, we recommend using a relatively short Heartbeat Timeout and a frequent Heartbeat."
   - URL: https://docs.temporal.io/encyclopedia/detecting-activity-failures
   - 置信度：**单源（官方）**；（"约 30 秒"来自社区博客 https://raphaelbeamonte.com/posts/good-practices-for-writing-temporal-workflows-and-activities/ ，次级）

6. **AWS Step Functions 官方最佳实践**：见第 5 类第 2 条（不设超时→永久卡住）
   - 置信度：**单源（官方）**

### 对本项目的适用性

- 本项目是"任务/作业编排"（内部批处理、导入导出、批量下发），**单实例**——不需要分布式抢锁，僵尸判定只需处理"进程崩溃 / 线程卡死 / 忘记回调"。
- **推荐**：
  - 任务统一维护 `heartbeatAt`（执行中每 **30–60s** 续期）；
  - 看门狗扫表周期 1 分钟，判定"**超过 3×心跳间隔无续期 = 僵尸**"（即 90–180s；对齐 K8s 的 period×failureThreshold 模式）；
  - 长任务（大导出）另行设 `maxExecutionTime`（如 30–60 分钟）作为绝对上限，超时标 FAILED 并释放资源；
  - 启动时做一次"孤儿任务回收"（服务重启后把 RUNNING 且无心跳的任务重置为 PENDING/FAILED）——这是单实例场景最常见的僵尸来源；
  - 重启恢复用"检查点 + 幂等重跑"，不要依赖消息队列的 visibility timeout（本项目若无 MQ 可完全跳过第 12 类中 Celery/Hangfire 那套）。

---

## 可直接套用的推荐值速查表

| # | 类别 | 推荐值（内网企业应用） | 前提 / 备注 |
|---|---|---|---|
| 1 | HTTP 客户端超时 | connect **3–5s**、read/write **30–60s**、整体 **60–120s**；AI 通道 read **120–300s**；**axios 必须显式设 timeout** | 内网少重试长超时；长响应分通道配置 |
| 1 | 云/SDK 默认参考 | OkHttp 10/10/10s、callTimeout 0；axios 0（无限）；AWS SDK **默认不设**；OpenAI/Anthropic 600s | 库默认普遍"要么 10s 太短、要么 0 无限"，都不能照抄 |
| 2 | SSE 心跳 | **15s**（规范建议值）；`heartbeat < min(代理超时)/2` | nginx/ALB 默认 60s；Spring 默认 30s（Tomcat）必须显式改 |
| 2 | SSE 写超时 | Spring `spring.mvc.async.request-timeout` 显式设 **≥30min 或 0（不超时）** | 不设 = Tomcat 30s 切断 |
| 3 | 轮询间隔 | 任务状态 **2s**；列表自动刷新 **30s 且默认关闭** | 条件轮询：无 running 任务即停；页面隐藏即停 |
| 3 | 轮询退避 | 失败后 2→4→8→16→**30s 封顶**，最多 5–10 次 | 对齐 SWR 的指数退避实现 |
| 4 | 重试 | maxAttempts **3**，Full Jitter，base **200ms**，cap **5s**；写副作用操作**不自动重试** | AWS=3/Azure=3/大模型 SDK=2；只在一层重试 |
| 5 | HITL 确认门超时 | 交互确认 **10 分钟**（超时自动拒绝）；工单式 **24 小时**（兜底拒绝）；**必须设超时** | 业界无统一值；官方唯一硬结论："不设超时=永久卡死" |
| 6 | 前端工具调用超时 | 单工具 **30s**（本地）/ **60s**（跨系统）；单步 **60s**；整体 **5–10 min**；超时按"工具错误结果"回给模型 | MCP 只规定 SHOULD 设；实现默认常 60s；长任务改走 taskId+轮询 |
| 7 | HikariCP | `maximumPoolSize **10**`（小规模 5–8）、`connectionTimeout **3000–5000**`、`maxLifetime **1800000**`（小于 DB 上限）、`keepaliveTime **120000**`、`validationTimeout **5000**`、`leakDetectionThreshold **60000**`、`minimumIdle` 不设（固定池） | 官方默认即推荐；不要按并发数放大 |
| 8 | 分页 | 默认 **20**，可选 20/50/100，**硬上限 100**；深度翻页用**游标**；全量走异步导出 | GitHub 30/100、Stripe 10/100 |
| 9 | 防抖 / 节流 | 后端搜索 **300ms**；纯前端过滤 **150ms**；滚动/拖拽节流 **100–200ms**；必配 `AbortController` | 300ms 是多源惯例（非规范）；NN/g 感知阈值 0.1s/1s/10s |
| 10 | EventSource 并发 | 每页**只开 1 条** SSE；总连接数 = 标签页数；走 **HTTP/2** 解除 6 连接限制 | 规范承认 per-server 限制，数字 6 来自 Chromium |
| 11 | 上传 | 单文件 **10–50MB**；`max-request-size = 2×`；nginx `client_max_body_size` 同步放大；导入限行数 | nginx 默认 1m、Spring 默认 1MB/10MB 都会直接失败 |
| 11 | 导出 xlsx | SXSSF **window 100–1000**；异步任务 + 下载令牌；单文件建议 **≤20 万行**（`类推`） | Excel 硬上限 1,048,576 行 |
| 12 | 看门狗 | 心跳 **30–60s**；僵尸判定 **3×心跳（90–180s）**；绝对上限 **30–60min**；启动时回收孤儿任务 | Quartz 60s / Hangfire 30min / Celery 1h / K8s 10s×3 |

---

## 未检到可靠来源 / 证据不足清单（如实登记）

1. **【无统一标准】HITL 人工确认门超时**：业界不存在主流共识值，也没有标准组织规定。本次只能给出"按审批形态分档的惯例区间"，唯一有硬依据的是"必须设超时，否则永久卡死"。LangChain/LangGraph 官方文档无超时默认值（因为不存在），AWS 官方只说"设一个合理值"。
2. **【未检到权威数值】前端轮询间隔**：没有任何规范/官方文档规定"列表页刷新间隔"应取多少秒。本文数字来自 Grafana 默认档位、Temporal 示例与实践文章，属机制有官方依据、数值仅为惯例。
3. **【未检到规范来源】"防抖 300ms"**：没有权威规范或原始研究直接给出 300ms；NN/g 只给出 0.1s/1s/10s 感知阈值。300ms 为多源次级实践收敛值。lodash/underscore 的 `debounce` 官方默认值本次未直读，未引用。
4. **【未检到官方原文】Java `HttpClient.Builder#connectTimeout` 的默认值**：Oracle javadoc 仅描述语义未写默认；"未设置即无限"为类推。仅 `HttpRequest.timeout` 有官方"不设置=block forever"原文。
5. **【未直读官方页】AWS ALB idle timeout 默认 60s**：AWS 官方文档页未直读，依据多个独立来源一致引用（含引用 AWS Documentation 的页面）。
6. **【未直读源码】Chromium "每源 6 连接"的原始常量**：`g_max_sockets_per_group = 6` 的 source.chromium.org 原始文件未直读，依据 Chromium issue 40180988 与多个一致引用该源码行的来源。WHATWG 规范本身不含数字。
7. **【无权威推荐值】大 xlsx 导出的行数/体积上限**：只有 Excel 硬上限（1,048,576 行）与 POI 流式窗口默认（100 行）可查；"建议 ≤20 万行"是本文类推，非官方推荐。
8. **【未直读】MCP 各 SDK 的 60s 默认常量**：GitHub API 代码检索需鉴权、SDK 源文件路径未命中，60s 依据 LM Studio 官方 issue 与社区多处一致表述（次级）。
9. **【未直读】HikariCP 池大小公式原文**：`About-Pool-Sizing` wiki 正文未直读，仅 README 摘要"连接数过多反而损害性能"为实读原文。
10. **【未检到】"SSE 心跳"的统一权威数值**：规范给的是"every 15 seconds or so"（非强制），各代理 60s；不存在更强的一致性来源。
11. **【未检到】"任务看门狗判定阈值"的统一权威数值**：只能给出各框架默认值的横向对比，不存在跨框架标准。

---

## 附：本文实读来源总览（按类别）

| 类别 | 主要来源（实读/官方） |
|---|---|
| 1 | OkHttp 源码、axios 源码、Oracle javadoc、AWS SDK Java 2.x 官方、OpenAI/Anthropic SDK 源码 |
| 2 | WHATWG HTML 规范、nginx 官方、Spring Framework 官方、Spring Boot issue、AWS ALB（次级） |
| 3 | TanStack Query 官方、SWR 官方、Temporal 官方、Grafana 配置（次级） |
| 4 | AWS Architecture Blog、AWS SDK 官方、Azure MS Learn 官方、OpenAI/Anthropic SDK 源码 |
| 5 | LangGraph（次级）、AWS Step Functions 官方、SailPoint 官方公告、PIM/企业 SLA 实践（次级） |
| 6 | MCP 规范官方、Vercel AI SDK 官方、OpenAI Agents SDK 官方、OpenAI/Anthropic SDK 源码 |
| 7 | HikariCP 官方 README（全文实读） |
| 8 | GitHub 官方文档、Stripe 官方文档 |
| 9 | NN/g 官方、多家工程博客（次级） |
| 10 | WHATWG 规范、Chromium issue + 源码引用（次级） |
| 11 | nginx 官方、Spring Boot 官方 javadoc、Microsoft Support 官方、Apache POI 官方 |
| 12 | Quartz 官方、Hangfire 官方包文档、Celery 官方配置页、Kubernetes 官方、Temporal 官方、Step Functions 官方 |
