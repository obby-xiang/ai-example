<template>
  <div>
    <div class="step-desc">对上传的配置文件进行预检查，验证数据格式、必填项和依赖关系</div>

    <!-- Start check -->
    <div v-if="!currentJob" class="start-section">
      <el-empty description="点击开始检查配置文件">
        <el-button type="primary" @click="startPrecheck">
          <el-icon><CircleCheck /></el-icon> 开始预检查
        </el-button>
      </el-empty>
    </div>

    <!-- Progress -->
    <div v-else>
      <div class="job-header">
        <el-icon v-if="isRunning" class="spin"><Loading /></el-icon>
        <el-icon v-else-if="isCompleted" color="#67c23a"><CircleCheck /></el-icon>
        <el-icon v-else color="#f56c6c"><CircleClose /></el-icon>
        <span class="job-status-text">{{ statusText }}</span>
        <el-button v-if="isRunning" size="small" plain type="danger" @click="doCancel">取消</el-button>
        <el-button v-if="!isRunning" size="small" @click="startPrecheck">重新检查</el-button>
      </div>

      <el-progress :percentage="progressPct" :status="progressStatus" style="margin:12px 0" />

      <!-- Per-def summary -->
      <el-table :data="jobItems" border size="small" style="margin-bottom:16px">
        <el-table-column prop="defCode" label="配置项" width="140" />
        <el-table-column label="状态" width="100">
          <template #default="{row}">
            <el-tag :type="itemTagType(row.status)" size="small">{{ itemLabel(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="进度" min-width="160">
          <template #default="{row}">
            <el-progress :percentage="Math.round(row.processed/Math.max(1,row.total)*100)" size="small" :status="row.status==='FAILED'?'exception':row.status==='COMPLETED'?'success':undefined" />
          </template>
        </el-table-column>
        <el-table-column label="行数" width="80">
          <template #default="{row}">{{ row.processed }}/{{ row.total }}</template>
        </el-table-column>
      </el-table>

      <!-- Issues -->
      <div v-if="issues.length > 0">
        <div class="issues-header">
          <span>发现 <b style="color:#f56c6c">{{ errorCount }} 个错误</b>，{{ warningCount }} 个警告</span>
        </div>
        <el-table :data="issues" border size="small" max-height="300">
          <el-table-column prop="defCode" label="配置项" width="120" />
          <el-table-column label="严重性" width="90">
            <template #default="{row}">
              <el-tag :type="row.severity==='ERROR'?'danger':row.severity==='WARNING'?'warning':'info'" size="small">{{ row.severity }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="rowIndex" label="行号" width="70" />
          <el-table-column prop="fieldCode" label="字段" width="100" />
          <el-table-column prop="message" label="问题描述" show-overflow-tooltip />
        </el-table>
      </div>
    </div>

    <div class="wizard-footer">
      <el-button @click="$emit('back')"><el-icon class="el-icon--left"><ArrowLeft /></el-icon> 上一步</el-button>
      <el-button type="primary" :disabled="!canProceed" @click="$emit('next')">
        下一步：导入配置 <el-icon class="el-icon--right"><ArrowRight /></el-icon>
      </el-button>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onUnmounted } from 'vue'
import { createJob, getJob, cancelJob as apiCancel, getIssues } from '@/api/jobs.js'
import { ElMessage } from 'element-plus'
import { CircleCheck, CircleClose, Loading, ArrowLeft, ArrowRight } from '@element-plus/icons-vue'

const props = defineProps({ task: Object })
const emit = defineEmits(['next', 'back'])

const currentJob = ref(null)
const jobItems = ref([])
const issues = ref([])
let pollTimer = null

const isRunning = computed(() => ['PENDING','RUNNING'].includes(currentJob.value?.status))
const isCompleted = computed(() => currentJob.value?.status === 'COMPLETED')
const progressPct = computed(() => Math.round((currentJob.value?.progress||0) / Math.max(1,currentJob.value?.total||1) * 100))
const progressStatus = computed(() => currentJob.value?.status === 'FAILED' ? 'exception' : currentJob.value?.status === 'COMPLETED' ? 'success' : '')
const statusText = computed(() => ({ PENDING:'等待中…', RUNNING:'正在检查…', COMPLETED:'检查完成', FAILED:'检查发现错误', CANCELLED:'已取消' })[currentJob.value?.status] || '')
const errorCount = computed(() => issues.value.filter(i => i.severity === 'ERROR').length)
const warningCount = computed(() => issues.value.filter(i => i.severity === 'WARNING').length)
const canProceed = computed(() => isCompleted.value && errorCount.value === 0)

onUnmounted(() => { if (pollTimer) clearInterval(pollTimer) })

async function startPrecheck() {
  const res = await createJob(props.task.id, 'PRECHECK')
  currentJob.value = res.data.data
  jobItems.value = []
  issues.value = []
  startPoll()
}

function startPoll() {
  if (pollTimer) clearInterval(pollTimer)
  pollTimer = setInterval(async () => {
    const res = await getJob(currentJob.value.id)
    currentJob.value = res.data.data
    jobItems.value = res.data.data.items || []
    if (!isRunning.value) {
      clearInterval(pollTimer)
      const ir = await getIssues(currentJob.value.id)
      issues.value = ir.data.data?.content || []
      if (isCompleted.value && errorCount.value === 0) ElMessage.success('检查通过，可以继续导入')
    }
  }, 1500)
}

async function doCancel() {
  await apiCancel(currentJob.value.id)
  clearInterval(pollTimer)
}

const itemTagType = (s) => ({ COMPLETED:'success', FAILED:'danger', RUNNING:'warning', PENDING:'info' })[s] || ''
const itemLabel = (s) => ({ COMPLETED:'通过', FAILED:'有错误', RUNNING:'检查中', PENDING:'等待' })[s] || s
</script>

<style lang="scss" scoped>
.step-desc { color:var(--el-text-color-secondary); margin-bottom:16px; }
.start-section { min-height:280px; display:flex; align-items:center; justify-content:center; }
.job-header { display:flex; align-items:center; gap:10px; margin-bottom:12px; font-size:14px; }
.job-status-text { font-weight:500; }
.spin { animation:rotate 1.2s linear infinite; @keyframes rotate { to { transform:rotate(360deg); } } }
.issues-header { margin-bottom:8px; font-size:13px; }
</style>
