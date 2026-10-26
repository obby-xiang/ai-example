<!-- 历史中间稿证据条目（自动生成头部，非正文）。源 = Kimi Code assistant wire 文本；不含 think / 工具输出 / 原始用户消息。 -->

# MT-223 · 其它 · 1. 卡头 chevron / 展开交互：**总是渲染、总是可点，无任何守卫**（8809e58d / agent-934）

> **项目归属**：**项目硬锚点**（正文命中 ai-example 路径 / 项目类名 / 项目文档名 / 项目 UI 概念）。
> **归属依据**：正文命中：`AiPanel.vue`、`parts.ts`、`generative_form`、`SpToolCallingManager`。
> **稿态**：历史中间稿 —— 该 wire 第 `m122` 条 assistant 消息（m 按 wire 内 assistant 消息计，含工具调用轮）；本条是该 wire 第 `mt14` 条有正文的 assistant 消息（共 18 条有正文），末条有正文消息为 `m174`。**非现行基线、非验收结论、非当前缺陷清单**。
> **来源**：会话 `8809e58d` / agent `agent-934`（`main` = 主会话）；原字符 3,681（链内 1 段、合计 3,681）。
> **链号 / 类别**：`MT-223` / **其它**；判定：助手结论/验收记录（标题无主题词）；**不是用户批准**。
> **轮次**：位于该 wire 第 5 个用户轮之后；相邻用户轮未见决断类字样（仅记布尔，未复制用户原文）。
> **去重**：行指纹对「仓内 + 落盘语料」覆盖率 `5%`（<60% 视为仓内无等价件）；同 wire 后文覆盖率 `5%`。
> **与末条归档（138 件）的关系**：末条 `m174`（4,189 字符）未入末条归档。
> **脱敏**：路径 0 / 会话标识 0 / 身份 0 / 密钥 0 / 旧仓名与同源仓名 0 处。

---

查完代码与两版 demo，结论如下（HEAD=`5244dc6`）。

## 1. 卡头 chevron / 展开交互：**总是渲染、总是可点，无任何守卫**

- chevron 是**无条件渲染**的：`AiPanel.vue:344` `<span :class="[TCHEV_CLASS, isBlockExpanded(block) ? 'is-open' : '']">›</span>`，外面没有任何 `v-if`。
- 卡头 button 的禁用条件**只有 `requiresAction`**：`:330` `:disabled="part.requiresAction"`、`:328` `cursor-default : cursor-pointer`；`:331` `@click="toggleBlock(block)"`。
- `toggleBlock` 无内容判据，纯写三态展开记账：`:1947-1949` → `ai.setBlockExpanded(block.id, !isBlockExpanded(block))`。
- 全文件搜 `hasDetail / canExpand / 空壳 / 无参数 / 无输出 / 无可展开` —— **0 命中**；唯一提到「空壳卡」的是 `:378` 的注释，指的是**挂起子块**（A9'⑤），不是展开体整体。

对比：思考卡（`:294-315`）有 `part.text` 守卫（`v-if="part.kind === 'reasoning' && part.text"`），**工具卡没有对等守空**。

## 2. 展开体分区与「空内容」穷举

展开体 = `:346` `<div v-show="isBlockExpanded(block)" class="px-2 pb-2 pt-0 flex flex-col gap-1.5" data-testid="block-body-…">`，内含四个独立判据的区：

| 区 | 判据 | 行号 |
|---|---|---|
| 输入 args | `v-if="part.args"` | `:350-355` |
| 输出 result | `v-if="part.result"` | `:356-369` |
| 错误 reason | `v-if="part.reason"` | `:372-375` |
| 挂起子块 | `v-if="part.requiresAction"` → CONFIRM pending 门卡 `:383`；GF → FormRenderer `:458` | `:382-473` |

空内容组合（逐案）：

| 情形 | args | result | 展开体实际内容 |
|---|---|---|---|
| 已决工具（succeeded/failed）**无 args + 无 result + 无 reason** | 无 | 无 | **完全空**（只剩容器 `pb-2` = 约 8px 空白条，`pt-0`） |
| `running` 且 args 缺省 | 无 | 无 | 同上（空） |
| **FRONTEND 通用工具占位段**（`suspended` 帧建段，`parts.ts:478-484` 不写 args，name ≠ `generative_form`） | 无 | 无 | requiresAction=true ⇒ **强制展开且空**；卡头 `disabled` ⇒ 连收起都做不到 |
| 无参工具 `list_tasks` **args = `'{}'`** | `{}` | 视状态 | 输入盒显示 2 字符 `{}`（`CollapsibleText pretty` → `markdown.ts`/`CollapsibleText.vue:45`）——**不算空** |
| 无参工具 args 为 `''`/缺省 | 无 | 无 | 空 |
| CONFIRM 待批 + 无 args | 无 | 无 | 门卡仍在（`gateRows` 兜底只出「操作」行，`:2059-2062`）⇒ 非空 |
| GF 待填 + 无 args | 无 | 无 | FormRenderer 仍在（schema 来自 store）⇒ 非空 |

args 的真值来自上游原样透传（`SpToolCallingManager.java:458` `call.arguments()` → `SseChatEmitter.java:555` `frame.put("args", …)`），前端不归一（`parts.ts:360`）；**无参工具实发 `"{}"` 还是 `""` 取决于模型/上游**（本仓测试 fixture 一律写 `args: '{}'`，见 `frontend/tests/ai-panel-parts-render.spec.ts:132, 225-226` 等）。两种都有，守卫需两者都兜。

**用户点开看到什么**：succeeded/failed 的无参无输出工具 —— 卡头多出约 8px 空白，chevron 旋转 90°，**没有分隔线、没有任何文字**（`:325-345` 卡头无 `is-open` 边框、`:346` 展开体也无 `border-t`）。

## 3. demo 口径：该规则**只落在回执只读卡**，普通工具卡没有

- v7.9/v7.10 的场景 19-e 标题即「无参数纯确认（无 chevron）」，正文：「纯确认类动作…没有可渲染的参数 —— 卡头恒显行即全部信息，**不给 chevron、整卡不可点**，避免「点开是空」的假入口」，并给出判据「**展开体渲染后行数 ≥ 1 → 给 chevron；0 行 → 不给 chevron、不加按钮**」（v7.9.html 场景19-e；v7.10.html 同段）。
- 裁决区 19 条同样只写「无详情可展不给 chevron」（v7.9/v7.10 裁决条）。
- **普通工具卡无此规则**：v7.10 的 `stateCard()` 无条件拼 `'<span class="tchev">›</span>'` + `tcard-bd`（v7.10.html `function stateCard(s)`），10 态样张（v7.10 已扩到「9 态 × kind2 + expiredLocally」）**每张都带 input+output**，没有任何空内容工具卡样张。

## 4. 结论与建议口径

**现状行为**：工具卡 chevron 与展开交互是**恒有**的；只要 args/result/reason 三项皆空且非挂起，展开体就是**空盒**（约 8px 空白 + chevron 旋转），并可反复点开收起 —— 正是 demo 19-e 明确要消灭的「点开是空的假入口」，只是该规则尚未覆盖到普通工具卡。最尴尬的是 FRONTEND 通用工具占位段：**强制展开 + 空 + 卡头禁用**（无法收起）。

**建议口径**（与 demo 19-e 判据对齐，扩展到工具卡）：

1. **采用 19-e 的同一判据**：`展开体渲染后行数 ≥ 1 → 给 chevron + 可点；0 行 → 不渲染 chevron、`card-head` 不加 `cursor-pointer`/`hover:bg`、`aria-expanded` 不置位、点击 no-op`。
2. **`requiresAction` 例外保留**：门卡/GF 即使参数为空也必须强制展开（有实际交互内容），不受判据 1 影响。
3. **不推荐**用「无参数/无输出」占位文案填满空盒 —— 与 R-165 S5a 项 4「过程信息不设额外入口、不新增文案块」的纪律相斥，且会把「无内容」变成又一次噪音；占位只在**确实需要解释为何为空**时用（如 FRONTEND 通用工具占位段的「等待前端执行」可由状态 chip 承接，无需展开体）。
4. 若要落代码，落点极小：`AiPanel.vue:344`（chevron 加 `v-if`）、`:328-331`（head 可点性/禁用）、`:346`（空体时也可不渲染容器）；判据函数建议与 `blocks.ts` 同层（如 `hasToolBody(part)`）便于 spec 锁定。

**一句话**：现状是「无内容也有 chevron、点开是空盒」；demo 只在回执只读卡（19-e）立了「0 行不给 chevron」的规则，v7.10 需把同一条规则显式扩展到普通工具卡（并保留 `requiresAction` 强制展开例外）。
