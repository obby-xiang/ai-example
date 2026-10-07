# GF-C E2E 用例设计（GLM-5.3）——生成式表单端到端联合验证棒（设计稿）

> 角色：复核与测试（GLM-5.3）。**只设计与取证，不改产品代码、不做任何 git 写操作**；本文档为唯一新增文件，保持未提交（提交官统一入库）。
> 仓库：`<REPO_ROOT>`，分支 `main`，HEAD `d19da30`（GF-B 已入库）。
> 设计日期：2026-10-07。
> 脱敏：不出现真实盘符（统一 `<REPO_ROOT>`）、密钥（统一 `***`）、用户名。
> 证据等级标注：【实测】= 本设计过程中实际读码/跑命令验证；【推断】= 由代码路径推演、未实跑；【假设】= 需执行棒落地时才能确认。
> 执行边界：设计文档。本文不落任何脚本改动；执行棒按 §5 的集成方案施工后，须照跑"22 既有用例 + GF 新用例"全量一轮取证。

---

## 0. 结论速览

| 项 | 值 |
|---|---|
| 新增用例数 | **5 个计入主计数（GF1–GF5）+ 1 个可选观察用例（GF6，默认不启用）** |
| 覆盖路径 | FILTER 回灌续跑 / CLARIFY 回灌续跑 / 取消终态 / 复核拒绝（400→改值重发）/ 幂等（409 双向）|
| 既有 22 用例 | **零断言改动**；对共享机制有一处**加法式加固**（§5.3，防"模型自发调表单"把既有用例炸成 400 异常，该风险 GF-A 期已登记未发生）【实测】 |
| 阻碍性问题 | **未发现**。全部契约点在 GF-A 单测与源码中有既成事实支撑，E2E 层无可预见的协议缺口；主要不确定性集中在"真实模型是否按提示词调用工具"，§3.3 给出降级判据隔离 |
| SKIP 底线 | 模型未触发表单记 **SKIP**（`GFSKIP=n` 单列保留）；**GF1–GF4 全部 SKIP ⇒ 整棒判 FAIL（退出码 1）**（裁决 #1，见 §3.3 与文末裁决结论） |

---

## 1. 取证基础（设计所依据的已验证事实）

以下事实全部为本设计过程中**实际读码**确认（标注文件:行号），执行棒可直接对照：

### 1.1 既有 E2E 机制（`scripts/verify-e2e.ps1`，1421 行）【实测】

| 机制 | 位置 | 要点 |
|---|---|---|
| `Read-Sse` | :202-247 | POST/GET 皆可；`$stopOn` 帧型命中即断开；`$sink` 收集帧；180s 硬超时；**预期内早退不视为失败** |
| `Invoke-AiTurn` | :253-289 | 一轮对话 + 挂起处理循环：首帧 `start` 取 `runId`，按 `seq` 差量重挂 `GET /api/ai/events/{runId}?lastSeq=N`；stopOn 集合含 `frontend_tool_request`；挂起帧取**最后一条**交给 `$onSuspend`，回灌后 500ms 再重挂续读 |
| `Invoke-AiTurn` 的回灌默认值 | :283-284 | 前端工具无自定义 result 时回灌 `'{"ok":true,"source":"e2e"}'` —— **对 generative_form 必然被闸门 400 拒**（该值不是 schema 值 JSON） |
| `PostJsonRaw` | :113-117 | 非 2xx 即 `throw` —— 不可用于预期 400/409 的回灌 |
| `Expect-Fail` | :126-132 | 返回原始响应不断言 success；400/409 裸 Map 响应（无 `success` 字段）可安全通过 |
| `$script:continueHandler` | :293-297 | 通用"继续型"挂起处理器：确认门一律拒、前端工具回灌 `{"ok":true,"source":"e2e"}` |
| 预清理 | :402-414 | 只清 `S5B-*` 任务与 `E2E_TEMP` |
| TC15 | :1041-1091 | 真实模型不调用工具时**换措辞重试 1 次**（点名工具名）的先例 |
| TC17 | :1160-1242 | 真实模型抖动的既有对策：显式点名提示词 + 拒绝路径记录 toolCallId + 409 重复决策断言 + **确认路径 3 次尝试换会话重试** |
| Q8 项 | :1386-1415 | 独立计数（`q8pass/q8fail`）、不计入退出码的先例 |
| TC19 | :1348-1379 | 收尾清理：删 `S5B-*` 任务、`E2E_TEMP`，演示数据恢复 |
| 结果口径 | :1418-1421 | `PASS/FAIL` 主计数 + `exit 1` 语义 |

### 1.2 后端契约（帧 / 端点 / 状态机）【实测】

| 事实 | 位置 |
|---|---|
| `frontend_tool_request` 帧：`{type, seq, runId, toolCallId, name, args(原始 JSON 字符串), timeoutSeconds, expiresAt, callback}` | `SseChatEmitter.java:263-274` |
| `frontend_tool_result` 结局帧：`{toolCallId, name, ok, executed:false, status, messageId, result}` | `SseChatEmitter.java:276-286` |
| `delta` 帧正文在 `text` 字段 | `SseChatEmitter.java:149-154` |
| 回灌端点三分支映射：NOT_FOUND→409 `UNKNOWN_TOOL_CALL`；DUPLICATE→409 `DUPLICATE_TOOL_CALL_ID`；REJECTED→400（`FORM_RESULT_REJECTED`，body 含 `reasons`/`status:"PENDING"`/`note`）；ACCEPTED→200（`status:"FRONTEND_RESULT"` 或取消时 `"FRONTEND_CANCELLED"`） | `AiController.java:233-272, 535-550` |
| 取消路径：`cancelled:true` → `dismiss()` → 状态 `FRONTEND_CANCELLED`、结果文本 `FRONTEND_CANCELLED：用户取消了该前端操作（关闭表单/放弃填写）…`、结局帧 `ok=false`、日志 `前端工具被用户取消 runId={} toolCallId={} name={}`（INFO） | `ConfirmGate.java:346-357, 395-410` |
| 拒绝路径：复核不过 → **状态不变、不发帧、不唤醒**，日志 `前端工具回灌被安全闸拒绝 runId={} toolCallId={} name={} code={}（状态不变，挂起继续等待）：{}`（WARN） | `ConfirmGate.java:358-363` |
| 诊断端点 `GET /api/ai/pending/{runId}` → **`ApiResponse` 信封**（`{"success":true,"message":null,"code":null,"data":[…]}`），**条目数组在 `data` 内**（条目字段 `toolCallId/name/arguments/status/reason/resultText/executed/resolvedAtMs` 等）；断言须先取 `.data` 再按 `toolCallId` 过滤，可直接断言挂起状态。**（2026-10-07 红队纠正：原记“条目数组、非信封”为误述，该误述是 K2.8 实现 P0-1 的源头）** | `AiController.java:557-565`（:559 签名 `ApiResponse<List<Map<String,Object>>>`，:564 `ApiResponse.ok(items)`）、`PendingToolCall.java:111-128` |
| reattach 回放**不含 delta/heartbeat**但**含 `frontend_tool_request` 等状态帧** | `SseChatEmitter.java:361-367` |
| 前端通道等待上限 `app.ai.hitl.timeout` 默认 **120s** | `AiProperties.java:70` |
| 入参闸门：非法 schema **不挂起**，直接 `frontend_tool_result(ok=false, status=REJECTED_ARGUMENTS)` 回填 + WARN 日志 `前端工具入参被安全闸拒绝…` | `SpToolCallingManager.java:272-316` |

### 1.3 披露与提示词现状（设计问题 1 的勘察结论）【实测】

- 工具注解：`@ToolScope({"page:tasks","task:*"})`、`READ` + `FRONTEND`（`AiTools.java:493-495`）。
- **工具描述有场景引导**（`AiTools.java:490-492`）：`"在对话内渲染一张结构化表单并等待用户填写/选择（用于向用户收集筛选条件，或在信息不足时向用户澄清）…用户提交后你会收到字段 key→值的 JSON，用户关闭表单则收到取消终态（无数据）"`；`FormSpec` 及各 `@ToolParam` 描述完整给出 schema 形状与六型白名单（`AiTools.java:497, 513-550`）。
- **系统提示词完全未提及该工具**：`ResilientChatService.java:98-124` 的 `SYSTEM_PROMPT` 九条工作原则覆盖导出/建任务/确认卡，**没有任何"何时用表单收集条件/澄清"的指令**。
- 结论：模型对 `generative_form` 的认知只有工具描述一层；"不保证主动调用"是结构性事实，**E2E 提示词必须显式点名工具并给定字段清单**（§3.1）。是否应在 `SYSTEM_PROMPT` 增补表单场景指引，登记为 §8 观察项 O2（产品决策，不属本棒）。

### 1.4 其它已验证细节【实测】

- PowerShell 版本 5.1；`ConvertTo-Json -Compress` 对**单元素嵌套数组**正确产出 `["a"]`（已在本机实测），GF 用例构造 `multi_select` 单元素值 JSON 无需绕行。**限定（本轮补全）**：该结论仅对"数组嵌套在值对象内"成立——裸单元素数组须走 `-InputObject`（`ConvertTo-Json -Compress -InputObject @('a')` → `["a"]`），若经管道传递会被展开成标量（`@('a') | ConvertTo-Json -Compress` → `"a"`），届时 `multi_select` 的单元素值会退化成字符串而遭闸门 400 拒。GF 用例的值 JSON 是"对象 + 数组字段"形状，故不受影响；`New-FormValues` 拼装时须保持对象外壳、不要把字段值单独管道序列化【实测：本轮在本机 PS 5.1.26100.9444 上照跑三式确认】。
- 前端 `stores/ai.ts:939-947`：`frontend_tool_request` 且 `name==='generative_form'` 走 `openGenerativeForm` 特判，**不落通用执行器**；结局帧按 `toolCallId` 精确收敛 `activeForm`。
- 前端 `frontend/src/components/AiPanel/AiPanel.vue:202-205`：挂起卡渲染条件是 `m.pendingCall.kind==='FRONTEND' && m.pendingCall.name==='generative_form'` 且 `liveForm.toolCallId === m.pendingCall.toolCallId`（§7 双实例问题的机制基础）。
- `set_condition` 披露域 `{page:tasks, task:EXPORT}`（`AiTools.java:400-401`），浏览器侧执行走 `WORKSPACE_ACTIONS`（`frontend/src/utils/frontend-tools.ts:262` 起）——"筛选真实生效"需浏览器在场，脚本层只能以值回显断言替代（§4 GF1 说明）。

---

## 2. 设计总览

| 用例 | 路径 | 上下文 | 模型交互轮次 | 主计数 |
|---|---|---|---|---|
| GF1 | FILTER：schema 下发 → 回灌值 JSON → 续跑值回显 | `page:tasks` | 1 | ✓ |
| GF2 | CLARIFY：schema 下发 → 回灌 → 续跑值回显 | `task:EXPORT/QUERY_COND` | 1 | ✓ |
| GF3 | 取消：回灌 `cancelled:true` → `FRONTEND_CANCELLED` 终态 → 模型收尾 | `page:tasks` | 1 | ✓ |
| GF4 | 拒绝：违规值 400 `FORM_RESULT_REJECTED`（PENDING 保持）→ 同 toolCallId 改值重发成功 → 续跑 | `page:tasks` | 1 | ✓ |
| GF5 | 幂等：已决条目重复回灌（值路径 + 取消路径）→ 409 `DUPLICATE_TOOL_CALL_ID` | 复用 GF3/GF4 的 runId，无新增模型轮次 | 0 | ✓ |
| GF6（可选） | 入参闸门：诱导非法 schema → 不挂起 + `REJECTED_ARGUMENTS` 结局帧 | `page:tasks` | 1 | 默认不启用 |

设计原则：

1. **不依赖浏览器**：脚本扮演前端（读 SSE、POST 回灌），与 TC16 先例同构。
2. **提示词确定性优先**：所有 GF 提示词显式点名 `generative_form`、逐字段给定 key/label/type/选项，并显式禁止文字提问与其它工具（§3.1）。
3. **断言自适应**：回灌值由脚本**按实际收到的 schema 现场合成**（按字段 type 选值），不赌模型给的 key 名——但提示词固定 key，使断言可收紧到具体值。
4. **既有 22 用例零断言改动**：GF 块整体插在 TC16 与 TC17 之间；共享机制仅做加法式加固（§5.3）。

---

## 3. 设计问题 1：触发策略与降级判据

### 3.1 确定性提示词策略

每条 GF 提示词固定四要素（模板）：

1. **点名工具**："请调用 generative_form 工具"（参照 TC15 第二次尝试 `请调用 list_config_defs 工具…` 的既有先例，verify-e2e.ps1:1047）；
2. **给定 scenario 与字段清单**：逐字段写明 `key / label / type / required / options`，让模型无需自由发挥；
3. **回显指令**：要求模型在收到填写结果后"把每个字段按 `key=值` 原样逐行回报"——把"续跑产出正确结果"变成可断言的文本（模型可能复述走样，断言放宽为"包含各值的子串"，见 §4）；
4. **负向约束**："不要用文字向我提问，也不要调用 generative_form 以外的任何工具，不会出现确认卡片"——压掉模型改走 `set_condition`/文字澄清的旁路（`set_condition` 在 `page:tasks` 同样披露，AiTools.java:400-401，必须显式排除）【实测】。

### 3.2 重试策略

与 TC17 同口径【实测】：每次尝试**换新会话**（会话记忆互相隔离，TC18 已验证），单用例最多 **3 次尝试**；每次尝试之间 `Start-Sleep 2`。GF 用例的会话命名 `gfc-<guid>`（与 `s5b-` 前缀区分，TC19 不清理它们——会话无清理端点，与既有口径一致）。

### 3.3 降级判据（区分"模型行为问题"与"产品缺陷"）

每次尝试失败时，按下列**判定表**归因后再决定重试/报 FAIL（归因证据全部可机械采集）：

| 症状 | 采集证据 | 判定 | 处置 |
|---|---|---|---|
| 整轮无 `frontend_tool_request`，也无 `tool_start(name=generative_form)` | ① `GET /api/ai/tools?page=tasks`（或对应上下文）返回的 `toolNames` 含 `generative_form`；② `/api/ai/history/{sessionId}` 的 ASSISTANT 消息无该工具卡片 | **披露正常 ⇒ 模型行为问题**（提示词已点名仍未调用） | 重试；3 次皆如此 → 用例判 **SKIP（模型未触发）**并退出码不变，摘要行打印 `GFSKIP=n`——不把上游模型抖动记成产品 FAIL【已裁决：指挥官 K3，见文末裁决 #1】。**硬性底线**：若 GF1–GF4 **全部** SKIP，整棒判 **FAIL（退出码 1）**——GF 已计入主计数，全 SKIP 等于本棒零有效验证（`GFSKIP=n` 摘要**单列保留**，与主计数并列打印） |
| 收到 `tool_start` 但**没有** `frontend_tool_request`，随后出现 `frontend_tool_result(ok=false, status=REJECTED_ARGUMENTS)` | 帧序列 + 后端日志 `前端工具入参被安全闸拒绝 … code=FORM_SCHEMA_REJECTED` | **闸门①按设计工作**（模型给了非法 schema）；连续 3 次同症状 → 判"工具描述引导不足"，登记观察项，不计链路 FAIL | 重试（模型读到结构化拒绝原因可自纠） |
| 收到 `frontend_tool_request` 但 `args` 缺 `form` 键 / `scenario` 非 FILTER|CLARIFY | 帧原文 | 入参闸门**未拦截**（`form` 缺失属非法 schema，理应走 REJECTED_ARGUMENTS）→ **产品缺陷** | FAIL |
| 回灌 POST 响应码/状态机与 §1.2 表不符 | 响应体原文 | **产品缺陷** | FAIL |
| 回灌 200 后重挂 `events` 读不到 `frontend_tool_result` / `done`（超过 90s） | reattach 帧序 + 后端日志 | **产品缺陷**（唤醒/续跑链路） | FAIL |
| 模型调了 `set_condition` 等其它前端工具（帧里出现其它 name） | 帧序列 | 模型行为问题（负向约束未被遵守） | 重试；3 次皆如此 → SKIP 同上 |

> 设计取舍说明：把"模型不配合"记为 SKIP 而非 FAIL，依据是本仓库既有证据口径——GF-A 补跑时"真实模型该轮未触发表单"并未被判为缺陷（GFa §7）。SKIP 口径会在执行棒报告里单独成列，不与链路断言混淆。

### 3.4 与"真实模型不确定性"的总体隔离（设计问题 6）

- **传输面断言不依赖模型文本**：帧序列、HTTP 码、挂起状态、日志关键字全部是确定性断言；模型文本只用于"值回显"宽松断言（子串包含）。
- **每用例独立会话 + 独立 fixture 任务**，失败互不传染。
- **GF5 完全不依赖模型**（复用 GF3/GF4 已决 toolCallId），是纯 HTTP 幂等断言。
- **GF6 默认不启用**：诱导模型产非法 schema 的服从率不可控（工具描述明写"禁止 HTML"，模型可能直接拒绝），启用与否留给执行棒按现场情况决定。

---

## 4. 用例定义（设计问题 2）

通用说明（适用 GF1–GF4）：

- **帧读取**：新助手 `Invoke-FormTurn`（§5.2）——与 `Invoke-AiTurn` 同构（`Read-Sse` + `lastSeq` 差量重挂 + 180s 预算），但把"回灌动作"外置为脚本块，使每个 GF 用例能拿到原始 HTTP 响应（`Invoke-AiTurn` 内嵌的 `PostJsonRaw` 在 400/409 时会 throw，不满足 GF3/GF4/GF5 需要）【实测：PostJsonRaw 行为见 :113-117】。
- **值合成函数 `New-FormValues($form)`**【推断：待执行棒实现，逻辑如下】：遍历 `fields`，按 `type` 取值——`text`→`"E2EGFC"`；`number`→`1`；`boolean`→`$true`；`date`→`"2026-10-07"`；`enum`→第一个 `options[].value`；`multi_select`→`@(第一个 option 的 value)`；跳过 `required=false` 的字段以验证"可选字段缺省合法"（GFa §6.1 SubmissionRules 已锁该语义）。产物 `ConvertTo-Json -Compress` 后作为 `result` 字符串 POST。
- **args 解析**：`frontend_tool_request.args` 是原始 JSON 字符串（`SseChatEmitter.java:267`【实测】），`ConvertFrom-Json` 后取 `.form`。

### GF1 筛选条件（FILTER）——回灌续跑

| 项 | 内容 |
|---|---|
| 前置 | 无需任务；上下文 `@{ page = 'tasks' }`（`page:tasks` 披露 `generative_form`，GFa §5.2 披露矩阵已实测）。后端以约定参数启动 + `AI_API_KEY` 注入 + Redis 可用（与 S5b 同口径） |
| 提示词原文 | `请调用 generative_form 工具（scenario=FILTER）出一张收集导出筛选条件的表单，字段就用这四个，key 和类型必须一致：keyword（文本，必填，占位提示"编码或名称关键字"）、minRows（数字，非必填）、effectiveDate（日期，格式 yyyy-MM-dd，非必填）、scope（下拉单选，选项 XN 和 HD）。不要用文字向我提问，也不要调用 generative_form 以外的任何工具，不会出现确认卡片。我填完后，请把每个字段按 key=值 原样逐行回报。` |
| 脚本动作 | 收到 `frontend_tool_request(name=generative_form)` 后：①断言帧契约（下）；②解析 `args.form`，按提示词校验 `scenario=FILTER` 且四 key 齐备（key 由提示词固定，模型换 key 即按 §3.3 判定）；③`New-FormValues` 合成值（`minRows/effectiveDate` 非必填故省略，验证缺省合法）；④`POST /api/ai/frontend-tool-result {runId, toolCallId, result, source='e2e-gf'}` |
| 预期帧序列 | `start` → `delta`（可能）→ `tool_start(generative_form)` → **`frontend_tool_request`**（此处 stopOn 断开）→ 【回灌 200】→ 重挂：**`frontend_tool_result(ok=true, status=FRONTEND_RESULT, executed=false)`** → `delta`（值回显）→ `done` |
| 断言点 | A1 帧 `name=generative_form`、`toolCallId` 非空、`timeoutSeconds=120`、`expiresAt` 为数字【实测：SseChatEmitter:268-271 固定置入】；A2 `args.form.scenario='FILTER'`、四 key 齐备、六型白名单内；A3 回灌响应 HTTP 200 且 `status='FRONTEND_RESULT'`、`accepted=true`、`executed=false`；A4 `GET /api/ai/pending/{runId}` 对应条目 `status='FRONTEND_RESULT'`、`resultText` 等于回灌 JSON；A5 续跑收到 `frontend_tool_result(ok=true)` 恰 1 条 + `done` 恰 1 条且无 `error`；A6 值回显：全部 `delta` 帧文本拼接后**包含** `E2EGFC`、`XN`、`scope=…`（子串断言，容错模型措辞）；A7 后端日志含 `AI 轮次开始 … 披露工具=` 且该清单含 `generative_form` |
| 证据形态 | §6 表 GF1 行 |

> 关于"筛选生效"：真实生效需浏览器执行 `set_condition`（工作区动作，`frontend/src/utils/frontend-tools.ts` WORKSPACE_ACTIONS）【实测】。脚本层无浏览器，故以 **A6 值回显**替代"筛选生效"断言——它证明回灌值 JSON **原样进入了模型上下文**（GFa §3.3 锁定"回灌的就是字段 key→值的 JSON"）；浏览器侧的"表单值→向导条件落库"留给前端手动冒烟（§7 同口径）。此取舍【推断】为可达范围内最强断言。

### GF2 询问澄清（CLARIFY）——回灌续跑

| 项 | 内容 |
|---|---|
| 前置 | 新建导出任务并推到查询条件步（复用既有封装：`New-ExportTask 'GFC-TC-GF2-澄清' @('CURRENCY')` + `PutJson /api/tasks/{id}/step @{step='QUERY_COND'}`）；上下文 `@{ page='export'; taskId; taskType='EXPORT'; step='QUERY_COND' }`（`task:EXPORT/QUERY_COND` 在 `generative_form` 披露域内，GFa §5.2） |
| 提示词原文 | `我想继续这个导出任务，但你缺两个信息。请调用 generative_form 工具（scenario=CLARIFY）向我澄清，字段就用这两个，key 和类型必须一致：exportScope（下拉单选，选项 ALL 和 SELECTED）、includeInactive（开关布尔）。不要用文字提问，也不要调用 generative_form 以外的任何工具。我填完后，把两个字段按 key=值 原样逐行回报。` |
| 脚本动作 | 同 GF1；值合成取 `enum`→`ALL`、`boolean`→`$true`（按实际 options/类型自适应） |
| 预期帧序列 | 与 GF1 同构 |
| 断言点 | A1 帧契约同 GF1-A1；A2 `scenario='CLARIFY'`、两 key 齐备；A3/A4/A5 同 GF1；A6 回显包含 `ALL` 与 `includeInactive=…`；A7 任务 fixture 用例内自删（`DeleteJson`），标题 `GFC-*` 进预清理扩展清单（§5.4） |

### GF3 取消路径

| 项 | 内容 |
|---|---|
| 前置 | 同 GF1（`page:tasks`，无需任务） |
| 提示词原文 | `请调用 generative_form 工具（scenario=FILTER）出一张表单收集一个筛选条件：keyword（文本，必填）。不要用文字提问，也不要调用其它任何工具。我填完后，把结果按 key=值 回报。` |
| 脚本动作 | 收到 `frontend_tool_request` 后 `POST /api/ai/frontend-tool-result {runId, toolCallId, cancelled=$true, source='e2e-gf'}`（**不带 result**，对齐 GFa §8.2"result 可省"） |
| 预期帧序列 | `start` → `tool_start` → **`frontend_tool_request`** → 【取消 POST 200】→ 重挂：**`frontend_tool_result(ok=false, status=FRONTEND_CANCELLED)`** → `delta`（模型收尾文本）→ `done` |
| 断言点 | A1 取消 POST 响应 200 且 `status='FRONTEND_CANCELLED'`、`cancelled=true`、`executed=false`【实测：GFa §3.4 已照录同形响应】；A2 `frontend_tool_result` 帧 `ok=false`、`status='FRONTEND_CANCELLED'`、`result` 以 `FRONTEND_CANCELLED：` 开头【实测：ConfirmGate.java:400-401 固定文本】；A3 `done` 到达且无 `error`；A4 `GET /api/ai/pending/{runId}` 条目 `status='FRONTEND_CANCELLED'`、`reason` 含 `FRONTEND_DISMISSED`、`executed=false`【实测：ConfirmGate.java:396-399】；A5 后端日志含 `前端工具被用户取消 runId=… name=generative_form`【实测：ConfirmGate.java:409 INFO 行】；A6 **不出现** `frontend_tool_request` 的重复下发（同 toolCallId 只出现一次）；A7 模型收尾 `delta` 非空且**不包含** `E2EGFC` 之类"收到了值"的表述（弱断言：非空即可，措辞不赌）→ 收敛为"delta 至少 1 条" |

> 记录 GF3 的 `runId/toolCallId` 供 GF5 幂等复用。

### GF4 复核拒绝路径（400 → 改值重发）

| 项 | 内容 |
|---|---|
| 前置 | 同 GF1 |
| 提示词原文 | `请调用 generative_form 工具（scenario=FILTER）出一张表单收集筛选条件，字段 key 和类型必须一致：keyword（文本，必填）、amount（数字）、scope（下拉单选，选项 XN 和 HD）。不要用文字提问，也不要调用其它任何工具。我填完后，把每个字段按 key=值 原样逐行回报。` |
| 脚本动作 | ① 先 POST **违规值**：`{"keyword":123,"amount":"abc","scope":"ZZ","extra":"<x>"}`（类型错 ×3 + 未知字段，按后端校验规则推演）；② 断言 400 后**同 toolCallId** POST 合规值 `{"keyword":"E2EGFC","scope":"XN"}`（`amount` 非必填省略） |
| 预期帧序列 | `start` → `tool_start` → **`frontend_tool_request`** →【违规 POST → **400，无帧**】→【合规 POST 200】→ 重挂：`frontend_tool_result(ok=true, status=FRONTEND_RESULT)` → `delta` → `done` |
| 断言点 | A1 违规 POST：HTTP **400**、`code='FORM_RESULT_REJECTED'`、`reasons` 数组非空且**≥3 条**（三处类型错 + 未知字段，按后端校验规则**推演应为 4 条**；GFa §3.3 实测的是另一组字段，不作为本用例的计数依据——断言取 ≥3 留余量）、**`status='PENDING'`**、`note` 含"挂起仍在等待"；A2 400 之后 `GET /api/ai/pending/{runId}` 条目**仍为 `PENDING`**（挂起未被消费）；A3 400 之后 3s 内重挂 `events`：**不出现** `frontend_tool_result`（不发帧、不唤醒）；A4 同 toolCallId 改值重发：HTTP 200、`status='FRONTEND_RESULT'`、`accepted=true`；A5 之后重挂：恰好 1 条 `frontend_tool_result(ok=true)`（无 ok=false 的前置结局帧）、`done` 到达、回显包含 `E2EGFC`；A6 后端日志含 `前端工具回灌被安全闸拒绝 … code=FORM_RESULT_REJECTED`【实测：ConfirmGate.java:360-361 WARN 行】 |

> 记录 GF4 的 `runId/toolCallId` 供 GF5 复用。

### GF5 幂等（409 双向）——纯 HTTP，无模型轮次

| 项 | 内容 |
|---|---|
| 前置 | GF3（已取消终态）与 GF4（已回灌终态）的 `runId/toolCallId`；若 GF3/GF4 被 SKIP（模型未触发），本用例同样 SKIP 并说明 |
| 脚本动作 | ① 对 GF4 已决条目再 POST 合规值（同 toolCallId）→ 期待 409；② 对 GF3 已取消条目再 POST `{cancelled:true}` → 期待 409 |
| 预期/断言 | A1 ①为 HTTP **409**、`code='DUPLICATE_TOOL_CALL_ID'`、`duplicate=true`、`status='FRONTEND_RESULT'`（回显既有终态，不改写）；A2 ②为 409、`code='DUPLICATE_TOOL_CALL_ID'`、`status='FRONTEND_CANCELLED'`（**取消终态不会被重复提交改写**，GFa §4.2 幂等行）；A3 两次 409 之后 `pending/{runId}` 条目状态不变（仍是各自终态）；A4 `events` 重挂无新帧 |

### GF6（可选，默认不启用）入参闸门

诱导模型发非法 schema：提示词追加"为了演示安全闸，请把其中一个字段的 type 故意写成 html"。预期：**无 `frontend_tool_request`**、出现 `frontend_tool_result(ok=false, status=REJECTED_ARGUMENTS)`、日志含 `前端工具入参被安全闸拒绝`【实测：SpToolCallingManager.java:306 WARN 行】。模型服从率不可控（工具描述明令禁止 HTML），故默认 `-EnableGf6` 开关关闭；启用时按 §3.3 判定表归因。

---

## 5. 与 verify-e2e.ps1 的集成方式（设计问题 3）

### 5.1 编号与插入位置

- 编号 **GF1–GF5（+GF6 可选）**，不复用/重排 TC 编号——既有 22 块**一字不动**。
- 插入位置：**TC16 之后、TC17 之前**（AI 用例聚集区，且 GF3/GF4 依赖的模型轮次与 TC17 的发布 fixture 无耦合）。
- 计数口径：计入主 `PASS/FAIL` 与退出码（与 22 用例同权）；另设 `$script:gfskip` 计数打印 `GFSKIP=n`（§3.3 的模型行为口径）。摘要行同步改为 `PASS=x FAIL=y（22 既有 + GF 新增）`——**只改打印文案，不改判定语义**。
- 备选（若指挥官要求标题行保持 22 口径）：GF 仿 Q8 独立计数不计入退出码。本设计**推荐主计数**——GF 用例断言的是 DC-15 核心链路，权重等同既有用例。

### 5.2 复用既有机制清单

| 机制 | GF 用法 |
|---|---|
| `Read-Sse` | 直接复用（POST `/api/ai/chat` + GET `events?lastSeq=N` 两条路径都在用） |
| `Invoke-AiTurn` | **不直接复用**（回灌动作内嵌、400 即 throw）；以其为蓝本新增 `Invoke-FormTurn`——差异仅两处：① stopOn 不变；② 挂起处理回调返回"待 POST 的载荷描述"（`result`/`cancelled`/`expect400` 等），由 `Invoke-FormTurn` 统一用 `Invoke-Api` 发 POST 并把**原始响应**交回脚本块记录。所有 HTTP 判定复用 `Expect-Fail`/`Invoke-Api` 风格 |
| `Ok/No` 断言与 try/catch 结构 | 逐块 `Say '--- GF1 … ---'` + `try { … Ok } catch { No }`，与既有块完全同构 |
| `Get-LogText` / `Get-DisclosureMap` | GF1-A7、GF3-A5、GF4-A6 的日志断言；`-BackendLog` 未提供时这些子断言判 FAIL（与既有口径一致，脚本头 :27 已声明） |
| `GET /api/ai/pending/{runId}` | 新引入的断言通道（既有脚本未用过；只读诊断端点，AiController.java:557【实测】）——用例内新增 `$script:gf3Run/$script:gf3Call` 等记录变量，仿 TC17 的 `$script:rejectToolCallId` 先例【实测：verify-e2e.ps1:1180-1181】 |
| 会话与提示词重试 | 仿 TC15（换措辞）/TC17（3 次换会话） |

### 5.3 共享机制的加法式加固（唯一的既有代码触碰点）

**问题**【实测推演】：`Invoke-AiTurn` 对前端工具的默认回灌是 `'{"ok":true,"source":"e2e"}'`（:283-284，处理器未给 `result` 时兜底），且 `PostJsonRaw` 非 2xx 即 throw（:113-117）。若真实模型在**既有任何用例**中自发调用 `generative_form`——**直接受扰的是四处 `Invoke-AiTurn` 调用点所在用例：TC15/TC16/TC17/TC18**——该通用值会被回灌闸门 400 拒 → throw → 该用例 FAIL。GF-A 补跑时此路径未出现（GFa §7 注 2 已登记为担忧），但它是既有 22 用例的一个**潜伏扰动面**，GF-C 落地后模型见到表单场景的概率上升。

> **受扰集合更正（2026-10-07 红队低-11 纠正）**：**TC22 不调用 `Invoke-AiTurn`**（仅 `GetJson /api/ai/history/{aiSession15}`，`verify-e2e.ps1:1924-1938`），故不计入直接受扰集合，**仅经由 TC15 写入的 `aiSession15` 会话间接受影响**；直接受扰集合为 **TC15/TC16/TC17/TC18**。

> **纠正（裁决 #2）**：真正危险的**不是**"没传处理器"的情形——`Invoke-AiTurn` 在 `$onSuspend` 为 `$null` 时直接 `break`（:277），根本不发 POST，挂起只会悬到 `app.ai.hitl.timeout`（120s）自然收摊。危险路径是**传了处理器、但处理器返回通用值**的情形：通用的 `$script:continueHandler`（:293-297）恒返回 `'{"ok":true,"source":"e2e"}'`，一旦落在表单挂起上就必然撞 400 拒绝分支【实测：:277 / :283-284 / :293-297 三处行为已逐行核对】。

**加固方案（加法、不改变既有可观测行为）**：在 `Invoke-AiTurn` 的前端工具回灌分支加一条兜底，**判据为**——只要挂起帧 `$s.name -eq 'generative_form'`，就**优先于 `$onSuspend` 返回的通用回灌值**，直接 POST `{runId, toolCallId, cancelled:$true, source='e2e'}`（对齐 GFa §8.3"前端能力不可用必须带 cancelled:true 收敛"的契约）；**仅当处理器显式返回表单专用载荷**（显式带 `cancelled`，或带非通用值 `result`）时才改用处理器载荷——保留 GF 用例（`Invoke-FormTurn`）与将来任何想主动回灌表单值的调用方的通路。该分支当前不可达（模型从未在既有用例中调用过该工具），故对 22 用例零行为影响；一旦模型自发调用，挂起立刻收敛（120s 超时被消掉），既有用例继续照跑。【实测：契约依据 GFa §8.3；【假设】：执行棒照跑全量后须核对 22 用例仍全绿以实证零扰动】

### 5.4 清理责任

- GF2 的 fixture 任务：**用例内自删**（`DeleteJson`，仿 Q8① 的即时清理先例 :1401【实测】），标题 `GFC-TC-GF2-*`。
- 预清理扩展：在既有预清理块（:402-414）**之后追加**一段同构的 `GFC-*` 任务清理（不改既有行，幂等重跑保障）。
- 会话（`gfc-*`）：无清理端点，与既有 `s5b-*` 会话同口径（留存于 AI 会话存储，随 TTL 过期）。
- GF 用例不碰演示数据（不导出不发布），TC19 的恢复逻辑不受影响。

### 5.5 对"既有 22 用例零扰动"的论证

| 扰动面 | 论证 |
|---|---|
| 断言 | 既有 22 块零改动；GF 块独立 try/catch，异常不外溢【实测：结构同构】 |
| 共享代码 | 仅 §5.3 一处加法兜底（当前不可达分支）+ 预清理追加段 + 摘要打印文案 |
| 数据面 | GF 只建/删 `GFC-*` 任务，不碰 `S5B-*`/`E2E_TEMP`/演示数据 |
| 会话面 | GF 用独立 `gfc-*` 会话，TC22 的历史断言用 `aiSession15`，无交集 |
| 模型行为面 | GF 提示词的负向约束把模型引导到 generative_form 单工具；不同会话无记忆串扰（TC18 已验证隔离）【实测】 |
| 耗时面 | GF1–GF4 各 1 个模型轮次（挂起+续跑在同轮内完成），预算与 TC16 同量级；GF5 零模型轮次 |

---

## 6. 逐用例预期证据形态（设计问题 4）

执行棒对照表（帧摘录为**预期形态**示意，`…` 为省略；`seq` 单调递增）：

| 用例 | 帧序列摘录（SSE） | HTTP 状态码 | 后端日志关键字 |
|---|---|---|---|
| GF1 | `data:{"type":"start","runId":…}` → `{"type":"tool_start","name":"generative_form","kind":"FRONTEND",…}` → `{"type":"frontend_tool_request","toolCallId":…,"name":"generative_form","args":"{\"form\":{\"scenario\":\"FILTER\",…}}","timeoutSeconds":120,"expiresAt":…}` → `{"type":"frontend_tool_result","ok":true,"status":"FRONTEND_RESULT","executed":false,…}` → `{"type":"delta","text":…}`×N → `{"type":"done",…}` | 回灌 POST → **200** `{accepted:true,status:"FRONTEND_RESULT"}`；`GET /api/ai/pending/{runId}` → 200，条目 `status:"FRONTEND_RESULT"` | `AI 轮次开始 … 披露工具=[…, generative_form, …]`（DEBUG）；**无** `安全闸拒绝`、**无** `前端工具被用户取消` |
| GF2 | 同 GF1（`scenario:"CLARIFY"`，两字段） | 同 GF1 | 同 GF1 |
| GF3 | `frontend_tool_request` → `{"type":"frontend_tool_result","ok":false,"status":"FRONTEND_CANCELLED","result":"FRONTEND_CANCELLED：用户取消了该前端操作…",…}` → `delta`×N → `done` | 取消 POST → **200** `{status:"FRONTEND_CANCELLED",cancelled:true}`；`pending` 条目 `status:"FRONTEND_CANCELLED"`,`reason:"FRONTEND_DISMISSED…"` | `前端工具被用户取消 runId=… toolCallId=… name=generative_form`（INFO） |
| GF4 | `frontend_tool_request` →【400 期间**无任何新帧**】→ `frontend_tool_result(ok=true,status="FRONTEND_RESULT")` → `delta` → `done` | 违规 POST → **400** `{code:"FORM_RESULT_REJECTED",reasons:[…≥3],status:"PENDING",note:"…挂起仍在等待…"}`；改值 POST → **200**；`pending` 在 400 后仍 `PENDING`、200 后 `FRONTEND_RESULT` | `前端工具回灌被安全闸拒绝 runId=… name=generative_form code=FORM_RESULT_REJECTED（状态不变，挂起继续等待）：[...]`（WARN） |
| GF5 | 两次 409 POST 后重挂 `events`：**无新帧** | ① 已决值条目重发 → **409** `{code:"DUPLICATE_TOOL_CALL_ID",status:"FRONTEND_RESULT",duplicate:true}`；② 已取消条目再取消 → **409** `{code:"DUPLICATE_TOOL_CALL_ID",status:"FRONTEND_CANCELLED"}` | 无新增关键日志（409 不落专用日志行，以响应体为证） |
| GF6（可选） | **无 `frontend_tool_request`**；`{"type":"frontend_tool_result","ok":false,"status":"REJECTED_ARGUMENTS",…}` → `delta` → `done` | 无回灌 POST（挂起从未发生） | `前端工具入参被安全闸拒绝 runId=… tool=generative_form code=FORM_SCHEMA_REJECTED（未挂起、未下发、未执行）：[...]`（WARN） |

原始记录归档建议（执行棒）：SSE 帧全量落 `<TMP>/gfc-<case>.sse.txt`、回灌请求/响应落 `<TMP>/gfc-<case>.http.txt`、后端日志由 `-BackendLog` 统一承载；附件入 `docs/evidence/` 时沿用 `GFc-附件-*` 命名。

---

## 7. 观察清单登记（设计问题 5）

### O1 GF-B 遗留："同一 toolCallId 双消息各挂挂起卡致 FormRenderer 双实例"——verify-e2e.ps1 **不可覆盖**

**判定：不可覆盖**。理由【实测（机制）+推断（现象）】：

1. 该问题是**浏览器渲染层**现象：`frontend/src/components/AiPanel/AiPanel.vue:202-205` 的渲染条件遍历每条消息的 `m.pendingCall`，若同一 `toolCallId` 的 `frontend_tool_request` 被处理两次落到两条 assistant 消息（如 reattach 回放 + 原帧、或历史镜像恢复 + 新帧），两条挂起卡都会通过 `liveForm.toolCallId === m.pendingCall.toolCallId` 匹配，各渲染一个 FormRenderer。**双实例成立与否取决于 Vue 组件树状态**，只有浏览器能看到。
2. verify-e2e.ps1 无浏览器：它只消费 SSE 帧与 HTTP 端点（脚本头注释自述"脚本本身扮演前端角色"）。脚本层最多能证明**服务端会重放同一帧**（reattach `lastSeq=0` 时 `frontend_tool_request` 会再次下发，`SseChatEmitter.java:361-367` 回放含该帧型【实测】）——这只是复现了问题的*前置输入*，不是问题本身；且"两次帧落到两条消息"是前端 store 的行为（`handleFrame` 对回放帧的处理路径），脚本完全观测不到。
3. 结论：登记为**前端手动/组件级验证项**，不进 E2E 脚本。

**替代验证建议**（按成本递增）：

- **建议 A（手动浏览器冒烟，零成本，推荐先行）**：按 GFb 复核 §6 的构造帧路径——在浏览器 console 对 Pinia store 连续两次 `handleFrame`（或 `openGenerativeForm`）投递**同一 toolCallId** 的 `frontend_tool_request` 帧（第二次投给新建的另一条 assistant 消息），肉眼核对是否出现两张 FormRenderer；顺带验证结局帧到达时两张卡是否都收敛。
- **建议 B（组件级测试，需先补测试工具链）**：当前 `frontend/package.json` 仅有 `dev/build/typecheck/preview` 脚本，无任何测试框架【实测】；引入 Vitest/@vue/test-utils 属**新增依赖**，受本棒"禁止引入依赖"约束不做——登记给后续棒裁决。
- **建议 C（产品侧修复优先）**：红队报告问题 1/2（`activeForm.runId` 死字段、断流路径不清态）与该现象同根（`activeForm` 与 `message.pendingCall` 双轨状态）。修复棒落地后，建议 A 的冒烟应复跑一次作为回归。
- 服务端可顺带固化的一条相关断言（已并入 GF3-A6）：同一 run 内同一 toolCallId 的 `frontend_tool_request` **不重复下发**（后端每 `(runId, toolCallId)` 只一条待决条目【实测：ConfirmGate 键控】）——即"双实例"的责任边界在前端回放/恢复路径，不在服务端重复下发。

### O2 系统提示词未引导表单场景

`SYSTEM_PROMPT`（ResilientChatService.java:98-124）无 `generative_form` 使用时机指引，模型"主动"用表单收集条件/澄清完全依赖工具描述【实测】。GF 系列用显式点名绕开该不确定性；是否在系统提示词补一条（如"需要用户补充条件/澄清时优先用 generative_form 而非连续文字追问"）属产品决策，登记待裁决。

### O3 模型自发调用 generative_form 对既有用例的潜伏扰动

见 §5.3。GF-C 落地加固后消除；执行棒全量照跑时应在报告中显式核对"全程 0 次模型在既有 22 用例中自发调用 generative_form"（或已触发且被 cancelled 兜底收敛，既有用例仍绿）。

---

## 8. 风险与边界（设计问题 6 补遗）

| 风险 | 隔离/处置 |
|---|---|
| 模型不调用工具（最大不确定源） | §3.1 提示词四要素 + §3.2 三次换会话重试 + §3.3 判定表（披露端点交叉取证归因）+ SKIP 口径 |
| 模型给合法但不符提示词的 schema（key/字段数漂移） | 断言按提示词收紧到具体 key；漂移按 §3.3 判定为"模型行为"重试；`New-FormValues` 自适应合成保证回灌值对**实际 schema** 合规（不因模型漂移误伤 400 路径） |
| 模型在挂起前先调其它前端工具（navigate_to 等） | `Invoke-FormTurn` 的挂起回调只对 `name=generative_form` 的帧走表单路径，其它前端工具按既有通用回灌继续（仿 TC17 handler17 的"回灌并继续"策略【实测：:1182-1196 注释即此教训】） |
| 回灌后唤醒/续跑延迟 | 重挂读流预算 180s（Read-Sse 硬上限）；GF4 的"400 后 3s 无帧"检查窗口 3s（闸门语义是不发帧，窗口只需覆盖正常发帧延迟） |
| 挂起超时（模型已下发但脚本断言失败退出） | 每个失败分支**收尾兜底**：若记录到 toolCallId 且 `pending` 仍 `PENDING`，用例 FAIL 前 POST `cancelled:true` 收敛挂起（避免 120s 悬置拖慢后续用例）——设计为 `finally` 语义 |
| 后端日志断言依赖 `-BackendLog` | 与既有口径一致（未提供判 FAIL，不静默跳过） |
| Redis 不可用 / key 缺失 | 沿用脚本头 :20-21 既有约定，AI 用例 FAIL 属环境问题非用例缺陷 |
| PowerShell 5.1 JSON 序列化边界 | 单元素嵌套数组已实测产出 `["a"]`（§1.4），`New-FormValues` 直接可用 |

**失败归因流程（执行棒执行时逐用例走）**：用例 FAIL → 先取三件证据（① 帧全量原文；② 回灌 HTTP 请求/响应原文；③ `-BackendLog` 相关段落）→ 对照 §3.3 判定表：证据落在"披露正常但模型未调用/调用漂移"行 → 记 SKIP 重跑；落在帧/码/状态机/日志与 §6 预期不符 → 产品缺陷，FAIL 成立并附原始证据。

---

## 9. 设计自查（对照验收标准）

| 验收标准 | 自查结论 |
|---|---|
| 每个用例可机械执行（提示词、断言、预期码全部具体） | ✓ §4 每用例给出提示词原文、逐条断言（A1…）、§6 给出帧/码/日志三面证据；GF1–GF5 均无"人工判断"步骤 |
| 不依赖浏览器 | ✓ 全部断言落在 SSE 帧 / HTTP 状态码与响应体 / 后端日志 / pending 快照四个通道；浏览器层现象已显式排除（§7 O1） |
| 既有 22 用例不受影响 | ✓ §5.5 五个扰动面逐条论证；唯一共享代码触碰为加法式兜底（当前不可达分支）；【假设】最终以执行棒全量照跑 PASS=22+GF 全绿实证 |
| 证据等级标注 | ✓ 全文按【实测】/【推断】/【假设】标注；实测项**多数**附文件:行号，另有部分实测项为无行号的**机制断言**（如"既有 22 用例零断言改动""GF 块与既有块结构同构""本棒零产品代码改动"这类结构性/流程性事实），其取证方式已在文中以文字说明，不强凑行号 |
| 脱敏 | ✓ 仅 `<REPO_ROOT>`/`***` 占位；无盘符、无用户名 |
| 禁止项 | ✓ 本棒零 git 写操作、零产品代码改动、零新依赖；本文档为唯一新增文件，无仓库外临时文件遗留（本设计阶段未产生需清理的临时产物） |

---

## 10. 总结论

GF-C E2E 方案设计完成：**GF1–GF5 五个用例计入主计数**（FILTER 回灌续跑 / CLARIFY 回灌续跑 / 取消终态 / 复核拒绝后改值重发 / 409 双向幂等），外加 GF6 可选观察用例（默认关闭）；全部复用 verify-e2e.ps1 的 SSE/回灌/断言机制（新增 `Invoke-FormTurn` 一处蓝本式辅助 + 一处加法式兜底加固），对既有 22 用例零断言改动。**未发现阻碍性问题**；主要风险（真实模型不配合）通过"显式点名提示词 + 三次换会话重试 + 披露端点交叉归因 + SKIP 口径"隔离为可区分的非产品缺陷。GF-B 遗留的 FormRenderer 双实例现象确认为脚本层不可覆盖，已登记观察清单并给出手动冒烟替代方案（§7 O1）。

---

## 裁决结论（指挥官 K3，2026-10-07）

| # | 事项 | 裁决 |
|---|---|---|
| 1 | §3.3 的 SKIP 口径 | **采纳** SKIP（模型未触发不记产品 FAIL，`GFSKIP=n` 摘要单列保留），并**加硬性底线**：GF1–GF4 **全部** SKIP 时整棒判 **FAIL（退出码 1）**——GF 已计入主计数，全 SKIP 等于零有效验证；原"待确认/备选直接 FAIL"标注作废（§3.3 判定表已按此改写） |
| 2 | §5.3 对 `Invoke-AiTurn` 的加法式兜底语义 | **按纠正后的语义实施**：挂起帧 `name=generative_form` 时优先于 `$onSuspend` 返回的**通用**回灌值，直接 POST `{runId, toolCallId, cancelled:true, source:'e2e'}`；仅当处理器显式返回表单专用载荷时才用处理器载荷。理由：`$onSuspend` 为 `$null` 时脚本直接 `break` 不发 POST（:277），危险路径是"传了处理器但返回通用值"（`$script:continueHandler` 恒返回 `'{"ok":true,"source":"e2e"}'`）。受扰用例集合更正为：**直接受扰 TC15/TC16/TC17/TC18**（四处 `Invoke-AiTurn` 调用点），**TC22 仅间接**（不调用 `Invoke-AiTurn`，只 `GetJson` 历史，仅经由 TC15 写入的 `aiSession15` 会话间接受影响）——原记“补全为 TC15/TC16/TC17/TC18/TC22”属过度计数（2026-10-07 红队低-11 纠正） |
| 3 | GF4 的 `reasons` 条数措辞 | **改推演口径**：删除"复刻 GFa §3.3 反例集"的说法；按后端校验规则**推演应为 4 条**（GFa §3.3 实测的是另一组字段，不作为本用例计数依据），断言取 **≥3** 留余量 |
| 4 | 行号 / 路径 / 自查表 / JSON 歧义 | **全部采纳修正**：`SpToolCallingManager.java:306`（原记 310）、`ConfirmGate.java:409`（原记 410，§1.2 的范围表述 `395-410` 已含该行故不动）；`frontend/src/components/AiPanel/AiPanel.vue` 全路径统一（原两处简写 `AiPanel.vue:202-205`）；§9 自查表"实测项均附文件:行号"改为如实口径（部分为无行号的机制断言）；§1.4 补单元素数组限定（裸数组须 `-InputObject`，管道传递会被展开） |
| 5 | 编号 / 插入点 / GF5 零轮次 | **采纳**：GF1–GF5 计入主计数、GF6 可选默认关闭；GF 块插在 **TC16 之后、TC17 之前**（既有 22 块一字不动）；GF5 复用 GF3/GF4 的 `runId/toolCallId` 做纯 HTTP 幂等断言，**零模型轮次** |
| 6 | O1 双 FormRenderer | **维持"脚本层不可覆盖"判定**（渲染层现象，verify-e2e.ps1 无浏览器）；**登记浏览器手动冒烟为收尾动作**（§7 O1 建议 A），红队问题 1/2 的修复棒落地后复跑一次作为回归 |

另：本轮仅按上述六条对本文档做文字修订（含行号/路径/口径修正），**未改产品代码、未引入依赖、未做任何 git 写操作**；文档保持未提交状态，交由提交官统一入库。
