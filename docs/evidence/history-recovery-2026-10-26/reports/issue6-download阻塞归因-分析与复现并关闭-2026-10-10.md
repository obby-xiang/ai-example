<!-- 历史取证 · 非现行基线 · 归档批次 history-recovery-2026-10-26 -->
# issue #6 · `download_export_file` 「await 下载阻塞回灌超时」定性与关闭

> **归档来源**：`<LOCAL_WORK>/issue6-reports/`（本件收 3 件）｜**归档日期**：2026-10-26｜**源件日期**：2026-10-10（正文自述）
> **性质**：历史取证 / **非现行基线**。正文为源件当时时点的结论（日期见各分件），逐字保留，仅做脱敏替换与行尾空白清理，**不删段、不改结论、不补写**；旧行号锚点对应当年快照，**不代表当前已验证或已修复**。
> **归并规则**：同一批次的「侦察 / 施工 / 复核 / 红队 / 会审 / 提交」按**一个入库单元**处理，件内以 `**〔分件 N／M〕**` 分隔，各分件保留源件完整标题与正文。
> 占位符口径：`<REPO_ROOT>` = 主仓检出根（源件中亦有 `<MAIN_REPO>` 写法，本件已归一为 `<REPO_ROOT>`） · `<LOCAL_WORK>` = 仓外工作区根（源件的 `<SCRATCH>` / `<TEMP_ROOT>` 同义） · `<REPORTS_DIR>` = 本单元对应的仓外报告目录 · `<USER_HOME>` = 用户主目录 · `<KIMI_SESSIONS_ROOT>` = 会话根 · `<KIMI_HOME>` = `.kimi-code` 目录 · `<REPO-URL>` = 远端仓库地址 · `<USER>` = 用户名 / 账号名 · `<HOST>` = 主机名 · `<LAN_IP>` = 内网地址 · `<DRIVE>` = 其它盘符 · `<KIMI_SESSION_A…>` = 会话标识（本件内按首见序） · `<USER_EMAIL>` = 邮箱 · `<USER_FRAGMENT>` = 用户名主干片段 · `<OLD_REPO_DIR>` / `<OLD_REPO_DIR_CONCAT>` / `<OLD_REPO_DIR_UPPER>` = 旧主仓目录名 / 同名分支名（连字符分隔 / 零分隔连写 / 全大写）；非连字符的其它分隔形态记作 `<OLD_REPO_DIR⟨分隔符⟩>`（`␠` = 空格），使「不同分隔符变体」仍可分辨但字面不落库。按命名纪律，该名的**一切字面写法**（含全角、Unicode 连字符作分隔、零宽字符插入）在库内一律改写为上述占位符；需要分辨零宽构造的场合改写为码位标注（如 `<U+200B>`），原字面仅存仓外原件。
> **本单元不含**：同目录 `_wb/` 5 件（issue 正文与评论抄录、评论草稿）与 `evidence-issue6-*.json/png` 属网页原件 / 草稿 / 截图，只登记不入（见 README §5）。

## 分件表

| # | 分件（源件） | 源行 | 源字节 | 角色 | 结论要点 |
|---:|---|---:|---:|---|---|
| 1 | `<LOCAL_WORK>/issue6-reports/issue6-分析报告.md` | 523 | 59,149 | 分析（定性） | D1–D5 判定 issue「不成立（不可复现 / 归因错误）」，附三个真实次要问题与多方案取舍；文末追加 Tier-1 隔离复现 + Tier-2 真实链路验证 |
| 2 | `<LOCAL_WORK>/issue6-reports/issue6-复现-kimibrowser.md` | 152 | 19,515 | 复现（实机） | Kimi Code 自带浏览器实机复现：可重放步骤、后端钟计时数据表、下载行为观测、与前次用户 Chrome 实测差异解释 |
| 3 | `<LOCAL_WORK>/issue6-reports/关闭报告.md` | 84 | 10,712 | 收尾（关闭） | 四步（存档 → 评论 → 关闭 → 删除）执行结果 + 删除后三重口径核验 + 偏差记录 |

---

**〔分件 1／3〕** `<LOCAL_WORK>/issue6-reports/issue6-分析报告.md` · 源 523 行 / 59,149 B · 角色：分析（定性）

# issue#6 分析报告：`download_export_file` 前端工具执行器「await 下载阻塞回灌超时」

- 分析对象：GitHub `<USER>-<USER_FRAGMENT>/ai-example` issue **#6**（公开仓库；该 slug 为公开标识，非本机用户名）
- 分析仓库：`<REPO_ROOT>`（= 本机 `<REPO_ROOT>`；后端 `backend/`，前端 `frontend/`）
- 分析方式：**只读静态分析**。零 git 写操作、零文件修改、未跑构建、未跑浏览器实验；GitHub API 仅调用 1 次（`GET /issues/6`，返回 `comments=0` 故未调评论端点，无限流）
- 分析时点：2026-10-10；`<REPO_ROOT>` 工作区含 10 个未提交改动（T4-3 专项在制品），本报告只读、已区分「HEAD 既有」与「工作区在制品」

---

## ① issue 原文要点

**元数据**（逐字取自 API 返回）

| 字段 | 值 |
|---|---|
| number / title | `6` / **`download_export_file 前端工具执行器 await 下载阻塞回灌超时`** |
| state | `open`（`closed_at: null`） |
| labels | `[]`（无标签） |
| user.login | `<USER>-<USER_FRAGMENT>`（`author_association: OWNER`） |
| created_at | `2026-10-10T02:08:20Z`（updated_at 同刻） |
| comments | `0`（无评论，无 pinned_comment，reactions 全 0） |
| milestone / assignees | 均空 |

**正文核心段逐字摘录**（`## Bug` 起）：

> ### 复现步骤
> 1. AI 在导出向导步骤 3 调用 `download_export_file(taskId, defCode)` 工具
> 2. 前端工具执行器 `await downloadOne(defCode)` 内部调用 `downloadBlob` 触发浏览器下载
> 3. 浏览器弹出下载对话框（或开始下载），阻塞浏览器进程
>
> ### 预期
> 前端工具执行器立即回灌结果给后端，下载在后台异步执行，AI 收到"已触发下载"的成功回灌。
>
> ### 实际
> `await downloadOne(defCode)` 等待下载操作完成后才 return，浏览器下载对话框阻塞了 JS 主线程，导致回灌 POST 请求（`/api/ai/frontend-tool-result`）无法在 120 秒内发出，后端超时报错："前端工具 download_export_file 未在 120 秒内回传执行结果"。AI 回复用户"下载未能确认成功"。
>
> ### 根因
> `ExportWizardView.vue:951-968` 的 `download_export_file` 前端工具执行器：
> ```javascript
> registerFrontendToolExecutor('download_export_file', async (call) => {
>     // ... 确保任务绑定、检查导出结果 ...
>     await downloadOne(defCode)    // ← 阻塞点：await 等待下载完成
>     return `已触发下载任务 #${taskId.value} 的 ${defCode} 导出文件`
> })
> ```
> `downloadOne` 内部（line 689-704）调用 `downloadBlob`（excel.ts:839-848），`downloadBlob` 通过 `link.click()` 触发浏览器原生下载。浏览器下载对话框是 Windows 桌面级弹窗，会阻塞浏览器进程，导致 `downloadOne` 的后续代码和执行器 `return` 无法执行，回灌 POST 无法发出。
> 前端工具执行器框架（`frontend-tools.ts:548`）是 `const result = await executor(call)`——执行器必须 return 才能触发回灌。执行器被阻塞 = 回灌被阻塞。
>
> ### 建议修复
> 将 `await downloadOne(defCode)` 改为异步触发，不等待下载完成即 return 回灌：
> ```javascript
> void downloadOne(defCode).catch(err => console.error('download failed:', err))
> return `已触发下载任务 #${taskId.value} 的 ${defCode} 导出文件`
> ```
> 这样执行器立即 return 回灌结果，下载在后台异步执行，不阻塞回灌链路。
>
> ### 影响范围
> 全项目中唯一一处。其他调用 `downloadBlob` 的位置（用户手动点击下载、模板下载、打包下载）都不在 AI 工具回灌链路中，无阻塞风险。`ElMessageBox.confirm/alert`、轮询等待、SSE 流读取等也不在 AI 工具回灌链路中或不会触发浏览器弹窗。
>
> ### 相关文件
> - `frontend/src/views/ExportWizardView.vue:951-968` — download_export_file 前端工具执行器
> - `frontend/src/views/ExportWizardView.vue:689-704` — downloadOne 函数
> - `frontend/src/utils/excel.ts:839-848` — downloadBlob 函数
> - `frontend/src/utils/frontend-tools.ts:548` — `await executor(call)` 回灌框架
>
> ### 严重度
> 中 — 下载本身可能已触发（取决于浏览器下载设置），但 AI 无法确认成功，会告知用户"下载未能确认成功"，造成困惑。用户手动点击下载时无此问题（不走 AI 回灌链路）。

**涉及功能面**：AI 前端工具通道（`@ToolChannel(FRONTEND)` 的 `download_export_file`）→ 前端工具执行器 → 浏览器下载副作用 → `POST /api/ai/frontend-tool-result` 回灌 → 后端挂起等待（`ConfirmGate#awaitFrontendResult`，`app.ai.hitl.timeout=120s`）。

---

## ② 定性：**不成立（不可复现 / 归因错误）**

一句话：被指控的"`await downloadOne` 等下载完成 ⇒ 阻塞 JS 主线程 ⇒ 120s 内发不出回灌"这条因果链，在代码、浏览器机制、以及本仓既有实跑证据三方面都不成立；issue 自身也承认该场景需要"取决于浏览器设置"这一前提。

判定为「**不可复现 + 归因错误**」而非"真缺陷"，依据如下（每条给证据级别）：

### D1【实测-代码】`await downloadOne` 并不是"等下载完成"

- `downloadOne`（`<REPO_ROOT>/frontend/src/views/ExportWizardView.vue:689-704`）内部只有两处耗时来源：
  1. `await buildXlsx(code)`（:696）——已加载表格时走 `grid.toXlsxBlob()`（`SpreadGrid.vue:258-263` → `buildRowsXlsxBlob`，纯前端 JSZip 组包）；未加载表格时走 `tasksApi.downloadFile`（`api/tasks.ts:73-75`，axios `timeout: 60000`，`api/http.ts:83-86`）。
  2. `downloadBlob(blob, fileName)`（:697）——`<REPO_ROOT>/frontend/src/utils/excel.ts:839-848`：
     ```js
     const url = URL.createObjectURL(blob)
     const link = document.createElement('a')
     link.href = url; link.download = fileName
     document.body.appendChild(link); link.click(); document.body.removeChild(link)
     setTimeout(() => URL.revokeObjectURL(url), 3000)
     ```
     `link.click()` 是**同步派发事件后立即返回**的 DOM 调用（HTML 规范的 `download` 链接是"导航到下载"，不阻塞脚本）；下载本体由浏览器进程异步接管，脚本侧**没有任何"等下载完成"的原语**（没有 awaited Promise、没有事件等待）。故 issue 所述"等待下载操作完成后才 return"与代码语义不符。

### D2【推断-高置信】"下载/另存对话框阻塞 JS 主线程"不成立

- 会**挂起渲染进程 JS 线程**的模态只有渲染进程内的阻塞式对话框：`alert()` / `confirm()` / `prompt()` / `print()`。
- 下载（含"另存为"）对话框不属此类：它由**浏览器进程**创建并模态于窗口（对用户表现为窗口输入被禁用），渲染进程照常执行 JS、定时器与网络请求（渲染进程与浏览器进程、网络服务分属不同进程；Chromium 的多进程架构即为此）。旁证：Chromium 官方 issue 40665570 把"弹出保存对话框很慢/卡住"归因于**浏览器进程**被安全软件拖慢（Bitdefender 案例），即该对话框的宿主是浏览器进程，而非标签页的渲染进程。
- 该弹框本身还需额外环境前提：Chrome 默认**不弹**"另存为"，仅当用户开启「下载前询问每个文件的保存位置」才弹（默认路径是直接落到下载目录）。issue 正文自己写的"（或开始下载）"即已覆盖默认情形——默认情形下连"弹框"这个前提都不存在。

### D3【实测-代码】执行器慢也不会卡住回灌链路

- 框架侧是 fire-and-forget：`stores/ai.ts:1298` `void this.runFrontendTool(message, frame)`（注释：前端工具没有 HITL，立即在浏览器执行并回灌），`runFrontendTool` 内部 `await executeFrontendTool(...)` → `await this.submitFrontendToolResult(...)`（`stores/ai.ts:1485-1500`）。
- 即：执行器与回灌是**同一条独立异步链**，与 SSE 帧处理、心跳、倒计时互不阻塞（帧处理不等执行器）。因此"回灌 POST 发不出"只能来自页面 JS 线程整体停摆，而 D2 已排除下载对话框能造成这一点。

### D4【实测/存档-历史实跑】本仓真实浏览器实跑中该轮是**成功**轮，无超时

证据文档 `<REPO_ROOT>/docs/evidence/S44c-AI面板与出口验证.md`（浏览器操作经 kimi-webbridge 驱动**用户的真实 Chrome**，模型为真实 `deepseek-flash`，见该文档 :8）：

- :125 帧序列表「下载导出文件」轮：`start → suspended → tool_start → tool_result → suspended → tool_start → frontend_tool_request → frontend_tool_result → delta → done`。（若真的白等到 120s 超时，形态应是"该轮耗时 ≥120s + 超时回填"，且该文档对失败/边界一向逐条登记。）
- :141 工具卡四态表把「已触发下载…」列入**「已成功」**（截图 `11-ai-frontend-download.png`）；:171-172 专节记录 `download_export_file` 结果为「已触发下载 CURRENCY 的导出文件（在线编辑后的内容）」（**触发下载**）。
- 同族原始帧级证据（副作用同在浏览器、同属"执行器 return 才回灌"的前端工具）：`open_export_file_editor` 轮的 SSE 原文中，`frontend_tool_request` 的 `atMs=…041143` → `frontend_tool_result` 的 `atMs=…041165`，**相差 22ms**（附件 `S44c-附件-ai-chat-11-…response.sse`，位于仓库外证据目录 `s44c-evidence`，未入库）。即"浏览器执行 → POST 回灌 → 后端受理并回帧"整链在真实浏览器里是**毫秒级**，不存在 120s 量级的阻塞。

  > 该帧型无法单独排他（`ConfirmGate#expire` 的超时结局也发同型 `frontend_tool_result`，`ConfirmGate.java:286-312`），故 D4 的强度来自"文档把该轮登记为成功 + 该轮 `.sse` 原文已按缓冲轮换释放 + 全文无任何 download 超时记录"的组合，而非单帧。

### D5【实测-全域检索】issue 的"实际"段没有任何"实测发生"的痕迹

在 `<REPO_ROOT>` 全域（含 `docs/evidence/**`、脚本、代码注释）检索 `未在 \d+ 秒内回传`、`未能确认成功`、以及 `download` 相关的 `FRONTEND_TIMEOUT` 记录：**0 处**。issue 引用的后端报错文案与 `ConfirmGate.java:300`（`"前端工具 " + name + " 未在 " + timeoutSeconds + " 秒内回传执行结果（前端超时），本次调用未获得数据。"`）逐字同形，且 `120` 与 `app.ai.hitl.timeout: 120s`（`application.yml:101-103`）一致——即"实际"段与超时文案对齐，但仓库中找不到对应运行记录，更像**由代码推演而非实测复现**。

### 结论与残余不确定性（诚实边界）

- 定性与归因：**不成立**。真缺陷（"执行器被下载阻塞 ⇒ 回灌超时"）在代码与浏览器机制上均不成立；issue 的"实际"段无可复现痕迹；S44c 真实浏览器实跑该轮为成功轮。
- 唯一未被现有证据覆盖的组合：用户**开启了**「下载前询问保存位置」且本机浏览器进程因安全软件等环境因素被拖慢/挂住（chromium#40665570 型）。该情形属**环境级卡顿**，不是本代码的缺陷；且届时"回灌是否超时"只取决于页面 JS 是否停摆，与是否 `await downloadOne` 无关（`downloadBlob` 之后 `downloadOne` 只剩 `ElMessage.success` 与 `finally`）。
- 因此，把 issue 当作"改进建议"看，它命中的**不是**它自己说的根因；真正可行动的两条次要问题见 ③ 的 P1/P2（其中 P1 恰与 issue 的"AI 无法确认成功"相反：当前实现是**无条件宣称成功**）。

---

## ③ 代码定位与根因分析

### 3.1 定位表（HEAD 即工作区；这三个文件均**不在** 10 个在制品改动之列）

| 锚点（类#方法） | 文件:行 | 事实 |
|---|---|---|
| `ExportWizardView#registerHandlers` 内 `registerFrontendToolExecutor('download_export_file')` | `<REPO_ROOT>/frontend/src/views/ExportWizardView.vue:951-968`（`:967` = `await downloadOne(defCode)`，`:968` = 无条件成功文案） | 与 issue 引用的 951-968 完全对上 |
| `ExportWizardView#downloadOne` | 同文件 `:689-704`（`:696` `await buildXlsx`、`:697` `downloadBlob`、`:699-703` 异常 → `ElMessage.error` 后 **return void**） | 与 issue 引用一致 |
| `ExportWizardView#buildXlsx` | 同文件 `:677-687`（grid 分支 `toXlsxBlob()`；回落分支 `tasksApi.downloadFile`） | issue 未提及，但它是本执行器唯一真 await 之一 |
| `ExportWizardView#ensureTaskBoundForTool` | 同文件 `:821-…`（`tasksApi.getTask`、在途绑定等待、结果加载） | 本执行器内另一组真 await |
| `excel#downloadBlob` | `<REPO_ROOT>/frontend/src/utils/excel.ts:839-848` | 与 issue 引用一致；`link.click()` 同步返回 |
| `frontend-tools#runRegisteredTool` | `<REPO_ROOT>/frontend/src/utils/frontend-tools.ts:550`（`const result = await executor({...})`） | issue 写 `:548`，工作区实为 **550**（±2 行漂移，属版本/近似误差，不影响论点） |
| `aiStore#handleFrame` / `#runFrontendTool` / `#submitFrontendToolResult` | `<REPO_ROOT>/frontend/src/stores/ai.ts:1298`（`void this.runFrontendTool(...)`）、`:1481-1501`、`:880-928` | 回灌链 fire-and-forget，独立于帧处理 |
| `ConfirmGate#awaitFrontendResult` / `#await` / `#expire` | `<REPO_ROOT>/backend/src/main/java/com/example/configmgr/ai/gate/ConfirmGate.java:185-187` / `:189-253` / `:286-312`（文案 `:300`） | 120s 悬起 → 超时回填；结局帧与人工决策同型 |
| `app.ai.hitl.timeout` | `<REPO_ROOT>/backend/src/main/resources/application.yml:101-103` = `120s` | 与报错文案中的 120 对齐 |
| `SseChatEmitter#frontendToolRequest` | `<REPO_ROOT>/backend/.../ai/run/SseChatEmitter.java:548-557` | 帧带 `timeoutSeconds` + `expiresAt`（与 `atMs` 同钟） |

### 3.2 根因

**无根因（缺陷不成立）**：`await downloadOne(defCode)` 解析的时刻 = 组包完成 + 同步 `link.click()` 返回 + `ElMessage` 调用，均为毫秒级；其后 `return` 与 `submitFrontendToolResult` 的 `fetch` 同链执行。不存在能把该链停满 120s 的机制。

### 3.3 顺带发现的三个**真实**次要问题（供判定"当建议收不收"）

**P1【实测，建议量级 S3，且与 issue 自述相反】回灌文案与真实结局解耦 ⇒ 可能"谎报成功"**

- `downloadOne` 把自身的失败吞进 `ElMessage.error` 后 **void 返回**（`ExportWizardView.vue:699-703`），执行器因此**无条件**返回「已触发下载任务 #N 的 X 导出文件（在线编辑后的内容）」（`:968`）。
- 即：当 `buildXlsx` 抛错（表格未就绪/未绑定任务/HTTP 失败）或下载被浏览器拦截时，回灌给模型的仍是"成功"。issue 担心的"AI 无法确认成功"在实践中被**反向**实现为"AI 必然被告知成功"。
- issue 给出的建议改法 `void downloadOne(defCode).catch(err => console.error(...))` 会把这一面**加深**：连"是否真的尝试过"都不再等，`ElMessage` 也退化为控制台日志；同时 `downloading.value`（`:694`/`:702`）在并发/连续调用下会出现"先发起的失败清理掉后发起的 loading 态"的竞态。

**P2【实测】"全项目中唯一一处"不准确**

- 另一条 **AI 可达**、且同样走"回灌链路 + `link.click()`"的下载路径：`ui_event type='download'`（`<REPO_ROOT>/frontend/src/stores/workspace.ts:424-431`：`await fetch(url)` → `saveBlob(await response.blob(), ...)`）→ `saveBlob`（`<REPO_ROOT>/frontend/src/api/http.ts:145-154`）。它经 `frontend-tools.ts` 路由 2/3（`runWorkspaceAction` / `runUiEventAction`）由 AI 驱动，执行结果同样是回灌文本。
- 附带差异：`saveBlob` 在 `click()` 之后**立即** `URL.revokeObjectURL(url)`，而 `downloadBlob` 延迟 3s 回收——两处实现不一致（对部分浏览器是已知风险点，与本 issue 论点无关，属独立小项）。

**P3【实测】该执行器内唯一能吃满 120s 预算的路径是"串行 HTTP 超时叠加"（与下载无关）**

- `axios timeout = 60000`（`api/http.ts:83-86`）逐请求生效、**无整链预算**：`ensureTaskBoundForTool`（`getTask` ≤60s + 绑定/导出结果加载 ≤60s）→ `buildXlsx` 回落分支 `downloadFile` ≤60s，最坏 ≈180s **>** `hitl.timeout=120s`。
- 因此"前端工具 … 未在 120 秒内回传执行结果"这一**症状**是可复现的类别，但触发条件是后端/网络挂起（跨任务调用时更易命中：`ensureTaskBoundForTool` 先做跨任务绑定与预加载），不是下载对话框。该口径在前批已被登记过一次（"跨任务比同任务多 3 个串行请求且无额外超时兜底（axios 单请求上限 60s）"，见 `docs/M2-排期计划.md` 的 issue#2 遗留一揽②）。

### 3.4 与 `atMs` / 挂起倒计时（T4-3）的关系：**正交，无因果**

- `frontend_tool_request` 帧带 `timeoutSeconds` / `expiresAt` / `atMs`（`SseChatEmitter.java:548-557`），`expiresAt − atMs ≡ timeoutSeconds×1000`；这是**后端权威**倒计时。
- 客户端 `foldDeadline`（`<REPO_ROOT>/frontend/src/stores/ai.ts:87-100`，调用点 `:974` 生成式表单、`AiPanel.vue:569` 确认门）只决定**倒计时显示**；工作区在制品的改动为：`foldDeadline` 加 `atMs > 0` 守卫、后端 `SseChatEmitter#frame` 统一带 `atMs`（实时帧亦走折算分支）、相关注释与类型同步。这些改动**既不改**后端挂起超时判定，**也不改**回灌链路。
- 结论：本 issue 与 T4-3 在制品无交集，判定不受在制品影响（在制品亦未触碰 `ExportWizardView.vue` / `excel.ts` / `frontend-tools.ts`，`git status` 可证）。

---

## ④ 严重度与影响面

- **issue 主张的缺陷**：严重度 **不适用**（不可复现 / 归因错误）。若强行映射本仓三级口径，宜记 **S4 观察项（无行动）**，而非其所自评的"中"。
- **其中真实可行动项**（P1 回灌文案谎报、P3 无整链预算）：**S3（建议/须登记）**。P1 的后果上限 = 模型与用户被"已触发下载"误导（无数据破坏、无越权、无挂起泄漏）；P3 的后果上限 = 一次工具调用白等 120s 后收"未执行"回填（体验与轮次成本，已在既有登记项内）。
- **若原样采纳 issue 建议改法**：影响面为**负收益**——谎报成功常态化（P1 加深）、`downloading` 状态竞态、回灌顺序先于"是否真的发起下载"（语义倒置）。
- 涉及页面/链路：仅导出向导页的 `download_export_file` 工具卡与回灌文本；不涉及数据面、作业面、鉴权面。

---

## ⑤ 修复方向建议（多方案取舍）

**方案 A（推荐：不修代码，登记为观察项）**
不改 `await downloadOne`。把 P1 作为独立 S3 观察项登记（"前端工具回灌文案须反映真实结局：`downloadOne` 应返回结局而非吞错"），P3 并入既有"跨任务串行请求无整链预算"登记项，P2 的一半（`saveBlob` 立即 revoke）并入同一批小项。
取舍：零风险、零门禁成本，符合本仓"issue 关闭即删 + 观察项去向表"的政策；缺点是不解决"文案可能谎报"这一真实的（低危）体验面。

**方案 B（若用户要求"必须落一处改动"）**
1. `downloadOne` 返回结构化结局（`{ok:true,fileName} | {ok:false,reason}`），异常仍在函数内 `ElMessage` 提示但不再丢弃信息；
2. 执行器 `await` 后按结局回灌真话：成功 =「已触发下载…（在线编辑后的内容）」；失败 =「下载未完成：<原因>；请提示用户手动重试」；
3. 给执行器加**低于 120s 的自有预算**（`Promise.race` + 超时文案「下载较慢，未确认成功，请用户手动确认」），使 P3 类卡顿不再白等满 120s、也不再谎报。
取舍：改动 3 处（同一文件两个函数 + 文案），语义诚实且对 P3 有实际抑制；代价是需过前端门禁（`yarn typecheck` / `build`，**本任务未跑**，属该方案的验收前提）。收益与风险均远优于 issue 原建议。

**方案 C（不推荐：照 issue 原样）**
`void downloadOne(defCode).catch(err => console.error(...))`。唯一"收益"是把回灌提前，但 D4 已证该链本来就是毫秒级，提前无实质价值；代价是谎报必然化 + 失败反馈丢失 + 状态竞态。

---

## ⑥ 建议处置流程

1. **呈用户裁决**（本 issue 前提已被证伪，且本仓政策为"关闭即删、删前存档"，不宜自行改动或自行关闭）。
2. 用户三选一：
   - **(a) 采纳"不成立"** → 按政策关闭并删除；删前把 issue 全文存档到本机 issue 存档目录，命名建议 `issue-6-download_export_file回灌阻塞.md`；同时把 P1（S3）与 P3 登记进排期去向表（P2 的 `saveBlob` 一致性小项同批）。
   - **(b) 认定 P1 值得修** → 走方案 B 落地（前端改动 + 门禁），修完再关闭删除。
   - **(c) 用户在真机现场复现出 120s 超时** → 保留 issue，补现场证据（复现时的 Network 时序 + 帧序列 + `GET /api/ai/runs/{runId}` 中该 toolCallId 的终态）；届时按 P3 重新定性，修复点仍**不是** `void downloadOne`。
3. **决定性验证步骤（用户可自行执行，5 分钟；也可用于裁决 (c)）**：
   - 前置：Chrome 设置里**开启**「下载前询问每个文件的保存位置」（制造 issue 所述前提）。
   - 操作：在导出向导步骤 3 让 AI 调 `download_export_file`；对话框弹出后**先不关**。
   - 观察三点：① 页面是否仍在刷新/动画（= 渲染进程 JS 未停）；② Network 面板是否已出现 `POST /api/ai/frontend-tool-result`；③ 该 toolCallId 的后端终态（RESULT 还是 TIMEOUT/`FRONTEND_TIMEOUT`）。
   - 判读：若在对话框未关闭时 POST 已发出 / 卡片已收敛 → issue 论点**直接否证**；若确实卡满 120s → 提交该现场证据（此时根因按 P3 方向排查，与本 issue 描述的"下载阻塞"无关）。

---

## 附：本次分析用到的证据清单（可复核）

- `<REPO_ROOT>/frontend/src/views/ExportWizardView.vue:677-704, 821-…, 950-969`（执行器与下载链）
- `<REPO_ROOT>/frontend/src/utils/excel.ts:839-848`、`<REPO_ROOT>/frontend/src/utils/frontend-tools.ts:148-172, 517-559`
- `<REPO_ROOT>/frontend/src/stores/ai.ts:87-100, 880-928, 1282-1300, 1412-1416, 1481-1501`
- `<REPO_ROOT>/frontend/src/stores/workspace.ts:424-431`、`<REPO_ROOT>/frontend/src/api/http.ts:83-86, 145-154`、`<REPO_ROOT>/frontend/src/api/tasks.ts:73-75`
- `<REPO_ROOT>/frontend/src/components/SpreadGrid.vue:258-263`
- `<REPO_ROOT>/backend/src/main/java/com/example/configmgr/ai/gate/ConfirmGate.java:185-253, 286-312`、`.../ai/run/SseChatEmitter.java:548-557`、`.../ai/tools/AiTools.java:470-499`、`backend/src/main/resources/application.yml:101-103`
- `<REPO_ROOT>/docs/evidence/S44c-AI面板与出口验证.md:8, 125, 141, 171-172, 298` 及仓库外附件目录 `s44c-evidence`（`S44c-附件-帧序列汇总.txt`、`S44c-附件-ai-chat-11-…response.sse`）
- `<REPO_ROOT>/docs/M2-排期计划.md:149-170`（历史 issue#1–#5 的定性与去向登记口径）
- 本机 issue 存档政策与清单：`issue-archive/README.md`

---

# §验证实证（2026-10-10，Tier-1 隔离复现 + Tier-2 真实应用链路）

> 本节为**实证追加**，不改动上文任何章节。除特别注明外，本节所有结论一律标 **【实测】**。
> 脱敏：scratch 目录以 `<LOCAL_WORK>` 指代（= issue6-verify）；主机名以 `<HOST>` 指代；端口与 127.0.0.1 为本次验证的 loopback 事实。

## 0. 裁决句（先给答案）

issue#6 声称「`download_export_file` 前端工具执行器 `await` 下载阻塞导致回灌 120s 超时」——**该因果链【实测】不成立**：

1. **`downloadOne` 从被调用到 resolve 的实际耗时 = 毫秒级**。真实应用链路（真浏览器执行真执行器）里：`frontend_tool_request` 帧到达页面 → `anchor.click`（`downloadOne` 内最后一步）**9.0 ms**；`createObjectURL` → `anchor.click` **0.30 ms**；`anchor.click` → 回灌 POST 发出 **3.10 ms**；服务端 `frontend_tool_request.atMs → frontend_tool_result.atMs` **25 ms**。隔离复现里 `downloadBlob` 同步返回 **0.5–1.0 ms**（8/16/64MB 载荷），`click → 回灌 POST` **0.1–0.5 ms**。**没有任何一处在百毫秒量级，更没有分钟量级。**
2. **无法构造出"因下载本身"导致的回灌超时**【实测】。保存框（另存为）由**独立 utility 进程**承载，不占用渲染进程 JS 线程：弹框挂着 15.2 s 期间，页面 50 ms ticker 全程 45–64 ms 间隔、**一次都没缺失**（每次轮询 `msSinceLastTick ≤ 33 ms`），CDP 对页面的求值照常返回，回灌 POST 在**弹框出现后 2411 ms** 正常发出并被桩 200 接收。反过来，**下载字节直到用户确认弹框才落地**（确认前目录里没有该文件，确认后 `measure-16MB.xlsx` / `CURRENCY_货币字典.xlsx` 才出现）——即"下载可以挂很久"与"回灌早已成功"二者完全解耦。
3. 因此在真实栈上**复现不出** `"前端工具 download_export_file 未在 120 秒内回传执行结果"`：Tier-2 整轮 280 ms 走完 `frontend_tool_request → frontend_tool_result(ok=true) → done`，**无 heartbeat、无 FRONTEND_TIMEOUT**（后端台账 `status=DONE`、`error=null`）。

## 1. 与原设计（§⑥ 步骤 3）的调整及理由

| 原设计 | 实际做法 | 调整理由 |
|---|---|---|
| 人眼观察"页面是否仍在刷新/动画" | 页面内 50 ms ticker 全程记录每次间隔 + 每次轮询 `msSinceLastTick` | 人眼无法留证、无法量化"是否停摆"；ticker 缺失一次即是主线程被阻塞的硬证据 |
| 看 DevTools Network 面板是否有 POST | ① 页面钩子记 `XMLHttpRequest#send`（回灌真实走 axios/XHR）+ ② 本机桩服务的**服务端墙钟**接收时刻 + ③ CDP `Network.requestWillBeSent` 三口径交叉 | 面板读数不可留证、精度不足；服务端时刻是"POST 是否真的发出"的最硬口径 |
| 直接上全栈 | 分两层：**Tier-1** 隔离最小复现（逐字 `downloadBlob` + 大载荷）→ **Tier-2** 真实应用链路 | Tier-2 受"模型是否配合调用工具"的不确定性影响，故先用桩上游把工具调用确定化，再上真栈 |
| 只看后端终态 | 后端终态 + 帧序列（`atMs`）+ 真 UI 工具卡文本 三口径 | 单看终态无法分辨"成功"与"超时"（两者同型 `frontend_tool_result`），必须叠加 UI 与帧内 `ok` 字段 |
| 前置：开启「下载前询问保存位置」 | 未改浏览器设置即已满足：**本机该 Chrome 在 `prompt_for_download=false` 的 profile 下仍然弹保存框**（原因未深究，疑似系统/策略级设置）。"无弹框"对照组改由 CDP `Browser.setDownloadBehavior(allow)` 构造 | 本机天然处于 issue 所述前提环境，无需也无法用 pref 关闭；对照组用 CDP 精确构造并确证"全程 0 个对话框窗口" |

端口纪律：启动前 `netstat -ano | grep LISTEN` 仅见 6379（Redis，本机既有），父任务指定的 18330 / 18399 / 9335 / 9336 / 5211 **均空闲、无冲突、无需顺延**（复跑另用 9337，也已释放）。

仓库零写入的取证：

- 后端**不在仓库内构建**：把仓库源码**逐字节拷贝**到 `<LOCAL_WORK>/backend-run`（`diff -r` 无差异，含 10 个在制品改动中的 5 个后端文件），在其中运行 `spring-boot:run`；H2 库、文件存储、`target/` 全落 scratch。
- 前端 vite 以 `root=<REPO_ROOT>/frontend`（只读）启动，`cacheDir` 显式指向 scratch；`node_modules` 通过 scratch 侧 junction 只读引用。
- 收尾复核：`git status --porcelain` 仍为**同一 10 个在制品改动、无新增**；`frontend/node_modules/.vite` 与 `data/` 的 mtime 未变（未被本次会话写入）。

## 2. Tier-1：隔离最小复现（逐字 `downloadBlob`）

装置：本机桩 `127.0.0.1:18399` 同时充当被测页与回灌接收端；被测页内嵌 **HEAD 版 `frontend/src/utils/excel.ts:839-848` 的逐字副本**，按 `downloadOne` 真实时序（组包 → `downloadBlob` → 微任务 → 回灌 POST）执行；chrome.exe 独立 profile + CDP（9335/9337）。

| 场景 | 载荷 | `downloadBlob` 同步返回 | `click → 回灌POST` | POST 到达桩 | 主线程 ticker | 对话框 | 下载文件 |
|---|---|---|---|---|---|---|---|
| 无弹框（CDP allow） | 16 MB | **0.6 ms** | **0.1 ms** | click + 1 ms | 45.1–62.9 ms（无缺失） | 0/31 采样 | `measure-16MB.xlsx` 16777216 B（回灌后才落地） |
| 无弹框（首轮，默认行为） | 64 MB | **1.0 ms** | **0.2 ms** | click + 4 ms | 该次窗口仅 30 ms，未产生 tick（不作结论） | 见下 | 先出现 64MB 级临时文件，最终完整落盘 |
| **弹框挂着**（post 延迟 3 s） | 16 MB | **0.5 ms** | **3003.3 ms**（人为延迟） | click + 3003 ms | 45.6–63.8 ms（无缺失；轮询 11 次 `msSinceLastTick ≤ 33 ms`） | 首次出现 click+**592 ms**，最后仍在 click+**15198 ms** | 确认弹框（Enter）后 `measure-16MB.xlsx` 16777216 B 才出现；确认前该文件不存在 |
| 无弹框（headless 复跑） | 8 MB | — | **0.5 ms** | click + 4 ms | 45.7–63.5 ms | 0 个 | `measure-8MB.xlsx` 8388608 B |

弹框场景的关键事实：

- **回灌 POST 在弹框出现之后 2411 ms 发出并到达**（弹框 10:16:40.624 出现 → POST 10:16:43.035 到达），即"框还开着"与"回灌照常完成"同时成立。
- **弹框属主进程 = `chrome.exe --type=utility`**（独立进程，既不是渲染进程也不是浏览器 UI 主进程）→ 这是"D2 弹框不阻塞渲染进程 JS"的**结构性**解释，而非经验归纳。
- 弹框期间的 CDP 求值**正常返回**（`runDownload` 的 `awaitPromise` 求值按 3013 ms 精确返回，其中 3000 ms 是人为延迟），即浏览器→渲染进程的 RPC 与页面 JS 全程畅通。
- 取消/确认弹框会决定该次下载的字节是否落地 → 弹框确实是**这次下载**的（因果绑定成立）。

## 3. Tier-2：真实应用链路（真前端 + 真后端 + 真浏览器 + 桩上游）

装置（全部落 scratch，仓库只读）：

- 真前端：vite dev `127.0.0.1:5211`（root=`<REPO_ROOT>/frontend`）→ `/api` 代理到后端。
- 真后端：`127.0.0.1:18330`，源码为仓库逐字节拷贝；`app.ai.hitl.timeout=120s`（`/api/ai/health` 回读 `confirmTimeoutSeconds:120`）。
- 上游桩：`127.0.0.1:18399`（`--mode scenario`，**确定性**返回一次 `download_export_file` 工具调用，`args={"taskId":1,"defCode":"CURRENCY"}`），故 **未消耗真实模型 key**（`AI_API_KEY` 为占位串）。
- 真浏览器：chrome.exe（独立 profile，CDP 9336，本机默认弹保存框），页面 `/export?taskId=1` 走到**第 3 步（导出结果）**；AI 面板消息用 CDP 真实输入（`Input.insertText` + Enter）发出。
- 业务前置（全走公开 API，等价用户手点）：任务 #1（EXPORT）→ 选 `CURRENCY` → 条件 `{"fields":[]}` → 步骤 `EXPORT` → 导出作业 **COMPLETED 5/5** → 文件 `CURRENCY_货币字典.xlsx`（rowCount=5）。
- 用户消息：「请把 CURRENCY 的导出文件直接下载给我。」

### 3.1 服务端帧序列（`atMs` = 后端钟，逐字取自页面侧 SSE tee）

| # | 帧 | atMs | 备注 |
|---|---|---|---|
| 1 | `start` | 1791598843476 | 轮开始 |
| 2 | `suspended` | 1791598843689 | 挂起（前端工具通道） |
| 3 | `tool_start:download_export_file` | 1791598843690 | |
| 4 | **`frontend_tool_request:download_export_file`** | **1791598843693** | 下发回灌回调说明（`timeoutSeconds=120`） |
| 5 | **`frontend_tool_result:download_export_file`（ok=true）** | **1791598843718** | 回执帧；**Δ(4→5) = 25 ms** |
| 6 | `message_start` | 1791598843741 | 模型续跑 |
| 7-8 | `delta` ×2 | 1791598843743 / 43744 | |
| 9 | `message_end` | 1791598843754 | |
| 10 | `done` | 1791598843756 | 整轮 = **280 ms** |

帧序列中**没有 `heartbeat`、没有超时结局**；工具卡文本（真 UI，逐字）：`下载导出文件` / `已成功` / `已触发下载任务 #1 的 CURRENCY 导出文件（在线编辑后的内容）`。

### 3.2 核心间隔（页面侧钩子 + 服务端 `atMs` 交叉）

| 量 | 实测值 | 说明 |
|---|---|---|
| ① `frontend_tool_request` 帧到达页面 → `anchor.click` | **9.0 ms** | 真实执行器前段：`ensureTaskBoundForTool`（任务绑定门）+ `buildXlsx`（组包 7434 B） |
| ② `createObjectURL` → `anchor.click` | **0.30 ms** | `downloadBlob`（`excel.ts:839-848`）整段同步段 |
| ③ **`anchor.click` → 回灌 POST（`XMLHttpRequest#send`）** | **3.10 ms** | ← issue 指控的"阻塞段"实际耗时 |
| ④ 服务端 `frontend_tool_request.atMs` → `frontend_tool_result.atMs` | **25 ms** | 含浏览器执行 + POST 往返 + 后端挂起唤醒与发帧 |
| 回灌 POST 响应 | `loadend status=200`，click 后 **16 ms** | |
| 整轮 `start → done` | **280 ms** | |

回灌报文（页面钩子逐字）：`{"runId":"<runId>","toolCallId":"call-dc14-1","result":"已触发下载任务 #1 的 CURRENCY 导出文件（在线编辑后的内容）","source":"frontend-executor"}`。
后端台账 `GET /api/ai/runs/<runId>`：`status=DONE`、`error=null`、`resumedBy=[]`、`createdBy=instance-<HOST>-pid<…>-<hash>`、上下文 `{page:"export", taskId:1, taskType:"EXPORT", step:"EXPORT", extra.selectedDefs:["CURRENCY"]}`。

### 3.3 同一轮里的保存框事实

- 保存框首次出现于 **click + 612 ms**（10:20:44.320），此后 ≥3.2 s 仍开着（采样至 10:20:47.578）；该轮回灌 POST 在 click + 3 ms 已发出、click + 16 ms 已 200。
- 即本轮次序是"**回灌先于弹框**"（因为 `downloadBlob` 同步返回后执行器立刻 return、回灌立刻发出，而弹框是随后才被 Chrome 弹起的）；"**弹框先于回灌**"的次序由 Tier-1 弹框场景覆盖（POST 在弹框出现后 2411 ms 发出）。**两种次序都实测过，回灌都不受影响。**
- 确认弹框后 `CURRENCY_货币字典.xlsx`（**7434 B**，与页面 `createObjectURL` 的 `size=7434` 完全一致）才落入下载目录 → 该下载的真实待确认期 ≥4 s，而回灌在此前早已成功并被后端确认。

## 4. 与 §② D1–D5 的关系：哪些结论由推断升为实测

| 原判据 | 本次实证后的等级 |
|---|---|
| D1「`await downloadOne` 非"等下载完成"」 | **【推断】→【实测】**：`downloadBlob` 同步返回 0.5–1.0 ms；真应用里"click → 回灌 POST"3.10 ms |
| D2「弹框不挂起渲染进程 JS」 | **【推断-高置信】→【实测】**：弹框挂着 15.2 s 期间 ticker 45–64 ms 无缺失、CDP 求值正常；且弹框属主是独立 utility 进程 |
| D3「执行器慢也不卡回灌链路」 | **【实测】**：click 后 3–38 ms 内 `frontend_tool_result → message_start → delta → done` 依次到达，帧流未被阻塞 |
| D4「历史实跑该轮为成功轮」 | **【实测】补强**：本次在**同一技术栈**上复现出真实浏览器执行器路径（非脚本桩回灌），Δ=25 ms、`ok=true`、卡面「已成功」 |
| D5「无实测超时痕迹」 | **【实测】补强**：本次同样未出现 `FRONTEND_TIMEOUT`，后端台账 `status=DONE` |

## 5. 复跑指引、退出码与收尾

- 全部脚本/日志在 `<LOCAL_WORK>`：`tier1-stub.mjs`（被测页 + 回灌接收端）、`tier1b.mjs`（Tier-1 驱动，`nodialog`/`dialog` 两模式）、`instrument.js` + `tier2-run.mjs`（Tier-2 页面侧仪表与驱动）、`watch-dialogs.ps1` / `accept-dialog.ps1` / `close-dialogs.ps1`（弹框取证与处置）、`prep-task.py`（导出任务前置）、`launch-chrome.mjs`、`cdp.mjs`、`vite.scratch.config.mts`；日志 `s2-nodialog.log`、`s3-dialog.log`、`rerun.log`、`tier2-run2.log`（含各自 `*-dialogs.log` 弹框时间线）。
- 退出码：`node tier1b.mjs nodialog …` = **0**（headless 复跑，`EXIT_CODE=0`）；`python prep-task.py` 正常收尾（打印 `TASK_ID=1 / RUN_READY=1`）；`node tier2-run.mjs` 正常收尾（脚本以 `process.exit(0)` 结束并打印完整结论段；因命令经管道取尾部显示，未单独留 EXIT 码——收尾判据用"结论段完整 + 端口/进程收尾 + 台账 `status=DONE`"替代）。
- 进程收尾（按 PID 只停自起进程）：Chrome 9335/9336/9337、vite 5211、后端 18330、桩 18399 全部停止；`netstat` 复核 **18330 / 18399 / 9335 / 9336 / 5211 / 9337 全部释放**；桌面**无残留顶层对话框**（复核 `#32770` 计数 = 0）。
- 仓库收尾：`git status --porcelain` = 同一 10 个在制品改动，**无新增、无新增未跟踪文件**。

## 6. 诚实边界（未覆盖项）

1. **未构造出"回灌 120s 超时"本身**：该现象的前提（下载/弹框占用渲染进程 JS 线程）已实证不存在；要造出 `FRONTEND_TIMEOUT` 只能让前端**完全不回灌**（例如把执行器改成永不 settle），那与 issue 的因果主张无关，故按"不构造"处理。若需要，可在同一装置上把执行器人为改成 `await new Promise(()=>{})` 来演示"真正的 120s 超时"作为对照——本次未做。
2. Tier-1 是隔离页（逐字 `downloadBlob` 副本 + 自建载荷与桩），不是应用本身；真实应用链路由 Tier-2 覆盖（真组件、真执行器、真浏览器、真后端）。
3. 本机"弹框无法用 profile 偏好关闭"的原因未深究（只记录了现象），因此"无弹框"对照组依赖 CDP `Browser.setDownloadBehavior(allow)` 构造（该对照已确证全程 0 个对话框窗口）。
4. 上游用桩（非真实模型）：这提高了工具调用的确定性，但也意味着**真实模型的措辞/重试行为不参与**本次测量；对"回灌耗时"这一被测量对象无影响（回灌链在模型之外）。

---

# §用户质疑复审（2026-10-10，用户反驳「确实观测到 120s 超时」后）

> 纪律：本节纯**只读 + grep**（零 git 写操作、未改工作区文件、未跑构建）。开工前 `git status --porcelain` **为空**（T4-3 已提交、工作树干净）；<REPO_ROOT> 保持干净至今。
> 证据级别：【实测-代码】= 本次实读代码/日志所得；【实测】= 上节 Tier-1/Tier-2 的真机实验；【推断】= 由实测证据推理；【假设】= 未验证。
> 脱敏：scratch/证据目录以相对目录名或 `<LOCAL_WORK>` 指代，不写盘符/用户名/内网 IP。

## 0. 三条结论速览

| 问题 | 结论 |
|---|---|
| **Q1** 完整 await 链与超时预算 | 执行器有 **2 个 await 段**、其中绑定段内含 1–3 个串行 HTTP + 1 个**无超时**等待。串行理论最长：**跨任务路径 ≤240 s（4×60 s），另存在可无限等待的 `readyPromise`**；**但 issue 自己的复现场景（同任务、同一步）理论上也到不了 120 s（≤60 s+CPU）**。裁决：`await downloadOne` **单独不足以**吃满 120 s；能超 120 s 的是它**前面**的绑定段 ⇒ 用户"不应该 await"的**方向在该路径上部分成立，但落点错**。 |
| **Q2** 超时观测的实证 | **仓内无 `download_export_file` 超时的任何记录（0 处）**。全域仅有两处**真实发生过**的前端工具超时：`navigate_to`（桩驱动）与 `generative_form`（桩驱动）——二者共同点是"**没有浏览器应答者**"，白等 120 s 是设计行为。最可能来源见 §Q2.4。 |
| **Q3** 用户 patch 评估 | `void downloadOne(...)` **修不到 Q1 的两段真阻塞**（绑定段 / `readyPromise`），只覆盖"单项 ≤60 s、根本到不了 120 s"的 `buildXlsx` 段落；同时把"失败时才谎报"升级为"**必然且更早谎报**"，并引入 `downloading` 竞态与同屏矛盾，且其 `.catch` 是**死代码**。最终建议：**方向采纳、落点纠正 → 用方案 B（执行器自有预算 + 结局驱动文案），不用 `void`。** |

## Q1. `await downloadOne` 的完整 await 链与超时预算

### Q1.1 完整 await 链（类名#方法名 / file:line）

```
registerFrontendToolExecutor('download_export_file', async call => …)   ExportWizardView.vue:951-969
├─ [await 段①] ensureTaskBoundForTool(call.args.taskId)              :957 → 定义 :821-858
│   ├─ 分支 A：target===当前绑定 且 无在途绑定 → 直接 return（**0 次 HTTP**）      :824-839
│   ├─ 分支 B：target===当前绑定 且 有**同 id 在途绑定** → await pending.promise   :827
│   │     └─ 该 promise 由 applyTaskBinding 建立（:438-441）= router.replace(:439) + loadTask(:440)
│   └─ 分支 C：target≠当前绑定 → await tasksApi.getTask(target)                :843  ≤60 s
│                                  → await applyTaskBinding(target, preloaded)  :853
│        两者最终都进 loadTask（:312-342）：
│          ├─ await tasksApi.getTask                                        :319  ≤60 s
│          └─ await loadExistingResults                                     :341 → :351-374
│               ├─ await tasksApi.getFiles                                  :356  ≤60 s
│               └─ 若"有结果且无 activeTab"（改绑时 resetTaskScopedState 刚清空 activeTab，必命中）
│                    → await nextTick                                       :366
│                    → await ensureGridLoaded(activeTab)                    :367 → :641-660
│                        ├─ await tasksApi.downloadFile                     :651  ≤60 s
│                        └─ await grid.loadFromXlsx                         :652 → SpreadGrid.vue:227-236
│                             → await loadFromJson                          :229 → :203
│                                → **await readyPromise**              SpreadGrid.vue:203  ← **无任何超时**
├─ 前置校验（exportResults 是否含 defCode）                            :961-963（0 次 HTTP）
├─ stepIndex!==2 → goStep(2)                                          :964-966（**同步**）
│     └─ goStep 内部 :461-465 全是 `void …` 支路（persistStepData / syncStepToBackend / refreshResults）
│        —— 执行器**不 await** 它们；但其中 refreshResults(:464→:615-623) 同样会 await ensureGridLoaded
│        （即也会撞上 readyPromise），并与执行器**并发**吃同一 axios 连接池
└─ [await 段②] downloadOne(defCode)                                   :967 → 定义 :689-704
      └─ await buildXlsx(code)                                         :696 → 定义 :677-687
           ├─ 有 gridRefs[code] → grid.toXlsxBlob()                    :681 → SpreadGrid.vue:258-263
           │     → buildRowsXlsxBlob（excel.ts:457-480）→ 自写 OOXML + JSZip（excel.ts:622-632）
           │     ⇒ **纯 CPU，无网络、无超时**（大表可数秒，但不无界）
           └─ 无 gridRefs → await tasksApi.downloadFile(taskId, code)  :686 → tasks.ts:73 → http.ts:128-131  ≤60 s
         随后 downloadBlob（excel.ts:839-848，**同步**）+ ElMessage（:698/:700）

链路内唯一的逐请求上限：axios 实例 `timeout: 60000`（api/http.ts:83-86，`getBlob` 复用同一实例 :128-131）。
仓内其他超时设施均**不在**本链路：AbortController 只服务 AI SSE 流（stores/ai.ts:585/656/795、api/sse.ts:319）；
jobs.ts:52 的 300 s 轮询上限用于作业等待；**唯一的有界等待** `PAGE_READY_TIMEOUT_MS=3000`（utils/frontend-tools.ts:226，`waitForTaskBound` :275-…）
**根本没被本执行器使用**（ExportWizardView 只在自己的 :821 里做绑定，全文无 `waitForTaskBound` 调用，仅注释提到）。
```

### Q1.2 三条路径的串行阻塞预算

| 路径 | 触发条件 | 串行 HTTP await | 理论上限 |
|---|---|---|---|
| **A（同任务）** —— **issue 复现步骤所属路径** | AI 传的 `taskId` == 页面当前绑定任务，且无在途绑定 | 0 次（绑定段直接 return）；`buildXlsx` 有 grid 时 0 次、无 grid 时 1 次 | **≤60 s + CPU**（**到不了 120 s**） |
| **B（在途绑定）** | 同任务但 watch/`navigate_to` 刚发起绑定仍在途（`inFlightBinding` 命中 :825-827） | 等待在途 promise：getFiles ≤60 s(+ 可能 ensureGridLoaded ≤60 s) | ≤120 s ＋**无限**（readyPromise） |
| **C（跨任务）** | AI 传的 `taskId` ≠ 当前绑定任务（`:841-857`） | getTask 60 + getFiles 60 + ensureGridLoaded.downloadFile 60 + buildXlsx 回落 downloadFile 60 | **≤240 s（4×60 s）** ＋**无限**（readyPromise） |

> 自我更正：上节报告 §③ P3 写的"三段串行 ≈180 s"是**低估**。按实读计数最坏是 **4 段 × 60 s = 240 s**（分支 C 且无 gridRefs），另有 `readyPromise` 这条**没有预算**的无限等待。

### Q1.3 那条"没有超时"的等待：`readyPromise` 可永不 settle【实测-代码】

`<REPO_ROOT>/frontend/src/components/SpreadGrid.vue`：

| 位置 | 事实 |
|---|---|
| :87-89 | `const readyPromise = new Promise(resolve => { readyResolve = resolve })` —— 唯一 settle 入口是 `readyResolve` |
| :94-96 | `onMounted` 中 `if (!host.value) return` —— **早退分支不调用 `readyResolve`** ⇒ promise 永不 settle |
| :131-135 | `catch { engineState='failed' }` —— **失败分支同样不调用 `readyResolve`** ⇒ 同样永不 settle（只把状态标成 failed） |
| :203 | `loadFromJson` 的第一行就是 `await readyPromise` ⇒ 上面两种情形下**永久挂起** |

该风险**本仓自己已经登记过**：`<REPO_ROOT>/docs/M2-排期计划.md:154`（issue#2 红队 RR1）原话——"`SpreadGrid` 在表格引擎初始化完成前被卸载 ⇒ `onMounted` 早退分支不调用 `readyResolve` ⇒ `readyPromise` 永不 resolve ⇒ R1 绑定链不 settle ⇒ **同 id gate 理论死等（工具调用挂死，无 HTTP 超时兜底）**"，处置为"一行可修"但**列为观察项、未落地**。本次实读的 :94-96/:131-135 与之一致，并**新增**发现失败分支（:131-135）也是同型缺口。

### Q1.4 裁决：是否可达 120 s？

- **可达**【推断，前提明确】：走 **分支 C**（跨任务）或 **分支 B**（在途绑定）时，只要后端"接受连接但不回包"让 2–4 个请求各吃满 60 s axios 上限，执行器的 await 总计就会落在 **120–240 s**，回灌必然晚于 120 s；若 grid 引擎未就绪且组件被卸载/加载失败，则**无上限**（不依赖任何网络异常）。
- **但 issue 自己写的复现步骤走的是分支 A**（同任务、向导第 3 步）：绑定段 0 次 HTTP、`downloadOne` ≤60 s ⇒ **理论上限也到不了 120 s**。这一点与上节 Tier-2 实测一致（同任务真机实测：请求帧到点击 9.0 ms、click→回灌 POST 3.10 ms、服务端帧差 25 ms）。

### Q1.5 关键裁决问题：走该路径时 `await downloadOne` 是否真会把回灌阻塞到超时？

**分两层回答（这是本次复审最重要的区分）**：

1. **单看 `await downloadOne(defCode)` 这一行：不足以**【实测-代码】。它内部只有两类耗时：`buildXlsx` 的回落下载（1 次 HTTP，≤60 s）或 grid 组包（纯 CPU，无网络），加上同步的 `downloadBlob`。**单项上限 60 s < 120 s**，因此这一行**不可能单独**造成 120 s 超时。
2. **但看执行器整体：在该路径（跨任务/在途绑定）上确实会被阻塞到超时**【推断，代码坐实】——只不过**吃爆预算的是它前面的 `ensureTaskBoundForTool`（:957）那一段**（getTask/getFiles/ensureGridLoaded 三个串行 60 s，以及 `readyPromise` 无超时），而不是 `await downloadOne`。
3. 因此用户的判断"**不该把回灌绑死在慢 await 上**"这一**方向**，在 Q1 坐实的路径上**是成立的**（机制不是对话框，而是执行器内部的串行 HTTP / 无超时等待）——**但落点不对**：把 `await downloadOne` 改成 `void`，对分支 B/C 的阻塞**基本无效**（详见 §Q3.1）。正确落点是"**给整个执行器一个低于 120 s 的回灌预算**"，而不是"放过其中一行"。

## Q2. 用户观测到的 120 s 超时的真实证据

### Q2.1 issue 正文所述（逐字，来自上节 §①）

> `/api/ai/frontend-tool-result` 无法在 120 秒内发出，后端超时报错："前端工具 download_export_file 未在 120 秒内回传执行结果"。AI 回复用户"下载未能确认成功"。
> 复现步骤：1. AI 在导出向导步骤 3 调用 `download_export_file(taskId, defCode)` 工具 …

即：**同任务、第 3 步**（= Q1 的分支 A），且断言超时是"下载对话框阻塞 JS 主线程"。

### Q2.2 全域检索结果（<REPO_ROOT> + 各证据/报告目录 + 浏览器缓存）

| 类别 | 命中 | 性质 |
|---|---|---|
| 代码/规格/决策文档里的**表述** | `ConfirmGate.java:300`、`docs/03-技术方案文档-v2.1.md:264`、`docs/spike/SP-01ab-决策卡.md:130`、`spike/sp01ab/…/SpikeToolCallingManager.java:130`、`spike/sp02/…/ToolCallExecutor.java:94`、`docs/evidence/GFa-…:238` | **非观测**（语义/文案定义） |
| **真实发生过的**前端工具超时（1）：`navigate_to` | `issue4-scratch/s4-stub/req-03-scenario.head.txt:29` + 同名 `.json`（`role:"tool"` 的内容 = "前端工具 **navigate_to** 未在 120 秒内回传执行结果（前端超时），本次调用未获得数据。"） | **桩驱动**现场（stub 发起工具调用，无浏览器应答者） |
| **真实发生过的**前端工具超时（2）：`generative_form` | `m2t12-run/e2e-artifacts/stub-violate-b/req-036.head.txt:26`、`req-037.head.txt`（同型文案，工具名 `generative_form`） | **桩驱动**现场（无人填表 ⇒ 超时） |
| `download_export_file` 的**超时**记录 | **0 处**（全域检索 `download_export_file` × `未在 … 秒内回传` 无命中；`.json/.txt/.sse/.log` 与浏览器 Cache 均无） | —— |

**结论【实测】：仓内没有 `download_export_file` 回灌超时的任何实证。** 唯一两处真实超时记录都不是该工具，且都发生在"前端无应答者"的桩环境。

### Q2.3 那个成功轮的对照（同工具、真环境）

`docs/evidence/S44c-AI面板与出口验证.md`（真浏览器 kimi-webbridge + 真 key 在线）：:125 下载轮帧序列含 `frontend_tool_request → frontend_tool_result → delta → done`；:141/:171-172 工具卡「已成功 … 已触发下载 CURRENCY 的导出文件（在线编辑后的内容）」。即**该工具在真环境里被实测为成功轮**（与上节 Tier-2 复现一致）。

### Q2.4 最可能来源分析（按可能性排序）

- **H1【推断，可能性最高】桩驱动 / 无浏览器应答者的环境**：`m2t12-run/…/stub-violate-b`、`issue4-scratch/s4-stub` 这一类 harness（脚本 + 上游桩）里，`frontend_tool_request` 发出后**没有任何页面执行器**，也不由脚本回灌 ⇒ 后端按设计白等满 120 s 并回填「（前端超时）」。这种环境下的超时**与下载、与 `await downloadOne`、与 JS 主线程全都无关**；把该行改成 `void` 也不会改变结果（执行器压根没被跑到）。若用户的观测来自此类 harness，则 issue 的"实际"段是把**环境缺失**误读为**代码阻塞**。
- **H2【推断】Q1 的分支 B/C + 后端僵死/长时间不回包**（≤240 s），或 grid 引擎未就绪且组件已卸载（**无限**）。机制与下载无关，但与"执行器不该 await 慢操作"的方向相关；需要"跨任务 taskId 或在途绑定"这一前提（issue 正文写的是同任务，与之不符）。
- **H3【假设，已被证伪】下载/保存框阻塞 JS 主线程**：上节 Tier-1 实测（弹框挂着 15.2 s，50 ms ticker 45–64 ms **零缺失**；回灌 POST 在弹框出现后 2411 ms 发出并 200）与 Tier-2 实测（真应用 click→POST 3.10 ms、服务端帧差 25 ms）已否证。

### Q2.5 若要自证"这次观测到底属于哪一类"，取证三件套（可判定，不需重跑）

1. **该次 `runId` → `GET /api/ai/runs/{runId}`**：看 `status` 与是否带 `FRONTEND_TIMEOUT`；这是"是否真发生超时、以及是哪个 toolCallId"的权威台账。
2. **帧序列**：`frontend_tool_request` 之后是**约 10–11 个 `heartbeat`**再收到超时结局（挂起期每 ~11 s 一次心跳），还是直接 `frontend_tool_result(ok=true)`。前者=真白等 120 s，后者=正常。
3. **该环境里有没有"活的前端"**：若为桩/脚本驱动（无页面执行器），则无论前端代码怎么写都会超时 ⇒ 观测归属 H1；若为真浏览器且 `download_export_file` 曾进入执行器（页面侧能看到 `anchor.click`/回灌 POST），才可能归属 H2/H3，而 H3 已被实测排除。
   另：超时回填文案带全角括号后缀「（前端超时）」，与 `ConfirmGate.java:300` 逐字一致，可用于比对用户手里的截图/日志是否为本仓文案。

## Q3. fix 方案技术评估（用户提议的确切 patch）

用户 patch：`void downloadOne(defCode).catch(err => console.error('download failed:', err))` 之后**立即** `return '已触发下载…'`。

### Q3.1 它修不修得好 Q1 坐实的真实阻塞路径？

| Q1 阻塞点（分支 B/C） | 该 patch 是否覆盖 | 依据 |
|---|---|---|
| `ensureTaskBoundForTool` 内的 `getTask` / `getFiles`（跨任务再 +60 s） | **✗ 完全不覆盖** | 该 await 在 :957，**位于** patch 之前的执行器前段 |
| `applyTaskBinding → loadExistingResults → ensureGridLoaded → grid.loadFromXlsx` | **✗ 完全不覆盖** | 同上，且这是**唯一可无限等待**的一段（SpreadGrid.vue:203） |
| `goStep(2)` 的支路（persistStepData / syncStepToBackend / refreshResults） | 无关（本就未 await） | :461-465 全为 `void` |
| `buildXlsx` 回落 `downloadFile`（≤60 s） | ✓ 覆盖 | 这是 patch 唯一真正改变的段落，而它**单项 <120 s** |
| `downloadBlob` + `ElMessage` | ✓ 覆盖（本就毫秒级） | 上节实测 0.30 ms / 3.10 ms |

**结论【推断，代码坐实】：该 patch 覆盖的恰好是"根本到不了 120 s"的那一段，而能超过 120 s 的两段（绑定段、`readyPromise`）都在它之外。** 换句话说：**在用户主张的观测环境里，这个 patch 大概率什么也不会改变**；若真有 H2 型观测，最可能是"少等了 buildXlsx 那 ≤60 s"，属于边际缓解而非修复。

### Q3.2 它引入的代价（具体形状）

1. **谎报从"失败时"升级为"必然且更早"**【实测-代码】。现行代码的文案虽已无条件（:968），但至少是"尝试之后"才发；改 `void` 后，回灌在**尚未执行 `buildXlsx`／尚未触发任何下载**之前就发出。此时 `buildXlsx` 的"尚未绑定任务"（:684）、网格组包失败、回落下载失败等，全部只留 `console.error` + 用户侧红 toast（:700），**模型侧一律看到成功**。
2. **同屏矛盾**：用户会同时看到红色 `ElMessage.error('下载失败…')` 与工具卡「已成功 / 已触发下载任务 #N 的 … 导出文件」——比现在的"AI 说未能确认"更糟（现在是模型不确定，改后是**界面自相矛盾且模型确信**）。
3. **`.catch(console.error)` 是死代码**【实测-代码】：`downloadOne` 内部已 `try/catch/finally` 吞掉全部异常且**不 rethrow**（:695-703），其 Promise 不会 reject ⇒ 该守卫不执行任何逻辑（唯一效果是把错误降级进控制台）。这说明该 patch 的作者对"错误已经在哪里被吞掉"的假设不成立。
4. **`downloading` 竞态的确切形状**【实测-代码】：`downloadOne` 在 :694 置 `downloading = code`、在 :702 finally 清空；UI 的转圈绑定在 :62（当前项）、:65-68（打包/全部）。改为不 await 后：AI 触发的那次下载仍在途时，用户（或同轮后续工具调用）再触发第二次下载 → **第一次的 finally 会把 `downloading` 清成 null**，第二次的 loading 指示提前消失/闪烁；多次重叠时 `downloading` 可能停在 null 而仍有下载在途（状态失真，按钮可点、重复下载）。
5. **文案断言失去依据**：括注「（在线编辑后的内容）」在 `void` 之后可能在 `toXlsxBlob` 尚未执行时就回灌；若实际回落走服务端文件（:686），该括注本身也可能不成立——把一个"已核实的事实"变成"未核实的断言"。

### Q3.3 与方案 B 的对比

| 维度 | C（用户 patch，`void` + 立即成功） | B（`downloadOne` 返回结局 + 执行器按结局回灌 + 自有 <120 s 预算） |
|---|---|---|
| 修 Q1 的真实阻塞段（绑定段 / `readyPromise`） | ✗（只覆盖 ≤60 s 的 buildXlsx 段） | ✓（预算罩住**整个**执行器，超时即回灌，不白等满 120 s） |
| 回灌文案诚实性 | ✗ 必然谎报（先回灌、后尝试） | ✓ 结局驱动（成功/失败原因/超时未确认三态） |
| 失败可行动性 | ✗ 模型认为成功，用户只能自己发现 | ✓ 回灌带原因，模型可改道并提示用户手动重试 |
| UI 一致性（`downloading`） | ✗ 竞态 + 同屏矛盾 | ✓（保留 await 语义，按结局收口） |
| 与 issue 原意（"回灌别被慢操作拖死"）契合 | 部分（方向对、落点错、代价高） | **完全**（回灌及时 + 不谎报 + 覆盖真阻塞段） |
| 改动量 / 验收 | 1 行（但仍需过前端 typecheck/build 门禁） | 约 3 处（同文件 `downloadOne` + 执行器 + 文案），需过门禁 |
| 副作用 | 死代码 + 状态竞态 + 谎报 | 需定义预算阈值（建议 60–90 s，留出 POST 与后端发帧时间） |

### Q3.4 最终取舍（一句话）

**采纳方向、纠正落点：不要用 `void`** —— 它既修不到 Q1 坐实的两段真阻塞（绑定段与可无限等待的 `readyPromise`），又把"失败时才谎报"升级为"必然且更早谎报"并引入 `downloading` 竞态与同屏矛盾；应改用**方案 B**（执行器自有低于 120 s 的预算 + 结局驱动文案），并把两条根因单独登记为 S3：① `SpreadGrid.readyPromise` 在早退/失败分支永不 settle（`SpreadGrid.vue:94-96`、`:131-135`，仓内 M2 排期 :154 已登记但未修）；② 前端工具执行器**无自有预算**（`frontend-tools.ts:550` 只 `await executor(call)`，全靠后端 120 s 兜底）。

## 附：本次复审的只读边界

- 未运行任何进程/脚本（上节 Tier-1/Tier-2 装置已全部停用并复核端口释放）；本节结论全部来自**实读源码 + 全域 grep + 上节实测**。
- `git status --porcelain` 在开工与收尾均为**空**；未新增/修改 <REPO_ROOT> 任何文件。
- 引用到的外部证据目录（`issue4-scratch/`、`m2t12-run/`、`s44c-evidence/` 等）均为**只读检索**，未改动。


---

**〔分件 2／3〕** `<LOCAL_WORK>/issue6-reports/issue6-复现-kimibrowser.md` · 源 152 行 / 19,515 B · 角色：复现（实机）

# issue#6 复核报告（Kimi Code 自带浏览器实机复现）

- issue：`<USER>-<USER_FRAGMENT>/ai-example` **#6「download_export_file 前端工具执行器 await 下载阻塞回灌超时」**（open，公开 slug，非本机用户名）
- 复核对象仓库：`<REPO_ROOT>`（分支 `main`，HEAD=`550c7c4`）——**本次零仓库文件改动**（`git status` 仅见 `docs/M2-排期计划.md`，该文件 mtime=11:01:43，由并行的父会话在写，非本棒产生；运行期 H2/文件落 `<LOCAL_WORK>\issue6-repro\db`，未写 `backend/data/`）
- 复核时点：2026-10-10 10:56–11:12（本机时钟，+08:00）
- 复核方式：**实机运行**（真后端 18330 + 真前端 5211 + 真实 DeepSeek `deepseek-flash` + Kimi Code 自带浏览器）
- 一句话结论：**复现成功**。在 Kimi Code 自带浏览器里，`download_export_file` 的回灌 POST 在 3 次实机轮次中 **2 次未在 120s 内发出**（结局帧 = 120,004 ms / 120,007 ms 的前端超时，UI 出现 issue 原文文案），**1 次延迟 30.6s 才发出**；同一链路在独立自动化 Chromium 上仅 **3.1–3.3 ms**。

---

## ① 结论分层（先给判定，再给证据）

| 层级 | 内容 |
|---|---|
| **【实测】复现** | Kimi Code 自带浏览器下 `download_export_file` 回灌超时 **可复现（3 次中 2 次超时，确定性非偶发）**；工具卡文案与 issue 正文逐字一致：「前端工具 download_export_file 未在 120 秒内回传执行结果（前端超时），本次调用未获得数据。」AI 回复落入 issue 描述的「下载未能确认成功」形态 |
| **【实测】阻塞段计时** | 自带浏览器 3 次：回灌 POST 相对 `frontend_tool_request` 帧的延迟 = **未到达（>120s，2 次）** / **30,621 ms（1 次）**。对照：独立自动化 Chromium 2 次 = **41 ms / 25 ms**（其中页面侧 anchor.click→POST = **3.3 ms / 3.1 ms**） |
| **【实测】下载行为** | 自带浏览器：**无任何"另存为"窗口出现**（80 次/160s 窗口采样，0 命中）；下载记录 `blob:http://localhost:5211/...` → run1 `state=completed`（7,434 B，实测 **113.4 s** 后才落盘）、run2/run3 `state=cancelled, receivedBytes=0`（文件未新增）。对照 Chrome（前次）：保存框弹出、独立 utility 进程、主线程零停摆 |
| **【推断-高置信】机制** | 自带浏览器的**下载处理链路把页面（渲染/网络）整体拖住**，时长与"下载项被解析（完成/取消）"的时刻强相关：run3 中 POST 到达(11:10:18.230) 仅滞后于下载被取消(11:10:17.726) **504 ms**；停滞期间该页面对 `/api` **零请求**（含回灌 POST、健康检查） |
| **【假设-未证】根因** | 为何该浏览器的 blob 下载要 30–113s 才解析（且 2/3 次以 0 字节取消）——本次无法在 MCP 浏览器内注入 JS/看 Network 面板，未能定位（候选：下载落盘/保存流程同步等待、下载保护/扫描外呼、非可见面板下的下载确认流程）。**不影响 issue#6 结论**：无论下载为何慢，"执行器 await 链路被下载拖住 ⇒ 回灌迟到/丢失"已被实测复现 |
| **【旁证】与 issue 文案的差异** | issue 正文把根因写成"浏览器下载对话框阻塞 JS 主线程"。本次**未观测到保存框**（该浏览器是自动落盘到 `<DRIVE>/<USER>\Downloads`），与用户 Chrome 的差异说明：**"下载对话框"不是必要条件，下载链路本身即可拖住回灌** |

---

## ② 代码锚点（触发路径，类名#方法名）

| 环节 | 锚点 |
|---|---|
| 工具登记（后端，FRONTEND 通道，`@ToolScope("task:EXPORT/EXPORT")`） | `AiTools#downloadExportFile`：`backend/src/main/java/com/example/configmgr/ai/tools/AiTools.java:346-354`（方法体 `FRONTEND_STUB_SHOULD_NOT_RUN`） |
| 挂起/回灌闸门（120s 出处） | `ConfirmGate`（`ai/gate/ConfirmGate.java`）+ `app.ai.hitl.timeout: 120s`：`backend/src/main/resources/application.yml:101-103` |
| SSE 帧下发 | `SseChatEmitter#frontendToolRequest`：`ai/run/SseChatEmitter.java:548-557`（帧带 `atMs`/`timeoutSeconds`/`expiresAt`） |
| 前端帧分发（fire-and-forget） | `useAiStore#handleFrame`：`frontend/src/stores/ai.ts:1290-1299`（`void this.runFrontendTool(message, frame)`） |
| 前端执行 + 回灌 | `useAiStore#runFrontendTool`：`frontend/src/stores/ai.ts:1481-1501`；`useAiStore#submitFrontendToolResult`：`frontend/src/stores/ai.ts:880-928`（`POST /api/ai/frontend-tool-result`） |
| 通用执行器（`await executor(call)` 即回灌前置条件） | `executeFrontendTool` / `runRegisteredTool`：`frontend/src/utils/frontend-tools.ts:148`、`:517-559`（关键行 `frontend-tools.ts:550` `const result = await executor(...)`） |
| 被指控的执行器（**阻塞点**） | `ExportWizardView.vue:950-969`，第 **967** 行 `await downloadOne(defCode)`（`registerFrontendToolExecutor('download_export_file', …)`） |
| 下载实现 | `ExportWizardView#downloadOne`：`frontend/src/views/ExportWizardView.vue:689-704`（`await buildXlsx` → `downloadBlob`）；`buildXlsx`：`:677-687`（有表格走 `grid.toXlsxBlob()`，否则 `tasksApi.downloadFile`） |
| 浏览器下载原语 | `downloadBlob`（`frontend/src/utils/excel.ts:839-848`）/ `saveBlob`（`frontend/src/api/http.ts:145-154`）：`URL.createObjectURL` → `a.download` → `a.click()` |
| 工具披露校验（实测披露表） | 后端日志 `ResilientChatService`：`上下文=task:EXPORT/EXPORT 披露工具=[…, download_export_file, …]`（本次 5 轮全部含该工具） |

---

## ③ 环境与启动（全部落 `<LOCAL_WORK>\issue6-repro\`）

| 项 | 值 |
|---|---|
| 后端 | `mvn spring-boot:run`（Maven 3.9.16 / JDK 21），`SERVER_PORT=18330`，`SPRING_DATASOURCE_URL=jdbc:h2:file:<LOCAL_WORK>/issue6-repro/db/config_mgr_db`，`APP_FILE_STORAGE_PATH=<LOCAL_WORK>/issue6-repro/db/files`，包装脚本 `run-backend.sh`，日志 `backend.log`（PID 16292） |
| 模型 | 真 DeepSeek：`AI_BASE_URL=https://api.deepseek.com`、`AI_MODEL=deepseek-flash`、`AI_API_KEY=***`（key 只经环境变量注入，未落任何文件/报告）；`GET /api/ai/health` → `{"available":true,"model":"deepseek-flash"…}` |
| Redis | Memurai `127.0.0.1:6379` 已在跑（只读确认） |
| 前端 | `yarn dev`（Vite 6.4.4），`VITE_DEV_PORT=5211`，`VITE_API_BASE=http://127.0.0.1:18329`（**记录型反向代理**，脚本 `run-frontend.sh`，日志 `frontend.log`，PID 12056） |
| 记录型代理 | `proxy.mjs`：`18329 → 18330`，逐条落 `proxy-events.jsonl`（请求到达时刻、POST 体、SSE 每帧原文+时刻），**只观测不改行为**（PID 23024） |
| 前置数据 | `prep-task.py` 全走公开 API 造出：任务 **#1**（EXPORT，标题 `issue6-kimibrowser-await-download`）→ 选 `CURRENCY` → 设条件 → 步进 `EXPORT` → 起导出作业 #1 → `COMPLETED 5/5` → 文件 `CURRENCY_货币字典.xlsx`（EXPORT，5 行） |
| 浏览器 | **Kimi Code 自带浏览器**（MCP `mcp__desktop_browser__run`，tab `panel-tab-123`，URL `http://localhost:5211/export?taskId=1`），无登录/账号要求（应用无鉴权入口） |

---

## ④ 逐步操作（可重放）

1. `netstat -ano | grep -E "18330|5211"` 确认为空 → 起后端（`run-backend.sh`）→ 轮询 `/api/ai/health` 就绪。
2. 起记录型代理（18329）→ 起前端（5211，`/api` 指到 18329）。
3. `python prep-task.py` 造前置数据（任务 #1 + CURRENCY 导出结果）。
4. 自带浏览器建 tab → `http://localhost:5211/export?taskId=1`（导出向导已停在第 3 步「导出配置」，AI 面板上下文显示 `task:EXPORT/EXPORT`、会话 `d95af34d`）。
5. 在 AI 输入框填入「请调用 download_export_file 工具把 CURRENCY（货币字典）的导出文件下载给我，taskId=1，defCode=CURRENCY。」→ 点「发送」（`page.element.click`）。**模型第 1 次即配合调用**（无重试、无 stub）。
6. 观测（每次）：代理 JSONL 的 `frontend_tool_request` 帧 `atMs`、`POST /api/ai/frontend-tool-result` 到达时刻与体、SSE 结局帧；`browser.get_downloads` 下载记录；后端 `backend.log`（`工具执行退出 … tool=download_export_file`）；`page.text.snapshot`/截图（工具卡四态 + AI 文案）；`run3-monitor.log`（每 2s 采样窗口列表/落盘/日志/计数）。
7. 共跑 3 轮（同会话 `d95af34d`，同一任务/同一配置项）：run1 面板隐藏、run2 面板隐藏、run3 先 `browser.switch_tab` 令面板可见。
8. 收尾：按 PID 停前端(12056)/后端(16292)/代理(23024)，`netstat` 复核端口释放（见 §⑨）。

---

## ⑤ 核心计时数据表（后端钟，`atMs` 与代理到达同为本机钟，可交叉核对）

| # | 轮次 | `frontend_tool_request` 帧 | 回灌 POST 到达代理（Δ 相对帧） | 结局帧 Δ | 结局 |
|---|---|---|---|---|---|
| 1 | 对照·自动化 Chromium run1 | 11:01:45.778 | 11:01:45.819（**Δ41 ms**） | 27 ms | ok「已触发下载任务 #1 的 CURRENCY 导出文件…」 |
| 2 | 对照·自动化 Chromium run2 | 11:02:22.415 | 11:02:22.440（**Δ25 ms**） | 23 ms | ok（同上） |
| 3 | **自带浏览器 run1**（面板未显示） | 11:03:20.810 | **120 s 内未到达** | 120,004 ms | **fail「前端工具 download_export_file 未在 120 秒内回传执行结果（前端超时），本次调用未获得数据。」** |
| 4 | **自带浏览器 run2**（面板未显示） | 11:06:41.749 | **未到达** | 120,007 ms | **fail（同上，逐字一致）** |
| 5 | **自带浏览器 run3**（切换为可见） | 11:09:47.609 | 11:10:18.230（**Δ30,621 ms**） | 30,620 ms | ok「已触发下载任务 #1 的…（在线编辑后的内容）」 |

页面侧细分（对照·自动化 Chromium，注入仪表实测）：

| 段 | run1 | run2 | 前次用户 Chrome 实测 |
|---|---|---|---|
| `frontend_tool_request` → `createObjectURL` | 9.9 ms | — | 9.0 ms |
| `createObjectURL` → `anchor.click` | 0.3 ms | — | 0.30 ms |
| **`anchor.click` → 回灌 POST(`xhr.send`)**（issue 指控的阻塞段） | **3.3 ms** | **3.1 ms** | **3.10 ms** |
| 回灌 POST → 后端受理并回帧 | 27 ms | 23 ms | 25 ms |
| 主线程 ticker（50 ms 基准）最大间隔 | 62.4 ms | 84.7 ms | 保存框挂着时仍 47–63 ms |
| 轮次总耗时（发送→done） | 1.6 s | 2.1 s | 0.3 s（工具段） |

> 说明：自带浏览器**无法注入 JS/无 Network 面板**（MCP 工具无 evaluate 能力），故自带浏览器侧只有"帧 `atMs` → POST 到达代理"这一条服务端钟口径的端到端数字；页面侧细分只能在对照浏览器上取。两者对照已足以判定阻塞段归属。

---

## ⑥ 下载行为观测（issue 的"下载对话框/下载策略"假设）

| 观测项 | 自带浏览器 | 对照·自动化 Chromium | 前次用户 Chrome |
|---|---|---|---|
| 是否弹保存框 | **未观测到**（160 s 内 80 次窗口采样，标题里无"另存为/Save As"；采样窗含 Kimi Code/Chrome/设置/任务管理器等，全程无新增窗口） | 不适用（headless 自动落盘） | 弹（Windows 保存框，独立成窗） |
| 落盘位置 | `<DRIVE>/<USER>\Downloads\CURRENCY_货币字典.xlsx`（run1 最终落盘 7,434 B，mtime 11:05:14.279） | 下载目录内 GUID 临时名（Playwright 语义） | 保存框确认后落盘 |
| 下载记录（`browser.get_downloads`） | run1 `completed 7434/7434`、run2 `cancelled 0/7434`、run3 `cancelled 0/7434` | 页面 download 事件 + 落盘 | `.tmp(7434B)` |
| 下载本身耗时 | run1：startedAt 11:03:20.829 → 记录完成 11:05:13.985（**113.4 s / 7 KB**）；run2：startedAt 11:06:41.761 → 取消于 ≈11:08:53.58（>120 s）；run3：startedAt 11:09:47.617 → 取消于 ≈11:10:17.73（**30.1 s**） | 立即完成（毫秒级） | 立即（文件在保存框开着时已生成 .tmp） |

**关键相关性【实测】**：run3 中"下载被取消"时刻 ≈11:10:17.73，回灌 POST 到达 = 11:10:18.230——**POST 恰好紧跟下载被解析之后（+504 ms）**；run1/run2 中下载未解析（>120 s）期间 POST 始终没有出现。停滞窗口内该页面对 `/api` 的请求数 = **0**（代理日志仅有 SSE 帧，无任何请求；run1 事后 UI 还出现一次「后端不可达，请确认服务已启动（/api 代理目标见 VITE_API_BASE）」提示，说明有 `/api` 请求在网络层失败）。

---

## ⑦ 证据文件清单（`<LOCAL_WORK>\issue6-repro\`）

| 文件 | 内容 |
|---|---|
| `proxy-events.jsonl` | 记录型代理全部事件（HTTP 请求/响应 + SSE 每帧原文与时刻，395 KB）——**原始计时来源** |
| `timeline.md` / `timeline.json` | §⑤ 计时表（脚本按 `runId` 配对帧与 POST，避免索引错配） |
| `backend.log` | 后端全量日志：5 轮 `AI 轮次开始`（披露工具含 `download_export_file`）、`工具执行退出 … outcome=done` 的时刻（= 超时/回灌受理时刻）、无任何"未在 N 秒内回传"字样（超时结局只在 SSE 帧里） |
| `run3-monitor.log` / `run3-monitor.sh` | run3 期间每 2 s 采样：窗口列表（找保存框）、下载文件 mtime、代理/后端计数 |
| `shots/kimibrowser-run1-01-before-send.png` | 自带浏览器：发送前（第 3 步 + 消息已填） |
| `shots/kimibrowser-run2-02-timeout-card.png` | 自带浏览器：**超时证据截图**（工具卡「下载导出文件 前端工具 已成功」+ 结果文本「…未在 120 秒内回传执行结果（前端超时）…」+ 黄色「后端不可达」提示 + AI 回复「若仍反复超时…需要联系系统管理员排查下载服务」） |
| `shots/kimibrowser-run3-03-success-after-30s.png` | 自带浏览器：run3（延迟 30.6 s 后回灌成功）的工具卡「已成功 / 已触发下载任务 #1…」 |
| `shots/run{1,2}-0{1,2,3}-*.png` | 对照·自动化 Chromium 三次节点截图（发前 / 工具请求 / 完成） |
| `proxy.mjs` `run-backend.sh` `run-frontend.sh` `prep-task.py` `alt-browser/{instrument.js,run.mjs,run1.log,run2.log,run1-raw.json,run2-raw.json}` | 全部脚本与对照浏览器原始输出（可重放） |

---

## ⑧ 与前次「用户 Chrome 实测（回灌 3.10 ms）」的对比与差异解释

| 维度 | 前次用户 Chrome | 本次 Kimi Code 自带浏览器 |
|---|---|---|
| `anchor.click` → 回灌 POST | 3.10 ms | **>120 s（2/3 次）或 30.6 s（1/3 次）** |
| 保存框 | 弹（另存为），独立 utility 进程；**主线程零停摆**（ticker 47–63 ms） | **未弹**；但页面被拖住（网络层零请求、POST 迟到/丢失） |
| 下载本本体 | 保存框开着期间即生成 `.tmp(7434B)` | 7 KB blob 要 30–113 s 才解析，2/3 次以 0 字节取消 |
| 结论 | 未复现阻塞、定性"归因错误" | **复现**（issue#6 成立，但根因不是"保存框阻塞"） |

**差异解释【推断-高置信】**：两者差异不在业务代码，而在**宿主浏览器的下载实现**。用户 Chrome 指"对话框"那条因果链确实不成立（前次已用主线程 ticker 反证）；但 issue 真正敏感的变量是"**下载链路会不会拖住页面 JS/网络**"——在 Kimi Code 自带浏览器（Electron 内置面板，下载需经应用进程落盘/确认）上，这个变量为"会"，于是 `await downloadOne(...)` 所在的同一 JS 任务被拖住，回灌 POST 随之迟到或丢失，140 ms 内必然发生的回灌变成 120 s 超时。

---

## ⑨ 偏差、替代路径与未决项（诚实边界）

1. **MCP 浏览器一度不可用**：11:01–11:03 期间 `browser.create_tab`/`browser.activate_panel` 连续 5 次返回 `BROWSER_UNAVAILABLE: Browser control requires a running turn`（读操作 `get_state`/`get_history`/`get_downloads` 正常）。→ 我因此**先走了替代路径**：Playwright 1.64.0 自带 Chromium（独立二进制、独立临时 profile，**未使用用户 Chrome、未使用 kimi-webbridge**），run1/run2 两组对照数据即来自该路径（已明确标注为【旁证】）。11:03 之后同一 `create_tab` 调用**成功**（同类调用前后结果不同，判定为该守卫的时序/状态问题），随即转入**自带浏览器正式复现**（run1/run2/run3）。
2. **无法在自带浏览器内注入 JS**：MCP 工具集无 evaluate/Network 面板，故自带浏览器侧无法直接量"主线程停摆"，只能给服务端钟的端到端计时 + 下载记录 + 窗口采样；页面侧细分来自对照浏览器。**结论的强度不受影响**（超时文案、120 s 结局、POST 缺失均为自带浏览器一手的实测）。
3. **tab 可见性波动**：`switch_tab` 报 `visible:true` 后，随后一次 `press_key` 回包又显示 `visible:false`，故 run3 的"面板可见"未严格成立（只能确认行为不同：30.6 s 而非 >120 s）。**未决**：可见性/焦点是否为该浏览器下载迟滞的调节变量。
4. **对照浏览器 headless**：headless 下不存在保存框，故它测的是"无对话框的自动下载链"；其页面侧 3.3/3.1 ms 与前次用户 Chrome 的 3.10 ms 高度一致，说明**业务代码本身在任何正常下载实现下都不阻塞**——阻塞来自自带浏览器的下载实现。
5. **未做**：`accept-downloads`/保存框强制策略下的自带浏览器对比（无法在 MCP 浏览器设置下载策略）；`void downloadOne(...)` 修复后的回归（**严禁改仓库**，故未改代码；若按 issue 建议改成不 await，可预期 run1/run2 形态消失）。
6. **端口收尾**：后端 16292 / 前端 12056 / 代理 23024 均已按 PID 停掉，`netstat` 复核 18330、5211、18329 无 LISTENING；H2 与文件只落 `<LOCAL_WORK>\issue6-repro\db`。
7. **应用侧残留状态**：自带浏览器里我建的 tab（`panel-tab-123`，`http://localhost:5211/export?taskId=1`）**保留未关**，其中完整保留了 run1–run3 的对话与工具卡（含「未在 120 秒内回传执行结果（前端超时）」那张卡），可直接目视复核；因前端 dev server 已停，该 tab 当前会重连失败（属预期）。同时 back-end Redis 里该会话 `<KIMI_SESSION_A>` 的 5 轮台账仍在（未清理）。

---

## ⑩ 对 issue#6 的直接含义（证据级）

- **【实测】issue 的"实际"段成立**（自带浏览器）：回灌 POST 未在 120 s 内发出 → 后端 120 s 前端超时 → AI 回「未在 120 秒内回传执行结果（前端超时），本次调用未获得数据」→ 用户侧表现"下载未能确认成功"。该现象**可确定性复现（3 次中 2 次）**。
- **【实测】issue 的"根因"段不准确**：阻塞不来自"Windows 保存框阻塞浏览器进程"（本次自带浏览器**没有保存框**照样阻塞；前次用户 Chrome **有保存框**却不阻塞）。真正的敏感变量是"宿主浏览器的下载链路是否拖住页面"。
- **【推断】issue 的修复建议方向正确**：把 `await downloadOne(defCode)` 改为 fire-and-forget（立即 return 回灌，"已触发下载"语义本就允许先回灌），可在任何下载实现下消除这条耦合；本次数据支持"执行器不该把回灌挂在下载完成上"。
- **副作用面**：自带浏览器下该工具的**实际下载交付也不可靠**（run2/run3 记录 `cancelled / 0 B`，文件未新增；run1 最终才落盘），即"AI 触发的下载"在该浏览器里既可能超时、也可能不落盘——证据指向"下载链路"而非"回灌链路"是更值得排查的一侧。


---

**〔分件 3／3〕** `<LOCAL_WORK>/issue6-reports/关闭报告.md` · 源 84 行 / 10,712 B · 角色：收尾（关闭）

# issue#6 关闭报告（存档 → 评论 → 关闭 → 删除）

- 目标：GitHub `<USER>-<USER_FRAGMENT>/ai-example` issue **#6**「`download_export_file` 前端工具执行器 await 下载阻塞回灌超时」
- 执行时点：2026-10-10 15:29–15:34（+08:00，本机时钟）
- 通道：**kimi-webbridge**（用户真实浏览器 Chrome + 浏览器扩展，daemon `127.0.0.1:10086`，`status` 报 `running:true` / `extension_connected:true` / v2.0.22）；**gh CLI 未使用**
- 政策依据：`<LOCAL_WORK>\issue-archive\README.md`（关闭即删，删除前必须全文存档）
- 结果：**四步全部完成，issue 已删除**

---

## ① 四步执行结果

| 步 | 动作 | 结果 | 关键回执 |
| --- | --- | --- | --- |
| 1 | **存档** | 完成 | `<LOCAL_WORK>\issue-archive\issue-6-download_export_file前端工具执行器await下载阻塞回灌超时.md`（21035 字节；抓取时 15497 字节，其后补入关闭评论全文与实测时刻）；抓取时点 REST 快照 `evidence-issue6-api-open.json`（state=open、comments=1、labels=[]） |
| 2 | **评论** | 完成 | 评论 ID **`6095138228`**，`created_at = 2026-10-10T07:30:44Z`（15:30:44 +08:00），body 长度 **1198** 字符（与拟稿逐字全等）；issue 此时仍为 `state=open`（评论与关闭分两次动作，见「偏差 ③」） |
| 3 | **关闭 issue** | 完成 | 点击 `Close issue`（@e69）；REST 复核 `state=closed`、`state_reason=completed`、`closed_at = 2026-10-10T07:31:08Z`（15:31:08 +08:00）、comments=2 |
| 4 | **删除 issue** | 完成 | `Delete issue`（@e89）→ 确认框 `Delete`（@e3）→ 自动跳转 `<REPO-URL>/issues` |

## ② issue 最终状态核验（删除后，三重口径）

| 口径 | 结果 | 证据 |
| --- | --- | --- |
| ① open 列表 | `is:issue state:open` → **`No results matched your search`**；分段计数回落为 **`Open 0` / `Closed 0`** | `evidence-issue6-open-list-after-delete.png`（15:32，当刻分段计数 `Closed 1` 为索引滞后）、`evidence-issues-empty-open-final.png`（15:34，已回落 0/0） |
| ② closed 列表 | `is:issue is:closed` → **`0 results` / `No results matched your search`** | `evidence-issue6-closed-list-after-delete.png`、`evidence-issues-empty-closed-final.png` |
| ③ 直访 issue URL | 页面标题回落为 `GitHub · Where software is built`，正文占位 **「This issue has been deleted. <USER>-<USER_FRAGMENT> deleted this from ai-example」**；匿名 REST `GET /repos/<USER>-<USER_FRAGMENT>/ai-example/issues/6` → **HTTP 410 Gone**（`{"message":"This issue was deleted",…,"status":"410"}`） | `evidence-issue6-deleted-placeholder.png`、`evidence-issue6-api-deleted-410.json` |

- 说明：GitHub 对 HTML 请求以 `200` 交付该删除占位页，故判据为「占位页文本 + REST 410」双重，而非 HTML 状态码。

## ③ 关闭通报评论（第 2 步，逐字，已发布）

- 永久链接：`<REPO-URL>/issues/6#issuecomment-6095138228`（删除后仅剩占位页）
- 逐字副本：`_wb\submitted-comment.md`、`_wb\issue6-comment-6095138228-verbatim.md`（与 REST `body` 逐字比对 `draft==api body: True`）

要点（与派单要求逐条对应）：

1. **根因已确认**：阻塞不在业务代码，而在**宿主浏览器的下载链路把执行器任务拖住**——`frontend-tools.ts` 的 `await executor(call)` 是回灌唯一前置，`ExportWizardView` 的 `await downloadOne(defCode)` 停住则回灌 POST 发不出。派单中的「保存目录弹框」被如实修订为**触发形态之一而非必要条件**（自带浏览器 160s/80 次窗口采样未观测到保存框照样阻塞；用户 Chrome 有保存框却不阻塞，主线程 ticker 47–63 ms 无停摆）。
2. **复现证据留档**：自带浏览器 3 轮中 2 轮回灌未在 120s 内发出（结局帧 `120,004 ms` / `120,007 ms`），1 轮延迟 30.6s 成功；对照自动化 Chromium 25–41 ms（页面侧 3.1–3.3 ms）。报告与全部证据在仓库外存档。
3. **根治已合入主线**：如实写成**两笔**——`21b05f0`（前端工具执行器统一超时预算 + 结局驱动回灌文案，issue#6/R-06，即派单所指「统一超时预算」）与 `bf8aa6d`（预算公式严格化「服务端上限 − 回灌 POST 上限 60s」+ 计时器溢出钳制 `2^31−1`，即派单所指「计时器钳制」）；并说明**未采纳**正文建议的 `void downloadOne(...)` 落点（会引入更早的谎报成功与竞态）。CI：run `38030848895`，`head_sha=bf8aa6d`，completed/success；`main` 当前 HEAD `d3020ee`（其后仅文档提交）——均由 REST 独立核验（`evidence-issue6-api-commit-*.json`、`evidence-issue6-api-branch.json`）。
4. **按政策关闭并删除本 issue**：已写入评论末段。

## ④ 存档文件清单

**存档文档**（`<LOCAL_WORK>\issue-archive\`）

| 文件 | 内容 |
| --- | --- |
| `issue-6-download_export_file前端工具执行器await下载阻塞回灌超时.md` | issue#6 全文存档（标题、状态、正文逐字 1944 字符、全部 2 条评论、标签/元数据、事件时间线、根因结论、根治提交表、删除留证、留证清单）；命名与格式照 #1–#5 先例 |
| `README.md` | 存档政策索引已更新：新增 #6 行、新增 #6 关闭评论 ID `6095138228`、新增「API 缓存陷阱」条目 |

**证据文件**（`<LOCAL_WORK>\issue6-reports\`，19 件 + `_wb\` 过程目录）

| 文件 | 内容 |
| --- | --- |
| `evidence-issue6-open-before-comment.png` | 评论前：Open 徽标 + 正文 + 右侧栏（含 `Delete issue`）+ 空评论框 |
| `evidence-issue6-commented-before-close.png` | 评论已发布、issue 仍 Open（第 2 步与第 3 步分离的证据） |
| `evidence-issue6-closed-before-delete.png` | 关闭后删除前：`Closed` 徽标 + `<USER>-<USER_FRAGMENT> closed this as completed 1m ago` + `Reopen issue` |
| `evidence-issue6-delete-confirm.png` | 删除确认框（`Delete issue? / This cannot be undone / Only administrators can delete issues / …`） |
| `evidence-issue6-deleted-placeholder.png` | 删除后直访 `issues/6` 的占位页 |
| `evidence-issue6-open-list-after-delete.png` | 删除后 `is:issue state:open` → `0 results` |
| `evidence-issue6-closed-list-after-delete.png` | 删除后 `is:issue is:closed` → `0 results` |
| `evidence-issues-empty-open-final.png` | 索引回落后的全域复核（`Open 0` / `Closed 0`） |
| `evidence-issues-empty-closed-final.png` | 索引回落后的 closed 复核（`0 results`） |
| `evidence-issue6-api-open.json` | 删除前 REST 快照（state=open、comments=1） |
| `evidence-issue6-api-comments.json` | 删除前评论快照（含既有通报 `6093217948`） |
| `evidence-issue6-api-events.json` | 删除前时间线快照（1 条 `commented`） |
| `evidence-issue6-api-closed.json` | 关闭后删除前 REST（state=closed、`closed_at=2026-10-10T07:31:08Z`、comments=2） |
| `evidence-issue6-api-comment.json` | 关闭评论 REST 快照（ID `6095138228`、body 1198 字符） |
| `evidence-issue6-api-commit-21b05f0.json`、`evidence-issue6-api-commit-bf8aa6d.json` | 两笔根治提交的 REST 快照（含完整 patch） |
| `evidence-issue6-api-branch.json` | 删除前 `main` HEAD = `d3020ee` |
| `evidence-issue6-api-deleted-410.json` | 删除后 REST 410 响应体 |
| `evidence-issues-api-all-list-after.json` | 删除后 `GET /issues?state=all` → **空数组（仓库 issue 总数 0）** |
| `_wb\` | 过程原始件：请求/响应体（`req-*.json` / `out-*.json`）、快照（`snap*.json/.txt`）、逐字正文与评论副本（`issue6-body-verbatim.md`、`issue6-comment-6093217948-verbatim.md`、`issue6-comment-6095138228-verbatim.md`、`submitted-comment.md`、`comment-draft.md`） |

## ⑤ 执行细节与偏差记录（诚实边界）

1. **REST 核验踩到浏览器 HTTP 缓存（本 issue 首次现形，已记入存档 README）**：关闭动作完成、UI 已显示 `Closed` 后，用页内 `fetch` 直接打 `api.github.com` **仍返回 `state=open`**——`api.github.com` 的 `Cache-Control: private, max-age=60` 让 `fetch` 命中了缓存。改用 `cache:'no-store'` + 时间戳查询参数后得到正确结果（`state=closed`）。**若只信第一次取数会误判关闭失败**。删除后的 410 核验同样带 `no-store`。
2. **删除按钮定位方式**：`Issue actions` 下拉里的 `Transfer/Clone/Lock/Pin/Delete issue/Give feedback`（@e89–@e95）**在该页 DOM 中始终存在**，且截图里一度以浮层形态出现；故未用坐标点击，而是「snapshot 取 ref → 点 `Delete issue` → snapshot 取确认框 ref（`Cancel` @e2 / `Delete` @e3）→ 点 `Delete`」，两次均由可访问性树核验按钮文本。
3. **第 2 步与第 3 步分离执行**（照派单「顺序不可乱：存档→评论→关闭→删除」）：先点 `Comment`（@e61）发表评论、issue 保持 Open（REST 复核 `state=open, comments=2`），再点 `Close issue`（@e69）关闭。先例 #5 用的是单次 `Close with comment`；本次按派单要求拆成两步，故多一张 `evidence-issue6-commented-before-close.png` 过渡留证。**未使用 `More options`/`Close with comment`，关闭原因为默认 `completed`。**
4. **索引滞后如期复现**：删除后立即截图的分段计数为 `Open 0 / Closed 1`（陈旧值），列表主体已是 `0 results`；约 75 s 后回落为 `Open 0 / Closed 0`（`evidence-issues-empty-open-final.png`）。与 #4/#5 同一现象。
5. **存档文档内的路径按本目录脱敏惯例写成 `<LOCAL_WORK>\…`**（存档目录在仓库外但仍不写本机绝对路径）；本报告按派单要求写真实路径 `<LOCAL_WORK>\…`。
6. **派单口径与实测口径的一处出入已如实处理**：派单描述根因是「自带浏览器弹保存目录选择框导致挂起」，而实机复核记录为「自带浏览器本轮**未观测到**保存框照样阻塞、有保存框的用户 Chrome 不阻塞」。评论中按证据写成「保存框是触发形态之一、非必要条件」，未照抄派单措辞，以免把已被实测修订的因果留成结论。
7. **零仓库写操作**：全程只读/只写 GitHub 与仓库外文件；未对任何本地 git 仓库执行写操作，未运行构建。`<REPO_ROOT>` 工作区的既有未提交改动（12 项，含 `docs/M2-排期计划.md` 等）由并行会话产生，与本棒无关。
8. **浏览器现场未收尾**：本次使用的 tab（tabId `1033773479`，会话 `issue6-close`，tab 组「issue#6 关闭存档」）**保留未关**，可直接目视复核删除占位页与列表页；需要清理时请让用户明确指示（关闭 tab 组属用户发起动作）。
