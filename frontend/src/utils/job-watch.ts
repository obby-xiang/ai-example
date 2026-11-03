/**
 * 作业跟踪器：**SSE 主通道 + 轮询兜底**（规格 §2「SSE 进度通道」），手动刷新批（M3 + F3）
 * 起由**帧空闲看门狗**承担假死判死。
 *
 * 通道分工（后端 TaskSseService 实际发布点）：
 * - `JOB_PROGRESS`（作业执行器分片边界发布，行级 processed/total + defCode）
 *   → 直接驱动进度条，不必等下一次快照请求；
 * - `JOB_DONE`（JobTerminalWriter 在数据面事务提交后发布）→ 触发一次权威快照拉取，
 *   快照说终态才算终态（避免"事件领先于库"）；
 * - 兜底轮询：通道判死期间业务不能停在假进度上，固定 3s 拉快照自愈（hidden 停表、
 *   visible 错峰补刷，撞在途守卫由 dirty flag 收尾补发，不丢弃）。
 *
 * ## 帧空闲看门狗（本批有意反转一期 D8"无帧是正常态"的口径）
 * 任务级通道**无周期心跳**，SSE 连上后若一直无帧，旧口径靠 30s 保底轮询顺带发现假死
 * （浪费：正常态下 30s 一拍全是空转）。新口径：
 * - SSE 连上（onopen）后**兜底表停表**（realtime 期间零周期快照），改由看门狗盯帧：
 *   连续 `FRAME_IDLE_WATCHDOG_MS`（120s，可注入 —— 测试/E2E 注小值）**无任何 SSE 帧**
 *   ⇒ 判通道死亡 ⇒ 降 3s polling；
 * - **复位源只有两个**：SSE 帧到达、onopen —— **polling 快照落地不复位**（F3：
 *   兜底 tick 是"通道已死"期间的自愈手段，不能反过来给死通道续命）；
 * - 死通道复活：降级后任意新帧到达 ⇒ 回事件驱动（realtime：兜底表停 + 看门狗重新武装
 *   + 拉一次权威快照）；
 * - `onerror` 断线仍是**即时**降级面（不等看门狗）；看门狗只是"连而无帧"的补充判死。
 *
 * 触发源收敛为三类：SSE 帧 / onopen 一发 / 3s 兜底表（realtime 30s 保底已删）。
 *
 * ## realtime 的"停表"= 真停表（F2）
 * 兜底表用控制器的 `suspendTimers/resumeTimers` 停用/恢复，**不持有任何定时器句柄**
 * （旧口径的"无穷大节奏"是实打实 2^31−1 ms 的 interval，且每帧 `refreshNow` 都重排一次）。
 *
 * ## 通道构造同步失败（F1）
 * `openTaskEvents` 内部 `new EventSource(...)` 可**同步**抛出（环境无 `EventSource` 全局 / URL 非法）。
 * 此时**只关闭失败通道、不终止跟踪**：起表在前的 3s 兜底轮询与 `visibilitychange` 监听保留，
 * `disposed` 保持 false，作业进度继续由轮询推进；函数照常返回 dispose（调用方仍可整单收摊）。
 * 收摊不放在构造失败路径上 —— 那会把"SSE 不可用"误判成"跟踪结束"，页面停在最后一次快照。
 * 错误经 `onError` 上报，且**延到微任务**：调用方是在 watchJob 返回后才登记句柄/注册表项，
 * 同步回调会撞上"尚未登记"的窗口（TDZ / 清不到表项；onError 里调 dispose 也会落空）。
 *
 * 终态判定一律以 `GET /api/jobs/{jobId}` 的快照为准（isJobFinal），
 * 不拿 JOB_DONE 的载荷当终态依据。
 */

import { jobsApi } from '@/api/jobs'
import { tasksApi } from '@/api/tasks'
import { isJobFinal, type Job } from '@/types/job'
import type { JobDonePayload, JobProgressPayload, TaskEventFrame } from '@/types/sse'
import { createPollingController } from '@/utils/polling'

/** 兜底轮询间隔（通道判死降级后的 3s 自愈节奏）。 */
export const JOB_FALLBACK_POLL_MS = 3000

/**
 * 帧空闲看门狗阈值（M3）：SSE 连上后连续无帧达此值 ⇒ 判通道死亡 ⇒ 降 3s polling。
 * **可注入**（`JobWatchOptions.frameIdleWatchdogMs`）：测试/E2E 注入小值驱动降级/恢复路径。
 */
export const FRAME_IDLE_WATCHDOG_MS = 120_000

/** SSE 通道状态（供 UI 显示"实时通道已降级"）。 */
export type JobChannelState = 'connecting' | 'realtime' | 'polling'

export interface JobWatchOptions {
  taskId: number
  jobId: number
  /** SSE JOB_PROGRESS 帧（行级进度即时刷新） */
  onProgress?: (payload: JobProgressPayload) => void
  /** 每次取到的作业快照 */
  onSnapshot?: (job: Job) => void
  /** 作业进入终态（快照为准） */
  onFinal?: (job: Job) => void
  /** 通道状态变化（realtime=SSE 事件驱动；polling=降级为轮询） */
  onChannel?: (state: JobChannelState) => void
  /** 兜底轮询间隔（降级后的自愈节奏；缺省 JOB_FALLBACK_POLL_MS = 3s） */
  pollIntervalMs?: number
  /** 帧空闲看门狗阈值（缺省 FRAME_IDLE_WATCHDOG_MS = 120s；测试/E2E 注入小值） */
  frameIdleWatchdogMs?: number
  /** 拉取快照失败时的回调（网络抖动不该打断跟踪） */
  onError?: (error: unknown) => void
  /** 跟踪结束回调（终态自收/外部 dispose 谁先触发都只调一次；供注册表同步注销，F10） */
  onDisposed?: () => void
}

/**
 * 开始跟踪一个作业，返回停止函数（页面卸载/切作业时必须调用）。
 */
export function watchJob(options: JobWatchOptions): () => void {
  const pollIntervalMs = options.pollIntervalMs ?? JOB_FALLBACK_POLL_MS
  const frameIdleWatchdogMs = options.frameIdleWatchdogMs ?? FRAME_IDLE_WATCHDOG_MS
  let disposed = false
  /** 首帧是否已到（"通道确实通了"的判据：首帧后补一次权威快照） */
  let firstFrameSeen = false

  /**
   * 通道状态（M3 新口径）：
   * - `realtime`：SSE 事件驱动 —— **无周期快照**（触发源收敛：帧 / onopen / 兜底表三分）；
   * - `polling`：通道判死/断线 —— 3s 兜底表接管自愈。
   *
   * 实现注：realtime 期间不 stop 控制器（stop 会把 refreshNow 一并关死，帧/onopen 的
   * 即时权威快照就丢了）——改为 **suspendTimers 真停表**（清表、不持有任何定时器句柄），
   * refreshNow/可见性纪律照常工作；降级时 resumeTimers 回 3s 即恢复兜底表。
   * 不再用 2^31−1 级的长延时"模拟停表"：那会让每个 realtime 跟踪实打实持有一个 24.8 天的
   * interval 句柄，并把"停表"的正确性押在引擎对 32 位延时上界的处理上（越界折返即 3s 热循环）。
   *
   * 同态短路（F3）：`onerror` 是事件级触发（EventSource 每次断线都回调），
   * 若每次都对 `emitChannel('polling')` 无条件重排兜底表起点，error 节拍 ≲3s 时
   * 3s 兜底表会被反复"重新武装"而永远一发不出（断线自愈饿死）。已处于该态时只通知、不重排。
   */
  let channelState: JobChannelState = 'polling' // 兜底表开局即 3s 武装 ⇒ 初始通道态 = polling
  const emitChannel = (state: JobChannelState): void => {
    if (disposed) {
      return
    }
    if (state !== channelState) {
      channelState = state
      if (state === 'realtime') {
        poller.suspendTimers() // 事件驱动：真停表（不建 24.8 天 interval）
      } else if (state === 'polling') {
        poller.resumeTimers() // 降级：按兜底节奏（3s）重新武装
      }
    }
    options.onChannel?.(state)
  }

  /**
   * 帧空闲看门狗（M3）：**仅 SSE 帧到达与 onopen 复位**（polling 快照不复位，F3）。
   * 触发 ⇒ 通道判死 ⇒ 降 3s polling；此后新帧到达由收帧路径回 realtime 并重新武装。
   */
  let watchdogTimer: ReturnType<typeof setTimeout> | null = null
  const clearWatchdog = (): void => {
    if (watchdogTimer !== null) {
      clearTimeout(watchdogTimer)
      watchdogTimer = null
    }
  }
  const resetWatchdog = (): void => {
    clearWatchdog()
    watchdogTimer = setTimeout(() => {
      watchdogTimer = null
      emitChannel('polling') // 连续无帧达阈值：通道判死（同态短路 ⇒ 重复触发只通知不重排）
    }, frameIdleWatchdogMs)
  }

  /**
   * 兜底轮询交给可见性纪律控制器（步2 形态，S2 起单节奏）：
   * `hidden` 停表（后台不再空转打后端）、`visible` 错峰补刷一次，
   * 撞在途守卫的刷新由 dirty flag 收尾补发（不丢弃）。
   * realtime 期间本表**停用但不 stop**（`suspendTimers`：不持任何定时器句柄，刷新面照常）；
   * 降级 polling 时由 emitChannel 的 `resumeTimers` 重新武装。
   */
  const poller = createPollingController({
    intervalMs: pollIntervalMs,
    run: () => syncSnapshot()
  })
  poller.start()

  /** 事件流句柄：构造可同步抛错，故不按"必有值"处理（见下方 F1 降级路径）。 */
  let source: EventSource | null = null

  try {
    source = tasksApi.openTaskEvents(
      options.taskId,
      (frame: TaskEventFrame) => {
        if (disposed) {
          return
        }
        resetWatchdog() // 帧 = 通道活着的直接证据（唯一复位源之一）
        const wasPolling = channelState === 'polling'
        if (!firstFrameSeen) {
          // 首帧（不限类型）= "通道确实通了"：与 onopen 各拉一次权威快照（D6）。
          // 走 refreshNow 而非裸拉：撞在途守卫时由 controller 记 dirty flag 收尾补发（口径②，不丢弃）。
          firstFrameSeen = true
          poller.refreshNow()
        } else if (wasPolling) {
          // 死通道复活的第一帧：回事件驱动时拉一次权威快照（之后的帧不再拉，直到下一次 onopen/首帧）
          poller.refreshNow()
        }
        if (wasPolling) {
          emitChannel('realtime') // 新帧到达 ⇒ 回事件驱动（看门狗已在上面重新武装）
        }
        if (frame.type === 'JOB_PROGRESS') {
          const payload = frame.data as JobProgressPayload
          if (payload.jobId === options.jobId) {
            options.onProgress?.(payload)
          }
          return
        }
        if (frame.type === 'JOB_DONE') {
          const payload = frame.data as JobDonePayload
          if (payload.jobId === options.jobId) {
            // JOB_DONE 触发的即时刷新与兜底 tick 走同一条路（口径②：撞在途守卫则收尾补发恰好 1 次）
            poller.refreshNow()
          }
        }
      },
      {
        onerror: () => {
          // EventSource 自身会按 retry 间隔重连；这里只把状态标为降级，业务继续靠轮询推进
          if (source && source.readyState !== EventSource.OPEN) {
            emitChannel('polling')
          }
        }
      }
    )

    source.onopen = () => {
      resetWatchdog() // onopen = 复位源之二：重连后看门狗计时归零
      emitChannel('realtime') // 通道建立 ⇒ 兜底表停表（含 onChannel 通知）
      poller.refreshNow() // onopen 后的权威快照（D6）
    }
  } catch (error) {
    // F1：通道构造同步抛错（环境无 EventSource / URL 非法）⇒ **SSE 不可用，但跟踪继续**。
    // 起表在前的 3s 兜底轮询 + visibilitychange 监听就是此刻可用的进度面，就地收摊会把它
    // 变成"死跟踪"（页面停在最后一次快照）；故 disposed 保持 false，poller 不 stop。
    // 只关闭失败通道：openTaskEvents 若在 new 之后才抛，句柄可能已生成，留下它就是泄漏。
    source?.close()
    source = null // 断开句柄引用（close 后不再需要，避免跟踪期一直持有失败对象）
    // onError 延到微任务：调用方在 watchJob **返回后**才登记句柄/注册表项，同步回调会撞上
    // 尚未登记的窗口（onError 里调 dispose 会落空/TDZ）。disposed 复检保证收摊后不再上报。
    queueMicrotask(() => {
      if (!disposed) {
        options.onError?.(error)
      }
    })
  }

  /**
   * 在途那一发的 promise（口径②的承载）：撞在途守卫时**把在途这一发交回** controller，
   * 而不是返回一个已 resolve 的 promise —— 后者会让 controller 以为"本次已完成"，
   * 那次刷新就无声消失（JOB_DONE 撞在途即此面：事件说完了，刷新却没发生）。
   * 交回在途 promise 后 controller 记 dirty flag，在途结束**补发恰好 1 次**（多次叠加合并 1 次）。
   */
  let inflight: Promise<void> | null = null

  function syncSnapshot(): void | Promise<void> {
    if (disposed) {
      return
    }
    if (inflight) {
      return inflight
    }
    inflight = (async () => {
      try {
        const job = await jobsApi.getJob(options.jobId)
        // 在途期间可能已被回收（同 kind 重跑/卸载/终态自制）：此时快照作废 ——
        // 写回会把**上一个作业**的状态盖到新作业的展示位上，甚至按旧终态弹提示。
        if (disposed) {
          return
        }
        options.onSnapshot?.(job)
        if (isJobFinal(job.status)) {
          const finalJob = job
          dispose()
          options.onFinal?.(finalJob)
        }
      } catch (error) {
        // F8：与 resolve 面的 disposed 复检对称 —— dispose 后迟到失败不许再透传 onError
        // （此时回调已被回收语义作废，透传会把死跟踪的错误弹到新作业的展示位上）。
        if (!disposed) {
          options.onError?.(error)
        }
      } finally {
        inflight = null
      }
    })()
    return inflight
  }

  /** 资源收摊（幂等，不触发 `onDisposed`）：仅正常 dispose 路径进入。 */
  function teardown(): void {
    clearWatchdog() // 看门狗随跟踪一起收摊（F10 口径：终态自收同样要清）
    poller.stop() // 同时清 interval 与卸载 visibilitychange 监听
    source?.close() // 构造期抛错时无句柄；句柄非空但已 close 时重复 close 无副作用
  }

  function dispose(): void {
    if (disposed) {
      return
    }
    disposed = true
    teardown()
    // F10：通知注册表同步注销（终态自收时 dispose 不经注册表入口，靠本回调同步清理；
    // onDisposed 接线方 = 页面轮次收尾处，见 ImportWizardView）
    options.onDisposed?.()
  }

  void syncSnapshot()
  return dispose
}
