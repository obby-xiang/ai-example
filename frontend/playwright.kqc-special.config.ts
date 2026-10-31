import { fileURLToPath } from 'node:url'
import { defineConfig, devices } from '@playwright/test'

/**
 * KQC 域降级档（16-kqc-b：K-06 索引未就绪 / C-02 分母未知）。
 *
 * 这两条要把后端起在「知识索引不可用」与「上下文分母未知」的形态下，而 harness 一棒只起一套服务
 * （`E2E_EXTRA_BACKEND_ARGS` 整棒生效）⇒ 与主档分开一棒：
 *   - `--app.ai.kb.dir=<repo>/scripts`：存在但不是知识仓（无 manifest.json）⇒ 播种跳过、索引建不成；
 *   - `--app.ai.context.limit-tokens=0`：分母未知 ⇒ 端点 limitTokens/percent 为 null。
 *
 * 端口段 197xx / DB 14-15、RUNDIR 与产物目录均独立；起停仍由统一 `support/harness.ts` 负责。
 *
 * 运行：cd frontend && npx playwright test --config playwright.kqc-special.config.ts
 */
const KB_DIR = fileURLToPath(new URL('../scripts', import.meta.url))

process.env.E2E_RUN_DIR ??= 'test-results/.e2e-run-kqc-special'
process.env.E2E_BACKEND_PORT ??= '19730'
process.env.E2E_DEGRADED_BACKEND_PORT ??= '19731'
process.env.E2E_STUB_PORT ??= '19799'
process.env.E2E_APP_PORT ??= '19801'
process.env.E2E_DEGRADED_APP_PORT ??= '19802'
process.env.E2E_REDIS_DB ??= '14'
process.env.E2E_REDIS_DB_DEGRADED ??= '15'
process.env.E2E_STUB_SCRIPT ??= 'e2e/support/kqc-upstream.mjs'
// 文件级闸门（16-kqc-b 抬头）：不带此 env 时用例整体 skip
process.env.E2E_KQC_SPECIAL ??= '1'
process.env.E2E_EXTRA_BACKEND_ARGS ??= `--app.ai.kb.dir=${KB_DIR} --app.ai.context.limit-tokens=0`

const appBase = process.env.E2E_APP ?? `http://127.0.0.1:${process.env.E2E_APP_PORT}`
const isCI = !!process.env.CI

export default defineConfig({
  testDir: './e2e',
  testMatch: /16-kqc-b\.spec\.ts/,
  fullyParallel: false,
  forbidOnly: isCI,
  retries: 0,
  workers: 1,
  timeout: 120_000,
  expect: { timeout: 15_000 },
  reporter: [
    ['list'],
    ['html', { outputFolder: process.env.E2E_REPORT_DIR ?? 'test-results/html-kqc-special', open: 'never' }]
  ],
  outputDir: process.env.E2E_OUTPUT_DIR ?? 'test-results/artifacts-kqc-special',
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
