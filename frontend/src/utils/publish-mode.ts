/**
 * R-09 fix round 1（C-1）：发布模式的**服务端权威解析**。
 *
 * <p>
 * 发布是否删除范围内旧行，由后端作业执行时读取 `Task.settingsJson.importMode` 决定
 * （`PublishJobRunner#resolveImportMode`）。页面里的 `importMode` ref 只是一份副本：
 * 模式切换的 PUT 可能失败、另一标签页/实例可能已改库。因此确认前必须重新读任务，
 * 不能拿本地副本当判据。
 *
 * <p>
 * **fail-closed**：读取任务失败、或 settings 不可解析 ⇒ 返回 `{ok:false}`，调用方不得
 * 创建作业，也不得默认成 REPLACE/MERGE 继续。后端对 `settingsJson` 为空/缺失的语义
 * 明确是 MERGE（`PublishJobRunner#resolveImportMode`），故空值仍解析为 MERGE。
 */
import { tasksApi } from '@/api/tasks'
import type { ImportMode } from '@/types/task'

export type PublishModeResolution =
  | { ok: true; mode: ImportMode }
  | { ok: false; reason: string }

/**
 * 把 `Task.settingsJson` 解析成后端同口径的导入模式（三态中的前两态；不可解析 = fail-closed）。
 *
 * <p>与 `PublishJobRunner#resolveImportMode` 对齐：空值 ⇒ MERGE；`importMode` 忽略大小写
 * 等于 `REPLACE` ⇒ REPLACE；其余（含缺失）⇒ MERGE；JSON 不合法 ⇒ 明确失败。
 */
export function parsePublishMode(settingsJson: string | null | undefined): PublishModeResolution {
  if (settingsJson === null || settingsJson === undefined) {
    return { ok: true, mode: 'MERGE' }
  }
  // API 契约破坏时（数字/对象等）不得 TypeError 直穿到点击路径：明确 fail-closed。
  if (typeof settingsJson !== 'string') {
    return { ok: false, reason: '任务设置格式异常（settingsJson 不是字符串）' }
  }
  if (settingsJson.trim() === '') {
    return { ok: true, mode: 'MERGE' }
  }
  try {
    const parsed = JSON.parse(settingsJson) as { importMode?: unknown }
    const raw = parsed?.importMode
    return {
      ok: true,
      mode: typeof raw === 'string' && raw.toUpperCase() === 'REPLACE' ? 'REPLACE' : 'MERGE'
    }
  } catch {
    return { ok: false, reason: '任务设置无法解析（importMode 不可读）' }
  }
}

/** 读取任务详情并从服务端权威值解析发布模式；读取失败同样 fail-closed。 */
export async function resolvePublishMode(taskId: number): Promise<PublishModeResolution> {
  let task: Awaited<ReturnType<typeof tasksApi.getTask>>
  try {
    task = await tasksApi.getTask(taskId)
  } catch (error) {
    const detail = error instanceof Error ? error.message : String(error)
    return { ok: false, reason: `无法读取任务 #${taskId} 的发布模式（服务端为准）：${detail}` }
  }
  return parsePublishMode(task.settingsJson)
}
