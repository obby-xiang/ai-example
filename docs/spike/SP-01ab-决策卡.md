# SP-01ab 决策卡：官方循环内的「前端工具暂停-恢复」与「HITL 确认门」

- **验证日期**：2026-10-06（本地时间 19:39–19:52）
- **工程**：`<REPO_ROOT>/spike/sp01ab`（Spring Boot 3.5.14 + Spring AI 1.1.8 BOM import + `spring-boot-starter-web`）
- **模型**：`deepseek-flash`，`base-url = https://api.deepseek.com`，密钥仅经环境变量 `DEEPSEEK_API_KEY`（本卡与全部附件中一律写 `***`）
- **端口**：`18301`（PoC 应用）、`18305`（仅本次取证用的本地记录代理，非产品设计）
- **操作纪律**：未改动 `<REPO_ROOT>` 其他任何目录；未执行任何 git 写操作；只停止了自己启动的进程（详见 §10）
- **脱敏**：本卡与附件已把本机路径替换为 `<REPO_ROOT>` / `<MAVEN_HOME>` / `<JDK_HOME>` / `<M2_REPO>`，用户名替换为 `<USER>`，密钥替换为 `***`

**证据等级约定**：【实测】= 本次真实运行留档、原文可复核；【推断】= 逻辑推演、未实测（均标注）。

---

## 0. 结论摘要

| 子项 | 结论标签 | 支撑 |
|---|---|---|
| **SP-01a** 前端工具（循环透出 → 前端回灌 → 循环恢复） | **有条件可行【实测】** | 官方扩展点 `ToolCallingManager` 内以「阻塞式挂起」实现；非流式 `call()` 与流式 `stream().chatResponse()` **两条官方循环路径各 11/11 断言全过**。条件见 §2.4 |
| **SP-01b** HITL 确认门（放行 / 拒绝 / 超时 + 挂起期心跳） | **可行【实测】** | 三种结局各 6/6、7/7、9/9 断言全过；挂起窗口内 15 帧心跳、连接未断 |
| 官方循环是否提供「一等公民暂停-恢复」 | **不提供【实测】** | Spring AI 1.1.8 无 suspend/resume API；本次的 4 个 HTTP 端点即 PoC 自建的外部输入通道（§4） |

任务书通过条件对照（两路径均满足）：

| 通过条件 | 非流式 | 流式 |
|---|---|---|
| 链路走通（透出 → 回灌 → 恢复） | ✓ A1/A2/A3 | ✓ |
| Loop 正常结束（非异常中断） | ✓ A4、B/C/D 的 `done.failed=false` | ✓ |
| 结果正确（最终回答含前端回灌数据 + 后端工具结果 42） | ✓ A5/A6 | ✓ |

---

## 1. 验证方法（可复现）

技术栈与配置：Spring Boot 3.5.14；`spring-ai-bom 1.1.8` import；`spring-ai-starter-model-openai`；Java 21。
官方扩展点接法（与合流参照实现 `HooksToolCallingManager` 同形）：`OpenAiChatModel.builder().toolCallingManager(<自定义管理器>)`，delegate 由官方 `ToolCallingManager.builder()` 构造。

> javap 实测（1.1.8）：`OpenAiChatModel$Builder` 上确有 `toolCallingManager(ToolCallingManager)` 与 `toolExecutionEligibilityPredicate(...)`；`OpenAiChatModel.call(Prompt)` 与 `stream(Prompt)` 的字节码中都出现 `ToolExecutionEligibilityPredicate.isToolExecutionRequired(...)` → `ToolCallingManager.executeToolCalls(...)` → `ToolExecutionResult.returnDirect()`。即 **两条路径的循环都在官方模型类内部，扩展点唯一**。

命令（全部在 `<REPO_ROOT>/spike/sp01ab` 下执行；key 只用环境变量）：

```bash
# 构建
"<MAVEN_HOME>/bin/mvn.cmd" -B -DskipTests package

# 协议取证代理（记录每次发给模型的请求/响应原文，Authorization 一律 ***）
node scripts/sp01ab-wire-proxy.mjs "$PWD/evidence" 18305

# 启动（base-url 指向取证代理；直接打线上则改用 https://api.deepseek.com）
export DEEPSEEK_API_KEY="***"
export AI_BASE_URL="http://127.0.0.1:18305"
java -jar target/sp01ab-frontend-tool-hitl-0.0.1-SNAPSHOT.jar

# 断言套件：非流式（POST /api/chat）与流式（POST /api/chat-stream）两条路径各跑一遍
node scripts/sp01ab-verify.mjs "$PWD/evidence" all             # 非流式
SPIKE_STREAM=1 node scripts/sp01ab-verify.mjs "$PWD/evidence-stream" all   # 流式
```

PoC 用 HTTP 端点模拟“前端”（非生产契约）：`POST /api/chat`、`POST /api/chat-stream`、`GET /api/events/{sid}`（SSE）、`POST /api/frontend-tool-result`、`POST /api/confirm`、`GET /api/events/{sid}/timeline`。

三个工具覆盖三类：

| 工具 | 类别 | 循环内处理 |
|---|---|---|
| `calculate` | 后端工具 | 原样委托官方 `DefaultToolCallingManager` 执行 |
| `get_user_profile` | **前端工具** | 不委托：SSE 透出调用请求 → 阻塞等 `POST /api/frontend-tool-result` → 结果作为官方 `ToolResponse` 交回循环 |
| `delete_config` | 敏感工具 | 不委托：SSE 下发确认请求 → 阻塞等人工决策 → 批准才执行；拒绝/超时以“未执行”文本回填 |

`get_user_profile` 的方法体是**哨兵桩**（返回 `BACKEND_STUB_SHOULD_NOT_RUN`），并用计数器证明后端从未执行它——避免“结果到底来自前端还是后端”的自证式结论。

---

## 2. SP-01a 实测：前端工具透出-回灌-恢复

### 2.1 事件时序（非流式运行，session `sp01ab-A-20261006-194820`）

| seq | 时间 | 事件 | 关键内容 |
|---|---|---|---|
| 2 | 19:48:21.634 | `start` | 一轮对话开始（工具清单含 `get_user_profile`） |
| 4 | 19:48:22.761 | `tool_start` | `calculate` / kind=`backend` / args `{a:6,operator:multiply,b:7}` |
| 5 | 19:48:22.762 | `tool_result` | `ok=true executed=true`，结果 `"后端计算器结果：6 multiply 7 = 42"` |
| 6 | 19:48:22.762 | `tool_start` | `get_user_profile` / kind=`frontend` / args `{userId:u-1024}` |
| 7 | 19:48:22.763 | **`frontend_tool_request`** | 透出 `toolCallId=call_01_oxvRn…`、`timeoutSeconds=60`、回调端点 |
| 8 | 19:48:22.810 | **`frontend_tool_result`** | `ok=true source=browser-localStorage waitedMs=48`，结果含前端回灌的随机令牌 `FRONTEND-SVUMQ1` |
| 10 | 19:48:24.193 | `model_reply` | `finishReason=STOP`，回答含 `42` 与 `张三-FRONTEND-SVUMQ1` |
| 11 | 19:48:24.194 | `done` | `elapsedMs=2560 failed=false` |

一次 assistant 消息里的两个工具调用被**顺序**处理：后端工具立即执行，前端工具阻塞 48ms 等回灌，然后两条 `role:tool` 消息一起交回官方循环。

### 2.2 模型侧原文（取证代理留档，`wire-01/02`）

```
# 第 1 次请求（发给模型）
tools = calculate, get_user_profile, delete_config      ← 前端工具以真实 JSON Schema 下发（后端无实现，靠循环内拦截）
messages.roles = system / user
→ 响应：finish_reason=tool_calls
  tool_calls = [ calculate({"a":6,"operator":"multiply","b":7}),
                 get_user_profile({"userId":"u-1024"}) ]

# 第 2 次请求（官方循环回填后再次请求模型）
messages = [ assistant(content="我这就同时发起两个调用。", tool_calls=[call_00_…, call_01_…]),
             tool(calculate → "后端计算器结果：6 multiply 7 = 42"),
             tool(get_user_profile → "用户 u-1024 的前端缓存资料…令牌=FRONTEND-…") ]
→ 响应：finish_reason=stop，最终回答
```

**这就是 SP-01a 的判定证据**：前端回灌的数据以官方 `role:tool` 消息进入循环，模型据此作答；同时后端 `get_user_profile` 桩体计数为 0。

### 2.3 断言明细（非流式 11/11、流式 11/11，两路径一致）

| ID | 断言 | 结果 |
|---|---|---|
| A0 | `POST /api/chat[-stream]` 受理 202 | PASS |
| A1 | 收到 `frontend_tool_request`（含 id/name/args/timeout） | PASS |
| A2 | `POST /api/frontend-tool-result` 被受理（`accepted=true`） | PASS |
| A3 | 循环内 `frontend_tool_result` ok=true、source=browser-localStorage并含回灌令牌 | PASS |
| A4 | `done.failed=false`（Loop 正常结束） | PASS（elapsedMs≈2.5–4s） |
| A5 | 最终回答含前端回灌数据（令牌嵌入姓名/等级字段） | PASS |
| A6 | 最终回答含后端工具结果 `42` | PASS |
| A7 | 无 `error` 事件 | PASS |
| A8 | 工具分类正确（`calculate:backend` + `get_user_profile:frontend`） | PASS |
| A9 | 后端**未执行**前端工具方法体（哨兵桩计数 0） | PASS |
| A10 | 后端工具执行 1 次 | PASS |

### 2.4 “有条件可行”的条件（边界，必须写进技术方案）

1. **挂起 = 阻塞官方循环里执行工具的那条线程**，不是一等公民的暂停。Spring AI 1.1.8 无 interrupt/checkpoint/`input_required` 类 API，`ChatClient` 也无 resume 方法；“外部输入通道”（SSE 下发 + HTTP 回灌）是 PoC 自建的。合流参照实现在流式下用 `subscribeOn(boundedElastic)` 承载这个阻塞，本次实测该做法成立。
2. **挂起态在进程内存里**（`Gate` + `CountDownLatch`）。进程重启即丢；多实例部署时回灌请求必须落到同一实例（粘性路由或外置状态存储）。**【推断】**：本次单实例验证，未测多实例/重启续跑。
3. **挂起占用一个工作线程 + 一条 SSE 连接**；并发挂起上限 = 线程池容量。若在 Tomcat 请求线程上直接挂起会耗尽线程池。**【推断】**：未做压测。
4. **前端工具仍需注册成普通 `@Tool`** 以便生成 Schema 下发（否则模型不可能发起该调用），但方法体永不执行——实现上是“只借 schema，不借执行”。
5. **超时语义需自定**：本 PoC 前端工具超时 → 以“前端未在 N 秒内回传，本次未获得数据”回填；确认门超时 → “已取消、未执行”回填（见 §3）。
> 注记（2026-10-10 批 A）：上句「以“前端未在 N 秒内回传，本次未获得数据”回填」为批 A 前口径；该口径已随**批 A**（等待治理排查 W-01 + W-02 改造，本仓去向表条目 `R-95`）变更为「**结局未知（不表示未发生）**」——「未获得数据」把**回执未在预算内到达**读成**操作未发生**，文件实际已落盘时即为**假失败**。权威实现见 `ConfirmGate#expire` 两分支：确认门分支仍为「已取消、未执行」（**确定未执行**），前端工具分支改为「结局未知（不表示未发生）」，两分支措辞不得互相串用。

6. 同一会话的并发对话被拒绝（409）：挂起门是会话级单值，与参照实现“同一会话最多一个确认门”一致。

---

## 3. SP-01b 实测：HITL 确认门三种结局 + 心跳保活

敏感工具 `delete_config` 的四种观测（三结局 + 参照）：非流式与流式两轮运行结果一致。

| 结局 | 断言 | 非流式实测原文（流式同） |
|---|---|---|
| ① 放行 | B1–B6 全 PASS | `confirm_decision{decision=approve, approved=true, source=http-post, waitedMs=11}` → `tool_result{ok=true, executed=true}` → 后端 `delete_config` 计数 **+1** → 最终回答“配置定义 def-777 已删除” |
| ② 拒绝 | C1–C7 全 PASS | `confirm_decision{decision=reject, reason="该配置在生产环境仍被引用，禁止删除"}` → `tool_result{ok=false, executed=false, result="用户拒绝了该操作，工具未执行。拒绝原因：…"}` → 计数 **+0** → 模型回答“删除操作**未执行**——已被拒绝，配置 def-777 仍然存在” |
| ③ 超时(30s) | D1–D9 全 PASS | 挂起 30002ms 后 `confirm_decision{decision=timeout, waitedMs=30002}` → `tool_result{ok=false, executed=false, result="确认等待超过 30 秒，系统已自动取消该操作（工具未执行）。"}` → 计数 **+0** → `done.failed=false elapsedMs=32792`（循环未被中断） |

**挂起期心跳保活（结局③，即“完全不操作”的最坏情形）**：
`confirm_request`(19:48:33.953) 到 `confirm_decision`(19:49:03.955) 之间，SSE 共下发 **15 帧 `ping`**，每帧 `data.pending="confirm:delete_config"`，间隔 2000ms；客户端侧到达时间戳（首帧 +2126ms、末帧 +30127ms）见 `sp01ab-D-sse-raw.txt`，HTTP 200 / `text/event-stream` 全程未断。
心跳实现与参照实现略有差异：参照把 20s 心跳放在 gate 的 `await` 循环内、经 `UiEventSink` 发 `ping`；本 PoC 用独立 `@Scheduled(2s)` 扫描“有 SSE 订阅者的会话”，`data.pending` 直接暴露当前挂起类型——好处是心跳与挂起解耦、便于观测，代价是多一个定时任务。

---

## 4. 卡点定位：哪一层能表达“执行前挂起”

| 层 | 能否在工具执行前挂起 | 依据 |
|---|---|---|
| `ChatClient` API 形状 | **不能**：无 suspend/resume/continue API，`.call()` / `.stream()` 一次跑完整个循环 | 【实测】本次所有挂起都由 PoC 端点驱动；javap 签名中无 resume 类方法 |
| Advisor 链 | **不能**（Advisor 环绕 prompt/response，工具执行发生在 `OpenAiChatModel` 内部，拿不到“单次工具执行的注入点”） | 【推断】：javap 证实 `executeToolCalls` 由 `OpenAiChatModel` 调用；本次未实测 Advisor 方案 |
| `ToolExecutionEligibilityPredicate` | **只能决定“要不要执行工具”**，不能挂起（返回 false 等于把 assistant 消息交回调用方，见 §5 方案 B） | 【推断】（javap 显示它只在 `isToolExecutionRequired` 处被调用一次） |
| **`ToolCallingManager.executeToolCalls`（官方扩展点）** | **能**：在把 `ToolResponse` 交回循环之前可任意阻塞，官方继续负责回填与下一轮请求 | 【实测】本次两个子项均由此实现 |

结论：**“透出-回灌”在官方循环里表达得了，但必须落在 `ToolCallingManager` 层，且形态是“阻塞等待”而不是“返还控制权后恢复”。** 若产品需求是后者（例如后端要能重启、要能跨实例续跑、要能给前端一个可持久化的挂起态），官方 1.1.8 没有对应能力。

---

## 5. 若合流必须要“真·暂停”（返还控制权）：最小绕行方案

- **方案 A（本次实测，推荐）**：阻塞式挂起。
  代价：挂起期占线程与会话内存；需要会话粘性；超时语义自定。收益：完全复用官方循环（回填/下一轮/终止判断都是官方的），改动面最小，与参照实现同形。
- **方案 B【推断，本次未实测】**：让 `toolExecutionEligibilityPredicate` 在“本轮含前端工具调用”时返回 false → 官方循环把 `AssistantMessage(tool_calls)` 原样交回调用方；业务侧持久化挂起态，前端回灌后由业务侧把 `ToolResponseMessage` 接回去**重新发起一次请求**续跑。
  代价：等于把“循环续跑权”收回业务侧，与“官方能力优先”的约束冲突加大；必须自行保证 `assistant(tool_calls)` 与 `role:tool` 消息序列合法且不重放、不丢；流式路径下还需自己拼接/续接 SSE。**要选它，必须先补一次实测。**
- **方案 C**：自研循环——被否决（见 §6）。

---

## 6. 被否决方案与回归风险

| 方案 | 否决理由 | 若采纳的回归风险 |
|---|---|---|
| 自研工具循环（不用官方 `ToolCallingManager`） | 与任务约束（官方能力优先、禁止自研循环）直接冲突 | 工具 Schema 生成、并行/多次工具调用、流式增量聚合、重试与异常处理、多模态/新供应商兼容全都要自维护；Spring AI 升级需同步跟进，长期维护成本最高 |
| 在 Advisor 层拦截 | §4：拿不到单次工具执行的挂起点 | 只能靠关掉 `internalToolExecutionEnabled` 再自己拼循环，等于半自研，收益为零 |
| `ToolCallbackResolver` 返回“抛异常”的 callback，靠异常触发挂起 | 官方 `DefaultToolCallingManager` 会把异常交给 `ToolExecutionExceptionProcessor` 转成**错误文本回填**，无法区分“等外部输入”与“真失败”，也无法保证回灌幂等 | 前端工具的正常挂起会以失败语义进入模型上下文，可能诱发模型改口/重试；超时与取消语义无处安放 |
| 把前端工具结果伪装成“用户的下一条消息” | 破坏 `assistant(tool_calls)` ↔ `role:tool` 配对 | 消息序列不合法（部分模型直接 400）、审计链断裂（无法区分人说了什么与系统注入了什么） |
| 让前端工具真的在后端实现（返回假数据） | 丢失“数据只存在前端”的业务语义 | 前后端数据源不一致，用户在工作区看到的与模型看到的不符 |

---

## 7. 对合流有用的次要实测发现

1. **两条工具执行路径的结果文本形状不一致【实测】**：委托官方 `DefaultToolCallingManager` 执行的结果被 JSON 序列化，前端/模型看到的是带引号的 `"\"后端计算器结果：6 multiply 7 = 42\""`（见 timeline `tool_result` 与 `wire-02-request.json` 的 `role:tool` 内容）；而 PoC 自建 `ToolResponse`（前端工具回灌、拒绝、超时）是原文透传。合流时建议统一（或在前端卡片渲染时归一化），否则卡片展示会出现多余引号。
2. **`deepseek-flash` 的 `reasoning_content` 结论得到独立复现【实测，范围限定】**：响应中确有 `reasoning_content`（思考模式成立），Spring AI 回填 assistant 消息时**不带**该字段，而本轮共 **16 次模型请求**（非流式 8 + 流式 8，含多轮 tool 回填）**零 400**。与 `docs/evidence/V7` 的结论一致（“deepseek-flash 缺 reasoning_content 必 400”不成立）。范围限定：仅 tool 调用多轮、单 key、当日模型版本。
3. **一条 assistant 消息可同时含 content 与 tool_calls【实测】**（`wire-01` 原文 `content="我这就同时发起两个调用。"` + 两个 `tool_calls`）。官方循环原样保留该消息，前端“工具卡片 + 文本气泡”的渲染要容忍这种混合。
4. **模型行为抖动会让“复述式断言”不稳定【实测】**：早期一次运行机制全通，但最终回答没有复述前端回灌的令牌（A5 失败）；把随机令牌嵌进数据本身（姓名/等级字段）后两轮稳定通过。→ 端到端断言应让**数据自带可判定特征**，不要依赖模型复述。
5. **流式路径同样可行【实测】**：`stream().chatResponse()` + `subscribeOn(boundedElastic)` 下，两种挂起都正常；流式运行额外产生了大量 `delta` 事件（timeline 550 行 vs 非流式 63 行），说明挂起不阻塞 SSE 通道本身（心跳与 delta 都走同一通道）。
6. **上游超时/重试与长挂起的相互作用是合流必须复核的点【推断】**：合流参照实现给流加了 `timeout(90s).retry(1)`。本次 PoC **未**启用该超时（且挂起期每 2s 有心跳），因此没有触发；若合流的超时判定基于“无事件”而非“有心跳”，长时间挂起（>90s 的确认等待）可能触发重放，导致**同一工具调用被重复请求**。建议合流中把“挂起期心跳”纳入超时判定的输入，并对重放加幂等保护。

---

## 8. 证据清单

代码即证据（`<REPO_ROOT>/spike/sp01ab`）：

| 文件 | 作用 |
|---|---|
| `src/main/java/com/example/spike/ai/SpikeToolCallingManager.java` | **核心**：官方 `ToolCallingManager` 扩展点上的两类挂起 |
| `src/main/java/com/example/spike/config/SpikeConfig.java` | 官方扩展点装配（`OpenAiChatModel.Builder.toolCallingManager(...)`） |
| `src/main/java/com/example/spike/hitl/Gate.java`、`SessionState.java` | 挂起门与会话态（含计数器，用于证明“执行与否”） |
| `src/main/java/com/example/spike/bus/SessionEvents.java`、`Heartbeat.java` | 事件留档 + SSE 扇出 + 心跳 |
| `src/main/java/com/example/spike/web/SpikeController.java` | 模拟前端的 HTTP/SSE 端点（含流式 `/api/chat-stream`） |
| `src/main/java/com/example/spike/tools/SpikeTools.java` | 三个 `@Tool`（含前端工具哨兵桩） |
| `scripts/sp01ab-verify.mjs` | 33 项断言套件（两路径各跑一次） |
| `scripts/sp01ab-wire-proxy.mjs` | 记录每次发给模型的请求/响应原文（Authorization → `***`） |
| `scripts/sp01ab-sanitize.mjs` | 附件脱敏 |

原始留档（同一份内容同时存放于 `<REPO_ROOT>/spike/sp01ab/evidence[-stream]/` 与 `<REPO_ROOT>/docs/spike/logs/sp01ab/`）：

| 路径 | 内容 |
|---|---|
| `blocking/`（31 个文件） | 非流式运行：`sp01ab-{A,B,C,D}-timeline.json`（服务端事件时序）、`sp01ab-{A,B,C,D}-sse-raw.txt`（SSE 逐帧原文含客户端到达时间戳）、`sp01ab-{A,B,C,D}-checks.json`（断言明细）、`sp01ab-summary.json`、`sp01ab-timeline-digest.txt`（时序摘要）、`wire-01..08-{request,response}.json`（模型协议原文）、`wire-proxy.out` |
| `streaming/`（32 个文件） | 流式运行，同上，另含 `app-run.log`（应用启动与运行日志，PID/端口/配置） |

映射关系（一次对话 = 2 次模型请求）：`wire-01/02`=场景 A、`wire-03/04`=场景 B、`wire-05/06`=场景 C、`wire-07/08`=场景 D；两轮运行的 wire 文件名各自独立编号。

---

## 9. 推翻条件

1. Spring AI 升版后 `ToolCallingManager` 语义/接口变化，或官方引入一等公民的暂停-恢复（interrupt / checkpoint / `input_required`）→ 本卡 SP-01a 的“有条件可行”应升级为“直接可用官方能力”，实现方式需重做。
2. 若合流保留 `timeout(90s).retry(1)` 且超时判定忽略挂起期心跳 → 长挂起会被重放并重复请求工具，本卡 SP-01b 的超时结局结论不再成立（需重新验证幂等）。**【推断】**，本次未触及。
3. 若要求多实例/进程重启后继续挂起流程 → 阻塞式方案不成立（挂起态在进程内存），必须改 §5 方案 B。
4. 若并发挂起数需求超过工作线程池容量 → 阻塞式方案不成立（需队列化或方案 B）。
5. 若换成非思考模式/另一供应商模型（如不支持并行 tool_calls）→ A 场景“一次消息触发两类工具”的结论需重测（本卡只覆盖 `deepseek-flash` 当日行为）。

---

## 10. 收尾与清理

- 只停止了自己启动的进程：PoC 应用（`18301`，PID 见 §8 `app-run.log`）与取证代理（`18305`）。
- 结束后 `netstat` 复核：`18301`、`18305` 均无 LISTENING，端口已释放。
- 禁用的端口（18080 / 18290–18294 / 18302 / 18303 / 18312）全程未使用；启动前确认 `18301` 空闲。
