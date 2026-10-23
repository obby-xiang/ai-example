/**
 * 批① P0 · E 域（空态与错误面）+ D 域（降级）
 * E-01 空态总览（空态 + 建议 chips + 新建对话置灰 + 输入可用）
 * D-02/E-07 无 key 降级：503 + 三档码之一 + alert-degraded + 发送禁用
 * E-03 错误卡（context 变体）：越窗 400 收敛为「上下文长度超限」卡（含行动按钮）
 * （断流红条的负向条件——用户主动停止不误闪——由 E2E-P01 / R-01 承接）
 */
import { expect, test } from '@playwright/test'
import { DEGRADED_API, DEGRADED_APP, openApp, sendMessage, waitRoundSettled } from './support/helpers'

test.describe('@stub E 空态与错误面 / D 降级', () => {
  test('E2E-E01 空态总览：空态可见、建议 chips 可见、新建对话置灰、输入框可用', async ({ page }) => {
    await openApp(page)
    await expect(page.locator('[data-testid="empty-state"]')).toBeVisible()
    await expect(page.locator('[data-testid="suggestion-chip"]').first()).toBeVisible()
    await expect(page.locator('[data-testid="new-chat"]')).toBeDisabled()
    await expect(page.locator('[data-testid="history-skeleton"]')).toHaveCount(0)
    await expect(page.locator('[data-testid="composer-input"]')).toBeEnabled()
  })

  test('E2E-D02 无 key 降级：/health 503 + 三档不可用码之一 + 横幅出现 + 发送禁用', async ({ page, request }) => {
    const health = await request.get(`${DEGRADED_API}/api/ai/health`)
    expect(health.status()).toBe(503)
    const body = (await health.json()) as { available?: boolean; code?: string }
    expect(body.available).toBe(false)
    expect(body.code ?? '').toMatch(/^AI_(UNAVAILABLE|KEY_MISSING|KEY_UNREADABLE)$/)

    await page.goto(`${DEGRADED_APP}/`, { waitUntil: 'domcontentloaded' })
    await expect(page.locator('[data-testid="alert-degraded"]')).toBeVisible({ timeout: 30_000 })
    await expect(page.locator('[data-testid="composer-input"]')).toBeVisible()
    await expect(page.locator('[data-testid="send-btn"]')).toBeDisabled()
  })

  test('E2E-E03 错误卡（context 变体）：越窗错误收敛为「上下文长度超限」+ 行动按钮', async ({ page }) => {
    await openApp(page)
    await sendMessage(page, 'context-overflow 用例-E03')

    const card = page.locator('[data-testid^="error-card-"][data-variant]').last()
    await expect(card).toBeVisible({ timeout: 40_000 })
    await expect(card).toContainText('上下文长度超限')
    await expect(card).toContainText('开新会话')
    await expect(page.locator('[data-testid^="error-card-action-"]').last()).toBeVisible()
    await waitRoundSettled(page, 30_000)
    // 错误卡不是断流红条：上下文越限有专属卡，不混用「结果可能不完整」
    await expect(page.locator('[data-testid="alert-interrupted"]')).toHaveCount(0)
  })
})
