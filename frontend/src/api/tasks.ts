/** 任务域 client（/api/tasks）。 */

import { del, get, getBlob, post, put, upload } from './http'
import type { PageResult } from '@/types/api'
import type { Job, JobType } from '@/types/job'
import type { TaskListQuery, Task, TaskFile, TaskSummary } from '@/types/task'
import type { TaskEventFrame } from '@/types/sse'
import { logCaughtError } from '@/utils/log-caught-error'


/** 任务列表：type/status/keyword 过滤 + 分页（后端 createdAt DESC, id DESC）。 */
export function listTasks(params: TaskListQuery = {}): Promise<PageResult<TaskSummary>> {
  return get<PageResult<TaskSummary>>('/tasks', { params })
}

/** 任务详情（含 items）。 */
export function getTask(id: number): Promise<Task> {
  return get<Task>(`/tasks/${id}`)
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

/**
 * 上传文件（.xlsx 或 .zip 批量）。
 *
 * <p>`defCodes` = 页面此刻的草案选择集，随同一个 multipart 请求携带：后端按这批编码做文件名
 * 匹配，因此上传不需要先 `POST /select-defs`（选择集的提交点是"下一步"，不是上传）。
 * 不传时后端退回"任务已选条目"匹配（旧调用面零变化）。
 */
export function uploadFile(id: number, file: File, defCodes: readonly string[] = []): Promise<{
  defCode?: string
  fileName?: string
  matchedFiles: Array<{ defCode: string; fileName: string }>
  unmatchedFiles?: string[]
  count: number
}> {
  const form = new FormData()
  form.append('file', file)
  for (const code of defCodes) {
    form.append('defCodes', code)
  }
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
 * 任务级事件流的可选挂具（轮询治理步3）：通道降级判据。
 * （F11：`maxDurationMs` 死码已删 —— 全仓零调用；通道寿命由调用方 close 与后端 emitter-timeout 管。）
 */
export interface TaskEventsOptions {
  /** 通道错误回调（`EventSource` 自身按 retry 间隔重连；业务侧据此把通道标为降级） */
  onerror?: (ev: Event) => void
}

export interface TaskEventSubscription {
  readonly readyState: number
  onopen: ((ev: Event) => void) | null
  close(): void
}

interface TaskEventSubscriber {
  onFrame: (frame: TaskEventFrame) => void
  onerror?: (ev: Event) => void
  opened: boolean
  handle: TaskEventSubscription
}

interface SharedTaskEvents {
  source: EventSource
  subscribers: Set<TaskEventSubscriber>
}

const taskEventStreams = new Map<number, SharedTaskEvents>()

/**
 * 任务级事件流（GET /api/tasks/{id}/events）：每文档、每 taskId 一条连接，多 job 订阅分发。
 * 最后一个订阅关闭时才关闭底层 EventSource；各订阅保留独立 onopen/onerror/dispose 语义。
 */
export function openTaskEvents(
  taskId: number,
  onFrame: (frame: TaskEventFrame) => void,
  options: TaskEventsOptions = {}
): TaskEventSubscription {
  let stream = taskEventStreams.get(taskId)
  if (!stream) {
    const source = new EventSource(`/api/tasks/${taskId}/events`)
    const shared: SharedTaskEvents = { source, subscribers: new Set<TaskEventSubscriber>() }
    stream = shared
    taskEventStreams.set(taskId, shared)
    const dispatch = (callback: () => void): void => {
      try {
        callback()
      } catch (error) {
          logCaughtError(error, 'api/tasks.ts:150')
        // 单个订阅者的回调异常不阻断其他订阅者，也不冒充 JSON 解析失败
      }
    }
    source.onmessage = (event: MessageEvent<string>) => {
      let frame: TaskEventFrame
      try {
        frame = JSON.parse(event.data) as TaskEventFrame
      } catch (error) {
          logCaughtError(error, 'api/tasks.ts:158')
        // 非 JSON 帧（心跳注释等）忽略
        return
      }
      for (const subscriber of [...shared.subscribers]) {
        dispatch(() => subscriber.onFrame(frame))
      }
    }
    source.onopen = (ev: Event) => {
      for (const subscriber of [...shared.subscribers]) {
        subscriber.opened = true
        dispatch(() => subscriber.handle.onopen?.(ev))
      }
    }
    source.onerror = (ev: Event) => {
      for (const subscriber of [...shared.subscribers]) {
        dispatch(() => subscriber.onerror?.(ev))
      }
    }
  }
  const activeStream = stream
  const subscriber: TaskEventSubscriber = {
    onFrame,
    onerror: options.onerror,
    opened: false,
    handle: null as unknown as TaskEventSubscription
  }
  let closed = false
  const handle: TaskEventSubscription = {
    get readyState(): number {
      return activeStream.source.readyState
    },
    onopen: null,
    close(): void {
      if (closed) {
        return
      }
      closed = true
      activeStream.subscribers.delete(subscriber)
      if (activeStream.subscribers.size === 0) {
        if (taskEventStreams.get(taskId) === activeStream) {
          taskEventStreams.delete(taskId)
        }
        activeStream.source.onmessage = null
        activeStream.source.onopen = null
        activeStream.source.onerror = null
        activeStream.source.close()
      }
    }
  }
  subscriber.handle = handle
  activeStream.subscribers.add(subscriber)
  if (activeStream.source.readyState === EventSource.OPEN) {
    queueMicrotask(() => {
      if (closed || subscriber.opened || activeStream.source.readyState !== EventSource.OPEN) {
        return
      }
      subscriber.opened = true
      try {
        handle.onopen?.(new Event('open'))
      } catch (error) {
          logCaughtError(error, 'api/tasks.ts:218')
        // 单个订阅者的 OPEN 回调异常不影响共享通道
      }
    })
  }
  return handle
}

export const tasksApi = {
  listTasks,
  getTask,
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
export type { Job, JobType, Task, TaskFile, TaskSummary }
