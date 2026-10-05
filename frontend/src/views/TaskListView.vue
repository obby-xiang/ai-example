<template>
  <div>
    <div class="flex justify-between items-center mb-4">
      <div>
        <h2 class="m-0 text-xl text-[#303133]">实施任务</h2>
        <div class="mt-1 text-[13px] text-[#909399]">导出配置 / 导入配置 任务管理</div>
      </div>
      <div>
        <el-button :icon="Refresh" circle @click="loadTasks" title="刷新" />
        <el-button type="primary" :icon="Plus" @click="createDialogVisible = true">创建任务</el-button>
      </div>
    </div>

    <el-table v-loading="loading" :data="tasks" border>
      <el-table-column prop="taskNo" label="任务编号" width="180" />
      <el-table-column prop="name" label="任务名称" min-width="160" show-overflow-tooltip />
      <el-table-column label="类型" width="100">
        <template #default="{ row }">
          <el-tag size="small" :type="row.type === 'EXPORT' ? 'success' : 'primary'">
            {{ row.type === 'EXPORT' ? '导出配置' : '导入配置' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="110">
        <template #default="{ row }">
          <el-tag size="small" :type="statusType(row.status)">{{ statusLabel(row.status) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="进度" width="160">
        <template #default="{ row }">
          <el-progress :percentage="row.progress || 0" :stroke-width="8" />
        </template>
      </el-table-column>
      <el-table-column label="当前步骤" width="90">
        <template #default="{ row }">第 {{ row.currentStep }} 步</template>
      </el-table-column>
      <el-table-column label="创建时间" width="170">
        <template #default="{ row }">{{ fmtTime(row.createdAt) }}</template>
      </el-table-column>
      <el-table-column label="更新时间" width="170">
        <template #default="{ row }">{{ fmtTime(row.updatedAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="220" fixed="right">
        <template #default="{ row }">
          <el-button size="small" type="primary" link :disabled="row.status === 'CANCELLED'"
                     @click="openTask(row)">打开</el-button>
          <el-button size="small" type="warning" link
                     :disabled="!['DRAFT', 'IN_PROGRESS'].includes(row.status)"
                     @click="cancelTask(row)">取消</el-button>
          <el-button size="small" type="danger" link @click="removeTask(row)">删除</el-button>
        </template>
      </el-table-column>
      <template #empty>
        <el-empty description="暂无任务，点击右上角创建" />
      </template>
    </el-table>

    <el-dialog v-model="createDialogVisible" title="创建任务" width="440px">
      <el-form label-width="80px">
        <el-form-item label="任务类型" required>
          <el-radio-group v-model="createForm.type">
            <el-radio value="EXPORT">
              导出配置
              <div class="text-xs text-[#909399] font-normal">选择配置项 → 设置查询条件 → 导出 Excel</div>
            </el-radio>
            <el-radio value="IMPORT">
              导入配置
              <div class="text-xs text-[#909399] font-normal">上传/编辑 → 检查 → 导入暂存 → 发布生效</div>
            </el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="任务名称" required>
          <el-input v-model="createForm.name" maxlength="60" placeholder="例如：Q4 税率批量导入" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="creating" @click="createTask">创建</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted, onBeforeUnmount } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Refresh } from '@element-plus/icons-vue'
import dayjs from 'dayjs'
import { taskApi } from '@/api'
import { useWorkspaceStore, registerPageHandler, unregisterPageHandler } from '@/stores/workspace'

const router = useRouter()
const ws = useWorkspaceStore()

const tasks = ref([])
const loading = ref(false)
const createDialogVisible = ref(false)
const creating = ref(false)
const createForm = ref({ type: 'EXPORT', name: '' })

async function loadTasks() {
  loading.value = true
  try {
    tasks.value = await taskApi.list()
  } finally {
    loading.value = false
  }
}

async function createTask() {
  if (!createForm.value.name.trim()) {
    ElMessage.warning('请输入任务名称')
    return
  }
  creating.value = true
  try {
    const task = await taskApi.create(createForm.value.type, createForm.value.name.trim())
    createDialogVisible.value = false
    createForm.value = { type: 'EXPORT', name: '' }
    ElMessage.success(`任务 ${task.taskNo} 已创建`)
    openTask(task)
  } finally {
    creating.value = false
  }
}

function openTask(row) {
  router.push(row.type === 'EXPORT' ? `/export/${row.id}` : `/import/${row.id}`)
}

async function cancelTask(row) {
  try {
    await ElMessageBox.confirm(`确定取消任务「${row.name}」？运行中的作业将一并取消。`, '取消任务', { type: 'warning' })
  } catch (e) { return }
  await taskApi.cancel(row.id)
  ElMessage.success('任务已取消')
  loadTasks()
}

async function removeTask(row) {
  try {
    await ElMessageBox.confirm(`确定删除任务「${row.name}」？关联作业、暂存与导出结果将一并删除。`, '删除任务', { type: 'error' })
  } catch (e) { return }
  await taskApi.remove(row.id)
  ElMessage.success('任务已删除')
  loadTasks()
}

function fmtTime(t) {
  return t ? dayjs(t).format('YYYY-MM-DD HH:mm:ss') : '-'
}

function statusType(s) {
  return { DRAFT: 'info', IN_PROGRESS: 'primary', COMPLETED: 'success', FAILED: 'danger', CANCELLED: 'warning' }[s] || 'info'
}
function statusLabel(s) {
  return { DRAFT: '草稿', IN_PROGRESS: '进行中', COMPLETED: '已完成', FAILED: '失败', CANCELLED: '已取消' }[s] || s
}

onMounted(() => {
  ws.enterPage({ page: 'TASKS' })
  registerPageHandler('get_workspace_state_extra', () => ({
    taskCount: tasks.value.length,
    tasks: tasks.value.slice(0, 10).map((t) => ({ id: t.id, taskNo: t.taskNo, type: t.type, status: t.status }))
  }))
  loadTasks()
})

onBeforeUnmount(() => {
  unregisterPageHandler('get_workspace_state_extra')
  ws.reset()
})
</script>
