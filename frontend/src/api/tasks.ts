/** 任务域 client（/api/tasks）。 */

import { del, get, getBlob, post, put, upload } from './http'
import type { PageResult } from '@/types/api'
import type { Job, JobType } from '@/types/job'
import type { TaskListQuery, Task, TaskFile, TaskOverview, TaskSummary } from '@/types/task'
import type { TaskEventFrame } from '@/types/sse'

/** 任务列表：type/status/keyword 过滤 + 分页（后端 createdAt DESC, id DESC）。 */
export function listTasks(params: TaskListQuery = {}): Promise<PageResult<TaskSummary>> {
  return get<PageResult<TaskSummary>>('/tasks', { params })
}

/** 任务详情（含 items）。 */
export function getTask(id: number): Promise<Task> {
  return get<Task>(`/tasks/${id}`)
}

/** 任务概览：任务 + 全部作业 + 文件（refreshCurrent 读取当前任务快照用）。 */
export function getTaskOverview(id: number): Promise<TaskOverview> {
  return get<TaskOverview>(`/tasks/${id}/overview`)
}

/** 创建任务（201；默认步骤 EXPORT→SELECT_DEFS / IMPORT→UPLOAD）。 */
export function createTask(type: 'EXPORT' | 'IMPORT', title?: string): Promise<Task> {
  return post<Task>('/tasks', title === undefined ? { type } : { type, title })
}

/** 删除任务（级联清理作业/条目/问题/暂存/文件，S4.3b⑦）。 */
export function deleteTask(id: number): Promise<void> {
  return del<void>(`/tasks/${id}`)
}

/** 选择配置项（清空重选语义）。 */
export function selectDefs(id: number, defCodes: string[]): Promise<Task> {
  return post<Task>(`/tasks/${id}/select-defs`, { defCodes })
}

/** 设置某配置项的查询条件（conditionJson，条目转 READY）。 */
export function setCondition(id: number, defCode: string, condition: unknown): Promise<void> {
  return put<void>(`/tasks/${id}/items/${encodeURIComponent(defCode)}/condition`, { condition })
}

/** 跳转步骤（currentStep 白名单由后端与工具标签共同约束）。 */
export function goToStep(id: number, step: string): Promise<Task> {
  return put<Task>(`/tasks/${id}/step`, { step })
}

/** 设置导入模式（MERGE/REPLACE，非法值后端 400）。 */
export function setImportMode(id: number, mode: 'MERGE' | 'REPLACE'): Promise<Task> {
  return put<Task>(`/tasks/${id}/import-mode`, { mode })
}

/** 任务文件列表。 */
export function getFiles(id: number): Promise<TaskFile[]> {
  return get<TaskFile[]>(`/tasks/${id}/files`)
}

/** 上传文件（.xlsx 或 .zip 批量；文件名需含配置编码）。 */
export function uploadFile(id: number, file: File): Promise<{
  defCode?: string
  fileName?: string
  matchedFiles: Array<{ defCode: string; fileName: string }>
  unmatchedFiles?: string[]
  count: number
}> {
  const form = new FormData()
  form.append('file', file)
  return upload(`/tasks/${id}/files/upload`, form)
}

/** 下载单个配置的文件（xlsx 字节流；EXPORT 优先，回退 UPLOAD）。 */
export function downloadFile(id: number, defCode: string): Promise<Blob> {
  return getBlob(`/tasks/${id}/files/${encodeURIComponent(defCode)}`)
}

/** 直接写回文件字节（在线编辑保存）。 */
export function saveFile(id: number, defCode: string, data: ArrayBuffer): Promise<void> {
  return put<void>(`/tasks/${id}/files/${encodeURIComponent(defCode)}`, data, {
    headers: { 'Content-Type': 'application/octet-stream' }
  })
}

/** 下载全部导出/上传文件（zip）。 */
export function downloadAllFiles(id: number): Promise<Blob> {
  return getBlob(`/tasks/${id}/files/download-all`)
}

/** 下载勾选的文件（单个 xlsx 或 zip）。 */
export function downloadSelected(id: number, codes: string[]): Promise<Blob> {
  return getBlob(`/tasks/${id}/files/download`, { params: { codes } })
}

/** 下载导入模板（单个 xlsx 或 zip）。 */
export function downloadTemplates(id: number, codes?: string[]): Promise<Blob> {
  return getBlob(`/tasks/${id}/files/templates`, codes && codes.length > 0 ? { params: { codes } } : undefined)
}

/**
 * 任务级事件流（GET /api/tasks/{id}/events）。
 *
 * 该通道帧形态与 AI 帧不同（大写 type + 包一层 data），且任务中心的进度口径
 * 按规格走 **4s 轮询**；本函数留给需要事件驱动的场景（如作业终态即时报）。
 */
export function openTaskEvents(taskId: number, onFrame: (frame: TaskEventFrame) => void): EventSource {
  const source = new EventSource(`/api/tasks/${taskId}/events`)
  source.onmessage = (event: MessageEvent<string>) => {
    try {
      onFrame(JSON.parse(event.data) as TaskEventFrame)
    } catch {
      // 非 JSON 帧（心跳注释等）忽略
    }
  }
  return source
}

export const tasksApi = {
  listTasks,
  getTask,
  getTaskOverview,
  createTask,
  deleteTask,
  selectDefs,
  setCondition,
  goToStep,
  setImportMode,
  getFiles,
  uploadFile,
  downloadFile,
  saveFile,
  downloadAllFiles,
  downloadSelected,
  downloadTemplates,
  openTaskEvents
}

/** 便捷类型出口（供 store 使用）。 */
export type { Job, JobType, Task, TaskFile, TaskOverview, TaskSummary }
