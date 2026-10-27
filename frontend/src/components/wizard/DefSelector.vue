<template>
  <div>
    <div class="flex gap-3 mb-2.5">
      <el-radio-group v-model="levelFilter" size="small" :disabled="props.disabled">
        <el-radio-button
          v-for="level in LEVEL_FILTERS"
          :key="level.value"
          :value="level.value"
          v-bind="aiAttrs(`level.${level.value || 'all'}`, {
            role: 'radio',
            'aria-checked': levelFilter === level.value ? 'true' : 'false',
            'data-ai-risk': 'mutate-local',
            'data-ai-verbs': 'click,highlight',
            'data-ai-label': level.label
          })"
        >{{ level.label }}</el-radio-button>
      </el-radio-group>
      <el-input
        v-model="keyword"
        size="small"
        :placeholder="t('wizard.defs.searchPlaceholder')"
        clearable
        class="w-[220px]"
        :disabled="props.disabled"
        v-bind="aiAttrs('search', {
          'data-ai-fill': 'allow',
          'data-ai-risk': 'mutate-local',
          'data-ai-verbs': 'fill,highlight,scroll',
          'data-ai-label': '搜索配置项'
        })"
      />
    </div>
    <el-table
      ref="tableRef"
      v-loading="loading"
      :data="filteredDefs"
      size="small"
      border
      :height="height"
      @selection-change="onSelectionChange"
    >
      <el-table-column type="selection" width="48" :selectable="() => !props.disabled">
        <template #default="{ row }">
          <el-checkbox
            :model-value="isSelected(row)"
            :disabled="props.disabled"
            :aria-label="t('wizard.defs.selectAria', { name: row.name, code: row.code })"
            v-bind="aiAttrs(row.code, {
              role: 'checkbox',
              'aria-checked': isSelected(row) ? 'true' : 'false',
              'data-ai-risk': 'mutate-local',
              'data-ai-undo': 'retoggle',
              'data-ai-verbs': 'click,highlight',
              'data-ai-label': `${row.name}（${row.code}）`
            })"
            @change="toggleRow(row)"
          />
        </template>
      </el-table-column>
      <el-table-column prop="code" :label="t('wizard.defs.code')" width="160" />
      <el-table-column prop="name" :label="t('wizard.defs.name')" min-width="140" />
      <el-table-column :label="t('wizard.defs.level')" width="90">
        <template #default="{ row }">
          <el-tag size="small" :type="levelType(row.level)">{{ levelLabel(row.level) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column :label="t('wizard.defs.fieldCount')" width="90">
        <template #default="{ row }">{{ row.fields?.length ?? 0 }}</template>
      </el-table-column>
      <el-table-column :label="t('wizard.defs.dependencies')" min-width="140">
        <template #default="{ row }">
          <span v-if="dependenciesOf(row).length">{{ dependenciesOf(row).join(', ') }}</span>
          <span v-else class="text-[#c0c4cc]">{{ t('wizard.defs.none') }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="description" :label="t('wizard.defs.description')" min-width="160" show-overflow-tooltip />
      <template #empty>
        <el-empty :description="t('wizard.defs.empty')" :image-size="60" />
      </template>
    </el-table>
  </div>
</template>

<script setup lang="ts">
import { useI18n } from 'vue-i18n'
/**
 * 配置项选择器（导出/导入向导第 1 步共用）。
 *
 * 依赖列的来源：`ConfigDefinition.fields` 里 fieldType=REFERENCE 的 refDefCode
 * （后端依赖顺序由 DependencyResolver 按同一份字段定义推导，前端只做展示）。
 * 勾选语义：筛选隐藏的行**保留**已选状态（导出/导入的选中集不因筛选而丢）。
 *
 * AI 标注（visop 批 B）：传入 `aiRefPrefix`（区域 id）时，搜索框 / 层级单选 / 行勾选框
 * 带 `data-ai-*`；行勾选框由本组件渲染（替换 el-table 内置的 selection 单元格），
 * 选中集仍以表格内部状态为权威。
 */
import { computed, nextTick, ref, watch } from 'vue'
import type { TableInstance } from 'element-plus'
import type { ConfigDefinition, ConfigLevel } from '@/types/definition'

const { t } = useI18n()

const props = withDefaults(
  defineProps<{
    defs: readonly ConfigDefinition[]
    /** 已选配置项编码（v-model） */
    modelValue: readonly string[]
    loading?: boolean
    height?: string | number
    /** 只读态：禁止改选与筛选（只读查看用） */
    disabled?: boolean
    /**
     * AI 标注前缀（形如 `import.selectDefs`）：给出时本组件的搜索框 / 层级单选 /
     * 行勾选框才带 `data-ai-*`，缺省不标注（数据浏览等未扩面用途零变化）。
     */
    aiRefPrefix?: string
  }>(),
  {
    loading: false,
    height: 380,
    disabled: false,
    aiRefPrefix: ''
  }
)

const emit = defineEmits<{ (event: 'update:modelValue', value: string[]): void }>()

const LEVEL_FILTERS: ReadonlyArray<{ value: '' | ConfigLevel; label: string }> = [
  { value: '', label: '全部层级' },
  { value: 'GLOBAL', label: '全局' },
  { value: 'REGION', label: '地区' },
  { value: 'PROJECT', label: '项目' }
]

/**
 * 标注属性（设计稿 §3.4.2）：`data-ai-ref` 是唯一「可被看见」的钥匙，
 * 因此未给前缀时**一个属性都不挂**（不留半套标注）。
 */
function aiAttrs(suffix: string, extra: Record<string, string> = {}): Record<string, string> {
  if (!props.aiRefPrefix) {
    return {}
  }
  // 只读态：el-checkbox / el-radio-button 的 root 是 label，原生 disabled 落在内层 input 上，
  // 快照的 state 推导读不到 ⇒ 补 aria-disabled（否则禁用控件会被显示成 enabled）。
  const attrs: Record<string, string> = props.disabled ? { 'aria-disabled': 'true', ...extra } : { ...extra }
  attrs['data-ai-ref'] = `${props.aiRefPrefix}.${suffix}`
  return attrs
}

const levelFilter = ref<'' | ConfigLevel>('')
const keyword = ref('')
const tableRef = ref<TableInstance | null>(null)
let syncing = false

const selectedSet = computed(() => new Set(props.modelValue))

function isSelected(row: ConfigDefinition): boolean {
  return selectedSet.value.has(row.code)
}

/**
 * 行勾选框的点击入口。
 *
 * 为什么走 `toggleRowSelection` 而不是直接 emit：表格内部的选中集是「全选表头 / 区间选择」
 * 的权威，绕开它只改 v-model 会让表头状态与行状态分叉。
 */
function toggleRow(row: ConfigDefinition): void {
  tableRef.value?.toggleRowSelection(row)
}

const filteredDefs = computed(() => {
  const kw = keyword.value.trim().toLowerCase()
  return props.defs.filter((item) => {
    if (levelFilter.value && item.level !== levelFilter.value) {
      return false
    }
    if (kw && !item.code.toLowerCase().includes(kw) && !item.name.toLowerCase().includes(kw)) {
      return false
    }
    return true
  })
})

/** 依赖：REFERENCE 字段引用的配置编码（去重、排除自身） */
function dependenciesOf(def: ConfigDefinition): string[] {
  const codes = new Set<string>()
  for (const field of def.fields ?? []) {
    if (field.fieldType === 'REFERENCE' && field.refDefCode && field.refDefCode !== def.code) {
      codes.add(field.refDefCode)
    }
  }
  return [...codes]
}

/**
 * 表格选中态同步的序号（last-wins）：watch 是异步的（`await nextTick()`），
 * 选中集/定义表连续变化时旧一轮的回写会晚于新一轮 —— 没有序号时"后跑完的那轮"说了算，
 * 结果就是勾选状态回退到上一份 modelValue。只让最新一轮落地。
 */
let syncSeq = 0

watch(
  () => [props.modelValue, props.defs],
  async () => {
    if (!tableRef.value) {
      return
    }
    const seq = ++syncSeq
    syncing = true
    await nextTick()
    if (seq !== syncSeq) {
      // 更新的同步已接管：本次不得再改表格选中态，也不得替它清 syncing
      return
    }
    const selected = new Set(props.modelValue)
    for (const def of props.defs) {
      tableRef.value.toggleRowSelection(def, selected.has(def.code))
    }
    syncing = false
  },
  { immediate: true, deep: true }
)

function onSelectionChange(rows: ConfigDefinition[]): void {
  if (syncing) {
    return
  }
  const visible = new Set(filteredDefs.value.map((item) => item.code))
  const kept = props.modelValue.filter((code) => !visible.has(code))
  emit('update:modelValue', [...kept, ...rows.map((row) => row.code)])
}

function levelType(level: ConfigLevel): 'success' | 'warning' | 'primary' | 'info' {
  return level === 'GLOBAL' ? 'success' : level === 'REGION' ? 'warning' : 'primary'
}

function levelLabel(level: ConfigLevel): string {
  return level === 'GLOBAL' ? '全局' : level === 'REGION' ? '地区' : '项目'
}
</script>
