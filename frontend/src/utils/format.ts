/**
 * 展示格式化与中文标签（全中文 UI 的集中来源，DC-06①）。
 */

import dayjs from 'dayjs'
import type { TaskStatus, TaskType } from '@/types/task'
import type { JobType, ProgressView, Severity } from '@/types/job'
import { formatJobProgress } from '@/types/job'

/**
 * 后端时间字符串的**实际**形态：ISO-8601 带纳秒（如 `2026-10-07T01:12:20.597158`）。
 *
 * 核对：docs/evidence/W3-附件-分页与过滤-请求响应原文.json（真实响应原文）。
 * 注意 JacksonConfig 里那个 `@Primary` ObjectMapper 配了 `yyyy-MM-dd HH:mm:ss`，
 * 但 HTTP 消息转换并未走它（实测输出是 ISO）——dayjs 两种都能解析，故展示层不受影响。
 */
export const BACKEND_DATETIME_FORMAT = 'YYYY-MM-DD HH:mm:ss'

/** 解析后端时间字符串（空值/非法值返回 null，避免各处 try/catch）。 */
export function parseBackendTime(value: string | null | undefined): dayjs.Dayjs | null {
  if (!value) {
    return null
  }
  const parsed = dayjs(value)
  return parsed.isValid() ? parsed : null
}

/** 时间展示（列表/详情统一口径）。 */
export function formatDateTime(value: string | null | undefined, fallback = '—'): string {
  const parsed = parseBackendTime(value)
  return parsed ? parsed.format(BACKEND_DATETIME_FORMAT) : fallback
}

/** 相对时间（任务中心"最近更新"列）。 */
export function formatFromNow(value: string | null | undefined, fallback = '—'): string {
  const parsed = parseBackendTime(value)
  if (!parsed) {
    return fallback
  }
  const diffSeconds = dayjs().diff(parsed, 'second')
  if (diffSeconds < 60) {
    return '刚刚'
  }
  if (diffSeconds < 3600) {
    return `${Math.floor(diffSeconds / 60)} 分钟前`
  }
  if (diffSeconds < 86400) {
    return `${Math.floor(diffSeconds / 3600)} 小时前`
  }
  return `${Math.floor(diffSeconds / 86400)} 天前`
}

/** 秒 → 倒计时文案（确认门超时展示）。 */
export function formatCountdown(seconds: number): string {
  const safe = Math.max(0, Math.floor(seconds))
  const minutes = Math.floor(safe / 60)
  const rest = safe % 60
  return minutes > 0 ? `${minutes}:${String(rest).padStart(2, '0')}` : `${rest} 秒`
}

export const TASK_TYPE_LABELS: Record<TaskType, string> = {
  EXPORT: '导出',
  IMPORT: '导入'
}

export const TASK_STATUS_LABELS: Record<TaskStatus, string> = {
  ACTIVE: '进行中',
  COMPLETED: '已完成',
  CANCELLED: '已取消',
  FAILED: '已失败'
}

export const JOB_TYPE_LABELS: Record<JobType, string> = {
  EXPORT: '导出',
  PRECHECK: '预检查',
  IMPORT: '导入',
  PUBLISH: '发布'
}

export const SEVERITY_LABELS: Record<Severity, string> = {
  ERROR: '错误',
  WARNING: '警告',
  INFO: '提示'
}

/** 步骤 key → 中文（与后端 @ToolScope 标签一致）。 */
export const STEP_LABELS: Record<string, string> = {
  SELECT_DEFS: '选择配置项',
  QUERY_COND: '查询条件',
  EXPORT: '导出执行',
  UPLOAD: '上传文件',
  PRECHECK: '预检查',
  IMPORT: '导入暂存',
  PUBLISH: '发布',
  DONE: '完成'
}

/** 步骤 key → 中文（未知 key 原样返回，便于发现后端新增步骤）。 */
export function stepLabel(step: string | null | undefined): string {
  if (!step) {
    return '—'
  }
  return STEP_LABELS[step] ?? step
}

/** 行级进度展示（total 未知 → "处理中"，不显示百分比）。 */
export function progressView(processed: number, total: number): ProgressView {
  return formatJobProgress(processed, total)
}
