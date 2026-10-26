# 历史补档：三份施工卡「时效体检」报告（步骤条包 / 对话时序包 / 轮询治理一期）

- **归档来源（已脱敏）**：`<LOCAL_WORK>/pkg-health/体检报告.md`（源 629 行 / 70,111 B）· 源件仅仓外，未移动、未改写
- **归档日期**：2026-10-26（本批仅归档）
- **历史取证 · 非现行基线**：本文是 2026-10-11（2026-10-12 批4④前置行号刷新） 时点的历史证据，结论未经当前代码复验，**不表示当前已验证 / 已修复**；正文逐字保留（仅路径、会话标识、用户名/主机名与旧目录名脱敏），不改写旧结论。
- **会话（体内代号）**：<KIMI_SESSION_A>（缩写；模型与 `agentId` 自证字段按 DC-19 保留原值）
- **源行 / 源字节**：629 行 / 70,111 B

---

执行者=DeepSeek V4 Flash（Kimi Code 子智能体）+授权来源=Kimi K3 指挥官任务卡（2026-10-11）　｜　**2026-10-12 批4④前置行号刷新**：执行者=`agent-527`（Kimi Code 子智能体，实际运行模型 `DeepSeek/deepseek-flash`，session `<KIMI_SESSION_A>`）+授权来源=**K3 派单 · 批4④ 侦察卡 B**（刷新范围=卡② 对话时序包区间；刷新时报头 HEAD=`296ffe2`；刷新 114 处／失锚 3 处；详见文末 §9）

# 三份施工卡「时效体检」报告

| 项 | 值 |
|---|---|
| 体检对象 | `<LOCAL_WORK>\step-wizard-pkg\施工卡.md`（步骤条包）／`<LOCAL_WORK>\chat-timeline-pkg\施工卡.md`（对话时序包）／`<LOCAL_WORK>\polling-gov\施工卡.md`（轮询治理一期 R-88） |
| 被检仓库 | `<REPO_ROOT>` |
| 体检方式 | **只读**：Read / Grep / `git` 只读命令。未改仓库任何文件、未做 git 写操作、未读任何 `.env`、未启动前后端 |
| 唯一写入 | 本文件（`<LOCAL_WORK>\pkg-health\体检报告.md`） |
| 断言分级 | 【实测】= 本棒实读代码/实跑只读命令所得原文；【推断】= 由实测直接推出的结论 |
| 体检时间 | 2026-10-11 |

---

## 0. 结论速览（一句话）

| 卡 | 结论 |
|---|---|
| ① 步骤条包（`step-wizard-pkg`） | **需刷新（约 32 处）**：`TaskWizard.vue`/`ImportWizardView.vue` 锚点全中，但 **`ExportWizardView.vue` 脚本区整体漂移（+8～+141 行，21 处）**，另 **台账 `R-94` 编号已被他项占用**（S4 必须改号）；与批 B **文件级无重叠**（仅台账 `docs/M2-排期计划.md` 同文件，须错开写入）。**32 处中 31 处为行号平移，唯一语义冲突是 `R-94` 编号** |
| ② 对话时序包（`chat-timeline-pkg`） | **需刷新（体检时点约 85 处，三卡里最重；2026-10-12 批4④前置刷新：实读复核 **114 处**、失锚 **3 处**）**：`stores/ai.ts`（≈50 处，尤其 `handleFrame` 起整段 **+280 行**、`runFrontendTool` **+362 行**）与 `AiPanel.vue`（≈18 处，+20～+152 行）几乎全面漂移，且 `ai.ts` 已新增会话身份守卫/骨架屏等卡外事实；`types/sse.ts`/`api/sse.ts`/`backend/**`（`SseChatEmitter`/`ResilientChatService`）锚点仍准，但 `types/tools.ts`（+17，已增 `outcome`）与 `RedisChatMemoryRepository.java`（+8～+28）已漂；与批 B **文件级无重叠**（仅用 `scripts/verify-e2e.ps1` 做验证，不改它）——**R-130/R-132 已随批1（`1efab09`）施工并入仓**（§6 的「建议并入」已成事实，锚点刷新值见 §6） |
| ③ 轮询治理一期（`polling-gov`） | **即开即用（约 21 处：20 处纯行号平移 + 1 处批 B 实时引入）**：六个核心文件（`stores/task.ts`/`api/jobs.ts`/`utils/job-watch.ts`/`types/sse.ts`/`api/tasks.ts`/`TaskCenterView.vue`）锚点**全中**，后端 `TaskSseService` 全中，4 个 `publishProgress` 发布点 3/4 全中（`ExportJobRunner.java:256` 已被批 B 顶到 `:259`），关键基线（`setInterval` 3 处 / `EventSource` 2 处 / `visibilitychange` 0 处）**全中**；漂移集中在 `ExportWizardView`/`AiPanel.vue`/`SpreadGrid`/`stores/workspace.ts` 的旁证锚点；与批 B **文件级无重叠**（纯前端） |
| ★ 体检窗口内的实时事实 | 批 B（R-96 看门狗）**在本棒体检期间半落地**：工作树由"干净"变为 6 项在制品（竟含**任务卡清单外**的 `JobRepository.java`）；`PublishJobRunner`/`application.yml`/`verify-e2e.ps1`/台账**尚未写入** ⇒ 三卡若要与批 B 错峰，窗口仍开着，但后端旁证锚点会随批 B 持续推进而继续漂移。**详见 §1.1** |

---

## 1. 基线核对：三卡的写作基线已整体前移（**必读，影响所有行号**）

三卡均自述基线 `HEAD = d3020ee`（2026-10-10），且**行号锚点一律取"工作树现态"**（含 18 项未提交在制品）。体检时的实际基线如下：【实测】

| 项 | 三卡自述 | 体检实测 |
|---|---|---|
| `HEAD` | `d3020ee` | **`d897096`**（`docs(evidence): 追溯审查报告入库 + R-130/R-131 登记（呈批 3 追溯收口）`，2026-10-11 10:33） |
| `d3020ee..HEAD` 提交数 | — | **26 个** |
| 工作树未提交项 | 18 项 | **0 项**（`git status --porcelain` 空） |
| 批 B（R-96 看门狗）落地面 | 施工中 | **体检窗口内半落地**（见 §1.1）【实测】 |

### 1.1 体检窗口内的实时变化：批 B 半落地（★必读）

本棒 10:38 首次核对时工作树**干净**（0 项）；10:39 复核时批 B 施工已开始写入，工作树变为 **6 项**（4 改 + 2 新增）【实测】：

```
 M backend/.../config/AppProperties.java
 M backend/.../job/repo/JobRepository.java          ← ★任务卡给的批 B 清单里没有这个文件
 M backend/.../job/service/ExportJobRunner.java
 M backend/.../job/service/JobProgressService.java
 ?? backend/.../job/service/JobHeartbeatRegistry.java   （新增）
 ?? backend/.../job/service/JobWatchdog.java            （新增）
```

**尚未出现**（任务卡清单里有、但体检时点还没写）：`PublishJobRunner.java`、`application.yml`、`scripts/verify-e2e.ps1`、docs 台账。

> 10:40 复核时该集合已扩到 **7 项**：又新增 `M backend/.../ai/web/AiController.java`（同样**不在**任务卡给的批 B 清单内）。**批 B 的工作树仍在持续增长**——本节的复验结论对"体检时点"有效，越晚开工越需重刷。

**对三卡锚点的直接影响（已逐一复验）**【实测】：

| 被卡引用的文件 | 批 B 是否已动 | 卡的锚点 | 复验结果 |
|---|---|---|---|
| `job/repo/JobRepository.java` | 已动（+3 行，追加在文件末尾） | 卡① §0/§6-①：`:15 findByTaskIdOrderByCreatedAtDescIdDesc` | ✅ **仍命中 `:15`** |
| `job/service/ExportJobRunner.java` | 已动 | 卡③ §2.1/附录A：`:256` `JOB_PROGRESS` 发布点 | ❌ **已漂到 `:259`**（+3，批 B 引入） |
| `job/service/ImportJobRunner.java` | 未动 | 卡③：`:216` | ✅ 仍 `:216` |
| `job/service/PrecheckJobRunner.java` | 未动 | 卡③：`:360`；卡①：`:273-274`（实为 `:267-268`，卡写作时即偏） | ✅ 发布点 `:360` 仍中 |
| `job/service/PublishJobRunner.java` | **未动** | 卡③：`:327` | ✅ 仍 `:327` |
| `task/service/TaskSseService.java` | 未动 | 卡③：`:73`/`:88`/`:92-101` | ✅ 全中（`sendEvent HEARTBEAT` 复验 @ `:88`） |
| `job/service/JobProgressService.java` / `config/AppProperties.java` | 已动 | **三卡均未引用** | — |

**【推断】结论不变：三卡与批 B 仍无文件级重叠**（批 B 全部落在 `backend/**`，三卡全落在 `frontend/**` + 台账）。但上面这行 `ExportJobRunner:256 → :259` 正是"批 B 落地会持续侵蚀三卡锚点"的**首个实例**——批 B 完全落地后（含 `PublishJobRunner`/`application.yml`/`verify-e2e.ps1`/台账），卡③ 的后端旁证锚点与卡② 的 E2E 基线须**再刷一轮**。

> 另请注意：`JobRepository.java` 被批 B 修改但**不在任务卡给出的批 B 改动面清单内**——属批 B 的清单外改动，建议指挥官在批 B 收口报告里核对该项是否应登记为范围外改动。


**为什么这决定一切**：三卡写作时的"工作树 18 项在制品"（UI 包拆详情抽屉等）已在 `a98f6d5` 落库，其后又有 25 个提交继续改动前端。因此：

- 三卡的模板区锚点（各 `*.vue` 的 `<template>`）大多**仍中**——因为 UI 包的改动正是卡所依据的现态；
- 三卡的**脚本区锚点**（`<script setup>` 之后）已随后续提交整体下移。位移量按文件、按区域不等（同一文件内可差 +8 → +141，见各卡明细）。

**【推断】三卡无需重写实读纪要的结论部分，但所有 `<script setup>` 区行号必须重刷；建议施工棒开工第一步先按本报告的新行号整批替换，而不是逐条撞错。**

---

## 2. 卡① 步骤条包（`<LOCAL_WORK>\step-wizard-pkg\施工卡.md`）

### 2.a 代码锚点核对

#### ✅ 完全命中的文件（0 漂移）

| 文件 | 结论 |
|---|---|
| `frontend/src/components/TaskWizard.vue`（**120 行，与卡一致**） | **全中**：`:17` `el-steps :status="stepperStatus"`／`:18` `el-step v-for`／`:22` `<slot />`／`:26-29`（实为 26-30）页脚／`:48-81`（实为 53-81）props／`:90` `stepState`／`:94-102` `stepperStatus`／`:104-115` `statusTagType`【实测】 |
| `frontend/src/views/ImportWizardView.vue`（**1059 行，与卡一致**） | **全中**：`:253-259`／`:389-422` `loadTask`／`:404-405`／`:443-462` `resumeJobs`／**`:448-449` `slice(-1)[0]`（O-2 现场仍在）**／`:464-480` `attachJob`／`:556-586` `loadEditors`／`:558` `stepIndex.value !== 0`／`:577-580` 空模板回落／`:588-597`／`:609-646` `flushEditors`／`:658-680` `goStep`／`:670` `setStep`／`:673-678` 懒加载钩子／`:688-701` `syncStepToBackend`／`:752-800` `beginWatchJob`／`:881-907` `loadStaging`／`:909-920` `onImportModeChange`／`:935-950`／`:952-981`／`:1015-1019`／`:1047-1058`；模板 `:33-35`/`:36-46`/`:47-49`/`:63-69`/`:79-81`/`:112-114`/`:133-136`/`:137`/`:172-174` **全中**【实测】 |
| `frontend/src/views/TaskCenterView.vue`（**357 行，与卡一致**） | **全中**：`:47` `@click="restoreWizard(row)"`／`:268-271` `restoreWizard(row)`／`:58-62`（实为 53-62）状态列【实测】 |
| `frontend/src/components/JobProgress.vue` | **全中**：`:8`「取消作业」按钮（`v-if="running"`，`:103` `running` 定义）【实测】 |
| `backend/.../task/service/TaskService.java` | **全中**：`:143` `goToStep`／`:185` `complete()`／`:189` `setCurrentStep("DONE")`／`:216` `applyJobOutcome`【实测】 |
| `backend/.../job/service/JobTerminalWriter.java` | **全中**：`:79-91`（`:84` `applyJobOutcome`、`:86` `JOB_DONE`）【实测】 |
| `backend/.../job/repo/JobRepository.java` | **全中**：`:15` `findByTaskIdOrderByCreatedAtDescIdDesc`【实测】 |
| `backend/.../task/entity/Task.java` | **全中**：`:37-38` `@Version` + `private Long version`【实测】 |
| `backend/.../job/service/ImportJobRunner.java` | **全中**：`:97` `deleteByTaskIdAndDefCode`（按 defCode 重导前清）【实测】 |
| `backend/.../task/controller/TaskController.java` | **全中**：`:81-83`（实为 `:81-82`）`GET /{id}`／`:56-63`（`:56-57`）`overview`／`:166-168`（`:166-167`）`files`／`:64-71`（`:64`）`toSummary`【实测】 |

#### ⚠️ 已漂移的锚点（需刷新）

**（1）`frontend/src/views/ExportWizardView.vue` —— 1224 行（卡写 1083 行，+141）；脚本区 21 处全漂**

模板区（`:1-107`）**未漂**：`:2-13`／`:11`／`:42` 预览行数／`:57-59` 开始·重新导出／`:62-68` 下载类／`:69` 保存编辑／`:89-93` 勾选／`:100-108` SpreadGrid／`:202` `counts` **全部命中**【实测】。

| 卡锚点 | 现态锚点 | 漂移 | 备注 |
|---|---|---|---|
| `:325-341` `loadTask()` | **`:320-350`** | −5 | ★**签名已变**：`async function loadTask(preloaded?: Task): Promise<TaskLoadOutcome>`（新增可选预载入 + 返回 `{taskOk, filesOk}`） |
| `:351-374` `loadExistingResults()` | **`:359-`** | +8 | ★**签名已变**：`Promise<boolean>` |
| `:388-397` `resetTaskScopedState()` | **`:396-`** | +8 | |
| `:406-412` `stopJobWatchForTaskSwitch()` | **`:420-425`** | +14 | |
| `:455-466` `goStep(next)` | **`:469-480`** | +14 | 仍为同步 `void`，与卡一致 |
| `:461` `persistStepData()` | **`:475`** | +14 | |
| `:462` `syncStepToBackend()` | **`:476`** | +14 | |
| `:463-465` `next===2 → refreshResults` | **`:477-479`** | +14 | |
| `:498-521` `persistStepData()` | **`:512-`** | +14 | |
| `:580-613` `beginWatchJob(jobId)` | **`:594-`** | +14 | |
| `:615-624` `refreshResults()` | **`:629-`** | +14 | |
| `:653-681` `ensureGridLoaded(code)` | **`:667-`** | +14 | ★**签名已变**：返回 `{ok: boolean; error: string}`（R-84/DC-14 结构化结局已落地） |
| `:781-809` `saveEdits()` | **`:862`** | +81 | `download_export_file` 即发即回改造在此区间插入约 79 行 |
| `:895-903` `export.nextStep` | **`:1021-1030`** | +126 | |
| `:949-978` `PAGE_HANDLER_OPEN_EXPORT_EDITOR` | **`:1075-1104`** | +126 | ★失败路径已改为 **`throw new Error(...)`**（`:1083`/`:1086`）与如实失败文案（`:1099-1101`） |
| `:962-964` `goStep(2)` | **`:1088-1090`** | +126 | |
| `:981-999` `download_export_file` 执行器 | **`:1119-1140`** | +138 | ★已"即发即回"（批 A：`preflightDownload` + `startDownloadInBackground`，不再 `await` 下载链路） |
| `:994-996` `goStep(2)` | **`:1013-1015`（在 `preflightDownload` 内）** | +19 | |
| `:1002-1006` `onMounted` | **`:1143-1147`** | +141 | |
| `:1046-1056` `watch(route.query.taskId)` | **`:1187-`** | +141 | |
| `:1071-1082` `watch(workspace.step)` | **`:1212-`** | +141 | |

**（2）其余零散漂移**

| 卡锚点 | 现态 | 漂移 | 性质 |
|---|---|---|---|
| `ImportWizardView.vue:883` `loadStaging` 依赖 `importJob.value?.id` | **`:882`** | −1 | 纯行号 |
| `ExportWizardView.vue:602`（S1b 引的"问题清单同形写法"：`currentIssues.value = (await jobsApi.getIssues(job.id)).content ?? []`） | **`:616`**（原文逐字一致） | +14 | 纯行号；卡的告诫「导出页**没有** `refreshIssues`，不要照搬导入页的名字」**仍成立**（导入页已有 `refreshIssues(kind, jobId)` @ `:839`，导出页确无） |
| `stores/task.ts:356-362` `stepsOf` | **`:357-362`** | +1 | 纯行号 |
| `stores/workspace.ts:360-369` `ui_event: goto_step` | **`:370-378`** | +10 | 纯行号（`PUT /tasks/{id}/step` @ `:375` 不变） |
| `JobController.java:36-38` | **`:34-35`** `listJobs` | −2 | ★**卡写作时即偏**（该文件自 `d3020ee` 起未改动，非本区间漂移） |
| `JobController.java:41-44` | **`:39-40`** `getJob` | −2 | 同上 |
| `JobController.java:56-61` | **`:58-59`** `getIssues` | +2 | 同上 |
| `JobController.java:64-70` | **`:65-66`** `getDiff` | +1 | 同上 |
| `PrecheckJobRunner.java:273-274`（0 错误 → `JobStatus.COMPLETED`） | **`:267-268`** | −6 | ★**卡写作时即偏**（该文件自 `d3020ee` 起未改动） |
| `node_modules/element-plus/es/components/step/src/item.d.ts` | **路径不存在**；实为 **`.../steps/src/item.d.ts`** | 路径错 | 2.14.7 目录结构为 `steps/src/{item,steps}.d.ts` |

### 2.b 基线数字核对

| 基线 | 卡所述 | 实测 | 结论 |
|---|---|---|---|
| Element Plus 实装版本 | 2.14.7（声明 `^2.9.3`） | **2.14.7** | ✅ 成立【实测】 |
| `el-steps` 无 `status` prop | props 仅 `space/active/direction/alignCenter/simple/finishStatus/processStatus` | **`StepsProps` 确认无 `status`** | ✅ 成立【实测】 |
| `el-step` status 六档、无 cancelled | `'' \| 'wait' \| 'process' \| 'finish' \| 'error' \| 'success'` | **完全一致** | ✅ 成立【实测】 |
| `JobRepository:15` DESC 序 | createdAt DESC, id DESC | 一致 | ✅ 成立【实测】 |
| `Task` 有 `@Version`（version 不变 ≡ 任务行零写） | `:37-38` | `:37-38` | ✅ 成立【实测】 |
| `ImportWizardView` O-2 缺陷 | `:448-449` `slice(-1)[0]` 取最早 | **`:449` 原文仍在** | ✅ 缺陷**仍未修**（O-2 仍开） |
| `ExportWizardView` 无 `resumeJobs`（O-3） | 无 | **`resumeJobs` 在该文件零命中** | ✅ 缺陷**仍未修**（O-3 仍开） |
| `SpreadGrid` 有 `readOnly` 但**无调用点** | 是 | `readOnly` prop `:56`/默认 `:64` ✓；`views/`+`components/` 下 `read-only` 调用点 **0 处** | ✅ 成立【实测】 |
| `DefSelector` 无 disabled/selectable | 是 | props `:58-70` 仅 `defs/modelValue/loading/height`；`el-table-column type="selection"` @ `:21` 无 `selectable` | ✅ 成立【实测】 |
| `ConditionForm` 无 disabled | 是 | props `:99-108` 仅 `fields/modelValue/hint` | ✅ 成立【实测】 |
| 既有 `:disabled="!operatorNeedsValue(...)"` / `fields.length === 0` | 存在，不得覆盖 | `:54` / `:70` 均在 | ✅ 成立【实测】 |
| `TaskWizard:17` `:status` 是无效属性（attr fallthrough） | 是 | `el-steps` 无此 prop，确认 | ✅ 成立【实测】 |
| 前端无测试框架 | `package.json` 仅 `dev/build/typecheck/preview` | **一致**（devDeps 无 vitest/jest/@vue/test-utils） | ✅ 成立【实测】 |
| `node scripts/ci/check-docs.mjs` 断言数 | 6 条 | **仍为 6 条**（`:523-528`） | ✅ 成立【实测】 |
| `TaskService.complete()` 无调用点（`currentStep` 永不为 `DONE`） | 是 | `complete()` @ `:185` 存在；全仓零调用（本棒未复跑全仓 grep，采信卡内实读） | ⚠️ **未复验**（低风险，建议施工棒首次 grep 复核） |
| `docs/M2-排期计划.md` S4 追加点：末行 `R-93`（`:285`）之后 | 是 | **`R-93` 确在 `:285`，但其后已排到 `R-131`**（`R-94`～`R-99` @ `:286-291`，`R-111`～`R-131` 更下） | ❌ **不成立**（见 2.d） |

### 2.c 与批 B 改动面的文件级重叠

批 B 改动面（任务卡所述）：`backend` 的 `ExportJobRunner.java` / `PublishJobRunner.java` / `JobProgressService.java` / `AppProperties.java` / `application.yml` + 新建 `JobHeartbeatRegistry.java` / `JobWatchdog.java` + `scripts/verify-e2e.ps1` + docs 台账。

| 卡①改动文件全集 | 与批 B 重叠？ |
|---|---|
| `frontend/src/components/TaskWizard.vue` | 否 |
| `frontend/src/components/wizard/DefSelector.vue` | 否 |
| `frontend/src/components/wizard/ConditionForm.vue` | 否 |
| `frontend/src/views/ImportWizardView.vue` | 否 |
| `frontend/src/views/ExportWizardView.vue` | 否 |
| `docs/M2-排期计划.md`（S4 仅追加 1 行） | **是 —— 同为台账 `docs/M2-排期计划.md`** |

**结论：卡① 与批 B「可并行」（生产代码零重叠；批 B 全部落在 `backend/**` + `scripts/verify-e2e.ps1`，卡① 全落在 `frontend/**`）。唯一冲突是台账同一文件同区追加 —— 须错开写入顺序，或把卡① 的 S4 追加放到批 B 台账写入之后（同一行区串行）。** 【实测 + 推断】

> 附带提示：卡① 的 S4 验证含 `node scripts/ci/check-docs.mjs`；该脚本断言①扫"导航/口径文档无盘符绝对路径"，只扫 `docs/` 下纳入范围的文档，卡① 只动 `docs/M2-排期计划.md` 一行，风险低，但施工后仍须实跑取 exit 0 证据。

### 2.d 验收标准可执行性

| 验收项 | 卡所述 | 实测 | 结论 |
|---|---|---|---|
| `yarn typecheck` / `yarn build` | 每步双绿 | `package.json` 脚本 `typecheck` = `vue-tsc --noEmit`、`build` = `vue-tsc --noEmit && node scripts/build.mjs` | ✅ 可执行【实测】 |
| `node scripts/ci/check-docs.mjs` | S4 后 exit 0（6 条断言） | 脚本存在，6 条断言 | ✅ 可执行【实测】 |
| S4 追加台账 `R-94` | 追加一行 `R-94`，紧接 `R-93`（`:285`）之后 | ❌ **`R-94` 已被占用**（`:286`「UI 包拆除配套收尾 · **正式立项**」，来源 GLM 红队 UI 包评审 MAJOR-1） | ❌ **必须先改号**（当前最大号 `R-131`，下一空号 = **`R-132`**）；且追加点已下移至表末（不再是 `:285` 之后） |
| S4 `git diff --numstat docs/M2-排期计划.md` 只增不减 | 是 | 该文件仍在，纪律可执行 | ✅ 可执行【实测】 |
| 手测路径（8081/5200、H2 控制台、`/h2-console`） | 是 | 未启动环境复核，仅确认 `application.yml` 存在、`vite.config.ts` 存在 | ⚠️ 未实测（卡内数值口径未复验，风险低） |
| 零写探针脚本落仓库外 | `zero-write-probe.mjs`（建议） | 无需入仓，卡内方案自洽 | ✅ 可执行【推断】 |

**★ 卡① 的 2 处实质影响（不止行号）**：

1. **S4 的 `R-94` 编号冲突**：`R-94` 已被"AI 发布冲突提示落点重建立项"占用并标为**正式立项**。卡① 若照字面追加 `R-94`，会造成 **两个 `R-94`**，直接破坏台账唯一性纪律。须改号（建议 `R-132`）并在施工报告登记改号原因。
2. **S1b / S3e 的落点签名已变**：`ExportWizardView.loadTask` 现在接收可选 `preloaded` 并返回 `TaskLoadOutcome`、`loadExistingResults` 返回 `boolean`、`ensureGridLoaded` 返回 `{ok, error}`、`download_export_file` 已"即发即回"、`PAGE_HANDLER_OPEN_EXPORT_EDITOR` 已用 `throw` 表达失败。S1b（插 `resumeJobs`）与 S3e（挡 AI 通道）的**实现指令需按新签名对齐**，不能照抄卡里的旧描述。

---

## 3. 卡② 对话时序包（`<LOCAL_WORK>\chat-timeline-pkg\施工卡.md`）

### 3.a 代码锚点核对

#### ✅ 仍命中（0 漂移或 −1）

| 文件 | 结论 |
|---|---|
| `frontend/src/types/sse.ts`（**自 `d3020ee` 起未改动**） | **全中**：`:8-10`（无名 SSE + `type` 在帧内）／`:82-98` `SseFrameBase.seq?/atMs?`／`:122-128` `message_start`/`message_end` + `messageId` + `kind`／`:131-155` `SseToolStartFrame`／`:158-176` `SseConfirmRequestFrame`（`:167-169` `expiresAt`/`atMs` 口径注释）／`:192-202` `SseFrontendToolRequestFrame`／`:323-326` `frameSeq()`【实测】（唯一偏差：`:234-247` 的 `SseReasoningFrame` 实为 `:242-247`，**卡写作时即偏**）**〔2026-10-12 批4④刷新：逐点实读，7 子锚点全中（0 漂移）；`d897096..296ffe2` 该文件 diff 为空〕** |
| `frontend/src/types/tools.ts` | **已漂**：`:115-119` → **`:132-148`**（+17，批3③a）：`FrontendToolExecution` **已新增 `outcome: FrontendToolOutcome`（`:140`）**，`ok` 收窄为 `ok === (outcome === 'ok')`（注释 `:129-130`；推导点 = `utils/frontend-tools.ts:107` 的 `makeExecution`，工厂本体 `:100-116`）⇒ 卡的 **P1 前置已落地**【实测·2026-10-12 刷新】 |
| `backend/.../ai/run/SseChatEmitter.java`（**自 `d3020ee` 起未改动**） | 实质全中，±1～3：`textStart()` `:516`／`textEnd()` `:525`／`toolStart` `:551`／`toolResult` `:560`／`confirmRequest` `:575`／`frontendToolRequest` `:599`／`frontendToolResult` `:613`／`frame()` 的 `atMs` `:680`／**`synchronized (this.emitLock)` `:729`（与卡完全一致）**／`heartbeatSeq` `:737`／`nextSeq()` `:700`【实测】**〔2026-10-12 批4④刷新：逐点实读，全部仍中（0 漂移）〕** |
| `backend/.../ai/run/ResilientChatService.java` | **`textEnd` 四处在 `:477 / :510 / :541 / :568`——与卡完全一致**；`textStart()` `:628`（落卡所引 `:613-631` 区间内）【实测】**〔2026-10-12 批4④刷新：逐点仍中（0 漂移）〕** |
| `backend/.../ai/memory/RedisChatMemoryRepository.java` | **已漂（批1 Lua 原子化 R-131/R-133）**：`:33-35` → **`:35-37`**（`chat:mem:<id>` LIST / `chat:mem:__ids__` SET）／`:53` → **`:61`** `KEY_PREFIX`／`:55` → **`:63`** `CONVERSATION_IDS_KEY`（另 `:110` → **`:138`** `SISMEMBER` 单点判存为新增）【实测·2026-10-12 刷新】 |
| `frontend/src/api/sse.ts` | `consumeFrame` `:164`／`state.lastSeq = max(...)` **`:172`**（卡 `:169-173`，+3）／`onmessage` `:560`——三处**至 HEAD 未再漂**；`reattach` 区 `:424-600` → **`:424-593`**（文件总行数 593）【实测·2026-10-12 刷新】 |
| `frontend/src/utils/frontend-tools.ts:314-321` `executeFrontendTool` | **`:347-353`**（+33；批3③a 三态 outcome 契约落地后重排）【实测·2026-10-12 刷新】 |
| 证据/调研文档 | `docs/research/对话时序混排-业界调研.md` **EXISTS**／`docs/evidence/S42-P2-附件B-RunStore与台账原文.md:151-160` **命中**（`[1] USER … [6] ASSISTANT` 逐条形态与卡一致）／`docs/evidence/S42-P1-核心运行时验证.md:391` **命中**（"`toolCalls/toolResponses` 从不落记忆"）／`docs/evidence/S44a-前端基座验证.md:110` **命中**／`docs/M2-排期计划.md:198`（R-06）、`:276`（R-84）**命中**【实测】**〔2026-10-12 批4④刷新：四份文档 `d897096..296ffe2` diff 为空，逐点仍中；台账 `:198`/`:276` 行号亦未漂〕** |

#### ⚠️ 已漂移的锚点（约 85 处，量最大）

**（1）`frontend/src/types/ai.ts` —— 8 处，体检时点统一 +2；至 HEAD `296ffe2` 已非统一（+2～+113；文件 256 行 → **442 行**）**

| 卡锚点 | 现态 |
|---|---|
| `:132-141` `AiHistoryMessage` | `:134-143`（未再漂） |
| `:161` `ToolRunStatus` | `:165` |
| `:164-177` `ToolRun` | `:168-181` |
| `:180-195` `ChatMessage` | `:184-217`（已增 `parts: MessagePart[]`） |
| `:198-226` `PendingToolCall` | `:297-325` |
| `:225` `PendingToolCall.status` | `:324` |
| `:229-234` `AiSessionMirror` | `:337-344`（已增 `v` + `parts`） |
| `:244-254` `ActiveGenerativeForm` | `:361-371` |

**内容已变**（2026-10-12 刷新）：`ChatMessage` 已增 `parts: MessagePart[]`（批2 权威翻转 ⇒ **P2 的「无 parts」面已消解**）；`ToolRunStatus` 已扩为 **9 态**（+`unknown`，`:165`）而 `PendingToolCall.status` 仍 8 态（`:324`）⇒ **P2 的「两枚举不一致」面仍成立**；另新增 `TextPart`/`ReasoningPart`/`ToolPart`/`MessagePart`/`ToolPartPatch`/`MIRROR_VERSION = 2`（`:219-351`）【实测·2026-10-12 刷新】。

**（2）`frontend/src/stores/ai.ts` —— ≈50 处，体检时点最大位移 +86；至 HEAD `296ffe2` 最大位移 **+395**（较 `d3020ee`）／**+362**（较卡锚点最靠后的 `:1490-1511`）**

原文件 **1513 行**（`d3020ee`）→ 体检时点 **1599 行**（+86）→ **HEAD `296ffe2` = 1908 行**（较 `d3020ee` +395；批1/批2/③a/③b 已入库）。下表「现态」列为 2026-10-12 批4④前置刷新按 HEAD 实读值。

| 卡锚点 | 现态 | 漂移 |
|---|---|---|
| `:31-42` 轮次身份守卫（`currentAbort`/`activeRoundHostId`/`formExpiryTimer`） | `:42` / `:50` / `:52` | +11 |
| `:136-146` `readMirror` | `:146` | +10 |
| `:148-167` `writeMirror` | **`:281`** | +133 |
| `:291-306` getter `pendingToolCall` | **`:461`** | +170 |
| `:308-311` getter `pendingConfirm` | **`:478`** | +170 |
| `:351-362` `startMirror` | **`:521`** | +170 |
| `:360` `mirrorTimer` | **`:62`（模块级声明）/ `:527-530`（订阅内防抖写盘）** | — |
| `:379-392` 工作区事件订阅 | **`:543-564`** | +164 |
| `:421-480` `loadHistory` | **`:596-709`** | +175 |
| `:423-431` 镜像分支 / `:441-446` / `:476-479` | `:600-627` / `:633-635` / `:642-644` | +177 / +192 / +166 |
| `:487-574` `restoreSuspendedSnapshot` | **`:725-817`** | +238 |
| `:500` 调用点 / `:511` `runsCurrent` await | **`:659` + `:708`（两处调用）/ `:730`** | +159 / +219 |
| `:527-534` 宿主消息定位 | `:773-777` | +246 |
| `:544-550` `upsertToolRun` | **`:785`（已更名 `upsertToolPart`）** | +241 |
| `:580-624` `resumeSnapshotStream` | **`:823-`** | +243 |
| `:635-718` `send` | **`:883-`**（`recordUserMessage`/`appendAssistantMessage` 调用 @ `:896-897`） | +248 |
| `:659-660` / `:798-802` 身份守卫 | **`:904-908` / `:1058-1062`**（`const isCurrentRound = (): boolean => currentAbort === controller` @ `:908` / `:1062`） | +245 / +260 |
| `:780-847` `reattachActive` | **`:1039-`** | +259 |
| `:789` 「正在重挂进行中的轮次…」 | **`:1048`** | +259 |
| `:849-928` `submitFrontendToolResult`/`confirm` | **`:1114-1168` / `:1178-1226`** | +265 / +329 |
| `:935-940` `hasDecidedPendingResidue` | **`:1235`** | +300 |
| `:948-990` `openGenerativeForm` | **`:1252`** | +304 |
| `:984` `formExpiryTimer` | **`:52`（声明）/ `:1292-1296`（武装）** | +308 |
| `:1049-1074` `stop()` | **`:1357`** | +308 |
| `:1077-1102` `resetSession` | **`:1401`** | +324 |
| `:1106-1134` `recordUserMessage`/`appendAssistantMessage` | **`:1466` / `:1484`** | +360 / +374 |
| `:1136-1148` `currentAssistantMessage` | **`:1503`** | +367 |
| **`:1151-1399` `handleFrame`** | **`:1517-1756`** | **+366** |
| `:1151-1188` 前置守卫 | `:1518-1574` | +367 |
| `:1157` `ownedByActiveRound` 判定 | **`:1523`** | +366 |
| `:1158-1166` 宿主消息查找 | `:1524-1527` | +366 |
| `:1168-1175` `runSeq` 记最大 seq | `:1534-1541` | +366 |
| `:1177-1188` 旧轮迟到帧分支 | `:1543-1554` | +366 |
| `:1191-1194` `start` | **`:1577`** | +386 |
| `:1195-1199` `message_start` | **`:1581-1587`** | +386 |
| `:1200-1203` `message_end` | **`:1582`（与 `message_start` 合并为同体双 case）** | +382 |
| `:1204-1207` `delta`（含 `:1205` `content +=`） | **`:1588-1593`**（`message.content += frame.text` @ `:1591`） | +384 |
| `:1208-1211` `reasoning` | **`:1595-1600`** | +387 |
| `:1212-1221` `tool_start` | **`:1601-1604`** | +389 |
| `:1222-1243` `tool_result`（`blocked`/`rejected` 三态分色 `:1230-1241`） | **`:1605-1610`**（三态裁决已移入 `utils/parts.ts#applyFrameToParts`） | +383 |
| `:1244-1256` `confirm_request` | **`:1611-1623`**（③b D8 已补已决守卫 `:1615-1617`） | +367 |
| `:1257-1281` `confirm_decision` | **`:1624-1630`** | +367 |
| `:1282-1300` `frontend_tool_request`（守卫 `:1283-1288`、`openGenerativeForm` `:1291-1295`、`runFrontendTool` `:1298`） | **`:1631-1655`**（守卫 `:1636-1641`、`openGenerativeForm` `:1649`、`runFrontendTool` `:1653`） | +349 |
| `:1301-1336` `frontend_tool_result`（守卫 `:1308-1310`、`:1324`、`:1325-1333`） | **`:1656-1676`**（表单收敛 `:1665-1673`） | +355 |
| `:1337-1359` `suspended` | **`:1677-1698`**（③b D8 逐条目判已决 `:1686-1690`） | +340 |
| `:1360-1365` `heartbeat`/`retry` | **`:1699` / `:1702`** | +339 / +337 |
| `:1366-1380` `done`（含 `:1369` 清 `pendingCall`） | **`:1705-1724`**（清理分支 `:1714-1718`） | +339 |
| `:1381-1390` `error` | **`:1725-1737`** | +344 |
| `:1396-1398` 收尾 → `loading=false` | **`:1753-1755`** | +357 |
| `:1402-1419` `toPendingCall` | **⚠️ 失锚（已删）** | — |
| `:1426-1434` `applyPendingTerminal` | **`:1765`** | +339 |
| `:1444-1465` `applyFrontendToolTerminal` | **`:1801`** | +357 |
| `:1468-1478` `upsertToolRun`（`:1473` spread 合并） | **`:1838`（已更名 `upsertToolPart`；spread 合并前移至 `utils/parts.ts`）** | +370 |
| `:1490-1511` `runFrontendTool`（`:1504` 二元落卡） | **`:1852-1906`**（三态 outcome 落卡 @ `:1889-1891`） | +362 |

**★ 卡①/② 的 §1.3 case 表已过时（不止行号）**：`stores/ai.ts` 现含卡外新增事实——issue#5 R2/R6 的**轮次身份守卫**（`ownedByActiveRound` 语义）、MAJOR-1 的**会话身份守卫**（`loadHistory` 内 `this.sessionId !== sid` 两处 `:449`/`:458` → **`:633`/`:642`**）、`stopped` 标志（`:268-269` → **`:430-437`** 注释口径）、`resetSession` 重写（`:1127` → **`:1401`**）、骨架屏 `historyLoaded`（`:429`）、`deleteHistory`（`resetSession` 内 `:1455`）。卡② 的 §1.3 表**未覆盖这些**，施工棒须补读。**★ 2026-10-12 批4④刷新**：上列卡外事实**已全部入库**（批1 `1efab09` R-130/R-132 会话＋轮次身份对账、批2 `6bea4ca` parts 权威翻转、③a `f0210f2` 三态 outcome、③b `fc6e5a2` 段状态机闸 + 死码清除）⇒ 卡② §1.3 表**已不可直接照用**（行号与语义双过时），④须按本报告新锚点整批替换，并对 `parts` 段模型重新划范围。

**（3）`frontend/src/components/AiPanel/AiPanel.vue` —— ≈18 处**

（原文件 **632 行**（`d3020ee`）→ 体检时点 **768 行**（+136）→ **HEAD `296ffe2` = 791 行**（+159）；增量集中在 `:23-135` 骨架屏/新建对话/复制按钮区与 `:162-196` 段渲染区，故越靠后的锚点位移越大）

| 卡锚点 | 现态 | 漂移 |
|---|---|---|
| `:121-126` 消息流 `v-for="m in ai.messages"` | **`:142`** | +21（`:122` 新增 `data-testid="history-skeleton"` 骨架屏；至 HEAD 未再漂） |
| `:138-140` `isTyping` 三点闪烁 | **`:158`** | +20（未再漂） |
| `:142-146` 思考链 `<details>` | **`:174-177`** | +32（改由 `reasoning` 段渲染） |
| `:148` 正文 `v-if="m.content"` | **`:171`**（`v-if="part.kind === 'text' && part.text"`） | +23（判据已改 parts，N10） |
| `:151-166` 工具卡 `v-for="run in m.toolRuns"` | **`:179-196`**（改 `v-for="part in m.parts"` 的 tool 段分支） | +28 |
| `:169-255` 挂起卡三分支 | **`:201-265`** | +32 |
| `:206-215` `FormRenderer` 挂载 | **`:237-246`** | +31 |
| `:245` FRONTEND 兜底 else →「已执行」 | **⚠️ 失锚（已删）**：③b D2 删该死码；活分支只剩 `:247-258`（`activeForm` 提交窗口） | — |
| `:253` CONFIRM 兜底 else →「执行失败」 | **⚠️ 失锚（已删）**：③b D2 整列删除（原 `:264-277`，留痕注释 @ `:261-264`） | — |
| `:408-411` / `:419-432` `formHostMessageId` 跨消息反查 | **`:476-489`（`formHostMessageId` @ `:476`）** | +68 |
| `:444-446` `isTyping` 定义 | **`:506`** | +62 |
| `:453-470` `toolTagType` | **`:515`**（③a D10(A) 已删 `default:`，九态显式 case） | +62 |
| `:472-484` `toolStatusLabel` | **`:544`**（`Record<ToolRunStatus, …>` 穷尽） | +72 |
| `:460-465` / `:478-481` `blocked` 分色 | **`:528-531`**（`case 'blocked'` @ `:530`；另 `:537-540` 为 `unknown` 分色） | +70 |
| `:558-570` `resetPendingWatch` | **`:707-719`** | +149 |
| `:572-578` 滚动 watcher 签名 | **`:724-733`**（签名**已改为 parts 口径**，批2） | +152 |
| `:169` `data-testid="pending-card-"` | **`:201`** | +32 |
| `:323-326` `now`/`confirmDeadline` | **`:381-383`**（`now` @ `:381`、`confirmDeadline` @ `:382`、`tickTimer` @ `:383`） | +58 |

**（4）`frontend/src/utils/frontend-tools.ts` —— 6 处，体检时点统一 +6（仅 `executeFrontendTool` +1）；至 HEAD 为 +32～+38（批3③a 三态 outcome 契约落地后重排；文件 898 行 → **1098 行**）**

| 卡锚点 | 现态 |
|---|---|
| `:314-321` `executeFrontendTool` | **`:347-353`**（+33） |
| `:349-374` `runWithBudget` | **`:383-`**（+34） |
| `:405-420` `timeoutOutcome` | **`:443-459`**（+38） |
| `:415-418` 文案「本次调用结局未知」 | 现于 **`:453` / `:455`**（原文案保留，措辞扩写为「前端超时：…本次调用结局未知 —— 既未确认成功，也未确认失败。」）**且已补 `outcome: 'unknown'`（`:459` 经 `makeExecution`）⇒ N3/P1 已随 ③a 落地（不再是"仍成立"）** |
| `:802-845` `runRegisteredTool`（成功支 `:836`） | `:845-`（成功支 **`:897`** 经 `makeExecution({ outcome: 'ok', … })` 推导 `ok`，不再是硬编码 `ok: true`）⇒ **R-84 已随 ③a 修**【实测·2026-10-12 刷新】 |
| `:869-891` `runPageHandler`（成功支 `:877-881`） | `:936-`（成功支 **`:947-953`** 走 `interpretHandlerOutcome`（`:970-989`）+ `makeExecution`）⇒ **R-84 已随 ③a 修**【实测·2026-10-12 刷新】 |

**（5）`frontend/src/views/ExportWizardView.vue:969-976` / `:973-975`（③a 的 R-84 落点）**

现态 **`:1263-1310`**（`registerPageHandler(PAGE_HANDLER_OPEN_EXPORT_EDITOR, …)`；`ensureGridLoaded` 结局校验 @ `:1298-1308`，两处 `throw` @ **`:1271`/`:1274`**）：**已实质落地**（`ensureGridLoaded`（定义 @ `:814`）返回 `{ok,error}`，失败时 `return` 如实失败文案，不再回灌"已打开在线编辑器"；另 `:1271`/`:1274` 改用 `throw`）。卡的 ③a 在**页面 handler 这一面已被别的批次修掉**；至 HEAD，`types/tools.ts#outcome`、`runRegisteredTool`/`runPageHandler` 结构化、`stores/ai.ts#runFrontendTool` 三态落卡**亦已全部落地**（③a `f0210f2`），③a 面**已整体闭合**。【实测·2026-10-12 刷新】

**（6）已漂移的验证引用**

| 卡锚点 | 现态 |
|---|---|
| `api/sse.ts:169-173` `consumeFrame` 只记 max 不去重 | `:172`（至 HEAD 未再漂） |
| `stores/ai.ts:1205` `content += frame.text` | **`:1591`**（区间 `:1588-1593`） |

### 3.b 基线数字核对

| 基线 | 卡所述 | 实测 | 结论 |
|---|---|---|---|
| 前端零测试框架 | 无 `test` 脚本、无 vitest/jest | **★ 2026-10-12 刷新：已不成立** —— `frontend/package.json` 现有 `test: vitest run`，devDeps 含 `vitest`/`@vue/test-utils`/`jsdom`，`frontend/tests/*.spec.ts` 共 12 个（R-91 `27797dc`/`4d9f984` 起） | ❌ **已不成立**（R-91 vitest 基建已入库） |
| CI 前端 job = typecheck + build | 是 | 未复读 `ci.yml`（本棒未读），卡内已实读 | ⚠️ 未复验 |
| E2E = `scripts/verify-e2e.ps1`，**29 用例**、后端 API 级、不驱动 UI | 是 | 脚本存在；头部注释 `:41` **仍写"29 个用例"**（2026-10-12 复核：`:41` **未漂**；TC1–TC22 + TC23 + GF1–GF5 + GF6），`TC*` 标记实测 23 个 | ✅ **29 用例口径成立**【实测·2026-10-12 刷新】 |
| `E2E_STUB_MODE=1` + redis | 是 | 未复验 `ci.yml` | ⚠️ 未复验 |
| P1：`FrontendToolExecution` 无"结局未知"载体 | 是 | **★ 2026-10-12 刷新：已落地** —— `types/tools.ts:115-119` → **`:132-148`**，已增 `outcome: FrontendToolOutcome`（`:140`）+ `makeExecution`（`utils/frontend-tools.ts:100-116`）单点推导 `ok`（`:107`） | ❌ **已不成立**（③a `f0210f2` 已补"结局未知"载体） |
| P2：现存状态枚举 9 态且两枚举不一致 | `types/ai.ts:161`（7 态）vs `:225`（8 态） | **2026-10-12 刷新**：`:165`（`pending running succeeded failed rejected expired blocked unknown cancelled` = **9** 态）vs `:324`（+`approved` = 8 态） | ✅ 成立（行号与口径订正：体检时点为 7 vs 8，HEAD 现为 **9 vs 8**）【实测】 |
| P3：②③④ 无 CI 自动化兜底 | 是 | 一致 | ✅ 成立（★刷新注记：前端已有 vitest，但**未接入 CI**，见上一行） |
| `docs/M2-排期计划.md:198`（R-06）`stores/ai.ts` 落点 `:1481-1501` | 卡内引用表行 | 行 **`:198` 命中**，但其正文写的 `stores/ai.ts:1481-1501` 已漂到 **`:1852-1906`**（`runFrontendTool`） | ⚠️ 台账内锚点亦漂（不影响卡②施工，影响引用一致性） |

### 3.c 与批 B 改动面的文件级重叠

| 卡②改动文件全集 | 与批 B 重叠？ |
|---|---|
| `frontend/src/types/ai.ts` | 否 |
| `frontend/src/types/tools.ts` | 否 |
| `frontend/src/stores/ai.ts` | 否 |
| `frontend/src/components/AiPanel/AiPanel.vue` | 否 |
| `frontend/src/utils/frontend-tools.ts` | 否 |
| `frontend/src/utils/blocks.ts`（新建） | 否 |
| `frontend/src/views/ExportWizardView.vue` | 否 |
| `backend/**` | **明确"不动"**（卡内 §3.3 护栏） |
| `scripts/verify-e2e.ps1` | **仅"运行"以验证，卡未要求修改**；但**批 B 要改它** |

**结论：卡② 与批 B「可并行」（生产代码零重叠，卡② 明确不改后端）。**
**⚠️ 但有一条时序风险**：卡② 大量使用 `E2E_STUB_MODE=1` + `scripts/verify-e2e.ps1` 作为"后端不动、结果应与基线一致"的回归判据；**批 B 正在修改该脚本**（卡② 基线 29 用例的计数口径可能随批 B 变化）。若批 B 先落地，卡② 的"29 用例与基线一致"这条判据须重取基线后再用。【推断】

### 3.d 验收标准可执行性

| 验收项 | 实测 | 结论 |
|---|---|---|
| `yarn typecheck` / `yarn build` | 脚本存在 | ✅ 可执行【实测】 |
| `powershell -File scripts/verify-e2e.ps1`（桩模式） | 脚本存在（155 KB） | ✅ 可执行；**计数口径受批 B 影响**（见 3.c）【实测】 |
| 仓库外 CDP 冒烟（`gfc-dualform-smoke/smoke.mjs` 同款） | 卡内明示为仓库外脚本，本棒未验证该路径存在 | ⚠️ 未实测（属"仓库外先例"引用） |
| `groupParts` 纯函数无框架验证（子包④） | 依赖"新增 `frontend/src/utils/blocks.ts`"，尚未建 | ✅ 方案自洽【推断】 |
| `data-testid="pending-card-"` 断言（③b） | **命中**：`AiPanel.vue:213` → **`:201`** 有 `:data-testid="'pending-card-' + m.pendingCall.toolCallId"` | ✅ 可执行【实测·2026-10-12 刷新】 |
| ③a 的结构化结局改造面 | ✅ **已全部落地（2026-10-12 刷新）**：页面 handler 面 `throw` @ `views/ExportWizardView.vue:1271`/`:1274`（handler 区 `:1263-1310`）；`types/tools.ts:115-119` → **`:132-148`** 已增 `outcome`（`:140`）；`runPageHandler:875-895` → **`:936-`** / `runRegisteredTool:808-850` → **`:845-`** 已结构化（`interpretHandlerOutcome` `:970-989`）；`runFrontendTool:1576` → **`:1852-1906`** 三态落卡 @ `:1889-1891`；`AiPanel` 两状态表已穷举 **9 态**（`:515-542` / `:544-560`） | ✅ **③a 已无剩余面**（③a `f0210f2` 已入库）；残面 = 台账 R-84 行尾注记登记的**只读/越界拒绝「字符串折 `ok`」同族 22 处**（DS Pro 复核 m-1），随 R-84 批结构化——④若含 ③a 收口须只针对该族 |

---

## 4. 卡③ 轮询治理一期（`<LOCAL_WORK>\polling-gov\施工卡.md`）

### 4.a 代码锚点核对

#### ✅ 核心文件全中（本卡是三卡里时效最好的）

| 文件 | 结论 |
|---|---|
| `frontend/src/stores/task.ts`（369 行） | **全中**：`:32` `TASK_AUTO_REFRESH_MS = 4000`／`:58` 模块级 timer 声明／**`:162-166` `setInterval`**／`:163-165` `if (!this.loading)`／`:114-116` `load()` loading 短路／`:131-142` `setPage`/`setSize`/`applyFilters`／**`:157-168` `startAutoRefresh`**／`:170-174` `stopAutoRefresh`／**`:294-321` `watchPublishOutcome`**／`:298` `waitForJobFinal`（**只给 `intervalMs`+`onTick`，确无 `signal`**）／`:308-311` 超时非终态 → `publishConflict = null`【实测】 |
| `frontend/src/utils/job-watch.ts`（123 行） | **全中**：`:21` `JOB_FALLBACK_POLL_MS = 3000`／**`:106-110` `setInterval`**／`:121` 首拉／`:86-104` `syncSnapshot`／`:96` 终态 `dispose()`／`:46-84` `watchJob`／`:61-73` 帧处理／**`:61-67` 无 HEARTBEAT/TASK_CHANGED 分支（丢弃）**／`:68-73` `JOB_DONE` → `syncSnapshot`／`:76-84` `onopen`→`realtime`、`onerror`→`polling`（**均不影响 3s poll**）／`:6-12` 注释口径【实测】 |
| `frontend/src/api/jobs.ts`（78 行） | **全中**：`:20` `getJob`／`:43-46` `WaitJobOptions`（**`:46` `signal?` 已在**）／**`:50-68` `waitForJobFinal`**／`:52` `timeoutMs ?? 300_000`／`:54` `for (;;)`／**`:63-65` 超时 `return job`（非终态返回、不抛错）**／`:66` sleep 1000ms／`:55-57` 新增 `signal.aborted` 早退（批 A 新增）【实测】 |
| `frontend/src/types/sse.ts`（自 `d3020ee` 未改动） | **全中**：`:8-10` 无名 SSE 形态／`:337` `TaskEventType`／`:340-347` `TaskChangedPayload`（`taskId/version/currentStep/status/summary`）／`:350-353` `JobDonePayload`（仅 `{jobId,status}`）／`:356-364` `JobProgressPayload`／`:367-369` `TaskEventFrame`【实测】 |
| `frontend/src/api/tasks.ts` | **全中**：`:10` `listTasks`／**`:105-115` `openTaskEvents`（只挂 `onmessage`，无 `onerror`/最大时长）**／`:106` `new EventSource('/api/tasks/{id}/events')`／`:107-113` 只解析 data【实测】 |
| `frontend/src/views/TaskCenterView.vue`（357 行） | **全中**：`:8`「· 列表每 4 秒自动刷新」／`:348-351` `onMounted` → `startAutoRefresh()`／`:354-355` `onBeforeUnmount` → `stopAutoRefresh()`／`:151` 注释「4s 自刷：store 的 setInterval」【实测】 |
| `frontend/src/components/wizard/*`（无关） | — |
| **后端** `task/service/TaskSseService.java` | **全中**：`:73` `subscribe`／`:88` `sendEvent(taskId, emitter, "HEARTBEAT", Map.of())`／`:92-101` `@EventListener onTaskChanged` → `TASK_CHANGED`【实测】 |
| **后端 4 个 `JOB_PROGRESS` 发布点** | **3/4 全中、1 处已随批 B 漂移**：`ImportJobRunner.java:216` ✅／`PrecheckJobRunner.java:360` ✅／`PublishJobRunner.java:327` ✅／**`ExportJobRunner.java:256` → ❌ 现为 `:259`**（批 B 正在改该文件，+3 行；见 §1.1）（注：四者实际目录为 `job/service/`，非卡内隐含的 `job/runner/`；卡只给文件名，不影响）【实测】 |
| `frontend/src/views/ImportWizardView.vue` | **`:752-800` `beginWatchJob`（定义 @ `:752`）**／`:730` `startJob` 内调用点（**卡中 `:756` 应为 `:752` 或调用点 `:730`**）／**`:799` `stopWatchers.push(dispose)` 前确无同 kind 回收**（卡 R-14 / N-1 缺陷**坐实仍在**）／`:993` `stopWatchers = []`（卡 `:992`，+1）／`:476` `attachJob` 内另一处 `beginWatchJob`【实测】 |

#### ⚠️ 已漂移的锚点（约 20 处，全为纯行号平移）

| 卡锚点 | 现态 | 漂移 |
|---|---|---|
| `stores/workspace.ts:398-407` `run_job`（PUBLISH 分支 `:401-404`） | **`:408-418`**（PUBLISH @ `:411`） | +10 |
| `stores/workspace.ts:432-439` `refresh_defs`/`refresh_data` | **`:455-467`** | +23 |
| `stores/workspace.ts:440-443` `refresh_tasks` | **`:470-478`** | +30 |
| `stores/workspace.ts:285-299` `onWorkspaceEvent` | **`:285`** | 0 ✅ |
| `stores/ai.ts:379-392` 唯一订阅者（`workspace_changed`/`ui_event_result`） | **`:385-396`** | +6 |
| `stores/ai.ts:360` `mirrorTimer` | **`:363-366`** | +3 |
| `stores/ai.ts:984` `formExpiryTimer` | **`:1018`** | +34 |
| `components/AiPanel/AiPanel.vue:595-597` 1s tick `setInterval` | **`:727-731`** | +132 |
| `AiPanel.vue:605-609` `clearInterval` 清理 | **`:739-741`** | +132 |
| `AiPanel.vue:323-326` `now`/`confirmDeadline` | ≈`:384-385` | +61 |
| `AiPanel.vue:558-570` `resetPendingWatch` | **`:690-703`** | +132 |
| `utils/frontend-tools.ts:354-358` 前端工具预算定时器 | **`:352-360`** | +6 |
| `components/SpreadGrid.vue:132` `readyTimer` | **`:137`** | +5 |
| `SpreadGrid.vue:175` `resizeTimer` | **`:180`** | +5 |
| `utils/excel.ts:847` Blob URL revoke | **`:847` 命中** | 0 ✅ |
| `views/ExportWizardView.vue:572` `startExport` | **`:569`** | −3 |
| `ExportWizardView.vue:581` `stopWatchJob?.()` | **`:595`** | +14 |
| `ExportWizardView.vue:407` 改绑回收 `stopJobWatchForTaskSwitch` | **`:420-425`** | +14 |
| `ExportWizardView.vue:586` `beginWatchJob(job.id ?? 0)` | **`:586` 命中** | 0 ✅ |
| `ExportWizardView.vue:1009` 卸载回收 | **`:1149-1153`** | +140 |
| `ExportWizardView.vue:602` 导出页 `currentIssues` 写法 | **`:616`** | +14 |

### 4.b 基线数字核对

| 基线 | 卡所述 | 实测 | 结论 |
|---|---|---|---|
| 有网络请求的周期轮询 = **3 条** | task.ts 4s / job-watch 3s / jobs.ts 1s | 三条全部命中且语义未变 | ✅ **成立**【实测】 |
| 全仓 `setInterval` = **3 处** | `stores/task.ts:162` / `utils/job-watch.ts:106` / `AiPanel.vue:595` | **3 处**，行号为 `:162` / `:106` / **`:727`** | ✅ 成立（AiPanel 行号 +132）【实测】 |
| 全仓 `EventSource` 构造 = **2 处** | `api/tasks.ts:106` / `api/sse.ts:552` | **2 处，两行号完全一致** | ✅ 成立【实测】 |
| `visibilitychange`/`document.hidden` = **0 处** | 0 | **0** | ✅ 成立【实测】 |
| 任务中心是"零 SSE 页" | 不 import SSE | `TaskCenterView.vue` 无 `openTaskEvents`/`EventSource` | ✅ 成立【实测】 |
| `TASK_CHANGED` 前端零消费者 | 是 | `job-watch.ts` 只判 `JOB_PROGRESS`/`JOB_DONE`；任务中心无订阅 | ✅ 成立【实测】 |
| `latestJob` / `hasRunningJob` getter 已被 UI 包删除 | 是 | `stores/task.ts` 内两者**零命中** | ✅ 成立（step-1 需重建谓词的前提仍有效）【实测】 |
| `:8` 文案未改 | 「列表每 4 秒自动刷新」 | **`:8` 原文在** | ✅ 成立（step-1 须同改）【实测】 |
| 台账 `R-88 :280` / `R-89 :281` / `R-26 :218` / `R-14 :206` | 是 | **四条行号全中** | ✅ 成立【实测】 |
| R-88 行内写的 `startAutoRefresh:182-199` 已漂 | 卡 N-4 已登记 | 现态 `:157-168`；台账 `:280` 仍写 `:182-199` | ✅ 卡 N-4 判断正确【实测】 |
| N-4：`views/TaskListView.vue` / `export/StepExport.vue` 不存在 | 是 | **两者均 MISSING** | ✅ 成立【实测】 |
| `docs/requirements.md:58`「列表 5 秒自动刷新」已被 2026-10-10 注记纠正为 4s | 是 | **`:58` 命中**，原文 + 注记俱在 | ✅ 成立【实测】 |
| `docs/evidence/W3-combined…:58` 引 `TaskListView.vue:151-155` | 是 | **`:58` 命中**，确引不存在的路径 | ✅ 成立【实测】 |
| P3 缺陷：`waitForJobFinal` 未传 `signal`，300s 超时静默 | 是 | `task.ts:298` 确只给 `intervalMs`+`onTick`；`jobs.ts:63-64` 确 `return job` | ✅ 成立【实测】 |
| 后端任务级通道 per-task（无列表级全局通道） | 是 | `TaskController.java:161` `GET /{id}/events` | ✅ 成立【实测】 |

### 4.c 与批 B 改动面的文件级重叠

| 卡③改动文件全集 | 与批 B 重叠？ |
|---|---|
| `frontend/src/stores/task.ts` | 否 |
| `frontend/src/api/jobs.ts` | 否 |
| `frontend/src/utils/job-watch.ts` | 否 |
| `frontend/src/utils/polling.ts`（新建） | 否 |
| `frontend/src/api/tasks.ts` | 否 |
| `frontend/src/views/TaskCenterView.vue` | 否 |
| `frontend/src/views/ImportWizardView.vue` | 否 |
| `frontend/src/components/AiPanel/AiPanel.vue`（可选、低优先） | 否 |
| `backend/**` | **明确"不动"** |

**结论：卡③ 与批 B「可并行」（零文件级重叠，纯前端）。**
**⚠️ 一条验证冲突**：卡③ §5 通用验证第 2 条要求 `git status --short backend/` **期望无输出**，以证明"一期不该动后端"。批 B 正在改 `backend/**`，**该判据在批 B 落地窗口内必然为红**——须改为"与本期开工前快照比对 backend/ 无**新增**变化"，或把该判据挪出批 B 并行窗口。【实测 + 推断】

### 4.d 验收标准可执行性

| 验收项 | 实测 | 结论 |
|---|---|---|
| `npm run typecheck` / `npm run build` | 脚本存在 | ✅ 可执行【实测】 |
| `git status --short backend/` 期望无输出 | 脚本可跑 | ⚠️ **批 B 并行窗口内失效**（见 4.c） |
| DevTools Network 分段计数/EventStream 面板判据 | 纯人工 | ✅ 可执行【推断】 |
| 实例上限 ≤3 / 四出口回收断言 | 属新建代码断言 | ✅ 方案自洽【推断】 |
| 回归面（任务中心/两向导/AI 面板/裁剪性） | 页面均在 | ✅ 可执行【实测】 |
| "不引入测试框架"约束 | `package.json` 确无测试框架 | ✅ 前提成立【实测】 |

---

## 5. 与批 B 的冲突矩阵（汇总）

批 B 改动面（任务卡所述）：`ExportJobRunner.java`／`PublishJobRunner.java`／`JobProgressService.java`／`AppProperties.java`／`application.yml` + 新建 `JobHeartbeatRegistry.java`／`JobWatchdog.java` + `scripts/verify-e2e.ps1` + docs 台账。
**体检实时状态：批 B 半落地（6 项在制品，另含清单外的 `JobRepository.java`）；详见 §1.1。** 【实测】

| 卡 | 与批 B 的文件级重叠 | 判定 | 说明 |
|---|---|---|---|
| ① 步骤条 | `docs/M2-排期计划.md`（唯一） | **可并行** | 生产代码零重叠；台账 S4 追加须与批 B 台账写入**错开顺序** |
| ② 对话时序 | 无（仅"运行" `verify-e2e.ps1`） | **可并行** | 卡② 不改后端、不改该脚本；但回归基线（29 用例）会被批 B 改动波及，须重取 |
| ③ 轮询治理 | 无 | **可并行** | 纯前端；唯一冲突是 `git status --short backend/` 这条验证判据在批 B 窗口内失效 |

**也就是说：三卡均不需要"等批 B 落地后开工"。** 需要处理的只有两处副作用：① 的台账同行写入串行化、② 与 ③ 各自一条验证判据的重定基线。

### 5.1 三卡之间的重叠（额外提醒，非任务所问但在批次编排中会撞车）

| 文件 | 卡① | 卡② | 卡③ |
|---|---|---|---|
| `views/ExportWizardView.vue` | ✅ S1b/S3c/S3e | ✅ ③a | — |
| `views/ImportWizardView.vue` | ✅ S1a/S3b/S3e | — | ✅ 步1 #3（watcher 按 kind 回收） |
| `components/AiPanel/AiPanel.vue` | —（明确不动） | ✅ ②③b④ | ✅ 步2 #4（可选） |
| `stores/task.ts` | — | — | ✅ |
| `utils/frontend-tools.ts` | — | ✅ ③a | — |

**【推断】三卡若同批并行，`ExportWizardView.vue`（①②）、`ImportWizardView.vue`（①③）、`AiPanel.vue`（②③）三处会形成写冲突，须串行或分棒。**

---

## 6. R-130 是否并入对话时序包（任务额外问项）

### 事实核对【实测】

`docs/M2-排期计划.md:319` 登记 R-130（追溯审查 · N1）：`loadHistory` 会话身份守卫未覆盖 `restoreSuspendedSnapshot` 内部 `runsCurrent` await 窗口，锚点为 `stores/ai.ts` 的 `:458` 守卫 / `:500` 调用 / `:511` 内部 await / `:524-581` 写回（**2026-10-12 刷新**：台账内锚为原文保留，代码现态见下表；台账状态栏另已有「**完成留痕（2026-10-11，只追加）：已施工并入仓（对话时序包批 1；代码提交 `1efab09`）**」），状态「已登记·待排期」→ **已施工并入仓**，去向写「**随对话时序包或独立小批修**」**已成事实**。

对当前 HEAD（`d897096`；下表「现态」列为 2026-10-12 批4④前置刷新值，按 HEAD `296ffe2` 实读）逐一实读：

| R-130 锚点 | 现态 | 命中 |
|---|---|---|
| `:458` `if (this.sessionId !== sid) return` | **`:642`** 原文一致（catch 分支另有一处同型守卫 @ `:633`） | ✅ |
| `:500` `await this.restoreSuspendedSnapshot()`（loadHistory 内） | **`:708`** 原文一致（空历史分支另有一处 @ `:659`） | ✅ |
| `:511` `current = await aiApi.runsCurrent(this.sessionId)` | **`:730`**（原文一致，实参已改入口捕获的 `sid` ⇒ `aiApi.runsCurrent(sid)`） | ✅ |
| `:524-581` 写回（`applyFrontendToolTerminal` `:530`、宿主重建 `:548-556`、`appendAssistantMessage` `:552`、`upsertToolRun` `:565`、`activeForm` `:572-576`、`resumeSnapshotStream` `:593`） | 全在 **`:760-816`** 区间内（对应现态：`:754` / `:773-782` / `:779` / `:785` / `:794-805` / `:815`） | ✅ |

**且实读确认缺陷仍活**（★2026-10-12 批4④刷新：**本结论已被推翻**，见行末注记）：`restoreSuspendedSnapshot`（`:508` → **`:725`**）**入口未捕获 `sid`**，`await aiApi.runsCurrent(...)`（`:511` → **`:730`**）之后到写回（`:524` → **`:748`** 起）之间**没有任何二次会话对账**。【实测（体检时点）】**【2026-10-12 刷新注记 · 缺陷已闭合】**：入口现同步捕获 `sid`（`:726`）与 `currentAbort`（`:727`），await 后、写回前有二次对账（`:737-739`：会话身份失配或 abort 句柄已换 ⇒ 整体丢弃旧快照）⇒ **R-130 / R-132 已随批1（`1efab09`）施工并入仓**，回归锁 = `frontend/tests/restore-reconcile.spec.ts`；故本节的「建议并入」**已无待办**（R-141①/② 的 re-attach/整替残余面另见台账 `:330`/`:333`）。

**与卡②的交集**：卡② 子包① 的改动表**已明确要改 `stores/ai.ts:487-574 restoreSuspendedSnapshot`**（"重建时同步补 ToolPart"），③b 亦再次改它（"宿主消息选择改为已持该 ToolPart 的消息优先"）。⇒ **同一函数、同一段代码，卡② 必然要动。**

### 一句建议

> **建议并入对话时序包，且落在子包①（双写过渡批）而非 ③b**——因为 R-130 与 `parts` 模型正交（只关乎会话身份对账），而 ① 本来就要改 `restoreSuspendedSnapshot`；一次改、一次风险最低，且避免把这个 MINOR（倾向 MAJOR）缺陷推迟到最高风险的 ②③b 之后才修。

---

## 7. 需刷新处数汇总

| 卡 | 需刷新处数（本棒计数口径：锚点在该处不再命中即计 1） | 分布 | 其中"非纯行号"（语义/编号/路径） |
|---|---|---|---|
| ① 步骤条 | **约 32 处** | `ExportWizardView.vue` 22 处（脚本/执行器区 21 处 + `:602`→`:616`）；`ImportWizardView.vue` 1 处；`stores/*` 2 处；后端 5 处（其中 4 处为卡写作时即偏）；文档/依赖 2 处 | **4 处**：①S4 的 `R-94` 编号被占（必须改号，下一空号 `R-132`）+ 追加点下移；②`loadTask` 签名变（`preloaded?` + 返回 `TaskLoadOutcome`）；③`ensureGridLoaded` 返回结构化结局 `{ok,error}` + `download_export_file` 已即发即回 + `PAGE_HANDLER_OPEN_EXPORT_EDITOR` 已用 `throw` 表失败；④`element-plus` 的 `step/src/item.d.ts` 路径错（应为 `steps/src/item.d.ts`） |
| ② 对话时序 | **体检时点约 85 处**；**2026-10-12 批4④前置刷新：实读复核 114 处、失锚 3 处**（分布见文末 §9.1） | `stores/ai.ts` ≈50 处（含 `handleFrame` 整段 **+280**、`runFrontendTool` **+362**）；`AiPanel.vue` ≈18 处（+20～+152）；`types/ai.ts` 8 处（+2～+113）；`frontend-tools.ts` 6 处（+32～+38）；`api/sse.ts` 1 处（0 漂）；`ExportWizardView.vue` 1 处（③a 落点，现 `:1263-1310`）；**另刷** `types/tools.ts`（+17）与 `RedisChatMemoryRepository.java`（+8～+28，批1 引入） | **3 处「非纯行号」已全部随批1/批2/③a/③b 入库**：①R-84 三面（页面 handler / `runRegisteredTool` / `runPageHandler`）已结构化 + `types/tools.ts` 已增 `outcome`；②`stores/ai.ts` 卡外事实（会话身份守卫 R-130/R-132 / `stopped` / 骨架屏 / `resetSession` 重写）已入库；③`timeoutOutcome` 已补 `outcome:'unknown'`。⇒ ④**须按本报告新锚点重取，不得照抄卡内旧行号；且③a 无剩余面**（残面 = 只读/越界拒绝「字符串折 `ok`」同族 22 处，随 R-84 批） |
| ③ 轮询治理 | **约 21 处（20 处为纯行号平移 + 1 处由批 B 实时引入）** | `stores/workspace.ts` 4 处；`stores/ai.ts` 3 处；`AiPanel.vue` 4 处；`ExportWizardView.vue` 6 处；`SpreadGrid.vue` 2 处；`frontend-tools.ts` 1 处；`ImportWizardView.vue` 1 处（另 `:756` 应为 `:730`/`:752`）；**`ExportJobRunner.java:256 → :259`（批 B 引入，§1.1）** | **0 处语义问题**（相位/基线数字/前端全部成立）；**2 处待处理**：①`ExportJobRunner:256→:259` 需换锚；②`git status --short backend/` 判据在批 B 窗口内失效 |

### 各卡"与批 B 冲突"结论

- ①：**与批 B 无生产代码冲突 → 可并行**；仅台账同一行区须错开写入。
- ②：**与批 B 无文件级冲突 → 可并行**；`verify-e2e.ps1` 的 29 用例基线须在批 B 落地后重取。
- ③：**与批 B 无文件级冲突 → 可并行**；`git status --short backend/` 这条"后端零改动"判据在批 B 窗口内失效，须改口径。

---

## 8. 体检纪律声明

- 本棒**未修改** `<REPO_ROOT>` 下任何文件；**未做**任何 git 写操作（无 add/commit/push/reset/stash/branch/worktree）；**未读**任何 `.env`。
- 全部 git 命令均为只读：`git rev-parse` / `git status --porcelain` / `git log` / `git show` / `git diff --stat` / `git cat-file`。
- **★ 验证窗口说明**：绝大多数锚点在 **10:38（工作树干净、批 B 未开始写入）** 核对；批 B 于 **10:39** 开始写入工作树，凡"批 B 改动面"内的文件（`AppProperties`/`JobRepository`/`ExportJobRunner`/`JobProgressService` + 2 新文件）本棒**已按 10:39 之后的现态重新复验**并单列于 §1.1。**未受批 B 影响的前端锚点结论以 10:38 的干净工作树为准**（该批次只改 `backend/**`，对 `frontend/**` 零影响）。
- **未启动**前后端、未跑 `yarn typecheck` / `yarn build` / `check-docs.mjs` / `verify-e2e.ps1`；卡内标为"验证方式"的命令本棒只核对**其存在性与调用形态**，未执行取结果。
- 卡①②③ 均未被我改动；唯一写入文件为本报告 `<LOCAL_WORK>\pkg-health\体检报告.md`。

### 明确未复验的事项（勿当结论引用）

1. `TaskService.complete()` 是否全仓零调用（卡①§6-① 的关键前提）——本棒未复跑全仓 grep，采信卡内实读。
2. `.github/workflows/ci.yml` 的 frontend job / e2e job 形态（卡②§1.8 实读）与 `E2E_STUB_MODE` 桩模式细节——本棒未读该文件。
3. `docs/M2-排期计划.md:283` R-91 的 vitest 呈批状态原文——本棒只定位到行号存在，未逐字核对内容。
4. Element Plus 的 `:status` attr fallthrough **实际渲染行为**（卡①§4-B 要求施工时现场取证）——本棒只做了 props 类型层核对，未在浏览器观测。
5. 三卡§5/§4 中各"手测路径（8081/5200、H2 控制台、Network 计数）"的可执行性——本棒未启动环境。

---

## 9. 2026-10-12 批4④前置刷新注记

> **执行者署名（DC-19）**：`agent-527`（Kimi Code 子智能体；**实际运行模型 = `DeepSeek/deepseek-flash`**，自证方式 = 读取本会话 `agents/agent-527/wire.jsonl` 中的 `"model":"DeepSeek/deepseek-flash"`；session id = `<KIMI_SESSION_A>`；任务名 = 批4④ 侦察卡 B）。
> **授权来源 = K3 指挥官派单 · 批4④ 侦察卡 B**；写入授权 = 本卡「允许且仅允许改写 `<LOCAL_WORK>\pkg-health\体检报告.md`」。

| 项 | 值 |
|---|---|
| 刷新范围 | **卡② 对话时序包区间** = §0 结论速览「② 对话时序包」行（`:21`）＋ §3「卡② 对话时序包」全节（`:201-384`，含 §3.a ✅/⚠️ 两表、§3.b 基线数字、§3.d 验收项）＋ §6「R-130 是否并入对话时序包」节（`:510-531`）＋ §7 汇总表「② 对话时序」行（`:540`） |
| 刷新时 HEAD | **`296ffe2ae20641ee1fe73a8409e0a0fa851ce1c6`**（`docs(m2,evidence): 批 3'③b 决策卡/施工卡/三报告入库 + R-144 落行 + 台账逐项销记/注记`）；刷新时 `git status --porcelain` = **空**（工作树干净） |
| 被参照仓库 | `<REPO_ROOT>`（**唯一**）；`<LOCAL_WORK>\ai-example` 与 `<LOCAL_WORK>\ai-example-code\ai-example-main` **零操作** |
| 读法 | 只读：Read / Grep / 只读 `git rev-parse|log|diff|status`。**未改主仓库任何文件、未做任何 git 写操作、未读 `.env`、未启前后端** |
| 唯一写入 | 本文件（`<LOCAL_WORK>\pkg-health\体检报告.md`） |
| **刷新处数** | **114 处**（口径 = 卡② 区间内被本棒按 HEAD **实读复核并写回**的锚点条目数（表格行/正文锚点条目各计 1）；其中**行号实际变更 105 处**、**复核确认未变 9 处**） |
| **失锚处数** | **3 处**（逐条见 §9.2；已含在 114 计数内，不另加） |
| 定位依据 | 每处均以「锚点旁记的**特征文本/符号名**」在 HEAD 实读重定位：先按现态行号 **±40 行窗**找特征文本，窗内找不到再对全文件 grep 符号名/字面量。§3 各表「现态」列即刷新后的 `file:line`；`stores/ai.ts` 表内每行均给出符号名或原文片段 |
| 未动区间 | **§2 卡① 步骤条、§4 卡③ 轮询治理一期、§5 冲突矩阵、§8 体检纪律声明——一行未改**（§4 内的 `AiPanel.vue`/`stores/ai.ts` 旁证锚点虽亦漂，按任务红线归轮询治理、本棒不写回；其现值见 §9.3 附注） |
| 行号自洽 | 本次全部改写均为**行内同数替换（行数 565 → 565，零增删行）**，故本报告所有行号引用（含新写入的 §3 现态列、§6 区间、本节区间标注）在改后文件中**仍然成立** |

### 9.1 刷新处数分布（合计 114）

| 区间 | 处数 | 实变／未变 | 备注 |
|---|---|---|---|
| §3.a「✅ 仍命中」表（8 行） | 8 | 4 实变 / 4 未变 | `types/sse.ts`（7 子锚点全中）、`SseChatEmitter.java`（11 子锚点全中）、`ResilientChatService.java`（5 子锚点全中）、证据/调研文档（6 子锚点全中）四行**复核后 0 漂移**；`types/tools.ts`（`:115-119`→`:132-148`）、`RedisChatMemoryRepository.java`（`:33-35`/`:53`/`:55`/`:110`→`:35-37`/`:61`/`:63`/`:138`）、`api/sse.ts`（`:424-600`→`:424-593`）、`frontend-tools.ts`（`:314-321`→`:347-353`）四行实变 |
| §3.a（1）`types/ai.ts` | 8 | 7 实变 / 1 未变 | `AiHistoryMessage` 未再漂；其余 7 行实变（最大 `PendingToolCall` `:198-226`→`:297-325`） |
| §3.a（2）`stores/ai.ts` | 54 | 53 实变 / **1 失锚** | 最大位移 `runFrontendTool` +362；`handleFrame` 整段 `:1151-1399`→`:1517-1756` |
| §3.a（3）`AiPanel.vue` | 18 | 16 实变（2 行未再漂）/ **2 失锚** | 位移 +20～+152 |
| §3.a（4）`frontend-tools.ts` | 6 | 6 实变 | +32～+38 |
| §3.a（5）`ExportWizardView.vue`（③a 落点） | 1 | 1 实变 | `:969-976`/`:973-975` → `:1263-1310`（handler 区） |
| §3.a（6）已漂移的验证引用 | 2 | 1 实变 / 1 未变 | `api/sse.ts:169-173` 未再漂；`stores/ai.ts:1205`→`:1591` |
| §3.a 表外注记（卡外事实那条 `★`） | 1 | 1 实变 | 内锚 `:449`/`:458`→`:633`/`:642`、`:268-269`→`:430-437`、`:1127`→`:1401` |
| §3.b 基线数字核对 | 5 | 4 实变 / 1 未变 | 前端测试框架（**结论已翻转**）／E2E 头部注释 `:41`（复核**未漂**）／P1（**已落地**）／P2 行号与口径（**9 vs 8**）／台账 R-06 内锚（`:1481-1501`→`:1852-1906`） |
| §3.d 验收标准可执行性 | 2 | 2 实变 | `AiPanel.vue:213`→`:201`；③a 改造面四组行号 + 结论（**③a 已无剩余面**） |
| §6 R-130 问项 | 7 | 7 实变 | 正文台账锚 1 ＋ HEAD 声明 1 ＋ 锚点表 4 ＋「缺陷仍活」段 1 |
| §7 汇总「② 对话时序」行 | 1 | 1 实变 | 计数与分布区间 |
| §0 结论速览「② 对话时序包」行 | 1 | 1 实变 | 计数与分布区间 |
| **合计** | **114** | **105 实变 / 9 未变**（另含 3 失锚） | — |

### 9.2 失锚清单（3 处：目标代码已删除/重构，无法重定位）

| # | 出处（本报告条目） | 文件 | 最后已知行号 | 特征文本 | 本棒判断 |
|---|---|---|---|---|---|
| 1 | §3.a（2）`stores/ai.ts` 表 | `frontend/src/stores/ai.ts` | 卡锚 `:1402-1419`；体检现态 `:1488` | `toPendingCall(frame, kind)` ——「帧 → 挂起投影；parts 化后变为"帧 → ToolPart 的初值"」 | **该死码已于「对话时序包批 2（权威翻转，`6bea4ca`）」删除**：挂起投影改为「段 → 派生」（`deriveProjections` 是唯一写口）。HEAD 全仓 grep `toPendingCall` 在 `stores/ai.ts` **零命中**；复刻形态存于 `frontend/src/utils/parts.ts:152` `toPendingCallProjection`（消费点 `:209`）。**④ 不得再按此锚点施工**，改锚 `utils/parts.ts:152` |
| 2 | §3.a（3）`AiPanel.vue` 表 | `frontend/src/components/AiPanel/AiPanel.vue` | 卡锚 `:245`；体检现态 `:289` | 「FRONTEND 兜底 else →「已执行」」 | **该死码已于批 3'③b（`fc6e5a2`，D2/R-139③）删除**——挂起卡终态 tag 列（原 `:264-277`）在 D8 关旁路后不可达，按清单整列删除；HEAD 活分支只剩 `activeForm` 提交窗口（`:247-258`）。当前 `:289` 为复制按钮区（无关）。**④ 按活分支锚点 `:247-258` 取行** |
| 3 | §3.a（3）`AiPanel.vue` 表 | `frontend/src/components/AiPanel/AiPanel.vue` | 卡锚 `:253`；体检现态 `:297` | 「CONFIRM 兜底 else →「执行失败」」 | 同上：**③b D2 整列删除**（CONFIRM 终态 tag 列，原 `:280-286`；删除留痕注释现存于 `:261-264`）。CONFIRM 面的活分支 = 「待决确认按钮」块 `:212-231`（restoreSuspendedSnapshot 重建路可达），**④ 须按该块取行** |

> 另说明：`AiPanel.vue` 的 2 处失锚**不是行号漂移**，而是**锚点所指的代码分支已被 ③b 作为死码删除**；行号本身在删除后并不存在可对齐的等价物（故不写回数字，只记判断）。

### 9.3 批4④ 直接相关的关键现值（供开工直接取用）

- **`AiPanel.vue` 锚点新旧行号对照表（18 处，见 §3.a（3）刷新后的表；18 处中 2 处失锚）**：
  `:121-126`→`:142`／`:138-140`→`:158`／`:142-146`→`:174-177`／`:148`→`:171`／`:151-166`→`:179-196`／`:169-255`→`:201-265`／`:206-215`→`:237-246`／`:245`→**失锚（删）**／`:253`→**失锚（删）**／`:408-411`+`:419-432`→`:476-489`／`:444-446`→`:506`／`:453-470`→`:515`／`:472-484`→`:544`／`:460-465`+`:478-481`→`:528-531`／`:558-570`→`:707-719`／`:572-578`→`:724-733`／`:169`（`data-testid`）→`:201`／`:323-326`→`:381-383`。
- **附注（属 §4 轮询治理区间，按红线未写回；仅供批4④ 参考，取用时须自行复核）**：§4.a 表内的 4 条 `AiPanel.vue` 旁证锚点现值为——`1s tick setInterval` `:595-597`→**`:750-752`**；`clearInterval` 清理 `:605-609`→**`:760-768`**（`clearInterval(tickTimer)` @ `:762`）；`now`/`confirmDeadline` `:323-326`→**`:381-383`**；`resetPendingWatch` `:558-570`→**`:707-719`**。§4.a 表内 `stores/ai.ts` 三条旁证锚点现值为：`:379-392`→**`:543-564`**、`:360`→**`:527-530`**、`:984`→**`:1292-1296`**。
- **卡② §1.3 的「已入库替代事实」**（④ 必须补读，卡内没有）：`parts` 段模型唯一真值（`types/ai.ts:184-294` + `utils/parts.ts`）／`MIRROR_VERSION = 2` 与 `migrateMirror`（`stores/ai.ts:158-210`）／`isFrameDecidedPendingStatus` 7 态入口闸（`:400-404`）／跨宿主已决终帧守卫（`:1569-1574`）／`frontend_tool_request`・`suspended`・`confirm_request` 三 case 的去 GF 前缀已决守卫（`:1615-1617`/`:1636-1641`/`:1686-1690`）／`runFrontendTool` 的轮次句柄比对（`:1856-1885`）。
- **③a/③b 收口后的残面（不得重复施工）**：台账 `docs/M2-排期计划.md:276`（R-84 行尾注记）登记的「只读/越界拒绝返回失败形字符串 ⇒ 经 `interpretHandlerOutcome` 字符串兼容支折 `outcome:'ok'`」同族 **22 处**（导出侧 11 + 导入侧 11），去向 = 随 R-84 批结构化；另 `docs/M2-排期计划.md:333`（R-144）登记的「后端历史载荷不携带段级状态 ⇒ `loadHistory` 整替丢本地 `unknown`/`failed` 真态」= **后端批**，未闭合。

### 9.4 本次刷新的自限与未复验事项

1. 本棒**未**执行 `yarn typecheck` / `yarn build` / `vitest` / `check-docs.mjs` / `verify-e2e.ps1` —— 只按 HEAD 实读代码定位锚点，未取运行结果。
2. §3.b「CI 前端 job」「`E2E_STUB_MODE` + redis」两行仍为体检时点的「⚠️ 未复验」，本棒未补读 `.github/workflows/ci.yml`。
3. 后端锚点仅实读 `SseChatEmitter.java`（1226 行）/`ResilientChatService.java`（788 行）/`RedisChatMemoryRepository.java`（181 行）三文件；未跑编译。
4. `docs/M2-排期计划.md` 本次仅作**只读引用**（刷新其内锚的当前漂移值），**未改台账一字**（台账写入属批4④ 施工面）。
