# 对话快捷选项（Quick Suggestions）设计方案

> 版本：v1.0
> 日期：2026-09-05
> 范围：ai-ui + ai-ui-vercel 双范式
> 关联文档：conversation-lifecycle-and-state-sync-plan.md（改造 D 的 workspaceState 契约是本方案类型②的输入）、design-and-implementation.md

---

## 1. 术语定位

两类快捷选项均属 **Prompt Controls（提示控件）** 家族（NN/g 定义：包围输入框、用于加速和补充文本输入的 UI 组件）：

| 形态 | 行业术语 | 别名 | 特征 |
|---|---|---|---|
| 对话结束后、AI 回复下方的可点击选项 | **Follow-up Suggestions**（追问建议）/ **Quick Replies**（快捷回复） | Suggested Replies、Smart Replies（Gmail 系）、Suggested Follow-ups（ChatGPT/Perplexity 系） | 对话驱动（turn-bound），随消息产生 |
| 输入框上方、随工作区操作/页面切换触发 | **Contextual Suggestions**（情境化建议）/ **Conversation Starters**（对话启动器，空态特例） | Prompt Suggestions（CopilotKit）、Icebreakers、Suggested Actions | 状态驱动（context-bound），随工作区状态/路由产生 |

关键区分维度：**触发时机**（AI 响应完成后 vs 用户情境变化时）和**内容来源**（对话上下文派生 vs 工作区状态派生）。

---

## 2. 类型①：对话结束后的快捷回复（Follow-up Suggestions / Quick Replies）

### 2.1 数据结构

建议作为**消息载荷的附属字段**，与产生它的消息绑定（非独立实体），历史回放时随消息原样重渲染——与本项目「卡片元数据随 messages 持久化」模式同构：

```json
{
  "messageId": "msg_123",
  "role": "assistant",
  "content": "已完成导出，共 150 行。",
  "quickReplies": [
    { "id": "qr_1", "label": "调整列宽后重新导出", "action": { "type": "send_message", "text": "调整列宽后重新导出" } },
    { "id": "qr_2", "label": "查看数据统计", "action": { "type": "send_message", "text": "查看这份数据的统计信息" } }
  ]
}
```

- `action.type` 两种行为：`send_message`（作为用户消息发送，占 90% 场景）、`trigger_tool`（直接触发工具调用跳过一轮 LLM，仅适合纯确认类）
- 统一契约：`Suggestion { id, label, action: { type, text|toolCall } }`

### 2.2 内容生成（三种策略，按成本递增）

| 策略 | 机制 | 代表 |
|---|---|---|
| 规则模板 | 按 scenario/step 查静态映射表 | 传统客服 bot（Intercom/ManyChat） |
| LLM 主响应附带 | system prompt 要求主模型在响应尾部输出结构化建议（尾部约定格式或 JSON mode 多字段） | 低成本，一次调用 |
| 独立 meta-prompt 调用 | 主响应完成后，把对话发给第二个轻量调用：「生成 3 个相关追问」 | ChatGPT 追问、NoForm Contextual Quick Replies |

**本项目选型**：配置管理场景场景数有限（IMPORT/EXPORT/ADJUST × 步骤），**规则模板起步 + LLM 附带兜底**即可，不做独立 meta 调用（token 成本敏感约束）。

### 2.3 前端组件与交互逻辑（Vue3）

```vue
<!-- SuggestionChips.vue：渲染在最后一条 assistant 消息尾部 -->
<template>
  <div v-if="visible" class="suggestion-chips">
    <button v-for="s in suggestions" :key="s.id" class="chip"
            @click="onSelect(s)">{{ s.label }}</button>
  </div>
</template>
```

交互逻辑四条铁律（业界共识）：

1. **只在最新一条 assistant 消息上显示**——历史消息的建议必须隐藏或置灰（防点击过时上下文）
2. **流式期间隐藏**——响应未完成不显示，防建议基于半截内容
3. **点击即消失**——发送后清空该组建议，按钮一次性（ephemeral）
4. **用户手动输入任何字符时收起**——用户已决定自己表达，建议让位

### 2.4 状态管理（Pinia）

```js
// ai store
state: () => ({
  messages: [],
  activeSuggestions: [],    // 当前生效的一组建议
  suggestionsForMsgId: null // 绑定哪条消息
}),
actions: {
  onMessageDone(msg) {
    // 流式完成 → 提取/生成建议
    this.activeSuggestions = msg.quickReplies ?? deriveFromScenario(msg)
    this.suggestionsForMsgId = msg.id
  },
  async selectSuggestion(s) {
    this.activeSuggestions = []
    if (s.action.type === 'send_message') await this.send(s.action.text)
    else await this.executeTool(s.action.toolCall)
  },
  onUserTyping() { this.activeSuggestions = [] }  // 输入框 watch
}
```

---

## 3. 类型②：输入框上方的情境化建议（Contextual Suggestions）

### 3.1 与类型①的本质区别

类型①是**对话驱动**（监听消息流），类型②是**状态驱动**（监听工作区状态和路由）——直接复用本项目 `dataVersion` 变更感知机制（plan 文档改造 D）。

### 3.2 触发机制（与改造 D 三拦截点同构）

| 触发源 | 实现 | 示例 |
|---|---|---|
| 路由变化 | `router.afterEach` | 进入 EXPORT 配置页 → 「帮我配置导出格式」 |
| store 变更 | `$onAction` 白名单拦截（改造 D1） | 用户选完定义 → 「已选 3 个定义，开始导出？」 |
| 特定 UI 事件 | 组件事件转发（SpreadJS `SelectionChanged`/`EditEnded`） | 用户选中一片区域 → 「分析选中区域的数据」 |

### 3.3 内容动态生成：规则引擎优先

```js
// suggestionEngine.js —— 纯函数，输入工作区快照，输出建议列表
function deriveSuggestions(workspaceState) {
  const rules = [
    { when: s => s.route === '/wizard/export' && s.selectedDefIds.length === 0,
      suggest: [{ label: '如何配置导出定义？', action: send('如何配置导出定义？') }] },
    { when: s => s.selectedDefIds.length > 0 && !s.confirmed,
      suggest: [{ label: `导出已选的 ${s.selectedDefIds.length} 个定义`, action: send('导出已选定义') }] },
    { when: s => s.dirty,
      suggest: [{ label: '数据有未确认修改，继续？', action: send('继续流程') }] },
  ]
  return rules.filter(r => r.when(workspaceState)).flatMap(r => r.suggest).slice(0, 3)
}
```

关键设计：
- **输入就是契约化的 workspaceState 快照**（plan 改造 D2 的 schema）——建议引擎与页面的唯一依赖是这个契约，不读 store 内部，保持解耦
- **响应式推导而非事件存储**：`computed(() => deriveSuggestions(snapshot))`，状态变→建议自动变，无需手动管理失效
- 需要 LLM 生成时用防抖（dataVersion 变化后 500ms 再请求），避免连续操作打爆 token

### 3.4 展示逻辑与用户行为关联策略

1. **位置**：紧贴输入框上方，不遮挡消息区。aiuxdesign.guide 实测：输入框上方路径上的建议点击率是消息旁的 3–4 倍（用户视线自然从内容移向输入框）
2. **数量上限 3–5 个**：超过即决策瘫痪（NN/g 与客服平台共同结论，NoForm 硬性限制 5 个）
3. **静默更新**：内容变化不打断用户，不做弹入动画抢焦点；仅首次出现可有轻提示
4. **点击策略两选一**：
   - 直接发送（主流，零摩擦）
   - **填充到输入框但不发送**（ChatGPT conversation starters 采用，可编辑后发送）——涉及写操作的建议推荐后者，给用户审阅机会，符合本项目「高危操作需确认」准则
5. **与用户输入互斥**：输入框非空时建议淡出（防误触），清空后恢复
6. **可关闭且记忆**：提供 × 关闭，关闭状态按 scenario 记忆（localStorage），避免烦扰

---

## 4. 业界主流实践案例

| 产品 | 类型①实践 | 类型②实践 |
|---|---|---|
| ChatGPT | 响应后生成 2–4 个 follow-up（独立 LLM 调用） | 空态 conversation starters（可编辑后发送） |
| Perplexity | 「Related」追问区，驱动多轮搜索飞轮 | — |
| Gmail/LinkedIn | Smart Reply：ML 模型生成 3 个短回复 | — |
| Microsoft 365 Copilot | — | declarative agent 声明式 `@conversationStarter`，最少 3 个样例提示 |
| CopilotKit | `useCopilotChatSuggestions`：LLM 读应用状态生成建议 | suggestions 绑定应用 context，状态变即重新生成 |
| 客服系（Intercom/NoForm） | Quick Replies：仅最新消息、流式隐藏、点击消失 | 按页面路由规则配不同 welcome + starters |
| Gemini/Claude | 空态能力展示卡片 | 附加上下文后（上传文件）出现相关建议 |

共同设计模式：**生成端与渲染端分离**——建议来源（规则/LLM/静态配置）策略可插拔，前端组件只认统一的 `Suggestion[]` 契约。

---

## 5. UX 原则与技术挑战

### 5.1 UX 原则（NN/g、Google Conversation Design、aiuxdesign 综合）

1. **解决「表达鸿沟」（articulation barrier）**：用户知道要什么但不知道怎么措辞——这是建议存在的根本理由，空输入框是对话 UI 最大的可用性问题
2. **建议是导航不是拐杖**：永远不替代自由输入（Quick Replies 头号反模式 = 强制只点不能说）
3. **情境性 > 通用性**：每条消息后挂同样的通用建议会迅速被无视（「机器人感」），宁缺毋滥
4. **2–4 个、措辞具体**：「查看数据统计」优于「继续」；动宾短语、≤20 字
5. **引导向系统擅长的路径**：建议的隐性价值是把用户引向实现得好的流程，降低 out-of-scope 率
6. **可访问性**：Tab 可达、Enter/Space 激活、新消息经 ARIA live region 播报

### 5.2 技术挑战与对策

| 挑战 | 说明 | 对策 |
|---|---|---|
| 时机正确性 | 流式中显示、旧消息建议残留是最常见 bug | 绑定 messageId + `done` 标志（复用 updateLastMsg 的 done 语义）；历史消息渲染时强制隐藏 |
| 建议时效性 | 生成后工作区已变（dataVersion 变更），建议失效 | 类型②点击前校验 dataVersion；过期则静默重算 |
| 生成成本 | 独立 LLM 调用生成建议 = token 翻倍 | 主响应附带（约定尾部格式）或纯规则引擎；类型②用防抖 |
| 点击行为分歧 | send_message 与 trigger_tool 混用时，后者跳过 LLM 会失去上下文更新 | trigger_tool 仅用于纯确认类；执行后仍走一轮让 AI 总结 |
| 两范式对称 | ai-ui 后端生成 vs ai-ui-vercel 前端生成，建议来源不同 | 统一 `Suggestion[]` 契约：ai-ui 由后端随消息下发，vercel 由前端规则引擎推导——渲染组件共享 |
| 效果度量 | 点击率是衡量建议质量的核心指标 | 每次展示/点击记事件（NoForm 等平台标配），驱动规则调优 |

---

## 6. 本项目落地建议（最小实现）

1. **先做类型②规则引擎版**：`deriveSuggestions(workspaceState)` 纯函数 + computed 响应式 + 输入框上方 chips——零后端改动，直接复用 plan 改造 D 的快照契约，覆盖「进入新页面/操作后引导」场景
2. **类型①从静态模板开始**：按 scenario+step 配置 2–3 条固定建议随 assistant 消息渲染，验证交互后再考虑 LLM 附带生成
3. 统一 `Suggestion { id, label, action: { type, text|toolCall } }` 契约，与 SchemaFormRenderer 一样纳入 plan 文档 §3.2 契约层第⑤类「渲染」的自然扩展

---

## 7. 参考来源

- NN/g：Prompt Controls in GenAI Chatbots: 4 Main Uses and Best Practices
- aiuxdesign.guide：Suggested Prompts & Conversation Starters（三分类 + 输入框上方位置点击率数据）
- NoForm AI：Contextual Quick Replies / Conversation Starters 配置规范（仅最新消息、流式隐藏、上限 5 个）
- Microsoft Learn：Declarative Agents Best Practices（conversation starters 最少 3 个）
- CopilotKit Docs：useCopilotChatSuggestions（suggestions 绑定应用状态）
- subux.pro：UX for AI Chatbots（fallback 建议、可访问性要求）
- 99helpers Glossary：Quick Replies（ephemeral 语义、反模式）
