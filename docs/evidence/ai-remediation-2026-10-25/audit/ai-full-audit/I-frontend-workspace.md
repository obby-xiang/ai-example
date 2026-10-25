> 来源=<仓外证据>/ai-full-audit/I-frontend-workspace.md（2026-10-25 归档）· 审计分片/草稿，属历史证据，不代替终稿 `docs/research/排查-AI全链路只读验收审计-终稿-2026-10-25.md`
## 0. 审计口径

- 源码快照：`<仓外证据>/ai-full-audit/tree`（ae883fb + `working.patch`，SHA256 `6c88b47d…b955`）。下文路径均相对该快照根；前端为 `frontend/`，后端仅用于核对**契约事实**（工具名、排序、状态枚举），不审其内部实现。
- 本域边界：AI 面板 ⇄ 业务工作区（定义/数据/任务中心/导出向导/导入向导/设置页）之间的**交互契约**；非 AI 的业务内部流程（Excel 解析、发布业务规则、数据查询语义）不审。
- 本域前提：后端按 Redis Cluster + 共享 DB/文件 + ≥3 JVM、同一对话请求可无粘滞落不同实例；前端须覆盖多页签并行与多用户隔离。**注意：本快照的前端测试拓扑不满足该前提**（见 §5）。
- 本审计**未运行**测试（快照无 `node_modules`，装依赖属修改环境）；所有"假绿/水分"判断均基于读测试代码与其断言对象，不基于执行结果。
- "环境"在我理解为 `/settings/ai`（AI 提供方/模型/默认参数配置）与 `/settings/ai-feedback`；工作区五页（tasks/definitions/data/export/import）另计。

## 1. 覆盖清单

### 1.1 链路 × 生产文件 × 相邻测试

| # | 链路 | 生产文件（关键行） | 相邻测试 | 驱动层级 |
|---|---|---|---|---|
| C1 | 对话面板流式/分段/终帧/停止/断流恢复 | `src/api/sse.ts:154-303,359-454,475-545,577-751`；`src/stores/ai.ts:2481-2645,2773-2851,4339-4464,4862-5226`；`src/utils/parts.ts:45-104,499-871` | `tests/parts-reducer.spec.ts:380-430`；`tests/parts-double-write.spec.ts:67-306`；`tests/sse-parser-boundary.spec.ts:417-430`；`tests/sse-watchdog.spec.ts`；`tests/aie2e-stream.spec.ts`；`tests/aie2e-stop.spec.ts`；`tests/concurrency-matrix.spec.ts:118-216`（真挂 AiPanel 点按钮）；`tests/rg-sse-abort.spec.ts`；`tests/r2-midstream-refresh.spec.ts`；`tests/park-request-tool-start-order.spec.ts`（新增，纯函数）；`e2e/01-stream`、`02-reload-stop`、`07-empty-error`、`14a-mdxy-md` | 纯函数 / store+桩 / 真面板 jsdom / 真机 E2E（单 JVM，桩上游模型） |
| C2 | HITL 确认门（批准/拒绝/勾选/防重/409 校正/多卡） | `src/stores/ai.ts:221-250,2853-3151`；`src/utils/parts.ts:639-720`；`src/components/AiPanel/AiPanel.vue:3463-3531` | `tests/gate-matrix.spec.ts:155-188`；`tests/r166-gate-link.spec.ts:217-300`；`tests/r166b-fixes.spec.ts:443-480`；`tests/parts-residue-cleanup.spec.ts:205-230`；`tests/irreversible-lifecycle.spec.ts`；`tests/confirm-irreversible.spec.ts`；`tests/b1-suspend-disconnect.spec.ts`；`e2e/03-hitl`、`17-unlock*` | store+桩 / 真面板 / 真机 E2E |
| C3 | 生成式表单（提交/取消/408?/超限/草稿/他端已决） | `src/stores/ai.ts:3164-3273,3300-3360,4191-4330`；`src/components/AiPanel/FormRenderer.vue:219-234,459,789` | `tests/gf-renderer.spec.ts`；`tests/gf-schema-rules.spec.ts`；`tests/gf-draft.spec.ts`；`tests/form-result-limit.spec.ts`；`tests/frontend-tool-result-terminal.spec.ts`；`e2e/04-form` | 组件 / store+桩 / 真机 E2E |
| C4 | 表格写预览 → 应用 → 撤销（版本失效/身份闸/指纹） | `src/stores/ai.ts:115-150,3371-3442,3803-4145`；`src/utils/sheet-tools.ts:716-733,1428-1500,2573-2620,2804-2806,2850-2911`；`src/components/SpreadGrid.vue:546-700`；`src/components/AiPanel/SheetPreviewCard.vue:1-221` | `tests/sheet-preview-apply-undo.spec.ts`（假表）；`tests/sheet-preview-identity.spec.ts`；`tests/sheet-preview-cleanup.spec.ts`；`tests/sheet-preview-highlight.spec.ts`；`tests/sheet-applied-summary-identity.spec.ts`；`tests/sheet-undo-confirm.spec.ts`；`tests/sheet-tools-*.spec.ts`；`e2e/06-sheet`、`12b-vl-tol` | 假适配器 / store+桩 / 真机 E2E（真 SpreadJS 只在 E2E） |
| C5 | AI 启动作业 → 页面绑作业 → 作业进度（Task SSE + 轮询兜底 + 看门狗） | `src/stores/ai.ts:812-816,5326-5382`；`src/utils/job-watch.ts:73-252`；`src/utils/polling.ts:46-247`；`src/api/tasks.ts:103-134`；`src/views/ImportWizardView.vue:1054-1115,1288-1311`；`src/views/ExportWizardView.vue:790-834,1387-1407`；`src/components/JobProgress.vue` | `tests/job-watch-channel.spec.ts:174-447`（EventSource 桩）；`tests/import-watch-recycle.spec.ts:61-220`（**自建同构 wiring，不挂视图**）；`tests/ai-start-job-sync.spec.ts`（挂 ImportWizardView，API 全桩）；`tests/export-wizard-step-outcome.spec.ts`；`tests/polling-controller.spec.ts`；`tests/polling-predicate.spec.ts`；`e2e/11a/11b-hgta`、`16-kqc*` | 桩 / 视图+桩 / 真机 E2E |
| C6 | 发布确认（REPLACE 复选框 / MERGE 免确认 / 权威模式 / fail-closed） | `src/views/ImportWizardView.vue:994-1052`；`src/stores/workspace.ts:523-569`；`src/utils/publish-mode.ts`；`src/utils/confirm-irreversible.ts` | `tests/import-publish-confirm.spec.ts:1-572`；`tests/publish-mode.spec.ts`；`tests/workspace-run-job-publish-confirm.spec.ts:1-174`（**只测不可达分支**，见 I-11）；`tests/gate-matrix.spec.ts:155-188`；`e2e/15-mdo`（发布夹具） | 视图+桩 / dispatch 直调+桩 / 真机 E2E |
| C7 | 发布冲突异步落点 → AI 工具卡 + 回执 | `src/stores/task.ts:103-110,285-376`；`src/stores/ai.ts:5326-5435`；`src/utils/publish-links.ts` | `tests/watch-publish-outcome.spec.ts:115-303`；`tests/publish-conflict-link.spec.ts`；`tests/publish-conflict-resultview.spec.ts`；`tests/publish-conflict-link.spec.ts` | store+HTTP 桩 + EventSource 桩 |
| C8 | 文件下载（1 码 xlsx / ≥2 码 zip / 即发即回 / 超时文案） | `src/views/ExportWizardView.vue:967-1170`；`src/utils/frontend-tools.ts:495-560`；`src/stores/workspace.ts:591-611`（`download` 分支，不可达） | `tests/export-wizard-batch-download.spec.ts`；`tests/excel-zip.spec.ts`；`e2e/12a-vl-vis`、`12b-vl-tol` | 视图+桩 / 真机 E2E（真下载） |
| C9 | 会话身份 / 多页签所有权 / 会话销毁 / 刷新恢复 | `src/stores/ai.ts:94-200,288-620,637-790,1659-1750,2020-2472,4494-4805`；`src/components/AiPanel/AiPanel.vue:3620-3690` | `tests/tab-ownership-lifecycle.spec.ts`（FakeLockManager/BroadcastChannel）；`tests/session-purge-reset.spec.ts`；`tests/session-purged-recovery.spec.ts`；`tests/context-restore.spec.ts`；`tests/restore-*.spec.ts`；`e2e/08-session`、`13-nfezui`、`13b/c/d`、`15-mdo`、`10-srp` | 桩 / 真机 E2E（真 Web Locks、真复制页签） |
| C10 | 工作区契约（context / 页面能力 / ui_event / 只读闸） | `src/stores/workspace.ts:142-320,322-374,397-639`；`src/utils/frontend-tools.ts:575-704,844-1120,1290-1340`；五页 `onMounted` / `onBeforeUnmount` | `tests/workspace-audience.spec.ts:1-84`；`tests/workspace-run-job-publish-confirm.spec.ts`；`tests/gate-matrix.spec.ts:155-188`；`tests/ai-tool-receipt.spec.ts`；`tests/tool-outcome-envelope.spec.ts`；`tests/goto-step-readonly-guard.spec.ts`；`e2e/05-visop`、`11a/11c` | dispatch 直调 / 视图+桩 / 真机 E2E |
| C11 | 设置页（AI 环境/模型）与管理面 | `src/views/AiSettingsView.vue`（834 行，**不 touch workspace / 不刷新面板 health**）；`src/api/aiConfig.ts`；`src/views/AiFeedbackView.vue` | `tests/ai-settings-view.spec.ts`（仅本页 UI/校验） | 组件+桩；**无跨页一致性测试** |

### 1.2 本域读过但**未**发现 AI 交互的文件（明确列出，未静默略过）

`src/views/DefinitionsView.vue`（498 行，无 region / 无 page handler / 不订阅 workspace 事件，只有 `enterPage` 与 `loadDefs`）、`src/views/DataBrowserView.vue`（370 行，同上，仅 `enterPage` 与计数/查询）、`src/components/TaskWizard.vue`（展示壳）、`src/components/wizard/*`、`src/components/AiPanel/ContextPill.vue`、`ContextPopover.vue`、`UploadHandoffCard.vue`、`resultview/*`、`src/utils/markdown.ts`、`src/utils/ai-suggestions.ts`、`src/utils/publish-mode.ts`（纯解析，已由 `publish-mode.spec.ts` 覆盖）。`src/utils/ai-regions.ts` / `ai-dom-tools.ts` 属"AI 界面原语"域，本报告只审其与工作区的边界（ref 生命周期 / epoch / 只读），不审原语内部实现。

### 1.3 明确未覆盖的文件与分支（详见 §5）

- 未覆盖文件：`src/views/AiSettingsView.vue`（与工作区的交互面）、`src/views/AiFeedbackView.vue`（同上）、`src/utils/publish-links.ts`（有单测，无跨页/跨实例测试）、`src/components/AiPanel/UploadHandoffCard.vue`（仅 `tests/ai-upload-handoff.spec.ts` 覆盖逻辑面）。
- 未覆盖分支：`workspace.ts` 的 `open_page/export_started/import_batch/import_action/download/refresh_defs/refresh_data/refresh_tasks/run_job/cancel_job/set_import_mode` 这些 ui_event 分支在**当前后端工具面上无模型可达入口**（后端 34 个 `@Tool` 里没有同名者，见 I-11）；`ImportWizardView` 的 `startJob` 对 `PUBLISH` 的分支无独立测试；`job-watch.ts` 的 `pollIntervalMs`/`frameIdleWatchdogMs` 注入面只有单测；`SpreadGrid` 的 `loadGeneration` 递增路径只在 E2E（`12b`）间接覆盖。

## 2. 逐链分析

统一记号：**入口 → 身份 → 共享状态/外部调用 → 正常/异常 → 重连/取消/恢复 → 用户可见结果**。

### C1 对话面板流式链

- **入口**：`AiPanel.send-btn`（`AiPanel.vue:963-967`）→ `ai.send()`（`ai.ts:2481`）；或刷新后 `onMounted → loadHistory → restoreSuspendedSnapshot → restoreInFlightRound/resumeSnapshotStream`（`AiPanel.vue:3620-3653`，`ai.ts:2074-2155,2169-2202,2384-2445`）。
- **身份**：`sessionId`（`sessionStorage['ai-session-id']`，`:94`）+ Web Lock `ai-session-<sid>`（`:290,434-469`）+ 轮次 `runId`（`start` 帧）+ `roundEpoch`（`:163,5456`）+ 宿主消息 `activeRoundHostId`（`:152`）。多页签：复制页签被 Web Locks 判 `duplicate` → `adoptFreshSession()` 换新 id（`:4606-4617`），不接管原会话；原页签关闭后空白副本经 1.5s 宽限再探锁才接手（`:4679-4750`）。
- **共享状态/外部调用**：`POST /api/ai/chat`（fetch+SSE，`sse.ts:475`）、`GET /api/ai/events/{runId}?lastSeq=`（EventSource，`:577`）、`POST /api/ai/cancel/{runId}`、`GET /api/ai/runs/current`、`POST /api/ai/history`、sessionStorage 镜像（节流 300ms + pagehide flush，`ai.ts:1483-1550`）。
- **正常分支**：`start→message_start→delta*→tool_start→tool_result→done`；`seq` 幂等（`ai.ts:4933`，`parts.ts:859-870`）、终帧闸门（`sse.ts:270-272`）、异 `runId` 帧丢弃（`:282-285`）、`stopped` 后非终帧丢弃（`ai.ts:4877`）、跨宿主已决 `toolCallId` 不重建段（`:4943-4948`）。
- **异常分支**：非 2xx → `ApiError` + 按码文案（`:2649-2756`，含 409/429/503/池饱和/无 key/无模型/Redis 不可用）；断流（无终帧）→ `streamInterrupted` + 提示 + `reattach`（最多 4 次，1/2/4/8s ±25%，`sse.ts:39-57,676-706`），建连看门狗 15s（`:73,659-674`），帧间静默 45s（`:31`）。
- **重连/取消/恢复**：`stop()`（`ai.ts:4339`）本地先收敛（段→cancelled、`stopped`、镜像即刻落盘），再 best-effort `POST cancel`；挂起即断（`sse.ts:141-144` + `ai.ts:2541-2546`）视为正常收尾，不自动重连；决策后由 `reconnectSuspendedRun` 带 `lastSeq` 续收（`:2461`）。
- **用户可见结果**：气泡分段渲染、工具卡四态、断流提示、停止标记、"继续"入口（`lastRoundOutcome`）。
- **本域结论**：C1 的帧序/去重/终态闸门**可证明正确（带条件：后端 seq 单调且不复用 toolCallId；归档回放按序）**，有纯函数与 store 级测试 + 双 JVM E2E 佐证；**残留缺口见 I-07（tool_start 对已终态段无保护）与 I-03（409 后不续流）**。

### C2 HITL 确认门

- **入口**：卡面"批准/拒绝"（`AiPanel.vue:3528`）→ `ai.confirm(approved, reason, toolCallId)`（`ai.ts:3034`）。刷新恢复路径：`runs/current.pendingEntries` → `rebuildPendingCard`（`:2270`）→ 多卡串行 `restoredPendingQueue`（`:1304,2358`）。
- **身份**：`activeRunId`（**注意**：仅由 `start` 帧 / 恢复路径写入；`reattachActive` 不写它，见 `:2773-2851`）+ `toolCallId` 逐卡 + `gateSubmissions`/`gateAcks`（按 toolCallId）。
- **共享状态/外部调用**：`POST /api/ai/confirm`（`runId+toolCallId+approved`）；`GET /api/ai/runs/current`（409 后 3s 校正）；段状态 + 派生投影（`parts.ts:156-221`）。
- **正常分支**：点击即入 `gateSubmissions`（`:3088`，双按钮连点短路）→ 乐观段（approved→running / rejected）→ POST 成功 → 清勾选 → **带 lastSeq 重连**（`:3114`）→ 补下一张卡 → 多卡未决时保持 `suspended`。
- **异常分支**：409 → 不写乐观裁决 → `syncing` 瞬态 → 3s 后 `syncGateFromAuthority`；POST 其它失败 → 段回 pending + 允许重试；不可撤销卡未勾选 → 拒绝批准（`:3084`）。
- **重连/取消/恢复**：`confirm_decision` 帧落终态 + `clearSuspendedIfNoOtherConfirms`；停止时 `clearGateSubmissions/Acks`。
- **用户可见结果**：卡面五态（待确认/已批准/已拒绝/已取消/已超时）+ 只读回执。
- **结论**：防重与"不伪造裁决"可证明正确；**409 后不续流 = 已证实的可达死角（I-03）**。

### C3 生成式表单

- **入口**：`frontend_tool_request(name=generative_form)` → `openGenerativeForm`（`:3300`）→ 面板 `FormRenderer` 提交/取消。
- **身份**：表单捕获创建时 `runId`（`:3311`），回灌优先用它（`:3187`），避免错配到新轮。
- **正常/异常**：客户端 schema 复核 + 8000 字预检（`:4204-4216`，超限零请求）；400 `FORM_RESULT_REJECTED` → 字段级错误可改可重试；`FRONTEND_RESULT_TOO_LARGE` → 可缩短重试；409 → `applyFrontendToolTerminal` 收敛但不写值。
- **恢复**：刷新后 `rebuildPendingCard` 重建表单 + 本机草稿（`loadFormDraft`），无本地超时。
- **用户可见结果**：表单卡、提交中、"已被他端提交"提示。
- **结论**：正常路径可证明正确；**409 分支之后同样不续流，且 `suspended` 不被清除**（`applyFrontendToolTerminal` 只改段，`ai.ts:5271-5296`）→ 输入框被 `composerHardDisabled` 锁住（`AiPanel.vue:1580-1586`），只剩"停止"一条出路（I-03 同族）。

### C4 写预览 → 应用 → 撤销

- **入口**：`frontend_tool_request(needsSheetPreview)` → `openWritePreview`（`:3371`）；恢复路径无本地 plan → `stalePreviewToolCallId`（`:2305`）→ 卡面"预览已失效 + 放弃"。
- **身份（版本失效核心）**：`previewId = f(toolCallId, instanceId, loadGeneration, scope)`（`sheet-tools.ts:1479-1497`）；`instanceId`/`loadGeneration` 来自 `SpreadGrid.vue:682-683`；`plan.sheetVer = workspace.dataVersion`（`ExportWizardView.vue:1525`、`ImportWizardView.vue:1342`）。
- **共享状态/外部调用**：只读 `sheet.current` handler（两向导各注册一份）→ 纯函数计划（`planSheetWithLevel`）→ 应用时 `applySheetPlan`（`sheet-tools.ts:2573`）+ 行指纹复核 → `writeCells`（屏幕 + 表格数据）→ `POST /frontend-tool-result`。
- **正常/异常**：计数档无明细 → 必须先物化（`:3925-3945`，且沿用**预览时**的指纹，避免自绕闸门）；`defCode` 变 / 身份变 / 指纹变 → 整批拒绝并如实回灌；大批量需勾选确认（`:3893`，卡面 `SheetPreviewCard.vue:112-130`）。
- **重连/取消/恢复**：取消 → `cancelled=true` 回灌；换选区/换标签/离开页面/换会话/停止 → `dropSheetPreview`（`:4043-4109`，只有 `scope=selection` 的计划会被选区变化误伤保护）；程序化重载 → `onSheetReloaded`（`:4095`）+ 硬闸 `previewId`。
- **用户可见结果**：预览色、已应用色可撤销（深度 3）、"AI 改动 N 格"摘要。
- **结论**：**旧预览覆盖新编辑的主闸门可证明正确（带条件）**：同一 `previewId` 下只对**主键列**做行指纹（`sheet-tools.ts:716-733,2590-2601`）；用户对同一行的**非主键格**手改不会被指纹发现，应用会覆盖之——这是设计上已登记的口径（卡面固定提示"同文件后写可覆盖"，`SheetPreviewCard.vue:27-30`），不判为缺陷，但必须在报告里明示。

### C5 作业启动 → 页面绑定 → 进度（Task SSE）

- **入口**：AI 侧 `start_export/start_precheck/start_import`（后端 DANGER，先过 C2 确认门）→ `tool_result` 文本"作业 #N" → `armStartJobSync`（`ai.ts:5360`）→ 页面 handler `export.syncJob`/`import.syncJob`（`ImportWizardView.vue:1288`，`ExportWizardView.vue:1387`，先校验 `taskId` 一致）→ `resumeJobs()` → `watchJob`。
- **身份**：`taskId`（页面 ref + workspace）+ `jobId` + 注册表 key `kind`（导入侧三 kind 可并存）+ `watchingJobId`（发布侧单槽，见 I-05）。
- **共享状态/外部调用**：`GET /api/tasks/{id}/events`（EventSource，无 auth 头、无 lastEventId）；`GET /api/jobs/{jobId}`（权威快照）；`GET /api/jobs/{id}/issues`；`GET /api/tasks/{id}`（终态后刷任务）。
- **正常分支**：SSE `JOB_PROGRESS` 直接改进度；`JOB_DONE` 触发一次快照；终态以**快照**为准（`job-watch.ts:220-224`）。
- **异常分支**：`onerror` 立即降级 3s 轮询（`:181-187`）；90s 无帧看门狗降级（`:125-131`，复位源只有帧与 onopen，轮询不复位 = 防饿死）；在途 `getJob` 撞车 → dirty flag 补发恰好一次（`:202-235`）；`disposed` 后迟到结果不写回不回调（`:214-233`）。
- **取消/终态**：终态自 dispose（清定时器/关 EventSource/`onDisposed` 摘柄）；页面卸载全释放（`ImportWizardView.vue:1612-1628`、`ExportWizardView.vue:1710`）；发布侧另有 300s 墙钟（`task.ts:333-337`）。
- **用户可见结果**：`JobProgress`（状态标签、行级进度、通道提示"实时/已降级"、问题明细、只读时隐藏取消）。
- **结论**：单任务内的进度链**可证明正确（带条件：`GET /jobs/{id}` 在任意实例读到的都是权威终态）**。**跨任务切换（I-02）、AI 发起的发布不绑页（I-04）、并发发布冲突丢弃（I-05）、终态不回模型（I-06）** 是本链的缺口。跨实例前提**完全未被前端 E2E 复现**。

### C6 发布确认

- **入口**：页面按钮 `startPublish()`（`ImportWizardView.vue:1001`）/ handler `import.startPublish`（`:1460`）/ 遗留 `run_job`（`workspace.ts:523`，不可达）。
- **身份/权威**：模式**只认服务端** `resolvePublishMode(taskId)`（`publish-mode.ts`），读取失败或不可解析 → fail-closed 不建作业；回写 `workspace.importMode` 但不记假"界面动作"。
- **防重**：页面 `publishPending`（`:1008-1012`）先于任何 await；`run_job` 分支**无**此闸（不可达，登记）。
- **正常/异常**：REPLACE → 复选框确认（不可逆，`confirm-irreversible.ts`）；MERGE → 免确认；取消 → 零 createJob 且不清既有冲突提示（`:1038-1042`）。
- **用户可见结果**：`ElMessage` 启动提示 + 作业面板（若已绑定）+ 冲突 Alert（页面侧）/ 冲突结果卡（AI 侧）。
- **结论**：**fail-closed 与"同一套确认语义"可证明正确（有测试+真机 E2E 夹具）**；但"AI 起动 → 页面可见"缺一半（I-04）。

### C7 发布冲突异步落点

- **入口**：`tool_result(start_publish)` → `armPublishConflictWatch`（`ai.ts:5326`）从结果文本 `/作业 #(\d+)/` 取号，从段 `args.taskId` 取任务号 → `watchPublishOutcome`（`task.ts:285`）。
- **正常/异常**：终态 FAILED → 拉 issues（最多 50）→ 摘要（首行"共 N 处"，明细前 5）→ 双通道：`ElMessage`（用户）+ `attachPublishConflict`（AI 卡 `reason` + `resultView` 表格）+ `reportUiEventResult`。
- **闸门**：`publishWatchers` 同 key 不双开；`watchingJobId` 只认最后一次发起的跟踪（`:352`）。
- **用户可见结果**：AI 工具卡变红底原因 + 表格；面板"AI 驱动工作区"回执条。
- **结论**：单次发布可证明正确；**并发两任务时静默丢弃（I-05）**；**正则耦合后端中文文案（I-06 附）**；"回灌模型"实为下一轮上下文（I-06）。

### C8 文件下载

- **入口**：`download_export_files`（前端执行器）→ `export.download` handler → `startBatchDownloadInBackground`/`downloadOne`；`ui_event download` 分支（`workspace.ts:591`，不可达）。
- **正常**：1 码 → `buildXlsx`（优先用在线编辑后的表格 `toXlsxBlob`，否则后端文件）→ `downloadBlob`；≥2 码 → zip。
- **异常**：前置校验 30s 收口（`frontend-tools.ts:522`）+ 外层 60s 预算（`download` 类超时文案明示"可能仍在后台，请勿重复触发"）；`inFlightDownloads` 防重。
- **结论**：可证明正确（带条件：`exportResults`/`gridRefs` 与当前任务一致）。**换任务竞态下 zip 名与内容可能来自不同任务（I-08）**。

### C9 会话身份 / 多页签 / 恢复

- **入口**：面板挂载 `ensureTabOwnership()`；`pagehide/pageshow`；`new-chat`；`410 SESSION_PURGED`。
- **身份**：pageId（sessionStorage）+ Web Locks + BroadcastChannel + 返回意向（60s TTL）。
- **正常/异常**：owner / duplicate / unsupported；复制页签换新会话并轮换 pageId；bfcache 恢复只降级不清屏；慢重载凭返回意向不清屏。
- **恢复**：`restoreSuspendedSnapshot` 入口自持 sid 与 `currentAbort` 身份，await 后二次对账（`:2086`）；`runs/current` 不可用 → 降级为镜像/历史。
- **多用户隔离**：**前端没有任何用户身份概念**（`src/api/http.ts` 无 Authorization/token/user 读点）；隔离完全由后端承担，本域只能证明"浏览器内不同页签互不串会话"。
- **结论**：页签隔离可证明正确（带条件：浏览器支持 Web Locks；不支持的旧环境降级为"共享会话 + 409 兜底"）；**多用户隔离在本域未验证**。

### C10 工作区契约

- **入口**：AI 工具 → `dispatchFrontendTool` 四级路由（注册工具 → 工作区动作表 → ui_event → 页面 handler）→ `dispatchUiEvent`。
- **身份**：`workspace.buildContext()` 严格 5 键（`page/taskType/step/taskId/extra`），`contextKey` 决定工具披露子集（`:112-120`）。
- **正常**：每个 ui_event 落到与用户点击同一 API/action（`goto_step→tasksApi.goToStep` 等）。
- **异常**：只读闸（`CANCELLED` 恒只读；`COMPLETED` 需按类型复核最终作业，`:419-436`）；`run_job` 发布前 fail-closed 读模式。
- **用户可见结果**：面板 chip"工作区已同步"、`ui_event_result` 回执条、AI 卡回执。
- **结论**：**关键缺口 I-01（设置页不让上下文失效）**；I-09（失败也显示"已同步"）；`refresh_defs/refresh_data` 是**诚实的空操作**（`:612-626`，无订阅者），模型被如实告知"数据不会变"——属已登记限制，不是谎报。

### C11 设置页（AI 环境）

- **入口**：`/settings/ai` 保存 provider/model/defaults（`AiSettingsView.vue:287-307,476-543`）。
- **外部调用**：`aiConfigApi`。
- **与工作区/面板的关系**：**没有**——不 `enterPage`、不 `probeHealth`、不发事件。面板的 `health.model` 只在挂载（`AiPanel.vue:3628`）与特定错误码后刷新（`ai.ts:2718-2736`）。
- **结论**：见 I-01（陈旧上下文）与 I-10（面板模型标签滞后）。

## 3. 测试可信度

| 类型 | 代表 | 能证明什么 | 不能证明什么 |
|---|---|---|---|
| 纯函数/状态机单测 | `parts-reducer`、`parts-double-write`、`park-request-tool-start-order`、`sse-parser-boundary`、`sheet-tools-*` | 段状态机、seq 幂等、帧解析边界（真实算法） | 不触 store/面板/网络，不能当"链路可用" |
| store + API 桩 | `b1-suspend-disconnect`、`r166-*`、`restore-*`、`concurrency-matrix`（后两例真挂面板） | 决策/重连/恢复的编排与竞态（桩只替网络，断言落在 store 状态与 DOM） | 不证明后端契约、不证明跨实例 |
| 组件挂载 + 全桩 | `ai-start-job-sync`、`export-wizard-step-outcome`、`import-publish-confirm`、`ai-settings-view` | 页面 handler 的**真实视图代码路径**与回灌文案 | API 全桩 ⇒ 不证明真实数据流 |
| dispatch 直调 | `workspace-run-job-publish-confirm`、`gate-matrix:155-188` | 契约层分支与零副作用 | **直调 `dispatchUiEvent`，不经过任何工具/面板**；且其中 `run_job/cancel_job/set_import_mode/download/refresh_*` 在**当前后端工具面上无模型入口**（I-11）⇒ 属"绿色但不可达" |
| 自建同构 wiring | `import-watch-recycle:61-73` | `watchJob` 的回收/dispose 契约 | **不挂 ImportWizardView**，测的是测试自己写的 3 行接线，**不覆盖换任务**（I-02 正落在这个盲区） |
| 假表适配器 | `sheet-preview-apply-undo`、`sheet-preview-identity` | 预览→应用→撤销跨层闭环（真 store + 假 SheetAdapter） | 不证明 SpreadJS 实例身份/`loadGeneration` 真实变化（只在 E2E） |
| EventSource 桩 + 假时钟 | `job-watch-channel`、`watch-publish-outcome` | 看门狗/降级/墙钟/在途补发的时序（断言为真实定时器行为） | 不证明后端帧真实形态；**`watch-publish-outcome:255-289` 把"旧作业结局被丢弃"锁成预期**，是"绿测试固化缺陷面"的典型 |
| 真机 E2E | `e2e/01..18`；`15-mdo` 双 JVM | 真实前后端 + 真实 Web Locks + 真复制页签 + 真下载 + 跨实例 SSE 扇出（seq 全等、单终帧） | 模型是**桩上游**（`harness.ts:63`，除非 `E2E_MODE=real`）；**只有 2 个 JVM 且 H2 各自独立、Redis 单库非 Cluster**；作业/下载/共享文件从未跨实例验证 |

**是否存在"只断言 mock/状态 200/自造答案/假绿/时序水分"**（逐项给结论）：
1. 只断言 mock：`workspace-run-job-publish-confirm`、`ai-start-job-sync`、`import-publish-confirm` 的断言对象是 `vi.fn()` 的调用参数与回灌文案——**但它们至少走真实 store/视图代码**，属"契约单测"，不能算生产验证；**真正的 mock-only** 是 `polling-predicate.spec.ts` 一类（测 3 行接线）。
2. 只断言状态 200：E2E `15-mdo` 的 `confirmOn` 断言了 `accepted/duplicate/storedBy/resumeOutcome`，**没有**只看 200，合格。
3. 测试自己造答案：`import-watch-recycle` 的 `beginDirect/disposeAllDirect`（`:61-73`）是测试自建的"与视图同构"接线——它证明的是 `watchJob` 本身，不证明视图接线正确（I-02 正是视图接线缺口）。
4. 假绿/跳过：`e2e/16-kqc*.spec.ts:41/61/68`、`e2e/17-unlock.spec.ts:677`、`e2e/10-srp.spec.ts:164` 有**条件/静态 skip**；`e2e/00-harness:30` 在真模型模式跳过桩——这些都用例名与原因标注，属显式跳过，不算隐瞒；但**必须知道它们默认不跑**。
5. 时序水分：`sse-watchdog` 用假时钟断言退避序列（合格）；`job-watch-channel` 的看门狗阈值可注入（合格）；**风险点**是 `ai-panel-scroll-follow` 之类"等两帧再断言"的写法（与业务状态无关，影响小）。
6. "把单测当生产验证"：本域最突出的是 §I-11 的两份 dispatch 直调测试，以及 I-05 中被测试固化的丢弃行为。

## 4. 发现清单

> 级别：块 Critical 未出现；以下最高为 Important。确定性三档：**已证实缺陷** / **具体风险** / **仅缺测试**。

### I-01 设置页使工作区上下文陈旧，AI 仍可写入"看不见"的任务（Important｜已证实缺陷）

- **位置**：`frontend/src/router/index.ts:54-66`（`/settings/ai`、`/settings/ai-feedback` 只有 `meta`，无 workspace 钩子）；`frontend/src/views/AiSettingsView.vue:20-25`（不 import workspace）；`frontend/src/App.vue:41`（AiPanel 全局常驻）；`frontend/src/stores/workspace.ts:471-509`（`goto_step/select_defs/set_conditions` 只校验 `workspace.taskId` 与只读，**不校验当前真实页**）。
- **触发步骤**：① 打开导入向导 → 任务 #7 第 4 步（`workspace.page=import/taskType=IMPORT/step=PUBLISH/taskId=7`）；② 点顶部导航"AI 设置"（组件卸载、`registerPageHandler` 全部注销，但无人 `enterPage`）；③ 观察面板上下文 chip 仍是"导入向导 · 任务 #7 · 发布"，页脚 `contextKey` 仍是 `task:IMPORT/PUBLISH`；④ 对 AI 说"把它改成整体替换 / 推进到下一步"→ `ui_event set_import_mode|goto_step` 直发 `PUT /tasks/7/...` 成功。
- **用户影响**：模型被披露成"仍在向导里"（工具子集错），并可在用户已经离开的页面上改任务状态；面板显示的工作区与用户所见不一致。
- **对应测试**：无。`tests/ai-settings-view.spec.ts` 只测设置页自身表单；`tests/workspace-audience.spec.ts` 只测 chip 文案。
- **缺的测试**：挂 App + 两页，断言进入 settings 后 `workspace.contextKey` 退化为 `page:settings`（或显式 `*`）且 `dispatchUiEvent` 的写分支被拒。

### I-02 导入向导同路由换任务：作业跟踪与作业位不回收（Important｜已证实缺陷）

- **位置**：`frontend/src/views/ImportWizardView.vue:1665-1679`（route.query 变化只做 `onSheetPageLeft + setTaskId + loadTask`）、`:335-343`（三个 `*Job` ref、三个 issues/channel ref 均无重置点）、`:541-578`（`resumeJobs` 只对**新任务存在的**作业 `attachJob`，缺席的 kind 原样保留旧 watcher 与旧值）、`:1043-1115`（`beginWatchJob` 只按 kind 回收，无任务维度）、`:1086-1111`（`onFinal` 无 `job.taskId === taskId.value` 校验，却调 `refreshTaskSnapshot()` 写**新**任务）；对照 `ExportWizardView.vue:535-541`（`stopJobWatchForTaskSwitch`）、`:563-567`（改绑首行调用）。文件自身注释 `:359-362` 已把导出侧视为蓝本，但导入侧未实现。
- **触发步骤**：① 在导入向导对任务 A 发起导入/发布（作业在跑或已终态）；② 不离开本路由，让 `taskId` 变化——AI `navigate_to(import, taskId=B)`（`restore_task` → `router.push` 复用组件），或页内"创建并绑定任务"（`:1630-1648`）后再切回；③ 任务 B 若没有 PUBLISH 作业，`publishJob` 仍是 A 的终态。
- **用户影响**：a) 页面第 4 步仍显示 A 的作业号/进度/问题，用户以为 B 在发布；b) A 的发布失败会以 `publishConflict` Alert 形式挂到 B 上（`:1089-1098` + `:1171-1179`），把 A 的冲突归因给 B；c) **A 的 publish=COMPLETED 且 B 状态=COMPLETED（预检查通过即 COMPLETED，见 `:391-400`）时，`isReadOnly`（`:402-408`）为真 → B 整页只读，"确认发布"按钮消失，只能刷新页面才能发布**；d) 旧 watcher 继续打 `GET /jobs/A` 与旧 task 的 EventSource（资源泄漏 + 跨任务事件）。
- **对应测试**：`tests/import-watch-recycle.spec.ts` 只锁同 kind 回收（且用自建 wiring，`:61-73`）；`tests/import-publish-confirm.spec.ts` 固定 taskId=7；无换任务用例。
- **缺的测试**：挂 ImportWizardView + 可变的 `route.query.taskId`，断言换任务后：旧 dispose 被调用、`publishJob/importJob/checkJob` 清空、`isReadOnly` 为假、无旧 jobId 的 getJob 轮询。

### I-03 409（重复提交/丢响应后重试）后不重挂 SSE（Important｜具体风险，代码路径明确）

- **位置**：`frontend/src/stores/ai.ts:3119-3129`（`confirm` 的 `DUPLICATE_TOOL_CALL_ID` 分支只 `markGateSyncing + armGateSync(3s)` 后 `return`）；`:3215-3227`（`submitFrontendToolResult` 的 409 分支只收敛段后 `return {duplicate:true}`）；`reconnectSuspendedRun` 只在成功路径调用（`:3114`、`:3211`）；`:2959-2981`（3s 后仅拉 `runs/current` 落回执，不建连接）；`:221-250`（`APPROVED→{approved,running}`）。
- **触发步骤**：① 确认门挂起（挂起即断，此刻**没有任何连接**）；② 用户点"批准"，`POST /api/ai/confirm` 的响应丢失（网络抖动/代理超时）→ 用户再点 → 409；③ 或 GF 表单提交后按"重试"→ 409；④ 3 秒后 `runs/current` 返回 APPROVED → 卡面变只读"running"，`suspended=false`、`loading=false`。
- **用户影响**：面板进入"空闲"外观，但服务端这一轮仍在执行；`done` / 后续 `tool_result` / 结论文本**永不显示**；停止按钮消失（`AiPanel.vue:950` 只在 `loading||suspended` 渲染），下次发送必然 409；GF 场景则相反——`suspended` 不被清除，输入框锁死（`AiPanel.vue:1580-1586`），只剩停止按钮。两条路都只有刷新页面（`restoreInFlightRound` 重挂）能恢复。
- **对应测试**：`tests/r166-gate-link.spec.ts:217-238` 断言 409 后"SSE 结局帧到达"的收敛——但挂起态下**根本没有连接**能送这帧，该用例在物理上只覆盖"非挂起轮 409"；`tests/r166b-fixes.spec.ts:443-480` 只测 409 不写值。
- **缺的测试**：409 后应断言 `reattachRun` 被调用一次（或显式"不续流 + 提供恢复入口"的替代设计）。

### I-04 AI 发起的发布作业不绑定到工作区作业面板（Important｜已证实缺陷）

- **位置**：`frontend/src/stores/ai.ts:812-816`（`START_JOB_SYNC` 只有 `start_export/start_precheck/start_import`）；`:5360-5382`（`armStartJobSync` 只按该表派发）；`:5326-5348`（`start_publish` 只武装 `watchPublishOutcome`）；`ImportWizardView.vue:1288-1311`（`import.syncJob` 具备按 kind 绑定任意作业的能力，但对 `start_publish` 永不触发）。
- **触发步骤**：在导入向导第 4 步让 AI"发布任务 #7"→ 确认门批准 → 对话显示"发布作业 #N 已启动"；页面 `publishJob` 仍为 `null` → `JobProgress` 显示"未开始"（`JobProgress.vue:67`）。
- **用户影响**：页面与对话矛盾；用户重复点"确认发布"→ 后端作业互斥（RESOURCE_LOCKED/已有作业）报错；成功后页面也不会自动刷新暂存/任务状态，直到手动刷新或重新进页。
- **对应测试**：`tests/ai-start-job-sync.spec.ts` 只覆盖三件套（其文件头 `:1-16` 自述"三件套"），未覆盖 `start_publish`。
- **缺的测试**：`start_publish` 的 `tool_result` 应断言 `import.syncJob` 被调用且 `publishJob.id === N`。

### I-05 并发发布跟踪的单槽闸丢弃较早作业的冲突（Important｜已证实行为，测试已固化）

- **位置**：`frontend/src/stores/task.ts:113-116`（`watchingJobId` 模块级单值）、`:298`（新跟踪无条件占闸）、`:352-355`（闸不匹配直接 `return`，**既不 `notifyJobWatch` 也不 `onConflict`**）。注册表本身按 `taskId:jobId` 支持并存（`:293-297`）。
- **触发步骤**：同一页签先后（或近乎同时）对任务 A、B 发起发布（AI 侧 `start_publish` 两次，或 A 由 AI 起、B 由页面起）→ B 的跟踪占据闸位 → A 的发布失败终态到达。
- **用户影响**：A 的失败**完全静默**——无 ElMessage、AI 卡无冲突明细、模型下轮也读不到（`recentActions` 无记录）。用户只知道"A 的发布作业失败了"（若页面恰好绑定），不知道为什么。
- **对应测试**：`tests/watch-publish-outcome.spec.ts:255-289` 正是这个场景，并**断言 `staleConflict` 不得被调用、提示仍为 1 次**——把"静默丢弃"写成预期。该用例对"同任务重跑"是合理取舍，但对"跨任务并发"是不可接受的失真，测试没有区分这两者。

### I-06 作业终态与发布冲突并不真正"回灌本轮模型"（Minor｜具体风险/契约不对称）

- **位置**：`frontend/src/stores/workspace.ts:405-409`（`reportUiEventResult` → `workspace.touch` + `emitWorkspaceEvent`）、`:548-565`（注释称"AI 保证通道…回灌模型"）；`frontend/src/stores/workspace.ts:283-302`（`buildContext().extra.recentActions` 只在**下一轮请求**携带）；`frontend/src/stores/ai.ts:812-816`+后端只有 `check_job_status`（只读一次性查询，`AiTools.java:207-218`），**无 cancel 工具**。
- **触发步骤**：AI 发起 `start_export`/`start_import`/`start_precheck`，作业 30s 后 FAILED；对话停在"作业 #N 已启动"，模型不知道失败；只有用户再发一句话，失败信息才作为 `recentActions` 出现。
- **用户影响**：AI 的"最后一句话"永远是"已启动"，不闭环；用户需自己去看作业面板；AI 也无法取消作业。
- **对应测试**：`tests/publish-conflict-link.spec.ts` 只证明"落段"，无"同一轮内模型可见"的断言（也做不到——协议没有该通道）。
- **备注**：发布冲突的卡面可见性属实（`attachPublishConflict` 写段 + `resultView`），本条只针对"模型可见"。

### I-07 `tool_start` 对**已终态**段没有保护（Minor｜仅缺测试/防御缺口）

- **位置**：`frontend/src/utils/parts.ts:539-557`（本次补丁：仅 `pending && requiresAction` 时 `break`；其余情况无条件 `upsertToolPart({status:'running', requiresAction:false})`）；`frontend/src/utils/parts.ts:91-104`（`upsertToolPart` 对已存在段做**无条件的 spread 合并**，没有"终态 first-wins"）；`frontend/src/stores/ai.ts:4943-4948`（跨宿主已决守卫只在**当前宿主没有该段**时生效）。
- **触发条件**：同一 `toolCallId` 的 `tool_start` 在 `tool_result/frontend_tool_result` **之后**到达且落在**同一宿主消息**上（跨实例异常回放顺序、或双连接未及时 abort 的窗口）。契约禁止该顺序，故判"具体风险"而非缺陷。
- **用户影响**：已完成的工具卡被复位成"执行中"并保持（后续不会再有该工具的终态帧），GF 卡则失去提交入口——正是本次补丁要防的形态，只是换了触发顺序。
- **对应测试**：新增 `tests/park-request-tool-start-order.spec.ts` 覆盖 `request→start` 与 `start→request` 两序，**未覆盖** `result→start`（`:64-111`）。

### I-08 批量下载：zip 名取起始任务、内容取当前任务（Minor｜具体风险，换任务竞态）

- **位置**：`frontend/src/views/ExportWizardView.vue:1066-1093`（`const task = taskId.value ?? 0` 在发起时捕获，`downloadBlob(zipped, \`export-${task}.zip\`)`）；`:967-976`（`buildXlsx` 在解析时才读 `taskId.value` 与 `gridRefs`）。
- **触发步骤**：AI/用户触发 ≥2 码下载 → 立即切到另一任务（路由 query 变化）→ `resetTaskScopedState` 清 `exportResults`/`loadedGrids`，`gridRefs` 随组件卸载被删 → `buildXlsx` 回落后端 `GET /tasks/{新id}/files/{code}`。
- **用户影响**：得到一个"名字是旧任务、内容是新任务"的 zip，或部分文件 404 导致整包失败（错误文案只报首个失败）。
- **对应测试**：`tests/export-wizard-batch-download.spec.ts` 无换任务场景。

### I-09 "工作区已同步"chip 在失败回执时仍出现（Minor｜已证实缺陷，文案级）

- **位置**：`frontend/src/stores/workspace.ts:447-452`（无论 `handled` 真假都 `workspace.touch(result.message, 'AI')`）→ `ai.ts:1567-1569` 订阅 `workspace_changed` → `AiPanel.vue:51-53`；文案 `src/locales/zh-CN.json:453` "工作区已同步：{summary}"。
- **触发步骤**：AI 在只读任务上 `select_definitions` → `dispatchUiEvent` 返回 `handled:false, message:'任务已结束，仅供查看，未变更所选配置项'` → chip 显示 **"工作区已同步：任务已结束，仅供查看…"**（`lastUiEventResult` 红/灰条同时显示）。
- **用户影响**：同一件事两套措辞（"已同步"+"未变更"），"已同步"是错的；模型下一轮 `recentActions` 里也出现"来源：AI"的失败记录。

### I-10 设置页保存模型/密钥后面板标签不刷新（Minor｜具体风险）

- **位置**：`frontend/src/views/AiSettingsView.vue:287-307,476-543`（保存成功只 `ElMessage.success`）；`frontend/src/components/AiPanel/AiPanel.vue:3628`（只在挂载 `probeHealth`）；`frontend/src/stores/ai.ts:1592-1604`（`probeHealth` 唯一写点）+ `:2718-2736`（仅在特定错误码后重探）。
- **触发步骤**：管理员在同一浏览器另一页签改默认模型 → 回对话页：副标题 `currentModelLabel`（`AiPanel.vue:3618`）仍是旧模型；`health.available` 也可能过时（若无 key 被移除，面板仍显示可用，直到首次 `/chat` 失败）。
- **用户影响**：展示与真实生效模型不一致；期间发送会得到按新配置的错误（如 MODEL_NOT_FOUND），增加一次失败往返。
- **对应测试**：无（`ai-settings-view.spec.ts` 不跨页）。

### I-11 一批 ui_event 分支无模型可达入口，却被当成"AI 能发布/能取消/能刷新"的证据（Minor｜覆盖/死码）

- **位置**：`frontend/src/stores/workspace.ts:455-639` 的 `run_job/cancel_job/download/refresh_defs/refresh_data/refresh_tasks/set_import_mode/open_page/export_started/import_batch/import_action`；`frontend/src/utils/frontend-tools.ts:1449-1466`（`toUiEvent` 支持）+ `:691-693`（`isUiEventType` 一律放行）；`frontend/src/types/tools.ts:503-530`（`UI_EVENT_TYPES` 白名单）。**后端 34 个 `@Tool` 中无同名工具**（`backend/src/main/java/com/example/configmgr/ai/tools/AiTools.java` 等，只有 `check_job_status`/`create_task`/`start_*`/`navigate_to`/`select_definitions`/`set_condition`/`confirm_step`/`download_export_files`/`open_export_file_editor`/表格 9 件/`ui_*` 7 件/kb 3 件）。
- **影响**：a) `tests/workspace-run-job-publish-confirm.spec.ts:72-173`（7 例）与 `tests/gate-matrix.spec.ts:155-188`（5 事件）锁的是**不可达分支**——绿不代表"AI 能发布/能取消"；b) 模型**没有任何取消作业的工具**；c) 若后端将来新增同名工具，这些分支会立刻生效且带着"无防重（`run_job` 可并发弹两次确认）""`download` 接受任意 URL（`workspace.ts:591-611` 直接 `fetch(event.url)`，无同源/白名单）"的既有缺口。
- **建议的判定**：这些分支应作为"契约层防御"保留，但测试命名与报告措辞必须标注"当前无模型入口"，不能计入 AI 全链路验证。

### I-12 `isCurrentTaskReadOnly` 的"最近一条作业"口径（Minor｜可证明正确（带条件）+ 已登记发散）

- **位置**：`frontend/src/stores/workspace.ts:419-436`（`jobs.filter(job => job.jobType === finalJobType)[0]`）；后端 `JobRepository.java:18`（`findByTaskIdOrderByCreatedAtDescIdDesc`）⇒ `[0]` 确为最新，**在此后端上可证正确**；注释 `:427-431` 已明示与后端"存在任一 COMPLETED 最终作业"的发散（仅"前端放行、后端拒"）。
- **风险**：若排序或分页口径变化（例如未来加 `size` 限制），该闸会静默失准；且它只读 `taskStatus === 'COMPLETED'` 时触发一次额外 GET，属"AI 写事件前的网络依赖"，超时会被 catch 成 `null` → 放行（fail-open）。属可接受取舍，登记即可。

## 5. 覆盖缺口（必须明确列出）

1. **拓扑前提未复现（最重要）**：`e2e/support/harness.ts:17,53-54,356,548` 为 A/B 两个 JVM 指定**各自独立的 H2 目录**，只共享 Redis 单库（`:535,548` 的 `REDIS_DB`）；**没有 3+ JVM、没有 Redis Cluster、没有共享 DB/文件**。因此"作业进度/任务 SSE/文件下载/发布冲突在跨实例与共享库下的正确性"在前端侧**零验证**。`e2e/15-mdo.spec.ts:1-14` 自述"M 域"也只声称共享 Redis 台账。
2. **多用户隔离**：前端无用户身份（`src/api/http.ts` 全文件无 Authorization/token/user），无法从前端验证；只能证明"页签隔离"。
3. **换任务/换绑定**：导入向导（I-02）无测试；导出侧有实现无专项测试（`tests/export-wizard-step-outcome.spec.ts:155` 只碰了 `applyTaskBinding` 的一个分支）。
4. **409 后的续流/恢复**：无测试（I-03）。
5. **`start_publish` 的页面同步**：无测试（I-04）。
6. **并发发布冲突**：有测试但断言的是"丢弃"（I-05）。
7. **设置页 ↔ 面板/工作区一致性**：无测试（I-01、I-10）。
8. **`tool_start` 的异常顺序**：只覆盖两种"契约/半契约"顺序（I-07）。
9. **文件下载跨任务**：无测试（I-08）。
10. **`refresh_defs/refresh_data` 的真实效果**：代码诚实声明"无订阅者 ⇒ 数据不变"（`workspace.ts:612-626`），但**没有任何测试**锁定"AI 说不会刷新 ⇒ 页面确实不刷新"，也没有面向用户的替代路径（需用户手动重开页面）。
11. **未覆盖分支**：见 §1.3。
12. **条件跳过**：`e2e/16-kqc*`、`17-unlock:677`、`10-srp:164`、`00-harness:30` 默认不执行相关面。

## 6. 可证明正确的部分（附条件）

| 结论 | 条件 | 证据 |
|---|---|---|
| 帧幂等与终帧闸门 | 后端 `seq` 单调、不复用 `toolCallId`；归档按序回放 | `sse.ts:270-303,391-413`；`ai.ts:4933-4948,5218-5225`；`parts.ts:859-870`；`tests/parts-reducer:415`、`tests/sse-parser-boundary:417`、`tests/aie2e-stream`、`e2e/15-mdo` M01（两实例 seq 全等、单终帧） |
| 断流可恢复、loading 必复位 | `reattach` 只 resolve 不 reject；看门狗阈值 < 代理空闲超时 | `sse.ts:577-751,521-541`；`tests/sse-watchdog`、`tests/b1-suspend-disconnect` |
| 确认门防重与"不伪造裁决" | 单页签内；服务端 409 语义为"已有决策" | `ai.ts:3074-3091,3119-3148`；`tests/r166-gate-link:217-238`、`tests/parts-residue-cleanup:205-230` |
| 预览身份 + 行指纹阻断"旧预览改新数据" | 行主键列参与指纹；**非主键格的手改按设计会被覆盖**（卡面已提示） | `sheet-tools.ts:716-733,1428-1500,2573-2601,2871-2893`；`SpreadGrid.vue:682-683`；`tests/sheet-preview-identity`、`tests/sheet-preview-apply-undo` |
| 作业终态以快照为准、通道降级自愈、在途刷新不丢 | 后端 `GET /jobs/{id}` 权威；EventSource 可重连 | `job-watch.ts:73-252`；`polling.ts:46-247`；`tests/job-watch-channel:174-447` |
| 发布模式 fail-closed | `GET /tasks/{id}` 可读到 `settingsJson` | `workspace.ts:529-545`；`ImportWizardView.vue:1020-1035`；`tests/publish-mode`、`tests/workspace-run-job-publish-confirm:150-173`（分支可达性另见 I-11） |
| 只读任务拒绝写事件 | 任务状态已由后端 `applyJobOutcome` 联动；jobs 列表 DESC | `workspace.ts:419-436`；`ImportWizardView.vue:402-408,1003,1408`；`tests/gate-matrix:155-188` |
| 页签会话隔离与让渡 | 浏览器支持 Web Locks；不支持时降级为共享会话 + 409 | `ai.ts:288-620,4576-4750`；`tests/tab-ownership-lifecycle`；`e2e/13-nfezui`、`e2e/15-mdo` M07 |

## 7. 未验证项（明确不做保证）

- 跨实例（3 JVM / Redis Cluster / 共享 DB+文件）下：作业进度、任务 SSE 重连、文件字节一致性、发布冲突归属、下载内容一致性——**本域无任何验证**（只有后端新增单测存在，未在本报告采信）。
- 真模型（`E2E_MODE=real`）下的帧序（含本次补丁针对的 park 帧序）——E2E 默认桩上游，`tests/park-request-tool-start-order.spec.ts` 只做纯函数。
- 浏览器兼容性：Safari/Firefox 的 Web Locks/BroadcastChannel 行为、`showOpenFilePicker` 缺失时的上传交接降级（只有 jsdom 级证据）。
- 所有"测试为绿"的表述：本快照无依赖，未执行任何测试。
