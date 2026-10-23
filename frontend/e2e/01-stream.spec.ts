/**
 * 批① P0 · S 域（流式稳定）
 * S-01 纯文本流逐字全文（真 SSE + 真渲染）
 * S-06 上游中途断流（桩 kill-stream）：已收正文保留 + 可继续发下一条
 */
import { expect, test } from '@playwright/test'
import {
  TURN_TEXT,
  assistantBubble,
  assistantText,
  openApp,
  sendMessage,
  waitRoundSettled
} from './support/helpers'

test.describe('@stub S 流式稳定', () => {
  test('E2E-S01 纯文本流：正文逐字等于桩的确定性文本，且流中确有增量', async ({ page }) => {
    await openApp(page)
    await sendMessage(page, 'turn-text stall-frames=250')

    // 流中：正文已在增长，打字机光标在位（真 SSE 的中间态，jsdom 结构上不可得）
    await expect(assistantBubble(page)).toBeVisible()
    await expect(page.locator('[data-testid="stream-caret"]')).toBeVisible({ timeout: 15_000 })
    const partial = await assistantText(page)
    expect(partial.length).toBeGreaterThan(0)
    expect(TURN_TEXT.startsWith(partial) || partial.length < TURN_TEXT.length).toBeTruthy()

    // 终态：整段正文逐字相等（不是"包含"）
    await waitRoundSettled(page)
    await expect(assistantBubble(page)).toHaveText(TURN_TEXT)

    // 闲聊判据：本轮零工具卡
    await expect(page.locator('[data-testid^="tool-card-"]')).toHaveCount(0)
  })

  test('E2E-S06 上游中途断流：已收正文保留、错误提示带码、可继续发下一条', async ({ page }) => {
    await openApp(page)
    await sendMessage(page, 'turn-text stall-frames=600 kill-stream=1800')

    // 断流前已收到正文（证明不是"一帧都没到"）
    await expect(assistantBubble(page)).toBeVisible({ timeout: 15_000 })
    await expect
      .poll(async () => (await assistantText(page)).length, { timeout: 20_000 })
      .toBeGreaterThan(10)

    // 桩的上游 TCP 被销毁 ⇒ 产品以「本轮出错 + 机器码」收敛，并给「继续」入口
    await expect(page.locator('[data-testid="notice-bar"]')).toContainText('UPSTREAM_STREAM_ERROR_AFTER_PARTIAL', {
      timeout: 40_000
    })
    await waitRoundSettled(page, 60_000)
    await expect(page.locator('[data-testid="continue-round"]')).toBeVisible()

    // 已收正文不因断流被清空
    expect((await assistantText(page)).length).toBeGreaterThan(10)

    // 页面仍可继续（出错后草稿回填并锚定该条用户消息 ⇒ 再发是从该条重跑）：新一轮真流式跑通
    await sendMessage(page, 'turn-text')
    await expect(assistantBubble(page)).toHaveText(TURN_TEXT, { timeout: 40_000 })
    await waitRoundSettled(page)
    await expect(page.locator('[data-testid="notice-bar"]')).toHaveCount(0)
  })
})
