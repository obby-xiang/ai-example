<template>
  <el-card shadow="never" class="wizard-shell">
    <template #header>
      <div class="flex items-center justify-between">
        <div class="flex items-center gap-2">
          <span class="text-base font-medium">{{ title }}</span>
          <el-tag v-if="taskId" size="small" type="info">任务 #{{ taskId }}</el-tag>
          <el-tag v-if="taskStatus" size="small" :type="statusTagType">{{ taskStatusLabel }}</el-tag>
        </div>
        <slot name="header-extra" />
      </div>
    </template>

    <el-steps :active="activeIndex" :status="stepState.elStatus" align-center finish-status="success" class="mb-4">
      <el-step v-for="step in steps" :key="step" :title="stepLabel(step)" />
    </el-steps>

    <JobProgress
      v-if="progress"
      :processed="progress.processed"
      :total="progress.total"
      :status="progress.status ?? null"
      :error-count="progress.errorCount ?? 0"
      :warning-count="progress.warningCount ?? 0"
      class="mb-4"
    />

    <div class="wizard-body">
      <!-- 体：业务步骤由各向导页插槽提供（壳体/体分离，glm 蓝本 TaskWizard 实践） -->
      <slot />
    </div>

    <div v-if="$slots.actions" class="mt-4 pt-3 border-t border-gray-200 flex justify-end gap-2">
      <slot name="actions" />
    </div>
  </el-card>
</template>

<script setup lang="ts">
/**
 * 向导壳体（步骤条 + 进度条 + 操作区），业务步骤由插槽注入（壳/体分离）。
 *
 * 步进条状态映射以**任务状态机**为准（task.ts 的 mapTaskStatusToStep），
 * 不用进度百分比反推状态。
 */
import { computed } from 'vue'
import JobProgress from '@/components/JobProgress.vue'
import { mapTaskStatusToStep } from '@/stores/task'
import { stepLabel } from '@/utils/format'
import type { TaskStatus } from '@/types/task'
import type { JobStatus } from '@/types/job'

const props = withDefaults(
  defineProps<{
    title: string
    steps: readonly string[]
    currentStep: string
    taskId?: number | null
    taskStatus?: TaskStatus | null
    progress?: { processed: number; total: number; status?: JobStatus | null; errorCount?: number; warningCount?: number } | null
  }>(),
  {
    taskId: null,
    taskStatus: null,
    progress: null
  }
)

const activeIndex = computed(() => {
  const index = props.steps.indexOf(props.currentStep)
  return index >= 0 ? index : 0
})

const stepState = computed(() => mapTaskStatusToStep(props.taskStatus))

const taskStatusLabel = computed(() => stepState.value.label)

const statusTagType = computed<'success' | 'danger' | 'info' | 'warning'>(() => {
  switch (props.taskStatus) {
    case 'COMPLETED':
      return 'success'
    case 'FAILED':
      return 'danger'
    case 'CANCELLED':
      return 'info'
    default:
      return 'warning'
  }
})
</script>

<style scoped>
.wizard-shell {
  height: 100%;
}
.wizard-body {
  min-height: 120px;
}
</style>
