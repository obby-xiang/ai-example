/**
 * 最小真实 Chromium 探针：ImportWizardView 第 1 步编辑区的 `el-tab-pane`
 * 「未激活标签页不会挂载 ⇒ 没有 SpreadGrid ref」这一前提是否成立。
 *
 * 只读本地静态页（file://，无服务、无后端、不碰 8081/5202）：同一份 element-plus 与 vue
 * 的 UMD 产物渲染 `<el-tabs>` + 3 个 `<el-tab-pane>`，子组件在 onMounted 里记账。
 */
import { chromium } from '@playwright/test'
import { fileURLToPath, pathToFileURL } from 'node:url'
import { dirname, join } from 'node:path'

const here = dirname(fileURLToPath(import.meta.url))
const page_url = pathToFileURL(join(here, 'tabs-harness.html')).href

const browser = await chromium.launch({ channel: undefined, headless: true })
const page = await browser.newPage()
const errors = []
page.on('pageerror', (error) => errors.push(String(error)))
await page.goto(page_url)
await page.waitForSelector('.grid-probe')
await page.waitForTimeout(200)

const afterMount = await page.evaluate(() => ({
  events: [...window.__probe.events],
  childCount: document.querySelectorAll('.grid-probe').length,
  panes: [...document.querySelectorAll('[role="tabpanel"]')].map((el) => ({
    id: el.id,
    hidden: el.getAttribute('aria-hidden'),
    display: getComputedStyle(el).display
  }))
}))

// 切到第二个标签页，观察是否发生"补挂载"（lazy 形态下这里才会出现 mount:DEMO）
await page.click('#tab-DEMO')
await page.waitForTimeout(200)
const afterSwitch = await page.evaluate(() => ({
  events: [...window.__probe.events],
  childCount: document.querySelectorAll('.grid-probe').length
}))

console.log(JSON.stringify({ page_url, errors, afterMount, afterSwitch }, null, 2))
await browser.close()
