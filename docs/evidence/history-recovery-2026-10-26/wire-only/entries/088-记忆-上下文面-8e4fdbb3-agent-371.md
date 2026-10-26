> **历史分析 / 过程证据 — 非现行基线，非验收结论**
>
> - 归档：2026-10-26 · `docs/evidence/history-recovery-2026-10-26/wire-only/`
> - 来源：Kimi Code 会话 `<KIMI_SESSION>` / 子 agent `agent-371` 的 wire 末条 assistant 文本（只读流式解析；非原始会话转写）
> - 原始体量：14787 B / 9624 字符 / 121 行；行指纹覆盖率（仓内 + 落盘语料）1.0%
> - 类别：**正式独立报告**
> - 脱敏：路径 0 / 会话标识 0 / 身份 0 / 密钥 0
> - 正文为该条消息的完整文本，未删段、未改写结论；仅脱敏与行尾空白规整。当时结论不代表当前代码状态。
## 1. 记忆/上下文面

**ChatMemory 实现与窗口**
- `backend/.../ai/config/AiModelConfig.java:81-88` — 用官方 `MessageWindowChatMemory.builder().chatMemoryRepository(...).maxMessages(properties.getMemory().getMaxMessages())` 建 `ChatMemory` bean；advisor 为官方 `MessageChatMemoryAdvisor`（同文件 `:90-94`），并挂为 `ChatClient` 默认 advisor（`:146-150`）。
- 窗口大小 = **40**：`ai/config/AiProperties.java:94-99`（`maxMessages = 40`，注释"官方实现负责裁剪"）；`resources/application.yml:110-111`（`memory.max-messages: 40`）。
- 存储层是自实现的官方 SPI 扩展 `ai/memory/RedisChatMemoryRepository.java`（`implements ChatMemoryRepository`，`:51`）；类头 `:12-30` 说明为什么不用官方 2.0 实现。

**chat:mem Redis 结构与 TTL**
- 键结构：`chat:mem:<conversationId>` = Redis **LIST**，每元素一条消息 JSON；`chat:mem:__ids__` = **SET** 会话 id 索引（`RedisChatMemoryRepository.java:32-37`，常量 `:53-55`）。
- 读 = `LRANGE 0 -1` 反序列化（`:110-116`，**无条数上限**）；写 = `DEL` + `RPUSH` + `EXPIRE`（"整窗口覆盖写"）（`:118-129`）；删 = `DEL` + `SREM`（`:132-135`）。
- TTL：构造时取 **`app.ai.session-ttl`（默认 6h）**（`AiModelConfig.java:76-79` → `AiProperties.java:29`，`application.yml:109`）；每次 `saveAll` 刷新，即滑动过期（`RedisChatMemoryRepository.java:137-142`，类注 `:41-43`）。
- 已知未覆盖：同 conversationId 并发写"后写者胜"，无跨实例互斥（`RedisChatMemoryRepository.java:48-49`；`docs/research/外部设计挖掘与借鉴报告.md:204`）。

**历史恢复给模型的消息条数上限**
- `ai/run/ResilientChatService.java:193` `memory = chatMemory.get(sessionId)`（即窗口内 ≤40 条）；`:200-206` 组装为 `[首个 SystemMessage] + 记忆窗口 + 本轮 UserMessage` 后才发请求（注释自述与官方 `MessageChatMemoryAdvisor.before` 逐字对齐）。
- 续跑走重建路径：`ai/run/ResumeService.java:296-321` 从快照 `messageJson` 反序列化 + 补 `assistant(tool_calls)`/`tool` 响应；`ResilientChatService.java:275-283` `buildResumedPrompt`，**同样无条数/token 上限**（快照本身源于窗口内消息，故实际有界）。

**token 计数/预算**
- **无**。全 `backend/src/main/java` 内 `tokenCount / countTokens / maxTokens / contextWindow` **零命中**；唯一的 token 数是 `application.yml:100` 的 `max-tokens: 4096`（补全输出上限），不是上下文预算。`ai/run/UsageDetails.java:8-32` 只做**事后** usage 维度补全（cached/reasoning tokens），不参与裁剪。

**摘要/压缩/滑动窗口/截断策略**
- **只有"按条数滑动窗口 + 最早消息丢弃"**，无摘要、无压缩、无 token 预算：
  - `ResilientChatService.java:576-592` `convergeMemory`：整轮结束后 `chatMemory.clear + add`，写入前 `trimKeepingToolPairs(memoryPortion, maxMessages)`；
  - `:600-609` `trimKeepingToolPairs`：`drop = size - maxMessages`，`subList(drop, size)` 即**丢最早消息**；唯一保护是"窗口首条不得是孤立 TOOL 消息"（`:594-599` 注释，防上游 400）。
- 相邻的"截断"只作用于**单条内容**、不作用于历史条数：
  - 工具结果回填模型：`ai/tool/ToolResultLimiter.java:47-59`（行数/字符数裁剪 + 可见截断标记 `:34`），配置 `AiProperties.java:342-350`（200 行 / 8000 字符）、`application.yml:160-165`；
  - 工作区 `extra` 注入：`ai/tool/ContextBuilder.java:62-66`（单值 300 字符、整段 800 字符）、`:161-193`（白名单 + 按序截停 + "已省略 N 项"）。
- 单条消息体**无上限**（`LRANGE 0 -1` 全量读）：`docs/spike/SP-01c-决策卡.md:440-452`（明确"当前实现未做截断，是已知未覆盖项"）；技术方案列为 `docs/03-技术方案文档-v2.1.md:654` §13.2 #6 待裁决（M2）；`:682` 的"#6 已闭环"实为**SSE 帧层 64KB 截断**（`app.ai.frame.max-bytes`），并非记忆消息体上限。
- 设计意图层明确"窗口按条数、不是按 token"：`docs/spike/SP-01c-决策卡.md:442-445`；`docs/03-技术方案文档-v2.1.md:554`（`max-messages` = "防 LIST 膨胀的唯一闸门，**须按往返 token 预算反推**"）。

## 2. 会话面

**sessionId 生命周期**
- 前端生成并持久于 **sessionStorage**（页签级）：`frontend/src/stores/ai.ts:27-29`（`ai-session-id` / `ai-mirror:`）、`:102-115` `initSessionId()`（无则 `createSessionId()` 写入）、`:243-245` state 初始化。新建对话换新 id：`:1116-1123`。
- 后端无"建会话"接口，首轮即"创建或续期"：`ai/session/AiSessionService.java:24-27`（注释）、`:52-69` `ensure()`（`ai:session:<id>` STRING，值 `{sessionId,lastActivity,rounds}`，滑动 TTL = `session-ttl`，`:33`/`:54`）、`:84-96` `roundOf`。
- 索引与台账各有 TTL：`ai:run:*`（快照/台账/事件归档 `EVENT_WINDOW=3000`）在 `ai/run/RunStore.java:28-49`、`:100`、`:731-745`（滑动 TTL + "创建时刻 + session-ttl"绝对上限）。

**页签即会话**
- `docs/adr/DECISION-REGISTER.md:10` DC-06③："AI 会话以浏览器页签为界：sessionStorage 存 sessionId，刷新不丢、新页签新会话、无长期留存"。
- 面板空态明示："对话保存在当前浏览器页签中，关闭页签即结束"（`frontend/src/components/AiPanel/AiPanel.vue:137`）。

**历史持久化范围**
- 服务端只有**当前会话的记忆窗口**可直接读回：`ai/web/AiController.java:505-529` `GET /api/ai/history/{sessionId}`（原样返回窗口消息，含 type/text/toolCalls/toolResponses/timestamp），`:560-591` `DELETE`（只删 `chat:mem:<id>` + 索引；**不删** `ai:session:*` / `ai:run:*`）。
- 前端双源恢复：sessionStorage 镜像即时恢复 → 与后端 history 对账（`stores/ai.ts:419-485`），镜像内容仅展示字段（`:148-167`）。**无会话列表/跨会话检索/导出。**

**多轮上下文中系统提示与工具定义的注入**
- 系统提示每轮重新拼装为**一条** SystemMessage：`SYSTEM_PROMPT`（`ResilientChatService.java:108`）+ `contextBuilder.buildContextMessage(context)`（`:197`），理由见 `:194-196`（官方 `system` 为单槽位，两次 `.system()` 会覆盖）。
- 工具定义**每轮按上下文重算**：`toolRegistry.forContext(context)`（`:211`，另 `:228` 挂为 `toolCallbacks`），实现见 `ai/tool/ToolRegistry.java:81-91`（scope 标签裁剪）+ `:103-128`（执行前第二/三道防线复查）。
- 系统消息**不写入记忆**：`convergeMemory` 过滤 `MessageType.SYSTEM`（`ResilientChatService.java:576-579`），避免下一轮重复叠加。
- 记忆 advisor 的会话键每轮显式传：`.advisors(a -> a.param(ChatMemory.CONVERSATION_ID, sessionId))`（`:224-225`）。

## 3. 长上下文保护

- **上游调用上限**：只有 `spring.ai.openai.chat.options.max-tokens: 4096`（`application.yml:99-102`，并开 `stream-usage: true`）。**无** 总上下文窗口/输入 token 上限配置，**无**超长预检。
- **超长时的行为**：无"静默截断"，走向**上游报错 → 错误终帧**：
  - `ResilientChatService.java:334-362` 捕获 → `classify()`（`:392-419`，未命中连接类特征则归 `STREAM_ERROR`）→ 重试判定 `retryable = brokenStream && !emitted && !sideEffect && hasNext && !overBudget`；
  - `ai/run/StreamViolationException.java:40` `STREAM_ERROR`，`:55-58` `brokenStream()` 含 `STREAM_ERROR`（即上下文溢出这类 400 在首轮"无产出"时**会被重试一次**，然后失败）；
  - 失败路径 `fail()`（`:546-570`）只发 `error` 帧，**不调用 `convergeMemory`**（对照 `:467` finish / `:508` emptyReply / `:530` cancelTerminal 才收敛），即失败轮不落记忆。
- **`resilience.total-budget = 300s` 与上下文的关系**：纯**每轮墙钟**预算，与上下文大小/token 无耦合关系——
  - 配置 `AiProperties.java:189-195`、`application.yml:143`；用于"超预算不再重试"（`:344-347`）、墙钟兜底 `awaitSettled`（`:372-390`）、以及 SSE emitter 生命周期（`application.yml:178-179` 注；`/health` 暴露 `totalBudget`：`AiController.java:611-615`）。
  - 副作用是**间接**的：上下文越长上游首包/总耗时越长，越容易撞 300s → `TOTAL_BUDGET_EXCEEDED`；同一 300s 还压住了确认门键（`confirm-timeout` 取 240s，`AiProperties.java:130-139`）。无"按上下文长度调预算"的机制。

## 4. 前端对话能力面（AiPanel 现状）

`frontend/src/components/AiPanel/AiPanel.vue`（768 行）+ `stores/ai.ts`：

| 能力 | 现状 | 证据 |
|---|---|---|
| 发送 | 有 | `AiPanel.vue:569-584`，按钮 `:321`，Enter 发送 `:313` |
| 停止 | 有 | `:586-588`，按钮 `:320`（`ai.loading` 时出现） |
| 重挂（收流） | 有 | `:663-666` `reattach`；入口 `:61`（409 SESSION_BUSY）、`:93`（断流） |
| 复制 | 有（逐条，正文纯文本） | `:619-653`，按钮 `:181-191`，含 `execCommand` 回退 `:600-617` |
| 新建对话 | 有（清屏 + 换 sessionId + 删旧记忆） | `:659-661`；store `stores/ai.ts:1099-1151` |
| 骨架屏 | 有（历史对账期） | `:120-126`（消费 `ai.historyLoaded`） |
| 表单渲染 | 有（schema 驱动 `FormRenderer`） | `:246-274`；`components/AiPanel/FormRenderer.vue` |
| 工具卡 | 有（四态 + 参数 + 结果 + 前端/确认门标签） | `:194-210` |
| 思考链 | 有（`reasoning` 帧才渲染） | `:162-166` |
| 确认门卡（确认/拒绝 + 倒计时 + 拒绝原因） | 有 | `:213-244` |
| 降级/冲突提示（409/503/断流/不可用） | 有 | `:52-114` |
| 重新生成 | **无** | 全 `frontend/src` 搜 `regenerate/重新生成` 零命中 |
| 编辑重发 | **无** | 无相关 action/按钮；用户消息仅展示与复制（`:145-192`） |
| 分支 / 多版本回复 | **无** | 无相关类型或 UI；后端也无对应端点（`AiController.java` 端点清单 `:149/208/241/293/330/352/360/374/383/392/423/474/505/560/594/738`） |
| 对话搜索 | **无** | 仅配置定义/向导页有搜索框（`DefSelector.vue:10`、`DefinitionsView.vue:26`），与对话无关 |
| 导出对话 | **无** | 零命中 |
| token 用量显示 | **无（数据到了但未消费）** | 帧类型有 `usage`（`types/sse.ts:259`），`stores/ai.ts:1415-1429` 的 `done` 分支**不读 `frame.usage`** |
| 上下文剩余提示 | **无** | 无条数/token 进度 UI；`/health` 也不返回上下文占用（`AiController.java:594-629`） |
| Markdown/图表渲染 | **无**（纯文本 `whitespace-pre-wrap`），已登记暂缓 | `AiPanel.vue:168`；`docs/M2-排期计划.md:395-407` |
| 消息级赞踩 | **无** | 属同批暂缓项（`docs/M2-排期计划.md:397`、`:406`） |

## 5. 既有登记（上下文/记忆/压缩相关）

**DECISION-REGISTER.md**
- `:6` **DC-02** — 会话状态存 Redis、**不要求持久化**（取代 ChatMemory H2 持久化）；推翻条件 = 要求审计/回溯/长期留存时重审。
- `:9` **DC-05** — Spring AI 已有能力禁止自研（记忆/循环走官方抽象）。
- `:10` **DC-06** — ③ 页签即会话、sessionStorage、无长期留存；④ 双通路解耦；⑤ 工具渐进披露。
- `:18` **DC-14** — 采纳 T2（工具结果截断）、T9（extra 收敛）；**"分层记忆"明确不采纳**（见 `docs/research/外部设计挖掘与借鉴报告.md:210` R6-1）。
- `:19` **DC-15** — 限定形态生成式 UI（对话内表单）。
- `:20` **DC-16** — 数字规格纪律（新增数值须有依据注释、可感知且跨层共享者须配置键化）。

**DECISION-CARDS.md**
- `:39-47` **ADR-3** — 会话记忆选型 A：官方 SPI 自实现 `RedisChatMemoryRepository`（Redis LIST + 滑动 TTL，默认 6h，"maxMessages 窗口由官方 `MessageWindowChatMemory` 裁剪"）。
- `:31` **ADR-2 硬规范②** — 续跑必须**显式重建完整历史**，不依赖官方行为。
- `:159` 附一 — "AI 会话记忆 | LIST + 滑动 TTL 6h"。
- `:168-171` **T3b 定值注记** — ① 会话 TTL 加"创建时刻 + 6h"绝对上限（`RunStore#remainingTtl/#ttlFor`），**session 记忆键的绝对上限"另立观察项"**；② `maxmemory-policy = noeviction`；③ SSE 单帧 64KB 字段级截断。

**M2-排期计划.md**
- `:292` **R-100**（对话面板·苞 A）— 新建对话 = 立即清屏 + 换 sessionId + `DELETE /api/ai/history/{sessionId}` 删旧记忆；否掉 409；不删 `ai:session:*`/`ai:run:*`；含"`resetSession` 未同步 `this.sessionId`"必修缺陷。
- `:293` **R-101**（对话面板·苞 B）— hover 复制、停止误闪红条修复、`historyLoaded` 骨架屏接线。
- `:290` **R-98 / W-08** — 单帧截断标记 `truncated`/`truncatedReason` **前端零消费**（"模型侧有标记、人没有"）。
- `:395-407` — **AI 对话正文 Markdown/图表渲染暂缓登记**（用户裁决"优先级不高"）；含"流式半截 Markdown 重排属业界反模式"、复制口径需重定义。
- `:163` T2b 观察 R8 — "已选配置项"行**无长度上限** vs `renderExtra` 300 字符截断的口径不一致。
- `:165` issue#4 G1 — **冻结上下文**导致"同轮末步才披露的启动类工具不可见"（上下文刷新机制待修）。

**技术方案 / 研究（既有登记旁证）**
- `docs/03-技术方案文档-v2.1.md:553-554` — 配置表 `app.ai.chat-memory.ttl` / `max-messages`，后者注为"防 LIST 膨胀的唯一闸门，须按往返 token 预算反推"。
- `docs/03-技术方案文档-v2.1.md:654` — §13.2 #6"Redis 单条消息体上限与仓库层截断阈值"待裁决（M2）；`:682` 闭环的是**帧层 64KB**，非消息体。
- `docs/research/外部设计挖掘与借鉴报告.md:194-212` — §6 记忆对比：`chat:mem` LIST + 整窗口覆盖写 + 滑动 TTL 6h + `trimKeepingToolPairs`；缺口 = 无跨实例互斥、无单条消息大小上限；R6-3 待办；`:368` B11 入 M2 backlog。

---

**上下文管理能力现状一句话总结**：当前只有"官方 `MessageWindowChatMemory` 按**消息条数**（maxMessages=40）滑动裁剪 + 最早消息优先丢弃（带 tool 配对保护）+ 整窗口覆盖写回 `chat:mem:<sessionId>`（LIST，滑动 TTL 6h，与页签会话同寿）"这一条链路，**全程无 token 计数/预算、无摘要或压缩、单条消息大小无上限**，历史恢复严格受该 40 条窗口约束，超长上下文既不预检也不静默截断而是由上游报错成 `STREAM_ERROR`（可能先重试一次）；前端具备发送/停止/重挂/复制/新建对话/骨架屏/表单/工具卡/思考链，**缺**重新生成、编辑重发、分支、搜索、导出、token 用量显示与上下文剩余提示（usage 帧已到端但未消费）。
