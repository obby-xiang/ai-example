import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import 'element-plus/dist/index.css'
import * as ElementPlusIconsVue from '@element-plus/icons-vue'

// SpreadJS 中文资源与样式
import '@grapecity/spread-sheets/styles/gc.spread.sheets.excel2013white.css'
import '@grapecity/spread-sheets-resources-zh'
import GC from '@grapecity/spread-sheets'
GC.Spread.Common.CultureManager.culture('zh-cn')
// LicenseKey 经 VITE_SPREADJS_KEY 注入（不入库）；评估模式仅显示水印，功能可用
const spreadjsKey = import.meta.env.VITE_SPREADJS_KEY
if (spreadjsKey) {
  GC.Spread.Sheets.LicenseKey = spreadjsKey
}

import App from './App.vue'
import router from './router'
import './styles/main.css'

const app = createApp(App)
// 临时调试：全局错误写入标题（E2E 排查用，验证后移除）
window.addEventListener('error', (e) => {
  document.title = 'ERR: ' + e.message
})
window.addEventListener('unhandledrejection', (e) => {
  document.title = 'REJ: ' + (e.reason?.message || String(e.reason))
})
const pinia = createPinia()
app.use(pinia)
app.use(router)
app.use(ElementPlus, { locale: zhCn })
app.config.errorHandler = (err) => {
  document.title = 'VUE: ' + (err?.message || String(err))
}
for (const [key, component] of Object.entries(ElementPlusIconsVue)) {
  app.component(key, component)
}
app.mount('#app')
