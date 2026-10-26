<!-- 历史中间稿证据条目（自动生成头部，非正文）。源 = Kimi Code assistant wire 文本；不含 think / 工具输出 / 原始用户消息。 -->

# MT-256 · 过程回执 · 结论（cbdb20ea / agent-960）

> **项目归属**：**项目硬锚点**（正文命中 ai-example 路径 / 项目类名 / 项目文档名 / 项目 UI 概念）。
> **归属依据**：正文命中：`scripts/ci`、`check-docs`、`docs/demo`、`chat-ux`。
> **稿态**：历史中间稿 —— 该 wire 第 `m61` 条 assistant 消息（m 按 wire 内 assistant 消息计，含工具调用轮）；本条是该 wire 第 `mt5` 条有正文的 assistant 消息（共 33 条有正文），末条有正文消息为 `m435`。**非现行基线、非验收结论、非当前缺陷清单**。
> **来源**：会话 `cbdb20ea` / agent `agent-960`（`main` = 主会话）；原字符 3,896（链内 1 段、合计 3,896）。
> **链号 / 类别**：`MT-256` / **过程回执**；判定：过程回执（自证/元数据/交付回报）；**不是用户批准**。
> **轮次**：位于该 wire 第 1 个用户轮之后；相邻用户轮含决断类字样（仅记布尔，未复制用户原文）。
> **去重**：行指纹对「仓内 + 落盘语料」覆盖率 `6%`（<60% 视为仓内无等价件）；同 wire 后文覆盖率 `8%`。
> **与末条归档（138 件）的关系**：末条 `m435`（2,485 字符）未入末条归档。
> **脱敏**：路径 3 / 会话标识 0 / 身份 0 / 密钥 0 / 旧仓名与同源仓名 0 处。

---

全部完成并已推送。以下是交付报告。

## 结论

r3 已按 K1~K8 全量返工、验证并入库推送：**两笔提交**（`0b32b8a` html、`3813ee0` 文档），`origin/main` 已同步。

## K1~K8 逐条落实证据

| 口径 | 实现落点 | 机器证据（Playwright 断言） |
|---|---|---|
| **K1** 已决=原样 UI 只读 | 页内 `lock()`：决策时克隆原卡正文 → 删 `[data-live-only]`（按钮/倒计时/提示整块）、删 `.hidden` 残留、控件 `tabindex=-1`、文本/数字/日期 `readonly`、容器 `pointer-events-none` + `data-locked`，另加捕获阶段 click/keydown 兜底；徽标+decidedAt 在卡头；卡原地留存，无 opacity 变化 | 每张活卡决策后：`button=0`、`tabbable=0`、`pointer-events=none`、徽标唯一、时间匹配 `2026-MM-DD HH:MM`；A2 单选勾选态、A3 复选框勾选态（4/3）、A4 与 B6 输入值、A5 窗口半成品值、A6 六型值回填全部保留 |
| **K2** H1 独立 + 恰四态 | A1 只有 拒绝/批准 两钮、无取消出口、不入多步；终态 approved/rejected/timeout + B4「已批准 · 执行失败」 | `#a1` 内无「取消」字样/钮；A1 三条路径各断言一次；A1 的徽标集合里没有 cancelled |
| **K3** 执行失败 | B4：徽标「! 已批准 · 执行失败」+ 参数区 3 行原样 + 失败原因单独一行（`bg-[#fdf1ef]`）常驻 | 断言失败行文案 `执行失败：目标实例 order-svc-04…` 存在、参数区 3 行 |
| **K4** 已取消只属非审批 | 取消只出现在 A4 / A5 / A6 与样张 B6；A1 无 | A4/A5/A6 取消路径断言；A1 反断言 |
| **K5** 无禁用态 + 按钮底部靠右 | 不可用按钮不渲染（无灰钮）；按钮行 `flex-1` 占位 + 主钮最右 | 全页 `[disabled]=0`（复载后再断言一次）；每个按钮行末钮右缘与行右缘 ≤24px |
| **K6** 逐行复选框 / 其它成行 | A2/A3/A5/A6 全部单选、复选一行一个；「其它」是行内复选框，勾选展开输入；未填不渲染提交 | A2/A3「其它未填→无提交钮」「填字→出现提交钮」断言；A6 多选逐行 |
| **K7** 拒绝后修正走会话 | B2：已拒绝卡（原样）+ 紧跟一张 AI「已按反馈修正」的待决审批卡 | DOM 顺序断言（`compareDocumentPosition`）、该卡 pending 且恰 2 钮、文案含「已按反馈修正」；点击批准后同样转只读 |
| **K8** 全 Tailwind / 禁 emoji / 单文件 | 只用 `hitl-interrupts.css` 预编译产物里的类（**CSS 文件零改动**） | 类名逐类核对：183 个 token / missing **0**；`style="`=0、`<style>`=0、仅 1 条 CSS 外链；正文 emoji 仅 ✓ / ✗ |

## 场景清单对照

- **S0** 口径卡 K1~K8 一行一条 ✅
- **A1** H1 审批（60s 倒计时 + 卡外「立即触发超时」）✅ · 批准/拒绝/超时三终态 ✅
- **A2** H2 单选（4+其它，有效选择前无提交）✅ · 含「其它+文字」路径 ✅
- **A3** H2 多选（逐行复选框 + 其它 + 已选计数）✅
- **A4** H3 输入（必填 / 空白无提交 / 取消出口）✅
- **A5** 多步 2 步（步骤1 多选 → 步骤2 输入；四控件底部靠右、不适用不渲染；进度 N/2；末步内联摘要；提交后逐步两张只读卡）✅；首步取消只留第 1 步卡，末步取消两张卡均为「已取消」且窗口半成品值保留 ✅
- **A6** GF 六型（text / number / date / enum / multi_select / boolean 开关）同卡，提交后同一张表单锁定值回填 ✅；取消路径 ✅
- **B1** 已批准（参数区 3 行=批准前）✅ **B2** 已拒绝 + 重新提案 ✅ **B3** 已超时 ✅ **B4** 已批准·执行失败 ✅ **B5** 多选已提交（4 控件/3 勾选 + 其它值）✅ **B6** 输入已取消（保留 `CHG-2026-04`）✅ **B7** 消息流串联 3 张不同终态 ✅

## 验证

- `node <LOCAL_WORK>/_cu-hitl-final/v3-test.mjs` → **PASS=204 FAIL=0**（含已决态结构校验：按钮数、可聚焦控件数、`pointer-events`、徽标唯一性、decidedAt、勾选/输入值保留）
- `node <LOCAL_WORK>/_cu-hitl-final/v3-classes.mjs` → 类名 183 个全命中、disabled=0、违禁字样=0、场景序 `s0,a1…a6,b1…b7`、emoji 仅 ✓/✗
- `node scripts/ci/check-docs.mjs` → **6/6 断言绿**
- `curl http://localhost:18330/hitl-interrupts.html | grep -c 'data-hitl-final="2026-10-17-r3"'` → **1**（响应 100944 B，与工作树文件逐字节等长）

## 截图（32 张，`<LOCAL_WORK>/_cu-hitl-final/v3-*.png`）

`v3-s0 / v3-a1 / v3-a1-approved / v3-a1-rejected / v3-a1-timeout / v3-a2 / v3-a2-submitted / v3-a2-submitted-other / v3-a3 / v3-a3-submitted / v3-a4 / v3-a4-submitted / v3-a4-cancelled / v3-a5 / v3-a5-step2 / v3-a5-submitted / v3-a5-cancelled / v3-a5-cancelled-step2 / v3-a6 / v3-a6-filled / v3-a6-submitted / v3-a6-cancelled / v3-b1 / v3-b2 / v3-b2-reproposal-decided / v3-b3 / v3-b4 / v3-b5 / v3-b6 / v3-b7 / v3-overview-top / v3-overview-full`

## 入库

| commit | 内容 |
|---|---|
| `0b32b8a` | `docs/demo/chat-ux/hitl-interrupts.html` 全文返工（1067 行，r3） |
| `3813ee0` | 裁决件 §9 追加注记（**第 122–146 行**，§1–§8 与 §8 旧注记原文冻结；9.1 纠偏「澄清恢复本意，非推翻」；9.2 K1 + 第四态定名）/ `docs/决策看板.md` **第 17 行**决策流水一行 / `docs/任务看板.md` **第 14 行**问题2 行补记「demo r3 全场景返工待终裁（`0b32b8a`）」 |

## 自证

读自身 `wire.jsonl` 的 `profile.bind` 单行：**`modelAlias = DeepSeek/deepseek-flash`**（`profileName=coder`、`thinkingEffort=high`；路径 `~/.kimi-code/sessions/wd_temp_38dcbf7ced2f/session_cbdb20ea…/agents/agent-960/wire.jsonl`）。

## 需上层知悉的两点判断

1. **B2 的「AI 重新提案」卡是可交互的活卡**（pending 必须有 拒绝/批准 两钮，否则不成立「待决审批卡」）。因此「B1~B7 零可交互控件」的断言口径落为：`[data-sample]` 内的已决样张零按钮、零可聚焦控件、`pointer-events:none`；B2 的重新提案卡是独立的 `[data-card="b2-reproposal"]`，单独断言其 pending + 恰 2 钮，并在点击后同样转只读。若上层要求 B 区整体静态，可把该卡改为纯静态待决样张（会失去一个活路径演示）。
2. **K7 之外没有任何新增交互**；A2/A3 的 cancelled 徽标为预留（未接取消钮，H2 无取消出口的需求未提），不影响任何断言。CSS 未改、无 `data/` 无关产物入库（工作树仅剩一个既有的未跟踪 `data/` 目录，非本次产生）。
