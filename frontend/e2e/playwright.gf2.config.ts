import { defineConfig, devices } from '@playwright/test'

/**
 * GF2 域专用 Playwright 配置（L-92：同会话两轮生成式表单）。
 *
 * 为什么单开一个域：本域要的「同一会话连发两张 GF 卡」与官方桩的两条收敛契约直接冲突
 * （已有 `role=tool` 只发文本 / 每条路由每会话只发一次），而官方桩路由表是共享契约、不得为本域扩表 ⇒
 * 换桩脚本（`support/gf2-upstream.mjs`），因此也只收本域 spec，不污染 `@stub` / `@real` 两档。
 *
 * 隔离形态与 `playwright.hgta.config.ts` / `playwright.nfezui.config.ts` 同构 = **只差一组环境变量**：
 * 加载期 `??=` 补本域默认值（显式 env 永远优先），起停仍由统一 `support/harness.ts` 负责
 * （globalSetup/Teardown 与 HGTA 域同一份，只按 `E2E_RUN_DIR` 的标记文件收自己起的实例）。
 *
 * 端口段 22530/22531/22599/22601/22602 + Redis DB 3：与常开的 8081/5202 以及 HGTA(187xx) /
 * NFEZUI(191xx) 两域完全错开。
 *
 * 运行：cd frontend && npx playwright test --config e2e/playwright.gf2.config.ts
 */
process.env.E2E_RUN_DIR ??= 'test-results/.e2e-run-gf2'
process.env.E2E_BACKEND_PORT ??= '22530'
process.env.E2E_DEGRADED_BACKEND_PORT ??= '22531'
process.env.E2E_STUB_PORT ??= '22599'
process.env.E2E_APP_PORT ??= '22601'
process.env.E2E_DEGRADED_APP_PORT ??= '22602'
process.env.E2E_REDIS_DB ??= '3'
// 本域桩：带 `GF-RND` 标记的轮次按轮次发卡，其余请求回纯文本（不转发二级官方桩）
process.env.E2E_STUB_SCRIPT ??= 'e2e/support/gf2-upstream.mjs'

const appBase = process.env.E2E_APP ?? `http://127.0.0.1:${process.env.E2E_APP_PORT}`

export default defineConfig({
  // 配置文件本身在 e2e/ 内：testDir 指本目录，其余相对路径同基准
  testDir: '.',
  testMatch: /11c-gf2\.spec\.ts/,
  fullyParallel: false,
  forbidOnly: false,
  retries: 0,
  workers: 1,
  timeout: 180_000,
  expect: { timeout: 15_000 },
  reporter: [['list']],
  outputDir: '../test-results/gf2-artifacts',
  grep: /@gf2/,
  globalSetup: './support/hgta-global-setup.ts',
  globalTeardown: './support/hgta-global-teardown.ts',
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
