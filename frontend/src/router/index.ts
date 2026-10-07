/**
 * 路由：五页齐备（FR-1.1 任务中心 / 导出向导 / 导入向导 / 配置定义 / 数据浏览）。
 *
 * 裁剪性（ADR-10 回归用例）：隐藏 AI 栏后全部业务功能仍可用 —— 路由与页面
 * 都不依赖 AiPanel 的挂载（AI 侧只经由 stores/workspace 的窄接口联动）。
 */

import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'

export const ROUTE_NAMES = {
  taskCenter: 'task-center',
  exportWizard: 'export-wizard',
  importWizard: 'import-wizard',
  definitions: 'definitions',
  dataBrowser: 'data-browser'
} as const

export type RouteName = (typeof ROUTE_NAMES)[keyof typeof ROUTE_NAMES]

const routes: RouteRecordRaw[] = [
  { path: '/', redirect: { name: ROUTE_NAMES.taskCenter } },
  {
    path: '/tasks',
    name: ROUTE_NAMES.taskCenter,
    component: () => import('@/views/TaskCenterView.vue'),
    meta: { title: '任务中心', pageId: 'tasks' }
  },
  {
    path: '/export',
    name: ROUTE_NAMES.exportWizard,
    component: () => import('@/views/ExportWizardView.vue'),
    meta: { title: '导出向导', pageId: 'export' }
  },
  {
    path: '/import',
    name: ROUTE_NAMES.importWizard,
    component: () => import('@/views/ImportWizardView.vue'),
    meta: { title: '导入向导', pageId: 'import' }
  },
  {
    path: '/definitions',
    name: ROUTE_NAMES.definitions,
    component: () => import('@/views/DefinitionsView.vue'),
    meta: { title: '配置定义', pageId: 'definitions' }
  },
  {
    path: '/data',
    name: ROUTE_NAMES.dataBrowser,
    component: () => import('@/views/DataBrowserView.vue'),
    meta: { title: '数据浏览', pageId: 'data' }
  },
  { path: '/:pathMatch(.*)*', redirect: { name: ROUTE_NAMES.taskCenter } }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

export default router
