<template>
  <div class="task-progress">
    <div class="tp-line">
      <el-tag :type="tagType" size="small">{{ statusLabel }}</el-tag>
      <el-progress :percentage="Number(progress || 0)" :status="progressStatus" class="tp-bar" />
    </div>
    <div v-if="message" class="tp-msg">{{ message }}</div>
  </div>
</template>

<script setup>
import { computed } from 'vue'

/** 长任务进度（导出/检查/导入/发布共用）：快照渲染 + SSE 实时刷新。 */
const props = defineProps({
  snapshot: { type: Object, default: () => ({}) }
})

const STATUS_LABEL = {
  PENDING: '等待执行', CREATED: '已创建', UPLOADED: '已上传',
  RUNNING: '执行中', CHECKING: '检查中', CHECKED: '检查完成',
  IMPORTING: '导入中', IMPORTED: '已导入(未发布)',
  PUBLISHING: '发布中', PUBLISHED: '已发布',
  SUCCESS: '已完成', FAILED: '失败', ERROR: '错误', SKIPPED: '已跳过'
}

const statusLabel = computed(() => STATUS_LABEL[props.snapshot.status] || props.snapshot.status || '-')

const tagType = computed(() => {
  const s = props.snapshot.status
  if (['SUCCESS', 'CHECKED', 'IMPORTED', 'PUBLISHED'].includes(s)) return 'success'
  if (['FAILED', 'ERROR'].includes(s)) return 'danger'
  if (['RUNNING', 'CHECKING', 'IMPORTING', 'PUBLISHING'].includes(s)) return 'primary'
  return 'info'
})

const progressStatus = computed(() => {
  if (props.snapshot.status === 'FAILED') return 'exception'
  if ((props.snapshot.progress || 0) >= 100) return 'success'
  return undefined
})
</script>

<style scoped>
.tp-line { display: flex; align-items: center; gap: 12px; }
.tp-bar { flex: 1; }
.tp-msg { margin-top: 6px; color: #606266; font-size: 13px; }
</style>
