> 入库注记（2026-10-13）：来源落点 = 仓外工作目录 <SCRATCH>\r158-ux3\复核确认-R158-DSPro.md（1010 B）；源件 sha256 = `c8a6f686df73abaa02fdc85f0b9cd5f2aea89d8c571f3ccd344abb55902d8734`；入库批次 = R-158 入库批（2026-10-13，单笔提交）；本确认为**收口后**终态确认，审查对象 = 收口修后工作树 ⇒ **审查锚 = 入库锚** = `r158-review.diff` sha256 `74242001e325eccca25b5d49b47a306a52f72e98fc5404c5d84992f79176d11f`；本件除本注记行外与源件逐字节一致（零改写）。
# 复核确认 R-158 · DSPro（收口后）

**结论：通过。** MINOR-1 真修、原四项维持、门禁全绿、纪律零动。

## 三点核对
1. **MINOR-1 真修**：`AiPanel.vue:802` hitlReceiptLabel decision 优先——approved 即显「已确认」不等 succeeded、rejected→「已拒绝」，缺省回退 status。spec:899-930 锁 4 断言决策态。✓
2. **四项维持**：#10 三字段（types:273/316）+写回+历史 spread 保留；#11 deriveCopyText（parts:501）join('\n\n')、deriveProjections 零动；#12 RO 真溢出才渐隐；重试去除（retryMessage 零命中）、skipUserRecord 保留（ai:1046）。✓
3. **判级转通过**。

## 门禁（独立复跑）
vitest 259/259·20 ✓；typecheck 0 ✓；build ✓；ls-files 1093（CollapsibleText+1 未 add）。HEAD 28cfc22 零 commit、后端零动、零新依赖。

## DC-19
模型 DeepSeek/deepseek-v4-pro =DSPro · 会话 session_f0e62484-c665-4ba8-b87b-8cfdf792d4ef · wire main profile.bind modelAlias 同左。
