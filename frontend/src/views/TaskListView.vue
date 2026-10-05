<template>
  <div>
    <div class="page-header">
      <div>
        <h2>任务中心</h2>
        <p class="subtitle">历史任务持久化存储：点击任务标题可进入该任务的向导页查看与继续操作</p>
      </div>
      <el-button type="primary" @click="openCreate">
        <el-icon><Plus /></el-icon> 新建任务
      </el-button>
    </div>

    <!-- 筛选栏 -->
    <div class="filter-bar">
      <el-select v-model="filters.type" clearable placeholder="任务类型" style="width:130px" @change="reload">
        <el-option label="导出配置" value="EXPORT" />
        <el-option label="导入配置" value="IMPORT" />
      </el-select>
      <el-select v-model="filters.status" clearable placeholder="任务状态" style="width:130px" @change="reload">
        <el-option label="进行中" value="ACTIVE" />
        <el-option label="已完成" value="COMPLETED" />
        <el-option label="失败" value="FAILED" />
        <el-option label="已取消" value="CANCELLED" />
      </el-select>
      <el-input v-model="filters.keyword" clearable placeholder="搜索任务标题或 ID" style="width:220px"
                @keyup.enter="reload" @clear="reload" />
      <el-button type="primary" :icon="Search" @click="reload">查询</el-button>
      <el-button :icon="Refresh" @click="reload">刷新</el-button>
      <span class="total-hint">共 {{ total }} 个任务</span>
    </div>

    <!-- 任务表格 -->
    <el-table :data="tasks" v-loading="loading" border stripe>
      <el-table-column prop="task.id" label="#" width="70" sortable />
      <el-table-column label="类型" width="90">
        <template #default="{ row }">
          <el-tag :type="row.task.type === 'EXPORT' ? 'success' : 'primary'" size="small">
            {{ row.task.type === 'EXPORT' ? '导出' : '导入' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="任务标题" min-width="200" show-overflow-tooltip>
        <template #default="{ row }">
          <el-link type="primary" :underline="false" @click="openTask(row)">
            {{ row.task.title }}
          </el-link>
        </template>
      </el-table-column>
      <el-table-column label="当前步骤" width="110">
        <template #default="{ row }">
          <el-tag size="small" effect="plain">{{ stepLabel(row.task.currentStep) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag :type="statusType(row.task.status)" size="small">{{ statusLabel(row.task.status) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="配置项" width="80" align="center">
        <template #default="{ row }">{{ row.itemCount }}</template>
      </el-table-column>
      <el-table-column label="最新作业 / 进度" min-width="220">
        <template #default="{ row }">
          <template v-if="row.latestJob">
            <div class="job-cell">
              <div class="job-line">
                <el-tag size="small" :type="jobTypeTag(row.latestJob.jobType)">{{ jobTypeLabel(row.latestJob.jobType) }}</el-tag>
                <el-tag size="small" :type="jobStatusTag(row.latestJob.status)" effect="plain">{{ jobStatusLabel(row.latestJob.status) }}</el-tag>
                <span v-if="row.latestJob.errorCount > 0" class="err-hint">错误 {{ row.latestJob.errorCount }}</span>
              </div>
              <el-progress
                v-if="row.latestJob.total > 0"
                :percentage="Math.round(row.latestJob.progress / row.latestJob.total * 100)"
                :status="row.latestJob.status === 'COMPLETED' ? 'success' : row.latestJob.status === 'FAILED' ? 'exception' : undefined"
                :stroke-width="8"
              />
            </div>
          </template>
          <span v-else class="empty-hint">尚未执行作业</span>
        </template>
      </el-table-column>
      <el-table-column label="创建时间" width="155">
        <template #default="{ row }">{{ formatTime(row.task.createdAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="90" fixed="right">
        <template #default="{ row }">
          <el-button type="danger" link size="small" @click="handleDelete(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-pagination
      v-model:current-page="page"
      v-model:page-size="pageSize"
      :total="total"
      :page-sizes="[10, 20, 50]"
      layout="total, sizes, prev, pager, next"
      style="margin-top:12px;justify-content:flex-end;display:flex"
      @current-change="reload"
      @size-change="reload"
    />

    <!-- 新建任务对话框 -->
    <el-dialog v-model="createVisible" title="新建快速实施任务" width="520px">
      <el-form label-width="90px">
        <el-form-item label="任务类型" required>
          <el-radio-group v-model="createForm.type">
            <el-radio-button value="EXPORT">
              <el-icon><Upload /></el-icon> 导出配置
            </el-radio-button>
            <el-radio-button value="IMPORT">
              <el-icon><Download /></el-icon> 导入配置
            </el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="任务标题">
          <el-input v-model="createForm.title" placeholder="可选，留空使用默认标题" maxlength="100" />
        </el-form-item>
      </el-form>
      <el-alert type="info" :closable="false" show-icon
                title="创建后任务立即持久化存储，可在任务中心随时查看状态与进度" />
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :loading="creating" @click="handleCreate">创建并开始</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import { listTasks, createTask, deleteTask } from '@/api/tasks.js'
import { ElMessageBox, ElMessage } from 'element-plus'
import { Plus, Search, Refresh, Upload, Download } from '@element-plus/icons-vue'

const router = useRouter()

const tasks = ref([])
const total = ref(0)
const loading = ref(false)
const page = ref(1)
const pageSize = ref(10)
const filters = reactive({ type: '', status: '', keyword: '' })

const createVisible = ref(false)
const creating = ref(false)
const createForm = reactive({ type: 'EXPORT', title: '' })

let refreshTimer = null

onMounted(() => {
  reload()
  // 定时刷新（展示作业实时进度）
  refreshTimer = setInterval(() => { reload(true) }, 5000)
})
onUnmounted(() => { if (refreshTimer) clearInterval(refreshTimer) })

async function reload(silent) {
  if (!silent) loading.value = true
  try {
    const res = await listTasks({
      type: filters.type || undefined,
      status: filters.status || undefined,
      keyword: filters.keyword || undefined,
      page: page.value - 1,
      size: pageSize.value
    })
    tasks.value = res.data.data?.content || []
    total.value = res.data.data?.totalElements || 0
  } finally {
    loading.value = false
  }
}

// 点击任务标题：进入该任务的向导页（与编辑/继续同一交互）
function openTask(row) {
  const t = row.task
  if (!t) return
  const base = t.type === 'EXPORT' ? 'export' : 'import'
  // DONE 步骤映射到各类型的最终步骤页，保证有内容可看
  let step = t.currentStep
  if (!step || step === 'DONE') {
    step = t.type === 'EXPORT' ? 'EXPORT' : 'PUBLISH'
  }
  router.push(`/tasks/${t.id}/${base}/${step}`)
}

function openCreate() {
  createForm.type = 'EXPORT'
  createForm.title = ''
  createVisible.value = true
}

async function handleCreate() {
  creating.value = true
  try {
    const title = createForm.title.trim() || (createForm.type === 'EXPORT' ? '导出配置任务' : '导入配置任务')
    // 创建即持久化（后端立即写入 H2），返回后跳转向导
    const res = await createTask({ type: createForm.type, title })
    const task = res.data.data
    createVisible.value = false
    ElMessage.success(`任务已创建并持久化（#${task.id}）`)
    const base = task.type === 'EXPORT' ? 'export' : 'import'
    router.push(`/tasks/${task.id}/${base}/${task.currentStep}`)
  } catch (e) {
    ElMessage.error('创建失败: ' + (e.response?.data?.message || e.message))
  } finally {
    creating.value = false
  }
}

async function handleDelete(row) {
  try {
    await ElMessageBox.confirm(`确定删除任务 #${row.task.id}「${row.task.title}」？其文件与暂存数据将一并删除。`, '删除任务', {
      type: 'warning', confirmButtonText: '删除', confirmButtonClass: 'el-button--danger'
    })
    await deleteTask(row.task.id)
    ElMessage.success('任务已删除')
    reload()
  } catch (e) { /* 取消 */ }
}

const stepLabels = {
  SELECT_DEFS: '选择配置', QUERY_COND: '查询条件', EXPORT: '执行导出',
  UPLOAD: '上传配置', PRECHECK: '检查配置', IMPORT: '导入配置', PUBLISH: '发布配置', DONE: '已完成'
}
const stepLabel = (s) => stepLabels[s] || s

const statusType = (s) => ({ ACTIVE: 'warning', COMPLETED: 'success', CANCELLED: 'info', FAILED: 'danger' })[s] || ''
const statusLabel = (s) => ({ ACTIVE: '进行中', COMPLETED: '已完成', CANCELLED: '已取消', FAILED: '失败' })[s] || s

const jobTypeLabel = (t) => ({ EXPORT: '导出', PRECHECK: '预检', IMPORT: '导入', PUBLISH: '发布' })[t] || t
const jobTypeTag = (t) => ({ EXPORT: 'success', PRECHECK: 'warning', IMPORT: 'primary', PUBLISH: 'danger' })[t] || ''
const jobStatusLabel = (s) => ({ PENDING: '等待', RUNNING: '运行中', COMPLETED: '完成', FAILED: '失败', CANCELLED: '已取消' })[s] || s
const jobStatusTag = (s) => ({ PENDING: 'info', RUNNING: 'warning', COMPLETED: 'success', FAILED: 'danger', CANCELLED: 'info' })[s] || ''

function formatTime(ts) {
  if (!ts) return '—'
  return String(ts).replace('T', ' ').substring(0, 19)
}
</script>

<style lang="scss" scoped>
.filter-bar { display: flex; gap: 10px; margin-bottom: 14px; align-items: center; }
.total-hint { font-size: 13px; color: var(--el-text-color-secondary); margin-left: 8px; }
.job-cell { display: flex; flex-direction: column; gap: 4px; }
.job-line { display: flex; gap: 6px; align-items: center; }
.err-hint { font-size: 12px; color: var(--el-color-danger); }
.empty-hint { font-size: 12px; color: var(--el-text-color-placeholder); }
</style>
