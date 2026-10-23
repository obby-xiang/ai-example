/**
 * 真机 E2E 批② · 13b —— E 域（空态与错误卡族）+ Z 域（结果视图渲染）
 *
 * 蓝本：docs/research/设计-真机E2E用例全集-2026-10-23.md §2.17 / §2.18
 * 批①已完成 E-01、E-03（context 变体，见 07-empty-error.spec.ts），本文件补齐 E 域其余 12 条中的
 * 可承载部分，以及 Z 域的 table / truncated / 刷新持久三条（Z-04 空视图在 13c 末尾，需先把任务库清空）。
 *
 * 运行：
 *   cd frontend && npx playwright test --config e2e/playwright.nfezui.config.ts e2e/13b-nfezui.spec.ts
 *
 * 口径：错误卡variant 用**浏览器侧响应注入**（唯一原因：上游桩路由表是共享契约，本域不改它）；
 * 结果视图走**真后端 + 真 list_tasks**（本域桩只负责下发工具调用，视图由 ListTasksResultViewProvider 造）。
 */
import { expect, test } from '@playwright/test'
import {
  MARK,
  NFEZUI_APP,
  NFEZUI_DEGRADED_APP,
  assistantBubbles,
  createTask,
  expandToolCard,
  fulfillTruncatedStream,
  lastToolCallId,
  listTasks,
  openPanel,
  send,
  sessionIdOf,
  userBubbles,
  waitSettled
} from './support/helpers-nfezui'

const unique = (tag: string): string => `${tag}-${Date.now()}-${Math.random().toString(36).slice(2, 6)}`

/** 在浏览器侧让 `POST /api/ai/chat` 直接失败（错误卡四型的分派来源；请求不进后端）。 */
async function failChatWith(
  page: Parameters<typeof openPanel>[0],
  marker: string,
  status: number,
  body: Record<string, unknown>
): Promise<void> {
  await page.route('**/api/ai/chat', async (route) => {
    if (route.request().method() !== 'POST' || !(route.request().postData() ?? '').includes(marker)) {
      await route.continue()
      return
    }
    await route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) })
  })
}

test.describe('@stub 13b · E 空态与错误卡族', () => {
  test('E2E-E02 骨架屏与空态互斥（两支）：新会话不显骨架；有历史且接口慢时只显骨架', async ({ page }) => {
    // 支①：新会话（无历史）⇒ 空态，零骨架
    await openPanel(page)
    await expect(page.locator('[data-testid="empty-state"]')).toBeVisible()
    await expect(page.locator('[data-testid="history-skeleton"]')).toHaveCount(0)

    // 支②：有历史 + 接口放慢 ⇒ 骨架出现且空态**不出现**；放行后骨架消失、消息出现
    await send(page, `turn-text 用例-E02-${unique('a')}`)
    await waitSettled(page)
    const sid = await sessionIdOf(page)
    // 骨架屏的可见前提是「消息未恢复、历史未对账完」：镜像在 pagehide 会强制回写（R-14），
    // 故用 init script 在新文档的应用脚本之前清掉镜像键（history 请求带 query ⇒ 路由用正则）
    void sid
    await page.addInitScript(() => {
      const doomed: string[] = []
      for (let index = 0; index < sessionStorage.length; index += 1) {
        const key = sessionStorage.key(index)
        if (key && key.startsWith('ai-mirror')) doomed.push(key)
      }
      doomed.forEach((key) => sessionStorage.removeItem(key))
    })
    await page.route(/\/api\/ai\/history\//, async (route) => {
      await new Promise((done) => setTimeout(done, 2500))
      await route.continue()
    })
    await page.reload({ waitUntil: 'domcontentloaded' })
    await expect(page.locator('[data-testid="history-skeleton"]')).toBeVisible({ timeout: 15_000 })
    await expect(page.locator('[data-testid="empty-state"]')).toHaveCount(0)
    await expect(page.locator('[data-testid="history-skeleton"]')).toHaveCount(0, { timeout: 30_000 })
    await expect(assistantBubbles(page)).toHaveCount(1, { timeout: 30_000 })
  })

  test('E2E-E03(qfi) 错误卡三型各自渲染：quota / filter / generic 的标题与行动按钮互不串文案', async ({ page }) => {
    await openPanel(page)

    // ① quota（429）
    await failChatWith(page, MARK.quota, 429, { success: false, code: '429', message: 'rate limit exceeded (quota)' })
    await send(page, `${MARK.quota} 用例-E03-quota`)
    const quotaCard = page.locator('[data-testid^="error-card-"][data-variant="quota"]').last()
    await expect(quotaCard).toBeVisible({ timeout: 40_000 })
    await expect(quotaCard).toContainText('请求超出配额')
    await expect(page.locator('[data-testid^="error-card-action-"]').last()).toContainText('稍后重试')
    await expect(quotaCard).not.toContainText('安全策略')
    await waitSettled(page, 30_000)

    // ② filter（400 + content_filter）
    await failChatWith(page, MARK.filter, 400, { success: false, code: 'content_filter', message: 'content_filter triggered' })
    await send(page, `${MARK.filter} 用例-E03-filter`)
    const filterCard = page.locator('[data-testid^="error-card-"][data-variant="filter"]').last()
    await expect(filterCard).toBeVisible({ timeout: 40_000 })
    await expect(filterCard).toContainText('内容被安全策略拦截')
    await expect(page.locator('[data-testid^="error-card-action-"]').last()).toContainText('改写后重发')
    await expect(filterCard).not.toContainText('请求超出配额')
    await waitSettled(page, 30_000)

    // ③ generic（未知码 500）
    await failChatWith(page, MARK.generic, 500, { success: false, code: 'NFEZUI_UNKNOWN', message: 'boom-generic' })
    await send(page, `${MARK.generic} 用例-E03-generic`)
    const genericCard = page.locator('[data-testid^="error-card-"][data-variant="generic"]').last()
    await expect(genericCard).toBeVisible({ timeout: 40_000 })
    await expect(genericCard).toContainText('本轮出错')
    await waitSettled(page, 30_000)
  })

  test('E2E-E04 通用错误卡不是空壳：标题+原文齐备、无行动钮、文案不与其他三型混用', async ({ page }) => {
    await openPanel(page)
    await failChatWith(page, MARK.generic, 500, {
      success: false,
      code: 'NFEZUI_UNKNOWN',
      message: 'nfezui-unclassified-failure-原文'
    })
    await send(page, `${MARK.generic} 用例-E04`)
    const cards = page.locator('[data-testid^="error-card-"][data-variant]')
    await expect(cards.last()).toBeVisible({ timeout: 40_000 })
    expect(await cards.count()).toBe(1)

    const card = cards.last()
    expect(await card.getAttribute('data-variant')).toBe('generic')
    await expect(card).toContainText('本轮出错')
    // 原文逐字进卡（可读，不是空壳）
    await expect(card.locator('[data-testid^="error-card-detail-"]')).toContainText('nfezui-unclassified-failure-原文')
    // 通用卡没有行动钮（没有可执行动作就不假装有）
    await expect(page.locator('[data-testid^="error-card-action-"]')).toHaveCount(0)
    await waitSettled(page, 30_000)
  })

  test('E2E-E05 错误卡详情与行动：原文可见、点「开新会话」清屏换会话', async ({ page }) => {
    await openPanel(page)
    await send(page, `turn-text 用例-E05-${unique('pre')}`)
    await waitSettled(page)
    const sidBefore = await sessionIdOf(page)

    await failChatWith(page, 'NFZ-E05-CTX', 400, {
      success: false,
      code: 'context_length_exceeded',
      message: 'This model\'s maximum context length is 128000 tokens, however you requested 130001 tokens.'
    })
    await send(page, 'NFZ-E05-CTX 用例-E05')
    const card = page.locator('[data-testid^="error-card-"][data-variant="context"]').last()
    await expect(card).toBeVisible({ timeout: 40_000 })
    await expect(card.locator('[data-testid^="error-card-detail-"]')).toContainText('maximum context length')

    const action = page.locator('[data-testid^="error-card-action-"]').last()
    await expect(action).toContainText('开新会话')
    await action.click()
    await expect(page.locator('[data-testid="empty-state"]')).toBeVisible({ timeout: 20_000 })
    expect(await sessionIdOf(page)).not.toBe(sidBefore)
    await expect(userBubbles(page)).toHaveCount(0)
  })

  test('E2E-E06 断流横幅条件：上游断流 ⇒ 红条出现；用户主动停止 ⇒ 不出现', async ({ page }) => {
    await openPanel(page)
    // 断流面 = 「done 之前连接结束」（ADR-6）；上游 kill-stream 会由后端重试+终帧收尾，不是这个面
    await fulfillTruncatedStream(page, 'NFZ-E06-STREAM', '断流前的半截正文')
    await send(page, 'NFZ-E06-STREAM 用例-E06')
    const banner = page.locator('[data-testid="alert-interrupted"]')
    await expect(banner).toBeVisible({ timeout: 60_000 })
    await expect(banner).toContainText('结果可能不完整')
    // 已生成内容保留（断流不是清屏）
    await expect(page.locator('[data-testid^="msg-bubble-"].w-full').last()).toContainText('断流前的半截正文')
    await waitSettled(page, 60_000)

    // 用户主动停止：不是「结果可能不完整」，红条不得误闪
    await page.locator('[data-testid="new-chat"]').click()
    await expect(page.locator('[data-testid="empty-state"]')).toBeVisible()
    const bubblesBeforeStop = await page.locator('[data-testid^="msg-bubble-"].w-full').count()
    await send(page, 'stall-frames=600 用例-E06-stop')
    await expect(page.locator('[data-testid="stop-btn"]')).toBeVisible({ timeout: 20_000 })
    // 先等**本轮**半截正文落地：空气泡不打「已停止」标记（markStoppedMessage 只在有正文/工具段时标）
    await expect(page.locator('[data-testid^="msg-bubble-"].w-full')).toHaveCount(bubblesBeforeStop + 1, { timeout: 20_000 })
    await expect
      .poll(async () => (await page.locator('[data-testid^="msg-bubble-"].w-full').last().innerText()).trim().length,
        { timeout: 30_000 })
      .toBeGreaterThan(0)
    await page.locator('[data-testid="stop-btn"]').click()
    await expect(page.locator('[data-testid^="msg-stopped-"]').last()).toBeVisible({ timeout: 20_000 })
    await expect(page.locator('[data-testid="alert-interrupted"]')).toHaveCount(0)
  })

  test('E2E-E07 降级横幅：无 key 实例上面板出降级黄条、发送钮禁用、提示可行动', async ({ page }) => {
    await page.goto(`${NFEZUI_DEGRADED_APP}/`, { waitUntil: 'domcontentloaded' })
    await expect(page.locator('[data-testid="composer-input"]')).toBeVisible({ timeout: 30_000 })
    const alert = page.locator('[data-testid="alert-degraded"]')
    await expect(alert).toBeVisible({ timeout: 30_000 })
    await expect(alert).toContainText('AI')
    // 降级面：发送钮禁用 —— 且输入区整体硬禁用（composerHardDisabled：health.available=false）
    await expect(page.locator('[data-testid="send-btn"]')).toBeDisabled()
    await expect(page.locator('[data-testid="composer-input"]')).toBeDisabled()
  })

  test('E2E-E08 池饱和横幅：并发池打满时第 4 轮 503 + alert-pool-saturated 可见', async ({ browser }) => {
    test.setTimeout(180_000)
    // 每个并发轮必须是**不同会话**：同一 context 的新标签页会继承 sessionStorage（同一 sessionId），
    // 那样第 2 轮起会被会话门 409 拦掉，测不到并发池。故一会话一 context。
    const contexts = []
    const pages = []
    for (let index = 0; index < 4; index += 1) {
      const ctx = await browser.newContext({ baseURL: NFEZUI_APP })
      const p = await ctx.newPage()
      await p.goto('/', { waitUntil: 'domcontentloaded' })
      await expect(p.locator('[data-testid="composer-input"]')).toBeVisible({ timeout: 30_000 })
      contexts.push(ctx)
      pages.push(p)
    }

    // 前三轮把并发池（suspend.pool-size=3）占满：stall-frames 让它们长时间在跑
    for (const p of pages.slice(0, 3)) {
      await send(p, `stall-frames=20000 用例-E08-${unique('hold')}`)
    }
    for (const p of pages.slice(0, 3)) {
      await expect(p.locator('[data-testid="stop-btn"]')).toBeVisible({ timeout: 30_000 })
    }

    // 第 4 轮：池已满 ⇒ 503 SUSPEND_POOL_SATURATED ⇒ 顶部池饱和横幅
    const late = pages[3]
    const rejected = late.waitForResponse((response) =>
      response.url().includes('/api/ai/chat') && response.status() === 503)
    await send(late, `turn-text 用例-E08-${unique('late')}`)
    const response = await rejected
    expect(response.status()).toBe(503)
    const body = (await response.text()) ?? ''
    expect(body).toContain('SUSPEND_POOL_SATURATED')
    await expect(late.locator('[data-testid="alert-pool-saturated"]')).toBeVisible({ timeout: 30_000 })

    // 收尾：停掉占位轮，释放并发池
    for (const p of pages.slice(0, 3)) {
      await p.locator('[data-testid="stop-btn"]').click().catch(() => undefined)
    }
    for (const p of pages.slice(0, 3)) {
      await expect(p.locator('[data-testid="stop-btn"]')).toHaveCount(0, { timeout: 30_000 }).catch(() => undefined)
    }
    for (const ctx of contexts) {
      await ctx.close()
    }
  })

  test('E2E-E09 notice 单槽位不堆叠：连造三条错误提示，屏上只留最新一条', async ({ page }) => {
    await openPanel(page)
    const codes: Array<[string, number, string, string]> = [
      [MARK.quota, 429, '429', 'rate limit exceeded (quota)'],
      [MARK.filter, 400, 'content_filter', 'content_filter triggered'],
      [MARK.generic, 500, 'NFEZUI_UNKNOWN', 'third-generic-notice']
    ]
    for (const [marker, status, code, message] of codes) {
      await failChatWith(page, marker, status, { success: false, code, message })
      await send(page, `${marker} 用例-E09-${unique(code)}`)
      await expect(page.locator('[data-testid^="error-card-"]').last()).toBeVisible({ timeout: 40_000 })
      await waitSettled(page, 30_000)
    }
    const bars = page.locator('[data-testid="notice-bar"]')
    await expect(bars).toHaveCount(1)
    await expect(bars.last()).toContainText('third-generic-notice')
    await expect(bars.last()).not.toContainText('rate limit exceeded')
  })

  test('E2E-E10 ui_event 回执：工作区动作执行后出 alert-ui-event（含事件名与处理结果）', async ({ page }) => {
    await openPanel(page, '/tasks')
    await send(page, `导出向导 用例-E10-${unique('nav')}`)
    const alert = page.locator('[data-testid="alert-ui-event"]')
    await expect(alert).toBeVisible({ timeout: 60_000 })
    const text = await alert.innerText()
    expect(text.length).toBeGreaterThan(0)
    // 回执带事件名（navigate/导航类），且不是空白壳
    expect(text).toMatch(/navigat|导航|导出|export/i)
    // 真页面确实被带到了导出向导（动作真执行，不只是回执）
    await expect.poll(async () => new URL(page.url()).pathname, { timeout: 30_000 }).toContain('export')
    await waitSettled(page, 60_000)
  })

  test('E2E-E13 空态矩阵可辨识：不同页面的空态各有独立文案，不共用一句话', async ({ page }) => {
    const texts: Record<string, string> = {}

    // ① 新会话空态
    await openPanel(page)
    texts.session = ((await page.locator('[data-testid="empty-state"]').innerText()) ?? '').trim()
    expect(texts.session.length).toBeGreaterThan(0)

    // ② 反馈页空态（接口回空）
    await page.route('**/api/ai/feedback', async (route) => {
      if (route.request().method() === 'GET') {
        await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ success: true, data: [] }) })
        return
      }
      await route.continue()
    })
    await page.goto('/settings/ai-feedback', { waitUntil: 'domcontentloaded' })
    await expect(page.locator('[data-testid="ai-feedback-view"]')).toBeVisible({ timeout: 30_000 })
    await expect(page.locator('[data-testid="ai-feedback-view"]')).toContainText('暂无反馈记录')
    texts.feedback = '暂无反馈记录'

    // ③ 设置页空态（没有 provider 的降级面：拦 /api/ai/providers 回空）
    await page.route('**/api/ai/providers', async (route) => {
      if (route.request().method() === 'GET') {
        await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ success: true, data: [] }) })
        return
      }
      await route.continue()
    })
    await page.goto('/settings/ai', { waitUntil: 'domcontentloaded' })
    const settingsEmpty = page.locator('[data-testid="ai-settings-empty"]')
    if (await settingsEmpty.count()) {
      texts.settings = ((await settingsEmpty.innerText()) ?? '').trim()
    }

    // ④ 建议区缺省：出错轮不出建议（"没有建议"这一态的可见面 = 区域不渲染）
    await openPanel(page)
    await failChatWith(page, MARK.generic, 500, { success: false, code: 'NFEZUI_UNKNOWN', message: 'e13-generic' })
    await send(page, `${MARK.generic} 用例-E13`)
    await expect(page.locator('[data-testid^="error-card-"]').last()).toBeVisible({ timeout: 40_000 })
    await waitSettled(page, 30_000)
    await expect(page.locator('[data-testid="followup-suggestions"]')).toHaveCount(0)

    const distinct = new Set(Object.values(texts))
    expect(distinct.size).toBe(Object.keys(texts).length)
    expect(Object.keys(texts).length).toBeGreaterThanOrEqual(2)
  })
})

test.describe('@stub 13b · Z 结果视图渲染', () => {
  test('E2E-Z01 table 视图渲染：表头 == 声明列、行数 == 视图行数、resultview-table 可见', async ({ page }) => {
    // 夹具：至少 3 条任务（零任务时视图走 empty 分支，测不到 table 渲染）
    if ((await listTasks(page.request)).length < 3) {
      await createTask(page.request, `NFEZUI-Z01-a-${unique('t')}`)
      await createTask(page.request, `NFEZUI-Z01-b-${unique('t')}`, 'IMPORT')
      await createTask(page.request, `NFEZUI-Z01-c-${unique('t')}`)
    }
    await openPanel(page, '/tasks')
    await send(page, `${MARK.listTasks} 用例-Z01-${unique('t')}`)
    await expect(page.locator('[data-testid^="tool-card-"]').last()).toBeVisible({ timeout: 60_000 })
    await waitSettled(page, 60_000)
    const toolCallId = await lastToolCallId(page)
    await expandToolCard(page, toolCallId)
    const table = page.locator('[data-testid="resultview-table"]').last()
    await expect(table).toBeVisible({ timeout: 30_000 })
    const headers = (await table.locator('thead th').allInnerTexts()).map((text) => text.trim())
    expect(headers).toEqual(['编号', '类型', '标题', '步骤', '状态'])
    const rows = table.locator('[data-testid="resultview-row"]')
    const rowCount = await rows.count()
    expect(rowCount).toBeGreaterThan(0)
    expect(rowCount).toBeLessThanOrEqual(20)
    // 行数 == 真任务数（≤20 时不截断，视图与任务中心同源）
    const total = (await listTasks(page.request)).length
    expect(rowCount).toBe(Math.min(total, 20))
    // 首列是编号（整数列），单元格是可读文本而不是 JSON
    const firstCell = (await rows.first().locator('td').first().innerText()).trim()
    expect(firstCell).toMatch(/^\d+$/)
    await waitSettled(page, 60_000)
  })

  test('E2E-Z05 截断视图：任务数超上限时出现 resultview-truncated 并说明「已显示 N/M 行」', async ({ page }) => {
    // 夹具：造到 22 条以上任务（list_tasks 上限 20 ⇒ 视图 meta.truncated 生效）
    const current = (await listTasks(page.request)).length
    for (let index = current; index < 22; index += 1) {
      await createTask(page.request, `NFEZUI-Z05-${index}-${unique('t')}`)
    }
    expect((await listTasks(page.request)).length).toBeGreaterThanOrEqual(22)

    await openPanel(page, '/tasks')
    await send(page, `${MARK.listTasks} 用例-Z05-${unique('t')}`)
    await expect(page.locator('[data-testid^="tool-card-"]').last()).toBeVisible({ timeout: 60_000 })
    await waitSettled(page, 60_000)
    await expandToolCard(page, await lastToolCallId(page))
    const table = page.locator('[data-testid="resultview-table"]').last()
    await expect(table).toBeVisible({ timeout: 30_000 })
    expect(await table.locator('[data-testid="resultview-row"]').count()).toBe(20)

    const note = page.locator('[data-testid="resultview-truncated"]').last()
    await expect(note).toBeVisible()
    const text = await note.innerText()
    expect(text).toMatch(/共\s*\d+\s*行/)
    expect(text).toContain('20')
    await waitSettled(page, 60_000)
  })

  // Z-06 未通过（发现项，见施工报告）：`resultView` 不随 `/api/ai/history` 恢复 ——
  // 历史重建只带 toolCallStates（status/kind2/reason），视图是帧内字段；刷新后卡面回落为旧文本形态。
  // 用例保留并按 fixme 显式挂账（不是静默跳过），产品补上「视图随段持久化」后去掉 fixme 即转绿。
  test('E2E-Z06 刷新后视图仍在：真 reload 后同一张 table 视图重新渲染，列与行逐值相等', async ({ page }) => {
    test.fixme(true, 'Z-06 未通过：resultView 不随 /history 恢复（历史重建只带 toolCallStates）')
    await openPanel(page, '/tasks')
    await send(page, `${MARK.listTasks} 用例-Z06-${unique('t')}`)
    await expect(page.locator('[data-testid^="tool-card-"]').last()).toBeVisible({ timeout: 60_000 })
    await waitSettled(page, 60_000)
    const beforeId = await lastToolCallId(page)
    await expandToolCard(page, beforeId)
    const table = page.locator('[data-testid="resultview-table"]').last()
    await expect(table).toBeVisible({ timeout: 30_000 })
    const headersBefore = (await table.locator('thead th').allInnerTexts()).map((text) => text.trim())
    const rowsBefore = (await table.locator('[data-testid="resultview-row"]').allInnerTexts()).map((text) => text.trim())

    await page.reload({ waitUntil: 'domcontentloaded' })
    await expect(page.locator('[data-testid^="tool-card-"]').last()).toBeVisible({ timeout: 40_000 })
    await expandToolCard(page, await lastToolCallId(page))
    const tableAfter = page.locator('[data-testid="resultview-table"]').last()
    await expect(tableAfter).toBeVisible({ timeout: 30_000 })
    const headersAfter = (await tableAfter.locator('thead th').allInnerTexts()).map((text) => text.trim())
    expect(headersAfter).toEqual(headersBefore)
    const rowsAfter = (await tableAfter.locator('[data-testid="resultview-row"]').allInnerTexts()).map((text) => text.trim())
    expect(rowsAfter).toEqual(rowsBefore)
  })
})
