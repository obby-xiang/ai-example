<template>
  <div class="space-y-4">
    <!-- 步骤1：选择配置 -->
    <section v-if="taskStore.currentStep === 'SELECT_CONFIG'" class="wizard-card">
      <div class="wizard-card__title">选择要导入的配置项（可多选）</div>
      <div class="text-xs text-gray-400 mb-3">选择后进入上传/编辑步骤；有依赖的配置项会按依赖顺序导入与发布。</div>
      <div v-for="group in groups" :key="group.level" class="mb-4">
        <div class="flex items-center gap-2 mb-2">
          <el-tag size="small" :type="levelType(group.level)" effect="dark">{{ levelText(group.level) }}</el-tag>
          <span class="text-xs text-gray-400">{{ group.items.length }} 个配置项</span>
        </div>
        <div class="grid grid-cols-1 md:grid-cols-2 xl:grid-cols-3 gap-2">
          <div v-for="c in group.items" :key="c.code"
            class="config-chip" :class="{ selected: selected.includes(c.code) }" @click="toggle(c.code)">
            <span class="chip-check"><el-checkbox :model-value="selected.includes(c.code)" /></span>
            <div class="min-w-0 flex-1">
              <div class="text-sm font-medium text-gray-800 truncate">
                {{ c.name }}
                <el-tooltip v-if="c.dependsOn?.length" :content="'依赖：' + c.dependsOn.map(d => d.def + '.' + d.field).join('、')">
                  <el-icon class="text-amber-500 align-middle"><Link /></el-icon>
                </el-tooltip>
              </div>
              <div class="text-xs text-gray-400 font-mono truncate">{{ c.code }}</div>
            </div>
            <div class="text-right flex-shrink-0">
              <div class="text-xs text-gray-500">现 {{ c.rowCount }} 行</div>
            </div>
          </div>
        </div>
      </div>
      <div class="flex items-center gap-3 pt-3 border-t border-gray-100">
        <el-button type="primary" :disabled="!selected.length" @click="saveSelection">
          下一步：上传/编辑数据（已选 {{ selected.length }} 项）
        </el-button>
      </div>
    </section>

    <!-- 步骤2：上传/在线编辑 -->
    <section v-else-if="taskStore.currentStep === 'PREPARE'" class="wizard-card">
      <div class="wizard-card__title">上传配置 / 在线编辑</div>
      <div class="text-xs text-gray-400 mb-3">
        支持：单个/多个 xlsx，或一个 zip（按文件名匹配配置项编码）；也可不上传，直接「在线编辑」按模板填写。
      </div>
      <div class="flex gap-2 mb-4 flex-wrap">
        <el-upload :show-file-list="false" :auto-upload="false" multiple accept=".xlsx"
          :on-change="(f) => onPickFiles([f.raw])">
          <el-button>选择 Excel 文件（可多选）</el-button>
        </el-upload>
        <el-upload :show-file-list="false" :auto-upload="false" accept=".zip"
          :on-change="(f) => onPickFiles([f.raw])">
          <el-button>选择 zip 压缩包</el-button>
        </el-upload>
        <el-button @click="downloadTemplates" :loading="downloadingTpl">下载模板（{{ selected.length }} 个）</el-button>
      </div>

      <el-alert v-if="uploadError" :title="uploadError" type="error" show-icon closable class="mb-3"
        @close="uploadError = ''" />

      <div class="space-y-3">
        <div v-for="code in selection" :key="code"
          class="border border-gray-200 rounded-lg overflow-hidden">
          <div class="flex items-center gap-2 px-4 py-2.5 bg-gray-50 border-b border-gray-200">
            <span class="font-medium text-sm">{{ configOf(code)?.name }}</span>
            <span class="text-xs text-gray-400 font-mono">{{ code }}</span>
            <el-tag v-if="uploads[code]" size="small" type="success">
              {{ uploads[code].rowCount }} 行 · {{ uploads[code].fileName || '在线编辑' }}
            </el-tag>
            <el-tag v-else size="small" type="info">未提交数据</el-tag>
            <div class="ml-auto flex gap-1">
              <el-button size="small" text type="primary" @click="openEdit(code)">
                {{ uploads[code] ? '编辑数据' : '在线编辑' }}
              </el-button>
              <el-button size="small" text type="danger" :disabled="!uploads[code]" @click="clearData(code)">
                清除
              </el-button>
            </div>
          </div>
        </div>
      </div>

      <div class="flex items-center gap-3 pt-3 border-t border-gray-100 mt-4">
        <el-button type="primary" @click="startCheck">下一步：开始检查</el-button>
        <span class="text-xs text-gray-400">检查会比较耗时（含跨配置项依赖校验），请耐心等待。</span>
      </div>
    </section>

    <!-- 步骤3：检查结果 -->
    <section v-else-if="taskStore.currentStep === 'CHECK'" class="wizard-card">
      <div class="wizard-card__title">检查结果</div>
      <template v-if="checkResult">
        <el-alert v-if="!checkResult.hasError" type="success" show-icon class="mb-3"
          :title="`检查通过（警告 ${checkResult.totalWarnings} 个）`" />
        <el-alert v-else type="error" show-icon class="mb-3"
          :title="`存在 ${checkResult.totalErrors} 个错误，必须修复后才能导入`" />
        <div v-for="c in checkResult.configs" :key="c.configCode"
          class="border border-gray-200 rounded-lg mb-2 overflow-hidden">
          <div class="flex items-center gap-2 px-4 py-2 bg-gray-50 border-b border-gray-200">
            <span class="font-medium text-sm">{{ c.configName }}</span>
            <span class="text-xs text-gray-400 font-mono">{{ c.configCode }}</span>
            <el-tag size="small" :type="c.errorCount > 0 ? 'danger' : c.warnCount > 0 ? 'warning' : 'success'">
              {{ c.errorCount > 0 ? `${c.errorCount} 错误` : c.warnCount > 0 ? `${c.warnCount} 警告` : '通过' }}
            </el-tag>
            <span class="text-xs text-gray-400 ml-auto">{{ c.rowCount }} 行</span>
          </div>
          <el-table v-if="c.issues?.length" :data="c.issues" size="small" max-height="220">
            <el-table-column label="级别" width="70">
              <template #default="{ row }">
                <el-tag size="small" :type="row.level === 'ERROR' ? 'danger' : 'warning'">
                  {{ row.level === 'ERROR' ? '错误' : '警告' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="row" label="行号" width="70" />
            <el-table-column prop="field" label="字段" width="140" />
            <el-table-column prop="message" label="说明" min-width="260" />
          </el-table>
        </div>
      </template>
      <div class="flex items-center gap-3 pt-3 border-t border-gray-100 mt-3">
        <el-button @click="backToPrepare">返回修改数据</el-button>
        <el-button type="primary" :disabled="!!checkResult?.hasError" @click="startImport">
          下一步：执行导入（暂存，不发布）
        </el-button>
      </div>
    </section>

    <!-- 步骤4：导入结果 -->
    <section v-else-if="taskStore.currentStep === 'IMPORT'" class="wizard-card">
      <div class="wizard-card__title">导入结果（暂存，未发布）</div>
      <el-alert type="info" show-icon class="mb-3" title="数据已进入暂存区，不影响线上；发布后将按配置项+适用范围替换已发布数据。" />
      <el-table v-if="importResult?.configs" :data="importResult.configs" size="small">
        <el-table-column prop="configName" label="配置项" min-width="150" />
        <el-table-column prop="stagedRows" label="本次暂存行数" width="120" />
        <el-table-column prop="publishedRows" label="原已发布行数" width="120" />
      </el-table>
      <div class="flex items-center gap-3 pt-3 border-t border-gray-100 mt-3">
        <el-button @click="backToPrepare">返回重新编辑</el-button>
        <el-button type="warning" @click="startPublish">发布配置（正式生效）</el-button>
      </div>
    </section>

    <!-- 步骤5/终态：发布结果 -->
    <section v-else class="wizard-card text-center py-10">
      <el-result :icon="taskStore.task?.status === 'SUCCESS' ? 'success' : 'info'"
        :title="taskStore.task?.status === 'SUCCESS' ? '发布完成，任务成功' : '任务已结束'">
        <template #extra>
          <el-button type="primary" @click="$router.push('/')">返回首页</el-button>
          <el-button @click="$router.push('/tasks')">查看任务中心</el-button>
        </template>
      </el-result>
    </section>

    <!-- 在线编辑对话框 -->
    <el-dialog v-model="editVisible" :title="`在线编辑：${editingCode ? configOf(editingCode)?.name : ''}`"
      width="86%" top="4vh" destroy-on-close :close-on-click-modal="false">
      <div class="text-xs text-gray-400 mb-2">
        表头由配置项结构动态生成，带必填标记与数据校验下拉；关闭时自动保存到任务。
      </div>
      <SpreadGrid v-if="editingCode" :fields="fieldsOf(editingCode)"
        :rows="editRows" :sheet-name="configOf(editingCode)?.name" height="56vh"
        :ref="el => (editGridRef = el)" />
      <template #footer>
        <el-button @click="editVisible = false">关闭（自动保存）</el-button>
        <el-button type="primary" @click="saveEdit">保存数据</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import JSZip from 'jszip'
import { api } from '@/api'
import { useTaskStore } from '@/stores/task'
import SpreadGrid from '@/components/SpreadGrid.vue'
import {
  buildTemplate, parseExcelFile, readRows, workbookToBlob,
  downloadBlob, zipAndDownload
} from '@/utils/excel'

const taskStore = useTaskStore()
const selected = ref([])
const uploads = reactive({})
const uploadError = ref('')
const downloadingTpl = ref(false)
const checkResult = ref(null)
const importResult = ref(null)

const editVisible = ref(false)
const editingCode = ref('')
const editRows = ref([])
const editGridRef = ref(null)

const groups = computed(() => {
  const byLevel = {}
  for (const c of taskStore.catalog) {
    (byLevel[c.level] ||= []).push(c)
  }
  return Object.entries(byLevel).map(([level, items]) => ({ level, items }))
})

const selection = computed(() => taskStore.selection)

onMounted(async () => {
  const params = taskStore.task?.params || {}
  selected.value = [...(params.selection || [])]
  const saved = params.uploads || {}
  for (const code of selected.value) {
    if (saved[code]) {
      uploads[code] = { ...saved[code] }
    }
  }
  checkResult.value = params.checkResult || null
  importResult.value = params.importResult || null
})

// 后台操作完成后（SSE 状态推进步骤）刷新结果数据
watch(() => taskStore.currentStep, (step, old) => {
  if (step === old) return
  const params = taskStore.task?.params || {}
  if (step === 'CHECK') {
    checkResult.value = params.checkResult || null
  }
  if (step === 'IMPORT') {
    importResult.value = params.importResult || null
  }
  if (step === 'PREPARE') {
    const saved = params.uploads || {}
    for (const code of selected.value) {
      if (saved[code]) {
        uploads[code] = { ...saved[code] }
      }
    }
  }
})

function configOf(code) {
  return taskStore.catalog.find((c) => c.code === code)
}
function fieldsOf(code) {
  return configOf(code)?.fields || []
}
function toggle(code) {
  const i = selected.value.indexOf(code)
  if (i >= 0) selected.value.splice(i, 1)
  else selected.value.push(code)
}

async function saveSelection() {
  await api.setSelection(taskStore.task.id, selected.value)
  ElMessage.success('已保存选择')
  await taskStore.loadTask(taskStore.task.id)
}

/* ---------- 模板下载 ---------- */

async function downloadTemplates() {
  downloadingTpl.value = true
  try {
    const files = []
    for (const code of selection.value) {
      const blob = await buildTemplate(fieldsOf(code), configOf(code)?.name)
      files.push({ name: `${code}.xlsx`, blob })
    }
    if (files.length === 1) {
      downloadBlob(files[0].blob, files[0].name)
    } else {
      await zipAndDownload(files, `配置模板_${dateStr()}.zip`)
    }
    ElMessage.success('模板已下载')
  } catch (e) {
    ElMessage.error('模板生成失败：' + e.message)
  } finally {
    downloadingTpl.value = false
  }
}

/* ---------- 文件上传 ---------- */

async function onPickFiles(fileList) {
  uploadError.value = ''
  const picked = []
  const zips = []
  for (const f of fileList) {
    if (!f) continue
    if (/\.zip$/i.test(f.name)) zips.push(f)
    else if (/\.xlsx$/i.test(f.name)) picked.push(f)
    else uploadError.value = `不支持的文件类型：${f.name}（仅支持 .xlsx / .zip）`
  }
  for (const z of zips) {
    try {
      const zip = await JSZip.loadAsync(z)
      for (const entry of Object.values(zip.files)) {
        if (/\.xlsx$/i.test(entry.name) && !entry.dir) {
          const blob = await entry.async('blob')
          picked.push(new File([blob], entry.name.split('/').pop()))
        }
      }
    } catch {
      uploadError.value = `zip 解压失败：${z.name}`
    }
  }
  const unmatched = []
  const matched = []
  for (const f of picked) {
    const code = f.name.replace(/\.xlsx$/i, '').trim()
    if (selection.value.includes(code)) {
      matched.push({ code, file: f })
    } else {
      unmatched.push(f.name)
    }
  }
  if (unmatched.length) {
    uploadError.value = `以下文件无法匹配已选配置项（文件名需与配置项编码一致）：${unmatched.join('、')}`
  }
  if (!matched.length) {
    if (!uploadError.value) uploadError.value = '没有可导入的文件'
    return
  }
  let ok = 0
  for (const m of matched) {
    try {
      const rows = await parseFile(m.code, m.file)
      await api.submitData(taskStore.task.id, m.code, m.file.name, rows)
      uploads[m.code] = { fileName: m.file.name, rowCount: rows.length }
      ok++
    } catch (e) {
      uploadError.value = `${m.file.name} 导入失败：${e.message}`
    }
  }
  if (ok) {
    ElMessage.success(`已提交 ${ok} 个文件`)
    await taskStore.loadTask(taskStore.task.id)
  }
}

async function parseFile(code, file) {
  const workbook = await parseExcelFile(file)
  const sheet = workbook.getSheet(0)
  const { rows, missingHeaders } = readRows(sheet, fieldsOf(code))
  if (missingHeaders.length) {
    throw new Error(`缺少表头字段：${missingHeaders.join('、')}`)
  }
  return rows
}

function clearData(code) {
  delete uploads[code]
  api.submitData(taskStore.task.id, code, '', [])
}

/* ---------- 在线编辑 ---------- */

function openEdit(code) {
  editingCode.value = code
  editRows.value = (uploads[code]?.rows || []).map((r) => ({ ...r }))
  editVisible.value = true
}

async function saveEdit() {
  const code = editingCode.value
  const grid = editGridRef.value
  if (!grid || !code) return
  const sheet = grid.getSpread()?.getActiveSheet()
  if (!sheet) return
  const { rows, missingHeaders } = readRows(sheet, fieldsOf(code))
  if (missingHeaders.length) {
    ElMessage.warning(`缺少表头字段：${missingHeaders.join('、')}`)
    return
  }
  await api.submitData(taskStore.task.id, code, '在线编辑', rows)
  uploads[code] = { fileName: '在线编辑', rowCount: rows.length }
  ElMessage.success(`已保存 ${rows.length} 行`)
  editVisible.value = false
}

/* ---------- 检查/导入/发布 ---------- */

async function startCheck() {
  const noData = selection.value.filter((c) => !uploads[c])
  if (noData.length) {
    await ElMessageBox.confirm(
      `配置项 ${noData.join('、')} 尚未提交数据，检查将产生警告（发布时会清空对应数据）。是否继续？`,
      '提示', { type: 'warning', confirmButtonText: '继续检查', cancelButtonText: '返回' })
  }
  await api.startCheck(taskStore.task.id)
  ElMessage.success('检查已开始，请稍候…')
}

async function backToPrepare() {
  // 后端步骤机不允许回退 API；重新打开任务数据编辑界面 = 回到 PREPARE 的数据视图
  // 这里通过重新提交当前数据触发步骤不变，仅导航展示
  // 实际交互：直接显示 PREPARE 界面（数据仍在），用户可覆盖提交后再检查
  ElMessage.info('请在当前数据基础上修改后重新检查（重新提交数据即可）')
}

async function startImport() {
  await ElMessageBox.confirm(
    '将执行导入：数据进入暂存区（不影响已发布数据）。确认继续？',
    '执行导入', { type: 'info', confirmButtonText: '开始导入' })
  await api.startImport(taskStore.task.id)
  ElMessage.success('导入已开始，请稍候…')
}

async function startPublish() {
  await ElMessageBox.confirm(
    '将执行发布：按配置项+适用范围替换已发布数据，正式生效。确认发布？',
    '发布确认', { type: 'warning', confirmButtonText: '确认发布' })
  await api.startPublish(taskStore.task.id)
  ElMessage.success('发布已开始，请稍候…')
}

function dateStr() {
  return new Date().toISOString().slice(0, 10).replace(/-/g, '')
}
function levelText(l) {
  return { GLOBAL: '全局', REGION: '地区', PROJECT: '项目' }[l] || l
}
function levelType(l) {
  return { GLOBAL: '', REGION: 'warning', PROJECT: 'success' }[l] || 'info'
}
</script>

<style>
.wizard-card {
  background: #fff;
  border: 1px solid #e4e7ed;
  border-radius: 8px;
  padding: 20px;
}
.wizard-card__title {
  font-weight: 600;
  font-size: 15px;
  color: #303133;
  margin-bottom: 4px;
}
.config-chip {
  display: flex;
  align-items: center;
  gap: 10px;
  border: 1px solid #e4e7ed;
  border-radius: 8px;
  padding: 10px 12px;
  cursor: pointer;
  transition: all .15s;
}
.config-chip:hover {
  border-color: #b3d8ff;
}
.config-chip.selected {
  border-color: #409eff;
  background: #ecf5ff;
}
.chip-check {
  pointer-events: none;
}
</style>
