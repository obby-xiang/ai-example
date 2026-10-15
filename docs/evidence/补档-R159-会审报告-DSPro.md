# 会审报告 R-159 · DS V4 Pro 单方评议

**结论：有条件通过 + 必修清单（无 MAJOR，5 处 MINOR）。** 六方案与 demo 色值/时序一致，7 锚点 6 准 1 色偏，基线 265/265·1103 实测成立；修 5 处事实/风险精度后即可定稿。

## 锚点核验（7 处，Read 行段实读）
1. `AiPanel.vue:784-790` showCaret ✓（工具卡成末块→光标消失，准确）
2. `AiPanel.vue:655-656` isTyping ✓（`streaming && 无非空 text`，首字符即翻，准确）
3. `AiPanel.vue:337` 呼吸点 ✓
4. `AiPanel.vue:1121-1129` 光标 CSS **色偏**：实为 `background:#409eff`，卡/demo 称 `#3b82f6`（demo `.caret.now` 同错）
5. `stores/ai.ts:1813-1819` delta `content += frame.text` 无节流 ✓
6. `utils/parts.ts:341-347` delta `part.text +=` ✓
7. `blocks.ts:69-93` → 实为 `utils/blocks.ts:69-93`（groupParts，内容核实无误，卡省略 `utils/` 前缀）

## MINOR 清单（必修）
- **M1 事实**：光标现状色 `#409eff`（AiPanel.vue:1125），卡问题②/demo「播放现状」误作 `#3b82f6`；demo 号称 1:1 复刻但现状色不 1:1。改 demo `.caret.now` 与卡为 `#409eff`。修正方向（B1 `#303133`）不受影响。
- **M2 事实**：「1500ms 拖尾」前后端均无实证——frontend 无 `1500`/setTimeout 拖尾，backend 无 done 延迟（仅 batch-job/启动无关项）；光标经 showCaret 随 `streaming=false` **即时消失**（无 fade）。demo「现状」:272 的 1500ms 属虚构。fix#4 口径应改「加 300ms 优雅淡出」而非「废 1500ms 拖尾」，并落实拖尾真实来源。
- **M3 路径**：`blocks.ts` 应为 `utils/blocks.ts:69-93`。
- **M4 风险遗漏（会审点名③）**：工具期光标归位边界未入 §五——工具为末块且流未收口（message_end 未到）时 showCaret 恒 false，「无后续文本立即归位+完成收尾」依赖 message_end 时序；施工卡须明确归位判据与收尾触发（是否仍绑 streaming=false）。
- **M5 风险错位**：§五风险2「markdown/复制取数」实无虞——正文纯文本渲染（`:188 {{ textOf(block) }}`，全仓无 v-html/marked），复制取 parts/content 非 DOM；**真正冲突** = 逐 chunk `span.ch` append 渲染 vs 现状整块插值 + §6.1 性能锁步（delta 不调 deriveProjections 防工具卡整列重渲）。施工卡须先实证渲染方案。

## 其余核验
- 六方案 vs demo：B1/呼吸常驻/无时间戳复制/工具卡（齿轮 #409EFF+plain tag+深绿实心 #67c23a/浅绿 #f0f9eb）/渐显（chIn .18s .1→1）均与 demo 一致 ✓
- isTyping 消费点：实测仅 1 处（:337 呼吸），改动面封闭（风险1 可提前消解，无需留施工卡 S0）
- 测试可断言性：5 项均具可断言信号 ✓；「300ms 淡出」jsdom 无 CSS 动画计时，建议 fake timers + class 断言而非真实 300ms
- 范围外：shimmer 不做 / aria-live+赞踩转挂账（规则⑥）合理 ✓
- 门禁：**265/265 实测成立**（21 spec；首跑 1 例 cold-env flaky 疑似超时、重跑即绿，属既有基建非卡缺陷）；ls-files 1103 ✓；后端零动 ✓

## DC-19 署名
模型 `DeepSeek/deepseek-v4-pro` · 会话 `session_f0e62484-c665-4ba8-b87b-8cfdf792d4ef` · wire `agents/main/wire.jsonl` profile.bind modelAlias 同左实证。
