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

export async function waitForJobFinal(jobId: number, options: WaitJobOptions = {}): Promise<Job> {
  const intervalMs = options.intervalMs ?? 1000
  const timeoutMs = options.timeoutMs ?? 300_000
  const startedAt = Date.now()
  for (;;) {
    if (options.signal?.aborted) {
      throw new Error('等待作业终态已被取消')
    }
    const job = await getJob(jobId)
    options.onTick?.(job)
    if (isJobFinal(job.status)) {
      return job
    }
    if (Date.now() - startedAt > timeoutMs) {
      return job
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
