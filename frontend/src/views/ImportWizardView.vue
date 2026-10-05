<template>
  <div v-loading="pageLoading">
    <div class="page-head">
      <div>
        <h2 class="page-title">导入配置向导</h2>
        <div class="page-sub" v-if="task">
          {{ task.taskNo }} · {{ task.name }}
          <el-tag size="small" style="margin-left: 8px;" :type="taskStatusType">{{ taskStatusLabel }}</el-tag>
        </div>
      </div>
      <el-button @click="$router.push('/tasks')">返回任务列表</el-button>
    </div>

    <el-steps :active="step - 1" align-center finish-status="success" style="margin-bottom: 20px;">
      <el-step title="上传配置" />
      <el-step title="检查配置" />
      <el-step title="导入配置" />
      <el-step title="发布配置" />
    </el-steps>

    <!-- ============ 第 1 步：选择 + 上传/编辑 ============ -->
    <div v-show="step === 1">
      <DefSelector v-model="selectedDefs" :defs="defs" />
      <div class="upload-bar">
        <el-button size="small" :disabled="!selectedDefs.length" @click="downloadSelectedTemplates">
          下载模板（{{ selectedDefs.length > 1 ? 'zip' : 'xlsx' }}）
        </el-button>
        <el-upload
          :auto-upload="false"
          :show-file-list="false"
          accept=".xlsx,.zip"
          :on-change="handleUpload"
        >
          <el-button size="small" type="primary" :disabled="!selectedDefs.length">上传 xlsx / zip</el-button>
        </el-upload>
        <span class="upload-hint">按文件名中的配置项编码匹配（如 COUNTRY.xlsx）；zip 内可含多个 xlsx；未上传的配置项可直接在线编辑。</span>
      </div>
      <template v-if="editorDefs.length">
        <el-tabs v-model="activeEditorTab" type="border-card">
          <el-tab-pane v-for="def in editorDefs" :key="def.code" :name="def.code">
            <template #label>
              {{ def.name }}（{{ def.code }}）
              <el-tag v-if="uploadedFiles[def.code]" size="small" type="success" style="margin-left: 4px;">已上传</el-tag>
            </template>
            <SpreadSheet :ref="(el) => setSpreadRef(def.code, el)" height="380px" @data-changed="onDataChanged" />
          </el-tab-pane>
        </el-tabs>
      </template>
    </div>

    <!-- ============ 第 2 步：检查 ============ -->
    <div v-show="step === 2">
      <div class="job-bar">
        <el-button type="primary" :loading="startingKind === 'check'" :disabled="jobRunning(checkJob)"
                   @click="startCheck">启动预检查</el-button>
        <span class="job-hint">校验必填、类型、枚举与跨配置项引用；不写入任何数据。</span>
      </div>
      <JobProgress :job="checkJob" @cancel="cancelJob(checkJob)" />
      <el-alert v-if="checkJob && checkJob.status === 'SUCCESS'" type="success" :closable="false" show-icon
                style="margin-top: 12px;" title="预检查通过，可进入下一步导入。" />
      <el-alert v-else-if="checkJob && checkJob.status === 'FAILED'" type="warning" :closable="false" show-icon
                style="margin-top: 12px;" title="存在校验错误，可返回第 1 步修改数据后重新检查，或直接继续（导入时将再次校验）。" />
    </div>

    <!-- ============ 第 3 步：导入 ============ -->
    <div v-show="step === 3">
      <div class="job-bar">
        <el-button type="primary" :loading="startingKind === 'import'" :disabled="jobRunning(importJob)"
                   @click="startImport">启动导入</el-button>
        <span class="job-hint">再次校验后写入暂存区（暂不发布）；有错误的配置项其下游依赖将被跳过。</span>
      </div>
      <JobProgress :job="importJob" @cancel="cancelJob(importJob)" />
    </div>

    <!-- ============ 第 4 步：发布 ============ -->
    <div v-show="step === 4">
      <el-card shadow="never" style="margin-bottom: 14px;">
        <template #header>
          <div class="staging-head">
            <span>暂存数据核查</span>
            <el-button size="small" @click="loadStaging" :loading="stagingLoading">刷新</el-button>
          </div>
        </template>
        <el-empty v-if="!staging.length" description="暂存区暂无数据，请先完成导入" :image-size="60" />
        <el-collapse v-else v-model="stagingOpen">
          <el-collapse-item v-for="s in staging" :key="s.defCode"
                            :title="`${defName(s.defCode)}（${s.defCode}）— ${s.rowCount} 行`" :name="s.defCode">
            <el-table :data="s.rows.slice(0, 100)" size="small" border max-height="300">
              <el-table-column prop="rowNo" label="行号" width="70" />
              <el-table-column v-for="f in fieldsOf(s.defCode)" :key="f.name" :label="f.label">
                <template #default="{ row }">{{ row.data[f.name] }}</template>
              </el-table-column>
            </el-table>
            <div v-if="s.rowCount > 100" class="staging-more">仅展示前 100 行</div>
          </el-collapse-item>
        </el-collapse>
      </el-card>
      <div class="job-bar">
        <el-button type="primary" :loading="startingKind === 'publish'" :disabled="jobRunning(publishJob)"
                   @click="startPublish">确认发布</el-button>
        <span class="job-hint">发布将按配置项全量替换正式区数据，发布成功后任务完成。</span>
      </div>
      <JobProgress :job="publishJob" @cancel="cancelJob(publishJob)" />
    </div>

    <!-- ============ 步骤条 ============ -->
    <div class="wizard-footer">
      <el-button :disabled="step === 1" @click="goStep(step - 1)">上一步</el-button>
      <el-button v-if="step < 4" type="primary" @click="nextStep">下一步</el-button>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, watch, onMounted, onBeforeUnmount, nextTick } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { taskApi, configApi, jobApi } from '@/api'
import { useWorkspaceStore, registerPageHandler, unregisterPageHandler } from '@/stores/workspace'
import { pollJob } from '@/utils/job'
import { downloadTemplates, parseUpload } from '@/utils/excel-io'
import DefSelector from '@/components/DefSelector.vue'
import JobProgress from '@/components/JobProgress.vue'
import SpreadSheet from '@/components/SpreadSheet.vue'

const route = useRoute()
const ws = useWorkspaceStore()
const taskId = Number(route.params.taskId)

const pageLoading = ref(true)
const task = ref(null)
const step = ref(1)
const defs = ref([])
const selectedDefs = ref([])
const activeEditorTab = ref('')
const uploadedFiles = ref({})
const checkJob = ref(null)
const importJob = ref(null)
const publishJob = ref(null)
const startingKind = ref('')
const staging = ref([])
const stagingLoading = ref(false)
const stagingOpen = ref([])
const spreadRefs = {}
const initialized = new Set()
let stopPolling = null
let persistTimer = null
let restoring = true

const editorDefs = computed(() =>
  selectedDefs.value.map((code) => defs.value.find((d) => d.code === code)).filter(Boolean)
)
const taskStatusType = computed(() => ({ DRAFT: 'info', IN_PROGRESS: 'primary', COMPLETED: 'success', FAILED: 'danger', CANCELLED: 'warning' }[task.value?.status] || 'info'))
const taskStatusLabel = computed(() => ({ DRAFT: '草稿', IN_PROGRESS: '进行中', COMPLETED: '已完成', FAILED: '失败', CANCELLED: '已取消' }[task.value?.status] || ''))

function jobRunning(job) {
  return job && ['PENDING', 'RUNNING'].includes(job.status)
}
function defOf(code) {
  return (defs.value || []).find((d) => d.code === code) || null
}
function defName(code) {
  return defOf(code)?.name || code
}
function fieldsOf(code) {
  return defOf(code)?.fields || []
}
function setSpreadRef(code, el) {
  if (el) spreadRefs[code] = el
}

// ===================== 编辑区初始化 =====================
watch([editorDefs, step], async () => {
  if (step.value !== 1) return
  await nextTick()
  for (const def of editorDefs.value) {
    if (!initialized.has(def.code) && spreadRefs[def.code]) {
      spreadRefs[def.code].loadData(def.fields, [])
      initialized.add(def.code)
    }
  }
}, { immediate: true })

function onDataChanged() {
  ws.bumpVersion()
}

// ===================== 模板下载 / 上传 =====================
async function downloadSelectedTemplates() {
  const list = editorDefs.value
  if (!list.length) return
  await downloadTemplates(list)
}

async function handleUpload(uploadFile) {
  const file = uploadFile.raw
  if (!file) return
  if (!selectedDefs.value.length) {
    ElMessage.warning('请先选择配置项')
    return
  }
  let results
  try {
    results = await parseUpload(file, editorDefs.value)
  } catch (e) {
    ElMessage.error('文件解析失败：' + (e?.message || e))
    return
  }
  const allErrors = []
  for (const r of results) {
    if (!r.def) {
      allErrors.push(`${r.fileName}：${r.errors.join('；') || '未匹配'}`)
      continue
    }
    await nextTick()
    const ref = spreadRefs[r.def.code]
    if (ref) {
      if (!initialized.has(r.def.code)) initialized.add(r.def.code)
      ref.loadData(r.def.fields, r.rows)
      uploadedFiles.value = { ...uploadedFiles.value, [r.def.code]: r.fileName }
    }
    if (r.errors && r.errors.length) {
      allErrors.push(`${r.fileName}：${r.errors.slice(0, 5).join('；')}${r.errors.length > 5 ? ' 等' : ''}`)
    }
  }
  if (allErrors.length) {
    ElMessageBox.alert(allErrors.join('\n'), '上传解析提示', { type: 'warning' })
  } else {
    ElMessage.success('上传解析成功，已覆盖对应编辑区')
  }
  ws.bumpVersion()
}

// ===================== 持久化 =====================
function persist(immediate = false) {
  if (restoring) return
  clearTimeout(persistTimer)
  const run = async () => {
    try {
      await taskApi.saveStepData(taskId, step.value, {
        selectedDefs: [...selectedDefs.value],
        checkJobId: checkJob.value ? checkJob.value.id : null,
        importJobId: importJob.value ? importJob.value.id : null,
        publishJobId: publishJob.value ? publishJob.value.id : null
      })
    } catch (e) { /* 拦截器已提示 */ }
  }
  if (immediate) run()
  else persistTimer = setTimeout(run, 500)
}

watch(selectedDefs, () => {
  ws.selectedDefs = [...selectedDefs.value]
  persist()
})

// ===================== 步骤导航 =====================
function goStep(n) {
  step.value = n
  ws.setStep(n)
  persist(true)
  if (n === 4) loadStaging()
}

function nextStep() {
  if (step.value === 1 && !selectedDefs.value.length) {
    ElMessage.warning('请先选择至少一个配置项')
    return
  }
  goStep(step.value + 1)
}

// ===================== 作业 =====================
function collectItems() {
  return selectedDefs.value.map((code) => ({
    defCode: code,
    rows: spreadRefs[code] ? spreadRefs[code].collectRows() : []
  }))
}

function trackJob(jobRef, kind, job) {
  jobRef.value = job
  persist(true)
  stopPolling && stopPolling()
  stopPolling = pollJob(job.id, async (j) => {
    jobRef.value = j
    if (j.status === 'SUCCESS') {
      if (kind === 'publish') {
        ElMessage.success('发布成功，任务已完成')
        task.value = await taskApi.get(taskId)
      } else {
        ElMessage.success(`${{ check: '预检查', import: '导入' }[kind] || ''}作业完成`)
      }
    } else if (j.status === 'FAILED') {
      ElMessage.error('作业失败：' + (j.error || '存在校验错误，请查看明细'))
    }
  })
}

async function startCheck() {
  if (!selectedDefs.value.length) return { success: false, error: '尚未选择配置项' }
  startingKind.value = 'check'
  try {
    const job = await jobApi.startCheck(taskId, collectItems())
    trackJob(checkJob, 'check', job)
    return { success: true, data: { jobId: job.id, status: job.status } }
  } finally {
    startingKind.value = ''
  }
}

async function startImport() {
  if (!selectedDefs.value.length) return { success: false, error: '尚未选择配置项' }
  startingKind.value = 'import'
  try {
    const job = await jobApi.startImport(taskId, collectItems())
    trackJob(importJob, 'import', job)
    return { success: true, data: { jobId: job.id, status: job.status } }
  } finally {
    startingKind.value = ''
  }
}

async function startPublish() {
  startingKind.value = 'publish'
  try {
    const job = await jobApi.startPublish(taskId)
    trackJob(publishJob, 'publish', job)
    return { success: true, data: { jobId: job.id, status: job.status } }
  } finally {
    startingKind.value = ''
  }
}

async function cancelJob(job) {
  if (!job) return
  await jobApi.cancel(job.id)
  ElMessage.info('已请求取消作业')
}

// ===================== 暂存核查 =====================
async function loadStaging() {
  stagingLoading.value = true
  try {
    staging.value = await jobApi.staging(taskId)
    stagingOpen.value = staging.value.map((s) => s.defCode)
  } finally {
    stagingLoading.value = false
  }
}

// ===================== 页面 handler（供 AI 前端工具调用） =====================
function registerHandlers() {
  registerPageHandler('select_config_defs', (codes, mode) => {
    if (mode === 'ADD') {
      const set = new Set(selectedDefs.value)
      codes.forEach((c) => set.add(c))
      selectedDefs.value = [...set]
    } else {
      selectedDefs.value = [...codes]
    }
  })
  registerPageHandler('start_check', async () => await startCheck())
  registerPageHandler('start_import', async () => await startImport())
  registerPageHandler('start_publish', async () => await startPublish())
  registerPageHandler('get_workspace_state_extra', () => ({
    uploadedFiles: { ...uploadedFiles.value },
    checkStatus: checkJob.value ? checkJob.value.status : null,
    importStatus: importJob.value ? importJob.value.status : null,
    publishStatus: publishJob.value ? publishJob.value.status : null,
    stagingCount: staging.value.reduce((acc, s) => acc + s.rowCount, 0)
  }))
}

const HANDLER_NAMES = ['select_config_defs', 'start_check', 'start_import', 'start_publish', 'get_workspace_state_extra']

// ===================== 恢复运行中的作业 =====================
async function resumeJob(jobId, jobRef, kind) {
  if (!jobId) return
  try {
    const job = await jobApi.get(jobId)
    jobRef.value = job
    if (['PENDING', 'RUNNING'].includes(job.status)) {
      stopPolling && stopPolling()
      stopPolling = pollJob(job.id, async (j) => {
        jobRef.value = j
        if (j.status === 'SUCCESS' && kind === 'publish') {
          task.value = await taskApi.get(taskId)
        }
      })
    }
  } catch (e) { /* 作业可能已被删除，忽略 */ }
}

// ===================== 生命周期 =====================
onMounted(async () => {
  try {
    const [t, allDefs] = await Promise.all([taskApi.get(taskId), configApi.list()])
    task.value = t
    defs.value = allDefs
    const sd = t.stepData || {}
    selectedDefs.value = Array.isArray(sd.selectedDefs) ? [...sd.selectedDefs] : []
    step.value = Math.min(Math.max(t.currentStep || 1, 1), 4)
    ws.enterPage({ page: 'IMPORT', taskId, step: step.value, selectedDefs: selectedDefs.value })
    registerHandlers()
    restoring = false
    await resumeJob(sd.checkJobId, checkJob, 'check')
    await resumeJob(sd.importJobId, importJob, 'import')
    await resumeJob(sd.publishJobId, publishJob, 'publish')
    if (step.value === 4) loadStaging()
  } finally {
    pageLoading.value = false
  }
})

onBeforeUnmount(() => {
  stopPolling && stopPolling()
  clearTimeout(persistTimer)
  HANDLER_NAMES.forEach(unregisterPageHandler)
  ws.reset()
})
</script>

<style scoped>
.page-head {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  margin-bottom: 16px;
}
.page-title { margin: 0; font-size: 20px; color: #303133; }
.page-sub { margin-top: 4px; font-size: 13px; color: #909399; }
.upload-bar {
  display: flex;
  align-items: center;
  gap: 12px;
  margin: 12px 0;
}
.upload-hint { font-size: 12px; color: #909399; }
.job-bar {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 12px;
}
.job-hint { font-size: 12px; color: #909399; }
.staging-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.staging-more { padding: 6px 0; font-size: 12px; color: #909399; }
.wizard-footer {
  margin-top: 20px;
  padding-top: 16px;
  border-top: 1px solid #ebeef5;
  display: flex;
  justify-content: center;
  gap: 12px;
}
</style>
