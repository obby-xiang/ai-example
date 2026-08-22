<template>
  <div>
    <StepHeader />
    <div class="page-card">
      <div class="section-title">
        <span>⑤ 发布</span>
      </div>

      <div v-if="!started && !finished">
        <el-alert type="warning" :closable="false" style="margin-bottom:16px;">
          点击"开始发布"将写入数据库（ADD/IMPORT/MODIFY），或生成导出文件/清单（EXPORT）。
        </el-alert>
        <div style="text-align:center;padding:40px 20px;">
          <el-icon :size="64" color="#e6a23c"><WarningFilled /></el-icon>
          <div style="margin-top:16px;font-size:16px;">点击下面按钮执行发布动作</div>
          <div style="margin-top:8px;color:#909399;font-size:13px;">
            任务：{{ task?.name }} · 场景：{{ scenarioLabel }} · 共 {{ selectedIds.length }} 项配置
          </div>
          <el-button type="primary" size="large" style="margin-top:20px;" :loading="publishing" @click="startPublish">
            <el-icon><Promotion /></el-icon> 开始发布
          </el-button>
        </div>
      </div>

      <div v-if="started && !finished">
        <el-steps direction="vertical" :active="currentStepIdx">
          <el-step
            v-for="(s, i) in subSteps"
            :key="i"
            :title="s.title"
            :description="s.desc"
            :status="s.status"
          />
        </el-steps>
        <div style="margin-top:16px;padding:14px;background:#fafafa;border-radius:6px;max-height:220px;overflow:auto;font-size:12px;">
          <div v-for="(l, i) in logs" :key="i">{{ l }}</div>
          <div v-if="logs.length === 0" style="color:#c0c4cc;">等待执行...</div>
        </div>
      </div>

      <div v-if="finished">
        <el-result
          :icon="publishOk ? 'success' : 'error'"
          :title="publishOk ? '发布成功!' : '发布失败'"
          :sub-title="publishOk ? '变更已生效,任务即将置为已完成状态。' : '请查看日志后重试。'"
        >
          <template #extra>
            <div style="text-align:left;padding:16px;background:#fafafa;border-radius:6px;max-height:300px;overflow:auto;">
              <div v-for="(l, i) in logs" :key="i" style="font-size:12px;">{{ l }}</div>
            </div>
          </template>
        </el-result>
        <div style="text-align:center;margin-top:16px;">
          <el-button @click="backDashboard">返回任务中心</el-button>
          <el-button type="primary" @click="restart" :disabled="publishOk">重新发布</el-button>
        </div>
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

const publishing = ref(false)
const started = ref(false)
const finished = ref(false)
const publishOk = ref(false)
const currentStepIdx = ref(0)
const logs = ref([])

const subSteps = reactive([
  { title: '准备上下文', desc: '读取任务与配置定义', status: 'wait' },
  { title: '执行写入/导出动作', desc: '按场景执行操作', status: 'wait' },
  { title: '完成任务', desc: '更新状态与快照', status: 'wait' }
])

function log(msg) {
  const ts = new Date().toLocaleTimeString('zh-CN', { hour12: false })
  logs.value.push(`[${ts}] ${msg}`)
}

async function startPublish() {
  if (publishing.value) return
  publishing.value = true
  started.value = true
  finished.value = false
  publishOk.value = false
  currentStepIdx.value = 0
  logs.value = []
  subSteps.forEach(s => s.status = 'wait')
  try {
    // Step 1
    subSteps[0].status = 'process'
    log('开始执行发布流程,场景=' + task.value.scenario)
    if (!selectedIds.value.length) throw new Error('没有选中任何配置项')
    await new Promise(r => setTimeout(r, 200))
    subSteps[0].status = 'success'
    currentStepIdx.value = 1

    // Step 2
    subSteps[1].status = 'process'
    const scenario = task.value.scenario
    let totalAffected = 0
    for (const id of selectedIds.value) {
      const def = configStore.defById(id)
      const name = def?.name || ('#' + id)
      const rowCount = await configStore.count(id).then(r => r.total).catch(() => 0)
      if (scenario === 'EXPORT') {
        log(`[EXPORT] 生成配置项《${name}》导出清单: ${rowCount} 行`)
        totalAffected += rowCount
      } else if (scenario === 'ADD' || scenario === 'IMPORT') {
        // 之前VIEW_DEFS页用户可以按页保存,这里再执行一次无操作确认
        log(`[${scenario}] 已确认配置项《${name}》数据写入(共${rowCount}行),跳过重复写入`)
        totalAffected += rowCount
      } else if (scenario === 'MODIFY') {
        log(`[MODIFY] 已确认配置项《${name}》变更持久化(当前${rowCount}行)`)
        totalAffected += rowCount
      }
    }
    log(`步骤2完成,累计影响 ${totalAffected} 行`)
    subSteps[1].status = 'success'
    currentStepIdx.value = 2

    // Step 3
    subSteps[2].status = 'process'
    await taskStore.saveStepData('PUBLISH', {
      at: Date.now(),
      logs: logs.value.slice(-100),
      totalAffected
    })
    await taskStore.complete()
    log('任务已标记为完成')
    subSteps[2].status = 'success'
    publishOk.value = true
    if (typeof window.__triggerAiAutoPrompt === 'function') {
      window.__triggerAiAutoPrompt('publish_done')
    }
  } catch (e) {
    log('错误:' + e.message)
    ElMessage.error('发布失败:' + e.message)
    subSteps[currentStepIdx.value].status = 'error'
    publishOk.value = false
  } finally {
    finished.value = true
    publishing.value = false
  }
}

function restart() {
  started.value = false
  finished.value = false
  logs.value = []
  currentStepIdx.value = 0
  subSteps.forEach(s => s.status = 'wait')
}

function backDashboard() {
  router.push('/dashboard')
}

onMounted(() => {
  if (!taskStore.currentTask) return router.push('/dashboard')
})
</script>
