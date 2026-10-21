/**
 * 路由：五页齐备（FR-1.1 任务中心 / 导出向导 / 导入向导 / 配置定义 / 数据浏览）。
 *
 * 裁剪性（ADR-10 回归用例）：隐藏 AI 栏后全部业务功能仍可用 —— 路由与页面
 * 都不依赖 AiPanel 的挂载（AI 侧只经由 stores/workspace 的窄接口联动）。
 */

import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'
import i18n from '@/locales'

export const ROUTE_NAMES = {
  taskCenter: 'task-center',
  exportWizard: 'export-wizard',
  importWizard: 'import-wizard',
  definitions: 'definitions',
  dataBrowser: 'data-browser',
  aiFeedback: 'ai-feedback',
  aiSettings: 'ai-settings'
} as const

export type RouteName = (typeof ROUTE_NAMES)[keyof typeof ROUTE_NAMES]

const routes: RouteRecordRaw[] = [
  { path: '/', redirect: { name: ROUTE_NAMES.taskCenter } },
  {
    path: '/tasks',
    name: ROUTE_NAMES.taskCenter,
    component: () => import('@/views/TaskCenterView.vue'),
    meta: { titleKey: 'route.taskCenter', pageId: 'tasks' }
  },
  {
    path: '/export',
    name: ROUTE_NAMES.exportWizard,
    component: () => import('@/views/ExportWizardView.vue'),
    meta: { titleKey: 'route.exportWizard', pageId: 'export' }
  },
  {
    path: '/import',
    name: ROUTE_NAMES.importWizard,
    component: () => import('@/views/ImportWizardView.vue'),
    meta: { titleKey: 'route.importWizard', pageId: 'import' }
  },
  {
    path: '/definitions',
    name: ROUTE_NAMES.definitions,
    component: () => import('@/views/DefinitionsView.vue'),
    meta: { titleKey: 'route.definitions', pageId: 'definitions' }
  },
  {
    path: '/data',
    name: ROUTE_NAMES.dataBrowser,
    component: () => import('@/views/DataBrowserView.vue'),
    meta: { titleKey: 'route.dataBrowser', pageId: 'data' }
  },
  {
    path: '/settings/ai',
    name: ROUTE_NAMES.aiSettings,
    component: () => import('@/views/AiSettingsView.vue'),
    meta: { titleKey: 'route.aiSettings', pageId: 'settings' }
  },
  {
    path: '/settings/ai-feedback',
    name: ROUTE_NAMES.aiFeedback,
    component: () => import('@/views/AiFeedbackView.vue'),
    meta: { titleKey: 'route.aiFeedback', pageId: 'settings' }
  },
  { path: '/:pathMatch(.*)*', redirect: { name: ROUTE_NAMES.taskCenter } }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

/**
 * 文档标题跟随路由 + 语言：title 以 `meta.titleKey` 存 key（不存译文），
 * 切换语言后由本钩子重算 —— 否则标题会停留在进入页面时的语言。
 */
export function applyDocumentTitle(to: { meta: Record<string | number | symbol, unknown> }): void {
  const key = to.meta.titleKey
  if (typeof key === 'string' && key) {
    document.title = i18n.global.t(key)
  }
}

router.afterEach(applyDocumentTitle)

export { routes }

export default router
