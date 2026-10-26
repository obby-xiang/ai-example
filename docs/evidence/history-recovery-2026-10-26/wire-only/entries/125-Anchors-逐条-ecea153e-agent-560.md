> **历史分析 / 过程证据 — 非现行基线，非验收结论**
>
> - 归档：2026-10-26 · `docs/evidence/history-recovery-2026-10-26/wire-only/`
> - 来源：Kimi Code 会话 `<KIMI_SESSION>` / 子 agent `agent-560` 的 wire 末条 assistant 文本（只读流式解析；非原始会话转写）
> - 原始体量：20054 B / 16311 字符 / 349 行；行指纹覆盖率（仓内 + 落盘语料）0.4%
> - 类别：**过程核验**
> - 脱敏：路径 0 / 会话标识 0 / 身份 0 / 密钥 0
> - 正文为该条消息的完整文本，未删段、未改写结论；仅脱敏与行尾空白规整。当时结论不代表当前代码状态。
## Anchors（逐条）

| 锚点 | 预期 | 实测（行号 + 原文） | 判定 |
|---|---|---|---|
| `job-watch.ts:21` | `export const JOB_FALLBACK_POLL_MS = 3000` | `:21` `export const JOB_FALLBACK_POLL_MS = 3000` | 一致 |
| `job-watch.ts:46` | `export function watchJob(...)` | `:46` `export function watchJob(options: JobWatchOptions): () => void {` | 一致 |
| `job-watch.ts:76-78` | onopen→realtime | `:76` `source.onopen = () => {` / `:77` `emitChannel('realtime')` / `:78` `}` | 一致 |
| `job-watch.ts:79-84` | onerror→polling | `:79` `source.onerror = () => {` / `:80` 注释 / `:81` `if (source.readyState !== EventSource.OPEN) {` / `:82` `emitChannel('polling')` / `:83-84` `}` `}` | 一致 |
| `job-watch.ts:96` | `dispose()`（终态分支内） | `:94` `if (isJobFinal(job.status)) {` → `:95` `const finalJob = job` / `:96` `dispose()` / `:97` `options.onFinal?.(finalJob)` | 一致 |
| `job-watch.ts:106-110` | `setInterval`（`if (!disposed) void syncSnapshot()`） | `:106` `const timer = setInterval(() => {` / `:107` `if (!disposed) {` / `:108` `void syncSnapshot()` / `:109` `}` / `:110` `}, pollIntervalMs)` | 一致 |
| `job-watch.ts:121` | 首拉 `void syncSnapshot()` | `:121` `void syncSnapshot()`（`:122` `return dispose`） | 一致 |
| `tasks.ts:105-115` | `openTaskEvents` | `:105` `export function openTaskEvents(taskId: number, onFrame: (frame: TaskEventFrame) => void): EventSource {` … `:115` `}` | 一致 |
| `tasks.ts:106` | `new EventSource(...)` | `:106` `const source = new EventSource(\`/api/tasks/${taskId}/events\`)` | 一致 |
| `tasks.ts` 仅 `onmessage`+try/catch、无 onerror | 无 onerror | `:107` 只有 `source.onmessage = …`；`:108-112` try/catch；全文无 `onerror` / `close()` / 最大时长 | 一致 |
| `tasks.ts:114` | `return source` | `:114` `return source` | 一致 |
| `ImportWizardView.vue:306` | `stopWatchers` 声明 | `:306` `let stopWatchers: Array<() => void> = []` | 一致 |
| `ImportWizardView.vue:840-888` | `beginWatchJob` | `:840` `function beginWatchJob(kind: 'check' \| 'import' \| 'publish', jobId: number): void {` … `:888` `}` | 一致 |
| `ImportWizardView.vue:887` | `stopWatchers.push(dispose)`，其前无同 kind 回收 | `:887` `stopWatchers.push(dispose)`；`:840-886` 全文无任何按 kind 的旧 watcher 查找/回收 | 一致（且"无回收"成立） |
| `ImportWizardView.vue:806 / :818` | `startJob` / 调 `beginWatchJob` | `:806` `async function startJob(kind: 'check' \| 'import' \| 'publish'): Promise<void> {`；`:817-819` `if (job.id !== undefined) {` `beginWatchJob(kind, job.id)` | 一致 |
| `ImportWizardView.vue:504 / :516` | `attachJob` / 调 `beginWatchJob` | `:504` `async function attachJob(kind: … , job: Job): Promise<void> {`；`:515-516` `if (jobRunning(job) && job.id !== undefined) {` `beginWatchJob(kind, job.id)` | 一致 |
| `ImportWizardView.vue:1115-1116` | 卸载回收 | `:1114` `onBeforeUnmount(() => {` / `:1115` `stopWatchers.forEach((dispose) => dispose())` / `:1116` `stopWatchers = []` | 一致 |
| `AiPanel.vue:839-841` | 1s tick `setInterval` | `:839` `tickTimer = setInterval(() => {` / `:840` `now.value = Date.now()` / `:841` `}, 1000)` | 一致 |
| `AiPanel.vue:850-853` | clearInterval | `:849` `onBeforeUnmount(() => {` / `:850` `if (tickTimer !== null) {` / `:851` `clearInterval(tickTimer)` / `:852` `tickTimer = null` / `:853` `}` | 一致（`clearInterval` 实际落在 `:851`，偏 1 行仍在区间内） |
| `AiPanel.vue:452-454` | now/confirmDeadline/tickTimer 声明 | `:452` `const now = ref(Date.now())` / `:453` `const confirmDeadline = ref<number \| null>(null)` / `:454` `let tickTimer: ReturnType<typeof setInterval> \| null = null` | 一致 |
| `AiPanel.vue:792-805` | `resetPendingWatch` | `:792` JSDoc / `:793` `function resetPendingWatch(pending: PendingToolCall \| null): void {` … `:805` `}` | 一致 |
| `ExportWizardView.vue:234` | `stopWatchJob` 声明 | `:234` `let stopWatchJob: (() => void) \| null = null` | 一致 |
| `ExportWizardView.vue:489-495` | 改绑回收 | `:489-495` `stopJobWatchForTaskSwitch()` 全文（见 Verbatim）；调用点 `:518` | 一致 |
| `ExportWizardView.vue:723-728` | `beginWatchJob` 单实例，`:724` 先调 `stopWatchJob?.()` | `:723` `function beginWatchJob(jobId: number): void {` / `:724` `stopWatchJob?.()` / `:725` `currentIssues.value = []` / `:726-728` `if (taskId.value === null \|\| jobId <= 0) {` `return` `}` | 一致 |

其他核对：仓库 HEAD = `6c9c699aa680297ecf0a83ec39eeae8716b68431`（`git rev-parse`），`git status --porcelain` 为空（工作区干净）。`job-watch.ts` 实际 **123 行**（锚点写"约 124 行"，非漂移）。

## Verbatim（逐字代码，标行号）

### `frontend/src/utils/job-watch.ts`（全文，1-123）

```ts
  1	/**
  2	 * 作业跟踪器：**SSE 主通道 + 轮询兜底**（规格 §2「SSE 进度通道」，kimi 蓝本的纯轮询弃用）。
  3	 *
  4	 * 通道分工（后端 TaskSseService 实际发布点）：
  5	 * - `JOB_PROGRESS`（作业执行器分片边界发布，行级 processed/total + defCode）
  6	 *   → 直接驱动进度条，不必等下一次快照请求；
  7	 * - `JOB_DONE`（JobTerminalWriter 在数据面事务提交后发布）→ 触发一次权威快照拉取，
  8	 *   快照说终态才算终态（避免"事件领先于库"）；
  9	 * - 兜底轮询：EventSource 断线/代理抖动时业务不能停在假进度上，固定间隔拉快照自愈。
 10	 *
 11	 * 终态判定一律以 `GET /api/jobs/{jobId}` 的快照为准（isJobFinal），
 12	 * 不拿 JOB_DONE 的载荷当终态依据。
 13	 */
 14
 15	import { jobsApi } from '@/api/jobs'
 16	import { tasksApi } from '@/api/tasks'
 17	import { isJobFinal, type Job } from '@/types/job'
 18	import type { JobDonePayload, JobProgressPayload, TaskEventFrame } from '@/types/sse'
 19
 20	/** 兜底轮询间隔（SSE 正常时只是低频自愈，不构成负载）。 */
 21	export const JOB_FALLBACK_POLL_MS = 3000
 22
 23	/** SSE 通道状态（供 UI 显示"实时通道已降级"）。 */
 24	export type JobChannelState = 'connecting' | 'realtime' | 'polling'
 25
 26	export interface JobWatchOptions {
 27	  taskId: number
 28	  jobId: number
 29	  /** SSE JOB_PROGRESS 帧（行级进度即时刷新） */
 30	  onProgress?: (payload: JobProgressPayload) => void
 31	  /** 每次取到的作业快照 */
 32	  onSnapshot?: (job: Job) => void
 33	  /** 作业进入终态（快照为准） */
 34	  onFinal?: (job: Job) => void
 35	  /** 通道状态变化（realtime=SSE 已连上；polling=降级为轮询） */
 36	  onChannel?: (state: JobChannelState) => void
 37	  /** 兜底轮询间隔 */
 38	  pollIntervalMs?: number
 39	  /** 拉取快照失败时的回调（网络抖动不该打断跟踪） */
 40	  onError?: (error: unknown) => void
 41	}
 42
 43	/**
 44	 * 开始跟踪一个作业，返回停止函数（页面卸载/切作业时必须调用）。
 45	 */
 46	export function watchJob(options: JobWatchOptions): () => void {
 47	  const pollIntervalMs = options.pollIntervalMs ?? JOB_FALLBACK_POLL_MS
 48	  let disposed = false
 49	  let inFlight = false
 50
 51	  const emitChannel = (state: JobChannelState): void => {
 52	    if (!disposed) {
 53	      options.onChannel?.(state)
 54	    }
 55	  }
 56
 57	  const source = tasksApi.openTaskEvents(options.taskId, (frame: TaskEventFrame) => {
 58	    if (disposed) {
 59	      return
 60	    }
 61	    if (frame.type === 'JOB_PROGRESS') {
 62	      const payload = frame.data as JobProgressPayload
 63	      if (payload.jobId === options.jobId) {
 64	        options.onProgress?.(payload)
 65	      }
 66	      return
 67	    }
 68	    if (frame.type === 'JOB_DONE') {
 69	      const payload = frame.data as JobDonePayload
 70	      if (payload.jobId === options.jobId) {
 71	        void syncSnapshot()
 72	      }
 73	    }
 74	  })
 75
 76	  source.onopen = () => {
 77	    emitChannel('realtime')
 78	  }
 79	  source.onerror = () => {
 80	    // EventSource 自身会按 retry 间隔重连；这里只把状态标为降级，业务继续靠轮询推进
 81	    if (source.readyState !== EventSource.OPEN) {
 82	      emitChannel('polling')
 83	    }
 84	  }
 85
 86	  async function syncSnapshot(): Promise<void> {
 87	    if (disposed || inFlight) {
 88	      return
 89	    }
 90	    inFlight = true
 91	    try {
 92	      const job = await jobsApi.getJob(options.jobId)
 93	      options.onSnapshot?.(job)
 94	      if (isJobFinal(job.status)) {
 95	        const finalJob = job
 96	        dispose()
 97	        options.onFinal?.(finalJob)
 98	      }
 99	    } catch (error) {
100	      options.onError?.(error)
101	    } finally {
102	      inFlight = false
103	    }
104	  }
105
106	  const timer = setInterval(() => {
107	    if (!disposed) {
108	      void syncSnapshot()
109	    }
110	  }, pollIntervalMs)
111
112	  function dispose(): void {
113	    if (disposed) {
114	      return
115	    }
116	    disposed = true
117	    clearInterval(timer)
118	    source.close()
119	  }
120
121	  void syncSnapshot()
122	  return dispose
123	}
```

### `frontend/src/api/tasks.ts:105-115`

```ts
105	export function openTaskEvents(taskId: number, onFrame: (frame: TaskEventFrame) => void): EventSource {
106	  const source = new EventSource(`/api/tasks/${taskId}/events`)
107	  source.onmessage = (event: MessageEvent<string>) => {
108	    try {
109	      onFrame(JSON.parse(event.data) as TaskEventFrame)
110	    } catch {
111	      // 非 JSON 帧（心跳注释等）忽略
112	    }
113	  }
114	  return source
115	}
```

（注释行 `:99-104` 原文含 `按规格走 **4s 轮询**`，与 `JOB_FALLBACK_POLL_MS = 3000` 口径不一致，见 Notable。）

### `frontend/src/views/ImportWizardView.vue`

`:306`
```ts
306	let stopWatchers: Array<() => void> = []
```

`:840-888`
```ts
840	function beginWatchJob(kind: 'check' | 'import' | 'publish', jobId: number): void {
841	  if (taskId.value === null) {
842	    return
843	  }
844	  const dispose = watchJob({
845	    taskId: taskId.value,
846	    jobId,
847	    onProgress: (payload) => {
848	      const current = jobOf(kind)
849	      if (current) {
850	        setJob(kind, { ...current, status: 'RUNNING', progress: payload.processed, total: payload.total })
851	      }
852	    },
853	    onSnapshot: (job) => {
854	      setJob(kind, job)
855	    },
856	    onChannel: (state) => {
857	      setChannel(kind, state)
858	    },
859	    onFinal: async (job) => {
860	      setJob(kind, job)
861	      await refreshIssues(kind, jobId)
862	      if (kind === 'publish') {
863	        // Q4：发布恒 201，冲突只能从终态 + issues 读出
864	        if (job.status === 'FAILED') {
865	          await buildPublishConflict(job)
866	          ElMessage.error('发布未成功：请查看冲突明细后重新导入再发布')
867	        } else if (job.status === 'COMPLETED') {
868	          ElMessage.success('发布成功，任务已完成')
869	        } else {
870	          ElMessage.info('发布作业已取消')
871	        }
872	        // 任务状态由后端 JobTerminalWriter 联动作业终态，这里一律刷新快照（失败也要显示 FAILED）
873	        await refreshTaskSnapshot()
874	        return
875	      }
876	      if (job.status === 'COMPLETED') {
877	        ElMessage.success(`${kind === 'check' ? '预检查' : '导入'}作业完成`)
878	      } else if (job.status === 'FAILED') {
879	        ElMessage.error(`${kind === 'check' ? '预检查' : '导入'}作业失败，请展开明细查看`)
880	      }
881	      if (kind === 'import' && job.status === 'COMPLETED') {
882	        await loadStaging()
883	      }
884	      await refreshTaskSnapshot()
885	    }
886	  })
887	  stopWatchers.push(dispose)
888	}
```

`:1114-1118`（卸载）
```ts
1114	onBeforeUnmount(() => {
1115	  stopWatchers.forEach((dispose) => dispose())
1116	  stopWatchers = []
1117	  HANDLER_NAMES.forEach(unregisterPageHandler)
1118	})
```

### `frontend/src/views/ExportWizardView.vue`

`:234`
```ts
234	let stopWatchJob: (() => void) | null = null
```

`:489-495`
```ts
489	function stopJobWatchForTaskSwitch(): void {
490	  stopWatchJob?.()
491	  stopWatchJob = null
492	  exportJob.value = null
493	  currentIssues.value = []
494	  channelState.value = null
495	}
```

`:723-728`
```ts
723	function beginWatchJob(jobId: number): void {
724	  stopWatchJob?.()
725	  currentIssues.value = []
726	  if (taskId.value === null || jobId <= 0) {
727	    return
728	  }
```
（`:729` `stopWatchJob = watchJob({ … })`；`:1355-1357` 卸载同样 `stopWatchJob?.()` + 置 null）

### `frontend/src/components/AiPanel/AiPanel.vue:839-853`

```ts
839	  tickTimer = setInterval(() => {
840	    now.value = Date.now()
841	  }, 1000)
842	  void ai.probeHealth()
843	  if (!ai.messages.length) {
844	    await ai.loadHistory()
845	  }
846	  scrollBottom()
847	})
848
849	onBeforeUnmount(() => {
850	  if (tickTimer !== null) {
851	    clearInterval(tickTimer)
852	    tickTimer = null
853	  }
```

## Semantic answers

**a. dispose / 防重 / dispose 后重入**
- `source.close()`：**有** —— `:118`（在 `clearInterval(timer)` `:117` 之后）。
- 标志与防双重 dispose：**有** —— `disposed` 声明 `:48`，`dispose()` 内 `:113-115` `if (disposed) { return }`（幂等）。消费侧另有 3 处 `disposed` 闸：`:52-54`（emitChannel）、`:58-60`（SSE 帧入口）、`:87-89`（syncSnapshot 入口）。
- **存在"dispose 之后仍触发"的路径：有，且是 in-flight promise 重入**。`:92` `const job = await jobsApi.getJob(...)` 之后**没有任何 `disposed` 复检**：
  - 若在 `getJob` 在途期间被外部 dispose（Export 的 `stopJobWatchForTaskSwitch` `:489`、或两页 `onBeforeUnmount`），resolve 后仍会执行 `:93` `options.onSnapshot?.(job)` 写状态，并在 `:94` 判终态后执行 `:96` `dispose()`（此时是 no-op）+ `:97` `options.onFinal?.(finalJob)` —— 即 **dispose 之后的迟到 onFinal**。`inFlight` 只防并发拉取，**不取消在途请求**（无 AbortController）。
  - 对 Import 页后果尤重：`onFinal` 内 `refreshTaskSnapshot()`（`:891-901`）读的是**当前** `taskId.value`，而 watcher 自己是旧任务创建时捕获的 `taskId`（`:845`）—— 正是 Export `:483-488` 注释描述的跨任务写。
  - SSE 帧侧的"迟到重入"：`close()` 后再来的帧由 `:58` 挡住；`:79` 的 `onerror` 在 `close()` 后规范上不再触发。所以唯一未设防的重入点是 `:92→:93/:97` 这一段。
  - 另注：`:96 dispose()` 先于 `:97 onFinal()` 是**设计内**顺序（内部终态路径），但代码无法区分"内部终态 dispose"与"外部提前 dispose"。

**b. 3s `setInterval` 的 `inFlight` 守卫**
- 守卫在 **`syncSnapshot` 内**，不在 interval 闭包里：`:87-88` `if (disposed || inFlight) { return }`。interval 闭包自身只判 `:107 if (!disposed)`。
- 语义 = **丢弃**（drop），不是排队：命中守卫直接 `return`，不缓存、不补拉，该次 tick / 该次调用整个作废；`inFlight` 由 `:102 finally` 复位，最多等到下一个 3s tick。
- 原文：
```ts
 86	  async function syncSnapshot(): Promise<void> {
 87	    if (disposed || inFlight) {
 88	      return
 89	    }
 90	    inFlight = true
 ...
101	    } finally {
102	      inFlight = false
103	    }
104	  }
```
- 副作用（重要）：`:71` 由 `JOB_DONE` 触发的"权威快照拉取"与 `:108` 轮询 tick **共用同一个 `inFlight`**，因此 JOB_DONE 落在一次轮询在途窗口内时会被静默丢弃 —— 只能靠 ≤3s 后的下一次 tick 自愈（延迟退化，不是永久丢失）。

**c. `openTaskEvents` 的 onerror / close() / 最大时长**
- **三者都没有**，与预期一致。`:105-115` 内只有 `new EventSource`（`:106`）+ `source.onmessage`（`:107`）+ try/catch（`:108-112`）+ `return source`（`:114`）。无 `onerror`、无 `source.close()`、无超时/最大时长字段。`onerror` 是调用方 `job-watch.ts:79` 事后挂上的；`close()` 只在 `job-watch.ts:118` 由 `dispose()` 调用，即**只有 dispose 能关流**，函数自身不提供关闭句柄。

**d. `emitChannel` 与 channel 状态置位/复位**
- `emitChannel` **存在**：`job-watch.ts:51-55`（带 `disposed` 闸）。
- 置位点（全量，仅两处）：`:77` `emitChannel('realtime')`（`source.onopen`）；`:82` `emitChannel('polling')`（`source.onerror`，且仅当 `source.readyState !== EventSource.OPEN`）。
- **`'connecting'` 从未被 emit** —— 它在 `:24` 的类型联合里，但全仓库无任何 `emitChannel('connecting')`/赋值（grep `connecting` 仅命中 `job-watch.ts:24` 与 `JobProgress.vue:126/129` 的渲染判断），即初始态只能呈现为 `null`。
- 消费/复位点：
  - Import：`:856-858` `onChannel: (state) => { setChannel(kind, state) }` → `:917-925` `setChannel` 写 `checkChannel/importChannel/publishChannel`（声明 `:294-296`）。**Import 页没有任何复位到 `null` 的路径**（无 `stopJobWatch*` 函数，grep 无 `Channel.value = null`）。
  - Export：`:740-742` `channelState.value = state`（声明 `:219`）；**复位** `:494` `channelState.value = null`（在 `stopJobWatchForTaskSwitch` 内，由 `:518` 在改绑时调用）；卸载路径 `:1355-1358` 未复位 `channelState`。

**e. Import `beginWatchJob` 是否完全无按 kind 回收**
- **判定：完全无。** `:840-888` 全文（上）中，唯一的前置判断是 `:841-843 if (taskId.value === null) { return }`；随后直接 `const dispose = watchJob({...})`（`:844`）→ `stopWatchers.push(dispose)`（`:887`）。没有任何 `Map<kind, dispose>` / `stopWatchers.find(...)` / 同 kind 先 `dispose()` + 从数组移除的逻辑；`stopWatchers` 只在卸载时整体遍历（`:1115`），且**终态自 dispose 后也不从数组剔除**。
- 可重复进入路径（实测）：`:818`（`startJob` 内，作业启动即注册）+ `:516`（`attachJob` 内，仅 `jobRunning(job)` 时）——而 `attachJob` 由 `resumeJobs()` `:483-502` 在每次 `loadTask()` `:458` 调用，`loadTask` 又被 `route.query.taskId` 的 watch `:1152-1163` 触发。故同 kind 可叠加多个 watcher，每个持有各自的 `jobId` 与创建时快照的 `taskId`（`:845`），但 `onFinal` 里的 `refreshTaskSnapshot()` 按**当时**的 `taskId.value` 落状态。

## Notable findings

1. **`syncSnapshot` await 后无 `disposed` 复检**（`job-watch.ts:92-98`）：外部 dispose（Export 改绑 `:489`、两页卸载）无法阻止在途快照写回与 `onFinal` 触发。这是 `job-watch.ts` 里唯一的"dispose 后重入"面，也是 Export 注释 `:483-488` 所描述缺陷的**底层成因**。
2. **`JOB_DONE` 的权威拉取会被 `inFlight` 丢弃**（`:71` 与 `:87` 共用守卫）：事件驱动降级为最多 3s 后的轮询自愈，与文件头 `:7-8` 声称的"JOB_DONE → 触发一次权威快照拉取"在时序上并非总能成立。
3. **通道状态机不完整**：`'connecting'`（`:24`）无产生点；Import 侧三个 channel ref 无复位路径；Export 卸载路径未复位 `channelState`。
4. **Import 缺 Export 已有的改绑回收**：`ExportWizardView.vue:489-495` 有 `stopJobWatchForTaskSwitch` 且 `:518` 在 `applyTaskBinding` 中调用；Import 的对应路径（`route.query.taskId` watch `:1152-1163` → `loadTask` → `resumeJobs` → `attachJob` → `beginWatchJob`）无任何停旧逻辑，watcher 与 `stopWatchers` 数组单调增长。
5. **口径注释不一致**：`api/tasks.ts:103` 注释称"任务中心的进度口径按规格走 **4s 轮询**"，而 `job-watch.ts:21` 实际兜底为 `3000` ms；两处数字不对齐（注释级，无代码影响）。
6. `openTaskEvents` 调用侧无法拿关闭句柄以外的控制（无 abort/超时），且 `EventSource` 在 `dispose()` 之外无生命周期出口 —— 若 `watchJob` 未被消费方调用 dispose，SSE 连接会随重连持续存活（Import 场景下与第 4 点叠加）。
