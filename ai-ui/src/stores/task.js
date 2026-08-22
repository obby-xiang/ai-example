import { defineStore } from 'pinia'
import { taskApi } from '@/api/task'

export const useTaskStore = defineStore('task', {
  state: () => ({
    tasks: [],
    currentTask: null,
    currentTaskId: null,
    scenarioMeta: { scenarioName: {}, scenarioSteps: {} },
    loading: false
  }),
  getters: {
    scenario: (s) => s.currentTask?.scenario || null,
    scenarioName: (s) => s.scenarioMeta.scenarioName?.[s.currentTask?.scenario] || '',
    currentStep: (s) => s.currentTask?.currentStep || '',
    steps: (s) => s.scenarioMeta.scenarioSteps?.[s.currentTask?.scenario] || [],
    selectedDefIds: (s) => {
      if (!s.currentTask?.selectedDefIds) return []
      return String(s.currentTask.selectedDefIds)
        .split(',')
        .map(x => Number(x.trim()))
        .filter(x => Number.isFinite(x) && x > 0)
    }
  },
  actions: {
    async loadMeta() {
      try {
        this.scenarioMeta = await taskApi.scenarios()
      } catch (e) { /* ignore */ }
    },
    async loadTasks() {
      this.loading = true
      try {
        this.tasks = await taskApi.list()
        if (!this.currentTask && this.tasks.length) {
          await this.selectTask(this.tasks[0].id)
        }
      } finally {
        this.loading = false
      }
    },
    async selectTask(id) {
      this.currentTaskId = id
      this.currentTask = await taskApi.get(id)
    },
    async createTask(name) {
      const t = await taskApi.create(name)
      this.currentTaskId = t.id
      this.currentTask = t
      if (!this.tasks.find(x => x.id === t.id)) this.tasks.unshift(t)
      return t
    },
    async selectScenario(scenario) {
      if (!this.currentTask) return null
      this.currentTask = await taskApi.selectScenario(this.currentTask.id, scenario)
      return this.currentTask
    },
    async updateSelectedDefs(defIds) {
      if (!this.currentTask) return null
      this.currentTask = await taskApi.updateSelected(this.currentTask.id, defIds)
      return this.currentTask
    },
    async saveStepData(step, stepData) {
      if (!this.currentTask) return null
      this.currentTask = await taskApi.updateStep(this.currentTask.id, step, stepData)
      return this.currentTask
    },
    async gotoStep(step) {
      if (!this.currentTask) return null
      this.currentTask = await taskApi.gotoStep(this.currentTask.id, step)
      return this.currentTask
    },
    async updateChanges(changes) {
      if (!this.currentTask) return null
      this.currentTask = await taskApi.updateChanges(this.currentTask.id, changes)
      return this.currentTask
    },
    async complete() {
      if (!this.currentTask) return null
      this.currentTask = await taskApi.complete(this.currentTask.id)
      return this.currentTask
    },
    refreshFromTask(task) {
      if (task) {
        this.currentTask = task
        this.currentTaskId = task.id
      }
    }
  }
})
