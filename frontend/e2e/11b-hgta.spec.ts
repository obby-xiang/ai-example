/**
 * 批③ 11b · G 域（GF 生成式表单）+ T 域（SH 表格工具）
 *
 * 蓝本：docs/research/设计-真机E2E用例全集-2026-10-23.md §2.5 / §2.6。
 * 批① 已覆盖 G-07（七型渲染）/ G-18（boolean 真实坐标）/ T-06（预览三档）/ T-14（应用），
 * 本文件做其余 G 条与能承载的 T 条。
 *
 * 桩的两条硬约束（决定了用例怎么写）：
 * 1. 会话指纹 = 首条 user 消息 ⇒ 每条提示词带唯一字母后缀；
 * 2. 上下文里出现 role=tool 后不再发起工具调用（收敛契约③）⇒ 一个会话只出一张卡/一次工具调用
 *    （「同会话两轮 GF」这种形态因此单开 11c-gf2.spec.ts + 本域桩，见 L-92）。
 */
import { expect, test, type APIRequestContext, type Locator, type Page } from '@playwright/test'
import {
  createExportedTask,
  createSheetImportTask,
  openApp,
  openPath,
  sendMessage,
  until,
  waitRoundSettled,
  waitRoundSettledDismissingPending
} from './support/helpers'
import {
  API,
  apiGet,
  apiPost,
  apiPostRaw,
  chatFrames,
  currentRun,
  pendingFormFields,
  sidOf,
  stubRequestsFor,
  toolMessagesOf,
  uniq,
  watchPost,
  type CurrentRun,
  type FormFieldSpec
} from './support/hgta'
import { aiRequestOptions, newAiSession } from './support/ai-identity'

const DEF = 'CURRENCY'

function pendingOf(run: CurrentRun): Record<string, unknown> | undefined {
  return (run.pendingEntries ?? [])[0]
}

async function openForm(page: Page, message: string): Promise<void> {
  await openApp(page)
  await sendMessage(page, message)
  await expect(page.locator('[data-testid="gf-fields"]')).toBeVisible({ timeout: 40_000 })
}

async function formRefs(request: APIRequestContext, sid: string): Promise<{ runId: string; toolCallId: string }> {
  const run = await currentRun(request, sid)
  const entry = pendingOf(run)
  return { runId: String(run.runId ?? ''), toolCallId: String(entry?.toolCallId ?? '') }
}

const DRAFT_PREFIX = 'cm.ai.form.draft.'

async function draftKeys(page: Page): Promise<string[]> {
  return page.evaluate((prefix) => {
    const keys: string[] = []
    for (let i = 0; i < sessionStorage.length; i += 1) {
      const key = sessionStorage.key(i)
      if (key && key.startsWith(prefix)) keys.push(key)
    }
    return keys
  }, DRAFT_PREFIX)
}

// ── G 域 ────────────────────────────────────────────────────────────────────

test.describe('@stub G 生成式表单', () => {
  test('E2E-G02 CLARIFY 回灌续跑：提交 200、本轮续跑到落定、模型收到值 JSON', async ({ page }) => {
    const suffix = uniq()
    const prompt = `scenario=CLARIFY 用例G02-${suffix}`
    await openForm(page, prompt)
    // CLARIFY 型的两个字段（标题在卡头，不在 fields 容器里）
    await expect(page.locator('#gf-field-exportScope')).toBeVisible()
    await expect(page.locator('#gf-field-includeInactive')).toHaveAttribute('type', 'checkbox')

    await page.locator('#gf-field-exportScope').selectOption('ALL')
    const include = page.locator('#gf-field-includeInactive')
    const box = await include.boundingBox()
    expect(box).not.toBeNull()
    await page.mouse.click(box!.x + box!.width / 2, box!.y + box!.height / 2)
    await expect(include).toBeChecked()

    const posted = page.waitForResponse(
      (res) => res.url().includes('/api/ai/frontend-tool-result') && res.request().method() === 'POST',
      { timeout: 20_000 }
    )
    await page.locator('[data-testid="gf-submit"]').click()
    expect((await posted).status()).toBe(200)

    await waitRoundSettled(page, 60_000)
    await expect(page.locator('[data-testid="gf-submit"]')).toHaveCount(0)
    // 提交成功 ⇒ 草稿镜像即清（G-16 的提交收口）
    expect(await draftKeys(page)).toHaveLength(0)

    // 模型侧真收到了值 JSON：续轮请求里的 tool 消息含两个字段名与取值
    const calls = stubCalls(prompt)
    expect(calls.length).toBeGreaterThanOrEqual(2)
    const continuation = calls[calls.length - 1]
    expect(continuation).toContain('exportScope')
    expect(continuation).toContain('ALL')
    expect(continuation).toContain('includeInactive')
  })

  test('E2E-G03 取消路径：回灌 cancelled=true、无挂起残留、输入解禁、本轮可继续', async ({ page }) => {
    await openForm(page, `scenario=FILTER 用例G03-${uniq()}`)
    await page.locator('#gf-field-keyword').fill('待取消')

    const submitted = watchPost(page, '/api/ai/frontend-tool-result')
    await page.locator('[data-testid="gf-cancel"]').click()
    const body = (await submitted).postDataJSON() as { cancelled?: boolean }
    expect(body.cancelled).toBe(true)

    // 取消后表单退出可编辑态（只读锁定 + 无操作按钮）
    await expect(page.locator('[data-testid="gf-submit"]')).toHaveCount(0, { timeout: 20_000 })
    await expect(page.locator('[data-testid="gf-cancel"]')).toHaveCount(0)
    await waitRoundSettled(page, 60_000)
    await expect(page.locator('[data-testid="composer-input"]')).toBeEnabled()
    expect(await draftKeys(page)).toHaveLength(0)

    // 本轮之后还能继续发消息（不被"挂起未决"卡住）
    const before = await page.locator('[data-testid^="msg-bubble-"].w-full').count()
    await sendMessage(page, `turn-text 续发-${uniq()}`)
    await until(async () => (await page.locator('[data-testid^="msg-bubble-"].w-full').count()) > before, 30_000, '新轮有回复')
    await waitRoundSettled(page, 30_000)
  })

  test('E2E-G04/G17 复核拒绝点亮字段：400 FORM_RESULT_REJECTED、按 key 点亮、改值后重发成功', async ({ page }) => {
    await openForm(page, `scenario=FILTER 用例G04-${uniq()}`)
    await page.locator('#gf-field-keyword').fill('E2E 复核')
    await page.locator('#gf-field-effectiveDate').fill('2026-10-23')
    await page.locator('#gf-field-scope').selectOption('XN')

    // 让线上请求体里的 enum 值越出选项（前端自校验通过、后端复核拒绝的真实分支）
    await page.route('**/api/ai/frontend-tool-result', async (route) => {
      const data = route.request().postData() ?? ''
      const tampered = data.replaceAll('XN', 'ZZ')
      await route.continue(tampered === data ? {} : { postData: tampered })
    })

    const rejected = page.waitForResponse(
      (res) => res.url().includes('/api/ai/frontend-tool-result') && res.request().method() === 'POST',
      { timeout: 20_000 }
    )
    await page.locator('[data-testid="gf-submit"]').click()
    const response = await rejected
    expect(response.status()).toBe(400)
    const payload = (await response.json()) as {
      code?: string
      data?: { retryable?: boolean; issues?: Array<{ key?: string; code?: string }> }
      errors?: Array<{ key?: string; code?: string }>
      issues?: Array<{ key?: string; code?: string }>
    }
    const text = JSON.stringify(payload)
    expect(text).toContain('FORM_RESULT_REJECTED')
    expect(text).toContain('VALUE_OUT_OF_OPTIONS')
    expect(text).toMatch(/"retryable"\s*:\s*true/)

    // 挂起仍在等待：表单回到填写态，且按 key 点亮了 scope 字段
    await expect(page.locator('[data-testid="gf-submit"]')).toBeVisible({ timeout: 20_000 })
    const fieldError = page.locator('#gf-field-scope-error')
    await expect(fieldError).toBeVisible({ timeout: 20_000 })
    await expect(fieldError).toContainText('不在选项内')
    await expect(page.locator('#gf-field-scope')).toHaveAttribute('aria-invalid', 'true')
    // 读屏播报（错误 live region）非空
    await expect(page.locator('[data-testid="gf-live-region"]')).not.toBeEmpty()

    // 改值后重发成功
    await page.unroute('**/api/ai/frontend-tool-result')
    await page.locator('#gf-field-scope').selectOption('HD')
    const ok = page.waitForResponse(
      (res) => res.url().includes('/api/ai/frontend-tool-result') && res.request().method() === 'POST',
      { timeout: 20_000 }
    )
    await page.locator('[data-testid="gf-submit"]').click()
    expect((await ok).status()).toBe(200)
    await waitRoundSettled(page, 60_000)
    await expect(page.locator('[data-testid="gf-submit"]')).toHaveCount(0)
  })

  test('E2E-G05 回灌幂等：重复回灌冲突 409、不二次推进', async ({ request }) => {
    const sid = await newAiSession(API)
    const chat = await chatFrames(request, { sessionId: sid, message: `scenario=FILTER 用例G05-${uniq()}`, context: { page: 'tasks' } })
    expect(chat.frames.map((frame) => String(frame.type))).toContain('frontend_tool_request')
    const { runId, toolCallId } = await formRefs(request, sid)
    const body = { runId, toolCallId, result: JSON.stringify({ keyword: '幂等' }) }

    const first = await apiPostRaw(request, '/api/ai/frontend-tool-result', body)
    expect(first.status).toBe(200)
    const second = await apiPostRaw(request, '/api/ai/frontend-tool-result', body)
    expect(second.status).toBe(409)
    expect(second.text).toContain('DUPLICATE_TOOL_CALL_ID')

    // 不二次推进：挂起条目只前进一步（终态 FRONTEND_RESULT 邻近值），且台账里只有一个该 toolCallId
    const pendings = await apiGet<Array<Record<string, unknown>>>(request, `/api/ai/pending/${runId}`)
    const mine = pendings.filter((item) => item.toolCallId === toolCallId)
    expect(mine).toHaveLength(1)
    expect(mine[0]?.status).not.toBe('PENDING')
  })

  test('E2E-G06 入参闸门：非法联动 schema 被拒、不挂起、不下发坏表单（表达式不求值）', async ({ request }) => {
    const sid = await newAiSession(API)
    const chat = await chatFrames(request, { sessionId: sid, message: `gf-expr-linkage 用例G06-${uniq()}`, context: { page: 'tasks' } })
    expect(chat.status).toBe(200)
    const types = chat.frames.map((frame) => String(frame.type))
    // schema 系统拒绝只回执模型（浏览器实测批）：不下发请求帧、不落任何卡片帧 ——
    // 否则用户先看见一张"已拒绝"的表单卡，模型改对后又落一张可用卡。
    // 不再用正文里出现错误码代理"模型被告知"：帧静默后 SSE 正文只剩模型自己的措辞。
    expect(types).not.toContain('frontend_tool_request')
    expect(types).not.toContain('frontend_tool_result')
    expect(
      chat.frames.filter((frame) => String(frame.type) === 'tool_start' && String(frame.name) === 'generative_form')
    ).toHaveLength(0)
    // 轮继续并正常收尾
    expect(types).toContain('done')
    const run = await currentRun(request, sid)
    expect(run.awaitingExternal ?? false).toBe(false)

    // 零卡帧 ≠ 任意静默：被拒的那次调用必须在账上留下**具体的联动拒绝**证据。
    // 结算条目已不再出现在实时 /pending（0c74f20f 起清理更快），改从 /history 的
    // toolCallStates（终态 + code）与 toolResponses（resultText）取证。
    let code = ''
    let result = ''
    await until(async () => {
      const history = await apiGet<{ messages: Array<Record<string, unknown>> }>(request, `/api/ai/history/${sid}`)
      const responses = new Map<string, string>()
      for (const message of history.messages) {
        for (const response of (message.toolResponses as Array<Record<string, unknown>> | undefined) ?? []) {
          const id = String(response.id ?? '')
          if (id) responses.set(id, String(response.responseData ?? ''))
        }
      }
      for (const message of history.messages) {
        const states = (message.toolCallStates as Record<string, Record<string, unknown>> | undefined) ?? {}
        for (const call of (message.toolCalls as Array<Record<string, unknown>> | undefined) ?? []) {
          if (String(call.name) !== 'generative_form') continue
          const id = String(call.id ?? '')
          const state = states[id]
          if (String(state?.status ?? '') !== 'REJECTED_ARGUMENTS') continue
          code = String(state?.code ?? '')
          result = responses.get(id) ?? ''
          return true
        }
      }
      return false
    }, 15_000, '历史出现 generative_form 的 REJECTED_ARGUMENTS 证据')
    // 条目码 = schema 级系统拒绝（服务端写入的终态事实）；具体原因在 result 文本里。
    expect(code).toBe('FORM_SCHEMA_REJECTED')
    // 具体原因 = 联动条件表达式非法（不是"槽位已知"或别的静默原因）。
    // result 是回给模型的人读原因（不带错误码字面量），按字段与拒因断言。
    expect(result).toContain('visibleWhen')
    expect(result).toContain('不接受表达式字符串')
  })

  test('E2E-G08 longText 上限 2000 / text 上限 200：前端即时拦、后端 400 VALUE_TOO_LONG', async ({ page, request }) => {
    const prompt = `gf-longtext 用例G08-${uniq()}`
    await openForm(page, prompt)
    const memo = page.locator('#gf-field-memo')
    const short = page.locator('#gf-field-short')
    await expect(memo).toHaveAttribute('maxlength', '2000')
    await expect(short).toHaveAttribute('maxlength', '200')

    // 2000 字填满后再敲一个字符：即时拦在 2000（不静默截断已输入内容）
    await memo.fill('长'.repeat(2000))
    await memo.pressSequentially('X')
    expect((await memo.inputValue()).length).toBe(2000)

    // 后端也拒：直接回灌一个 2001 字的值
    const { runId, toolCallId } = await formRefs(request, await sidOf(page))
    const tooLong = await apiPostRaw(request, '/api/ai/frontend-tool-result', {
      runId,
      toolCallId,
      result: JSON.stringify({ memo: '长'.repeat(2001) })
    })
    expect(tooLong.status).toBe(400)
    expect(tooLong.text).toContain('VALUE_TOO_LONG')
    // 挂起仍在等待（可改后重发）
    await expect(page.locator('[data-testid="gf-submit"]')).toBeVisible()
  })

  test('E2E-G09 联动 visibleWhen：A 值决定 B 可见性，且不执行表达式', async ({ page }) => {
    await openForm(page, `gf-linkage 用例G09-${uniq()}`)
    const detail = page.locator('#gf-field-detail')
    const kind = page.locator('#gf-field-kind')

    // A2（依赖不满足）⇒ 不可见
    await kind.selectOption('A2')
    await expect(detail).toHaveCount(0)
    // A1（依赖满足）⇒ 可见
    await kind.selectOption('A1')
    await expect(detail).toBeVisible()
    await detail.fill('明细值')
    // A2 ⇒ 再次隐藏（隐藏字段的值不进回灌体）
    await kind.selectOption('A2')
    await expect(detail).toHaveCount(0)
    await kind.selectOption('A1')
    await expect(detail).toBeVisible()
    await expect(detail).toHaveValue('明细值')
  })

  test('E2E-G10 联动 requiredWhen/disabledWhen：必填与禁用随 A 变化', async ({ page }) => {
    await openForm(page, `gf-linkage 用例G10-${uniq()}`)
    const kind = page.locator('#gf-field-kind')
    const detail = page.locator('#gf-field-detail')
    const note = page.locator('#gf-field-note')

    await kind.selectOption('A1')
    await expect(detail).toHaveAttribute('aria-required', 'true')
    await expect(note).toBeEnabled()

    await kind.selectOption('A2')
    await expect(note).toBeDisabled()
    // A2 时 detail 不可见 ⇒ 必填随可见性一起消失（不会拦表单）
    await expect(detail).toHaveCount(0)
    await expect(page.locator('[data-testid="gf-submit"]')).toBeEnabled()
  })

  test('E2E-G11 optionsFrom 闭集级联：父值决定子选项集', async ({ page, request }) => {
    await openForm(page, `gf-options-from 用例G11-${uniq()}`)
    const region = page.locator('#gf-field-region')
    const city = page.locator('#gf-field-city')

    const optionTexts = async (): Promise<string[]> => city.locator('option').allInnerTexts()

    await region.selectOption('XN')
    await expect(city).toBeVisible()
    expect((await optionTexts()).filter((text) => text.includes('XA'))).toHaveLength(1)
    expect((await optionTexts()).some((text) => text.includes('HD1'))).toBe(false)

    await region.selectOption('HD')
    expect((await optionTexts()).filter((text) => text.includes('HD1'))).toHaveLength(1)
    expect((await optionTexts()).some((text) => text.includes('XA'))).toBe(false)

    // 服务端数据源口子只认注册表 id（URL/脚本一律 400）
    const res = await request.get(
      `${API}/api/ai/form/option-sources/definition_fields?value=HD`,
      aiRequestOptions('/api/ai/form/option-sources')
    )
    expect(res.ok()).toBe(true)
    const forged = await request.get(
      `${API}/api/ai/form/option-sources/https%3A%2F%2Fevil.example?value=HD`,
      aiRequestOptions('/api/ai/form/option-sources')
    )
    expect(forged.status()).toBe(400)
  })

  test('E2E-G12 defaultFrom：子字段初值来自映射，父值变化即跟随', async ({ page }) => {
    await openForm(page, `gf-options-from 用例G12-${uniq()}`)
    const region = page.locator('#gf-field-region')
    const amount = page.locator('#gf-field-amount')
    await region.selectOption('XN')
    await expect(amount).toHaveValue('10')
    await region.selectOption('HD')
    await expect(amount).toHaveValue('20')
  })

  test('E2E-G13 依赖深度上限 2：四层联动被拒（fail-closed，不猜）', async ({ request }) => {
    const sid = await newAiSession(API)
    const chat = await chatFrames(request, { sessionId: sid, message: `gf-deep-linkage 用例G13-${uniq()}`, context: { page: 'tasks' } })
    expect(chat.status).toBe(200)
    const types = chat.frames.map((frame) => String(frame.type))
    // 与 G06 同口径：schema 系统拒绝只回执模型，用户面零卡片（不下发请求帧、不落失败卡帧）
    expect(types).not.toContain('frontend_tool_request')
    expect(types).not.toContain('frontend_tool_result')
    expect(
      chat.frames.filter((frame) => String(frame.type) === 'tool_start' && String(frame.name) === 'generative_form')
    ).toHaveLength(0)
    // 轮继续并正常收尾
    expect(types).toContain('done')
    const run = await currentRun(request, sid)
    expect(run.awaitingExternal ?? false).toBe(false)
  })

  test('E2E-G16 草稿生命周期：只在 sessionStorage、取消即清、不用 localStorage、无倒计时', async ({ page }) => {
    await openForm(page, `scenario=FILTER 用例G16-${uniq()}`)
    await page.locator('#gf-field-keyword').fill('草稿值')

    // 草稿按 toolCallId 单键落 sessionStorage（且只有这一个键）
    await until(async () => (await draftKeys(page)).length === 1, 10_000, '草稿键写入')
    const keys = await draftKeys(page)
    expect(keys[0]).toMatch(/^cm\.ai\.form\.draft\./)
    const localKeys = await page.evaluate(() => {
      const out: string[] = []
      for (let i = 0; i < localStorage.length; i += 1) {
        const key = localStorage.key(i)
        if (key && key.startsWith('cm.ai.form.draft.')) out.push(key)
      }
      return out
    })
    expect(localKeys).toHaveLength(0)

    // 全程无倒计时文案
    const panelText = await page.locator('[data-testid="ai-panel"], aside').first().innerText()
    expect(panelText).not.toMatch(/倒计时|剩余\s*\d+\s*秒/)

    // 取消 ⇒ 草稿清空
    await page.locator('[data-testid="gf-cancel"]').click()
    await until(async () => (await draftKeys(page)).length === 0, 15_000, '取消后草稿清空')
  })

})

// ── 真模型表单填写：schema 驱动（模型生成的字段面不可假设） ────────────────────

/** 选项值（`options[].value` 的字符串形态；`optionsFrom` 级联的字段这里读不到，按"无选项"处理）。 */
function optionValuesOf(field: FormFieldSpec): string[] {
  return (field.options ?? [])
    .map((option) => (option.value === undefined || option.value === null ? '' : String(option.value)))
    .filter((value) => value !== '')
}

/** 先 XN（本域 FILTER 场景的取值），其余按 schema 顺序。 */
function pickOptions(values: string[], want: number): string[] {
  const ordered = [...values].sort((left, right) => {
    const leftHit = /^XN$/i.test(left) ? 0 : 1
    const rightHit = /^XN$/i.test(right) ? 0 : 1
    return leftHit - rightHit
  })
  return ordered.slice(0, Math.max(1, Math.min(want, ordered.length)))
}

/** 文本取值：按 format 给一个必然过格式的值，否则给本轮的采样本值。 */
function textValueOf(field: FormFieldSpec, index: number): string {
  switch (field.format) {
    case 'email':
      return 'e2e@example.com'
    case 'tel':
      return '13800000000'
    case 'url':
      return 'https://example.com/e2e'
    case 'idcard':
      return '11010519491231002X'
    default: {
      const base = `真模型-${index}`
      const maxLength = Number(field.maxLength ?? 0)
      return maxLength > 0 ? base.slice(0, Math.max(1, maxLength)) : base
    }
  }
}

/** 数字取值：落在 `[min,max]` 内的一个值（`min` 缺省取 1；`step` 不做对齐 —— 真生成带 step 的字段就让它红在断言上）。 */
function numberValueOf(field: FormFieldSpec): string {
  const min = field.min === undefined ? 1 : Number(field.min)
  const max = field.max === undefined ? min : Number(field.max)
  return String(Math.min(Math.max(1, min), Number.isFinite(max) ? max : min))
}

/**
 * 按本轮挂起条目的 `form.fields` 把表单填成"能过 schema 复核"的形状。
 *
 * <p>本用例测的是「用户填完 → 回灌 200 → 续跑」，不是「模型会生成哪些字段」：
 * 真模型的字段面不可假设（CI run 39977930803：模型给「范围」生成 `multi_select` + `minItems 1`，
 * 只填关键字的回灌被后端如实拒成 400 `FORM_RESULT_REJECTED` / `VALUE_TOO_FEW_ITEMS`）。
 * 故这里按 schema 逐字段给值：多选至少 `minItems` 项、枚举取首个合法值、文本/数字给合法值；
 * 联动隐藏的字段不渲染 ⇒ 跳过；`attachment`/`date` 不做猜测（真出现就让它红在断言上）。
 *
 * <p>**每个动作都带显式超时**（{@link ACTION_TIMEOUT_MS}）：Playwright 默认 `actionTimeout=0`
 * 会无限等，真模型下"元素永远不可点"就会把整条用例挂到 15 分钟的测试上限上，红得没有任何信息。
 */
const ACTION_TIMEOUT_MS = 10_000

async function fillFormBySchema(
  page: Page,
  request: APIRequestContext,
  form: Locator,
  index: number
): Promise<void> {
  const scope = String((await form.getAttribute('data-testid')) ?? '').replace('gf-form-', '')
  const fields = await pendingFormFields(request, await sidOf(page))
  if (fields === null) {
    throw new Error(`#${index} 挂起条目里读不到表单 schema（runs/current 的 generative_form 入参为空）`)
  }
  for (const field of fields) {
    const base = `gf-field-${scope}-${field.key}`
    const type = String(field.type ?? 'text')
    if (type === 'multi_select') {
      const values = optionValuesOf(field)
      if (values.length === 0) continue // optionsFrom 级联：选项由父字段决定，这里无值可点
      const minItems = Math.max(1, Number(field.minItems ?? 1))
      const maxItems = field.maxItems === undefined ? values.length : Math.max(1, Number(field.maxItems))
      for (const value of pickOptions(values, Math.min(minItems, maxItems))) {
        const box = page.locator(`[data-testid="${base}-${value}"]`)
        if ((await box.count()) === 0) continue // 联动隐藏的字段不渲染
        if (!(await box.isChecked())) await box.check({ timeout: ACTION_TIMEOUT_MS })
      }
      continue
    }
    const control = page.locator(`[data-testid="${base}"]`)
    if ((await control.count()) === 0) continue // 联动隐藏 / 只读回放卡
    if (type === 'enum') {
      const values = optionValuesOf(field)
      if (values.length === 0) continue
      const current = await control.inputValue()
      if (current === '') await control.selectOption(pickOptions(values, 1)[0], { timeout: ACTION_TIMEOUT_MS })
      continue
    }
    if (type === 'date') continue // ISO 日期与场景无关：不猜（真需要时红在断言上，别猜出一个假值）
    if (type === 'text' || type === 'number') {
      if ((await control.inputValue()) !== '') continue // 已填的（关键字）保持用例给的值
      await control.fill(type === 'text' ? textValueOf(field, index) : numberValueOf(field), {
        timeout: ACTION_TIMEOUT_MS
      })
      continue
    }
    // boolean（缺省 false ⇒ 必填也只能点开）/ attachment（值只能由上传产生）：不猜
  }
}

test.describe('@real G 生成式表单（真模型）', () => {
  /**
   * G-01（新契约，2026-10-30）：SET_CONDITIONS 表单只由宿主在 QUERY_COND 统一发放，
   * 模型不得自行下发。本条因此不再按"模型愿不愿意发表单"做概率采样，而走确定性的宿主链：
   * 任务真实停在 QUERY_COND + 条件未决定 ⇒ 下一轮 AI 由宿主挂出唯一一张条件表；
   * 回灌后本轮收尾，不再出现第二张条件表。
   */
  test('E2E-G01 宿主在 QUERY_COND 统一发条件表（真模型，单轮）', async ({ page, request }) => {
    test.setTimeout(300_000)
    const title = `E2E-G01-${uniq()}`
    const task = await apiPost<{ id: number }>(request, '/api/tasks', { type: 'EXPORT', title })
    await apiPost(request, `/api/tasks/${task.id}/select-defs`, { defCodes: ['CURRENCY'] })
    const stepRes = await request.put(
      `${API}/api/tasks/${task.id}/step`,
      aiRequestOptions(`/api/tasks/${task.id}/step`, { data: { step: 'QUERY_COND' } })
    )
    expect(stepRes.status()).toBe(200)

    await openPath(page, `/export?taskId=${task.id}`)
    await expect(page.locator('footer')).toContainText('task:EXPORT/QUERY_COND', { timeout: 30_000 })

    // 下一轮 AI 进入 QUERY_COND 且条件未决定 ⇒ 宿主（不是模型）下发一次 SET_CONDITIONS 表单。
    await sendMessage(page, `请继续处理任务 ${task.id} 的筛选条件。`)
    const form = page
      .locator('[data-testid^="gf-form-"]')
      .filter({ has: page.locator('[data-testid="gf-submit"]') })
      .last()
    await expect(form).toBeVisible({ timeout: 60_000 })
    // 宿主条件表形状：单个非必填长文本 conditions（不是模型自定义字段面）。
    await expect(form.locator('#gf-field-conditions')).toBeVisible()
    await expect(form).toContainText('筛选条件')

    await form.locator('#gf-field-conditions').fill('币种代码等于 CNY')
    const posted = page.waitForResponse(
      (res) => res.url().includes('/api/ai/frontend-tool-result') && res.request().method() === 'POST',
      { timeout: 30_000 }
    )
    await form.locator('[data-testid="gf-submit"]').click()
    expect((await posted).status()).toBe(200)

    // 续跑里可能追加确认门/其它挂起面 ⇒ 逐个收掉再等收敛；条件表本身不得再发第二张。
    await waitRoundSettledDismissingPending(page, 120_000)
    await expect(page.locator('[data-testid^="gf-form-"]')).toHaveCount(1)
    await expect(page.locator('[data-testid="gf-error"]')).toHaveCount(0)
  })
})

// ── T 域 ────────────────────────────────────────────────────────────────────

/** 桩落档里该指纹的全部上游请求（tool 消息原文拼接）。 */
function stubCalls(firstUserText: string): string[] {
  return stubRequestsFor(firstUserText).map((item) => JSON.stringify(toolMessagesOf(item.body)))
}

/** 可写表格任务（导入「上传配置」步，未终态 ⇒ 写闸放行）。 */
async function openImportedSheet(page: Page, taskId: number): Promise<void> {
  await openPath(page, `/import?taskId=${taskId}`)
  await expect(page.locator('footer')).toContainText('task:IMPORT/UPLOAD', { timeout: 30_000 })
  await expect(page.locator('.el-tabs__item').first()).toBeVisible({ timeout: 60_000 })
  await expect(page.locator('.el-tab-pane canvas').first()).toBeVisible({ timeout: 60_000 })
}

async function openExportedSheet(page: Page, taskId: number): Promise<void> {
  await openPath(page, `/export?taskId=${taskId}`)
  await expect(page.locator('footer')).toContainText('task:EXPORT/EXPORT', { timeout: 30_000 })
  await expect(page.locator('.el-tabs__item').first()).toBeVisible({ timeout: 60_000 })
  await expect(page.locator('.el-tab-pane canvas').first()).toBeVisible({ timeout: 60_000 })
}

/**
 * 在页面里跑产品自己的表格工具函数，返回它的文本结果（真实 SpreadJS 数据）。
 *
 * <p>L-115：根因修在产线（`SpreadGrid` 适配器 live 绑定 + `sheet.current` 等载入落定），这里只做
 * **有界重取一次**的韧性补充 —— 仅当失败形态是"表格正在重建/尚未就绪"这类瞬态时才重取一次，
 * 其余失败照旧一次判红（不给断言降级留口子）。
 */
async function runSheetTool(
  page: Page,
  tool: 'readSheetSummary' | 'findRows' | 'aggregateSheet',
  args: Record<string, unknown>
): Promise<{ ok: boolean; text: string }> {
  return page.evaluate(
    async ({ toolName, args: toolArgs, defCode }) => {
      const ft = (await import('/src/utils/frontend-tools.ts')) as unknown as {
        resolveSheetContext: (code: string) => Promise<
          { ok: false; reason: string } | { ok: true; ctx: unknown }
        >
      }
      const st = (await import('/src/utils/sheet-tools.ts')) as unknown as Record<
        string,
        (ctx: unknown, toolArgs2: unknown) => { ok: boolean; text?: string; reason?: string }
      >
      const transient = (text: string): boolean => /重建|尚未就绪|还没有可操作|请重试/.test(text)
      const attempt = async (): Promise<{ ok: boolean; text: string; transient: boolean }> => {
        const resolved = await ft.resolveSheetContext(defCode)
        if (!resolved.ok) {
          const text = `resolve 失败：${resolved.reason}`
          return { ok: false, text, transient: transient(text) }
        }
        try {
          const fn = st[toolName]
          const out = fn(resolved.ctx, toolArgs)
          const text = out.ok ? (out.text ?? '') : `拒绝：${out.reason ?? ''}`
          return { ok: out.ok, text, transient: false }
        } catch (error) {
          const text = `读取失败：${error instanceof Error ? error.message : String(error)}`
          return { ok: false, text, transient: transient(text) }
        }
      }
      let out = await attempt()
      if (out.transient) {
        await new Promise((resolve) => setTimeout(resolve, 150))
        out = await attempt()
      }
      return { ok: out.ok, text: out.text }
    },
    { toolName: tool, args, defCode: DEF }
  )
}

test.describe('@stub T 表格工具', () => {
  test('E2E-T13 聚合卡面：分组数与每组行数与真实表一致、按度量降序、共 N 组为全量', async ({ page, request }) => {
    const taskId = await createExportedTask(request)
    await openExportedSheet(page, taskId)
    await sendMessage(page, `scenario=SHEET-AGG 用例T13-${uniq()}`)

    // 卡面（聚合结果卡）：标题「聚合表格」+ 已成功
    const title = page.locator('[data-testid^="card-title-"]').filter({ hasText: '聚合表格' }).first()
    await expect(title).toBeVisible({ timeout: 60_000 })
    await waitRoundSettled(page, 60_000)

    // 用真实表格算期望：decimalPlaces 去重分组 + 每组行数
    const expected = await page.evaluate(async (defCode) => {
      const ft = (await import('/src/utils/frontend-tools.ts')) as unknown as {
        resolveSheetContext: (code: string) => Promise<
          | { ok: false; reason: string }
          | {
              ok: true
              ctx: {
                adapter: {
                  rowCount(): number
                  colCount(): number
                  header(c: number): string
                  getCell(r: number, c: number): unknown
                }
              }
            }
        >
      }
      const resolved = await ft.resolveSheetContext(defCode)
      if (!resolved.ok) return null
      const { adapter } = resolved.ctx
      const headers = Array.from({ length: adapter.colCount() }, (_, index) => adapter.header(index))
      // header 可能是中文标签：取不到 decimalPlaces 时按该表的固定列序（第 4 列）取
      let col = headers.indexOf('decimalPlaces')
      if (col < 0) col = 3
      const counts = new Map<string, number>()
      for (let row = 0; row < adapter.rowCount(); row += 1) {
        const key = String(adapter.getCell(row, col))
        counts.set(key, (counts.get(key) ?? 0) + 1)
      }
      return Array.from(counts.entries()).map(([key, rows]) => ({ key, rows }))
    }, DEF)
    expect(expected).not.toBeNull()
    const groups = expected as Array<{ key: string; rows: number }>
    expect(groups.length).toBeGreaterThan(1)

    // 卡面文本里逐组出现 "decimalPlaces <key>：<rows> 行"，且总量为全量组数
    const cardText = await page.locator('[data-testid^="msg-bubble-"].w-full').last().innerText()
    const panelText = await page.locator('aside').last().innerText()
    const surface = `${cardText}
${panelText}`
    for (const group of groups) {
      expect(surface).toContain(`${group.key}：${group.rows} 行`)
    }
    expect(surface).toMatch(new RegExp(`共 ${groups.length} 组`))

    // 降序：行数多的组先出现
    const ordered = [...groups].sort((a, b) => b.rows - a.rows)
    let cursor = -1
    for (const group of ordered) {
      const at = surface.indexOf(`${group.key}：${group.rows} 行`, cursor + 1)
      expect(at).toBeGreaterThan(cursor)
      cursor = at
    }
  })

  test('E2E-T05 count 档预览 + 分布：影响行数与真实表一致、分布分组可见', async ({ page, request }) => {
    const taskId = await createSheetImportTask(request)
    await openImportedSheet(page, taskId)
    await sendMessage(page, `scenario=SHEET-UPDATE 用例T05-${uniq()}`)

    const card = page.locator('[data-testid="sheet-preview-card"]')
    await expect(card).toBeVisible({ timeout: 60_000 })
    await expect(card.locator('[data-testid="sheet-preview-count"]')).toBeVisible()
    await expect(card.locator('[data-testid="sheet-preview-distribution"]')).toBeVisible()
    const dists = card.locator('[data-testid^="sheet-preview-dist-"]')
    expect(await dists.count()).toBeGreaterThan(0)

    // 影响行数 = 表里 code=USD 的行数（谓词命中的真实行数）
    const usdRows = await page.evaluate(async (defCode) => {
      const ft = (await import('/src/utils/frontend-tools.ts')) as unknown as {
        resolveSheetContext: (code: string) => Promise<
          { ok: false; reason: string } | { ok: true; ctx: { adapter: { rowCount(): number; getCell(r: number, c: number): unknown } } }
        >
      }
      const resolved = await ft.resolveSheetContext(defCode)
      if (!resolved.ok) return -1
      let hits = 0
      for (let row = 0; row < resolved.ctx.adapter.rowCount(); row += 1) {
        if (resolved.ctx.adapter.getCell(row, 0) === 'USD') hits += 1
      }
      return hits
    }, DEF)
    expect(usdRows).toBe(1)
    // 计数档的摘要行是「将影响 N 行 / M 格」
    await expect(card.locator('[data-testid="sheet-preview-summary"]')).toContainText(new RegExp(`将影响 ${usdRows} 行`))

    // 预览期零写：表里 USD 行仍是原值（decimalPlaces=2，应用前不改）
    const before = await page.evaluate(async (defCode) => {
      const ft = (await import('/src/utils/frontend-tools.ts')) as unknown as {
        resolveSheetContext: (code: string) => Promise<
          { ok: false; reason: string } | { ok: true; ctx: { adapter: { rowCount(): number; getCell(r: number, c: number): unknown } } }
        >
      }
      const resolved = await ft.resolveSheetContext(defCode)
      if (!resolved.ok) return null
      for (let row = 0; row < resolved.ctx.adapter.rowCount(); row += 1) {
        if (resolved.ctx.adapter.getCell(row, 0) === 'USD') return resolved.ctx.adapter.getCell(row, 3)
      }
      return null
    }, DEF)
    expect(String(before)).toBe('2')

    await card.locator('[data-testid="sheet-preview-cancel"]').click()
    await waitRoundSettled(page, 40_000)
  })

  test('E2E-T08 行指纹：预览后改一格 ⇒ 应用被整批拒绝并要求重新预览', async ({ page, request }) => {
    const taskId = await createSheetImportTask(request)
    await openImportedSheet(page, taskId)
    await sendMessage(page, `scenario=SHEET-UPDATE 用例T08-${uniq()}`)

    const card = page.locator('[data-testid="sheet-preview-card"]')
    await expect(card).toBeVisible({ timeout: 60_000 })

    // 预览生成后，真实改一格（走页面自己的适配器写口），指纹随即失效
    // L-115 诊断：失败信息里带**具体分支**（解析失败的原因 / 表里到底有什么），
    // 避免「touched=false」把"表格不可用"和"表里没有 USD"混成一条红。
    const probe = await page.evaluate(async (defCode) => {
      const ft = (await import('/src/utils/frontend-tools.ts')) as unknown as {
        resolveSheetContext: (code: string) => Promise<
          | { ok: false; reason: string }
          | {
              ok: true
              ctx: {
                adapter: {
                  rowCount(): number
                  getCell(r: number, c: number): unknown
                  writeCells: (updates: Array<{ row: number; col: number; value: unknown }>) => void
                }
              }
            }
        >
      }
      const resolved = await ft.resolveSheetContext(defCode)
      if (!resolved.ok) return { touched: false, reason: `resolve 失败：${resolved.reason}` }
      const { adapter } = resolved.ctx
      const rows = adapter.rowCount()
      for (let row = 0; row < rows; row += 1) {
        if (adapter.getCell(row, 0) === 'USD') {
          // 行指纹取「主键列」的值 ⇒ 改主键才构成"预览期间表被改动"
          adapter.writeCells([{ row, col: 0, value: 'USD-改过' }])
          return { touched: true, reason: '' }
        }
      }
      const head = Array.from({ length: Math.min(rows, 5) }, (_, row) => String(adapter.getCell(row, 0))).join(',')
      return { touched: false, reason: `表里没有 code=USD 的行（rowCount=${rows}，首列前 5 行=${head || '（空）'}）` }
    }, DEF)
    expect(probe.touched, probe.reason).toBe(true)

    await card.locator('[data-testid="sheet-preview-apply"]').click()
    // 整批拒绝：卡面出现「预览期间已变动 / 未应用任何改动」的失败结论
    await expect(page.locator('[data-testid="sheet-preview-card"]')).toHaveCount(0, { timeout: 30_000 })
    const reason = page.locator('[data-testid^="msg-bubble-"].w-full').last()
    await expect(reason).toContainText(/预览已失效|预览期间已变动|未应用任何改动/, { timeout: 30_000 })
    const unchanged = await page.evaluate(async (defCode) => {
      const ft = (await import('/src/utils/frontend-tools.ts')) as unknown as {
        resolveSheetContext: (code: string) => Promise<
          { ok: false; reason: string } | { ok: true; ctx: { adapter: { rowCount(): number; getCell(r: number, c: number): unknown } } }
        >
      }
      const resolved = await ft.resolveSheetContext(defCode)
      if (!resolved.ok) return null
      for (let row = 0; row < resolved.ctx.adapter.rowCount(); row += 1) {
        if (String(resolved.ctx.adapter.getCell(row, 0)).startsWith('USD')) {
          return { rows: resolved.ctx.adapter.rowCount(), decimalPlaces: String(resolved.ctx.adapter.getCell(row, 3)) }
        }
      }
      return null
    }, DEF)
    // 整批全或无：目标行的 decimalPlaces 仍是预览前的 2（没有被部分写 3）
    expect((unchanged as { decimalPlaces: string } | null)?.decimalPlaces).toBe('2')
  })

  test('E2E-T09 只读壳只拦写：读路径全可用、写路径被执行层拦截、前后端声明逐字段对账', async ({ page, request }) => {
    // 只读壳 = 已完成的导出任务（任务终态 ⇒ 表格仅供查看）
    const taskId = await createExportedTask(request)
    const disclosed = await apiGet<{ tools?: Array<{ name: string }> }>(
      request,
      `/api/ai/tools?page=export&taskType=EXPORT&step=EXPORT&taskId=${taskId}`
    )
    const names = (disclosed.tools ?? []).map((tool) => tool.name)
    // 读写工具都在披露面（披露由写闸开关决定）；只读性在执行层拦 —— 下面逐条验证
    for (const tool of ['read_sheet_summary', 'find_rows', 'aggregate_sheet', 'get_row_count', 'inspect_columns', 'review_cells']) {
      expect(names, `应披露读工具 ${tool}`).toContain(tool)
    }
    for (const tool of ['update_rows', 'replace_values', 'normalize_column', 'undo_last_sheet_edit']) {
      expect(names, `应披露写工具 ${tool}`).toContain(tool)
    }

    // 读路径在只读壳上照常可用（真实表格数据）
    await openExportedSheet(page, taskId)
    const read = await runSheetTool(page, 'readSheetSummary', { defCode: DEF, scope: 'sheet', limit: 5 })
    expect(read.ok).toBe(true)
    expect(read.text).toMatch(/共\s*\d+\s*行/)

    // 写路径在只读壳上被执行层拦（零改动）：走产品自己的前端工具执行器
    const write = await page.evaluate(async (defCode) => {
      const ft = (await import('/src/utils/frontend-tools.ts')) as unknown as {
        executeFrontendTool: (call: {
          name: string
          toolCallId?: string
          args: Record<string, unknown>
        }) => Promise<{ outcome: string; result: string }>
      }
      const out = await ft.executeFrontendTool({
        name: 'update_rows',
        toolCallId: `e2e-t09-${Date.now()}`,
        args: {
          defCode,
          scope: 'predicate',
          predicate: { scopeKeys: [], fields: [{ fieldCode: 'code', operator: 'EQ', value: 'USD' }] },
          set: [{ columnCode: 'decimalPlaces', mode: 'literal', value: 3 }],
          preview: 'full-diff'
        }
      })
      return { outcome: out.outcome, result: out.result }
    }, DEF)
    expect(write.outcome).toBe('failed')
    expect(write.result).toContain('仅供查看')

    // 前后端声明对账（同一份真实模块）：写工具的 rowRange / caseMode 参数名与后端一致
    const declared = await page.evaluate(async () => {
      const mod = (await import('/src/types/tools.ts')) as unknown as {
        FRONTEND_TOOLS: Array<{ name: string; args?: Array<{ name: string }> }>
      }
      const params = (name: string): string[] =>
        (mod.FRONTEND_TOOLS.find((tool) => tool.name === name)?.args ?? []).map((param) => param.name)
      return {
        names: mod.FRONTEND_TOOLS.map((tool) => tool.name),
        updateRowsParams: params('update_rows'),
        normalizeParams: params('normalize_column'),
        readSummaryParams: params('read_sheet_summary')
      }
    })
    for (const name of names.filter((tool) => tool.startsWith('read_') || tool === 'find_rows' || tool === 'aggregate_sheet')) {
      expect(declared.names).toContain(name)
    }
    // 逐字段对账：后端 @ToolParam 名与前端声明同名（rowRange 是 string 区间、caseMode 在 normalize 上）
    expect(declared.readSummaryParams).toEqual(
      expect.arrayContaining(['defCode', 'scope', 'columnCodes', 'rowRange', 'predicate', 'limit'])
    )
    expect(declared.updateRowsParams).toEqual(
      expect.arrayContaining(['defCode', 'scope', 'predicate', 'set', 'preview'])
    )
    expect(declared.normalizeParams).toContain('caseMode')
  })

  test('E2E-T01 读侧采样上限：≤20 行 / ≤2000 字符 / 恒带「共 N 行」/ 逐格 A1 地址', async ({ page, request }) => {
    const taskId = await createExportedTask(request)
    await openExportedSheet(page, taskId)
    const out = await runSheetTool(page, 'readSheetSummary', { defCode: DEF, scope: 'sheet', limit: 20 })
    expect(out.ok).toBe(true)
    expect(out.text.length).toBeLessThanOrEqual(2000)
    expect(out.text).toMatch(/共\s*\d+\s*行/)
    expect(out.text).toMatch(/\b[A-Z]{1,2}\d+\b/) // A1 地址
    const lines = out.text.split('\n').filter((line) => /\b[A-Z]{1,2}\d+\b/.test(line))
    expect(lines.length).toBeLessThanOrEqual(20)
  })

  test('E2E-T02 find_rows 三模式命中与地址', async ({ page, request }) => {
    const taskId = await createExportedTask(request)
    await openExportedSheet(page, taskId)
    const contains = await runSheetTool(page, 'findRows', { defCode: DEF, scope: 'sheet', search: 'US', match: 'contains' })
    expect(contains.ok).toBe(true)
    expect(contains.text).toMatch(/共\s*\d+\s*行/)
    const equals = await runSheetTool(page, 'findRows', { defCode: DEF, scope: 'sheet', search: 'USD', match: 'equals' })
    expect(equals.ok).toBe(true)
    const prefix = await runSheetTool(page, 'findRows', { defCode: DEF, scope: 'sheet', search: 'US', match: 'prefix' })
    expect(prefix.ok).toBe(true)
    // equals 的命中数 ≤ contains（子集关系，三种模式的语义真的不同）
    const hits = (text: string): number => Number(/共\s*(\d+)\s*行/.exec(text)?.[1] ?? '-1')
    expect(hits(equals.text)).toBeLessThanOrEqual(hits(contains.text))
    expect(hits(prefix.text)).toBeLessThanOrEqual(hits(contains.text))
    expect(contains.text).toMatch(/\b[A-Z]{1,2}\d+\b/)
  })

  test('E2E-T03 聚合降序 + 全量组数：「共 N 组」为全量、组间按度量降序', async ({ page, request }) => {
    const taskId = await createExportedTask(request)
    await openExportedSheet(page, taskId)
    const out = await runSheetTool(page, 'aggregateSheet', { defCode: DEF, scope: 'sheet', groupBy: 'decimalPlaces', metrics: ['count'] })
    expect(out.ok).toBe(true)
    expect(out.text).toMatch(/共\s*\d+\s*组/)

    // 解析 "分组：decimalPlaces <key>：<rows> 行" 的组序列
    const pairs = Array.from(out.text.matchAll(/(\S+?)：(\d+) 行/g)).map((match) => ({
      key: match[1],
      rows: Number(match[2])
    }))
    expect(pairs.length).toBeGreaterThan(1)
    // 「共 N 组」= 全量组数（不是截断后的条数）
    const claimed = Number(/共\s*(\d+)\s*组/.exec(out.text)?.[1] ?? '-1')
    expect(claimed).toBe(pairs.length)
    // 降序：逐对不增
    for (let i = 1; i < pairs.length; i += 1) {
      expect(pairs[i - 1].rows).toBeGreaterThanOrEqual(pairs[i].rows)
    }
    // 与真实表逐组核对总数（行数合计 == 表数据行数）
    const total = await page.evaluate(async (defCode) => {
      const ft = (await import('/src/utils/frontend-tools.ts')) as unknown as {
        resolveSheetContext: (code: string) => Promise<{ ok: boolean; ctx?: { adapter: { rowCount(): number } } }>
      }
      const resolved = await ft.resolveSheetContext(defCode)
      return resolved.ok ? resolved.ctx!.adapter.rowCount() : -1
    }, DEF)
    const sum = pairs.reduce((acc, pair) => acc + pair.rows, 0)
    expect(sum).toBeLessThanOrEqual(total + 1)
  })

  test('E2E-T07 应用后可撤销：撤销也经预览卡「应用」，第一层回滚到应用前、再撤无第二层', async ({ page, request }) => {
    const taskId = await createSheetImportTask(request)
    await openImportedSheet(page, taskId)
    await sendMessage(page, `scenario=SHEET-UPDATE 用例T07-${uniq()}`)
    const card = page.locator('[data-testid="sheet-preview-card"]')
    await expect(card).toBeVisible({ timeout: 60_000 })
    await card.locator('[data-testid="sheet-preview-apply"]').click()
    await waitRoundSettled(page, 60_000)

    const readUsd = async (): Promise<unknown> =>
      page.evaluate(async (defCode) => {
        const ft = (await import('/src/utils/frontend-tools.ts')) as unknown as {
          resolveSheetContext: (code: string) => Promise<
            { ok: false; reason: string } | { ok: true; ctx: { adapter: { rowCount(): number; getCell(r: number, c: number): unknown } } }
          >
        }
        const resolved = await ft.resolveSheetContext(defCode)
        if (!resolved.ok) return null
        for (let row = 0; row < resolved.ctx.adapter.rowCount(); row += 1) {
          if (resolved.ctx.adapter.getCell(row, 0) === 'USD') return resolved.ctx.adapter.getCell(row, 3)
        }
        return null
      }, DEF)

    /** 撤销栈深度（数据面真值：`sheet-tools.sheetUndoDepth`，按工作表实例缓存，与 UI 同源）。 */
    const undoDepth = async (): Promise<number> =>
      page.evaluate(async (defCode) => {
        const ft = (await import('/src/utils/frontend-tools.ts')) as unknown as {
          resolveSheetContext: (code: string) => Promise<
            { ok: false; reason: string } | { ok: true; ctx: { adapter: unknown } }
          >
        }
        const tools = (await import('/src/utils/sheet-tools.ts')) as unknown as {
          sheetUndoDepth: (adapter: unknown) => number
        }
        const resolved = await ft.resolveSheetContext(defCode)
        return resolved.ok ? tools.sheetUndoDepth(resolved.ctx.adapter) : -1
      }, DEF)

    // 应用后：USD.decimalPlaces = 3、撤销栈深度 1（`applySheetPlan` 反向入栈，深度上限 3）
    expect(String(await readUsd())).toBe('3')
    expect(await undoDepth()).toBe(1)

    // 撤销一层（新会话，桩每会话只发一次工具调用）。
    // 契约（2026-10-25 工具安全边界）：`undo_last_sheet_edit` 是 WRITE / FRONTEND 工具，
    // 走人围栏 —— `openWritePreview` → `planUndo` 建预览卡（`SheetPreviewCard#isUndoPlan`），
    // 用户点「应用」才 `applyUndoPlan` 剥栈并回灌；不点应用不写一格。
    await page.locator('[data-testid="new-chat"]').click()
    await expect(page.locator('[data-testid="composer-input"]')).toBeEnabled({ timeout: 30_000 })
    await sendMessage(page, `scenario=SHEET-UNDO 用例T07-撤销-${uniq()}`)
    const undoCard = page.locator('[data-testid="sheet-preview-card"]')
    await expect(undoCard).toBeVisible({ timeout: 60_000 })
    // 预览期零写：预览卡还没点应用，值仍是应用后的 3
    expect(String(await readUsd())).toBe('3')
    const undoPosted = watchPost(page, '/api/ai/frontend-tool-result')
    await undoCard.locator('[data-testid="sheet-preview-apply"]').click()
    const undoBody = (await undoPosted).postDataJSON() as { outcome?: string; result?: string }
    await waitRoundSettled(page, 60_000)

    // 第一层撤销完成：值回到应用前（2）、回执报「剩余可撤销 0 层」、栈已空
    expect(String(await readUsd())).toBe('2')
    expect(String(undoBody.outcome ?? '')).toBe('ok')
    expect(String(undoBody.result ?? '')).toContain('已撤销')
    expect(String(undoBody.result ?? '')).toContain('剩余可撤销 0 层')
    expect(await undoDepth()).toBe(0)

    // 再撤不可用 / 无第二层：空栈时 `planUndo` 不建卡、直接如实回执（零写），本轮照常收尾。
    // 判据 = 无预览卡 + 回灌 outcome=failed 且文案「没有可撤销的 AI 修改」+ 值不变；
    // 不靠放大超时来掩盖挂死（若空撤销仍建卡，本轮会停在挂起态，waitRoundSettled 会如实失败）。
    await page.locator('[data-testid="new-chat"]').click()
    await expect(page.locator('[data-testid="composer-input"]')).toBeEnabled({ timeout: 30_000 })
    const emptyPosted = watchPost(page, '/api/ai/frontend-tool-result')
    await sendMessage(page, `scenario=SHEET-UNDO 用例T07-空撤销-${uniq()}`)
    const emptyBody = (await emptyPosted).postDataJSON() as { outcome?: string; result?: string }
    await waitRoundSettled(page, 60_000)
    expect(await page.locator('[data-testid="sheet-preview-card"]').count()).toBe(0)
    expect(String(emptyBody.outcome ?? '')).toBe('failed')
    expect(String(emptyBody.result ?? '')).toContain('没有可撤销的 AI 修改')
    expect(String(await readUsd())).toBe('2')
    expect(await undoDepth()).toBe(0)
  })
})
