<template>
  <div>
    <div class="job-head" v-if="job">
      <el-tag :type="statusType(job.status)" effect="dark">{{ statusLabel(job.status) }}</el-tag>
      <span v-if="job.currentItem && running" class="current-item">正在处理：{{ job.currentItem }}</span>
      <span class="job-count">{{ job.processed || 0 }} / {{ job.total || 0 }}</span>
      <el-button v-if="running" size="small" type="danger" plain @click="$emit('cancel')">取消作业</el-button>
    </div>
    <el-progress v-if="job" :percentage="percent" :status="progressStatus" style="margin: 8px 0 12px;" />
    <div v-if="job && job.error" class="job-error">
      <el-alert type="error" :title="job.error" :closable="false" show-icon />
    </div>
    <el-table v-if="job && job.items && job.items.length" :data="job.items" size="small" border>
      <el-table-column type="expand" v-if="hasAnyDetail">
        <template #default="{ row }">
          <el-table v-if="row.detail && row.detail.length" :data="row.detail" size="small" style="margin: 4px 16px;">
            <el-table-column prop="rowNo" label="行号" width="80" />
            <el-table-column prop="field" label="字段" width="160" />
            <el-table-column prop="message" label="错误原因" />
          </el-table>
          <div v-else style="padding: 8px 16px; color: #909399;">无明细</div>
        </template>
      </el-table-column>
      <el-table-column prop="seq" label="顺序" width="64" />
      <el-table-column prop="defCode" label="配置项" min-width="160" />
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag size="small" :type="statusType(row.status)">{{ statusLabel(row.status) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="totalRows" label="总行数" width="90" />
      <el-table-column prop="okRows" label="成功" width="80" />
      <el-table-column label="失败" width="80">
        <template #default="{ row }">
          <span :style="{ color: row.errorRows ? '#f56c6c' : 'inherit' }">{{ row.errorRows ?? '-' }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="message" label="说明" min-width="140" show-overflow-tooltip />
    </el-table>
    <el-empty v-if="!job" description="尚未启动作业" :image-size="60" />
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { jobPercent } from '@/utils/job'

const props = defineProps({
  job: { type: Object, default: null }
})
defineEmits(['cancel'])

const running = computed(() => props.job && ['PENDING', 'RUNNING'].includes(props.job.status))
const percent = computed(() => jobPercent(props.job))
const progressStatus = computed(() => {
  if (!props.job) return undefined
  if (props.job.status === 'SUCCESS') return 'success'
  if (props.job.status === 'FAILED' || props.job.status === 'CANCELLED') return 'exception'
  return undefined
})
const hasAnyDetail = computed(() =>
  !!(props.job && props.job.items && props.job.items.some((it) => it.detail && it.detail.length))
)

function statusType(s) {
  return {
    PENDING: 'info',
    RUNNING: 'primary',
    SUCCESS: 'success',
    FAILED: 'danger',
    CANCELLED: 'warning',
    SKIPPED: 'info'
  }[s] || 'info'
}

function statusLabel(s) {
  return {
    PENDING: '等待中',
    RUNNING: '运行中',
    SUCCESS: '成功',
    FAILED: '失败',
    CANCELLED: '已取消',
    SKIPPED: '已跳过'
  }[s] || s
}
</script>

<style scoped>
.job-head {
  display: flex;
  align-items: center;
  gap: 12px;
}
.current-item { color: #606266; font-size: 13px; }
.job-count { margin-left: auto; color: #909399; font-size: 13px; }
.job-error { margin-bottom: 10px; }
</style>
