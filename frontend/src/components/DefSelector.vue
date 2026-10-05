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
      :data="filteredDefs"
      size="small"
      border
      height="380"
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
      <el-table-column prop="rowCount" label="数据行数" width="90" />
      <el-table-column label="依赖" min-width="140">
        <template #default="{ row }">
          <span v-if="row.dependsOn && row.dependsOn.length">{{ row.dependsOn.join(', ') }}</span>
          <span v-else class="text-[#c0c4cc]">无</span>
        </template>
      </el-table-column>
      <el-table-column prop="description" label="描述" min-width="160" show-overflow-tooltip />
    </el-table>
  </div>
</template>

<script setup>
import { ref, computed, watch, nextTick } from 'vue'

const props = defineProps({
  defs: { type: Array, default: () => [] },
  modelValue: { type: Array, default: () => [] }
})
const emit = defineEmits(['update:modelValue'])

const levelFilter = ref('')
const keyword = ref('')
const tableRef = ref(null)
let syncing = false

const filteredDefs = computed(() => {
  const kw = keyword.value.trim().toLowerCase()
  return (props.defs || []).filter((d) => {
    if (levelFilter.value && d.level !== levelFilter.value) return false
    if (kw && !String(d.code).toLowerCase().includes(kw) && !String(d.name).toLowerCase().includes(kw)) return false
    return true
  })
})

// modelValue → 表格勾选状态同步（含筛选后不可见行，需遍历全量 defs）
watch(
  () => [props.modelValue, props.defs],
  async () => {
    if (!tableRef.value) return
    syncing = true
    await nextTick()
    const selected = new Set(props.modelValue || [])
    for (const d of props.defs || []) {
      tableRef.value.toggleRowSelection(d, selected.has(d.code))
    }
    syncing = false
  },
  { immediate: true, deep: true }
)

function onSelectionChange(rows) {
  if (syncing) return
  // 保留当前筛选下不可见但已选的行
  const visibleCodes = new Set(filteredDefs.value.map((d) => d.code))
  const kept = (props.modelValue || []).filter((c) => !visibleCodes.has(c))
  emit('update:modelValue', [...kept, ...rows.map((r) => r.code)])
}

function levelType(l) {
  return { GLOBAL: 'success', REGION: 'warning', PROJECT: 'primary' }[l] || 'info'
}
function levelLabel(l) {
  return { GLOBAL: '全局', REGION: '地区', PROJECT: '项目' }[l] || l
}
</script>
