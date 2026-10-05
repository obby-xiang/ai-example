<template>
  <el-container class="app-layout">
    <!-- Left: Business workspace -->
    <el-main class="workspace-panel">
      <el-menu
        :default-active="$route.path"
        mode="horizontal"
        router
        class="top-nav"
      >
        <el-menu-item index="/tasks">
          <el-icon><List /></el-icon>
          任务中心
        </el-menu-item>
        <el-menu-item index="/definitions">
          <el-icon><Setting /></el-icon>
          配置定义
        </el-menu-item>
        <el-menu-item index="/data">
          <el-icon><DataBoard /></el-icon>
          配置数据
        </el-menu-item>
      </el-menu>

      <div class="workspace-content">
        <router-view />
      </div>
    </el-main>

    <!-- Right: AI Chat Panel (always visible) -->
    <el-aside class="ai-panel-container" width="380px">
      <AiPanel />
    </el-aside>
  </el-container>
</template>

<script setup>
import { onMounted } from 'vue'
import { useAiStore } from '@/stores/ai.js'
import AiPanel from '@/components/AiPanel/AiPanel.vue'

const aiStore = useAiStore()

onMounted(async () => {
  await aiStore.initSession()
})
</script>

<style lang="scss">
.app-layout {
  height: 100vh;
  display: flex;
  overflow: hidden;
}

.workspace-panel {
  flex: 1;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  padding: 0;
  border-right: 1px solid var(--el-border-color);
}

.top-nav {
  flex-shrink: 0;
  border-bottom: 1px solid var(--el-border-color);
}

.workspace-content {
  flex: 1;
  overflow: auto;
  padding: 20px;
}

.ai-panel-container {
  flex-shrink: 0;
  overflow: hidden;
  display: flex;
  flex-direction: column;
}
</style>
