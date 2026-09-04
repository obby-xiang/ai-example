# 会话生命周期与状态同步方案设计

> 版本：v1.0
> 日期：2026-09-04
> 范围：ai-ui（后端 Agent Loop）+ ai-ui-vercel（前端 AI Runtime）双范式
> 前置文档：design-and-implementation.md、collect-user-input-and-progress-sync.md

---

## 1. 背景与问题定义

来自生产使用的 7 个问题，归为四组：

| 组 | 问题 | 本质 |
|---|---|---|
| 会话生命周期 | ①刷新/跳路由后对话怎么续 ②多窗口各自独立对话、关窗即毁 | 会话标识与存储作用域 |
| 中断恢复 | ③AI 反问未完成时刷新，pending 交互怎么恢复 ④pending 要超时取消 ⑤pending 期间用户手工改了工作区怎么办 | pause-resume 的健壮性 |
| 状态一致性 | ⑥页面与 AI 怎么解耦、解耦后怎么交互、手动操作怎么被感知 | Shared State 双向同步 |
| 执行控制与上下文 | ⑦工具执行到一半被打断、流程被闲聊穿插、上下文撞上限 | Agent 运行时治理 |

---

## 2. 业界实践对标（检索验证，2026-09）

| 机制 | 业界原型 | 本项目对应 |
|---|---|---|
| Shared State 双向共享 | CopilotKit `useAgent`：`agent.state` 读 / `agent.setState` 写，SSE 推送 | config store 单一真理 + 快照通道 + 工具现查 |
| 状态同步协议 | AG-UI：STATE_SNAPSHOT（全量/重连）/ STATE_DELTA（JSON Patch 增量）/ INTERRUPT | ChatReq.workspaceState（pull 模式，Microsoft AG-UI 实现同款） |
| 中断-恢复 | LangGraph `interrupt()` + checkpointer + `thread_id` + `Command(resume=...)` | resumeToken + AgentState(WAITING_TOOL) + sessionId |
| 挂起期间外部变更检测 | Claude Code "File has been modified since read"：Read 记 mtime → Write 前对比 → 变了强制重读 | dataVersion 计数器 + 恢复时对比 + 强制现查（改造 D） |
| 假阳性教训 | Claude Code mtime 被 IDE autosave 污染 → 社区建议 content hash | 粗粒度版本先跑，假阳性成问题再升级 hash/分域 |
| 超时安全默认 | AG-UI 准则：timeout/disconnect 默认最安全动作（拒绝） | WAITING_TOOL TTL + 过期拒绝回灌（改造 C） |
| 流程抗穿插 | Rasa slot filling：slot（状态）独立于 story（对话） | scenario/step/plan 在 AgentState，不在对话历史（已有架构红利） |
| 打断语义 | 业界共识：打断=轮次边界停止+保留现场，非指令级中断、非自动回滚 | 改造 F |
| 上下文管理 | Claude Code auto-compact / 读取截断；LangGraph trim/summarize | 改造 G |

**结论：本方案主干全部有业界原型，非凭空设计。**

---

## 3. 总体架构

### 3.1 CopilotKit 三件套映射

| 三件套 | 本项目落地 | 缺口（本方案补齐） |
|---|---|---|
| Shared State | config store 唯一真理源；AI 写=工具调用，AI 读=get_workspace_state | dataVersion 变更感知 + 快照契约化 |
| Generative UI | SchemaFormRenderer（collect_user_input/excel_import）+ 工具卡片 + Plan 卡片 | 刷新后重放渲染（pending 恢复） |
| Human-in-the-loop | needConfirm / requireDoubleConfirm / pause-resume | 超时取消 + 刷新恢复 |

### 3.2 解耦模型：状态同源、契约交互、变更可感知

```
┌──────────────── 契约层（双方唯一依赖）────────────────┐
│  页面→AI：①状态查询 ②能力清单(按场景过滤) ③dataVersion  │
│  AI→页面：④命令(工具) ⑤渲染(schema) ⑥审批(confirm/input) │
└──────────────┬──────────────────────┬────────────────┘
       ┌───────┴────────┐     ┌───────┴────────┐
       │ 页面 Workspace  │     │   AI Agent     │
       │ Vue store/组件  │     │  LLM + loop    │
       └────────────────┘     └────────────────┘
            ▲ 用户手动操作与 AI 工具写同一 store（同权）
```

运行循环：感知① → 决策（结合②）→ 行动④ → 高危走⑥ → 需输入走⑤ → 任何变更经③可感知。

### 3.3 两范式 Shared State 物理实现差异

| | ai-ui-vercel（前端 Runtime） | ai-ui（后端 Agent Loop） |
|---|---|---|
| AI 与 store | 同进程，工具直读 store（天然实时） | 跨进程，靠快照协议 |
| 同步协议 | 不需要 | ChatReq.workspaceState（已有）+ ToolResultReq 补快照（本方案） |
| 一致性水位 | 实时 | 请求粒度；pending 期间靠版本对比兜底 |

---

## 4. 改造点详细设计

### 改造 A：会话随窗口（问题①②）

**原则：会话标识与消息存 sessionStorage（刷新存活、关窗自毁、多标签天然隔离）；任务数据保持 localStorage 不动。**

| # | 文件 | 改动 |
|---|---|---|
| A1 | ai-ui-vercel/src/main.js | pinia 持久化插件：ai store 的 `sessionId` 从 localStorage 迁 sessionStorage |
| A2 | ai-ui-vercel/src/stores/ai.js L179-203 | `loadHistory`/`_persistMessages` 的 `ai-vercel-chat-{taskId}` key 迁 sessionStorage |
| A3 | ai-ui/src/stores/ai.js L38 | `sessionId` 生成后写 sessionStorage，state 初始化时先读（**修复现状：刷新后 uuidv4() 重新生成导致历史断连**） |

后端 AgentState 24h 过期兜底清理孤儿会话，无需改动。

### 改造 B：pending 刷新恢复（问题③）

**机制（AG-UI 重连模式）：检测「尾部 assistant 有 toolCall 但无 result」→ 重建交互卡片，不续跑已断的 Promise。**

ai-ui（后端 loop）：

| # | 文件 | 改动 |
|---|---|---|
| B1 | AiController + AiService | 新增 `GET /api/ai/pending?sessionId=`：复用 AgentStateRepository.findAllBySessionIdAndStatus 查 WAITING_TOOL，返回 `{resumeToken, pendingToolCalls, expiresAt, mode}` |
| B2 | ai-ui/src/stores/ai.js + AiPanel.vue | onMounted 调 pending 接口 → 有则按 mode 重建卡片（CONFIRM→确认按钮，INPUT→SchemaFormRenderer 表单）→ 提交走现有 submitToolResult |

ai-ui-vercel（前端 runtime）：

| # | 文件 | 改动 |
|---|---|---|
| B3 | ai-ui-vercel/src/stores/ai.js | `recoverPendingInteraction()`：扫描 messages 尾部未应答 toolCall → 重建 pendingInteraction（卡片元数据随 messages 持久化在 sessionStorage，天然存活刷新） |
| B4 | ai-ui-vercel AiPanel.vue | 用户提交后不续跑旧 Promise，改为 append tool result 消息 + 重新 streamText 开新一轮 loop；**注意绕过 autoExecutedCallIds 去重 Set（memory 已记录此坑）** |

### 改造 C：交互超时取消（问题④）

| # | 文件 | 改动 |
|---|---|---|
| C1 | application.yml + AiService.java L339-381 | WAITING_TOOL 的 expiresAt 从 24h 缩到配置项 `agent.waiting-tool-ttl-minutes`（默认 10 分钟） |
| C2 | AiService.submitToolResult L143-210 | 增加 `expiresAt < now` 校验 → 拒绝回灌 + 置 EXPIRED + 返回明确错误码 |
| C3 | 两范式 AiPanel.vue | pending 卡片倒计时（用 B1 返回的 expiresAt），超时置灰「已过期，请重新发起」 |

### 改造 D：dataVersion 变更感知 + 快照契约化（问题⑤⑥，核心）

**D1 感知层（纯前端，两范式同构）**：

```js
// config store：任何 mutation 成功后才递增，加载类 action 排除
const MUTATING_ACTIONS = new Set([
  'batchSetField', 'deleteRows', 'replaceValues',
  'importRows', 'confirmData', 'selectDefinitions', 'setScenario'
])
configStore.$onAction(({ name, after }) => {
  if (MUTATING_ACTIONS.has(name)) after(() => { configStore.dataVersion++ })
})
router.afterEach(() => { configStore.dataVersion++ })  // 路由=上下文变更
// SpreadSheet.vue EditEnded 回调同样触发（表格编辑不经 store action）
```

- `dataVersion` 持久化 localStorage（属工作区非会话，跨刷新存活）
- 刷新恢复走 loadXxx action，**不在拦截名单** → 恢复不算变更
- 连续多步操作只产生版本差（15→25），恢复时只对比首尾，不需要事件流

**D2 快照契约化**：workspaceState 固定 schema（两范式统一）：

```json
{
  "route": "/wizard/adjust",
  "scenario": "EXPORT", "step": "SELECT_DEFS",
  "selectedDefIds": [1, 3],
  "rowCounts": {"1": 150, "3": 80},
  "dirty": true, "dataVersion": 15
}
```

只放摘要；全量数据仍走 get_workspace_state 现查（token 原则不破）。

**D3 版本对比与提示**：

| # | 文件 | 改动 |
|---|---|---|
| D3a | AiDTO.java ToolResultReq L90-94 | 增加 `workspaceState` 字段（回灌携带最新快照——修复「挂起时快照冻结」缺口） |
| D3b | AiService.saveAgentState | 挂起时记录 dataVersion 到 AgentState（新列，ddl-auto 自动迁移） |
| D3c | AiService.submitToolResult | 版本不一致时 tool result message 前置提示：`[系统] 等待期间工作区已被用户手动修改（v15→v18），后续决策请先调 get_workspace_state 获取最新状态` |
| D3d | ai-ui-vercel ai.js | pendingInteraction 记录挂起版本；resolveInteraction 对比，变了则在 tool result content 附加同款提示 |

**D4（可选 UX）**：AiPanel 订阅 dataVersion，pending 期间显示「工作区有变更」轻提示条。

### 改造 E：prompt 行为准则

两范式 system prompt 组装处补三条：

1. 执行任何写操作（table_*、run_flow）前必须调 get_workspace_state 确认最新状态，不得依赖对话历史中的数据快照
2. 回答与当前流程无关的问题时不要重置或推进流程状态；用户表达继续意图时先现查当前步骤再行动
3. 收到「工作区已变更」系统提示时，必须先现查再决策

### 改造 F：执行打断（问题⑦上半）

**原则：打断=轮次边界停止+保留现场；不做指令级中断，不做自动回滚。**

| # | 文件 | 改动 |
|---|---|---|
| F1 | 两范式 AiPanel.vue | 生成/执行中显示「停止」按钮 → AbortController 断开 SSE/fetch |
| F2 | AiService agent loop L245-323 | 每轮 loop 开始检查中断标志（连接断开/显式取消），当前轮结束后停；AgentState 保留现场（已执行步骤、已回灌结果） |
| F3 | run_flow 执行器 | 步骤间检查取消标志（协作式取消），每步完成写 stepArtifacts（已有机制） |
| F4 | 前端秒级原子工具（table_*） | 不做执行中打断，只阻止新轮次（业界惯例） |

用户打断后问"刚才做到哪了"→ AI 现查 get_workspace_state + stepArtifacts 如实回答。

### 改造 G：上下文治理（问题⑦下半）

| # | 文件 | 改动 | 优先级 |
|---|---|---|---|
| G1 | AiService.buildRawMessages | 发送窗口化：system + 最近 N 轮（默认 20）；DB 仍存全量 | 高 |
| G2 | 后端工具结果 | 大结果截断摘要化（列表>50 项返回前 50+总数+「用 xx 查详情」） | 高 |
| G3 | AiService | token 接近阈值时老消息摘要压缩（auto-compact） | 低，可后置 |

流程不受压缩影响：plan/scenario/step 在 AgentState 不在消息里。

---

## 5. 实施阶段（4 阶段，各自独立可验证）

| 阶段 | 内容 | 验证方法 |
|---|---|---|
| 1 | 改造 A | 刷新→会话恢复；关窗重开→新会话；双窗口→各自独立 |
| 2 | 改造 D（D1→D3→D4） | 「1月改2月」复现：AI 挂起确认→手动改数据→确认→AI 感知变更并现查 |
| 3 | 改造 B + C | AI 反问表单→F5→卡片重现→提交→loop 续跑；pending 挂 10 分钟→卡片过期→回灌被拒 |
| 4 | 改造 E + F + G（G3 可后置） | 停止按钮生效且现场可查；闲聊穿插后继续流程正确；长会话不撞上限 |

## 6. 风险与升级路径

| 风险 | 应对 |
|---|---|
| 版本假阳性（打字中途版本变，AI 多现查一次） | 可接受；成问题则升级内容 hash 或版本分域（data/selection/route） |
| messages 迁 sessionStorage 后 ai-ui 服务端消息成孤儿 | 现有 24h 过期清理兜底，符合「对话随窗口销毁」语义 |
| vercel 重放 loop 被 autoExecutedCallIds 去重跳过 | 恢复路径绕过该 Set（memory 已记录） |
| AI 需要知道「改了什么」而非「改了没有」 | 升级 AG-UI STATE_DELTA（JSON Patch 事件流）——当前不需要 |
| 多标签共享同一会话实时同步 | 升级 BroadcastChannel + SSE push——当前需求是隔离，不做 |
