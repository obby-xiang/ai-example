<template>
  <div>
    <div class="step-desc">选择要导出的配置项（可多选）</div>

    <el-row :gutter="16" style="margin-bottom:12px">
      <el-col :span="8">
        <el-select v-model="levelFilter" clearable placeholder="按层级筛选" style="width:100%">
          <el-option label="全局" value="GLOBAL" />
          <el-option label="地区级" value="REGION" />
          <el-option label="项目级" value="PROJECT" />
        </el-select>
      </el-col>
      <el-col :span="8">
        <el-input v-model="keyword" clearable placeholder="搜索配置编码或名称" :prefix-icon="Search" />
      </el-col>
      <el-col :span="8">
        <span class="selected-hint">已选 {{ selectedCodes.length }} 个配置项</span>
      </el-col>
    </el-row>

    <el-table
      ref="tableRef"
      :data="filteredDefs"
      v-loading="loading"
      @selection-change="handleSelectionChange"
      border
      max-height="450"
    >
      <el-table-column type="selection" width="50" />
      <el-table-column prop="code" label="编码" width="150" />
      <el-table-column prop="name" label="名称" width="120" />
      <el-table-column label="层级" width="90">
        <template #default="{ row }">
          <el-tag :type="levelType(row.level)" size="small">{{ levelLabel(row.level) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="字段数" width="80">
        <template #default="{ row }">{{ row.fields?.length || 0 }}</template>
      </el-table-column>
      <el-table-column prop="description" label="说明" show-overflow-tooltip />
    </el-table>

    <div class="wizard-footer">
      <el-button type="primary" :disabled="selectedCodes.length === 0" @click="handleNext">
        下一步：设置查询条件
        <el-icon class="el-icon--right"><ArrowRight /></el-icon>
      </el-button>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, watch } from 'vue'
import { listDefinitions } from '@/api/definitions.js'
import { useTaskStore } from '@/stores/task.js'
import { Search, ArrowRight } from '@element-plus/icons-vue'

const props = defineProps({ task: Object })
const emit = defineEmits(['next'])

const taskStore = useTaskStore()
const defs = ref([])
const loading = ref(false)
const levelFilter = ref('')
const keyword = ref('')
const selectedCodes = ref([])
const tableRef = ref(null)

const filteredDefs = computed(() => {
  return defs.value.filter(d => {
    if (levelFilter.value && d.level !== levelFilter.value) return false
    if (keyword.value && !d.code.includes(keyword.value) && !d.name.includes(keyword.value)) return false
    return true
  })
})

onMounted(async () => {
  loading.value = true
  try {
    const res = await listDefinitions()
    defs.value = res.data.data || []
    syncSelectionFromTask()
  } finally {
    loading.value = false
  }
})

// 外部变更（AI 或另一页签操作）后同步表格勾选状态
watch(() => (props.task?.items || []).map(i => i.defCode).join(','), () => {
  syncSelectionFromTask()
})

function syncSelectionFromTask() {
  const codes = (props.task?.items || []).map(i => i.defCode)
  selectedCodes.value = [...codes]
  const table = tableRef.value
  if (table && defs.value.length) {
    table.clearSelection()
    defs.value.forEach(row => {
      if (codes.includes(row.code)) table.toggleRowSelection(row, true)
    })
  }
}

function handleSelectionChange(rows) {
  selectedCodes.value = rows.map(r => r.code)
}

async function handleNext() {
  if (selectedCodes.value.length === 0) return
  await taskStore.selectDefs(props.task.id, selectedCodes.value)
  emit('next')
}

const levelType = (l) => ({ GLOBAL: 'success', REGION: 'warning', PROJECT: 'primary' })[l] || ''
const levelLabel = (l) => ({ GLOBAL: '全局', REGION: '地区', PROJECT: '项目' })[l] || l
</script>

<style lang="scss" scoped>
.step-desc { color: var(--el-text-color-secondary); margin-bottom: 16px; }
.selected-hint { font-size: 14px; color: var(--el-color-primary); line-height: 32px; }
</style>
