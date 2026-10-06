<template>
  <div class="flex h-screen w-screen overflow-hidden bg-slate-100">
    <!-- 左：业务工作区 -->
    <div class="flex min-w-0 flex-1 flex-col border-r border-slate-200">
      <el-menu
        :default-active="$route.path"
        mode="horizontal"
        router
        class="shrink-0 border-b border-slate-200 px-2"
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

      <div class="min-h-0 flex-1 overflow-auto p-5">
        <router-view />
      </div>
    </div>

    <!-- 右：AI 对话栏（常驻，不随路由销毁） -->
    <div class="w-[380px] shrink-0 border-l border-slate-200 bg-white">
      <AiPanel />
    </div>
  </div>
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
