<template>
  <div>
    <div class="page-header">
      <div>
        <h2>{{ task?.title || '导出配置' }}</h2>
        <p class="subtitle">选择配置项 → 设置查询条件 → 执行导出</p>
      </div>
      <el-button @click="$router.push('/tasks')">返回任务中心</el-button>
    </div>

    <!-- Step progress -->
    <el-steps :active="currentStepIndex" finish-status="success" style="margin-bottom:24px">
      <el-step title="选择配置" />
      <el-step title="查询条件" />
      <el-step title="执行导出" />
    </el-steps>

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

const route = useRoute()
const router = useRouter()
const taskStore = useTaskStore()
const workspaceStore = useWorkspaceStore()

const step = computed(() => route.params.step)
const taskId = computed(() => Number(route.params.id))
const task = computed(() => taskStore.currentTask)

const stepOrder = ['SELECT_DEFS', 'QUERY_COND', 'EXPORT']
const currentStepIndex = computed(() => stepOrder.indexOf(step.value))

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
    router.push(`/tasks/${taskId.value}/export/${prevStep}`)
  }
}
</script>
