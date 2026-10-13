> 入库注记（2026-10-13）：来源落点 = 仓外工作目录 <SCRATCH>\r155-reason\复核报告-R155-DSPro.md（2995 B）；源件 sha256 = `f2e3c53ddb74c1c03e3d97705dedc528f136159907f1aefbcb42c8f753431c9f`；入库批次 = R-155 入库批（2026-10-13，单笔提交）；本复核所用**审查锚** = `r155-review.diff` sha256 `e35797e3aac0822b82ed22cb8af35b39040d0242d5b825ff2243d8451c7df6ce`（复核后无代码改动 ⇒ 审查锚 = 入库锚，无重锚）；本件除本注记行外与源件逐字节一致（零改写）。
# 复核报告 R-155 · DSPro 单方复核

**判级：有条件通过（MINOR-2）** —— 代码实现完整正确、门禁全绿、零 commit，仅施工报告存在两处 file:line 精度偏差（不影响交付物本身）。

## DC-19 三要素（实测，非口头）

- 实际运行模型：`DeepSeek/deepseek-v4-pro`（profileName=agent，thinkingEffort=high）
- 会话 id：`session_f1716b4c-44fe-454a-b0e4-9ed0bf02cf02`（state.json:lastPrompt=R-155 复核派发）
- wire profile.bind 实证：`{"type":"profile.bind","agentId":"main","modelAlias":"DeepSeek/deepseek-v4-pro","profileName":"agent","thinkingEffort":"high",...}`（wire.jsonl 首条）

## 六面结论

**① 方案符合性 — 通过**
- start_publish 落点（非 run_job）：`ai.ts:2055` `frame.name !== 'start_publish' → return`
- 注册表登记/清理闭环：登记 `ai.ts:2069` linkPublishTool；命中注销 `ai.ts:2108`；换会话注销 `ai.ts:2090`；resetSession 清 `ai.ts:1627` clearPublishToolLinks
- 查无关联静默走原回执：`ai.ts:2085-2087` 无 link 返 false；`workspace.ts:489` reportUiEventResult 恒在
- reason 唯一写口：`ai.ts:2100` upsertToolPart(createIfMissing=false)

**② 代码质量 — 通过**
- 泄漏面：resetSession 全清 + 冲突命中/换会话即时清；非冲突成功态 linger 至 resetSession（会话内内存态，有界，见⑤②）
- 多会话隔离：link 存 sessionId，消费校验 `ai.ts:2089`；模块级 Map 每 tab 独立
- 职责划分清晰：`publish-links.ts:16-31` 纯注册表 4 op；ai.ts 登记+消费；workspace.ts:484 查询；懒 import 避 ai↔workspace 静态环
- 无顺手扩面：types/ai.ts 未动（reason 字段既有，决策卡§一），job-watch.ts 仅注释

**③ 测试 — 通过**
- 5 例覆盖齐全：关联命中(①②③)、查无关联负例(④)、终态清理 resetSession(⑤)
- 独立复跑：`publish-conflict-link.spec.ts` 5/5 绿；全量 `247/247 (20 files)` 无损（242+5）；`vue-tsc --noEmit` 0 错（EXIT=0）

**④ 纪律 — 通过**
- AiPanel.vue 0 行：git status 无 M，diff 未触（渲染位 :263-267 既有）
- job-watch.ts 仅注释：`job-watch.ts:246-247`
- 零 commit：HEAD 仍 `30b9f10`，工作树 3 M + 2 ?? + ?? data/ 与卡一致

**⑤ 风险项核判 — 可接受**
- ① 超时抖动：独立两趟全绿未复现，可信但不可证伪
- ② linger 至 resetSession：可接受（会话内内存态有界，命中/换会话即时清，合§五不扩 toolCallStates）
- ③ reason 不进镜像：合决策卡§五口径

**⑥ 报告偏差 — MINOR-2**
- MINOR-1：publish-links.ts 报告称 24 行，实测 32 行
- MINOR-2：resetSession 行号报告 :1662-1663，实测 :1627（另 armPublishConflictWatch 报 :2054-2082 实测 :2054-2076、attachPublishConflict 报 :2084-2114 实测 :2084-2113，尾部小偏）

## 附：锚自验

审查锚 `r155-review.diff` sha256 = `e35797e3…c7df6ce`，与卡给定一致；diff 与工作树逐文件一致。
