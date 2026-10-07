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
          class="!border-b-0"
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

    <!-- 左右双栏：左工作区（业务），右 AI 栏（400px 展开 / 48px 收起，蓝本 App.vue:6-12 同款） -->
    <main class="flex-1 flex min-h-0">
      <section class="flex-1 min-w-0 min-h-0 overflow-auto p-4">
        <router-view />
      </section>
      <el-aside
        :width="panelWidth"
        class="border-l border-solid border-[#e4e7ed] bg-white transition-[width] duration-200 overflow-hidden"
      >
        <AiPanel />
      </el-aside>
    </main>

    <footer class="px-4 py-1 text-xs text-gray-400 bg-white border-t border-gray-200">
      工作区契约 v{{ workspace.contractVersion }} ｜ 上下文 {{ workspace.contextKey }} ｜ 数据版本 {{ workspace.dataVersion }}
      <span v-if="!ai.expanded" class="ml-2">（AI 栏已收起为 48px 侧条，全部业务功能仍可用）</span>
    </footer>
  </div>
</template>

<script setup lang="ts">
/**
 * 应用外壳：左工作区 + 右 AI 栏（AI 栏收起为 48px 竖排条，蓝本同形态）。
 *
 * 裁剪性（ADR-10 回归用例）在这里体现：AiPanel 与业务路由/页面之间只隔
 * stores/workspace 的窄接口；收起 AI 栏（48px 侧条）或整体移除本组件后，
 * 五页业务全流程照常可用（导出/导入/发布/定义/数据浏览均有页面内按钮）。
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

/** AI 栏宽度：蓝本 400px 展开 / 48px 收起。 */
const panelWidth = computed(() => (ai.expanded ? '400px' : '48px'))

onMounted(() => {
  // 进入应用即声明工作区口径（任务中心），向导页进入后由页面自身覆盖
  workspace.enterPage({ pageId: 'tasks', page: 'tasks' })
})
</script>
