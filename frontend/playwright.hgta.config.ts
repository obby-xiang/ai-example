import { defineConfig, devices } from '@playwright/test'

/**
 * H/G/T/A 域真机 E2E 专用配置（并行批隔离用）。
 *
 * 与仓内 `playwright.config.ts` 的差别只有三处，且都为了**多路并行不互相踩**：
 * 1. 不自带 globalSetup/globalTeardown —— 起停由本域 spec 的 beforeAll/afterAll 走
 *    `support/harness-hgta.ts`（`RUN_DIR` 独立，pid 台账不与别路共用）；
 * 2. 产物目录改 `test-results/hgta-*`（不与别路的 artifacts/html 互覆盖）；
 * 3. 其余（单 worker、Chromium、超时、@stub/@real 选择器）与主配置同口径。
 *
 * 若全局 globalSetup 已经起过服务（整仓跑），spec 内的探活守卫会跳过自起与收尾。
 *
 * 运行：cd frontend && npx playwright test --config playwright.hgta.config.ts e2e/11a-hgta.spec.ts
 */
const appBase = process.env.E2E_APP ?? `http://127.0.0.1:${process.env.E2E_APP_PORT ?? '18801'}`

export default defineConfig({
  testDir: './e2e',
  fullyParallel: false,
  forbidOnly: false,
  retries: 0,
  workers: 1,
  timeout: 120_000,
  expect: { timeout: 15_000 },
  reporter: [['list']],
  outputDir: 'test-results/hgta-artifacts',
  grep: new RegExp(process.env.E2E_MODE === 'real' ? '@real' : '@stub'),
  globalSetup: './e2e/support/hgta-global-setup.ts',
  globalTeardown: './e2e/support/hgta-global-teardown.ts',
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
