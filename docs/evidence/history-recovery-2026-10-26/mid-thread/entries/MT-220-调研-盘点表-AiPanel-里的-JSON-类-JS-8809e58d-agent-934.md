<!-- 历史中间稿证据条目（自动生成头部，非正文）。源 = Kimi Code assistant wire 文本；不含 think / 工具输出 / 原始用户消息。 -->

# MT-220 · 调研 · 盘点表：AiPanel 里的 JSON / 类 JSON 展示点（8809e58d / agent-934）

> **项目归属**：**项目硬锚点**（正文命中 ai-example 路径 / 项目类名 / 项目文档名 / 项目 UI 概念）。
> **归属依据**：正文命中：`frontend/src/`、`AiPanel.vue`、`回执卡`、`FormRenderer.vue`。
> **稿态**：历史中间稿 —— 该 wire 第 `m40` 条 assistant 消息（m 按 wire 内 assistant 消息计，含工具调用轮）；本条是该 wire 第 `mt8` 条有正文的 assistant 消息（共 18 条有正文），末条有正文消息为 `m174`。**非现行基线、非验收结论、非当前缺陷清单**。
> **来源**：会话 `8809e58d` / agent `agent-934`（`main` = 主会话）；原字符 3,923（链内 1 段、合计 3,923）。
> **链号 / 类别**：`MT-220` / **调研**；判定：助手调研记录（历史调研，非当前结论）；**不是用户批准**。
> **轮次**：位于该 wire 第 2 个用户轮之后；相邻用户轮含决断类字样（仅记布尔，未复制用户原文）。
> **去重**：行指纹对「仓内 + 落盘语料」覆盖率 `4%`（<60% 视为仓内无等价件）；同 wire 后文覆盖率 `4%`。
> **与末条归档（138 件）的关系**：末条 `m174`（4,189 字符）未入末条归档。
> **脱敏**：路径 0 / 会话标识 0 / 身份 0 / 密钥 0 / 旧仓名与同源仓名 0 处。

---

调查完成。工作树干净（仅 `data/` 未跟踪），HEAD=`3212ebd`。重要前提：v7.9 的「回执 JSON 盒退役」裁决（`docs/research/裁决-回执原卡只读态-2026-10-15.md:7`）**尚未落到 HEAD** —— 回执「查看详情」JSON 盒、以及 v7.8 的 input 160 内滚（aj15）/ 回执详情 200 内滚（aj16）在代码里都还是旧形态。

## 盘点表：AiPanel 里的 JSON / 类 JSON 展示点

| # | 位置（文件:行号） | 展示内容 | 出现场景 | 默认可见？ |
|---|---|---|---|---|
| 1 | `frontend/src/components/AiPanel/AiPanel.vue:350-355`（mono 盒 :352；`CollapsibleText :text="part.args" pretty` :353；pretty 逻辑 `CollapsibleText.vue:40-49`） | **工具入参 args**（JSON 字符串 → `JSON.parse`+`stringify(...,2)` 缩进 2）；限高 **96px + 渐隐 +「显示更多」钮**（`CollapsibleText.vue:77-102`） | 展开任一工具卡；**pending 确认门/GF 卡因 `requiresAction` 被强制展开**（`utils/blocks.ts:60-63`），无需点击 | 普通卡：展开后可见；**pending CONFIRM/GF 卡：默认可见** |
| 2 | `AiPanel.vue:356-368`（:362 `{{ part.result }}`；截断角标 :362-367） | **工具输出 result**：纯文本，`whitespace-pre-wrap` + `max-h-[160px]` 内滚 —— **不是 markdown、无 JSON pretty/高亮**；后端返回 JSON 就原样裸 JSON | 工具执行完，展开卡 | 展开后可见 |
| 2b | 同上（文本里内嵌 JSON） | FRONTEND 工具参数不完整时，result 文案拼 `JSON.stringify(args)`：`frontend/src/utils/frontend-tools.ts:781`、`:925` | 前端工具参数错 | 展开后可见 |
| 3 | `AiPanel.vue:266-275`（内容由 `receiptDetailText` `:2041-2052` 生成，`pretty`） | **已决 CONFIRM/GF 的 args**（JSON pretty），GF 的 `submittedValues` 折进同一 JSON 的 `values` 键；mono 盒 + 限高 96 +「显示更多」 | 点回执「查看详情」 | **默认隐藏**（钮后）；回执卡本体恒可见 |
| 4 | `AiPanel.vue:248`（`hitlReceiptSummary` `:2381-2394`） | **args 前 3 键的 `key=value`**（对象值 `JSON.stringify`）——半 JSON 文本，恒可见行 | 已决 HITL 段 | **默认可见（恒可见）** |
| 5 | `AiPanel.vue:400-409`（`gateRows` `:2058-2072`） | **批准前参数**：首行「操作」=工具中文名，其后 args 前 4 键 key/value（对象值 `JSON.stringify`）；非 JSON args ⇒ 只出「操作」行 | 待确认态（CONFIRM pending） | **默认可见**（强制展开） |
| 6 | 同 #1（门卡同一卡体） | pending 时 **args JSON 盒与 #5 字段表并存** —— 同一份参数两份呈现 | 待确认态 | **默认可见** |
| 7 | `frontend/src/components/AiPanel/FormRenderer.vue:1-152`（字段区 :21-110；`data-testid="gf-fields"` :21） | **GF 表单 UI**（六型控件），**不是 schema JSON**；文案一律文本插值、永不 v-html（:17-18、:160） | GF 待填态 | **默认可见** |
| 8 | 同 #1（GF 同一卡体） | **整份表单 schema JSON**（args = `{form:{fields:[...]}}`，store 从 `parseToolArgs(args).form` 解析：`frontend/src/stores/ai.ts:1928`、`:1177`） | GF 待填态 | **默认可见**，与表单 UI 重复（v7.8 aj15 实录：本样例 22 行/442px） |
| 9 | `frontend/src/utils/markdown.ts:94-108`、`:402-414` + `AiPanel.vue:994`（`CODE_PRE_LIMIT_CLASS` 200px 内滚） | AI 正文里的 ` ```json ` 等围栏 → **高亮 pre**（图容器 + 复制钮） | AI 正文含代码块 | **默认可见**（正文） |
| 10 | `AiPanel.vue:1463`（`MMD_SOURCE_BOX_CLASS`）、注入点 `:1523`，`.mmdc-src` 200px 内滚 | mermaid **源码**盒（非 JSON） | 正文含 mermaid，切「源码」tab | 折叠（tab 后可见） |
| 11 | `AiPanel.vue:603-628`（:617 `{{ m.errorCard.detail }}`）；`frontend/src/utils/error-card.ts:95-111` | 错误 detail = 后端 `message` **纯文本**，无任何 JSON 格式化（若后端 message 自身是 JSON 则原样） | 出错那一轮 | **默认可见** |

补充事实：Vue 侧**没有** `pre[data-json]`（那只存在于 demo html）；mono 盒用 `div` 而非 `pre`，理由见 `AiPanel.vue:1460-1462`。`CollapsibleText` 的 `pretty` 只被两处使用（#1 args、#3 回执详情）。

## 逐条回答

**2. 默认可见 vs 展开后才见**
- **默认可见**：#4 回执摘要、#5 门卡字段表、#6 pending 卡的 args JSON 盒、#7 GF 表单、#8 GF 的 schema JSON、#9 正文 JSON 代码块、#11 错误 detail。
- **需主动点开**：#3 回执详情（「查看详情」钮）、#10 mermaid 源码（tab）、#2 输出/#1 输入在**普通卡**上需展开卡头。
- 关键机制：**`requiresAction` 卡被强制展开且卡头 `disabled`**（`blocks.ts:60-63`、`AiPanel.vue:328-331`）——所以门卡/GF 待动作态的卡体内容（含 args JSON 盒）**无需点击即见**。

**3. result 的渲染**：纯文本，不是 markdown、不是 JSON 组件（`AiPanel.vue:356-368`）。工具输出若是 JSON 结构 → 直接以原始 JSON 文本落在 160px 内滚盒里，无缩进美化、无高亮、无键值化。

**4. 错误卡 detail**：`m.errorCard.detail` = 后端错误文案原样透传（`error-card.ts:33`、`:95-111`），纯文本一行，**无 JSON 形态**。

**5. 门卡（待确认）参数**：用户批准前看到的是**渲染字段表**（`gateRows`：操作/键/值，前 4 键），**不是** JSON——但同一张卡体上方还并列着一条 pretty JSON 的「输入」盒（#6），所以实际是「字段表 + 原始 JSON」双份。

**6. GF 表单待填态**：用户看到的是**表单 UI**（FormRenderer 六型控件），不是 schema JSON——但同卡体的「输入」盒就是**整份 schema JSON**（#8），默认可见。

## 一句话总结

终端用户视角最可疑的 JSON 展示点 = **门卡/GF 待动作态那条「输入」args JSON 盒**（pending 卡强制展开 ⇒ 默认可见，与门卡字段表重复、与 GF 表单重复，GF 那份就是整份表单 schema JSON）**，以及已决态「回执查看详情」与「工具卡输入」两处重复的 pretty JSON 盒**；另加工具**输出**对 JSON 结构零处理的裸文本直出（`AiPanel.vue:356-368`）——这三点正是 v7.9 裁决要退役/改内滚的对象。
