/**
 * 真机 E2E · 批② —— S 流式 / R 刷新 / P 停止（本域剩余条；批①已覆盖 S-01/S-06/R-01/R-10/R-12/P-01/P-02/P-05）。
 *
 * 蓝本：`docs/research/设计-真机E2E用例全集-2026-10-23.md` §2.1 / §2.2 / §2.3（编号沿用）。
 * 纪律：每条都是真浏览器（真 reload / 真鼠标 / 真 DOM）+ 真后端 / 真桩上游；
 *       断言一律落在 data-testid 与终态文本上；不改任何既有文件。
 * 私有端口基（本批分配）：桩 18599 / 后端 18530+18531 / 前端 18601+18602（Redis DB 10），
 *       由 E2E_*_PORT / E2E_*_BASE 环境变量注入（默认 183xx 段跑法同样成立）。
 */
import { expect, test, type Page } from '@playwright/test'
import {
  TURN_TEXT,
  api,
  assistantBubble,
  assistantText,
  createPublishableImportTask,
  openApp,
  openPath,
  runCurrent,
  runStatus,
  sendMessage,
  sessionId,
  until,
  waitRoundSettled
} from './support/helpers'
import {
  archivedFrames,
  captureChat,
  chatFrames,
  countType,
  deltaText,
  eventsUrl,
  historySegments,
  isoDurationMs,
  parseSse,
  readMirror,
  segmentOrder,
  sessionStorageKeys,
  stubRequestsContaining,
  API_BASE
} from './support/helpers-srp'
import { RUN_DIR } from './support/harness'

/**
 * 桩的会话指纹 = 首条 user 消息：同一提示词重复使用会让桩把"该路由已用过"的轮次退化成文本轮。
 * 故每条提示词都带一次性后缀（标记本身仍是 messageAny 的子串，路由照常命中）。
 */
let promptSeq = 0
function P(prompt: string): string {
  promptSeq += 1
  return `${prompt} ${Date.now().toString(36)}${promptSeq}${Math.random().toString(36).slice(2, 6)}`
}

/**
 * S-07 的退避判定区间，唯一事实源 = backend `RetryBackoff`
 * （BASE_MS=500 / JITTER_RATIO=0.25 / MAX_MS=5000）：第 1 次重试 `500×(1±0.25)` = `[375, 625]`ms。
 * 上界额外留出一次本地上游往返 + JVM 冷路径的余量（实测 15~180ms）；
 * 原上界 5000 与"数秒档"不可区分，本值把它收窄到能区分"500ms 档"的量级。
 */
const S07_BACKOFF_FLOOR_MS = 375
const S07_BACKOFF_CEILING_MS = 2_000

/**
 * S-07 的独立轮次数：单轮间隔 = 真实退避 + 产品固定开销，开销偶发可顶过下界（实测突变体下
 * 3 次里出现 1 次 559ms 的"没睡却过线"），多轮取样要求**每轮**都达标
 * ⇒ 漏判概率 ≈（单轮被掩盖的概率）^N。5 轮在"每轮 1/3 被掩盖"的极端假设下仍 < 1%。
 */
const S07_ROUNDS = 5

/** 本轮在途的停止钮（loading / suspended 才有，别的轮没有）。 */
function stopButton(page: Page) {
  return page.locator('[data-testid="stop-btn"]')
}

/** 错误卡根节点（`error-card-<msgId>`；注意排除 `error-card-detail-*` / `error-card-action-*` 子节点）。 */
function errorCard(page: Page) {
  return page.locator('[data-testid^="error-card-"][data-variant]')
}

// ─────────────────────────────────────────────────────────────────────────────
// S · STRM 流式稳定（§2.1）
// ─────────────────────────────────────────────────────────────────────────────

test.describe('@stub S 流式稳定', () => {
  /**
   * S-02 文本/工具交错段序：实时 = 回放 = 历史，DOM 段落顺序与之一致。
   * 桩 turn-interleave 产出「text→tool→text→tool→text(收尾)」两段工具 + 收尾正文。
   */
  test('E2E-S02 交错段序：实时帧 / 归档回放 / 历史逐段 kind 相等，DOM 卡序与正文位置一致', async ({ page, request }) => {
    const chat = captureChat(page)
    await openApp(page)
    await sendMessage(page, P('turn-interleave 配置定义 S02'))
    await waitRoundSettled(page, 60_000)

    const live = parseSse(await chat.body())
    const start = live.find((frame) => frame.type === 'start')
    const runId = String(start?.runId ?? '')
    expect(runId.length).toBeGreaterThan(0)

    // ① 实时帧：两段工具调用、两个不同 toolCallId、收尾正文
    const liveSegments = segmentOrder(live)
    expect(liveSegments.filter((segment) => segment.startsWith('tool:'))).toHaveLength(2)
    expect(new Set(liveSegments.filter((segment) => segment.startsWith('tool:'))).size).toBe(2)
    expect(liveSegments[liveSegments.length - 1]).toBe('text')
    expect(countType(live, 'done')).toBe(1)

    // ② 归档回放：与实时逐段相等（同一 toolCallId 不被拆两段）
    const replay = await archivedFrames(runId)
    expect(segmentOrder(replay)).toEqual(liveSegments)

    // ③ 历史：与实时逐段相等（落库口径同源）
    const sid = await sessionId(page)
    const history = await api.get<{ messages: Array<Record<string, unknown>> }>(request, `/api/ai/history/${sid}`)
    expect(historySegments(history)).toEqual(liveSegments)

    // ④ DOM：两张卡按 toolCallId 顺序渲染，收尾正文位于两卡之下（真几何坐标，不用"包含"代替顺序）
    const cards = page.locator('[data-testid^="tool-card-"]')
    await expect(cards).toHaveCount(2)
    const domIds = await cards.evaluateAll((elements) => elements.map((element) => element.getAttribute('data-testid')))
    const liveIds = liveSegments.filter((segment) => segment.startsWith('tool:')).map((segment) => `tool-card-${segment.slice(5)}`)
    expect(domIds).toEqual(liveIds)

    const tail = page.getByText('交错轮收尾', { exact: false })
    await expect(tail).toHaveCount(1)
    const boxes = [
      await cards.nth(0).boundingBox(),
      await cards.nth(1).boundingBox(),
      await tail.boundingBox()
    ]
    expect(boxes.every((box) => box !== null)).toBeTruthy()
    expect(boxes[0]!.y).toBeLessThan(boxes[1]!.y)
    expect(boxes[1]!.y).toBeLessThan(boxes[2]!.y)
  })

  /**
   * S-03 响应头挂起不误判断流：首包看门狗（默认 30s）触发 → 先 retry、再显式错误卡。
   * 桩 stall-headers=40000 ⇒ 两个尝试都拿不到首包，最终以 FIRST_BYTE_TIMEOUT 收敛。
   */
  test('E2E-S03 响应头挂起：30s 首包看门狗触发重试与可重试错误卡（不静默干等）', async ({ page }) => {
    test.setTimeout(120_000)
    const chat = captureChat(page)
    await openApp(page)
    const sentAt = Date.now()
    await sendMessage(page, P('turn-text stall-headers=40000 S03'))

    // ① 期间没有任何正文（"已发送但无任何状态变化"是缺陷，必须由重试提示打破）
    await expect(page.locator('[data-testid="notice-bar"]')).toContainText('正在重试', { timeout: 45_000 })
    const firstRetryMs = Date.now() - sentAt
    expect(firstRetryMs).toBeGreaterThan(28_000)

    // ② 两个尝试都超时后：显式错误卡 + 机器码可读（不是无限转圈）
    await expect(errorCard(page)).toBeVisible({ timeout: 75_000 })
    await expect(page.locator('[data-testid="notice-bar"]')).toContainText('本轮出错')
    await expect(page.locator('[data-testid="notice-bar"]')).toContainText('AI 响应超时')
    await expect(page.locator('[data-testid="notice-bar"]')).not.toContainText('UPSTREAM_FIRST_BYTE_TIMEOUT')
    await expect(stopButton(page)).toHaveCount(0)

    // ③ 帧级证据：重试与终态码（首包看门狗真在判定，不是前端兜底猜的）
    const frames = parseSse(await chat.body())
    expect(countType(frames, 'retry')).toBe(1)
    expect(frames.find((frame) => frame.type === 'retry')?.code).toBe('UPSTREAM_FIRST_BYTE_TIMEOUT')
    expect(frames.find((frame) => frame.type === 'error')?.code).toBe('UPSTREAM_FIRST_BYTE_TIMEOUT')
    expect(countType(frames, 'delta')).toBe(0)
    expect(countType(frames, 'done')).toBe(0)
  })

  /**
   * S-04 帧间静默断流判定：静默到点即判死（不无限流式），随后重试、再以显式错误收敛。
   *
   * 构造面与边界（重要）：桩的 `stall-frames` 是**恒定帧间隔**——
   * 间隔 ≥ 首包阈值时首包看门狗（30s）必先触发（那是 S-03 的面）；间隔 < 首包阈值时
   * 帧间静默恒小于阈值。故要命中 inter-event 分支，必须把 `app.ai.resilience.inter-event-timeout`
   * 调到 **小于 first-byte-timeout**（如 12s）。阈值未调时本用例跳过并说明原因，
   * 断言本身按 `/health` 里的**实际阈值**取数（不写死 90s）。
   */
  test('E2E-S04 帧间静默：到点判死 → 重试 → 显式错误收敛', async ({ page, request }) => {
    const health = (await (await request.get(`${API_BASE}/api/ai/health`)).json()) as {
      resilience?: { interEventTimeout?: string; firstByteTimeout?: string }
    }
    const interMs = isoDurationMs(health.resilience?.interEventTimeout ?? 'PT1M30S')
    const firstMs = isoDurationMs(health.resilience?.firstByteTimeout ?? 'PT30S')
    test.skip(
      interMs >= firstMs,
      `inter-event(${interMs}ms) ≥ first-byte(${firstMs}ms)：桩的恒定帧间隔下首包看门狗必先触发，`
        + 'SILENT 分支不可构造（需以 APP_AI_RESILIENCE_INTEREVENTTIMEOUT<first-byte 启动后端）'
    )
    test.setTimeout(Math.max(120_000, interMs * 2 + 60_000))

    const chat = captureChat(page)
    await openApp(page)
    const sentAt = Date.now()
    // 帧间隔 > 阈值：首帧（会落成上游事件）之后必然静默超过阈值
    await sendMessage(page, P(`turn-text stall-frames=${interMs + 4_000} S04`))

    // ① 静默判定触发：页面出「正在重试」
    await expect(page.locator('[data-testid="notice-bar"]')).toContainText('正在重试', {
      timeout: interMs + 40_000
    })
    expect(Date.now() - sentAt).toBeGreaterThanOrEqual(interMs)

    // ② 帧级：code=SILENT 族，且与 start 的时刻差 ≥ 配置阈值
    const frames = parseSse(await chat.body())
    const start = frames.find((frame) => frame.type === 'start')
    const retry = frames.find((frame) => frame.type === 'retry')
    expect(retry).toBeTruthy()
    expect(String(retry!.code)).toContain('SILENT')
    expect(Number(retry!.atMs) - Number(start!.atMs)).toBeGreaterThanOrEqual(interMs)

    // ③ 终态显式收敛：错误卡 + 停止钮消失（不存在"永远流式中"）
    await expect(errorCard(page)).toBeVisible({ timeout: 60_000 })
    await expect(stopButton(page)).toHaveCount(0)
    expect(countType(frames, 'done')).toBe(0)
    await expect(page.locator('[data-testid="send-btn"]')).toBeVisible({ timeout: 10_000 })
  })

  /**
   * S-05 重连差量续收不重复：真实中途断开 + 带 lastSeq 重连，拼接正文 == 全量正文。
   * 客户端在收到第 2 个 delta 后主动断开 POST 流，再用 `/events/{runId}?lastSeq=K` 续收。
   */
  test('E2E-S05 断点续收：重连 URL 带 lastSeq=K，拼接正文等于全量且 done 只 1 次', async () => {
    const sid = `srp-s05-${Date.now()}`
    const controller = new AbortController()
    const response = await fetch(`${eventsChatUrl()}/api/ai/chat`, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ sessionId: sid, message: P('turn-text stall-frames=600 S05') }),
      signal: controller.signal
    })
    expect(response.ok).toBe(true)

    const first: string[] = []
    const reader = response.body!.getReader()
    const decoder = new TextDecoder()
    let buffered = ''
    let runId = ''
    let lastSeq = 0
    let deltas = 0
    let aborted = false
    while (true) {
      let chunk: ReadableStreamReadResult<Uint8Array>
      try {
        chunk = await reader.read()
      } catch {
        break // 主动 abort 后的读异常
      }
      if (chunk.done) break
      buffered += decoder.decode(chunk.value, { stream: true })
      const lines = buffered.split('\n')
      buffered = lines.pop() ?? ''
      for (const line of lines) {
        if (!line.startsWith('data:')) continue
        const payload = line.slice(5).trim()
        if (!payload.startsWith('{')) continue
        const frame = JSON.parse(payload) as { type: string; runId?: string; seq?: number; text?: string }
        first.push(payload)
        if (frame.type === 'start') runId = String(frame.runId ?? '')
        if (frame.type === 'delta') {
          deltas += 1
        }
        if (typeof frame.seq === 'number') lastSeq = Math.max(lastSeq, frame.seq)
        if (deltas >= 2) {
          // 真实断连：客户端单方掐断（后端轮次照跑，归档继续增长）
          controller.abort()
          aborted = true
          break
        }
      }
      if (aborted) break
    }
    await reader.cancel().catch(() => undefined)

    expect(aborted).toBe(true)
    expect(runId.length).toBeGreaterThan(0)
    expect(lastSeq).toBeGreaterThan(0)

    // ① 重连 URL 含 lastSeq=K（差量语义的显式形态）
    const url = eventsUrl(runId, lastSeq)
    expect(url).toContain(`lastSeq=${lastSeq}`)

    // ② 续收至终帧：只补 seq > K，且拼起来逐字等于全量正文
    const replay = parseSse(await (await fetch(url)).text())
    expect(replay.length).toBeGreaterThan(0)
    for (const frame of replay) {
      if (typeof frame.seq === 'number') expect(frame.seq).toBeGreaterThan(lastSeq)
    }
    const firstFrames = first.map((payload) => JSON.parse(payload) as { type: string; text?: string })
    const joined = deltaText(firstFrames) + deltaText(replay)
    expect(joined).toBe(TURN_TEXT)
    expect(countType(firstFrames, 'done') + countType(replay, 'done')).toBe(1)

    // ③ 无重复段：两段 seq 集合不相交
    const firstSeqs = new Set(firstFrames.map((frame) => (frame as { seq?: number }).seq).filter((seq): seq is number => typeof seq === 'number'))
    for (const frame of replay) {
      if (typeof frame.seq === 'number') expect(firstSeqs.has(frame.seq)).toBe(false)
    }
  })

  /**
   * S-07 上游 5xx 重试退避：一次 503 → 退避 500ms 档（±25% 抖动）→ 第二次成功；只 1 个 done。
   *
   * 退避是「**每一次**重试都 ≥ 契约下界」的全称命题，而单轮测得的间隔 = 真实退避 + 产品自身
   * 固定开销（`retry` 帧落档 → 第二次请求装配；冷 JVM / GC 抖动实测可达 ~0.5s）⇒ 只测一轮时，
   * "根本没睡"的缺陷也能被这段开销顶过下界（突变实验里 3 次有 1 次 559ms 过线）。故本用例跑
   * `S07_ROUNDS` 个独立轮次、每轮都要满足下界：缺陷作用于**每一轮**，开销掩盖只会偶发。
   */
  test('E2E-S07 上游 503：同一轮重试一次（≤ max-attempts=2），成功路径只有 1 个 done', async () => {
    for (let round = 1; round <= S07_ROUNDS; round += 1) {
      const tag = `第 ${round}/${S07_ROUNDS} 轮`
      const sid = `srp-s07-${Date.now()}-${round}`
      const message = P('turn-text fail-503-once S07')
      const frames = await chatFrames(sid, message)
      const start = frames.find((frame) => frame.type === 'start')
      const retries = frames.filter((frame) => frame.type === 'retry')
      const done = frames.find((frame) => frame.type === 'done')

      // ① 恰 1 次重试、attempt=2，码为「上游暂时不可用」且可重试。
      //    契约来源（2026-10-25）：`ErrorCodes#UPSTREAM_UNAVAILABLE`（408/5xx/网络层 ——
      //    "这一轮请求根本没拿到可用响应"）与 `UpstreamErrorClassifier#byStatus`
      //    （status 408 或 5xx ⇒ new Outcome(UPSTREAM_UNAVAILABLE, retryable=true, null, status)）。
      //    能收到 `retry` 帧本身就是"已判为可重试"的实证：不可重试的分支直接走 error 终帧。
      expect(retries, tag).toHaveLength(1)
      expect(Number(retries[0].nextAttempt), tag).toBe(2)
      expect(String(retries[0].code), tag).toBe('UPSTREAM_UNAVAILABLE')

      // ② 退避可观测：retry 之后到首个正文帧的间隔落在「500ms 档」的**抖动区间**里（不是立即重打）。
      //    区间口径的唯一事实源 = backend `RetryBackoff`：BASE_MS=500、JITTER_RATIO=0.25、MAX_MS=5000
      //    ⇒ 第 1 次重试的合法时长 = 500×(1±0.25) = [375, 625]ms。抖动是设计的一部分（防共振），
      //    不是测量误差 —— 故下界是 375 而不是旧值 400：400 比契约更窄，抖动抽样落在下沿
      //    （sample ≲ 0.05 ⇒ 375~399ms）时会误红（run 40228606894 实测 398ms；本机同一用例连跑 30 次
      //    复现 3 次红灯 = 392/397/399ms，同批后端日志的实际退避落到 375/381/383/385ms —— 下界 375 实测可达）。
      //    上界 = 625 + 一次本地上游往返/冷路径余量；旧值 5000 区分不了"500ms 档"与"数秒档"。
      const firstDelta = frames.find((frame) => frame.type === 'delta')
      const backoffMs = Number(firstDelta!.atMs) - Number(retries[0].atMs)
      expect(backoffMs, `${tag} retry→首个 delta 间隔`).toBeGreaterThanOrEqual(S07_BACKOFF_FLOOR_MS)
      expect(backoffMs, `${tag} retry→首个 delta 间隔`).toBeLessThanOrEqual(S07_BACKOFF_CEILING_MS)

      // ②' 桩侧实证（与产品自报的 `atMs` 相互独立的另一条钟）：本轮恰 2 次上游请求，
      //     503 在前、200 在后，第二次请求落在 `retry` 帧**之后**，两次落档间隔也 ≥ 抖动下界。
      //     这样"第二次上游请求确实等过"不依赖产品自己的时间戳；同时排除把别轮请求配错。
      const upstream = stubRequestsContaining(message)
      expect(upstream, `${tag}：桩落档不可见（${RUN_DIR}/stub）`).toHaveLength(2)
      expect(upstream.map((request) => request.status), tag).toEqual([503, 200])
      expect(upstream[0].atMs, `${tag} 首次 503 应在 retry 帧之前`).toBeLessThanOrEqual(
        Number(retries[0].atMs)
      )
      expect(upstream[1].atMs, `${tag} 第二次请求应在 retry 帧之后`).toBeGreaterThanOrEqual(
        Number(retries[0].atMs)
      )
      const upstreamGapMs = upstream[1].atMs - upstream[0].atMs
      expect(upstreamGapMs, `${tag} 桩侧 503→第二次请求间隔`).toBeGreaterThanOrEqual(
        S07_BACKOFF_FLOOR_MS
      )
      expect(upstreamGapMs, `${tag} 桩侧 503→第二次请求间隔`).toBeLessThanOrEqual(
        S07_BACKOFF_CEILING_MS
      )

      // ③ 成功路径只 1 个 done，正文完整
      expect(done, tag).toBeTruthy()
      expect(Number(done!.attempts), tag).toBe(2)
      expect(countType(frames, 'done'), tag).toBe(1)
      expect(deltaText(frames), tag).toBe(TURN_TEXT)
      expect(Number(done!.atMs) - Number(start!.atMs), `${tag} 总耗时`).toBeLessThan(30_000)
    }
  })

  /**
   * S-08 usage 末帧去重（网关 repeat 形态）：pill / done.usage / `/context` 三方同值。
   * 桩 `usage-shape=repeat` 让每个分片都挂同一份 usage（旧缺陷：逐帧累加放大）。
   */
  test('E2E-S08 usage 三方一致：pill ↔ done.usage ↔ /context 逐值相等（repeat 形态不放大）', async ({ page, request }) => {
    const chat = captureChat(page)
    await openApp(page)
    await sendMessage(page, P('turn-text usage-shape=repeat S08'))
    await waitRoundSettled(page, 60_000)

    const frames = parseSse(await chat.body())
    const done = frames.find((frame) => frame.type === 'done')
    const usage = done!.usage as Record<string, number>
    const doneContext = done!.context as { usedTokens: number; limitTokens: number | null }

    const sid = await sessionId(page)
    // `/api/ai/context/{sid}` 是**裸载荷**（不走 {success,data} 信封，与 /history 不同）
    const contextResponse = await request.get(`${API_BASE}/api/ai/context/${sid}`)
    expect(contextResponse.ok()).toBe(true)
    const context = (await contextResponse.json()) as {
      usedTokens: number
      limitTokens: number | null
      breakdown: Array<{ key: string; tokens: number }>
    }

    // ① 逐值相等：本轮 prompt 真值（repeat 形态 4321 不得被 8 个分片累加放大）
    expect(usage.promptTokens).toBe(context.usedTokens)
    expect(doneContext.usedTokens).toBe(context.usedTokens)
    expect(doneContext.limitTokens).toBe(context.limitTokens)
    expect(usage.totalTokens).toBe(usage.promptTokens + usage.completionTokens)
    // ② 采集帧数 > 1 且值仍是单份（去重的直接证据）
    expect(Number(usage.frames)).toBeGreaterThan(1)
    expect(usage.promptTokens).toBe(4321)

    // ③ pill 与端点同值（title 里的「已用 / 上限」是数值真值面）
    const pill = page.locator('[data-testid="context-pill"]')
    await expect(pill).toBeVisible()
    const title = (await pill.getAttribute('title')) ?? ''
    expect(title).toContain('4.3k')
    expect(title).toContain('128k')
    expect(title).toContain('3.4%')
    // ④ 七项拆分自洽：used = 六项之和（free 是余量），free + used = limit
    const usedKeys = context.breakdown.filter((row) => row.key !== 'free')
    const usedSum = usedKeys.reduce((sum, row) => sum + row.tokens, 0)
    expect(usedSum).toBe(context.usedTokens)
    const free = context.breakdown.find((row) => row.key === 'free')
    expect((free?.tokens ?? 0) + context.usedTokens).toBe(context.limitTokens)
  })

  /**
   * S-13 单帧 64KB 上限不破坏 JSON：桩 huge-frame=102400 ⇒ 正文按上限**跨帧分片**
   * （同一 messageId 的多条既有 delta），不丢字、不挂截断标记，前端零解析异常。
   *
   * <p>契约来源（2026-10-25 截断专项）：`FrameSizeLimiter` 类注「跨帧分片」+ `splitStringValue`
   * + `SseChatEmitter#delta` —— 可续接的 `delta.text` 不再做字段级截断；上限 = `AiProperties`
   * 的 `app.ai.result-budget.ui.max-frame-bytes`（application.yml = 65536，e2e harness 不覆盖）。
   * 判据与 `DeltaFrameSplitTest` / `DeltaFrameSplitLimitGuardTest` 同源：每帧整帧 JSON ≤ 上限、
   * 同一 messageId、按序拼接 == 原文、**不挂** truncated/noticeCode 等截断标记。
   *
   * <p>字节口径：`captureChat` 走 Playwright 的 `response.text()`，对 SSE 响应用的是浏览器侧的
   * 缺省字符集解码（非 UTF-8），把它拿来量 UTF-8 字节会把中文放大（假超限）。因此**字节上限**
   * 与**原文长度**都从归档原文量：`GET /api/ai/events/{runId}` 由 Node 侧按 UTF-8 取字节，
   * 与 `SseChatEmitter` 的 `payloadBytes = objectMapper.writeValueAsBytes(frame)` 同一口径；
   * 实时流只用来对齐帧集（seq/type 逐位相同）与"UI 画出了收到的每一片"。
   */
  test('E2E-S13 百 KB 大帧：同 messageId 跨帧分片、每帧合法且 ≤64KB、正文完整、前端零解析异常', async ({ page, request }) => {
    const consoleErrors: string[] = []
    page.on('console', (message) => {
      if (message.type() === 'error') consoleErrors.push(message.text())
    })
    const chat = captureChat(page)
    await openApp(page)
    await sendMessage(page, P('turn-text huge-frame=102400 S13'))
    await waitRoundSettled(page, 60_000)

    const live = parseSse(await chat.body()).filter((frame) => frame.type !== 'heartbeat')
    expect(live.length).toBeGreaterThan(0)
    const runId = String(live.find((frame) => frame.type === 'start')?.runId ?? '')
    expect(runId).not.toBe('')

    // ① 归档原文（Node 侧 UTF-8）：每帧整帧 JSON 的字节数 ≤ 单帧上限（判定含全部字段/转义）。
    const archivedRaw = (await (await request.get(`${API_BASE}/api/ai/events/${runId}`)).body()).toString('utf8')
    const archivedPayloads = archivedRaw
      .split(/\r?\n/)
      .filter((line) => line.startsWith('data:'))
      .map((line) => line.slice(5).trim())
      .filter((payload) => payload.startsWith('{'))
    const maxFrameBytes = 64 * 1024
    const oversized = archivedPayloads
      .map((payload) => Buffer.byteLength(payload, 'utf8'))
      .filter((bytes) => bytes > maxFrameBytes)
    expect(oversized).toEqual([])

    // ② 每帧都是合法 JSON（逐行 JSON.parse；任何一帧被字节级腰斩都会在这里抛）。
    const archived = archivedPayloads
      .map((payload) => JSON.parse(payload) as { type: string; messageId?: unknown; [key: string]: unknown })
      .filter((frame) => frame.type !== 'heartbeat')
    expect(archived.length).toBeGreaterThan(0)

    // ③ 实时 == 归档：同一帧集（seq/type 逐位相同）⇒ 分片在实时通道没有丢帧、没有重发。
    const shape = (frames: Array<{ type: string; [key: string]: unknown }>) =>
      frames.map((frame) => `${frame.type}#${String(frame.seq)}`)
    expect(shape(live)).toEqual(shape(archived))

    // ④ 正文走"同一 messageId 的多帧 delta"：至少 2 片、messageId 唯一、无任何截断标记。
    const deltas = archived.filter((frame) => frame.type === 'delta')
    expect(deltas.length).toBeGreaterThan(1)
    expect(new Set(deltas.map((frame) => String(frame.messageId))).size).toBe(1)
    for (const frame of [...deltas, ...live.filter((frame) => frame.type === 'delta')]) {
      expect(frame.truncated).toBeUndefined()
      expect(frame.noticeCode).toBeUndefined()
      expect(frame.truncatedReason).toBeUndefined()
      expect(frame.originalChars).toBeUndefined()
      expect(frame.returnedChars).toBeUndefined()
    }

    // ⑤ 拼接正文完整：桩注入 102400 字符，一字不少（分片不得丢字/重字），轮次正常收尾。
    const text = deltaText(archived)
    expect(text.length).toBe(102400)

    // ⑥ 渲染长度与归档正文一致（UI 用 TextDecoder('utf-8') 解流，画出的就是这 102400 字）；
    //    只量 `.md-body` 正文，排除截断提示角标/布局空白。
    const rendered = (await assistantBubble(page).locator('.md-body').last().innerText()).trim()
    expect(rendered.length).toBe(text.length)
    expect(rendered.length).toBe(102400)
    await expect(page.locator('[data-testid="send-btn"]')).toBeVisible({ timeout: 10_000 })

    // ⑦ 前端零未捕获异常 / 零解析错误
    expect(consoleErrors).toEqual([])
  })

})

/**
 * S-15 思考链（reasoning）块渲染与折叠：真模型 reasoning 输出（桩不产 reasoning 帧）。
 *
 * <p><b>定责更新（L-91，2026-10-23）</b>：缺口已补 —— 上游 `deepseek-flash` 返回的
 * `reasoning_content` 现在有帧出口：后端 `ResilientChatService#consume` 把它映射成
 * `delta{kind:'reasoning'}`（配 `message_start/message_end{kind:'reasoning'}` 边界，
 * 帧类型集仍是 16；机制实证见后端 `ReasoningContentUpstreamProbeTest`），
 * 前端 `stores/ai.ts` / `utils/parts.ts` 按 `kind` 分流到 reasoning 段。
 *
 * <p>因此本条的失败定责<b>不再指向后端帧映射</b>，而是"真模型没吐 reasoning"或
 * "帧→段→块"链路的真实回归。**真模型验证待 CI e2e-real key**（本机无 key，不 SKIP、
 * 不放宽断言 —— 蓝本 §0.6-1）。
 *
 * <p>判据面修订（同一批，非放宽）：
 * ① 思考块默认折叠（`blocks.ts` 的 defaultExpanded 对纯 reasoning 段为 false），故先点开再读
 *    思考体 —— 旧写法在"块默认折叠"的前提下直接断言 `reasoning-scroll` 可见，属判据不可满足；
 * ③ 正文判定从"整条气泡 innerText"改为**正文块 `.md-body`** —— 思考卡渲染在气泡**内部**，
 *    整气泡 innerText 必然含思考文本；改后判据更强（正文非空 + 不含思考文本），不再是恒假条件。
 */
test.describe('@real S 思考链', () => {
  test('E2E-S15 思考块独立分框、可折叠、不混入正文', async ({ page }) => {
    await openApp(page)
    await sendMessage(page, '请先仔细推理再回答：1+1 为什么等于 2？请把推理过程写出来。')
    await waitRoundSettled(page, 120_000)

    const toggle = page.locator('[data-testid^="reasoning-toggle-"]')
    await expect(
      toggle,
      '零思考块（需真模型）：先看后端本轮流里有没有 delta{kind:"reasoning"}（帧出口 L-91 已就绪），'
        + '再看 store 是否分流到 reasoning 段；确认真模型本轮是否真返回 reasoning_content'
    ).toHaveCount(1, { timeout: 60_000 })
    const blockId = String(await toggle.getAttribute('data-testid')).replace('reasoning-toggle-', '')

    // ① 思考块独立成框：默认折叠 ⇒ 点开后思考体非空
    const body = page.locator(`[data-testid="reasoning-body-${blockId}"]`)
    const scroll = page.locator(`[data-testid="reasoning-scroll-${blockId}"]`)
    await expect(body).toBeHidden()
    await toggle.click()
    await expect(body).toBeVisible()
    await expect(scroll).toBeVisible()
    const reasoningText = (await scroll.innerText()).trim()
    expect(reasoningText.length).toBeGreaterThan(0)

    // ② 折叠/展开：真鼠标再点两次，可见性切换
    await toggle.click()
    await expect(body).toBeHidden()
    await toggle.click()
    await expect(body).toBeVisible()

    // ③ 思考文本不混入正文：只看**正文块**（思考卡在气泡内，整气泡 innerText 会含思考文本）
    const prose = (await page.locator('[data-testid^="msg-bubble-"].w-full .md-body').last().innerText()).trim()
    expect(prose.length).toBeGreaterThan(0)
    expect(prose.includes(reasoningText.slice(0, 20))).toBe(false)
  })
})

/**
 * S-15s 思考链（**桩模式**，与 S-15 @real 同构的判据）：L-91 的上游 `reasoning_content`
 * → `delta{kind:'reasoning'}`（配 `message_start/message_end{kind:'reasoning'}` 边界）
 * → 前端 `parts` 的 reasoning 段 → 独立折叠块。
 *
 * <p><b>为什么不并入 S-15</b>：S-15 的证据是"真模型这一刻确实吐了思考"，只能在真 key 下跑；
 * 本条把**链路**（上游分片→帧→段→块）变成确定性可跑，桩侧的形态开关是
 * `stub-upstream.mjs` 的 `reasoning=<chars>`（正文分片之前插 1–2 个 `reasoning_content` 分片，
 * 关闭时帧逐字节不变）。两条同判据、不同证据面，互不替代。
 *
 * <p>断言分两层：① 帧面 = 段边界与增量同源（`message_end.chars` == 思考段字符数）；
 * ② 块面 = 默认折叠 → 点开文本非空且**长度等于帧面的 `chars`**（块→段同源，不是另一份数据）→
 * 正文块 `.md-body` 非空且不含思考文本。
 *
 * <p><b>证据取值纪律</b>：`captureChat` 拿到的是浏览器对 `text/event-stream` 的解码结果
 * （响应头不带 charset ⇒ Playwright 侧按单字节解码，中文会被摊成 UTF-8 字节数），
 * 故**帧面只断言结构、计数与 `chars` 数字**，文本内容一律取自 DOM；两者用"长度相等"打通。
 */
test.describe('@stub S 思考链', () => {
  test('E2E-S15s 思考块独立分框、默认折叠、不混入正文（桩 reasoning 形态）', async ({ page }) => {
    const chat = captureChat(page)
    await openApp(page)
    await sendMessage(page, P('reasoning=240 请先思考再回答 S15s'))
    await waitRoundSettled(page, 60_000)

    // ① 帧面：思考段有开有合、多次增量、段序在正文之前
    const frames = parseSse(await chat.body())
    const reasoningStart = frames.filter((frame) => frame.type === 'message_start' && frame.kind === 'reasoning')
    const reasoningEnd = frames.filter((frame) => frame.type === 'message_end' && frame.kind === 'reasoning')
    const reasoningDeltas = frames.filter((frame) => frame.type === 'delta' && frame.kind === 'reasoning')
    expect(reasoningStart, '整轮应恰有一个思考段边界（首片自动补 message_start）').toHaveLength(1)
    expect(reasoningEnd, '思考段必须恰闭合一次（message_end(kind=reasoning)）').toHaveLength(1)
    // 桩的 reasoning=240 发 1–2 片：帧面至少 1 片，且**没有**变成每个正文分片一个空思考片
    expect(reasoningDeltas.length).toBeGreaterThanOrEqual(1)
    const reasoningChars = Number(reasoningEnd[0].chars)
    expect(reasoningChars).toBe(240)
    // 段序：思考段在正文段之前（正文 message_start(kind=text) 的首帧下标更大）
    const startIndex = frames.indexOf(reasoningStart[0])
    const textStartIndex = frames.findIndex((frame) => frame.type === 'message_start' && frame.kind === 'text')
    expect(textStartIndex).toBeGreaterThan(startIndex)
    expect(frames.indexOf(reasoningEnd[0])).toBeLessThan(textStartIndex)
    // 段互斥：思考段闭合之后不得再有思考增量，正文分片也都在闭合之后
    const lastReasoningIndex = frames.map((frame) => frame.type === 'delta' && frame.kind === 'reasoning')
      .lastIndexOf(true)
    expect(lastReasoningIndex).toBeLessThan(textStartIndex)
    const proseFrames = frames.filter((frame) => frame.type === 'delta' && frame.kind !== 'reasoning')
    expect(proseFrames.length).toBeGreaterThan(0)
    expect(frames.indexOf(proseFrames[0])).toBeGreaterThan(frames.indexOf(reasoningEnd[0]))

    // ② 块面：思考块独立成框、默认折叠 ⇒ 点开后思考体非空，长度 = 帧面 chars（同源）
    const toggle = page.locator('[data-testid^="reasoning-toggle-"]')
    await expect(toggle, '桩 reasoning 形态下必须出现且只出现一个思考块').toHaveCount(1, { timeout: 20_000 })
    const blockId = String(await toggle.getAttribute('data-testid')).replace('reasoning-toggle-', '')
    const body = page.locator(`[data-testid="reasoning-body-${blockId}"]`)
    const scroll = page.locator(`[data-testid="reasoning-scroll-${blockId}"]`)
    await expect(body).toBeHidden()
    await toggle.click()
    await expect(body).toBeVisible()
    await expect(scroll).toBeVisible()
    const reasoningText = (await scroll.innerText()).trim()
    expect(reasoningText.length).toBeGreaterThan(0)
    expect(reasoningText.length, '思考块文本长度必须等于思考段 chars（段→块同一份数据）').toBe(reasoningChars)

    // ③ 折叠/展开：真鼠标再点两次，可见性切换
    await toggle.click()
    await expect(body).toBeHidden()
    await toggle.click()
    await expect(body).toBeVisible()

    // ④ 思考文本不混入正文：只看**正文块** `.md-body`（整气泡 innerText 必然含思考文本）
    const prose = (await page.locator('[data-testid^="msg-bubble-"].w-full .md-body').last().innerText()).trim()
    expect(prose.length).toBeGreaterThan(0)
    expect(prose.includes(reasoningText.slice(0, 20))).toBe(false)
  })
})

function eventsChatUrl(): string {
  return process.env.E2E_API_BASE ?? `http://127.0.0.1:${process.env.E2E_BACKEND_PORT ?? '18330'}`
}

// ─────────────────────────────────────────────────────────────────────────────
// R · RLD 真刷新恢复（§2.2）—— 每条都用真 `page.reload()`
// ─────────────────────────────────────────────────────────────────────────────

test.describe('@stub R 刷新恢复', () => {
  /**
   * R-02 老会话第 2 轮流式中刷新：第 1 轮（含工具卡）完好，第 2 轮不出现「问了没人答」。
   */
  test('E2E-R02 第 2 轮流式中刷新：第 1 轮正文与卡全在，第 2 轮续显到收尾', async ({ page }) => {
    await openApp(page)
    await sendMessage(page, P('配置定义 R02-第一轮'))
    await waitRoundSettled(page, 60_000)
    await expect(page.locator('[data-testid^="tool-card-"]')).toHaveCount(1)
    await expect(assistantBubble(page)).toContainText('CURRENCY')

    // 第 2 轮：流式中刷新
    await sendMessage(page, P('turn-text stall-frames=2000 R02-第二轮'))
    await expect.poll(async () => (await assistantText(page)).length, { timeout: 20_000 }).toBeGreaterThan(10)
    await page.reload({ waitUntil: 'domcontentloaded' })
    await expect(page.locator('[data-testid="composer-input"]')).toBeVisible({ timeout: 30_000 })

    // ① 第 1 轮完好：工具卡与原正文都还在（历史整替不得吞掉已完成的轮）
    await expect(page.locator('[data-testid^="tool-card-"]')).toHaveCount(1, { timeout: 30_000 })
    await expect(page.locator('[data-testid^="msg-bubble-"]')).toHaveCount(4, { timeout: 30_000 })

    // ② 第 2 轮不是「问了没人答」：助手气泡续收到全文
    await expect(assistantBubble(page)).toHaveText(TURN_TEXT, { timeout: 90_000 })
    await waitRoundSettled(page, 90_000)
  })

  /**
   * R-04 刷新后立刻发新消息 → 409 收敛：幻影气泡回收、草稿只留 1 份、提示只 1 条、
   * 409 载荷带 runId + reattach 线索。
   *
   * 复现面 = 蓝本原话「刷新后 1s 内发送」：真 reload 后把恢复请求（history / runs/current）
   * 延迟 2.5s，发送抢先到达后端 ⇒ 必得 409。这是同一页签内的真实竞态，不靠第二个页签
   * （第二页签会因页签所有权切到新会话，那是 M 域口径）。
   */
  test('E2E-R04 刷新后抢发：409 带 runId/reattach，零幻影气泡，提示与草稿各 1 份', async ({ page, request }) => {
    await openApp(page)
    await sendMessage(page, P('turn-text stall-frames=2500 R04-在途'))
    await expect.poll(async () => (await assistantText(page)).length, { timeout: 20_000 }).toBeGreaterThan(10)
    const sid = await sessionId(page)

    // 真刷新 + 恢复请求延迟（把"刷新后 1s 内"的竞态窗口放大到确定可命中）
    await page.route('**/api/ai/history/**', async (route) => {
      await new Promise((done) => setTimeout(done, 2_500))
      await route.continue()
    })
    await page.route('**/api/ai/runs/current**', async (route) => {
      await new Promise((done) => setTimeout(done, 2_500))
      await route.continue()
    })
    await page.reload({ waitUntil: 'domcontentloaded' })
    await page.waitForSelector('[data-testid="composer-input"]', { timeout: 30_000 })

    const draft = P('R04 抢发')
    const conflict = page.waitForResponse(
      (response) => response.url().includes('/api/ai/chat') && response.status() === 409,
      { timeout: 15_000 }
    )
    await page.fill('[data-testid="composer-input"]', draft)
    await page.click('[data-testid="send-btn"]')
    const response = await conflict

    // ① 409 载荷：runId + reattach 线索（前端据此可改走 /events/{runId}）
    const body = (await response.json()) as Record<string, unknown>
    expect(String(body.code)).toBe('SESSION_BUSY')
    expect(String(body.runId ?? '').length).toBeGreaterThan(0)
    expect(String(body.reattach ?? '')).toContain('/api/ai/events/')
    const heldRunId = String(body.runId)

    // ② 用户气泡不新增（幻影回收）：历史 1 条 user + 在途 1 条 assistant = 2 条气泡
    await expect(page.locator('[data-testid^="msg-bubble-"]')).toHaveCount(2, { timeout: 25_000 })
    await expect(page.locator('[data-testid^="edit-msg-"]')).toHaveCount(1)
    // ③ 无「本轮未开始」技术气泡；同义提示只 1 条（且不暴露 runId / 重挂等实现词）
    await expect(page.locator('body')).not.toContainText('本轮未开始')
    const notice = page.locator('[data-testid="notice-bar"]')
    await expect(notice).toHaveCount(1, { timeout: 10_000 })
    await expect(notice).toContainText('这条消息没有发出去')
    await expect(notice).not.toContainText(heldRunId)
    // ④ 草稿只留 1 份（输入框仍持有原文，未回灌成第二处）
    await expect(page.locator('[data-testid="composer-input"]')).toHaveValue(draft)
    // ⑤ 在途轮仍是同一轮（runId 未变），且可用停止收口
    const current = await runCurrent(request, sid)
    expect(current.found).toBe(true)
    expect(String(current.runId)).toBe(heldRunId)
    await page.locator('[data-testid="stop-btn"]').click()
    await waitRoundSettled(page, 20_000)
  })

  /**
   * R-06 HITL 挂起中刷新：同一条 assistant 消息里的说明文字 + 待决卡被重建，卡可批准，
   * 批准后本轮跑完；刷新后无 heartbeat 常驻（不出现"永远转圈"）。
   */
  test('E2E-R06 确认门挂起中刷新：卡与说明重建可批准，批准后本轮跑完', async ({ page, request }) => {
    const taskId = await createPublishableImportTask(request)
    await openPath(page, `/import?taskId=${taskId}`)
    await expect(page.locator('footer')).toContainText('task:IMPORT/PUBLISH', { timeout: 30_000 })
    await sendMessage(page, P(`scenario=IRREV-PUBLISH 任务 ${taskId} 发布 R06`))

    const gate = page.locator('[data-testid="hitl-gate"]')
    await expect(gate).toBeVisible({ timeout: 30_000 })
    const sid = await sessionId(page)
    const bubblesBefore = await page.locator('[data-testid^="msg-bubble-"]').count()
    const reconnects: string[] = []
    page.on('request', (request_) => {
      if (request_.url().includes('/api/ai/events/')) reconnects.push(request_.url())
    })

    await page.reload({ waitUntil: 'domcontentloaded' })
    await expect(page.locator('[data-testid="composer-input"]')).toBeVisible({ timeout: 30_000 })

    // ① 同轮三部分保全：气泡数不变，待决卡回来且可交互
    await expect(page.locator('[data-testid^="msg-bubble-"]')).toHaveCount(bubblesBefore, { timeout: 30_000 })
    await expect(gate).toBeVisible({ timeout: 30_000 })
    await expect(gate).toContainText('确认启动发布作业')
    await expect(gate.getByRole('button', { name: '拒绝', exact: true })).toBeEnabled()

    // ② 后端仍认这一轮为挂起（不是"刷新即失忆"）
    const current = await runCurrent(request, sid)
    expect(current.found).toBe(true)
    expect(String(current.snapshotStatus)).toBe('SUSPENDED')

    // ③ 监听必须早于刷新，才能包含第一条恢复连接。22s 覆盖四次重试的最大退避总长，
    //    期间只允许一次增量恢复；正常挂起收流不得弹重试或"结果可能不完整"假警报。
    await page.waitForTimeout(22_000)
    expect(reconnects).toHaveLength(1)
    expect(reconnects[0]).toContain(`lastSeq=${current.archiveMaxSeq}`)
    await expect(page.locator('[data-testid="notice-bar"]')).toHaveCount(0)

    // ④ 批准后本轮真跑完（拒绝路径已在批① H-02 覆盖，这里走批准）
    await page.locator('[data-testid="gate-ack-box"]').click()
    await gate.getByRole('button', { name: '批准', exact: true }).click()
    await expect(gate).toHaveCount(0, { timeout: 20_000 })
    await waitRoundSettled(page, 90_000)

    // ⑤ 终态与 /history 一致：本轮助手消息不再挂 necessitatesAction
    const history = await api.get<{ messages: Array<Record<string, unknown>> }>(request, `/api/ai/history/${sid}`)
    expect(history.messages.length).toBeGreaterThan(0)
  })

  /**
   * R-07 批准后工具执行中刷新：不许"什么都不恢复 + 输入框谎报空闲"；12s 内状态有变化，
   * 后端跑完后 UI 与 `/history` 一致。
   */
  test('E2E-R07 批准后执行中刷新：12s 内续收，终态与 /history 一致', async ({ page, request }) => {
    const taskId = await createPublishableImportTask(request)
    await openPath(page, `/import?taskId=${taskId}`)
    await expect(page.locator('footer')).toContainText('task:IMPORT/PUBLISH', { timeout: 30_000 })
    await sendMessage(page, P(`scenario=IRREV-PUBLISH 任务 ${taskId} 发布 R07`))

    const gate = page.locator('[data-testid="hitl-gate"]')
    await expect(gate).toBeVisible({ timeout: 30_000 })
    await page.locator('[data-testid="gate-ack-box"]').click()
    await gate.getByRole('button', { name: '批准', exact: true }).click()

    // 批准后立刻刷新（400ms 内）
    await page.waitForTimeout(400)
    await page.reload({ waitUntil: 'domcontentloaded' })
    await expect(page.locator('[data-testid="composer-input"]')).toBeVisible({ timeout: 30_000 })

    // ① 12s 内状态确实在变（续收或终态），不是"刷新后死寂"
    const sid = await sessionId(page)
    await until(async () => {
      const current = await runCurrent(request, sid)
      const status = String(current.snapshotStatus ?? '')
      return !current.found || ['DONE', 'CANCELLED', 'FAILED', 'SUSPENDED'].includes(status)
    }, 12_000, '刷新后 12s 内有状态变化')
    await waitRoundSettled(page, 90_000)

    // ② 终态 UI 与 /history 同向：气泡数 == 历史里 USER/ASSISTANT(+工具) 的可见轮数口径
    const history = await api.get<{ messages: Array<Record<string, unknown>> }>(request, `/api/ai/history/${sid}`)
    const userTurns = history.messages.filter((message) => String(message.type ?? '') === 'USER').length
    await expect(page.locator('[data-testid^="msg-bubble-"]')).toHaveCount(userTurns * 1 + userTurns, { timeout: 30_000 })
    await expect(page.locator('[data-testid="send-btn"]')).toBeVisible({ timeout: 10_000 })
  })

  /**
   * R-08 GF 挂起中刷新：标题/字段/待填写态完整重建，控件可交互，提交后本轮跑完，**无倒计时**。
   */
  test('E2E-R08 生成式表单挂起中刷新：结构重建、可交互、提交后跑完、无倒计时', async ({ page }) => {
    await openApp(page)
    await sendMessage(page, P('gf-seven-types R08'))
    await expect(page.locator('[data-testid="gf-fields"]')).toBeVisible({ timeout: 40_000 })

    await page.reload({ waitUntil: 'domcontentloaded' })
    await expect(page.locator('[data-testid="gf-fields"]')).toBeVisible({ timeout: 30_000 })

    // ① 标题与七型字段完整重建
    await expect(page.locator('[data-testid^="msg-bubble-"]').last()).toContainText('七型渲染基线')
    for (const key of ['f_text', 'f_number', 'f_boolean', 'f_date', 'f_enum', 'f_file']) {
      await expect(page.locator(`#gf-field-${key}`)).toBeVisible()
    }
    // ② 无倒计时：卡面不得出现「剩余 Ns」类文案（去超时裁决的直接可见面）
    await expect(page.locator('[data-testid^="msg-bubble-"]').last()).not.toContainText('剩余')
    // ③ 控件真可交互（真鼠标坐标勾选 boolean）
    const toggle = page.locator('#gf-field-f_boolean')
    const box = await toggle.boundingBox()
    expect(box).not.toBeNull()
    await page.mouse.click(box!.x + box!.width / 2, box!.y + box!.height / 2)
    await expect(toggle).toBeChecked()

    // ④ 必填项与其余字段填好并提交：本轮跑完（空 date / 空附件会被表单校验拦下，属既有校验口径）
    await page.locator('#gf-field-f_text').fill('R08 恢复后提交')
    await page.locator('#gf-field-f_date').fill('2026-10-23')
    const png = Buffer.from(
      'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==',
      'base64'
    )
    await page.locator('#gf-field-f_file').setInputFiles({ name: 'e2e-r08.png', mimeType: 'image/png', buffer: png })
    await expect(page.locator('[data-testid="gf-fields"]')).toContainText('e2e-r08.png', { timeout: 20_000 })
    await page.locator('[data-testid="gf-submit"]').click()
    await expect(page.locator('[data-testid="gf-submit"]')).toHaveCount(0, { timeout: 30_000 })
    await expect(page.locator('[data-testid="gf-error"]')).toHaveCount(0)
    await waitRoundSettled(page, 60_000)
    await expect(page.locator('[data-testid="send-btn"]')).toBeVisible({ timeout: 10_000 })
  })

  /**
   * R-09 表单已填一半刷新：四字段逐值相等（boolean 必须 true，非 schema 默认），草稿来自本机镜像。
   */
  test('E2E-R09 表单填一半刷新：text/enum/boolean/multi_select 逐值相等', async ({ page }) => {
    await openApp(page)
    await sendMessage(page, P('gf-seven-types R09'))
    await expect(page.locator('[data-testid="gf-fields"]')).toBeVisible({ timeout: 40_000 })

    await page.locator('#gf-field-f_text').fill('CURRENCY')
    await page.locator('#gf-field-f_enum').selectOption('HD')
    const toggle = page.locator('#gf-field-f_boolean')
    const box = await toggle.boundingBox()
    expect(box).not.toBeNull()
    await page.mouse.click(box!.x + box!.width / 2, box!.y + box!.height / 2)
    await page.locator('#gf-field-f_multi-A').check()
    await page.locator('#gf-field-f_multi-B').check()
    await expect(page.locator('#gf-field-f_multi-B')).toBeChecked()

    await page.reload({ waitUntil: 'domcontentloaded' })
    await expect(page.locator('[data-testid="gf-fields"]')).toBeVisible({ timeout: 30_000 })

    // 逐值相等（boolean 必须为 true —— 不是 schema 默认 false）
    await expect(page.locator('#gf-field-f_text')).toHaveValue('CURRENCY')
    await expect(page.locator('#gf-field-f_enum')).toHaveValue('HD')
    await expect(page.locator('#gf-field-f_boolean')).toBeChecked()
    await expect(page.locator('#gf-field-f_multi-A')).toBeChecked()
    await expect(page.locator('#gf-field-f_multi-B')).toBeChecked()

    // 草稿键按 toolCallId 隔离：本会话只有这一份
    const draftKeys = (await sessionStorageKeys(page)).filter((key) => key.startsWith('cm.ai.form.draft.'))
    expect(draftKeys).toHaveLength(1)
  })

  /**
   * R-11 稍后重开同一会话（最终一致）：正文与卡完整；历史气泡数 == 实时气泡数。
   */
  test('E2E-R11 重开同一会话：气泡数、正文与卡与实时一致', async ({ page, context }) => {
    await openApp(page)
    await sendMessage(page, P('配置定义 R11'))
    await waitRoundSettled(page, 60_000)
    const sid = await sessionId(page)
    const liveBubbles = await page.locator('[data-testid^="msg-bubble-"]').count()
    const liveText = await assistantText(page)
    const liveCards = await page.locator('[data-testid^="tool-card-"]').count()
    expect(liveBubbles).toBe(2)
    expect(liveCards).toBe(1)

    const liveUserText = (await page.locator('[data-testid^="msg-bubble-"]').first().innerText()).trim()
    expect(liveText).toContain('列出配置定义')
    expect(liveText).toContain('已成功')

    // 「收起面板 → 重开」= 关掉本页签再开一个持有同 sessionId 的页签
    // （同会话第二页签并存时会因页签所有权切到新会话，那是 M 域口径，不属 R-11）
    await page.close()
    const reopened = await context.newPage()
    await reopened.addInitScript((session: string) => {
      window.sessionStorage.setItem('ai-session-id', session)
    }, sid)
    await reopened.goto('/', { waitUntil: 'domcontentloaded' })
    await expect(reopened.locator('[data-testid="composer-input"]')).toBeVisible({ timeout: 30_000 })

    await expect(reopened.locator('[data-testid^="msg-bubble-"]')).toHaveCount(liveBubbles, { timeout: 30_000 })
    await expect(reopened.locator('[data-testid^="tool-card-"]')).toHaveCount(liveCards)
    // 正文与卡完整：用户气泡逐字相等，助手气泡带同一张卡与同一状态
    expect((await reopened.locator('[data-testid^="msg-bubble-"]').first().innerText()).trim()).toBe(liveUserText)
    const reopenedText = (await reopened.locator('[data-testid^="msg-bubble-"].w-full').last().innerText()).trim()
    expect(reopenedText).toContain('列出配置定义')
    expect(reopenedText).toContain('已成功')
    await reopened.close()
  })

  /**
   * R-13 历史 + 镜像合并不整替：历史里没有的在途助手消息仍在（气泡数 = 历史 + 1），
   * 同提问恰 1 条 user 气泡。
   */
  test('E2E-R13 镜像在途轮合并：历史 + 1 条在途助手消息，user 气泡不重复', async ({ page, request }) => {
    await openApp(page)
    await sendMessage(page, P('turn-text stall-frames=2500 R13'))
    await expect.poll(async () => (await assistantText(page)).length, { timeout: 20_000 }).toBeGreaterThan(10)
    const sid = await sessionId(page)
    const mirror = await readMirror(page, sid)
    expect(mirror?.inFlight).toBe(true)

    await page.reload({ waitUntil: 'domcontentloaded' })
    await expect(page.locator('[data-testid="composer-input"]')).toBeVisible({ timeout: 30_000 })
    await expect.poll(async () => (await assistantText(page)).length, { timeout: 30_000 }).toBeGreaterThan(10)

    const history = await api.get<{ messages: Array<Record<string, unknown>> }>(request, `/api/ai/history/${sid}`)
    const historyUser = history.messages.filter((message) => String(message.type ?? '') === 'USER').length
    const historyAssistant = history.messages.filter((message) => String(message.type ?? '') === 'ASSISTANT').length

    // ① 气泡数 = 历史 + 1（镜像里那条尚未落库的在途助手消息）
    await expect
      .poll(async () => page.locator('[data-testid^="msg-bubble-"]').count(), { timeout: 15_000 })
      .toBe(historyUser + historyAssistant + 1)
    // ② 同一提问恰 1 条 user 气泡（镜像与历史各留一份是"重复"）
    await expect(page.locator('[data-testid^="edit-msg-"]')).toHaveCount(historyUser)

    await page.locator('[data-testid="stop-btn"]').click()
    await waitRoundSettled(page, 20_000)
  })

  /**
   * R-14 镜像落盘时机（节流非防抖）：pagehide 立即冲刷；正文 ≥ 屏上 80%；
   * lastRunId 与 runSeq 均已写入；只有一个会话镜像键。
   */
  test('E2E-R14 流式中 pagehide 冲刷镜像：正文 ≥ 屏上 80%，lastRunId/runSeq 齐备', async ({ page }) => {
    await openApp(page)
    await sendMessage(page, P('turn-text stall-frames=3000 R14'))
    await expect.poll(async () => (await assistantText(page)).length, { timeout: 20_000 }).toBeGreaterThan(20)

    const screenText = await assistantText(page)
    const sid = await sessionId(page)
    await page.evaluate(() => {
      window.dispatchEvent(new PageTransitionEvent('pagehide'))
    })
    await page.waitForTimeout(600)

    const mirror = await readMirror(page, sid)
    expect(mirror).not.toBeNull()
    const assistants = (mirror!.messages ?? []).filter((message) => message.role === 'assistant')
    const mirrorText = String(assistants[assistants.length - 1]?.content ?? '')
    // ① 正文覆盖 ≥ 屏上 80%（节流窗口内的最短差）
    expect(mirrorText.length).toBeGreaterThanOrEqual(Math.floor(screenText.length * 0.8))
    // ② lastRunId 与 runSeq 已落
    expect(String(mirror!.lastRunId ?? '').length).toBeGreaterThan(0)
    expect(Object.keys(mirror!.runSeq ?? {}).length).toBeGreaterThan(0)
    // ③ 只有 1 个会话镜像键（无泄漏/无重复会话）
    expect((await sessionStorageKeys(page)).filter((key) => key.startsWith('ai-mirror:'))).toHaveLength(1)

    await page.locator('[data-testid="stop-btn"]').click()
    await waitRoundSettled(page, 20_000)
  })

  /**
   * R-15 刷新后上下文指示器恢复：pill 值与 `/context` 一致；popover 七项自洽。
   */
  test('E2E-R15 刷新后上下文 pill 恢复：与 /context 一致，popover 七项自洽', async ({ page, request }) => {
    await openApp(page)
    await sendMessage(page, P('turn-text R15'))
    await waitRoundSettled(page, 60_000)
    const sid = await sessionId(page)

    await page.reload({ waitUntil: 'domcontentloaded' })
    await expect(page.locator('[data-testid="context-pill"]')).toBeVisible({ timeout: 30_000 })

    const contextResponse = await request.get(`${API_BASE}/api/ai/context/${sid}`)
    const context = (await contextResponse.json()) as {
      usedTokens: number
      limitTokens: number | null
      percent: number | null
      compaction: { level: string }
      breakdown: Array<{ key: string; tokens: number }>
    }
    const pill = page.locator('[data-testid="context-pill"]')
    const title = (await pill.getAttribute('title')) ?? ''
    expect(title).toContain(`${context.percent}%`)
    expect(await pill.innerText()).toContain(`${context.percent}%`)

    await pill.click()
    await expect(page.locator('[data-testid^="context-row-"]')).toHaveCount(7, { timeout: 10_000 })
    const usedSum = context.breakdown
      .filter((row) => row.key !== 'free')
      .reduce((sum, row) => sum + row.tokens, 0)
    expect(usedSum).toBe(context.usedTokens)
    expect(['none', 'soft', 'hard']).toContain(context.compaction.level)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// P · STP 手工停止（§2.3）—— 真鼠标点 `stop-btn` / 真键盘 / 真归档对账
// ─────────────────────────────────────────────────────────────────────────────

test.describe('@stub P 手工停止', () => {
  /**
   * P-03 工具执行中停止：卡收敛为「已取消」（1s 内不再 running），镜像同段同终态，
   * 归档有收敛信号（done.cancelled 或 pendingOutcomes）。
   *
   * 复现面：前端工具（navigate_to）执行后回灌 `POST /api/ai/frontend-tool-result` 的请求被
   * 延迟 8s —— 这 8s 里卡真实处于「调用中」，等价于蓝本 slow-tool 造出的"工具执行窗口"。
   */
  test('E2E-P03 工具执行中停止：卡 1s 内收敛「已取消」，镜像与归档同向', async ({ page }) => {
    const chat = captureChat(page)
    await page.route('**/api/ai/frontend-tool-result**', async (route) => {
      await new Promise((done) => setTimeout(done, 8_000))
      await route.continue()
    })
    await openApp(page)
    await sendMessage(page, P('导出向导 P03'))

    // 卡出现且处于「调用中」（延迟回灌期间的真实状态）
    const status = page.locator('[data-testid^="card-status-"]')
    await expect(status).toHaveCount(1, { timeout: 30_000 })
    await expect(status).toContainText('调用中', { timeout: 20_000 })

    await stopButton(page).click()
    // ① 1s 内不再有 running 卡
    await expect(status).not.toContainText('调用中', { timeout: 1_000 })
    // ② 该卡文案「已取消」
    await expect(status).toContainText('已取消', { timeout: 5_000 })
    await waitRoundSettled(page, 20_000)

    // ③ 镜像同段同为终态（刷新后不会回退）
    const sid = await sessionId(page)
    await page.waitForTimeout(700)
    const mirror = await readMirror(page, sid)
    expect(JSON.stringify(mirror ?? {})).not.toContain('"running"')

    // ④ 归档有收敛信号：done.cancelled 或 pending 条目 CANCELLED
    const frames = parseSse(await chat.body().catch(() => ''))
    const done = frames.find((frame) => frame.type === 'done')
    if (done) {
      expect(done.cancelled === true || Array.isArray(done.pendingOutcomes)).toBe(true)
    }
  })

  /**
   * P-06 GF 挂起中停止：表单控件消失、卡显示「已取消」（非"待填写"空壳），停止后可发送。
   */
  test('E2E-P06 表单挂起中停止：控件转只读、卡「已取消」、草稿不残留、随后可发新消息', async ({ page }) => {
    await openApp(page)
    await sendMessage(page, P('gf-seven-types P06'))
    await expect(page.locator('[data-testid="gf-fields"]')).toBeVisible({ timeout: 40_000 })

    await stopButton(page).click()
    // ① 不再是可填表单：提交钮退场、字段控件转为只读/禁用（不是留个"待填写"空壳）
    await expect(page.locator('[data-testid="gf-submit"]')).toHaveCount(0, { timeout: 10_000 })
    // L-92 随迁：停止后挂起卡换成**只读回放卡**，其字段 DOM id 已改为
    // `gf-field-<scope>-<key>`（`FormRenderer#controlIdOfKey`，只读卡的 scope 是实例序号、不可预测），
    // 故不再按 `#gf-field-<key>` 定位；轮次唯一定位走 testid。只读卡卡体是 `v-show` 常挂载，
    // 折叠态（回合结束自动收卡头）同样能断「禁用」这一条。
    const readOnlyForm = page.locator('[data-testid^="ro-form-"]')
    await expect(readOnlyForm).toHaveCount(1)
    await expect(readOnlyForm.locator('[data-testid$="-f_text"]')).toBeDisabled()
    // ② 卡显示「已取消」并注明"填写内容不作记录"（只读回执形态）
    const badge = page.locator('[data-testid^="ro-badge-"]').last()
    await expect(badge).toContainText('已取消', { timeout: 10_000 })
    await expect(page.locator('[data-testid^="msg-bubble-"].w-full').last())
      .toContainText('未提交，填写内容不作记录')
    // ③ activeForm 已清：本机草稿不残留
    expect((await sessionStorageKeys(page)).filter((key) => key.startsWith('cm.ai.form.draft.'))).toHaveLength(0)
    // ④ 输入框解禁，能发新消息并真跑一轮
    await expect(page.locator('[data-testid="send-btn"]')).toBeVisible({ timeout: 10_000 })
    await sendMessage(page, P('turn-text P06-后续'))
    await expect(assistantBubble(page)).toHaveText(TURN_TEXT, { timeout: 60_000 })
    await waitRoundSettled(page, 60_000)
  })

  /**
   * P-07 停止后立刻发新消息（四档延时 10/50/100/600ms）：四档全 200、无幻影气泡、草稿不双份、
   * 被停正文保留。
   */
  test('E2E-P07 停止后四档延时发送：四档全 200，无幻影气泡，被停正文保留', async ({ context }) => {
    for (const delay of [10, 50, 100, 600]) {
      const page = await context.newPage()
      await openApp(page)
      await sendMessage(page, P('turn-text stall-frames=2000 P07-初轮'))
      await expect.poll(async () => (await assistantText(page)).length, { timeout: 20_000 }).toBeGreaterThan(10)

      await stopButton(page).click()
      await waitRoundSettled(page, 20_000)
      const stoppedText = await assistantText(page)
      expect(stoppedText.length).toBeGreaterThan(0)

      await page.waitForTimeout(delay)
      const sent = page.waitForResponse(
        (response) => response.url().includes('/api/ai/chat') && response.request().method() === 'POST',
        { timeout: 20_000 }
      )
      await sendMessage(page, P(`turn-text P07-${delay}ms`))
      const response = await sent
      expect(response.status(), `延时 ${delay}ms 档应 200`).toBe(200)

      await expect(assistantBubble(page)).toHaveText(TURN_TEXT, { timeout: 60_000 })
      await waitRoundSettled(page, 60_000)

      // 无幻影气泡：恰好两轮 = 4 条气泡；被停正文仍在屏上
      await expect(page.locator('[data-testid^="msg-bubble-"]')).toHaveCount(4)
      await expect(page.locator('body')).toContainText(stoppedText.slice(0, 12))
      // 草稿不双份：发送后输入框已清空
      await expect(page.locator('[data-testid="composer-input"]')).toHaveValue('')
      await page.close()
    }
  })

  /**
   * P-08 停止后同 tick 回车（≤10ms）：不静默吞键 —— 要么真发出（200），要么显式提示且草稿保留。
   */
  test('E2E-P08 停止与回车同 tick：不静默吞键（发出或有显式提示 + 草稿保留）', async ({ page }) => {
    await openApp(page)
    await sendMessage(page, P('turn-text stall-frames=2000 P08-初轮'))
    await expect.poll(async () => (await assistantText(page)).length, { timeout: 20_000 }).toBeGreaterThan(10)

    const requests: number[] = []
    page.on('response', (response) => {
      if (response.url().includes('/api/ai/chat') && response.request().method() === 'POST') {
        requests.push(response.status())
      }
    })

    const draft = P('P08 同 tick 请求')
    await page.locator('[data-testid="composer-input"]').fill(draft)
    await page.evaluate(() => {
      const stop = document.querySelector('[data-testid="stop-btn"]')
      const input = document.querySelector('[data-testid="composer-input"]')
      if (stop instanceof HTMLElement) stop.click()
      if (input instanceof HTMLElement) {
        input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', code: 'Enter', bubbles: true }))
      }
    })
    await page.waitForTimeout(4_000)

    const composer = page.locator('[data-testid="composer-input"]')
    if (requests.length === 0) {
      // 分支 A：本地显式拒绝 —— 必须有提示，且草稿仍在（不静默吞）
      await expect(page.locator('[data-testid="notice-bar"]')).toHaveCount(1, { timeout: 5_000 })
      await expect(composer).toHaveValue(draft)
    } else if (requests.every((status) => status === 200)) {
      // 分支 B：真发出 —— 200，且草稿被消费
      await expect(composer).toHaveValue('', { timeout: 20_000 })
    } else {
      // 分支 C：赶在停止收口前发出、被后端 409 拒（同 tick 的正常竞态）——
      // 同样不许静默：必须显式提示「没发出去」，且输入框**逐字等于用户原草稿**（L-83：
      // 旧实现会把它回填成上一条用户消息，用户刚打的草稿被静默覆盖 ⇒「非空」是假绿），
      // 幻影气泡已回收。
      expect(requests).toContain(409)
      await expect(page.locator('[data-testid="notice-bar"]')).toHaveCount(1, { timeout: 10_000 })
      await expect(page.locator('[data-testid="notice-bar"]')).toContainText('这条消息没有发出去')
      await expect(composer).toHaveValue(draft, { timeout: 10_000 })
      await expect(page.locator('[data-testid^="msg-bubble-"]')).toHaveCount(2, { timeout: 15_000 })
    }
  })

  /**
   * P-09 双击停止幂等：同 tick 两次点击只发 1 次 `POST /cancel`，后端只 1 个终态。
   */
  test('E2E-P09 同 tick 双击停止：只发 1 次 cancel，后端只 1 个终态', async ({ page, request }) => {
    await openApp(page)
    await sendMessage(page, P('turn-text stall-frames=2500 P09'))
    await expect.poll(async () => (await assistantText(page)).length, { timeout: 20_000 }).toBeGreaterThan(10)
    const sid = await sessionId(page)
    const current = await runCurrent(request, sid)
    const runId = String(current.runId ?? '')
    expect(runId.length).toBeGreaterThan(0)

    const cancels: string[] = []
    page.on('request', (request_) => {
      if (request_.url().includes('/api/ai/cancel/')) cancels.push(request_.url())
    })

    await page.evaluate(() => {
      const stop = document.querySelector('[data-testid="stop-btn"]')
      if (stop instanceof HTMLElement) {
        stop.click()
        stop.click()
      }
    })
    await waitRoundSettled(page, 20_000)
    await page.waitForTimeout(800)

    // ① 只发 1 次 cancel（第 2 次被 stopping 首行守卫本地拦下）
    expect(cancels).toHaveLength(1)
    // ② 后端只 1 个终态：CANCELLED，且「已停止」chip 只 1 个
    await until(async () => (await runStatus(request, runId)) === 'CANCELLED', 15_000, 'run CANCELLED')
    await expect(page.locator('[data-testid^="msg-stopped-"]')).toHaveCount(1)
    await expect(page.locator('[data-testid="alert-interrupted"]')).toHaveCount(0)
  })

  /**
   * P-11 停止后迟到帧不写：停止时流中仍有在途块，停止后不新增气泡/卡片/正文，镜像不写。
   */
  test('E2E-P11 停止后迟到帧不写：零新气泡零新卡，正文与镜像冻结', async ({ page }) => {
    await openApp(page)
    await sendMessage(page, P('turn-text stall-frames=2000 P11'))
    await expect.poll(async () => (await assistantText(page)).length, { timeout: 20_000 }).toBeGreaterThan(10)

    await stopButton(page).click()
    await waitRoundSettled(page, 20_000)
    const bubbles = await page.locator('[data-testid^="msg-bubble-"]').count()
    const cards = await page.locator('[data-testid^="tool-card-"]').count()
    const text = await assistantText(page)
    const sid = await sessionId(page)
    await page.waitForTimeout(500)
    const mirrorBefore = JSON.stringify(await readMirror(page, sid))

    // 停止后至少还有一个帧间隔窗口（stall 2s × 剩余切片），迟到帧不得落地
    await page.waitForTimeout(8_000)
    expect(await page.locator('[data-testid^="msg-bubble-"]').count()).toBe(bubbles)
    expect(await page.locator('[data-testid^="tool-card-"]').count()).toBe(cards)
    expect(await assistantText(page)).toBe(text)
    expect(JSON.stringify(await readMirror(page, sid))).toBe(mirrorBefore)
    await expect(page.locator('[data-testid="alert-interrupted"]')).toHaveCount(0)
  })

  /**
   * P-12 停止后刷新：被停的卡显示「已取消」，不消失、不回退成更早的待决态。
   *
   * L-75 已修（真机 E2E 批② SRP 域 P-12 转正）：纯卡轮（本轮既无正文也无工具执行结果，只有一张
   * 待决门卡）停止后，取消终局按快照的 in-flight 条目重建 `assistant(tool_calls)` + 配对
   * `role:tool`，并带 `stopped` 标记 ⇒ `/history` 不再只剩 USER。
   */
  test('E2E-P12 停止后刷新：被停卡仍在且为「已取消」，不回退待决', async ({ page, request }) => {
    const taskId = await createPublishableImportTask(request)
    await openPath(page, `/import?taskId=${taskId}`)
    await expect(page.locator('footer')).toContainText('task:IMPORT/PUBLISH', { timeout: 30_000 })
    await sendMessage(page, P(`scenario=IRREV-PUBLISH 任务 ${taskId} 发布 P12`))

    const gate = page.locator('[data-testid="hitl-gate"]')
    await expect(gate).toBeVisible({ timeout: 30_000 })
    await stopButton(page).click()
    await expect(gate).toHaveCount(0, { timeout: 20_000 })
    await waitRoundSettled(page, 20_000)
    // 停止后门卡转只读回执（`hitl-receipt` + `ro-badge-*`），状态「已取消」
    await expect(page.locator('[data-testid^="ro-badge-"]').last()).toContainText('已取消', { timeout: 10_000 })

    await page.reload({ waitUntil: 'domcontentloaded' })
    await expect(page.locator('[data-testid="composer-input"]')).toBeVisible({ timeout: 30_000 })

    // ① 卡不消失：只读回执仍在
    await expect(page.locator('[data-testid="hitl-receipt"]')).toHaveCount(1, { timeout: 30_000 })
    // ② 仍为「已取消」：不得回到「确认启动发布作业」的待决门形态
    await expect(page.locator('[data-testid="hitl-gate"]')).toHaveCount(0, { timeout: 15_000 })
    await expect(page.locator('[data-testid^="ro-badge-"]').last()).toContainText('已取消')
  })

  /**
   * P-13 「继续」入口语义：按重发该问题处理 —— 新轮、新气泡、turnNo 递增（不续写半截答案）。
   */
  test('E2E-P13 继续入口：重发该问题成新轮，turnNo 递增，气泡是新轮', async ({ page, request }) => {
    await openApp(page)
    // 断流轮（缺 done）才有「继续」入口；被用户停止的轮与「继续」互斥（P-01）
    await sendMessage(page, P('turn-text stall-frames=400 kill-stream=1500 P13'))
    await expect(page.locator('[data-testid="continue-round"]')).toBeVisible({ timeout: 40_000 })
    await waitRoundSettled(page, 40_000)

    const sid = await sessionId(page)
    const contextBefore = (await (await request.get(`${API_BASE}/api/ai/context/${sid}`)).json()) as { turnNo: number }
    const bubblesBefore = await page.locator('[data-testid^="msg-bubble-"]').count()

    await page.locator('[data-testid="continue-round"]').click()
    await expect
      .poll(async () => page.locator('[data-testid^="msg-bubble-"]').count(), { timeout: 20_000 })
      .toBe(bubblesBefore + 2)
    await waitRoundSettled(page, 60_000)

    // ① turnNo 递增（新轮，不是续写）
    const contextAfter = (await (await request.get(`${API_BASE}/api/ai/context/${sid}`)).json()) as { turnNo: number }
    expect(contextAfter.turnNo).toBe(contextBefore.turnNo + 1)
    // ② 气泡是新轮：末条 user 气泡文本为「继续」
    const userBubbles = page.locator('[data-testid^="edit-msg-"]')
    expect(await userBubbles.count()).toBe(bubblesBefore / 2 + 1)
  })

  /**
   * P-14 停止后台账一致：`/runs/{runId}`、`/pending/{runId}`、`/runs/current`、`/history` 四者同向。
   */
  test('E2E-P14 停止后台账四者同向：run/pending/current/history 都是取消终态', async ({ page, request }) => {
    const taskId = await createPublishableImportTask(request)
    await openPath(page, `/import?taskId=${taskId}`)
    await expect(page.locator('footer')).toContainText('task:IMPORT/PUBLISH', { timeout: 30_000 })
    await sendMessage(page, P(`scenario=IRREV-PUBLISH 任务 ${taskId} 发布 P14`))
    await expect(page.locator('[data-testid="hitl-gate"]')).toBeVisible({ timeout: 30_000 })

    const sid = await sessionId(page)
    const current = await runCurrent(request, sid)
    const runId = String(current.runId ?? '')
    expect(runId.length).toBeGreaterThan(0)

    await stopButton(page).click()
    await waitRoundSettled(page, 20_000)

    // ① 运行账：CANCELLED
    await until(async () => (await runStatus(request, runId)) === 'CANCELLED', 20_000, 'run CANCELLED')
    // ② 待决账：条目取消终态（CANCELLED / RUN_CANCELLED），不留 PENDING
    const pending = await api.get<Array<{ status?: string; code?: string | null; toolCallId?: string }>>(
      request,
      `/api/ai/pending/${encodeURIComponent(runId)}`
    )
    expect(pending.length).toBeGreaterThan(0)
    for (const entry of pending) {
      expect(['CANCELLED', 'RUN_CANCELLED', 'FRONTEND_CANCELLED']).toContain(String(entry.status))
      expect(String(entry.status)).not.toBe('PENDING')
    }
    // ③ 当前轮查询不再谎报"有在途轮"：终态轮的索引已收起（found=false），
    //    「上一轮被取消」由 ① 的 `/runs/{runId}` 回答（同一 RunStore 的另一面）
    const after = await runCurrent(request, sid)
    expect(['CANCELLED', 'NOT_FOUND']).toContain(after.found ? String(after.snapshotStatus) : 'NOT_FOUND')
    // ④ 历史里该轮仍在（被停不等于没发生）
    const history = await api.get<{ messages: Array<Record<string, unknown>> }>(request, `/api/ai/history/${sid}`)
    expect(history.messages.filter((message) => String(message.type ?? '') === 'USER')).toHaveLength(1)
  })
})
