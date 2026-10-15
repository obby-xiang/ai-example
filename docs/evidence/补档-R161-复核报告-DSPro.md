# 复核报告-R161-DSPro（滚动专项 · 三点核对）

DC-19：modelAlias=`DeepSeek/deepseek-v4-pro`；会话 `session_20f51a93-4eab-4478-addb-9fc600c60e3d`（wire 实证）。

## ① 八项落点 —— 通过
diff+实读核验：pinBottom 统一 / 回声闸≤1px / RO 重钉底+撤 setTimeout hack / 阈值 64-24 滞回 / 平滑回底（.scroll-smooth，未用 scrollTo smooth）/ role="log" / reduced-motion 豁免（.caret 保留）八项全部落位，与卡面一致。
注：§一 行号漂移——pinBottom 书:583 实:882、onMsgScroll 书:547 实:520、reduced-motion 书:1364-1369 实:1344 超 EOF。引用性缺陷，建议订正，不改判定。

## ② M1 留证 —— 通过（可信）
m1-harness 与 demo 滚动逻辑逐字一致（diff 仅 fix 默认+_trace+insertAbove）；trace 字段与证据 JSON 吻合；四场景自洽（A dist=60 被闸吞、B2 Δ300 判真滚、B1 dist 不变不漏判、0 locks）。成立。
保留：WebBridge 实驱系自述（serve.log 止 18:07，无独立 wire），不影响。

## ③ 门禁 —— 通过（实跑）
279/279(21 文件) · typecheck 0 · build ✓ · check-docs 6/6 · ls-files 1103 · 2 M+?? data/ · HEAD=4a08058 零 commit，与报告一致。

## 偏差四条 —— 全可接受
基线 265→271（R-159 先入库）；pinBottom 同步直写+RO 兜底（决策卡 §一.1 推论）；≤1px vs demo<1（卡面明文）；scrollBottom 留 force 壳（指纹 watch 需守卫）。

## 结论
三项全过 → **转通过**（行号待订正）。
