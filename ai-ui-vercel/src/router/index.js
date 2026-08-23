import { createRouter, createWebHistory } from 'vue-router'
import { useTaskStore } from '@/stores/task'

const routes = [
  {
    path: '/',
    redirect: '/dashboard'
  },
  {
    path: '/dashboard',
    name: 'Dashboard',
    component: () => import('@/views/DashboardView.vue'),
    meta: { title: '任务中心' }
  },
  {
    path: '/wizard/select-scenario',
    name: 'StepSelectScenario',
    component: () => import('@/views/wizard/StepSelectScenario.vue'),
    meta: { title: '选择场景', step: 'SELECT_SCENARIO' }
  },
  {
    path: '/wizard/select-defs',
    name: 'StepSelectDefs',
    component: () => import('@/views/wizard/StepSelectDefs.vue'),
    meta: { title: '选择配置项', step: 'SELECT_DEFS' }
  },
  {
    path: '/wizard/view-defs',
    name: 'StepViewDefs',
    component: () => import('@/views/wizard/StepViewDefs.vue'),
    meta: { title: '查看配置项', step: 'VIEW_DEFS' }
  },
  {
    path: '/wizard/query-cond',
    name: 'StepQueryCond',
    component: () => import('@/views/wizard/StepQueryCond.vue'),
    meta: { title: '配置查询', step: 'QUERY_COND' }
  },
  {
    path: '/wizard/precheck',
    name: 'StepPrecheck',
    component: () => import('@/views/wizard/StepPrecheck.vue'),
    meta: { title: '预检查', step: 'PRECHECK' }
  },
  {
    path: '/wizard/review',
    name: 'StepReview',
    component: () => import('@/views/wizard/StepReview.vue'),
    meta: { title: '复核确认', step: 'REVIEW' }
  },
  {
    path: '/wizard/publish',
    name: 'StepPublish',
    component: () => import('@/views/wizard/StepPublish.vue'),
    meta: { title: '发布', step: 'PUBLISH' }
  },
  {
    path: '/wizard/result',
    name: 'StepResult',
    component: () => import('@/views/wizard/StepResult.vue'),
    meta: { title: '查看结果', step: 'RESULT' }
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

/**
 * 根据当前任务的步骤代码映射到路由路径
 */
export function stepToRoute(step) {
  if (!step) return '/dashboard'
  const found = routes.find(r => r.meta?.step === step)
  return found ? found.path : '/dashboard'
}

router.beforeEach((to, from, next) => {
  // 非仪表盘路由必须有任务;若没有则去仪表盘
  if (to.path.startsWith('/wizard')) {
    const taskStore = useTaskStore()
    if (!taskStore.currentTaskId && !taskStore.currentTask) {
      return next('/dashboard')
    }
  }
  next()
})

export default router
