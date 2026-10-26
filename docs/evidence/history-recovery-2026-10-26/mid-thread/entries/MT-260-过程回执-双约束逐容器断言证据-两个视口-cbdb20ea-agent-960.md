<!-- 历史中间稿证据条目（自动生成头部，非正文）。源 = Kimi Code assistant wire 文本；不含 think / 工具输出 / 原始用户消息。 -->

# MT-260 · 过程回执 · 双约束逐容器断言证据（两个视口）（cbdb20ea / agent-960）

> **项目归属**：**项目硬锚点**（正文命中 ai-example 路径 / 项目类名 / 项目文档名 / 项目 UI 概念）。
> **归属依据**：正文命中：`docs/决策看板`、`docs/任务看板`、`check-docs`。
> **稿态**：历史中间稿 —— 该 wire 第 `m257` 条 assistant 消息（m 按 wire 内 assistant 消息计，含工具调用轮）；本条是该 wire 第 `mt21` 条有正文的 assistant 消息（共 33 条有正文），末条有正文消息为 `m435`。**非现行基线、非验收结论、非当前缺陷清单**。
> **来源**：会话 `cbdb20ea` / agent `agent-960`（`main` = 主会话）；原字符 2,358（链内 1 段、合计 2,358）。
> **链号 / 类别**：`MT-260` / **过程回执**；判定：过程回执（自证/元数据/交付回报）；**不是用户批准**。
> **轮次**：位于该 wire 第 7 个用户轮之后；相邻用户轮含决断类字样（仅记布尔，未复制用户原文）。
> **去重**：行指纹对「仓内 + 落盘语料」覆盖率 `8%`（<60% 视为仓内无等价件）；同 wire 后文覆盖率 `17%`。
> **与末条归档（138 件）的关系**：末条 `m435`（2,485 字符）未入末条归档。
> **脱敏**：路径 1 / 会话标识 0 / 身份 0 / 密钥 0 / 旧仓名与同源仓名 0 处。

---

r8 已完成实施、验证、入库和推送。

## 双约束逐容器断言证据（两个视口）

17 个限高容器统一改为 `max-h-[min(Npx,Nvh)]`，`data-limit` 同步为表达式字符串：

| 族 | 定稿写法 | 1440×900 实测（断言） | 1200×700 实测（断言） |
|---|---|---|---|
| 字段类：A1 参数区、A2/A3/B5 选项列表、A4/B6 字段区、B1–B4/B7 参数与字段区 | `min(280px,35vh)` | **280px**（35vh=315 > 280，生效 280） | **245px**（35vh=245 < 280，收紧生效） |
| 表单与组合类：A6 GF 字段区、A5 多步只读卡 | `min(320px,45vh)` | **320px**（45vh=405 > 320） | **315px**（45vh=315 < 320；A5 已决卡实测 315px 且仍内滚 507/315） |

逐场景断言覆盖 12 个场景 + A5（后者在矮视口单独走一遍提交路径），共 14 条收紧断言；1440×900 下另有 12 条「生效值 = 像素上限」断言。J1 单层滚动审计（每张卡溢出容器 ≤1、无嵌套）与全部 F、G、H 族语义断言均未出现回归。

## CSS diff

- 第五次重建（Tailwind 3.4.19 CLI，同参数，content 追加 r7 HTML）：选择器 **272 → 274**，新增恰两条 —— `.max-h-[min(280px,35vh)]`、`.max-h-[min(320px,45vh)]`；**零丢失**（`only-old chunks = 0`）；字节 19130 → **19248**；备份 `hitl-interrupts.css.bak-r8`。
- 一个必要工具修正：Tailwind 把任意值里的逗号写成十六进制转义（`.max-h-\[min\(280px\2c 35vh\)\]`），类名白名单校验已补「十六进制转义解码」，否则会误判 `min(280px,35vh)` 缺失（修后 193/193 命中）。

## 断言数

**PASS=388 FAIL=0**（r7 的 374 条全部保留未回归，净增 14 条双约束断言）。

## 截图（42 张，`<LOCAL_WORK>/_cu-hitl-final/v8-*.png`）

正常视口与矮视口对照：`v8-a2.png`（1440×900，280px 容量）↔ `v8-a2-short.png`（1200×700，245px，明显收紧）；`v8-a6.png` ↔ `v8-a6-short.png`（320 → 315）。其余同 r7 场景全覆盖：`v8-s0 / a1(+approved/rejected/timeout) / a2(+filled/before-cancel/cancelled/submitted) / a3(+…/submitted) / a4(+…/cancelled-empty) / a5(+step1/step2/submitted/cancelled) / v8b-a5-submitted-scroll / a6(+filled/submitted/cancelled) / b1–b7(含 b2-reproposal-decided) / v8-scroll-a2·a3·a6 / v8-compare-pending-vs-readonly / overview-top·overview-full`。

## 入库

| commit | 内容 |
|---|---|
| `0ee27b8` | `hitl-interrupts.html`（r8，marker `2026-10-17-r8`）+ `hitl-interrupts.css`（五次重建）一笔 |
| `240421e` | 裁决件 **§14 第 299 行起**（14.1 第 304 / 14.2 第 315 / 14.3 第 321 / 14.4 第 327）· `docs/决策看板.md` **第 22 行** · `docs/任务看板.md` **第 14 行** |

已 `push origin main`（`0c3f7ee..240421e`）。`curl` marker `2026-10-17-r8` grep=1；CSS 200 / 19248 B；check-docs **6/6**。

## 自证

读自身 `wire.jsonl` 的 `profile.bind`：**`modelAlias = DeepSeek/deepseek-flash`**（`profileName=coder`；agent-960 / session_cbdb20ea）。

## 两点提请注意

1. **项目侧映射已按你的口径写入 §14.4**：长表格目标 `min(480px,60vh)`（修正了 §13.3 里我先提的 400），用户长消息折叠**维持 184 / 8 行不变**，其余按字段类 280/35vh、表单组合类 320/45vh 映射；`frontend/src` 仍零改动，待 2A / resultView 批施工。
2. **矮视口下 A2 的「其他」行更靠下**：280→245 后列表可视行数从约 4 行降到约 3.5 行，「其他」行需滚动才可见（与 r6/r7 报告同一处取舍）；若要它常驻视野，仍可把该行钉在滚动区外。
