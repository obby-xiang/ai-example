<!-- 历史中间稿证据条目（自动生成头部，非正文）。源 = Kimi Code assistant wire 文本；不含 think / 工具输出 / 原始用户消息。 -->

# MT-259 · 其它 · 三条落实证据（cbdb20ea / agent-960）

> **项目归属**：**项目硬锚点**（正文命中 ai-example 路径 / 项目类名 / 项目文档名 / 项目 UI 概念）。
> **归属依据**：正文命中：`scripts/ci`、`check-docs`、`docs/决策看板`、`docs/任务看板`。
> **稿态**：历史中间稿 —— 该 wire 第 `m168` 条 assistant 消息（m 按 wire 内 assistant 消息计，含工具调用轮）；本条是该 wire 第 `mt14` 条有正文的 assistant 消息（共 33 条有正文），末条有正文消息为 `m435`。**非现行基线、非验收结论、非当前缺陷清单**。
> **来源**：会话 `cbdb20ea` / agent `agent-960`（`main` = 主会话）；原字符 3,206（链内 6 段、合计 17,948）。
> **链号 / 类别**：`MT-259` / **其它**；判定：助手结论/验收记录（标题无主题词）；**不是用户批准**。
> **轮次**：位于该 wire 第 4 个用户轮之后；相邻用户轮未见决断类字样（仅记布尔，未复制用户原文）。
> **去重**：行指纹对「仓内 + 落盘语料」覆盖率 `9%`（<60% 视为仓内无等价件）；同 wire 后文覆盖率 `17%`。
> **与末条归档（138 件）的关系**：末条 `m435`（2,485 字符）未入末条归档。
> **脱敏**：路径 7 / 会话标识 0 / 身份 0 / 密钥 0 / 旧仓名与同源仓名 0 处。

---

### 〔段 1／6 · assistant 消息 m168 · 3,206 字符〕

r5 三条已全部实现、验证、入库推送。

## 三条落实证据

| 条 | 实现 | 机器证据（v5 断言） |
|---|---|---|
| **G1** A2/A3 补「取消」 | 两张卡按钮行改为「取消（次钮）｜ 提交（主钮）」，取消在最左；点击 → `decide(name,'cancelled',true)`：先 `resetForm()` 把单选/复选回未选、「其他」文本清空，再锁成已取消只读态；徽标「✗ 已取消」+ decidedAt + 只读标记 | `a2-cancel`/`a3-cancel` 存在、次钮样式（圆角 8px / 1px 边框 / 白底）、行内序列 `取消…提交`；A2 取消后 `data-status=cancelled`、5 个 radio 全未选、`[data-a2-other].value=''`、button=0、控件全 disabled；A3 取消后 4 个复选框全未选、计数回「已选 0 项」、其他清空、零按钮 |
| **G2** A5 三槽 | 行内顺序改为 `取消` ｜ `上一步` ｜ `下一步`/`提交`；`下一步`/`提交` 占同一槽位按步显示（另一侧 `hidden`），不可用即 disabled；**首步把「上一步」移出 DOM**，仅末步插入到主槽之前 | 首步可见序列 `取消\|下一步`、`[data-act="a5-back"]` 在 DOM 中不存在、主槽只显示下一步；勾选后下一步可用；末步序列 `取消\|上一步\|提交`、上一步可用、主槽换成提交且窗口未填时 disabled；回退首步后上一步再次移出 DOM |
| **G3** 只读控件原生 disabled | `lock()` 删除 `pointer-events-none` / `tabindex=-1` / 捕获拦截三件套，改为对全部 `input,textarea,select` 设 `disabled`；文本类控件补 `disabled:text-[#57606a] disabled:bg-transparent disabled:cursor-default`，自绘开关补 `disabled:opacity-[.55]`；开关容器由 `span` 改 `label`（去掉旋钮的 `pointer-events-none` 仍可点） | 每张已决卡：`ctl>0 且全部 disabled`、`focusable=0`、`button=0`、无 `pointer-events-none`；A6 文本控件 `disabled` 且 computed 颜色 `rgb(87,96,106)`；B1–B7 样张控件全 disabled；全页 `.pointer-events-none` 计数 **0**（复载后再查一次仍 0） |

## 验证

- **Playwright v5：PASS=281 FAIL=0**（`<LOCAL_WORK>\_cu-hitl-final\v5-test.mjs`）。F1~F9 / K1~K8 语义断言全部保留且未回归；新增 G1/G2/G3 断言如上，另含「A5 首步主槽只显示下一步（G2 例外）」专项。
- **类名白名单：192/192 全命中**（新增 3 条 `disabled:` 变体）。
- **CSS 二次重建**（G3 缺类，按上次流程）：Tailwind 3.4.19 CLI、`--minify`、content = r5 HTML + 历代 HTML、同一 config 与 base 层；选择器 **266 → 269**（新增恰 3 条：`disabled:bg-transparent` / `disabled:opacity-[.55]` / `disabled:text-[#57606a]`），**零丢失**；字节 18811 → 19028；备份 `hitl-interrupts.css.bak-r5`。
- `curl http://localhost:18330/hitl-interrupts.html | grep -c 'data-hitl-final="2026-10-17-r5"'` → **1**；CSS 200 / 19028 B。
- `node scripts/ci/check-docs.mjs` → **6/6 绿**。

## 截图（39 张，`<LOCAL_WORK>/_cu-hitl-final/v5-*.png` + `v5b-*.png`）

`v5-s0 · a1 / a1-approved / a1-rejected / a1-timeout · a2 / a2-filled / a2-before-cancel / a2-cancelled / a2-submitted · a3 / a3-before-cancel / a3-cancelled / a3-submitted · a4 / a4-submitted / a4-cancelled-empty · a5 / a5-step1 / a5-step2 / a5-submitted / a5-cancelled · v5b-a5-submitted-scroll · a6 / a6-filled / a6-submitted / a6-cancelled · b1 / b2 / b2-reproposal-decided / b3 / b4 / b5 / b6 / b7 · v5-compare-pending-vs-readonly（待决白卡 vs 已决灰卡同屏）· v5-overview-top / overview-full`

重点：`v5-a5-step1.png`（首步=取消｜下一步）、`v5-a5-step2.png`（末步=取消｜上一步｜提交）、`v5-compare-pending-vs-readonly.png`（A4 待决与 A5 已决同屏，disabled 观感对照）。

## 入库

| commit | 内容 |
|---|---|
| `1116ce6` | `hitl-interrupts.html`（r5，marker `2026-10-17-r5`）+ `hitl-interrupts.css`（二次重建）一笔 |
| `01210e4` | 裁决件 **§11 第 185 行起**（11.1 第 190 / 11.2 第 196 / 11.3 第 203）· `docs/决策看板.md` **第 19 行**（并在第 18 行 r4 行补注被 G2 修订）· `docs/任务看板.md` **第 14 行**补记「demo r5 三条修正待终裁（`1116ce6`）」 |

已 `push origin main`（`3c484d8..01210e4`）。

## 两处需报备的判断

1. **G2 与 F1 的冲突按 G2 处理**：F1 原是「不可用按钮常驻 + disabled」，但 G2 明确首步「上一步」不显示、且「下一步/提交」同槽切换。因此本次：首步「上一步」**不入 DOM**（严格照 G2 例外），主槽的 `下一步/提交` 用 `hidden` 切换（同一时刻只显示一个），其余不可用态仍 disabled 常驻。测试已为这两处写了专项断言，S0 K5 也补了例外说明。
2. **G1 的取消语义按 F9 执行**：A2/A3 取消后不保留任何勾选/输入（K1 的「原样保留」只适用于提交类收尾），与你给的「选项回初始未选态再锁定」一致；A2/A3 的场景脚注已同步补写这句。

---

### 〔段 2／6 · assistant 消息 m197 · 3,465 字符〕

r6 三条已落地、验证、入库推送。

## 三条落实证据

| 条 | 实现 | 机器证据（v6 断言） |
|---|---|---|
| **H1** 撤只读卡浅灰底 | `lock()` 删除 `bg-[#f6f7f9]` 与 `[data-opt-row]` 去边框/去白底两段；9 处静态样张 `[data-locked]` 的 `bg-[#f6f7f9]` 同时撤除；A5 只读滚动区同步。只读区分只剩 disabled 控件 + 卡头锁形「只读」chip + 徽标 | 每张已决卡：卡面 computed 底色 = `rgb(255,255,255)`（与待决一致）、`[data-locked]` 底色 = `rgba(0,0,0,0)`（无只读专用底色）；7 张样张同样两条断言 |
| **H2** 文本控件回归原生 disabled | `lock()` 不再改控件 class，只设 `disabled` + 追加 `disabled:text-[#57606a]`；删除 `border-0 border-b`、`bg-transparent`、`disabled:bg-transparent`、`disabled:cursor-default`；静态样张 3 个文本框、4 个选项行恢复「边框 + 白底」与待决一致 | 每张含文本控件的已决卡与样张：`borderTopWidth > 0`（实测 1px）且 `disabled=true`、控件仍可聚焦数为 0；A6 文本色 `rgb(87,96,106)` |
| **H3** 限高内滚全场景对齐 | 17 个内容容器统一 `max-h-[Npx] overflow-y-auto` + `data-scroll-region` + `data-limit="N"`；A1 参数区补「审计 / 前置」两行，使 160px 限高有可滚内容 | 12 个场景逐条断言：`computed max-height = 裁决值`、`overflow-y=auto`、`data-limit` 对齐；A1/A2/A3/A6/B5 断言 `scrollHeight > clientHeight`；A4/B 区短内容断言「未超高但限高就位」；A1/A2/A3/A6 另做 `scrollTop` 生效断言 + 内滚截图 |

## 「场景 → 限高值 → 出处」对齐表

| 场景 | 裁决限高 | 出处 |
|---|---|---|
| 审批卡参数区（A1 / B1 / B2 / B3 / B4 / B7） | **160px** | 门卡字段区 = 纯限高内滚 160px（v7 `.grows` 口径）：`frontend/src/components/AiPanel/AiPanel.vue:448-451`；`docs/evidence/施工报告-S6批1.md:60`、`:68` |
| 单选选项列表（A2）· 多选选项列表（A3 / B5） | **160px** | **无专条，参照门卡字段区 160px**（同族：卡内字段 / 候选区） |
| 输入卡字段区（A4 / B6 / B7 已取消卡） | **160px** | **无专条，参照门卡字段区 160px**（H3 同族） |
| GF 生成式表单字段区（A6） | **240px** | `AiPanel.vue:908`（限高族：工具输出 160 / 代码 pre 200 / 门卡字段 160 / GF 字段 240）；`FormRenderer.vue:21-22`（`max-h-[240px] overflow-y-auto`） |
| 多步组合流只读卡（A5） | **240px** | 本件 §10（r4 新设，同族最近 = GF 240） |

同族备查（本 demo 未用）：工具卡 input/output/args 160（`AiPanel.vue:379/392-396/274-277`、archive `r165-chat-ui-v7.12.html` aj02）、回执详情 200（`裁决-回执原卡只读态-2026-10-15.md:5`、archive `:63`）、代码 pre 200 / 错误卡 detail 200 / 长表格表体 320 / 用户长消息折叠 184（`AiPanel.vue:908/679-681/418/898`）。

## 验证

- **Playwright v6：PASS=343 FAIL=0**（沿用 281 条语义断言，未回归；F8 的「只读底≠白」反转为「= 待决底」，H2 的边框断言反向为「边框回来了」）。
- 类名白名单 **192/192**。
- CSS 第三次重建：Tailwind 3.4.19 CLI 同参数，content 追加 r4/r5 HTML；选择器 **269 → 270**（新增恰 `max-h-[160px]`，**零丢失**），字节 19028 → 19062；备份 `hitl-interrupts.css.bak-r6`。
- `curl` marker `2026-10-17-r6` grep=1；CSS 200 / 19062 B；check-docs **6/6**。

## 截图（41 张，`<LOCAL_WORK>/_cu-hitl-final/v6-*.png`）

`v6-s0 · a1 / a1-approved / a1-rejected / a1-timeout · a2 / a2-filled / a2-before-cancel / a2-cancelled / a2-submitted · a3 / a3-before-cancel / a3-cancelled / a3-submitted · a4 / a4-submitted / a4-cancelled-empty · a5 / a5-step1 / a5-step2 / a5-submitted / a5-cancelled · a6 / a6-filled / a6-submitted / a6-cancelled · b1–b7（含 b2-reproposal-decided）· v6-compare-pending-vs-readonly（待决 vs 已决同屏：底色一致、只有原生禁用观感）· v6-scroll-a1 / a2 / a3 / a6（内滚证据，各滚 80px）· overview-top / overview-full`

## 入库

| commit | 内容 |
|---|---|
| `8466856` | `hitl-interrupts.html`（r6）+ `hitl-interrupts.css`（三次重建）一笔 |
| `29ef5dd` | 裁决件 **§12 第 210 行起**（12.1 第 215 / 12.2 第 221 / 12.3 第 235）· `docs/决策看板.md` **第 20 行** · `docs/任务看板.md` **第 14 行** |

已 `push origin main`（`01210e4..29ef5dd`）。

## 自证

读自身 `wire.jsonl` 的 `profile.bind`：**`modelAlias = DeepSeek/deepseek-flash`**（`profileName=coder`；agent-960 / session_cbdb20ea）。

## 两处需上层拍板的落地影响

1. **「其他」行现在落在 160px 滚动区内**：DOM 上仍常驻渲染（F2 满足），但待决/已决态都要滚动才看得到它（A2 有 5 行、160px 只容纳约 2.4 行）。若希望「其他」始终在视野内，可把该行钉在滚动区外（仍是列表一行），需你确认是否改。
2. **输入卡（A4 / B6）单字段不触发内滚**：字段区内容约 84px，160px 限高就位但 `scrollHeight == clientHeight`，已按「限高就位、内容未超高」断言并在 §12.3 记录；若要看到滚动，只能加字段（会破坏 H3 单字段设计）。

---

### 〔段 3／6 · assistant 消息 m223 · 3,575 字符〕

r7 两条已落地、验证、入库推送。

## J1 落实证据（消灭双滚动条）

- **根因定位**：多步只读卡是「外层内滚区 + 步骤内容克隆」结构，而步骤 1 的选项列表在待决态本身就是一层内滚区——克隆后两层相邻并存。实测 A5 已决态同时存在两个溢出容器：外层 `482/240`、内层（被克隆的选项列表）`185/160`。
- **修法**：新增 `stripScroll()`——把步骤内容克隆进只读卡前，剥离内层滚动区（去掉 `data-scroll-region` / `data-limit` 与 `max-h-*` / `overflow-y-auto`），只保留外层那一层。其余场景本就单层（卡片用 `overflow-hidden` 裁切，`overflow:hidden` 不产生滚动条）。
- **断言**：对每张卡（A1–A6 + B1–B7 样张 + B2 重新提案）统计子树内「`overflow-y:auto|scroll` 且 `scrollHeight>clientHeight`」的容器：**数量 ≤1 且两两不嵌套**（逐卡 2 条），外加 A5 已决态专项三条（溢出容器=1、无嵌套、滚动区节点数=1）。修后实测 A5 仅 `507/320` 一层。

## J2 落实证据（限高上调）

| 场景 | 旧 | 新 | 实测（内容/客户端） | 断言 |
|---|---|---|---|---|
| 选项列表（A2/A3/B5） | 160 | **280** | A2 483/280 · A3 291/280 · B5 291/280 | 超高 → 可滚 |
| 审批卡参数区（A1/B1–B4/B7） | 160 | **280** | A1 195/195 · B1–B4 107 · B7 107/80/72 | 未超高（限高就位） |
| 输入卡字段（A4/B6） | 160 | **280** | 80/80 | 未超高（限高就位） |
| GF 表单（A6） | 240 | **320** | 591/318 | 超高 → 可滚 |
| 多步只读（A5） | 240 | **320** | 507/320 | 超高 → 可滚（单层） |

A2 补两条真实业务选项（「只发布币种域」「按区域灰度发布」，共 6 选项 + 其他 = 7 行）保 280 可滚演示；A1 的「审计/前置」两行按指示保留（内容合理，280 下不再超高，断言按实际）。S0 K11 已同步为 280/320 + 单层滚动。

## 项目级限高审计（产出表，`frontend/src` 零改动）

| 位置 | 现值 | 建议 | 依据 |
|---|---|---|---|
| `AiPanel.vue:277` 思考过程展开体 | 160 | **280** | 字段/文本族 |
| `AiPanel.vue:313` 回执/门卡只读 label-value 表 | 160 | **280** | 字段区族 |
| `AiPanel.vue:379` 工具卡 args rows | 160 | **280** | 字段区族 |
| `AiPanel.vue:396` 工具卡 output | 160 | **280** | 代码/回执详情族 |
| `AiPanel.vue:412` 确认门卡「原因」 | 240 | **280** | 文本族向 280 收敛 |
| `AiPanel.vue:418` 长表格表体 | 320 | **400** | 长表格族 |
| `AiPanel.vue:451` 门卡字段区 | 160 | **280** | 字段区族（demo 审批卡同源） |
| `AiPanel.vue:681` 错误卡 detail | 200 | **280** | 文本块族 |
| `AiPanel.vue:1078` 用户长消息折叠 | 184 | **280** | 长消息族 |
| `AiPanel.vue:1086` 代码块 pre | 200 | **280** | 代码族 |
| `AiPanel.vue:1600` mermaid 预览 | 260 | **320** | 表单/组合族（缩放拖拽，高一档） |
| `AiPanel.vue:1609` mermaid 源码盒 | 200 | **280** | 代码族 |
| `FormRenderer.vue:21` GF 字段区 | 240 | **320** | 表单/组合族 |
| `markdown.ts:188` md 代码 pre | 200 | **280** | 代码族 |
| `markdown.ts:440` 表格表体 | 320 | **400** | 长表格族 |

共 15 处（`AiPanel.vue` 12 / `FormRenderer.vue` 1 / `markdown.ts` 2），已写入裁决件 §13.3，标注「本批不动项目代码，待裁后随 2A / resultView 批施工」。

## 验证

- **Playwright v7：PASS=374 FAIL=0**（v6 的 343 条语义断言全部保留未回归；新增 J1 审计与 J2 尺度断言）。
- 类名白名单 **193/193**；CSS 第四次重建：270 → **272** 选择器（新增恰 `max-h-[280px]` / `max-h-[320px]`，**零丢失**），19062 → 19130 B；备份 `hitl-interrupts.css.bak-r7`。
- `curl` marker `2026-10-17-r7` grep=1；CSS 200 / 19130 B；check-docs **6/6**。

## 截图（41 张，`<LOCAL_WORK>/_cu-hitl-final/v7-*.png`）

`v7-s0 · a1 / a1-approved / a1-rejected / a1-timeout · a2 / a2-filled / a2-before-cancel / a2-cancelled / a2-submitted · a3 / a3-before-cancel / a3-cancelled / a3-submitted · a4 / a4-submitted / a4-cancelled-empty · a5 / a5-step1 / a5-step2 / a5-submitted / a5-cancelled · v7b-a5-submitted-scroll（单层滚动证据）· a6 / a6-filled / a6-submitted / a6-cancelled · b1–b7（含 b2-reproposal-decided）· v7-scroll-a2 / a3 / a6（280/280/320 内滚证据）· v7-compare-pending-vs-readonly · overview-top / overview-full`

## 入库

| commit | 内容 |
|---|---|
| `5fee3b8` | `hitl-interrupts.html`（r7）+ `hitl-interrupts.css`（四次重建）一笔 |
| `0c3f7ee` | 裁决件 **§13 第 246 行起**（13.1 第 251 / 13.2 第 257 / 13.3 第 270 / 13.4 第 292）· `docs/决策看板.md` **第 21 行** · `docs/任务看板.md` **第 14 行** |

已 `push origin main`（`29ef5dd..0c3f7ee`）。

## 自证

读自身 `wire.jsonl` 的 `profile.bind`：**`modelAlias = DeepSeek/deepseek-flash`**（`profileName=coder`；agent-960 / session_cbdb20ea）。

## 一点提请知悉

A2 的选项列表在 280px 下容纳约 4 行（共 7 行），「其他」行仍在滚动区内——与 r6 报告里的同一处取舍（F2 要求输入框常驻渲染，H3/J2 要求列表限高内滚）。DOM 上它始终存在，需滚动才可见；若要「其他」始终在视野内，可把该行钉到滚动区外，等你一句话即可改。

---

### 〔段 4／6 · assistant 消息 m314 · 2,844 字符〕

r9 三项已落地、验证、入库推送。

## N1 已决默认折叠（成功终态）

- **折叠形态**：卡头两行——首行 `徽标 + 状态徽标 + 风险 + 决策时间 + 只读`，第二行整宽 `人话摘要 + chevron`（用 `order-last` 让摘要落到第二行整宽，避免被徽标挤成一列一字）。摘要示例：`已批准 · 删除生产数据库 orders_prod`、`已提交 · 发布范围=其他（自定义处理说明）`、`已提交 · 已勾选 2 项前置确认`、`已提交 · 变更单号 CHG-2026-0417`、`已提交 · 发布窗口 2026-10-18 02:00–04:00`、`已提交 · 发布配置：spring 配置发布（华东）`。
- **卡头是控件**：`role="button"` + `tabindex="0"` + `aria-expanded`（折叠 false / 展开 true），点击与 **Enter / Space** 都能切换（两种键盘都断言过）。
- **回合计时**：决策瞬间 `data-collapsed="0"`（断言「不折叠」），900ms 后冒出一条「AI 继续执行：…」后续消息，卡随之收成卡头行（断言 followup 存在 + 折叠 + 摘要）。
- **四类例外默认展开**：已拒绝 / 已超时 / 已批准·执行失败 / 已取消——A1 拒绝、A1 超时、A4 取消、A5 首步取消、A6 取消均断言 `data-collapse` 为空。
- **展开后语义不变**：展开断言「零按钮 + 控件全 disabled + 内容可见」；单层滚动（J1）与限高（J2/H3）断言都在展开态测量，全部通过。
- 静态样张：B1 / B5 / B7 首卡默认折叠（`data-collapsed="1"`、`aria-expanded="false"`、摘要可见、卡体隐藏），点击展开；B2/B3/B4/B6 与 B7 其余卡默认展开。

## N2 撤 B2 重新提案

- `b2-reproposal` 卡片与 `b2-approve/b2-reject` 处理逻辑整体删除；B2 只剩一张已拒绝只读样张（展开）。
- 断言：B2 无 `[data-card="b2-reproposal"]`、无 `data-status="pending"`、`cards=0 / samples=1`；全页 `input/textarea` 无「拒」占位、无「拒绝原因/拒因」常驻行；B2 底部行改为「决策人 demo-user · 拒绝未改动任何实例 · 已写入审计日志」。
- S0 K7 与 B2 场景说明删掉「按反馈重新提案」的演示表述（改为「修正走会话属产品口径，本 demo 不演该闭环」），裁决文档里的产品口径未动。

## N3 口径卡

S0 由 K1~K11 扩为 **K1~K12**（新增 K12 已决默认折叠：成功态回合结束后收卡头行、四类例外展开、卡头可聚焦 + Enter/Space）；页首副标题改为「已决默认折叠（用户 2026-10-17 裁决 r9）」。

## 验证

- **Playwright v9：PASS=425 FAIL=0**（v8 的 388 条全部保留；受影响处改为展开态测量、可聚焦检查排除 `[data-card-head]`）。
- 类名白名单 **195/195**；CSS 第六次重建：274 → **276**（新增 `rotate-180`、`order-last`，**零丢失**），19248 → 19504 B；备份 `hitl-interrupts.css.bak-r9`。
- `curl` marker `2026-10-17-r9` grep=1；CSS 200 / 19504 B；check-docs **6/6**。

## 截图（50 张，`<LOCAL_WORK>/_cu-hitl-final/v9-*.png`）

折叠/展开对照：`v9-a1-collapsed` / `v9-a1-approved`、`v9-a2-collapsed` / `v9-a2-submitted`、`v9-a3-collapsed` / `v9-a3-submitted`、`v9-a4-collapsed` / `v9-a4-submitted`、`v9-a5-collapsed` / `v9-a5-submitted`、`v9-a6-collapsed` / `v9-a6-submitted`、`v9-b1-collapsed` / `v9-b1-expanded`；例外展开态：`v9-a1-rejected`、`v9-a1-timeout`、`v9-a4-cancelled-empty`、`v9-a5-cancelled`、`v9-a6-cancelled`、`v9-b2-rejected`、`v9-b3`、`v9-b4`、`v9-b6`；时序三张：`v9-a1-collapsed`（含 AI 后续条）、`v9-a5-collapsed`、`v9-b2-rejected`；其余场景/内滚/对照图沿用 v9 前缀全套。

## 入库

| commit | 内容 |
|---|---|
| `884f227` | `hitl-interrupts.html`（r9）+ `hitl-interrupts.css`（六次重建）一笔 |
| `1269cfb` | 裁决件 **§15 第 341 行起**（15.1 第 346 / 15.2 第 356 / 15.3 第 362 / 15.4 第 366）· `docs/决策看板.md` 新增 r9 行 · `docs/任务看板.md` 第 14 行补记 r9 |

已 `push origin main`（`240421e..1269cfb`）。

## 自证

读自身 `wire.jsonl` 的 `profile.bind`：**`modelAlias = DeepSeek/deepseek-flash`**（`profileName=coder`；agent-960 / session_cbdb20ea）。

## 两处实现取舍（供你判断）

1. **折叠卡头布局改成两行**：单行放不下「徽标 + 摘要 + 时间 + chevron」（440px 卡宽），最终用「首行徽标/时间、次行整宽摘要 + chevron」。若你要严格单行，需要隐藏类型徽标或风险徽标（我可再调）。
2. **折叠触发是固定 900ms 定时 + 一条 demo 后续消息**：真实产品应由下一回合 SSE 事件驱动；这里按「demo 模拟」的要求做，时序断言已覆盖「决策瞬间不收 → 后续消息出现后收」。

---

### 〔段 5／6 · assistant 消息 m344 · 2,227 字符〕

r10 两条已落地、验证、入库推送。

## M1 卡头减肥（全卡生效）

- **撤除**：`风险 HIGH / MEDIUM` 徽标（全页 0）、决策时间（`data-decided-time` 节点与静态时间戳全删，decidedAt 仅后端记录）、「只读」chip 与锁形图标块（`data-readonly-mark` 全页 0）。
- **保留**：类型徽标（`H1 · 单步审批` 等）+ 状态徽标（`✓ 已批准 / ✓ 已提交 / ✗ 已拒绝 …`）。
- **折叠卡头单行**：`状态徽标 + 人话摘要(nowrap+ellipsis) + chevron` 同一行，实测卡头高 ≤40px、三者 `getBoundingClientRect().top` 完全一致（B1 / B5 / B7 首卡逐张断言）。
- 例外四类仍默认展开；`decide()` 里写时间/只读标记的两行代码同步删除，`lock()`、折叠、限高、单层滚动全部不受影响。
- 场景标题行未动；脚注里「卡头 = 徽标 + 决策时间 + 只读标记」三处同步改写。

## M2 T1 工具卡对照场景

- 新增 `data-scene="t1"`（A6 与 B1 之间）。形态取自项目实现而非自拟：
  - 卡头：状态图标 15×15 + 工具名 `publish_config`（mono 11.4px / `max-w-[128px]` 省略号）+ kind 胶囊「后端工具」+ 状态胶囊「已成功」+ chevron `›`；
  - `role="button"` / `tabindex="0"` / `aria-expanded`，点击与 **Enter** 都能切换（chevron 展开后 `rotate-90`）；
  - 展开体：参数字段表（键列 72px、7 行）+ 输出等宽区（20 行日志），各 `min(280px,35vh)` 双约束内滚、无展开钮；两区**并列不嵌套**（J1 对 T1 放宽为 ≤2 且强制「无嵌套」，其余卡仍 ≤1）。
- 默认折叠；S0 新增 K13（卡头减肥）/ K14（工具卡对照），口径卡范围 K1~K14。

## 验证

- **Playwright v10：PASS=414 FAIL=0**（v9 的 425 条中，已撤的时间/只读断言同步删除；新增 M1 9 条 + M2 10 条，其余零回归）。
- 类名白名单 **213/213**；CSS 第七次重建：276 → **289**（新增恰 13 条工具卡类，**零丢失**），19504 → 19988 B；备份 `hitl-interrupts.css.bak-r10`。
- `curl` marker `2026-10-17-r10` grep=1；CSS 200 / 19988 B；check-docs **6/6**。

## 截图（54 张，`<LOCAL_WORK>/_cu-hitl-final/v10-*.png`）

- **对比图（重点交付）**：`v10-compare-tool-vs-hitl.png` —— 同屏自上而下：T1 工具卡（展开，参数表 + 输出区）／B1 已批准（折叠单行卡头）／B2 已拒绝（展开）／B3 已超时（展开）。
- **折叠单行卡头特写**：`v10-head-collapsed-closeup.png`（B1 卡头 880×76 原尺寸：`[H1 · 单步审批][✓ 已批准] 已批准 · 重启支付网关 gateway-prod-03 ›`）。
- **T1 折叠/展开**：`v10-t1-collapsed.png` / `v10-t1-expanded.png` / `v10-t1.png`。
- 其余沿用 v10 前缀全套：各活卡决策前/后、折叠/展开、例外四态、样张、内滚证据、`v10-compare-pending-vs-readonly`、overview。

## 入库

| commit | 内容 |
|---|---|
| `0ac5e4d` | `hitl-interrupts.html`（r10）+ `hitl-interrupts.css`（七次重建）一笔 |
| `ccccef1` | 裁决件 **§16 第 374 行起**（16.1 第 379 / 16.2 第 387 / 16.3 第 396）· `docs/决策看板.md` 新增 r10 行 · `docs/任务看板.md` 第 14 行补记 r10 |

已 `push origin main`（`1269cfb..ccccef1`）。

## 自证

读自身 `wire.jsonl` 的 `profile.bind`：**`modelAlias = DeepSeek/deepseek-flash`**（`profileName=coder`；agent-960 / session_cbdb20ea）。

## 一处判断说明

T1 的两个限高区（参数 table / 输出 pre）在项目实现里就是**并列两块**各自限高，不是嵌套；因此我把 J1 的「每卡溢出容器 ≤1」对 T1 放宽为 ≤2，并单独断言「两部分互不包含」。其余所有卡仍按 ≤1 且零嵌套校验，未放松。

---

### 〔段 6／6 · assistant 消息 m392 · 2,631 字符〕

r12 两条已落地、验证、入库推送。

## P1 · A5 卡头日期时间残留：逐条清单（文件行号 + 处置）

我按 `20\d\d-` / `\d{2}:\d{2}` / `data-decided` / 静态时间戳四条线各扫一遍，逐命中判定归属：

| # | 位置（r11 行号） | 内容 | 归属 | 处置 |
|---|---|---|---|---|
| 1 | `a5Finish()` r11 `:1119` `var t = stamp()` + `:1135` `$$('[data-a5-time]')…` | **运行时**往 A5 只读卡头写 `2026-10-17 19:39` | **卡头（真凶）** | 删写入逻辑；`stamp()` 函数整条退役 |
| 2 | A5 只读卡头 `data-a5-time` span（r11 `:447` 同行） | `--:--` 占位，被 #1 覆盖 | **卡头** | 删节点 |
| 3 | `a5Finish()` 摘要 `'已提交 · 发布窗口 ' + a5.win` | 折叠卡头摘要含 `2026-10-18 02:00–04:00` | **卡头** | 改「已提交 · 发布窗口已确认」（窗口值仍在卡体） |
| 4 | A5 窗口输入 placeholder | `如 2026-10-18 02:00–04:00` | 卡体 input 属性 | 改「如：次日凌晨低峰时段（2 小时）」 |
| 5 | followup 条 `'继续执行：' + summary` | 派生自 #3 | 卡外 | 随 #3 自动清零 |
| 6 | r10 已清：`data-decided-time`×5 + 样张静态时间戳×9 | — | 卡头 | 已删（复核 0 命中） |
| 7 | `CHG-2026-0417`（A1 留痕行）/ `CHG-2026-0416`（B1 卡底）/ T1 args `change_id` | 变更单号 | 卡体业务编号 | **保留**并列入 5 条白名单（非日期时间），测试里逐条列明 |
| 8 | 页首/S0 的 `2026-10-17` | 页面脚手架 | 卡外 | 保留 |

**根因**：r10 清时间时只匹配了 `data-decided-time`，A5 只读卡头用的是 `data-a5-time`（另一套命名），漏网。修复后实测 A5 折叠卡头文本 = `发布前参数确认 / ✓ 已提交 / 已提交 · 发布窗口已确认`，零日期时间；followup 同步为零。

## P2 · 卡头对齐 T1 工具卡

- 全部 **16 个卡头**撤掉 `bg-[#f6f7f9]` 与 `border-b border-[#eef0f3]`：卡头 = 无底色、无分隔线的裸文字行，与 T1 工具卡同观感；卡头与卡体之间不留装饰性分隔。
- 层级靠「状态徽标 / 摘要 + 卡体内容」承担，**未新增任何装饰**（T1 为唯一参照系）。
- 实测逐卡 computed：`background-color = rgba(0,0,0,0)`、`border-bottom-width = 0px`（A1~A6 / T1 / B1~B7 全通过）。

## 断言

**PASS=514 FAIL=0**（v11 的 472 条零回归 + 新增 42 条）：
- 每张卡（14 张 + 工具卡）逐条 7 项：卡内无注解词、卡头无类型规格标注、**卡内（含卡头）无 `\d{4}-\d{2}-\d{2}` / `\d{2}:\d{2}`**、**卡头无 `20\d\d-`**（业务编号白名单 5 条显式豁免）、**卡头背景透明**、**卡头无底边框**。
- A5 摘要断言改为「不带日期时间」。

## 截图（54 张，`<LOCAL_WORK>/_cu-hitl-final/v12-*.png`）

- **A5 步进卡特写（修正后卡头）**：`v12-a5-step1.png`、`v12-a5-step2.png`、`v12-a5-collapsed.png`（「已提交 · 发布窗口已确认」单行）。
- **卡头同屏对照**：`v12-compare-tool-vs-hitl.png` —— T1 工具卡（展开）与 B1 已批准（折叠单行）上下相邻，两者卡头同为无底色裸文字行；另有 `v12-head-collapsed-closeup.png`（B1 卡头 880×76 原尺寸特写）。
- overview：`v12-overview-top.png` / `v12-overview-full.png`；其余场景全套同前缀。

## 入库

| commit | 内容 |
|---|---|
| `36733cb` | `hitl-interrupts.html`（r12）一笔（无新增 CSS 类，未重编；`211/211` 类仍全命中） |
| `cd9f763` | 裁决件 **§18 第 438 行起**（18.1 第 443 / 18.2 第 460 / 18.3 第 466）· `docs/决策看板.md` 新增 r12 行 · `docs/任务看板.md` 第 14 行补记 r12 |

已 `push origin main`（`664787a..cd9f763`）；`curl` marker `2026-10-17-r12` grep=1；check-docs **6/6**。

## 自证

读自身 `wire.jsonl` 的 `profile.bind`：**`modelAlias = DeepSeek/deepseek-flash`**（`profileName=coder`；agent-960 / session_cbdb20ea）。

## 一点补充说明

P1 暴露的是**命名不一致导致的漏扫**（`data-decided-time` vs `data-a5-time`）。本次已把「运行时写时间」的代码路径整条删除（`stamp()` 退役），后续任何卡都不可能再写入时间戳；测试也从「按节点名抽查」改成「逐卡全文正则扫描 + 卡头二级扫描」，避免同类漏网。
