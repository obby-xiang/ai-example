# S4.4c AI 面板完整实现 + 契约联动 + 出口验证（证据文档）

> 日期：2026-10-07　角色：S4.4c 前端合流实施工程师（c 棒）
> 依据：`<MAIN_V2>/docs/s4/S4.4-前端合流规格.md`（§1.1 视觉纪律、§2 部件映射、§3 API 对齐、§5 出口 8 条）
> 仓库：`<MAIN_V2>`（main-v2，a/b 棒已落地，backend 补丁棒已修 P1–P5）
> 实跑形态：后端 `java -jar target/config-mgr.jar --server.port=18318`（H2 文件库 + 本机 Redis 6379，
> `AI_API_KEY=***` 仅经环境变量注入）；前端 `yarn dev`（`VITE_API_BASE=http://localhost:18318`，端口 5202）；
> 浏览器操作经 kimi-webbridge（session `s44c`，页签组「S4.4c AI 面板与出口验证」）；AI 走真实模型 `deepseek-flash`。
> 本棒**未**执行任何 git 写操作；未改 `backend/`；`docs/` 下只新增本文件。

---

## 0. 代码改动清单（13 个文件，+1289 / −287）

> 注：本统计为最终工作区实测值（`git diff --numstat HEAD -- frontend/` 合计）；其中补丁轮（裁决②③）
> 追加的 +243 / −3 增量见本文 §P0「补丁验证」节。

```
$ cd <MAIN_V2> && git status --short
 M frontend/src/App.vue
 M frontend/src/components/AiPanel/AiPanel.vue
 M frontend/src/stores/ai.ts
 M frontend/src/stores/workspace.ts
 M frontend/src/styles/main.css
 M frontend/src/types/condition.ts
 M frontend/src/types/data.ts
 M frontend/src/types/sse.ts
 M frontend/src/types/tools.ts
 M frontend/src/utils/frontend-tools.ts
 M frontend/src/views/DataBrowserView.vue
 M frontend/src/views/ExportWizardView.vue
 M frontend/src/views/ImportWizardView.vue
（无新增文件、无删除文件；.env.local 与 dist/ 由 .gitignore 覆盖）
```

| 文件 | 改了什么 | 为什么 |
|---|---|---|
| `components/AiPanel/AiPanel.vue` | 占位壳 → 完整面板：蓝本同款头部/消息气泡/思考链折叠/工具卡/HITL 卡/输入区；四态工具卡（含参数与结果）；确认卡倒计时置灰 + 拒绝原因输入；409/503/断流三类 EP Alert + 一键重挂；上下文条（会话/工作区/数据版本/同步 chip） | 规格 §2 部件映射 + §5 出口 3/6 + 裁决⑦ |
| `stores/ai.ts` | ① `suspended` 帧只在含 CONFIRM/FRONTEND 条目时才置挂起（后端**每批工具**都发该帧）；② `confirm_request/confirm_decision` 联动工具卡四态；③ `reasoning` 帧前向兼容；④ 断流 `streamInterrupted`（ADR-6 文案）；⑤ 工作区契约订阅 `startWorkspaceSync`；⑥ 发消息注入并清空 `recentActions`；⑦ reattach 参数类型防御；⑧ AI 栏展开态页签级记忆；⑨ 409/503 补 `probeHealth` | 后端实测帧语义 + 契约联动（规格 §3/§4） |
| `stores/workspace.ts` | `touch()`：版本自增 + 动作记录 + `workspace_changed` 事件；`buildContext().extra.recentActions`；`contextSummary` getter；`dispatchUiEvent` 结果记入动作并广播 `ui_event_result` | 契约联动（用户点击 → AI 上下文，deepseek 事件形态） |
| `utils/frontend-tools.ts` | 三条路由：登记前端工具 / ui_event 同名动作（经契约层 `dispatchUiEvent`）/ 页面 handler；保留三级可用性兜底 | 规格 §2「前端工具执行器 + 三级兜底」 |
| `types/sse.ts` | 新增 `SseReasoningFrame` + `REASONING_FRAME_TYPE`（**不入**后端 13 类实测白名单） | 裁决⑦：有 reasoning 帧才渲染，前向兼容 |
| `types/tools.ts` | `WorkspaceActionSource` / `WorkspaceActionRecord` / `WORKSPACE_ACTION_LIMIT` | 契约事件形态 |
| `types/condition.ts` | 操作符白名单补 `LIKE`；**删除客户端镜像求值器**（`matchRow`/`compareConditionValues`/`matchesOperator`） | 移交项⑤⑦ |
| `types/data.ts` | `DataQuery.conditions` | 移交项⑦（列表端点已接 conditions） |
| `views/DataBrowserView.vue` | 改走服务端 `conditions`（列表 + 计数同口径），页面不再本页求值；口径说明文案同步 | 移交项⑦ |
| `views/ExportWizardView.vue` | `goStep` 落库 `PUT /tasks/{id}/step`；`watch(workspace.step)` 跟随契约层步骤；表格编辑 `touch` 同步上下文 | E4「刷新后任务状态正确」+ 契约联动 |
| `views/ImportWizardView.vue` | 同上（步骤落库 + 跟随 + 表格编辑同步） | 同上 |
| `App.vue` | AI 栏改 `el-aside`（400px 展开 / 48px 收起 + `transition-[width]`，蓝本 App.vue:6-12 同款）；`layout-column` 改内联 Tailwind | 视觉 1:1（b 棒 §6.7 移交「宽度/折叠语义」） |
| `styles/main.css` | 删 `.layout-column`（Tailwind 可做）；补 `.writing-vertical` 工具类（蓝本 style.css:14-19 同款） | 样式纪律 |

---

## 1 E1 `yarn build` 绿（TS strict 0 error）

```
$ cd <MAIN_V2>/frontend && yarn build
$ vue-tsc --noEmit && node scripts/build.mjs
✓ built in 32.47s
Done in 37.17s.
EXIT=0
dist/assets/index-BjWA55CO.js   68.17 kB   ← AI 面板/状态层分包（本棒新增代码）
dist/assets/element-…js      1,090.25 kB
dist/assets/spreadjs-…js     6,198.64 kB
```

- `vue-tsc --noEmit`（tsconfig：`strict: true`、`noUnusedLocals/Parameters`、`noImplicitOverride`、
  `verbatimModuleSyntax`）**0 error**；构建输出 **0 warning**（`grep -ci error/warning` = 0/0）。
- 基线对照：本棒开工前同一命令亦为 EXIT=0（`/tmp/e1-baseline.log`，48.17s）。
- 全仓 `any` 计数：`grep -rn ": any\|<any>\|as any" src/` → **0**。

## 2 E2 双向导走查全通（快速回归）

| 流程 | 路径（真实点击） | 结果 |
|---|---|---|
| 导出 | 任务中心「创建任务(导出)」→ 导出向导第 1 步勾选 CURRENCY → 下一步（第 2 步条件）→ 下一步（第 3 步）→ **开始导出** → 结果表格（`货币字典（5 行）`）→ **下载当前配置项 xlsx** | 全通：作业 COMPLETED 5/5，`GET /tasks/4/files` → `CURRENCY_货币字典.xlsx` rowCount=5（截图 `30-E7-export-done.png`、`31-E7-export-downloaded.png`） |
| 导入 | 任务中心「创建任务(导入)」→ 导入向导第 1 步勾选 CURRENCY → 下一步 → **启动预检查**（job#7 COMPLETED 5/5 错误 0）→ 下一步 → **启动导入**（job#8 COMPLETED 5/5 错误 0）→ 下一步 → 暂存数据核查 → **确认发布**（job#9 COMPLETED 5/5 错误 0）→ `GET /api/data/CURRENCY` 版本自增 | 全通（截图 `33/34/35/36-E7-*.png`） |

- 步骤落库（本棒新增）实测：点「下一步」后 `GET /api/tasks/{id}` → `currentStep=EXPORT/PRECHECK/…`
  与界面同步（修复前恒停在 `SELECT_DEFS`，刷新会退回首步——见 §12 缺陷 3）。
- 数据浏览口径（移交项⑦）实测（截图 `38-E2-databrowser-conditions.png`）：条件 `货币代码 code 等于 CNY`
  → 前端实际发出的请求（抓包原文）
  `GET /api/data/CURRENCY?page=0&size=20&scopeType=GLOBAL&conditions={"fields":[{"fieldCode":"code","operator":"EQ","value":"CNY"}]}`
  → **200，`totalElements=1`，`content[0].rowKey=CNY`**；页面表格仅 1 行，口径说明
  「列表与行数预估走同一个服务端 conditions 口径（前端不再做本页求值），当前命中 1 行」。
  即：列表端点**已接 `conditions`**，前端不再做「只筛当前页」的镜像求值。

## 3 E3 AI 联动（真实模型 deepseek-flash）

### 3.1 渐进披露子集正确（后端 DEBUG 原文）

```
$ grep "披露工具" <MAIN_V2>/backend 启动日志
上下文=task:EXPORT/QUERY_COND 披露工具=[check_job_status, get_config_def, get_row_count,
    list_config_defs, get_workspace_state, list_tasks]
上下文=task:EXPORT/EXPORT     披露工具=[…同上…, open_export_file_editor, download_export_file, start_export]
上下文=task:IMPORT/PRECHECK   披露工具=[…同上…, start_precheck]
上下文=task:IMPORT/IMPORT     披露工具=[…同上…, start_import]
上下文=task:IMPORT/PUBLISH    披露工具=[…同上…, start_publish]
```
（全文：`S44c-附件-渐进披露-工具子集实测.txt`）——**同一会话内随页面/步骤切换，子集逐级变化**，
且从未出现越界工具（QUERY_COND 下无 `start_export`，PRECHECK 下无 `start_import`）。

### 3.2 契约注入（`buildContext`）实测请求体

```json
POST /api/ai/chat
{"sessionId":"538cdc9e-…","message":"请告诉我当前工作区状态，并预估 CURRENCY 的导出行数。",
 "context":{"page":"export","taskType":"EXPORT","step":"QUERY_COND","taskId":2,
  "extra":{"pageId":"export","selectedDefs":["CURRENCY"],"importMode":null,"dataVersion":8,"contractVersion":1,
   "recentActions":["进入任务中心（来源：界面）","进入导出向导（来源：界面）",
                    "切换到步骤「查询条件」（来源：界面）","已选择配置项：CURRENCY（来源：界面）"]}}}
```
- 严格 5 键（page/taskType/step/taskId/extra），多余信息进 `extra`（后端 ObjectMapper 不容未知键，规格 §3）；
- `extra.recentActions` = **用户手点动作经契约层同步进 AI 上下文**（§11）；
- 面板上下文条同步显示「工作区 导出向导 · 任务 #2 · 步骤 查询条件 · 已选 1 个配置项」与
  「工作区已同步：已选择配置项：CURRENCY（v8 · 界面）」（截图 `05-ai-panel-context.png`）。

### 3.3 帧序列（真实抓包；原文见 `S44c-附件-帧序列汇总.txt`）

| 轮次 | 步骤 | 帧序列（省略连续 delta） |
|---|---|---|
| 工作区查询 | QUERY_COND | start → delta → suspended → tool_start → tool_result → tool_start → tool_result → suspended → tool_start → tool_result → delta → done（3 次工具：get_workspace_state / get_row_count / list_tasks） |
| 启动导出 | EXPORT | 同上（start_export + check_job_status ×2） |
| 下载导出文件 | EXPORT | start → suspended → tool_start → tool_result → suspended → tool_start → **frontend_tool_request** → **frontend_tool_result** → delta → done |
| 预检查 | PRECHECK | start → delta → suspended → tool_start → tool_result ×3 → delta → done |
| 导入 | IMPORT | start → suspended → tool_start → tool_result ×2 → delta → done |
| 发布（放行） | PUBLISH | start → suspended → tool_start → **confirm_request** → heartbeat ×11 → **confirm_decision** → tool_result → suspended → tool_start → tool_result → delta → done |
| 发布（拒绝） | PUBLISH | start → suspended → tool_start → **confirm_request** → heartbeat ×9 → **confirm_decision** → tool_result(ok=false) → delta → done |

> 说明：确认/拒绝两轮的 `.sse` 原文在浏览器抓包缓冲区轮换后被释放（`帧序列汇总.txt` 中这两条显示 `帧序列(0)`），
> 上表为该两轮当时读取到的原始序列；同批次的 `S44c-附件-ai-chat-11-*.request/response`（前端工具轮）与
> `S44c-附件-e6-holding-run-frames.sse`（挂起轮）保留了原文。

### 3.4 工具卡四态实测

| 态 | 证据 |
|---|---|
| **等待确认** | 截图 `18-ai-publish-confirm.png`：工具卡 `启动发布作业 [确认门] [等待确认]`，参数 `{"taskId":3}` |
| **调用中** | 截图 `06-ai-turn1-generating.png`（正文流式中工具卡为「调用中…」深色 tag） |
| **已成功** | 截图 `07/10/11/15/16/37`：`已成功` + 参数 + 结果文本（如「导出作业已启动（作业 #1）」「已触发下载…」「已打开 CURRENCY 的在线编辑器」） |
| **执行失败** | 截图 `22-ai-rejected.png`：`启动发布作业 [确认门] [执行失败]`，结果「用户拒绝了该操作，工具未执行。拒绝原因：本棒为拒绝路径验证…」+ 红字「原因：…」 |

### 3.5 HITL 确认 / 拒绝双路径

- **放行**：`18-ai-publish-confirm.png`（确认卡：需确认 tag + 参数 + `剩余确认时间 1:46` +
  `拒绝原因（可选，填了会原样回填给模型）` + 拒绝/确认执行）→ 点「确认执行」→
  `19-ai-publish-approved.png`；后端 `POST /api/ai/confirm {"approved":true}`、
  作业 #4 `PUBLISH COMPLETED 5/5`，`GET /api/data/CURRENCY` 5 行版本自增。
- **拒绝**：`21-ai-confirm-2nd.png`（第二次确认卡）→ 填原因 `本棒为拒绝路径验证，不执行本次发布。`
  → 点「拒绝」→ `POST /api/ai/confirm {"approved":false,"reason":"本棒为拒绝路径验证，不执行本次发布。"}`
  （原文：`S44c-附件-e6-409-response.json` 同目录抓包）→ 工具卡 `已拒绝/执行失败` + 助手回复逐字复述拒绝原因
  （截图 `22-ai-rejected.png`）→ `GET /api/ai/runs/{runId}` 台账里该 toolCallId `status=REJECTED`。
- **倒计时置灰**：`confirm_request.timeoutSeconds=120` → 面板按秒倒计时；超时（`remainingSeconds<=0`）
  时按钮 `disabled` 且文案变「确认已超时（按钮已置灰），本轮将按拒绝/超时收尾」（代码路径 + E6 挂起轮
  等待 120s 间实时显示 `剩余确认时间`；未刻意等满 120s，见§13 待裁决 5）。

### 3.6 思考链（裁决⑦：有帧才渲染）

- 后端 `SseChatEmitter` 当前**不产出** `reasoning` 帧（deepseek-flash 的 `reasoning_content` 只做
  ADR-4 回填补丁）。前端按裁决⑦实现：`toSseFrame` 前向兼容识别 `reasoning`；`handleFrame` 累积
  `message.reasoning`；面板 `<details v-if="m.reasoning">` **无内容即整块不出现**。
- 实测（11 轮全量帧序列）：无 `reasoning` 帧 → 面板从未出现空的「思考过程」折叠块（截图 `07/08` 全为
  正文 + 工具卡）。后端补帧后无需改前端契约。

### 3.7 AI 驱动工作区（导航/打开编辑器/触发下载）

- `open_export_file_editor`（FRONTEND 帧）：截图 `37-E3-open-editor-fetool.png` —— 工具卡
  `打开导出文件在线编辑器`，参数 `{"taskId":6,"defCode":"CURRENCY"}`，结果「已打开 CURRENCY 的在线编辑器
  （导出结果表格）」，工作区第 3 步切到该配置项并渲染 SpreadJS 表格（**导航 + 驱动工作区**）。
- `download_export_file`：截图 `11-ai-frontend-download.png`，结果「已触发下载 CURRENCY 的导出文件
  （在线编辑后的内容）」（**触发下载**）。
- 执行器三级兜底：页面未注册 handler 时返回 `degraded` 说明文本（不静默、不抛回模型）；
  上下文不匹配时返回 `unavailable`（`@ToolScope` 判定）。**选配置/填条件**两条能力经
  `.../utils/frontend-tools.ts` 的 ui_event/页面 handler 路由转发到与用户点击同一套 store action
  （后端当前只有 2 个 FRONTEND 工具，故这两条路由未走真实帧，见§13 待裁决 2）。

## 4 E4 刷新恢复（AI 历史不丢 + 工作区任务状态正确）

| 项 | 证据 |
|---|---|
| 刷新前 | 截图 `07-ai-turn1-done.png`（4 张工具卡 + 正文） |
| 刷新后（F5） | 截图 `08-after-refresh.png`：**同一会话 `538cdc9e`**，历史消息与工具卡完整恢复 |
| 页签级镜像 | `sessionStorage['ai-mirror:538cdc9e-…']` → `{savedAt, mirrorMessageCount: 39, lastRunId:"7c79e60c-…"}` |
| 后端对账 | `GET /api/ai/history/538cdc9e-…` → `count=39`（ASSISTANT 20 / USER 8 / TOOL 11）——镜像与后端**1:1 一致** |
| 工作区任务状态 | 刷新后 `GET /api/tasks/{id}` 的 `currentStep=EXPORT` 与界面第 3 步一致（本棒新增步骤落库；9-导出-step3 截图 `09-export-step3.png`）；`/import?taskId=5` 刷新后第 2 步（PRECHECK）与库内一致（截图 `32/33`） |
| 挂起态降级 | 后端历史为空时（TTL 过期/重启）镜像保留展示 + 挂起工具卡降级 `expired`（代码路径 `loadHistory`） |

## 5 E5 冲突提示（Q4 异步形态：作业 FAILED + issues）

复现（真实作业，全部经后端 API）：导入→发布（版本 1→3，快照 baseVersion=1）后**再次发布同一任务**：

```
POST /api/tasks/3/jobs {"jobType":"PUBLISH"}        → 201（恒 201，异步受理）
GET  /api/jobs/5                                    → {"jobType":"PUBLISH","status":"FAILED",
                                                       "progress":5,"total":5,"errorCount":5}
GET  /api/jobs/5/issues                             → [{"defCode":"CURRENCY","rowKey":"CNY","severity":"ERROR",
  "message":"发布冲突：行 CNY 在导入后被其他操作修改（快照版本 1，当前版本 3），请重新导入后再发布"}, …5 条]
```

界面（截图 `23-publish-conflict.png`）：导入向导第 4 步底部红色 `el-alert`
「**发布冲突（异步形态）**：发布冲突：行 CNY 在导入后被其他操作修改（快照版本 1，当前版本 3），
请重新导入后再发布」，任务状态 tag 转「已失败」——**前端未把发布当同步请求判错**，
冲突只从作业终态 + issues 文案读出（`task.ts#watchPublishOutcome` + `ImportWizardView#buildPublishConflict`）。

## 6 E6 409 SESSION_BUSY + 一键 reattach

场景：同一页签会话已有一轮挂在确认门（由外部客户端发起，保持运行）→ 面板再次发送。

| 步 | 证据 |
|---|---|
| ① 重复发起 | 面板发送 → `POST /api/ai/chat` → **409**（抓包 `S44c-附件-e6-409-response.json`）：`{"code":"SESSION_BUSY","message":"该会话已有一轮对话在进行中，请先处理或重挂该轮","sessionId":"538cdc9e-…","runId":"7c79e60c-6914-4b42-9ca5-95c7324881ee","degraded":false,"reattach":"/api/ai/events/7c79e60c-…"}` |
| ② 面板提示 | 截图 `27-e6-409.png`：红字「**当前会话正在进行中**」+ 「该会话已有一轮在进行（runId=7c79e60c-…），可一键重挂继续接收这一轮的输出。」+ 「一键重挂收流」按钮；占位助手气泡写明「（本轮未开始：该会话已有一轮正在进行，可一键重挂接收它）」 |
| ③ 一键重挂 | 点「一键重挂收流」→ `GET /api/ai/events/7c79e60c-…` **200 且流保持**（抓包 `completed:false`）；后端 `reattach runId=7c79e60c… status=SUSPENDED 回放帧数=4` |
| ④ 收流 | 截图 `28-e6-reattached-ok.png`：重挂后回放出的 `start/suspended/tool_start/confirm_request` 重建出确认卡（`剩余确认时间 1:53` + 拒绝原因 + 双按钮），面板状态回到「挂起等待中」 |
| ⑤ 收尾 | 在面板点「拒绝」→ `POST /api/ai/confirm {"approved":false}` → 该轮以 `done`（usage 9866 tokens, attempts=1）收尾（`S44c-附件-e6-holding-run-frames.sse` 结尾原文） |

**本棒由此发现的缺陷（已修，§12 缺陷 1）**：`@click="reattach"` 把点击事件当 `runId` 传入，
reattach 打到 `/api/ai/events/%5Bobject%20PointerEvent%5D` → 404 → 面板误报「连接中断」。
修复后重跑上述 6 步全通（抓包中 3 条 404 记录即为修复前的失败痕迹）。

## 7 E7 裁剪性（隐藏 AI 栏，业务全流程可用）

- 收起形态 = 蓝本同款 **48px 竖排条**（`el-aside` width 400px↔48px + `transition-[width]`），
  实测 `getComputedStyle(aside).width` = `48px`，条内文案「AI 助手」；页签级记忆（刷新后仍收起）。
- 收起态下完整跑通：
  - 导出：创建任务 → 选配置 → 下一步×2 → 开始导出 → 结果表格（5 行）→ 下载
    （截图 `29-E7-collapsed-rail.png`、`30-E7-export-done.png`、`31-E7-export-downloaded.png`）；
  - 导入：创建任务 → 选配置 → 预检查 → 导入 → 发布（作业 #7/#8/#9 全 COMPLETED，截图 `33/34/35/36`）。
- 依据：AI 面板与业务之间只隔 `stores/workspace` 窄接口（业务视图不 import ai store；
  `grep -rn "stores/ai" src/views src/components/wizard src/components/TaskWizard.vue` → 0 命中）。
- 说明：文件上传那一步的「本地选文件」由环境限制（见§13 待裁决 4），业务侧其余按钮全部真实点击。

## 8 E8 全中文 UI 抽查

- 模板英文硬编码扫描（`e8-scan.py`：文本节点 + placeholder/title/label/description 属性）→
  仅 1 处命中且为技术标识：`DefinitionsView.vue:8`「主键标志的 JSON 名是 <code>key</code>（不是 isKey）」
  —— 中文句子里嵌字段名，属技术要求（Q3 字段契约）。
- 脚本区面向用户文案扫描（ElMessage/ElMessageBox/notice/placeholder/title）→ 0 处纯英文硬编码。
- `index.html` `lang="zh-CN"`、`<title>配置管理平台</title>`；EP 中文 locale 全程生效
  （分页「共 N 条/条/页」、空态、表单校验均为中文）。
- 逐页截图（任务中心/导出向导/导入向导/配置定义/数据浏览/AI 面板）均为中文；唯一拉丁字面量是
  蓝本原文的快捷键提示「Enter 发送 · Shift+Enter 换行」（键名，蓝本 `AiPanel.vue:101` 同文，1:1 保留）。

## 9 样式纪律自查

```
$ grep -rn "<style" src/            → 只有 AiPanel.vue:504 <style scoped>（另 excel.ts:610 是 xlsx 的 styles.xml 字符串）
$ grep -rc "<style scoped>" src/    → 1 处（AiPanel.vue）
$ scoped CSS 行数（含标签）          → 19 行：2 行标签 + 2 行注释 + 15 行规则
$ 自造 EP 等价物（原生 button/input/table/select/progress/dialog） → 0 命中
$ 静态 style=" 属性                  → 0（仅 SpreadGrid 的 :style="{ height }" 动态高度，蓝本同做法）
```

- 唯一 scoped CSS = 蓝本那**一处**打字机动画（`.typing .dot` + `@keyframes blink`，蓝本
  `AiPanel.vue:186-202`），已注明「Tailwind v3 无 animation/关键帧工具类」。
- 全局 `styles/main.css` 只留最小复位 + `.writing-vertical`（蓝本 `style.css:14-19` 同款，
  Tailwind 无 writing-mode 工具类）；原 `.layout-column` 已改为内联 Tailwind（`min-h-0 overflow-auto`）。
- 组件选型全部 EP：`el-alert`（三类降级/冲突/断流提示）、`el-tag`（四态）、`el-button`、
  `el-input`（textarea）、`el-empty`（图标 + 中文空态）、`el-icon`（@element-plus/icons-vue）；
  工具卡/气泡用 Tailwind 边框与色板（蓝本同款 `#dcdfe6`/`#f4f4f5`/`#d9ecff`/`#409eff`）。

## 10 补丁棒移交项处置

| 项 | 处置 | 证据 |
|---|---|---|
| ⑤ LIKE 等操作符前后端白名单对齐 | `types/condition.ts` 的 `ConditionOperator` 补 `LIKE`（后端 `ConditionEvaluator.matchesOp` 该 case 与 CONTAINS 等价，注释已注明）；`operatorLabel` 补「模糊匹配」。前端表单不主动产出 `LIKE`（STRING/ENUM 仍给 CONTAINS），仅保证**收到的条件**不漏算子 | 后端 `ConditionEvaluator.java` case 列表 ⇄ 前端联合类型逐一核对 |
| ⑦ 数据浏览撤镜像求值器、改用后端 conditions | 删除 `matchRow`/`matchesOperator`/`compareConditionValues`/`rawValue`/`asText`（共 ~110 行）；列表与计数共用 `types/condition.ts#stringifyCondition`；页面删 `visibleRows` 计算属性，表格直接吃服务端分页结果 | 实测（浏览器点击）：请求 `GET /api/data/CURRENCY?…&conditions={"fields":[{"fieldCode":"code","operator":"EQ","value":"CNY"}]}` → 200 `totalElements=1`（`rowKey=CNY`）；口径说明文案改为「列表与行数预估走同一个服务端 conditions 口径（前端不再做本页求值）」（截图 `38-E2-databrowser-conditions.png`） |

## 11 契约联动实现说明（双通路解耦）

| 方向 | 通路 | 实现 |
|---|---|---|
| 工作区 → AI 上下文 | 用户点击 → `workspace.touch(摘要, '界面')` → 版本自增 + 记 `recentActions` + 广播 `workspace_changed` | 面板订阅后显示「工作区已同步：<摘要>（v<n> · 界面）」；`buildContext().extra.recentActions` 随下一轮请求交给模型，发完即清空（不重复累积） |
| AI → 工作区 | `dispatchUiEvent(uiEvent)`（与用户点击同一套 action/API）→ 结果记入动作（source=AI）+ 广播 `ui_event_result` | 面板显示「AI 驱动工作区：<结果>」；`utils/frontend-tools.ts` 的前端工具帧可经该入口驱动导航/选配置/填条件/下载 |
| 页面能力反注册 | `registerPageHandler('export.selectDefs'/'import.startPublish'/…)` | 页面挂载时登记、卸载时注销；AI 侧不 import 任何视图 |
| 版本号 | `WORKSPACE_CONTRACT_VERSION=1` + `dataVersion` 单调自增 | 每轮请求与每次事件都带版本，供两侧兼容判定 |

实测：`recentActions` 请求体原文见 §3.2；面板 chip 见截图 `05-ai-panel-context.png`；
`ui_event_result` 事件（AI→工作区方向）在后端补 ui_event 通道前无真实来源（§13 待裁决 2）。

## 12 本棒发现并处置的缺陷（修复 3 项 / 如实登记 1 项）

| # | 缺陷 | 影响 | 处置 |
|---|---|---|---|
| 1 | 面板「一键重挂」按钮 `@click="reattach"` 把 `PointerEvent` 当 runId 传入 | reattach 打 `/api/ai/events/[object PointerEvent]` → 404 → 面板误报「连接中断，结果可能不完整」（E6 首轮实测复现，抓包 3 条 404） | 模板改 `@click="reattach()"` + 函数与 store 双重类型防御（非字符串一律落回现场 runId）→ 重跑 E6 全通 |
| 2 | `suspended` 帧被当作「挂起等待」 | 后端对**每一批工具调用**都先发 `suspended`（挂起态外置，见 `SpToolCallingManager`），旧逻辑会为 `get_workspace_state` 这类 BACKEND 工具渲染确认卡，并弹出「本轮已挂起外置」噪音提示、输入区被误锁 | 只有帧内出现 `CONFIRM`/`FRONTEND` 条目才置挂起；真正的等待由 `confirm_request`/`frontend_tool_request` 置位（N6 裁决口径不变） |
| 3 | 向导切步不落库 | 刷新后退回首步（后端 `currentStep` 停在 `SELECT_DEFS`），AI 上下文里的步骤与后端不一致（首轮模型自己指出了该矛盾） | 两个向导 `goStep` 增 `PUT /tasks/{id}/step`；并 `watch(workspace.step)` 跟随契约层步骤（AI 驱动 `goto_step` 时界面同步） |
| 4 | 被拒工具卡的终态 tag | `confirm_decision(approved=false)` 先把工具卡置 `已拒绝`，紧随其后的 `tool_result(ok=false)` 又把它覆盖成「执行失败」；结果文本与红字原因仍写明「用户拒绝了该操作。拒绝原因：…」（截图 `22-ai-rejected.png`） | **未改，如实登记**（终态语义属裁决项，见§13 待裁决 3）；改动很小：`tool_result` 遇既有 `rejected` 时不覆盖 |

## 13 遗留【待裁决】

| # | 项 | 现状与影响 | 建议 |
|---|---|---|---|
| 1 | **思考链恒不显示** | 后端 `SseChatEmitter` 无 `reasoning` 帧 → 面板的思考链折叠永不出现（前端已按裁决⑦前向兼容：类型 + 解析 + `v-if` 渲染均就绪，一补帧即生效） | 裁决是否在 `delta` 之外补 `reasoning` 帧（把 `reasoningContent` 从 metadata 透传前端） |
| 2 | **ui_event 通道无真实来源** | 后端无 `ui_event` 帧，`dispatchUiEvent` 与前端工具的 ui_event/页面 handler 路由目前只能由「未来后端帧」触发；本棒以类型 + 代码路径覆盖，未走真实帧 | 裁决：后端是否补 ui_event 帧（AI 主动导航/选配置）或明确「AI 只经后端工具驱动」 |
| 3 | 拒绝后工具卡文案 | 期望「已拒绝」，实测 `tool_result(ok=false)` 把状态翻成「执行失败」（结果文本与原因里明确写了「用户拒绝了该操作」） | 裁决：前端以「先到者为准」（拒绝后不再被 failed 覆盖）还是后端在 `tool_result` 里补 `rejected` 标志 |
| 4 | 文件本地选择能力 | 浏览器扩展未开启「允许访问文件网址」，`upload`/`DOM.setFileInputFiles` 均被拒（`Not allowed`）；本棒为此用 `POST /tasks/{id}/files/upload` 完成导入前置文件落盘（面向用户的路径与端点一致） | 若验收要求「全 GUI 手点上传」，需用户开启该扩展开关，或由验收方准备文件后人工选文件 |
| 5 | 确认门超时置灰未等满 | 倒计时/置灰为代码路径 + 实时倒计时截图（`剩余确认时间 1:46/1:53` 逐步递减），未刻意等待 120s 走完超时分支 | 是否需要一次「等满 120s 观察到时置灰 + 后端 TIMEOUT 收尾」的补充取证 |
| 6 | 行级 `total` 瞬时 100%（b 棒遗留 2） | 本次未见异常（作业 5/5 稳定），维持 b 棒记录 | 维持上级裁决 |
| 7 | CURRENCY 已发布数据版本自增 | 本棒导入+发布两轮后实测终态 `CNY/v5, EUR/v4, GBP/v4, JPY/v4, USD/v4`（**字段值与原种子逐字一致**，仅乐观锁 version 元数据变化）；任务/文件已清理 | 如需严格还原库，请按 b 棒口径用 DB 备份回滚（本棒未找到 main-v2 的库备份） |
| 8 | AI 栏展开态记忆 | 本棒新增页签级记忆（`sessionStorage['ai-panel-expanded']`，蓝本无此项，理由：裁剪性不应被刷新打回） | 若要求严格 1:1 蓝本行为，可去掉该记忆 |

## 14 测试痕迹与清理

- **自产任务**：本棒共创建 5 条 `S44C-*` 任务（#2 `S44C-导出-界面`、#3 `S44C-导入-AI 驱动`、
  #4 `S44C-裁剪-导出`、#5 `S44C-裁剪-导入`、#6 `S44C-编辑器-FE工具`），已全部
  `DELETE /api/tasks/{id}`（级联清作业/条目/文件）；清理后 `GET /api/tasks` → `totalElements: 0`。
- **文件存储**：任务删除级联后 `<MAIN_V2>/backend/data/files/` **为空**（本棒上传/导出件已随任务清除）。
- **环境异动（如实登记）**：11:45:20 出现一条非本棒动作创建的空导出任务（标题「导出任务 2026/10/7 11:45:20」），
  11:45:31 被删除（`操作流水 动作=DELETE_TASK 对象=task#1`）——两条日志均非本棒操作，推测为同机其他人/页签操作；
  对本棒证据无影响（本棒未使用该任务）。
- **数据库**：见§13 待裁决 7（数据值未变，仅版本号自增）。
- **端口/进程**：本棒自起的后端 18318、前端 5202 均已停止，`netstat` 复核 18318/5202 **无 LISTENING**
  （`tasklist` 亦无 java 进程；清理后最后一次复核输出：`端口已释放：18318 / 5202 无 LISTENING`）。
- **浏览器**：本棒页签保留在分组「S4.4c AI 面板与出口验证」（按 webbridge 约定不代为关闭）。
- **仓库**：未执行任何 git 写操作；工作区改动 = §0 的 13 个文件（+ 未入库的 `.env.local`、`dist/`）。

## 15 复现命令（评审核对用）

```bash
# 后端（H2 库在 backend/data/，Redis 需在 6379）
cd <MAIN_V2>/backend && "…/maven3/bin/mvn" -o -s maven-settings.xml package -DskipTests
AI_API_KEY=*** java -jar target/config-mgr.jar --server.port=18318

# 前端
cd <MAIN_V2>/frontend && printf 'VITE_API_BASE=http://localhost:18318\nVITE_DEV_PORT=5202\n' > .env.local
yarn build          # E1：vue-tsc --noEmit && vite build → EXIT=0
yarn dev            # http://localhost:5202

# 证据与产物
<EVID>/S44c-附件-帧序列汇总.txt              # 11 轮真实帧序列
<EVID>/S44c-附件-渐进披露-工具子集实测.txt     # 后端 DEBUG：上下文 ⇄ 披露工具
<EVID>/S44c-附件-e6-409-response.json        # 409 SESSION_BUSY 原文（runId + reattach 路径）
<EVID>/S44c-附件-e6-holding-run-frames.sse    # 挂起轮 SSE 原文（含 done 收尾）
<EVID>/S44c-附件-ai-chat-11-*.request/.response # 前端工具轮请求体 + 帧原文
<SHOTS>/*.png                               # 47 张逐项截图（E2–E8 + 补丁②③）
```

> 附件根目录：`<EVID>` = `<REPO_ROOT>/../s44c-evidence`（本机位于 `<TMP_ROOT>/s44c-evidence`），
> `<SHOTS>` = `<REPO_ROOT>/../s44c-shots`（本机位于 `<TMP_ROOT>/s44c-shots`）
> （截图与抓包原文按本项目既有惯例放在仓库外的临时取证目录，本文件内已内联关键原文）。

---

# 补丁验证（裁决② 前端工具集补齐 / 裁决③ 被拒工具卡终态）

> 执行时间：2026-10-07 12:14–12:32　环境同上（后端 18318 + 前端 5202 + 真实模型 deepseek-flash）
> 追加改动（在 §0 的 13 个文件之内，无新增文件）：
> `utils/frontend-tools.ts`（②动作表）、`views/ExportWizardView.vue` / `views/ImportWizardView.vue`（`*.nextStep` 页面能力）、
> `stores/ai.ts`（③终态 + 前端工具回执不覆盖本地终态）、`components/AiPanel/AiPanel.vue`（③状态色映射）。

## P1 裁决② 后端口径核对（先查后做，不凭蓝本臆造）

后端 `AiTools.java` 共 **12 个 `@Tool`**，其中带 `@ToolChannel(FRONTEND)` 的**只有 2 个**：

```
$ cd <MAIN_V2>/backend && grep -n "@ToolChannel" src/main/java/com/example/configmgr/ai/tools/AiTools.java
235:    @ToolChannel(ToolMeta.Channel.FRONTEND)   →  @Tool(name="open_export_file_editor")  @ToolScope("task:EXPORT/EXPORT")
245:    @ToolChannel(ToolMeta.Channel.FRONTEND)   →  @Tool(name="download_export_file")     @ToolScope("task:EXPORT/EXPORT")

$ grep -n "Registered tool" <后端启动日志>   # ToolRegistry 全量注册（12 条，唯一来源）
open_export_file_editor scopes=[task:EXPORT/EXPORT]      ← FRONTEND
download_export_file    scopes=[task:EXPORT/EXPORT]      ← FRONTEND
list_tasks / get_config_def / check_job_status / list_config_defs / start_export /
start_import / start_publish / get_workspace_state / get_row_count / start_precheck  ← 均为 BACKEND
```

运行期反证：本棒 11 轮真实对话抓包中，`frontend_tool_request` 帧只出现过这 2 个名字
（`open_export_file_editor`、`download_export_file`）；未披露的工具模型根本调不到。

**结论（按后端口径）**：裁决②点名的 `navigate_to` / `select_definitions` / `set_condition` / `confirm_step`
**在 main-v2 后端不存在**（蓝本 `frontend-tools.js` 的 10 个前端工具来自**旧基座**；main-v2 把
`start_export/start_precheck/start_import/start_publish/get_workspace_state/list_config_defs`
全部改成了 BACKEND 通道，即"后端自己执行、不再挂起等前端"）。
因此"前端工具集"的**实做范围 = 这 2 个**，二者在 E3 已双向实测通过（打开在线编辑器 / 触发下载）。

## P2 裁决② 前端动作表补齐（后端一旦披露即零改动生效）

`utils/frontend-tools.ts` 新增 `WORKSPACE_ACTIONS` 动作表 + 别名表 + 路由规则：

| 动作名（裁决②点名） | 中文 | 页面/步骤可用性（第三道防线） | 页面能力 handler（优先） | 契约层后备（ui_event） |
|---|---|---|---|---|
| `navigate_to` | 页面导航 | 任意页 | — | `restore_task`(带 taskId) / `open_page` |
| `select_definitions` | 选择配置项 | export/import + SELECT_DEFS/UPLOAD + 需任务 | `export.selectDefs` / `import.selectDefs` | `select_defs` |
| `set_condition` | 设置查询条件 | export + QUERY_COND + 需任务 | `export.setConditions` | `set_conditions` |
| `confirm_step` | 推进向导步骤 | export/import + 需任务 | `export.nextStep` / `import.nextStep`（**新增**） | `goto_step`(带 step) |

别名（避免命名差异漏路由）：`select_config_defs`→select_definitions、`set_query_conditions`→set_condition、
`goto_step`/`advance_step`→confirm_step、`open_page`/`restore_task`→navigate_to。
三级兜底不变：`available`（handler/契约层完成）→ `degraded`（契约层拒绝，给可行动说明）→
`unavailable`（名字未知 / 参数不全 / 当前页面或步骤不该出现该动作，返回中文原因）。

## P3 裁决② 驱动验证

### P3.1 前端半程 E2E（执行器直呼，逐条真实改变界面）

入口：页面内 `import('/src/utils/frontend-tools.ts')` 直呼 `executeFrontendTool`（与 `frontend_tool_request`
帧走的是**同一个执行器与同一套页面能力**，仅触发源不同；后端不披露这些工具，故无帧可造）。

```
navigate_to({"page":"export","taskId":33})      → ok=true  available :: 已恢复到任务 #33 的向导（导出）
                                                   上下文: {"pageId":"export","step":"SELECT_DEFS","taskId":33}
select_definitions({"codes":["CURRENCY"]})      → ok=true  available :: 已选择配置项：CURRENCY
confirm_step({})                                → ok=true  available :: 已推进到导出向导步骤「查询条件」
                                                   上下文: {"step":"QUERY_COND"}
set_condition({"defCode":"CURRENCY","conditions":{"fields":[{"fieldCode":"code","operator":"EQ","value":"CNY"}]}})
                                                → ok=true  available :: 已设置 CURRENCY 的查询条件：code 等于 CNY
confirm_step({"step":"EXPORT"})                 → ok=true  available :: 已推进到导出向导步骤「导出执行」
select_definitions({"codes":["DOC_TYPE"]})      → ok=false unavailable :: 当前步骤为EXPORT，选择配置项只支持：SELECT_DEFS、UPLOAD。…
unknown_demo_action({})                         → ok=false unavailable :: 未知前端工具 unknown_demo_action：…
```

后端落库对账（真实副作用，非前端自说自话）：

```
$ GET /api/tasks/33
step= EXPORT status= ACTIVE
  item CURRENCY READY cond= {"fields":[{"fieldCode":"code","operator":"EQ","value":"CNY"}]}
```
界面：导出向导第 3 步（选择配置✓ 查询配置✓）+ 已选 CURRENCY（截图 `41-P2-workspace-driven.png`）。

### P3.2 模型半程（真实对话）

- **模型自行驱动（截图 `40-P2-model-driven-export.png`，帧原文 `S44c-附件-p2-model-driven-export.response.sse`）**：
  在任务中心说「帮我导出 CURRENCY 的数据」，本轮 `上下文=page:tasks`
  `披露工具=[list_tasks, get_config_def, list_config_defs, get_workspace_state, get_row_count]`
  → 模型先后调用 5 个工具：`get_config_def`✓、`get_row_count`✓、**`create_task`✗**、**`start_export`✗**、
  `get_workspace_state`✓；失败两张卡的原因是后端原文
  「工具执行失败：No ToolCallback found for tool name: create_task / start_export」
  —— 模型**无法**自行导航/选配置（这些工具后端不披露），只能改口让用户确认入口。
  帧序列：`start → suspended → tool_start → tool_result ×2 → suspended → tool_start → tool_result
  → suspended → tool_start → tool_result → tool_start → tool_result → delta → done`
- **发起导出（工作区已就绪后）**：接着说「工作区已就绪（导出向导第 3 步，已选 CURRENCY 且条件为 code=CNY）。
  请启动导出作业。」→ `上下文=task:EXPORT/EXPORT`，模型调用 `start_export` → 作业 #33
  `EXPORT COMPLETED 5/5 错误0`（截图 `42-P2-model-start-export.png`）。

**结论**：前端半程（导航/选配置/设条件/推进步骤）已完整实现并逐条验证生效；
"一句话让模型跑完这 4 步"**受后端工具面阻塞**（见 P5 移交）。

## P4 裁决③ 被拒工具卡终态区分显示

代码（3 处）：
1. `stores/ai.ts#tool_result`：既有状态为 `rejected` 时**保持** `rejected`（不再被紧随的 `tool_result(ok=false)`
   覆盖成 `failed`）；
2. `stores/ai.ts#frontend_tool_result`（**本棒新发现的次级缺陷**）：后端这帧的 `ok` 表示"回灌已收到"，
   恒为 `true`（帧原文见下），会把前端自己判定的失败改写成"已成功"；现改为**本地已 failed/rejected 则保持原终态**；
3. `AiPanel.vue#toolTagType`：`rejected → warning「已拒绝」`、`failed → danger「执行失败」`、
   `pending → primary「等待确认」`、`succeeded/running → success`、`expired → info`，四类互不混用。

实测（两次真实运行，均为本棒新代码）：

| 终态 | 触发 | DOM 取证（`aside .el-tag`） | 截图 |
|---|---|---|---|
| **REJECTED** | 发布确认卡点「拒绝」+ 填原因 | `el-tag el-tag--warning el-tag--small el-tag--light ⇒ 已拒绝`（同卡另有 `el-tag--warning …plain ⇒ 确认门`） | `43-P3-rejected-warning.png` / `43b-P3-rejected-card.png`（`[确认门] [已拒绝]`，橙色） |
| **FAILED** | 让模型 `open_export_file_editor` 故意缺 `defCode` → 执行器 `ok=false` | `el-tag el-tag--danger el-tag--small el-tag--light ⇒ 执行失败`（同卡 `前端工具` 蓝 tag） | `44-P3-failed-danger.png`（`[前端工具]`+`执行失败`，红色） |

次级缺陷的帧原文（`S44c-附件-p3-frontend-failed.response.sse`）：

```
start → suspended → tool_start → frontend_tool_request {"taskId": 33}
      → frontend_tool_result open_export_file_editor ok=true "参数不合法，…缺少必填参数 defCode（配置定义编码）"
      → delta → done
```
即：**浏览器侧明明失败（ok=false），后端回执却是 ok=true** —— 不修则卡片显示「已成功」（复核前实测如此），
修正后显示「执行失败」（红色，截图 `44` 为修正后重跑）。

## P5 补丁验证的遗留【待裁决】

| # | 项 | 现状与影响 | 建议 |
|---|---|---|---|
| 1 | **② 的"一句话驱动全链"需后端补工具** | 后端当前不披露导航/选配置/设条件/推进步骤工具 → 模型无法自行完成（P3.2 实测） | 后端按 P2 表补 4 个 `@ToolChannel(FRONTEND)` 工具（`navigate_to{page,taskId}`、`select_definitions{codes,mode}`、`set_condition{defCode,conditions}`、`confirm_step{step}`），前端**零改动**即生效（执行器与页面能力已就绪，P3.1 已验证） |
| 2 | 蓝本 10 工具 vs main-v2 12 工具的口径差 | 蓝本把 `start_export/start_check/start_import/start_publish` 视为前端挂起工具；main-v2 改为 BACKEND 直执行（副作用在后端，前端只做展示与导航） | 属 main-v2 既定设计（S4.2/S4.3 后端重写），本棒按后端口径实现，不回溯蓝本 |
| 3 | 历史重建（刷新后）工具卡状态一律 `已成功` | `loadHistory` 对历史 `toolCalls` 统一置 `succeeded`（历史里没有逐调用终态），刷新后看不到"曾经的失败/拒绝" | 裁决：后端 history 端点是否补 toolCalls 的执行结局字段；否则维持现状（正文里仍有失败原因文本） |
