import { defineStore } from 'pinia'
import { ref, watch } from 'vue'
import { updateContext } from '@/api/ai.js'
import { useAiStore } from './ai.js'

export const useWorkspaceStore = defineStore('workspace', () => {
  const page = ref('tasks')
  const taskId = ref(null)
  const taskType = ref(null)
  const step = ref(null)
  const extra = ref({})

  let reportTimer = null

  function updateFromRoute(route) {
    page.value = route.meta?.page || 'tasks'
    taskType.value = route.meta?.taskType || null
    taskId.value = route.params?.id ? Number(route.params.id) : null
    step.value = route.params?.step || null
    scheduleReport()
  }

  function setExtra(key, value) {
    extra.value[key] = value
    scheduleReport()
  }

  function scheduleReport() {
    if (reportTimer) clearTimeout(reportTimer)
    reportTimer = setTimeout(() => reportContext(), 300)
  }

  async function reportContext() {
    const aiStore = useAiStore()
    const sid = aiStore.sessionId
    if (!sid) return
    try {
      await updateContext(sid, {
        page: page.value,
        taskId: taskId.value,
        taskType: taskType.value,
        step: step.value,
        extra: extra.value
      })
    } catch (e) {
      // ignore context update errors
    }
  }

  return { page, taskId, taskType, step, extra, updateFromRoute, setExtra, reportContext }
})
