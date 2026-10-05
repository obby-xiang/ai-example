<template>
  <div>
    <div class="page-header">
      <div>
        <h2>任务中心</h2>
        <p class="subtitle">管理配置导出和导入任务</p>
      </div>
      <div style="display:flex;gap:8px">
        <el-button type="primary" @click="createExport">
          <el-icon><Upload /></el-icon> 新建导出任务
        </el-button>
        <el-button @click="createImport">
          <el-icon><Download /></el-icon> 新建导入任务
        </el-button>
      </div>
    </div>

    <el-table :data="taskStore.tasks" v-loading="taskStore.loading" border stripe>
      <el-table-column prop="id" label="#" width="60" />
      <el-table-column label="类型" width="80">
        <template #default="{ row }">
          <el-tag :type="row.type === 'EXPORT' ? 'success' : 'primary'" size="small">
            {{ row.type === 'EXPORT' ? '导出' : '导入' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="title" label="任务标题" min-width="200" show-overflow-tooltip />
      <el-table-column label="当前步骤" width="120">
        <template #default="{ row }">
          <el-tag size="small" effect="plain">{{ stepLabel(row.currentStep) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag :type="statusType(row.status)" size="small">{{ statusLabel(row.status) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="创建时间" width="160" prop="createdAt" />
      <el-table-column label="操作" width="140" fixed="right">
        <template #default="{ row }">
          <el-button type="primary" link size="small" @click="openTask(row)">继续</el-button>
          <el-button type="danger" link size="small" @click="handleDelete(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>
  </div>
</template>

<script setup>
import { onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useTaskStore } from '@/stores/task.js'
import { ElMessageBox, ElMessage } from 'element-plus'
import { Upload, Download } from '@element-plus/icons-vue'

const router = useRouter()
const taskStore = useTaskStore()

onMounted(() => taskStore.loadTasks())

async function createExport() {
  const task = await taskStore.createTask('EXPORT', '导出配置-' + new Date().toLocaleDateString())
  router.push(`/tasks/${task.id}/export/SELECT_DEFS`)
}

async function createImport() {
  const task = await taskStore.createTask('IMPORT', '导入配置-' + new Date().toLocaleDateString())
  router.push(`/tasks/${task.id}/import/UPLOAD`)
}

function openTask(task) {
  const base = task.type === 'EXPORT' ? 'export' : 'import'
  router.push(`/tasks/${task.id}/${base}/${task.currentStep}`)
}

async function handleDelete(task) {
  await ElMessageBox.confirm(`确定删除任务 "${task.title}"？`, '删除任务', {
    type: 'warning', confirmButtonText: '删除', confirmButtonClass: 'el-button--danger'
  })
  await taskStore.deleteTask(task.id)
  ElMessage.success('任务已删除')
}

const stepLabels = {
  SELECT_DEFS: '选择配置', QUERY_COND: '查询条件', EXPORT: '执行导出',
  UPLOAD: '上传配置', PRECHECK: '检查配置', IMPORT: '导入配置', PUBLISH: '发布配置', DONE: '已完成'
}
const stepLabel = (s) => stepLabels[s] || s

const statusType = (s) => ({ ACTIVE: '', COMPLETED: 'success', CANCELLED: 'info', FAILED: 'danger' })[s] || ''
const statusLabel = (s) => ({ ACTIVE: '进行中', COMPLETED: '已完成', CANCELLED: '已取消', FAILED: '失败' })[s] || s
</script>
