> **历史分析 / 过程证据 — 非现行基线，非验收结论**
>
> - 归档：2026-10-26 · `docs/evidence/history-recovery-2026-10-26/wire-tail/`
> - 来源：Kimi Code 会话 `<KIMI_SESSION>` / 子 agent `agent-84` 的 wire 末条 assistant 文本（消息序号 m=8；只读流式解析，非原始会话转写）
> - 历史时点：2026-10-07 18:00 → 2026-10-07 18:01（wire 事件时间窗，UTC+8）；当时模型：`DeepSeek/deepseek-flash`
> - 原基线：`b41784a`（正文自动摘取的首次基线提及），未经本批复验
> - 原始体量：24814 B / 14944 字符 / 129 行；行指纹覆盖率（本批复算）7.5%（最高单文件 8%：`docs/evidence/history-recovery-2026-10-26/mid-thread/entries/MT-035-施工复核-验收环境-0c4dbfa1-agent-83.md`）
> - 类别：**正式调研 / 设计 / 裁决 / 分析**（原判：裁决）
> - 与既有件关系：GFb-红队审查；报告口径覆盖率 0.08
> - 脱敏：路径 2 / 会话标识 0 / 身份 3 / 密钥 0 / 命名纪律 9
> - 正文为该条消息的完整文本，未删段、未改写结论；仅脱敏、占位符归一与行尾空白规整。当时结论不代表当前代码状态，不得作为当前缺陷或有效裁决引用。
已完成四项检查。以下为逐项如实回报。

## 1. git status --porcelain（仓库 `ai-example-code/<REPO>`）

实测输出（8 项，与"6 项前端改动 + 2 份 docs/evidence 新文档"一致）：

```
 M frontend/src/components/AiPanel/AiPanel.vue
 M frontend/src/stores/ai.ts
 M frontend/src/types/ai.ts
 M frontend/src/types/api.ts
?? docs/evidence/GFb-复核验证-GLM-5.3.md
?? docs/evidence/GFb-红队审查-DS-V4-Pro.md
?? frontend/src/components/AiPanel/FormRenderer.vue
?? frontend/src/types/form-schema.ts
```
4 改（前端）+ 2 新增（前端）+ 2 新增（docs/evidence）。两份文档的 mtime 为 17:55（红队）与 17:59（复核）。

## 2. `docs/evidence/GFb-红队审查-DS-V4-Pro.md`

**元信息**：角色反方红队，审查对象 GF-B 生成式表单前端棒（作者 Kimi K2.8，异厂商）；仓库 `<REPO>`，分支 `<REPO>`，HEAD `b41784a`；审查方式 `git diff HEAD` 读 4 改 + 直读 2 新增，对照后端 `AiController`/`ConfirmGate`/`FrontendToolResultRequest`/`SseChatEmitter` 与 GFa §8。结论速览：问题 7 条（中 2 / 低 3 / 信息 2），最高严重度中，总结论"放行带修复项"。

### 问题清单

**问题 1 — 中 ｜ 回灌/取消使用可变 `activeRunId`，未用表单捕获的 `runId`（串会话错配隐患）**
- 位置：`frontend/src/stores/ai.ts`：`submitFrontendToolResult`（约 L587-623）内 `const runId = this.activeRunId`；`submitGenerativeForm`（L674-699）、`cancelGenerativeForm`（L702-713）只传 `form.toolCallId`；而 `openGenerativeForm`（L655-663）已把 `runId`/`messageId` 存入 `activeForm`。
- 证据要点【实测】：`activeForm.runId` 与 `activeForm.messageId` 两字段"只有写入、全仓无任何读取"。
- 复现路径【推断】：表单在 run A 打开 → 断流 → `loading` 落 false 而 `activeForm` 仍在 → 用户发新消息启动 run B（`start` 帧置 `activeRunId=B`）→ 旧表单本地超时计时器触发 `cancelGenerativeForm(true)` → 用 `runId=B` 发 `POST {runId:B, toolCallId:T, cancelled:true}` → `ConfirmGate.submitFrontendResult(B,T)` 查 `pending(B,T)` 为 null → 409 `UNKNOWN_TOOL_CALL`。
- 影响【推断】：后端按 `(runId, toolCallId)` 二元键控、`toolCallId` 为每次生成的 UUID，故最坏是 409 拒绝，不会误消费新 run 挂起条目、无跨会话数据污染；属健壮性缺陷非可利用漏洞。
- 修复建议：`submitFrontendToolResult` 增可选 `runId` 参数（或让 submit/cancel 直传 `form.runId`），回灌/取消一律用表单创建时捕获的 `runId`；同时删除死字段 `activeForm.messageId`（或真正用作结局帧收敛判据）。

**问题 2 — 中 ｜ 断流（非终态 `onClose`）路径不清表单态与本地超时计时器**
- 位置：`stores/ai.ts` `send` 的 `onClose` 分支（L415-426）只置 `streamInterrupted` 与 `notice`，未 disarm、未清 `activeForm`。
- 证据要点【实测】：`done`（L979-983）、`error`（L993-996）、`stop()`（L725-729）、`resetSession()`（L755-756）四条路径都调了 `disarmFormExpiry()` + `activeForm = null`，唯独断流这一条漏了；而清单 #2 要求"done/error/停止/重置路径都清理"，断流是第五个真实收尾出口（ADR-6 明确提示结果可能不完整、可重挂）。
- 复现路径【推断】：表单打开期间 SSE 中断 → `onClose(terminal=false)` → `loading=false`（finally）而 `activeForm`/计时器保留 → 计时器到点触发取消回灌（POST 不依赖 SSE，可送达收敛）；但若用户在到点前发新消息启动新 run，即与问题 1 叠加造成错配回灌。
- 影响【推断】：计时器不会无限泄漏（到点必触发一次取消并 disarm），但 `activeForm` 会以 `filling`/`cancelled` 态残留至下一轮 `done`/`error`/`openGenerativeForm` 覆盖或 `resetSession`；主要危害是与问题 1 组合。
- 修复建议：`onClose` 非终态分支同样执行 `disarmFormExpiry()` + 清 `activeForm`；或在 `send` 开头无条件清理上一轮 `activeForm`。

**问题 3 — 低 ｜ 字段 key 允许 `__proto__`/`constructor` 等原型键，`buildInitialValues` 用 `{}` 直接赋值**
- 位置：`frontend/src/types/form-schema.ts` `buildInitialValues`（L136-142）/`initialValueOf`；后端白名单正则 `^[A-Za-z_][A-Za-z0-9_]*$`（GFa §2.2）不排除 `__proto__`/`constructor`/`prototype`。
- 证据/复现【实测+推断】：模型下发 `key:"__proto__"` 的字段（regex 通过、选项合法，后端闸门放行）→ `model['__proto__'] = []` 对 `{}` 字面量赋对象值只改写局部对象原型、不产生 own property。
- 影响【推断】：`{...model.value}` 与 `JSON.stringify` 都不会传播它，不构成可利用的原型污染（model 每次新建、无 `Object.assign`/深合并攻击面）；实际后果是该字段无法正常取值/回填、`checkFormValues` 读到继承的原型对象，最终误判或被后端 400 拒。`constructor` 键仅覆盖自身 own property，无安全后果。
- 修复建议：`parseFormSchema` 加 key 黑名单（`__proto__`/`constructor`/`prototype`）拒收，或 `buildInitialValues` 改用 `Object.create(null)`；建议后端 `GenerativeFormRules` 同步补黑名单（两处同源对账）。

**问题 4 — 低 ｜ 400 非 `FORM_RESULT_REJECTED` 码时前端只特判其一，降级提示可恢复但拒绝细节不进表单卡**
- 位置：`stores/ai.ts` `submitFrontendToolResult` catch（L610-621）；后端 `AiController.rejectedFrontendResult`（L535-551）。
- 证据要点【实测】：后端存在 `verdict == null` 分支产出 `code:"FRONTEND_TOOL_RESULT_REJECTED"`，但 `ConfirmGate.submitFrontendResult` 仅 `verdict != null && !verdict.accepted()` 时返回 `Outcome.REJECTED`（L358-363）→ REJECTED 必带非空 verdict，该分支是死代码；`generative_form` 当前 400 拒绝码只有 `FORM_RESULT_REJECTED`。前端 `ErrorCode` 已登记 `FORM_RESULT_REJECTED`/`FORM_SCHEMA_REJECTED`/`FRONTEND_CANCELLED` 三码，却只特判其一。
- 复现路径【推断】：未来若出现别的 400 码 → 落通用分支 → `this.notice = message`（面板 `el-alert :title="ai.notice"`，`AiPanel.vue` L89-94）并返回 `{ok:false,message}` → `form.status='filling'`、`form.error='提交失败，请重试或点「取消」放弃'`。
- 影响【实测+推断】：仍可恢复、细节经 notice 未完全丢失，但表单卡内只显示泛化文案而非具体拒绝原因（如"未知字段 xxx"），提示略误导，非阻断。
- 修复建议：`submitGenerativeForm` 通用失败分支把 `outcome.message` 拼进 `form.error`；或对"任何 400 + 带 `reasons` 的拒绝体"统一走 rejected 通道。

**问题 5 — 低 ｜ 取消回灌失败的错误提示不可见（`status` 已 `cancelled`，错误无人渲染）**
- 位置：`stores/ai.ts` `cancelGenerativeForm`（L702-713）；`AiPanel.vue` `liveForm`（L387-391）与 fallback（L214-216）。
- 证据要点【实测】：`cancelGenerativeForm` 先置 `status='cancelled'`，回灌失败后写 `form.error='…取消回灌未送达…后端将在超时后自动收尾'`；但 `liveForm` 仅在 `filling`/`submitting` 时非空，`cancelled` 下为 null → FormRenderer 不渲染，fallback 只显示"表单已取消"tag，`form.error` 无渲染点。
- 复现路径【推断】：本地超时触发自动取消但取消 POST 网络失败 → 用户只看到"表单已取消"，看不到后端仍在等、将超时收尾。
- 影响【推断】：纯 UX/可观测性缺口，后端权威超时兜底，无正确性风险。修复建议：fallback 在 `cancelled` 态下额外展示 `form.error`（若有）。

**问题 6 — 信息 ｜ 提交/本地超时取消竞态的瞬时 `status` 覆写**
- 位置：`stores/ai.ts` `submitGenerativeForm`（L687-698）与 `cancelGenerativeForm`（L707-712）。
- 证据/复现【实测+推断】：两者 `await` 后无条件写 `form.status`；并发时（后端 `submitLock` 串行，先到者 ACCEPTED、后到者 409 DUPLICATE）存在提交分支把已 `cancelled` 覆盖回 `submitted`（或相反）的瞬时错序。
- 影响【推断】：后端为权威事实，结局帧随后清空 `activeForm` 最终收敛，仅 UI 短暂显示错误终态标签，无数据面影响。修复建议：写 `status` 前判当前 `activeForm` 是否仍指向同一 `toolCallId` 且未被清空；或统一由结局帧收敛。

**问题 7 — 信息 ｜ 本地超时/倒计时依赖客户端时钟（与既有确认卡同口径）**
- 位置：`stores/ai.ts` `openGenerativeForm`（L664-670）`deadline - Date.now()`；`AiPanel.vue` `formRemainingSeconds`（L395-400）。
- 证据/影响【实测+推断】：`expiresAt` 为服务端 epoch 毫秒，本地计算依赖客户端时钟，漂移 X 则超时提前/滞后 X；后端 `ConfirmGate` 的 `deadline = startedAt + timeoutSeconds*1000` 才权威。滞后时提交得 409、提前时表单提前置灰走取消，均可恢复；与既有确认卡同口径，**非本棒新增风险**。修复建议（可选）：以服务端时间基准或下发剩余秒数修正漂移，或维持现状并注释标注。

### 对 A/B/C 的逐条裁定

- **A（`FormRenderer.vue:29` `el-input-number` 内联 `style="width: 100%"`）**：裁定 "维持指挥官预裁（务实处理可接受），不反对"。理由：`el-input-number` 默认宽度不自适应容器，内联 style 是最小成本使其与同表单其它控件等宽，无安全/正确性后果；唯一"不一致"是同文件其它控件用 Tailwind `class="w-full"`，属风格 nit；若强求一致可改 `class="w-full"`（对 EP 组件根节点同样生效），但**非必需**。
- **B（`FormRenderer.vue:138` 无 `watch(props.schema)`，旧值残留？）**：裁定 "当前无真实复用路径导致旧值残留；属潜在脆弱点，建议低成本加固"。理由：`model` 确为 `ref(buildInitialValues(...))` 一次性建值；组件受 `v-if="liveForm && liveForm.toolCallId === m.pendingCall.toolCallId"` 门控，表单收敛必先令 `activeForm=null` → `liveForm=null` → `v-if` false → **卸载**，下一张表单到来时全新挂载重跑 `buildInitialValues`，故跨表单不残留【实测】。唯一能"不卸载换 schema"的路径需同一 run 内连续两帧 `frontend_tool_request` 且中间无收敛帧，后端 `SpToolCallingManager` 一次只挂起一个前端工具，单 run 内不可达；reattach 回放前 `activeForm` 已随 refresh/断流不在【推断】。结论：旧值残留不成立，但"`v-if` 门控保证重挂载"是**未在代码里显式声明的不变量**，建议加 `:key="liveForm.toolCallId"` 或 `watch(() => props.schema, ...)`，成本一行。
- **C（后端 400 可能返回 `FORM_RESULT_REJECTED` 之外的 code，前端只特判其一 —— 是否可恢复、提示是否误导？）**：裁定 "行为仍可恢复；提示略误导但不误导到阻断；`verdict==null` 分支当前是死代码，非真实风险"。理由：`AiController.rejectedFrontendResult`（L539-541）`verdict == null` 时 `code="FRONTEND_TOOL_RESULT_REJECTED"`，但 `ConfirmGate.submitFrontendResult`（L358-363）仅 `verdict != null && !verdict.accepted()` 才 REJECTED，故该分支不可达（死代码），当前 400 拒绝码只有 `FORM_RESULT_REJECTED`【实测】；即便未来出现其它码，前端落通用分支后 `status='filling'` 可恢复、后端 message 经 `this.notice` → `el-alert :title` 文本展示（细节未完全丢失），仅表单卡内显示泛化"提交失败"——提示略误导，不阻断。建议把 `outcome.message` 并入 `form.error`（同问题 4）。

### 总结论

**放行带修复项。** 无高危/致命：注入面干净、幂等与竞态经后端权威收敛、无跨会话数据污染、状态机无卡死、类型校验无绕过。需在合流前（或下一个 GF-B 补丁棒）修复两项中等：问题 1（回灌/取消用 `activeRunId` 而非 `form.runId`，死字段 `form.runId`/`messageId`）与问题 2（断流 `onClose` 路径不清表单态与计时器），两者叠加才产生错配回灌（最坏 409）；问题 3/4/5 建议顺手处理。另注：审查方未执行任何 git 写操作。

另外该文档记录了 5 项对抗性清单逐项裁定：注入/XSS 通过（全仓无 v-html/innerHTML/属性注入，仅 3 处注释命中）；回灌幂等与竞态"收敛，但见问题 1/2/5/6"；越权/串会话"无数据污染，但有错配隐患"；状态机"未卡死"；类型与校验绕过"通过"（`__proto__` 键问题 3 为唯一瑕疵）。

## 3. `docs/evidence/GFb-复核验证-GLM-5.3.md`

**元信息**：角色独立复核（GLM-5.3），不采信作者与验收者结论、一切自己重跑；只验证不改代码；仓库 `<REPO>`，分支 `<REPO>`，HEAD `b41784a`；环境 Windows（Git Bash）、node v22.23.2 / yarn 1.22.22；约束：全程无 git 写操作、除本文档与仓库外临时目录外零文件改动、零新增依赖。

### 逐项 PASS/FAIL 与关键证据

- **项 1 git 状态与改动范围：PASS**【实测】。`git rev-parse HEAD` → `b41784a6e8eb1c809b442dad3e4d2052ecc2f385`，`git branch --show-current` → `<REPO>`；`git status --porcelain` 恰好 6 项（4 改 + 2 新增），与题目描述逐项一致；`git diff --stat` 全部落子 `frontend/src/`（4 文件，+255/−10）；未触碰 `backend/`、`.github/`、`scripts/githooks`、`package.json`、`yarn.lock`。复核窗口内出现的第 7 项（红队报告 .md）是并行第三方写入，不计入作者改动。
- **项 2 typecheck 与 build：PASS**【实测】。`yarn typecheck`（`vue-tsc --noEmit`）`Done in 3.84s.`，**EXIT_CODE=0（约 4s）**；`yarn build`（`vue-tsc --noEmit && node scripts/build.mjs`）vite v6.4.4、`✓ 1746 modules transformed.`、`✓ built in 30.02s`、`Done in 34.43s.`，**EXIT_CODE=0（约 35s）**；build 产物含全部预期 chunk（element/spreadjs/vendor 等，无报错无警告中断）。
- **项 3 `types/form-schema.ts` 动态断言：PASS（40/40）**【实测】。方法：原样复制该文件到仓库外临时目录，自写 `assertions.mts`（仅 import 两个导出），`node --experimental-strip-types assertions.mts` 运行，跑完 `rm -rf`（已确认删除），不入仓、零依赖。分项：
  - `parseFormSchema` 非法输入一律 null —— **20 例全过**（非对象 null/数组/JSON 字符串/数字 4 例；缺 scenario / scenario 空串；fields 缺失 / fields 空数组；非法 type `richtext` / 缺 type；重复 key；enum 无 options / options 空数组；裸字符串 options `['a','b']`；multi_select 无 options；字段非对象 / key 空串 / title 非字符串 / options 含重复 value / 选项缺 value 5 例）。
  - 合法 schema —— **7 例全过**（六型字段全量解析成功，scenario/title/六字段/选项 `label` 可省/非 enum 字段不强求 options；无 title 时通过且产物不含 title 键）。
  - `checkFormValues` —— **13 例全过**（必填缺失含文案 `「关键词」为必填项`；未知字段 `extra` → `存在 schema 之外的字段：extra`；enum 越界 `lvl:'mid'` → `取值不在选项内`；date 坏格式 `2026/01/01`、`2026-13-45` → `应为 yyyy-MM-dd 格式的合法日期`，形似但日历非法也被 `Date.parse` 拦；multi_select 越界 `['a','z']` / 重复 `['a','a']` → `应在选项内且不重复`；number/boolean/text 类型不符 + 合法全量提交 0 问题）。
  - 过程：首轮 **39/40**，唯一 FAIL（C09）经核查为**本复核断言用例自身设计缺陷**（用例值对象漏带必填字段 `lvl`，源码行为正确），修正用例后 40/40，`EXIT_CODE=0`。非源码问题。
- **项 4 契约静态核对：PASS（红队复核出的 2 个中等问题除外，见下）**【实测】，逐条：(1) `generative_form` 特判不落通用执行器兜底（`handleFrame` 命中 `frame.name === 'generative_form'` → `openGenerativeForm` 后立即 `break`，submit/cancel 独立于执行器通道）；(2) 取消与 schema 解析失败路径均带 `cancelled:true`（解析失败 `submitFrontendToolResult(frame.toolCallId, '', 'frontend-executor', true)` 且 result 传空串不回灌说明文本；显式取消；本地超时自动取消；两种载荷互斥构造）；(3) 400/409 分支区分（`ErrorCode` 登记 `FORM_RESULT_REJECTED`(400)、`FORM_SCHEMA_REJECTED`(400)、`FRONTEND_CANCELLED`(终态)、`DUPLICATE_TOOL_CALL_ID`(409)、`UNKNOWN_TOOL_CALL`(409)；catch 先判 409 duplicate 再判 400 rejected 且不置 notice 打断填写，其余落通用失败分支；与后端 `AiController.frontendToolResult` NOT_FOUND/DUPLICATE→409、REJECTED→400 对齐）；(4) 本地超时自动 `cancelled:true` 回灌，`disarmFormExpiry` 在 done/error/stop/resetSession/结局帧/新表单六处清理；(5) 与后端 DTO/Rules 对齐（`FrontendToolResultRequest` 字段级一致、`cancelled` 为可空 Boolean 缺省 false 老客户端零改动；`GenerativeFormRules` 两码与前端逐字一致、六型白名单一致、options 只收 `{value,label?}` 一致、日期严格 ISO 同口径；前端注释明示"只做防御性兜底、不重复后端校验"属设计上的宽松，非遗漏）。
- **项 5 视觉纪律抽查（FormRenderer.vue）：PASS**【实测】。`grep '<style'` 零命中（文件仅 `<template>` + `<script setup>` 两段，共 161 行）；`grep 'v-html'` 命中 2 处均为注释（L12、L111 头注释），非代码命中；模板全 el-* 组件（`el-tag`/`el-input`/`el-input-number`/`el-switch`/`el-date-picker`/`el-select`+`el-option`/`el-alert`/`el-button` 共 13 处用法），文案全部 `{{ }}` 插值或 prop 绑定；唯一内联样式为 L29 `el-input-number` 的 `style="width: 100%"`，功能必要、无安全影响，与红队 A 项裁定一致。
- **项 6 作者"手动冒烟说明（纯前端构造帧路径）"可行性审查：PASS（附两点说明）**。前提说明【实测】：在仓库与 `<TEMP>` 工作区（`gfb-scratch/`、`gfb-e2e/`、`gfb-*.log`）均**未定位到作者完工报告正文文件**（仅有作者的 typecheck/build 日志、断言草稿与一份不含 generative_form 用例的 S5b 22 用例 E2E 回归日志），故无法逐字核对原文，以下为独立审查的逻辑可行性结论。结论【推断】：路径成立（`handleFrame`/`openGenerativeForm` 均为 Pinia store 公开 action，可 console 直接调；构造含 `name:'generative_form'`、`toolCallId`、`runId`、`timeoutSeconds`/`expiresAt`、`args`（form schema 的 JSON 字符串）的帧即可驱动全链路；空会话时 `currentAssistantMessage()` 自动 `appendAssistantMessage()`，无需前置造消息）。两点注意：a) 帧必须自带 `runId`（或先构造 `start` 帧置 `activeRunId`），两者皆缺时表单不渲染且不发回灌（`submitFrontendToolResult` 在 `activeRunId` 为空时本地返回 `{ok:false}`）；b) 前端 `parseFormSchema` 不校验 scenario 白名单（只查非空串），任意非空 scenario 均能渲染（FormRenderer 仅 `FILTER` 显示"筛选条件"，其余显示"信息确认"），对齐后端应构造 `FILTER`/`CLARIFY`。交互闭环限制【推断】：提交/取消会真实 `POST /api/ai/frontend-tool-result`，纯前端（无后端在线）只能验证"渲染 + 客户端复核拦截 + 失败分支文案"，回灌闭环需后端在线——这是该冒烟路径的固有边界，非步骤错误。未发现明显不可执行步骤，但因报告原文缺失，结论以代码路径可行性为限并标注【推断】。

### 与作者报告 / 机械验收不一致处（逐字摘录）

1. **工作区 6 项 → 7 项（环境事实差异）**【实测】："复核进行中（约 17:55）第三方红队（DS-V4-Pro）向 `docs/evidence/GFb-红队审查-DS-V4-Pro.md` 写入了审查报告，工作区未提交项由 6 变 7（新增该未跟踪 .md）。该文件非本复核产物，属并行流程写入；除它之外工作区与题目描述一致。"
2. **作者完工报告正文未找到**【实测】："`gfb-*` 工作目录只留有构建/断言/E2E 产物，无报告正文。项 6 的冒烟审查因此降级为'代码路径可行性'级别……**无法对作者原文逐字核对**——这一点与'作者报告里写了手动冒烟说明'的前提不一致，如实记录。"
3. **对红队报告两个中等问题（问题 1/2）的独立验证：均属实**【实测】："本复核不采信红队结论、自行 grep/读码复现"——问题 1：`activeForm.runId`/`activeForm.messageId` 全仓**零读点**（`grep activeForm\.(runId|messageId)` 无命中），写入后从未使用，submit/cancel 实际用的都是 `this.activeRunId`；断流残留 + 新轮启动组合下最坏跨 run 错配回灌（后端 `(runId, toolCallId)` 键控封顶 409，无数据污染）。问题 2：`send` 的 `onClose` 非终态分支只置 `streamInterrupted`/`notice`，**未** `disarmFormExpiry`、**未**清 `activeForm`；而 done/error/stop/resetSession 四路径均清理，断流是第五个收尾出口，确实遗漏。并注明："本复核对项 4 的 PASS 判定不受影响：上述两问题不违反本复核清单的任何一条契约断言……属健壮性缺陷而非契约违背——与红队'放行带修复项'的定级相容。"
4. 其余各项（typecheck/build 结果、断言行为、视觉纪律、契约字段对齐）**未发现**与既有证据材料相矛盾之处【实测】。

### 总结论

**6 项验证全部 PASS**：改动范围干净（仅前端 6 文件）、typecheck/build 双绿（exit 0，3.84s / 34.43s）、`form-schema.ts` 防御性解析与复核 40/40 断言全过（非法 schema 一律 null + cancelled:true 收敛、六型取值复核齐备）、前后端契约字段级对齐（`cancelled` 可选字段、`FORM_*`/`DUPLICATE` 错误码、400/409 语义）、视觉纪律达标（无真实 style 块、无 v-html 代码命中、全 el-* 组件）、纯前端构造帧冒烟路径逻辑可行。附带两点如实记录：作者完工报告正文未找到（项 6 以代码可行性为限）；红队两个中等问题经独立验证属实，属健壮性缺陷、不违反本清单契约，建议随红队意见在下个补丁棒修复。（本复核未执行任何 git 写操作；除本文档与已删除的临时断言目录外未改动任何文件；未引入任何依赖。）

## 4. 脱敏检查

对两份文档执行 grep（盘符 `[A-Za-z]:[\\/]`、`<DRIVE>\Users`、`sk-` 开头密钥、`<USER>`、邮箱、`http(s)://`、`127.0.0.1`、`192.168`、`localhost`、`Bearer`、`token`）：

| 模式 | GFb-红队审查-DS-V4-Pro.md | GFb-复核验证-GLM-5.3.md |
|---|---|---|
| 真实盘符（`E:\`、`<DRIVE>\Users` 等） | 0 命中 | 0 命中 |
| `sk-` 开头密钥 | 0 命中 | 0 命中 |
| `<USER>` 字样 | 0 命中 | 0 命中 |
| 邮箱 / IP / URL / Bearer / token | 0 命中 | 0 命中 |
| `***` | 1 处（仅脱敏声明行） | 1 处（仅脱敏声明行） |

唯一与"盘符"字面相关的命中是两份文档各自的脱敏声明行（"无盘符、无密钥（统一 `***`）、无用户名。"/"不出现真实盘符（统一 `<REPO>`）、密钥（统一 `***`）、用户名。"），非泄漏。两份文档占位符使用一致：`<REPO>`、`<REPO_ROOT>`、`<TEMP>`。**未发现真实盘符、密钥或 `<USER>` 字样命中。**

## 5. 附：交叉核对（仅陈述事实，不含评价）

我另对红队/复核共同引用的关键代码点做了直接读取，结果与两份文档所述一致：

- `frontend/src/stores/ai.ts:593` 确为 `const runId = this.activeRunId`；`L655-663` 在 `activeForm` 中写入 `toolCallId/runId/messageId/form/status/error/deadline`；`L674-713` 的 submit/cancel 只传 `form.toolCallId`。全仓 `activeForm` 出现点（`AiPanel.vue:214/215/389`、`stores/ai.ts:204/655/675/703/725/728/756/943/946/979/982/993/995`）中无一处读取 `activeForm.runId` 或 `activeForm.messageId`。
- `stores/ai.ts:420-426` 的 `onClose` 非终态分支确实只置 `streamInterrupted`/`notice`，无 `disarmFormExpiry()`/清 `activeForm`（同文件 `725-729`、`755-756`、`979-983`、`993-996` 有清理）。
- `FormRenderer.vue` 共 161 行（与复核"逐行读全文 161 行"一致），L29 为 `style="width: 100%"`，L138 为 `const model = ref<Record<string, FormModelValue>>(buildInitialValues(props.schema) as ...)`（无 `watch`）。
- `AiPanel.vue:389-391` `liveForm` 仅在 `filling`/`submitting` 时非空，`L214-216` fallback 在 `cancelled`/`submitted` 态只渲染 tag。
- `form-schema.ts` `buildInitialValues`（L135-142 附近）为 `const model: Record<string, unknown> = {}` 后 `model[field.key] = initialValueOf(field)`。
