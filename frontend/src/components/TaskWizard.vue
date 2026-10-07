<template>
  <div>
    <div class="flex justify-between items-start mb-4">
      <div>
        <h2 class="m-0 text-xl text-[#303133]">{{ title }}</h2>
        <div class="mt-1 text-[13px] text-[#909399]">
          <span v-if="subtitle">{{ subtitle }}</span>
          <el-tag v-if="taskStatus" size="small" class="ml-2" :type="statusTagType">{{ taskStatusLabel }}</el-tag>
        </div>
      </div>
      <div class="flex items-center gap-2">
        <slot name="header-extra" />
        <el-button @click="goBack">{{ backLabel }}</el-button>
      </div>
    </div>

    <el-steps :active="activeIndex" :status="stepperStatus" align-center finish-status="success" class="mb-5">
      <el-step v-for="step in steps" :key="step.key" :title="step.label" />
    </el-steps>

    <!-- 体：各步骤的业务内容由向导页插槽注入（壳/体分离，glm 蓝本 TaskWizard 实践） -->
    <slot />

    <div class="mt-5 pt-4 border-t border-solid border-[#ebeef5] flex justify-center gap-3">
      <slot name="actions-extra" />
      <el-button v-if="showPrev" :disabled="prevDisabled" @click="emit('prev')">上一步</el-button>
      <el-button v-if="showNext" type="primary" :disabled="nextDisabled" :loading="nextLoading" @click="emit('next')">
        {{ nextLabel }}
      </el-button>
    </div>
  </div>
</template>

<script setup lang="ts">
/**
 * 向导壳体（标题栏 + 步骤条 + 页脚导航），业务步骤由插槽注入 —— 壳/体分离
 * （规格 §2 部件映射：glm-5.3 `TaskWizard.vue` 取壳，视觉按 kimi-k3 同名页对齐）。
 *
 * 步骤条状态映射以**任务状态机**为准（task store 的 mapTaskStatusToStep），
 * 不用进度百分比反推状态；`activeIndex` 由页面按任务 currentStep 给出。
 */
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { ROUTE_NAMES } from '@/router'
import { mapTaskStatusToStep } from '@/stores/task'
import type { TaskStatus } from '@/types/task'

interface WizardStep {
  key: string
  label: string
}

const props = withDefaults(
  defineProps<{
    title: string
    /** 副标题（任务编号 · 名称） */
    subtitle?: string
    steps: readonly WizardStep[]
    /** 当前步骤下标（0 基） */
    activeIndex: number
    taskStatus?: TaskStatus | null
    backLabel?: string
    showPrev?: boolean
    showNext?: boolean
    prevDisabled?: boolean
    nextDisabled?: boolean
    nextLoading?: boolean
    nextLabel?: string
  }>(),
  {
    subtitle: '',
    taskStatus: null,
    backLabel: '返回任务列表',
    showPrev: true,
    showNext: true,
    prevDisabled: false,
    nextDisabled: false,
    nextLoading: false,
    nextLabel: '下一步'
  }
)

const emit = defineEmits<{
  (event: 'prev'): void
  (event: 'next'): void
}>()

const router = useRouter()

const stepState = computed(() => mapTaskStatusToStep(props.taskStatus))

const taskStatusLabel = computed(() => stepState.value.label)

const stepperStatus = computed<'error' | 'wait' | undefined>(() => {
  if (props.taskStatus === 'FAILED') {
    return 'error'
  }
  if (props.taskStatus === 'CANCELLED') {
    return 'wait'
  }
  return undefined
})

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

function goBack(): void {
  void router.push({ name: ROUTE_NAMES.taskCenter })
}
</script>
