/**
 * 应用入口：Vue 3 + TS + Pinia + Router + Element Plus（中文 locale）
 * + Tailwind（preflight=false）。
 *
 * SpreadJS 已改为**按需动态加载**（`utils/spreadjs.ts` 的 loadSpreadCore）：入口不再
 * 静态 import 它，故首屏 chunk 不背 6.2MB 的 spreadjs 包（只有真正渲染表格的页面
 * 才经动态 import 拉取，中文 Culture 与 LicenseKey 在那一刻各自设置）。
 */

import { createApp, type Component } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import 'element-plus/dist/index.css'
import * as ElementPlusIconsVue from '@element-plus/icons-vue'
import dayjs from 'dayjs'
import 'dayjs/locale/zh-cn'

import App from './App.vue'
import router from './router'
import './styles/main.css'

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
