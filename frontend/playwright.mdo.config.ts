import { defineConfig, devices } from '@playwright/test'

/**
 * MDO 域（15-mdo：M 多实例 / D 降级 / O 并发）专用 Playwright 配置。
 *
 * 本域拓扑与别域不同：令 `E2E_REDIS_DB == E2E_REDIS_DB_DEGRADED` ⇒ harness 起的 A（有 key）与
 * B（无 key 的降级面）两个**不同 JVM** 看同一份轮次台账；跨实例 `/events` 扇出、跨实例决策、
 * 跨实例读台账才是真的跨进程。
 *
 * 其余与 `playwright.hgta.config.ts` 同构 = 一组环境变量（RUNDIR / 端口段 215xx / DB 12 / 追加后端参数）；
 * 起停仍由统一 `support/harness.ts` 负责，产物目录独立。
 *
 * 运行：cd frontend && npx playwright test --config playwright.mdo.config.ts
 */
process.env.E2E_RUN_DIR ??= 'test-results/.e2e-run-mdo'
process.env.E2E_BACKEND_PORT ??= '21530'
process.env.E2E_DEGRADED_BACKEND_PORT ??= '21531'
process.env.E2E_STUB_PORT ??= '21599'
process.env.E2E_APP_PORT ??= '21601'
process.env.E2E_DEGRADED_APP_PORT ??= '21602'
process.env.E2E_REDIS_DB ??= '12'
process.env.E2E_REDIS_DB_DEGRADED ??= '12'
// 本域需要「挂起确认门」在 15s 内到期的那一面（跨实例重连 / 超时终态）
process.env.E2E_EXTRA_BACKEND_ARGS ??= '--app.ai.hitl.frontend-tool-timeout=15s'

const appBase = process.env.E2E_APP ?? `http://127.0.0.1:${process.env.E2E_APP_PORT}`
const isCI = !!process.env.CI

export default defineConfig({
  testDir: './e2e',
  testMatch: /15-mdo\.spec\.ts/,
  fullyParallel: false,
  forbidOnly: isCI,
  retries: 0,
  workers: 1,
  timeout: 120_000,
  expect: { timeout: 15_000 },
  reporter: [
    ['list'],
    ['html', { outputFolder: process.env.E2E_REPORT_DIR ?? 'test-results/html-mdo', open: 'never' }]
  ],
  outputDir: process.env.E2E_OUTPUT_DIR ?? 'test-results/artifacts-mdo',
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
