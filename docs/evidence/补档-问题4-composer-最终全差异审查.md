> 归档来源：仓外正式报告（已脱敏）；归档日期：2026-10-19；本件为 2026-10-19 仓外文档补档批入库件，正文结论与数据未改。

# 问题4 composer v2 项目回落 · 最终全差异审查

## 0. 身份自证与审查对象

- 自身 wire：`<SESSIONS_ROOT>/<SESSION_ID>/agents/agent-1119/wire.jsonl`
- `profile.bind`：`agentId=agent-1119`、`modelAlias=DeepSeek/deepseek-flash`、`profileName=coder`、`thinkingEffort=high`。
- 仓库：`<REPO_ROOT>`
- BASE：`cb0e926c0b7c72aacc21a27ede1171cc83b15a62`
- HEAD：未提交工作树
- 变更文件（仅 3 个）：
  - `frontend/src/components/AiPanel/AiPanel.vue`（+240/−49）
  - `frontend/src/stores/ai.ts`（+40/−9）
  - `frontend/tests/ai-panel-parts-render.spec.ts`（+726/−25）
- 合计：1006 insertions / 83 deletions。
- `?? data/` 为开工前既存未跟踪目录（Oct 12），不属于本批；提交时不得 `git add -A`。
- 审查包：`<SCRATCH>/_cu-composer-project-r26/final-review.diff`，1263 行，58,828 bytes，
  sha256 `24cd933009d8158fdb6f3750ba48bc907ec8f859858d41fdd3fa25e4d3956eaa`。
- 本次只读审查：未改源码、未提交、未起停服务；仅向仓外审查包写 diff/日志/本报告。

## 1. 结论摘要

- **Critical：0**
- **Important：0 个阻断项**
- **Minor：7 个非阻断项**（tip 跨通道隐藏、send 拒绝未兜底、隐式编辑锚点边界、会话重置锚点、左工具位可见占位、测试缺口、注释精度）
- **READY / NOT READY：READY（composer 交付范围）**
- **可提交结论：可提交**；只提交上述 3 个文件，排除 `data/`。
- **不能给“全项目/全浏览器 PASS”**：360/400 是既有页面级问题5，仍 FAIL；真实中文 IME 仍 BLOCKED；live HITL 仍 BLOCKED；浏览器证据为 Chromium（内置浏览器 + Chromium），未覆盖 Firefox/Safari。

## 2. 规格逐项核对

| 规格 | 结论 | 证据 |
|---|---|---|
| 最多 4 行、101px 封顶 | PASS | 浏览器实测 1/4/6 行 = 40/101/101px；6 行 `scrollHeight=143 > clientHeight=101`，内滚；工具栏 top 前后均 727。 |
| 流式可编辑 + 圆形停止 | PASS | 流式中 textarea 无 disabled/虚线；停止钮 28×28、radius 9999px、白方块 9×9、中心 dx/dy=0。 |
| 普通 Enter 流式静默 | PASS | 测试 + 浏览器：value/caret 不变，不发送、不停止、不换行、无 notice、无排队。 |
| Shift+Enter | PASS | 追加换行，caret 前进，流式继续。 |
| IME 守卫 | 合成 PASS / 真实 BLOCKED | `isComposing`、`keyCode 229`、compositionend 30ms、blur 复位均有测试；真实中文输入法未实机验收。 |
| 真实 hard-disabled | PASS | health 明确不可用 / healthError / suspended / pendingToolCall 硬禁用；health=null 未知态不硬禁用、发送钮禁用。 |
| 发送焦点 | PASS | 成功清空后 `focus({preventScroll:true})`；浏览器空闲 Enter 后焦点回 textarea。 |
| 失败/断流保稿 | PASS | 开流前 503、SSE 断流均恢复原稿并聚焦；用户新输入不被覆盖。 |
| 主动 stop 不恢复 | PASS | 真实 SSE stop 后输入为空，不自动发送；停止后 Enter 恰好发送一次。 |
| 重发不重复用户消息 | PASS | 真实 store + 浏览器 503 重发：用户气泡保持 1 条；编辑重跑沿用原 id。 |
| error 终帧映射 | PASS | 新增 `sawErrorTerminal`，真实 store 测试锁定 `outcome=error`。 |
| tip Esc + 不破坏 mermaid | PASS | 发送/停止 tip Esc 后 `display:none`/`hidden`；focusin/mouse 重入恢复；Esc 不 `preventDefault`/`stopPropagation`；mermaid 全屏仍可 Esc 关闭。 |
| 全 Tailwind | PASS | 无内联 `style`、无新增 `<style>`；测试断言。 |
| 不改后端/API/SSE/#3/2A/外层问题5 | PASS | `git diff` 仅 3 文件；App.vue/styles、backend、api、sse 均 0 行。 |
| 页面级 360/400 | FAIL（既有问题5，不归本批） | `document.scrollWidth=493`；责任元素为 header nowrap + 固定 400px aside；composer 368px 在 aside 内，`descendantBeyondAside=[]`。 |

## 3. Strengths

- `send()` 的 outcome 映射完整：`not-sent / ok / error / interrupted / cancelled`，并优先用 `isCurrentRound()` 挡住旧轮污染；`stopped`、`streamInterrupted` 每轮复位。
- error 终帧修复正确：`onFrame` 只在本轮置 `sawErrorTerminal`，开流前 catch 不受污染，真实 store 测试覆盖。
- 失败保稿分支克制：只写 `inputText` / `editingMessageId` / `focus()`，绝不自动重发或重复 `recordUserMessage`。
- 重发防重复路径真实成立：失败用户消息作为编辑锚点，重发走 `skipUserRecord:true`；浏览器 503 与单元测试均验证用户消息不增加。
- 测试数量增长且没有弱化真实行为：旧 composer 结构锁按新规格重写；旧 `store.send` mock 同步为 `{outcome:'ok'}`；新增 store 映射组走真实 store + API 桩。
- tip 共享状态机无内存泄漏：状态最多 2 个 key，`Set` 替换式更新；无定时器、无 document/global 监听、无动态监听器，容器监听随组件卸载。
- 浏览器证据链身份可核：`browser/final/report.md` 自证 `agent-1084`，其 wire `profile.bind` 与只读验收 turn prompt 一致，不是施工 agent 冒充。

## 4. Issues

### Critical (Must Fix)

无。

### Important (Should Fix)

无阻断性 Important。前一轮的 3 个 Important 均已闭环：
- SSE terminal `error` 误归 `ok`：已加 `sawErrorTerminal` + 测试（`stores/ai.ts:1521-1530`、`1582`；spec:4921）。
- IME 组合态缺 blur 复位：已加 `@blur.capture` + 测试（`AiPanel.vue:747`、`1339-1342`；spec:3530）。
- store loading 守卫注释过期：已更新（`stores/ai.ts:1497-1499`）。

### Minor (Nice to Have)

1. **tip 跨通道隐藏过强（事件语义与 demo 有差异）**
   - 位置：`frontend/src/components/AiPanel/AiPanel.vue:1396-1408`、`1419-1428`。
   - 现状：`mouseout` / `focusout` 无条件 `hideComposerTip(key)`。
   - 影响：按钮保持键盘焦点时，鼠标扫过再移出会加 `hidden`，`group-focus-visible` 仍应显示的 tip 被 `display:none` 盖掉；反之 hover 仍有效时 focusout 也会隐藏。demo（`docs/demo/chat-ux/composer-decisions.html:404-416`）在 leave 时是移除 `hidden`。
   - 建议：仅当 `hoveredComposerTip` 与 `focusedComposerTip` 都不指向该 key 时才隐藏；补一条“hover+focusout / focus+mouseout”组合测试。
   - 分诊：非阻断，纯 polish；Esc/hover/focus 主路径已通过。

2. **组件 `send()` 未兜底 `ai.send` 的 reject**
   - 位置：`frontend/src/components/AiPanel/AiPanel.vue:2794-2809`；store 的 try 从 `stores/ai.ts:1576` 才开始。
   - 现状：`recordUserMessage` / `appendAssistantMessage` / `workspace.buildContext` 在 try 之外；若其中异常，`ai.send` reject，组件不会恢复草稿、不会设锚点。
   - 影响：极端内部异常下草稿丢失；若失败前已记录用户消息，用户再次输入可能形成重复。
   - 建议：组件层 `try/catch`，catch 中按失败路径恢复 `draft` 并聚焦（不自动重发）。
   - 分诊：非阻断；正常网络失败全部在 store catch 内，浏览器 503/断流已验证。

3. **失败后的隐式编辑锚点 + “继续”路径可截断后段历史**
   - 位置：`AiPanel.vue:2808`（锚点）、`2656-2658`（`continueRound`）、`1163-1165`（错误卡“稍后重试”）。
   - 场景：发送 A 失败 → 恢复 A 草稿并把 `editingMessageId=A` → 点击“继续/稍后重试”产生 B 轮 → 再按 Enter 重发 A ⇒ 按既有编辑重跑语义截断 B 轮。
   - 影响：不是重复消息；是隐式 edit 模式导致的历史截断，用户看不到锚点提示。
   - 建议：在另一轮开始（`continueRound`/程序化 send）时清除隐式锚点，或在 composer 显示“重试/编辑中”状态；若要严格无隐式截断，需要产品裁决。
   - 分诊：非阻断；与“重发不重复用户消息”的既定实现是同一设计取舍。

4. **会话重置后锚点短暂悬空（既有 Minor）**
   - 位置：`AiPanel.vue:2808`；回落保护 `2783-2793`。
   - 现状：resetSession 清空消息后，旧轮返回 `interrupted` 仍会把 `editingMessageId` 设为已不存在的 id。
   - 影响：下一次发送 `cut < 0` 回落普通发送，不重复、不误删；只是短暂悬空。
   - 分诊：非阻断；可在设锚前确认 `ai.messages` 仍存在该 id。

5. **左工具位显示可见占位文案（既有 Minor）**
   - 位置：`AiPanel.vue:750`；测试 `tests/ai-panel-parts-render.spec.ts:3247`。
   - 现状：底栏显示“工具位（预留 · 留空）”虚线占位；demo 同款，但生产 UI 像开发标注，且未 `aria-hidden`，屏幕阅读器会读到。
   - 影响：无内部溢出（composer 368px 在 400px aside 内）；纯视觉/无障碍细节。
   - 建议：保留虚线槽但去掉文案或加 `aria-hidden="true"`；若“留空”按字面执行，需产品确认。
   - 分诊：非阻断。

6. **测试缺口（数量增长但少数边界未钉住）**
   - 旧轮 `!isCurrentRound() → interrupted` 无专测（`stores/ai.ts:1563-1574`）；代码阅读确认优先级正确。
   - tip 跨通道隐藏无测试（现有 spec:3322 focusout、3357 mouseover/out 各自独立）。
   - `keyCode 229` 用例紧跟在 compositionend 后，30ms 守卫也会拦截，未单独隔离 229 分支（spec:3469-3489）。
   - 组件层未测 `ai.send` reject 的草稿恢复。
   - 分诊：非阻断；建议后续补，不影响本次 READY。

7. **注释精度**
   - 位置：`AiPanel.vue:2806`。
   - 现状：注释写“失败 / 断流 / 未发送 … 新消息锚到本轮用户消息”；`not-sent` 的 `userMessageId` 为 null，不会设锚；用户已写新内容时也不会走恢复块。
   - 影响：仅阅读误导，代码行为正确。
   - 建议：改为“error/interrupted 新消息锚到 userMessageId；not-sent 为 null；编辑重跑沿用 editId”。

### 额外提示（不计为 Issue）

- 停止后焦点未显式回收：`stop()` 只 `void ai.stop()`（`AiPanel.vue:2812-2813`），停止钮卸载后焦点可能落回 body。规格只要求“发送焦点”，故不判问题；若追求连续输入体验，可在停止收尾后 `focus`。
- `busyConflict` 未纳入 `composerHardDisabled`（phase1 风险 #5）：按钮可能可点但 store 返回 409 notice；属既有/后续裁决，不阻断。
- Ctrl/Meta/Alt+Enter 当前按普通 Enter 发送（demo 同口径）；规格未要求，未判。
- 其他 tip（消息操作、mermaid 工具条）没有 Esc 关闭；本批只要求 composer tip，未判。

## 5. Declined to judge（明确不判/不归本批）

- **360/400 页面级横向溢出（问题5）**：既有页面壳层缺陷，App.vue/styles 本批 0 diff；composer 内部无新增溢出。该验收项仍 FAIL，不能改判 PASS。
- **真实中文 IME**：环境无法可靠产生真实组合，仅有合成事件证据；标 BLOCKED，不得冒充验收。
- **live HITL 圆圈问号**：本次未安全产生 live HITL gate；模板/单测仍在，标 BLOCKED；不归本批。
- **Firefox / Safari**：未实机；`field-sizing:content` 为 Baseline Newly available，不支持时回退 40px + 框内滚动，未加 JS 镜像。
- **后端/API/SSE/#3/2A/App.vue/styles**：diff 为 0，明确不在审查范围。
- **`data/` 未跟踪目录**：Oct 12 既存，非本批产物；提交时排除。
- **编辑重跑失败时后段历史已被截断**：R-157 既有语义，本批未改；只新增保稿/锚点，不按本批回归判。
- **SSE terminal error 的浏览器级注入**：浏览器只注入了开流前 503 与空 body 断流；terminal error 映射由真实 store 单测覆盖。
- **build wrapper 的 .tmp-esbuild 杀毒兼容路径**：本次用同一 Vite 配置把 outDir 重定向到仓外验证，未走 `scripts/build.mjs` 的 TMP 包装；构建本身 exit 0。

## 6. 独立验证与证据

| 项目 | 命令/证据 | 结果 |
|---|---|---|
| targeted | `yarn test tests/ai-panel-parts-render.spec.ts` | 173/173 passed，exit 0 |
| full | `yarn test` | 23 files / 444/444 passed，exit 0 |
| typecheck | `yarn typecheck` | exit 0 |
| production build | Vite build（outDir 重定向到仓外） | exit 0，built in 32.05s |
| diff whitespace | `git diff --check` | exit 0（仅 LF→CRLF 提示） |
| scope | `git diff --name-only cb0e926` | 仅 3 文件 |
| no outer changes | App.vue/styles/backend/api/sse diff | 0 行 |
| gate docs | `gate-check-docs.log` | 6/6 PASS |
| gate test | 旧 `gate-test.log` 439（12:42，早于 13:01 tip 修复） | 已被 `tip-esc-full.log` 444 与本次 444 覆盖 |
| gate build | 旧 `gate-build.log` 12:42（早于 tip 修复） | 本次最终树独立 build exit 0 |
| browser | `browser/final/report.md` + `tip-final.json` + `smoke-final.json` + `mermaid-escape.json` | tip/几何/stop/失败/360-400 均按边界记录 |

审查包补充文件：
- `<SCRATCH>/_cu-composer-project-r26/final-review-verify.log`（targeted/full/typecheck）
- `<SCRATCH>/_cu-composer-project-r26/final-review-build.log`（最终树 build）
- `<SCRATCH>/_cu-composer-project-r26/final-review-report.md`（本报告）

## 7. Assessment

**Ready to merge? Yes（composer 交付范围）**

**Reasoning：** 规格核心路径全部有真实 store/组件测试与 Chromium 浏览器证据；outcome/锚点未发现正常路径的重复消息或误删历史；tip 状态机无内存/全局事件问题。剩余为 7 个非阻断 Minor 与两个外部验收边界（360/400 页面级问题5、真实 IME/HITL BLOCKED）。

**可提交操作：** 仅提交
`frontend/src/components/AiPanel/AiPanel.vue`、
`frontend/src/stores/ai.ts`、
`frontend/tests/ai-panel-parts-render.spec.ts`；
不要提交 `data/`。本次审查未 commit。

**最终用户汇报必须保留的边界：**
1. composer 内部 PASS，不等于页面级 PASS；360/400 仍 FAIL（既有问题5，App.vue 外壳）。
2. 真实中文 IME BLOCKED，只有合成事件/Chromium 证据。
3. live HITL 仍 BLOCKED，不能称 #3 全量通过。
4. 未覆盖 Firefox/Safari；`field-sizing` 不支持时回退不增高。
5. 不能写“全项目全浏览器通过”。
