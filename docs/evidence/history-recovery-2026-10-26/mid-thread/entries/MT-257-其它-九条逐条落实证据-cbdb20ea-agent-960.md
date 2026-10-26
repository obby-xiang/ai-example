<!-- 历史中间稿证据条目（自动生成头部，非正文）。源 = Kimi Code assistant wire 文本；不含 think / 工具输出 / 原始用户消息。 -->

# MT-257 · 其它 · 九条逐条落实证据（cbdb20ea / agent-960）

> **项目归属**：**项目硬锚点**（正文命中 ai-example 路径 / 项目类名 / 项目文档名 / 项目 UI 概念）。
> **归属依据**：正文命中：`scripts/ci`、`check-docs`、`docs/demo`、`chat-ux`。
> **稿态**：历史中间稿 —— 该 wire 第 `m101` 条 assistant 消息（m 按 wire 内 assistant 消息计，含工具调用轮）；本条是该 wire 第 `mt10` 条有正文的 assistant 消息（共 33 条有正文），末条有正文消息为 `m435`。**非现行基线、非验收结论、非当前缺陷清单**。
> **来源**：会话 `cbdb20ea` / agent `agent-960`（`main` = 主会话）；原字符 3,892（链内 1 段、合计 3,892）。
> **链号 / 类别**：`MT-257` / **其它**；判定：助手结论/验收记录（标题无主题词）；**不是用户批准**。
> **轮次**：位于该 wire 第 2 个用户轮之后；相邻用户轮含决断类字样（仅记布尔，未复制用户原文）。
> **去重**：行指纹对「仓内 + 落盘语料」覆盖率 `7%`（<60% 视为仓内无等价件）；同 wire 后文覆盖率 `14%`。
> **与末条归档（138 件）的关系**：末条 `m435`（2,485 字符）未入末条归档。
> **脱敏**：路径 2 / 会话标识 0 / 身份 0 / 密钥 0 / 旧仓名与同源仓名 0 处。

---

r4 九条全部落地、验证、入库并推送（`origin/main` 已同步）。

## 九条逐条落实证据

| 条 | 实现 | 机器证据（v4 断言） |
|---|---|---|
| **F1** 待决按钮常驻 + disabled | 所有待决卡的有效性按钮改为「常驻 + `disabled` 切换」：A2/A3/A4/A6 提交、A5 四个步进钮；A1 两钮本就恒可用 | `a2-submit / a3-submit / a4-submit / a5-back / a5-next / a5-submit / a6-submit` 初始全部 present+visible+disabled（21 条断言）；对 A4 禁用钮派发 click 后仍 `data-status=editing`；A5 首步 back/next/submit 禁用、取消可用，末步 next 禁用、back 与 submit 随填写切换 |
| **F2** 「其他」+ 输入常驻 | 全页 0 处「其它」；A2/A3 的「其他」文字输入框去掉隐藏逻辑，初始即显示；有效性改为 `choice && (choice!=='other' \|\| 文本非空)` | 源码与 DOM 双查 0 命中；A2/A3 输入框初始 `isVisible()`；A2 选其他未填 → 提交 disabled、填字 → 可用；A3 勾其他未填 → disabled、填字 → 可用 |
| **F3** 已决卡底部内边距 | 锁定克隆尾部统一追加 11px spacer（`data-lock-spacer`），静态样张统一 `pb-[11px]` | 9 张已决卡逐张断言「最深叶子元素到卡底 ≥8px」，实测 11–12px（A1 参数区不再贴底、A2 底部留白恢复） |
| **F4** 取消改按钮 | A4/A5/A6 取消由文字链改为次钮（`rounded-[8px] px-[13px] py-[5px]` + 边框 + 白底） | 三个取消钮 computed `borderTopWidth=1px`、`borderRadius=8px`、`bg=rgb(255,255,255)` |
| **F5** 按钮顺序 | 多步行固定为 上一步 · 下一步 · 取消 · 提交，`flex-1` 占位右排 | 行内按钮文本序列断言 `上一步\|下一步\|取消\|提交`；全页每个按钮行末钮右缘与行右缘 ≤24px |
| **F6** 删除「不批准」 | 超时徽标只留「! 已超时」；倒计时/脚注/场景说明/样张正文全改写为「无应答即自动收尾，本次操作不会执行」「超时未应答 · 本次操作未执行」 | 全页 innerText 与源码「不批准」=0；A1 超时徽标严格等于 `! 已超时`；决策后再查一次仍为 0 |
| **F7** 多步已决单卡 | `[data-a5-ro]` 只留一张卡：一个徽标 + 一个 decidedAt + 一个只读区；两步内容之间插入细分隔线 `border-t`；容器挂 `data-scroll-region` + `max-h-[240px] overflow-y-auto` | `[data-a5-card]` 计数=1、`[data-a5-live]` 已移除、两个步骤槽 + 分割线存在、容器类名含两条限高类；勾选态与窗口值保留 |
| **F8** 只读可区分 | 锁定即给外壳加浅灰底 `bg-[#f6f7f9]`、`[data-opt-row]` 去边框去白底、文本/数字/日期控件 `border-0 border-b` + 透明底；卡头新增锁形内联 SVG + 「只读」chip（`[data-readonly-mark]`） | 每张已决卡与每张样张：只读底 computed ≠ 白、`[data-readonly-mark]` 可见、A6 输入框 `borderTopWidth=0 / bg=transparent` |
| **F9** 未提交不记录 | 新增 `resetForm()`：取消/超时收尾前清空所有 input/checkbox/radio、把开关复位并回退滑块、把摘要与计数复位；B6/B7 静态样张同步置空并改为「未提交，填写内容不作记录」 | A4 取消值=''、A5 首步/末步取消后勾选全 false + 窗口='' + 摘要='—'、A6 取消后六型全回初始（含开关 knob 无 `translate-x-[16px]`）、B6=''、B7 已取消卡='' |

## 验证

- `node <LOCAL_WORK>/_cu-hitl-final/v4-test.mjs` → **PASS=275 FAIL=0**（并发前 261/14 → 修测量口径与一处漏写 `pointer-events-none` 后全绿）
- 类名白名单：**191 个 token，189 命中 `hitl-interrupts.css`**；缺 2 条且**恰为 F7 限高所需的 `max-h-[240px]` / `overflow-y-auto`**（见下「需上层决定」）
- 全页：`不批准`=0、`其它`=0、emoji 仅 ✓/✗、无 `style` 属性/标签、仅 1 条 CSS 外链
- `node scripts/ci/check-docs.mjs` → **6/6 绿**
- `curl http://localhost:18330/hitl-interrupts.html | grep -c 'data-hitl-final="2026-10-17-r4"'` → **1**

## 截图（33 张，`<LOCAL_WORK>/_cu-hitl-final/v4-*.png`）

`v4-s0 · v4-a1 / a1-approved / a1-rejected / a1-timeout · v4-a2 / a2-other-empty-disabled / a2-submitted · v4-a3 / a3-submitted · v4-a4 / a4-submitted / a4-cancelled-empty · v4-a5 / a5-step1 / a5-step2 / a5-submitted / a5-cancelled / a5-cancelled-step2 · v4-a6 / a6-filled / a6-submitted / a6-cancelled · v4-b1 / b2 / b2-reproposal-decided / b3 / b4 / b5 / b6 / b7 · v4-overview-top / overview-full`

重点对照：`v4-a5-step1.png`（禁用钮常驻+顺序）、`v4-a5-submitted.png`（单卡+分割线+浅灰只读底）、`v4-a2.png`（其他输入常驻+提交禁用）、`v4-a6-cancelled.png`（F9 全空态）、`v4-a1-approved.png`（底部留白+锁标记）。

## 入库

| commit | 内容 |
|---|---|
| `d7653fe` | `docs/demo/chat-ux/hitl-interrupts.html` r4（1187 行，marker `2026-10-17-r4`） |
| `665d0d4` | 裁决件 **§10 第 150 行起**（10.1 第 155 / 10.2 第 167 / 10.3 第 173）· `docs/决策看板.md` **第 18 行**（并在第 17 行 K1 结论格补注「两项已由 F1/F9 修订」）· `docs/任务看板.md` **第 14 行**补记「demo r4 九条修正待终裁（commit `d7653fe`）」 |

已 `push origin main`（`3813ee0..665d0d4`）。

## 自证

读自身 `wire.jsonl` 的 `profile.bind` 单行：**`modelAlias = DeepSeek/deepseek-flash`**（`profileName=coder`；agent-960 / session_cbdb20ea）。

## 需上层决定的一处（与指令冲突，未擅自处置）

**F7 的「限高内滚」目前不生效。** `max-h-[240px]` 与 `overflow-y-auto` 这两条工具类**不在 `hitl-interrupts.css` 预编译产物里**（该产物是按 r3 HTML 的 class 集合生成的，天然没有这两条）。我按 F7 写出了容器（`data-scroll-region`）与两条类名，但**没有改 CSS、没有加行内样式**——因为指令写明「禁改 css 文件、禁手写 style」。结果：多步只读卡是**单卡 + 分割线**（结构完全达标），但**不会被裁到 240px 内滚**，内容全高展示（实测约 556px）。若要真正内滚，需要对该 CSS 做一次 tailwind 重新构建（构建动作而非手写 CSS），请放行后我补一轮。裁决件 §10.3 已把这条差异写清。
