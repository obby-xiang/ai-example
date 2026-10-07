<template>
  <TaskWizard
    title="导入向导"
    :steps="TaskSteps.IMPORT"
    :current-step="currentStep"
    :task-id="taskId"
    :task-status="taskStore.currentTask?.status ?? null"
  >
    <el-alert
      type="info"
      :closable="false"
      show-icon
      title="本棒为占位壳（b/c 棒实现业务步骤体）"
      description="四步体：上传文件 → 预检查 → 导入暂存 → 发布；发布冲突按 Q4 异步形态（恒 201，冲突=作业 FAILED + issues 文案）。"
    />

    <div class="mt-4 text-sm text-gray-600 space-y-2">
      <div>路由：<span class="font-mono">/import</span>（AI 上下文标签 task:IMPORT/&lt;步骤&gt;）</div>
      <div>导入模式：<span class="font-mono">{{ workspace.importMode ?? '未设置（MERGE/REPLACE）' }}</span></div>
      <div v-if="taskStore.publishConflict" class="text-red-600">
        发布冲突（异步形态）：{{ taskStore.publishConflict.message }}
      </div>
    </div>

    <template #actions>
      <el-button disabled>上一步</el-button>
      <el-button type="primary" disabled>下一步</el-button>
    </template>
  </TaskWizard>
</template>

<script setup lang="ts">
/**
 * 导入向导（本棒：壳 + 契约接线；四步体由 b/c 棒实现）。
 */
import { onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import TaskWizard from '@/components/TaskWizard.vue'
import { useTaskStore } from '@/stores/task'
import { useWorkspaceStore } from '@/stores/workspace'
import { TaskSteps } from '@/types/task'

const workspace = useWorkspaceStore()
const taskStore = useTaskStore()
const route = useRoute()

const taskId = ref<number | null>(null)
const currentStep = ref<string>(TaskSteps.IMPORT[0])

onMounted(() => {
  const queryTaskId = Number(route.query.taskId)
  taskId.value = Number.isFinite(queryTaskId) && queryTaskId > 0 ? queryTaskId : null
  currentStep.value = typeof route.query.step === 'string' ? route.query.step : TaskSteps.IMPORT[0]

  workspace.enterPage({
    pageId: 'import',
    page: 'import',
    taskType: 'IMPORT',
    step: currentStep.value,
    taskId: taskId.value
  })

  if (taskId.value !== null) {
    void taskStore.openTask(taskId.value)
  }
})
</script>
