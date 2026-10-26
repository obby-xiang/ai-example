> **历史分析 / 过程证据 — 非现行基线，非验收结论**
>
> - 归档：2026-10-26 · `docs/evidence/history-recovery-2026-10-26/wire-only/`
> - 来源：Kimi Code 会话 `<KIMI_SESSION>` / 子 agent `agent-330` 的 wire 末条 assistant 文本（只读流式解析；非原始会话转写）
> - 原始体量：20337 B / 12314 字符 / 139 行；行指纹覆盖率（仓内 + 落盘语料）5.5%
> - 类别：**正式独立报告**
> - 脱敏：路径 1 / 会话标识 0 / 身份 0 / 密钥 0
> - 正文为该条消息的完整文本，未删段、未改写结论；仅脱敏与行尾空白规整。当时结论不代表当前代码状态。
# 前端工具 `download_export_file` 实读简报

代码基线：`<REPO_ROOT>`（工作树现态，HEAD `d3020ee`）。全部路径以仓库根为基准。

---

## ① 链路图（文字版，带行号锚）

### A. 后端发起 + 挂起

| 步 | 位置 | 事实 |
|---|---|---|
| 1 | `backend/src/main/java/com/example/configmgr/ai/tools/AiTools.java:346-354` | `@Tool(name="download_export_file")` + `@ToolScope("task:EXPORT/EXPORT")` + `@ToolRisk(READ)` + `@ToolChannel(FRONTEND)`，方法体只 `return FRONTEND_STUB`（哨兵桩，后端永不执行）【实测】 |
| 2 | `ai/gate/SpToolCallingManager.java:181-185` | 按 `kind` 分派：`KIND_FRONTEND → awaitFrontend`（另两支是确认门与后端执行）【实测】 |
| 3 | `SpToolCallingManager.java:268-275` | `awaitFrontend`：先过 `FrontendToolGuard` 入参闸（无复核器则直接放行），再 `gate.awaitFrontendResult`【实测】 |
| 4 | `ai/gate/ConfirmGate.java:185-187 / 189-253` | 挂起实现：登记 latch（`:192`）→ 发帧 → `deadline = now + timeoutSeconds*1000`（`:204`）→ 循环里查 Redis 条目是否 `resolved()`（`:216-221`）→ 否则 `latch.await(min(remain, 2s))`（`:226`）→ `remain<=0` 走 `expire()`（`:223-224`）【实测】 |
| 5 | `ai/config/AiProperties.java:116-123` | `app.ai.hitl.timeout` 默认 `Duration.ofSeconds(120)`，确认门与前端工具**共用**这一个键【实测】 |
| 6 | `ai/run/SseChatEmitter.java:599-611` | `frontend_tool_request` 帧：`toolCallId / name / args / timeoutSeconds(=120) / expiresAt / atMs`，并写死回灌回调 `POST /api/ai/frontend-tool-result {runId, toolCallId, result}`【实测】 |

### B. 前端执行（注册 → 执行体 → 浏览器下载）

| 步 | 位置 | 事实 |
|---|---|---|
| 7 | `frontend/src/types/tools.ts:82-107`（`:96-106` 是该工具） | 前端注册表 `FRONTEND_TOOLS`：args = `taskId`(number,必填) / `defCode`(string,必填)，scope `task:EXPORT/EXPORT`，channel FRONTEND，risk READ【实测】 |
| 8 | `frontend/src/utils/frontend-tools.ts:433-437 → 802-844` | 路由 1（登记工具）：`runRegisteredTool` 做参数校验 → scope 校验（`:817`）→ 取 executor（`:825`，`executors` Map，空则 `defaultExecutor`）→ `await executor(...)`（`:835`）【实测】 |
| 9 | `frontend/src/views/ExportWizardView.vue:981-999`（`:1012` 卸载注销） | **执行体注册点**：读 `defCode` → `await ensureTaskBoundForTool(call.args.taskId)`（`:987`，跨任务绑定：`getTask` + `loadTask` + `loadExistingResults`，见 `:842-879`、`:433-451`）→ `exportResults` 里必须有该 defCode（`:991-993`）→ `goStep(2)`（`:994-996`）→ **`await downloadOne(defCode)`**（`:997`）→ 返回字符串 `` `已触发下载任务 #N 的 CODE 导出文件（在线编辑后的内容）` ``（`:998`）【实测】 |
| 10 | `ExportWizardView.vue:710-725` | `downloadOne`：`buildXlsx`（`:717`）→ `downloadBlob`（`:718`）→ `ElMessage.success('已下载 ...')`（`:719`）→ **异常只进 `ElMessage.error`，从不 rethrow**（`:720-721`）【实测】 |
| 11 | `ExportWizardView.vue:698-708` | `buildXlsx`：有表格则 `grid.toXlsxBlob()`（SpreadJS 行数据自产 OOXML，`components/SpreadGrid.vue:325-330`），否则 `tasksApi.downloadFile`（axios GET，`api/http.ts:83-86` 单请求上限 **60s**）【实测】 |
| 12 | `frontend/src/utils/excel.ts:839-848`（与 `api/http.ts:145-154` 同实现） | 浏览器下载 = `URL.createObjectURL` + `document.createElement('a')` + `a.download` + `document.body.appendChild` + **`link.click()`** + `removeChild` + `revokeObjectURL`。**没有** File System Access API / `showSaveFilePicker()`，即"另存为目录框"完全交给浏览器默认行为【实测】 |

### C. 结果回传（HTTP 回灌 + 唤醒）

| 步 | 位置 | 事实 |
|---|---|---|
| 13 | `frontend/src/stores/ai.ts:1282-1300` | `frontend_tool_request` 帧：`suspended = true`（`:1289`）→ 非 `generative_form` 就 `void this.runFrontendTool(...)`（`:1298`，`void` 调用不阻塞帧循环）【实测】 |
| 14 | `stores/ai.ts:1490-1511` | `runFrontendTool`：`await executeFrontendTool({..., budgetMs: deriveFrontendToolBudgetMs(frame.timeoutSeconds)})`（`:1494-1499`）→ 写/toolRun 卡（`:1500-1506`）→ `await submitFrontendToolResult(...)`（`:1510`，**必须等执行器返回才发 POST**，这就是超时预算存在的唯一理由，注释见 `frontend-tools.ts:36-41`）【实测】 |
| 15 | `stores/ai.ts:880-928` + `api/http.ts:110-113` | POST `/api/ai/frontend-tool-result`；409 `DUPLICATE` 视为中性终态（`:902-914`），400 `FORM_RESULT_REJECTED` 表示挂起仍在等【实测】 |
| 16 | `ai/web/AiController.java:236-251`（`:443-471` 是 `ConfirmGate.submitFrontendResult`） | 通过 → `status=FRONTEND_RESULT` + 发 `frontend_tool_result(ok=true)` 帧 + `signal` 唤醒 latch；重复 → HTTP 409；未找到 → 409【实测】 |
| 17 | `SpToolCallingManager.java:194-196` | 唤醒后 `text = resolved.getResultText()`，为**每个** tool_call 生成一条 `ToolResponseMessage.ToolResponse`（文本经 `tool/ToolResultLimiter.java:47-64` 裁剪）作为 `role:tool` 回填模型 → 模型续写【实测】 |
| 18 | `SseChatEmitter.java:613-623` + `stores/ai.ts:1301-1335` | 回灌帧只更新前端工具卡（`ok` 恒由后端按"收到回灌"置位），不改变模型已拿到的文本【实测】 |

**120 秒超时的两条分支**（关键）：

- **分支甲（前端预算先到，设计内）**：`frontend-tools.ts:191` `FRONTEND_TOOL_TIMEOUT_BUDGET_MS = 60_000`，`:200` 余量 `60_000`，`:234-247` 折算 `min(max(服务端上限−60s, 5s), 服务端上限)`；默认 120s → **60s**。到点由 `runWithBudget`（`:349-374`，`:358` 的 `setTimeout`）判超时 → `timeoutOutcome`（`:405-420`）：下载类文案在 `:411-415`「等待 N 秒仍未收到浏览器的完成回执…该操作可能仍在后台继续，文件也许已经下载到浏览器的下载目录…不要直接重复触发下载」。随后照常 POST 回灌，后端受理（这条**不是**用户看到的那句）【实测】
- **分支乙（前端定时器没能先到，用户实际命中）**：`ConfirmGate.java:223-225 → 286-312`，`:294-300` 生成文案
  **`前端工具 download_export_file 未在 120 秒内回传执行结果（前端超时），本次调用未获得数据。`**（`:300`）
  → `:309` 发 `frontend_tool_result(ok=false)` 帧 → 该轮以 `TIMEOUT` 收尾；前端随后（若还能发）回的 POST 被 `AiController.java:248-251` 按 409 幂等拒，前端提示"该工具结果已回灌过（幂等拒绝）"（`stores/ai.ts:912`）【实测：文案逐字一致；分支归属为实测+推断，见③】

---

## ② 120 秒等待的必要性结论：AI 到底需不需要这个结果

**结论：AI 不需要"浏览器下载完成"这个信号，但它需要"执行体内的确定性前置校验"这一层结论。现状把两者绑在一个 `await` 里，所以付了全额等待成本。**【推断，依据为代码】

三层拆开看（都在 `ExportWizardView.vue:981-999`）：

1. **确定性失败（AI 真正需要的）**：① 参数缺 `defCode`（`:983-985`）；② 跨任务绑定失败/任务不可加载（`:987-990`，`ensureTaskBoundForTool`）；③ 目标任务里没有该 defCode 的导出结果（`:991-993`）。这三条产出的都是"可行动的错误文本"，模型据此能改走 `list_tasks` / 提示用户——**这些判断全部发生在 `downloadBlob` 之前**，耗时是网络往返级【实测】
2. **浏览器下载动作本身（`a.click()`）**：`excel.ts:839-848` 是同步 API，`click()` 不会返回"文件已落盘"；成功与否由浏览器下载管理器决定【实测：代码里没有任何落盘确认】
3. **成功回执文本**：`:998` 返回的是 `已触发下载任务 #N 的 CODE 导出文件` —— **纯 ack 字符串**。结果里**没有**文件内容、没有大小、没有落盘路径【实测】

再往前一层：后端拿到该文本后只干两件事——写进 `PendingToolCall.resultText`（`ConfirmGate.java:462`）与 SSE 帧、作为 `role:tool` 文本回填模型（`SpToolCallingManager.java:194-196`）。**没有任何后端业务逻辑消费它**（不更新任务状态、不写文件、不记台账；台账仅在确认门放行后的 `executeOnce` 写）【实测：全仓搜索该工具的消费点仅此两处】。

也就是说：**这条工具的真实语义已经是"发起 + 前置校验"，120 秒等的是一个它根本没提供的信息**（落盘确认）。用户质疑成立【推断，高置信】。

但要如实说明两点反面：

- 等待**不是完全无价值**的：分支甲/乙的差异在于，等待期内执行器若抛出（引擎未就绪、文件读取失败），回灌文本会带原文（`runRegisteredTool:837-843` 的 `执行失败：<原因>`）而不是"结局未知"。虽然 `downloadOne` 吞异常（`:720-721`）使这条路径实际上只覆盖"绑定/结果不存在"两类；`frontend-tools.ts:401-403` 自记这是"下载失败却回灌成功"的 P1 面【实测】
- 该等待同时还承担**协议硬约束**：官方 `ToolCallingManager` 循环要求每个 `tool_call` 必须有且仅有一条 `ToolResponseMessage`（`SpToolCallingManager.java:194-202`），所以"发了就不管"在前端这一层不是可选项——**必须**给一个文本，只是这个文本可以是即时的"已发起"，不必是"已完成"【实测 + 推断】

---

## ③ 超时后用户侧实际体验的事实还原

用户引用的是 `ConfirmGate.java:300` 的后端文案，即**分支乙**。能推出的事实链【推断，除标注外】：

1. **前端自己的 60s 预算没有先到**。`runWithBudget:349-374` 只用宏任务 `setTimeout` 计时，`:329-338` 的注释明说"计时器是宏任务，**无法打断同步阻塞主线程的执行器**"。要走到后端 120s 判定，只能是执行体所在的主线程被长时间阻塞（或被宿主浏览器/自动化层挂住），60s 定时器与随后的 POST 都推迟到 120s 之后【推断，与仓库既有实测吻合】
2. **仓库内已有的实测证据（同型现象，issue#6 原始三轮）**：`docs/M2-排期计划.md:285`（R-93）记录三轮 = **120.004s 超时 / 120.007s 超时 / 30.6s 成功**；对照组（自动下载、无弹框的 Chromium）同环境 `click` → 回灌 POST 仅 **25~41ms**；**某轮文件已在请求后约 114s 落盘，而回灌 POST 仍未发出**，后端 120s 准点判超时【实测，仓库记录】
3. **所以用户侧的真实结局是三者错位**：
   - 浏览器侧：下载**很可能已经发生**（`downloadBlob` 的 `click()` 在阻塞前就已执行，文件在下载目录里）【推断】
   - AI 侧：收到 `本次调用未获得数据`，于是**在对话里告诉用户"没拿到数据"**——语义上错了一个量级：不是"没拿到数据"，而是"没拿到回执"【实测：文案即此】
   - 工具卡侧：`frontend_tool_result` 帧 `ok=false` + 本地无终态 → 卡片落 `failed`（`stores/ai.ts:1307-1318`）；若本地已由 60s 预算先行落 `failed`，则本地终态不被帧改写（同处 `localTerminal` 守卫）【实测】
4. **修复（21b05f0 / bf8aa6d）没堵住这个场景**：`bf8aa6d` 只是把预算公式严格化为"上限−60s"、给 `setTimeout` 加溢出钳制（`frontend-tools.ts:200-247`、`:278-294`）——它能把**异步挂起类**从 120s 提前到 60s 收口，但对**同步阻塞主线程**这一类无效（`frontend-tools.ts:329-338` 自记该边界）。用户现在看到的 120s 后端文案，正是这条边界仍在的实证【推断 + 实测文案】

一句话还原：**文件多半已经躺在下载目录里，AI 却对用户说"没拿到数据"，用户的疑问与不信任都由这次错位产生。**

---

## ④ fire-and-forget 改动面清单与风险点（只评估，不施工）

先给一条**必须知道的前置事实**：这件事**已经被评审并明确否过一次**。`docs/M2-排期计划.md:285`（R-93）写：「**不采纳** void 化落点（会引入更早谎报与竞态）」。所以本清单的定位是"若要重开，需要先解决当时否决的两个理由"。

### 改动面清单

| # | 面 | 位置 | 要改什么 |
|---|---|---|---|
| 1 | 执行体拆分 | `views/ExportWizardView.vue:981-999` | 把"前置校验"（保留 `await`，产出确定性错误文本）与"下载动作"分离：`await downloadOne(...)` 改为"发起后立即返回 ack"（`downloadOne` 自身需拆出 `startDownload()`，因为 `downloadOne:710-725` 把构建与点击揉在一起）。返回文案改成如实口径："已发起下载（未等待浏览器完成，落盘请到下载目录确认）" |
| 2 | 超时预算分层 | `utils/frontend-tools.ts:191/200/234-247/314-321` | 可选择给下载类一个**更短**的预算（如 5s，只覆盖前置校验），或给描述符加"即发即回"标记让路由 1 跳过长等待。注意 `deriveFrontendToolBudgetMs` 目前是全局折算，单工具改写要引入新字段（`ToolArgSpec` 同级） |
| 3 | 错误可见性（最大缺口） | `ExportWizardView.vue:720-721` + `utils/frontend-tools.ts:401-403` | `downloadOne` 吞异常。一旦不等结果，**a.click 之后的失败将没有任何通道回到 AI**：`ConfirmGate.submitFrontendResult:448-450` 对已决条目一律 409，**系统当前不存在"迟到结局二次回灌"的合法通道**。要么补一条（新端点/新帧/notice），要么接受"AI 只知已发起" |
| 4 | 工具卡状态机 | `types/ai.ts:155-177`（`ToolRunStatus`）、`stores/ai.ts:1500-1506`、`:1301-1335`、`:1444-1464` | 现在的四态映射是 `execution.ok ? 'succeeded' : 'failed'`（`:1504`）。ack 语义下 `succeeded` 会谎报"下载成功"，需要新增 `dispatched/in-flight` 一类状态（并同步 `applyFrontendToolTerminal` 的"非 generative_form 即收掉挂起投影"分支与 `localTerminal` 守卫） |
| 5 | 挂起等待本身 | `ConfirmGate.java:185-187/189-253`、`AiController.java:236-268` | **不需要改协议**：ack 也是一条普通 `result` 文本，照样走 `FRONTEND_RESULT`。收益是挂起时长从 60~120s 降到毫秒级，释放 `SpToolCallingManager` 挂起线程（`:189-253` 每轮占一条线程至多 120s，`docs/M2-排期计划.md:233` R-41 记 20 路即打满）。**CAS/轮询不会空转**：等待是 `latch` 阻塞 + 2s 心跳（`:226-236`），窗口极短【实测】 |
| 6 | 模型侧防重复触发 | `AiTools.java:346`（工具 description） | 现描述是"触发浏览器下载指定配置的导出文件"，未说明"返回即已发起、勿重复调用"。ack 语义下模型很可能再调一次 → 重复下载。需要改 description + 回灌文案双向压制；`ToolRegistryDisclosureTest` 若断言描述需同步 |
| 7 | 重入/幂等 | `ExportWizardView.vue:981-999` | 现在没有"同 defCode 去重"；fire-and-forget 后同一轮/连轮重复触发的代价从"晚知道"变成"多下一个文件" |
| 8 | UI 挂起提示 | `stores/ai.ts:339-341`（`canSend = !loading`）、`AiPanel.vue:389-391` | 现状：挂起期整轮 `loading=true`，输入框禁用、占位文案"挂起等待中…"。fire-and-forget 后等待消失，这层提示自然不再出现（与 `suspended` 的 CONFIRM 用法共用，不改也无害） |

### 风险点（对应当初的否决理由）

1. **更早的谎报**：ack 说"已发起"，用户/模型都会读成"已成功"。而 `downloadOne` 吞异常 + `a.click()` 无法感知落盘，**当前的"成功"文本其实已经只是 ack**（`:998`），所以风险增量集中在"语义被误读为完成"
2. **竞态**：ack 返回后下载仍在后台，若紧接着 `navigate_to` 切页/卸载，`onBeforeUnmount:1008-1016` 会注销执行器并重建页面状态；`grid.toXlsxBlob()` 依赖页面存活（`buildXlsx:698-708` 走 SpreadJS 实例）。即"发起"与"完成"之间页面可能已变【推断】
3. **无迟到结局通道**（结构性）：409 幂等把"迟到"定型为"丢弃"，因此 fire-and-forget 之后 AI/用户在**任何**后续轮次都无法得知那次下载的真实结果——除非新增通道，这会把改动面从"前端 1 处"扩到"前端 + 后端 + 协议帧"

### 最小可行替代（若不想动协议）

保持现状等待机制不变，只做两件低成本的事：① 把 `:998` 的成功文案与 `timeoutOutcome:411-415` 的文案统一为**同为"已发起待确认"**口径（不再出现"已触发下载"这种读起来像成功的句子）；② 把 `downloadOne` 的异常改成 rethrow（`:720-721`），让"确定性失败"真正回灌。这不需要协议改动，也能消掉"AI 说没拿到数据而文件已落盘"和"下载失败却回灌成功"两个谎报面。

---

## ⑤ 同类工具清单

按"是否需要等结果"分档（依据 `frontend-tools.ts:5-16` 的后端 7 个 FRONTEND 工具 + 前端四条路由可达面）【实测】：

| 档 | 工具 / 动作 | 位置 | 判定 |
|---|---|---|---|
| **真·不需要等结果**（下载类） | `download_export_file` | `types/tools.ts:96-106`；执行器 `ExportWizardView.vue:981-999` | 副作用在浏览器且不可回读，回灌文本已是 ack |
| 同上 | `download`（ui_event 同名动作，路由 3） | `stores/workspace.ts:424-431`（`await fetch` + `saveBlob`，**无 signal、无 timeout**，即 `docs/M2-排期计划.md:203` R-11） | 同性质；且它自己也可能挂住 → 回灌不发 |
| 同上 | `download_export_files`（蓝本旧名） | `frontend-tools.ts:383-389` | **未登记于任何路由**，送进来直接落"未知前端工具"unavailable，仅保留在下载类文案名单里 |
| **半候选**（需要"打开成功"的结论） | `open_export_file_editor` | `frontend-tools.ts:976-988` 默认执行器；页面实现 `ExportWizardView.vue:949-978` | 必须等 SpreadJS 引擎就绪（`SpreadGrid.vue:261-266`，看门狗 60s）才能如实回灌"表格未就绪"（`bf8aa6d` 专门为此修，见 `ExportWizardView.vue:967-976`）。它**不能** fire-and-forget，否则回到"谎报已打开" |
| **不能即发即回**（后续动作依赖状态） | `navigate_to` / `select_definitions` / `set_condition` / `confirm_step` | 动作表 `frontend-tools.ts:597-656`；完成判据 `:735-767`（等页面就绪 `:523-538`、等任务绑定 `:560-580`，上限 `PAGE_READY_TIMEOUT_MS=3000`） | 等的是**状态落地**而非用户体验；F3/issue#1 实测过"不等就打在未挂载/未绑定的窗口上"。它们的等待是 3s 级，不是 120s 级，不构成本次问题 |
| **必须等（等的是人）** | `generative_form` | `stores/ai.ts:1291-1296`、`:1287`；超时折算见 `:974-986` | 等用户填表，不受执行器预算影响（`frontend-tools.ts:310-313` 明确排除） |
| **不需要前端** | `start_export` / `start_precheck` / `start_import` / `start_publish` | `AiTools.java`（BACKEND + DANGER，确认门） | 在 main 里都是后端执行，等的是用户确认，不是前端 |

另需留意：`ImportWizardView.vue:509` 也有 `downloadBlob`（模板下载），但**没有**登记为前端工具/页面能力（页面 handler 只有 `import.nextStep/selectDefs/upload/startPrecheck/startImport/startPublish`，`ImportWizardView.vue:935-976`），AI 目前无法触发它【实测】。

---

### 可与用户直接对话的结论句

- 你的质疑成立：`download_export_file` 的返回文本本来就只是一个"已触发"通知（`ExportWizardView.vue:998`），AI 拿不到文件、大小或落盘确认；后端也不消费它【实测】
- 但"发了就不管"在本项目已经**被明确否决过**（`docs/M2-排期计划.md:285` R-93，理由：更早的谎报 + 竞态），且当前系统**没有**"迟到结局回灌"的通道（409 幂等丢弃），所以真要改就得连协议/状态机一起改【实测】
- 你看到「未获得数据」时，文件**很可能已经在下载目录里**——这是 120s 后端超时文案与浏览器下载实际结局错位造成的；修复（`21b05f0`+`bf8aa6d`）只覆盖"异步挂起"，覆盖不了"主线程被同步阻塞"这一类【推断，有 `frontend-tools.ts:329-338` 与 R-93 记录支撑】
