import { defineStore } from 'pinia'

/**
 * ============================================================
 * AI 对话栏 ⇄ 业务工作区之间的【唯一接口层】（窄接口，联动不耦合）
 * ============================================================
 * - AI 侧（AiPanel.vue / stores/ai.js / utils/frontend-tools.js）只允许 import
 *   本模块暴露的公开契约，不得触碰业务视图内部状态；
 * - 业务视图（views/、components/SpreadSheet.vue 等）不得 import ai store，
 *   页面能力一律通过下方 registerPageHandler 反向暴露给 AI 工具；
 * - 因此任一侧可独立移除：移除 AI 栏不影响工作区，移除工作区（如直接打开
 *   /tasks 之外的空页面）AI 栏仍可纯对话 + 后端工具。
 *
 * 公开契约：
 * - 状态（只读语义）：page / taskId / step / selectedDefs / queryConditions / dataVersion
 * - 动作：enterPage() / reset() / setStep() / setSelectedDefs() / setQueryConditions() / bumpVersion()
 * - AI 专用：buildContext()（每次 chat/tool-result 请求注入的 Agent Context）、
 *   snapshot()（get_workspace_state 工具按需拉取的工作区快照）
 * - 页面能力注册表（模块级函数）：registerPageHandler() / unregisterPageHandler() / getPageHandler()
 */
export const useWorkspaceStore = defineStore('workspace', {
  state: () => ({
    page: 'TASKS', // TASKS | EXPORT | IMPORT
    taskId: null,
    step: 1,
    selectedDefs: [],
    queryConditions: {}, // { [defCode]: [{field, op, value, value2?}] }
    dataVersion: 0
  }),
  actions: {
    enterPage({ page, taskId = null, step = 1, selectedDefs = [], queryConditions = {} }) {
      this.page = page
      this.taskId = taskId
      this.step = step
      this.selectedDefs = [...selectedDefs]
      this.queryConditions = { ...queryConditions }
    },
    reset() {
      this.page = 'TASKS'
      this.taskId = null
      this.step = 1
      this.selectedDefs = []
      this.queryConditions = {}
    },
    setStep(step) {
      this.step = step
    },
    setSelectedDefs(codes, mode = 'REPLACE') {
      if (mode === 'ADD') {
        const set = new Set(this.selectedDefs)
        for (const c of codes || []) set.add(c)
        this.selectedDefs = [...set]
      } else {
        this.selectedDefs = [...(codes || [])]
      }
      this.bumpVersion()
    },
    setQueryConditions(defCode, conditions) {
      this.queryConditions = { ...this.queryConditions, [defCode]: conditions || [] }
      this.bumpVersion()
    },
    bumpVersion() {
      this.dataVersion++
    },
    buildContext() {
      return {
        page: this.page,
        step: this.step,
        taskId: this.taskId,
        selectedDefs: [...this.selectedDefs],
        dataVersion: this.dataVersion
      }
    },
    snapshot() {
      return {
        ...this.buildContext(),
        queryConditions: JSON.parse(JSON.stringify(this.queryConditions))
      }
    }
  }
})

// ============ 页面 handler 注册表（非响应式，向导页面注册/注销） ============
const handlers = new Map()

export function registerPageHandler(name, fn) {
  handlers.set(name, fn)
}

export function unregisterPageHandler(name) {
  handlers.delete(name)
}

export function getPageHandler(name) {
  return handlers.get(name) || null
}
