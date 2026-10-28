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
  answerTextOf,
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
  waitStubRequestsAfter,
  withModelWindow,
  type Frame
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
    // 近似标题只用于换词重查：no_match 输出里不得出现任何 doc_id（K-10 确定性泄漏面 ——
    // suggestTitles 曾把 id 拼进候选串，模型在答复里复述了候选 doc_id，同时说明并非命中）
    const docIds = (await apiData<Array<{ id: string }>>('/api/ai/kb/docs')).map((doc) => doc.id)
    const leaked = docIds.filter((id) => body.includes(id))
    expect(leaked, `no_match 输出泄漏了候选 doc_id：${leaked.join(',')}\n工具输出：${body}`).toHaveLength(0)
  })

  /**
   * K-03 首屏 / 翻页 / 页大小的两把尺子。
   *
   * <p><b>判据随迁（L-87，2026-10-23 实测）</b>：L-87 把 `kb_read_doc` 的页大小改为按**模型侧预算**
   * 派生并复核（`KbTools#readPage`），于是"max_chars=20000 的一页必然被结果限流器 head 截断
   * （`truncated=true` + `结果已截断`）"不再成立 —— 实测该页 2327 字 / 正文 102 行、
   * `next_offset=3527`、限流器 `truncated=false`。这是**修复后的期望行为**（整页对模型可见，
   * 截断标记不再吃掉 `</knowledge>` 与 next_offset），不是"预算没生效"：
   * 页长被行预算（`max-rows` 200 − 渲染开销）压在 8000 字之下，且 `next_offset` 连续。
   * 故判据改为「页大小同时受字符与行预算约束、翻页无洞」，截断面仍在（超行预算的长文档，
   * 每页止于行边界 + `truncated=true next_offset=…`）。
   */
  test('E2E-K03 kb_read_doc 首屏/翻页：1200 首屏 + next_offset 连续、页大小受字符与行双预算', async () => {
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

    // ── 页大小的两把尺子（L-87）：「页大小同时受字符与行预算约束、翻页无洞」 ───────────────
    // 语义随迁（L-87 落地后）：页大小不再"先切满 8000 字再交给限流器 head 截断"，而是
    // 由**模型侧预算**派生并在返回前复核（`KbTools#readPage`：页 = 限流器行预算 − 渲染开销，
    // 复核不过就缩页重读）⇒ 旧判据「maxChars=20000 必然被限流器截断、truncated=true」
    // 已不成立：整页对模型可见，`truncated=false` 正是修复后的**期望**行为（不是预算没生效）。
    // 改判为下面四条（任何一条红都说明预算/切页真的坏了）：
    //   ① 字符尺先行：maxChars=300 ⇒ 页恰 300 字，next_offset 连续；
    //   ② 行尺接管：maxChars ≥ 8000 ⇒ 页长 < 8000 字（字符没到顶），且同一文档换 8000/20000
    //      给出**同一页**（行预算才是决定量）；03-region-tariff-long 全文 327 行，真超行预算；
    //   ③ 整页可见：限流器零截断（KbText 8000 字闸 + 模型侧 200 行闸都在页内），这是 L-87 的目的；
    //   ④ 翻页无洞：`next_offset = 起点 + 本页字数`，跨切点的 20 字窗口 == 上页末 10 字 + 下页前 10 字。
    const charPage = await kbRound(
      sid,
      'K03f',
      `tool=kb_read_doc&args={"docId":"${docId}","offset":1200,"maxChars":300}`
    )
    const charBody = readDocBody(lastToolText(charPage.continuation))
    const charTail = readDocTail(lastToolText(charPage.continuation))
    expect(charBody.length, '字符预算（maxChars=300）必须成为页大小').toBe(300)
    expect(charTail.truncated).toBe(true)
    expect(charTail.nextOffset).toBe(1200 + charBody.length)
    // 同一 offset 的两种页大小共用一套切点口径：短页就是长页的前缀（没有第二套切页算法）
    expect(secondBody.startsWith(charBody)).toBe(true)

    const wide = await kbRound(
      sid,
      'K03e',
      `tool=kb_read_doc&args={"docId":"${docId}","offset":1200,"maxChars":20000}`
    )
    const wideContent = lastToolContent(wide.continuation)
    const wideText = lastToolText(wide.continuation)
    const wideBody = readDocBody(wideText)
    const wideTail = readDocTail(wideText)
    // ① 单次工具结果整体仍在 8000 字输入闸内（max_chars 请求值不越闸）
    expect(wideContent.length).toBeLessThanOrEqual(8000)
    // ③ 整页对模型可见：限流器零截断（L-87 之后"结果已截断"只应出现在缩页也装不下的极端情形）
    expect(toolEnvelope(wideContent).truncated).toBe(false)
    // ② 行预算接管：页长远未到 8000 字 ⇒ 切点是行预算给的；行数仍在模型侧 200 行闸内
    expect(wideBody.length).toBeLessThan(8000)
    const wideRows = wideBody.split('\n').length
    expect(wideRows, '页内行数必须不越模型侧 200 行闸').toBeLessThanOrEqual(200)
    expect(wideRows, '327 行的文档一页装不下 ⇒ 必须真的在行上被切').toBeGreaterThan(1)
    expect(wideTail.truncated, '文档还有下一页 ⇒ 尾标必须如实说 truncated=true').toBe(true)
    // ④ 翻页无洞（行切点）：next_offset = 本页末字的下一位（绝对偏移），跨切点窗口的前半 = 本页末 10 字
    expect(wideTail.nextOffset).toBe(1200 + wideBody.length)
    const seam = await kbRound(
      sid,
      'K03h',
      `tool=kb_read_doc&args={"docId":"${docId}","offset":${wideTail.nextOffset - 10},"maxChars":20}`
    )
    const seamBody = readDocBody(lastToolText(seam.continuation))
    expect(seamBody.length).toBe(20)
    // 重叠即"重"、不等即"漏"：窗口前半必须是本页最后 10 字，后半就是下一页的开头
    expect(seamBody.slice(0, 10), '行预算切点处出现了缝（本页末 10 字与 next_offset 处读到的内容不一致）')
      .toBe(wideBody.slice(-10))

    // ② 续：字符预算从 8000 提到 20000，页不变 ⇒ 决定页大小的是行预算，不是字符预算
    const wide8 = await kbRound(
      sid,
      'K03i',
      `tool=kb_read_doc&args={"docId":"${docId}","offset":1200,"maxChars":8000}`
    )
    expect(readDocBody(lastToolText(wide8.continuation)), '8000 与 20000 的字符预算必须给出同一页（行预算接管）')
      .toBe(wideBody)
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
    // 正文完整性：缓存壳里不得出现 U+FFFD。这条把"读侧把多字节字符切坏"（TCP 分块逐块解码）
    // 与"写侧用了错误字符集"两种故障都变成一句话可读的红，而不是靠后面"两次读取相等"的难读 diff。
    expect(contentBefore.value, '缓存正文含 U+FFFD（读侧分块解码或写侧字符集有误）').not.toContain('\uFFFD')
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

  test('E2E-C12 CONTEXT_OVERFLOW 识别：越窗 400 认成上下文超窗、不重试、用户面安全文案', async () => {
    const sid = newSessionId('C12')
    const before = maxStubIndex()
    const round = await chatRound(sid, `上下文用例 ${fingerprint('C12')} context-overflow`)

    expect(round.status).toBe(200)
    expect(round.done).toBeUndefined()
    expect(round.errors).toHaveLength(1)
    const error = round.errors[0]
    // 分类落在 code 上（不可重试的越窗）。用户面文案 = R-08 白名单预置短句
    // （ResilientChatService.SAFE_FAILURE_MESSAGES，2026-10-24 裁决：原始 body / 异常只进
    // 日志与台账，绝不再回到用户面 —— 旧口径“message 保留上游原文”已废弃）。
    expect(String(error.code)).toBe('CONTEXT_OVERFLOW')
    expect(String(error.message)).toBe('对话内容过长，请开启新会话后重试。')
    // 反向锁：上游 400 的状态行、body 措辞与错误分类都不得泄漏到用户面
    for (const leaked of ['上游返回 HTTP 400', 'maximum context length',
      'context_length_exceeded', 'invalid_request_error', '300001']) {
      expect(String(error.message)).not.toContain(leaked)
    }

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
  /**
   * C-13 的压缩分母（`ai_model.max_context`）：必须显著大于 `reserve-tokens`（默认 13000），
   * 否则 `limit − reserve` 被钳到 1、逐轮直冲 L3。取 15000 ⇒ 预算 2000，
   * 真模型 6 轮对话 + 工具结果的体量足够把 L1（清工具结果）走完再进 L2（折叠旧轮）。
   * 与 `verify-e2e.ps1` 的启动回退值 20000 不同 ⇒ "分母取的是模型行"这件事有断言盯着。
   */
  const REAL_LINEAGE_WINDOW = 15000

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
      const text = answerTextOf(round)
      // 形状：答复非空、不吐内部定界标记与调试字段（只看正文 —— 思考段是模型的推演草稿，
      // 不是给用户看的答复；把它算进"答复"会让"思考里念了 doc_id"被误判成编造）
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
    const unknownText = answerTextOf(unknown)
    expect(unknownText).not.toContain('<knowledge>')
    // 只查**正文**的 doc_id 出现：思考段里模型在候选间推演（"要不要再查 X？无关"）是正常推理，
    // 不是编造；正文里出现语料的 doc_id 才是把未登记表达挂到某篇文档上。
    const cited = docIds.filter((id) => unknownText.includes(id))
    // 失败信息带答复原文：仅凭 doc_id 名单无法区分"编造命中"与"如实罗列语料条目"
    expect(cited, `未登记表达被编造成了语料里的 doc_id：${cited.join(',')}\n答复原文：${unknownText}`)
      .toHaveLength(0)

    // eslint-disable-next-line no-console
    console.log(`[K-10] N=${REAL_SAMPLING} 命中=${hits} 阈值≥${REAL_THRESHOLD}`)
    // eslint-disable-next-line no-console
    console.log(`[K-10] 未登记表达答复（前 600 字）：${unknownText.slice(0, 600)}`)
    expect(hits, `未命中采样：\n${misses.join('\n')}`).toBeGreaterThanOrEqual(REAL_THRESHOLD)
  })

  // 成本敏感档（蓝本 §0.3）：N=6 轮连续对话（临界 5 轮 + 1 轮观察），不设 10 轮采样。
  /**
   * C-13 压缩谱系**真验**（L-93 判据对齐；与 `16-kqc-c.spec.ts` 的 @stub C-13 同构）。
   *
   * <p>旧判据只断言"档位单调不减、每次至多一格"——压缩从未触发时它**平凡成立**
   * （全 none 也满足），于是恒绿而无信息量。本条换成"真的压过"的四张证据面：
   *   ① 压缩台账（`GET /context/{sid}?ledger=1`）：条数 > 0、首条 L1、逐条只升一格、每条 `after < before`；
   *   ② 档位谱系：轮级 `level` 单调不减、每次至多一格，且末轮已离开 `none`；
   *   ③ `compactedTurns / lostTokens` 口径：L1 恒 0/0；L2 起 `turns > 0` 且 `lostTokens > 0`；
   *   ④ 三面同源：快照 ≡ 台账末条；本轮 SSE `context` 帧 ≡ 该轮台账（level/before/after/savedTokens）。
   *
   * <p><b>分母</b>：`ai_model.max_context` 是唯一真源（L-89/L-93，env 的
   * `app.ai.context.limit-tokens` 只是启动期回退）⇒ 用 `withModelWindow` 压成
   * {@link REAL_LINEAGE_WINDOW} 并在跑完恢复；断言同时盯住"分母 = 这个窗口"，防止它悄悄回落
   * 到默认 128000 而整条又变平凡。
   *
   * <p><b>为什么提示词要求每轮调三次工具</b>（2026-10-23，CI run 39977930803 定位）：压缩从 `none`
   * 每次至多升一格 ⇒ 首档只能是 L1，而 L1 的动作是**清工具结果**（`tool_result_clearing`）。
   * 但 L1 只清 pin 窗口之外的结果 —— `app.ai.context.keep-tool-pairs` 默认 5，最近 5 条工具结果
   * 一条都不动。于是"每轮 1 次工具调用 + 6 轮"在结构上**永远清不出东西**：第 N 轮压缩判定发生时，
   * 历史里只有 N−1 ≤ 5 条结果，全在 pin 窗口内 ⇒ `reason=nothing_to_clear` ⇒ 档位恒 `none`。
   * 首跑实测正是这一形态（档位全 none、占用已到 74.9%→106.5%、工具调用 1/1/1/1/1/1）——
   * 阈值根本不是问题，缺的是**可清的对象**。故提示词要求每轮分别查三个配置定义（每轮 3 条结果），
   * 第 3 轮起总数 > 5，pin 窗口外的旧结果才有得清。
   * 真模型是否照办属 @real 的固有不确定性 —— 红时先看 SSE 的 `tool_start`/`tool_result` 条数，
   * 以及快照 `compaction.reason`（`nothing_to_clear` = 前置不足；`under_threshold` = 占比没到）；
   * 而不确定性本身由 `16-kqc-c.spec.ts` 的 @stub C-13 用确定性桩守着。
   *
   * <p><b>真模型验证</b>：2026-10-23 首跑（run 39977930803）红在"6 轮内从未触发"，根因见上；
   * 本条的断言口径（档位离开 none、台账非空、三面同源）一字未放宽。
   */
  test('@real E2E-C13 压缩谱系真验：台账 / 档位 / 占用 / 三面同源（真模型）', async () => {
    test.setTimeout(900_000)
    const order = ['none', 'L1', 'L2', 'L3']
    const levelOf = (level: unknown): string => (level === undefined || level === null ? 'none' : String(level))
    const levelIndex = (level: unknown): number => {
      const index = order.indexOf(levelOf(level))
      if (index < 0) throw new Error(`未知压缩档位：${String(level)}`)
      return index
    }

    await withModelWindow(REAL_LINEAGE_WINDOW, async () => {
      const sid = newSessionId('C13')
      const rounds: Array<{
        turn: number
        level: string
        percent: number | null
        frame: Frame | undefined
        tools: number
        results: number
        reason: string
      }> = []
      for (let turn = 1; turn <= 6; turn += 1) {
        const round = await chatRound(
          sid,
          `第 ${turn} 轮：分别用 get_config_def 查询 CURRENCY、DOC_TYPE、APPROVE_ROLE 三个配置定义的字段详情`
            + '（三个编码各调用一次工具，不要合并成一次），再用一句话复述上一轮结论。'
        )
        // 真模型轮可能不给 done（HTTP 409 会话门 / 上游 error 终帧 / 空流）—— 红时把
        // 状态码与帧序列带进断言消息，避免"第 N 轮未收敛"这种无法归因的失败（run 40714209280 第 6 轮即缺此证据）。
        expect(
          round.done,
          `第 ${turn} 轮未收敛（HTTP ${round.status}，帧：${round.frames.map((frame) => frame.type).join(',') || '<空>'}`
            + `，错误码：${round.errors.map((frame) => String(frame.code ?? '')).join(',') || '-'}）`
        ).toBeTruthy()
        expect(round.errors).toHaveLength(0)
        const snapshot = await contextSnapshot(sid)
        // 分母必须来自模型行（写接口写进去的那个值），不是任何 env 回退
        expect(snapshot.limitTokens, `第 ${turn} 轮分母必须是模型行`).toBe(REAL_LINEAGE_WINDOW)
        const telemetry = (round.done?.context ?? {}) as { usedTokens?: number; percent?: number }
        const used = Number(telemetry.usedTokens ?? snapshot.usedTokens)
        const percent = telemetry.percent === undefined ? snapshot.percent : Number(telemetry.percent)
        if (percent !== null && Number.isFinite(percent)) {
          // 触发与展示同分母（L-89）：帧里的百分比就是 used / 模型行窗口
          expect(Math.abs(percent - (used * 100) / REAL_LINEAGE_WINDOW), `第 ${turn} 轮展示百分比与分母不同源`)
            .toBeLessThanOrEqual(1)
        }
        rounds.push({
          turn,
          level: levelOf(snapshot.compaction?.level),
          percent,
          frame: round.frames.find((frame) => frame.type === 'context'),
          tools: round.frames.filter((frame) => frame.type === 'tool_start').length,
          results: round.frames.filter((frame) => frame.type === 'tool_result').length,
          reason: String(snapshot.compaction?.reason ?? '-')
        })
      }

      const levels = rounds.map((row) => row.level)
      // eslint-disable-next-line no-console
      console.log(
        `[C-13] 窗口=${REAL_LINEAGE_WINDOW} 档位=${levels.join(' → ')} ` +
          `占用=${rounds.map((row) => (row.percent === null ? '-' : `${row.percent.toFixed(1)}%`)).join(' → ')} ` +
          `工具调用=${rounds.map((row) => row.tools).join('/')} ` +
          `工具结果=${rounds.map((row) => row.results).join('/')} ` +
          `跳过原因=${rounds.map((row) => row.reason).join('/')}`
      )

      // ② 档位谱系：单调不减、每次至多一格，且真的离开 none（"全 none 也单调"不再能过）
      for (let index = 1; index < levels.length; index += 1) {
        const step = levelIndex(levels[index]) - levelIndex(levels[index - 1])
        expect(step, `第 ${rounds[index].turn} 轮档位跳变：${levels.join(' → ')}`).toBeLessThanOrEqual(1)
        expect(step, `第 ${rounds[index].turn} 轮档位回退：${levels.join(' → ')}`).toBeGreaterThanOrEqual(0)
      }
      expect(
        levelIndex(levels[levels.length - 1]),
        `6 轮内压缩从未触发（档位恒 ${levels.join(' → ')}）；工具调用 ${rounds.map((row) => row.tools).join('/')}、`
          + `工具结果 ${rounds.map((row) => row.results).join('/')}、占用 `
          + `${rounds.map((row) => (row.percent === null ? '-' : `${row.percent.toFixed(1)}%`)).join(' → ')}、`
          + `快照跳过原因 ${rounds.map((row) => row.reason).join('/')}。`
          + '判据：L1 只清 `keep-tool-pairs`（默认 5）之外的旧工具结果 ⇒ 历史里工具结果总数 ≤ 5 时'
          + '（reason=nothing_to_clear）结构上开不了压；占比没到 L1 则是 under_threshold'
      ).toBeGreaterThanOrEqual(levelIndex('L1'))

      // ① 台账：条数 > 0、首条 L1 起步、逐条只升一格、每条都真的省了 token
      const ledger = await contextLedger(sid)
      const changed = ledger
        .filter((event) => levelOf(event.level) !== 'none')
        .sort((left, right) => Number(left.atMs ?? 0) - Number(right.atMs ?? 0))
      expect(changed.length, '压缩台账必须非空（否则"压过"没有证据）').toBeGreaterThan(0)
      expect(levelOf(changed[0].level), '谱系必须从 L1 起步').toBe('L1')
      for (let index = 1; index < changed.length; index += 1) {
        const step = levelIndex(changed[index].level) - levelIndex(changed[index - 1].level)
        const trail = changed.map((event) => levelOf(event.level)).join(' → ')
        expect(step, `台账档位跳变：${trail}`).toBeLessThanOrEqual(1)
        expect(step, `台账档位回退：${trail}`).toBeGreaterThanOrEqual(0)
      }
      for (const event of changed) {
        const before = Number(event.before ?? 0)
        const after = Number(event.after ?? 0)
        expect(after, `压缩未降低占用：level=${levelOf(event.level)} before=${before} after=${after}`).toBeLessThan(before)
      }
      // ③ compactedTurns / lostTokens 口径：L1 只清结果不折叠轮 ⇒ 0/0；L2 起必须如实记账
      expect(Number(changed[0].turns ?? -1), 'L1 不得折叠轮次').toBe(0)
      expect(Number(changed[0].lostTokens ?? -1), 'L1 不丢轮次 ⇒ lostTokens=0（省下的量另由 savedTokens 表达）').toBe(0)
      for (const event of changed.filter((item) => levelIndex(item.level) >= levelIndex('L2'))) {
        expect(Number(event.turns ?? 0), `L2+ 的 compactedTurns 必须 > 0：${JSON.stringify(event).slice(0, 200)}`)
          .toBeGreaterThan(0)
        expect(Number(event.lostTokens ?? 0), `L2+ 的 lostTokens 必须 > 0：${JSON.stringify(event).slice(0, 200)}`)
          .toBeGreaterThan(0)
      }
      // ④ 占用真下降：末条的 after 低于首条的 before
      expect(Number(changed[changed.length - 1].after ?? 0)).toBeLessThan(Number(changed[0].before ?? 0))

      // ⑤ 三面同源之一：快照 ≡ 台账末条
      const lastEvent = changed[changed.length - 1]
      const finalSnapshot = await contextSnapshot(sid)
      expect(levelOf(finalSnapshot.compaction?.level)).toBe(levelOf(lastEvent.level))
      expect(Number(finalSnapshot.compaction?.compactedTurns ?? 0)).toBe(Number(lastEvent.turns ?? 0))
      expect(Number(finalSnapshot.compaction?.lostTokens ?? 0)).toBe(Number(lastEvent.lostTokens ?? 0))

      // ⑤ 三面同源之二：SSE `context` 帧 ≡ 该轮台账（压缩真的发生时才发帧）
      const framed = rounds.filter((row) => row.frame !== undefined)
      expect(framed.length, '压缩轮必须带 context 帧（即时可见面）').toBeGreaterThan(0)
      for (const row of framed) {
        const frame = row.frame as Frame
        expect(levelOf(frame.level)).toBe(row.level)
        expect(Number(frame.before ?? 0)).toBeGreaterThan(Number(frame.after ?? 0))
        expect(Number(frame.savedTokens ?? 0)).toBe(Number(frame.before ?? 0) - Number(frame.after ?? 0))
        const matched = changed.find((event) => levelOf(event.level) === levelOf(frame.level)
          && Number(event.after ?? -1) === Number(frame.after ?? -2))
        expect(matched, `context 帧在台账里找不到同源条目：${JSON.stringify(frame).slice(0, 200)}`).toBeDefined()
      }

      // eslint-disable-next-line no-console
      console.log(
        `[C-13] 台账=${changed.length} 条（${changed.map((event) => `${levelOf(event.level)}:${event.before}→${event.after}`).join(' / ')}）` +
          ` 末条 turns=${String(lastEvent.turns)} lostTokens=${String(lastEvent.lostTokens)}`
      )
    })
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
