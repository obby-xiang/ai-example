import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import 'element-plus/dist/index.css'
import * as ElementPlusIconsVue from '@element-plus/icons-vue'

// SpreadJS 资源
import '@grapecity/spread-sheets/styles/gc.spread.sheets.excel2013white.css'
import '@grapecity/spread-sheets-resources-zh'
import GC from '@grapecity/spread-sheets'
// 中文资源
GC.Spread.Common.CultureManager.culture('zh-cn')
// SpreadJS LicenseKey(通过 .env.local 注入 VITE_SPREADJS_KEY,不入库)
// 评估模式有水印和功能限制,正式使用需购买授权
GC.Spread.Sheets.LicenseKey = import.meta.env.VITE_SPREADJS_KEY || ''

import App from './App.vue'
import router from './router'
import './styles/main.scss'
import { installDataVersionCapture } from '@/utils/workspace-version'

const app = createApp(App)
const pinia = createPinia()

// 本地持久化插件(只持久化当前任务ID和sessionId和AI聊天,因为完整任务状态以服务端为准)
// 改造 A：会话随窗口——sessionId 存 sessionStorage(刷新存活、关窗自毁、多标签隔离)；
//   store 内 initSessionId() 已负责初始化,插件仅同步后续变更,不再从 localStorage 恢复 sessionId
pinia.use(({ store }) => {
  const stored = localStorage.getItem('pinia-' + store.$id)
  if (stored) {
    try {
      const parsed = JSON.parse(stored)
      if (store.$id === 'task') {
        if (parsed.currentTaskId) store.currentTaskId = parsed.currentTaskId
      }
      if (store.$id === 'ai') {
        if (parsed.expanded) store.expanded = parsed.expanded
      }
    } catch (e) { /* ignore */ }
  }
  store.$subscribe((_mutation, state) => {
    const toSave = {}
    if (store.$id === 'task') toSave.currentTaskId = state.currentTaskId
    if (store.$id === 'ai') {
      try { sessionStorage.setItem('ai-session-id', state.sessionId) } catch (e) { /* ignore */ }
      toSave.expanded = state.expanded
    }
    localStorage.setItem('pinia-' + store.$id, JSON.stringify(toSave))
  })
})

// 改造 D1：安装 dataVersion 捕获（store 白名单 + 路由拦截点）
installDataVersionCapture(pinia, router)

for (const [key, component] of Object.entries(ElementPlusIconsVue)) {
  app.component(key, component)
}

app.use(pinia)
app.use(router)
app.use(ElementPlus, { locale: zhCn })
app.mount('#app')
