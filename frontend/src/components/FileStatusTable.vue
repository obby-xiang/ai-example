<template>
  <div class="file-status">
    <el-table :data="entries" size="small" border>
      <el-table-column prop="defCode" label="配置编码" width="140" />
      <el-table-column prop="defName" label="配置名称" width="140" />
      <el-table-column prop="fileName" label="文件" min-width="150">
        <template #default="{ row }">
          <span class="mono">{{ row.fileName || '-' }}</span>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="130">
        <template #default="{ row }">
          <el-tag :type="statusTag(row.status)" size="small">{{ statusLabel(row.status) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="rowCount" label="行数" width="70" />
      <el-table-column label="错误/警告" width="90">
        <template #default="{ row }">
          <span :class="{ 'err': row.errorCount > 0 }">{{ row.errorCount }}</span> /
          <span :class="{ 'warn': row.warnCount > 0 }">{{ row.warnCount }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="message" label="说明" min-width="180" show-overflow-tooltip />
      <el-table-column label="操作" width="170" fixed="right">
        <template #default="{ row }">
          <el-button size="small" text type="primary" :disabled="row.errorCount === 0 && row.warnCount === 0"
                     @click="showIssues(row)">查看明细</el-button>
          <el-button size="small" text type="warning"
                     :disabled="row.errorCount === 0"
                     @click="downloadErrors(row)">下载错误</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="dialogVisible" :title="'检查明细 - ' + currentDef" width="760px">
      <el-table :data="currentIssues" size="small" border max-height="420">
        <el-table-column prop="rowIndex" label="行号" width="70" />
        <el-table-column prop="field" label="字段" width="130" />
        <el-table-column label="级别" width="70">
          <template #default="{ row }">
            <el-tag :type="row.level === 'ERROR' ? 'danger' : 'warning'" size="small">
              {{ row.level === 'ERROR' ? '错误' : '警告' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="message" label="问题描述" min-width="240" />
        <el-table-column prop="value" label="原值" width="120" show-overflow-tooltip />
      </el-table>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { useImportStore } from '../stores/importTask'

const props = defineProps({
  entries: { type: Array, default: () => [] },
  batchId: { type: [Number, String], default: null }
})

const store = useImportStore()
const dialogVisible = ref(false)
const currentIssues = ref([])
const currentDef = ref('')

const STATUS_LABEL = {
  UPLOADED: '已上传', CHECKED: '检查完成', IMPORTED: '已导入', PUBLISHED: '已发布',
  ERROR: '错误', SKIPPED: '已跳过', CHECKING: '检查中', IMPORTING: '导入中', PUBLISHING: '发布中'
}

function statusLabel(s) { return STATUS_LABEL[s] || s }
function statusTag(s) {
  if (s === 'IMPORTED' || s === 'PUBLISHED') return 'success'
  if (s === 'ERROR' || s === 'SKIPPED') return 'danger'
  if (s === 'CHECKED') return 'primary'
  return 'info'
}

async function showIssues(row) {
  currentDef.value = `${row.defCode}（${row.defName}）`
  currentIssues.value = await store.loadIssues(row.defCode)
  dialogVisible.value = true
}

function downloadErrors(row) {
  const url = `/api/import/batches/${props.batchId}/errors/${row.defCode}`
  const a = document.createElement('a')
  a.href = url
  a.download = `${row.defCode}-errors.xlsx`
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
}
</script>

<style scoped>
.err { color: #f56c6c; font-weight: 600; }
.warn { color: #e6a23c; }
</style>
