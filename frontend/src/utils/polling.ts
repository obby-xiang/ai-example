/**
 * 轮询治理 · 手动刷新批（S2，M2 + F1）：**单节奏**可见性纪律定时器封装。
 *
 * 瘦身（本批口径）：任务中心的条件自刷（hasActiveJob 4s / 30s 双速）随 M1 整体摘除后，
 * 控制器的**双速语义全部失去消费者** ⇒ 删除 `shouldRun` 谓词 / `desiredIntervalMs` 变节奏 /
 * `resync`（F2 冷进入升级）/ `isPaused`（hidden 停表状态无读取方）；`CATCHUP_JITTER_MS` 改为
 * 内部常量、不再导出（**不可删**：仍是回可见错峰补刷的唯一取值来源，见 `:26` 定义 / 使用点）。
 *
 * 保留（现存两个消费方依赖）：
 * - **单节奏**：`intervalMs` + `run`，每次到点即跑；
 * - **可见性纪律**：内部挂 `document` 的 `visibilitychange`——
 *   `hidden` → **停表**（清 interval；**不取消在途 run 的 promise**，在途请求照常落地）；
 *   `visible` → 先按 0~250ms 随机错峰延时补刷一次（**错峰作用于补刷本身**：多轮询点同时苏醒
 *   时请求被 250ms 窗口打散，不出现同一毫秒齐射），补刷落地后再重置 interval；
 *   **不补偿 hidden 期间错过的 tick**：后台错过的那些不发，回可见只补 1 次（避免"补账雪崩"）；
 * - `stop/dispose`：幂等，卸载 visibilitychange 监听与作废待办；
 * - `refreshNow`（job-watch 的 SSE 帧 / onopen / JOB_DONE 即时刷新用）：立即跑一次 +
 *   **重置 interval 起点**（防"补刷 + 原 tick"同刻双发）；撞在途守卫记 dirty flag，在途结束后
 *   **补发恰好 1 次**（多次合并 1 次）——被挡掉的刷新没有静默丢弃路径；
 * - `setIntervals`（job-watch 通道降级 3s ⇄ 慢档用）：运行时换节奏，切换即重排表、起点重置。
 *
 * 现存消费方：作业跟踪兜底轮询（`utils/job-watch.ts`）。
 */

/** 回可见补刷的错峰窗口上界（0~250ms 均匀取值，**作用于补刷本身**；内部常量，不再导出）。 */
const CATCHUP_JITTER_MS = 250

export interface PollingControllerOptions {
  /** 轮询间隔（单节奏）。 */
  intervalMs: number
  /** 一次轮询动作；返回 promise 时其落地即"本次结束"（在途守卫与 dirty flag 以它为准）。 */
  run: () => void | Promise<void>
}

export interface PollingController {
  /** 起表（幂等：重复调用不产生第二个 interval）。开局就 hidden 时直接停表。 */
  start(): void
  /** 停表（幂等：重复调用不抛错），同时卸载 visibilitychange 监听与作废待办。 */
  stop(): void
  /** 立即跑一次 + 重置 interval 起点；撞在途守卫则记 dirty flag 收尾补发。 */
  refreshNow(): void
  /** 运行时切换节奏（job-watch 通道降级）；切换即重排表、起点重置。 */
  setIntervals(next: { intervalMs: number }): void
}

export function createPollingController(options: PollingControllerOptions): PollingController {
  let intervalMs = options.intervalMs

  let timer: ReturnType<typeof setInterval> | null = null
  /** 已武装的节奏（null = 未武装）；与 `intervalMs` 不等时才重排表。 */
  let armedMs: number | null = null
  let catchupTimer: ReturnType<typeof setTimeout> | null = null
  let started = false
  let paused = false
  /** run 在途（`run` 返回 promise 时以它为准；同步 `run` 视为立即结束）。 */
  let running = false
  /** dirty flag：被在途守卫挡掉的刷新（不丢弃、收尾补发恰好 1 次、多次合并 1 次）。 */
  let dirty = false
  let listening = false

  const isHidden = (): boolean => typeof document !== 'undefined' && document.hidden === true

  function clearTimer(): void {
    if (timer !== null) {
      clearInterval(timer)
      timer = null
    }
    armedMs = null
  }

  function clearCatchup(): void {
    if (catchupTimer !== null) {
      clearTimeout(catchupTimer)
      catchupTimer = null
    }
  }

  /**
   * 收尾补发：被在途守卫挡掉的刷新在这里落地（恰好 1 次；多次阻挡已由 dirty 布尔合并）。
   * dirty 的清除在 invoke() 真正落地时做：paused 期它保持置位，等回 visible 的补刷落地。
   */
  function flushPending(): void {
    if (!dirty) {
      return
    }
    invoke()
  }

  function settleRun(): void {
    running = false
    flushPending()
  }

  /**
   * 跑一次 `run`：在途时改记 dirty flag，否则发起并在落地时收尾补发。
   *
   * 双闸（F1）：
   * - `!started`（stop() 之后）→ 直接丢弃：控制器已收摊，refreshNow 不得再 invoke；
   * - `paused`（hidden 停表期）→ **只置 dirty 不发请求**：事件驱动刷新不丢（回 visible 时
   *   由错峰补刷恰好落地 1 次），但 hidden 期间对后端 0 请求。
   */
  function invoke(): void {
    if (!started) {
      return
    }
    if (paused) {
      dirty = true
      return
    }
    if (running) {
      dirty = true
      return
    }
    dirty = false // 真正落地的那一次把待办清零：本次执行即补发本身
    running = true
    let result: void | Promise<void>
    try {
      result = options.run()
    } catch {
      // 同步抛错：本次就此结束（异常出口由 `run` 自身承担：store 写 error 字段 / watcher 走 onError），
      // 仍走收尾补发，避免"挡掉的那次刷新"随异常一起丢。
      settleRun()
      return
    }
    const pending = result as Promise<void> | undefined
    if (pending !== undefined && typeof pending.then === 'function') {
      // 两侧都按"本次结束"处理：tick 路径不产生未捕获 rejection（run 的错误由 run 自己的出口承担）。
      pending.then(settleRun, settleRun)
      return
    }
    settleRun()
  }

  /** 按给定节奏起表；清旧表是切换语义的一部分（旧 timer 不清就会在切换瞬间同 tick 双发）。 */
  function arm(periodMs: number): void {
    clearTimer()
    timer = setInterval(onTick, periodMs)
    armedMs = periodMs
  }

  function onTick(): void {
    if (!started || paused) {
      return
    }
    if (intervalMs !== armedMs) {
      // 节奏变化（setIntervals 之后）：清旧表、起新表。本跳是旧节奏下的应发一跳，照发一次；
      // 新表首跳距此刻为一个完整新周期，故不会因切换在同一 tick 双发。
      arm(intervalMs)
    }
    invoke()
  }

  function pause(): void {
    paused = true
    clearTimer()
    // 补刷还没落地就又 hidden：作废（不补偿，也不留着醒来后再打一发旧的）
    clearCatchup()
  }

  function resume(): void {
    paused = false
    clearCatchup()
    // 可见补刷：先按 0~250ms 随机错峰延时（错峰作用于补刷本身），补刷落地后再按当前节奏重置表。
    // hidden 期间错过的 tick 不补偿 —— 只补这 1 次。
    const jitterMs = Math.random() * CATCHUP_JITTER_MS
    catchupTimer = setTimeout(() => {
      catchupTimer = null
      if (!started || paused) {
        return
      }
      invoke()
      arm(intervalMs)
    }, jitterMs)
  }

  function onVisibilityChange(): void {
    if (!started) {
      return
    }
    if (isHidden()) {
      if (!paused) {
        pause()
      }
      return
    }
    if (paused) {
      resume()
    }
  }

  function bindVisibility(): void {
    if (listening || typeof document === 'undefined') {
      return
    }
    document.addEventListener('visibilitychange', onVisibilityChange)
    listening = true
  }

  function unbindVisibility(): void {
    if (!listening || typeof document === 'undefined') {
      return
    }
    document.removeEventListener('visibilitychange', onVisibilityChange)
    listening = false
  }

  return {
    start(): void {
      if (started) {
        return // 幂等：重复 start 不再起第二个表
      }
      started = true
      paused = false
      bindVisibility()
      if (isHidden()) {
        paused = true // 开局就不可见：直接停表，回可见时按错峰补刷一次
        return
      }
      arm(intervalMs)
    },

    stop(): void {
      // 幂等：表/监听器已清则空操作，重复 stop 不抛错
      started = false
      paused = false
      dirty = false // 停表即作废待办：控制器已收摊，补发没有意义
      clearTimer()
      clearCatchup()
      unbindVisibility()
    },

    refreshNow(): void {
      // 刷新时刻重新起表（起点重置），防"补刷 + 原 tick"同刻双发。
      if (started && !paused) {
        arm(intervalMs)
      }
      // 立即跑一次；撞在途守卫则由 dirty flag 收尾补发。
      invoke()
    },

    setIntervals(next): void {
      intervalMs = next.intervalMs
      if (started && !paused) {
        arm(intervalMs) // 切换即重排表（起点重置，不同 tick 双发）
      }
    }
  }
}
