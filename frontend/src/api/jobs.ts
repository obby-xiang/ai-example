/** 作业域 client（JobController：/api/tasks/{id}/jobs、/api/jobs/{id}）。 */

import { del, get, post } from './http'
import type { PageResult } from '@/types/api'
import type { ConfigStagingRow } from '@/types/data'
import type { Job, JobCancelResult, JobStatus, JobType, ValidationIssue } from '@/types/job'
import { isJobFinal } from '@/types/job'

/** 创建并启动作业（201；执行在事务提交后异步开始）。 */
export function createJob(taskId: number, jobType: JobType): Promise<Job> {
  return post<Job>(`/tasks/${taskId}/jobs`, { jobType })
}

/** 任务的全部作业（createdAt DESC, id DESC）。 */
export function listJobs(taskId: number): Promise<Job[]> {
  return get<Job[]>(`/tasks/${taskId}/jobs`)
}

/** 单作业快照（行级 progress/total）。 */
export function getJob(jobId: number): Promise<Job> {
  return get<Job>(`/jobs/${jobId}`)
}

/** 取消作业；已终态 → 409 JOB_ALREADY_FINAL（响应体带 code）。 */
export function cancelJob(jobId: number): Promise<JobCancelResult> {
  return del<JobCancelResult>(`/jobs/${jobId}`)
}

/** 校验问题分页（发布冲突的异步形态全靠它给文案）。 */
export function getIssues(jobId: number, page = 0, size = 50): Promise<PageResult<ValidationIssue>> {
  return get<PageResult<ValidationIssue>>(`/jobs/${jobId}/issues`, { params: { page, size } })
}

/** 发布差异（暂存行列表）。 */
export function getDiff(jobId: number): Promise<ConfigStagingRow[]> {
  return get<ConfigStagingRow[]>(`/jobs/${jobId}/diff`)
}

/** 作业终态集合（与 isJobFinal 同源，导出便于 store 判定）。 */
export const JOB_FINAL_STATUSES: readonly JobStatus[] = ['COMPLETED', 'FAILED', 'CANCELLED']

/** 等待作业进入终态（轮询快照；发布冲突必须先等 FAILED 再查 issues，Q4）。 */
export interface WaitJobOptions {
  intervalMs?: number
  timeoutMs?: number
  signal?: AbortSignal
  onTick?: (job: Job) => void
}

/**
 * 等待结局：**终态到达**与**超时兜底**必须可判别。
 *
 * 原先超时走 `return job` —— 返回的是非终态 Job，调用方读到的"结局"和真终态无从区分，
 * 于是「等超时」被当成「拿到终态」，静默地把发布冲突态清成 null。
 */
export type WaitJobFinalOutcome =
  | { status: 'final'; job: Job }
  | { status: 'timeout'; job: Job }

/**
 * 等待被 signal 取消（文案与原先一致，仅补类名以便调用方按类型收口，不必猜文案）。
 *
 * 消费者是 stores/task.ts 的 watchPublishOutcome，其调用点（stores/workspace.ts 的 void 调用）
 * 不接 rejection，只能靠类型判别把这个错误收成一次提示。
 */
export class JobWaitAbortedError extends Error {
  constructor(message = '等待作业终态已被取消') {
    super(message)
    this.name = 'JobWaitAbortedError'
  }
}

export async function waitForJobFinal(jobId: number, options: WaitJobOptions = {}): Promise<WaitJobFinalOutcome> {
  const intervalMs = options.intervalMs ?? 1000
  const timeoutMs = options.timeoutMs ?? 300_000
  const startedAt = Date.now()
  for (;;) {
    if (options.signal?.aborted) {
      throw new JobWaitAbortedError()
    }
    const job = await getJob(jobId)
    options.onTick?.(job)
    if (isJobFinal(job.status)) {
      return { status: 'final', job }
    }
    if (Date.now() - startedAt > timeoutMs) {
      // 超时不是终态：显式标注，交由调用方决定"提示 + 保留现场"还是继续等
      return { status: 'timeout', job }
    }
    await new Promise<void>((resolve) => setTimeout(resolve, intervalMs))
  }
}

export const jobsApi = {
  createJob,
  listJobs,
  getJob,
  cancelJob,
  getIssues,
  getDiff,
  waitForJobFinal
}
