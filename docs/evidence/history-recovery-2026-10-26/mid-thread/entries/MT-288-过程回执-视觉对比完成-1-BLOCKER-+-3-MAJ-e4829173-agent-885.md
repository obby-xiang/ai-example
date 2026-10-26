<!-- 历史中间稿证据条目（自动生成头部，非正文）。源 = Kimi Code assistant wire 文本；不含 think / 工具输出 / 原始用户消息。 -->

# MT-288 · 过程回执 · 视觉对比完成：1 BLOCKER + 3 MAJOR + 4 MINOR，横向滚动条已定位到容器层（e4829173 / agent-885）

> **项目归属**：**项目硬锚点**（正文命中 ai-example 路径 / 项目类名 / 项目文档名 / 项目 UI 概念）。
> **归属依据**：正文命中：`回执卡`、`frontend/src/`、`AiPanel.vue`。
> **稿态**：历史中间稿 —— 该 wire 第 `m144` 条 assistant 消息（m 按 wire 内 assistant 消息计，含工具调用轮）；本条是该 wire 第 `mt58` 条有正文的 assistant 消息（共 89 条有正文），末条有正文消息为 `m239`。**非现行基线、非验收结论、非当前缺陷清单**。
> **来源**：会话 `e4829173` / agent `agent-885`（`main` = 主会话）；原字符 2,988（链内 1 段、合计 2,988）。
> **链号 / 类别**：`MT-288` / **过程回执**；判定：过程回执（自证/元数据/交付回报）；**不是用户批准**。
> **轮次**：位于该 wire 第 6 个用户轮之后；相邻用户轮未见决断类字样（仅记布尔，未复制用户原文）。
> **去重**：行指纹对「仓内 + 落盘语料」覆盖率 `4%`（<60% 视为仓内无等价件）；同 wire 后文覆盖率 `0%`。
> **与末条归档（138 件）的关系**：末条 `m239`（3,029 字符）未入末条归档。
> **脱敏**：路径 2 / 会话标识 0 / 身份 0 / 密钥 0 / 旧仓名与同源仓名 0 处。

---

## 视觉对比完成：1 BLOCKER + 3 MAJOR + 4 MINOR，横向滚动条已定位到容器层

**报告落盘**：`<LOCAL_WORK>/r163-chat-ui/视觉差距-真实vsDemo.md`（12.9 KB）｜**截图**：`<LOCAL_WORK>/r163-chat-ui/shots/`（真实 11 张 + demo 5 张 + 测量脚本/输出）

### 差距清单（按严重度）
| # | 级别 | 现象 | 真实截图 | demo 截图 |
|---|---|---|---|---|
| S1 | **BLOCKER** | 消息区底部常驻横向滚动条，JSON 代码行在右边缘被裁（首屏只到 `"val`，横向拖 260px 后才见 `"value`） | `03-real-hscrollbar-zoom.png`、`05-real-codeblock-area.png`、`06-real-after-hscroll-right.png` | `demo-04-markdown-code-table-mermaid.png`（demo 无横向滚动条） |
| S2 | MAJOR | **正文排版链整体未生效**：H1 实测 **27 px/字**（墨迹高 34）vs demo 标题 **14 px/字**；列表缩进/段间距/引用线全回落浏览器默认 | `10-real-toolcard-and-h1.png`、`04-real-conversation-top.png` | `demo-04-*.png`、`demo-01-panel-overview.png` |
| S3 | MAJOR | 代码块裸奔：实测底色 **#FFFFFF**（无 #F8F8F9 灰底、无边框），且无 demo 的语言标签条/`highlight.js`/`复制` | `07-real-codeblock-clipped-zoom.png` | `demo-04-*.png`（#F8F8F8 + 头部条 #F6F7F9 + #EEF0F3 线） |
| S4 | MAJOR | 用户气泡配色与 demo 相反：真实 **#D9ECFF 品牌蓝** vs demo **#F6F7F9 中性灰**（demo 色彩纪律：灰为主、蓝仅用于"进行中"） | `04-real-conversation-top.png` | `demo-02-user-bubble-alert-zoom.png` |
| S5 | MINOR | 表格塌陷：表头「是否需要你确认」折 2–3 行、列被挤压、末行被滚动条压住 | `08-real-table-zoom.png` | `demo-04-*.png`（列宽舒展、表头单行） |
| S6 | MINOR | 工具卡是**带边框盒子**且工具名/摘要文案重复（「列出配置定义 / 列出全部配置定义」）；demo 为无边框单行 + 状态 chip + chevron | `10-real-toolcard-and-h1.png` | `demo-03-toolcards-json-zoom.png` |
| S7 | MINOR | demo 15 场景中实机未出现/无法对照：断流 Alert、状态行、思考折叠、mermaid、HITL、回执卡、GF 表单、失败/取消态（长文不折叠 ✔ 已符合） | —（仅 `04/11` 可对照正文） | `demo-01-panel-overview.png`、`demo-02-*.png` |
| S8 | NIT | 密度观感偏大：真实正文 ≈13–14px / 标题 2em，demo 正文 ≈11.8px/字、标题 14–15px | `10-*.png`、`11-real-config-list-response.png` | `demo-04-*.png` |

### 横向滚动条定位（专节已写入报告）
- **出在哪层容器**：`frontend/src/components/AiPanel/AiPanel.vue:119` 的 **消息滚动容器** `ref="msgWrapRef" role="log" class="flex-1 overflow-y-auto p-3"`。它只声明 `overflow-y-auto`，按 CSS 规则另一轴被算作 `auto`，内容越界时就在**容器底边**（输入框正上方）冒出横向滚动条。
- **实测判据**：横向拖 260px 后**整片消息**（代码块 + 3 张表格）一起左移、代码右侧 `"value` 才露出 → 越界在容器层，不是卡片内部滚动；thumb ≈轨道 85% → scrollWidth ≈ clientWidth×1.18（溢出 ≈60–70px）。
- **什么内容撑爆**：助手正文的**围栏代码块**（`.md-body pre`，规格 `AiPanel.vue:1460-1469`）长 JSON 行 ≈容器宽+65px；规格给的 `overflow-x:auto` **未生效**（见根因），既不换行也无内部滚动条，只能把溢出上抛；表格 `width:100%` 未生效后按 min-content 撑宽为次要因素。
- **根因（源码级，解释 S1/S2/S3/S5）**：`AiPanel.vue:1376` 是 `<style scoped>` 且**全文无 `:deep()`**，`.md-body` 的后代选择器（h1/pre/table/code/ul/blockquote…）编译后要求节点带 scope 属性，而 **v-html 注入节点不带** → 全部不命中；只有 `.md-body` 自身的继承属性（font-size/line-height/overflow-wrap）生效。`.md-body` 规则仅存在于该文件（全局无补偿规则），且 `main.css` 30 行仅 html/body 复位、Tailwind preflight 已关闭。
- **修法建议（报告内，未改任何文件）**：把 `.md-body` 整套规则移出 scoped（或非 scoped 全局块），再补 `pre{overflow-x:auto}`+灰底边框、容器 `min-w-0` 兜底、表格 `overflow-wrap:anywhere`；验收 `scrollWidth===clientWidth`、h1 实测 ≈17px。

### 行动与边界
- 在真实面板发了两条测试消息（markdown 示例、「列出全部配置定义」触发工具卡），无其他交互。
- **未修改任何仓库文件、未动任何服务**；`git status --porcelain` 仅 `?? data/`（Oct 12 起的既有未跟踪运行目录，非本轮产生）。仅新增报告与 `shots/` 下截图/测量脚本。
- 备注：只读检查发现仓库 HEAD 已是 `59ccdc4`（R-163 已入库），故本轮测量对象 = 当前已提交实现；demo 与实机字体栈不同，字号差异请按比例读。
