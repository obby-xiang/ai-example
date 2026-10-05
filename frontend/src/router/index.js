import { createRouter, createWebHistory } from 'vue-router'

const routes = [
  { path: '/', redirect: '/tasks' },
  { path: '/tasks', name: 'tasks', component: () => import('../views/TasksView.vue'), meta: { title: '任务管理' } },
  { path: '/export', name: 'export', component: () => import('../views/ExportWizardView.vue'), meta: { title: '导出配置' } },
  { path: '/import', name: 'import', component: () => import('../views/ImportWizardView.vue'), meta: { title: '导入配置' } },
  { path: '/defs', name: 'defs', component: () => import('../views/DefsAdminView.vue'), meta: { title: '配置定义' } },
  { path: '/data', name: 'data', component: () => import('../views/DataBrowseView.vue'), meta: { title: '数据浏览' } }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

router.afterEach((to) => {
  document.title = (to.meta.title ? to.meta.title + ' - ' : '') + 'AI 辅助动态配置管理系统'
})

export default router
