<template>
  <div>
    <div class="flex justify-between items-center mb-4">
      <div>
        <h2 class="m-0 text-xl text-[#303133]">{{ t('views.definitions.title') }}</h2>
        <div class="mt-1 text-[13px] text-[#909399]">
          {{ t('views.definitions.subtitle1') }}
          <span class="font-mono">key</span>{{ t('views.definitions.subtitle2') }}
        </div>
      </div>
      <div>
        <el-button :icon="Refresh" circle :title="t('views.definitions.refresh')" @click="loadDefs" />
        <el-button type="primary" :icon="Plus" @click="openEditor(null)">{{ t('views.definitions.create') }}</el-button>
      </div>
    </div>

    <div class="flex items-center gap-2 mb-3">
      <el-radio-group v-model="levelFilter" size="small" @change="loadDefs">
        <el-radio-button value="">{{ t('views.definitions.allLevels') }}</el-radio-button>
        <el-radio-button value="GLOBAL">{{ t('views.definitions.global') }}</el-radio-button>
        <el-radio-button value="REGION">{{ t('views.definitions.region') }}</el-radio-button>
        <el-radio-button value="PROJECT">{{ t('views.definitions.project') }}</el-radio-button>
      </el-radio-group>
      <el-input
        v-model="keyword"
        :placeholder="t('views.definitions.searchPlaceholder')"
        clearable
        class="w-[220px]"
        @keyup.enter="loadDefs"
        @clear="loadDefs"
      />
      <el-button type="primary" @click="loadDefs">{{ t('views.definitions.search') }}</el-button>
    </div>

    <el-table v-loading="loading" :data="defs" border>
      <el-table-column prop="code" :label="t('views.definitions.code')" width="170" />
      <el-table-column prop="name" :label="t('views.definitions.name')" min-width="160" />
      <el-table-column :label="t('views.definitions.level')" width="90">
        <template #default="{ row }: { row: ConfigDefinition }">
          <el-tag size="small" :type="levelType(row.level)">{{ levelLabel(row.level) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column :label="t('views.definitions.fields')" min-width="260" show-overflow-tooltip>
        <template #default="{ row }: { row: ConfigDefinition }">
          <span class="text-[#606266]">
            {{ (row.fields ?? []).map((field) => `${field.label}(${typeLabel(field.fieldType)})`).join('、') || '—' }}
          </span>
        </template>
      </el-table-column>
      <el-table-column :label="t('views.definitions.key')" width="140" show-overflow-tooltip>
        <template #default="{ row }: { row: ConfigDefinition }">
          <span class="text-[#606266]">{{ keyFieldsOf(row).join('、') || '—' }}</span>
        </template>
      </el-table-column>
      <el-table-column :label="t('views.definitions.dependencies')" width="150" show-overflow-tooltip>
        <template #default="{ row }: { row: ConfigDefinition }">
          <span v-if="dependenciesOf(row).length" class="text-[#606266]">{{ dependenciesOf(row).join('、') }}</span>
          <span v-else class="text-[#c0c4cc]">{{ t('views.definitions.none') }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="description" :label="t('views.definitions.description')" min-width="160" show-overflow-tooltip />
      <el-table-column :label="t('views.definitions.actions')" width="240" fixed="right">
        <template #default="{ row }: { row: ConfigDefinition }">
          <el-button size="small" type="primary" link @click="openDetail(row)">{{ t('views.definitions.detail') }}</el-button>
          <el-button size="small" type="primary" link @click="openEditor(row)">{{ t('views.definitions.editFields') }}</el-button>
          <el-button size="small" link :loading="templateLoading === row.code" @click="downloadTemplate(row)">
            {{ t('views.definitions.downloadTemplate') }}
          </el-button>
          <el-button size="small" link @click="openData(row)">{{ t('views.definitions.data') }}</el-button>
        </template>
      </el-table-column>
      <template #empty>
        <el-empty :description="t('views.definitions.empty')" :image-size="60" />
      </template>
    </el-table>

    <!-- 定义详情：字段 / 层级 / 依赖 -->
    <el-drawer v-model="detailVisible" size="760px" :title="detailTitle">
      <template v-if="detail">
        <el-descriptions :column="2" border size="small" class="mb-4">
          <el-descriptions-item :label="t('views.definitions.code')">{{ detail.code }}</el-descriptions-item>
          <el-descriptions-item :label="t('views.definitions.name')">{{ detail.name }}</el-descriptions-item>
          <el-descriptions-item :label="t('views.definitions.level')">{{ levelLabel(detail.level) }}</el-descriptions-item>
          <el-descriptions-item :label="t('views.definitions.fieldCount')">{{ detail.fields?.length ?? 0 }}</el-descriptions-item>
          <el-descriptions-item :label="t('views.definitions.dependenciesOf')">{{ dependenciesOf(detail).join('、') || t('views.definitions.none') }}</el-descriptions-item>
          <el-descriptions-item :label="t('views.definitions.keyFields')">{{ keyFieldsOf(detail).join('、') || '—' }}</el-descriptions-item>
          <el-descriptions-item :label="t('views.definitions.description')" :span="2">{{ detail.description || '—' }}</el-descriptions-item>
        </el-descriptions>

        <div class="mb-2 text-[13px] text-[#303133]">{{ t('views.definitions.fieldList') }}</div>
        <el-table :data="detail.fields ?? []" size="small" border>
          <el-table-column prop="sortOrder" :label="t('views.definitions.sortOrder')" min-width="48" />
          <el-table-column prop="code" :label="t('views.definitions.fieldCode')" min-width="120" />
          <el-table-column prop="label" :label="t('views.definitions.fieldLabel')" min-width="134" />
          <el-table-column :label="t('views.definitions.type')" min-width="120">
            <template #default="{ row }: { row: ConfigField }">
              <el-tag size="small">{{ typeLabel(row.fieldType) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column :label="t('views.definitions.required')" min-width="48">
            <template #default="{ row }: { row: ConfigField }">{{ row.required ? t('views.definitions.yes') : t('views.definitions.no') }}</template>
          </el-table-column>
          <el-table-column :label="t('views.definitions.key')" min-width="58">
            <template #default="{ row }: { row: ConfigField }">
              <el-tag v-if="row.key" size="small" type="danger">{{ t('views.definitions.key') }}</el-tag>
              <span v-else>—</span>
            </template>
          </el-table-column>
          <el-table-column :label="t('views.definitions.optionsRef')" min-width="184">
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
          <el-button size="small" @click="downloadTemplate(detail)">{{ t('views.definitions.downloadImportTemplate') }}</el-button>
          <el-button size="small" @click="openData(detail)">{{ t('views.definitions.viewData') }}</el-button>
          <el-button size="small" type="primary" @click="openEditor(detail)">{{ t('views.definitions.editFields') }}</el-button>
        </div>
      </template>
    </el-drawer>

    <!-- 定义 / 字段编辑 -->
    <el-drawer v-model="editorVisible" size="82%" :title="editingCode ? t('views.definitions.editTitle', { code: editingCode }) : t('views.definitions.create')">
      <el-form label-width="auto" size="small">
        <div class="grid grid-cols-2 gap-x-6">
          <el-form-item :label="t('views.definitions.code')" required>
            <el-input v-model="form.code" :disabled="editingCode !== null" :placeholder="t('views.definitions.codePlaceholder')" />
          </el-form-item>
          <el-form-item :label="t('views.definitions.name')" required>
            <el-input v-model="form.name" :placeholder="t('views.definitions.namePlaceholder')" />
          </el-form-item>
          <el-form-item :label="t('views.definitions.level')" required>
            <el-select v-model="form.level" class="w-full">
              <el-option :label="t('views.definitions.globalShort')" value="GLOBAL" />
              <el-option :label="t('views.definitions.regionShort')" value="REGION" />
              <el-option :label="t('views.definitions.projectShort')" value="PROJECT" />
            </el-select>
          </el-form-item>
          <el-form-item :label="t('views.definitions.sortOrder')">
            <el-input-number v-model="form.sortOrder" :min="0" />
          </el-form-item>
          <el-form-item :label="t('views.definitions.message')" class="col-span-2">
            <el-input v-model="form.description" type="textarea" :rows="2" />
          </el-form-item>
        </div>
      </el-form>

      <el-divider content-position="left">{{ t('views.definitions.fieldSection') }}</el-divider>
      <el-table :data="form.fields" size="small" border>
        <el-table-column :label="t('views.definitions.fieldCode')" width="150">
          <template #default="{ row }: { row: EditableField }">
            <el-input v-model="row.code" size="small" :placeholder="t('views.definitions.fieldCodePlaceholder')" />
          </template>
        </el-table-column>
        <el-table-column :label="t('views.definitions.fieldLabel')" width="160">
          <template #default="{ row }: { row: EditableField }">
            <el-input v-model="row.label" size="small" :placeholder="t('views.definitions.labelPlaceholder')" />
          </template>
        </el-table-column>
        <el-table-column :label="t('views.definitions.type')" width="130">
          <template #default="{ row }: { row: EditableField }">
            <el-select v-model="row.fieldType" size="small">
              <el-option v-for="type in FIELD_TYPES" :key="type.value" :label="type.label" :value="type.value" />
            </el-select>
          </template>
        </el-table-column>
        <el-table-column :label="t('views.definitions.required')" width="70">
          <template #default="{ row }: { row: EditableField }">
            <el-switch v-model="row.required" size="small" />
          </template>
        </el-table-column>
        <el-table-column :label="t('views.definitions.key')" width="70">
          <template #default="{ row }: { row: EditableField }">
            <el-switch v-model="row.key" size="small" />
          </template>
        </el-table-column>
        <el-table-column :label="t('views.definitions.optionsRef')" min-width="260">
          <template #default="{ row }: { row: EditableField }">
            <el-input
              v-if="row.fieldType === 'ENUM'"
              v-model="row.optionsText"
              size="small"
              :placeholder="t('views.definitions.optionsPlaceholder')"
            />
            <div v-else-if="row.fieldType === 'REFERENCE'" class="flex gap-1">
              <el-select v-model="row.refDefCode" size="small" :placeholder="t('views.definitions.refDefinition')" class="w-1/2">
                <el-option
                  v-for="candidate in refCandidates"
                  :key="candidate.code"
                  :label="`${candidate.name}（${candidate.code}）`"
                  :value="candidate.code"
                />
              </el-select>
              <el-input v-model="row.refFieldCode" size="small" :placeholder="t('views.definitions.refFieldCode')" class="w-1/2" />
            </div>
            <span v-else class="text-[#c0c4cc]">—</span>
          </template>
        </el-table-column>
        <el-table-column :label="t('views.definitions.actions')" width="70">
          <template #default="{ $index }">
            <el-button size="small" type="danger" link @click="form.fields.splice($index, 1)">{{ t('views.definitions.remove') }}</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-button size="small" :icon="Plus" class="mt-2" @click="addField">{{ t('views.definitions.addField') }}</el-button>
      <div class="mt-1 text-xs text-[#909399]">
        {{ t('views.definitions.backendRules') }}
      </div>

      <div class="mt-4 pt-3 border-t border-solid border-[#ebeef5] flex gap-2">
        <el-button type="primary" :loading="saving" @click="save">{{ t('views.definitions.save') }}</el-button>
        <el-button @click="editorVisible = false">{{ t('views.definitions.cancel') }}</el-button>
      </div>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { useI18n } from 'vue-i18n'
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

const { t } = useI18n()

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

/** 字段类型下拉（i18n L-69：label 由 locale 派生 —— 渲染期读 ⇒ 切语言即重算）。 */
const FIELD_TYPE_VALUES: readonly FieldType[] = ['STRING', 'NUMBER', 'DATE', 'ENUM', 'BOOLEAN', 'REFERENCE']
const FIELD_TYPES = computed(() => FIELD_TYPE_VALUES.map((value) => ({
  value,
  label: t(`views.definitions.types.${value}`)
})))

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

const detailTitle = computed(() => (
  detail.value
    ? t('views.definitions.detailTitle', { name: detail.value.name })
    : t('views.definitions.detailTitlePlain')
))
const refCandidates = computed(() => defs.value.filter((item) => item.code !== editingCode.value))

function emptyForm(): EditableDefinition {
  return { code: '', name: '', level: 'GLOBAL', description: '', sortOrder: 0, fields: [] }
}

function levelType(level: ConfigLevel): 'success' | 'warning' | 'primary' {
  return level === 'GLOBAL' ? 'success' : level === 'REGION' ? 'warning' : 'primary'
}

function levelLabel(level: ConfigLevel): string {
  return t(`views.definitions.${level.toLowerCase()}`)
}

function typeLabel(type: FieldType): string {
  return FIELD_TYPES.value.find((item) => item.value === type)?.label ?? type
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
    ElMessage.error(error instanceof Error ? error.message : t('views.definitions.msg.loadFailed'))
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
    ElMessage.success(t('views.definitions.msg.templateDownloaded', { code: def.code }))
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t('views.definitions.msg.templateDownloadFailed'))
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
    return t('views.definitions.validate.codeRequired')
  }
  if (!/^[A-Za-z][A-Za-z0-9_]*$/.test(form.code.trim())) {
    return t('views.definitions.validate.codeInvalid')
  }
  if (!form.name.trim()) {
    return t('views.definitions.validate.nameRequired')
  }
  const codes = new Set<string>()
  for (const field of form.fields) {
    if (!field.code.trim() || !field.label.trim()) {
      return t('views.definitions.validate.fieldKeyRequired')
    }
    if (!/^[A-Za-z][A-Za-z0-9_]*$/.test(field.code.trim())) {
      return t('views.definitions.validate.fieldCodeInvalid', { code: field.code })
    }
    if (codes.has(field.code.trim())) {
      return t('views.definitions.validate.fieldCodeDuplicate', { code: field.code })
    }
    codes.add(field.code.trim())
    if (field.fieldType === 'REFERENCE' && (!field.refDefCode || !field.refFieldCode.trim())) {
      return t('views.definitions.validate.referenceRequired', { code: field.code })
    }
  }
  if (form.fields.length > 0 && !form.fields.some((field) => field.key)) {
    return t('views.definitions.validate.keyFieldRequired')
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
      ElMessage.success(t('views.definitions.msg.savedUpdated'))
    } else {
      await definitionsApi.createDefinition(payload)
      ElMessage.success(t('views.definitions.msg.savedCreated'))
    }
    editorVisible.value = false
    await loadDefs()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t('views.definitions.msg.saveFailed'))
  } finally {
    saving.value = false
  }
}

onMounted(async () => {
  workspace.enterPage({ pageId: 'definitions', page: 'definitions', taskType: null, step: null, taskId: null })
  await loadDefs()
})
</script>
