# 复核报告-R160-DSPro（HITL 三缺陷 · 三点核对）

DC-19：`DeepSeek/deepseek-v4-pro`；会话 `session_20f51a93-4eab-4478-addb-9fc600c60e3d`（wire 70 条实证）。

## ① 五项落点 —— 通过
M1 签名改传 pending、5 点全改；decision 条目态派生（T2 升格不抹）、decidedAt←resolvedAtMs、submittedValues←resultText 限 FRONTEND。M2 loadHistory 回读 + receipt 独立块（id=${msg}:receipt:${toolCallId} 恒可见、不参与 expandedBlocks、判据上移 blocks.ts）。M3 expiresAt 先于 saveSuspended set（顺序红线）+ runsCurrent 透出 + 快照续走 + 旧数据静态兜底。M4 两行 CSS。M5 删 RO 单一 measure。全落位。

## ② 门禁 —— 通过（实跑）
前端 288/288(21 文件)·typecheck 0·build ✓·check-docs 6/6·ls-files 1103·HEAD=91654a2 零 commit；后端 496/496（surefire 77 报告求和 496、0 失败，+4 例经 diff 证实）。
注：工作树实 13 M 文件（后端 4+2 / 前端 5+2），报告书「12」与自身分解 4+2+5+2=13 不符；types/ai.ts 属 M2/M3 类型定义、非超卡面，系计数笔误建议订正。

## ③ 偏差四条 —— 全可接受
注高法替 RO 桩；parse 兜底 undefined（falsy 等价）；回执位移出卡体（定稿口径 testid 不变）；isHitlReceiptPart 删（判据上移本体不变）。

## 结论
三项全过 → **转通过**（附计数订正建议）。
