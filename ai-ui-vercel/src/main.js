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

const app = createApp(App)
const pinia = createPinia()

// 本地持久化插件(持久化 currentTaskId / sessionId / expanded)
// AI 消息历史由 ai store 自行按 taskId 持久化到 localStorage
pinia.use(({ store }) => {
  const stored = localStorage.getItem('vercel-' + store.$id)
  if (stored) {
    try {
      const parsed = JSON.parse(stored)
      if (store.$id === 'task') {
        if (parsed.currentTaskId) store.currentTaskId = parsed.currentTaskId
      }
      if (store.$id === 'ai') {
        if (parsed.sessionId) store.sessionId = parsed.sessionId
        if (parsed.expanded !== undefined) store.expanded = parsed.expanded
      }
    } catch (e) { /* ignore */ }
  }
  store.$subscribe((_mutation, state) => {
    const toSave = {}
    if (store.$id === 'task') toSave.currentTaskId = state.currentTaskId
    if (store.$id === 'ai') {
      toSave.sessionId = state.sessionId
      toSave.expanded = state.expanded
    }
    localStorage.setItem('vercel-' + store.$id, JSON.stringify(toSave))
  })
})

for (const [key, component] of Object.entries(ElementPlusIconsVue)) {
  app.component(key, component)
}

app.use(pinia)
app.use(router)
app.use(ElementPlus, { locale: zhCn })
app.mount('#app')
