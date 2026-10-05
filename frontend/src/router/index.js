import { createRouter, createWebHashHistory } from 'vue-router'

const routes = [
  { path: '/', name: 'home', component: () => import('@/views/Home.vue') },
  { path: '/task/:id', name: 'task', component: () => import('@/views/TaskWizard.vue') },
  { path: '/tasks', name: 'tasks', component: () => import('@/views/TaskCenter.vue') }
]

export default createRouter({
  history: createWebHashHistory(),
  routes
})
