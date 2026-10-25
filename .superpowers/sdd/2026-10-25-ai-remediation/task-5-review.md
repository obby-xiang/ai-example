# Task 5 独立审查：前端多卡、恢复与 DOM 状态（FE-22 / FE-17 / FE-32 / FE-33 / FE-25+PK-12）

- 审查对象：`task-5-brief.md`、`task-5-report.md`、`task-5.patch`（10 文件：5 生产 + 5 测试）
- 审查方式：**只读**。未改主仓、未改交付文件、未起服务。为验证"补丁 = 被跑过的代码"，把 `task-5.patch` 应用到 `git ls-files frontend/src frontend/tests` 的干净副本，再与隔离副本逐字节比对；另写 3 个一次性探针 spec 证明两处状态机漏洞（跑完即删，副本 `tests/` 已复原）。
- 复跑范围（按控制器口径，不重跑宽套件）：**新增 20 例** + 被改写的 `ai-region-batch-b.spec.ts`（16 例）+ `vue-tsc --noEmit`。
- 文中 `path:line` 一律指**打补丁后**的文件（`<隔离副本>/_verify-20261025-chat-state/frontend`，已证明与「main `34fd8f4` + patch」逐字节一致）。

---

## 0 结论

| 维度 | 判定 | 说明 |
|---|---|---|
| **Spec Compliance** | **PASS（2 处 Important 缺口：I-1 / I-2）** | 四条缺陷都有 RED→GREEN；但"多卡不丢续跑"只在**响应先于结局帧**这一种时序下成立（I-1），另一种合法时序下仍会丢卡、丢重连、并解锁输入区；`error` 终态仍会卡死输入区（I-2） |
| **Task Quality** | **PASS（2 Important / 10 Minor，无 Critical）** | 改动克制、注释到位、两层缓存分别修复、码表闭集处理正确、测试非自我实现；问题集中在"补丁自己重写过的两段"里的残留判据与两处测试降级 |
| **合并建议** | **有条件合并（Approve with required fix）** | 先修 I-1（`hasOtherPendingCards` 计入 `restoredPendingQueue`，一行）与 I-2（`error` 分支撑清 `suspended`，一行），两者都可各配一条 5 行用例；其余 Minor 可随后续批 |

---

## 1 需求对照

| Brief 要求 | 实现位置 | 证据 | 判定 |
|---|---|---|---|
| 多卡 / 混合卡恢复不丢续跑 | `ai.ts:3066-3074`（`hasOtherPendingCards`）、`:3081-3085`、`:5211-5213` | `ai-multi-card-mixed-resume.spec.ts:111-135`（混合卡）、`:137-160`（纯 CONFIRM 三卡回归锁） | ⚠️ 成立，但见 **I-1** |
| 409 有界可恢复，不永久卡死 | `ai.ts:180`、`:2987-2994`、`:3038-3052` | `ai-multi-card-mixed-resume.spec.ts:180-202`、`:204-226`；探针 C 证明 `error` 分支仍会卡死（I-2） | ⚠️ 提交锁已可恢复；终态残留一处未收（I-2）、一处窄竞态（M-4） |
| 隐藏步骤不披露 / 不可写 | `ai-dom-tools.ts:482-523`（判据）、`:672-674`（快照过滤）、`:1144-1149`（第⑦道闸）、`:1050-1062`（回执） | `ai-dom-visibility.spec.ts` 7 例；`ai-region-batch-b.spec.ts:171-190`（真视图不披露） | ✅（残留：文本段判据更浅、`opacity` 类隐藏未覆盖 —— M-5/M-6） |
| 动作后同参快照必须新鲜（两层） | 层①`ai-dom-tools.ts:130-141,632-633`；层②`frontend-tools.ts:641` | `ai-snapshot-freshness.spec.ts` 4 例（含"无写动作仍去重"回归锁） | ✅（非 ui 写动作仍未纳入 —— M-7） |
| 终态表单/上传/预览清理 + 交接放弃身份安全 | `ai.ts:3466-3473`、`:5276-5282`、`:5310-5328`、`:3758-3766`；`AiPanel.vue:652` | `ai-round-terminal-residue.spec.ts:103-175`、`:178-206`（含 park 不破、活动卡放弃照常回灌） | ✅（`error` 分支未解 `suspended` —— I-2） |
| `parts/workspace/task` 刻意不改 | 未改 | 段侧残留由 `expireResidueToolParts`（`ai.ts:811-823`）收口，本次缺陷确实在单例层 | ✅ 同意该判断 |
| `ui-receipt.ts` 增码（越出 brief 清单） | `ui-receipt.ts:26-28` | 后端只做 `^UI_[A-Z_]{2,40}$` 正则（`FrontendToolResultRequest.java:30`），无需后端改动；`makeExecution` 会丢未登记码（`frontend-tools.ts:160-163`） | ✅ 必要，接受 |

---

## 2 关键判定（逐条回答控制器点名的问题）

### ① `GATE_SYNC_MAX_ATTEMPTS=3` 之后解锁：**不会隐藏活跃轮，也不会让用户另起一轮；可恢复路径成立**

- 上限与节奏（`ai.ts:2987-2994`）：`3s → 6s → 12s`，最多 3 次 `GET /runs/current`，用尽后 `clearGateSubmission`（`ai.ts:3050`，只 `disarmGateSync` + 删提交态，**不动 `suspended`**）。
- 为什么不会"另起一轮"：输入区硬禁用 = `health 不可用 || suspended || pendingToolCall`（`AiPanel.vue:1580-1586`）。409 走 `markGateSyncing`（`ai.ts:2953-2979`）**把段写回 `pending` + `requiresAction=true`**，解锁只清 `gateSubmissions`；因此解锁后 `pendingToolCall` 仍非空、`suspended` 仍为真（由 `confirm_request`/`suspended` 帧置位，`ai.ts:5126`、`:5158`、`:5238`），**输入区始终锁着**。用户唯一能做的仍是那两张按钮。
- 恢复路径：重试是同 `toolCallId` 的幂等 409（不会把已批准的翻成拒绝），刷新走 `restoreSuspendedSnapshot` 读权威（`ai.ts:2116-2197`）。报告 §3 的结论成立。
- **但**：给出的"解锁"提示复用 `ai.notice.decisionPending`（"确认已提交，但暂未同步到后端真实裁决（可稍后刷新查看）"），解锁后没有一句"现在可以再试一次" —— 属文案缺口（M-3，报告 §8-④ 已自陈）。
- **另一个残留**（M-4，探针 B）：若结局帧与 `done` 先到、本页签自己的 POST 后拿到 409（多页签同会话裁决的合法时序），`markGateSyncing` 会把已 `expired` 的段**复活成 `pending`**（它没有"该轮已终态 / 段已决"守卫）→ 收口轮里出现一张僵尸卡且 `pendingToolCall != null` ⇒ 输入区被永久禁用，直到刷新。探针实测：`{partStatus:'pending', requiresAction:true, pendingToolCall:true, suspended:false, gateSubmission:true, lastMessageDone:true}`。这是**既有**缺陷、非本次引入，但它与 FE-17 的"不永久卡死"目标同族，建议一并加守卫（`hasDecidedPendingResidue(toolCallId) === true` 时不进 `markGateSyncing`）。

### ② `hasOtherPendingCards` 对 restored / mixed 卡的判据：**方向正确，但漏掉了"还在恢复队列里、尚未建段"的卡（I-1）**

- 判据本身（`ai.ts:3066-3074`）只看**段**：`kind==='tool'` + `requiresAction===true` + `status==='pending'` + `kind2 ∈ {CONFIRM, FRONTEND}`，且排除本次 `toolCallId`。我核对了所有 `requiresAction: true` 的生产点（`ai.ts:2335/2976/3234/3550/3660/3837`、`parts.ts:657/732/833`），全部是真正"等人"的卡；BACKEND 条目确实从不入此判据 ⇒ 不会把后端工具误算成人围栏。
- restored 卡由 `rebuildPendingCard`（`ai.ts:2312-2390`）落成 `status:'pending'` + `requiresAction:true`，与判据吻合；混合卡用例（CONFIRM 先出、GF 在队列）证明串行补卡路径工作。
- **缺口**：`restoreSuspendedSnapshot` 把除第一张以外的卡放进 `restoredPendingQueue`（`ai.ts:2172-2174`），在 `revealNextRestoredPendingCard` 之前**它们没有段**，判据看不见它们。于是当 `confirm_decision` 帧**先于** confirm POST 的响应抵达时：
  - `confirm_decision` handler → `clearSuspendedIfNoOtherConfirms('c1')`（`ai.ts:5144`）看到"没有别的段" ⇒ `suspended=false`；
  - POST 返回后 `reconnectSuspendedRun` 因 `!suspended` 早退（`ai.ts:2503-2505`）、`revealNextRestoredPendingCard` 同样早退（`ai.ts:2400-2402`）⇒ **不重连、下一张永不出现**；
  - 此时 `suspended=false` 且 `pendingToolCall=null` ⇒ 输入区解锁，而后端这一轮仍 SUSPENDED ⇒ 用户下一条消息撞 409 —— 正是报告 §2 描述的 FE-22 机制。
  - 探针 A（两卡 restored 队列 + 在 `api.confirm` 内先注入 `confirm_decision` 再返回）实测：`{reattach:0, suspended:false, card2:false, queueLen:1, pendingToolCall:false, activeRunId:'run-1'}`。
- 修法（一行 + 一例）：`hasOtherPendingCards` 追加 `|| this.restoredPendingQueue.length > 0`（队列里只剩已决条目时 `revealNext` 会先清空，`ai.ts:2409-2412`，不会造成永久锁）；用例即在 mock 的 `api.confirm` 里先 `handleFrame(confirm_decision)` 再 resolve。
- 为什么这条重要：`req=true` 的第二张卡其请求帧**已经发过**（归档里），决策后不会重发；`restoredPendingQueue` 是它唯一的下发通道，所以这条竞态不是"晚一点出现"，而是"永远不出现"。

### ③ 终态清理是否跨轮安全、是否完整：**跨轮安全；`error` 分支漏 `suspended`（I-2）**

- 跨轮安全：`handleFrame` 有 `ownedByActiveRound` 守卫（`ai.ts:5038-5048`），旧轮的 `done`/`error` 只封口自己那条消息，不会清掉新轮的单例。`cleanupTerminalInteractionState`（`ai.ts:3466-3473`）只在当前轮终帧调用；`stop()`（`ai.ts:4472-4505`）另行做等价收口（含 `cancelRoundParts`）。`restoredPendingQueue` 在 `send`/`reattachActive`/`stop`/终态四处都会作废，未发现跨轮泄漏。
- 完整性：表单（含草稿镜像）、上传单例、预览计划 + 失效标记、恢复队列全部收口；`notifyAiUploadDone` / `confirmWritePreview` 在收口后确为零回灌（测试锁定）。`dropSheetPreview('stop')` 只清预览色与计划，不动 `sheetApplied`（已应用摘要）—— 正确。
- **缺口（I-2）**：`done` 分支显式 `this.suspended = false`（`ai.ts:5282`），`error` 分支没有（`ai.ts:5310-5328`，只 `expireResidueToolParts` + 新的 `cleanupTerminalInteractionState` + `clearGateSubmissions`）。探针 C（`confirm_request` → `error`）实测：`{suspended:true, pendingToolCall:false, loading:false}` ⇒ `composerHardDisabled=true`，屏上无任何可点的卡，输入框占位还是"正在等待你的确认…"，**只能刷新**。这是既有缺陷，但补丁正好重写了这个分支且目标就是"终态不卡死"，建议顺手补 `this.suspended = false`（与 done 同口径）。

### ④ 可见性判据的兜底语义：**判据集合合理、保守方向正确，但两处语义被夸大**

- 采用 `isConnected` + 祖先链（含 open shadow 宿主）的 `hidden` / `inert` / `aria-hidden="true"` / inline 与 computed 的 `display:none`、`visibility:hidden|collapse`，再叠一层 `checkVisibility()`（`ai-dom-tools.ts:455-523`）。不按类名猜、不用几何判据，方向正确（jsdom 无布局，几何判据会把整屏判隐藏）。
- 快照侧：隐藏元素不进正文行与 `total`，但 `ref` 仍进 `seenRefs`（`ai-dom-tools.ts:651-674`）—— 归因从"未快照"改成"未显示"，与 deny 的既有口径一致，测试 `ai-dom-visibility.spec.ts:78-108` 正好钉住这个细节。
- 写动词侧：第⑦道闸只拦 `WRITE_VERBS`（`ai-dom-tools.ts:1144-1149`），`scroll/highlight` 不拦；回执三段式 + `UI_TARGET_HIDDEN`（`ai-dom-tools.ts:1050-1062`）与 `UI_TARGET_DISABLED` 分开归因，下一步是"切步骤"而不是"再点一次"，正确。
- **语义夸大之一**：`aria-hidden="true"` 是**无障碍树**语义，不是视觉可见性 —— 一个视觉可见的控件若祖先带 `aria-hidden` 会被拒执（与"看得见才可操作"相反）。本仓 `aria-hidden` 只出现在 6 个文件：AiPanel / FormRenderer / UploadHandoffCard 的图标、必填星号、tip 箭头与 markdown 图标（均为装饰性叶子），故**现实风险低**，但注释里"判据按能不能看见取最保守的交集"应改为"取可见性与可达性的交集"，并补一句"aria-hidden 祖先按不可操作处理"。
- **语义夸大之二**：`opacity:0`、`sr-only`/`clip`、`position` 移出视口、closed `<details>` 等常见隐藏手法未覆盖（`checkVisibility()` 无参调用也不校验 opacity）。即"隐藏 ⇒ 拒绝"是**必要不充分**：漏判的隐藏控件仍会被点/填（回执仍会写"界面已同步"）。本仓两个向导用 `v-show` + `el-collapse`（`display:none`），已覆盖，属可接受的收窄，但应在注释/报告里写明边界。
- 另：`ui_snapshot({text:true})` 的文本段（`ai-dom-tools.ts:692`、`:746-779`）走的是**另一套更浅的判据** `isTextSkipped`（`:839-852`：只看元素**自身**的 inline `display/visibility`、`hidden`、`aria-hidden`，不看祖先 chain、不看 computed）。两个向导的 `v-show` 是 inline `display:none`，所以本场景实际不漏；但"类名/祖先 computed 隐藏"的文案仍会被披露，与控件行的新判据**口径不一致**（M-5，建议统一复用 `isElementVisibleForAi`）。

### ⑤ DOM 版本跨轮 / 跨页签的作用域：**方向安全，但只覆盖 ui 写动作**

- `domVersion` 模块级（`ai-dom-tools.ts:130-141`），`markUiMutation` 只在**真实写入成功**处推进（`aiClick` `:1412`、`aiFill` `:1520`、`aiSelect` `:1622`、`applyUiUndo` `:1739/1753/1762`）+ 换轮 `beginUiTurn`（`:157-167`）+ 复位 `:228`；只读动作不推进（"无写动作仍去重"回归锁 `ai-snapshot-freshness.spec.ts:95-103`）。
- 两层都被覆盖：层①缓存键 `epoch|domVersion|args`（`:632-633`），层②去重作用域 `runId|epoch|domVersion`（`frontend-tools.ts:641`）。跨页签天然隔离（每个 JS 上下文一份模块状态）；跨轮由 `beginUiTurn` 换 token 推进（唯一生产调用点 `ai.ts:5580`，runId 即 token）。
- 作用域"多失效"方向是安全的：任何写动作只会让**更多**调用重新遍历，不会让旧画面被当成新画面；`rememberSameCall` 还有"执行期间现场已换过则不记账"的守卫（`frontend-tools.ts:666-676`）。
- 残留（M-7）：**非 ui 写动作**不推进版本 —— `set_import_mode` / `go_to_step` / `select_definitions` 等 workspace action 只 `touchWorkspace`（数据版本，`ai-dom-tools.ts:299-305`），不推进 `epoch` 也不推进 `domVersion`（`bumpAiEpoch` 在生产代码里零调用）。于是"AI 改模式/切步骤后再同参 `ui_snapshot`"仍会被层②去重成动作前文本。这是与 FE-33 同类的洞，只是不在 `ui_*` 动词面上；建议在 `runPageHandler`/`runWorkspaceAction` 成功后统一调一次 `bumpAiEpoch()`（比逐个动作加 `markUiMutation` 更省）。
- 另一个已知边界（M-11）：页面自身变化（用户手填、数据刷新）在同一轮内仍返回缓存快照；`ai-snapshot-freshness.spec.ts:57-70` 是用 `beginUiTurn` 模拟跨轮来证明"换轮失效"，也就是说"同轮页面自变"仍不新鲜。与 FE-33 的原始口径（AI 自己的动作）不冲突，但要写清。

### ⑥ `UI_CODES` 注册：**必要且正确**

- `ui-receipt.ts:26-28` 增码；`makeExecution` 的 `sanitizeUiCode`（`frontend-tools.ts:160-163`）确实会静默丢弃未登记码，不加就会让 `UI_TARGET_HIDDEN` 在回灌体里消失（本项目反复要修的失败模式）。后端只做 `^UI_[A-Z_]{2,40}$`（`FrontendToolResultRequest.java:30`），`UI_TARGET_HIDDEN` 合规，无需后端改动。`ai-dom-visibility.spec.ts:122-135` 走 `executeFrontendTool` 端到端验证了"码穿过执行器边界仍保留"，比只测 `UI_CODES.includes` 更强。同意报告 §8-① 的处理，**接受越界**。

### ⑦ `ai-region-batch-b` 的期望改写：**3 处保住原意，1 处悄悄降级**

- B-01 首条（`spec:171-190`）：改为"区域仍注册 + 标注仍在 DOM（`querySelector` 非空）+ 第 1 步快照不得披露第 4 步 `import.mode.merge` + 尾行总数一致"。这是**正确的**改写：它同时锁住了"注册面没退化"与"隐藏不披露"，而不是把缺陷写成期望。
- 风险档位 / 后果性动作（`spec:196-208`、`spec:350-359`）：站到 `PUBLISH` 再断言，保留"风险分流是真闸（`set_import_mode` 指路 + 零写入）"的原意，`selectDefs.risk` 读的是注册表元数据、不依赖快照，合理。
- COND / 导出第 2 步 12 控件（`spec:243-247`、`spec:283-297`）：站到 `QUERY_COND`，基线数字与断言逐条未变，正确。
- **降级（M-1）**：B-08「尺寸闸 —— 最复杂的导出 COND 快照 ≤2000 字符且尾行恒在」（`spec:314-322`）**没有随之站到 `QUERY_COND`**，仍用 `EXPORT_TASK`（`SELECT_DEFS`）；可见性闸生效后第 2 步整体隐藏，这条用例现在量的是**第 1 步的简单快照**，与用例名/意图不符，2000 字符上限在"最复杂快照"上的回归保护实际丢了。建议同一行加 `currentStep: 'QUERY_COND'`。（合成快照的 2000 上限仍有 `ai-snapshot.spec.ts` 与 `ai-perception-batch-c.spec.ts` 兜底，故列 Minor。）
- 另两处陈旧：`spec:372-373`、`spec:388` 注释仍写"快照按 epoch 缓存…必须先 `bumpAiEpoch()`"，而 `aiClick`/`aiFill` 现在会 `markUiMutation` 自动失效；`bumpAiEpoch()` 已多余（留着无害），但注释指向的是旧行为（M-2）。

---

## 3 问题清单

### Critical
无。

### Important

**I-1｜混合/多卡恢复：结局帧先于 POST 响应时仍会丢卡 + 丢重连 + 解锁输入区**
`frontend/src/stores/ai.ts:3066-3074`（`hasOtherPendingCards` 不看 `restoredPendingQueue`）、`:3081-3085`、`:2400-2402`、`:2503-2505`、`:5144`
- 触发：restored 串行队列（第二张 `req=true`，请求帧已在归档）时，`confirm_decision` 帧比 confirm POST 的响应先到。
- 后果：`suspended=false` → 不重连、第二张永不出现、`activeRunId` 仍在 ⇒ 用户下一条消息撞 409（FE-22 原机制以另一条路径复发）。
- 证据：一次性探针（已删）实测 `reattach:0, suspended:false, card2:false, queueLen:1`。
- 修法：`hasOtherPendingCards` 追加 `|| this.restoredPendingQueue.length > 0`；补一条"在 mock `api.confirm` 里先注帧再 resolve"的用例。
- 备注：这是**时序**问题，真实 SSE 下两种顺序都可能（浏览器端无法假定），故必须在状态机层消除，而不是靠"实践中很少见"。

**I-2｜`error` 终态不清 `suspended`：输入区永久禁用且屏上无可点的卡**
`frontend/src/stores/ai.ts:5310-5328`（对照 `:5282` 的 done 分支）
- 触发：挂起中（有 `confirm_request` 或 409 同步中）收到 `error`。
- 后果：`expireResidueToolParts` 收掉 `pendingToolCall`，但没有卡、`suspended` 恒真 ⇒ `composerHardDisabled` 恒真、占位文案仍是"正在等待你的确认…"，只能刷新。
- 证据：探针 C 实测 `{suspended:true, pendingToolCall:false, loading:false}`。
- 修法：`error` 分支与 `done` 同口径加 `this.suspended = false`（终态即本轮结束，安全）；补一条与 `done` 对称的用例。

### Minor

- **M-1**｜`ai-region-batch-b.spec.ts:314-322`：B-08 未随可见性语义站到 `QUERY_COND`，"最复杂快照 ≤2000"这条覆盖实质降级。建议补 `currentStep: 'QUERY_COND'`。
- **M-2**｜`ai-region-batch-b.spec.ts:372-373, 388`：注释仍称"必须 `bumpAiEpoch` 才能看新画面"，FE-33 后写动作已自动失效（调用留着无害，注释误导后续维护）。
- **M-3**｜`ai.ts:165-171`：`GATE_SYNC_GRACE_MS` 的文档仍写"就拉**一次** … 再等只是让用户多看一会儿假态"，与新的 3/6/12s 有界重试设计矛盾；`ai.ts:3051` 用尽后复用 `decisionPending` 文案（"确认已提交…可稍后刷新查看"），解锁后没有"可重试"一句（需新 i18n 键，`locales` 不在清单——报告 §8-④ 已自陈，可留后续批）。
- **M-4**｜`ai.ts:2953-2979`（`markGateSyncing`）+ `:3213-3221`：409 在 `done` 之后抵达会把已 `expired` 的段复活成 `pending`+`requiresAction`（多页签同会话裁决的合法时序），形成"收口轮里的僵尸卡 + 输入区永久禁用"。建议 409 分支先判 `hasDecidedPendingResidue(toolCallId)` 再决定是否 `markGateSyncing`。
- **M-5**｜`ai-dom-tools.ts:746-779` + `:839-852`：`text=true` 的文本段走 `isTextSkipped`（只看自身 inline style / `hidden` / `aria-hidden`），**比控件行的 `isElementVisibleForAi` 浅**（不看祖先链、不看 computed）⇒ 用类名或祖先 computed 隐藏的文案仍会被披露；建议让 `walkRegionText` 复用 `isElementVisibleForAi` 统一口径（本仓 `v-show` 场景已覆盖，属口径不一致而非当前泄漏）。
- **M-6**｜`ai-dom-tools.ts:482-523`：`opacity:0` / `sr-only` / 移出视口 / closed `<details>` 未覆盖（漏判即仍会点）；`aria-hidden` 属 AT 语义（会误拒视觉可见的控件）。建议把注释口径改成"可见性 ∩ 可达性"，并登记已知不覆盖的隐藏手法。
- **M-7**｜`frontend-tools.ts:641` + `ai-dom-tools.ts:130-141`：非 ui 写动作（`set_import_mode` / `go_to_step` / `select_definitions` / workspace action）不推进 `epoch` 也不推进 `domVersion` ⇒ 其后同参 `ui_snapshot` 仍可能返回动作前文本（FE-33 同类）；`bumpAiEpoch` 在生产代码零调用。建议在动作成功后统一推进一次。
- **M-8**｜`ai-dom-tools.ts:1631-1656`：`ui_scroll` 对隐藏元素仍回"已滚动到…"，与"隐藏即如实拒绝"的新口径不一致（描边 `ui_highlight` 同理，但描边确实无害；滚动不可能生效）。
- **M-9**｜报告数字：§9 的"新增 20 + 相邻 345 = 365"把 `ai-region-batch-b` 的 16 例**算了两次**（相邻表 22 个 spec 合计 329，+16 = 345；20+329+16 = 365 才是真实去重后总数）；正文又说相邻跑了"23 个"（表里 22 行）；§开头"3 个缺陷即期望的既有测试"与 §7"改写两个"不一致（实际改了 4 条断言/3-4 条用例）。结论（全绿、0 失败）不受影响，但控制器不要引用这两个总数。
- **M-10**｜交接卡身份安全只做了「放弃」：`AiPanel.vue:651` 的 `@choose="ai.chooseUploadFile()"` 仍无身份（今天安全，仅因为非活动卡渲染成 `missing` 而 `chooseDisabled`，`UploadHandoffCard.vue:35,96`）；`UploadHandoffCard` 的 `unavailable` 态（"任务已结束，仅供查看"）在终态改为"直接清卡"后仍无生产者，属死态（报告 §8-③/§6 已登记）。建议 `chooseUploadFile(toolCallId?)` 与 cancel 同形，或删掉 `unavailable` 分支。
- **M-11**｜`ai-dom-tools.ts:157-167`：`beginUiTurn` 只在**本轮第一次前端工具执行**时触发（`ai.ts:5580`），"换轮 = 新现场"实际是"换轮后第一次用 UI 工具 = 新现场"；纯文本/纯 CONFIRM 轮不推进版本（无害，但文档应写实）。同轮内页面自变仍不新鲜（见 ⑤）。

---

## 4 只在浏览器里成立、本次**未**验证的前提

1. **真实 `v-show` / Element Plus `el-tab-pane` 的 computed `display`**：判据走 `getComputedStyle` 祖先链，理论上与真机同口径，但没在真实 Chromium 核对；`checkVisibility()` 分支（Chrome ≥105）在 jsdom 恒不可用 ⇒ **零覆盖**（报告 §8-⑤.2 已自陈）。
2. **真机上隐藏控件被 `el.click()` 的副作用**：修复后不再依赖它（隐藏一律拒执），但"旧行为是否真的产生副作用"未实测；brief Step 1 要求的"原生点击/折叠行为不确定先上浏览器确认"**没有做**（报告以"判据只用标准语义"替代论证）。这是本次验证方式与 brief 的唯一流程性偏离。
3. **真实 SSE 时序**：park 非阻塞、409 后重连、多卡恢复全部在 mock SSE 下验证；`confirm_decision` 帧与 confirm POST 响应的**到达顺序**（即 I-1 的触发前提）只能在真实后端 + 浏览器里定论 —— 但这不影响 I-1 该修：两种顺序都合法，状态机必须都正确。
4. **复制页签 / Web Locks 所有权**：`ai.ts:419-427,4588` 一带未改也未复验；M-4 的多页签时序同样只能在真机复现。
5. **真实两卡刷新中间态**（卡 2 请求帧尚未发出、`req=false`）：`restoredPendingQueue` 首次有测试覆盖，但仍是 `/runs/current` 桩。
6. **相邻 345 例、前端全量、`npm run build`、后端、Playwright**：按控制器口径**未跑**（只复跑 20 新例 + 1 个被改写 spec），构建与真 CI 由控制器统一跑。

---

## 5 已复核的验证证据

工作目录：`<隔离副本>/_verify-20261025-chat-state/frontend`（= main `34fd8f4` + `task-5.patch`，逐字节比对 10 个文件全一致）

```
# 补丁可应用性（主仓，仅检查，未落盘；主仓 frontend 只有既有的 types/api.ts 改动）
git apply --check -p1 .superpowers/.../task-5.patch              → OK

# 新增 20 例（复跑）
npx vitest run tests/ai-multi-card-mixed-resume.spec.ts tests/ai-dom-visibility.spec.ts \
  tests/ai-snapshot-freshness.spec.ts tests/ai-round-terminal-residue.spec.ts
→ 4 files / 20 tests passed

# 被改写的 spec（复跑）
npx vitest run tests/ai-region-batch-b.spec.ts                    → 16 tests passed

# 类型检查（复跑，无输出）
npx vue-tsc --noEmit                                              → 0 error

# 一次性探针（已删除，仅用于本审查取证）
A) restored 两卡 + 帧先于响应   → reattach:0, suspended:false, card2:false, queueLen:1
B) 409 在 done 之后             → part pending+requiresAction, pendingToolCall:true, lastMessageDone:true
C) confirm_request 后 error     → suspended:true, pendingToolCall:false  ⇒ 输入区永久禁用
```

---

## 6 合并建议

**有条件合并（Approve with required fix）。**

1. **必须**先修 I-1（`hasOtherPendingCards` 计入 `restoredPendingQueue`）与 I-2（`error` 分支撑清 `suspended`）：两处都是一行改动 + 一条 5-10 行用例，且都落在补丁自己重写过的函数/分支里；不修则"多卡不丢续跑"与"终态不卡死"在合法时序下仍不成立。
2. **建议同批**处理 M-1（B-08 站到 `QUERY_COND`）与 M-3/M-2（陈旧注释），成本极低，避免把"降级的回归保护"和"错误文档"合进主线。
3. M-4/M-5/M-6/M-7/M-8/M-10/M-11 可作为后续批（需新 i18n 键、或属真实浏览器加固），不阻塞本次合并。
4. 合流顺序与文件重叠提示（报告 §10）成立：本补丁只碰 frontend store/DOM/panel/receipt + 测试，与 Task 4（向导视图）、Task 7（form-schema）、Task 1（`types/api.ts`）无文件重叠；但 `ai-region-batch-b.spec.ts` 挂载两个向导视图，若 Task 4 也改这些断言，须以"隐藏步骤不披露"为准重新对齐。
5. 最终全量/build/真机（浏览器探针、Playwright、真实 SSE 时序）仍需控制器统一跑；本审查只覆盖"20 新例 + 1 个被改写 spec + 类型检查 + 状态机取证"。
