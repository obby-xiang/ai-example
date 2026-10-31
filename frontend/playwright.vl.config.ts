import { defineConfig, devices } from '@playwright/test'

/**
 * VL 域（12a 可视可操作 / 12b 工具全量面）专用 Playwright 配置。
 *
 * 为什么必须单开：本域**自带三件套**（`e2e/support/helpers-vl.ts`：18930 后端 / 18999 桩 / 19001 前端
 * + Redis DB 12），且 `assertSingleApiBase()` 会强制 `E2E_API_BASE` / `E2E_APP` 与本域栈一致 ——
 * 在主 config 的 183xx 栈下跑会直接以「后端基址不一致」报错（不是产品红）。
 *
 * 形态：
 *   - `E2E_EXTERNAL=1`：主 harness 不起 183xx 那套（本域在 beforeAll 里自己起/复用 VL 栈）；
 *   - `E2E_API_BASE` / `E2E_APP` 指向 VL 栈，供 `support/helpers` 的 api / 页面助手使用；
 *   - 产物目录独立；起停由 spec 的 `afterAll`（`stopVlStack`）负责，只收本进程起的实例。
 *
 * 运行：cd frontend && npx playwright test --config playwright.vl.config.ts
 */
process.env.E2E_EXTERNAL ??= '1'
process.env.E2E_API_BASE ??= `http://127.0.0.1:${process.env.VL_BACKEND_PORT ?? '18930'}`
process.env.E2E_APP ??= `http://127.0.0.1:${process.env.VL_APP_PORT ?? '19001'}`

const appBase = process.env.E2E_APP
const isCI = !!process.env.CI

export default defineConfig({
  testDir: './e2e',
  testMatch: /12[ab]-vl-.*\.spec\.ts/,
  fullyParallel: false,
  forbidOnly: isCI,
  retries: 0,
  workers: 1,
  timeout: 180_000,
  expect: { timeout: 15_000 },
  reporter: [
    ['list'],
    ['html', { outputFolder: process.env.E2E_REPORT_DIR ?? 'test-results/html-vl', open: 'never' }]
  ],
  outputDir: process.env.E2E_OUTPUT_DIR ?? 'test-results/artifacts-vl',
  grep: new RegExp(process.env.E2E_MODE === 'real' ? '@real' : '@stub'),
  globalSetup: './e2e/support/global-setup.ts',
  globalTeardown: './e2e/support/global-teardown.ts',
  use: {
    baseURL: appBase,
    locale: 'zh-CN',
    timezoneId: 'Asia/Shanghai',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    video: 'off',
    viewport: { width: 1440, height: 900 },
    launchOptions: { args: ['--disable-backgrounding-occluded-windows', '--disable-renderer-backgrounding'] }
  },
  projects: [
    {
      name: 'chromium',
      // 设备预设自带 viewport 1280×720，会覆盖顶层 use 的同名键（project.use 合并在后）；
      // 故在展开之后显式写回 1440×900，保证实际生效的是顶层口径。
      use: { ...devices['Desktop Chrome'], viewport: { width: 1440, height: 900 } }
    }
  ]
})
