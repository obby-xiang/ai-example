> 入库注记（2026-10-13）：来源落点 = 仓外工作目录 <SCRATCH>\r158-ux3\复核报告-R158-DSPro.md（2918 B）；源件 sha256 = `3b27125ec46f78fe3403f005b62754bccb0136c1ee6d52032948a5156b71e067`；入库批次 = R-158 入库批（2026-10-13，单笔提交）；本复核所用**审查锚** = 收口前工作树（DSPro 复核态）；收口修后由提交官**重锚** ⇒ 本批**入库锚** = `r158-review.diff` sha256 `74242001e325eccca25b5d49b47a306a52f72e98fc5404c5d84992f79176d11f`（审查锚 ≠ 入库锚，合 M2 立项口径）；本件除本注记行外与源件逐字节一致（零改写）。
# 复核报告 R-158 · DSPro 单方复核

**结论：有条件通过（MINOR-1）** —— 四项均落地，门禁全绿，仅 #10 回执徽标维度与 decision 字段存一处语义偏。

## DC-19（续轮免自证，引用 R-155 轮实测）

- 模型：`DeepSeek/deepseek-v4-pro`（profileName=agent，thinkingEffort=high）
- 会话：`session_f1716b4c-44fe-454a-b0e4-9ed0bf02cf02`

## 分级问题清单

**MINOR-1：#10 `decision` 字段写而未读，回执徽标由 `status` 派生（决策/执行维度混用）**
- 落点：`AiPanel.vue:800` hitlReceiptLabel 按 `part.status` switch；`decision` 写入 3 处（`ai.ts:1298/1317/1333`）但渲染零读（全仓 `.decision` 只写不读）。
- 现象：approve 后 status='running' 期间徽标显「执行中」而非「已确认」；「已确认」延后到 status='succeeded' 才显；已确认但执行失败的段显「执行失败」而非「已确认」。决策卡「已确认/已取消/已过期」系决策态，徽标却按工具执行态派生。
- 影响：非阻断——三徽标（已确认/已拒绝/已过期）均能经 status 呈现，refresh 回溯（decidedAt/submittedValues）正常；属语义偏+事实死字段。

## 四项核对

1. **#10 HITL 只读回执 — 通过（除 MINOR-1）**：`v-else-if="isHitlReceiptPart"` 回执卡（AiPanel.vue:303）；types/ai.ts ToolPart/Patch 增 decision/decidedAt/submittedValues；confirm() 写 decision+decidedAt（含幂等/失败回退清缺省）、submitGenerativeForm 写 submittedValues（ai.ts:1298-1333/:1490-1504）；历史三路径 spread 语义天然保留（sanitizeMirrorResidues:235/expireResidueToolParts:254 `{...part}`，writeMirror:299 parts 原样落盘），零改动成立。
2. **#11 复制段间换行 — 通过**：`deriveCopyText`（parts.ts:501）text 段 join('\n\n')，copyMessage 改接（AiPanel.vue:952）；deriveProjections/镜像/delta 零触碰（契约红线不破，parts-reducer 单测锁 content 契约）。
3. **#12 CollapsibleText — 通过**：新组件 RO 测真溢出（contentRect∨scrollHeight > maxHeight）才渲渐隐+「显示更多」；短内容零副作用；pretty JSON 缩进/失败原样；args/result/思考区三面复用；R-154 truncated 角标经 slot 保留；`expandedResults` 本地态退役。
4. **重试去除 — 通过**：retry 按钮+`retryMessage` 整列删；`skipUserRecord` 通道保留（ai.ts send 未动）；编辑重跑（R-157 MAJOR-1 面）用例仍绿，未回退。

## 门禁 / 纪律（独立复跑）

- 前端 258/258·20 spec ✓（253 +5：新增 5 UI +1 deriveCopyText −1 重试）；typecheck 0 错 ✓；build 未独立重跑（typecheck+vitest 覆盖）。
- ls-files = **1093** ✓；?? 仅 data/（常态）+ CollapsibleText.vue（+1 预期）+ docs/research（随批）。
- HEAD 仍 `28cfc22`（零 commit）；工作树 6 M + 3 ?? 无意外；后端零动、零新依赖。
