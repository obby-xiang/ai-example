import { defineConfig, devices } from '@playwright/test'

/**
 * KQC 域主档（16-kqc：K 知识问答 / Q 快捷建议 / C 上下文）专用 Playwright 配置。
 *
 * 为什么单开：KB 三工具不在官方桩的 `productTools` 共享契约表里 ⇒ 官方桩下 K 域结构性不可达，
 * 本域换桩 `e2e/support/kqc-upstream.mjs`（只补 KB 工具 + usage 四形状 + 越窗 400）。
 * 隔离形态与 `playwright.hgta.config.ts` 同构 = 一组环境变量（RUNDIR / 端口段 217xx / DB 14-15）；
 * 起停仍由统一 `support/harness.ts` 负责，产物目录独立。
 *
 * 本档只收 `16-kqc.spec.ts`；b（K-06/C-02 专用后端参数）与 c（压缩谱系）各有独立 config。
 *
 * 运行：cd frontend && npx playwright test --config playwright.kqc.config.ts
 */
process.env.E2E_RUN_DIR ??= 'test-results/.e2e-run-kqc'
process.env.E2E_BACKEND_PORT ??= '21730'
process.env.E2E_DEGRADED_BACKEND_PORT ??= '21731'
process.env.E2E_STUB_PORT ??= '21799'
process.env.E2E_APP_PORT ??= '21801'
process.env.E2E_DEGRADED_APP_PORT ??= '21802'
process.env.E2E_REDIS_DB ??= '14'
process.env.E2E_REDIS_DB_DEGRADED ??= '15'
process.env.E2E_STUB_SCRIPT ??= 'e2e/support/kqc-upstream.mjs'

const appBase = process.env.E2E_APP ?? `http://127.0.0.1:${process.env.E2E_APP_PORT}`
const isCI = !!process.env.CI

export default defineConfig({
  testDir: './e2e',
  testMatch: /16-kqc\.spec\.ts/,
  fullyParallel: false,
  forbidOnly: isCI,
  retries: 0,
  workers: 1,
  timeout: 120_000,
  expect: { timeout: 15_000 },
  reporter: [
    ['list'],
    ['html', { outputFolder: process.env.E2E_REPORT_DIR ?? 'test-results/html-kqc', open: 'never' }]
  ],
  outputDir: process.env.E2E_OUTPUT_DIR ?? 'test-results/artifacts-kqc',
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
      use: { ...devices['Desktop Chrome'] }
    }
  ]
})
