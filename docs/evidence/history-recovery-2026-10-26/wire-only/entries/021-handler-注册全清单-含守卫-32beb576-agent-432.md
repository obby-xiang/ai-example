> **历史分析 / 过程证据 — 非现行基线，非验收结论**
>
> - 归档：2026-10-26 · `docs/evidence/history-recovery-2026-10-26/wire-only/`
> - 来源：Kimi Code 会话 `<KIMI_SESSION>` / 子 agent `agent-432` 的 wire 末条 assistant 文本（只读流式解析；非原始会话转写）
> - 原始体量：13495 B / 10134 字符 / 120 行；行指纹覆盖率（仓内 + 落盘语料）4.3%
> - 类别：**过程核验**
> - 脱敏：路径 0 / 会话标识 0 / 身份 0 / 密钥 0
> - 正文为该条消息的完整文本，未删段、未改写结论；仅脱敏与行尾空白规整。当时结论不代表当前代码状态。
## 1. handler 注册全清单（含守卫）

写入路径上的两个文件**只在**这两处注册页面能力（全仓 `grep registerPageHandler(` 无第二处调用点）。

**`frontend/src/views/ImportWizardView.vue`** — `HANDLER_NAMES` @986-993（6 个名字，全部登记；无 `registerFrontendToolExecutor`）

| 行 | handler | `if (isReadOnly.value)` 守卫 | 落到哪个写函数 |
|---|---|---|---|
| 997 | `import.nextStep` | 有 @999 | `goStep(index)` @1009 |
| 1018 | `import.selectDefs` | 有 @1020 | 无（只改本地 ref + 本地 store） |
| 1032 | `import.upload` | 无，但函数体只有 `return '请提示用户点击…'` @1033 | 零写 |
| 1036 | `import.startPrecheck` | 有 @1038 | `startJob('check')` @1041 |
| 1045 | `import.startImport` | 有 @1047 | `startJob('import')` @1050 |
| 1054 | `import.startPublish` | 有 @1056 | `startJob('publish')` @1059 |

**`frontend/src/views/ExportWizardView.vue`** — `HANDLER_NAMES` @989-995（5 项；`PAGE_HANDLER_OPEN_EXPORT_EDITOR` = `'export.openEditor'`，定义在 `frontend/src/utils/frontend-tools.ts:72`）

| 行 | handler | 守卫 | 写函数 |
|---|---|---|---|
| 1122 | `export.nextStep` | 有 @1124 | `goStep(index)` @1132 |
| 1136 | `export.selectDefs` | **无** | 无写 API（只改本地 `selectedDefs`/`draftModels` @1138-1141 + `syncSelectedDefsToWorkspace('AI')` @1144 纯本地 store） |
| 1148 | `export.setConditions` | 有 @1150 | `tasksApi.setCondition` @1169 |
| 1179 | `export.start` | 有 @1181 | `startExport()` @1184 |
| 1188 | `export.openEditor` | 无写守卫，但**分支收口**：@1201-1208 只读走 `viewStep(2)`、非只读 `goStep(2)`；@1215 `ensureGridLoaded`（GET） |
| 1237 | `registerFrontendToolExecutor('download_export_file')` | 无守卫，但 @1109-1116 只读走 `viewStep(2)`、非只读 `goStep(2)`；下载副作用在浏览器 | |

## 2. 写 API 调用点全清单（行号 / 所在函数 / UI 入口可达性）

**ImportWizardView.vue**

| 行 | 调用 | 所在函数 | 入口可达性（只读态） |
|---|---|---|---|
| 525 | `tasksApi.selectDefs` | `persistSelection` @521 | 三个上游全被隐藏：`downloadTemplates` @541（按钮 @37 `v-if="!isReadOnly"`）、`handleUpload` @566（`el-upload` @41 `v-if="!isReadOnly"`）、`flushEditors` @654（按钮 @52 `v-if="!isReadOnly"`） |
| 567 | `tasksApi.uploadFile` | `handleUpload` @555 | 上传控件 @41 `v-if="!isReadOnly"` |
| 670 | `tasksApi.saveFile` | `flushEditors` @646 | 按钮 @52 隐藏；另一上游是 `goStep` @716（见下行） |
| 759 | `tasksApi.goToStep` | `syncStepToBackend` @750 | 仅由 `goStep` @724 调用 |
| 711 | `goStep` | — | 3 个调用点：`goPrev` @767 ← 页脚“上一步”（`show-prev` @11 = `!isReadOnly`）、`nextStep` @775 ← 页脚“下一步”（`show-next` @12 = `!isReadOnly && …`）、AI handler @1009（有守卫 @999） |
| 788 | `jobsApi.createJob` | `startJob` @780 | `startCheck` @802 ← 按钮 @85 隐藏；`startImport` @806 ← 按钮 @118 隐藏；`startPublish` @811 ← 按钮 @178 隐藏；另经 handler @1041/1050/1059（均有守卫） |
| 934 | `jobsApi.cancelJob` | `cancelJob(job)` @929 | **JobProgress `@cancel` @95 / @128 / @202 —— 无任何只读守卫**（见§7-A） |
| 976 | `tasksApi.setImportMode` | `onImportModeChange` @971 | 单选组 @139 `:disabled="isReadOnly"`；**该文件没有 `watch(importMode)`**（全仓 grep 仅 @139/299/439/452/976-978） |

**ExportWizardView.vue**

| 行 | 调用 | 所在函数 | 入口可达性 |
|---|---|---|---|
| 608 | `tasksApi.selectDefs` | `persistStepData` @602 | 仅由 `goStep` @550 与 `startExport` @668 调用 |
| 615 | `tasksApi.setCondition` | `persistStepData` @602 | 同上；另有 handler @1169（守卫 @1150） |
| 587 | `tasksApi.goToStep` | `syncStepToBackend` @578 | 仅由 `goStep` @551 调用 |
| 544 | `goStep` | — | 4 个调用点：`@prev` @14（页脚隐藏 @11）、`nextStep` @598 ← 页脚 @12 隐藏、handler @1132（守卫 @1124）、`preflightDownload` @1114 与 openEditor handler @1206（两处都在 `isReadOnly` 的 else 分支） |
| 671 | `jobsApi.createJob` | `startExport` @659 | 按钮 @62 `v-if="!isReadOnly"`；handler @1184（守卫 @1181） |
| 741 | `jobsApi.cancelJob` | `cancelJob` @735 | **JobProgress `@cancel` @83 —— 无只读守卫**（见§7-A 的出口侧说明） |
| 976 | `tasksApi.saveFile` | `saveEdits` @957 | 按钮 @74 `v-if="!isReadOnly"` |

**三个被点名的 watcher/回调（逐条判定，均为零写）**

- Import `watch(selectedDefs)` @1097 → `syncSelectedDefsToWorkspace` @1098（纯本地 store @404-411）+ `loadEditors(true)` @1100（`downloadFile` GET @609 / 本地模板）——**零写**。触发面：`DefSelector` 在 `disabled` 下选择列 `:selectable="() => !props.disabled"`（`components/wizard/DefSelector.vue:21`），只读态点不动；即使被 AI 的 `select_definitions` 改选（@1024），也只落到本 watcher 的零写分支。
- Import `watch(workspace.step)` @1129 → 改 `stepIndex` + `loadStaging`（`getDiff` GET @951）——零写。**注意**：`ui_event goto_step` 是先 PUT（§4）再 `workspace.setStep`，所以这个 watcher 本身不产生写，写发生在上游。
- Export `watch(workspace.step)` @1330 → `refreshResults` @1337（`loadExistingResults`/`downloadFile` GET）——零写。
- Import `onEditorTabChange` @625 → `ensureEditorLoaded` → `loadEditors`（GET）；`onGridChanged` @636 → `workspace.touch`（本地），且 `SpreadGrid :read-only` @71。Export `onTabChange` @792 → `ensureGridLoaded`（GET @778）；`onGridChanged` @796 → `workspace.bumpVersion/touch`（本地），`:read-only` @109。**均零写**。
- 附带一条非写但越界：Import 第 4 步“刷新”按钮 @143 没有 `v-if`，只读态可点，但 `loadStaging` 是 `getDiff`（GET）。

## 3. 全仓 `frontend/src` 的 `viewStep`

- 定义：`views/ImportWizardView.vue:736`（`async function viewStep`）；`views/ExportWizardView.vue:563`。
- 模板绑定：`ImportWizardView.vue:16`、`ExportWizardView.vue:16`（`@step-click="viewStep"` ← `TaskWizard` 只读态 `<button>` 的 `emit('step-click', i)`，`components/TaskWizard.vue:45`）。
- 调用点：`ExportWizardView.vue:1112`（`preflightDownload`，只读分支）、`ExportWizardView.vue:1204`（`export.openEditor` handler，只读分支）。
- 注释提及（非调用）：`ImportWizardView.vue:688`、`ExportWizardView.vue:537/1082/1109/1201`。
- **无其它文件定义或调用**。Import 侧无 handler 分支调用 `viewStep`。

## 4. `stores/workspace.ts` 的 `goto_step` 现状

**未加任何只读守卫，仍无条件写。** `goto_step` @370-379：仅判 `workspace.taskId === null`，随后 `await tasksApi.goToStep(taskId, event.step)` @375（`tasksApi.goToStep` → `PUT /tasks/{id}/step`，`api/tasks.ts:45-47`），再 `workspace.setStep` @376 + `refreshCurrent` @377。

同一 `runUiEvent` 里其它写分支同样无守卫：`select_defs` @385（`POST /select-defs`）、`set_conditions` @395（`PUT …/condition`）、`set_import_mode` @404（`PUT …/import-mode`）、`run_job` @409（`POST /jobs`）、`cancel_job` @419（`DELETE /jobs/{id}`）。旁证：整个 `frontend/src/**/*.ts` 里 grep `readOnly|read_only|isReadOnly` **零命中** —— 只读判定只存在于两个 `.vue` 的局部 computed。

## 5. `JobProgress.vue` 的「取消作业」按钮

- 显示条件：`v-if="running"`（`components/JobProgress.vue:8`），`running = job !== null && ['PENDING','RUNNING'].includes(job.status)`（@103）。**没有 `readOnly` prop**，组件只接收 `job/issues/channel/errorMessage`（@84-99）。
- 使用处的额外守卫：Import `@95`、`@128`、`@202` 与 Export `@83` —— 四处**都只是 `@cancel="cancelJob(...)"`，无 `v-if`/`:disabled`/只读分支**。

## 6. 写方法清单（供对账）

`frontend/src/api/tasks.ts`（写＝POST/PUT/DELETE）
- `createTask` @25 → `POST /tasks`
- `deleteTask` @30 → `DELETE /tasks/{id}`
- `selectDefs` @35 → `POST /tasks/{id}/select-defs`
- `setCondition` @40 → `PUT /tasks/{id}/items/{defCode}/condition`
- `goToStep` @45 → `PUT /tasks/{id}/step`
- `setImportMode` @50 → `PUT /tasks/{id}/import-mode`
- `uploadFile` @60 → `POST /tasks/{id}/files/upload`
- `saveFile` @78 → `PUT /tasks/{id}/files/{defCode}`
- 只读方法（GET/blob）：`listTasks` @10、`getTask` @15、`getTaskOverview` @20、`getFiles` @55、`downloadFile` @73、`downloadAllFiles` @85、`downloadSelected` @90、`downloadTemplates` @95、`openTaskEvents` @105

`frontend/src/api/jobs.ts`
- `createJob` @10 → `POST /tasks/{taskId}/jobs`
- `cancelJob` @25 → `DELETE /jobs/{jobId}`
- 只读：`listJobs` @15、`getJob` @20、`getIssues` @30、`getDiff` @35、`waitForJobFinal` @50

## 7. 只读态下疑似仍可达的写路径

### A. `JobProgress` 的「取消作业」——**唯一一条经真实 UI 可点的写路径**
推理链：
1. 按钮条件只是作业 `PENDING/RUNNING`（`JobProgress.vue:8,103`），两向导的四次使用都没有只读守卫（§5），`cancelJob` 函数体内也只有 `job?.id` 判空（`ImportWizardView.vue:929-939`、`ExportWizardView.vue:735-746`）→ `DELETE /api/jobs/{id}`。
2. 只读态与“有在途作业”并不互斥：Import 的 `isReadOnly` 有一条是 `task.status === 'CANCELLED'`（`ImportWizardView.vue:341-347`）。
3. 任务变 CANCELLED 的唯一来源是“取消某个作业”：`JobService.cancel` @264-267 → `applyJobOutcome(taskId, CANCELLED)` → `TaskService.java:216-233` 把任务置 `CANCELLED`；页面在该作业终态时 `await refreshTaskSnapshot()`（`ImportWizardView.vue:858,865-875`）→ `isReadOnly` 当场翻 true。
4. 互斥只按“同任务 + **同类型**”拦截（`JobService.assertNoJobInFlight` @113-121，`IN_FLIGHT = PENDING/RUNNING`），所以同任务上 `IMPORT` 与 `PUBLISH` 两类作业**可以同时在途**（`assertPrecheckPassed` 只要求最近一次 PRECHECK 已通过，不要求 IMPORT 已结束）。
5. 于是：IMPORT 在途 → 用户在第 4 步启动 PUBLISH（按钮只被 `jobRunning(publishJob)` 约束，@178）→ 取消 PUBLISH → 任务变 CANCELLED、`isReadOnly` 翻 true，而 IMPORT 作业仍是 RUNNING → 第 3 步的 `JobProgress` 上“取消作业”依旧可见可点 → 只读态发出 `DELETE /jobs/{importJobId}`。刷新页面同样成立：`resumeJobs()` @480 会把在途作业重新挂上。
6. Export 侧同构但更窄：`isReadOnly` 要求 `exportJob COMPLETED`，本页只有一种作业类型，所以正常情况下 `cancelJob` @741 在只读态无可见按钮；需要“任务在别处被取消而 exportJob 仍在途”才触发。

### B. 契约层 ui_event 分支（`workspace.ts`）无只读闸门
`goto_step` @375 / `select_defs` @385 / `set_conditions` @395 / `set_import_mode` @404 / `run_job` @409 / `cancel_job` @419 全部无条件写（§4），且 `.ts` 层不存在任何只读概念。可达性分两条：
- **B1（正常工具名）不可达**：AI 侧 `navigate_to`/`select_definitions`/`set_condition`/`confirm_step` 在 `utils/frontend-tools.ts:445-451` 先命中“工作区动作表”（路由 2），其中 `confirm_step` 的 `handlerNames` @656 会命中两个向导页的 `*.nextStep`（有守卫），`select_definitions` @628 命中 `*.selectDefs`。即正常命名面被页面 handler 接住。
- **B2（同名 ui_event 名）代码可达、当前未披露**：路由 3（@449-451）按**名字字符串**匹配 `UI_EVENT_TYPES`，没有“后端已披露工具集”白名单。`select_defs` / `set_conditions` / `set_import_mode` / `run_job` / `cancel_job` 这些名字既不在 `FRONTEND_TOOLS`（`types/tools.ts:82-107` 只有 `open_export_file_editor` / `download_export_file`），也不在 `WORKSPACE_ACTION_ALIASES` @681-688，所以一旦以这些名字到达（`frontend_tool_request` 帧，`stores/ai.ts:1368`/`1580` → `executeFrontendTool`），就会**直落 `dispatchUiEvent` 的无条件写**，与只读态无关。
- **B3（窄竞态）**：路由 2 命中动作但页面 handler 此刻缺失时会 fallback 到 `toUiEvent` → `dispatchUiEvent`（`frontend-tools.ts:731-739`）。`workspace.pageId` 在离开向导后不会重置（无 `leavePage`，只在别的页 `enterPage` 时改，`stores/workspace.ts:123-125`），`onBeforeUnmount` 又已 `unregisterPageHandler`（`ImportWizardView.vue:1076`、`ExportWizardView.vue:1270`），所以在“仍算 export/import 页、但向导已卸载”的窗口里发 `confirm_step` 会走 `toUiEvent` @657-660 产出 `goto_step` → `PUT /step`。

### C. 非写但被点名的两处（供你判断是否算越界）
- `export.selectDefs` handler @1136 **无守卫**：只读态若 `workspace.step` 仍是 `SELECT_DEFS`（例如 CANCELLED 的导出任务停在首步），AI `select_definitions` 能改掉页面内存里的 `selectedDefs/draftModels`（不落库、不写文件）——UI 上表现为只读页的勾选集被 AI 改动，随后 `watch(selectedDefs)` @1318 只做本地收敛。
- Import 第 4 步“刷新” @143 无 `v-if`，只读态可点（`loadStaging` 为 GET，非写）。

**未能验证的一点**：A 的推理链第 5 步里“IMPORT 与 PUBLISH 可同时在途”是从 `JobService.assertNoJobInFlight` 的按类型互斥 + `assertPrecheckPassed` 的判据读出的，我没有跑起后端实测该并发组合，也没有跑浏览器验证“只读态下取消按钮确实可见可点”。
