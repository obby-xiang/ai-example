# AI 交互整改 · 批次 0–9 证据索引（2026-10-26）

> **性质**：**证据索引 / 台账**，不是验收报告，也不是施工完成记录。本件只回答三件事——**每批落在哪个真实 commit**、**实际跑过哪些单点**、**哪些没跑**。
> **基线**：**整改最新代码 HEAD = `9a2f01c`**（批次 9 提交，含文档回写与工程门禁类验收）；整改功能施工的最后一个代码批 = **`b74641c`**（`fix: 收敛AI会话时序与恢复竞态`，批次 8）；批次 0 所依据的文档面提交 = **`e4d4d55`**；整改前产品 / 测试代码基线 = **`61de820`**。四者**不得混用**。
> **口径来源**：`docs/research/裁决-AI交互整改实施口径-2026-10-26.md`（`AR-01`…`AR-17`）与 `docs/superpowers/plans/2026-10-26-ai-interaction-remediation.md`（Task 0–13）。本件与二者冲突时以裁决件为准。
> **本件编写纪律**（批次 9 / 文档批）：**只读代码与 git 历史**；**未起停服务**、**未做 git 写**、**未触发任何 workflow**。**例外（已实跑并逐条登记在 §6.1）** = 批次 9 的 6 项验收：后端编译、`ErrorCodesContractTest#frontendMirrorMatchesBackendCodes` 1/1、前端 `npm run build`、`check-docs` 6/6、i18n 静态检查、`workspace-context-task-status` 1/1。除这 6 项外，件内"实际跑过"一律指各施工批**自己记录过**的单点，**不是本件重跑**的结果；后端 / 前端全量与 E2E **本件未跑**。

---

## 0. 怎么读本件（三条硬规则）

1. **"有 commit" ≠ "已验证"**：批次 0–8 的代码都已入库，但每批实际跑过的多数只有 **1–5 个单点方法**（下称"证据下界"）。**单点绿不构成全批覆盖**，更不构成"全量绿 / CI 绿 / 项目验收完成"。
2. **"有单点" ≠ "可上线"**：真实 PostgreSQL、生产 Redis Cluster 5.0.14、真实 Lettuce 中断、真实三实例、真实浏览器 profile、`@real` 真模型、五工作流**均未在本轮验证**（见 §3）。裁决与代码成立**都不能替代**这些验证。
3. **未跑项必须连读**：每条"未跑"都是该批结论的**适用边界**。只引用"交付"不引用"未跑"，等于把未验证写成已验证。

---

## 1. 批次总表（0–9）

| 批次 | 对应 Task | 真实 commit | 变更规模 | 一句话范围 |
|---|---|---|---|---|
| 0 | Task 0 | **`0ef453b`** | 6 files / +1171 −24 | 合并审查落库、`AR-01`…`AR-15` 口径裁决件、施工计划（纯文档） |
| 1 | Task 1 + Task 2（含 1D / `AR-16`） | **`d704e8c`** | 26 files / +2279 −208 | 条件语义与导出 / 导入 / 筛选 / 建作业守卫的数据真相（1A–1E） |
| 2 | Task 4 + Task 3 部分 + `AR-17` | **`0ede5cc`** | 40 files / +4659 −276 | 取消优先守卫、`unconfirmed`、仅模型步重跑、「继续」、合成提示、恢复缺口；普通 regenerate 隐藏 |
| 3 | Task 5 + Task 6 Step 6 + `AR-11` | **`c6734b7`** | 37 files / +3288 −151 | 首轮 `start` 前移、压缩期独立保活、分阶段超时、`summary-model` 退役、24h 到期可见化 |
| 4 | Task 6（3–5）+ Task 7 + Task 9 | **`55e6078`** | 61 files / +3209 −227 | 预检语义与知情三字段、`taskStatus` 收窄、scope 不变量、`create_task` 进确认门、表单排队 |
| 5 | Task 8 | **`9ac28d0`** | 27 files / +1870 −163 | 页面声明与复位、动作串行、权威任务类型、`start_publish` 绑定、`AR-08` 受限刷新 |
| 6 | Task 10 + Task 3 Step 4 | **`54ca6ad`** | 29 files / +1744 −114 | 思考真实计时、`log` 可访问性、空态三载体、复制页签 / 清场 / 陈旧卡 / 孤儿草稿、`N8`/`N9` 摘要 |
| 7 | Task 11 | **`a373663`** | 15 files / +2354 −249 | 观察器迁移、`mdCache` LRU、`v-memo`、ToolPart 缓存、镜像增量有界、降级提示、fence key、mermaid idle |
| 8 | Task 12 | **`b74641c`** | 14 files / +2251 −114 | `Q-08` / `Q-05` / `D-08` / `M-07` / `TC-18`、确定性 409、运行期死轮扫描、`D7` 单点、`L-143`、`L-150` 三格用例 |

- **提交链**：`e4d4d55 → 0ef453b → d704e8c → 0ede5cc → c6734b7 → 55e6078 → 9ac28d0 → 54ca6ad → a373663 → b74641c → 9a2f01c`（`git rev-parse <sha>^` 逐条核对为线性父链；末位 `9a2f01c` = 批次 9）。
- **状态字段说明**：`docs/任务看板.md` 中批次 8 行原写"工作区，未入库 / 📤"，那是**提交前**的记录事实；本件按 `git log` 实测定为 **已入库 `b74641c`**，且**批次 9 已把该行回写为 ✅**（看板当前段同步，历史行原文不回改）。
- **批次 9**（文档批 + 工程门禁类验收）**已入库并推送 `9a2f01c`**；范围、实跑验收与未验边界见 §6（含 §6.1 验收表）。

---

## 2. 逐批台账

> 格式统一为：**改动摘要** / **实际单点证据（仅下界）** / **未跑与未做（如实）**。
> "RED → GREEN" = 先写红测确认失败、再最小实现转绿，**各一次**；"单点 GREEN" = 只记录一次通过；"多次修正" = 断言或实现往返调整过，**不得读作一次通过**。

### 批次 0 · `0ef453b`（Task 0，纯文档）

- **改动摘要**：新增裁决件（`AR-01`…`AR-15` 逐条"裁决 / 理由 / 代价 / 影响任务"）；计划中"待裁 / 未裁不做"字样全部改写为指向 `AR-xx`；两份看板当前段与 `docs/research/README.md` 索引同步。
- **实际单点证据**：**无**（本批零代码、零测试）。
- **未跑与未做**：`check-docs` 未跑（Task 0 Step 3 的文档门禁在该批未执行）；后端 / 前端任何测试与构建均未跑；未起停服务；未做 git 写（提交由后续批次同一笔完成）。

### 批次 1 · `d704e8c`（Task 1 + Task 2，1A–1E + `AR-16`）

- **改动摘要**：
  - **1A**：`WizardActionGuard` 硬闸拒绝 `EQ ""` / 空白 / `IN []` / 重复 `fieldCode`（`EMPTY`/`NOT_EMPTY` 仍合法）；草案未被用户修改时不得用 `{}` 覆盖 AI 下发条件；"非法空输入 ≠ 显式清空"可区分。
  - **1B**：下载前逐码确保载入、失败回落服务端字节，任一失败整批显式失败；保存校验失败计失败且不清脏；作业 / 文件清单读取失败 fail-closed；重导出前判脏并保留旧结果标代次。
  - **1C**：勾选 / 上传 / 换任务重载前判脏并 flush；离页守卫在导航真正发生前结算；取消勾选含未保存编辑先写回；旧代次结果晚到不得画进新任务。
  - **1D**：筛选控件值为草稿、`commitFilterDraft()` 在查询 / 翻页 / 刷新时提交为已应用值；`JobService.assertNoRunnerInFlight` **只探测 `PENDING`/`RUNNING`**（`AR-16`：终态作业零 Redis 探测；task 行锁、Redis 读失败 fail-closed 409、被拒不落 PENDING 行均不变）。
  - **1E**：空态 chips 与 composer 共用可用性闸；notice 自带 severity、关键错误归错误卡、不新增全局队列；预检阻断态删"可直接继续"误导文案。
- **实际单点证据（5 个）**：
  1. 后端 `WizardActionGuardTest#rejectsBlankValuesEmptyInAndRepeatedFieldCodes` —— **RED → GREEN**；
  2. 前端 `tests/task4-export-condition-fidelity.spec.ts` 的「OTHER 标签载入失败 ⇒ 回落服务端字节进 zip」**1 个方法** —— RED 时包内实际是 `EMPTY-WORKBOOK`（静默空包），GREEN 通过；
  3. 前端 `tests/task4-import-file-fidelity.spec.ts` 的「1C-⑤ 取消勾选含未保存编辑 ⇒ 先写回；写回失败且用户取消 ⇒ 恢复勾选、编辑不丢」**1 个方法**；
  4. 前端 `tests/task-center-view.spec.ts#filter-draft-and-applied-source` —— RED 时翻页请求缺 `status`，GREEN 通过（`1 passed / 8 skipped`）；
  5. 前端 `tests/ai-followup-suggestions.spec.ts` 的「U3 空态 chips 与 composer 共用可用性闸」**1 个方法**。
  - **非测试构建项（不当测试证据）**：`npm run typecheck` 退出 0；后端 IDEA 内置 Maven `-o test-compile` 退出 0（**未 clean**）。
- **未跑与未做**：上述 5 点之外的其余新增 / 既有用例；后端定向套件（含 `RunnerActiveMultiInstanceTest` 三例）与后端全量；前端全量；`npm run build`；主 CI；五工作流；`@real`；真实三实例 / PG / Redis 5.0 Cluster；**未跑 `check-docs`**。

### 批次 2 · `0ede5cc`（Task 4 + Task 3 部分 + `AR-17`）

- **改动摘要**：
  - **取消优先守卫**：`RunStore` 把守卫从"非取消终态"扩到**所有非取消状态**，新增可判定 `saveStatusUnlessCancelled`；`ResumeService.retryModelStepInternal` 改用该守卫（不用普通 save），被拒后复读快照区分"取消 / 丢租"，结局 = `ALREADY_CANCELLED`(200) / `LEASE_HELD_ELSEWHERE`(409) / `STATE_UNKNOWN`(503)。
  - **`unconfirmed`**：`SseChatEmitter.error` additive 重载 + `emitUnconfirmedTerminal`，`turn_end=unconfirmed`、`error.unconfirmed=true`（code 仍为真实故障码），前端落**中性"状态未确认"**视图，不新增终帧。
  - **仅模型步重跑**：`POST /api/ai/runs/{runId}/retry-model-step`；前端 `retryModelStep` 用 `message.runId` 优先、只有当前在途宿主才回退 `activeRunId`，否则拒绝且不出入口；不本地截断、不追加用户消息。
  - **AR-17**：普通消息级 regenerate **入口整体隐藏**、删除 `regenerateMessage` 假 action；错误卡动作按可见性隐藏；**A7 保留缺项**。
  - synthetic 历史条目转 `role:'system'` + 专用「已压缩」提示；`recoveryGap` 中性提示。
- **实际单点证据（1 个）**：后端 `RunStoreTerminalGuardTest#runningRetryWriteCannotOverwriteCancelledWithoutLease` —— **RED → GREEN 各一次**（RED = `expected CANCELLED but was RUNNING`）。
- **未跑与未做**：`ResumeServiceModelStepRetryTest`、`EmptyReplyTerminalTest` 新增面、`UpstreamErrorClassifierTest`、`AiControllerHistoryPayloadTest` 新增面、`SseChatEmitterFramesTest` 新增面；前端 `a11-batch2-*` 全部用例、隐藏 regenerate / 归属校验各面用例；**前端未跑 typecheck、未跑全量**；后端定向 / 全量、`npm run build`、主 CI、五工作流、`@real`、真实环境均未跑。

### 批次 3 · `c6734b7`（Task 5 + Task 6 Step 6 + `AR-11`）

- **改动摘要**：
  - **首帧前移（`AR-06`）**：`out.start` 在**同步压缩调用之前**发出、仍是首帧；紧随 `phase=compacting` 的阶段帧由请求线程在进入压缩前发出，**压缩调用自身一帧不发**；定义"已 start、快照未建"阶段——`reattach` 返回 **202 准备中**（可重试、带已用时间，不返回 404 / "轮不存在"），该阶段异常仍走取消优先守卫、禁裸 save。
  - **独立保活**：新增 `TurnKeepalive`，覆盖压缩 / 上游静默 / 慢工具；终态 / 异常 / 取消 / **park** 四处释放，park 收流后不再出帧、不扰 seq。
  - **前端分阶段超时**：`connect` 15s / `first-frame` 30s / `idle` 45s 三阶段可区分（45s 不放宽）；压缩状态条「正在压缩·已用 N 秒」不伪造百分比。
  - **`summary-model` 退役（`AR-11`）**：删 `application.yml` 空值行 + `AiProperties.Context` 绑定；启动对仍写该键的外部配置给**一次** WARN 且点名主模型；不恢复独立摘要分支。
  - **24h 到期可见化（`AR-10`）**：卡面剩余 / 到期时刻 + 到期可达出口；**不改 TTL、不加续期**；实时挂起路径**不新增任何轮询**；刷新恢复按记录时刻重算。
- **实际单点证据（4 个）**：
  1. 后端 `FirstTurnCompactionStageTest` **首轮方法** —— RED → GREEN；
  2. 后端 `AiRetiredKeysWarnerTest#summaryModelKeyWarnsOnceAndNamesTheMainModel` —— 一次 WARN 且点名主模型；
  3. 前端 `b3-compaction-stage` **心跳 1 个方法**（压缩期保活只由独立定时器出帧）；
  4. 前端 `b3-ar10-run-expiry` **到期 1 个方法**（到期可见化 + 可达出口）。
- **未跑与未做**：上述 4 点之外的其余用例（含两件新 spec 的其余 `it`）；`TurnKeepaliveTest`、`AiControllerEventsPreparingTest`、`AiControllerRunsCurrentTest`、`ResumeContextCompactionTest`、`ChatTurnFrameSequenceConformanceTest` 的**精确序列与 replay 断言**均未作为本批单点实跑（属"改了口径但未取证"）；后端定向 / 全量、前端全量、`typecheck`、`npm run build`、主 CI、五工作流、`@real`、真实环境未跑。

### 批次 4 · `55e6078`（Task 6 3–5 + Task 7 + Task 9）

- **改动摘要**：
  - **预检语义**：未进门不再渲染成"已批准"；批准后执行前冲突才显示"已批准·未执行"；DANGER 批准 / 拒绝 / 取消语义保留。
  - **知情三字段（`AR-02`）**：影响面 + 条件摘要（后端预检 payload 内补，不由前端反解）+ 权威发布模式（宿主枚举，模型文本不作权威）；只读摘要行、可折叠默认收起；不恢复旧五段式 / 抽屉 / 第二张卡。
  - **`taskStatus` 收窄**：披露与执行共用同一终态判据，`COMPLETED` 不再披露 `start_export`；空 scope 两侧同判；启动断言"回调表 ⊆ 元数据表"；工具 scope 下限 **26 → 37**。
  - **`create_task`（`AR-03`）**：升 DANGER；宿主幂等（规范化 `name` + `parentId` + 稳定参数哈希）；**单轮上限 1**（第二次直接拒绝并给可读原因）。
  - **表单队列（`AR-07`）**：同一时刻仅一个活动表单，第二张**排队**、第一张结算后按到达序呈现，实时与恢复**同构**，每张都有取消出口；不做批量一次提交。
  - **`definitions` 选项源** + **空选项必填 schema 期拒绝**。
- **实际单点证据（5 个）**：
  1. 后端 `ToolPreflightServiceTest#payloadCarriesImpactConditionSummaryAndAuthoritativePublishMode`（三字段进 payload）；
  2. 前端 `ai-panel-parts-render` **知情摘要 1 个方法**；
  3. 前端 `gf-form-queue` **同批两张 1 个方法**；
  4. 后端 `GenerativeFormSchemaRulesTest#requiredOptionFieldWithNoResolvableOptionsIsRejectedAtSchemaTime`；
  5. 前端 `ai-panel-gf-queue-card` **排队位次 1 个方法** —— **RED 期间含一次期望值修正**（首次断言取值写错，修正后再转 GREEN），**不写作"一次通过"**。
- **未跑与未做**："未批准 / 已批准·未执行"前端语义用例；`taskStatus` 收窄用例；scope 不变量启动断言（26→37）用例；`create_task` 幂等与单轮 1 用例（`AiToolsInvocationTest` / `ToolPreflightGateTest`）——**该批 `create_task` 面"单点证据 = 无"**；`definitions` 选项源用例；其余全部 spec；后端定向 / 全量、前端全量、`typecheck`、`npm run build`、主 CI、五工作流、`@real`、真实环境。

### 批次 5 · `9ac28d0`（Task 8）

- **改动摘要**：页面进入显式声明上下文（`enterPage`）并复位陈旧 `taskId`/`taskStatus`；`queryConditions` 按权威来源恢复；设置 / 反馈页默认 `tasks` 并说明原因；只在 handler 成功时记"已同步"；同一助手消息内前端动作串行；`restore_task` 先查权威任务类型（导入任务不进导出链路）；`start_publish` 绑定页面与任务；**`AR-08`** = 事件后 + 仅在途 + 可见时三条件才刷新 + 数据截至时刻，不引入常驻轮询。
- **实际单点证据（2 个）**：
  1. 前端 `workspace-page-context-reset.spec.ts` **页面声明与复位 1 个方法**；
  2. 前端 `task-center-view.spec.ts#ar08-event-inflight-visible`（事件后 + 在途 + 可见才刷新，并显示数据截至时刻）—— **GREEN 后仍有后续复核**（复核期核对过断言与实现），不写作"一次通过"。
- **未跑与未做**：`tasks` 默认原因、失败不报已同步、动作串行、权威任务类型、`start_publish` 各面用例；`workspace-audience` / `workspace-run-job-publish-confirm` / `ai-start-job-sync` / `session-purge-reset` 等 spec；`typecheck`、前端全量 / 构建、后端任何测试、主 CI、五工作流、`@real`、真实环境。

### 批次 6 · `54ca6ad`（Task 10 + Task 3 Step 4）

- **改动摘要**：
  - **思考真实计时**：段级 `startedAt/endedAt/lastFrameAt`（服务端 `atMs` 派生）+ 局部秒表；实时 / 续跑 / 重连有起止；离线按已收帧还原已用时间、不伪造；**历史已完成轮不显示时长**（`AR-12`：不新增后端字段、不按当前时刻回算）；无起点不显示。
  - **可访问性**：`role="log"` 可访问名、`aria-busy`、`aria-expanded` + `aria-controls`、计时 `aria-live=off`。
  - **空态三载体**：同时写清浏览器镜像 / Redis 运行态·记忆 24h 滑动 / 既有完整历史 DB **无 TTL**（`AR-01`），不写"不落库"，删"关闭页签即结束"。
  - **复制页签 / 清场 / 陈旧卡 / 孤儿草稿**：复制页签只一次性告知"这是新会话"（`AR-14`，不接回原会话）；新会话清 `contextSync`/`lastUiEventResult`/`lastRoundOutcome` 且 notice 自带 severity；陈旧卡按 code 显示人话并置只读；换会话按前缀 + 白名单清孤儿草稿键（禁 `storage.clear()`）。
  - **`N8`/`N9`**：用户摘要与诊断 detail 分层，配置键 / 路径 / `runId` / `toolCallId` 默认不上屏；内部码给人话摘要、原码只进诊断面。
- **实际单点证据（3 个）**：
  1. 前端 `ai-reasoning-frame.spec.ts` **实时秒表 1 个方法** —— **GREEN 前有多次修正**（断言与实现来回调整若干轮），不写作"一次通过"；
  2. 前端 `b6b-session-handoff.spec.ts` **陈旧卡 1 个方法**（人话 + 只读）；
  3. 后端 `SseChatEmitterFramesTest#toolResultFrameShowsUserSummaryForInternalCodes`。
- **未跑与未做**：离线 / 重连计时、多段各自计时、无起点不显示、历史轮无计时；a11y 四项各面；复制页签告知 / 清场 / 孤儿草稿；`N8` 前端人话映射；`ai-panel-parts-render` / `context-pill` / `restore-reconcile` 等 spec；`typecheck`、前端全量 / 构建、后端定向 / 全量、主 CI、五工作流、`@real`、真实环境。

### 批次 7 · `a373663`（Task 11）

- **改动摘要**：观察器迁移（`expanded` 变化即迁移 RO / IO / 滚动监听并清 pending）；组件层 `mdCache` 改 **LRU**（与内层 16 LRU 分层）；`v-memo` 显式依赖；ToolPart `WeakMap` 派生缓存；镜像**增量有界**且保留"最新助手 + 配对用户"、配额失败一次性可见；单条 >1 万字符降级给**一次性提示**；fence key **只由渲染器按出现序生成**、模型原生 HTML 不得携带 / 覆盖槽位属性；mermaid 只在**可见且 idle** 水合。
- **实际单点证据（3 个）**：
  1. 前端 `ai-panel-batch7a.spec.ts` **收起 / 展开 1 个方法**；
  2. 前端 `tool-part-derived-cache.spec.ts` **1 个方法**；
  3. 前端 `markdown-batch7c.spec.ts#7C-1`（模型原生 HTML 不携带 / 覆盖槽位属性）。
- **未跑与未做**：**真实浏览器 profile 未做**（消息数 / DOM / P50-P95-max 帧耗时 / 长任务 / INP / 内存均未测）⇒ **虚拟化立项闸不启动**（`AR-15`：不引依赖、不选型、阈值仍"消息 >80 条再议"）；`mdCache` LRU 命中刷新 / 同长不同文、`v-memo` 依赖、镜像增量与配额失败、降级一次性提示、其余 fence key、mermaid idle 各面；`markdown-render-perf` / `ai-panel-scroll-follow` / `parts-double-write` 等 spec；`typecheck`、前端全量 / 构建、后端任何测试、主 CI、五工作流、`@real`、真实环境。

### 批次 8 · `b74641c`（Task 12）

- **改动摘要**：
  - **`Q-08`（`AR-04`）**：维持 **park 释放会话门**，用例改断言确定成立的三件事（无幻影气泡 / 历史条数不变 / 前端无假提示），移除"挂起期同会话必 409"断言；`Q-05` 同批会话门口径收口。
  - **`D-08` / `M-07` / `TC-18`**：三实例取消语义的本地确定性补强；两个终态结果面按同一守卫收敛。
  - **`D-13`**：409 面另立**可控时序**用例（门确定持有的窗口内），覆盖 409 与 `runId` / `reattach` 回执，不靠 sleep / 重试制造通过。
  - **运行期死轮扫描**：受租约 + 僵尸年龄约束的低频扫描（活跃不接管 / 挂起已决可接管 / 失败终态不复活），不放宽 180s 窗口。
  - **`D7` 单点**：`start_*` 零条目不得判成功（只验不改）。
  - **`L-143`**：挂起收流与订阅者关闭同毫秒的帧交付竞态改为确定性可重建用例。
  - **`L-150` 三格用例**：R-1 `UNKNOWN` 不得执行 / R-2 已 `CANCELLED` 不得误认领 / R-3 claim 哈希-only 不得伪未执行（**仅本地用例**）。
- **实际单点证据（3 个）**：
  1. 后端 `StartupResumeRunnerRuntimeSweepTest` —— **单点 GREEN**；
  2. 后端 `ZeroItemJobTerminalTest#exportJobWithoutTaskItemsMustNotComplete` —— **1/1 通过**（`D7` 只记单点，**不把台账改成"已验"**）；
  3. 后端 `SseChatEmitterParkCloseRaceTest` —— **单点 RED → GREEN**。
- **未跑与未做（必须连读）**：
  - ⓐ **`Q-08` 的 `verify-e2e.ps1` 脚本未运行**——改动只到代码与用例文本；
  - ⓑ **`L-150` 真实三实例未执行**——本机 **Docker / HAProxy 不可用** ⇒ **`L-150` 待验、不闭环**；`cluster-3node` 16/16 **不得**反推三格；
  - ⓒ **`stub-upstream` 只做静态同步、未跑 self-check**（`scripts/ci/stub-upstream.mjs` 与其 test 均未执行）；
  - ⓓ **Step 6 主 CI 三次独立成功未做**；
  - ⓔ 除此之外的其余用例、后端定向 / 全量、前端全量、`typecheck`、`npm run build`、五工作流、`@real`、真实环境。

---

## 3. 全局未验证面（任何引用本件者必须一并引用本节）

| # | 未验证面 | 事实 | 影响 / 不得据此声称 |
|---|---|---|---|
| G-1 | **`L-150` 真实三实例** | **未执行**：本机 Docker / HAProxy 不可用；三格只到"本地用例绿" | 不得声称"三实例取消语义已验"；`cluster-3node` 16/16 不构成反推 |
| G-2 | **真实 PostgreSQL** | 本轮全部测试跑在 H2；无 PG 实跑记录 | 不得声称"PG 兼容 / PG 上验收通过" |
| G-3 | **生产 Redis Cluster 5.0.14** | 本轮无真集群运行；单测替身为内存 / 单库命名空间 | 不得声称"Cluster 下正确"；`XRANGE` 版本面等历史风险不因本整改消失 |
| G-4 | **真实 Lettuce 中断** | 未做注入（连接中断 / 重连 / MOVED·ASK 等） | 不得声称"网络抖动下已验证" |
| G-5 | **真实浏览器 profile** | Task 11 Step 4 **未做**（消息数 / DOM / P50-P95-max / 长任务 / INP / 内存） | 虚拟化不立项；不得声称"长会话性能已量化"或"已优化到目标" |
| G-6 | **五工作流** | 本轮整改**未触发**任何 workflow；既有 `61de820` 周期事实是 **4/5 达三轮、`ci.yml` 未达成**（`40255993044` attempt 2 因 `AIE2E-Q-08` 红） | 不得写"五工作流全绿"；未触发 / skipped 一律不计 |
| G-7 | **`A7` 消息级 regenerate** | `AR-17` 明确**本批不做**、入口隐藏 | **A7 保留缺项**；不得写"已支持 regenerate" |
| G-8 | **`@real` 真模型** | 未触发 | 不得声称"真模型服从率 / 工具选择已验" |
| G-9 | **`L-143` 竞态** | 只建了确定性用例（单点 RED → GREEN），**无"竞态消失"的证据** | 挂账不销记 |
| G-10 | **`L-149` / `L-151`** | `L-149`（五工作流验收）未闭环；`L-151`（Q-08 脚本 / 时序）**已裁、待施工**，脚本未跑 | 不得销记 |
| G-11 | **`D7` 零条目** | 只有 1 个方法通过，未覆盖真实作业边界全量 | 台账**保持"已修待复验"** |
| G-12 | **批次 2–8 的工程门禁** | 仅批次 1 跑过 `typecheck` 与后端 `test-compile`；批次 2–8 期间**未再跑**；**批次 9 另行跑了**后端 compile / 前端 `npm run build` / `check-docs` / i18n 静态检查（见 §6.1） | 不得声称"类型检查 / 构建通过"覆盖整轮整改；§6.1 只证明**当前树**能编译 / 能构建 / 门禁过，**不追溯**批次 2–8 各自快照 |

---

## 4. 口径 → 批次映射（便于按条追溯）

| 口径 | 主题 | 主要落点批次 | 验证状态 |
|---|---|---|---|
| `AR-01` | 历史范围 / 保留期 / DLP / 失败现场 | 2（镜像 + synthetic 展示）、6（空态三载体） | 单点；DB 无 TTL 为**现状事实**，非本批新建 |
| `AR-02` | 确认卡三字段 | 4 | 后端 payload 单点 + 前端 1 方法 |
| `AR-03` | `create_task` DANGER + 幂等 + 单轮 1 | 4 | **无单点**（仅代码成立） |
| `AR-04` | Q-08 维持 park 释放门 | 8 | 用例文本已改；**脚本未跑** |
| `AR-05` | 仅模型步重跑 / 不做半截替换 | 2 | 1 个后端单点 |
| `AR-06` | 首轮 `start` 前移 + 快照未建语义 | 3 | 首轮方法 1 个单点；conformance 精确序列未取证 |
| `AR-07` | 表单单活动队列 | 4 | 2 个前端单点（其一含期望值修正） |
| `AR-08` | 任务中心受限刷新 | 5 | 1 个前端单点 |
| `AR-09` | 继续 / 重试本轮 / 编辑后果提示 | 2 + 6 | 单点覆盖"重试"；「继续」与编辑提示未单独取证 |
| `AR-10` | 24h 只可见化 + armed 跨 park | 2（armed） + 3（可见化） | armed 未见单独单点；到期 1 个前端单点 |
| `AR-11` | `summary-model` 删键 + 退役告警 | 3 | 1 个后端单点 |
| `AR-12` | 历史思考不新增持久化 | 6 | 实时秒表单点；历史轮"不显示"未单独取证 |
| `AR-13` | 通知 severity + 关键错误归卡 | 1 + 6 | 无单独单点 |
| `AR-14` | 复制页签只告知 | 6 | 无单独单点 |
| `AR-15` | 虚拟化只量化 | 7 | **profile 未做** |
| `AR-16` | 旧 runner 守卫只扫非终态 | 1（1D） | 无单点；`RunnerActiveMultiInstanceTest` 未跑 |
| `AR-17` | 普通 regenerate 本批不做 | 2 | 无单点；A7 保留缺项 |

---

## 5. 本件**不**支持的主张（明确禁止的写法）

- 不支持"全量测试全绿 / 前端全绿 / 后端全绿 / 构建通过"覆盖整轮整改。
- 不支持"主 CI 绿 / 五工作流全跑 / 项目验收完成 / 可上线"。
- 不支持"真实 PG / 生产 Redis Cluster / Lettuce 中断 / 三实例 / `@real` 已验证"。
- 不支持"长会话性能已量化 / 虚拟化已立项"。
- 不支持"A7 regenerate 已修复 / 已支持"。
- 不支持"`L-143` / `L-149` / `L-150` / `L-151` 已闭环"。
- 不支持把"单点 RED → GREEN"读作"该面全部用例通过"。

---

## 6. 批次 9（本文档批）范围与落点

- **性质**：Task 13 Step 1–2 的**文档同步** + 本批**工程门禁类验收**（§6.1，6 项，已实跑）；**不是功能施工**，**不构成整轮整改验收**。
- **入库**：**已提交并推送 `9a2f01c`**（批次 9 = 本件 + 三份核心文档更正 + `docs/README.md` / `docs/research/README.md` 注记 + 两份看板当前段 + 计划 Task 13 完成范围）；提交链末位见 §1。
- **本批实际写入**：
  - `docs/02-需求设计文档-v2.2-冻结版.md` —— 追加「现行口径追加与更正（2026-10-26）」，**历史段落未改**；
  - `docs/03-技术方案文档-v2.1.md` —— 追加「现行口径追加与更正（2026-10-26）」技术面；
  - `docs/AI对话日志链路追踪手册.md` —— 追加日志面更正（新事件 / `unconfirmed` / DLP 措辞等）；
  - 本文件（证据索引）；
  - `docs/README.md` 证据入口与两条基线导航行；
  - `docs/research/README.md` 一条"本批未新增研究件"的计数维护注记（如无需新增件，仅登记边界）；
  - `docs/superpowers/plans/2026-10-26-ai-interaction-remediation.md` —— Task 13 的**完成范围**与未完成项登记。
- **本批未做（在 §6.1 验收之外）**：除 §6.1 列出的 6 项外**未跑任何其它测试 / 全量 / 构建矩阵**；未起停服务；未做 git 写。

### 6.1 批次 9 验收（本批实跑，逐条只写跑过的）

| # | 验收项 | 结果 | 覆盖范围与不得外推 |
|---|---|---|---|
| A-1 | 后端编译 | **BUILD SUCCESS**（IDEA 内置 `mvn.cmd`，`-o`，**未 clean**；`-DskipTests` 级别同口径） | 只证明**编译**通过，**不等于**后端测试通过 |
| A-2 | 后端单测（契约） | `ErrorCodesContractTest#frontendMirrorMatchesBackendCodes` **1/1 通过** | **单点**：只证明"前端错误码镜像 = 后端码表"这一条断言；**不等于** `ErrorCodesContractTest` 全类或后端全量 |
| A-3 | 前端构建 | `npm run build` **成功**（**3268 modules transformed**） | 只证明构建产物可出；`vue-tsc` 类型检查结论按该命令实际输出为准，**不单独声称 typecheck 全绿** |
| A-4 | 文档门禁 | `node scripts/ci/check-docs.mjs` **6/6** | 只覆盖该门禁的 6 条断言；**不覆盖**两份看板正文与 research 索引全部内容 |
| A-5 | i18n 静态检查 | **通过**（静态检查，非运行时） | 只证明静态残留检查在当前基线内；**不等于**双语文案在 UI 上全部正确 |
| A-6 | 前端单点测试 | `workspace-context-task-status` **1/1 通过** | **单点**：只覆盖"页面上下文 + 任务状态"这一条；**不等于**前端全量 |

**验收之后仍未验（必须与本表连读，禁止合并成"全绿"）**：

- **`L-150` 真实三实例**：本机 Docker / HAProxy 不可用 ⇒ **未执行、不闭环**（见 §3 G-1）；
- **真实环境面**：真实 PostgreSQL / 生产 Redis Cluster 5.0.14 / 真实 Lettuce 中断 —— 均**未做**（§3 G-2 ~ G-4）；
- **真实浏览器 profile**：Task 11 Step 4 **未做** ⇒ **虚拟化不立项**（§3 G-5）；
- **五工作流**：本轮**未触发**任何 workflow；既有 `61de820` 周期事实仍是 **4/5 达三轮、`ci.yml` 未达成**（§3 G-6）；
- **`@real` 真模型**：未触发（§3 G-8）；**`L-143`** 无竞态消失证据（§3 G-9）；**`L-149` / `L-151` 不销记**（§3 G-10）。
- **本表 6 项不构成"整轮整改验收通过"**：A-1/A-3/A-4/A-5 是**工程门禁类**（只证明能编译 / 能构建 / 文档门禁过 / 静态 i18n 干净），A-2/A-6 是**各 1 个方法**；后端全量、前端全量、E2E、五工作流、真实环境**均未跑**。

---

## 7. 复核入口

| 目的 | 文件 |
|---|---|
| 口径原文（谁裁的、代价是什么） | `docs/research/裁决-AI交互整改实施口径-2026-10-26.md` |
| 施工步骤与每批"实现范围与状态" | `docs/superpowers/plans/2026-10-26-ai-interaction-remediation.md` |
| 代码复核与方案来源 | `docs/research/合并-AI设计与交互审查-代码复核与方案-2026-10-26.md` |
| 批次 / 挂账登记（历史行原文不回改） | `docs/任务看板.md`、`docs/决策看板.md` |
| 现行需求 / 技术 / 日志口径 | `docs/02-需求设计文档-v2.2-冻结版.md`、`docs/03-技术方案文档-v2.1.md`、`docs/AI对话日志链路追踪手册.md` |
| 本轮之前的审查证据（不同批次，勿混算） | `docs/evidence/ai-interaction-review-2026-10-26/README.md`、`docs/evidence/ai-remediation-2026-10-25/README.md` |
