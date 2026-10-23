/**
 * 批① P0 · R 域（真刷新）+ P 域（真按钮停止）
 * R-01 回复中刷新续显；R-10 已完成轮刷新零横幅；R-12 刷新后在途轮停止入口
 * P-01 流式中停止；P-02 停止后刷新正文不丢
 */
import { expect, test, type Page } from '@playwright/test'
import {
  TURN_TEXT,
  assistantBubble,
  assistantText,
  openApp,
  runCurrent,
  runStatus,
  sendMessage,
  sessionId,
  until,
  waitRoundSettled
} from './support/helpers'

async function startSlowRound(page: Page): Promise<void> {
  await openApp(page)
  await sendMessage(page, 'turn-text stall-frames=2500')
  await expect(assistantBubble(page)).toBeVisible({ timeout: 15_000 })
  await expect.poll(async () => (await assistantText(page)).length, { timeout: 20_000 }).toBeGreaterThan(10)
}

test.describe('@stub R 刷新恢复 / P 手工停止', () => {
  test('E2E-R01 回复中刷新：仍在途的助手气泡续显并收尾到全文', async ({ page }) => {
    await startSlowRound(page)
    await page.reload({ waitUntil: 'domcontentloaded' })
    await expect(page.locator('[data-testid="composer-input"]')).toBeVisible({ timeout: 30_000 })

    // 刷新后不得"只剩用户气泡"：助手气泡 30s 内带着正文回来
    await expect
      .poll(async () => (await assistantText(page)).length, { timeout: 30_000 })
      .toBeGreaterThan(10)
    await expect(assistantBubble(page)).toHaveText(TURN_TEXT, { timeout: 60_000 })
    await waitRoundSettled(page, 60_000)

    // 真断流横幅不出现（在途续收不是断流）
    await expect(page.locator('[data-testid="alert-interrupted"]')).toHaveCount(0)
  })

  test('E2E-R10 已完成轮刷新：完整恢复且零横幅零提示（防过度修复）', async ({ page }) => {
    await openApp(page)
    await sendMessage(page, 'turn-text')
    await waitRoundSettled(page)
    await expect(assistantBubble(page)).toHaveText(TURN_TEXT)

    await page.reload({ waitUntil: 'domcontentloaded' })
    await expect(assistantBubble(page)).toHaveText(TURN_TEXT, { timeout: 30_000 })
    await expect(page.locator('[data-testid^="msg-bubble-"]')).toHaveCount(2)
    await expect(page.locator('[data-testid="alert-interrupted"]')).toHaveCount(0)
    await expect(page.locator('[data-testid="stop-btn"]')).toHaveCount(0)
  })

  test('E2E-R12 刷新后在途轮：停止入口可点，后端真收敛为 CANCELLED', async ({ page, request }) => {
    await startSlowRound(page)
    await page.reload({ waitUntil: 'domcontentloaded' })
    await expect
      .poll(async () => (await assistantText(page)).length, { timeout: 30_000 })
      .toBeGreaterThan(10)

    const sid = await sessionId(page)
    const current = await runCurrent(request, sid)
    expect(current.found).toBe(true)
    const runId = String(current.runId ?? '')
    expect(runId.length).toBeGreaterThan(0)

    const stop = page.locator('[data-testid="stop-btn"]')
    await expect(stop).toBeVisible({ timeout: 15_000 })
    await expect(stop).toBeEnabled()
    await stop.click()

    await until(async () => (await runStatus(request, runId)) === 'CANCELLED', 15_000, 'run CANCELLED')
    await expect(page.locator('[data-testid="send-btn"]')).toBeVisible({ timeout: 10_000 })
  })

  test('E2E-P01 流式中点停止：正文保留 + 「已停止」chip + 输入解禁 + 无断流红条', async ({ page }) => {
    await startSlowRound(page)
    const before = (await assistantText(page)).length

    await page.locator('[data-testid="stop-btn"]').click()
    await expect(page.locator('[data-testid^="msg-stopped-"]')).toHaveCount(1, { timeout: 15_000 })
    await waitRoundSettled(page, 20_000)

    // 正文不回滚、不缩水
    expect((await assistantText(page)).length).toBeGreaterThanOrEqual(before)
    // 主动停止 ≠ 断流
    await expect(page.locator('[data-testid="alert-interrupted"]')).toHaveCount(0)
    // 「继续」与「已停止」互斥
    await expect(page.locator('[data-testid="continue-round"]')).toHaveCount(0)
    // 输入解禁
    await expect(page.locator('[data-testid="send-btn"]')).toBeVisible({ timeout: 10_000 })
  })

  test('E2E-P02 停止后真刷新：半截正文不丢', async ({ page }) => {
    await startSlowRound(page)
    const before = (await assistantText(page)).length
    await page.locator('[data-testid="stop-btn"]').click()
    await waitRoundSettled(page, 20_000)

    await page.reload({ waitUntil: 'domcontentloaded' })
    await expect
      .poll(async () => (await assistantText(page)).length, { timeout: 30_000 })
      .toBeGreaterThanOrEqual(before)
    await expect(page.locator('[data-testid="alert-interrupted"]')).toHaveCount(0)
    await expect(page.locator('[data-testid="send-btn"]')).toBeVisible({ timeout: 10_000 })
  })
})
