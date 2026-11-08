# AI Conversation Recovery and Content Remediation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修复创建导出流程的重复恢复、断轮、过程卡和技术文案，并以真实内容与刷新一致性作为验收门禁。

**Architecture:** 复用现有 Redis/SSE/history/pending/ledger、稳定 `assistant-{runId}` messageId 和有序 parts，不重写事件框架。全量刷新按权威历史重建同 run，活动连接按 seq 增量幂等；镜像只做 last-good 缓存。宿主状态机负责常规流程，动态文案集中收口。

**Tech Stack:** 现有 Java 21/Spring、Vue 3/TypeScript、Redis、Node.js 独立验证脚本、Kimi Code Desktop 内置浏览器；不新增依赖。

**Spec:** `docs/superpowers/specs/2026-11-08-ai-conversation-recovery-content-design.md`；调研：`docs/research/调研-AI对话重复恢复与导出内容治理-2026-11-08.md`。

## Global Constraints

- 先取得故障证据再改恢复算法；未确认的 H1 不得写成唯一根因。
- 不修改普通测试代码；独立验证脚本和 `docs/verification/AI对话独立复测清单-2026-11-05.md` 按授权维护。
- AI 数据除模型配置和用户反馈外不得访问数据库；业务表只核对真实业务副作用。
- 不新增依赖、供应商分支、场景话术特判或新事件框架；协议变化仅允许 additive。
- 每次动态验证只执行一个场景/变体，复杂场景至少三个独立新会话；禁止批量循环测试用例。
- 每批生产代码必须实际编译，Java 使用 IDEA 默认 Reformat；独立脚本执行 `node --check`。
- 每批逐 hunk 关联审查、独立内容审查、开发规范 ledger 与门禁通过后提交推送。
- 脚本 PASS 不等于整项 PASS；浏览器、Redis、业务 DB、真实供应商未执行时分别保留 NOT_RUN。

## Review Focus

- 同 run 多个持久化 ASSISTANT 与纯文本/多工具/多表单混合：只能形成一个有序 UI 时间线，测试 C-131、C-138。
- history 暂时不可用、旧镜像缺稳定 ID：只保留一份真实缓存并明确恢复状态，不复制、不静默清空，测试 C-131、C-133。
- 用户未发送草稿和未提交表单：刷新后保留，不能与助手历史一起被重建掉，测试 C-134、C-135。
- 动态文案的空值、未知值、旧数据和中英文：不显示技术话术或无价值占位，测试 C-136。
- 创建导出各事件边界的刷新和重复操作：不得增加 run、消息、卡片、任务、作业或工具副作用，测试 C-130～C-133、C-137。

---

### Task 0: 固定故障证据并确认分层根因

**Files:**
- Create: `docs/research/分析-AI对话重复恢复根因-2026-11-08.md`
- Modify: `docs/verification/AI对话独立复测清单-2026-11-05.md`
- Optional create: `scripts/verification/ai-timeline-content-api.mjs`（仅独立公开 API 证据驱动）

**Interfaces:**
- Consumes: 公开 `/api/ai/chat`、`/api/ai/events/{runId}`、`/api/ai/history/{sessionId}`、`/api/ai/runs/{runId}` 和只读业务 API。
- Produces: 同一故障会话的分层证据、确认的重复来源、Task 1 必须保持的输入/输出身份清单；不得伪造应用内帧。

- [ ] **Step 1: 用同一创建指令建立失败现场**

  按 C-130 操作，保存用户原文、真实模型请求的工具披露与首次输出、完整 SSE、history/run、任务和作业计数。记录实际 HEAD、执行者、时间和仓外证据目录。

- [ ] **Step 2: 在流程边界逐一刷新并读取四层内容**

  按 C-131/C-132 保存脱敏镜像、history entries、公开 SSE seq、消息/parts、实际 DOM。比较每层消息数、稳定 ID、卡片数、完整文本和顺序，定位重复首次出现的层。

- [ ] **Step 3: 形成根因结论、宿主迁移映射和最小修复边界**

  将重复来源分为后端重复落账、SSE 重放、镜像合并、快照重建或 DOM 投影；若 H1 不成立，记录反证并改选实际根因。另形成以下现有调用映射并核对实际入出参：创建结算→`DefinitionSelectionForm.parkNavigateIfDue`；已知配置→`parkSelectDefinitionsIfDue`；选择完成→`parkAdvanceIfDue`；进入 QUERY_COND→`ResumeService.parkConditionForms`→`ConditionsForm.parkIfDueAtQueryCond`；条件留空→`shouldParkConditionAdvance`→EXPORT；EXPORT→Task 2 新 `ExportStartForm.parkIfDueAtExport`。未完成本步不得修改恢复算法或宿主流程。

- [ ] **Step 4: 固化失败用例和证据范围**

  C-130～C-139 保持 NOT_RUN，写入实际失败样本和预期精确差异。独立脚本若创建，必须 `node --check`，OUT_DIR 只能位于仓外，每次只接受一个 CASE。

### Task 1: 统一恢复身份与全量/增量语义

**Files:**
- Modify: `frontend/src/stores/ai.ts`
- Modify: `frontend/src/types/ai.ts`
- Modify only if evidence proves missing server identity: `backend/src/main/java/com/example/configmgr/ai/web/AiController.java`
- Modify only for the independent C-131C upstream fixture: `scripts/ci/stub-upstream.mjs`
- Modify: `docs/research/分析-AI对话重复恢复根因-2026-11-08.md`

**Interfaces:**
- Consumes: 实时 `messageId=assistant-{runId}`、runId、`segments[].seq`、toolCallId、普通 SSE seq、lastAppliedSeq；持久化 part 使用 `(entryId, segments[].seq)`；eventId 仅用于既有压缩事件。
- Produces: 同一 run 唯一 UI 助手及有序 parts；`mergeMirrorInFlightRound` 不得克隆未匹配的同 run 镜像助手；旧数据保留 legacy 单份展示。

- [ ] **Step 1: 先运行 C-131 作为修复前反例**

  按 C-131A/B/C 的确定性上游变体保存多 ASSISTANT、多工具、多表单、非前缀正文和两个进度版本的镜像/history。修复前必须出现重复或分叉；不能构造时保持 NOT_RUN，不改断言冒充复现。

- [ ] **Step 2: 收敛历史消息身份**

  优先消费实时已有的 `assistant-{runId}` messageId；跨来源 part 对账需要 entryId 时，通过 additive history 字段透出并同步 `AiHistoryMessage`，不得当作现有字段。`history-数组下标` 仅作 legacy 展示 ID，不参与跨来源身份推断。

- [ ] **Step 3: 修复镜像对账而不是新增去重补丁**

  同 run 的历史条目按原顺序投影为一个 UI 助手；未匹配镜像不得克隆插入。history 可用时以权威历史重建，暂不可用时只保留一份镜像及真实恢复状态。

- [ ] **Step 4: 核对全量重建和 seq 增量幂等**

  全量刷新替换同 run，普通 SSE 只应用 `seq > lastAppliedSeq`；工具按 toolCallId、part 按 `(entryId, segments[].seq)`、既有压缩事件按 eventId 幂等，旧 generation/revision 不覆盖新现场。不得重放工具或表单副作用。

- [ ] **Step 5: 独立关联审查和编译**

  审查实时、history、镜像、快照、分页、旧数据和多实例路径。前端生产构建可用时执行；若修改 Java，Maven 非测试编译必须 BUILD SUCCESS，IDEA 默认格式化后重审 diff。

- [ ] **Step 6: 单场景验收并提交**

  分别执行 C-131、C-132、C-133、C-138，逐次比较完整正文、parts、卡片和副作用。独立审查者重新计算内容，不只读 summary；门禁通过后提交推送。

### Task 2: 收口创建导出的一轮宿主闭环

**Files:**
- Modify: `backend/src/main/java/com/example/configmgr/ai/run/PromptRulePacks.java`（仅删除冲突/重复规则，不堆长提示词）
- Modify: `backend/src/main/resources/ai/tools.yaml`（同步模型契约）
- Modify: `backend/src/main/java/com/example/configmgr/ai/form/DefinitionSelectionForm.java`
- Modify: `backend/src/main/java/com/example/configmgr/ai/form/ConditionsForm.java`
- Modify: `backend/src/main/java/com/example/configmgr/ai/run/ResumeService.java`
- Create: `backend/src/main/java/com/example/configmgr/ai/form/ExportStartForm.java`
- Modify: `frontend/src/components/AiPanel/AiPanel.vue`
- Modify: `frontend/src/locales/zh-CN.json`、`frontend/src/locales/en-US.json`

**Interfaces:**
- Consumes: 真实 create_task 结果、任务类型、当前 step、defCodes/conditions 缺口、现有 pending/ledger、`ToolPreflightService.inspect`、`HostForm.shouldPark`、`ConfirmGate.publishRequestsForParkedBatch`。
- Produces: `ExportStartForm.parkIfDueAtExport(String runId, AiContext context, List<Message> history): boolean`；仅在权威 EXPORT 步骤、配置/条件完整、原始意图允许续接且无既有 EXPORT 作业时，经预检生成确定性 `host-start-export-{runId}-{taskId}` 的 kind=CONFIRM 的 pending 并挂起确认。

- [ ] **Step 1: 运行 C-130 固定当前断轮和重复追问**

  记录首次模型输出为何没有直接 create_task；区分规则未披露、工具未披露、模型未遵守和后续宿主误导，不添加中文关键词生产分支。

- [ ] **Step 2: 按 Task 0 映射推进导航、配置和条件**

  复用 `DefinitionSelectionForm` 的导航/选择/推进与 `ResumeService.parkConditionForms` 的条件链；只收集真实缺口，已知信息不询问。任何失败、拒绝或取消沿用真实终态，不自动重试或改问用户。

- [ ] **Step 3: 在权威 EXPORT 步骤挂起启动确认**

  `ResumeService.parkConditionForms` 在条件链未挂起且任务真实步骤已为 EXPORT 时调用 `ExportStartForm.parkIfDueAtExport`。新方法先核对任务条目、条件、原始 openWizard 意图、现有作业和 pending/ledger，再复用 `ToolPreflightService` 预检；通过后以确定性 ID 创建 `name=start_export`、`kind=CONFIRM` 的 pending，写入 `pending.setPreflight(report.asMap())`，并由 `HostForm.shouldPark` 走既有 `suspended → ConfirmGate 请求帧 → 收流`。该 CONFIRM 条目批准后仍复用既有后端执行、预检指纹复核和幂等台账，批准前不得启动作业。`hasIssued` 与执行台账抑制重复下发；预检失败、拒绝、取消、未知结局均不启动、不下载、不伪造成功。

- [ ] **Step 4: 统一自动过程和结果表达**

  导航、选择、确认步骤等成功自动过程只更新业务页面；需要操作的 HITL/表单、业务结果和真实失败保留。启动确认只在 HITL 出现；任务号、配置项、条件和结果只表达一次。同步工具定义与提示词，避免规则冲突。

- [ ] **Step 5: 独立审查、编译和单场景验收**

  关联审查预检/pending/确认/恢复/拒绝/取消/作业副作用及创建/导入/发布相邻路径，Maven 非测试编译必须 BUILD SUCCESS。执行 C-130、C-137 及既有 C-111～C-114，任何第二次聊天输入、重复任务/作业、重复确认或绕预检均 FAIL。

### Task 3: 集中治理动态文案和表单用途

**Files:**
- Modify: `backend/src/main/java/com/example/configmgr/ai/gate/ToolPreflightService.java`
- Modify: `frontend/src/components/AiPanel/AiPanel.vue`
- Modify: `frontend/src/locales/zh-CN.json`、`frontend/src/locales/en-US.json`
- Modify if needed: `backend/src/main/resources/messages_zh.properties`、`messages_en.properties`

**Interfaces:**
- Consumes: preflight target/impact/conditionSummary、表单 purpose、真实定义名称和 ReplyLanguage。
- Produces: 每个工具/表单的简洁用户文案及安全省略规则；结构化 fact/reportHash 不随人读文案改变。

- [ ] **Step 1: 建立动态文案字段清单**

  枚举五个确认工具和各 purpose 的标题、字段、空值、未知值、异常值及中英文兜底。保留结构化 fact 和 reportHash 输入不变。

- [ ] **Step 2: 清退技术话术和无价值占位**

  删除“宿主收集一次”“影响面暂不可估”“创建≠执行”等用户文案；用户侧使用“配置项”。空任务名等字段省略，相同条件紧凑表达，未知但需决策时给具体可行动事实。

- [ ] **Step 3: 按 purpose 生成表单标题**

  选择配置、设置条件等使用明确业务标题，不再共用“请填写以下信息”；真实动态名称来自后端，不编造示例。中英文同步。

- [ ] **Step 4: 独立逐字段审查、编译和验收**

  审查所有引用方、历史 preflight 和指纹稳定性，实际编译。执行 C-136，并复核 C-115～C-121 相关卡面；任何技术话术、空占位或重复字段均 FAIL。

### Task 4: 完整内容验收与最终独立审查

**Files:**
- Modify: `docs/verification/AI对话独立复测清单-2026-11-05.md`
- Optional modify: `scripts/verification/ai-timeline-content-api.mjs`
- Create: `docs/verification/AI对话恢复与导出内容验收-2026-11-08.md`

**Interfaces:**
- Consumes: C-130～C-139、相关既有 C-61～C-69 和 C-111～C-129 的仓外证据。
- Produces: 每案 PASS/FAIL/NOT_RUN、实际内容差异、业务/Redis/日志/清理证据和未覆盖边界。

- [ ] **Step 1: 执行确定性刷新边界矩阵**

  每个 CASE 单独执行，覆盖 C-130～C-138 指定边界。随机刷新必须记录 seed 和实际事件点，不能用固定 sleep 或连续批量循环代替。

- [ ] **Step 2: 独立比较完整内容和真实副作用**

  比较精确文本、消息数、parts、卡片 ID/类型/状态/字段、DOM、history/run、任务/作业/文件、Redis 键/TTL、日志和清理。不得通过 trim/归一化消除重复证据。

- [ ] **Step 3: 审查自然语言内容**

  检查重复段落、内部术语、多余追问、无效礼貌句、硬编码示例、重复确认和同一事实多次表达。模型可自由措辞，但违反这些不变量即 FAIL。

- [ ] **Step 4: 汇总未覆盖与最终 verdict**

  浏览器、直接 Redis、业务 DB、真实供应商、多实例和 Cluster 分别记录。任何缺失保持 NOT_RUN，不能用其他场景拼接 PASS，不能宣称全部 AI 场景已覆盖。

- [ ] **Step 5: 最终规范门禁与提交推送**

  独立审查者逐 CASE、逐独立轮次读取原始 SSE/history/DOM/业务文件，重算完整正文、消息数、parts、卡片数量与顺序及哈希；抽样只用于额外日志检查。生产编译和脚本语法检查完成，ledger 覆盖全部文件，`node scripts/ci/check-d6-sync.mjs --standards` 通过后提交推送。
