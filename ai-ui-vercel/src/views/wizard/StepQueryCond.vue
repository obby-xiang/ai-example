<template>
  <div>
    <StepHeader />
    <div class="page-card">
      <div class="section-title">
        <span>③ 配置查询（导出场景）</span>
      </div>
      <div style="margin-bottom:16px;">
        本次将导出下列配置项（仅计数并展示导出清单，不查全量明细）：
      </div>
      <div class="stat-box">
        <div class="stat-item" v-for="(info, i) in itemInfo" :key="i">
          <div class="label">{{ info.name }} <span style="opacity:.6;">({{ info.code }})</span></div>
          <div class="value" style="font-size:20px;" :class="{'is-loading': info.loading}">
            <template v-if="info.loading"><el-icon class="is-loading"><Loading /></el-icon></template>
            <template v-else>{{ info.total == null ? '—' : info.total }}</template>
          </div>
        </div>
      </div>

      <el-form label-width="90px" style="max-width:700px;">
        <el-form-item label="导出模式">
          <el-radio-group v-model="cond.mode">
            <el-radio value="all">导出全部行</el-radio>
            <el-radio value="count_limit">按数量上限导出（每配置项最多N行）</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="数量上限" v-if="cond.mode === 'count_limit'">
          <el-input-number v-model="cond.limit" :min="1" :max="100000" />
          <span style="margin-left:8px;color:#909399;font-size:12px;">(每个配置项最多导出行数)</span>
        </el-form-item>
        <el-form-item label="说明">
          <el-input v-model="cond.note" type="textarea" :rows="2" placeholder="可选,给本次导出写个备注..." />
        </el-form-item>
      </el-form>

      <div style="margin-top:24px;display:flex;gap:12px;justify-content:flex-end;">
        <el-button @click="prev">← 返回选择配置项</el-button>
        <el-button type="primary" :loading="saving" :disabled="!itemInfo.length" @click="next">
          下一步:查看结果 →
        </el-button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useTaskStore } from '@/stores/task'
import { useConfigStore } from '@/stores/config'
import StepHeader from '@/components/StepHeader.vue'
import { ElMessage } from 'element-plus'

const taskStore = useTaskStore()
const configStore = useConfigStore()
const router = useRouter()

const cond = reactive({
  mode: 'all',
  limit: 1000,
  note: ''
})
const saving = ref(false)
const itemInfo = ref([])

const selectedIds = computed(() => taskStore.selectedDefIds)

async function recount() {
  itemInfo.value = selectedIds.value.map(id => {
    const d = configStore.defById(id)
    return { defId: id, name: d?.name || '', code: d?.code || '', total: null, loading: true }
  })
  for (const info of itemInfo.value) {
    try {
      const r = await configStore.count(info.defId)
      info.total = r.total
    } catch (e) {
      info.total = -1
    } finally {
      info.loading = false
    }
  }
}

function prev() {
  router.push('/wizard/select-defs')
}

async function next() {
  saving.value = true
  try {
    const stepData = {
      cond: { ...cond },
      items: itemInfo.value.map(x => ({ defId: x.defId, code: x.code, name: x.name, total: x.total })),
      type: 'export',
      at: Date.now()
    }
    await taskStore.saveStepData('QUERY_COND', { ...cond })
    await taskStore.saveStepData('RESULT', stepData)
    await taskStore.gotoStep('RESULT')
    router.push('/wizard/result')
    if (typeof window.__triggerAiAutoPrompt === 'function') {
      window.__triggerAiAutoPrompt('enter_step')
    }
    ElMessage.success('已生成导出清单')
  } finally {
    saving.value = false
  }
}

watch(selectedIds, () => recount(), { immediate: false })

onMounted(async () => {
  if (!taskStore.currentTask) return router.push('/dashboard')
  if (!configStore.definitions.length) await configStore.loadDefinitions()
  if (!selectedIds.value.length) {
    ElMessage.warning('尚未选择配置项,请先返回选择')
    router.push('/wizard/select-defs')
    return
  }
  // 恢复上一轮条件
  const prevStep = taskStore.currentTask?.stepData?.QUERY_COND
  if (prevStep && typeof prevStep === 'object') {
    Object.assign(cond, prevStep)
  }
  await recount()
})
</script>
