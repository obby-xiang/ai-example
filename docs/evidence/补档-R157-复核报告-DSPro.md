> 入库注记（2026-10-13）：来源落点 = 仓外工作目录 <SCRATCH>\r157-ux2\复核报告-R157-DSPro.md（2792 B）；源件 sha256 = `e42c3e160c5442c9d40aca37fbbec2ea000832d6c4507723641334c0705f8087`；入库批次 = R-157 入库批（2026-10-13，单笔提交）；本复核所用**审查锚** = `r157-review.diff` sha256 `95d53f47d0b36f39cb898550e44fd9a155f3f3576985e63c1350d2a29fabeab1`（复核后无代码改动 ⇒ 审查锚 = 入库锚，无重锚）；本件除本注记行外与源件逐字节一致（零改写）。
# 复核报告 R-157 · DSPro 单方复核

**结论：驳回（MAJOR-1）** —— 编辑/重试截断后经 `ai.send` 重发，产生重复消息，违反决策卡§2「改完从该条重跑 / 从对应提问重跑」业界语义。

## DC-19（续轮免自证，引用 R-155 轮实测）

- 模型：`DeepSeek/deepseek-v4-pro`（profileName=agent，thinkingEffort=high）
- 会话：`session_f1716b4c-44fe-454a-b0e4-9ed0bf02cf02`（R-155 轮已实测）

## 分级问题清单

**MAJOR-1：编辑/重试产生重复消息（核心语义缺陷）**
- 落点：`AiPanel.vue:835-844`（send 截断+重发）、`:791-808`（retryMessage）、`ai.ts:1054`（send 恒 recordUserMessage 追加）
- 现象：`send()`/`retryMessage()` 截断后调 `ai.send(text)`，而 `ai.send` 无条件 `recordUserMessage` 追加一条新用户消息 ⇒
  - 编辑：原消息（旧文本）保留 + 新文本重发为**新消息**，旧文本未更新、仍在会话中；
  - 重试：原提问保留 + 同文本重发 ⇒ 提问**重复出现两次**。
- 违反：决策卡§2「改完从该条重跑」「从对应提问重跑」；业界 regenerate 不重复提问、edit 应更新该条文本。
- 证据：`ai-panel-parts-render.spec.ts` 编辑重跑/重试用例自证 `['u-1', /^u-/, /^a-/]`（两用户消息并存）。

**MINOR-1：报告 diff --stat 精度偏**
- 施工报告:38-40 spec 报 170 实测 172；总插入报 314 实测 316（numstat 155/56 + 161/11）。

**MINOR-2：报告「改写 4 例」实为 3 例**
- 施工报告:17 改写 = isTyping/#7/#9 三例（新增 6 例无误，247+6=253 成立）。

**MINOR-3：操作行时间戳/操作未真正左右分置**
- `AiPanel.vue:323-330` 新操作行删去原 `justify-end`/`mr-auto`，时间戳与按钮相邻（gap-2）而非「左置/右置」两端分置；测试仅验「时间戳在操作行内」，未验分置。

## 逐面核对

1. 决策卡五点：①操作行外置=通过（气泡外 `msg-ops-*`，hover/focus 保留）；②编辑收窄=通过、重跑语义=**MAJOR-1**；③指示器接力=通过（groupParts 滤空 text 段 ⇒ 呼吸点/光标互斥接力，prefers-reduced-motion 已加）；④发送滚底=通过（followBottom+scrollBottom(true)）；⑤头像去文字=通过（SVG 零文字）。
2. 施工卡完成判据：五项+随批已落；DC-19 三要素已自证（kimi-code/kimi-for-coding·826849bc·profile.bind）。
3. 门禁独立复跑：前端 253/253·20 spec ✓；typecheck 0 错 ✓；ls-files 1088 无意外新增 ✓；build 未独立重跑（typecheck+vitest 已覆盖，避 dist 产物，报告 claim 可信）。
4. 纪律：git log HEAD 仍 22d919a ✓；工作树仅 2 M + ?? data/ ✓；R-155 reason 链/后端/依赖零动 ✓。
5. 分级：MAJOR-1 + MINOR-3（见上）。
