# AI 对话一轮闭环与交互文案治理施工验收计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
>
> 当前状态：仅记录计划和进行只读分析，**尚未批准启动施工**。本文件不是任何代码已修改或场景已通过的证据。

**Goal:** 任意能够在一轮对话内完成的 AI 操作，都从用户的一次指令连续走到真实终点；必要的 HITL/表单操作完成后自动续跑，不再要求用户在输入框重新表达同一意图，同时清退无效卡片、技术黑话、重复回复及未经批准的按钮文案变化。

**Architecture:** 以历史正常交互和现行安全规格为基准，不以当前错误实现反推需求。后端按“用户指令 → 工具与表单 → 必要确认 → 自动续跑 → 真实终态”恢复同轮状态机；前端只呈现用户需要决策或理解的内容，内部恢复、跳过和重试保持静默。安全确认不被绕过，真实失败和用户拒绝不被隐藏。

**Tech Stack:** Java 21、Spring AI、Reactor、Redis、Vue 3、TypeScript、Tailwind CSS、现有 i18n、真实 DeepSeek 模型、独立 HTTP/SSE/浏览器验证。

**Spec:** `docs/verification/AI对话独立复测清单-2026-11-05.md`；`docs/02-需求设计文档-v2.2-冻结版.md`；`docs/03-技术方案文档-v2.1.md`；`docs/adr/DECISION-CARDS.md`；本文件记录的 User 最新明确裁决。

## Global Constraints

- **一轮闭环优先**：“创建导出任务”同样必须一轮走完可完成的全流程，不能只创建或配置后停下。
- **同一轮不等于无确认**：创建、启动、发布、写入等危险动作仍走各自 HITL；用户批准前不得真实执行。
- **确认与表单不是新意图**：批准、拒绝、提交、修改、取消均是同一轮流程的继续，不得要求用户再发送“开始导出”等重复指令。
- **用户可退出**：拒绝或取消后如实结束，不自动重试、不再次询问、不伪造继续。
- **能静默处理就不打扰**：自动恢复、参数归一、内部重试、内部跳过、内部纠错和状态同步不向用户展示。
- **不掩盖真实问题**：真实失败、未知结局、数据不一致和用户主动拒绝必须保留真实状态与安全说明，禁止用隐藏卡片、提前返回、降级或日志屏蔽冒充成功。
- **无技术黑话**：普通用户不得看到 `FORM_SLOTS_ALREADY_KNOWN`、`set_condition`、`defCode`、草案、下一批、runId、toolCallId、SSE、Redis 等内部术语。
- **按钮文案不得擅改**：以历史正常版本及用户明确批准的文案为准；所有交互卡统一核查，不能只修单个 HITL 按钮。
- **不扩大 redesign**：只处理一轮闭环、无效卡片、操作文案、空白条件、重复回复和输入光环裁切，不 redesign 无关卡片。
- **普通测试代码不改**：允许生产代码、压测代码、独立验证脚本和测试文档；不得批量或循环执行单元测试。
- **真实验证分工**：DeepSeek 按主清单逐场景执行真实 HTTP/SSE/Redis/浏览器验证；每个场景独立执行，不通过循环重跑掩盖模型波动。
- **提交门禁**：IDEA Reformat、逐 hunk 独立静态复审、生产编译、真实场景验收、开发规范记录全部完成后才能提交推送。
- **证据诚实**：`FAIL` 与 `NOT_RUN` 不得写成 `PASS`；单实例 27/27 不代表多实例、Cluster、故障注入和 API 负载通过。

## Review Focus

- “创建导出任务”是否在批准创建和启动后于同一轮真实执行，而不是停在配置完成。
- “导出 CNY 货币字典”是否准确形成 `code=CNY` 条件并一轮执行，不重复要求启动指令。
- “导出货币字典”的空白条件是否直接按全量继续，不被当成非空文本，不出现重复条件表或内部错误卡。
- 拒绝、取消与真实失败是否保持可见且不被静默重试。
- 所有按钮是否恢复历史正常文案，未混入未经批准的新文案。
- 内部跳过、内部重试和自动恢复是否不再产生“已跳过/未执行”卡片。
- 模型或工具偶发偏离时，系统是否在安全边界内自动纠正，而不是把内部恢复过程甩给用户。
- 输入组件在聚焦、长内容、滚动和窄屏下是否保持光环完整且不被容器裁切。

---

### Task 1: 冻结历史基线与验收矩阵

**Files:**
- Update: `docs/verification/AI对话独立复测清单-2026-11-05.md`
- Reference: 历史正常版本及近期相关 Git diff

**Interfaces:**
- Consumes: 用户最新一轮闭环裁决、历史正常交互、现行 HITL 安全规格。
- Produces: 后续任务共同遵守的场景、按钮文案、卡片显示与终态基线。

- [ ] **Step 1: 建立全场景矩阵**
  覆盖创建导出、直接导出、带条件导出、空白条件、已有任务启动、导入、预检查、发布、上传、表格预览、表单填写/修改/取消、HITL 批准/拒绝。

- [ ] **Step 2: 固定每个场景的验收结果**
  明确必要卡片、按钮原文、允许暂停点、批准后动作、真实终态、历史结果和禁止出现的卡片/文案。

- [ ] **Step 3: 固定“不得发生”清单**
  包括要求用户再发一轮、启动被内部跳过、无效“未执行”卡、技术错误码、内部工具名、草案/批次、重复状态回复和未经批准的按钮改名。

- [ ] **Step 4: 审查矩阵**
  由主会话逐行核对用户最新要求；任何矩阵歧义先裁决并记录，不带着猜测进入施工。

### Task 2: 盘点当前状态机与全部交互文案

**Files:**
- Analyze: `backend/src/main/java/com/example/configmgr/ai/run/ResilientChatService.java`
- Analyze: `backend/src/main/java/com/example/configmgr/ai/gate/SpToolCallingManager.java`
- Analyze: `backend/src/main/resources/ai/tools.yaml`
- Analyze: `frontend/src/components/AiPanel/**`
- Analyze: `frontend/src/utils/confirm-irreversible.ts`
- Analyze: `frontend/src/utils/tool-summaries.ts`
- Analyze: `frontend/src/locales/zh-CN.json`
- Analyze: `frontend/src/locales/en-US.json`
- Create: `docs/research/分析-AI交互卡操作文案现状-2026-11-08.md`

**Interfaces:**
- Consumes: 当前代码、历史版本、Task 1 验收矩阵。
- Produces: 精确到文件/调用链的修改清单，以及全部按钮的当前文案/历史文案/目标文案对照。

- [ ] **Step 1: 盘点所有用户可点击操作**
  包括批准、拒绝、取消、提交、应用、重试、上传、下载、编辑、选择、继续及步骤操作；记录组件、场景、当前中英文文案和实际动作。

- [ ] **Step 2: 对照历史正常文案**
  标出所有变化及引入提交，没有用户明确依据的变化列入恢复项，不只检查 HITL。

- [ ] **Step 3: 追踪“已跳过/未执行”生成来源**
  区分内部策略跳过、模型偏离、自动恢复失败、真实用户拒绝和真实执行失败，决定哪些从源头消除、哪些真实保留。

- [ ] **Step 4: 追踪断轮和空白条件**
  核对创建/启动工具披露、同轮阻断、提示词、宿主续跑、条件空白归一及重复恢复路径。

- [ ] **Step 5: 盘点输入光环**
  检查 input、textarea、select、上传及多层滚动容器的 focus/focus-visible、outline、box-shadow、overflow 和 padding。

### Task 3: 恢复同轮创建—配置—确认—执行状态机

**Files:**
- Modify: `backend/src/main/java/com/example/configmgr/ai/gate/SpToolCallingManager.java`
- Modify: `backend/src/main/java/com/example/configmgr/ai/run/ResilientChatService.java`
- Modify: `backend/src/main/resources/ai/tools.yaml`
- Modify: 相关宿主续跑与工具守卫文件（以 Task 2 证据为准）
- Update: `docs/verification/AI对话独立复测清单-2026-11-05.md`

**Interfaces:**
- Consumes: Task 1 的一轮闭环规则、Task 2 的断轮调用链。
- Produces: 同一 run 内创建/配置/启动确认/批准/执行连续推进；拒绝和取消真实终止。

- [ ] **Step 1: 先记录当前断轮真实复现**
  对“创建导出任务”保留当前需要第二轮输入的证据，作为 RED；不得以静态推测替代。

- [ ] **Step 2: 移除错误的后续独立请求阻断**
  仅移除“必须再发一轮”的人为门禁，不放宽危险工具的 HITL 确认。

- [ ] **Step 3: 统一提示词、工具描述与执行门禁**
  明确所有可一轮完成的操作连续推进；创建和配置完成允许进入启动确认，批准后真实执行。

- [ ] **Step 4: 保持取消、拒绝和异常语义**
  用户拒绝或取消不自动重启；真实失败不转成功；已有任务不得被重复创建。

- [ ] **Step 5: 静态复审与生产编译**
  独立审查完整状态机、租约、pending、runEpoch、历史和恢复路径，IDEA Reformat 后编译通过。

- [ ] **Step 6: 单场景真实验证**
  “创建导出任务”在同一轮完成创建、配置、启动确认，批准后产生真实作业；无需第二次聊天输入。

### Task 4: 空白条件与可恢复流程静默收口

**Files:**
- Modify: 条件表单、条件草案及 `confirm_step` 相关生产文件（以 Task 2 证据为准）
- Modify: 必要的提示词与工具参数归一位置
- Create: `scripts/verification/ai-one-turn-interaction-api.mjs`
- Update: `docs/verification/AI对话独立复测清单-2026-11-05.md`

**Interfaces:**
- Consumes: Task 3 的连续状态机、Task 2 的空白状态证据。
- Produces: 空白=全量、明确非空=结构化条件、可恢复错误不形成用户可见技术卡。

- [ ] **Step 1: 固定空白输入边界**
  覆盖 `null`、空字符串、纯空白、换行、全角空格、明确“不筛选/全部”和真实非空条件。

- [ ] **Step 2: 统一空白归一**
  前端显示、后端草案和 confirm 门禁使用同一语义；纯空白不得被当作非空文本。

- [ ] **Step 3: 静默完成可恢复操作**
  必须设置空草案或重复确认时自动完成，不展示内部工具名、错误码和恢复说明，不重复发条件表。

- [ ] **Step 4: 保持真实非法条件失败**
  非空但无法解析或不符合结构的条件不得静默当成全量，给出简洁可操作的说明。

- [ ] **Step 5: 定向验证**
  “导出货币字典”留空直接全量推进；不出现 FORM_SLOTS_ALREADY_KNOWN、重复条件卡或重复确认卡。

### Task 5: 恢复所有交互按钮历史文案

**Files:**
- Modify: `frontend/src/components/AiPanel/**`（仅限文案不符组件）
- Modify: `frontend/src/utils/confirm-irreversible.ts`
- Modify: `frontend/src/locales/zh-CN.json`
- Modify: `frontend/src/locales/en-US.json`
- Update: `docs/research/分析-AI交互卡操作文案现状-2026-11-08.md`

**Interfaces:**
- Consumes: Task 2 的当前/历史/目标文案对照。
- Produces: 所有交互卡一致且经确认的中英文操作文案，按钮动作与文字一致。

- [ ] **Step 1: 恢复无明确变更依据的历史文案**
  包括 HITL 批准/拒绝及其他表单、预览、上传和步骤按钮，不另造新文案。

- [ ] **Step 2: 核对中英文与动态参数**
  同一动作不同卡片不得互相矛盾；不拼接不可翻译句子，不把内部工具名当用户动作名。

- [ ] **Step 3: 全量静态盘点**
  逐一确认没有遗漏其他被改按钮；将最终清单和证据写入分析文档。

- [ ] **Step 4: 浏览器逐场景验证**
  检查按钮文字、动作、禁用态、取消态和批准后续跑，不只截图文案。

### Task 6: 清退无效卡片、技术细节与重复回复

**Files:**
- Modify: `frontend/src/components/AiPanel/**`
- Modify: `frontend/src/utils/tool-summaries.ts`
- Modify: 必要的后端结果视图与用户文案生产位置
- Update: `docs/research/分析-AI交互卡操作文案现状-2026-11-08.md`

**Interfaces:**
- Consumes: Task 2 的卡片来源分类、Task 3/4 已恢复的状态机。
- Produces: 只展示必要决策、真实结果和可行动错误的卡片体系。

- [ ] **Step 1: 从源头减少无效内部调用**
  不因错误提示词或门禁产生启动后跳过、条件确认失败后重复调用等无效过程。

- [ ] **Step 2: 内部恢复保持静默**
  内部跳过、参数纠正、自动重试和状态同步不生成用户卡片；真实用户拒绝和真实失败不得借此隐藏。

- [ ] **Step 3: 清退技术黑话**
  移除错误码、内部工具名、defCode、草案、批次、回灌和其他开发/测试话术，保留安全业务原因。

- [ ] **Step 4: 收敛最终回复**
  同一结果只表达一次；不重复任务号、配置项、条件、当前步骤和“如需开始请告诉我”等无效引导。

- [ ] **Step 5: 核对真实状态**
  卡片“已完成/未执行/已取消/失败”必须与权威状态一致，不通过删卡掩盖状态机问题。

### Task 7: 修复输入组件聚焦光环裁切

**Files:**
- Modify: 具体输入组件及其滚动容器样式（以 Task 2 证据为准）

**Interfaces:**
- Consumes: Task 2 的 focus/overflow/padding 盘点。
- Produces: 输入、文本域、选择和上传区域在各交互状态下完整可见的焦点指示。

- [ ] **Step 1: 复现聚焦裁切**
  记录普通、长文本、滚动和窄屏下光环被截断的区域。

- [ ] **Step 2: 最小调整现有间距或裁切**
  使用 Tailwind/现有组件能力，保留布局、滚动和卡片限高，不新增手写组件或无关样式。

- [ ] **Step 3: 浏览器核对**
  鼠标、键盘 focus-visible、长内容、滚动及窄屏下光环完整，无双滚动条和布局跳动。

### Task 8: 全场景独立验收、账本与提交

**Files:**
- Update: `docs/verification/AI对话独立复测清单-2026-11-05.md`
- Update: `docs/verification/AI对话独立复测清单-2026-11-05.md`
- Update: `docs/verification/本轮整改变更账本-2026-11-08.md`
- Update: `docs/delivery/development-standards-review.json`

**Interfaces:**
- Consumes: Task 3–7 的最终代码与定向证据。
- Produces: 可审查的提交、真实通过/失败/未执行边界及推送结果。

- [ ] **Step 1: 独立静态复审**
  逐文件、逐 hunk 检查状态机、HITL、文案、异常、Redis、JSON、集合、日志和数据库白名单。

- [ ] **Step 2: 执行构建门禁**
  生产代码编译通过；压测/独立验证脚本语法检查通过；不执行批量或循环单元测试。

- [ ] **Step 3: DeepSeek 全场景验收**
  每个场景独立执行，覆盖 Task 1 矩阵；任何断轮、无效卡、技术黑话、错误按钮或错误终态均判 FAIL。

- [ ] **Step 4: 复核服务日志**
  ERROR/WARN 逐项分类，真实问题继续修复；不得把预期控制流、主动断流与真实故障混为一谈。

- [ ] **Step 5: 清理与证据**
  清理自建任务、会话、附件和反馈，不触碰其他并发数据；记录 runId、HTTP/SSE、真实作业、历史和截图证据。

- [ ] **Step 6: 提交并推送**
  开发规范七域记录覆盖全部暂存文件，密钥扫描和 `node scripts/ci/check-d6-sync.mjs --standards` 通过，不绕过 pre-commit；核对 `HEAD == origin/main`。

## 完成判定

只有同时满足以下条件，才可宣布本次治理完成：

- “创建导出任务”“导出 CNY 货币字典”“导出货币字典”均无需第二次聊天输入即完成同轮真实闭环。
- 必要的 HITL 和表单仍然存在且文案正确，批准/提交后自动续跑。
- 没有内部产生的“已跳过/未执行”卡，没有技术错误码、内部工具名、草案/批次和重复状态说明。
- 空白条件直接按全量继续，真实非空条件正确生效，非法条件不被伪装成全量。
- 拒绝、取消、真实失败和未知状态保持真实，未被静默恢复机制吞掉。
- 所有涉及的按钮均与历史正常文案及实际动作一致，无遗漏、无擅改。
- 输入组件聚焦光环完整，滚动和窄屏下无裁切、无双滚动条。
- 静态复审和生产编译通过，目标真实场景通过；其他环境明确保留 `NOT_RUN`，不宣称全量全环境通过。
