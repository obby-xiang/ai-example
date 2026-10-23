/**
 * 批① P0 · T 域（表格工具）
 * T-06 写预览三档可切 + 预览高亮图例出现（预览期零写）
 * T-14 预览应用 → 已应用摘要（真实 SpreadJS 适配器）
 */
import { expect, test, type Page } from '@playwright/test'
import { createSheetImportTask, openPath, sendMessage, waitRoundSettled } from './support/helpers'

async function openUploadedSheet(page: Page, taskId: number): Promise<void> {
  await openPath(page, `/import?taskId=${taskId}`)
  await expect(page.locator('footer')).toContainText('task:IMPORT/UPLOAD', { timeout: 30_000 })
  // SpreadJS 与结果标签页按需加载：等到结果标签页与画布都在位（标签文案是定义中文名，不以编码为锚）
  await expect(page.locator('.el-tabs__item').first()).toBeVisible({ timeout: 60_000 })
  await expect(page.locator('.el-tab-pane canvas').first()).toBeVisible({ timeout: 60_000 })
}

test.describe('@stub T 表格工具', () => {
  test('E2E-T06 写预览：三档可切、格子高亮图例出现、预览期不落写', async ({ page, request }) => {
    const taskId = await createSheetImportTask(request)
    await openUploadedSheet(page, taskId)
    await sendMessage(page, 'scenario=SHEET-UPDATE 用例-T06')

    const card = page.locator('[data-testid="sheet-preview-card"]')
    await expect(card).toBeVisible({ timeout: 40_000 })
    await expect(card.locator('[data-testid="sheet-preview-levels"] button')).toHaveCount(3)
    await expect(card.locator('[data-testid="sheet-preview-count"]')).toBeVisible()

    // 切到全量 diff：屏幕标色图例随之出现（表里待改格已临时着色，只影响屏幕）
    await card.locator('[data-testid="sheet-preview-level-full-diff"]').click()
    await expect(card.locator('[data-testid="sheet-preview-full"]')).toBeVisible()
    await expect(card.locator('[data-testid="sheet-preview-highlight-legend"]')).toBeVisible()

    await card.locator('[data-testid="sheet-preview-level-sample-diff"]').click()
    await expect(card.locator('[data-testid="sheet-preview-sample"]')).toBeVisible()

    // 预览期还没有 applied 摘要（应用是显式一步）
    await expect(page.locator('[data-testid="sheet-applied-summary"]')).toHaveCount(0)
    // 收口：取消预览释放挂起轮
    await card.locator('[data-testid="sheet-preview-cancel"]').click()
    await waitRoundSettled(page, 30_000)
  })

  test('E2E-T14 预览应用：真实写回适配器（USD decimalPlaces 2→3）', async ({ page, request }) => {
    const taskId = await createSheetImportTask(request)
    await openUploadedSheet(page, taskId)
    await sendMessage(page, 'scenario=SHEET-UPDATE 用例-T14')

    const card = page.locator('[data-testid="sheet-preview-card"]')
    await expect(card).toBeVisible({ timeout: 40_000 })
    await card.locator('[data-testid="sheet-preview-apply"]').click()

    // 应用后预览卡收口
    await expect(page.locator('[data-testid="sheet-preview-card"]')).toHaveCount(0, { timeout: 30_000 })
    await waitRoundSettled(page, 40_000)

    // 真实写回证据：用同一页面的表格适配器回读 USD 行的 decimalPlaces（预览前 2 → 应用后 3）
    const readBack = await page.evaluate(async () => {
      const ft = await import('/src/utils/frontend-tools.ts') as unknown as {
        resolveSheetContext: (defCode: string) => Promise<
          | { ok: false; reason: string }
          | { ok: true; ctx: { adapter: { rowCount: () => number; getCell: (row: number, col: number) => unknown } } }
        >
      }
      const resolved = await ft.resolveSheetContext('CURRENCY')
      if (!resolved.ok) return { ok: false as const, reason: resolved.reason }
      const { adapter } = resolved.ctx
      for (let row = 0; row < adapter.rowCount(); row += 1) {
        if (adapter.getCell(row, 0) === 'USD') {
          return { ok: true as const, value: adapter.getCell(row, 3) }
        }
      }
      return { ok: false as const, reason: 'USD 行不存在' }
    })
    expect(readBack).toEqual({ ok: true, value: 3 })
  })
})
