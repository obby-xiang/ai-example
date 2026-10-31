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
 * - 本配置只收**主栈可跑切片**（00–10、11a/11b、17-unlock）；域专用 spec 由各自 config 承载
 *   （L-107：主 config 曾跨域收集 13 系 / 14 系 / 15 / 16-kqc 系 / 17b，CI 的 `--grep @stub` 会在缺域环境时必红）。
 *   注：12a/12b（VL 域）自带三件套且强制本域基址，故也走独立 config（`playwright.vl.config.ts`）。
 * - 产物目录可参数化：`E2E_OUTPUT_DIR` / `E2E_REPORT_DIR`（L-108：多域串行/并行时 trace 不再互相覆盖）。
 *
 * 运行方式：
 *   npx playwright test --grep @stub          # 桩模式（默认）
 *   E2E_MODE=real npx playwright test --grep @real
 */
const appBase = process.env.E2E_APP ?? `http://127.0.0.1:${process.env.E2E_APP_PORT ?? '18401'}`
const isCI = !!process.env.CI

/**
 * 思考帧展示（`app.ai.reasoning.display-enabled`）本棒显式打开。
 *
 * 后端自 2026-10-27 起默认 `display-enabled=false`（不发 `delta{kind:'reasoning'}`、`/history`
 * 不透 `reasoningContent`），而主栈切片里的 S-15（真模型）/ S-15s（桩 reasoning 形态）验的正是
 * 「显示打开」时的帧→段→块链路 —— 默认关着时 10-srp 会零思考帧必红（run 40675108348 实证）。
 * 只在主栈这一棒打开（域专用 config 各有自己的后端参数）；外部已指定时尊重外部值。
 * 注：`E2E_EXTRA_BACKEND_ARGS` 整棒生效，但只有 10-srp 断言 reasoning（其它 spec 不涉思考面）。
 */
process.env.E2E_EXTRA_BACKEND_ARGS ??= '--app.ai.reasoning.display-enabled=true'

/**
 * 域专用 spec（各自有独立栈 / 桩 / 后端参数，主栈上必红或必 skip）：
 *   12 系 vl = 自带 189xx 栈（`playwright.vl.config.ts`）；13 系 nfezui = 191xx + 本域桩；
 *   14 系 mdxy = mdxy 桩 + KEK；15-mdo = 双 JVM 同 DB；16-kqc 系 = kqc 桩（b/c 还有专用后端参数）；
 *   17b = 缩小超时/预算；11c-gf2 = gf2 桩。
 * 桩模式全部排除；real 模式保留 14b / 16-kqc 的 @real 条（它们不依赖域桩，跑主栈）。
 */
const STUB_MODE_IGNORE = [
  /12[ab]-vl-.*\.spec\.ts/,
  /13[a-z]?-nfezui\.spec\.ts/,
  /14[abc]-mdxy-.*\.spec\.ts/,
  /15-mdo\.spec\.ts/,
  /16-kqc(-[bc])?\.spec\.ts/,
  /17b-unlock-budget\.spec\.ts/,
  /11c-gf2\.spec\.ts/
]
const REAL_MODE_IGNORE = [
  /12[ab]-vl-.*\.spec\.ts/, // 12a/12b 无 @real 条
  /13[a-z]?-nfezui\.spec\.ts/,
  /14a-mdxy-.*\.spec\.ts/, // 14b 的 @real X-07 跑主栈，只排 14a/14c
  /14c-mdxy-.*\.spec\.ts/,
  /15-mdo\.spec\.ts/,
  /16-kqc-[bc]\.spec\.ts/, // 16-kqc.spec.ts 的 @real K-10/C-13 跑主栈
  /17b-unlock-budget\.spec\.ts/,
  /11c-gf2\.spec\.ts/
]

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
    ['html', { outputFolder: process.env.E2E_REPORT_DIR ?? 'test-results/html', open: 'never' }]
  ],
  // L-108：产物目录参数化（多域串行/并行时 trace 不再互相覆盖；默认值保持旧行为）。
  outputDir: process.env.E2E_OUTPUT_DIR ?? 'test-results/artifacts',
  // L-107：模式即选择器 + 域专用 spec 排除（各域由自己的 config / CI 步承载，见文件头）。
  testIgnore: process.env.E2E_MODE === 'real' ? REAL_MODE_IGNORE : STUB_MODE_IGNORE,
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
      // 设备预设自带 viewport 1280×720，会覆盖顶层 use 的同名键（project.use 合并在后）；
      // 故在展开之后显式写回 1440×900，保证实际生效的是顶层口径。
      use: { ...devices['Desktop Chrome'], viewport: { width: 1440, height: 900 } }
    }
  ]
})
