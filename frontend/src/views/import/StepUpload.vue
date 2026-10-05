<template>
  <div>
    <div class="step-desc">选择配置项并上传对应的Excel文件，或下载模板在线编辑</div>

    <!-- Config selection -->
    <div class="section-title">1. 选择配置项</div>
    <el-row :gutter="12" style="margin-bottom:12px">
      <el-col :span="8">
        <el-select v-model="levelFilter" clearable placeholder="按层级筛选" style="width:100%">
          <el-option label="全局" value="GLOBAL" /><el-option label="地区级" value="REGION" /><el-option label="项目级" value="PROJECT" />
        </el-select>
      </el-col>
      <el-col :span="8">
        <el-input v-model="keyword" clearable placeholder="搜索配置编码或名称" />
      </el-col>
    </el-row>
    <el-table ref="defTableRef" :data="filteredDefs" max-height="240" border @selection-change="handleDefSelection">
      <el-table-column type="selection" width="50" />
      <el-table-column prop="code" label="编码" width="140" />
      <el-table-column prop="name" label="名称" width="120" />
      <el-table-column label="层级" width="80">
        <template #default="{row}">
          <el-tag :type="levelType(row.level)" size="small">{{ levelLabel(row.level) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="description" label="说明" show-overflow-tooltip />
    </el-table>

    <!-- File upload section -->
    <div class="section-title" style="margin-top:24px">2. 上传配置文件</div>
    <div class="upload-toolbar">
      <el-button size="small" @click="downloadTemplates" :disabled="selectedCodes.length === 0">
        <el-icon><Download /></el-icon> 下载模板（{{ selectedCodes.length }} 个）
      </el-button>
      <el-upload ref="uploadRef" :auto-upload="false" :on-change="handleFileChange" :show-file-list="false" accept=".xlsx,.zip" multiple>
        <el-button size="small"><el-icon><Upload /></el-icon> 批量上传文件</el-button>
      </el-upload>
    </div>

    <el-table :data="fileRows" border>
      <el-table-column prop="defCode" label="配置编码" width="140" />
      <el-table-column prop="defName" label="名称" width="120" />
      <el-table-column label="文件状态" width="160">
        <template #default="{row}">
          <el-tag v-if="row.fileName" type="success" size="small">{{ row.fileName }}</el-tag>
          <el-tag v-else type="info" size="small">未上传</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="200">
        <template #default="{row}">
          <el-button size="small" link @click="openEditor(row)">在线编辑</el-button>
          <el-upload :auto-upload="false" :on-change="(f) => uploadSingle(f, row)" :show-file-list="false" accept=".xlsx" style="display:inline">
            <el-button size="small" link><el-icon><Upload /></el-icon> 上传</el-button>
          </el-upload>
        </template>
      </el-table-column>
    </el-table>

    <!-- SpreadJS editor -->
    <el-dialog v-model="editorVisible" :title="`在线编辑：${editorRow?.defCode}`" width="90%" fullscreen>
      <SpreadJSEditor v-if="editorVisible" :taskId="task?.id" :defCode="editorRow?.defCode" @saved="onEditorSaved" />
      <template #footer><el-button @click="editorVisible=false">关闭</el-button></template>
    </el-dialog>

    <div class="wizard-footer">
      <el-button type="primary" :disabled="selectedCodes.length === 0" :loading="saving" @click="handleNext">
        下一步：检查配置 <el-icon class="el-icon--right"><ArrowRight /></el-icon>
      </el-button>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { listDefinitions } from '@/api/definitions.js'
import { downloadTemplates as apiDownloadTemplates, uploadFile, getFiles } from '@/api/tasks.js'
import { useTaskStore } from '@/stores/task.js'
import { ElMessage } from 'element-plus'
import { Download, Upload, ArrowRight } from '@element-plus/icons-vue'
import SpreadJSEditor from '@/components/SpreadJSEditor/SpreadJSEditor.vue'

const props = defineProps({ task: Object })
const emit = defineEmits(['next'])
const taskStore = useTaskStore()

const defs = ref([])
const levelFilter = ref('')
const keyword = ref('')
const selectedCodes = ref([])
const fileMap = ref({})   // defCode -> {fileName}
const editorVisible = ref(false)
const editorRow = ref(null)
const saving = ref(false)

const filteredDefs = computed(() => defs.value.filter(d => {
  if (levelFilter.value && d.level !== levelFilter.value) return false
  if (keyword.value && !d.code.includes(keyword.value) && !d.name.includes(keyword.value)) return false
  return true
}))

const fileRows = computed(() => selectedCodes.value.map(code => {
  const def = defs.value.find(d => d.code === code)
  return { defCode: code, defName: def?.name || code, fileName: fileMap.value[code]?.fileName }
}))

onMounted(async () => {
  const [defRes, fileRes] = await Promise.all([
    listDefinitions(),
    props.task?.id ? getFiles(props.task.id) : Promise.resolve({ data: { data: [] } })
  ])
  defs.value = defRes.data.data || []
  // Restore existing selections from task
  if (props.task?.items?.length) {
    selectedCodes.value = props.task.items.map(i => i.defCode)
    for (const f of (fileRes.data.data || [])) {
      if (f.fileType === 'UPLOAD') fileMap.value[f.defCode] = { fileName: f.fileName }
    }
  }
})

function handleDefSelection(rows) { selectedCodes.value = rows.map(r => r.code) }

async function downloadTemplates() {
  if (!props.task?.id || selectedCodes.value.length === 0) return
  const res = await apiDownloadTemplates(props.task.id, selectedCodes.value)
  const ext = selectedCodes.value.length === 1 ? '.xlsx' : '.zip'
  triggerDownload(res.data, `templates${ext}`)
}

async function handleFileChange(file) {
  if (!props.task?.id) return
  try {
    const res = await uploadFile(props.task.id, file.raw)
    const data = res.data.data || {}
    const matchedFiles = data.matchedFiles || (data.defCode ? [{ defCode: data.defCode, fileName: data.fileName }] : [])

    for (const m of matchedFiles) {
      fileMap.value[m.defCode] = { fileName: m.fileName }
      if (!selectedCodes.value.includes(m.defCode)) selectedCodes.value.push(m.defCode)
    }

    let msg = `已匹配 ${matchedFiles.length} 个配置项`
    const unmatched = data.unmatchedFiles || []
    if (unmatched.length > 0) {
      msg += `，${unmatched.length} 个文件未匹配: ${unmatched.slice(0, 3).join('、')}${unmatched.length > 3 ? '…' : ''}`
    }
    ElMessage[unmatched.length > 0 ? 'warning' : 'success'](msg)
  } catch (e) {
    ElMessage.error(e.response?.data?.message || '上传失败')
  }
}

async function uploadSingle(file, row) {
  if (!props.task?.id) return
  await uploadFile(props.task.id, file.raw)
  fileMap.value[row.defCode] = { fileName: file.name }
  ElMessage.success('文件已上传')
}

function openEditor(row) { editorRow.value = row; editorVisible.value = true }
function onEditorSaved() { editorVisible.value = false; ElMessage.success('在线编辑已保存') }

async function handleNext() {
  saving.value = true
  try {
    await taskStore.selectDefs(props.task.id, selectedCodes.value)
    emit('next')
  } finally { saving.value = false }
}

function triggerDownload(blob, name) {
  const url = URL.createObjectURL(blob instanceof Blob ? blob : new Blob([blob]))
  const a = document.createElement('a'); a.href = url; a.download = name; a.click(); URL.revokeObjectURL(url)
}
const levelType = (l) => ({ GLOBAL: 'success', REGION: 'warning', PROJECT: 'primary' })[l] || ''
const levelLabel = (l) => ({ GLOBAL: '全局', REGION: '地区', PROJECT: '项目' })[l] || l
</script>

<style lang="scss" scoped>
.step-desc { color: var(--el-text-color-secondary); margin-bottom:16px; }
.section-title { font-weight:600; font-size:14px; margin-bottom:10px; }
.upload-toolbar { display:flex; gap:8px; margin-bottom:10px; align-items:center; }
</style>
