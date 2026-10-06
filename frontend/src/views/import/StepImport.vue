<template>
  <div>
    <div class="mb-4 text-sm text-slate-500">执行数据导入，将文件内容写入暂存区，可在发布前预览变更</div>

    <div v-if="!currentJob" class="flex min-h-[280px] items-center justify-center">
      <el-empty description="预检查通过后，点击开始导入">
        <el-button type="primary" @click="startImport">
          <el-icon><Upload /></el-icon> 开始导入
        </el-button>
      </el-empty>
    </div>

    <div v-else>
      <div class="mb-3 flex items-center gap-2.5 text-sm">
        <el-icon v-if="isRunning" class="animate-spin"><Loading /></el-icon>
        <el-icon v-else-if="isCompleted" color="#67c23a"><CircleCheck /></el-icon>
        <el-icon v-else color="#f56c6c"><CircleClose /></el-icon>
        <span class="job-status-text">{{ statusText }}</span>
        <el-button v-if="!isRunning" size="small" @click="startImport">重新导入</el-button>
      </div>
      <el-progress :percentage="progressPct" :status="progressStatus" style="margin:12px 0" />

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
        <el-table-column label="行数" width="100">
          <template #default="{row}">{{ row.processed }}/{{ row.total }}</template>
        </el-table-column>
      </el-table>

      <!-- Diff preview -->
      <div v-if="isCompleted && diffRows.length > 0">
        <div class="mb-2 text-[13px] font-medium">
          <span>暂存区数据预览（共 {{ diffRows.length }} 行）</span>
        </div>
        <el-table :data="diffRows.slice(0, 100)" border size="small" max-height="280">
          <el-table-column prop="defCode" label="配置项" width="120" />
          <el-table-column label="操作类型" width="90">
            <template #default="{row}">
              <el-tag :type="row.opType === 'UPSERT' ? 'success' : 'danger'" size="small">{{ row.opType === 'UPSERT' ? '新增/更新' : '删除' }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="rowKey" label="主键" width="180" show-overflow-tooltip />
          <el-table-column label="数据" show-overflow-tooltip>
            <template #default="{row}">{{ row.dataJson }}</template>
          </el-table-column>
        </el-table>
        <div v-if="diffRows.length > 100" class="mt-1.5 text-xs text-slate-500">仅显示前 100 行，共 {{ diffRows.length }} 行</div>
      </div>
    </div>

    <div class="wizard-footer">
      <el-button @click="$emit('back')"><el-icon class="el-icon--left"><ArrowLeft /></el-icon> 上一步</el-button>
      <el-button type="primary" :disabled="!canProceed" @click="$emit('next')">
        下一步：发布配置 <el-icon class="el-icon--right"><ArrowRight /></el-icon>
      </el-button>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted, watch } from 'vue'
import { createJob, getJob, getDiff } from '@/api/jobs.js'
import { useTaskStore } from '@/stores/task.js'
import { ElMessage } from 'element-plus'
import { Upload, Loading, CircleCheck, CircleClose, ArrowLeft, ArrowRight } from '@element-plus/icons-vue'

const props = defineProps({ task: Object })
const emit = defineEmits(['next', 'back'])

const taskStore = useTaskStore()
const currentJob = ref(null)
const jobItems = ref([])
const diffRows = ref([])
let pollTimer = null

const isRunning = computed(() => ['PENDING','RUNNING'].includes(currentJob.value?.status))
const isCompleted = computed(() => currentJob.value?.status === 'COMPLETED')
const progressPct = computed(() => Math.round((currentJob.value?.progress||0)/Math.max(1,currentJob.value?.total||1)*100))
const progressStatus = computed(() => currentJob.value?.status === 'FAILED' ? 'exception' : currentJob.value?.status === 'COMPLETED' ? 'success' : '')
const statusText = computed(() => ({ PENDING:'等待中…', RUNNING:'正在导入…', COMPLETED:'导入完成，数据已写入暂存区', FAILED:'导入失败', CANCELLED:'已取消' })[currentJob.value?.status] || '')
const canProceed = computed(() => isCompleted.value)

onMounted(() => {
  // 认领 AI/其他页签启动的 IMPORT 作业
  watch(() => taskStore.liveJobs, async () => {
    const evt = taskStore.latestJobEvent('IMPORT')
    if (!evt || !evt.jobId || currentJob.value?.id === evt.jobId) return
    try {
      const res = await getJob(evt.jobId)
      currentJob.value = res.data.data
      jobItems.value = res.data.data.items || []
      if (isRunning.value) startPoll()
      else if (isCompleted.value) {
        const dr = await getDiff(evt.jobId)
        diffRows.value = dr.data.data || []
      }
    } catch (e) { /* ignore */ }
  }, { deep: true })
})

onUnmounted(() => { if (pollTimer) clearInterval(pollTimer) })

async function startImport() {
  const res = await createJob(props.task.id, 'IMPORT')
  currentJob.value = res.data.data
  jobItems.value = []; diffRows.value = []
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
      if (isCompleted.value) {
        const dr = await getDiff(currentJob.value.id)
        diffRows.value = dr.data.data || []
        ElMessage.success('导入完成，请预览变更后点击发布')
      }
    }
  }, 1500)
}

const itemTagType = (s) => ({ COMPLETED:'success', FAILED:'danger', RUNNING:'warning', PENDING:'info' })[s] || ''
const itemLabel = (s) => ({ COMPLETED:'已导入', FAILED:'失败', RUNNING:'导入中', PENDING:'等待' })[s] || s
</script>
