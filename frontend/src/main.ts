/**
 * 应用入口：Vue 3 + TS + Pinia + Router + Element Plus（中文 locale）
 * + Tailwind（preflight=false）+ SpreadJS（**中文 Culture**，glm 蓝本 main.js 实践）。
 */

import { createApp, type Component } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import 'element-plus/dist/index.css'
import * as ElementPlusIconsVue from '@element-plus/icons-vue'
import dayjs from 'dayjs'
import 'dayjs/locale/zh-cn'

// SpreadJS 中文资源与样式：资源包必须在 CultureManager.culture 之前加载
import '@grapecity/spread-sheets/styles/gc.spread.sheets.excel2013white.css'
import '@grapecity/spread-sheets-resources-zh'
import GC from '@grapecity/spread-sheets'

import App from './App.vue'
import router from './router'
import './styles/main.css'

GC.Spread.Common.CultureManager.culture('zh-cn')

// LicenseKey 经 VITE_SPREADJS_KEY 注入（不入库）；评估模式仅显示水印，功能可用
const spreadjsKey = import.meta.env.VITE_SPREADJS_KEY
if (spreadjsKey) {
  GC.Spread.Sheets.LicenseKey = spreadjsKey
}

dayjs.locale('zh-cn')

const app = createApp(App)
app.use(createPinia())
app.use(router)
app.use(ElementPlus, { locale: zhCn })

for (const [name, component] of Object.entries(ElementPlusIconsVue)) {
  app.component(name, component as Component)
}

app.config.errorHandler = (error) => {
  console.error('[vue] 未捕获错误', error)
}

app.mount('#app')
