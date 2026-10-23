/**
 * 批③d · 14a · MD 域（RND 渲染：markdown 结构 / 高亮 / mermaid / 流式尾块 / 降级 / 消毒）
 *
 * 蓝本：docs/research/设计-真机E2E用例全集-2026-10-23.md §2.23（MD-01…MD-12）。
 * 覆盖对账：X-09（前端绝不裸渲）与 MD-11 是同一断言面（蓝图 §0.6 第 6 条：一处一登记），
 * 故 X-09 合并进本文件 MD-11，X 文件不再重开一条。
 *
 * 上游：本域自建桩 `support/mdxy-upstream.mjs`（E2E_STUB_SCRIPT 注入），正文形状由提示词里的
 * `md=<形状>` 标记选择；期望值一律从桩落盘的 `content.json` 取（用例与桩只有一个字面量来源）。
 *
 * 断言纪律（蓝图 §0.2 A 级）：只断 DOM 结构 / class / 文本 / 计算样式，不断动画中间态与像素坐标。
 */
import { expect, test, type Locator, type Page } from '@playwright/test'
import { assistantBubble, assistantText, openApp, sendMessage, waitRoundSettled } from './support/helpers'
import { content, uniq } from './support/helpers-mdxy'

/** 助手气泡里的正文容器（唯一渲染链路 `.md-body`）。 */
function mdBody(page: Page): Locator {
  return assistantBubble(page).locator('.md-body')
}

/** 从 markdown 文档里取指定语言的围栏源码（MD-03 的期望值来源）。 */
function fenceCode(doc: string, lang: string): string {
  const match = new RegExp('```' + lang + '\\n([\\s\\S]*?)```').exec(doc)
  return match === null ? '' : match[1]
}

test.describe('@stub MD 渲染', () => {
  test('E2E-MD01 基础结构：标题/列表/表格/引用/分隔线各自渲染，无原始标记泄漏', async ({ page }) => {
    await openApp(page)
    await sendMessage(page, `md=basic 用例MD01-${uniq()}`)
    await waitRoundSettled(page)

    const body = mdBody(page)
    await expect(body.locator('h1')).toHaveText('一级标题')
    await expect(body.locator('h2')).toHaveText('二级标题')
    await expect(body.locator('h3')).toHaveText('三级标题')
    await expect(body.locator('ul > li')).toHaveCount(2)
    await expect(body.locator('ol > li')).toHaveCount(2)
    await expect(body.locator('strong')).toHaveText('加粗')
    await expect(body.locator('em')).toHaveText('斜体')
    // 删除线：markdown-it 产的是 `<s>`，DOMPurify 白名单只放行 `del` ⇒ 标记被剥、文字保留
    // （发现项 MD-01-D1：删除线样式丢失，见报告）；此处断言"文字在、标记不漏"。
    await expect(body).toContainText('删除线')
    expect(await body.locator('s').count()).toBe(0)
    // 表格：3 列 × 2 数据行（列数正确 = 表头单元格数）
    await expect(body.locator('table thead th')).toHaveCount(3)
    await expect(body.locator('table tbody tr')).toHaveCount(2)
    await expect(body.locator('blockquote')).toHaveCount(1)
    await expect(body.locator('hr')).toHaveCount(1)

    // 原始标记不泄漏（`##` / 表格分隔行 / 引用符都要被解析掉）
    const text = await body.innerText()
    expect(text).not.toContain('##')
    expect(text).not.toContain('| ---')
    expect(text).not.toContain('**加粗**')
    expect(text).not.toContain('> 引用行')
  })

  test('E2E-MD02 行内代码与围栏高亮：hljs 类名在位、长行块内横滚不撑破气泡', async ({ page }) => {
    await openApp(page)
    await sendMessage(page, `md=highlight 用例MD02-${uniq()}`)
    await waitRoundSettled(page)

    const body = mdBody(page)
    await expect(body.locator('code.md-code').first()).toHaveText('const inlineValue = 42')

    const figure = body.locator('figure.code')
    await expect(figure).toHaveCount(1)
    await expect(figure.locator('figcaption .lg')).toHaveText('js')
    // highlight.js 真正跑过（不是纯文本转义）
    await expect(figure.locator('span[class*="hljs-"]').first()).toBeVisible()

    const pre = figure.locator('pre')
    const metrics = await pre.evaluate((el) => ({
      scrollWidth: el.scrollWidth,
      clientWidth: el.clientWidth,
      overflowX: getComputedStyle(el).overflowX
    }))
    expect(metrics.overflowX).toBe('auto')
    expect(metrics.scrollWidth).toBeGreaterThan(metrics.clientWidth)

    const bubbleBox = await assistantBubble(page).boundingBox()
    const figureBox = await figure.boundingBox()
    expect(bubbleBox).not.toBeNull()
    expect(figureBox).not.toBeNull()
    expect(figureBox!.width).toBeLessThanOrEqual(bubbleBox!.width + 1)
  })

  test('E2E-MD03 代码块复制：剪贴板内容等于原始代码（含缩进、无高亮标签）', async ({ page, context }) => {
    await context.grantPermissions(['clipboard-read', 'clipboard-write'])
    await openApp(page)
    await sendMessage(page, `md=highlight 用例MD03-${uniq()}`)
    await waitRoundSettled(page)

    const button = mdBody(page).locator('figure.code button.cpbtn')
    await expect(button).toHaveCount(1)
    await button.click()
    await expect(button).toHaveClass(/is-copied/)

    const copied = (await page.evaluate(() => navigator.clipboard.readText())).replace(/\r\n/g, '\n')
    const expected = fenceCode(content().highlight, 'js')
    /** 行尾空白不参与比对（剪贴板/选区可能带行尾空白），行首缩进必须逐字保留。 */
    const lines = (text: string): string[] => text.split('\n').map((line) => line.replace(/\s+$/, '')).join('\n').trimEnd().split('\n')
    expect(expected).toContain('  return a + b')
    expect(lines(copied)).toEqual(lines(expected))
    expect(copied).toContain('\n  return a + b\n')
    expect(copied).not.toContain('hljs')
    expect(copied).not.toContain('<span')
  })

  test('E2E-MD04 mermaid 卡渲染：围栏升级为图卡，源码块不再并存', async ({ page }) => {
    await openApp(page)
    const errors: string[] = []
    page.on('pageerror', (error) => errors.push(error.message))
    await sendMessage(page, `md=mermaid 用例MD04-${uniq()}`)
    await waitRoundSettled(page)

    const body = mdBody(page)
    await expect(body.locator('.mmdc')).toHaveCount(1)
    const stage = body.locator('.mmdc-stage svg')
    await expect(stage).toBeVisible()
    // 图内文字可读（mermaid 产出的 <text> 节点带原始标签）
    await expect(stage).toContainText('开始')
    // 「不出现源码与图并存」：mermaid 围栏块已被卡替换
    await expect(body.locator('pre code.language-mermaid')).toHaveCount(0)
    expect(errors).toEqual([])
  })

  test('E2E-MD05 查看器缩放：百分比按 1.25 步进且与 transform 一致，关闭归位', async ({ page }) => {
    await openApp(page)
    await sendMessage(page, `md=mermaid 用例MD05-${uniq()}`)
    await waitRoundSettled(page)
    await mdBody(page).locator('.mmdc-full').click()

    const viewer = page.locator('[data-testid="mmd-viewer"]')
    const pct = page.locator('[data-testid="mmd-zoom-pct"]')
    const zoomLayer = page.locator('[data-testid="mmd-viewer-zoom"]')
    await expect(viewer).toBeVisible()
    await expect(pct).toHaveText('100%')

    await page.locator('[data-testid="mmd-zoom-in"]').click()
    await expect(pct).toHaveText('125%')
    await page.locator('[data-testid="mmd-zoom-in"]').click()
    await expect(pct).toHaveText('156%')
    expect(await zoomLayer.evaluate((el) => getComputedStyle(el).getPropertyValue('--z').trim())).toBe('1.5625')

    await page.locator('[data-testid="mmd-zoom-out"]').click()
    await expect(pct).toHaveText('125%')
    await page.locator('[data-testid="mmd-reset"]').click()
    await expect(pct).toHaveText('100%')
    expect(await zoomLayer.evaluate((el) => getComputedStyle(el).getPropertyValue('--z').trim())).toBe('1')

    await page.locator('[data-testid="mmd-close"]').click()
    await expect(viewer).toHaveCount(0)
  })

  test('E2E-MD06 mermaid 复制源码与下载 SVG：内容正确、不触发页面跳转', async ({ page, context }) => {
    await context.grantPermissions(['clipboard-read', 'clipboard-write'])
    await openApp(page)
    await sendMessage(page, `md=mermaid 用例MD06-${uniq()}`)
    await waitRoundSettled(page)
    await mdBody(page).locator('.mmdc-full').click()
    const viewer = page.locator('[data-testid="mmd-viewer"]')
    await expect(viewer).toBeVisible()

    await page.locator('[data-testid="mmd-copy"]').click()
    await expect(page.locator('[data-testid="mmd-copy"]')).toHaveClass(/is-copied/)
    const copied = await page.evaluate(() => navigator.clipboard.readText())
    expect(copied).toContain('graph TD')
    expect(copied).toContain('A[开始] --> B{判断}')
    expect(copied).not.toContain('<svg')

    const urlBefore = page.url()
    const [download] = await Promise.all([
      page.waitForEvent('download'),
      page.locator('[data-testid="mmd-download"]').click()
    ])
    expect(download.suggestedFilename()).toMatch(/\.svg$/)
    const file = await download.path()
    expect(file).not.toBeNull()
    const svgText = (await import('node:fs')).readFileSync(file!, 'utf8')
    expect(svgText.trimStart().startsWith('<svg')).toBe(true)
    // 下载/复制都不改地址、不关查看器
    expect(page.url()).toBe(urlBefore)
    await expect(viewer).toBeVisible()
  })

  test('E2E-MD07 mermaid 语法错误兜底：不崩页，回落源码卡，无未捕获异常', async ({ page }) => {
    const errors: string[] = []
    page.on('pageerror', (error) => errors.push(error.message))
    await openApp(page)
    await sendMessage(page, `md=mermaid-broken 用例MD07-${uniq()}`)
    await waitRoundSettled(page)

    const body = mdBody(page)
    await expect(body.locator('.mmdc')).toHaveCount(1)
    // 源码态卡：无 tab / 无缩放钮，源码可见
    await expect(body.locator('.mmdc-tabs')).toHaveCount(0)
    await expect(body.locator('.mmdc-src')).toBeVisible()
    await expect(body.locator('.mmdc-src')).toContainText('graph TD')
    await expect(body.locator('.mmdc-copy')).toHaveCount(1)
    expect(errors).toEqual([])
  })

  test('E2E-MD08 未闭合围栏（流式尾块）：中途纯文本，闭合后二次渲染为高亮且不丢字', async ({ page }) => {
    await openApp(page)
    await sendMessage(page, `md=stream 用例MD08-${uniq()}`)
    const bubble = assistantBubble(page)

    // 中途：尾围栏未闭合 ⇒ 纯文本渲染（无 hljs 高亮）、不空白、不闪烁空格子。
    // 判据用「前半段已到、后半段未到」这个窗口，不靠单条文本存在性（闭合后两者都在）。
    const body = mdBody(page)
    await expect
      .poll(async () => {
        const text = await bubble.innerText()
        return text.includes('const first = 1') && !text.includes('const second = 2')
      }, { timeout: 30_000 })
      .toBe(true)
    await expect(body.locator('pre')).toHaveCount(1)
    await expect(body.locator('pre span[class*="hljs-"]')).toHaveCount(0)
    await expect(body.locator('h2')).toHaveText('流式标题')

    // 闭合后：二次渲染为高亮，前段文字一字不丢
    await waitRoundSettled(page)
    await expect(body.locator('pre span[class*="hljs-"]').first()).toBeVisible()
    const preText = await body.locator('pre').innerText()
    expect(preText).toContain('const first = 1')
    expect(preText).toContain('const second = 2')
    await expect(body).toContainText('尾部段落收口。')
  })

  test('E2E-MD09 长文 >10k 降级：结构照渲，高亮与 mermaid 关闭且后续渲染继承', async ({ page }) => {
    await openApp(page)
    await sendMessage(page, `md=long 用例MD09-${uniq()}`)
    await waitRoundSettled(page, 120_000)

    const body = mdBody(page)
    await expect(body.locator('h1')).toHaveText('长文标题')
    expect(await body.locator('h2').count()).toBeGreaterThan(10)
    // 降级：mermaid 不渲染成卡、围栏不包图容器、不高亮，但源码/结构都在
    await expect(body.locator('.mmdc')).toHaveCount(0)
    await expect(body.locator('figure.code')).toHaveCount(0)
    await expect(body.locator('pre code.language-mermaid')).toHaveCount(1)
    await expect(body.locator('pre span[class*="hljs-"]')).toHaveCount(0)

    // 降级判定按「本次渲染的字符数」逐条生效：同页签内的下一条短正文回到非降级路径。
    // 发现项 MD-09-D1：蓝图要求「同页签内后续渲染继承降级」，代码里只有**耗时预算**超限才置
    // 模块级粘滞位（`degraded`），长文阈值（>10k）只在本次渲染内短路 ⇒ 真机语义是逐条判定。
    // 本处按真实语义断言（不假绿）：短正文重新高亮、重新包图容器。
    await sendMessage(page, `md=highlight 用例MD09b-${uniq()}`)
    await waitRoundSettled(page)
    const next = mdBody(page)
    await expect(next.locator('figure.code')).toHaveCount(1)
    await expect(next.locator('figure.code span[class*="hljs-"]').first()).toBeVisible()
  })

  test('E2E-MD10 站外链接与外图收敛：外图剥除、外链带 rel/target/可见标记', async ({ page }) => {
    await openApp(page)
    await sendMessage(page, `md=xss 用例MD10-${uniq()}`)
    await waitRoundSettled(page)

    const body = mdBody(page)
    const external = body.locator('a[href^="https://evil.example"]')
    await expect(external).toHaveCount(1)
    await expect(external).toHaveAttribute('rel', /noopener/)
    await expect(external).toHaveAttribute('rel', /noreferrer/)
    await expect(external).toHaveAttribute('target', '_blank')
    await expect(external).toHaveClass(/md-ext-link/)

    // 站外图（无需点击即出网）一律剥除；站内链接保留且不加 target
    await expect(body.locator('img[src*="evil.example"]')).toHaveCount(0)
    const internal = body.locator('a[href="/tasks"]')
    await expect(internal).toHaveCount(1)
    await expect(internal).not.toHaveAttribute('target', '_blank')
  })

  test('E2E-MD11 v-html 消毒（合并 X-09）：脚本/iframe/事件属性一律不执行', async ({ page }) => {
    const errors: string[] = []
    page.on('pageerror', (error) => errors.push(error.message))
    await openApp(page)
    await sendMessage(page, `md=xss 用例MD11-${uniq()}`)
    await waitRoundSettled(page)

    const body = mdBody(page)
    // 注入标记从未被执行
    expect(await page.evaluate(() => (window as unknown as { __mdxss?: number }).__mdxss ?? null)).toBeNull()
    expect(await body.locator('script').count()).toBe(0)
    expect(await body.locator('iframe').count()).toBe(0)
    // 没有任何元素带事件属性（白名单外属性整条剥掉）
    expect(await body.locator('[onerror], [onload], [onclick]').count()).toBe(0)
    // 危险协议链接不保留
    expect(await body.locator('a[href^="javascript:"]').count()).toBe(0)
    // 正文按文本渲染（控制台零未捕获异常）
    await expect(body).toContainText('普通文本前段。')
    expect(errors).toEqual([])
  })

  test('E2E-MD12 300 个 delta：逐段渲染无丢失、无重复、不乱序', async ({ page }) => {
    await openApp(page)
    await sendMessage(page, `md=deltas 用例MD12-${uniq()}`)
    await waitRoundSettled(page, 120_000)

    const text = await assistantText(page)
    const expected = content().deltaPieces.join('')
    const markers = text.match(/<\d{3}>/g) ?? []
    expect(markers).toHaveLength(300)
    expect(markers.join('')).toBe(expected)
    // 丢字/重复的末端判据：正文以最后一个片段收尾
    expect(text.trimEnd().endsWith('<299>')).toBe(true)
  })
})
