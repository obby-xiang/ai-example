> **历史分析 / 过程证据 — 非现行基线，非验收结论**
>
> - 归档：2026-10-26 · `docs/evidence/history-recovery-2026-10-26/wire-tail/`
> - 来源：Kimi Code 会话 `<KIMI_SESSION>` / 子 agent `agent-559` 的 wire 末条 assistant 文本（消息序号 m=7；只读流式解析，非原始会话转写）
> - 历史时点：2026-10-12 08:24 → 2026-10-12 08:24（wire 事件时间窗，UTC+8）；当时模型：`DeepSeek/deepseek-flash`
> - 原基线：`6c9c699`（正文自动摘取的首次基线提及），未经本批复验
> - 原始体量：15773 B / 12831 字符 / 223 行；行指纹覆盖率（本批复算）1.8%（最高单文件 1%：`docs/evidence/history-recovery-2026-10-26/wire-only/entries/126-Anchors-逐条-ecea153e-agent-561.md`）
> - 类别：**过程核验**
> - 与既有件关系：126-Anchors；报告口径覆盖率 0.01
> - 脱敏：路径 0 / 会话标识 0 / 身份 0 / 密钥 0 / 命名纪律 0
> - 正文为该条消息的完整文本，未删段、未改写结论；仅脱敏、占位符归一与行尾空白规整。当时结论不代表当前代码状态，不得作为当前缺陷或有效裁决引用。
核验完成（HEAD `6c9c699`，全程只读，未做任何写操作）。以下行号均为本次实际 Read 到的行号。

## Anchors

| # | 锚点 | 预期 | 实测（行号 + 原文） | 判定 |
|---|---|---|---|---|
| 1a | `task.ts:32` | `TASK_AUTO_REFRESH_MS = 4000` | `32	export const TASK_AUTO_REFRESH_MS = 4000` | 一致 |
| 1b | `task.ts:58` | timer 声明 | `58	let autoRefreshTimer: ReturnType<typeof setInterval> \| null = null` | 一致 |
| 1c | `task.ts:162` | `autoRefreshTimer = setInterval(` | `162	      autoRefreshTimer = setInterval(() => {` | 一致 |
| 1d | `task.ts:157-168` | startAutoRefresh | `157	    startAutoRefresh(intervalMs = TASK_AUTO_REFRESH_MS): void {` … `168	    },` | 一致 |
| 1e | `task.ts:170-176` | stopAutoRefresh | `170	    stopAutoRefresh(): void {` … `176	    },` | 一致 |
| 1f | `task.ts:114-116` | `if (this.loading) { return }` | `114	      if (this.loading) {` / `115	        return` / `116	      }` | 一致 |
| 2a | `task.ts:294-321` | watchPublishOutcome | `294	    async watchPublishOutcome(jobId: number): Promise<void> {` … `321	    },`（321 = action 收尾） | 一致 |
| 2b | `:298` | 调 `waitForJobFinal(jobId, {` 未传 signal | `298	      const job = await waitForJobFinal(jobId, {`；options 仅 `intervalMs: 1000`(299) 与 `onTick`(300-305)，**无 signal 键** | 一致（另有 295-297 `if (!jobId) { return }` 前置守卫，预期外） |
| 2c | `:308-310` | 非 FAILED 静默 `publishConflict = null` | `308	      if (job.status !== 'FAILED') {` / `309	        this.publishConflict = null` / `310	        return` | 一致 |
| 3a | `TaskCenterView.vue:8` | 「· 列表每 4 秒自动刷新」 | `8	          <span class="ml-1">· 列表每 4 秒自动刷新{{ lastLoadedText }}</span>` | 一致（尾部带插值 `{{ lastLoadedText }}`） |
| 3b | `:351` | `taskStore.startAutoRefresh()` | `351	  taskStore.startAutoRefresh()` | 一致 |
| 3c | `:355` | `taskStore.stopAutoRefresh()` | `355	  taskStore.stopAutoRefresh()` | 一致 |
| 3d | `:348-352` | onMounted | `348	onMounted(() => {` … `352	})` | 一致 |
| 3e | `:354-356` | onBeforeUnmount | `354	onBeforeUnmount(() => {` … `356	})` | 一致（357 行 = 文件末 `</script>`） |
| 4a | `workspace.ts:442` | `case 'run_job':` | `442	      case 'run_job': {` | 一致 |
| 4b | `:450-454` | PUBLISH 分支调 watchPublishOutcome | `450	          if (event.jobType === 'PUBLISH') {` / `452	          void taskStore.watchPublishOutcome(job.id ?? 0)` / `453-454` 收尾 `return ok(...)` + `}` | 一致（调用点在 **452**，`void` 丢弃、无 signal 形参） |
| 4c | `:499-506` | refresh_defs | `499	      case 'refresh_defs': {` … `506	      }`（505 = `...')` 收尾的 `return ok(` 串） | 一致 |
| 4d | `:507-513` | refresh_data | `507	      case 'refresh_data': {` … `513	      }` | 一致 |
| 4e | `:514-517`，`:515` `await taskStore.load()` | refresh_tasks | `514	      case 'refresh_tasks': {` / `515	        await taskStore.load()` / `516	        return ok('任务列表已刷新')` / `517	      }` | 一致 |
| 5 | `types/task.ts:112-119` | latestJob 行字段 | `112	/** 任务列表摘要（后端 record TaskSummary(Task task, int itemCount, int fileCount, Job latestJob)）。 */` / `113	export interface TaskSummary {` … `118	  latestJob: import('./job').Job \| null` / `119	}` | 一致（行级口径在 `types/job.ts:39-41` 标注 `progress/total 为**行级**口径`） |
| 6a | `docs/M2-排期计划.md:280` | R-88，行内含旧锚 `startAutoRefresh:182-199` | `280	\| R-88（轮询治理立项 · R-26 同型升维）…（`TASK_AUTO_REFRESH_MS=4000` / 模块级 `setInterval` / `startAutoRefresh:182-199`）…` | 一致（**该内嵌锚已过期**：现态 startAutoRefresh = 157-168） |
| 6b | `:281` | R-89，行内含 `ImportWizardView.vue:748-795` | `281	\| R-89（轮询治理 · 同类点登记…）① utils/job-watch.ts#watchJob（:86-121）+ views/ImportWizardView.vue:748-795 …` | 一致（**内嵌锚已漂移**，见 Notable 3） |
| 6c | `:218` | R-26 | `218	\| R-26（… B-26）stores/task.ts#startAutoRefresh（stores/task.ts:76,187）…` | 一致（内嵌 `:76,187` 亦为旧值；现态 = 58 / 157-168） |
| 6d | `:206` | R-14 | `206	\| R-14（… B-14）utils/job-watch.ts#watchJob（utils/job-watch.ts:86-121）+ views/ImportWizardView.vue:748-795…` | 一致（`job-watch.ts:86-121` 现态吻合：86 syncSnapshot、106 setInterval） |
| 6e | `:205` | R-13 | `205	\| R-13（… B-13）api/jobs.ts#waitForJobFinal（api/jobs.ts:50-68）…` | 一致（实测 `waitForJobFinal` 恰为 `jobs.ts:50-68`） |
| 6f | `:286` | R-94 | `286	\| R-94（UI 包拆除配套收尾 …）…（`stores/workspace.ts:398-405`）→ `watchPublishOutcome`…（`stores/task.ts:294-321`）…` | 一致（`task.ts:294-321` 与该行自引相符；`workspace.ts:398-405` 现态已变为 442-456，见 Notable 1） |
| 6g | `:347` | E2E 记 29 | `347	\| 15（T4-4）GF6 入参闸门用例启用 \| **闭环** \| 桩模式启用并**进主计数（28→29，实读）**…` | 一致 |
| 7 | `docs/requirements.md:58` | 「列表 5 秒自动刷新」+ 2026-10-10 注记纠正为 4s | `58	- **进度实时刷新**：列表 5 秒自动刷新作业进度；页面提供手动刷新 ｜**注记（2026-10-10 UI 改造）**：刷新间隔口径纠正——实测本仓任务中心列表为 **4s** 自刷（`frontend/src/stores/task.ts` 的 `TASK_AUTO_REFRESH_MS = 4000`，模块级 `setInterval`，每次 tick 前查 `loading` 防重叠），非 5 秒；原表述按只追加纪律保留留痕。` | 一致 |
| 8a | `setInterval` 调用点 3 处 | `task.ts:162`、`job-watch.ts:106`、`AiPanel.vue:839` | 命中（调用点）：`stores/task.ts:162`、`utils/job-watch.ts:106`、`components/AiPanel/AiPanel.vue:839` → **3 处** ✅；类型声明：`stores/task.ts:58`、`AiPanel/AiPanel.vue:454` → **2 处** ✅（另有 `clearInterval`：`task.ts:172`、`job-watch.ts:117`、`AiPanel.vue:851`；注释串：`views/TaskCenterView.vue:151`） | 一致 |
| 8b | `new EventSource` 2 处 | `api/tasks.ts:106`、`api/sse.ts:552` | `api/tasks.ts:106:  const source = new EventSource(\`/api/tasks/${taskId}/events\`)`；`api/sse.ts:552:      source = new EventSource(SSE_PATHS.events(runId, since ?? undefined))` | 一致 |
| 8c | `visibilitychange` / `document.hidden` | 0 处 | Grep `frontend/src` → 无命中 | 一致 |
| 8d | `TASK_CHANGED` 运行时消费 | 0（仅类型/注释） | `types/api.ts:125`（注释串）、`types/sse.ts:337`（类型联合 `\| 'TASK_CHANGED' \|`）、`types/sse.ts:339`（注释）；无运行时分支/订阅 | 一致 |
| 8e | `hasActiveJob` / `hasRunningJob` | 各 0 | Grep → 无命中 | 一致 |

## Verbatim snippets

`frontend/src/stores/task.ts`（行号前缀仅供定位，正文逐字照抄）：

```ts
113	    async load(): Promise<void> {
114	      if (this.loading) {
115	        return
116	      }
117	      this.loading = true
118	      this.error = null
119	      try {
120	        const result: PageResult<TaskSummary> = await tasksApi.listTasks(this.queryParams)
121	        this.items = result.content ?? []
122	        this.total = result.totalElements ?? 0
123	        this.lastLoadedAt = Date.now()
124	      } catch (error) {
125	        this.error = error instanceof Error ? error.message : '任务列表加载失败'
126	      } finally {
127	        this.loading = false
128	      }
129	    },
```

```ts
131	    setPage(page: number): void {
132	      this.page = page
133	      void this.load()
134	    },
135
136	    setSize(size: number): void {
137	      this.size = size
138	      this.page = 0
139	      void this.load()
140	    },
141
142	    applyFilters(patch: { type?: TaskType | null; status?: TaskStatus | null; keyword?: string }): void {
143	      if (patch.type !== undefined) {
144	        this.filterType = patch.type
145	      }
146	      if (patch.status !== undefined) {
147	        this.filterStatus = patch.status
148	      }
149	      if (patch.keyword !== undefined) {
150	        this.filterKeyword = patch.keyword
151	      }
152	      this.page = 0
153	      void this.load()
154	    },
```

```ts
156	    /** 开启 4s 自刷（已在跑则忽略；每次 tick 前检查 loading 防重叠）。 */
157	    startAutoRefresh(intervalMs = TASK_AUTO_REFRESH_MS): void {
158	      if (autoRefreshTimer !== null) {
159	        this.autoRefresh = true
160	        return
161	      }
162	      autoRefreshTimer = setInterval(() => {
163	        if (!this.loading) {
164	          void this.load()
165	        }
166	      }, intervalMs)
167	      this.autoRefresh = true
168	    },
169
170	    stopAutoRefresh(): void {
171	      if (autoRefreshTimer !== null) {
172	        clearInterval(autoRefreshTimer)
173	        autoRefreshTimer = null
174	      }
175	      this.autoRefresh = false
176	    },
```

```ts
179	    async refreshCurrent(): Promise<void> {
180	      const taskId = this.currentTaskId
181	      if (taskId === null) {
182	        return
183	      }
184	      this.detailLoading = true
185	      try {
186	        const overview = await tasksApi.getTaskOverview(taskId)
187	        this.currentTask = overview.task
188	        this.currentJobs = overview.jobs ?? []
189	        this.currentFiles = overview.files ?? []
190	        const latest = this.currentJobs[0]
191	        if (latest?.id !== undefined && latest.status === 'FAILED') {
192	          await this.loadIssues(latest.id)
193	        }
194	      } catch (error) {
195	        this.error = error instanceof Error ? error.message : '任务详情加载失败'
196	      } finally {
197	        this.detailLoading = false
198	      }
199	    },
```

```ts
288	    /**
289	     * 跟踪发布作业终态：FAILED 时查 issues 并组装 Q4 的冲突形态。
290	     *
291	     * 前端**禁止**把发布当成同步请求判错：POST /api/tasks/{id}/jobs 恒 201，
292	     * 冲突只能从作业终态 + issues 文案读出。
293	     */
294	    async watchPublishOutcome(jobId: number): Promise<void> {
295	      if (!jobId) {
296	        return
297	      }
298	      const job = await waitForJobFinal(jobId, {
299	        intervalMs: 1000,
300	        onTick: (tick) => {
301	          const index = this.currentJobs.findIndex((item) => item.id === jobId)
302	          if (index >= 0) {
303	            this.currentJobs.splice(index, 1, tick)
304	          }
305	        }
306	      })
307	      await this.refreshCurrent()
308	      if (job.status !== 'FAILED') {
309	        this.publishConflict = null
310	        return
311	      }
312	      await this.loadIssues(jobId)
313	      const errorIssues = this.issues.filter((issue) => issue.severity === 'ERROR')
314	      const first = errorIssues[0]
315	      this.publishConflict = {
316	        jobId,
317	        jobStatus: 'FAILED',
318	        issueKeys: errorIssues.map((issue) => `${issue.defCode}#${issue.rowKey ?? '-'}`),
319	        message: first?.message ?? '发布作业失败，请查看校验问题清单'
320	      }
321	    },
```

`filterStatus` 声明处（state）：

```ts
66	    /** 筛选条件（keyword 的 LIKE 转义由后端处理） */
67	    filterType: null as TaskType | null,
68	    filterStatus: null as TaskStatus | null,
69	    filterKeyword: '',
70	    loading: false,
```

`loading` 全部置位点（`stores/task.ts`，Grep 全文件确认无遗漏）：

- `70	    loading: false,`（state 初值）
- `117	      this.loading = true`（唯一置 true）
- `127	        this.loading = false`（唯一置 false，位于 `finally`）
- 读取点：`114 if (this.loading)`（`load()` 短路）、`163 if (!this.loading)`（自刷 tick 守卫）。`detailLoading`(184/197)、`issuesLoading`(276/284)、`autoRefresh`(73/159/167/175) 为独立字段，不计入 `loading`。

`frontend/src/stores/workspace.ts` `run_job` 分支全文：

```ts
442	      case 'run_job': {
443	        // 收口修 F6（红队 minor-4）：目标任务是当前只读任务时拒绝（与页面 start 系 handler 同口径）；
444	        // 其余任务 id 不在当前只读壳语义内，按原路径放行（后端终态守卫另见 R-137 登记）。
445	        if (event.taskId === workspace.taskId && (await isCurrentTaskReadOnly(workspace))) {
446	          return fail('任务已结束，仅供查看，未启动作业')
447	        }
448	        const job = await jobsApi.createJob(event.taskId, event.jobType)
449	        await taskStore.refreshCurrent()
450	        if (event.jobType === 'PUBLISH') {
451	          // Q4：发布冲突是**异步形态**（恒 201），必须等作业终态后查 issues
452	          void taskStore.watchPublishOutcome(job.id ?? 0)
453	          return ok(`发布作业已启动（作业 #${job.id}），冲突会在完成后以作业失败 + 问题清单呈现`)
454	        }
455	        return ok(`作业已启动：${event.jobType}（作业 #${job.id}）`)
456	      }
```

## Notable findings

1. **`workspace.ts` 行号整体前移（相对卡草案自引）**：R-94（`docs/M2-排期计划.md:286`）自引 `stores/workspace.ts:398-405`，现态 run_job 分支 = `442-456`、`watchPublishOutcome` 调用点 = `452`。文档内锚已漂移，但 4a–4e 各条"预期行号"与实测完全吻合。
2. **`watchPublishOutcome` 的超时静默面在文件里是活的**：`api/jobs.ts:63-65` 超时分支 `return job`（非终态、不抛），因此 `task.ts:308` 的 `job.status !== 'FAILED'` 会吞掉"发布悬置"（R-13 / R-89② 所指同一处），实测确认该路径可达且**无任何日志/告警语句**。另有预期外的前置守卫 `295-297`（`if (!jobId) return`）。
3. **R-89 / R-14 的内嵌锚 `ImportWizardView.vue:748-795` 已失锚**：实测 745-748 为 `goStep` 收尾，750-756 为"只读态点节点查看"注释块；ImportWizard 实际的 watcher 调用 = `views/ImportWizardView.vue:844`（`const dispose = watchJob({`，import 在 `:236`）。同族另有**预期外调用点** `views/ExportWizardView.vue:729`（`stopWatchJob = watchJob({`），即 `watchJob` 全仓 2 处调用（非 1 处）。
4. **`startAutoRefresh:182-199`（R-88 行内）与 `stores/task.ts:76,187`（R-26 行内）均为旧锚**：现态 = 声明 `:58`、方法 `157-168`。此即草案所述"行内含旧锚"的事实基础，核验成立。
5. **`TASK_CHANGED` 确为零运行时消费**：全 `frontend/src` 仅 3 处命中，均为类型/注释（`types/sse.ts:337` 类型联合、`types/sse.ts:339` 注释、`types/api.ts:125` 注释），与 R-98 W-11 及锚点 8d 相符；这也解释了"任务中心只能靠 4s 轮询"。
6. **`startAutoRefresh` 有一处预期外的"已开启"分支副作用**：`158-161` 在 `autoRefreshTimer !== null` 时把 `this.autoRefresh = true` 后 `return`（即重复调用会重置标志但**不改 interval**）；因此 `TaskCenterView.vue:351` 与 `:355` 是**唯一**的启停调用点（Grep 全 `frontend/src` 仅这两处），`TASK_AUTO_REFRESH_MS` 除 `task.ts:32/157` 外无其他引用点。
7. **`refreshCurrent` 的死链判断被代码坐实**：`currentTaskId` 全仓仅 4 处（`task.ts:77` 声明、`180` 读取、`202` 写 null、`225` 比较），**无任何非空写点** ⇒ `refreshCurrent`（179-199）恒在 `181-183` 早退；但 `workspace.ts:449`、`task.ts:239/251/264/307` 仍调用它（R-94 ② 的死码面成立，且调用点不止 R-94 所举的 3 处）。
8. 锚点 8a 的计数口径已复现且无多余：`setInterval` **实调用**恰 3 处，另 2 处为 `ReturnType<typeof setInterval>` 类型声明，`clearInterval` 3 处（`task.ts:172`、`job-watch.ts:117`、`AiPanel.vue:851`），`TaskCenterView.vue:151` 仅为注释提及 —— 均与预期清单一致，未发现预期外 tick 源。
