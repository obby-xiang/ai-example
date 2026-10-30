/**
 * 2026-10-29 审计批（M-15 / M-16 / M-17 / M-19 / M-20）真机 E2E。
 *
 * <h2>口径</h2>
 * - 全部 `@stub`（走 `e2e/support/global-setup.ts` 起的三件套：桩上游 + 独立后端 + 前端 dev server），
 *   端口与产物目录沿用主 config（18399 / 18330 / 18401）；
 * - 失败面用 `page.route` 造（探针失败 / `/chat` 500 / `/history` 慢），不依赖桩上游的额外指令；
 * - 断言只看用户可见结果 + 后端真状态（`/runs/current`、`/runs/{id}`），不看内部实现细节。
 *
 * <h2>覆盖与缺口</h2>
 * - M-18（建卡失败 + 回灌失败收口 `suspended`）没有真机条：需要后端发一张**坏 schema** 的
 *   `generative_form` 帧，现桩不产这种帧；该行为由单测 `tests/ai-state-guards-20261029.spec.ts` 锁住。
 * - M-20 的"其它卡片"触发面在真机上用「手填加一条模型 → 删掉」实现（都得走 `load()`），
 *   用例末尾自行清理，不留脏数据。
 *
 * <h2>执行</h2>
 * `npx playwright test e2e/20-state-settings.spec.ts --grep @stub`
 * （本批提交环境无真机三件套，**未执行**；未执行的结论只作"用例已就位"的证据。）
 */
import { expect, test, type Page } from '@playwright/test'
import {
  assistantBubble,
  assistantText,
  openApp,
  openPath,
  runCurrent,
  runStatus,
  sendMessage,
  sessionId,
  until,
  waitRoundSettled
} from './support/helpers'

const chatPath = '**/api/ai/chat'
const historyPath = '**/api/ai/history/**'
const healthPath = '**/api/ai/health'

function acceptDialogs(page: Page): void {
  page.on('dialog', (dialog) => {
    void dialog.accept()
  })
}

function assistantBubbles(page: Page) {
  return page.locator('[data-testid^="msg-bubble-"].w-full')
}

test.describe('@stub 2026-10-29 状态与设置守卫', () => {
  test('E2E-M15 探针失败不锁输入：健康检查 502 后仍可发送', async ({ page }) => {
    let failed = false
    await page.route(healthPath, async (route) => {
      if (!failed) {
        failed = true
        await route.fulfill({
          status: 502,
          contentType: 'application/json',
          body: JSON.stringify({ success: false, code: 'NETWORK_ERROR', message: 'probe down' })
        })
        return
      }
      await route.continue()
    })

    await openApp(page)
    // 探针失败只是提示（状态胶囊 + 降级条），发送钮不得因此禁用
    await page.locator('[data-testid="composer-input"]').fill('探针失败也要能发')
    await expect(page.locator('[data-testid="send-btn"]')).toBeEnabled({ timeout: 10_000 })
    await page.locator('[data-testid="send-btn"]').click()

    await expect(assistantBubble(page)).toBeVisible({ timeout: 20_000 })
    await waitRoundSettled(page, 60_000)
  })

  test('E2E-M16 编辑 = 只回填正文（2026-10-30 直裁）：不发送、不删除、不重跑', async ({ page }) => {
    await openApp(page)
    await sendMessage(page, 'turn-text')
    await waitRoundSettled(page)
    const reply = await assistantText(page)
    expect(reply.length).toBeGreaterThan(10)

    let chatCalls = 0
    await page.route(chatPath, (route) => {
      chatCalls += 1
      void route.continue()
    })
    const bubblesBefore = await page.locator('[data-testid^="msg-bubble-"]').count()

    // 编辑本轮提问：只回填（输入框为空 ⇒ 覆盖保护不弹确认），不再有「删除 N 条」确认
    await page.locator('[data-testid^="edit-msg-"]').first().click()

    await expect(page.locator('[data-testid="composer-input"]')).toHaveValue(/\S/, { timeout: 10_000 })
    // 消息一条不少、原回答原样在屏上
    expect(await page.locator('[data-testid^="msg-bubble-"]').count()).toBe(bubblesBefore)
    await expect(assistantBubble(page)).toHaveText(reply, { timeout: 10_000 })
    // 不发送、不重跑：编辑本身一个请求都不发
    expect(chatCalls).toBe(0)
  })

  test('E2E-M17 历史重载在途时开新轮：不整体替换在途消息（同一轮不出两条气泡）', async ({ page }) => {
    await openApp(page)
    // 刷新时把 /history 压住 4s：这段时间里立刻发一条新一轮
    await page.route(historyPath, async (route) => {
      await new Promise((resolve) => setTimeout(resolve, 4_000))
      await route.continue()
    })
    await page.reload({ waitUntil: 'domcontentloaded' })

    const composer = page.locator('[data-testid="composer-input"]')
    await expect(composer).toBeVisible({ timeout: 30_000 })
    await composer.fill('turn-text stall-frames=600 历史在途')
    await page.locator('[data-testid="send-btn"]').click()

    await expect(assistantBubbles(page)).toHaveCount(1, { timeout: 30_000 })
    await waitRoundSettled(page, 60_000)
    // 历史响应落地之后，本轮仍是同一条助手气泡（没有被历史顶掉后再建一条）
    await expect(assistantBubbles(page)).toHaveCount(1, { timeout: 20_000 })
    await expect(page.locator('[data-testid="alert-interrupted"]')).toHaveCount(0)
  })

  test('E2E-M19 start 帧之前按停止：后端这一轮最终被取消（不留在跑）', async ({ page, request }) => {
    await openApp(page)
    const sid = await sessionId(page)
    // 帧间隔拉到 3s：点停止时 start 帧很可能还没到（runId 未知）—— 正是本修的场景
    await sendMessage(page, 'turn-text stall-frames=3000 M19')
    const stop = page.locator('[data-testid="stop-btn"]')
    await expect(stop).toBeVisible({ timeout: 10_000 })
    const before = await runCurrent(request, sid)
    await stop.click()

    // 本地立即收敛（发送钮回来）
    await expect(page.locator('[data-testid="send-btn"]')).toBeVisible({ timeout: 15_000 })
    // 后端同样收敛：要么这一轮已不存在，要么它是 CANCELLED
    const runId = String(before.runId ?? '')
    await until(async () => {
      const current = await runCurrent(request, sid)
      if (!current.found) {
        return true
      }
      if (runId === '') {
        return false
      }
      return (await runStatus(request, runId)) === 'CANCELLED'
    }, 30_000, 'run cancelled after pre-start stop')
  })

  test('E2E-M20 设置页重载保留其它卡片的脏密钥与改名', async ({ page }) => {
    acceptDialogs(page)
    await openPath(page, '/settings/ai')
    await expect(page.locator('[data-testid="ai-settings-view"]')).toBeVisible({ timeout: 30_000 })
    const cards = page.locator('[data-testid="provider-card"]')
    await expect(cards.first()).toBeVisible({ timeout: 30_000 })

    const keyInput = cards.first().locator('[data-testid="key-input"]')
    const nameInput = cards.first().locator('[data-testid="provider-display-name"]')
    const originalName = await nameInput.inputValue()
    await keyInput.fill('sk-e2e-dirty-key')
    await nameInput.fill(`${originalName}-e2e`)

    // 同页触发一次 load()：手填加一条模型（随后删掉，不留脏数据）
    const modelId = `e2e-probe-${Date.now()}`
    await page.locator('[data-testid="manual-model-id"]').fill(modelId)
    await page.locator('[data-testid="manual-model-submit"]').click()
    await expect
      .poll(async () => cards.first().locator('[data-testid="model-row"]').count(), { timeout: 30_000 })
      .toBeGreaterThan(0)

    // 重载之后脏密钥与改名都还在
    await expect(keyInput).toHaveValue('sk-e2e-dirty-key', { timeout: 10_000 })
    await expect(nameInput).toHaveValue(`${originalName}-e2e`, { timeout: 10_000 })

    // 保存真的把 apiKey 交出去（拦截请求体取证）
    const saveRequest = page.waitForRequest((req) =>
      req.url().includes('/api/ai/providers/') && req.method() === 'PUT'
    )
    await cards.first().locator('[data-testid="save-provider"]').click()
    const body = (await saveRequest).postDataJSON() as { apiKey?: string; displayName?: string }
    expect(body.apiKey).toBe('sk-e2e-dirty-key')
    expect(body.displayName).toBe(`${originalName}-e2e`)

    // 清理：删掉刚加的模型行（避免污染后续用例）
    const row = page.locator('[data-testid="model-row"]').filter({ hasText: modelId })
    if (await row.count() > 0) {
      await row.first().locator('[data-testid="remove-model-row"]').click()
      await expect(row).toHaveCount(0, { timeout: 20_000 })
    }
  })
})
