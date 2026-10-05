<template>
  <div class="max-w-6xl mx-auto">
    <div class="flex items-center justify-between mb-4">
      <div>
        <h1 class="text-xl font-bold text-gray-800">
          {{ taskStore.task?.title || '任务向导' }}
          <el-tag v-if="taskStore.task" :type="statusType(taskStore.task.status)" size="small" class="ml-2">
            {{ statusText(taskStore.task.status) }}
          </el-tag>
        </h1>
        <p class="text-xs text-gray-400 mt-1 font-mono">{{ taskStore.task?.id }}</p>
      </div>
      <div class="flex gap-2">
        <el-button v-if="canCancel" size="small" type="danger" plain @click="cancel">取消任务</el-button>
        <el-button size="small" @click="$router.push('/')">返回首页</el-button>
      </div>
    </div>

    <!-- 步骤条 -->
    <div class="bg-white rounded-lg border border-gray-200 px-6 py-4 mb-4">
      <el-steps :active="taskStore.stepIndex" align-center finish-status="success">
        <el-step v-for="(s, i) in steps" :key="s.key" :title="s.name"
          :description="taskStore.currentStep === s.key ? '当前步骤' : ''">
          <template #icon>
            <span class="step-num" :class="{ active: i <= taskStore.stepIndex }">{{ i + 1 }}</span>
          </template>
        </el-step>
      </el-steps>
    </div>

    <!-- 进度条（后台操作执行中） -->
    <div v-if="taskStore.progress" class="bg-white rounded-lg border border-blue-200 px-5 py-3 mb-4">
      <div class="flex items-center justify-between mb-2">
        <span class="text-sm font-medium text-blue-700">
          <el-icon class="mr-1 align-middle"><Loading /></el-icon>
          {{ opText }}：{{ taskStore.progress.message }}
        </span>
        <span class="text-sm text-blue-600 font-semibold">{{ taskStore.progress.percent }}%</span>
      </div>
      <el-progress :percentage="taskStore.progress.percent" :stroke-width="8"
        :status="taskStore.progress.percent >= 100 ? 'success' : undefined" />
      <div v-if="taskStore.progress.items?.length" class="mt-2 space-y-1">
        <div v-for="it in taskStore.progress.items" :key="it.configCode"
          class="flex items-center gap-2 text-xs">
          <span class="w-40 truncate text-gray-600 font-mono">{{ it.configCode }}</span>
          <el-icon v-if="it.status === 'DONE'" class="text-green-500"><CircleCheckFilled /></el-icon>
          <el-icon v-else-if="it.status === 'ERROR'" class="text-red-500"><CircleCloseFilled /></el-icon>
          <el-icon v-else class="text-blue-500 animate-spin"><Loading /></el-icon>
          <span class="text-gray-500">{{ it.message }}</span>
        </div>
      </div>
    </div>

    <component v-if="taskStore.task && wizardComp" :is="wizardComp" :key="taskStore.task.id" />
    <div v-else class="text-center text-gray-400 py-16">
      <el-icon :size="36"><Loading /></el-icon>
      <p class="mt-2 text-sm">加载任务中…</p>
    </div>
  </div>
</template>

<script setup>
import { computed, defineAsyncComponent, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '@/api'
import { useTaskStore } from '@/stores/task'

const route = useRoute()
const router = useRouter()
const taskStore = useTaskStore()

const ExportWizard = defineAsyncComponent(() => import('@/components/wizard/ExportWizard.vue'))
const ImportWizard = defineAsyncComponent(() => import('@/components/wizard/ImportWizard.vue'))

const wizardComp = computed(() => taskStore.isExport ? ExportWizard : taskStore.isImport ? ImportWizard : null)

const steps = computed(() => taskStore.isExport
  ? [
      { key: 'SELECT_CONFIG', name: '选择配置' },
      { key: 'SET_CONDITION', name: '查询配置' },
      { key: 'EXECUTE_EXPORT', name: '导出配置' }
    ]
  : [
      { key: 'SELECT_CONFIG', name: '选择配置' },
      { key: 'PREPARE', name: '上传配置' },
      { key: 'CHECK', name: '检查配置' },
      { key: 'IMPORT', name: '导入配置' },
      { key: 'PUBLISH', name: '发布配置' }
    ])

const canCancel = computed(() =>
  taskStore.task && !['SUCCESS', 'CANCELLED'].includes(taskStore.task.status))

const opText = computed(() => ({
  EXPORT: '导出进度', CHECK: '检查进度', IMPORT: '导入进度', PUBLISH: '发布进度'
}[taskStore.progress?.op] || '操作进度'))

onMounted(async () => {
  await taskStore.loadCatalog()
  await taskStore.loadTask(route.params.id)
})

watch(() => route.params.id, (id) => {
  if (id && route.name === 'task') {
    taskStore.loadTask(id)
  }
})

async function cancel() {
  await ElMessageBox.confirm('确定取消该任务吗？执行中的操作会在当前配置项处理完后停止。',
    '取消任务', { type: 'warning', confirmButtonText: '取消任务', cancelButtonText: '再想想' })
  await api.cancelTask(taskStore.task.id)
  ElMessage.success('已取消')
  await taskStore.loadTask(taskStore.task.id)
}

function statusType(s) {
  return { SUCCESS: 'success', FAILED: 'danger', CANCELLED: 'info', EXECUTING: 'warning', WAITING: 'primary' }[s] || 'info'
}
function statusText(s) {
  return { WAITING: '进行中', EXECUTING: '执行中', SUCCESS: '成功', FAILED: '失败', CANCELLED: '已取消' }[s] || s
}
</script>

<style scoped>
.step-num {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 24px;
  height: 24px;
  border-radius: 50%;
  background: #e4e7ed;
  color: #909399;
  font-size: 12px;
  font-weight: 600;
}
.step-num.active {
  background: #409eff;
  color: #fff;
}
</style>
