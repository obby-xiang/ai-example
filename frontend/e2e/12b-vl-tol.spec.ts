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
import { expect, test, type APIRequestContext } from '@playwright/test'
import { api, createExportedTask, createImportTaskAtUpload, createUploadedImportTask, sendMessage, waitRoundSettled } from './support/helpers'
import {
  ensureVlStack,
  stopVlStack,
  VL_API,
  vlCall,
  vlOpenApp,
  vlRuntime,
  vlSnapshot,
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


/** 表格引擎挂载是异步的（SpreadJS 初始化 ~1s）：轮询到工具真能取到适配器再断言。 */
async function callUntilReady(
  page: import('@playwright/test').Page,
  name: string,
  args: Record<string, unknown>,
  timeoutMs = 30_000
): Promise<{ outcome?: string; availability?: string; result?: string; code?: string }> {
  const deadline = Date.now() + timeoutMs
  let last: { outcome?: string; availability?: string; result?: string; code?: string } = {}
  while (Date.now() < deadline) {
    last = await vlCall(page, name, args, { snapshot: false })
    if (last.outcome === 'ok') return last
    await page.waitForTimeout(1_200)
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
    const footerBefore = await page.locator('footer').innerText()

    const outcome = await callUntilReady(page, 'inspect_columns', { defCode: 'CURRENCY' })
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
    expect(await page.locator('footer').innerText()).toBe(footerBefore)
    expect((await vlRuntime(page)).writeCount).toBe(0)
  })

  test('E2E-L10 review_cells：本地校验摘要带单元格地址、条数受上限约束', async ({ page, request }) => {
    const taskId = await createUploadedImportTask(request, 'CURRENCY')
    await vlOpenApp(page, `/import?taskId=${taskId}`)
    await vlWaitRegions(page, 'import.grid')

    const outcome = await callUntilReady(page, 'review_cells', { defCode: 'CURRENCY' })
    expect(outcome.outcome).toBe('ok')
    const text = String(outcome.result ?? '')
    // 恒回"共 N 处"（含 0 处），明细不超过 20 条上限
    expect(text).toMatch(/共\s*\d+\s*处/)
    const details = text.split(/\r?\n/).filter((line) => /^[A-Z]+\d+$|单元格/.test(line.trim()))
    expect(details.length).toBeLessThanOrEqual(20)
    // limit 上界：1..20 之外一律拒执（不静默放大明细）
    const capped = await callUntilReady(page, 'review_cells', { defCode: 'CURRENCY', limit: 999 })
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

    // 非法编码如实失败（不谎报已选）
    const bad = await vlCall(page, 'select_definitions', { codes: ['NOT_A_DEF'], task: 'import' })
    const badText = String(bad.result ?? '')
    expect(badText).toContain('NOT_A_DEF')
  })

  test('E2E-L12 set_condition：筛选控件值真实变化且落库', async ({ page, request }) => {
    const taskId = await createExportTaskAtQueryCond(request)
    await vlOpenApp(page, `/export?taskId=${taskId}`)
    await vlWaitRegions(page, 'export.cond')
    const before = await vlSnapshot(page)
    expect(before.regionIds).toContain('export.cond')

    const outcome = await vlCall(page, 'set_condition', {
      defCode: 'CURRENCY',
      conditions: { fields: [{ fieldCode: 'code', operator: 'EQ', value: 'USD' }] }
    })
    expect(outcome.outcome).toBe('ok')
    expect(String(outcome.result)).toContain('CURRENCY')

    // 落库：后端 conditionJson 是导出作业的唯一条件来源
    const task = await api.get<{ items: Array<{ defCode: string; conditionJson: string | null }> }>(request, `/api/tasks/${taskId}`)
    const item = task.items.find((entry) => entry.defCode === 'CURRENCY')
    expect(item?.conditionJson ?? '').toContain('USD')

    // 非法条件（缺 defCode）结构化失败
    const bad = await vlCall(page, 'set_condition', { conditions: { fields: [] } })
    expect(String(bad.result ?? '')).toContain('缺少 defCode')
  })

  test('E2E-L13 confirm_step：步骤真实推进、非法步骤被拒（不越级）', async ({ page, request }) => {
    const taskId = await createExportTaskAtQueryCond(request)
    await vlOpenApp(page, `/export?taskId=${taskId}`)
    await vlWaitRegions(page, 'export.cond')

    // 越级/非法目标：明确拒绝并给出可选值
    const illegal = await vlCall(page, 'confirm_step', { step: 'NOPE' })
    expect(String(illegal.result ?? '')).toContain('不在导出向导范围内')
    // 界面仍停在原步骤
    await expect(page.locator('footer')).toContainText('task:EXPORT/QUERY_COND', { timeout: 15_000 })

    // 合法推进：QUERY_COND → EXPORT
    const advanced = await vlCall(page, 'confirm_step', { step: 'EXPORT' })
    expect(String(advanced.result ?? '')).toContain('导出执行')
    await expect(page.locator('footer')).toContainText('task:EXPORT/EXPORT', { timeout: 30_000 })
    // 落库：任务当前步骤同步
    const task = await api.get<{ currentStep: string }>(request, `/api/tasks/${taskId}`)
    expect(task.currentStep).toBe('EXPORT')
  })

  test('E2E-L14 open_export_file_editor：真打开编辑器并载入该文件；无产物时结构化失败', async ({ page, request }) => {
    const taskId = await createExportedTask(request)
    await vlOpenApp(page, `/export?taskId=${taskId}`)
    await vlWaitRegions(page, 'export.selectDefs')

    const opened = await vlCall(page, 'open_export_file_editor', { taskId, defCode: 'CURRENCY' })
    expect(opened.outcome).toBe('ok')
    expect(String(opened.result)).toContain('CURRENCY')
    await expect(page.locator('footer')).toContainText('task:EXPORT/EXPORT', { timeout: 30_000 })
    // 编辑器容器真的在屏（不是只回一句"已打开"）
    await expect(page.locator('[data-testid="spread-grid"], .spread-host, canvas').first()).toBeVisible({ timeout: 30_000 })

    // 无产物编码：结构化失败，不空白页
    const missing = await vlCall(page, 'open_export_file_editor', { taskId, defCode: 'DOC_TYPE' })
    expect(String(missing.outcome)).not.toBe('ok')
    expect(String(missing.result)).toContain('DOC_TYPE')
    await expect(page.locator('[data-testid="spread-grid"], .spread-host, canvas').first()).toBeVisible({ timeout: 15_000 })
  })

  test('E2E-L15 download_export_files：真下载事件 + 文件名与产物一致', async ({ page, request }) => {
    const taskId = await createExportedTask(request)
    await vlOpenApp(page, `/export?taskId=${taskId}`)
    await vlWaitRegions(page, 'export.selectDefs')

    // 先把该文件的在线编辑器打开（引擎就绪后再下载，落盘内容是"在线编辑后的当前表格"）
    const opened = await vlCall(page, 'open_export_file_editor', { taskId, defCode: 'CURRENCY' })
    expect(opened.outcome).toBe('ok')
    await expect(page.locator('[data-testid="spread-grid"], .spread-host, canvas').first()).toBeVisible({ timeout: 30_000 })

    const downloadPromise = page.waitForEvent('download', { timeout: 90_000 })
    const outcome = await vlCall(page, 'download_export_files', { taskId, defCodes: ['CURRENCY'] })
    const download = await downloadPromise
    const name = download.suggestedFilename()
    expect(name).toMatch(/CURRENCY/)
    expect(name).toMatch(/\.(xlsx|zip)$/)
    expect(String(outcome.result ?? '')).toContain('CURRENCY')
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
