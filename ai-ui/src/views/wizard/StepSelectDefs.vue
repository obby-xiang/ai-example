<template>
  <div>
    <StepHeader />
    <div class="page-card">
      <div class="section-title">
        <span>② 选择需要导出的配置项</span>
        <div class="btn-row">
          <el-button size="small" @click="selectAll" :disabled="unselected.length === 0">全选</el-button>
          <el-button size="small" @click="selectNone" :disabled="selectedIds.length === 0">清空</el-button>
          <el-tag size="small" type="success">已选 {{ selectedIds.length }} / {{ allDefs.length }}</el-tag>
        </div>
      </div>

      <div style="margin-bottom:16px;">
        <span class="chip" v-for="id in selectedIds" :key="'c'+id">
          {{ defName(id) }} <span class="close" @click="toggleDef(id)">×</span>
        </span>
        <span v-if="selectedIds.length === 0" style="color:#c0c4cc;font-size:12px;">(尚未选择,点击下方卡片添加)</span>
      </div>

      <div class="defs-grid">
        <div
          v-for="d in allDefs"
          :key="d.id"
          class="def-card"
          :class="{selected: selectedIds.includes(d.id)}"
          @click="toggleDef(d.id)"
        >
          <div class="def-head">
            <div>
              <div class="def-name">{{ d.name }}</div>
              <div class="def-code">code: {{ d.code }} · ID:{{ d.id }}</div>
            </div>
            <el-checkbox :model-value="selectedIds.includes(d.id)" />
          </div>
          <div class="def-fields">
            <span class="field-tag" v-for="c in d.columns" :key="c.key">
              {{ c.label }}<span v-if="c.required" style="color:#f56c6c;">*</span>
            </span>
          </div>
          <div style="display:flex;align-items:center;justify-content:space-between;margin-top:4px;font-size:12px;color:#909399;">
            <span>{{ d.description || '' }}</span>
            <el-tag size="small" type="info">{{ d.columns?.length || 0 }} 字段</el-tag>
          </div>
        </div>
      </div>

      <div style="margin-top:24px;display:flex;gap:12px;justify-content:flex-end;">
        <el-button @click="prev">← 返回</el-button>
        <el-button type="primary" :disabled="selectedIds.length === 0" :loading="saving" @click="next">
          下一步:配置查询 →
        </el-button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useTaskStore } from '@/stores/task'
import { useConfigStore } from '@/stores/config'
import StepHeader from '@/components/StepHeader.vue'
import { ElMessage } from 'element-plus'

const taskStore = useTaskStore()
const configStore = useConfigStore()
const router = useRouter()

const allDefs = computed(() => configStore.enabledDefinitions)
const selectedIds = ref(taskStore.selectedDefIds.slice())
const saving = ref(false)

const unselected = computed(() => allDefs.value.filter(d => !selectedIds.value.includes(d.id)))

function defName(id) {
  const d = configStore.defById(id)
  return d ? `${d.name}(${d.code})` : '#' + id
}

function toggleDef(id) {
  const idx = selectedIds.value.indexOf(id)
  if (idx >= 0) selectedIds.value.splice(idx, 1)
  else selectedIds.value.push(id)
}
function selectAll() {
  selectedIds.value = allDefs.value.map(d => d.id)
}
function selectNone() {
  selectedIds.value = []
}

watch(selectedIds, () => {
  if (typeof window.__triggerAiAutoPrompt === 'function') {
    window.__triggerAiAutoPrompt('select_defs_change')
  }
})

async function next() {
  if (!selectedIds.value.length) return
  saving.value = true
  try {
    await taskStore.updateSelectedDefs(selectedIds.value)
    await taskStore.gotoStep('QUERY_COND')
    if (typeof window.__triggerAiAutoPrompt === 'function') {
      window.__triggerAiAutoPrompt('enter_step')
    }
    ElMessage.success('已保存配置项选择')
    router.push('/wizard/query-cond')
  } finally {
    saving.value = false
  }
}

function prev() {
  router.push('/wizard/select-scenario')
}

onMounted(async () => {
  if (!taskStore.currentTask) return router.push('/dashboard')
  if (!allDefs.value.length) await configStore.loadDefinitions()
})
</script>
