/**
 * 批③ 11c · GF2 域：**同一会话连跑两轮生成式表单**（L-92 桩模式回归）。
 *
 * 蓝本：`docs/research/设计-真机E2E用例全集-2026-10-23.md` §0.3（采样档）+ G-01 条目。
 *
 * 为什么单开一域：L-92 的现场只在「同页两张 GF 卡」时出现 —— 第一轮提交完的表单卡以只读态
 * 留在页面上，第二轮再来一张**同 schema** 的卡。官方桩的两条收敛契约（已有 `role=tool` 只发文本 /
 * 每条路由每会话只发一次）恰好不允许这种形态，而那张路由表是共享契约 ⇒ 本域换桩
 * （`support/gf2-upstream.mjs`，带 `GF-RND` 标记按轮次发卡），配置见 `e2e/playwright.gf2.config.ts`。
 *
 * 断言三层（缺一层都证不到"收敛"）：
 * 1. 卡面 → 两卡的字段 testid 轮次唯一（`gf-field-<toolCallId>-<key>`）、DOM id 不撞车；
 * 2. 线上 → 第二张卡提交打到 `/api/ai/frontend-tool-result` 且 200，回灌体带的是**第二轮的 toolCallId** 与第二轮的值；
 * 3. 后端 → 该轮 run 快照收敛到 `DONE`，且该 toolCallId 的挂起条目不再是 `PENDING`；
 *    模型侧（桩落档）续轮请求里能读到第二轮的值 —— 即 `approval_decided` 之后续跑真的跑了。
 */
import { expect, test, type Page } from '@playwright/test'
import { openApp, runStatus, sendMessage, until, waitRoundSettled } from './support/helpers'
import { apiGet, stubRequestsFor, toolMessagesOf, uniq } from './support/hgta'

/** 本域桩的提示词标记（与 `support/gf2-upstream.mjs` 单一取值点同源）。 */
const MARKER = 'GF-RND'

/** 已渲染的表单卡（含上一轮的只读回放卡）：卡根 testid = `gf-form-<scope>`（轮次 toolCallId / 只读卡序号）。 */
function gfCard(page: Page, index: number) {
  return page.locator('[data-testid^="gf-form-"]').nth(index - 1)
}

/** 卡内字段控件：字段 key 不含连字符（后端 KEY_PATTERN）⇒ 按 testid 后缀在**该卡作用域内**定位。 */
function gfField(page: Page, index: number, key: string) {
  return gfCard(page, index).locator(`[data-testid$="-${key}"]`)
}

function scopeOf(cardTestId: string | null): string {
  return String(cardTestId ?? '').replace('gf-form-', '')
}

test.describe('@gf2 GF2 同会话两轮表单', () => {
  test('E2E-G01s 两轮 GF：字段 testid 轮次唯一、第二轮提交收敛到 done', async ({ page, request }) => {
    const tag = uniq()
    const firstPrompt = `${MARKER} 第1轮-${tag}`
    const secondPrompt = `${MARKER} 第2轮-${tag}`

    await openApp(page)

    // ── 第 1 轮：一张卡、提交、续跑到落定 ──────────────────────────────────
    await sendMessage(page, firstPrompt)
    await expect(page.locator('[data-testid="gf-fields"]')).toBeVisible({ timeout: 60_000 })
    await gfField(page, 1, 'keyword').fill('第一轮关键字')
    await page.locator('[data-testid="gf-submit"]').click()
    await waitRoundSettled(page, 90_000)
    await expect(page.locator('[data-testid="gf-submit"]')).toHaveCount(0)
    // 第 1 轮的卡以只读态留在页面上（现场前提：同页后续会有两张卡）
    await expect(page.locator('[data-testid="gf-fields"]')).toHaveCount(1)

    // ── 第 2 轮：同 schema 第二张卡 ───────────────────────────────────────
    await sendMessage(page, secondPrompt)
    await expect(page.locator('[data-testid="gf-fields"]')).toHaveCount(2, { timeout: 60_000 })

    // L-92 的核心断言：同页两卡字段**互不撞车** —— 卡根作用域不同、字段 testid 轮次唯一，
    // 且 DOM id 只剩待填卡那一份（修前两卡都是 `id="gf-field-keyword"`，定位/聚焦/`label for` 全串卡）。
    const round1TestId = await gfField(page, 1, 'keyword').getAttribute('data-testid')
    const round2TestId = await gfField(page, 2, 'keyword').getAttribute('data-testid')
    expect(round1TestId).not.toBe(round2TestId)
    expect(round2TestId?.startsWith('gf-field-')).toBe(true)
    expect(round2TestId?.endsWith('-keyword')).toBe(true)
    await expect(page.locator('#gf-field-keyword')).toHaveCount(1)
    await expect(page.locator('#gf-field-keyword')).toBeEditable()

    const round2Scope = scopeOf(await gfCard(page, 2).getAttribute('data-testid'))
    expect(round2Scope).not.toBe('')

    await gfField(page, 2, 'keyword').fill('第二轮关键字')

    const posted = page.waitForResponse(
      (res) => res.url().includes('/api/ai/frontend-tool-result') && res.request().method() === 'POST',
      { timeout: 30_000 }
    )
    await page.locator('[data-testid="gf-submit"]').click()
    const response = await posted
    expect(response.status()).toBe(200)
    const postedBody = response.request().postDataJSON() as { runId?: string; toolCallId?: string; result?: string }
    expect(postedBody.result ?? '').toContain('第二轮关键字')
    // 提交的是**第二张卡**的 toolCallId（不是上一轮那张卡的）
    expect(String(postedBody.toolCallId ?? '')).toBe(round2Scope)

    // ── 收敛面 ────────────────────────────────────────────────────────────
    await waitRoundSettled(page, 90_000)
    await expect(page.locator('[data-testid="gf-submit"]')).toHaveCount(0)
    await expect(page.locator('[data-testid="gf-error"]')).toHaveCount(0)

    const runId = String(postedBody.runId ?? '')
    const toolCallId = String(postedBody.toolCallId ?? '')
    expect(runId).not.toBe('')
    expect(toolCallId).not.toBe('')

    const pendings = await apiGet<Array<Record<string, unknown>>>(request, `/api/ai/pending/${runId}`)
    const mine = pendings.filter((item) => item.toolCallId === toolCallId)
    expect(mine, '第二轮回灌后该 toolCallId 没有台账条目').toHaveLength(1)
    expect(String(mine[0]?.entryStatus ?? ''), '第二轮挂起条目仍停在 PENDING').not.toBe('PENDING')

    // 本轮 run 收敛到 DONE：`approval_decided` 之后续跑真的跑完了
    await until(async () => (await runStatus(request, runId)) === 'DONE', 60_000, `第二轮 run ${runId} 收敛到 DONE`)

    // ── 模型侧：续轮请求里带第二轮的值（回灌真的进了上下文） ────────────────
    const calls = stubRequestsFor(firstPrompt)
    const lastRequest = calls[calls.length - 1]
    expect(lastRequest, '桩没有收到续轮请求').toBeDefined()
    const toolTexts = toolMessagesOf(lastRequest.body)
    expect(toolTexts.join('\n')).toContain('第二轮关键字')
    // 第 1 轮的值也在历史里（两轮各一条工具结果）
    expect(toolTexts.join('\n')).toContain('第一轮关键字')
  })
})
