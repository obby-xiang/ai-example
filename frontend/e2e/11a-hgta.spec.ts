/**
 * 批③ 11a · H 域（HITL 确认门）+ A 域（附件上传全链）
 *
 * 蓝本：docs/research/设计-真机E2E用例全集-2026-10-23.md §2.4 / §2.16。
 * 批① 已覆盖 H-02（拒绝零执行）/ H-09（不可撤销复选框），本文件做其余 H 条 + 全部 A 条。
 *
 * 端口段 18730/18799/18801、Redis DB 11（`support/harness-hgta.ts` 自起自收，不与别路共用
 * RUN_DIR/pid 台账）；桩的会话指纹 = 首条 user 消息，故每条提示词都带唯一字母后缀。
 */
import { expect, test, type APIRequestContext, type Page } from '@playwright/test'
import {
  createImportTaskAtUpload,
  createPublishableImportTask,
  openApp,
  openPath,
  sendMessage,
  until,
  waitRoundSettled
} from './support/helpers'
import {
  API,
  apiGet,
  apiPost,
  apiPostRaw,
  chatFrames,
  currentRun,
  sidOf,
  stubRequests,
  stubRequestsFor,
  toolMessagesOf,
  uniq,
  watchPost,
  type CurrentRun
} from './support/hgta'

const DEF = 'CURRENCY'

interface JobView {
  id: number
  jobType: string
  status: string
}

// ── 通用夹具 ────────────────────────────────────────────────────────────────

/** 造一个停在 EXPORT 步、已选 CURRENCY 的导出任务（start_export 的披露上下文 = task:EXPORT/EXPORT）。 */
async function createExportStepTask(request: APIRequestContext): Promise<number> {
  const task = await apiPost<{ id: number }>(request, '/api/tasks', { type: 'EXPORT', title: `E2E-HGTA-${uniq()}` })
  await apiPost(request, `/api/tasks/${task.id}/select-defs`, { defCodes: [DEF] })
  const res = await request.put(`${API}/api/tasks/${task.id}/step`, { data: { step: 'EXPORT' } })
  if (!res.ok()) throw new Error(`PUT step => HTTP ${res.status()}`)
  return task.id
}

/** 停在导入「上传配置」步的任务（导入类任务的另一条腿，用来造"类型不匹配"的预检失败）。 */
async function createImportAtUpload(request: APIRequestContext): Promise<number> {
  const task = await apiPost<{ id: number }>(request, '/api/tasks', { type: 'IMPORT', title: `E2E-HGTA-IMP-${uniq()}` })
  await apiPost(request, `/api/tasks/${task.id}/select-defs`, { defCodes: [DEF] })
  return task.id
}

async function jobsOf(request: APIRequestContext, taskId: number, type: string): Promise<JobView[]> {
  const all = await apiGet<JobView[]>(request, `/api/tasks/${taskId}/jobs`)
  return all.filter((job) => job.jobType === type)
}

function gate(page: Page) {
  return page.locator('[data-testid="hitl-gate"]')
}

function gateButton(page: Page, name: string) {
  return gate(page).getByRole('button', { name, exact: true })
}

async function openExportStep(page: Page, taskId: number): Promise<void> {
  await openPath(page, `/export?taskId=${taskId}`)
  await expect(page.locator('footer')).toContainText('task:EXPORT/EXPORT', { timeout: 30_000 })
}

async function openPublishStep(page: Page, taskId: number): Promise<void> {
  await openPath(page, `/import?taskId=${taskId}`)
  await expect(page.locator('footer')).toContainText('task:IMPORT/PUBLISH', { timeout: 30_000 })
}

/** 本轮真的披露给模型的工具名（上游请求原文里的 tools[]）。 */
function toolNamesOf(body: Record<string, unknown>): string[] {
  const tools = Array.isArray(body.tools) ? (body.tools as Array<{ function?: { name?: string } }>) : []
  return tools.map((tool) => tool?.function?.name ?? '').filter((name) => name.length > 0)
}

/** 快照里的当前挂起条目（按工具名取第一条未决）。 */
function pendingOf(run: CurrentRun, name: string): Record<string, unknown> | undefined {
  return (run.pendingEntries ?? []).find((entry) => entry.name === name)
}

// ── H-01 单卡批准 → 真实执行 ────────────────────────────────────────────────

test.describe('@stub H HITL 确认门（批③ 剩余条）', () => {
  test('E2E-H01 单卡批准：作业真实创建 + 文件产出 + 卡转只读「已批准」', async ({ page, request }) => {
    const taskId = await createExportStepTask(request)
    await openExportStep(page, taskId)
    await sendMessage(page, `start_export 任务 ${taskId} 批准用例-${uniq()}`)

    await expect(gate(page)).toBeVisible({ timeout: 40_000 })
    // 卡面必须指向真实目标（不是模型口头说的任务）
    await expect(page.locator('[data-testid="gate-rows"]')).toContainText(String(taskId))

    const sid = await sidOf(page)
    const before = await currentRun(request, sid)
    expect(before.found).toBe(true)
    expect(before.snapshotStatus).toBe('SUSPENDED')
    const entry = pendingOf(before, 'start_export')
    expect(entry).toBeTruthy()
    const toolCallId = String(entry?.toolCallId ?? '')
    // 三真值之一：请求帧确实发过（否则前端不该为它建卡）
    expect(entry?.req).toBe(true)
    expect(entry?.kind).toBe('CONFIRM')
    // 预检通过才进闸门（线上形态无 pass 布尔：pass = 各检查项全过）
    const preflight = entry?.preflight as { checks?: Array<{ name: string; pass: boolean }>; reportHash?: string } | null
    expect(preflight?.checks?.length ?? 0).toBeGreaterThan(3)
    expect(preflight?.checks?.every((check) => check.pass)).toBe(true)
    expect(String(preflight?.reportHash ?? '')).toMatch(/^[0-9a-f]{8,64}$/)

    // 批准前零作业
    expect(await jobsOf(request, taskId, 'EXPORT')).toHaveLength(0)

    await gateButton(page, '批准').click()
    await expect(gate(page)).toHaveCount(0, { timeout: 20_000 })
    await waitRoundSettled(page, 90_000)

    // 真实执行：作业恰好 1 个且跑到终态
    await until(async () => (await jobsOf(request, taskId, 'EXPORT')).length === 1, 30_000, '导出作业创建')
    await until(
      async () => (await jobsOf(request, taskId, 'EXPORT')).every((job) => ['COMPLETED', 'FAILED', 'CANCELLED'].includes(job.status)),
      60_000,
      '导出作业终态'
    )
    expect((await jobsOf(request, taskId, 'EXPORT'))[0]?.status).toBe('COMPLETED')

    // 产出文件真的可下载（不是"作业完成但无产物"）
    const file = await request.get(`${API}/api/tasks/${taskId}/files/${DEF}`)
    expect(file.ok()).toBe(true)
    expect((await file.body()).byteLength).toBeGreaterThan(1000)

    // 卡转只读「已批准」，且不再有待决按钮
    await expect(page.locator(`[data-testid="ro-badge-${toolCallId}"]`)).toHaveText(/已批准/, { timeout: 20_000 })
    await expect(page.locator(`[data-testid="ro-body-${toolCallId}"]`)).toHaveCount(1)
    await expect(gateButton(page, '批准')).toHaveCount(0)
  })

  // ── H-03 重复决策幂等（API 层：两次同 toolCallId） ───────────────────────

  test('E2E-H03 重复决策幂等：二次同 toolCallId 冲突 409 且不二次执行', async ({ page, request }) => {
    const taskId = await createExportStepTask(request)
    await openExportStep(page, taskId)
    await sendMessage(page, `start_export 任务 ${taskId} 幂等用例-${uniq()}`)
    await expect(gate(page)).toBeVisible({ timeout: 40_000 })

    const run = await currentRun(request, await sidOf(page))
    const entry = pendingOf(run, 'start_export')
    const runId = String(run.runId ?? '')
    const toolCallId = String(entry?.toolCallId ?? '')
    expect(runId).not.toBe('')
    expect(toolCallId).not.toBe('')

    const first = await apiPostRaw(request, '/api/ai/confirm', { runId, toolCallId, approved: true })
    expect(first.status).toBe(200)
    expect(first.json.accepted).toBe(true)
    expect(first.json.duplicate).toBe(false)
    expect(first.json.approved).toBe(true)
    expect(first.json.status).toBe('APPROVED')

    // 裁决落库后由挂起线程异步执行 ⇒ executed 用台账轮询取（不在响应瞬间断言）
    await until(async () => {
      const pendings = await apiGet<Array<Record<string, unknown>>>(request, `/api/ai/pending/${runId}`)
      return pendings.some((item) => item.toolCallId === toolCallId && item.executed === true)
    }, 30_000, '决策已执行（executed=true）')

    const second = await apiPostRaw(request, '/api/ai/confirm', { runId, toolCallId, approved: true })
    expect(second.status).toBe(409)
    expect(JSON.stringify(second.json)).toContain('DUPLICATE_TOOL_CALL_ID')

    // 台账计数不变：仍只有 1 个导出作业（重复决策不二次执行）
    await until(async () => (await jobsOf(request, taskId, 'EXPORT')).length === 1, 30_000, '唯一导出作业')

    // 重复决策后台账仍只记一次执行（状态只前进一步）
    const ledger = await apiGet<Array<Record<string, unknown>>>(request, `/api/ai/pending/${runId}`)
    const decided = ledger.find((item) => item.toolCallId === toolCallId)
    expect(decided?.status).toBe('APPROVED')

    // 决策后挂起收敛（不再有待决条目等待外部）
    await until(async () => {
      const now = await currentRun(request, await sidOf(page))
      return now.awaitingExternal !== true
    }, 30_000, '挂起收敛')

    // 他端（API）决策后本页签不自行感知 ⇒ 真刷新按快照重建为只读「已批准」，作业仍只 1 个
    await page.reload({ waitUntil: 'domcontentloaded' })
    await expect(page.locator('[data-testid="composer-input"]')).toBeVisible({ timeout: 30_000 })
    await expect(page.locator(`[data-testid="ro-badge-${toolCallId}"]`)).toHaveText(/已批准/, { timeout: 40_000 })
    await expect(page.locator('[data-testid="hitl-gate"]')).toHaveCount(0)
    expect(await jobsOf(request, taskId, 'EXPORT')).toHaveLength(1)
  })

  // ── H-04 多卡逐卡决策 ───────────────────────────────────────────────────

  test('E2E-H04 多卡逐卡决策：三条待决台账、闸门逐张揭示、全决前仍挂起全决后解除', async ({ page, request }) => {
    const taskId = await createExportStepTask(request)
    await openExportStep(page, taskId)
    await sendMessage(page, `start_export 任务 ${taskId} multi-card=3 多卡-${uniq()}`)

    await expect(gate(page).first()).toBeVisible({ timeout: 40_000 })
    const sid = await sidOf(page)
    const run = await currentRun(request, sid)
    const confirms = (run.pendingEntries ?? []).filter(
      (entry) => entry.name === 'start_export' && entry.kind === 'CONFIRM' && entry.entryStatus === 'PENDING'
    )
    // 一批三条待决条目（桩一条消息发 3 个 tool_call，id 互异）
    expect(confirms.length).toBe(3)
    expect(new Set(confirms.map((entry) => entry.toolCallId)).size).toBe(3)
    // 逐张揭示：此刻只有 1 个条目「请求帧已发」（req=true），前端只对已发帧建卡
    const emitted = () => confirms.filter((entry) => entry.req === true).length
    expect(emitted()).toBe(1)
    await expect(gate(page)).toHaveCount(1)
    expect(await page.locator('[data-testid="stop-btn"]').count()).toBe(1)

    // 逐卡拒绝（零执行路径）：每决一张，下一张的请求帧才发出并建卡
    for (let i = 1; i <= 3; i += 1) {
      await gateButton(page, '拒绝').first().click()
      if (i < 3) {
        // 仍有未决条目 ⇒ 本轮仍挂起 ⇒ 停止钮仍在（不得提前判定收敛）
        await expect(page.locator('[data-testid="stop-btn"]')).toHaveCount(1, { timeout: 20_000 })
        await expect(gate(page)).toHaveCount(1, { timeout: 40_000 })
        const mid = await currentRun(request, sid)
        expect(mid.snapshotStatus).toBe('SUSPENDED')
        expect(mid.awaitingExternal).toBe(true)
        const midReqs = (mid.pendingEntries ?? []).filter((entry) => entry.req === true).length
        expect(midReqs).toBe(i + 1)
      } else {
        await expect(gate(page)).toHaveCount(0, { timeout: 40_000 })
      }
    }

    await waitRoundSettled(page, 60_000)
    const after = await currentRun(request, sid)
    expect(after.awaitingExternal).toBe(false)
    expect(after.snapshotStatus).not.toBe('SUSPENDED')
    // 三张全拒绝 ⇒ 零作业
    expect(await jobsOf(request, taskId, 'EXPORT')).toHaveLength(0)
    // 三张已决卡都留在屏上且转只读「已拒绝」
    await expect(page.locator('[data-testid^="ro-badge-"]').filter({ hasText: '已拒绝' })).toHaveCount(3)
  })

  // ── H-05 预检摘要三通道同源（帧 / runs·current / pending） ──────────────

  test('E2E-H05 预检摘要三通道同源：confirm_request 帧 = runs/current = pending', async ({ request }) => {
    const taskId = await createExportStepTask(request)
    const sid = `e2e-h05-${uniq()}`
    const chat = await chatFrames(request, {
      sessionId: sid,
      message: `start_export 任务 ${taskId} 预检同源-${uniq()}`,
      context: { page: 'tasks', taskType: 'EXPORT', step: 'EXPORT', taskId }
    })
    expect(chat.status).toBe(200)

    // 通道① confirmation 请求帧（挂起即断连，响应体就是已发帧）
    const confirmFrame = chat.frames.find((frame) => frame.type === 'confirm_request')
    expect(confirmFrame, `已发帧类型：${chat.frames.map((f) => f.type).join(',')}`).toBeTruthy()
    const frameGate = (confirmFrame?.gate ?? confirmFrame) as Record<string, unknown>
    const framePreflight = (frameGate.preflight ?? confirmFrame?.preflight) as Record<string, unknown> | null
    expect(framePreflight).toBeTruthy()

    // 通道② runs/current
    const run = await currentRun(request, sid)
    expect(run.snapshotStatus).toBe('SUSPENDED')
    const entry = pendingOf(run, 'start_export')
    const currentPreflight = entry?.preflight as Record<string, unknown> | null
    expect(currentPreflight).toBeTruthy()

    // 通道③ pending/{runId}（诊断快照：同一条目的另一份原文）
    const pendings = await apiGet<Array<Record<string, unknown>>>(request, `/api/ai/pending/${String(run.runId)}`)
    const pendingEntry = pendings.find((item) => item.name === 'start_export')
    const pendingPreflight = pendingEntry?.preflight as Record<string, unknown> | null
    expect(pendingPreflight).toBeTruthy()

    // 逐值相等：target / checks / impact / reportHash
    expect(currentPreflight).toEqual(framePreflight)
    expect(pendingPreflight).toEqual(framePreflight)
    const checks = (framePreflight?.checks ?? []) as Array<{ name: string; pass: boolean; detail: string }>
    expect(checks.map((check) => check.name)).toEqual(['任务存在', '类型匹配', '无在途作业', '预检指纹新鲜'])
    expect(checks.every((check) => check.pass)).toBe(true)
    expect(String(framePreflight?.impact ?? '')).toContain('将导出 1 个配置定义')
    expect(String(framePreflight?.reportHash ?? '')).toMatch(/^[0-9a-f]{8,64}$/)
    const target = framePreflight?.target as { taskId?: number; type?: string; status?: string } | undefined
    expect(target?.taskId).toBe(taskId)
    expect(target?.type).toBe('EXPORT')
    // reportHash 绑定：同一份预检结论 ⇒ 三通道同号
    expect(pendingPreflight?.reportHash).toBe(currentPreflight?.reportHash)
    expect(framePreflight?.reportHash).toBe(currentPreflight?.reportHash)

    // 收尾：拒绝，让挂起轮收敛，避免污染后续（同一 Redis DB 下的孤儿挂起）
    await apiPostRaw(request, '/api/ai/confirm', {
      runId: String(run.runId),
      toolCallId: String(entry?.toolCallId ?? ''),
      approved: false
    })
  })

  // ── H-06 预检失败不进闸门 ───────────────────────────────────────────────

  test('E2E-H06 预检失败不进闸门：PRECHECK_FAILED 结构化失败 + 零闸门副作用 + retryable', async ({ request }) => {
    // 导入类任务不能发起 EXPORT 作业 ⇒ 预检「类型匹配」项必失败
    const importTask = await createImportAtUpload(request)
    const sid = `e2e-h06-${uniq()}`
    const chat = await chatFrames(request, {
      sessionId: sid,
      message: `start_export 任务 ${importTask} 预检失败-${uniq()}`,
      context: { page: 'tasks', taskType: 'IMPORT', step: 'UPLOAD', taskId: importTask }
    })
    expect(chat.status).toBe(200)

    const types = chat.frames.map((frame) => String(frame.type ?? ''))
    // 零闸门副作用：绝不出现 confirm_request / suspended 帧
    expect(types).not.toContain('confirm_request')
    expect(types).not.toContain('suspended')

    // 结构化失败：tool_result 帧带 PRECHECK_FAILED 与 retryable
    const resultFrame = chat.frames.find((frame) => String(frame.type ?? '').includes('tool_result'))
    expect(resultFrame, `已发帧类型：${types.join(',')}`).toBeTruthy()
    const payload = JSON.stringify(resultFrame)
    expect(payload).toContain('PRECHECK_FAILED')
    expect(payload).toMatch(/"retryable"\s*:\s*true/)

    // 零副作用②：没有挂起轮，也没有待决条目
    const run = await currentRun(request, sid)
    expect(run.awaitingExternal).toBe(false)
    if (run.found) {
      expect((run.pendingEntries ?? []).filter((entry) => entry.entryStatus === 'PENDING')).toHaveLength(0)
    }
    // 零副作用③：导入任务上没有任何 EXPORT 作业
    expect(await jobsOf(request, importTask, 'EXPORT')).toHaveLength(0)
  })

  // ── H-07 越 scope 面：披露闸先于预检（未披露 ⇒ 零发起、零预检） ──────────

  test('E2E-H07 越 scope 面：未披露的工具零发起（披露闸先于预检），page:tasks 下才披露', async ({ request }) => {
    const taskId = await createExportStepTask(request)

    // ── 半段① 上下文 = definitions（不披露 start_export） ──
    const sid = `e2e-h07-${uniq()}`
    const message = `start_export 任务 ${taskId} 越scope-${uniq()}`
    const chat = await chatFrames(request, { sessionId: sid, message, context: { page: 'definitions' } })
    expect(chat.status).toBe(200)

    // 披露面证据（唯一真相：本轮真的发给模型的 tools[]）
    const sent = stubRequestsFor(message)
    expect(sent.length).toBeGreaterThan(0)
    const tools = toolNamesOf(sent[0].body)
    expect(tools.length).toBeGreaterThan(0)
    expect(tools).not.toContain('start_export')

    // 零闸门、零预检、零执行：一轮纯文本
    const types = chat.frames.map((frame) => String(frame.type ?? ''))
    expect(types).not.toContain('confirm_request')
    expect(types).not.toContain('suspended')
    expect(types).not.toContain('tool_start')
    expect(types).toContain('done')
    const run = await currentRun(request, sid)
    expect(run.awaitingExternal ?? false).toBe(false)
    expect(await jobsOf(request, taskId, 'EXPORT')).toHaveLength(0)

    // ── 半段② 同一句话，上下文换成 page:tasks ⇒ 披露恢复、闸门出现（对照，证明①不是空转） ──
    const sid2 = `e2e-h07b-${uniq()}`
    const message2 = `start_export 任务 ${taskId} 对照-${uniq()}`
    const chat2 = await chatFrames(request, { sessionId: sid2, message: message2, context: { page: 'tasks' } })
    expect(chat2.status).toBe(200)
    const sent2 = stubRequestsFor(message2)
    expect(toolNamesOf(sent2[0].body)).toContain('start_export')
    expect(chat2.frames.map((frame) => String(frame.type ?? ''))).toContain('confirm_request')
    // 收尾：拒绝，让挂起轮收敛
    const run2 = await currentRun(request, sid2)
    await apiPostRaw(request, '/api/ai/confirm', {
      runId: String(run2.runId ?? ''),
      toolCallId: String(pendingOf(run2, 'start_export')?.toolCallId ?? ''),
      approved: false
    })
    expect(await jobsOf(request, taskId, 'EXPORT')).toHaveLength(0)
  })

  // ── H-08 已决只读态 + 六态徽标（可产出部分） ────────────────────────────

  test('E2E-H08 已决只读态：一轮内「已拒绝 + 已批准」双徽标正确、卡体默认折叠可展开', async ({ page, request }) => {
    const taskId = await createExportStepTask(request)
    await openExportStep(page, taskId)
    // 同一轮出两张卡（桩 multi-card），一张拒绝一张批准 ⇒ 两种终态徽标同屏可比
    await sendMessage(page, `start_export 任务 ${taskId} multi-card=2 只读态-${uniq()}`)
    await expect(gate(page)).toBeVisible({ timeout: 40_000 })

    const sid = await sidOf(page)
    const first = await currentRun(request, sid)
    const pendingIds = (first.pendingEntries ?? [])
      .filter((entry) => entry.kind === 'CONFIRM' && entry.entryStatus === 'PENDING')
      .map((entry) => String(entry.toolCallId))
    expect(pendingIds).toHaveLength(2)

    // 卡①拒绝（拒绝永远可用，且拒绝不得被画成「已批准 · 执行失败」）
    const rejectedId = pendingIds[0]
    await gateButton(page, '拒绝').click()
    // 卡②揭示后批准（真实执行）
    await expect(gate(page)).toBeVisible({ timeout: 40_000 })
    const second = await currentRun(request, sid)
    const approvedId = (second.pendingEntries ?? [])
      .filter((entry) => entry.kind === 'CONFIRM' && entry.entryStatus === 'PENDING' && entry.req === true)
      .map((entry) => String(entry.toolCallId))[0]
    expect(approvedId).toBeTruthy()
    expect(approvedId).not.toBe(rejectedId)
    await gateButton(page, '批准').click()
    await expect(gate(page)).toHaveCount(0, { timeout: 20_000 })
    await waitRoundSettled(page, 90_000)

    // 拒绝终态：✗ 已拒绝 / data-state=rejected
    const rejectedBadge = page.locator(`[data-testid="ro-badge-${rejectedId}"]`)
    await expect(rejectedBadge).toHaveText('✗ 已拒绝', { timeout: 20_000 })
    await expect(rejectedBadge).toHaveAttribute('data-state', 'rejected')
    // 批准终态：✓ 已批准（执行成功）或 ! 已批准 · 执行失败（真实执行结论，二者都算「已批准」支）
    const approvedBadge = page.locator(`[data-testid="ro-badge-${approvedId}"]`)
    await expect(approvedBadge).toHaveText(/已批准|执行失败/, { timeout: 30_000 })
    await expect(approvedBadge).toHaveAttribute('data-state', /^(approved|failed)$/)

    // 已决卡体默认折叠（v-show 隐藏，不是不渲染）
    await expect(page.locator(`[data-testid="ro-body-${rejectedId}"]`)).toBeHidden()
    await expect(page.locator(`[data-testid="ro-body-${approvedId}"]`)).toBeHidden()

    // 卡头可点开 → 卡体显示；已决不出现任何操作按钮
    await page.locator(`[data-testid="card-title-${rejectedId}"]`).click()
    await expect(page.locator(`[data-testid="ro-body-${rejectedId}"]`)).toBeVisible()
    await expect(page.locator('[data-testid="hitl-gate"]')).toHaveCount(0)
    await expect(page.locator('[data-testid="ro-body-'+rejectedId+'"]').getByRole('button', { name: '批准' })).toHaveCount(0)

    // 真实执行只发生在被批准的那一张：恰好 1 个导出作业
    await until(async () => (await jobsOf(request, taskId, 'EXPORT')).length === 1, 30_000, '批准的那张才执行')
    expect(await jobsOf(request, taskId, 'EXPORT')).toHaveLength(1)
    // 两张已决卡同屏（历史卡不被新一轮/刷新吞掉）
    await expect(page.locator('[data-testid^="ro-badge-"]')).toHaveCount(2)
  })

  // ── H-10 挂起即断连 + 决策后差量重连 ────────────────────────────────────

  test('E2E-H10 挂起即断连：浏览器侧 SSE 流在挂起处结束，决策后按 lastSeq 差量重连并续写原气泡', async ({ page, request }) => {
    // 观察器只读：把页面里真实 SSE 流的结束时刻记下来（不改产品、不改请求）
    await page.addInitScript(() => {
      const store = { streams: [] as Array<{ url: string; ended: boolean; chunks: number; error?: string }> }
      ;(window as unknown as { __hgtaStreams: typeof store }).__hgtaStreams = store
      const original = window.fetch.bind(window)
      window.fetch = async (input: RequestInfo | URL, init?: RequestInit): Promise<Response> => {
        const res = await original(input as RequestInfo, init)
        const url = typeof input === 'string' ? input : input instanceof URL ? input.href : input.url
        if (!(res.headers.get('content-type') ?? '').includes('text/event-stream') || !res.body) return res
        const record = { url, ended: false, chunks: 0 }
        store.streams.push(record)
        const reader = res.body.getReader()
        const stream = new ReadableStream<Uint8Array>({
          start(controller) {
            const pump = async (): Promise<void> => {
              try {
                for (;;) {
                  const { done, value } = await reader.read()
                  if (done) {
                    record.ended = true
                    controller.close()
                    return
                  }
                  record.chunks += 1
                  controller.enqueue(value)
                }
              } catch (error) {
                record.error = String(error)
                try {
                  controller.error(error)
                } catch {
                  /* 已关闭 */
                }
              }
            }
            void pump()
          },
          cancel(reason) {
            record.ended = true
            void reader.cancel(reason)
          }
        })
        return new Response(stream, { status: res.status, statusText: res.statusText, headers: res.headers })
      }
    })

    const taskId = await createExportStepTask(request)
    await openExportStep(page, taskId)

    const reattachUrls: string[] = []
    page.on('request', (req) => {
      if (req.url().includes('/api/ai/events/')) reattachUrls.push(req.url())
    })

    await sendMessage(page, `start_export 任务 ${taskId} 断连-${uniq()}`)
    await expect(gate(page)).toBeVisible({ timeout: 40_000 })

    // 断连证据：chat 的 SSE 流已经在浏览器侧读到 EOF，而卡还在等用户（挂起即收流）
    await until(
      async () => page.evaluate(() => {
        const store = (window as unknown as { __hgtaStreams?: { streams: Array<{ url: string; ended: boolean }> } }).__hgtaStreams
        return (store?.streams ?? []).some((item) => item.url.includes('/api/ai/chat') && item.ended === true)
      }),
      20_000,
      'chat SSE 流结束（挂起即断连）'
    )
    await expect(gate(page)).toBeVisible()
    // 挂起期间流已结束：不会有迟到帧再往原连接里写
    const streamSnapshot = await page.evaluate(() => {
      const store = (window as unknown as { __hgtaStreams?: { streams: Array<{ url: string; ended: boolean }> } }).__hgtaStreams
      return (store?.streams ?? []).map((item) => ({ url: item.url, ended: item.ended }))
    })
    expect(streamSnapshot.filter((item) => item.url.includes('/api/ai/chat') && item.ended).length).toBe(1)

    // 决策前的原气泡与已收字数
    const bubbleId = await page.locator('[data-testid^="msg-bubble-"].w-full').last().getAttribute('data-testid')
    expect(bubbleId).toBeTruthy()
    const beforeText = await page.locator(`[data-testid="${bubbleId}"]`).innerText()

    await gateButton(page, '批准').click()
    await waitRoundSettled(page, 90_000)

    // 差量重连：决策后走 GET /api/ai/events/{runId}?lastSeq=…（带序号 = 差量，不是全量回放）
    await until(async () => reattachUrls.length > 0, 20_000, '决策后重连')
    expect(reattachUrls.some((url) => /\/api\/ai\/events\/[^?]+/.test(url))).toBe(true)
    expect(reattachUrls.every((url) => url.includes('lastSeq='))).toBe(true)
    // 差量口径：lastSeq > 0（已收过帧才可能带非零序号；全量回放会带 0）
    const lastSeqs = reattachUrls.map((url) => Number(/lastSeq=(\d+)/.exec(url)?.[1] ?? '-1'))
    expect(lastSeqs.every((seq) => seq > 0)).toBe(true)

    // 续写原气泡（同一 data-testid 仍在，且文本不丢）
    const bubble = page.locator(`[data-testid="${bubbleId}"]`)
    await expect(bubble).toHaveCount(1)
    const afterText = await bubble.innerText()
    expect(afterText.length).toBeGreaterThanOrEqual(beforeText.length)
    expect(afterText).not.toBe('')
  })

  // ── H-11 卡快照重建 ─────────────────────────────────────────────────────

  test('E2E-H11 刷新后卡快照重建：只对已发帧的未决条目建卡，重建后决策仍真实执行', async ({ page, request }) => {
    const taskId = await createExportStepTask(request)
    await openExportStep(page, taskId)
    await sendMessage(page, `start_export 任务 ${taskId} 重建-${uniq()}`)
    await expect(gate(page)).toBeVisible({ timeout: 40_000 })

    const sid = await sidOf(page)
    const before = await currentRun(request, sid)
    const expected = (before.pendingEntries ?? []).filter(
      (entry) =>
        entry.req === true
        && entry.entryStatus === 'PENDING'
        && ['CONFIRM', 'FRONTEND'].includes(String(entry.kind))
    )
    expect(expected.length).toBe(1)
    const toolCallId = String(expected[0]?.toolCallId ?? '')

    // 真刷新：卡由 /runs/current 快照重建（不是浏览器内存里那张）
    await page.reload({ waitUntil: 'domcontentloaded' })
    await expect(page.locator('[data-testid="composer-input"]')).toBeVisible({ timeout: 30_000 })
    await expect(gate(page)).toHaveCount(expected.length, { timeout: 40_000 })
    // kind 三值按真值过滤：CONFIRM 才画成确认门（BACKEND/FRONTEND 不冒充）
    await expect(page.locator(`[data-testid="card-title-${toolCallId}"]`)).toBeVisible()
    await expect(page.locator(`[data-testid="card-status-${toolCallId}"]`)).toHaveAttribute('data-state', 'pending')
    // 重建的卡不能是"一点即发"：批准按钮此刻必须可点但要求真实决策
    await gateButton(page, '批准').click()
    await expect(gate(page)).toHaveCount(0, { timeout: 20_000 })
    await waitRoundSettled(page, 90_000)

    await until(async () => (await jobsOf(request, taskId, 'EXPORT')).length === 1, 30_000, '重建后仅执行一次')
    expect(await jobsOf(request, taskId, 'EXPORT')).toHaveLength(1)
    // 已决卡不被补发帧复活：刷新后 + 决策后都不再出现待决门
    await expect(gate(page)).toHaveCount(0)
  })
})

// ── A 域：附件上传全链（§2.16） ──────────────────────────────────────────────

/** 页面侧跑过多少次附件上传（POST /api/ai/form/attachments）。 */
function attachmentPostCount(page: Page): { value: number } {
  const counter = { value: 0 }
  page.on('request', (req) => {
    if (req.url().includes('/api/ai/form/attachments') && req.method() === 'POST') counter.value += 1
  })
  return counter
}

async function openAttachmentForm(page: Page, tag: string): Promise<void> {
  await openApp(page)
  await sendMessage(page, `gf-attachment ${tag}`)
  await expect(page.locator('[data-testid="gf-fields"]')).toBeVisible({ timeout: 40_000 })
  await expect(page.locator('#gf-field-sheet')).toHaveAttribute('type', 'file')
}

test.describe('@stub A 附件上传全链', () => {
  test('E2E-A01/A04 附件两段式：上传只得 fileId、回灌只带 fileId、文件内容不进模型', async ({ page }) => {
    // 文件名可以进模型（元数据），内容不行 ⇒ 两个标记分开：文件名标记 / 内容哨兵
    const nameMark = uniq('file').toUpperCase()
    const contentMark = `ZQXSENTINEL${uniq().toUpperCase()}`
    const fileName = `sheet-${nameMark}.txt`
    await openAttachmentForm(page, `用例A01-${uniq()}`)
    const posts = attachmentPostCount(page)

    const uploaded = page.waitForResponse(
      (res) => res.url().includes('/api/ai/form/attachments') && res.request().method() === 'POST',
      { timeout: 30_000 }
    )
    await page.locator('#gf-field-sheet').setInputFiles({
      name: fileName,
      mimeType: 'text/plain',
      buffer: Buffer.from(`${contentMark}\nsecret line two\n`)
    })
    const response = await uploaded
    expect(response.status()).toBe(200)
    const payload = (await response.json()) as { data?: { fileId?: string; fileName?: string; size?: number } }
    const fileId = payload.data?.fileId ?? ''
    expect(fileId).toMatch(/^att_[A-Za-z0-9_-]{8,64}$/)
    expect(payload.data?.fileName).toBe(fileName)
    expect(payload.data?.size).toBeGreaterThan(0)
    expect(posts.value).toBe(1)

    // 字段回显：文件名 + 大小 + 移除入口
    const fields = page.locator('[data-testid="gf-fields"]')
    await expect(fields).toContainText(fileName, { timeout: 20_000 })
    await expect(fields).toContainText(/\d+(\.\d+)?\s?(B|KB)/)
    await expect(page.locator('[data-testid="gf-attach-remove-sheet"]')).toBeVisible()

    // 第二段：回灌体只含 fileId（不含内容/base64）
    const submitted = watchPost(page, '/api/ai/frontend-tool-result')
    await page.locator('[data-testid="gf-submit"]').click()
    const req = await submitted
    const raw = req.postData() ?? ''
    const body = req.postDataJSON() as { result?: string; cancelled?: boolean }
    expect(body.cancelled).toBeFalsy()
    const values = JSON.parse(body.result ?? '{}') as Record<string, unknown>
    expect(values.sheet).toBe(fileId)
    expect(raw).not.toContain(contentMark)
    expect(raw).not.toContain('secret line two')
    expect(raw).not.toMatch(/base64/i)

    await waitRoundSettled(page, 60_000)
    await expect(page.locator('[data-testid="gf-submit"]')).toHaveCount(0)

    // A-04：文件内容从未进模型上下文（上游请求原文逐字节扫描，零命中）
    const allBodies = stubRequests().map((item) => item.raw).join('\n')
    expect(allBodies).not.toContain(contentMark)
    expect(allBodies).not.toContain('secret line two')
    // 元数据（fileName / fileId）确实到了模型侧 —— 否则"两段式"只走到一半
    expect(allBodies).toContain(fileName)
    expect(stubRequests().some((item) => toolMessagesOf(item.body).some((text) => text.includes(fileId)))).toBe(true)
  })

  test('E2E-A03 大小与类型前端拒绝：零上传、可行动文案、字段回到未选态', async ({ page }) => {
    await openAttachmentForm(page, `用例A03-${uniq()}`)
    const posts = attachmentPostCount(page)
    const input = page.locator('#gf-field-sheet')

    // ① 类型不支持（.exe）
    await input.setInputFiles({ name: 'evil.exe', mimeType: 'application/octet-stream', buffer: Buffer.from('MZ') })
    await expect(page.locator('#gf-field-sheet-error')).toContainText('不支持该文件类型', { timeout: 15_000 })
    await expect(page.locator('[data-testid="gf-attach-remove-sheet"]')).toHaveCount(0)

    // ② 超过 5MB 上限（与后端 MAX_ATTACHMENT_BYTES 同口径）
    await input.setInputFiles({
      name: 'big.txt',
      mimeType: 'text/plain',
      buffer: Buffer.alloc(5 * 1024 * 1024 + 16, 97)
    })
    await expect(page.locator('#gf-field-sheet-error')).toContainText('不能超过 5MB', { timeout: 15_000 })
    await expect(page.locator('[data-testid="gf-attach-remove-sheet"]')).toHaveCount(0)

    // 两次都在本地拦下：一次上传都没有（不占额度、不落文件）
    expect(posts.value).toBe(0)
    // 字段仍可再次选择（未进入不可恢复态）
    await expect(input).toBeEnabled()
  })

  test('E2E-A05 上传中断可重试：失败文案可见、无残留 fileId、重选即成功', async ({ page }) => {
    await openAttachmentForm(page, `用例A05-${uniq()}`)
    const posts = attachmentPostCount(page)
    let abortOnce = true
    await page.route('**/api/ai/form/attachments**', async (route) => {
      if (abortOnce) {
        abortOnce = false
        await route.abort('failed')
        return
      }
      await route.continue()
    })

    await page.locator('#gf-field-sheet').setInputFiles({
      name: 'retry.txt',
      mimeType: 'text/plain',
      buffer: Buffer.from('retry me')
    })
    await expect(page.locator('#gf-field-sheet-error')).toBeVisible({ timeout: 20_000 })
    await expect(page.locator('#gf-field-sheet-error')).toContainText(/上传失败|重试/)
    // 无残留：没有 fileId 回显，也没有删除入口
    await expect(page.locator('[data-testid="gf-attach-remove-sheet"]')).toHaveCount(0)

    // 再选一次（这次放行）⇒ 成功且可移除
    await page.locator('#gf-field-sheet').setInputFiles({
      name: 'retry.txt',
      mimeType: 'text/plain',
      buffer: Buffer.from('retry me')
    })
    await expect(page.locator('[data-testid="gf-attach-remove-sheet"]')).toBeVisible({ timeout: 30_000 })
    await expect(page.locator('#gf-field-sheet-error')).toHaveCount(0)
    expect(posts.value).toBe(2)

    // 移除入口真的能清掉字段值（回灌体里不带附件）
    await page.locator('[data-testid="gf-attach-remove-sheet"]').click()
    await expect(page.locator('[data-testid="gf-attach-remove-sheet"]')).toHaveCount(0)
  })

  test('E2E-A02 附件三重校验：伪造 id / 换 runId 归属不符 / 消费即失效，均零副作用', async ({ request }) => {
    // 两张并行挂起的表单：各自 (runId, toolCallId)
    const sid1 = `e2e-a02a-${uniq()}`
    const chat1 = await chatFrames(request, { sessionId: sid1, message: `gf-attachment 用例A02a-${uniq()}`, context: { page: 'tasks' } })
    expect(chat1.frames.map((frame) => String(frame.type))).toContain('frontend_tool_request')
    const run1 = await currentRun(request, sid1)
    const t1 = String((run1.pendingEntries ?? [])[0]?.toolCallId ?? '')
    expect(run1.runId).toBeTruthy()
    expect(t1).not.toBe('')

    const sid2 = `e2e-a02b-${uniq()}`
    await chatFrames(request, { sessionId: sid2, message: `gf-attachment 用例A02b-${uniq()}`, context: { page: 'tasks' } })
    const run2 = await currentRun(request, sid2)
    const t2 = String((run2.pendingEntries ?? [])[0]?.toolCallId ?? '')
    expect(run2.runId).toBeTruthy()
    expect(t2).not.toBe('')

    const upload = async (runId: string, toolCallId: string, name: string): Promise<string> => {
      const res = await request.post(`${API}/api/ai/form/attachments`, {
        multipart: {
          file: { name, mimeType: 'text/plain', buffer: Buffer.from('a02 payload') },
          runId,
          toolCallId
        }
      })
      if (!res.ok()) throw new Error(`上传失败 HTTP ${res.status()} ${await res.text()}`)
      const json = (await res.json()) as { data?: { fileId?: string } }
      return json.data?.fileId ?? ''
    }

    // ① 伪造 id：不存在 ⇒ 拒绝
    const forged = await apiPostRaw(request, '/api/ai/frontend-tool-result', {
      runId: String(run1.runId),
      toolCallId: t1,
      result: JSON.stringify({ sheet: 'att_FORGEDFORGED01', memo: 'x' })
    })
    expect(forged.status).toBe(400)
    expect(forged.text).toContain('ATTACHMENT_')

    // ② 归属不符：A 表单上传的 fileId 拿到 B 表单提交 ⇒ 拒绝
    const fileIdA = await upload(String(run1.runId), t1, 'owned-a.txt')
    expect(fileIdA).toMatch(/^att_[A-Za-z0-9_-]{8,64}$/)
    const wrongOwner = await apiPostRaw(request, '/api/ai/frontend-tool-result', {
      runId: String(run2.runId),
      toolCallId: t2,
      result: JSON.stringify({ sheet: fileIdA, memo: 'x' })
    })
    expect(wrongOwner.status).toBe(400)
    expect(wrongOwner.text).toContain('ATTACHMENT_FORBIDDEN')

    // 零副作用：两次失败都没让挂起态变化
    const stillPending = await currentRun(request, sid2)
    expect(stillPending.snapshotStatus).toBe('SUSPENDED')
    expect((stillPending.pendingEntries ?? [])[0]?.entryStatus).toBe('PENDING')

    // ③ 一次性：正确 (run1,t1) 提交后消费；同一 toolCallId 的上传口随即关闭
    const ok = await apiPostRaw(request, '/api/ai/frontend-tool-result', {
      runId: String(run1.runId),
      toolCallId: t1,
      result: JSON.stringify({ sheet: fileIdA, memo: 'ok' })
    })
    expect(ok.status).toBe(200)
    const afterConsume = await request.post(`${API}/api/ai/form/attachments`, {
      multipart: {
        file: { name: 'late.txt', mimeType: 'text/plain', buffer: Buffer.from('late') },
        runId: String(run1.runId),
        toolCallId: t1
      }
    })
    expect(afterConsume.ok()).toBe(false)
    // 已消费的 fileId 不可再用（换一张表单也不行）
    const reuse = await apiPostRaw(request, '/api/ai/frontend-tool-result', {
      runId: String(run2.runId),
      toolCallId: t2,
      result: JSON.stringify({ sheet: fileIdA, memo: 'reuse' })
    })
    expect(reuse.status).toBe(400)
    expect(reuse.text).toMatch(/ATTACHMENT_(FORBIDDEN|CONSUMED|NOT_FOUND)/)
  })

  /**
   * 交接卡主链（A-06/A-07/A-08/A-09/A-10）在桩模式下**不可达**：桩的路由表把
   * `ui_request_upload.ref` 写成 `import.upload.entry`，而产品声明的上传入口是
   * `import.upload.choose`（ImportWizardView 的 uploadEntry）。产品的前置闸（ref 必须在本轮
   * 真实快照遍历里出现过）因此恒定拒绝，交接卡永远建不起来。本条把**这条拒绝路径**钉住，
   * 并把"桩 ref 与产品 ref 漂移"做成可回归的断言（任一方向修好，本条会红，提醒同步用例）。
   */
  test('E2E-A06 交接卡前置闸：未快照的 ref 一律拒绝（UI_REF_NOT_SNAPSHOTTED），零上传、轮继续', async ({ page, request }) => {
    const taskId = await createImportTaskAtUpload(request)
    await openPath(page, `/import?taskId=${taskId}`)
    await expect(page.locator('footer')).toContainText('task:IMPORT/UPLOAD', { timeout: 30_000 })

    // 产品侧真实的上传入口 ref 只有 import.upload.choose 一族（桩用的 entry 根本不存在）
    await expect(page.locator('[data-ai-ref="import.upload.choose"]')).toHaveCount(1, { timeout: 20_000 })
    await expect(page.locator('[data-ai-ref="import.upload.entry"]')).toHaveCount(0)

    const posts = attachmentPostCount(page)
    await sendMessage(page, `scenario=UI-UPLOAD 用例A06-${uniq()}`)

    // 拒绝理由落在卡面（真 DOM 文本），不是静默失败
    await expect(page.locator('[data-testid^="msg-bubble-"].w-full').last()).toContainText(/UI_REF_NOT_SNAPSHOTTED|未在本轮界面快照/, {
      timeout: 40_000
    })
    // 零交接卡、零上传、轮正常收口
    await expect(page.locator('[data-testid="upload-handoff"]')).toHaveCount(0)
    await waitRoundSettled(page, 60_000)
    expect(posts.value).toBe(0)
    const run = await currentRun(request, await sidOf(page))
    expect(run.awaitingExternal ?? false).toBe(false)
    // 上传口仍在（未被"半建立"状态占住）
    await expect(page.locator('[data-ai-ref="import.upload.choose"]')).toBeEnabled()
  })
})
