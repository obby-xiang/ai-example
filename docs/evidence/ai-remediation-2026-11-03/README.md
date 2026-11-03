# AI 对话历史 / 恢复 / 表单 / 资源整改与验证报告（2026-11-03）

> **性质**：本轮整改的**文档沉淀与验证记录**，不是验收报告，也不替代后续验证批的验收结论。本文只描述代码与既有文档中的事实、以及主控在本轮真实浏览器中实际走通的核心场景；**不把静态阅读、单点测试或浏览器走查写成“全量通过”**。
>
> **基线**：`HEAD = e7258b12` + 当前工作树。工作树实测 **67 个已跟踪文件修改 + 1 个未跟踪测试文件**；`git diff --shortstat` = **67 files changed, 3948 insertions(+), 1055 deletions(-)**。工作树内容尚未提交，引用时按“当前工作树”理解，不当作已入库事实。
>
> **写作边界**：本笔只改 `docs/`；**零代码 / 零测试执行 / 零脚本改动 / 零服务起停 / 零 git 写**。仓库内不写密钥、不写本机绝对路径、不写个人隐私；仓外过程材料（浏览器截图、运行日志、demo）一律不入库。行号以当前工作树为准，代码后续变动后以最新文件为准。
>
> **与既有文档的关系**：本文不重复设计稿与审查稿的论证。上下文压缩的交互契约见 `docs/research/设计-上下文压缩整改与交互-2026-11-03.md`；提示文案与交互问题清单见 `docs/research/审查-AI对话内容提示与交互-2026-11-03.md`；租户隔离与 Redis 驻留面见 `docs/research/复核-租户与用户隔离现状-2026-11-03.md` 与 `docs/research/审查-Redis锁缓存与内存驻留全链路-2026-11-03.md`；现行需求 / 技术口径见 `docs/02-需求设计文档-v2.2-冻结版.md` / `docs/03-技术方案文档-v2.1.md`。

---

## 1. 本轮范围与提交台账

本轮覆盖 **AI 对话历史的真源与恢复、失败终态、上下文压缩、SSE 重连对账、页签隔离、停止与新会话、任务创建复用、条件草案绑定、知识库缓存代次、精确计数、C 端提示与资源清理**。已入库提交如下（按时间正序）：

| 提交 | 日期 | 提交主题 | 与本报告的关系 |
|---|---|---|---|
| `3b28e370` | 2026-11-02 | `refactor(ai): 精简对话冗余设计与历史兼容` | 精简对话冗余、历史兼容与前端呈现收敛的前置批 |
| `2854796a` | 2026-11-02 | `perf(ai): 优化对话可靠性与资源成本` | 可靠性、资源成本与配置面调整的前置批 |
| `9546e58a` | 2026-11-03 | `refactor(ai): 移除预估行数并补充压缩演示` | **AI 行数工具下线**；`get_row_count` 从工具注册表与 `AiTools` 移除；精确计数改由 REST / UI 承担 |
| `62b14b12` | 2026-11-03 | `fix(ai): 闭环停止取消并完善对话交互设计` | **停止 / 取消闭环**、超时与交互文案 |
| `7c108dea` | 2026-11-03 | `fix(ai): 继续操作正常展示用户消息` | 继续 / 续跑时用户消息正常展示 |
| `c1abfc40` | 2026-11-03 | `feat(ai): 落地上下文压缩并收敛失败恢复` | **上下文压缩、记忆窗口、失败恢复**主批 |
| `bf098f60` | 2026-11-03 | `docs(ai): 记录租户隔离复核与Redis审查` | 只读复核件入库，不属本报告施工面 |
| `160a9d12` | 2026-11-03 | `fix(ai): 修复刷新恢复与导出表单回归` | **刷新恢复、导出表单回归**、工具注册面 |
| `7147f366` | 2026-11-03 | `fix(ai): 闭环缓存回收与运行资源治理` | **KB 缓存回收、运行资源治理** |
| `e7258b12` | 2026-11-03 | `fix: 收敛线程资源并规范日志输出` | **线程资源收敛、日志规范**、脚本 / 钩子调整 |

**当前工作树（未提交，约 67 改 + 1 新增测试）** 是在上述提交之上的后续整改，主要覆盖：多真源历史补偿、失败终态历史、`create_task` 跨轮续用、条件草案身份绑定、精确计数 REST / UI、KB 代次、C 端提示简化与资源清理。本文以下若写“当前工作树”，均指这一层，不冒充已入库。

---

## 2. 整改内容（按主题）

### 2.1 多真源历史：展示历史、工作上下文、运行快照、浏览器镜像

**问题**：旧 `/history` 直接读被压缩 / 窗口裁切过的 `ChatMemory`，刷新后可能只剩摘要或最近若干轮；`transcript` 非空时又不再回看记忆，写点漏写就永久缺条；首轮空记忆没有 `ready` 标记时，后续写点可能把“含本轮用户提问的 prompt”当旧历史复制，导致刷新后同一提问出现两次。

**本轮做法**：

1. **展示历史与模型工作上下文分离**：`/history` 以 `ai:tx:` transcript 为展示主源；`chat:mem` 只作为**补偿源**，用于补 transcript 缺条和终态事实，不覆盖已有展示原文（`ConversationTranscriptStore.java:287`、`TranscriptEntries.java:265`）。
2. **原始输入单独承载**：`AiChatRequest` 新增 `displayText`；模型与记忆仍用归一化后的 `message`，展示历史用用户原始输入（`AiChatRequest.java:39`、`AiController.java:426`、`ResilientChatService.java:683`）。终态重写用户条目时不会再被归一文本替换。
3. **原始时间与排序**：请求起点随首笔快照落库；用户条目排序键取“请求起点与 `createdAtMs` 的较早者”，时钟回拨或同毫秒时退到 `createdAtMs - 1`，保证终态补写的用户条目仍排在工具卡之前（`RunSnapshot.java:340`）。
4. **首轮基线决策**：空记忆也置 `ready`，避免后续写点把本轮提问当旧历史再复制一份；`memory == null`（读不到）保持未初始化，不伪装成“空历史”（`ConversationTranscriptStore.java:310` 一带）。
5. **按 run 排除当前轮**：`ensureBaselineFromMemory` 用当前 `runId` 排除本轮消息，不再从请求原文猜当前 USER 的位置（`ConversationTranscriptStore.java:287`、`TranscriptEntries.java:247`）。
6. **旧 baseline 收敛**：历史 `baseline:<序号>` 条目只与已存在的 run 条目按 `(role, text, toolCallId, name, toolCalls)` 结构一对一收敛；不同轮合法同文不会因全局文本去重而消失（`TranscriptEntries.java:302` 一带）。
7. **预检展示摘要只增不减**：挂起写点与终态写点共用 `entryId`，读取 last-wins；后写条目若没带预检，沿用先写的 `preflight` 展示摘要，卡头不会从业务标题退回工具名（`TranscriptEntries.java:457`）。白名单只含 `target / impact / conditionSummary / publishMode`，`checks` 与 `reportHash` 不落展示历史（`TranscriptEntries.java:432`）。
8. **失败事实随历史保留**：assistant 条目带 `errorCode / errorMessage`，`/history` 原样透出；空白正文的失败条目也能重建错误卡（`TranscriptEntries.java:44`、`AiController.java:2301`）。

**边界**：多真源仍是“最终一致”而非强一致；补偿条目在缺少 `turnStartedAtMs` 时会落到排序键 `0`，极端排序边界见 §4 `L-159`。真实 Redis / 多实例下的并发补偿未验证。

### 2.2 失败恢复：失败现场进入本轮真实历史

**问题**：失败轮过去只把失败事实放在 Redis 快照与浏览器镜像里，历史通道没有 `errorCode / errorMessage`；刷新后失败轮可能被归一成一条“完整回答”，或只剩半截正文。`/history` 也没有能力把失败事实补回展示历史。

**本轮做法**：

1. `ResilientChatService#failureHistory` 生成“本轮失败消息”：保留已生成正文、思考与错误码 / 错误文案；即使正文为空，也用仅带失败事实的消息落库（`ResilientChatService.java:3616`）。
2. `recordFailureTerminal` 把失败历史同时写入 transcript 与按 run 合并的记忆，不伪造工具结果、不恢复旧的 run 级复杂重试入口（`ResilientChatService.java:3661`）。
3. 控制器在“轮外异常”路径也收敛失败历史：先准备失败消息，再走取消优先守卫写终态，写成功后才 `recordFailureTerminal`（`AiController.java:689` 一带）。
4. 续跑侧对 `PROVIDER_GONE` 等终态同样生成失败历史，并结算未决工具为上游失败，不留下“工具仍在跑”的假象（`ResumeService.java:2690`、`ResumeService.java:2029`）。
5. `StartupResumeRunner` / `SessionPurgeRetryRunner` 每轮使用唯一 owner，避免 release 失败后进程内视图残留导致下一拍 `created=false`、不续租也不释放（`StartupResumeRunner.java` 扫描 owner、`SessionPurgeRetryRunner.java` 轮次 owner）。

**边界**：失败现场仍不新增 DB 写入；Redis 24h 滑动窗口之外不可恢复；浏览器镜像有界，只保证最近一段。真实 Redis 与多实例故障未验证。

### 2.3 上下文压缩与记忆窗口 CAS

**问题**：压缩 / 窗口改写大多改的是“别的 run 的块”，按 run 合并会跳过这些改写，出现“台账说压了、模型仍看原文”；整窗覆盖若不设基线，又会用旧内容覆盖并发新消息。压缩状态、台账、快照与记忆若提交顺序不一致，会留下“状态说已压缩、模型看原文”的分叉。

**本轮做法**：

1. **顺序固定**：请求前先做记忆窗口（清旧工具结果、按 turn 整轮淘汰），再做模型上限占比兜底压缩；落库顺序为“归档 → 记忆 → 快照”，避免“快照已压、记忆未压”的双历史线（`ResilientChatService.java:4680`）。
2. **整窗 CAS**：以本轮起点读到的 `windowSnapshot` 为基线做 `replaceWindow`；期间别的写者改了窗口就**丢弃本次计算结果并重读重算**（最多 5 次，WARN + `W1_MEMORY_WRITE`），绝不用压缩前旧内容覆盖新消息（`ResilientChatService.java:4849`、`:4868`）。
3. **提交顺序**：只有整窗 CAS 成功后才 `contextCompactor.commit(...)`，再发 `context` 帧；CAS 失败时本轮不广播“已压缩”假事实（`ResilientChatService.java:4719` 一带）。
4. **清空推进代次**：清空会话会推进记忆代次，旧写者不能在本轮之后把残窗复活；压缩输入与 CAS 使用同一次 `readWindow` 的副本（`ResilientChatService.java:702`、`:707`）。
5. **展示与工作上下文分离**：压缩只改模型工作上下文，不删用户展示历史；摘要正文不展示，只保留既有 `已压缩上下文` 事件节点语义（见设计件 §4）。
6. **压缩期取消**：压缩期间收到取消，只认确定 `CANCELLED`；保存最小 `CANCELLED` 快照、零模型调用、唯一取消终帧，刷新后可重挂读取（`ResilientChatService.java` 压缩后复查取消段）。

**边界**：CAS 的验证面仅限单进程 / 测试替身 / 代码级检查；真实 Redis、真实 Cluster、多实例并发压缩未验证。压缩后的长期历史加载与分页性能见 §4 `L-156`。

### 2.4 SSE 重连与归档对账

**问题**：客户端若用服务端 `runs/current.archiveMaxSeq` 当 `lastSeq`，会跳过本端从未收到的 `suspended` / `confirm_request` / `frontend_tool_request` 帧，导致挂起卡恢复不出来、无卡前端工具永远不执行；只依赖 pub/sub 唤醒时，最后一条业务帧 / 终帧的 wake 丢失会让订阅者停在半截。

**本轮做法**：

1. **客户端锚点 = 本页签实际已应用的 seq**：`appliedSeqOfRun` 取 `runSeq` 与消息 `appliedSeqs` 的最大值，不用服务端归档最大号；返回 0 表示“本端未知”，调用方不得据此清空段或把旧请求当首次执行（`frontend/src/stores/ai.ts:340` 一带）。
2. **服务端低频归档对账**：非驱动方按各订阅者的 `wakeFloor`（重挂时即客户端 `lastSeq`）每 **30s** 读一次归档；pub/sub 只负责加速，丢了最后一条 wake 也能由对账确定恢复；心跳本身不推进业务游标（`SseChatEmitter.java:521`、`:1131`）。
3. **对账单飞与顺序**：对账在既有有界投递池中单飞；读归档在锁外完成，锁内只取游标与入队；读取期间最小游标若前进，下个窗口重读，避免低号排到高号之后（`SseChatEmitter.java:1163` 一带）。
4. **终帧与容量**：见到终帧后停止对账；超过 live queue 容量则按既有策略 evict，不无限堆积（`SseChatEmitter.java:1212`）。
5. **刷新全量重放的预检补位**：已决确认工具的 `suspended` / `confirm_request` 帧在重放时会被“已决”闸跳过，因此 `tool_result` 结局帧也带预检展示摘要，重建出的卡不丢业务标题 / 条件 / 影响面（`SseChatEmitter.java` 注释、`TranscriptEntries.java:432`）。

**边界**：真实多实例、真实 Redis Cluster、真实网络中断未验证；30s 对账是恢复下界，不是实时性承诺。

### 2.5 工具跨页签互斥与复制页签隔离

**问题**：同一前端工具请求可能同时到达两个页签；两边都执行会重复产生副作用。复制页签会继承 `sessionStorage`（含 `pageId`）与后端会话，旧判据 `navigation.type` 在真实浏览器里对复制页签返回 `back_forward`，无法区分“复制”与“刷新”。

**本轮做法**：

1. **工具级跨页签互斥**：`withFrontendToolFence` 以 `(身份指纹, sessionId, runId, toolCallId)` 为名申请 Web Lock，`ifAvailable: true`；拿不到锁 = 他端持有，本端不执行、只等权威结果；**没有 Web Locks 能力时不得自动执行**，台账只记录结果，不冒充 CAS（`frontend/src/stores/ai.ts:2070` 一带）。
2. **页签内单飞**：同页签同 `toolCallId` 用 `frontendToolsInFlight` 占位；执行 / 回灌完成写 `ai-frontend-tool:` 台账（TTL 24h），恢复时可按 `claimed / completed` 判断是否已执行；无法确认时用中性文案“本页签没有再次执行，请人工核对”，不伪造成功（`frontend/src/stores/ai.ts:1903`、`:1985`）。
3. **复制页签三层握手**：`pageId`（每页签 UUID，存 `sessionStorage`）+ Web Locks 会话锁（`ai-session-<identity>:<sessionId>`，拿到 = 原页签，`null` = 被占用）+ `BroadcastChannel`（`claim` / `released`）。复制页签判为 `duplicate` 后就地轮换 `pageId`、起新会话，不接管原会话（`frontend/src/stores/ai.ts:1088`、`:10888`）。
4. **让渡宽限**：`pagehide` 只广播 `released` 意向、不立即释放锁；空白副本等 `OWNERSHIP_RELEASE_GRACE_MS = 1500ms` 后再探一次锁；慢重载回来的原页签凭一次性“返回意向”（`RETURN_INTENT_KEY`，60s 有效）只降级为 `duplicate`，不清会话、不清屏；bfcache 恢复做有界重认领（`frontend/src/stores/ai.ts:1095`、`:1133`）。
5. **失败方向保守**：Web Locks API 抛错 / reject 一律判 `unsupported`，绝不判 `occupied`；把“问不到”当“确认复制”会误删现场（`frontend/src/stores/ai.ts:1326` 一带）。

**边界**：复制页签由**用户人工验证通过**；无 Web Locks 的旧浏览器按“恢复”兜底，未做兼容矩阵；服务端多实例不在本项范围内。

### 2.6 停止与新会话

**本轮做法**：

1. **停止墓碑**：`stop()` 同步写 `ai-stopped-run:<身份>:<session>:<run>` 到 `localStorage`，作为刷新后“这一轮已停止”的兜底；当前页签镜像仍是第二道兜底。墓碑按会话 / 身份清理，不跨会话误杀新轮（`frontend/src/stores/ai.ts:704`、`:744`）。
2. **start 前停止**：runId 尚未到达时的停止按传输句柄登记“停止意向”，TTL 60s、最多 8 条；该轮 `start` 到达时按**旧身份**补 cancel；换会话后意向转“只取消、不写当前现场”；已消费的传输后续帧不得写入新会话（`frontend/src/stores/ai.ts:533` 一带）。
3. **补取消有界**：`stopTombstoneRecancelled` 最多记 16 个 runId，同 run 只补一次，不轮询、不无限重试，也绝不对别的 runId 发取消（`frontend/src/stores/ai.ts:522`）。
4. **停止与发送同 tick**：发送在“停止落地”前最多等 `STOP_SETTLE_MAX_WAIT_MS = 2000ms`，避免撞上停止尚未传播的残留 409；超时后照常发送，仍由权威忙态判据拦（`frontend/src/stores/ai.ts:512`）。
5. **新会话不继承现场**：`adoptFreshSession` 递增会话现场代次，清镜像、乐观气泡、在途轮、表单草稿、前端工具台账与停止墓碑；会话 id 不复用（`frontend/src/stores/ai.ts:10704`）。
6. **停止轮不出现继续 / 重试**：已标记 `stopped` 的轮不写“断流”事实，也不产生继续 / 重试入口；停止是用户决定，不是断流（`frontend/src/stores/ai.ts:1282` 一带）。

**边界**：停止墓碑与前端工具台账的**长期清理**未做（只随会话销毁 / 切换清理），见 §4 `L-157`。

### 2.7 `create_task` 复用、预检与 `independent` 语义

**问题**：在已有任务页继续表达同一创建意图时，旧逻辑可能重复创建任务；预检也只看新入参，无法展示“这是复用当前任务”。

**本轮做法**：

1. **跨轮续用**：`TaskService#createIdempotent(type, title, runId, continuationTaskId)` 在当前任务满足“ACTIVE + 本身带 AI 创建幂等事实 + 类型与规范化标题一致”时复用槽位任务，不重复创建（`TaskService.java:175`、`:245`、`:294`）。
2. **续跑记录不改写原始键**：续跑 run 记入原任务 `settings_json.aiCreateRuns`，原始 `ai_create_run` / `ai_create_key` 不改；任务行锁让跨实例同槽位续跑串行，同一续跑 run 的第二个不同意图仍由单轮上限拒绝（`TaskService.java:245` 一带）。
3. **预检识别续用**：`ToolPreflightService#inspectCreate` 在 `continuationTask` 命中时把 target 改成当前任务的 `taskId / type / title`，影响面文案为“将复用 … 任务 #N”，`reportHash` 绑定 `continue:type:id:title` 与条件事实，批准即绑定到该续用目标（`ToolPreflightService.java:386`、`:576`）。
4. **`independent` 的准确语义**：`create_task` 新增可选参数 `independent`。**`independent=true` = 不把当前任务当作续用槽位，走独立新建路径，不是复用**；省略或 `false` 且当前任务可续用时才复用（`AiTools.java:375`、`ToolPreflightService.java:576` 的 `independent` 分支）。
5. **同轮幂等仍生效**：即使 `independent=true`，同一轮已创建过任务时仍受单轮上限与幂等约束——同键复用、异键 `CREATE_TASK_CONFLICT`，不会在同一轮创建第二个任务（`TaskService.java:162`、`:175`）。

**边界**：只有“AI 创建、ACTIVE、同类型同规范化标题”的任务可续用；手工任务、不同意图、已终态任务仍走原创建路径。真实多实例行锁竞争未验证。

### 2.8 条件草案的任务绑定与 ABA

**问题**：旧方案用 `toolCallId` 前缀编码 `run + taskId + defCode`，模型自己下发的 `set_condition` 没有任务号，只能靠 `createTaskRequestedAtMs` 时间边界推断归属。时间边界在跨轮、时钟漂移、任务 A 与任务 B 交替、同任务重放时都可能误判（ABA）：新任务继承旧任务的条件收集状态，或旧草案被当成当前任务的事实。

**本轮做法**：

1. **身份三元组绑定**：`PendingToolCall` 新增 `draftRunId / draftTaskId / draftDefCode`；宿主草案与模型草案都在生成 / 回灌时 `bindDraft`，读取时 `isBoundDraft` 精确比对三元组（`PendingToolCall.java:119` 一带、`ConditionsForm.java:161`、`:174`）。
2. **时间边界退役**：`EXTRA_CREATE_TASK_REQUESTED_AT` 标记为 `@Deprecated`，生产代码不得使用；`clearTaskScopedExtra` 清掉候选、`selectedDefs`、`selectionSynced` 等任务作用域事实（`RunContextRefresher.java:96`、`:390`）。
3. **草案事实只认绑定条目**：`draftFacts` 只统计 `defCode` 属于当前任务、且三元组与 `(runId, taskId, defCode)` 完全一致的待决条目；别的任务 / 别的 run 的草案不再计入（`ConditionsForm.java:811`）。
4. **同任务重放保留事实**：`RunContextRefresher` 在同一任务重放 / 跨 run 继续时只替换本次 `create_task` 候选，不重复触发收集；换任务时由 `applyTask` + `clearTaskScopedExtra` 作废旧任务槽位（`RunContextRefresher.java:219` 一带）。
5. **首次进入 QUERY_COND 不抢弹表**：刚成功推进到条件步骤的首次交棒只置 `EXTRA_CONDITION_STRUCTURE_HANDOFF`，把用户已给出的筛选值交给既有 `set_condition` 通道结构化；仍未知时才由后续交棒挂一次非必填条件表（`ConditionsForm.java:257` 一带）。
6. **确认前带正文**：`confirm_step` 提前 park 时把当前 assistant 正文与元数据一路传到 `saveSuspended`，取消 / 刷新 / 续跑能恢复本段正文，而不是读上一轮快照（`ConditionsForm.java:387` 一带）。

**边界**：旧数据里没有三元组的待决条目不会被计入（宁可让用户重收一次，也不串任务），未做历史回填；真实跨实例并发与真实任务落库时序未验证。

### 2.9 知识库缓存代次与下线回收

**问题**：KB 正文键无 TTL，下线只能显式删；旧刷新若在回收前才醒来，可能删掉新实例刚重写的正文；清单与正文若先后暴露，读侧可能看到半份清单。

**本轮做法**：

1. **独立代次键**：新增 `kb:cache:manifest:generation`（INCR），与读侧刷新通知 `:version` 分离，避免提前暴露半份清单（`KbCache.java:53`、`:272`）。
2. **索引值改代次**：`kb:cache:docs` 的 field 值从常量 `"1"` 改为写入代次；`putDocIds / putDocId` 只允许代次单调提升（`KbCache.java` 索引写入段）。
3. **条件写 / 条件删**：正文、索引、清单均带代次闸；`deleteDoc` / `removeDocId` / `putManifest` 只在“当前代次 ≤ 本次代次”时生效，旧刷新不能删除 / 覆盖新刷新的结果（`KbCache.java:129`、`:199`、`:223`）。
4. **同代次贯穿**：`CachedKbSource#syncToCache` 先取代次，再读上一版清单与索引，之后所有正文 / 索引 / 清单写入都使用同一代次；旧回收即使晚醒也让位给更高代次（`CachedKbSource.java:260` 一带）。
5. **读路径惰性补写**：`fetchContent` 的惰性补写也用 `nextGeneration()`，不再写“无代次”的正文（`CachedKbSource.java:113`）。
6. **Cluster 安全**：正文与索引各用一条**单键** Lua 做代次条件删除，不用 `KEYS` / `SCAN` / 跨槽多键 Lua（`RedisKbCacheStore.java:285`）。

**边界**：真实 Redis / Redis Cluster 上的代次并发、KB 运行验证、故障注入均未做（§4 `L-153`、`L-154`）；`docs/知识库维护手册.md` §8A 已在同批补上 `generation` 键族与代次条件写 / 删口径，但文档同步不等于运行验证。

### 2.10 精确计数与 AI 行数工具下线

**问题**：AI 侧的行数工具让模型对“数据量”做读取 / 预估，但模型并不需要为此获得一条独立读取能力；同时旧实现把“预估行数”与“精确计数”混在工具面，容易让模型把估算说成事实。

**本轮做法**：

1. **AI 行数工具下线**：`get_row_count` 从 `AiToolNames` 注册表与 `AiTools` 移除（提交 `9546e58a`）；当前 `AiTools` 不再注入 `ConfigDataService`，AI 侧没有行数读取工具。`tools.yaml` 版本升至 `2026-10-27.23`，注释记录“AI 精确行数工具下线；REST/UI 的独立计数能力不变”。
2. **精确计数改由 REST / UI 承担**：新增 `GET /api/data/{defCode}/count`，前端 `dataApi.countData` 调用；导出向导条件区新增“查询精确行数”按钮与“当前条件精确命中 N 行”展示（`DataController.java:89`、`frontend/src/api/data.ts`、`ExportWizardView.vue:66`）。
3. **口径与导出作业一致**：无条件时统计该定义全部已发布行；有条件时复用 `ConditionEvaluator` 的条件过滤（`ConfigDataService.java:54`、`:59`）。

**边界（重要契约问题）**：`/count` 虽然接受 `scopeType / scopeKey`，但 `ConfigDataService.count` 目前忽略两者、恒按 `countByDefCode` 统计；条件分支的 `DataController` 把 `scopeKey` 传 `null`，`countFiltered` 本身也没有 `scopeType` 入参。因此“带范围参数的精确计数”与列表 / 导出可能不一致，属未修的契约问题（§4 `L-158`）。AI 侧不再有行数工具，但 UI 的计数按钮仍可用。

### 2.11 C 端提示简化

**本轮做法**（中英双 locale 同批）：

1. **去内部术语**：把“幂等拒绝”“上游连接中断”“首帧超时”“挂起等待中”等改成人话；连接类提示统一为“连接中断 / 连接超时”，不再向用户暴露协议阶段（`frontend/src/locales/zh-CN.json`、`en-US.json` 的 `ai.notice.*`、`ai.panel.*`）。
2. **失败与表单可恢复**：表单被拒 / 超限 / 取消未送达时明确“填写内容仍保留，可修改后重新提交 / 重新载入确认状态”，不暗示后端会静默收尾。
3. **挂起期输入说明**：输入区占位改为“可先输入；处理上方待办后本轮会继续，结束后可发送（也可点停止提前结束）”，不再说“请先处理或停止”。
4. **计数与条件文案**：新增“查询精确行数 / 当前条件精确命中 N 行”“筛选条件未决定，创建后可继续设置”等面向 C 端的表达。
5. **不新增用户可见入口**：除计数按钮外，本轮提示简化不新增按钮 / 弹窗 / 新状态位；`工作中…` / `等待中…` / 时间戳 / 已停止 / 已截断等既有规格保留。

**边界**：文案简化不等于交互重构；部分提示（如准备中、压缩过程条）归各自专项，本文不重复。浏览器实测只覆盖核心对话场景，未做逐条文案的视觉回归。

### 2.12 资源与线程收敛

**本轮做法**：

1. **租约墓碑只服务 run 租约**：`LeaseService` 只对带 hash tag / fence 的 run 租约写 `ended` 墓碑；无 tag 的周期资源锁不参与写权限判定，release 异常时条件清除该任本地视图，避免 `held / ended` 随扫描次数累积（`LeaseService.java` ownership check / renew / release 段）。
2. **周期任务唯一 owner**：`StartupResumeRunner` 每次扫描、`SessionPurgeRetryRunner` 每轮使用 `<instanceId>:scan:<n>` / `<instanceId>:purge:<n>`，续租与释放严格对应当前任（`StartupResumeRunner.java`、`SessionPurgeRetryRunner.java`）。
3. **线程与日志**：提交 `e7258b12` 收敛线程资源、规范日志输出，并调整脚本 / 钩子；不改变业务帧与接口。
4. **缓存回收**：`7147f366` 闭环 KB 缓存回收与运行资源治理；代次闸见 §2.9。

**边界**：工具台账、停止墓碑、`recoveryEntriesByRun` 的**长期清理**未做（§4 `L-157`）；purge 故障注入未做（§4 `L-154`）。

---

## 3. 浏览器实测通过的核心场景

> **口径**：以下 10 项为本轮在真实前后端 / 真实浏览器中由主控实际走通的核心场景；**复制页签一项由用户人工验证通过**。判定以“页面可见终态 + 刷新 / 重连后仍成立”为准，不附截图与日志原文（仓外过程材料，按 DC-08 不入库）。**浏览器走查不等于自动化回归，也不等于后端 / 前端全量、E2E、主 CI 通过**。

| # | 场景 | 通过口径（实际观察到的行为） | 验证方式 |
|---|---|---|---|
| 1 | 多真源历史 + 原始输入 / 时间 | 发送后刷新，用户气泡仍是**原始输入**（不是归一化后的模型文本）；时间戳沿用原轮；旧轮不被压缩摘要替换或静默少条 | 浏览器 |
| 2 | 失败恢复 | 构造失败轮后刷新，仍显示失败事实（已生成正文 + 错误卡 / 错误码），不会被归一成一条完整回答；空白正文的失败也能重建错误卡 | 浏览器 |
| 3 | 上下文压缩与展示历史分离 | 触发压缩后，用户可见原文仍在；`已压缩上下文` 事件节点出现在该回复的真实时序位置；刷新后节点按事件 id 去重、位置不变；摘要正文不展示 | 浏览器 |
| 4 | SSE 重连 / 归档对账 | 断流后重连，缺失帧补齐且不重复；挂起卡能恢复；终帧只出现一次；不出现“重连后整段历史被替换” | 浏览器 |
| 5 | 工具跨页签互斥 | 两个页签同时收到同一前端工具请求时，只有一个页签执行并回灌；另一个不重复执行，等待 / 采用权威结果 | 浏览器（双页签） |
| 6 | 复制页签隔离 | 复制页签起**新会话**，不接管原页签会话；原页签继续正常；复制页签不继承原会话消息与在途轮 | **用户人工验证通过** |
| 7 | 停止 / 新会话 | 停止后终态为 `已停止`，不出现继续 / 重试；停止与发送同 tick 不误报“上一轮仍在进行”；新会话不继承旧消息、草稿、停止墓碑 | 浏览器 |
| 8 | `create_task` 复用与 `independent` 新建 | 同轮重复创建复用同一任务；跨轮继续复用当前任务（预检显示“将复用 … 任务 #N”）；**`independent=true` 显示“将创建”，走独立新建，不是复用**；同轮上限仍生效 | 浏览器 |
| 9 | 条件草案任务绑定 | 换任务 / 跨轮后旧任务条件草案不串到新任务；同一任务重复回放不重复弹条件表；旧任务表单回灌不被当成新任务事实 | 浏览器 |
| 10 | 精确计数 | 导出向导点“查询精确行数”，展示“当前条件精确命中 N 行”；AI 对话不再出现行数工具 / 行数预估话术 | 浏览器 + 接口核对 |

**未在浏览器中构造**（不得据本文反推为已验）：真实多实例 / Redis Cluster / KB 运行、purge 故障注入、最终 `start_export` 与真实下载、长历史“加载更早”的性能与稳定性、工具台账 / 停止墓碑 / `recoveryEntriesByRun` 的长期清理。见 §4。

---

## 4. 遗留挂账（逐项，L-153 ~ L-160）

> 以下 8 项已同步登记到 `docs/任务看板.md` 的挂账小节。**未闭合 = 未验证 / 未做 / 已知边界 / 用户明确本轮不做**，不得在后续汇报中写成“已完成”或“全绿”。

| 编号 | 遗留项 | 事实 / 原因 | 影响 / 风险 | 建议下一步 | 状态 |
|---|---|---|---|---|---|
| **L-153** | 真实多实例 / Redis Cluster / KB 运行验证未做 | 本轮 Redis 相关路径（记忆 CAS、KB 代次、租约、SSE 归档）的验证面仅限单进程 / 测试替身 / 代码级检查；未起真实三实例、未连真实 Cluster、未在真实 Redis 上跑 KB 缓存刷新与回收 | 跨槽、版本差集、并发代次、跨实例接管等只在设计 / 单测层面成立；真实环境可能暴露 `CROSSSLOT`、5.0 版本差、租约时序问题 | 在隔离环境起三实例 + 真实 Cluster（含 5.0 兼容面）跑 KB 刷新 / 回收 / 压缩 / 重连；记录 run 与日志摘要 | 未做 |
| **L-154** | purge 故障注入未做 | `SessionPurgeRetryRunner` 的续租、存活判据、owner 清理改动只有静态 / 单点覆盖；未注入 DB 失败、文件删除失败、续租超时、进程中断 | 清理半途失败后的重试收敛、锁 TTL 与 owner 视图行为未实证 | 用既有故障注入设施覆盖 purge 的失败 / 超时 / 中断三类场景；验证不双跑、不永久占锁 | 未做 |
| **L-155** | 最终 `start_export` 与实际下载未做 | 浏览器实测走到计数 / 条件 / 任务创建与复用，未走完“最终启动导出作业 + 实际下载文件” | 导出作业启动、文件生成、下载链路与本轮历史 / 表单改动的交互未验证；不能声称端到端导出通过 | 在真实浏览器完成 `start_export` 确认 → 作业完成 → 下载文件并核对内容 | 未做 |
| **L-156** | 长历史“加载更早” / 服务端稳定分页性能未闭合 | 前端已有 `loadEarlierHistory` 与 `HISTORY_PAGE_SIZE`，但未做长会话（数百轮 / 大工具结果）下的加载耗时、视口稳定、服务端分页一致性压测；服务端稳定分页的极端边界未验证 | 长会话可能出现加载慢、页边界 toolCall / TOOL 配对错位、重复或漏条；性能无基线 | 构造长历史夹具，测首屏 / 每次“加载更早”的耗时与内存；核对页边界配对与服务端分页稳定性 | 未闭合 |
| **L-157** | 工具台账 / 停止墓碑 / `recoveryEntriesByRun` 长期清理未做 | 前端工具台账 `ai-frontend-tool:` 有 24h TTL，但 `ai-stopped-run:` 墓碑与模块级 `recoveryEntriesByRun` 只随会话销毁 / 切换清理；长会话、多 run、多页签下可能累积 | localStorage 体积增长、旧 run 恢复条目驻留、极端情况下旧墓碑影响判断（现有 runId 精确匹配已降低误杀面） | 给停止墓碑加 TTL / 容量上限；`recoveryEntriesByRun` 按 run 终态 / 最近 N 个收敛；补长会话清理用例 | 未做 |
| **L-158** | 计数 API `scopeType` / `scopeKey` 被忽略的契约问题未修 | `/api/data/{defCode}/count` 接受两个参数，但 `ConfigDataService.count` 恒按 `countByDefCode` 统计、忽略 `scopeType / scopeKey`；条件分支的 `DataController` 把 `scopeKey` 传 `null`，`countFiltered` 本身也没有 `scopeType` 入参 | 带范围参数的“精确计数”可能与列表 / 导出范围不一致；前端展示的数字可能被用户当成当前范围的真实行数 | 明确契约：要么按 `scopeType / scopeKey` 收窄，要么在 API / UI 去掉这两个参数并注明“全定义计数”；补范围计数用例 | 未修 |
| **L-159** | 补偿条目的极端排序边界未修 | `reconcileWithMemory` 用 `fromMessages(memory, Map.of(), false, 0L)` 补偿缺条；没有 `turnStartedAtMs` 元数据的消息会得到排序键 `0`，可能排到会话最前；旧 baseline 收敛依赖结构键，极端同文 / 同工具调用仍可能歧义 | 极端情况下补偿条目位置不正确，或旧 baseline 与 run 条目收敛不彻底；正常路径已由展示原文优先与结构配对降低影响 | 为补偿条目保留原始时间（或按相邻 run 推断区间）；补“缺元数据 / 同文多轮 / 页边界”排序用例 | 未修 |
| **L-160** | 全量测试 / CI 验证本轮不做（用户明确） | 本轮用户明确不需要测试代码验证，不跑后端 / 前端全量、E2E、主 CI；以 §3 的浏览器行为证据为准。当前工作树含 67 个已跟踪修改 + 1 个未跟踪测试，但均不作为本轮验收前置 | 不能据本文声称“测试全绿 / CI 通过 / 可发布”；本轮不做是用户明确的范围决定，不是遗漏；若后续发布 / 回归需要，另行立验证批 | 需要发布 / 回归 / 门禁时另立验证批；届时按实际结果回写，不预写全绿 | 本轮不做（用户明确） |

**明确不销记的既有挂账**：`L-149`（五工作流未达成三轮）、`L-150`（三反例未跑）、`L-143`（间歇性 SSE 帧交付竞态未见消失证据）、`L-151`（Q-08 脚本未运行）、`L-152`（AI 全量审视 25 方向待逐项裁决）等**均不因本轮整改销记**；本轮只新增 L-153~L-160。

---

## 5. 验证与门禁（如实）

1. **本笔（文档）**：只改 `docs/`；运行文档门禁 `node scripts/ci/check-docs.mjs`，结果见 §7 回执。该门禁只覆盖文档一致性 / 命名纪律 / 端口 / 版本等，不覆盖代码正确性。
2. **未由本笔执行**：后端编译 / 单测 / 全量、前端 `typecheck` / vitest / build、E2E、主 CI、真实模型、真实 Redis / Cluster、真实多实例。**不得**把本报告或浏览器走查当作上述任一门的通过证据。
3. **浏览器实测**：核心场景 10 项见 §3；复制页签由用户人工验证；未附截图 / 日志原文。浏览器实测是**行为级证据**，不是自动化回归。
4. **测试状态**：本轮用户明确不做全量测试 / CI 验证，以 §3 的浏览器行为证据为准；`L-160` 只登记“本轮不做、后续需要时另立验证批”，**不是本轮待办**，也**不写“全绿”**。
5. **文档同步**：`docs/知识库维护手册.md` §8A 已在同批补齐 `generation` 键族与“正文 / 索引 / 清单均带代次、旧刷新不得删除 / 覆盖更高代次、no-op 轮次也抬代次”的现行口径；不再列为待同步项。

---

## 6. 引用与依据

| 主题 | 依据 |
|---|---|
| 上下文压缩交互契约 | `docs/research/设计-上下文压缩整改与交互-2026-11-03.md` |
| 提示文案与交互问题清单 | `docs/research/审查-AI对话内容提示与交互-2026-11-03.md` |
| 租户 / 用户隔离现状 | `docs/research/复核-租户与用户隔离现状-2026-11-03.md` |
| Redis 锁 / 缓存 / 内存驻留 | `docs/research/审查-Redis锁缓存与内存驻留全链路-2026-11-03.md` |
| 现行需求 / 技术口径 | `docs/02-需求设计文档-v2.2-冻结版.md`、`docs/03-技术方案文档-v2.1.md` |
| 决策与登记 | `docs/adr/DECISION-REGISTER.md`、`docs/决策看板.md` |
| 任务与挂账 | `docs/任务看板.md`（L-153~L-160） |

**代码锚点**（当前工作树；行号会随代码演进漂移，引用时以文件内注释与最新代码为准）：

- 历史 / 补偿：`backend/src/main/java/com/example/configmgr/ai/history/ConversationTranscriptStore.java:287`、`:312`、`TranscriptEntries.java:248`、`:265`、`:288`、`:432`、`:457`
- 原始输入 / 时间：`backend/src/main/java/com/example/configmgr/ai/web/AiChatRequest.java:39`、`AiController.java:426`、`ResilientChatService.java:683`、`RunSnapshot.java:340`
- 失败恢复：`ResilientChatService.java:3616`、`:3661`、`AiController.java:689`、`ResumeService.java:2690`
- 压缩 CAS：`ResilientChatService.java:4680`、`:4849`、`:4868`、`:4719`
- SSE 对账：`SseChatEmitter.java:521`、`:1131`、`frontend/src/stores/ai.ts:324`
- 页签 / 工具互斥：`frontend/src/stores/ai.ts:2070`、`:1088`、`:10888`、`:1133`
- 停止 / 新会话：`frontend/src/stores/ai.ts:510`、`:527`、`:540`、`:704`、`:744`、`:10704`
- `create_task`：`TaskService.java:175`、`:245`、`:294`、`ToolPreflightService.java:386`、`:576`、`AiTools.java:375`
- 条件草案：`PendingToolCall.java:119`、`ConditionsForm.java:161`、`:174`、`:811`、`RunContextRefresher.java:98`、`:239`、`:390`
- KB 代次：`KbCache.java:53`、`:129`、`:199`、`:223`、`:272`、`RedisKbCacheStore.java:285`、`CachedKbSource.java:261`
- 精确计数：`DataController.java:89`、`ConfigDataService.java:54`、`:59`、`frontend/src/api/data.ts`、`ExportWizardView.vue:66`
- 资源：`LeaseService.java`（ownership check / renew / release）、`StartupResumeRunner.java`、`SessionPurgeRetryRunner.java`

---

## 7. 文档门禁回执与文档变更清单

**门禁回执**：

```text
node scripts/ci/check-docs.mjs
  [PASS] 1) nav/policy docs contain no drive-letter absolute paths
  [PASS] 2) tech-stack versions match pom.xml / package.json
  [PASS] 3) files referenced by nav/policy docs exist
  [PASS] 4) port table matches application.yml / vite.config.ts
  [PASS] 5) naming-discipline forbidden terms (full git ls-files set)
  [PASS] 6) .java probes under docs/ are not part of the build
all green: 6/6 assertions passed.
```

> 门禁结果以本笔实际执行时点为准；新增未跟踪文件不在 `git ls-files` 口径内，提交后由提交官复跑。

**本笔改动的文档**（仅 `docs/`）：

| 文件 | 动作 | 说明 |
|---|---|---|
| `docs/evidence/ai-remediation-2026-11-03/README.md` | 新增 | 本报告（整改内容、浏览器实测、遗留 L-153~L-160、依据与回执） |
| `docs/README.md` | 修改 | 证据分区新增本报告入口 |
| `docs/任务看板.md` | 修改 | 新增 `## 11-03` 1 行；挂账新增 L-153~L-160（218 → 226）；追加数据截止注记 |
| `docs/决策看板.md` | 修改 | 追加 2026-11-03 注记：决策行零变化、计数仍 209、挂账 226；不新增 / 不迁移 / 不 supersede 任何决策 |
| `docs/知识库维护手册.md` | 修改 | §8A 键族补 `generation` / `kb:cache:manifest:generation`；下线回收补代次条件写 / 删与 no-op 抬代次口径 |

**未改动**：代码、测试、脚本、构建文件、服务配置；未执行 git add / commit / push；未起停任何服务；未提交仓外 demo。**本报告不构成验收结论**；本轮用户明确不做全量测试 / CI 验证，后续需要发布 / 回归 / 门禁时另立验证批。
