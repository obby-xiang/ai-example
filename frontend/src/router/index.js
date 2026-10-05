import { createRouter, createWebHistory } from 'vue-router'
import { useWorkspaceStore } from '@/stores/workspace.js'

const routes = [
  {
    path: '/',
    redirect: '/tasks'
  },
  {
    path: '/tasks',
    name: 'tasks',
    component: () => import('@/views/TaskListView.vue'),
    meta: { page: 'tasks' }
  },
  {
    path: '/tasks/:id/export/:step',
    name: 'export-wizard',
    component: () => import('@/views/export/ExportWizardView.vue'),
    meta: { page: 'task', taskType: 'EXPORT' }
  },
  {
    path: '/tasks/:id/import/:step',
    name: 'import-wizard',
    component: () => import('@/views/import/ImportWizardView.vue'),
    meta: { page: 'task', taskType: 'IMPORT' }
  },
  {
    path: '/definitions',
    name: 'definitions',
    component: () => import('@/views/DefinitionsView.vue'),
    meta: { page: 'definitions' }
  },
  {
    path: '/data',
    name: 'data',
    component: () => import('@/views/DataBrowserView.vue'),
    meta: { page: 'data' }
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

router.afterEach((to) => {
  // Update workspace context after navigation
  try {
    const store = useWorkspaceStore()
    store.updateFromRoute(to)
  } catch (e) {
    // store might not be ready during first navigation
  }
})

export default router
