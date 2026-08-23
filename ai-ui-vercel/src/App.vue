<template>
  <div class="app-container">
    <div class="app-header">
      <div class="title">
        <component :is="'MagicStick'" :size="24" />
        <span>AI辅助配置管理系统</span>
        <el-tag v-if="taskStore.currentTask" size="small" effect="dark" type="success" style="margin-left:12px;">
          {{ taskStore.currentTask.name }}
          <span v-if="taskStore.scenarioName" style="opacity:.85">· {{ taskStore.scenarioName }}</span>
        </el-tag>
      </div>
      <div class="meta">
        <template v-if="taskStore.currentTask">
          <span>当前步骤:<b>{{ currentStepLabel }}</b></span>
          <el-button size="small" type="info" plain @click="goDashboard">返回任务中心</el-button>
        </template>
        <el-tag type="warning" size="small">前端 AI Runtime · Vercel SDK</el-tag>
      </div>
    </div>

    <div class="app-body">
      <div class="work-area">
        <router-view v-slot="{ Component, route }">
          <transition name="fade" mode="out-in">
            <component :is="Component" :key="route.fullPath" />
          </transition>
        </router-view>
      </div>

      <!-- AI对话栏:常驻在 App 根,独立于 router-view,跨路由不会销毁 -->
      <div class="ai-panel" :class="{ collapsed: !aiStore.expanded }">
        <AiPanel />
      </div>
    </div>
  </div>
</template>

<script setup>
import { onMounted, computed, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useTaskStore } from '@/stores/task'
import { useConfigStore } from '@/stores/config'
import { useAiStore } from '@/stores/ai'
import { stepToRoute } from '@/router'
import AiPanel from '@/components/AiPanel.vue'
import { ElMessage } from 'element-plus'

const taskStore = useTaskStore()
const configStore = useConfigStore()
const aiStore = useAiStore()
const router = useRouter()

const STEP_LABEL = {
  SELECT_SCENARIO: '选择场景', SELECT_DEFS: '选择配置项', VIEW_DEFS: '查看配置项',
  QUERY_COND: '配置查询', PRECHECK: '预检查', REVIEW: '复核确认',
  PUBLISH: '发布', RESULT: '查看结果'
}
const currentStepLabel = computed(() => STEP_LABEL[taskStore.currentStep] || '未开始')

function goDashboard() {
  router.push('/dashboard')
}

// 当任务的 currentStep 变化时,自动跳转对应路由(由 AI 或向导触发)
watch(
  () => taskStore.currentTask?.currentStep,
  (newStep) => {
    if (!newStep || !taskStore.currentTask) return
    const target = stepToRoute(newStep)
    if (target === '/dashboard') return
    if (router.currentRoute.value.path !== target) {
      router.push(target)
    }
  }
)

onMounted(async () => {
  try {
    await Promise.all([taskStore.loadMeta(), configStore.loadDefinitions()])
    await taskStore.loadTasks()
    if (taskStore.currentTaskId) {
      await taskStore.selectTask(taskStore.currentTaskId).catch(() => {
        taskStore.currentTaskId = null
        taskStore.currentTask = null
      })
    }
    // 如果当前有任务,加载AI历史
    if (taskStore.currentTaskId) {
      aiStore.loadHistory(taskStore.currentTaskId)
      // 如果还没消息,触发一次自动提示
      if (aiStore.messages.length === 0) {
        await triggerAutoPrompt('init')
      }
      // 根据任务步骤自动导航到对应路由
      const path = stepToRoute(taskStore.currentStep)
      if (path !== '/dashboard' && router.currentRoute.value.path !== path) {
        router.replace(path)
      }
    } else if (router.currentRoute.value.path !== '/dashboard') {
      router.replace('/dashboard')
    }
  } catch (e) {
    ElMessage.error('初始化失败:' + e.message)
  }
})

// 组装工作区状态快照,发送给AI
function collectWorkspaceState(event) {
  const t = taskStore.currentTask
  return {
    event,
    route: router.currentRoute.value.path,
    task: t ? {
      id: t.id,
      name: t.name,
      scenario: t.scenario,
      scenarioName: taskStore.scenarioName,
      currentStep: t.currentStep,
      currentStepLabel: STEP_LABEL[t.currentStep],
      status: t.status,
      selectedDefIds: taskStore.selectedDefIds,
      selectedDefs: taskStore.selectedDefIds.map(id => {
        const d = configStore.defById(id)
        return d ? { id: d.id, code: d.code, name: d.name, fieldCount: d.columns?.length || 0 } : null
      }).filter(Boolean),
      steps: taskStore.steps
    } : null,
    definitions: configStore.enabledDefinitions.map(d => ({ id: d.id, code: d.code, name: d.name }))
  }
}

async function triggerAutoPrompt(event) {
  if (!taskStore.currentTaskId && event !== 'init') return
  aiStore.setLastEvent(event)
  await aiStore.chat('', collectWorkspaceState(event), {
    autoPrompt: true,
    triggerEvent: event
  })
}

// 暴露给子组件/工具:全局触发AI自动提示 + 收集工作区状态
window.__triggerAiAutoPrompt = triggerAutoPrompt
window.__collectWorkspaceState = collectWorkspaceState
</script>

<style scoped>
.fade-enter-active, .fade-leave-active {
  transition: opacity 0.2s ease;
}
.fade-enter-from, .fade-leave-to {
  opacity: 0;
}
</style>
