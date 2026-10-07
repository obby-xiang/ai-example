# GF-B 红队审查证据（DS-V4-Pro，对抗性 / 推翻式）

> 角色：反方审查 / 红队。只审查、不改代码；结论写入本文档。
> 审查对象：GF-B 生成式表单前端棒（作者 Kimi K2.8，与审查方异厂商）。
> 仓库：`<MAIN_V2>`（= `<REPO_ROOT>/ai-example-code/ai-example-main-v2`），分支 `main-v2`，HEAD `b41784a`。
> 工作区 6 项未提交改动：4 改（`AiPanel.vue` / `stores/ai.ts` / `types/ai.ts` / `types/api.ts`）+ 2 新增（`FormRenderer.vue` / `types/form-schema.ts`）。
> 审查方式：`git diff HEAD` 读 4 改 + 直接读 2 新增；对照后端源码 `backend/.../ai/`（`AiController` / `ConfirmGate` / `FrontendToolResultRequest` / `SseChatEmitter`）与 `docs/evidence/GFa-生成式表单后端工具验证.md` §8。
> 证据等级：**实测**（读代码/读源码直接确认）、**推断**（由代码路径推演、未跑 UI）、**假设**（需进一步验证）。
> 脱敏：无盘符、无密钥（统一 `***`）、无用户名。
> 审查日期：2026-10-07。

---

## 0. 结论速览

| 项 | 值 |
|---|---|
| 问题总数 | 7（中 2 / 低 3 / 信息 2） |
| 最高严重度 | **中（Medium）** |
| 总结论 | **放行带修复项** |

> 未发现高危/致命：注入面（v-html/innerHTML/属性注入）全链路干净；回灌幂等与竞态在后端 `(runId, toolCallId)` 键控下均收敛、无跨会话数据污染。两个中等问题均为"前端态耦合 + 断流路径清理遗漏"的健壮性缺陷，非可利用漏洞。

---

## 1. 问题清单

### 问题 1 —— 中（Medium）｜回灌/取消使用可变 `activeRunId`，未使用表单捕获的 `runId`（串会话错配隐患）

- **位置**：`<MAIN_V2>/frontend/src/stores/ai.ts`
  - `submitFrontendToolResult`（约 L587-623）内部 `const runId = this.activeRunId`；
  - `submitGenerativeForm`（L674-699）与 `cancelGenerativeForm`（L702-713）只传 `form.toolCallId`，走上述函数时实际用的是 `this.activeRunId`；
  - `openGenerativeForm`（L655-663）明明把 `runId`、`messageId` 存进了 `activeForm`。
- **证据（实测）**：`activeForm.runId` 与 `activeForm.messageId` 两个字段**只有写入、全仓无任何读取**（grep `.runId`/`.messageId` 的读点全部落在 `body`/`busyConflict`/`frame`/`state` 上，无一命中 `activeForm`）。作者意图上捕获了 `runId` 却从未用于回灌 —— 回灌永远走"当前轮的 `activeRunId`"。
- **复现路径（推断）**：表单在 run A 打开（`form.runId=A`，`toolCallId=T`）→ 断流（非终态 `onClose`，见问题 2）→ `loading` 落 false、`activeForm` 仍在 → 用户发新消息启动 run B → `start` 帧把 `this.activeRunId` 置为 B → 旧表单的本地超时计时器随后触发 `cancelGenerativeForm(true)` → `submitFrontendToolResult(T, …)` 用 `activeRunId=B` 发起 `POST {runId:B, toolCallId:T, cancelled:true}`。后端 `ConfirmGate.submitFrontendResult(B, T)` 查 `store.pending(B, T)` 为 null → 409 `UNKNOWN_TOOL_CALL`。
- **影响评估（推断）**：后端按 `(runId, toolCallId)` 二元组键控挂起条目（`ConfirmGate.key()`），`toolCallId` 为每次调用生成的 UUID，跨 run 碰撞概率可忽略。因此错配最坏结果是 **409 拒绝**，**不会**误消费新 run 的挂起条目、**不会**造成跨会话数据污染（无越权写入）。这是健壮性缺陷，不是可利用的安全漏洞。
- **修复建议**：`submitFrontendToolResult` 增加可选 `runId` 参数（或让 `submitGenerativeForm`/`cancelGenerativeForm` 直接传 `form.runId`），回灌/取消一律用表单创建时捕获的 `runId`；同时删除死字段 `activeForm.messageId`（或真正用它做结局帧收敛判据）。

### 问题 2 —— 中（Medium）｜断流（非终态 `onClose`）路径不清表单态与本地超时计时器

- **位置**：`<MAIN_V2>/frontend/src/stores/ai.ts` `send`（L415-426）的 `onClose` 分支只置 `streamInterrupted` 与 `notice`，**未** disarm / 未清 `activeForm`。
- **证据（实测）**：对照四条已覆盖的清理路径 —— `done`（L979-983）、`error`（L993-996）、`stop()`（L725-729）、`resetSession()`（L755-756）都调了 `disarmFormExpiry()` + `this.activeForm = null`；唯独 `onClose`（断流/缺终帧）这一条漏了。清单 #2 明确要求"done/error/停止/重置路径都清理"，而**断流是第五个真实存在的收尾出口**（ADR-6 显式提示"结果可能不完整、可重挂"）。
- **复现路径（推断）**：表单打开期间 SSE 连接中断 → `onClose(terminal=false)` 触发 → `loading=false`（`finally` 块）而 `activeForm`/计时器保留 → 计时器继续走（或用户仍能看到表单卡）。若计时器到点触发 `cancelGenerativeForm(true)`，回灌经 HTTP POST 仍能送达后端并收敛（POST 不依赖 SSE 流）；但若用户在计时器到点前发新消息启动新 run，则与问题 1 叠加：旧表单计时器把取消打到新 run 的 `activeRunId` 上。
- **影响评估（推断）**：计时器不会无限泄漏（到点必触发一次取消并 disarm），但 `activeForm` 会以 `filling`/`cancelled` 态残留在会话态里，直到下一轮的 `done`/`error`/`openGenerativeForm` 覆盖或 `resetSession`。主要危害是与问题 1 组合产生错配回灌。
- **修复建议**：`onClose` 非终态分支同样执行 `disarmFormExpiry()` + 清 `activeForm`（表单不因断流而凭空"还在填"，与"刷新后由后端超时收敛"的口径一致）；或至少在 `send` 开头（新轮启动时）无条件清理上一轮的 `activeForm`。

### 问题 3 —— 低（Low）｜字段 key 允许 `__proto__`/`constructor` 等原型键，`buildInitialValues` 用 `{}` 直接赋值

- **位置**：`<MAIN_V2>/frontend/src/types/form-schema.ts` `buildInitialValues`（L136-142）/ `initialValueOf`；后端白名单正则 `^[A-Za-z_][A-Za-z0-9_]*$`（GFa §2.2）。
- **证据（实测）**：该正则**不排除** `__proto__`、`constructor`、`prototype`（三者均满足 `[A-Za-z_][A-Za-z0-9_]*`）。`buildInitialValues` 用 `const model = {}` 后 `model[field.key] = …` 直接赋值。
- **复现路径（推断）**：模型给出 `{"form":{"scenario":"FILTER","fields":[{"key":"__proto__","label":"x","type":"multi_select","options":[{"value":"a"}]}]}}`（后端闸门放行，因为 regex 通过、选项合法）→ 前端 `model['__proto__'] = []`。对 `{}` 字面量赋 `__proto__` 为**数组（对象）**会改写该局部对象的原型，且**不产生 own property**。
- **影响评估（推断）**：该字段值不会成为 own property，因此 `{...model.value}`（own 可枚举）**不会**把 `__proto__` 传播出去，`JSON.stringify` 也不会带上它 —— 不构成可利用的全局/跨对象原型污染（model 每次新建、无 `Object.assign`/深合并攻击面）。实际后果是：`__proto__` 字段无法正常取值/回填，`checkFormValues` 读的是继承来的原型对象/数组而非预期类型，最终该字段要么被误判、要么提交被后端 400 拒。属"畸形 schema 键导致字段不可用"的健壮性问题。`constructor` 键则只是覆盖自身 own property，无安全后果。
- **修复建议**：`parseFormSchema` 增加 `key` 黑名单（`__proto__`/`constructor`/`prototype`）拒收，或 `buildInitialValues` 改用 `Object.create(null)`；并建议后端 `GenerativeFormRules` 同步补黑名单（两处同源对账）。

### 问题 4 —— 低（Low）｜400 非 `FORM_RESULT_REJECTED` 码时，前端仅特判其一，降级提示可恢复但拒绝细节不进表单卡

- **位置**：`<MAIN_V2>/frontend/src/stores/ai.ts` `submitFrontendToolResult` 的 catch（L610-621）；后端 `AiController.rejectedFrontendResult`（L535-551）。
- **证据（实测）**：后端 `rejectedFrontendResult` 存在 `verdict == null` 分支，产出 `code:"FRONTEND_TOOL_RESULT_REJECTED"`。但 `ConfirmGate.submitFrontendResult` 只有 `verdict != null && !verdict.accepted()` 才返回 `Outcome.REJECTED`（L358-363），即 **REJECTED 必带非空 verdict**，故 `verdict==null` 分支是**死代码**。当前对 `generative_form` 而言，400 拒绝码只有 `FORM_RESULT_REJECTED` 一种。前端 `ErrorCode` 已登记 `FORM_RESULT_REJECTED`/`FORM_SCHEMA_REJECTED`/`FRONTEND_CANCELLED` 三码，但 `submitFrontendToolResult` 只特判 `FORM_RESULT_REJECTED`。
- **复现路径（推断）**：若未来新增复核器（或后端改动）使某个 400 带出 `FRONTEND_TOOL_RESULT_REJECTED` 或其它码 → 前端落入通用分支 → `this.notice = message`（后端 `message` 字段进面板 `el-alert`）+ 返回 `{ok:false, message}` → `submitGenerativeForm` 置 `form.status='filling'`、`form.error='提交失败，请重试或点「取消」放弃'`。
- **影响评估（实测+推断）**：**仍可恢复**（status 回 `filling`，可重试/取消），拒绝细节也**未完全丢失**（经 `notice` 面板 Alert 展示，`AiPanel.vue` L89-94 用 `:title="ai.notice"` 文本渲染）。但表单卡内显示的是泛化文案"提交失败"，而不是后端的具体拒绝原因（如"未知字段 xxx"），**提示略误导**。非阻断。
- **修复建议**：`submitGenerativeForm` 的通用失败分支把 `outcome.message` 拼进 `form.error`；或让 `submitFrontendToolResult` 对"任何 400 + 带 `reasons` 的拒绝体"统一走 rejected 通道，而非只认单一 code。

### 问题 5 —— 低（Low）｜取消回灌失败的错误提示不可见（`status` 已 `cancelled`，错误无人渲染）

- **位置**：`<MAIN_V2>/frontend/src/stores/ai.ts` `cancelGenerativeForm`（L702-713）；`AiPanel.vue` `liveForm`（L387-391）与 fallback（L214-216）。
- **证据（实测）**：`cancelGenerativeForm` 先置 `form.status='cancelled'`，回灌失败后写 `form.error='…取消回灌未送达…后端将在超时后自动收尾'`。但 `liveForm` 只在 `status==='filling'||'submitting'` 时非空，`cancelled` 态下 `liveForm=null` → FormRenderer 不渲染 → fallback 只显示 `表单已取消` tag，**`form.error` 无任何渲染点**。
- **复现路径（推断）**：本地超时触发自动取消，但取消 POST 网络失败 → 用户看到"表单已取消"，却看不到"后端其实还在等、将超时收尾"的真相。
- **影响评估（推断）**：纯 UX/可观测性缺口，后端权威超时兜底，无正确性风险。
- **修复建议**：fallback 分支在 `cancelled` 态下额外展示 `form.error`（若有）。

### 问题 6 —— 信息（Info）｜提交/本地超时取消竞态的瞬时 `status` 覆写

- **位置**：`<MAIN_V2>/frontend/src/stores/ai.ts` `submitGenerativeForm`（L687-698）与 `cancelGenerativeForm`（L707-712）。
- **证据（实测）**：两者 `await` 回灌返回后**无条件**写 `form.status`。若"提交在途"与"本地超时取消"并发（后端 `submitLock` 串行化，先到者 ACCEPTED、后到者 409 DUPLICATE），存在：提交 ACCEPTED 后取消得 409 → 取消分支不写 status，但提交分支把已 `cancelled` 覆盖回 `submitted`（或相反）的瞬时错序。
- **影响评估（推断）**：后端为权威事实，结局帧 `frontend_tool_result` 随后清空 `activeForm`，最终收敛；仅 UI 短暂显示错误终态标签，无数据面影响。
- **修复建议**：写 `status` 前判当前 `activeForm` 是否仍指向同一 `toolCallId` 且未被清空；或统一由结局帧收敛、本地只做"pending 中"与"出错回填"两态。

### 问题 7 —— 信息（Info）｜本地超时/倒计时依赖客户端时钟（与既有确认卡同口径）

- **位置**：`<MAIN_V2>/frontend/src/stores/ai.ts` `openGenerativeForm`（L664-670）`deadline - Date.now()`；`AiPanel.vue` `formRemainingSeconds`（L395-400）。
- **证据（实测）**：`expiresAt` 为服务端 epoch 毫秒，本地 `deadline - Date.now()` 与 `deadline - now.value` 都依赖客户端时钟。若客户端时钟与服务器漂移 X，本地超时提前/滞后 X。后端 `ConfirmGate` 的 `deadline = startedAt + timeoutSeconds*1000` 才是权威。
- **影响评估（推断）**：后端权威超时兜底；滞后时用户多填一会儿提交会得 409，提前时表单提前置灰并走取消，均可恢复。与既有确认卡倒计时同口径，**非本棒新增风险**，仅登记。
- **修复建议**：可选——以 `expiresAt - (服务端心跳里的时间基准)` 或服务端下发剩余秒数修正漂移；或维持现状并在注释中标注"客户端时钟近似、后端兜底"。

---

## 2. 对抗性清单逐项裁定

### 2.1 注入 / XSS（清单 #1）—— 通过

- 【实测】全仓 `frontend/src` 无 `v-html`、无 `innerHTML`、无 `dangerouslySetInnerHTML`、无 `insertAdjacentHTML`（grep 命中仅 3 处注释，均非代码）。
- 【实测】`FormRenderer.vue` 全部 schema 文案走文本插值或属性绑定：`{{ schema.title }}`、`{{ field.label }}`、`:placeholder`、el-option `:label`、el-alert `:title`；`el-select`/`el-option` 由 Element Plus 按文本渲染，不支持 HTML 标签解析。
- 【实测】`parseFormSchema` 白名单六型 + 只认 `{value,label?}` 选项对象，`type:"html"`/`attributes`/`onClick`/`style` 等在**下发前已被后端闸门拒绝**（GFa §3.2 实测），前端即使收到也不会有渲染路径。
- **结论**：无注入面，安全。

### 2.2 回灌幂等与竞态（清单 #2）—— 收敛，但见问题 1/2/5/6

- 【实测】重复提交：`submitGenerativeForm` 首句 `form.status !== 'filling'` 拦截提交中重复点击（`status='submitting'` 同步置位先于 `await`）。
- 【实测】提交 vs 取消竞态：后端 `ConfirmGate.submitFrontendResult` 走 `submitLock` 串行 + 幂等（重复 → 409 `DUPLICATE_TOOL_CALL_ID`），先到者 ACCEPTED、后到者 409；前端两分支对 `duplicate` 均按"已收敛"处理。
- 【实测】本地超时 vs 后端超时：本地 `setTimeout(expiresAt - now)` 与后端 `deadline` 几乎同刻；本地先到 → 取消 ACCEPTED，后端先到 → `frontend_tool_result(TIMEOUT)` 帧触发 `activeForm` 清理并 `disarmFormExpiry`。结局帧 handler（L943-947）用 `activeForm?.toolCallId === frame.toolCallId` 精确匹配，不误清新表单。
- 【实测】400 重发 vs 超时到点：400 `FORM_RESULT_REJECTED` 后 `status` 回 `filling`、`error` 展示拒绝原因，可同 `toolCallId` 重发或取消；若此时超时到点，取消优先于复核（后端"取消 > 复核"），收敛。
- **残留**：断流路径未清理（问题 2）、取消失败提示不可见（问题 5）、瞬时 status 覆写（问题 6）。

### 2.3 越权 / 串会话（清单 #3）—— 无数据污染，但有错配隐患

- 【实测】`runId`/`toolCallId` 由 SSE 帧（`emit` 统一 `putIfAbsent("runId", runId)`）携带，`toolCallId` 为 UUID；后端按 `(runId, toolCallId)` 键控，跨 run 错配必得 409，**不会**误消费新 run 条目、**不会**跨会话写数据。
- 【实测】`activeRunId` 变化时旧表单回灌污染新 run 的风险：受后端二元键控封顶为 409，见问题 1。
- **结论**：无越权；但 `form.runId` 死字段 + 回灌用 `activeRunId` 是应修的耦合缺陷。

### 2.4 状态机漏洞（清单 #4）—— 未卡死

- 【实测】`submitting` 重复触发被 `status !== 'filling'` 拦截。
- 【实测】`filling → submitting → filling`（被拒）可无限循环但始终有"取消"出口（取消按钮仅在 `submitting` 禁用，被拒后回到 `filling` 即恢复可用）；后端"取消优先于复核"保证放弃永远不被复核挡住。
- 【实测】`expiresAt` 缺失 → `deadline=null` → 本地不限时，后端 120s 权威超时兜底；`expiresAt` 为过去时刻 → `setTimeout(0)` 立即取消、控件置灰，行为合理（表单已过期即收）。
- **结论**：状态机无卡死点。

### 2.5 类型与校验绕过（清单 #5）—— 通过

- 【实测】`number`：`Number.isFinite` 拒绝 NaN/Infinity（`buildInitialValues` 与 `checkFormValues` 双重把关）。
- 【实测】`date`：`DATE_PATTERN`（严格 `yyyy-MM-dd`）+ `Date.parse` 判非法日历日（`2026-02-30`/`2026-02-29` 被 `Date.parse` 判 NaN），与后端严格 ISO 判定同口径；`el-date-picker value-format="YYYY-MM-DD"` 只产合法值。
- 【实测】`multi_select`：去重（`Set.size === length`）+ 值在选项内 + 非字符串三查；`boolean` 严格 `typeof === 'boolean'`；`text` 严格 string；未知字段被 `checkFormValues` 拒绝（UI 模型只含 schema 键，属纵深防御）。
- 【实测】`parseFormSchema` 拒收后必走 `cancelled:true`：`openGenerativeForm` 的 `!spec || !runId` 分支调 `submitFrontendToolResult(frame.toolCallId, '', 'frontend-executor', true)`，绝不让通用执行器回灌说明文本（符合 GFa §8.3 / 裁决 #3）。
- **结论**：无校验绕过；原型键（问题 3）是唯一健壮性瑕疵。

### 2.6 机械验收遗留 3 条备注（清单 #6）—— 见下节逐条裁定。

---

## 3. 对 A / B / C 的逐条裁定

### A. `FormRenderer.vue:29` `el-input-number` 内联 `style="width: 100%"`

- **裁定：维持指挥官预裁（务实处理可接受），不反对。**
- 【实测】`el-input-number` 默认宽度不自适应容器，内联 `style="width:100%"` 是最小成本使其与同表单其它控件等宽；无任何安全/正确性后果。
- 【实测】仅有的"不一致"：同文件其它控件用 Tailwind `class="w-full"`，唯它用内联 style。属风格一致性 nit，不构成风险。若强求一致，改 `class="w-full"`（Tailwind 的 `width:100%` 对 EP 组件根节点同样生效）即可，但**非必需**。

### B. `FormRenderer.vue:138` 模型仅初始化建值、无 `watch(props.schema)` —— 旧值残留？

- **裁定：当前无真实复用路径导致旧值残留；属潜在脆弱点，建议低成本加固。**
- 【实测】`model` 确为 `ref(buildInitialValues(props.schema))` 一次性建值，无 `watch`。
- 【实测】组件渲染受 `v-if="liveForm && liveForm.toolCallId === m.pendingCall.toolCallId"` 门控：表单收敛（submit/cancel/done/error/结局帧）必先令 `activeForm=null` → `liveForm=null` → `v-if` 变 false → **组件卸载**；下一张表单到来时 `v-if` 变 true → **全新挂载**，`buildInitialValues` 重新执行。故跨表单不会残留旧值。
- 【推断】唯一能"不卸载就换 `props.schema`"的路径是 `openGenerativeForm` 在 `activeForm` 未清空时被直接覆写（需同一 run 内连续两帧 `frontend_tool_request` 且中间无收敛帧）—— 后端 `SpToolCallingManager` 一次只挂起一个前端工具，单 run 内不可达；reattach 回放前 `activeForm` 已随 refresh/断流不在（`activeForm` 不进镜像）。
- **结论**：当前**不存在**真实复用路径，旧值残留不成立；但"`v-if` 门控保证重挂载"是一个**未在代码里显式声明的不变量**（缺 `:key` 或 `watch` 兜底），一旦未来改动破坏该不变量即复现。建议二选一：给 `FormRenderer` 加 `:key`（如 `:key="liveForm.toolCallId"`）强制重挂载，或加 `watch(() => props.schema, ...)` 重建 `model`，成本一行。

### C. 后端 400 还可能返回 `FORM_RESULT_REJECTED` 之外的 code（`AiController` `verdict==null` 分支），前端只特判其一 —— 是否可恢复、提示是否误导？

- **裁定：行为仍可恢复；提示略误导但不误导到阻断；`verdict==null` 分支当前是死代码，非真实风险。**
- 【实测】`AiController.rejectedFrontendResult`（L539-541）`verdict == null` 时 `code="FRONTEND_TOOL_RESULT_REJECTED"`。
- 【实测】但 `ConfirmGate.submitFrontendResult`（L358-363）仅在 `verdict != null && !verdict.accepted()` 时返回 `Outcome.REJECTED`，即 **REJECTED 分支的 verdict 恒非空**；故 `verdict==null` 分支**不可达（死代码）**。对 `generative_form` 而言，当前 400 拒绝码只有 `FORM_RESULT_REJECTED`。
- 【实测】即便未来出现其它 400 码，前端落入通用分支后：`submitGenerativeForm` 置 `status='filling'`（**可恢复**，可改值重发或取消），后端 `message` 经 `this.notice` → 面板 `el-alert :title="ai.notice"` 文本展示（**拒绝细节未完全丢失**）。仅表单卡内显示泛化"提交失败"文案，未展示具体拒绝原因 —— **提示略误导**，但不阻断恢复。
- **结论**：非真实风险（死代码分支），可恢复、细节经 notice 兜底；建议把 `outcome.message` 并入 `form.error` 以消除"略误导"（同问题 4）。

---

## 4. 总结论

**放行带修复项。**

- 无高危/致命：注入面干净、幂等与竞态经后端权威收敛、无跨会话数据污染、状态机无卡死、类型校验无绕过。
- 需在合流前（或下一个 GF-B 补丁棒）修复两项中等问题：**问题 1**（回灌/取消用 `activeRunId` 而非 `form.runId`，死字段 `form.runId`/`messageId`）与**问题 2**（断流 `onClose` 路径不清表单态与计时器）；两者叠加才会产生错配回灌（最坏 409）。
- 问题 3/4/5 为低/信息级健壮性与 UX 缺口，建议顺手处理（`__proto__` 键黑名单、拒绝细节并入表单卡、取消失败提示可见化）。

（审查方未执行任何 git 写操作；本文档为唯一新增/改动文件。）
