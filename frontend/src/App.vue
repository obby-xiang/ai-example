<template>
  <div class="app-shell">
    <header class="app-header">
      <div class="flex items-center gap-3">
        <el-icon :size="22" class="text-blue-600"><Setting /></el-icon>
        <span class="text-base font-semibold">配置快速实施平台</span>
        <el-tag size="small" type="info" effect="plain">动态配置 · 导出/导入 · AI 辅助</el-tag>
      </div>
      <div class="flex items-center gap-2">
        <el-tooltip :content="connected ? '事件通道已连接' : '事件通道未连接'" placement="bottom">
          <span class="inline-block w-2.5 h-2.5 rounded-full"
            :class="connected ? 'bg-green-500' : 'bg-gray-300'"></span>
        </el-tooltip>
        <el-button text size="small" @click="$router.push('/tasks')">
          <el-icon class="mr-1"><List /></el-icon>任务中心
        </el-button>
        <el-button text size="small" @click="$router.push('/')">
          <el-icon class="mr-1"><HomeFilled /></el-icon>首页
        </el-button>
        <el-button text size="small" type="primary" @click="aiPanelVisible = !aiPanelVisible">
          <el-icon class="mr-1"><ChatDotRound /></el-icon>{{ aiPanelVisible ? '收起AI' : '展开AI' }}
        </el-button>
      </div>
    </header>
    <div class="app-body">
      <main class="app-main">
        <router-view />
      </main>
      <aside v-show="aiPanelVisible" class="app-aside">
        <AiPanel />
      </aside>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElNotification } from 'element-plus'
import AiPanel from '@/components/AiPanel.vue'
import { useSessionStore } from '@/stores/session'
import { useTaskStore } from '@/stores/task'
import { useAiStore } from '@/stores/ai'

const router = useRouter()
const sessionStore = useSessionStore()
const taskStore = useTaskStore()
const aiStore = useAiStore()
const aiPanelVisible = ref(true)
const connected = computed(() => sessionStore.connected)

onMounted(async () => {
  sessionStore.ensureSession()
  await aiStore.restore()

  // 刷新恢复：会话绑定的任务自动回到向导（导航主权归前端）
  try {
    const state = await import('@/api').then(({ api }) => api.sessionState())
    if (state?.activeTaskId && router.currentRoute.value.name !== 'task') {
      router.push(`/task/${state.activeTaskId}`)
    }
  } catch { /* ignore */ }

  sessionStore.connectSSE({
    state: (data) => {
      // AI 或其他通路产生的任务变更 → 同步工作区
      if (data?.task) {
        taskStore.applyTaskMap(data.task)
        if (data.cause === 'CREATED' && data.task.id) {
          if (router.currentRoute.value.name !== 'task') {
            router.push(`/task/${data.task.id}`)
            ElMessage.success('AI 已创建任务，已为您打开向导')
          } else if (data.task.id !== taskStore.task?.id) {
            // 正在其他任务向导中：不打断，提供跳转入口（导航主权归用户）
            ElNotification({
              title: 'AI 已创建新任务',
              message: `「${data.task.title}」已创建，点击进入向导`,
              type: 'success',
              duration: 8000,
              onClick: () => router.push(`/task/${data.task.id}`)
            })
          }
        }
      }
    },
    progress: (data) => {
      taskStore.applyProgress(data)
    },
    activity: (data) => {
      if (data?.text) {
        aiStore.pushActivity(data.text)
      }
    },
    notice: (data) => {
      if (data?.level === 'error') {
        ElNotification.error({ title: '错误', message: data.text, duration: 5000 })
      } else if (data?.text) {
        ElMessage.warning(data.text)
      }
    },
    ping: () => { /* 心跳 */ }
  })
})
</script>

<style>
.app-shell {
  display: flex;
  flex-direction: column;
  height: 100vh;
  overflow: hidden;
}
.app-header {
  height: 48px;
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 16px;
  background: #fff;
  border-bottom: 1px solid #e4e7ed;
}
.app-body {
  flex: 1;
  display: flex;
  min-height: 0;
}
.app-main {
  flex: 1;
  min-width: 0;
  overflow: auto;
  padding: 16px;
}
.app-aside {
  width: 400px;
  flex-shrink: 0;
  border-left: 1px solid #e4e7ed;
  background: #fff;
  overflow: hidden;
}
</style>
