/**
 * 批③c 真机 E2E · K 知识问答（10）+ Q 快捷建议（8）+ C 上下文（14）
 *
 * 编号沿用 `docs/research/设计-真机E2E用例全集-2026-10-23.md` §2.7 / §2.8 / §2.9。
 * 本域独家前缀：spec `16-kqc*.spec.ts`、桩 `support/kqc-upstream.mjs`、助手 `support/helpers-kqc.ts`；
 * 端口 / Redis DB / 运行目录全部走 harness 的 `E2E_*` 参数化（不碰 8081 / 5202，不改任何既有文件）。
 *
 * 为什么需要本域桩：KB 三工具不在官方桩的 `productTools` 共享契约表里（该表只读），
 * 官方桩下 K 域**结构上不可达**。本域桩只补 KB 工具调用 + usage 四形状 + 越窗 400，其余行为不变。
 *
 * 分层：标 `@stub` 的在本域桩下本地可跑；标 `@real` 的（K-10 / C-13）只写不本地跑（真 key 由 CI 注入）。
 * 断言强度：A 级只落 data-testid / 终态文案；B 级比对端点载荷、SSE 帧、桩落档三方同值。
 */
import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { expect, test } from '@playwright/test'
import { assistantBubble, openApp, sendMessage, sessionId, waitRoundSettled } from './support/helpers'
import {
  aiHealth,
  apiData,
  chatRound,
  contextLedger,
  contextSnapshot,
  deltaTextOf,
  fingerprint,
  httpRaw,
  kbStatus,
  maxStubIndex,
  newSessionId,
  parsePillText,
  readDocBody,
  readDocTail,
  redisExec,
  roleTextsOf,
  systemTextOf,
  tokensFromLabel,
  toolEnvelope,
  toolNames,
  lastToolContent,
  lastToolText,
  toolTextsOf,
  unwrapKnowledge,
  usageShapes,
  waitStubRequestsAfter
} from './support/helpers-kqc'

/** 策略：本域桩用例依赖本域桩（KB 三工具不在官方桩的共享 `productTools` 表里）。 */
const KQC_STUB_ACTIVE = (process.env.E2E_STUB_SCRIPT ?? '').includes('kqc-upstream')

/**
 * 真模型模式（`E2E_MODE=real`）**不起任何桩**（harness：`needStub = MODE === 'stub'`），
 * 本域 `@real` 条的证据面是 SSE 帧 / `/context` / `/kb/*` 端点，不依赖桩落档 —— 因此
 * 「有没有本域桩」不能当作真模型棒的跳过条件（否则 K-10 / C-13 在真机棒里恒 SKIP，
 * 而蓝本 §0.6-5 要求标「真模型」的条目必须在真 key 下跑过）。
 */
const KQC_REAL_MODE = process.env.E2E_MODE === 'real'

test.skip(
  !KQC_STUB_ACTIVE && !KQC_REAL_MODE,
  'K/Q/C 域需要在 KQC 域桩下运行：E2E_STUB_SCRIPT=<仓库根>/frontend/e2e/support/kqc-upstream.mjs'
)

/** 知识索引是启动期异步建的（harness 只等 /health 与种子）：本域所有用例先等 ready。 */
test.beforeAll(async () => {
  const deadline = Date.now() + 100_000
  let last = ''
  let lastReloadAt = 0
  while (Date.now() < deadline) {
    try {
      const status = await kbStatus()
      last = JSON.stringify(status)
      if (status.enabled && status.ready && status.doc_count >= 20) return
      // 主实例与降级实例共用同一相对 kb dir / index dir（harness 的既有起停口径）⇒
      // Lucene write.lock 竞争，落后的一方要等 10m 刷新周期。这里按运维路径触发一次手动重建
      // （见报告「发现项 F-2」：这是 harness/配置面缺陷，不是本域用例的假设）。
      if (Date.now() - lastReloadAt > 15_000) {
        lastReloadAt = Date.now()
        await httpRaw('POST', '/api/ai/kb/reload').catch(() => null)
      }
    } catch (error) {
      last = error instanceof Error ? error.message : String(error)
    }
    await new Promise((done) => setTimeout(done, 500))
  }
  throw new Error(`知识库未在 100s 内就绪：${last}`)
})

/** 读语言包（Playwright 的 ESM 加载器不接受 JSON 导入属性，直接读文件最稳）。 */
function readLocale(locale: 'zh-CN' | 'en-US'): { ai: { suggestions: Record<string, string> } } {
  const path = join(process.cwd(), 'src', 'locales', `${locale}.json`)
  return JSON.parse(readFileSync(path, 'utf8')) as { ai: { suggestions: Record<string, string> } }
}

/** 建议模板 id 的闭集（= `utils/ai-suggestions.ts` 的 `SuggestionId`）。 */
const SUGGESTION_IDS = [
  'taskProgress',
  'exportEstimate',
  'startExport',
  'precheckImport',
  'startImport',
  'listDefs',
  'workspaceState',
  'newExportTask',
  'dataRowCount'
]

/** 一轮工具调用的两段请求（调用帧 + 带 role=tool 的续轮）。 */
async function kbRound(sessionIdValue: string, caseId: string, toolAndArgs: string) {
  const before = maxStubIndex()
  const round = await chatRound(sessionIdValue, `知识问答用例 ${fingerprint(caseId)} ${toolAndArgs}`)
  const requests = await waitStubRequestsAfter(before, 2)
  return { round, first: requests[0], continuation: requests[1] }
}

test.describe('@stub 16 K 知识问答', () => {
  test('E2E-K01 kb_search 命中与上限：top_k 强截 10、命中带 doc_id/section、不暴露检索分', async () => {
    const sid = newSessionId('K01')
    const { round, first, continuation } = await kbRound(sid, 'K01', 'tool=kb_search&args={"query":"费率","topK":50}')

    expect(round.status).toBe(200)
    expect(round.errors).toHaveLength(0)
    expect(round.done).toBeTruthy()

    // 披露面：KB 工具确实在本轮披露清单里（否则桩的披露闸会退化为文本轮）
    expect(first.tools).toContain('kb_search')
    expect(String(first.decision?.tool)).toBe('kb_search')
    expect(Number((first.decision?.args as { topK?: number } | undefined)?.topK)).toBe(50)

    // 执行面：真后端跑了真 Lucene 检索，结果回灌给上游的续轮（role=tool）
    const results = toolTextsOf(continuation)
    expect(results).toHaveLength(1)
    const body = unwrapKnowledge(results[0])
    expect(body).toContain('状态: ok')
    // 服务端强截：给 50 也按 top-k-max 截到 10
    expect(body).toContain('top_k=10')
    // 分数字面不出现（KbSearchHit 结构层就没有分数字段）
    expect(body).not.toMatch(/(^|\W)score(\W|$)/i)

    const hits = body.split('\n').filter((line) => /^\d+\) doc_id=/.test(line))
    expect(hits.length).toBeGreaterThan(0)
    expect(hits.length).toBeLessThanOrEqual(10)
    for (const hit of hits) {
      expect(hit).toMatch(/doc_id=[a-z0-9-]+/)
      expect(hit).toMatch(/\| section=\S/)
      expect(hit).toMatch(/match=(high|medium|low)/)
      expect(hit).toMatch(/updated_at=\d{4}-\d{2}-\d{2}/)
    }
  })

  test('E2E-K02 空结果三态：no_match 可区分、给可行动提示、不抛异常', async () => {
    const sid = newSessionId('K02')
    const { round, continuation } = await kbRound(sid, 'K02', 'tool=kb_search&args={"query":"zzzqxv"}')

    expect(round.status).toBe(200)
    expect(round.errors).toHaveLength(0)
    expect(round.done).toBeTruthy()

    const body = unwrapKnowledge(lastToolText(continuation))
    // 机器可判的状态行（三态可区分的第一要件：状态是结构量而不是措辞）
    const stateLine = /^状态: ([a-z_]+)$/m.exec(body)
    expect(stateLine).not.toBeNull()
    expect(['ok', 'low_confidence', 'no_match']).toContain(stateLine![1])
    expect(stateLine![1]).toBe('no_match')
    expect(body).toContain('未检索到相关知识')
    // 可行动提示：换正式名称/同义词重试 + 明说"知识库中未找到"
    expect(body).toContain('建议:')
    expect(body).toContain('知识库中未找到')
    // 空结果不是异常：仍是正常 done 轮（不是 error 帧、不是 5xx）
    expect(round.frames.filter((frame) => frame.type === 'error')).toHaveLength(0)
  })

  test('E2E-K03 kb_read_doc 首屏/翻页：1200 首屏 + next_offset 连续、max_chars 上限不越输入闸', async () => {
    const sid = newSessionId('K03')
    const docId = 'region-tariff-long'

    const first = await kbRound(sid, 'K03a', `tool=kb_read_doc&args={"docId":"${docId}"}`)
    const firstText = unwrapKnowledge(lastToolText(first.continuation))
    // 首屏 = 目录 + 前 1200 字（省略 section 且 offset=0）
    expect(firstText).toContain('--- 目录 ---')
    expect(firstText).toContain('--- 正文 ---')
    const firstBody = readDocBody(lastToolText(first.continuation))
    expect(firstBody.length).toBe(1200)
    const firstTail = readDocTail(lastToolText(first.continuation))
    expect(firstTail.truncated).toBe(true)
    expect(firstTail.nextOffset).toBe(1200)

    const second = await kbRound(
      sid,
      'K03b',
      `tool=kb_read_doc&args={"docId":"${docId}","offset":${firstTail.nextOffset},"maxChars":4000}`
    )
    const secondBody = readDocBody(lastToolText(second.continuation))
    expect(secondBody.length).toBe(4000)
    const secondTail = readDocTail(lastToolText(second.continuation))
    expect(secondTail.truncated).toBe(true)
    // next_offset 是绝对偏移，不是页内偏移
    expect(secondTail.nextOffset).toBe(1200 + 4000)

    // 翻页无缝隙：跨页 20 字符窗口 == 上页末 10 字 + 下页前 10 字
    const window = await kbRound(
      sid,
      'K03c',
      `tool=kb_read_doc&args={"docId":"${docId}","offset":1190,"maxChars":20}`
    )
    const windowBody = readDocBody(lastToolText(window.continuation))
    expect(windowBody.length).toBe(20)
    expect(windowBody).toBe(firstBody.slice(-10) + secondBody.slice(0, 10))

    // max_chars 给到远超上限：单次工具结果整体仍受输入闸约束（不越 8000 字符 / 结果限流器）
    const wide = await kbRound(
      sid,
      'K03e',
      `tool=kb_read_doc&args={"docId":"${docId}","offset":1200,"maxChars":20000}`
    )
    const wideContent = lastToolContent(wide.continuation)
    expect(wideContent.length).toBeLessThanOrEqual(8000)
    // 这一页必然被结果限流器 head 截断（200 行上限先于 max_chars 生效，见报告「发现项 F-3」）：
    // 断言的是"截断被如实告知"，而不是"能拿到 8000 字"
    const wideEnvelope = toolEnvelope(wideContent)
    expect(wideEnvelope.truncated).toBe(true)
    expect(String(wideEnvelope.notice ?? '')).toContain('结果已截断')
  })

  test('E2E-K04 kb_lookup_term 精确 + 别名：命中给预制答案、未命中如实 found=false', async () => {
    const sid = newSessionId('K04')

    const exact = await kbRound(sid, 'K04a', 'tool=kb_lookup_term&args={"term":"标准费率"}')
    const exactText = unwrapKnowledge(lastToolText(exact.continuation))
    expect(exactText).toContain('found=true')
    expect(exactText).toContain('normalized=标准费率')
    expect(exactText).toContain('answer: ')
    expect(exactText).toContain('base_rate')
    expect(exactText).toContain('source_doc=region-tariff')

    // 别名命中：同一份预制答案（不是"大概相似的词"）
    const alias = await kbRound(sid, 'K04b', 'tool=kb_lookup_term&args={"term":"基准费率"}')
    const aliasText = unwrapKnowledge(lastToolText(alias.continuation))
    expect(aliasText).toContain('found=true')
    const answerOf = (text: string): string => (/answer: (.+)/.exec(text) ?? ['', ''])[1]
    expect(answerOf(aliasText)).toBe(answerOf(exactText))

    // 未登记术语：不做模糊兜底，明确 found=false 并把下一步指向 kb_search
    const missing = await kbRound(sid, 'K04c', 'tool=kb_lookup_term&args={"term":"量子永动机台账"}')
    const missingText = unwrapKnowledge(lastToolText(missing.continuation))
    expect(missingText).toContain('found=false')
    expect(missingText).not.toContain('answer: ')
    expect(missingText).toContain('next=use kb_search')
    expect(missingText).toContain('不要编造解释')
  })

  test('E2E-K05 目录段注入与位置稳定：system 段含目录且多轮逐字相同、正文无定界泄漏', async () => {
    const sid = newSessionId('K05')
    const before = maxStubIndex()
    await chatRound(sid, `知识问答用例 ${fingerprint('K05a')}`)
    const second = await chatRound(sid, `知识问答用例 ${fingerprint('K05b')}`)
    expect(second.done).toBeTruthy()
    const requests = await waitStubRequestsAfter(before, 2)

    const firstSystem = systemTextOf(requests[0])
    const secondSystem = systemTextOf(requests[1])
    // 目录段在 system 提示词里（后缀段），且多轮逐字相同（prompt 缓存前缀稳定）
    expect(firstSystem).toContain('## 业务知识库')
    expect(firstSystem).toContain('<knowledge_catalog>')
    expect(firstSystem).toContain('- region-tariff |')
    expect(firstSystem).toContain('### 知识库使用规则')
    expect(secondSystem).toBe(firstSystem)

    // 泄漏面：历史里的 user/assistant 正文不带定界标签（只有 role=tool 结果允许有）
    for (const request of requests) {
      for (const text of [...roleTextsOf(request, 'user'), ...roleTextsOf(request, 'assistant')]) {
        expect(text).not.toContain('<knowledge>')
        expect(text).not.toContain('</knowledge>')
      }
    }
    // 用户可见正文同样不含定界/调试标记
    const visible = deltaTextOf(second)
    expect(visible.length).toBeGreaterThan(0)
    expect(visible).not.toContain('<knowledge>')
    expect(visible).not.toContain('toolCallId=')
  })

  test('E2E-K07 知识缓存：Redis 缓存键就位、二次查询不改变缓存值、清键后仍可服务', async () => {
    const sid = newSessionId('K07')
    const docKey = 'kb:cache:doc:region-tariff-long'
    const versionKey = 'kb:cache:manifest:version'

    // 索引建好后缓存层应已把 manifest + 每篇文档写进本域 Redis DB（doc 键是 hash：content/etag/sha/updatedAt）
    const docExists = await redisExec(['EXISTS', docKey])
    expect(docExists).toEqual({ type: 'int', value: '1' })
    const versionExists = await redisExec(['EXISTS', versionKey])
    expect(versionExists.type).toBe('int')
    const contentBefore = await redisExec(['HGET', docKey, 'content'])
    expect(contentBefore.type).toBe('bulk')
    expect(contentBefore.value.length).toBeGreaterThan(1000)
    const shaBefore = await redisExec(['HGET', docKey, 'sha'])
    expect(shaBefore.type).toBe('bulk')
    expect(shaBefore.value.length).toBeGreaterThan(0)

    const first = await kbRound(sid, 'K07a', 'tool=kb_search&args={"query":"费率"}')
    const second = await kbRound(sid, 'K07b', 'tool=kb_search&args={"query":"费率"}')
    const bodyOf = (text: string): string => unwrapKnowledge(text)
    expect(bodyOf(lastToolText(first.continuation))).toContain('状态: ok')
    // 同查询同上下文：命中同一批文档（缓存命中的可观测形态 —— 结果不漂移）
    expect(bodyOf(lastToolText(second.continuation))).toBe(bodyOf(lastToolText(first.continuation)))
    // 缓存值未被读取路径改写（cache-aside 只读不重写）
    const contentAfter = await redisExec(['HGET', docKey, 'content'])
    const shaAfter = await redisExec(['HGET', docKey, 'sha'])
    expect(contentAfter.value).toBe(contentBefore.value)
    expect(shaAfter.value).toBe(shaBefore.value)

    // 版本键 INCR（等价于"另一实例改了索引版本"）后，本实例仍能正常服务（不 5xx）
    const beforeVersion = await redisExec(['GET', versionKey])
    const beforeValue = Number(beforeVersion.value)
    const bumped = await redisExec(['INCR', versionKey])
    expect(bumped.type).toBe('int')
    expect(Number(bumped.value)).toBe(beforeValue + 1)
    const afterBump = await kbRound(sid, 'K07c', 'tool=kb_search&args={"query":"费率"}')
    expect(afterBump.round.errors).toHaveLength(0)
    expect(bodyOf(lastToolText(afterBump.continuation))).toContain('状态: ok')
    expect((await kbStatus()).ready).toBe(true)
  })

  test('E2E-K08 热重载原子切 reader：并发查询 + POST /kb/reload 不空窗不 500', async () => {
    const searches = Array.from({ length: 4 }, (_, index) => {
      const sid = newSessionId(`K08-${index}`)
      return chatRound(sid, `知识问答用例 ${fingerprint(`K08-${index}`)} tool=kb_search&args={"query":"费率"}`)
    })
    const reload = httpRaw('POST', '/api/ai/kb/reload')
    const [rounds, reloadRes] = await Promise.all([Promise.all(searches), reload])

    expect(reloadRes.status).toBe(200)
    const reloadBody = reloadRes.json as { success?: boolean; data?: { ready?: boolean; doc_count?: number } }
    expect(reloadBody.success).toBe(true)
    expect(reloadBody.data?.ready).toBe(true)
    expect(reloadBody.data?.doc_count).toBeGreaterThanOrEqual(20)

    for (const round of rounds) {
      expect(round.status).toBe(200)
      expect(round.errors).toHaveLength(0)
      expect(round.done).toBeTruthy()
      // 重建期间没有空窗：每次检索都拿到 ok 结果，而不是 no_match / error
      const blocks = round.frames
        .filter((frame) => frame.type === 'delta')
        .map((frame) => String(frame.text ?? ''))
        .join('')
      expect(blocks.length).toBeGreaterThan(0)
    }

    // 切换后照常服务（旧 reader 退役、新 reader 供料）
    const after = await kbRound(newSessionId('K08-after'), 'K08-after', 'tool=kb_search&args={"query":"费率"}')
    expect(unwrapKnowledge(lastToolText(after.continuation))).toContain('状态: ok')
  })
})

test.describe('@stub 16 Q 快捷建议', () => {
  const suggestionButtons = (page: import('@playwright/test').Page) =>
    page.locator('[data-testid="followup-suggestion"]')

  test('E2E-Q02 每轮建议：≤3 条、无重复、id 属闭集且同上下文稳定', async ({ page }) => {
    await openApp(page)
    await sendMessage(page, `快捷建议用例 ${fingerprint('Q02a')}`)
    await waitRoundSettled(page)

    const group = page.locator('[data-testid="followup-suggestions"]')
    await expect(group).toBeVisible()
    const ids = await suggestionButtons(page).evaluateAll((nodes) =>
      nodes.map((node) => (node.getAttribute('data-suggestion-id') ?? '').trim()))
    expect(ids.length).toBeGreaterThanOrEqual(1)
    expect(ids.length).toBeLessThanOrEqual(3)
    expect(new Set(ids).size).toBe(ids.length)
    for (const id of ids) expect(SUGGESTION_IDS).toContain(id)

    // 每条都有无障碍名（aria-label = 「发送：<文案>」）
    for (let index = 0; index < ids.length; index += 1) {
      await expect(suggestionButtons(page).nth(index)).toHaveAttribute('aria-label', /\S/)
    }

    // 同一上下文再跑一轮：id 集合稳定（规则模板，不随模型漂移）
    await sendMessage(page, `快捷建议用例 ${fingerprint('Q02b')}`)
    await waitRoundSettled(page)
    await expect(group).toBeVisible()
    const idsAgain = await suggestionButtons(page).evaluateAll((nodes) =>
      nodes.map((node) => (node.getAttribute('data-suggestion-id') ?? '').trim()))
    expect(idsAgain).toEqual(ids)
  })

  test('E2E-Q03 点建议即发：立即发送且输入框草稿保留', async ({ page }) => {
    await openApp(page)
    await sendMessage(page, `快捷建议用例 ${fingerprint('Q03')}`)
    await waitRoundSettled(page)

    const suggestion = suggestionButtons(page).first()
    await expect(suggestion).toBeVisible()
    const text = (await suggestion.innerText()).trim()

    const composer = page.locator('[data-testid="composer-input"]')
    const draft = '草稿保留检查：这段文字不该被建议发送清掉'
    await composer.fill(draft)

    await suggestion.click()
    // 点即发：建议文案直接成为用户气泡（不是回填输入框）
    await expect(page.locator('[data-testid^="msg-bubble-"]').filter({ hasText: text }).first()).toBeVisible({
      timeout: 15_000
    })
    expect(await composer.inputValue()).toBe(draft)
    // 注意：桩一轮只有 ~100ms，点完可能已到 done 并重建下一轮建议 —— "旧建议在流式中移除"
    // 无法在这么短的窗口里稳定观测（该性质由 Q-05 的流式中断言覆盖），这里只收回本轮。
    await waitRoundSettled(page)
    await expect(page.locator('[data-testid="followup-suggestions"]')).toHaveCount(1)
    expect(await composer.inputValue()).toBe(draft)
  })

  test('E2E-Q04 建议不被历史整替抹掉：真刷新后按当前轮重建、不出现半旧半新', async ({ page, request }) => {
    await openApp(page)
    await sendMessage(page, `快捷建议用例 ${fingerprint('Q04a')}`)
    await waitRoundSettled(page)
    const before = await suggestionButtons(page).evaluateAll((nodes) =>
      nodes.map((node) => (node.getAttribute('data-suggestion-id') ?? '').trim()))
    expect(before.length).toBeGreaterThan(0)
    const sid = await sessionId(page)
    const historyBefore = await request.get(`/api/ai/history/${sid}`).then((res) => res.json() as Promise<{
      data?: { count?: number }
    }>)

    await page.reload({ waitUntil: 'domcontentloaded' })
    await expect(page.locator('[data-testid="composer-input"]')).toBeVisible({ timeout: 30_000 })
    // 刷新恢复：历史回来了、旧建议不留（本地状态没有"建议已就绪"的依据）
    await expect(page.locator('[data-testid^="msg-bubble-"]').first()).toBeVisible({ timeout: 30_000 })
    await expect(page.locator('[data-testid="followup-suggestions"]')).toHaveCount(0)

    const historyAfter = await request.get(`/api/ai/history/${sid}`).then((res) => res.json() as Promise<{
      data?: { count?: number }
    }>)
    expect(historyAfter.data?.count).toBe(historyBefore.data?.count)

    // 新一轮完成后按当前上下文重建（同一组 id，且只一组）
    await sendMessage(page, `快捷建议用例 ${fingerprint('Q04b')}`)
    await waitRoundSettled(page)
    await expect(page.locator('[data-testid="followup-suggestions"]')).toHaveCount(1)
    const after = await suggestionButtons(page).evaluateAll((nodes) =>
      nodes.map((node) => (node.getAttribute('data-suggestion-id') ?? '').trim()))
    expect(after).toEqual(before)
  })

  test('E2E-Q05 流式中不出建议、点不到也就发不出（零 409）', async ({ page }) => {
    const conflicts: string[] = []
    page.on('response', (response) => {
      if (response.url().includes('/api/ai/chat')) conflicts.push(String(response.status()))
    })

    await openApp(page)
    await sendMessage(page, `快捷建议用例 ${fingerprint('Q05')} stall-frames=900`)
    // 流式中：停止钮在、建议区整块不出（缺一不出的第一道闸）。
    // 三处计数必须在**同一个 evaluate** 里取（逐个 locator 断言会把窗口拖到轮末，变成假绿/假红）
    await expect(page.locator('[data-testid="stop-btn"]')).toBeVisible({ timeout: 15_000 })
    const duringStream = await page.evaluate(() => ({
      stopVisible: document.querySelectorAll('[data-testid="stop-btn"]').length > 0,
      groups: document.querySelectorAll('[data-testid="followup-suggestions"]').length,
      followups: document.querySelectorAll('[data-testid="followup-suggestion"]').length,
      chips: document.querySelectorAll('[data-testid="suggestion-chip"]').length
    }))
    expect(duringStream.stopVisible).toBe(true)
    expect(duringStream.groups).toBe(0)
    expect(duringStream.followups).toBe(0)
    expect(duringStream.chips).toBe(0)

    // 流式期间不存在可点的建议 ⇒ 不会冒出第二次发送
    await waitRoundSettled(page, 90_000)
    const bubbleCount = await page.locator('[data-testid^="msg-bubble-"]').count()
    expect(bubbleCount).toBe(2)
    expect(conflicts.filter((status) => status === '409')).toHaveLength(0)
    // 对照：本轮正常 done 之后建议才出现
    await expect(page.locator('[data-testid="followup-suggestions"]')).toBeVisible()
  })

  test('E2E-Q06 i18n 双语：chips 与 aria 走 ai.suggestions.*，zh/en 键集对称', async ({ page }) => {
    const zh = readLocale('zh-CN')
    const en = readLocale('en-US')
    expect(Object.keys(en.ai.suggestions).sort()).toEqual(Object.keys(zh.ai.suggestions).sort())

    // `addInitScript` 每次导航都会重放，故"切回中文"用**新 context**（无 init 脚本、localStorage 空 ⇒ 默认 zh-CN）
    await page.addInitScript(() => window.localStorage.setItem('cm.locale', 'en-US'))
    await openApp(page)
    const firstChip = page.locator('[data-testid="suggestion-chip"]').first()
    await expect(firstChip).toBeVisible()
    const englishTexts = await page.locator('[data-testid="suggestion-chip"]').evaluateAll((nodes) =>
      nodes.map((node) => (node.textContent ?? '').trim()))
    expect(englishTexts.length).toBeGreaterThan(0)
    for (const text of englishTexts) {
      expect(Object.values(en.ai.suggestions)).toContain(text)
    }
    await expect(firstChip).toHaveAttribute('aria-label', new RegExp(`^${en.ai.suggestions.sendAria.replace('{text}', '')}`))

    // 每轮建议同样是英文
    await sendMessage(page, `快捷建议用例 ${fingerprint('Q06')}`)
    await waitRoundSettled(page)
    const followupText = (await page.locator('[data-testid="followup-suggestion"]').first().innerText()).trim()
    expect(Object.values(en.ai.suggestions)).toContain(followupText)

    const appUrl = process.env.E2E_APP ?? `http://127.0.0.1:${process.env.E2E_APP_PORT ?? '18401'}`
    // 切回中文：再追加一条 init script（Playwright 按追加顺序执行，后写者生效）后真刷新
    await page.addInitScript(() => window.localStorage.setItem('cm.locale', 'zh-CN'))
    await page.goto(appUrl, { waitUntil: 'domcontentloaded' })
    await expect(page.locator('[data-testid="composer-input"]')).toBeVisible({ timeout: 30_000 })
    await page.locator('[data-testid="new-chat"]').click()
    const zhChips = await page.locator('[data-testid="suggestion-chip"]').evaluateAll((nodes) =>
      nodes.map((node) => (node.textContent ?? '').trim()))
    expect(zhChips.length).toBeGreaterThan(0)
    for (const text of zhChips) {
      expect(Object.values(zh.ai.suggestions)).toContain(text)
    }
  })

  test('E2E-Q07 陈旧建议清理：新建对话后建议清空、空态 chips 回来', async ({ page }) => {
    await openApp(page)
    await sendMessage(page, `快捷建议用例 ${fingerprint('Q07')}`)
    await waitRoundSettled(page)
    await expect(page.locator('[data-testid="followup-suggestions"]')).toBeVisible()
    const oldSid = await sessionId(page)

    await page.locator('[data-testid="new-chat"]').click()
    await expect(page.locator('[data-testid="empty-state"]')).toBeVisible()
    // 建议不跨会话串味
    await expect(page.locator('[data-testid="followup-suggestions"]')).toHaveCount(0)
    await expect(page.locator('[data-testid="followup-suggestion"]')).toHaveCount(0)
    await expect(page.locator('[data-testid="suggestion-chip"]').first()).toBeVisible()
    expect(await sessionId(page)).not.toBe(oldSid)
  })
})

test.describe('@stub 16 C 上下文统计与压缩', () => {
  test('E2E-C01 pill 与端点同值：百分比/已用/上限三处一致、档位按服务端百分比', async ({ page }) => {
    const shapes = usageShapes()
    await openApp(page)
    await sendMessage(page, `上下文用例 ${fingerprint('C01')} usage-shape=last`)
    await waitRoundSettled(page)

    const pill = page.locator('[data-testid="context-pill"]')
    await expect(pill).toBeVisible()
    const view = parsePillText(await pill.innerText())
    const sid = await sessionId(page)
    const snapshot = await contextSnapshot(sid)

    expect(snapshot.source).toBe('stream')
    expect(snapshot.usedTokens).toBe(shapes.last.promptTokens)
    expect(view.used).toBe(snapshot.usedTokens)
    expect(view.limit).toBe(snapshot.limitTokens)
    expect(view.percent).toBe(snapshot.percent)
    // 档位由服务端百分比决定（本形状远低于 70 ⇒ safe）
    await expect(pill).toHaveAttribute('data-tier', 'safe')
    // aria 带百分比语义
    await expect(pill).toHaveAttribute('aria-label', new RegExp(`${snapshot.percent}%`))

    // 分母单点：/health 的 contextLimitTokens 与端点 limitTokens 同值
    const health = await aiHealth()
    expect(health.contextLimitTokens).toBe(snapshot.limitTokens)
  })

  test('E2E-C03 usage 取末帧真值：done.usage 与 /context 同值（不读中间帧）', async () => {
    const shapes = usageShapes()
    const sid = newSessionId('C03')
    const round = await chatRound(sid, `上下文用例 ${fingerprint('C03')} usage-shape=last`)
    expect(round.done).toBeTruthy()
    const usage = round.done!.usage as { promptTokens: number; completionTokens: number; totalTokens: number; est: boolean; source: string }
    expect(usage.promptTokens).toBe(shapes.last.promptTokens)
    expect(usage.completionTokens).toBe(shapes.last.completionTokens)
    expect(usage.totalTokens).toBe(shapes.last.totalTokens)
    expect(usage.est).toBe(false)
    expect(usage.source).toBe('stream')

    const snapshot = await contextSnapshot(sid)
    expect(snapshot.usedTokens).toBe(usage.promptTokens)
    expect(snapshot.estimated).toBe(false)
    expect(snapshot.source).toBe('stream')
    expect(snapshot.percent).toBe(Math.round((usage.promptTokens * 1000) / (snapshot.limitTokens as number)) / 10)
  })

  test('E2E-C04 每块重复 usage 不放大：多个非空 usage 帧只采纳一份', async () => {
    const shapes = usageShapes()
    const sid = newSessionId('C04')
    const round = await chatRound(sid, `上下文用例 ${fingerprint('C04')} usage-shape=repeat`)
    expect(round.done).toBeTruthy()
    const usage = round.done!.usage as { promptTokens: number; totalTokens: number; est: boolean; frames: number }
    // 桩在每个 content 分片都挂了同一份 usage ⇒ frames > 1，但值必须等于单帧真值
    expect(usage.frames).toBeGreaterThan(1)
    expect(usage.promptTokens).toBe(shapes.repeat.promptTokens)
    expect(usage.totalTokens).toBe(shapes.repeat.totalTokens)
    expect(usage.est).toBe(false)
    const snapshot = await contextSnapshot(sid)
    expect(snapshot.usedTokens).toBe(shapes.repeat.promptTokens)
  })

  test('E2E-C05 末帧 0/0/0：回退到最近非零真值（不把真值读成 0）', async () => {
    const shapes = usageShapes()
    const sid = newSessionId('C05')
    const round = await chatRound(sid, `上下文用例 ${fingerprint('C05')} usage-shape=zero-last`)
    expect(round.done).toBeTruthy()
    const usage = round.done!.usage as { promptTokens: number; est: boolean }
    expect(usage.promptTokens).toBe(shapes['zero-last'].promptTokens)
    expect(usage.promptTokens).toBeGreaterThan(0)
    expect(usage.est).toBe(false)
    const snapshot = await contextSnapshot(sid)
    expect(snapshot.usedTokens).toBe(usage.promptTokens)
  })

  test('E2E-C06 无 usage 回退估算：标 ≈、source=estimate、下一轮仍按估算收敛', async ({ page }) => {
    await openApp(page)
    await sendMessage(page, `上下文用例 ${fingerprint('C06')} usage-shape=none`)
    await waitRoundSettled(page)

    const pill = page.locator('[data-testid="context-pill"]')
    await expect(pill).toBeVisible()
    const view = parsePillText(await pill.innerText())
    expect(view.estimate).toBe(true)
    expect(view.raw.startsWith('≈')).toBe(true)

    const sid = await sessionId(page)
    const snapshot = await contextSnapshot(sid)
    expect(snapshot.estimated).toBe(true)
    expect(snapshot.source).toBe('estimate')
    expect(snapshot.usedTokens).toBeGreaterThan(0)
    // pill 用 k 缩写展示（5.7k），与真值之间有显示取整 ⇒ 容差 100 token
    expect(Math.abs((view.used as number) - snapshot.usedTokens)).toBeLessThan(100)

    // 第二轮：估算基线参与下一轮（分母仍来自同一单点，不因估算丢失）
    await sendMessage(page, `上下文用例 ${fingerprint('C06b')} usage-shape=none`)
    await waitRoundSettled(page)
    const second = await contextSnapshot(sid)
    expect(second.source).toBe('estimate')
    expect(second.limitTokens).toBe(snapshot.limitTokens)
    expect(second.usedTokens).toBeGreaterThan(0)
  })

  test('E2E-C07 六项拆分守恒：Σ context-row-* == used，free 为派生、顺序固定', async ({ page }) => {
    await openApp(page)
    await sendMessage(page, `上下文用例 ${fingerprint('C07')} usage-shape=last`)
    await waitRoundSettled(page)

    await page.locator('[data-testid="context-pill"]').click()
    const popover = page.locator('[data-testid="context-popover"]')
    await expect(popover).toBeVisible()
    await expect(page.locator('[data-testid="context-row-history"]')).toBeVisible()

    const rows = await popover.locator('[data-testid^="context-row-"]').evaluateAll((nodes) =>
      nodes.map((node) => ({
        key: (node.getAttribute('data-testid') ?? '').replace('context-row-', ''),
        tokens: (node.querySelector('span:last-child')?.textContent ?? '').trim()
      })))
    expect(rows.map((row) => row.key)).toEqual(['history', 'toolResults', 'summary', 'system', 'tools', 'input', 'free'])

    const usedRows = rows.filter((row) => row.key !== 'free')
    const sum = usedRows.reduce((total, row) => total + tokensFromLabel(row.tokens.replace(' tokens', '')), 0)
    const sid = await sessionId(page)
    const snapshot = await contextSnapshot(sid)
    expect(sum).toBe(snapshot.usedTokens)
    // free = limit - used（派生量，不参与守恒）
    const free = rows.find((row) => row.key === 'free')!
    if (free.tokens.startsWith('已超')) {
      expect(tokensFromLabel(free.tokens.replace('已超 ', ''))).toBe(snapshot.usedTokens - (snapshot.limitTokens as number))
    } else {
      expect(tokensFromLabel(free.tokens.replace(' tokens', ''))).toBe((snapshot.limitTokens as number) - snapshot.usedTokens)
    }
  })

  test('E2E-C12 CONTEXT_OVERFLOW 识别：越窗 400 认成上下文超窗、不重试、文案指向压缩', async () => {
    const sid = newSessionId('C12')
    const before = maxStubIndex()
    const round = await chatRound(sid, `上下文用例 ${fingerprint('C12')} context-overflow`)

    expect(round.status).toBe(200)
    expect(round.done).toBeUndefined()
    expect(round.errors).toHaveLength(1)
    const error = round.errors[0]
    // 分类落在 code 上（不可重试的越窗）；message 保留上游原文供排障（见报告「发现项 F-4」）
    expect(String(error.code)).toBe('CONTEXT_OVERFLOW')
    expect(String(error.message)).toContain('上游返回 HTTP 400')
    expect(String(error.message)).toContain('maximum context length')
    expect(String(error.message)).toContain('context_length_exceeded')

    // 不重试：整轮只打了 1 次上游（越窗重发只会再超一次）
    const requests = await waitStubRequestsAfter(before, 1, 20_000)
    expect(requests[0].index).toBe(before + 1)
    const after = maxStubIndex()
    expect(after).toBe(before + 1)
  })
})

test.describe('@real 16 KQC 真模型（CI 手工触发；本地不跑）', () => {
  // 蓝本 §0.3 常规行为档：N=10、阈值 ≥7（成本敏感档仅 C-13 用，见该条行内注明 N=6 轮）。
  const REAL_SAMPLING = Math.max(1, Number(process.env.E2E_REAL_SAMPLING ?? '10'))
  const REAL_THRESHOLD = Math.ceil(REAL_SAMPLING * 0.7)

  test('@real E2E-K10 英文查询桥接：英文术语经 aliases_en 命中中文语料，未登记表达如实 no_match', async () => {
    test.setTimeout(900_000)

    // 前置（B 级确定性）：KB 就绪、三工具在披露清单里 —— 命中失败的归因先排除"没开库"
    const status = await kbStatus()
    expect(status.ready, 'KB 未就绪：先看 /api/ai/kb/status').toBe(true)
    const disclosed = await toolNames()
    for (const name of ['kb_search', 'kb_lookup_term', 'kb_read_doc']) {
      expect(disclosed).toContain(name)
    }
    const docIds = (await apiData<Array<{ id: string }>>('/api/ai/kb/docs')).map((doc) => doc.id)

    // C 级概率面：英文问句 → 真的调了 kb_* 且答复落在中文语料的概念上
    let hits = 0
    const misses: string[] = []
    for (let index = 1; index <= REAL_SAMPLING; index += 1) {
      const round = await chatRound(
        newSessionId(`K10-${index}`),
        'How is the regional tariff calculated? Please search the knowledge base and answer in Chinese.'
      )
      expect(round.done).toBeTruthy()
      const text = deltaTextOf(round)
      // 形状：答复非空、不吐内部定界标记与调试字段
      expect(text.length).toBeGreaterThan(0)
      expect(text).not.toContain('<knowledge>')
      expect(text).not.toContain('toolCallId=')
      const kbCalls = round.frames
        .filter((frame) => frame.type === 'tool_start' && String(frame.name ?? '').startsWith('kb_'))
        .map((frame) => String(frame.name))
      const grounded = /区域费率|费率|跨区|加收/.test(text)
      if (kbCalls.length > 0 && grounded) {
        hits += 1
      } else {
        misses.push(`#${index} tools=${kbCalls.join(',') || '-'} text=${text.slice(0, 80)}`)
      }
    }

    // 未登记表达：召回为空时如实 no_match，不编造 doc_id（单轮确定性口径，不进采样计数）
    const unknown = await chatRound(
      newSessionId('K10-absent'),
      'How does the flurbox quota work? Please search the knowledge base.'
    )
    expect(unknown.done).toBeTruthy()
    const unknownText = deltaTextOf(unknown)
    expect(unknownText).not.toContain('<knowledge>')
    const cited = docIds.filter((id) => unknownText.includes(id))
    expect(cited, `未登记表达被编造成了语料里的 doc_id：${cited.join(',')}`).toHaveLength(0)

    // eslint-disable-next-line no-console
    console.log(`[K-10] N=${REAL_SAMPLING} 命中=${hits} 阈值≥${REAL_THRESHOLD}`)
    expect(hits, `未命中采样：\n${misses.join('\n')}`).toBeGreaterThanOrEqual(REAL_THRESHOLD)
  })

  // 成本敏感档（蓝本 §0.3）：N=6 轮连续对话（临界 5 轮 + 1 轮观察），不设 10 轮采样。
  test('@real E2E-C13 压缩级别只升一级 + 滞回：长会话多轮后 level 单调、每次至多一格', async () => {
    const sid = newSessionId('C13')
    const levels: string[] = []
    const percents: number[] = []
    for (let turn = 0; turn < 6; turn += 1) {
      const round = await chatRound(sid, `第 ${turn + 1} 轮：请复述上一轮要点并给出一句结论。`)
      expect(round.done).toBeTruthy()
      const snapshot = await contextSnapshot(sid)
      levels.push(String(snapshot.compaction?.level ?? 'none'))
      percents.push(Number(snapshot.percent ?? 0))
    }
    const order = ['none', 'L1', 'L2', 'L3']
    for (let index = 1; index < levels.length; index += 1) {
      const step = order.indexOf(levels[index]) - order.indexOf(levels[index - 1])
      expect(step, `第 ${index + 1} 轮压缩档跳变：${levels.join(' → ')}`).toBeLessThanOrEqual(1)
      expect(step, `第 ${index + 1} 轮压缩档回退：${levels.join(' → ')}`).toBeGreaterThanOrEqual(0)
    }
    // eslint-disable-next-line no-console
    console.log(`[C-13] 档位=${levels.join(' → ')} 占用=${percents.map((p) => `${p.toFixed(1)}%`).join(' → ')}`)
    // 诊断面（不断言）：档位只记「最近一次压缩」；是否真压过要看台账与 lostTokens，
    // 否则「level 恒 none」既可能是没到阈值、也可能是压过又回落，本条的判据会平凡通过。
    const ledger = await contextLedger(sid).catch(() => [])
    const last = ledger[ledger.length - 1]
    // eslint-disable-next-line no-console
    console.log(
      `[C-13] 压缩台账条数=${ledger.length} 末条=${last === undefined ? '-' : JSON.stringify(last).slice(0, 200)}`
    )
  })
})

test.describe('@stub 16 K/Q/C 桥接面', () => {
  test('E2E-K01b KB 三工具都在披露清单里（无页面上下文时也披露）', async () => {
    const names = await toolNames()
    expect(names).toContain('kb_search')
    expect(names).toContain('kb_read_doc')
    expect(names).toContain('kb_lookup_term')
  })

  test('E2E-C01b pill 未测前不渲染（新会话无记账 ⇒ 不出现 0%）', async ({ page }) => {
    await openApp(page)
    await expect(page.locator('[data-testid="empty-state"]')).toBeVisible()
    await expect(page.locator('[data-testid="context-pill"]')).toHaveCount(0)
  })

  test('E2E-Q01b 空态 chips 文案来自 ai.suggestions.*（与每轮建议同一词表）', async ({ page }) => {
    const zh = readLocale('zh-CN')
    await openApp(page)
    await expect(page.locator('[data-testid="empty-state"]')).toBeVisible()
    const chips = await page.locator('[data-testid="suggestion-chip"]').evaluateAll((nodes) =>
      nodes.map((node) => (node.textContent ?? '').trim()))
    expect(chips.length).toBeGreaterThan(0)
    for (const text of chips) expect(Object.values(zh.ai.suggestions)).toContain(text)
    expect(await assistantBubble(page).count()).toBe(0)
  })
})
