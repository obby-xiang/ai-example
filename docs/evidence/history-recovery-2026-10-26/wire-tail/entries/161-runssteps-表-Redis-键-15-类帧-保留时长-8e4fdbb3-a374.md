> **历史分析 / 过程证据 — 非现行基线，非验收结论**
>
> - 归档：2026-10-26 · `docs/evidence/history-recovery-2026-10-26/wire-tail/`
> - 来源：Kimi Code 会话 `<KIMI_SESSION>` / 子 agent `agent-374` 的 wire 末条 assistant 文本（消息序号 m=31；只读流式解析，非原始会话转写）
> - 历史时点：2026-10-10 23:37 → 2026-10-10 23:38（wire 事件时间窗，UTC+8）；当时模型：`DeepSeek/deepseek-flash`
> - 原基线：`d3020ee`（正文自动摘取的首次基线提及），未经本批复验
> - 原始体量：19429 B / 13071 字符 / 154 行；行指纹覆盖率（本批复算）1.9%（最高单文件 2%：`docs/任务看板.md`）
> - 类别：**过程核验**
> - 与既有件关系：施工报告；报告口径覆盖率 0.02
> - 脱敏：路径 0 / 会话标识 0 / 身份 0 / 密钥 0 / 命名纪律 0
> - 正文为该条消息的完整文本，未删段、未改写结论；仅脱敏、占位符归一与行尾空白规整。当时结论不代表当前代码状态，不得作为当前缺陷或有效裁决引用。
调研完成。以下为逐项结论与证据（路径均相对 <MAIN_REPO>，行号取当前工作树现态；HEAD 已从卡片写作时的 `d3020ee` 前进到 `03a6cbe`）。

---

# 1. 后端留痕面

## 1.1 runs/steps 表结构 —— 不存在，AI 轨迹零持久化入库

- `backend/src/main/resources/db/migration/V1__schema.sql` 全库仅 13 张表：`regions / projects / config_definitions / config_fields / config_dependencies / config_data_rows / config_staging_rows / tasks / task_items / task_files / jobs / job_items / validation_issues`。`V2__staging_base_version.sql` 只动暂存基版本。**没有任何 AI/对话/轨迹表**。
- 唯一形似字段 `tasks.current_step VARCHAR(64)`（`V1__schema.sql:92`）是业务向导步骤（UPLOAD/PRECHECK/IMPORT/PUBLISH），与 AI 轮次无关。
- 结论：**一轮 AI 对话在后端唯一落点是 Redis**，无表、无行、无外键可 join。

## 1.2 Redis 键清单（ai:run:* 等）

单一事实源 = `backend/src/main/java/com/example/configmgr/ai/run/RunStore.java:28-51`（键结构 javadoc）与 `:62-91`（常量）：

| 键 | 类型 | 内容 | 证据 |
|---|---|---|---|
| `ai:run:<runId>` | STRING(JSON) | `RunSnapshot` 全量状态 | `RunStore.java:62`、`save` `:191-214` |
| `ai:runs` | SET | runId 索引（重启发现的入口） | `:64`、`:187` |
| `ai:pending:<runId>` | HASH(field=toolCallId) | 待决工具条目 | `:66`、`:371-380` |
| `ai:claim:<runId>` | HASH | 执行认领（HSETNX 语义，认领**先于**执行） | `:68`、`:427-441` |
| `ai:ledger:<runId>` | LIST(JSON) | 工具执行台账，字段 `atMs/at/executedBy/toolCallId/name/kind/resultText` | `:70`、`:449-465` |
| `ai:events:<runId>` | LIST(JSON) | SSE 帧外置归档，窗口 **3000** 帧 | `:72`、`:100`、`:573-592` |
| `ai:beat:<runId>` | STRING | 心跳活动戳（心跳不入归档） | `:75`、`:643-650` |
| `ai:seq:<runId>` | STRING | 已发放最大业务 seq（跨进程续号锚点） | `:91`、`:604-632` |
| `ai:session:current:<sessionId>` | STRING→runId | 挂起可重建索引 | `:82`、`:228-238` |

外层还有：`ai:session:<sessionId>`（值 = sessionId/lastActivity/**rounds**，`ai/session/AiSessionService.java:33,57-64`——这是全仓最接近"会话级台账"的记录）、`ai:session:lock:<sessionId>`（`session/SessionGate.java:42`）、`ai:cancel:<runId>`（`run/CancellationRegistry.java:45`）、`ai:resume:lock:<runId>`（`run/ResumeService.java:65`）、`chat:mem:<sessionId>` + `chat:mem:__ids__`（`ai/memory/RedisChatMemoryRepository.java:53-55`）。

**RunSnapshot 字段级**（`ai/run/RunSnapshot.java:65-105`）：`runId / sessionId / status / round / attempts / cancelled / messageJson[] / assistantContent / inFlightToolCalls[] / context{page,taskType,step,taskId,extra} / finalText / error / createdBy / createdAtMs / updatedAtMs / resumedBy[]`。
注意 `messageJson`（`:82`）存的是**模型当时真正看到的完整请求历史**（含两条 system + user + 每轮 assistant(tool_calls)/tool 配对）——轨迹里信息密度最高的就是这一项，但它只活 6 小时（见 1.5）。

## 1.3 事件流帧类型清单（实测 15 类，非"delta/status/tool/form/confirm"五类）

后端出帧现场 `ai/run/SseChatEmitter.java`，前端白名单 `frontend/src/types/sse.ts:59-75` 与之逐字一致：

`start`(:479) · `message_start`/`message_end`(:505) · `delta`(:485) · `tool_start`(:551) · `tool_result`(:560) · `confirm_request`(:575) · `confirm_decision`(:587) · `frontend_tool_request`(:599) · `frontend_tool_result`(:613) · `suspended`(:534) · `heartbeat`(:545) · `retry`(:630) · `done`(:643) · `error`(:658)

三点须澄清的口径偏差：
- **没有 `status` 帧、没有 `form` 帧**。生成式表单走 `frontend_tool_request`（`name=generative_form`）——`ai/form/GenerativeFormRules.java:64`、`ai/tools/AiTools.java:511`；前端在 `AiPanel.vue:246-256` 按该 name 分流渲染 `FormRenderer`。
- "工具"是**两帧**（`tool_start` + `tool_result`），不是一帧（`types/sse.ts:12-15` 自述核对结论）。
- 存在前向兼容的 `reasoning` 帧定义，但**后端不产出**（`types/sse.ts:79-80,234-247`；ADR-4 把 `reasoning_content` 只做回填补丁）。

## 1.4 步骤状态机（两层，均无独立"step 表"）

- **轮次态** `RunSnapshot`：`RUNNING / SUSPENDED / DONE / FAILED / CANCELLED`（`RunSnapshot.java:30-39`），终态判定收敛为唯一函数 `isTerminal`（`:60-63`，含 REJECTED 归一）。
- **工具条目态** `PendingToolCall`（`ai/run/PendingToolCall.java:21-60`）：`PENDING / APPROVED / REJECTED / TIMEOUT / FRONTEND_RESULT / FRONTEND_CANCELLED / REJECTED_ARGUMENTS / CANCELLED / EXECUTED / BLOCKED`；`kind = CONFIRM | FRONTEND | BACKEND`（`:63-69`）；时间戳分列 `requestedAtMs → claimedAtMs → executedAtMs/resolvedAtMs`（`:94-105`）。
- 前端把这两套再压成自己的枚举，且**两套枚举互不一致**：`ToolRunStatus` 7 态（`types/ai.ts:161`）vs `PendingToolCall.status` 8 态（`:225`）——已被 chat-timeline 卡登记为前置改造面 P2。

## 1.5 保留时长与审计日志

- **保留 = 会话生命周期，不是审计留存**：`app.ai.session-ttl: 6h`（`backend/src/main/resources/application.yml:109`）。`RunStore.remainingTtl` 为纯函数 `min(sessionTtl, createdAt + sessionTtl − now)`（`RunStore.java:740-770`）——**滑动续期但受创建时刻 + 6h 绝对上限压制**，一轮的所有键最长 6 小时即消失。
- 分键 TTL：`ai:session:current:` = `session.lock.ttl` 10m（`RunStore.java:299-301`；`application.yml:157-159`）；`ai:cancel:` = `total-budget × 2` = 600s（`CancellationRegistry` 类注）；`ai:resume:lock:` = 300s（`ResumeService.java:413-416`）；`chat:mem:` = 6h（`ai/config/AiModelConfig.java:78`）。
- **无审计表、无追加式留痕**：`backend/src` 全量 grep `审计|audit` **零命中**。唯一的"操作流水"是三行 `log.info("操作流水: ...")`——`definition/service/DefinitionService.java:75/141/177`、`task/service/TaskService.java:270`，**AI 面一条都没有**（AI 侧 `log.info` 仅 `ConfirmGate.java:294/529/594/603/607`、`ResilientChatService.java:533`、`StartupResumeRunner.java:69/170`）。
- 需求与实现落差（关键缺口）：`docs/02-需求设计文档-v2.2-冻结版.md:121` FR-5.6「**每次对话记录 token 用量、工具调用序列、耗时并入库**」（P1）；`docs/03-技术方案文档-v2.1.md:475-482` §8.3「AI 行为记录」、`:527-529` §10。**实际：usage 只进 `done` 帧（`SseChatEmitter.java:643-656`）与一行日志（`ResilientChatService.java:478/542`），不入库、不落盘、无查询出口。**

---

# 2. 轨迹可见面

## 2.1 用户能看到什么（前端 AiPanel）

`frontend/src/components/AiPanel/AiPanel.vue`：
- **工具卡**（四态 + 参数 + 结果 + 原因）：`:194-210`；状态→色/文案为穷举 switch：`toolTagType`(`:512`)、`toolStatusLabel`(`:531`)。
- **思考链折叠块**：`:162-166`，条件是 `v-if="m.reasoning"` ⇒ **后端不产 reasoning 帧，因此实际不出现**；且用原生 `<details>`，展开态不持久。
- **挂起卡**：`:213-260+`，三分支 = 确认门（倒计时 + 拒绝原因 + 双按钮）/ 生成式表单（`FormRenderer`）/ 前端工具执行态。
- 正文气泡 + 逐条复制：`:168-192`（复制口径**不含**思考链与工具卡，`:622-624`）；历史对账骨架屏/空态/UI 驱动结果 alert：`:108-139`。
- **没有步骤条、没有时间线、没有轮次列表、没有历史会话列表页**。

## 2.2 轨迹在数据模型里被"压平"

前端展示态模型 = `ChatMessage{ id, role, messageId?, content, reasoning, toolRuns[], pendingCall, streaming, done }`（`frontend/src/types/ai.ts:180-195`）——**无 `parts[]`，三个并列字段之间没有顺序信息**，正文是整轮拼接串。刷新恢复走 `GET /api/ai/history/{sessionId}` → `loadHistory` 把 `toolCalls/toolResponses` 与正文**并列落字段**（`frontend/src/stores/ai.ts:421-480`），sessionStorage 镜像只写 `Pick<...>` 六字段（`:148-167`）。⇒ **轮内"正文↔工具"的先后不可还原**，这是 chat-timeline 卡要修的核心。

## 2.3 查询端点（AiController 实测全集）

类注 `ai/web/AiController.java:58-78` + 实测映射：

| 端点 | 行 | 返回什么 |
|---|---|---|
| `GET /api/ai/runs` | `:383-390` | 待续跑轮次清单（→ `ResumeService.listRuns` `:118-166`：status/round/messageCount/createdBy/resumedBy/pending/pendingUnresolved/ledgerCounts/activeAtMs/zombieRunning/resumeable） |
| `GET /api/ai/runs/{runId}` | `:392-399` | **全仓最完整的一轮轨迹只读视图**（→ `ResumeService.describe` `:185-207`：snapshot + context + historyRoles + inFlight + pending + ledger + ledgerCounts + keys） |
| `GET /api/ai/runs/current?sessionId=` | `:423-459` | 挂起可重建快照（三分支语义，含 archiveMaxSeq） |
| `GET /api/ai/events/{runId}` | `:293-305` | 重挂 + 状态类帧回放（`?lastSeq=N` 差量） |
| `GET /api/ai/pending/{runId}` | `:738-745` | 挂起项快照 |
| `GET /api/ai/startup-resume`、`POST .../scan` | `:360-381` | 启动续跑扫描报告 |
| `GET/POST /api/ai/cancel/{runId}` | `:330-355` | 取消事实 + 取证 |
| `POST /api/ai/runs/{runId}/resume` | `:474-493` | 续跑 |
| `GET/DELETE /api/ai/history/{sessionId}` | `:505-529` / `:560-591` | 记忆窗口原文 / 删除 |
| `GET /api/ai/health` | `:594-630` | 运行态观测面（见 §5） |
| `POST /api/ai/chat` · `/confirm` · `/frontend-tool-result` | `:149` / `:208` / `:241` | 写侧 |

**重要事实：前端几乎没用这些只读面。** `frontend/src/api/ai.ts` 导出了 `runs()`/`run()`/`resume()`/`pending()`，但全 `frontend/src` grep 调用点**零命中**（只有 `runsCurrent` 与 `reattachRun` 被 store 用，`stores/ai.ts:495,599,813`）。⇒ `/api/ai/runs*`、`/pending`、`/startup-resume` **只有 HTTP 出口、没有界面**，是纯运维/取证通道。

## 2.4 任务中心的任务详情

`frontend/src/views/TaskCenterView.vue` 只有任务列表：任务名/类型/状态/步骤(`stepLabel(row.task.currentStep)`, `:80`)/最新作业进度/创建-更新时间(`:82-86`)。点任务标题 = `restoreWizard` 跳回向导（`:47`、`:268-271`），**没有独立的"任务详情"页**。后端列表单元 = `record TaskSummary(Task task, int itemCount, int fileCount, Job latestJob)`（`task/controller/TaskSummary.java`）。任务级 SSE 只有 `HEARTBEAT / TASK_CHANGED / JOB_PROGRESS / JOB_DONE`（`types/sse.ts:337`），其 `TASK_CHANGED.summary` 是"动作摘要（如跳转到步骤: EXPORT）"——是**业务动作流水**，不是 AI 轨迹。
⇒ **任务中心里看不到任何 AI 对话轨迹**；AI 轨迹的唯一展示位是右侧 AiPanel。

---

# 3. 排队包覆盖面（只读仓外卡片）

## 3.1 <SCRATCH>/step-wizard-pkg/施工卡.md（477 行）

1. **主题**：「步骤条节点点击查看」——已结束任务进只读纯查看档（节点可点、动作按钮全隐藏、零服务端写入）。
2. **改面**：纯前端 + 1 行文档。改动预期全集 = `TaskWizard.vue` / `wizard/DefSelector.vue` / `wizard/ConditionForm.vue` / `ImportWizardView.vue` / `ExportWizardView.vue` / `docs/M2-排期计划.md`（§7 表；`:306-310` 列了逐项"零写"清单）。
3. **步骤切分 S1→S4**（`:208-305`）：S1 O-2/O-3 两个缺陷修复（`resumeJobs` 取错最早作业、导出向导缺 resume）；S2 `el-step` 节点级 `status`（失败红 + "（失败）"文字，取消中性色）；S3a 只读外壳 + 「仅供查看」横幅 + 可点 `<button>` 节点；S3b 抽 `ensureStepLoaded` 供写/读两路共用；S3c/S3d 写侧动作隐藏 + 三控件加 disabled；S3e AI 通道过路守卫。
4. **与轨迹面的关系**：**基本不覆盖轨迹面**。它覆盖的是"任务/作业/步骤"这条**业务执行轨迹**（作业面板、校验问题明细、暂存差异行），为只读回看提供数据（§1.6 数据可得性矩阵 = 9 个既有 GET 面，`TaskController.java:56-83,166-168`、`JobController.java:36-70`）。
5. 唯一交叉点：§6-③ 登记 `ui_event.goto_step`（`stores/workspace.ts:360-369`）直写 `PUT /tasks/{id}/step`、后端 `TaskService.goToStep` 无状态守卫——即 **AI 可绕道改写任务步骤**；本包只挡页面侧，不动该契约。
6. 定位声明：**明确"不改后端"**（§5 不做清单第 1 条）——不新增任何接口，故不可能补轨迹留痕。

## 3.2 <SCRATCH>/chat-timeline-pkg/施工卡.md（575 行）

1. **主题**：「对话界面时序混排」——把 AiPanel 从"三个并列字段"改为有序 `parts[]`，实现正文/思考/工具的轮内交错渲染。
2. **四条基线结论**（§0）：协议层已具备全部条件（`seq` + `atMs` + `emitLock` 内"取号→落档→入队"，`SseChatEmitter.java:677-681,729`）；后端正文段**不是**按工具边界切段（`textStart()` CAS 只开一次 `:515-522`；`message_end.chars` 是**整轮**长度，`ResilientChatService.java:477/510/541/568`）；历史记忆是消息级有序、**轮内有序不可还原**。
3. **四子包**（§2）：①消息模型（`parts[]` + 派生投影，**必须与②同批**）；②实时渲染按 seq 切段（`tool_*`/`confirm_*`/`frontend_tool_*` 到达即"封口 text part → push ToolPart → 开新 text part"，**零协议改动**）；③一卡全生命周期（拆 ③a 存量投影 / ③b ToolPart 状态机）；④折叠规则（三规则照抄业界）。
4. **三个前置改造面**（§0 P1-P3）：P1 `FrontendToolExecution` 无"结局未知"载体（`types/tools.ts:115-120`）；P2 六态 vs 实存 9 态枚举不一致，须先出收敛映射表（§3.2）；P3 **前端零测试框架**，②③④ 渲染验收无 CI 兜底。
5. **与轨迹面的关系**：**直接覆盖"轨迹可见面"的渲染层**——它要修的正是 §2.2 记录的"轮内时序被压平"。但它**只在前端**，明示 **"后端/本包零改动"**（表头第 10 行），所以不新增任何后端留痕。
6. 明确"不做"：历史不做时序重建（用户裁决，§1.7）、不新增测试框架、不改 `chat:mem` 形态（§5）。

**两卡覆盖面小结**：step-wizard 管**业务执行轨迹的前端回看**（无后端改动），chat-timeline 管**AI 对话轮内时序的前端渲染**（无后端改动）。**后端留痕/持久化/导出/审计这一整面，两卡都不碰。**

---

# 4. 回放/导出面

- **回放：有，但是"重挂式回放"，不是"回看式回放"**。`GET /api/ai/events/{runId}`（`AiController.java:293-305`）→ `SseChatEmitter.replayAndAttach`（`:373-393`）→ `stageReplay`（`:436-457`）。关键限制：**只回放状态类帧，跳过 `delta` 与 `heartbeat`**（`:441-443`）——因为 delta 按 T3-2 **不入归档**（`RunStore.java:573-581`，与心跳同待遇）。⇒ **回放补不回正文**，正文重建靠"回放状态帧 + 实时 delta"两段式。`?lastSeq=N` 仅补 `seq > N`（`:460-469`）。
- **归档不是可交付物**：数据源 = Redis LIST `ai:events:<runId>`，窗口 ≤3000 帧、TTL ≤6h（`RunStore.java:100,585-586`）——**归档一过期，这一轮就再也回放不出来**。
- **轨迹无导出/下载端点**：AiController 全部映射逐条核对（§2.3 表），**没有任何 download/export**。
- **既有 `download_export_file` 不是轨迹导出**：`ai/tools/AiTools.java:364-368` 是 FRONTEND 工具，让浏览器下载**某个配置项的导出文件**（数据面），登记在 `ToolRegistryDisclosureTest.java:56` 的 FRONTEND 工具白名单里。⇒ **对话轨迹没有对应物**。
- 前端唯一的"带走"能力是**逐条复制正文**（`AiPanel.vue:172-192`，不含思考链与工具卡）。无导出 Markdown/JSON、无分享、无打印。

---

# 5. 排障面

- **correlation id：不存在**。`backend/src` 全量 grep `MDC|traceId|X-Request-Id|requestId` → **零命中**（仅 `frontend/node_modules` 命中）。技术方案 `docs/03-技术方案文档-v2.1.md:529` §10 明列「**TraceID** 贯穿：chat 轮次 → 工具调用 → 作业 → 落库操作流水；日志结构化」——**未实现**。
- **实际可用的串联标识 = `runId`**：首帧 `start` 即下发（`SseChatEmitter.java:479-483`），409 SESSION_BUSY 也带（`AiController.java:169-176`，含 `reattach` 路径）；再加 `instanceId`（`RunStore.java:131-133`）贯穿 `createdBy / executedBy / resumedBy / storedBy` 四字段——这是本项目"跨进程追一轮"的核心手段。
- **日志**：无 `logback-spring.xml`（全仓无该文件）⇒ Spring Boot 默认**控制台输出、无文件落盘**；级别 `logging.level.com.example.configmgr: DEBUG`（`application.yml:208-211`）。关键行：`ResilientChatService.java:348`（尝试失败，带"末帧序列"）、`:494`（空回复）、`:533`（取消）、`:556`（失败）；`StreamWatchdog.java:164`；`ConfirmGate.java:294/529/594-607`。**均带 runId，但无关联 id、无结构化字段、无落盘。**
- **evidence 落盘：运行时没有**。`docs/evidence/`（如 `W2-附件-全程在线SSE原文.txt`、`S42-P2-附件B-RunStore与台账原文.md`、`docs/evidence/probes/M2-T3-5-ProbeReplay.java`）全部是**研发期人工/脚本产出**的证据文档，不是运行时自动留痕机制。
- **诊断快照：有代码、无出口**。`ResilientChatService.resilienceSnapshot()`（`:762-774`）与 `guardState(runId)`（`:777-786`，返回 `toolActive/toolStarted/lastToolActivityAtMs/cancelled/cancelPolls/budget`）是 public 方法，但**全仓 grep 只命中定义处、无任何调用点** ⇒ 没有 HTTP 端点暴露，属死代码级诊断面。
- **真正能用的排障手段（按可用度）**：
  1. `GET /api/ai/health`（`AiController.java:594-630`）——运行态总览：`sseDelivery`（投递池 poolSize/active/queueDepth/evicted/writeTimeouts）、`activeSuspendGates`、`heldSessions`、`sessionLockRenewals`、`redis` 探活、`cancellations`、`instanceId`、`resilience`。
  2. `GET /api/ai/runs/{runId}`（`ResumeService.describe` `:185-207`）——一轮的全量台账（ledger + pending + inFlight + historyRoles + keys）。
  3. `GET /api/ai/pending/{runId}` / `GET /api/ai/cancel/{runId}` / `GET /api/ai/startup-resume`（`AiController.java:738/352/360`）。
  4. `GET /api/ai/events/{runId}` 全量回放（响应体即帧序，可当"轨迹 dump"用——但不能下载）。
  5. 直读 Redis（`RunStore` 多处 javadoc 标注"诊断/取证可直读"：`seqKey` `:168`、`sessionCurrentKey` `:240`、`beatKey` `:652`）。

---

# 轨迹面一句话现状总结

**AI 对话轨迹在后端是一份"活 6 小时的 Redis 会话态"（快照 + 台账 + 3000 帧归档，字段极全但无表、无审计、无关联 id、无落盘、无导出），在前端是一份"轮内时序被压平的进程内投影"（工具卡+挂起卡+几乎不出现的思考链），两条既有施工卡（step-wizard / chat-timeline）分别覆盖业务执行轨迹的前端回看与 AI 轨迹的前端渲染，均明示不改后端 ⇒ 「持久化留痕 / 轨迹导出 / 时序回看 / TraceID 排障」这一整块是当前缺口面，而它恰好对应已冻结、未实现的需求项 FR-5.6（AI 行为记录入库）。**
