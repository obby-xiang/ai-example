/**
 * 真机 E2E 批② · 13d —— Z 域（结果视图的 text / fallback 两支）+ I 域（工具面与结果视图文案双语）
 *
 * 蓝本：docs/research/设计-真机E2E用例全集-2026-10-23.md §2.18（Z-02/Z-03）/ §2.20（I-04/I-07）
 *
 * **合成夹具声明**（Z-02/Z-03）：`kind=text` 与 `kind=chart` 在仓内**没有后端造口**
 * （`ListTasksResultViewProvider` 只造 `table`；`chart`/`form` 是契约占位）。故这两条用一个
 * **真工具卡** + 契约合法（`version=1.0` + 合法 `status` + 必填 `fallback.text`）的视图值，
 * 走真 `normalizeResultView` → 真 `ResultViewRenderer` → 真 DOM。合成的是「视图值」这一输入，
 * 渲染链、归一化、降级判定全部是产品代码。
 */
import { expect, test, type Page } from '@playwright/test'
import {
  assistantBubbles,
  cjkHits,
  createTask,
  expandToolCard,
  lastToolCallId,
  listTasks,
  openPanel,
  send,
  storeEval,
  switchToEnglish,
  waitSettled
} from './support/helpers-nfezui'

const unique = (tag: string): string => `${tag}-${Date.now()}-${Math.random().toString(36).slice(2, 6)}`

interface ToolCardIds {
  messageId: string
  toolCallId: string
}

/** 取最后一张工具卡的 (messageId, toolCallId)：视图夹具只挂在真卡上（并展开卡体）。 */
async function lastToolCard(page: Page): Promise<ToolCardIds> {
  const toolCallId = await lastToolCallId(page)
  await expandToolCard(page, toolCallId)
  const messageId = await storeEval(page, (store, arg) => {
    const messages = store.messages as Array<{ id: string; parts?: Array<{ kind?: string; toolCallId?: string }> }>
    const host = messages.find((message) =>
      (message.parts ?? []).some((part) => part.kind === 'tool' && part.toolCallId === arg))
    return host?.id ?? ''
  }, toolCallId)
  return { messageId, toolCallId }
}

/** 把契约合法的 resultView 值写进那张真卡的段（其余渲染链全走产品代码）。 */
async function attachView(page: Page, ids: ToolCardIds, view: Record<string, unknown>): Promise<void> {
  await storeEval(page, (store, arg) => {
    const payload = arg as { messageId: string; toolCallId: string; view: Record<string, unknown> }
    const messages = store.messages as Array<{ id: string; parts?: Array<Record<string, unknown>> }>
    const message = messages.find((item) => item.id === payload.messageId)
    const part = (message?.parts ?? []).find(
      (item) => item.kind === 'tool' && item.toolCallId === payload.toolCallId
    )
    if (!part) return false
    part.resultView = payload.view
    return true
  }, { messageId: ids.messageId, toolCallId: ids.toolCallId, view })
}

test.describe('@stub 13d · Z 结果视图（text / fallback）+ I 工具面双语', () => {
  test('E2E-Z02 text 视图渲染：按纯文本渲染（不解析 markdown、不执行 HTML）、保留原文', async ({ page }) => {
    await openPanel(page, '/tasks')
    await send(page, `配置定义 用例-Z02-${unique('t')}`)
    await expect(page.locator('[data-testid^="tool-card-"]').last()).toBeVisible({ timeout: 60_000 })
    await waitSettled(page, 60_000)
    const ids = await lastToolCard(page)
    expect(ids.messageId).not.toBe('')
    expect(ids.toolCallId).not.toBe('')

    const text = '一句话结果：**不是加粗** <b>不是标签</b> 第一行\n第二行保留换行'
    await attachView(page, ids, {
      version: '1.0',
      kind: 'text',
      status: 'ok',
      description: text,
      fallback: { text: 'i18n:resultview.fallback.generic' }
    })

    const node = page.locator('[data-testid="resultview-text"]').last()
    await expect(node).toBeVisible({ timeout: 15_000 })
    const rendered = await node.innerText()
    // 原文逐字保留（markdown 标记与 HTML 标记都当文本，不被解析）
    expect(rendered).toContain('**不是加粗**')
    expect(rendered).toContain('<b>不是标签</b>')
    expect(rendered).toContain('第二行保留换行')
    // 不解析：容器内没有 b/strong/a 标签被渲染出来
    expect(await node.locator('b, strong, a').count()).toBe(0)
    // 纯文本视图自带 whitespace-pre-wrap（换行可见）
    const whiteSpace = await node.evaluate((el) => window.getComputedStyle(el).whiteSpace)
    expect(whiteSpace).toBe('pre-wrap')
  })

  test('E2E-Z03 未知 kind（chart）走 fallback：出降级文案（不是空白），并保留原文兜底', async ({ page }) => {
    await openPanel(page, '/tasks')
    await send(page, `配置定义 用例-Z03-${unique('t')}`)
    await expect(page.locator('[data-testid^="tool-card-"]').last()).toBeVisible({ timeout: 60_000 })
    await waitSettled(page, 60_000)
    const ids = await lastToolCard(page)

    await attachView(page, ids, {
      version: '1.0',
      kind: 'chart',
      status: 'ok',
      fallback: { text: 'i18n:resultview.fallback.generic' },
      data: { inline: { series: [1, 2, 3] } }
    })

    const fallback = page.locator('[data-testid="resultview-fallback"]').last()
    await expect(fallback).toBeVisible({ timeout: 15_000 })
    // 降级文案来自词表（不空白、不白屏）
    await expect(fallback).toContainText('该结果暂不支持结构化展示')
    // 未知 kind 不得渲成表格；原文仍在 tool-output-text 里兜底
    await expect(page.locator('[data-testid="resultview-table"]')).toHaveCount(0)
    await expect(page.locator('[data-testid="tool-output-text"]').last()).not.toBeEmpty()
  })

  test('E2E-I04 工具名与参数说明双语：en 模式下工具卡标题走 tool.* 词表、零中文', async ({ page }) => {
    await openPanel(page, '/tasks')
    await switchToEnglish(page)
    await send(page, `配置定义 用例-I04-${unique('t')}`)
    const card = page.locator('[data-testid^="tool-card-"]').last()
    await expect(card).toBeVisible({ timeout: 60_000 })
    await waitSettled(page, 60_000)

    const title = (await page.locator('[data-testid^="card-title-"]').last().innerText()).trim()
    // 词表值（en）= "List definitions"；未登记时回落原始工具名，两种都不该是中文
    expect(title.length).toBeGreaterThan(0)
    expect(cjkHits(title)).toHaveLength(0)

    // 卡面整体：剔除卡状态标签（blocks.ts 的 toolStatusLabelOf 硬编码中文——发现项 I-04/I-07），
    // 其余（标题/参数/结果）必须零中文
    const cardText = await card.evaluate((el) => {
      const clone = el.cloneNode(true) as HTMLElement
      clone.querySelectorAll('[data-testid^="card-status-"]').forEach((node) => node.remove())
      // 工具**输出**是业务数据（定义名/任务标题等本来就是中文），I-04 只锁「显示名/参数说明」
      clone.querySelectorAll('[data-testid="tool-output-text"], [data-testid^="resultview-"]').forEach((node) => node.remove())
      return clone.innerText
    })
    expect(cjkHits(cardText)).toHaveLength(0)
  })

  test('E2E-I07 表格卡与结果视图文案双语：en 下表格列名/截断说明全英文', async ({ page, request }) => {
    // 夹具：把任务造到 22 条以上，触发视图的 truncated 说明
    const current = (await listTasks(request)).length
    for (let index = current; index < 22; index += 1) {
      await createTask(request, `NFEZUI-I07-${index}-${unique('t')}`)
    }

    await openPanel(page, '/tasks')
    await switchToEnglish(page)
    await send(page, `NFZ-LIST-TASKS 用例-I07-${unique('t')}`)
    await expect(page.locator('[data-testid^="tool-card-"]').last()).toBeVisible({ timeout: 60_000 })
    await waitSettled(page, 60_000)
    await expandToolCard(page, await lastToolCallId(page))
    const table = page.locator('[data-testid="resultview-table"]').last()
    await expect(table).toBeVisible({ timeout: 30_000 })

    const headers = (await table.locator('thead th').allInnerTexts()).map((text) => text.trim())
    expect(headers.length).toBe(5)
    for (const header of headers) {
      expect(header.length).toBeGreaterThan(0)
      expect(cjkHits(header)).toHaveLength(0)
    }
    const note = page.locator('[data-testid="resultview-truncated"]').last()
    await expect(note).toBeVisible()
    const noteText = await note.innerText()
    expect(noteText).toMatch(/\d/)
    expect(cjkHits(noteText)).toHaveLength(0)
    // 卡面整体（剔除卡状态标签，理由同 I-04）零中文
    const card = page.locator('[data-testid^="tool-card-"]').last()
    const cardText = await card.evaluate((el) => {
      const clone = el.cloneNode(true) as HTMLElement
      clone.querySelectorAll('[data-testid^="card-status-"]').forEach((node) => node.remove())
      return clone.innerText
    })
    expect(cjkHits(cardText)).toHaveLength(0)
    await waitSettled(page, 60_000)
  })
})
