<template>
  <div>
    <div class="page-header">
      <div>
        <h2>任务中心</h2>
        <p class="subtitle">历史任务持久化存储：查看所有快速实施任务的状态与进度</p>
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
      <el-table-column label="任务标题" min-width="180" show-overflow-tooltip>
        <template #default="{ row }">
          <el-link type="primary" :underline="false" @click="openDetail(row)">{{ row.task.title }}</el-link>
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
      <el-table-column label="操作" width="170" fixed="right">
        <template #default="{ row }">
          <el-button type="primary" link size="small" :disabled="!canResume(row)" @click="resume(row)">继续</el-button>
          <el-button link size="small" @click="openDetail(row)">详情</el-button>
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

    <!-- 任务详情抽屉 -->
    <el-drawer v-model="detailVisible" size="62%" :title="detail ? `任务 #${detail.task?.id} 详情` : '任务详情'">
      <template v-if="detail">
        <div class="detail-section">
          <div class="detail-header">
            <h3>{{ detail.task?.title }}</h3>
            <div>
              <el-tag :type="detail.task?.type === 'EXPORT' ? 'success' : 'primary'" size="small">{{ detail.task?.type === 'EXPORT' ? '导出配置' : '导入配置' }}</el-tag>
              <el-tag :type="statusType(detail.task?.status)" size="small" style="margin-left:6px">{{ statusLabel(detail.task?.status) }}</el-tag>
            </div>
          </div>
          <p class="detail-meta">创建于 {{ formatTime(detail.task?.createdAt) }}，更新于 {{ formatTime(detail.task?.updatedAt) }}</p>
        </div>

        <!-- 步骤进度 -->
        <div class="detail-section">
          <div class="section-title">流程进度</div>
          <el-steps :active="stepIndex(detail.task)" finish-status="success" align-center>
            <el-step v-for="(s, i) in stepsOf(detail.task)" :key="i" :title="stepLabel(s)" />
          </el-steps>
        </div>

        <!-- 配置项状态 -->
        <div class="detail-section">
          <div class="section-title">配置项（{{ detail.task?.items?.length || 0 }}）</div>
          <el-table :data="detail.task?.items || []" border size="small">
            <el-table-column prop="defCode" label="配置编码" width="150" />
            <el-table-column label="状态" width="120">
              <template #default="{ row }">
                <el-tag :type="itemStatusTag(row.status)" size="small">{{ itemStatusLabel(row.status) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="查询条件/备注" show-overflow-tooltip>
              <template #default="{ row }">{{ row.conditionJson || '—' }}</template>
            </el-table-column>
          </el-table>
        </div>

        <!-- 作业历史 -->
        <div class="detail-section">
          <div class="section-title">作业历史（{{ detail.jobs?.length || 0 }}）</div>
          <el-table :data="detail.jobs || []" border size="small">
            <el-table-column label="作业" width="90">
              <template #default="{ row }">
                <el-tag size="small" :type="jobTypeTag(row.jobType)">{{ jobTypeLabel(row.jobType) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="状态" width="95">
              <template #default="{ row }">
                <el-tag size="small" :type="jobStatusTag(row.status)">{{ jobStatusLabel(row.status) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="进度" min-width="150">
              <template #default="{ row }">
                <el-progress
                  v-if="row.total > 0"
                  :percentage="Math.round(row.progress / row.total * 100)"
                  :status="row.status === 'COMPLETED' ? 'success' : row.status === 'FAILED' ? 'exception' : undefined"
                  :stroke-width="8"
                />
                <span v-else class="empty-hint">—</span>
              </template>
            </el-table-column>
            <el-table-column label="错误/警告" width="95" align="center">
              <template #default="{ row }">
                <span :class="{ 'err-hint': row.errorCount > 0 }">{{ row.errorCount }}/{{ row.warningCount }}</span>
              </template>
            </el-table-column>
            <el-table-column label="开始时间" width="150">
              <template #default="{ row }">{{ formatTime(row.startedAt) }}</template>
            </el-table-column>
            <el-table-column label="结束时间" width="150">
              <template #default="{ row }">{{ formatTime(row.finishedAt) }}</template>
            </el-table-column>
          </el-table>
        </div>

        <!-- 文件 -->
        <div class="detail-section">
          <div class="section-title">文件（{{ detail.files?.length || 0 }}）</div>
          <el-table :data="detail.files || []" border size="small">
            <el-table-column prop="defCode" label="配置编码" width="150" />
            <el-table-column label="类型" width="90">
              <template #default="{ row }">
                <el-tag size="small" effect="plain">{{ fileTypeLabel(row.fileType) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="fileName" label="文件名" show-overflow-tooltip />
            <el-table-column prop="rowCount" label="行数" width="80" />
          </el-table>
        </div>
      </template>
    </el-drawer>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import { listTasks, createTask, deleteTask, getTaskOverview } from '@/api/tasks.js'
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

const detailVisible = ref(false)
const detail = ref(null)
const detailLoading = ref(false)

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
    // 详情抽屉打开时同步刷新
    if (detailVisible.value && detail.value?.task?.id) {
      refreshDetail(detail.value.task.id)
    }
  } finally {
    loading.value = false
  }
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

function canResume(row) {
  const t = row.task
  if (!t) return false
  if (t.status === 'COMPLETED') {
    // 已完成的导出任务仍可进入导出结果页查看文件；导入任务走详情
    return t.type === 'EXPORT'
  }
  if (t.status === 'CANCELLED') return false
  return true
}

function resume(row) {
  const t = row.task
  if (!t) return
  const base = t.type === 'EXPORT' ? 'export' : 'import'
  router.push(`/tasks/${t.id}/${base}/${t.currentStep}`)
}

async function openDetail(row) {
  detailVisible.value = true
  detail.value = { task: row.task, jobs: row.latestJob ? [row.latestJob] : [], files: [] }
  await refreshDetail(row.task.id)
}

async function refreshDetail(taskId) {
  if (detailLoading.value) return
  detailLoading.value = true
  try {
    const res = await getTaskOverview(taskId)
    detail.value = res.data.data
  } catch (e) {
    // ignore
  } finally {
    detailLoading.value = false
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

function stepsOf(task) {
  if (!task) return []
  return task.type === 'EXPORT' ? ['SELECT_DEFS', 'QUERY_COND', 'EXPORT'] : ['UPLOAD', 'PRECHECK', 'IMPORT', 'PUBLISH']
}
function stepIndex(task) {
  const steps = stepsOf(task)
  const idx = steps.indexOf(task?.currentStep)
  if (task?.status === 'COMPLETED') return steps.length
  return idx < 0 ? 0 : idx
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

const itemStatusLabel = (s) => ({ PENDING: '待处理', READY: '已就绪', CHECKING: '检查中', CHECKED: '检查通过', IMPORTING: '导入中', IMPORTED: '已导入', PUBLISHING: '发布中', PUBLISHED: '已发布', COMPLETED: '已完成', FAILED: '失败' })[s] || s
const itemStatusTag = (s) => ({ CHECKED: 'success', IMPORTED: 'success', PUBLISHED: 'success', COMPLETED: 'success', FAILED: 'danger', CHECKING: 'warning', IMPORTING: 'warning', PUBLISHING: 'warning' })[s] || 'info'

const fileTypeLabel = (t) => ({ TEMPLATE: '模板', UPLOAD: '上传', EXPORT: '导出' })[t] || t

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
.detail-section { margin-bottom: 22px; }
.detail-header { display: flex; justify-content: space-between; align-items: center; }
.detail-header h3 { font-size: 16px; }
.detail-meta { font-size: 12px; color: var(--el-text-color-secondary); margin-top: 4px; }
.section-title { font-size: 13px; font-weight: 600; margin-bottom: 8px; color: var(--el-text-color-primary); }
</style>
