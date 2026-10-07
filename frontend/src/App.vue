<template>
  <div class="h-full flex flex-col">
    <header class="flex items-center justify-between px-4 py-2 bg-white border-b border-gray-200">
      <div class="flex items-center gap-4">
        <span class="text-lg font-medium">配置管理平台</span>
        <el-menu
          :default-active="activeRoute"
          mode="horizontal"
          :ellipsis="false"
          router
          class="app-nav"
        >
          <el-menu-item :index="ROUTE_NAMES.taskCenter" :route="{ name: ROUTE_NAMES.taskCenter }">任务中心</el-menu-item>
          <el-menu-item :index="ROUTE_NAMES.exportWizard" :route="{ name: ROUTE_NAMES.exportWizard }">导出向导</el-menu-item>
          <el-menu-item :index="ROUTE_NAMES.importWizard" :route="{ name: ROUTE_NAMES.importWizard }">导入向导</el-menu-item>
          <el-menu-item :index="ROUTE_NAMES.definitions" :route="{ name: ROUTE_NAMES.definitions }">配置定义</el-menu-item>
          <el-menu-item :index="ROUTE_NAMES.dataBrowser" :route="{ name: ROUTE_NAMES.dataBrowser }">数据浏览</el-menu-item>
        </el-menu>
      </div>
      <el-button link @click="ai.toggleExpand()">
        {{ ai.expanded ? '隐藏 AI 栏' : '显示 AI 栏' }}
      </el-button>
    </header>

    <!-- 左右双栏：左工作区（业务），右 AI 栏（可折叠隐藏 = 裁剪性预留） -->
    <main class="flex-1 flex min-h-0">
      <section class="flex-1 min-w-0 layout-column p-4">
        <router-view />
      </section>
      <AiPanel v-if="ai.expanded" />
    </main>

    <footer class="px-4 py-1 text-xs text-gray-400 bg-white border-t border-gray-200">
      工作区契约 v{{ workspace.contractVersion }} ｜ 上下文 {{ workspace.contextKey }} ｜ 数据版本 {{ workspace.dataVersion }}
      <span v-if="!ai.expanded" class="ml-2">（AI 栏已隐藏，全部业务功能仍可用）</span>
    </footer>
  </div>
</template>

<script setup lang="ts">
/**
 * 应用外壳：左工作区 + 右 AI 栏（AI 栏可折叠隐藏）。
 *
 * 裁剪性（ADR-10 回归用例）在这里体现：AiPanel 是否挂载与业务路由/页面无关，
 * 隐藏后 router-view 自动占满宽度，业务全流程照常可用。
 */
import { computed, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import AiPanel from '@/components/AiPanel/AiPanel.vue'
import { ROUTE_NAMES } from '@/router'
import { useAiStore } from '@/stores/ai'
import { useWorkspaceStore } from '@/stores/workspace'

const ai = useAiStore()
const workspace = useWorkspaceStore()
const route = useRoute()

const activeRoute = computed(() => String(route.name ?? ROUTE_NAMES.taskCenter))

onMounted(() => {
  // 进入应用即声明工作区口径（任务中心），向导页进入后由页面自身覆盖
  workspace.enterPage({ pageId: 'tasks', page: 'tasks' })
})
</script>

<style scoped>
.app-nav {
  border-bottom: none;
}
</style>
