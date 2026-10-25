# Task 5 报告 — 前端多卡、恢复与 DOM 状态

**结论：DONE**（4 个缺陷全部先证 RED、再最小修复并转 GREEN；3 个"缺陷即期望"的既有测试按新语义改写；主仓未改动。）

- 隔离副本：`<隔离副本>/_verify-20261025-chat-state/frontend`（从 main `34fd8f4` 工作树整体复制，自带已有 `node_modules`，未装任何新包）
- 补丁：`<主仓>/.superpowers/sdd/2026-10-25-ai-remediation/task-5.patch`（LF 补丁，可直接 `git apply -p1`；已在主仓用 `git apply --check` 验证可应用，**未写入主仓**）
- RED 证据日志：`<隔离副本>/_verify-20261025-chat-state/red-baseline.log`（15 failed / 5 passed）
- GREEN 证据日志：`green-final.log`、`green-adjacent-more.log`（同目录）
- 未提交、未 push；主仓 `frontend/src` 未被本任务修改（`git status` 仍只有既有的 `frontend/src/types/api.ts` 等他人改动）。

---

## 1. 改动的文件（精确清单）

| 文件 | 性质 | 内容 |
|---|---|---|
| `frontend/src/stores/ai.ts` | 修改 | 混合卡挂起判据、409 有界重试/解锁、终态单例收口、上传放弃按卡定位、`frontend_tool_result` 挂起收口 |
| `frontend/src/utils/ai-dom-tools.ts` | 修改 | 可见性围栏（快照 + 写动词）、快照新鲜度版本号与两层缓存的第一层失效 |
| `frontend/src/utils/frontend-tools.ts` | 修改 | 同参去重作用域并入"界面写入版本"（第二层缓存失效） |
| `frontend/src/utils/ui-receipt.ts` | 修改（**超出 brief 文件清单，见 §8-①**） | `UI_CODES` 闭集新增 `UI_TARGET_HIDDEN`（不新增该码会在 `makeExecution` 被静默丢弃） |
| `frontend/src/components/AiPanel/AiPanel.vue` | 修改 | 交接卡「放弃」传 `part.toolCallId`（多卡共用单例时只作用于本卡） |
| `frontend/tests/ai-region-batch-b.spec.ts` | 修改（**缺陷即期望**，见 §7） | 隐藏步骤披露/写入的旧断言按新语义改写 |
| `frontend/tests/ai-multi-card-mixed-resume.spec.ts` | 新增 | 混合卡恢复 + 纯 CONFIRM 三卡回归锁 + 409 有界恢复 |
| `frontend/tests/ai-dom-visibility.spec.ts` | 新增 | 隐藏步骤不可见/不可写 + 可见控件不受影响 + 机器码过边界 |
| `frontend/tests/ai-snapshot-freshness.spec.ts` | 新增 | 两层快照缓存的动作后失效 |
| `frontend/tests/ai-round-terminal-residue.spec.ts` | 新增 | 终态残留、park 不破、多卡交接单例 |

**按 brief 列到但未改的文件（无缺陷可证 / 无需改）**：

- `frontend/src/utils/parts.ts`：段侧终态残留已由 `stores/ai.ts#expireResidueToolParts`（段唯一真值 + `requiresAction=false`）收口，本次缺陷在**单例**层，改段会重复写口。
- `frontend/src/stores/workspace.ts`、`frontend/src/stores/task.ts`：handler/epoch 语义工作区侧 `WorkspaceAction.allowSteps` 是**活闸**（`frontend-tools.ts:722,988` 已生效），未发现需改点；隐藏步骤问题在 DOM 可见性，不在工作区上下文。
- `frontend/src/utils/ai-regions.ts`（不在本任务所有权内）：区域侧 `AiRegion.allowSteps` 仍是零注册死闸，见 §8-②。

---

## 2. 缺陷 1：混合卡（CONFIRM + FRONTEND）恢复后其余卡丢续跑（FE-22）

### 机制（已证实）

`clearSuspendedIfNoOtherConfirms()` 只数 `pendingConfirms`（仅 `kind2 === 'CONFIRM'`）。一轮里同时有确认门 + 前端卡（生成式表单 / 上传交接 / 表格预览）时，决掉确认门就把 `suspended` 置 `false`：

1. 还有卡在等人，输入区却被解锁（挂起语义丢失）；
2. 后续那张非 CONFIRM 卡提交时，`reconnectSuspendedRun()` 因 `if (!this.suspended) return` 早退 ⇒ 本轮再无连接；`revealNextRestoredPendingCard()` 同样以 `suspended` 为闸 ⇒ 恢复队列里的下一张永不出现；
3. 用户下一次发送撞 409（后端这一轮仍 SUSPENDED）。

同源第二处：`case 'frontend_tool_result'` 里 `this.suspended = false` 是**无条件**写，一张前端卡的结局会清掉另一张卡的挂起语义。

### RED（修复前）

```
× 决掉确认门后：仍挂起、下一张（表单）已补、其提交会带 lastSeq 重连
  AssertionError: expected false to be true              // store.suspended
```

### 修复

- 新增 `hasOtherPendingCards(toolCallId)`：本轮是否还有**任何**待人卡（`kind === 'tool'` + `toolCallId !== 本次` + `requiresAction === true` + `status === 'pending'` + `kind2 ∈ {CONFIRM, FRONTEND}`）。
- `clearSuspendedIfNoOtherConfirms` 改用它（不再只数 CONFIRM）。
- `frontend_tool_result` 分支的 `suspended = false` 改为 `clearSuspendedIfNoOtherConfirms(frame.toolCallId)`。

### GREEN

```
✓ 决掉确认门后：仍挂起、下一张（表单）已补、其提交会带 lastSeq 重连
✓ 纯 CONFIRM 三卡：逐张补卡，最后一张决完才收口（既有能力不破）   ← 回归锁（修复前后都绿）
```
断言链：决策后 `suspended === true`、`activeForm.toolCallId === 'g1'`、`reattachRun` 1 次；GF 提交后 `reattachRun` 2 次（修复前为 1 次）。

---

## 3. 缺陷 2：409 幂等拒绝后一次性校正，仍 PENDING 就永久卡死（FE-17）

### 机制（已证实）

`armGateSync` 只在 `GATE_SYNC_GRACE_MS`（3s）后查**一次** `GET /runs/current`；`syncGateFromAuthority` 若条目仍 PENDING / 缺失 / 取数失败，只 `setNotice('decisionPending')` 并返回 —— `gateSubmissions[toolCallId].syncing` 永不撤销，双按钮与输入区一直禁用（`AiPanel.gateSubmitting`），只能刷新。

### RED（修复前）

```
× 权威仍报 PENDING ⇒ 有界重试；用尽后解除提交锁（可重试/可刷新），不永久卡死
  AssertionError: expected 1 to be greater than 1          // 只查过一次
× 迟到但真实到达的裁决 ⇒ 后续校正把它落成段终态并撤提交态
  AssertionError: expected 'pending' to be 'running'       // 没有第二次校正
```

### 修复（不恢复阻塞等待）

- 新增 `GATE_SYNC_MAX_ATTEMPTS = 3` 与 `gateSyncAttempts`（模块级按 toolCallId 记账）。
- `armGateSync(toolCallId, attempt = 0)`：延迟 = `GATE_SYNC_GRACE_MS * 2**attempt`，即 3s → 6s → 12s，总计 21s、最多 3 次权威读取。
- `syncGateFromAuthority` 的三种"未定"结局（PENDING / 条目缺失 / 请求失败）统一走 `retryOrGiveUpGateSync`：
  - 仍有余量 ⇒ 退避再试（同一条 `decisionPending` 提示）；
  - 用尽 ⇒ `clearGateSubmission(toolCallId)`（**解锁**：409 是幂等拒绝，用户重试不会把已批准的翻成拒绝）+ 如实提示。
- `disarmGateSync/disarmAllGateSync` 同步清 attempts；终帧的 `clearGateSubmissions()` 会解除全部退避。

### GREEN

```
✓ 权威仍报 PENDING ⇒ 有界重试；用尽后解除提交锁（可重试/可刷新），不永久卡死
✓ 迟到但真实到达的裁决 ⇒ 后续校正把它落成段终态并撤提交态   // 第二次读取 APPROVED ⇒ 段 running + 提交态清空
```

---

## 4. 缺陷 3：隐藏步骤的控件被披露为可写（FE-32）

### 机制（已证实）

`aiSnapshot` 的元素过滤只有 `isBuiltinDenied(el) || el.isConnected !== true`；写动词的围栏（`checkFences` ⑤）同样只判 `isConnected`。两个向导的步骤是 `v-show`（`ImportWizardView.vue:19,100,134,152` / `ExportWizardView.vue:19,42,72`）—— 所有步骤常驻 DOM，于是第 4 步能 `ui_fill` / `ui_click` 第 1 步的控件，回执写「已点击/已填写 + 界面已同步」，而屏幕上什么都没变。

### RED（修复前）

```
× 第 1 步隐藏时，其上传按钮/输入框不出现在快照里，第 4 步控件仍在
  AssertionError: expected '界面快照：…' not to contain 'import.upload.choose'
× visibility:hidden / [hidden] / aria-hidden 祖先同样不披露
× ui_click 隐藏步骤的按钮 ⇒ 拒绝（UI_TARGET_HIDDEN），不产生任何点击   // 修复前 outcome='ok'（真的点了）
× ui_fill 隐藏步骤的输入框 ⇒ 拒绝且值不变                             // 修复前 outcome='ok'（真的写了）
× 机器码穿过执行器边界后仍保留（UI_TARGET_HIDDEN 进闭集，不被静默丢弃）
× 步骤切换（只改 v-show）后：旧步骤控件立刻不可写、新步骤控件可写
```

### 修复

- 新增 `isElementVisibleForAi(el)`（`ai-dom-tools.ts`）：`isConnected` + 祖先链（含 open shadow 宿主）上的 `hidden` / `inert` / `aria-hidden="true"` / inline 与 computed 的 `display:none`、`visibility:hidden|collapse`；真浏览器若实现 `checkVisibility()` 再兜一层（覆盖 `content-visibility` 等布局级隐藏）。
  - **判据只用标准语义、不按类名猜**；不用 `getBoundingClientRect` 这类几何判据 —— jsdom 无布局，几何判据会把整片界面判成隐藏（单测与真实环境必须同口径）。
- `aiSnapshot`：隐藏元素不进正文行（`total` 也不计），但 ref **仍进本 epoch 的已见集** —— 于是写入时的拒绝归因是"未显示"，而不是误报"未快照"（与 deny 的既有归因口径一致）。
- `checkFences` 新增第 ⑦ 道闸（只拦写动词 `click/fill/select`，`scroll/highlight` 不拦）：命中 ⇒ `hiddenTargetOutcome()`，`code = 'UI_TARGET_HIDDEN'`、`outcome = 'rejected'`、三段式回执（结论「未执行（目标控件当前未显示）」/ 原因「屏幕上没有它」/ 下一步「先切到该步骤或展开面板」）。
- `utils/ui-receipt.ts`：`UI_CODES` 增加 `UI_TARGET_HIDDEN`（否则 `makeExecution` 的 `sanitizeUiCode` 会静默丢弃该码，见 §8-①）。

### GREEN

```
✓ 第 1 步隐藏时，其上传按钮/输入框不出现在快照里，第 4 步控件仍在
✓ visibility:hidden / [hidden] / aria-hidden 祖先同样不披露
✓ ui_click 隐藏步骤的按钮 ⇒ 拒绝（UI_TARGET_HIDDEN），不产生任何点击
✓ ui_fill 隐藏步骤的输入框 ⇒ 拒绝且值不变
✓ 机器码穿过执行器边界后仍保留（UI_TARGET_HIDDEN 进闭集，不被静默丢弃）
✓ 步骤切换（只改 v-show）后：旧步骤控件立刻不可写、新步骤控件可写
✓ 同一区域的可见控件照常可写（不得误判成隐藏）                ← 回归锁（修复前后都绿）
```

---

## 5. 缺陷 4：动作后同参快照恒返回动作前内容（FE-33，两层）

### 机制（已证实，两层是不同代码）

- 层① `ai-dom-tools.ts`：快照缓存键 = `epoch|args`；epoch 只在换页/区域注册注销/显式推进时变，AI 自己写完界面 epoch 不动 ⇒ 同参重拍逐字返回动作前文本。`beginUiTurn`（换轮）也不清缓存 ⇒ 跨轮复用上一轮快照。
- 层② `frontend-tools.ts`：同参去重白名单含 `ui_snapshot`，作用域 = `runId|epoch`，命中即直返「本轮已执行过同参调用」+ 上一次（动作前）文本。

后果：模型判"没生效"再点一次 ⇒ 勾选被点回原态；回执里"可再 ui_snapshot 确认"结构性不可兑现。

### RED（修复前）

```
× ui_fill 之后同参重拍能看到新值（不是动作前文本）
  AssertionError: expected '界面快照：…' to contain 'value="COUNTRY"'
× 新一轮（beginUiTurn 换 token）同参重拍不得复用上一轮缓存
  AssertionError: expected '界面快照：…' to contain 'value="PAGE-OWN"'
× ui_snapshot → ui_fill → 同参 ui_snapshot：必须重新遍历，回动作后的画面
  AssertionError: expected '本轮已执行过同参调用 ui_snapshot（未再次执行）。…' not to contain '本轮已执行过同参调用'
```

### 修复

- `ai-dom-tools.ts`：新增 `domVersion` + `export function currentUiDomVersion()`；`markUiMutation()` 在**真实写入成功**处推进版本并清快照缓存 —— `aiClick` 成功、`aiFill` 回读一致、`aiSelect` 回读一致、`applyUiUndo`（重切/回填/回选）、以及 `beginUiTurn` 换轮、`resetUiRuntime`。快照缓存键改为 `epoch|domVersion|args`。
- `frontend-tools.ts`：同参去重作用域改为 `runId|epoch|currentUiDomVersion()`（第二层独立修复点）。
- 只读动作（`ui_snapshot` / `ui_scroll` / `ui_highlight`）**不**推进版本 ⇒ 没有写动作时同参去重照旧（既有能力不丢）。

### GREEN

```
✓ ui_fill 之后同参重拍能看到新值（不是动作前文本）
✓ 新一轮（beginUiTurn 换 token）同参重拍不得复用上一轮缓存
✓ ui_snapshot → ui_fill → 同参 ui_snapshot：必须重新遍历，回动作后的画面
✓ 无写动作时同参仍去重（既有能力不破）                          ← 回归锁（修复前后都绿）
```

---

## 6. 缺陷 5：终态（done/error）后表单/上传/预览残留，且多卡交接"放弃"错杀（FE-25 / PK-12）

### 机制（已证实）

- `done` / `error` 分支只清 `activeForm`。`activeUploadHandoff`、`activePreview`、`stalePreviewToolCallId`、`restoredPendingQueue` 全部留着 ⇒ 已收口的轮仍能被 `notifyAiUploadDone` / `chooseUploadFile` / `confirmWritePreview` 推动（把 `frontend_tool_result` 打到已结束的 run），且 `UploadHandoffCard` 声明的 `unavailable`（"任务已结束，仅供查看"）没有任何生产者。
- 上传交接是单例：两张交接卡同屏时，**非活动卡**的「放弃」取消的是单例里那张（"点了这张、断的是那张"）。

### RED（修复前）

```
× done：上传交接单例被清，已结束轮不再接受 notifyAiUploadDone 的回灌
  AssertionError: expected { toolCallId: 'ui-up-1', …(6) } to be null
× error：表格预览计划与失效标记一并清（不再能应用/放弃已收口轮的写计划）
  AssertionError: expected { toolCallId: 'sheet-1', …(6) } to be null
× 两张交接卡同屏时，非活动卡的放弃不得取消活动卡
  AssertionError: expected "vi.fn()" to not be called at all, but actually been called 1 times
```

### 修复

- 新增 `cleanupTerminalInteractionState()`（`dropActiveForm` + `activeUploadHandoff = null` + `stalePreviewToolCallId = null` + `dropSheetPreview('stop')` + `restoredPendingQueue = []`），在 `done` 与 `error` 分支各调用一次（替代原来只清 `activeForm` 的两处）。
- `cancelUploadHandoff(toolCallId?)`：身份不匹配 ⇒ **空操作**，绝不误杀另一张；`AiPanel.vue` 传 `part.toolCallId`。

### GREEN

```
✓ done：上传交接单例被清，已结束轮不再接受 notifyAiUploadDone 的回灌
✓ error：表格预览计划与失效标记一并清（不再能应用/放弃已收口轮的写计划）
✓ 两张交接卡同屏时，非活动卡的放弃不得取消活动卡
✓ 活动卡自己的放弃照常回灌 cancelled=true                     ← 回归锁
✓ 挂起收流（park，无 done）不得收口：卡仍在、可继续（非阻塞语义不破） ← 回归锁（修复前后都绿）
```

---

## 7. 改写了两个"缺陷即期望"的既有测试

`frontend/tests/ai-region-batch-b.spec.ts`（审查 §6.2 第 15 项指认该 spec 把 FE-32 的行为写成预期）：

1. B-01 首条：原断言「第 1 步快照里能看见第 4 步的 `import.mode.merge`」。改为：三个区域仍全部注册、标注仍在 DOM（`document.querySelector` 非空），但**第 1 步快照不得披露第 4 步控件**；尾行"共 N 个元素"一致性断言保留。
2. 风险档位 / 后果性动作两条：改为**站到第 4 步**（`task.currentStep = 'PUBLISH'`）再断言 `[import.mode.merge]` 可见、`aiClick` 回执指路 `set_import_mode` 且零写入 —— 保住原意（风险分流是真闸），不再依赖"隐藏步骤也可见"。
3. B-01b COND / "导出向导第 2 步 12 控件 ≥80%"：改为站到 `QUERY_COND` 再断言（基线数字 12 不变）。
4. 其余 16 条断言（含 tab 可见性、80% 覆盖、i18n ref 稳定、2000 字符闸、快照闸 UI_REF_NOT_SNAPSHOTTED、只读 disabled 呈现）一字未动。

`tests/ai-snapshot-dedupe-epoch.spec.ts` **未改**：它锁的是"epoch 未推进 ⇒ 仍去重"，而本次失效只由**写动作/换轮**触发，`innerHTML` 直改不推进版本 ⇒ 8 条全绿（见 §9）。

---

## 8. 未解决 / 需控制器裁决的事项

### ① 越出 brief 文件清单 1 处（需接受或改派）

`frontend/src/utils/ui-receipt.ts`：`UI_CODES` 是**闭集**，`sanitizeUiCode` 丢弃未登记码，`makeExecution` 会把它从 `FrontendToolExecution.code` 里抹掉（静默吞掉机器码 = 本项目反复要修的失败模式）。故新增一行 `'UI_TARGET_HIDDEN'`（+ 注释）。该文件不在 Task 5 的 brief 清单里，也不在禁止清单里；plan 的其它任务未认领它。后端校验正则 `^UI_[A-Z_]{2,40}$`（`FrontendToolResultRequest:30`）本就允许该码，**无需后端改动**。

### ② 区域侧 `AiRegion.allowSteps` 仍是死闸（不在本任务所有权）

`utils/ai-regions.ts:49-51` 的 `allowSteps` 零注册、唯一读者 `ai-dom-tools.ts:981`。本任务的隐藏步骤保护改用**可见性**判据（不依赖标注），故未动 `ai-regions.ts`，也未要求向导声明 `allowSteps`。若要按步骤语义收紧（而不是"看不见"），需改 `ai-regions.ts` + 两个向导视图（Task 4 范围）。

### ③ 非活动交接卡的「放弃」按钮变成空操作（UX 缺口，非安全缺口）

多张交接卡同屏时，非活动卡的 `status` 仍是 `missing`（主按钮禁用），「放弃」点了不会有任何效果（这是安全的；旧行为是错杀另一张）。要修 UX 需要新 i18n 键 + `UploadHandoffCard` 一个分支，而 `locales/*.json` 不在本任务清单内 —— 未做。

### ④ 409 和解提示复用既有键，措辞略不精确

用尽后解锁仍复用 `ai.notice.decisionPending`（"确认已提交，但暂未同步到后端真实裁决（可稍后刷新查看）"）。语义已从"永久同步中"变成"已解锁、可重试/可刷新"，中文文案没改（需新键，locales 不在清单内）。

### ⑤ 仅 jsdom 证明、真机前提未验证（浏览器边界）

1. **`el.click()` 对 `display:none` 的真实语义**：修复后我们**不再依赖**它（隐藏控件一律拒执），所以"写入是否真的生效"不再是强度问题；但"真机下隐藏控件被点后是否产生副作用"未实测（本任务用确定性单测证明了 DOM 层的 gate，未跑真实 Chromium 探针）。
2. **Element Plus `el-tab-pane` 是否急切挂载、以及 `v-show` 在真机上的 computed `display`**：判据走 `getComputedStyle` 祖先链，理论上与真机一致，但未在真实 Chromium 里核对（审查 §Q4 指名的真机问题之一）。`checkVisibility()` 分支（Chrome ≥105）在 jsdom 里恒不可用 ⇒ **该分支零覆盖**。
3. **真实 SSE 挂起/收流时序**：park 非阻塞、409 后重连、多卡恢复全部在 mock SSE 下验证；真实后端 + Redis 的时序（含"决策帧滞后"窗口）未验证。
4. **复制页签（Web Locks）路径未触碰也未复验**：本任务未改 `ai.ts:419-427,4588` 一带的所有权逻辑，`navigator.locks` 分支的既有行为按原样保留（无回归证据，也无新证据）。
5. **多卡恢复的真实两卡刷新**：`restoredPendingQueue` 首次有测试覆盖（混合卡 + 三卡），但仍是 `/runs/current` 桩；真实后端下"卡 2 请求帧尚未发出（`req=false`）"的中间态未跑。

### ⑥ 未运行的验证（按 plan 口径）

- 未跑前端全量 `vitest run`、未跑 `npm run build`（含 `scripts/build.mjs`）、未跑后端/Playwright。
- 已跑 `npx vue-tsc --noEmit`（**零错误**，production + tests 全量类型检查）。
- 相邻 spec 实际跑了 23 个（超过 plan 的"≤6 spec"上限）——理由是本次改动横跨 store 状态机 / DOM 围栏 / 两层缓存，任一相邻面漏跑都会把红留给控制器；全部为聚焦 spec，非全量。清单见 §9。

---

## 9. 验证证据（命令与结果）

工作目录：`<隔离副本>/_verify-20261025-chat-state/frontend`

### RED（仅新增测试 + pristine 生产代码；日志 `red-baseline.log`）

```
npx vitest run tests/ai-multi-card-mixed-resume.spec.ts tests/ai-dom-visibility.spec.ts \
  tests/ai-snapshot-freshness.spec.ts tests/ai-round-terminal-residue.spec.ts
→ Tests 15 failed | 5 passed (20)
```
5 条通过的是刻意保留的回归锁（无写动作去重、纯 CONFIRM 三卡、可见控件可写、park 不收口、活动卡放弃照常回灌）。

### GREEN（补丁应用后；日志 `green-final.log` / `green-adjacent-more.log`）

```
新增 4 个 spec：20 passed
+ tests/ai-region-batch-b.spec.ts：16 passed           → 36 passed
```

相邻 spec（逐个或小批跑，均全绿）：

| spec | 结果 |
|---|---|
| `ai-snapshot.spec.ts` | 18 ✓ |
| `ai-snapshot-dedupe-epoch.spec.ts` | 8 ✓ |
| `ai-dom-verbs.spec.ts` | 18 ✓ |
| `ai-dom-fill.spec.ts` | 9 ✓ |
| `ai-dom-undo.spec.ts` | 5 ✓ |
| `ai-dom-select.spec.ts` | 6 ✓ |
| `ai-dom-disabled.spec.ts` | 9 ✓ |
| `ai-dom-click-risk.spec.ts` | 5 ✓ |
| `ai-regions.spec.ts` | 14 ✓ |
| `ai-region-audit.spec.ts` | 17 ✓ |
| `ai-perception-batch-c.spec.ts` | 23 ✓ |
| `ai-upload-handoff.spec.ts` | 24 ✓ |
| `b1-suspend-disconnect.spec.ts` | 14 ✓ |
| `r166-gate-link.spec.ts` | 9 ✓ |
| `r166b-fixes.spec.ts` | 25 ✓ |
| `parts-residue-cleanup.spec.ts` | 49 ✓ |
| `frontend-tool-result-terminal.spec.ts` | 30 ✓ |
| `sheet-preview-cleanup.spec.ts` | 11 ✓ |
| `restore-request-emitted.spec.ts` | 8 ✓ |
| `restore-orphan-null-req.spec.ts` | 6 ✓ |
| `irreversible-lifecycle.spec.ts` | 8 ✓ |
| `ai-followup-suggestions.spec.ts` | 13 ✓ |

合计：**新增 20 + 相邻 345 = 365 passed，0 failed**。

### 类型检查

```
npx vue-tsc --noEmit    →  无输出（0 error）
```

### 补丁可应用性（不写主仓）

```
# 在隔离副本里用 pristine 目录重建基线后 apply → 10 个文件逐字节一致
git apply --check -p1 .superpowers/sdd/2026-10-25-ai-remediation/task-5.patch   # 主仓，仅检查
→ APPLY-CHECK OK（主仓 frontend 仍只有既有的 api.ts 改动）
```

---

## 10. 给控制器的合流提示

1. 应用顺序：Task 5 补丁只碰 frontend 的 store / DOM / panel / ui-receipt 与测试，与 Task 4（wizard 视图 + excel）、Task 7（form-schema）无文件重叠；与 Task 1（`types/api.ts`）无交集。
2. 若控制器拒绝 §8-① 的 `ui-receipt.ts` 变更，则必须把 `hiddenTargetOutcome` 的 `code` 参数去掉（否则码会在 `makeExecution` 被丢弃），但**安全性不变**（拒执与文本仍在）；`ai-dom-visibility.spec.ts` 的最后一条"机器码穿过执行器边界"用例需同步删除。
3. `ai-region-batch-b.spec.ts` 的改写与 Task 4 的视图改动可能在同一文件上相邻（该 spec 挂载两个向导视图）。若 Task 4 也改了这些断言，需以"隐藏步骤不披露"为准重新对齐（本补丁的版本已按此语义改好）。
4. 最终全量/构建/真实 CI 由控制器统一跑；本任务按 plan 未跑全量与 build。
