> **历史分析 / 过程证据 — 非现行基线，非验收结论**
>
> - 归档：2026-10-26 · `docs/evidence/history-recovery-2026-10-26/wire-tail/`
> - 来源：Kimi Code 会话 `<KIMI_SESSION>` / 子 agent `agent-535` 的 wire 末条 assistant 文本（消息序号 m=8；只读流式解析，非原始会话转写）
> - 历史时点：2026-10-12 03:46 → 2026-10-12 03:47（wire 事件时间窗，UTC+8）；当时模型：`DeepSeek/deepseek-flash`
> - 原基线：正文未出现基线哈希，未经本批复验
> - 原始体量：10946 B / 7693 字符 / 115 行；行指纹覆盖率（本批复算）3.7%（最高单文件 4%：`docs/evidence/history-recovery-2026-10-26/wire-only/entries/024-结论摘要-4309e010-agent-440.md`）
> - 类别：**过程核验**
> - 与既有件关系：024-结论摘要；报告口径覆盖率 0.04
> - 脱敏：路径 0 / 会话标识 0 / 身份 0 / 密钥 0 / 命名纪律 0
> - 正文为该条消息的完整文本，未删段、未改写结论；仅脱敏、占位符归一与行尾空白规整。当时结论不代表当前代码状态，不得作为当前缺陷或有效裁决引用。
核验完成。以下逐项给证据。

---

## 1. `scripts/m2t2a-smoke.mjs` — PASS（除断言计数表述需订正细节）

| 核验点 | 结果 | 证据 |
|---|---|---|
| 总行数 488 | **PASS** | `wc -l` = `488`；`Read` 状态块 `Total lines in file: 488` |
| :53 常量 | **PASS** | `53	const TESTID_TAG_CANCELLED = 'tag-form-cancelled'` |
| :39 注释引用 | **PASS** | `39	// data-testid（gf-submit / gf-cancel / pending-card-<toolCallId> / tag-form-cancelled），` |
| :166 注释引用 | **PASS** | `166	// tag-form-cancelled 计数；store 侧 pendingStatuses 仅供 detail；toolRunCount 判无重复工具卡` |
| :176 计数表达式 | **PASS** | `176	    cancelledTags: document.querySelectorAll('[data-testid="${TESTID_TAG_CANCELLED}"]').length,` |
| :299 断言③b | **PASS** | `299	  assert('③b', c2.formRendererCount === 0 && c2.cancelledTags === 2,` |
| 5 处 `assert('` | **PASS** | `grep -c "assert('"` = 5；行号 :285 / :297 / :299 / :346 / :457，与草案一致 |

断言名清单（`assert(name, cond, detail)` 定义在 `:183`）：`②`(:285)、`③`(:297)、`③b`(:299)、`①`(:346，阶段3a 未收到帧的 fail-fast 分支)、`①`(:457，阶段3d 终局)。草案「①/②/③/③b」口径正确，附带说明：**① 出现两次**（:346 为失败路径，:457 为正常路径），不是唯一调用点。

关键旁证（草案 C-2 的断言③b 失效判断成立）：`AiPanel.vue` 内 `tag-form-cancelled` **零命中**（全仓代码域仅脚本自身），故 `cancelledTags === 2` 已无 DOM 对应物。

## 2. `scripts/chat-panel-smoke.mjs` — PASS（⑫/⑬ 存在，但物理顺序倒置）

- 文件 823 行；`assert('` 调用点 = **13 处**，即 ①…⑬，无重号、无缺号。
- :737 `assert('⑬', heldId !== null && s13.skeleton === true && s13.historyLoaded === false` — **PASS**（草案称 :737 assert('⑬') 正确）
- :797 `    assert('⑫', !!inflight2 && !!sawInterrupt && !!s12 && s12.streamInterrupted === true` — **PASS**（草案称 :797 assert('⑫') 正确）
- 需注意：**⑬ 在源码中先于 ⑫ 出现**（步序 = 项4 在项3 之前），:794/:795 另有 `RESULT.skipped['⑫']` SKIP 记录。若草案按「编号顺序 = 执行顺序」引用将会误判。「13 断言」归属本文件，成立。

其余调用点：①:454 ②:472 ③:487 ④:514 ⑤:529 ⑥:558 ⑦:570 ⑧:602 ⑨:638 ⑩:669 ⑪:696。

## 3. `\.pendingCall\b` 于 `frontend/src` — **FAIL（草案 19 处，实测 21 行 / 26 次）**

`.spec.ts` 零命中（PASS 部分）。逐条（行号 + 原文关键片段）：

**AiPanel.vue — 模板 9 处（全部 PASS，行号与草案逐个一致）**
- :201 `<div v-if="m.pendingCall && m.pendingCall.toolCallId === part.toolCallId" :data-testid="'pending-card-' + m.pendingCall.toolCallId"`
- :204 `<span>{{ toolLabel(m.pendingCall.name) }}</span>`
- :205 `<el-tag v-if="m.pendingCall.kind === 'CONFIRM'" …>需确认</el-tag>`
- :206 `<el-tag v-if="m.pendingCall.status === 'expired'" …>会话已失效</el-tag>`
- :208 `<div v-if="argsText(m.pendingCall.args)" …>`
- :209 `<code>{{ argsText(m.pendingCall.args) }}</code>`
- :212 `<template v-if="m.pendingCall.kind === 'CONFIRM' && m.pendingCall.status === 'pending'">`
- :233 `<div v-else-if="m.pendingCall.kind === 'FRONTEND' && m.pendingCall.name === GENERATIVE_FORM_TOOL" class="mt-2">`
- :238 `v-if="liveForm && liveForm.toolCallId === m.pendingCall.toolCallId && m.id === formHostMessageId"`

**AiPanel.vue — script 2 处（PASS）**
- :482 `const pending = ai.messages[i]?.pendingCall`
- :730 `return \`…|${last && last.pendingCall ? last.pendingCall.status : ''}\``

**ai.ts — 草案列 6 处（全部存在，但漏报 2 行）**
- :193 `const pending = message.pendingCall` ✔
- :299 `pendingCall: message.pendingCall` ✔
- :467 `if (message.role === 'assistant' && message.pendingCall) {` ✔
- :470 `if (message.pendingCall.status === 'pending') {` ✔
- :471 `return message.pendingCall` ✔
- :1262 `const args = message.pendingCall?.args ?? parseToolArgs(frame.args)` ✔
- **漏报 :1710**（注释）`// 旧口径的 \`message.pendingCall = pendingCall?.status === 'pending' ? null : ...\` 直写`
- **漏报 :1799**（注释）`* 批2（权威翻转）：原先直接改 \`item.pendingCall\` 的那一半已删除 —— 挂起卡是段的派生结果。`

**types/ai.ts:262（PASS，注释）** `* 为什么落在段上（批2 起）：\`message.pendingCall\` 已改为由段派生，…`
**parts.ts:216（PASS，写点）** `message.pendingCall = pending`

统计口径澄清（这是与草案不符的根源）：命中 **21 行 / 26 次**（AiPanel.vue 11 行/16 次，ai.ts 8 行/8 次，types/ai.ts 1 行，parts.ts 1 行）。草案的 19 = 18 行可执行代码 + 唯一被计入的注释行 `types/ai.ts:262`；即**草案同时漏掉 ai.ts 的两条注释行、却计入了一条注释行**，计数规则不自洽。纯代码行为 **18**，纯「非注释或非注释内」行按草案口径应为 **19 亦不成立**。

## 4. `expandedBlocks|groupParts|blocksOf` 全仓 — **PASS（零命中）**

`grep -rnE "expandedBlocks|groupParts|blocksOf" .`（排除 `node_modules`/`.git`）= 空输出。

## 5. `m2t2a` 于 CI / package.json — **PASS（零命中，附一处表述瑕疵）**

- `.github/workflows/ci.yml`：**零命中**（该文件 e2e job 只调 `./scripts/verify-e2e.ps1`）；全仓 `m2t2a` 命中仅限 `scripts/m2t2a-smoke.mjs` 自身 + `scripts/chat-panel-smoke.mjs:52` 注释 + docs，`scripts/verify-e2e.ps1` 零命中。
- `frontend/package.json`：**零命中**，且 `"test": "vitest run"` + `frontend/vitest.config.ts:10 include: ['tests/**/*.spec.ts']` ⇒ 脚本确不在 `yarn test` 链路。
- **瑕疵**：仓库根**不存在 `package.json`**（根目录仅 `.github README.md backend data docs frontend scripts spike .gitignore .git`）。草案「grep package.json 零命中」实为文件不存在，属空真，宜改为「根无 package.json」。

## 6. `tag-form-cancelled` 全仓 — 代码域 **PASS**，docs 域 **FAIL（7 份而非 ≥8）**

**代码/测试域 = 3 处，PASS**（与草案 C-2 一致）：
- `scripts/m2t2a-smoke.mjs:39`（注释）、`:53`（`const TESTID_TAG_CANCELLED = 'tag-form-cancelled'`）、`:166`（注释）
- `frontend/tests/` **零引用**；`frontend/src/**` **零引用**（`AiPanel.vue` 无此 testid ⇒ 引用已成悬空）。

**docs/evidence 域 = 7 份 / 17 处行命中（FAIL「≥8 份」）**：

| 文档 | 次数 |
|---|---|
| `docs/evidence/补档-CHATTIMELINE-批3③b-红队报告-DS-V4-Pro.md` | 4 |
| `docs/evidence/M2-T2b-施工与验证-K2.8.md` | 3 |
| `docs/evidence/补档-CHATTIMELINE-批3③b-施工报告-K2.8.md` | 2 |
| `docs/evidence/补档-CHATTIMELINE-批3③b-施工卡.md` | 2 |
| `docs/evidence/补档-CHATTIMELINE-批3③b-复核报告-GLM-5.3.md` | 2 |
| `docs/evidence/补档-CHATPANEL-施工卡.md` | 1 |
| `docs/evidence/补档-CHATPANEL-施工报告-苞B.md` | 1 |

域外（`docs/` 根，非 evidence）：`docs/M2-排期计划.md` ×1。**「≥8 份」只有在把 `docs/M2-排期计划.md` 也算入、且域写作「docs/」时才成立**；按「docs/evidence/」域实测为 **7**。另注意历史上的「3 份」口径（见 `补档-CHATTIMELINE-批3③b-施工报告-K2.8.md:105`）已过期，现态 7 份。

## 7. 额外：`scripts/` 域与 `frontend-tools.ts` 域的 `pendingCall` 消费面

- `scripts/` 全目录：**仅 3 处命中，全在 `m2t2a-smoke.mjs`**：
  - `:36`（注释）`//   ③ 取消后所有持该 toolCallId 的消息 pendingCall.status 收敛为 cancelled`
  - `:174`（**唯一运行时消费点**）`pendingStatuses: ai.messages.filter(m => m.pendingCall && m.pendingCall.toolCallId === '${toolCallId}').map(m => m.pendingCall.status),`
  - `:288`（注释）`// ── 阶段 2：取消 → 断言③所有消息 pendingCall.status 收敛 cancelled ──────`
- `frontend/src/utils/frontend-tools.ts`（红线文件）：**`pendingCall` 零命中 —— 无任何 pendingCall 消费面**。该文件与 `toolCallId` 的唯一接触点是 `:60` `toolCallId?: string` 类型字段；文件头自述 `generative_form` **不走本文件通用执行器**（`stores/ai.ts` 特判 + `AiPanel` 渲染 `FormRenderer`，`frontend-tools.ts:14-16`）。⇒ 对 `pendingCall` 的任何改形/退役**不会触及 frontend-tools.ts**；它的契约耦合面在 `toolCallId` 入参，而非 pendingCall。

---

## 结论摘要

**与草案不符处（4 项）**

1. **§3 计数错**：`\.pendingCall\b` 于 `frontend/src` 实测 **21 行 / 26 次**，草案 19 处漏报 `stores/ai.ts:1710`、`:1799`（两条注释行），同时却把注释行 `types/ai.ts:262` 计入 ⇒ 计数规则不自洽。草案列出的 19 个行号**逐个实读在位**，无一处虚指——错的是总数与口径声明。
2. **§6 计数错**：`docs/evidence/` 命中 `tag-form-cancelled` 的文档实测 **7 份 / 17 处**，非「≥8 份」；差 1 份 = 把 `docs/M2-排期计划.md`（`docs/` 根，不属 evidence）算了进来。代码/测试域 3 处（:39/:53/:166）口径正确。
3. **§5 表述瑕疵**：仓库根**无 `package.json`**，该条 grep 为空真。
4. **§2 顺序陷阱**：`chat-panel-smoke.mjs` 13 断言成立，但 **⑬(:737) 物理上先于 ⑫(:797)**（执行序 项4 → 项3），引用时勿按编号推顺序。

**scripts/ 域 pendingCall 消费情况**：仅 `m2t2a-smoke.mjs:174` 一处运行时读（`ai.messages[].pendingCall.status` 映射），服务于断言③（:297）与③b（:299）；另有 :36/:288 两条注释。**该脚本的阶段 1/2 现态可达性存疑**——仓内既有登记（`docs/M2-排期计划.md` R-106、`docs/evidence/补档-NUMSPEC-施工报告-苞F.md:166`）指出 `injectExpr` 带宿主消息 id 会被 `ai.ts` 的 `activeRoundHostId` 轮次守卫判为旧轮帧丢弃，⇒ `message.pendingCall` 永不写入、阶段 1/2 断言②③③b 必 FAIL。即：**脚本域的 pendingCall 消费面目前很可能是「死消费」**，任何以「m2t2a 断言③b 会挡住回归」为前提的论证都不成立（叠加：`tag-form-cancelled` 的 DOM 已被批 3③b A3 删除，③b 断言语义上永不成立）。

**frontend-tools.ts 域 pendingCall 消费情况**：**零消费面**（`pendingCall` 零命中，仅 `toolCallId?: string` 类型位）。⇒ **D2 前置核实若把 frontend-tools.ts 列为 pendingCall 改形的风险面，属过度假设**；真实耦合面在 `frontend/src/stores/ai.ts`（8 行）+ `AiPanel.vue`（11 行，含 9 处模板绑定）+ `types/ai.ts`（1 行注释 + 类型定义 :205/:341）+ `utils/parts.ts`（:216 唯一写点）。**前置核实充分性结论**：草案对 frontend-tools.ts 的 pendingCall 消费假设不成立，但对 `pendingCall` 消费已集中在 store/组件/类型三处的判断，方向正确且可据上表逐行核对。
