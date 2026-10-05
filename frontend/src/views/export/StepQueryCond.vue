<template>
  <div>
    <div class="step-desc">为每个选中的配置项设置查询条件，不设条件则导出全部数据</div>

    <el-tabs v-model="activeTab" type="card">
      <el-tab-pane v-for="item in task?.items || []" :key="item.defCode" :label="item.defCode" :name="item.defCode">
        <div class="cond-panel">
          <div class="cond-hint">
            <el-icon><InfoFilled /></el-icon>
            留空表示不过滤该条件
          </div>

          <!-- Scope filter for REGION/PROJECT levels -->
          <div class="cond-row" v-if="defMap[item.defCode]?.level === 'REGION'">
            <span class="cond-label">地区范围</span>
            <el-select v-model="conditions[item.defCode].scopeKeys" multiple clearable placeholder="不限地区（全部）" style="width:300px">
              <el-option v-for="r in regions" :key="r.code" :label="r.name" :value="r.code" />
            </el-select>
          </div>
          <div class="cond-row" v-else-if="defMap[item.defCode]?.level === 'PROJECT'">
            <span class="cond-label">项目范围</span>
            <el-select v-model="conditions[item.defCode].scopeKeys" multiple clearable placeholder="不限项目（全部）" style="width:300px">
              <el-option v-for="p in projects" :key="p.code" :label="p.name" :value="p.code" />
            </el-select>
          </div>
        </div>
      </el-tab-pane>
    </el-tabs>

    <div class="wizard-footer">
      <el-button @click="$emit('back')">
        <el-icon class="el-icon--left"><ArrowLeft /></el-icon> 上一步
      </el-button>
      <el-button type="primary" @click="handleNext" :loading="saving">
        下一步：执行导出
        <el-icon class="el-icon--right"><ArrowRight /></el-icon>
      </el-button>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted, computed } from 'vue'
import { listDefinitions } from '@/api/definitions.js'
import { getRegions, getProjects } from '@/api/masterdata.js'
import { useTaskStore } from '@/stores/task.js'
import { InfoFilled, ArrowLeft, ArrowRight } from '@element-plus/icons-vue'

const props = defineProps({ task: Object })
const emit = defineEmits(['next', 'back'])

const taskStore = useTaskStore()
const defs = ref([])
const regions = ref([])
const projects = ref([])
const activeTab = ref('')
const saving = ref(false)

const defMap = computed(() => Object.fromEntries(defs.value.map(d => [d.code, d])))

// One condition object per def
const conditions = reactive({})

onMounted(async () => {
  const [defRes, regionRes, projectRes] = await Promise.all([
    listDefinitions(), getRegions(), getProjects()
  ])
  defs.value = defRes.data.data || []
  regions.value = regionRes.data.data || []
  projects.value = projectRes.data.data || []

  if (props.task?.items?.length > 0) {
    activeTab.value = props.task.items[0].defCode
    for (const item of props.task.items) {
      conditions[item.defCode] = reactive({
        scopeKeys: []
      })
      // Parse existing condition
      if (item.conditionJson) {
        try {
          const parsed = JSON.parse(item.conditionJson)
          if (parsed.scopeKeys) conditions[item.defCode].scopeKeys = parsed.scopeKeys
        } catch (e) {}
      }
    }
  }
})

async function handleNext() {
  saving.value = true
  try {
    for (const item of (props.task?.items || [])) {
      const cond = conditions[item.defCode]
      if (cond) {
        const condJson = JSON.stringify({ scopeKeys: cond.scopeKeys || [] })
        await taskStore.setCondition(props.task.id, item.defCode, condJson)
      }
    }
    emit('next')
  } finally {
    saving.value = false
  }
}
</script>

<style lang="scss" scoped>
.step-desc { color: var(--el-text-color-secondary); margin-bottom: 16px; }
.cond-panel { padding: 16px 0; }
.cond-hint { display: flex; align-items: center; gap: 6px; color: var(--el-text-color-secondary); font-size: 12px; margin-bottom: 16px; }
.cond-row { display: flex; align-items: center; gap: 12px; margin-bottom: 12px; }
.cond-label { width: 80px; font-size: 13px; font-weight: 500; }
</style>
