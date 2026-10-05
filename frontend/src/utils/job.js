import { jobApi } from '@/api'

const TERMINAL = ['SUCCESS', 'FAILED', 'CANCELLED']

/**
 * 1s 轮询作业详情直到终态，返回停止函数。
 * onUpdate(job) 每次拿到最新 JobRun 回调（含终态最后一次）。
 */
export function pollJob(jobId, onUpdate, interval = 1000) {
  let stopped = false
  let timer = null
  const tick = async () => {
    if (stopped) return
    try {
      const job = await jobApi.get(jobId)
      onUpdate && onUpdate(job)
      if (job && TERMINAL.includes(job.status)) {
        stopped = true
        return
      }
    } catch (e) { /* 单次轮询失败不中断，下一轮重试 */ }
    if (!stopped) timer = setTimeout(tick, interval)
  }
  tick()
  return () => {
    stopped = true
    if (timer) clearTimeout(timer)
  }
}

export function jobPercent(job) {
  if (!job) return 0
  if (job.status === 'SUCCESS') return 100
  if (!job.total) return 0
  return Math.round((job.processed / job.total) * 100)
}
