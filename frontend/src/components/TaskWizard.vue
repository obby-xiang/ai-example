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
        <el-button @click="goBack">{{ backText }}</el-button>
      </div>
    </div>

    <el-alert
      v-if="readOnly"
      type="info"
      :closable="false"
      show-icon
      :title="t('task.readOnly')"
      class="mb-4"
      data-testid="view-only-banner"
    />

    <el-steps :active="activeIndex" align-center finish-status="success" class="mb-5">
      <el-step v-for="(step, i) in steps" :key="step.key" :status="stepStatuses[i] ?? ''">
        <template #title>
          <!--
            只读态：节点是"查看入口"，必须是真按钮（可 Tab、可 Enter/Space，驱动的正是 #wizard-step-body）；
            进行中态：纯文本 —— 不做"可聚焦但点了没反应"的伪按钮（业界调研 §六-D-3）。
            两态都保留失败红字：状态不能只靠颜色（§六-D-1）。
            字体复位用 `[font:inherit]`（任意属性 = `font: inherit`）而非 `font-[inherit]`：本项目
            `tailwind.config.js` 关了 preflight，`font-[inherit]` 只产出 `font-family:inherit`，
            `<button>` 的 UA 样式仍按 13.3px/400/normal 渲染 ⇒ 节点文字会比进行中态明显变小、变细。
          -->
          <template v-if="readOnly">
            <button
              type="button"
              class="border-0 bg-transparent p-0 cursor-pointer text-inherit [font:inherit]"
              :aria-current="i === activeIndex ? 'true' : undefined"
              aria-controls="wizard-step-body"
              :data-testid="'wizard-step-' + i"
              @click="emit('step-click', i)"
            >
              {{ step.label }}<span v-if="stepStatuses[i] === 'error'" class="text-[#f56c6c]">{{ t('task.failedSuffix') }}</span>
            </button>
          </template>
          <template v-else>
            {{ step.label }}<span v-if="stepStatuses[i] === 'error'" class="text-[#f56c6c]">{{ t('task.failedSuffix') }}</span>
          </template>
        </template>
      </el-step>
    </el-steps>

    <!-- 体：各步骤的业务内容由向导页插槽注入（壳/体分离，glm 蓝本 TaskWizard 实践） -->
    <!-- id 是步骤条节点按钮的 aria-controls 目标（可点节点驱动本容器的内容切换） -->
    <div id="wizard-step-body">
      <slot />
    </div>

    <div class="mt-5 pt-4 border-t border-solid border-[#ebeef5] flex justify-center gap-3">
      <slot name="actions-extra" />
      <el-button v-if="showPrev" :disabled="prevDisabled" @click="emit('prev')">{{ t('task.prev') }}</el-button>
      <el-button v-if="showNext" type="primary" :disabled="nextDisabled" :loading="nextLoading" @click="emit('next')">
        {{ nextText }}
      </el-button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { useI18n } from 'vue-i18n'
/**
 * 向导壳体（标题栏 + 步骤条 + 页脚导航），业务步骤由插槽注入 —— 壳/体分离
 * （规格 §2 部件映射：glm-5.3 `TaskWizard.vue` 取壳，视觉按 kimi-k3 同名页对齐）。
 *
 * 顶部状态标签以**任务状态机**为准，按当前语言查 `views.tasks.*`（术语与任务中心一致），
 * 不用进度百分比反推状态；`activeIndex` 由页面按任务 currentStep 给出。
 *
 * 步骤条**节点级**状态由页面按"该步作业的终态"给出（`stepStatuses`，下标与 `steps` 对齐），
 * 不用容器级粗染色 —— `el-steps` 无 `status` prop，只有 `el-step` 有。
 *
 * `readOnly`（任务已结束）时：顶部出「仅供查看」横幅，节点由纯文本变 `<button>`
 * （可 Tab / Enter，驱动 `#wizard-step-body` 内容切换，`emit('step-click', i)` 交页面处理），
 * 页脚导航由页面隐藏 —— 业界范式「点节点 = 查看，不是编辑」（业界调研 §三-1/2、§六-B）。
 */
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { ROUTE_NAMES } from '@/router'
import type { TaskStatus } from '@/types/task'

const { t } = useI18n()

interface WizardStep {
  key: string
  label: string
}

/** 节点状态档位：与 `el-step` 的 `status` prop 同档（无 cancelled 档，取消态用 'wait' 表达中性） */
type StepStatus = '' | 'wait' | 'process' | 'finish' | 'error' | 'success'

const props = withDefaults(
  defineProps<{
    title: string
    /** 副标题（任务编号 · 名称） */
    subtitle?: string
    steps: readonly WizardStep[]
    /** 当前步骤下标（0 基） */
    activeIndex: number
    /** 各步骤节点状态（下标与 steps 对齐）；留空项与超出长度的项按容器派生状态渲染 */
    stepStatuses?: readonly StepStatus[]
    /** 只读态（任务已结束）：渲染「仅供查看」横幅，节点变为可点按钮，页脚导航由页面隐藏 */
    readOnly?: boolean
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
    stepStatuses: () => [],
    readOnly: false,
    taskStatus: null,
    backLabel: '',
    showPrev: true,
    showNext: true,
    prevDisabled: false,
    nextDisabled: false,
    nextLoading: false,
    nextLabel: ''
  }
)

const emit = defineEmits<{
  (event: 'prev'): void
  (event: 'next'): void
  /** 只读态点节点查看（下标 0 基）；页面实现必须是**本地纯查看**，不得落库 */
  (event: 'step-click', index: number): void
}>()

const router = useRouter()

const taskStatusLabel = computed(() => {
  switch (props.taskStatus) {
    case 'COMPLETED':
      return t('views.tasks.completed')
    case 'FAILED':
      return t('views.tasks.failed')
    case 'CANCELLED':
      return t('views.tasks.cancelled')
    default:
      return t('views.tasks.active')
  }
})

const backText = computed(() => props.backLabel || t('task.backToList'))

const nextText = computed(() => props.nextLabel || t('task.next'))

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
