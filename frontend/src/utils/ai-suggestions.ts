/**
 * 批 E · E2：每轮完成后的上下文建议（规则模板 + 上下文槽位）。
 *
 * <h2>为什么不让模型生成</h2>
 * 用户裁决 2026-10-22：建议内容用**规则模板 + 当前页面/上下文槽位**（页面类型、当前任务），
 * 不让模型自由生成。理由有三：<b>零额外调用</b>（不为一句话再开一轮）、<b>可预期</b>
 * （同一上下文每次都是同一组建议，用户不会觉得界面在"猜"）、<b>可测</b>（判定是纯函数，
 * 逐组合断言，不必读模型输出）。模型侧若真需要"猜下一步"，那是工具/提示词的职责，不在这里。
 *
 * <h2>口径</h2>
 * <ul>
 * <li>恒 2–3 条、去重（同模板只出现一次）；</li>
 * <li>文案 ≤ {@link MAX_SUGGESTION_CHARS} 字（窄面板一行一条，超了就换行/截断，不再"建议"）；</li>
 * <li>只返回 i18n 键 + 插值参数，文案由调用方按当前语言渲染 —— 本模块零中文硬编码；</li>
 * <li>槽位只用**已有**上下文（`stores/workspace.ts` 的 page / taskType / step / taskId），不新开数据源。</li>
 * </ul>
 *
 * <h2>排序</h2>
 * 当前任务进度优先（用户最可能想知道"我这单到哪了"）；其次按页面/向导给 2–3 条动作。
 * 导出/导入向导按**当前步骤**决定先后：已到执行步 ⇒ 启动在前（下一步就是它）。
 */

/** 单条建议文案的字符上限（中文按字计；窄面板一行一条）。 */
export const MAX_SUGGESTION_CHARS = 16

/** 规则槽位（与 `stores/workspace.ts` 的字段同名同义，便于两侧对照）。 */
export interface SuggestionContext {
  page: string | null
  taskType: 'EXPORT' | 'IMPORT' | null
  step: string | null
  taskId: number | null
}

/** 建议模板 id（= `ai.suggestions.<id>` 的 i18n 键尾；也是稳定 DOM 锚点）。 */
export type SuggestionId =
  | 'taskProgress'
  | 'exportEstimate'
  | 'startExport'
  | 'precheckImport'
  | 'startImport'
  | 'listDefs'
  | 'workspaceState'
  | 'newExportTask'
  | 'dataRowCount'

export interface FollowupSuggestion {
  /** 模板 id（去重键 + `data-suggestion-id`） */
  id: SuggestionId
  /** i18n 键（本模块只给尾段，调用方拼 `ai.suggestions.`） */
  key: SuggestionId
  /** 插值参数（目前只有任务号） */
  params?: Record<string, string | number>
}

/** 导出执行步：下一步就是"启动导出作业"。 */
const EXPORT_RUN_STEPS = new Set(['EXPORT'])

/** 导入的后段步（上传/预检查之后）：启动导入更贴近下一步。 */
const IMPORT_RUN_STEPS = new Set(['IMPORT', 'PUBLISH'])

/**
 * 按上下文给 2–3 条建议。
 *
 * @param context 当前页面/任务槽位（向导页 taskType+step 成对给出；非向导页只有 page）
 */
export function buildFollowups(context: SuggestionContext): FollowupSuggestion[] {
  const out: FollowupSuggestion[] = []
  const push = (id: SuggestionId): void => {
    if (out.length >= 3 || out.some((item) => item.id === id)) {
      return
    }
    out.push(id === 'taskProgress' ? { id, key: id, params: { id: context.taskId ?? 0 } } : { id, key: id })
  }

  if (context.taskId !== null && context.taskId !== undefined) {
    push('taskProgress')
  }

  if (context.taskType === 'EXPORT') {
    const runFirst = context.step !== null && EXPORT_RUN_STEPS.has(context.step)
    for (const id of runFirst ? (['startExport', 'exportEstimate'] as const) : (['exportEstimate', 'startExport'] as const)) {
      push(id)
    }
  } else if (context.taskType === 'IMPORT') {
    const runFirst = context.step !== null && IMPORT_RUN_STEPS.has(context.step)
    for (const id of runFirst ? (['startImport', 'precheckImport'] as const) : (['precheckImport', 'startImport'] as const)) {
      push(id)
    }
  } else if (context.page === 'definitions') {
    push('listDefs')
    push('workspaceState')
  } else if (context.page === 'data') {
    push('dataRowCount')
    push('workspaceState')
  } else if (context.page === 'tasks') {
    push('listDefs')
    push('newExportTask')
    push('workspaceState')
  } else {
    push('workspaceState')
    push('listDefs')
  }

  // 保底 2 条（任何上下文都不出"只有一条"的尴尬布局；已够 2 条时不追加）
  if (out.length < 2) {
    push('workspaceState')
  }
  if (out.length < 2) {
    push('listDefs')
  }
  return out.slice(0, 3)
}
