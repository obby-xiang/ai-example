> 归档来源：仓外正式报告（已脱敏）；归档日期：2026-10-19；本件为 2026-10-19 仓外文档补档批入库件，正文结论与数据未改。

# ai-example #3 · G（Feather help-circle）回落 + 全项回归（r26-G）

- 时间：2026-10-18 13:32–13:43（本地）
- 仓库：`<REPO_ROOT>`（只读参考；HEAD `1e6b972`，工作树含主会话 G 改动）
- 本轮**未改代码/demo/docs、未 commit/push、未起停服务**；新证据只写入本目录。
- 自证（本 agent wire `profile.bind`，`<SESSION_ID_1>/agents/agent-1051/wire.jsonl`）：`agentId=agent-1051`、`modelAlias=DeepSeek/deepseek-flash`、`profileName=coder`、`thinkingEffort=high`。
- 身份说明：agent-1071 为施工 agent，非本轮验证者。
- 工具：Kimi Code Desktop 内置浏览器 `mcp__desktop_browser__run`（主，项目页 + demo 页）；同构建 Chromium 辅助量测（次，`aux-g.json`，只取精确 DOM 几何/属性）。

## 0. 服务

| 探测 | 结果 |
| --- | --- |
| `http://localhost:5202/` | 200 |
| `http://localhost:18330/card-channels.html` | 200 |
| `http://localhost:5202/api/ai/health` | 200 |
| `http://localhost:8081/api/ai/health` | 200 |
| `http://localhost:18399/` | 200 |

项目内置页签 `panel-tab-492`（会话 `<SESSION_ID_2>`），demo 页签 `panel-tab-503`；本轮结束已 close/release。

## A. G 回落专项

| 断言 | 结果 | 实测 |
| --- | --- | --- |
| 项目 HITL 待决左图标 = G | **PASS** | 内置浏览器「启动导出作业（任务 #296）等待确认」左圆圈问号（r10 圆圈 + 官方问号钩 + 点），非盾牌；截图 `01`。辅助量测：SVG `15×15`、`stroke=#57606a`、`stroke-width=1.7`、`circle r=10`、`hasLine=true`、`hasShieldPath=false`、`path d=M9.09 9a3 3 0 0 1 5.83 1c0 2-3 3-3 3` |
| 项目 HITL 已拒绝 = 同一 G | **PASS** | 内置浏览器「✗ 已拒绝」左 G；截图 `02` |
| 项目 HITL 已批准 = 同一 G | **PASS** | 内置浏览器「✓ 已批准」左 G；截图 `03`（真实启动作业 #354） |
| 项目 HITL 已超时 = 同一 G | **PASS** | 240s 实时先「✗ 已拒绝」，reload 后「! 已超时」折叠；左 G；截图 `08` |
| 项目 G 中性、右状态唯一承载 | **PASS** | G 恒 `#57606a`；待决「等待确认」、已决状态均在右标签；卡头 `statusAfterSummary=true`；待决无 chevron（`chevron=null`、`aria-expanded=true`、header disabled） |
| demo v3 版本/审核态 | **PASS** | `<html data-card-channels="2026-10-18-v3" data-icon-variant="bare" data-review-status="approved">`；标题「demo v3（已通过）」；辅助量测 `version=2026-10-18-v3`、`review=approved` |
| demo 6 张 HITL 卡 bare 全为 G | **PASS** | 6 卡：`hitl-pending-normal`、`hitl-pending-high`、`hitl-approved`、`hitl-rejected`、`hitl-timeout`、`hitl-failed`；bare SVG 均 `15×15`、`#57606a`、`1.7`、`circle r=10`、`line=true`、G path；截图 `04`（2 待确认）、`05`（4 已决） |
| demo 6 张 HITL 卡 tile 全为 G | **PASS** | tile 外层 `20×20`、`#57606a`，内层 G；6/6 可见；bare 在 tile 下 `display:none`，tile 在 bare 下 `display:none`；截图 `06`（4 已决）、`07`（2 待确认） |
| demo tool 扳手 / GF 清单不变 | **PASS** | tool bare `15×15` 扳手 path `M14.7 6.3…`；GF bare `15×15` 清单 path `M16 4h2…`；tile 同样仅换槽 20×20，图形不变；截图 `04`～`07` |
| 旧盾牌证据未复用 | **PASS** | 本轮 12 张项目/demo 截图全部新拍；`hasShieldPath=false`；旧 r26 盾牌图未引用 |

## B. #3 全项回归

| # | 断言 | 结果 | 本轮实测 |
| --- | --- | --- | --- |
| 1 | 三类型判据 + 左类型裸 SVG | **PASS** | tool 扳手 / HITL G / GF 清单；项目三类型均 `15×15`、`#57606a`、`1.7`；HITL `circle r=10`、`line`、非盾牌 |
| 2 | 右状态唯一承载 + pending 文案 | **BLOCKED（仅普通 tool pending 子项）** | HITL「等待确认」、GF「待填写」实测；tool 已成功「已成功」；状态在标题/摘要之后、chevron 之前；普通 tool pending「待执行」未捕获（见 C） |
| 3 | 卡头约 36.4px | **PASS** | 项目 tool/HITL/GF 均 **36.39px**（400 面板）；360 同样 36.39px |
| 4 | GF 一卡一头 | **PASS** | 待填 GF 统一卡头「G 回归表单 / 1 项必填 / 待填写」，卡体直接进字段，无第二标题/状态；截图 `09` |
| 5 | 待填 GF 可编辑 | **PASS** | 内置浏览器填入 `G26-SUBMIT-001 / 提交轮备注` 并提交；辅助量测 input `disabled=false`、`value=G26-AUX` 回读 |
| 6 | 待决无 chevron（待决 HITL/GF） | **PASS** | 项目待决 HITL/GF header `chevron=null`、`aria-expanded=true`、disabled；已决卡均有 `›` 并默认折叠 |
| 7 | 360/400 无内部横向溢出；按钮防折行；限高内滚 | **PASS** | 400：panel `396/396`、header `350/350`；360：panel `356/356`、header `310/310`。GF 取消/提交 400 同 y=821.63、360 同 y=867.16，nowrap，`54/54`；HITL 拒绝 56px / 确认执行 78.41px，400 同 y=770.94、360 同 y=832.33，`54/54`、`76/76`。GF fields `auto; 320px`（310/310、270/270）；gate rows `auto; 280px`（306/306、266/266） |
| 8a | GF 取消空值 | **PASS** | 取消前填 `SECRET-G26-CANCEL`；取消后卡头「未提交，填写内容不作记录 ✗ 已取消 ›」；展开后两个 input `value=""`、disabled，卡内提交按钮 0；截图 `10`、`11` |
| 8b | 只读卡头可访问名 | **PASS** | 已拒绝 `启动导出作业 （任务 #296） ✗ 已拒绝 ›`；已批准 `… ✓ 已批准 ›`；已超时 `… ! 已超时 ›`；GF 已取消 `G 回归表单 未提交，填写内容不作记录 ✗ 已取消 ›`；GF 已提交 `G 回归表单-提交轮 编号 G26-SUBMIT-001，备注 提交轮备注 ✓ 已提交 ›`；`aria-label=null` |

## C. 普通 tool pending「待执行」尝试（仍 BLOCKED）

1. 发「请对任务 #296 启动导出作业」后，立即用浏览器端 `page.wait_for` 持续轮询「待执行」30s：超时，未命中（该调用直接进入 HITL 等待确认）。
2. 发「请下载任务 #296 的导出文件」后，再次 `page.wait_for` 轮询「待执行」30s：超时，未命中（工具在下一次观测前已完成，并触发下载 `export-296.zip`）。
3. 未使用 JS 注入/伪造 DOM/单测冒充浏览器 PASS；保留 **BLOCKED**。

## D. 既有挂账（只单列，不修，不计 G 回退）

- **HITL 实时超时先显示「✗ 已拒绝」**：240s 到点后 live 卡为已拒绝，reload 后才「! 已超时」；本轮再次复现。
- 2A 三缺陷未触碰；tool failed 跨刷新本轮未构造，未计。
- 副作用：为取得「已批准」真实启动作业 **#354**（任务 #296，随后 1600/1600 完成）；下载动作保存 `<USER_DOWNLOADS>\export-296.zip`；辅助量测将 #295 向导推进到导出执行并拒绝确认门（未启动作业）。

## E. 结论

- **G 回落成功**：项目 HITL 待决/已拒绝/已批准/已超时均为同一 G；非盾牌。
- **demo v3 通过**：6 张 HITL 卡 bare/tile 均为 G，版本/审核态 `v3/approved`；tool 扳手、GF 清单不变。
- **#3 主项无 FAIL**；仅普通 tool pending 子项 **BLOCKED**。
- 证据目录：`<SCRATCH>/_cu-hitl-g-r26/browser/`

## F. 证据文件

- 项目截图：`shots/01-project-hitl-pending-G.png`、`02-...rejected-G`、`03-...approved-G`、`08-...expired-G`、`09-project-gf-pending`、`10-project-gf-cancelled-collapsed`、`11-project-gf-cancelled-expanded-empty`、`12-project-gf-submitted-collapsed`
- demo 截图：`shots/04-demo-bare-hitl-pending-2cards-G.png`、`05-demo-bare-hitl-decided-4cards-G`、`06-demo-tile-hitl-decided-4cards-G`、`07-demo-tile-hitl-pending-2cards-G`
- 360/400 截图：`shots/aux-project-gf-400.png`、`aux-project-gf-360.png`、`aux-project-hitl-400.png`、`aux-project-hitl-360.png`
- 量测：`aux-g.json`（demo 6 卡 bare/tile 属性与显隐、项目 G/GF/HITL 几何、a11y、按钮、限高内滚）；脚本 `aux-g.mjs`；日志 `aux-g.out` / `aux-g.err`
- 自证：`profile-bind.txt`
