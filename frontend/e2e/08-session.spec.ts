/**
 * 批① P0 · N 域（会话管理）+ F 域（消息操作）+ Q 域（空态 chips）
 * N-01 新建对话：清屏 + 按钮置灰 + 会话 id 换新 + 旧镜像删除
 * F-05 复制消息：剪贴板文本与正文逐字符相等
 * Q-01 空态 chips 点即发（不回填输入框）
 */
import { expect, test } from '@playwright/test'
import { TURN_TEXT, api, openApp, sendMessage, sessionId, waitRoundSettled } from './support/helpers'

test.describe('@stub N 会话 / F 消息操作 / Q 快捷操作', () => {
  test.use({ permissions: ['clipboard-read', 'clipboard-write'] })

  test('E2E-N01 新建对话：清屏、按钮置灰、会话 id 换新、旧镜像键删除', async ({ page, request }) => {
    await openApp(page)
    await sendMessage(page, 'turn-text 用例-N01')
    await waitRoundSettled(page)
    await expect(page.locator('[data-testid="new-chat"]')).toBeEnabled()

    const before = await sessionId(page)
    const mirrorBefore = await page.evaluate((sid) => sessionStorage.getItem(`ai-mirror:${sid}`) !== null, before)
    expect(mirrorBefore).toBe(true)

    // 销毁是**异步**的（`resetSession` 点击即清屏/换 id，`DELETE /api/ai/session/{old}` 在其后发出并等待）；
    // 镜像键在换 id 的同一帧就被删掉，所以"旧镜像为 null"只证明换 id 成功，**不证明**后端已销毁。
    // 因此先挂上"等产品自己那条 DELETE 的响应"，把读 /history 的时刻钉在销毁完成之后 ——
    // 响应返回时记忆面已删完（实测：早读会读到销毁前那一窗 2 条 = [user, assistant]）。
    const purgeResponse = page.waitForResponse(
      (res) => res.request().method() === 'DELETE' && res.url().includes(`/api/ai/session/${before}`),
      { timeout: 30_000 }
    )

    await page.locator('[data-testid="new-chat"]').click()
    await expect(page.locator('[data-testid="empty-state"]')).toBeVisible()
    await expect(page.locator('[data-testid="new-chat"]')).toBeDisabled()

    const after = await sessionId(page)
    expect(after).not.toBe(before)
    const state = await page.evaluate(({ oldSid, newSid }) => ({
      oldMirror: sessionStorage.getItem(`ai-mirror:${oldSid}`),
      newMirror: sessionStorage.getItem(`ai-mirror:${newSid}`)
    }), { oldSid: before, newSid: after })
    expect(state.oldMirror).toBeNull()
    // 后端旧会话记忆已清空（新建对话是"离开旧会话"，不是仅清屏）：等产品自己那条销毁响应，
    // 再读后端 —— 时序由响应确定（不是等一个固定延迟），并顺带钉住"产品确实发了销毁且报 complete"。
    const purge = await purgeResponse
    expect(purge.status()).toBe(200)
    const purgeBody = (await purge.json()) as { complete?: boolean }
    expect(purgeBody.complete).toBe(true)
    const history = await api.get<{ count: number }>(request, `/api/ai/history/${before}`)
    expect(history.count).toBe(0)
  })

  test('E2E-F05 复制消息：剪贴板文本 === 正文（逐字符）', async ({ page }) => {
    await openApp(page)
    await sendMessage(page, 'turn-text 用例-F05')
    await waitRoundSettled(page)
    await expect(page.locator('[data-testid^="msg-bubble-"].w-full').last()).toHaveText(TURN_TEXT)

    const copy = page.locator('[data-testid^="copy-msg-"]').last()
    await copy.click()
    // 成功态 = 按钮切到 is-copied（失败才出 copy-hint-*）
    await expect(copy).toHaveClass(/is-copied/)

    const copied = await page.evaluate(() => navigator.clipboard.readText())
    expect(copied).toBe(TURN_TEXT)
  })

  test('E2E-Q01 空态 chip 点即发：直接发送、不回填输入框、发出后 chips 消失', async ({ page }) => {
    await openApp(page)
    const chip = page.locator('[data-testid="suggestion-chip"]').first()
    await expect(chip).toBeVisible()
    const text = (await chip.innerText()).trim()

    await chip.click()
    // 用户气泡直接出现（点即发，不是把文案塞进输入框）
    await expect(page.locator('[data-testid^="msg-bubble-"]').filter({ hasText: text }).first()).toBeVisible({
      timeout: 15_000
    })
    expect(await page.locator('[data-testid="composer-input"]').inputValue()).toBe('')
    await expect(page.locator('[data-testid="suggestion-chip"]')).toHaveCount(0)
    await waitRoundSettled(page)
  })
})
