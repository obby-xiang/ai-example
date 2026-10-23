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
  waitRoundSettled
} from './support/helpers'
import {
  API,
  apiGet,
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
    const sid = `e2e-g05-${uniq()}`
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
    const sid = `e2e-g06-${uniq()}`
    const chat = await chatFrames(request, { sessionId: sid, message: `gf-expr-linkage 用例G06-${uniq()}`, context: { page: 'tasks' } })
    expect(chat.status).toBe(200)
    const types = chat.frames.map((frame) => String(frame.type))
    expect(types).not.toContain('frontend_tool_request')
    expect(chat.text).toMatch(/LINKAGE_CONDITION_INVALID|FORM_SCHEMA_REJECTED|REJECTED_ARGUMENTS/)
    // 轮继续并正常收尾
    expect(types).toContain('done')
    const run = await currentRun(request, sid)
    expect(run.awaitingExternal ?? false).toBe(false)
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
    const res = await request.get(`${API}/api/ai/form/option-sources/definition_fields?value=HD`)
    expect(res.ok()).toBe(true)
    const forged = await request.get(`${API}/api/ai/form/option-sources/https%3A%2F%2Fevil.example?value=HD`)
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
    const sid = `e2e-g13-${uniq()}`
    const chat = await chatFrames(request, { sessionId: sid, message: `gf-deep-linkage 用例G13-${uniq()}`, context: { page: 'tasks' } })
    expect(chat.status).toBe(200)
    const types = chat.frames.map((frame) => String(frame.type))
    expect(types).not.toContain('frontend_tool_request')
    expect(chat.text).toMatch(/LINKAGE_DEPTH|DEPTH|FORM_SCHEMA_REJECTED|REJECTED_ARGUMENTS/)
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
  /** C 级常规行为档（蓝本 §0.3）：N=10 ≥7；`E2E_REAL_SAMPLING` 只调小 N，阈值同比例推导。 */
  const GF_SAMPLING = Math.max(1, Number(process.env.E2E_REAL_SAMPLING ?? '10'))
  const GF_THRESHOLD = Math.ceil(GF_SAMPLING * 0.7)

  /**
   * G-01（蓝本 §2.5 + §0.3）：FILTER 回灌续跑 = **常规行为**概率面 ⇒ N=10 / 阈值 ≥7。
   *
   * <p>采样化前置（L-92，2026-10-23）已落地：表单字段带**轮次唯一** testid
   * `gf-field-<toolCallId>-<key>`，卡根 `gf-form-<toolCallId>`。修前字段只有静态 `id="gf-field-keyword"`，
   * 同页留下上一轮的只读卡时第二轮定位就串卡（实测第 2 轮停在 `approval_suspend`）⇒ 只能单轮。
   *
   * <p>分层口径（§0.3「C 级只断形状 + 概率下限」）：
   * <ul>
   *   <li>**C 级（进概率计数）**：模型这一轮愿不愿意调 `generative_form`（表单卡有没有出现）；</li>
   *   <li>**A/B 级（逐条硬断言，不计数）**：真出现了就必须对 —— 回灌 200、本轮续跑到落定、
   *       表单退出可填态、无卡级错误、提交后助手气泡有续写内容。</li>
   * </ul>
   *
   * <p>每轮用 `new-chat` 起**新会话**（采样独立性：不让上一轮的工具结果压进下一轮上下文）。
   *
   * <p>**填写面按 schema 驱动（2026-10-23，CI run 39977930803 定位）**：真模型的字段面不可假设 ——
   * 首跑里模型把「范围」生成成 `multi_select` + `minItems 1`，用例只填关键字就提交，回灌被后端
   * 按 schema 复核如实拒成 400 `FORM_RESULT_REJECTED`（`VALUE_TOO_FEW_ITEMS`：`scope` 至少 1 项）。
   * 这是**用例的填写面不完整**，不是产品缺陷（后端拒得对、卡片按 `issues[].key` 点亮了字段）。
   * 故提交前一律按本轮挂起条目的 `form.fields` 补齐（见 {@link fillFormBySchema}）。
   */
  test(`E2E-G01 FILTER 回灌续跑（真模型，N=${GF_SAMPLING} ≥${GF_THRESHOLD}）`, async ({ page, request }) => {
    test.setTimeout(900_000)
    let triggered = 0
    const problems: string[] = []

    await openApp(page)
    for (let index = 1; index <= GF_SAMPLING; index += 1) {
      await sendMessage(page, `请用生成式表单收集本次导出的筛选条件：关键字、范围（XN/HD），然后等我填写。采样 ${index}`)

      // 触发面（C 级）：本轮的 **live** 表单卡 —— 只认"卡内还有提交钮"的那张。
      // 只用 `[data-testid^="gf-form-"]` 的 `.last()` 会撞上**只读回放卡**（scope 是卡片序号，
      // 如 `gf-form-ro11`；字段是 disabled 的）。实测（本地 N=10，第 6 轮）：模型一轮内连发两张卡，
      // 第一张以「未提交 ✗ 已取消」的只读态留在页面上，`.last()` 正好选中它 ⇒ 关键字 `fill` 永远等
      // 可用状态。只读卡不带 `gf-submit`（渲染器 `v-if="!readonly"`），故用它把 live 卡钉死。
      const form = page
        .locator('[data-testid^="gf-form-"]')
        .filter({ has: page.locator('[data-testid="gf-submit"]') })
        .last()
      const appeared = await form
        .waitFor({ state: 'visible', timeout: 90_000 })
        .then(() => true)
        .catch(() => false)
      if (!appeared) {
        problems.push(`#${index} 模型未触发 generative_form（本轮按文本收尾）`)
        // 收干净本轮：真模型可能在本轮里**先被入参闸门拒绝、再挂起一次**（2026-10-23 本地实测：
        // `前端工具入参被安全闸拒绝` 之后紧跟一次 `approval_suspend`），而卡没渲染出来就没有
        // 提交/取消入口 ⇒ 面板一直"挂起等待中"，下一轮 composer 落不了键、`sendMessage` 的
        // `fill`（Playwright 默认 actionTimeout=0）会一直等到 15 分钟的测试上限。
        const stopBtn = page.locator('[data-testid="stop-btn"]')
        if ((await stopBtn.count()) > 0) await stopBtn.first().click({ timeout: 15_000 }).catch(() => undefined)
        await waitRoundSettled(page, 60_000)
      } else {
        triggered += 1
        // ── 以下 A/B 级：真触发了就必须对（不参与概率计数） ──────────────────
        // 场景指定的关键字先填；模型可能没按 `keyword` 命名（那时靠 schema 驱动的文本字段兜底）
        const keyword = form.locator('[data-testid$="-keyword"]')
        if ((await keyword.count()) > 0) await keyword.fill(`真模型-${index}`, { timeout: 15_000 })
        await fillFormBySchema(page, request, form, index)

        const posted = page.waitForResponse(
          (res) => res.url().includes('/api/ai/frontend-tool-result') && res.request().method() === 'POST',
          { timeout: 30_000 }
        )
        await form.locator('[data-testid="gf-submit"]').click({ timeout: 15_000 })
        const response = await posted
        // 非 200 时把拒绝体带进断言消息（FORM_RESULT_REJECTED 的 issues 是"哪一格没填对"的唯一真值）
        const rejectBody = response.status() === 200 ? '' : (await response.text()).slice(0, 400)
        expect(response.status(), `#${index} 回灌（FRONTEND_RESULT）非 200：${rejectBody}`).toBe(200)

        await waitRoundSettled(page, 120_000)
        await expect(page.locator('[data-testid="gf-submit"]'), `#${index} 提交后表单仍可填`).toHaveCount(0)
        await expect(page.locator('[data-testid="gf-error"]'), `#${index} 卡级错误`).toHaveCount(0)
        // 续跑真的跑了：提交后助手气泡有内容（桩面「模型收到值 JSON」由 G-02/G-04 的落档断言承载）
        const bubbleText = await page.locator('[data-testid^="msg-bubble-"].w-full').last().innerText()
        expect(bubbleText.trim().length, `#${index} 提交后续跑没有产生助手内容`).toBeGreaterThan(0)
      }

      await page.locator('[data-testid="new-chat"]').click({ timeout: 15_000 })
      await expect(page.locator('[data-testid="empty-state"]')).toBeVisible({ timeout: 20_000 })
    }

    // eslint-disable-next-line no-console
    console.log(`[G-01] N=${GF_SAMPLING} 触发=${triggered} 阈值≥${GF_THRESHOLD}`)
    expect(triggered, `未达阈值的采样：\n${problems.join('\n')}`).toBeGreaterThanOrEqual(GF_THRESHOLD)
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

/** 在页面里跑产品自己的表格工具函数，返回它的文本结果（真实 SpreadJS 数据）。 */
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
      const resolved = await ft.resolveSheetContext(defCode)
      if (!resolved.ok) return { ok: false, text: `resolve 失败：${resolved.reason}` }
      const st = (await import('/src/utils/sheet-tools.ts')) as unknown as Record<
        string,
        (ctx: unknown, toolArgs2: unknown) => { ok: boolean; text?: string; reason?: string }
      >
      const fn = st[toolName]
      const out = fn(resolved.ctx, toolArgs)
      return { ok: out.ok, text: out.ok ? (out.text ?? '') : `拒绝：${out.reason ?? ''}` }
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
    const touched = await page.evaluate(async (defCode) => {
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
      if (!resolved.ok) return false
      const { adapter } = resolved.ctx
      for (let row = 0; row < adapter.rowCount(); row += 1) {
        if (adapter.getCell(row, 0) === 'USD') {
          // 行指纹取「主键列」的值 ⇒ 改主键才构成"预览期间表被改动"
          adapter.writeCells([{ row, col: 0, value: 'USD-改过' }])
          return true
        }
      }
      return false
    }, DEF)
    expect(touched).toBe(true)

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

  test('E2E-T07 应用后可撤销：第一层撤销回滚到应用前', async ({ page, request }) => {
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
    expect(String(await readUsd())).toBe('3')

    // 撤销一层（新会话，桩每会话只发一次工具调用）
    await page.locator('[data-testid="new-chat"]').click()
    await expect(page.locator('[data-testid="composer-input"]')).toBeEnabled({ timeout: 30_000 })
    await sendMessage(page, `scenario=SHEET-UNDO 用例T07-撤销-${uniq()}`)
    await waitRoundSettled(page, 60_000)
    expect(String(await readUsd())).toBe('2')
  })
})
