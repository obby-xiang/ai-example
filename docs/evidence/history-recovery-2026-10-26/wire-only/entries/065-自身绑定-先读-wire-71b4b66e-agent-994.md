> **历史分析 / 过程证据 — 非现行基线，非验收结论**
>
> - 归档：2026-10-26 · `docs/evidence/history-recovery-2026-10-26/wire-only/`
> - 来源：Kimi Code 会话 `<KIMI_SESSION>` / 子 agent `agent-994` 的 wire 末条 assistant 文本（只读流式解析；非原始会话转写）
> - 原始体量：13325 B / 8185 字符 / 131 行；行指纹覆盖率（仓内 + 落盘语料）1.1%
> - 类别：**过程核验**
> - 脱敏：路径 0 / 会话标识 0 / 身份 0 / 密钥 0
> - 正文为该条消息的完整文本，未删段、未改写结论；仅脱敏与行尾空白规整。当时结论不代表当前代码状态。
## 0. 自身绑定（先读 wire）

`profile.bind` 单行为：
`{"type":"profile.bind","agentId":"agent-994","modelAlias":"DeepSeek/deepseek-flash","profileName":"explore","thinkingEffort":"high",...}`

即 **agentId=agent-994 / modelAlias=DeepSeek/deepseek-flash**。同 session 下 agent-995、agent-996 也是同任务同级子智能体，读 wire 时注意别混。本轮只做只读检索，未改文件、未起停服务。

---

## 1. 可核实的主流模式清单

证据分级：**源码级**（可直接读到的官方实现/测试快照）> **文档级**（官方文档文字/官方图片 alt 文本，像素未亲看）> **未找到**。

| 产品 | 对象 | 可核实事实 | 级别 |
|---|---|---|---|
| VS Code Copilot | 工具调用行 | 工具图标由代码 `this.domNode.prepend(toolIcon)` 放到行首；CSS 给行加 `padding-left` 并把 icon `position:absolute; left:...; top:...`，注释明确称其为 “activity gutter / transcript gutter icons”。图标与首行文字基线对齐，位于卡片自身矩形内的左侧槽位 | 源码级 |
| VS Code Copilot | 工具调用行（完成态） | `pastTenseMessage` 作为完成文案；子部件 `getIcon()` 在完成时返回 `Codicon.check`、错误返回 `Codicon.error`、运行中为旋转 loading。即**同一个头部图标槽承担身份+状态** | 源码级 |
| VS Code Copilot | 确认卡 | `IChatConfirmationWidget2Options.icon?: ThemeIcon`，渲染时把 `$(${icon.id}) ${title}` 直接拼进标题字符串；MCP 用 `Codicon.mcp`，普通工具用工具自身 icon，兜底 `Codicon.tools`。图标在**标题行内、标题文字左侧** | 源码级 |
| VS Code Copilot | 确认卡的风险提示 | 风险徽标是 `icon + 文本` 的一行，CSS 注释写明 “a slim row that sits below the main content … above the action buttons”，在卡片**底部**，不是顶部凸起 | 源码级 |
| VS Code Copilot | 表单/输入卡 | MCP elicitation 复用同一个 `ChatConfirmationWidget`（title/subtitle/message/buttons），没有另造卡头或外部标签 | 源码级 |
| ChatGPT | inline 卡 | 官方 Apps SDK UI 规范把布局写死为 “**Icon & tool call: A label with the app name and icon**”，位于 inline 卡内容上方；图标与应用名同处一条 label，未提凸起标签 | 文档级 |
| ChatGPT | 确认 | 官方帮助称 “ChatGPT may show an **approval card** describing the app and the proposed action”，按钮为 Deny/Allow/Allow once/…；未描述图标位置 | 文档级 |
| Claude Code | 权限确认 | 官方截图的 alt 文本完整描述：标题 “Bash command”、描述 “Run the test suite”、命令 `npm test`、“This command requires approval”、问句 “Do you want to proceed?”，四个选项 + Esc/Tab footer。**没有图标、没有标签页结构，纯面板** | 文档级（alt 文本） |
| Claude Code | 已完成工具调用 | MCP 调用默认折叠成单行 “…shown as a single ‘Called slack 3 times’ line”；VS Code 扩展把 “tool calls, tool results, and thinking” 收进可展开行 | 文档级 |
| Gemini CLI | 确认框 | 快照实测：圆角边框盒，头行是 `? replace  edit file` / `? Shell  …`，下面是详情与 `● 1. Allow once / 2. Allow for this session / …`；**无图标槽** | 源码级 + 测试快照 |
| Gemini CLI | 工具结果 | `ToolMessage` 用 sticky header：左侧 `ToolStatusIndicator` 状态列 + 工具名/描述，主体用左右边框包住；是“左侧状态列”，不是外部凸起 | 源码级 |
| Cursor | MCP 工具调用 | “Cursor asks for approval before using MCP tools by default. **Click the arrow next to the tool name** to see arguments”；响应在聊天里以可展开视图呈现 | 文档级 |
| Cursor | 审批卡 | 官方原文 “the **approval card** shows the full path and why Cursor is asking. Click **Allow**”；未描述图标/卡头结构 | 文档级 |
| Claude 网页版 | 工具批准 | 官方帮助只说明 “Review Claude's tool approval requests… click ‘Allow always’”，无卡头/图标描述 | 文档级（信息不足） |
| Gemini 消费端 App | 工具卡 | 未找到官方文字或可解析的 alt 描述 | 未找到 |

---

## 2. A/B 是否被采用、具体位置

**A（卡头左侧加图标）：有明确采用，且是唯一能核实到的位置。**

- **VS Code Copilot**：
  - 工具调用行：图标 prepend 到行首，占左侧 gutter，行内容整体右移（padding-left），属于“**卡内左侧图标槽**”，不是浮在卡外。
  - 工具确认卡：图标拼在标题字符串最前面（`$(icon) 标题`），即**标题行最左**；MCP/普通工具/兜底三种图标。
- **ChatGPT**：inline 卡的 label 行是 “应用图标 + 应用名”，在卡片内容顶部、左对齐，属于 A 的变体（图标在卡头 label 内，不在卡外）。
- **Cursor / Claude Code / Gemini CLI**：确认界面都不使用“图标在卡头左侧”这一结构（Claude Code 和 Gemini CLI 根本不在确认框里放图标）；因此不能算 A 的正面样本，只能算“允许无图标”的反证。
- **Claude 网页版 / Gemini 消费端**：官方文字不足以确认，未见可核实结构。

**B（顶部/左侧凸起标签，图标在标签内）：在五个产品里没有找到任何官方证据。**

- 没有一个官方源码、官方文档或官方截图 alt 文本显示“图标标签凸出卡片边界”。
- 最接近 B 的三个结构都不是 B：
  1. **ChatGPT**：图标 + 应用名是一条 **label 行**，在卡内容之上，但仍在 inline 容器矩形内，不是外凸标签。
  2. **VS Code 风险徽标**：图标 + 文本在卡片**底部**，是一条 slim row，也不是顶部标签。
  3. **Gemini CLI**：左侧状态列（sticky header 的状态指示器），是行内布局，不是标签。
- 结论要写成：**“未找到采用 B 的官方证据”**，不能写成“业界不用 B”。我能核实的样本里，图标一律在卡片矩形内的标题/首行左侧。

---

## 3. 对四类卡可迁移的规律

**① 工具调用卡（进行中/运行中）**
- 图标占“首行左侧槽”，与首行文字对齐；用 `padding-left` 让出槽位，而不是绝对定位盖在文字上。
- 一个槽同时表达“工具身份”和“运行状态”：运行中 loading、失败 error、完成 check。状态变化优先换图标，不再额外加徽章。
- 行内文案用“动词 + 对象”，完成后用过去式（VS Code 的 `pastTenseMessage`），保持同一行高度不跳变。

**② 确认卡**
- 图标放在**标题行内、标题文字左侧**（VS Code 的实现），且是可选字段；Claude Code / Gemini CLI 则完全不放图标。说明：**图标是增强，不是确认语义的必需品**。
- 确认卡的标题必须直接说“要做什么”（Bash command / replace edit file），正文放参数、路径、命令、风险说明；按钮单独一行在底部。
- 风险提示不要做成顶部外凸标签去抢注意力；VS Code 把它做成正文下方、按钮上方的一行 `icon + 说明`，与卡头分离。
- B 若用在确认卡上，会与“标题即动作”的阅读顺序冲突，且外凸部分在折叠/展开、窄栏时会压到相邻消息——这是从现有结构可推出的风险，不是我实测到的缺陷。

**③ 表单/输入卡（elicit / 提问）**
- 直接复用确认卡的壳：同一 title/subtitle 区、同一按钮区、同一风险位。VS Code 的 elicitation 就是 `ChatConfirmationWidget` 的实例，没有独立视觉语言。
- 表单不需要卡头图标来“区分类型”；用标题和字段本身区分。若整个产品族都采用 A，则表单卡沿用同一图标槽即可，避免出现“确认卡有图标、表单卡没有”的割裂。

**④ 已决回执（完成/失败/已取消）**
- 统一折叠成**单行**：左侧图标表示结果状态，文案用过去式/结果式，详情按需展开（VS Code、Claude Code、Cursor 都是这个方向）。
- 不要把已决回执继续渲染成大卡片，也不要把确认阶段的图标标签保留在回执上；回执的视觉重量应低于确认卡。
- 失败态复用同一个图标槽换成 error 图标，避免新增右侧 badge，保持左对齐稳定。

---

## 4. URL 清单

### 官方一手（结论依据）

**VS Code / Copilot（源码）**
- 工具调用图标 prepend：https://github.com/microsoft/vscode/blob/main/src/vs/workbench/contrib/chat/browser/widget/chatContentParts/toolInvocationParts/chatToolInvocationPart.ts
- 左侧图标槽 CSS（`padding-left` + 绝对定位 + gutter 注释）：https://github.com/microsoft/vscode/blob/main/src/vs/workbench/contrib/chat/browser/widget/chatContentParts/media/chatThinkingContent.css
- 确认卡 icon 选项与 `$(icon) title`：https://github.com/microsoft/vscode/blob/main/src/vs/workbench/contrib/chat/browser/widget/chatContentParts/chatConfirmationWidget.ts
- 工具确认传入 MCP/工具图标：https://github.com/microsoft/vscode/blob/main/src/vs/workbench/contrib/chat/browser/widget/chatContentParts/toolInvocationParts/abstractToolConfirmationSubPart.ts
- 表单复用确认卡：https://github.com/microsoft/vscode/blob/main/src/vs/workbench/contrib/chat/browser/widget/chatContentParts/chatElicitationContentPart.ts
- 风险徽标在正文下方/按钮上方：https://github.com/microsoft/vscode/blob/main/src/vs/workbench/contrib/chat/browser/widget/chatContentParts/toolInvocationParts/media/toolRiskBadge.css
- 完成/错误/运行中状态图标：https://github.com/microsoft/vscode/blob/main/src/vs/workbench/contrib/chat/browser/widget/chatContentParts/toolInvocationParts/chatToolInvocationSubPart.ts
- 官方文档“每个动作都显示为 tool call”：https://code.visualstudio.com/learn/foundations/approvals-autonomy-and-context-budget
- 官方文档工具审批/URL 审批：https://code.visualstudio.com/docs/copilot/chat/chat-tools

**OpenAI / ChatGPT**
- Apps SDK UI 规范（Icon & tool call label）：https://developers.openai.com/plugins/concepts/ui-guidelines
- 审批卡与权限选项：https://help.openai.com/en/articles/20001495-managing-app-permissions-in-chatgpt
- 连接应用与“可能先请求批准”：https://help.openai.com/en/articles/11487775-connected-apps-in-chatgpt

**Claude**
- Claude Code 权限面板（官方截图 alt 完整描述布局）：https://docs.claude.com/en/docs/claude-code/permissions
- Claude Code 交互模式（MCP 折叠为单行）：https://docs.claude.com/en/docs/claude-code/interactive-mode
- Claude Code VS Code 扩展（tool calls/results 收进可展开行）：https://docs.claude.com/en/docs/claude-code/vs-code
- 连接器与工具批准（Claude 网页/桌面）：https://support.claude.com/en/articles/11176164-use-connectors-to-extend-claude-s-capabilities

**Gemini**
- Gemini CLI 确认框实现：https://github.com/google-gemini/gemini-cli/blob/main/packages/cli/src/ui/components/ToolConfirmationQueue.tsx
- Gemini CLI 工具消息/状态列：https://github.com/google-gemini/gemini-cli/blob/main/packages/cli/src/ui/components/messages/ToolMessage.tsx
- 确认框渲染快照（纯文本可核）：https://github.com/google-gemini/gemini-cli/blob/main/packages/cli/src/ui/components/__snapshots__/ToolConfirmationQueue.test.tsx.snap

**Cursor**
- MCP 工具批准与“点击工具名旁箭头看参数”：https://cursor.com/docs/mcp
- 运行模式与 “approval card … Click Allow”：https://cursor.com/docs/agent/security/run-modes
- Agent 工具总览（无 UI 结构描述）：https://cursor.com/docs/agent/overview

### 二手文章

本轮结论**没有用二手文章作证据**。搜索到的 Cursor 论坛、社区文章只用于定位官方页面，未引用其 UI 描述。

---

## 5. 明确未找到 / 限制

1. **Claude 网页版/桌面版**：没有找到官方对工具调用卡“图标在哪、卡头长什么样”的文字或可解析 alt 描述。只能确认存在“tool approval requests / Allow always”。
2. **Gemini 消费端 App**：未找到官方文字或源码级证据描述工具卡、确认卡的卡头结构；Gemini CLI 不能直接代表消费端 UI。
3. **Cursor**：闭源，官方文档只描述“工具名 + 展开箭头 + 审批卡 + Allow”，没有图标位置或标签形态；无法确认其卡头是否用了 A。
4. **ChatGPT 审批卡的图标位置**：官方只说 “approval card”，没有说图标在主卡头还是正文；能核实的只有 inline 卡的 “应用图标 + 应用名” label。
5. **截图验证限制**：本次工具链只能取网页文本/alt 与源码，不能读取截图像素；官方文档里的图片我按 alt 文本引用，未亲眼看图。因此“某产品截图上图标具体像素位置”这类结论我不下。
6. **“主流”范围**：以上只覆盖题目指定的五个产品；不能外推为“多数产品都……”。B 的结论严格是“在这五个产品的官方材料中未找到采用证据”。

如果主会话要做取舍：证据最硬的方向是 **A 的“卡内左侧图标槽 + 标题行内图标”**（VS Code 源码、ChatGPT 官方规范），而 B 目前没有一手样本；Claude Code 和 Gemini CLI 还说明确认类卡片可以完全不用图标。
