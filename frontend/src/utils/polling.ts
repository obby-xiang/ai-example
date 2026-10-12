/**
 * 轮询治理一期 · 步2：**可见性纪律的统一定时器封装**（单 interval 动态切换）。
 *
 * 为什么要有它：页面开着就一直在打后端的自管定时器散落在三处（任务中心列表自刷、
 * 作业跟踪兜底 poll、AI 面板倒计时节拍），各自 `setInterval` + 各自清理；标签页切走后
 * 浏览器虽会节流后台定时器，但**口径不可依赖**（切回来还会齐发补账）。
 * 这里把"什么时候该发、发多快、看不见时怎么办"收敛成一个可单测的控制器，三处只填 `run`。
 *
 * ## 节奏（单 interval 动态切换）
 * `shouldRun()` 为真按 `intervalMs`、为假按 `fallbackIntervalMs`；**每次 tick 前重新判定**，
 * 节奏变化 = 清旧 interval + 起新 interval（切换瞬间旧 timer 必清）。切换后首个 tick 距切换时刻
 * ≥ `min(intervalMs, fallbackIntervalMs)`，**绝不因切换在同一 tick 双发**（切换那一跳只发 1 次）。
 * 停止默认恒真 ⇒ 单节奏轮询（`intervalMs === fallbackIntervalMs` 即可）。
 *
 * ## 可见性纪律（hidden 停表 / visible 错峰补刷）
 * 内部挂 `document` 的 `visibilitychange`：
 * - `hidden` → **停表**（清 interval；**不取消在途 run 的 promise**，在途请求照常落地）；
 * - `visible` → 先按 **0~250ms 随机错峰延时**补刷一次（**错峰作用于补刷本身**：多轮询点同时苏醒
 *   时请求被 250ms 窗口打散，不出现同一毫秒齐射），补刷落地后再按**当前节奏**重置 interval；
 * - **不补偿 hidden 期间错过的 tick**：后台错过的那些不发，回可见只补 1 次（避免"补账雪崩"）。
 * `isPaused()` 反映的就是 hidden 停表状态（供 UI/判据读取）。
 *
 * ## 两条口径（写死，三路径统一）
 * ① **`refreshNow` 重置 interval 起点**：刷新即重新起表，"补刷 / 事件帧刷新"与已排定的原 tick
 *   不再同刻双发（防"补刷 + 原 tick"双发；R2-2/D7）。
 * ② **dirty flag + 收尾补发**：刷新撞上在途守卫（`run` 返回的 promise 尚未落地）时**不丢弃**，
 *   记 dirty flag；在途结束后**补发恰好 1 次**；在途期间多次叠加**合并为 1 次**。
 *   该语义**统一覆盖三路径**：① SSE 事件帧触发的刷新；② 回可见（visible 补刷 / 手动回可见）触发的
 *   `refreshNow`；③ `JOB_DONE` 触发的一次性刷新（`utils/job-watch.ts`，与兜底 tick 共用 `inFlight`）。
 *   反过来说：本控制器**没有静默丢弃路径** —— 被挡掉的刷新一定会在收尾时补一发。
 *   另有两道闸（F1）：`stop()` 后 `refreshNow` 不再 invoke（控制器已收摊）；hidden 停表期
 *   `refreshNow` 只置 dirty 不发请求（回 visible 的错峰补刷恰好落地 1 次，hidden 期对后端 0 请求）。
 *
 * `setIntervals` 供调用方在运行时切换节奏：步3 已接作业跟踪的通道降级
 * （`utils/job-watch.ts`：SSE `realtime` → 30s 保底、`polling` → 3s）。
 */

/** 回可见补刷的错峰窗口上界（0~250ms 均匀取值，**作用于补刷本身**）。 */
export const CATCHUP_JITTER_MS = 250

export interface PollingControllerOptions {
  /** 条件节奏（`shouldRun()` 为真时）：如任务中心的 4s。 */
  intervalMs: number
  /** 兜底节奏（`shouldRun()` 为假时）：如任务中心的 30s 保底。 */
  fallbackIntervalMs: number
  /** 一次轮询动作；返回 promise 时其落地即"本次结束"（在途守卫与 dirty flag 以它为准）。 */
  run: () => void | Promise<void>
  /** 条件节奏谓词；缺省恒真（单节奏轮询）。每次 tick 前重新判定。 */
  shouldRun?: () => boolean
}

export interface PollingController {
  /** 起表（幂等：重复调用不产生第二个 interval）。开局就 hidden 时直接停表。 */
  start(): void
  /** 停表（幂等：重复调用不抛错），同时卸载 visibilitychange 监听与作废待办。 */
  stop(): void
  /** 立即跑一次（不受 `shouldRun` 限制）+ 重置 interval 起点；撞在途守卫则记 dirty flag 收尾补发。 */
  refreshNow(): void
  /** 是否处于 hidden 停表状态。 */
  isPaused(): boolean
  /** 运行时切换节奏（3s⇄30s）；切换即重排表、起点重置。 */
  setIntervals(next: { intervalMs: number; fallbackIntervalMs: number }): void
  /**
   * 按当前 `shouldRun()` 判定立即重排（F2：冷进入节奏升级）——armed 节奏与判定不一致时
   * 清旧表起新表（起点重置）；一致时无操作（不打乱既有节拍）。load() 写回新 items 后调用。
   */
  resync(): void
}

export function createPollingController(options: PollingControllerOptions): PollingController {
  const shouldRun = options.shouldRun ?? (() => true)

  let intervalMs = options.intervalMs
  let fallbackIntervalMs = options.fallbackIntervalMs

  let timer: ReturnType<typeof setInterval> | null = null
  /** 已武装的节奏（null = 未武装）；与 `desiredIntervalMs()` 不等时才重排表。 */
  let armedMs: number | null = null
  let catchupTimer: ReturnType<typeof setTimeout> | null = null
  let started = false
  let paused = false
  /** run 在途（`run` 返回 promise 时以它为准；同步 `run` 视为立即结束）。 */
  let running = false
  /** dirty flag：被在途守卫挡掉的刷新（口径②：不丢弃、收尾补发恰好 1 次、多次合并 1 次）。 */
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

  /** 当前应发节奏（每次 tick 前重新判定）。 */
  function desiredIntervalMs(): number {
    return shouldRun() ? intervalMs : fallbackIntervalMs
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
   * 跑一次 `run`：在途时改记 dirty flag（口径②），否则发起并在落地时收尾补发。
   *
   * 双闸（F1，红队 M1/m7）：
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
    const want = desiredIntervalMs()
    if (want !== armedMs) {
      // 节奏变化：清旧表、起新表。本跳是旧节奏下的应发一跳，照发一次；新表首跳距此刻为一个完整
      // 新周期（≥ min(intervalMs, fallbackIntervalMs)），故不会因切换在同一 tick 双发。
      arm(want)
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
      arm(desiredIntervalMs())
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
      arm(desiredIntervalMs())
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
      // 口径①：刷新时刻重新起表（起点重置），防"补刷 + 原 tick"同刻双发。
      if (started && !paused) {
        arm(desiredIntervalMs())
      }
      // 立即跑一次，不受 shouldRun 限制；撞在途守卫则由口径② 收尾补发。
      invoke()
    },

    isPaused(): boolean {
      return paused
    },

    setIntervals(next): void {
      intervalMs = next.intervalMs
      fallbackIntervalMs = next.fallbackIntervalMs
      if (started && !paused) {
        arm(desiredIntervalMs()) // 切换即重排表（起点重置，不同 tick 双发）
      }
    },

    resync(): void {
      if (!started || paused) {
        return
      }
      const want = desiredIntervalMs()
      if (want !== armedMs) {
        // F2：armed 节奏落后于 shouldRun 判定（典型 = 冷进入时空列表武装了保底、load 才写回在跑行）
        // ——立即升级/降级重排，不把升级推迟到下一个（最长 30s 的）保底 tick。
        arm(want)
      }
    }
  }
}
