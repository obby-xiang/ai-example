/**
 * 批③c 真机 E2E · K-06 / C-02（**需要专用后端参数**的两个降级面用例）
 *
 * 为什么单独一份：这两条要把后端起在「知识索引不可用」与「上下文分母未知」的形态下，
 * 而 harness 一棒只起一套服务（`E2E_EXTRA_BACKEND_ARGS` 对本棒全部用例生效）。
 * 故文件级闸门：只有显式带 `E2E_KQC_SPECIAL=1` 才跑，正常套跑（`npx playwright test e2e`）自动跳过。
 *
 * 运行（专用一棒，跑完清理端口）：
 *   cd frontend
 *   E2E_KQC_SPECIAL=1 \
 *   E2E_EXTRA_BACKEND_ARGS="--app.ai.kb.dir=<仓库根>/scripts --app.ai.context.limit-tokens=0" \
 *   E2E_BACKEND_PORT=19730 E2E_DEGRADED_BACKEND_PORT=19731 E2E_STUB_PORT=19799 \
 *   E2E_APP_PORT=19801 E2E_DEGRADED_APP_PORT=19802 E2E_REDIS_DB=14 E2E_REDIS_DB_DEGRADED=15 \
 *   E2E_RUN_DIR=test-results/.e2e-run-kqc-special \
 *   E2E_STUB_SCRIPT="<仓库根>/frontend/e2e/support/kqc-upstream.mjs" \
 *   npx playwright test e2e/16-kqc-b.spec.ts
 *
 * `--app.ai.kb.dir=<仓库根>/scripts`：指向一个**存在但不是知识仓**（无 manifest.json）的目录 ⇒
 *   首启播种被跳过、索引建不成 ⇒ `ready=false` + `last_error` 非空（K-06 的前置）。
 * `--app.ai.context.limit-tokens=0`：分母未知的降级态 ⇒ 端点 `limitTokens:null`、不显百分比（C-02 的前置）。
 */
import { expect, test } from '@playwright/test'
import { openApp, sendMessage, sessionId, waitRoundSettled } from './support/helpers'
import {
  aiHealth,
  apiData,
  httpRaw,
  chatRound,
  contextSnapshot,
  fingerprint,
  kbStatus,
  lastToolText,
  maxStubIndex,
  newSessionId,
  parsePillTitle,
  systemTextOf,
  unwrapKnowledge,
  waitStubRequestsAfter
} from './support/helpers-kqc'

test.skip(
  process.env.E2E_KQC_SPECIAL !== '1',
  'K-06 / C-02 需要专用后端参数（kb.dir 不可用 + limit-tokens=0），见文件头运行说明'
)

test('@stub E2E-K06 索引未就绪文案分流：status 如实 available=false、工具回执给可行动文案、HTTP 非 500', async () => {
  // ① 状态面如实：ready=false 且带原因（不是"加载中请稍后"的含糊文案）
  const status = await kbStatus()
  expect(status.enabled).toBe(true)
  expect(status.ready).toBe(false)
  expect(String(status.last_error ?? '').length).toBeGreaterThan(0)
  expect(status.doc_count).toBe(0)

  // ② 工具面：检索类请求不抛异常、不 5xx，回执里带"为什么"与两条可行动修复路径
  const sid = await newSessionId('K06')
  const before = maxStubIndex()
  const round = await chatRound(sid, `知识问答用例 ${fingerprint('K06')} tool=kb_search&args={"query":"费率"}`)
  expect(round.status).toBe(200)
  expect(round.errors).toHaveLength(0)
  expect(round.done).toBeTruthy()
  const requests = await waitStubRequestsAfter(before, 2)
  const text = unwrapKnowledge(lastToolText(requests[1]))
  expect(text).toContain('知识库索引未建成')
  expect(text).toContain('app.ai.kb.dir')
  expect(text).toContain('POST /api/ai/kb/reload')

  // ③ 提示词面：空目录不注入假目录，改为"不可用，别用通用知识冒充官方口径"
  const system = systemTextOf(requests[1])
  expect(system).toContain('知识库当前不可用')
  expect(system).not.toContain('<knowledge_catalog>')
})

test('@stub E2E-C02 分母未知不显百分比：圆环不确定态（无 value）只留轨道、tooltip 只说已用、端点 limitTokens/percent 为 null、不崩', async ({ page }) => {
  // 分母未知的合法入口：allowlist 里加一个**没有声明 max_context** 的模型并设为默认。
  // 此时 ContextLimitResolver 回落到过渡键（本棒用 E2E_EXTRA_BACKEND_ARGS 置 0）⇒ limitTokens = 未知。
  const providers = await apiData<Array<{ id: number; code: string; enabled: boolean }>>('/api/ai/providers')
  const provider = providers.find((item) => item.enabled) ?? providers[0]
  const created = await httpRaw('POST', `/api/ai/providers/${provider.id}/models`, {
    modelId: 'kqc-nolimit',
    displayName: 'KQC 无上限模型',
    maxContext: null,
    maxOutput: null,
    supportsToolCalling: true,
    enabled: true,
    isDefault: true,
    sortOrder: 0
  })
  expect([200, 201]).toContain(created.status)

  // 配置快照切换需要一拍：轮询 /health 直到分母真的变成未知
  const deadline = Date.now() + 30_000
  let health = await aiHealth()
  while (Date.now() < deadline && health.contextLimitTokens !== null) {
    await new Promise((done) => setTimeout(done, 400))
    health = await aiHealth()
  }
  expect(health.model).toBe('kqc-nolimit')
  expect(health.contextLimitTokens).toBeNull()
  expect(health.compactionOn).toBe(false)

  await openApp(page)
  await sendMessage(page, `上下文用例 ${fingerprint('C02')} usage-shape=last`)
  await waitRoundSettled(page)

  const pill = page.locator('[data-testid="context-pill"]')
  await expect(pill).toBeVisible()
  // 默认态零可见文字：读数只在 hover 的那一行里（title 与 aria-label 同源）
  await expect(pill).toHaveText('')
  const view = parsePillTitle(await pill.getAttribute('title'))
  expect(view.raw).not.toContain('%')
  expect(view.percent).toBeNull()
  expect((view.used as number)).toBeGreaterThan(0)
  expect(view.raw).toContain('已用')
  await expect(pill).toHaveAttribute('aria-label', view.raw)
  // 没有分母就没有占比：不写 value（原生不确定态，视觉只剩轨道），绝不拿 0 冒充
  await expect(pill).not.toHaveAttribute('value')
  await expect(pill).toHaveAttribute('data-tier', 'none')

  const sid = await sessionId(page)
  const snapshot = await contextSnapshot(sid)
  expect(snapshot.limitTokens).toBeNull()
  expect(snapshot.percent).toBeNull()
  expect(snapshot.source).toBe('stream')
  expect(Math.abs((view.used as number) - snapshot.usedTokens)).toBeLessThan(100)

  // 点击不展开详情：上限未知也不给分项，界面上不再有"上限未知"面板
  await pill.click()
  await expect(page.locator('[data-testid="context-popover"]')).toHaveCount(0)
  await expect(page.locator('[data-testid^="context-row-"]')).toHaveCount(0)
})
