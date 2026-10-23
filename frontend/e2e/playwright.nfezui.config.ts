import { defineConfig, devices } from '@playwright/test'

/**
 * NFEZUI 域（N/F/E/Z/U/I）专用 Playwright 配置。
 *
 * 与 `playwright.config.ts` 的差别**只有三处**，其余口径逐项对齐：
 *   1) `baseURL` 指本域前端（19201），不是共享默认 18401；
 *   2) globalSetup/Teardown 用 `support/global-setup-nfezui.ts`（独占端口段与运行目录，
 *      不删别人的 H2 数据面、不按别人的 pid 收尾）；
 *   3) `testMatch` 只收本域 spec —— 同族并行时各跑各的，不会扫到别人的文件。
 *
 * 口径（设计稿 §0.1）：只跑 Chromium；trace/截图仅失败保留；不挂 push；端口可参数化。
 */
const appBase = process.env.E2E_APP ?? 'http://127.0.0.1:19201'
const isCI = !!process.env.CI

export default defineConfig({
  // 配置文件本身在 e2e/ 内：testDir 指本目录，其余相对路径同基准
  testDir: '.',
  testMatch: /13[a-z]?-nfezui\.spec\.ts/,
  fullyParallel: false,
  forbidOnly: isCI,
  retries: 0,
  workers: 1,
  timeout: 120_000,
  expect: { timeout: 15_000 },
  reporter: [
    ['list'],
    ['html', { outputFolder: '../test-results/html-nfezui', open: 'never' }]
  ],
  outputDir: '../test-results/artifacts-nfezui',
  grep: new RegExp(process.env.E2E_MODE === 'real' ? '@real' : '@stub'),
  globalSetup: './support/global-setup-nfezui.ts',
  globalTeardown: './support/global-teardown-nfezui.ts',
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
      use: { ...devices['Desktop Chrome'] }
    }
  ]
})
