<template>
  <div>
    <div class="page-card">
      <div class="section-title">
        <span>任务中心</span>
        <div class="btn-row">
          <el-button type="primary" @click="createNewTask">
            <el-icon><Plus /></el-icon> 新建任务
          </el-button>
          <el-button @click="reload">
            <el-icon><Refresh /></el-icon> 刷新
          </el-button>
        </div>
      </div>

      <div class="stat-box">
        <div class="stat-item">
          <div class="label">任务总数</div>
          <div class="value">{{ tasks.length }}</div>
        </div>
        <div class="stat-item">
          <div class="label">草稿/进行中</div>
          <div class="value">{{ inProgressCount }}</div>
        </div>
        <div class="stat-item">
          <div class="label">已完成</div>
          <div class="value">{{ completedCount }}</div>
        </div>
        <div class="stat-item">
          <div class="label">可用配置定义</div>
          <div class="value">{{ configStore.enabledDefinitions.length }}</div>
        </div>
      </div>

      <el-table :data="tasks" v-loading="taskStore.loading" stripe style="width:100%;">
        <el-table-column prop="id" label="ID" width="70" align="center" />
        <el-table-column prop="name" label="任务名称" min-width="220">
          <template #default="{row}">
            <span style="font-weight:600;">{{ row.name }}</span>
          </template>
        </el-table-column>
        <el-table-column label="场景" width="130">
          <template #default="{row}">
            <el-tag size="small" v-if="row.scenario" :type="scenarioTag(row.scenario)">
              {{ scenarioName(row.scenario) }}
            </el-tag>
            <span v-else style="color:#c0c4cc;">未选择</span>
          </template>
        </el-table-column>
        <el-table-column label="当前步骤" width="160">
          <template #default="{row}">
            <span style="font-size:13px;">{{ stepLabel(row.currentStep) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100" align="center">
          <template #default="{row}">
            <el-tag size="small" :type="statusTag(row.status)">
              {{ statusName(row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="更新时间" width="180" align="center" prop="updatedAt" />
        <el-table-column label="操作" width="240" align="center" fixed="right">
          <template #default="{row}">
            <el-button size="small" type="primary" link @click="enterTask(row)">继续工作</el-button>
            <el-button size="small" type="danger" link @click="deleteTask(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>

      <div v-if="tasks.length === 0" style="padding:40px;text-align:center;color:#909399;">
        暂无任务,点击右上角"新建任务"开始吧！
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useTaskStore } from '@/stores/task'
import { useConfigStore } from '@/stores/config'
import { stepToRoute } from '@/router'
import { ElMessage, ElMessageBox } from 'element-plus'

const taskStore = useTaskStore()
const configStore = useConfigStore()
const router = useRouter()

const tasks = computed(() => taskStore.tasks)
const inProgressCount = computed(() => tasks.value.filter(t => t.status !== 'COMPLETED').length)
const completedCount = computed(() => tasks.value.filter(t => t.status === 'COMPLETED').length)

const SN = { EXPORT: '导出配置', IMPORT: '导入配置', ADD: '新增配置', MODIFY: '修改配置' }
const ST = { EXPORT: 'success', IMPORT: 'primary', ADD: 'warning', MODIFY: 'info' }
function scenarioName(s) { return SN[s] || s }
function scenarioTag(s) { return ST[s] || '' }
const SL = { SELECT_SCENARIO: '选择场景', SELECT_DEFS: '选择配置项', VIEW_DEFS: '查看配置项',
  QUERY_COND: '配置查询', PRECHECK: '预检查', REVIEW: '复核确认', PUBLISH: '发布', RESULT: '查看结果' }
function stepLabel(s) { return SL[s] || '未开始' }
const SNAME = { DRAFT: '草稿', IN_PROGRESS: '进行中', COMPLETED: '已完成', CANCELLED: '已取消' }
const STAG = { DRAFT: 'info', IN_PROGRESS: 'warning', COMPLETED: 'success', CANCELLED: 'danger' }
function statusName(s) { return SNAME[s] || s }
function statusTag(s) { return STAG[s] || '' }

async function reload() {
  await taskStore.loadTasks()
  ElMessage.success('已刷新任务列表')
}

async function createNewTask() {
  try {
    const { value } = await ElMessageBox.prompt('请输入任务名称', '新建任务', {
      confirmButtonText: '创建',
      cancelButtonText: '取消',
      inputValue: '配置任务-' + new Date().toISOString().slice(0, 10),
      inputPattern: /.{2,}/,
      inputErrorMessage: '任务名称至少2个字符'
    })
    const t = await taskStore.createTask(value)
    ElMessage.success('任务已创建')
    router.push(stepToRoute(t.currentStep))
  } catch (e) { /* ignore cancel */ }
}

async function enterTask(row) {
  await taskStore.selectTask(row.id)
  const target = stepToRoute(row.currentStep || 'SELECT_SCENARIO')
  router.push(target)
}

async function deleteTask(row) {
  try {
    await ElMessageBox.confirm(`确认删除任务《${row.name}》?`, '确认', { type: 'warning' })
    await taskStore.remove(row.id)
    await reload()
    ElMessage.success('已删除')
  } catch (e) { /* ignore cancel */ }
}

onMounted(async () => {
  if (!configStore.definitions.length) await configStore.loadDefinitions()
  await taskStore.loadTasks()
})
</script>
