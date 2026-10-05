<template>
  <div>
    <div class="page-card">
      <el-steps :active="store.step - 1" align-center class="wizard-steps" @click="onStepClick">
        <el-step v-for="(s, i) in importSteps" :key="i"
                 :title="s.title" :description="s.desc" :status="importStepStatus[i]"
                 :class="importStepClass(i)" />
      </el-steps>
    </div>

    <!-- 第一步：选择配置 / 模板 / 上传 / 在线编辑 -->
    <div class="page-card" v-if="store.step === 1">
      <div class="step-toolbar">
        <el-input v-model="keyword" placeholder="按名称/编码过滤" clearable style="width: 220px" size="small" />
        <el-select v-model="levelFilter" placeholder="层级" clearable style="width: 120px" size="small">
          <el-option label="全局" value="GLOBAL" />
          <el-option label="地区" value="REGION" />
          <el-option label="项目" value="PROJECT" />
        </el-select>
        <el-button size="small" :disabled="!store.selectedDefs.length" @click="downloadTemplates(false)">
          下载模板（单个）
        </el-button>
        <el-button size="small" :disabled="!store.selectedDefs.length" @click="downloadTemplates(true)">
          下载模板（zip）
        </el-button>
      </div>

      <el-table :data="filteredDefs" size="small" border @selection-change="onSelectionChange">
        <el-table-column type="selection" width="46" />
        <el-table-column prop="code" label="配置编码" width="150">
          <template #default="{ row }"><span class="mono">{{ row.code }}</span></template>
        </el-table-column>
        <el-table-column prop="name" label="配置名称" width="170" />
        <el-table-column label="层级" width="80">
          <template #default="{ row }">
            <el-tag size="small">{{ levelLabel(row.level) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="依赖（导入顺序）" width="170">
          <template #default="{ row }">
            <span class="text-muted">{{ row.dependsOn.length ? row.dependsOn.join(', ') : '无' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="字段（表头）" min-width="240">
          <template #default="{ row }">
            <span class="text-muted">{{ row.fields.map(f => f.label).join('、') }}</span>
          </template>
        </el-table-column>
      </el-table>

      <el-divider content-position="left">文件上传（按文件名匹配配置编码，如 SERVER_PARAM.xlsx；支持 zip）</el-divider>
      <el-upload drag multiple :auto-upload="false" :show-file-list="false"
                 accept=".xlsx,.xls,.zip" :on-change="onFileChange" style="max-width: 620px">
        <div class="el-upload__text">拖拽文件到此处，或 <em>点击选择</em>（单个 xlsx / 多个文件 zip）</div>
      </el-upload>
      <div v-if="pendingFiles.length" class="pending-files">
        <el-tag v-for="f in pendingFiles" :key="f.uid" closable @close="removeFile(f)">{{ f.name }}</el-tag>
        <el-button size="small" type="primary" :loading="uploading" @click="doUpload">上传到批次</el-button>
      </div>

      <el-divider content-position="left">批次文件状态（可在线编辑后覆盖上传）</el-divider>
      <div class="step-toolbar">
        <el-button size="small" type="primary" :disabled="!store.selectedDefs.length"
                   @click="ensureBatch">创建/复用导入批次</el-button>
        <el-button size="small" @click="refreshBatch" :disabled="!store.batch">刷新</el-button>
        <el-button size="small" @click="store.goStep(2)" :disabled="!canProceed">
          下一步：检查配置
        </el-button>
      </div>
      <div v-if="store.uploadReport" class="upload-report">
        <el-alert type="success" :closable="false" show-icon
                  :title="`已匹配 ${store.uploadReport.matched.length} 个文件` +
                    (store.uploadReport.unmatched.length ? `，未匹配 ${store.uploadReport.unmatched.length} 个：` +
                      store.uploadReport.unmatched.join('、') : '')" />
      </div>
      <FileStatusTable v-if="store.entries.length" :entries="store.entries" :batch-id="store.batch && store.batch.id" />

      <div v-if="store.batch && store.selectedDefs.length" class="edit-row">
        <div v-for="code in store.selectedDefs" :key="code" class="edit-cell">
          <span class="mono">{{ code }}</span>
          <el-button size="small" text type="primary" @click="openEditor(code)">在线编辑</el-button>
        </div>
        <span class="text-muted">在线编辑后保存并上传即可覆盖原文件</span>
      </div>
    </div>

    <!-- 第二步：检查 -->
    <div class="page-card" v-if="store.step === 2">
      <div class="step-toolbar">
        <el-button size="small" @click="store.goStep(1)">上一步</el-button>
        <el-button size="small" type="primary" :loading="acting" @click="doCheck">开始检查</el-button>
        <el-button size="small" @click="store.refresh()">刷新</el-button>
        <span class="text-muted">按依赖拓扑顺序检查（被依赖者先检查）；引用字段校验对已发布数据与本批次前序文件</span>
      </div>
      <TaskProgress :snapshot="store.batch || {}" />
      <FileStatusTable v-if="store.entries.length" :entries="store.entries" :batch-id="store.batch && store.batch.id" />
      <div class="step-toolbar" style="margin-top: 14px">
        <el-button type="primary" size="small" :disabled="!checkPassed" @click="store.goStep(3)">
          检查通过，下一步：导入配置
        </el-button>
        <span v-if="!checkPassed && store.batch && ['CHECKED', 'FAILED'].includes(store.batch.status)"
              class="text-muted" style="color: #e6a23c">存在错误，请修正文件后重新上传再检查</span>
      </div>
    </div>

    <!-- 第三步：导入 -->
    <div class="page-card" v-if="store.step === 3">
      <div class="step-toolbar">
        <el-button size="small" @click="store.goStep(2)">上一步</el-button>
        <el-button size="small" type="primary" :loading="acting" @click="doImport">开始导入</el-button>
        <el-button size="small" @click="store.refresh()">刷新</el-button>
        <span class="text-muted">导入前会再次检查；通过的文件入库为草稿（未发布），不影响线上生效数据</span>
      </div>
      <TaskProgress :snapshot="store.batch || {}" />
      <FileStatusTable v-if="store.entries.length" :entries="store.entries" :batch-id="store.batch && store.batch.id" />
      <div v-if="store.batch && store.batch.status === 'IMPORTED'" class="draft-preview">
        <el-alert type="info" :closable="false" show-icon
                  title="导入完成（未发布）。可在下方预览草稿数据，核查无误后进入发布步骤。" />
        <el-table :data="draftRows" size="small" border style="margin-top: 8px" max-height="260">
          <el-table-column label="范围" width="110" v-if="hasScope">
            <template #default="{ row }">{{ row.scope }}</template>
          </el-table-column>
          <el-table-column v-for="f in draftDefFields" :key="f.code" :label="f.label" :prop="'data.' + f.code"
                           min-width="120" show-overflow-tooltip />
        </el-table>
        <div class="step-toolbar" style="margin-top: 10px">
          <el-select v-model="draftDef" size="small" style="width: 220px" @change="loadDraft">
            <el-option v-for="c in store.selectedDefs" :key="c" :label="defTitle(c)" :value="c" />
          </el-select>
          <el-button type="primary" size="small" @click="store.goStep(4)">核查无误，下一步：发布配置</el-button>
        </div>
      </div>
    </div>

    <!-- 第四步：发布 -->
    <div class="page-card" v-if="store.step === 4">
      <div class="step-toolbar">
        <el-button size="small" @click="store.goStep(3)">上一步</el-button>
        <el-button size="small" type="success" :loading="acting" @click="doPublish">发布配置</el-button>
        <el-button size="small" @click="store.refresh()">刷新</el-button>
        <span class="text-muted">发布前最终检查；通过后草稿转为生效数据并替换旧生效数据</span>
      </div>
      <TaskProgress :snapshot="store.batch || {}" />
      <FileStatusTable v-if="store.entries.length" :entries="store.entries" :batch-id="store.batch && store.batch.id" />
      <div v-if="store.batch && store.batch.status === 'PUBLISHED'" style="margin-top: 14px">
        <el-result icon="success" title="发布成功" sub-title="配置数据已对外生效，可在「数据浏览」页查看">
          <template #extra>
            <el-button type="primary" size="small" @click="$router.push('/data')">前往数据浏览</el-button>
            <el-button size="small" @click="store.reset()">开始新批次</el-button>
          </template>
        </el-result>
      </div>
    </div>

    <!-- 在线编辑对话框 -->
    <el-dialog v-model="editorVisible" :title="'在线编辑 - ' + editorDef" width="92%" top="4vh" destroy-on-close>
      <div class="step-toolbar">
        <span class="text-muted">编辑完成后点击「保存并上传」将覆盖批次内该配置的文件</span>
      </div>
      <SpreadSheet v-if="editorVisible" ref="spreadRef" :source="editorSource" :filename="editorDef + '.xlsx'"
                   :saveable="false" height="58vh" />
      <template #footer>
        <el-button @click="editorVisible = false">取消</el-button>
        <el-button type="primary" :loading="savingEditor" @click="saveAndUpload">保存并上传</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useImportStore } from '../stores/importTask'
import { useDefsStore } from '../stores/defs'
import FileStatusTable from '../components/FileStatusTable.vue'
import TaskProgress from '../components/TaskProgress.vue'
import SpreadSheet from '../components/SpreadSheet.vue'

const store = useImportStore()
const defsStore = useDefsStore()
const keyword = ref('')
const levelFilter = ref('')
const pendingFiles = ref([])
const uploading = ref(false)
const acting = ref(false)
const editorVisible = ref(false)
const editorDef = ref('')
const editorSource = ref(null)
const savingEditor = ref(false)
const spreadRef = ref(null)
const draftDef = ref('')
const draftRows = ref([])

const filteredDefs = computed(() => defsStore.enabled.filter(d =>
  (!keyword.value || d.name.includes(keyword.value) || d.code.toLowerCase().includes(keyword.value.toLowerCase()))
  && (!levelFilter.value || d.level === levelFilter.value)))

const checkPassed = computed(() => {
  if (!store.batch) return false
  const status = store.batch.status
  if (!['CHECKED', 'IMPORTED', 'PUBLISHED'].includes(status)) return false
  return (store.entries || []).every(e => e.errorCount === 0)
})

const importSteps = [
  { title: '上传配置', desc: '模板下载 / 上传 xlsx·zip / 在线编辑' },
  { title: '检查配置', desc: '异步预检查：进度 + 明细（含依赖）' },
  { title: '导入配置', desc: '再次检查并入库（未发布草稿）' },
  { title: '发布配置', desc: '最终检查 + 替换生效数据' }
]

/** 每步状态随批次状态机映射：process=当前、finish=已过、success=完成、error=失败、wait=未开始。 */
const importStepStatus = computed(() => {
  const st = ['wait', 'wait', 'wait', 'wait']
  const b = store.batch
  const status = b ? b.status : null
  const cur = store.step
  if (!status) {
    st[0] = cur === 1 ? 'process' : 'wait'
    return st
  }
  const ok1 = () => { st[0] = 'finish' }
  const ok2 = () => { st[0] = 'finish'; st[1] = 'success' }
  const ok3 = () => { st[0] = 'finish'; st[1] = 'success'; st[2] = 'success' }
  const ok4 = () => { st[0] = 'success'; st[1] = 'success'; st[2] = 'success'; st[3] = 'success' }
  switch (status) {
    case 'CREATED':
      st[0] = cur === 1 ? 'process' : 'finish'
      break
    case 'CHECKING':
      ok1(); st[1] = 'process'
      break
    case 'CHECKED':
      ok1(); st[1] = checkPassed.value ? 'success' : (cur === 2 ? 'process' : 'finish')
      break
    case 'IMPORTING':
      ok2(); st[2] = 'process'
      break
    case 'IMPORTED':
      ok3(); st[3] = 'wait'
      break
    case 'PUBLISHING':
      ok3(); st[3] = 'process'
      break
    case 'PUBLISHED':
      ok4()
      break
    case 'FAILED':
      for (let i = 0; i < 4; i++) {
        if (i < cur - 1) st[i] = 'finish'
        else if (i === cur - 1) st[i] = 'error'
      }
      break
    default:
      break
  }
  return st
})

/** 允许跳转到的最大步骤（1-4）：由批次状态与检查结果决定。 */
function importMaxStep() {
  if (!store.batch) return 1
  const status = store.batch.status
  switch (status) {
    case 'CREATED': return 2   // 批次已创建：可进入第2步执行检查
    case 'CHECKING': return 2
    case 'CHECKED': return checkPassed.value ? 3 : 2
    case 'IMPORTING': return 3
    case 'IMPORTED':
    case 'PUBLISHING':
    case 'PUBLISHED': return 4
    case 'FAILED': return Math.max(1, store.step)
    default: return 1
  }
}

function importStepClass(i) {
  const clickable = i + 1 <= importMaxStep() && store.step !== i + 1
  return clickable ? 'is-clickable' : 'is-disabled'
}

function onStepClick(e) {
  const steps = [...e.currentTarget.querySelectorAll('.el-step')]
  const target = e.target.closest('.el-step')
  const idx = steps.indexOf(target)
  if (idx < 0 || idx + 1 === store.step) return
  if (idx + 1 > importMaxStep()) return
  store.goStep(idx + 1)
}

const canProceed = computed(() => store.batch && store.selectedDefs.length > 0)

const hasScope = computed(() => {
  const d = defsStore.byCode(draftDef.value)
  return d && d.level !== 'GLOBAL'
})

const draftDefFields = computed(() => {
  const d = defsStore.byCode(draftDef.value)
  return d ? d.fields : []
})

onMounted(async () => {
  if (!defsStore.defs.length) await defsStore.load()
  if (store.batch && ['CHECKING', 'IMPORTING', 'PUBLISHING'].includes(store.batch.status)) {
    store.subscribe(store.batch.id)
  } else if (store.batch) {
    store.refresh()
  }
})

function levelLabel(l) { return { GLOBAL: '全局', REGION: '地区', PROJECT: '项目' }[l] || l }
function defTitle(code) {
  const d = defsStore.byCode(code)
  return d ? `${d.name}（${d.code}）` : code
}

function onSelectionChange(rows) {
  store.selectDefs(rows.map(r => r.code))
}

function onFileChange(file) {
  pendingFiles.value.push(file.raw)
}

function removeFile(f) {
  pendingFiles.value = pendingFiles.value.filter(x => x.uid !== f.uid)
}

async function ensureBatch() {
  try {
    await store.ensureBatch('快速实施-导入')
    ElMessage.success(`导入批次已就绪（ID ${store.batch.id}）`)
  } catch (e) {
    ElMessage.error(e.message)
  }
}

async function refreshBatch() {
  await store.refresh()
}

async function doUpload() {
  if (!store.batch) {
    ElMessage.warning('请先创建导入批次')
    return
  }
  uploading.value = true
  try {
    const report = await store.upload(pendingFiles.value)
    pendingFiles.value = []
    if (report.unmatched.length) {
      ElMessage.warning(`有 ${report.unmatched.length} 个文件未匹配到配置编码：${report.unmatched.join('、')}`)
    } else {
      ElMessage.success(`上传成功，匹配 ${report.matched.length} 个文件`)
    }
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    uploading.value = false
  }
}

function downloadTemplates(zip) {
  const codes = store.selectedDefs
  if (!codes.length) return
  if (!zip && codes.length === 1) {
    window.open(`/api/import/templates?defCodes=${codes[0]}&zip=false`, '_blank')
  } else {
    window.open(`/api/import/templates?defCodes=${codes.join(',')}&zip=${zip}`, '_blank')
  }
}

function openEditor(code) {
  editorDef.value = code
  const entry = (store.entries || []).find(e => e.defCode === code)
  if (entry && entry.fileName) {
    // 已上传：加载已上传文件
    editorSource.value = `/api/import/batches/${store.batch.id}/files/${code}`
  } else {
    // 未上传：加载模板作为编辑起点（满足“不上传也可在线编辑”）
    editorSource.value = `/api/import/templates?defCodes=${code}&zip=false`
  }
  editorVisible.value = true
}

async function saveAndUpload() {
  savingEditor.value = true
  try {
    const blob = await spreadRef.value.exportBlob()
    const file = new File([blob], `${editorDef.value}.xlsx`,
      { type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' })
    await store.upload([file])
    ElMessage.success(`${editorDef.value} 已保存并上传（覆盖）`)
    editorVisible.value = false
  } catch (e) {
    ElMessage.error('保存上传失败：' + e.message)
  } finally {
    savingEditor.value = false
  }
}

async function doCheck() {
  acting.value = true
  try {
    await store.runCheck()
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    acting.value = false
  }
}

async function doImport() {
  acting.value = true
  try {
    await store.runImport()
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    acting.value = false
  }
}

async function doPublish() {
  try {
    await ElMessageBox.confirm('发布后该批次草稿将转为生效数据，并替换同配置下的旧生效数据。确认发布？', '发布确认', {
      confirmButtonText: '确认发布',
      cancelButtonText: '再核查一下',
      type: 'warning'
    })
  } catch {
    return
  }
  acting.value = true
  try {
    await store.runPublish()
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    acting.value = false
  }
}

async function loadDraft() {
  if (!draftDef.value) return
  draftRows.value = await store.loadDrafts(draftDef.value)
}

// 导入完成后默认预览第一个配置的草稿
import { watch } from 'vue'
watch(() => store.batch && store.batch.status, (s) => {
  if (s === 'IMPORTED' && store.selectedDefs.length) {
    draftDef.value = store.selectedDefs[0]
    loadDraft()
  }
})
</script>

<style scoped>
.step-toolbar {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 14px;
  flex-wrap: wrap;
}
.pending-files {
  margin-top: 10px;
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.upload-report { margin: 10px 0; }
.edit-row {
  margin-top: 14px;
  display: flex;
  gap: 20px;
  align-items: center;
  flex-wrap: wrap;
}
.edit-cell {
  display: flex;
  align-items: center;
  gap: 6px;
  border: 1px dashed #dcdfe6;
  border-radius: 6px;
  padding: 4px 10px;
}
.wizard-steps :deep(.el-step.is-clickable) {
  cursor: pointer;
}
.wizard-steps :deep(.el-step.is-clickable:hover .el-step__title) {
  color: #409eff;
}
.wizard-steps :deep(.el-step.is-disabled) {
  cursor: not-allowed;
}
</style>
