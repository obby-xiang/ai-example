/**
 * 真机 E2E · 批③ V 域（可视可操作）剩余条 —— 12a
 *
 * 蓝本：`docs/research/设计-真机E2E用例全集-2026-10-23.md` §2.13（V-01…V-22）。
 * 批① 已覆盖 V-01 / V-02 / V-08 / V-11（见 `05-visop.spec.ts`），本文件承接其余 17 条
 * （V-21「预览高亮收口」不可承载，理由见交付报告）。
 *
 * 两条执行路径（口径见 `support/helpers-vl.ts` 抬头）：
 *   - **真模块直调**（成功路径）：页面里 `import('/src/utils/frontend-tools.ts')` 走
 *     `executeFrontendTool`（路由 → 围栏 → 执行 → 回执全链），断言落真 DOM / 计算样式 / aria；
 *   - **真模型路径**（拒执与卡面）：桩下发 `frontend_tool_request` → store → 执行器 → 卡面。
 *     桩路由的 epoch 是固定串，只能到围栏层 —— 这正是 V-07 / V-19 要断的一面。
 *
 * 端口/DB：本域独占 18930 / 18999 / 19001 + Redis DB 12（`support/helpers-vl.ts`）。
 */
import { expect, test, type APIRequestContext, type Page } from '@playwright/test'
import { api, createImportTaskAtUpload, sendMessage, waitRoundSettled, waitJob, type JobView } from './support/helpers'
import { aiIdentityHeaders, newAiSession } from './support/ai-identity'
import {
  ensureVlStack,
  latestStubRequest,
  stopVlStack,
  untilStubRequestAfter,
  VL_API,
  VL_APP,
  vlAbortOperations,
  vlCall,
  vlOpenApp,
  vlRuntime,
  vlSnapshot,
  vlSnapshotUntil,
  vlSnapshotWithRegion,
  vlStableFooter,
  vlWaitRegions
} from './support/helpers-vl'

const IMPORT_UPLOAD = (taskId: number): string => `/import?taskId=${taskId}`

/** 桩的会话指纹按首条 user 消息算 —— 每条用例带唯一后缀，互不串。 */
function mark(tag: string): string {
  return `${tag} ${Date.now()}-${Math.floor(Math.random() * 1000)}`
}

async function openImportUpload(page: Page, taskId: number): Promise<void> {
  await vlOpenApp(page, IMPORT_UPLOAD(taskId))
  await vlWaitRegions(page, 'import.upload')
  await vlWaitRegions(page, 'import.selectDefs')
}

/**
 * 造一条已发布（终态）的导入任务 —— 页面因此进入只读态。
 *
 * 夹具自证（V17 复扫定位）：模板/上传两步**必须校验状态码**。旧写法不校验时，模板 404 的
 * JSON 错误体会被当成 `.xlsx` 原样上传，随后 PRECHECK 以「未找到上传文件 / 无法解析」报
 * FAILED —— 读起来像产品回归，实际是夹具把一次 HTTP 失败吞成了"数据文件"。另注：两步用
 * 域内真基址 `VL_API`（与页面同栈），任务创建走 `api`（= `E2E_API_BASE`）；两者一旦不一致
 * （典型：忘了 `E2E_EXTERNAL=1`，harness 又自起一套），本函数会立刻以「模板下载失败」自曝，
 * 而不是伪装成 PRECHECK 红。两个分支都在失败时抛出带响应体的错误，报错即根因。
 */
async function createPublishedImportTask(request: APIRequestContext): Promise<number> {
  const task = await api.post<{ id: number }>(request, '/api/tasks', { type: 'IMPORT', title: `E2E-VL-RO-${Date.now()}` })
  const id = task.id
  await api.post(request, `/api/tasks/${id}/select-defs`, { defCodes: ['DOC_TYPE'] })
  const template = await request.get(`${VL_API}/api/tasks/${id}/files/templates?codes=DOC_TYPE`)
  if (!template.ok()) {
    throw new Error(`模板下载失败：HTTP ${template.status()} ${await template.text()}（任务 #${id} 是否建在 ${VL_API} 这套后端上？）`)
  }
  const bytes = await template.body()
  const upload = await request.post(`${VL_API}/api/tasks/${id}/files/upload`, {
    multipart: {
      file: {
        name: 'DOC_TYPE_e2e.xlsx',
        mimeType: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
        buffer: bytes
      }
    }
  })
  if (!upload.ok()) {
    throw new Error(`上传失败：HTTP ${upload.status()} ${await upload.text()}`)
  }
  const precheck = await api.post<JobView>(request, `/api/tasks/${id}/jobs`, { jobType: 'PRECHECK' })
  expect((await waitJob(request, precheck.id)).status).toBe('COMPLETED')
  await api.put(request, `/api/tasks/${id}/step`, { step: 'PUBLISH' })
  const publish = await api.post<JobView>(request, `/api/tasks/${id}/jobs`, { jobType: 'PUBLISH' })
  expect((await waitJob(request, publish.id)).status).toBe('COMPLETED')
  return id
}

/**
 * 读**表格自身**滚动容器的 scrollTop（L-109 的 V05 判据）。
 *
 * 为什么不再量"目标元素的祖先"：V05 的滚动目标是配置项搜索框（本页唯一带 `scroll` 动词的
 * 控件），它坐在表格**上方**、不在表格的滚动容器里。于是同一段手势会给出两个答案 ——
 * 行还没到齐时表格不可滚，滚轮落到页面外框（旧断言量到 >0）；行到齐后滚轮被表格吃掉
 * （旧断言量到 0，即挂账里"`pushed>0` 收到 0"）。"表格被滚了多少"只能问表格自己：
 * el-table 的真滚动层是 body-wrapper 内的滚动元素（外层 wrapper 本身是 `overflow:hidden`）。
 */
async function tableScrollTop(page: Page): Promise<number> {
  return page.evaluate(() => {
    const wrapper = document.querySelector('.el-table__body-wrapper')
    if (!wrapper) return 0
    let top = 0
    for (const node of Array.from(wrapper.querySelectorAll('*'))) {
      if (!(node instanceof HTMLElement)) continue
      const style = getComputedStyle(node)
      if (!/(auto|scroll|overlay)/.test(style.overflowY)) continue
      if (node.scrollHeight <= node.clientHeight) continue
      top = Math.max(top, node.scrollTop)
    }
    return top
  })
}

/** 元素是否在视口内（"落点可见"判据，用视口几何而非坐标断言）。 */
async function inViewport(page: Page, ref: string): Promise<boolean> {
  return page.evaluate((value) => {
    const el = document.querySelector(`[data-ai-ref="${value}"]`)
    if (!el) return false
    const rect = el.getBoundingClientRect()
    return rect.top >= -1 && rect.bottom <= window.innerHeight + 1
  }, ref)
}

test.describe('@stub V 可视可操作（12a）', () => {
  test.beforeAll(async () => {
    await ensureVlStack()
  })

  test.afterAll(async () => {
    await stopVlStack()
  })

  test('E2E-V03 ui_fill 写值与回读：200 字逐字相等、201 字被拒、未授权控件拒执', async ({ page, request }) => {
    const taskId = await createImportTaskAtUpload(request)
    await openImportUpload(page, taskId)

    const target = page.locator('[data-ai-ref="import.selectDefs.search"]')
    await expect(target).toBeVisible()

    // ① 未在 data-ai-verbs 里授权 fill 的控件拒执（勾选框只声明 click/highlight）
    const denied = await vlCall(page, 'ui_fill', { ref: 'import.selectDefs.CURRENCY', value: 'x' })
    expect(denied.outcome).toBe('failed')
    expect(denied.result).toContain('不允许「fill」动词')
    await expect(page.locator('[data-ai-ref="import.selectDefs.CURRENCY"]')).toHaveAttribute('aria-checked', 'false')

    // ② 边界内：200 字写后回读逐字相等（真 DOM value）
    const value200 = 'A'.repeat(200)
    const ok = await vlCall(page, 'ui_fill', { ref: 'import.selectDefs.search', value: value200 })
    expect(ok.outcome).toBe('ok')
    expect(ok.result).toContain('已填写')
    await expect(target).toHaveValue(value200)
    expect(await target.inputValue()).toBe(value200)
    // 撤销点已记录（写动作的固有回执之一）
    expect((await vlRuntime(page)).undo).not.toBeNull()

    // ③ 超 1 字：入参校验先于围栏，值必须原样不动
    const tooLong = await vlCall(page, 'ui_fill', { ref: 'import.selectDefs.search', value: 'B'.repeat(201) })
    expect(tooLong.outcome).toBe('failed')
    expect(tooLong.availability).toBe('unavailable')
    expect(tooLong.result).toContain('超过 200 字符')
    await expect(target).toHaveValue(value200)
  })

  test('E2E-V04 ui_select 只认语义值：缺 option 拒执、未授权 select 拒执、零副作用', async ({ page, request }) => {
    const taskId = await createImportTaskAtUpload(request)
    await openImportUpload(page, taskId)

    // ① 缺 option：入参校验说明"要语义值，不是索引"
    const noOption = await vlCall(page, 'ui_select', { ref: 'import.selectDefs.search' })
    expect(noOption.outcome).toBe('failed')
    expect(noOption.availability).toBe('unavailable')
    expect(noOption.result).toContain('选项的语义值')

    // ② 传索引（"1"）在未授权 select 的元素上不生效：动词闸先拒执，原语不会"点开再猜"
    const byIndex = await vlCall(page, 'ui_select', { ref: 'import.selectDefs.search', option: '1' })
    expect(byIndex.outcome).toBe('failed')
    expect(byIndex.result).toContain('不允许「select」动词')
    await expect(page.locator('[data-ai-ref="import.selectDefs.search"]')).toHaveValue('')

    // ③ 只读风险区域里的控件同样拒执（选择属本地写动作，只在 mutate-local 元素上允许）
    const readRisk = await vlCall(page, 'ui_select', { ref: 'import.grid.tab.DOC_TYPE', option: 'CURRENCY' })
    expect(readRisk.outcome).toBe('failed')
    expect(readRisk.result).toContain('不允许「select」动词')

    // 零副作用：目标勾选框与搜索框都未被改动
    const docType = page.locator('[data-ai-ref="import.selectDefs.DOC_TYPE"]')
    await expect(docType).toHaveAttribute('aria-checked', 'true')
    expect((await vlRuntime(page)).undo).toBeNull()
  })

  test('E2E-V05 ui_scroll 只改视口：表格视口真位移、数据未改、落点可见', async ({ page, request }) => {
    const taskId = await createImportTaskAtUpload(request)
    await openImportUpload(page, taskId)

    const targetRef = 'import.selectDefs.search'
    // 行到齐再滚：没有行时表格自身不可滚，滚轮会落到页面外框（旧断言 `pushed>0` 的漂移根因，L-109）
    await expect(page.locator('[data-ai-ref="import.selectDefs.DOC_TYPE"]')).toBeVisible({ timeout: 30_000 })
    const dataVersionBefore = await vlStableFooter(page, 'task:IMPORT/')

    // 真人手势：真鼠标悬停在表格上 + 真滚轮 ⇒ **表格自身**滚动容器的 scrollTop 真的变大
    await page.locator('.el-table__body-wrapper').first().hover()
    await page.mouse.wheel(0, 400)
    await expect.poll(() => tableScrollTop(page), { timeout: 10_000 }).toBeGreaterThan(0)
    const pushed = await tableScrollTop(page)

    // 原语把目标（搜索框）滚回视口：只改视口，且不碰表格自己的数据视口
    // （表格不是搜索框的祖先滚动容器 ⇒ 它的 scrollTop 必须原地不动；±2000 的入参上界在末尾单测）
    const scrolled = await vlCall(page, 'ui_scroll', { ref: targetRef, delta: 0, block: 'center' })
    expect(scrolled.outcome).toBe('ok')
    expect(scrolled.result).toContain('只改了视口')
    const after = await tableScrollTop(page)
    expect(after).toBe(pushed)
    expect(await inViewport(page, targetRef)).toBe(true)

    // 数据未改：数据版本不变、无写回执、无撤销点
    expect(await vlStableFooter(page, 'task:IMPORT/')).toBe(dataVersionBefore)
    const runtime = await vlRuntime(page)
    expect(runtime.undo).toBeNull()
    expect(runtime.writeCount).toBe(0)

    // 入参上界：±2000 之外的 delta 一律拒执
    const tooFar = await vlCall(page, 'ui_scroll', { ref: targetRef, delta: 2001 })
    expect(tooFar.outcome).toBe('failed')
    expect(tooFar.result).toContain('不超过 ±2000')
  })

  test('E2E-V06 ui_highlight 三重冗余：描边 + 图标 + 文案 + 读屏播报', async ({ page, request }) => {
    const taskId = await createImportTaskAtUpload(request)
    await openImportUpload(page, taskId)

    const outcome = await vlCall(page, 'ui_highlight', { ref: 'import.upload.choose', note: '在这里上传文件' })
    expect(outcome.outcome).toBe('ok')

    // ① 描边（类 + 标记属性）
    const target = page.locator('[data-ai-ref="import.upload.choose"]')
    await expect(target).toHaveClass(/ring-2/)
    await expect(target).toHaveAttribute('data-ai-highlighted', 'true')

    // ② 图标 + ③ 文案：徽标同时带 svg 与 note 原文（高亮 2.5s 后收口，断言在窗口内完成）
    const badge = page.locator('[data-ai-highlight-badge]')
    await expect(badge).toHaveCount(1)
    await expect(badge).toBeVisible()
    await expect(badge.locator('svg')).toHaveCount(1)
    await expect(badge).toContainText('AI 提示：在这里上传文件')

    // 读屏播报：运行时只存动作文本，前缀由面板 `ai.panel.uiOperating` 组句（不出现双前缀）
    const runtime = await vlRuntime(page)
    expect(runtime.announcement).toBe('在这里上传文件')

    // note 预算 60 字
    const tooLong = await vlCall(page, 'ui_highlight', { ref: 'import.upload.choose', note: 'N'.repeat(61) })
    expect(tooLong.outcome).toBe('failed')
    expect(tooLong.result).toContain('超过 60 字符')
  })

  test('E2E-V07 ui_request_upload 入参校验：永不经手路径、accept 只收 xlsx/zip、未快照 ref 拒执', async ({ page, request }) => {
    const taskId = await createImportTaskAtUpload(request)
    await openImportUpload(page, taskId)

    // 参数面（真模块的唯一校验造口 validateUploadArgs）
    const params = await page.evaluate(async () => {
      const host = window as unknown as { __vlImport?: (path: string) => Promise<unknown> }
      const load = async (path: string) => host.__vlImport ? host.__vlImport(path) : import(path)
      const dom = await load('/src/utils/ai-dom-tools.ts') as {
        validateUploadArgs: (args: Record<string, unknown>) => { ok: boolean; outcome?: { result?: string } }
        uploadFileNameOf: (file: { name: string }) => string
      }
      const withPath = dom.validateUploadArgs({ ref: 'import.upload.choose', path: '/tmp/a.xlsx' })
      const badAccept = dom.validateUploadArgs({ ref: 'import.upload.choose', accept: ['exe'] })
      const upperAccept = dom.validateUploadArgs({ ref: 'import.upload.choose', accept: ['.XLSX', 'zip'] })
      const longName = dom.uploadFileNameOf({ name: `${'x'.repeat(80)}.xlsx` })
      return {
        withPath: { ok: withPath.ok, result: withPath.outcome?.result ?? '' },
        badAccept: { ok: badAccept.ok, result: badAccept.outcome?.result ?? '' },
        upperAccept: { ok: upperAccept.ok },
        longName
      }
    })
    expect(params.withPath.ok).toBe(false)
    expect(params.withPath.result).toContain('永不经手文件路径')
    expect(params.badAccept.ok).toBe(false)
    expect(params.badAccept.result).toContain('只能收窄到 xlsx / zip')
    expect(params.upperAccept.ok).toBe(true)
    // 文件名预算 64：超长只回 basename 的截断形态（保留扩展名），不是"照搬全名"
    expect(params.longName.length).toBeLessThanOrEqual(64)
    expect(params.longName.endsWith('.xlsx')).toBe(true)

    // 快照闸（真模型路径）：桩下发未快照的 ref ⇒ store 拒执并落 rejected 卡，不建交接卡
    let chooserFired = false
    page.on('filechooser', () => {
      chooserFired = true
    })
    await sendMessage(page, `scenario=UI-UPLOAD ${mark('V07')}`)
    const card = page.locator('[data-testid^="tool-card-"]').last()
    await expect(card).toBeVisible({ timeout: 30_000 })
    await expect(page.locator('[data-testid="tool-output-text"]').last()).toContainText('未在本轮界面快照中出现', { timeout: 20_000 })
    await expect(card.locator('[data-testid^="card-status-"]')).toHaveAttribute('data-state', 'rejected')
    await waitRoundSettled(page, 40_000)
    // 无"等人"卡（不建卡 = 没有手势入口），也没有倒计时式超时
    await expect(page.locator('[data-testid="upload-handoff-card"]')).toHaveCount(0)
    expect(chooserFired).toBe(false)
  })

  test('E2E-V09 跨刷新 epoch 失效：真 reload 后旧 ref 一律拒执且零副作用', async ({ page, request }) => {
    const taskId = await createImportTaskAtUpload(request)
    await openImportUpload(page, taskId)

    const before = await vlSnapshot(page)
    const oldRef = 'import.selectDefs.CURRENCY'
    const oldEpoch = before.epoch
    const checkbox = page.locator(`[data-ai-ref="${oldRef}"]`)

    // 刷新前该 ref 可用（对照：同一段代码、同一个 ref，reload 前能点）
    const live = await vlCall(page, 'ui_click', { ref: oldRef })
    expect(live.outcome).toBe('ok')
    await expect(checkbox).toHaveAttribute('aria-checked', 'true')

    // 真重载（不是"重新挂载组件"）
    await page.reload({ waitUntil: 'domcontentloaded' })
    await vlWaitRegions(page, 'import.selectDefs')
    const stateAfterReload = await checkbox.getAttribute('aria-checked')

    const after = await vlSnapshot(page)
    expect(after.epoch).not.toBe(oldEpoch)

    // 用刷新前的 ref + 刷新前的 epoch 触发操作：拒执 + 零副作用 + 可行动原因
    const stale = await vlCall(page, 'ui_click', { ref: oldRef, epoch: oldEpoch }, { snapshot: false })
    expect(stale.outcome).toBe('failed')
    expect(stale.result).toContain('界面已变化（epoch 不匹配')
    expect(stale.result).toContain('请重新 ui_snapshot')
    expect(await checkbox.getAttribute('aria-checked')).toBe(stateAfterReload)
    expect((await vlRuntime(page)).writeCount).toBe(0)
  })

  test('E2E-V10 写前原值被用户手改：UI_PRECONDITION_CHANGED，不覆盖人工值', async ({ page, request }) => {
    // 本用例刻意用"未勾选任何配置项"的任务：勾选集变化会让编辑区区域注册/注销并推进 epoch，
    // 而前置条件判据绑定 epoch —— 那会把"用户手改"和"换 epoch"混为一谈。
    const task = await api.post<{ id: number }>(request, '/api/tasks', { type: 'IMPORT', title: `E2E-VL-V10-${Date.now()}` })
    await openImportUpload(page, task.id)

    const input = page.locator('[data-ai-ref="import.selectDefs.search"]')
    const first = await vlCall(page, 'ui_fill', { ref: 'import.selectDefs.search', value: 'DOC' })
    expect(first.outcome).toBe('ok')
    await expect(input).toHaveValue('DOC')

    // 用户手工追加一个字符（真鼠标 + 真键盘）
    await input.click()
    await page.keyboard.press('End')
    await page.keyboard.type('X')
    await expect(input).toHaveValue('DOCX')

    const second = await vlCall(page, 'ui_fill', { ref: 'import.selectDefs.search', value: 'CURR' })
    expect(second.outcome).toBe('rejected')
    expect(second.code).toBe('UI_PRECONDITION_CHANGED')
    expect(second.result).toContain('结论：')
    expect(second.result).toContain('原因：')
    expect(second.result).toContain('下一步：')
    // 人工值原样保留
    await expect(input).toHaveValue('DOCX')
  })

  test('E2E-V12 幂等与去重：同轮同参直返、跨轮重新执行、已在目标页直返', async ({ page, request }) => {
    const taskId = await createImportTaskAtUpload(request)
    await openImportUpload(page, taskId)

    const first = await vlCall(page, 'ui_snapshot', { limit: 20 }, { runId: 'vl-run-a' })
    expect(first.outcome).toBe('ok')
    const second = await vlCall(page, 'ui_snapshot', { limit: 20 }, { runId: 'vl-run-a' })
    expect(second.result).toContain('本轮已执行过同参调用')

    // 换轮（runId 变）⇒ 去重缓存按轮重置，必须真执行
    const other = await vlCall(page, 'ui_snapshot', { limit: 20 }, { runId: 'vl-run-b' })
    expect(other.result).not.toContain('本轮已执行过同参调用')
    expect(other.result).toContain('界面快照')

    // 同页同参导航：UI_ALREADY_ON_PAGE 直返，且不重复 push
    const samePage = await vlCall(page, 'navigate_to', { page: 'import', taskId })
    expect(samePage.outcome).toBe('ok')
    expect(samePage.code).toBe('UI_ALREADY_ON_PAGE')
    expect(samePage.result).toContain('已在目标页')
    await expect(page).toHaveURL(new RegExp(`taskId=${taskId}`))
  })

  test('E2E-V13 写动词每轮配额 12：第 13 次拒执、下一轮重置', async ({ page, request }) => {
    // 本用例刻意不做任何勾选：编辑区（import.grid）不出现 ⇒ 过滤不会改变区域注册集，
    // 于是"换 epoch"不会掺进配额判定（配额才是唯一变量）。
    const task = await api.post<{ id: number }>(request, '/api/tasks', { type: 'IMPORT', title: `E2E-VL-V13-${Date.now()}` })
    await openImportUpload(page, task.id)
    const writeRef = 'import.selectDefs.search'

    await vlCall(page, 'ui_snapshot', { limit: 20 }, { turn: 'vl-turn-q1' })
    const outcomes: string[] = []
    for (let i = 0; i < 13; i += 1) {
      const outcome = await vlCall(page, 'ui_fill', { ref: writeRef, value: `q${i}` }, { snapshot: false })
      outcomes.push(String(outcome.outcome))
    }
    expect(outcomes.slice(0, 12)).toEqual(Array(12).fill('ok'))
    expect(outcomes[12]).toBe('failed')
    expect((await vlRuntime(page)).writeCount).toBe(12)

    // 配额耗尽的文案（第 13 次回执）
    const last = await vlCall(page, 'ui_fill', { ref: writeRef, value: 'q13' }, { snapshot: false })
    expect(last.result).toContain('已达上限（12 次）')

    // 下一轮重置为 12：计数归零且写动词重新可用
    await vlCall(page, 'ui_snapshot', { limit: 20 }, { turn: 'vl-turn-q2' })
    expect((await vlRuntime(page)).writeCount).toBe(0)
    const next = await vlCall(page, 'ui_fill', { ref: writeRef, value: 'q2' }, { snapshot: false })
    expect(next.outcome).toBe('ok')
  })

  test('E2E-V14 动态披露收窄：显式 uiRegionCount=0 不出 ui_*，缺失键 fail-open', async ({ request }) => {
    const before = latestStubRequest()

    // ① 显式 0：本轮上下文声明"本页没有已注册区域" ⇒ 七个 ui_* 一律不披露
    const zeroMark = mark('V14-zero')
    const zero = await request.post(`${VL_API}/api/ai/chat`, {
      data: {
        sessionId: await newAiSession(VL_API),
        message: `配置定义 ${zeroMark}`,
        context: { page: 'definitions', extra: { uiRegionCount: 0 } }
      },
      headers: { Accept: 'text/event-stream', ...aiIdentityHeaders() },
      timeout: 60_000
    })
    expect(zero.ok()).toBe(true)
    await zero.text()
    const zeroRequest = await untilStubRequestAfter(before?.tag ?? null)
    expect(zeroRequest.tools).toContain('list_config_defs')
    for (const name of ['ui_snapshot', 'ui_click', 'ui_fill', 'ui_select', 'ui_scroll', 'ui_highlight', 'ui_request_upload']) {
      expect(zeroRequest.tools).not.toContain(name)
    }

    // ② 缺失键：按 fail-open 披露（旧客户端 / curl 不带该键时不能把界面能力整片关掉）
    const absentMark = mark('V14-absent')
    const absent = await request.post(`${VL_API}/api/ai/chat`, {
      data: {
        sessionId: await newAiSession(VL_API),
        message: `配置定义 ${absentMark}`,
        context: { page: 'definitions' }
      },
      headers: { Accept: 'text/event-stream', ...aiIdentityHeaders() },
      timeout: 60_000
    })
    expect(absent.ok()).toBe(true)
    await absent.text()
    const absentRequest = await untilStubRequestAfter(zeroRequest.tag)
    expect(absentRequest.tools).toContain('ui_snapshot')
    expect(absentRequest.tools).toContain('ui_click')

    // ③ 另一条通路（只读披露端点）同向：不带 extra ⇒ fail-open，ui_* 在列
    const tools = await request.get(`${VL_API}/api/ai/tools?page=definitions`)
    const body = (await tools.json()) as { data: { toolNames: string[] } }
    expect(body.data.toolNames).toContain('ui_snapshot')
  })

  test('E2E-V15 区域注册/注销 ↔ epoch：换页后区域整片关闭、旧 ref 在新文档里失效', async ({ page, request }) => {
    const taskId = await createImportTaskAtUpload(request)
    await openImportUpload(page, taskId)
    // L-109：区域注册可能晚于断言 ⇒ 轮询到它真出现在快照里再取 epoch
    const before = await vlSnapshotWithRegion(page, 'import.selectDefs')
    expect(before.regionIds).toContain('import.selectDefs')
    const oldRef = 'import.selectDefs.search'
    const oldEpoch = before.epoch

    const resolveOld = async (): Promise<string | null> => page.evaluate(async (ref) => {
      const host = window as unknown as { __vlImport?: (path: string) => Promise<unknown> }
      const load = async (path: string) => host.__vlImport ? host.__vlImport(path) : import(path)
      const regions = await load('/src/utils/ai-regions.ts') as {
        resolveAiRef: (value: string) => { region: { id: string } } | null
      }
      return regions.resolveAiRef(ref)?.region.id ?? null
    }, oldRef)
    expect(await resolveOld()).toBe('import.selectDefs')

    // 真导航（应用内 router push，同一文档）：import 页卸载 ⇒ 区域逐个注销
    const navigated = await vlCall(page, 'navigate_to', { page: 'tasks' })
    expect(navigated.outcome).toBe('ok')
    await expect(page.locator('footer')).toContainText('page:tasks', { timeout: 30_000 })
    await expect(page).not.toHaveURL(/import/, { timeout: 30_000 })

    // 区域注销同理是"晚于断言"的：轮询到区域表真的清空，再断言旧 ref 的结局
    const afterPage = await vlSnapshotUntil(page, (view) => view.regionIds.length === 0,
      '导入器区域整片注销（regionIds 空）')
    expect(afterPage.epoch).not.toBe(oldEpoch)
    expect(afterPage.regionIds).toHaveLength(0)
    expect(await resolveOld()).toBeNull()
    // 区域全注销 ⇒ 原语整片不可用（不是"还能点，只是猜错 ref"）
    const fencedOut = await vlCall(page, 'ui_click', { ref: oldRef }, { snapshot: false })
    expect(fencedOut.outcome).toBe('failed')
    expect(fencedOut.result).toContain('不支持')

    // 重新进入导入器：新文档/新 epoch 下旧 ref 仍然失效（快照闸），新快照的 ref 可用
    await vlOpenApp(page, IMPORT_UPLOAD(taskId))
    const back = await vlSnapshotWithRegion(page, 'import.selectDefs')
    expect(back.epoch).not.toBe(afterPage.epoch)
    expect(await resolveOld()).toBe('import.selectDefs')
    const staleEpoch = await vlCall(page, 'ui_click', { ref: oldRef, epoch: oldEpoch }, { snapshot: false })
    expect(staleEpoch.outcome).toBe('failed')
    expect(staleEpoch.result).toContain('界面已变化（epoch 不匹配')
    // 未快照分支同样拒执（零副作用）：换一个没被本轮快照覆盖的 ref
    const neverSeen = await vlCall(page, 'ui_click', { ref: 'import.selectDefs.NOT_IN_THIS_SNAPSHOT' }, { snapshot: false })
    expect(neverSeen.outcome).toBe('rejected')
    expect(neverSeen.code).toBe('UI_REF_NOT_SNAPSHOTTED')
    const fresh = await vlCall(page, 'ui_click', { ref: 'import.selectDefs.CURRENCY' })
    expect(fresh.outcome).toBe('ok')
    await expect(page.locator('[data-ai-ref="import.selectDefs.CURRENCY"]')).toHaveAttribute('aria-checked', 'true')
  })

  test('E2E-V16 操作条与读屏播报：动作文案可见、停止等效、结束即消失', async ({ page, request }) => {
    const taskId = await createImportTaskAtUpload(request)
    await openImportUpload(page, taskId)

    // 起一轮会持续 loading 的真回合（桩帧间静默 8s），把原语的播报同步进面板（生产路径的同一入口）
    await sendMessage(page, `turn-text stall-frames=8000 ${mark('V16')}`)
    await expect(page.locator('[data-testid="stop-btn"]')).toHaveCount(1, { timeout: 20_000 })
    const highlight = await vlCall(page, 'ui_highlight', { ref: 'import.upload.choose', note: '准备上传' })
    expect(highlight.outcome).toBe('ok')
    await page.evaluate(async () => {
      const host = window as unknown as { __vlImport?: (path: string) => Promise<unknown> }
      const store = await (host.__vlImport ? host.__vlImport('/src/stores/ai.ts') : import('/src/stores/ai.ts')) as {
        useAiStore: () => { syncUiRuntimeState: () => void }
      }
      store.useAiStore().syncUiRuntimeState()
    })

    const bar = page.locator('[data-testid="ai-operating-bar"]')
    await expect(bar).toBeVisible({ timeout: 15_000 })
    await expect(bar).toContainText('准备上传')
    const liveRegion = page.locator('[data-testid="ai-live-region"]')
    await expect(liveRegion).toHaveAttribute('aria-live', 'polite')
    await expect(liveRegion).toHaveText('AI 正在操作左侧工作区：准备上传')

    // 操作条上的停止 = 与主停止同一个语义（ai.stop()）
    await page.locator('[data-testid="ai-operating-stop"]').click()
    await expect(bar).toHaveCount(0, { timeout: 20_000 })
    await expect(page.locator('[data-testid="stop-btn"]')).toHaveCount(0, { timeout: 20_000 })
    // 停止后界面动词一律拒执（停止位与配额同源）
    const blocked = await vlCall(page, 'ui_click', { ref: 'import.selectDefs.CURRENCY' })
    expect(blocked.outcome).not.toBe('ok')
    expect(blocked.result).toContain('本轮已停止')
  })

  test('E2E-V17 只读任务下写动词拒执：读动词可用、零副作用', async ({ page, request }) => {
    const taskId = await createPublishedImportTask(request)
    await vlOpenApp(page, `/import?taskId=${taskId}`)
    await vlWaitRegions(page, 'import.selectDefs')

    // 只读页的控件是禁用态（aria-disabled）⇒ 写动词落在禁用闸，且绝不谎报"已点击"
    const checkboxRef = 'import.selectDefs.CURRENCY'
    await expect(page.locator(`[data-ai-ref="${checkboxRef}"]`)).toHaveAttribute('aria-disabled', 'true')
    const writeOutcome = await vlCall(page, 'ui_click', { ref: checkboxRef })
    expect(['blocked', 'failed']).toContain(String(writeOutcome.outcome))
    expect(writeOutcome.result).not.toContain('已点击')
    expect((await vlRuntime(page)).writeCount).toBe(0)
    await expect(page.locator(`[data-ai-ref="${checkboxRef}"]`)).toHaveAttribute('aria-checked', 'false')

    // 读动词可用：高亮照常（禁用元素也能看，拦了是能力倒退）
    const highlight = await vlCall(page, 'ui_highlight', { ref: checkboxRef, note: '只读页面' })
    expect(highlight.outcome).toBe('ok')
    await expect(page.locator(`[data-ai-ref="${checkboxRef}"]`)).toHaveAttribute('data-ai-highlighted', 'true')
  })

  test('E2E-V18 围栏短路顺序：停止 > 快照 > 区域，只回第一个命中码', async ({ page, request }) => {
    const taskId = await createImportTaskAtUpload(request)
    await openImportUpload(page, taskId)

    // ① 停止位命中：即使同时"未快照 + ref 不存在"，也只回停止文案（不误报为未快照）
    await vlAbortOperations(page)
    const aborted = await vlCall(page, 'ui_click', { ref: 'no.such.ref', epoch: 'zzzzzz-1' }, { snapshot: false })
    expect(aborted.outcome).toBe('failed')
    expect(aborted.result).toContain('本轮已停止')
    expect(aborted.result).not.toContain('未在本轮界面快照中')
    expect(aborted.code).toBeUndefined()

    // ② 新一轮复位停止位：未快照先于区域解析（同一个不存在的 ref）
    await vlCall(page, 'ui_snapshot', { limit: 5 }, { turn: 'vl-turn-short' })
    const notSnapshotted = await vlCall(page, 'ui_click', { ref: 'no.such.ref' }, { snapshot: false })
    expect(notSnapshotted.outcome).toBe('rejected')
    expect(notSnapshotted.code).toBe('UI_REF_NOT_SNAPSHOTTED')
    expect(notSnapshotted.result).not.toContain('不在当前页面')

    // ③ 快照过、随后区域被注销 ⇒ 拒执（区域层），仍零副作用
    const snapshot = await vlSnapshot(page)
    const aRef = snapshot.refs.find((item) => item.regionId === 'import.selectDefs')?.ref
    expect(aRef).toBeTruthy()
    await page.evaluate(async () => {
      const host = window as unknown as { __vlImport?: (path: string) => Promise<unknown> }
      const load = async (path: string) => host.__vlImport ? host.__vlImport(path) : import(path)
      const regions = await load('/src/utils/ai-regions.ts') as {
        unregisterAiRegion: (id: string) => void
      }
      regions.unregisterAiRegion('import.selectDefs')
    })
    const gone = await vlCall(page, 'ui_click', { ref: aRef as string }, { snapshot: false })
    expect(gone.outcome).not.toBe('ok')
    expect(gone.result).not.toContain('已点击')
  })

  test('E2E-V19 8 态回执落卡：八态映射闭集 + 卡面状态与 tool_result 同表', async ({ page, request, browser }) => {
    // ① 八态 → 卡面状态（真模块，唯一映射表）+ 闭集不吞 timeout/rejected
    await vlOpenApp(page)
    const mapping = await page.evaluate(async () => {
      const host = window as unknown as { __vlImport?: (path: string) => Promise<unknown> }
      const load = async (path: string) => host.__vlImport ? host.__vlImport(path) : import(path)
      const receipt = await load('/src/utils/ui-receipt.ts') as {
        toolStatusOfOutcome: (outcome: string) => string
        UI_CODES: readonly string[]
      }
      const outcomes = ['ok', 'partial', 'timeout', 'rejected', 'blocked', 'cancelled', 'skipped', 'unknown']
      const table: Record<string, string> = {}
      for (const outcome of outcomes) table[outcome] = receipt.toolStatusOfOutcome(outcome)
      return { table, codes: receipt.UI_CODES }
    })
    expect(mapping.table.ok).toBe('succeeded')
    expect(mapping.table.rejected).toBe('rejected')
    expect(mapping.table.blocked).toBe('blocked')
    expect(mapping.table.cancelled).toBe('cancelled')
    expect(mapping.table.skipped).toBe('skipped')
    expect(mapping.table.timeout).toBe('failed')
    expect(mapping.table.partial).toBe('failed')
    expect(mapping.table.unknown).toBe('unknown')
    expect(mapping.table.timeout).not.toBe(mapping.table.unknown)
    expect(mapping.table.rejected).not.toBe(mapping.table.unknown)
    // 契约来源：`frontend/src/utils/ui-receipt.ts` 的 `UI_CODES` 闭集（机器码，不进用户文案）。
    // L-82（笔 6321424）新增 `UI_DOWNLOAD_TIMEOUT`（7 → 8）；
    // FE-32（Task 5）新增 `UI_TARGET_HIDDEN`（8 → 9），且**与 `UI_TARGET_DISABLED` 分开归因**：
    // 隐藏的下一步是"切到该步骤 / 展开面板"，禁用的下一步才是"先让控件恢复可用"。
    // 逐枚点名（不只数数）：闭集被悄悄放宽、改名或换掉新增那一枚都会在这里红。
    const expectedCodes = [
      'UI_PAGE_NOT_READY',
      'UI_REF_NOT_SNAPSHOTTED',
      'UI_BUDGET_EXHAUSTED',
      'UI_DOWNLOAD_TIMEOUT',
      'UI_PRECONDITION_CHANGED',
      'UI_TARGET_DISABLED',
      'UI_TARGET_HIDDEN',
      'UI_ALREADY_ON_PAGE',
      'UI_ALREADY_EXECUTED'
    ]
    expect(mapping.codes).toHaveLength(expectedCodes.length)
    for (const code of expectedCodes) {
      expect(mapping.codes).toContain(code)
    }
    // 闭集内不重复（同一码两条 = 映射表被复制粘贴污染）
    expect(new Set(mapping.codes).size).toBe(expectedCodes.length)

    // ② 真卡面：成功卡（succeeded）与拒执卡（rejected）各有独立状态与文案。
    //    两轮必须各自独立会话 —— 桩的收敛契约③规定"上下文里已有 role=tool 消息就只发文本"，
    //    同一会话的第二条消息不会再有工具调用。
    const taskId = await createImportTaskAtUpload(request)
    const first = await browser.newContext({ baseURL: VL_APP, locale: 'zh-CN' })
    try {
      const okPage = await first.newPage()
      await openImportUpload(okPage, taskId)
      await sendMessage(okPage, `scenario=UI-SNAPSHOT ${mark('V19')}`)
      const okCard = okPage.locator('[data-testid^="tool-card-"]').last()
      await expect(okCard).toBeVisible({ timeout: 30_000 })
      await expect(okCard.locator('[data-testid^="card-status-"]')).toHaveAttribute('data-state', 'succeeded', { timeout: 20_000 })
      await waitRoundSettled(okPage, 40_000)
    } finally {
      await first.close()
    }

    const second = await browser.newContext({ baseURL: VL_APP, locale: 'zh-CN' })
    try {
      const rejectedPage = await second.newPage()
      await openImportUpload(rejectedPage, taskId)
      await sendMessage(rejectedPage, `scenario=UI-CLICK ${mark('V19-rej')}`)
      const rejectedCard = rejectedPage.locator('[data-testid^="tool-card-"]').last()
      await expect(rejectedCard).toBeVisible({ timeout: 30_000 })
      await expect(rejectedCard.locator('[data-testid^="card-status-"]')).toHaveAttribute('data-state', 'rejected', { timeout: 20_000 })
      await expect(rejectedCard.locator('[data-testid="tool-output-text"]')).toContainText('未在本轮界面快照中出现')
      await waitRoundSettled(rejectedPage, 40_000)
    } finally {
      await second.close()
    }
  })

  test('E2E-V20 IME 组合输入不被破坏：组合串结束后写值正确、无拼音残留', async ({ page, request }) => {
    const taskId = await createImportTaskAtUpload(request)
    await openImportUpload(page, taskId)
    const input = page.locator('[data-ai-ref="import.selectDefs.search"]')
    await input.click()

    const cdp = await page.context().newCDPSession(page)
    await cdp.send('Input.imeSetComposition', { text: 'zhongwen', selectionStart: 8, selectionEnd: 8 })
    await cdp.send('Input.insertText', { text: '中文' })
    await expect(input).toHaveValue('中文')

    // 组合结束（选中态收敛）后由原语写值：值逐字相等、无半截拼音
    await page.keyboard.press('End')
    const written = await vlCall(page, 'ui_fill', { ref: 'import.selectDefs.search', value: 'E2E-IME' })
    expect(written.outcome).toBe('ok')
    const value = await input.inputValue()
    expect(value).toBe('E2E-IME')
    expect(value).not.toContain('zhongwen')
    expect(value).not.toContain('中中')
  })

  test('E2E-V22 跨区域 ref 唯一性与解析：全局唯一、前缀即区域、标签 ≤24 字', async ({ page, request }) => {
    const taskId = await createImportTaskAtUpload(request)
    await openImportUpload(page, taskId)
    // L-109：区域注册晚于断言 ⇒ 轮询到三个区域同时出现在一份快照里
    const view = await vlSnapshotUntil(page, (snapshot) => snapshot.regionIds.length >= 3,
      '导入上传页至少三个区域同时注册')

    // 同一次快照里多个区域同时注册 ⇒ ref 全局唯一（跨区不重名）
    expect(view.regionIds.length).toBeGreaterThanOrEqual(3)
    const refs = view.refs.map((item) => item.ref).filter((ref) => ref !== '')
    expect(new Set(refs).size).toBe(refs.length)
    for (const item of view.refs) {
      // 前缀即区域 id：ref 解析不串区
      expect(item.ref.startsWith(`${item.regionId}.`)).toBe(true)
      // 标签预算 24 字（快照行宽的一部分）
      expect(item.label.length).toBeLessThanOrEqual(24)
    }

    // 解析结果与快照归属一致（真模块解析，不是按字符串猜）
    const resolved = await page.evaluate(async (list) => {
      const host = window as unknown as { __vlImport?: (path: string) => Promise<unknown> }
      const load = async (path: string) => host.__vlImport ? host.__vlImport(path) : import(path)
      const regions = await load('/src/utils/ai-regions.ts') as {
        resolveAiRef: (value: string) => { region: { id: string } } | null
      }
      const table: Record<string, string | null> = {}
      for (const ref of list) table[ref] = regions.resolveAiRef(ref)?.region.id ?? null
      return table
    }, refs.slice(0, 12))
    for (const item of view.refs.slice(0, 12)) {
      expect(resolved[item.ref]).toBe(item.regionId)
    }

    // 不存在的区域 ref 解析为 null（不会"跨区串门"到同后缀的另一个区域）
    const ghost = await page.evaluate(async () => {
      const host = window as unknown as { __vlImport?: (path: string) => Promise<unknown> }
      const load = async (path: string) => host.__vlImport ? host.__vlImport(path) : import(path)
      const regions = await load('/src/utils/ai-regions.ts') as {
        resolveAiRef: (value: string) => unknown
      }
      return regions.resolveAiRef('import.nonexistent.search') === null
    })
    expect(ghost).toBe(true)

    // 跨区使用（A 区的 ref 在 B 区的动作里）= 区域围栏拒执：注销 A 区后其 ref 立即不可用
    await page.evaluate(async () => {
      const host = window as unknown as { __vlImport?: (path: string) => Promise<unknown> }
      const load = async (path: string) => host.__vlImport ? host.__vlImport(path) : import(path)
      const regions = await load('/src/utils/ai-regions.ts') as {
        unregisterAiRegion: (id: string) => void
      }
      regions.unregisterAiRegion('import.selectDefs')
    })
    const otherRegionRef = view.refs.find((item) => item.regionId === 'import.selectDefs')?.ref as string
    const cross = await vlCall(page, 'ui_highlight', { ref: otherRegionRef }, { snapshot: false })
    expect(cross.outcome).not.toBe('ok')
    expect(cross.result).not.toContain('已高亮')
  })
})
