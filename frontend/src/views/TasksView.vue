<template>
  <div>
    <div class="page-card">
      <div class="step-toolbar">
        <span style="font-weight: 600">快速实施任务（历史任务持久化存储）</span>
        <el-button size="small" type="primary" @click="router.push('/export')">新建导出任务</el-button>
        <el-button size="small" type="success" @click="router.push('/import')">新建导入任务</el-button>
        <el-select v-model="typeFilter" size="small" style="width: 130px" @change="onFilterChange">
          <el-option label="全部类型" value="" />
          <el-option label="导出配置" value="EXPORT" />
          <el-option label="导入配置" value="IMPORT" />
        </el-select>
        <div style="flex: 1"></div>
        <el-button size="small" @click="load" :loading="loading">刷新</el-button>
        <el-tag size="small" type="info">共 {{ total }} 个任务</el-tag>
      </div>

      <el-table :data="tasks" size="small" border v-loading="loading" @row-click="openTask" class="task-table">
        <el-table-column label="任务" width="110">
          <template #default="{ row }"><span class="mono">{{ row.key }}</span></template>
        </el-table-column>
        <el-table-column label="类型" width="100">
          <template #default="{ row }">
            <el-tag size="small" :type="row.taskType === 'EXPORT' ? 'primary' : 'success'">
              {{ row.taskTypeName }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="title" label="任务名称" width="180" show-overflow-tooltip />
        <el-table-column label="涉及配置" min-width="180">
          <template #default="{ row }">
            <span class="text-muted mono">{{ (row.defCodes || []).join(', ') || '-' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <el-tag size="small" :type="statusTag(row.status)">{{ statusLabel(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="进度" width="160">
          <template #default="{ row }">
            <el-progress :percentage="Number(row.progress || 0)"
                         :status="row.status === 'FAILED' ? 'exception'
                           : (row.progress >= 100 ? 'success' : undefined)"
                         :stroke-width="10" />
          </template>
        </el-table-column>
        <el-table-column prop="message" label="消息" min-width="200" show-overflow-tooltip />
        <el-table-column label="创建时间" width="165">
          <template #default="{ row }">{{ fmtTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="90" fixed="right">
          <template #default="{ row }">
            <el-button size="small" text type="primary" @click.stop="openTask(row)">查看</el-button>
          </template>
        </el-table-column>
      </el-table>
      <div class="pager">
        <el-pagination v-model:current-page="page" v-model:page-size="size"
                       :total="total" :page-sizes="[10, 20, 50, 100]"
                       layout="total, sizes, prev, pager, next, jumper" small
                       @current-change="load" @size-change="onSizeChange" />
      </div>
      <div class="text-muted" style="margin-top: 8px">
        说明：任务在创建时即持久化入库（导出任务在向导第 2 步“开始导出”时创建，导入任务在向导第 1 步“创建/复用导入批次”时创建）；
        运行中任务每 4 秒自动刷新（状态与进度已落库，刷新页面不丢失）；点击行或“查看”可跳转对应向导恢复该任务。
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, onBeforeUnmount } from 'vue'
import { useRouter } from 'vue-router'
import { api } from '../api'
import { useExportStore } from '../stores/exportTask'
import { useImportStore } from '../stores/importTask'

const router = useRouter()
const exportStore = useExportStore()
const importStore = useImportStore()

const tasks = ref([])
const typeFilter = ref('')
const loading = ref(false)
const total = ref(0)
const page = ref(1)
const size = ref(20)
let timer = null

const STATUS_LABEL = {
  PENDING: '等待执行', RUNNING: '执行中', SUCCESS: '已完成', FAILED: '失败',
  CREATED: '已创建', CHECKING: '检查中', CHECKED: '检查完成',
  IMPORTING: '导入中', IMPORTED: '已导入(草稿)', PUBLISHING: '发布中', PUBLISHED: '已发布'
}

function statusLabel(s) { return STATUS_LABEL[s] || s }
function statusTag(s) {
  if (['SUCCESS', 'PUBLISHED'].includes(s)) return 'success'
  if (['FAILED'].includes(s)) return 'danger'
  if (['RUNNING', 'CHECKING', 'IMPORTING', 'PUBLISHING'].includes(s)) return 'primary'
  return 'info'
}

function fmtTime(t) {
  return t ? String(t).replace('T', ' ').substring(0, 19) : '-'
}

async function load() {
  loading.value = true
  try {
    const q = new URLSearchParams({ page: page.value, size: size.value })
    if (typeFilter.value) q.set('type', typeFilter.value)
    const data = await api.get(`/api/tasks?${q.toString()}`)
    tasks.value = data.rows || []
    total.value = data.total || 0
  } finally {
    loading.value = false
  }
}

function onFilterChange() {
  page.value = 1
  load()
}

function onSizeChange() {
  page.value = 1
  load()
}

/** 查看/恢复任务：跳转对应向导并恢复该任务视图（运行中任务自动订阅进度）。 */
async function openTask(row) {
  if (row.taskType === 'EXPORT') {
    const snap = await api.get(`/api/export/tasks/${row.id}`)
    exportStore.task = { ...snap, id: row.id }
    exportStore.goStep(3)
    if (!['SUCCESS', 'FAILED'].includes(snap.status)) exportStore.subscribe(row.id)
    router.push('/export')
  } else {
    const snap = await api.get(`/api/import/batches/${row.id}`)
    importStore.batch = { ...snap, id: row.id }
    importStore.entries = snap.detail.files || []
    const s = snap.status
    importStore.goStep(['PUBLISHED'].includes(s) ? 4 : ['IMPORTED'].includes(s) ? 3 : ['CHECKED', 'FAILED'].includes(s) ? 2 : 1)
    if (['CHECKING', 'IMPORTING', 'PUBLISHING'].includes(s)) importStore.subscribe(row.id)
    router.push('/import')
  }
}

onMounted(() => {
  load()
  // 运行中任务自动刷新（状态/进度已持久化，轮询即实时）
  timer = setInterval(() => {
    if (!loading.value) load()
  }, 4000)
})

onBeforeUnmount(() => {
  if (timer) clearInterval(timer)
})
</script>

<style scoped>
.step-toolbar {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 14px;
  flex-wrap: wrap;
}
.task-table :deep(.el-table__row) {
  cursor: pointer;
}
.pager {
  margin-top: 12px;
  display: flex;
  justify-content: flex-end;
}
</style>
