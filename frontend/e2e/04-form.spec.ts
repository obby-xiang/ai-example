/**
 * 批① P0 · G 域（GF 生成式表单）
 * G-07 七型全覆盖渲染（无一型回落成纯文本）
 * G-18 boolean 开关真实坐标可点 + 七型表单完整提交续跑
 */
import { expect, test } from '@playwright/test'
import { openApp, sendMessage, waitRoundSettled } from './support/helpers'

async function openSevenTypeForm(page: import('@playwright/test').Page, tag: string): Promise<void> {
  await openApp(page)
  await sendMessage(page, `gf-seven-types 用例-${tag}`)
  await expect(page.locator('[data-testid="gf-fields"]')).toBeVisible({ timeout: 40_000 })
  // AR-07 高级条件渐进披露（55e6078）：FILTER 卡前 6 张可选字段同屏，长尾（本表的 attachment）
  // 默认折叠 ⇒ 先展开高级区，七型才同屏可断言（判据不变：七型仍是七个真实控件、无一型回落成纯文本）。
  const advanced = page.locator('[data-testid="gf-advanced-toggle"]')
  if ((await advanced.count()) > 0) await advanced.click()
}

test.describe('@stub G 生成式表单', () => {
  test('E2E-G07 七型渲染：text/number/boolean/date/enum/multi_select/attachment 各自可交互', async ({ page }) => {
    await openSevenTypeForm(page, 'G07')

    await expect(page.locator('#gf-field-f_text')).toHaveAttribute('type', 'text')
    await expect(page.locator('#gf-field-f_number')).toHaveAttribute('type', 'number')
    await expect(page.locator('#gf-field-f_boolean')).toHaveAttribute('type', 'checkbox')
    await expect(page.locator('#gf-field-f_date')).toHaveAttribute('type', 'date')
    await expect(page.locator('#gf-field-f_enum')).toBeVisible()
    expect(await page.locator('#gf-field-f_enum').evaluate((el) => el.tagName)).toBe('SELECT')
    await expect(page.locator('#gf-field-f_multi-A')).toHaveAttribute('type', 'checkbox')
    await expect(page.locator('#gf-field-f_multi-B')).toHaveAttribute('type', 'checkbox')
    await expect(page.locator('#gf-field-f_file')).toHaveAttribute('type', 'file')

    // 七个字段逐个可见（无"回落成纯文本"的字段）；multi_select 的 id 在选项级（-A/-B）
    for (const key of ['f_text', 'f_number', 'f_boolean', 'f_date', 'f_enum', 'f_file']) {
      await expect(page.locator(`#gf-field-${key}`)).toBeVisible()
    }
    await expect(page.locator('#gf-field-f_multi-A')).toBeVisible()
    await expect(page.locator('#gf-field-f_multi-B')).toBeVisible()
    await expect(page.locator('[data-testid="gf-submit"]')).toBeVisible()
    await expect(page.locator('[data-testid="gf-cancel"]')).toBeVisible()
  })

  test('E2E-G18 boolean 真实坐标点按 + 七型完整提交：回灌体逐值正确、本轮续跑到落定', async ({ page }) => {
    await openSevenTypeForm(page, 'G18')

    const toggle = page.locator('#gf-field-f_boolean')
    // 展开高级区后表单内层滚动容器可能停在长尾字段处 ⇒ 先滚到 boolean 再取坐标（判据不变：仍是真实鼠标点按）
    await toggle.scrollIntoViewIfNeeded()
    const box = await toggle.boundingBox()
    expect(box).not.toBeNull()
    // 真实鼠标坐标点击（jsdom 的 element.click() 结构上做不到；G-18 的历史根因就在这里）
    await page.mouse.click(box!.x + box!.width / 2, box!.y + box!.height / 2)
    await expect(toggle).toBeChecked()

    await page.locator('#gf-field-f_text').fill('E2E 七型提交')
    await page.locator('#gf-field-f_number').fill('7')
    await page.locator('#gf-field-f_date').fill('2026-10-23')
    await page.locator('#gf-field-f_enum').selectOption('HD')
    await page.locator('#gf-field-f_multi-A').check()
    await page.locator('#gf-field-f_multi-B').check()

    // 附件是七型之一：走两段式上传（文件本体 POST，表单值只带 fileId）
    const png = Buffer.from(
      'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==',
      'base64'
    )
    await page.locator('#gf-field-f_file').setInputFiles({ name: 'e2e-seven.png', mimeType: 'image/png', buffer: png })
    await expect(page.locator('[data-testid="gf-fields"]')).toContainText('e2e-seven.png', { timeout: 20_000 })

    const submitted = page.waitForRequest(
      (req) => req.url().includes('/api/ai/frontend-tool-result') && req.method() === 'POST',
      { timeout: 20_000 }
    )
    await page.locator('[data-testid="gf-submit"]').click()
    const request = await submitted
    const body = request.postDataJSON() as { result?: string; cancelled?: boolean }
    expect(body.cancelled).toBeFalsy()
    const values = JSON.parse(body.result ?? '{}') as Record<string, unknown>
    expect(values.f_text).toBe('E2E 七型提交')
    expect(values.f_number).toBe(7)
    expect(values.f_boolean).toBe(true)
    expect(values.f_date).toBe('2026-10-23')
    expect(values.f_enum).toBe('HD')
    expect(values.f_multi).toEqual(['A', 'B'])
    // 附件值只带 fileId（内容不出浏览器）
    expect(String(values.f_file)).toMatch(/^att_[A-Za-z0-9_-]{8,64}$/)

    // 回灌后本轮续跑到落定：表单退出可编辑态、无卡级错误、无停止钮
    await waitRoundSettled(page, 60_000)
    await expect(page.locator('[data-testid="gf-submit"]')).toHaveCount(0)
    await expect(page.locator('[data-testid="gf-error"]')).toHaveCount(0)
  })
})
