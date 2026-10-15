# r161 滚动 demo 验证报告

环境：18331 探针空 → 后台起 `python -m http.server 18331 --bind 127.0.0.1`，curl 200；tab panel-tab-509。读数取 readout 行真实 DOM 文本（page.text.snapshot），「浮钮=显」另经 visual 快照复核。
自证 profile.bind（首轮 wire，agent-848）：`modelAlias=DeepSeek/deepseek-flash · profileName=coder · thinkingEffort=high`

## 8 项逐项（修复后复验）

1. 现状·播放回复 **PASS**：文本段 `离底 0px｜跟随=true｜浮钮=隐`；完成 `618px｜跟随=false｜浮钮=显`（修复前 669px，差 51=AI 空行 42+T1 文本 9 ⇒ 文本段已钉底），视口止于确认门、表单被顶出。
2. 现状·上滚一点 20px **FAIL**（题面预期「显」不成立）：贴底 `20px｜跟随=true｜浮钮=隐`；滚到 40px 才 `浮钮=显` ⇒ 出钮闸实为 32px。
3. 修复·播放回复 **PASS**：`0px｜跟随=true｜浮钮=隐（阈值 209px）`，视口尾=生成式表单三字段。
4. 修复·上滚一点 20px **PASS**：`20px｜跟随=true｜浮钮=隐`。
5. 修复·上滚半屏 **PASS**：`271px｜跟随=false｜浮钮=显`（20+418×0.6）。
6. 修复·✉×2 **PASS**：`373px｜浮钮=显`，文案 `↓ 2 条新消息`。
7. 修复·点浮钮 **PASS**：回底 `0px｜跟随=true｜浮钮=隐`、pill 消失；回底后再于离底态注入 1 次 → `↓ 1 条新消息`（未清零应为 3）。
8. 修复·播放中途上滚半屏 **PASS**：`869px｜跟随=false｜浮钮=显`；document 快照证实确认门+表单在滚动之后插入、未强拉。

## 9（新增）回底后立即点播放 ⇒ 全程钉底 **PASS**

修复：贴底点播放全程 `0px｜跟随=true`，至确认门+表单插入仍钉底（同第 3 项场景）。现状：开场即钉底不再被误锁，确认门插入前 `0px｜跟随=true`；确认门后按现状口径停止（终值 618px，与第 1 项一致）。

## 修复说明（demo-scroll.html 最小改动）

根因坐实（打点副本 `_trace.html`）：`play()` 里 `pane.scrollTop = pane.scrollHeight` 之后约 3ms 派发的 scroll 事件中，`appendChild(row)` 已把 scrollHeight 顶高（1062→1104）⇒ 回调读到 `d=42 ≥ 32`，误置 `followBottom=false`，本轮钉底全被闸。**两模式同病**：修复前从干净贴底态点播放，修复模式同样 `d=42 follow true->false`；上轮第 3 项 PASS 属状态侥幸（当时 scrollTop 恰等于新 max，赋值为空操作故未派发事件）。

1. 新增 `pinBottom()`＝设 scrollTop＋记录落点 `pinGuardTop`＋`followBottom=true`；全文 8 处程序化钉底（play 开场、文本 delta×2、afterAppend、backToBottom、injectNew×2、初始化）统一走它；`pane.scrollTop = pane.scrollHeight` 现仅存在于 pinBottom 内。
2. `onScroll(e)` 加回声判定：原生 scroll 事件且 `scrollTop == pinGuardTop` ⇒ 只刷 readout、不动跟随闸；真实滚动到达才解 guard。手动调用（afterAppend/scrollUp/injectNew）不参与判定，原语义不变。
3. legend 补 ④「程序化钉底保护」。口径未变：现状工具卡插入仍不钉底（第 1 项 618px 即证）、阈值仍为现状 32px / 修复 209px。

核验：脚本 `node --check` 通过；打点副本 `_trace2.html` 实证回声被跳过（`+5540ms onScroll(scroll)[回声跳过] d=42 follow_before=true`，其后逐 tick `pinBottom d=0 follow=true`）。两份打点副本留同目录供复验。

## 出钮阈值改 64/24 滞回（用户直裁）· 复验（修复模式，readout 实证）

改动（demo-scroll.html，最小）：`showThreshold` 由 `Math.max(200, pane.clientHeight*0.5)` 改为常量 `SHOW_PX = 64`；修复模式隐藏闸由 `< FOLLOW_PX`(32) 改为 `< HIDE_PX`(24)；readout 括号随之显示「出钮阈值 64px」；legend ② 改为「浮钮显示 = 离底 >64px、<24px 隐藏（滞回，业界主流口径）」。现状模式口径不变（仍 ≥32px 出钮、无滞回）。

- ① 贴底播放全程钉底回归 **PASS**：`离底 0px｜跟随=true｜浮钮=隐（出钮阈值 64px）`，确认门+表单在视口尾。
- ② 上滚 20px **PASS**：`20px｜跟随=true｜浮钮=隐`。
- ③ 上滚到 70px **PASS**：`70px｜跟随=false｜浮钮=显`（>64 出钮）。
- ④ 滞回带 **PASS**：出钮后回滚到 `50px` 仍 `浮钮=显`（下探未及 64 不重现、未及 24 不隐藏）；再回滚到 `10px` → `浮钮=隐`（<24 隐藏）。实测链：`90px 显 → 50px 显 → 10px 隐`。

回滚用 page.visual.scroll 在窗格上发滚轮 deltaY（1:1 像素、约 1s 后生效，窗格无下滚按钮故借此实现）；`node --check` 复检通过。
