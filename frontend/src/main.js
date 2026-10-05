import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import 'element-plus/dist/index.css'
import GC from '@grapecity/spread-sheets'
import '@grapecity/spread-sheets-resources-zh'
import '@grapecity/spread-sheets/styles/gc.spread.sheets.excel2013white.css'
// 全局样式（Tailwind）：放在 Element Plus / SpreadJS 样式之后，
// 使工具类在同优先级下能覆盖组件默认样式（如 el-main 的 padding）
import './style.css'

import App from './App.vue'
import router from './router'

const key = import.meta.env.VITE_SPREADJS_KEY
if (key) {
  GC.Spread.Sheets.LicenseKey = key
}
GC.Spread.Common.CultureManager.culture('zh-cn')

const app = createApp(App)
app.use(createPinia())
app.use(router)
app.use(ElementPlus, { locale: zhCn })
app.mount('#app')
