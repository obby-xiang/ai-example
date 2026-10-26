> **历史分析 / 过程证据 — 非现行基线，非验收结论**
>
> - 归档：2026-10-26 · `docs/evidence/history-recovery-2026-10-26/wire-only/`
> - 来源：Kimi Code 会话 `<KIMI_SESSION>` / 子 agent `agent-536` 的 wire 末条 assistant 文本（只读流式解析；非原始会话转写）
> - 原始体量：9749 B / 7679 字符 / 106 行；行指纹覆盖率（仓内 + 落盘语料）3.4%
> - 类别：**施工复核**
> - 脱敏：路径 2 / 会话标识 0 / 身份 0 / 密钥 0
> - 正文为该条消息的完整文本，未删段、未改写结论；仅脱敏与行尾空白规整。当时结论不代表当前代码状态。
## 1. git 核验 — PASS
```
git rev-parse HEAD      → 296ffe2ae20641ee1fe73a8409e0a0fa851ce1c6   (与预期一致)
git branch --show-current → main
git status --porcelain  → （空输出）
```

## 2. 行数核验 — PASS（5/5 全中）
| 文件 | 预期 | 实测 |
|---|---|---|
| frontend/src/components/AiPanel/AiPanel.vue | 791 | 791 |
| frontend/src/utils/parts.ts | 458 | 458 |
| frontend/src/stores/ai.ts | 1908 | 1908 |
| frontend/tests/ai-panel-parts-render.spec.ts | 243 | 243 |
| scripts/m2t2a-smoke.mjs | 488 | 488 |

## 3. AiPanel.vue :160-300 逐锚点 — PASS（全部命中，2 处文字细节需补正）
- :166-169 PASS — `v-for="part in m.parts"` + `:key="part.kind === 'tool' ? part.toolCallId : part.kind + '-' + part.seg"`
- :171 PASS — `<div v-if="part.kind === 'text' && part.text" class="whitespace-pre-wrap">`
- :174-177 PASS — `<details v-else-if="part.kind === 'reasoning' && part.text">` + `<summary class="cursor-pointer">思考过程</summary>`
- :179 PASS — `<template v-else-if="part.kind === 'tool'">`
- :181 PASS 卡根 div **无 data-testid**（`:181 <div class="mt-1.5 border border-solid …">`）
- :182-190 PASS 卡头（:184 `toolLabel(part.name)`、:185 前端工具、:186 确认门、:187-189 状态 tag）
- :191-193 PASS 参数（`v-if="part.args"` + `<code>{{ part.args }}</code>`）；:194 PASS 结果
- :195 PASS `原因：{{ part.reason }}`，类名含 `text-[#f56c6c]`（红字）
- :196 PASS 闭合 `</div>`
- :198-200 PASS 注释「批2 过渡形态：挂起卡跟随其工具段位置（③b 把两者合并成一卡之前）…」
- :201 PASS `v-if="m.pendingCall && m.pendingCall.toolCallId === part.toolCallId"` + `:data-testid="'pending-card-' + m.pendingCall.toolCallId"`
- :202-210 PASS（:204 `toolLabel(m.pendingCall.name)`、:205 需确认 tag、:206 会话已失效 tag、:208-210 `argsText(m.pendingCall.args)`）
- :212 PASS `v-if` CONFIRM+pending；:213-218 PASS 倒计时；:219-226 PASS 拒绝原因输入；:227-230 PASS 拒绝/确认按钮
- :233 PASS `v-else-if` FRONTEND+GENERATIVE_FORM_TOOL；:237-246 PASS FormRenderer（:238 宿主 `v-if="liveForm && liveForm.toolCallId === m.pendingCall.toolCallId && m.id === formHostMessageId"`）
- :247 PASS `v-else` 非宿主块
- :251 **补正**：tag 文案实为「**表单已取消**」（非「已取消」），条件 `ai.activeForm?.status === 'cancelled'`，**无 data-testid**；:252 文案「表单已提交，等待回执」；:253 PASS 表单状态已丢失
- :254-257 PASS（:254 注释「红队问题 5：取消回灌失败…」、:255 `v-if="…status === 'cancelled' && ai.activeForm.error"`、:256 `{{ ai.activeForm.error }}`）
- :259 / :265 / :266 / :267 PASS 闭合（div / div / template×2）
- :261-264 PASS 删除痕迹注释（「…按 §5 清单整列删除」）
- :269-293 PASS（:273 `v-if="m.parts.some((part) => part.kind === 'text' && part.text)"`、:277 `copy-hint-`、:286 `copy-msg-`、:293 闭合）
- :191-193 与 :194 分界与草案一致（参数块在 :191-193，结果块为 :194）

## 4. `grep -n "pendingCall"` AiPanel.vue 全量 — 15 行（PASS，完整列出）
```
200:  单槽 pendingCall 保证每条消息至多一张挂起卡。 -->
201:  <div v-if="m.pendingCall && m.pendingCall.toolCallId === part.toolCallId" :data-testid="'pending-card-' + …">
204:  <span>{{ toolLabel(m.pendingCall.name) }}</span>
205:  <el-tag v-if="m.pendingCall.kind === 'CONFIRM'" size="small" type="warning">需确认</el-tag>
206:  <el-tag v-if="m.pendingCall.status === 'expired'" size="small" type="info">会话已失效</el-tag>
208:  <div v-if="argsText(m.pendingCall.args)" class="mt-1.5 text-xs text-[#909399] break-all">
209:  <code>{{ argsText(m.pendingCall.args) }}</code>
212:  <template v-if="m.pendingCall.kind === 'CONFIRM' && m.pendingCall.status === 'pending'">
233:  <div v-else-if="m.pendingCall.kind === 'FRONTEND' && m.pendingCall.name === GENERATIVE_FORM_TOOL" …>
238:  v-if="liveForm && liveForm.toolCallId === m.pendingCall.toolCallId && m.id === formHostMessageId"
262:  （原 :280-286）在 D8 关旁路后均不可达（终态段 requiresAction=false ⇒ pendingCall
463: * 结局帧到达后 activeForm 被清空，挂起卡随 pendingCall 一并消失）。
482:  const pending = ai.messages[i]?.pendingCall
640: * （reasoning 段）、不含工具卡（tool 段 / pendingCall），**不做 trim**（正文按
730:  return `…|${last && last.pendingCall ? last.pendingCall.status : ''}`
```
模板区实际出现 **9 处**（:201/:204/:205/:206/:208/:209/:212/:233/:238），脚本区 5 处（:482/:730 + 3 条注释）。草案卡点「挂起卡只在 :201 一处 data-testid」成立。

## 5. 其余锚点 — PASS（全中）
- :356-363 PASS imports（`computed, nextTick, onBeforeUnmount, onMounted, ref, watch` / icons / `useAiStore, foldDeadline` / FormRenderer / `GENERATIVE_FORM_TOOL, ChatMessage, PendingToolCall, ToolRunStatus`）
- :446 PASS `if (ai.suspended || ai.pendingToolCall)` → `'挂起等待中：请先在上方处理确认/执行，或点「停止」结束本轮'`（:446-448）
- :465-468 PASS `liveForm` = `status === 'filling' || 'submitting'` 才返回，否则 null
- :471-474 PASS 注释（T2a-D#3 宿主 + 「T2a 返修（S5）：宿主候选限定 pending/running/approved」）
- :476-489 PASS `formHostMessageId` 逆序遍历；:484 恰为 `(pending.status === 'pending' || … 'running' || … 'approved')`
- :515-542 PASS `toolTagType` 九态显式 case 无 default：pending/running/succeeded/failed/rejected/blocked/expired/cancelled/unknown（=9）；注：switch 后无兜底 return（穷尽闸依赖 TS 联合穷尽）
- :544-560 PASS `toolStatusLabel` 为 `Record<ToolRunStatus, string>`，:553 `expired: '会话已失效'`
- :730 PASS 滚动指纹末段 `…${last && last.pendingCall ? last.pendingCall.status : ''}`
- :741-745 PASS `watch(() => ai.pendingConfirm, (pending) => resetPendingWatch(pending), { immediate: true })`

## 6. spec 文件全读 — PASS
`describe` 2 个（:74 批2 段序、:153 批3③b 三路单卡锁）。7 个 `it(` 行号与预期一致：**:75 / :94 / :119 / :134 / :165 / :186 / :217**。

第一个 describe 4 例断言：
- :75 段序 — `wrapper.text()` 中 `indexOf('先说话') < indexOf('列出配置定义') < indexOf('后说话')`（工具不沉底）
- :94 单槽/位置 — `findAll('[data-testid^="pending-card-"]')` **`toHaveLength(1)`**、`cards[0].attributes('data-testid') === 'pending-card-c1'`；:105-115 位置：`html.indexOf('启动导出作业') < html.indexOf('pending-card-c1') < html.indexOf('列出任务')`（挂起卡夹在 c1 与 c2 工具卡之间、不下沉）
- :119 N10 复制判据 — 构造 parts/content 互相矛盾的 2 条消息，断言 `findAll('[data-testid^="copy-msg-"]')` **`toHaveLength(1)`**、testid 为 `copy-msg-a-1`（只有 parts 有正文那条出按钮）、`text()` 含「段里有正文」
- :134 typing 判据 — 流式中且无非空 text 段才出；`findAll('.typing')` **`toHaveLength(1)`**、`text()` 含「正在说」

批3③b 三路单卡锁（计数写法**三例完全同型**）：
- :165 重投路 — 触发 `store.handleFrame({type:'frontend_tool_request', toolCallId:'r1', …}, old.id)`；断言 `findAll('[data-testid^="pending-card-"]')` **`toHaveLength(0)`** + `store.messages.flatMap(m => m.parts.filter(p => p.kind==='tool' && p.toolCallId==='r1'))` **`toHaveLength(1)`**
- :186 新宿主路 — `api.reattachRun` 注入 request/suspended/frontend_tool_result 三帧；同型 0 卡 + 1 段，另加 `segments[0]` `toMatchObject({ status: 'unknown' })`
- :217 历史整替路 — `api.history` 返回 h1 单工具调用 + `loadHistory()`；同型 0 卡 + 1 段（选择器均 `[data-testid^="pending-card-"]`，断言均为 `toHaveLength(0)`）

## 7. frontend/tests/ 清单与 `it(` 计数 — PASS
11 个 spec 文件（另 setup.ts）：ai-panel-parts-render 7、export-wizard-step-outcome 5、frontend-tool-outcome 9、frontend-tool-result-terminal 30、parts-double-write 5、parts-reducer 15、parts-residue-cleanup 22、ready 4、restore-reconcile 3、sse-watchdog 4、tools-and-deadline 9 → **总计 113**（两种口径 `^\s*it(` 与 `\bit(` 均为 113，无 it.skip/todo 差异）。

## 8. frontend/vitest.config.ts include — PASS
```ts
environment: 'jsdom',
include: ['tests/**/*.spec.ts'],
setupFiles: ['./tests/setup.ts']
```

## 9. grep 结果
- `tag-form-cancelled` 于 **frontend/ 全域 = 0 命中**（PASS，与预期一致）。但**该 testid 仅存在于 scripts/m2t2a-smoke.mjs:53 `const TESTID_TAG_CANCELLED = 'tag-form-cancelled'`**（另 :39/:166 注释、:176 选择器）——脚本里的 `cancelledTags` 计数对应的前端元素已不存在，属死断言口径，建议在施工卡注记（不是本次核验失败项）。
- `pending-card-` 出现处（排除 untracked 构建产物与 docs）：
  - frontend/src/components/AiPanel/AiPanel.vue:**201**（唯一实现点）
  - frontend/tests/ai-panel-parts-render.spec.ts:**106, 108, 112, 150, 180, 210, 238**
  - scripts/m2t2a-smoke.mjs:**29, 39, 165, 172, 173**
  - 另：`frontend/dist/assets/index-DOL-ciHL.js`（1 个打包产物命中；`frontend/dist/` 在 .gitignore:17 且 git 未跟踪，非源码）；docs/evidence/*.md 8 篇引用（补档-CHATTIMELINE-批3③b 系列等）
  - 附带核对：m2t2a-smoke.mjs:173 的 `=== 'pending-card-${toolCallId}'` 写在生成用模板串内，外层模板字面量会完成插值，**不是遗漏插值的 bug**。

**红线遵守**：全程只读，未执行任何 git 写或文件写；未访问 `<LOCAL_WORK>\ai-example`、`<LOCAL_WORK>\ai-example-code\ai-example-main`；未读取任何 `.env`。

**模型自证**：子智能体无法自证模型。
