<!-- 历史中间稿证据条目（自动生成头部，非正文）。源 = Kimi Code assistant wire 文本；不含 think / 工具输出 / 原始用户消息。 -->

# MT-224 · 调研 · ① 图标按钮清单（全项目 `frontend/src`，共 21 个纯图标钮）（8809e58d / agent-934）

> **项目归属**：**项目硬锚点**（正文命中 ai-example 路径 / 项目类名 / 项目文档名 / 项目 UI 概念）。
> **归属依据**：正文命中：`AiPanel.vue`、`回执卡`。
> **稿态**：历史中间稿 —— 该 wire 第 `m140` 条 assistant 消息（m 按 wire 内 assistant 消息计，含工具调用轮）；本条是该 wire 第 `mt16` 条有正文的 assistant 消息（共 18 条有正文），末条有正文消息为 `m174`。**非现行基线、非验收结论、非当前缺陷清单**。
> **来源**：会话 `8809e58d` / agent `agent-934`（`main` = 主会话）；原字符 5,172（链内 1 段、合计 5,172）。
> **链号 / 类别**：`MT-224` / **调研**；判定：助手调研记录（历史调研，非当前结论）；**不是用户批准**。
> **轮次**：位于该 wire 第 6 个用户轮之后；相邻用户轮未见决断类字样（仅记布尔，未复制用户原文）。
> **去重**：行指纹对「仓内 + 落盘语料」覆盖率 `3%`（<60% 视为仓内无等价件）；同 wire 后文覆盖率 `2%`。
> **与末条归档（138 件）的关系**：末条 `m174`（4,189 字符）未入末条归档。
> **脱敏**：路径 0 / 会话标识 0 / 身份 0 / 密钥 0 / 旧仓名与同源仓名 0 处。

---

HEAD 实为 `36efa84`（S6 批1）+ 一个 docs 提交，代码即 S6 状态。以下为取证结果。

## ① 图标按钮清单（全项目 `frontend/src`，共 21 个纯图标钮）

**A. AiPanel 模板内原生 `<button>`（12 个）**

| # | 位置 | 用途 | title | aria-label | tip 气泡 |
|---|---|---|---|---|---|
| 1 | `AiPanel.vue:600` | 赞（feedback-up，svg） | ✗ | ✓ 动态 | ✓ |
| 2 | `AiPanel.vue:612` | 踩（feedback-down，svg） | ✗ | ✓ 动态 | ✓ |
| 3 | `AiPanel.vue:624` | 编辑消息（svg） | ✗ | ✓ | ✓ |
| 4 | `AiPanel.vue:635` | 复制消息（两枚 svg 叠放） | ✗ | ✓ | ✓ |
| 5 | `AiPanel.vue:724` | 停止生成（9×9 白方块） | ✓ | ✓ | ✗ |
| 6 | `AiPanel.vue:734` | 发送（字符 `↑`） | ✗ | ✓ | ✗ |
| 7 | `AiPanel.vue:765` | mermaid 缩小 | ✗ | ✓ | ✓ |
| 8 | `AiPanel.vue:776` | 放大 | ✗ | ✓ | ✓ |
| 9 | `AiPanel.vue:787` | 复位 100% | ✗ | ✓ | ✓ |
| 10 | `AiPanel.vue:797` | 复制源码 | ✗ | ✓ | ✓ |
| 11 | `AiPanel.vue:808` | 下载 SVG | ✗ | ✓ | ✓ |
| 12 | `AiPanel.vue:818` | 关闭全屏 | ✗ | ✓ | ✓ |

**B. AiPanel 注入 HTML（mermaid 卡，3 个）** — 由 `buildMermaidCardHtml` 生成，按钮在渲染层注入的 HTML 里靠事件委托取值

| # | 位置 | 用途 | title | aria-label | tip 气泡 |
|---|---|---|---|---|---|
| 13 | `AiPanel.vue:1591` | 卡片「复位到 100%」（`mmdc-ireset`） | ✗ | ✓ | ✓（`mmdTipSpan`） |
| 14 | `AiPanel.vue:1592` | 卡片「全屏查看」（`mmdc-full`） | ✗ | ✓ | ✓ |
| 15 | `AiPanel.vue:1603` | 源码栏「复制源码」（`mmdc-copy`） | ✗ | ✓ | ✓ |

**C. 渲染层注入（1 个）**

| # | 位置 | 用途 | title | aria-label | tip 气泡 |
|---|---|---|---|---|---|
| 16 | `utils/markdown.ts:415` | 代码块 figcaption 的「复制代码」 | ✗ | ✓ | ✓ |

**D. `el-button` 图标钮（5 个，跨组件/跨页）**

| # | 位置 | 用途 | title | aria-label | tip 气泡 |
|---|---|---|---|---|---|
| 17 | `components/AiPanel/AiPanel.vue:32` | **收起 AI 面板**（`el-icon ArrowRight`） | ✓「收起」 | ✗ | ✗ |
| 18 | `components/wizard/ConditionForm.vue:69` | **删除条件行**（`:icon="Delete" circle`） | ✗ | ✗ | ✗ |
| 19 | `views/DataBrowserView.vue:11` | 刷新（`:icon="Refresh" circle`） | ✓「刷新」 | ✗ | ✗ |
| 20 | `views/DefinitionsView.vue:12` | 刷新 | ✓「刷新」 | ✗ | ✗ |
| 21 | `views/TaskCenterView.vue:12` | 刷新 | ✓「刷新」 | ✗ | ✗ |

> 注：另有三处**含图标但带文字**，不计入：思考卡头 `AiPanel.vue:261`、工具卡头 `:338`、回执卡头 `:291`；`回到底部 :694`（有可见文案）与 `继续 :584`（有「继续」文字）同样不计入。

**统计**

- 图标按钮总数 **21**
- 有 `aria-label` **16**／有 `title` **5**（互相重叠为 0，即无一钮同时具备两者——见下）
  - 仅 aria、无 title：A 组 12 + B 组 3 + C 组 1 = 16
  - 仅 title、无 aria：`:32`、`:19/:20/:21`（3 个刷新）= 4；`停止生成 :724` 两者都有
- 有 tip 气泡（`TIP_CLASS`，`group-hover/tip` + `group-focus-visible/tip` 控制显隐，`markdown.ts:343`）**14**（A 组 10 + B 组 3 + C 组 1）
- **零覆盖清单（无 title、无 aria-label、无 tip）**：**1 个** —— `components/wizard/ConditionForm.vue:69`「删除条件行」，纯图标圆钮、无任何可读名与提示（键盘/读屏只得一个无名词）
- 视觉提示缺口（有 aria 但鼠标用户无提示）：`发送钮 :734`（无 title、无 tip，仅靠 `↑` 与 placeholder 文案「Enter 发送」自解释）

## ② Enter / IME 现状

| 问题 | 结论 | 证据 |
|---|---|---|
| 绑定位置 | composer 是原生 `textarea ref="composerRef"`（`:714-723`），唯一键盘绑定 `@keydown.enter.exact.prevent="send"` | `AiPanel.vue:722`（注释同口径 `:709`） |
| Enter=发送 | ✓ | 同上 |
| Shift+Enter=换行 | ✓（`.exact` 要求无修饰键，Shift+Enter 不匹配 ⇒ 走浏览器原生换行，未被 `prevent`） | 同上 |
| Ctrl/Cmd+Enter | 不匹配 `.exact` ⇒ **不发送**，走原生（textarea 内无默认行为） | 同上 |
| **isComposing / 组合态处理** | **完全没有** —— 全 `frontend/src`+`frontend/tests` 搜 `composition`/`isComposing`/`.composing` **0 命中** | — |
| 中文输入法选字 Enter | **会触发发送**：keydown 无组合过滤，`.exact` 不排除 `isComposing`；且 `.prevent` 会拦掉候选上屏；又因 Vue `v-model` 在组合期不回填，`inputText` 仍是组合前的旧值 ⇒ 可能「发出旧文本/空文本 + 打断输入法」 | `:722` + `:717` `v-model="inputText"` |
| 运行中（loading）Enter | **被 disabled 拦死**：textarea `:disabled="!ai.canSend"`（`:721`），`canSend = !state.loading`（`stores/ai.ts:924-926`）⇒ 输入框禁用，keydown 根本不产生 | `:721`、`stores/ai.ts:924` |
| 运行中的兜底路径 | `send()` 内 `!ai.canSend → noticeBusySend()`（保留草稿 + 提示「本轮进行中：本条消息未发送」），**UI 层不可达**，代码注释自陈是纵深防御 | `AiPanel.vue:2534-2539`、`stores/ai.ts:1473-1475`、注释 `:2536` |
| Escape | **仅** mermaid 全屏查看器有（打开期间挂 `window` keydown，`closeMmdViewer`）；输入区／门卡／GF 表单／消息流**均无 Escape 绑定** | `AiPanel.vue:1951-1956`、注册于 `:1959` |
| 发送后清空 | ✓ `inputText.value = ''`（常规路 `:2561`；编辑重跑路 `:2555`） | — |
| 发送后聚焦 | **不回收焦点**：全文件 `focus()` 仅 2 处 —— 错误卡动作「改写后重发」（`:1115`）与查看器 stage（`:1894`）。发送即进入 loading ⇒ textarea 转 disabled ⇒ 焦点丢失，轮次结束后**不会自动回焦**，需用户手点 | `:1115, :1894` |

## ③ 发送 / 停止钮形态实况

| | 发送钮 | 停止钮 |
|---|---|---|
| 位置 | `AiPanel.vue:729-740` | `AiPanel.vue:724-733` |
| 尺寸/圆角 | `w-[28px] h-[28px] rounded-[8px]` | `w-[28px] h-[28px] rounded-[8px]`（同） |
| 颜色 | `bg-[#2563eb] text-white`；禁用 `disabled:bg-[#8b949e] disabled:cursor-default` | `bg-[#1f2328]`，`hover:brightness-[1.15]` |
| 内容 | 字符 `↑`（`text-[13px] leading-none`） | `span w-[9px] h-[9px] bg-white rounded-[2px]` |
| title | **无** | `title="停止生成"` |
| aria-label | `aria-label="发送"` | `aria-label="停止生成"` |
| 可见 tooltip | 无 | 无（title 走浏览器原生） |
| 禁用判据 | `:disabled="!canSubmit"`，`canSubmit = ai.canSend && ai.available && inputText.trim().length>0`（`:1255`） | 无 disabled |
| 切换 | `v-if="ai.loading"`（两钮互斥，同尺寸同位） | 同左 |

## 现状结论（一句话）

图标钮覆盖整体良好（21 中 16 有 aria、14 有自绘 tip 气泡），**唯一零覆盖是 `ConditionForm.vue:69` 的「删除条件行」**，另 `发送钮 :734` 虽有无障碍名但鼠标用户无任何悬停提示；键盘侧 **Enter/Shift+Enter 语义正确但与中文 IME 完全未接**（无 `isComposing`，组合期 Enter 会误发送并吞掉候选），运行中 Enter 因输入框被 `disabled` 拦死（兜底路径不可达），Escape 只在 mermaid 查看器内有效，发送后既不回收焦点也不豁免 IME——建议优先补 ①`ConditionForm` 删行钮的 `title/aria-label`、②composer 的 `@keydown.enter` 组合态守卫（`if (e.isComposing || e.keyCode === 229) return`）、③发送钮 hover 提示与发送后 `composerRef.focus()`。
