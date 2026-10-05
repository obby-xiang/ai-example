import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import 'element-plus/dist/index.css'
import GC from '@grapecity/spread-sheets'
import '@grapecity/spread-sheets/styles/gc.spread.sheets.excel2013white.css'
import App from './App.vue'
import router from './router'
import './styles/main.css'

// SpreadJS LicenseKey：正式使用需购买授权并写入 .env.local（不入库）。
// 未授权为评估模式：功能完整但带水印（见 docs/06-部署运行手册.md）。
GC.Spread.Sheets.LicenseKey = import.meta.env.VITE_SPREADJS_KEY || ''

const app = createApp(App)
app.use(createPinia())
app.use(router)
app.use(ElementPlus, { locale: zhCn })
app.mount('#app')
