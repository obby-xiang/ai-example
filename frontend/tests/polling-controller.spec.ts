/**
 * 轮询治理一期 · 步2：`utils/polling.ts` 的**可见性纪律控制器**（单 interval 动态切换）。
 *
 * 本 spec 锁的契约（TDD 口径，先 RED 后 GREEN）：
 * - **单 interval 动态切换**：`shouldRun()` 为真按 `intervalMs`、为假按 `fallbackIntervalMs`；
 *   每次 tick 前重新判定，节奏变化 = 清旧表起新表（切换瞬间旧 timer 必清 ⇒ 不同 tick 双发）；
 * - **可见性纪律**：`hidden` 停表（不取消在途 run）、`visible` 先按 0~250ms 随机错峰延时补刷一次
 *   再重置节奏，**不补偿 hidden 期间错过的 tick**；
 * - **`refreshNow`**：绕过谓词立即跑一次 + **重置 interval 起点**；撞在途守卫记 dirty flag，
 *   在途结束后**补发恰好 1 次**（多次叠加合并 1 次）；
 * - **幂等**：重复 `start()` 不产生第二个 interval、重复 `stop()` 不抛错且卸载监听。
 *
 * 桩件口径：`document.hidden` 在 jsdom 里只读 ⇒ 用 `Object.defineProperty` 覆写自身属性
 * （afterEach 删除自有权以还原本体）；错峰延迟用 `vi.spyOn(Math, 'random')` 可控桩
 * （默认 0.5 ⇒ 125ms，边界见 `catchup-jitter-window`）；时间推进一律 `vi.useFakeTimers()`。
 */
import { afterEach, beforeEach, describe, expect, it, vi, type Mock } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'

const api = vi.hoisted(() => ({ listTasks: vi.fn() }))

vi.mock('@/api/tasks', () => ({
  tasksApi: { listTasks: api.listTasks }
}))

import { createPollingController, type PollingController } from '@/utils/polling'
import { useTaskStore } from '@/stores/task'
import type { Job, JobStatus } from '@/types/job'
import type { TaskSummary } from '@/types/task'

/** 错峰桩默认值：0.5 ⇒ 125ms（0~250ms 窗口的中点）。 */
const RANDOM_STUB = 0.5
const JITTER_MS = RANDOM_STUB * 250

/** 条件节奏 / 保底节奏（与 stores/task.ts 的 4s + 30s 同口径）。 */
const CONDITIONAL_MS = 4000
const FALLBACK_MS = 30_000

function deferred<T>(): { promise: Promise<T>; resolve: (value: T) => void } {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((res) => {
    resolve = res
  })
  return { promise, resolve }
}

/** jsdom 的 `document.hidden` 只读（原型 getter）⇒ 覆写为自身访问器属性。 */
function setDocumentHidden(hidden: boolean): void {
  Object.defineProperty(document, 'hidden', { configurable: true, get: () => hidden })
}

function restoreDocumentHidden(): void {
  delete (document as unknown as { hidden?: boolean }).hidden
}

/** 切走 / 切回：listener 是同步的，不需要推进时钟。 */
function hide(): void {
  setDocumentHidden(true)
  document.dispatchEvent(new Event('visibilitychange'))
}

function show(): void {
  setDocumentHidden(false)
  document.dispatchEvent(new Event('visibilitychange'))
}

interface ControllerConfig {
  intervalMs?: number
  fallbackIntervalMs?: number
  shouldRun?: () => boolean
  run?: () => void | Promise<void>
}

/** 建一个带可观测 `run` 的 controller；`count()` 让"恰好 N 次"的断言读起来就是计数。 */
function makeHarness(config: ControllerConfig = {}): {
  controller: PollingController
  run: Mock
  count: () => number
} {
  const run = vi.fn(config.run ?? (() => {}))
  const controller = createPollingController({
    intervalMs: config.intervalMs ?? CONDITIONAL_MS,
    fallbackIntervalMs: config.fallbackIntervalMs ?? FALLBACK_MS,
    shouldRun: config.shouldRun,
    run
  })
  return { controller, run, count: () => run.mock.calls.length }
}

/** 用例收尾统一 stop（避免监听器/表跨用例残留，`vi.getTimerCount()` 才有意义）。 */
const live: PollingController[] = []
function launched(controller: PollingController): PollingController {
  live.push(controller)
  return controller
}

/** start + 登记（用例收尾由 afterEach 统一 stop）。 */
function started(controller: PollingController): void {
  controller.start()
  launched(controller)
}

beforeEach(() => {
  vi.useFakeTimers()
  vi.spyOn(Math, 'random').mockReturnValue(RANDOM_STUB)
  restoreDocumentHidden()
})

afterEach(() => {
  while (live.length) {
    live.pop()?.stop()
  }
  vi.restoreAllMocks()
  vi.useRealTimers()
  restoreDocumentHidden()
})

describe('utils/polling.ts · 可见性纪律控制器', () => {
  it('hidden-stops-tick：hidden 后推进任意时长，run 0 次调用', async () => {
    const { controller, count } = makeHarness()
    started(controller)

    await vi.advanceTimersByTimeAsync(CONDITIONAL_MS)
    expect(count()).toBe(1)

    hide()
    expect(controller.isPaused()).toBe(true)
    await vi.advanceTimersByTimeAsync(5 * CONDITIONAL_MS)
    expect(count()).toBe(1)
  })

  it('visible-single-catchup：visible 后恰好补 1 次 run，随后按原周期继续', async () => {
    const { controller, count } = makeHarness()
    started(controller)
    await vi.advanceTimersByTimeAsync(1000)
    hide()
    await vi.advanceTimersByTimeAsync(10 * CONDITIONAL_MS)
    expect(count()).toBe(0)

    show()
    expect(controller.isPaused()).toBe(false)
    expect(count()).toBe(0) // 错峰作用于补刷本身：补刷不抢跑在 visible 同步栈里
    await vi.advanceTimersByTimeAsync(JITTER_MS)
    expect(count()).toBe(1) // 恰好 1 次补刷

    await vi.advanceTimersByTimeAsync(CONDITIONAL_MS)
    expect(count()).toBe(2) // 随后按原周期
  })

  it('no-missed-tick-compensation：hidden 期间推进 N 倍周期，visible 后总调用数 = 1（补刷）+ 后续真实应发数（不补发错过的 tick）', async () => {
    const { controller, count } = makeHarness()
    started(controller)
    await vi.advanceTimersByTimeAsync(CONDITIONAL_MS)
    expect(count()).toBe(1)

    hide()
    await vi.advanceTimersByTimeAsync(10 * CONDITIONAL_MS) // hidden 期间错过 10 个 tick
    expect(count()).toBe(1)

    show()
    await vi.advanceTimersByTimeAsync(JITTER_MS)
    expect(count()).toBe(2) // 只有 1 次补刷，错过的 10 个不补偿
    await vi.advanceTimersByTimeAsync(CONDITIONAL_MS)
    expect(count()).toBe(3)
    await vi.advanceTimersByTimeAsync(CONDITIONAL_MS)
    expect(count()).toBe(4)
  })

  it('refreshnow-resets-interval：refreshNow() 后 interval 起点重置——紧接着推进不足一个周期的时长不再触发，到下一个完整周期点才触发', async () => {
    const { controller, count } = makeHarness()
    started(controller)

    await vi.advanceTimersByTimeAsync(3000) // t=3000：原表下一跳本在 t=4000
    expect(count()).toBe(0)

    controller.refreshNow() // 立即跑一次 + 起点重置到 t=3000
    expect(count()).toBe(1)

    await vi.advanceTimersByTimeAsync(1000) // t=4000：不重置的话原 tick 会在此刻双发
    expect(count()).toBe(1)
    await vi.advanceTimersByTimeAsync(2999) // t=6999
    expect(count()).toBe(1)
    await vi.advanceTimersByTimeAsync(1) // t=7000 = 刷新时刻 + 一个完整周期
    expect(count()).toBe(2)
  })

  it('catchup-jitter-window：Math.random 桩下 visible 补刷延时落在 0~250ms；同一假时钟口径下"推进 ≤250ms 窗口内观测恰好 1 次 run"', async () => {
    const random = vi.spyOn(Math, 'random').mockReturnValue(0.999) // ⇒ ≈249.75ms（窗口上界侧）
    const upper = makeHarness()
    started(upper.controller)
    hide()
    show()
    expect(random).toHaveBeenCalled()
    expect(upper.count()).toBe(0) // 补刷不在 visible 的同步栈里抢跑
    await vi.advanceTimersByTimeAsync(250) // ≤250ms 窗口
    expect(upper.count()).toBe(1) // 窗口内恰好 1 次
    await vi.advanceTimersByTimeAsync(250)
    expect(upper.count()).toBe(1) // 补刷不重复发

    // 错峰量随 Math.random 线性取值（不是写死 250ms 常量）：0.4 ⇒ 100ms
    random.mockReturnValue(0.4)
    const lower = makeHarness()
    started(lower.controller)
    hide()
    show()
    await vi.advanceTimersByTimeAsync(99)
    expect(lower.count()).toBe(0)
    await vi.advanceTimersByTimeAsync(1)
    expect(lower.count()).toBe(1)
  })

  it('inflight-not-cancelled：hidden 时刻已发出的 run promise 仍可 resolve（run 被调用过且 promise 完成）', async () => {
    const pending = deferred<void>()
    const run = vi.fn(() => pending.promise)
    const harness = makeHarness({ run })
    const { controller, count } = harness
    started(controller)

    await vi.advanceTimersByTimeAsync(CONDITIONAL_MS)
    expect(count()).toBe(1)
    expect(run).toHaveBeenCalledTimes(1)

    let settled = false
    void pending.promise.then(() => {
      settled = true
    })

    hide()
    expect(controller.isPaused()).toBe(true)
    await vi.advanceTimersByTimeAsync(5 * CONDITIONAL_MS)
    expect(count()).toBe(1) // 停表：hidden 期间不再发新的

    pending.resolve()
    await vi.advanceTimersByTimeAsync(0)
    expect(settled).toBe(true) // 在途那一发不被取消，照常 resolve
  })

  it('start-stop-idempotent：重复 start 不产第二个 interval（run 调用频率不变）、重复 stop 不抛错', async () => {
    const { controller, count } = makeHarness()
    started(controller)
    controller.start()
    controller.start()

    await vi.advanceTimersByTimeAsync(3 * CONDITIONAL_MS)
    expect(count()).toBe(3) // 起了第二个 interval 就会是 6
    expect(vi.getTimerCount()).toBe(1) // 同刻只有一个表

    controller.stop()
    expect(() => controller.stop()).not.toThrow()
    expect(vi.getTimerCount()).toBe(0)
    await vi.advanceTimersByTimeAsync(60_000)
    expect(count()).toBe(3)

    hide()
    expect(controller.isPaused()).toBe(false) // 监听器已卸载：停表状态不再翻转
    show()
    await vi.advanceTimersByTimeAsync(60_000)
    expect(count()).toBe(3)
  })

  it('fallback-tick-when-skipped：shouldRun=false 持续 30s（fallbackIntervalMs=30000）→ run 恰好 1 次，且以 30s 为周期', async () => {
    const { controller, count } = makeHarness({ shouldRun: () => false })
    started(controller)

    await vi.advanceTimersByTimeAsync(29_999)
    expect(count()).toBe(0) // 4s 条件节奏不生效
    await vi.advanceTimersByTimeAsync(1)
    expect(count()).toBe(1) // 30s 保底恰好 1 次

    await vi.advanceTimersByTimeAsync(29_999)
    expect(count()).toBe(1)
    await vi.advanceTimersByTimeAsync(1)
    expect(count()).toBe(2) // 以 30s 为周期

    // 步3 预留：运行时改节奏（3s⇄30s）；切换即重排表，起点重置（不双发）
    controller.setIntervals({ intervalMs: 4000, fallbackIntervalMs: 60_000 })
    await vi.advanceTimersByTimeAsync(59_999)
    expect(count()).toBe(2)
    await vi.advanceTimersByTimeAsync(1)
    expect(count()).toBe(3)
  })

  it('fallback-and-conditional-not-same-tick：shouldRun 在 true/false 间切换时，同一 tick 不出现两次 run（切换瞬间旧 timer 清除）', async () => {
    let active = true
    const { controller, count } = makeHarness({ shouldRun: () => active })
    started(controller)

    await vi.advanceTimersByTimeAsync(CONDITIONAL_MS)
    expect(count()).toBe(1) // 4s 条件节奏

    active = false
    await vi.advanceTimersByTimeAsync(CONDITIONAL_MS) // t=8000：旧 4s 表的应发一跳 = 切换点
    expect(count()).toBe(2) // 同 tick 只发 1 次（旧 timer 在切换瞬间已清）
    await vi.advanceTimersByTimeAsync(CONDITIONAL_MS)
    expect(count()).toBe(2) // 切到 30s 后 4s 内不再发
    await vi.advanceTimersByTimeAsync(26_000) // t=38000：保底 tick
    expect(count()).toBe(3)

    active = true
    await vi.advanceTimersByTimeAsync(30_000) // t=68000：保底 tick + 切回 4s（仍只 1 次）
    expect(count()).toBe(4)
    await vi.advanceTimersByTimeAsync(CONDITIONAL_MS) // 4s 节奏恢复
    expect(count()).toBe(5)
    await vi.advanceTimersByTimeAsync(3999)
    expect(count()).toBe(5)
    await vi.advanceTimersByTimeAsync(1)
    expect(count()).toBe(6)
  })

  it('paused-refreshnow-only-dirties：hidden 期事件驱动 refreshNow 只置 dirty 不发请求；回 visible 恰好补 1 次（F1）', async () => {
    const { controller, count } = makeHarness()
    started(controller)
    hide()
    expect(controller.isPaused()).toBe(true)

    // hidden 期间事件帧驱动三次 refreshNow（模拟收帧路径）：一律不得打后端
    controller.refreshNow()
    controller.refreshNow()
    controller.refreshNow()
    await vi.advanceTimersByTimeAsync(10 * CONDITIONAL_MS)
    expect(count()).toBe(0) // hidden 期收帧 0 请求

    show()
    await vi.advanceTimersByTimeAsync(JITTER_MS)
    expect(count()).toBe(1) // 回 visible 恰好 1 次补刷（dirty 合并，不补发错过的 tick）
    await vi.advanceTimersByTimeAsync(CONDITIONAL_MS)
    expect(count()).toBe(2) // 随后按当前节奏
  })

  it('stopped-refreshnow-no-invoke：stop() 后 refreshNow 不再 invoke（契约级闸，F1/m7）', async () => {
    const { controller, count } = makeHarness()
    started(controller)
    await vi.advanceTimersByTimeAsync(CONDITIONAL_MS)
    expect(count()).toBe(1)

    controller.stop()
    controller.refreshNow()
    controller.refreshNow()
    await vi.advanceTimersByTimeAsync(5 * CONDITIONAL_MS)
    expect(count()).toBe(1) // stop 后 refreshNow 不 invoke、不留待办
  })

  it('dirty-flag-refreshnow-path：run 在途（返回受控 pending promise）时 refreshNow() → 在途 resolve 后补发恰好 1 次（再在途再 refreshNow 多次合并仍为 1 次）', async () => {
    const first = deferred<void>()
    const second = deferred<void>()
    const run = vi.fn<() => void | Promise<void>>()
    run.mockReturnValueOnce(first.promise).mockReturnValueOnce(second.promise).mockReturnValue(undefined)
    const { controller, count } = makeHarness({ run })
    started(controller)

    await vi.advanceTimersByTimeAsync(CONDITIONAL_MS) // 第 1 发：在途
    expect(count()).toBe(1)

    controller.refreshNow()
    controller.refreshNow()
    controller.refreshNow()
    await vi.advanceTimersByTimeAsync(0)
    expect(count()).toBe(1) // 撞在途守卫：不并发打后端

    first.resolve()
    await vi.advanceTimersByTimeAsync(0)
    expect(count()).toBe(2) // 收尾补发恰好 1 次（三连合并）；这一发仍在途

    controller.refreshNow() // 撞在第 2 发的在途守卫上
    controller.refreshNow()
    await vi.advanceTimersByTimeAsync(0)
    expect(count()).toBe(2)
    second.resolve()
    await vi.advanceTimersByTimeAsync(0)
    expect(count()).toBe(3) // 再在途再叠加：仍只补 1 次
  })
})

// ---------------------------------------------------------------------------
// 接线回归：stores/task.ts 的 startAutoRefresh 必须收敛为**单 interval 动态切换**
// （步1 的"双 setInterval 并存"过渡形态在步2 被 controller 取代）。
// ---------------------------------------------------------------------------

function makeJob(status: JobStatus, id: number): Job {
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

/** latestJob=null 表示该行尚无作业（后端 TaskSummary 的形态）。 */
function makeSummary(taskId: number, status: JobStatus | null): TaskSummary {
  return {
    task: {
      id: taskId,
      type: 'IMPORT',
      title: `任务 ${taskId}`,
      currentStep: 'UPLOAD',
      status: 'ACTIVE',
      items: []
    },
    itemCount: 0,
    fileCount: 0,
    latestJob: status === null ? null : makeJob(status, taskId * 10)
  }
}

function pageOf(items: TaskSummary[]): { content: TaskSummary[]; totalElements: number } {
  return { content: items, totalElements: items.length }
}

describe('stores/task.ts · startAutoRefresh 单 interval 动态切换（步2 接线）', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    api.listTasks.mockReset()
  })

  afterEach(() => {
    useTaskStore().stopAutoRefresh()
  })

  it('store-autorefresh-cold-entry-upgrade：冷进入空列表武装 30s 保底；load() 写回 RUNNING 后立即升 4s（不拖一个完整保底 tick），且全程无同 tick 双发、stop 后归零（F2）', async () => {
    const store = useTaskStore()
    api.listTasks.mockResolvedValue(pageOf([makeSummary(2, 'RUNNING')]))
    store.items = []
    expect(store.hasActiveJob).toBe(false)

    store.startAutoRefresh()
    store.startAutoRefresh() // 重复 start：不建第二个表（步1 :158-161 的幂等语义保留）
    await vi.advanceTimersByTimeAsync(FALLBACK_MS - 1)
    expect(api.listTasks).toHaveBeenCalledTimes(0) // 4s 条件节奏不生效
    await vi.advanceTimersByTimeAsync(1)
    expect(api.listTasks).toHaveBeenCalledTimes(1) // t=30000：30s 保底恰好 1 次；该次 load 写回 RUNNING
    expect(store.hasActiveJob).toBe(true)

    // F2 升级语义：load() 完成后按新 items 重排 —— 若仍按旧起点拖一个完整 30s，下面 4s 窗口内不会发
    await vi.advanceTimersByTimeAsync(CONDITIONAL_MS - 1)
    expect(api.listTasks).toHaveBeenCalledTimes(1) // 差 1ms 不发
    await vi.advanceTimersByTimeAsync(1)
    expect(api.listTasks).toHaveBeenCalledTimes(2) // t=34000 = 升级重排后恰好一个 4s 周期
    await vi.advanceTimersByTimeAsync(CONDITIONAL_MS)
    expect(api.listTasks).toHaveBeenCalledTimes(3) // 4s 节奏持续
    await vi.advanceTimersByTimeAsync(CONDITIONAL_MS - 1)
    expect(api.listTasks).toHaveBeenCalledTimes(3) // 无同 tick 双发

    store.stopAutoRefresh()
    await vi.advanceTimersByTimeAsync(FALLBACK_MS)
    expect(api.listTasks).toHaveBeenCalledTimes(3) // stop 后不再打后端
  })
})
