import { defineConfig, devices } from '@playwright/test'

/**
 * KQC 域压缩档（16-kqc-c：C-08 / C-10 / C-13 / C-14）。
 *
 * 压缩触发判的是「下一次请求预估」占「分母 − reserve-tokens」的比例；默认分母（128000）下
 * 桩的几百 token 工具结果要几十轮才够 70%。本档只把**分母**压小（不改判定语义）：
 *   - `--app.ai.context.limit-tokens=20000` 必须大于 reserve-tokens（13000）以过启动自检；
 *   - 真正的分母由各用例经模型配置写接口临时设置（`helpers-kqc.ts#withModelWindow`），
 *     并断言 `limitTokens === 窗口 && !== 20000`（证明分母来自模型行，不是过渡键）。
 *
 * `usage-shape=repeat` 由用例自带；端口段 229xx / DB 14-15 / RUNDIR / 产物目录均独立。
 *
 * 运行：cd frontend && npx playwright test --config playwright.kqc-compact.config.ts
 */
process.env.E2E_RUN_DIR ??= 'test-results/.e2e-run-kqc-compact'
process.env.E2E_BACKEND_PORT ??= '22930'
process.env.E2E_DEGRADED_BACKEND_PORT ??= '22931'
process.env.E2E_STUB_PORT ??= '22999'
process.env.E2E_APP_PORT ??= '22901'
process.env.E2E_DEGRADED_APP_PORT ??= '22902'
process.env.E2E_REDIS_DB ??= '14'
process.env.E2E_REDIS_DB_DEGRADED ??= '15'
process.env.E2E_STUB_SCRIPT ??= 'e2e/support/kqc-upstream.mjs'
// 文件级闸门（16-kqc-c 抬头）：不带此 env 时用例整体 skip
process.env.E2E_KQC_COMPACT ??= '1'
process.env.E2E_EXTRA_BACKEND_ARGS ??= '--app.ai.context.limit-tokens=20000'

const appBase = process.env.E2E_APP ?? `http://127.0.0.1:${process.env.E2E_APP_PORT}`
const isCI = !!process.env.CI

export default defineConfig({
  testDir: './e2e',
  testMatch: /16-kqc-c\.spec\.ts/,
  fullyParallel: false,
  forbidOnly: isCI,
  retries: 0,
  workers: 1,
  timeout: 180_000,
  expect: { timeout: 15_000 },
  reporter: [
    ['list'],
    ['html', { outputFolder: process.env.E2E_REPORT_DIR ?? 'test-results/html-kqc-compact', open: 'never' }]
  ],
  outputDir: process.env.E2E_OUTPUT_DIR ?? 'test-results/artifacts-kqc-compact',
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
