<template>
  <div>
    <div v-for="(row, index) in rows" :key="index" class="flex gap-2 mb-2 items-center">
      <el-select
        v-model="row.field"
        placeholder="字段"
        class="w-[180px]"
        @change="onFieldChange(row)"
      >
        <el-option
          v-for="field in fields"
          :key="field.code"
          :label="`${field.label}（${field.code}）`"
          :value="field.code"
        />
      </el-select>
      <el-select v-model="row.operator" placeholder="操作符" class="w-[130px]">
        <el-option
          v-for="option in operatorsOf(row.field)"
          :key="option.value"
          :label="option.label"
          :value="option.value"
        />
      </el-select>

      <template v-if="typeOf(row.field) === 'ENUM'">
        <el-select v-model="row.value" placeholder="选项" class="w-[180px]" clearable filterable>
          <el-option
            v-for="option in optionsOf(row.field)"
            :key="option.value"
            :label="option.label"
            :value="option.value"
          />
        </el-select>
      </template>
      <template v-else-if="typeOf(row.field) === 'BOOLEAN'">
        <el-select v-model="row.value" placeholder="取值" class="w-[180px]" clearable>
          <el-option label="是（true）" value="true" />
          <el-option label="否（false）" value="false" />
        </el-select>
      </template>
      <template v-else-if="typeOf(row.field) === 'DATE'">
        <el-date-picker
          v-model="row.value"
          type="date"
          value-format="YYYY-MM-DD"
          placeholder="日期"
          class="w-[180px]"
        />
      </template>
      <template v-else>
        <el-input
          v-model="row.value"
          :disabled="!operatorNeedsValue(row.operator)"
          :placeholder="valuePlaceholder(row)"
          class="w-[180px]"
        />
      </template>

      <el-input
        v-if="row.operator === 'BETWEEN'"
        v-model="row.value2"
        placeholder="至"
        class="w-[140px]"
      />
      <el-button :icon="Delete" circle size="small" @click="removeRow(index)" />
    </div>

    <div class="flex items-center gap-2">
      <el-button size="small" :icon="Plus" :disabled="fields.length === 0" @click="addRow">添加条件</el-button>
      <span class="text-xs text-[#c0c4cc]">{{ hint }}</span>
    </div>
  </div>
</template>

<script setup lang="ts">
/**
 * 动态条件表单（类型感知），导出向导第 2 步与数据浏览页共用。
 *
 * 规则来源 `types/condition.ts`（后端口径的镜像）：
 * - 可选操作符随字段类型变（数值/日期有区间，枚举/引用有 IN，字符串有 EMPTY/NOT_EMPTY）；
 * - `BETWEEN` 是前端草案，提交时展开为 GTE + LTE 两条（后端无 BETWEEN）；
 * - 取值为空即视为"该字段未填 → 不提交"（EMPTY/NOT_EMPTY 例外，本就不带值）。
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

const props = withDefaults(
  defineProps<{
    fields: readonly ConfigField[]
    modelValue: ConditionDraftModel
    hint?: string
  }>(),
  {
    hint: '不设置条件表示导出全部数据'
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
