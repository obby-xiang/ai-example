import { defineStore } from 'pinia'
import { api, sseRequest } from '../api'
import { bumpWorkspace } from '../utils/workspace'

/** 导出配置向导状态（用户操作与 AI ui_event 同源写入）。 */
export const useExportStore = defineStore('exportTask', {
  state: () => ({
    step: 1,
    selectedDefs: [],
    conditions: {},
    task: null,
    controller: null
  }),
  actions: {
    selectDefs(codes) {
      this.selectedDefs = [...codes]
      bumpWorkspace()
    },
    setConditions(defCode, conds) {
      this.conditions = { ...this.conditions, [defCode]: conds }
      bumpWorkspace()
    },
    clearConditions(defCode) {
      const next = { ...this.conditions }
      delete next[defCode]
      this.conditions = next
      bumpWorkspace()
    },
    reset() {
      this.step = 1
      this.selectedDefs = []
      this.conditions = {}
      this.task = null
      this.stopSse()
      bumpWorkspace()
    },
    goStep(s) {
      this.step = s
      bumpWorkspace()
    },
    async startExport(conditions) {
      const conds = conditions || this.conditions
      const task = await api.post('/api/export/tasks', {
        defCodes: this.selectedDefs,
        conditions: conds
      })
      this.task = task
      this.step = 3
      bumpWorkspace()
      this.subscribe(task.id)
      return task
    },
    async refresh() {
      if (this.task) this.task = await api.get(`/api/export/tasks/${this.task.id}`)
    },
    subscribe(taskId) {
      this.stopSse()
      this.controller = new AbortController()
      const onEvent = (ev, data) => {
        if (ev === 'snapshot' || ev === 'progress' || ev === 'done') {
          this.task = data
        }
      }
      sseRequest(`/api/export/tasks/${taskId}/events`, {
        signal: this.controller.signal,
        onEvent
      }).catch(() => {
        /* 断开：可手动刷新恢复 */
      }).finally(() => {
        this.controller = null
      })
    },
    stopSse() {
      if (this.controller) {
        this.controller.abort()
        this.controller = null
      }
    }
  }
})
