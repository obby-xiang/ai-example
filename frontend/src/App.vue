<template>
  <!-- 整体骨架（Tailwind 布局）：顶部导航固定 + 左工作区独立滚动 + 右 AI 栏固定 -->
  <div class="app-shell flex h-screen flex-col overflow-hidden">
    <header class="app-header flex h-14 shrink-0 items-center gap-6 bg-gradient-to-r from-brand-from to-brand-to px-5 text-white">
      <span class="text-[17px] font-semibold whitespace-nowrap">⚙ AI 辅助动态配置管理系统</span>
      <!-- 顶部导航：Element Plus 菜单组件（horizontal + router 模式），非自绘 -->
      <el-menu mode="horizontal" router :default-active="route.path" :ellipsis="false"
               background-color="transparent" text-color="#d7e4f2" active-text-color="#ffffff"
               class="nav-menu">
        <el-menu-item v-for="item in navItems" :key="item.path" :index="item.path">{{ item.label }}</el-menu-item>
      </el-menu>
      <div class="flex-1"></div>
      <span class="text-xs text-[#cfe0f3]">{{ backendInfo }}</span>
    </header>
    <div class="flex min-h-0 flex-1">
      <main class="workspace min-w-0 flex-1 overflow-y-auto p-4">
        <router-view />
      </main>
      <aside class="ai-side flex w-[400px] shrink-0 flex-col overflow-hidden border-l border-[#e4e7ed] bg-white">
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
import { initWorkspaceContract } from './utils/workspaceContract'

const route = useRoute()
const router = useRouter()
const defsStore = useDefsStore()
const aiStore = useAiStore()
const backendInfo = ref('')

// 契约层初始化：AI ui_event ↔ 工作区 store 的唯一映射点（解耦联动）
initWorkspaceContract()

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
  // 刷新恢复：按页签会话恢复对话历史（H2 持久化，后端重启也不丢）
  aiStore.restoreHistory()
})
</script>
