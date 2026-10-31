/**
 * 真机 E2E 批② · 13c —— U 域（撤销族）+ I 域（i18n 双语）+ Z-04（空视图）
 *
 * 蓝本：docs/research/设计-真机E2E用例全集-2026-10-23.md §2.19 / §2.20 / §2.18
 *
 * 运行：
 *   cd frontend && npx playwright test --config e2e/playwright.nfezui.config.ts e2e/13c-nfezui.spec.ts
 *
 * 撤销面的口径说明（重要）：`ui_*` 的**模型路径**在桩里带固定 epoch（`a1b2c3-1`），而 epoch 是
 * 每页随机 —— 桩永远进不了写动词（05-visop 已用"真模块真 DOM"绕开同一处）。本文件因此：
 *   - 元素选择/快照闸/风险分级/撤销栈/撤销回写**全部走真模块真 DOM**（`/src/utils/ai-dom-tools.ts`）；
 *   - 撤销按钮与卡面渲染走**真组件真点击**；
 *   - 唯一被用例直接赋值的是「帧 → store」这两跳（`uiUndoEntry` / `uiUndoToolCallId`），
 *     它们在生产里由 `runFrontendTool` 在收到 frontend_tool_request 后写入。
 */
import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { expect, test, type Page } from '@playwright/test'
import {
  assistantBubbles,
  cjkHits,
  createImportTaskAtUpload,
  createTask,
  deleteAllTasks,
  expandToolCard,
  lastToolCallId,
  listTasks,
  openPanel,
  send,
  sessionIdOf,
  storeEval,
  switchToChinese,
  switchToEnglish,
  userBubbles,
  waitSettled
} from './support/helpers-nfezui'

const KNOWN_CJK_RESIDUE = new Set([
  // 发现项（产品未 i18n，本轮不修：AiPanel.vue 被同族并行改动中）：
  // ① AiPanel.vue 的可用性标签 `return ai.available ? '可用' : '已降级'`（硬编码）；
  // ② 工作区事件消息由 workspace store 用中文拼装（`工作区已同步：进入任务中心（v5 · 界面）`），
  //    其中的页面名/动作词来自业务数据，不是 ai.* 词表。
  '可用',
  '已降级',
  '会话',
  '工作区',
  '数据版本',
  '任务中心',
  '进入任务中心',
  '界面',
  '工作区已同步',
  // ③ loading-breath 的「工作中…」也是硬编码文案（AiPanel.vue 的呼吸点标签）
  '工作中'
])

/** 词表驱动面之外只允许上述已知残留；其它任何 CJK 视为回归。 */
function unexpectedCjk(text: string): string[] {
  return cjkHits(text).filter((hit) => !KNOWN_CJK_RESIDUE.has(hit))
}

/**
 * 面板文本，只剔除身份切换器的**option 数据**（租户/用户下拉里的业务数据）。
 *
 * <p>下拉的 option 文案来自后端身份目录（预置「租户一/租户二/用户101…」），与消息正文同属数据面，
 * 不是硬编码 chrome，因此不参与 CJK 扫描。<b>但切换器本体的 chrome 必须留在扫描面内</b>：
 * 旧实现整块 `remove()` 掉 `identity-switcher`，把 select（及其 aria-label / 未来的标签文案）
 * 一起摘掉 —— 那一块的 i18n 回归（切到 en 之后仍带中文）根本测不到。这里只摘 `<option>`（数据），
 * select 元素与其属性保留（aria-label 另由 I03 的 attrs 断言逐条覆盖）。
 */
async function panelTextWithoutIdentityOptions(page: Page): Promise<string> {
  return page.locator('.ai-chat').evaluate((el) => {
    const clone = el.cloneNode(true) as HTMLElement
    clone.querySelectorAll('[data-testid="identity-switcher"] option').forEach((node) => node.remove())
    return clone.innerText
  })
}

const IMPORT_PAGE = '/import'
const unique = (tag: string): string => `${tag}-${Date.now()}-${Math.random().toString(36).slice(2, 6)}`

interface MutateClickResult {
  ref: string
  before: string | null
  after: string | null
  outcome: string
  hasUndo: boolean
  kind: string
}

/** 走真模块做一次 mutate-local 点击（快照闸 + 风险分级 + 撤销栈都是产品真路径）。 */
async function mutateLocalClick(page: Page): Promise<MutateClickResult> {
  return page.evaluate(async () => {
    const dom = await import('/src/utils/ai-dom-tools.ts') as unknown as {
      aiSnapshot: (args: Record<string, unknown>) => { result?: string }
      aiClick: (args: Record<string, unknown>) => Promise<{ outcome?: string; result?: string }>
      peekUiUndo: () => { kind?: string } | null
    }
    const reg = await import('/src/utils/ai-regions.ts') as unknown as { currentAiEpoch: () => string }
    const target = document.querySelector('[data-ai-undo="retoggle"]') as HTMLElement | null
    const ref = target?.getAttribute('data-ai-ref') ?? ''
    const before = target?.getAttribute('aria-checked') ?? null
    dom.aiSnapshot({ limit: 40 })
    const click = await dom.aiClick({ ref, epoch: reg.currentAiEpoch() })
    const entry = dom.peekUiUndo() as { kind?: string } | null
    return {
      ref,
      before,
      after: target?.getAttribute('aria-checked') ?? null,
      outcome: click.outcome ?? '',
      hasUndo: entry !== null,
      kind: entry?.kind ?? ''
    }
  })
}

test.describe('@stub 13c · U 撤销族', () => {
  test('E2E-U01 界面撤销点出现：mutate-local 点击成功后，仅归属卡上出现撤销行', async ({ page, request }) => {
    const taskId = await createImportTaskAtUpload(request)
    await openPanel(page, `${IMPORT_PAGE}?taskId=${taskId}`)
    await expect(page.locator('footer')).toContainText('task:IMPORT/UPLOAD', { timeout: 30_000 })

    // 先有一张前端工具卡（真 frontend_tool_request → 卡面），撤销行只能挂在它自己的卡上
    await send(page, `scenario=UI-CLICK 用例-U01-${unique('c')}`)
    const toolCallId = await lastToolCallId(page)
    expect(toolCallId.length).toBeGreaterThan(0)
    await waitSettled(page, 60_000)
    await expect(page.locator('[data-testid="ui-undo-row"]')).toHaveCount(0)

    const click = await mutateLocalClick(page)
    // 前端工具结局八态里「成功」的实发值是 'ok'（'succeeded' 是段状态名，不是 outcome）
    expect(click.outcome).toBe('ok')
    expect(click.hasUndo).toBe(true)
    expect(click.kind).toBe('retoggle')
    expect(click.before).not.toBe(click.after)

    // 帧 → store 的两跳（生产由 runFrontendTool 写入）：指向那张真实工具卡
    await storeEval(page, (store, arg) => {
      const ai = store as unknown as { syncUiRuntimeState: () => void; uiUndoToolCallId: string | null }
      ai.syncUiRuntimeState()
      ai.uiUndoToolCallId = arg as string
      return true
    }, toolCallId)

    await expandToolCard(page, toolCallId)
    const row = page.locator('[data-testid="ui-undo-row"]')
    await expect(row).toHaveCount(1)
    await expect(row).toBeVisible()
    await expect(row.locator('[data-testid="ui-undo-button"]')).toBeVisible()
    // 归属正确：撤销行就在那张工具的卡体内（卡外无第二份）
    await expect(page.locator(`[data-testid="tool-card-${toolCallId}"] [data-testid="ui-undo-row"]`)).toHaveCount(1)
  })

  test('E2E-U02 撤销恢复原值：真点撤销钮 → 控件回到点击前状态 + 回执文案 + 读屏播报', async ({ page, request }) => {
    const taskId = await createImportTaskAtUpload(request)
    await openPanel(page, `${IMPORT_PAGE}?taskId=${taskId}`)
    await expect(page.locator('footer')).toContainText('task:IMPORT/UPLOAD', { timeout: 30_000 })

    await send(page, `scenario=UI-CLICK 用例-U02-${unique('c')}`)
    const toolCallId = await lastToolCallId(page)
    await waitSettled(page, 60_000)

    const click = await mutateLocalClick(page)
    // 前端工具结局八态里「成功」的实发值是 'ok'（'succeeded' 是段状态名，不是 outcome）
    expect(click.outcome).toBe('ok')
    await storeEval(page, (store, arg) => {
      const ai = store as unknown as { syncUiRuntimeState: () => void; uiUndoToolCallId: string | null }
      ai.syncUiRuntimeState()
      ai.uiUndoToolCallId = arg as string
      return true
    }, toolCallId)
    await expandToolCard(page, toolCallId)

    await expect(page.locator('[data-testid="ui-undo-button"]')).toBeVisible()
    // 点撤销钮（卡体展开是异步的：坐标点击会因按钮位移落空，故走 locator 的命中测试点击）
    await page.locator('[data-testid="ui-undo-button"]').click()

    await expect(page.locator('[data-testid="ui-undo-row"]')).toHaveCount(0, { timeout: 20_000 })
    // 控件回到点击前（逐值相等：aria-checked 复原）
    const restored = await page.locator('[data-ai-undo="retoggle"]').first().getAttribute('aria-checked')
    expect(restored).toBe(click.before)
    // 回执文案（notice 槽）+ 读屏播报（aria-live 区非空）
    await expect(page.locator('[data-testid="notice-bar"]')).toContainText('已撤销')
  })

  test('E2E-U03 撤销深度 1：撤销一次后按钮消失，不可二次撤销', async ({ page, request }) => {
    const taskId = await createImportTaskAtUpload(request)
    await openPanel(page, `${IMPORT_PAGE}?taskId=${taskId}`)
    await expect(page.locator('footer')).toContainText('task:IMPORT/UPLOAD', { timeout: 30_000 })
    await send(page, `scenario=UI-CLICK 用例-U03-${unique('c')}`)
    const toolCallId = await lastToolCallId(page)
    await waitSettled(page, 60_000)

    const click = await mutateLocalClick(page)
    expect(click.hasUndo).toBe(true)
    await storeEval(page, (store, arg) => {
      const ai = store as unknown as { syncUiRuntimeState: () => void; uiUndoToolCallId: string | null }
      ai.syncUiRuntimeState()
      ai.uiUndoToolCallId = arg as string
      return true
    }, toolCallId)
    await expandToolCard(page, toolCallId)
    await page.locator('[data-testid="ui-undo-button"]').click()
    await expect(page.locator('[data-testid="ui-undo-row"]')).toHaveCount(0)

    // 深度 1：撤销栈空；再调用一次只得到「没有可撤销的界面操作」，不产生假回执
    const second = await storeEval(page, async (store) => {
      const ai = store as unknown as { undoLastUiAction: () => Promise<string> }
      return ai.undoLastUiAction()
    })
    expect(second).toContain('没有可撤销')
    await expect(page.locator('[data-testid="ui-undo-row"]')).toHaveCount(0)
  })

  test('E2E-U07 撤销不跨刷新/跨会话：撤销栈是内存态，刷新与新建对话后都不出现撤销钮', async ({ page, request }) => {
    const taskId = await createImportTaskAtUpload(request)
    await openPanel(page, `${IMPORT_PAGE}?taskId=${taskId}`)
    await expect(page.locator('footer')).toContainText('task:IMPORT/UPLOAD', { timeout: 30_000 })
    await send(page, `scenario=UI-CLICK 用例-U07-${unique('c')}`)
    const toolCallId = await lastToolCallId(page)
    await waitSettled(page, 60_000)

    const click = await mutateLocalClick(page)
    expect(click.hasUndo).toBe(true)
    await storeEval(page, (store, arg) => {
      const ai = store as unknown as { syncUiRuntimeState: () => void; uiUndoToolCallId: string | null }
      ai.syncUiRuntimeState()
      ai.uiUndoToolCallId = arg as string
      return true
    }, toolCallId)
    await expandToolCard(page, toolCallId)
    await expect(page.locator('[data-testid="ui-undo-button"]')).toBeVisible()

    // ① 真刷新 ⇒ 撤销栈（内存态）归零：页面上没有任何撤销钮
    await page.reload({ waitUntil: 'domcontentloaded' })
    await expect(page.locator('[data-testid="composer-input"]')).toBeVisible({ timeout: 30_000 })
    await expect(page.locator('[data-testid="ui-undo-row"]')).toHaveCount(0)

    // ② 新建对话 ⇒ 换会话收口（resetUiSurfaces），撤销点不跨会话
    const sidBefore = await sessionIdOf(page)
    await page.locator('[data-testid="new-chat"]').click()
    await expect(page.locator('[data-testid="empty-state"]')).toBeVisible({ timeout: 20_000 })
    expect(await sessionIdOf(page)).not.toBe(sidBefore)
    await expect(page.locator('[data-testid="ui-undo-row"]')).toHaveCount(0)
  })
})

test.describe('@stub 13c · I i18n 双语', () => {
  test('E2E-I01 zh→en 全局面板切换：面板头/空态/输入区/提示条转英文，无中文残留', async ({ page }) => {
    await openPanel(page)
    const zhText = await page.locator('.ai-chat').innerText()
    expect(cjkHits(zhText).length).toBeGreaterThan(0)

    await switchToEnglish(page)
    const enText = await panelTextWithoutIdentityOptions(page)
    // 工作区状态条 / 可用性标签是已知残留（见 KNOWN_CJK_RESIDUE 的两条发现项），
    // 其余 chrome 一律零 CJK —— 出现新的中文即判红。
    expect(unexpectedCjk(enText)).toEqual([])
    // 关键面逐处英文：空态标题、建议 chips、输入框 aria、停止/发送按钮
    await expect(page.locator('[data-testid="empty-state"]')).toContainText(/[A-Za-z]/)
    const chips = await page.locator('[data-testid="suggestion-chip"]').allInnerTexts()
    expect(chips.length).toBeGreaterThan(0)
    for (const chip of chips) {
      expect(cjkHits(chip)).toHaveLength(0)
      expect(chip.trim().length).toBeGreaterThan(0)
    }
    await expect(page.locator('[data-testid="composer-input"]')).toHaveAttribute('placeholder', /[A-Za-z]/)
    expect(cjkHits((await page.locator('[data-testid="send-btn"]').getAttribute('aria-label')) ?? '')).toHaveLength(0)
  })

  test('E2E-I02 键集对称：zh-CN 与 en-US 键集零差集、无空值键', async () => {
    const localesDir = join(process.cwd(), 'src', 'locales')
    const flatten = (value: unknown, prefix = ''): string[] => {
      if (value === null || typeof value !== 'object') return prefix === '' ? [] : [prefix]
      return Object.entries(value as Record<string, unknown>)
        .flatMap(([key, child]) => flatten(child, prefix === '' ? key : `${prefix}.${key}`))
    }
    const zh = JSON.parse(readFileSync(join(localesDir, 'zh-CN.json'), 'utf8')) as Record<string, unknown>
    const en = JSON.parse(readFileSync(join(localesDir, 'en-US.json'), 'utf8')) as Record<string, unknown>
    const zhKeys = new Set(flatten(zh))
    const enKeys = new Set(flatten(en))

    const missingInEn = [...zhKeys].filter((key) => !enKeys.has(key))
    const missingInZh = [...enKeys].filter((key) => !zhKeys.has(key))
    expect(missingInEn).toEqual([])
    expect(missingInZh).toEqual([])
    // ai.* / tool.* / resultview.* 三大族两侧都齐（本域蓝本点名的词表族）
    for (const key of [...zhKeys]) {
      if (/^(ai|tool|resultview)\./.test(key)) {
        expect(enKeys.has(key)).toBe(true)
      }
    }
    // 无空值键
    const empties: string[] = []
    const walk = (value: unknown, prefix = ''): void => {
      if (value === null || typeof value !== 'object') {
        if (String(value ?? '').trim() === '') empties.push(prefix)
        return
      }
      for (const [key, child] of Object.entries(value as Record<string, unknown>)) {
        walk(child, prefix === '' ? key : `${prefix}.${key}`)
      }
    }
    walk(zh)
    walk(en)
    expect(empties).toEqual([])
  })

  test('E2E-I03 面板 DOM 无硬编码中文：en 模式下除消息正文外零 CJK', async ({ page }) => {
    await openPanel(page)
    await switchToEnglish(page)
    // 空态（无消息正文）时的面板全量文本（已知残留见 KNOWN_CJK_RESIDUE）
    const full = await panelTextWithoutIdentityOptions(page)
    expect(unexpectedCjk(full)).toEqual([])

    const attrs = await page.locator('.ai-chat [aria-label], .ai-chat [title]').evaluateAll((nodes) =>
      nodes.flatMap((node) => [node.getAttribute('aria-label') ?? '', node.getAttribute('title') ?? '']))
    expect(cjkHits(attrs.filter((text) => text.trim() !== '').join('\n'))).toHaveLength(0)

    // 一轮问答后：把用户正文与模型正文从面板文本里剔除，剩余 chrome 仍零 CJK
    const question = `nfezui i18n probe ${unique('q')}`
    await send(page, question)
    await waitSettled(page, 60_000)
    // 呼吸点带淡出动画：等它真的退场再扫，避免把「流式中标签」当静态 chrome
    await expect(page.locator('[data-testid="loading-breath"]')).toHaveCount(0, { timeout: 20_000 })
    const answer = (await assistantBubbles(page).last().innerText()).trim()
    const withRound = await panelTextWithoutIdentityOptions(page)
    const stripped = withRound.split(question).join('').split(answer).join('')
    expect(unexpectedCjk(stripped)).toEqual([])
  })

  test('E2E-I06 建议 chips 词表与 aria：en 模式走 ai.suggestions.*，aria-label 带动作语义', async ({ page }) => {
    await openPanel(page)
    await switchToEnglish(page)
    const chips = page.locator('[data-testid="suggestion-chip"]')
    const count = await chips.count()
    expect(count).toBeGreaterThan(0)
    for (let index = 0; index < count; index += 1) {
      const chip = chips.nth(index)
      const label = (await chip.getAttribute('aria-label')) ?? ''
      expect(label).toMatch(/^Send:/i)
      expect(cjkHits(label)).toHaveLength(0)
      expect(cjkHits((await chip.innerText()).trim())).toHaveLength(0)
    }
    // 建议组（每轮建议）在 en 下同样走词表
    await send(page, `turn-text 用例-I06-${unique('a')}`)
    await waitSettled(page, 60_000)
    const group = page.locator('[data-testid="followup-suggestions"]')
    if (await group.count()) {
      expect(await group.getAttribute('aria-label')).toMatch(/[A-Za-z]/)
    }
  })

  test('E2E-I08 切语言不丢状态：消息/草稿/待决卡/轮次在 zh↔en 之间保持', async ({ page }) => {
    await openPanel(page)
    await send(page, `turn-text 用例-I08-a-${unique('a')}`)
    await waitSettled(page)
    await send(page, `turn-text 用例-I08-b-${unique('b')}`)
    await waitSettled(page)
    expect(await assistantBubbles(page).count()).toBe(2)

    // ① 草稿：切语言不丢、不重放消息、不产生新轮
    await page.locator('[data-testid="composer-input"]').fill('草稿-I08')
    const bubbleCount = await page.locator('[data-testid^="msg-bubble-"]').count()
    await switchToEnglish(page)
    expect(await page.locator('[data-testid^="msg-bubble-"]').count()).toBe(bubbleCount)
    await expect(page.locator('[data-testid="composer-input"]')).toHaveValue('草稿-I08')
    await switchToChinese(page)
    await expect(page.locator('[data-testid="composer-input"]')).toHaveValue('草稿-I08')
    expect(await page.locator('[data-testid^="msg-bubble-"]').count()).toBe(bubbleCount)

    // ② 待决卡（GF 表单）：挂起中切语言，表单结构与已填值保持
    //    注：挂起态输入区被硬禁用（composerHardDisabled），故草稿面在第 ① 段单独验，不与之叠加。
    await page.locator('[data-testid="composer-input"]').fill('')
    await send(page, 'scenario=FILTER 用例-I08-form')
    const fields = page.locator('[data-testid="gf-fields"]')
    await expect(fields).toBeVisible({ timeout: 60_000 })
    await page.locator('#gf-field-keyword').first().fill('nfezui-i08-draft')
    const bubblesWithForm = await page.locator('[data-testid^="msg-bubble-"]').count()

    await switchToEnglish(page)
    await expect(fields).toBeVisible()
    await expect(page.locator('#gf-field-keyword').first()).toHaveValue('nfezui-i08-draft')
    expect(await page.locator('[data-testid^="msg-bubble-"]').count()).toBe(bubblesWithForm)

    await switchToChinese(page)
    await expect(page.locator('#gf-field-keyword').first()).toHaveValue('nfezui-i08-draft')
    expect(await page.locator('[data-testid^="msg-bubble-"]').count()).toBe(bubblesWithForm)
  })

  test('E2E-I09 长英文文案不溢出：窄栏 360px 下 en 面板无水平溢出', async ({ page }) => {
    await page.setViewportSize({ width: 360, height: 780 })
    await openPanel(page)
    await switchToEnglish(page)
    const overflow = await page.locator('.ai-chat').evaluate((el) => ({
      scrollWidth: el.scrollWidth,
      clientWidth: el.clientWidth
    }))
    expect(overflow.scrollWidth).toBeLessThanOrEqual(overflow.clientWidth + 1)

    // 有一轮消息（含长英文按钮组）后仍不溢出
    await send(page, `turn-text 用例-I09-${unique('a')}`)
    await waitSettled(page, 60_000)
    const after = await page.locator('.ai-chat').evaluate((el) => ({
      scrollWidth: el.scrollWidth,
      clientWidth: el.clientWidth
    }))
    expect(after.scrollWidth).toBeLessThanOrEqual(after.clientWidth + 1)
  })
})

test.describe('@stub 13c · Z-04 空视图（须先清空任务库，故放在最后）', () => {
  test('E2E-Z04 空视图：零任务时出现 resultview-empty（不是空白、也不是空表格骨架）', async ({ page, request }) => {
    // 先把库造起来再清空：删除动作必须真的发生过（否则「空」可能只是本来就没数据）
    await createTask(request, `NFEZUI-Z04-a-${unique('t')}`)
    await createTask(request, `NFEZUI-Z04-b-${unique('t')}`, 'IMPORT')
    const removed = await deleteAllTasks(request)
    expect(removed).toBeGreaterThan(0)
    expect(await listTasks(request)).toHaveLength(0)

    await openPanel(page, '/tasks')
    await send(page, `NFZ-LIST-TASKS 用例-Z04-${unique('t')}`)
    await expect(page.locator('[data-testid^="tool-card-"]').last()).toBeVisible({ timeout: 60_000 })
    await waitSettled(page, 60_000)
    await expandToolCard(page, await lastToolCallId(page))
    const empty = page.locator('[data-testid="resultview-empty"]').last()
    await expect(empty).toBeVisible({ timeout: 60_000 })
    await expect(empty).toContainText('暂无任务')
    // 空 ≠ 失败：不出现「无法以表格展示」的降级文案，也不渲染空表骨架
    await expect(page.locator('[data-testid="resultview-fallback"]')).toHaveCount(0)
    await expect(page.locator('[data-testid="resultview-table"]')).toHaveCount(0)
    await waitSettled(page, 60_000)
    // 本轮仍是一问一答（空视图不是失败轮，气泡与工具卡都在）
    expect(await userBubbles(page).count()).toBe(1)
    await expect(page.locator('[data-testid^="tool-card-"]').last()).toBeVisible()
  })
})
