> 来源=<仓外证据>/ai-full-audit/D-model-history.md（2026-10-25 归档）· 审计分片/草稿，属历史证据，不代替终稿 `docs/research/排查-AI全链路只读验收审计-终稿-2026-10-25.md`
# D｜供应商/模型、历史与上下文链路 只读审计报告

- 审计对象（固定快照）：`<仓外证据>/ai-full-audit/tree`（`ae883fb` + 审计开始时未提交补丁，`working.patch` SHA256=6c88b47d…b955）
- 审计方式：**只读静态审查**。未启动任何服务、未跑任何测试、未连 8081/5202、未连接 Redis/DB、未派子 agent、未修改快照内任何文件；本报告写在仓外。
- 结论口径：区分 **可证明正确（带条件）/ 已证实缺陷 / 具体风险 / 仅缺测试 / 未验证**。所有“缺陷/风险”均给出 `path:line`、触发步骤与用户影响；没有“绝对正确”的结论。
- 快照内工作区补丁（`working.patch`）涉及 `parts.ts`（帧序防御）、`SseChatEmitter`、`ConfirmGate`、`SpToolCallingManager` 与 health/job 等；本报告阅读的是**打补丁后的快照内容**，其中 `parts.ts` 的补丁片段已逐行读过；`SseChatEmitter`/`ConfirmGate` 补丁段只做了局部阅读（见 §1.2）。

---
### 0. 结论摘要（给指挥官）

- **最高优先级**：F-01（重试把用户消息重复写进记忆并重复进请求；终态失败不收敛 ⇒ 请求内重复、刷新后 UI 双气泡）与 F-02（框架 advisor 的 40 条裁剪不保 tool 配对，可能把孤儿 `role:tool` 送上游造成整轮 400）。两者同源：产品把“整窗覆盖写 + 配对保护”建在了 `saveAll` 一侧，却没有接管官方 `MessageChatMemoryAdvisor`/`MessageWindowChatMemory` 在 `before/after` 里的另一套写入与裁剪；而全部服务级用例都把 advisor mock 掉了。
- **其次**：F-03（`max_context` 缺失静默按 128000 兜底 ⇒ 小窗口模型压缩永不触发、`/health` 还报 `compactionOn=true`）、F-05（压缩摘要以用户气泡出现在 `/history`，且与完整历史库不一致）、F-06（工具执行期无心跳，前端 45s 判断线而工具允许跑 120s）。
- **覆盖缺口**：完整历史 DB 通道（`/history/{id}/messages`）服务端正确但**前端从不消费**；`GET /api/ai/models` 与请求级 `modelId/params` 在 UI 上已退场；`/health` 不报配置版本 ⇒ 跨实例配置视图一致性不可观测。
- 本报告 §5 列出 12 条“带条件可证明正确”，§6 列出 5 类本环境无法验证项（未起服务、未跑真实拓扑）。


## 1. 覆盖清单

### 1.1 已逐行阅读的生产代码（本域）

| 文件 | 行数 | 阅读深度 |
| --- | --- | --- |
| `ai/config/AiConfigService.java` | 1085 | 全文 |
| `ai/config/AiProviderRegistry.java` | 545 | 全文 |
| `ai/config/AiModelResolver.java` | 63 | 全文 |
| `ai/config/AiModelConfig.java` | 90 | 全文 |
| `ai/config/AiConfigSeeder.java` | 152 | 全文 |
| `ai/config/AiConfigWriter.java` | 942 | 通读 + 关键段全读（命令 record、provider/model 写、bumpVersion/afterCommit、默认目标归一、maxOutput 归属） |
| `ai/config/AiEndpointGuard.java` | 222 | 全文 |
| `ai/config/AiConnectionProbe.java` | 287 | 前 150 行 + 结构 |
| `ai/config/AiRetryDefaults.java` | 180 | 全文 |
| `ai/config/AiKeyCipher.java` | 281 | 前 90 行（信封/AAD/fail-closed/单 kid） |
| `ai/config/ProviderRuntime.java` | 23 | 全文 |
| `ai/config/db/*`（AiProvider/AiProviderModel/AiSetting） | 61–117 | 结构 |
| `ai/memory/ReasoningOrigin.java` | 125 | 全文 |
| `ai/memory/RedisChatMemoryRepository.java` | 412 | 全文 |
| `ai/memory/MessageJsonCodec.java` | 179 | 全文 |
| `ai/run/ResilientChatService.java` | 2352 | 全文 |
| `ai/run/ResumeService.java` | 1260 | 全文（200–520 注释段跳读） |
| `ai/run/UpstreamErrorClassifier.java` | 314 | 全文 |
| `ai/run/StreamWatchdog.java` | 235 | 全文 |
| `ai/run/RetryBackoff.java` | 112 | 全文 |
| `ai/run/FrameSizeLimiter.java` | 449 | 前 140 行 + 结构 |
| `ai/run/SseChatEmitter.java` | 2206 | 局部（emit 取号/截断/落档/唤醒、onWake、心跳、wakeFloor） |
| `ai/run/RedisFrameStore.java` | 758 | 局部（append/xadd/失败降级） |
| `ai/gate/SpToolCallingManager.java` | 1940 | 局部（executeToolCalls 主流程、executeOnce/claimAndExecute/delegateSingle/resolvePending/settle、轮内预算、工具超时） |
| `ai/context/ContextCompactor.java` | 487 | 全文 |
| `ai/context/ContextTrigger.java` | 157 | 全文 |
| `ai/context/ContextLimitResolver.java` | 127 | 全文 |
| `ai/context/ContextConfig.java` | 92 | 全文 |
| `ai/context/MemoryWindow.java` | 248 | 全文 |
| `ai/context/RollingSummarizer.java` | 376 | 全文 |
| `ai/context/ContextSummaryClient.java` | 233 | 全文 |
| `ai/context/SystemMessages.java` | 68 | 全文 |
| `ai/context/ContextEstimator.java` | 155 | 全文 |
| `ai/context/ContextMeter.java` | 178 | 全文 |
| `ai/tool/ContextBuilder.java` | 411 | 前 200 行 + 结构 |
| `ai/kb/KbPromptSection.java` | 108 | 全文 |
| `ai/kb/KbService.java` | 943 | 局部（启停/刷新/版本轮询/readDoc 分页/search 入口/effective*） |
| `ai/kb/KbTools.java` | 347 | 结构 + 关键分支 |
| `ai/history/AiChatHistoryService.java` | 418 | 分页/写入/还原全读 |
| `ai/history/AiChatHistoryRecorder.java` | 238 | 全文 |
| `ai/web/AiController.java` | 2009 | 重点段全读（/chat、/confirm、/frontend-tool-result、/events、/cancel、/runs*、/history*、/context、/health、错误体） |
| `ai/web/AiChatRequest.java` | 74 | 全文 |
| `ai/web/AiConfigController.java` | 263 | 全文 |
| `resources/application.yml` | — | AI/上下文/记忆/超时/KB 全部键 |
| `db/migration/V4__ai_provider_config.sql`、`V5__ai_model_reasoning_backfill.sql` | — | 全文 |
| 仓内框架补丁 `org/springframework/ai/openai/ReasoningAwareOpenAiChatModel.java` | 168 | 全文 |
| 前端 `api/sse.ts` | 752 | 全文 |
| 前端 `api/ai.ts` | 263 | 全文 |
| 前端 `stores/ai.ts` | 5523 | 重点段：会话/页签所有权 240–660、loadHistory 1655–1990、handleFrame 4860–5260、send/reattach 2555–2850 |
| 前端 `utils/parts.ts` | 871 | 关键函数（openStreamPart/applyFrameToParts/alreadyApplied/markApplied） |
| 前端 `AiPanel.vue` | 3700 | 仅 composer/model/param 相关段 |

### 1.2 明确未覆盖 / 部分覆盖（不悄悄略过）

| 范围 | 状态 | 说明 |
| --- | --- | --- |
| `SseChatEmitter` 投递池/写超时/evict/回放窗口容量/`FrameWakeListener` | 未覆盖 | 只读“取号→截断→落档→发布→入队”与 `onWake` 两段 |
| `RunStore`（2822 行） | 未覆盖 | 只读 `create/saveSuspended` 字段落点与 `appendEvent` 委托；快照/待决/台账 Lua、TTL、fence 未审 |
| `LeaseService`（1146）、`SessionGate`（部分）、`CancellationRegistry`、`StartupResumeRunner`、`RunLiveness` | 部分 | 只审与续跑/取消/会话串行直接相关的入口与语义 |
| `ConfirmGate`（1937，含补丁） | 部分 | 只看心跳节拍、等待上限、请求帧与决策收敛接缝 |
| `ai/form/*`（GenerativeForm 六文件 + 附件存储） | 未覆盖 | 工具/表单子域；本域只关心“挂起即断/回灌”边界 |
| `ai/tool/*` 其余（ToolRegistry/SheetWriteGuard/WizardActionGuard 等） | 未覆盖 | 披露/越权/表格写闸 |
| `ai/tools/AiTools.java`、`ai/resultview/*`、`ai/feedback/*`、`ai/observe/*` | 未覆盖 | 非本域/观测展示面 |
| `ai/kb/*` 其余（MarkdownChunker、KbCache、HttpKbSource、KbSampleSeeder、AiKbController） | 未覆盖 | 只审 prompt 段与 readDoc 分页 |
| `ai/session/*`（Purge/Reaper） | 未覆盖 | 只看了 `SessionGate` 语义 |
| 前端 `AiPanel.vue` 渲染主链、`FormRenderer.vue`、`utils/frontend-tools.ts`、`utils/sheet-tools.ts`、`stores/workspace.ts` | 未覆盖 | 只审与 SSE/历史/会话相关接缝 |
| 测试 | 抽样 | 读约 25 个测试文件/片段，未逐个读完 300+ 文件 |

---

## 2. 链路分析（入口 → 身份 → 共享状态/外部调用 → 正常/异常 → 重连/取消/恢复 → 用户可见）

### 链 A：首轮 `POST /api/ai/chat`

**入口与身份**
1. `AiController.chat`（`AiController.java:299`）依次：可用性 → 必填 → Redis 探活 → 限流（session+IP）→ 归一化（InputSanitizer）→ 空拒 → 长度（超限 400 `MESSAGE_TOO_LONG`，拒绝不截断）→ `ContextFieldGate`（未知字段降级不回显）→ **模型存在性**（`resolveChatTarget(modelId)`，无 providerId）→ 参数范围 → 会话销毁守卫（fail-closed）→ 生成 runId → `SessionGate.acquire`（Redis 单键 Lua，跨实例）→ 僵死轮放行 → 409 SESSION_BUSY 携旧 runId + reattach 地址。
2. 挂起池提交 `ResilientChatService.chat`；`sessionId` 是本轮记忆键（`CONVERSATION_ID`），`runId` 是本轮外置键。**身份只有 sessionId/runId，没有用户身份**（§4 F-11）。

**共享状态与外部调用**
- 快照：`chatMemory.get(sessionId)`（Redis LIST `chat:mem:{sid}`，整窗 ≤40 条，TTL 24h，写时刷新）。
- 预算链（顺序刻意）：`MemoryWindow.plan`（token 预算 48k + 条数 40，先清旧工具结果再按 turn 整轮淘汰；配对保护在 `trimKeepingToolPairs`）→ `ContextCompactor.compact`（L1/L2/L3，分母 `ai_model.max_context`）→ **把窗口/压缩结果写回记忆**（`writeBackMemory`）→ `store.create` 快照（“记忆先、快照后”，见类注）。
- 请求：`runtime.chatClient().prompt().system(...).user(...).advisors(param CONVERSATION_ID).toolContext(runId,sessionId)`；`ChatClient` 带 `MessageChatMemoryAdvisor` 单例（`AiProviderRegistry.java:308/341/415`）。
- 模型实例：`RuntimeKey = providerId|keyFingerprint|baseUrl|completionsPath`，模型名不在 key 里；默认参数变更由 `config.version` 版本闸清缓存（跨实例靠 Redis `ai:cfg:ver` INCR，1s 轮询）。

**正常分支**：`consume` 逐片发 reasoning/正文 delta；`finish` 校验“最后一个 finishReason 必须是终态”（`TOOL_CALLS` 不算、无终帧判断流），空正文 → `EMPTY_REPLY` FAILED，`content_filter` → 失败，`length` → `truncated`；落快照 DONE → 记忆整窗覆盖写 → `done{usage,model,extra,context}`。
**异常分支**：分类器先 HTTP 状态后异常类型（400 越窗子类 → `CONTEXT_OVERFLOW`；401/403 不重试；429/408/5xx/网络可重试；未知不重试）；重试四条件（可重试 ∧ 未产出 ∧ 无副作用 ∧ 有下次 ∧ 未超段预算），429 Retry-After 参与预算裁剪。
**重连**：`GET /events/{runId}?lastSeq=N`，回放与挂订阅在同一出帧锁内；非终态且仍有 PENDING 的挂起轮主动收流。
**取消**：权威标志在 Redis；看门狗每拍轮询 + 同进程监听加速；终态 `done{cancelled:true}`，快照 CANCELLED，未决条目 CANCELLED 并补裁决帧，半截正文带 `stopped` 落记忆。
**用户可见**：文本/工具卡/确认卡/错误卡；`done.model` 上游回显优先；`done.context` 轮末占用。

### 链 B：重试/超时/上游错误

- 三层时间面：socket 读空闲 300s（`JdkClientHttpConnector`，`AiProviderRegistry.java:124/406`）> 段预算 300s / 事件间 90s（看门狗）> 首包 30s；工具执行期看门狗**不计静默**（beacon）。
- 前端另有 45s 帧间静默（`api/sse.ts:31`），刻意低于代理 60s，属错层设计（后端 90s）。
- 每次尝试**重建请求**（`Flux.defer`，`ResilientChatService.java:458-471`），因此重试不重用上一次的请求对象——但**不清理 advisor 已写入的记忆**（F-01）。
- 段预算重置式：挂起点段结束、恢复点重置段起点（`ToolActivityBeacon`），并配“工具步数累计不重置”（`max-tool-steps=25`）作地板。
- 非流式路径（摘要 `ChatModel.call`）走自有 `RetryTemplate`（3 次尝试、0.5s×2 上限 5s、±25% 抖动），不吃自动配置的 10 次/3min 退避。

### 链 C：HITL 挂起 → 续跑

- `saveSuspended` 先把 `assistant(tool_calls)`（含来源标记）与全部待决条目外置，再发 `suspended`/`request`；park 开启时请求帧先于执行循环公布，`tool_start` 也前移（帧序契约）。
- 续跑入口：启动扫描 / 运维 `POST /runs/{runId}/resume` / 决策后 `ConfirmGate` 派发。准入：取消（Redis）→ 终态 → RUNNING（仅启动扫描可僵尸接管，判据 = max(快照 updatedAt, 最后非心跳业务帧) 超 180s）→ **PROVIDER_GONE（按快照 `(providerId, modelId)` 精确解析）** → PENDING_UNRESOLVED → 会话门（一次性 token）→ **写权限权威判定**（LOST/UNAVAILABLE 只读不推进）→ 续跑权租约归属校验。
- 历史重建：快照历史 + 重建 `assistant(tool_calls)` + 按条目真态结算 `role:tool`（台账复用 / APPROVED 补执行恰好一次 / 终态复用 / 停走级联 SKIPPED）；`restoreSystem` 归位/重建 SYSTEM；随后窗口 + 压缩（分母带 `snapshot.providerId`）；交棒 `driveResumed`（按快照身份取实例、冻结参数逐项覆写含清空）。
- 异常：`ProviderGoneException` → FAILED 终态（不静默换模型）；续跑交棒失败 → `markDriveFailure` + `AI_RESUME_FAILED` 白名单短句。
- 用户可见：刷新后由 `runs/current` 重建卡面（`req` 位、`irreversible`、预检报告）；存在真浏览器两页签/刷新用例（本次未执行）。

### 链 D：记忆 / 上下文预算 / 压缩 / 摘要

- 两套窗口：**生产写口**（`saveAll` 整窗覆盖 + `trimKeepingToolPairs` 配对保护）与**框架 advisor 写口**（`MessageWindowChatMemory.add`，仅条数裁剪、**无配对保护**）——见 F-02。
- 压缩：L1 只清旧工具结果（幂等占位符 + 未决 HITL 保护）→ L2 按 turn 滑窗 + 递归摘要（六项保留清单、定界中和、只用本轮 runtime、结构化禁用工具、超时/截断/空产物均判失败）→ L3 按 turn 硬丢最旧（丢完仍超就照发）。原子性：先归档原文 → 写真值 → 写台账；任一步失败整步回滚（连 L1 也不落地）。
- 分母：`ai_model.max_context` → 过渡键 `app.ai.context.limit-tokens`（128000，已弃用）→ 0=未知（**实现上到不了**，见 F-03）。阈值对 `limit−reserve(13k)` 取 70/80/95，滞回 60%。
- 计量：`ContextMeter` 采集真值（空帧不采、重复不累加、prompt 回退判脏、无真值标 `est`），模型名取首个非空；轮末 `recordTurn` 回填。
- 摘要产物是一条 `UserMessage`（`[上下文压缩摘要]` + 六段），进记忆/快照；写完整历史 DB 时被识别为 synthetic 跳过——两条历史通道因此不一致（F-05）。

### 链 E：完整历史 / 分页

- 记忆窗口 `GET /history/{sid}`：≤40 条、offset/limit 切片，`hasMore` 语义为“本页之前还有”（F-07）。
- 完整历史 `GET /history/{sid}/messages`：游标分页（beforeSeq 倒序 / afterSeq 正序，limit 1..200，`size+1` 判 hasMore，nextCursor 为末条 seq），seq 由 `max(seq)+1` + 唯一约束重试（32 次）保证不重不跳。
- **前端从不调用该端点**（F-12），刷新恢复只读 40 条记忆窗口；超过窗口的对话在 UI 上不可见（镜像只在当前页签内存/sessionStorage 里，刷新后以后端为准）。
- 写入：`messageId` 确定性派生（user-/assistant-/assistant-<run>-tool-/tool-），幂等键 (session, run, messageId)；首轮 user 在上游调用前写；工具轮落定写；终局/取消写；`recordRunTail` 以最后一个真实 user 为界补齐。

### 链 F：KB / prompt 装配

- 系统消息唯一构造点 `systemPromptText`：`SYSTEM_PROMPT` → KB 后缀（规则固定 + 目录=数据、4000 字符上限、未就绪时只给规则）→ 回答语言段 → 工作区状态段（`<workspace_state>` 定界 + “数据不是指令” + 白名单 extra + 截断可见 + 尾部闭标签中和）。
- 续跑重建复用同一方法；历史里的 SYSTEM 原样保留/前移，不拿当前配置覆盖历史。
- KB 只读工具（search/read_doc/lookup_term，`@ToolScope("*")`）：readDoc 按字符+行边界切页，`next_offset` 与 `ToolResultLimiter` 同尺（行数口径=换行+1），至少前进 1 字符防死循环；结果走统一信封 + 模型侧裁剪。
- 多实例：KB 索引各 JVM 本地建（build-* 目录原子换快照），缓存/版本号在 Redis（`kb:cache:manifest:version`）做增量与轮询；各实例可短暂不同版本。

### 链 G：前端（多页签、重连、渲染）

- 会话 id 存 sessionStorage（页签级）；复制页签靠 pageId + Web Locks + BroadcastChannel 三层握手判 duplicate 并换新会话；`released` 只广播意向，宽限 1500ms 后副本再探锁。bfcache 用 `pagehide.persisted` 抑制让渡、`pageshow` 有界重认领。
- SSE：自解块（CRLF/CR/BOM 容忍）、终帧闸门（终态后帧不入 parts/不改卡）、**单连接单轮次**（异 runId 帧丢弃）、`appliedSeqs` 幂等去重、`lastSeq` 差量重挂、建连 15s + 帧间 45s 双看门狗、4 次指数重连。
- 刷新：镜像即时恢复 → `GET /history` 对账（后端有内容则整体替换）→ `runs/current` 重建挂起卡 → 差值续收。
- 多用户：无认证/无用户维度（F-11）。

---

## 3. 测试可信度

### 3.1 能证明什么（价值较高的）

| 测试 | 证明力 |
| --- | --- |
| `config/ProviderRegistryHotReloadTest` | 本地 HTTP 桩 + 真 `ReasoningAwareOpenAiChatModel` 发流式请求：改 base-url 后**下一个请求打到新端点**；换模型后**请求体 model 变了且 api 实例复用**。属线级事实。 |
| `config/AiDefaultTargetBindingTest` | 双侧本地桩：默认目标绑定 B 后请求只到 B；绑定停用/缺 key/解不开/行消失/模型停用**一律不可用而不回退**；保存 temperature 后下一轮生效。 |
| `config/AiResumeExactProviderBindingTest` | 真 service + 内存仓储 + 同名模型挂两家：续跑只到快照那一家；B 失模型 → `PROVIDER_GONE`，不去 A 借名。 |
| `org/springframework/ai/openai/ReasoningReplayRequestTest` | 捕获真实 `ChatCompletionRequest`：同供应方回放 `reasoning_content`；跨供应方置 null 但 toolCalls/tool 配对不变；env→DB 视为切换；未接线时 fail-closed。 |
| `run/ReasoningContentUpstreamProbeTest` | 线格式→`ChatCompletionChunk`→公开流式 API 的 metadata 保留 reasoning（OpenAiApi 被 mock，模型/转换是真的）。 |
| `history/AiChatHistoryServiceTest` | `@SpringBootTest` + 真 H2/Flyway：seq 冲突重试、幂等、游标分页 hasMore 边界。 |
| `run/*Conformance*`（帧序/取消台账/三参装配/落盘补发） | 真 `ResilientChatService`/`SseChatEmitter` + 内存 RunStore，断言帧序列与游标语义。 |
| 前端 `tab-ownership-lifecycle.spec.ts` | 用 `FakeLockManager`/`FakeBroadcastChannel` 覆盖让渡/宽限/bfcache/复制页签**判定逻辑**；jsdom 无 Web Locks，只证明逻辑。 |
| `frontend/e2e/13-nfezui.spec.ts` E2E-N07/N08/N09 | 真浏览器两页签（Playwright）：一 owner 一 duplicate、副本换新会话、原页签离开后接管并读回历史。**本次未执行**（需起后端），只能按代码判断覆盖面。 |

### 3.2 不能证明什么 / 假绿与水分的具体位置

1. **服务级用例根本不经过记忆 advisor**：`ResilientChatService*`、`UpstreamErrorRetryPolicyTest`、`Conformance*` 都是 `mock(ChatModel)` + `mock(AiProviderRegistry)` + `mock(ChatMemory)`，`AiConfigTestFixture:327` 直接把 `MessageChatMemoryAdvisor` mock 掉。框架在 `before/after` 里对记忆的写入、`MessageWindowChatMemory` 的条数裁剪**完全不在测试视野内**（F-01/F-02 由此漏网）。
2. **“测试自己造答案”**：`web/AiControllerHistoryPayloadTest:210-221` 把 `/history` 的 `hasMore` 定义成“本页之前还有”并断言 `true`，实现与测试同源、互相背书；对“正序翻页能否判结束”没有断言（F-07）。
3. **只断言 mock 交互**：`context/MemoryWindowWiringTest:194/207/227` 以 `verify(chatMemory.add(anyList))` 与顺序断言证明“先窗口后模型”，证明调用形状而非记忆内容；`ResilientChatServiceMemoryWriteTest` 用 fake repository 断言“只 saveAll、从不 clear”，不覆盖 advisor 写入。
4. **状态 200 型**：`AiController*` 多数是 mockMvc + mock ChatMemory，只证明控制器分支与载荷形状，不证明模型/上游/Redis 真行为。
5. **单元测试不能当生产验证**：`ContextCompactorTest`/`RollingSummarizerTest`/`MemoryWindowTest` 是纯函数级，证明算法，不证明“Redis Cluster 下跨实例同一轮看到同一份历史”。
6. **条件跳过**：`frontend/e2e/17-unlock.spec.ts:677` 的 `E2E-R18`（重挂横幅生命周期）明文 `test.skip`（缺稳定触发面，作者拒绝写假绿）；`16-kqc*.spec.ts` 按 `E2E_KQC_SPECIAL`/桩模式跳过。诚实跳过，但对应行为未被本轮任何自动化证明。
7. **时序水分**：页签让渡用例用真实计时器（`OWNERSHIP_RELEASE_GRACE_MS/3`、`GRACE_BUDGET_MS`）等待，逻辑正确但依赖调度；`sse.ts` 的 45s/15s 看门狗在 vitest 里靠 fake timers，不证明真实网络下的错层。
8. **E2E 的桩口径**：CI 的 E2E 跑在上游替身上，只证明“桩给的帧被正确消费”，不证明模型服从率与真实上游错误形态。

### 3.3 本域**没有**测试的明确缺口

- 重试对记忆的副作用（用户消息是否重复、请求是否重复发）——无。
- 真实 advisor + `MessageWindowChatMemory` + Redis 仓储组合下的窗口裁剪是否保 tool 配对——无。
- 续跑轮 `done.context.limitTokens` 在同名模型跨供应商下的口径——无。
- 压缩摘要在 `/history` 的呈现/过滤——无。
- 工具执行 >45s 时后端是否有帧（前端 idle 是否误判）——无。
- `max_context` 缺失时的回退值与压缩是否仍会触发——无。
- seed-force / Flyway 回填后其他运行实例是否重载——无。
- 事件日志落档失败 → 重挂丢 delta 的可见后果——无。

---

## 4. 发现清单

> 严重度：Critical > Important > Minor。确定性：**已证实缺陷** / **具体风险（机制可证，触发有条件）** / **仅缺测试** / **未验证**。

### F-01【Important｜已证实缺陷（机制），未在真实拓扑复跑】重试把用户消息重复写进记忆并重复进请求；终态失败不收敛，重复在 UI 持久可见

- 位置：
  - `ai/config/AiModelConfig.java:79-81`（advisor 单例）→ `AiProviderRegistry.java:308/341/415`（每个 ChatClient 都挂它）
  - `ai/run/ResilientChatService.java:458-471`（每次尝试 `Flux.defer` 重建 ChatClient 请求）
  - `ai/run/ResilientChatService.java:1378-1400`（`fail()` 只写快照终态，**不调用 `convergeMemory`**）
  - 框架（1.1.7 字节码实测，`~/.m2/.../spring-ai-client-chat-1.1.7.jar` 的 `MessageChatMemoryAdvisor#before`）：先 `chatMemory.get` 拼 `memory+instructions`，返回前**无条件** `chatMemory.add(conversationId, prompt.getLastUserOrToolResponseMessage())`；`after` 再 `chatMemory.add(conversationId, results.map(Generation::getOutput))`。
- 触发（生产默认 `max-attempts: 2`）：一轮可重试失败（如首包 30s 超时，未产出任何帧）→ 第 2 次尝试。
  - 第 2 次尝试请求体 = 记忆（已被第 1 次 `before` 写入该 user） + `[system, 同一 user]` ⇒ **同一句话在请求里出现两次**。
  - 若最终仍失败：`fail()` 不收敛记忆 ⇒ 记忆留下 1–2 份该 user 且无 assistant；刷新后 `/history` 渲染成两条用户气泡；下一轮快照也以这份记忆为底 ⇒ 重复被固化，直到窗口滚过。
- 用户影响：模型看到重复问题（可能重复作答/答非所问）、token 浪费、`/context` 占用偏高；刷新后凭空多一条重复提问；失败轮的提问永久“无回答”地留在历史。
- 多实例：与实例数无关；记忆在共享 Redis，`saveAll` 整窗最后写者胜。
- 测试：**无**（`ResilientChatServiceMemoryWriteTest`/`UpstreamErrorRetryPolicyTest` 均 mock 掉 advisor 与 ChatModel）。

### F-02【Important｜具体风险（机制已证实，边界依赖消息构成）】两套窗口实现不一致：advisor 的 40 条裁剪不保 tool 配对，可能把孤儿 `role:tool` 送进下一轮并造成一整轮 400

- 位置：
  - `ai/config/AiModelConfig.java:68-75`（`MessageWindowChatMemory(maxMessages=40)`）
  - 框架 `MessageWindowChatMemory#process`（1.1.7 字节码实测）：`drop = size - maxMessages`；遍历时 `SystemMessage` 一律保留，其余前 `drop` 条丢弃——**没有任何 assistant(tool_calls)↔tool 配对判据**。
  - 产品侧唯一有配对保护的地方是整窗覆盖写：`ResilientChatService.java:1638-1647`（首条是 TOOL 时把切口后推）；`MemoryWindow.java:219-243`（turn 淘汰保配对）。
- 触发：长会话记忆稳定在 40 条（`convergeMemory` 每轮裁到 40）→ 下一轮 `before` 追加 user 后触发框架裁 1 条 → 若被裁的是 `assistant(tool_calls)`，存储首条变成 TOOL → **再下一轮** `before` 读到的记忆以 TOOL 开头，且未越预算时 `MemoryWindow` 原样返回 → 请求体出现“无前置 tool_calls 的 role:tool”。
- 用户影响：该轮以 `UPSTREAM_REJECTED`（不可重试）失败，界面提示“上游拒绝了本次请求，请检查模型参数或配置后重试”；重发一次通常恢复（下一次 `before` 把毒首条裁掉），但失败轮提问留在记忆里。表现为“随机一轮莫名失败”，错误码不指向历史结构，排障无线索。
- 多实例：记忆共享；同一 run 由会话门串行，但窗口裁剪发生在任意实例的模型调用前。
- 测试：**无**。没有任何用例装配“真实 advisor + 真实 MessageWindowChatMemory + Redis 仓储”；产品侧配对保护测试（`MemoryWindowTest`、`ResilientChatServiceMemoryWriteTest#maxMessagesStillTrimsTheWindowKeepingToolPairs`）只覆盖另一条写路径。

### F-03【Important｜具体风险（代码可证）】`max_context` 缺失时静默按已弃用的 128000 兜底——小窗口模型的压缩永不触发，`/health` 还显示 `compactionOn=true`

- 位置：`ai/context/ContextLimitResolver.java:72-98`（`resolve→fromDatabase`，空/非正值 → `configured()`）、`:121-125`（`configured()` 读 `app.ai.context.limit-tokens`）；`AiProperties.java:1341`（默认 128000）；`application.yml:168`（显式 128000）；`ai/context/ContextConfig.java:75-90`（行里没填 `max_context` 就返回 null）；`ai/config/AiConfigWriter.java:857-861`（`maxContext` 允许为 null）。
- 触发：设置页“添加模型”不填 `maxContext`（或 PUT 省略）→ 该模型请求上限按 128000 计算。
- 用户影响：32K/8K 窗口模型，70% 触发线在 ~80K/115K，压缩永不触发；而记忆 token 预算固定 48K（`application.yml:156`）本身超过这些窗口 ⇒ 长会话被上游以越窗拒绝（`CONTEXT_OVERFLOW`，不重试，提示“对话内容过长，请开启新会话”）。`/health` 的 `contextLimitTokens=128000`、`compactionOn=true` 与事实相反。
- 与类注冲突（可直接引证）：`ContextLimitResolver.java:9-17` 声明“都没有 ⇒ 0=未知”，但“模型行没有 max_context”一路走到 `configured()`，而该键在本仓配置里恒为 128000，**“未知”分支实际不可达**。
- 测试：**无**（`ContextLimitResolverTest` 覆盖“非正值回落”，未覆盖“真实配置下 0 不可达”的组合）。

### F-04【Minor–Important｜已证实缺陷】续跑轮上下文分母丢掉 providerId：同名模型跨供应商时 `done.context` 的 limit/percent 说谎

- 位置：`ai/run/ResilientChatService.java:2145`（`limitTokens(null, model)`）对比首轮 `:1961-1962`（`limitTokens(runtime.providerId(), runtime.modelId())`）与续跑压缩 `ai/run/ResumeService.java:920-925`（带 `snapshot.getProviderId()`）。
- 机制：`providerId=null` 时 `ContextConfig.maxContextOf` 按 modelId 取**快照里第一条同名且 enabled 的行**（`ContextConfig.java:75-90`）。
- 触发：A/B 两家挂同名模型，A `max_context=128000`、B `max_context=32000`；在 B 上挂起 → 续跑。
- 用户影响：续跑轮 `done.context.limitTokens` 显示 128000（真实 32000），`percent` 约小 4 倍；同一会话首轮与续跑的百分比跳变；运维据百分比判断“还没到压缩线”而实际已接近窗口。
- 测试：**无**（`ContextFrameWiringTest` 只断言默认 128000）。

### F-05【Important｜已证实缺陷】压缩摘要以“用户消息”进入 `/history`：刷新后 UI 凭空出现一条 `[上下文压缩摘要]` 用户气泡；且与完整历史库不一致

- 位置：`ai/context/RollingSummarizer.java:222-237`（产物 = 带 `compactedFrom` 元数据的 **UserMessage**）→ `ai/run/ResilientChatService.java:1420-1422`（收敛只过滤 SYSTEM，摘要留在记忆）→ `ai/web/AiController.java:1331-1334`（`toHistoryItem` 对 USER 直接透传 `text`）→ 前端 `stores/ai.ts:905-914`（`roleOf('USER') → 'user'`）、`:1783-1802`（非 TOOL 条目一律建 text 段）。
- 触发：会话累计到 L2 压缩（默认 80% 线）→ 刷新面板。
- 用户影响：用户看到自己“说过”一段六段式模型摘要（会话目标/待办/资源 ID 等），而流式当时从未出现过这条消息；它随记忆一直存在。完整历史 DB 因 `AiChatHistoryRecorder.isSynthetic`（`AiChatHistoryRecorder.java:177-185`）跳过它，两条历史通道内容不一致。
- 附带风险：摘要正文是模型基于不可信历史生成的文本，被当成用户原话长期展示，易被后续轮次/用户误读为“用户已确认”。
- 测试：**无**（压缩测试只断言摘要消息形态与元数据，没有 `/history` 或前端渲染断言）。

### F-06【Important｜具体风险】工具执行期（上限 120s）后端没有任何帧，前端 45s 空闲即判“连接中断”并重连——长工具轮反复假断线

- 位置：`application.yml:209`（`app.ai.tool.exec-timeout: 120s`）；心跳只有两处：`gate/ConfirmGate.java:699`（挂起等人工，2s）与 `ai/run/ResilientChatService.java:714-721`（**仅当收到上游分片时**，5s）；同步工具执行（`SpToolCallingManager.executeWithTimeout`，`:1576-1610`）期间既无上游事件也无心跳。前端 `api/sse.ts:31`（45s）、`:73`（建连 15s）、`:39`（4 次重连）。
- 触发：任一后端工具耗时 >45s（导出/预检查/表格聚合等重活，配置允许到 120s）。
- 用户影响：45s 后前端 abort 并提示“连接中断/正在重试”，随后每 ~45s 重连一次；用户很可能点“停止”，而工具可能已产生副作用（停止只保证不再继续，不保证回滚）。run 在后端仍健康，重连最终能收到后续帧（`lastSeq` 差量），属**体验与误操作风险**，非数据丢失。
- 测试：**无**（后端无“工具执行期心跳”用例；前端 idle 用例不覆盖 >45s 工具）。

### F-07【Minor｜已证实缺陷】`GET /api/ai/history/{sessionId}` 的 `hasMore` 语义反了：表示“本页之前还有”，正序 offset 在 0 时恒 false

- 位置：`ai/web/AiController.java:1268`（`hasMore = from > 0 && from < total`）。
- 触发：`?offset=0&limit=10`，`total=100` ⇒ `hasMore=false`（后面还有 90 条）；`?offset=95&limit=10` ⇒ `hasMore=true`（后面已没有）。
- 用户影响：当前无现网影响（前端不传 offset/limit，`api/ai.ts:65-68`）；任何未来按 offset 正序翻页的客户端都会在第一页认为到底。`AiControllerHistoryPayloadTest:210-221` 把反向语义钉死，“用例通过”不构成该契约正确。

### F-08【Minor｜具体风险】配置写路径没有乐观并发：两个设置页/两个实例同时保存是“后写覆盖”

- 位置：`ai/config/AiConfigWriter.java:222-230`（`DefaultsCommand` 无 `version`）、`:505-545`（整体替换）；`DefaultsView` 虽回显 `version`（`AiConfigService.java:329-348`），写命令不校验。
- 触发：管理员 A 在实例 1 改 temperature，管理员 B 在实例 2 改默认模型，两次保存互不感知。
- 用户影响：一方修改被静默覆盖（含 `providerId/modelId` 成对绑定）。缓解：保存时校验“provider 已启用 + 模型在其 allowlist”，陈旧绑定会 400 而非静默串台；但温度/allowUserParams 被覆盖没有提示。
- 测试：`AiConfigWriteEndpointTest`/`AiConfigWritePersistenceTest` 覆盖单写者，未覆盖并发/版本冲突。

### F-09【Minor｜具体风险（多实例）】种子与 Flyway 回填不推进 `config.version`/不 INCR：运行中的其他实例看不到

- 位置：`ai/config/AiConfigSeeder.java:111-150`（写 provider/model 行，**不调用** `bumpVersion`、不 INCR）；唯一版本推进点是 `AiConfigWriter.java:686-696`（事务内）与 `:711-720`（提交后 INCR + 本实例 reload）；`V5__ai_model_reasoning_backfill.sql` 直接 UPDATE `ai_model` 也不动版本。
- 触发：`app.ai.config.seed-force=true` 滚动重启实例 1（换 base-url/key）→ 实例 2/3 仍在跑，`ai:cfg:ver` 与 DB `config.version` 都没变 ⇒ 继续用旧 base-url/key，直到下一次任意配置保存或自身重启。
- 用户影响：多实例下“改了种子只有一台生效”，请求无粘滞落到旧端点时会打旧上游/旧 key（401 或计费到旧账号）。README 与 registry 类注写“改种子需重启”，属已声明运维前提，但代码层没有信号（`/health` 也不报配置版本，见 F-12）。
- 测试：`AiConfigSeederTest#seedForceIsTheOnlyWayToRefillFromEnv` 只验证写库结果，不验证跨实例可见性。

### F-10【Minor｜具体风险 / 未验证】事件日志落档失败被吞掉 → 重挂/跨实例补帧永久缺那一条 delta；`done` 帧不带全文

- 位置：`ai/run/RedisFrameStore.java:265-282`（xadd 失败降级自动 ID，再失败只记日志）；`ai/run/SseChatEmitter.java:1494-1499`（先 `frames.append` 再广播）；`done` 载荷只有 usage/model/extra/context（`ResilientChatService.java:1010-1025`）。
- 触发：某条 `delta` 落档失败 + 客户端在该帧之后重挂（或从另一实例重挂）。
- 用户影响：重挂后正文少一段（seq 允许跳号，`stores/ai.ts:4933` 只按 seq 去重，不做连续性判定）。刷新走 `/history` 才用最终文本对账，属“重挂路径的短暂内容缺口”，非永久数据丢失。缺测试。

### F-11【Minor｜风险（架构事实）】AI 端点没有用户身份：多用户隔离 = 不可猜的 sessionId/runId + 浏览器页签存储

- 位置：`backend/pom.xml` 无 spring-security 依赖、`src/main/java` 无认证过滤器；`/history/{sessionId}`、`/context/{sessionId}`、`DELETE /api/ai/session/{sessionId}`、`/runs/{runId}`、`/confirm`、`/frontend-tool-result` 全部只凭 id；`stores/ai.ts:639-650` 会话 id = 客户端 `crypto.randomUUID()`。
- 触发：任何能访问后端的人拿到（或猜到/从日志/共享浏览器拿到）sessionId 即可读/删该会话记忆与上下文，或对未决卡提交决策。
- 用户影响：会话记忆可能包含表格摘要、任务标题、配置数据等业务内容；跨用户隔离依赖“id 不可猜”的能力型边界，不是身份校验。**这不是本域新引入的缺陷**（整仓无认证），但“多用户隔离”不应被表述为已实现。
- 多页签部分是真的：pageId + Web Locks + BroadcastChannel + `runs/current`，且有真浏览器用例（E2E-N07/08/09，未执行）。`tab-ownership` 单测用 fake lock manager，只能证明判定逻辑。
- 测试：无认证相关测试（因为没有认证）。

### F-12【Minor｜缺陷 + 覆盖缺口】“请求级模型/参数”与“同名模型不同供应商”的 API 语义在产品中不可达；`/health` 不报配置版本

- 位置：
  - 前端**从不发送** `modelId`/`params`：`components/AiPanel/AiPanel.vue:936`、`stores/ai.ts:2566`、`types/ai.ts:964-971`，并有 `tests/ai-panel-params-removed.spec.ts` 锁“请求体不带这两个键”。
  - `GET /api/ai/models`（`AiConfigController.java:93-102`）无前端消费者。
  - 后端仍保留请求级解析：`AiConfigService.java:611-636`（只按模型名 → **按库序第一家**）；`AiController.java:382-388` 的 400 检查同样只按名字。
  - `/health` 输出（`AiController.java:1579-1672`）无 `configVersion`/快照时间；只有 `/settings/defaults` 回显 `version`。
- 影响：UI 不会触发“同名模型选错家”，但外部客户端（脚本/E2E/未来选择器）只要发 `modelId` 就可能被解析到库序第一家且**没有 providerId 可指定**（只有续跑侧有精确绑定）。跨实例排查“这台机器是不是还在旧配置”缺字段。
- 测试：`ExactProviderBindingTest` 把“同名 → 第一家”写成旧路径既有行为，未加“必须带 providerId”的拒绝。

### F-13【Minor｜已证实缺陷（文档/行为漂移）】会话 TTL 与文档口径不一致

- 位置：`application.yml:146`（`app.ai.session-ttl: 24h`）、`AiProperties.java:36`（24h）；`README.md` 写“会话记忆存 Redis（滑动 TTL 6h，非持久化）”。
- 影响：运维按 README 估算“销毁窗口”，实际 24h；且 TTL 只在写（`saveAll` 的 PEXPIRE 经 RENAME 继承）时刷新，读不刷新——“滑动”只随轮次滑动。属文档漂移，不改行为。

### F-14【Minor｜仅缺测试】非流式/摘要路径的 HTTP 层无读超时，只靠父预算中断

- 位置：`ai/config/AiProviderRegistry.java:390-410`（只给流式 WebClient 设 300s 读空闲；`OpenAiApi` 未配 `restClientBuilder` 的 responseTimeout）；`ai/context/ContextSummaryClient.java:140-173`（`Future.get(summary-timeout=60s)` + `cancel(true)`，注释声明“中断能穿透 JDK 阻塞读”）。
- 风险：`ChatModel.call`（摘要）在极端半开连接下依赖线程中断生效；本仓没有该断言的**运行时**测试（`AiConnectionProbeTimeoutTest` 只覆盖探活 HttpClient）。属“仅缺测试/未验证”，不宣称有缺陷。

---

## 5. 可证明正确（带条件）

以下结论有代码 + 针对性测试支撑，但都带前提，不是无条件保证：

1. **默认目标成对解析（providerId+modelId）**：绑定后只跑绑定那一家；停用/缺 key/解不开/行消失/模型停用一律空 ⇒ 503/`NO_MODEL_CONFIGURED`，不静默换家（`AiConfigService.java:501-554, 916-971`；`AiDefaultTargetBindingTest` 双侧桩实测请求落点）。条件：读取时 `ai_setting` 一致；并发改配置的窄窗口会落到“空”而不是错配。
2. **续跑按快照身份精确绑定**：`target(providerId, modelId)` 失败即 `PROVIDER_GONE`，不借同名模型、不换模型（`ResumeService.java:730-756`、`ResilientChatService.java:588-627`；`AiResumeExactProviderBindingTest`）。条件：快照有 providerId；批① 旧快照走名字兼容路径（已登记）。
3. **跨供应方推理隔离**：只回放与当前 providerId 同源的 `reasoningContent`；存储不删字；toolCalls/tool 配对不变；无来源标记的旧数据按声明策略 fail-open；有标记但当前未知 fail-closed（`ReasoningOrigin.java:65-71`、`ReasoningAwareOpenAiChatModel.java:107-120`；`ReasoningReplayRequestTest` 捕获真实请求体）。条件：生产实例由 registry 构造并带 providerId（`AiProviderRegistryParamsTest` 断言）。
4. **上游错误分类与重试边界**：状态优先、未知不重试、429 带 Retry-After 且受预算裁剪、已产出/有副作用/无下次/超预算任一为真不重试（`UpstreamErrorClassifier.java:99-163`、`ResilientChatService.java:754-779`；`UpstreamErrorClassifierTest`/`UpstreamErrorRetryPolicyTest`）。条件：上游错误以这些异常形态出现；非 2xx body 只进日志。
5. **段级预算 + 步数笼**：挂起不判预算、恢复重置段、步数跨挂起累计不清零（`StreamWatchdog.java:153-170`、`SpToolCallingManager.java:370-383`；`StreamWatchdogSegmentBudgetTest`/`StreamWatchdogGateWaitBudgetTest`）。条件：beacon 锚点可用；轻装配读回 0 时按播种值。
6. **压缩原子性与失败即原样**：任一步失败返回原列表；先归档再写真值；失败只推进熔断（`ContextCompactor.java:303-357, 424-442`；`ContextCompactorTest`）。条件：archive/write 抛 RuntimeException；压缩器已装配。
7. **L2 摘要的结构与安全**：两段（system 规则 + user 定界数据）、六项清单、定界中和（含摘要/分段标签）、产物必须唯一闭合、`finishReason=length` 判失败、结构化禁用工具（`RollingSummarizer.java:156-321`、`ContextSummaryClient.java:105-200`）。条件：模型遵守 system 规则；违规产物按失败处理（历史不变）。
8. **摘要不污染主会话记忆**：摘要走 `chatModel.call`、不挂 advisor（`ContextSummaryClient.java:38-42`；`ContextConfig.java:59-62`）。
9. **记忆整窗写的 Cluster 安全**：staging 单键 Lua（RPUSH+PEXPIRE）→ RENAME（同 hash tag）→ 索引后置；删除三条单键；空白 id 拒绝；销毁守卫 fail-closed（`RedisChatMemoryRepository.java:219-221, 304-354`；`RedisChatMemoryRepositoryClusterSafetyTest`/`DeleteTest`/`SaveAllEmptyTest`）。条件：RENAME 同槽成立；索引最终一致（已声明）。
10. **完整历史游标分页**：`size+1` 判 hasMore、nextCursor、`beforeSeq=0` 空页、limit 越界归一（`AiChatHistoryService.java:199-235, 305-311`；`AiChatHistoryServiceTest`）。条件：seq 唯一约束存在（V8/V9）。
11. **单帧不做字节截断**：分片/字段级降采样/结构化 JSON 标记，超限仍合法 JSON（`FrameSizeLimiter.java:32-50, 94-140`；`SseFrameSizeLimitTest`/`DeltaFrameSplitTest`）。条件：上限 ≥1024 字节（装配期失败）。
12. **SSE 单轮次/终帧闸门/幂等**：终态后帧不落地、异 runId 帧丢弃、seq 去重、挂起即断不自动重连（`api/sse.ts:270-303, 333-357, 533-537`；`sse-watchdog` 等 vitest）。条件：帧都带 seq/runId（旧帧兼容口径已声明）。

---

## 6. 未验证 / 本环境无法验证

- F-01/F-02 的**真实触发**：未起 3 JVM + Redis Cluster + 共享 DB，未跑真实上游；F-01 的“重试重复进请求”由字节码 + 代码路径证明，但没有抓包/桩抓取请求体作为运行证据。F-02 需构造窗口边界，结论是“机制已证 + 边界成立时会发生”，未实测。
- 前端 45s idle 与 120s 工具的真实交互（F-06）：只按常量与心跳发射点推断，未跑真浏览器 + 慢工具。
- Playwright 两页签用例（E2E-N07/08/09）与全部 `@stub` E2E：需后端与桩，本次未执行；其“通过”状态只能由 CI/本地历史报告证明，本报告不采信。
- Redis Cluster 下 `ai:cfg:ver`、`chat:mem:__ids__`、`ai:xevents:` 的真实分区/迁移行为：按键设计（单键/同 tag）判断为 Cluster 安全，未实跑。
- 多实例 KB 版本收敛（`kb:cache:manifest:version` 轮询）：只读代码，未验证 Redis 锁/增量在真实并发下的收敛时间。

---

## 7. 关键证据附录

1. **advisor 的记忆写入**（框架 1.1.7，`MessageChatMemoryAdvisor#before/#after`，javap 反汇编）：
   - `before`：`memory = chatMemory.get(cid)` → 复制 + `addAll(prompt.getInstructions())` → 把首个 `SystemMessage` 移到 index 0 → **`chatMemory.add(cid, prompt.getLastUserOrToolResponseMessage())`** → 返回处理后的请求。
   - `after`：`chatMemory.add(cid, chatResponse.getResults().stream().map(Generation::getOutput).toList())`。
   - `adviseStream`：`ChatClientMessageAggregator.aggregateChatClientResponse(flux, resp -> after(...))`。
   - 产品接点：`AiModelConfig.java:79-81`（bean）、`AiProviderRegistry.java:308/341/415`（挂到每个 ChatClient）、`ResilientChatService.java:458-471`（每次尝试重新订阅 → `before` 再跑一次）。
2. **`MessageWindowChatMemory#process`**（同一 jar）：`drop = size - maxMessages`；遍历时 `if (msg instanceof SystemMessage) keep; else if (i < drop) skip; else keep`。**没有** `MessageType.TOOL` 判定，也**没有**“首条不得为 TOOL”的后置修正。
3. **产品侧两套裁剪**：`ResilientChatService.trimKeepingToolPairs`（`:1638-1647`）与 `MemoryWindow.dropOldestTurns`（`MemoryWindow.java:219-243`）都保配对；框架路径不保。
4. **摘要消息形态**：`RollingSummarizer.java:222-237`（`SUMMARY_PREFIX + <conversation_summary>…`，`UserMessage.builder().metadata(compactedFrom/lostTokens/atMs)`）。
5. **分母回退链**：`ContextLimitResolver.java:72-98` → `configured()`；`AiProperties.java:1341` = 128000；`ContextConfig.java:75-90` = 行缺列返回 null。
6. **续跑分母**：`ResilientChatService.java:2135-2155`（`limitTokens(null, model)`）vs `ResumeService.java:920-925`（`snapshot.getProviderId()`）vs `SpToolCallingManager.java:952-963`（`snapshot.getProviderId()`）。
7. **配置版本推进点**：`AiConfigWriter.java:686-696`（bump，事务内）、`:711-720`（提交后 INCR + reload + evict）；`AiConfigSeeder.java:111-150`（无 bump/INCR）；`V5` 迁移（无 bump）。
8. **读侧收敛**：`AiConfigService.java:155-240`（1s 节流；Redis 版本优先，键缺失/异常回落 DB 版本；加载失败下一拍无条件重试）。
