import { defineConfig, devices } from '@playwright/test'

/**
 * H/G/T/A 域真机 E2E 专用配置（并行批隔离用）。
 *
 * 隔离形态（L-81 收口后）= **只差一组环境变量**，不再有 harness 副本：
 *   1. 本文件在加载期把本域默认值补进 `process.env`（`??=` ⇒ 显式传的 env 永远优先），
 *      统一 harness 与各 spec 读同一份 —— 所以「裸跑本配置」也不会落回 18330/18401 而与
 *      config 的 baseURL 对不上（批② 首跑 27 条 ERR_CONNECTION_REFUSED 的根因）；
 *   2. 产物目录改 `test-results/hgta-*`（不与别路的 artifacts/html 互覆盖）；
 *   3. 其余（单 worker、Chromium、超时、@stub/@real 选择器）与主配置同口径。
 *
 * 起停仍由 globalSetup/Teardown 负责（统一 `support/harness.ts`，`E2E_RUN_DIR` 独立、pid 台账不共用）；
 * 若已有实例在跑，setup 的探活守卫会跳过自起与收尾。
 *
 * 运行：cd frontend && npx playwright test --config playwright.hgta.config.ts e2e/11a-hgta.spec.ts
 */
process.env.E2E_RUN_DIR ??= 'test-results/.e2e-run-hgta'
process.env.E2E_BACKEND_PORT ??= '18730'
process.env.E2E_DEGRADED_BACKEND_PORT ??= '18731'
process.env.E2E_STUB_PORT ??= '18799'
process.env.E2E_APP_PORT ??= '18801'
process.env.E2E_DEGRADED_APP_PORT ??= '18802'
process.env.E2E_REDIS_DB ??= '11'

const appBase = process.env.E2E_APP ?? `http://127.0.0.1:${process.env.E2E_APP_PORT}`

export default defineConfig({
  testDir: './e2e',
  // 与 nfezui / gf2 / 各域 config 同口径：只收本域 spec（避免裸跑本 config 时把别域 spec 一并收集，
  // 与 L-107 同一类问题；显式传文件参数仍可用）。
  testMatch: /11[ab]-hgta\.spec\.ts/,
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
      // 设备预设自带 viewport 1280×720，会覆盖顶层 use 的同名键（project.use 合并在后）；
      // 故在展开之后显式写回 1440×900，保证实际生效的是顶层口径。
      use: { ...devices['Desktop Chrome'], viewport: { width: 1440, height: 900 } }
    }
  ]
})
