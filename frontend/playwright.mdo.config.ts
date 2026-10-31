import { defineConfig, devices } from '@playwright/test'

/**
 * MDO 域（15-mdo：M 多实例 / D 降级 / O 并发）专用 Playwright 配置。
 *
 * 两组使用不同拓扑，不能共用旧的“A 有模型、B 无模型且不同库”装配：
 * - multi：A/B 共享 H2、Redis、上游与模型配置，验证无粘滞下的跨实例订阅、决策与接管；
 * - degraded：A/B 各自独立 H2，B 无模型，验证 D/O 的降级与并发边界。
 * 两组串行运行，RUN_DIR、报告和产物目录分开。默认只跑 multi；另一组必须显式选择。
 *
 * 运行：cd frontend && npx playwright test --config playwright.mdo.config.ts
 * 另一组：cd frontend && E2E_MDO_GROUP=degraded npx playwright test --config playwright.mdo.config.ts
 */
const group = process.env.E2E_MDO_GROUP ?? 'multi'
if (group !== 'multi' && group !== 'degraded') {
  throw new Error(`E2E_MDO_GROUP 只支持 multi/degraded，收到：${group}`)
}
process.env.E2E_SHARED_AI_STACK = group === 'multi' ? '1' : '0'
process.env.E2E_RUN_DIR ??= `test-results/.e2e-run-mdo-${group}`
process.env.E2E_BACKEND_PORT ??= '21530'
process.env.E2E_DEGRADED_BACKEND_PORT ??= '21531'
process.env.E2E_STUB_PORT ??= '21599'
process.env.E2E_APP_PORT ??= '21601'
process.env.E2E_DEGRADED_APP_PORT ??= '21602'
process.env.E2E_REDIS_DB ??= '12'
process.env.E2E_REDIS_DB_DEGRADED ??= '12'
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
    ['html', { outputFolder: process.env.E2E_REPORT_DIR ?? `test-results/html-mdo-${group}`, open: 'never' }]
  ],
  outputDir: process.env.E2E_OUTPUT_DIR ?? `test-results/artifacts-mdo-${group}`,
  grep: new RegExp(process.env.E2E_MODE === 'real' ? '@real' : (group === 'multi' ? '@stub M ' : '@stub [DO] ')),
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
