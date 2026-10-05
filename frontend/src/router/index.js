import { createRouter, createWebHistory } from 'vue-router'

const routes = [
  { path: '/', redirect: '/tasks' },
  { path: '/tasks', name: 'tasks', component: () => import('@/views/TaskListView.vue') },
  { path: '/export/:taskId', name: 'export', component: () => import('@/views/ExportWizardView.vue') },
  { path: '/import/:taskId', name: 'import', component: () => import('@/views/ImportWizardView.vue') }
]

export default createRouter({
  history: createWebHistory(),
  routes
})
