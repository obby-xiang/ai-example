<template>
  <div>
    <div class="page-header">
      <div>
        <h2>{{ task?.title || '导入配置' }}</h2>
        <p class="subtitle">上传配置 → 检查配置 → 导入配置 → 发布配置</p>
      </div>
      <el-button @click="$router.push('/tasks')">返回任务中心</el-button>
    </div>

    <!-- 步进条：Element Plus el-steps，步骤可点击切换（@click 透传到步骤根节点） -->
    <el-steps
      :active="stepActiveIndex"
      finish-status="success"
      style="margin-bottom:24px"
    >
      <el-step
        v-for="(s, i) in importSteps"
        :key="s.key"
        :title="s.title"
        class="cursor-pointer select-none hover:opacity-80"
        @click="jumpToStep(s.key)"
      />
    </el-steps>

    <div class="wizard-body">
      <StepUpload v-if="step === 'UPLOAD'" :task="task" @next="goNext" />
      <StepPrecheck v-else-if="step === 'PRECHECK'" :task="task" @next="goNext" @back="goBack" />
      <StepImport v-else-if="step === 'IMPORT'" :task="task" @next="goNext" @back="goBack" />
      <StepPublish v-else-if="step === 'PUBLISH'" :task="task" @back="goBack" />
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useTaskStore } from '@/stores/task.js'
import { useWorkspaceStore } from '@/stores/workspace.js'
import StepUpload from './StepUpload.vue'
import StepPrecheck from './StepPrecheck.vue'
import StepImport from './StepImport.vue'
import StepPublish from './StepPublish.vue'

const route = useRoute()
const router = useRouter()
const taskStore = useTaskStore()
const workspaceStore = useWorkspaceStore()

const step = computed(() => route.params.step)
const taskId = computed(() => Number(route.params.id))
const task = computed(() => taskStore.currentTask)

const stepOrder = ['UPLOAD', 'PRECHECK', 'IMPORT', 'PUBLISH']
const importSteps = [
  { key: 'UPLOAD', title: '上传配置' },
  { key: 'PRECHECK', title: '检查配置' },
  { key: 'IMPORT', title: '导入配置' },
  { key: 'PUBLISH', title: '发布配置' }
]

// el-steps active：已完成任务全部走完，其余取当前步骤下标
const stepActiveIndex = computed(() => {
  if (task.value?.status === 'COMPLETED') return importSteps.length
  const idx = importSteps.findIndex(s => s.key === step.value)
  return idx < 0 ? 0 : idx
})

onMounted(async () => {
  await taskStore.loadTask(taskId.value)
  // 订阅任务事件流：AI 或用户对任务的任何变更都会实时同步到工作区
  taskStore.connectTaskStream(taskId.value)
})

onUnmounted(() => {
  taskStore.disconnectTaskStream()
})
watch(step, (s) => { workspaceStore.setExtra('step', s) })

// 点击步进条任意步骤直接跳转查看（同步后端 currentStep）
async function jumpToStep(key) {
  if (key === step.value) return
  await taskStore.goToStep(taskId.value, key)
  router.push(`/tasks/${taskId.value}/import/${key}`)
}

async function goNext() {
  const idx = stepOrder.indexOf(step.value)
  if (idx < stepOrder.length - 1) {
    const nextStep = stepOrder[idx + 1]
    await taskStore.goToStep(taskId.value, nextStep)
    router.push(`/tasks/${taskId.value}/import/${nextStep}`)
  }
}

function goBack() {
  const idx = stepOrder.indexOf(step.value)
  if (idx > 0) {
    const prevStep = stepOrder[idx - 1]
    taskStore.goToStep(taskId.value, prevStep)
    router.push(`/tasks/${taskId.value}/import/${prevStep}`)
  }
}
</script>
