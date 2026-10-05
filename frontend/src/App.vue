<template>
  <div class="app-shell">
    <header class="app-header">
      <span class="logo">⚙ AI 辅助动态配置管理系统</span>
      <div class="nav">
        <span v-for="item in navItems" :key="item.path"
              :class="['nav-item', { active: route.path.startsWith(item.path) }]"
              @click="router.push(item.path)">{{ item.label }}</span>
      </div>
      <div style="flex: 1"></div>
      <span class="text-muted" style="color: #cfe0f3">{{ backendInfo }}</span>
    </header>
    <div class="app-body">
      <main class="workspace">
        <router-view />
      </main>
      <aside class="ai-side">
        <AiPanel />
      </aside>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import AiPanel from './components/AiPanel.vue'
import { api } from './api'
import { useDefsStore } from './stores/defs'
import { useAiStore } from './stores/ai'

const route = useRoute()
const router = useRouter()
const defsStore = useDefsStore()
const aiStore = useAiStore()
const backendInfo = ref('')

const navItems = [
  { path: '/tasks', label: '任务管理' },
  { path: '/export', label: '导出配置' },
  { path: '/import', label: '导入配置' },
  { path: '/defs', label: '配置定义' },
  { path: '/data', label: '数据浏览' }
]

onMounted(async () => {
  try {
    await defsStore.load()
    const meta = await api.get('/api/meta')
    backendInfo.value = `${meta.app} · 模型 ${meta.model} · 端口 ${meta.port}`
  } catch (e) {
    backendInfo.value = '后端未连接'
  }
  // 刷新恢复：按页签会话恢复对话历史（后端内存会话存在则恢复，否则自动降级为新会话）
  aiStore.restoreHistory()
})
</script>
