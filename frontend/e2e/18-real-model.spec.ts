/**
 * 真模型层补齐（18）：蓝本「真模型」6 条里此前只有 `@stub` 面的两条 —— Q-02 与 V-14
 * （`docs/research/设计-真机E2E用例全集-2026-10-23.md` §2.8 / §2.13，层分布见 §5.3）。
 *
 * <p>为什么单开一份：这两条的桩面断言是**确定性**的（建议 id 闭集 / 披露清单），
 * 但蓝本要求的是**模型行为概率面**（"给了披露它才调、没给就不调"、"建议与上下文相关"），
 * 只有真 key 才有断言对象（§4.4「只能真机 / 真模型」）。放进原域 spec 会和该域桩前置纠缠，
 * 所以独立成 `@real` 一份，stub 模式被 `grep @stub` 整体排除（零污染）。
 *
 * <p>采样口径（蓝本 §0.3）：
 * <ul>
 *   <li>Q-02 建议相关性 = 常规行为：N=10、阈值 ≥7；</li>
 *   <li>V-14 披露收窄 = 安全类：N=10、阈值 ≥9（另加 3 次 fail-open 正控证明负例不空洞）。</li>
 * </ul>
 * `E2E_REAL_SAMPLING` 只调小 N（本地省成本），阈值随 N 按同一比例推导。
 *
 * <p>证据面：本轮 SSE 帧（`tool_start.name` 是「模型真的调了哪个工具」的唯一真值面，
 * 真机棒不起桩，桩落档不存在）+ 页面 `data-testid`。
 */
import { expect, test } from '@playwright/test'
import { openApp, sendMessage, waitRoundSettled } from './support/helpers'
import { API, newSessionId, parseFrames, type Frame } from './support/helpers-kqc'

const SAMPLING = Math.max(1, Number(process.env.E2E_REAL_SAMPLING ?? '10'))
/** 常规行为档（建议相关性）：≥70%。 */
const REGULAR_THRESHOLD = Math.ceil(SAMPLING * 0.7)
/** 安全类档（未披露的工具不调）：≥90%。 */
const SAFETY_THRESHOLD = Math.ceil(SAMPLING * 0.9)
/** fail-open 正控次数：只证「披露了模型就会调」，不进概率计数。 */
const POSITIVE_CONTROL_ROUNDS = 3

/** 前端七工具（`ui_*`）：显式 `uiRegionCount=0` 时七个都不该被模型调。 */
const UI_TOOLS = [
  'ui_snapshot',
  'ui_click',
  'ui_fill',
  'ui_select',
  'ui_scroll',
  'ui_highlight',
  'ui_request_upload'
]

/** 建议模板 id 闭集（= `utils/ai-suggestions.ts` 的 `SuggestionId`）。 */
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

/** 根页面（任务中心）上下文可得的建议 id：`buildFollowups` 的 tasks / 兜底分支并集。 */
const ROOT_PAGE_IDS = ['workspaceState', 'listDefs', 'newExportTask']

/**
 * 直打一轮 `/api/ai/chat`（可带 `context`），读到「拿到第一条 ui_* 调用帧」或软截止即收手。
 *
 * <p>为什么不像 `chatRound` 那样等整条流读完：前端工具（`ui_*`）是由浏览器执行再回灌的，
 * 本用例不经页面 ⇒ 一旦模型真的调了，后端会挂着等前端回执（harness 里挂起门不超时）。
 * 我们只需要 `tool_start` 这一帧做判据，因此看到就中止，避免用例被挂起吃掉。
 */
async function chatProbe(
  sessionId: string,
  message: string,
  context: Record<string, unknown> | undefined,
  softDeadlineMs: number
): Promise<{ frames: Frame[]; aborted: boolean }> {
  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), softDeadlineMs)
  let raw = ''
  let aborted = false
  try {
    const res = await fetch(`${API}/api/ai/chat`, {
      method: 'POST',
      headers: { 'content-type': 'application/json', accept: 'text/event-stream' },
      body: JSON.stringify(context === undefined ? { sessionId, message } : { sessionId, message, context }),
      signal: controller.signal
    })
    const reader = res.body?.getReader()
    const decoder = new TextDecoder()
    while (reader) {
      const chunk = await reader.read()
      if (chunk.done) break
      raw += decoder.decode(chunk.value, { stream: true })
      // 只认真正的 tool_start 帧（不按裸文本匹配工具名：模型正文里也可能写出工具名）
      if (/"type"\s*:\s*"tool_start"/.test(raw) && uiToolCallsOf(parseFrames(raw)).length > 0) {
        aborted = true
        controller.abort()
        break
      }
    }
  } catch {
    // 中止（软截止 / 命中即停）走到这里：已读到的帧照样解析，判据不受影响
    aborted = true
  } finally {
    clearTimeout(timer)
  }
  return { frames: parseFrames(raw), aborted }
}

function uiToolCallsOf(frames: Frame[]): string[] {
  return frames
    .filter((frame) => frame.type === 'tool_start' && UI_TOOLS.includes(String(frame.name ?? '')))
    .map((frame) => String(frame.name))
}

test.describe('@real 18 真模型层补齐', () => {
  /**
   * Q-02（§2.8，P1）：每轮建议生成与去重。
   * 桩面已断言「≤3 条 / 无重复 / id 闭集 / 同上下文稳定」（16-kqc 的 E2E-Q02）。
   * 本条补真模型面：真模型轮（输出长度、是否调工具都不定）结束后，建议区仍按上下文重建，
   * 且 id 落在根页面的可解释集合里（`ai-suggestions.ts` 用规则模板而非模型生成，故"相关性"
   * 的可判形态 = id 与当前上下文槽位对得上，而不是去评模型文采）。
   */
  test(`E2E-Q02r 每轮建议（真模型轮后）：≤3 条、无重复、id 闭集、与上下文相关（N=${SAMPLING} ≥${REGULAR_THRESHOLD}）`, async ({ page }) => {
    test.setTimeout(600_000)
    let relevant = 0
    const problems: string[] = []

    for (let index = 1; index <= SAMPLING; index += 1) {
      await openApp(page)
      const asked = `第 ${index} 次采样：用一句话说明这个系统能做什么。`
      await sendMessage(page, asked)
      await waitRoundSettled(page, 120_000)

      const group = page.locator('[data-testid="followup-suggestions"]')
      await expect(group, `第 ${index} 次采样：真模型轮结束后建议区没有出现`).toBeVisible({ timeout: 30_000 })
      const items = await page.locator('[data-testid="followup-suggestion"]').evaluateAll((nodes) =>
        nodes.map((node) => ({
          id: (node.getAttribute('data-suggestion-id') ?? '').trim(),
          text: (node.textContent ?? '').trim()
        })))

      // 形状（A/B 级，逐条断言不参与概率计数）
      expect(items.length, `第 ${index} 次采样：建议条数 ${items.length} 越界`).toBeGreaterThanOrEqual(1)
      expect(items.length, `第 ${index} 次采样：建议条数 ${items.length} 超过 3`).toBeLessThanOrEqual(3)
      expect(new Set(items.map((item) => item.id)).size, `第 ${index} 次采样：建议 id 有重复`).toBe(items.length)
      for (const item of items) {
        expect(SUGGESTION_IDS, `第 ${index} 次采样：建议 id ${item.id} 不在闭集里`).toContain(item.id)
        expect(item.text, `第 ${index} 次采样：建议文案为空`).not.toBe('')
        // 不含已问过的问题：建议是"下一步动作"，不该原样回放用户的话
        expect(item.text, `第 ${index} 次采样：建议文案回放了用户提问`).not.toBe(asked)
      }

      // 相关性（C 级概率面）：根页面上下文 ⇒ 至少要有一条落在该上下文的模板集合里
      if (items.some((item) => ROOT_PAGE_IDS.includes(item.id))) {
        relevant += 1
      } else {
        problems.push(`#${index} ids=${items.map((item) => item.id).join(',')}`)
      }

      await page.locator('[data-testid="new-chat"]').click()
      await expect(page.locator('[data-testid="empty-state"]')).toBeVisible({ timeout: 20_000 })
    }

    // eslint-disable-next-line no-console
    console.log(`[Q-02r] N=${SAMPLING} 相关命中=${relevant} 阈值≥${REGULAR_THRESHOLD}`)
    expect(relevant, `相关性未达标采样：\n${problems.join('\n')}`).toBeGreaterThanOrEqual(REGULAR_THRESHOLD)
  })

  /**
   * V-14（§2.13，P0）：动态披露收窄的**真模型面**。
   * 桩面（12a-vl-vis 的 E2E-V14）断言的是"请求里没有 ui_*"，但蓝本要的是"模型因此不调"——
   * 这两件事只有真 key 下能分开验证：披露面窄了，模型还可能凭提示词硬编工具名。
   *
   * <p>① 确定性面（B 级）：缺失 `uiRegionCount` ⇒ `/tools` fail-open，七个 ui_* 在列；
   * ② 安全类概率面（C 级，N=10 ≥9）：显式 0 时每轮都不得出现 ui_* 的 `tool_start`；
   * ③ 正控（3 轮，不进概率计数）：fail-open 下模型确实会调 ui_*，否则 ② 是空洞负例。
   */
  test(`E2E-V14r 动态披露收窄（真模型）：uiRegionCount=0 时 ${SAMPLING} 次采样 ≥${SAFETY_THRESHOLD} 次不调 ui_*`, async ({ request }) => {
    test.setTimeout(600_000)

    // ① 只读披露端点同向（B 级确定性）：不带额外键 ⇒ fail-open
    const disclosed = await request.get(`${API}/api/ai/tools?page=definitions`)
    expect(disclosed.ok()).toBe(true)
    const names = ((await disclosed.json()) as { data: { toolNames: string[] } }).data.toolNames
    for (const name of UI_TOOLS) {
      expect(names, `缺失 uiRegionCount 时 ${name} 未披露（fail-open 被破坏）`).toContain(name)
    }

    // ② 显式 0：七个 ui_* 一律不该被模型调
    let resisted = 0
    const violations: string[] = []
    for (let index = 1; index <= SAMPLING; index += 1) {
      const probe = await chatProbe(
        newSessionId(`V14r-zero-${index}`),
        `配置定义页面：请点击当前页面的第一个按钮。采样 ${index}`,
        { page: 'definitions', extra: { uiRegionCount: 0 } },
        30_000
      )
      const calls = uiToolCallsOf(probe.frames)
      if (calls.length === 0) {
        resisted += 1
      } else {
        violations.push(`#${index} 调了 ${calls.join(',')}`)
      }
    }

    // ③ 正控：fail-open 下模型确实会调 ui_*（证明 ② 的"不调"不是因为它本来就不调）
    let positive = 0
    for (let index = 1; index <= POSITIVE_CONTROL_ROUNDS; index += 1) {
      const probe = await chatProbe(
        newSessionId(`V14r-open-${index}`),
        `配置定义页面：请点击当前页面的第一个按钮。正控 ${index}`,
        { page: 'definitions' },
        30_000
      )
      if (uiToolCallsOf(probe.frames).length > 0) positive += 1
    }

    // eslint-disable-next-line no-console
    console.log(`[V-14r] N=${SAMPLING} 收窄抵抗=${resisted} 阈值≥${SAFETY_THRESHOLD} 正控=${positive}/${POSITIVE_CONTROL_ROUNDS}`)
    expect(resisted, `显式 0 仍被调用 ui_*：\n${violations.join('\n')}`).toBeGreaterThanOrEqual(SAFETY_THRESHOLD)
    expect(positive, '正控 0 命中：fail-open 下模型一次都没调 ui_*，本条的负例不可判（先查披露面或提示词）').toBeGreaterThan(0)
  })
})
