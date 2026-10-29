/**
 * 真机 E2E · 批③c —— 「解锁条目」域（批③b 桩增强后才可承载的那批）。
 *
 * 蓝本：`docs/research/设计-真机E2E用例全集-2026-10-23.md` §2（编号沿用）。
 *
 * **本文件实际承载**（逐条实跑通过）：
 *   A-07 / A-09 / R-17（交接卡放弃·刷新两态）· L-02~L-08（请求级工具路由）·
 *   S-10 / S-12 · P-04 / P-10 · V 域模型侧成功路径（epoch 注入）· E-11（失败卡半段）·
 *   I-05 · U-06 · R-05。
 * **本文件显式跳过**（产品未实现 / 缺稳定触发面 / 蓝本断言与产品事实不符，逐条理由写在用例上方）：
 *   A-10 · R-18。
 * **另见 `17b-unlock-budget.spec.ts`**：需要缩小超时/预算的 S-09 / S-11（整棒后端参数不同，故拆档）。
 * **未承载**（A-08 / R-03 / R-16 / T-04·T-10·T-11·T-12 / U-04·U-05 / E-12 / Z-07·Z-08 / N-03 / V-21）：
 *   理由逐条见交付报告「未承载清单」。
 *
 * 两条新桩面（`scripts/README.md` §2.3c）是本域的存在理由：
 *   - `tool=<name>&args=<json>` 请求级工具路由 —— 此前 L-02~L-08 缺桩路由、无直接调用面；
 *   - `epoch=<nonce-N>` 请求级 epoch 注入 —— 此前 V 域模型侧成功路径恒停在「epoch 不匹配」围栏；
 *   - 桩 `UI-UPLOAD` 的 ref 已修为 `import.upload.choose` —— 此前交接卡恒在 `UI_REF_NOT_SNAPSHOTTED`。
 *
 * 纪律：真浏览器 + 真后端 + 真桩上游；断言只落 `data-testid` / 终态文本 / 帧字段（离散量）；
 *       每条提示词带唯一后缀（桩的会话指纹 = 首条 user 消息）。
 * 端口/DB：本域独占 19930 / 19931 / 19999 / 20001 / 20002；Redis **DB 8 / 9**。
 *       注：分配口径是 DB 20，实测本机 Redis 只有 16 个库（`SELECT 20` ⇒ `ERR DB index is out of range`），
 *       故改用 8/9（批② 占 5/6/10~14）。全部可用 `E2E_*` 环境变量覆盖。
 */
import { expect, test, type APIRequestContext, type Page } from '@playwright/test'
import {
  TURN_TEXT,
  api,
  apiUrl,
  createExportedTask,
  openPath,
  sessionId as pageSessionId,
  waitJob,
  waitRoundSettled,
  type JobView
} from './support/helpers'
import { aiRequestOptions } from './support/ai-identity'
import {
  API_BASE,
  chatStream,
  createImportTaskAtStep,
  doneFrame,
  framesOf,
  activeHandoff,
  handoffCard,
  listTasks,
  openImportUpload,
  sendAndWaitGate,
  sendPrompt,
  sendPromptUnsettled,
  snapshotAndEpoch,
  startUploadHandoff,
  taskJobs,
  toolResults,
  uiRuntime,
  uniq
} from './support/unlock'

function sid(tag: string): string {
  return `unlock-${tag}-${Date.now().toString(36)}-${Math.floor(Math.random() * 1e6)}`
}

/** 未决/已决卡片的 `data-state`（离散量；文案由 i18n 决定，不作契约）。 */
function lastCardState(page: Page) {
  return page.locator('[data-testid^="card-status-"], [data-testid^="ro-badge-"]').last()
}

// ─────────────────────────────────────────────────────────────────────────────
// A · ATT 附件上传全链（§2.16）：A-07 / A-09 / A-10
// （A-06 的拒绝路径已由批②覆盖；本批桩 ref 修好后交接卡首次可建）
// ─────────────────────────────────────────────────────────────────────────────

test.describe('@stub A 上传交接卡', () => {
  /**
   * A-07 交接卡放弃：`cancelled:true` ⇒ `FRONTEND_CANCELLED`，轮继续，卡落终态。
   * 与 A-06 的区别：那张卡是真的建起来了（快照闸先被模型自己的 ui_snapshot 放行）。
   */
  test('E2E-A07 交接卡放弃：cancelled 回灌 ⇒ 卡已取消、轮继续到 done、无悬挂条目', async ({ page, request }) => {
    const taskId = await createImportTaskAtStep(request, 'UPLOAD')
    await openImportUpload(page, taskId)
    await startUploadHandoff(page)
    // 区域声明的 uploadEntry 就是产品真实入口（批③b 修的 L-77：ref 不再漂移）
    expect((await activeHandoff(page))?.ref).toBe('import.upload.choose')

    // 卡面三段可见（选择文件 / 放弃 / 只收 xlsx）
    await expect(page.locator('[data-testid="upload-handoff-choose"]')).toBeEnabled()
    await expect(page.locator('[data-testid="upload-handoff-cancel"]')).toBeVisible()
    await expect(handoffCard(page)).toContainText('xlsx')

    await page.locator('[data-testid="upload-handoff-cancel"]').click()

    // 卡收起（activeUploadHandoff 清空 ⇒ 段 requiresAction 落回 false），状态收敛为「已取消」
    await expect(handoffCard(page)).toHaveCount(0, { timeout: 20_000 })
    await expect(lastCardState(page)).toHaveAttribute('data-state', 'cancelled', { timeout: 20_000 })
    // 轮继续到终态（挂起收口，不悬挂）
    await waitRoundSettled(page, 90_000)

    const sessionId = await pageSessionId(page)
    const current = await api.get<{ found: boolean; awaitingExternal?: boolean }>(
      request,
      `/api/ai/runs/current?sessionId=${encodeURIComponent(sessionId)}`
    )
    expect(current.awaitingExternal ?? false).toBe(false)
  })

  /**
   * A-09 / R-17（前半）：上传中刷新 —— 区域仍注册 ⇒ 交接卡**可继续**（不是死卡、不空白）。
   * 真 `page.reload()`（禁"重新挂载组件"替代）。
   */
  test('E2E-A09 上传中刷新：交接卡按快照重建、可选文件、无死循环重试', async ({ page, request }) => {
    const taskId = await createImportTaskAtStep(request, 'UPLOAD')
    await openImportUpload(page, taskId)
    await startUploadHandoff(page)

    const chatPosts: string[] = []
    page.on('request', (req) => {
      if (req.url().includes('/api/ai/chat') && req.method() === 'POST') chatPosts.push(req.url())
    })
    const resultPosts: string[] = []
    page.on('request', (req) => {
      if (req.url().includes('/api/ai/frontend-tool-result') && req.method() === 'POST') resultPosts.push(req.url())
    })

    await page.reload({ waitUntil: 'domcontentloaded' })
    await expect(page.locator('[data-testid="composer-input"]')).toBeVisible({ timeout: 30_000 })

    // 重建出的卡：等待态 + 主按钮可点（区域仍在）
    await expect(handoffCard(page)).toBeVisible({ timeout: 40_000 })
    // 前提先钉死：刷新后导入向导第 1 步要重新注册上传入口。
    // 路由页是懒加载，挂载可能晚于历史回放 ⇒ 这里等"区域已在"，把
    // "页面还没挂起来"（装载时序）与"卡面不可操作"（产品判据）分开归因。
    await expect(page.locator('[data-ai-ref="import.upload.choose"]')).toHaveCount(1, { timeout: 40_000 })
    await expect(page.locator('[data-testid="upload-handoff-choose"]')).toBeEnabled()
    // 刷新**不**重跑对话、不重放回灌（否则就是死循环重试）
    expect(chatPosts).toHaveLength(0)
    expect(resultPosts).toHaveLength(0)

    // 放弃可收口 ⇒ 重建出来的不是死卡
    await page.locator('[data-testid="upload-handoff-cancel"]').click()
    await expect(handoffCard(page)).toHaveCount(0, { timeout: 20_000 })
    await waitRoundSettled(page, 90_000)
    await expect(lastCardState(page)).toHaveAttribute('data-state', 'cancelled')
  })

  /**
   * A-10 区域消失后的交接卡（L-85 修复后转真断言）。
   *
   * 旧形态（本域 2026-10-23 实测，发现项 F-UNLOCK-1）：应用内离开导入向导后 `import.upload`
   * 区域已注销，但卡面仍为 `waiting`、`upload-handoff-choose` 仍可点；点下去 `chooseUploadFile`
   * 取不到 `region` ⇒ `receiveFile` 不执行，却仍以 `succeeded` 回灌「…并交给导入向导」= 谎报。
   *
   * <p>修后口径：卡面状态与「可操作性」同源（`uploadHandoffAvailability()`）⇒ 区域不在本页即
   * `missing`（主按钮禁用 + 指路「请先打开导入向导第 1 步」）；且**即便直达 store 走旧点击路径**
   * 也以 `failed` + 人话原因收口，回灌文本里不得出现「交给导入向导」。
   */
  test('E2E-A10 区域消失：交接卡转 missing（不可点），直达 choose 也不谎报成功', async ({ page, request }) => {
    const taskId = await createImportTaskAtStep(request, 'UPLOAD')
    await openImportUpload(page, taskId)
    await startUploadHandoff(page)
    await expect(page.locator('[data-testid="upload-handoff-choose"]')).toBeEnabled()

    // 应用内离开导入向导（不做刷新：刷新走的是"按快照重建"的另一条路）
    await page.locator('header .el-menu-item', { hasText: '任务中心' }).first().click()
    await expect(page.locator('footer')).not.toContainText('task:IMPORT/UPLOAD', { timeout: 30_000 })
    await expect(page.locator('[data-ai-ref="import.upload.choose"]')).toHaveCount(0, { timeout: 20_000 })

    // ① 卡面转 missing：主按钮不可点 + 指路；放弃仍可用（不是死卡）
    const choose = page.locator('[data-testid="upload-handoff-choose"]')
    await expect(choose).toBeDisabled({ timeout: 20_000 })
    await expect(page.locator('[data-testid="upload-handoff-hint"]')).toContainText('请先打开导入向导')
    await expect(page.locator('[data-testid="upload-handoff-cancel"]')).toBeEnabled()
    // ② store 侧判据同向：建卡时记的 status 不变，但"现在能不能交"已为 missing
    expect((await activeHandoff(page))?.status).toBe('waiting')

    // ③ 直达 choose（旧点击路径）不得谎报成功：回灌文本必须是"本次未执行上传"
    const results: string[] = []
    page.on('request', (req) => {
      if (req.url().includes('/api/ai/frontend-tool-result') && req.method() === 'POST') {
        results.push(req.postData() ?? '')
      }
    })
    await page.evaluate(async () => {
      const host = window as unknown as { __unlockImport?: (path: string) => Promise<unknown> }
      const load = async (path: string) => (host.__unlockImport ? host.__unlockImport(path) : import(path))
      const mod = await load('/src/stores/ai.ts') as { useAiStore: () => { chooseUploadFile: () => Promise<void> } }
      await mod.useAiStore().chooseUploadFile()
    })
    await expect.poll(() => results.length, { timeout: 20_000 }).toBe(1)
    expect(results[0]).not.toContain('交给导入向导')
    expect(results[0]).toContain('本次未执行上传')
    expect(results[0]).toContain('failed')
    await expect(handoffCard(page)).toHaveCount(0, { timeout: 20_000 })
    expect(await activeHandoff(page)).toBeNull()
  })

  /**
   * R-17（后半）：**已交接**后刷新 —— 卡落终态并保留元数据（文件名/结论），不重新挂起。
   * 交接的完成路径 = 用户点页面真实上传入口（同一套 uploadOne 链会 notifyAiUploadDone 收口）。
   */
  test('E2E-R17 已交接后刷新：卡保留元数据为终态、不重新挂起', async ({ page, request }) => {
    const taskId = await createImportTaskAtStep(request, 'UPLOAD')
    await openImportUpload(page, taskId)
    await startUploadHandoff(page)

    // 用户（用例代为）走页面既有上传链：el-upload 的隐藏 input 直接喂真文件
    const template = await request.get(apiUrl(`/api/tasks/${taskId}/files/templates?codes=DOC_TYPE`))
    const bytes = await template.body()
    await page.locator('input[type="file"]').setInputFiles({
      name: 'DOC_TYPE_unlock.xlsx',
      mimeType: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
      buffer: bytes
    })

    // 交接以成功收口（不等人也不重试）。页面链回灌的是**结论摘要**（`已上传并匹配 N 个文件`），
    // 不写文件名 —— 文件名只在「对话内选文件」那条链上才有（此处走的是页面入口）。
    await expect(handoffCard(page)).toHaveCount(0, { timeout: 60_000 })
    await waitRoundSettled(page, 90_000)
    const bubble = page.locator('[data-testid^="msg-bubble-"].w-full').last()
    await expect(bubble).toContainText('已上传并匹配 1 个文件', { timeout: 20_000 })

    await page.reload({ waitUntil: 'domcontentloaded' })
    await expect(page.locator('[data-testid="composer-input"]')).toBeVisible({ timeout: 30_000 })
    // 刷新后：结论元数据仍在（随段持久化），且**没有**重新出现的待选卡
    const after = page.locator('[data-testid^="msg-bubble-"].w-full').last()
    await expect(after).toContainText('已上传并匹配 1 个文件', { timeout: 30_000 })
    await expect(handoffCard(page)).toHaveCount(0)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// L · TOL 工具全量面（§2.24）：L-02…L-08 —— 全部走请求级工具路由 `tool=&args=`
// ─────────────────────────────────────────────────────────────────────────────

test.describe('@stub L 请求级工具路由', () => {
  /** L-02 `get_config_def`：有 ⇒ 字段详情；无 ⇒ 结构化失败（不 500、不空对象、轮继续）。 */
  test('E2E-L02 get_config_def：命中返回字段集、不存在结构化失败且轮继续', async () => {
    const hit = await chatStream(sid('l02a'), `tool=get_config_def&args={"code":"CURRENCY"} ${uniq('L02')}`, {
      page: 'definitions'
    })
    expect(hit.status).toBe(200)
    const ok = toolResults(hit.frames).find((frame) => frame.name === 'get_config_def')
    expect(ok, '应有一个 get_config_def 的 tool_result').toBeTruthy()
    expect(ok?.ok).toBe(true)
    expect(String(ok?.result)).toContain('CURRENCY')
    expect(String(ok?.result)).toMatch(/字段|必填|类型/)
    expect(doneFrame(hit.frames).type).toBe('done')

    const missing = await chatStream(
      sid('l02b'),
      `tool=get_config_def&args={"code":"NO_SUCH_DEF_${Date.now()}"} ${uniq('L02X')}`,
      { page: 'definitions' }
    )
    const miss = toolResults(missing.frames).find((frame) => frame.name === 'get_config_def')
    expect(miss, '不存在的编码也应有结构化 tool_result').toBeTruthy()
    expect(miss?.ok).toBe(false)
    expect(String(miss?.code ?? '')).toMatch(/NOT_FOUND|RESOURCE/)
    expect(String(miss?.result ?? '').length).toBeGreaterThan(0)
    expect(framesOf(missing.frames, 'error')).toHaveLength(0)
    expect(doneFrame(missing.frames).type).toBe('done')
  })

  /** L-03 `list_tasks` + `resultView.table`：卡面表格来自视图通道（列 == 声明列）。 */
  test('E2E-L03 list_tasks：resultView.table 出现且列与行数自洽', async ({ page, request }) => {
    await createImportTaskAtStep(request, 'PRECHECK')
    await openPath(page, '/tasks')
    await expect(page.locator('footer')).toContainText('page:tasks', { timeout: 30_000 })

    await sendPrompt(page, `tool=list_tasks&args={} ${uniq('L03')}`, 90_000)
    // 工具卡默认折叠：先展开卡体（视图通道在卡体内），再断言表格真的可见
    await page.locator('[data-testid^="block-toggle-"], [data-testid^="ro-toggle-"]').last().click()
    await expect(page.locator('[data-testid="resultview-table"]').last()).toBeVisible({ timeout: 20_000 })

    const table = page.locator('[data-testid="resultview-table"]').last()
    const rows = table.locator('[data-testid="resultview-row"]')
    await expect(rows.first()).toBeVisible()
    expect(await rows.count()).toBeGreaterThan(0)
    // 列头不是空的（视图声明列被真的渲染出来）
    await expect(table.locator('th').first()).toBeVisible()
  })

  /** L-04 `get_workspace_state`：返回的任务态跟着请求的 taskId 走（不是缓存旧页）。 */
  test('E2E-L04 get_workspace_state：随任务上下文返回真实工作区态', async ({ request }) => {
    const taskId = await createImportTaskAtStep(request, 'PRECHECK')
    const first = await chatStream(
      sid('l04a'),
      `tool=get_workspace_state&args={"taskId":${taskId}} ${uniq('L04')}`,
      { page: 'import', taskType: 'IMPORT', step: 'UPLOAD', taskId }
    )
    const res = toolResults(first.frames).find((frame) => frame.name === 'get_workspace_state')
    expect(res?.ok).toBe(true)
    expect(String(res?.result)).toContain(String(taskId))

    // 换一个（不存在的）任务：不得继续返回上一个任务的缓存态
    const second = await chatStream(
      sid('l04b'),
      `tool=get_workspace_state&args={"taskId":99999999} ${uniq('L04X')}`,
      { page: 'import', taskType: 'IMPORT', step: 'UPLOAD' }
    )
    const res2 = toolResults(second.frames).find((frame) => frame.name === 'get_workspace_state')
    expect(String(res2?.result ?? '')).not.toContain(`任务 #${taskId}`)
  })

  /** L-05 `create_task`（WRITE）：真实落库、不经确认门、结果带任务 id。 */
  test('E2E-L05 create_task：真实建任务、无确认门、无 gate 帧', async ({ request }) => {
    const title = `E2E-L05-${Date.now()}`
    const run = await chatStream(
      sid('l05'),
      `tool=create_task&args={"type":"EXPORT","title":"${title}"} ${uniq('L05')}`,
      { page: 'tasks' }
    )
    const res = toolResults(run.frames).find((frame) => frame.name === 'create_task')
    expect(res?.ok).toBe(true)
    expect(String(res?.result)).toMatch(/任务|#\d+/)
    // WRITE ≠ DANGER：不进确认门（`suspended` 帧是"本批在途工具"的通用信封，
    // 后端工具也会有 —— 判据只能是 `confirm_request` 缺席 + 卡面无 hitl-gate）
    expect(framesOf(run.frames, 'confirm_request')).toHaveLength(0)

    const tasks = await listTasks(request)
    expect(tasks.some((task) => (task.title ?? '') === title)).toBe(true)
  })

  /** L-06 `start_import`（DANGER）：预检通过后挂起；批准 ⇒ 真实作业；零 gate 副作用记录。 */
  test('E2E-L06 start_import：预检通过 → 确认卡 → 批准 → 真实导入作业', async ({ page, request }) => {
    const taskId = await createImportTaskAtStep(request, 'IMPORT')
    await openPath(page, `/import?taskId=${taskId}`)
    await expect(page.locator('footer')).toContainText('task:IMPORT/IMPORT', { timeout: 30_000 })

    await sendAndWaitGate(page, `tool=start_import&args={"taskId":${taskId}} ${uniq('L06')}`)
    const gate = page.locator('[data-testid="hitl-gate"]')
    await expect(gate).toContainText('启动导入作业')
    await gate.getByRole('button', { name: '批准', exact: true }).click()

    await expect(page.locator('[data-testid="hitl-gate"]')).toHaveCount(0, { timeout: 60_000 })
    await waitRoundSettled(page, 120_000)

    const jobs = await taskJobs(request, taskId, 'IMPORT')
    expect(jobs).toHaveLength(1)
    const final = await waitJob(request, jobs[0]!.id)
    expect(['COMPLETED', 'FAILED']).toContain(final.status)
  })

  /**
   * L-07 `start_precheck`：预检报告 + 作业创建。
   *
   * 蓝本把这条写成「不进确认门（READ/WRITE 而非 DANGER）」——**与产品事实不符**：
   * `AiTools.startPrecheck` 是 `@ToolRisk(DANGER)`、且 `ToolPreflightService.CONFIRM_TOOLS` 含它，
   * 因此产品会先跑只读预检再挂确认门。本用例按**产品事实**断言（拒绝 ⇒ 零作业），
   * 蓝本偏差已记入交付报告。
   */
  test('E2E-L07 start_precheck：走确认门（产品事实）且拒绝后零预检作业', async ({ page, request }) => {
    const taskId = await createImportTaskAtStep(request, 'PRECHECK')
    await openPath(page, `/import?taskId=${taskId}`)
    await expect(page.locator('footer')).toContainText('task:IMPORT/PRECHECK', { timeout: 30_000 })

    await sendAndWaitGate(page, `tool=start_precheck&args={"taskId":${taskId}} ${uniq('L07')}`)
    const gate = page.locator('[data-testid="hitl-gate"]')
    await gate.getByRole('button', { name: '拒绝', exact: true }).click()
    await expect(page.locator('[data-testid="hitl-gate"]')).toHaveCount(0, { timeout: 40_000 })
    await waitRoundSettled(page, 90_000)

    expect(await taskJobs(request, taskId, 'PRECHECK')).toHaveLength(0)
  })

  /** L-08 `check_job_status`：终态如实（不虚报运行中），且与 `GET /api/jobs/{id}` 同值。 */
  test('E2E-L08 check_job_status：已终态不虚报运行中，与作业端点同值', async ({ request }) => {
    const taskId = await createExportedTask(request)
    // 该任务已跑完一次 EXPORT（再发会被 TASK_FINALIZED 409 拒）：直接拿那一条
    const jobs = await taskJobs(request, taskId, 'EXPORT')
    expect(jobs.length).toBeGreaterThan(0)
    const job = jobs[0] as JobView
    const final = await waitJob(request, job.id)
    expect(final.status).toBe('COMPLETED')

    const run = await chatStream(
      sid('l08'),
      `tool=check_job_status&args={"jobId":${job.id}} ${uniq('L08')}`,
      { taskType: 'EXPORT', step: 'EXPORT', taskId }
    )
    const res = toolResults(run.frames).find((frame) => frame.name === 'check_job_status')
    expect(res?.ok).toBe(true)
    const text = String(res?.result ?? '')
    expect(text).toContain('COMPLETED')
    // 已终态：不得同时出现"运行中"这类进行时口径
    expect(text).not.toMatch(/RUNNING|运行中/)

    const live = await api.get<JobView>(request, `/api/jobs/${job.id}`)
    expect(text).toContain(live.status)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S · STRM 流式稳定（§2.1）：S-10 / S-12
// ─────────────────────────────────────────────────────────────────────────────

test.describe('@stub S 流式稳定剩余条', () => {
  /**
   * S-10 步数到顶 25：`skipped/TOOL_STEP_LIMIT`，轮收尾到 done（不判整轮失败）。
   *
   * 形态修正（实测 2026-10-23）：**串行** 26 次不会走到跳过分支 —— 第 25 步之后产品追加
   * `STEP_LIMIT_WRAP_UP` 收尾指令并只要文本（第 26 次调用根本不发生，实测 tool_start=25）。
   * 跳过分支的触发面是**同一批多卡**：`multi-card=2` 让每条消息发 2 个 tool_calls，
   * 第 13 批时只剩 1 个步数额度 ⇒ 恰一个 `skipped/TOOL_STEP_LIMIT`、另一个照常执行。
   */
  test('E2E-S10 工具步数到顶：超额调用 skipped/TOOL_STEP_LIMIT，同批其余照常执行、轮收尾', async () => {
    const run = await chatStream(sid('s10'), `tool-loop=26 multi-card=2 ${uniq('S10')}`, { page: 'tasks' }, 240_000)
    const results = toolResults(run.frames)
    expect(results.length, '应留下 26 条工具结局（25 执行 + 1 跳过）').toBe(26)
    const skipped = results.filter((frame) => frame.outcome === 'skipped')
    expect(skipped).toHaveLength(1)
    expect(skipped[0]?.code).toBe('TOOL_STEP_LIMIT')
    expect(skipped[0]?.executed).toBe(false)
    // 前 25 条真的执行了
    expect(results.filter((frame) => frame.outcome === 'ok')).toHaveLength(25)
    expect(framesOf(run.frames, 'done')).toHaveLength(1)
  })

  /** S-12 迟到帧闸门：`[DONE]` 之后继续推 2 帧 —— 整帧丢弃，正文/卡状态/镜像零变化。 */
  test('E2E-S12 迟到帧：done 之后 2 帧被整帧丢弃，正文与镜像零变化、控制台零未捕获异常', async ({ page }) => {
    const errors: string[] = []
    page.on('pageerror', (error) => errors.push(error.message))
    await openPath(page, '/tasks')

    await sendPrompt(page, `turn-text late-frames=2 ${uniq('S12')}`, 90_000)
    await expect(lastCardState(page)).toHaveCount(0)

    const bubble = page.locator('[data-testid^="msg-bubble-"].w-full').last()
    const text = (await bubble.innerText()).trim()
    expect(text.length).toBeGreaterThan(0)
    // 迟到帧的文本（"done 之后不应入库"）绝不能进正文（整帧丢弃）
    expect(text).not.toMatch(/done 之后不应入库/)

    // 镜像里也不该多出帧带来的内容
    const mirror = await page.evaluate(() => {
      const key = Object.keys(sessionStorage).find((name) => name.startsWith('ai-mirror'))
      return key === undefined ? '' : String(sessionStorage.getItem(key) ?? '')
    })
    expect(mirror).not.toMatch(/done 之后不应入库/)
    expect(errors, `控制台未捕获异常：${errors.join(' | ')}`).toHaveLength(0)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// P · STP 手工停止（§2.3）：P-04 / P-10
// ─────────────────────────────────────────────────────────────────────────────

test.describe('@stub P 停止剩余条', () => {
  /**
   * P-04 停止在收尾处（正向对照）：4 卡全成功、正文收尾中停止 ⇒ 卡与正文全保留、
   * 无「已取消」误标、输入框立刻可用。桩 `slow-tail-frames` 只拉长**续轮帧间**，
   * 因此停止必然落在"工具已成功、正文还在流"的窗口里。
   */
  test('E2E-P04 收尾处停止：已完成卡不被误标取消，正文保留，输入即可用', async ({ page }) => {
    await openPath(page, '/tasks')
    // 一轮「工具轮 + 收尾文本轮」：`slow-tail-frames` 只拉长续轮帧间（首帧不延迟），
    // 因此工具卡先落成功、正文随后慢慢流 —— 停止必然落在收尾窗口。
    await page.locator('[data-testid="composer-input"]').fill(`tool=list_config_defs&args={} slow-tail-frames=600 ${uniq('P04')}`)
    await page.locator('[data-testid="send-btn"]').click()

    // 等工具卡进入终态（成功）且正文仍在流（停止钮在）
    await expect(page.locator('[data-testid^="card-status-"]').last()).toHaveAttribute('data-state', 'succeeded', {
      timeout: 60_000
    })
    const stop = page.locator('[data-testid="stop-btn"]')
    await expect(stop).toBeVisible({ timeout: 30_000 })
    await stop.click({ force: true })
    await expect(stop).toHaveCount(0, { timeout: 30_000 })

    // 卡仍是成功态（不得因为"停止"就把已完成的卡改写成已取消）
    await expect(page.locator('[data-testid^="card-status-"]').last()).toHaveAttribute('data-state', 'succeeded')
    // 正文保留（不是空白）
    const text = (await page.locator('[data-testid^="msg-bubble-"].w-full').last().innerText()).trim()
    expect(text.length).toBeGreaterThan(0)
    await expect(page.locator('[data-testid="composer-input"]')).toBeEnabled()
  })

  /** P-10 stopping 态可见：点一次停止后按钮进 disabled + 「停止中」文案。 */
  test('E2E-P10 stopping 态：点停止后按钮禁用且文案为停止中', async ({ page }) => {
    await openPath(page, '/tasks')
    await page.locator('[data-testid="composer-input"]').fill(`turn-text stall-frames=400 ${uniq('P10')}`)
    await page.locator('[data-testid="send-btn"]').click()

    const stop = page.locator('[data-testid="stop-btn"]')
    await expect(stop).toBeVisible({ timeout: 30_000 })
    await stop.click()
    // stopping 是瞬态：用 toPass 抓「禁用 + 停止中」这一帧
    await expect(async () => {
      const count = await stop.count()
      if (count === 0) return
      await expect(stop).toBeDisabled()
      await expect(stop).toContainText('停止中')
    }).toPass({ timeout: 10_000 })
    await expect(stop).toHaveCount(0, { timeout: 30_000 })
    // 停止后立刻恢复可输入（stopping 只是瞬态，不锁输入）
    await expect(page.locator('[data-testid="composer-input"]')).toBeEnabled()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// V · VIS 模型侧成功路径（epoch 注入）：桩的固定 epoch 必被围栏拒，
//     本域把页面真 epoch 写进提示词 ⇒ 工具入参被换成页面真值 ⇒ 成功路径可达。
// ─────────────────────────────────────────────────────────────────────────────

test.describe('@stub V epoch 注入成功路径', () => {
  test('E2E-V02·V06(epoch) 模型侧成功路径：注入页面真 epoch 后 ui_highlight 真实生效并落回执', async ({ page, request }) => {
    const taskId = await createImportTaskAtStep(request, 'UPLOAD')
    await openImportUpload(page, taskId)
    // 真快照（产品同一个 aiSnapshot）+ 页面真 epoch：快照闸与 epoch 围栏都取真值。
    // 最多两轮：`registerAiRegion` 会推 epoch，若恰在"快照→工具执行"之间发生区域注册，
    // 这一次会被围栏判为 epoch 不匹配（产品语义正确）—— 重取快照再来一次，不吞掉真实失败。
    let ok = false
    for (let attempt = 0; attempt < 2 && !ok; attempt += 1) {
      if (attempt > 0) {
        await page.locator('[data-testid="new-chat"]').click()
        await expect(page.locator('[data-testid="empty-state"]')).toBeVisible({ timeout: 20_000 })
      }
      const epoch = await snapshotAndEpoch(page)
      expect(epoch).toMatch(/^[a-z0-9]{6}-\d+$/)
      // 注入只改**既有** epoch 键（不新增键）：args 里先放占位串，提示词里的真 epoch 覆盖它
      await sendPrompt(
        page,
        `tool=ui_highlight&args={"ref":"import.upload.choose","note":"在这里上传","epoch":"a1b2c3-1"} epoch=${epoch} ${uniq('V02')}`,
        90_000
      )
      ok = (await page.locator('[data-testid^="card-status-"]').last().getAttribute('data-state')) === 'succeeded'
    }
    expect(ok, '注入页面真 epoch 后 ui_highlight 应落到成功态').toBe(true)
    // 回执原文（"已高亮「…」"）在卡体内；围栏拒执只会是 rejected + UI_* 机器码
    await expect(page.locator('[data-testid^="msg-bubble-"].w-full').last()).toContainText('已高亮', { timeout: 20_000 })
    // 高亮不改写屏外任何东西：写动词配额仍是 0（只读动作不占配额）
    const runtime = await uiRuntime(page)
    expect(runtime.writeCount).toBe(0)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// E · EMP 空态与错误卡族（§2.17）：E-11（失败卡半段；冲突半段见报告）
// ─────────────────────────────────────────────────────────────────────────────

test.describe('@stub E 错误卡族', () => {
  /**
   * E-11（失败卡半段）：工具失败 ⇒ 卡头「执行失败」+ 卡体内可读原因，轮继续到 done（不 500、不静默）。
   *
   * 蓝本写的是「失败卡出现 `tool-error`」——**与产品事实不符**：`utils/parts.ts` 的
   * `tool_result` 分支只对 `blocked` / `rejected` 写 `reason`（红底 `tool-error` 行只服务
   * "未执行"两类），普通业务失败把原文落在**输出区**（`part.result`）。故本条按产品事实断言。
   */
  test('E2E-E11 失败卡：卡头执行失败 + 卡体原因可读，轮继续到 done', async ({ page }) => {
    await openPath(page, '/definitions')
    await sendPrompt(page, `tool=get_config_def&args={"code":"NO_SUCH_${Date.now()}"} ${uniq('E11')}`, 90_000)

    await expect(page.locator('[data-testid^="card-status-"]').last()).toHaveAttribute('data-state', 'failed')
    await page.locator('[data-testid^="block-toggle-"], [data-testid^="ro-toggle-"]').last().click()
    const body = page.locator('[data-testid^="msg-bubble-"].w-full').last()
    await expect(body).toContainText(/不存在|未找到/)
    await waitRoundSettled(page, 30_000)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// I · I18N 双语（§2.20）：I-05（GF 错误码词表）
// ─────────────────────────────────────────────────────────────────────────────

test.describe('@stub I 双语', () => {
  /**
   * I-05 GF 错误码词表双语：en 下必填校验走 `ai.gf.errors.VALUE_REQUIRED_MISSING` 模板，
   * 不出现 zh 模板、也不把裸机器码（VALUE_REQUIRED_MISSING）当文案。
   */
  test('E2E-I05 GF 错误码走 en 词表：不落 zh 模板、不显裸码', async ({ page }) => {
    await openPath(page, '/tasks')
    // 切 en（真实按钮，不是改 localStorage）
    await page.locator('[data-test="locale-toggle"]').click()
    await expect(page.locator('[data-testid="composer-input"]')).toHaveAttribute('placeholder', /Message|message|输入/i)

    // 表单是要人填的挂起轮：不能等收敛（等不到），只确认被受理再等表单卡
    await sendPromptUnsettled(page, `scenario=FILTER ${uniq('I05')}`)
    await expect(page.locator('[data-testid="gf-fields"]')).toBeVisible({ timeout: 40_000 })
    // 必填字段留空直接提交 ⇒ 前端校验出词表文案
    await page.locator('[data-testid="gf-submit"]').click()
    // 字段级问题落在每字段的提示位 + `gf-live-region`（读屏播报同一份文案；
    // 卡级 `gf-error` 只承载归属不到字段的问题）。标签文本来自模型给的 schema，词表才是产品面。
    const live = page.locator('[data-testid="gf-live-region"]')
    await expect(live).toContainText(/is required/i, { timeout: 20_000 })
    const text = (await live.innerText()).trim()
    expect(text).not.toMatch(/为必填项/)
    expect(text).not.toContain('VALUE_REQUIRED_MISSING')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// U · UND 撤销族（§2.19）：U-06 停止后撤销点失效
// ─────────────────────────────────────────────────────────────────────────────

test.describe('@stub U 撤销族', () => {
  /** U-06 停止后撤销点失效：停止 ⇒ 撤销点清除、按钮消失（内存态按轮归属）。 */
  test('E2E-U06 停止后撤销点失效：ui-undo-row 消失、不再可撤', async ({ page, request }) => {
    const taskId = await createImportTaskAtStep(request, 'UPLOAD')
    await openImportUpload(page, taskId)
    const epoch = await snapshotAndEpoch(page)

    await sendPrompt(
      page,
      `tool=ui_fill&args={"ref":"import.selectDefs.search","value":"E2E","epoch":"a1b2c3-1"} epoch=${epoch} ${uniq('U06')}`,
      90_000
    )
    // mutate-local 的写动作会留下撤销点（且只挂在该卡上）
    await expect(page.locator('[data-testid="ui-undo-row"]')).toHaveCount(1, { timeout: 20_000 })

    // 起一轮慢流再停止 —— 停止 = 本轮被放弃，撤销点随之作废
    await page.locator('[data-testid="composer-input"]').fill(`turn-text stall-frames=500 ${uniq('U06b')}`)
    await page.locator('[data-testid="send-btn"]').click()
    const stop = page.locator('[data-testid="stop-btn"]')
    await expect(stop).toBeVisible({ timeout: 30_000 })
    await stop.click()
    await expect(stop).toHaveCount(0, { timeout: 30_000 })
    await expect(page.locator('[data-testid="ui-undo-row"]')).toHaveCount(0, { timeout: 20_000 })
    expect((await uiRuntime(page)).undo).toBeNull()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// R · RLD 刷新恢复（§2.2）：R-05 / R-18（重挂回到原气泡 / 重挂横幅生命周期）
// ─────────────────────────────────────────────────────────────────────────────

test.describe('@stub R 刷新恢复剩余条', () => {
  /** 起一轮慢流 → 正文已出 → 真 reload → 等重挂补齐。返回 runId 与气泡基线。 */
  async function reloadMidStream(page: Page, request: APIRequestContext, tag: string) {
    await openPath(page, '/tasks')
    await page.locator('[data-testid="composer-input"]').fill(`turn-text stall-frames=1200 ${uniq(tag)}`)
    await page.locator('[data-testid="send-btn"]').click()
    const bubble = page.locator('[data-testid^="msg-bubble-"].w-full').last()
    await expect.poll(async () => (await bubble.innerText()).trim().length, { timeout: 30_000 }).toBeGreaterThan(10)
    const sessionId = await pageSessionId(page)
    const current = await api.get<{ found: boolean; runId?: string }>(
      request,
      `/api/ai/runs/current?sessionId=${encodeURIComponent(sessionId)}`
    )
    expect(current.found).toBe(true)
    const bubblesBefore = await page.locator('[data-testid^="msg-bubble-"]').count()
    return { runId: String(current.runId ?? ''), bubblesBefore }
  }

  /**
   * R-05 一键重挂回到原气泡：刷新后自动重挂补齐正文 ——
   * 气泡数不变（不新建占位气泡）、正文**逐字**等于桩的确定性文本（无重复段、无丢段）、
   * 不以「正在重挂」类占位前缀开头。
   */
  test('E2E-R05 刷新后重挂回原气泡：气泡数不变、正文逐字补齐、无占位前缀', async ({ page, request }) => {
    const { runId, bubblesBefore } = await reloadMidStream(page, request, 'R05')

    await page.reload({ waitUntil: 'domcontentloaded' })
    await expect(page.locator('[data-testid="composer-input"]')).toBeVisible({ timeout: 30_000 })

    const bubble = page.locator('[data-testid^="msg-bubble-"].w-full').last()
    await expect(bubble).toHaveText(TURN_TEXT, { timeout: 90_000 })
    // 气泡数不变：重挂复用原气泡，不追加占位气泡
    expect(await page.locator('[data-testid^="msg-bubble-"]').count()).toBe(bubblesBefore)
    await waitRoundSettled(page, 90_000)

    // 归档侧同源：该 run 的归档帧里有且只有一个 done，末帧文本与屏上一致
    const archive = await request.get(
      `${API_BASE}/api/ai/events/${encodeURIComponent(runId)}?lastSeq=0`,
      aiRequestOptions('/api/ai/events')
    )
    const frames = (await archive.text())
      .split(/\r?\n/)
      .filter((line) => line.startsWith('data:'))
      .map((line) => line.slice(5).trim())
      .filter((payload) => payload.startsWith('{'))
      .map((payload) => JSON.parse(payload) as { type?: string })
    expect(frames.filter((frame) => frame.type === 'done')).toHaveLength(1)
  })

  /**
   * R-18 重挂横幅生命周期 —— **本轮不承载**：`busyConflict`（409 SESSION_BUSY）提示的
   * "onopen 即消失"需要一个可稳定复现的抢发竞态；本机恢复请求 40ms 内就完成，
   * 抢发几乎不可能撞上，重跑三次均未出现提示（不可判定）。同一竞态面已由批② R-04
   * 用"延迟恢复请求 2.5s"确定性构造并断言 409 载荷，本条的**新增面**（横幅消失时机）
   * 缺稳定触发面，故不写假绿用例。
   */
  test.skip('E2E-R18 重挂横幅生命周期（缺稳定抢发面，见报告）', async () => {
    /* 见上方说明 */
  })
})
