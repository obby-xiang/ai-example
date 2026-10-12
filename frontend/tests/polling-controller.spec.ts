/**
 * 手动刷新批（S2，M2 + F1）：`utils/polling.ts` 瘦身后的**单节奏可见性纪律控制器**。
 *
 * 本 spec 锁的契约（TDD 口径，先 RED 后 GREEN）：
 * - **单节奏**：只认 `intervalMs` + `run`；**没有**条件自刷的双速语义
 *   （`shouldRun` / `desiredIntervalMs` / `fallbackIntervalMs` 全部随任务中心自刷摘除）；
 * - **没有** `resync`（F2 冷进入升级的消费者已不存在）与 `isPaused`（hidden 停表状态无读取方）；
 * - **可见性纪律保留**：`hidden` 停表（不取消在途 run）、`visible` 先按 0~250ms 随机错峰补刷一次
 *   再重置节奏，**不补偿 hidden 期间错过的 tick**（hidden 期零请求）；
 * - **`refreshNow`**：立即跑一次 + **重置 interval 起点**；撞在途守卫记 dirty flag，
 *   在途结束后**补发恰好 1 次**（多次叠加合并 1 次）；stop() 后不 invoke；
 * - **`setIntervals`**：运行时换节奏（job-watch 通道降级 3s⇄N s 用），切换即重排表、起点重置；
 * - **幂等**：重复 `start()` 不产生第二个 interval、重复 `stop()` 不抛错且卸载监听。
 *
 * 桩件口径：`document.hidden` 在 jsdom 里只读 ⇒ 用 `Object.defineProperty` 覆写自身属性
 * （afterEach 删除自有权以还原本体）；错峰延迟用 `vi.spyOn(Math, 'random')` 可控桩
 * （默认 0.5 ⇒ 125ms，边界见 `catchup-jitter-window`）；时间推进一律 `vi.useFakeTimers()`。
 */
import { afterEach, beforeEach, describe, expect, it, vi, type Mock } from 'vitest'

import { createPollingController, type PollingController } from '@/utils/polling'

/** 错峰桩默认值：0.5 ⇒ 125ms（0~250ms 窗口的中点）。 */
const RANDOM_STUB = 0.5
const JITTER_MS = RANDOM_STUB * 250

/** 基准节奏（generic 单节奏；历史 4s 口径遗留为通用测试节奏）。 */
const BASE_MS = 4000

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
    intervalMs: config.intervalMs ?? BASE_MS,
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

describe('utils/polling.ts · 单节奏可见性纪律控制器（S2 契约）', () => {
  it('contract-slimmed：无 resync / isPaused / shouldRun 双速面（M2 + F1 删除项）', () => {
    const { controller } = makeHarness()
    const face = controller as unknown as Record<string, unknown>
    expect(face.resync).toBeUndefined()
    expect(face.isPaused).toBeUndefined()
    expect('resync' in controller).toBe(false)
    expect('isPaused' in controller).toBe(false)
  })

  it('single-rhythm-tick：单节奏起表，按 intervalMs 周期触发', async () => {
    const { controller, count } = makeHarness()
    started(controller)

    await vi.advanceTimersByTimeAsync(BASE_MS - 1)
    expect(count()).toBe(0)
    await vi.advanceTimersByTimeAsync(1)
    expect(count()).toBe(1)

    await vi.advanceTimersByTimeAsync(BASE_MS)
    expect(count()).toBe(2)
    await vi.advanceTimersByTimeAsync(BASE_MS)
    expect(count()).toBe(3)
  })

  it('hidden-stops-tick：hidden 后推进任意时长，run 0 次调用（hidden 期零请求）', async () => {
    const { controller, count } = makeHarness()
    started(controller)

    await vi.advanceTimersByTimeAsync(BASE_MS)
    expect(count()).toBe(1)

    hide()
    await vi.advanceTimersByTimeAsync(5 * BASE_MS)
    expect(count()).toBe(1)
  })

  it('visible-single-catchup：visible 后恰好补 1 次 run，随后按原周期继续', async () => {
    const { controller, count } = makeHarness()
    started(controller)
    await vi.advanceTimersByTimeAsync(1000)
    hide()
    await vi.advanceTimersByTimeAsync(10 * BASE_MS)
    expect(count()).toBe(0)

    show()
    expect(count()).toBe(0) // 错峰作用于补刷本身：补刷不抢跑在 visible 同步栈里
    await vi.advanceTimersByTimeAsync(JITTER_MS)
    expect(count()).toBe(1) // 恰好 1 次补刷

    await vi.advanceTimersByTimeAsync(BASE_MS)
    expect(count()).toBe(2) // 随后按原周期
  })

  it('no-missed-tick-compensation：hidden 期间推进 N 倍周期，visible 后总调用数 = 1（补刷）+ 后续真实应发数（不补发错过的 tick）', async () => {
    const { controller, count } = makeHarness()
    started(controller)
    await vi.advanceTimersByTimeAsync(BASE_MS)
    expect(count()).toBe(1)

    hide()
    await vi.advanceTimersByTimeAsync(10 * BASE_MS) // hidden 期间错过 10 个 tick
    expect(count()).toBe(1)

    show()
    await vi.advanceTimersByTimeAsync(JITTER_MS)
    expect(count()).toBe(2) // 只有 1 次补刷，错过的 10 个不补偿
    await vi.advanceTimersByTimeAsync(BASE_MS)
    expect(count()).toBe(3)
    await vi.advanceTimersByTimeAsync(BASE_MS)
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

    await vi.advanceTimersByTimeAsync(BASE_MS)
    expect(count()).toBe(1)
    expect(run).toHaveBeenCalledTimes(1)

    let settled = false
    void pending.promise.then(() => {
      settled = true
    })

    hide()
    await vi.advanceTimersByTimeAsync(5 * BASE_MS)
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

    await vi.advanceTimersByTimeAsync(3 * BASE_MS)
    expect(count()).toBe(3) // 起了第二个 interval 就会是 6
    expect(vi.getTimerCount()).toBe(1) // 同刻只有一个表

    controller.stop()
    expect(() => controller.stop()).not.toThrow()
    expect(vi.getTimerCount()).toBe(0)
    await vi.advanceTimersByTimeAsync(60_000)
    expect(count()).toBe(3)

    hide()
    show()
    await vi.advanceTimersByTimeAsync(60_000)
    expect(count()).toBe(3) // 监听器已卸载：停表状态不再翻转
  })

  it('setIntervals-runtime-switch：运行时换节奏（job-watch 通道降级用）；切换即重排表、起点重置，不双发', async () => {
    const { controller, count } = makeHarness({ intervalMs: 3000 })
    started(controller)

    await vi.advanceTimersByTimeAsync(3000)
    expect(count()).toBe(1) // 3s 兜底节奏

    controller.setIntervals({ intervalMs: 60_000 }) // 切慢档（realtime 保底的形态）
    await vi.advanceTimersByTimeAsync(59_999)
    expect(count()).toBe(1) // 旧 3s 表已清，新表未到期
    await vi.advanceTimersByTimeAsync(1)
    expect(count()).toBe(2) // t=63000：切换后恰好一个完整新周期

    controller.setIntervals({ intervalMs: 3000 }) // 切回 3s
    await vi.advanceTimersByTimeAsync(2999)
    expect(count()).toBe(2)
    await vi.advanceTimersByTimeAsync(1)
    expect(count()).toBe(3) // 切换瞬间重排：不双发
  })

  it('paused-refreshnow-only-dirties：hidden 期事件驱动 refreshNow 只置 dirty 不发请求；回 visible 恰好补 1 次（F1）', async () => {
    const { controller, count } = makeHarness()
    started(controller)
    hide()

    // hidden 期间事件帧驱动三次 refreshNow（模拟收帧路径）：一律不得打后端
    controller.refreshNow()
    controller.refreshNow()
    controller.refreshNow()
    await vi.advanceTimersByTimeAsync(10 * BASE_MS)
    expect(count()).toBe(0) // hidden 期收帧 0 请求

    show()
    await vi.advanceTimersByTimeAsync(JITTER_MS)
    expect(count()).toBe(1) // 回 visible 恰好 1 次补刷（dirty 合并，不补发错过的 tick）
    await vi.advanceTimersByTimeAsync(BASE_MS)
    expect(count()).toBe(2) // 随后按当前节奏
  })

  it('stopped-refreshnow-no-invoke：stop() 后 refreshNow 不再 invoke（契约级闸，F1/m7）', async () => {
    const { controller, count } = makeHarness()
    started(controller)
    await vi.advanceTimersByTimeAsync(BASE_MS)
    expect(count()).toBe(1)

    controller.stop()
    controller.refreshNow()
    controller.refreshNow()
    await vi.advanceTimersByTimeAsync(5 * BASE_MS)
    expect(count()).toBe(1) // stop 后 refreshNow 不 invoke、不留待办
  })

  it('dirty-flag-refreshnow-path：run 在途（返回受控 pending promise）时 refreshNow() → 在途 resolve 后补发恰好 1 次（再在途再 refreshNow 多次合并仍为 1 次）', async () => {
    const first = deferred<void>()
    const second = deferred<void>()
    const run = vi.fn<() => void | Promise<void>>()
    run.mockReturnValueOnce(first.promise).mockReturnValueOnce(second.promise).mockReturnValue(undefined)
    const { controller, count } = makeHarness({ run })
    started(controller)

    await vi.advanceTimersByTimeAsync(BASE_MS) // 第 1 发：在途
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
