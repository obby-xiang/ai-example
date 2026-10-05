<template>
  <div v-loading="pageLoading">
    <div class="page-head">
      <div>
        <h2 class="page-title">导出配置向导</h2>
        <div class="page-sub" v-if="task">
          {{ task.taskNo }} · {{ task.name }}
          <el-tag size="small" style="margin-left: 8px;" :type="taskStatusType">{{ taskStatusLabel }}</el-tag>
        </div>
      </div>
      <el-button @click="$router.push('/tasks')">返回任务列表</el-button>
    </div>

    <el-steps :active="step - 1" align-center finish-status="success" style="margin-bottom: 20px;">
      <el-step title="选择配置" />
      <el-step title="查询配置" />
      <el-step title="导出配置" />
    </el-steps>

    <!-- ============ 第 1 步：选择配置项 ============ -->
    <div v-show="step === 1">
      <DefSelector v-model="selectedDefs" :defs="defs" />
      <div class="step-tip">已选 {{ selectedDefs.length }} 个配置项；存在依赖关系的配置项将按依赖顺序导出。</div>
    </div>

    <!-- ============ 第 2 步：查询条件 ============ -->
    <div v-show="step === 2">
      <el-empty v-if="!selectedDefs.length" description="请先在第 1 步选择配置项" />
      <el-card v-for="code in selectedDefs" :key="code" class="cond-card" shadow="never">
        <template #header>
          <div class="cond-head">
            <span class="cond-title">{{ defName(code) }}（{{ code }}）</span>
            <el-button size="small" @click="previewCount(code)" :loading="countLoading[code]">预览行数</el-button>
            <el-tag v-if="counts[code] !== undefined && counts[code] !== null" size="small" type="success">
              命中 {{ counts[code] }} 行
            </el-tag>
          </div>
        </template>
        <div v-for="(cond, idx) in condForms[code] || []" :key="idx" class="cond-row">
          <el-select v-model="cond.field" placeholder="字段" style="width: 180px;" @change="cond.value = ''; cond.value2 = ''">
            <el-option v-for="f in fieldsOf(code)" :key="f.name" :label="`${f.label}（${f.name}）`" :value="f.name" />
          </el-select>
          <el-select v-model="cond.op" placeholder="操作符" style="width: 130px;">
            <el-option v-for="op in OPS" :key="op" :label="opLabel(op)" :value="op" />
          </el-select>
          <template v-if="fieldOf(code, cond.field)?.type === 'ENUM'">
            <el-select v-model="cond.value" placeholder="选项" style="width: 180px;" clearable>
              <el-option v-for="o in fieldOf(code, cond.field)?.options || []" :key="o" :label="o" :value="o" />
            </el-select>
          </template>
          <template v-else-if="fieldOf(code, cond.field)?.type === 'BOOLEAN'">
            <el-select v-model="cond.value" placeholder="取值" style="width: 180px;" clearable>
              <el-option label="true" value="true" />
              <el-option label="false" value="false" />
            </el-select>
          </template>
          <template v-else-if="fieldOf(code, cond.field)?.type === 'DATE'">
            <el-date-picker v-model="cond.value" type="date" value-format="YYYY-MM-DD" placeholder="日期" style="width: 180px;" />
          </template>
          <template v-else>
            <el-input v-model="cond.value" :placeholder="cond.op === 'IN' ? '多个值用英文逗号分隔' : '值'" style="width: 180px;" />
          </template>
          <el-input v-if="cond.op === 'BETWEEN'" v-model="cond.value2" placeholder="至" style="width: 140px;" />
          <el-button :icon="Delete" circle size="small" @click="removeCond(code, idx)" />
        </div>
        <el-button size="small" :icon="Plus" @click="addCond(code)">添加条件</el-button>
        <span class="cond-hint">不设置条件表示导出全部数据</span>
      </el-card>
    </div>

    <!-- ============ 第 3 步：导出 ============ -->
    <div v-show="step === 3">
      <div class="export-bar">
        <el-button type="primary" :loading="starting" :disabled="jobRunning"
                   @click="startExport">
          {{ exportJob ? '重新导出' : '开始导出' }}
        </el-button>
        <template v-if="exportResults.length">
          <el-divider direction="vertical" />
          <el-button size="small" @click="downloadOne(activeTab)">下载当前配置项 xlsx</el-button>
          <el-button size="small" :disabled="!checkedDefs.length" @click="downloadChecked">
            打包下载勾选（{{ checkedDefs.length }}）
          </el-button>
          <el-button size="small" @click="downloadAll">全部打包 zip</el-button>
        </template>
      </div>

      <JobProgress :job="exportJob" @cancel="cancelJob" />

      <template v-if="exportResults.length">
        <el-alert type="info" :closable="false" show-icon style="margin: 12px 0;"
                  title="可在线编辑表格内容；编辑只影响下载的文件，不会回写数据库。" />
        <el-checkbox-group v-model="checkedDefs" style="margin-bottom: 8px;">
          <el-checkbox v-for="it in exportResults" :key="it.defCode" :value="it.defCode">
            {{ it.defCode }}
          </el-checkbox>
        </el-checkbox-group>
        <el-tabs v-model="activeTab" type="border-card">
          <el-tab-pane v-for="it in exportResults" :key="it.defCode" :name="it.defCode"
                       :label="`${defName(it.defCode)}（${it.rowCount} 行）`">
            <SpreadSheet :ref="(el) => setSpreadRef(it.defCode, el)" height="440px" />
          </el-tab-pane>
        </el-tabs>
      </template>
    </div>

    <!-- ============ 步骤条 ============ -->
    <div class="wizard-footer">
      <el-button :disabled="step === 1" @click="goStep(step - 1)">上一步</el-button>
      <el-button v-if="step < 3" type="primary" @click="nextStep">下一步</el-button>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, watch, onMounted, onBeforeUnmount, nextTick } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Plus, Delete } from '@element-plus/icons-vue'
import { taskApi, configApi, jobApi } from '@/api'
import { useWorkspaceStore, registerPageHandler, unregisterPageHandler } from '@/stores/workspace'
import { pollJob } from '@/utils/job'
import { downloadExportFiles } from '@/utils/excel-io'
import DefSelector from '@/components/DefSelector.vue'
import JobProgress from '@/components/JobProgress.vue'
import SpreadSheet from '@/components/SpreadSheet.vue'

const OPS = ['EQ', 'NE', 'LIKE', 'GT', 'GE', 'LT', 'LE', 'BETWEEN', 'IN']

const route = useRoute()
const ws = useWorkspaceStore()
const taskId = Number(route.params.taskId)

const pageLoading = ref(true)
const task = ref(null)
const step = ref(1)
const defs = ref([])
const selectedDefs = ref([])
const condForms = ref({}) // { code: [{field, op, value, value2}] }
const counts = ref({})
const countLoading = ref({})
const starting = ref(false)
const exportJob = ref(null)
const exportResults = ref([])
const activeTab = ref('')
const checkedDefs = ref([])
const spreadRefs = {}
let stopPolling = null
let persistTimer = null
let restoring = true

const jobRunning = computed(() => exportJob.value && ['PENDING', 'RUNNING'].includes(exportJob.value.status))
const taskStatusType = computed(() => ({ DRAFT: 'info', IN_PROGRESS: 'primary', COMPLETED: 'success', FAILED: 'danger', CANCELLED: 'warning' }[task.value?.status] || 'info'))
const taskStatusLabel = computed(() => ({ DRAFT: '草稿', IN_PROGRESS: '进行中', COMPLETED: '已完成', FAILED: '失败', CANCELLED: '已取消' }[task.value?.status] || ''))

function opLabel(op) {
  return { EQ: '等于', NE: '不等于', LIKE: '包含', GT: '大于', GE: '大于等于', LT: '小于', LE: '小于等于', BETWEEN: '介于', IN: '在列表中' }[op] || op
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
function fieldOf(code, fieldName) {
  return fieldsOf(code).find((f) => f.name === fieldName) || null
}

// ===================== 条件表单 ⇄ API 结构 =====================
function toApiConditions(code) {
  const out = []
  for (const c of condForms.value[code] || []) {
    if (!c.field || !c.op) continue
    if (c.op !== 'NE' && (c.value === '' || c.value === null || c.value === undefined)) continue
    const field = fieldOf(code, c.field)
    let value = c.value
    if (c.op === 'IN') {
      value = String(c.value).split(',').map((s) => s.trim()).filter(Boolean)
      if (!value.length) continue
    } else if (field && field.type === 'NUMBER' && value !== '') {
      const n = Number(value)
      if (!Number.isNaN(n)) value = n
    }
    const cond = { field: c.field, op: c.op, value }
    if (c.op === 'BETWEEN') {
      if (c.value2 === '' || c.value2 === null || c.value2 === undefined) continue
      cond.value2 = c.value2
    }
    out.push(cond)
  }
  return out
}

function fromApiConditions(list) {
  return (list || []).map((c) => ({
    field: c.field,
    op: c.op,
    value: Array.isArray(c.value) ? c.value.join(',') : (c.value ?? ''),
    value2: c.value2 ?? ''
  }))
}

function allApiConditions() {
  const out = {}
  for (const code of selectedDefs.value) out[code] = toApiConditions(code)
  return out
}

// ===================== 条件行操作 =====================
function addCond(code) {
  if (!condForms.value[code]) condForms.value[code] = []
  condForms.value[code].push({ field: '', op: 'EQ', value: '', value2: '' })
}
function removeCond(code, idx) {
  condForms.value[code].splice(idx, 1)
}

async function previewCount(code) {
  countLoading.value = { ...countLoading.value, [code]: true }
  try {
    const resp = await configApi.query(code, toApiConditions(code), 0, 1)
    counts.value = { ...counts.value, [code]: resp.total }
  } finally {
    countLoading.value = { ...countLoading.value, [code]: false }
  }
}

// ===================== 持久化 =====================
function persist(immediate = false) {
  if (restoring) return
  clearTimeout(persistTimer)
  const run = async () => {
    try {
      await taskApi.saveStepData(taskId, step.value, {
        selectedDefs: [...selectedDefs.value],
        queryConditions: allApiConditions(),
        exportJobId: exportJob.value ? exportJob.value.id : null
      })
    } catch (e) { /* 拦截器已提示 */ }
  }
  if (immediate) run()
  else persistTimer = setTimeout(run, 500)
}

watch([selectedDefs, condForms], () => {
  ws.selectedDefs = [...selectedDefs.value]
  ws.queryConditions = allApiConditions()
  persist()
}, { deep: true })

// ===================== 步骤导航 =====================
function goStep(n) {
  step.value = n
  ws.setStep(n)
  persist(true)
}

function nextStep() {
  if (step.value === 1 && !selectedDefs.value.length) {
    ElMessage.warning('请先选择至少一个配置项')
    return
  }
  goStep(step.value + 1)
}

// ===================== 导出作业 =====================
async function startExport() {
  if (!selectedDefs.value.length) {
    ElMessage.warning('请先选择配置项')
    return { success: false, error: '尚未选择配置项' }
  }
  if (step.value < 3) goStep(3)
  starting.value = true
  try {
    const items = selectedDefs.value.map((code) => ({ defCode: code, conditions: toApiConditions(code) }))
    const job = await jobApi.startExport(taskId, items)
    exportJob.value = job
    exportResults.value = []
    persist(true)
    beginPolling(job.id)
    return { success: true, data: { jobId: job.id, status: job.status } }
  } finally {
    starting.value = false
  }
}

function beginPolling(jobId) {
  stopPolling && stopPolling()
  stopPolling = pollJob(jobId, async (job) => {
    exportJob.value = job
    if (job.status === 'SUCCESS') {
      await loadResults(job.id)
      ElMessage.success('导出完成')
    } else if (job.status === 'FAILED') {
      ElMessage.error('导出失败：' + (job.error || '未知错误'))
    }
  })
}

async function loadResults(jobId) {
  exportResults.value = await jobApi.exportResults(jobId)
  if (exportResults.value.length && !exportResults.value.some((it) => it.defCode === activeTab.value)) {
    activeTab.value = exportResults.value[0].defCode
  }
  await nextTick()
  for (const it of exportResults.value) {
    const ref = spreadRefs[it.defCode]
    if (ref) ref.loadData(it.fields, it.rows)
  }
}

async function cancelJob() {
  if (!exportJob.value) return
  await jobApi.cancel(exportJob.value.id)
  ElMessage.info('已请求取消作业')
}

// ===================== 下载 =====================
function setSpreadRef(code, el) {
  if (el) spreadRefs[code] = el
}

function collectItems() {
  return exportResults.value.map((it) => ({
    defCode: it.defCode,
    fileName: it.fileName,
    fields: it.fields,
    rows: spreadRefs[it.defCode] ? spreadRefs[it.defCode].collectRows() : it.rows
  }))
}

async function doDownload(codes) {
  if (!exportResults.value.length) return { success: false, error: '尚无导出结果，请先完成导出' }
  const { fileName } = await downloadExportFiles(collectItems(), codes)
  return { success: true, data: { fileName } }
}

async function downloadOne(code) {
  if (!code) return
  await doDownload([code])
}
async function downloadChecked() {
  await doDownload([...checkedDefs.value])
}
async function downloadAll() {
  await doDownload(null)
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
  registerPageHandler('set_query_conditions', (defCode, conditions) => {
    condForms.value = { ...condForms.value, [defCode]: fromApiConditions(conditions) }
  })
  registerPageHandler('start_export', async () => await startExport())
  registerPageHandler('download_export_files', async (codes) => await doDownload(codes))
  registerPageHandler('get_workspace_state_extra', () => ({
    conditions: allApiConditions(),
    jobStatus: exportJob.value ? exportJob.value.status : null,
    resultDefs: exportResults.value.map((it) => ({ defCode: it.defCode, rowCount: it.rowCount }))
  }))
}

const HANDLER_NAMES = ['select_config_defs', 'set_query_conditions', 'start_export', 'download_export_files', 'get_workspace_state_extra']

// ===================== 生命周期 =====================
onMounted(async () => {
  try {
    const [t, allDefs] = await Promise.all([taskApi.get(taskId), configApi.list()])
    task.value = t
    defs.value = allDefs
    const sd = t.stepData || {}
    selectedDefs.value = Array.isArray(sd.selectedDefs) ? [...sd.selectedDefs] : []
    const forms = {}
    for (const code of selectedDefs.value) {
      forms[code] = fromApiConditions((sd.queryConditions || {})[code])
    }
    condForms.value = forms
    step.value = Math.min(Math.max(t.currentStep || 1, 1), 3)
    ws.enterPage({
      page: 'EXPORT',
      taskId,
      step: step.value,
      selectedDefs: selectedDefs.value,
      queryConditions: allApiConditions()
    })
    registerHandlers()
    restoring = false
    // 恢复未完成的导出作业 / 已完成结果
    if (sd.exportJobId) {
      try {
        const job = await jobApi.get(sd.exportJobId)
        exportJob.value = job
        if (['PENDING', 'RUNNING'].includes(job.status)) {
          beginPolling(job.id)
        } else if (job.status === 'SUCCESS') {
          await loadResults(job.id)
        }
      } catch (e) { /* 作业可能已被删除，忽略 */ }
    }
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
.step-tip { margin-top: 10px; font-size: 13px; color: #909399; }
.cond-card { margin-bottom: 12px; }
.cond-head { display: flex; align-items: center; gap: 12px; }
.cond-title { font-weight: 600; margin-right: auto; }
.cond-row { display: flex; gap: 8px; margin-bottom: 8px; align-items: center; }
.cond-hint { margin-left: 10px; font-size: 12px; color: #c0c4cc; }
.export-bar { display: flex; align-items: center; margin-bottom: 12px; }
.wizard-footer {
  margin-top: 20px;
  padding-top: 16px;
  border-top: 1px solid #ebeef5;
  display: flex;
  justify-content: center;
  gap: 12px;
}
</style>
