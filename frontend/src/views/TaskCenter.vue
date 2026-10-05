<template>
  <div class="max-w-6xl mx-auto">
    <div class="flex items-center justify-between mb-4">
      <h1 class="text-xl font-bold text-gray-800">任务中心</h1>
      <el-button size="small" @click="load">
        <el-icon class="mr-1"><Refresh /></el-icon>刷新
      </el-button>
    </div>

    <div class="bg-white rounded-lg border border-gray-200 p-3 mb-3 flex gap-3 items-center">
      <el-select v-model="query.type" placeholder="任务类型" clearable size="small" style="width: 140px" @change="load">
        <el-option label="导出配置" value="EXPORT_CONFIG" />
        <el-option label="导入配置" value="IMPORT_CONFIG" />
      </el-select>
      <el-select v-model="query.status" placeholder="状态" clearable size="small" style="width: 120px" @change="load">
        <el-option label="进行中" value="WAITING" />
        <el-option label="执行中" value="EXECUTING" />
        <el-option label="成功" value="SUCCESS" />
        <el-option label="失败" value="FAILED" />
        <el-option label="已取消" value="CANCELLED" />
      </el-select>
      <span class="text-xs text-gray-400">共 {{ tasks.length }} 条</span>
    </div>

    <div class="bg-white rounded-lg border border-gray-200">
      <el-table :data="tasks" size="small" @row-click="openTask" class="cursor-pointer">
        <el-table-column label="任务ID" width="130">
          <template #default="{ row }">
            <span class="font-mono text-xs">{{ row.id.slice(0, 12) }}…</span>
          </template>
        </el-table-column>
        <el-table-column prop="title" label="类型" width="100" />
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="statusType(row.status)" size="small">{{ statusText(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="当前步骤" width="110">
          <template #default="{ row }">{{ stepText(row.currentStep) }}</template>
        </el-table-column>
        <el-table-column label="已选配置" min-width="160">
          <template #default="{ row }">
            <span v-if="row.selection?.length" class="text-xs text-gray-600">{{ row.selection.join('、') }}</span>
            <span v-else class="text-xs text-gray-400">—</span>
          </template>
        </el-table-column>
        <el-table-column prop="createdAt" label="创建时间" width="170" />
        <el-table-column prop="updatedAt" label="更新时间" width="170" />
        <el-table-column label="操作" width="150" fixed="right">
          <template #default="{ row }">
            <el-button size="small" type="primary" text
              :disabled="['SUCCESS', 'FAILED', 'CANCELLED'].includes(row.status)"
              @click.stop="openTask(row)">继续处理</el-button>
            <el-button v-if="!['SUCCESS', 'CANCELLED'].includes(row.status)" size="small" type="danger" text
              @click.stop="cancel(row)">取消</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-if="!tasks.length" description="暂无任务" :image-size="60" />
    </div>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '@/api'

const router = useRouter()
const tasks = ref([])
const query = reactive({ type: '', status: '' })

onMounted(load)

async function load() {
  tasks.value = await api.listTasks({
    type: query.type || undefined,
    status: query.status || undefined
  })
}

function openTask(row) {
  router.push(`/task/${row.id}`)
}

async function cancel(row) {
  await ElMessageBox.confirm(
    `确定取消任务「${row.title}」吗？执行中的操作会在当前配置项处理完后停止。`,
    '取消任务', { type: 'warning', confirmButtonText: '取消任务', cancelButtonText: '再想想' })
  await api.cancelTask(row.id)
  ElMessage.success('已取消')
  load()
}

function statusType(s) {
  return { SUCCESS: 'success', FAILED: 'danger', CANCELLED: 'info', EXECUTING: 'warning', WAITING: 'primary' }[s] || 'info'
}
function statusText(s) {
  return { WAITING: '进行中', EXECUTING: '执行中', SUCCESS: '成功', FAILED: '失败', CANCELLED: '已取消' }[s] || s
}
function stepText(s) {
  return { SELECT_CONFIG: '选择配置', SET_CONDITION: '查询配置', EXECUTE_EXPORT: '导出配置', PREPARE: '上传配置', CHECK: '检查配置', IMPORT: '导入配置', PUBLISH: '发布配置' }[s] || s
}
</script>
