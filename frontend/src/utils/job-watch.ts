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
 *   连续 `FRAME_IDLE_WATCHDOG_MS`（90s，可注入 —— 测试/E2E 注小值）**无任何 SSE 帧**
 *   ⇒ 判通道死亡 ⇒ 降 3s polling；
 * - **复位源只有两个**：SSE 帧到达、onopen —— **polling 快照落地不复位**（F3：
 *   兜底 tick 是"通道已死"期间的自愈手段，不能反过来给死通道续命）；
 * - 死通道复活：降级后任意新帧到达 ⇒ 回事件驱动（realtime：兜底表停 + 看门狗重新武装
 *   + 拉一次权威快照）；
 * - `onerror` 断线仍是**即时**降级面（不等看门狗）；看门狗只是"连而无帧"的补充判死。
 *
 * 触发源收敛为三类：SSE 帧 / onopen 一发 / 3s 兜底表（realtime 30s 保底已删）。
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
export const FRAME_IDLE_WATCHDOG_MS = 90_000

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
  /** 帧空闲看门狗阈值（缺省 FRAME_IDLE_WATCHDOG_MS = 90s；测试/E2E 注入小值） */
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
   * 即时权威快照就丢了）——把节奏推到无穷大（等价停表、refreshNow/可见性纪律照常工作），
   * 降级时 setIntervals 回 3s 即恢复兜底表。
   *
   * 同态短路（F3）：`onerror` 是事件级触发（EventSource 每次断线都回调），
   * 若每次都对 `emitChannel('polling')` 无条件重排兜底表起点，error 节拍 ≲3s 时
   * 3s 兜底表会被反复"重新武装"而永远一发不出（断线自愈饿死）。已处于该态时只通知、不重排。
   */
  let armedChannel: JobChannelState = 'polling' // 兜底表开局即 3s 武装 ⇒ 初始通道态 = polling
  /** realtime 期间的"停表"节奏：约 24.8 天的间隔 = 不起周期 tick（refreshNow 不受影响）。
   *  取 2^31-1 是定时器延时的 32 位安全上界：更大的值现代规范（HTML Timers）为**钳制**到可表示
   *  上界而非回绕（早期 fake-timers/旧实现才有溢出回绕表现，注释原表述有误，C2 修正）；反正
   *  帧空闲看门狗（90s）远早于此触发降级，本节奏永不抵达。 */
  const REALTIME_IDLE_MS = 2_147_483_647
  const emitChannel = (state: JobChannelState): void => {
    if (disposed) {
      return
    }
    if (state !== armedChannel) {
      armedChannel = state
      if (state === 'realtime') {
        poller.setIntervals({ intervalMs: REALTIME_IDLE_MS })
      } else if (state === 'polling') {
        poller.setIntervals({ intervalMs: pollIntervalMs })
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
   * realtime 期间本表处于 stop 态；降级 polling 时由 emitChannel 重新 start。
   */
  const poller = createPollingController({
    intervalMs: pollIntervalMs,
    run: () => syncSnapshot()
  })
  poller.start()

  const source = tasksApi.openTaskEvents(
    options.taskId,
    (frame: TaskEventFrame) => {
      if (disposed) {
        return
      }
      resetWatchdog() // 帧 = 通道活着的直接证据（唯一复位源之一）
      const wasPolling = armedChannel === 'polling'
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
        if (source.readyState !== EventSource.OPEN) {
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

  function dispose(): void {
    if (disposed) {
      return
    }
    disposed = true
    clearWatchdog() // 看门狗随跟踪一起收摊（F10 口径：终态自收同样要清）
    poller.stop() // 同时清 interval 与卸载 visibilitychange 监听
    source.close()
    // F10：通知注册表同步注销（终态自收的场景 dispose 不经注册表入口，has()/size() 才不失真）
    options.onDisposed?.()
  }

  void syncSnapshot()
  return dispose
}
