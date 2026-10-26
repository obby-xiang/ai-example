> **历史分析 / 过程证据 — 非现行基线，非验收结论**
>
> - 归档：2026-10-26 · `docs/evidence/history-recovery-2026-10-26/wire-tail/`
> - 来源：Kimi Code 会话 `<KIMI_SESSION>` / 子 agent `agent-83` 的 wire 末条 assistant 文本（消息序号 m=40；只读流式解析，非原始会话转写）
> - 历史时点：2026-10-07 17:46 → 2026-10-07 18:15（wire 事件时间窗，UTC+8）；当时模型：`DeepSeek/deepseek-flash`
> - 原基线：`b41784a`（正文自动摘取的首次基线提及），未经本批复验
> - 原始体量：12261 B / 8545 字符 / 127 行；行指纹覆盖率（本批复算）16.5%（最高单文件 13%：`docs/evidence/history-recovery-2026-10-26/mid-thread/entries/MT-035-施工复核-验收环境-0c4dbfa1-agent-83.md`）
> - 类别：**施工复核**
> - 与既有件关系：MT-035；报告口径覆盖率 0.13
> - 脱敏：路径 2 / 会话标识 0 / 身份 0 / 密钥 0 / 命名纪律 0
> - 正文为该条消息的完整文本，未删段、未改写结论；仅脱敏、占位符归一与行尾空白规整。当时结论不代表当前代码状态，不得作为当前缺陷或有效裁决引用。
复验完成，全部证据如下（只读；未修改任何文件、未做 git 写操作；HEAD 与验收前后均为 `b41784a`）。

## 1. 范围核对 — PASS
```
$ git status --porcelain | wc -l  →  8
 M frontend/src/components/AiPanel/AiPanel.vue
 M frontend/src/stores/ai.ts
 M frontend/src/types/ai.ts
 M frontend/src/types/api.ts
?? frontend/src/components/AiPanel/FormRenderer.vue
?? frontend/src/types/form-schema.ts
?? "docs/evidence/GFb-复核验证-GLM-5.3.md"
?? "docs/evidence/GFb-红军审查-DS-V4-Pro.md"
```
恰好 4 改 + 2 新前端文件 + 2 份 docs/evidence 审查 md = 8 项，无其他新增文件。`git diff --stat` 增量：AiPanel.vue +55、stores/ai.ts +198、types/ai.ts +30/-12、types/api.ts +8/-2。`types/api.ts` 的 diff 与首轮**逐字节相同**（仅 `ErrorCode` 三码新增），且其 mtime 17:34 早于本轮修复文件（18:09–18:10）⇒ K2.8"修复棒未动 types/api.ts"属实。

## 2. 构建门 — PASS
```
$ cd frontend && yarn typecheck
$ vue-tsc --noEmit      → Done in 3.66s.      TYPECHECK_EXIT=0 ELAPSED=4s
$ cd frontend && yarn build
$ vue-tsc --noEmit && node scripts/build.mjs
✓ 1746 modules transformed.  ✓ built in 29.43s  → Done in 33.74s.
                                              BUILD_EXIT=0 ELAPSED=35s
```

## 3. 修复 1（runId 显式化 + messageId 删除）— PASS
- `stores/ai.ts:593-602`：
```ts
async submitFrontendToolResult(toolCallId, result, source = 'http-post', cancelled = false, runId?: string) {
  // GF-B 修复（红队问题 1）：表单回灌优先用表单创建时捕获的 runId …
  const targetRunId = runId ?? this.activeRunId
```
- 三个调用点：`openGenerativeForm` 解析失败分支 `ai.ts:657` `void this.submitFrontendToolResult(frame.toolCallId, '', 'frontend-executor', true, runId ?? undefined)`（该分支无 form 对象，传的是同源捕获的 `runId`，语义等价且正确）；`submitGenerativeForm` `ai.ts:694` `..., 'http-post', false, form.runId)`；`cancelGenerativeForm` `ai.ts:726` `..., 'http-post', true, form.runId)`。
- `types/ai.ts:232-243` `ActiveGenerativeForm` 现为 `toolCallId / runId / form / status / error / deadline`，**已无 messageId**。
- 残留读取点：`grep -rn "form\.messageId\|liveForm\.messageId\|activeForm\?*\.messageId" src` → **NONE**；`grep -rn messageId src` 仅剩与本棒无关的既有用途（`ChatMessage.messageId` `types/ai.ts:176,184`、SSE 帧 `types/sse.ts:99,111,140,198`、工具台账 `stores/ai.ts:885,956` 的 `messageId: frame.messageId`）⇒ 死字段零残留读取。

## 4. 修复 2（send 断流补收尾）— PASS，六出口已对齐
`stores/ai.ts:420-431`：
```ts
onClose: (info) => {
  if (!info.terminal) {
    this.streamInterrupted = true
    this.notice = `连接中断，结果可能不完整（…）…`
    if (this.activeForm !== null) {
      // 红队问题 2：断流是第五个收尾出口 —— 与 done/error/stop/resetSession 对齐
      disarmFormExpiry(); this.activeForm = null
    }
  }
}
```
全量出口清单（`grep -n "activeForm = null"`）：send.onClose `428-429`、stop `746-747`、resetSession `774-775`、`frontend_tool_result` 终帧 `964-965`、done `1000-1001`、error `1013-1014`。**唯一未收尾的出口是 `reattachActive` 的 onClose（543-548）——见第 11 项。**

## 5. 修复 3（原型键加固）— PASS（静态 + 动态）
- `types/form-schema.ts:43` `const FORBIDDEN_FIELD_KEYS = new Set(['__proto__', 'constructor', 'prototype'])`；`parseField` 命中即拒：`:72` `if (typeof raw.key !== 'string' || raw.key === '' || FORBIDDEN_FIELD_KEYS.has(raw.key) || typeof raw.label !== 'string' || raw.label === '') return null`；`buildInitialValues` `:142` `const model = Object.create(null) as Record<string, unknown>`。
- 动态复测（仓库外 `<LOCAL_WORK>\gfb2-scratch`，复制该文件后 `node --experimental-strip-types`，跑完已 `rm -rf` 删除）：
```
parse[key=__proto__]   -> null
parse[key=constructor] -> null
parse[key=prototype]   -> null
parse[legal]           -> ok scenario=FILTER fields=6
buildInitialValues proto -> null-prototype OK
initial model          -> {"name":"abc","n":3,"d":"","e":"","m":[],"b":true} keys= 6
checkFormValues(ok)    -> ["「枚举」为必填项"]     （enum 必填无默认值，符合预期）
checkFormValues(bad)   -> ["存在 schema 之外的字段：ghost","「名称」为必填项","「枚举」的取值不在选项内"]
```
临时目录已删除：`ls <LOCAL_WORK> | grep gfb2-scratch` → `temp folder removed OK`。

## 6. 修复 4（失败文案并入后端原因）— PASS
`stores/ai.ts:710-713`：
```ts
form.error = outcome.message
  ? `提交失败：${outcome.message}（可重试或点「取消」放弃）`
  : '提交失败，请重试或点「取消」放弃'
```
`outcome.message` 由 `submitFrontendToolResult` 非 409/400 分支写入（`ai.ts:622-625`，取 `error.message`）。

## 7. 修复 5（cancelled 态展示 error）— PASS
`AiPanel.vue:219-220`：
```html
<div v-if="ai.activeForm?.status === 'cancelled' && ai.activeForm.error" class="text-xs text-[#f56c6c]">
  {{ ai.activeForm.error }}
</div>
```
双大括号文本插值，无 `v-html`（全仓 v-html 命中仍为 3 处注释，见第 10 项）。

## 8. 修复 6（await 后写回过期守卫）— PASS（含一处口径说明）
- `submitGenerativeForm`：`ai.ts:694` await → `:695-698` `if (this.activeForm?.toolCallId !== form.toolCallId) { /* 结局帧/新表单已收敛 */ return }` → 之后才写 `submitted`/`filling`。守卫严格位于 await 之后、写 status 之前 ✓
- `cancelGenerativeForm`：`ai.ts:723` **await 之前**就置 `status='cancelled'`（该处必须前置，UI 当即收敛），`ai.ts:725-728` 的同一守卫保护的是 await 之后的 `form.error` 写入 ✓。两处口径一致，只是 cancel 的 status 是前置写、受保护的是后置错误写。

## 9. 修复 7（FormRenderer 按 toolCallId 定 key）— PASS
`AiPanel.vue:204-206`：
```html
<FormRenderer
  v-if="liveForm && liveForm.toolCallId === m.pendingCall.toolCallId"
  :key="liveForm.toolCallId"
```

## 10. 纪律 — PASS
- 越界路径：`git status --porcelain | awk '{print $2}' | grep -E '^backend/|\.github/|scripts/githooks|package\.json|yarn\.lock'` → **NONE**。
- `<style>` 块：全仓 3 处命中，无新增——`AiPanel.vue:575`（**既有** `<style scoped>`，diff 无 style 行，仅因插入代码位移）、`FormRenderer.vue:111`（注释）、`utils/excel.ts:610`（既有 XML 字符串）。FormRenderer.vue 本轮**未被修改**（mtime 17:36，早于修复文件 18:09–18:10），首轮登记的 `FormRenderer.vue:29` 内联 `style="width: 100%"` 仍在（是属性不是 style 块）。
- 真实 `v-html`：`grep -rn v-html frontend/src | wc -l` = 3，仍全在注释内（`FormRenderer.vue:12`、`FormRenderer.vue:111`、`types/form-schema.ts:9`）。

## 11. 【证伪任务】指挥官推断不成立 —— 该缺口**可达**（FAIL/证伪）
推断的两条前提：① send 断流已清 activeForm（**现为真**，`ai.ts:425-430`）；② reattach 运行时表单态必为空（**为假**）。反驳关键：**reattach 自己的回放流就能把表单重新打开**，所以"表单态非空"不是继承自上一次 send，而是在 reattachActive 执行期间产生的。

可复现场景（S1，最干净、且正是 UI 主动引导的流程）：
1. UI 入口：`AiPanel.vue:45-52` `v-if="ai.busyConflict"` → `<el-button @click="reattach()">一键重挂收流</el-button>`；`AiPanel.vue:487-489` `reattachActive(undefined)`。此路径的前提是"另一处会话/另一标签页正在跑"（`handleChatError` → `ai.ts:480-486` 置 busyConflict），**本标签页从未收到过该 run 的任何帧**。
2. 因此 `ai.ts:521-527` `targetRunId = busyConflict.runId`，而 `lastSeq = this.runSeq[targetRunId]` 为 `undefined`（`runSeq` 仅内存态 `ai.ts:209`，只在收到帧时写 `ai.ts:833-837`）。
3. `lastSeq === undefined` ⇒ SSE 客户端不带 `?lastSeq=`（`api/sse.ts:36-40`）⇒ **后端全量回放**（`AiController.java:285-294`，日志 `reattach runId=… lastSeq=… 回放帧数=…`）。
4. 后端回放只过滤 `delta`/`heartbeat`（`SseChatEmitter.java:358-372`），**`frontend_tool_request` 属于被归档的状态类帧**（`emit()` 取号并 `store.appendEvent`，`SseChatEmitter.java:424-441`；帧体见 `:263-274`，且 `:425` `frame.putIfAbsent("runId", …)` 保证回放帧带 runId）⇒ 该帧会被回放。
5. 前端 `handleFrame` 命中 `case 'frontend_tool_request'` → `frame.name === 'generative_form'` → `openGenerativeForm`（`ai.ts:931-937`）→ `this.activeForm = {…}`（`ai.ts:660-668`）⇒ **reattachActive 运行期间 activeForm 非 null**。
6. 若该 reattach 流随后**非终态关闭**（客户端只在重连次数耗尽/被 abort 时才回调 onClose，`api/sse.ts:441-490`；例如后端重启、代理超时、网络中断）→ `ai.ts:543-548` 只写 `streamInterrupted/notice`，**不收 activeForm** ⇒ 缺口命中。

可达性加固（S2，链式）：第一次 reattach 因第 5 步打开了表单，且其 onClose 置 `streamInterrupted=true`（`ai.ts:545`）⇒ `AiPanel.vue:78-86` 的"一键重挂该轮"按钮出现 ⇒ 第二次点击进入 `reattachActive` 时**入口处 activeForm 已非 null**（`loadHistory` 之后的任何路径都不清除它），此时缺口不需要依赖回放也成立。

反例（为什么推断"看起来对"）：同页"send 断流 → 重挂该轮"这一子路径里，表单帧此前已被收到，`runSeq[runId]` 有值，孤儿过滤（`SseChatEmitter.java:374-382`，`seq <= lastSeq` 跳过）会把回放的表单帧丢掉，activeForm 确实保持 null —— 但这只是众多路径之一，不能推出"必为空"。

影响边界（说明它为何不是致命漏）：即使缺口命中，表单仍**可收敛**——`openGenerativeForm` 按 `expiresAt`（绝对时刻）武装了定时器（若已过期则 `max(0,…)` 立即触发），到期自动 `cancelGenerativeForm(true)` 带 `cancelled:true`；且修复 1 保证回灌发往 `form.runId`，不会错配到新 run；用户手动提交/取消同样有效。真正的问题是**口径不一致**（send 断流清态、reattach 断流不清态），以及断流后表单卡仍可交互。

建议（决定权在指挥官）：既然"不可达"这一前提已被证伪，就不宜以"不可达"为由不修——最小改法是给 `ai.ts:544-547` 补与 428-430 同样的 3 行 `disarmFormExpiry(); this.activeForm = null`；若选择保留（断流后继续让用户填完，UX 上更优），则应显式登记为"有意为之"，并同时说明为何 send 断流的清理策略与之不同。

### 附加观察（不在 7 项修复内，未对活后端验证，供参考）
回放会同时把表单帧派发到**本次 reattach 新建的助手消息**（`ai.ts:528` 新消息 + `handleFrame(frame, assistant.id)`），而原始消息的 `pendingCall` 只在收到针对该 toolCallId 的终帧时才清（`ai.ts:959-963`）。由于 `AiPanel.vue:205` 的匹配条件是 `liveForm.toolCallId === m.pendingCall.toolCallId`（不含消息维度），同一 toolCallId 在两条消息上都挂着挂起卡时，**同一表单可能被渲染成两个 FormRenderer 实例共享一个 activeForm**（重复提交面）。这是本场景下的代码级推断，未做活体复现，仅登记。

## 一行总结论
**11 项中 10 项 PASS、第 11 项为证伪（缺口可达，修与不修需指挥官裁决）：范围恰 8 项无越界、typecheck（exit 0，3.7s）与 build（exit 0，35s / vite 29.4s）双绿、7 个修复点全部按描述落地并有行号与动态断言证据（原型键黑名单 + 空原型对象实测拒收、合法 schema 仍通过），未新增 `<style>` 块、无真实 v-html、未碰 backend/.github/githooks/package.json/yarn.lock；唯一未收尾出口仍是 `reattachActive` 的 onClose（`stores/ai.ts:543-548`），而"reattach 时表单态必为空"的推断被推翻——后端全量回放会把已归档的 `frontend_tool_request(generative_form)` 重新送达（409→一键重挂收流即可触发），使 activeForm 在 reattach 执行期间非空。**
