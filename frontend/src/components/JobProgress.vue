<template>
  <div class="w-full">
    <div class="flex items-center justify-between mb-1 text-xs text-gray-500">
      <span>{{ view.label }}</span>
      <span v-if="view.percent !== null">{{ view.percent }}%</span>
      <span v-else class="text-amber-600">总量未知</span>
    </div>
    <el-progress
      :percentage="view.percent ?? 0"
      :indeterminate="view.unknownTotal"
      :duration="2"
      :status="progressStatus"
      :stroke-width="12"
      :show-text="false"
    />
    <div v-if="errorCount > 0 || warningCount > 0" class="mt-1 text-xs">
      <span v-if="errorCount > 0" class="text-red-600 mr-3">错误 {{ errorCount }}</span>
      <span v-if="warningCount > 0" class="text-amber-600">警告 {{ warningCount }}</span>
    </div>
  </div>
</template>

<script setup lang="ts">
/**
 * 行级进度条（口径：jobs.progress / jobs.total 为**行级** processed/total）。
 *
 * total 未知（约定 -1）时不显示百分比，改走 indeterminate 的"处理中"形态
 * ——见 types/job.ts 的 formatJobProgress。
 */
import { computed } from 'vue'
import type { JobStatus } from '@/types/job'
import { progressView } from '@/utils/format'

const props = withDefaults(
  defineProps<{
    processed: number
    total: number
    status?: JobStatus | string | null
    errorCount?: number
    warningCount?: number
  }>(),
  {
    status: null,
    errorCount: 0,
    warningCount: 0
  }
)

const view = computed(() => progressView(props.processed, props.total))

const progressStatus = computed<'success' | 'exception' | 'warning' | undefined>(() => {
  if (props.status === 'COMPLETED') {
    return 'success'
  }
  if (props.status === 'FAILED') {
    return 'exception'
  }
  if (props.status === 'CANCELLED') {
    return 'warning'
  }
  return undefined
})
</script>
