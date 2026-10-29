/**
 * 真机 E2E · 多步工作流（页面级动作分批串行）· Task 1「续跑上下文刷新与表单幻影」
 *
 * <h2>被验的场景（本批的原始场景，不是 CI 绿的替代品）</h2>
 * 生产 {@code app.ai.park.enabled=true} 下，一句话走完「导出 CURRENCY（货币字典）」：
 * <b>创建任务 → 导航向导 → 选配置 → 推进步骤 → 设条件</b>，且<b>全程没有一张冗余表单卡</b>
 * （{@code generative_form} 一次都不该发：配置项已由任务/用户明确值确定）。
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
 * <li><b>浏览器</b>：确认卡出现/被批准、{@code gf-fields} 恒为 0（无冗余表单）、无错误卡；</li>
 * <li><b>历史载荷</b> {@code GET /api/ai/history/{sid}}：工具调用名序列（{@code create_task} →
 * {@code navigate_to} → {@code select_definitions} → {@code confirm_step} → {@code set_condition}）
 * 与 {@code toolCallStates[].code}；{@code FORM_SLOTS_ALREADY_KNOWN} 一次都不许出现；</li>
 * <li><b>业务面</b>：{@code GET /api/tasks/{id}/jobs} 至少一条 EXPORT 作业（创建真的落了库）。</li>
 * </ol>
 *
 * <p><b>运行方式</b>：{@code E2E_MODE=real AI_API_KEY=… AI_BASE_URL=… AI_MODEL=… npx playwright test 19-multistep-context}。
 * 本批施工环境无真 key，因此<b>本用例未在本地执行</b>（报告如实登记"未验证"）。
 */
import { expect, test, type APIRequestContext, type Page } from '@playwright/test'
import { api, openApp, sendMessage, sessionId } from './support/helpers'

/** 模型可能不按顺序走：给两轮机会（每轮内部自动推进多批），至少一轮走完全链才算通过。 */
const ATTEMPTS = Math.max(1, Number(process.env.E2E_MULTISTEP_ATTEMPTS ?? '2'))

/** 需要人工批准的卡（create_task / start_export 都是可逆确认卡：无复选框）。 */
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
        `请用「货币字典」（CURRENCY）这个配置做一次导出，条件是只导出启用的行。`
          + `按顺序自己做完：先 create_task 建导出任务，再 navigate_to 打开导出向导，`
          + `然后 select_definitions 勾上 CURRENCY，confirm_step 推进到筛选条件步，`
          + `再 set_condition 设置条件，最后如实告诉我现在到了哪一步。`
          + `配置项已经明确，不需要再发任何收集表单。`
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

    // ③ 业务面：任务真的建成了，且至少一条 EXPORT 作业存在
    const created = calls.find((call) => call.name === 'create_task' && /#\d+/.test(call.result ?? ''))
    expect(created, 'create_task 的回执里没有真实任务号').toBeTruthy()
    const taskId = Number(/#(\d+)/.exec(created?.result ?? '')?.[1] ?? 0)
    expect(taskId).toBeGreaterThan(0)
    const jobs = await api.get<Array<{ jobType?: string }>>(request, `/api/tasks/${taskId}/jobs`)
    expect(jobs.filter((job) => (job.jobType ?? '').toUpperCase() === 'EXPORT').length).toBeGreaterThan(0)

    // ④ 用户面没有失败/中断提示（全程只有确认卡这一种人工介入）
    await expect(page.locator('[data-testid^="error-card-"]')).toHaveCount(0)
    await expect(page.locator('[data-testid="alert-interrupted"]')).toHaveCount(0)
  })
})
