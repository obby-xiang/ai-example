<template>
  <div class="max-w-5xl mx-auto">
    <div class="mb-6">
      <h1 class="text-xl font-bold text-gray-800">快速实施任务</h1>
      <p class="text-sm text-gray-500 mt-1">选择任务类型开始，任务创建后即持久化，可随时从任务中心恢复。</p>
    </div>

    <div class="grid grid-cols-1 md:grid-cols-2 gap-4 mb-8">
      <div v-for="t in taskTypes" :key="t.code"
        class="task-card bg-white rounded-lg border border-gray-200 p-5 cursor-pointer hover:border-blue-400 hover:shadow-md transition-all"
        @click="create(t.code)">
        <div class="flex items-start gap-3">
          <div class="w-10 h-10 rounded-lg flex items-center justify-center flex-shrink-0"
            :class="t.code === 'EXPORT_CONFIG' ? 'bg-blue-50 text-blue-600' : 'bg-green-50 text-green-600'">
            <el-icon :size="22"><component :is="t.code === 'EXPORT_CONFIG' ? 'Download' : 'Upload'" /></el-icon>
          </div>
          <div class="min-w-0">
            <div class="font-semibold text-gray-800">{{ t.name }}</div>
            <div class="text-xs text-gray-500 mt-1 leading-5">{{ t.description }}</div>
            <div class="mt-3 flex items-center gap-1 flex-wrap">
              <el-tag v-for="(s, i) in t.steps" :key="s" size="small" effect="plain" type="info">
                {{ i + 1 }}. {{ s }}
              </el-tag>
            </div>
          </div>
        </div>
      </div>
    </div>

    <div class="bg-white rounded-lg border border-gray-200">
      <div class="px-5 py-3 border-b border-gray-100 flex items-center justify-between">
        <span class="font-semibold text-sm text-gray-700">最近任务</span>
        <el-button text size="small" type="primary" @click="$router.push('/tasks')">查看全部</el-button>
      </div>
      <el-table :data="recent" size="small" @row-click="openTask" class="cursor-pointer">
        <el-table-column prop="title" label="任务" min-width="120" />
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="statusType(row.status)" size="small">{{ statusText(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="当前步骤" width="110">
          <template #default="{ row }">{{ stepText(row.currentStep) }}</template>
        </el-table-column>
        <el-table-column prop="createdAt" label="创建时间" width="170" />
      </el-table>
      <el-empty v-if="!recent.length" description="暂无任务" :image-size="60" />
    </div>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { api } from '@/api'
import { useSessionStore } from '@/stores/session'

const router = useRouter()
const sessionStore = useSessionStore()
const taskTypes = ref([])
const recent = ref([])

onMounted(async () => {
  sessionStore.ensureSession()
  taskTypes.value = await api.taskTypes()
  recent.value = (await api.listTasks({})).slice(0, 8)
})

async function create(type) {
  const task = await api.createTask(type)
  ElMessage.success('任务已创建')
  router.push(`/task/${task.id}`)
}

function openTask(row) {
  router.push(`/task/${row.id}`)
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

<style scoped>
.task-card:active {
  transform: scale(0.99);
}
</style>
