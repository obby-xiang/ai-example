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

import App from './App.vue'
import router from './router'
import './styles/main.scss'

const app = createApp(App)
const pinia = createPinia()

// 本地持久化插件(只持久化当前任务ID和sessionId和AI聊天,因为完整任务状态以服务端为准)
pinia.use(({ store }) => {
  const stored = localStorage.getItem('pinia-' + store.$id)
  if (stored) {
    try {
      const parsed = JSON.parse(stored)
      if (store.$id === 'task') {
        if (parsed.currentTaskId) store.currentTaskId = parsed.currentTaskId
      }
      if (store.$id === 'ai') {
        if (parsed.sessionId) store.sessionId = parsed.sessionId
        if (parsed.expanded) store.expanded = parsed.expanded
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
    localStorage.setItem('pinia-' + store.$id, JSON.stringify(toSave))
  })
})

for (const [key, component] of Object.entries(ElementPlusIconsVue)) {
  app.component(key, component)
}

app.use(pinia)
app.use(router)
app.use(ElementPlus, { locale: zhCn })
app.mount('#app')
