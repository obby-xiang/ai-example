import { defineStore } from 'pinia'

/**
 * 工作区状态 store —— AI context 注入与 get_workspace_state 工具的唯一数据源。
 * 向导页面进入时调用 enterPage()，离开时 reset()；
 * 页面专属动作（启动作业、下载导出文件等）通过 registerHandler 注册。
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
