# 视觉差距：真实界面 vs demo v6（R-163 · AI 对话面板）

- 对象：真实 `http://localhost:5202/tasks`（前端 dev，后端 8081 真实 DeepSeek 已通）｜基准 `http://localhost:18330/`（demo v6，切「新设计」视图）
- 方法：Kimi 桌面浏览器（`mcp__desktop_browser__run`）同窗口同视口（989×1253，deviceScaleFactor 1）截图；像素级取样/墨迹盒测量用 `shots/measure*.ps1`（System.Drawing，输出 `measure*-out.txt`）
- 复现动作：真实面板发「你好，用 markdown 回答：标题、列表、代码块、表格各来一点」→ 等流式完成 → 截图；再发「列出全部配置定义」触发工具卡 → 截图
- 截图目录：`r163-chat-ui/shots/`（命名 `NN-real-*.png` / `demo-*.png`）
- 时间：2026-10-14 21:20–21:35

## 结论摘要（对用户三点观感的核实）

| 用户说法 | 核实 | 依据 |
| --- | --- | --- |
| 「出现横向滚动条」 | **成立（BLOCKER）** | 消息容器底部常驻横向滚动条；横向拖动整片内容位移，代码行右侧才露出来 |
| 「跟 demo 完全不一样」 | **部分成立（1 BLOCKER + 3 MAJOR）** | 骨架（去头像/无气泡全宽/时间位/工具卡行/HITL 等结构）大体对齐 demo；但**正文排版整链未生效**（标题字号约 2 倍、代码块无容器样式）、**用户气泡配色与 demo 相反**、**工具卡外观不符** |
| 「惨不忍睹」 | **主因 = 标题字号爆炸 + 代码块裸奔 + 表格塌陷 + 滚动条** | 见 S1–S5，均有像素/源码级证据 |

## 差距清单（按严重度）

### S1【BLOCKER】横向滚动条常驻，代码行被裁切
- 现象：AI 面板消息区底部常驻一条带左右箭头的横向滚动条（thumb 约占轨道 85%）；正文里的 JSON 代码块整行被右边缘裁掉（首屏只到 `"val`／拖动后可见 `"value`）。
- 真实截图：`03-real-hscrollbar-zoom.png`（滚动条放大）、`05-real-codeblock-area.png`（裁切态）、`06-real-after-hscroll-right.png`（横向拖动后）、`09-real-hscrollbar-zoom2.png`
- demo 截图：`demo-01-panel-overview.png`、`demo-04-markdown-code-table-mermaid.png`（demo 面板无任何横向滚动条）
- 差距描述：demo 的代码块在容器内**自带内部滚动/换行**，整块不越界；真实界面把越界溢出到**消息滚动容器**这一层，导致整片消息（含表格）横向可拖，且右侧内容被输入框上方的滚动条压住。

### S2【MAJOR】Markdown 正文排版链整体未生效 → 标题字号≈2 倍
- 现象：助手正文的 H1「全部配置定义清单」「配置管理助手 · 回复格式演示」远大于正文；H2「一、GLOBAL（全局级，7 个）」「二、代码块」同样放大；列表缩进、段落间距、引用左边线等全部按浏览器默认表现。
- 实测（墨迹盒，同视口）：真实 H1 单字宽 **≈27 px/字**（8 字 216 px，墨迹高 34 px）vs demo 标题「关键发现」**14 px/字**（4 字 56 px，墨迹高 12 px）；真实正文/气泡 ≈13–14 px（用户气泡墨迹高 13 px），demo 正文 ≈11.8 px/字。
- 真实截图：`10-real-toolcard-and-h1.png`（工具卡 + H1 放大图）、`04-real-conversation-top.png`、`11-real-config-list-response.png`
- demo 截图：`demo-04-markdown-code-table-mermaid.png`、`demo-01-panel-overview.png`
- 差距描述：规格（`AiPanel.vue:1442-1445`）h1=17px/h2=16px/h3=15px；实测单字宽 27 px、墨迹高 34 px（≈浏览器默认 `h1{font-size:2em}` 量级），即标题约放大 **1.6–2 倍**。
- 根因（源码级）：`frontend/src/components/AiPanel/AiPanel.vue:1376` 为 `<style scoped>`，且文件内 **无任何 `:deep()`**；`.md-body` 的 23 处规则里，后代选择器（`.md-body h1/h2/pre/table/th/code/blockquote/ul…`）编译后要求节点带 scope 属性，而 `v-html` 注入节点不带 → **全部不命中**；只有 `.md-body` 自身（带属性）的继承属性（font-size/line-height/overflow-wrap）生效。

### S3【MAJOR】代码块裸奔：无灰底/无边框/无语言标签/无复制
- 现象：JSON 代码块直接白底贴在正文里，无圆角灰底容器、无边框，也没有 demo 的头部条（语言名 + `highlight.js` + `复制`）。
- 实测底色：真实代码区采样 **#FFFFFF**（且无 `#F8F8F9` 像素，规格 `AiPanel.vue:1460-1469` 的灰底+边框未命中）；demo 代码区 **#F8F8F8** + 头部条 **#F6F7F9** + 分隔线 **#EEF0F3**。
- 真实截图：`07-real-codeblock-clipped-zoom.png`、`05-real-codeblock-area.png`
- demo 截图：`demo-04-markdown-code-table-mermaid.png`、`demo-03-toolcards-json-zoom.png`
- 附加：语法高亮有（hljs github 主题：`#005CC5`/`#032F62`），但代码块**没有实现自身横向滚动**（`overflow-x:auto` 未命中）→ 直接催生 S1。

### S4【MAJOR】用户气泡配色与 demo 相反（蓝 vs 中性灰）
- 实测底色：真实用户气泡 **#D9ECFF**（`AiPanel.vue:170 bg-[#d9ecff]`）｜demo 用户气泡 **#F6F7F9**（中性灰）。
- 真实截图：`04-real-conversation-top.png`｜demo 截图：`demo-02-user-bubble-alert-zoom.png`
- 差距描述：demo 色彩纪律写明「UI 中性灰为主 · 1 个品牌蓝 · 语义色仅用于状态」；demo 里蓝只用于「进行中」状态 chip。真实界面把**每条用户消息**刷成品牌蓝，是观感「不像一个产品」的主要来源之一。（结构上右侧、圆角、时间靠右与 demo 一致。）

### S5【MINOR】表格排版塌陷
- 现象：`三、表格` 区表头「是否需要你确认」折成 2–3 行，列宽被挤压、单元格文字折行、行高不稳；表格与滚动条重叠（最后一行被横向滚动条压住）。
- 真实截图：`08-real-table-zoom.png`｜demo 截图：`demo-04-markdown-code-table-mermaid.png`（demo 表格：列宽舒展、表头单行、行高一致）
- 归因：同 S2（`.md-body th,td{padding:.35em .7em}`、`table{width:100%}` 未命中，回落浏览器默认）。

### S6【MINOR】工具卡外观不符（真实=带边框盒子；demo=单行无边框 + 状态 chip）
- 现象：真实工具卡是一圈浅色边框的盒子，行内为「☰ 列出配置定义　列出全部配置定义　[已成功]」——工具名与摘要文案高度重复；demo 为一行 `⟳ web_search 正在搜索 5 个站点… [调用中] ›`／`✓ str_replace_editor 已编辑 5 个文件 +27 −23 [已成功] ˅`，无外框、靠细分隔线，名称与摘要信息互补。
- 真实截图：`10-real-toolcard-and-h1.png`｜demo 截图：`demo-03-toolcards-json-zoom.png`
- 备注：真实侧本轮只触发到「已成功」1 态；demo 有 9 态可对照，其余 8 态未在实机出现（见 S7）。

### S7【MINOR】demo 覆盖的场景在实机未出现/无法对照
未在实机触达或未实现（故本轮无实机截图）：断流 Alert（demo 场景 1）、AI 流式末尾光标（3）、mermaid 图（6）、思考过程默认折叠（7）、工具卡执行失败（8）、HITL 待批卡（9）、回执卡（10）、generative_form（11/12）、长文段不折叠对照（13，实机长文确实未折叠 ✔）、本轮已取消（15）。
- 对照 demo：`demo-01-panel-overview.png`（场景清单）、`demo-02-user-bubble-alert-zoom.png`（断流 Alert 样式：红描边卡片 + 「!」+ 重试按钮）
- 差距描述：实机缺少 demo 的断流 Alert 样式与状态行（demo 面板顶「● 正常 · ai-example · 数据版本 v41 · contextSync 已同步」），实机只有「可用 / 会话 / 工作区 / 数据版本 / 重新检测」一行。

### S8【NIT】整体密度/字号观感偏大
- 真实正文 ≈13–14 px、标题默认 2em；demo 正文 ≈12 px、标题 14–15 px。同宽（≈375 px）面板下，真实界面因此显得「字大、行疏、易折行」，进一步放大 S1/S5 的溢出问题。

## 横向滚动条专节（出在哪层容器 / 什么内容撑爆）

**出在哪层容器**
- 定位：`frontend/src/components/AiPanel/AiPanel.vue:119`
  `<div ref="msgWrapRef" role="log" class="flex-1 overflow-y-auto p-3" @scroll.passive="onMsgScroll">`
  —— **消息滚动容器**。该容器只声明了 `overflow-y-auto`；按 CSS 规则，另一轴 `visible` 会被计算为 `auto`，于是内容一旦越界，就在**容器底部**（输入框正上方）出现横向滚动条。
- 实测证据：滚动条位于容器底边（`03-real-hscrollbar-zoom.png`、`09-real-hscrollbar-zoom2.png`）；把该容器横向拖 260 px 后，**整片消息**（代码块与三张表格）一起左移，代码行右侧 `"value"` 才露出（对比 `05-real-codeblock-area.png` → `06-real-after-hscroll-right.png`）→ 越界发生在容器层，不是某个卡片的内部滚动。
- 越界量估算：滚动条 thumb ≈ 轨道的 85% → scrollWidth ≈ clientWidth × 1.18，面板内容宽 ≈375 px → 溢出 ≈ 60–70 px。

**什么内容撑爆**
- 直接原因：助手正文里的**围栏代码块**（`.md-body pre`，规格 `AiPanel.vue:1460-1469`）。该块长 JSON 行（例：`{ "fieldCode": "code", "operator": "EQ", "value": … }`）宽度 ≈ 容器宽 + 65 px（`07-real-codeblock-clipped-zoom.png` 可见 `"valu` 处被切断）。
- 为什么没在代码块内部消化：规格给的 `overflow-x: auto` **没生效**——同 S2 根因（`<style scoped>` + 无 `:deep()`，v-html 注入的 `pre` 不带 scope 属性）。代码块因此既不换行也无内部滚动条，只能把溢出传给上层容器。
- 次要参与：`三、表格` 中 `width:100%` 未生效的表格在挤压后仍按内容 min-content 撑宽（表头「是否需要你确认」折行、单元格长串不换行），与代码块叠加。

**修法建议（供施工参考，本轮未改任何文件）**
1. 把 `.md-body` 相关规则从 `<style scoped>` 中释放：改用 `:deep(.md-body h1)` 等，或将整套 `.md-body` 样式移到**非 scoped** 的全局样式块/`src/styles/main.css`（推荐后者，一处生效、避免逐条 `:deep()`）。
2. 复核修好后给 `pre` 补上 `overflow-x:auto` 与灰底/边框，并在容器层补 `min-w-0` 兜底；表格补 `table-layout:auto` + 单元格 `overflow-wrap:anywhere`。
3. 验收：面板底部不再出现横向滚动条；`scrollWidth === clientWidth`（消息容器）；h1 实测 ≈17 px 而非 32 px。

## 截图清单（r163-chat-ui/shots/）

真实界面：
| 文件 | 内容 |
| --- | --- |
| 01-real-initial-empty-panel.png | 首次打开：空态（纸飞机 + 建议词 chip + 输入框） |
| 02-real-markdown-response.png | 首条 markdown 回复完成态（含横向滚动条） |
| 03-real-hscrollbar-zoom.png | **横向滚动条局部放大**（带左右箭头，thumb ≈85%） |
| 04-real-conversation-top.png | 会话顶部：用户蓝气泡 + 时间右置 + H1 放大 + 列表 |
| 05-real-codeblock-area.png | 代码块区：长行被右边缘裁切 |
| 06-real-after-hscroll-right.png | 容器横向拖动后：露出 `"value`，整片内容左移 |
| 07-real-codeblock-clipped-zoom.png | 代码块局部放大（白底、无边框、无内部滚动条） |
| 08-real-table-zoom.png | 表格局部放大（表头折行、行被滚动条压住） |
| 09-real-hscrollbar-zoom2.png | 横向滚动条二次放大（与表格末行叠加） |
| 10-real-toolcard-and-h1.png | 工具卡「已成功」行 + H1「全部配置定义清单」放大 |
| 11-real-config-list-response.png | 第二条回复完成态（H1/H2 + 3 张表格 + 汇总表） |

demo v6（18330，新设计视图）：
| 文件 | 内容 |
| --- | --- |
| demo-00-view-before-toggle.png | 未切换（现状视图）留档 |
| demo-01-panel-overview.png | 新设计全场景（面板 + 场景清单 + 合规栈 + 色彩纪律） |
| demo-02-user-bubble-alert-zoom.png | 面板顶部放大：断流 Alert + 中性灰用户气泡 + 时间右置 |
| demo-03-toolcards-json-zoom.png | 工具卡（调用中/已成功）+ INPUT/OUTPUT JSON 放大 |
| demo-04-markdown-code-table-mermaid.png | Markdown 完成态：标题/列表/引用/勾选列表 + 带头部条的代码块 + 表格 + mermaid |

测量脚本与输出：`shots/measure.ps1`、`measure2.ps1`、`measure3.ps1`，`measure-out.txt`、`measure2-out.txt`、`measure3-out.txt`（供复核重跑；真实/demo 关键取值见 S2 与横向滚动条节）。

## 方法与局限

- 同窗口、同视口（989×1253，DSF=1）截取，字号用墨迹盒（`lum<150`）测量，误差 ±1 px；demo 与实机字体栈不同（demo 为演示字体栈），故**请按比例读**，绝对值仅供参考。
- 实机只触达「助手正文 + 用户消息 + 工具卡已成功态」；9 态工具卡、HITL、mermaid、断流等场景未在实机出现，S7 按「无法对照/未实现」列，未做实现级判定。
- 本轮**未修改任何仓库文件、未改动任何服务**；仅新增本报告与 `shots/` 下截图/测量脚本。
> 入库脱敏（DC-08 · 提交官 2026-10-15）：仓外盘符路径 → 仓外相对口径（去 `<SCRATCH>` 前缀）（2 处）；旧仓目录名 → `ai-example`；密钥/令牌 0 命中（`sk-` 扫描 0）；其余逐字节同仓外源。
