/**
 * 作业跟踪器：**SSE 主通道 + 轮询兜底**（规格 §2「SSE 进度通道」，kimi 蓝本的纯轮询弃用）。
 *
 * 通道分工（后端 TaskSseService 实际发布点）：
 * - `JOB_PROGRESS`（作业执行器分片边界发布，行级 processed/total + defCode）
 *   → 直接驱动进度条，不必等下一次快照请求；
 * - `JOB_DONE`（JobTerminalWriter 在数据面事务提交后发布）→ 触发一次权威快照拉取，
 *   快照说终态才算终态（避免"事件领先于库"）；
 * - 兜底轮询：EventSource 断线/代理抖动时业务不能停在假进度上，固定间隔拉快照自愈。
 *   步2 起该兜底交给 `utils/polling.ts` 的可见性纪律 controller（hidden 停表、visible 错峰补刷一次），
 *   JOB_DONE 的即时刷新与兜底 tick 走同一条路（撞在途守卫则收尾补发恰好 1 次，不丢弃）。
 *   步3 起**通道状态与兜底节奏绑定**（D6）：`realtime` → 30s 保底（只降频、**不清零**——
 *   本通道无周期心跳，长静默是正常态，停表会失去唯一的假死发现面）；`polling` → 回 3s。
 *   同时 `onopen` 与**首帧**各拉一次权威快照（D6/D8：不设帧新鲜度阈值，降级只由
 *   `onerror`/`readyState` 驱动）。
 *
 * 终态判定一律以 `GET /api/jobs/{jobId}` 的快照为准（isJobFinal），
 * 不拿 JOB_DONE 的载荷当终态依据。
 */

import { jobsApi } from '@/api/jobs'
import { tasksApi } from '@/api/tasks'
import { isJobFinal, type Job } from '@/types/job'
import type { JobDonePayload, JobProgressPayload, TaskEventFrame } from '@/types/sse'
import { createPollingController } from '@/utils/polling'

/** 兜底轮询间隔（SSE 正常时只是低频自愈，不构成负载）。 */
export const JOB_FALLBACK_POLL_MS = 3000

/**
 * 实时通道下的保底轮询间隔（步3，D6）：SSE 已连上时**不停表、只降频**。
 *
 * 为什么不清零：任务级通道**无周期心跳**（application.yml 的 `app.task.sse` 段内无 heartbeat 键），
 * "长时间无帧"是正常态 ⇒ 停表就等于把唯一的假死发现面关掉（R3-3）；30s 保底的成本可忽略。
 */
export const JOB_REALTIME_FALLBACK_POLL_MS = 30_000

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
  /** 通道状态变化（realtime=SSE 已连上；polling=降级为轮询） */
  onChannel?: (state: JobChannelState) => void
  /** 兜底轮询间隔 */
  pollIntervalMs?: number
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
  let disposed = false
  /** 首帧是否已到（"通道确实通了"的判据：首帧后补一次权威快照） */
  let firstFrameSeen = false

  /**
   * 通道状态 = 兜底节奏（步3，D6）。`realtime` → **30s 保底**（只降频、不清零：本通道无周期心跳，
   * 长静默是正常态，停表就失去了唯一的假死发现面）；`polling` → 回 3s 自愈节奏。
   * 降级判定**只**由 `onerror`/`readyState` 驱动，**不设帧新鲜度阈值**（D8）——
   * 任意长度的无帧都不得触发降级/关闭（无帧的正常态不能编码成故障）。
   *
   * 同态短路（F3，红队 M3）：`onerror` 是事件级触发（EventSource 每次断线都回调），
   * 若每次都对 `emitChannel('polling')` 无条件重排 arm 起点，error 节拍 ≲3s 时
   * 3s 兜底表会被反复"重新武装"而永远一发不出（断线自愈饿死）。已处于该态时只通知、不重排。
   */
  let armedChannel: JobChannelState = 'polling' // 兜底表开局即 3s 武装 ⇒ 初始通道态 = polling
  const emitChannel = (state: JobChannelState): void => {
    if (disposed) {
      return
    }
    if (state !== armedChannel) {
      armedChannel = state
      if (state === 'realtime') {
        poller.setIntervals({
          intervalMs: JOB_REALTIME_FALLBACK_POLL_MS,
          fallbackIntervalMs: JOB_REALTIME_FALLBACK_POLL_MS
        })
      } else if (state === 'polling') {
        poller.setIntervals({ intervalMs: pollIntervalMs, fallbackIntervalMs: pollIntervalMs })
      }
    }
    options.onChannel?.(state)
  }

  /**
   * 兜底轮询交给可见性纪律 controller（步2）：`intervalMs === fallbackIntervalMs` ⇒ 单节奏；
   * 步3 起由 `emitChannel` 按通道状态 `setIntervals` 动态切换（realtime 30s ⇄ polling 3s，
   * 切换即重排旧表，不因旧 timer 残留多打一跳）。
   * 走 controller 的另一个收益：`hidden` 停表（后台不再空转打后端）、`visible` 错峰补刷一次，
   * 且撞在途守卫的刷新由 dirty flag 收尾补发（不丢弃）。
   */
  const poller = createPollingController({
    intervalMs: pollIntervalMs,
    fallbackIntervalMs: pollIntervalMs,
    run: () => syncSnapshot()
  })
  poller.start()

  const source = tasksApi.openTaskEvents(
    options.taskId,
    (frame: TaskEventFrame) => {
      if (disposed) {
        return
      }
      if (!firstFrameSeen) {
        // 首帧（不限类型）= "通道确实通了"：与 onopen 各拉一次权威快照（D6）。
        // 走 refreshNow 而非裸拉：撞在途守卫时由 controller 记 dirty flag 收尾补发（口径②，不丢弃）。
        firstFrameSeen = true
        poller.refreshNow()
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
    emitChannel('realtime') // 通道建立 ⇒ 兜底降为 30s 保底（含 onChannel 通知）
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
    poller.stop() // 同时清 interval 与卸载 visibilitychange 监听
    source.close()
    // F10：通知注册表同步注销（终态自收的场景 dispose 不经注册表入口，has()/size() 才不失真）
    options.onDisposed?.()
  }

  void syncSnapshot()
  return dispose
}

/**
 * 作业跟踪回收表：一页可能同时跟踪多个 kind 的作业（导入向导 = check / import / publish）。
 *
 * 为什么必须有：只往一个数组里 push dispose 的话，同 kind 重跑时旧 watcher 仍在轮询旧作业，
 * 它的 onSnapshot / onFinal 会把**上一个作业**的状态写回新作业位（本轮已同时修 syncSnapshot
 * 的 disposed 复检作为第二道闸）；不同 kind 并存则互不回收。
 *
 * 放在这里（而非 .vue 内部）是为了让"同 kind 回收 + 在途快照作废"这条语义可被 tests 直接驱动。
 */
export interface JobWatcherRegistry {
  /** 登记某 kind 的 watcher；同 kind 已有在册者先回收（dispose 一次）并从册中移除。 */
  register(kind: string, dispose: () => void): void
  /** 回收某 kind 的在册 watcher（无则忽略）。 */
  recycle(kind: string): void
  /** 回收全部（页面卸载/改绑）。 */
  disposeAll(): void
  /** 某 kind 是否在册。 */
  has(kind: string): boolean
  /** 在册 watcher 总数。 */
  size(): number
}

export function createJobWatcherRegistry(): JobWatcherRegistry {
  const byKind = new Map<string, () => void>()
  const registered: Array<() => void> = []

  const drop = (kind: string): (() => void) | null => {
    const previous = byKind.get(kind)
    if (!previous) {
      return null
    }
    byKind.delete(kind)
    const index = registered.indexOf(previous)
    if (index >= 0) {
      registered.splice(index, 1)
    }
    return previous
  }

  return {
    register(kind, dispose) {
      drop(kind)?.()
      byKind.set(kind, dispose)
      registered.push(dispose)
    },
    recycle(kind) {
      drop(kind)?.()
    },
    disposeAll() {
      const pending = [...registered]
      registered.length = 0
      byKind.clear()
      pending.forEach((dispose) => dispose())
    },
    has(kind) {
      return byKind.has(kind)
    },
    size() {
      return registered.length
    }
  }
}
