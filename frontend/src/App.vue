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

      <!-- 左右双栏：左工作区（业务），右 AI 栏（可调宽展开 / 48px 收起） -->
      <main class="flex-1 flex min-h-0">
        <!-- R-163B G2：业务区只纵滚、横向 hidden 止血（宽表交 el-table 自带横滚），同 demo 裁而不滚口径 -->
        <section class="flex-1 min-w-0 min-h-0 overflow-y-auto overflow-x-hidden p-4">
          <router-view />
        </section>
        <el-aside
          :width="panelWidth"
          class="relative border-l border-solid border-[#e4e7ed] bg-white overflow-hidden"
          :class="isResizing ? '' : 'transition-[width] duration-200'"
        >
          <div
            v-if="ai.expanded && maxPanelWidth > MIN_AI_PANEL_WIDTH"
            role="separator"
            aria-label="AI panel width"
            aria-orientation="vertical"
            :aria-valuemin="MIN_AI_PANEL_WIDTH"
            :aria-valuemax="maxPanelWidth"
            :aria-valuenow="Math.round(expandedPanelWidth)"
            :aria-valuetext="`${Math.round(expandedPanelWidth)}px`"
            tabindex="0"
            class="group absolute left-0 top-0 z-10 h-full w-2 cursor-col-resize touch-none select-none"
            @pointerdown="startPanelResize"
            @pointermove="resizePanel"
            @pointerup="stopPanelResize"
            @pointercancel="stopPanelResize"
            @lostpointercapture="stopPanelResize"
            @keydown="handlePanelResizeKeydown"
          >
            <span
              class="absolute left-1/2 top-1/2 h-12 w-1 -translate-x-1/2 -translate-y-1/2 rounded-full bg-blue-500 opacity-0 pointer-events-none group-focus-visible:opacity-100"
            />
            <span
              v-if="isResizing"
              class="absolute inset-y-0 left-0 w-0.5 bg-blue-500 pointer-events-none"
            />
          </div>
          <AiPanel />
        </el-aside>
      </main>
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
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute } from 'vue-router'
import AiPanel from '@/components/AiPanel/AiPanel.vue'
import { ROUTE_NAMES } from '@/router'
import { EXPANDED_KEY, useAiStore } from '@/stores/ai'
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

const MIN_AI_PANEL_WIDTH = 360
const MAX_AI_PANEL_WIDTH = 640
const COLLAPSED_AI_PANEL_WIDTH = 48
const AI_PANEL_WIDTH_STORAGE_KEY = 'ai-panel-width'
const viewportWidth = ref(typeof window === 'undefined' ? 1280 : window.innerWidth)

function getDefaultPanelWidth() {
  return Math.min(420, Math.max(MIN_AI_PANEL_WIDTH, viewportWidth.value * 0.3))
}

const preferredPanelWidth = ref(getDefaultPanelWidth())
const maxPanelWidth = computed(() => Math.max(
  MIN_AI_PANEL_WIDTH,
  Math.min(MAX_AI_PANEL_WIDTH, viewportWidth.value - 720),
))
const expandedPanelWidth = computed(() => Math.min(
  maxPanelWidth.value,
  Math.max(MIN_AI_PANEL_WIDTH, preferredPanelWidth.value),
))
const panelWidth = computed(() => `${ai.expanded ? expandedPanelWidth.value : COLLAPSED_AI_PANEL_WIDTH}px`)
const isResizing = ref(false)
let resizePointerId: number | null = null
let resizeStartX = 0
let resizeStartWidth = 0

function persistPanelWidth() {
  try {
    window.localStorage.setItem(AI_PANEL_WIDTH_STORAGE_KEY, String(preferredPanelWidth.value))
  } catch {
    return
  }
}

function restorePanelWidth() {
  try {
    const storedWidth = window.localStorage.getItem(AI_PANEL_WIDTH_STORAGE_KEY)
    if (storedWidth === null) return
    const parsedWidth = Number(storedWidth)
    if (Number.isFinite(parsedWidth)) {
      preferredPanelWidth.value = Math.min(
        MAX_AI_PANEL_WIDTH,
        Math.max(MIN_AI_PANEL_WIDTH, parsedWidth),
      )
    }
  } catch {
    return
  }
}

function handleWindowResize() {
  viewportWidth.value = window.innerWidth
}

function handleStorage(event: StorageEvent) {
  if (event.key === AI_PANEL_WIDTH_STORAGE_KEY) {
    if (isResizing.value) return
    if (event.newValue === null) {
      preferredPanelWidth.value = getDefaultPanelWidth()
      return
    }
    const remoteWidth = Number(event.newValue)
    if (!Number.isFinite(remoteWidth)) return
    preferredPanelWidth.value = Math.min(
      MAX_AI_PANEL_WIDTH,
      Math.max(MIN_AI_PANEL_WIDTH, remoteWidth),
    )
    return
  }
  if (event.key === EXPANDED_KEY) {
    ai.expanded = event.newValue !== '0'
  }
}

function startPanelResize(event: PointerEvent) {
  if (event.button !== 0 || !ai.expanded || maxPanelWidth.value <= MIN_AI_PANEL_WIDTH) return
  event.preventDefault()
  const handle = event.currentTarget as HTMLElement
  handle.focus()
  resizePointerId = event.pointerId
  resizeStartX = event.clientX
  resizeStartWidth = expandedPanelWidth.value
  isResizing.value = true
  handle.setPointerCapture(event.pointerId)
}

function resizePanel(event: PointerEvent) {
  if (!isResizing.value || event.pointerId !== resizePointerId) return
  const nextWidth = resizeStartWidth + resizeStartX - event.clientX
  preferredPanelWidth.value = Math.min(
    maxPanelWidth.value,
    Math.max(MIN_AI_PANEL_WIDTH, nextWidth),
  )
}

function stopPanelResize(event: PointerEvent) {
  if (!isResizing.value || event.pointerId !== resizePointerId) return
  isResizing.value = false
  resizePointerId = null
  persistPanelWidth()
}

function handlePanelResizeKeydown(event: KeyboardEvent) {
  if (event.key !== 'ArrowLeft' && event.key !== 'ArrowRight') return
  event.preventDefault()
  const offset = event.key === 'ArrowLeft' ? 16 : -16
  preferredPanelWidth.value = Math.min(
    maxPanelWidth.value,
    Math.max(MIN_AI_PANEL_WIDTH, expandedPanelWidth.value + offset),
  )
  persistPanelWidth()
}

onMounted(() => {
  viewportWidth.value = window.innerWidth
  restorePanelWidth()
  window.addEventListener('resize', handleWindowResize)
  window.addEventListener('storage', handleStorage)
  // 进入应用即声明工作区口径（任务中心），向导页进入后由页面自身覆盖
  workspace.enterPage({ pageId: 'tasks', page: 'tasks' })
})

onUnmounted(() => {
  window.removeEventListener('resize', handleWindowResize)
  window.removeEventListener('storage', handleStorage)
})
</script>

<style scoped>
/* EP 横向菜单自带 1px 下边框，与设计不符；用实例类 + EP 类提高特异性，
   避免 important 后缀与全局覆写。 */
.app-nav.el-menu--horizontal.el-menu {
  border-bottom-width: 0;
}
</style>
