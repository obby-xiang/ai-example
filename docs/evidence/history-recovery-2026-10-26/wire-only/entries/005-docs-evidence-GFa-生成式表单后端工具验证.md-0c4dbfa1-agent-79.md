> **历史分析 / 过程证据 — 非现行基线，非验收结论**
>
> - 归档：2026-10-26 · `docs/evidence/history-recovery-2026-10-26/wire-only/`
> - 来源：Kimi Code 会话 `<KIMI_SESSION>` / 子 agent `agent-79` 的 wire 末条 assistant 文本（只读流式解析；非原始会话转写）
> - 原始体量：21164 B / 14703 字符 / 241 行；行指纹覆盖率（仓内 + 落盘语料）17.2%
> - 类别：**施工复核**
> - 脱敏：路径 0 / 会话标识 0 / 身份 0 / 密钥 0
> - 正文为该条消息的完整文本，未删段、未改写结论；仅脱敏与行尾空白规整。当时结论不代表当前代码状态。
## 1. `docs/evidence/GFa-生成式表单后端工具验证.md`（436 行，仓库根相对路径 `docs/evidence/GFa-生成式表单后端工具验证.md`）

### 1.1 §8 完整摘录（第 361-398 行，标题即 `## 8. GF-C（前端 FormRenderer）对接契约清单`）

> 本棒只交付后端；以下是前端必须遵守/可依赖的契约（均已在后端用例里锁定）。

**§8.1 帧（既有帧型，无新增）**（363-370 行）

| 帧 | 前端拿到什么 |
|---|---|
| `frontend_tool_request` | `name=generative_form`，`args={"form":{…}}`（已过白名单校验，**必为合法 schema**），`toolCallId`、`timeoutSeconds`、`expiresAt`、`callback` |
| `frontend_tool_result` | 结局帧：`ok`、`status`（`FRONTEND_RESULT`/`FRONTEND_CANCELLED`/`REJECTED_ARGUMENTS`/`TIMEOUT`）、`executed=false`、`result` |

**§8.2 回灌（`POST /api/ai/frontend-tool-result`）**（372-380 行，逐字）

```
- **提交**：`{runId, toolCallId, result: "<字段 key→值的 JSON 对象>", source}` —— `result` 必须是与 schema 相符的值 JSON
  （number 是数、date 是 `yyyy-MM-dd`、enum/multi_select 的值在选项内、未知字段禁止）。
- **用户放弃**：`{runId, toolCallId, cancelled: true}`（`result` 可省）⇒ 条目立刻落 `FRONTEND_CANCELLED`，
  不必等超时。
- **被拒（400）**：`{code:"FORM_RESULT_REJECTED", message, reasons:[…]}` + `status:"PENDING"` ——
  含义是"**挂起仍在等待**"，前端应提示用户改值后重发（同一个 `toolCallId`），或改发 `cancelled:true`。
- 幂等：已决条目再提交（含取消）⇒ 409 `DUPLICATE_TOOL_CALL_ID`。
```

**§8.3 一处必须注意的前端能力不可用路径**（382-391 行，【待裁决】见 §9 #3）

> 现有前端的通用执行器在"工具名未知/能力不可用"时会回灌**一段说明文本**。对 `generative_form` 而言这段文本**不是**值 JSON ⇒ 会被闸门拒 400、挂起不消费 ⇒ 该轮要等到超时才收尾。因此 GF-C 的正确做法是二选一：
> 1. 实现表单渲染器（正常路径，回灌值 JSON）；
> 2. 渲染器不可用（如能力未注册）时，**带 `cancelled:true` 回灌**，让挂起立刻收敛。
>
> 这条已用用例锁死语义（`GenerativeFormContractTest#guardRejectsFreeTextResultAndTellsTheFrontendWhatToDo`），其拒绝文本本身就带着"用户放弃请带 cancelled=true"的指引。

**§8.4 前端需要顺手改掉的过时注释（不属本棒范围）**（393-398 行，逐字）

> `frontend/src/utils/frontend-tools.ts` 顶部注释仍写"<OLD_REPO_DIR> 后端 `AiTools` 共 **12** 个 `@Tool`，其中带 `@ToolChannel(FRONTEND)` 的**只有 2 个**"——**本棒之前就已过时**（S4.4d 把 4 个工作区动作标为 FRONTEND 后实为 6 个），本棒再增 1 个后为：**18 个工具 / 7 个前端通道工具**。`frontend/` 不在本棒范围内，故只在此登记，请 GF-C 一并更正（它与"前端零改动即可生效"的判断依据直接相关）。

### 1.2 "GF-C" 出现的全部行号与上下文

| 行号 | 上下文（GF-C 的指代） |
|---|---|
| 9 | 范围边界："前端 `frontend/` 一行未动（FormRenderer 属 GF-C）" |
| 108 | §2.5 安全边界："若 GF-C 引入富文本渲染，内容级校验必须回到 `GenerativeFormRules` 补" |
| 286 | §5.3 表尾："前端的动作表尚未含 `generative_form`（GF-C 的工作）" |
| 320 | §6.3 未覆盖："需前端 FormRenderer（GF-C）与真实模型，本棒【未实测】" |
| 325 | §6.3 未覆盖："属 GF-C/E2E 场景" |
| 347、348 | §7 第 1 点："端到端的表单场景本来就属于 GF-C…留待 GF-C" |
| **361** | **§8 章节标题本身**：`## 8. GF-C（前端 FormRenderer）对接契约清单` |
| 385 | §8.3："因此 GF-C 的正确做法是二选一" |
| **398** | **§8.4 内**："故只在此登记，请 GF-C 一并更正"（§8.4 正文第 393-398 行内唯一一处 GF-C） |
| 406 | §9 #1 备选影响："注意：在 GF-C 之前，放宽会提高…概率" |
| 408 | §9 #3："把它写成 GF-C 的对接契约（§8.3）" |
| 430 | 裁决结论 #1："GF-C 实测需要再放宽（一行注解）" |
| 432 | 裁决结论 #3："GF-C 契约：前端不可用时必须带 `cancelled:true`" |

共 14 行含 "GF-C"（347/348 同行对），另第 361 行是章节标题。全文 "GF-B" 目前**只出现在第 436 行**（文末"另："补注），是唯一已用新命名的位置。

### 1.3 文末"裁决结论"节（第 426-436 行，指挥官 K3，2026-10-07）五项裁决

| # | 事项 | 裁决 |
|---|---|---|
| 1 | 澄清场景放宽到全上下文 | 维持 `{page:tasks, task:*}` —— 与渐进披露最小集同向，GF-C 实测需要再放宽（一行注解） |
| 2 | 取消用可选字段 vs 独立端点 | 维持可选字段 —— 老客户端零改动，复用幂等入口 |
| 3 | "能力不可用"回灌形态 | 维持严格闸门 —— GF-C 契约：前端不可用时必须带 `cancelled:true`；协议扩展不做 |
| 4 | 选项裸字符串 | 维持不允许 —— 模型看到的形状与闸门接受的形状不分叉 |
| 5 | E2E 是否照跑一轮 | 照跑一轮 —— 由提交官验收时执行，实证披露变化不影响 22 用例 |

另（第 436 行）："`frontend/src/utils/frontend-tools.ts` 顶部工具计数注释过时（实为 18 个 `@Tool` / 7 个 `FRONTEND`），已登记转交 GF-B 处理。"

### 1.4 补充：错误码语义（文档 §3.4 / §3.5 / §4.2 的行号锚点）

- `FORM_SCHEMA_REJECTED`（下发前闸门）：§3.2 第 147-164 行；原文"表单 schema 未通过白名单校验，工具未执行（未向前端下发表单）"，**一次给全所有原因**；行为=不挂起/不下发/不认领，只回填模型 + 一帧 `frontend_tool_result(ok=false,status=REJECTED_ARGUMENTS)`（§3.1 表，139-141 行）。
- `FORM_RESULT_REJECTED`（回灌闸门）：§3.3 第 166-196 行、§3.4 第 200-218 行；拒绝文本"回灌的表单结果未通过类型复核，本次回灌被拒（挂起仍在等待，可修正后重试）"，HTTP 400 + `status:"PENDING"`，条目**保持 PENDING**（§3.5 第 221-227 行："被拒不算消费"）。
- `FRONTEND_CANCELLED`：§4.2 第 244-252 行；触发 `cancelled: true`（`result` 被忽略），条目状态 `FRONTEND_CANCELLED`（reason `FRONTEND_DISMISSED`，`executed=false`），结局帧 `frontend_tool_result(ok=false, status=FRONTEND_CANCELLED)`；回填文本（249 行）："FRONTEND_CANCELLED：用户取消了该前端操作（关闭表单/放弃填写），本次调用未获得任何数据…"；**取消优先于复核**（226-227 行）；幂等：已决条目再带 `cancelled` ⇒ 409 `DUPLICATE_TOOL_CALL_ID`。

---

## 2. 前端 AI 会话面板与前端工具通道现状

### 2.1 AI 会话面板组件

- 组件：`frontend/src/components/AiPanel/AiPanel.vue`（538 行，唯一 AI 面板）。挂载点 `frontend/src/App.vue:34`（`<el-aside>` 内），导入 `App.vue:55`。
- SSE 帧处理**不在组件里**，在 store：`frontend/src/stores/ai.ts`（903 行）的 `handleFrame(frame, assistantId)`，帧分派 `switch (frame.type)`；`frontend_tool_request` 分支在 `stores/ai.ts:771-777`，`frontend_tool_result` 分支在 `stores/ai.ts:778-798`。
- 面板展示挂起卡（含前端工具分支）在 `AiPanel/AiPanel.vue:168-217`（`v-if="m.pendingCall"`；`kind === 'FRONTEND'` 分支在 202-208 行，目前只显示"待浏览器自动执行/执行中"标签）。

### 2.2 `frontend/src/utils/frontend-tools.ts`（614 行）：清单与注册机制

**顶部注释当前计数表述（第 4-8 行，逐字）**：

```
 * **后端口径核对（本棒实测，勿凭蓝本臆造）**：<OLD_REPO_DIR> 后端 `AiTools` 共 12 个 `@Tool`，
 * 其中带 `@ToolChannel(FRONTEND)` 的**只有 2 个**：`open_export_file_editor`、`download_export_file`
 * （其余 10 个是 BACKEND 通道，由后端自己执行；`start_export/start_precheck/start_import/start_publish`
 * 在 <OLD_REPO_DIR> 里都是 BACKEND，不再像旧基座那样挂起等前端）。所以：
 * - 路由 1 覆盖后端**实际披露**的前端工具（2 个），完整实现；
```

另一处同口径过时表述：**第 255-257 行**注释"后端当前只披露 2 个 FRONTEND 工具，故这些动作今天经…触发"。

**真实数字（我实测核对，backend `ai/tools/AiTools.java`）**：

- `@Tool(name=…)` 共 **18** 个（`grep -c '@Tool(name'` = 18；`list_config_defs, get_config_def, list_tasks, get_workspace_state, check_job_status, get_row_count, create_task, start_export, start_precheck, start_import, start_publish, open_export_file_editor, download_export_file, navigate_to, select_definitions, set_condition, confirm_step, generative_form`）。
- `@ToolChannel(ToolMeta.Channel.FRONTEND)` 共 **7** 个（`AiTools.java` 第 339/349/365/375/403/439/495 行）＝上述后 7 个。
- 即文档所说"18 个工具 / 7 个前端通道工具"**与代码实际一致**；`frontend-tools.ts` 头部的"12 / 2"确实过时。
- 同类过时点：`frontend/src/types/tools.ts:7-31` 的"后端工具全集"表只列 **17** 个（无 `generative_form`），且 29-32 行仍写"`open_export_file_editor`/`download_export_file` 曾被写成'唯一两个 FRONTEND 工具'，S4.4d 起四个工作区动作也是 FRONTEND"（表里那 4 个已按 FRONTEND 列，但对账户数仍停在 17）。
- 前端全仓对 `generative_form` **零引用**（`grep -rn "generative_form" frontend/` 无命中）→ FormRenderer / 渲染分支尚不存在。

**注册机制**（`frontend-tools.ts`）：

- 描述表（静态）：`FRONTEND_TOOLS` 在 `types/tools.ts:79-104`（仅 2 条：`open_export_file_editor`、`download_export_file`）；`FrontendToolName` 联合类型 `types/tools.ts:76`。
- 执行器注册表（运行期）：`const executors = new Map<string, FrontendToolExecutor>()`（`frontend-tools.ts:53`）+ `registerFrontendToolExecutor` / `unregisterFrontendToolExecutor`（56-63 行）；页面级 handler 通过 `getPageHandler`（来自 `stores/workspace.ts`）反向暴露。
- 统一入口 `executeFrontendTool(call)`（143-167 行）四条路由：
  - 路由 1：`findFrontendTool(name)` → 参数校验 + `@ToolScope` 校验 + 执行器/默认执行器（`runRegisteredTool`，428-470 行）；
  - 路由 2：`WORKSPACE_ACTIONS`（259-319 行，仅 `navigate_to`/`select_definitions`/`set_condition`/`confirm_step`）+ 别名表（322-329 行）；
  - 路由 3：`ui_event` 同名动作；
  - 路由 4：页面 handler；
  - 兜底（162-166 行）：返回 `{ok:false, availability:'unavailable', result:'未知前端工具 ${name}：该工具没有浏览器侧执行器，请改用后端工具完成。'}` —— **这就是 §8.3 说的"说明文本"，对 `generative_form` 会被后端闸门拒 400**。
- 可用性三级：`available / degraded / unavailable`（`types/tools.ts:52`；实现里的返回处见 `runRegisteredTool` 等）。

### 2.3 `frontend_tool` 帧如何到达前端 / 如何回灌

**下行（SSE，非 WebSocket）**：

- 主通道 `POST /api/ai/chat`（**POST + text/event-stream**，用 `fetch` + `ReadableStream` 自解帧，因为 EventSource 不支持 POST）：`frontend/src/api/sse.ts:1-14`（文件头注释）、`SSE_PATHS.chat = '/api/ai/chat'`（`api/sse.ts:30-40`）、`readStream()` 用 `reader.read()` + `SseChunkParser`（`api/sse.ts:222-267`）。
- 重挂通道 `GET /api/ai/events/{runId}?lastSeq=`（reattach，EventSource）：`api/sse.ts:36-39`。
- 帧进入 store：`stores/ai.ts:399-411` 的 `SseFrameHandlers.onFrame: (frame) => this.handleFrame(frame, assistant.id)`。
- `frontend_tool_request` 处理（`stores/ai.ts:771-777`）：
  ```ts
  case 'frontend_tool_request': {
    this.suspended = true
    message.pendingCall = this.toPendingCall(frame, 'FRONTEND')
    // 前端工具没有 HITL：立即在浏览器执行并回灌结果
    void this.runFrontendTool(message, frame)
    break
  }
  ```
- 帧类型定义：`frontend/src/types/sse.ts:176-186`（`SseFrontendToolRequestFrame`：`toolCallId/name/args?(JSON 字符串)/timeoutSeconds/expiresAt?/callback?`；184 行注释给出回调端点说明）与 189-200 行（`SseFrontendToolResultFrame`，含 `status`、`messageId`）；帧白名单 `SseFrameTypes`（`types/sse.ts:63-64`）。
- 后端发射处：`backend/src/main/java/com/example/configmgr/ai/run/SseChatEmitter.java:263-274` —— `frontendToolRequest` 帧字段为 `toolCallId/name/args(pending.getArguments() 原文，即 {"form":{…}})/timeoutSeconds/expiresAt/callback`；结局帧 `frontendToolResult`（276-286 行）带 `ok/executed=false/status/messageId/result`。

**上行（HTTP POST，非 SSE）**：

- 端点：`POST /api/ai/frontend-tool-result`（`frontend/src/api/ai.ts:47-50`）：
  ```ts
  export function frontendToolResult(request: FrontendToolResultRequest): Promise<FrontendToolResultAck> {
    return post<FrontendToolResultAck>('/ai/frontend-tool-result', request)
  }
  ```
- 调用点：`stores/ai.ts:880-901` `runFrontendTool()` → 执行后 `await this.submitFrontendToolResult(frame.toolCallId, execution.result, 'frontend-executor')`（900 行）。
- 提交实现：`stores/ai.ts:563-579`
  ```ts
  /** 前端工具结果回灌（幂等：重复 → 409，未知 → 409）。 */
  async submitFrontendToolResult(toolCallId: string, result: string, source = 'http-post'): Promise<void> {
    const runId = this.activeRunId
    ...
    await aiApi.frontendToolResult({ runId, toolCallId, result, source })
    ... if (error.code === ErrorCode.DUPLICATE_TOOL_CALL_ID) { this.notice = '该工具结果已回灌过（幂等拒绝）' }
  ```
  - **幂等键 = `toolCallId`**（配 `runId`），`runId` 取自 `this.activeRunId`（来自 `start` 帧）。
- **请求体类型缺 `cancelled`**：`frontend/src/types/ai.ts:64-72`
  ```ts
  export interface FrontendToolResultRequest {
    runId: string
    toolCallId: string
    /** 前端执行结果文本，原样作为工具结果回填给模型 */
    result: string
    /** 结果来源标注（默认 http-post），仅用于留档 */
    source?: string
  }
  ```
  —— 没有 `cancelled` 字段；响应类型 `FrontendToolResultAck`（`types/ai.ts:74-84`）也没有 `status` 之外的 `FORM_RESULT_REJECTED` 相关字段。
- **错误码枚举也没有 FORM 码**：`frontend/src/types/api.ts:51-72` 的 `ErrorCode` 只有 `SESSION_BUSY / SUSPEND_POOL_SATURATED / AI_UNAVAILABLE / AI_REDIS_UNAVAILABLE / JOB_ALREADY_FINAL / DUPLICATE_TOOL_CALL_ID / UNKNOWN_TOOL_CALL / UNKNOWN_RUN / BAD_REQUEST / AI_RUN_FAILED`；前端全仓无 `FORM_SCHEMA_REJECTED`/`FORM_RESULT_REJECTED`/`FRONTEND_CANCELLED` 字样（`grep -rn "FORM_"` 仅命中注释里的 `DUPLICATE_TOOL_CALL_ID` 描述）。
- `ApiError.code` 对后端 400 响应体会取体里的 `code`（`frontend/src/api/http.ts:70-75`），故 400 到达 `submitFrontendToolResult` 时 `error.code === 'FORM_RESULT_REJECTED'`，但当前 catch 只特判 `DUPLICATE_TOOL_CALL_ID`，其余走 `this.notice = error.message`（577 行）——即会以通用提示呈现，且**不会重试/不会自动带 cancelled**。

### 2.4 `frontend/src/types/` 下与 AI 帧/工具相关的类型文件

| 文件 | 相关内容与行号 |
|---|---|
| `types/sse.ts`（354 行） | 帧类型联合 `SseFrame`（259-275 行）、`SseFrontendToolRequestFrame`（175-186 行）、`SseFrontendToolResultFrame`（188-200 行）、`SseSuspendedFrame`（202-210 行）、`isSuspendedFrame`（282-287 行）、`toSseFrame` 运行时校验（289 行起） |
| `types/ai.ts`（255 行） | `AiChatRequest`（30-36 行）、`ConfirmRequest/Result`（38-62 行）、`FrontendToolResultRequest`（64-72 行）、`FrontendToolResultAck`（74-84 行）、展示态 `ToolRunStatus`（149-155 行）、`ToolRun`（157-171 行）、`ChatMessage`（173-189 行）、`PendingToolCall`（191-208 行） |
| `types/tools.ts`（189 行） | `ToolRiskLevel`/`ToolChannel`（40-44 行）、`ToolAvailability`（52 行）、`ToolArgSpec`（55-61 行）、`FrontendToolDescriptor`（64-73 行）、`FrontendToolName`（76 行）、`FRONTEND_TOOLS`（79-104 行）、`findFrontendTool`（107-109 行）、`FrontendToolExecution`（112-117 行）、`UiEvent`/`UI_EVENT_TYPES`（128-162 行） |
| `types/api.ts`（136 行） | `ApiErrorBody`（43-48 行）、`ErrorCode`（51-72 行） |
| `types/index.ts` | 桶文件，`export * from './sse' | './ai' | './tools' | …` |
| `types/condition.ts` | 见下 |

### 2.5 `frontend/src/types/condition.ts` 顶部"后端对未知操作符视为通过"注释

原文（`frontend/src/types/condition.ts:23-27`）：

```
/**
 * 操作符白名单（ConditionEvaluator.matchesOp 的 case 全量，含 LIKE）。
 *
 * 后端对未知操作符的处理是"视为通过"（不丢行），前端类型只收窄登记在册的算子。
 */
```

即：行 23-27 为完整 JSDoc 块，**关键句在第 26 行**。`ConditionOperator` 联合类型在其后（28-40 行）。

---

## 3. 视觉/组件基线（Element Plus + Tailwind）

- 最典型表单组件：`frontend/src/components/wizard/ConditionForm.vue`（216 行）—— 全模板用 `el-select`/`el-option`/`el-date-picker`/`el-input`/`el-button` + 内联 Tailwind 类（如 `class="flex gap-2 mb-2 items-center"`、`class="w-[180px]"`、`class="text-xs text-[#c0c4cc]"`），脚本用 `<script setup lang="ts">` + `defineProps/defineEmits`，**无 `<style>` 块**。日期控件口径 `value-format="YYYY-MM-DD"`（43-49 行）与后端 `date` 型的 `yyyy-MM-dd` 要求一致。
- 对话框/挂起卡基线：`AiPanel/AiPanel.vue:168-217`（挂起卡外壳用 `border border-solid border-[#dcdfe6] rounded-md p-2 bg-white` + `el-tag`/`el-input`/`el-button`）、`42-104`（`el-alert` 降级提示）、`222-242`（输入区）。

**是否禁自写 CSS：是，规则成文。** 依据 `docs/s4/S4.4-前端合流规格.md:20-43`（"§1.1 视觉基准与样式纪律"，业务方 2026-10-07 指令）：

> 1. **组件全部用 Element Plus**：能用 EP 组件实现的，不允许自己写——禁止自造等价物…
> 2. **样式全部内联 Tailwind 工具类**：能用 TailwindCSS 工具类实现的，不允许自己写 CSS——手写 CSS 去实现 Tailwind 能做的事，即为违规。
> 3. **scoped CSS 仅允许蓝本确有的极少数情况，且逐条注明原因**：kimi-k3 全前端只有 **1 处、18 行**（`AiPanel.vue` 的 `.typing .dot` + `@keyframes blink`…超出蓝本已有范围的新增 scoped CSS 一律违规…

代码现状核对：全 `frontend/src` 只有 **1 个 `<style>` 块** —— `AiPanel/AiPanel.vue:520-538`（`<style scoped>`，2 行标签 + 2 行注释 + 15 行规则，正是打字动画；其内注释写明"属规格 §1.1 允许的唯一 scoped CSS"）。其余 `.vue`（`JobProgress.vue`、`SpreadGrid.vue`、`TaskWizard.vue`、`wizard/ConditionForm.vue`、5 个 view、`App.vue`）**0 处 scoped CSS**。全局样式只在 `frontend/src/styles/main.css`（30 行：`@tailwind` 三行 + 最小复位 + `@layer utilities { .writing-vertical }`）；`frontend/tailwind.config.js` 关闭 `preflight`（避免与 EP/SpreadJS 冲突）并扩展色 `workspace: '#f5f7fa'`、`aiPanel: '#fafbfc'`。

---

## 4. 前端构建/校验命令

`frontend/package.json`（包管理器为 **yarn**，存在 `frontend/yarn.lock`）：

```json
"scripts": {
  "dev": "vite",
  "build": "vue-tsc --noEmit && node scripts/build.mjs",
  "typecheck": "vue-tsc --noEmit",
  "preview": "vite preview"
}
```

- **没有 `lint` 脚本**（devDependencies 里也无 eslint/prettier）；CI 只用这两个：`.github/workflows/ci.yml:63-67` → `yarn typecheck`、`yarn build`（工作目录 `frontend`，Node 22，`yarn install --frozen-lockfile`）。
- `scripts/build.mjs` 是构建包装（把 `TMP/TEMP` 指到项目内 `.tmp-esbuild` 再调 `vite build`）。

---

## 5. GF-B 起手需要知道的现状缺口（纯事实登记，不含建议）

- 前端 **0 处**引用 `generative_form`；`AiPanel/AiPanel.vue:288-314` 的 `TOOL_LABELS` 无 `generative_form` 条目（缺标签时 `toolLabel()` 回落显示原名，`AiPanel.vue:367-369`）。
- 回灌请求类型 `FrontendToolResultRequest`（`types/ai.ts:64-72`）**无 `cancelled` 字段**；`submitFrontendToolResult`（`stores/ai.ts:564`）**无 `cancelled` 形参**；前端从不发送 `cancelled:true`。
- `ErrorCode`（`types/api.ts:51-72`）**无** `FORM_SCHEMA_REJECTED`/`FORM_RESULT_REJECTED`/`FRONTEND_CANCELLED`；`submitFrontendToolResult` 的 catch（`stores/ai.ts:572-578`）只特判 `DUPLICATE_TOOL_CALL_ID`。
- 前端工具执行器对未知工具的兜底文本（`utils/frontend-tools.ts:162-166`）即 §8.3 所指"会把挂起拖到 120s 超时"的那段文本。
- `frontend_tool_request` 帧到达后**立即执行**（`stores/ai.ts:774-775`），无一等公民的"等待用户提交表单"态；`pendingCall.status` 联合类型（`types/ai.ts:207`）为 `'pending' | 'running' | 'approved' | 'rejected' | 'expired'`。
- 仓库无 `AGENTS.md`；当前 HEAD `3de9d02`（GF-A 证据提交），工作树干净，分支 `<OLD_REPO_DIR>`。
