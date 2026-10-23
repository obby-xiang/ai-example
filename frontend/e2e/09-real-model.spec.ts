/**
 * 真模型冒烟（C 级：只断形状 + 概率下限，不逐字比对模型输出）。
 *
 * 运行：E2E_MODE=real AI_API_KEY=*** AI_BASE_URL=... AI_MODEL=... npx playwright test
 * 采样：E2E_REAL_SAMPLING（默认 10，与设计稿 §0.3 的常规行为口径一致；本地验证可设 2）。
 *
 * 本批只接 2 条，证明「真 key → 真后端 → 真浏览器」链路通；其余真模型用例按 §5 分批铺开。
 */
import { expect, test } from '@playwright/test'
import { openApp, sendMessage, waitRoundSettled } from './support/helpers'

const SAMPLING = Math.max(1, Number(process.env.E2E_REAL_SAMPLING ?? '10'))
const THRESHOLD = Math.ceil(SAMPLING * 0.7)

test.describe('@real 真模型冒烟', () => {
  test('E2E-RM01 真模型纯文本轮：非空答复、终态收敛、零错误提示', async ({ page }) => {
    test.setTimeout(150_000)
    await openApp(page)
    await sendMessage(page, '请用一句话（不超过 30 字）介绍你自己。不要调用任何工具。')

    await expect
      .poll(async () => (await page.locator('[data-testid^="msg-bubble-"].w-full').last().innerText()).trim().length, {
        timeout: 120_000
      })
      .toBeGreaterThan(3)
    await waitRoundSettled(page, 120_000)
    await expect(page.locator('[data-testid^="error-card-"]')).toHaveCount(0)
    await expect(page.locator('[data-testid="alert-interrupted"]')).toHaveCount(0)
  })

  test(`E2E-RM02 真模型工具轮：${SAMPLING} 次采样中 ≥${THRESHOLD} 次发起工具调用并收敛`, async ({ page }) => {
    test.setTimeout(600_000)
    let success = 0
    const seen: string[] = []
    for (let i = 1; i <= SAMPLING; i += 1) {
      await openApp(page)
      await sendMessage(page, `列出当前系统里的任务（第 ${i} 次）。请直接调用合适的工具，不要向我确认。`)
      try {
        await expect(page.locator('[data-testid^="tool-card-"]').first()).toBeVisible({ timeout: 60_000 })
        success += 1
        seen.push((await page.locator('[data-testid^="tool-card-"]').first().innerText()).split('\n')[0] ?? '')
      } catch {
        // 未触发工具：按 C 级采样口径计为一次未命中（不用于逐字判断模型输出）
      }
      await waitRoundSettled(page, 90_000)
      await page.locator('[data-testid="new-chat"]').click()
      await expect(page.locator('[data-testid="empty-state"]')).toBeVisible({ timeout: 20_000 })
    }
    // 形状断言：命中轮里工具卡标题非空；概率下限：≥ 70%
    // eslint-disable-next-line no-console
    console.log(`[RM-02] N=${SAMPLING} 工具轮命中=${success} 阈值≥${THRESHOLD}`)
    expect(success).toBeGreaterThanOrEqual(THRESHOLD)
    expect(seen.filter((title) => title.trim() !== '')).toHaveLength(success)
  })
})
