<template>
  <div>
    <div class="page-header">
      <div>
        <h2>配置定义管理</h2>
        <p class="subtitle">查看和管理动态配置项的结构定义（字段、类型、必填、下拉选项、引用关系）</p>
      </div>
      <el-button type="primary" @click="openCreate">
        <el-icon><Plus /></el-icon> 新建配置定义
      </el-button>
    </div>

    <el-row :gutter="16">
      <!-- Left: definition list -->
      <el-col :span="8">
        <div class="def-list-panel card">
          <div class="list-toolbar">
            <el-select v-model="levelFilter" clearable size="small" placeholder="层级" style="width:100px">
              <el-option label="全局" value="GLOBAL" />
              <el-option label="地区" value="REGION" />
              <el-option label="项目" value="PROJECT" />
            </el-select>
            <el-input v-model="keyword" clearable size="small" placeholder="搜索" style="flex:1" />
          </div>
          <div class="def-item-list" v-loading="loading">
            <div
              v-for="def in filteredDefs" :key="def.code"
              class="def-item"
              :class="{ active: selected?.code === def.code }"
              @click="select(def)"
            >
              <div class="def-item-main">
                <span class="def-code">{{ def.code }}</span>
                <el-tag :type="levelType(def.level)" size="small">{{ levelLabel(def.level) }}</el-tag>
              </div>
              <div class="def-name">{{ def.name }}</div>
            </div>
            <el-empty v-if="filteredDefs.length === 0" description="无配置定义" />
          </div>
        </div>
      </el-col>

      <!-- Right: definition detail -->
      <el-col :span="16">
        <div v-if="selected" class="card">
          <div class="detail-header">
            <div>
              <h3>{{ selected.name }} <span class="code-badge">{{ selected.code }}</span></h3>
              <p class="def-desc">{{ selected.description || '暂无描述' }}</p>
            </div>
            <div class="detail-actions">
              <el-button size="small" type="primary" @click="openFieldEditor">
                <el-icon><Edit /></el-icon> 编辑字段
              </el-button>
              <el-button size="small" @click="downloadTpl(selected)">
                <el-icon><Download /></el-icon> 下载模板
              </el-button>
            </div>
          </div>

          <el-divider />

          <el-table :data="selected.fields || []" border size="small">
            <el-table-column prop="code" label="字段编码" width="130" />
            <el-table-column prop="label" label="标签" width="120" />
            <el-table-column label="类型" width="100">
              <template #default="{row}">
                <el-tag size="small" effect="plain">{{ typeLabel(row.fieldType) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="必填" width="70">
              <template #default="{row}">
                <el-icon v-if="row.required" color="#67c23a"><Select /></el-icon>
                <el-icon v-else color="#dcdfe6"><Minus /></el-icon>
              </template>
            </el-table-column>
            <el-table-column label="主键" width="70">
              <template #default="{row}">
                <el-icon v-if="row.isKey" color="#409eff"><Select /></el-icon>
                <el-icon v-else color="#dcdfe6"><Minus /></el-icon>
              </template>
            </el-table-column>
            <el-table-column label="选项/引用" show-overflow-tooltip>
              <template #default="{row}">
                <span v-if="row.refDefCode">{{ row.refDefCode }}.{{ row.refFieldCode }}</span>
                <span v-else-if="row.optionsJson">
                  <el-tag v-for="opt in parseOpts(row.optionsJson).slice(0,3)" :key="opt.value" size="small" effect="plain" style="margin-right:2px">{{ opt.label }}</el-tag>
                  <span v-if="parseOpts(row.optionsJson).length > 3">+{{ parseOpts(row.optionsJson).length - 3 }}</span>
                </span>
                <span v-else class="cell-empty">—</span>
              </template>
            </el-table-column>
          </el-table>
        </div>
        <el-empty v-else description="从左侧选择一个配置定义" />
      </el-col>
    </el-row>

    <!-- Create dialog -->
    <el-dialog v-model="createVisible" title="新建配置定义" width="600px">
      <el-form :model="form" label-width="100px">
        <el-form-item label="编码" required>
          <el-input v-model="form.code" placeholder="例如：PROJ_COST（字母开头）" />
        </el-form-item>
        <el-form-item label="名称" required>
          <el-input v-model="form.name" placeholder="例如：项目成本表" />
        </el-form-item>
        <el-form-item label="层级" required>
          <el-select v-model="form.level" placeholder="选择层级" style="width:100%">
            <el-option label="全局" value="GLOBAL" /><el-option label="地区级" value="REGION" /><el-option label="项目级" value="PROJECT" />
          </el-select>
        </el-form-item>
        <el-form-item label="说明">
          <el-input v-model="form.description" type="textarea" :rows="2" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible=false">取消</el-button>
        <el-button type="primary" @click="handleCreate" :loading="creating">创建</el-button>
      </template>
    </el-dialog>

    <!-- Field editor dialog -->
    <el-dialog v-model="editorVisible" :title="`编辑字段：${selected?.code} ${selected?.name}`" width="1000px" top="4vh">
      <div class="editor-toolbar">
        <el-button size="small" :icon="Plus" @click="addFieldRow">新增字段</el-button>
        <span class="hint">字段编码以字母开头；REFERENCE 类型需选择引用配置和引用字段；ENUM 类型选项每行一条，格式 value=label</span>
      </div>
      <el-table :data="editingFields" border size="small" max-height="520">
        <el-table-column label="字段编码" width="150">
          <template #default="{row}">
            <el-input v-model="row.code" size="small" placeholder="fieldCode" :disabled="row.__original" />
          </template>
        </el-table-column>
        <el-table-column label="标签" width="140">
          <template #default="{row}">
            <el-input v-model="row.label" size="small" placeholder="中文标签" />
          </template>
        </el-table-column>
        <el-table-column label="类型" width="120">
          <template #default="{row}">
            <el-select v-model="row.fieldType" size="small" style="width:100%">
              <el-option v-for="t in fieldTypes" :key="t.value" :label="t.label" :value="t.value" />
            </el-select>
          </template>
        </el-table-column>
        <el-table-column label="必填" width="60" align="center">
          <template #default="{row}">
            <el-switch v-model="row.required" size="small" />
          </template>
        </el-table-column>
        <el-table-column label="主键" width="60" align="center">
          <template #default="{row}">
            <el-switch v-model="row.isKey" size="small" />
          </template>
        </el-table-column>
        <el-table-column label="选项 / 引用" min-width="260">
          <template #default="{row}">
            <template v-if="row.fieldType === 'ENUM'">
              <el-input v-model="row.optionsText" type="textarea" :rows="2" size="small" placeholder="PURCHASE=采购单&#10;SALES=销售单" />
            </template>
            <template v-else-if="row.fieldType === 'REFERENCE'">
              <div class="ref-row">
                <el-select v-model="row.refDefCode" size="small" placeholder="引用配置" style="width:140px" @change="row.refFieldCode = ''">
                  <el-option v-for="d in defs" :key="d.code" :label="`${d.code} ${d.name}`" :value="d.code" />
                </el-select>
                <el-select v-model="row.refFieldCode" size="small" placeholder="引用字段" style="width:130px">
                  <el-option v-for="f in refFieldOptions(row.refDefCode)" :key="f.code" :label="f.label" :value="f.code" />
                </el-select>
              </div>
            </template>
            <span v-else class="cell-empty">—</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="90" fixed="right">
          <template #default="{ $index }">
            <el-button size="small" :icon="ArrowUp" circle plain :disabled="$index === 0" @click="moveRow($index, -1)" />
            <el-button size="small" :icon="ArrowDown" circle plain :disabled="$index === editingFields.length - 1" @click="moveRow($index, 1)" />
            <el-button size="small" :icon="Delete" circle plain type="danger" @click="editingFields.splice($index, 1)" />
          </template>
        </el-table-column>
      </el-table>
      <template #footer>
        <el-button @click="editorVisible=false">取消</el-button>
        <el-button type="primary" :loading="savingFields" @click="saveFields">保存字段</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { listDefinitions, getDefinition, createDefinition, updateDefinition, downloadTemplate } from '@/api/definitions.js'
import { ElMessage } from 'element-plus'
import { Plus, Download, Select, Minus, Edit, Delete, ArrowUp, ArrowDown } from '@element-plus/icons-vue'

const defs = ref([])
const loading = ref(false)
const selected = ref(null)
const levelFilter = ref('')
const keyword = ref('')
const createVisible = ref(false)
const creating = ref(false)
const form = ref({ code: '', name: '', level: 'GLOBAL', description: '' })

const editorVisible = ref(false)
const editingFields = ref([])
const savingFields = ref(false)

const fieldTypes = [
  { value: 'STRING', label: '字符串' },
  { value: 'NUMBER', label: '数字' },
  { value: 'DATE', label: '日期' },
  { value: 'ENUM', label: '枚举' },
  { value: 'BOOLEAN', label: '布尔' },
  { value: 'REFERENCE', label: '引用' }
]

const filteredDefs = computed(() => defs.value.filter(d => {
  if (levelFilter.value && d.level !== levelFilter.value) return false
  if (keyword.value && !d.code.includes(keyword.value) && !d.name.includes(keyword.value)) return false
  return true
}))

onMounted(async () => {
  loading.value = true
  try {
    const res = await listDefinitions()
    defs.value = res.data.data || []
    if (defs.value.length) await select(defs.value[0])
  } finally { loading.value = false }
})

async function select(def) {
  const res = await getDefinition(def.code)
  selected.value = res.data.data
}

function refFieldOptions(refDefCode) {
  const d = defs.value.find(x => x.code === refDefCode)
  if (!d?.fields) return []
  return d.fields.filter(f => f.isKey)
}

function openFieldEditor() {
  if (!selected.value) return
  editingFields.value = (selected.value.fields || []).map(f => ({
    __original: f.code,
    code: f.code,
    label: f.label,
    fieldType: f.fieldType,
    required: f.required,
    isKey: f.isKey,
    optionsText: f.optionsJson ? parseOpts(f.optionsJson).map(o => `${o.value}=${o.label}`).join('\n') : '',
    refDefCode: f.refDefCode || '',
    refFieldCode: f.refFieldCode || ''
  }))
  editorVisible.value = true
}

function addFieldRow() {
  editingFields.value.push({
    code: '', label: '', fieldType: 'STRING',
    required: false, isKey: false,
    optionsText: '', refDefCode: '', refFieldCode: ''
  })
}

function moveRow(idx, dir) {
  const arr = editingFields.value
  const target = idx + dir
  if (target < 0 || target >= arr.length) return
  ;[arr[idx], arr[target]] = [arr[target], arr[idx]]
}

async function saveFields() {
  if (!selected.value) return
  savingFields.value = true
  try {
    const fields = editingFields.value.map(f => {
      const field = {
        code: f.code.trim(),
        label: f.label.trim(),
        fieldType: f.fieldType,
        required: f.required,
        isKey: f.isKey
      }
      if (f.fieldType === 'ENUM') {
        const options = (f.optionsText || '').split('\n')
          .map(line => line.trim()).filter(Boolean)
          .map(line => {
            const idx = line.indexOf('=')
            const value = idx >= 0 ? line.slice(0, idx).trim() : line
            const label = idx >= 0 ? line.slice(idx + 1).trim() : line
            return { value, label }
          })
        field.optionsJson = JSON.stringify(options)
      }
      if (f.fieldType === 'REFERENCE') {
        field.refDefCode = f.refDefCode || null
        field.refFieldCode = f.refFieldCode || null
      }
      return field
    })

    const res = await updateDefinition(selected.value.code, {
      code: selected.value.code,
      name: selected.value.name,
      level: selected.value.level,
      description: selected.value.description,
      fields
    })
    selected.value = res.data.data
    // 刷新列表缓存
    const defRes = await listDefinitions()
    defs.value = defRes.data.data || []
    editorVisible.value = false
    ElMessage.success('字段定义已保存')
  } catch (e) {
    ElMessage.error('保存失败: ' + (e.response?.data?.message || e.message))
  } finally {
    savingFields.value = false
  }
}

function openCreate() { form.value = { code: '', name: '', level: 'GLOBAL', description: '' }; createVisible.value = true }

async function handleCreate() {
  if (!form.value.code || !form.value.name) {
    ElMessage.warning('请填写编码和名称')
    return
  }
  creating.value = true
  try {
    const res = await createDefinition({ ...form.value, fields: [] })
    defs.value.unshift(res.data.data)
    createVisible.value = false
    await select(res.data.data)
    ElMessage.success('配置定义已创建，点击"编辑字段"添加字段')
  } catch (e) {
    ElMessage.error('创建失败: ' + (e.response?.data?.message || e.message))
  } finally { creating.value = false }
}

async function downloadTpl(def) {
  const res = await downloadTemplate(def.code)
  const url = URL.createObjectURL(res.data)
  const a = document.createElement('a'); a.href = url; a.download = def.code + '_' + def.name + '.xlsx'; a.click(); URL.revokeObjectURL(url)
}

function parseOpts(json) {
  try { return JSON.parse(json) } catch { return [] }
}
const levelType = (l) => ({ GLOBAL: 'success', REGION: 'warning', PROJECT: 'primary' })[l] || ''
const levelLabel = (l) => ({ GLOBAL: '全局', REGION: '地区', PROJECT: '项目' })[l] || l
const typeLabel = (t) => (fieldTypes.find(f => f.value === t) || {}).label || t
</script>

<style lang="scss" scoped>
.def-list-panel { height: calc(100vh - 200px); display: flex; flex-direction: column; overflow: hidden; }
.list-toolbar { display: flex; gap: 8px; padding: 8px 0; flex-shrink: 0; }
.def-item-list { flex: 1; overflow-y: auto; }
.def-item {
  padding: 10px 12px; border-bottom: 1px solid var(--el-border-color-lighter); cursor: pointer;
  &:hover { background: var(--el-fill-color-light); }
  &.active { background: var(--el-color-primary-light-9); }
}
.def-item-main { display: flex; align-items: center; justify-content: space-between; margin-bottom: 2px; }
.def-code { font-weight: 600; font-size: 13px; }
.def-name { font-size: 12px; color: var(--el-text-color-secondary); }
.detail-header { display: flex; justify-content: space-between; align-items: flex-start; }
.detail-actions { display: flex; gap: 8px; }
h3 { font-size: 16px; margin-bottom: 4px; }
.code-badge { font-size: 12px; color: var(--el-text-color-secondary); font-weight: normal; font-family: monospace; }
.def-desc { font-size: 13px; color: var(--el-text-color-secondary); }
.editor-toolbar { display: flex; align-items: center; gap: 12px; margin-bottom: 10px; }
.editor-toolbar .hint { font-size: 12px; color: var(--el-text-color-secondary); }
.ref-row { display: flex; gap: 6px; }
.cell-empty { color: var(--el-text-color-placeholder); }
</style>
