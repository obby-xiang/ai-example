/**
 * 作业跟踪器：**SSE 主通道 + 轮询兜底**（规格 §2「SSE 进度通道」，kimi 蓝本的纯轮询弃用）。
 *
 * 通道分工（后端 TaskSseService 实际发布点）：
 * - `JOB_PROGRESS`（作业执行器分片边界发布，行级 processed/total + defCode）
 *   → 直接驱动进度条，不必等下一次快照请求；
 * - `JOB_DONE`（JobTerminalWriter 在数据面事务提交后发布）→ 触发一次权威快照拉取，
 *   快照说终态才算终态（避免"事件领先于库"）；
 * - 兜底轮询：EventSource 断线/代理抖动时业务不能停在假进度上，固定间隔拉快照自愈。
 *
 * 终态判定一律以 `GET /api/jobs/{jobId}` 的快照为准（isJobFinal），
 * 不拿 JOB_DONE 的载荷当终态依据。
 */

import { jobsApi } from '@/api/jobs'
import { tasksApi } from '@/api/tasks'
import { isJobFinal, type Job } from '@/types/job'
import type { JobDonePayload, JobProgressPayload, TaskEventFrame } from '@/types/sse'

/** 兜底轮询间隔（SSE 正常时只是低频自愈，不构成负载）。 */
export const JOB_FALLBACK_POLL_MS = 3000

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
}

/**
 * 开始跟踪一个作业，返回停止函数（页面卸载/切作业时必须调用）。
 */
export function watchJob(options: JobWatchOptions): () => void {
  const pollIntervalMs = options.pollIntervalMs ?? JOB_FALLBACK_POLL_MS
  let disposed = false
  let inFlight = false

  const emitChannel = (state: JobChannelState): void => {
    if (!disposed) {
      options.onChannel?.(state)
    }
  }

  const source = tasksApi.openTaskEvents(options.taskId, (frame: TaskEventFrame) => {
    if (disposed) {
      return
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
        void syncSnapshot()
      }
    }
  })

  source.onopen = () => {
    emitChannel('realtime')
  }
  source.onerror = () => {
    // EventSource 自身会按 retry 间隔重连；这里只把状态标为降级，业务继续靠轮询推进
    if (source.readyState !== EventSource.OPEN) {
      emitChannel('polling')
    }
  }

  async function syncSnapshot(): Promise<void> {
    if (disposed || inFlight) {
      return
    }
    inFlight = true
    try {
      const job = await jobsApi.getJob(options.jobId)
      options.onSnapshot?.(job)
      if (isJobFinal(job.status)) {
        const finalJob = job
        dispose()
        options.onFinal?.(finalJob)
      }
    } catch (error) {
      options.onError?.(error)
    } finally {
      inFlight = false
    }
  }

  const timer = setInterval(() => {
    if (!disposed) {
      void syncSnapshot()
    }
  }, pollIntervalMs)

  function dispose(): void {
    if (disposed) {
      return
    }
    disposed = true
    clearInterval(timer)
    source.close()
  }

  void syncSnapshot()
  return dispose
}
