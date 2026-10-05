<template>
  <div>
    <div class="step-desc">确认无误后发布配置，数据将从暂存区正式写入生产配置</div>

    <el-alert type="warning" title="发布操作不可撤销，请确认数据无误后再发布" show-icon :closable="false" style="margin-bottom:16px" />

    <div v-if="!currentJob" class="start-section">
      <div class="confirm-card">
        <h3>即将发布以下配置项</h3>
        <ul class="def-list">
          <li v-for="item in task?.items || []" :key="item.defCode">
            <el-tag size="small">{{ item.defCode }}</el-tag>
          </li>
        </ul>
        <el-button type="danger" @click="handlePublish" :loading="confirming" style="margin-top:16px">
          <el-icon><Check /></el-icon> 确认发布
        </el-button>
      </div>
    </div>

    <div v-else>
      <div class="job-header">
        <el-icon v-if="isRunning" class="spin"><Loading /></el-icon>
        <el-icon v-else-if="isCompleted" color="#67c23a"><CircleCheck /></el-icon>
        <el-icon v-else color="#f56c6c"><CircleClose /></el-icon>
        <span class="job-status-text">{{ statusText }}</span>
      </div>
      <el-progress :percentage="progressPct" :status="progressStatus" style="margin:12px 0" />

      <el-table :data="jobItems" border size="small">
        <el-table-column prop="defCode" label="配置项" width="140" />
        <el-table-column label="状态" width="100">
          <template #default="{row}">
            <el-tag :type="itemTagType(row.status)" size="small">{{ itemLabel(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="进度" min-width="160">
          <template #default="{row}">
            <el-progress :percentage="Math.round(row.processed/Math.max(1,row.total)*100)" size="small"
              :status="row.status==='FAILED'?'exception':row.status==='COMPLETED'?'success':undefined" />
          </template>
        </el-table-column>
        <el-table-column label="已发布行数" width="120">
          <template #default="{row}">{{ row.processed }}/{{ row.total }}</template>
        </el-table-column>
      </el-table>

      <el-result v-if="isCompleted" icon="success" title="发布成功" sub-title="配置数据已正式生效">
        <template #extra>
          <el-button type="primary" @click="$router.push('/tasks')">返回任务中心</el-button>
          <el-button @click="$router.push('/data')">查看配置数据</el-button>
        </template>
      </el-result>
    </div>

    <div class="wizard-footer" v-if="!isCompleted">
      <el-button @click="$emit('back')"><el-icon class="el-icon--left"><ArrowLeft /></el-icon> 上一步</el-button>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted, watch } from 'vue'
import { useRouter } from 'vue-router'
import { createJob, getJob } from '@/api/jobs.js'
import { useTaskStore } from '@/stores/task.js'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Check, Loading, CircleCheck, CircleClose, ArrowLeft } from '@element-plus/icons-vue'

const props = defineProps({ task: Object })
const emit = defineEmits(['back'])
const router = useRouter()

const taskStore = useTaskStore()
const currentJob = ref(null)
const jobItems = ref([])
const confirming = ref(false)
let pollTimer = null

const isRunning = computed(() => ['PENDING','RUNNING'].includes(currentJob.value?.status))
const isCompleted = computed(() => currentJob.value?.status === 'COMPLETED')
const progressPct = computed(() => Math.round((currentJob.value?.progress||0)/Math.max(1,currentJob.value?.total||1)*100))
const progressStatus = computed(() => currentJob.value?.status === 'FAILED' ? 'exception' : currentJob.value?.status === 'COMPLETED' ? 'success' : '')
const statusText = computed(() => ({ PENDING:'等待中…', RUNNING:'正在发布…', COMPLETED:'发布成功！', FAILED:'发布失败', CANCELLED:'已取消' })[currentJob.value?.status] || '')

onMounted(() => {
  // 认领 AI/其他页签启动的 PUBLISH 作业
  watch(() => taskStore.liveJobs, async () => {
    const evt = taskStore.latestJobEvent('PUBLISH')
    if (!evt || !evt.jobId || currentJob.value?.id === evt.jobId) return
    try {
      const res = await getJob(evt.jobId)
      currentJob.value = res.data.data
      jobItems.value = res.data.data.items || []
      if (isRunning.value) startPoll()
    } catch (e) { /* ignore */ }
  }, { deep: true })
})

onUnmounted(() => { if (pollTimer) clearInterval(pollTimer) })

async function handlePublish() {
  try {
    await ElMessageBox.confirm(
      `确定要发布 ${props.task?.items?.length || 0} 个配置项的数据吗？此操作不可撤销。`,
      '确认发布', { type: 'warning', confirmButtonText: '发布', confirmButtonClass: 'el-button--danger' }
    )
  } catch { return }
  confirming.value = true
  try {
    const res = await createJob(props.task.id, 'PUBLISH')
    currentJob.value = res.data.data
    startPoll()
  } finally { confirming.value = false }
}

function startPoll() {
  if (pollTimer) clearInterval(pollTimer)
  pollTimer = setInterval(async () => {
    const res = await getJob(currentJob.value.id)
    currentJob.value = res.data.data
    jobItems.value = res.data.data.items || []
    if (!isRunning.value) {
      clearInterval(pollTimer)
      if (isCompleted.value) ElMessage.success('🎉 配置发布成功！')
    }
  }, 1500)
}

const itemTagType = (s) => ({ COMPLETED:'success', FAILED:'danger', RUNNING:'warning', PENDING:'info' })[s] || ''
const itemLabel = (s) => ({ COMPLETED:'已发布', FAILED:'失败', RUNNING:'发布中', PENDING:'等待' })[s] || s
</script>

<style lang="scss" scoped>
.step-desc { color:var(--el-text-color-secondary); margin-bottom:16px; }
.start-section { min-height:280px; display:flex; align-items:center; justify-content:center; }
.confirm-card { text-align:center; h3 { font-size:16px; margin-bottom:16px; } }
.def-list { list-style:none; display:flex; flex-wrap:wrap; gap:8px; justify-content:center; }
.job-header { display:flex; align-items:center; gap:10px; margin-bottom:12px; font-size:14px; }
.job-status-text { font-weight:500; }
.spin { animation:rotate 1.2s linear infinite; @keyframes rotate { to { transform:rotate(360deg); } } }
</style>
