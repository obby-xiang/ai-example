<template>
  <div>
    <div class="page-header">
      <div>
        <h2>{{ task?.title || '导出配置' }}</h2>
        <p class="subtitle">选择配置项 → 设置查询条件 → 执行导出</p>
      </div>
      <el-button @click="$router.push('/tasks')">返回任务中心</el-button>
    </div>

    <!-- Step progress（可点击切换步骤） -->
    <StepBar
      :steps="exportSteps"
      :current="step"
      :completed="task?.status === 'COMPLETED'"
      style="margin-bottom:24px"
      @select="jumpToStep"
    />

    <!-- Step content -->
    <div class="wizard-body">
      <StepSelectDefs v-if="step === 'SELECT_DEFS'" :task="task" @next="goNext" />
      <StepQueryCond v-else-if="step === 'QUERY_COND'" :task="task" @next="goNext" @back="goBack" />
      <StepExport v-else-if="step === 'EXPORT'" :task="task" @back="goBack" />
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useTaskStore } from '@/stores/task.js'
import { useWorkspaceStore } from '@/stores/workspace.js'
import StepSelectDefs from './StepSelectDefs.vue'
import StepQueryCond from './StepQueryCond.vue'
import StepExport from './StepExport.vue'
import StepBar from '@/components/common/StepBar.vue'

const route = useRoute()
const router = useRouter()
const taskStore = useTaskStore()
const workspaceStore = useWorkspaceStore()

const exportSteps = [
  { key: 'SELECT_DEFS', title: '选择配置' },
  { key: 'QUERY_COND', title: '查询条件' },
  { key: 'EXPORT', title: '执行导出' }
]

const step = computed(() => route.params.step)
const taskId = computed(() => Number(route.params.id))
const task = computed(() => taskStore.currentTask)

const stepOrder = ['SELECT_DEFS', 'QUERY_COND', 'EXPORT']

onMounted(async () => {
  await taskStore.loadTask(taskId.value)
  // 订阅任务事件流：AI 或用户对任务的任何变更都会实时同步到工作区
  taskStore.connectTaskStream(taskId.value)
})

onUnmounted(() => {
  taskStore.disconnectTaskStream()
})

watch(step, (s) => {
  workspaceStore.setExtra('step', s)
})

// 点击步进条任意步骤直接跳转查看（同步后端 currentStep，保持单一事实来源）
async function jumpToStep(key) {
  if (key === step.value) return
  await taskStore.goToStep(taskId.value, key)
  router.push(`/tasks/${taskId.value}/export/${key}`)
}

async function goNext() {
  const current = stepOrder.indexOf(step.value)
  if (current < stepOrder.length - 1) {
    const nextStep = stepOrder[current + 1]
    await taskStore.goToStep(taskId.value, nextStep)
    router.push(`/tasks/${taskId.value}/export/${nextStep}`)
  }
}

function goBack() {
  const current = stepOrder.indexOf(step.value)
  if (current > 0) {
    const prevStep = stepOrder[current - 1]
    taskStore.goToStep(taskId.value, prevStep)
    router.push(`/tasks/${taskId.value}/export/${prevStep}`)
  }
}
</script>
