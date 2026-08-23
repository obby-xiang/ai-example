# AI 辅助配置管理系统 — 设计与实现文档

> 版本：v1.1（含 collect_user_input 用户交互机制）
> 更新日期：2026-08-22

---

## 目录

1. [系统概览](#1-系统概览)
2. [核心架构设计](#2-核心架构设计)
3. [工具体系](#3-工具体系)
4. [用户交互机制设计](#4-用户交互机制设计)
5. [前端架构](#5-前端架构)
6. [后端实现](#6-后端实现)
7. [关键接口契约](#7-关键接口契约)
8. [进度条同步问题与修复](#8-进度条同步问题与修复)
9. [经验沉淀](#9-经验沉淀)
10. [运行与验证](#10-运行与验证)

---

## 1. 系统概览

### 1.1 项目定位与业务目标

本系统是一个 **AI 辅助的配置管理平台**，将大语言模型（LLM）嵌入到配置项的导出 / 导入 / 新增 / 修改四类业务流程中。用户通过左右双面板布局操作：左侧是多步骤任务向导（4 个业务场景的状态机驱动），右侧是常驻的 AI 对话面板。AI 不仅能回答问题，还能通过 **Function Calling 工具** 直接操作工作区（导航步骤、选择配置项、改写表格、执行流程），实现"对话即操作"。

**核心业务场景**：

| 场景 | 步骤序列 | 说明 |
|------|----------|------|
| EXPORT 导出配置 | SELECT_SCENARIO → SELECT_DEFS → QUERY_COND → RESULT | 选配置项、设条件、导出 |
| IMPORT 导入配置 | SELECT_SCENARIO → VIEW_DEFS → PRECHECK → REVIEW → PUBLISH | 查看项、预检查、复核、发布 |
| ADD 新增配置 | SELECT_SCENARIO → VIEW_DEFS → PRECHECK → REVIEW → PUBLISH | 定义项、预检查、复核、发布 |
| MODIFY 修改配置 | SELECT_SCENARIO → VIEW_DEFS → PRECHECK → REVIEW → PUBLISH | 编辑数据、预检查、复核、发布 |

### 1.2 技术栈

| 层 | 选型 | 说明 |
|----|------|------|
| 后端 | Spring Boot 3.5.14 + JDK 21 | 端口 8081（8080 被占用） |
| 前端 | Vue 3 (Composition API) + Vite | 端口 5173，包管理 yarn |
| 表格 | GrapeCity SpreadJS | 利用原生校验/必填/下拉，减少自写代码 |
| 数据库 | H2 嵌入式（文件存储） | `jdbc:h2:file:./data/ai_config_db;DB_CLOSE_DELAY=-1;MODE=MySQL`，`ddl-auto:update` 自动迁移 |
| AI | DeepSeek（OpenAI 规范） | `https://api.deepseek.com/chat/completions`，模型 `deepseek-v4-flash` |
| 构建 | 后端 Maven / 前端 yarn | Maven 路径 `D:\Program Files\JetBrains\...\maven3\` |

### 1.3 整体架构

```
┌─────────────────────────────────────────────────────────────────┐
│                      浏览器（Vue3 SPA）                          │
│  ┌──────────────────────┐    ┌─────────────────────────────────┐ │
│  │  左：任务向导         │    │  右：常驻 AI 面板 (AiPanel)      │ │
│  │  StepHeader 进度条    │    │  ┌───────────────────────────┐ │ │
│  │  + wizard 视图        │◄──►│  │ 消息列表 + 工具卡片        │ │ │
│  │  (SpreadJS 表格)      │    │  │  AUTO/CONFIRM/INPUT 三分支 │ │ │
│  │  Pinia: task/config   │    │  │ SchemaFormRenderer 表单    │ │ │
│  └──────────┬───────────┘    │  └──────────┬────────────────┘ │ │
│             │                 │  Pinia: ai  │                  │ │
│             │                 └─────────────┼──────────────────┘ │
│             │  路由切换不销毁右面板           │                    │
└─────────────┼─────────────────────────────────┼──────────────────┘
              │ HTTP (axios)                    │
              ▼                                 ▼
┌─────────────────────────────────────────────────────────────────┐
│                    后端 ai-service (Spring Boot)                 │
│  ┌──────────────┐  ┌──────────────┐  ┌────────────────────────┐ │
│  │ TaskController│  │ AiController │  │ ConfigController        │ │
│  └──────┬───────┘  └──────┬───────┘  └────────────────────────┘ │
│         ▼                  ▼                                      │
│  ┌──────────────┐  ┌──────────────────────────────────────────┐  │
│  │ TaskService  │  │ AiService (Agent Loop)                    │  │
│  │ 状态机/CRUD  │  │  while(有tool_calls){                     │  │
│  └──────┬───────┘  │    调 DeepSeek → 解析 tool_calls         │  │
│         │          │    BACKEND 工具同步执行                    │  │
│         ▼          │    FRONTEND 工具 → 暂停+resumeToken        │  │
│  ┌──────────────┐ │  }                                         │  │
│  │ H2 + JPA     │ │  PlannerService (Plan-Execute Phase1)      │  │
│  │ Task/AgentState│ └──────────────────────────────────────────┘  │
│  │ ConfigDef/Row │  ┌──────────────────────────────────────────┐  │
│  └──────────────┘  │ ToolDiscoveryService (YAML 驱动发现)      │  │
│                    │  tools.yaml + scenarios.yaml              │  │
│                    └──────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────┘
                              │ RestClient
                              ▼
                    ┌──────────────────┐
                    │  DeepSeek API    │
                    │ (OpenAI 规范)    │
                    └──────────────────┘
```

### 1.4 目录结构

**后端** [ai-service/src/main](file:///e:/workspace/trae/hello-world/ai-example/ai-service/src/main)：

```
java/com/example/ai/
├── AiServiceApplication.java        # 启动类
├── config/      StartupInitializer, WebConfig
├── controller/  AiController, ConfigController, TaskController
├── dto/         AiDTO, ConfigDTO, R, TaskDTO
├── entity/      AgentState, AiChatMessage, ConfigDataRow, ConfigDefinition, Task
├── repository/  *Repository (JPA)
├── service/     AiService, ConfigDataService, ConfigDefinitionService,
│                DataSeedService, PlannerService, TaskService
└── tool/        FrontendTools, ScenarioDefinition, ToolDefinition, ToolDiscoveryService
resources/
├── application.yml
├── tools.yaml        # 11 个工具定义（Schema + 信任分级）
└── scenarios.yaml    # 4 场景状态机
```

**前端** [ai-ui/src](file:///e:/workspace/trae/hello-world/ai-example/ai-ui/src)：

```
src/
├── App.vue                          # 根组件（loadMeta + currentStep 路由联动）
├── main.js
├── api/      ai.js, config.js, request.js, task.js
├── components/  AiPanel.vue, SchemaFormRenderer.vue, SpreadSheet.vue, StepHeader.vue
├── router/  index.js                # stepToRoute 映射 + beforeEach 守卫
├── stores/  ai.js, config.js, task.js   # Pinia
├── utils/   frontend-tool-registry.js    # 前端工具执行逻辑
└── views/
    ├── DashboardView.vue
    └── wizard/  StepSelectScenario, StepSelectDefs, StepViewDefs,
                 StepQueryCond, StepPrecheck, StepReview, StepPublish, StepResult
```

---

## 2. 核心架构设计

### 2.1 后端 Agent Loop

[AiService.java](file:///e:/workspace/trae/hello-world/ai-example/ai-service/src/main/java/com/example/ai/service/AiService.java) 的核心是一个 `while` 循环，模拟 ReAct 模式：

```
用户消息 → 调 DeepSeek(带 tools) → 响应含 tool_calls?
   ├─ 无 → 返回最终文本，done=true
   └─ 有 → 取第一个 tool_call
           ├─ BACKEND 工具(list_config_defs 等) → 同步执行 → 结果作 tool message 回灌 → 继续循环
           └─ FRONTEND 工具(navigate_step 等) → convertRawToolCallToFrontendDTO
               → saveAgentState(resumeToken) → 返回 done=false + toolCalls → 暂停循环
```

**关键设计**：
- **串行执行**：每轮只处理第一个 tool_call（避免多 tool_call 时 tool_call_ids 与 tool message 数量不匹配导致 400，见 [经验 9.1](#9-经验沉淀)）
- **BACKEND 工具**在 loop 内同步执行，结果作为 `tool` role message 回灌，模型可自主链式查询（如 `list_config_defs → get_config_def`）
- **FRONTEND 工具**暂停 loop，等前端执行后通过 `/api/ai/tool-result` 回灌恢复

### 2.2 Pause-Resume 机制

前端工具的执行不在后端发生（后端不操作 SpreadJS / 路由 / Pinia），而是采用 **暂停-恢复** 协议：

1. 后端遇到 FRONTEND 工具时，生成 `resumeToken`，把 `AgentState`（messages、pendingToolCalls、workflow 上下文、plan、stepArtifacts、scenario、step、mode）序列化到 H2，24h 过期
2. 返回 `ChatResp { done:false, resumeToken, toolCalls:[{callId, toolName, args, mode, autoExec, needConfirm, ...}] }`
3. 前端按 `mode` 渲染卡片，用户执行后 `POST /api/ai/tool-result { resumeToken, callId, result }`
4. 后端用 `resumeToken` 精确查找 `AgentState`，恢复 messages，追加 tool result，继续 agent loop

**关键文件**：
- 后端：[AiService.java](file:///e:/workspace/trae/hello-world/ai-example/ai-service/src/main/java/com/example/ai/service/AiService.java) `submitToolResult` / `saveAgentState`
- 状态实体：[AgentState.java](file:///e:/workspace/trae/hello-world/ai-example/ai-service/src/main/java/com/example/ai/entity/AgentState.java)
- 前端：[ai.js](file:///e:/workspace/trae/hello-world/ai-example/ai-ui/src/stores/ai.js) `submitToolResult`

### 2.3 工具信任分级（三档 + mode）

工具按风险分三档，由 `mode` 字段驱动前端渲染分支：

| 信任级别 | 标签组合 | mode | 前端行为 | 典型工具 |
|----------|----------|------|----------|----------|
| 自动执行 | `autoExec=true, needConfirm=false` | AUTO | watch 自动执行（dedup Set 防循环） | navigate_step, select_definitions |
| 用户确认 | `needConfirm=true` | CONFIRM | 显示"确认执行"按钮 | table_batch_set_field, run_flow |
| 二次确认 | `requireDoubleConfirm=true` | CONFIRM | 工具卡片按钮 + ElMessageBox 二次弹窗 | run_flow |
| 表单收集 | `autoExec=false, needConfirm=false`（collect_user_input 专属） | INPUT | 渲染 SchemaFormRenderer 表单 | collect_user_input（本轮新增） |

mode 注入逻辑见 [AiService.convertRawToolCallToFrontendDTO](file:///e:/workspace/trae/hello-world/ai-example/ai-service/src/main/java/com/example/ai/service/AiService.java)：

```java
String mode = "collect_user_input".equals(c.name)
        ? "INPUT"
        : ((autoExec && !needConfirm) ? "AUTO" : "CONFIRM");
```

### 2.4 场景状态机（Progressive Disclosure）

[scenarios.yaml](file:///e:/workspace/trae/hello-world/ai-example/ai-service/src/main/resources/scenarios.yaml) 把每个业务场景定义为状态图：

- 每个节点（步骤）声明自己的 `tools` 列表
- AI **永远只看到当前步骤允许的工具**（[ToolDiscoveryService.listAvailableTools](file:///e:/workspace/trae/hello-world/ai-example/ai-service/src/main/java/com/example/ai/tool/ToolDiscoveryService.java) 按 `scenario+step` 过滤）
- 状态机维护全局连贯性，AI 不决定路由（路由由 `navigate_step` 工具 + 后端 `TaskService` 状态机共同保证）

**收益**：token 优化（不注入全量工具）+ 实时数据（按需查询）+ 防止 AI 在错误步骤调用错误工具。

### 2.5 Plan-and-Execute 工作流

[PlannerService.java](file:///e:/workspace/trae/hello-world/ai-example/ai-service/src/main/java/com/example/ai/service/PlannerService.java) 实现业界 Plan-and-Execute 模式（对照 LangGraph Plan-and-Execute 子图、Anthropic 三智能体 Harness）：

1. **Plan 阶段**：调 LLM（`response_format=json_object` 强制 JSON）生成完整 plan，注入当前场景状态图 + 工具元数据
2. **Execute 阶段**（AiService）：逐步执行，每步基于前一步结果推理
3. 用户在 Plan 阶段 **一次确认整个计划**，代替每步确认

**兜底**：LLM 失败时用状态图直接生成默认 plan（`buildFallbackPlan`），保证可用性。

### 2.6 状态持久化

- **Task**：从创建（DRAFT）即持久化，刷新可恢复（[TaskService.create](file:///e:/workspace/trae/hello-world/ai-example/ai-service/src/main/java/com/example/ai/service/TaskService.java)）
- **AgentState**：messages、pendingToolCalls、workflow 上下文、plan、stepArtifacts、scenario、step、mode 序列化到 H2，24h 过期
- **AiChatMessage**：对话历史持久化，支持按 taskId/sessionId 查询
- `ddl-auto:update` 自动迁移新字段，无需手动建表

---

## 3. 工具体系

### 3.1 工具清单（11 个）

| # | 工具名 | kind | autoExec | needConfirm | 适用步骤 | 作用 |
|---|--------|------|----------|-------------|----------|------|
| 1 | navigate_step | FRONTEND | ✓ | ✗ | 全步骤 | 跳转工作区步骤/切换场景 |
| 2 | get_workspace_state | BACKEND | ✓ | ✗ | 全步骤 | 查询当前工作区状态 |
| 3 | list_config_defs | BACKEND | ✓ | ✗ | SELECT_DEFS/VIEW_DEFS/QUERY_COND | 列配置定义 |
| 4 | get_config_def | BACKEND | ✓ | ✗ | 同上 | 取配置定义详情 |
| 5 | select_definitions | FRONTEND | ✓ | ✗ | EXPORT/SELECT_DEFS | 选择配置项 |
| 6 | confirm_complete | FRONTEND | ✓ | ✗ | RESULT/PUBLISH | 确认完成流程 |
| 7 | table_batch_set_field | FRONTEND | ✗ | ✓ | VIEW_DEFS/QUERY_COND | 批量设字段 |
| 8 | table_delete_rows | FRONTEND | ✗ | ✓ | MODIFY/VIEW_DEFS | 删除行 |
| 9 | table_replace_values | FRONTEND | ✗ | ✓ | VIEW_DEFS/QUERY_COND | 替换值 |
| 10 | run_flow | FRONTEND | ✗ | ✓+二次 | RESULT/PUBLISH | 执行导出/发布流程 |
| 11 | **collect_user_input** | FRONTEND | ✗ | ✗ | 全步骤 | **Schema 表单收集用户输入（本轮新增）** |

### 3.2 YAML 驱动的工具定义

工具定义集中在 [tools.yaml](file:///e:/workspace/trae/hello-world/ai-example/ai-service/src/main/resources/tools.yaml)，包含：`name / description / kind / autoExec / needConfirm / requireDoubleConfirm / scenarios / steps / parameters(JSON Schema)`。

**单一事实来源**：后端 [ToolDiscoveryService](file:///e:/workspace/trae/hello-world/ai-example/ai-service/src/main/java/com/example/ai/tool/ToolDiscoveryService.java) 启动时加载 YAML，对内提供 `isAutoExec/defaultNeedConfirm/isRequireDoubleConfirm/kindOf/listAvailableTools`，对外暴露原生 OpenAI 协议 `tools` 数组。[FrontendTools.java](file:///e:/workspace/trae/hello-world/ai-example/ai-service/src/main/java/com/example/ai/tool/FrontendTools.java) 仅保留兜底硬编码（YAML 加载失败时降级）。

### 3.3 工具动态发现（MCP tools/list 等价）

`GET /api/ai/tools?scenario=X&step=Y` 返回该步骤可用工具（progressive disclosure）。`format=meta` 返回含 autoExec/needConfirm 元数据，供调试。

### 3.4 后端工具 vs 前端工具执行路径

```
tool_call 进入 agent loop
  ├─ ToolDiscoveryService.kindOf(name) == BACKEND
  │    → 后端同步执行（如 list_config_defs 查 DB）
  │    → 结果作 tool message 回灌 → 继续循环（模型可链式多轮查询）
  └─ kindOf == FRONTEND
       → convertRawToolCallToFrontendDTO（注入 mode/autoExec/needConfirm）
       → saveAgentState + 返回 resumeToken → 暂停
       → 前端执行（路由/Pinia/SpreadJS/表单）
       → POST /api/ai/tool-result 回灌 → 恢复 loop
```

---

## 4. 用户交互机制设计

### 4.1 业界做法分析

在引入 collect_user_input 前，AI 只能"执行动作"（导航/选择/写表格），无法"向用户收集输入"。针对用户需求（输入框、单选、多选、按钮、组合表单），对比业界五种方案：

| 方案 | 代表产品 | 核心思想 | 契合度 | 备注 |
|------|----------|----------|--------|------|
| **Adaptive Cards** | Microsoft Copilot / Bot Framework | JSON Schema 描述卡片（Input.Text/ChoiceSet/Toggle），跨端渲染 | ★★★★★ | Schema-driven + 卡片化，理念最接近 |
| **JSON Schema Form** | react-jsonschema-form / Form.io | JSON Schema 定义字段→自动渲染控件+校验 | ★★★★★ | 字段定义即渲染契约 |
| **Slack Block Kit Modals** | Slack | Blocks 组合收集表单，提交回灌 | ★★★★ | 理念一致，协议不同 |
| **HumanInputTool** | LangChain / AutoGen | agent 主动暂停请求用户输入，拿到值继续 | ★★★★★ | pause-resume 完全一致 |
| **多轮单字段问答** | ChatGPT / Claude | 一次问一个字段，逐步收集 | ★★★ | 对话式，配置场景效率低 |

**业界共识**：**Schema-driven 表单卡片 + agent pause-resume**。agent 通过工具调用"声明"需要收集的字段，前端按 schema 渲染表单，用户填写提交后回灌，agent 继续。这恰好复用本项目已有的 FRONTEND 工具暂停-恢复机制，零新协议。

### 4.2 collect_user_input 工具设计

**设计要点**：
- `fields` 数组可含 1~N 个字段 → AI 自主决定单轮多字段（表单）或单字段（多轮问答），灵活覆盖两种交互模式
- `mode=INPUT`（新增）→ 前端渲染 SchemaFormRenderer，不走 AUTO 自动执行也不走 CONFIRM 确认按钮
- 用户提交的表单值作为 `result.data` 回灌，AI 据此继续推理
- 全场景全步骤可用（任何步骤都可能需要补充参数）

**参数 Schema**（[tools.yaml](file:///e:/workspace/trae/hello-world/ai-example/ai-service/src/main/resources/tools.yaml)）：

```yaml
- name: collect_user_input
  kind: FRONTEND
  autoExec: false
  needConfirm: false
  parameters:
    type: object
    properties:
      formTitle:   { type: string }          # 表单标题
      submitLabel: { type: string, default: 提交 }
      fields:                                # 字段数组
        type: array
        items:
          type: object
          properties:
            key:         { type: string }     # 回灌结果 data 中的 key
            label:       { type: string }
            type:        { enum: [text, textarea, number, boolean,
                                  single_select, multi_select,
                                  button_group, date] }
            required:    { type: boolean }
            default:     {}                  # 默认值
            placeholder: { type: string }
            description: { type: string }    # 帮助文本
            options:                          # 选项列表
              items: { properties: { label, value } }
            min / max / pattern               # 校验
    required: [formTitle, fields]
```

### 4.3 SchemaFormRenderer 组件

[SchemaFormRenderer.vue](file:///e:/workspace/trae/hello-world/ai-example/ai-ui/src/components/SchemaFormRenderer.vue) 按 `type` 映射 Element Plus 控件：

| type | Element Plus 控件 | 校验支持 |
|------|-------------------|----------|
| `text` | el-input | required / min·max 长度 / pattern 正则 |
| `textarea` | el-input(type=textarea, show-word-limit) | 同上 |
| `number` | el-input-number | required / min·max 范围 |
| `boolean` | el-switch | required |
| `single_select` | el-select | required |
| `multi_select` | el-select(multiple) | required（数组非空） |
| `button_group` | el-radio-group + el-radio-button | required（按钮组单选） |
| `date` | el-date-picker(YYYY-MM-DD) | required |

**设计原则**：
- 前端只做 required/min/max/pattern **最小契约校验**，语义校验交给后端/模型
- 提交 `emit('submit', { ...formData })`，取消 `emit('cancel')`
- `fields` 变化时 watch 重新初始化（工具卡片复用场景）

### 4.4 表单提交流程（mode=INPUT 分支）

[AiPanel.vue](file:///e:/workspace/trae/hello-world/ai-example/ai-ui/src/components/AiPanel.vue) 工具卡片渲染三分支：

```vue
<!-- 已执行/取消/失败：状态标签 -->
<template v-if="isToolDone(m, tc)">...</template>

<!-- INPUT 模式：Schema-driven 表单 -->
<template v-else-if="tc.mode === 'INPUT'">
  <SchemaFormRenderer
    :fields="tc.args?.fields || []"
    @submit="(vals) => executeAndResume(m, tc, false, vals)"
    @cancel="executeAndResume(m, tc, true)" />
</template>

<!-- AUTO / CONFIRM 分支（原有） -->
```

`executeAndResume` 增加 `formData` 参数，INPUT 分支**不调用 executeFrontendTool**（表单收集无副作用，值即结果）：

```javascript
async function executeAndResume(message, tc, userCancelled = false, formData = null) {
  let result
  if (userCancelled) {                          // 取消
    result = { ok: false, message: 'user_cancelled', reason: '...' }
    aiStore.setToolCallStatus(tc.callId, 'cancelled')
  } else if (formData !== null) {                // INPUT：表单值直接回灌
    result = { ok: true, message: '已收集用户输入', data: formData }
    aiStore.setToolCallStatus(tc.callId, 'succeeded')
  } else {                                       // 普通 FRONTEND 工具
    result = await executeFrontendTool(tc)
    ...
  }
  await aiStore.submitToolResult(message.resumeToken, tc.callId, result)
}
```

### 4.5 交互时序（端到端）

```
用户："导出配置，文件名叫 sales，格式 csv"
  │
  ▼ POST /api/ai/chat
后端 agent loop → DeepSeek 返回 tool_calls[collect_user_input]
  │  mode=INPUT, args.fields=[{key:filename,type:text}, {key:format,type:button_group}]
  ▼ saveAgentState(resumeToken) → 返回 done=false
前端 AiPanel 检测 mode=INPUT → 渲染 SchemaFormRenderer（输入框 + 按钮组）
  │
  ▼ 用户填写 "sales" / 点 "csv" / 点"提交"
executeAndResume(m, tc, false, {filename:'sales', format:'csv'})
  │
  ▼ POST /api/ai/tool-result { resumeToken, callId, result:{ok:true, data:{...}} }
后端恢复 agent loop → 追加 tool result → DeepSeek 拿到值继续推理
  │  → 可能继续调 run_flow / navigate_step / 或返回最终文本
  ▼ 返回 done=true + AI 文本（基于回灌值）
```

---

## 5. 前端架构

### 5.1 状态管理（Pinia）

| Store | 职责 | 关键 state/getter |
|-------|------|-------------------|
| [task.js](file:///e:/workspace/trae/hello-world/ai-example/ai-ui/src/stores/task.js) | 任务 + 场景元数据 | `currentTask`、`currentStep`、`steps`（按 scenario 取 scenarioSteps）、`scenarioName` |
| [ai.js](file:///e:/workspace/trae/hello-world/ai-example/ai-ui/src/stores/ai.js) | 对话 + 工具执行 | `messages`、`submitToolResult`、`setToolCallStatus`、`tryAutoExecute` |
| config.js | 配置定义/数据行 | SpreadJS 数据源 |

### 5.2 前端工具注册表

[frontend-tool-registry.js](file:///e:/workspace/trae/hello-world/ai-example/ai-ui/src/utils/frontend-tool-registry.js) 与后端工具名 100% 对齐，每个工具定义：`labelText / labelType / needConfirm(args) / validate(args) / execute(args)`。所有副作用（路由跳转、Pinia、SpreadJS、接口请求）集中在此，AiPanel 只展示卡片+触发执行。

**collect_user_input 注册**：

```javascript
collect_user_input: {
  labelText: '表单',
  labelType: 'primary',
  needConfirm: () => false,
  validate(args = {}) {
    // 校验 fields 数组 + 每字段 key/label/type
  },
  // INPUT 模式下 AiPanel 直接回灌表单值，不调用此 execute（兼容兜底）
  async execute(args) { return { ok: true, message: '已收集用户输入', data: args } }
}
```

### 5.3 AiPanel 工具卡片渲染

[AiPanel.vue](file:///e:/workspace/trae/hello-world/ai-example/ai-ui/src/components/AiPanel.vue) 卡片结构：`a-title`（toolTagType/Label）→ `a-impact`（影响摘要）→ `a-params`（参数）→ `a-form`（INPUT 表单）→ `a-btns`（状态/按钮）。

**自动执行 dedup**：watch 监听签名 `${messages.length}|${last.done}|${last.toolCalls.length}`，用 `autoExecutedCallIds` Set 防止 watch 无限循环。

### 5.4 进度条同步机制

[StepHeader.vue](file:///e:/workspace/trae/hello-world/ai-example/ai-ui/src/components/StepHeader.vue) 用 `el-steps`，`currentIndex = displaySteps.indexOf(currentStep)`：

```javascript
// 兜底：scenario 未选或 steps 未加载时，至少渲染当前步骤节点
const displaySteps = computed(() => {
  if (steps.value && steps.value.length) return steps.value
  return currentStep.value ? [currentStep.value] : ['SELECT_SCENARIO']
})
```

[App.vue](file:///e:/workspace/trae/hello-world/ai-example/ai-ui/src/App.vue) watch `currentStep` 变化自动同步路由（`stepToRoute`），实现"任务状态→路由→进度条"三方联动。

---

## 6. 后端实现

### 6.1 分层结构

- **controller**：[AiController](file:///e:/workspace/trae/hello-world/ai-example/ai-service/src/main/java/com/example/ai/controller/AiController.java)（对话/工具/计划/发现）、TaskController、ConfigController
- **service**：AiService（agent loop）、PlannerService（Plan-Execute）、TaskService（状态机）、ConfigDataService、ConfigDefinitionService、DataSeedService
- **tool**：FrontendTools（兜底+影响摘要）、ToolDiscoveryService（YAML 发现）、ToolDefinition、ScenarioDefinition
- **entity/repository**：JPA 实体 + 仓储
- **dto**：AiDTO（ChatReq/ChatResp/FrontendToolCall/PlanStep/ToolResultReq）、R（统一响应）、TaskDTO、ConfigDTO

### 6.2 AiService agent loop 核心路径

1. `chat(ChatReq)`：预存用户消息→构建 messages→进入 loop
2. loop：`buildRawRequestBody`（注入 `thinking={"type":"disabled"}` 关闭思考省 token）→ 调 DeepSeek → 解析 `tool_calls`
3. 无 tool_calls → 返回 done=true
4. 有 tool_calls → 取第一个 → `kindOf` 判 BACKEND/FRONTEND
5. BACKEND → 同步执行 → tool message 回灌 → 继续
6. FRONTEND → `convertRawToolCallToFrontendDTO`（注入 mode/autoExec/needConfirm/requireDoubleConfirm）→ `saveAgentState` → 返回 done=false

### 6.3 convertRawToolCallToFrontendDTO（mode 注入）

```java
boolean autoExec = toolDiscoveryService.isAutoExec(c.name);
boolean needConfirm = toolDiscoveryService.defaultNeedConfirm(c.name);
boolean requireDoubleConfirm = toolDiscoveryService.isRequireDoubleConfirm(c.name);
String mode = "collect_user_input".equals(c.name)
        ? "INPUT"
        : ((autoExec && !needConfirm) ? "AUTO" : "CONFIRM");
```

### 6.4 System Prompt 设计原则

- **只描述能力，不注入全量业务数据**（token 优化 + 实时数据，AI 按需通过工具查询）
- 引导 AI：调 navigate_step 推进流程；有结构化输入需求用 collect_user_input（不要用纯文本提问）；工具链式查询（list→get detail）

### 6.5 任务状态机（TaskService）

[TaskService.java](file:///e:/workspace/trae/hello-world/ai-example/ai-service/src/main/java/com/example/ai/service/TaskService.java) 维护 `SCENARIO_STEPS` 映射，`selectScenario` 自动跳到场景第二步，`gotoStep` 校验步骤属于当前场景（**跨场景跳步防护**，见第 8 章）。

---

## 7. 关键接口契约

### 7.1 AI 接口（[AiController](file:///e:/workspace/trae/hello-world/ai-example/ai-service/src/main/java/com/example/ai/controller/AiController.java)，前缀 `/api/ai`）

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/chat` | 发送消息，返回 `{done, messages?, toolCalls?, resumeToken?}` |
| POST | `/tool-result` | 前端工具执行结果回灌，恢复 agent loop |
| POST | `/plan` | 生成执行计划（Plan-Execute Phase1），返回 `{plan[], summary, isFallback}` |
| GET | `/tools?scenario&step&format` | 工具动态发现（progressive disclosure） |
| GET | `/scenarios` | 列场景状态图 |
| POST | `/auto-prompt` | 工作区事件触发的自动提示 |
| GET | `/history?taskId&sessionId` | 查询历史消息 |
| DELETE | `/history?taskId` | 清空历史 |
| GET | `/health` | 健康检查 |

### 7.2 任务接口（TaskController，前缀 `/api/tasks`）

`GET /`(list) `GET /{id}` `POST /`（create）`POST /select-scenario` `POST /goto-step` `POST /{id}/step-data` `POST /{id}/selected-defs` `POST /{id}/changes` `POST /{id}/complete` `GET /meta/scenarios`

### 7.3 配置接口（ConfigController，前缀 `/api/configs`）

配置定义（ConfigDefinition）与数据行（ConfigDataRow）的 CRUD，供 SpreadJS 渲染。

---

## 8. 进度条同步问题与修复

### 8.1 现象

用户反馈："明明到选择配置项界面了，顶部进度条还在第一步"——已进入 SELECT_DEFS 路由，但 el-steps 仍停留在第一步。

### 8.2 根因分析（代码 + 数据 + 浏览器三方印证）

- **浏览器复现**：任务 #65（scenario=EXPORT, currentStep=SELECT_DEFS）进度条显示**正确**（第2步/4步），说明正常路径无 bug
- **间歇性根因**：当任务 `scenario=null`（未选场景）但 `currentStep` 被设为 SELECT_DEFS 等后续步骤时，`taskStore.steps` getter 因 `scenarioMeta.scenarioSteps[null]` 返回空数组，el-steps 不渲染节点
  - **触发路径**：AI 调 `navigate_step({step:'SELECT_DEFS'})` 不带 scenario；后端 `gotoStep` 在 scenario=null 时**跳过校验**直接 `setCurrentStep(step)`，破坏状态机语义（步骤序列由场景决定）
- **次要**：StepHeader 无兜底，steps 为空时显示"步骤 1/0"

### 8.3 修复方案

| # | 文件 | 改动 |
|---|------|------|
| F1 | [TaskService.java](file:///e:/workspace/trae/hello-world/ai-example/ai-service/src/main/java/com/example/ai/service/TaskService.java) `gotoStep` | scenario=null 时只允许 SELECT_SCENARIO，否则抛错"请先选择场景" |
| F2 | [frontend-tool-registry.js](file:///e:/workspace/trae/hello-world/ai-example/ai-ui/src/utils/frontend-tool-registry.js) `navigate_step.execute` | step≠SELECT_SCENARIO 且任务 scenario 为空时拦截返回错误 |
| F3 | [StepHeader.vue](file:///e:/workspace/trae/hello-world/ai-example/ai-ui/src/components/StepHeader.vue) | `displaySteps` 兜底：steps 为空时渲染当前步骤单节点 |

### 8.4 验证

- F1：`POST /api/tasks/goto-step {taskId:1, step:"SELECT_DEFS"}`（任务#1 scenario=null）→ 返回 400 拒绝 ✓
- 正常路径：任务 #65 进度条显示第2步/4步 ✓

---

## 9. 经验沉淀

> 摘自项目记忆，关键经验与踩坑：

1. **串行执行 tool_call**：OpenAI 协议要求 tool_call_ids 与 tool message 数量匹配。agent loop 串行执行第一个 tool_call，assistant message 只含已执行的 tool_call_id（`List.of(c)`），否则多 tool_call 时 400。
2. **关闭 thinking 省 token**：DeepSeek 带工具时中间 assistant 的 `reasoning_content` 必须回传否则 400。根治：`buildRawRequestBody` 加 `thinking={"type":"disabled"}`（配置管理场景无需思维链）。
3. **autoExec dedup Set**：watch 监听 `messages.length` 无法捕获同消息更新（store.updateLastMsg 不变 length）。改监听签名 `${length}|${last.done}|${last.toolCalls.length}`，并用 `autoExecutedCallIds` Set 防无限循环。
4. **cleanupWaitingState 精细化**：无条件清理同 session 的 WAITING_TOOL 会误杀活跃 state。只清理 `expires_at<now` 的过期 state，多个活跃 WAITING_TOOL 用 resumeToken 精确查找不冲突。
5. **chat 入口去重**：预存用户消息到 DB 后，`buildRawMessages` 的 listHistory 又加载它并追加当前消息→重复。dedup：比较 history 尾部 role+content 与当前消息。
6. **Spring AI 1.1.x**：starter 改名 `spring-ai-starter-model-openai`；国内镜像可能不同步，用阿里云 public mirror（项目级 maven-settings.xml）。
7. **H2 配置**：新版不支持 `AUTO_SERVER=TRUE` + `DB_CLOSE_ON_EXIT=FALSE` 共存，用 `DB_CLOSE_DELAY=-1`。
8. **Windows 文件锁**：maven clean 失败多因旧 jar 进程持有 target/ai-service.jar，先 kill 8081 端口进程。
9. **Vue contenteditable**：`browser_type` 无法操作 contenteditable div 输入框，E2E 测试用 `browser_evaluate` 设 textContent + dispatch input 事件。
10. **impact 描述需覆盖新工具**：[FrontendTools.summarizeImpact](file:///e:/workspace/trae/hello-world/ai-example/ai-service/src/main/java/com/example/ai/tool/FrontendTools.java) 缺 collect_user_input case 会走 default 显示"未知工具，前端将阻断执行"，误导用户（已修复）。

---

## 10. 运行与验证

### 10.1 编译启动

```powershell
# 后端（ai-service 目录）
& "D:\Program Files\JetBrains\IntelliJ IDEA\plugins\maven-plugin\lib\maven3\bin\mvn.cmd" clean package -DskipTests -q
java -jar target/ai-service.jar          # 监听 8081

# 前端（ai-ui 目录）
yarn dev                                   # 监听 5173
```

### 10.2 端到端验证方案

1. **进度条**：创建新任务 → "我要导出配置" → AI 先 selectScenario/navigate_step 选场景（不直接跳 SELECT_DEFS）→ 进度条与路由一致
2. **表单交互**："帮我新建一个配置项" → AI 调 collect_user_input 收集 code/name/字段 → 前端渲染表单 → 填写提交 → 回灌 → AI 继续
3. **控件覆盖**：验证 text/button_group/multi_select 等控件渲染 + required 校验
4. **回灌闭环**：表单提交后 AI 拿到值继续推理，给出结果或下一轮工具调用

### 10.3 已验证场景（2026-08-22）

浏览器自动化测试全链路 PASS：
- AI 调 collect_user_input 返回表单卡片（用户名 text 必填 / 性别 button_group 男女 / 爱好 multi_select）✓
- 影响提示"将向用户收集 3 个表单字段"（不再误显示未知工具）✓
- 填写张三/男/阅读 → 提交 → 卡片"已执行" ✓
- AI 后续响应拿到回灌值（提及张三/男/阅读）✓
- 浏览器控制台无报错 ✓
- 进度条 F1 防御：scenario=null 跳 SELECT_DEFS 被 400 拒绝 ✓

---

## 附：关键文件索引

| 关注点 | 文件 |
|--------|------|
| Agent Loop | [AiService.java](file:///e:/workspace/trae/hello-world/ai-example/ai-service/src/main/java/com/example/ai/service/AiService.java) |
| Plan-Execute | [PlannerService.java](file:///e:/workspace/trae/hello-world/ai-example/ai-service/src/main/java/com/example/ai/service/PlannerService.java) |
| 工具发现 | [ToolDiscoveryService.java](file:///e:/workspace/trae/hello-world/ai-example/ai-service/src/main/java/com/example/ai/tool/ToolDiscoveryService.java) |
| 工具定义 | [tools.yaml](file:///e:/workspace/trae/hello-world/ai-example/ai-service/src/main/resources/tools.yaml) / [scenarios.yaml](file:///e:/workspace/trae/hello-world/ai-example/ai-service/src/main/resources/scenarios.yaml) |
| 任务状态机 | [TaskService.java](file:///e:/workspace/trae/hello-world/ai-example/ai-service/src/main/java/com/example/ai/service/TaskService.java) |
| AI 面板 | [AiPanel.vue](file:///e:/workspace/trae/hello-world/ai-example/ai-ui/src/components/AiPanel.vue) |
| 表单渲染器 | [SchemaFormRenderer.vue](file:///e:/workspace/trae/hello-world/ai-example/ai-ui/src/components/SchemaFormRenderer.vue) |
| 前端工具注册 | [frontend-tool-registry.js](file:///e:/workspace/trae/hello-world/ai-example/ai-ui/src/utils/frontend-tool-registry.js) |
| 进度条 | [StepHeader.vue](file:///e:/workspace/trae/hello-world/ai-example/ai-ui/src/components/StepHeader.vue) |
| 接口契约 | [AiController.java](file:///e:/workspace/trae/hello-world/ai-example/ai-service/src/main/java/com/example/ai/controller/AiController.java) |
