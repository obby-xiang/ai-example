> 来源=<仓外证据>/ai-full-audit/final-gap/FA6-report.md（2026-10-25 归档）· 审计分片/草稿，属历史证据，不代替终稿 `docs/research/排查-AI全链路只读验收审计-终稿-2026-10-25.md`
未运行任何测试。

# FA6-report｜前端 store / 类型 / 格式化（11 文件 / 4,606 行逐行）

> 只读审计。**未运行任何测试**、未改任何源码/测试/配置、未触发 CI。本组 11 个文件均已**逐行读完**（`wc -l` 合计 4,606，与 FA6 清单一致），因此 `FINAL-AUDIT.md §5.8.2` 中这 11 条可从"未逐行"改为"已逐行"——但**不得**据此声称任何条目已修复。
> 说明：本代理为只读角色（无写入工具），故 `final-gap/FA6-report.md` **未落盘**，内容即本回复，请控制方保存。

---

## 0. 新增 / 补正条目速览

| 新 ID | 结论 | Sev | Cert | 主路径 |
|---|---|---|---|---|
| FA6-01 | 工作区写动作与导航**无串行化**：同批并行调用可打到**导航前**的 taskId，并退化到 ui_event 后备路径 | S3 | C（机制）/R（落错任务） | `stores/workspace.ts:465-521`；`utils/frontend-tools.ts:1019-1034`；`stores/ai.ts:5061` |
| FA6-02 | `dispatchUiEvent` 的**成功**写也双记账，且内部那条记成 `来源：界面`（补 I-09 只覆盖失败面） | S4 | C | `stores/workspace.ts:450` vs `:180-245,481,494,507,520` |
| FA6-03 | `restore_task` 丢掉 `page`/`taskType` ⇒ `resolveWizardRoute` 走"缓存→EXPORT"兜底，给 IMPORT 任务开**导出向导**并改写 `taskType` | S3 | C | `stores/task.ts:392-399`；`stores/workspace.ts:465-469`；`types/tools.ts:496` |
| FA6-04 | `task.load()` 的 `loading` 早退吞掉 `setPage/setSize/applyFilters` 的刷新（只有 `refreshNow` 置 pending）；`refresh_tasks` 回假回执 | S4 | C | `stores/task.ts:158-161,197-228`；`stores/workspace.ts:627-629` |
| FA6-05 | `enterPage` 只写"传入的键"：数据浏览页漏传 `taskId:null` ⇒ 旧任务绑定残留；`taskStatus/queryConditions` 永不复位 | S4 | C | `views/DataBrowserView.vue:238,361`；`stores/workspace.ts:144-178` |
| FA6-06 | `cancel_job` 只读守卫按"当前工作区任务"判，不核 `jobId` 归属 ⇒ 跨任务误拒（现状不可达） | S4 | C | `stores/workspace.ts:570-578` |
| FA6-07 | 发布跟踪**第二处单槽**（workspace 级 dispose）：同任务重跑也会静默丢上一轮冲突（补 FE-19） | S4 | C | `stores/workspace.ts:75,551-552`；`stores/task.ts:293-297` |
| FA6-08 | 冲突明细只取前 50 条却把"共 N 处"写成页内条数；无 ERROR 时把 WARNING 当"校验冲突" | S4 | C | `stores/task.ts:103-110,361-375` |
| FA6-09 | `contextSummary`（UI 面）拼中文 `stepLabel` ⇒ 英文界面出现中文步骤名（补 FE-29） | S4 | C | `stores/workspace.ts:126-139`；`utils/format.ts:96-113` |
| FA6-10 | `WORKSPACE_CONTRACT_VERSION` 无任何消费方（只显示/上报），"双侧兼容判定"名存实亡 | S4 | C（代码）/D（设计） | `types/tools.ts:487-488`；`stores/workspace.ts:80,294`；`App.vue:46` |
| FA6-11 | 上下文 pill 在"有上限、无百分比"时丢掉分母，与 tooltip 口径不一致 | S4 | C | `utils/context-usage.ts:148-162` vs `:124-126` |

11 条中只有 FA6-03 触及**跨实体写错**（错向导 + taskType 被改写），FA6-01 在竞态命中时**把 defs/条件写到上一个任务**；其余为回执/上下文/展示保真度问题。

---

## 1. 逐文件映射（与既有 canonical ID 的关系）

| # | 文件 | 结论 | 与既有 ID 的关系 |
|---|---|---|---|
| 1 | `stores/workspace.ts` (639) | buildContext 确实严格 5 键；`ui_event` 15 分支全实现且 `default` 用 `never` 做运行期兜底；只读闸 6 处同构 | **确认** FE-15（`:471-509` 的写分支 + 陈旧 pageId 可用）、FE-20（`:283-302` 只下一轮可见）、I-09（`:447-452` 失败回执也记账）、I-11（`:455-639` 多个分支无模型入口）、I-12（`:419-436` `[0]` 取值）；**补充** FE-19（第二处单槽，新增 FA6-07）、I-09（成功面 + 源误标，新增 FA6-02）、FE-29（`contextSummary`，新增 FA6-09）；**新增** FA6-01/03/05/06/10。未否证任何既有条目。 |
| 2 | `stores/task.ts` (424) | 手动刷新单通道、300s 墙钟、F4 注册表 + jobId 闸、双通道冲突收口均可复现 | **确认** FE-19（`:113-116,298,352-355` 静默丢弃；`:293-297` 注册表本身支持并存）、FE-18（`resolveWizardRoute` 不绑面板）、I-12（`listJobs` 全量 DESC ⇒ `[0]` 成立）；**补充** FE-19（新增 FA6-07）；**新增** FA6-03/04/08。**限定**：`Job.id?: number`（`types/job.ts:34`）使 `job.id ?? 0` 不是死码——一旦后端省 `id`，watcher 被静默禁用而回执照报"作业 #undefined"。 |
| 3 | `types/ai.ts` (984) | 请求级 `modelId/params` 已退场（`:961-973` 仅保留后端能力镜像，前端不发送，与批 F·F1 一致）；`parts` 唯一真值 + 四投影（`:341-352`）；镜像 `Pick` 刻意不含 `appliedSeqs/errorCard/timestamp`（`:735`、`:368-380`） | **确认** FE-27（`buildContext` 在 POST 前消费）、FE-21/22（`PendingToolCall.status` 词表与 `ToolRunStatus` 的 `unknown/skipped` 分离）、BZ-25/26 的类型面。无新增；未发现类型自相矛盾。 |
| 4 | `types/sse.ts` (622) | 帧白名单 16 类 + 旧 `reasoning` 放行（`:128-157,568-580`）；思考段走 `delta.kind`，不新增帧型 | **确认** FE-12/13 所依赖的"帧集冻结"前提。遗留：只校验 `type`、不校验必填字段（见 §5-U3）。 |
| 5 | `types/tools.ts` (563) | `UI_EVENT_TYPES` 与 `UiEvent` 联合**逐项一致**（15/15）；工具表风险列已按 DANGER/FRONTEND 修正 | **确认** I-11（15 类中 `export_started/import_batch/import_action` 无任何生产者，`:503-505` 为死分支）。**新增** FA6-03（`restore_task.taskType` 声明但既不产出也不消费）、FA6-10。 |
| 6 | `types/form-schema.ts` (832) | 白名单解析、fail-closed、与后端同源上限（20/50/200/2000）成立；`visibleValues` 丢可选空串（L-77）成立 | **确认** BZ-25（前端同样放行：`checkFormIssues` 仅在 `options.length > 0` 时判 `VALUE_OUT_OF_OPTIONS`，`:740` ⇒ 级联分支缺失时前端也不拦）、BZ-26（`asFiniteNumber` 把非有限 `step` 丢弃，前端不会崩但也不会预拦）；**限定** FE-08 的影响面（见 §3.4）。无新增缺陷。 |
| 7 | `types/resultview.ts` (117) | 四封闭枚举 + `fallback` 必填 + `data.ref/inline` 二选一 | `revision` 的"单调递增"只写在注释，仓库内无比较点（`grep revision` 于 `stores/ai.ts`/`utils/parts.ts` 零命中）——但契约本身规定"后到者覆盖"，故不立条目，列 §5-U4。 |
| 8 | `utils/format.ts` (118) | 渐进时间/步进标签/进度格式化 | **确认** FE-29（`:90-93` 中文业务标签）；`stepLabel` 的未知 key 原样返回（`:108-113`）是有意为之。无新增。 |
| 9 | `utils/context-usage.ts` (200) | 70/85/95 边界逐点正确（69→safe…95→danger）；`k` 缩写与"服务端算百分比"纪律成立 | **新增** FA6-11（唯一的逻辑分支不一致）。 |
| 10 | `utils/form-draft.ts` (87) | 仅 sessionStorage、按 `toolCallId` 一键一份、失败静默降级 | **确认** FE-08；**限定**：草稿值在渲染侧经 `initialModel` 按"字段类型形状 + 枚举合法值"过滤（`FormRenderer.vue:370-401`），故接管只泄露**同名字段的填值**，不构成 schema 绕过或未知键注入。 |
| 11 | `utils/form-scope.ts` (20) | 模块级自增序号，交互卡用 `toolCallId`、回放卡用 `ro<n>` | 与 L-92 一致。多实例（同 realm 多 app）唯一；跨 realm/跨 bundle 各自从 0 起（无现实消费者）。 |

---

## 2. 新增条目（代码可证；每条带触发与效果）

### FA6-01｜工作区动作与导航之间没有串行化 ⇒ 同批并行会打旧 taskId（S3｜C 机制 / R 落点）

- **位置**：`frontend/src/stores/workspace.ts:465-521`（`restore_task` 只 `router.push`；`select_defs/set_conditions/set_import_mode/goto_step` 一律读 `workspace.taskId`）；`frontend/src/utils/frontend-tools.ts:1019-1034`（有 handler 走 handler，无 handler 才落 `toUiEvent` ⇒ `dispatchUiEvent`）；`frontend/src/stores/ai.ts:5061`（每帧 `void this.runFrontendTool(...)`，**无队列**）。
- **触发**：模型在**一条 assistant 消息里同批发两个前端工具**：`navigate_to(page=import, taskId=B)` + `select_definitions(codes=[…])`。两个 `frontend_tool_request` 帧几乎同时到达，前者 `await router.push`（毫秒级，含新页 mount），后者立即进入 `runWorkspaceAction`：`pageId` 仍是旧的 `export`、`step` 仍满足 `allowSteps:['SELECT_DEFS','UPLOAD']`、`taskId` 仍是 **A**、`export.selectDefs` handler 因新页未挂载而**取不到** ⇒ 落到 ui_event 后备。
- **效果**：`tasksApi.selectDefs(A, codes)` —— AI 声称"为任务 B 选好配置项"，实际写在**任务 A** 上；回执 `已选择配置项…` 为 ok，模型不会怀疑。F3 的"等页面就绪/等任务绑定"只对**紧随其后的下一次调用**有效，对同批并行成员无效。
- **测试**：`frontend-tools.ts` 侧无任何并发用例；`workspace-run-job-publish-confirm`、`gate-matrix` 都是**串行直调** `dispatchUiEvent`。可证伪的最小断言：两次工具帧同批发、`router.push` 人为延迟 50ms，断言 `api.selectDefs` 的第一个实参为 B（现状为 A）。

### FA6-02｜ui_event 成功写被双记账，内部一条标成 `来源：界面`（S4｜C）

- **位置**：`frontend/src/stores/workspace.ts:450`（`dispatchUiEvent` 无条件 `touch(result.message,'AI')`）× `:180-190`（`setStep`）、`:213-224`（`setSelectedDefs` 默认 `source='界面'`）、`:226-232`（`setQueryConditions`）、`:234-245`（`setImportMode`）——调用点 `:481/:494/:507/:520` 均**不传** `source`。
- **触发**：ui_event 后备路径上的任一**成功**写（`select_defs`/`set_conditions`/`set_import_mode`/`goto_step`）。典型触发条件即 FA6-01 的并行窗口，或 FE-15 的"设置页不注销 pageId"窗口（handler 已随页面卸载而注销）。
- **效果**：① 一次动作写**两条** `recentActions`（`已选择配置项：A、B（来源：界面）` + `…（来源：AI）`），`dataVersion` +2；② 模型被告知"**用户自己点的**"，与实际相反——`setSelectedDefs` 的注释（`:206-212`）明确要求 AI 驱动应记 `AI`；③ 窗口只有 5 条（`types/tools.ts:563`），两三条 AI 动作即可把用户真实的点击挤出上下文。与 I-09 同族但**不同面**：I-09 锁的是失败回执 + chip 文案，本条的触发是成功写。
- **测试**：`tests/workspace-audience.spec.ts:25-52` 只锁"UI 手点 ⇒ `（来源：界面）`"；`tests/gate-matrix.spec.ts:183-184` 只锁失败事件"恰 5 条 AI 记录"；`tests/ai-dom-verbs.spec.ts:198-211` 的"恰 1 条/次"走的是 DOM 工具路径（`notifyWorkspaceChanged`），**不覆盖** `dispatchUiEvent` 的写分支。缺测：`dispatchUiEvent({type:'select_defs'})` 后断言 `recentActions` 长度为 1 且 `source==='AI'`（现状为 2 且含 `界面`）。

### FA6-03｜`restore_task` 丢掉 page/taskType ⇒ 错向导 + taskType 被改写（S3｜C）

- **位置**：`frontend/src/utils/frontend-tools.ts:852-864`（`navigate_to` 只产出 `{type:'restore_task', taskId}`，**不带 page/taskType**）；`frontend/src/types/tools.ts:496`（`taskType?` 声明了但唯一生产者不填）；`frontend/src/stores/workspace.ts:465-469`（`taskStore.resolveWizardRoute(event.taskId)` 无第二参）；`frontend/src/stores/task.ts:392-399`（`taskType ?? items.find(...) ?? 'EXPORT'`）。
- **触发**：模型调 `navigate_to(page='import', taskId=7)`，而任务 7 不在任务中心**当前页**（`size=10`，第 2 页起即不在）或当前筛选之外。
- **效果**：`resolveWizardRoute` 取不到缓存 ⇒ 回落 `EXPORT` ⇒ 打开**导出向导**，`ExportWizardView.loadTask`（`:399-430`）照常 `getTask(7)` 并把 `enterPage({taskType:'EXPORT', step:STEP_KEYS[0]})` 写进工作区：IMPORT 任务的 `currentStep`（`UPLOAD/PRECHECK/IMPORT/PUBLISH`）在导出 `STEP_KEYS` 里 `indexOf=-1` ⇒ 显示为第 1 步"选择配置项"，同时工作区契约从此**谎报** `taskType=EXPORT` ⇒ 披露给模型的是 `task:EXPORT/*` 工具面（含 `start_export` 的 DANGER 门），并出现"导入任务被当作导出任务"的页面。`restoreWizard` 的异步版失败时同样回落 EXPORT（`task.ts:402-409`）。
- **测试**：`tests/gate-matrix.spec.ts:47,107` 与 `tests/goto-step-readonly-guard.spec.ts:25-27` 都把 `useTaskStore` **整体 mock**（`resolveWizardRoute: vi.fn()`），真身零覆盖；任务中心行的 `restoreWizard(row.task.type)` 走的是另一条正确路径（显式传 type），也不覆盖该分支。可证伪断言：`items` 不含任务 7、`dispatchUiEvent({type:'restore_task',taskId:7})` 后断言 `router.push` 的 `name`（现状恒为 export）。

### FA6-04｜`load()` 的 `loading` 早退吞掉分页/筛选刷新（S4｜C）

- **位置**：`frontend/src/stores/task.ts:158-161`（`if (this.loading) return`）；`:197-203`（只有 `refreshNow` 置 `refreshPending`）；`:205-228`（`setPage/setSize/applyFilters` 直接 `void this.load()`，**不置 pending**）；`frontend/src/stores/workspace.ts:627-629`（`refresh_tasks` 无条件 `ok('任务列表已刷新')`）。视图侧分页/筛选不随 `loading` 禁用（`TaskCenterView.vue:115,211,226`）。
- **触发**：首屏 `load()` 在途（或手动刷新在途）时点分页/改筛选/改每页条数。
- **效果**：`page/size/filter` 已改而**列表未重拉**——分页器显示"第 2 页"、表格仍是第 1 页数据（下一次任意刷新才纠正）；筛选标签已生效、结果仍是旧集合。同类入口中只有 `refreshNow` 有"合并为恰好 1 次补发"的保护，三个 setter 没有。`refresh_tasks` 在同样窗口回"任务列表已刷新"的**假回执**（该分支按 I-11 现状无模型入口，故只作契约层缺陷登记）。
- **测试**：`tests/task-center-view.spec.ts` 对 `setPage/applyFilters/loading` **零断言**；`refreshPending` 无任何用例。可证伪断言：`load()` 挂起时调 `setPage(1)`，待其结束后断言发生了第二次请求。

### FA6-05｜`enterPage` 的"部分写入"语义 ⇒ 数据浏览页残留旧任务绑定（S4｜C）

- **位置**：`frontend/src/stores/workspace.ts:144-178`（只写"传入且非 undefined"的键，`taskStatus/queryConditions` 从不复位）；`frontend/src/views/DataBrowserView.vue:361`（`enterPage({pageId:'data',page:'data',taskType:null,step:null})` —— **缺 `taskId:null`**）、`:238`（同样缺）。
- **触发**：从导出/导入向导（已绑任务 #12）切到"数据浏览"。
- **效果**：`buildContext()` 在 `page=data / taskType=null / step=null` 的同时继续上报 `taskId=12`，面板 chip 也显示"数据浏览 · 任务 #12"（`workspace.ts:129-131`）——页面与上下文互相矛盾；模型可能据此引用/操作一个本页并不存在的任务。对照：`TaskCenterView.vue:361`、`DefinitionsView.vue:495` 都显式传 `taskId:null`，只有数据浏览漏了。
- **同源第二点**：`taskStatus` 也不复位；而 `isCurrentTaskReadOnly` 的 CANCELLED 分支（`:421-423`）**不要求 `taskId` 非空**，所以"进过已取消任务的向导 → 回任务中心（`taskId:null` 但状态仍 CANCELLED）"后，所有只读闸一律判只读，直到再次进入某个向导。当前因写事件同时受 `allowPages` 限制，实际可见面窄，故并入本条。
- **测试**：无（`DataBrowserView` 无 workspace 断言）。可证伪断言：向导内 → 切数据页 → `buildContext().taskId === null`（现状 12）。

### FA6-06｜`cancel_job` 的只读守卫跨任务误拒（S4｜C；按 I-11 现状不可达）

- **位置**：`frontend/src/stores/workspace.ts:570-578`（`isCurrentTaskReadOnly(workspace)` 完全不看 `event.jobId` 属于哪个任务、也不核归属）。
- **触发**：当前工作区任务为 CANCELLED/COMPLETED，而模型要取消的是**另一个任务**在跑的作业。
- **效果**：回执"任务已结束，仅供查看，未取消作业"——拒绝理由与目标对象无关；反之当前任务 ACTIVE 时对任意 `jobId` 都放行（靠后端兜）。因 `cancel_job` 当前无模型入口（I-11 已证），属契约层防御缺陷，不升级。
- **测试**：`gate-matrix` 只测"CANCELLED ⇒ `cancelJob` 不被调用"，不测跨任务。

### FA6-07｜发布跟踪的第二处单槽：workspace 级 dispose（S4｜C，补 FE-19）

- **位置**：`frontend/src/stores/workspace.ts:75`（模块级 `publishWatcherDispose` 单值）、`:551-552`（每次 `run_job(PUBLISH)` 先 `dispose()` 上一轮）；`frontend/src/stores/task.ts:293-297`（注册表按 `taskId:jobId` 本可并存）、`:308-313`（dispose 只删键 + 停 watchJob，**不判终态**）。
- **触发**：任一第二次 `run_job(PUBLISH)`（同任务重跑也会命中，不只是 FE-19 的"跨任务并发占闸"）。
- **效果**：上一轮 watcher 被静默停掉 ⇒ 其 FAILED 终态**既**走不到 `applyPublishOutcome` 的闸位检查**也**不会被 watchJob 观察到 ⇒ 无 ElMessage、无 `attachPublishConflict`、无 `reportUiEventResult`。FE-19 的落点是"闸位被新跟踪占据"，本条是"watcher 本身被提前拆除"——两条独立机制、同一用户可见结局（失败完全静默）。`tests/watch-publish-outcome.spec.ts:255-289` 只固化闸位那条。
- **测试**：同上；缺"同一 key 重跑后旧作业晚到 FAILED"的用例。

### FA6-08｜冲突明细"共 N 处"是页内条数，且无 ERROR 时把 WARNING 当冲突（S4｜C）

- **位置**：`frontend/src/stores/task.ts:361`（`getIssues(jobId, 0, 50)`）、`:366-375`（`counted = ERROR 非空 ? ERROR : 全量`）、`:103-110`（首行 `共 ${issues.length} 处`，`issues` 已被截到 50）。
- **触发**：一次发布校验产生 >50 条问题；或 FAILED 但只有 WARNING/INFO。
- **效果**：卡面 reason 与回灌给模型的摘要写"共 50 处"（真实 60+，**低报**且无"仅列前 50"之外的提示）；仅 WARNING 的失败被命名为"校验冲突明细（共 N 处）"，与"冲突项 = ERROR 级"的口径（`:371-373`）自相矛盾。`I-frontend-workspace §I-01` 只记了"最多 50"，未记首行计数的失真。
- **测试**：`publish-conflict-resultview` / `publish-conflict-link` 无 >50 与"仅 WARNING"夹具。

### FA6-09｜`contextSummary` 拼中文步骤名（S4｜C，补 FE-29）

- **位置**：`frontend/src/stores/workspace.ts:126-139`（`translate('ai.workspace.step', {label: stepLabel(state.step)})`）；`frontend/src/utils/format.ts:96-113`（`STEP_LABELS` 恒中文）。
- **触发**：UI 切 en-US，面板上下文 chip 在任一含 `step` 的页面。
- **效果**：模板是英文、插值是中文（"Step: 导出执行"）——与 `PAGE_LABEL_KEYS` 的双受众设计（页面名随语言）不一致；`recentActions.summary` 保持中文是**正确**的（C5），本条只针对 UI 面。

### FA6-10｜契约版本号没有任何判定方（S4｜C 代码 / D 设计）

- **位置**：`frontend/src/types/tools.ts:487-488`（注释"用于兼容判定"）；`frontend/src/stores/workspace.ts:80,294`（只存、只上报）；`frontend/src/App.vue:46`（只显示）。全仓 `grep contractVersion` 无任何比较/分支。
- **效果**：版本漂移（前端事件形状变了、后端 `extra` 解析变了）不会被任何一侧发现；模型收到的 `contractVersion` 也从无消费方。属"契约自述强于实现"，会误导后续维护者以为有兼容闸。

### FA6-11｜上下文 pill 在"有上限、无百分比"时丢分母（S4｜C）

- **位置**：`frontend/src/utils/context-usage.ts:154-159`（`limitTokens === null || percent === null` 合并处理）vs `:124-126`（tooltip 在 `percent===null` 但 `limitTokens!==null` 时走 `usedLimitNoPercent`，仍给分母）。
- **触发**：服务端返回 `limitTokens=128000, percent=null`（`ContextUsage` 允许，`types/sse.ts:499-511`）。
- **效果**：pill "已用 84k"、tooltip "84k / 128k"——同一份数据两种口径；用户扫一眼拿不到占比信息。`tests/context-pill.spec.ts` 只覆盖"上限已知+百分比"与"全未知"两态。

---

## 3. 多页签 / 多实例 / 乱序 / 错误行为（本组文件的专项结论）

1. **多页签**：`pageHandlers`、`subscribers`、`publishWatchers`、`watchingJobId`、`refreshPending`、`publishWatcherDispose`、`form-scope.sequence` 全是**模块级/每 realm**状态，页签之间零共享 ⇒ FE-14 成立；同时意味着"AI 在一个页签发起的发布冲突"永远不会出现在另一页签（I-05 的静默丢弃在跨页签时是**必然**而非偶发）。
2. **多实例（同页多 app/多面板）**：`form-scope` 用模块级自增，确实跨 app 实例唯一；但 `refreshPending` 与 `publishWatcherDispose` 也跨 app 共享——两个 app 各有一个任务中心时，A 的 `refreshNow` 会被 B 的在途 `load()` 顺带补发（多一次请求，无数据错），A 的发布跟踪会被 B 的新发布静默拆除（FA6-07 的放大版）。无测试。
3. **乱序**：`dispatchUiEvent` 无入队/锁，`recentActions` 的顺序 = **完成顺序**而非发起顺序 ⇒ 同批两动作的 `dataVersion` 与"动作日志"可能倒序；`FA6-01` 是同一根因的更严重表现。`ai.ts` 侧每帧 `void` 派发是这一点的直接证据。另：`applyPublishOutcome` 在门口检查闸位后 `await getIssues`，期间闸位被新跟踪占据也能落盘（这是**正确**的，保证本作业的冲突不被吞）——真正的丢点在 FA6-07 的 dispose 与 FE-19 的闸位。
4. **错误行为**：`task.load()` 失败只写 `loadError`、保留旧 `items`（可接受）；`isCurrentTaskReadOnly` 的 `jobsApi.listJobs(...).catch(() => null)` ⇒ **fail-open**（已由 I-12 登记）；`dispatchUiEvent` 的 `catch` 把任何异常翻成 `fail(ui_event 处理失败：…)` 并**照样** `touch(...,'AI')`（I-09/FA6-02）；`download` 到点 abort 走 fail（R-11 已闭合）；`watchPublishOutcome(…, jobId=0)` 直接返回 noop（`task.ts:290-291`），配合可选 `Job.id` 会产出"作业 #undefined 已启动"的成功回执。

---

## 4. 测试可信度（本组）

| 测试 | 实际锁住 | 盲区 |
|---|---|---|
| `workspace-audience.spec.ts` | 中文摘要 + `（来源：界面）`（UI 手点）+ `contextSummary` 中文 | 不锁 ui_event 路径的 source；不锁 en 下 `contextSummary` 的 step 插值（FA6-09） |
| `gate-matrix.spec.ts` | 5 个写事件在只读壳下都 fail 且不调 API；AI 记录恰 5 条 | `useTaskStore` 被整体 mock ⇒ `resolveWizardRoute` 真身零覆盖（FA6-03）；只测失败面 |
| `workspace-run-job-publish-confirm.spec.ts` | 发布模式 fail-closed + 确认门 | 直调 `dispatchUiEvent`，不经任何工具/面板（I-11 已判"绿色但不可达"） |
| `goto-step-readonly-guard.spec.ts` | `goto_step` 的只读闸 | 同样 mock task store；不锁 `setStep` 的二次记账 |
| `watch-publish-outcome.spec.ts:255-289` | 把"旧冲突静默丢弃"写成**期望** | 不区分同任务重跑（FA6-07）与跨任务并发（FE-19） |
| `task-center-view.spec.ts` | 渲染/取消确认 | 分页/筛选在 `loading` 下的行为零断言（FA6-04） |
| `gf-schema-rules` / `gf-renderer` / `gf-draft` | 纯函数 schema 校验、草稿读写 | 不覆盖"级联分支缺失⇒前端也放行"（BZ-25 的前端同源面）、不覆盖 `linkageInvalid` 的隐藏必填 |
| `context-pill.spec.ts` | 三档换色与文本 | 不覆盖 `limitTokens!==null && percent===null`（FA6-11） |
| `ai-dom-verbs.spec.ts:198-211` | DOM 工具"恰 1 条 recentActions/次" | 与 `dispatchUiEvent` 的写分支无关，不能用作 FA6-02 的回归锁 |

**结论**：本组 11 个文件的"契约层"行为（只读闸、fail-closed、中文红线）有单测；"跨调用时序"（并行、重跑、导航竞态）与"store 真身"（`resolveWizardRoute`、`load` 合并、`enterPage` 部分写入）**基本零覆盖**，且部分用例以 mock 替代被测对象。

---

## 5. 明确覆盖与未决分支

### 5.1 已逐行覆盖（本组全部行）

`workspace.ts` 639（state 12 键、4 getter、9 action、页面注册表、事件总线、`dispatchUiEvent` 15 分支 + `default`）、`task.ts` 424（5 getter/17 action、`refreshPending`/`publishWatchers`/`watchingJobId`/墙钟）、`ai.ts` 984（5 请求/响应体、`ContextUsage` 转出、`ChatMessage`+三段联合、`ToolPart` 20+ 字段、镜像 v2、GF 会话态、批③ 合并声明）、`sse.ts` 622（16 帧 + `reasoning` 兼容 + 3 个运行时助手 + 任务级 3 帧）、`tools.ts` 563（工具表、9 表格 + 7 UI 描述、`UiEvent` 15 型 + 白名单 + 记录/限长）、`form-schema.ts` 832（7 型解析、5 原语、取值校验 9 类码、`visibleValues`、不可重试码表）、`resultview.ts` 117、`format.ts` 118、`context-usage.ts` 200、`form-draft.ts` 87、`form-scope.ts` 20。

### 5.2 未决 / 无法定论（不立条目，登记备查）

- **U1 死分支**：`export_started`/`import_batch`/`import_action`（`workspace.ts:579-590`）在 `frontend/src` 内**无任何生产者**（`grep` 零命中，仅 `types/tools.ts` 与 workspace 自身），`batchId` 从未被消费；`import_action` 即使可达也只 `router.push` 到无 `taskId` 的导入向导。
- **U2 死判别字段**：`select_defs.task`（`types/tools.ts:498`）由 `toUiEvent` 生成、被 `runUiEvent` **忽略**（`:484-496`）；`restore_task.taskType` 无人填（FA6-03）。
- **U3 运行时校验名不副实**：`toSseFrame`（`sse.ts:568-580`）只认 `type`，不校验必填载荷（如 `delta.text`）；`frameSeq` 接受 0/负数与重复值。当前后端在仓内一致，故仅"缺防御"，未立条目。
- **U4 `resultView.revision` 无消费方**：契约写"同 id 单调递增"，但 `stores/ai.ts`/`utils/parts.ts` 无比较点；重放与实时同 revision 时行为无差异，故不立条目。
- **U5 前后端分支不可达**：`parseField` 允许 `options`+`optionsFrom` 并存，后端 `GenerativeFormRules` 判 `OPTIONS_SOURCE_CONFLICT`；`linkageInvalid` 的"隐藏必填字段"路径需后端 `linkageIssues` 漏过（已校验引用，故不可达）。两者均为前端不可达的防御分支，只作确认 BZ-25 的旁证。
- **U6 类型宽松点**（不构成缺陷，仅记录）：`AiChatContext.page?: string` 与 `WorkspaceSnapshot.page: string`（非 `WorkspacePageId`）⇒ 拼错页面名不会被类型捕获；`WorkspaceContract`（`types/workspace.ts:67`，不在本组 11 文件）声明了 `dispatch()` 而 store 无该方法且全仓零引用。
- **U7 FormFieldSpec 的 `min/max: number|string`** 同时服务 number 与 date：数值字段若给字符串 min，前端 `numberOutOfRange` 忽略、后端 `doubleOrNull` 直接拒 schema ⇒ 前端永远看不到该形态，**不是**前后端判定分歧。
- **U8 `between` 的字符串分支用字典序、`equalValue` 用 `String()` 比**（`form-schema.ts:473-483,502-512`）：对 `date` 正确；对"模型给了字符串数字"的 number/文本联动会给出 `"10" < "9"` 的判定。因 number 字段已被类型校验挡住，暂判无现发影响。

### 5.3 边界声明

本次严格限于 FA6 的 11 个文件 + `final-gap/FA6.md` 要求；为确认"用户可见效果"与"可达性"引用了 `utils/frontend-tools.ts`、`stores/ai.ts`（已由 P1 逐行）、两个向导视图、`App.vue`、`backend/.../GenerativeFormRules.java` 的**定点证据**，未对其做新的逐行审计，也未扩展到非 AI 业务内核。
