<template>
  <div>
    <div class="flex gap-3 mb-2.5">
      <el-radio-group v-model="levelFilter" size="small">
        <el-radio-button value="">全部层级</el-radio-button>
        <el-radio-button value="GLOBAL">全局</el-radio-button>
        <el-radio-button value="REGION">地区</el-radio-button>
        <el-radio-button value="PROJECT">项目</el-radio-button>
      </el-radio-group>
      <el-input v-model="keyword" size="small" placeholder="搜索编码 / 名称" clearable class="w-[220px]" />
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
      <el-table-column type="selection" width="48" />
      <el-table-column prop="code" label="编码" width="160" />
      <el-table-column prop="name" label="名称" min-width="140" />
      <el-table-column label="层级" width="90">
        <template #default="{ row }">
          <el-tag size="small" :type="levelType(row.level)">{{ levelLabel(row.level) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="字段数" width="90">
        <template #default="{ row }">{{ row.fields?.length ?? 0 }}</template>
      </el-table-column>
      <el-table-column label="依赖" min-width="140">
        <template #default="{ row }">
          <span v-if="dependenciesOf(row).length">{{ dependenciesOf(row).join(', ') }}</span>
          <span v-else class="text-[#c0c4cc]">无</span>
        </template>
      </el-table-column>
      <el-table-column prop="description" label="描述" min-width="160" show-overflow-tooltip />
      <template #empty>
        <el-empty description="暂无配置定义" :image-size="60" />
      </template>
    </el-table>
  </div>
</template>

<script setup lang="ts">
/**
 * 配置项选择器（导出/导入向导第 1 步共用）。
 *
 * 依赖列的来源：`ConfigDefinition.fields` 里 fieldType=REFERENCE 的 refDefCode
 * （后端依赖顺序由 DependencyResolver 按同一份字段定义推导，前端只做展示）。
 * 勾选语义：筛选隐藏的行**保留**已选状态（导出/导入的选中集不因筛选而丢）。
 */
import { computed, nextTick, ref, watch } from 'vue'
import type { TableInstance } from 'element-plus'
import type { ConfigDefinition, ConfigLevel } from '@/types/definition'

const props = withDefaults(
  defineProps<{
    defs: readonly ConfigDefinition[]
    /** 已选配置项编码（v-model） */
    modelValue: readonly string[]
    loading?: boolean
    height?: string | number
  }>(),
  {
    loading: false,
    height: 380
  }
)

const emit = defineEmits<{ (event: 'update:modelValue', value: string[]): void }>()

const levelFilter = ref<'' | ConfigLevel>('')
const keyword = ref('')
const tableRef = ref<TableInstance | null>(null)
let syncing = false

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

watch(
  () => [props.modelValue, props.defs],
  async () => {
    if (!tableRef.value) {
      return
    }
    syncing = true
    await nextTick()
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
