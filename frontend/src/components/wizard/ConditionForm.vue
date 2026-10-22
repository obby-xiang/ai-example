<template>
  <div>
    <div v-for="(row, index) in rows" :key="index" class="flex gap-2 mb-2 items-center">
      <el-select
        v-model="row.field"
        :disabled="props.disabled"
        :placeholder="t('wizard.condition.field')"
        class="w-[180px]"
        v-bind="aiAttrs(row, 'field', { role: 'combobox', 'data-ai-verbs': 'highlight,scroll', 'data-ai-label': aiLabelOf(row, '字段') })"
        @change="onFieldChange(row)"
      >
        <el-option
          v-for="field in fields"
          :key="field.code"
          :label="`${field.label}（${field.code}）`"
          :value="field.code"
        />
      </el-select>
      <el-select
        v-model="row.operator"
        :disabled="props.disabled"
        :placeholder="t('wizard.condition.operator')"
        class="w-[130px]"
        v-bind="aiAttrs(row, 'op', { role: 'combobox', 'data-ai-verbs': 'highlight,scroll', 'data-ai-label': aiLabelOf(row, '操作符') })"
      >
        <el-option
          v-for="option in operatorsOf(row.field)"
          :key="option.value"
          :label="option.label"
          :value="option.value"
        />
      </el-select>

      <template v-if="typeOf(row.field) === 'ENUM'">
        <el-select
          v-model="row.value"
          :disabled="props.disabled"
          :placeholder="t('wizard.condition.option')"
          class="w-[180px]"
          clearable
          filterable
          v-bind="aiAttrs(row, 'value', { role: 'combobox', 'data-ai-verbs': 'highlight,scroll', 'data-ai-label': aiLabelOf(row, '取值') })"
        >
          <el-option
            v-for="option in optionsOf(row.field)"
            :key="option.value"
            :label="option.label"
            :value="option.value"
          />
        </el-select>
      </template>
      <template v-else-if="typeOf(row.field) === 'BOOLEAN'">
        <el-select
          v-model="row.value"
          :disabled="props.disabled"
          :placeholder="t('wizard.condition.value')"
          class="w-[180px]"
          clearable
          v-bind="aiAttrs(row, 'value', { role: 'combobox', 'data-ai-verbs': 'highlight,scroll', 'data-ai-label': aiLabelOf(row, '取值') })"
        >
          <el-option :label="t('wizard.condition.yes')" value="true" />
          <el-option :label="t('wizard.condition.no')" value="false" />
        </el-select>
      </template>
      <template v-else-if="typeOf(row.field) === 'DATE'">
        <el-date-picker
          v-model="row.value"
          :disabled="props.disabled"
          type="date"
          value-format="YYYY-MM-DD"
          :placeholder="t('wizard.condition.date')"
          class="w-[180px]"
          v-bind="aiAttrs(row, 'value', { role: 'textbox', 'data-ai-verbs': 'highlight,scroll', 'data-ai-label': aiLabelOf(row, '日期') })"
        />
      </template>
      <template v-else>
        <el-input
          v-model="row.value"
          :disabled="props.disabled || !operatorNeedsValue(row.operator)"
          :placeholder="valuePlaceholder(row)"
          class="w-[180px]"
          v-bind="aiAttrs(row, 'value', {
            'data-ai-fill': 'allow',
            'data-ai-risk': 'mutate-local',
            'data-ai-verbs': 'fill,highlight,scroll',
            'data-ai-label': aiLabelOf(row, '取值')
          })"
        />
      </template>

      <el-input
        v-if="row.operator === 'BETWEEN'"
        v-model="row.value2"
        :disabled="props.disabled"
        :placeholder="t('wizard.condition.to')"
        class="w-[140px]"
        v-bind="aiAttrs(row, 'value2', {
          'data-ai-fill': 'allow',
          'data-ai-risk': 'mutate-local',
          'data-ai-verbs': 'fill,highlight,scroll',
          'data-ai-label': aiLabelOf(row, '上限')
        })"
      />
      <el-button
        :icon="Delete"
        circle
        size="small"
        :disabled="props.disabled"
        v-bind="aiAttrs(row, 'remove', {
          'data-ai-risk': 'mutate-local',
          'data-ai-verbs': 'click,highlight',
          'data-ai-label': aiLabelOf(row, '删除条件')
        })"
        @click="removeRow(index)"
      />

    </div>

    <div class="flex items-center gap-2">
      <el-button
        size="small"
        :icon="Plus"
        :disabled="props.disabled || fields.length === 0"
        v-bind="aiAttrs(null, 'add', { 'data-ai-risk': 'mutate-local', 'data-ai-verbs': 'click,highlight', 'data-ai-label': '添加条件' })"
        @click="addRow"
      >{{ t('wizard.condition.add') }}</el-button>
      <span class="text-xs text-[#c0c4cc]">{{ hint }}</span>
    </div>
  </div>
</template>

<script setup lang="ts">
import { useI18n } from 'vue-i18n'
/**
 * 动态条件表单（类型感知），导出向导第 2 步与数据浏览页共用。
 *
 * 规则来源 `types/condition.ts`（后端口径的镜像）：
 * - 可选操作符随字段类型变（数值/日期有区间，枚举/引用有 IN，字符串有 EMPTY/NOT_EMPTY）；
 * - `BETWEEN` 是前端草案，提交时展开为 GTE + LTE 两条（后端无 BETWEEN）；
 * - 取值为空即视为"该字段未填 → 不提交"（EMPTY/NOT_EMPTY 例外，本就不带值）。
 *
 * AI 标注（visop 批 B）：传入 `aiRefPrefix`（区域 id，如 `export.cond.CURRENCY`）时，
 * 每行的字段 / 操作符 / 取值 / 删除与「添加条件」带 `data-ai-*`。选择类控件的**选择语义**
 * 仍由注册动作 `set_condition` 承担（见设计稿 §2.2 批 B 的 O-2 口径：原语只补「看得见」），
 * 因此它们的动词集收紧到 `highlight,scroll`；文本取值框才是 `fill` 写入口。
 */
import { computed, ref, watch } from 'vue'
import { Delete, Plus } from '@element-plus/icons-vue'
import {
  buildFieldConditions,
  emptyDraftModel,
  operatorNeedsValue,
  operatorsForFieldType,
  type ConditionDraft,
  type ConditionDraftModel,
  type DraftOperator,
  type OperatorOption
} from '@/types/condition'
import { parseFieldOptions, type ConfigField, type FieldType } from '@/types/definition'

const { t } = useI18n()

const props = withDefaults(
  defineProps<{
    fields: readonly ConfigField[]
    modelValue: ConditionDraftModel
    hint?: string
    /** 只读态：逐控件禁用（只读查看用）；与既有 disabled 条件取「或」，不覆盖 */
    disabled?: boolean
    /** AI 标注前缀（形如 `export.cond.CURRENCY`）；缺省不标注（数据浏览页零变化） */
    aiRefPrefix?: string
  }>(),
  {
    hint: '不设置条件表示导出全部数据',
    disabled: false,
    aiRefPrefix: ''
  }
)

const emit = defineEmits<{ (event: 'update:modelValue', value: ConditionDraftModel): void }>()

interface DraftRow extends ConditionDraft {
  field: string
}

const rows = ref<DraftRow[]>([])
let syncing = false

function rowsFromModel(model: ConditionDraftModel): DraftRow[] {
  return Object.entries(model.fields).map(([field, draft]) => ({
    field,
    operator: draft.operator,
    value: draft.value,
    value2: draft.value2
  }))
}

watch(
  () => props.modelValue,
  (model) => {
    syncing = true
    rows.value = rowsFromModel(model)
    syncing = false
  },
  { immediate: true, deep: true }
)

watch(
  rows,
  () => {
    if (syncing) {
      return
    }
    const model: ConditionDraftModel = emptyDraftModel(props.modelValue.scopeKeys)
    for (const row of rows.value) {
      if (!row.field) {
        continue
      }
      model.fields[row.field] = { operator: row.operator, value: row.value, value2: row.value2 }
    }
    // 与外部模型等价时不再回写（否则"父级回填 → 子级再 emit"会互相触发成环）
    if (JSON.stringify(model) === JSON.stringify(props.modelValue)) {
      return
    }
    emit('update:modelValue', model)
  },
  { deep: true }
)

const fieldMap = computed(() => new Map(props.fields.map((field) => [field.code, field])))

/**
 * AI 标注属性：ref = `<前缀>.<字段编码>.<部位>`；未给前缀 / 行还没选字段时不挂任何属性。
 *
 * 只读态统一补 `aria-disabled`：`el-select` / `el-date-picker` 的 root 是 div，
 * 快照的 state 推导读不到原生 `disabled`，不补就会把禁用控件显示成 enabled。
 */
function aiAttrs(row: DraftRow | null, part: string, extra: Record<string, string> = {}): Record<string, string> {
  if (!props.aiRefPrefix) {
    return {}
  }
  if (row !== null && !row.field) {
    return {}
  }
  const attrs: Record<string, string> = { ...extra }
  if (part !== 'add' && props.disabled) {
    attrs['aria-disabled'] = 'true'
  }
  attrs['data-ai-ref'] = row === null ? `${props.aiRefPrefix}.${part}` : `${props.aiRefPrefix}.${row.field}.${part}`
  return attrs
}

function aiLabelOf(row: DraftRow, suffix: string): string {
  const label = fieldMap.value.get(row.field)?.label ?? row.field
  return `${label} ${suffix}`
}

function typeOf(code: string): FieldType | null {
  return fieldMap.value.get(code)?.fieldType ?? null
}

function operatorsOf(code: string): OperatorOption[] {
  const type = typeOf(code)
  return type ? operatorsForFieldType(type) : operatorsForFieldType('STRING')
}

function optionsOf(code: string) {
  const field = fieldMap.value.get(code)
  return field ? parseFieldOptions(field) : []
}

function valuePlaceholder(row: DraftRow): string {
  if (!operatorNeedsValue(row.operator)) {
    return '该操作符不需要值'
  }
  return row.operator === 'IN' ? '多个值用英文逗号分隔' : '值'
}

function addRow(): void {
  const first = props.fields[0]
  if (!first) {
    return
  }
  rows.value.push({ field: first.code, operator: 'EQ', value: '', value2: '' })
}

function removeRow(index: number): void {
  rows.value.splice(index, 1)
}

function onFieldChange(row: DraftRow): void {
  row.value = ''
  row.value2 = ''
  const allowed = operatorsOf(row.field).map((option) => option.value as DraftOperator)
  if (!allowed.includes(row.operator)) {
    row.operator = 'EQ'
  }
}

/** 当前草案展开成后端条件（预览行数/提交共用，保证展示与提交同口径）。 */
const fieldConditions = computed(() => {
  const model = emptyDraftModel(props.modelValue.scopeKeys)
  for (const row of rows.value) {
    if (row.field) {
      model.fields[row.field] = { operator: row.operator, value: row.value, value2: row.value2 }
    }
  }
  return buildFieldConditions(model)
})

defineExpose({ fieldConditions })
</script>
