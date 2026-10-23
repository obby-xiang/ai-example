import { defineConfig, devices } from '@playwright/test'

/**
 * 真机 E2E（Playwright，仅 Chromium）配置。
 *
 * 口径（设计稿 docs/research/设计-真机E2E用例全集-2026-10-23.md §0.1）：
 * - 只跑 Chromium，不做浏览器兼容矩阵；
 * - trace / screenshot 仅失败时保留，全部产物落 frontend/test-results（.gitignore 已排除）；
 * - 自起三件套（桩上游 + 独立后端 + 前端 dev server）由 globalSetup 负责，端口全部可参数化，
 *   默认 18399 / 18330 / 18401，**绝不占用常开的 8081 / 5202**；
 * - 真模型模式由 E2E_MODE=real + AI_API_KEY/AI_BASE_URL/AI_MODEL 注入，用例按 @real 标记筛选。
 *
 * 运行方式：
 *   npx playwright test --grep @stub          # 桩模式（默认）
 *   E2E_MODE=real npx playwright test --grep @real
 */
const appBase = process.env.E2E_APP ?? `http://127.0.0.1:${process.env.E2E_APP_PORT ?? '18401'}`
const isCI = !!process.env.CI

export default defineConfig({
  testDir: './e2e',
  // 用例共享同一后端 / Redis / 上游桩，并行会互相干扰（会话、取消、挂起）；
  // 单 worker 串行是当前仓情的稳定口径（全套 P0 预算 ≤15min，见设计稿 §5.2）。
  fullyParallel: false,
  forbidOnly: isCI,
  retries: 0,
  workers: 1,
  timeout: 120_000,
  expect: { timeout: 15_000 },
  reporter: [
    ['list'],
    ['html', { outputFolder: 'test-results/html', open: 'never' }]
  ],
  outputDir: 'test-results/artifacts',
  // 模式即选择器：stub 模式只跑 @stub，real 模式只跑 @real（同一份配置，避免真/桩用例互相污染）。
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
    // 真 key 只存在于后端进程的环境变量里，浏览器侧请求不携带 key（上游调用由后端发起），
    // 因此 trace / 截图 / 日志天然不含密钥；harness 也不回显任何环境变量值。
    launchOptions: { args: ['--disable-backgrounding-occluded-windows', '--disable-renderer-backgrounding'] }
  },
  projects: [
    {
      name: 'chromium',
      use: { ...devices['Desktop Chrome'] }
    }
  ]
})
