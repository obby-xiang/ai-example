import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import * as tasksApi from '@/api/tasks.js'

export const useTaskStore = defineStore('task', () => {
  const tasks = ref([])
  const currentTask = ref(null)
  const loading = ref(false)

  async function loadTasks() {
    loading.value = true
    try {
      const res = await tasksApi.listTasks()
      tasks.value = res.data.data || []
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
    const task = res.data.data
    tasks.value.unshift(task)
    return task
  }

  async function deleteTask(id) {
    await tasksApi.deleteTask(id)
    tasks.value = tasks.value.filter(t => t.id !== id)
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

  return { tasks, currentTask, loading, loadTasks, loadTask, createTask, deleteTask, selectDefs, setCondition, goToStep }
})
