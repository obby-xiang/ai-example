<template>
  <div class="context-chip" v-if="label">
    <el-icon size="12"><Location /></el-icon>
    <span>{{ label }}</span>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { Location } from '@element-plus/icons-vue'
import { useWorkspaceStore } from '@/stores/workspace.js'

const ws = useWorkspaceStore()

const label = computed(() => {
  if (ws.taskType && ws.step) {
    const typeLabel = ws.taskType === 'EXPORT' ? '导出' : '导入'
    const stepLabels = {
      SELECT_DEFS: '选择配置',
      QUERY_COND: '查询条件',
      EXPORT: '执行导出',
      UPLOAD: '上传配置',
      PRECHECK: '检查配置',
      IMPORT: '导入配置',
      PUBLISH: '发布配置'
    }
    return `${typeLabel} › ${stepLabels[ws.step] || ws.step}`
  }
  const pageLabels = { tasks: '任务中心', definitions: '配置定义', data: '配置数据' }
  return pageLabels[ws.page] || ''
})
</script>

<style lang="scss" scoped>
.context-chip {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 4px 12px;
  font-size: 11px;
  color: var(--el-text-color-secondary);
  background: var(--el-fill-color-light);
  border-bottom: 1px solid var(--el-border-color-lighter);
}
</style>
