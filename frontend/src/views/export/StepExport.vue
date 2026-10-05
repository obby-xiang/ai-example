<template>
  <div>
    <div class="step-desc">配置导出结果，可在线预览编辑，也可下载文件</div>

    <!-- Job progress -->
    <div v-if="currentJob && isRunning" class="job-progress-section">
      <div class="job-status-header">
        <el-icon class="spin"><Loading /></el-icon>
        <span>正在导出，请稍候…</span>
        <el-button size="small" type="danger" plain @click="cancelJob">取消</el-button>
      </div>
      <el-progress :percentage="Math.round(currentJob.progress / Math.max(1, currentJob.total) * 100)" />
      <div class="job-items">
        <div v-for="item in currentJob.items || []" :key="item.defCode" class="job-item">
          <span class="job-item-code">{{ item.defCode }}</span>
          <el-progress :percentage="Math.round(item.processed / Math.max(1, item.total) * 100)" :status="itemStatus(item.status)" class="item-progress" />
          <span class="job-item-count">{{ item.processed }}/{{ item.total }}</span>
        </div>
      </div>
    </div>

    <!-- Results table -->
    <div v-if="!isRunning && files.length > 0" class="results-section">
      <div class="results-header">
        <span class="results-count">共 {{ files.length }} 个文件</span>
        <div class="results-actions">
          <el-button size="small" @click="downloadChecked" :disabled="checkedCodes.length === 0">
            下载已选（{{ checkedCodes.length }}）
          </el-button>
          <el-button size="small" type="primary" @click="downloadAll">全部下载</el-button>
        </div>
      </div>
      <el-table :data="files" @selection-change="handleSelection" border>
        <el-table-column type="selection" width="50" />
        <el-table-column prop="defCode" label="配置编码" width="140" />
        <el-table-column prop="fileName" label="文件名" show-overflow-tooltip />
        <el-table-column prop="rowCount" label="行数" width="80" />
        <el-table-column label="操作" width="200">
          <template #default="{ row }">
            <el-button size="small" type="primary" link @click="openEditor(row)">在线编辑</el-button>
            <el-button size="small" link @click="downloadSingle(row)">下载</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <!-- No job yet -->
    <div v-else-if="!isRunning && !hasCompleted" class="start-section">
      <el-empty description="点击开始导出">
        <el-button type="primary" @click="startExport">
          <el-icon><Download /></el-icon> 开始导出
        </el-button>
      </el-empty>
    </div>

    <!-- SpreadJS Editor Dialog -->
    <el-dialog v-model="editorVisible" :title="editorTitle" width="90%" fullscreen>
      <SpreadJSEditor v-if="editorVisible" :taskId="task?.id" :defCode="editorDefCode" @saved="onFileSaved" />
      <template #footer>
        <el-button @click="editorVisible = false">关闭</el-button>
      </template>
    </el-dialog>

    <div class="wizard-footer">
      <el-button @click="$emit('back')">
        <el-icon class="el-icon--left"><ArrowLeft /></el-icon> 上一步
      </el-button>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { createJob, getJob, cancelJob as apiCancelJob } from '@/api/jobs.js'
import { getFiles, downloadFile, downloadAllFiles, downloadTemplates } from '@/api/tasks.js'
import { ElMessage } from 'element-plus'
import { Loading, Download, ArrowLeft } from '@element-plus/icons-vue'
import SpreadJSEditor from '@/components/SpreadJSEditor/SpreadJSEditor.vue'

const props = defineProps({ task: Object })
const emit = defineEmits(['back'])

const currentJob = ref(null)
const files = ref([])
const checkedCodes = ref([])
const editorVisible = ref(false)
const editorDefCode = ref('')
const editorTitle = ref('')
let pollTimer = null

const isRunning = computed(() => currentJob.value?.status === 'RUNNING' || currentJob.value?.status === 'PENDING')
const hasCompleted = computed(() => currentJob.value?.status === 'COMPLETED')

onMounted(async () => {
  await loadFiles()
})

onUnmounted(() => { if (pollTimer) clearInterval(pollTimer) })

async function loadFiles() {
  if (!props.task?.id) return
  const res = await getFiles(props.task.id)
  files.value = (res.data.data || []).filter(f => f.fileType === 'EXPORT')
}

async function startExport() {
  const res = await createJob(props.task.id, 'EXPORT')
  currentJob.value = res.data.data
  startPolling()
}

function startPolling() {
  if (pollTimer) clearInterval(pollTimer)
  pollTimer = setInterval(async () => {
    if (!currentJob.value) return
    const res = await getJob(currentJob.value.id)
    currentJob.value = res.data.data
    if (!isRunning.value) {
      clearInterval(pollTimer)
      await loadFiles()
      if (hasCompleted.value) ElMessage.success('导出完成！')
    }
  }, 1500)
}

async function cancelJob() {
  if (currentJob.value) await apiCancelJob(currentJob.value.id)
  clearInterval(pollTimer)
}

function handleSelection(rows) {
  checkedCodes.value = rows.map(r => r.defCode)
}

function openEditor(file) {
  editorDefCode.value = file.defCode
  editorTitle.value = `在线编辑：${file.fileName}`
  editorVisible.value = true
}

async function downloadSingle(file) {
  const res = await downloadFile(props.task.id, file.defCode)
  triggerDownload(res.data, file.fileName)
}

async function downloadAll() {
  const res = await downloadAllFiles(props.task.id)
  triggerDownload(res.data, `export-${props.task.id}.zip`)
}

async function downloadChecked() {
  const res = await downloadTemplates(props.task.id, checkedCodes.value)
  triggerDownload(res.data, `export-selected.zip`)
}

function triggerDownload(blob, filename) {
  const url = URL.createObjectURL(blob instanceof Blob ? blob : new Blob([blob]))
  const a = document.createElement('a'); a.href = url; a.download = filename; a.click()
  URL.revokeObjectURL(url)
}

async function onFileSaved() {
  editorVisible.value = false
  await loadFiles()
  ElMessage.success('文件已保存')
}

const itemStatus = (s) => ({ COMPLETED: 'success', FAILED: 'exception' })[s] || ''
</script>

<style lang="scss" scoped>
.step-desc { color: var(--el-text-color-secondary); margin-bottom: 16px; }
.job-progress-section { margin-bottom: 24px; }
.job-status-header { display: flex; align-items: center; gap: 10px; margin-bottom: 12px; }
.spin { animation: rotate 1.2s linear infinite; @keyframes rotate { to { transform: rotate(360deg); } } }
.job-items { display: flex; flex-direction: column; gap: 8px; margin-top: 12px; }
.job-item { display: flex; align-items: center; gap: 8px; }
.job-item-code { width: 120px; font-size: 12px; flex-shrink: 0; }
.item-progress { flex: 1; }
.job-item-count { width: 80px; font-size: 12px; text-align: right; flex-shrink: 0; }
.results-section { margin-bottom: 16px; }
.results-header { display: flex; align-items: center; justify-content: space-between; margin-bottom: 12px; }
.results-count { font-size: 14px; }
.results-actions { display: flex; gap: 8px; }
.start-section { min-height: 300px; display: flex; align-items: center; justify-content: center; }
</style>
