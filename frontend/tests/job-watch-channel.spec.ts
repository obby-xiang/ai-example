/**
 * 轮询治理一期 · 步3（第二个 spec）：`utils/job-watch.ts` 的**通道降级切换**（D6）+ 权威快照（D6）
 * + dirty flag 三路径收尾补发（D9 口径②）。
 *
 * 现状问题：SSE 连上后 3s 兜底轮询照旧跑 —— 任务级通道**无周期心跳**（§0-7），
 * 正常态是"长时间无帧"，于是"双通道 3s 并行"里那 3s 轮询全是浪费；反过来，
 * 因为长期无帧是正常态，**不能**设"帧新鲜度阈值"（D8），降级只能由 `onerror`/`readyState` 驱动。
 * 另一处静默丢弃：`JOB_DONE` 帧触发的刷新撞上 `inFlight` 时，`syncSnapshot` 直接被守卫吞掉
 * （`run` 返回已 resolve 的 promise ⇒ controller 的 dirty flag 根本没机会置位），
 * 于是"事件说完了"这次刷新**无声消失**（D9：本控制器没有静默丢弃路径）。
 *
 * 本 spec 锁的契约（全部用假时钟 + 受控 promise）：
 * - `realtime` → 30s **保底**（不停表、不清零）；`onerror` 且 `readyState !== OPEN` → 回 3s；
 * - 切换瞬间旧 timer 必清（切换后首个 tick 是一个完整新周期）；
 * - `onopen` 与**首帧**各拉一次权威快照（合计恰好 2 次，非首帧不再拉）；
 * - 任意长无帧**不触发降级/关闭**；
 * - 三路径撞在途守卫都**不丢弃**：收尾补发**恰好 1 次**、多次叠加**合并 1 次**。
 */
import { afterEach, beforeEach, describe, expect, it, vi, type Mock } from 'vitest'

const api = vi.hoisted(() => ({ getJob: vi.fn() }))

vi.mock('@/api/jobs', () => ({ jobsApi: { getJob: api.getJob } }))

import { JOB_FALLBACK_POLL_MS, watchJob } from '@/utils/job-watch'
import type { Job, JobStatus } from '@/types/job'

/** 实时通道下的保底间隔（30s：不停表，只降频 —— 保底 tick 是唯一假死发现面）。 */
const REALTIME_FALLBACK_MS = 30_000

/** 错峰桩默认值：0.5 ⇒ 125ms（与 utils/polling.ts 的 0~250ms 窗口同口径）。 */
const RANDOM_STUB = 0.5
const JITTER_MS = RANDOM_STUB * 250

/** EventSource 自写桩（jsdom 30 无内置；api/tasks.ts:106 内部直接 new，只能挂全局）。 */
class EventSourceStub {
  static instances: EventSourceStub[] = []
  static readonly OPEN = 1
  static readonly CONNECTING = 0
  static readonly CLOSED = 2

  readyState = EventSourceStub.CONNECTING
  closed = false
  onopen: ((ev: Event) => void) | null = null
  onmessage: ((ev: MessageEvent<string>) => void) | null = null
  onerror: ((ev: Event) => void) | null = null

  constructor(readonly url: string) {
    EventSourceStub.instances.push(this)
  }

  close(): void {
    this.closed = true
    this.readyState = EventSourceStub.CLOSED
  }

  emit(type: string, data: unknown = {}): void {
    this.onmessage?.({ data: JSON.stringify({ type, data }) } as MessageEvent<string>)
  }

  fireOpen(): void {
    this.readyState = EventSourceStub.OPEN
    this.onopen?.(new Event('open'))
  }

  fireError(): void {
    this.readyState = EventSourceStub.CONNECTING
    this.onerror?.(new Event('error'))
  }
}

function makeJob(status: JobStatus, id = 101): Job {
  return {
    id,
    taskId: 1,
    jobType: 'IMPORT',
    status,
    progress: 0,
    total: 0,
    errorCount: 0,
    warningCount: 0,
    items: []
  }
}

function deferred<T>(): { promise: Promise<T>; resolve: (value: T) => void } {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((res) => {
    resolve = res
  })
  return { promise, resolve }
}

/** 让挂起的 promise 链全部落地。 */
async function flush(): Promise<void> {
  for (let i = 0; i < 4; i += 1) {
    await vi.advanceTimersByTimeAsync(0)
  }
}

function pollCount(): number {
  return api.getJob.mock.calls.length
}

/** jsdom 的 `document.hidden` 只读（原型 getter）⇒ 覆写为自身访问器属性。 */
function setDocumentHidden(hidden: boolean): void {
  Object.defineProperty(document, 'hidden', { configurable: true, get: () => hidden })
}

function restoreDocumentHidden(): void {
  delete (document as unknown as { hidden?: boolean }).hidden
}

function hide(): void {
  setDocumentHidden(true)
  document.dispatchEvent(new Event('visibilitychange'))
}

function show(): void {
  setDocumentHidden(false)
  document.dispatchEvent(new Event('visibilitychange'))
}

const live: Array<() => void> = []

function lastSource(): EventSourceStub {
  const source = EventSourceStub.instances.at(-1)
  if (!source) {
    throw new Error('EventSource 桩未创建：openTaskEvents 未走真实实现')
  }
  return source
}

/** 起一个跟踪器：桩已就绪（首拉在 watchJob 内同步发出）。 */
function startWatch(): { source: EventSourceStub; onChannel: Mock; stop: () => void } {
  const onChannel = vi.fn()
  const stop = watchJob({ taskId: 1, jobId: 101, onChannel, onSnapshot: vi.fn() })
  live.push(stop)
  return { source: lastSource(), onChannel, stop }
}

beforeEach(() => {
  api.getJob.mockReset()
  api.getJob.mockResolvedValue(makeJob('RUNNING'))
  EventSourceStub.instances = []
  ;(globalThis as unknown as { EventSource: unknown }).EventSource = EventSourceStub
  restoreDocumentHidden()
  vi.useFakeTimers()
  vi.spyOn(Math, 'random').mockReturnValue(RANDOM_STUB)
})

afterEach(() => {
  while (live.length) {
    live.pop()?.()
  }
  vi.restoreAllMocks()
  vi.useRealTimers()
  restoreDocumentHidden()
  delete (globalThis as unknown as { EventSource?: unknown }).EventSource
})

describe('utils/job-watch.ts · 通道降级切换（D6）', () => {
  it('realtime-switch-to-30s：onopen ⇒ realtime 兜底降为 30s 保底（不是清零）：30s 内只多打 1 次', async () => {
    const { source, onChannel } = startWatch()
    await flush()
    expect(pollCount()).toBe(1) // 开局首拉

    source.fireOpen()
    await flush()
    expect(onChannel).toHaveBeenLastCalledWith('realtime')
    const afterOpen = pollCount()
    expect(afterOpen).toBe(2) // onopen 后的权威快照（恰好 1 次）

    await vi.advanceTimersByTimeAsync(REALTIME_FALLBACK_MS - 1)
    expect(pollCount()).toBe(afterOpen) // 若是 3s 节奏，这里早该多出 9 次

    await vi.advanceTimersByTimeAsync(1)
    expect(pollCount()).toBe(afterOpen + 1) // 30s 保底恰好 1 次（不清零 ⇒ 假死仍可发现）
  })

  it('onerror-back-to-3s：onerror 且 readyState !== OPEN ⇒ 回 polling 且恢复 3s 节奏', async () => {
    const { source, onChannel } = startWatch()
    await flush()
    source.fireOpen()
    await flush()
    expect(onChannel).toHaveBeenLastCalledWith('realtime')

    source.fireError() // readyState = CONNECTING（≠ OPEN）
    await flush()
    expect(onChannel).toHaveBeenLastCalledWith('polling')

    const base = pollCount()
    await vi.advanceTimersByTimeAsync(JOB_FALLBACK_POLL_MS - 1)
    expect(pollCount()).toBe(base)
    await vi.advanceTimersByTimeAsync(1)
    expect(pollCount()).toBe(base + 1)
    await vi.advanceTimersByTimeAsync(JOB_FALLBACK_POLL_MS)
    expect(pollCount()).toBe(base + 2) // 3s 节奏恢复
  })

  it('old-timer-cleared-on-switch：3s→30s 切换瞬间旧 timer 清除，切换后首个 tick 距切换为一个完整新周期（不因旧 timer 多打一次）', async () => {
    const { source } = startWatch()
    await flush()

    await vi.advanceTimersByTimeAsync(2000) // 距旧 3s 表的首跳还差 1s
    const beforeSwitch = pollCount()
    source.fireOpen() // t=2000：切到 30s
    await flush()
    const afterSwitch = pollCount() // onopen 权威快照 +1

    await vi.advanceTimersByTimeAsync(1001) // t=3001：旧 timer 若残留，此刻会多打一次
    expect(pollCount()).toBe(afterSwitch)
    expect(beforeSwitch).toBe(afterSwitch - 1)

    await vi.advanceTimersByTimeAsync(REALTIME_FALLBACK_MS - 1001 - 1) // t=31999
    expect(pollCount()).toBe(afterSwitch)
    await vi.advanceTimersByTimeAsync(1) // t=32000 = 切换时刻 + 30s
    expect(pollCount()).toBe(afterSwitch + 1)
  })

  it('authoritative-snapshot-twice：onopen 与首帧后各拉一次权威快照（合计恰好 2 次），非首帧不再拉', async () => {
    const { source } = startWatch()
    await flush()
    const initial = pollCount() // 开局首拉（步1 既有语义）
    expect(initial).toBe(1)

    source.fireOpen()
    await flush()
    expect(pollCount()).toBe(initial + 1) // ① onopen 后

    source.emit('HEARTBEAT', {})
    await flush()
    expect(pollCount()).toBe(initial + 2) // ② 首帧后（合计 2 次，不多不少）

    source.emit('HEARTBEAT', {})
    source.emit('JOB_PROGRESS', { jobId: 101, jobType: 'IMPORT', defCode: 'D', processed: 1, total: 2, pct: 50 })
    await flush()
    expect(pollCount()).toBe(initial + 2) // 非首帧不再拉权威快照
  })

  it('no-frame-freshness-threshold：任意长无帧（远超 30s）不触发降级/关闭，通道保持', async () => {
    const { source, onChannel } = startWatch()
    await flush()
    source.fireOpen()
    await flush()
    const base = pollCount()

    await vi.advanceTimersByTimeAsync(10 * REALTIME_FALLBACK_MS) // 5 分钟无任何帧
    expect(onChannel).toHaveBeenCalledTimes(1) // 只报过一次 realtime
    expect(onChannel).toHaveBeenCalledWith('realtime')
    expect(onChannel).not.toHaveBeenCalledWith('polling')
    expect(source.closed).toBe(false)
    expect(pollCount()).toBe(base + 10) // 30s 保底照常推进（降级不由"无帧"驱动）
  })

  it('same-state-onerror-short-circuit：通道已是 polling 时再收 onerror 不重置 arm 起点（F3：连续 onerror×3 后 3s 兜底仍按原节拍发请求）', async () => {
    const { source } = startWatch()
    await flush()
    expect(pollCount()).toBe(1) // 开局首拉；3s 兜底表武装于 t=0（初始通道态 = polling，无通知事件）

    // 连续 onerror×3：若每次 error 都无条件 emitChannel('polling') 重排 arm 起点，
    // 错误节拍 ≲3s 时兜底 poll 将永远一发不出（断线自愈饿死）。
    source.fireError() // t≈0
    await flush()
    await vi.advanceTimersByTimeAsync(1000)
    source.fireError() // t=1000
    await flush()
    await vi.advanceTimersByTimeAsync(1000)
    source.fireError() // t=2000
    await flush()

    // 起点未被重置：原表（t=0 武装）的 tick 仍在 t=3000 发出
    await vi.advanceTimersByTimeAsync(999)
    expect(pollCount()).toBe(1)
    await vi.advanceTimersByTimeAsync(1)
    expect(pollCount()).toBe(2) // 3s 兜底按原节拍发出（同态短路 ⇒ 起点不被 error 拖动）

    // 第三次 error 之后节奏仍不被拖：下一个 tick 在 t=6000（而非 t=2000+ 某次新起点）
    await vi.advanceTimersByTimeAsync(JOB_FALLBACK_POLL_MS - 1)
    expect(pollCount()).toBe(2)
    await vi.advanceTimersByTimeAsync(1)
    expect(pollCount()).toBe(3)
  })

  it('getjob-called-with-configured-jobid：所有快照拉取都带 watchJob 的 jobId（F7①：不能用 taskId 串作业）', async () => {
    const { source } = startWatch() // taskId=1, jobId=101
    await flush()
    source.fireOpen()
    await flush()
    source.emit('JOB_DONE', { jobId: 101, status: 'COMPLETED' })
    await flush()

    expect(pollCount()).toBeGreaterThanOrEqual(2)
    for (const [jobId] of api.getJob.mock.calls) {
      expect(jobId).toBe(101) // 每次 getJob 都必须用 options.jobId（taskId=1 不得混入）
    }
  })
})

describe('utils/job-watch.ts · 终态释放与 dirty flag 收尾补发（口径②）', () => {
  it('final-disposes-both：onFinal 后 timer 与 source 同时释放，且不再取数', async () => {
    const onFinal = vi.fn()
    const stop = watchJob({ taskId: 1, jobId: 101, onFinal })
    live.push(stop)
    const source = lastSource()
    await flush()
    expect(onFinal).not.toHaveBeenCalled()
    expect(vi.getTimerCount()).toBeGreaterThan(0) // 兜底 timer 在册

    api.getJob.mockResolvedValue(makeJob('COMPLETED'))
    source.emit('JOB_DONE', { jobId: 101, status: 'COMPLETED' })
    await flush()

    expect(onFinal).toHaveBeenCalledTimes(1)
    expect(onFinal.mock.calls[0]?.[0]?.status).toBe('COMPLETED')
    expect(source.closed).toBe(true)
    expect(vi.getTimerCount()).toBe(0) // 兜底 timer 随 source 一起释放

    const after = pollCount()
    await vi.advanceTimersByTimeAsync(5 * REALTIME_FALLBACK_MS)
    expect(pollCount()).toBe(after)
  })

  it('dirty-visible-refreshnow-path：回可见 refreshNow 撞在途 inFlight ⇒ 收尾补发恰好 1 次', async () => {
    const first = deferred<Job>()
    api.getJob.mockReturnValueOnce(first.promise) // 开局首拉挂住（在途）
    const { source } = startWatch()
    expect(pollCount()).toBe(1) // 在途：不并发

    hide()
    show()
    await vi.advanceTimersByTimeAsync(JITTER_MS) // 回可见错峰补刷：撞在途守卫
    await flush()
    expect(pollCount()).toBe(1)

    hide()
    show()
    await vi.advanceTimersByTimeAsync(JITTER_MS) // 第二次补刷叠加：仍不并发
    await flush()
    expect(pollCount()).toBe(1)

    first.resolve(makeJob('RUNNING'))
    await flush()
    expect(pollCount()).toBe(2) // 收尾补发恰好 1 次（两次阻挡合并）

    expect(source.closed).toBe(false)
  })

  it('dirty-jobdone-path：JOB_DONE 帧撞 inFlight ⇒ 收尾补发恰好 1 次（总 getJob 数精确 = 2）', async () => {
    const first = deferred<Job>()
    api.getJob.mockReturnValueOnce(first.promise)
    const { source } = startWatch()
    expect(pollCount()).toBe(1)

    source.emit('HEARTBEAT', {}) // 首帧权威快照：撞在途（不丢弃，等落地）
    await flush()
    expect(pollCount()).toBe(1)

    source.emit('JOB_DONE', { jobId: 101, status: 'COMPLETED' })
    await flush()
    expect(pollCount()).toBe(1)

    source.emit('JOB_DONE', { jobId: 101, status: 'COMPLETED' })
    await flush()
    expect(pollCount()).toBe(1)

    first.resolve(makeJob('RUNNING'))
    await flush()
    expect(pollCount()).toBe(2) // 1 在途 + 收尾补发恰好 1 次（多次叠加合并）
  })
})
