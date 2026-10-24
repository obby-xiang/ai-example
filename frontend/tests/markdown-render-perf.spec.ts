/**
 * 前端渲染性能专项（2026-10-24）：L-120 + 流式块级前缀缓存 + 高亮瘦身。
 *
 * 红测先行：断言按「产品口径」写死，实现落地前应红；实现落地后全绿且不再回退。
 * - L-120：24ms 单次耗时预算 + 页签级粘滞降级退役；>10k 单条短路保留（确定性、不粘滞）。
 * - 流式前缀缓存：分块路径与全文路径的**最终 HTML 必须逐字全等**（净化口径一字不改）。
 * - hljs：core + 常用语言白名单（未注册语言走 escape 兜底，行为兼容）。
 */
import { describe, expect, it, vi } from 'vitest'
import {
  DEGRADE_AT_CHARS,
  getMarkdownRenderStats,
  highlightLanguages,
  renderMarkdown,
  resetMarkdownRenderStats,
  resetStreamDebounce
} from '@/utils/markdown'

/** 流式渲染（绕开 75ms 防抖记账：本文件测的是前缀缓存，不是防抖窗口）。 */
function streamRender(text: string, blockKey: string) {
  resetStreamDebounce()
  return renderMarkdown(text, true, blockKey)
}

/** 全文渲染（无 blockKey ⇒ 部件不启用前缀缓存，可作分块路径的对照基准）。 */
function fullRender(text: string) {
  return renderMarkdown(text, true)
}

describe('L-120：24ms 耗时预算与页签级粘滞降级退役', () => {
  it('模块不再导出 RENDER_BUDGET_MS / isDegraded / resetDegradation；按条阈值仍在', async () => {
    const mod = (await import('@/utils/markdown')) as Record<string, unknown>
    expect('RENDER_BUDGET_MS' in mod).toBe(false)
    expect('isDegraded' in mod).toBe(false)
    expect('resetDegradation' in mod).toBe(false)
    expect(mod.DEGRADE_AT_CHARS).toBe(10_000)
  })

  it('墙钟越线不粘滞：慢渲染之后的下一条正文仍正常高亮', () => {
    const spy = vi.spyOn(performance, 'now')
    let clock = 0
    spy.mockImplementation(() => (clock += 1_000))
    try {
      const first = renderMarkdown('```js\nconst a = 1\n```', false)
      const second = renderMarkdown('```js\nconst b = 2\n```', false)
      expect(first.html).toContain('hljs-')
      expect(second.html).toContain('hljs-')
    } finally {
      spy.mockRestore()
    }
  })

  it('>10k 单条按次短路（本条无高亮/mermaid），下一条立即恢复', () => {
    const long = '```js\nconst a = 1\n```\n\n```mermaid\ngraph TD; A-->B;\n```\n\n' + 'x'.repeat(DEGRADE_AT_CHARS)
    const degraded = renderMarkdown(long, false)
    expect(degraded.html).not.toContain('hljs-')
    expect(degraded.mermaid).toHaveLength(0)
    const next = renderMarkdown('```js\nconst b = 2\n```', false)
    expect(next.html).toContain('hljs-')
  })
})

describe('流式块级「净化后 HTML」前缀缓存', () => {
  const HEAD = '# 标题\n\n' + '甲'.repeat(400) + '\n\n```js\nconst a = 1\n```\n\n第二段开始'

  it('逐帧增长：每一帧的分块产物都与全文渲染逐字全等', () => {
    resetMarkdownRenderStats()
    const doc =
      HEAD +
      '，继续增长。\n\n- 项目一\n- 项目二\n\n```ts\nconst z = 3\n```\n\n| A | B |\n| --- | --- |\n| 1 | 2 |\n\n' +
      '> 引用行 **粗** *斜* ~~删~~\n\n1. 有序一\n2. 有序二\n\n[链接](https://example.com/doc) 与 ![图](/api/file/x.png) 与 `inline`\n\n---\n\n末段收口。'
    for (let end = 20; end <= doc.length; end += 41) {
      const text = doc.slice(0, end)
      const chunked = streamRender(text, 'k-frames')
      const full = fullRender(text)
      expect(chunked.html, `帧 ${end} 分块与全文不一致`).toBe(full.html)
    }
    const stats = getMarkdownRenderStats()
    expect(stats.prefixHits).toBeGreaterThan(0) // 缓存确实被复用（不是每帧全量重解析）
    expect(stats.chunkRenders).toBeGreaterThan(0)
  })

  it('边界前移：新完成的块增量追加（不重渲整段前缀），产物仍与全文全等', () => {
    const t1 = '第一段' + '甲'.repeat(150) + '\n\n第二段尾块'
    const t2 = t1 + '\n\n第三段'
    const t3 = t2 + '\n\n第四段'
    resetMarkdownRenderStats()
    const r1 = streamRender(t1, 'k-grow')
    const r2 = streamRender(t2, 'k-grow')
    const r3 = streamRender(t3, 'k-grow')
    expect(r1.html).toBe(fullRender(t1).html)
    expect(r2.html).toBe(fullRender(t2).html)
    expect(r3.html).toBe(fullRender(t3).html)
    const stats = getMarkdownRenderStats()
    expect(stats.prefixBuilds).toBe(1) // 只有首帧建前缀
    expect(stats.prefixHits).toBe(2) // 后两帧都是「增量追加」
  })

  it('围栏 key 全局唯一且按文档序续号（跨前缀/新增块）', () => {
    const t1 = '```js\nconst a = 1\n```\n\n' + '甲'.repeat(200) + '\n\n第二段'
    streamRender(t1, 'k-keys')
    const t2 = t1 + '\n\n```ts\nconst b = 2\n```\n\n```python\nx = 1\n```\n\n结尾'
    const chunked = streamRender(t2, 'k-keys')
    const keys = [...chunked.html.matchAll(/data-fence-key="([^"]*)"/g)].map((m) => m[1])
    expect(keys).toEqual(['f1-js', 'f2-ts', 'f3-python'])
    expect(new Set(keys).size).toBe(keys.length)
    expect(chunked.html).toBe(fullRender(t2).html)
  })

  it('流式结束（streaming=false）整篇重渲：产物与一次性渲染全等，mermaid 登记同序', () => {
    const doc =
      '# 标题\n\n```mermaid\ngraph TD; A-->B;\n```\n\n正文一\n\n```js\nconst a = 1\n```\n\n正文二'
    const mid = streamRender(doc, 'k-end')
    expect(mid.html).not.toContain('<figure') // 流式期不包图容器（既有口径不变）
    const final = renderMarkdown(doc, false, 'k-end')
    const once = renderMarkdown(doc, false, 'k-end-once')
    expect(final.html).toBe(once.html)
    expect(final.mermaid).toEqual(once.mermaid)
    expect(final.html).toContain('<figure')
  })

  it('原生 HTML 跨块：前缀含 html token ⇒ 退回全文渲染（标签不被提前闭合）', () => {
    const t1 = '<div>\n\n开头段落' + '甲'.repeat(300) + '\n\n第二段'
    streamRender(t1, 'k-raw')
    const t2 = t1 + '继续'
    resetMarkdownRenderStats()
    const chunked = streamRender(t2, 'k-raw')
    const stats = getMarkdownRenderStats()
    expect(stats.prefixHits).toBe(0)
    expect(stats.chunkRenders).toBe(0)
    expect(chunked.html).toBe(fullRender(t2).html)
    // 全文口径：div 包住后续段落（分块净化会把开标签就地闭合 → <div></div><p …>）
    expect(chunked.html.replace(/\s+/g, '')).toContain('<div><pclass=')
  })

  it('原生 HTML 跨块（行内）：前缀段落含内联标签 ⇒ 同样退回全文渲染', () => {
    const t1 = '开头 <div>内联标签' + '甲'.repeat(200) + '\n\n第二段'
    streamRender(t1, 'k-raw-inline')
    const t2 = t1 + '继续'
    resetMarkdownRenderStats()
    const chunked = streamRender(t2, 'k-raw-inline')
    expect(getMarkdownRenderStats().prefixHits).toBe(0)
    expect(getMarkdownRenderStats().chunkRenders).toBe(0)
    expect(chunked.html).toBe(fullRender(t2).html)
  })

  it('引用式链接（env.references 非空）退回全文渲染', () => {
    const t1 = '前言\n\n' + '甲'.repeat(200) + '\n\n[引用][doc] 段落'
    streamRender(t1, 'k-ref')
    const t2 = t1 + '\n\n[doc]: https://example.com/doc'
    resetMarkdownRenderStats()
    const chunked = streamRender(t2, 'k-ref')
    expect(getMarkdownRenderStats().prefixHits).toBe(0)
    expect(chunked.html).toBe(fullRender(t2).html)
    expect(chunked.html).toContain('href="https://example.com/doc"')
  })

  it('分块净化不放宽 XSS 白名单（前缀与新增块同口径）', () => {
    const t1 = '正常段落\n\n' + '甲'.repeat(300) + '\n\n最后一段'
    streamRender(t1, 'k-xss')
    const t2 =
      t1 +
      '\n\n<img src="x" onerror="alert(1)">\n\n<script>alert(2)</script><iframe src="https://evil"></iframe>\n\n[恶意](javascript:alert(3))'
    const chunked = streamRender(t2, 'k-xss')
    expect(chunked.html).not.toContain('onerror')
    expect(chunked.html).not.toContain('<script')
    expect(chunked.html).not.toContain('iframe')
    expect(chunked.html).not.toContain('href="javascript:')
    expect(chunked.html).toBe(fullRender(t2).html)
  })
})

describe('高亮瘦身：hljs core + 常用语言白名单', () => {
  const COMMON = ['javascript', 'typescript', 'java', 'python', 'sql', 'json', 'bash', 'yaml', 'xml', 'css', 'markdown', 'diff']

  it('只注册常用语言（≤20 种），未注册语言退回 escape 兜底', () => {
    const langs = highlightLanguages()
    expect(langs.length).toBeLessThanOrEqual(20)
    expect(langs).toEqual(expect.arrayContaining(COMMON))
    // 未注册（原全量包里也不会高亮的生僻语言）：源码不丢、无高亮 span
    const unknown = renderMarkdown('```zzz-unknown\nconst a = 1\n```', false).html
    expect(unknown).toContain('const a = 1')
    expect(unknown).not.toContain('hljs-')
  })

  it('常用语言（含 html / md 别名）仍走 hljs 高亮', () => {
    const samples: Record<string, string> = {
      js: 'const a = 1',
      ts: 'const a: number = 1',
      java: 'public class A {}',
      python: 'def f():\n  return 1',
      sql: 'SELECT * FROM t',
      json: '{"a":1}',
      bash: 'echo hi',
      yaml: 'a: 1',
      xml: '<a href="x">y</a>',
      html: '<a href="x">y</a>',
      css: '.a { color: red }',
      md: '# t',
      diff: '-a\n+b'
    }
    for (const [lang, code] of Object.entries(samples)) {
      const html = renderMarkdown('```' + lang + '\n' + code + '\n```', false).html
      expect(html, `语言 ${lang} 未高亮`).toContain('hljs-')
    }
  })
})

describe('mermaid 配置收紧（锁定常量）', () => {
  it('maxTextSize 30000 / maxEdges 300 / 渲染缓存上限 64', async () => {
    const mod = (await import('@/utils/markdown')) as Record<string, unknown>
    expect(mod.MERMAID_MAX_TEXT_SIZE).toBe(30_000)
    expect(mod.MERMAID_MAX_EDGES).toBe(300)
    expect(mod.MERMAID_CACHE_MAX).toBe(64)
  })
})
