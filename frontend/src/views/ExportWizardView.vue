<template>
  <TaskWizard
    title="导出向导"
    :steps="TaskSteps.EXPORT"
    :current-step="currentStep"
    :task-id="taskId"
  >
    <el-alert
      type="info"
      :closable="false"
      show-icon
      title="本棒为占位壳（b/c 棒实现业务步骤体）"
      description="壳体（步骤条/进度条/操作区）与契约层已就绪：步骤体将按「选择配置项 → 查询条件 → 导出执行」三体分别实现。"
    />

    <div class="mt-4 text-sm text-gray-600 space-y-2">
      <div>路由：<span class="font-mono">/export</span>（AI 上下文标签 task:EXPORT/&lt;步骤&gt;）</div>
      <div>已注册页面能力：<span class="font-mono">{{ registeredHandlers.join('、') || '（无）' }}</span></div>
      <div>已选配置项：<span class="font-mono">{{ workspace.selectedDefs.join('、') || '（空）' }}</span></div>
    </div>

    <template #actions>
      <el-button disabled>上一步</el-button>
      <el-button type="primary" disabled>下一步</el-button>
    </template>
  </TaskWizard>
</template>

<script setup lang="ts">
/**
 * 导出向导（本棒：壳 + 契约接线；三步体由 b/c 棒实现）。
 *
 * 这里演示「页面把能力反向暴露给 AI」的登记方式（registerPageHandler）：
 * AI 侧的前端工具执行器经 getPageHandler 取到能力，页面卸载即注销，
 * 于是 AI 侧不需要 import 任何视图组件。
 */
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import TaskWizard from '@/components/TaskWizard.vue'
import { PAGE_HANDLER_OPEN_EXPORT_EDITOR } from '@/utils/frontend-tools'
import { registerPageHandler, unregisterPageHandler, useWorkspaceStore } from '@/stores/workspace'
import { TaskSteps } from '@/types/task'

const workspace = useWorkspaceStore()
const route = useRoute()

const taskId = ref<number | null>(null)
const currentStep = ref<string>(TaskSteps.EXPORT[0])
const registeredHandlers = ref<string[]>([])

onMounted(() => {
  const queryTaskId = Number(route.query.taskId)
  taskId.value = Number.isFinite(queryTaskId) && queryTaskId > 0 ? queryTaskId : null
  currentStep.value = typeof route.query.step === 'string' ? route.query.step : TaskSteps.EXPORT[0]

  workspace.enterPage({
    pageId: 'export',
    page: 'export',
    taskType: 'EXPORT',
    step: currentStep.value,
    taskId: taskId.value
  })

  registerPageHandler(PAGE_HANDLER_OPEN_EXPORT_EDITOR, () => {
    // 在线编辑器（SpreadJS + 保存回后端）由 c 棒接入，这里只回可读说明
    return '导出向导已收到打开编辑器请求（编辑器体由 c 棒实现）'
  })
  registeredHandlers.value = [PAGE_HANDLER_OPEN_EXPORT_EDITOR]
})

onBeforeUnmount(() => {
  unregisterPageHandler(PAGE_HANDLER_OPEN_EXPORT_EDITOR)
  registeredHandlers.value = []
})
</script>
