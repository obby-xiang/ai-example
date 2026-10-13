> 入库注记（2026-10-13）：来源落点 = 仓外工作目录 <SCRATCH>\r157-ux2\复核确认-R157-DSPro.md（984 B）；源件 sha256 = `01826a8e40be797f57f487ee4aeb19c56dce9d123816f2b1a22f7f3bad3d4829`；入库批次 = R-157 入库批（2026-10-13，单笔提交；**审查锚 = 入库锚** = `r157-review.diff` sha256 `95d53f47d0b36f39cb898550e44fd9a155f3f3576985e63c1350d2a29fabeab1`）；本件除本注记行外与源件逐字节一致（零改写）。
# 复核确认 R-157 · DSPro

**结论：通过**（MAJOR-1 真修，MINOR-1/2/3 销记）

## 三点核对
- ① MAJOR-1 真修：ai.ts:1041-1066 `send(text,resend:{skipUserRecord?})`；编辑=AiPanel.vue `target.parts` 原地重写+`deriveProjections`+截断+`ai.send(text,{skipUserRecord:true})`；重试=截断+skipUserRecord 重跑。改后断言 `['u-1',/^a-/]`+`content==='改写后的提问'`，独立复跑 253/253 绿。
- ② MINOR 销记：1/2 报告精度校准（numstat 341/69、改写 3 例）；3 操作行 `justify-between`+首末子断言（spec:738-740），视觉两端分置建议 CU 目验。
- ③ 判级转「通过」：无残留 MAJOR。

## 门禁（独立复跑）
spec 253/253·20、typecheck 0、ls-files 1088 均 ✓；build 未独立重跑（typecheck+vitest 覆盖）；HEAD 仍 22d919a；R-155 链/后端零动。

## DC-19（续轮引用 R-155 实测）
模型 `DeepSeek/deepseek-v4-pro`；会话 `session_f1716b4c-44fe-454a-b0e4-9ed0bf02cf02`。
