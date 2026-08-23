# 用户交互机制设计 + 进度条同步修复

## Context（为什么做这个改动）

用户反馈两个问题：

1. **进度条不同步**："明明到选择配置项界面了，顶部进度条还在第一步"——已进入 SELECT_DEFS 界面，但 StepHeader 的 el-steps 仍停留在第一步。
2. **缺少用户交互机制**：当前 AI 只能通过工具"执行动作"（导航/选择/写表格），无法"向用户收集输入"。用户要求支持输入框、单选、多选、按钮及组合表单，并希望分析业界做法后给出设计。

目标：① 修复进度条间歇性不同步；② 新增 `collect_user_input` 工具，让 AI 能通过 Schema-driven 表单主动向用户收集结构化输入，复用现有 pause-resume 机制闭环。

---

## 一、进度条不同步：根因与修复

### 根因分析（代码 + 数据 + 浏览器复现三方印证）

- 浏览器当前复现任务 #65（scenario=EXPORT, currentStep=SELECT_DEFS）进度条**显示正确**（第2步/4步），说明正常路径无 bug。
- **间歇性根因**：当任务 `scenario=null`（未选场景）但 `currentStep` 被设为 SELECT_DEFS 等后续步骤时，`taskStore.steps` getter 返回空数组（`scenarioMeta.scenarioSteps[null]` → `undefined` → `[]`），el-steps 不渲染任何节点，进度条显示异常（"步骤 1/0"）。
  - 触发路径：AI 调 `navigate_step({step:'SELECT_DEFS'})` 不带 scenario；后端 `TaskService.gotoStep` 在 scenario=null 时跳过校验直接 `setCurrentStep(step)`，破坏了状态机语义（步骤序列由场景决定）。
- 次要：StepHeader 无兜底，steps 为空时仍显示"步骤 1/0"。

### 修复方案

| # | 文件 | 改动 |
|---|------|------|
| F1 | [TaskService.java](file:///e:/workspace/trae/hello-world/ai-example/ai-service/src/main/java/com/example/ai/service/TaskService.java) `gotoStep` | 当 `scenario==null && !"SELECT_SCENARIO".equals(step)` 时抛错"请先选择场景再进入后续步骤"，阻止跨场景跳步 |
| F2 | [frontend-tool-registry.js](file:///e:/workspace/trae/hello-world/ai-example/ai-ui/src/utils/frontend-tool-registry.js) `navigate_step.execute` | 防御：当 `step!=='SELECT_SCENARIO'` 且 `taskStore.scenario` 为空时，若 `args.scenario` 有值则先 selectScenario，否则返回 `{ok:false, message:'当前任务尚未选择场景，无法跳转到该步骤'}` |
| F3 | [StepHeader.vue](file:///e:/workspace/trae/hello-world/ai-example/ai-ui/src/components/StepHeader.vue) | 兜底：当 `steps` 为空但 `currentStep` 非空时，渲染单节点"当前：{stepLabel}"，避免"步骤 1/0"误导 |

---

## 二、用户交互机制设计

### 业界做法分析

| 方案 | 代表产品 | 核心思想 | 与本项目的契合度 |
|------|----------|----------|------------------|
| **Adaptive Cards** | Microsoft Copilot / Bot Framework | JSON Schema 描述卡片（Input.Text/ChoiceSet/Toggle），跨端渲染 | 高（Schema-driven + 卡片化） |
| **JSON Schema Form** | react-jsonschema-form / Form.io | JSON Schema 定义字段→自动渲染控件+校验 | 高（字段定义即渲染契约） |
| **Slack Block Kit Modals** | Slack | Blocks 组合收集表单，提交回灌 | 中（理念一致，协议不同） |
| **HumanInputTool** | LangChain / AutoGen | agent 主动暂停请求用户输入，拿到值后继续 | 高（pause-resume 一致） |
| **多轮单字段问答** | ChatGPT/Claude | 一次问一个字段，逐步收集 | 中（对话式，但配置场景效率低） |

**业界共识**：Schema-driven 表单卡片 + agent pause-resume。agent 通过工具调用"声明"需要收集的字段，前端按 schema 渲染表单，用户填写提交后回灌，agent 继续。这恰好复用本项目已有的 FRONTEND 工具暂停-恢复机制。

### 设计方案：新增 `collect_user_input` 工具

**设计要点**：
- `fields` 数组可含 1~N 个字段 → AI 自主决定单轮多字段（表单）还是单字段（多轮问答），灵活覆盖两种交互模式
- `mode=INPUT`（新增）→ 前端渲染 SchemaFormRenderer，不走 AUTO 自动执行也不走 CONFIRM 确认按钮
- 用户提交的表单值作为 `result.data` 回灌，AI 据此继续推理

**支持的控件类型**（覆盖用户需求：输入框/单选/多选/按钮/组合表单）：

| type | Element Plus 控件 | 说明 |
|------|-------------------|------|
| `text` | el-input | 单行输入框 |
| `textarea` | el-input(type=textarea) | 多行输入框 |
| `number` | el-input-number | 数字输入 |
| `boolean` | el-switch | 开关 |
| `single_select` | el-select | 下拉单选 |
| `multi_select` | el-select(multiple) | 下拉多选 |
| `button_group` | el-radio-group(button) | 按钮组单选（少量选项快速点选） |
| `date` | el-date-picker | 日期选择 |

每个字段支持：`key/label/type/required/default/placeholder/description/options/min/max/pattern`

---

## 三、实施步骤

### 后端（ai-service）

| # | 文件 | 改动 |
|---|------|------|
| B1 | [tools.yaml](file:///e:/workspace/trae/hello-world/ai-example/ai-service/src/main/resources/tools.yaml) | 新增 `collect_user_input` 工具定义（kind=FRONTEND, autoExec=false, needConfirm=false, 全场景全步骤，parameters 含 formTitle/submitLabel/fields 数组） |
| B2 | [scenarios.yaml](file:///e:/workspace/trae/hello-world/ai-example/ai-service/src/main/resources/scenarios.yaml) | 每个步骤的 tools 列表追加 `collect_user_input` |
| B3 | [FrontendTools.java](file:///e:/workspace/trae/hello-world/ai-example/ai-service/src/main/java/com/example/ai/tool/FrontendTools.java) `kindOf` | switch 加 `case "collect_user_input" -> FRONTEND`；`defaultNeedConfirm` 返回 false |
| B4 | [AiService.java](file:///e:/workspace/trae/hello-world/ai-example/ai-service/src/main/java/com/example/ai/service/AiService.java) `convertRawToolCallToFrontendDTO` | mode 注入逻辑扩展：`"collect_user_input".equals(c.name) ? "INPUT" : (原 AUTO/CONFIRM 逻辑)` |

### 前端（ai-ui）

| # | 文件 | 改动 |
|---|------|------|
| F4 | **新增** `src/components/SchemaFormRenderer.vue` | 接收 `fields` props，按 type 映射 Element Plus 控件渲染；校验 required/min/max/pattern；emit `submit(values)` / `cancel` |
| F5 | [frontend-tool-registry.js](file:///e:/workspace/trae/hello-world/ai-example/ai-ui/src/utils/frontend-tool-registry.js) | 注册 `collect_user_input`：`needConfirm=false`，`execute` 不执行副作用，直接返回 `{ok:true, message:'已收集用户输入', data: args}`（实际值由 AiPanel 表单提交时注入） |
| F6 | [AiPanel.vue](file:///e:/workspace/trae/hello-world/ai-example/ai-ui/src/components/AiPanel.vue) | ① `isAutoExec` 排除 `mode==='INPUT'`；② 工具卡片渲染新增 INPUT 分支：用 `<SchemaFormRenderer>` 替代确认按钮，提交时收集 values 调 `executeAndResume(message, tc, false, values)`；③ `executeAndResume` 增加 `formData` 参数，回灌 `result.data=formData` |
| F7 | 进度条修复 F1/F2/F3（见上表） |

### 复用的现有机制（不重造轮子）
- pause-resume：`saveAgentState` + `submitToolResult`（[AiService.java](file:///e:/workspace/trae/hello-world/ai-example/ai-service/src/main/java/com/example/ai/service/AiService.java) L318-323, [ai.js](file:///e:/workspace/trae/hello-world/ai-example/ai-ui/src/stores/ai.js) `submitToolResult`）
- 工具信任分级：`ToolDiscoveryService.isAutoExec/defaultNeedConfirm`（按 YAML 读取，新增工具自动生效）
- 工具卡片渲染框架：AiPanel 现有 `isAutoExec/resolveNeedConfirm` 分支，INPUT 作为第三分支插入

---

## 四、验证方案（端到端闭环）

1. **编译重启**：后端 maven 编译 + 重启 8081；前端 yarn dev 热更新
2. **进度条验证**：
   - 创建新任务（scenario=null）→ 在 AI 对话输入"我要导出配置"→ AI 应先调 selectScenario/navigate_step 选场景，不应直接跳 SELECT_DEFS
   - 任意时刻刷新页面，进度条与路由界面一致
3. **表单交互验证**：
   - AI 对话输入"导出所有配置，文件名叫 sales_report"→ AI 应调 `collect_user_input`（若需要补充参数）或直接执行
   - 触发表单的典型指令："帮我新建一个配置项"（AI 用 collect_user_input 收集 code/name/字段）→ 前端渲染表单卡片 → 填写提交 → 后端恢复 loop → AI 继续执行
   - 验证控件：单选(button_group)、多选(multi_select)、文本输入(text)、必填校验
4. **回灌闭环**：表单提交后 AI 拿到 values 继续推理，最终给出结果或下一轮工具调用
5. **浏览器自动化**：用 browser_use 打开 5173，输入触发 collect_user_input 的指令，截图表单卡片，填写提交，验证 AI 后续响应
