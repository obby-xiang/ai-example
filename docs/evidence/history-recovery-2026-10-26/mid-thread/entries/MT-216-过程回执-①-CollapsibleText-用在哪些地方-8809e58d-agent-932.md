<!-- 历史中间稿证据条目（自动生成头部，非正文）。源 = Kimi Code assistant wire 文本；不含 think / 工具输出 / 原始用户消息。 -->

# MT-216 · 过程回执 · ① CollapsibleText 用在哪些地方（8809e58d / agent-932）

> **项目归属**：**项目硬锚点**（正文命中 ai-example 路径 / 项目类名 / 项目文档名 / 项目 UI 概念）。
> **归属依据**：正文命中：`demo v7`、`frontend/src/`、`AiPanel.vue`、`generative_form`。
> **稿态**：历史中间稿 —— 该 wire 第 `m25` 条 assistant 消息（m 按 wire 内 assistant 消息计，含工具调用轮）；本条是该 wire 第 `mt5` 条有正文的 assistant 消息（共 8 条有正文），末条有正文消息为 `m35`。**非现行基线、非验收结论、非当前缺陷清单**。
> **来源**：会话 `8809e58d` / agent `agent-932`（`main` = 主会话）；原字符 4,509（链内 1 段、合计 4,509）。
> **链号 / 类别**：`MT-216` / **过程回执**；判定：过程回执（自证/元数据/交付回报）；**不是用户批准**。
> **轮次**：位于该 wire 第 2 个用户轮之后；相邻用户轮含决断类字样（仅记布尔，未复制用户原文）。
> **去重**：行指纹对「仓内 + 落盘语料」覆盖率 `3%`（<60% 视为仓内无等价件）；同 wire 后文覆盖率 `0%`。
> **与末条归档（138 件）的关系**：末条 `m35`（5,212 字符）未入末条归档。
> **脱敏**：路径 2 / 会话标识 0 / 身份 0 / 密钥 0 / 旧仓名与同源仓名 0 处。

---

调查完成。以下是 demo v7.8 第 17 项「『显示更多』统一规范（截断展开钮）」的场景结论。

## ① CollapsibleText 用在哪些地方

组件本体 `frontend/src/components/AiPanel/CollapsibleText.vue`（104 行）：收起态限高（`maxHeight` 默认 96px，思考区传 160px，见 `:25-34`），真溢出（`scrollHeight > maxHeight`，`:54-65`）才加限高类 + 底部渐隐 + 「显示更多」钮（`:74-81, :94-102`）。

AiPanel 里**当前只剩 3 处**调用，全部是"展开体内部的长内容"：

| # | 内容 | 行号 | 限高 | 具体例子 | 外层 v-show 父节点 |
|---|---|---|---|---|---|
| 1 | **工具卡「输入」参数** args | `AiPanel.vue:350-354`（`:353`） | 96px | `select_definitions` 的 `codes: [...]`、`download_export_files` 的 `defCodes`、`generative_form` 的整份表单 schema —— 展开工具卡后「输入」区的长 JSON | `:346` `v-show="isBlockExpanded(block)"` |
| 2 | **思考过程** reasoning 正文 | `AiPanel.vue:309-313`（`:313`） | 160px | AI 的长思维链文本 | `:309` `v-show="isBlockExpanded(block)"` |
| 3 | **回执卡详情 JSON** | `AiPanel.vue:266-275`（`:274`） | 96px | HITL 原始参数 / GF 已提交值（`receipt-more-<toolCallId>`） | `:266` `v-show="receiptArgsOpen.has(...)"` |

**注意：工具卡的「输出」已不用它了** —— R-165 S5a 项 4 把输出区改成纯限高内滚（`max-h-[160px] overflow-y-auto`，无展开钮），CollapsibleText 在此退役（`AiPanel.vue:356-368`，注释 `:358-359` 明写「原 CollapsibleText 的渐隐 +『显示更多』钮退役」）。

另外，**用户长消息的折叠/展开不是 CollapsibleText**，是 AiPanel 里另写的一套（`AiPanel.vue:481-492` 的 `user-msg-more-*` 钮、`:981-986` 的 `USER_MSG_MAX_HEIGHT_PX=184`、`:1042-1074` 的 measure）。这一套不受下面第 3 点的 bug 影响。

## ② demo v7.8 第 17 项的改动内容

裁决区第 17 点 `<LOCAL_WORK>/r163-demo/v7.8.html:630`（`id="aj17"`，标题「『显示更多』统一规范（截断展开钮）」）：

- **来源**：用户实机截图实报 —— 「内容截断处的展开控件用的是 **EP 风格边框小钮「显示更多」**，与 11 已定的文字链规范不是一套」。
- **统一成什么**：全站统一为第 11 点的规范 —— **底部左对齐文字链**「**展开 ▾ / 收起 ▴**」，`11.5px`、色 `#57606a`（hover 转 `#2563eb`）、距内容 **8px**、无边框无底色、带 `aria-expanded`；统一类 `mt-[8px] block text-left text-[11.5px] text-[#57606a] hover:text-[#2563eb]`；遮罩沿用「末 90px 渐隐」绝对值。
- **改动前的形态差异**（demo 现状栏列了四处，`v7.8.html:630` 内）：
  1. 长消息折叠钮（现状样张 `data-cl-more`）= **EP 描边小钮「显示更多」**：`mt-[6px] border border-[#e3e6ea] bg-white rounded-[6px] px-[9px] py-[2px] text-[11.5px]`，居中；
  2. 03 代码块「展开全高」= 描边小钮 11.5px / `rounded-[6px]`；
  3. 12 mermaid「查看源码」= 描边小钮 11.8px / `rounded-[8px]`；
  4. 回执「查看详情」= 文字钮 + 下划线（**已属文字链族，保留**）。
- **口径（第 13 点同源）**：改变可见内容量的控件 = 文字钮；站内所有「截断/展开」入口（长消息、CollapsibleText、代码块展开、详情展开）只允许两种形态 —— **底部左对齐文字链**，或**彻底删除改用纯限高内滚**；**边框式展开小钮一律不再出现**。

**代码落地现状（未完成）**：三处 CollapsibleText 的钮仍是 `class="mt-0.5 text-xs text-[#409eff]"`（12px 蓝字、`CollapsibleText.vue:97`）——既不是 demo 现状栏的 EP 描边钮，也不是改后规范；用户长消息钮也仍是 `mt-[6px] text-[12.2px] text-[#2563eb]`、文案「展开/收起」（`AiPanel.vue:487-491`），即第 11/17 项的改后口径（11.5px 灰字、mt-8、`展开 ▾`）尚未落到代码。

## ③ 「v-show 子树测高为 0」bug 的机制与影响（已确认）

**机制**：`CollapsibleText` 只在 `onMounted → nextTick` 量一次高（`CollapsibleText.vue:54-65`），读 `el.scrollHeight`。但上面表格里三处的**外层父节点都是 `v-show`**（`:266 / :309 / :346`），默认 `display:none` ⇒ 子树无布局 ⇒ `scrollHeight = 0` ⇒ `measure()` 判 `0 > 96/160 = false` ⇒ `overflowed=false` ⇒ **不加限高类、不渲渐隐、不渲「显示更多」钮**。之后用户把卡展开时**没有任何重测**（组件无 RO、无 watcher，`toggle` 也永远走不到——钮根本没渲），所以 `overflowed` 永久为 false。

**影响（用户实际看到什么）**：
- **长内容从不折叠**：展开工具卡看「输入」参数、展开思考过程、点回执「查看详情」看 JSON —— 一律**全量撑开**，既不裁切也不渐隐，也**没有「显示更多」/「收起」钮**。
- 反例不受影响：`requiresAction` 的确认门/GF 卡在挂载时是强制展开的（`blocks.ts` 的 `defaultExpanded`），父节点可见 ⇒ 量高正常；用户长消息那套用 `max-height + overflow-hidden`（非 display:none）⇒ 量高也正常（其注释 `AiPanel.vue:1049` 明确说「收起态下 scrollHeight 仍为内容自然高」）。
- **测试掩盖了它**：spec 用「注高法」直接 `Object.defineProperty(el,'scrollHeight',{value:500})`（`frontend/tests/ai-panel-parts-render.spec.ts:108-125`，另见 `:670` 注释），绕开真实布局，所以 288→304 全绿但实机失效。

## ④ 触发场景一句话 + 例子

**一句话**：用户在 AI 对话里展开一张**普通工具卡**看「输入」参数、展开**思考过程**、或点**回执卡的「查看详情」**时，只要那块内容超过限高（96/160px），就该在内容下方出现"展开控件"——第 17 项要把它统一成底部左对齐的灰色文字链「展开 ▾ / 收起 ▴」（不再是 EP 描边小钮「显示更多」）。

**具体例子**：用户问 AI「把 CURRENCY 和 RATE 两个配置导出来」→ AI 调 `download_export_files(defCodes: ["CURRENCY","RATE",...])` → 用户点开工具卡 → 看「输入」区那段 `defCodes` 长 JSON（超过 96px）；按 v7.8 第 17 项应出贴左文字链「展开 ▾」，现状是（或按组件现状干脆什么钮都没有）EP 风格小方钮「显示更多」；另如同一次会话里 AI 的思考链很长，展开「思考过程」时同理。demo 的对照样张（长消息版）见 `v7.8.html:630`（现状栏 `data-cl-more` 描边钮 vs 改后栏 `u17-more` 文字钮），场景 17 主样张见 `v7.8.html:1299-1390`（折叠态 `:1302-1322`、展开态 `:1345-1361`、判据/折叠类 `:1385-1386`）。

**关键文件行号**
- `frontend/src/components/AiPanel/CollapsibleText.vue:3-9`（口径）、`:25-34`（props/限高值域）、`:54-65`（单一 measure）、`:74-81`（收起类）、`:94-102`（钮，`:97` 当前样式）
- `frontend/src/components/AiPanel/AiPanel.vue:274`（回执详情 CollapsibleText）/ `:313`（思考）/ `:353`（工具 args）/ `:356-368`（输出已改纯限高内滚）/ v-show 父节点 `:266 / :309 / :346`
- 用户长消息折叠（另一套）：`AiPanel.vue:481-492, 975-986, 1042-1074`
- 测试注高法：`frontend/tests/ai-panel-parts-render.spec.ts:108-125, 670-702`
- demo：`<LOCAL_WORK>/r163-demo/v7.8.html:630`（aj17）、`:397`（aj11）、`:1299-1390`（场景 17 样张）
