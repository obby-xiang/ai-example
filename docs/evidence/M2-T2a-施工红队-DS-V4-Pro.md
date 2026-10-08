# M2-T2a 施工红队审查（DeepSeek V4 Pro）

> 角色：反方审查 / 红队。职责是推翻，不是附和。只审不改、零 git 写操作；本文档为唯一新增产物，未提交。
> 审查对象：M2-T2a 施工产出（`git diff a71832d..HEAD -- frontend/ scripts/`），HEAD `1e2edbb`，基线 `a71832d`。
> 规格基准：`docs/M2-T2-返修决策卡.md`（T2a-D#1~#5 + 末尾勘误节 5 条，勘误是规格组成部分）；`docs/evidence/M2-T2-设计红队审查-DS-V4-Pro.md` 裁决结论节。
> 证据等级：**【实测】** = 本次实际读码确认；**【推断】** = 由代码路径推演、未实跑；关键竞态/终端行为未经 CDP 实跑。
> 脱敏：路径相对 `<REPO_ROOT>`；无密钥、无用户名、无内网 IP。
> 审查日期：2026-10-08。

---

## 0. 结论速览

| 项 | 值 |
|---|---|
| 问题总数 | **7**（阻断 ×0 / 严重 ×1 / 建议 ×6） |
| 最高严重度 | **严重（Severe）** |
| 总结论 | **打回（待返修，非阻断级）** |

一句话：T2a 的核心目标（form-cancel 的 cancelled 收敛、双卡唯一实例、pendingToolCall 续扫）实现正确，冒烟断言 ②③ 对"修复前后"判别有效；但 **`frameStatusToPendingStatus` 五支映射表漏了后端真实会下发的 `CANCELLED`（run-cancel 路径）**，导致"整轮取消"与"表单取消"两条取消路径的 pendingCall 终态行为分叉——正是本包要消灭的"消息级不一致"的同类问题。此外终态广播被统一作用于**所有** FRONTEND 工具（非仅 generative_form），给通用前端工具引入一张冗余"已执行"卡。

---

## 1. 问题清单

### 1.1 严重

#### S1｜五支映射表漏 `CANCELLED`（后端真实 frontend_tool_result status），run-cancel 路径终态被"降级为置 null"而非 cancelled

- **位置**：`frontend/src/stores/ai.ts:166-181`（`frameStatusToPendingStatus` 五支 switch，default 返回 null）、`frontend/src/stores/ai.ts:1020-1033`（frontend_tool_result handler：`terminal` 为 null 时落 `else if (frame.ok)` / `else 置 null`）、`frontend/src/stores/ai.ts:652-660`（409 DUPLICATE 收敛复用同一映射）。
- **证据**【实测】：
  - 前端映射表只有 5 支：`FRONTEND_RESULT/FRONTEND_CANCELLED/TIMEOUT/REJECTED/BLOCKED`（`ai.ts:168-179`），其余一律 `return null`。
  - 后端 `ConfirmGate.cancel`（run-cancel 落地）把仍 PENDING 的条目标为 `PendingToolCall.CANCELLED`（大写），并以 `frontendToolResult(fresh, false, ...)` 发结局帧（`backend/.../gate/ConfirmGate.java:204-218`）；`SseChatEmitter.frontendToolResult` 把 `status=pending.getStatus()` 塞进帧（`backend/.../run/SseChatEmitter.java:276-286`）。故 `status:"CANCELLED"`（大写）是**真实可达**的 `frontend_tool_result` 帧 status。
  - `types/sse.ts:22-33` 的 `PendingStatus` 联合**本就含** `CANCELLED`（设计红队 C2 已点名，勘误只补了 `FRONTEND_CANCELLED`，未处置 `CANCELLED` 的映射）。
  - 同理，后端 `SpToolCallingManager.rejectFrontendArguments` 以 `frontendToolResult` 发 `status:"REJECTED_ARGUMENTS"`（`backend/.../gate/SpToolCallingManager.java:296-305`；`PendingToolCall.java:47` 常量），该值也不在五支映射内、也不在 `PendingStatus` 联合内。
- **复现路径【推断】**：generative_form 挂起填写中 → 点"停止"（`stop()` 清 `activeForm` 后 `POST /cancel`）→ 后端把条目转 `CANCELLED` 并发 `frontend_tool_result(status="CANCELLED", ok=false)` → 前端 `frameStatusToPendingStatus` 返回 null → 走 `else`（`ai.ts:1025-1032`）把仍 pending/cancelled 的 pendingCall **置 null**。结果：卡片整体消失，而不是显示 cancelled 终态；与 form-cancel（`FRONTEND_CANCELLED`→"表单已取消" tag）行为分叉。
  - 409 收敛同漏：若 submit/cancel 请求命中已 run-cancel 的条目，409 响应体 `status:"CANCELLED"`（`AiController.duplicateToolCall` 直填 `submission.pending().getStatus()`，`backend/.../web/AiController.java:516-523`）→ `frameStatusToPendingStatus` null → `applyPendingTerminal` 不收敛，pendingCall 留在 `pending`。
- **影响**：`frameStatusToPendingStatus` 作为"帧/响应体 status → pendingCall.status 终态"的**唯一翻译层**不完备；"取消后收敛为 cancelled"的目标在 run-cancel 分支不成立，且 `null → ok=false → 置 null` 的兜底把一个明确的终态语义吞掉了。属规格与实现共同缺口（实现忠实于五支规格，规格漏了后端契约值）。
- **修复方向**：二选一——(a) 映射表补 `CANCELLED → cancelled`（必要时把 run-cancel 也统一进 cancelled 终态口径，并同步 `PendingStatus` 联合与 409 收敛路径）；或 (b) 在规格里显式声明"run-cancel 走置 null 降级、不落 cancelled 终态"，并补一条冒烟断言覆盖该口径，避免与 form-cancel 终态混淆。推荐 (a)。

---

### 1.2 建议

#### S2｜终态广播统一作用于所有 FRONTEND 工具，通用前端工具回灌后残留一张冗余"已执行"pendingCall 卡

- **位置**：`frontend/src/stores/ai.ts:1018-1024`（frontend_tool_result handler 对**任意** FRONTEND 工具 `applyPendingTerminal(..., 'succeeded')`）、`frontend/src/components/AiPanel/AiPanel.vue:232-238`（通用 FRONTEND 分支，`succeeded` 落入 `else`→"已执行"）。
- **证据**【实测】：基线（a71832d）此处是 `message.pendingCall = null`（diff 中被删行）；T2a 改为 `applyPendingTerminal` 后，通用前端工具（navigate_to / open_export_file_editor 等，`runFrontendTool` 路径）在回灌收到 `frontend_tool_result(FRONTEND_RESULT)` 后，`pendingCall.status='succeeded'` **不再被置 null**。于是同一消息上出现两张卡：toolRun 卡（`m.toolRuns`，"已成功"）+ pendingCall 卡（"已执行"，`AiPanel.vue:232-238` else 分支）。
- **影响**：每次通用前端工具调用都多渲染一张冗余卡；属规格未覆盖的副作用（决策卡/勘误的终态示例全部围绕 generative_form，未讨论通用 FRONTEND 工具的 pendingCall 残留）。非数据错误，纯展示冗余。
- **修复方向**：把"终态保留 pendingCall 投影"限定到 generative_form（或明确通用 FRONTEND 工具终态仍走置 null 语义），或在通用 FRONTEND 分支对 `succeeded/blocked` 显式给"已执行/未执行"标签并同步决策卡口径。

#### S3｜openGenerativeForm 守卫"先写 pendingCall 后判守卫"，返回时未回滚已写入的 `'pending'` 投影

- **位置**：`frontend/src/stores/ai.ts:987-997`（frontend_tool_request case：先 `message.pendingCall = this.toPendingCall(...)` 再 `openGenerativeForm`）、`frontend/src/stores/ai.ts:689-697`（守卫命中直接 `return`，不回滚 `message.pendingCall`）。
- **证据**【推断】：
  - 守卫判定的 residue 是"跨消息 pendingCall 投影"（`this.messages.find(...)`，`ai.ts:691-694`），而 `message.pendingCall` 已被 `toPendingCall` 覆写为 `'pending'`；`'pending'` 不在 `isFrameDecidedPendingStatus` 集合内，故**跨消息** residue 仍可被正确命中（reattach 新建消息、旧消息持终态 residue 时守卫有效）。
  - 但守卫 `return` 后，本消息的 `message.pendingCall` 仍是刚写入的 `'pending'`，未被回滚。reattach 回放一条"已决 toolCallId 的 frontend_tool_request"时，会先渲染一张 `'pending'` 卡（generative_form 分支落入 `AiPanel.vue:224` else→"表单状态已丢失，等待后端超时收敛"），直到回放里紧随的 `frontend_tool_result` 帧再把它收敛——瞬时错误卡。
  - 结构性脆弱点：若未来出现"同一消息重收同一 toolCallId 的 frontend_tool_request"（同 assistantId 路径），`toPendingCall` 会把同消息上的终态 residue 覆写为 `'pending'`，守卫在同消息上失明（当前可达路径均为跨消息，未实测到）。
- **影响**：B3 防御守卫只挡住 FormRenderer/timer 重新武装，没挡住"挂起投影重新写入"；"不挂起"半实现。
- **修复方向**：把守卫前移到 `message.pendingCall = toPendingCall(...)` **之前**（或让 `openGenerativeForm` 返回布尔并据其回滚 `message.pendingCall`），保证"已决 toolCallId 不产生任何新 pending 投影"。

#### S4｜冒烟断言①（刷新后单卡）对"修复前后"不判别（近永真）

- **位置**：`scripts/m2t2a-smoke.mjs` 断言①（`postRefresh.pendingCards === 1 && postRefresh.formRendererCount === 0`）。
- **证据**【实测】：
  - 断言①注入**单条**消息后整页刷新，校验"1 张 pendingCall 卡 + 0 个 FormRenderer"。刷新恢复走 `writeMirror`/`readMirror`/`loadHistory` 空历史降级（`ai.ts:115-133 / 374-402`），这三段**均未被 T2a 改动**；基线（a71832d）注入单消息刷新后同样是 1 张卡、0 个 FormRenderer。
  - 故该断言在原缺陷形态下**也会 PASS**（永真），不构成对 T2a 变更的门禁。`formRendererCount === 0` 更是恒真（activeForm 本就不进镜像，T2a 范围内刷新后必然无 FormRenderer）。
  - 决策卡勘误 5 已自认"T2a 阶段仅验镜像重建路径的单卡形态，完整刷新续填待 T2b"，但断言清单仍把①列为可重复验证项，存在"拿永真断言当回归护栏"的误报面。
- **影响**：若后续把①当作 T2a 验收证据，属漏报；对 T2b 落地前无实质保护。
- **修复方向**：①改为能区分修复前后的形态（如"双消息同 toolCallId 注入后刷新，pendingCards 仍为 1 且无重复工具卡"），或显式标注①为"T2b 前置占位断言、对 T2a 不判别"，避免误读。

#### S5｜`formHostMessageId` 只按消息序取最后一条持 toolCallId 的消息，不校验其 pendingCall.status

- **位置**：`frontend/src/components/AiPanel/AiPanel.vue:410-421`（computed 从尾向前找第一条 `pendingCall.toolCallId === toolCallId`，不看 status）、`frontend/src/components/AiPanel/AiPanel.vue:207`（FormRenderer v-if 的 `m.id === formHostMessageId`）。
- **证据**【推断】：若同一 toolCallId 在消息 A（较早，`pending`）与消息 B（较晚，已 `cancelled`/`succeeded` 终态残留）并存，而 `activeForm` 仍非空，则 `formHostMessageId` 取到消息 B（终态消息），FormRenderer 会挂在终态消息上而非仍 pending 的消息 A。当前 `activeForm` 单实例 + 终态即清 `activeForm` 的生命周期使该组合难触发，故列为低置信推断。
- **影响**：潜在门控错挂；未实测到可达路径。
- **修复方向**：`formHostMessageId` 取"最后一条 status 仍为 `pending`（或 `running`/`approved`）"的持 toolCallId 消息，终态残留消息不作为宿主候选。

#### S6｜通用 FRONTEND 分支对新增 status `blocked` 落入 else"已执行"，语义错标

- **位置**：`frontend/src/components/AiPanel/AiPanel.vue:232-238`（kind=FRONTEND 非 generative_form 分支：显式匹配 `pending/running/rejected/expired/cancelled`，`else`→"已执行"）。
- **证据**【实测】：
  - `PendingToolCall.status` 联合已扩入 `cancelled/succeeded/blocked`（`types/ai.ts:218`）。`succeeded` 落 else→"已执行"（语义尚可）；但 `blocked`（"越 scope 未执行"）也落 else→"已执行"，与真实语义（未执行）相反。
  - 是否可达【推断】：`blocked` 进入 pendingCall 投影需 `frontend_tool_result(status="BLOCKED")`，后端 BLOCKED 主要走 `tool_result` 帧（`tool_result` handler 有 blocked 特判，`ai.ts:935-945`），frontend_tool_result 侧 BLOCKED 未见实发路径；故按低置信标注，但类型扩充后读侧未穷举这一事实成立。
- **影响**：读侧标签链对新增 status 未穷举（对比 generative_form 分支 `AiPanel.vue:221` 已显式覆盖 `rejected || blocked`）。
- **修复方向**：通用 FRONTEND 分支显式补 `succeeded`/`blocked` 分支（blocked→"未执行/超出范围"），与 generative_form 分支口径对齐。

#### S7｜冒烟脚本以按钮文本"提交/取消"计数 FormRenderer 实例，脆弱

- **位置**：`scripts/m2t2a-smoke.mjs` `countExpr`（`querySelectorAll('button')` 过滤 `textContent.trim()` 等于"提交"/"取消"）。
- **证据**【实测】：`FormRenderer.vue:97-100` 恰好一个"取消"一个"提交"按钮，当前可正确计数；但该计数绑定到按钮**文案**，一旦页面其他组件/未来向导出现"提交/取消"文案按钮（或 FormRenderer 文案变更），断言②③会误报。且 `cancelledTags` 依赖 `.el-tag` 文案"表单已取消"，同属文案耦合。
- **影响**：冒烟稳定性/抗噪弱，属误报路径而非漏报路径。
- **修复方向**：改按组件/structure 定位（如 `[data-testid]` 或 FormRenderer 根节点 + `__vue_app__` 组件树），或至少把文案常量集中并加注释。

---

## 2. 攻击面无问题清单（含排查方法）

以下攻击面经逐条核查**未发现缺陷**：

1. **终态单调 / winner 规则（攻击面1 主路径）**【实测】：`applyPendingTerminal`（`ai.ts:1128-1136`）只覆盖 `pending || cancelled`，帧终态（`FRONTEND_RESULT→succeeded`）能覆盖本地乐观 `cancelled`（他端先提交翻 succeeded，A2 语义正确），而已落定的 succeeded/rejected/blocked/expired 不会被后续帧降级（first-wins 单调）。form-cancel 路径 `FRONTEND_CANCELLED→cancelled`、submit 路径 `FRONTEND_RESULT→succeeded` 均单调。排查方法：双端竞态冒烟"他端提交+本地取消"断言终态恒 succeeded 无翻闪。
2. **pendingToolCall getter 续扫（C3）**【实测】：`ai.ts:244-259` 对非 `pending` 终态跳过继续向前扫、不短路，新增 status 不会漏掉更早真正 pending 的调用。排查方法：双卡场景（一 cancelled 一 pending）断言 getter 返回后者。
3. **localTerminal 补 succeeded/expired（T2a-D#1 C1 双守卫之二）**【实测】：`ai.ts:1006-1009` 已把 `succeeded/expired` 纳入 toolRun 侧终态集合，帧回执不改写已 succeeded 的 toolRun。排查方法：GFd 双卡场景"一条 succeeded 一条 pending"时取消帧只收敛 pending 那条（决策卡验证用例）。
4. **多 toolCallId 并存门控串扰（攻击面2）**【实测】：`activeForm` 为单实例、`formHostMessageId` 以 `liveForm.value?.toolCallId` 精确匹配，不同 toolCallId 的 pendingCall 不会串扰 FormRenderer 门控。排查方法：双 toolCallId 同时挂起冒烟（架构上单 activeForm 已保证不并发）。
5. **镜像读写的类型扩充兼容（攻击面6）**【实测】：`writeMirror` 内嵌 `pendingCall`、`readMirror` 裸 `JSON.parse`，`cancelled/succeeded/blocked` 以字符串形态无损往返，无需反序列化迁移；`loadHistory` 空历史分支只把 `pending` 降级 `expired`、非 pending 终态残留按终态展示（`ai.ts:394-402`），符合 C2 镜像兼容口径。排查方法：写入含 cancelled 残留的镜像→刷新→断言残留按"表单已取消"展示。
6. **409 DUPLICATE 中性终态（T2a-D#4）主路径**【实测】：`ai.ts:652-660` 对 409 只读响应体 `status` 就地收敛 pendingCall、不报错，响应体确有 `status` 字段（`AiController.duplicateToolCall`，`AiController.java:516-523`）。排查方法：重复回灌冒烟断言 notice 为幂等文案、pendingCall 按响应体 status 收敛（注意：`CANCELLED` 值仍受 S1 缺口影响）。

---

## 3. 总结论

**打回（待返修，无阻断级）。**

- 方向与实现主路径成立：form-cancel 的 cancelled 乐观置位+帧终态覆盖、双卡唯一实例（`formHostMessageId` 门控）、pendingToolCall 续扫、409 读响应体收敛、localTerminal 补 succeeded/expired 均按规格落地，冒烟断言 ②③ 对修复前后判别有效（原缺陷形态下会 FAIL）。
- 但 **S1（映射表漏 `CANCELLED`）是规格与实现共同缺口**：`frameStatusToPendingStatus` 作为"帧/响应体 status → pendingCall.status"的唯一翻译层，漏了后端真实可达的 `CANCELLED`（run-cancel）与 `REJECTED_ARGUMENTS`（schema 安全闸拒绝）。run-cancel 路径把"取消终态"降级为"置 null 消失"，与 form-cancel 的 cancelled 终态分叉——与 T2a 要消灭的"消息级不一致"同源。此条为打回主因。
- 叠加 **S2（通用前端工具冗余卡）** 这一规格未覆盖的可见副作用，以及 S3/S4 两个"半实现/近永真"的守卫与验证缺口，当前不宜按"一次施工即闭环"放行。
- 最小返修动作：补 `CANCELLED→cancelled` 映射（含 409 收敛路径 + `PendingStatus` 联合口径说明），并明确通用 FRONTEND 工具终态投影的保留/置 null 语义；S3/S4 建议一并收敛。若业务方裁决"run-cancel 卡消失"可接受并写入规格，则 S1 降级为建议、可改判通过，但需补一条覆盖该口径的断言。
