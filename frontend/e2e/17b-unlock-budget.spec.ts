/**
 * 真机 E2E · 批③c「解锁条目」第二档 —— 需要**缩小超时/预算**的条目（S-09 / S-11）。
 *
 * 为什么要单独一档：`app.ai.tool.exec-timeout`（默认 120s）与 `app.ai.resilience.total-budget`
 * （默认 300s）都以「分钟」计，主档里为它们等 120s/300s 不现实；harness 的
 * `E2E_EXTRA_BACKEND_ARGS` 可以按本棒改后端启动参数 —— 但它是**整棒生效**的，
 * 1ms 的工具超时会连带压死主档里所有要"工具真的执行成功"的用例，故拆档：
 *
 *   cd frontend && E2E_BACKEND_PORT=19930 E2E_DEGRADED_BACKEND_PORT=19931 E2E_STUB_PORT=19999 \
 *     E2E_APP_PORT=20001 E2E_DEGRADED_APP_PORT=20002 E2E_REDIS_DB=8 E2E_REDIS_DB_DEGRADED=9 \
 *     E2E_RUN_DIR=test-results/.e2e-run-unlock \
 *     E2E_EXTRA_BACKEND_ARGS="--app.ai.tool.exec-timeout=1ms --app.ai.resilience.total-budget=8s  *       --app.ai.hitl.frontend-tool-timeout=4s" \
 *     npx playwright test e2e/17b-unlock-budget.spec.ts
 *
 * 断言仍是「形状 + 终态」（不落在模型语义上）。
 */
import { expect, test, type APIRequestContext } from '@playwright/test'
import { createPublishableImportTask, openPath, waitRoundSettled } from './support/helpers'
import {
  chatStream,
  createImportTaskAtStep,
  doneFrame,
  framesOf,
  handoffCard,
  openImportUpload,
  sendAndWaitGate,
  startUploadHandoff,
  toolResults,
  uniq
} from './support/unlock'

function sid(tag: string): string {
  return `unlock-${tag}-${Date.now().toString(36)}-${Math.floor(Math.random() * 1e6)}`
}

async function runOf(request: APIRequestContext, sessionId: string): Promise<{ runId?: string; error?: string }> {
  const res = await request.get(
    `http://127.0.0.1:${process.env.E2E_BACKEND_PORT ?? '19930'}/api/ai/runs/current?sessionId=${encodeURIComponent(sessionId)}`
  )
  const json = (await res.json()) as { data?: { runId?: string } }
  return json.data ?? {}
}

test.describe('@stub S 超时面（缩小阈值的独立档）', () => {
  /**
   * S-09 工具执行独立超时：`app.ai.tool.exec-timeout=1ms` 下任何后端工具都超时 ⇒
   * `outcome=timeout` + `code=TOOL_TIMEOUT` + `executed=false`，**轮继续**到 done（不判整轮失败）。
   */
  test('E2E-S09 工具执行超时：outcome=timeout/TOOL_TIMEOUT、executed=false、轮继续到 done', async () => {
    const run = await chatStream(sid('s09'), `tool=get_config_def&args={"code":"CURRENCY"} ${uniq('S09')}`, {
      page: 'definitions'
    })
    const res = toolResults(run.frames).find((frame) => frame.name === 'get_config_def')
    expect(res, '应有一个 get_config_def 的 tool_result').toBeTruthy()
    expect(res?.outcome).toBe('timeout')
    expect(res?.code).toBe('TOOL_TIMEOUT')
    expect(res?.executed).toBe(false)
    expect(res?.ok).toBe(false)
    // 轮继续：有 done 终帧，且没有整轮级 error 帧
    expect(doneFrame(run.frames).type).toBe('done')
    expect(framesOf(run.frames, 'error')).toHaveLength(0)
  })

  /**
   * S-11 段预算重置式：`total-budget=8s` 下挂起等待 **12s**（> 预算）后再批准，
   * 本段仍能完整收尾 —— 不出现 `TOTAL_BUDGET_EXCEEDED`（挂起时长不计入段预算）。
   *
   * 差距说明：快照视图不暴露 `gateWaitMs`，故本条断的是"超预算的挂起之后仍无 TOTAL_BUDGET"
   * 这一必要条件；`totalMs - activeMs ≈ 挂起时长` 那一项无取数面（记入报告）。
   */
  test('E2E-S11 挂起不计入段预算：等待超过 total-budget 后批准仍完整收尾、无 TOTAL_BUDGET', async ({ page, request }) => {
    const taskId = await createPublishableImportTask(request)
    await openPath(page, `/import?taskId=${taskId}`)
    await expect(page.locator('footer')).toContainText('task:IMPORT/PUBLISH', { timeout: 30_000 })

    await sendAndWaitGate(page, `tool=start_publish&args={"taskId":${taskId}} ${uniq('S11')}`)
    const gate = page.locator('[data-testid="hitl-gate"]')
    // 挂起 12s（预算 8s）：人等待的这段时间不能被算进段预算
    await page.waitForTimeout(12_000)
    await expect(gate).toBeVisible()
    // start_publish 是不可逆动作（Category P）：批准前必须勾选同意位
    const ack = page.locator('[data-testid="gate-ack-box"]')
    if (await ack.count() > 0) await ack.click()
    await gate.getByRole('button', { name: '批准', exact: true }).click()
    await expect(page.locator('[data-testid="hitl-gate"]')).toHaveCount(0, { timeout: 60_000 })
    await waitRoundSettled(page, 120_000)

    const sessionId = await page.evaluate(() => sessionStorage.getItem('ai-session-id') ?? '')
    const run = await runOf(request, sessionId)
    expect(String(run.error ?? '')).not.toContain('TOTAL_BUDGET')
    // 轮有终端正文（收尾文本而不是错误卡）
    const text = (await page.locator('[data-testid^="msg-bubble-"].w-full').last().innerText()).trim()
    expect(text.length).toBeGreaterThan(0)
    expect(text).not.toContain('TOTAL_BUDGET_EXCEEDED')
  })
})

test.describe('@stub A 上传交接的无超时面', () => {
  /**
   * A-08 交接卡无超时：`frontend-tool-timeout` 压到 4s 后静置 12s（3× 上限），
   * 交接卡仍可继续（`ui_request_upload` 是"等人"类工具，产品明确不套该超时）。
   * 静置期间不得出现任何"已超时"文案，`/runs/current` 仍 reported awaitingExternal。
   */
  test('E2E-A08 交接卡无超时：超过 frontend-tool-timeout 三倍仍可继续，无超时文案', async ({ page, request }) => {
    const taskId = await createImportTaskAtStep(request, 'UPLOAD')
    await openImportUpload(page, taskId)
    await startUploadHandoff(page)

    await page.waitForTimeout(12_000)
    await expect(handoffCard(page)).toBeVisible()
    await expect(page.locator('[data-testid="upload-handoff-choose"]')).toBeEnabled()
    const panelText = await page.locator('[data-testid^="msg-bubble-"].w-full').last().innerText()
    expect(panelText).not.toMatch(/超时|timeout/i)

    const sessionId = await page.evaluate(() => sessionStorage.getItem('ai-session-id') ?? '')
    const current = await request.get(
      `http://127.0.0.1:${process.env.E2E_BACKEND_PORT ?? '19930'}/api/ai/runs/current?sessionId=${encodeURIComponent(sessionId)}`
    )
    const json = (await current.json()) as { data?: { awaitingExternal?: boolean } }
    expect(json.data?.awaitingExternal ?? false).toBe(true)

    // 仍可收口（不是只能等超时的死卡）
    await page.locator('[data-testid="upload-handoff-cancel"]').click()
    await expect(handoffCard(page)).toHaveCount(0, { timeout: 30_000 })
    await waitRoundSettled(page, 90_000)
  })
})
