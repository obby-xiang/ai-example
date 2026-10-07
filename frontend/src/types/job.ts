/**
 * 作业与校验问题契约。
 *
 * 核对源：backend/.../job/entity/Job.java、JobItem.java、ValidationIssue.java
 *         job/controller/JobController.java、job/service/JobService.java、JobProgressService.java
 */

/** 作业类型（Job.JobType）。 */
export type JobType = 'EXPORT' | 'PRECHECK' | 'IMPORT' | 'PUBLISH'

/** 作业状态（Job.JobStatus）。 */
export type JobStatus = 'PENDING' | 'RUNNING' | 'COMPLETED' | 'FAILED' | 'CANCELLED'

/** 作业条目状态：后端为自由字符串，取值 RUNNING/COMPLETED/FAILED/CANCELLED 等。 */
export type JobItemStatus = string

/**
 * 作业条目（每个配置项一条）。
 *
 * 进度口径（JobProgressService 注释）：job_items 承载**配置项级** processed/total，
 * 作业行 jobs 承载**行级** processed/total（字段名 progress/total），两者不混用。
 */
export interface JobItem {
  id?: number
  jobId: number
  defCode: string
  status: JobItemStatus
  processed: number
  total: number
}

/** 作业本体：progress/total 为**行级**口径。 */
export interface Job {
  id?: number
  taskId: number
  jobType: JobType
  status: JobStatus
  /** 已处理行数（行级） */
  progress: number
  /** 已知待处理行数（行级）；total 未知时按 -1 约定展示"处理中"（见 formatJobProgress） */
  total: number
  errorCount: number
  warningCount: number
  /** 结果 JSON 字符串 */
  resultJson?: string | null
  items: JobItem[]
  createdAt?: string
  startedAt?: string | null
  finishedAt?: string | null
}

/** 校验问题严重级（ValidationIssue.Severity）。 */
export type Severity = 'ERROR' | 'WARNING' | 'INFO'

/** 校验问题（预检查/导入/发布逐行问题）。 */
export interface ValidationIssue {
  id?: number
  jobId: number
  defCode: string
  rowKey?: string | null
  fieldCode?: string | null
  severity: Severity
  message: string
  rowIndex?: number | null
}

/** 取消作业的响应（DELETE /api/jobs/{jobId} 的 data）。 */
export interface JobCancelResult {
  jobId: number
  cancelKey: string
  redisWritten: boolean
  wokeInProcess: boolean
}

/** 作业是否处于终态（终态作业取消 → 409 JOB_ALREADY_FINAL）。 */
export function isJobFinal(status: JobStatus): boolean {
  return status === 'COMPLETED' || status === 'FAILED' || status === 'CANCELLED'
}

/** 行级进度展示口径：total 未知（<=0）时不显示百分比。 */
export interface ProgressView {
  /** total 已知时 = processed/total*100，未知时 null */
  percent: number | null
  /** 展示文案：'处理中' 或 '处理中 123/456' */
  label: string
  /** 是否处于"总量未知"形态 */
  unknownTotal: boolean
}

/**
 * 行级进度 → 展示文案。
 *
 * 规格口径：total 未知（后端约定 -1）→ 显示"处理中"，不显示百分比。
 * 实测提醒：当前后端（S4.1~S4.3）未出现 total=-1 的产出点（grep 无命中），
 * 此处为按规格做的容错实现，见证据文档【待裁决】。
 */
export function formatJobProgress(progress: number, total: number): ProgressView {
  if (total <= 0) {
    return { percent: null, label: '处理中', unknownTotal: true }
  }
  const clamped = Math.max(0, Math.min(progress, total))
  const percent = Math.round((clamped / total) * 100)
  return { percent, label: `处理中 ${clamped}/${total}`, unknownTotal: false }
}
