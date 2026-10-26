<!-- 历史中间稿证据条目（自动生成头部，非正文）。源 = Kimi Code assistant wire 文本；不含 think / 工具输出 / 原始用户消息。 -->

# MT-168 · 施工复核 · 0. profile.bind 自证（60549341 / agent-1072）

> **项目归属**：**项目硬锚点**（正文命中 ai-example 路径 / 项目类名 / 项目文档名 / 项目 UI 概念）。
> **归属依据**：正文命中：`demo v2`、`scripts/ci`、`check-docs`、`docs/demo`。
> **稿态**：历史中间稿 —— 该 wire 第 `m91` 条 assistant 消息（m 按 wire 内 assistant 消息计，含工具调用轮）；本条是该 wire 第 `mt12` 条有正文的 assistant 消息（共 16 条有正文），末条有正文消息为 `m129`。**非现行基线、非验收结论、非当前缺陷清单**。
> **来源**：会话 `60549341` / agent `agent-1072`（`main` = 主会话）；原字符 2,455（链内 1 段、合计 2,455）。
> **链号 / 类别**：`MT-168` / **施工复核**；判定：施工复核记录（当时那次核验看到什么，不证明当前仍成立）；**不是用户批准**。
> **轮次**：位于该 wire 第 9 个用户轮之后；相邻用户轮未见决断类字样（仅记布尔，未复制用户原文）。
> **去重**：行指纹对「仓内 + 落盘语料」覆盖率 `19%`（<60% 视为仓内无等价件）；同 wire 后文覆盖率 `30%`。
> **与末条归档（138 件）的关系**：末条 `m129`（2,568 字符）未入末条归档。
> **脱敏**：路径 2 / 会话标识 1 / 身份 0 / 密钥 0 / 旧仓名与同源仓名 0 处。

---

**最终门禁结论：PASS（5/5 全绿；status 恰 8 文件；demo v2 approved；未提前入库）**

### 0. profile.bind 自证
`<KIMI_HOME>/sessions/wd_temp_38dcbf7ced2f/session_60549341/agents/agent-1072/wire.jsonl:5` → `agentId=agent-1072`、`profileName=coder`、`modelAlias=DeepSeek/deepseek-flash`、`thinkingEffort=high`。本轮未改任何文件、未提交、未起停服务。

### 1. 门禁结果（独立执行）

| 项 | 命令 | 退出码 | 实测 |
|---|---|---|---|
| 单测 | `yarn test` | **0** | `Test Files 23 passed (23)`、`Tests 444 passed (444)`；`ai-panel-parts-render.spec.ts` **173 tests / 3067ms**；总 8.36s |
| 类型检查 | `yarn typecheck` | **0** | `vue-tsc --noEmit`，5.09s，无错误 |
| 构建 | `yarn build` | **0** | vite 6.4.4，`✓ built in 24.73s`，30.59s 完成 |
| 文档一致性 | `node scripts/ci/check-docs.mjs` | **0** | `6/6 断言绿`（1244 路径，实扫 1241，豁免 3） |
| 空白/冲突 | `git diff --check` | **0** | 无 whitespace error；仅 8 条 LF→CRLF 提示 |

日志：`<LOCAL_WORK>/_cu-composer-project-r26/final-gate-test.log`、`final-gate-typecheck.log`、`final-gate-build.log`、`final-gate-check-docs.log`、`final-gate-diff-check.log`。

**警告**（均既有、不阻断）：
- 单测：406 条 Vue warning（390 `Failed to resolve component: el-*` + 16 `Failed to resolve directive: loading`），集中于 3 个既有 spec；`FAIL/×/AssertionError/Failed Tests` 计数 **0**，无 degraded 粘性签名。
- 构建：1 条 vite reporter 提示（`stores/ai.ts` 动态+静态双导入），既有分块提示。

### 2. status（恰 8 个 M + 历史 data）
```
 M docs/demo/README.md
 M docs/demo/chat-ux/composer-decisions.html
 M docs/research/调研-AI面板业界细则-2026-10-17.md
 M docs/任务看板.md
 M docs/决策看板.md
 M frontend/src/components/AiPanel/AiPanel.vue
 M frontend/src/stores/ai.ts
 M frontend/tests/ai-panel-parts-render.spec.ts
?? data/
```
- `M` 文件数 = **8**；无多余文件；`frontend/dist/` 已被 `.gitignore:17` 忽略。
- Diff 规模：3 代码 `240+/49-`、`40+/9-`、`726+/25-`；5 docs 合计约 `68+/24-`。
- 项目范围外 diff：**NONE**（8 个路径均在预期清单内）。

### 3. 静态口径核验
- **demo v2 approved**：`data-composer-decisions="2026-10-18-v2"`、`data-review-status="approved"`，标题「方案批准稿（已批准）」；版本仍为 v2，交互/布局未改。
- **任务看板**：新增 `10-19` 行「批·问题4 composer v2 项目回落」状态 **📤**，明文「完成待提交」「**未提前写完成态 / 入库 SHA**」；旧 v7.11 三勾位行改标 supersede、状态仍 📋。
- **决策看板**：新增两行 —— 用户终裁行 **✅**（用户「通过，开始吧」的裁决状态，属决策本身）、项目回落行 **📤**；项目回落未写 ✅ / 入库 SHA。
- **无✅提前入库**：新增行中无「闭环入库 / 入库锚 / commit SHA」；任务看板新增 ✅ 仅出现在既有统计口径「含 ✅ 34」，非本批完成态。
- **README**：composer v2 行状态改为「定稿」，补批准说明（approved、v2 不变、项目回落见 10-19 📤）。

### 4. 注意点（如实）
- 决策看板用户直裁行是 ✅、项目回落行是 📤；若门禁口径要求「10-19 两行均 📤」，则用户直裁行的 ✅ 属需澄清的差异（我理解 ✅ 表示用户裁决已完成、非项目入库，故未判失败）。
- 看板自述 `444/444`、`targeted 173/173` 与本轮实测完全一致；本轮未复跑浏览器验收（本门禁范围仅单测/类型/构建/docs）。
