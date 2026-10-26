> **历史分析 / 过程证据 — 非现行基线，非验收结论**
>
> - 归档：2026-10-26 · `docs/evidence/history-recovery-2026-10-26/wire-only/`
> - 来源：Kimi Code 会话 `<KIMI_SESSION>` / 子 agent `agent-111` 的 wire 末条 assistant 文本（只读流式解析；非原始会话转写）
> - 原始体量：17307 B / 11481 字符 / 112 行；行指纹覆盖率（仓内 + 落盘语料）0.0%
> - 类别：**正式独立报告**
> - 脱敏：路径 1 / 会话标识 0 / 身份 0 / 密钥 0
> - 正文为该条消息的完整文本，未删段、未改写结论；仅脱敏与行尾空白规整。当时结论不代表当前代码状态。
## 探查范围

只读探查 `<REPO_ROOT>`（HEAD `9de199d`），零写操作。读过的证据/规格：`docs/evidence/GFd-双FormRenderer冒烟验证.md`（全文）、`GFc-端到端执行验证.md`（相关节）、`GFb-红队审查-DS-V4-Pro.md:142`、`docs/M2-排期计划.md:23`、`docs/adr/DECISION-REGISTER.md:19`（DC-15）、`docs/s4/S4.4-前端合流规格.md:51/69/103`。三域在排期表里只有一行定义（`docs/M2-排期计划.md:23`），没有独立设计稿；GF-B-③ 的原始描述只在 GFd 裁决 3（`docs/evidence/GFd-双FormRenderer冒烟验证.md:221`）出现。

---

## ① 表单态入镜像 / 刷新可续填（GF-B-③）

### 后端现状

- **挂起态确实完整外置**（这是后端"能做"的基础）：`ai:pending:<runId>` HASH（field=toolCallId）存 `PendingToolCall` 全字段（`backend/src/main/java/com/example/configmgr/ai/run/RunStore.java:219-228`、`PendingToolCall.java:71-105`）；`ai:run:<runId>` 存整轮快照含 `inFlightToolCalls` 与完整请求历史 `messageJson`（`RunSnapshot.java:51-67`）。落库时机在进入任何等待**之前**（`RunStore.java:197-217`，调用点 `gate/SpToolCallingManager.java:162-163`）。
- **表单挂起帧确实入帧归档**：`frontend_tool_request` 由等待侧发出（`gate/ConfirmGate.java:134-146`，`run/SseChatEmitter.java:263-274`），经 `SseChatEmitter.emit` 逐帧带 `seq` 落 `ai:events:<runId>`（`SseChatEmitter.java:424-446`，`RunStore.java:415-430`）。
- **重挂回放会把 `frontend_tool_request` 原样补发**：`replayLocked` 只跳过 `delta`/`heartbeat`（`SseChatEmitter.java:358-375`，跳过判据在 `:363`），终态轮次也是回放完即收尾（`AiController.java:283-295`）。也就是说**后端已有"刷新后重放表单请求帧"的机制**。
- **但服务端没有"按 sessionId 找回挂起表单"的读路径被使用**：`GET /api/ai/runs` 会给出 `runId/sessionId/pending/pendingUnresolved`（`run/ResumeService.java:118-140`，`AiController.java:371-378`）；`GET /api/ai/pending/{runId}`（`AiController.java:559-566`）；`SessionGate.currentRunId(sessionId)` 存在（`session/SessionGate.java:116-127`）但**全仓无调用点、也无对应端点**（grep `currentRunId` 仅命中定义）。
- **服务端续跑明确拒绝未决前端挂起**：`POST /api/ai/runs/{runId}/resume` 对有 `PENDING` 的外部挂起项返回 `PENDING_UNRESOLVED`（`run/ResumeService.java:290-298`），控制器落到 409（`AiController.java:400-407` 的 `default` 分支）。即"服务端不会替用户继续推这一轮"，恢复只能靠 reattach 重放。
- **历史（记忆窗口）在挂起期不含本轮工具消息**：记忆收敛只在轮终态调用（`run/ResilientChatService.java:446/476` → `convergeMemory:522-535`），挂起中的 `assistant(tool_calls)` 只存在快照的 `assistantContent/inFlightToolCalls` 里（`RunSnapshot.java:60-64`）。所以 `GET /api/ai/history/{sessionId}` 在挂起期只返回**之前轮次**的消息。

### 前端现状

- **`activeForm` 不进镜像，且是显式决定**：`frontend/src/stores/ai.ts:202-203` 注释"不进镜像：刷新后由 pendingCall 的 expired 降级与后端超时收敛"；镜像只写 6 个字段（`id/role/content/reasoning/toolRuns/pendingCall`，`stores/ai.ts:115-134`，类型 `types/ai.ts:217-222`）。GFb 红队独立记录同一事实（`docs/evidence/GFb-红队审查-DS-V4-Pro.md:142`）。
- **刷新恢复链路只有两步，且第二步会把挂起卡抹掉**：`stores/ai.ts:343-396` —— 先按镜像恢复 `messages`（含 `pendingCall`，`:346-353`），再与后端历史对账；**历史非空时整表重建且 `pendingCall: null`**（`:373-393`，字面量在 `:389`）。`stores/ai.ts:394` 留有注释"历史里若存在未决挂起，补一张待处理卡片"，但其下**没有任何实现代码**（`:394-395` 直接 `historyLoaded = true`）。
- **历史为空（首轮即挂起/TTL 过期）时才保留镜像，但把 pending 降级为 `expired`**（`stores/ai.ts:363-370`）。
- **刷新后不会自动 reattach**：`AiPanel.vue:553-564` 的 `onMounted` 只调 `startMirror/startWorkspaceSync/probeHealth/loadHistory`，**不调 `reattachActive`**；虽然镜像存了 `lastRunId`（`stores/ai.ts:282`），但只在 `streamInterrupted` 的提示条上被用到（`AiPanel.vue:78-86`，条件含 `ai.streamInterrupted`，刷新后为 false）。重挂入口仅两处：409 冲突条（`AiPanel.vue:44-51`，由 `busyConflict` 驱动 `stores/ai.ts:468-487`）。
- **前端有一个专门的"表单丢了"文案分支**：`AiPanel.vue:217` —— `表单状态已丢失（刷新后无法续填），等待后端超时收敛`（`liveForm` 为空且 `activeForm` 非 cancelled/submitted 时显示；`liveForm` 计算见 `AiPanel.vue:393-396`）。
- **前端已有但从未调用的客户端**：`aiApi.runs / run / resume / pending` 全部定义在 `frontend/src/api/ai.ts:87-105`，grep 全 `frontend/src` 只有 7 处 `aiApi.*` 调用（chat/reattachRun/confirm/frontendToolResult/cancel/history/health，分布 `stores/ai.ts:328/357/441/536/578/614/759`）。

### 断点（现在断在哪一环）

1. **`activeForm` 没落库到客户端持久层**（`stores/ai.ts:115-134` 镜像字段表）——刷新后 `FormRenderer` 的渲染条件 `liveForm`（`AiPanel.vue:393-396`）恒为 null。
2. **`loadHistory` 在历史非空时用 `pendingCall: null` 覆盖镜像**（`stores/ai.ts:389`），导致连"挂起卡"本身都消失，只剩 `toolRuns` 里一条 `status:'succeeded'` 的历史工具卡（`:375-382` 把历史 toolCalls 一律标为 succeeded）。
3. **刷新后没有任何自动/可点的 reattach**（`AiPanel.vue:553-564`；重挂按钮的两个触发条件在刷新后都不成立）——而后端回放恰好是能救回表单的那条路（`SseChatEmitter.java:363`）。
4. **没有任何"sessionId → 未决表单"的消费**（`SessionGate.currentRunId` 无端点；`aiApi.pending/runs` 无调用）。

### 测试覆盖

- 后端：无。`ReattachLastSeqConformanceTest` 覆盖的是帧序/差量（`backend/src/test/java/com/example/configmgr/ai/conformance/ReattachLastSeqConformanceTest.java:52-58/87-92/126-130`），用例帧集为 `start/suspended/done/tool_*`，**没有 `frontend_tool_request` 的回放断言**；`SseChatEmitterFramesTest.java:82` 只断言帧类型顺序含 `frontend_tool_request`。
- E2E：`scripts/verify-e2e.ps1` 的 GF1–GF6 全部经脚本自扮前端（不加载前端产物，见 `docs/evidence/GFc-端到端执行验证.md:79`），GF4-A3（`:1790-1793`）只在 3.5s 窗口内断言"拒绝路径无 `frontend_tool_result` 帧"，**无"刷新/重挂后表单可续填"用例**。
- 前端：**零**。`frontend/package.json:6-11` 只有 `dev/build/typecheck/preview`，无 test 脚本、无 vitest/jest 依赖，全仓无 `*.spec.ts`/`*.test.ts`。

---

## ② 双 FormRenderer 收敛（GFd）

### 后端现状

- **封顶键是 `(runId, toolCallId)`**：`ConfirmGate.submitFrontendResult` 先 `pending(runId, toolCallId)`，`null` → `NOT_FOUND`，状态非 `PENDING` → `DUPLICATE`（`backend/src/main/java/com/example/configmgr/ai/gate/ConfirmGate.java:346-357`）；控制器把两者都映射为 409（`AiController.java:242-248`，错误体 `:506-526`，码 `UNKNOWN_TOOL_CALL`/`DUPLICATE_TOOL_CALL_ID`）。
- 幂等语义有单测锁：`gate/GenerativeFormGateTest.java:236`（`repeatedSubmissionIsDuplicateEvenWhenCancelling`）、`web/AiControllerFrontendResultTest.java:95`。
- 后端没有"同一 toolCallId 只能被一个前端实例消费"以外的机制；它在 `runId+toolCallId` 上先到者胜。

### 前端现状

- **挂起卡按消息渲染**：`AiPanel.vue:122` 遍历 `ai.messages`，`:169` 每条消息渲染 `v-if="m.pendingCall"` 的卡片；表单分支在 `:202-223`，`FormRenderer` 挂载条件为 `liveForm.toolCallId === m.pendingCall.toolCallId` 且 `:key="liveForm.toolCallId"`（`:204-213`）。同一 toolCallId 落在 N 条消息上 → N 个实例（同 key 但属不同子树，互不复用）。
- **`activeForm` 全局唯一**：state 声明 `stores/ai.ts:204`，唯一写点 `openGenerativeForm`（`:646-685`，赋值在 `:670-677`），取消/收尾只改这一份（`:723-738`、`:1004-1008`）。
- **字段值是组件本地态**：`FormRenderer.vue:138` `const model = ref<Record<FormModelValue>>(buildInitialValues(props.schema))`，`props` 只有 `schema/submitting/error/remainingSeconds`（`:124-131`），**不读 `activeForm`**。GFd §3.C 实测两卡输入互不同步（`docs/evidence/GFd-双FormRenderer冒烟验证.md:141-152`）。
- **两条消息各持一个 pendingCall 的写入路径**（同一 toolCallId）：①镜像恢复保留旧消息的 `pendingCall`（`stores/ai.ts:346-353`）；②`reattachActive` **新起一条助手消息**承接回放帧（`stores/ai.ts:528`），回放到的 `frontend_tool_request` 在新消息上再写一份 `pendingCall`（`stores/ai.ts:937-944`）。GFc 用例设计文档独立记录同一推断（`docs/evidence/GFc-E2E用例设计-GLM-5.3.md:277`）。
- **卡②提交的产物处理**：后端 409 `DUPLICATE_TOOL_CALL_ID` 在前端被判为"成功"——`stores/ai.ts:705` `if (outcome.ok || outcome.duplicate) { form.status = 'submitted' }`，即卡内显示"表单已提交，等待回执"（`AiPanel.vue:216`），但卡②的值实际未被采纳。

### 断点

1. **挂起态持有者是"每条消息各一份 `pendingCall`"（`types/ai.ts:190`），而表单态持有者是全局单例 `activeForm`（`stores/ai.ts:204`）**——两者基数不一致，渲染条件用 `toolCallId` 相等做匹配（`AiPanel.vue:205`），于是"单例 × N 条消息"必然渲染 N 张卡。
2. **没有任何去重/占位收敛逻辑**：既没有"同 toolCallId 只保留一条消息的挂起卡"的投影，也没有"卡②禁用提交"的分支（`AiPanel.vue:202-223` 无此判断）。
3. 后端只能"封顶"不能"消重卡"（`ConfirmGate.java:346-357`），因此双卡最终靠 `activeForm` 置空同时收起（`AiPanel.vue:214-222`）。

### 测试覆盖

- 后端：仅"重复提交幂等 409"（`gate/GenerativeFormGateTest.java:236`、`web/AiControllerFrontendResultTest.java:95-113`）。
- E2E：无。GF1–GF6 每次会话只挂起一张表单（脚本按单帧 `frontend_tool_request` 处理，`scripts/verify-e2e.ps1:1496/1588/1668`）。
- 前端：零单测；GFd 是 CDP 注入帧的手工冒烟（`docs/evidence/GFd-双FormRenderer冒烟验证.md:74-95`），不进 CI。

---

## ③ 取消后消息级 `pendingCall.status` 收敛

### 后端现状

- **取消语义已完整落库**：表单取消走 `POST /api/ai/frontend-tool-result {cancelled:true}`（`AiController.java:233-266`）→ `ConfirmGate.submitFrontendResult` 的取消分支（`ConfirmGate.java:346-352`）→ `dismiss()`：条目置 `FRONTEND_CANCELLED`、`reason=FRONTEND_DISMISSED`、`resultText` 带固定前缀，然后**落库 → 发 `frontend_tool_result(ok=false)` 帧 → 唤醒**（`ConfirmGate.java:395-417`，帧写点在 `:414`）。
- **整轮取消也有落库**：`RunStore.cancelPendings` 把 `PENDING` 条目改 `CANCELLED`（`RunStore.java:383-398`），由 `ResilientChatService.cancelTerminal` 调用（`run/ResilientChatService.java:478`），也由等待侧 `ConfirmGate.cancel`（`:199-215`）与超时 `expire`（`:225-243`）各发一帧 `frontend_tool_result`。
- 帧里**带终态字段**：`frontendToolResult` 帧含 `status`（`SseChatEmitter.java:276-286`，`status` 在 `:282`），类型契约见 `frontend/src/types/sse.ts:188-200`。

### 前端现状

- **消息级 `PendingToolCall.status` 的取值域里根本没有"取消"**：`frontend/src/types/ai.ts:213` —— `status: 'pending' | 'running' | 'approved' | 'rejected' | 'expired'`（后端 `PendingStatus` 有 `CANCELLED`，见 `types/sse.ts:21-33`）。
- **取消动作只作用于 `activeForm`**：`cancelGenerativeForm`（`stores/ai.ts:723-738`）只改 `form.status='cancelled'`，**不碰 `message.pendingCall`**；`stop()` 同理只清 `activeForm`（`:750-754`）。
- **`frontend_tool_result` 帧处理器不读 `frame.status`**：`stores/ai.ts:950-975` 只做 `toolRuns` upsert + 在 `:965-967` 把 `message.pendingCall` **置 null**（不是置终态）；`localTerminal` 只识别 `failed/rejected/blocked`（`:955`），`FRONTEND_CANCELLED` 落不到任何状态位。
- 唯一的"消息级收敛"靠 `done` 帧兜底：`stores/ai.ts:1003` `message.pendingCall = message.pendingCall?.status === 'pending' ? null : message.pendingCall`。
- 镜像恢复路径给出的是**第三态 `expired`**，不是 `cancelled`（`stores/ai.ts:363-370`）。

### 断点

1. **前端无法表达"取消"这一消息级终态**（`types/ai.ts:213` 取值域缺位）——即使结局帧到达也只能把整条 `pendingCall` 抹掉，帧里的 `status=FRONTEND_CANCELLED`（`SseChatEmitter.java:282`）无人消费。
2. **收敛完全依赖"结局帧到达且落在这条消息上"**：`stop()` 先 `currentAbort.abort()`（`stores/ai.ts:744`）再 POST cancel（`:759`）→ SSE 已断，后端随后发出的 `frontend_tool_result`/`done` 都到不了；`onClose` 断流分支只清 `activeForm`（`:425-430`），不清 `pendingCall`。此时该消息永久停在 `pending`，UI 显示"待浏览器自动执行"（`AiPanel.vue:226`）或表单卡落"表单状态已丢失…"（`AiPanel.vue:217`），且 `pendingToolCall` getter 仍把它当未决（`stores/ai.ts:214-228`）→ 输入区占位恒为"挂起等待中…"（`AiPanel.vue:371-376`）。
3. **多消息持同一 toolCallId 时**，结局帧只会清掉 handleFrame 命中的那一条（`stores/ai.ts:833` 按 `assistantId` 定位 + `:965`），其余消息的 `pendingCall` 留在 `pending`（②与③同源）。

### 测试覆盖

- 后端：取消终态有单测 —— `gate/GenerativeFormGateTest.java:206-221`（`dismissalEndsAtExplicitCancelledState`、`dismissalWinsOverResultReview`）、`web/AiControllerFrontendResultTest.java:78-93`（`cancellationIsForwardedAndReportedAsTerminalState`）。
- E2E：GF3 断言取消路径的 HTTP/待决条目/结局帧三处终态（`scripts/verify-e2e.ps1:1685-1705`），但断言对象是**服务端**状态与帧原文，**不加载前端**，故消息级 `pendingCall.status` 不可观测（`docs/evidence/GFc-端到端执行验证.md:79`）。
- 前端：零单测 / 零 E2E 可见性。GFd 的手工冒烟是唯一实测（`docs/evidence/GFd-双FormRenderer冒烟验证.md:166-185`，含 §四.4 的 `expired` 副产物记录）。

---

## 三域共同根源假设（【推断】）

【推断】三域是同一个断层在不同出口的表现：**"表单态"在前端被拆成三份互不同步的副本，且没有一条"以服务端挂起态为权威、按 `(runId, toolCallId)` 幂等投影到唯一消息 + 唯一渲染实例"的收敛路径。**支持这一推断的代码事实：

1. **三份副本、基数各不相同**：全局单例 `activeForm`（`stores/ai.ts:204`）／每条消息一份 `pendingCall`（`types/ai.ts:190`，`AiPanel.vue:169`）／每个 FormRenderer 实例一份本地 `model`（`FormRenderer.vue:138`）。①断在副本 1、②断在副本 2 的基数膨胀、③断在副本 2 无终态取值。
2. **服务端权威态齐备但缺一条"可被前端消费的投影契约"**：`ai:pending:<runId>` 有完整条目（`RunStore.java:219-228`）、归档有 `frontend_tool_request` 原文（`SseChatEmitter.java:363/441`），但 sessionId→runId 的读接口无端点（`SessionGate.java:116` 无调用），前端 `runs/pending/resume` 客户端定义了却零调用（`api/ai.ts:87-105`）。刷新恢复因此只能靠"客户端镜像 + 历史对账"（`stores/ai.ts:343-396`），而这条路的两次覆盖（`:389` 置 null、`:366` 降级 expired）都不搬服务端真相。
3. **①与②相互放大**：`reattachActive` 用"新起一条消息"承接回放帧（`stores/ai.ts:528` + `:539`），一旦把①做成"刷新后自动 reattach"，双卡会从注入态（GFd §二.3）变成常规态——即 `activeForm` 单例与 `pendingCall` 多实例的结构性错配（`AiPanel.vue:204-213`）会立刻常态暴露。
4. **③的落点也在同一条缝上**：后端把取消写进了条目与帧（`ConfirmGate.java:400-417`），前端却在"消息级"这一层没有可表达的终态（`types/ai.ts:213`），于是取消事实只能靠"结局帧到达 → 整条置 null"（`stores/ai.ts:965-967`）这一条偶然通路传递；帧未达（abort/断流/多卡）即遗留 `pending`。
5. **三域都没有前端可执行的门禁**（`frontend/package.json:6-11` 无测试脚本，E2E 不加载前端产物 —— `docs/evidence/GFc-端到端执行验证.md:79`），因此这三处的回归目前既不会红也不会绿，只能靠 CDP 手工冒烟（GFd）发现。

需要最终裁决的模糊点（供上层判断，我不代决）：③的目标取值是"消息级出现 `cancelled`"还是"消息级清空 + 卡片态由 `activeForm` 承载"，代码里两种口径各有一半（`AiPanel.vue:215-221` 已按 `activeForm.status` 展示"表单已取消"，而 `stores/ai.ts:965` 选择置 null）——规格未定，属设计取舍。
