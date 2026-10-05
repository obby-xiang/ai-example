<template>
  <el-container class="app-shell">
    <el-main class="workspace">
      <router-view />
    </el-main>
    <el-aside :width="aiStore.expanded ? '400px' : '48px'" class="ai-side">
      <AiPanel />
    </el-aside>
  </el-container>
</template>

<script setup>
import AiPanel from '@/components/AiPanel.vue'
import { useAiStore } from '@/stores/ai'

const aiStore = useAiStore()
</script>

<style>
html, body, #app {
  height: 100%;
  margin: 0;
  padding: 0;
}
.app-shell {
  height: 100%;
}
.workspace {
  padding: 16px;
  overflow: auto;
  background: #f5f7fa;
}
.ai-side {
  border-left: 1px solid #e4e7ed;
  background: #fff;
  transition: width 0.2s ease;
  overflow: hidden;
}
</style>
