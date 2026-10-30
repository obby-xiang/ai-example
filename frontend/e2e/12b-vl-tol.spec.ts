/**
 * 真机 E2E · 批③ L 域（工具全量面）—— 12b
 *
 * 蓝本：`docs/research/设计-真机E2E用例全集-2026-10-23.md` §2.24（L-01…L-18）。
 * 本文件承接可在**当前桩与产品面**上真机承载的 11 条；L-02…L-08 不可承载，
 * 理由（缺桩路由 / 无直接调用面）见交付报告。
 *
 * 两条执行路径同 12a：
 *   - 后端工具：真模型路径（桩下发 → 后端执行 → 卡面）与只读披露端点 `GET /api/ai/tools`；
 *   - 前端工具：真模块直调 `executeFrontendTool`（真 DOM、真下载、真落库）。
 */
import { expect, test, type APIRequestContext, type Page } from '@playwright/test'
import { api, createExportedTask, createImportTaskAtUpload, createUploadedImportTask, sendMessage, waitRoundSettled } from './support/helpers'
import {
  ensureVlStack,
  stopVlStack,
  VL_API,
  vlCall,
  vlOpenApp,
  vlRuntime,
  vlSnapshotWithRegion,
  vlStableFooter,
  vlWaitRegions
} from './support/helpers-vl'

function mark(tag: string): string {
  return `${tag} ${Date.now()}-${Math.floor(Math.random() * 1000)}`
}

interface ToolView {
  name: string
  displayName: string
  titleKey: string
  lang: string
  riskLevel: string
  channel: string
  scopePatterns: string[]
}

async function toolsFor(request: APIRequestContext, query: string): Promise<{ toolNames: string[]; tools: ToolView[] }> {
  const res = await request.get(`${VL_API}/api/ai/tools?${query}`)
  expect(res.ok()).toBe(true)
  const body = (await res.json()) as { data: { toolNames: string[]; tools: ToolView[] } }
  return body.data
}


/** 前端工具回执（`executeFrontendTool` 的返回面）。 */
interface ToolOutcome {
  outcome?: string
  availability?: string
  result?: string
  code?: string
}

/**
 * 轮询一次前端工具调用，直到回执满足**就绪判据**（L-109）。
 *
 * 这页面上有三层"晚于断言"的注册：SpreadJS 引擎挂载（~1s）、页面的 `registerPageHandler`
 * 处理器、以及区域注册表（页面首帧后还可能被 Vite 重载整体重建）。于是同一段代码
 * 既可能打到"适配器还没就绪"（`0 列` / `当前页面没有可操作的表格`），也可能打到
 * "页面处理器还没注册"（回执变成后端的 ui_event 错误文案）。旧写法用 `outcome === 'ok'`
 * 当就绪判据 —— 而 `已匹配 0/4 列` 恰恰是 outcome=ok 的错误数据面，于是假红。
 * 这里让调用方给出**正向判据**（真拿到期望内容才算就绪），轮询有界，超时返回末次回执由断言报错。
 */
async function callUntil(
  page: import('@playwright/test').Page,
  name: string,
  args: Record<string, unknown>,
  ready: (view: ToolOutcome) => boolean,
  timeoutMs = 30_000
): Promise<ToolOutcome> {
  const deadline = Date.now() + timeoutMs
  let last: ToolOutcome = {}
  while (Date.now() < deadline) {
    last = await vlCall(page, name, args, { snapshot: false })
    if (ready(last)) return last
    await page.waitForTimeout(600)
  }
  return last
}

/** 造一个停在「查询配置」步的导出任务。 */
async function createExportTaskAtQueryCond(request: APIRequestContext, defCodes: string[] = ['CURRENCY']): Promise<number> {
  const task = await api.post<{ id: number }>(request, '/api/tasks', { type: 'EXPORT', title: `E2E-VL-EXP-${Date.now()}` })
  await api.post(request, `/api/tasks/${task.id}/select-defs`, { defCodes })
  await api.put(request, `/api/tasks/${task.id}/step`, { step: 'QUERY_COND' })
  return task.id
}

/** 读任务条目里**业务库**的 `conditionJson` 原文（任务里没有该条目 ⇒ 空串）。 */
async function conditionJsonOf(request: APIRequestContext, taskId: number, defCode: string): Promise<string> {
  const task = await api.get<{ items?: Array<{ defCode: string; conditionJson: string | null }> }>(request, `/api/tasks/${taskId}`)
  return task.items?.find((entry) => entry.defCode === defCode)?.conditionJson ?? ''
}

interface ConditionWrites {
  /** 已发出的写请求 URL（按发出序）。 */
  requests: string[]
  /** 对应响应码（按到达序）。 */
  statuses: number[]
}

/**
 * 侦听页面发往「任务条目查询条件」的写请求（`PUT /api/tasks/{id}/items/{defCode}/condition`）。
 *
 * 草案口径（2026-10-30）：`set_condition` 只写页面草案、不碰业务库，唯一提交点是离开
 * 查询条件步的 `persistStepData`（`confirm_step` / 开始导出）。于是"写了几次、写在哪一拍"
 * 本身就是判据：草案阶段必须 0 次（提前写），推进时有且只有 1 次（重复写也是缺陷）。
 * 事后读库只能证明"最终值对不对"，分辨不出这两类回归，故用页面真实写请求作证据面。
 */
function watchConditionWrites(page: Page, taskId: number, defCode: string): ConditionWrites {
  const target = `/api/tasks/${taskId}/items/${defCode}/condition`
  const matched = (url: string): boolean => {
    try {
      return new URL(url).pathname.endsWith(target)
    } catch {
      return false
    }
  }
  const writes: ConditionWrites = { requests: [], statuses: [] }
  page.on('request', (req) => {
    if (req.method() === 'PUT' && matched(req.url())) writes.requests.push(req.url())
  })
  page.on('response', (res) => {
    if (res.request().method() === 'PUT' && matched(res.url())) writes.statuses.push(res.status())
  })
  return writes
}

test.describe('@stub L 工具全量面（12b）', () => {
  test.beforeAll(async () => {
    await ensureVlStack()
  })

  test.afterAll(async () => {
    await stopVlStack()
  })

  test('E2E-L01 list_config_defs：真回合列出定义、卡面可读、披露口径为全页可用', async ({ page, request }) => {
    // 披露面：list_config_defs 的 scope 是 `*` ⇒ 任何页面都披露（不被 page 收窄）
    for (const query of ['page=tasks', 'page=definitions', 'page=export']) {
      const data = await toolsFor(request, query)
      expect(data.toolNames).toContain('list_config_defs')
      const meta = data.tools.find((tool) => tool.name === 'list_config_defs')
      expect(meta?.riskLevel).toBe('READ')
      expect(meta?.channel).toBe('BACKEND')
      expect(meta?.scopePatterns).toContain('*')
    }

    // 真回合：桩下发 list_config_defs → 后端真执行 → 卡面可读（不依赖 resultView 也完整）
    await vlOpenApp(page, '/definitions')
    await sendMessage(page, `配置定义 ${mark('L01')}`)
    const card = page.locator('[data-testid^="tool-card-"]').last()
    await expect(card).toBeVisible({ timeout: 30_000 })
    await expect(card.locator('[data-testid^="card-status-"]')).toHaveAttribute('data-state', 'succeeded', { timeout: 20_000 })
    const output = card.locator('[data-testid="tool-output-text"]')
    await expect(output).toContainText('CURRENCY')
    await expect(output).toContainText('DOC_TYPE')
    await waitRoundSettled(page, 60_000)
  })

  test('E2E-L09 inspect_columns：真表格的列映射诊断（只建议不改文件）', async ({ page, request }) => {
    const taskId = await createUploadedImportTask(request, 'CURRENCY')
    await vlOpenApp(page, `/import?taskId=${taskId}`)
    await vlWaitRegions(page, 'import.grid')
    // 页脚在挂载后还会再写几笔（任务详情回填 / 上传件解析推 dataVersion）：基线只取"已静止"的终态
    const footerBefore = await vlStableFooter(page, 'task:IMPORT/')

    // 引擎挂载 + 上传件解析都是异步的：工具"能跑"不等于"表里已有列"（实测读到 0 列）。
    // 就绪判据 = 真读到 4 列表头，而不是 outcome==='ok'。
    const outcome = await callUntil(page, 'inspect_columns', { defCode: 'CURRENCY' },
      (view) => view.outcome === 'ok' && /已匹配\s*4\/4\s*列/.test(String(view.result ?? '')))
    expect(outcome.outcome).toBe('ok')
    const text = String(outcome.result ?? '')
    // 真表头（上传件 = CURRENCY 导出模板）与字段定义对账：四列全命中
    expect(text).toMatch(/已匹配\s*4\/4\s*列/)
    expect(text).not.toContain('无法提供表格适配器')
    expect(text.length).toBeGreaterThan(10)
    // 不存在的编码：结构化失败（不静默给一份空诊断）
    const ghost = await vlCall(page, 'inspect_columns', { defCode: 'NOT_A_DEF' }, { snapshot: false })
    expect(String(ghost.outcome)).not.toBe('ok')
    expect(String(ghost.result ?? '')).toContain('NOT_A_DEF')
    // 纯读：数据版本未变、无写动词计数（诊断只建议不改文件）
    expect(await vlStableFooter(page, 'task:IMPORT/')).toBe(footerBefore)
    expect((await vlRuntime(page)).writeCount).toBe(0)
  })

  test('E2E-L10 review_cells：本地校验摘要带单元格地址、条数受上限约束', async ({ page, request }) => {
    const taskId = await createUploadedImportTask(request, 'CURRENCY')
    await vlOpenApp(page, `/import?taskId=${taskId}`)
    await vlWaitRegions(page, 'import.grid')

    const outcome = await callUntil(page, 'review_cells', { defCode: 'CURRENCY' },
      (view) => view.outcome === 'ok' && /共\s*\d+\s*处/.test(String(view.result ?? '')))
    expect(outcome.outcome).toBe('ok')
    const text = String(outcome.result ?? '')
    // 恒回"共 N 处"（含 0 处），明细不超过 20 条上限
    expect(text).toMatch(/共\s*\d+\s*处/)
    const details = text.split(/\r?\n/).filter((line) => /^[A-Z]+\d+$|单元格/.test(line.trim()))
    expect(details.length).toBeLessThanOrEqual(20)
    // limit 上界：1..20 之外一律拒执（不静默放大明细）。
    // 就绪判据 = 真拿到参数校验文案（"当前页面没有可操作的表格"是适配器未就绪，不是本断言的面）。
    const capped = await callUntil(page, 'review_cells', { defCode: 'CURRENCY', limit: 999 },
      (view) => String(view.result ?? '').includes('limit 只能是 1 到 20 的整数'))
    expect(String(capped.outcome)).not.toBe('ok')
    expect(String(capped.result ?? '')).toContain('limit 只能是 1 到 20 的整数')
  })

  test('E2E-L11 select_definitions：前端动作真选中，回执与选中态一致', async ({ page, request }) => {
    const taskId = await createImportTaskAtUpload(request)
    await vlOpenApp(page, `/import?taskId=${taskId}`)
    await vlWaitRegions(page, 'import.selectDefs')
    const target = page.locator('[data-ai-ref="import.selectDefs.APPROVE_ROLE"]')
    await expect(target).toHaveAttribute('aria-checked', 'false')

    const outcome = await vlCall(page, 'select_definitions', { codes: ['APPROVE_ROLE'], task: 'import' })
    expect(outcome.outcome).toBe('ok')
    expect(String(outcome.result)).toContain('APPROVE_ROLE')
    // 页面选中态真的变了（不是只回一句话）
    await expect(target).toHaveAttribute('aria-checked', 'true', { timeout: 15_000 })

    // 非法编码如实失败（不谎报已选）。旧断言只看回执"含 NOT_A_DEF"——旧实现回的是
    // 「已选择配置项：APPROVE_ROLE、NOT_A_DEF」（成功语义），这个弱断言照样通过。
    // 真判据：结局必须是 failed、回执点名无效码与可用码、**页面选中态未变**（没有"部分成功"）。
    const bad = await vlCall(page, 'select_definitions', { codes: ['NOT_A_DEF'], task: 'import' })
    expect(String(bad.outcome)).toBe('failed')
    const badText = String(bad.result ?? '')
    expect(badText).toContain('NOT_A_DEF')
    expect(badText).toContain('可用编码')
    await expect(target).toHaveAttribute('aria-checked', 'true')
    await expect(page.locator('[data-ai-ref$=".NOT_A_DEF"]')).toHaveCount(0)
  })

  test('E2E-L12 set_condition：只形成页面草案，下一步才提交一次', async ({ page, request }) => {
    const taskId = await createExportTaskAtQueryCond(request)
    const writes = watchConditionWrites(page, taskId, 'CURRENCY')
    await vlOpenApp(page, `/export?taskId=${taskId}`)
    // L-109：区域注册可能晚于断言（首帧后页面还会被重载/重挂）⇒ 轮询到区域真出现在快照里
    const before = await vlSnapshotWithRegion(page, 'export.cond')
    expect(before.regionIds).toContain('export.cond')
    expect(await conditionJsonOf(request, taskId, 'CURRENCY')).toBe('')

    const outcome = await callUntil(page, 'set_condition', {
      defCode: 'CURRENCY',
      conditions: { fields: [{ fieldCode: 'code', operator: 'EQ', value: 'USD' }] }
    }, (view) => view.outcome === 'ok' && String(view.result ?? '').includes('CURRENCY'))
    expect(outcome.outcome).toBe('ok')
    expect(String(outcome.result)).toContain('CURRENCY')

    // 草案阶段（2026-10-30 提交边界）：设置条件只写页面草案，业务库仍是旧值 —— 本任务从无 ⇒ 空。
    // 负断言留一个有界窗口：提前写入若发生，这一拍之内必然已经发到后端（否则读库只是抢在 HTTP 之前）。
    await page.waitForTimeout(500)
    expect(writes.requests).toHaveLength(0)
    expect(await conditionJsonOf(request, taskId, 'CURRENCY')).toBe('')

    // 非法条件（缺 defCode）结构化失败。R6：除文本外必须断言 outcome=failed ——
    // 旧页面 handler 缺 defCode 时回**字符串**，`interpretHandlerOutcome` 会把它判成 ok（假成功）。
    const bad = await callUntil(page, 'set_condition', { conditions: { fields: [] } },
      (view) => String(view.outcome) === 'failed' && String(view.result ?? '').includes('缺少 defCode'))
    expect(String(bad.outcome)).toBe('failed')
    expect(String(bad.result ?? '')).toContain('缺少 defCode')

    // 既有 confirm_step（「下一步」）是唯一提交点：草案在这一拍一次性落库。
    // 次数判据比"最终值对不对"更细 —— 提前写（草案阶段 1 次）与重复写（推进后 2 次）都是缺陷。
    const advanced = await callUntil(page, 'confirm_step', { step: 'EXPORT' },
      (view) => view.outcome === 'ok' && String(view.result ?? '').includes('已推进'))
    expect(advanced.outcome).toBe('ok')
    await expect.poll(() => writes.requests.length, { timeout: 15_000 }).toBe(1)
    await expect.poll(() => writes.statuses.length, { timeout: 15_000 }).toBe(1)
    expect(writes.statuses[0]).toBeGreaterThanOrEqual(200)
    expect(writes.statuses[0]).toBeLessThan(300)

    // 落库：后端 conditionJson 是导出作业的唯一条件来源，且落的就是草案那一份（不多不少一条）
    const raw = await conditionJsonOf(request, taskId, 'CURRENCY')
    expect(raw).toContain('USD')
    const parsed = JSON.parse(raw) as { fields?: Array<{ fieldCode: string; operator: string; value: unknown }> }
    expect(parsed.fields).toHaveLength(1)
    expect(parsed.fields?.[0]).toMatchObject({ fieldCode: 'code', operator: 'EQ', value: 'USD' })
  })

  test('E2E-L12b set_condition（多值 IN）：confirm_step 不得把条件换义（数组仍是数组）', async ({ page, request }) => {
    const taskId = await createExportTaskAtQueryCond(request)
    await vlOpenApp(page, `/export?taskId=${taskId}`)
    await vlSnapshotWithRegion(page, 'export.cond')

    const set = await callUntil(page, 'set_condition', {
      defCode: 'CURRENCY',
      conditions: { fields: [{ fieldCode: 'code', operator: 'IN', value: ['CNY', 'USD'] }] }
    }, (view) => view.outcome === 'ok' && String(view.result ?? '').includes('CURRENCY'))
    expect(set.outcome).toBe('ok')

    // 真机确认推进（confirm_step 会走 persistStepData）：草案对多值 IN 必须无损
    const advanced = await callUntil(page, 'confirm_step', { step: 'EXPORT' },
      (view) => view.outcome === 'ok' && String(view.result ?? '').includes('已推进'))
    expect(advanced.outcome).toBe('ok')

    const task = await api.get<{ items: Array<{ defCode: string; conditionJson: string | null }> }>(request, `/api/tasks/${taskId}`)
    const item = task.items.find((entry) => entry.defCode === 'CURRENCY')
    const parsed = JSON.parse(item?.conditionJson ?? '{}') as {
      fields?: Array<{ fieldCode: string; operator: string; value: unknown }>
    }
    const field = parsed.fields?.find((entry) => entry.fieldCode === 'code')
    expect(field?.operator).toBe('IN')
    // 修复前：confirm_step 把 IN 数组压成 "CNY,USD" 字符串（后端按整串比较 ⇒ 命中 0 行）
    expect(Array.isArray(field?.value)).toBe(true)
    expect(field?.value).toEqual(['CNY', 'USD'])
  })

  test('E2E-L13 confirm_step：步骤真实推进、非法步骤被拒（不越级）', async ({ page, request }) => {
    const taskId = await createExportTaskAtQueryCond(request)
    await vlOpenApp(page, `/export?taskId=${taskId}`)
    // L-109：区域注册晚于断言 ⇒ 轮询到区域真出现在快照里（页面处理器同批注册）
    await vlSnapshotWithRegion(page, 'export.cond')

    // 越级/非法目标：明确拒绝并给出可选值。
    // 就绪判据 = 真拿到**页面侧**的拒绝文案；打到后端 ui_event（"ui_event 处理失败：…步骤序列内"）
    // 说明页面处理器还没注册好，属就绪问题而非产品回归。
    const illegal = await callUntil(page, 'confirm_step', { step: 'NOPE' },
      (view) => String(view.result ?? '').includes('不在导出向导范围内'))
    expect(String(illegal.result ?? '')).toContain('不在导出向导范围内')
    // 界面仍停在原步骤
    await expect(page.locator('footer')).toContainText('task:EXPORT/QUERY_COND', { timeout: 15_000 })

    // 合法推进：QUERY_COND → EXPORT
    const advanced = await callUntil(page, 'confirm_step', { step: 'EXPORT' },
      (view) => String(view.result ?? '').includes('导出执行'))
    expect(String(advanced.result ?? '')).toContain('导出执行')
    await expect(page.locator('footer')).toContainText('task:EXPORT/EXPORT', { timeout: 30_000 })
    // 落库：任务当前步骤同步
    const task = await api.get<{ currentStep: string }>(request, `/api/tasks/${taskId}`)
    expect(task.currentStep).toBe('EXPORT')
  })

  test('E2E-L14 open_export_file_editor：真打开编辑器并载入该文件；无产物时结构化失败', async ({ page, request }) => {
    const taskId = await createExportedTask(request)
    await vlOpenApp(page, `/export?taskId=${taskId}`)
    await vlSnapshotWithRegion(page, 'export.selectDefs')

    // 就绪判据 = 真打开并载入该文件（文件列表/编辑器容器都晚于页面挂载）
    const opened = await callUntil(page, 'open_export_file_editor', { taskId, defCode: 'CURRENCY' },
      (view) => view.outcome === 'ok' && String(view.result ?? '').includes('CURRENCY'))
    expect(opened.outcome).toBe('ok')
    expect(String(opened.result)).toContain('CURRENCY')
    await expect(page.locator('footer')).toContainText('task:EXPORT/EXPORT', { timeout: 30_000 })
    // 编辑器容器真的在屏（不是只回一句"已打开"）
    await expect(page.locator('[data-testid="spread-grid"], .spread-host, canvas').first()).toBeVisible({ timeout: 30_000 })

    // 无产物编码：结构化失败，不空白页（R7：断言收紧为 failed，不再只要求"非 ok"）
    const missing = await callUntil(page, 'open_export_file_editor', { taskId, defCode: 'DOC_TYPE' },
      (view) => String(view.outcome) === 'failed' && String(view.result ?? '').includes('DOC_TYPE'))
    expect(String(missing.outcome)).toBe('failed')
    expect(String(missing.result)).toContain('DOC_TYPE')
    await expect(page.locator('[data-testid="spread-grid"], .spread-host, canvas').first()).toBeVisible({ timeout: 15_000 })

    // 缺 defCode：参数错误必须结构化失败（R7 起 handler 缺参也回 {ok:false}；
    // 工具层 REQUIRED_PARAMS 先行时同样 failed），编辑器不被切换
    const noDef = await callUntil(page, 'open_export_file_editor', { taskId },
      (view) => String(view.outcome) === 'failed' && String(view.result ?? '').includes('defCode'))
    expect(String(noDef.outcome)).toBe('failed')
    expect(String(noDef.result)).toContain('defCode')
  })

  test('E2E-L15 download_export_files：真下载事件 + 文件名与产物一致', async ({ page, request }) => {
    const taskId = await createExportedTask(request)
    await vlOpenApp(page, `/export?taskId=${taskId}`)
    await vlSnapshotWithRegion(page, 'export.selectDefs')

    // 先把该文件的在线编辑器打开（引擎就绪后再下载，落盘内容是"在线编辑后的当前表格"）
    const opened = await callUntil(page, 'open_export_file_editor', { taskId, defCode: 'CURRENCY' },
      (view) => view.outcome === 'ok' && String(view.result ?? '').includes('CURRENCY'))
    expect(opened.outcome).toBe('ok')
    await expect(page.locator('[data-testid="spread-grid"], .spread-host, canvas').first()).toBeVisible({ timeout: 30_000 })

    const downloadPromise = page.waitForEvent('download', { timeout: 90_000 })
    const outcome = await callUntil(page, 'download_export_files', { taskId, defCodes: ['CURRENCY'] },
      (view) => String(view.result ?? '').includes('CURRENCY'))
    const download = await downloadPromise
    const name = download.suggestedFilename()
    expect(name).toMatch(/CURRENCY/)
    expect(name).toMatch(/\.(xlsx|zip)$/)
    expect(String(outcome.result ?? '')).toContain('CURRENCY')
  })

  test('E2E-L15b 定义列表不可用（0 字段）：download_export_files 必须确定性失败且零下载', async ({ page, request }) => {
    const taskId = await createExportedTask(request)
    // 真机把定义列表打成空（后端仍在，但页面拿不到字段）——0 字段不得按成功载入，更不得
    // 产出空 xlsx 交给浏览器。路由必须在打开页面前挂上（页面挂载即拉定义）。
    await page.route('**/api/definitions*', (route) => route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ success: true, data: [] })
    }))
    await vlOpenApp(page, `/export?taskId=${taskId}`)
    await vlSnapshotWithRegion(page, 'export.selectDefs')

    const outcome = await callUntil(page, 'download_export_files', { taskId, defCodes: ['CURRENCY'] },
      (view) => view.outcome === 'failed' && String(view.result ?? '').includes('字段'))
    expect(String(outcome.outcome)).toBe('failed')
    expect(String(outcome.result ?? '')).toContain('字段')
    expect(String(outcome.result ?? '')).toContain('未触发下载')
    // 浏览器侧一个下载事件都不该发生（空文件比"没有文件"更糟：用户会当成导出结果）
    await expect(page.waitForEvent('download', { timeout: 2_000 })).rejects.toThrow()
  })

  test('E2E-L16 越 scope 的工具不披露也不执行：READ 同样受 scope 约束', async ({ page, request }) => {
    // ① 披露面：任务级 READ 工具（check_job_status / 表格工具）只在对应上下文披露
    const pageTasks = await toolsFor(request, 'page=tasks')
    expect(pageTasks.toolNames).not.toContain('check_job_status')
    expect(pageTasks.toolNames).not.toContain('inspect_columns')
    const taskCtx = await toolsFor(request, 'page=export&taskType=EXPORT&step=QUERY_COND')
    expect(taskCtx.toolNames).toContain('check_job_status')
    expect(taskCtx.toolNames).not.toContain('inspect_columns')
    const exportCtx = await toolsFor(request, 'page=export&taskType=EXPORT&step=EXPORT')
    expect(exportCtx.toolNames).toContain('inspect_columns')
    expect(exportCtx.toolNames).toContain('download_export_files')

    // ② 执行面：不在允许页面时前端工具直接拒执（不静默返回全量）
    await vlOpenApp(page, '/tasks')
    const denied = await vlCall(page, 'set_condition', { defCode: 'CURRENCY', conditions: { fields: [] } })
    expect(denied.outcome).toBe('failed')
    expect(denied.availability).toBe('unavailable')
    expect(String(denied.result)).toContain('只支持：export')
  })

  test('E2E-L17 工具定义 YAML 覆盖生效：标题走覆盖、未覆盖工具保持原样', async ({ request }) => {
    const data = await toolsFor(request, 'page=tasks')
    const listTasks = data.tools.find((tool) => tool.name === 'list_tasks')
    // YAML（backend/src/main/resources/ai/tools.yaml）里 list_tasks 的 title 是「列出任务」
    expect(listTasks?.displayName).toBe('列出任务')
    expect(listTasks?.titleKey).toBe('tool.list_tasks.title')
    expect(listTasks?.lang).toBe('zh')
    const createTask = data.tools.find((tool) => tool.name === 'create_task')
    expect(createTask?.displayName).toBe('创建任务')
    // 未覆盖的工具（ui_* 不在 YAML 内）保持"显示名 = 工具名"
    const snapshot = data.tools.find((tool) => tool.name === 'ui_snapshot')
    expect(snapshot?.displayName).toBe('ui_snapshot')
    expect(snapshot?.titleKey).toBe('tool.ui_snapshot.title')
    // 覆盖只改 UI 面，不改风险/通道
    expect(listTasks?.riskLevel).toBe('READ')
    expect(listTasks?.channel).toBe('BACKEND')
  })

  test('E2E-L18 未知工具名 ⇒ 结构化对账：明确不可用、不抛异常、无幽灵卡', async ({ page }) => {
    await vlOpenApp(page, '/tasks')
    const outcome = await vlCall(page, 'definitely_not_a_tool_xyz', {})
    expect(outcome.outcome).toBe('failed')
    expect(outcome.availability).toBe('unavailable')
    expect(String(outcome.result)).toContain('未知前端工具')
    expect(String(outcome.result)).toContain('definitely_not_a_tool_xyz')
    // 不产生幽灵卡：页面上没有新增工具卡
    await expect(page.locator('[data-testid^="tool-card-"]')).toHaveCount(0)
  })
})
