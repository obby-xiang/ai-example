import { defineStore } from 'pinia'
import { api } from '@/api'

/**
 * 任务 store：当前任务快照（REST 响应与 SSE state 事件共同驱动）。
 * AI 工具触发的变更通过 SSE 广播到这里 → 工作区实时联动（单一事实源在后端）。
 */
export const useTaskStore = defineStore('task', {
  state: () => ({
    task: null,          // 当前任务对象（detail）
    catalog: [],         // 配置项目录缓存
    progress: null       // 进行中操作进度（SSE progress 事件）
  }),
  getters: {
    isExport: (s) => s.task?.type === 'EXPORT_CONFIG',
    isImport: (s) => s.task?.type === 'IMPORT_CONFIG',
    currentStep: (s) => s.task?.currentStep || '',
    selection: (s) => s.task?.params?.selection || [],
    stepIndex() {
      const steps = this.isExport
        ? ['SELECT_CONFIG', 'SET_CONDITION', 'EXECUTE_EXPORT']
        : ['SELECT_CONFIG', 'PREPARE', 'CHECK', 'IMPORT', 'PUBLISH']
      return Math.max(0, steps.indexOf(this.currentStep))
    }
  },
  actions: {
    async loadCatalog() {
      if (!this.catalog.length) {
        this.catalog = await api.listConfigs()
      }
      return this.catalog
    },
    async loadTask(id) {
      this.task = await api.taskDetail(id)
      await api.bindTask(id)
      return this.task
    },
    applyTaskMap(map) {
      // SSE state 事件或 REST 响应统一走这里
      if (this.task && map.id !== this.task.id) return
      this.task = { ...this.task, ...map }
    },
    applyProgress(p) {
      if (this.task && p.taskId === this.task.id) {
        this.progress = p
      }
    },
    clearProgress(taskId) {
      if (!taskId || (this.task && taskId === this.task.id)) {
        this.progress = null
      }
    }
  }
})
