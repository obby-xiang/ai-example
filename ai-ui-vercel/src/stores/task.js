import { defineStore } from 'pinia'
import { v4 as uuidv4 } from 'uuid'

/**
 * 任务 store —— 前端 AI runtime 模式下用 localStorage 持久化(满足"创建即持久化、刷新可恢复"约束)。
 * 替代旧 ai-ui 的后端 REST(/api/tasks/*)。
 *
 * 场景状态图(SCENARIO_STEPS)从 ai-service scenarios.yaml 平移为 JS 常量。
 * 保留与旧 task store 完全一致的方法签名 + getter,使拷贝的视图/组件无需改动。
 *
 * 经验沉淀(进度条不同步 bug):
 *   - scenario=null 时只允许 SELECT_SCENARIO,跳到后续步骤会拒绝
 *   - gotoStep 做跨场景跳步校验
 */

// ====== 场景状态图(平移自 scenarios.yaml) ======
export const SCENARIO_STEPS = {
  EXPORT: ['SELECT_SCENARIO', 'SELECT_DEFS', 'QUERY_COND', 'RESULT'],
  IMPORT: ['SELECT_SCENARIO', 'VIEW_DEFS', 'PRECHECK', 'REVIEW', 'PUBLISH'],
  ADD: ['SELECT_SCENARIO', 'VIEW_DEFS', 'PRECHECK', 'REVIEW', 'PUBLISH'],
  MODIFY: ['SELECT_SCENARIO', 'VIEW_DEFS', 'PRECHECK', 'REVIEW', 'PUBLISH']
}
const SCENARIO_NAME = {
  EXPORT: '导出配置', IMPORT: '导入配置', ADD: '新增配置', MODIFY: '修改配置'
}
const SCENARIO_DESC = {
  EXPORT: '选择配置项,设置查询条件,导出数据',
  IMPORT: '上传文件,预检查,复核,发布',
  ADD: '定义新配置项,预检查,复核,发布',
  MODIFY: '修改现有配置项数据,预检查,复核,发布'
}

const STORAGE_KEY = 'ai-vercel-tasks'

function loadAll() {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    if (!raw) return []
    const arr = JSON.parse(raw)
    return Array.isArray(arr) ? arr : []
  } catch (e) { return [] }
}
function saveAll(tasks) {
  try { localStorage.setItem(STORAGE_KEY, JSON.stringify(tasks)) } catch (e) { /* ignore */ }
}

export const useTaskStore = defineStore('task', {
  state: () => ({
    tasks: [],
    currentTask: null,
    currentTaskId: null,
    scenarioMeta: {
      scenarioName: { ...SCENARIO_NAME },
      scenarioSteps: { ...SCENARIO_STEPS },
      scenarioDesc: { ...SCENARIO_DESC }
    },
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
        .split(',').map(x => Number(x.trim())).filter(x => Number.isFinite(x) && x > 0)
    }
  },
  actions: {
    async loadMeta() {
      // 场景元数据是静态常量,直接就绪
      this.scenarioMeta = {
        scenarioName: { ...SCENARIO_NAME },
        scenarioSteps: { ...SCENARIO_STEPS },
        scenarioDesc: { ...SCENARIO_DESC }
      }
    },
    async loadTasks() {
      this.loading = true
      try {
        this.tasks = loadAll()
        if (!this.currentTask && this.tasks.length) {
          await this.selectTask(this.tasks[0].id)
        }
      } finally {
        this.loading = false
      }
    },
    async selectTask(id) {
      this.currentTaskId = id
      const all = loadAll()
      const t = all.find(x => x.id === id)
      this.currentTask = t || null
      return this.currentTask
    },
    _persist() {
      if (!this.currentTask) return
      const all = loadAll()
      const idx = all.findIndex(x => x.id === this.currentTask.id)
      if (idx >= 0) all[idx] = this.currentTask
      else all.unshift(this.currentTask)
      saveAll(all)
      this.tasks = all
    },
    async createTask(name) {
      const id = Date.now()
      const t = {
        id,
        name: name || ('配置任务-' + new Date().toISOString().slice(0, 10)),
        scenario: null,
        currentStep: 'SELECT_SCENARIO',
        status: 'DRAFT',
        selectedDefIds: '',
        stepData: {},
        changes: null,
        createdAt: new Date().toISOString(),
        updatedAt: new Date().toISOString()
      }
      this.currentTask = t
      this.currentTaskId = id
      this._persist()
      return t
    },
    async selectScenario(scenario) {
      if (!this.currentTask) return null
      this.currentTask.scenario = scenario
      this.currentTask.currentStep = SCENARIO_STEPS[scenario]?.[1] || 'SELECT_SCENARIO'
      this.currentTask.status = 'IN_PROGRESS'
      this.currentTask.updatedAt = new Date().toISOString()
      this._persist()
      return this.currentTask
    },
    async updateSelectedDefs(defIds) {
      if (!this.currentTask) return null
      this.currentTask.selectedDefIds = (defIds || []).join(',')
      this.currentTask.updatedAt = new Date().toISOString()
      this._persist()
      return this.currentTask
    },
    async saveStepData(step, stepData) {
      if (!this.currentTask) return null
      this.currentTask.stepData = this.currentTask.stepData || {}
      this.currentTask.stepData[step] = stepData
      this.currentTask.updatedAt = new Date().toISOString()
      this._persist()
      return this.currentTask
    },
    async gotoStep(step) {
      if (!this.currentTask) return null
      // 跨场景跳步校验(进度条 bug 根因防护):
      //   scenario=null 时只允许 SELECT_SCENARIO
      const scenario = this.currentTask.scenario
      if (!scenario || step === 'SELECT_SCENARIO') {
        if (step !== 'SELECT_SCENARIO' && !scenario) {
          // 无场景跳后续步骤,拒绝
          return this.currentTask
        }
        this.currentTask.currentStep = step
        this.currentTask.updatedAt = new Date().toISOString()
        this._persist()
        return this.currentTask
      }
      const allowed = SCENARIO_STEPS[scenario] || []
      if (!allowed.includes(step)) {
        // 步骤不在当前场景状态图内,拒绝
        return this.currentTask
      }
      this.currentTask.currentStep = step
      this.currentTask.updatedAt = new Date().toISOString()
      this._persist()
      return this.currentTask
    },
    async updateChanges(changes) {
      if (!this.currentTask) return null
      this.currentTask.changes = changes
      this.currentTask.updatedAt = new Date().toISOString()
      this._persist()
      return this.currentTask
    },
    async complete() {
      if (!this.currentTask) return null
      this.currentTask.status = 'COMPLETED'
      this.currentTask.updatedAt = new Date().toISOString()
      this._persist()
      return this.currentTask
    },
    async remove(id) {
      let all = loadAll().filter(x => x.id !== id)
      saveAll(all)
      this.tasks = all
      if (this.currentTaskId === id) {
        this.currentTaskId = null
        this.currentTask = null
      }
    },
    refreshFromTask(task) {
      if (task) {
        this.currentTask = task
        this.currentTaskId = task.id
      }
    }
  }
})
