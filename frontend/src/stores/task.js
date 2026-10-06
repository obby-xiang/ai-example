import { defineStore } from 'pinia'
import { ref, reactive } from 'vue'
import * as tasksApi from '@/api/tasks.js'

/**
 * 任务状态单一事实来源（后端）。
 * 无论变更来自用户在工作区的操作，还是 AI 工具调用，都通过同一套 REST 接口
 * 修改后端状态，并通过同一条任务 SSE 事件流广播。工作区订阅该流即可
 * 实时反映两侧的变更——这就是"联动但不耦合"的实现：
 * - 去掉 AI 面板：工作区操作照常（完全不依赖 AI 模块）
 * - 去掉工作区：AI 工具直接操作后端服务，任务状态照常流转
 */
export const useTaskStore = defineStore('task', () => {
  const tasks = ref([])
  const currentTask = ref(null)
  const loading = ref(false)

  // 任务 SSE 事件流（TASK_CHANGED / JOB_PROGRESS / JOB_DONE）
  const eventSource = ref(null)
  const liveJobs = reactive({})   // jobId -> 最近一次事件片段
  const lastEvent = ref(null)

  async function loadTasks(params) {
    loading.value = true
    try {
      const res = await tasksApi.listTasks(params)
      return res.data.data
    } finally {
      loading.value = false
    }
  }

  async function loadTask(id) {
    loading.value = true
    try {
      const res = await tasksApi.getTask(id)
      currentTask.value = res.data.data
      return currentTask.value
    } finally {
      loading.value = false
    }
  }

  async function createTask(type, title) {
    const res = await tasksApi.createTask({ type, title })
    return res.data.data
  }

  async function deleteTask(id) {
    await tasksApi.deleteTask(id)
    if (currentTask.value?.id === id) currentTask.value = null
  }

  async function selectDefs(taskId, defCodes) {
    const res = await tasksApi.selectDefs(taskId, defCodes)
    if (currentTask.value?.id === taskId) {
      currentTask.value = res.data.data
    }
    return res.data.data
  }

  async function setCondition(taskId, defCode, condition) {
    await tasksApi.setCondition(taskId, defCode, condition)
    await loadTask(taskId)
  }

  async function goToStep(taskId, step) {
    const res = await tasksApi.goToStep(taskId, step)
    if (currentTask.value?.id === taskId) {
      currentTask.value = res.data.data
    }
    return res.data.data
  }

  /**
   * 订阅任务事件流。向导页挂载时调用，卸载时 disconnect。
   * 事件来源对消费者透明：用户操作与 AI 操作都会触发相同事件。
   */
  function connectTaskStream(taskId) {
    if (eventSource.value) {
      if (eventSource.value.__taskId === taskId) return
      disconnectTaskStream()
    }
    const es = new EventSource(`/api/tasks/${taskId}/events`)
    es.__taskId = taskId
    es.onmessage = (evt) => {
      try {
        const msg = JSON.parse(evt.data)
        lastEvent.value = { type: msg.type, data: msg.data, ts: Date.now() }
        if (msg.type === 'TASK_CHANGED') {
          // 任务状态变了（无论谁改的）：刷新本地快照
          loadTask(taskId)
        } else if (msg.type === 'JOB_PROGRESS') {
          liveJobs[msg.data.jobId] = { ...(liveJobs[msg.data.jobId] || {}), ...msg.data }
        } else if (msg.type === 'JOB_DONE') {
          liveJobs[msg.data.jobId] = { ...(liveJobs[msg.data.jobId] || {}), ...msg.data }
          loadTask(taskId)
        }
      } catch (e) { /* ignore malformed */ }
    }
    es.onerror = () => { /* EventSource 自动重连 */ }
    eventSource.value = es
  }

  function disconnectTaskStream() {
    if (eventSource.value) {
      eventSource.value.close()
      eventSource.value = null
    }
  }

  /** 取某类型作业的最近一次事件片段（供步骤组件“认领”AI 启动的作业） */
  function latestJobEvent(jobType) {
    let latest = null
    for (const j of Object.values(liveJobs)) {
      if (j && j.jobType === jobType) {
        if (!latest || (j.ts || 0) >= (latest.ts || 0)) latest = j
      }
    }
    return latest
  }

  return {
    tasks, currentTask, loading, liveJobs, lastEvent,
    loadTasks, loadTask, createTask, deleteTask, selectDefs, setCondition, goToStep,
    connectTaskStream, disconnectTaskStream, latestJobEvent
  }
})
