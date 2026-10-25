/**
 * 真机 E2E 批② · 13a —— N 域（会话管理与页签所有权）+ F 域（反馈与消息操作）
 *
 * 蓝本：docs/research/设计-真机E2E用例全集-2026-10-23.md §2.14 / §2.15
 * 批①已完成 N-01、F-05（见 08-session.spec.ts），本文件补齐其余 19 条。
 *
 * 运行（独占端口段 19130/19131/19198/19199/19201/19202，Redis DB 13/14）：
 *   cd frontend && npx playwright test --config e2e/playwright.nfezui.config.ts e2e/13-nfezui.spec.ts
 *
 * 口径：断言只落 data-testid / 终态文本 / 真 reload / 真鼠标；不引新依赖；不改共享文件。
 */
import { expect, test, type Page } from '@playwright/test'
import {
  MARK,
  NFEZUI_API,
  NFEZUI_APP,
  apiGet,
  apiRaw,
  assistantBubbles,
  cjkHits,
  computed,
  feedbackList,
  fulfillTruncatedStream,
  historyOf,
  openPanel,
  NFEZUI_REDIS_DB,
  redisEval,
  redisInt,
  send,
  sessionIdOf,
  storageState,
  userBubbles,
  waitSettled
} from './support/helpers-nfezui'

const TURN = 'turn-text 用例'
const unique = (tag: string): string => `${tag}-${Date.now()}-${Math.random().toString(36).slice(2, 6)}`

async function seedRound(page: Page, tag: string): Promise<void> {
  await send(page, `${TURN}-${unique(tag)}`)
  await waitSettled(page)
}

test.describe('@stub 13a · N 会话管理与页签所有权', () => {
  test('E2E-N02 首屏历史加载与骨架屏：骨架与空态互斥，加载完骨架消失、消息出现', async ({ page }) => {
    await openPanel(page)
    // 造 3 轮历史（同一页签会话）
    for (const tag of ['N02-a', 'N02-b', 'N02-c']) {
      await seedRound(page, tag)
    }
    const sid = await sessionIdOf(page)
    await expect(assistantBubbles(page)).toHaveCount(3)

    // 骨架屏的可见前提是「消息未恢复、历史未对账完」：镜像会同步恢复正文，故先按 R-13 的
    // 镜像缺失形态（复制页签/镜像键丢失）清掉本会话镜像，再真重载并把 /history 放慢。
    // 镜像在 pagehide 时会被强制回写（R-14），刷新前删键无效 —— 改用 init script 在新文档的
    // 应用脚本之前清掉镜像键，构造出「有后端历史、本页签无镜像」的真机形态（R-13 的镜像缺失分支）。
    void sid
    await page.addInitScript(() => {
      const doomed: string[] = []
      for (let index = 0; index < sessionStorage.length; index += 1) {
        const key = sessionStorage.key(index)
        if (key && key.startsWith('ai-mirror')) doomed.push(key)
      }
      doomed.forEach((key) => sessionStorage.removeItem(key))
    })
    // history 请求带 query（offset/limit），glob 须用正则匹配整条 URL
    await page.route(/\/api\/ai\/history\//, async (route) => {
      await new Promise((done) => setTimeout(done, 2500))
      await route.continue()
    })
    await page.reload({ waitUntil: 'domcontentloaded' })

    await expect(page.locator('[data-testid="history-skeleton"]')).toBeVisible({ timeout: 15_000 })
    // 骨架屏与空态**不同框**
    await expect(page.locator('[data-testid="empty-state"]')).toHaveCount(0)
    await expect(page.locator('[data-testid="history-skeleton"]')).toHaveCount(0, { timeout: 30_000 })
    await expect(assistantBubbles(page)).toHaveCount(3, { timeout: 30_000 })
    await expect(page.locator('[data-testid="empty-state"]')).toHaveCount(0)
  })

  test('E2E-N04 删除会话全链：DELETE 200 → history 空 → context 归零 → 重复删除幂等', async ({ page, request }) => {
    await openPanel(page)
    await seedRound(page, 'N04')
    const sid = await sessionIdOf(page)

    // ① 销毁前：真实存在过的状态（不伪造 —— 都是本用例这一轮真跑出来的）
    const before = await historyOf(request, sid)
    expect(before.count).toBeGreaterThan(0)
    const fullBefore = await apiGet<{ count: number }>(
      request,
      `/api/ai/history/${encodeURIComponent(sid)}/messages?limit=200`
    )
    expect(fullBefore.count).toBeGreaterThan(0)
    const ctxBefore = await apiGet<{ usedTokens?: number; source?: string }>(
      request,
      `/api/ai/context/${encodeURIComponent(sid)}`
    )
    expect(Number(ctxBefore.usedTokens ?? 0)).toBeGreaterThan(0)
    // 反馈：真点一次赞（与 F-01 同一入口），让"反馈也随会话销毁"有可验证的销毁前状态
    const messageId = ((await assistantBubbles(page).last().getAttribute('data-testid')) ?? '')
      .replace('msg-bubble-', '')
    await page.locator(`[data-testid="feedback-up-${messageId}"]`).click()
    await expect.poll(async () => (await feedbackList(request)).filter((item) => item.sessionId === sid).length, {
      timeout: 15_000
    }).toBeGreaterThan(0)
    // 会话面/记忆面/上下文面的真键确实在位（Redis DB 13，本域独占）
    for (const key of [`ai:session:${sid}`, `chat:mem:{${sid}}`, `ai:ctx:${sid}`]) {
      expect(await redisInt(NFEZUI_REDIS_DB, ['EXISTS', key]), `${key} 销毁前应存在`).toBe(1)
    }
    // 业务数据基线：「只删 AI 自有数据」不是"顺手删干净"
    const tasksBefore = await apiGet<{ totalElements?: number }>(request, '/api/tasks?page=0&size=1')

    const first = await apiRaw(request, 'delete', `/api/ai/history/${encodeURIComponent(sid)}`)
    expect(first.status()).toBe(200)
    // DELETE 回的是裸对象（与 /cancel、/context 同姿态，不套 ApiResponse）
    // 契约来源：`AiController` 的 DELETE 已统一委托 `AiSessionPurgeService`（完整会话销毁）——
    // 删该会话全部 AI 数据（记忆 / run 13 键 / 上下文 / 附件 / 反馈 / 完整历史 / 会话索引），
    // 业务数据不碰；旧 `/history` 只是兼容路径，**没有第二套"只清记忆窗口"的语义**。
    const removed = (await first.json()) as {
      complete?: boolean
      found?: boolean
      aborted?: boolean
      deleted?: boolean
      existed?: boolean
      removedFromIndex?: boolean
      errors?: string[]
      deferred?: unknown[]
      memory?: { existed?: boolean; messages?: number; deleted?: boolean }
      runs?: Array<{ runId: string; deleted?: boolean; deferred?: boolean }>
      context?: { deletedKeys?: number }
      attachments?: { registered?: number; registrationsDeleted?: number; filesDeleted?: number; failedFileIds?: string[] }
      session?: { currentDeleted?: boolean; recordDeleted?: boolean }
      database?: {
        feedbackRows?: number
        feedbackDeleted?: number
        chatMessageRows?: number
        chatMessagesDeleted?: number
      }
    }
    // 销毁完整性：不是"删了点东西就算成功"，而是各面都交代清楚且无残留/无延后
    expect(removed.complete).toBe(true)
    expect(removed.aborted).toBe(false)
    expect(removed.found).toBe(true)
    expect(removed.deleted).toBe(true) // 兼容字段 = complete
    expect(removed.existed).toBe(true) // 记忆确实存在过（不是空删）
    expect(removed.removedFromIndex).toBe(true) // 记忆索引里已没有它
    expect(removed.errors ?? []).toEqual([])
    expect(removed.deferred ?? []).toEqual([])
    expect(removed.memory?.deleted).toBe(true)
    expect(removed.runs?.every((run) => run.deleted && !run.deferred)).toBe(true)
    expect(removed.context?.deletedKeys ?? 0).toBeGreaterThan(0)
    expect(removed.database?.feedbackRows ?? 0).toBeGreaterThan(0)
    expect(removed.database?.feedbackDeleted).toBe(removed.database?.feedbackRows)
    expect(removed.database?.chatMessageRows ?? 0).toBeGreaterThan(0)
    expect(removed.database?.chatMessagesDeleted).toBe(removed.database?.chatMessageRows)
    // 附件面：N-04 的桩场景没有挂起表单 ⇒ 没有附件实例可造（不伪造）。
    // 这里只钉"响应必须如实交代附件面、且没有删除失败"；真正的附件删除由 hgta 域用真表单覆盖。
    expect(removed.attachments?.registrationsDeleted).toBe(removed.attachments?.registered)
    expect(removed.attachments?.failedFileIds ?? []).toEqual([])

    // ② 销毁后：记忆窗口、完整历史、反馈、上下文计量、会话/记忆/上下文键全部归零/不可读
    expect((await historyOf(request, sid)).count).toBe(0)
    const fullAfter = await apiGet<{ count: number }>(
      request,
      `/api/ai/history/${encodeURIComponent(sid)}/messages?limit=200`
    )
    expect(fullAfter.count).toBe(0)
    const ctxAfter = await apiGet<{ usedTokens?: number; source?: string }>(
      request,
      `/api/ai/context/${encodeURIComponent(sid)}`
    )
    // 归零的口径：计量键随会话销毁 ⇒ 读回的是"未测过"（usedTokens=0 且 source=none），
    // 而不是把旧值留着假装"这轮还很满"。
    expect(ctxAfter.usedTokens).toBe(0)
    expect(ctxAfter.source).toBe('none')
    expect((await feedbackList(request)).filter((item) => item.sessionId === sid)).toEqual([])
    for (const key of [`ai:session:${sid}`, `chat:mem:{${sid}}`, `ai:ctx:${sid}`, `ai:session:runs:${sid}`]) {
      expect(await redisInt(NFEZUI_REDIS_DB, ['EXISTS', key]), `${key} 销毁后应不存在`).toBe(0)
    }
    const tasksAfter = await apiGet<{ totalElements?: number }>(request, '/api/tasks?page=0&size=1')
    expect(tasksAfter.totalElements).toBe(tasksBefore.totalElements)

    // ③ 幂等：删一个已不存在的会话仍是 200，不 5xx；且不再宣称"删掉了记忆"
    const second = await apiRaw(request, 'delete', `/api/ai/history/${encodeURIComponent(sid)}`)
    expect(second.status()).toBe(200)
    const again = (await second.json()) as { complete?: boolean; found?: boolean; existed?: boolean; deleted?: boolean }
    expect(again.complete).toBe(true)
    expect(again.found).toBe(false)
    expect(again.existed).toBe(false)
    expect(again.deleted).toBe(true)
  })

  test('E2E-N05 会话 id 页签级与刷新沿用：同一 sessionId 沿用、镜像键仍是同一个', async ({ page }) => {
    await openPanel(page)
    await seedRound(page, 'N05')
    const sid = await sessionIdOf(page)
    const before = await storageState(page)
    expect(before.mirror).not.toBeNull()

    await page.reload({ waitUntil: 'domcontentloaded' })
    await expect(page.locator('[data-testid="composer-input"]')).toBeVisible({ timeout: 30_000 })
    const after = await sessionIdOf(page)
    expect(after).toBe(sid)
    const state = await storageState(page)
    expect(state.mirror).not.toBeNull()
    // 镜像不因刷新多出一份（同会话单键）
    expect(state.keys.filter((key) => key.startsWith('ai-mirror:')).length).toBe(1)
    // 会话 id 存 sessionStorage（不是 localStorage）
    const inLocal = await page.evaluate(() => localStorage.getItem('ai-session-id'))
    expect(inLocal).toBeNull()
  })

  test('E2E-N06 关页签重开 = 新会话：新 id、旧历史不出现、sessionStorage 已清', async ({ browser, page }) => {
    await openPanel(page)
    await seedRound(page, 'N06')
    const oldSid = await sessionIdOf(page)
    await expect(assistantBubbles(page)).toHaveCount(1)

    await page.close()
    // 「关页签重开」= 新的浏览会话（sessionStorage 为空的那一档）。同一 context 里 newPage()
    // 会继承上一标签页的 sessionStorage 快照（实测），那是「复制页签」而不是「重开新页签」。
    const freshContext = await browser.newContext({ baseURL: NFEZUI_APP })
    const fresh = await freshContext.newPage()
    await fresh.goto('/', { waitUntil: 'domcontentloaded' })
    await expect(fresh.locator('[data-testid="composer-input"]')).toBeVisible({ timeout: 30_000 })

    const newSid = await sessionIdOf(fresh)
    expect(newSid).not.toBe(oldSid)
    await expect(fresh.locator('[data-testid="empty-state"]')).toBeVisible()
    await expect(assistantBubbles(fresh)).toHaveCount(0)
    const state = await storageState(fresh)
    // 新会话的镜像只能是空壳（产品会在挂载后为本会话写一份空镜像）——
    // 关键判据是「旧会话的正文没有跟着进来」，不是「镜像键不存在」
    const mirror = state.mirror === null ? null : JSON.parse(state.mirror) as { messages?: unknown[] }
    expect(mirror?.messages?.length ?? 0).toBe(0)
    await freshContext.close()
  })

  test('E2E-N07 双页签同会话所有权：一 owner 一 duplicate，duplicate 不双跑', async ({ context, page, request }) => {
    await openPanel(page)
    await seedRound(page, 'N07')
    const sid = await sessionIdOf(page)

    // 「复制标签页」的真机形态：新页签继承同一 sessionStorage 会话 id
    const dup = await context.newPage()
    await dup.addInitScript((sessionId) => {
      try {
        sessionStorage.setItem('ai-session-id', sessionId)
      } catch {
        /* 隐私模式：忽略 */
      }
    }, sid)
    await dup.goto('/', { waitUntil: 'domcontentloaded' })
    await expect(dup.locator('[data-testid="composer-input"]')).toBeVisible({ timeout: 30_000 })

    // duplicate 就地换新会话（不抢锁、不删原会话记忆）
    await expect
      .poll(async () => dup.evaluate(() => sessionStorage.getItem('ai-session-id')), { timeout: 20_000 })
      .not.toBe(sid)
    const dupSid = await sessionIdOf(dup)
    expect(dupSid).not.toBe(sid)
    await expect(dup.locator('[data-testid="empty-state"]')).toBeVisible()

    // 原页签不受影响：会话 id 与消息都还在
    expect(await sessionIdOf(page)).toBe(sid)
    await expect(assistantBubbles(page)).toHaveCount(1)
    // 后端旧会话记忆未被复制页签删掉
    expect((await historyOf(request, sid)).count).toBeGreaterThan(0)
  })

  test('E2E-N08 复制页签就地换新会话：不显示旧历史、两页签不共用 runId', async ({ context, page }) => {
    await openPanel(page)
    await seedRound(page, 'N08')
    const sid = await sessionIdOf(page)
    const ownerRun = await apiGet<{ found?: boolean; runId?: string }>(
      page.request,
      `/api/ai/runs/current?sessionId=${encodeURIComponent(sid)}`
    )

    const dup = await context.newPage()
    await dup.addInitScript((sessionId) => {
      try {
        sessionStorage.setItem('ai-session-id', sessionId)
      } catch {
        /* 忽略 */
      }
    }, sid)
    await dup.goto('/', { waitUntil: 'domcontentloaded' })
    await expect(dup.locator('[data-testid="composer-input"]')).toBeVisible({ timeout: 30_000 })
    await expect(dup.locator('[data-testid="empty-state"]')).toBeVisible({ timeout: 20_000 })

    // 复制页签里没有任何旧历史气泡（换新会话，不是接管）
    await expect(assistantBubbles(dup)).toHaveCount(0)
    const dupSid = await sessionIdOf(dup)
    expect(dupSid).not.toBe(sid)
    // 两个页签不共用同一 runId：复制页签的会话下没有在途轮
    const dupRun = await apiGet<{ found?: boolean; runId?: string }>(
      dup.request,
      `/api/ai/runs/current?sessionId=${encodeURIComponent(dupSid)}`
    )
    expect(dupRun.found === true && dupRun.runId === ownerRun.runId).toBe(false)
  })

  test('E2E-N09 接管会话：原页签离开后 duplicate 接手同一会话并读回历史', async ({ context, page }) => {
    await openPanel(page)
    await seedRound(page, 'N09')
    const sid = await sessionIdOf(page)

    const dup = await context.newPage()
    await dup.addInitScript((sessionId) => {
      try {
        sessionStorage.setItem('ai-session-id', sessionId)
      } catch {
        /* 忽略 */
      }
    }, sid)
    await dup.goto('/', { waitUntil: 'domcontentloaded' })
    await expect(dup.locator('[data-testid="composer-input"]')).toBeVisible({ timeout: 30_000 })
    await expect(dup.locator('[data-testid="empty-state"]')).toBeVisible({ timeout: 20_000 })

    // 原页签离开（pagehide ⇒ 广播 released + 释放锁），duplicate 接手
    await page.close()
    await expect.poll(async () => dup.evaluate(() => sessionStorage.getItem('ai-session-id')), { timeout: 30_000 })
      .toBe(sid)
    // 接手后把该会话历史读回来（真历史，不是空白）
    await expect(assistantBubbles(dup)).toHaveCount(1, { timeout: 30_000 })
  })

  test('E2E-N10 会话滑动 TTL：活跃会话持续续期，键随会话存在', async ({ page, request }) => {
    await openPanel(page)
    await seedRound(page, 'N10-a')
    const sid = await sessionIdOf(page)
    // Redis 面（DB 13 独占）：会话键存在且带 TTL（默认 session-ttl 24h）
    const ttl1 = await redisInt(NFEZUI_REDIS_DB, ['TTL', `ai:session:${sid}`])
    expect(ttl1).toBeGreaterThan(0)
    expect(ttl1).toBeLessThanOrEqual(86_400)

    // 静置 5s ⇒ TTL 自然下掉；再跑一轮 ⇒ 滑动续期把它抬回满值（先掉后回才可观测）
    await new Promise((done) => setTimeout(done, 5000))
    const ttlIdle = await redisInt(NFEZUI_REDIS_DB, ['TTL', `ai:session:${sid}`])
    expect(ttlIdle).toBeLessThan(ttl1)
    await seedRound(page, 'N10-b')
    const ttlFresh = await redisInt(NFEZUI_REDIS_DB, ['TTL', `ai:session:${sid}`])
    expect(ttlFresh).toBeGreaterThan(ttlIdle)
    expect(ttlFresh).toBeGreaterThanOrEqual(ttl1 - 1)

    // 静默过期的等价面：会话键 + 记忆键 + 上下文键一并按 TTL 消失
    for (const key of [`ai:session:${sid}`, `chat:mem:{${sid}}`, `ai:ctx:${sid}`]) {
      await redisEval(NFEZUI_REDIS_DB, ['DEL', key])
    }
    expect((await historyOf(request, sid)).count).toBe(0)
  })

  test('E2E-N11 会话到期后发消息：发送成功、不 500/409、新会话正常计轮', async ({ page }) => {
    await openPanel(page)
    await seedRound(page, 'N11-a')
    const sid = await sessionIdOf(page)
    for (const key of [`ai:session:${sid}`, `chat:mem:{${sid}}`, `ai:ctx:${sid}`]) {
      await redisEval(NFEZUI_REDIS_DB, ['DEL', key])
    }

    const failures: number[] = []
    page.on('response', (response) => {
      if (response.url().includes('/api/ai/chat') && response.status() >= 400) {
        failures.push(response.status())
      }
    })
    const tag = unique('N11-b')
    await send(page, `${TURN}-${tag}`)
    await waitSettled(page, 60_000)

    expect(failures).toEqual([])
    await expect(page.locator('[data-testid^="error-card-"]')).toHaveCount(0)
    // 新一轮照常计轮（历史里能看到本轮）
    await expect.poll(async () => (await historyOf(page.request, sid)).count, { timeout: 20_000 }).toBeGreaterThan(0)
  })

  test('E2E-N12 面板收起/展开记忆：收起态刷新后保持，重开后消息仍在', async ({ page }) => {
    await openPanel(page)
    await seedRound(page, 'N12')
    await expect(assistantBubbles(page)).toHaveCount(1)

    await page.getByTitle('收起').click()
    await expect(page.locator('.ai-chat')).toHaveCount(0)
    await expect(page.locator('[data-testid="composer-input"]')).toHaveCount(0)
    await expect(page.locator('.writing-vertical')).toBeVisible()

    // 收起状态是页签级记忆：刷新仍收起
    await page.reload({ waitUntil: 'domcontentloaded' })
    await expect(page.locator('.writing-vertical')).toBeVisible({ timeout: 30_000 })
    await expect(page.locator('.ai-chat')).toHaveCount(0)

    // 收起期间业务照常（后端历史仍在），重开后消息仍在
    await page.locator('.writing-vertical').click()
    await expect(page.locator('.ai-chat')).toBeVisible()
    await expect(assistantBubbles(page)).toHaveCount(1, { timeout: 30_000 })
  })
})

test.describe('@stub 13a · F 反馈与消息操作', () => {
  test('E2E-F01 赞全链：按钮选中 + POST 200 + GET /feedback 出现 rating=1', async ({ page }) => {
    await openPanel(page)
    await seedRound(page, 'F01')
    const bubbleId = (await assistantBubbles(page).last().getAttribute('data-testid')) ?? ''
    const messageId = bubbleId.replace('msg-bubble-', '')
    const up = page.locator(`[data-testid="feedback-up-${messageId}"]`)
    await expect(up).toBeVisible()

    const post = page.waitForResponse((response) =>
      response.url().includes('/api/ai/feedback') && response.request().method() === 'POST')
    await up.click()
    expect((await post).status()).toBe(200)

    await expect(up).toHaveAttribute('aria-pressed', 'true')
    await expect.poll(async () => {
      const list = await feedbackList(page.request)
      return list.find((item) => item.messageId === messageId)?.rating ?? 0
    }, { timeout: 15_000 }).toBe(1)
  })

  test('E2E-F02 踩 + 正文快照：rating=-1、excerpt 为正文前 200 字（非 ID 片段）', async ({ page }) => {
    await openPanel(page)
    await seedRound(page, 'F02')
    const content = (await assistantBubbles(page).last().innerText()).trim()
    const messageId = ((await assistantBubbles(page).last().getAttribute('data-testid')) ?? '')
      .replace('msg-bubble-', '')

    const down = page.locator(`[data-testid="feedback-down-${messageId}"]`)
    await down.click()
    await expect(down).toHaveAttribute('aria-pressed', 'true')

    await expect.poll(async () => (await feedbackList(page.request)).some((item) => item.messageId === messageId),
      { timeout: 15_000 }).toBe(true)
    const saved = (await feedbackList(page.request)).find((item) => item.messageId === messageId)
    expect(saved?.rating).toBe(-1)
    expect(saved?.excerpt ?? '').toBe(content.slice(0, 200))
    expect(saved?.excerpt ?? '').not.toBe(messageId.slice(0, 8))
    // 后端支持的 reason 字段：面板尚未提供原因入口（发现项 F-02，见施工报告）
    expect(saved?.reason ?? null).toBeNull()
  })

  test('E2E-F03 反馈幂等/重复提交：同一 messageId 只有一条记录，重复点不双发', async ({ page }) => {
    await openPanel(page)
    await seedRound(page, 'F03')
    const messageId = ((await assistantBubbles(page).last().getAttribute('data-testid')) ?? '')
      .replace('msg-bubble-', '')
    const up = page.locator(`[data-testid="feedback-up-${messageId}"]`)
    const down = page.locator(`[data-testid="feedback-down-${messageId}"]`)

    let posts = 0
    page.on('request', (req) => {
      if (req.url().includes('/api/ai/feedback') && req.method() === 'POST') posts += 1
    })

    await up.click()
    await expect(up).toHaveAttribute('aria-pressed', 'true')
    // 再点一次 = 取消（rating=0 ⇒ 删行）；随后改踩
    await up.click()
    await expect(up).toHaveAttribute('aria-pressed', 'false')
    await down.click()
    await expect(down).toHaveAttribute('aria-pressed', 'true')

    expect(posts).toBe(3)
    await expect.poll(async () => (await feedbackList(page.request)).filter((i) => i.messageId === messageId).length,
      { timeout: 15_000 }).toBe(1)
    const saved = (await feedbackList(page.request)).find((item) => item.messageId === messageId)
    expect(saved?.rating).toBe(-1)
    // 本地态互斥：赞不同时选中
    await expect(up).toHaveAttribute('aria-pressed', 'false')
  })

  test('E2E-F04 反馈页列表渲染：时间/摘要/rating/原因四列齐；无数据显空态', async ({ page, request }) => {
    // 夹具：三条反馈（API 直造，避免依赖面板操作顺序）
    for (const rating of [1, -1, 1]) {
      const messageId = `nfezui-f04-${rating}-${unique('m')}`
      const res = await apiRaw(request, 'post', '/api/ai/feedback', {
        messageId,
        sessionId: 'nfezui-f04-session',
        rating,
        excerpt: `反馈页夹具 ${messageId}`
      })
      expect(res.status()).toBe(200)
    }

    await page.goto('/settings/ai-feedback', { waitUntil: 'domcontentloaded' })
    await expect(page.locator('[data-testid="ai-feedback-view"]')).toBeVisible({ timeout: 30_000 })
    const table = page.locator('[data-testid="ai-feedback-table"]')
    await expect(table).toBeVisible({ timeout: 20_000 })
    const headers = await table.locator('thead th').allInnerTexts()
    expect(headers.map((text) => text.trim())).toEqual(['时间', '消息摘要', '评价', '原因'])
    const rows = page.locator('[data-testid="ai-feedback-row"]')
    await expect(rows.first()).toBeVisible()
    expect(await rows.count()).toBeGreaterThanOrEqual(3)
    const first = await rows.first().innerText()
    expect(first).toMatch(/\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}/)
    const all = await table.innerText()
    expect(all).toContain('赞')
    expect(all).toContain('踩')
    expect(all).toContain('—')

    // 空态：接口回空列表（真空表的渲染面，不与「有数据」互串）
    await page.route('**/api/ai/feedback', async (route) => {
      if (route.request().method() === 'GET') {
        await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ success: true, data: [] }) })
        return
      }
      await route.continue()
    })
    await page.reload({ waitUntil: 'domcontentloaded' })
    await expect(page.locator('[data-testid="ai-feedback-table"]')).toHaveCount(0)
    await expect(page.locator('[data-testid="ai-feedback-view"]')).toContainText('暂无')
  })

  test('E2E-F06 编辑用户消息重跑：非空先弹确认、回填原文、发送后截断其后消息并重跑', async ({ page }) => {
    await openPanel(page)
    await seedRound(page, 'F06-一')
    await seedRound(page, 'F06-二')
    await expect(assistantBubbles(page)).toHaveCount(2)
    expect(await userBubbles(page).count()).toBe(2)

    const firstUser = userBubbles(page).first()
    const originalText = (await firstUser.innerText()).trim()
    const editId = ((await firstUser.getAttribute('data-testid')) ?? '').replace('msg-bubble-', '')

    // 输入框非空 ⇒ 先弹 window.confirm（真对话框）
    await page.locator('[data-testid="composer-input"]').fill('草稿占位')
    let dialogText = ''
    page.once('dialog', async (dialog) => {
      dialogText = dialog.message()
      await dialog.accept()
    })
    await page.locator(`[data-testid="edit-msg-${editId}"]`).click()
    await expect.poll(async () => dialogText).not.toBe('')
    expect(dialogText).toContain('覆盖')
    await expect(page.locator('[data-testid="composer-input"]')).toHaveValue(originalText)

    // 改完从该条重跑：该条之后的消息被截断，不新增用户气泡
    const rewritten = `改写后的问题 ${unique('F06')}`
    await page.locator('[data-testid="composer-input"]').fill(rewritten)
    await page.locator('[data-testid="send-btn"]').click()
    await waitSettled(page)

    expect(await userBubbles(page).count()).toBe(1)
    expect((await userBubbles(page).first().innerText()).trim()).toBe(rewritten)
    await expect(assistantBubbles(page)).toHaveCount(1)
  })

  test('E2E-F07 「继续」入口语义：出错轮出继续、被停止轮不出、点击作新用户消息发送', async ({ page }) => {
    await openPanel(page)
    // ① 断流轮（缺终帧 ⇒ lastRoundOutcome=interrupted，且助手消息有半截正文 ⇒ 元信息行在）⇒ 出「继续」
    await fulfillTruncatedStream(page, 'NFZ-F07-STREAM')
    await send(page, 'NFZ-F07-STREAM 用例-F07')
    await expect(page.locator('[data-testid="alert-interrupted"]')).toBeVisible({ timeout: 60_000 })
    await waitSettled(page, 60_000)
    const cont = page.locator('[data-testid="continue-round"]')
    await expect(cont).toBeVisible()

    // 点「继续」= 以新用户消息「继续」重发
    await cont.click()
    await expect(userBubbles(page).last()).toContainText('继续', { timeout: 20_000 })
    await waitSettled(page)

    // ② 被用户停止的轮**不出**「继续」（与「已停止」标识互斥）
    //    换新会话再停：本轮宿主唯一，避免上一轮（断流/继续）的宿主干扰「已停止」落点判定。
    await page.locator('[data-testid="new-chat"]').click()
    await expect(page.locator('[data-testid="empty-state"]')).toBeVisible({ timeout: 20_000 })
    await send(page, 'stall-frames=1500 stop 用例-F07')
    await expect(page.locator('[data-testid="stop-btn"]')).toBeVisible({ timeout: 20_000 })
    // 先等半截正文落地再停：空气泡不打「已停止」标记（markStoppedMessage 只在有正文/工具段时标）
    await expect
      .poll(async () => (await assistantBubbles(page).last().innerText()).trim().length, { timeout: 30_000 })
      .toBeGreaterThan(0)
    await page.locator('[data-testid="stop-btn"]').click()
    await expect(page.locator('[data-testid^="msg-stopped-"]').last()).toBeVisible({ timeout: 20_000 })
    await expect(page.locator('[data-testid="continue-round"]')).toHaveCount(0)
  })

  test('E2E-F08 时间戳 / 呼吸点 / 操作行显现：流式中呼吸、完成后时间戳、操作行常驻可见', async ({ page }) => {
    await openPanel(page)
    await send(page, 'stall-frames=1200 用例-F08')
    // 流式中：助手行元信息位 = 呼吸点，**同一位**不出现时间戳
    const streamingOps = page.locator('[data-testid^="msg-ops-"]').last()
    await expect(streamingOps.locator('[data-testid="loading-breath"]')).toBeVisible({ timeout: 20_000 })
    await expect(streamingOps.locator('[data-testid="msg-timestamp"]')).toHaveCount(0)
    await waitSettled(page, 60_000)
    // 完成后：同一位呼吸点退场、时间戳出现（二者不同框）
    const settledOps = page.locator('[data-testid^="msg-ops-"]').last()
    await expect(settledOps.locator('[data-testid="loading-breath"]')).toHaveCount(0, { timeout: 20_000 })
    await expect(settledOps.locator('[data-testid="msg-timestamp"]')).toBeVisible()

    // 操作行：常驻（R-165 S4 项 1 起撤掉 opacity-0 浮出机制）—— 未 hover 即可见
    const ops = page.locator('[data-testid^="msg-ops-"]').last()
    await expect(ops).toBeVisible()
    expect(await computed(page, '[data-testid^="msg-ops-"]', 'opacity')).toBe('1')
    expect(await computed(page, '[data-testid^="msg-ops-"]', 'visibility')).toBe('visible')
    await ops.hover()
    expect(await computed(page, '[data-testid^="msg-ops-"]', 'opacity')).toBe('1')
  })

  test('E2E-F09 用户长消息折叠：默认折叠、点开展开全文、再点收起、数据不变', async ({ page, request }) => {
    await openPanel(page)
    const longText = `长消息折叠夹具 ${unique('F09')} ` + '这一段用于撑出超过八行的正文，'.repeat(40)
    await send(page, longText)
    await waitSettled(page)
    const sid = await sessionIdOf(page)
    const historyBefore = await historyOf(request, sid)

    const userBubble = userBubbles(page).first()
    const moreId = ((await userBubble.getAttribute('data-testid')) ?? '').replace('msg-bubble-', '')
    const more = page.locator(`[data-testid="user-msg-more-${moreId}"]`)
    await expect(more).toBeVisible({ timeout: 20_000 })
    await expect(more).toHaveAttribute('aria-expanded', 'false')

    const collapsedHeight = (await userBubble.boundingBox())?.height ?? 0
    await more.click()
    await expect(more).toHaveAttribute('aria-expanded', 'true')
    const expandedHeight = (await userBubble.boundingBox())?.height ?? 0
    expect(expandedHeight).toBeGreaterThan(collapsedHeight)
    expect((await userBubble.innerText()).trim()).toContain(longText.trim())

    await more.click()
    await expect(more).toHaveAttribute('aria-expanded', 'false')
    // 折叠不改变会话数据（后端条数与正文一致）
    const historyAfter = await historyOf(request, sid)
    expect(historyAfter.count).toBe(historyBefore.count)
  })

  test('E2E-F10 反馈提交失败不假成功：不写服务端记录、刷新后不残留选中态', async ({ page }) => {
    await openPanel(page)
    await seedRound(page, 'F10')
    const messageId = ((await assistantBubbles(page).last().getAttribute('data-testid')) ?? '')
      .replace('msg-bubble-', '')

    await page.route('**/api/ai/feedback', async (route) => {
      if (route.request().method() === 'POST') {
        await route.fulfill({
          status: 500,
          contentType: 'application/json',
          body: JSON.stringify({ success: false, code: 'INTERNAL_ERROR', message: 'feedback submit failed' })
        })
        return
      }
      await route.continue()
    })

    const up = page.locator(`[data-testid="feedback-up-${messageId}"]`)
    await up.click()
    await page.waitForTimeout(800)
    // 服务端没有这条记录（失败不落库）
    expect((await feedbackList(page.request)).some((item) => item.messageId === messageId)).toBe(false)

    // 刷新后不残留「已反馈」选中态（本地态不持久化）
    await page.unroute('**/api/ai/feedback')
    await page.reload({ waitUntil: 'domcontentloaded' })
    await expect(assistantBubbles(page).last()).toBeVisible({ timeout: 30_000 })
    const reloadedId = ((await assistantBubbles(page).last().getAttribute('data-testid')) ?? '')
      .replace('msg-bubble-', '')
    await expect(page.locator(`[data-testid="feedback-up-${reloadedId}"]`)).toHaveAttribute('aria-pressed', 'false')
  })

  test('E2E-F02b 踩的 aria 语义（真实鼠标）：aria-pressed 与 aria-label 同步切到「取消」', async ({ page }) => {
    await openPanel(page)
    await seedRound(page, 'F02b')
    const messageId = ((await assistantBubbles(page).last().getAttribute('data-testid')) ?? '')
      .replace('msg-bubble-', '')
    const down = page.locator(`[data-testid="feedback-down-${messageId}"]`)
    const box = await down.boundingBox()
    expect(box).not.toBeNull()
    // 真鼠标坐标点击（不走 element.click 的语义替代）
    await page.mouse.move((box?.x ?? 0) + (box?.width ?? 0) / 2, (box?.y ?? 0) + (box?.height ?? 0) / 2, { steps: 6 })
    await page.mouse.down()
    await page.mouse.up()
    await expect(down).toHaveAttribute('aria-pressed', 'true')
    const label = await down.getAttribute('aria-label')
    expect(cjkHits(label ?? '')).not.toHaveLength(0)
    expect(label).toContain('取消')
  })
})
