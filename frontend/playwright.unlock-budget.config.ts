import { defineConfig, devices } from '@playwright/test'

/**
 * 解锁域缩阈值档（17b-unlock-budget：S-09 / S-11 / A-08）。
 *
 * 为什么要单独一档：`app.ai.tool.exec-timeout`（默认 120s）与 `app.ai.resilience.total-budget`
 * （默认 300s）都以分钟计，主档等不起；而 `E2E_EXTRA_BACKEND_ARGS` 是**整棒生效**的 ——
 * 1ms 的工具超时会连带压死主档里所有要"工具真的执行成功"的用例，故拆档。
 * 主档（17-unlock）跑在主 config 里，本档独占端口段 219xx / DB 8-9 / RUNDIR / 产物目录。
 *
 * 运行：cd frontend && npx playwright test --config playwright.unlock-budget.config.ts
 */
process.env.E2E_RUN_DIR ??= 'test-results/.e2e-run-unlock-budget'
process.env.E2E_BACKEND_PORT ??= '21930'
process.env.E2E_DEGRADED_BACKEND_PORT ??= '21931'
process.env.E2E_STUB_PORT ??= '21999'
process.env.E2E_APP_PORT ??= '22001'
process.env.E2E_DEGRADED_APP_PORT ??= '22002'
process.env.E2E_REDIS_DB ??= '8'
process.env.E2E_REDIS_DB_DEGRADED ??= '9'
process.env.E2E_EXTRA_BACKEND_ARGS ??=
  '--app.ai.tool.exec-timeout=1ms --app.ai.resilience.total-budget=8s --app.ai.hitl.frontend-tool-timeout=4s'

const appBase = process.env.E2E_APP ?? `http://127.0.0.1:${process.env.E2E_APP_PORT}`
const isCI = !!process.env.CI

export default defineConfig({
  testDir: './e2e',
  testMatch: /17b-unlock-budget\.spec\.ts/,
  fullyParallel: false,
  forbidOnly: isCI,
  retries: 0,
  workers: 1,
  timeout: 120_000,
  expect: { timeout: 15_000 },
  reporter: [
    ['list'],
    ['html', { outputFolder: process.env.E2E_REPORT_DIR ?? 'test-results/html-unlock-budget', open: 'never' }]
  ],
  outputDir: process.env.E2E_OUTPUT_DIR ?? 'test-results/artifacts-unlock-budget',
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
