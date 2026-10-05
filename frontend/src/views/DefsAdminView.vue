<template>
  <div>
    <div class="page-card">
      <div class="step-toolbar">
        <span style="font-weight: 600">配置定义（动态建模）</span>
        <div style="flex: 1"></div>
        <el-button type="primary" size="small" @click="openEditor(null)">新建配置定义</el-button>
      </div>
      <el-table :data="defsStore.defs" size="small" border v-loading="defsStore.loading">
        <el-table-column prop="code" label="配置编码" width="160">
          <template #default="{ row }"><span class="mono">{{ row.code }}</span></template>
        </el-table-column>
        <el-table-column prop="name" label="配置名称" width="180" />
        <el-table-column label="层级" width="80">
          <template #default="{ row }">
            <el-tag size="small">{{ levelLabel(row.level) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="字段（表头）" min-width="240">
          <template #default="{ row }">
            <span class="text-muted">{{ row.fields.map(f => f.label + '(' + typeLabel(f.type) + ')').join('、') }}</span>
          </template>
        </el-table-column>
        <el-table-column label="依赖" width="150">
          <template #default="{ row }">
            <span class="text-muted">{{ row.dependsOn.length ? row.dependsOn.join(', ') : '-' }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="publishedRowCount" label="生效行数" width="80" />
        <el-table-column prop="draftRowCount" label="草稿行数" width="80" />
        <el-table-column label="状态" width="80">
          <template #default="{ row }">
            <el-tag size="small" :type="row.enabled ? 'success' : 'info'">{{ row.enabled ? '启用' : '停用' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="240" fixed="right">
          <template #default="{ row }">
            <el-button size="small" text type="primary" @click="openEditor(row)">编辑</el-button>
            <el-button size="small" text type="primary" @click="openRows(row)">数据行</el-button>
            <el-button size="small" text @click="toggle(row)">{{ row.enabled ? '停用' : '启用' }}</el-button>
            <el-button size="small" text type="danger" @click="remove(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <!-- 配置定义编辑抽屉 -->
    <el-drawer v-model="drawerVisible" :title="editingCode ? '编辑配置定义 - ' + editingCode : '新建配置定义'"
               size="78%" destroy-on-close>
      <el-form label-width="110px" size="small">
        <div class="form-grid">
          <el-form-item label="配置编码" required>
            <el-input v-model="form.code" :disabled="!!editingCode" placeholder="如 SERVER_PARAM（唯一，用于文件匹配）" />
          </el-form-item>
          <el-form-item label="配置名称" required>
            <el-input v-model="form.name" placeholder="如 服务器参数配置" />
          </el-form-item>
          <el-form-item label="层级" required>
            <el-select v-model="form.level" style="width: 100%">
              <el-option label="全局（GLOBAL）" value="GLOBAL" />
              <el-option label="地区（REGION）" value="REGION" />
              <el-option label="项目（PROJECT）" value="PROJECT" />
            </el-select>
          </el-form-item>
          <el-form-item label="依赖配置">
            <el-select v-model="form.dependsOn" multiple filterable style="width: 100%"
                       placeholder="导入时先于本配置（被依赖者先检查/导入）">
              <el-option v-for="d in defsStore.defs.filter(x => x.code !== editingCode)" :key="d.code"
                         :label="d.name + '（' + d.code + '）'" :value="d.code" />
            </el-select>
          </el-form-item>
          <el-form-item label="说明">
            <el-input v-model="form.description" type="textarea" :rows="2" />
          </el-form-item>
          <el-form-item label="排序">
            <el-input-number v-model="form.sortOrder" :min="0" />
          </el-form-item>
          <el-form-item label="启用">
            <el-switch v-model="form.enabled" />
          </el-form-item>
        </div>
      </el-form>

      <el-divider content-position="left">字段定义（表头，动态渲染依据）</el-divider>
      <el-table :data="form.fields" size="small" border>
        <el-table-column label="字段编码" width="150">
          <template #default="{ row }">
            <el-input v-model="row.code" size="small" placeholder="如 server_name" />
          </template>
        </el-table-column>
        <el-table-column label="字段名称(表头)" width="160">
          <template #default="{ row }">
            <el-input v-model="row.label" size="small" placeholder="如 服务器名" />
          </template>
        </el-table-column>
        <el-table-column label="类型" width="130">
          <template #default="{ row }">
            <el-select v-model="row.type" size="small">
              <el-option v-for="t in fieldTypes" :key="t.value" :label="t.label" :value="t.value" />
            </el-select>
          </template>
        </el-table-column>
        <el-table-column label="必填" width="60">
          <template #default="{ row }">
            <el-switch v-model="row.required" size="small" />
          </template>
        </el-table-column>
        <el-table-column label="选项/引用/范围" min-width="240">
          <template #default="{ row }">
            <el-input v-if="row.type === 'SELECT'" v-model="row.optionsText" size="small"
                      placeholder="逗号分隔：生产,测试,开发" />
            <template v-else-if="row.type === 'REFERENCE'">
              <el-select v-model="row.refDefCode" size="small" placeholder="引用配置" style="width: 46%">
                <el-option v-for="d in defsStore.defs" :key="d.code" :label="d.code" :value="d.code" />
              </el-select>
              <el-input v-model="row.refFieldCode" size="small" placeholder="引用字段" style="width: 50%; margin-left: 4px" />
            </template>
            <template v-else-if="row.type === 'NUMBER'">
              <el-input-number v-model="row.min" size="small" placeholder="min" controls-position="right" style="width: 45%" />
              <el-input-number v-model="row.max" size="small" placeholder="max" controls-position="right" style="width: 45%; margin-left: 4px" />
            </template>
            <el-input v-else v-model="row.defaultValue" size="small" placeholder="默认值（可选）" />
          </template>
        </el-table-column>
        <el-table-column label="操作" width="70">
          <template #default="{ $index }">
            <el-button size="small" text type="danger" @click="form.fields.splice($index, 1)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-button size="small" style="margin-top: 8px" @click="addField">+ 添加字段</el-button>

      <div class="step-toolbar" style="margin-top: 16px">
        <el-button type="primary" @click="save">保存配置定义</el-button>
        <el-button @click="drawerVisible = false">取消</el-button>
      </div>
    </el-drawer>

    <!-- 数据行管理抽屉 -->
    <el-drawer v-model="rowsVisible" :title="'数据行 - ' + rowsDefName" size="82%" destroy-on-close>
      <div class="step-toolbar">
        <el-button type="primary" size="small" @click="openRowEdit(null)">新增行</el-button>
        <el-button size="small" @click="loadRows">刷新</el-button>
        <el-tag size="small" type="info">{{ rows.length }} 行（生效数据）</el-tag>
      </div>
      <el-table :data="rows" size="small" border max-height="520">
        <el-table-column label="范围" width="110" v-if="rowsDef && rowsDef.level !== 'GLOBAL'">
          <template #default="{ row }">{{ row.scope }}</template>
        </el-table-column>
        <el-table-column v-for="f in rowsDef ? rowsDef.fields : []" :key="f.code" :label="f.label"
                         min-width="110" show-overflow-tooltip>
          <template #default="{ row }">
            <el-tag v-if="f.type === 'BOOLEAN'" size="small" :type="row.data[f.code] ? 'success' : 'info'">
              {{ row.data[f.code] ? '是' : '否' }}
            </el-tag>
            <span v-else>{{ row.data[f.code] }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="130" fixed="right">
          <template #default="{ row }">
            <el-button size="small" text type="primary" @click="openRowEdit(row)">编辑</el-button>
            <el-button size="small" text type="danger" @click="removeRow(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-drawer>

    <!-- 行编辑对话框（动态表单渲染） -->
    <el-dialog v-model="rowEditVisible" :title="rowEditId ? '编辑行' : '新增行'" width="560px" destroy-on-close>
      <DynamicForm v-if="rowsDef" :def="rowsDef" mode="row" :model="rowForm" />
      <template #footer>
        <el-button @click="rowEditVisible = false">取消</el-button>
        <el-button type="primary" @click="saveRow">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useDefsStore } from '../stores/defs'
import { api } from '../api'
import DynamicForm from '../components/DynamicForm.vue'

const defsStore = useDefsStore()

const fieldTypes = [
  { value: 'TEXT', label: '文本' },
  { value: 'TEXTAREA', label: '长文本' },
  { value: 'NUMBER', label: '数字' },
  { value: 'BOOLEAN', label: '布尔' },
  { value: 'DATE', label: '日期' },
  { value: 'SELECT', label: '下拉' },
  { value: 'REFERENCE', label: '引用' }
]

const drawerVisible = ref(false)
const editingCode = ref(null)
const form = reactive(emptyForm())

const rowsVisible = ref(false)
const rowsDef = ref(null)
const rowsDefName = ref('')
const rows = ref([])

const rowEditVisible = ref(false)
const rowEditId = ref(null)
const rowForm = reactive({})

onMounted(async () => {
  if (!defsStore.defs.length) await defsStore.load()
})

function emptyForm() {
  return {
    code: '', name: '', level: 'GLOBAL', description: '', dependsOn: [],
    fields: [], sortOrder: 0, enabled: true
  }
}

function levelLabel(l) { return { GLOBAL: '全局', REGION: '地区', PROJECT: '项目' }[l] || l }
function typeLabel(t) {
  return fieldTypes.find(x => x.value === t) ? fieldTypes.find(x => x.value === t).label : t
}

function openEditor(def) {
  editingCode.value = def ? def.code : null
  Object.assign(form, emptyForm())
  if (def) {
    form.code = def.code
    form.name = def.name
    form.level = def.level
    form.description = def.description || ''
    form.dependsOn = [...def.dependsOn]
    form.sortOrder = def.sortOrder
    form.enabled = def.enabled
    form.fields = def.fields.map(f => ({
      ...f,
      optionsText: (f.options || []).join(',')
    }))
  }
  drawerVisible.value = true
}

function addField() {
  form.fields.push({ code: '', label: '', type: 'TEXT', required: false, optionsText: '', options: [], min: null, max: null })
}

async function save() {
  if (!form.code || !form.name) {
    ElMessage.warning('请填写配置编码与名称')
    return
  }
  const payload = {
    code: form.code.trim().toUpperCase(),
    name: form.name.trim(),
    level: form.level,
    description: form.description,
    dependsOn: form.dependsOn,
    sortOrder: form.sortOrder,
    enabled: form.enabled,
    fields: form.fields.map(f => ({
      code: (f.code || '').trim(),
      label: (f.label || '').trim(),
      type: f.type,
      required: !!f.required,
      options: f.type === 'SELECT' ? String(f.optionsText || '').split(/[,，]/).map(s => s.trim()).filter(Boolean) : [],
      min: f.min ?? null,
      max: f.max ?? null,
      refDefCode: f.type === 'REFERENCE' ? f.refDefCode : null,
      refFieldCode: f.type === 'REFERENCE' ? (f.refFieldCode || '').trim() : null,
      defaultValue: f.defaultValue || null
    })).filter(f => f.code && f.label)
  }
  try {
    if (editingCode.value) {
      await api.put(`/api/defs/${editingCode.value}`, payload)
      ElMessage.success('配置定义已更新（动态渲染即时生效）')
    } else {
      await api.post('/api/defs', payload)
      ElMessage.success('配置定义已创建')
    }
    drawerVisible.value = false
    await defsStore.load()
  } catch (e) {
    ElMessage.error(e.message)
  }
}

async function toggle(def) {
  await api.post(`/api/defs/${def.code}/toggle`)
  await defsStore.load()
}

async function remove(def) {
  try {
    await ElMessageBox.confirm(`确认删除配置「${def.name}（${def.code}）」？`, '删除确认', { type: 'warning' })
  } catch {
    return
  }
  try {
    await api.del(`/api/defs/${def.code}`)
    ElMessage.success('已删除')
    await defsStore.load()
  } catch (e) {
    ElMessage.error(e.message)
  }
}

async function openRows(def) {
  rowsDef.value = def
  rowsDefName.value = `${def.name}（${def.code}）`
  rowsVisible.value = true
  await loadRows()
}

async function loadRows() {
  const data = await api.get(`/api/data/rows?defCode=${rowsDef.value.code}&published=true&page=1&size=1000`)
  rows.value = data.rows
}

function openRowEdit(row) {
  rowEditId.value = row ? row.id : null
  for (const k of Object.keys(rowForm)) delete rowForm[k]
  if (row) {
    Object.assign(rowForm, { ...row.data, __scope__: row.scope })
  } else {
    rowForm.__scope__ = ''
    rowsDef.value.fields.forEach(f => {
      if (f.type === 'BOOLEAN') rowForm[f.code] = false
    })
  }
  rowEditVisible.value = true
}

async function saveRow() {
  const data = { ...rowForm }
  const scope = data.__scope__
  delete data.__scope__
  try {
    if (rowEditId.value) {
      await api.put(`/api/data/rows/${rowEditId.value}?defCode=${rowsDef.value.code}`, { scope, data })
    } else {
      await api.post(`/api/data/rows?defCode=${rowsDef.value.code}`, { scope, data, published: true })
    }
    ElMessage.success('已保存')
    rowEditVisible.value = false
    await loadRows()
    await defsStore.load()
  } catch (e) {
    ElMessage.error(e.message)
  }
}

async function removeRow(row) {
  try {
    await ElMessageBox.confirm('确认删除该行数据？', '删除确认', { type: 'warning' })
  } catch {
    return
  }
  await api.del(`/api/data/rows/${row.id}`)
  await loadRows()
  await defsStore.load()
}
</script>

<style scoped>
.step-toolbar {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 14px;
  flex-wrap: wrap;
}
.form-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 0 24px;
}
</style>
