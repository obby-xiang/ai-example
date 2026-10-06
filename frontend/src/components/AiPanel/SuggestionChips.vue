<template>
  <div class="flex shrink-0 flex-wrap gap-1.5 border-t border-slate-100 bg-slate-50 px-3 py-2">
    <el-button
      v-for="chip in chips"
      :key="chip"
      size="small"
      round
      plain
      @click="$emit('select', chip)"
      class="!h-6 !px-2.5 !text-xs"
    >{{ chip }}</el-button>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { useWorkspaceStore } from '@/stores/workspace.js'

const emit = defineEmits(['select'])
const ws = useWorkspaceStore()

const chips = computed(() => {
  if (ws.taskType === 'EXPORT') {
    if (ws.step === 'SELECT_DEFS') return ['帮我选择所有全局配置', '帮我选择所有项目级配置']
    if (ws.step === 'QUERY_COND') return ['预估一下当前选中配置的数据量', '设置地区为华东']
    if (ws.step === 'EXPORT') return ['开始导出', '导出状态怎么样了？']
  }
  if (ws.taskType === 'IMPORT') {
    if (ws.step === 'UPLOAD') return ['帮我下载所有模板', '各配置项有哪些字段？']
    if (ws.step === 'PRECHECK') return ['开始预检查', '检查结果怎么样？']
    if (ws.step === 'IMPORT') return ['开始导入', '查看差异']
    if (ws.step === 'PUBLISH') return ['分析一下发布风险', '发布配置']
  }
  if (ws.page === 'tasks') return ['创建一个导出任务', '创建一个导入任务', '列出最近的任务']
  if (ws.page === 'definitions') return ['介绍一下各配置项的含义', '帮我查看货币配置的字段']
  return ['帮我介绍一下系统功能', '列出所有配置定义']
})
</script>
