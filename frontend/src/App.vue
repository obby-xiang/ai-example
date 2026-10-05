<template>
  <el-container style="height: 100vh">
    <el-header class="app-header" height="56px">
      <span class="logo">⚙ AI 辅助动态配置管理系统</span>
      <div class="nav">
        <span v-for="item in navItems" :key="item.path"
              :class="['nav-item', { active: route.path.startsWith(item.path) }]"
              @click="router.push(item.path)">{{ item.label }}</span>
      </div>
      <div style="flex: 1"></div>
      <span class="text-muted" style="color: #cfe0f3">{{ backendInfo }}</span>
    </el-header>
    <el-container>
      <el-main class="workspace">
        <router-view />
      </el-main>
      <el-aside class="ai-side" width="400px">
        <AiPanel />
      </el-aside>
    </el-container>
  </el-container>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import AiPanel from './components/AiPanel.vue'
import { api } from './api'
import { useDefsStore } from './stores/defs'

const route = useRoute()
const router = useRouter()
const defsStore = useDefsStore()
const backendInfo = ref('')

const navItems = [
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
})
</script>
