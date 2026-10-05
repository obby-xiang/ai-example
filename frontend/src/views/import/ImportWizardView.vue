<template>
  <div>
    <div class="page-header">
      <div>
        <h2>{{ task?.title || '导入配置' }}</h2>
        <p class="subtitle">上传配置 → 检查配置 → 导入配置 → 发布配置</p>
      </div>
      <el-button @click="$router.push('/tasks')">返回任务中心</el-button>
    </div>

    <el-steps :active="currentStepIndex" finish-status="success" style="margin-bottom:24px">
      <el-step title="上传配置" />
      <el-step title="检查配置" />
      <el-step title="导入配置" />
      <el-step title="发布配置" />
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
import { ref, computed, onMounted, watch } from 'vue'
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
const currentStepIndex = computed(() => stepOrder.indexOf(step.value))

onMounted(async () => { await taskStore.loadTask(taskId.value) })
watch(step, (s) => { workspaceStore.setExtra('step', s) })

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
    router.push(`/tasks/${taskId.value}/import/${stepOrder[idx - 1]}`)
  }
}
</script>
