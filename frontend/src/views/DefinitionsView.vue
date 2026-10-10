<template>
  <div>
    <div class="flex justify-between items-center mb-4">
      <div>
        <h2 class="m-0 text-xl text-[#303133]">配置定义</h2>
        <div class="mt-1 text-[13px] text-[#909399]">
          定义与字段浏览（层级 / 关键词过滤 + 模板下载 + 字段编辑）；主键标志的 JSON 名是
          <span class="font-mono">key</span>（不是 isKey）
        </div>
      </div>
      <div>
        <el-button :icon="Refresh" circle title="刷新" @click="loadDefs" />
        <el-button type="primary" :icon="Plus" @click="openEditor(null)">新建配置定义</el-button>
      </div>
    </div>

    <div class="flex items-center gap-2 mb-3">
      <el-radio-group v-model="levelFilter" size="small" @change="loadDefs">
        <el-radio-button value="">全部层级</el-radio-button>
        <el-radio-button value="GLOBAL">全局</el-radio-button>
        <el-radio-button value="REGION">地区</el-radio-button>
        <el-radio-button value="PROJECT">项目</el-radio-button>
      </el-radio-group>
      <el-input
        v-model="keyword"
        placeholder="搜索编码 / 名称"
        clearable
        class="w-[220px]"
        @keyup.enter="loadDefs"
        @clear="loadDefs"
      />
      <el-button type="primary" @click="loadDefs">查询</el-button>
    </div>

    <el-table v-loading="loading" :data="defs" border>
      <el-table-column prop="code" label="配置编码" width="170" />
      <el-table-column prop="name" label="配置名称" min-width="160" />
      <el-table-column label="层级" width="90">
        <template #default="{ row }: { row: ConfigDefinition }">
          <el-tag size="small" :type="levelType(row.level)">{{ levelLabel(row.level) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="字段（表头）" min-width="260" show-overflow-tooltip>
        <template #default="{ row }: { row: ConfigDefinition }">
          <span class="text-[#606266]">
            {{ (row.fields ?? []).map((field) => `${field.label}(${typeLabel(field.fieldType)})`).join('、') || '—' }}
          </span>
        </template>
      </el-table-column>
      <el-table-column label="主键" width="140" show-overflow-tooltip>
        <template #default="{ row }: { row: ConfigDefinition }">
          <span class="text-[#606266]">{{ keyFieldsOf(row).join('、') || '—' }}</span>
        </template>
      </el-table-column>
      <el-table-column label="依赖" width="150" show-overflow-tooltip>
        <template #default="{ row }: { row: ConfigDefinition }">
          <span v-if="dependenciesOf(row).length" class="text-[#606266]">{{ dependenciesOf(row).join('、') }}</span>
          <span v-else class="text-[#c0c4cc]">无</span>
        </template>
      </el-table-column>
      <el-table-column prop="description" label="描述" min-width="160" show-overflow-tooltip />
      <el-table-column label="操作" width="240" fixed="right">
        <template #default="{ row }: { row: ConfigDefinition }">
          <el-button size="small" type="primary" link @click="openDetail(row)">详情</el-button>
          <el-button size="small" type="primary" link @click="openEditor(row)">编辑字段</el-button>
          <el-button size="small" link :loading="templateLoading === row.code" @click="downloadTemplate(row)">
            下载模板
          </el-button>
          <el-button size="small" link @click="openData(row)">数据</el-button>
        </template>
      </el-table-column>
      <template #empty>
        <el-empty description="暂无配置定义" :image-size="60" />
      </template>
    </el-table>

    <!-- 定义详情：字段 / 层级 / 依赖 -->
    <el-drawer v-model="detailVisible" size="760px" :title="detailTitle">
      <template v-if="detail">
        <el-descriptions :column="2" border size="small" class="mb-4">
          <el-descriptions-item label="配置编码">{{ detail.code }}</el-descriptions-item>
          <el-descriptions-item label="配置名称">{{ detail.name }}</el-descriptions-item>
          <el-descriptions-item label="层级">{{ levelLabel(detail.level) }}</el-descriptions-item>
          <el-descriptions-item label="字段数">{{ detail.fields?.length ?? 0 }}</el-descriptions-item>
          <el-descriptions-item label="依赖配置">{{ dependenciesOf(detail).join('、') || '无' }}</el-descriptions-item>
          <el-descriptions-item label="主键字段">{{ keyFieldsOf(detail).join('、') || '—' }}</el-descriptions-item>
          <el-descriptions-item label="描述" :span="2">{{ detail.description || '—' }}</el-descriptions-item>
        </el-descriptions>

        <div class="mb-2 text-[13px] text-[#303133]">字段清单（按 sortOrder）</div>
        <el-table :data="detail.fields ?? []" size="small" border>
          <el-table-column prop="sortOrder" label="排序" min-width="48" />
          <el-table-column prop="code" label="字段编码" min-width="120" />
          <el-table-column prop="label" label="字段名称（表头）" min-width="134" />
          <el-table-column label="类型" min-width="120">
            <template #default="{ row }: { row: ConfigField }">
              <el-tag size="small">{{ typeLabel(row.fieldType) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="必填" min-width="48">
            <template #default="{ row }: { row: ConfigField }">{{ row.required ? '是' : '否' }}</template>
          </el-table-column>
          <el-table-column label="主键" min-width="58">
            <template #default="{ row }: { row: ConfigField }">
              <el-tag v-if="row.key" size="small" type="danger">主键</el-tag>
              <span v-else>—</span>
            </template>
          </el-table-column>
          <el-table-column label="选项 / 引用" min-width="184">
            <template #default="{ row }: { row: ConfigField }">
              <span v-if="row.fieldType === 'ENUM'">
                {{ parseFieldOptions(row).map((option) => `${option.value}=${option.label}`).join('、') || '—' }}
              </span>
              <span v-else-if="row.fieldType === 'REFERENCE'">
                {{ row.refDefCode }}.{{ row.refFieldCode }}
              </span>
              <span v-else class="text-[#c0c4cc]">—</span>
            </template>
          </el-table-column>
        </el-table>

        <div class="mt-4 flex gap-2">
          <el-button size="small" @click="downloadTemplate(detail)">下载导入模板</el-button>
          <el-button size="small" @click="openData(detail)">查看数据行</el-button>
          <el-button size="small" type="primary" @click="openEditor(detail)">编辑字段</el-button>
        </div>
      </template>
    </el-drawer>

    <!-- 定义 / 字段编辑 -->
    <el-drawer v-model="editorVisible" size="82%" :title="editingCode ? `编辑配置定义 - ${editingCode}` : '新建配置定义'">
      <el-form label-width="auto" size="small">
        <div class="grid grid-cols-2 gap-x-6">
          <el-form-item label="配置编码" required>
            <el-input v-model="form.code" :disabled="editingCode !== null" placeholder="如 TAX_RATE（唯一，用于文件名匹配）" />
          </el-form-item>
          <el-form-item label="配置名称" required>
            <el-input v-model="form.name" placeholder="如 税率配置" />
          </el-form-item>
          <el-form-item label="层级" required>
            <el-select v-model="form.level" class="w-full">
              <el-option label="全局（GLOBAL）" value="GLOBAL" />
              <el-option label="地区（REGION）" value="REGION" />
              <el-option label="项目（PROJECT）" value="PROJECT" />
            </el-select>
          </el-form-item>
          <el-form-item label="排序">
            <el-input-number v-model="form.sortOrder" :min="0" />
          </el-form-item>
          <el-form-item label="说明" class="col-span-2">
            <el-input v-model="form.description" type="textarea" :rows="2" />
          </el-form-item>
        </div>
      </el-form>

      <el-divider content-position="left">字段定义（表头，动态渲染与校验依据）</el-divider>
      <el-table :data="form.fields" size="small" border>
        <el-table-column label="字段编码" width="150">
          <template #default="{ row }: { row: EditableField }">
            <el-input v-model="row.code" size="small" placeholder="如 rate_value" />
          </template>
        </el-table-column>
        <el-table-column label="字段名称（表头）" width="160">
          <template #default="{ row }: { row: EditableField }">
            <el-input v-model="row.label" size="small" placeholder="如 税率值" />
          </template>
        </el-table-column>
        <el-table-column label="类型" width="130">
          <template #default="{ row }: { row: EditableField }">
            <el-select v-model="row.fieldType" size="small">
              <el-option v-for="type in FIELD_TYPES" :key="type.value" :label="type.label" :value="type.value" />
            </el-select>
          </template>
        </el-table-column>
        <el-table-column label="必填" width="70">
          <template #default="{ row }: { row: EditableField }">
            <el-switch v-model="row.required" size="small" />
          </template>
        </el-table-column>
        <el-table-column label="主键" width="70">
          <template #default="{ row }: { row: EditableField }">
            <el-switch v-model="row.key" size="small" />
          </template>
        </el-table-column>
        <el-table-column label="选项 / 引用" min-width="260">
          <template #default="{ row }: { row: EditableField }">
            <el-input
              v-if="row.fieldType === 'ENUM'"
              v-model="row.optionsText"
              size="small"
              placeholder="逗号分隔：值=标签，如 1=普通,2=优惠"
            />
            <div v-else-if="row.fieldType === 'REFERENCE'" class="flex gap-1">
              <el-select v-model="row.refDefCode" size="small" placeholder="引用配置" class="w-1/2">
                <el-option
                  v-for="candidate in refCandidates"
                  :key="candidate.code"
                  :label="`${candidate.name}（${candidate.code}）`"
                  :value="candidate.code"
                />
              </el-select>
              <el-input v-model="row.refFieldCode" size="small" placeholder="引用字段编码" class="w-1/2" />
            </div>
            <span v-else class="text-[#c0c4cc]">—</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="70">
          <template #default="{ $index }">
            <el-button size="small" type="danger" link @click="form.fields.splice($index, 1)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-button size="small" :icon="Plus" class="mt-2" @click="addField">添加字段</el-button>
      <div class="mt-1 text-xs text-[#909399]">
        后端约束：字段编码须以字母开头、同定义内唯一、至少一个主键；REFERENCE 必须指定引用配置与引用字段。
      </div>

      <div class="mt-4 pt-3 border-t border-solid border-[#ebeef5] flex gap-2">
        <el-button type="primary" :loading="saving" @click="save">保存配置定义</el-button>
        <el-button @click="editorVisible = false">取消</el-button>
      </div>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
/**
 * 配置定义页（五页之④）：定义列表 / 详情（字段 + 层级 + 依赖）/ 字段编辑 / 模板下载。
 *
 * 功能面参照 deepseek `DefsAdminView.vue`（定义 + 字段在线编辑 + 模板下载），
 * 视觉按 kimi 蓝本整体风格（内联 Tailwind + Element Plus，无自造组件、无手写 CSS）。
 * 字段编辑走 `PUT /api/definitions/{code}`（后端按 update 里的 fields 整体替换）
 * —— 因此字段的增删改与定义属性一次提交，无需逐字段接口。
 */
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Plus, Refresh } from '@element-plus/icons-vue'
import { definitionsApi } from '@/api/definitions'
import { useWorkspaceStore } from '@/stores/workspace'
import { downloadBlob } from '@/utils/excel'
import { parseFieldOptions, type ConfigDefinition, type ConfigField, type ConfigLevel, type FieldType } from '@/types/definition'

interface EditableField {
  code: string
  label: string
  fieldType: FieldType
  required: boolean
  key: boolean
  optionsText: string
  refDefCode: string
  refFieldCode: string
}

interface EditableDefinition {
  code: string
  name: string
  level: ConfigLevel
  description: string
  sortOrder: number
  fields: EditableField[]
}

const FIELD_TYPES: Array<{ value: FieldType; label: string }> = [
  { value: 'STRING', label: '文本' },
  { value: 'NUMBER', label: '数字' },
  { value: 'DATE', label: '日期' },
  { value: 'ENUM', label: '枚举（下拉）' },
  { value: 'BOOLEAN', label: '布尔' },
  { value: 'REFERENCE', label: '引用（跨配置）' }
]

const workspace = useWorkspaceStore()
const router = useRouter()

const defs = ref<ConfigDefinition[]>([])
const loading = ref(false)
const levelFilter = ref<'' | ConfigLevel>('')
const keyword = ref('')
const templateLoading = ref<string | null>(null)

const detailVisible = ref(false)
const detail = ref<ConfigDefinition | null>(null)

const editorVisible = ref(false)
const editingCode = ref<string | null>(null)
const saving = ref(false)
const form = reactive<EditableDefinition>(emptyForm())

const detailTitle = computed(() => (detail.value ? `配置详情 · ${detail.value.name}` : '配置详情'))
const refCandidates = computed(() => defs.value.filter((item) => item.code !== editingCode.value))

function emptyForm(): EditableDefinition {
  return { code: '', name: '', level: 'GLOBAL', description: '', sortOrder: 0, fields: [] }
}

function levelType(level: ConfigLevel): 'success' | 'warning' | 'primary' {
  return level === 'GLOBAL' ? 'success' : level === 'REGION' ? 'warning' : 'primary'
}

function levelLabel(level: ConfigLevel): string {
  return level === 'GLOBAL' ? '全局' : level === 'REGION' ? '地区' : '项目'
}

function typeLabel(type: FieldType): string {
  return FIELD_TYPES.find((item) => item.value === type)?.label ?? type
}

function keyFieldsOf(def: ConfigDefinition): string[] {
  return (def.fields ?? []).filter((field) => field.key).map((field) => field.code)
}

function dependenciesOf(def: ConfigDefinition): string[] {
  const codes = new Set<string>()
  for (const field of def.fields ?? []) {
    if (field.fieldType === 'REFERENCE' && field.refDefCode) {
      codes.add(field.refDefCode)
    }
  }
  return [...codes]
}

async function loadDefs(): Promise<void> {
  loading.value = true
  try {
    const params: { level?: ConfigLevel; keyword?: string } = {}
    if (levelFilter.value) {
      params.level = levelFilter.value
    }
    if (keyword.value.trim()) {
      params.keyword = keyword.value.trim()
    }
    defs.value = await definitionsApi.listDefinitions(params)
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '配置定义加载失败')
  } finally {
    loading.value = false
  }
}

function openDetail(def: ConfigDefinition): void {
  detail.value = def
  detailVisible.value = true
}

function openData(def: ConfigDefinition): void {
  void router.push({ name: 'data-browser', query: { defCode: def.code } })
}

async function downloadTemplate(def: ConfigDefinition): Promise<void> {
  templateLoading.value = def.code
  try {
    const blob = await definitionsApi.downloadTemplate(def.code)
    downloadBlob(blob, `${def.code}_${def.name}.xlsx`)
    ElMessage.success(`已下载 ${def.code} 的导入模板`)
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '模板下载失败')
  } finally {
    templateLoading.value = null
  }
}

function openEditor(def: ConfigDefinition | null): void {
  editingCode.value = def ? def.code : null
  Object.assign(form, emptyForm())
  if (def) {
    form.code = def.code
    form.name = def.name
    form.level = def.level
    form.description = def.description ?? ''
    form.sortOrder = def.sortOrder
    form.fields = (def.fields ?? []).map((field) => ({
      code: field.code,
      label: field.label,
      fieldType: field.fieldType,
      required: field.required,
      key: field.key,
      optionsText: parseFieldOptions(field).map((option) => `${option.value}=${option.label}`).join(','),
      refDefCode: field.refDefCode ?? '',
      refFieldCode: field.refFieldCode ?? ''
    }))
  }
  editorVisible.value = true
}

function addField(): void {
  form.fields.push({
    code: '',
    label: '',
    fieldType: 'STRING',
    required: false,
    key: false,
    optionsText: '',
    refDefCode: '',
    refFieldCode: ''
  })
}

/** `值=标签` 列表 → optionsJson（同时接受只有值的写法，标签默认等于值）。 */
function buildOptionsJson(text: string): string | null {
  const items = text
    .split(/[,，]/)
    .map((item) => item.trim())
    .filter((item) => item !== '')
    .map((item) => {
      const [value, label] = item.split(/[=:：]/)
      const safeValue = (value ?? '').trim()
      return { value: safeValue, label: (label ?? safeValue).trim() || safeValue }
    })
    .filter((item) => item.value !== '')
  return items.length > 0 ? JSON.stringify(items) : null
}

function validateForm(): string | null {
  if (!form.code.trim()) {
    return '请填写配置编码'
  }
  if (!/^[A-Za-z][A-Za-z0-9_]*$/.test(form.code.trim())) {
    return '配置编码须以字母开头，只允许字母、数字、下划线'
  }
  if (!form.name.trim()) {
    return '请填写配置名称'
  }
  const codes = new Set<string>()
  for (const field of form.fields) {
    if (!field.code.trim() || !field.label.trim()) {
      return '字段编码与字段名称不能为空'
    }
    if (!/^[A-Za-z][A-Za-z0-9_]*$/.test(field.code.trim())) {
      return `字段编码 [${field.code}] 须以字母开头，只允许字母、数字、下划线`
    }
    if (codes.has(field.code.trim())) {
      return `字段编码重复：${field.code}`
    }
    codes.add(field.code.trim())
    if (field.fieldType === 'REFERENCE' && (!field.refDefCode || !field.refFieldCode.trim())) {
      return `REFERENCE 字段 [${field.code}] 必须指定引用配置与引用字段`
    }
  }
  if (form.fields.length > 0 && !form.fields.some((field) => field.key)) {
    return '至少需要一个主键字段'
  }
  return null
}

async function save(): Promise<void> {
  const problem = validateForm()
  if (problem) {
    ElMessage.warning(problem)
    return
  }
  const payload: ConfigDefinition = {
    code: form.code.trim().toUpperCase(),
    name: form.name.trim(),
    level: form.level,
    description: form.description.trim() || null,
    sortOrder: form.sortOrder,
    fields: form.fields.map((field, index): ConfigField => ({
      defCode: form.code.trim().toUpperCase(),
      code: field.code.trim(),
      label: field.label.trim(),
      fieldType: field.fieldType,
      required: field.required,
      key: field.key,
      sortOrder: index,
      optionsJson: field.fieldType === 'ENUM' ? buildOptionsJson(field.optionsText) : null,
      refDefCode: field.fieldType === 'REFERENCE' ? field.refDefCode : null,
      refFieldCode: field.fieldType === 'REFERENCE' ? field.refFieldCode.trim() : null
    }))
  }
  saving.value = true
  try {
    if (editingCode.value) {
      await definitionsApi.updateDefinition(editingCode.value, payload)
      ElMessage.success('配置定义已更新（字段结构即时生效）')
    } else {
      await definitionsApi.createDefinition(payload)
      ElMessage.success('配置定义已创建')
    }
    editorVisible.value = false
    await loadDefs()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '保存失败')
  } finally {
    saving.value = false
  }
}

onMounted(async () => {
  workspace.enterPage({ pageId: 'definitions', page: 'definitions', taskType: null, step: null, taskId: null })
  await loadDefs()
})
</script>
