import { defineConfig, devices } from '@playwright/test'

/**
 * NFEZUI 域（N/F/E/Z/U/I）专用 Playwright 配置。
 *
 * 隔离形态（L-81 收口后）= **一组环境变量**，不再是 harness 副本：
 *   1) 本文件加载期补本域默认值（`??=` ⇒ 显式 env 优先）：运行目录 / 六端口 / Redis 库 13-14 /
 *      本域桩脚本 + 二级官方桩 / 挂起池 3 / 限流 400·4000 / 就绪超时 —— 统一 `support/harness.ts`
 *      只按这些 env 起停，不再有 `harness-nfezui.ts`；
 *   2) `baseURL` 指本域前端（19201）；
 *   3) `testMatch` 只收本域 spec —— 同族并行时各跑各的，不会扫到别人的文件。
 *
 * 口径（设计稿 §0.1）：只跑 Chromium；trace/截图仅失败保留；不挂 push；端口可参数化。
 */
process.env.E2E_RUN_DIR ??= 'test-results/.e2e-run-nfezui'
process.env.E2E_BACKEND_PORT ??= '19130'
process.env.E2E_DEGRADED_BACKEND_PORT ??= '19131'
process.env.E2E_STUB_UPSTREAM_PORT ??= '19198'
process.env.E2E_STUB_PORT ??= '19199'
process.env.E2E_APP_PORT ??= '19201'
process.env.E2E_DEGRADED_APP_PORT ??= '19202'
process.env.E2E_REDIS_DB ??= '13'
process.env.E2E_REDIS_DB_DEGRADED ??= '14'
// 本域桩：命中 NFZ-* 提示词时自造形状，其余请求逐字节转发二级官方桩（19198）
process.env.E2E_STUB_SCRIPT ??= 'e2e/support/nfezui-upstream.mjs'
// E-08 的池饱和面：并发轮次池压到 3（第 4 轮并发即 503 SUSPEND_POOL_SATURATED）
process.env.E2E_EXTRA_BACKEND_ARGS ??= '--app.ai.suspend.pool-size=3'
process.env.E2E_AI_LIMIT_SESSION_PER_MINUTE ??= '400'
process.env.E2E_AI_LIMIT_IP_PER_MINUTE ??= '4000'
process.env.E2E_READY_TIMEOUT_MS ??= '150000'
process.env.E2E_SEED_TIMEOUT_MS ??= '120000'
process.env.E2E_APP_READY_TIMEOUT_MS ??= '120000'

const appBase = process.env.E2E_APP ?? `http://127.0.0.1:${process.env.E2E_APP_PORT}`
const isCI = !!process.env.CI

export default defineConfig({
  // 配置文件本身在 e2e/ 内：testDir 指本目录，其余相对路径同基准
  testDir: '.',
  testMatch: /13[a-z]?-nfezui\.spec\.ts/,
  fullyParallel: false,
  forbidOnly: isCI,
  retries: 0,
  workers: 1,
  timeout: 120_000,
  expect: { timeout: 15_000 },
  reporter: [
    ['list'],
    ['html', { outputFolder: '../test-results/html-nfezui', open: 'never' }]
  ],
  outputDir: '../test-results/artifacts-nfezui',
  grep: new RegExp(process.env.E2E_MODE === 'real' ? '@real' : '@stub'),
  globalSetup: './support/global-setup-nfezui.ts',
  globalTeardown: './support/global-teardown-nfezui.ts',
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
