/**
 * 真机 E2E · 多步工作流（页面级动作分批串行）· Task 1「续跑上下文刷新与表单幻影」
 *
 * <h2>被验的场景（本批的原始场景，不是 CI 绿的替代品）</h2>
 * 生产 {@code app.ai.park.enabled=true} 下，一句话走完「导出 CURRENCY（货币字典）里的一行」：
 * <b>创建任务 → 导航向导 → 选配置 → 推进步骤 → 设条件</b>，且<b>全程没有一张冗余表单卡</b>
 * （{@code generative_form} 一次都不该发：配置项已由任务/用户明确值确定）。
 *
 * <p>筛选条件锚在种子数据的<b>真实列</b>上：{@code CURRENCY} 的字段是
 * {@code code / name / exchangeRate / decimalPlaces}（**没有 enabled**），种子行含
 * {@code code=CNY（人民币）}。所以用户话说的是"货币代码 code = CNY"，模型才有可能产生
 * {@code code=CNY} 这条条件；写"只导出启用的行"等于让它去筛一个不存在的列（本用例的
 * {@code code=CNY} 断言会必然落空 —— 那是用例自己的错，不是产品缺陷）。
 *
 * <p><b>明确停止点 = 筛选条件步（{@code currentStep=QUERY_COND}）</b>：任务书要求的就是
 * "创建、导航、选配置、推进、设条件"，本用例<b>不</b>覆盖 {@code start_export}（那是另一条
 * 有自己确认卡的链路）。因此提示词会显式说"到设条件就停下、不要启动导出"，断言也据此收口 ——
 * 这样"模型多走一步"才是被提示词约束住的偏差，而不是把产品多做的那一步误判成失败。
 *
 * <h2>为什么必须真机 + 真模型</h2>
 * <ul>
 * <li><b>park 链路只有真装配才有</b>：单元用例里 {@code SpToolCallingManager} 直接跑完循环，
 * 而生产是"验卡挂起 → 决策落库 → 续跑重建 → 下一跳"，中间隔着两次进程内切换；</li>
 * <li><b>"模型会不会按顺序走"是概率面</b>：本用例断言工具链真的发生过（历史载荷），
 * 不是断言模型措辞。</li>
 * </ul>
 *
 * <h2>证据面（三方同源）</h2>
 * <ol>
 * <li><b>浏览器</b>：确认卡出现/被批准（{@code approvals>=1} = 真的走过 park→续跑）、
 * {@code gf-fields} 恒为 0（无冗余表单）、无错误卡；</li>
 * <li><b>历史载荷</b> {@code GET /api/ai/history/{sid}}：工具调用名序列（{@code create_task} →
 * {@code navigate_to} → {@code select_definitions} → {@code confirm_step} → {@code set_condition}）
 * 与 {@code toolCallStates[].code}；{@code FORM_SLOTS_ALREADY_KNOWN} 一次都不许出现；</li>
 * <li><b>业务面（任务权威状态）</b> {@code GET /api/tasks/{id}}：{@code currentStep=QUERY_COND}
 * （本用例的明确停止点）且 {@code items[]} 里 {@code CURRENCY} 条目的 {@code conditionJson}
 * 已持久化并含 {@code code=CNY} —— 不再断言"存在 EXPORT 作业"（本用例根本不启动导出）。</li>
 * </ol>
 *
 * <p><b>运行方式</b>：{@code E2E_MODE=real AI_API_KEY=… AI_BASE_URL=… AI_MODEL=… npx playwright test 19-multistep-context}。
 * 本批施工环境无真 key，因此<b>本用例未在本地执行</b>（报告如实登记"未验证"）。
 */
import { expect, test, type APIRequestContext, type Page } from '@playwright/test'
import { api, openApp, sendMessage, sessionId } from './support/helpers'

/** 模型可能不按顺序走：给两轮机会（每轮内部自动推进多批），至少一轮走完全链才算通过。 */
const ATTEMPTS = Math.max(1, Number(process.env.E2E_MULTISTEP_ATTEMPTS ?? '2'))

/** 需要人工批准的卡（本场景唯一一张 = create_task 的可逆确认卡：无复选框）。 */
async function approveGatesUntilSettled(page: Page, deadlineMs: number): Promise<number> {
  const gate = page.locator('[data-testid="hitl-gate"]')
  const stop = page.locator('[data-testid="stop-btn"]')
  const deadline = Date.now() + deadlineMs
  let approvals = 0
  while (Date.now() < deadline) {
    if ((await gate.count()) > 0) {
      const approve = gate.getByRole('button', { name: '批准', exact: true })
      if (await approve.isEnabled().catch(() => false)) {
        await approve.click().catch(() => undefined)
        approvals += 1
      }
      // 决策落库后本轮由续跑推进：等这张卡收口再看下一张
      await expect(gate).toHaveCount(0, { timeout: 30_000 }).catch(() => undefined)
      continue
    }
    if ((await stop.count()) === 0) {
      return approvals
    }
    await page.waitForTimeout(300)
  }
  return approvals
}

interface HistoryCall {
  id: string
  name: string
  code?: string | null
  result?: string | null
}

/** 历史载荷里的工具调用序列（按 assistant 条目出现序；带条目 code 与工具结果原文）。 */
async function historyCalls(request: APIRequestContext, sid: string): Promise<HistoryCall[]> {
  const history = await api.get<{ messages: Array<Record<string, unknown>> }>(request, `/api/ai/history/${sid}`)
  const responses = new Map<string, string>()
  for (const item of history.messages) {
    for (const response of (item.toolResponses as Array<Record<string, unknown>> | undefined) ?? []) {
      const id = String(response.id ?? '')
      if (id) {
        responses.set(id, String(response.responseData ?? ''))
      }
    }
  }
  const calls: HistoryCall[] = []
  for (const item of history.messages) {
    const states = (item.toolCallStates as Record<string, Record<string, unknown>> | undefined) ?? {}
    for (const call of (item.toolCalls as Array<Record<string, unknown>> | undefined) ?? []) {
      const id = String(call.id ?? '')
      const state = states[id]
      calls.push({
        id,
        name: String(call.name ?? ''),
        code: state?.code === undefined ? null : (state.code as string | null),
        result: responses.get(id) ?? null
      })
    }
  }
  return calls
}

function indexOfFirst(calls: HistoryCall[], name: string): number {
  return calls.findIndex((call) => call.name === name)
}

/**
 * 从落库的条件 JSON 里抽出 `{field, value}` 对（形状不锁死：`fields[]` / `conditions[]` /
 * 直接的 `{key,value}` 都可能，后端 `TaskItem.conditionJson` 只保证是 JSON 文本）。
 *
 * <p>为什么按"对"抽而不是断言某个具体键：条件是**模型生成后经前端契约落库**的，键名由前端
 * `api/tasks.ts#setCondition` 决定、不是本用例的验收面；本用例要证的是"用户说的 code=CNY 真的进了库"。
 * 解析失败返回空表（由调用方断言"至少一对"），不吞异常也不伪造。
 */
function collectConditionPairs(raw: string): Array<{ field: string; value: string }> {
  let parsed: unknown
  try {
    parsed = JSON.parse(raw)
  } catch {
    return []
  }
  const pairs: Array<{ field: string; value: string }> = []
  const visit = (node: unknown): void => {
    if (Array.isArray(node)) {
      node.forEach(visit)
      return
    }
    if (node === null || typeof node !== 'object') {
      return
    }
    const record = node as Record<string, unknown>
    const fieldKey = ['field', 'fieldCode', 'code', 'key', 'column', 'name'].find(
      (key) => typeof record[key] === 'string'
    )
    const valueKey = ['value', 'values', 'operand', 'keyValue'].find((key) => record[key] !== undefined)
    if (fieldKey && valueKey) {
      pairs.push({ field: String(record[fieldKey]), value: JSON.stringify(record[valueKey]) ?? '' })
    }
    Object.values(record).forEach((value) => {
      if (value !== null && typeof value === 'object') {
        visit(value)
      }
    })
  }
  visit(parsed)
  return pairs
}

test.describe('@real 19 多步工作流上下文刷新（导出 CURRENCY 货币字典）', () => {
  test('E2E-MC01 park 下创建→导航→选配置→推进→设条件全链走通，且全程无冗余表单', async ({ page, request }) => {
    test.setTimeout(600_000)
    await openApp(page)

    let calls: HistoryCall[] = []
    let approvals = 0
    let chainComplete = false
    const observations: string[] = []

    for (let attempt = 1; attempt <= ATTEMPTS && !chainComplete; attempt += 1) {
      if (attempt > 1) {
        await page.locator('[data-testid="new-chat"]').click()
        await expect(page.locator('[data-testid="empty-state"]')).toBeVisible({ timeout: 20_000 })
        await openApp(page)
      }
      await sendMessage(
        page,
        `请用「货币字典」（CURRENCY）这个配置做一次导出：只导出货币代码 code = CNY（人民币）这一行。`
          + `按顺序自己做完：先 create_task 建导出任务，再 navigate_to 打开导出向导，`
          + `然后 select_definitions 勾上 CURRENCY，confirm_step 推进到筛选条件步，`
          + `再 set_condition 把条件设成 CURRENCY 的 code = CNY。`
          + `到筛选条件这一步就结束（这是本次任务的终点）：不要继续推进步骤，也不要启动导出作业。`
          + `配置项和条件都已经明确，不需要再发任何收集表单。`
      )
      approvals += await approveGatesUntilSettled(page, 240_000)
      const sid = await sessionId(page)
      calls = await historyCalls(request, sid)
      const names = calls.map((call) => call.name)
      chainComplete = ['create_task', 'navigate_to', 'select_definitions', 'confirm_step', 'set_condition']
        .every((name) => names.includes(name))
      observations.push(`attempt=${attempt} tools=${names.join(',')}`)
    }

    // eslint-disable-next-line no-console
    console.log(`[MC-01] ${observations.join(' | ')} approvals=${approvals}`)

    // ① 全链工具都真的发生过（顺序 = 页面级动作的契约顺序；同一批不扩权由单点用例负责）
    const order = ['create_task', 'navigate_to', 'select_definitions', 'confirm_step', 'set_condition']
      .map((name) => indexOfFirst(calls, name))
    expect(order.every((index) => index >= 0), `工具链不完整：${calls.map((call) => call.name).join(',')}`)
      .toBe(true)
    expect(order).toEqual([...order].sort((left, right) => left - right))

    // ② 无冗余表单：generative_form 一次都没发；槽位放行的幻影码一次都没出现
    expect(calls.filter((call) => call.name === 'generative_form')).toHaveLength(0)
    expect(calls.filter((call) => call.code === 'FORM_SLOTS_ALREADY_KNOWN')).toHaveLength(0)
    // 浏览器面同口径：整轮没有任何表单卡（提交/取消都算"多余的表单"）
    await expect(page.locator('[data-testid="gf-fields"]')).toHaveCount(0)

    // ③ 业务面：任务真的建成了，并停在「筛选条件步」、条件已按 CURRENCY.code=CNY 持久化
    //    停止点由提示词显式声明（"到筛选条件就结束、不要启动导出"）⇒ 这一步的多走一截是
    //    可判定的偏差；本用例覆盖范围不含 start_export，故不虚构作业、也不放宽下面的状态断言。
    const created = calls.find((call) => call.name === 'create_task' && /#\d+/.test(call.result ?? ''))
    expect(created, 'create_task 的回执里没有真实任务号').toBeTruthy()
    const taskId = Number(/#(\d+)/.exec(created?.result ?? '')?.[1] ?? 0)
    expect(taskId).toBeGreaterThan(0)
    const task = await api.get<{
      currentStep?: string
      items?: Array<{ defCode?: string; conditionJson?: string | null }>
    }>(request, `/api/tasks/${taskId}`)
    expect(task.currentStep, `任务未停在筛选条件步（实际 ${task.currentStep}）`).toBe('QUERY_COND')
    const currency = (task.items ?? []).find((item) => (item.defCode ?? '').toUpperCase() === 'CURRENCY')
    expect(currency, '任务条目里没有 CURRENCY').toBeTruthy()
    const conditionPairs = collectConditionPairs(currency?.conditionJson ?? '')
    expect(conditionPairs.length, `条件未持久化：${currency?.conditionJson ?? '(null)'}`).toBeGreaterThan(0)
    expect(
      conditionPairs.some((pair) => /code/i.test(pair.field) && pair.value.includes('CNY')),
      `条件里没有 code=CNY：${currency?.conditionJson ?? '(null)'}`
    ).toBe(true)

    // ④ 确实走了确认 / park 续跑（一次确认卡被批准 = 一条 HITL 路径被真机走过）
    expect(approvals, '全程没有任何确认卡被批准 ⇒ 没走到 park→续跑链路').toBeGreaterThanOrEqual(1)

    // ⑤ 用户面没有失败/中断提示（全程只有确认卡这一种人工介入）
    await expect(page.locator('[data-testid^="error-card-"]')).toHaveCount(0)
    await expect(page.locator('[data-testid="alert-interrupted"]')).toHaveCount(0)
  })
})
