> 归档来源：仓外正式报告（已脱敏）；归档日期：2026-10-19；本件为 2026-10-19 仓外文档补档批入库件，正文结论与数据未改。

# GF 边缘修复后 · 真实项目只读回填回归报告（final）

日期：2026-10-19
目标：`http://localhost:5202/tasks`（主会话已重起前端）
后端：`http://localhost:8081`（健康 200）
基线：`5274419`（工作树改动为被验对象，验收期间未改）
证据目录：`<SCRATCH>/_cu-gf-fix-r26/browser/final/`

## 0. 自证与环境

`profile.bind`（本执行体 wire 首条）：

```json
{"type":"profile.bind","agentId":"agent-1084","modelAlias":"DeepSeek/deepseek-flash","profileName":"coder","thinkingEffort":"high"}
```

- 前端 5202 / 后端 8081 均在验收开始时返回 200。
- 本次验收**未修改任何代码、docs、服务配置，未 commit**；`git status` 与开工时一致（仅既有被验改动 + `?? data/`）。
- 新会话：`<SESSION_ID_1>`（补充 Chromium，1280×800）。

## 1. 结论摘要

| # | 验收项 | 裁决 | 证据 |
|---|---|---|---|
| 1 | frame-first（结局帧先于 POST 应答）回填 | **PASS** | `final-regression.json`：只读卡 +81ms，POST 应答 +1269ms |
| 2 | 非默认值提交后不刷新回填 | **PASS** | `都不是，新建…` + `GF边缘回归：非默认` |
| 3 | 字段值与卡头摘要同值、不回落默认 | **PASS** | 摘要逐字一致；未回落 `导出全量配置` |
| 4 | 提交后字段真 `disabled` | **PASS** | `selectDisabled=true`、`inputDisabled=true` |
| 5 | 折叠 / 展开 | **PASS** | `block → none → block`，值保持 |
| 6 | 刷新后持久 | **PASS** | 刷新默认折叠，展开后值/摘要不变 |
| 7 | 取消不回填 | **PASS** | 摘要“未提交，填写内容不作记录”、`✗ 已取消`；默认值 + 空备注 |
| 8 | 只读视觉无变化 | **PASS** | 透明卡体 + 白底输入、`#E3E6EA` 边框、无只读标记；截图 03/05/08 |
| 9 | duplicate：重复 POST 409 | **PASS（网络级）** | `DUPLICATE_TOOL_CALL_ID`，UI 摘要/值不变，无重复卡 |
| 10 | duplicate：真实双窗 UI 提交 | **BLOCKED** | 两次尝试第二页签均未恢复待填写卡，未伪造 |
| 11 | 边缘修复未破坏主流程 | **PASS** | 上述 1–8 全部通过 |

**总体：PASS（主流程与 frame-first 边缘路径均通过；双窗 UI duplicate 为 BLOCKED）。**

## 2. frame-first（强制构造，非推测）

方法：Playwright 路由拦截 `POST /api/ai/frontend-tool-result`，先 `route.fetch()` 让后端处理（结局帧立即经 SSE 下发），再把 HTTP 应答**压住 1200ms** 后才 `fulfill`。这样页面必然先收到结局帧、后收到 POST 应答。

时间线（`final-regression.json`）：

| 事件 | wall (ms) | 本地时间 | 说明 |
|---|---|---|---|
| click 提交 | 1792395696486 | 15:41:36.486 | — |
| POST 到达后端 | 1792395696525 | .525 | 路由 `route.fetch()` |
| 后端已处理 | 1792395696554 | .554 | 结局帧已产生 |
| 只读卡出现（首次轮询） | 1792395696567 | .567 | **+81ms**，`postDelivered=0` |
| 稳定读取只读卡 | 1792395696583 | .583 | **+97ms** |
| POST 应答交付页面 | 1792395697755 | .755 | **+1269ms** |

首次出现即已是正确值，**没有**先渲染默认值再修正：

```json
{"roToggle":true,"selectValue":"都不是，新建…","selectDisabled":true,
 "inputValue":"GF边缘回归：非默认","inputDisabled":true,
 "roSummary":"验收动作 都不是，新建…，备注 GF边缘回归：非默认",
 "roBadge":"✓ 已提交","bodyDisplay":"block","postDelivered":0}
```

截图 `shots/03-frame-first-readonly.png` 摄于 POST 应答交付前：只读卡已显示正确值，底部仍为“工作中…”。

对应边缘修复①（frame-only 时同帧落值，并对他宿主 first-wins 重放）在本场景下工作正常；单宿主场景无法区分“重放”是否发生，多宿主浏览器级构造仍为 BLOCKED（见 §6）。

## 3. 主流程回归（非默认值）

GF：`GF 边缘回归验证`，`toolCallId=call_00_faQ00yWmxtArwugzBTNX1473`，run `d3771b83-9aca-4716-bd55-25d9c8c5f8ca`。

提交值：

```json
{"action":"都不是，新建…","note":"GF边缘回归：非默认"}
```

| 观测 | 值 |
|---|---|
| `roSummary` | `验收动作 都不是，新建…，备注 GF边缘回归：非默认` |
| `roBadge` | `✓ 已提交` |
| `selectValue` / `inputValue` | `都不是，新建…` / `GF边缘回归：非默认` |
| `selectDisabled` / `inputDisabled` | `true` / `true` |
| `roBodyBg` | `rgba(0, 0, 0, 0)`（透明，卡体白底） |
| `readonlyMarker` | `false` |
| `duplicateToggles` / `duplicateBodies` | `1` / `1` |
| 页面 pending 卡残留 | `0` |

- 折叠：`roBodyDisplay=none`，`selectValue` 保持非默认值。
- 再展开：`block`，摘要与值不变。
- 刷新后：默认折叠；展开后 `selectValue="都不是，新建…"`、`inputValue="GF边缘回归：非默认"`、两者仍 disabled，摘要不变。
- 截图：`01-pending.png`、`02-chosen.png`、`04-readonly-live.png`、`05-readonly-expanded.png`、`07-after-refresh-expanded.png`。

## 4. 取消回归

GF：`GF 边缘回归取消`，`toolCallId=call_00_lAsVHSyE99ySNoyBNsB60751`。
填写 `按范围导出` + `cancel-edge-不应回填` 后取消：

| 观测 | 值 |
|---|---|
| `roSummary` | `未提交，填写内容不作记录` |
| `roBadge` | `✗ 已取消` |
| `selectValue` | `导出全量配置`（默认） |
| `inputValue` | `""` |
| disabled | `true` / `true` |
| 重复卡 | `1` / `1` |

未回填取消前的 `按范围导出` / `cancel-edge-不应回填`。截图 `shots/08-cancel-readonly.png`。

## 5. duplicate（网络级 409）

主 GF 提交完成后，直接向同一 `runId/toolCallId` 发送不同值的重复 POST：

```json
{"action":"按范围导出","note":"dup-local-不应写回"}
```

响应：

```json
{"code":"DUPLICATE_TOOL_CALL_ID","message":"该 toolCallId 已有决策或结果，重复提交被拒绝",
 "runId":"d3771b83-9aca-4716-bd55-25d9c8c5f8ca",
 "toolCallId":"call_00_faQ00yWmxtArwugzBTNX1473","status":"FRONTEND_RESULT",
 "duplicate":true}
```

HTTP 409。随后 UI：

- 摘要仍为权威值 `验收动作 都不是，新建…，备注 GF边缘回归：非默认`；
- `selectValue` 仍为 `都不是，新建…`，未变成 `按范围导出`；
- 只读卡/表单体数量仍为 `1/1`，无新卡。

说明：该请求由页面 `fetch` 直接发出，**未经 store 的 `submitGenerativeForm` duplicate 分支**；因此这证明“重复回灌被后端拒绝、UI 未被污染”，不证明前端 duplicate 守卫本身。守卫仅有单测覆盖。

## 6. 真实双窗 duplicate（BLOCKED）

两次尝试在同一浏览器上下文开第二页签、复用同一 `sessionId`，让第二页签显示待填写 GF，再在 POST 应答被压住期间由第二页签提交：

1. 仅注入 `ai-session-id`，屏蔽 `GET /api/ai/events/**`：第二页签 15s 内未出现 `pending-card-<tcid>`（`duplicate-ui.json`，`pending2=null`）。
2. 额外复制 `ai-mirror:<sid>` 到第二页签（sessionStorage 全量除 `ai-tab-page-id`），仍屏蔽 SSE：第二页签仍未出现待填写卡（`duplicate-ui2.json`，`pending2=null`）。

结论：第二页签无法在不接收 SSE 的情况下恢复挂起表单；若放开 SSE，第一页签提交后结局帧会立即关闭第二页签表单，无法稳定制造“同一表单双窗提交”的窗口。**按约束标 BLOCKED，未用脚本调用 store 或改写响应来伪造。**

两次尝试产生的挂起 GF 已用 `POST /api/ai/frontend-tool-result` + `cancelled:true` 清理：

- run `799ce401-e426-4ec5-bf36-3f970a244aca` / `call_00_vlQJFUOEiiXcDnTHt1gj0217` → `accepted:true, status:FRONTEND_CANCELLED`；
- run `3be4e366-d06b-4b47-be17-72d34e0b4fe4` / `call_00_wWhDUm2EDRGttcVeArJ38587` → `accepted:true, status:FRONTEND_CANCELLED`；
- 复核 `runs/current`：第二个 `found:false`；第一个 `snapshotStatus:RUNNING`、条目 `FRONTEND_CANCELLED`（后端已唤醒收尾）。

## 7. 只读视觉

`final-regression.json` 计算样式：

- `roBodyBg: rgba(0,0,0,0)`（透明，卡体白底）；
- `selectBg: rgb(255,255,255)`、`selectBorder: rgb(227,230,234)`、`selectColor: rgb(87,96,106)`；
- `readonlyMarker: false`（无“只读”字样/新标记）；
- 截图中卡体为白色、禁用输入为浅灰边框文字，与上一轮形态一致。

## 8. 错误与异常

- 仅 1 条 console error：`Failed to load resource: 409 (Conflict)`，来自 §5 的故意重复 POST，预期内。
- 无 pageerror，无未捕获异常。

## 9. 业务副作用

| 会话 | 记录 | 处理 |
|---|---|---|
| `<SESSION_ID_1>` | GF `GF 边缘回归验证`（`call_00_faQ...`） | 已提交，保留 |
| 同上 | GF `GF 边缘回归取消`（`call_00_lAsV...`） | 用户取消，保留 |
| `<SESSION_ID_2>` | GF `GF 双窗重复提交验证`（`call_00_vlQ...`） | 已 cancelled 清理 |
| `<SESSION_ID_3>` | GF `GF 双窗重复提交验证2`（`call_00_wWh...`） | 已 cancelled 清理 |

- 未创建业务任务；任务中心列表与验收前一致（#354/#353 等未变化）。
- 验收脚本、JSON、截图仅写在 `final/` 下。

## 10. 证据索引

- `final-regression.json`：frame-first 时间线、主流程、折叠/刷新、cancel、409 duplicate。
- `duplicate-ui.json` / `duplicate-ui2.json`：双窗恢复失败记录（BLOCKED）。
- `final-regression.cjs` / `duplicate-ui.cjs` / `duplicate-ui2.cjs`：复现脚本（仅证据目录）。
- `shots/01-pending.png` … `08-cancel-readonly.png`（两次双窗尝试均因第二页签未出现待填写卡而未产生截图）。
- 上一轮基线证据仍在 `../`（`gf2-submit.json` 等）。

## 11. 边界与未覆盖

1. 双窗真实 UI duplicate：BLOCKED（第二页签无法在无 SSE 时恢复挂起表单）。
2. 同 `toolCallId` 多宿主/多段的 frame-only 重放：浏览器级未构造；单宿主路径已由 frame-first 实测覆盖。
3. frame-first 是**强制构造**（压住 POST 应答 1200ms），不是自然竞态采样；结论是“该路径可正确回填”，不是“自然发生率”。
4. 本轮未复测 120s 超时（上一轮内置浏览器已观测终态，见 `../builtin-observations.json`）。
