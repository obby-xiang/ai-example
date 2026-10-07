# V7 核验：kimi-k3「deepseek-flash 思考模式回填 assistant 必须携带 reasoning_content，缺失则 400；已打框架补丁 ReasoningAwareOpenAiChatModel」

- **被核验分支**：`ai-example-kimi-k3`（`git rev-parse --abbrev-ref HEAD` = `kimi-k3`，HEAD = `cf5b8a53275f6045c2285611149c4201622b5e53`）
- **核验日期**：2026-10-06（本地时间 19:00 前后）
- **核验方式**：**绕过项目后端**，用 `curl` / Node `fetch` 直连 DeepSeek OpenAI 兼容端点 `https://api.deepseek.com/chat/completions` 构造真实请求；全部请求/响应原文留档（附件 A/B/C）
- **结论**：**【实测-不符】**（对 `deepseek-flash` 的归因不成立；但报错原文与机制本身真实存在，实测复现于 `deepseek-v4-pro`，详见 §5）

---

## 1. 核验目标（自述原文与出处）

### 1.1 本核验项的定义来源（任务书原话）

> kimi-k3 分支自述"deepseek-flash 思考模式（thinking）下，多轮对话回填 assistant 消息时必须携带 reasoning_content，缺失则 API 返回 400——与旧模型规则相反；分支留有实测报错原文存档，并实现了框架补丁 ReasoningAwareOpenAiChatModel"

### 1.2 被核验分支自身的声明原文

| 出处 | 原文（截取） |
|---|---|
| `ai-example-kimi-k3/docs/02-architecture.md:32` | 「**唯一的框架补丁**：`OpenAiChatModel.createRequest` 把历史 assistant 消息的 `reasoningContent` 硬编码为 null，而 deepseek-flash 思考模式必须回放（**缺失 400，实测**）。在同名包下放 `ReasoningAwareOpenAiChatModel extends OpenAiChatModel`，@Override 该方法仅改动这一处传播」 |
| `ai-example-kimi-k3/docs/02-architecture.md:34` | 「**`deepseek-flash` 是思考模式模型：回填 assistant 消息必须携带本轮 `reasoning_content`，缺失会 400**（报错原文：*The \`reasoning_content\` in the thinking mode must be passed back to the API*）。⚠ 这与 deepseek-chat/deepseek-reasoner 的旧规则（不可回传 reasoning_content）**相反**，是实测踩坑后修正的结论」 |
| `ai-example-kimi-k3/docs/02-architecture.md:144` | 「deepseek-flash 思考模式要求回填 reasoning_content（缺失 400，与旧模型规则相反） \| 同名包补丁子类 `ReasoningAwareOpenAiChatModel` 在 createRequest 中传播（框架缺口，升级后评审移除）；reasoning 存 AssistantMessage.metadata 闭环（实测验证）」 |
| `ai-example-kimi-k3/docs/05-verification.md:37` | 「**deepseek-flash 思考模式 400**：多轮工具调用后报 400，**直连 API 复现**，报错 *"The \`reasoning_content\` in the thinking mode must be passed back to the API"*。结论：deepseek-flash 与旧模型规则**相反**，回填 assistant 消息**必须携带**本轮 reasoning_content。修复后 7 轮连续工具调用无错误」 |
| `ai-example-kimi-k3/docs/05-verification.md:118` | 「补丁类 `ReasoningAwareOpenAiChatModel` 在 Spring AI 升级后需评审是否可移除」 |
| `ai-example-kimi-k3/backend/src/main/java/com/example/quickstart/ai/AiChatService.java:51` | 「DeepSeek 兼容性：deepseek-flash 为思考模式，回填 assistant 消息必须携带本轮 reasoning_content……由 ReasoningAwareOpenAiChatModel 补丁负责回放，**缺失会 400**」 |
| `ai-example-kimi-k3/backend/src/main/java/com/example/quickstart/ai/AiChatService.java:393` | 「// deepseek-flash 为思考模式：回填时必须携带本轮 reasoning_content，**否则下一轮 400**」 |
| `ai-example-kimi-k3/backend/src/main/java/org/springframework/ai/openai/ReasoningAwareOpenAiChatModel.java:26-34` | 类 Javadoc：「……而 deepseek-flash（思考模式，OpenAI 兼容协议）要求多轮请求回填 assistant 消息时携带本轮 reasoning_content，**缺失会 400**」 |

**拆解为可核验断言**：

- **C1**：`deepseek-flash` 是思考模式模型，响应中含 `reasoning_content` 字段。
- **C2**：带 `tools` 的多轮请求中，回填 assistant 消息**必须携带** `reasoning_content`，**缺失则 API 返回 400**。
- **C3**：400 报错原文为 `The reasoning_content in the thinking mode must be passed back to the API`（原文含反引号，见 §1.2 表格中的分支引用）。
- **C4**：该结论与旧模型（deepseek-chat / deepseek-reasoner）规则**相反**。
- **C5**：分支**留有实测报错原文存档**。
- **C6**：分支实现了框架补丁 `ReasoningAwareOpenAiChatModel`，在 `createRequest` 的 ASSISTANT 分支传播 `reasoningContent`。

### 1.3 关于 C5「实测报错原文存档」的实际检索结果

**检索动作与结果（可复现）**：

```bash
cd <REPO_ROOT>/ai-example-kimi-k3
grep -rn "reasoning" . | grep -v node_modules | grep -v /target/      # 命中：docs 2 处 + 4 个 Java 源文件（见上表）
find . -iname "*reasoning*" -o -iname "*thinking*" -o -iname "*400*" # 唯一命中：ReasoningAwareOpenAiChatModel.java（源码，非存档）
git log --all --diff-filter=A --name-only                           # 全历史新增文件清单中无任何报错存档文件
git log --all --format="%B" | grep -i "reasoning"                   # 仅 3 条 commit 标题，无报错正文
```

**实况**：分支内**不存在独立的"实测报错原文存档"文件**（无 `*.json` / `*.txt` / `*.log` / `*.md` 存档）。该报错文本仅以**内联引用**形式出现在 `docs/02-architecture.md:34` 与 `docs/05-verification.md:37` 两处，且只有一行（不含状态码、不含响应 JSON、不含 request_id）。因此"预留存档可供逐字比对"这一前提**不成立**：本次比对以 docs 中内联引用的那句文本作为分支侧基准。

（附注：`grep -rl "thinking mode must be passed back" <REPO_ROOT>` 另命中 `ai-example-main/ai-service/.../AiService.java`、`ai-example-trae/ai-service/.../AiService.java`、`<REPO_ROOT>/docs/merge/03-技术方案文档.md`（本仓库参考件），均非本分支自产内容，不属于本核验项。）

---

## 2. 环境与核验方法

| 项 | 实际值 |
|---|---|
| OS / Shell | Windows，Git Bash（`<GIT_HOME>`） |
| 运行时 | Node.js `v22.23.2`（脚本内用内置 `fetch` 发请求）、`curl`（`/mingw64/bin/curl`） |
| 端点 | `https://api.deepseek.com/chat/completions`（另有 `/v1/chat/completions`、`/beta/chat/completions` 同端点复测） |
| 鉴权 | 请求头 `Authorization: Bearer ***`（真实 key 仅存在于进程环境变量 `DEEPSEEK_API_KEY`，未写入任何文件、未出现在请求 body 中） |
| 主模型 | `deepseek-flash`（分支 `application.yml` 所配模型） |
| 对照模型 | `deepseek-v4-pro`、以及别名 `deepseek-v4-flash` / `deepseek-reasoner` / `deepseek-chat` |
| 请求开关 | 思考模式**默认开启**（DeepSeek 官方文档：思考模式默认打开，effort 默认 `high`）；显式开关为 OpenAI 格式 body 顶层 `"thinking": {"type":"enabled"}` + `"reasoning_effort": "low/high/max"` |
| 请求总数 | 约 57 次真实 HTTP 请求（附件 A/B/C 中留有原文的约 50 次；均为一次性、无重试） |

**分支侧"如何开启思考模式"的实际实现**（读源码得到）：`backend/src/main/resources/application.yml` 仅设置 `base-url: https://api.deepseek.com`、`completions-path: /chat/completions`、`model: deepseek-flash`、`parallel-tool-calls: false`，**没有任何 `thinking` / `reasoning_effort` 参数**（`OpenAiConfig.java` 也未设置 extraBody）。即分支本身依赖"思考模式默认开启"。

**核心三组实验构造**：

1. 第 1 轮：`POST /chat/completions`，body = `{model: deepseek-flash, messages:[user], tools:[get_city_population], tool_choice:"auto", stream:false}`，用户话术为「上海的人口是多少？请调用工具查询。」→ 取回 assistant 消息（应含 `reasoning_content`）。
2. 第 2 轮（实验组）：把第 1 轮 assistant 消息 **原样**（含 `reasoning_content`）追加进 `messages`，再追加对应的 `role:"tool"` 结果消息 → 观察状态码。
3. 第 2 轮（对照组）：**完全相同的第 2 轮请求**，唯一差异是 `delete assistantMessage.reasoning_content` → 观察状态码，预期按分支自述为 400。

---

## 3. 实际观察（请求/响应原文，完整版见附件 A、B、C）

### 3.1 第 1 轮请求（实测原文）

```json
{
  "model": "deepseek-flash",
  "messages": [{ "role": "user", "content": "上海的人口是多少？请调用工具查询。" }],
  "tools": [{
    "type": "function",
    "function": {
      "name": "get_city_population",
      "description": "查询指定城市的人口数量",
      "parameters": { "type": "object", "properties": { "city": { "type": "string", "description": "城市名称" } }, "required": ["city"] }
    }
  }],
  "tool_choice": "auto",
  "stream": false
}
```

### 3.2 第 1 轮响应 —— **HTTP 200**，含 `reasoning_content`（C1 成立）

```json
{"id":"70411db8-f224-41b2-930f-31e9d86e7745","object":"chat.completion","created":1791284171,"model":"deepseek-flash","choices":[{"index":0,"message":{"role":"assistant","content":"I'll look that up for you.","reasoning_content":"The user asks about Shanghai's population, and asks me to call the tool.","tool_calls":[{"index":0,"id":"call_00_88FTMwhoOrXt18XRE3kt5141","type":"function","function":{"name":"get_city_population","arguments":"{\"city\": \"上海\"}"}}]},"logprobs":null,"finish_reason":"tool_calls"}],"usage":{"prompt_tokens":306,"completion_tokens":64,"total_tokens":370,"prompt_tokens_details":{"cached_tokens":0},"completion_tokens_details":{"reasoning_tokens":16},"prompt_cache_hit_tokens":0,"prompt_cache_miss_tokens":306},"system_fingerprint":"aeb56401ca74e127821c4f9126dcb669"}
```

### 3.3 第 2 轮（实验组：assistant 原样回填，含 `reasoning_content`）—— **HTTP 200**

请求体（`messages` 部分原文，`tools`/`tool_choice`/`stream` 同第 1 轮）：

```json
{
  "model": "deepseek-flash",
  "messages": [
    { "role": "user", "content": "上海的人口是多少？请调用工具查询。" },
    {
      "role": "assistant",
      "content": "I'll look that up for you.",
      "reasoning_content": "The user asks about Shanghai's population, and asks me to call the tool.",
      "tool_calls": [{ "index": 0, "id": "call_00_88FTMwhoOrXt18XRE3kt5141", "type": "function",
        "function": { "name": "get_city_population", "arguments": "{\"city\": \"上海\"}" } }]
    },
    { "role": "tool", "tool_call_id": "call_00_88FTMwhoOrXt18XRE3kt5141", "content": "{\"city\":\"上海\",\"population\":24874500}" }
  ],
  "tools": [ ... 同第 1 轮 ... ],
  "tool_choice": "auto",
  "stream": false
}
```

响应原文：

```json
{"id":"a59003b7-1dda-4d0c-99fd-9a7818f6928e","object":"chat.completion","created":1791284177,"model":"deepseek-flash","choices":[{"index":0,"message":{"role":"assistant","content":"根据查询结果，上海的人口为 **24,874,500** 人（约 2487 万）。","reasoning_content":""},"logprobs":null,"finish_reason":"stop"}],"usage":{"prompt_tokens":392,"completion_tokens":25,"total_tokens":417,"prompt_tokens_details":{"cached_tokens":256},"completion_tokens_details":{"reasoning_tokens":0},"prompt_cache_hit_tokens":256,"prompt_cache_miss_tokens":136},"system_fingerprint":"aeb56401ca74e127821c4f9126dcb669"}
```

→ 与预期一致（200），**这一组不构成结论差异**。

### 3.4 第 2 轮（对照组：仅去掉 `reasoning_content`，其余逐字节相同）—— **HTTP 200，未出现 400**（C2 未复现）

请求 `messages` 部分原文：

```json
{
  "model": "deepseek-flash",
  "messages": [
    { "role": "user", "content": "上海的人口是多少？请调用工具查询。" },
    {
      "role": "assistant",
      "content": "I'll look that up for you.",
      "tool_calls": [{ "index": 0, "id": "call_00_88FTMwhoOrXt18XRE3kt5141", "type": "function",
        "function": { "name": "get_city_population", "arguments": "{\"city\": \"上海\"}" } }]
    },
    { "role": "tool", "tool_call_id": "call_00_88FTMwhoOrXt18XRE3kt5141", "content": "{\"city\":\"上海\",\"population\":24874500}" }
  ],
  "tools": [ ... 同第 1 轮 ... ],
  "tool_choice": "auto",
  "stream": false
}
```

响应原文（状态码 200，非 400）：

```json
{"id":"823d88a0-9912-4070-ad9f-e7e6f7232e61","object":"chat.completion","created":1791284180,"model":"deepseek-flash","choices":[{"index":0,"message":{"role":"assistant","content":"根据查询结果，**上海的人口为 24,874,500 人**（约 2487 万）。\n\n需要我查询其他城市的人口数据吗？","reasoning_content":"The tool returned population 24,874,500. Let me report."},"logprobs":null,"finish_reason":"stop"}],"usage":{"prompt_tokens":392,"completion_tokens":50,"total_tokens":442,"prompt_tokens_details":{"cached_tokens":256},"completion_tokens_details":{"reasoning_tokens":15},"prompt_cache_hit_tokens":256,"prompt_cache_miss_tokens":136},"system_fingerprint":"aeb56401ca74e127821c4f9126dcb669"}
```

### 3.5 按 DeepSeek 官方文档样例参数复测（`thinking={"type":"enabled"}` + `reasoning_effort:"high"` + `tools`）

| 请求 | 状态码 | 备注 |
|---|---|---|
| F1 第 1 轮（thinking + effort=high + tools） | **200** | 返回 `tool_calls` |
| F2 第 2 轮回填**含** `reasoning_content` | **200** | — |
| F3 第 2 轮回填**去掉** `reasoning_content` | **200** | **仍非 400** |

### 3.6 扩展变体矩阵（全部为真实请求，完整原文见附件 B）

| # | 变体（模型 = deepseek-flash，均带 `tools`） | 状态码 | 是否 400 |
|---|---|---|---|
| V1 | 回填 assistant 无 `reasoning_content`，工具结果在末尾 | 200 | 否 |
| V2 | 回填 assistant 无 `reasoning_content`，assistant 为最后一条（**故意违反 tool_calls 协议**） | **400** | 是，但报错为 `insufficient tool messages following tool_calls message`（与本项无关的另一种校验） |
| V3 | 回填 assistant `content:""` 且无 `reasoning_content` | 200 | 否 |
| V4 | 回填 `reasoning_content: null` | 200 | 否 |
| V5 | 回填 `reasoning_content: ""` | 200 | 否 |
| V6 | 回填**含** `reasoning_content` + `stream:true` | 200 | 否 |
| V7 | 回填无 `reasoning_content` + `stream:true` | 200 | 否 |
| V8 | tool 消息不带 `name` 字段、无 `reasoning_content` | 200 | 否 |
| W1 | assistant 完全不带 `content` 键、无 `reasoning_content` | 200 | 否 |
| W2 | 显式 `thinking:{type:enabled}`、无 `reasoning_content` | 200 | 否 |
| W3 | 显式 `thinking:{type:enabled}`、含 `reasoning_content` | 200 | 否 |
| W8 | 回填**伪造**的 `reasoning_content:"!!!bogus!!!"` | 200 | 否 |
| X1 | 「后续用户轮」场景（历史中含过 tool_call + 新一轮 user），无 `reasoning_content` | 200 | 否 |
| X2 | 同 X1 但含 `reasoning_content` | 200 | 否 |
| X3 / X4 | 无 `reasoning_content` + `reasoning_effort=high` / `=max` | 200 / 200 | 否 |
| X5 | 无 `reasoning_content` + `thinking:{type:enabled}` | 200 | 否 |
| X6 | 改用模型名 `deepseek-v4-flash`，无 `reasoning_content` | 200 | 否 |
| X7 | 无 `reasoning_content` + `parallel_tool_calls:false`（**与分支 yml 完全一致**） | 200 | 否 |
| X8 | 无 `reasoning_content` + `stream:true` + `reasoning_effort=high` | 200 | 否 |
| 循环 keep3 | 3 轮工具循环，回填**含** `reasoning_content` | 全 200 | 否 |
| 循环 strip3 | 3 轮工具循环，回填**全程剥离** `reasoning_content` | 全 200 | 否 |
| 循环 strip8 | 最长 8 轮工具循环，回填**全程剥离** `reasoning_content` | 全 200（第 4 轮模型自然停止调用工具） | 否 |
| 最终复测 | 7 轮工具循环，参数**与分支应用等价**（`thinking:{type:enabled}` + `reasoning_effort:high` + `parallel_tool_calls:false`），每轮回填**均剥离** `reasoning_content` | 7 次全 **200** | 否 |

**另注**：`/v1/chat/completions`、`/beta/chat/completions` 两个路径用同一对照组请求体（p2b）复测，同样 **200**。

### 3.7 关键对照实验：同一构造换 `deepseek-v4-pro` → **400 复现，报错原文逐字一致**

| # | 请求（`tools` 存在；唯一变量为 `reasoning_content`） | 状态码 |
|---|---|---|
| P1 | `model: deepseek-v4-pro`，回填 assistant **无** `reasoning_content` | **400** |
| P2 | `model: deepseek-v4-pro`，回填 assistant **含** `reasoning_content` | **200** |
| P3 | `model: deepseek-v4-pro`，无 `reasoning_content` 且显式 `thinking:{type:enabled}` + `reasoning_effort:high` | **400** |

P1 响应原文（HTTP 400）：

```json
{"error":{"message":"The `reasoning_content` in the thinking mode must be passed back to the API. (request_id: 7d6fde7c-b731-4375-a3be-50fb0c5441b6)","type":"invalid_request_error","param":null,"code":"invalid_request_error"}}
```

P3 响应原文（HTTP 400）：

```json
{"error":{"message":"The `reasoning_content` in the thinking mode must be passed back to the API. (request_id: 54dd613d-4a48-4e80-818d-b1f8c3f6f7a9)","type":"invalid_request_error","param":null,"code":"invalid_request_error"}}
```

P2 响应原文（HTTP 200，`model` 字段确为 `deepseek-v4-pro`）：

```json
{"id":"28476f2a-47ac-4df5-b4be-3eaa83a2dd73","object":"chat.completion","created":1791284435,"model":"deepseek-v4-pro","choices":[{"index":0,"message":{"role":"assistant","content":"上海的人口为 **24,874,500 人**（约 2487 万）。","reasoning_content":"Tool result population 24,874,500. Answer in Chinese."},"logprobs":null,"finish_reason":"stop"}], ... ,"system_fingerprint":"a307abda487cd1b463329ccb945ce396"}
```

### 3.8 模型清单（`GET /models`，HTTP 200）

```json
{"object":"list","data":[
  {"id":"deepseek-flash","object":"model","owned_by":"deepseek","name":"DeepSeek-V4.1-Flash","context_window":1048576,
   "effort":{"supported_levels":["low","high","max"],"default_level":"high"}, ...},
  {"id":"deepseek-v4-pro","object":"model","owned_by":"deepseek","name":"DeepSeek-V4-Pro","context_window":1048576,
   "effort":{"supported_levels":["low","high","max"],"default_level":"high"}, ...}]}
```

- 该接口只列出 `deepseek-flash`、`deepseek-v4-pro` 两个模型（**没有** `deepseek-chat` / `deepseek-reasoner`）。
- 用 `deepseek-chat` / `deepseek-reasoner` / `deepseek-v4-flash` 作为 `model` 发请求均返回 200，但响应中的 `"model"` 字段**都回写为 `deepseek-flash`** —— 即这些别名在服务端被路由到了 `deepseek-flash`，**无法用来验证 C4「与旧模型规则相反」**。

### 3.9 官方文档的立场（说明 C2「机制」确有出处）

DeepSeek 官方《思考模式》文档（`https://api-docs.deepseek.com/zh-cn/guides/thinking_mode`）明确写道：

> 请注意，携带了 `tools` 参数的请求，在后续所有请求中，必须完整回传 `reasoning_content` 给 API——即使该轮模型未实际进行工具调用。若您的代码中未正确回传 `reasoning_content`，API 会返回 400 报错。

并且文档把思考模式开关写为 `{"thinking": {"type": "enabled/disabled"}}`，默认打开、effort 默认 `high`——与分支 `application.yml`（不传任何思考参数、依赖默认开启）的行为一致。

因此：**C2 所述的"机制与规则"是 DeepSeek 官方文档的明文规定，但该规定在本次核验时对 `deepseek-flash` 并未被服务端强制执行，而对 `deepseek-v4-pro` 被严格执行。**

---

## 4. 逐项对照表

| 断言 | 分支自述 | 本次实测 | 判定 |
|---|---|---|---|
| **C1** `deepseek-flash` 是思考模式模型，响应含 `reasoning_content` | 是 | 每次纯对话/工具调用响应均含 `reasoning_content`（含 `reasoning_tokens` 计数） | ✅ 符合 |
| **C2** 回填 assistant 缺失 `reasoning_content` → **API 返回 400** | 是 | **对 `deepseek-flash` 未复现**：核心三组 §3.4 为 200；另 20+ 种变体、含 7 轮应用等价参数循环，**全部 200，无一次 400** | ❌ **不符** |
| **C3** 400 报错原文 = `The reasoning_content in the thinking mode must be passed back to the API` | 是 | 该文案**逐字复现**，但出现在 `deepseek-v4-pro` 上；服务端实际返回为 `... to the API. (request_id: <uuid>)`（分支引用未含 request_id 后缀） | ⚠️ 文案真实、但**归因模型错误** |
| **C4** 与旧模型（deepseek-chat / deepseek-reasoner）规则**相反** | 是 | **无法验证**：该两别名在 `/models` 中不存在，服务端实际将其路由到 `deepseek-flash`（响应 `model` 字段为 `deepseek-flash`）；且对这两个别名回传/不回传 `reasoning_content` 均返回 200，观察不到"旧规则（禁止回传）"的差异 | ⚠️ 未能验证 |
| **C5** 分支留有实测报错原文存档 | 是 | 全分支检索（`grep` / `find` / `git log --all --diff-filter=A`）**未找到任何独立存档文件**；只有 2 处 docs 内联引用该一行文本 | ❌ 不符 |
| **C6** 实现框架补丁 `ReasoningAwareOpenAiChatModel` 并在 `createRequest` 的 ASSISTANT 分支传播 `reasoningContent` | 是 | 源码存在（`backend/src/main/java/org/springframework/ai/openai/ReasoningAwareOpenAiChatModel.java`，123 行），第 70-75 行确实把 `assistantMessage.getMetadata().get("reasoningContent")` 传入 `ChatCompletionMessage` 第 9 组件；并在 `OpenAiConfig.java:35` 以 `@Primary` 装配生效 | ✅ 符合（补丁实现本身正确） |
| 附 | 「修复后 7 轮连续工具调用无错误」 | 本次剥离版 7 轮循环亦无错误——**无法区分"补丁修好了"与"根本没这个错"** | ⚠️ 无法证实因果 |

---

## 5. 结论

### 结论标签：**【实测-不符】**

**具体表述**：

1. **对 `deepseek-flash` 而言，本项自述的核心行为断言（回填 assistant 缺失 `reasoning_content` → API 返回 400）在本次核验中不成立。** 该模型确实处于思考模式（响应含 `reasoning_content`），但在带 `tools` 的多轮回填场景下，无论是否回传、是否回传空串/null/伪造值、是否流式、是否显式 `thinking:{type:"enabled"}`、是否 `reasoning_effort=high/max`、是否 `parallel_tool_calls:false`（与分支 yml 一致），服务端**一律返回 200**；7 轮应用等价参数的工具循环、每轮回填均剥离 `reasoning_content`，同样 7 次全部 200。因此**无法用直连 API 复现分支声称的 400**。

2. **但该报错文案与机制本身是真实的，只是属于另一个模型。** 用完全相同构造的请求把 `model` 换成 `deepseek-v4-pro`：缺失 `reasoning_content` → **400**，且报错原文（`The reasoning_content in the thinking mode must be passed back to the API.`）与分支 docs 存档的引用**逐字一致**（服务端仅多出 ` (request_id: <uuid>)` 后缀）；携带 `reasoning_content` → **200**。DeepSeek 官方《思考模式》文档也明文规定"携带 `tools` 的请求后续必须完整回传 `reasoning_content`，否则 400"。

3. **补丁针对的问题真实存在，补丁实现也正确，只是分支把归因挂在了错误的模型名上。** `ReasoningAwareOpenAiChatModel` 确实补上了 Spring AI 1.1.8 `OpenAiChatModel.createRequest` 把 ASSISTANT 消息第 9 组件 `reasoningContent` 硬编码为 null 的缺口（源码实读确认，且以 `@Primary` 生效）。该缺口在 `deepseek-v4-pro` 上会真实触发 400；在分支当前配置的 `deepseek-flash` 上本次未观察到触发。**合流时该补丁无需移除，风险为"无害且对 v4-pro 必要"**；但分支 docs 中「deepseek-flash……缺失会 400（实测）」的措辞**不准确**，建议核对后修正为模型无关的表述（或明确指 `deepseek-v4-pro`）。

4. **两处须登记的偏差**：
   - docs 声称"分支留有实测报错原文存档"，实际**不存在独立存档文件**，只有两处一行内联引用（无状态码、无响应 JSON、无 request_id）；本次逐字比对只能以该行文本为基准。
   - docs 声称"与 deepseek-chat/deepseek-reasoner 的旧规则相反"，因这两个别名在服务端被映射到 `deepseek-flash`（响应 `model` 字段证据），**本次无法验证**。

5. **诚实边界（供合流评审参考）**：本项结论只代表 **2026-10-06 当日、该 API key 下、`https://api.deepseek.com` 端点**的实测行为。服务端可能在不同时间/账号/后端实例上开启该强制校验（官方文档即如此规定），因而本核验**不能证伪分支当时的观测**，只能说明"该断言在核验时点不可复现，且已知可复现的承载者是 `deepseek-v4-pro`"。

---

## 6. 附件清单（同目录）

| 文件 | 内容 |
|---|---|
| `V7-附件-A-核心三组请求响应原文.json` | §3.1–3.5 核心三组（第 1 轮 / 含 reasoning_content / 去掉 reasoning_content）+ 官方文档参数复测组的完整请求体与响应原文 |
| `V7-附件-B-扩展变体矩阵.json` | §3.6 的 5 组扩展试验（基础变体、模型与参数变体、后续用户轮、3 轮循环、7 轮应用等价参数循环）的完整请求体与响应原文 |
| `V7-附件-C-deepseek-v4-pro-400报错原文.json` | §3.7 的 P1/P2/P3 完整响应原文（400 报错原文与 200 对照） |

> 说明：所有附件中的 `Authorization` 头统一记作 `Bearer ***`；真实 API Key 仅存在于发起请求的进程环境变量 `DEEPSEEK_API_KEY` 中，**未写入任何文件、未出现在任何请求 body**。

---

## 7. 现场清理与纪律声明

- **纪律**：全程**未修改**被核验分支的任何源码、配置或文档；**未执行任何 git 写操作**（`git status --short` 在结束后为空）。API Key 只在进程环境变量中，证据文档与附件均为 `***`。
- **被核验分支遗留物**：因 V8 需要真实启动后端，`ai-example-kimi-k3/backend/` 下新增了构建产物 `target/`（含 `quickstart-backend-1.0.0.jar`）与运行期数据库 `data/quickstart_db.mv.db`。二者均被该分支 `.gitignore` 覆盖（`.gitignore` 含 `target/`、`data/`），如需干净状态可直接删除。`data/` 目录在实验前**不存在**，故无需备份。
- **端口**：V8 使用的 18293 已释放，详见 `V8-kimi-作业幂等核验.md` §7。
- **未执行项**：无。核心三组 + 官方文档参数复测 + 扩展矩阵 + v4-pro 对照全部完成，无因失败重试而中止的实验。
