/**
 * 手动刷新批（S3，M3 + F3）：`utils/job-watch.ts` **帧空闲看门狗** + 触发源收敛。
 *
 * 口径反转（有意为之，替代一期 D8"无帧是正常态"）：
 * - **删除** realtime 30s 保底（`JOB_REALTIME_FALLBACK_POLL_MS`）：SSE 连上后兜底表**停表**，
 *   不再有任何低频周期快照（触发源收敛为三类：SSE 帧 / onopen 一发 / 3s 兜底表）；
 * - **新增帧空闲看门狗** `FRAME_IDLE_WATCHDOG_MS = 90_000`（可注入，测试/E2E 注小值）：
 *   连续 90s 无 SSE 帧 ⇒ 判通道死亡 ⇒ 降 3s polling；此后任意新帧到达 ⇒ 回事件驱动
 *   （realtime：兜底表停 + 重新武装看门狗）；
 * - **复位源只有两个**：SSE 帧到达与 onopen —— **polling 快照落地不复位**（F3：
 *   兜底 tick 是"通道已死"期间的自愈手段，不能反过来给死通道续命）；
 * - 其余保留：首帧/onopen 各拉一次权威快照、JOB_PROGRESS 直驱进度、JOB_DONE 走 refreshNow
 *   （撞在途守卫由 dirty flag 收尾补发，不丢弃）、同态短路、inflight 守卫、终态自收。
 *
 * 全部用假时钟 + 受控 promise；EventSource 走全局桩（jsdom 无内置）。
 */
import { afterEach, beforeEach, describe, expect, it, vi, type Mock } from 'vitest'

const api = vi.hoisted(() => ({ getJob: vi.fn() }))

vi.mock('@/api/jobs', () => ({ jobsApi: { getJob: api.getJob } }))

import { FRAME_IDLE_WATCHDOG_MS, JOB_FALLBACK_POLL_MS, watchJob } from '@/utils/job-watch'
import type { Job, JobStatus } from '@/types/job'

/** 兜底轮询间隔（3s：看门狗降级后的自愈节奏）。 */
const POLL_MS = JOB_FALLBACK_POLL_MS // 3000

/** 看门狗测试阈值：注入小值（默认 90s  production 常量另行断言）。 */
const WATCHDOG_MS = 10_000

/** 错峰桩默认值：0.5 ⇒ 125ms（与 utils/polling.ts 的 0~250ms 窗口同口径）。 */
const RANDOM_STUB = 0.5
const JITTER_MS = RANDOM_STUB * 250

/** EventSource 自写桩（jsdom 无内置；api/tasks.ts 内部直接 new，只能挂全局）。 */
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

/** 起一个跟踪器：桩已就绪（首拉在 watchJob 内同步发出）；默认注入小阈值看门狗。 */
function startWatch(overrides: { watchdogMs?: number } = {}): {
  source: EventSourceStub
  onChannel: Mock
  stop: () => void
} {
  const onChannel = vi.fn()
  const stop = watchJob({
    taskId: 1,
    jobId: 101,
    onChannel,
    onSnapshot: vi.fn(),
    frameIdleWatchdogMs: overrides.watchdogMs ?? WATCHDOG_MS
  })
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

describe('utils/job-watch.ts · 帧空闲看门狗（M3：90s 无帧即判通道死亡降级）', () => {
  it('watchdog-default-90s：生产缺省阈值 = 90_000（可注入面之外的真实常量）', () => {
    expect(FRAME_IDLE_WATCHDOG_MS).toBe(90_000)
    expect(JOB_FALLBACK_POLL_MS).toBe(3000)
  })

  it('realtime-no-base-poll：onopen ⇒ realtime 兜底表停表 —— 远超旧 30s 保底的时长内 0 次快照（只要未触看门狗）', async () => {
    const { source, onChannel } = startWatch({ watchdogMs: 60_000 })
    await flush()
    expect(pollCount()).toBe(1) // 开局首拉

    source.fireOpen()
    await flush()
    expect(onChannel).toHaveBeenLastCalledWith('realtime')
    expect(pollCount()).toBe(2) // onopen 权威快照（恰好 1 次）

    await vi.advanceTimersByTimeAsync(45_000) // 远超旧 30s 保底周期
    expect(pollCount()).toBe(2) // 无 30s 保底：realtime 期间零周期快照
    expect(onChannel).toHaveBeenCalledTimes(1)
  })

  it('watchdog-degrades-to-polling：realtime 连续无帧达阈值 ⇒ 判死降 3s polling（onChannel 通知 + 兜底 tick 恢复）', async () => {
    const { source, onChannel } = startWatch()
    await flush()
    source.fireOpen()
    await flush()
    expect(onChannel).toHaveBeenLastCalledWith('realtime')

    await vi.advanceTimersByTimeAsync(WATCHDOG_MS - 1)
    expect(onChannel).toHaveBeenCalledTimes(1) // 阈值内不降级
    await vi.advanceTimersByTimeAsync(1)
    expect(onChannel).toHaveBeenLastCalledWith('polling') // 看门狗触发：通道判死

    const base = pollCount()
    await vi.advanceTimersByTimeAsync(POLL_MS - 1)
    expect(pollCount()).toBe(base)
    await vi.advanceTimersByTimeAsync(1)
    expect(pollCount()).toBe(base + 1) // 3s 兜底恢复
    await vi.advanceTimersByTimeAsync(POLL_MS)
    expect(pollCount()).toBe(base + 2)
    expect(source.closed).toBe(false) // 通道仍在（EventSource 自身重连语义不变）
  })

  it('watchdog-not-reset-by-polling-snapshot（F3）：降级后的 3s 兜底快照落地不复位看门狗 —— 无新帧则保持 polling，不自回升', async () => {
    const { source, onChannel } = startWatch()
    await flush()
    source.fireOpen()
    await flush()
    source.emit('HEARTBEAT', {}) // 帧 ⇒ 复位看门狗（此时 realtime）
    await flush()

    await vi.advanceTimersByTimeAsync(WATCHDOG_MS)
    expect(onChannel).toHaveBeenLastCalledWith('polling') // 判死降级

    const callsAtDegrade = onChannel.mock.calls.length
    await vi.advanceTimersByTimeAsync(5 * POLL_MS) // 兜底 tick 快照不断落地
    expect(onChannel.mock.calls.length).toBe(callsAtDegrade) // 快照不复位 ⇒ 无 realtime 回升
    expect(onChannel).toHaveBeenLastCalledWith('polling')
    expect(pollCount()).toBeGreaterThan(2) // 兜底表确在跑
    expect(source.closed).toBe(false)
  })

  it('frame-restores-realtime：降级后任意新 SSE 帧到达 ⇒ 回事件驱动（realtime 通知 + 权威快照 1 次 + 看门狗重新武装）', async () => {
    const { source, onChannel } = startWatch()
    await flush()
    source.fireOpen()
    await flush()

    await vi.advanceTimersByTimeAsync(WATCHDOG_MS)
    expect(onChannel).toHaveBeenLastCalledWith('polling')

    const base = pollCount()
    source.emit('HEARTBEAT', {}) // 死通道复活的第一帧
    await flush()
    expect(onChannel).toHaveBeenLastCalledWith('realtime')
    expect(pollCount()).toBe(base + 1) // 回事件驱动时拉一次权威快照

    // 看门狗重新武装：再次无帧达阈值 ⇒ 二次降级
    await vi.advanceTimersByTimeAsync(WATCHDOG_MS)
    expect(onChannel).toHaveBeenLastCalledWith('polling')
    await vi.advanceTimersByTimeAsync(POLL_MS)
    expect(pollCount()).toBeGreaterThan(base + 1) // 二次降级后兜底恢复
  })

  it('onopen-resets-watchdog：onopen 复位看门狗（重连后计时重新开始，不在旧计时上累积触发）', async () => {
    const { source, onChannel } = startWatch()
    await flush()
    source.fireOpen()
    await flush()

    await vi.advanceTimersByTimeAsync(WATCHDOG_MS - 1000) // 距触发还差 1s
    source.fireOpen() // 重连 onopen：计时归零
    await flush()
    await vi.advanceTimersByTimeAsync(WATCHDOG_MS - 1000)
    expect(onChannel).not.toHaveBeenCalledWith('polling') // 旧计时应触发而未触发 ⇒ 确被复位
    expect(onChannel).toHaveBeenLastCalledWith('realtime')

    await vi.advanceTimersByTimeAsync(1000)
    expect(onChannel).toHaveBeenLastCalledWith('polling') // 新计时到点照常降级
  })

  it('frame-during-polling-resets-then-restores：polling 期间的帧先复位看门狗再回升 —— 帧本身就是复位源', async () => {
    const { source, onChannel } = startWatch()
    await flush()
    source.fireOpen()
    await flush()
    await vi.advanceTimersByTimeAsync(WATCHDOG_MS)
    expect(onChannel).toHaveBeenLastCalledWith('polling')

    source.emit('HEARTBEAT', {})
    await flush()
    expect(onChannel).toHaveBeenLastCalledWith('realtime')
    // 帧复位 ⇒ 此看门狗周期内不再触发；polling 快照（若有）不得续命 —— 已由 F3 用例锁定
  })
})

describe('utils/job-watch.ts · 触发源收敛与快照纪律（保留面）', () => {
  it('authoritative-snapshot-twice：onopen 与首帧各拉一次权威快照（合计恰好 2 次），非首帧不再拉', async () => {
    const { source } = startWatch()
    await flush()
    const initial = pollCount() // 开局首拉
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

  it('onerror-back-to-3s：onerror 且 readyState !== OPEN ⇒ 立即回 polling（不等看门狗），3s 兜底接管', async () => {
    const { source, onChannel } = startWatch()
    await flush()
    source.fireOpen()
    await flush()
    expect(onChannel).toHaveBeenLastCalledWith('realtime')

    source.fireError() // 断线事件：即时降级（看门狗是"连而无帧"的补充判死面）
    await flush()
    expect(onChannel).toHaveBeenLastCalledWith('polling')

    const base = pollCount()
    await vi.advanceTimersByTimeAsync(POLL_MS - 1)
    expect(pollCount()).toBe(base)
    await vi.advanceTimersByTimeAsync(1)
    expect(pollCount()).toBe(base + 1)
    await vi.advanceTimersByTimeAsync(POLL_MS)
    expect(pollCount()).toBe(base + 2) // 3s 兜底接管
  })

  it('same-state-onerror-short-circuit：通道已是 polling 时再收 onerror 不重置兜底表起点（F3：连续 onerror×3 后 3s 兜底仍按原节拍发）', async () => {
    const { source } = startWatch()
    await flush()
    expect(pollCount()).toBe(1) // 开局首拉；3s 兜底表武装于 t=0（初始通道态 = polling，无通知事件）

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
    expect(pollCount()).toBe(2)

    // 第三次 error 之后节奏仍不被拖：下一个 tick 在 t=6000
    await vi.advanceTimersByTimeAsync(POLL_MS - 1)
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

describe('utils/job-watch.ts · 终态释放与 dirty flag 收尾补发（口径②，保留面）', () => {
  it('final-disposes-all：onFinal 后 timer / source / 看门狗同时释放，且不再取数', async () => {
    const onFinal = vi.fn()
    const stop = watchJob({ taskId: 1, jobId: 101, onFinal, frameIdleWatchdogMs: WATCHDOG_MS })
    live.push(stop)
    const source = lastSource()
    await flush()
    expect(onFinal).not.toHaveBeenCalled()
    expect(vi.getTimerCount()).toBeGreaterThan(0) // 兜底 timer + 看门狗在册

    source.fireOpen()
    await flush()
    expect(vi.getTimerCount()).toBeGreaterThan(0)

    api.getJob.mockResolvedValue(makeJob('COMPLETED'))
    source.emit('JOB_DONE', { jobId: 101, status: 'COMPLETED' })
    await flush()

    expect(onFinal).toHaveBeenCalledTimes(1)
    expect(onFinal.mock.calls[0]?.[0]?.status).toBe('COMPLETED')
    expect(source.closed).toBe(true)
    expect(vi.getTimerCount()).toBe(0) // 兜底 timer 与看门狗随 source 一起释放

    const after = pollCount()
    await vi.advanceTimersByTimeAsync(5 * WATCHDOG_MS)
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
