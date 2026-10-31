import { defineConfig, devices } from '@playwright/test'

/**
 * MDXY 域（14a MD 渲染 / 14b X 安全 / 14c Y 设置）专用 Playwright 配置。
 *
 * 隔离形态与 `playwright.hgta.config.ts` 同构 = **只差一组环境变量**（`??=` ⇒ 显式传的 env 永远优先）：
 *   1. 本域桩 `e2e/support/mdxy-upstream.mjs`（官方桩没有 `md=` / `safety=` 形状）；
 *   2. 独占端口段 21330 / 21331 / 21399 / 21401 / 21402 + Redis DB 13 / 11；
 *   3. 产物目录独立（trace 不与其他域互覆盖）；起停仍由统一 `support/harness.ts` 负责。
 *
 * 前置：Y 域「写入 key」路径需要 `AI_SECRET_KEK`（base64 32B）。仓库内零 key 字面量，
 * 本地/CI 由运行命令注入（CI 见 `.github/workflows/e2e-real.yml` 的 MDXY 步）。
 *
 * 运行：cd frontend && npx playwright test --config playwright.mdxy.config.ts
 */
process.env.E2E_RUN_DIR ??= 'test-results/.e2e-run-mdxy'
process.env.E2E_BACKEND_PORT ??= '21330'
process.env.E2E_DEGRADED_BACKEND_PORT ??= '21331'
process.env.E2E_STUB_PORT ??= '21399'
process.env.E2E_APP_PORT ??= '21401'
process.env.E2E_DEGRADED_APP_PORT ??= '21402'
process.env.E2E_REDIS_DB ??= '13'
process.env.E2E_REDIS_DB_DEGRADED ??= '11'
process.env.E2E_STUB_SCRIPT ??= 'e2e/support/mdxy-upstream.mjs'

if (!process.env.AI_SECRET_KEK) {
  throw new Error(
    'MDXY 域需要 AI_SECRET_KEK（base64 32B，Y 域写入 key 路径的前置）：' +
      '本地可用 `head -c 32 /dev/urandom | base64 -w 0` 生成后经环境变量注入'
  )
}

const appBase = process.env.E2E_APP ?? `http://127.0.0.1:${process.env.E2E_APP_PORT}`
const isCI = !!process.env.CI

export default defineConfig({
  testDir: './e2e',
  testMatch: /14[abc]-mdxy-.*\.spec\.ts/,
  fullyParallel: false,
  forbidOnly: isCI,
  retries: 0,
  workers: 1,
  timeout: 120_000,
  expect: { timeout: 15_000 },
  reporter: [
    ['list'],
    ['html', { outputFolder: process.env.E2E_REPORT_DIR ?? 'test-results/html-mdxy', open: 'never' }]
  ],
  outputDir: process.env.E2E_OUTPUT_DIR ?? 'test-results/artifacts-mdxy',
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
