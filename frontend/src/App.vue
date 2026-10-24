<template>
  <el-config-provider :locale="localeStore.elLocale">
    <div class="h-full flex flex-col">
      <header class="flex items-center justify-between px-4 py-2 bg-white border-b border-gray-200">
        <div class="flex items-center gap-4">
          <span class="text-lg font-medium">{{ t('common.app.title') }}</span>
          <el-menu
            :default-active="activeRoute"
            mode="horizontal"
            :ellipsis="false"
            router
            class="app-nav"
          >
            <el-menu-item :index="ROUTE_NAMES.taskCenter" :route="{ name: ROUTE_NAMES.taskCenter }">{{ t('nav.tasks') }}</el-menu-item>
            <el-menu-item :index="ROUTE_NAMES.definitions" :route="{ name: ROUTE_NAMES.definitions }">{{ t('nav.definitions') }}</el-menu-item>
            <el-menu-item :index="ROUTE_NAMES.dataBrowser" :route="{ name: ROUTE_NAMES.dataBrowser }">{{ t('nav.data') }}</el-menu-item>
            <el-menu-item :index="ROUTE_NAMES.aiSettings" :route="{ name: ROUTE_NAMES.aiSettings }">{{ t('nav.aiSettings') }}</el-menu-item>
            <el-menu-item :index="ROUTE_NAMES.aiFeedback" :route="{ name: ROUTE_NAMES.aiFeedback }">{{ t('nav.aiFeedback') }}</el-menu-item>
          </el-menu>
        </div>
        <div class="flex items-center gap-3">
          <el-button link data-test="locale-toggle" @click="localeStore.toggle()">
            {{ localeStore.isEnglish ? t('nav.langZh') : t('nav.langEn') }}
          </el-button>
          <el-button link @click="ai.toggleExpand()">
            {{ ai.expanded ? t('nav.hideAiPanel') : t('nav.showAiPanel') }}
          </el-button>
        </div>
      </header>

      <!-- 左右双栏：左工作区（业务），右 AI 栏（400px 展开 / 48px 收起，蓝本 App.vue:6-12 同款） -->
      <main class="flex-1 flex min-h-0">
        <!-- R-163B G2：业务区只纵滚、横向 hidden 止血（宽表交 el-table 自带横滚），同 demo 裁而不滚口径 -->
        <section class="flex-1 min-w-0 min-h-0 overflow-y-auto overflow-x-hidden p-4">
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
        {{ t('app.footer.contract', { version: workspace.contractVersion }) }} ｜ {{ t('app.footer.context', { key: workspace.contextKey }) }} ｜ {{ t('app.footer.dataVersion', { version: workspace.dataVersion }) }}
        <span v-if="!ai.expanded" class="ml-2">{{ t('app.footer.collapsedHint') }}</span>
      </footer>
    </div>
  </el-config-provider>
</template>

<script setup lang="ts">
/**
 * 应用外壳：左工作区 + 右 AI 栏（AI 栏收起为 48px 竖排条，蓝本同形态）。
 *
 * 裁剪性（ADR-10 回归用例）在这里体现：AiPanel 与业务路由/页面之间只隔
 * stores/workspace 的窄接口；收起 AI 栏（48px 侧条）或整体移除本组件后，
 * 五页业务全流程照常可用（导出/导入/发布/定义/数据浏览均有页面内按钮）。
 *
 * Element Plus 的 locale 走根级 `<el-config-provider>`（不是入口的静态常量）：
 * 切语言时内建文案（分页/日期面板）必须跟着变。
 */
import { computed, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute } from 'vue-router'
import AiPanel from '@/components/AiPanel/AiPanel.vue'
import { ROUTE_NAMES } from '@/router'
import { useAiStore } from '@/stores/ai'
import { useLocaleStore } from '@/stores/locale'
import { useWorkspaceStore } from '@/stores/workspace'

const { t } = useI18n()
const ai = useAiStore()
const workspace = useWorkspaceStore()
const localeStore = useLocaleStore()
const route = useRoute()

/** 菜单项只有 3 条（任务中心/配置定义/数据浏览）：两向导页无独立菜单项，高亮归算到任务中心。 */
const MENU_FALLBACK_NAMES: string[] = [ROUTE_NAMES.exportWizard, ROUTE_NAMES.importWizard]
const activeRoute = computed(() => {
  const name = String(route.name ?? ROUTE_NAMES.taskCenter)
  return MENU_FALLBACK_NAMES.includes(name) ? ROUTE_NAMES.taskCenter : name
})

/** AI 栏宽度：蓝本 400px 展开 / 48px 收起。 */
const panelWidth = computed(() => (ai.expanded ? '400px' : '48px'))

onMounted(() => {
  // 进入应用即声明工作区口径（任务中心），向导页进入后由页面自身覆盖
  workspace.enterPage({ pageId: 'tasks', page: 'tasks' })
})
</script>

<style scoped>
/* EP 横向菜单自带 1px 下边框，与设计不符；用实例类 + EP 类提高特异性，
   避免 important 后缀与全局覆写。 */
.app-nav.el-menu--horizontal.el-menu {
  border-bottom-width: 0;
}
</style>
