# 历史补档：顶部菜单精简 + 任务标题即入口（UI 包）施工 / 复核 / 修补 / 命名修补

- **归档来源（已脱敏）**：4 件仓外原件，全部来自 `<LOCAL_WORK>/ui-title-open/`，源件仅仓外、未移动、未改写；分件见下表
- **归档日期**：2026-10-26（本批仅归档）
- **历史取证 · 非现行基线**：本文是 2026-10-10 时点的历史证据，结论未经当前代码复验，**不表示当前已验证 / 已修复**；正文逐字保留（仅路径、会话标识、用户名/主机名与旧目录名脱敏），不改写旧结论。
- **会话**：正文未出现会话 id，来源会话见索引 `README.md` 的来源映射表（模型与 `agentId` 自证字段按 DC-19 保留原值）
- **源行 / 源字节**：1119 行 / 93,774 B

| 分件 | 原件（仓外别名） | 源行 | 源字节 | 主题 |
|---:|---|---:|---:|---|
| 1 | `<LOCAL_WORK>/ui-title-open/施工报告.md` | 226 | 18,872 | 施工报告（基线 d3020ee，零 git 写） |
| 2 | `<LOCAL_WORK>/ui-title-open/复核报告.md` | 316 | 27,936 | 独立复核报告（S4-01/02/03/05 与待裁决项） |
| 3 | `<LOCAL_WORK>/ui-title-open/修补报告.md` | 255 | 22,306 | 修补报告（复核后四项增量修补） |
| 4 | `<LOCAL_WORK>/ui-title-open/命名修补报告.md` | 322 | 24,660 | 命名纪律修补报告（零分隔连写形态命中点修复 + 门禁补强） |

---

**〔分件 1／4〕** 原件：`<LOCAL_WORK>/ui-title-open/施工报告.md`

# 施工报告：顶部菜单精简 + 任务标题即入口（UI 包）

- 受检仓库：`<MAIN_REPO>`（前端 `frontend/`，后端 `backend/`）
- 施工日期：2026-10-10
- 基线提交：`d3020ee`（施工前工作树干净）
- 提交状态：**全部改动保持未提交**；全程**零 git 写操作**（未 add / commit / stash / checkout）
- 结论：裁决 A、B 全部落地；`yarn typecheck` / `yarn build` / `check-docs` 三门禁全绿；浏览器端到端实测通过。**1 处按裁决书无法执行（`getTaskOverview` 保留，见 §4.1）**，另有 1 处附带修复与 3 处需呈批的纪律张力（§4.2）。

---

## 1. 改动清单

汇总：12 个文件被修改（`git diff --stat` = 19 insertions / 143 deletions），无新增文件，无未跟踪文件。

```
 backend/.../task/controller/TaskController.java |   2 +-
 docs/M2-排期计划.md                             |   2 +-
 docs/evidence/S44a-前端基座验证.md              |   2 +-
 docs/evidence/S44b-五页视图验证.md              |   4 +-
 docs/test-cases.md                              |   2 +-
 docs/verification-results.md                    |   4 +-
 frontend/src/App.vue                            |   2 -
 frontend/src/api/tasks.ts                       |   2 +-
 frontend/src/stores/task.ts                     |  15 +--
 frontend/src/views/ExportWizardView.vue         |   2 +-
 frontend/src/views/ImportWizardView.vue         |   2 +-
 frontend/src/views/TaskCenterView.vue           | 123 +-------------------
```

### 1.1 A —— 顶部导航删两个向导菜单项

| 文件:锚点 | 改动 |
|---|---|
| `frontend/src/App.vue:14-15`（改前） | 删 `el-menu-item`「导出向导」（`ROUTE_NAMES.exportWizard`）与「导入向导」（`ROUTE_NAMES.importWizard`）两行 |
| `frontend/src/App.vue:13-15`（改后） | 菜单 = 任务中心 / 配置定义 / 数据浏览（3 项） |

**保留不动（逐项核对未改）**：`frontend/src/router/index.ts:30-39` 的 `/export`、`/import` 路由与 `meta.title`；`frontend/src/stores/workspace.ts:39-52` 的 `PAGE_ROUTES` / `PAGE_LABELS`（`export: '导出向导'`、`import: '导入向导'` 为 AI 契约，原样保留）；AiPanel 的 `TOOL_LABELS` 与各页面 handler；两个向导页本体。上述文件均**未被 `git status` 列出**，即物理未改。

### 1.2 B —— 任务中心：标题即入口、删「打开」按钮、拆详情抽屉

`frontend/src/views/TaskCenterView.vue`：**466 行 → 357 行（净 −109）**。

| # | 改前锚点 | 改动 | 改后锚点 |
|---|---|---|---|
| 1 | `:47` | 标题按钮 `@click="openDetail(row)"` → `@click="restoreWizard(row)"`（＝原「打开」按钮语义） | `:47` |
| 2 | `:88-103` | 操作列 `width="200"` → `width="160"`；删「打开」按钮（原 `:90`），保留「取消」「删除」 | `:88-102` |
| 3 | `:145-220` | **整块删** `el-drawer v-model="detailVisible"`（含发布冲突 alert、任务 descriptions、作业表、文件表、校验问题表） | — |
| 4 | `:257` | 删 `const detailVisible = ref(false)` | — |
| 5 | `:267-272` | 删 `currentTask` / `detailTitle` / `importModeLabel` 三个 computed（仅抽屉使用） | — |
| 6 | `:325-338` | 删 `jobTagType()`（仅抽屉使用） | — |
| 7 | `:373-380` | 删 `openDetail()`（抽屉唯一入口，内含 `closeTask()` + `openTask()`） | — |
| 8 | `:239-243` | 清理失用 import：`jobStatusLabel`（stores/task）、`JOB_TYPE_LABELS`+`SEVERITY_LABELS`（utils/format）、`readImportMode`+`TaskFile`（types/task）、`JobStatus`+`ValidationIssue`（types/job） | `:158-166` |
| 9 | `:147-156` | 头部口径注释补一条「点任务标题 = 一键恢复向导（`restoreWizard` 按任务类型跳对应向导页，与创建任务后跳转同口径）」 | `:152` |

**保留不动**：`restoreWizard()`（现 `:268-271`）；`createTask()` 内创建后跳转（现 `:342`）；`canCancel` / `cancelTask` / `removeTask` / 进度三函数；`TaskStatus`/`TaskSummary`/`TaskType`/`Job` 与 `TASK_TYPE_LABELS`/`stepLabel`/`progressView`/`formatDateTime` 均仍被列表使用，未误删。

### 1.3 `frontend/src/stores/task.ts`（净 −13 行）

| 改前锚点 | 改动 |
|---|---|
| `:111-113` | 删 getter `latestJob`（唯一残留引用是自身实现里的 `state.items[0]?.latestJob` 字段读取，非 getter 调用） |
| `:114-116` | 删 getter `hasRunningJob` |
| `:203-207` | 删 action `openTask`（删掉后 `currentTaskId` 再无写入方） |
| `:94` | state 注释「当前任务（**详情抽屉**/向导共用）」→「当前任务（向导与 ui_event 刷新链路共用）」 |

**保留不动**：`refreshCurrent`（现 `:197-217`）、`closeTask`（`:219-229`）、`loadIssues`（`:293-305`）、`watchPublishOutcome`（`:312-341`）、`restoreWizard`（`:345-353`）、`resolveWizardRoute`（`:354-364`）、`fetchTaskType`，以及全部 state 字段（`currentTaskId`/`currentTask`/`currentJobs`/`currentFiles`/`issues`/`issuesTotal`/`issuesLoading`/`detailLoading`/`publishConflict`）。

### 1.4 `frontend/src/api/tasks.ts`

`getTaskOverview` **保留未删（与裁决书不符，理由见 §4.1）**，仅将注释由「任务概览：任务 + 全部作业 + 文件（**详情抽屉用**）」改为「（`refreshCurrent` 读取当前任务快照用）」。现锚点 `:19-22`（函数）、`:120`（`tasksApi` 对象成员）。`TaskOverview` 类型导入与具名导出保持原样。

### 1.5 向导页文案（附带修复，超出裁决书锚点清单）

| 文件:锚点 | 改动 |
|---|---|
| `frontend/src/views/ExportWizardView.vue:22` | alert `description` 内「或从任务中心点**「打开」**」→「或从任务中心点**任务标题**进入」 |
| `frontend/src/views/ImportWizardView.vue:22` | 同上 |

理由：删「打开」按钮后该文案指向一个不存在的按钮。**实测附带发现：该 `description` 本就不显示**——`el-alert` 存在 default slot（「创建…任务并进入」按钮）时 `description` prop 被 slot 覆盖（浏览器实测该告警 innerText = `尚未绑定任务 ｜ 创建导出任务并进入`）。故本条是「源码口径不失实」的清理，**无用户可见行为变化**。

### 1.6 后端（仅注释 1 行）

`backend/src/main/java/com/example/configmgr/task/controller/TaskController.java:54`：
`任务详情概览：任务 + 全部作业 + 文件，供详情抽屉使用。` → `任务详情概览：任务 + 全部作业 + 文件。`
端点 `GET /api/tasks/{id}/overview`（`:56-`）保留，行为零变化。

### 1.7 文档回灌（只追加/注记，内容零删）

| # | 文件:锚点 | 处理 |
|---|---|---|
| 1 | `docs/verification-results.md:150` | U-02「标题即入口」整行保留 + 行尾注记：改造前一度失实（点标题开的是详情抽屉），本次改造删抽屉、点标题改由 `restoreWizard` 承载，本行**重新吻合**（2026-10-10） |
| 2 | `docs/verification-results.md:174` | TM-09 整行保留 + 行尾注记：**本例失效**（抽屉已整体拆除，验证对象不存在） |
| 3 | `docs/test-cases.md:212` | TM-08「UI 详情抽屉」整行保留 + 行尾注记：**本例失效** |
| 4 | `docs/M2-排期计划.md:282` | R-90 行尾注记：该行「两处抽屉内表格固定列宽合计超容器」中的**任务中心详情抽屉**治理对象随之消失（仅 `DefinitionsView` 编辑抽屉仍在），原表述按本表只追加纪律留痕 |
| 5 | `docs/evidence/S44a-前端基座验证.md:192` | 「5 项菜单（任务中心/导出向导/导入向导/配置定义/数据浏览）」行尾注记：现为 3 项 |
| 6 | `docs/evidence/S44b-五页视图验证.md:269` | 「顶部 `el-menu` 五页导航」行尾注记：菜单项 5 → 3 |
| 7 | `docs/evidence/S44b-五页视图验证.md:329` | 「顶部五页导航」行尾注记：同上 |

**扫过但按裁决书「仅描述页面/路由存在的不动」未改**：`docs/technical-design.md:319-320`（`/tasks/:id/export/:step → 导出向导`，路由描述，页面仍在）；`docs/s4/S4.4-前端合流规格.md:91`（五页/向导**页面**清单，页面仍在）；`docs/03-技术方案文档-v2.1.md:418`、`docs/merge/03-技术方案文档.md:12`（views 目录与工作区示意，非顶部菜单清单）。第 5~7 条触碰 `docs/evidence/`，与 DOC#5「历史证据不回改」的口径存在张力，见 §4.2。

---

## 2. 验证结果

### 2.1 类型门禁【实测】——绿

```
$ cd frontend && yarn typecheck
$ vue-tsc --noEmit
Done in 4.41s.
```

### 2.2 构建门禁【实测】——绿

```
$ cd frontend && yarn build
✓ 1747 modules transformed.
dist/assets/TaskCenterView-<hash>.js   8.67 kB │ gzip: 3.52 kB
✓ built in 27.97s
```
产物复核：TaskCenterView chunk 内 `任务详情` **0 命中**（抽屉代码确认未进包）；`任务已删除` / `确定取消任务` 仍在（取消/删除链路完好）。

### 2.3 文档门禁【实测】——6/6 绿

```
$ node scripts/ci/check-docs.mjs
  [PASS] ① 导航/口径文档无盘符绝对路径
  [PASS] ② 技术栈版本与 pom.xml / package.json 一致
  [PASS] ③ 导航/口径文档引用的文件存在
  [PASS] ④ 端口表与 application.yml / vite.config.ts 一致
  [PASS] ⑤ 命名纪律禁则（git ls-files 全量）（实扫文本 913 个；豁免 3 个）
  [PASS] ⑥ .java 探针不参与构建
全部通过：6/6 断言绿。   EXIT=0
```
断言 ⑤ 覆盖全部被改文件（含 3 个 `docs/evidence/` 文件），无命名纪律命中。

### 2.4 grep 复核【实测】——逐条

| 复核项 | 结果 |
|---|---|
| `TaskCenterView.vue` 内 `openDetail` / `detailVisible` | **0 命中** |
| `TaskCenterView.vue` 内 `jobTagType` / `TaskFile` / `ValidationIssue` / `JobStatus` / `jobStatusLabel` / `readImportMode` / `openTask` | **0 命中** |
| `stores/task.ts` 内 `openTask` / `hasRunningJob` / `latestJob` | **0 命中** |
| 全 `frontend/src` 内 `openTask(` 调用点 | **0 命中**（仅 `openTaskEvents`，无关） |
| `App.vue` 内「导出向导」「导入向导」菜单残留 | **0 命中**（`el-menu-item` 仅 3 行：任务中心/配置定义/数据浏览） |
| `getTaskOverview` 调用点 | 3 处：`api/tasks.ts:20`（定义）、`:120`（对象成员）、`stores/task.ts:204`（`refreshCurrent`）——即**仍有活调用点**，见 §4.1 |
| `openDetail` / `detailVisible` 其他文件残留 | 仅 `DefinitionsView.vue:64/78/282/341/343`（配置定义页自己的抽屉，**与本任务无关，未动**） |
| 向导页「尚未绑定任务」告警 | 保留（`ExportWizardView.vue:21`、`ImportWizardView.vue:21`，另有多处 `ElMessage.warning`） |

### 2.5 浏览器端到端【实测】——通过（真实 Chrome，经本机 webbridge 守护进程）

环境：预置后端 jar（`backend/target/config-mgr.jar`）起在 `8081`；vite dev server 起在 `5202`（以 `VITE_API_BASE=http://localhost:8081` 覆盖用户 `.env.local` 的既有指向，**未修改用户 `.env.local`**）。

| 断言 | 实测读数 |
|---|---|
| 顶部菜单恰 3 项 | `menubar` = 「任务中心」「配置定义」「数据浏览」（`banner` 实测树） |
| 操作列不再有「打开」 | 两行任务实测 `ops = ["取消","删除"]` |
| 点任务标题 = 进入向导（EXPORT） | 点标题 `TitleEntryCheck` → `location.href = http://localhost:5202/export?taskId=1`，`h2 = 导出配置向导`，步骤条 = 选择配置/查询配置/导出配置，`el-drawer` 数 = **0** |
| 点任务标题 = 进入向导（IMPORT） | 点标题 `TitleEntryCheckImport` → `http://localhost:5202/import?taskId=2`，`h2 = 导入配置向导`，`el-drawer` 数 = **0** |
| 直接输 URL 的「未绑定任务」告警保留 | 直连 `http://localhost:5202/export`（无 taskId）→ `尚未绑定任务` 命中 = **true**，页面仍给出「创建导出任务并进入」按钮 |

### 2.6 后端测试未跑——理由

后端仅改 `TaskController.java:54` 的 **Javadoc 一行**（无签名、无逻辑、无 SQL、无契约变化），且端点保留；编译面与行为面均无风险。按裁决书「后端仅注释改动，不需跑后端测试」执行。为验证端到端，本次**运行**了预置 jar（非测试套件），见 §2.5。

---

## 3. 观察项（本包未动，登记下轮）

**O-1 `refreshCurrent` 空转链（裁决书已预判，实测坐实）**：删 `openTask` 后 `currentTaskId` 再无写入方，而 `refreshCurrent`（`stores/task.ts:197`）首行即 `if (taskId === null) return`，故其对全部既有调用点均为**空转无副作用**：`stores/workspace.ts:367`（`goto_step`）、`:377`（`select_defs`）、`:400`（`run_job`）、`:325/269/282`（`watchPublishOutcome` 内 + setImportMode + startJob）。这些调用点此前（向导页场景）同样空转，故**行为与改造前一致、无回归**；但 `getTaskOverview` 因此保留了活调用点（§4.1 的直接后果）。彻底解耦（连 `refreshCurrent`/`currentTaskId` 一起收口，或让向导页真正写入 `currentTaskId` 使详情态重新有源）建议单独立项。

**O-2 导入向导作业恢复取的是「最早一次」作业，与命名相悖**：`frontend/src/views/ImportWizardView.vue:448-449` 的局部 helper 名为 `latest`，实现是 `jobs.filter(...).slice(-1)[0]`；而 `GET /api/tasks/{id}/jobs` 按 **createdAt DESC** 返回（`JobController.java:36` → `JobService.java:227-229` → `JobRepository.java:15` `findByTaskIdOrderByCreatedAtDescIdDesc`）。即 `.slice(-1)` 取到的是**该类型最早的一次作业**，同类型跑过第二轮时页面恢复展示的是第一轮。属既有缺陷，本包未动。

**O-3 导出向导重开不恢复作业状态**：`ExportWizardView.vue` 无 `resumeJobs`（对照：`ImportWizardView.vue:418` 有），`exportJob`（`:210`）每次进入均为 `null`，仅凭任务里已存在的 EXPORT 文件恢复结果（`:345` 注释口径）；故「导出作业跑完 → 离开再回向导」看不到作业进度/明细/失败信息，只看到结果文件。属既有行为，本包未动。

---

## 4. 偏差、纪律张力与操作留痕

### 4.1 偏差：`getTaskOverview` **未删**（裁决书要求删）

裁决书依据是「删除 `openTask` 后`getTaskOverview` 失去唯一调用点」。实测**该前提不成立**——`refreshCurrent` 仍调用它，而 `refreshCurrent` 被明确要求「保留不动」，二者不可同时满足。证据（实跑）：

```
# 步骤 1：删掉 api/tasks.ts:19-22 的函数体
$ yarn typecheck
src/api/tasks.ts(115,3): error TS18004: No value exists in scope for the shorthand property 'getTaskOverview'.

# 步骤 2：再删掉 tasksApi 对象成员 getTaskOverview
$ yarn typecheck
src/stores/task.ts(204,41): error TS2339: Property 'getTaskOverview' does not exist on type '{ listTasks: …; 13 more …; openTaskEvents: … }'.
```

即「删 `getTaskOverview`」与「`yarn typecheck` 必须绿」＋「`refreshCurrent` 保留不动」三者互斥。取舍：**复归 `getTaskOverview`（函数 + 对象成员），只把注释去抽屉化**；typecheck 随之回绿。若必须删，需先授权改 `refreshCurrent`（与 O-1 一并收口），届时可同批删除。

### 4.2 纪律张力：`docs/evidence/` 三处注记（请复核是否保留）

裁决书第 5 条要求「grep `docs/` 中描述顶部菜单的表述，失实即同法注记」。实际命中**不在**举例的 `technical-design.md` / `s4/S4.4-前端合流规格.md`（二者只有页面/路由清单，按「页面仍在则不动」未改），而在 `docs/evidence/` 的三处菜单清单。这 3 处已在 §1.7 第 5~7 条按「只追加注记、原表述留痕」处理；但 DOC#5（`scripts/ci/check-docs.mjs:19-21`）明确声明历史证据文档属「历史记录，不回改」。**未删任何内容、仅追加注记**，若判为越界，回退这 3 行即可（`git checkout -- docs/evidence/S44a-前端基座验证.md docs/evidence/S44b-五页视图验证.md` 属 git 写操作，需你侧执行）。

### 4.3 操作留痕（本机副作用，均已收尾）

- 起了 `backend/target/config-mgr.jar`（CWD＝仓库根）与 vite dev server（`5202`），**均已 kill，端口已释放**（`8081`/`5202` 无 LISTENING）。
- 该 jar 以仓库根为 CWD，落在根 `data/config_mgr_db.mv.db`（**该目录及文件为 10-09 既有产物，非本包新建**）。为端到端验证在其中建了 2 条测试任务（`#1` EXPORT / `#2` IMPORT），验证后**已通过 `DELETE /api/tasks/{id}` 全部删除**，退出时 `totalElements = 0`。仓库的 `backend/data/`（真实开发库）**未被触碰**。
- 浏览器：经 webbridge 在用户真实浏览器中开了一个标签组「任务中心标题即入口验证」（1 个标签，当前停在 `/export`）。按该扩展 skill 的纪律「关闭须由用户发起」，**未自行关闭**——如需清理请让用户说一句或授权我 `close_session`。
- `frontend/dist/` 因 `yarn build` 被重建（该目录 gitignore，不进 diff）。
- 未改动用户既有文件 `frontend/.env.local`（仅以环境变量覆盖本次 dev server 的 API 指向）。

---

## 5. 【待裁决】

**`docs/requirements.md:54-55`「任务详情」需求面失去实现。**

原文（冻结版，本包按纪律**未改文件**）：

> - **任务详情**：流程步骤进度、各配置项条目状态（检查通过/已导入/已发布/失败）、
>   作业历史（每次预检/导入/发布/导出的状态、进度、错误数、起止时间）、关联文件清单

该条正是被拆掉的详情抽屉（`el-drawer` + `GET /api/tasks/{id}/overview`）的需求来源。拆除后，任务中心**不再有任何**「任务 + 全部作业 + 文件 + 校验问题」的聚合视图；四块信息目前只能分散在两个向导页里看（步进条 / 条目列表 / 各步作业面板 / 文件列表），且按 O-3，导出向导重开还看不到作业态。

可选处置（请裁决，本包不动）：
1. **承认需求面收缩**：把 :54-55 改写为「任务详情经向导页承载」口径，并注记抽屉拆除日期；
2. **恢复一个轻量的任务详情入口**（例如列表行「详情」按钮 + 抽屉只读四块），等于回退本包裁决 B 的一部分；
3. **维持现状 + 在 :54-55 行尾加失效注记**（同 §1.7 手法），把「聚合详情视图」转入待办立项。

**相邻同源漂移（一并呈报，未改）**：`docs/requirements.md:53` 声称列表「每行显示…**配置项数**」，但实测列 = 任务/类型/状态/进度/当前步骤/创建时间/更新时间/操作（无配置项数）；`docs/requirements.md:58` 声称「列表 **5 秒**自动刷新」，实现为 4s（`TASK_AUTO_REFRESH_MS = 4000`）。二者均非本包引入，登记备裁。

**〔分件 2／4〕** 原件：`<LOCAL_WORK>/ui-title-open/复核报告.md`

# 复核报告：顶部菜单精简 + 任务标题即入口（独立复核）

- 复核对象：`<MAIN_REPO>`（前端 `frontend/`，后端 `backend/`）
- 被复核施工报告：`<LOCAL_WORK>\ui-title-open\施工报告.md`（226 行，已通读）
- 基线：`d3020ee`（与施工报告一致，未提交）
- 复核日期：2026-10-10
- 复核方式：**独立复现 + 对抗反查**（不采信施工报告的任何结论为既定事实）
- 纪律：**零 git 写操作**（未 add / commit / stash / checkout / reset）；全程只读 + 在 `<REPO_ROOT>` 之外的临时目录 `<LOCAL_WORK>/ui-title-open/scratch/` 内做实验与起服务；报告脱敏（`<MAIN_REPO>` / `<REPO_ROOT>` / `<USER>`）
- 结论：**放行**（0×S1 / 0×S2 / 0×S3；4 项 S4 观察项与 1 项前置遗留，均不阻断）

---

## 0. 复核环境的独立取证

| 项 | 实测值 |
|---|---|
| `git log --oneline -1` | `d3020ee docs: 闭环登记——包①/包②/包⑤/UI 包已修注记 …`（与报告一致，仍在顶） |
| `git status --porcelain` | 恰 12 个 ` M ` 条目，**0 个 `??` 未跟踪** |
| `git diff --numstat` 汇总 | files=12 insertions=19 deletions=143（与报告 §1 完全一致） |
| 端口纪律 | 起服务前 `netstat` 查 5201/5202/5211/5212/5213/8081/5174/18316 **全部空闲**；我自起服务只用 5213（vite）+ 8081（后端）；用完按 PID 停自起进程并复验端口释放（见 §6） |
| 后端库隔离 | 后端 jar 以 **CWD = `<LOCAL_WORK>/ui-title-open/scratch/backend-run`** 启动，其 H2 落在临时目录；`<REPO_ROOT>/data/` 的 `config_mgr_db.mv.db` mtime 复核前后均为 **15:06**（未被触碰）；**未触碰 `backend/data/`（真实开发库）** |
| 用户文件 | `frontend/.env.local` **未读未改**（仅以命令行环境变量覆盖本次 dev server 的 API 指向） |

---

## 1. 门禁复跑【实测】——三绿

### 1.1 `cd frontend && yarn typecheck`

```
$ yarn typecheck
yarn run v1.22.22
$ vue-tsc --noEmit
Done in 4.55s.
TYPECHECK_EXIT=0
```

### 1.2 `cd frontend && yarn build`

```
✓ 1747 modules transformed.
dist/assets/TaskCenterView-kiKbW28z.js         8.67 kB │ gzip: 3.52 kB
dist/assets/DefinitionsView-D64yUYCV.js       15.27 kB │ gzip: 5.22 kB
✓ built in 27.20s
BUILD_EXIT=0
```

产物独立复核（`grep` 直接打 chunk，非报告转述）：

| 串 | `TaskCenterView-kiKbW28z.js` 命中数 |
|---|---|
| `任务详情` | **0** |
| `打开` | **0** |
| `el-drawer` | **0** |
| `导出向导` / `导入向导` | **0** / **0** |
| `任务已删除` | 1 |
| `确定取消任务` | 1 |

（chunk 体积 8673 B = 8.67 kB 与报告一致；`任务详情` 0 命中 ⇒ 抽屉代码确认未进包，取消/删除链路完好。）

### 1.3 仓库根 `node scripts/ci/check-docs.mjs`

```
  [PASS] ① 导航/口径文档无盘符绝对路径
  [PASS] ② 技术栈版本与 pom.xml / package.json 一致
  [PASS] ③ 导航/口径文档引用的文件存在
  [PASS] ④ 端口表与 application.yml / vite.config.ts 一致
         （命名纪律扫描：git ls-files 916 个路径，实扫文本 913 个；豁免 3 个）
  [PASS] ⑤ 命名纪律禁则（git ls-files 全量）
  [PASS] ⑥ .java 探针不参与构建
全部通过：6/6 断言绿。
CHECKDOCS_EXIT=0
```

**门禁结论：三绿，与施工报告一致（PASS）。**

---

## 2. 应然态逐条复核

### 2.1 ① `App.vue` 删两个菜单项 —— PASS【实测】

- 源码：`frontend/src/App.vue:13-15` 恰 3 行 `el-menu-item`：任务中心 / 配置定义 / 数据浏览。
- `git diff` 只删 2 行，无其他改动。
- 浏览器实测：`menubar` 无障碍树 = 「任务中心 配置定义 数据浏览」（3 项，见 §5）。
- 路由与 AI 契约未动（实读 + `git status` 未列出该两个文件）：
  - `frontend/src/router/index.ts:29-40` 的 `/export`、`/import` 路由与 `meta.title` 原样；`router/index.ts:2` 注释仍写「五页齐备」（**属描述页面/路由存在，页面仍在，非失实**）。
  - `frontend/src/stores/workspace.ts:48-53` 的 `PAGE_LABELS`（`export: '导出向导'`、`import: '导入向导'`）= AI 回灌契约，原样保留。
  - `frontend/src/components/AiPanel/AiPanel.vue:329` `TOOL_LABELS` 未动。
  - `workspace.ts:413/417/421` 的 AI「进入导出/导入向导」handler 未动 ⇒ **AI 侧入口无损**。

### 2.2 ② `TaskCenterView.vue`：标题即入口 / 删「打开」/ 拆抽屉与死代码 —— PASS【实测】

- 标题按钮绑定：`TaskCenterView.vue:47` `@click="restoreWizard(row)"`（原为 `openDetail(row)`）；`TaskCenterView.vue:269` `taskStore.restoreWizard(row.task.id ?? 0, row.task.type)` → `stores/task.ts:345-351` 按类型返回 `{name: exportWizard|importWizard, query:{taskId}}` ⇒ **taskId / type 参数无损**（且与原「打开」按钮调用同一函数，`row.task.id ?? 0` 兜底亦与原实现逐字相同，零行为变化）。
- 操作列：`width="160"`（原 200），「打开」按钮已删，仅剩「取消」「删除」（`:90-101`）。
- 抽屉：`el-drawer` 在 `TaskCenterView.vue` **0 命中**（`grep -n "el-drawer\|drawer\|任务详情\|详情"` 无输出）。
- 行数：worktree **357** 行（`git show HEAD:… | wc -l` = 466）⇒ 净 **−109**；报告自述「356 行（净 −110）」**差 1**（见 S4-02）。
- 死代码：`openDetail` / `detailVisible` / `jobTagType` / 相关 3 个 computed / 6 处失用 import 在 `TaskCenterView.vue` 内 **0 命中**。
- 首次编译期铁证：`tsconfig.json` 开着 `noUnusedLocals` + `noUnusedParameters`，残留的失用 import 会被 `yarn typecheck` 直接判红 —— 门禁绿即反证 import 已清干净。

### 2.3 ③ `stores/task.ts` 手术 —— PASS【实测】

| 断言 | 实测 |
|---|---|
| `openTask` 全仓 0 调用点 | 全 src 仅剩 `openTaskEvents`（`api/tasks.ts:105/134`、`utils/job-watch.ts:57`），**无一处 `openTask(`** |
| `hasRunningJob` 0 | 0 命中 |
| `latestJob` getter 已删、无调用 | 0 处 getter 调用；残留 `latestJob` 全是 `TaskSummary.latestJob` **字段**（`types/task.ts:118`、`TaskCenterView.vue:65/68/69/70/74/264/274`），与本手术无关 |
| `refreshCurrent` 保留 | `stores/task.ts:197-217` 在位；调用点 = `task.ts:257`(setImportMode)/`:269`(startJob)/`:282`(cancelJob)/`:325`(watchPublishOutcome) + `workspace.ts:367`(goto_step)/`:377`(select_defs)/`:400`(run_job)，另 `workspace.ts:403` → `watchPublishOutcome` → `task.ts:325` 链完好 |
| `closeTask`/`loadIssues`/`watchPublishOutcome` 保留 | 均在位（`:219/:293/:312`） |
| `getTaskOverview` 调用点恰 1 处活调用 | `api/tasks.ts:20`（定义）+ `:120`（对象成员）+ `stores/task.ts:204`（`refreshCurrent` 内）⇒ **唯一活调用点即 refreshCurrent**，与报告一致 |
| `workspace.ts` 是否被误伤 | `git status` 未列出该文件 ⇒ **物理未改**（调用链不可能被误伤） |

### 2.4 ④ 后端 `TaskController.java` 仅注释 —— PASS【实测】

`git diff` 该文件 **1 行**：`:54` `任务详情概览：任务 + 全部作业 + 文件，供详情抽屉使用。` → `…任务 + 全部作业 + 文件。`
端点 `GET /{id}/overview`（`:56` 起）签名与实现零变化；未跑后端测试（仅 Javadoc 一变），但本次**独立运行了同一 jar**并实调该端点族（§5），编译面与行为面均无风险。

### 2.5 ⑤ 文档注记 7 处（只追加 / 原表述留痕 / 零删） —— PASS【实测】

用脚本把 `git show HEAD:<file>` 逐行与 worktree 逐行做**前缀包含比对**，结果（`scratch/doc-lines.mjs`）：

| # | 文件:行 | 原行是否逐字保留 | 载体 | 表体完整性 |
|---|---|---|---|---|
| 1 | `docs/verification-results.md:150`（U-02） | 是（commonPrefix=65/66，仅尾 `\|` 让位） | 表格行（3 列） | ASCII `\|` 计 **4→4**（新注记用全角 `｜`，不破列） |
| 2 | `docs/verification-results.md:174`（TM-09） | 是（192/193） | 表格行（3 列） | 4→4 |
| 3 | `docs/test-cases.md:212`（TM-08） | 是（42/43） | 表格行（3 列，文件末行） | 4→4 |
| 4 | `docs/M2-排期计划.md:282`（R-90） | 是（1172/1174，尾 ` \|` 让位） | 表格行（2 列） | 3→3 |
| 5 | `docs/evidence/S44a-前端基座验证.md:192` | 是（230/230，**纯追加**） | 列表项 | 不适用（0→0） |
| 6 | `docs/evidence/S44b-五页视图验证.md:269` | 是（130/131） | 表格行（4 列） | 5→5 |
| 7 | `docs/evidence/S44b-五页视图验证.md:329` | 是（171/172） | 表格行（4 列） | 5→5 |

- **零删确认**：7 处新增文本全部为单行（无换行注入），且原行正文逐字保留 —— 唯一被替换的字符是表行尾部的 ASCII `|` 分隔符，它被挪到注记之后，行尾仍以 ASCII `|` 收尾 ⇒ **表体结构未破、注记确实落在表体内**。
- `git diff --numstat` 每个文档文件均为 **N/N**（行数不变）⇒ 反证「只追加、未新增/删除行」。
- M2-排期计划.md 的 R-90 行：原正文（含「① 行级进度列…② DefinitionsView 910px…」）整段留痕，注记以「；**另注（2026-10-10 UI 改造）**」追加，遵守该表只追加纪律。
- 报告 §1.7「扫过但未改」的 4 个文件（`docs/technical-design.md`、`docs/s4/S4.4-前端合流规格.md`、`docs/03-技术方案文档-v2.1.md`、`docs/merge/03-技术方案文档.md`）经 `git status` 反证**确实未被改动**。

---

## 3. 对抗反查（逐条给证据）

### a. 抽屉是否删净 —— PASS

`grep -rn`（`frontend/src` 全域，8 个符号一次扫）：

```
openDetail|detailVisible|jobTagType|JOB_TYPE_LABELS|SEVERITY_LABELS|readImportMode|openTask\b|hasRunningJob
→ DefinitionsView.vue:64/78/282/341/343   （配置定义页自有抽屉，同名独立函数，属正常，未动）
→ ImportWizardView.vue:233/400            （readImportMode，向导自身在用，正常）
→ utils/format.ts:73/80                   （两个 LABELS 的**定义**）
→ types/task.ts:138                       （readImportMode 定义）
```

- `TaskCenterView.vue` 内：`openDetail`/`detailVisible`/`jobTagType`/`el-drawer`/`任务详情`/`详情` **全 0 命中**。
- 全 src 内 `openDetail`/`detailVisible` 命中仅剩 `DefinitionsView.vue`（无关页，未动）—— 与报告口径一致。
- 补充反查：`ExportWizardView.vue` 内**不存在** `SEVERITY_LABELS` 的替身（该文件只有 1 处 `severity === 'ERROR'` 的**值**比较，`:235`，不依赖 `utils/format` 的标签表）⇒ `SEVERITY_LABELS` 确为无读者导出（见 S4-01）；此项不影响「抽屉是否删净」的判定（本节 a 项已由 0 命中直接证明）。

### b. 标题链路正确性 —— PASS【实测 + 源码】

见 §2.2；另有活体证据（§5）：点 `ReviewTitleLink_EXPORT` → `http://localhost:5213/export?taskId=1`；点 `ReviewTitleLink_IMPORT` → `http://localhost:5213/import?taskId=2`。操作列 DOM 实测每行 `["取消","删除"]`。

### c. store 手术干净度 —— PASS（附 1 项 S4 观察）

- `openTask` / `hasRunningJob` 全仓 0；`latestJob` getter 0（残留均为 `TaskSummary` 字段）。
- `refreshCurrent` 调用点 7 处（`task.ts` ×4、`workspace.ts` ×3）全部在位，且 `workspace.ts` 物理未改。
- `getTaskOverview` 活调用点 = `stores/task.ts:204`（`refreshCurrent` 内）**恰 1 处**。
- **S4 观察（新发现）**：`currentTaskId` 全仓**只有 `= null` 一种写入**（`task.ts:95` 初始化、`:220` closeTask），**无任何置非空点** ⇒ `refreshCurrent` 首行 `if (taskId === null) return` 恒命中，该 action 在全部 7 个调用点**全空转**；连带 `currentTask`/`currentJobs`/`currentFiles`/`issues`/`issuesTotal`/`detailLoading`/`publishConflict` 这一整族 state **无任何视图读者**（`grep` 在 `*.vue` 内 0 命中，`ImportWizardView` 的 `publishConflict` 是自己的局部 ref）。这是施工报告 O-1 的坐实与**强化**：改造前至少抽屉路径会真正写入这族 state，改造后写入源与读者同时消失。**无用户可见回归**（读者已随抽屉一并拆除），故仍判观察级。

### d. 菜单删除波及面 —— PASS

- `App.vue` 恰 3 项（§2.1）。
- `导出向导`/`导入向导` 在前端全量残留（`grep -ro` 计数 **24 处 / 9 个文件**）逐条分类：
  - **合法残留**：`router/index.ts:2/32/38`（路由与 `meta.title`）、`stores/workspace.ts:50-51`（AI 契约 `PAGE_LABELS`）、`:414/418/422`（AI 回灌文案）、`types/ai.ts:20`、`types/workspace.ts:73`、`utils/frontend-tools.ts:71/476/981`、`components/AiPanel/AiPanel.vue`（`TOOL_LABELS`）、`ExportWizardView.vue:3`（向导页标题）/`:22/899/902`、`ImportWizardView.vue:3/22/361/939/949`、`components/wizard/*` 注释 —— 全部为「页面仍在 ⇒ 描述页面/契约」的合法面。
  - **非法残留（菜单/入口）**：**0**。且核实「删菜单后向导仍可达」：人侧 = 创建任务跳转 + 点任务标题（两者均活体实测）、AI 侧 = `workspace.ts:413/417/421` 的 handler ⇒ **可达性未断**。

### e. 文档纪律 —— PASS

见 §2.5（含表体完整性、零删、只追加三项实证）。`docs/evidence/` 三处的越界争议另见 §4.2。

### f. 命名纪律与脱敏 —— PASS（附 1 项前置遗留）

- **我自己的扫描器**（非复用门禁）：对 12 个改动文件全文做 `main`+分隔符+`v2` 的 **NFKC / NFKD / 零宽删除态 / 零宽分隔态 / Unicode 连字符族（U+2010-2015、U+2212、U+FF0D）/ 全角斜杠** 折叠后匹配。
- 对 **`git diff` 新增行**（真正由本包写入的文本）单独扫描：**命中 0**。
- 对改动文件全文（含历史行）扫描：命中 1 个位置 —— `docs/evidence/S44b-五页视图验证.md:20` 的 `<TEMP_ROOT>/<OLD_REPO_DIR_CONCAT>-db-backup.mv.db`（`main`+`v2` **零分隔**）。**已用 `git show HEAD:` 反证该行存在于基线**（命中数 1，改造前即如此）⇒ **非本包引入**，且 `check-docs` 断言⑤未命中该形态（口径未覆盖「零分隔」写法）。列为前置遗留 S4-05，不计本包账。
- 脱敏扫描（改动文件全文 + 施工报告 + 本复核报告）：盘符绝对路径 `[A-Za-z]:[\\/]`、用户名 `<USER>`、内网 IP（10./192.168./172.16-31.）、`127.0.0.1`、凭据（`sk-`/`ghp_`/`Bearer`）**全部 0 命中**。
  - 施工报告侧确认：仓库一律写作 `<MAIN_REPO>` / `<REPO_ROOT>`；端口 `8081`/`5202`/`5174` 为回环开发端口，非内网地址；webbridge 守护进程以 `127.0.0.1:10086` 表述（回环，合规）。

### g. git 态 —— PASS【实测】

```
$ git status --porcelain
 M backend/.../TaskController.java
 M docs/M2-排期计划.md
 M docs/evidence/S44a-前端基座验证.md
 M docs/evidence/S44b-五页视图验证.md
 M docs/test-cases.md
 M docs/verification-results.md
 M frontend/src/App.vue
 M frontend/src/api/tasks.ts
 M frontend/src/stores/task.ts
 M frontend/src/views/ExportWizardView.vue
 M frontend/src/views/ImportWizardView.vue
 M frontend/src/views/TaskCenterView.vue
$ git status --porcelain --untracked-files=all | grep '^??'   → 无
$ git log --oneline -1   → d3020ee（仍在顶，未被提交）
```

- 工作树恰为施工报告的 12 个改动文件；**无未跟踪杂物**（`frontend/dist` 被 gitignore，不进 diff）。
- 复核全程（含我在仓外起的服务、跑的双向实验）结束后**重跑 `git status` 结果逐字不变**。

---

## 4. 两处自述偏差的独立判断

### 4.1 `getTaskOverview` 保留是否正确 —— **判断：正确，报告处置无误**

报告依据「删 `openTask` 后 `getTaskOverview` 失去唯一调用点」的**前提**被我独立复现为**不成立**。我在 `<REPO_ROOT>` 之外做了一份 **frontend 源码副本 + node_modules 目录联接（junction）**，先验证副本基线 `vue-tsc --noEmit` 退出码 0（与仓库同绿，证明副本可信），再做两步删除：

```
# 步骤 1：删 api/tasks.ts 内 getTaskOverview 函数体（保留 tasksApi 成员）
$ node node_modules/vue-tsc/bin/vue-tsc.js --noEmit
src/api/tasks.ts(115,3): error TS18004: No value exists in scope for the shorthand property 'getTaskOverview'.
STEP1_EXIT=2

# 步骤 2：再删 tasksApi 对象成员
$ node node_modules/vue-tsc/bin/vue-tsc.js --noEmit
src/stores/task.ts(204,41): error TS2339: Property 'getTaskOverview' does not exist on type '{ listTasks: … }'.
        （类型串为便于阅读的截断；文件:行列与 TS 码逐字一致）
STEP2_EXIT=2
```

**两条报错的文件:行列与施工报告 §4.1 逐字一致**（我独立跑出，非转述）。结论：

1. 「删 `getTaskOverview`」与「`refreshCurrent` 保留不动」＋「`yarn typecheck` 必须绿」**三者互斥**，报告的前提修订（裁决书「唯一调用点」判断有误）**成立**；
2. 取舍正确：复归函数 + 对象成员、只把注释去抽屉化，是满足门禁的唯一自洽解；
3. 附带后果照单披露：`getTaskOverview` 因此**保留了形式上的活调用点**，且该调用点因 `currentTaskId` 永不置非空而**实际不执行**（§3c）—— 属「名义活、执行死」，报告已登记为 O-1 并建议单独立项，**我同意**，并建议立项时把 `getTaskOverview` + `refreshCurrent` + `currentTaskId` + 该族 state 一并收口（届时可同批删净）。

### 4.2 `docs/evidence/` 3 处注记是否越界 —— **判断：不越界，建议保留**

先还原 DOC#5 原文（实读 `scripts/ci/check-docs.mjs`，**注：报告引用为 `:19-21`，实测该段位于 `:20-22`，内容引用准确、仅行号漂移 1**，见 S4-03）：

```
 * 范围边界（裁决 7，重要）：①②③④ 的扫描范围是下面的 NAV_DOCS 显式清单 ——
 *   **明确排除 `docs/evidence/` 历史证据文档**（其中出现的 8080/182xx 端口号、旧版本号属历史记录，不回改）。
 *   ⑤ 相反，按 `git ls-files` **全量**（历史证据文档同样受命名纪律约束）。
```

我的独立判断（三点）：

1. **DOC#5 的「不回改」是门禁适用范围声明，不是「禁止追加注记」的禁令。** 它的落点是「①②③④ 不去检查历史证据文档里的过期端口/版本号」，语义是「过期的历史数字按现状接受」，而不是「该文件的文本不可被追加任何内容」。把它读成后者，会使任何跨期对齐注记都被永久禁止 —— 这既不是该注释的字面含义，也不符合「历史证据要可追溯」的初衷。
2. **本次做法恰恰没有破坏「历史性」**：三处均**逐字保留原表述**（含「5 项菜单」「五页导航」等当时实测读数），注记以 `｜**注记（2026-10-10 UI 改造）**` 形式追加，并显式写明「上列 … 为改造前实测留痕，原表述保留」。读者不会误认为证据被重测。
3. **零风险**：断言 ①②③④ 本就不扫 `docs/evidence/`，追加文本不可能触发新红；断言 ⑤ 全量扫且已 PASS。若不追加，则会留下「文档称 5 项菜单而实现为 3 项」的**双向失实**（既失实又无注记），比追加更糟。

⇒ **维持这 3 处注记**。若为绝对保守起见要回退，代价是「菜单清单口径在证据文档内失实」，我判为**得不偿失**（回退需 git 写操作，亦与「零 git 写」纪律冲突，须由你侧决定）。

---

## 5. 行为级抽查【实测】（可选但已执行）

**端口纪律执行**：起服务前 `netstat` 确认 5201/5202/5211-5213/8081/5174/18316 全空闲；自起服务用 **5213（vite）** + **8081（后端 jar，CWD＝仓外临时目录）**；未修改用户 `.env.local`（仅命令行 `VITE_API_BASE=http://localhost:8081 VITE_DEV_PORT=5213` 覆盖）。

| 断言 | 实测读数 |
|---|---|
| 首页菜单恰 3 项 | `menubar` 无障碍树 = 「任务中心 配置定义 数据浏览」；`menuitem` 恰 3 个（e2/e3/e4），**无** `el-menu-item` 级向导项 |
| 任务标题是**可点击链接按钮** | 任务行标题以 `role=button` 出现在无障碍树（`e30 button ReviewTitleLink_EXPORT`、`e25 button ReviewTitleLink_IMPORT`） |
| 操作列 | 两行任务实测操作按钮 = `["取消"(disabled),"删除"]`，**无「打开」** |
| 点标题 → 向导（EXPORT） | 点 `ReviewTitleLink_EXPORT` → `tab.url = http://localhost:5213/export?taskId=1`；向导页渲染（定义清单/上一步/下一步），`role=dialog`（=el-drawer）计数 **0** |
| 点标题 → 向导（IMPORT） | 回退后点 `ReviewTitleLink_IMPORT` → `http://localhost:5213/import?taskId=2` |
| 抽屉已无 | 任务中心页（`scope=document` 全量树，47 元素）**无任何 `role=dialog`**；导入向导页 `scope=document` 过滤 `role=dialog` → **total=0**；导出向导页（`scope=viewport`，39 元素）快照内**无 dialog 元素** |
| 数据面 | 后端实起（H2 种子迁移完成），`POST /api/tasks` 建 2 条（#1 EXPORT / #2 IMPORT），列表接口 `totalElements`、`latestJob:null` 均正常；**全部落在仓外临时库** |

**环境限制（据实说明）**：抽查后段，桌面浏览器控制权在本轮被释放（`BROWSER_UNAVAILABLE: Browser control requires a running turn`），故「直连 `/export`（无 taskId）时『尚未绑定任务』告警仍在」这**一项**未做活体复核；我改用**依赖源码 + 已安装 element-plus 构建产物**替代取证：

- `ExportWizardView.vue:19-30` / `ImportWizardView.vue:19-30`：`el-alert` 带 `title="尚未绑定任务"` + `description` + **default slot（按钮）**；
- 实读 `frontend/node_modules/element-plus/es/components/alert/src/alert.vue_vue_type_script_setup_true_lang.mjs:58`：
  `renderSlot(_ctx.$slots, "default", {}, () => [createTextVNode(toDisplayString(__props.description), 1)])`
  ⇒ **有 default slot 时 `description` prop 被 slot 覆盖、不渲染**。**【实测（源码级）】** 施工报告 §1.5 关于「该 `description` 本就不显示」的判断**成立**，向导页 `:22` 的文案改动因此确属零用户可见变化的源码口径清理。

---

## 6. 对抗反查新发现（分级）

**S1 / S2 / S3：无。**

| 编号 | 级别 | 发现 | 证据 | 建议 |
|---|---|---|---|---|
| S4-01 | S4 | **3 个孤儿导出残留**：`utils/format.ts:73 JOB_TYPE_LABELS`、`:80 SEVERITY_LABELS`、`stores/task.ts:58 jobStatusLabel` 在本包改造后**全仓 0 调用点**（`git grep` 于 HEAD 证明三者此前唯一使用者就是被拆掉的抽屉） | `grep -rn` 于 `frontend/`（排除 node_modules/dist）：三者各只命中自身定义行 | 与「删全部死代码」的目标口径不符（任务清单只点名「失用 import」，import 确已删净）。建议顺手删这 3 个导出，或在报告中显式声明「按『只删 import』口径保留」。**不阻断放行** |
| S4-02 | S4 | 报告 §1.2 自述 `TaskCenterView.vue` **466 行 → 356 行（净 −110）**；实测 HEAD 466 → worktree **357**（净 **−109**），**差 1** | `wc -l frontend/src/views/TaskCenterView.vue` = 357；`git show HEAD:… \| wc -l` = 466 | 报告数字勘误即可，无代码影响 |
| S4-03 | S4 | 报告 §1.7 引用 `scripts/ci/check-docs.mjs:19-21` 的 DOC#5「历史证据不回改」，实测该段位于 `:20-22`（行号漂移 1）；引用内容准确 | `sed -n '1,40p' scripts/ci/check-docs.mjs` | 勘误 |
| S4-04 | S4 | **O-1 强化**：`currentTaskId` 全仓只有 `= null` 写入（无置非空点）⇒ `refreshCurrent` 在 7 个调用点**全空转**；`currentTask`/`currentJobs`/`currentFiles`/`issues`/`issuesTotal`/`detailLoading`/`publishConflict` 整族 state 在 `*.vue` 内 **0 读者**；`closeTask` 在 `removeTask` 中的分支（`task.ts:243`）恒不命中 | `grep -rn "currentTaskId = "` → 仅 1 处（`= null`）；`grep` 该族字段于 `*.vue` → 0 | 无用户可见回归（读者已随抽屉拆除），同意报告登记为 O-1 并单独立项；立项时建议与 S4-01 同批收口 |
| S4-05 | S4（前置遗留，非本包） | `docs/evidence/S44b-五页视图验证.md:20` 含 `<OLD_REPO_DIR_CONCAT>-db-backup.mv.db`（`main`+`v2` **零分隔**形态），我的扫描器命中 1 处；`check-docs` 断言⑤未命中该形态 | `git show HEAD:… \| grep -c <OLD_REPO_DIR_CONCAT>-db-backup` = **1** ⇒ 基线即存在；本包新增行命中 **0** | 归前置欠账（建议在命名纪律口径中补「零分隔」形态），**不计本包账、不阻断放行** |

---

## 7. 遗留副作用与清理（我侧）

| 项 | 状态 |
|---|---|
| 我起的 vite dev（5213，pid <PID> + esbuild 子进程 25556） | **已 `taskkill /T /F` 终止**，端口 5213 无 LISTENING |
| 我起的后端 jar（8081） | **已终止**（`TaskStop`），端口 8081 无 LISTENING；`tasklist` 已无 java.exe |
| 仓外实验目录 `<LOCAL_WORK>/ui-title-open/scratch/fe`（含指向仓库 node_modules 的 junction） | **junction 先用 `cmd rmdir` 单独摘除**（绝无 `rm -rf` 穿透目标），随后删除 `scratch/fe` 与 `scratch/backend-run`；已复验仓库 `frontend/node_modules` 完好（153 项、`vue-tsc/package.json` 在位） |
| 保留的复核产物 | `<LOCAL_WORK>/ui-title-open/scratch/{doc-audit.mjs, doc-lines.mjs, naming.mjs, pipes.mjs, vite-dev.log}`（可复跑的证据脚本） |
| 我开的浏览器标签 | 在 **Kimi Code Desktop 自带浏览器面板**（非用户真实浏览器）中留 1 个标签（当前停在 `/import?taskId=2`）；抽查后段控制权被释放（`BROWSER_UNAVAILABLE`），**我无法在本轮自行关闭** —— 面板内手动关闭即可，无残留进程 |
| 施工方自述遗留（复核可见部分） | webbridge 守护进程 `127.0.0.1:10086` 仍在监听（用户浏览器扩展侧，既有基础设施）；施工报告 §4.3 自述在**用户真实浏览器**留了 1 个标签组——该点我**未做活体核查**（不越界操作用户浏览器），据实标注为**未复核项** |
| 仓库侧 | `frontend/dist/` 因我的 `yarn build` 重建（gitignore，不进 diff）；`<REPO_ROOT>/data/config_mgr_db.mv.db` mtime 复核前后均为 15:06（未被我触碰） |

---

## 8. 总结论

**放行（0×S1 / 0×S2 / 0×S3；4 项 S4 观察 + 1 项前置遗留，均不阻断）。**

- 门禁三绿 **全部由我独立复跑复现**（`typecheck` / `build` / `check-docs` 6/6），产物级证据（chunk 内 `任务详情`/`打开`/`el-drawer` 均 0 命中）与源码级证据（`noUnusedLocals` 下的类型门禁）双向互证。
- 五条应然态**逐条 PASS**：菜单 3 项、标题即入口（taskId/type 无损、活体实测两类型均正确跳转）、抽屉与死代码删净（仅存 S4-01 的 3 个孤儿导出）、store 手术干净（`openTask`/`hasRunningJob`/`latestJob` 0 调用点、`refreshCurrent` 链未动）、后端仅 1 行注释、文档 7 处注记**只追加 + 原表述逐字留痕 + 表体未破**。
- 报告自述的**两处偏差我均独立判定为处置正确**：`getTaskOverview` 保留（我复现出与报告逐字一致的两条 TS 报错，三条件互斥成立）；`docs/evidence/` 3 处注记**不越界、建议保留**（DOC#5 的「不回改」是门禁范围声明，非追加禁令）。
- 需你侧决策/登记的 4 件小事（均可不改即放行）：S4-01 是否顺手删 3 个孤儿导出；S4-02/S4-03 报告数字与行号勘误；S4-05 命名纪律口径是否补「零分隔」形态。
- 施工报告 §5【待裁决】的「`docs/requirements.md:54-55` 任务详情需求面失去实现」属**需求面收缩**，超出本包施工边界，我复核同意其为**真实的待裁决项**（本包未动该文件，符合纪律）；其中「`requirements.md:58` 声称 5 秒刷新、实现 4 秒（`TASK_AUTO_REFRESH_MS = 4000`）」经我核对源码属实，属**既有前端/文档漂移**，非本包引入。

**〔分件 3／4〕** 原件：`<LOCAL_WORK>/ui-title-open/修补报告.md`

# 修补报告：UI 包四项增量修补

- 修补对象：`<MAIN_REPO>`（前端 `frontend/`，yarn；后端未动）
- 基线：`d3020ee`（与施工/复核报告一致，仍在顶，**未提交**）
- 依据：`<TEMP_ROOT>/ui-title-open/复核报告.md`（316 行，S4-01 / S4-02 / S4-03 / S4-05 与 §8 待裁决）
- 修补日期：2026-10-10
- 纪律：**零 git 写操作**（无 add / commit / stash / checkout / reset；全程只读 + 编辑工作树文件）；报告脱敏（`<MAIN_REPO>` / `<REPO_ROOT>` / `<TEMP_ROOT>` / `<USER>`）
- 结论：**四项全部处置完毕**；`typecheck` / `build` / `check-docs`（6/6）三绿；工作树 **15 个改动文件、0 个未跟踪**

## 0. 结果摘要

| # | 修补项 | 结果 |
|---|---|---|
| ① | 删 3 个孤儿导出（S4-01） | **已删**（含必需的 import 连带清理）：`utils/format.ts` 2 个、`stores/task.ts` 1 个；前置 grep 证实全仓 0 调用点（含模板 / 类型导入 / re-export） |
| ② | `docs/requirements.md` 三处注记回灌 | **已追加 3 处注记**（`:53` / `:55` 承载 `:54-55` 段 / `:58`）；原表述逐字留痕、纯追加、行数不变 |
| ③ | 施工报告勘误 | **一半已改、一半按反证未改**：`:41` 已改 `466→357`；`:198` 的 `check-docs.mjs:19-21` **实测正确，未改**（复核 S4-03 的 `:20-22` 才是错的，见 §3.2） |
| ④ | 命名擦边登记（`S44b:20` 的 `<OLD_REPO_DIR_CONCAT>`） | **本补丁未处置（守纪律）**；登记时发现该处**已被同批另一任务修复**、规则亦已扩展，现态 `git grep <OLD_REPO_DIR_CONCAT>` = **0 命中**（见 §4） |

## 1. 修补① 删 3 个孤儿导出【实测】

### 1.1 前置取证：全仓 0 调用点（含模板 / 类型导入 / re-export）

```
$ cd <MAIN_REPO>/frontend && grep -rn "JOB_TYPE_LABELS" src/     → 仅 src/utils/format.ts:73（自身定义）
$                                grep -rn "SEVERITY_LABELS"  src/ → 仅 src/utils/format.ts:80（自身定义）
$                                grep -rn "jobStatusLabel"   src/ → 仅 src/stores/task.ts:58（自身定义）
$ 递归扫 frontend/{tests,test,e2e,scripts,public}（若存在）      → 0 命中（仅 scripts/public 存在，均 0）
$ 全仓扫 *.md/*.mjs/*.java/*.json/*.yml（排除 node_modules）     → 0 命中（无文档枚举这 3 个导出）
$ grep -n "TASK_TYPE_LABELS|STEP_LABELS|utils/format" docs scripts … → 仅 S44a:103 一句提及 utils/format.ts，
                                                                        不涉导出清单 ⇒ 无文档失实面
```

**HEAD 反证（三者此前唯一使用者就是被拆掉的详情抽屉）**：

```
$ git grep -n "JOB_TYPE_LABELS|SEVERITY_LABELS|jobStatusLabel" HEAD -- .
HEAD:frontend/src/stores/task.ts:58            export function jobStatusLabel(…)
HEAD:frontend/src/utils/format.ts:73           export const JOB_TYPE_LABELS …
HEAD:frontend/src/utils/format.ts:80           export const SEVERITY_LABELS …
HEAD:frontend/src/views/TaskCenterView.vue:172   {{ JOB_TYPE_LABELS[row.jobType] }}
HEAD:frontend/src/views/TaskCenterView.vue:176   {{ jobStatusLabel(row.status) }}
HEAD:frontend/src/views/TaskCenterView.vue:210   {{ SEVERITY_LABELS[row.severity] }}
HEAD:frontend/src/views/TaskCenterView.vue:239   import { useTaskStore, jobStatusLabel } from '@/stores/task'
HEAD:frontend/src/views/TaskCenterView.vue:241   import { … JOB_TYPE_LABELS … SEVERITY_LABELS … } from '@/utils/format'
```

⇒ 4 个读者（172/176/210）与 2 条 import（239/241）随抽屉一并删除后，**0 调用点**成立，无「有调用点则保留」的情形。

### 1.2 改动明细

| 文件 | 改动 | 净变化 |
|---|---|---|
| `frontend/src/utils/format.ts` | 删 `JOB_TYPE_LABELS`（6 行）、`SEVERITY_LABELS`（5 行）；**连带**将 `import type { JobType, ProgressView, Severity } from '@/types/job'` 收窄为 `import type { ProgressView } from '@/types/job'`（必需：`tsconfig` 开着 `noUnusedLocals`，不收窄必判红） | 109 → **96 行**；`git diff --numstat` = 1 / 14 |
| `frontend/src/stores/task.ts` | 删 `/** 作业状态 → 中文标签。 */` + `jobStatusLabel`（17 行 + 1 空行） | 387 → **369 行**；其余原有改动一字未动；该文件 numstat 由 1/14 变为 **1/32** |

**import 面已核实无需再动**：`stores/task.ts` 的 `JobStatus`（现 `:365` `isFinal`）与 `JobType`（现 `:248` `startJob` 签名）仍在使用，**保留**；`format.ts` 的 `JobType` / `Severity` 二者在删除后成为唯一失用者，已一并清除（现存 `JobType|Severity` 命中 = 0）。

**影响面：零**——两者此前均无 import 者、无导出桶（无 re-export 文件），删除不改变任何调用链；`TaskCenterView` 产物 chunk 哈希与体积在本次 `yarn build` 后仍为 `TaskCenterView-kiKbW28z.js` / 8.67 kB（与复核报告一致，未受牵连）。

## 2. 修补② `docs/requirements.md` 三处注记回灌【实测】

### 2.1 事实核验（先核后注，注记内容全部有实测支撑）

| 原表述 | 实测 | 证据 |
|---|---|---|
| `:53`「每行显示…**配置项数**…」 | **无该列**。任务中心列表实为 8 列：任务 / 类型 / 状态 / 进度 / 当前步骤 / 创建时间 / 更新时间 / 操作 | `frontend/src/views/TaskCenterView.vue` 的 `el-table-column label=` 全集（`:43/51/58/63/79/82/85/88`）。另核：后端摘要**确有** `itemCount`（`frontend/src/types/task.ts:119`）但前端未渲染；源分支（v1）`TaskListView.vue` 列为 #/类型/任务标题/当前步骤/状态/创建时间/操作，**亦无**该列 ⇒ 该列表述自始未落地 |
| `:54-55`「任务详情：流程步骤进度、各配置项条目状态、作业历史、关联文件清单」 | 唯一实现（任务中心 `el-drawer` 详情抽屉）**已按用户裁决整体拆除** | 复核报告 §2.2（`TaskCenterView.vue` 内 `el-drawer`/`任务详情` 0 命中；产物 chunk 内 `任务详情`/`el-drawer` 0 命中）；本补丁未动该文件 |
| `:58`「列表 5 秒自动刷新」 | **4s** | `frontend/src/stores/task.ts:32` `export const TASK_AUTO_REFRESH_MS = 4000`；`:157-168` `startAutoRefresh`（`setInterval` 在 `:162-166`，tick 前查 `loading` 防重叠） |

### 2.2 注记落点与文本（载体 = 行尾，整行重写）

| 落点 | 注记段 |
|---|---|
| `:53` 行尾 | `｜**注记（2026-10-10 UI 改造）**：本列口径失实——实测任务中心列表**无「配置项数」列**（实列 = 任务/类型/状态/进度/当前步骤/创建时间/更新时间/操作；后端摘要虽含 `itemCount`，前端未渲染；源分支视图亦无该列）；原表述按只追加纪律保留留痕。` |
| `:55` 行尾（`:54-55` 段的段末） | `｜**注记（2026-10-10 UI 改造）**：**需求面收缩**——本需求的唯一实现（任务中心「任务详情」抽屉）已按用户裁决整体拆除；任务详情改由导出/导入向导页承载（点任务标题即进入向导并在 `?taskId=` 带入任务）；上列「各配置项条目状态 / 作业历史 / 关联文件清单」不再有任务中心侧实现；原表述按只追加纪律保留留痕。` |
| `:58` 行尾 | `｜**注记（2026-10-10 UI 改造）**：刷新间隔口径纠正——实测本仓任务中心列表为 **4s** 自刷（`frontend/src/stores/task.ts` 的 `TASK_AUTO_REFRESH_MS = 4000`，模块级 `setInterval`，每次 tick 前查 `loading` 防重叠），非 5 秒；原表述按只追加纪律保留留痕。` |

**载体选择说明**：`:54-55` 是一条跨两行的列表项，注记落在**段末（`:55`）**而非行中（`:54` 行尾是顿号「、」，插入注记会截断句子）；`:53`（列表项末行）与 `:58`（单行项）均落在自身行尾。

### 2.3 纪律验证（脚本 `scratch/prefix-check.mjs`，与 HEAD blob 逐行比对）

```
HEAD 行数=140  worktree 行数=140        （wc -l：139 / 139，零增删行）
--- L53 --- 原行逐字为前缀: YES | 原行长度=46  新行长度=187  追加=141
--- L55 --- 原行逐字为前缀: YES | 原行长度=43  新行长度=214  追加=171
--- L58 --- 原行逐字为前缀: YES | 原行长度=36  新行长度=221  追加=185
差异行数=3
ASCII 竖线总数 HEAD=130  WT=130          （零新分隔符、零结构破坏）
新增文本·盘符绝对路径: 0 命中 / 用户名: 0 命中 / 内网 IP: 0 命中
```

⇒ 三处均为**纯追加**：原行逐字为前缀、差异行恰 3 行、总行数与竖线数不变、新增文本零敏感命中。`git diff --numstat docs/requirements.md` = **3 / 3**。

## 3. 修补③ 施工报告勘误

工具：`<TEMP_ROOT>/ui-title-open/施工报告.md`（仓外文件，可直接改）。

### 3.1 已改：`:41` 行数勘误（S4-02）

```
改前：`frontend/src/views/TaskCenterView.vue`：**466 行 → 356 行（净 −110）**。
改后：`frontend/src/views/TaskCenterView.vue`：**466 行 → 357 行（净 −109）**。
```

实测依据：`wc -l frontend/src/views/TaskCenterView.vue` = **357**；`git show HEAD:… | wc -l` = **466**。改后文本重新脱敏扫描：盘符绝对路径 0 命中、`<USER>` 名 0 命中。

### 3.2 未改（**反证，建议撤销 S4-03**）：`:198` 的 `check-docs.mjs:19-21`

派单要求「`check-docs.mjs:19-21` 实为 `:20-22`」。**实测该段就在 `:19-21`**：

```
$ awk 'NR>=19 && NR<=21 {printf "%d\t%s\n", NR, $0}' scripts/ci/check-docs.mjs
19	 * 范围边界（裁决 7，重要）：①②③④ 的扫描范围是下面的 NAV_DOCS 显式清单 ——
20	 *   **明确排除 `docs/evidence/` 历史证据文档**（其中出现的 8080/182xx 端口号、旧版本号属历史记录，不回改）。
21	 *   ⑤ 相反，按 `git ls-files` **全量**（历史证据文档同样受命名纪律约束）。
22	 */
$ git show HEAD:scripts/ci/check-docs.mjs | awk 'NR>=19&&NR<=22'   → 同上（19/20/21 三行 + 22 = `*/`）
$ git status --porcelain scripts/ci/check-docs.mjs                 → 空（该文件当时未被修改，行号无漂移）
```

DOC#5 段恰为 3 行（范围边界声明 + 明确排除 + ⑤ 相反），即 **`:19-21`**；`:22` 是块注释收尾 `*/`。复核报告 §4.2 引用的正文内容与 `:19-21` 逐字一致，唯 `:20-22` 的行号判断**偏移 +1**（疑为按 `sed -n '1,40p'` 视图计数时多算了一个空行）。

⇒ **施工报告该处无需修改**；若按派单改成 `:20-22`，会把一处**正确引用**改成错误引用。故本补丁**未改**，并建议将 S4-03 登记为「复核误报，撤销」。

## 4. 修补④ 命名擦边（`S44b:20` 的零分隔 `<OLD_REPO_DIR_CONCAT>`）登记

按派单「不处置，只登记」，本补丁**未触碰该文件**。登记过程中实测到**同批另一任务已在本补丁窗口内修复该处并扩展规则**，现态如下：

| 项 | 基线（HEAD） | 本补丁开始前 | 现态（同批任务修复后） |
|---|---|---|---|
| `docs/evidence/S44b-五页视图验证.md:20` | `` `<TEMP_ROOT>/<OLD_REPO_DIR_CONCAT>-db-backup.mv.db` ``（`git show HEAD:` 命中 **1**） | 同基线（1 命中） | `` `<TEMP_ROOT>/<MAIN_REPO>-db-backup.mv.db` ``（**已脱敏**） |
| `git grep -n "<OLD_REPO_DIR_CONCAT>"`（tracked 全量） | — | **1 命中**（仅该行） | **0 命中** |
| 命名纪律规则 | 无「零分隔」态 | 同基线 | `scripts/ci/check-docs.mjs` 被同批任务追加「**词首守卫 + 第三态 去分隔符连写**」（注释自述「T4-S4 补，2026-10-10 裁决」；`git diff` 15:32:24 = 4/2 → 15:33:32 = 86/13，**仍在编**） |

同类「零分隔 / 分隔符变体」全仓扫描（tracked）：`<OLD_REPO_DIR_CONCAT>` / `<OLD_REPO_DIR>` / `<OLD_REPO_DIR>` / `main｜v2` / `<OLD_REPO_DIR>` **全部 0 命中**（`backend/target/`、`frontend/dist/` 等构建产物为 gitignore 未跟踪路径，不在本口径内）。

⇒ 该项**无需下批闭环**：擦边形态已消除、规则已补「零分隔」第三态、断言 ⑤ 仍 PASS。本补丁仅将其从「前置遗留」更新为「已闭环（他包完成）」并登记如上，**未参与、未复核其规则实现细节**。

## 5. 验证结果（全部【实测】）

| 验证项 | 命令 | 结果 |
|---|---|---|
| 类型门禁 | `cd frontend && yarn typecheck` | `vue-tsc --noEmit`，`Done in 4.50s.`，**EXIT=0**（15:33:27 复跑同绿：`Done in 4.51s.`） |
| 构建门禁 | `cd frontend && yarn build` | `✓ built in 28.66s`，**EXIT=0**；`TaskCenterView-kiKbW28z.js 8.67 kB`（与复核一致） |
| 文档门禁 | `node scripts/ci/check-docs.mjs` | **6/6 全过**，`全部通过：6/6 断言绿`，**EXIT=0**。三次复跑：①本补丁前规则版；②同批任务补「零分隔第三态」后（15:32 后）；③15:33:38 版（新增「规则自检：正向种子 13 全命中…负对照 11 全不误伤」行）——**均 6/6** |
| 被删符号残留 | `grep -rn` 于 `frontend/src` 与全仓（排除 node_modules/dist，含 docs/scripts/backend） | 3 个符号 **0 命中**（15:33:27 复验 0）；`JobType|Severity` 于 `format.ts` **0 命中** |
| 工作树 | `git status --porcelain --untracked-files=all` | 15 个 ` M `、**0 个 `??`**（15:33:21 快照；清单此后未变） |
| 未提交态 | `git log --oneline -1` / `git stash list` | `d3020ee`（仍在顶）/ stash 列表空 |
| 原有 12 文件语义未动（反证） | `git diff --numstat` 差值算术 | 见下 |

**「原有 12 文件除 `stores/task.ts` 外逐字未变」的算术反证**（以复核报告 §0 的基线 `19 insertions / 143 deletions` 为锚）：

```
本补丁改动前（15:31 实测）原 12 文件 = 19 ins / 143 del  → 与复核基线逐一吻合（说明我未动任何一行的既有 hunk）
本补丁改动后原 12 文件            = 19 ins / 161 del  → 差 = +18 del = 恰为 jobStatusLabel（17 行 + 1 空行）
新增文件贡献：format.ts 1/14 + requirements.md 3/3
合计 = 23 ins / 178 del（当时 14 文件）✓ 与实测 numstat 逐文件相加一致
```

（`stores/task.ts` 的其余 5 处既有 hunk——`latestJob`/`hasRunningJob` 两个 getter、`openTask`、state 注释——在 `git diff` 中逐字保持原样，未受本补丁影响。）

## 6. 观察项（登记，本补丁未越界处置）

| 编号 | 观察项 | 证据 | 建议 |
|---|---|---|---|
| O-A | **复核 S4-03 为误报**：`check-docs.mjs` 的 DOC#5 段实在 `:19-21`，非 `:20-22`；施工报告原引用正确 | §3.2 的 awk + `git show HEAD:` 双证 | S4-03 撤销登记；施工报告 `:198` 保持原样（本补丁已如此处置） |
| O-B | **`stores/task.ts` 锚点整体漂移 −18**：本补丁在该文件 `:57-74` 删了 18 行，其下所有被引用的行号全部上移 18；`施工报告 §1 / §1.3` 与 `复核报告 §2.3 / §3c` 中该文件的锚点随之失准 | 逐点实测（下表） | 建议由报告所有者按下表一次性回灌；本补丁**未擅改**（派单只授权两处勘误） |
| O-C | **施工报告 §1 的统计块已非现态**：`12 个文件 / 19 ins / 143 del` → 15:33:21 快照为 `15 个文件 / 28 ins / 181 del`（其中 `check-docs.mjs` 仍在被并发改写，故该汇总为移动量）；`stores/task.ts \| 15 +--` → `33 +--`；新增 `utils/format.ts \| 15 +--`；§1.3 标题「净 −13 行」→「净 −31 行」 | `git diff --shortstat`（15:33:21 / 15:33:32 两次读数）+ 逐文件 numstat | 同上，随 O-B 一并在报告所有者侧回灌；回灌时应以「本补丁定格 numstat」为准，勿采信并发窗口内的汇总值 |
| O-D | **扫描器假阳性**：`[A-Za-z]:[\\/]`（盘符正则）会命中 URL 方案（`http://` → `<DRIVE>//`、`https://` → `<DRIVE>//`）。本补丁三文件的新增行（5 行）实扫 **0 命中**；全量新增行扫描当时（23 行）报 1 命中，逐行定位为 `docs/verification-results.md` 既有注记行内的 `http://localhost:5174/`（回环开发端口，合规、且非本补丁新增）；含并发任务在编改动后（110 行）报 2 命中（`<DRIVE>/`、`<DRIVE>/`），**全部为 URL 方案假阳性** | `node scratch/newlines-scan.mjs [<files>]` → 0 / 1 / 2 命中；`git diff -U0 \| grep '^+' \| grep -E "[A-Za-z]:[\\\\/]"` 逐行定位 | 四态扫描器宜对 `[a-z]+://` 做白名单豁免（与 R-92 的植入/负对照纪律一并维护），否则每轮都会出现假阳性 |
| O-E | **并发同批任务的存在需在闭环登记时体现**：`docs/evidence/S44b-五页视图验证.md`（`:20` 擦边脱敏，总 3/3 中 1/1 属它）与 `scripts/ci/check-docs.mjs`（命名纪律补「词首守卫 + 第三态 去分隔符连写」，numstat 15:32:24 为 4/2、15:33:32 已达 86/13，**本报告落笔时仍在编**）由**同批另一任务**在本补丁窗口内修改，非本补丁所为 | 文件 mtime + `git diff` 内容（规则注释自述「T4-S4 补，2026-10-10 裁决」）+ `check-docs` 输出新增行「规则自检：正向种子 13 全命中…负对照 11 全不误伤」 | 闭环登记按 **15 文件**口径；`check-docs.mjs` 的最终 diffstat 应以该任务收工后重测为准（本补丁不给定格值） |

### O-B 锚点映射表（`frontend/src/stores/task.ts`，Δ = −18，逐点实测）

| 锚点（报告引用值） | 本补丁后实测值 |
|---|---|
| `refreshCurrent` `:197-217` | `:179-199`（函数首行 179） |
| `closeTask` `:219-229` | `:201-211` |
| `loadIssues` `:293-305` | `:275-287` |
| `watchPublishOutcome` `:312-341` | `:294-323` |
| `restoreWizard` `:345-353` | `:327-335` |
| `resolveWizardRoute` `:354-364` | `:336-346` |
| `getTaskOverview` 活调用点 `:204` | `:186` |
| `currentTaskId` 初始化 `:95` | `:77` |
| `currentTaskId = null`（closeTask） `:220` | `:202` |
| `removeTask` 内 `closeTask()` 分支 `:243` | `:226` |
| `setImportMode` 调 `refreshCurrent` `:257` | `:239` |
| `startJob` 调 `refreshCurrent` `:269` | `:251` |
| `cancelJob` 调 `refreshCurrent` `:282` | `:264` |
| `watchPublishOutcome` 调 `refreshCurrent` `:325` | `:307` |
| `isFinal` / `JobStatus` 使用点 `:383` | `:365` |

（`frontend/src/stores/workspace.ts` 的 3 处调用点 `:367 / :377 / :400` **未漂移**——该文件物理未改。）

## 7. `git status` 全文（15:33:21 快照，`git -c core.quotepath=false`，路径保持可读）

> **快照口径提醒**：同批另一任务正在**同时**编辑 `scripts/ci/check-docs.mjs`，其 numstat 在本报告落笔前后持续变动（15:32:24 = 4/2 → 15:33:32 = 86/13 → 仍在变），故 `git diff --shortstat` 的**汇总数字是移动量、不可作为定格值**；可以定格的只有**文件清单（15 个，此后未变）**与本补丁三个文件各自的 numstat（见下表）。

```
On branch main
Your branch is up to date with 'origin/main'.

Changes not staged for commit:
  (use "git add <file>..." to update what will be committed)
  (use "git restore <file>..." to discard changes in working directory)
	modified:   backend/src/main/java/com/example/configmgr/task/controller/TaskController.java
	modified:   docs/M2-排期计划.md
	modified:   docs/evidence/S44a-前端基座验证.md
	modified:   docs/evidence/S44b-五页视图验证.md
	modified:   docs/requirements.md
	modified:   docs/test-cases.md
	modified:   docs/verification-results.md
	modified:   frontend/src/App.vue
	modified:   frontend/src/api/tasks.ts
	modified:   frontend/src/stores/task.ts
	modified:   frontend/src/utils/format.ts
	modified:   frontend/src/views/ExportWizardView.vue
	modified:   frontend/src/views/ImportWizardView.vue
	modified:   frontend/src/views/TaskCenterView.vue
	modified:   scripts/ci/check-docs.mjs

no changes added to commit (use "git add" and/or "git commit -a")
```

```
$ git status --porcelain --untracked-files=all | grep '^??'   → 无未跟踪文件
$ git status --porcelain --untracked-files=all | wc -l        → 15
$ git diff --shortstat  @15:33:21    → 15 files changed, 28 insertions(+), 181 deletions(-)
$ git diff --shortstat  @15:33:32    → 15 files changed, 110 insertions(+), 192 deletions(-)   ← 差值全部来自 check-docs.mjs 的并发在编
$ git log --oneline -1   → d3020ee（仍在顶，未提交）
$ git stash list         → 空
```

**本补丁三个文件的定格 numstat**（与并发改动无关，可作复核锚点）：

```
frontend/src/stores/task.ts    1   32     （其中 18 del = 本补丁的 jobStatusLabel 删除）
frontend/src/utils/format.ts   1   14     （全部属本补丁）
docs/requirements.md           3    3     （全部属本补丁）
```

**文件清单归属（按本补丁贡献拆分）**：

| 归属 | 文件 | 说明 |
|---|---|---|
| 原 UI 包 12 文件 | `TaskController.java`、`M2-排期计划.md`、`S44a`、`S44b`、`test-cases.md`、`verification-results.md`、`App.vue`、`api/tasks.ts`、`stores/task.ts`、`ExportWizardView.vue`、`ImportWizardView.vue`、`TaskCenterView.vue` | 语义未动；仅 `stores/task.ts` 被本补丁**追加**一段纯删除（S4-01 所需） |
| 本补丁新增改动 | `stores/task.ts`（追加删除 18 行）、`frontend/src/utils/format.ts`（新进清单，S4-01 删除）、`docs/requirements.md`（新进清单，三处注记） | 派单预期「原 12 + requirements.md」；实际另需 `format.ts` 一处——该文件是 S4-01 的删除落点，**不可避免** |
| 并发同批任务（非本补丁） | `docs/evidence/S44b-五页视图验证.md`（`:20` 擦边脱敏）、`scripts/ci/check-docs.mjs`（命名纪律补第三态） | 见 O-E；本补丁未触碰 |

## 8. 遗留 / 未完成

- 本补丁四项**均已处置**（③ 的一半按反证未改、④ 按纪律未处置——两者均在 §3.2 / §4 给了证据与现态）。
- 未执行后端测试（未动后端；`TaskController.java` 的改动属原 UI 包）。
- 未做浏览器活体复核（本补丁为源码 + 文档级增量，行为面零变化；复核报告 §5 已有活体证据）。
- 建议指挥官侧一次收口：S4-03 撤销登记、O-B/O-C 锚点与统计回灌（本补丁已给出可直接套用的映射表与实测值）、O-D 扫描器白名单。
- 本补丁在 `<TEMP_ROOT>/ui-title-open/scratch/` 留了 2 个可复跑的证据脚本：`prefix-check.mjs`（注记逐字留痕/结构/脱敏校验）、`newlines-scan.mjs`（新增行四态命名 + 脱敏扫描，含植入对照与负对照，5/5 对照通过）。

**〔分件 4／4〕** 原件：`<LOCAL_WORK>/ui-title-open/命名修补报告.md`

# 命名纪律修补报告 —— 零分隔连写形态（命中点修复 + 全仓扫雷 + 门禁补强）

> 日期：2026-10-10　执行：命名修补子智能体（后台独立运行）
> 仓库：`<MAIN_REPO>`（= 本机 `<REPO_ROOT>`，Git Bash 工作树，分支 `main`）
> 纪律：**零 git 写操作、全程保持未提交态**（未 add / 未 commit / 未 stash / 未改动 HEAD）；报告已脱敏（`<MAIN_REPO>` / `<REPO_ROOT>` / `<USER>`）。
> 裁决依据（用户 2026-10-10）：命名纪律禁则 = main+分隔符+v2 的任意大小写组合；本次补「**零分隔连写**」漏网形态，并**补门禁**。
> 本报告落在工作树外（`<LOCAL_WORK>\ui-title-open\`），其中为取证必须原样引用的一处修复前字面量，**该字面量仅存在于本报告与工作树外证据日志中，未进仓库**。

---

## 0. 交付物与结论摘要

| 项 | 结果 |
|---|---|
| ① 命中点修复 | `docs/evidence/S44b-五页视图验证.md:20`：`<OLD_REPO_DIR_CONCAT>-db-backup` → `<MAIN_REPO>-db-backup`（**仅此 1 行**，同文件其他内容未动） |
| ② 全仓扫雷（零分隔连写态） | `git ls-files` 口径 916 路径 / 实扫 913 文本 / 豁免 3：**修复前 1 命中（即 ①）→ 修复后 0**；文件系统口径（含未跟踪物）另见 §2.3、§7.1 |
| ③ 门禁补强 | `scripts/ci/check-docs.mjs` 断言 ⑤：双态 → **三态**（新增「去分隔符连写态」），并补**词首守卫**与**规则自检（植入对照）**；**断言计数仍 6**，CI 口径不变 |
| 验证 | 门禁 **6/6 绿**（exit 0）；全仓零分隔复扫 **0 命中**；反向验证「旧口径绿 / 新口径红」对照成立；对照矩阵 69 用例 0 不合期望 |
| git 足迹 | 我方仅 2 个改动文件（S44b 文档 + check-docs.mjs）；工作树另有 13 个改动文件与 2 个未跟踪文件**均非本子智能体所为**（见 §6 归属取证） |
| 未做 | 未跑 backend/frontend 全量测试（改动面 = 1 个历史证据文档 + 1 个门禁脚本，无交集；且工作树含他人在途改动），理由见 §7.6 |

---

## 1. 工作①：命中点修复（改前/改后逐字符）

文件：`docs/evidence/S44b-五页视图验证.md`，第 20 行（表格「0. 环境与产物」的 `| DB 备份 |` 行）。

**改前**（原样，含命中字面量）：

```
| DB 备份 | `<TEMP_ROOT>/<OLD_REPO_DIR_CONCAT>-db-backup.mv.db`、`<TEMP_ROOT>/kimi-blueprint-db-backup.mv.db`（测试后按此还原，见 §8） |
```

**改后**：

```
| DB 备份 | `<TEMP_ROOT>/<MAIN_REPO>-db-backup.mv.db`、`<TEMP_ROOT>/kimi-blueprint-db-backup.mv.db`（测试后按此还原，见 §8） |
```

diff（真实 `git diff` 片段，`§1` 归属见 §6）：

```
-| DB 备份 | `<TEMP_ROOT>/<OLD_REPO_DIR_CONCAT>-db-backup.mv.db`、`<TEMP_ROOT>/kimi-blueprint-db-backup.mv.db`（测试后按此还原，见 §8） |
+| DB 备份 | `<TEMP_ROOT>/<MAIN_REPO>-db-backup.mv.db`、`<TEMP_ROOT>/kimi-blueprint-db-backup.mv.db`（测试后按此还原，见 §8） |
```

**裁决依据与豁免性处置说明**：

- 该文件是 `docs/evidence/` 下的**历史证据文档**。按裁决 7，断言 ①②③④ 明确排除历史证据文档（端口/旧版本号等历史记录不回改），但**断言 ⑤ 不排除它们**（`check-docs.mjs` 头部注释「⑤ 相反，按 `git ls-files` 全量」）。
- 因此本处属「命名纪律禁则」的**豁免性处置**：只改指代形态（禁词字面量 → 脱敏占位符 `<MAIN_REPO>`），**不改任何历史事实、不改时间/端口/命令/结论**，也不回改该文件其他任何内容。全文 grep 复核：该文件内 `<OLD_REPO_DIR_CONCAT>`/`db-backup` 相关命中原本**仅此 1 处**（`grep -n "db-backup\|MAIN_REPO"` 仅第 20 行），无连带修改。
- **占位符惯例的轻微不一致（记录，不处置）**：该文件既有的本机占位符体系是 `<REPO_ROOT>` / `<TEMP_ROOT>` / `<BLUEPRINT>`（见该文件第 4/6/7 行），本次按用户裁决原文使用 `<MAIN_REPO>`（与仓库级惯例、3 个 spike 脱敏脚本的替换目标一致）。按「裁决原文优先 + 只动命中处」执行，未顺手统一。

---

## 2. 工作②：全仓扫雷（零分隔 `main`+`v2`）

### 2.1 口径与实现

- 三态归一：`NFKC 折叠` → `零宽字符（U+200B-200D / U+2060 / U+FEFF）双态（删除态 / 空格态）` → **去分隔符连写态**（`-` `_` 空格 点 + Unicode 连字符族 U+2010-U+2015 / U+2043 / U+2212 / U+FE58 / U+FE63 / U+FF0D 视为「零个或多个」）。
- 大小写不敏感；带**词首守卫**（禁词前一字符不得是 ASCII 字母/数字，理由见 §3.2）。
- **规则实例来源不是转写**：扫描脚本从交付版 `scripts/ci/check-docs.mjs` 中**切片抽取** `const NAME_GUARD` … `const hitsName` 的真身并在沙箱内执行（见附录 A1/A2），保证「扫描用的规则 == 门禁里的规则」。旧口径对照取自 `git show HEAD:scripts/ci/check-docs.mjs`（git 对象，权威）。

### 2.2 `git ls-files` 口径（= 断言 ⑤ 的正式扫描面）

```
$ node scan-zero-sep.mjs <MAIN_REPO> --variant=both
repo=<MAIN_REPO>
variant=concat+guard  git ls-files=916  实扫文本=913  豁免=3
命中=1
  concat+guard  docs/evidence/S44b-五页视图验证.md:20  | DB 备份 | `<TEMP_ROOT>/<OLD_REPO_DIR_CONCAT>-db-backup.mv.db`、…
```

修复后复跑（同一命令）：

```
variant=concat+guard  git ls-files=916  实扫文本=913  豁免=3
命中=0
```

**结论：全仓零分隔连写形态命中 = 1（即工作①的已知命中点），无其他命中；修复后 0。**

### 2.3 文件系统口径（补强：`git ls-files` 之外的实际文件）

按用户口径排除 `.git` / `node_modules` / `dist`（另排除构建产物 `target` / `build` / `.tmp-build` / `coverage`）：

```
$ node fs-scan.mjs <MAIN_REPO>
文件系统级扫描（排除 .git / node_modules / dist / target / build / .tmp-build / coverage）：目录 194，文件 931
路径名命中 0：（无）
文件内容命中 8：
  docs/research/对话时序混排-业界调研.md:5   你给的现状是「15 类帧」。但当前仓库 `ai-example-code/本仓` 里已经多了一组边界帧…
  docs/research/对话时序混排-业界调研.md:245 - 本地仓库：`<REPO_ROOT>\backend\src\main\java\…`
  spike/sp01ab/scripts/sp01ab-sanitize.mjs:13  …（豁免）
  spike/sp01ab/scripts/sp01ab-sanitize.mjs:14  …（豁免）
  spike/sp01d/scripts/make-attachments.mjs:14  …（豁免）
  spike/sp01d/scripts/make-attachments.mjs:15  …（豁免）
  spike/sp02/scripts/sp02-sanitize.mjs:14      …（豁免）
  spike/sp02/scripts/sp02-sanitize.mjs:15      …（豁免）
```

- **零分隔连写态（本次形态）：文件系统口径同样 0 命中** —— 上述 8 行全部是**既有态（含分隔符 `<OLD_REPO_DIR>`）**，不是本次漏网形态；其中 6 行落在**豁免段**（3 个 spike 脱敏脚本的匹配源正则），另 2 行落在**并发子智能体刚写入的未跟踪文件**（见 §7.1 移交项）。
- 文件名/目录名扫描：**0 命中**（含仓库根目录自身 `ai-example-<MAIN_REPO 名>` 形态带分隔符，不属禁则）。

### 2.4 豁免段核对（3 个 spike 脚本「不要误伤」）

| 豁免文件 | 头部豁免注释（前 12 行内） | 文件内命中行 | 命中性质 |
|---|---|---|---|
| `spike/sp01ab/scripts/sp01ab-sanitize.mjs` | 有（第 3 行「命名纪律豁免项（DOC#5 裁决）」） | 13、14 | `RULES` 的脱敏匹配源正则 |
| `spike/sp02/scripts/sp02-sanitize.mjs` | 有（第 3 行） | 14、15 | `RULES` 的脱敏匹配源正则 |
| `spike/sp01d/scripts/make-attachments.mjs` | 有（第 4 行） | 14、15 | `sanitize` 链的 `.replace()` 匹配源 |

- 断言 ⑤ 的豁免机制（`NAMING_EXEMPT` 三路径整文件跳过 + 头部「命名纪律豁免 / DOC#5」注释存在性校验）**本次一字未改**，三种归一态都不会触及豁免面（豁免判定发生在读文件之前）。
- 复核结果：豁免面仍恰为 3 个文件、命中行全部落在其匹配源正则行；**新增第三态未产生任何新的「需要豁免的文件」**（全仓 + 文件系统口径均无第 4 个文件命中）。

---

## 3. 工作③：门禁断言 ⑤ 改造（双态 → 三态）

文件：`scripts/ci/check-docs.mjs`（`git diff --numstat` = **+87 / −14** 行，含注释与自检代码）。**断言计数保持 6**，默认输出与 CI 调用口径不变。

### 3.1 规则核心 diff 要点

```diff
 const NAME_PREFIX = 'ma' + 'in'
 const NAME_SUFFIX = 'v' + '2'
-const NAME_SEP = '[\\s\\-_.\\u2010-\\u2015\\u2043\\u2212\\uFE58\\uFE63\\uFF0D]+'
-const NAME_RE = new RegExp(NAME_PREFIX + NAME_SEP + NAME_SUFFIX, 'i')
+const NAME_GUARD = '(^|[^A-Za-z0-9])'
+const NAME_SEP_CLASS = '\\s\\-_.\\u2010-\\u2015\\u2043\\u2212\\uFE58\\uFE63\\uFF0D'
+const NAME_SEP = `[${NAME_SEP_CLASS}]+`
+const NAME_SEP_OPT = `[${NAME_SEP_CLASS}]*`                                    // ← 第三态：分隔符「零个或多个」
+const NAME_RE = new RegExp(NAME_GUARD + NAME_PREFIX + NAME_SEP + NAME_SUFFIX, 'i')
+const NAME_CONCAT_RE = new RegExp(NAME_GUARD + NAME_PREFIX + NAME_SEP_OPT + NAME_SUFFIX, 'i')  // ← 新增
 const ZERO_WIDTH_RE = /[\u200B-\u200D\u2060\uFEFF]/g
 const normalizeName = (text) => text.normalize('NFKC').replace(ZERO_WIDTH_RE, '')
 const separateName = (text) => text.normalize('NFKC').replace(ZERO_WIDTH_RE, ' ')
-const hitsName = (text) => NAME_RE.test(normalizeName(text)) || NAME_RE.test(separateName(text))
+const hitsName = (text) =>
+  NAME_RE.test(normalizeName(text))            // 态①+③底座：删除态（零宽藏词内）
+  || NAME_RE.test(separateName(text))          // 态②：分隔态（零宽充当分隔）
+  || NAME_CONCAT_RE.test(normalizeName(text))  // 态③：去分隔符连写（零分隔）
```

- **态③语义**：在删除态归一文本上，前缀与后缀之间的分隔符量词由 `+` 改 `*` ——「**零个**分隔符」即本次漏网形态（零分隔连写），「一个或多个」等价于态①，故态③是态①的超集；同时顺带覆盖「**零宽既藏词内又作分隔**」的组合形态（既登记观察项 S-OB1 的 `ma⟨ZW⟩in⟨ZW⟩v2`，矩阵 5 例实测已覆盖）。
- 三态归一、行号不变、零第三方依赖、`git ls-files` 口径（不 grep 工作树）等既有约定全部保持。规则字面量仍按 **DC-08 拼接构造**（源码内不出现完整禁用字面量）。

### 3.2 配套新增：词首守卫（**这是「去掉分隔符后匹配」的必要件，不是可选优化**）

`NAME_GUARD = '(^|[^A-Za-z0-9])'`：禁词前一字符不得是 ASCII 字母/数字。

- **必要性（实测反证）**：若不加守卫，第二态（连写/去分隔符）会把**域名类文本**逐字符误伤 —— 「`domain` + 分隔符 + 后缀`v2`」在去掉分隔符后与禁词尾部逐字符重合。矩阵实测（附录 A3，`旧双态` 列即「无守卫 + `main SEP+ v2`」）：

| 样本 | 无守卫（HEAD 旧口径） | 有守卫（本次交付） |
|---|---|---|
| `do<OLD_REPO_DIR⟨␠⟩>` | **命中（假阳性）** | 不中 |
| `do<OLD_REPO_DIR>` | **命中（假阳性）** | 不中 |
| `do<OLD_REPO_DIR⟨_⟩>` | **命中（假阳性）** | 不中 |
| `do<OLD_REPO_DIR⟨.⟩>` | **命中（假阳性）** | 不中 |
| `do<OLD_REPO_DIR_CONCAT>` / `https://example.com/do<OLD_REPO_DIR_CONCAT>/api` | 不中 | 不中 |

  ⇒ 该假阳性是**旧口径既有缺陷**（与本次新增无关），守卫一并收窄；且负对照样本本身能写进规则源码，也依赖该守卫（否则门禁文件自己就红）。
- **不削弱判定面的取证**：真实指代（仓库目录名/分支名/路径段/代码字符串）恒由 `/` `\` `<` `>` `-` 空格 引号 或行首定界。全仓实测：**加守卫前后命中集合完全一致**（修复前同为 S44b:20 那 1 行；修复后同为 0），差异只出现在域名类假阳性上（附录 A3 全仓审计段）。

### 3.3 配套新增：⑤-a 规则自检（**植入对照**，默认执行、不新增断言计数）

在 `assertNamingDiscipline()` 入口调用 `selfCheckNamingRule()`：

- **13 条正向种子**（全部按 DC-08 拼接构造，源码内无禁用字面量）：零分隔连写（原形/全大写/路径形态/中文紧邻/前缀为分隔符）+ 既有各分隔符态（`-` `_` 空格 点）+ 全角连字符（NFKC）+ U+2011 + 零宽藏词内/作分隔；要求**每条都被 `hitsName` 命中**，并额外要求：
  - `NAME_CONCAT_RE` 至少独立命中 6 条（防「第三态被改窄」——实测 13/13 全部经第三态命中）；
  - **零分隔连写种子必须被 `NAME_CONCAT_RE` 命中**（防「规则形同未加」）。
- **11 条负对照**：`main` 单词单独、`v2` 单独、域名类（空格/连字符/连写/URL）、`src/main.ts`、`mainFrame`、`derivative`、占位符惯例（`<MAIN_REPO>-db-backup`、`<REPO_ROOT>/<TEMP_ROOT>`）；要求**全部不命中**。
- 自检结论会打印在门禁输出里（默认跑可见）：

```
（命名纪律扫描：git ls-files 916 个路径，实扫文本 913 个；豁免 3 个）
（规则自检：正向种子 13 全命中、其中经第三态连写规则命中 13 例；负对照 11 全不误伤）
```

---

## 4. 植入对照 / 反向验证记录（全部【实测】）

### 4.1 对照矩阵（全码点族正/负矩阵 + 全仓误报审计）

脚本 `scratch/matrix.mjs`：**新规则**从交付版门禁文件切片抽取；**旧口径**从 `git show HEAD:scripts/ci/check-docs.mjs` 抽取（非转写）。

```
== 对照矩阵（规则取自交付版 check-docs.mjs 切片；旧口径取自 git show HEAD）==
矩阵小计：用例 69，不合期望 0；其中"旧双态漏、新三态命中"的用例 18 个
交付版自带种子/对照：种子 13 条，负对照 11 条

== 全仓审计（非豁免 913 个文本文件）==
交付版（三态+守卫）命中 0 行：（无）
旧口径（HEAD 双态）命中 0 行：（无）
仅交付版命中（旧口径漏网）：（无）
```

矩阵分组：**A 连写 13 例**（全部「旧双态漏、新三态命中」）、**B 既有态 46 例**（分隔符族 / Unicode 连字符族全码点 / 全角 NFKC / 零宽双态 / 零宽组合形态 C 5 例 —— 回归不丢）、**C 负对照 19 例**（全不误伤）。18 例新增覆盖 = 13 例零分隔连写 + 5 例零宽组合形态 C。

### 4.2 变异测试（证明自检/守卫真的「卡得住」——临时改窄规则 → 必须红）

| 变异 | 改动 | 门禁结果 | 定位 |
|---|---|---|---|
| A | `NAME_SEP_OPT` 的 `*` 退回 `+`（第三态失效） | **⑤ FAIL（6 处）**，exit 1 | 5 条零分隔种子未被捕获 + 「第三态未捕获零分隔连写种子（规则形同未加）」 |
| B | `NAME_GUARD` 置空 `'(^|)'`（删词首守卫） | **⑤ FAIL（4 处）**，exit 1 | 「负对照被误伤」×4（域名类·空格/连字符/连写/URL） |

两次变异后均从工作树外备份**按字节还原**，`md5sum` 与变异前一致（见 §5 哈希）。

### 4.3 反向验证：真实命中点上「旧口径绿 / 新口径红」对照

把 `docs/evidence/S44b-五页视图验证.md:20` **临时还原**为修复前形态，同一时刻跑两个门禁（旧口径 = `git show HEAD` 版本，仅把 `REPO` 常量改为绝对路径；新 = 交付版）：

```
--- 交付版门禁（三态+守卫） ---
  [FAIL] ⑤ 命名纪律禁则（git ls-files 全量）（1 处）
发现 1 处文档漂移：
  [⑤ 命名纪律禁则（git ls-files 全量）] docs/evidence/S44b-五页视图验证.md:20  =>  命中命名纪律禁则（旧主仓目录名/同名分支名，含零分隔连写形态；指代一律用占位符）
EXIT=1

--- 旧口径门禁（git show HEAD:scripts/ci/check-docs.mjs，仅把 REPO 常量改为绝对路径） ---
  [PASS] ⑤ 命名纪律禁则（git ls-files 全量）
全部通过：6/6 断言绿。
EXIT=0
```

⇒ 同一份仓库状态：**旧门禁全绿（漏网）= 缺陷复现**；**新门禁红且精确定位 `文件:行`= 补强有效**。验证后已把该行还原为修复后形态（§5 哈希钉死）。

---

## 5. 验证结果（实测）

| 验证项 | 命令 | 结果 |
|---|---|---|
| 门禁全绿 | `node scripts/ci/check-docs.mjs` | **[PASS]×6，退出码 0**，末行「全部通过：6/6 断言绿。」 |
| 断言计数与 CI 口径 | `.github/workflows/ci.yml:267` = `run: node scripts/ci/check-docs.mjs`（无参） | **仍为 6 条断言**，无参数/无新增模式，CI 无需改动；ci.yml 注释「6 条断言」保持成立 |
| `--help` 口径同步 | `node scripts/ci/check-docs.mjs --help` | ⑤ 行已更新为「匹配三态 = NFKC + 零宽双态 + 去分隔符连写，带词首守卫，断言内自带正向种子/负对照自检」 |
| 全仓零分隔复扫 | `node scan-zero-sep.mjs <MAIN_REPO> --variant=both` | **命中 0**（豁免段除外） |
| 文件系统口径复扫 | `node fs-scan.mjs <MAIN_REPO>` | 路径名 0；内容命中仅 6 行豁免段（+ 未跟踪他人在途文档 2 行，见 §7.1） |
| 对照矩阵 | `node matrix.mjs <MAIN_REPO>` | 69 用例 / 0 不合期望 / 18 例新增覆盖 |
| 反向验证 | 见 §4.3 | 旧口径 6/6 绿、新口径红并精确定位 `S44b:20` |
| 变异测试 | 见 §4.2 | 两种改窄均被 ⑤ 自检拦下并定位 |
| 哈希钉死（终态） | `md5sum` | `scripts/ci/check-docs.mjs` = `76ec102d3d1545314ed7c09b3c4d6ac8`；`docs/evidence/S44b-五页视图验证.md` = `19376c03767470037fa027ab5395efa0` |

门禁终跑全文：

```
文档一致性门禁（check-docs）
  [PASS] ① 导航/口径文档无盘符绝对路径
  [PASS] ② 技术栈版本与 pom.xml / package.json 一致
  [PASS] ③ 导航/口径文档引用的文件存在
  [PASS] ④ 端口表与 application.yml / vite.config.ts 一致
         （命名纪律扫描：git ls-files 916 个路径，实扫文本 913 个；豁免 3 个）
         （规则自检：正向种子 13 全命中、其中经第三态连写规则命中 13 例；负对照 11 全不误伤）
  [PASS] ⑤ 命名纪律禁则（git ls-files 全量）
  [PASS] ⑥ .java 探针不参与构建

全部通过：6/6 断言绿。
```

> 附带核对：`docs/M2-排期计划.md:314` 记载的「命名纪律基线：非豁免命中 0」在修复后**依然成立**（修复前按新口径为 1，属该基线未覆盖的形态；本批修复后回到 0），故无需改动该文档。

---

## 6. `git status`（全文）与改动归属取证

```
$ git status --porcelain
 M backend/src/main/java/com/example/configmgr/task/controller/TaskController.java
 M "docs/M2-排期计划.md"
 M "docs/evidence/S44a-前端基座验证.md"
 M "docs/evidence/S44b-五页视图验证.md"          ← 本子智能体（工作①）
 M docs/requirements.md
 M docs/test-cases.md
 M docs/verification-results.md
 M frontend/src/App.vue
 M frontend/src/api/tasks.ts
 M frontend/src/stores/task.ts
 M frontend/src/utils/format.ts
 M frontend/src/views/ExportWizardView.vue
 M frontend/src/views/ImportWizardView.vue
 M frontend/src/views/TaskCenterView.vue
 M scripts/ci/check-docs.mjs                     ← 本子智能体（工作③）
?? "docs/research/对话时序混排-业界调研.md"      ← 非本子智能体（并发子智能体，见下）
?? "docs/research/步骤条交互-业界调研.md"        ← 非本子智能体（同上）
```

统计：`M` 15 行、`??` 2 行；**双方约定的「应只新增 2 个改动文件」需按下述归属解读**（我方改动集 = 2，工作树其余变化是并发子智能体在途产物，非本子智能体所致）：

- **我方改动 = 2 个文件**：`docs/evidence/S44b-五页视图验证.md`（本子智能体 1 行；`numstat` 合计 3/3 含并发子智能体另 2 行）、`scripts/ci/check-docs.mjs`（+87/−14）。二者的 mtime = `15:33`（本子智能体写盘时刻）。
- 其余 13 个改动文件 mtime 全部落在 `15:01–15:30`（早于我方首次写盘），**非本子智能体所写**；其中 S44b 文件内的另外 2 处改动（第 266、326 行的「2026-10-10 UI 改造」注记行）亦为并发子智能体所写，与本次修复不在同一行、无重叠（`git diff` 可分离）。
- 2 个未跟踪文件由**并发子智能体在本子智能体运行期间新写**（mtime `15:34:55` / `15:34:59`；本子智能体首轮 `git status` 时为 0 个 `??`，文件系统扫描文件数由 929→931 亦印证）。**本子智能体未创建、未修改、未删除任何未跟踪文件**，也未做任何 git 写操作（`add/commit/stash/checkout` 全程未使用）。

---

## 7. 移交项与观察项

1. **【移交·优先】未跟踪调研文档 2 行命名纪律命中（本子智能体未动，避免与在途写入冲突）**
   - `docs/research/对话时序混排-业界调研.md:5`：`ai-example-code/本仓`（**含盘符/仓库名脱敏问题**）
   - `docs/research/对话时序混排-业界调研.md:245`：`<REPO_ROOT>\backend\...`（同上，且为盘符绝对路径）
   - 现状：这两个文件**未跟踪**，断言 ⑤ 按 `git ls-files` 口径**不会扫到**，故当前 6/6 绿；**一旦 `git add`，⑤ 立即红**（命中形态为既有态 `<OLD_REPO_DIR>`，非本次新增形态 —— 无需再改门禁，只需脱敏为 `<MAIN_REPO>`）。
   - 建议：由该文件的属主（并发子智能体）或整合方统一替换为 `<MAIN_REPO>` 后再入库（与 3 个 spike 脱敏脚本的替换目标一致）。本子智能体不代改的理由：文件处于在途写入中（mtime 距我方核查仅 ~12 秒），代改存在丢失更新风险；且超出「我方足迹 = 2 文件」的约定。
2. **门禁不扫文件名/路径名本身**（既有登记观察项，非本批范围）：本次只做体检 —— `git ls-files` 路径名 + 文件系统路径名对三态规则**均 0 命中**。口径未改。
3. **术语对账（避免混淆）**：本报告与门禁注释中的「第三态」= **去分隔符连写**（本次裁决形态）；`docs/M2-排期计划.md:187` 的 **S-OB1「第三态」** 指**零宽组合形态 C**（`ma⟨ZW⟩in⟨ZW⟩v2`）并附「须自带全量矩阵」要求。二者名同实异：本次交付的第三态**顺带覆盖了组合形 C**（矩阵 B 组 5 例、旧双态全漏），且本批已提供全码点族矩阵与全仓误报审计（附录 A3/A4）。是否据此关闭 S-OB1 观察项，由后续裁决。
4. **词首守卫的判定边界（明示，便于后续裁决）**：紧贴 ASCII 字母/数字的形态**不判红**（如 `domain<分隔符>v2`、`x<OLD_REPO_DIR_CONCAT>`、`sub<OLD_REPO_DIR_CONCAT>`），因为该形状必是「含禁词的更长词/域名」，且要与域名类文本不误伤相取舍。若要覆盖「前缀紧贴字母」形态，需另行裁决并同步负对照矩阵。
5. **S44b 占位符惯例差异**：本次按裁决原文写 `<MAIN_REPO>`，该文件既有体系为 `<REPO_ROOT>`（见 §1）；未统一，留待文档治理批统一处理。
6. **未跑 backend/frontend 全量测试**：本次改动面 = 1 个历史证据文档的 1 行 + 1 个门禁脚本（Node 内置模块、零第三方依赖），与后端/前端构建测试无交集；且工作树含并发子智能体的在途改动（13 个文件），全量跑会把他人半成品状态计入结果。门禁（`check-docs.mjs`）即为本改动的对口验证项，已 6/6 绿。

---

## 附录：证据文件（均在工作树外，可复核）

| 文件 | 内容 |
|---|---|
| `<LOCAL_WORK>\ui-title-open\scratch\matrix.mjs` / `matrix.log` | 对照矩阵（69 用例）+ 全仓审计（新/旧口径对照） |
| `<LOCAL_WORK>\ui-title-open\scratch\scan-zero-sep.mjs` | 零分隔连写全仓扫描（`git ls-files` 口径，规则切片自交付版门禁） |
| `<LOCAL_WORK>\ui-title-open\scratch\scan-ups.mjs` | 无守卫/有守卫对照扫描（守卫差异取证） |
| `<LOCAL_WORK>\ui-title-open\scratch\fs-scan.mjs` / `fs-scan.log` | 文件系统口径扫描（含路径名） |
| `<LOCAL_WORK>\ui-title-open\scratch\oldgate.mjs` / `oldgate-patched.mjs` / `oldgate-run.log` | 旧口径门禁（`git show HEAD` 版本，仅改 REPO 常量）及其运行日志 |
| `<LOCAL_WORK>\ui-title-open\scratch\reverse-check.log` | §4.3 反向验证全文（旧口径绿 / 新口径红） |
| `<LOCAL_WORK>\ui-title-open\scratch\final-gate.log` | 终态门禁 6/6 绿全文 |
| `<LOCAL_WORK>\ui-title-open\scratch\check-docs.diff` | 门禁脚本完整 diff |
| `<LOCAL_WORK>\ui-title-open\scratch\check-docs.mjs.bak` / `hash-before.txt` | 变异测试前的按字节备份与哈希（用于还原校验） |
