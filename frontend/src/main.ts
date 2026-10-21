/**
 * 应用入口：Vue 3 + TS + Pinia + Router + Element Plus + vue-i18n
 * + Tailwind（preflight=false）。
 *
 * SpreadJS 已改为**按需动态加载**（`utils/spreadjs.ts` 的 loadSpreadCore）：入口不再
 * 静态 import 它，故首屏 chunk 不背 6.2MB 的 spreadjs 包（只有真正渲染表格的页面
 * 才经动态 import 拉取，中文 Culture 与 LicenseKey 在那一刻各自设置）。
 *
 * 语言：i18n 实例由 `locales/index.ts` 装配，Element Plus 的 locale 改由 `App.vue`
 * 的 `<el-config-provider>` 下发（入口不再传静态 locale，否则切语言后内建文案不跟随）。
 */

import { createApp, type Component } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import * as ElementPlusIconsVue from '@element-plus/icons-vue'

import App from './App.vue'
import router from './router'
import i18n from './locales'
import { useLocaleStore } from './stores/locale'
import './styles/main.css'

const app = createApp(App)
const pinia = createPinia()

app.use(pinia)
app.use(i18n)
app.use(router)
app.use(ElementPlus)

// 启动恢复语言（i18n / <html lang> / localStorage / dayjs 一次到位）
useLocaleStore(pinia).init()

for (const [name, component] of Object.entries(ElementPlusIconsVue)) {
  app.component(name, component as Component)
}

app.config.errorHandler = (error) => {
  console.error('[vue] 未捕获错误', error)
}

app.mount('#app')
