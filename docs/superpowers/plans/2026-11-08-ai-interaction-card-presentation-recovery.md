# AI Interaction Card Presentation Recovery Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在不改变业务状态机和确认门的前提下，实现长多选批量操作、短卡头、统一待操作、静态交互终态及可恢复的动态业务快照。

**Architecture:** 复用现有 FormRenderer、BusinessCardHeader、ToolPart、preflight 和 store 恢复通道。交互卡只呈现用户意图和决定，执行结果继续由既有执行卡、任务中心或最终回复承担；不新增组件、依赖或供应商/业务特判。

**Tech Stack:** Vue 3、TypeScript、Tailwind CSS、现有 i18n 与 AiPanel store。

**Spec:** `docs/research/分析-AI交互卡选择表头状态与内容治理-2026-11-08.md`

## Global Constraints

- 普通测试代码不得修改；施工阶段不执行普通测试，生产构建必须实际通过。
- 等待用户统一中文“待操作”、英文 `Action required`；不使用“待提交”。
- HITL：已批准/已拒绝；表单：已提交/已取消；预览：已应用/已取消；上传：已上传/已取消。
- 在途只显示按钮忙碌并禁用，卡头保持待操作；未受理不得伪造终态。
- 后续执行中、完成、失败、未执行不得改写交互卡决定。
- 卡头默认无副标题，完整业务信息只在卡体出现一次；不使用 32 字重复标题。
- 长多选指当前可解析选项超过 6 项；`maxItems` 小于选项数时不得提供全选，也不得静默选前 N 项。
- 当前轮操作后保持展开，历史已决卡可默认折叠；恢复不得出现已批准却待操作且无按钮。
- 每批独立关联审查、生产构建、规范门禁通过后提交推送；动态验收统一为 NOT_RUN，直到 DeepSeek 按 C-127～C-129 实测。
- 排除 `docs/demo/README.md`、`docs/test-cases.md`、`docs/research/设计-AI卡片治理-2026-11-06.md` 既有非目标本地修改。

## Review Focus

- `maxItems`、空选项、加载失败、只读/禁用和级联刷新不得产生非法全选或空白假状态；由 Task 1 的 C-127 覆盖。
- 长中文/英文任务名和任务号不得重新进入卡头或只能依赖 tooltip；由 Task 2 的 C-128 覆盖。
- 点击后未受理、受理后执行失败、刷新恢复不得伪造已提交或把已批准变成待操作；由 Task 2/3 的 C-129 覆盖。
- `conditionSummary`、`publishMode`、`arguments/preflight` 不得在确认或恢复时丢失；由 Task 2/3 的 C-128 覆盖。
- 普通工具执行状态可以变化，但不得反向修改交互卡；由 Task 2 的 C-129 覆盖。

---

### Task 1: 长多选与共享交互文案

**Files:**
- Modify: `frontend/src/components/AiPanel/FormRenderer.vue`
- Modify: `frontend/src/locales/zh-CN.json`
- Modify: `frontend/src/locales/en-US.json`

**Interfaces:**
- Consumes: `optionsOf(field)`、`setValue(key, value)`、现有 `maxItems`、只读/禁用和级联草稿路径。
- Produces: 超过 6 项多选的三态全选与组内计数；共享状态键 `ai.card.status.actionRequired`（待操作 / Action required）及全选所需新键。Task 2 只引用这些键，不再编辑 locales。

- [ ] **Step 1: 实现通用多选组语义与批量操作**
  - 用 `fieldset/legend` 或等价组语义关联标题、提示和错误，首错聚焦真实控件。
  - 当前可解析选项超过 6 项且未受 `maxItems`、加载、只读或禁用限制时显示三态全选和已选 X/Y。
  - 部分选择点击后按 `optionsOf(field)` 顺序写完整值数组；全部选择点击后写 `[]`；复用 `setValue`，不新增业务通道。
- [ ] **Step 2: 实现动态选项真实状态**
  - 加载中、加载失败和空选项分别呈现，不互相冒充；失败保留简洁重试入口。
  - 动态刷新后按现有级联规则重算全选状态。
- [ ] **Step 3: 补齐共享中英文键**
  - 增加待操作、全选、已选计数、加载/失败/重试等必要键，不增加卡头副标题或结果型按钮。
- [ ] **Step 4: 构建与报告**
  - 运行 `node scripts/build.mjs`，记录真实结果；标准 `pnpm build` 的既有测试类型错误不得掩盖。
  - C-127 动态验收记 NOT_RUN，报告写入本计划 SDD workspace。

### Task 2: 卡头、静态状态和卡体业务快照

**Files:**
- Modify: `frontend/src/components/AiPanel/AiPanel.vue`
- Modify: `frontend/src/components/AiPanel/BusinessCardHeader.vue`
- Modify: `frontend/src/components/AiPanel/cardPresentation.ts`
- Modify: `frontend/src/components/AiPanel/SheetPreviewCard.vue`
- Modify: `frontend/src/components/AiPanel/UploadHandoffCard.vue`
- Modify: `frontend/src/utils/blocks.ts`

**Interfaces:**
- Consumes: Task 1 的 `ai.card.status.actionRequired` 及既有 `ToolPart.decision/status/preflight/args`。
- Produces: 统一待操作、各交互卡静态终态、短卡头和无副标题布局；不再把执行状态映射进交互卡。

- [ ] **Step 1: 统一状态呈现**
  - 所有等待用户的交互卡显示待操作；按钮在途只忙碌禁用，受理后直接进入对应终态。
  - HITL 的批准、拒绝不再被重译为已完成、未执行或执行中；移除 `approved + running → running` 的呈现特判。
  - 后续执行结果不改变交互卡；真实取消、失效保留。
- [ ] **Step 2: 收敛卡头与卡体**
  - 卡头只保留短动作/用途、状态和展开控件，默认取消副标题。
  - 任务名、任务号、配置编码、条件、发布模式、文件名、写表范围和提交值在卡体完整展示一次。
  - 移除 32 字重复标题及上传/表单/预览等重复信息和重复状态词。
- [ ] **Step 3: 保留动态快照与当前轮展开**
  - `conditionSummary`、`publishMode` 等已有 preflight 信息必须可见，不过滤必要业务条件。
  - 当前轮操作后保持展开，历史已决卡可默认折叠；不得通过隐藏真实错误减少状态。
- [ ] **Step 4: 构建与报告**
  - Task 1 locales 已合入后运行 `node scripts/build.mjs`。
  - C-128/C-129 动态验收记 NOT_RUN，报告写入本计划 SDD workspace。

### Task 3: 已决卡恢复完整性

**Files:**
- Modify: `frontend/src/stores/ai.ts`
- Modify: `frontend/src/utils/parts.ts`

**Interfaces:**
- Consumes: 历史 `arguments/preflight/irreversible`、已批准/拒绝/取消和 `requiresAction` 真实快照。
- Produces: 恢复后保留业务快照和真实决定，不产生已批准却待操作且无按钮的假状态。

- [ ] **Step 1: 修复已决卡物化**
  - `materializeRestoredDecidedCard` 回填快照已有的 `arguments/preflight/irreversible`，保持字段白名单和敏感信息边界。
- [ ] **Step 2: 修复历史决策映射**
  - 已批准不得映射成待操作；已批准但尚未取得执行终态仍保持已批准，不伪造执行成功。
  - 只有真实待用户操作且 `requiresAction=true` 才显示待操作和按钮。
- [ ] **Step 3: 构建与报告**
  - 运行 `node scripts/build.mjs`；C-128/C-129 动态验收记 NOT_RUN。

### Task 4: 分批审查、提交与真实验收

**Files:**
- Review only: 上述生产文件及 `docs/delivery/development-standards-review.json`
- Verify: `docs/verification/AI对话独立复测清单-2026-11-05.md`

- [ ] **Step 1: 独立关联审查**
  - 每批逐 hunk 检查共享状态映射、恢复、敏感字段、执行隔离和既有执行卡，非施工者给出 APPROVE/REQUEST_CHANGES。
- [ ] **Step 2: 生产构建与提交**
  - 最终差异运行 `node scripts/build.mjs` 和 `node scripts/ci/check-d6-sync.mjs --standards`。
  - 分批提交推送；不得把标准 `pnpm build` 的既有失败写成通过。
- [ ] **Step 3: DeepSeek 真实验收**
  - 按 C-115～C-129 新口径及原场景次数执行；每轮保存真实输入、截图、DOM/Network、SSE/history、Redis、日志和业务副作用。
  - 发现问题继续最小修复、独立复审、生产构建和提交；未达次数保持 NOT_RUN。
