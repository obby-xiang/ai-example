<template>
  <div>
    <StepHeader />
    <div class="page-card">
      <div class="section-title">
        <span>④ 复核确认</span>
      </div>

      <el-alert type="warning" :closable="false" style="margin-bottom:20px;">
        <template #title>
          <b>发布前确认</b>
        </template>
        请确认以下信息无误。确认后将把变更写入数据库,写入过程不可撤销。
      </el-alert>

      <el-descriptions :column="2" border style="margin-bottom:20px;">
        <el-descriptions-item label="任务名称">{{ task?.name }}</el-descriptions-item>
        <el-descriptions-item label="任务编号">#{{ task?.id }}</el-descriptions-item>
        <el-descriptions-item label="场景">{{ scenarioLabel }}</el-descriptions-item>
        <el-descriptions-item label="创建时间">{{ task?.createdAt }}</el-descriptions-item>
        <el-descriptions-item label="涉及配置项数" :span="2">{{ selectedIds.length }} 项</el-descriptions-item>
      </el-descriptions>

      <h4 style="margin:10px 0 14px;font-size:14px;">配置项清单（含行数统计）</h4>
      <el-table :data="reviewItems" border stripe>
        <el-table-column type="index" label="#" width="50" align="center" />
        <el-table-column prop="name" label="配置项名称" min-width="160">
          <template #default="{row}">
            <b>{{ row.name }}</b> <span style="color:#909399;">({{ row.code }})</span>
          </template>
        </el-table-column>
        <el-table-column label="当前数据库行数" width="140" align="center" prop="beforeCount" />
        <el-table-column v-if="taskStore.scenario==='MODIFY'" label="新增" width="90" align="center">
          <template #default="{row}">
            <span style="color:#67c23a;">+{{ row.added || 0 }}</span>
          </template>
        </el-table-column>
        <el-table-column v-if="taskStore.scenario==='MODIFY'" label="修改" width="90" align="center">
          <template #default="{row}">
            <span style="color:#e6a23c;">~{{ row.modified || 0 }}</span>
          </template>
        </el-table-column>
        <el-table-column v-if="taskStore.scenario==='MODIFY'" label="删除" width="90" align="center">
          <template #default="{row}">
            <span style="color:#f56c6c;">-{{ row.deleted || 0 }}</span>
          </template>
        </el-table-column>
        <el-table-column label="复核意见" min-width="140">
          <template #default="{row}">
            <el-radio-group v-model="row.decision" size="small">
              <el-radio-button value="agree">同意</el-radio-button>
              <el-radio-button value="pending">待定</el-radio-button>
              <el-radio-button value="reject">退回</el-radio-button>
            </el-radio-group>
          </template>
        </el-table-column>
      </el-table>

      <h4 style="margin:20px 0 10px;font-size:14px;">复核备注（可选）</h4>
      <el-input
        v-model="reviewNote"
        type="textarea"
        :rows="3"
        placeholder="请输入复核意见，例如：已与业务确认，本次变更合规。"
      />

      <div style="margin-top:24px;display:flex;gap:12px;justify-content:flex-end;">
        <el-button @click="prev">← 返回预检查</el-button>
        <el-button type="primary" :loading="saving" :disabled="!canSubmit" @click="next">
          提交发布 →
        </el-button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useTaskStore } from '@/stores/task'
import { useConfigStore } from '@/stores/config'
import StepHeader from '@/components/StepHeader.vue'
import { ElMessage } from 'element-plus'

const taskStore = useTaskStore()
const configStore = useConfigStore()
const router = useRouter()

const task = computed(() => taskStore.currentTask)
const selectedIds = computed(() => taskStore.selectedDefIds)
const scenarioLabel = computed(() => taskStore.scenarioName)

const reviewItems = ref([])
const reviewNote = ref('')
const saving = ref(false)

const canSubmit = computed(() => {
  if (!reviewItems.value.length) return false
  return reviewItems.value.every(x => x.decision === 'agree')
})

async function loadReviewItems() {
  reviewItems.value = []
  // 若有PRECHECK或VIEW_DEFS缓存的变更就用,否则仅加载行数
  const precheckItems = task.value?.stepData?.PRECHECK?.items || []
  for (const id of selectedIds.value) {
    const def = configStore.defById(id)
    const cnt = await configStore.count(id).catch(() => ({ total: 0 }))
    const preInfo = precheckItems.find(x => x.defId === id) || {}
    reviewItems.value.push({
      defId: id,
      name: def?.name || '',
      code: def?.code || '',
      beforeCount: cnt.total,
      added: 0, modified: 0, deleted: 0,
      decision: 'agree'
    })
  }
}

function prev() {
  router.push('/wizard/precheck')
}

async function next() {
  if (!canSubmit.value) return ElMessage.warning('请将所有配置项置为"同意"后再提交')
  saving.value = true
  try {
    // 保存复核信息
    const reviewSnapshot = {
      note: reviewNote.value,
      at: Date.now(),
      items: reviewItems.value
    }
    await taskStore.saveStepData('REVIEW', reviewSnapshot)
    await taskStore.gotoStep('PUBLISH')
    router.push('/wizard/publish')
    if (typeof window.__triggerAiAutoPrompt === 'function') {
      window.__triggerAiAutoPrompt('enter_step')
    }
    ElMessage.success('复核信息已保存')
  } finally {
    saving.value = false
  }
}

onMounted(async () => {
  if (!taskStore.currentTask) return router.push('/dashboard')
  if (!configStore.definitions.length) await configStore.loadDefinitions()
  // 恢复上次的备注
  reviewNote.value = task.value?.stepData?.REVIEW?.note || ''
  await loadReviewItems()
})
</script>
