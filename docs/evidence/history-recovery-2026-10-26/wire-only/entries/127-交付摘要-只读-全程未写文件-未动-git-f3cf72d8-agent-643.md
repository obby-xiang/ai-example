> **历史分析 / 过程证据 — 非现行基线，非验收结论**
>
> - 归档：2026-10-26 · `docs/evidence/history-recovery-2026-10-26/wire-only/`
> - 来源：Kimi Code 会话 `<KIMI_SESSION>` / 子 agent `agent-643` 的 wire 末条 assistant 文本（只读流式解析；非原始会话转写）
> - 原始体量：21356 B / 14463 字符 / 142 行；行指纹覆盖率（仓内 + 落盘语料）2.6%
> - 类别：**过程核验**
> - 脱敏：路径 8 / 会话标识 2 / 身份 0 / 密钥 0
> - 正文为该条消息的完整文本，未删段、未改写结论；仅脱敏与行尾空白规整。当时结论不代表当前代码状态。
## 交付摘要（只读，全程未写文件、未动 git）

### 0. 自证实际模型
我的 agent 目录 = `<KIMI_SESSIONS>\<WORKSPACE>\session_<KIMI_SESSION>-8ea7-46b1-839a-2789342566cb\agents\agent-643\wire.jsonl`（按唯一命中的 `turn.prompt` 定身份：该 wire 全文命中 `r148-cleanup` 11 次、含 `素材核验-R148.md`；另 4 个 agent-640/641/642/644 命中 0 次）。

- 首条 `profile.bind`：`{"type":"profile.bind","agentId":"agent-643","modelAlias":"DeepSeek/deepseek-flash","profileName":"coder","thinkingEffort":"high"}`
- 全 wire 统计：`"modelAlias":"DeepSeek/deepseek-flash"` × 6；`"model":"deepseek-flash"` × 5、`"model":"DeepSeek/deepseek-flash"` × 5；`"provider":"openai"` × 5。
- 自证力边界：仅证「harness 请求别名 + provider=openai」，非服务端权重级证据（与素材同口径）。

### 1. 素材清单（`<LOCAL_WORK>\r148-cleanup\`，`ls -la` 结果）
目录下**仅 2 个文件，无扫描脚本输出、无证据 json**：

| 文件 | 大小 | 行数 | 内容 |
|---|---|---|---|
| `素材核验-R148.md` | 8187 B | 69 | 主结论：25 条汇总表 + 对 K3 起卡最关键 3 点 + 模型自证 |
| `素材核验-R148-明细.md` | 32607 B | 370 | 逐条原始取证：git grep 命中清单、行号摘录、锚点漂移表、工作树状态 |

两文件均已在本次读取中全读（`<LOCAL_WORK>\r148-cleanup\素材核验-R148.md:1-69`、`<LOCAL_WORK>\r148-cleanup\素材核验-R148-明细.md:1-370`）。

另按要求读了清单原件 `<LOCAL_WORK>\r137-guard\过度设计21处清单-回取.md`（8187 B，A~E 26 条逐条原文）。

**基线常量（起草卡头必备）**：
- 仓库 `<REPO_ROOT>` @ HEAD `42606518dac1180a8c4606212f652e297da61b6d`
- 审计基线 `45b2c6a`，落后 HEAD **5 个提交**（`git log --oneline 45b2c6a..HEAD | wc -l` = 5）
- 中间提交：`0f46286`（手动刷新批 17 份卡/报告入库）→ `e1b9314`（R-149 issue#7 合并）→ `4260651`（R-149 四报告 + M2:338 闭环注记）→ **`0a60e78`（手动刷新批施工本体，删 `task-events.ts` + 2 spec）**
- 取证口径：一律提交态（`git show HEAD:<path>` / `git grep <pat> HEAD`），未以脏工作树作结论；无写仓，HEAD 恒 `42606518`
- 核验期工作树状态（素材仅记录、未用于结论）：` M` 6 个（`AiTools.java`、`JobService.java`、`TaskService.java`、`stores/workspace.ts`、`scripts/m2t2a-smoke.mjs`、`scripts/verify-e2e.ps1`）+ `??` 4 个新增测试（`AiToolsThrowPathTest.java`、`TaskFinalizedGuardTest.java`、`TaskGoToStepGuardTest.java`、`goto-step-readonly-guard.spec.ts`）——**余棒在途，起草卡需声明施工期工作树持续脏**。

### 2. 25 条逐条状态表

| # | 一句话内容 | 核验分类 | 落点（HEAD 态；"审计期"另注） |
|---|---|---|---|
| A1 | `TaskEventTarget.jobId` 死字段 | **已消失** | HEAD 无文件（审计期 `frontend/src/utils/task-events.ts:37`；整文件 + 2 spec 随 `0a60e78` 删） |
| A2 | `activeCount()`/`activeTaskIds()` 零消费 | **已消失** | 同上，整文件删（审计期 `task-events.ts:60,62,181,185`；`tests/task-events.spec.ts:279-287` 亦删） |
| A3 | `taskEventSubscriptionCount` 可观测计数 | **已消失** | HEAD 全仓仅 docs 4 处命中，`frontend/src`+`tests` 零命中（审计期 `stores/task.ts:135`） |
| A4 | `isPaused()` 零消费 | **已消失** | 实现已删；`frontend/src/utils/polling.ts:6`（注释列名）+ `frontend/tests/polling-controller.spec.ts:7/106/110/112`（反向锁 `toBeUndefined()`/`'isPaused' in controller === false`）；polling.ts HEAD 248 行 |
| A5 | `isSuspendedFrame()` 零调用 | **直删安全** | `frontend/src/types/sse.ts:299-303`（逐字命中）；全树仅定义 1 处；经 `types/index.ts export * from './sse'` 入公开桶 |
| A6 | `REASONING_FRAME_TYPE`/`SseReasoningFrame` | **存疑待会审** | `types/sse.ts:79-80,242,280,314-315`；消费者 `stores/ai.ts:1701-1705`、`AiPanel.vue:187/266/275`、`stores/ai.ts:264-265/284/297/746/1579-1597`；后端 0 |
| A7 | `TaskChangedPayload` 四字段 | **已消失** | HEAD `types/sse.ts:337` 只剩 `HEARTBEAT|JOB_PROGRESS|JOB_DONE`；`:340 JobDonePayload`、`:346 JobProgressPayload`、`:359 data:`；后端仅 `TaskSseServiceTest.java:161` 注释；`types/api.ts:126` 注释 |
| B1 | `AiPoolCapacityBudget.java`（109 行独立类，为一个启动期 WARN） | **需连带** | 生产 3 点：`AiProperties.java:64`、**`AiController.java:689`**（+`:4` import、`:678` javadoc）；测试 3 文件：`AiPoolCapacityBudgetTest.java`（26/34/39/44/48/55/56/62/70/72/82/97/100）、`AiPropertiesT35DefaultsTest.java:41/46`、`AiControllerHealthSseDeliveryTest.java:49`；类内 `:96-108` 反射回落（`:95-107`）；docs `03-技术方案文档-v2.1.md:675`、`M2-T3-5-ProbeMatrix.java:450` |
| B2 | `JobWatcherRegistry` 注册表 | **需连带** | HEAD 漂移后 `frontend/src/utils/job-watch.ts:263-313`（`:263` interface、`:276` 工厂、`:277 byKind`、`:278 registered`）；消费者 `ImportWizardView.vue:236/314/853/859/1128`（4 锚全中）；`ExportWizardView.vue:234/490-491/724-729/1356-1357`（裸 `stopWatchJob` 未用）；测试 `tests/import-watch-recycle.spec.ts:11/23/55/58/158` |
| B3 | 测试专用"直执"第二套装配 | **需连带**（`budgeted()`/`direct()` 不可删） | `RunRegistry.java:62-64`（两参构造，漂移 −1）、`:66-81`（三参 `@Autowired`）、注释 `:55-58`；`SseWriteBudget.java:114-117/118-120 direct()`、`:146-148 budgeted()`；两参构造调用 **12 点/6 测试文件**（`ChatTurnFrameSequenceConformanceTest.java:73`、`SessionBusyFrameIsolationConformanceTest.java:68`、`ThreeArgAssemblyConformanceTest.java:70/90/109/120/129/146`、`ToolFrameSequenceConformanceTest.java:87`、`EmptyReplyTerminalTest.java:74`、`SseChatEmitterDeliveryTest.java:456/483`）；`budgeted()` 生产 `SseChatEmitter.java:906` + 测试 `SseWriteBudgetTest.java:143` |
| B4 | 4 个注入项（3 项零生产消费 + `lastSeq` 在用） | **直删安全**（3 项）/ `lastSeq` 不可动 | `frontend/src/api/sse.ts:73-85`（`:74` interface、`:76 signal`、`:77/78/79`、`:84 lastSeq`、`:80-83` reattach 注释）；内部回落 `:332/396/432/434`；常量 `:22/:25/:28/:43`；测试 `tests/sse-watchdog.spec.ts:6/40/42/47/65`；`lastSeq` 生产 `stores/ai.ts:934/1167`（另 `:886/893/895/897/1119/1127/1129`、`api/sse.ts:551`） |
| C1 | 一条 AI 流叠 6 层终止机制 | **存疑待会审** | `api/sse.ts:22(90s)/43(15s)/506-521/540`；`StreamWatchdog.java:131/143/149`；`ResilientChatService.java:96/311-317/372-382`；yml `:140 first-byte 30s/:141 inter-event 90s/:142 max-attempts 2/:143 total-budget 300s` |
| C2 | 作业进度 5 类触发源 → 同一 `GET /api/jobs/{id}` | **存疑待会审** | HEAD 形态变化：`job-watch.ts:25` 自述「收敛为三类…realtime 30s 保底已删」；漂移后 `:38 JOB_FALLBACK_POLL_MS=3000`、`:44 FRAME_IDLE_WATCHDOG_MS=90_000`、`:118-131`、`:151 resetWatchdog()`、`:165-178`；`resetWatchdog:125-131`、`poller:139-142`、`start:143`、`:154-160`、`:172-177`、`disposed:148-150`；守卫已交 `polling.ts:110-114`；测试 `tests/job-watch-channel.spec.ts` |
| C3 | 任务列表新鲜度 8 层 | **已消失**（主锚点失效）；`CATCHUP_JITTER_MS` 不可删 | `task.ts:308-327` 现为 `cancelJob/loadIssues`（与 `startAutoRefresh` 无关）；`startAutoRefresh/stopAutoRefresh/autoRefreshController` src 零命中；`task.ts:8` 自述唯一入口 `refreshNow()`、`:186/:220/:280`、`refreshPending :65/174/177/184-188`；`polling.ts:4-6` 自述、`shouldRun/desiredIntervalMs/resync/isPaused` 全删；**`CATCHUP_JITTER_MS` 存活（`:26` 定义、`:165` 使用）**；消费方 `job-watch.ts:35/139`、`AiPanel.vue:432/462/847` |
| C4 | 两条"判死写 FAILED"路径 + hold/release 豁免 | **存疑待会审** | `JobWatchdog.java:160-184`（`:160 scan()` 包级可见、`:161`、`:165-166`、`:168-170` 幂等、`:172-178`、`:179-182`）；`StaleJobRecoveryRunner.java:44-73`（`:47-49/54-57/59-66/68/69-72`）；hold/release 4 处全中：`ExportJobRunner.java:58/130/177`、`PublishJobRunner.java:79/88/98`；注入 `JobProgressService.java:48/53`、`JobWatchdog.java:34/69/95/102`；测试 `JobWatchdogTest.java`、`AppPropertiesJobWatchdogValidationTest.java` |
| C5 | 取消双路（Redis 轮询 + 进程内监听） | **存疑待会审** | `CancellationRegistry.java:20-31`（类 javadoc「ADR-8 修正③：Redis 为准，直通知仅加速」）+ `:33-35` 降级；`StreamWatchdog.java:83-85`（`cancelListener`）、`:125-128`（CAS 单次生效） |
| C6 | 前端工具超时三层串联 | **存疑待会审** | `AiProperties.java:153-158/160`(120s)；`frontend-tools.ts:206-212`（+`:203-204` 实测 120,004/120,007ms）；`api/http.ts:103 HTTP_TIMEOUT_MS=60_000`、`:105-108`（清单引 :107 命中）、`:98-101` 另指 2 个独立常量（`SpreadGrid.vue READY_TIMEOUT_MS`、`stores/workspace.ts DOWNLOAD_FETCH_TIMEOUT_MS`）；注释内锚 `http.ts:85` 已过期 |
| D2 | 两通道引入三套线程池/队列/预算 | **存疑待会审** | `SseWriteBudget.java` 274 行/13602 B、`:130-143 sharedElasticWriterPool()`；`RunRegistry.java:71-74`（`:72-73` 命中）、`:76`、`:77-80`、`:85-88`；测试 `SseWriteBudgetTest.java`、`SseChatEmitterWriteBudgetTest.java`、`SseChatEmitterDeliveryTest.java`、`ThreeArgAssemblyConformanceTest.java` |
| D3 | 四文件四分落盘-回放-续跑-重挂面 | **存疑待会审** | 行数字节全中：`ai/web/AiController.java` 782/40120、`ai/run/RunStore.java` 792/32547、`ai/run/SseChatEmitter.java` 1226/58907、`ai/run/ResilientChatService.java` 788/39374；证据 `docs/evidence/S42-P2-附件B-RunStore与台账原文.md` |
| D4 | `scripts/verify-e2e.ps1` 巨型脚本 | **存疑待会审** | 文件 `scripts/verify-e2e.ps1`：2357 行 / 155,689 B / `^function ` 40 / `TC[ -]?[0-9]` 166 / `Ok\|No` 70 / `Gf-` 60 / `Q8-` 9（工作树该文件为 ` M`，数字取 HEAD 口径） |
| E1 | `delivery-pool-size`/`delivery-queue-capacity` 显式写缺省值 | **直删安全**（可删但可选保留） | `application.yml:188/189`；自注 `:174-176`；同值源 `AiProperties.java:280`(8)/`:295`(256) |
| E2 | `hikari.maximum-pool-size: 10` 显式写框架默认 | **直删安全**（同上） | `application.yml:34`；自注 `:32-33`、连带面 `:28-31`；= HikariCP 官方默认 10 |
| E3 | 两处 `write-timeout: 15s` 一语义两键 | **存疑待会审** | `application.yml:190`(app.ai.sse)/`:202`(app.task.sse)；同源 `AiProperties.java:326`；两处 fail-fast + javadoc yml `:180-182`/`:194-196` |
| E4 | 三处"常量 + 同名配置键"对偶 | **存疑待会审** | `AiProperties.java:295`(javadoc `:283-294`)、`:326`、`:370`(javadoc `:369`)；读取侧三元 `RunRegistry.java:85-88` |
| E5 | `resume.on-startup` 口径分歧（登记项） | **存疑待会审** | `application.yml:147 on-startup: true`（注释 `:145-146`）vs `AiProperties.java:211 = false`（javadoc `:205-210`） |

**汇总（口径校验 6+4+3+12=25 ✓，D1 除外）**
- 已消失（无需动作）**6**：A1 A2 A3 A4 A7 C3
- 直删安全 **4**：A5、B4（3 项；`lastSeq` 不可动）、E1、E2
- 需连带删测试/替换 **3**：B1 B2 B3
- 存疑待会审 **12**：A6 C1 C2 C4 C5 C6 D2 D3 D4 E3 E4 E5

### 3. 存疑 12 条逐条疑点（会审重点，全量）

1. **A6**：审计称「无渲染消费」**失准**。前端已建完整消费链（`stores/ai.ts:1701-1705` 主 `onFrame` switch 的 `case 'reasoning'` + `AiPanel.vue:187/266/275` 折叠块与 `data-testid`）；缺的是**后端生产者**（`git grep '"reasoning"' HEAD -- backend/src` **零命中**）。属「消费者已建、生产者未接」的**前向兼容**（裁决⑦）；删则须同步回滚 store 分支 + 面板渲染 + `parts` 段语义 + 裁决⑦协议记录 ⇒ 不是死码。
2. **C1**：6 层跨前后端互锁；**前端 90s 与后端 inter-event 90s 同值护同一"静默"面**；**总预算 300s 表达两次**（`StreamWatchdog:131` Redis 轮询判 vs `awaitSettled:374` 墙钟兜底）。属语义/架构裁决：前端 90s 可删改由后端终帧收口；`awaitSettled` 与看门狗「二选一」需裁决。
3. **C2**：形态已部分收敛——`realtime 30s 保底已删`，清单建议的「保留 SSE + 3s 兜底」**已是现状**；剩余 3 源（SSE 帧 / onopen 一发 / 3s 兜底表）是否再收敛属取舍；另 `inflight` 自建守卫已交 `polling.ts` 的 `running`/`dirty` 收口。
4. **C4**：两条判死路径动作同构（issue→FAILED→cancel，启动期版额外归一 JobItem）；合并为共用判死方法**须保启动期语义、幂等与 JobItem 归一行为不变**。
5. **C5**：「轮询可降频」属性能调参可做；但**删轮询即失跨进程取消能力**（javadoc 明示跨进程时监听者为空、靠两处轮询发现）⇒ 语义损失，不可删。
6. **C6**：属**数值规格面**而非纯冗余（服务端 120s − 回灌 POST 60s = 前端 60s，三层互相折算）；改动需 **≥5 处同批**（3 主 + `SpreadGrid.vue READY_TIMEOUT_MS` + `workspace.ts DOWNLOAD_FETCH_TIMEOUT_MS`）+ 专项裁决；`AiProperties:153-158` 已明示「**本次不动**」。
7. **D2**：架构级；三套池/队列/预算的价值论证**全押在"客户端不读也不断开"这一罕见态**，需**实测背压证据**方能裁剪。
8. **D3**：属**模块边界重构**非死码；四文件互引，动任一端点须同时理解四处。
9. **D4**：工程债/可维护性面；收敛需分批且**不能破坏 166 TC 语义**。
10. **E3**：两键分属**两条 SSE 通道的独立配置类与独立启动校验**，合并会破坏分通道 fail-fast 姿态。
11. **E4**：三处对偶是**为"非 Spring 装配路径"（两参构造 / 单测）服务的有意设计**，删除须先论证测试装配可改（B3 已证 `direct()` 路径仍在使用）。
12. **E5**：处置**二选一**——①保留显式 `true` 并接受「缺省≠生效值」；②删 yml 行并把代码缺省改 `true`（**会改变"未配置环境"的行为，须评估**）。

### 4. B1 / B3 / B4-lastSeq 前提失准的具体原因（素材原文）

素材主文件「对 K3 起卡最关键的 3 点」第 2 条原文：

> **审计 3 处「唯一/仅测试」前提在 HEAD 失准**：
> - B1「唯一调用方 `AiProperties:64`」→ 实有 **`AiController:689`**（`/health` 键契约）；
> - B3「`budgeted()` 仅测试读」→ 实有 **`SseChatEmitter.java:906`（生产）**；
> - B4「其余仅测试使用」→ **`lastSeq` 有生产消费**（`stores/ai.ts:934/1167`）。
> 按原前提直删会**打红生产**，此三条必须重估。

逐条展开（明细原文要点）：
- **B1**：清单称「唯一调用方 `AiProperties.java:64` 一行」；HEAD 实际另有 `AiController.java:4` import + **`:689`** `snapshot.put("boundedElasticCapacity", AiPoolCapacityBudget.boundedElasticCapacity())`（清单遗漏的第二处生产调用），且 `AiControllerHealthSseDeliveryTest.java:49` **锁 HTTP JSON 契约** `$.sseDelivery.boundedElasticCapacity`；`docs/03-技术方案文档-v2.1.md:675` 要求 `/api/ai/health` 的 `sseDelivery` 含 `queueCapacity`/`boundedElasticCapacity`/`heartbeatIntervalMs` 三键（GLM 复核称「七键齐」）。⇒ 引用面实为 **3 生产点 + 3 测试文件**。
- **B3**：清单称「`budgeted()` 仅测试读」；HEAD 实际 **`SseChatEmitter.java:906`** `if (!this.writeBudget.budgeted()) {` 为**生产路径**；仅两参构造收敛为测试面（12 调用点/6 文件，**生产 0**）。⇒ **`budgeted()` 与 `direct()` 不得按"测试专用"处理**（`direct()` 被非 Spring 装配路径经 `RunRegistry` 两参构造使用，与 E4 关联）。
- **B4-lastSeq**：清单称「其余仅测试 `sse-watchdog.spec.ts:47,65`」；HEAD 实际 `lastSeq` 有生产消费 `stores/ai.ts:934/1167`（另 `:886/893/895/897/1119/1127/1129` 记账文案、`api/sse.ts:551 since = state.lastSeq ?? options.lastSeq`）。可直删者仅 `idleTimeoutMs`/`maxReconnects`/`reconnectDelayMs` 三项（需改 `sse-watchdog.spec.ts`），**`lastSeq` 不可动**。

### 5. A6 非死码的证据描述（原文）

> **消费者（清单「无渲染消费」失准）**：`git grep -n 'reasoning' HEAD -- frontend/src` → `stores/ai.ts:1701-1705`：
> ```ts
> case 'reasoning': {
>   // 裁决⑦：有 reasoning 帧才渲染思考链（无帧则 reasoning 恒空 → 面板不显示折叠块）
>   message.reasoning += frame.text
>   applyFrameToParts(message, frame)
>   break
> }
> ```
> （位于主 `onFrame` switch 内，紧邻 `case 'delta'`）
> - `components/AiPanel/AiPanel.vue:187 / 266 / 275`（`v-if="part.kind === 'reasoning'"` 渲染折叠块 + `data-testid="reasoning-toggle-*"/"reasoning-body-*"`）
> - `stores/ai.ts:264-265 / 284 / 297 / 746 / 1579-1597`（投影与初始化）
>
> **生产者**：`git grep -n '"reasoning"' HEAD -- backend/src` → **零命中** ⇒ 后端确实不产 reasoning 帧（与清单一致）。
> **结论**：属「**消费者已建、生产者未接**」的前向兼容面（裁决⑦），**不是死码**。

补充锚点：`types/sse.ts:79-80` 注释自述「前向兼容的思考链帧名（裁决⑦；后端当前不产出，故不在上面的实测白名单里）」；`:280` 联合类型成员 `| SseReasoningFrame`；`:314-315` `toSseFrame` 白名单放行。删除波及面：`stores/ai.ts` 分支 + `AiPanel.vue` 渲染 + `parts` 段语义 + 裁决⑦协议记录。

### 6. 批次切分建议 / 风险项 / 依赖关系

**批次切分建议**
- 清单原文**「建议优先拆除 Top 5」**（wire :449 末节）：①任务中心 SSE 全链（顺带清 A1~A4、A7）②`polling.ts` 降单节奏 ③AI 流静默/预算双层去重 ④`AiPoolCapacityBudget` 折回 + 回收 B3/B4 ⑤作业进度触发源收敛 + 判死两路径合并。**注：其中 ① ② 已由 `0a60e78` 落地，Top5 已部分作废。**
- 前批决策卡 `<LOCAL_WORK>\r137-guard\决策卡-手动刷新批-定稿.md:13`（M6）已给小卡立项 scope 原文：
> **立项 R-148 = 过度设计清理批**（AI 流双层看门狗去重 C1、AiPoolCapacityBudget 折回 B1、JobWatchdog 双路径合并 C4、配置膨胀族 E1~E5、B3/B4 测试专用面回收），**串行序排队**。
- 素材主文件结论第 1 条（**最重要的起卡约束**）：
> **清单已部分过期**：A 族 5/7 + C3 共 **6 条在 HEAD 已不存在**（`0a60e78` 手动刷新批所为）。清单产自 `45b2c6a`，落后 5 个提交 ⇒ **决策卡不得按 26 条全量立卡**。

**风险项**
- 审计 3 处「唯一/仅测试」前提失准（B1/B3/B4-lastSeq），**按原前提直删会打红生产**，此三条必须重估（见 §4）。
- 锚点漂移汇总（4 条）：B2（220-272→263-313，+43）、B3（63-66→62-64，−1）、C2（四段漂移 +10/+8/尾界收缩/+1）、B4（无漂移，但注释内锚点过期，如 `frontend-tools.ts` 注释引 `http.ts:85` 实为 `:103`）；锚点整体失效 2 条：A1、A2（文件已删）；主锚点失效：C3（`task.ts:308-327` 语义已换）。
- 措辞-代码轻微不符（需会审留意）：`polling.ts:6` 把 `CATCHUP_JITTER_MS` 列于「删除」句，但常量仍存在（仅「不再导出」成立）——**有活消费者（`:165`）⇒ 不可删**，清单 C3 该子项建议已落空。
- 现状口径警告：核验期工作树脏（6 改动 + 4 未跟踪，含 `scripts/verify-e2e.ps1` 被余棒在改，D4 数字为 HEAD 提交态口径）。

**依赖关系（删面互相牵连，起草卡需按组处理）**
- B1 → `/health` 七键契约：删类须同步改 `AiController:689`、删/改 3 个测试、更新技术方案与复核证据文档。
- B3 → `budgeted()` 被生产 `SseChatEmitter:906` 使用（不可删）；`direct()` 被非 Spring 装配路径使用（与 E4 耦合）；可收敛的仅两参构造（连带 6 测试文件 12 调用点）。
- B4 → `lastSeq` 不可动；只可删 3 项注入（连改 `sse-watchdog.spec.ts`）。
- C3 → `CATCHUP_JITTER_MS` 有 2 个活消费方（`job-watch.ts:139`、`AiPanel.vue:847`）⇒ 不可删；`polling.ts` 瘦身已落。
- C1 ↔ C5 同属链路互锁（同一条 AI 流的终止/取消面）；C1 内部 `awaitSettled` 与看门狗「二选一」。
- C6 → 前端工具超时需 ≥5 处同批 + 专项裁决（`AiProperties:158` 明示本次不动）。
- E3 ↔ E4 ↔ B3 共同指向「分通道 fail-fast + 非 Spring 装配第二路径」这一有意设计面。
- E1/E2 为「可删但可选保留」（唯一损失是 yml 注释的"口径可见"文档价值）。

**素材未给落点的条目**：无（25 条全部有 HEAD 态或审计期落点，已在上表逐条抄录）。

**结尾说明**：本次为只读任务，未写任何文件、未执行任何 git 写操作（仅用 `git` 只读类命令的证据来自素材本身，我本人运行的是 `Read`/`ls`/`grep` 读取）。r148-cleanup 目录无扫描脚本输出或证据 json，全部输入即上述 2 个 md + 清单原件；另有 1 条可作起草参考的前批先例文件（`决策卡-手动刷新批-定稿.md`、`复核报告-手动刷新批.md`），已一并摘录其与本批相关的 M6 立项 scope 与已落地事实。
